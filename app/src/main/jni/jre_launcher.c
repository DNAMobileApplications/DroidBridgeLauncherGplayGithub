/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * DroidBridge modifications:
 * Copyright (c) 2026 DNA Mobile Applications.
 * Modified for DroidBridge Android launcher integration.
 */

#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "log.h"
#include "utils.h"
#include "environ/environ.h"

#define FULL_VERSION "1.8.0-internal"
#define DOT_VERSION "1.8"

static const char** const_jargs = NULL;
static const jboolean const_javaw = JNI_FALSE;
static const jboolean const_cpwildcard = JNI_TRUE;
static const jint const_ergo_class = 0;

typedef jint JLI_Launch_func(int argc, char ** argv,
                             int jargc, const char** jargv,
                             int appclassc, const char** appclassv,
                             const char* fullversion,
                             const char* dotversion,
                             const char* pname,
                             const char* lname,
                             jboolean javaargs,
                             jboolean cpwildcard,
                             jboolean javaw,
                             jint ergo);

struct {
    sigset_t tracked_sigset;
    int pipe[2];
} abort_waiter_data;

_Noreturn extern void nominal_exit(int code, bool is_signal);

_Noreturn static void* abort_waiter_thread(void* extraArg) {
    pthread_sigmask(SIG_BLOCK, &abort_waiter_data.tracked_sigset, NULL);
    int signal;
    read(abort_waiter_data.pipe[0], &signal, sizeof(int));
    nominal_exit(signal, true);
}

_Noreturn static void abort_waiter_handler(int signal) {
    write(abort_waiter_data.pipe[1], &signal, sizeof(int));
    while (1) {}
}

static void abort_waiter_setup() {
    const static int tracked_signals[] = { SIGABRT };
    const static int ntracked = (sizeof(tracked_signals) / sizeof(tracked_signals[0]));
    struct sigaction sigactions[ntracked];
    sigemptyset(&abort_waiter_data.tracked_sigset);

    for (size_t i = 0; i < ntracked; i++) {
        sigaddset(&abort_waiter_data.tracked_sigset, tracked_signals[i]);
        memset(&sigactions[i], 0, sizeof(struct sigaction));
        sigactions[i].sa_handler = abort_waiter_handler;
        sigemptyset(&sigactions[i].sa_mask);
    }

    if (pipe(abort_waiter_data.pipe) != 0) {
        LOGE("Failed to set up aborter pipe: %s", strerror(errno));
        return;
    }

    pthread_t waiter_thread;
    int result = pthread_create(&waiter_thread, NULL, abort_waiter_thread, NULL);
    if (result != 0) {
        LOGE("Failed to start up waiter thread: %s", strerror(result));
        for (int i = 0; i < 2; i++) close(abort_waiter_data.pipe[i]);
        return;
    }

    pthread_detach(waiter_thread);

    for (size_t i = 0; i < ntracked; i++) {
        if (sigaction(tracked_signals[i], &sigactions[i], NULL) != 0) {
            LOGE("Failed to set signal handler for signal %i: %s", tracked_signals[i], strerror(errno));
        }
    }
}

static int string_contains(const char* haystack, const char* needle) {
    return haystack != NULL && needle != NULL && strstr(haystack, needle) != NULL;
}

static int env_is_true(const char* name) {
    const char* value = getenv(name);
    return value != NULL && (strcmp(value, "1") == 0 || strcasecmp(value, "true") == 0 || strcasecmp(value, "yes") == 0);
}

static int arg_property_is_true(const char* arg, const char* name) {
    if (arg == NULL || name == NULL) return 0;

    char expected[256];
    snprintf(expected, sizeof(expected), "-D%s=true", name);
    if (strcmp(arg, expected) == 0) return 1;

    snprintf(expected, sizeof(expected), "-D%s=1", name);
    if (strcmp(arg, expected) == 0) return 1;

    snprintf(expected, sizeof(expected), "-D%s=yes", name);
    return strcmp(arg, expected) == 0;
}

static int args_request_safe_jli_signals(int argc, char** argv) {
    for (int i = 0; i < argc; i++) {
        const char* arg = argv[i];
        if (arg_property_is_true(arg, "droidbridge.safe_jli_signals")) return 1;
        if (arg_property_is_true(arg, "droidbridge.safe_jli_signals")) return 1;
    }
    return 0;
}

static int should_preserve_android_signal_handlers(int argc, char** argv) {
    /*
     * Keep this opt-in only.
     *
     * The previous DroidBridge patch automatically enabled safe signal mode for
     * :java_gui, Cacio, CTCToolkit, and java.awt.headless=false launches. That
     * made the OptiFine GUI installer differ from Zalith's working flow and left
     * Android/Bionic handlers active when JLI_Launch entered HotSpot. On several
     * Android 14/15/16 devices that path aborts immediately with:
     *
     *   FORTIFY: pthread_mutex_lock called on a destroyed mutex
     *
     * Zalith's GUI installer does the normal standalone JVM signal reset, so do
     * the same unless Java explicitly asks for DroidBridge safe mode.
     */
    if (env_is_true("DROIDBRIDGE_SAFE_JLI_SIGNALS")) return 1;
    if (env_is_true("DROIDBRIDGE_SAFE_JLI_SIGNALS")) return 1;
    if (args_request_safe_jli_signals(argc, argv)) return 1;
    return 0;
}

static void reset_signals_for_standalone_jvm(void) {
    struct sigaction clean_sa;
    memset(&clean_sa, 0, sizeof(struct sigaction));

    for (int sigid = SIGHUP; sigid < NSIG; sigid++) {
        if (sigid == SIGKILL || sigid == SIGSTOP) continue;
        clean_sa.sa_handler = (sigid == SIGSEGV) ? SIG_IGN : SIG_DFL;
        sigemptyset(&clean_sa.sa_mask);
        clean_sa.sa_flags = 0;
        sigaction(sigid, &clean_sa, NULL);
    }
}

static void* try_dlopen_libjli_from_path(const char* searchPath) {
    if (searchPath == NULL || searchPath[0] == '\0') return NULL;

    char* copy = strdup(searchPath);
    if (copy == NULL) return NULL;

    void* handle = NULL;
    char* savePtr = NULL;
    char* token = strtok_r(copy, ":", &savePtr);
    while (token != NULL && handle == NULL) {
        if (token[0] != '\0') {
            char candidate[4096];
            snprintf(candidate, sizeof(candidate), "%s/libjli.so", token);
            handle = dlopen(candidate, RTLD_LAZY | RTLD_GLOBAL);
            if (handle != NULL) {
                LOGD("Found JLI lib from LD path: %s", candidate);
                break;
            }
        }
        token = strtok_r(NULL, ":", &savePtr);
    }

    free(copy);
    return handle;
}

static void* open_libjli() {
    void* libjli = dlopen("libjli.so", RTLD_LAZY | RTLD_GLOBAL);
    if (libjli != NULL) return libjli;

    const char* firstError = dlerror();
    LOGE("JLI lib by name failed: %s", firstError != NULL ? firstError : "unknown");

    libjli = try_dlopen_libjli_from_path(getenv("LD_LIBRARY_PATH"));
    if (libjli != NULL) return libjli;

    const char* explicitPath = getenv("DROIDBRIDGE_LIBJLI_PATH");
    if (explicitPath != NULL && explicitPath[0] != '\0') {
        libjli = dlopen(explicitPath, RTLD_LAZY | RTLD_GLOBAL);
        if (libjli != NULL) {
            LOGD("Found JLI lib from DROIDBRIDGE_LIBJLI_PATH: %s", explicitPath);
            return libjli;
        }
        LOGE("JLI explicit path failed: %s", dlerror());
    }

    return NULL;
}

static jint launchJVM(int margc, char** margv) {
    const int preserveSignals = should_preserve_android_signal_handlers(margc, margv);

    if (preserveSignals) {
        LOGD("Safe JLI signal mode active; preserving Android signal handlers");
    } else {
        LOGD("Standalone JVM signal mode active; resetting signal handlers");
        reset_signals_for_standalone_jvm();
        abort_waiter_setup();
    }

    void* libjli = open_libjli();
    if (libjli == NULL) {
        LOGE("JLI lib = NULL: %s", dlerror());
        return -1;
    }
    LOGD("Found JLI lib");

    JLI_Launch_func* pJLI_Launch = (JLI_Launch_func*) dlsym(libjli, "JLI_Launch");
    if (pJLI_Launch == NULL) {
        LOGE("JLI_Launch = NULL: %s", dlerror());
        return -1;
    }

    LOGD("Calling JLI_Launch");
    jint result = pJLI_Launch(margc, margv,
                              0, NULL,
                              0, NULL,
                              FULL_VERSION,
                              DOT_VERSION,
                              *margv,
                              *margv,
                              (const_jargs != NULL) ? JNI_TRUE : JNI_FALSE,
                              const_cpwildcard,
                              const_javaw,
                              const_ergo_class);
    LOGD("JLI_Launch returned %d", result);
    return result;
}

JNIEXPORT jint JNICALL Java_com_oracle_dalvik_VMLauncher_launchJVM(JNIEnv* env, jclass clazz, jobjectArray argsArray) {
    if (droidbridge_environ != NULL) {
        droidbridge_environ->dalvikJNIEnvPtr_ANDROID = env;
    }

    if (argsArray == NULL) {
        LOGE("Args array null, returning");
        return 0;
    }

    int argc = (*env)->GetArrayLength(env, argsArray);
    char** argv = convert_to_char_array(env, argsArray);
    LOGD("Done processing args");

    jint res = launchJVM(argc, argv);

    LOGD("Going to free args");
    free_char_array(env, argsArray, argv);
    LOGD("Free done");
    return res;
}

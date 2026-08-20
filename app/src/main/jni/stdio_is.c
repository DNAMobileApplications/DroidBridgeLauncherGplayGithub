/*
 * DroidBridge Launcher native runtime bridge component.
 *
 * Original project:
 *
 * Original license: GNU Lesser General Public License v3.0,
 * unless this file or a bundled component states a different license.
 *
 * DroidBridge modifications:
 * Copyright (c) 2026 DNA Mobile Applications.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */

#include <jni.h>
#include <sys/types.h>
#include <stdbool.h>
#include <unistd.h>
#include <pthread.h>
#include <stdio.h>
#include <fcntl.h>
#include <string.h>
#include <errno.h>
#include <stdlib.h>
#include <environ/environ.h>

#include "stdio_is.h"

//
// Created by maks on 17.02.21.
//

static volatile jobject exitTrap_ctx;
static volatile jclass exitTrap_exitClass;
static volatile jmethodID exitTrap_staticMethod;
static JavaVM *exitTrap_jvm;

static int pfd[2];
static pthread_t logger;
static jmethodID logger_onEventLogged;
static volatile jobject logListener = NULL;
static int latestlog_fd = -1;

static bool recordBuffer(char* buf, ssize_t len) {
    if (strstr(buf, "Session ID is")) return false;
    if (latestlog_fd != -1)
    {
        write(latestlog_fd, buf, len);
        fdatasync(latestlog_fd);
    }
    return true;
}

static void *logger_thread() {
    JNIEnv *env;
    jstring writeString;

    JavaVM* dvm = droidbridge_environ->dalvikJavaVMPtr;
    (*dvm)->AttachCurrentThread(dvm, &env, NULL);

    ssize_t  rsize;
    char buf[2050];

    while ((rsize = read(pfd[0], buf, sizeof(buf)-1)) > 0)
    {
        bool shouldRecordString = recordBuffer(buf, rsize); //record with newline int latestlog
        if (buf[rsize-1]=='\n')
        {
            rsize=rsize-1; //truncate
        }
        buf[rsize]=0x00;
        if (shouldRecordString && logListener != NULL && logger_onEventLogged != NULL)
        {
            writeString = (*env)->NewStringUTF(env, buf); //send to app without newline
            (*env)->CallVoidMethod(env, logListener, logger_onEventLogged, writeString);
            (*env)->DeleteLocalRef(env, writeString);
        }
    }
    (*dvm)->DetachCurrentThread(dvm);
    return NULL;
}

JNIEXPORT void JNICALL
Java_ca_dnamobile_droidbridgelauncher_runtime_Logger_begin(JNIEnv *env, __attribute((unused)) jclass clazz, jstring logPath) {
    if (latestlog_fd != -1)
    {
        int localfd = latestlog_fd;
        latestlog_fd = -1;
        close(localfd);
    }

    if (logger_onEventLogged == NULL)
    {
        jclass eventLogListener = (*env)->FindClass(env, "ca/dnamobile/droidbridgelauncher/runtime/Logger$eventLogListener");
        logger_onEventLogged = (*env)->GetMethodID(env, eventLogListener, "onEventLogged", "(Ljava/lang/String;)V");
    }

    jclass ioeClass = (*env)->FindClass(env, "java/io/IOException");


    setvbuf(stdout, 0, _IOLBF, 0); // make stdout line-buffered
    setvbuf(stderr, 0, _IONBF, 0); // make stderr unbuffered

    /* create the pipe and redirect stdout and stderr */
    pipe(pfd);
    dup2(pfd[1], 1);
    dup2(pfd[1], 2);

    /* open latestlog.txt for writing */
    const char* logFilePath = (*env)->GetStringUTFChars(env, logPath, NULL);
    latestlog_fd = open(logFilePath, O_WRONLY | O_TRUNC | O_CREAT, 0644);

    if (latestlog_fd == -1)
    {
        (*env)->ThrowNew(env, ioeClass, strerror(errno));
        (*env)->ReleaseStringUTFChars(env, logPath, logFilePath);
        return;
    }
    (*env)->ReleaseStringUTFChars(env, logPath, logFilePath);

    /* spawn the logging thread */
    int result = pthread_create(&logger, 0, logger_thread, 0);

    if (result != 0)
    {
        close(latestlog_fd);
        (*env)->ThrowNew(env, ioeClass, strerror(result));
    }
    pthread_detach(logger);
}

_Noreturn void nominal_exit(int code, bool is_signal) {
    JNIEnv *env;
    jint errorCode = (*exitTrap_jvm)->GetEnv(exitTrap_jvm, (void**)&env, JNI_VERSION_1_6);

    if (errorCode == JNI_EDETACHED)
        errorCode = (*exitTrap_jvm)->AttachCurrentThread(exitTrap_jvm, &env, NULL);

    if (errorCode != JNI_OK)
        killpg(getpgrp(), SIGTERM);

    if (code != 0 && exitTrap_exitClass != NULL && exitTrap_staticMethod != NULL && exitTrap_ctx != NULL)
        (*env)->CallStaticVoidMethod(env, exitTrap_exitClass, exitTrap_staticMethod, exitTrap_ctx, code, is_signal);

    // Delete the reference, not gonna need 'em later anyway
    if (exitTrap_ctx != NULL) (*env)->DeleteGlobalRef(env, exitTrap_ctx);
    if (exitTrap_exitClass != NULL) (*env)->DeleteGlobalRef(env, exitTrap_exitClass);

    // A hat trick, if you will
    // Call the Android System.exit() to perform Android's shutdown hooks and do a
    // fully clean exit.
    // After doing this, either of these will happen:
    // 1. Runtime calls exit() for real and it will be handled by ByteHook's recurse handler
    // and redirected back to the OS
    // 2. Zygote sends SIGTERM (no handling necessary, the process perishes)
    // 3. A different thread calls exit() and the hook will go through the exit_tripped path
    jclass systemClass = (*env)->FindClass(env,"java/lang/System");
    jmethodID exitMethod = (*env)->GetStaticMethodID(env, systemClass, "exit", "(I)V");
    (*env)->CallStaticVoidMethod(env, systemClass, exitMethod, 0);
    // System.exit() should not ever return, but the compiler doesn't know about that
    // so put a while loop here
    while(1) {}
}

JNIEXPORT void JNICALL Java_ca_dnamobile_droidbridgelauncher_runtime_Logger_appendToLog(JNIEnv *env, __attribute((unused)) jclass clazz, jstring text){
    if (text == NULL) return;

    jsize utfLength = (*env)->GetStringUTFLength(env, text);
    char *buffer = (char *) malloc((size_t) utfLength + 2U);
    if (buffer == NULL) return;

    (*env)->GetStringUTFRegion(
            env,
            text,
            0,
            (*env)->GetStringLength(env, text),
            buffer
    );

    // appendToLog() itself owns the final line break. Strip any CR/LF already
    // supplied by Java callers so one message cannot create an empty line.
    jsize cleanLength = utfLength;
    while (cleanLength > 0
            && (buffer[cleanLength - 1] == '\n' || buffer[cleanLength - 1] == '\r')) {
        cleanLength--;
    }

    buffer[cleanLength] = '\n';
    buffer[cleanLength + 1] = '\0';
    bool recorded = recordBuffer(buffer, cleanLength + 1);

    if (recorded && logListener != NULL && logger_onEventLogged != NULL) {
        buffer[cleanLength] = '\0';
        jstring cleanText = (*env)->NewStringUTF(env, buffer);
        if (cleanText != NULL) {
            (*env)->CallVoidMethod(env, logListener, logger_onEventLogged, cleanText);
            (*env)->DeleteLocalRef(env, cleanText);
        }
    }

    free(buffer);
}

JNIEXPORT void JNICALL
Java_ca_dnamobile_droidbridgelauncher_runtime_Logger_setLogListener(JNIEnv *env, __attribute((unused)) jclass clazz, jobject log_listener) {
    jobject oldListener = logListener;
    jobject newListener = NULL;

    if (log_listener != NULL)
    {
        // A Java listener can be registered before Logger.begin() redirects stdout.
        // Resolve the callback from the actual listener object so appendToLog() can
        // never reach CallVoidMethod with a null jmethodID.
        if (logger_onEventLogged == NULL)
        {
            jclass listenerClass = (*env)->GetObjectClass(env, log_listener);
            if (listenerClass != NULL)
            {
                jmethodID callback = (*env)->GetMethodID(
                        env,
                        listenerClass,
                        "onEventLogged",
                        "(Ljava/lang/String;)V"
                );
                (*env)->DeleteLocalRef(env, listenerClass);

                if ((*env)->ExceptionCheck(env) == JNI_TRUE)
                {
                    (*env)->ExceptionClear(env);
                    callback = NULL;
                }
                logger_onEventLogged = callback;
            }
        }

        if (logger_onEventLogged == NULL)
        {
            // Reject an unusable listener instead of leaving a partial JNI state.
            return;
        }

        newListener = (*env)->NewGlobalRef(env, log_listener);
        if (newListener == NULL) return;
    }

    logListener = newListener;
    if (oldListener != NULL && oldListener != newListener)
        (*env)->DeleteGlobalRef(env, oldListener);
}

JNIEXPORT void JNICALL
Java_ca_dnamobile_droidbridgelauncher_runtime_utils_JREUtils_setupExitMethod(JNIEnv *env, jclass clazz,
                                                        jobject context) {
    exitTrap_ctx = (*env)->NewGlobalRef(env, context);
    (*env)->GetJavaVM(env, &exitTrap_jvm);

    jclass localExitClass = (*env)->FindClass(env, "ca/dnamobile/droidbridgelauncher/ErrorActivity");
    if ((*env)->ExceptionCheck(env) == JNI_TRUE) {
        (*env)->ExceptionClear(env);
        localExitClass = NULL;
    }

    if (localExitClass == NULL) {
        exitTrap_exitClass = NULL;
        exitTrap_staticMethod = NULL;
        return;
    }

    exitTrap_exitClass = (*env)->NewGlobalRef(env, localExitClass);
    exitTrap_staticMethod = (*env)->GetStaticMethodID(env, exitTrap_exitClass, "showExitMessage", "(Landroid/content/Context;IZ)V");
    if ((*env)->ExceptionCheck(env) == JNI_TRUE) {
        (*env)->ExceptionClear(env);
        exitTrap_staticMethod = NULL;
    }
}
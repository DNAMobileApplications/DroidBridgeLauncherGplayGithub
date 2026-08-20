/*
 * Derived from the existing LGPL native bridge boundary used by DroidBridge.
 *
 * DroidBridge modifications:
 * Copyright (c) 2026 DNA Mobile Applications.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */

#include "droidbridge_renderspec.h"
#include "driver_helper/nsbypass.h"

#include <dlfcn.h>
#include <jni.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static droidbridge_renderspec_t g_droidbridge_renderspec = {0};
static char* g_egl_path = NULL;
static bool g_use_namespace = false;

static void* droidbridge_egl_acquire_namespace(const char* name) {
    return linker_ns_dlopen(name, RTLD_LOCAL | RTLD_NOW);
}

static void* droidbridge_egl_acquire_default(const char* name) {
    return dlopen(name, RTLD_NOW | RTLD_LOCAL);
}

const droidbridge_renderspec_t* droidbridge_renderspec_get(void) {
    return &g_droidbridge_renderspec;
}

static bool string_is_empty(const char* value) {
    return value == NULL || value[0] == '\0';
}


void droidbridge_renderspec_configure_native(
        const char* egl_path,
        droidbridge_acquire_egl_handle_t egl_acquire,
        int force_gles_context,
        int override_major_version) {
    if (string_is_empty(egl_path) || egl_acquire == NULL) {
        printf("DroidBridgeRenderSpec-v74: native configure skipped egl=%s acquire=%p\n",
               egl_path != NULL ? egl_path : "<null>",
               egl_acquire);
        return;
    }

    char* copied = strdup(egl_path);
    if (copied == NULL) {
        printf("DroidBridgeRenderSpec-v74: native configure strdup failed egl=%s\n", egl_path);
        return;
    }

    free(g_egl_path);
    g_egl_path = copied;
    g_droidbridge_renderspec.egl_path = g_egl_path;
    g_droidbridge_renderspec.egl_acquire = egl_acquire;
    g_droidbridge_renderspec.force_gles_context = force_gles_context;
    g_droidbridge_renderspec.override_major_version = override_major_version;
    g_droidbridge_renderspec.configured = true;

    printf("DroidBridgeRenderSpec-v74: native configured egl=%s acquire=%p forceGles=%d overrideMajor=%d\n",
           g_droidbridge_renderspec.egl_path,
           g_droidbridge_renderspec.egl_acquire,
           g_droidbridge_renderspec.force_gles_context,
           g_droidbridge_renderspec.override_major_version);
}

JNIEXPORT jboolean JNICALL
Java_ca_dnamobile_droidbridgelauncher_renderer_DroidBridgeRenderSpec_nativeConfigure(
        JNIEnv* env,
        jclass clazz,
        jstring eglPath,
        jstring namespacePath,
        jboolean useNamespace,
        jboolean forceGlesContext,
        jint overrideMajorVersion) {
    (void)clazz;

    if (eglPath == NULL) {
        printf("DroidBridgeRenderSpec: missing EGL path\n");
        return JNI_FALSE;
    }

    const char* egl_path_chars = (*env)->GetStringUTFChars(env, eglPath, NULL);
    if (string_is_empty(egl_path_chars)) {
        if (egl_path_chars != NULL) {
            (*env)->ReleaseStringUTFChars(env, eglPath, egl_path_chars);
        }
        printf("DroidBridgeRenderSpec: empty EGL path\n");
        return JNI_FALSE;
    }

    const char* namespace_path_chars = NULL;
    if (namespacePath != NULL) {
        namespace_path_chars = (*env)->GetStringUTFChars(env, namespacePath, NULL);
    }

    bool namespace_ready = false;
    if (useNamespace && !string_is_empty(namespace_path_chars)) {
        namespace_ready = linker_ns_load(namespace_path_chars);
        if (!namespace_ready) {
            printf("DroidBridgeRenderSpec: namespace load failed for path=%s\n", namespace_path_chars);
        }
    }

    droidbridge_acquire_egl_handle_t acquire = NULL;
    if (useNamespace && namespace_ready) {
        acquire = droidbridge_egl_acquire_namespace;
        g_use_namespace = true;
    } else {
        acquire = droidbridge_egl_acquire_default;
        g_use_namespace = false;
    }

    void* egl_handle = acquire(egl_path_chars);
    if (egl_handle == NULL) {
        const char* err = dlerror();
        printf("DroidBridgeRenderSpec: failed to load EGL=%s namespace=%d error=%s\n",
               egl_path_chars,
               g_use_namespace ? 1 : 0,
               err != NULL ? err : "unknown");

        if (namespace_path_chars != NULL) {
            (*env)->ReleaseStringUTFChars(env, namespacePath, namespace_path_chars);
        }
        (*env)->ReleaseStringUTFChars(env, eglPath, egl_path_chars);
        return JNI_FALSE;
    }

    char* copied = strdup(egl_path_chars);
    if (copied == NULL) {
        if (namespace_path_chars != NULL) {
            (*env)->ReleaseStringUTFChars(env, namespacePath, namespace_path_chars);
        }
        (*env)->ReleaseStringUTFChars(env, eglPath, egl_path_chars);
        return JNI_FALSE;
    }

    free(g_egl_path);
    g_egl_path = copied;

    g_droidbridge_renderspec.egl_path = g_egl_path;
    g_droidbridge_renderspec.egl_acquire = acquire;
    g_droidbridge_renderspec.force_gles_context = forceGlesContext ? 1 : 0;
    g_droidbridge_renderspec.override_major_version = (int)overrideMajorVersion;
    g_droidbridge_renderspec.configured = true;

    printf("DroidBridgeRenderSpec: configured egl=%s namespace=%d handle=%p forceGles=%d overrideMajor=%d\n",
           g_droidbridge_renderspec.egl_path,
           g_use_namespace ? 1 : 0,
           egl_handle,
           g_droidbridge_renderspec.force_gles_context,
           g_droidbridge_renderspec.override_major_version);

    if (namespace_path_chars != NULL) {
        (*env)->ReleaseStringUTFChars(env, namespacePath, namespace_path_chars);
    }
    (*env)->ReleaseStringUTFChars(env, eglPath, egl_path_chars);
    return JNI_TRUE;
}

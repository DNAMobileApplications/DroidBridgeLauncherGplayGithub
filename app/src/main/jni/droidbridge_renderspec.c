/*
 * Derived from the existing LGPL native bridge boundary used by DroidBridge.
 *
 * DroidBridge modifications:
 * Copyright (c) 2026 DNA Mobile Applications.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */

#include "droidbridge_renderspec.h"
#include "ctxbridges/egl_loader.h"
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

static const char* droidbridge_file_name_only(const char* path) {
    if (path == NULL || path[0] == '\0') return path;
    const char* slash = strrchr(path, '/');
    return slash != NULL ? slash + 1 : path;
}

static bool droidbridge_is_wrapped_renderer_provider(const char* path) {
    const char* base = droidbridge_file_name_only(path);
    if (base == NULL) return false;
    return strstr(base, "ltw") != NULL
            || strstr(base, "LTW") != NULL
            || strstr(base, "mobileglues") != NULL
            || strstr(base, "MobileGlues") != NULL;
}

static bool droidbridge_is_ltw_provider(const char* path) {
    const char* base = droidbridge_file_name_only(path);
    if (base == NULL) return false;
    return strstr(base, "ltw") != NULL || strstr(base, "LTW") != NULL;
}

static bool droidbridge_env_enabled(const char* name) {
    const char* value = getenv(name);
    return value != NULL && value[0] != '\0'
            && strcmp(value, "0") != 0
            && strcmp(value, "false") != 0;
}

static bool droidbridge_existing_egl_matches(const char* requested) {
    const char* loaded = droidbridge_egl_get_loaded_name();
    if (loaded == NULL || loaded[0] == '\0' || strcmp(loaded, "<none>") == 0) return false;
    const char* requested_base = droidbridge_file_name_only(requested);
    const char* loaded_base = droidbridge_file_name_only(loaded);
    if (requested_base == NULL || loaded_base == NULL) return false;
    size_t requested_len = strlen(requested_base);
    return strncmp(loaded_base, requested_base, requested_len) == 0
            && (loaded_base[requested_len] == '\0' || loaded_base[requested_len] == ' ');
}

static void* droidbridge_egl_acquire_existing_runtime(const char* ignored_name) {
    (void)ignored_name;
    void* existing = droidbridge_egl_get_handle();
    if (existing == NULL) {
        dlsym_EGL();
        existing = droidbridge_egl_get_handle();
    }
    return existing;
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
    bool reuse_runtime_wrapped_handle = false;
    if (!useNamespace && droidbridge_is_wrapped_renderer_provider(egl_path_chars)) {
        /*
         * Android 10 wrapped renderers are already loaded by GLBridge before
         * GameActivity configures RenderSpec. Reopening an extracted LTW or
         * MobileGlues plugin here can create a second linker-namespace image.
         * RenderPearl 26.3 compares GL procedure addresses from the OpenGL and
         * SDL paths, so both paths must share the exact same renderer handle.
         * This mirrors MojoExec's single namespace=0 EGL handle handoff.
         */
        void* existing = droidbridge_egl_get_handle();
        if (existing == NULL) {
            dlsym_EGL();
            existing = droidbridge_egl_get_handle();
        }
        if (existing != NULL && droidbridge_existing_egl_matches(egl_path_chars)) {
            acquire = droidbridge_egl_acquire_existing_runtime;
            g_use_namespace = false;
            reuse_runtime_wrapped_handle = true;
            printf("DroidBridgeRenderSpec-v75: reusing runtime EGL handle=%p loaded=%s requested=%s\n",
                   existing, droidbridge_egl_get_loaded_name(), egl_path_chars);
        }
    }

    if (acquire == NULL) {
        if (useNamespace && namespace_ready) {
            acquire = droidbridge_egl_acquire_namespace;
            g_use_namespace = true;
        } else {
            acquire = droidbridge_egl_acquire_default;
            g_use_namespace = false;
        }
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

    /*
     * Minecraft 26.2 on Android 10 still creates the live OpenGL context through
     * DroidBridge's GLFW/GLBridge path. RenderSpec has already opened LTW by its
     * absolute plugin path here, while the legacy EGL loader may have cached the
     * system libEGL fallback earlier. Adopt this exact LTW image into GLBridge
     * before any context is created. Minecraft 26.3 is excluded because its live
     * context is owned by the SDL3 compatibility provider.
     */
    if (droidbridge_is_ltw_provider(egl_path_chars)
            && !droidbridge_env_enabled("DROIDBRIDGE_SDL3_WRAPPED_OPENGL")) {
        if (droidbridge_egl_adopt_handle(egl_handle, egl_path_chars)) {
            acquire = droidbridge_egl_acquire_existing_runtime;
            reuse_runtime_wrapped_handle = true;
            printf("DroidBridgeRenderSpec-v76: adopted LTW provider for GLFW/GLBridge handle=%p path=%s\n",
                   egl_handle, egl_path_chars);
        } else {
            printf("DroidBridgeRenderSpec-v76: failed to adopt LTW provider for GLFW/GLBridge handle=%p path=%s\n",
                   egl_handle, egl_path_chars);
        }
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

    printf("DroidBridgeRenderSpec: configured egl=%s namespace=%d handle=%p reusedRuntime=%d forceGles=%d overrideMajor=%d\n",
           g_droidbridge_renderspec.egl_path,
           g_use_namespace ? 1 : 0,
           egl_handle,
           reuse_runtime_wrapped_handle ? 1 : 0,
           g_droidbridge_renderspec.force_gles_context,
           g_droidbridge_renderspec.override_major_version);

    if (namespace_path_chars != NULL) {
        (*env)->ReleaseStringUTFChars(env, namespacePath, namespace_path_chars);
    }
    (*env)->ReleaseStringUTFChars(env, eglPath, egl_path_chars);
    return JNI_TRUE;
}

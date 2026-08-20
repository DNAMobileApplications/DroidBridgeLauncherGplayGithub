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

//
// Created by Vera-Firefly on 17.01.2025.
//
#include <EGL/egl.h>
#include <stdbool.h>
#include <string.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <android/dlext.h>
#include <stdio.h>
#include "nsbypass.h"
#include "GL/gl.h"

//#define ADRENO_POSSIBLE
#ifdef ADRENO_POSSIBLE

bool checkAdrenoGraphics() {
    EGLDisplay eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (eglDisplay == EGL_NO_DISPLAY || eglInitialize(eglDisplay, NULL, NULL) != EGL_TRUE) 
        return false;

    EGLint egl_attributes[] = {
        EGL_BLUE_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_RED_SIZE, 8,
        EGL_ALPHA_SIZE, 8, EGL_DEPTH_SIZE, 24, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_NONE
    };

    EGLint num_configs = 0;
    if (eglChooseConfig(eglDisplay, egl_attributes, NULL, 0, &num_configs) != EGL_TRUE || num_configs == 0) {
        eglTerminate(eglDisplay);
        return false;
    }

    EGLConfig eglConfig;
    eglChooseConfig(eglDisplay, egl_attributes, &eglConfig, 1, &num_configs);

    const EGLint egl_context_attributes[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    EGLContext context = eglCreateContext(eglDisplay, eglConfig, EGL_NO_CONTEXT, egl_context_attributes);
    if (context == EGL_NO_CONTEXT) {
        eglTerminate(eglDisplay);
        return false;
    }

    if (eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, context) != EGL_TRUE) {
        eglDestroyContext(eglDisplay, context);
        eglTerminate(eglDisplay);
        return false;
    }

    const char* vendor = (const char*)glGetString(GL_VENDOR);
    const char* renderer = (const char*)glGetString(GL_RENDERER);

    bool is_adreno = (vendor && renderer && strcmp(vendor, "Qualcomm") == 0 && strstr(renderer, "Adreno") != NULL);

    eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(eglDisplay, context);
    eglTerminate(eglDisplay);

    return is_adreno;
}

static bool db_env_enabled(const char* name) {
    const char* value = getenv(name);
    return value != NULL && value[0] != '\0' && strcmp(value, "0") != 0
           && strcmp(value, "false") != 0 && strcmp(value, "FALSE") != 0;
}

void* loadTurnipVulkan() {
    /*
     * v60: fixed C string escaping from v59 and kept explicit Turnip loading.
     * If Java selected bundled Turnip/Zink, do not depend only on the tiny
     * system-EGL Adreno probe. Some Android builds can fail that probe even
     * though the device is Adreno and bundled libvulkan_freedreno.so is valid.
     */
    bool forcedTurnip = db_env_enabled("DROIDBRIDGE_LOAD_TURNIP")
            || db_env_enabled("DROIDBRIDGE_LOAD_TURNIP")
            || db_env_enabled("DROIDBRIDGE_USE_CUSTOM_TURNIP");
    if (!forcedTurnip && !checkAdrenoGraphics()) {
        printf("AdrenoSupp-v68: Adreno probe failed and Turnip was not forced.\n");
        return NULL;
    }

    const char* native_dir = getenv("DRIVER_PATH");
    const char* cache_dir = getenv("TMPDIR");

    if (!native_dir || native_dir[0] == '\0') {
        native_dir = getenv("DROIDBRIDGE_TURNIP_DRIVER_DIR");
    }
    if (!native_dir || native_dir[0] == '\0') {
        printf("AdrenoSupp-v68: no DRIVER_PATH/DROIDBRIDGE_TURNIP_DRIVER_DIR for Turnip.\n");
        return NULL;
    }
    if (!cache_dir || cache_dir[0] == '\0') {
        cache_dir = native_dir;
    }

    if (!linker_ns_load(native_dir)) {
        printf("AdrenoSupp-v68: linker_ns_load failed for %s.\n", native_dir);
        return NULL;
    }

    void* linkerhook = linker_ns_dlopen("liblinkerhook.so", RTLD_LOCAL | RTLD_NOW);
    if (!linkerhook) {
        printf("AdrenoSupp-v68: failed to load liblinkerhook.so.\n");
        return NULL;
    }

    void* turnip_driver_handle = linker_ns_dlopen("libvulkan_freedreno.so", RTLD_LOCAL | RTLD_NOW);
    if (!turnip_driver_handle) {
        printf("AdrenoSupp-v68: failed to load libvulkan_freedreno.so.\n");
        dlclose(linkerhook);
        return NULL;
    }

    void* dl_android = linker_ns_dlopen("libdl_android.so", RTLD_LOCAL | RTLD_LAZY);
    if (!dl_android) {
        printf("AdrenoSupp-v68: failed to load libdl_android.so.\n");
        dlclose(linkerhook);
        dlclose(turnip_driver_handle);
        return NULL;
    }

    void* android_get_exported_namespace = dlsym(dl_android, "android_get_exported_namespace");
    void (*linkerhookPassHandles)(void*, void*, void*) = dlsym(linkerhook, "linker_hook_set_handles");

    if (!linkerhookPassHandles || !android_get_exported_namespace) {
        printf("AdrenoSupp-v68: linkerhook exported namespace symbols missing.\n");
        dlclose(dl_android);
        dlclose(linkerhook);
        dlclose(turnip_driver_handle);
        return NULL;
    }

    linkerhookPassHandles(turnip_driver_handle, android_dlopen_ext, android_get_exported_namespace);

    void* libvulkan = linker_ns_dlopen_unique_named(cache_dir, "libvulkan.so", "libdbrvlk.so", RTLD_LOCAL | RTLD_NOW);
    if (!libvulkan) {
        printf("AdrenoSupp-v68: failed to load.\n");
        dlclose(dl_android);
        dlclose(linkerhook);
        dlclose(turnip_driver_handle);
        return NULL;
    }

    printf("AdrenoSupp-v68: Turnip ready using libdbrvlk.so Vulkan loader alias from %s, ptr=%p\n", native_dir, libvulkan);
    return libvulkan;
}

#endif
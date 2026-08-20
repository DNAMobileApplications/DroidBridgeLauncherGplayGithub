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
// Modifiled by Vera-Firefly on 15.01.2025.
//
#include <stdio.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <string.h>
#include "environ/environ.h"
#include "br_loader.h"
#include "osmesa_loader.h"
#include "renderer_config.h"

GLboolean (*OSMesaMakeCurrent_p) (OSMesaContext ctx, void *buffer, GLenum type, GLsizei width, GLsizei height);
OSMesaContext (*OSMesaGetCurrentContext_p) (void);
OSMesaContext (*OSMesaCreateContext_p) (GLenum format, OSMesaContext sharelist);
OSMesaContext (*OSMesaCreateContextAttribs_p) (const int *attribList, OSMesaContext sharelist);
OSMesaContext (*OSMesaCreateContextExt_p) (GLenum format, GLint depthBits, GLint stencilBits, GLint accumBits, OSMesaContext sharelist);
void (*OSMesaDestroyContext_p) (OSMesaContext ctx);
void (*OSMesaFlushFrontbuffer_p) ();
void (*OSMesaPixelStore_p) (GLint pname, GLint value);
GLubyte* (*glGetString_p) (GLenum name);
void (*glFinish_p) (void);
void (*glClearColor_p) (GLclampf red, GLclampf green, GLclampf blue, GLclampf alpha);
void (*glClear_p) (GLbitfield mask);
void (*glReadPixels_p) (GLint x, GLint y, GLsizei width, GLsizei height, GLenum format, GLenum type, void* data);
void (*glReadBuffer_p) (GLenum mode);

static void droidbridge_noop_OSMesaFlushFrontbuffer(void) {
    /*
     * Older legacy Mesa 23.x OSMesa builds do not export OSMesaFlushFrontbuffer.
     * DroidBridge does not call this directly, but resolving it as a required
     * symbol made mixed Turnip/OSMesa packages fail during startup. Keep the
     * symbol optional and use glFinish as the closest safe fallback when present.
     */
    if (glFinish_p) {
        glFinish_p();
    }
}

bool is_renderer_vulkan() {
    return (droidbridge_environ->config_renderer == RENDERER_VK_ZINK
         || droidbridge_environ->config_renderer == RENDERER_VIRGL);
}

char* construct_main_path(const char* mesa_name, const char* droidbridge_native_dir) {
    char* main_path = NULL;if (mesa_name != NULL && strncmp(mesa_name, "/data", 5) == 0) {
        main_path = strdup(mesa_name);
    } else {
        if (asprintf(&main_path, "%s/%s", droidbridge_native_dir, mesa_name) == -1) {
            return NULL;
        }
    }
    return main_path;
}

static bool env_nonempty(const char* name) {
    const char* value = getenv(name);
    return value != NULL && value[0] != '\0';
}

static bool env_contains(const char* name, const char* needle) {
    const char* value = getenv(name);
    return value != NULL && needle != NULL && strstr(value, needle) != NULL;
}

static void droidbridge_sanitize_osmesa23_zink_env(void) {
    const char* renderer = getenv("DROIDBRIDGE_RENDERER");
    const char* lib = getenv("LIB_MESA_NAME");
    bool osmesa8 = lib != NULL && strstr(lib, "libOSMesa_8.so") != NULL;
    bool zink = (renderer != NULL && strcmp(renderer, "vulkan_zink") == 0)
            || env_contains("GALLIUM_DRIVER", "zink")
            || env_contains("MESA_LOADER_DRIVER_OVERRIDE", "zink");
    if (!osmesa8 || !zink) return;

    const char* ext = getenv("MESA_EXTENSION_OVERRIDE");
    if (ext != NULL && ext[0] != '\0') {
        fprintf(stderr, "DroidBridge OSM Loader v54: clearing MESA_EXTENSION_OVERRIDE for libOSMesa_8 Zink; old value='%s'\n", ext);
        unsetenv("MESA_EXTENSION_OVERRIDE");
    }

    const char* glver = getenv("MESA_GL_VERSION_OVERRIDE");
    if (glver == NULL || strstr(glver, "COMPAT") != NULL || strstr(glver, "compat") != NULL) {
        fprintf(stderr, "DroidBridge OSM Loader v54: keeping plain MESA_GL_VERSION_OVERRIDE for libOSMesa_8 Zink (old=%s)\n", glver ? glver : "<null>");
        setenv("MESA_GL_VERSION_OVERRIDE", "4.3", 1);
    }

    if (!env_nonempty("MESA_GLSL_VERSION_OVERRIDE")) {
        setenv("MESA_GLSL_VERSION_OVERRIDE", "430", 1);
    }

    setenv("DROIDBRIDGE_OSMESA_ZINK_V54", "1", 1);
}

void dlsym_OSMesa() {
    if (!is_renderer_vulkan()) return;
    droidbridge_sanitize_osmesa23_zink_env();

    char* mesa_name = getenv("LIB_MESA_NAME");
    char* droidbridge_native_dir = getenv("DROIDBRIDGE_NATIVEDIR");

    char* main_path = construct_main_path(mesa_name, droidbridge_native_dir);
    if (!main_path) {
        fprintf(stderr, "Error: Failed to construct main path.\n");
        abort();
    }

    void* dl_handle = dlopen(main_path, RTLD_LOCAL | RTLD_LAZY);
    free(main_path);
    if (!dl_handle) {
        fprintf(stderr, "Error: Failed to open library: %s\n", dlerror());
        abort();
    }

    /*
     * v54: Resolve exported OSMesa/core GL symbols with dlsym first.
     * The Mesa 23.0.4 libOSMesa_8.so used by several Android launcher packs can
     * return dispatch/trampoline pointers from OSMesaGetProcAddress before a
     * context exists. On the uploaded Adreno 740 logs this died inside
     * libOSMesa_8.so before any context-created diagnostics were emitted.
     */
    OSMesaMakeCurrent_p = load_symbol(dl_handle, "OSMesaMakeCurrent");
    OSMesaGetCurrentContext_p = load_symbol_optional(dl_handle, "OSMesaGetCurrentContext");
    OSMesaCreateContext_p = load_symbol(dl_handle, "OSMesaCreateContext");
    OSMesaCreateContextExt_p = load_symbol_optional(dl_handle, "OSMesaCreateContextExt");
    OSMesaCreateContextAttribs_p = load_symbol_optional(dl_handle, "OSMesaCreateContextAttribs");
    OSMesaDestroyContext_p = load_symbol_optional(dl_handle, "OSMesaDestroyContext");
    OSMesaPixelStore_p = load_symbol(dl_handle, "OSMesaPixelStore");
    glGetString_p = load_symbol(dl_handle, "glGetString");
    glClearColor_p = load_symbol_optional(dl_handle, "glClearColor");
    glClear_p = load_symbol_optional(dl_handle, "glClear");
    glFinish_p = load_symbol(dl_handle, "glFinish");
    glReadPixels_p = load_symbol_optional(dl_handle, "glReadPixels");
    glReadBuffer_p = load_symbol_optional(dl_handle, "glReadBuffer");

    fprintf(stderr, "DroidBridge OSM Loader v54: symbols makeCurrent=%p create=%p createExt=%p createAttribs=%p pixelStore=%p glGetString=%p glFinish=%p\n",
            OSMesaMakeCurrent_p, OSMesaCreateContext_p, OSMesaCreateContextExt_p, OSMesaCreateContextAttribs_p,
            OSMesaPixelStore_p, glGetString_p, glFinish_p);

    OSMesaFlushFrontbuffer_p = load_symbol_optional(dl_handle, "OSMesaFlushFrontbuffer");
    if (!OSMesaFlushFrontbuffer_p) {
        fprintf(stderr, "DroidBridge OSM Loader v54: optional OSMesaFlushFrontbuffer missing; using glFinish/no-op fallback.\n");
        OSMesaFlushFrontbuffer_p = droidbridge_noop_OSMesaFlushFrontbuffer;
    }

    if (!OSMesaMakeCurrent_p || !OSMesaCreateContext_p || !OSMesaPixelStore_p || !glGetString_p || !glFinish_p) {
        fprintf(stderr, "DroidBridge OSM Loader: one or more required OSMesa/OpenGL symbols are missing. makeCurrent=%p createContext=%p pixelStore=%p glGetString=%p glFinish=%p\n",
                OSMesaMakeCurrent_p, OSMesaCreateContext_p, OSMesaPixelStore_p, glGetString_p, glFinish_p);
    }

}
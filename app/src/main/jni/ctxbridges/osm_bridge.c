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
// Created by maks on 18.10.2023.
//
#include <malloc.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <environ/environ.h>
#include <android/log.h>
#include "osm_bridge.h"

static const char* g_LogTag = "GLBridge";
static __thread osm_render_window_t* currentBundle;
// a tiny buffer for rendering when there's nowhere t render
static char no_render_buffer[64 * 64 * 4];
static bool hasSetNoRendererBuffer = false;

// Its not in a .h file because it is not supposed to be used outsife of this file.
void setNativeWindowSwapInterval(struct ANativeWindow* nativeWindow, int swapInterval);

bool osm_init() {
    dlsym_OSMesa();
    if (!OSMesaMakeCurrent_p || !OSMesaCreateContext_p || !OSMesaPixelStore_p || !glFinish_p) {
        __android_log_print(ANDROID_LOG_ERROR, g_LogTag,
                            "OSMesa init has missing required symbols: makeCurrent=%p createContext=%p pixelStore=%p glFinish=%p",
                            OSMesaMakeCurrent_p, OSMesaCreateContext_p, OSMesaPixelStore_p, glFinish_p);
        return false;
    }
    return true;
}

osm_render_window_t* osm_get_current() {
    return currentBundle;
}

osm_render_window_t* osm_init_context(osm_render_window_t* share) {
    if (!OSMesaCreateContext_p) {
        __android_log_print(ANDROID_LOG_ERROR, g_LogTag, "OSMesaCreateContext_p is NULL; cannot create context");
        return NULL;
    }

    osm_render_window_t* render_window = malloc(sizeof(osm_render_window_t));
    if(render_window == NULL) return NULL;
    memset(render_window, 0, sizeof(osm_render_window_t));
    OSMesaContext osmesa_share = NULL;
    if(share != NULL) osmesa_share = share->context;
    OSMesaContext context = NULL;

    /*
     * v54: Do not use OSMesaCreateContextAttribs by default for Mesa 23.0.4
     * libOSMesa_8.so. The uploaded logs die inside libOSMesa_8.so before the
     * v53 Android-log context diagnostics appear, which strongly suggests the
     * context creation path itself is exploding. Prefer the older
     * OSMesaCreateContextExt/OSMesaCreateContext paths used by classic legacy Android launcher
     * OSMesa bridges. Set DROIDBRIDGE_OSMESA_ENABLE_ATTRIBS=1 only for testing.
     */
    const char* enableAttribs = getenv("DROIDBRIDGE_OSMESA_ENABLE_ATTRIBS");
    bool allowAttribs = enableAttribs != NULL && strcmp(enableAttribs, "1") == 0;

    if (context == NULL && OSMesaCreateContextExt_p != NULL) {
        fprintf(stderr, "DroidBridge OSM v59: attempting OSMesaCreateContextExt(GL_RGBA,24,8,0) share=%p\n", osmesa_share);
        context = OSMesaCreateContextExt_p(GL_RGBA, 24, 8, 0, osmesa_share);
        fprintf(stderr, "DroidBridge OSM v59: OSMesaCreateContextExt returned %p\n", context);
    }

    if (context == NULL) {
        fprintf(stderr, "DroidBridge OSM v59: attempting legacy OSMesaCreateContext(GL_RGBA) share=%p\n", osmesa_share);
        context = OSMesaCreateContext_p(GL_RGBA, osmesa_share);
        fprintf(stderr, "DroidBridge OSM v59: OSMesaCreateContext returned %p\n", context);
    }

    if (context == NULL && allowAttribs && OSMesaCreateContextAttribs_p != NULL) {
        int attribs[] = {
                OSMESA_FORMAT, GL_RGBA,
                OSMESA_PROFILE, OSMESA_COMPAT_PROFILE,
                OSMESA_CONTEXT_MAJOR_VERSION, 4,
                OSMESA_CONTEXT_MINOR_VERSION, 3,
                0
        };
        fprintf(stderr, "DroidBridge OSM v59: attempting opt-in OSMesaCreateContextAttribs GL 4.3 compat share=%p\n", osmesa_share);
        context = OSMesaCreateContextAttribs_p(attribs, osmesa_share);
        fprintf(stderr, "DroidBridge OSM v59: OSMesaCreateContextAttribs returned %p\n", context);
    }

    if(context == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, g_LogTag, "OSMesa context creation returned NULL share=%p", osmesa_share);
        free(render_window);
        return NULL;
    }
    render_window->context = context;
    fprintf(stderr, "DroidBridge OSM v59: OSMesa context created context=%p share=%p\n", context, osmesa_share);
    __android_log_print(ANDROID_LOG_INFO, g_LogTag, "OSMesa context created v59 context=%p share=%p", context, osmesa_share);
    return render_window;
}

void osm_set_no_render_buffer(ANativeWindow_Buffer* buffer) {
    buffer->bits = &no_render_buffer;
    buffer->width = 64;
    buffer->height = 64;
    buffer->stride = 64;
}

void osm_swap_surfaces(osm_render_window_t* bundle) {
    if(bundle->nativeSurface != NULL && bundle->newNativeSurface != bundle->nativeSurface) {
        if(!bundle->disable_rendering) {
            __android_log_print(ANDROID_LOG_INFO, g_LogTag, "Unlocking for cleanup...");
            ANativeWindow_unlockAndPost(bundle->nativeSurface);
        }
        ANativeWindow_release(bundle->nativeSurface);
    }
    if(bundle->newNativeSurface != NULL) {
        __android_log_print(ANDROID_LOG_ERROR, g_LogTag, "Switching to new native surface");
        bundle->nativeSurface = bundle->newNativeSurface;
        bundle->newNativeSurface = NULL;
        ANativeWindow_acquire(bundle->nativeSurface);
        ANativeWindow_setBuffersGeometry(bundle->nativeSurface, 0, 0, WINDOW_FORMAT_RGBX_8888);
        bundle->disable_rendering = false;
        return;
    }else {
        __android_log_print(ANDROID_LOG_ERROR, g_LogTag,
                            "No new native surface, switching to dummy framebuffer");
        bundle->nativeSurface = NULL;
        osm_set_no_render_buffer(&bundle->buffer);
        bundle->disable_rendering = true;
    }

}

void osm_release_window() {
    currentBundle->newNativeSurface = NULL;
    osm_swap_surfaces(currentBundle);
}

void osm_apply_current_ll() {
    if (currentBundle == NULL || currentBundle->context == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, g_LogTag, "osm_apply_current_ll without context currentBundle=%p", currentBundle);
        return;
    }

    ANativeWindow_Buffer* buffer = &currentBundle->buffer;
    if (buffer->bits == NULL || buffer->width <= 0 || buffer->height <= 0) {
        __android_log_print(ANDROID_LOG_WARN, g_LogTag,
                            "OSMesa buffer not ready; using dummy buffer bits=%p size=%dx%d stride=%d",
                            buffer->bits, buffer->width, buffer->height, buffer->stride);
        osm_set_no_render_buffer(buffer);
    }

    fprintf(stderr, "DroidBridge OSM v59: OSMesaMakeCurrent start context=%p bits=%p size=%dx%d stride=%d\n",
            currentBundle->context, buffer->bits, buffer->width, buffer->height, buffer->stride);
    GLboolean madeCurrent = OSMesaMakeCurrent_p(currentBundle->context, buffer->bits, GL_UNSIGNED_BYTE, buffer->width, buffer->height);
    fprintf(stderr, "DroidBridge OSM v59: OSMesaMakeCurrent returned %d\n", madeCurrent);
    if (!madeCurrent) {
        __android_log_print(ANDROID_LOG_ERROR, g_LogTag,
                            "OSMesaMakeCurrent failed context=%p bits=%p size=%dx%d stride=%d",
                            currentBundle->context, buffer->bits, buffer->width, buffer->height, buffer->stride);
        return;
    }

    if (OSMesaGetCurrentContext_p != NULL && OSMesaGetCurrentContext_p() == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, g_LogTag, "OSMesaMakeCurrent returned true but current context is NULL");
    }

    if(buffer->stride != currentBundle->last_stride)
        OSMesaPixelStore_p(OSMESA_ROW_LENGTH, buffer->stride);
    currentBundle->last_stride = buffer->stride;
}

void osm_make_current(osm_render_window_t* bundle) {
    if(bundle == NULL) {
        //technically this does nothing as its not possible to unbind a context in OSMesa
        OSMesaMakeCurrent_p(NULL, NULL, 0, 0, 0);
        currentBundle = NULL;
        return;
    }
    bool hasSetMainWindow = false;
    currentBundle = bundle;
    if(droidbridge_environ->mainWindowBundle == NULL) {
        droidbridge_environ->mainWindowBundle = (basic_render_window_t*) bundle;
        __android_log_print(ANDROID_LOG_INFO, g_LogTag, "Main window bundle is now %p", droidbridge_environ->mainWindowBundle);
        droidbridge_environ->mainWindowBundle->newNativeSurface = droidbridge_environ->droidbridgeWindow;
        hasSetMainWindow = true;
    }
    if(bundle->nativeSurface == NULL) {
        //prepare the buffer for our first render!
        osm_swap_surfaces(bundle);
        if(hasSetMainWindow) droidbridge_environ->mainWindowBundle->state = STATE_RENDERER_ALIVE;
    }
    if (!hasSetNoRendererBuffer)
    {
        osm_set_no_render_buffer(&bundle->buffer);
        hasSetNoRendererBuffer = true;
    }
    osm_apply_current_ll();
    OSMesaPixelStore_p(OSMESA_Y_UP,0);
}

void osm_swap_buffers() {
    if(currentBundle->state == STATE_RENDERER_NEW_WINDOW) {
        osm_swap_surfaces(currentBundle);
        currentBundle->state = STATE_RENDERER_ALIVE;
    }

    if(currentBundle->nativeSurface != NULL && !currentBundle->disable_rendering)
        if(ANativeWindow_lock(currentBundle->nativeSurface, &currentBundle->buffer, NULL) != 0)
            osm_release_window();

    osm_apply_current_ll();
    glFinish_p(); // this will force osmesa to write the last rendered image into the buffer

    if(currentBundle->nativeSurface != NULL && !currentBundle->disable_rendering)
        if(ANativeWindow_unlockAndPost(currentBundle->nativeSurface) != 0)
            osm_release_window();
}

void osm_setup_window() {
    if(droidbridge_environ->mainWindowBundle != NULL) {
        __android_log_print(ANDROID_LOG_INFO, g_LogTag, "Main window bundle is not NULL, changing state");
        droidbridge_environ->mainWindowBundle->state = STATE_RENDERER_NEW_WINDOW;
        droidbridge_environ->mainWindowBundle->newNativeSurface = droidbridge_environ->droidbridgeWindow;
    }
}

void osm_swap_interval(int swapInterval) {
    if(droidbridge_environ->mainWindowBundle != NULL && droidbridge_environ->mainWindowBundle->nativeSurface != NULL) {
        setNativeWindowSwapInterval(droidbridge_environ->mainWindowBundle->nativeSurface, swapInterval);
    }
}

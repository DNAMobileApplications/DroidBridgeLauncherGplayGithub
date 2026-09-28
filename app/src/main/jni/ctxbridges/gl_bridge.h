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
// Created by maks on 17.09.2022.
//
#include <EGL//egl.h>
#include <stdbool.h>
#ifndef DROIDBRIDGE_RUNTIME_GL_BRIDGE_H
#define DROIDBRIDGE_RUNTIME_GL_BRIDGE_H

typedef struct {
    char       state;
    struct ANativeWindow *nativeSurface;
    struct ANativeWindow *newNativeSurface;
    EGLConfig  config;
    EGLint     format;
    EGLContext context;
    EGLSurface surface;
} gl_render_window_t;

bool gl_init();
gl_render_window_t* gl_get_current();
gl_render_window_t* gl_init_context(gl_render_window_t* share);
void gl_make_current(gl_render_window_t* bundle);
void gl_destroy_context(gl_render_window_t* bundle);
void gl_swap_buffers();
void gl_setup_window();
void gl_swap_interval(int swapInterval);


#endif //DROIDBRIDGE_RUNTIME_GL_BRIDGE_H

/*
 * Derived from the existing LGPL native bridge boundary used by DroidBridge.
 *
 * DroidBridge modifications:
 * Copyright (c) 2026 DNA Mobile Applications.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */

#ifndef DROIDBRIDGE_RENDERSPEC_H
#define DROIDBRIDGE_RENDERSPEC_H

#include <stdbool.h>

typedef void* (*droidbridge_acquire_egl_handle_t)(const char* name);

typedef struct {
    droidbridge_acquire_egl_handle_t egl_acquire;
    const char* egl_path;
    int force_gles_context;
    int override_major_version;
    bool configured;
} droidbridge_renderspec_t;

const droidbridge_renderspec_t* droidbridge_renderspec_get(void);

/* v74: native-side RenderSpec configuration used by the EGL loader.
 * This lets the already-loaded Mesa EGL handle become the configured RenderSpec
 * before LWJGL asks for the OpenGL provider, matching DroidBridge's "replacing OpenGL
 * with renderspec driver" path instead of falling back through env guesses.
 */
void droidbridge_renderspec_configure_native(
        const char* egl_path,
        droidbridge_acquire_egl_handle_t egl_acquire,
        int force_gles_context,
        int override_major_version);

#endif // DROIDBRIDGE_RENDERSPEC_H

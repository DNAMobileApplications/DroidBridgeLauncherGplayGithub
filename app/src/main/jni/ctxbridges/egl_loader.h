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
// Created by maks on 21.09.2022.
//
#include <EGL/egl.h>
#include <stdbool.h>
#ifndef DROIDBRIDGE_RUNTIME_EGL_LOADER_H
#define DROIDBRIDGE_RUNTIME_EGL_LOADER_H

extern EGLBoolean (*eglMakeCurrent_p) (EGLDisplay dpy, EGLSurface draw, EGLSurface read, EGLContext ctx);
extern EGLBoolean (*eglDestroyContext_p) (EGLDisplay dpy, EGLContext ctx);
extern EGLBoolean (*eglDestroySurface_p) (EGLDisplay dpy, EGLSurface surface);
extern EGLBoolean (*eglTerminate_p) (EGLDisplay dpy);
extern EGLBoolean (*eglReleaseThread_p) (void);
extern EGLContext (*eglGetCurrentContext_p) (void);
extern EGLDisplay (*eglGetDisplay_p) (NativeDisplayType display);
extern EGLDisplay (*eglGetPlatformDisplay_p) (EGLenum platform, void* native_display, const EGLint* attrib_list);
extern EGLBoolean (*eglInitialize_p) (EGLDisplay dpy, EGLint *major, EGLint *minor);
extern EGLBoolean (*eglChooseConfig_p) (EGLDisplay dpy, const EGLint *attrib_list, EGLConfig *configs, EGLint config_size, EGLint *num_config);
extern EGLBoolean (*eglGetConfigAttrib_p) (EGLDisplay dpy, EGLConfig config, EGLint attribute, EGLint *value);
extern EGLBoolean (*eglBindAPI_p) (EGLenum api);
extern EGLSurface (*eglCreatePbufferSurface_p) (EGLDisplay dpy, EGLConfig config, const EGLint *attrib_list);
extern EGLSurface (*eglCreateWindowSurface_p) (EGLDisplay dpy, EGLConfig config, NativeWindowType window, const EGLint *attrib_list);
extern EGLBoolean (*eglSwapBuffers_p) (EGLDisplay dpy, EGLSurface draw);
extern EGLint (*eglGetError_p) (void);
extern EGLContext (*eglCreateContext_p) (EGLDisplay dpy, EGLConfig config, EGLContext share_list, const EGLint *attrib_list);
extern EGLBoolean (*eglSwapInterval_p) (EGLDisplay dpy, EGLint interval);
extern EGLBoolean (*eglSurfaceAttrib_p) (EGLDisplay dpy, EGLSurface surface, EGLint attribute, EGLint value);
extern EGLSurface (*eglGetCurrentSurface_p) (EGLint readdraw);
extern EGLBoolean (*eglQuerySurface_p)(EGLDisplay display, EGLSurface surface, EGLint attribute, EGLint * value);
extern const char* (*eglQueryString_p)(EGLDisplay display, EGLint name);
extern __eglMustCastToProperFunctionPointerType (*eglGetProcAddress_p) (const char *procname);

void dlsym_EGL();
void* droidbridge_egl_get_handle(void);
const char* droidbridge_egl_get_loaded_name(void);
bool droidbridge_egl_adopt_handle(void* handle, const char* loaded_name);

#endif //DROIDBRIDGE_RUNTIME_EGL_LOADER_H

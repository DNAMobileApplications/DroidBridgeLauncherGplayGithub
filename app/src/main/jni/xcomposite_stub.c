/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * DroidBridge desktop Linux WebRTC compatibility shim.
 * This is a no-op Android shim used only to satisfy dev.onvoid.webrtc
 * linux-aarch64 DT_NEEDED dependencies.
 */
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define EXPORT __attribute__((visibility("default")))
typedef struct _XDisplay Display;
typedef unsigned long XID;
typedef XID Window;
typedef XID Drawable;
typedef XID Pixmap;
typedef XID Picture;
typedef XID Cursor;
typedef unsigned long Atom;
typedef int Bool;
typedef int Status;
#ifndef True
#define True 1
#endif
#ifndef False
#define False 0
#endif


EXPORT Bool XCompositeQueryExtension(Display* dpy, int* event_base_return, int* error_base_return) {
    if (event_base_return) *event_base_return = 0;
    if (error_base_return) *error_base_return = 0;
    return False;
}
EXPORT Status XCompositeQueryVersion(Display* dpy, int* major_version_return, int* minor_version_return) {
    if (major_version_return) *major_version_return = 0;
    if (minor_version_return) *minor_version_return = 4;
    return 1;
}
EXPORT int XCompositeVersion(void) { return 4; }
EXPORT void XCompositeRedirectWindow(Display* dpy, Window window, int update) {}
EXPORT void XCompositeRedirectSubwindows(Display* dpy, Window window, int update) {}
EXPORT void XCompositeUnredirectWindow(Display* dpy, Window window, int update) {}
EXPORT void XCompositeUnredirectSubwindows(Display* dpy, Window window, int update) {}
EXPORT XID XCompositeCreateRegionFromBorderClip(Display* dpy, Window window) { return 0; }
EXPORT Pixmap XCompositeNameWindowPixmap(Display* dpy, Window window) { return 0; }
EXPORT Window XCompositeGetOverlayWindow(Display* dpy, Window window) { return 0; }
EXPORT void XCompositeReleaseOverlayWindow(Display* dpy, Window window) {}

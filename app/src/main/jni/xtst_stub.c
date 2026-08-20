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


EXPORT Bool XTestQueryExtension(Display* dpy, int* event_base_return, int* error_base_return, int* major_version_return, int* minor_version_return) {
    if (event_base_return) *event_base_return = 0; if (error_base_return) *error_base_return = 0; if (major_version_return) *major_version_return = 2; if (minor_version_return) *minor_version_return = 2; return False;
}
EXPORT int XTestFakeKeyEvent(Display* dpy, unsigned int keycode, Bool is_press, unsigned long delay) { return 0; }
EXPORT int XTestFakeButtonEvent(Display* dpy, unsigned int button, Bool is_press, unsigned long delay) { return 0; }
EXPORT int XTestFakeMotionEvent(Display* dpy, int screen_number, int x, int y, unsigned long delay) { return 0; }
EXPORT int XTestFakeRelativeMotionEvent(Display* dpy, int x, int y, unsigned long delay) { return 0; }
EXPORT int XTestGrabControl(Display* dpy, Bool impervious) { return 0; }
EXPORT int XTestCompareCursorWithWindow(Display* dpy, Window window, Cursor cursor) { return 0; }
EXPORT int XTestCompareCurrentCursorWithWindow(Display* dpy, Window window) { return 0; }

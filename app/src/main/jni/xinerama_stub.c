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


typedef struct { int screen_number; short x_org, y_org, width, height; } XineramaScreenInfo;
EXPORT Bool XineramaQueryExtension(Display* dpy, int* event_base_return, int* error_base_return) { if (event_base_return) *event_base_return = 0; if (error_base_return) *error_base_return = 0; return False; }
EXPORT int XineramaQueryVersion(Display* dpy, int* major_version_return, int* minor_version_return) { if (major_version_return) *major_version_return = 1; if (minor_version_return) *minor_version_return = 1; return 1; }
EXPORT Bool XineramaIsActive(Display* dpy) { return False; }
EXPORT XineramaScreenInfo* XineramaQueryScreens(Display* dpy, int* number_return) { if (number_return) *number_return = 0; return NULL; }

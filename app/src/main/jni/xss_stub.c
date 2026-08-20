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


typedef struct { Drawable window; int state; int kind; unsigned long til_or_since; unsigned long idle; unsigned long event_mask; } XScreenSaverInfo;
EXPORT Bool XScreenSaverQueryExtension(Display* dpy, int* event_base_return, int* error_base_return) { if (event_base_return) *event_base_return = 0; if (error_base_return) *error_base_return = 0; return False; }
EXPORT Status XScreenSaverQueryVersion(Display* dpy, int* major_version_return, int* minor_version_return) { if (major_version_return) *major_version_return = 1; if (minor_version_return) *minor_version_return = 2; return 1; }
EXPORT XScreenSaverInfo* XScreenSaverAllocInfo(void) { return (XScreenSaverInfo*) calloc(1, sizeof(XScreenSaverInfo)); }
EXPORT Status XScreenSaverQueryInfo(Display* dpy, Drawable drawable, XScreenSaverInfo* saver_info) { if (saver_info) memset(saver_info, 0, sizeof(XScreenSaverInfo)); return 1; }
EXPORT void XScreenSaverSelectInput(Display* dpy, Drawable drawable, unsigned long event_mask) {}
EXPORT void XScreenSaverSuspend(Display* dpy, Bool suspend) {}

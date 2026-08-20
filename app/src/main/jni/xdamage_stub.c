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


typedef XID Damage;
typedef XID XserverRegion;
EXPORT Bool XDamageQueryExtension(Display* dpy, int* event_base_return, int* error_base_return) {
    if (event_base_return) *event_base_return = 0;
    if (error_base_return) *error_base_return = 0;
    return False;
}
EXPORT Status XDamageQueryVersion(Display* dpy, int* major_version_return, int* minor_version_return) {
    if (major_version_return) *major_version_return = 1;
    if (minor_version_return) *minor_version_return = 1;
    return 1;
}
EXPORT int XDamageVersion(void) { return 1001; }
EXPORT Damage XDamageCreate(Display* dpy, Drawable drawable, int level) { return 0; }
EXPORT void XDamageDestroy(Display* dpy, Damage damage) {}
EXPORT void XDamageSubtract(Display* dpy, Damage damage, XserverRegion repair, XserverRegion parts) {}
EXPORT void XDamageAdd(Display* dpy, Drawable drawable, XserverRegion region) {}

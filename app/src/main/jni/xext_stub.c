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


EXPORT Bool XShmQueryExtension(Display* dpy) { return False; }
EXPORT Status XShmQueryVersion(Display* dpy, int* major, int* minor, Bool* pixmaps) { if (major) *major = 0; if (minor) *minor = 0; if (pixmaps) *pixmaps = False; return 0; }
EXPORT int XShmAttach(Display* dpy, void* shminfo) { return 0; }
EXPORT int XShmDetach(Display* dpy, void* shminfo) { return 0; }
EXPORT int XShmPutImage(Display* dpy, Drawable d, void* gc, void* image, int sx, int sy, int dx, int dy, unsigned int w, unsigned int h, Bool send_event) { return 0; }
EXPORT void* XShmCreateImage(Display* dpy, void* visual, unsigned int depth, int format, char* data, void* shminfo, unsigned int width, unsigned int height) { return NULL; }
EXPORT Bool XShapeQueryExtension(Display* dpy, int* event_base, int* error_base) { if (event_base) *event_base = 0; if (error_base) *error_base = 0; return False; }
EXPORT Status XShapeQueryVersion(Display* dpy, int* major, int* minor) { if (major) *major = 1; if (minor) *minor = 1; return 1; }
EXPORT void XShapeCombineRegion(Display* dpy, Window dest, int destKind, int xOff, int yOff, XID region, int op) {}
EXPORT void XShapeCombineMask(Display* dpy, Window dest, int destKind, int xOff, int yOff, Pixmap src, int op) {}
EXPORT void XShapeCombineShape(Display* dpy, Window dest, int destKind, int xOff, int yOff, Window src, int srcKind, int op) {}
EXPORT void XShapeSelectInput(Display* dpy, Window window, unsigned long mask) {}
EXPORT unsigned long XShapeInputSelected(Display* dpy, Window window) { return 0; }
EXPORT Bool XSyncQueryExtension(Display* dpy, int* event_base, int* error_base) { if (event_base) *event_base = 0; if (error_base) *error_base = 0; return False; }
EXPORT Status XSyncInitialize(Display* dpy, int* major, int* minor) { if (major) *major = 3; if (minor) *minor = 1; return 1; }

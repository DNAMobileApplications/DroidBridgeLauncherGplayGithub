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


typedef struct { unsigned int version, size, width, height, xhot, yhot, delay; unsigned int* pixels; } XcursorImage;
EXPORT XcursorImage* XcursorImageCreate(int width, int height) { XcursorImage* img = (XcursorImage*) calloc(1, sizeof(XcursorImage)); if (img) { img->width = width; img->height = height; } return img; }
EXPORT void XcursorImageDestroy(XcursorImage* image) { if (!image) return; free(image->pixels); free(image); }
EXPORT Cursor XcursorImageLoadCursor(Display* dpy, const XcursorImage* image) { return 0; }
EXPORT XcursorImage* XcursorShapeLoadImage(unsigned int shape, const char* theme, int size) { return NULL; }
EXPORT Cursor XcursorShapeLoadCursor(Display* dpy, unsigned int shape) { return 0; }
EXPORT Cursor XcursorLibraryLoadCursor(Display* dpy, const char* file) { return 0; }
EXPORT XcursorImage* XcursorLibraryLoadImage(const char* file, const char* theme, int size) { return NULL; }
EXPORT int XcursorSupportsARGB(Display* dpy) { return 0; }
EXPORT int XcursorSetDefaultSize(Display* dpy, int size) { return 0; }
EXPORT int XcursorGetDefaultSize(Display* dpy) { return 16; }
EXPORT Bool XcursorSetTheme(Display* dpy, const char* theme) { return False; }
EXPORT char* XcursorGetTheme(Display* dpy) { return NULL; }

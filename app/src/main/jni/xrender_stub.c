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


typedef struct { short red, redMask, green, greenMask, blue, blueMask, alpha, alphaMask; } XRenderDirectFormat;
typedef struct { unsigned long id; int type; int depth; XRenderDirectFormat direct; unsigned long colormap; } XRenderPictFormat;
typedef struct { unsigned short red, green, blue, alpha; } XRenderColor;
typedef XID GlyphSet;
EXPORT Bool XRenderQueryExtension(Display* dpy, int* event_base_return, int* error_base_return) {
    if (event_base_return) *event_base_return = 0;
    if (error_base_return) *error_base_return = 0;
    return False;
}
EXPORT Status XRenderQueryVersion(Display* dpy, int* major_version_return, int* minor_version_return) {
    if (major_version_return) *major_version_return = 0;
    if (minor_version_return) *minor_version_return = 11;
    return 1;
}
EXPORT Status XRenderQueryFormats(Display* dpy) { return 1; }
EXPORT XRenderPictFormat* XRenderFindVisualFormat(Display* dpy, void* visual) { return NULL; }
EXPORT XRenderPictFormat* XRenderFindFormat(Display* dpy, unsigned long mask, XRenderPictFormat* templ, int count) { return NULL; }
EXPORT XRenderPictFormat* XRenderFindStandardFormat(Display* dpy, int format) { return NULL; }
EXPORT Picture XRenderCreatePicture(Display* dpy, Drawable drawable, XRenderPictFormat* format, unsigned long valuemask, void* attributes) { return 0; }
EXPORT void XRenderFreePicture(Display* dpy, Picture picture) {}
EXPORT void XRenderChangePicture(Display* dpy, Picture picture, unsigned long valuemask, void* attributes) {}
EXPORT void XRenderSetPictureClipRectangles(Display* dpy, Picture picture, int x_origin, int y_origin, void* rectangles, int n) {}
EXPORT void XRenderComposite(Display* dpy, int op, Picture src, Picture mask, Picture dst, int sx, int sy, int mx, int my, int dx, int dy, unsigned int w, unsigned int h) {}
EXPORT void XRenderFillRectangle(Display* dpy, int op, Picture dst, XRenderColor* color, int x, int y, unsigned int w, unsigned int h) {}
EXPORT void XRenderFillRectangles(Display* dpy, int op, Picture dst, XRenderColor* color, void* rectangles, int n_rects) {}
EXPORT GlyphSet XRenderCreateGlyphSet(Display* dpy, XRenderPictFormat* format) { return 0; }
EXPORT void XRenderFreeGlyphSet(Display* dpy, GlyphSet glyphset) {}
EXPORT void XRenderAddGlyphs(Display* dpy, GlyphSet glyphset, const unsigned long* gids, const void* glyphs, int nglyphs, const char* images, int nbyte_images) {}
EXPORT void XRenderFreeGlyphs(Display* dpy, GlyphSet glyphset, const unsigned long* gids, int nglyphs) {}

/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * DroidBridge WebRTC compatibility shim.
 *
 * Minecraft Snapshot 7+ currently ships dev.onvoid.webrtc desktop Linux
 * natives. On Android, that linux-aarch64 native asks for libXfixes.so.3.
 * Android does not provide Xfixes, so this shim satisfies the dynamic linker
 * and returns harmless/no-op Xfixes behavior.
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
typedef XID Cursor;
typedef unsigned long Atom;
typedef unsigned long XserverRegion;
typedef int Bool;
typedef int Status;

#ifndef True
#define True 1
#endif

#ifndef False
#define False 0
#endif

typedef struct {
    short x;
    short y;
    unsigned short width;
    unsigned short height;
} XRectangle;

typedef struct {
    int type;
    unsigned long serial;
    Bool send_event;
    Display* display;
    Window window;
    int subtype;
    Window owner;
    XserverRegion region;
    XserverRegion name;
    unsigned long cursor_serial;
    unsigned long timestamp;
    Atom cursor_name;
} XFixesCursorNotifyEvent;

typedef struct {
    int type;
    unsigned long serial;
    Bool send_event;
    Display* display;
    Window window;
    int subtype;
    int x;
    int y;
    int width;
    int height;
    int count;
} XFixesSelectionNotifyEvent;

EXPORT Bool XFixesQueryExtension(Display* dpy, int* event_base_return, int* error_base_return) {
    if (event_base_return) *event_base_return = 0;
    if (error_base_return) *error_base_return = 0;
    return False;
}

EXPORT Status XFixesQueryVersion(Display* dpy, int* major_version_return, int* minor_version_return) {
    if (major_version_return) *major_version_return = 5;
    if (minor_version_return) *minor_version_return = 0;
    return 1;
}

EXPORT int XFixesVersion(void) {
    return 50000;
}

EXPORT void XFixesSelectSelectionInput(Display* dpy, Window win, Atom selection, unsigned long eventMask) {
}

EXPORT void XFixesSelectCursorInput(Display* dpy, Window win, unsigned long eventMask) {
}

EXPORT Cursor XFixesGetCursorImage(Display* dpy) {
    return 0;
}

EXPORT void* XFixesGetCursorImageAndName(Display* dpy) {
    return NULL;
}

EXPORT void XFixesChangeCursor(Display* dpy, Cursor source, Cursor destination) {
}

EXPORT void XFixesChangeCursorByName(Display* dpy, Cursor source, const char* name) {
}

EXPORT void XFixesHideCursor(Display* dpy, Window win) {
}

EXPORT void XFixesShowCursor(Display* dpy, Window win) {
}

EXPORT XserverRegion XFixesCreateRegion(Display* dpy, XRectangle* rectangles, int nrectangles) {
    return 0;
}

EXPORT XserverRegion XFixesCreateRegionFromBitmap(Display* dpy, Pixmap bitmap) {
    return 0;
}

EXPORT XserverRegion XFixesCreateRegionFromWindow(Display* dpy, Window window, int kind) {
    return 0;
}

EXPORT XserverRegion XFixesCreateRegionFromGC(Display* dpy, void* gc) {
    return 0;
}

EXPORT XserverRegion XFixesCreateRegionFromPicture(Display* dpy, unsigned long picture) {
    return 0;
}

EXPORT void XFixesDestroyRegion(Display* dpy, XserverRegion region) {
}

EXPORT void XFixesSetRegion(Display* dpy, XserverRegion region, XRectangle* rectangles, int nrectangles) {
}

EXPORT void XFixesCopyRegion(Display* dpy, XserverRegion dst, XserverRegion src) {
}

EXPORT void XFixesUnionRegion(Display* dpy, XserverRegion dst, XserverRegion src1, XserverRegion src2) {
}

EXPORT void XFixesIntersectRegion(Display* dpy, XserverRegion dst, XserverRegion src1, XserverRegion src2) {
}

EXPORT void XFixesSubtractRegion(Display* dpy, XserverRegion dst, XserverRegion src1, XserverRegion src2) {
}

EXPORT void XFixesInvertRegion(Display* dpy, XserverRegion dst, XRectangle* rect, XserverRegion src) {
}

EXPORT void XFixesTranslateRegion(Display* dpy, XserverRegion region, int dx, int dy) {
}

EXPORT void XFixesRegionExtents(Display* dpy, XserverRegion dst, XserverRegion src) {
}

EXPORT XRectangle* XFixesFetchRegion(Display* dpy, XserverRegion region, int* nrectangles_return) {
    if (nrectangles_return) *nrectangles_return = 0;
    return NULL;
}

EXPORT XRectangle* XFixesFetchRegionAndBounds(Display* dpy, XserverRegion region, int* nrectangles_return, XRectangle* bounds_return) {
    if (nrectangles_return) *nrectangles_return = 0;
    if (bounds_return) memset(bounds_return, 0, sizeof(XRectangle));
    return NULL;
}

EXPORT void XFixesSetGCClipRegion(Display* dpy, void* gc, int clip_x_origin, int clip_y_origin, XserverRegion region) {
}

EXPORT void XFixesSetWindowShapeRegion(Display* dpy, Window win, int shape_kind, int x_off, int y_off, XserverRegion region) {
}

EXPORT void XFixesSetPictureClipRegion(Display* dpy, unsigned long picture, int clip_x_origin, int clip_y_origin, XserverRegion region) {
}

EXPORT void XFixesSetCursorName(Display* dpy, Cursor cursor, const char* name) {
}

EXPORT const char* XFixesGetCursorName(Display* dpy, Cursor cursor, Atom* atom_return) {
    if (atom_return) *atom_return = 0;
    return NULL;
}

EXPORT void XFixesExpandRegion(Display* dpy, XserverRegion dst, XserverRegion src,
                               unsigned int left, unsigned int right,
                               unsigned int top, unsigned int bottom) {
}

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


typedef struct { int deviceid; char* name; int use; int attachment; Bool enabled; int num_classes; void** classes; } XIDeviceInfo;
EXPORT int XIQueryVersion(Display* dpy, int* major_version_inout, int* minor_version_inout) { if (major_version_inout) *major_version_inout = 2; if (minor_version_inout) *minor_version_inout = 4; return 0; }
EXPORT XIDeviceInfo* XIQueryDevice(Display* dpy, int deviceid, int* ndevices_return) { if (ndevices_return) *ndevices_return = 0; return NULL; }
EXPORT void XIFreeDeviceInfo(XIDeviceInfo* info) { if (info) free(info); }
EXPORT int XISelectEvents(Display* dpy, Window win, void* masks, int num_masks) { return 0; }
EXPORT int XIGetSelectedEvents(Display* dpy, Window win, int* num_masks_return, void** masks_return) { if (num_masks_return) *num_masks_return = 0; if (masks_return) *masks_return = NULL; return 0; }
EXPORT int XIGrabDevice(Display* dpy, int deviceid, Window grab_window, unsigned long time, Cursor cursor, int grab_mode, int paired_device_mode, Bool owner_events, void* mask) { return 0; }
EXPORT int XIUngrabDevice(Display* dpy, int deviceid, unsigned long time) { return 0; }
EXPORT int XIAllowEvents(Display* dpy, int deviceid, int event_mode, unsigned long time) { return 0; }
EXPORT int XIWarpPointer(Display* dpy, int deviceid, Window src_win, Window dst_win, double sx, double sy, unsigned int sw, unsigned int sh, double dx, double dy) { return 0; }

/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * DroidBridge WebRTC compatibility shim.
 *
 * Minecraft Snapshot 7+ currently ships dev.onvoid.webrtc desktop Linux
 * natives. On Android, that linux-aarch64 native asks for libXrandr.so.2.
 * Android does not provide Xrandr, so this shim satisfies the dynamic linker
 * and reports no display outputs/modes.
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
typedef XID RROutput;
typedef XID RRCrtc;
typedef XID RRMode;
typedef XID RRProvider;
typedef unsigned long Time;
typedef int Bool;
typedef int Status;
typedef unsigned short Rotation;
typedef unsigned short SizeID;

#ifndef True
#define True 1
#endif

#ifndef False
#define False 0
#endif

typedef struct {
    int width;
    int height;
    int mwidth;
    int mheight;
} XRRScreenSize;

typedef struct {
    int min_width;
    int min_height;
    int max_width;
    int max_height;
    int noutput;
    RROutput* outputs;
    int ncrtc;
    RRCrtc* crtcs;
    int nmode;
    void* modes;
    Time timestamp;
    Time configTimestamp;
} XRRScreenResources;

typedef struct {
    Time timestamp;
    RRCrtc crtc;
    char* name;
    int nameLen;
    unsigned long mm_width;
    unsigned long mm_height;
    int connection;
    int subpixel_order;
    int ncrtc;
    RRCrtc* crtcs;
    int nclone;
    RROutput* clones;
    int nmode;
    int npreferred;
    RRMode* modes;
} XRROutputInfo;

typedef struct {
    Time timestamp;
    int x;
    int y;
    unsigned int width;
    unsigned int height;
    RRMode mode;
    Rotation rotation;
    int noutput;
    RROutput* outputs;
    Rotation rotations;
    int npossible;
    RROutput* possible;
} XRRCrtcInfo;

typedef struct {
    int type;
    unsigned long serial;
    Bool send_event;
    Display* display;
    Window window;
    Window root;
    Time timestamp;
    Time config_timestamp;
    SizeID size_index;
    int subpixel_order;
    Rotation rotation;
    int width;
    int height;
    int mwidth;
    int mheight;
} XRRScreenChangeNotifyEvent;

typedef struct {
    RROutput output;
    RRCrtc crtc;
    RRMode mode;
    unsigned int npreferred;
    unsigned int nmode;
    RRMode* modes;
    unsigned int nclone;
    RROutput* clones;
    unsigned int ncrtc;
    RRCrtc* crtcs;
} XRRProviderInfo;

typedef struct {
    int nproviders;
    RRProvider* providers;
} XRRProviderResources;

typedef struct {
    char* name;
    Bool primary;
    Bool automatic;
    int noutput;
    RROutput* outputs;
    int x;
    int y;
    int width;
    int height;
    int mwidth;
    int mheight;
} XRRMonitorInfo;

EXPORT Bool XRRQueryExtension(Display* dpy, int* event_base_return, int* error_base_return) {
    if (event_base_return) *event_base_return = 0;
    if (error_base_return) *error_base_return = 0;
    return False;
}

EXPORT Status XRRQueryVersion(Display* dpy, int* major_version_return, int* minor_version_return) {
    if (major_version_return) *major_version_return = 1;
    if (minor_version_return) *minor_version_return = 5;
    return 1;
}

EXPORT void XRRSelectInput(Display* dpy, Window window, int mask) {
}

EXPORT int XRRUpdateConfiguration(void* event) {
    return 0;
}

EXPORT XRRScreenSize* XRRSizes(Display* dpy, int screen, int* nsizes_return) {
    if (nsizes_return) *nsizes_return = 0;
    return NULL;
}

EXPORT short* XRRRates(Display* dpy, int screen, int size_index, int* nrates_return) {
    if (nrates_return) *nrates_return = 0;
    return NULL;
}

EXPORT Rotation XRRRotations(Display* dpy, int screen, Rotation* current_rotation_return) {
    if (current_rotation_return) *current_rotation_return = 1;
    return 1;
}

EXPORT void* XRRGetScreenInfo(Display* dpy, Drawable draw) {
    return NULL;
}

EXPORT void XRRFreeScreenConfigInfo(void* config) {
}

EXPORT SizeID XRRConfigCurrentConfiguration(void* config, Rotation* rotation_return) {
    if (rotation_return) *rotation_return = 1;
    return 0;
}

EXPORT short XRRConfigCurrentRate(void* config) {
    return 0;
}

EXPORT Time XRRConfigTimes(void* config, Time* config_timestamp_return) {
    if (config_timestamp_return) *config_timestamp_return = 0;
    return 0;
}

EXPORT XRRScreenSize* XRRConfigSizes(void* config, int* nsizes_return) {
    if (nsizes_return) *nsizes_return = 0;
    return NULL;
}

EXPORT short* XRRConfigRates(void* config, int sizeID, int* nrates_return) {
    if (nrates_return) *nrates_return = 0;
    return NULL;
}

EXPORT Status XRRSetScreenConfig(Display* dpy, void* config, Drawable draw, int size_index, Rotation rotation, Time timestamp) {
    return 0;
}

EXPORT Status XRRSetScreenConfigAndRate(Display* dpy, void* config, Drawable draw, int size_index, Rotation rotation, short rate, Time timestamp) {
    return 0;
}

EXPORT int XRRRootToScreen(Display* dpy, Window root) {
    return 0;
}

EXPORT XRRScreenResources* XRRGetScreenResources(Display* dpy, Window window) {
    XRRScreenResources* resources = (XRRScreenResources*) calloc(1, sizeof(XRRScreenResources));
    return resources;
}

EXPORT XRRScreenResources* XRRGetScreenResourcesCurrent(Display* dpy, Window window) {
    XRRScreenResources* resources = (XRRScreenResources*) calloc(1, sizeof(XRRScreenResources));
    return resources;
}

EXPORT void XRRFreeScreenResources(XRRScreenResources* resources) {
    if (!resources) return;
    free(resources->outputs);
    free(resources->crtcs);
    free(resources->modes);
    free(resources);
}

EXPORT XRROutputInfo* XRRGetOutputInfo(Display* dpy, XRRScreenResources* resources, RROutput output) {
    XRROutputInfo* info = (XRROutputInfo*) calloc(1, sizeof(XRROutputInfo));
    if (info) {
        info->connection = 1; /* Disconnected */
        info->name = NULL;
        info->nameLen = 0;
    }
    return info;
}

EXPORT void XRRFreeOutputInfo(XRROutputInfo* outputInfo) {
    if (!outputInfo) return;
    free(outputInfo->name);
    free(outputInfo->crtcs);
    free(outputInfo->clones);
    free(outputInfo->modes);
    free(outputInfo);
}

EXPORT XRRCrtcInfo* XRRGetCrtcInfo(Display* dpy, XRRScreenResources* resources, RRCrtc crtc) {
    XRRCrtcInfo* info = (XRRCrtcInfo*) calloc(1, sizeof(XRRCrtcInfo));
    if (info) {
        info->width = 1;
        info->height = 1;
        info->rotation = 1;
        info->rotations = 1;
    }
    return info;
}

EXPORT void XRRFreeCrtcInfo(XRRCrtcInfo* crtcInfo) {
    if (!crtcInfo) return;
    free(crtcInfo->outputs);
    free(crtcInfo->possible);
    free(crtcInfo);
}

EXPORT Status XRRSetCrtcConfig(Display* dpy, XRRScreenResources* resources, RRCrtc crtc,
                               Time timestamp, int x, int y, RRMode mode, Rotation rotation,
                               RROutput* outputs, int noutputs) {
    return 0;
}

EXPORT Status XRRSetCrtcTransform(Display* dpy, RRCrtc crtc, void* transform,
                                  const char* filter, void* params, int nparams) {
    return 0;
}

EXPORT void XRRGetCrtcTransform(Display* dpy, RRCrtc crtc, void** pending_transform_return,
                                char** pending_filter_return, int* pending_nparams_return,
                                void** current_transform_return, char** current_filter_return,
                                int* current_nparams_return) {
    if (pending_transform_return) *pending_transform_return = NULL;
    if (pending_filter_return) *pending_filter_return = NULL;
    if (pending_nparams_return) *pending_nparams_return = 0;
    if (current_transform_return) *current_transform_return = NULL;
    if (current_filter_return) *current_filter_return = NULL;
    if (current_nparams_return) *current_nparams_return = 0;
}

EXPORT RROutput XRRGetOutputPrimary(Display* dpy, Window window) {
    return 0;
}

EXPORT void XRRSetOutputPrimary(Display* dpy, Window window, RROutput output) {
}

EXPORT XRRProviderResources* XRRGetProviderResources(Display* dpy, Window window) {
    XRRProviderResources* resources = (XRRProviderResources*) calloc(1, sizeof(XRRProviderResources));
    return resources;
}

EXPORT void XRRFreeProviderResources(XRRProviderResources* resources) {
    if (!resources) return;
    free(resources->providers);
    free(resources);
}

EXPORT XRRProviderInfo* XRRGetProviderInfo(Display* dpy, XRRScreenResources* resources, RRProvider provider) {
    XRRProviderInfo* info = (XRRProviderInfo*) calloc(1, sizeof(XRRProviderInfo));
    return info;
}

EXPORT void XRRFreeProviderInfo(XRRProviderInfo* providerInfo) {
    if (!providerInfo) return;
    free(providerInfo->modes);
    free(providerInfo->clones);
    free(providerInfo->crtcs);
    free(providerInfo);
}

EXPORT Status XRRSetProviderOffloadSink(Display* dpy, RRProvider provider, RRProvider sink_provider) {
    return 0;
}

EXPORT Status XRRSetProviderOutputSource(Display* dpy, RRProvider provider, RRProvider source_provider) {
    return 0;
}

EXPORT XRRMonitorInfo* XRRGetMonitors(Display* dpy, Window window, Bool get_active, int* nmonitors_return) {
    if (nmonitors_return) *nmonitors_return = 0;
    return NULL;
}

EXPORT void XRRFreeMonitors(XRRMonitorInfo* monitors) {
    if (!monitors) return;
    free(monitors);
}

EXPORT void XRRSetMonitor(Display* dpy, Window window, XRRMonitorInfo* monitor) {
}

EXPORT void XRRDeleteMonitor(Display* dpy, Window window, unsigned long name) {
}

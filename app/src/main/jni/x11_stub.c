/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * DroidBridge WebRTC compatibility shim.
 *
 * Minecraft Snapshot 7+ currently ships dev.onvoid.webrtc desktop Linux
 * natives. On Android, that linux-aarch64 native asks for libX11.so.6.
 * Android does not provide X11, so this shim satisfies the dynamic linker
 * and returns harmless/null display values.
 */

#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

#define EXPORT __attribute__((visibility("default")))

typedef struct _XDisplay Display;
typedef unsigned long XID;
typedef XID Window;
typedef XID Drawable;
typedef XID Pixmap;
typedef XID Colormap;
typedef XID Cursor;
typedef XID Font;
typedef unsigned long Atom;
typedef unsigned long VisualID;
typedef unsigned long Time;
typedef unsigned long KeySym;
typedef unsigned char KeyCode;
typedef int Bool;
typedef int Status;
typedef int (*XErrorHandler)(Display*, void*);
typedef int (*XIOErrorHandler)(Display*);

#ifndef True
#define True 1
#endif

#ifndef False
#define False 0
#endif

#define Success 0
#define None 0L
#define CurrentTime 0L
#define QueuedAlready 0
#define QueuedAfterReading 1
#define QueuedAfterFlush 2

struct _XDisplay {
    int placeholder;
};

typedef struct {
    void* visual;
    VisualID visualid;
    int screen;
    int depth;
    int class;
    unsigned long red_mask;
    unsigned long green_mask;
    unsigned long blue_mask;
    int colormap_size;
    int bits_per_rgb;
} XVisualInfo;

typedef struct {
    int type;
    unsigned long serial;
    Bool send_event;
    Display* display;
    Window window;
} XAnyEvent;

typedef union _XEvent {
    int type;
    XAnyEvent xany;
    long pad[24];
} XEvent;

typedef struct {
    int type;
    unsigned long serial;
    Bool send_event;
    Display* display;
    int extension;
    int evtype;
    unsigned int cookie;
    void* data;
} XGenericEventCookie;

typedef struct {
    unsigned char byte1;
    unsigned char byte2;
} XChar2b;

typedef struct {
    int ascent;
    int descent;
} XFontStruct;

typedef struct {
    unsigned int max_keypermod;
    KeyCode* modifiermap;
} XModifierKeymap;

static struct _XDisplay g_display = { 1 };

EXPORT int XInitThreads(void) {
    return 1;
}

EXPORT Display* XOpenDisplay(const char* display_name) {
    return &g_display;
}

EXPORT int XCloseDisplay(Display* display) {
    return 0;
}

EXPORT int XConnectionNumber(Display* display) {
    return -1;
}

EXPORT int XDefaultScreen(Display* display) {
    return 0;
}

EXPORT Window XDefaultRootWindow(Display* display) {
    return 1;
}

EXPORT Window XRootWindow(Display* display, int screen_number) {
    return 1;
}

EXPORT Window XRootWindowOfScreen(void* screen) {
    return 1;
}

EXPORT void* XDefaultScreenOfDisplay(Display* display) {
    return NULL;
}

EXPORT int XScreenCount(Display* display) {
    return 1;
}

EXPORT int XDisplayWidth(Display* display, int screen_number) {
    return 1;
}

EXPORT int XDisplayHeight(Display* display, int screen_number) {
    return 1;
}

EXPORT int XDisplayWidthMM(Display* display, int screen_number) {
    return 1;
}

EXPORT int XDisplayHeightMM(Display* display, int screen_number) {
    return 1;
}

EXPORT int XDefaultDepth(Display* display, int screen_number) {
    return 24;
}

EXPORT void* XDefaultVisual(Display* display, int screen_number) {
    return NULL;
}

EXPORT Colormap XDefaultColormap(Display* display, int screen_number) {
    return 0;
}

EXPORT unsigned long XBlackPixel(Display* display, int screen_number) {
    return 0;
}

EXPORT unsigned long XWhitePixel(Display* display, int screen_number) {
    return 0xffffff;
}

EXPORT int XLockDisplay(Display* display) {
    return 0;
}

EXPORT int XUnlockDisplay(Display* display) {
    return 0;
}

EXPORT int XFlush(Display* display) {
    return 0;
}

EXPORT int XSync(Display* display, Bool discard) {
    return 0;
}

EXPORT int XNoOp(Display* display) {
    return 0;
}

EXPORT Atom XInternAtom(Display* display, const char* atom_name, Bool only_if_exists) {
    if (atom_name == NULL) return 0;
    unsigned long h = 5381;
    const unsigned char* s = (const unsigned char*) atom_name;
    while (*s) h = ((h << 5) + h) + *s++;
    return h ? h : 1;
}

EXPORT char* XGetAtomName(Display* display, Atom atom) {
    char* out = (char*) malloc(16);
    if (out) strcpy(out, "DROIDBRIDGE");
    return out;
}

EXPORT int XFree(void* data) {
    if (data) free(data);
    return 0;
}

EXPORT int XQueryExtension(Display* display, const char* name, int* major_opcode, int* first_event, int* first_error) {
    if (major_opcode) *major_opcode = 0;
    if (first_event) *first_event = 0;
    if (first_error) *first_error = 0;
    return False;
}

EXPORT Bool XQueryPointer(Display* display, Window window, Window* root_return, Window* child_return,
                          int* root_x_return, int* root_y_return, int* win_x_return, int* win_y_return,
                          unsigned int* mask_return) {
    if (root_return) *root_return = 1;
    if (child_return) *child_return = 0;
    if (root_x_return) *root_x_return = 0;
    if (root_y_return) *root_y_return = 0;
    if (win_x_return) *win_x_return = 0;
    if (win_y_return) *win_y_return = 0;
    if (mask_return) *mask_return = 0;
    return False;
}

EXPORT int XGetWindowProperty(Display* display, Window window, Atom property, long long_offset, long long_length,
                              Bool delete_property, Atom req_type, Atom* actual_type_return,
                              int* actual_format_return, unsigned long* nitems_return,
                              unsigned long* bytes_after_return, unsigned char** prop_return) {
    if (actual_type_return) *actual_type_return = 0;
    if (actual_format_return) *actual_format_return = 0;
    if (nitems_return) *nitems_return = 0;
    if (bytes_after_return) *bytes_after_return = 0;
    if (prop_return) *prop_return = NULL;
    return Success;
}

EXPORT int XChangeProperty(Display* display, Window window, Atom property, Atom type, int format,
                           int mode, const unsigned char* data, int nelements) {
    return 0;
}

EXPORT int XDeleteProperty(Display* display, Window window, Atom property) {
    return 0;
}

EXPORT Window XCreateSimpleWindow(Display* display, Window parent, int x, int y,
                                  unsigned int width, unsigned int height, unsigned int border_width,
                                  unsigned long border, unsigned long background) {
    return 1;
}

EXPORT Window XCreateWindow(Display* display, Window parent, int x, int y,
                            unsigned int width, unsigned int height, unsigned int border_width,
                            int depth, unsigned int class, void* visual,
                            unsigned long valuemask, void* attributes) {
    return 1;
}

EXPORT int XDestroyWindow(Display* display, Window window) {
    return 0;
}

EXPORT int XMapWindow(Display* display, Window window) {
    return 0;
}

EXPORT int XMapRaised(Display* display, Window window) {
    return 0;
}

EXPORT int XUnmapWindow(Display* display, Window window) {
    return 0;
}

EXPORT int XMoveWindow(Display* display, Window window, int x, int y) {
    return 0;
}

EXPORT int XResizeWindow(Display* display, Window window, unsigned int width, unsigned int height) {
    return 0;
}

EXPORT int XMoveResizeWindow(Display* display, Window window, int x, int y, unsigned int width, unsigned int height) {
    return 0;
}

EXPORT int XStoreName(Display* display, Window window, const char* window_name) {
    return 0;
}

EXPORT int XSetWMName(Display* display, Window window, void* text_prop) {
    return 0;
}

EXPORT int XSetClassHint(Display* display, Window window, void* class_hints) {
    return 0;
}

EXPORT int XSelectInput(Display* display, Window window, long event_mask) {
    return 0;
}

EXPORT int XNextEvent(Display* display, XEvent* event_return) {
    if (event_return) memset(event_return, 0, sizeof(XEvent));
    return 0;
}

EXPORT int XPeekEvent(Display* display, XEvent* event_return) {
    if (event_return) memset(event_return, 0, sizeof(XEvent));
    return 0;
}

EXPORT int XPending(Display* display) {
    return 0;
}

EXPORT int XEventsQueued(Display* display, int mode) {
    return 0;
}

EXPORT Bool XCheckTypedEvent(Display* display, int event_type, XEvent* event_return) {
    if (event_return) memset(event_return, 0, sizeof(XEvent));
    return False;
}

EXPORT Bool XCheckMaskEvent(Display* display, long event_mask, XEvent* event_return) {
    if (event_return) memset(event_return, 0, sizeof(XEvent));
    return False;
}

EXPORT Bool XCheckWindowEvent(Display* display, Window window, long event_mask, XEvent* event_return) {
    if (event_return) memset(event_return, 0, sizeof(XEvent));
    return False;
}

EXPORT int XSendEvent(Display* display, Window window, Bool propagate, long event_mask, XEvent* event_send) {
    return 0;
}

EXPORT Bool XFilterEvent(XEvent* event, Window window) {
    return False;
}

EXPORT Bool XGetEventData(Display* display, XGenericEventCookie* cookie) {
    return False;
}

EXPORT void XFreeEventData(Display* display, XGenericEventCookie* cookie) {
}

EXPORT int XGrabPointer(Display* display, Window grab_window, Bool owner_events, unsigned int event_mask,
                        int pointer_mode, int keyboard_mode, Window confine_to, Cursor cursor, Time time) {
    return 0;
}

EXPORT int XUngrabPointer(Display* display, Time time) {
    return 0;
}

EXPORT int XGrabKeyboard(Display* display, Window grab_window, Bool owner_events, int pointer_mode, int keyboard_mode, Time time) {
    return 0;
}

EXPORT int XUngrabKeyboard(Display* display, Time time) {
    return 0;
}

EXPORT int XWarpPointer(Display* display, Window src_w, Window dest_w, int src_x, int src_y,
                        unsigned int src_width, unsigned int src_height, int dest_x, int dest_y) {
    return 0;
}

EXPORT Cursor XCreateFontCursor(Display* display, unsigned int shape) {
    return 0;
}

EXPORT int XDefineCursor(Display* display, Window window, Cursor cursor) {
    return 0;
}

EXPORT int XUndefineCursor(Display* display, Window window) {
    return 0;
}

EXPORT int XFreeCursor(Display* display, Cursor cursor) {
    return 0;
}

EXPORT KeySym XkbKeycodeToKeysym(Display* display, KeyCode keycode, int group, int level) {
    return 0;
}

EXPORT KeySym XKeycodeToKeysym(Display* display, KeyCode keycode, int index) {
    return 0;
}

EXPORT KeyCode XKeysymToKeycode(Display* display, KeySym keysym) {
    return 0;
}

EXPORT char* XKeysymToString(KeySym keysym) {
    return NULL;
}

EXPORT KeySym XStringToKeysym(const char* string) {
    return 0;
}

EXPORT KeySym* XGetKeyboardMapping(Display* display, KeyCode first_keycode, int keycode_count, int* keysyms_per_keycode_return) {
    if (keysyms_per_keycode_return) *keysyms_per_keycode_return = 0;
    return NULL;
}

EXPORT void XDisplayKeycodes(Display* display, int* min_keycodes_return, int* max_keycodes_return) {
    if (min_keycodes_return) *min_keycodes_return = 8;
    if (max_keycodes_return) *max_keycodes_return = 255;
}

EXPORT XModifierKeymap* XGetModifierMapping(Display* display) {
    XModifierKeymap* map = (XModifierKeymap*) calloc(1, sizeof(XModifierKeymap));
    return map;
}

EXPORT int XFreeModifiermap(XModifierKeymap* modmap) {
    if (modmap) {
        free(modmap->modifiermap);
        free(modmap);
    }
    return 0;
}

EXPORT int XLookupString(void* event_struct, char* buffer_return, int bytes_buffer,
                         KeySym* keysym_return, void* status_in_out) {
    if (buffer_return && bytes_buffer > 0) buffer_return[0] = '\0';
    if (keysym_return) *keysym_return = 0;
    return 0;
}

EXPORT Status XGetGeometry(Display* display, Drawable d, Window* root_return, int* x_return, int* y_return,
                           unsigned int* width_return, unsigned int* height_return,
                           unsigned int* border_width_return, unsigned int* depth_return) {
    if (root_return) *root_return = 1;
    if (x_return) *x_return = 0;
    if (y_return) *y_return = 0;
    if (width_return) *width_return = 1;
    if (height_return) *height_return = 1;
    if (border_width_return) *border_width_return = 0;
    if (depth_return) *depth_return = 24;
    return True;
}

EXPORT Status XGetWindowAttributes(Display* display, Window window, void* window_attributes_return) {
    if (window_attributes_return) memset(window_attributes_return, 0, 128);
    return True;
}

EXPORT Status XGetVisualInfo(Display* display, long vinfo_mask, XVisualInfo* vinfo_template,
                             int* nitems_return) {
    if (nitems_return) *nitems_return = 0;
    return 0;
}

EXPORT XVisualInfo* XMatchVisualInfo(Display* display, int screen, int depth, int class, XVisualInfo* vinfo_return) {
    if (vinfo_return) memset(vinfo_return, 0, sizeof(XVisualInfo));
    return NULL;
}

EXPORT Pixmap XCreatePixmap(Display* display, Drawable d, unsigned int width, unsigned int height, unsigned int depth) {
    return 0;
}

EXPORT int XFreePixmap(Display* display, Pixmap pixmap) {
    return 0;
}

EXPORT void* XCreateGC(Display* display, Drawable d, unsigned long valuemask, void* values) {
    return NULL;
}

EXPORT int XFreeGC(Display* display, void* gc) {
    return 0;
}

EXPORT int XSetForeground(Display* display, void* gc, unsigned long foreground) {
    return 0;
}

EXPORT int XFillRectangle(Display* display, Drawable d, void* gc, int x, int y, unsigned int width, unsigned int height) {
    return 0;
}

EXPORT int XSetErrorHandler(XErrorHandler handler) {
    return 0;
}

EXPORT int XSetIOErrorHandler(XIOErrorHandler handler) {
    return 0;
}

EXPORT int XSetLocaleModifiers(const char* modifier_list) {
    return 1;
}

EXPORT char* XLocaleOfIM(void* im) {
    return NULL;
}

EXPORT void* XOpenIM(Display* display, void* rdb, char* res_name, char* res_class) {
    return NULL;
}

EXPORT Status XCloseIM(void* im) {
    return 0;
}

EXPORT void* XCreateIC(void* im, ...) {
    return NULL;
}

EXPORT void XDestroyIC(void* ic) {
}

EXPORT void XSetICFocus(void* ic) {
}

EXPORT void XUnsetICFocus(void* ic) {
}

EXPORT char* XGetICValues(void* ic, ...) {
    return NULL;
}

EXPORT char* XSetICValues(void* ic, ...) {
    return NULL;
}

EXPORT char* XGetIMValues(void* im, ...) {
    return NULL;
}

EXPORT char* XSetIMValues(void* im, ...) {
    return NULL;
}

EXPORT int XConvertSelection(Display* display, Atom selection, Atom target, Atom property, Window requestor, Time time) {
    return 0;
}

EXPORT int XSetSelectionOwner(Display* display, Atom selection, Window owner, Time time) {
    return 0;
}

EXPORT Window XGetSelectionOwner(Display* display, Atom selection) {
    return 0;
}

EXPORT int Xutf8LookupString(void* ic, void* event, char* buffer_return, int bytes_buffer,
                             KeySym* keysym_return, Status* status_return) {
    if (buffer_return && bytes_buffer > 0) buffer_return[0] = '\0';
    if (keysym_return) *keysym_return = 0;
    if (status_return) *status_return = 0;
    return 0;
}

EXPORT int XmbLookupString(void* ic, void* event, char* buffer_return, int bytes_buffer,
                           KeySym* keysym_return, Status* status_return) {
    if (buffer_return && bytes_buffer > 0) buffer_return[0] = '\0';
    if (keysym_return) *keysym_return = 0;
    if (status_return) *status_return = 0;
    return 0;
}

EXPORT int XwcLookupString(void* ic, void* event, wchar_t* buffer_return, int wchars_buffer,
                           KeySym* keysym_return, Status* status_return) {
    if (buffer_return && wchars_buffer > 0) buffer_return[0] = 0;
    if (keysym_return) *keysym_return = 0;
    if (status_return) *status_return = 0;
    return 0;
}

EXPORT XFontStruct* XLoadQueryFont(Display* display, const char* name) {
    return NULL;
}

EXPORT int XFreeFont(Display* display, XFontStruct* font_struct) {
    return 0;
}

EXPORT int XTextWidth(XFontStruct* font_struct, const char* string, int count) {
    return 0;
}

EXPORT int XTextWidth16(XFontStruct* font_struct, const XChar2b* string, int count) {
    return 0;
}

EXPORT int XDrawString(Display* display, Drawable d, void* gc, int x, int y, const char* string, int length) {
    return 0;
}

EXPORT int XDrawString16(Display* display, Drawable d, void* gc, int x, int y, const XChar2b* string, int length) {
    return 0;
}

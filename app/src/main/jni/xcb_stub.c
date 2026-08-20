/*
 * DroidBridge WebRTC compatibility shim: libxcb.so.1
 */
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#define EXPORT __attribute__((visibility("default")))
typedef struct xcb_connection_t { int placeholder; } xcb_connection_t;
typedef struct xcb_setup_t { int placeholder; } xcb_setup_t;
typedef struct { int fd; short events; short revents; } xcb_pollfd_t;
typedef struct { unsigned int sequence; } xcb_void_cookie_t;
typedef struct { uint8_t response_type; } xcb_generic_event_t;
typedef struct { uint8_t response_type; uint8_t error_code; uint16_t sequence; uint32_t resource_id; uint16_t minor_code; uint8_t major_code; uint8_t pad0; } xcb_generic_error_t;
static xcb_connection_t g_xcb = {1};
EXPORT xcb_connection_t* xcb_connect(const char* displayname, int* screenp) { if (screenp) *screenp = 0; return &g_xcb; }
EXPORT xcb_connection_t* xcb_connect_to_display_with_auth_info(const char* display, void* auth, int* screenp) { if (screenp) *screenp = 0; return &g_xcb; }
EXPORT void xcb_disconnect(xcb_connection_t* c) {}
EXPORT int xcb_connection_has_error(xcb_connection_t* c) { return 1; }
EXPORT int xcb_get_file_descriptor(xcb_connection_t* c) { return -1; }
EXPORT const xcb_setup_t* xcb_get_setup(xcb_connection_t* c) { return NULL; }
EXPORT int xcb_generate_id(xcb_connection_t* c) { return 1; }
EXPORT xcb_generic_event_t* xcb_wait_for_event(xcb_connection_t* c) { return NULL; }
EXPORT xcb_generic_event_t* xcb_poll_for_event(xcb_connection_t* c) { return NULL; }
EXPORT void* xcb_wait_for_reply(xcb_connection_t* c, unsigned int request, xcb_generic_error_t** e) { if (e) *e = NULL; return NULL; }
EXPORT void* xcb_poll_for_reply(xcb_connection_t* c, unsigned int request, xcb_generic_error_t** e) { if (e) *e = NULL; return NULL; }
EXPORT int xcb_flush(xcb_connection_t* c) { return 0; }
EXPORT uint32_t xcb_get_maximum_request_length(xcb_connection_t* c) { return 0; }
EXPORT int xcb_prefetch_maximum_request_length(xcb_connection_t* c) { return 0; }
EXPORT xcb_void_cookie_t xcb_create_window_checked(xcb_connection_t* c, uint8_t depth, uint32_t wid, uint32_t parent, int16_t x, int16_t y, uint16_t width, uint16_t height, uint16_t border_width, uint16_t _class, uint32_t visual, uint32_t value_mask, const void* value_list) { xcb_void_cookie_t ck = {0}; return ck; }
EXPORT xcb_void_cookie_t xcb_destroy_window_checked(xcb_connection_t* c, uint32_t window) { xcb_void_cookie_t ck = {0}; return ck; }
EXPORT xcb_void_cookie_t xcb_map_window_checked(xcb_connection_t* c, uint32_t window) { xcb_void_cookie_t ck = {0}; return ck; }
EXPORT xcb_void_cookie_t xcb_unmap_window_checked(xcb_connection_t* c, uint32_t window) { xcb_void_cookie_t ck = {0}; return ck; }

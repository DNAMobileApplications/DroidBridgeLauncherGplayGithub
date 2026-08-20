/*
 * DroidBridge PulseAudio compatibility shim.
 *
 * Minecraft 26.2 Snapshot 7 includes dev.onvoid.webrtc desktop Linux natives
 * that DT_NEEDED libpulse.so.0. Android does not ship PulseAudio, so this
 * tiny shim satisfies the loader and reports PulseAudio as unavailable.
 *
 * This is intentionally not a real audio backend. It is only meant to let the
 * WebRTC data-channel side initialize far enough for Snapshot P2P testing.
 */

#include <stddef.h>
#include <stdint.h>

#define EXPORT __attribute__((visibility("default")))

/* A small subset of PulseAudio enum values used by callers. */
#define PA_CONTEXT_FAILED 5
#define PA_CONTEXT_TERMINATED 6
#define PA_STREAM_FAILED 3
#define PA_STREAM_TERMINATED 4
#define PA_OPERATION_DONE 2

EXPORT const char* pa_get_library_version(void) {
    return "DroidBridge PulseAudio stub";
}

EXPORT const char* pa_strerror(int error) {
    (void) error;
    return "PulseAudio is unavailable on Android";
}

EXPORT void* pa_threaded_mainloop_new(void) { return (void*) 1; }
EXPORT void pa_threaded_mainloop_free(void* m) { (void) m; }
EXPORT int pa_threaded_mainloop_start(void* m) { (void) m; return 0; }
EXPORT void pa_threaded_mainloop_stop(void* m) { (void) m; }
EXPORT void pa_threaded_mainloop_lock(void* m) { (void) m; }
EXPORT void pa_threaded_mainloop_unlock(void* m) { (void) m; }
EXPORT void pa_threaded_mainloop_wait(void* m) { (void) m; }
EXPORT void pa_threaded_mainloop_signal(void* m, int wait_for_accept) { (void) m; (void) wait_for_accept; }
EXPORT void pa_threaded_mainloop_accept(void* m) { (void) m; }
EXPORT void* pa_threaded_mainloop_get_api(void* m) { (void) m; return NULL; }

EXPORT void* pa_context_new(void* api, const char* name) { (void) api; (void) name; return (void*) 1; }
EXPORT void* pa_context_new_with_proplist(void* api, const char* name, void* proplist) {
    (void) api; (void) name; (void) proplist; return (void*) 1;
}
EXPORT int pa_context_connect(void* c, const char* server, int flags, const void* api) {
    (void) c; (void) server; (void) flags; (void) api; return -1;
}
EXPORT void pa_context_disconnect(void* c) { (void) c; }
EXPORT void* pa_context_ref(void* c) { return c; }
EXPORT void pa_context_unref(void* c) { (void) c; }
EXPORT int pa_context_errno(void* c) { (void) c; return 1; }
EXPORT int pa_context_get_state(void* c) { (void) c; return PA_CONTEXT_TERMINATED; }
EXPORT void pa_context_set_state_callback(void* c, void (*cb)(void*, void*), void* userdata) {
    (void) c; (void) cb; (void) userdata;
}
EXPORT void pa_context_set_subscribe_callback(void* c, void (*cb)(void*, int, uint32_t, void*), void* userdata) {
    (void) c; (void) cb; (void) userdata;
}
EXPORT void* pa_context_subscribe(void* c, int mask, void (*cb)(void*, int, void*), void* userdata) {
    (void) c; (void) mask; (void) cb; (void) userdata; return NULL;
}
EXPORT void* pa_context_get_sink_info_list(void* c, void (*cb)(void*, const void*, int, void*), void* userdata) {
    (void) c; (void) cb; (void) userdata; return NULL;
}
EXPORT void* pa_context_get_source_info_list(void* c, void (*cb)(void*, const void*, int, void*), void* userdata) {
    (void) c; (void) cb; (void) userdata; return NULL;
}
EXPORT void* pa_context_get_server_info(void* c, void (*cb)(void*, const void*, void*), void* userdata) {
    (void) c; (void) cb; (void) userdata; return NULL;
}

EXPORT void* pa_proplist_new(void) { return NULL; }
EXPORT void pa_proplist_free(void* p) { (void) p; }
EXPORT int pa_proplist_sets(void* p, const char* key, const char* value) {
    (void) p; (void) key; (void) value; return 0;
}

EXPORT void* pa_operation_ref(void* o) { return o; }
EXPORT void pa_operation_unref(void* o) { (void) o; }
EXPORT int pa_operation_get_state(void* o) { (void) o; return PA_OPERATION_DONE; }
EXPORT void pa_operation_cancel(void* o) { (void) o; }

EXPORT void* pa_stream_new(void* c, const char* name, const void* ss, const void* map) {
    (void) c; (void) name; (void) ss; (void) map; return (void*) 1;
}
EXPORT void* pa_stream_new_with_proplist(void* c, const char* name, const void* ss, const void* map, void* proplist) {
    (void) c; (void) name; (void) ss; (void) map; (void) proplist; return (void*) 1;
}
EXPORT int pa_stream_connect_playback(void* s, const char* dev, const void* attr, int flags, const void* volume, void* sync_stream) {
    (void) s; (void) dev; (void) attr; (void) flags; (void) volume; (void) sync_stream; return -1;
}
EXPORT int pa_stream_connect_record(void* s, const char* dev, const void* attr, int flags) {
    (void) s; (void) dev; (void) attr; (void) flags; return -1;
}
EXPORT void pa_stream_disconnect(void* s) { (void) s; }
EXPORT void* pa_stream_ref(void* s) { return s; }
EXPORT void pa_stream_unref(void* s) { (void) s; }
EXPORT int pa_stream_get_state(void* s) { (void) s; return PA_STREAM_TERMINATED; }
EXPORT int pa_stream_errno(void* s) { (void) s; return 1; }
EXPORT void pa_stream_set_state_callback(void* s, void (*cb)(void*, void*), void* userdata) {
    (void) s; (void) cb; (void) userdata;
}
EXPORT void pa_stream_set_read_callback(void* s, void (*cb)(void*, size_t, void*), void* userdata) {
    (void) s; (void) cb; (void) userdata;
}
EXPORT void pa_stream_set_write_callback(void* s, void (*cb)(void*, size_t, void*), void* userdata) {
    (void) s; (void) cb; (void) userdata;
}
EXPORT size_t pa_stream_readable_size(void* s) { (void) s; return 0; }
EXPORT size_t pa_stream_writable_size(void* s) { (void) s; return 0; }
EXPORT int pa_stream_peek(void* s, const void** data, size_t* nbytes) {
    (void) s;
    if (data) *data = NULL;
    if (nbytes) *nbytes = 0;
    return -1;
}
EXPORT int pa_stream_drop(void* s) { (void) s; return 0; }
EXPORT int pa_stream_write(void* s, const void* data, size_t nbytes, void* free_cb, int64_t offset, int seek) {
    (void) s; (void) data; (void) nbytes; (void) free_cb; (void) offset; (void) seek; return -1;
}
EXPORT int pa_stream_cork(void* s, int b, void (*cb)(void*, int, void*), void* userdata) {
    (void) s; (void) b; (void) cb; (void) userdata; return -1;
}
EXPORT int pa_stream_flush(void* s, void (*cb)(void*, int, void*), void* userdata) {
    (void) s; (void) cb; (void) userdata; return -1;
}
EXPORT int pa_stream_drain(void* s, void (*cb)(void*, int, void*), void* userdata) {
    (void) s; (void) cb; (void) userdata; return -1;
}

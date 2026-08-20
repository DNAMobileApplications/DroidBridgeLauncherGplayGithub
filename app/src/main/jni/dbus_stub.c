/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * DroidBridge WebRTC compatibility shim.
 *
 * Minecraft Snapshot 7+ currently ships dev.onvoid.webrtc desktop Linux
 * natives. On Android, that linux-aarch64 native asks for libdbus-1.so.3.
 * Android does not provide DBus, so this shim satisfies the dynamic linker
 * and reports that no DBus service/session is available.
 */

#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define EXPORT __attribute__((visibility("default")))

typedef int dbus_bool_t;
typedef int dbus_int32_t;
typedef unsigned int dbus_uint32_t;
typedef unsigned long long dbus_uint64_t;
typedef long long dbus_int64_t;
typedef unsigned long DBusBusType;
typedef unsigned long DBusHandlerResult;
typedef unsigned long DBusDispatchStatus;
typedef unsigned long DBusWatchFlags;
typedef unsigned long DBusTimeoutFlags;

typedef struct DBusConnection { int placeholder; } DBusConnection;
typedef struct DBusMessage { int placeholder; } DBusMessage;
typedef struct DBusMessageIter DBusMessageIter;
typedef struct DBusPendingCall DBusPendingCall;
typedef struct DBusWatch DBusWatch;
typedef struct DBusTimeout DBusTimeout;
typedef struct DBusPreallocatedSend DBusPreallocatedSend;
typedef struct DBusServer DBusServer;
typedef struct DBusSignatureIter DBusSignatureIter;

typedef struct {
    const char* name;
    const char* message;
    unsigned int dummy1;
    unsigned int dummy2;
    unsigned int dummy3;
    unsigned int dummy4;
    void* dummy5;
} DBusError;

struct DBusMessageIter {
    void* dummy1;
    void* dummy2;
    uint32_t dummy3;
    int dummy4;
    int dummy5;
    int dummy6;
    int dummy7;
    int dummy8;
    int dummy9;
    int dummy10;
    int dummy11;
    int pad1;
    void* pad2;
    void* pad3;
};

struct DBusSignatureIter {
    void* dummy1;
    void* dummy2;
    uint32_t dummy8;
    int dummy12;
};

#define DBUS_BUS_SESSION 0
#define DBUS_BUS_SYSTEM 1
#define DBUS_BUS_STARTER 2

#define DBUS_HANDLER_RESULT_HANDLED 0
#define DBUS_HANDLER_RESULT_NOT_YET_HANDLED 1
#define DBUS_HANDLER_RESULT_NEED_MEMORY 2

#define DBUS_DISPATCH_DATA_REMAINS 0
#define DBUS_DISPATCH_COMPLETE 1
#define DBUS_DISPATCH_NEED_MEMORY 2

#define DBUS_TYPE_INVALID 0
#define DBUS_TYPE_STRING 's'
#define DBUS_TYPE_OBJECT_PATH 'o'
#define DBUS_TYPE_SIGNATURE 'g'
#define DBUS_TYPE_ARRAY 'a'
#define DBUS_TYPE_VARIANT 'v'
#define DBUS_TYPE_STRUCT 'r'
#define DBUS_TYPE_DICT_ENTRY 'e'
#define DBUS_TYPE_BOOLEAN 'b'
#define DBUS_TYPE_BYTE 'y'
#define DBUS_TYPE_INT16 'n'
#define DBUS_TYPE_UINT16 'q'
#define DBUS_TYPE_INT32 'i'
#define DBUS_TYPE_UINT32 'u'
#define DBUS_TYPE_INT64 'x'
#define DBUS_TYPE_UINT64 't'
#define DBUS_TYPE_DOUBLE 'd'

static DBusConnection g_connection;
static DBusMessage g_message;

EXPORT int dbus_threads_init_default(void) {
    return 1;
}

EXPORT void dbus_error_init(DBusError* error) {
    if (!error) return;
    memset(error, 0, sizeof(DBusError));
}

EXPORT void dbus_error_free(DBusError* error) {
    if (!error) return;
    memset(error, 0, sizeof(DBusError));
}

EXPORT int dbus_error_is_set(const DBusError* error) {
    return error && error->name != NULL;
}

EXPORT void dbus_set_error(DBusError* error, const char* name, const char* message, ...) {
    if (!error) return;
    error->name = name;
    error->message = message;
}

EXPORT void dbus_set_error_const(DBusError* error, const char* name, const char* message) {
    if (!error) return;
    error->name = name;
    error->message = message;
}

EXPORT void dbus_move_error(DBusError* src, DBusError* dest) {
    if (!src || !dest) return;
    *dest = *src;
    memset(src, 0, sizeof(DBusError));
}

EXPORT void dbus_shutdown(void) {
}

EXPORT DBusConnection* dbus_bus_get(DBusBusType type, DBusError* error) {
    if (error) {
        error->name = "org.freedesktop.DBus.Error.NoServer";
        error->message = "DBus is unavailable on Android";
    }
    return NULL;
}

EXPORT DBusConnection* dbus_bus_get_private(DBusBusType type, DBusError* error) {
    return dbus_bus_get(type, error);
}

EXPORT DBusConnection* dbus_bus_get_shared(DBusBusType type, DBusError* error) {
    return dbus_bus_get(type, error);
}

EXPORT DBusConnection* dbus_connection_open(const char* address, DBusError* error) {
    if (error) {
        error->name = "org.freedesktop.DBus.Error.NoServer";
        error->message = "DBus is unavailable on Android";
    }
    return NULL;
}

EXPORT DBusConnection* dbus_connection_open_private(const char* address, DBusError* error) {
    return dbus_connection_open(address, error);
}

EXPORT DBusConnection* dbus_connection_ref(DBusConnection* connection) {
    return connection;
}

EXPORT void dbus_connection_unref(DBusConnection* connection) {
}

EXPORT void dbus_connection_close(DBusConnection* connection) {
}

EXPORT int dbus_connection_get_is_connected(DBusConnection* connection) {
    return 0;
}

EXPORT int dbus_connection_get_is_authenticated(DBusConnection* connection) {
    return 0;
}

EXPORT int dbus_connection_get_is_anonymous(DBusConnection* connection) {
    return 1;
}

EXPORT int dbus_connection_get_unix_fd(DBusConnection* connection, int* fd) {
    if (fd) *fd = -1;
    return 0;
}

EXPORT int dbus_connection_get_socket(DBusConnection* connection, int* fd) {
    if (fd) *fd = -1;
    return 0;
}

EXPORT void dbus_connection_set_exit_on_disconnect(DBusConnection* connection, int exit_on_disconnect) {
}

EXPORT int dbus_connection_read_write_dispatch(DBusConnection* connection, int timeout_milliseconds) {
    return 0;
}

EXPORT int dbus_connection_read_write(DBusConnection* connection, int timeout_milliseconds) {
    return 0;
}

EXPORT DBusDispatchStatus dbus_connection_get_dispatch_status(DBusConnection* connection) {
    return DBUS_DISPATCH_COMPLETE;
}

EXPORT DBusDispatchStatus dbus_connection_dispatch(DBusConnection* connection) {
    return DBUS_DISPATCH_COMPLETE;
}

EXPORT int dbus_connection_has_messages_to_send(DBusConnection* connection) {
    return 0;
}

EXPORT void dbus_connection_flush(DBusConnection* connection) {
}

EXPORT int dbus_connection_send(DBusConnection* connection, DBusMessage* message, dbus_uint32_t* serial) {
    if (serial) *serial = 0;
    return 0;
}

EXPORT DBusMessage* dbus_connection_send_with_reply_and_block(DBusConnection* connection, DBusMessage* message, int timeout_milliseconds, DBusError* error) {
    if (error) {
        error->name = "org.freedesktop.DBus.Error.NoReply";
        error->message = "DBus is unavailable on Android";
    }
    return NULL;
}

EXPORT int dbus_connection_send_with_reply(DBusConnection* connection, DBusMessage* message, DBusPendingCall** pending_return, int timeout_milliseconds) {
    if (pending_return) *pending_return = NULL;
    return 0;
}

EXPORT DBusMessage* dbus_connection_pop_message(DBusConnection* connection) {
    return NULL;
}

EXPORT int dbus_connection_add_filter(DBusConnection* connection, void* function, void* user_data, void* free_data_function) {
    return 1;
}

EXPORT void dbus_connection_remove_filter(DBusConnection* connection, void* function, void* user_data) {
}

EXPORT int dbus_connection_register_object_path(DBusConnection* connection, const char* path, const void* vtable, void* user_data) {
    return 0;
}

EXPORT int dbus_connection_unregister_object_path(DBusConnection* connection, const char* path) {
    return 0;
}

EXPORT int dbus_connection_set_watch_functions(DBusConnection* connection, void* add_function, void* remove_function, void* toggled_function, void* data, void* free_data_function) {
    return 1;
}

EXPORT int dbus_connection_set_timeout_functions(DBusConnection* connection, void* add_function, void* remove_function, void* toggled_function, void* data, void* free_data_function) {
    return 1;
}

EXPORT void dbus_connection_set_wakeup_main_function(DBusConnection* connection, void* wakeup_main_function, void* data, void* free_data_function) {
}

EXPORT int dbus_bus_register(DBusConnection* connection, DBusError* error) {
    if (error) {
        error->name = "org.freedesktop.DBus.Error.NoServer";
        error->message = "DBus is unavailable on Android";
    }
    return 0;
}

EXPORT int dbus_bus_request_name(DBusConnection* connection, const char* name, unsigned int flags, DBusError* error) {
    if (error) {
        error->name = "org.freedesktop.DBus.Error.NoServer";
        error->message = "DBus is unavailable on Android";
    }
    return -1;
}

EXPORT int dbus_bus_release_name(DBusConnection* connection, const char* name, DBusError* error) {
    return -1;
}

EXPORT const char* dbus_bus_get_unique_name(DBusConnection* connection) {
    return ":0.0";
}

EXPORT int dbus_bus_name_has_owner(DBusConnection* connection, const char* name, DBusError* error) {
    return 0;
}

EXPORT int dbus_bus_start_service_by_name(DBusConnection* connection, const char* name, dbus_uint32_t flags, dbus_uint32_t* result, DBusError* error) {
    if (result) *result = 0;
    return 0;
}

EXPORT void dbus_bus_add_match(DBusConnection* connection, const char* rule, DBusError* error) {
}

EXPORT void dbus_bus_remove_match(DBusConnection* connection, const char* rule, DBusError* error) {
}

EXPORT DBusMessage* dbus_message_new(int message_type) {
    return &g_message;
}

EXPORT DBusMessage* dbus_message_new_method_call(const char* destination, const char* path, const char* iface, const char* method) {
    return &g_message;
}

EXPORT DBusMessage* dbus_message_new_method_return(DBusMessage* method_call) {
    return &g_message;
}

EXPORT DBusMessage* dbus_message_new_signal(const char* path, const char* iface, const char* name) {
    return &g_message;
}

EXPORT DBusMessage* dbus_message_new_error(DBusMessage* reply_to, const char* error_name, const char* error_message) {
    return &g_message;
}

EXPORT DBusMessage* dbus_message_ref(DBusMessage* message) {
    return message;
}

EXPORT void dbus_message_unref(DBusMessage* message) {
}

EXPORT int dbus_message_get_type(DBusMessage* message) {
    return 0;
}

EXPORT int dbus_message_is_method_call(DBusMessage* message, const char* iface, const char* method) {
    return 0;
}

EXPORT int dbus_message_is_signal(DBusMessage* message, const char* iface, const char* signal_name) {
    return 0;
}

EXPORT int dbus_message_is_error(DBusMessage* message, const char* error_name) {
    return 0;
}

EXPORT int dbus_message_has_destination(DBusMessage* message, const char* name) {
    return 0;
}

EXPORT int dbus_message_has_sender(DBusMessage* message, const char* name) {
    return 0;
}

EXPORT int dbus_message_has_signature(DBusMessage* message, const char* signature) {
    return signature == NULL || signature[0] == '\0';
}

EXPORT const char* dbus_message_get_path(DBusMessage* message) {
    return NULL;
}

EXPORT const char* dbus_message_get_interface(DBusMessage* message) {
    return NULL;
}

EXPORT const char* dbus_message_get_member(DBusMessage* message) {
    return NULL;
}

EXPORT const char* dbus_message_get_error_name(DBusMessage* message) {
    return NULL;
}

EXPORT const char* dbus_message_get_destination(DBusMessage* message) {
    return NULL;
}

EXPORT const char* dbus_message_get_sender(DBusMessage* message) {
    return NULL;
}

EXPORT const char* dbus_message_get_signature(DBusMessage* message) {
    return "";
}

EXPORT dbus_uint32_t dbus_message_get_serial(DBusMessage* message) {
    return 0;
}

EXPORT dbus_uint32_t dbus_message_get_reply_serial(DBusMessage* message) {
    return 0;
}

EXPORT void dbus_message_set_serial(DBusMessage* message, dbus_uint32_t serial) {
}

EXPORT int dbus_message_set_reply_serial(DBusMessage* message, dbus_uint32_t reply_serial) {
    return 1;
}

EXPORT int dbus_message_set_destination(DBusMessage* message, const char* destination) {
    return 1;
}

EXPORT int dbus_message_set_sender(DBusMessage* message, const char* sender) {
    return 1;
}

EXPORT int dbus_message_set_path(DBusMessage* message, const char* path) {
    return 1;
}

EXPORT int dbus_message_set_interface(DBusMessage* message, const char* iface) {
    return 1;
}

EXPORT int dbus_message_set_member(DBusMessage* message, const char* member) {
    return 1;
}

EXPORT int dbus_message_append_args(DBusMessage* message, int first_arg_type, ...) {
    return 1;
}

EXPORT int dbus_message_get_args(DBusMessage* message, DBusError* error, int first_arg_type, ...) {
    return first_arg_type == DBUS_TYPE_INVALID;
}

EXPORT int dbus_message_contains_unix_fds(DBusMessage* message) {
    return 0;
}

EXPORT int dbus_message_iter_init(DBusMessage* message, DBusMessageIter* iter) {
    if (iter) memset(iter, 0, sizeof(DBusMessageIter));
    return 0;
}

EXPORT void dbus_message_iter_init_append(DBusMessage* message, DBusMessageIter* iter) {
    if (iter) memset(iter, 0, sizeof(DBusMessageIter));
}

EXPORT int dbus_message_iter_get_arg_type(DBusMessageIter* iter) {
    return DBUS_TYPE_INVALID;
}

EXPORT int dbus_message_iter_get_element_type(DBusMessageIter* iter) {
    return DBUS_TYPE_INVALID;
}

EXPORT int dbus_message_iter_next(DBusMessageIter* iter) {
    return 0;
}

EXPORT void dbus_message_iter_recurse(DBusMessageIter* iter, DBusMessageIter* sub) {
    if (sub) memset(sub, 0, sizeof(DBusMessageIter));
}

EXPORT void dbus_message_iter_get_basic(DBusMessageIter* iter, void* value) {
    if (value) memset(value, 0, sizeof(void*));
}

EXPORT int dbus_message_iter_append_basic(DBusMessageIter* iter, int type, const void* value) {
    return 1;
}

EXPORT int dbus_message_iter_open_container(DBusMessageIter* iter, int type, const char* contained_signature, DBusMessageIter* sub) {
    if (sub) memset(sub, 0, sizeof(DBusMessageIter));
    return 1;
}

EXPORT int dbus_message_iter_close_container(DBusMessageIter* iter, DBusMessageIter* sub) {
    return 1;
}

EXPORT void dbus_message_iter_abandon_container(DBusMessageIter* iter, DBusMessageIter* sub) {
}

EXPORT DBusPendingCall* dbus_pending_call_ref(DBusPendingCall* pending) {
    return pending;
}

EXPORT void dbus_pending_call_unref(DBusPendingCall* pending) {
}

EXPORT int dbus_pending_call_set_notify(DBusPendingCall* pending, void* function, void* user_data, void* free_user_data) {
    return 1;
}

EXPORT int dbus_pending_call_get_completed(DBusPendingCall* pending) {
    return 1;
}

EXPORT DBusMessage* dbus_pending_call_steal_reply(DBusPendingCall* pending) {
    return NULL;
}

EXPORT void dbus_pending_call_block(DBusPendingCall* pending) {
}

EXPORT int dbus_watch_get_enabled(DBusWatch* watch) {
    return 0;
}

EXPORT int dbus_watch_get_unix_fd(DBusWatch* watch) {
    return -1;
}

EXPORT int dbus_watch_get_socket(DBusWatch* watch) {
    return -1;
}

EXPORT unsigned int dbus_watch_get_flags(DBusWatch* watch) {
    return 0;
}

EXPORT void* dbus_watch_get_data(DBusWatch* watch) {
    return NULL;
}

EXPORT void dbus_watch_set_data(DBusWatch* watch, void* data, void* free_data_function) {
}

EXPORT int dbus_watch_handle(DBusWatch* watch, unsigned int flags) {
    return 0;
}

EXPORT int dbus_timeout_get_enabled(DBusTimeout* timeout) {
    return 0;
}

EXPORT int dbus_timeout_get_interval(DBusTimeout* timeout) {
    return -1;
}

EXPORT void* dbus_timeout_get_data(DBusTimeout* timeout) {
    return NULL;
}

EXPORT void dbus_timeout_set_data(DBusTimeout* timeout, void* data, void* free_data_function) {
}

EXPORT int dbus_timeout_handle(DBusTimeout* timeout) {
    return 0;
}

EXPORT const char* dbus_get_local_machine_id(void) {
    return "00000000000000000000000000000000";
}

EXPORT void dbus_free(void* memory) {
    free(memory);
}

EXPORT void dbus_free_string_array(char** str_array) {
    if (!str_array) return;
    for (char** p = str_array; *p; ++p) free(*p);
    free(str_array);
}

EXPORT char* dbus_malloc(size_t bytes) {
    return (char*) malloc(bytes);
}

EXPORT char* dbus_malloc0(size_t bytes) {
    return (char*) calloc(1, bytes);
}

EXPORT char* dbus_realloc(void* memory, size_t bytes) {
    return (char*) realloc(memory, bytes);
}

EXPORT int dbus_parse_address(const char* address, void* entry, int* array_len, DBusError* error) {
    if (array_len) *array_len = 0;
    return 0;
}

EXPORT int dbus_signature_validate(const char* signature, DBusError* error) {
    return 1;
}

EXPORT int dbus_signature_validate_single(const char* signature, DBusError* error) {
    return 1;
}

EXPORT int dbus_type_is_basic(int typecode) {
    return typecode != DBUS_TYPE_INVALID;
}

EXPORT int dbus_type_is_container(int typecode) {
    return typecode == DBUS_TYPE_ARRAY || typecode == DBUS_TYPE_VARIANT || typecode == DBUS_TYPE_STRUCT || typecode == DBUS_TYPE_DICT_ENTRY;
}

EXPORT int dbus_type_is_fixed(int typecode) {
    return typecode != DBUS_TYPE_STRING && typecode != DBUS_TYPE_OBJECT_PATH && typecode != DBUS_TYPE_SIGNATURE && typecode != DBUS_TYPE_INVALID;
}

EXPORT void dbus_signature_iter_init(DBusSignatureIter* iter, const char* signature) {
    if (iter) memset(iter, 0, sizeof(DBusSignatureIter));
}

EXPORT int dbus_signature_iter_get_current_type(const DBusSignatureIter* iter) {
    return DBUS_TYPE_INVALID;
}

EXPORT int dbus_signature_iter_get_element_type(const DBusSignatureIter* iter) {
    return DBUS_TYPE_INVALID;
}

EXPORT int dbus_signature_iter_next(DBusSignatureIter* iter) {
    return 0;
}

EXPORT void dbus_signature_iter_recurse(const DBusSignatureIter* iter, DBusSignatureIter* sub) {
    if (sub) memset(sub, 0, sizeof(DBusSignatureIter));
}

EXPORT char* dbus_signature_iter_get_signature(const DBusSignatureIter* iter) {
    char* out = (char*) malloc(1);
    if (out) out[0] = '\0';
    return out;
}

EXPORT int dbus_validate_path(const char* path, DBusError* error) {
    return 1;
}

EXPORT int dbus_validate_interface(const char* name, DBusError* error) {
    return 1;
}

EXPORT int dbus_validate_member(const char* name, DBusError* error) {
    return 1;
}

EXPORT int dbus_validate_error_name(const char* name, DBusError* error) {
    return 1;
}

EXPORT int dbus_validate_bus_name(const char* name, DBusError* error) {
    return 1;
}

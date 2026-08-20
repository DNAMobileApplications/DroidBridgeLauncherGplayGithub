/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * Small app-local libcutils compatibility shim for DroidBridge's Mesa Android build.
 *
 * Some Mesa/Freedreno Android builds retain DT_NEEDED/libcutils symbols that are
 * private to the Android platform namespace. Android app classloader namespaces
 * cannot link against the platform/private libcutils.so, so DroidBridge provides
 * a minimal local library with the libcutils symbols Mesa/libdrm paths probe.
 */

#include <android/log.h>
#include <errno.h>
#include <ctype.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

#ifndef PROPERTY_VALUE_MAX
#define PROPERTY_VALUE_MAX 92
#endif

#ifndef AID_USER_OFFSET
#define AID_USER_OFFSET 100000
#endif

#ifndef AID_APP_START
#define AID_APP_START 10000
#endif

#ifndef AID_APP_END
#define AID_APP_END 19999
#endif

extern "C" {

typedef unsigned int userid_t;
typedef unsigned int appid_t;

__attribute__((constructor)) static void db_cutils_constructor(void) {
    __android_log_print(ANDROID_LOG_INFO, "DroidBridgeCutils",
                        "loaded app-local libcutils property shim");
}

/* Some Android libcutils builds export this global. Keep it present and disabled. */
uint64_t atrace_enabled_tags = 0;

static size_t db_strlcpy(char *dst, const char *src, size_t size) {
    if (src == nullptr) src = "";
    size_t src_len = strlen(src);
    if (size != 0 && dst != nullptr) {
        size_t copy_len = src_len >= size ? size - 1 : src_len;
        memcpy(dst, src, copy_len);
        dst[copy_len] = '\0';
    }
    return src_len;
}


static const char *db_getenv_nonempty(const char *name) {
    if (name == nullptr || name[0] == '\0') return nullptr;
    const char *value = getenv(name);
    return (value != nullptr && value[0] != '\0') ? value : nullptr;
}

static const char *db_exact_env_for_android_property(const char *key) {
    if (key == nullptr) return nullptr;

    if (!strcmp(key, "mesa.loader.driver.override") ||
        !strcmp(key, "debug.mesa.loader.driver.override") ||
        !strcmp(key, "persist.mesa.loader.driver.override") ||
        !strcmp(key, "mesa.loader.driver_override") ||
        !strcmp(key, "debug.mesa.loader.driver_override") ||
        !strcmp(key, "mesa.driver.override") ||
        !strcmp(key, "debug.mesa.driver.override") ||
        !strcmp(key, "mesa.driver_override") ||
        !strcmp(key, "debug.mesa.driver_override") ||
        !strcmp(key, "mesa.driver") ||
        !strcmp(key, "debug.mesa.driver")) {
        const char *value = db_getenv_nonempty("MESA_LOADER_DRIVER_OVERRIDE");
        if (value) return value;
        value = db_getenv_nonempty("DROIDBRIDGE_MESA_DRIVER");
        if (value) return value;
        value = db_getenv_nonempty("GALLIUM_DRIVER");
        if (value) return value;
    }

    if (!strcmp(key, "gallium.driver") ||
        !strcmp(key, "mesa.gallium.driver") ||
        !strcmp(key, "debug.gallium.driver") ||
        !strcmp(key, "debug.mesa.gallium.driver")) {
        /* DroidBridge direct Freedreno KGSL does not force Gallium's pipe driver.
         * Only honor GALLIUM_DRIVER when the launcher explicitly sets it for
         * non-KGSL renderers. Do NOT fall back to DROIDBRIDGE_MESA_DRIVER=kgsl;
         * that variable selects Mesa's Android KGSL loader path, not Gallium's
         * pipe driver name.
         */
        return db_getenv_nonempty("GALLIUM_DRIVER");
    }

    if (!strcmp(key, "mesa.debug") || !strcmp(key, "debug.mesa.debug")) {
        const char *value = db_getenv_nonempty("MESA_DEBUG");
        if (value) return value;
    }

    if (!strcmp(key, "mesa.log.level") || !strcmp(key, "debug.mesa.log.level")) {
        const char *value = db_getenv_nonempty("MESA_LOG_LEVEL");
        if (value) return value;
    }

    if (!strcmp(key, "libgl.debug") || !strcmp(key, "debug.libgl.debug")) {
        const char *value = db_getenv_nonempty("LIBGL_DEBUG");
        if (value) return value;
    }

    if (!strcmp(key, "fd.mesa.debug") || !strcmp(key, "debug.fd.mesa.debug")) {
        const char *value = db_getenv_nonempty("FD_MESA_DEBUG");
        if (value) return value;
    }

    if (!strcmp(key, "mesa.android.no_kms_swrast") ||
        !strcmp(key, "debug.mesa.android.no_kms_swrast")) {
        const char *value = db_getenv_nonempty("MESA_ANDROID_NO_KMS_SWRAST");
        if (value) return value;
    }

    if (!strcmp(key, "mesa.shader_cache_dir") || !strcmp(key, "debug.mesa.shader_cache_dir")) {
        const char *value = db_getenv_nonempty("MESA_SHADER_CACHE_DIR");
        if (value) return value;
    }

    return nullptr;
}

static const char *db_transformed_env_for_property(const char *key, char *name_buf, size_t name_size) {
    if (key == nullptr || key[0] == '\0' || name_buf == nullptr || name_size == 0) return nullptr;

    size_t out = 0;
    for (size_t i = 0; key[i] != '\0' && out + 1 < name_size; ++i) {
        unsigned char c = (unsigned char) key[i];
        if (isalnum(c)) {
            name_buf[out++] = (char) toupper(c);
        } else {
            name_buf[out++] = '_';
        }
    }
    name_buf[out] = '\0';

    const char *value = db_getenv_nonempty(name_buf);
    if (value) return value;

    char prefixed[PROPERTY_VALUE_MAX * 2];
    snprintf(prefixed, sizeof(prefixed), "DROIDBRIDGE_PROP_%s", name_buf);
    return db_getenv_nonempty(prefixed);
}


static bool db_should_log_property_key(const char *key) {
    if (key == nullptr) return false;
    if (strstr(key, "mesa") != nullptr || strstr(key, "gallium") != nullptr ||
        strstr(key, "libgl") != nullptr || strstr(key, "kgsl") != nullptr ||
        strstr(key, "driver") != nullptr) {
        return true;
    }
    return db_getenv_nonempty("DROIDBRIDGE_MESA") != nullptr;
}

typedef int (*db_system_property_get_fn)(const char *key, char *value);

static int db_real_system_property_get(const char *key, char *value) {
    static db_system_property_get_fn real_get = nullptr;
    static bool looked_up = false;

    if (!looked_up) {
        looked_up = true;
        real_get = (db_system_property_get_fn)dlsym(RTLD_NEXT, "__system_property_get");
        if (real_get == nullptr) {
            void *libc_handle = dlopen("libc.so", RTLD_NOW | RTLD_LOCAL);
            if (libc_handle != nullptr) {
                real_get = (db_system_property_get_fn)dlsym(libc_handle, "__system_property_get");
            }
        }
    }

    if (real_get == nullptr || value == nullptr) return 0;
    return real_get(key, value);
}

static int db_property_get_from_env(const char *key, char *value) {
    const char *mapped = db_exact_env_for_android_property(key);
    char transformed[PROPERTY_VALUE_MAX * 2];
    const char *source_name = "exact";

    if (mapped == nullptr) {
        if (key != nullptr && strstr(key, "gallium") != nullptr) {
            return 0;
        }
        mapped = db_transformed_env_for_property(key, transformed, sizeof(transformed));
        source_name = transformed;
    }

    if (mapped == nullptr) return 0;

    db_strlcpy(value, mapped, PROPERTY_VALUE_MAX);

    if (key != nullptr &&
        (strstr(key, "mesa") != nullptr || strstr(key, "gallium") != nullptr || strstr(key, "libgl") != nullptr)) {
        __android_log_print(ANDROID_LOG_INFO, "DroidBridgeCutils",
                            "property_get(%s) using env %s=%s", key, source_name, value);
    }

    return static_cast<int>(strlen(value));
}


int __system_property_get(const char *key, char *value) {
    if (value == nullptr) return 0;
    value[0] = '\0';

    if (key != nullptr) {
        int len = db_property_get_from_env(key, value);
        if (len > 0) {
            if (db_should_log_property_key(key)) {
                __android_log_print(ANDROID_LOG_INFO, "DroidBridgeCutils",
                                    "__system_property_get(%s) using DroidBridge env=%s", key, value);
            }
            return len;
        }

        len = db_real_system_property_get(key, value);
        if (len > 0) {
            if (db_should_log_property_key(key)) {
                __android_log_print(ANDROID_LOG_INFO, "DroidBridgeCutils",
                                    "__system_property_get(%s) using Android property=%s", key, value);
            }
            return len;
        }
    }

    return 0;
}

int property_get(const char *key, char *value, const char *default_value) {
    if (value == nullptr) return 0;
    value[0] = '\0';

    if (key != nullptr) {
        /* Prefer DroidBridge env overrides before Android properties. */
        int len = db_property_get_from_env(key, value);
        if (len > 0) return len;

        len = db_real_system_property_get(key, value);
        if (len > 0) {
            if (db_should_log_property_key(key)) {
                __android_log_print(ANDROID_LOG_INFO, "DroidBridgeCutils",
                                    "property_get(%s) using Android property=%s", key, value);
            }
            return len;
        }
    }

    if (default_value != nullptr) {
        db_strlcpy(value, default_value, PROPERTY_VALUE_MAX);
        return static_cast<int>(strlen(value));
    }

    return 0;
}

int property_set(const char *key, const char *value) {
    (void) key;
    (void) value;
    errno = EPERM;
    return -1;
}

int property_list(void (*propfn)(const char *key, const char *value, void *cookie), void *cookie) {
    (void) propfn;
    (void) cookie;
    return 0;
}

int8_t property_get_bool(const char *key, int8_t default_value) {
    char value[PROPERTY_VALUE_MAX];
    if (property_get(key, value, nullptr) <= 0) return default_value;

    if (!strcasecmp(value, "1") || !strcasecmp(value, "y") || !strcasecmp(value, "yes")
            || !strcasecmp(value, "on") || !strcasecmp(value, "true")) {
        return 1;
    }
    if (!strcasecmp(value, "0") || !strcasecmp(value, "n") || !strcasecmp(value, "no")
            || !strcasecmp(value, "off") || !strcasecmp(value, "false")) {
        return 0;
    }
    return default_value;
}

int32_t property_get_int32(const char *key, int32_t default_value) {
    char value[PROPERTY_VALUE_MAX];
    if (property_get(key, value, nullptr) <= 0) return default_value;
    char *end = nullptr;
    long parsed = strtol(value, &end, 0);
    return (end != value) ? static_cast<int32_t>(parsed) : default_value;
}

uint32_t property_get_uint32(const char *key, uint32_t default_value) {
    char value[PROPERTY_VALUE_MAX];
    if (property_get(key, value, nullptr) <= 0) return default_value;
    char *end = nullptr;
    unsigned long parsed = strtoul(value, &end, 0);
    return (end != value) ? static_cast<uint32_t>(parsed) : default_value;
}

int64_t property_get_int64(const char *key, int64_t default_value) {
    char value[PROPERTY_VALUE_MAX];
    if (property_get(key, value, nullptr) <= 0) return default_value;
    char *end = nullptr;
    long long parsed = strtoll(value, &end, 0);
    return (end != value) ? static_cast<int64_t>(parsed) : default_value;
}

int android_get_control_socket(const char *name) {
    (void) name;
    errno = ENOENT;
    return -1;
}

int socket_make_sockaddr_un(const char *name, int namespaceId, struct sockaddr_un *p_addr, socklen_t *alen) {
    (void) name;
    (void) namespaceId;
    (void) p_addr;
    (void) alen;
    errno = ENOSYS;
    return -1;
}

int socket_local_client(const char *name, int namespaceId, int type) {
    (void) name;
    (void) namespaceId;
    (void) type;
    errno = ENOENT;
    return -1;
}

int socket_local_server(const char *name, int namespaceId, int type) {
    (void) name;
    (void) namespaceId;
    (void) type;
    errno = EACCES;
    return -1;
}

int socket_network_client(const char *host, int port, int type) {
    (void) host;
    (void) port;
    (void) type;
    errno = ENOSYS;
    return -1;
}

int fs_prepare_dir(const char *path, mode_t mode, uid_t uid, gid_t gid) {
    (void) uid;
    (void) gid;
    if (path == nullptr) {
        errno = EINVAL;
        return -1;
    }
    if (mkdir(path, mode) == 0 || errno == EEXIST) return 0;
    return -1;
}

userid_t multiuser_get_user_id(uid_t uid) {
    return static_cast<userid_t>(uid / AID_USER_OFFSET);
}

appid_t multiuser_get_app_id(uid_t uid) {
    return static_cast<appid_t>(uid % AID_USER_OFFSET);
}

uid_t multiuser_get_uid(userid_t user_id, appid_t app_id) {
    return static_cast<uid_t>(user_id * AID_USER_OFFSET + (app_id % AID_USER_OFFSET));
}

uid_t multiuser_get_shared_app_gid(uid_t uid) {
    appid_t app_id = multiuser_get_app_id(uid);
    if (app_id >= AID_APP_START && app_id <= AID_APP_END) {
        return static_cast<uid_t>(50000 + app_id - AID_APP_START);
    }
    return static_cast<uid_t>(-1);
}

int qtaguid_tagSocket(int sockfd, int tag, uid_t uid) {
    (void) sockfd;
    (void) tag;
    (void) uid;
    return 0;
}

int qtaguid_untagSocket(int sockfd) {
    (void) sockfd;
    return 0;
}

int qtaguid_setCounterSet(int counterSetNum, uid_t uid) {
    (void) counterSetNum;
    (void) uid;
    return 0;
}

int qtaguid_deleteTagData(int tag, uid_t uid) {
    (void) tag;
    (void) uid;
    return 0;
}

/*
 * libcutils atrace compatibility.
 * Mesa only needs these symbols to exist. Tracing is disabled for app-local use.
 */
void atrace_init() {
    atrace_enabled_tags = 0;
}

void atrace_update_tags() {
    atrace_enabled_tags = 0;
}

uint64_t atrace_get_enabled_tags() {
    return 0;
}

int atrace_is_tag_enabled(uint64_t tag) {
    (void) tag;
    return 0;
}

void atrace_set_tracing_enabled(bool enabled) {
    (void) enabled;
    atrace_enabled_tags = 0;
}

void atrace_begin(uint64_t tag, const char *name) {
    (void) tag;
    (void) name;
}

void atrace_end(uint64_t tag) {
    (void) tag;
}

void atrace_async_begin(uint64_t tag, const char *name, int32_t cookie) {
    (void) tag;
    (void) name;
    (void) cookie;
}

void atrace_async_end(uint64_t tag, const char *name, int32_t cookie) {
    (void) tag;
    (void) name;
    (void) cookie;
}

void atrace_int(uint64_t tag, const char *name, int32_t value) {
    (void) tag;
    (void) name;
    (void) value;
}

void atrace_int64(uint64_t tag, const char *name, int64_t value) {
    (void) tag;
    (void) name;
    (void) value;
}

void atrace_begin_body(const char *name) {
    (void) name;
}

void atrace_end_body() {
}

void atrace_async_begin_body(const char *name, int32_t cookie) {
    (void) name;
    (void) cookie;
}

void atrace_async_end_body(const char *name, int32_t cookie) {
    (void) name;
    (void) cookie;
}

void atrace_int_body(const char *name, int32_t value) {
    (void) name;
    (void) value;
}

void atrace_int64_body(const char *name, int64_t value) {
    (void) name;
    (void) value;
}

void klog_init() {
}

void klog_set_level(int level) {
    (void) level;
}

void klog_write(int level, const char *fmt, ...) {
    (void) level;
    (void) fmt;
}

} // extern "C"

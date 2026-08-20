/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * App-local libhardware compatibility shim for DroidBridge's Mesa Android/KGSL build.
 *
 * Why this exists:
 * Mesa's Android EGL/KGSL build can have a DT_NEEDED dependency on libhardware.so.
 * Android app classloader namespaces cannot normally link against the private platform
 * libhardware.so, so the app must provide an app-local SONAME-compatible shim.
 *
 * Important:
 * The previous namespace-delegate version could accidentally resolve hw_get_module()
 * back to this same shim. When platform/libhardware internally resolved the public
 * symbol again, it recursed until the JVM reported an irrecoverable native stack
 * overflow. This version validates every candidate with dladdr() and rejects symbols
 * that do not come from a platform path before delegating.
 */

#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <link.h>
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <stdio.h>
#include <stdarg.h>
#include <unistd.h>
#include <sys/system_properties.h>

#define DB_LOG_TAG "DroidBridgeHWShim"

/*
 * Mirror shim diagnostics to both logcat and the JVM stdout/stderr streams.
 * DroidBridge latestlog.txt usually captures the JVM streams but not every
 * Android logcat tag, so this lets the next latestlog show whether Mesa reached
 * gralloc, vendor HAL candidates, or ENOENT fallback without needing a separate
 * Logcat paste.
 */
static int db_hwshim_log_print(int prio, const char *tag, const char *fmt, ...) {
    char buffer[2048];
    memset(buffer, 0, sizeof(buffer));

    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buffer, sizeof(buffer) - 1, fmt ? fmt : "", ap);
    va_end(ap);

    int result = __android_log_print(prio, tag ? tag : DB_LOG_TAG, "%s", buffer);

    const char *safe_tag = tag ? tag : DB_LOG_TAG;
    fprintf(stderr, "%s: %s\n", safe_tag, buffer);
    fflush(stderr);
    fprintf(stdout, "%s: %s\n", safe_tag, buffer);
    fflush(stdout);
    return result;
}

#define __android_log_print db_hwshim_log_print

__attribute__((constructor))
static void droidbridge_hwshim_on_load() {
    __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                        "loaded app-local libhardware diagnostic shim");
}

#ifndef ANDROID_DLEXT_USE_NAMESPACE
#define ANDROID_DLEXT_USE_NAMESPACE 0x200
#endif

extern "C" {

struct hw_module_methods_t;

struct hw_module_t {
    uint32_t tag;
    uint16_t module_api_version;
    uint16_t hal_api_version;
    const char *id;
    const char *name;
    const char *author;
    struct hw_module_methods_t *methods;
    void *dso;
#if defined(__LP64__)
    uint64_t reserved[25];
#else
    uint32_t reserved[25];
#endif
};

typedef int (*hw_get_module_fn)(const char *id, const struct hw_module_t **module);
typedef int (*hw_get_module_by_class_fn)(const char *class_id, const char *inst, const struct hw_module_t **module);
typedef int (*hw_module_exists_fn)(const char *path, const char *name);
typedef void *(*android_get_exported_namespace_fn)(const char *name);

typedef struct android_dlextinfo_compat {
    uint64_t flags;
    void *reserved_addr;
    size_t reserved_size;
    int relro_fd;
    int library_fd;
    off64_t library_fd_offset;
    void *library_namespace;
} android_dlextinfo_compat;

typedef void *(*android_dlopen_ext_fn)(const char *filename, int flag,
                                       const android_dlextinfo_compat *extinfo);

int hw_get_module(const char *id, const struct hw_module_t **module);
int hw_get_module_by_class(const char *class_id, const char *inst, const struct hw_module_t **module);
int hw_module_exists(const char *path, const char *name);

static void *g_real_hardware = nullptr;
static bool g_real_hardware_tried = false;
static bool g_opening_real_hardware = false;
static bool g_inside_delegate = false;

static bool starts_with(const char *value, const char *prefix) {
    if (value == nullptr || prefix == nullptr) return false;
    return strncmp(value, prefix, strlen(prefix)) == 0;
}

static bool is_platform_path(const char *path) {
    return starts_with(path, "/system/")
            || starts_with(path, "/system_ext/")
            || starts_with(path, "/vendor/")
            || starts_with(path, "/odm/")
            || starts_with(path, "/product/")
            || starts_with(path, "/apex/");
}

static bool symbol_is_safe_platform_symbol(void *symbol, const char *symbol_name) {
    if (symbol == nullptr) return false;

    // Direct self-address checks catch the easy case.
    if ((strcmp(symbol_name, "hw_get_module") == 0
            && symbol == reinterpret_cast<void *>(&hw_get_module))
            || (strcmp(symbol_name, "hw_get_module_by_class") == 0
            && symbol == reinterpret_cast<void *>(&hw_get_module_by_class))
            || (strcmp(symbol_name, "hw_module_exists") == 0
            && symbol == reinterpret_cast<void *>(&hw_module_exists))) {
        __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                            "rejecting self symbol %s by address", symbol_name);
        return false;
    }

    Dl_info info{};
    if (dladdr(symbol, &info) == 0 || info.dli_fname == nullptr) {
        __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                            "rejecting %s because dladdr failed", symbol_name);
        return false;
    }

    // This is the important guard: if dlsym() returned a symbol from the app's
    // packaged libhardware.so shim, delegation will recurse. Only accept symbols
    // whose backing library is visibly from Android platform/vendor/apex paths.
    if (!is_platform_path(info.dli_fname)) {
        __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                            "rejecting %s from non-platform library: %s",
                            symbol_name, info.dli_fname);
        return false;
    }

    __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                        "accepted %s from platform library: %s",
                        symbol_name, info.dli_fname);
    return true;
}

static void log_dl_error(const char *prefix, const char *target) {
    const char *error = dlerror();
    __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG, "%s %s: %s",
                        prefix ? prefix : "dlopen failed",
                        target ? target : "<null>",
                        error ? error : "unknown");
}

static void *sym_from_handle(void *handle, const char *name) {
    if (handle == nullptr || name == nullptr) return nullptr;

    void *value = dlsym(handle, name);
    if (value != nullptr) return value;

    // Bionic exposes some namespace functions through libdl_android.cpp as
    // weak wrappers around hidden __loader_* symbols. Some devices do not
    // expose android_get_exported_namespace() directly to the app namespace,
    // but the loader symbol may still be visible from libdl/libc. Try both.
    char loader_name[160];
    memset(loader_name, 0, sizeof(loader_name));
    snprintf(loader_name, sizeof(loader_name) - 1, "__loader_%s", name);
    value = dlsym(handle, loader_name);
    if (value != nullptr) {
        __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                            "resolved linker symbol %s via %s", name, loader_name);
    }
    return value;
}

static void *sym_default(const char *name) {
    void *value = sym_from_handle(RTLD_DEFAULT, name);
    if (value != nullptr) return value;

    static const char *libraries[] = {
            "libdl.so",
            "libdl_android.so",
#if defined(__aarch64__) || defined(__x86_64__)
            "/system/lib64/libdl_android.so",
            "/apex/com.android.runtime/lib64/bionic/libdl_android.so",
            "/apex/com.android.runtime/lib64/bionic/libdl.so",
            "/system/lib64/libc.so",
            "/apex/com.android.runtime/lib64/bionic/libc.so",
#else
            "/system/lib/libdl_android.so",
            "/apex/com.android.runtime/lib/bionic/libdl_android.so",
            "/apex/com.android.runtime/lib/bionic/libdl.so",
            "/system/lib/libc.so",
            "/apex/com.android.runtime/lib/bionic/libc.so",
#endif
            "libc.so",
            nullptr
    };

    for (int i = 0; libraries[i] != nullptr; ++i) {
        void *handle = dlopen(libraries[i], RTLD_NOW | RTLD_LOCAL);
        if (handle == nullptr) {
            const char *error = dlerror();
            __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                                "linker helper open failed %s: %s",
                                libraries[i], error ? error : "unknown");
            continue;
        }

        value = sym_from_handle(handle, name);
        if (value != nullptr) {
            __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                                "resolved linker symbol %s from %s", name, libraries[i]);
            return value;
        }
    }

    __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                        "linker symbol unavailable: %s", name ? name : "<null>");
    return nullptr;
}

static void *open_with_namespace(const char *path) {
    android_get_exported_namespace_fn get_ns =
            reinterpret_cast<android_get_exported_namespace_fn>(
                    sym_default("android_get_exported_namespace"));
    android_dlopen_ext_fn dlopen_ext =
            reinterpret_cast<android_dlopen_ext_fn>(sym_default("android_dlopen_ext"));

    if (get_ns == nullptr || dlopen_ext == nullptr) {
        __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                            "android namespace loader unavailable for %s", path);
        return nullptr;
    }

    static const char *namespaces[] = {
            // sphal is the important one for gralloc/vendor HAL access on
            // Treble devices. vndk/default are diagnostic fallbacks.
            "sphal",
            "vndk",
            "system",
            "default",
            nullptr
    };

    for (int i = 0; namespaces[i] != nullptr; ++i) {
        void *ns = get_ns(namespaces[i]);
        if (ns == nullptr) {
            __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                                "namespace %s unavailable", namespaces[i]);
            continue;
        }

        android_dlextinfo_compat ext{};
        ext.flags = ANDROID_DLEXT_USE_NAMESPACE;
        ext.library_namespace = ns;

        void *handle = dlopen_ext(path, RTLD_NOW | RTLD_LOCAL, &ext);
        if (handle != nullptr) {
            void *sym = dlsym(handle, "hw_get_module");
            if (symbol_is_safe_platform_symbol(sym, "hw_get_module")) {
                __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                                    "delegating to platform %s via namespace %s",
                                    path, namespaces[i]);
                return handle;
            }
            __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                                "namespace handle for %s rejected", path);
            continue;
        }

        log_dl_error("namespace dlopen failed", path);
    }

    return nullptr;
}

static void *open_real_hardware() {
    if (g_real_hardware_tried) return g_real_hardware;

    if (g_opening_real_hardware) {
        __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                            "recursive platform libhardware lookup blocked");
        return nullptr;
    }

    g_opening_real_hardware = true;
    g_real_hardware_tried = true;

#if defined(__aarch64__) || defined(__x86_64__)
    static const char *paths[] = {
            "/system/lib64/libhardware.so",
            "/apex/com.android.vndk.v33/lib64/libhardware.so",
            "/apex/com.android.vndk.current/lib64/libhardware.so",
            "/vendor/lib64/libhardware.so",
            nullptr
    };
#else
    static const char *paths[] = {
            "/system/lib/libhardware.so",
            "/apex/com.android.vndk.v33/lib/libhardware.so",
            "/apex/com.android.vndk.current/lib/libhardware.so",
            "/vendor/lib/libhardware.so",
            nullptr
    };
#endif

    for (int i = 0; paths[i] != nullptr; ++i) {
        void *candidate = open_with_namespace(paths[i]);
        if (candidate != nullptr) {
            g_real_hardware = candidate;
            g_opening_real_hardware = false;
            return g_real_hardware;
        }

        // Diagnostic fallback only. This usually fails from the app classloader namespace.
        candidate = dlopen(paths[i], RTLD_NOW | RTLD_LOCAL);
        if (candidate != nullptr) {
            void *sym = dlsym(candidate, "hw_get_module");
            if (symbol_is_safe_platform_symbol(sym, "hw_get_module")) {
                __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                                    "delegating to platform %s by direct dlopen", paths[i]);
                g_real_hardware = candidate;
                g_opening_real_hardware = false;
                return g_real_hardware;
            }
            __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                                "direct handle for %s rejected", paths[i]);
        }
        log_dl_error("direct dlopen failed", paths[i]);
    }

    __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                        "real platform libhardware unavailable; falling back to ENOENT stub");
    g_opening_real_hardware = false;
    return nullptr;
}


static bool safe_copy_property(char *out, size_t out_size, const char *key) {
    if (out == nullptr || out_size == 0 || key == nullptr) return false;
    memset(out, 0, out_size);
    int len = __system_property_get(key, out);
    if (len <= 0) return false;
    out[out_size - 1] = '\0';
    // Keep candidate names safe; property values should never contain slashes here.
    for (size_t i = 0; out[i] != '\0'; ++i) {
        char c = out[i];
        bool ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
                  (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
        if (!ok) {
            out[0] = '\0';
            return false;
        }
    }
    return true;
}

static void add_candidate_name(char names[][96], int *count, int max_count, const char *name) {
    if (names == nullptr || count == nullptr || name == nullptr || name[0] == '\0') return;
    if (*count >= max_count) return;

    char normalized[96];
    memset(normalized, 0, sizeof(normalized));
    if (strstr(name, ".so") != nullptr) {
        snprintf(normalized, sizeof(normalized) - 1, "%s", name);
    } else {
        snprintf(normalized, sizeof(normalized) - 1, "gralloc.%s.so", name);
    }

    for (int i = 0; i < *count; ++i) {
        if (strcmp(names[i], normalized) == 0) return;
    }
    snprintf(names[*count], 95, "%s", normalized);
    (*count)++;
}

static void *dlopen_hal_path(const char *path) {
    if (path == nullptr || path[0] == '\0') return nullptr;

    android_get_exported_namespace_fn get_ns =
            reinterpret_cast<android_get_exported_namespace_fn>(
                    sym_default("android_get_exported_namespace"));
    android_dlopen_ext_fn dlopen_ext =
            reinterpret_cast<android_dlopen_ext_fn>(sym_default("android_dlopen_ext"));

    if (get_ns != nullptr && dlopen_ext != nullptr) {
        static const char *namespaces[] = {"sphal", "vndk", "system", "default", nullptr};
        for (int i = 0; namespaces[i] != nullptr; ++i) {
            void *ns = get_ns(namespaces[i]);
            if (ns == nullptr) continue;

            android_dlextinfo_compat ext{};
            ext.flags = ANDROID_DLEXT_USE_NAMESPACE;
            ext.library_namespace = ns;
            void *handle = dlopen_ext(path, RTLD_NOW | RTLD_LOCAL, &ext);
            if (handle != nullptr) {
                __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                                    "opened HAL candidate via namespace %s: %s",
                                    namespaces[i], path);
                return handle;
            }
        }
    }

    void *handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (handle != nullptr) {
        __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                            "opened HAL candidate directly: %s", path);
        return handle;
    }
    return nullptr;
}

static bool module_symbol_from_handle(void *handle, const char *path, const struct hw_module_t **module) {
    if (handle == nullptr || module == nullptr) return false;

    void *sym = dlsym(handle, "HMI");
    if (sym == nullptr) sym = dlsym(handle, "HAL_MODULE_INFO_SYM");
    if (sym == nullptr) {
        const char *error = dlerror();
        __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                            "HAL candidate has no module symbol %s: %s",
                            path ? path : "<unknown>", error ? error : "unknown");
        return false;
    }

    struct hw_module_t *candidate = reinterpret_cast<struct hw_module_t *>(sym);
    if (candidate->id == nullptr || strcmp(candidate->id, "gralloc") != 0) {
        __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                            "HAL candidate rejected, id=%s path=%s",
                            candidate->id ? candidate->id : "<null>", path ? path : "<unknown>");
        return false;
    }

    candidate->dso = handle;
    *module = candidate;
    __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                        "loaded gralloc HAL directly id=%s name=%s path=%s module=%p",
                        candidate->id ? candidate->id : "<null>",
                        candidate->name ? candidate->name : "<null>",
                        path ? path : "<unknown>", candidate);
    return true;
}

static bool try_direct_gralloc_hal(const char *inst, const struct hw_module_t **module) {
    if (module == nullptr) return false;
    *module = nullptr;

    char names[48][96];
    int count = 0;
    memset(names, 0, sizeof(names));

    char value[PROP_VALUE_MAX];
    if (inst != nullptr && inst[0] != '\0') add_candidate_name(names, &count, 48, inst);
    if (safe_copy_property(value, sizeof(value), "ro.hardware.gralloc")) add_candidate_name(names, &count, 48, value);
    if (safe_copy_property(value, sizeof(value), "ro.hardware")) add_candidate_name(names, &count, 48, value);
    if (safe_copy_property(value, sizeof(value), "ro.board.platform")) add_candidate_name(names, &count, 48, value);
    if (safe_copy_property(value, sizeof(value), "ro.product.board")) add_candidate_name(names, &count, 48, value);
    if (safe_copy_property(value, sizeof(value), "ro.boot.hardware")) add_candidate_name(names, &count, 48, value);

    // Common Qualcomm/Android fallback module names. The direct path matters because
    // Android's app namespace often blocks platform libhardware.so, but may still allow
    // dlopen of the vendor gralloc module when using the exported sphal namespace.
    static const char *fallbacks[] = {
            "default", "qti", "qcom", "adreno", "msm", "msmnile", "kona",
            "lahaina", "taro", "kalama", "pineapple", "sm8550", "sm8475",
            nullptr
    };
    for (int i = 0; fallbacks[i] != nullptr; ++i) add_candidate_name(names, &count, 48, fallbacks[i]);

#if defined(__aarch64__) || defined(__x86_64__)
    static const char *dirs[] = {
            "/odm/lib64/hw", "/vendor/lib64/hw", "/system_ext/lib64/hw", "/system/lib64/hw", nullptr
    };
#else
    static const char *dirs[] = {
            "/odm/lib/hw", "/vendor/lib/hw", "/system_ext/lib/hw", "/system/lib/hw", nullptr
    };
#endif

    for (int d = 0; dirs[d] != nullptr; ++d) {
        for (int n = 0; n < count; ++n) {
            char path[256];
            memset(path, 0, sizeof(path));
            snprintf(path, sizeof(path) - 1, "%s/%s", dirs[d], names[n]);

            void *handle = dlopen_hal_path(path);
            if (handle == nullptr) continue;
            if (module_symbol_from_handle(handle, path, module)) return true;
        }
    }

    __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                        "direct gralloc HAL load failed after %d candidate names", count);
    return false;
}

static void *real_symbol(const char *name) {
    void *handle = open_real_hardware();
    if (handle == nullptr) return nullptr;

    void *symbol = dlsym(handle, name);
    if (!symbol_is_safe_platform_symbol(symbol, name)) return nullptr;
    return symbol;
}

int hw_get_module(const char *id, const struct hw_module_t **module) {
    if (g_inside_delegate) {
        if (module != nullptr) *module = nullptr;
        errno = ENOENT;
        __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                            "recursive hw_get_module(%s) blocked", id ? id : "<null>");
        return -ENOENT;
    }

    hw_get_module_fn real = reinterpret_cast<hw_get_module_fn>(real_symbol("hw_get_module"));
    if (real != nullptr) {
        g_inside_delegate = true;
        int result = real(id, module);
        g_inside_delegate = false;
        __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                            "hw_get_module(%s) delegated result=%d module=%p",
                            id ? id : "<null>", result, module ? *module : nullptr);
        return result;
    }

    if (id != nullptr && strcmp(id, "gralloc") == 0 && try_direct_gralloc_hal(nullptr, module)) {
        __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                            "hw_get_module(gralloc) satisfied by direct HAL module=%p",
                            module ? *module : nullptr);
        return 0;
    }

    if (module != nullptr) *module = nullptr;
    errno = ENOENT;
    __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                        "hw_get_module(%s) stubbed ENOENT", id ? id : "<null>");
    return -ENOENT;
}

int hw_get_module_by_class(const char *class_id, const char *inst, const struct hw_module_t **module) {
    if (g_inside_delegate) {
        if (module != nullptr) *module = nullptr;
        errno = ENOENT;
        __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                            "recursive hw_get_module_by_class(%s,%s) blocked",
                            class_id ? class_id : "<null>", inst ? inst : "<null>");
        return -ENOENT;
    }

    hw_get_module_by_class_fn real = reinterpret_cast<hw_get_module_by_class_fn>(
            real_symbol("hw_get_module_by_class"));
    if (real != nullptr) {
        g_inside_delegate = true;
        int result = real(class_id, inst, module);
        g_inside_delegate = false;
        __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                            "hw_get_module_by_class(%s,%s) delegated result=%d module=%p",
                            class_id ? class_id : "<null>", inst ? inst : "<null>",
                            result, module ? *module : nullptr);
        return result;
    }

    if (class_id != nullptr && strcmp(class_id, "gralloc") == 0 && try_direct_gralloc_hal(inst, module)) {
        __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                            "hw_get_module_by_class(gralloc,%s) satisfied by direct HAL module=%p",
                            inst ? inst : "<null>", module ? *module : nullptr);
        return 0;
    }

    if (module != nullptr) *module = nullptr;
    errno = ENOENT;
    __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                        "hw_get_module_by_class(%s,%s) stubbed ENOENT",
                        class_id ? class_id : "<null>", inst ? inst : "<null>");
    return -ENOENT;
}

int hw_module_exists(const char *path, const char *name) {
    hw_module_exists_fn real = reinterpret_cast<hw_module_exists_fn>(real_symbol("hw_module_exists"));
    if (real != nullptr) {
        g_inside_delegate = true;
        int result = real(path, name);
        g_inside_delegate = false;
        __android_log_print(ANDROID_LOG_INFO, DB_LOG_TAG,
                            "hw_module_exists(%s,%s) delegated result=%d",
                            path ? path : "<null>", name ? name : "<null>", result);
        return result;
    }

    errno = ENOENT;
    return 0;
}

} // extern "C"

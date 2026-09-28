/*
 * DroidBridge Launcher native runtime bridge component.
 *
 * Original project:
 *
 * Original license: GNU Lesser General Public License v3.0,
 * unless this file or a bundled component states a different license.
 *
 * DroidBridge modifications:
 * Copyright (c) 2026 DNA Mobile Applications.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */

//
// Created by maks on 05.06.2023.
// Modifiled by Vera-Firefly on 17.01.2025.
// DroidBridge: Android 13+ namespace creation fallback and diagnostics.
//
#include "nsbypass.h"
#include <dlfcn.h>
#include <android/dlext.h>
#include <android/log.h>
#include <sys/mman.h>
#include <string.h>
#include <strings.h>
#include <stdio.h>
#include <stdarg.h>
#include <stdlib.h>
#include <linux/limits.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <elf.h>
#include <stdint.h>
#include <stdbool.h>

#define OP_MS 0b11111100000000000000000000000000
#define BL_OP 0b10010100000000000000000000000000
#define BL_IM 0b00000011111111111111111111111111
#define SEARCH_PATH "/system/lib64"
#define ELF_EHDR Elf64_Ehdr
#define ELF_SHDR Elf64_Shdr
#define ELF_HALF Elf64_Half
#define ELF_XWORD Elf64_Xword
#define ELF_DYN Elf64_Dyn

#ifndef RTLD_NOLOAD
#define RTLD_NOLOAD 0x00004
#endif

static const char* NS_TAG = "DroidBridgeNSBypass";

static void ns_log(const char* fmt, ...) {
    char buffer[2048];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_INFO, NS_TAG, "%s", buffer);
    fprintf(stderr, "%s: %s\n", NS_TAG, buffer);
    fflush(stderr);
    fprintf(stdout, "%s: %s\n", NS_TAG, buffer);
    fflush(stdout);
}

static size_t droidbridge_page_size(void) {
    long value = sysconf(_SC_PAGESIZE);
    return value > 0 ? (size_t)value : (size_t)4096;
}

static void* droidbridge_page_start(const void* pointer) {
    const size_t page_size = droidbridge_page_size();
    return (void*)(((uintptr_t)pointer) & ~((uintptr_t)page_size - (uintptr_t)1));
}

typedef void* (*loader_dlopen_t)(const char* filename, int flags, const void* caller_addr);
typedef struct android_namespace_t* (*ld_android_create_namespace_t)(
    const char* name, const char* ld_library_path, const char* default_library_path, uint64_t type,
    const char* permitted_when_isolated_path, struct android_namespace_t* parent, const void* caller_addr);
typedef struct android_namespace_t* (*android_create_namespace_public_t)(
    const char* name, const char* ld_library_path, const char* default_library_path, uint64_t type,
    const char* permitted_when_isolated_path, struct android_namespace_t* parent);
typedef bool (*android_link_namespaces_public_t)(struct android_namespace_t* namespace_from,
                                                struct android_namespace_t* namespace_to,
                                                const char* shared_libs_sonames);
typedef void* (*ld_android_link_namespaces_t)(struct android_namespace_t* namespace_from,
                                              struct android_namespace_t* namespace_to,
                                              const char* shared_libs_sonames);

static ld_android_create_namespace_t hidden_create_namespace = NULL;
static android_create_namespace_public_t public_create_namespace = NULL;
static ld_android_link_namespaces_t hidden_link_namespaces = NULL;
static android_link_namespaces_public_t public_link_namespaces = NULL;
static struct android_namespace_t* driver_namespace = NULL;
static char driver_namespace_path[PATH_MAX * 2];

bool patch_elf_soname(int patchfd, int realfd, uint16_t patchid);

static void resolve_from_handle(void* handle, const char* label) {
    if (handle == NULL) return;

    if (hidden_create_namespace == NULL) {
        hidden_create_namespace = (ld_android_create_namespace_t)dlsym(handle, "__loader_android_create_namespace");
        if (hidden_create_namespace != NULL) {
            ns_log("resolved __loader_android_create_namespace from %s", label);
        }
    }
    if (hidden_link_namespaces == NULL) {
        hidden_link_namespaces = (ld_android_link_namespaces_t)dlsym(handle, "__loader_android_link_namespaces");
        if (hidden_link_namespaces != NULL) {
            ns_log("resolved __loader_android_link_namespaces from %s", label);
        }
    }

    if (public_create_namespace == NULL) {
        public_create_namespace = (android_create_namespace_public_t)dlsym(handle, "android_create_namespace");
        if (public_create_namespace != NULL) {
            ns_log("resolved android_create_namespace from %s", label);
        }
    }
    if (public_link_namespaces == NULL) {
        public_link_namespaces = (android_link_namespaces_public_t)dlsym(handle, "android_link_namespaces");
        if (public_link_namespaces != NULL) {
            ns_log("resolved android_link_namespaces from %s", label);
        }
    }
}

static void* find_branch_label(void* func_start) {
    if (func_start == NULL) return NULL;
    const size_t page_size = droidbridge_page_size();
    void* func_page_start = droidbridge_page_start(func_start);
    mprotect(func_page_start, page_size, PROT_READ | PROT_EXEC);
    uint32_t* bl_addr = (uint32_t*)func_start;

    // Keep the search bounded. Some Android linker builds no longer have the
    // simple BL pattern near dlopen; the old unbounded loop could walk too far.
    for (int i = 0; i < 512; i++, bl_addr++) {
        if ((*bl_addr & OP_MS) == BL_OP) {
            return ((char*)bl_addr) + (*bl_addr & BL_IM) * 4;
        }
    }
    return NULL;
}

static bool resolve_namespace_api(void) {
#ifdef ADRENO_POSSIBLE
    resolve_from_handle(RTLD_DEFAULT, "RTLD_DEFAULT");

    void* libdl_handle = dlopen("libdl.so", RTLD_NOW | RTLD_LOCAL);
    if (libdl_handle != NULL) {
        resolve_from_handle(libdl_handle, "libdl.so");
    } else {
        ns_log("dlopen(libdl.so) failed: %s", dlerror());
    }

    void* ld_android_handle = dlopen("ld-android.so", RTLD_NOW | RTLD_LOCAL);
    if (ld_android_handle != NULL) {
        resolve_from_handle(ld_android_handle, "ld-android.so direct");
    } else {
        ns_log("dlopen(ld-android.so) direct failed: %s", dlerror());
    }

    if (hidden_create_namespace == NULL || hidden_link_namespaces == NULL) {
        loader_dlopen_t loader_dlopen = (loader_dlopen_t)find_branch_label((void*)&dlopen);
        if (loader_dlopen != NULL) {
            const size_t page_size = droidbridge_page_size();
            void* loader_page_start = droidbridge_page_start((const void*)loader_dlopen);
            mprotect(loader_page_start, page_size, PROT_READ | PROT_WRITE | PROT_EXEC);

            void* loader_ld_handle = loader_dlopen("ld-android.so", RTLD_NOW | RTLD_LOCAL, &dlopen);
            if (loader_ld_handle != NULL) {
                resolve_from_handle(loader_ld_handle, "ld-android.so loader_dlopen");
            } else {
                ns_log("loader_dlopen(ld-android.so) failed");
            }
        } else {
            ns_log("could not resolve linker loader_dlopen branch from dlopen");
        }
    }

    if (hidden_create_namespace == NULL && public_create_namespace == NULL) {
        ns_log("namespace create symbol unavailable");
        return false;
    }
    return true;
#else
    return false;
#endif
}

static struct android_namespace_t* create_namespace_local(
        const char* name,
        const char* ld_library_path,
        const char* default_library_path,
        uint64_t type,
        const char* permitted_when_isolated_path,
        struct android_namespace_t* parent) {
#ifdef ADRENO_POSSIBLE
    if (hidden_create_namespace != NULL) {
        void* caller = __builtin_return_address(0);
        return hidden_create_namespace(name, ld_library_path, default_library_path, type,
                                       permitted_when_isolated_path, parent, caller);
    }
    if (public_create_namespace != NULL) {
        return public_create_namespace(name, ld_library_path, default_library_path, type,
                                       permitted_when_isolated_path, parent);
    }
#endif
    return NULL;
}

static void link_namespace_basics(struct android_namespace_t* ns) {
#ifdef ADRENO_POSSIBLE
    if (ns == NULL) return;

    const char* libs =
            "ld-android.so:"
            "libdl.so:"
            "liblog.so:"
            "libm.so:"
            "libc.so:"
            "libc++.so:"
            "libandroid.so:"
            "libnativewindow.so:"
            "libsync.so:"
            "libz.so:"
            "libnativeloader.so:"
            "libnativeloader_lazy.so";

    if (hidden_link_namespaces != NULL) {
        hidden_link_namespaces(ns, NULL, libs);
        ns_log("linked namespace with hidden linker api");
        return;
    }
    if (public_link_namespaces != NULL) {
        public_link_namespaces(ns, NULL, libs);
        ns_log("linked namespace with public linker api");
        return;
    }
    ns_log("namespace link symbol unavailable; continuing without explicit link");
#endif
}

static bool path_already_present(const char* list, const char* item) {
    if (list == NULL || item == NULL || item[0] == '\0') return false;

    const size_t item_len = strlen(item);
    const char* cursor = list;
    while (*cursor != '\0') {
        const char* end = strchr(cursor, ':');
        size_t len = end != NULL ? (size_t)(end - cursor) : strlen(cursor);
        if (len == item_len && strncmp(cursor, item, item_len) == 0) {
            return true;
        }
        if (end == NULL) break;
        cursor = end + 1;
    }
    return false;
}

static void append_search_path(char* out, size_t out_size, const char* path) {
    if (out == NULL || out_size == 0 || path == NULL || path[0] == '\0') return;
    if (path_already_present(out, path)) return;

    size_t used = strlen(out);
    if (used + 1 >= out_size) return;
    if (used > 0) {
        out[used++] = ':';
        out[used] = '\0';
    }
    strncat(out, path, out_size - used - 1);
}

static void append_path_list(char* out, size_t out_size, const char* paths) {
    if (paths == NULL || paths[0] == '\0') return;

    const char* cursor = paths;
    while (*cursor != '\0') {
        const char* end = strchr(cursor, ':');
        size_t len = end != NULL ? (size_t)(end - cursor) : strlen(cursor);
        if (len > 0) {
            char item[PATH_MAX * 2];
            size_t copy_len = len < sizeof(item) - 1 ? len : sizeof(item) - 1;
            memcpy(item, cursor, copy_len);
            item[copy_len] = '\0';
            append_search_path(out, out_size, item);
        }
        if (end == NULL) break;
        cursor = end + 1;
    }
}

static bool ns_env_enabled(const char* name) {
    const char* value = getenv(name);
    return value != NULL
            && value[0] != '\0'
            && strcmp(value, "0") != 0
            && strcasecmp(value, "false") != 0;
}

static void build_search_path(char* out, size_t out_size, const char* lib_search_path) {
    const char* native_dir = getenv("DROIDBRIDGE_MESA_NATIVE_DIR");
    const char* alias_dir = getenv("DROIDBRIDGE_MESA_ALIAS_DIR");
    const char* kopper_vulkan_alias_dir = getenv("DROIDBRIDGE_KOPPER_VULKAN_ALIAS_DIR");
    const bool kopper_turnip = ns_env_enabled("DROIDBRIDGE_KOPPER_FORCE_NAMESPACE_VULKAN");

    out[0] = '\0';

    /*
     * v13: Kopper's private Turnip-loader namespace must resolve Android
     * platform dependencies from /system before app-local compatibility shims.
     * Current FCL does the same fundamental thing: /system/lib64 precedes the
     * custom driver path.  With DroidBridge's old app-first order, the Kopper
     * payload's lightweight libcutils.so could shadow Android's platform
     * libcutils; vendor libhidlbase.so then failed to resolve
     * native_handle_clone while loading the private libvulkan clone.
     *
     * Keep the existing app-first policy for every non-Kopper namespace so this
     * fix cannot regress direct Freedreno/regular Mesa paths.
     */
    if (kopper_turnip) {
        /*
         * v14: Only the dedicated private Vulkan-loader directory precedes
         * /system. Mesa 26.1.x can ignore VULKAN_PTR and open libvulkan.so
         * itself, so expose the Turnip-hooked clone under that filename.
         *
         * Keep /system ahead of every general Mesa/app compatibility directory
         * so Android platform dependencies (notably libcutils/native_handle)
         * retain the v13 system-first fix.
         */
        append_search_path(out, out_size, kopper_vulkan_alias_dir);
        append_search_path(out, out_size, SEARCH_PATH);
        append_path_list(out, out_size, lib_search_path);
        append_search_path(out, out_size, native_dir);
        append_search_path(out, out_size, alias_dir);
        append_search_path(out, out_size, "/system_ext/lib64");
        append_search_path(out, out_size, "/vendor/lib64");
        append_search_path(out, out_size, "/odm/lib64");
        append_search_path(out, out_size, "/product/lib64");
        append_search_path(out, out_size, "/apex/com.android.runtime/lib64");
        append_search_path(out, out_size, "/apex/com.android.art/lib64");
        append_search_path(out, out_size, "/data/local/tmp");
        ns_log("namespace search policy=kopper-vulkan-alias-first-system-second alias=%s path=%s",
               kopper_vulkan_alias_dir != NULL ? kopper_vulkan_alias_dir : "<unset>", out);
        return;
    }

    append_path_list(out, out_size, lib_search_path);
    append_search_path(out, out_size, native_dir);
    append_search_path(out, out_size, alias_dir);
    append_search_path(out, out_size, "/data/local/tmp");
    append_search_path(out, out_size, "/vendor/lib64");
    append_search_path(out, out_size, "/system_ext/lib64");
    append_search_path(out, out_size, "/odm/lib64");
    append_search_path(out, out_size, "/product/lib64");
    append_search_path(out, out_size, "/apex/com.android.runtime/lib64");
    append_search_path(out, out_size, "/apex/com.android.art/lib64");
    append_search_path(out, out_size, SEARCH_PATH);

    ns_log("namespace search policy=app-first path=%s", out);
}

static struct android_namespace_t* try_create_namespace_path(const char* name, const char* path) {
    if (path == NULL || path[0] == '\0') return NULL;

    const char* permitted = "/system/:/system_ext/:/vendor/:/odm/:/product/:/apex/:/data/";
    struct android_namespace_t* ns = create_namespace_local(name, path, path, 3, permitted, NULL);
    ns_log("create namespace name=%s path=%s result=%p", name, path, ns);
    return ns;
}

bool linker_ns_load(const char* lib_search_path) {
#ifdef ADRENO_POSSIBLE
    if (lib_search_path == NULL || lib_search_path[0] == '\0') {
        ns_log("linker_ns_load called with empty path");
        return false;
    }

    if (driver_namespace != NULL) {
        if (strcmp(driver_namespace_path, lib_search_path) == 0) {
            ns_log("reusing existing namespace path=%s ns=%p", driver_namespace_path, driver_namespace);
            return true;
        }
        ns_log("reusing existing namespace despite path change old=%s new=%s ns=%p",
               driver_namespace_path, lib_search_path, driver_namespace);
        return true;
    }

    if (!resolve_namespace_api()) {
        return false;
    }

    char full_path[PATH_MAX * 4];
    build_search_path(full_path, sizeof(full_path), lib_search_path);

    driver_namespace = try_create_namespace_path("droidbridge_mesa_namespace", full_path);

    const bool kopper_turnip = ns_env_enabled("DROIDBRIDGE_KOPPER_FORCE_NAMESPACE_VULKAN");
    if (driver_namespace == NULL && !kopper_turnip) {
        driver_namespace = try_create_namespace_path("droidbridge_mesa_namespace_min", lib_search_path);
    }

    const char* native_dir = getenv("DROIDBRIDGE_MESA_NATIVE_DIR");
    if (driver_namespace == NULL && !kopper_turnip && native_dir != NULL && native_dir[0] != '\0') {
        driver_namespace = try_create_namespace_path("droidbridge_mesa_namespace_native", native_dir);
    }
    if (driver_namespace == NULL && kopper_turnip) {
        ns_log("Kopper system-first namespace creation failed; refusing app-only fallback");
    }

    if (driver_namespace == NULL) {
        ns_log("all namespace create attempts failed for path=%s", lib_search_path);
        return false;
    }

    snprintf(driver_namespace_path, sizeof(driver_namespace_path), "%s", lib_search_path);
    link_namespace_basics(driver_namespace);
    ns_log("namespace ready path=%s ns=%p", lib_search_path, driver_namespace);
    return true;
#else
    return false;
#endif
}

void* linker_ns_dlopen(const char* name, int flag) {
#ifdef ADRENO_POSSIBLE
    if (driver_namespace == NULL) {
        ns_log("linker_ns_dlopen(%s) without namespace", name ? name : "<null>");
        return NULL;
    }
    android_dlextinfo dlextinfo = {
        .flags = ANDROID_DLEXT_USE_NAMESPACE,
        .library_namespace = driver_namespace
    };
    dlerror();
    void* handle = android_dlopen_ext(name, flag, &dlextinfo);
    if (handle == NULL) {
        const char* err = dlerror();
        ns_log("android_dlopen_ext namespace failed name=%s flags=0x%x error=%s",
               name ? name : "<null>", flag, err ? err : "unknown");
    } else {
        ns_log("android_dlopen_ext namespace loaded name=%s handle=%p", name ? name : "<null>", handle);
    }
    return handle;
#else
    return NULL;
#endif
}

void* linker_ns_dlopen_unique(const char* tmpdir, const char* name, int flags) {
#ifdef ADRENO_POSSIBLE
    char pathbuf[PATH_MAX];
    static uint16_t patch_id;
    int patch_fd, real_fd;
    snprintf(pathbuf, PATH_MAX, "%s/%d_p.so", tmpdir, patch_id);
    patch_fd = open(pathbuf, O_CREAT | O_RDWR, S_IRUSR | S_IWUSR);
    if (patch_fd == -1) return NULL;
    snprintf(pathbuf, PATH_MAX, "%s/%s", SEARCH_PATH, name);
    real_fd = open(pathbuf, O_RDONLY);
    if (real_fd == -1)
    {
        close(patch_fd);
        return NULL;
    }

    if (!patch_elf_soname(patch_fd, real_fd, patch_id)) {
        close(patch_fd);
        close(real_fd);
        return NULL;
    }

    android_dlextinfo extinfo = {
        .flags = ANDROID_DLEXT_USE_NAMESPACE | ANDROID_DLEXT_USE_LIBRARY_FD,
        .library_fd = patch_fd,
        .library_namespace = driver_namespace
    };
    snprintf(pathbuf, PATH_MAX, "/proc/self/fd/%d", patch_fd);
    dlerror();
    void* handle = android_dlopen_ext(pathbuf, flags, &extinfo);
    const char* load_error = handle == NULL ? dlerror() : NULL;
    ns_log("linker_ns_dlopen_unique source=%s patchId=%03x fd=%d handle=%p error=%s",
           name, patch_id, patch_fd, handle, load_error != NULL ? load_error : "<none>");
    return handle;
#else
    return NULL;
#endif
}

bool patch_elf_soname(int patchfd, int realfd, uint16_t patchid) {
    struct stat realstat;
    if (fstat(realfd, &realstat))
        return false;

    if (ftruncate64(patchfd, realstat.st_size) == -1)
        return false;

    char* target = mmap(NULL, realstat.st_size, PROT_READ | PROT_WRITE, MAP_SHARED, patchfd, 0);
    if (!target)
        return false;

    if (read(realfd, target, realstat.st_size) != realstat.st_size)
    {
        munmap(target, realstat.st_size);
        return false;
    }
    close(realfd);

    ELF_EHDR *ehdr = (ELF_EHDR*)target;
    ELF_SHDR *shdr = (ELF_SHDR*)(target + ehdr->e_shoff);
    for (ELF_HALF i = 0; i < ehdr->e_shnum; i++)
    {
        ELF_SHDR *hdr = &shdr[i];
        if (hdr->sh_type == SHT_DYNAMIC) {
            char* strtab = target + shdr[hdr->sh_link].sh_offset;
            ELF_DYN *dynEntries = (ELF_DYN*)(target + hdr->sh_offset);
            for (ELF_XWORD k = 0; k < (hdr->sh_size / hdr->sh_entsize);k++)
            {
                ELF_DYN* dynEntry = &dynEntries[k];
                if (dynEntry->d_tag == DT_SONAME)
                {
                    char* soname = strtab + dynEntry->d_un.d_val;
                    char sprb[4];
                    snprintf(sprb, 4, "%03x", patchid);
                    memcpy(soname, sprb, 3);
                    munmap(target, realstat.st_size);
                    return true;
                }
            }
        }
    }
    munmap(target, realstat.st_size);
    return false;
}


static bool patch_elf_soname_to_name(int patchfd, int realfd, const char* replacement_soname) {
    if (replacement_soname == NULL || replacement_soname[0] == '\0') return false;

    struct stat realstat;
    if (fstat(realfd, &realstat))
        return false;

    if (ftruncate64(patchfd, realstat.st_size) == -1)
        return false;

    char* target = mmap(NULL, realstat.st_size, PROT_READ | PROT_WRITE, MAP_SHARED, patchfd, 0);
    if (!target)
        return false;

    if (read(realfd, target, realstat.st_size) != realstat.st_size) {
        munmap(target, realstat.st_size);
        return false;
    }
    close(realfd);

    ELF_EHDR *ehdr = (ELF_EHDR*)target;
    ELF_SHDR *shdr = (ELF_SHDR*)(target + ehdr->e_shoff);
    for (ELF_HALF i = 0; i < ehdr->e_shnum; i++) {
        ELF_SHDR *hdr = &shdr[i];
        if (hdr->sh_type == SHT_DYNAMIC) {
            char* strtab = target + shdr[hdr->sh_link].sh_offset;
            ELF_DYN *dynEntries = (ELF_DYN*)(target + hdr->sh_offset);
            for (ELF_XWORD k = 0; k < (hdr->sh_size / hdr->sh_entsize); k++) {
                ELF_DYN* dynEntry = &dynEntries[k];
                if (dynEntry->d_tag == DT_SONAME) {
                    char* soname = strtab + dynEntry->d_un.d_val;
                    size_t old_len = strlen(soname);
                    size_t new_len = strlen(replacement_soname);
                    if (new_len > old_len) {
                        ns_log("replacement SONAME '%s' is longer than original '%s'", replacement_soname, soname);
                        munmap(target, realstat.st_size);
                        return false;
                    }
                    memset(soname, 0, old_len);
                    memcpy(soname, replacement_soname, new_len);
                    munmap(target, realstat.st_size);
                    return true;
                }
            }
        }
    }
    munmap(target, realstat.st_size);
    return false;
}

void* linker_ns_dlopen_unique_named(const char* tmpdir, const char* name, const char* unique_soname, int flags) {
#ifdef ADRENO_POSSIBLE
    if (tmpdir == NULL || tmpdir[0] == '\0' || name == NULL || name[0] == '\0' || unique_soname == NULL || unique_soname[0] == '\0') {
        ns_log("linker_ns_dlopen_unique_named invalid args tmpdir=%s name=%s unique=%s",
               tmpdir ? tmpdir : "<null>", name ? name : "<null>", unique_soname ? unique_soname : "<null>");
        return NULL;
    }

    char pathbuf[PATH_MAX];
    int patch_fd, real_fd;
    snprintf(pathbuf, PATH_MAX, "%s/%s", tmpdir, unique_soname);
    unlink(pathbuf);
    patch_fd = open(pathbuf, O_CREAT | O_TRUNC | O_RDWR, S_IRUSR | S_IWUSR);
    if (patch_fd == -1) {
        ns_log("failed creating unique alias %s", pathbuf);
        return NULL;
    }

    snprintf(pathbuf, PATH_MAX, "%s/%s", SEARCH_PATH, name);
    real_fd = open(pathbuf, O_RDONLY);
    if (real_fd == -1) {
        close(patch_fd);
        ns_log("failed opening system loader source %s", pathbuf);
        return NULL;
    }

    if (!patch_elf_soname_to_name(patch_fd, real_fd, unique_soname)) {
        close(patch_fd);
        close(real_fd);
        ns_log("failed patching SONAME for %s -> %s", name, unique_soname);
        return NULL;
    }

    android_dlextinfo extinfo = {
        .flags = ANDROID_DLEXT_USE_NAMESPACE | ANDROID_DLEXT_USE_LIBRARY_FD,
        .library_fd = patch_fd,
        .library_namespace = driver_namespace
    };
    snprintf(pathbuf, PATH_MAX, "/proc/self/fd/%d", patch_fd);
    void* handle = android_dlopen_ext(pathbuf, flags, &extinfo);
    ns_log("linker_ns_dlopen_unique_named source=%s unique=%s handle=%p", name, unique_soname, handle);
    return handle;
#else
    return NULL;
#endif
}

void* linker_ns_dlopen_alias_file(const char* alias_dir,
                                  const char* name,
                                  const char* output_name,
                                  const char* unique_soname,
                                  int flags) {
#ifdef ADRENO_POSSIBLE
    if (driver_namespace == NULL || alias_dir == NULL || alias_dir[0] == '\0'
            || name == NULL || name[0] == '\0'
            || output_name == NULL || output_name[0] == '\0'
            || unique_soname == NULL || unique_soname[0] == '\0') {
        ns_log("linker_ns_dlopen_alias_file invalid args ns=%p dir=%s name=%s output=%s soname=%s",
               driver_namespace,
               alias_dir ? alias_dir : "<null>",
               name ? name : "<null>",
               output_name ? output_name : "<null>",
               unique_soname ? unique_soname : "<null>");
        return NULL;
    }

    if (mkdir(alias_dir, S_IRWXU) != 0 && access(alias_dir, F_OK) != 0) {
        ns_log("linker_ns_dlopen_alias_file failed creating dir=%s", alias_dir);
        return NULL;
    }

    char output_path[PATH_MAX];
    char source_path[PATH_MAX];
    snprintf(output_path, sizeof(output_path), "%s/%s", alias_dir, output_name);
    snprintf(source_path, sizeof(source_path), "%s/%s", SEARCH_PATH, name);

    unlink(output_path);
    int patch_fd = open(output_path, O_CREAT | O_TRUNC | O_RDWR, S_IRUSR | S_IWUSR);
    if (patch_fd == -1) {
        ns_log("linker_ns_dlopen_alias_file failed creating output=%s", output_path);
        return NULL;
    }

    int real_fd = open(source_path, O_RDONLY);
    if (real_fd == -1) {
        ns_log("linker_ns_dlopen_alias_file failed opening source=%s", source_path);
        close(patch_fd);
        unlink(output_path);
        return NULL;
    }

    if (!patch_elf_soname_to_name(patch_fd, real_fd, unique_soname)) {
        ns_log("linker_ns_dlopen_alias_file failed patching source=%s output=%s soname=%s",
               source_path, output_path, unique_soname);
        close(patch_fd);
        unlink(output_path);
        return NULL;
    }

    fsync(patch_fd);
    android_dlextinfo extinfo = {
        .flags = ANDROID_DLEXT_USE_NAMESPACE | ANDROID_DLEXT_USE_LIBRARY_FD,
        .library_fd = patch_fd,
        .library_namespace = driver_namespace
    };

    char fd_path[64];
    snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", patch_fd);
    dlerror();
    void* handle = android_dlopen_ext(fd_path, flags, &extinfo);
    const char* load_error = handle == NULL ? dlerror() : NULL;
    ns_log("linker_ns_dlopen_alias_file source=%s output=%s soname=%s fd=%d handle=%p error=%s",
           source_path, output_path, unique_soname, patch_fd, handle,
           load_error != NULL ? load_error : "<none>");
    close(patch_fd);
    return handle;
#else
    return NULL;
#endif
}


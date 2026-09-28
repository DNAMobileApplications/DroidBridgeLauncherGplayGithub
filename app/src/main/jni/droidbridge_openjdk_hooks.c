#define _GNU_SOURCE

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <errno.h>
#include <sys/mman.h>
#include <unistd.h>

#define TAG "DroidBridgeOpenJDKHooks"

typedef int (*db_present_frame_fn)(void *window, void *original_swap);
typedef int (*db_set_swap_interval_fn)(int interval, void *original_setter);
typedef bool (*db_sdl_swap_window_fn)(void *window);
typedef bool (*db_sdl_set_swap_interval_fn)(int interval);

static db_present_frame_fn g_present_frame = NULL;
static db_set_swap_interval_fn g_set_swap_interval = NULL;
static int g_present_resolve_attempted = 0;
static int g_swap_interval_resolve_attempted = 0;
static int g_logged_direct_fallback = 0;
static int g_logged_swap_interval_fallback = 0;

static db_present_frame_fn resolve_present_frame(void) {
    if (g_present_frame != NULL || g_present_resolve_attempted) return g_present_frame;
    g_present_resolve_attempted = 1;

    g_present_frame = (db_present_frame_fn)dlsym(
            RTLD_DEFAULT,
            "droidbridge_sdl3_present_openjdk_frame");
    if (g_present_frame != NULL) return g_present_frame;

    void *runtime = dlopen("libdroidbridge_runtime.so", RTLD_NOW | RTLD_GLOBAL);
    if (runtime != NULL) {
        g_present_frame = (db_present_frame_fn)dlsym(
                runtime,
                "droidbridge_sdl3_present_openjdk_frame");
    }
    if (g_present_frame == NULL) {
        const char *error = dlerror();
        __android_log_print(
                ANDROID_LOG_ERROR,
                TAG,
                "Direct presentation helper unavailable: %s",
                error != NULL ? error : "unknown");
        fprintf(
                stderr,
                "DroidBridgeSDL3GL: v16 direct presentation helper unavailable error=%s\n",
                error != NULL ? error : "unknown");
    }
    return g_present_frame;
}

static db_set_swap_interval_fn resolve_set_swap_interval(void) {
    if (g_set_swap_interval != NULL || g_swap_interval_resolve_attempted) {
        return g_set_swap_interval;
    }
    g_swap_interval_resolve_attempted = 1;

    g_set_swap_interval = (db_set_swap_interval_fn)dlsym(
            RTLD_DEFAULT,
            "droidbridge_sdl3_set_openjdk_swap_interval");
    if (g_set_swap_interval != NULL) return g_set_swap_interval;

    void *runtime = dlopen("libdroidbridge_runtime.so", RTLD_NOW | RTLD_GLOBAL);
    if (runtime != NULL) {
        g_set_swap_interval = (db_set_swap_interval_fn)dlsym(
                runtime,
                "droidbridge_sdl3_set_openjdk_swap_interval");
    }
    if (g_set_swap_interval == NULL) {
        const char *error = dlerror();
        fprintf(stderr,
                "DroidBridgeSDL3GL: v16 swap-interval helper unavailable error=%s\n",
                error != NULL ? error : "unknown");
    }
    return g_set_swap_interval;
}

JNIEXPORT jboolean JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSdlGlSwapWindow(
        JNIEnv *env,
        jclass clazz,
        jlong window,
        jlong original_function) {
    (void)env;
    (void)clazz;

    void *window_ptr = (void *)(uintptr_t)window;
    void *function_ptr = (void *)(uintptr_t)original_function;
    db_present_frame_fn present = resolve_present_frame();
    if (present != NULL) {
        return present(window_ptr, function_ptr) ? JNI_TRUE : JNI_FALSE;
    }

    db_sdl_swap_window_fn original = (db_sdl_swap_window_fn)function_ptr;
    bool result = original != NULL ? original(window_ptr) : false;
    if (!g_logged_direct_fallback) {
        g_logged_direct_fallback = 1;
        fprintf(
                stderr,
                "DroidBridgeSDL3GL: v16 direct SDL swap fallback result=%d window=%p function=%p\n",
                result ? 1 : 0,
                window_ptr,
                function_ptr);
    }
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSdlGlSetSwapInterval(
        JNIEnv *env,
        jclass clazz,
        jint interval,
        jlong original_function) {
    (void)env;
    (void)clazz;

    void *function_ptr = (void *)(uintptr_t)original_function;
    db_set_swap_interval_fn setter = resolve_set_swap_interval();
    if (setter != NULL) {
        return setter((int)interval, function_ptr) ? JNI_TRUE : JNI_FALSE;
    }

    db_sdl_set_swap_interval_fn original =
            (db_sdl_set_swap_interval_fn)function_ptr;
    bool result = original != NULL ? original((int)interval) : false;
    if (!g_logged_swap_interval_fallback) {
        g_logged_swap_interval_fallback = 1;
        fprintf(stderr,
                "DroidBridgeSDL3GL: v16 direct SDL swap-interval fallback interval=%d result=%d function=%p\n",
                (int)interval,
                result ? 1 : 0,
                function_ptr);
    }
    return result ? JNI_TRUE : JNI_FALSE;
}



typedef int (*db_install_runtime_hooks_fn)(JNIEnv *env);

static db_install_runtime_hooks_fn resolve_install_runtime_hooks(void) {
    dlerror();
    db_install_runtime_hooks_fn install_hooks =
            (db_install_runtime_hooks_fn)dlsym(
                    RTLD_DEFAULT,
                    "droidbridge_install_openjdk_runtime_hooks_for_env");
    if (install_hooks != NULL) return install_hooks;

    void *runtime = dlopen("libdroidbridge_runtime.so", RTLD_NOW | RTLD_GLOBAL);
    if (runtime != NULL) {
        install_hooks = (db_install_runtime_hooks_fn)dlsym(
                runtime,
                "droidbridge_install_openjdk_runtime_hooks_for_env");
    }
    return install_hooks;
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_system_DroidBridgeOpenJdkBootstrap_nativeInstallRuntimeHooks(
        JNIEnv *env,
        jclass clazz) {
    (void)clazz;
    if (env == NULL) return 0;

    db_install_runtime_hooks_fn install_hooks = resolve_install_runtime_hooks();
    if (install_hooks == NULL) {
        const char *error = dlerror();
        __android_log_print(
                ANDROID_LOG_ERROR,
                TAG,
                "Early OpenJDK linker hook entry point unavailable: %s",
                error != NULL ? error : "unknown");
        fprintf(stderr,
                "DroidBridgeNativeMesa: early OpenJDK linker hook entry point unavailable error=%s\n",
                error != NULL ? error : "unknown");
        return 0;
    }

    int result = install_hooks(env);
    __android_log_print(
            result ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
            TAG,
            "Early OpenJDK linker hook install result=%d",
            result);
    fprintf(stderr,
            "DroidBridgeNativeMesa: early OpenJDK linker hook native result=%d\n",
            result);
    return result;
}


/*
 * HotSpot-side access to live renderer state.
 *
 * These JNI exports live in the uniquely named library that is loaded by the
 * embedded OpenJDK class loader. They forward to plain C symbols exported by
 * libdroidbridge_runtime, which is owned by Android/ART.
 */
typedef void (*db_runtime_void_fn)(void);
typedef int (*db_runtime_int_fn)(void);

static void *g_runtime_state_handle = NULL;
static int g_runtime_state_handle_attempted = 0;
static int g_logged_runtime_state_missing = 0;

static void *resolve_runtime_state_symbol(const char *name) {
    if (name == NULL || name[0] == '\0') return NULL;

    dlerror();
    void *symbol = dlsym(RTLD_DEFAULT, name);
    if (symbol != NULL) return symbol;

    if (g_runtime_state_handle == NULL && !g_runtime_state_handle_attempted) {
        g_runtime_state_handle_attempted = 1;
        g_runtime_state_handle = dlopen(
                "libdroidbridge_runtime.so",
                RTLD_NOW | RTLD_GLOBAL);
    }
    if (g_runtime_state_handle != NULL) {
        symbol = dlsym(g_runtime_state_handle, name);
    }
    if (symbol == NULL && !g_logged_runtime_state_missing) {
        g_logged_runtime_state_missing = 1;
        const char *error = dlerror();
        fprintf(stderr,
                "DroidBridgeOpenJDKState: runtime state symbol unavailable name=%s error=%s\n",
                name,
                error != NULL ? error : "unknown");
    }
    return symbol;
}

typedef int (*db_art_sdl_init_fn)(uint32_t flags, void *function);
typedef void (*db_art_sdl_quit_subsystem_fn)(uint32_t flags, void *function);
typedef void (*db_art_sdl_quit_fn)(void *function);
typedef void *(*db_art_sdl_create_window_fn)(
        const char *title, int width, int height, uint64_t flags, void *function);
typedef void *(*db_art_sdl_create_window_properties_fn)(
        uint32_t properties, void *function);
typedef void (*db_art_sdl_destroy_window_fn)(void *window, void *function);

#define DB_ART_SDL_DISPATCH_TABLE_ENV "DROIDBRIDGE_ART_SDL_DISPATCH_TABLE"
#define DB_ART_SDL_DISPATCH_MAGIC UINT64_C(0x444241525453444c)
#define DB_ART_SDL_DISPATCH_VERSION 1u

typedef struct db_art_sdl_dispatch_table {
    uint64_t magic;
    uint32_t version;
    uint32_t size;
    db_art_sdl_init_fn init;
    db_art_sdl_init_fn init_subsystem;
    db_art_sdl_quit_subsystem_fn quit_subsystem;
    db_art_sdl_quit_fn quit;
    db_art_sdl_create_window_fn create_window;
    db_art_sdl_create_window_properties_fn create_window_with_properties;
    db_art_sdl_destroy_window_fn destroy_window;
} db_art_sdl_dispatch_table;

static db_art_sdl_dispatch_table *g_art_sdl_dispatch_table = NULL;
static int g_art_sdl_dispatch_table_attempted = 0;

static db_art_sdl_dispatch_table *resolve_art_sdl_dispatch_table(void) {
    if (g_art_sdl_dispatch_table != NULL || g_art_sdl_dispatch_table_attempted) {
        return g_art_sdl_dispatch_table;
    }
    g_art_sdl_dispatch_table_attempted = 1;
    const char *text = getenv(DB_ART_SDL_DISPATCH_TABLE_ENV);
    if (text == NULL || text[0] == '\0') {
        fprintf(stderr, "DroidBridgeSDL3GL: ART SDL dispatch table env missing\n");
        return NULL;
    }
    char *end = NULL;
    unsigned long long raw = strtoull(text, &end, 0);
    if (raw == 0 || end == text || (end != NULL && *end != '\0')) {
        fprintf(stderr, "DroidBridgeSDL3GL: ART SDL dispatch table invalid value=%s\n", text);
        return NULL;
    }
    db_art_sdl_dispatch_table *table =
            (db_art_sdl_dispatch_table *) (uintptr_t) raw;
    if (table->magic != DB_ART_SDL_DISPATCH_MAGIC
            || table->version != DB_ART_SDL_DISPATCH_VERSION
            || table->size < sizeof(db_art_sdl_dispatch_table)
            || table->init == NULL
            || table->create_window == NULL) {
        fprintf(stderr,
                "DroidBridgeSDL3GL: ART SDL dispatch table validation failed address=%p magic=0x%llx version=%u size=%u\n",
                (void *) table,
                (unsigned long long) table->magic,
                (unsigned int) table->version,
                (unsigned int) table->size);
        return NULL;
    }
    g_art_sdl_dispatch_table = table;
    fprintf(stderr,
            "DroidBridgeSDL3GL: ART SDL dispatch table ready address=%p version=%u size=%u\n",
            (void *) table,
            (unsigned int) table->version,
            (unsigned int) table->size);
    return table;
}

static int art_sdl_dispatch_enabled(void) {
    const char *value = getenv("DROIDBRIDGE_SDL3_ART_DISPATCH");
    return value != NULL && value[0] != '\0' && strcmp(value, "0") != 0
            && strcasecmp(value, "false") != 0
            && strcasecmp(value, "off") != 0
            && strcasecmp(value, "no") != 0;
}

/*
 * Android 10 compatibility for DroidBridge's bundled SDL 3.2.22 image.
 *
 * LWJGL's SDL_Init function pointer identifies the exact libSDL3.so mapping
 * that Minecraft is about to execute. Patch that mapping directly instead of
 * guessing between ART and embedded-OpenJDK linker namespaces. The instruction
 * fingerprint below is taken from DroidBridge's packaged arm64-v8a libSDL3.so
 * and skips only the initial Android Java touch-device enumeration that crashes
 * when SDL cannot obtain an ART JNIEnv from the OpenJDK render thread.
 */
#define DB_SDL322_TOUCH_CALL_OFFSET ((uintptr_t) 0x196974u)
#define DB_SDL322_TOUCH_PREV_EXPECTED ((uint32_t) 0xbd005a80u)
#define DB_SDL322_TOUCH_CALL_EXPECTED ((uint32_t) 0x97fffdefu)
#define DB_SDL322_TOUCH_NEXT_EXPECTED ((uint32_t) 0x97fffc4au)

/* Android mouse driver callbacks in DroidBridge's packaged SDL 3.2.22. */
#define DB_SDL322_CREATE_CURSOR_OFFSET ((uintptr_t) 0x195b4cu)
#define DB_SDL322_SHOW_CURSOR_OFFSET ((uintptr_t) 0x195c58u)
#define DB_SDL322_RELATIVE_MOUSE_OFFSET ((uintptr_t) 0x195d4cu)

#define DB_SDL322_CREATE_CURSOR_WORD0 ((uint32_t) 0xa9bd7bfdu)
#define DB_SDL322_CREATE_CURSOR_WORD1 ((uint32_t) 0xf9000bf5u)
#define DB_SDL322_SHOW_CURSOR_WORD0 ((uint32_t) 0xa9be7bfdu)
#define DB_SDL322_SHOW_CURSOR_WORD1 ((uint32_t) 0xa9014ff4u)
#define DB_SDL322_RELATIVE_MOUSE_WORD0 ((uint32_t) 0xa9be7bfdu)
#define DB_SDL322_RELATIVE_MOUSE_WORD1 ((uint32_t) 0xf9000bf3u)

/* Android 3.2.22 video-backend helpers that normally call SDLActivity. */
#define DB_SDL322_GET_NATIVE_WINDOW_OFFSET ((uintptr_t) 0x96d40u)
#define DB_SDL322_SET_ACTIVITY_TITLE_OFFSET ((uintptr_t) 0x9765cu)
#define DB_SDL322_SET_WINDOW_STYLE_OFFSET ((uintptr_t) 0x976c8u)
#define DB_SDL322_SET_ORIENTATION_OFFSET ((uintptr_t) 0x97704u)
#define DB_SDL322_MINIMIZE_WINDOW_OFFSET ((uintptr_t) 0x977c0u)
#define DB_SDL322_SHOULD_MINIMIZE_OFFSET ((uintptr_t) 0x977ecu)
#define DB_SDL322_SUSPEND_SCREENSAVER_OFFSET ((uintptr_t) 0x982e4u)
#define DB_SDL322_IS_CHROMEBOOK_OFFSET ((uintptr_t) 0x989d4u)
#define DB_SDL322_IS_DEX_OFFSET ((uintptr_t) 0x98a0cu)

#define DB_SDL322_GET_NATIVE_WINDOW_W0 ((uint32_t) 0xa9bd7bfdu)
#define DB_SDL322_GET_NATIVE_WINDOW_W1 ((uint32_t) 0xf9000bf5u)
#define DB_SDL322_GET_NATIVE_WINDOW_W2 ((uint32_t) 0xa9024ff4u)
#define DB_SDL322_GET_NATIVE_WINDOW_W3 ((uint32_t) 0x910003fdu)
#define DB_SDL322_SET_ACTIVITY_TITLE_W0 ((uint32_t) 0xa9be7bfdu)
#define DB_SDL322_SET_ACTIVITY_TITLE_W1 ((uint32_t) 0xa9014ff4u)
#define DB_SDL322_SET_WINDOW_STYLE_W0 ((uint32_t) 0xa9be7bfdu)
#define DB_SDL322_SET_WINDOW_STYLE_W1 ((uint32_t) 0xf9000bf3u)
#define DB_SDL322_SET_ORIENTATION_W0 ((uint32_t) 0xa9bc7bfdu)
#define DB_SDL322_SET_ORIENTATION_W1 ((uint32_t) 0xf9000bf7u)
#define DB_SDL322_MINIMIZE_WINDOW_W0 ((uint32_t) 0xa9bf7bfdu)
#define DB_SDL322_MINIMIZE_WINDOW_W1 ((uint32_t) 0x910003fdu)
#define DB_SDL322_SHOULD_MINIMIZE_W0 ((uint32_t) 0xa9bf7bfdu)
#define DB_SDL322_SHOULD_MINIMIZE_W1 ((uint32_t) 0x910003fdu)
#define DB_SDL322_SUSPEND_SCREENSAVER_W0 ((uint32_t) 0xa9be7bfdu)
#define DB_SDL322_SUSPEND_SCREENSAVER_W1 ((uint32_t) 0xf9000bf3u)
#define DB_SDL322_IS_CHROMEBOOK_W0 ((uint32_t) 0xa9bf7bfdu)
#define DB_SDL322_IS_CHROMEBOOK_W1 ((uint32_t) 0x910003fdu)
#define DB_SDL322_IS_DEX_W0 ((uint32_t) 0xa9bf7bfdu)
#define DB_SDL322_IS_DEX_W1 ((uint32_t) 0x910003fdu)

#define DB_AARCH64_RET ((uint32_t) 0xd65f03c0u)
#define DB_AARCH64_MOV_W0_0 ((uint32_t) 0x2a1f03e0u)
#define DB_AARCH64_LDR_X16_LITERAL_8 ((uint32_t) 0x58000050u)
#define DB_AARCH64_BR_X16 ((uint32_t) 0xd61f0200u)
#define DB_SDL3_PTR_ENV "DROIDBRIDGE_SDL3_ANATIVEWINDOW_PTR"

#define DB_AARCH64_NOP ((uint32_t) 0xd503201fu)
#define DB_AARCH64_MOV_W0_1 ((uint32_t) 0x52800020u)
#define DB_AARCH64_MOV_X0_XZR ((uint32_t) 0xaa1f03e0u)
#define DB_AARCH64_RET ((uint32_t) 0xd65f03c0u)

static int touch_init_bypass_enabled(void) {
    const char *value = getenv("DROIDBRIDGE_SDL3_ANDROID10_SKIP_INIT_TOUCH");
    return value != NULL && value[0] != '\0' && strcmp(value, "0") != 0
            && strcasecmp(value, "false") != 0
            && strcasecmp(value, "off") != 0
            && strcasecmp(value, "no") != 0;
}

static int db_patch_code_words(uint32_t *site,
                               const uint32_t *expected,
                               const uint32_t *replacement,
                               size_t count,
                               const char *label) {
#if !defined(__aarch64__)
    (void) site;
    (void) expected;
    (void) replacement;
    (void) count;
    (void) label;
    return 0;
#else
    if (site == NULL || expected == NULL || replacement == NULL || count == 0) return 0;

    int already = 1;
    for (size_t i = 0; i < count; ++i) {
        uint32_t current = __atomic_load_n(site + i, __ATOMIC_ACQUIRE);
        if (current != replacement[i]) {
            already = 0;
            if (current != expected[i]) {
                fprintf(stderr,
                        "DroidBridgeSDL3GL: Android 10 SDL JNI quarantine fingerprint mismatch label=%s site=%p index=%zu expected=0x%08x current=0x%08x replacement=0x%08x\n",
                        label, (void *) site, i, expected[i], current, replacement[i]);
                return 0;
            }
        }
    }
    if (already) return 1;

    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) page_size = 4096;
    uintptr_t first = (uintptr_t) site;
    uintptr_t last = (uintptr_t) (site + count - 1);
    uintptr_t first_page = first & ~((uintptr_t) page_size - 1u);
    uintptr_t last_page = last & ~((uintptr_t) page_size - 1u);
    size_t span = (size_t) ((last_page - first_page) + (uintptr_t) page_size);

    errno = 0;
    if (mprotect((void *) first_page, span, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        int rwx_errno = errno;
        errno = 0;
        if (mprotect((void *) first_page, span, PROT_READ | PROT_WRITE) != 0) {
            fprintf(stderr,
                    "DroidBridgeSDL3GL: Android 10 SDL JNI quarantine mprotect failed label=%s site=%p rwxErrno=%d rwErrno=%d\n",
                    label, (void *) site, rwx_errno, errno);
            return 0;
        }
        fprintf(stderr,
                "DroidBridgeSDL3GL: Android 10 SDL JNI quarantine RW fallback label=%s site=%p rwxErrno=%d\n",
                label, (void *) site, rwx_errno);
    }

    for (size_t i = 0; i < count; ++i) {
        __atomic_store_n(site + i, replacement[i], __ATOMIC_RELEASE);
    }
    __builtin___clear_cache((char *) site, (char *) (site + count));

    errno = 0;
    if (mprotect((void *) first_page, span, PROT_READ | PROT_EXEC) != 0) {
        fprintf(stderr,
                "DroidBridgeSDL3GL: Android 10 SDL JNI quarantine restore RX failed label=%s site=%p errno=%d\n",
                label, (void *) site, errno);
    }

    for (size_t i = 0; i < count; ++i) {
        if (__atomic_load_n(site + i, __ATOMIC_ACQUIRE) != replacement[i]) return 0;
    }
    return 1;
#endif
}

static void *db_android10_published_native_window(void) {
    const char *text = getenv(DB_SDL3_PTR_ENV);
    if (text == NULL || text[0] == '\0') {
        fprintf(stderr,
                "DroidBridgeSDL3GL: Android 10 native-window bridge has no published ANativeWindow\n");
        return NULL;
    }

    char *end = NULL;
    errno = 0;
    uintptr_t raw = (uintptr_t) strtoull(text, &end, 16);
    if (errno != 0 || end == text || (end != NULL && *end != '\0') || raw == 0) {
        fprintf(stderr,
                "DroidBridgeSDL3GL: Android 10 native-window bridge rejected pointer=%s errno=%d\n",
                text, errno);
        return NULL;
    }

    void *window = (void *) raw;
    typedef void (*acquire_fn)(void *);
    static acquire_fn acquire = NULL;
    static int resolved = 0;
    if (!resolved) {
        resolved = 1;
        void *android = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (android != NULL) {
            acquire = (acquire_fn) dlsym(android, "ANativeWindow_acquire");
        }
    }
    if (acquire != NULL) acquire(window);

    static int logged = 0;
    if (!logged) {
        logged = 1;
        fprintf(stderr,
                "DroidBridgeSDL3GL: Android 10 SDL native-window Java bridge replaced with published ANativeWindow=%p acquire=%d\n",
                window, acquire != NULL ? 1 : 0);
    }
    return window;
}

static int db_patch_absolute_jump(uint32_t *site,
                                  const uint32_t expected[4],
                                  void *target,
                                  const char *label) {
#if !defined(__aarch64__)
    (void) site; (void) expected; (void) target; (void) label;
    return 0;
#else
    uintptr_t address = (uintptr_t) target;
    const uint32_t replacement[4] = {
        DB_AARCH64_LDR_X16_LITERAL_8,
        DB_AARCH64_BR_X16,
        (uint32_t) (address & UINT64_C(0xffffffff)),
        (uint32_t) ((address >> 32) & UINT64_C(0xffffffff))
    };
    return db_patch_code_words(site, expected, replacement, 4, label);
#endif
}

static int patch_exact_sdl322_touch_call(void *sdl_init) {
    if (!touch_init_bypass_enabled() || sdl_init == NULL) return 0;
#if !defined(__aarch64__)
    fprintf(stderr,
            "DroidBridgeSDL3GL: Android 10 SDL JNI quarantine unsupported architecture\n");
    return 0;
#else
    Dl_info info;
    memset(&info, 0, sizeof(info));
    if (dladdr(sdl_init, &info) == 0 || info.dli_fbase == NULL || info.dli_fname == NULL) {
        fprintf(stderr,
                "DroidBridgeSDL3GL: Android 10 SDL JNI quarantine dladdr failed SDL_Init=%p\n",
                sdl_init);
        return 0;
    }

    const char *base_name = strrchr(info.dli_fname, '/');
    base_name = base_name != NULL ? base_name + 1 : info.dli_fname;
    if (strcmp(base_name, "libSDL3.so") != 0) {
        fprintf(stderr,
                "DroidBridgeSDL3GL: Android 10 SDL JNI quarantine rejected image=%s SDL_Init=%p base=%p\n",
                info.dli_fname, sdl_init, info.dli_fbase);
        return 0;
    }

    uintptr_t base = (uintptr_t) info.dli_fbase;

    uint32_t *touch = (uint32_t *) (base + DB_SDL322_TOUCH_CALL_OFFSET);
    uint32_t touch_prev = __atomic_load_n(touch - 1, __ATOMIC_ACQUIRE);
    uint32_t touch_cur = __atomic_load_n(touch, __ATOMIC_ACQUIRE);
    uint32_t touch_next = __atomic_load_n(touch + 1, __ATOMIC_ACQUIRE);
    if (touch_prev != DB_SDL322_TOUCH_PREV_EXPECTED
            || (touch_cur != DB_SDL322_TOUCH_CALL_EXPECTED && touch_cur != DB_AARCH64_NOP)
            || touch_next != DB_SDL322_TOUCH_NEXT_EXPECTED) {
        fprintf(stderr,
                "DroidBridgeSDL3GL: Android 10 SDL JNI quarantine touch fingerprint mismatch image=%s base=%p prev=0x%08x current=0x%08x next=0x%08x SDL_Init=%p\n",
                info.dli_fname, info.dli_fbase,
                touch_prev, touch_cur, touch_next, sdl_init);
        return 0;
    }

    const uint32_t touch_expected[] = { DB_SDL322_TOUCH_CALL_EXPECTED };
    const uint32_t touch_replace[] = { DB_AARCH64_NOP };
    int touch_ok = touch_cur == DB_AARCH64_NOP
            || db_patch_code_words(touch, touch_expected, touch_replace, 1, "initTouch");

    /*
     * SDL's Android mouse driver calls back into SDLActivity for custom/system
     * pointer icons and Android pointer capture. Those outbound ART JNI calls
     * are redundant in DroidBridge: the launcher already owns cursor state,
     * physical-mouse capture and gamepad-cursor routing. On API 29 the calls
     * originate from the embedded OpenJDK render thread and Android_JNI_GetEnv
     * cannot provide a valid ART JNIEnv. Keep SDL's core mouse driver installed,
     * but make only the Java-facing callbacks harmless.
     */
    uint32_t *create_cursor = (uint32_t *) (base + DB_SDL322_CREATE_CURSOR_OFFSET);
    const uint32_t create_expected[] = {
        DB_SDL322_CREATE_CURSOR_WORD0, DB_SDL322_CREATE_CURSOR_WORD1
    };
    const uint32_t create_replace[] = {
        DB_AARCH64_MOV_X0_XZR, DB_AARCH64_RET
    };
    int create_ok = db_patch_code_words(create_cursor, create_expected, create_replace, 2,
                                        "createCustomCursor");

    uint32_t *show_cursor = (uint32_t *) (base + DB_SDL322_SHOW_CURSOR_OFFSET);
    const uint32_t show_expected[] = {
        DB_SDL322_SHOW_CURSOR_WORD0, DB_SDL322_SHOW_CURSOR_WORD1
    };
    const uint32_t show_replace[] = {
        DB_AARCH64_MOV_W0_1, DB_AARCH64_RET
    };
    int show_ok = db_patch_code_words(show_cursor, show_expected, show_replace, 2,
                                      "showCursor");

    uint32_t *relative_mouse = (uint32_t *) (base + DB_SDL322_RELATIVE_MOUSE_OFFSET);
    const uint32_t relative_expected[] = {
        DB_SDL322_RELATIVE_MOUSE_WORD0, DB_SDL322_RELATIVE_MOUSE_WORD1
    };
    const uint32_t relative_replace[] = {
        DB_AARCH64_MOV_W0_1, DB_AARCH64_RET
    };
    int relative_ok = db_patch_code_words(relative_mouse, relative_expected, relative_replace, 2,
                                          "relativeMouse");

    /*
     * Finish removing SDL's Java dependency from Android video bootstrap.
     * DroidBridge owns these Activity/window responsibilities already. The
     * one operation we must preserve is native-window acquisition, which is
     * redirected to the ANativeWindow published by GameActivity rather than
     * querying SDLActivity.getNativeSurface() through ART.
     */
    uint32_t *title = (uint32_t *) (base + DB_SDL322_SET_ACTIVITY_TITLE_OFFSET);
    const uint32_t title_expected[] = {
        DB_SDL322_SET_ACTIVITY_TITLE_W0, DB_SDL322_SET_ACTIVITY_TITLE_W1
    };
    const uint32_t title_replace[] = { DB_AARCH64_MOV_W0_1, DB_AARCH64_RET };
    int title_ok = db_patch_code_words(title, title_expected, title_replace, 2, "setActivityTitle");

    uint32_t *style = (uint32_t *) (base + DB_SDL322_SET_WINDOW_STYLE_OFFSET);
    const uint32_t style_expected[] = {
        DB_SDL322_SET_WINDOW_STYLE_W0, DB_SDL322_SET_WINDOW_STYLE_W1
    };
    const uint32_t void_replace[] = { DB_AARCH64_RET, DB_AARCH64_NOP };
    int style_ok = db_patch_code_words(style, style_expected, void_replace, 2, "setWindowStyle");

    uint32_t *orientation = (uint32_t *) (base + DB_SDL322_SET_ORIENTATION_OFFSET);
    const uint32_t orientation_expected[] = {
        DB_SDL322_SET_ORIENTATION_W0, DB_SDL322_SET_ORIENTATION_W1
    };
    int orientation_ok = db_patch_code_words(orientation, orientation_expected, void_replace, 2,
                                             "setOrientation");

    uint32_t *minimize = (uint32_t *) (base + DB_SDL322_MINIMIZE_WINDOW_OFFSET);
    const uint32_t minimize_expected[] = {
        DB_SDL322_MINIMIZE_WINDOW_W0, DB_SDL322_MINIMIZE_WINDOW_W1
    };
    int minimize_ok = db_patch_code_words(minimize, minimize_expected, void_replace, 2,
                                          "minimizeWindow");

    uint32_t *should_minimize = (uint32_t *) (base + DB_SDL322_SHOULD_MINIMIZE_OFFSET);
    const uint32_t should_minimize_expected[] = {
        DB_SDL322_SHOULD_MINIMIZE_W0, DB_SDL322_SHOULD_MINIMIZE_W1
    };
    const uint32_t false_replace[] = { DB_AARCH64_MOV_W0_0, DB_AARCH64_RET };
    int should_minimize_ok = db_patch_code_words(should_minimize, should_minimize_expected,
                                                 false_replace, 2, "shouldMinimizeOnFocusLoss");

    uint32_t *screensaver = (uint32_t *) (base + DB_SDL322_SUSPEND_SCREENSAVER_OFFSET);
    const uint32_t screensaver_expected[] = {
        DB_SDL322_SUSPEND_SCREENSAVER_W0, DB_SDL322_SUSPEND_SCREENSAVER_W1
    };
    int screensaver_ok = db_patch_code_words(screensaver, screensaver_expected, title_replace, 2,
                                             "suspendScreenSaver");

    uint32_t *chromebook = (uint32_t *) (base + DB_SDL322_IS_CHROMEBOOK_OFFSET);
    const uint32_t chromebook_expected[] = {
        DB_SDL322_IS_CHROMEBOOK_W0, DB_SDL322_IS_CHROMEBOOK_W1
    };
    int chromebook_ok = db_patch_code_words(chromebook, chromebook_expected, false_replace, 2,
                                            "isChromebook");

    uint32_t *dex = (uint32_t *) (base + DB_SDL322_IS_DEX_OFFSET);
    const uint32_t dex_expected[] = { DB_SDL322_IS_DEX_W0, DB_SDL322_IS_DEX_W1 };
    int dex_ok = db_patch_code_words(dex, dex_expected, false_replace, 2, "isDeXMode");

    uint32_t *native_window = (uint32_t *) (base + DB_SDL322_GET_NATIVE_WINDOW_OFFSET);
    const uint32_t native_window_expected[] = {
        DB_SDL322_GET_NATIVE_WINDOW_W0, DB_SDL322_GET_NATIVE_WINDOW_W1,
        DB_SDL322_GET_NATIVE_WINDOW_W2, DB_SDL322_GET_NATIVE_WINDOW_W3
    };
    int native_window_ok = db_patch_absolute_jump(native_window, native_window_expected,
                                                  (void *) db_android10_published_native_window,
                                                  "getNativeWindow");

    int ok = touch_ok && create_ok && show_ok && relative_ok
            && title_ok && style_ok && orientation_ok && minimize_ok
            && should_minimize_ok && screensaver_ok && chromebook_ok && dex_ok
            && native_window_ok;
    fprintf(stderr,
            "DroidBridgeSDL3GL: Android 10 SDL 3.2.22 video-JNI isolation %s image=%s base=%p SDL_Init=%p touch=%d cursor=%d/%d relative=%d title=%d style=%d orientation=%d minimize=%d focusLoss=%d screensaver=%d chromebook=%d dex=%d nativeWindow=%d\n",
            ok ? "applied" : "incomplete",
            info.dli_fname, info.dli_fbase, sdl_init,
            touch_ok, create_ok, show_ok, relative_ok, title_ok, style_ok, orientation_ok,
            minimize_ok, should_minimize_ok, screensaver_ok, chromebook_ok, dex_ok,
            native_window_ok);
    return ok;
#endif
}

JNIEXPORT jboolean JNICALL
Java_org_lwjgl_sdl_SDLInit_droidBridgePatchAndroid10SdlTouch(
        JNIEnv *env, jclass clazz, jlong original_function) {
    (void) env;
    (void) clazz;
    return patch_exact_sdl322_touch_call((void *) (uintptr_t) original_function)
            ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_lwjgl_sdl_SDLInit_droidBridgeSDLInitOnArt(
        JNIEnv *env, jclass clazz, jint flags, jlong original_function) {
    (void) env;
    (void) clazz;
    if (!art_sdl_dispatch_enabled()) return JNI_FALSE;
    db_art_sdl_dispatch_table *table = resolve_art_sdl_dispatch_table();
    if (table == NULL || table->init == NULL) return JNI_FALSE;
    fprintf(stderr, "DroidBridgeSDL3GL: ART SDL dispatch submit SDL_Init flags=0x%x function=%p\n",
            (unsigned int) flags, (void *) (uintptr_t) original_function);
    int result = table->init((uint32_t) flags,
                             (void *) (uintptr_t) original_function);
    fprintf(stderr, "DroidBridgeSDL3GL: ART SDL dispatch return SDL_Init result=%d\n", result);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_lwjgl_sdl_SDLInit_droidBridgeSDLInitSubSystemOnArt(
        JNIEnv *env, jclass clazz, jint flags, jlong original_function) {
    (void) env;
    (void) clazz;
    if (!art_sdl_dispatch_enabled()) return JNI_FALSE;
    db_art_sdl_dispatch_table *table = resolve_art_sdl_dispatch_table();
    if (table == NULL || table->init_subsystem == NULL) return JNI_FALSE;
    return table->init_subsystem((uint32_t) flags,
                                 (void *) (uintptr_t) original_function)
            ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_sdl_SDLInit_droidBridgeSDLQuitSubSystemOnArt(
        JNIEnv *env, jclass clazz, jint flags, jlong original_function) {
    (void) env;
    (void) clazz;
    if (!art_sdl_dispatch_enabled()) return;
    db_art_sdl_dispatch_table *table = resolve_art_sdl_dispatch_table();
    if (table != NULL && table->quit_subsystem != NULL) {
        table->quit_subsystem((uint32_t) flags,
                              (void *) (uintptr_t) original_function);
    }
}

JNIEXPORT void JNICALL
Java_org_lwjgl_sdl_SDLInit_droidBridgeSDLQuitOnArt(
        JNIEnv *env, jclass clazz, jlong original_function) {
    (void) env;
    (void) clazz;
    if (!art_sdl_dispatch_enabled()) return;
    db_art_sdl_dispatch_table *table = resolve_art_sdl_dispatch_table();
    if (table != NULL && table->quit != NULL) {
        table->quit((void *) (uintptr_t) original_function);
    }
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_sdl_SDLVideo_droidBridgeSDLCreateWindowOnArt(
        JNIEnv *env,
        jclass clazz,
        jlong title_ptr,
        jint width,
        jint height,
        jlong flags,
        jlong original_function) {
    (void) env;
    (void) clazz;
    if (!art_sdl_dispatch_enabled()) return 0;
    db_art_sdl_dispatch_table *table = resolve_art_sdl_dispatch_table();
    if (table == NULL || table->create_window == NULL) return 0;
    void *window = table->create_window(
            (const char *) (uintptr_t) title_ptr,
            (int) width,
            (int) height,
            (uint64_t) flags,
            (void *) (uintptr_t) original_function);
    return (jlong) (uintptr_t) window;
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_sdl_SDLVideo_droidBridgeSDLCreateWindowWithPropertiesOnArt(
        JNIEnv *env,
        jclass clazz,
        jint properties,
        jlong original_function) {
    (void) env;
    (void) clazz;
    if (!art_sdl_dispatch_enabled()) return 0;
    db_art_sdl_dispatch_table *table = resolve_art_sdl_dispatch_table();
    if (table == NULL || table->create_window_with_properties == NULL) return 0;
    void *window = table->create_window_with_properties(
            (uint32_t) properties,
            (void *) (uintptr_t) original_function);
    return (jlong) (uintptr_t) window;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_sdl_SDLVideo_droidBridgeSDLDestroyWindowOnArt(
        JNIEnv *env,
        jclass clazz,
        jlong window,
        jlong original_function) {
    (void) env;
    (void) clazz;
    if (!art_sdl_dispatch_enabled()) return;
    db_art_sdl_dispatch_table *table = resolve_art_sdl_dispatch_table();
    if (table != NULL && table->destroy_window != NULL) {
        table->destroy_window((void *) (uintptr_t) window,
                              (void *) (uintptr_t) original_function);
    }
}

static int env_flag_enabled(const char *name) {
    const char *value = getenv(name);
    if (value == NULL || value[0] == '\0') return 0;
    if (strcmp(value, "0") == 0
            || strcasecmp(value, "false") == 0
            || strcasecmp(value, "off") == 0
            || strcasecmp(value, "no") == 0) {
        return 0;
    }
    return 1;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_system_DroidBridgeFrameCounter_nativeNotifyVulkanPresentation(
        JNIEnv *env,
        jclass clazz) {
    (void) env;
    (void) clazz;
    db_runtime_void_fn notify = (db_runtime_void_fn) resolve_runtime_state_symbol(
            "droidbridge_openjdk_notify_vulkan_presentation");
    if (notify != NULL) notify();
}

JNIEXPORT jboolean JNICALL
Java_org_lwjgl_system_DroidBridgeFrameCounter_nativeIsLogicalVsyncEnabled(
        JNIEnv *env,
        jclass clazz) {
    (void) env;
    (void) clazz;
    db_runtime_int_fn query = (db_runtime_int_fn) resolve_runtime_state_symbol(
            "droidbridge_openjdk_is_logical_vsync_enabled");
    int enabled = query != NULL
            ? query()
            : (env_flag_enabled("DROIDBRIDGE_VSYNC_FRAME_PACING")
                || env_flag_enabled("DROIDBRIDGE_VULKAN_FORCE_FIFO"));
    return enabled ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_system_DroidBridgeFrameCounter_nativeGetVsyncTargetFps(
        JNIEnv *env,
        jclass clazz) {
    (void) env;
    (void) clazz;
    db_runtime_int_fn query = (db_runtime_int_fn) resolve_runtime_state_symbol(
            "droidbridge_openjdk_get_vsync_target_fps");
    int fps = query != NULL ? query() : 0;
    if (fps < 30 || fps > 360) {
        const char *value = getenv("DROIDBRIDGE_VSYNC_TARGET_FPS");
        fps = value != NULL ? atoi(value) : 60;
    }
    if (fps < 30 || fps > 360) fps = 60;
    return (jint) fps;
}

JNIEXPORT jboolean JNICALL
Java_org_lwjgl_system_DroidBridgeFrameCounter_nativeIsPresentationPaused(
        JNIEnv *env,
        jclass clazz) {
    (void) env;
    (void) clazz;
    db_runtime_int_fn query = (db_runtime_int_fn) resolve_runtime_state_symbol(
            "droidbridge_openjdk_is_presentation_paused");
    return query != NULL && query() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_system_DroidBridgeFrameCounter_nativeGetPresentationGeneration(
        JNIEnv *env,
        jclass clazz) {
    (void) env;
    (void) clazz;
    db_runtime_int_fn query = (db_runtime_int_fn) resolve_runtime_state_symbol(
            "droidbridge_openjdk_get_presentation_generation");
    return query != NULL ? (jint) query() : 0;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    if (vm == NULL) return JNI_ERR;

    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK || env == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "OpenJDK JNIEnv unavailable");
        return JNI_ERR;
    }

    /*
     * Do not RegisterNatives on LWJGL classes here. Java 25 can crash in
     * libjvm when DynamicLinkLoader/GL classes are rebound during early class
     * initialization. This uniquely named library is used only for the direct
     * per-frame SDL presentation JNI method above.
     */
    __android_log_print(ANDROID_LOG_INFO, TAG, "OpenJDK direct presentation/state shim v19 loaded");
    fprintf(stderr, "DroidBridgeSDL3GL: OpenJDK direct presentation/state shim v19 native loaded\n");
    return JNI_VERSION_1_6;
}

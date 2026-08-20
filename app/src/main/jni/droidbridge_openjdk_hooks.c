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

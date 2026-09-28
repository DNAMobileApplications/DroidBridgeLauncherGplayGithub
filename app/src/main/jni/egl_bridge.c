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

#include <jni.h>
#include <assert.h>
#include <dlfcn.h>

#include <stdbool.h>
#include <stdint.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>
#include <unistd.h>
#include <pthread.h>
#include <errno.h>

#include <EGL/egl.h>
#include <GL/osmesa.h>
#include "ctxbridges/egl_loader.h"
#include "ctxbridges/osmesa_loader.h"
#include "ctxbridges/renderer_config.h"
#include "ctxbridges/virgl_bridge.h"
#include "driver_helper/nsbypass.h"

#ifdef GLES_TEST
#include <GLES2/gl2.h>
#endif

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/rect.h>
#include <string.h>
#include <environ/environ.h>
#include <android/dlext.h>
#include <time.h>
#include "utils.h"
#include "ctxbridges/bridge_tbl.h"
#include "ctxbridges/osm_bridge.h"

#define GLFW_CLIENT_API 0x22001
/* Consider GLFW_NO_API as Vulkan API */
#define GLFW_NO_API 0
#define GLFW_OPENGL_API 0x30001

// This means that the function is an external API and that it will be used
#define EXTERNAL_API __attribute__((used))
// This means that you are forced to have this function/variable for ABI compatibility
#define ABI_COMPAT __attribute__((unused))

static bool env_enabled(const char* value);
typedef const char* (*db_native_glfw_abi_version_fn)(void);
typedef int (*db_native_glfw_has_required_exports_fn)(void);
typedef void (*db_native_glfw_configure_global_fn)(const char*, const char*, int);
typedef void (*db_native_glfw_surface_window_fn)(ANativeWindow*, int, int);
typedef void (*db_native_glfw_resize_fn)(int, int);
typedef void (*db_native_glfw_void_fn)(void);
typedef void* (*db_native_glfw_create_context_fn)(void*);
typedef void (*db_native_glfw_make_current_fn)(void*);
typedef void (*db_native_glfw_swap_buffers_fn)(void);
typedef EGLDisplay (*db_egl_get_current_display_fn)(void);
typedef EGLContext (*db_egl_get_current_context_fn)(void);
typedef EGLBoolean (*db_egl_swap_interval_fn)(EGLDisplay, EGLint);
typedef EGLBoolean (*db_egl_make_current_fn)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
typedef EGLint (*db_egl_get_error_fn)(void);

static void* db_native_glfw_handle = NULL;
static int db_native_glfw_surface_attached = 0;
/*
 * Set only after droidbridgeCreateContext() has successfully rebound the
 * NativeGLFW EGL context on Minecraft's game/render thread. 26.x may resolve
 * libGLDroidBridge.so after this point; releasing the context from the linker
 * hook then leaves LWJGL GL.createCapabilities() with no current context.
 * Keep the old handoff behavior only for the true pre-context preload path.
 */
static int db_native_glfw_game_context_ready = 0;
/* Snapshot 4+ SDL3 pre-context ownership. This extra ANativeWindow reference and
 * the NativeGLFW EGLWindowSurface exist only while LWJGL resolves Mesa symbols.
 * They must be destroyed before SDL3 calls eglCreateWindowSurface on the same
 * Android window, otherwise Mesa correctly returns EGL_BAD_ALLOC. */
static ANativeWindow* db_native_glfw_sdl3_precontext_window = NULL;
static int db_native_glfw_sdl3_precontext_window_owned = 0;
static uint64_t db_native_glfw_swap_counter = 0;

/*
 * NativeGLFW owns the Mesa EGLDisplay/EGLContext. The generic GLBridge table
 * therefore cannot apply eglSwapInterval for this route. Resolve the EGL
 * functions from the exact provider NativeGLFW uses and apply the interval on
 * the render thread while that context is current.
 */
static void* db_native_glfw_egl_handle = NULL;
static db_egl_get_current_display_fn db_native_glfw_egl_get_current_display = NULL;
static db_egl_get_current_context_fn db_native_glfw_egl_get_current_context = NULL;
static db_egl_swap_interval_fn db_native_glfw_egl_swap_interval = NULL;
static db_egl_make_current_fn db_native_glfw_egl_make_current = NULL;
static db_egl_get_error_fn db_native_glfw_egl_get_error = NULL;
static volatile int db_native_glfw_requested_swap_interval = -1;
static volatile int db_native_glfw_swap_interval_dirty = 0;
static int db_native_glfw_egl_symbols_logged = 0;
static int db_native_glfw_swap_deferred_logged = 0;
static void db_native_glfw_reapply_swap_interval_if_needed(const char* reason);
static void* db_fast_exit_watchdog_thread(void* unused) {
    (void) unused;
    sleep(4);
    printf("EGLBridge: fast exit watchdog exiting cleanly before Minecraft shutdown watchdog\n");
    fflush(stdout);
    _exit(0);
    return NULL;
}

static void db_arm_fast_exit_watchdog(void) {
    pthread_t thread;
    int rc = pthread_create(&thread, NULL, db_fast_exit_watchdog_thread, NULL);
    if (rc == 0) {
        pthread_detach(thread);
        printf("EGLBridge: fast exit watchdog armed\n");
        fflush(stdout);
    } else {
        printf("EGLBridge: fast exit watchdog failed to arm rc=%d\n", rc);
        fflush(stdout);
    }
}


static int db_native_glfw_enabled(void) {
    const char* value = getenv("DROIDBRIDGE_NATIVE_GLFW_KGSL");
    return env_enabled(value);
}

/*
 * Minecraft 26.x preloads OpenGL on a bootstrap thread before its render thread
 * starts. That route must release the context after resolving the GL library so
 * the render thread can claim it. Legacy LWJGLX/Cleanroom/BTA and the established
 * LWJGL 3 paths initialize capabilities immediately on the same thread, so they
 * must keep the context current.
 *
 * The launcher enables this only for 26.x+ profiles through:
 *   DROIDBRIDGE_NATIVE_GLFW_CONTEXT_HANDOFF=1
 *
 * Defaulting to disabled preserves the known-working behavior for every older
 * Minecraft/LWJGL profile.
 */
static int db_native_glfw_linkerhook_handoff_enabled(void) {
    return env_enabled(getenv("DROIDBRIDGE_NATIVE_GLFW_CONTEXT_HANDOFF"));
}

static void db_native_glfw_log_exports(void* handle) {
    if (handle == NULL) return;
    db_native_glfw_abi_version_fn version =
            (db_native_glfw_abi_version_fn) dlsym(handle, "droidbridge_native_glfw_abi_version");
    db_native_glfw_has_required_exports_fn has_exports =
            (db_native_glfw_has_required_exports_fn) dlsym(handle, "droidbridge_native_glfw_has_required_exports");
    printf("DroidBridgeNativeGLFW: loaded ABI=%s requiredExports=%d\n",
           version != NULL ? version() : "<missing-version-symbol>",
           has_exports != NULL ? has_exports() : 0);
}

static void* db_native_glfw_open(void) {
    if (db_native_glfw_handle != NULL) return db_native_glfw_handle;

    const char* explicit_path = getenv("DROIDBRIDGE_NATIVE_GLFW_LIB");
    if (explicit_path != NULL && explicit_path[0] != '\0') {
        dlerror();
        db_native_glfw_handle = dlopen(explicit_path, RTLD_NOW | RTLD_GLOBAL);
        if (db_native_glfw_handle != NULL) {
            printf("DroidBridgeNativeGLFW: dlopen explicit %s handle=%p\n", explicit_path, db_native_glfw_handle);
            db_native_glfw_log_exports(db_native_glfw_handle);
            return db_native_glfw_handle;
        }
        const char* error = dlerror();
        printf("DroidBridgeNativeGLFW: explicit dlopen failed %s error=%s\n", explicit_path, error ? error : "unknown");
    }

    dlerror();
    db_native_glfw_handle = dlopen("libdroidbridge_native_glfw_v82.so", RTLD_NOW | RTLD_GLOBAL);
    if (db_native_glfw_handle != NULL) {
        printf("DroidBridgeNativeGLFW: dlopen soname handle=%p\n", db_native_glfw_handle);
        db_native_glfw_log_exports(db_native_glfw_handle);
        return db_native_glfw_handle;
    }

    const char* error = dlerror();
    printf("DroidBridgeNativeGLFW: dlopen libdroidbridge_native_glfw_v82.so failed: %s\n", error ? error : "unknown");
    return NULL;
}

static int db_native_glfw_resolve_egl_swap_symbols(void) {
    if (db_native_glfw_egl_swap_interval != NULL &&
        db_native_glfw_egl_make_current != NULL &&
        db_native_glfw_egl_get_current_display != NULL &&
        db_native_glfw_egl_get_current_context != NULL) {
        return 1;
    }

    const char* egl_path = getenv("DROIDBRIDGE_NATIVE_GLFW_EGL");
    if (egl_path == NULL || egl_path[0] == '\0') egl_path = getenv("DROIDBRIDGE_MESA_EGL");
    if (egl_path == NULL || egl_path[0] == '\0') egl_path = getenv("DROIDBRIDGE_EGL");
    if (egl_path == NULL || egl_path[0] == '\0') egl_path = "libEGL_mesa.so";

    if (db_native_glfw_egl_handle == NULL) {
        dlerror();
        db_native_glfw_egl_handle = dlopen(egl_path, RTLD_NOW | RTLD_LOCAL);
        if (db_native_glfw_egl_handle == NULL) {
            const char* error = dlerror();
            if (!db_native_glfw_egl_symbols_logged) {
                printf("DroidBridgeNativeGLFW: VSync provider dlopen failed egl=%s error=%s\n",
                       egl_path, error ? error : "unknown");
                fflush(stdout);
                db_native_glfw_egl_symbols_logged = 1;
            }
            return 0;
        }
    }

    dlerror();
    db_native_glfw_egl_get_current_display =
            (db_egl_get_current_display_fn) dlsym(db_native_glfw_egl_handle, "eglGetCurrentDisplay");
    db_native_glfw_egl_get_current_context =
            (db_egl_get_current_context_fn) dlsym(db_native_glfw_egl_handle, "eglGetCurrentContext");
    db_native_glfw_egl_swap_interval =
            (db_egl_swap_interval_fn) dlsym(db_native_glfw_egl_handle, "eglSwapInterval");
    db_native_glfw_egl_make_current =
            (db_egl_make_current_fn) dlsym(db_native_glfw_egl_handle, "eglMakeCurrent");
    db_native_glfw_egl_get_error =
            (db_egl_get_error_fn) dlsym(db_native_glfw_egl_handle, "eglGetError");

    if (db_native_glfw_egl_get_current_display == NULL ||
        db_native_glfw_egl_get_current_context == NULL ||
        db_native_glfw_egl_swap_interval == NULL ||
        db_native_glfw_egl_make_current == NULL) {
        const char* error = dlerror();
        if (!db_native_glfw_egl_symbols_logged) {
            printf("DroidBridgeNativeGLFW: VSync provider missing EGL symbols egl=%s error=%s\n",
                   egl_path, error ? error : "unknown");
            fflush(stdout);
            db_native_glfw_egl_symbols_logged = 1;
        }
        return 0;
    }

    if (!db_native_glfw_egl_symbols_logged) {
        printf("DroidBridgeNativeGLFW: VSync provider ready egl=%s handle=%p\n",
               egl_path, db_native_glfw_egl_handle);
        fflush(stdout);
        db_native_glfw_egl_symbols_logged = 1;
    }
    return 1;
}

static int db_native_glfw_current_context_state(const char* reason) {
    if (!db_native_glfw_resolve_egl_swap_symbols()) {
        printf("DroidBridgeNativeGLFW: context validation unavailable reason=%s\n",
               reason ? reason : "unknown");
        fflush(stdout);
        return -1;
    }

    EGLDisplay display = db_native_glfw_egl_get_current_display();
    EGLContext context = db_native_glfw_egl_get_current_context();
    int current = display != EGL_NO_DISPLAY && context != EGL_NO_CONTEXT;
    if (!current) {
        EGLint error = db_native_glfw_egl_get_error != NULL
                ? db_native_glfw_egl_get_error()
                : EGL_SUCCESS;
        printf("DroidBridgeNativeGLFW: no current EGL context reason=%s display=%p context=%p err=0x%04x\n",
               reason ? reason : "unknown",
               (void*) display,
               (void*) context,
               error);
        fflush(stdout);
    }
    return current;
}

static int db_native_glfw_release_current_context(const char* reason) {
    if (!db_native_glfw_resolve_egl_swap_symbols()) return 0;

    EGLDisplay display = db_native_glfw_egl_get_current_display();
    EGLContext context = db_native_glfw_egl_get_current_context();
    if (display == EGL_NO_DISPLAY || context == EGL_NO_CONTEXT) return 1;

    if (db_native_glfw_egl_get_error != NULL) {
        (void) db_native_glfw_egl_get_error();
    }
    EGLBoolean ok = db_native_glfw_egl_make_current(
            display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    EGLint error = db_native_glfw_egl_get_error != NULL
            ? db_native_glfw_egl_get_error()
            : EGL_SUCCESS;
    printf("DroidBridgeNativeGLFW: released preload-thread EGL context reason=%s ok=%d err=0x%04x\n",
           reason ? reason : "unknown", ok == EGL_TRUE ? 1 : 0, error);
    fflush(stdout);
    return ok == EGL_TRUE ? 1 : 0;
}

static int db_native_glfw_make_current_checked(
        void* handle,
        void* window,
        const char* reason
) {
    if (handle == NULL) return 0;

    db_native_glfw_make_current_fn make_current =
            (db_native_glfw_make_current_fn) dlsym(handle, "droidbridge_native_glfw_make_current");
    if (make_current == NULL) {
        const char* error = dlerror();
        printf("DroidBridgeNativeGLFW: missing droidbridge_native_glfw_make_current error=%s\n",
               error ? error : "unknown");
        fflush(stdout);
        return 0;
    }

    make_current(window);
    int current = db_native_glfw_current_context_state(reason);
    if (current == 1) {
        printf("DroidBridgeNativeGLFW: %s made nativeglfw context current\n",
               reason ? reason : "unknown");
        fflush(stdout);
        db_native_glfw_reapply_swap_interval_if_needed(
                reason ? reason : "makeCurrent");
        return 1;
    }

    if (current < 0) {
        // Validation is unavailable on this provider. Preserve the prior behavior
        // rather than breaking renderers that do not export EGL query symbols.
        printf("DroidBridgeNativeGLFW: %s makeCurrent validation unavailable; preserving compatibility\n",
               reason ? reason : "unknown");
        fflush(stdout);
        return 1;
    }

    printf("DroidBridgeNativeGLFW: %s failed to make nativeglfw context current\n",
           reason ? reason : "unknown");
    fflush(stdout);
    return 0;
}

static int db_native_glfw_apply_swap_interval(int interval, const char* reason) {
    int normalized = interval > 0 ? 1 : 0;
    __atomic_store_n(&db_native_glfw_requested_swap_interval, normalized, __ATOMIC_RELAXED);
    __atomic_store_n(&db_native_glfw_swap_interval_dirty, 1, __ATOMIC_RELAXED);

    if (!db_native_glfw_enabled() || !db_native_glfw_resolve_egl_swap_symbols()) {
        return 0;
    }

    EGLDisplay display = db_native_glfw_egl_get_current_display();
    EGLContext context = db_native_glfw_egl_get_current_context();
    if (display == EGL_NO_DISPLAY || context == EGL_NO_CONTEXT) {
        if (!db_native_glfw_swap_deferred_logged) {
            printf("DroidBridgeNativeGLFW: eglSwapInterval(%d) deferred reason=%s display=%p context=%p\n",
                   normalized, reason ? reason : "unknown", (void*) display, (void*) context);
            fflush(stdout);
            db_native_glfw_swap_deferred_logged = 1;
        }
        return 0;
    }

    if (db_native_glfw_egl_get_error != NULL) {
        (void) db_native_glfw_egl_get_error();
    }
    EGLBoolean ok = db_native_glfw_egl_swap_interval(display, normalized);
    EGLint error = db_native_glfw_egl_get_error != NULL ? db_native_glfw_egl_get_error() : EGL_SUCCESS;
    if (ok == EGL_TRUE) {
        __atomic_store_n(&db_native_glfw_swap_interval_dirty, 0, __ATOMIC_RELAXED);
        db_native_glfw_swap_deferred_logged = 0;
    }

    printf("DroidBridgeNativeGLFW: eglSwapInterval(%d) reason=%s ok=%d err=0x%04x display=%p context=%p\n",
           normalized,
           reason ? reason : "unknown",
           ok == EGL_TRUE ? 1 : 0,
           error,
           (void*) display,
           (void*) context);
    fflush(stdout);
    return ok == EGL_TRUE ? 1 : 0;
}

static void db_native_glfw_reapply_swap_interval_if_needed(const char* reason) {
    int requested = __atomic_load_n(&db_native_glfw_requested_swap_interval, __ATOMIC_RELAXED);
    int dirty = __atomic_load_n(&db_native_glfw_swap_interval_dirty, __ATOMIC_RELAXED);
    if (requested >= 0 && dirty) {
        db_native_glfw_apply_swap_interval(requested, reason);
    }
}

static void db_native_glfw_configure_if_available(void* handle) {
    if (handle == NULL) return;
    db_native_glfw_configure_global_fn configure =
            (db_native_glfw_configure_global_fn) dlsym(handle, "droidbridge_native_glfw_configure_global");
    if (configure == NULL) {
        const char* error = dlerror();
        printf("DroidBridgeNativeGLFW: missing droidbridge_native_glfw_configure_global error=%s\n", error ? error : "unknown");
        return;
    }

    const char* egl = getenv("DROIDBRIDGE_NATIVE_GLFW_EGL");
    if (egl == NULL || egl[0] == '\0') egl = getenv("DROIDBRIDGE_MESA_EGL");
    if (egl == NULL || egl[0] == '\0') egl = getenv("DROIDBRIDGE_EGL");
    if (egl == NULL || egl[0] == '\0') egl = "libEGL_mesa.so";

    const char* driver = getenv("DROIDBRIDGE_NATIVE_GLFW_DRIVER");
    if (driver == NULL || driver[0] == '\0') driver = getenv("DROIDBRIDGE_MESA_DRIVER");
    if (driver == NULL || driver[0] == '\0') driver = "kgsl";

    configure(egl, driver, 1);
    printf("DroidBridgeNativeGLFW: configured egl=%s driver=%s desktopGl=1\n", egl, driver);
}

/*
 * Minecraft 26.3 Snapshot 4+ publishes its Android Surface through SDL3 instead
 * of calling the legacy JREUtils.setupBridgeWindow() path. The SDL bridge stores
 * the acquired ANativeWindow pointer in this process-wide environment variable.
 *
 * LWJGL resolves desktop OpenGL entry points before SDL3 creates its real render
 * context. Adopt the already-published window here so NativeGLFW can create a
 * short-lived Mesa context for function resolution, matching the working
 * Snapshot 3 startup order. The linker-hook handoff releases that context before
 * SDL3 creates and owns the actual game context.
 */
static ANativeWindow* db_native_glfw_adopt_sdl3_published_window(const char* reason) {
    if (droidbridge_environ == NULL) return NULL;

    const char* pointer_text = getenv("DROIDBRIDGE_SDL3_ANATIVEWINDOW_PTR");
    if (pointer_text == NULL || pointer_text[0] == '\0') return droidbridge_environ->droidbridgeWindow;

    errno = 0;
    char* end = NULL;
    uintptr_t pointer_value = (uintptr_t) strtoull(pointer_text, &end, 16);
    if (errno != 0 || end == pointer_text || (end != NULL && *end != '\0') || pointer_value == 0) {
        printf("DroidBridgeNativeGLFW: invalid SDL3 published window token reason=%s value=%s\n",
               reason ? reason : "unknown", pointer_text);
        fflush(stdout);
        return droidbridge_environ->droidbridgeWindow;
    }

    ANativeWindow* published = (ANativeWindow*) pointer_value;
    if (droidbridge_environ->droidbridgeWindow == published) return published;

    ANativeWindow_acquire(published);
    db_native_glfw_sdl3_precontext_window = published;
    db_native_glfw_sdl3_precontext_window_owned = 1;
    ANativeWindow* old = droidbridge_environ->droidbridgeWindow;
    droidbridge_environ->droidbridgeWindow = published;
    droidbridge_environ->savedWidth = ANativeWindow_getWidth(published);
    droidbridge_environ->savedHeight = ANativeWindow_getHeight(published);
    if (droidbridge_environ->savedWidth <= 0) droidbridge_environ->savedWidth = 1;
    if (droidbridge_environ->savedHeight <= 0) droidbridge_environ->savedHeight = 1;
    db_native_glfw_surface_attached = 0;

    if (old != NULL) ANativeWindow_release(old);

    printf("DroidBridgeNativeGLFW: adopted SDL3 published window reason=%s window=%p size=%dx%d\n",
           reason ? reason : "unknown",
           (void*) published,
           droidbridge_environ->savedWidth,
           droidbridge_environ->savedHeight);
    fflush(stdout);
    return published;
}

static void db_native_glfw_destroy_sdl3_precontext_surface(void* handle, const char* reason) {
    if (!db_native_glfw_sdl3_precontext_window_owned ||
        db_native_glfw_sdl3_precontext_window == NULL) {
        return;
    }

    /* NativeGLFW owns the temporary EGLWindowSurface. Releasing only the
     * current context is insufficient: EGL permits one active window surface
     * per native window, so SDL3's later eglCreateWindowSurface would fail with
     * EGL_BAD_ALLOC until this surface is actually destroyed. */
    if (handle != NULL && db_native_glfw_surface_attached) {
        dlerror();
        db_native_glfw_void_fn surface_destroyed =
                (db_native_glfw_void_fn) dlsym(handle,
                        "droidbridge_native_glfw_surface_destroyed");
        if (surface_destroyed != NULL) {
            surface_destroyed();
            printf("DroidBridgeNativeGLFW: destroyed temporary SDL3 preload EGL window surface reason=%s\n",
                   reason ? reason : "unknown");
        } else {
            const char* error = dlerror();
            printf("DroidBridgeNativeGLFW: missing temporary surface teardown export reason=%s error=%s\n",
                   reason ? reason : "unknown", error ? error : "unknown");
        }
        fflush(stdout);
    }
    db_native_glfw_surface_attached = 0;

    /* Drop only the reference acquired by db_native_glfw_adopt_sdl3_published_window().
     * SDL3 keeps its own published-window reference and remains the sole owner
     * of the real game EGL surface/context. */
    if (droidbridge_environ != NULL &&
        droidbridge_environ->droidbridgeWindow == db_native_glfw_sdl3_precontext_window) {
        droidbridge_environ->droidbridgeWindow = NULL;
    }
    ANativeWindow_release(db_native_glfw_sdl3_precontext_window);
    printf("DroidBridgeNativeGLFW: released temporary SDL3 preload ANativeWindow reason=%s window=%p\n",
           reason ? reason : "unknown",
           (void*) db_native_glfw_sdl3_precontext_window);
    fflush(stdout);
    db_native_glfw_sdl3_precontext_window = NULL;
    db_native_glfw_sdl3_precontext_window_owned = 0;
}

static void db_native_glfw_attach_existing_window(const char* reason) {
    if (!db_native_glfw_enabled()) return;
    if (db_native_glfw_surface_attached) {
        printf("DroidBridgeNativeGLFW: surface already attached, skip reason=%s\n", reason ? reason : "unknown");
    }

    void* handle = db_native_glfw_open();
    db_native_glfw_configure_if_available(handle);
    if (handle == NULL) return;

    if (droidbridge_environ->droidbridgeWindow == NULL) {
        printf("DroidBridgeNativeGLFW: no ANativeWindow available for reason=%s\n", reason ? reason : "unknown");
        return;
    }

    if (!db_native_glfw_surface_attached) {
        int width = ANativeWindow_getWidth(droidbridge_environ->droidbridgeWindow);
        int height = ANativeWindow_getHeight(droidbridge_environ->droidbridgeWindow);
        if (width <= 0) width = droidbridge_environ->savedWidth > 0 ? droidbridge_environ->savedWidth : 1;
        if (height <= 0) height = droidbridge_environ->savedHeight > 0 ? droidbridge_environ->savedHeight : 1;

        db_native_glfw_surface_window_fn surface_window =
                (db_native_glfw_surface_window_fn) dlsym(handle, "droidbridge_native_glfw_surface_window");
        if (surface_window == NULL) {
            const char* error = dlerror();
            printf("DroidBridgeNativeGLFW: missing droidbridge_native_glfw_surface_window error=%s\n", error ? error : "unknown");
            return;
        }

        surface_window(droidbridge_environ->droidbridgeWindow, width, height);
        db_native_glfw_surface_attached = 1;
        printf("DroidBridgeNativeGLFW: %s handed Surface to nativeglfw size=%dx%d\n",
               reason ? reason : "unknown", width, height);
    }

    (void) db_native_glfw_make_current_checked(
            handle,
            (void*) droidbridge_environ->droidbridgeWindow,
            reason ? reason : "attach/makeCurrent"
    );
}


static int db_native_glfw_align_resize_dimension(int value) {
    const int alignment = 4;
    if (value <= 0) return 1;
    int aligned = (value / alignment) * alignment;
    if (aligned <= 0) aligned = value;
    return aligned > 0 ? aligned : 1;
}

static void db_native_glfw_resize_existing_window(int width, int height, const char* reason) {
    if (!db_native_glfw_enabled()) return;
    if (width <= 0) width = 1;
    if (height <= 0) height = 1;
    int requested_width = width;
    int requested_height = height;
    width = db_native_glfw_align_resize_dimension(width);
    height = db_native_glfw_align_resize_dimension(height);
    if (width != requested_width || height != requested_height) {
        printf("DroidBridgeNativeGLFW: resizeBridgeWindow snapped unsafe size %dx%d -> %dx%d\n",
               requested_width, requested_height, width, height);
        fflush(stdout);
    }

    if (droidbridge_environ == NULL || droidbridge_environ->droidbridgeWindow == NULL) {
        printf("DroidBridgeNativeGLFW: resizeBridgeWindow skipped, no ANativeWindow reason=%s size=%dx%d\n",
               reason ? reason : "unknown", width, height);
        fflush(stdout);
        return;
    }

    ANativeWindow_setBuffersGeometry(
            droidbridge_environ->droidbridgeWindow,
            width,
            height,
            AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM
    );
    droidbridge_environ->savedWidth = width;
    droidbridge_environ->savedHeight = height;

    void* handle = db_native_glfw_open();
    db_native_glfw_configure_if_available(handle);
    if (handle == NULL) return;

    db_native_glfw_resize_fn resize =
            (db_native_glfw_resize_fn) dlsym(handle, "droidbridge_native_glfw_resize");
    if (resize != NULL) {
        resize(width, height);
        __atomic_store_n(&db_native_glfw_swap_interval_dirty,
                         __atomic_load_n(&db_native_glfw_requested_swap_interval, __ATOMIC_RELAXED) >= 0 ? 1 : 0,
                         __ATOMIC_RELAXED);
        db_native_glfw_reapply_swap_interval_if_needed("resize");
        printf("DroidBridgeNativeGLFW: resizeBridgeWindow updated existing NativeGLFW surface reason=%s size=%dx%d\n",
               reason ? reason : "unknown", width, height);
        fflush(stdout);
        return;
    }

    const char* error = dlerror();
    printf("DroidBridgeNativeGLFW: missing droidbridge_native_glfw_resize reason=%s size=%dx%d error=%s\n",
           reason ? reason : "unknown", width, height, error ? error : "unknown");
    fflush(stdout);
}

__attribute__((visibility("default"), used)) void* droidbridge_runtime_native_glfw_acquire_opengl_handle(void) {
    if (!db_native_glfw_enabled()) return NULL;

    /* SDL3 snapshots no longer call setupBridgeWindow(). Recover the published
     * ANativeWindow before opening/resolving Mesa so a context exists while
     * LWJGL caches OpenGL function addresses. */
    (void) db_native_glfw_adopt_sdl3_published_window("linkerhook-acquire");

    void* handle = db_native_glfw_open();
    if (handle == NULL) return NULL;

    if (!db_native_glfw_surface_attached && droidbridge_environ != NULL && droidbridge_environ->droidbridgeWindow != NULL) {
        db_native_glfw_attach_existing_window("linkerhook-acquire");
    } else {
        db_native_glfw_configure_if_available(handle);
    }

    (void) db_native_glfw_make_current_checked(
            handle,
            droidbridge_environ != NULL ? (void*) droidbridge_environ->droidbridgeWindow : NULL,
            "linkerhook-acquire-before"
    );

    typedef void* (*db_native_glfw_acquire_opengl_handle_fn)(void);
    db_native_glfw_acquire_opengl_handle_fn acquire =
            (db_native_glfw_acquire_opengl_handle_fn) dlsym(handle, "droidbridge_native_glfw_acquire_opengl_handle");
    if (acquire == NULL) {
        const char* error = dlerror();
        printf("DroidBridgeNativeGLFW: droidbridge_runtime acquire missing native acquire symbol error=%s\n", error ? error : "unknown");
        return NULL;
    }

    void* egl_handle = acquire();
    printf("DroidBridgeNativeGLFW: droidbridge_runtime acquire_opengl_handle returned %p\n", egl_handle);

    /*
     * 26.x can hit this function in two different phases:
     *   1) an early preload phase before the game/render context exists, where
     *      the temporary context must still be handed off;
     *   2) the render-thread libGLDroidBridge.so resolve after
     *      droidbridgeCreateContext(), where LWJGL calls GL.createCapabilities()
     *      immediately after this function returns.
     *
     * Releasing in phase (2) is the crash seen on Freedreno: the log reports a
     * successful makeCurrent/acquire, then explicitly clears the context, and
     * GL.createCapabilities() fails with "There is no OpenGL context current".
     */
    if (egl_handle != NULL) {
        if (db_native_glfw_linkerhook_handoff_enabled()
                && !db_native_glfw_game_context_ready) {
            (void) db_native_glfw_release_current_context("linkerhook-acquire-after");
            db_native_glfw_destroy_sdl3_precontext_surface(
                    handle, "linkerhook-acquire-after");
        } else {
            printf("DroidBridgeNativeGLFW: keeping linkerhook EGL context current gameContextReady=%d handoffPolicy=%d\n",
                   db_native_glfw_game_context_ready,
                   db_native_glfw_linkerhook_handoff_enabled() ? 1 : 0);
            fflush(stdout);
        }
    } else if (db_native_glfw_linkerhook_handoff_enabled()
            && !db_native_glfw_game_context_ready) {
        /* Do not leak a temporary window surface when provider acquisition
         * fails and the linker hook falls back to another OpenGL route. */
        (void) db_native_glfw_release_current_context("linkerhook-acquire-failed");
        db_native_glfw_destroy_sdl3_precontext_surface(
                handle, "linkerhook-acquire-failed");
    }
    return egl_handle;
}

__attribute__((visibility("default"), used))
int droidbridge_runtime_native_glfw_release_current_context(void) {
    if (!db_native_glfw_linkerhook_handoff_enabled()) {
        printf("DroidBridgeNativeGLFW: external linkerhook context release skipped by compatibility policy\n");
        fflush(stdout);
        return 0;
    }
    if (db_native_glfw_game_context_ready) {
        printf("DroidBridgeNativeGLFW: external linkerhook context release skipped; game/render context is already current\n");
        fflush(stdout);
        return 0;
    }
    return db_native_glfw_release_current_context("external-linkerhook-release");
}


EGLConfig config;
struct PotatoBridge potatoBridge;

void* loadTurnipVulkan(void);
void calculateFPS(void);
static void droidbridgeFramePaceIfNeeded(const char* reason, bool vulkanFrame);
static int64_t droidbridgeMonotonicNs(void);
static void droidbridgeResetGlAdaptivePacing(void);
static void droidbridgeObserveGlSwap(
        int64_t presentEntryNs,
        int64_t swapStartNs,
        int64_t swapEndNs);
static bool env_enabled(const char* value);
void load_vulkan(void);

EXTERNAL_API void droidbridgeTerminate(void) {
    printf("EGLBridge: Terminating\n");
    fflush(stdout);
    db_arm_fast_exit_watchdog();

    if (db_native_glfw_enabled()) {
        void* handle = db_native_glfw_open();
        if (handle != NULL) {
            db_native_glfw_void_fn terminate =
                    (db_native_glfw_void_fn) dlsym(handle, "droidbridge_native_glfw_terminate");
            if (terminate != NULL) terminate();
        }
        return;
    }

    switch (droidbridge_environ->config_renderer) {
        case RENDERER_GL4ES: {
            eglMakeCurrent_p(potatoBridge.eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            eglDestroySurface_p(potatoBridge.eglDisplay, potatoBridge.eglSurface);
            eglDestroyContext_p(potatoBridge.eglDisplay, potatoBridge.eglContext);
            eglTerminate_p(potatoBridge.eglDisplay);
            eglReleaseThread_p();

            potatoBridge.eglContext = EGL_NO_CONTEXT;
            potatoBridge.eglDisplay = EGL_NO_DISPLAY;
            potatoBridge.eglSurface = EGL_NO_SURFACE;
        } break;

        case RENDERER_VK_ZINK: {
            // Nothing to do here.
        } break;
    }
}

JNIEXPORT void JNICALL Java_ca_dnamobile_dbridgelauncher_runtime_utils_JREUtils_setupBridgeWindow(JNIEnv* env,
                                                                                 ABI_COMPAT jclass clazz,
                                                                                 jobject surface) {
    droidbridge_environ->droidbridgeWindow = ANativeWindow_fromSurface(env, surface);

    if (db_native_glfw_enabled()) {
        db_native_glfw_surface_attached = 0;
        db_native_glfw_attach_existing_window("setupBridgeWindow");

        /*
         * setupBridgeWindow() runs on Android's Surface/UI callback thread,
         * before Minecraft's render thread starts. NativeGLFW creates and binds
         * the Mesa EGL context while attaching the ANativeWindow. Leaving that
         * context current here makes the later render-thread eglMakeCurrent()
         * fail with EGL_BAD_ACCESS (0x3002). Release only this thread's current
         * ownership; the context and window surface remain alive and are rebound
         * by droidbridgeInit()/droidbridgeCreateContext() on the render thread.
         */
        (void) db_native_glfw_release_current_context(
                "setupBridgeWindow-after-attach");
        return;
    }

    if (br_setup_window) {
        br_setup_window();
    }
}

JNIEXPORT void JNICALL
Java_ca_dnamobile_dbridgelauncher_runtime_utils_JREUtils_releaseBridgeWindow(ABI_COMPAT JNIEnv* env,
                                                            ABI_COMPAT jclass clazz) {
    if (db_native_glfw_enabled()) {
        void* handle = db_native_glfw_open();
        if (handle != NULL) {
            db_native_glfw_void_fn surface_destroyed =
                    (db_native_glfw_void_fn) dlsym(handle, "droidbridge_native_glfw_surface_destroyed");
            if (surface_destroyed != NULL) surface_destroyed();
        }
        db_native_glfw_surface_attached = 0;
        if (__atomic_load_n(&db_native_glfw_requested_swap_interval, __ATOMIC_RELAXED) >= 0) {
            __atomic_store_n(&db_native_glfw_swap_interval_dirty, 1, __ATOMIC_RELAXED);
        }
    }

    if (droidbridge_environ->droidbridgeWindow != NULL) {
        ANativeWindow_release(droidbridge_environ->droidbridgeWindow);
        droidbridge_environ->droidbridgeWindow = NULL;
    }
}

JNIEXPORT void JNICALL
Java_ca_dnamobile_dbridgelauncher_runtime_utils_JREUtils_resizeBridgeWindow(ABI_COMPAT JNIEnv* env,
                                                           ABI_COMPAT jclass clazz,
                                                           jint width,
                                                           jint height) {
    if (width <= 0) width = 1;
    if (height <= 0) height = 1;
    width = db_native_glfw_align_resize_dimension(width);
    height = db_native_glfw_align_resize_dimension(height);

    if (droidbridge_environ != NULL && droidbridge_environ->droidbridgeWindow != NULL) {
        ANativeWindow_setBuffersGeometry(
                droidbridge_environ->droidbridgeWindow,
                width,
                height,
                AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM
        );
        droidbridge_environ->savedWidth = width;
        droidbridge_environ->savedHeight = height;
    }

    if (db_native_glfw_enabled()) {
        db_native_glfw_resize_existing_window(width, height, "JREUtils.resizeBridgeWindow");

        /* resize() binds the EGL context on the Android callback thread too. */
        (void) db_native_glfw_release_current_context(
                "JREUtils.resizeBridgeWindow-after-resize");
    }
}

// Package-compatibility aliases: the same native library is used by both
// ca.dnamobile.dbridgelauncher (Re-Write) and ca.dnamobile.javalauncher (Original).
// Keep both JNI exports so either package can attach the Android surface.
JNIEXPORT void JNICALL Java_ca_dnamobile_javalauncher_runtime_utils_JREUtils_setupBridgeWindow(JNIEnv* env,
                                                                                 ABI_COMPAT jclass clazz,
                                                                                 jobject surface) {
    Java_ca_dnamobile_dbridgelauncher_runtime_utils_JREUtils_setupBridgeWindow(env, clazz, surface);
}

JNIEXPORT void JNICALL
Java_ca_dnamobile_javalauncher_runtime_utils_JREUtils_releaseBridgeWindow(ABI_COMPAT JNIEnv* env,
                                                            ABI_COMPAT jclass clazz) {
    Java_ca_dnamobile_dbridgelauncher_runtime_utils_JREUtils_releaseBridgeWindow(env, clazz);
}

JNIEXPORT void JNICALL
Java_ca_dnamobile_javalauncher_runtime_utils_JREUtils_resizeBridgeWindow(ABI_COMPAT JNIEnv* env,
                                                           ABI_COMPAT jclass clazz,
                                                           jint width,
                                                           jint height) {
    Java_ca_dnamobile_dbridgelauncher_runtime_utils_JREUtils_resizeBridgeWindow(env, clazz, width, height);
}

/* Final Google Play package aliases. The implementation stays shared with the
 * rewrite/original entry points so every package resolves the same JNI bridge. */
JNIEXPORT void JNICALL
Java_ca_dnamobile_droidbridgelauncher_runtime_utils_JREUtils_setupBridgeWindow(JNIEnv* env,
                                                                              ABI_COMPAT jclass clazz,
                                                                              jobject surface) {
    Java_ca_dnamobile_dbridgelauncher_runtime_utils_JREUtils_setupBridgeWindow(env, clazz, surface);
}

JNIEXPORT void JNICALL
Java_ca_dnamobile_droidbridgelauncher_runtime_utils_JREUtils_releaseBridgeWindow(ABI_COMPAT JNIEnv* env,
                                                                                ABI_COMPAT jclass clazz) {
    Java_ca_dnamobile_dbridgelauncher_runtime_utils_JREUtils_releaseBridgeWindow(env, clazz);
}

JNIEXPORT void JNICALL
Java_ca_dnamobile_droidbridgelauncher_runtime_utils_JREUtils_resizeBridgeWindow(ABI_COMPAT JNIEnv* env,
                                                                               ABI_COMPAT jclass clazz,
                                                                               jint width,
                                                                               jint height) {
    Java_ca_dnamobile_dbridgelauncher_runtime_utils_JREUtils_resizeBridgeWindow(env, clazz, width, height);
}

EXTERNAL_API void* droidbridgeGetCurrentContext(void) {
    /*
     * SDL3/Vulkan Minecraft 26.3 does not create an OpenGL context. Some mods
     * still probe GLFW's current-context entry point during renderer/resource
     * initialization. GLFW semantics for a GLFW_NO_API/Vulkan window are to
     * report no current OpenGL context, not to manufacture one.
     *
     * On the SDL3-only Vulkan route droidbridgeInitOpenGL() may never install a
     * legacy bridge table, leaving br_get_current == NULL. Calling it here used
     * to jump to address 0x0 (seen as droidbridgeGetCurrentContext+0xb0 in the
     * JVM native crash log). Keep this query side-effect free and null-safe.
     */
    if (droidbridge_environ == NULL) {
        return NULL;
    }

    if (droidbridge_environ->config_renderer == RENDERER_VULKAN) {
        return NULL;
    }

    if (db_native_glfw_enabled()) {
        void* handle = db_native_glfw_open();
        if (handle != NULL) {
            db_native_glfw_create_context_fn create_context =
                    (db_native_glfw_create_context_fn) dlsym(handle, "droidbridge_native_glfw_create_context");
            if (create_context != NULL) return create_context(NULL);
        }
        return droidbridge_environ->droidbridgeWindow;
    }

    if (droidbridge_environ->config_renderer == RENDERER_VIRGL) {
        return virglGetCurrentContext();
    }

    if (br_get_current != NULL) {
        return br_get_current();
    }

    /* No legacy GL bridge exists (normal for SDL3/Vulkan). */
    return NULL;
}

extern void* droidbridge_vulkan_compat_select_loader(void* realLoaderHandle);

static void set_vulkan_ptr(void* ptr) {
    if (ptr == NULL) {
        unsetenv("VULKAN_PTR");
        return;
    }

    char envval[64];
    sprintf(envval, "%" PRIxPTR, (uintptr_t) ptr);
    setenv("VULKAN_PTR", envval, 1);
}

static void* try_open_vulkan_loader(void) {
    dlerror();
    void* vulkanPtr = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_LOCAL);
    if (vulkanPtr != NULL) {
        printf("OSMDroid: Loaded Vulkan via libvulkan.so.1, ptr=%p\n", vulkanPtr);
        return vulkanPtr;
    }

    const char* firstError = dlerror();
    printf("OSMDroid: libvulkan.so.1 failed: %s\n", firstError != NULL ? firstError : "unknown error");

    dlerror();
    vulkanPtr = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (vulkanPtr != NULL) {
        printf("OSMDroid: Loaded Vulkan via libvulkan.so, ptr=%p\n", vulkanPtr);
        return vulkanPtr;
    }

    const char* secondError = dlerror();
    printf("OSMDroid: libvulkan.so failed: %s\n", secondError != NULL ? secondError : "unknown error");
    return NULL;
}

void load_vulkan(void) {
    const char* zinkPreferSystemDriver = getenv("DROIDBRIDGE_ZINK_PREFER_SYSTEM_DRIVER");
    const bool explicitSystemVulkan = env_enabled(getenv("DROIDBRIDGE_USE_SYSTEM_VULKAN"));
    const bool preferSystemVulkan = explicitSystemVulkan
            || (zinkPreferSystemDriver != NULL && zinkPreferSystemDriver[0] != '\0'
                && strcmp(zinkPreferSystemDriver, "0") != 0);
    const bool kopperRequireCustom = env_enabled(getenv("DROIDBRIDGE_KOPPER_FORCE_NAMESPACE_VULKAN"))
            && !preferSystemVulkan;
    int deviceApiLevel = android_get_device_api_level();

    if (!preferSystemVulkan && deviceApiLevel >= 28) {
#ifdef ADRENO_POSSIBLE
        /*
         * v9: Do not introduce a new DT_NEEDED ABI from libdroidbridge_runtime
         * to driver_helper just for Kopper. The v8 direct reference to
         * loadTurnipVulkanKopper() made the entire runtime fail dlopen when an
         * incrementally packaged driver_helper did not yet export that symbol.
         *
         * DROIDBRIDGE_KOPPER_FORCE_NAMESPACE_VULKAN is already set before this
         * call, so the long-standing loadTurnipVulkan() ABI selects the Kopper
         * original-SONAME namespace path in the updated helper. Successful
         * helper setup publishes DROIDBRIDGE_KOPPER_TURNIP_READY=1.
         */
        void* result = loadTurnipVulkan();
        if (result != NULL) {
            const bool kopperReady = env_enabled(getenv("DROIDBRIDGE_KOPPER_TURNIP_READY"));
            printf("AdrenoSupp-v80: Loaded Turnip, loader address: %p kopper=%d ready=%d\n",
                   result,
                   kopperRequireCustom ? 1 : 0,
                   kopperReady ? 1 : 0);
            fflush(stdout);
            if (kopperRequireCustom && !kopperReady) {
                printf("AdrenoSupp-v80: Kopper Turnip handle exists but namespace readiness was not published; refusing ambiguous/system Vulkan path.\n");
                fflush(stdout);
                set_vulkan_ptr(NULL);
                return;
            }
            void* selected = droidbridge_vulkan_compat_select_loader(result);
            set_vulkan_ptr(selected);
            if (kopperRequireCustom) {
                printf("AdrenoSupp-v84: Kopper VULKAN_PTR handoff=%s loader=%p strategy=alias-visible-private-loader\n",
                       getenv("VULKAN_PTR") != NULL ? getenv("VULKAN_PTR") : "<unset>",
                       selected);
                fflush(stdout);
            }
            return;
        }
        if (kopperRequireCustom) {
            printf("AdrenoSupp-v75: Kopper requires custom Turnip; refusing system Qualcomm fallback.\n");
            set_vulkan_ptr(NULL);
            return;
        }
#endif
    }

    printf("OSMDroid: Loading Vulkan regularly...\n");
    void* vulkanPtr = try_open_vulkan_loader();
    void* selected = droidbridge_vulkan_compat_select_loader(vulkanPtr);
    set_vulkan_ptr(selected);
}

static bool env_is(const char* value, const char* expected) {
    return value != NULL && expected != NULL && strcmp(value, expected) == 0;
}

static bool env_enabled(const char* value) {
    return value != NULL
            && value[0] != '\0'
            && strcmp(value, "0") != 0
            && strcmp(value, "false") != 0
            && strcmp(value, "FALSE") != 0;
}


static bool is_droidbridge_mesa_zink_turnip(const char* renderer,
                                            const char* mesa_mode,
                                            const char* mesa_driver,
                                            const char* renderer_mesa_mode,
                                            const char* droidbridge_mesa) {
    if (!env_enabled(droidbridge_mesa)) {
        return false;
    }

    return env_is(mesa_mode, "zink_turnip")
            || env_is(renderer_mesa_mode, "zink_turnip")
            || (env_is(renderer, "vulkan_zink") && env_is(mesa_driver, "zink"));
}

static void configure_droidbridge_mesa_zink_turnip_desktop_gl(void) {
    printf("EGLBridge: Using DroidBridge Mesa zink_turnip desktop GL bridge\n");

    setenv("DROIDBRIDGE_MESA", "1", 1);
    setenv("DROIDBRIDGE_MESA_MODE", "zink_turnip", 1);
    setenv("DROIDBRIDGE_MESA_DRIVER", "zink", 1);
    setenv("DROIDBRIDGE_RENDERER_MESA_MODE", "zink_turnip", 1);

    setenv("GALLIUM_DRIVER", "zink", 1);
    setenv("MESA_LOADER_DRIVER_OVERRIDE", "zink", 1);
    setenv("MESA_GL_VERSION_OVERRIDE", "4.6COMPAT", 1);
    setenv("MESA_GLSL_VERSION_OVERRIDE", "460", 1);

    /*
     * Critical: Minecraft 26.x compiles desktop GLSL 330+ shaders.
     * A GLES 3.x context reports only GLSL ES versions and then crashes in Mesa.
     * These flags are consumed by DroidBridge's EGL/GL bridge implementation.
     */
    setenv("DROIDBRIDGE_MESA_DESKTOP_GL", "1", 1);
    setenv("DROIDBRIDGE_EGL_FORCE_DESKTOP_GL", "1", 1);
    setenv("DROIDBRIDGE_EGL_NO_SYSTEM_FALLBACK", "1", 1);
    unsetenv("LIBGL_ES");
}

static int droidbridgeRefreshForceVsyncFromEnvironment(const char* reason) {
    const char* forceVsync = getenv("FORCE_VSYNC");
    const char* mobileGluesForceVsync = getenv("DROIDBRIDGE_MOBILEGLUES_FORCE_VSYNC");
    const int enabled = env_enabled(forceVsync) || env_enabled(mobileGluesForceVsync);

    if (droidbridge_environ == NULL) {
        printf("EGLBridge: VSync clamp refresh skipped; environment is unavailable reason=%s\n",
               reason != NULL ? reason : "unknown");
        fflush(stdout);
        return enabled;
    }

    const int previous = droidbridge_environ->force_vsync ? 1 : 0;
    droidbridge_environ->force_vsync = enabled != 0;

    if (previous != enabled || reason == NULL || strcmp(reason, "glfwSwapInterval") != 0) {
        printf("EGLBridge: VSync clamp refresh reason=%s enabled=%d previous=%d FORCE_VSYNC=%s DROIDBRIDGE_MOBILEGLUES_FORCE_VSYNC=%s\n",
               reason != NULL ? reason : "unknown",
               enabled,
               previous,
               forceVsync != NULL ? forceVsync : "<null>",
               mobileGluesForceVsync != NULL ? mobileGluesForceVsync : "<null>");
        fflush(stdout);
    }
    return enabled;
}

int droidbridgeInitOpenGL(void) {
    droidbridgeRefreshForceVsyncFromEnvironment("droidbridgeInitOpenGL");

    const char* renderer = getenv("DROIDBRIDGE_RENDERER");
    if (renderer == NULL) {
        printf("EGLBridge: DROIDBRIDGE_RENDERER is not set\n");
        return 0;
    }

    /*
     * Some launcher-side rollback paths accidentally pass the numeric renderer
     * sentinel "-1" while the selected instance still needs an OpenGL backend.
     * Letting that path continue can load Turnip but never install an OSMesa/GL
     * bridge table, which later crashes Minecraft with "There is no OpenGL
     * context current in the current thread". Treat -1 as Vulkan Zink when an
     * OSMesa payload is present.
     */
    if (strcmp(renderer, "-1") == 0) {
        printf("EGLBridge-v52: DROIDBRIDGE_RENDERER=-1 fallback -> vulkan_zink/libOSMesa_8.so\n");
        setenv("DROIDBRIDGE_RENDERER", "vulkan_zink", 1);
        if (getenv("LIB_MESA_NAME") == NULL || getenv("LIB_MESA_NAME")[0] == '\0') {
            setenv("LIB_MESA_NAME", "libOSMesa_8.so", 1);
        }
        renderer = getenv("DROIDBRIDGE_RENDERER");
    }

    /*
     * v57 hard guard: the Java-side legacy Freedreno alias can still be followed
     * by older launch code that copies the selected renderer library into
     * DROIDBRIDGE_EGL. The latest failed log showed exactly this mismatch:
     *   DROIDBRIDGE_RENDERER=vulkan_zink
     *   DROIDBRIDGE_EGL=libEGL_mesa.so
     *   LIB_MESA_NAME=libOSMesa_8.so
     * That route creates an OSMesa context but LWJGL later reports no current
     * OpenGL context. If the renderer is Vulkan Zink and libOSMesa_8 is the Mesa
     * frontend, force every bridge-library variable back to libOSMesa_8.so before
     * any bridge table is selected.
     */
    const char* lib_mesa_name_v57 = getenv("LIB_MESA_NAME");
    const char* droidbridge_runtime_egl_v57 = getenv("DROIDBRIDGE_EGL");
    if (renderer != NULL && strcmp(renderer, "vulkan_zink") == 0
            && lib_mesa_name_v57 != NULL
            && strstr(lib_mesa_name_v57, "libOSMesa_8.so") != NULL
            && (droidbridge_runtime_egl_v57 == NULL || strcmp(droidbridge_runtime_egl_v57, "libOSMesa_8.so") != 0)) {
        printf("EGLBridge-v61: forcing Vulkan Zink DROIDBRIDGE_EGL from %s to libOSMesa_8.so\n",
               droidbridge_runtime_egl_v57 != NULL ? droidbridge_runtime_egl_v57 : "<null>");
        setenv("DROIDBRIDGE_EGL", "libOSMesa_8.so", 1);
        setenv("DROIDBRIDGE_EGL_LIBRARY", "libOSMesa_8.so", 1);
        setenv("DROIDBRIDGE_EGL_LIBRARY", "libOSMesa_8.so", 1);
        setenv("DROIDBRIDGE_RENDERER_LIBRARY", "libOSMesa_8.so", 1);
        setenv("DROIDBRIDGE_RENDERER_LIBRARY", "libOSMesa_8.so", 1);
        setenv("OSMESA_LIB", "libOSMesa_8.so", 1);
        setenv("DROIDBRIDGE_OSMESA_LIBRARY", "libOSMesa_8.so", 1);
        setenv("OSMESA_LIBRARY", "libOSMesa_8.so", 1);
        setenv("LIBGL_OSMESA", "libOSMesa_8.so", 1);
        unsetenv("DROIDBRIDGE_MESA");
        unsetenv("DROIDBRIDGE_MESA_MODE");
        unsetenv("DROIDBRIDGE_MESA_DRIVER");
        unsetenv("DROIDBRIDGE_MESA_EGL");
        unsetenv("DROIDBRIDGE_MESA_GL");
        unsetenv("DROIDBRIDGE_EGL_FORCE_DESKTOP_GL");
        unsetenv("DROIDBRIDGE_EGL_NO_SYSTEM_FALLBACK");
    }

    const char* mesa_mode = getenv("DROIDBRIDGE_MESA_MODE");
    const char* mesa_driver = getenv("DROIDBRIDGE_MESA_DRIVER");
    const char* renderer_mesa_mode = getenv("DROIDBRIDGE_RENDERER_MESA_MODE");
    const char* droidbridge_mesa = getenv("DROIDBRIDGE_MESA");

    printf("EGLBridge-v70: env DROIDBRIDGE_RENDERER=%s DROIDBRIDGE_MESA_MODE=%s DROIDBRIDGE_MESA_DRIVER=%s DROIDBRIDGE_RENDERER_MESA_MODE=%s DROIDBRIDGE_MESA=%s DROIDBRIDGE_EGL=%s LIB_MESA_NAME=%s\n",
           renderer != NULL ? renderer : "<null>",
           mesa_mode != NULL ? mesa_mode : "<null>",
           mesa_driver != NULL ? mesa_driver : "<null>",
           renderer_mesa_mode != NULL ? renderer_mesa_mode : "<null>",
           droidbridge_mesa != NULL ? droidbridge_mesa : "<null>",
           getenv("DROIDBRIDGE_EGL") != NULL ? getenv("DROIDBRIDGE_EGL") : "<null>",
           getenv("LIB_MESA_NAME") != NULL ? getenv("LIB_MESA_NAME") : "<null>");

    bool mesa_zink_turnip = is_droidbridge_mesa_zink_turnip(
            renderer,
            mesa_mode,
            mesa_driver,
            renderer_mesa_mode,
            droidbridge_mesa
    );

    /*
     * Direct KGSL must be selected by DROIDBRIDGE_RENDERER itself.
     * Do NOT infer it from stale Mesa env variables. The previous v42/v43 test did
     * exactly that and broke Vulkan Zink by forcing Freedreno while
     * DROIDBRIDGE_RENDERER was still vulkan_zink.
     */
    bool direct_mesa_kgsl = (renderer != NULL && strcmp(renderer, "freedreno_kgsl") == 0);

    if (direct_mesa_kgsl) {
        /* v69: match the known working Adreno 740 path. The working log uses
         * MESA_LOADER_DRIVER_OVERRIDE=kgsl and does not force Turnip/Zink for
         * the freedreno_kgsl renderer.  The v65-v68 Zink/Turnip path boots but
         * keeps the same sky/cloud artifacts on 8 Gen 2 and can black-screen on
         * 8 Gen 3.
         */
        printf("EGLBridge-v70: direct Freedreno direct KGSL selected; using libEGL_mesa.so with MESA_LOADER_DRIVER_OVERRIDE=kgsl (no Turnip/Zink).\n");
        set_vulkan_ptr(NULL);
        setenv("DROIDBRIDGE_MESA", "1", 1);
        setenv("DROIDBRIDGE_MESA_MODE", "freedreno_kgsl", 1);
        setenv("DROIDBRIDGE_MESA_DRIVER", "kgsl", 1);
        setenv("DROIDBRIDGE_RENDERER_MESA_MODE", "freedreno_kgsl", 1);
        setenv("MESA_LOADER_DRIVER_OVERRIDE", "kgsl", 1);
        unsetenv("GALLIUM_DRIVER");
        setenv("DROIDBRIDGE_EGL", "libEGL_mesa.so", 1);
        setenv("LIB_MESA_NAME", "libEGL_mesa.so", 1);
        setenv("DROIDBRIDGE_MESA_EGL_PLATFORM_DISPLAY", "1", 1);
        setenv("DROIDBRIDGE_MESA_DESKTOP_GL", "1", 1);
        setenv("DROIDBRIDGE_EGL_FORCE_DESKTOP_GL", "1", 1);
        setenv("DROIDBRIDGE_EGL_NO_SYSTEM_FALLBACK", "1", 1);
        setenv("LIBGL_ES", "2", 1);
        setenv("LIBGL_MIPMAP", "3", 1);
        setenv("LIBGL_NOINTOVLHACK", "1", 1);
        setenv("LIBGL_NORMALIZE", "1", 1);
        setenv("LIBGL_NOERROR", "1", 1);
        setenv("EGL_PLATFORM", "android", 1);
        setenv("allow_higher_compat_version", "true", 1);
        /*
         * Snapshot 5 compiles thousands of GLSL 1.40 pipelines. Mesa's
         * force_glsl_extensions_warn option turns the harmless core UBO usage
         * into a high-severity KHR_debug message for every shader, producing
         * several megabytes of synchronous log traffic and visible stalls.
         * Keep the compatibility allowances but disable only that forced warning
         * mode for the corrected SDL3 persistent-buffer route.
         */
        if (env_enabled(getenv("DROIDBRIDGE_SDL3_QUIET_GLSL_WARNINGS"))) {
            setenv("force_glsl_extensions_warn", "false", 1);
        } else {
            setenv("force_glsl_extensions_warn", "true", 1);
        }
        setenv("allow_glsl_extension_directive_midshader", "true", 1);
        unsetenv("DROIDBRIDGE_DIRECT_FREEDRENO_LOAD_TURNIP");
        unsetenv("DROIDBRIDGE_DIRECT_FREEDRENO_TURNIP_ZINK_V68");
        unsetenv("DROIDBRIDGE_LOAD_TURNIP");
        unsetenv("DROIDBRIDGE_LOAD_TURNIP");
        unsetenv("DROIDBRIDGE_USE_CUSTOM_TURNIP");
        unsetenv("DROIDBRIDGE_EGL_FORCE_RGBX8888");
        unsetenv("DROIDBRIDGE_DIRECT_FREEDRENO_OPAQUE_RGBX8888");
        unsetenv("MESA_EXTENSION_OVERRIDE");
        unsetenv("DROIDBRIDGE_MESA_SAFE_SWAPS");
        setenv("DROIDBRIDGE_DIRECT_FREEDRENO_NATIVE_SURFACE_V69", "1", 1);
    } else {
        load_vulkan();
    }

    if (renderer != NULL && strcmp(renderer, "opengles3_desktopgl_zink_kopper") == 0) {
        /*
         * Kopper Zink mirrors the current FCL/Zalith route: stay on the normal
         * GL bridge, but make Mesa choose Zink and disable KMS swrast fallback.
         * Vulkan has already been selected/loaded by the block above so this
         * preserves DroidBridge's System Vulkan / Turnip driver selection.
         */
        printf("EGLBridge: Using DroidBridge Kopper Zink desktop GL route\n");
        setenv("GALLIUM_DRIVER", "zink", 1);
        setenv("MESA_LOADER_DRIVER_OVERRIDE", "zink", 1);
        setenv("MESA_ANDROID_NO_KMS_SWRAST", "1", 1);
        setenv("MESA_NO_ERROR", "0", 1);
        setenv("LIBGL_NOERROR", "0", 1);
        /*
         * JavaRuntimeBootstrap installs a stricter BTA 8+ Kopper profile after
         * the renderer defaults are built. Do not overwrite it here during
         * native EGL bootstrap: doing so silently changed BTA back to lazy
         * descriptors + compact/noreorder even though the launch log reported
         * sync/flushsync/noreorder/nobgc. That leaves Gallium worker work in
         * flight while BTA performs its startup fullscreen/renderer transition
         * and can crash libgallium_dri.so with an invalid queued object.
         *
         * Keep the normal fast Kopper defaults for every other game.
         */
        if (env_enabled(getenv("DROIDBRIDGE_KOPPER_BTA_STABILITY"))) {
            setenv("ZINK_DESCRIPTORS", "auto", 1);
            setenv("ZINK_DEBUG", "sync,flushsync,noreorder,nobgc", 1);
            setenv("mesa_glthread", "false", 1);
            printf("EGLBridge: BTA 8+ Kopper stability profile preserved descriptors=auto debug=sync,flushsync,noreorder,nobgc glthread=off\n");
        } else {
            setenv("ZINK_DESCRIPTORS", "lazy", 1);
            setenv("ZINK_DEBUG", "compact,noreorder", 1);
            setenv("mesa_glthread", "false", 1);
        }
        setenv("DROIDBRIDGE_EGL_FORCE_DESKTOP_GL", "1", 1);
        setenv("DROIDBRIDGE_MESA_DESKTOP_GL", "1", 1);
        printf("EGLBridge: Kopper Vulkan selection system=%s custom=%s driver=%s driverPath=%s namespace=%s\n",
               getenv("DROIDBRIDGE_USE_SYSTEM_VULKAN") != NULL ? getenv("DROIDBRIDGE_USE_SYSTEM_VULKAN") : "<null>",
               getenv("DROIDBRIDGE_USE_CUSTOM_TURNIP") != NULL ? getenv("DROIDBRIDGE_USE_CUSTOM_TURNIP") : "<null>",
               getenv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER") != NULL ? getenv("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER") : "<null>",
               getenv("DRIVER_PATH") != NULL ? getenv("DRIVER_PATH") : "<null>",
               getenv("DROIDBRIDGE_MESA_NAMESPACE_PATH") != NULL ? getenv("DROIDBRIDGE_MESA_NAMESPACE_PATH") : "<null>");
    }

    if (mesa_zink_turnip) {
        /*
         * DroidBridge intentionally keeps DROIDBRIDGE_RENDERER=opengles3 for this path
         * so libdroidbridge_runtime does not enter the legacy OSMesa vulkan_zink renderer.
         * However, the normal opengles branch creates a GLES context, which cannot
         * run Minecraft 26.x desktop GLSL shaders. Treat zink_turnip as a Mesa
         * desktop-GL bridge here before the generic opengles path can claim it.
         */
        configure_droidbridge_mesa_zink_turnip_desktop_gl();
        droidbridge_environ->config_renderer = RENDERER_GL4ES;
        set_gl_bridge_tbl();
    } else if (strncmp("opengles", renderer, 8) == 0) {
        droidbridge_environ->config_renderer = RENDERER_GL4ES;
        set_gl_bridge_tbl();
    }

    if (strcmp(renderer, "freedreno_kgsl") == 0) {
        printf("EGLBridge-v70: Using DroidBridge Mesa freedreno_kgsl GL bridge backend=kgsl/freedreno\n");
        setenv("DROIDBRIDGE_MESA", "1", 1);
        setenv("DROIDBRIDGE_RENDERER_MESA_MODE", "freedreno_kgsl", 1);
        setenv("DROIDBRIDGE_MESA_DRIVER", "kgsl", 1);
        setenv("MESA_LOADER_DRIVER_OVERRIDE", "kgsl", 1);
        unsetenv("GALLIUM_DRIVER");
        setenv("LIBGL_ES", "2", 1);
        setenv("DROIDBRIDGE_MESA_DESKTOP_GL", "1", 1);
        droidbridge_environ->config_renderer = RENDERER_GL4ES;
        set_gl_bridge_tbl();
    }

    if (strcmp(renderer, "custom_gallium") == 0) {
        droidbridge_environ->config_renderer = RENDERER_VK_ZINK;
        set_osm_bridge_tbl();
    }

    if (strcmp(renderer, "vulkan_zink") == 0 && !mesa_zink_turnip) {
        printf("EGLBridge: Using DroidBridge/Mesa Zink Vulkan bridge\n");
        setenv("DROIDBRIDGE_EGL", "libOSMesa_8.so", 1);
        setenv("DROIDBRIDGE_EGL_LIBRARY", "libOSMesa_8.so", 1);
        setenv("DROIDBRIDGE_EGL_LIBRARY", "libOSMesa_8.so", 1);
        setenv("LIB_MESA_NAME", "libOSMesa_8.so", 1);
        droidbridge_environ->config_renderer = RENDERER_VK_ZINK;
        load_vulkan();
        setenv("GALLIUM_DRIVER", "zink", 1);
        setenv("MESA_LOADER_DRIVER_OVERRIDE", "zink", 1);
        setenv("DROIDBRIDGE_RENDERER_MESA_MODE", "zink_turnip", 1);
        set_osm_bridge_tbl();
    }

    if (strcmp(renderer, "gallium_freedreno") == 0) {
        droidbridge_environ->config_renderer = RENDERER_VK_ZINK;
        setenv("MESA_LOADER_DRIVER_OVERRIDE", "kgsl", 1);
        setenv("GALLIUM_DRIVER", "freedreno", 1);
        set_osm_bridge_tbl();
    }

    if (strcmp(renderer, "gallium_panfrost") == 0) {
        droidbridge_environ->config_renderer = RENDERER_VK_ZINK;
        setenv("GALLIUM_DRIVER", "panfrost", 1);
        setenv("MESA_DISK_CACHE_SINGLE_FILE", "1", 1);
        set_osm_bridge_tbl();
    }

    if (strcmp(renderer, "gallium_virgl") == 0) {
        droidbridge_environ->config_renderer = RENDERER_VIRGL;
        setenv("GALLIUM_DRIVER", "virpipe", 1);
        setenv("OSMESA_NO_FLUSH_FRONTBUFFER", "1", false);
        setenv("MESA_GL_VERSION_OVERRIDE", "4.3", 1);
        setenv("MESA_GLSL_VERSION_OVERRIDE", "430", 1);

        const char* noFlushFrontbuffer = getenv("OSMESA_NO_FLUSH_FRONTBUFFER");
        if (noFlushFrontbuffer != NULL && strcmp(noFlushFrontbuffer, "1") == 0) {
            printf("VirGL: OSMesa buffer flush is DISABLED!\n");
        }

        loadSymbolsVirGL();
        virglInit();
        return 0;
    }

    if (br_init()) {
        br_setup_window();
    }

    return 0;
}

EXTERNAL_API int droidbridgeInit(void) {
    /*
     * NativeGLFW returns before droidbridgeInitOpenGL(), so initialize the
     * launcher/Minecraft VSync clamp here for every renderer route.
     */
    droidbridgeRefreshForceVsyncFromEnvironment("droidbridgeInit");

    if (db_native_glfw_enabled()) {
        printf("DroidBridgeNativeGLFW: droidbridgeInit using NativeGLFW instead of GLBridge\n");
        if (droidbridge_environ->droidbridgeWindow != NULL) {
            ANativeWindow_acquire(droidbridge_environ->droidbridgeWindow);
            droidbridge_environ->savedWidth = ANativeWindow_getWidth(droidbridge_environ->droidbridgeWindow);
            droidbridge_environ->savedHeight = ANativeWindow_getHeight(droidbridge_environ->droidbridgeWindow);
            ANativeWindow_setBuffersGeometry(
                    droidbridge_environ->droidbridgeWindow,
                    droidbridge_environ->savedWidth,
                    droidbridge_environ->savedHeight,
                    AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM
            );
            db_native_glfw_attach_existing_window("droidbridgeInit");
        } else {
            printf("DroidBridgeNativeGLFW: droidbridgeInit had no ANativeWindow yet\n");
        }
        return 1;
    }

    ANativeWindow_acquire(droidbridge_environ->droidbridgeWindow);
    droidbridge_environ->savedWidth = ANativeWindow_getWidth(droidbridge_environ->droidbridgeWindow);
    droidbridge_environ->savedHeight = ANativeWindow_getHeight(droidbridge_environ->droidbridgeWindow);
    ANativeWindow_setBuffersGeometry(
            droidbridge_environ->droidbridgeWindow,
            droidbridge_environ->savedWidth,
            droidbridge_environ->savedHeight,
            AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM
    );
    droidbridgeInitOpenGL();
    return 1;
}

EXTERNAL_API void droidbridgeSetWindowHint(int hint, int value) {
    if (hint != GLFW_CLIENT_API) {
        return;
    }

    switch (value) {
        case GLFW_NO_API:
            droidbridge_environ->config_renderer = RENDERER_VULKAN;
            /* Nothing to do: initialization is handled in Java-side */
            break;

        case GLFW_OPENGL_API:
            /* Nothing to do: initialization is called in droidbridgeCreateContext */
            break;

        default:
            printf("GLFW: Unimplemented API 0x%x\n", value);
            abort();
    }
}

EXTERNAL_API int droidbridgeGetWindowClientApi(void* window) {
    if (window == NULL || droidbridge_environ == NULL) return GLFW_NO_API;

    /*
     * Report the API of the queried window, not the latest global window hint.
     * Vulkan Reforged sets GLFW_NO_API before it asks whether NeoForge's already
     * existing early-display window was OpenGL. That old handle is the GL bridge
     * bundle, while a contextless Vulkan window is the process ANativeWindow.
     */
    int api;
    if (droidbridge_environ->mainWindowBundle != NULL
            && window == (void*) droidbridge_environ->mainWindowBundle) {
        api = GLFW_OPENGL_API;
    } else if (droidbridge_environ->droidbridgeWindow != NULL
            && window == (void*) droidbridge_environ->droidbridgeWindow) {
        api = GLFW_NO_API;
    } else {
        /* Non-main GL contexts are still GL bridge bundles. Keep this fallback
         * tied to the current bridge mode without overriding the two exact
         * identities above. */
        api = droidbridge_environ->config_renderer == RENDERER_VULKAN
                ? GLFW_NO_API
                : GLFW_OPENGL_API;
    }

    printf("DroidBridgeGLFW: GLFW_CLIENT_API window=%p mainBundle=%p nativeWindow=%p result=0x%x\n",
           window,
           (void*) droidbridge_environ->mainWindowBundle,
           (void*) droidbridge_environ->droidbridgeWindow,
           api);
    fflush(stdout);
    return api;
}

EXTERNAL_API void droidbridgeDestroyWindow(void* window) {
    if (window == NULL || droidbridge_environ == NULL) return;

    /*
     * Destroy based on the handle being destroyed, not the latest global GLFW
     * hint. Vulkan Reforged may already have requested GLFW_NO_API when it
     * destroys NeoForge's old OpenGL early window. That old handle is still the
     * GL bridge's mainWindowBundle and must release its EGLWindowSurface before
     * Vulkan can reuse the underlying ANativeWindow.
     */
    if (droidbridge_environ->mainWindowBundle != NULL
            && window == (void*) droidbridge_environ->mainWindowBundle) {
        printf("DroidBridgeGLFW: destroying GL window bundle for Vulkan handoff window=%p nativeWindow=%p\n",
               window,
               (void*) droidbridge_environ->droidbridgeWindow);
        fflush(stdout);
        gl_destroy_context((gl_render_window_t*) window);
    }
}

EXTERNAL_API void droidbridgeSwapBuffers(void) {
    const int64_t presentEntryNs = droidbridgeMonotonicNs();
    droidbridgeFramePaceIfNeeded("glfwSwapBuffers", false);
    calculateFPS();

    if (db_native_glfw_enabled()) {
        db_native_glfw_swap_counter++;
        db_native_glfw_reapply_swap_interval_if_needed("beforeSwap");
        void* handle = db_native_glfw_open();
        if (handle != NULL) {
            db_native_glfw_swap_buffers_fn swap_buffers =
                    (db_native_glfw_swap_buffers_fn) dlsym(handle, "droidbridge_native_glfw_swap_buffers");
            if (swap_buffers != NULL) {
                if (db_native_glfw_swap_counter <= 5 || (db_native_glfw_swap_counter % 120ULL) == 0ULL) {
                    printf("DroidBridgeNativeGLFW: droidbridgeSwapBuffers frame=%" PRIu64 " -> native\n", db_native_glfw_swap_counter);
                    fflush(stdout);
                }
                swap_buffers();
            } else {
                const char* error = dlerror();
                printf("DroidBridgeNativeGLFW: missing droidbridge_native_glfw_swap_buffers error=%s\n", error ? error : "unknown");
                fflush(stdout);
            }
        } else {
            printf("DroidBridgeNativeGLFW: droidbridgeSwapBuffers frame=%" PRIu64 " no native handle\n", db_native_glfw_swap_counter);
            fflush(stdout);
        }
        return;
    }

    if (droidbridge_environ->config_renderer == RENDERER_VK_ZINK ||
        droidbridge_environ->config_renderer == RENDERER_GL4ES) {
        const int64_t swapStartNs = droidbridgeMonotonicNs();
        br_swap_buffers();
        const int64_t swapEndNs = droidbridgeMonotonicNs();
        droidbridgeObserveGlSwap(presentEntryNs, swapStartNs, swapEndNs);
    }

    if (droidbridge_environ->config_renderer == RENDERER_VIRGL) {
        virglSwapBuffers();
    }
}

EXTERNAL_API void droidbridgeMakeCurrent(void* window) {
    if (db_native_glfw_enabled()) {
        void* handle = db_native_glfw_open();
        if (handle != NULL) {
            if (!db_native_glfw_surface_attached) {
                db_native_glfw_attach_existing_window("droidbridgeMakeCurrent");
            }
            (void) db_native_glfw_make_current_checked(
                    handle,
                    window != NULL ? window : (void*) droidbridge_environ->droidbridgeWindow,
                    "droidbridgeMakeCurrent"
            );
        }
        return;
    }

    if (droidbridge_environ->config_renderer == RENDERER_VK_ZINK ||
        droidbridge_environ->config_renderer == RENDERER_GL4ES) {
        br_make_current((basic_render_window_t*) window);
    }

    if (droidbridge_environ->config_renderer == RENDERER_VIRGL) {
        virglMakeCurrent(window);
    }
}

EXTERNAL_API void* droidbridgeCreateContext(void* contextSrc) {
    if (db_native_glfw_enabled()) {
        void* handle = db_native_glfw_open();
        if (handle != NULL) {
            db_native_glfw_attach_existing_window("droidbridgeCreateContext");
            db_native_glfw_configure_if_available(handle);
            db_native_glfw_create_context_fn create_context =
                    (db_native_glfw_create_context_fn) dlsym(handle, "droidbridge_native_glfw_create_context");
            if (create_context != NULL) {
                void* ctx = create_context(contextSrc);
                printf("DroidBridgeNativeGLFW: droidbridgeCreateContext returned %p\n", ctx);
                if (ctx == NULL) {
                    printf("DroidBridgeNativeGLFW: refusing invalid NULL NativeGLFW context\n");
                    fflush(stdout);
                    return NULL;
                }

                if (!db_native_glfw_make_current_checked(
                        handle,
                        (void*) droidbridge_environ->droidbridgeWindow,
                        "droidbridgeCreateContext"
                )) {
                    db_native_glfw_game_context_ready = 0;
                    printf("DroidBridgeNativeGLFW: refusing context because eglMakeCurrent did not succeed\n");
                    fflush(stdout);
                    return NULL;
                }
                db_native_glfw_game_context_ready = 1;
                printf("DroidBridgeNativeGLFW: game/render context ready; linkerhook must preserve current context\n");
                fflush(stdout);
                return ctx;
            }
        }
        return NULL;
    }

    if (droidbridge_environ->config_renderer == RENDERER_VULKAN) {
        return (void*) droidbridge_environ->droidbridgeWindow;
    }

    if (droidbridge_environ->config_renderer == RENDERER_VIRGL) {
        return virglCreateContext(contextSrc);
    }

    return br_init_context((basic_render_window_t*) contextSrc);
}

void* maybe_load_vulkan(void) {
    const char* current = getenv("VULKAN_PTR");
    if (current == NULL || current[0] == '\0') {
        load_vulkan();
        current = getenv("VULKAN_PTR");
    }

    if (current == NULL || current[0] == '\0') {
        printf("OSMDroid: maybe_load_vulkan(): no Vulkan pointer available\n");
        return NULL;
    }

    return (void*) strtoull(current, NULL, 16);
}

static int frameCount = 0;
static int fps = 0;
static time_t lastTime = 0;

static int64_t g_framePaceLastNs = 0;
static uint64_t g_framePaceCounter = 0;
static volatile int g_glSwapIntervalEnabled = 0;
static volatile int g_glAdaptivePacingArmed = 0;
static int g_glFastSwapStreak = 0;
static int64_t g_glLastPresentEntryNs = 0;
static int g_glAdaptivePacingLogged = 0;
static int g_glBlockingSwapObserved = 0;
static int g_glBlockingSwapStreak = 0;
static volatile int g_framePaceTargetFpsOverride = 0;
static volatile int g_vulkanStartupVsyncChecked = 0;
/* Set only after the LWJGL Vulkan present path is actually observed. The
 * launch-time System Vulkan preference is not proof that Minecraft selected
 * its Vulkan backend (26.1.x can still be OpenGL). */
static volatile int g_actualVulkanPresentationObserved = 0;
/* -1 means no live in-game override has reached native code yet. Launch-time
 * environment then decides the initial state. 0/1 are authoritative after the
 * player changes Minecraft's VSync option. */
static volatile int g_liveMinecraftVsyncOverride = -1;
/* Android can stop the BufferQueue producer before Vulkan notices Activity
 * lifecycle changes. The LWJGL acquire hook waits on this gate instead of
 * letting Minecraft turn VK_TIMEOUT into a fatal GPU-timeout crash. */
static volatile int g_vulkanPresentationPaused = 0;
/* Incremented for every pause/resume edge so an acquire that was already in
 * flight when Android detached the producer still forces one clean swapchain
 * recreation after the app returns. */
static volatile int g_vulkanPresentationGeneration = 0;

static bool droidbridgeFramePacingEnvEnabled(void) {
    const char* value = getenv("DROIDBRIDGE_VSYNC_FRAME_PACING");
    return env_enabled(value);
}

static bool droidbridgeVulkanFifoEnabled(void) {
    const char* value = getenv("DROIDBRIDGE_VULKAN_FORCE_FIFO");
    return env_enabled(value);
}

static int droidbridgeLogicalVsyncEnabled(void) {
    const int liveOverride = __atomic_load_n(
            &g_liveMinecraftVsyncOverride,
            __ATOMIC_ACQUIRE);
    if (liveOverride >= 0) return liveOverride != 0;

    /* The launch-time logical pacer is authoritative for wrapped/system
     * Vulkan. Keep FORCE_FIFO as a compatibility fallback for component sets
     * installed before the Java bootstrap started exporting pacing=1. */
    return droidbridgeFramePacingEnvEnabled() || droidbridgeVulkanFifoEnabled();
}

static int droidbridgeVulkanPresentationPaused(void) {
    return __atomic_load_n(&g_vulkanPresentationPaused, __ATOMIC_ACQUIRE) != 0;
}

static void droidbridgeSetVulkanPresentationPaused(int paused, const char* reason) {
    const int normalized = paused != 0 ? 1 : 0;
    const int previous = __atomic_exchange_n(
            &g_vulkanPresentationPaused,
            normalized,
            __ATOMIC_ACQ_REL);
    if (previous != normalized) {
        const int generation = __atomic_add_fetch(
                &g_vulkanPresentationGeneration,
                1,
                __ATOMIC_ACQ_REL);
        printf("DroidBridgeVulkanLifecycle: presentationPaused=%d previous=%d generation=%d reason=%s\n",
               normalized,
               previous,
               generation,
               reason != NULL ? reason : "unknown");
        fflush(stdout);
    }
}

static int droidbridgeFramePacingTargetFps(void) {
    int liveOverride = __atomic_load_n(&g_framePaceTargetFpsOverride, __ATOMIC_RELAXED);
    if (liveOverride >= 30 && liveOverride <= 360) {
        return liveOverride;
    }

    const char* value = getenv("DROIDBRIDGE_VSYNC_TARGET_FPS");
    if (value != NULL && value[0] != '\0') {
        char* end = NULL;
        long parsed = strtol(value, &end, 10);
        if (end != value && parsed >= 30 && parsed <= 360) {
            return (int) parsed;
        }
    }
    return 60;
}

static int64_t droidbridgeMonotonicNs(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ((int64_t) ts.tv_sec * 1000000000LL) + (int64_t) ts.tv_nsec;
}

static void droidbridgeSleepNs(int64_t ns) {
    if (ns <= 0) return;
    struct timespec req;
    req.tv_sec = (time_t) (ns / 1000000000LL);
    req.tv_nsec = (long) (ns % 1000000000LL);
    while (nanosleep(&req, &req) != 0 && errno == EINTR) {
        // retry with remaining time
    }
}

static void droidbridgeFramePaceIfNeeded(const char* reason, bool vulkanFrame) {
    bool pacingRequested = droidbridgeFramePacingEnvEnabled();

    /*
     * Vulkan present modes already own presentation timing. Sleeping here before
     * vkQueuePresentKHR adds a second limiter on top of FIFO, halves throughput on
     * high-refresh displays and prevents VSync OFF from exposing real performance.
     * Keep the software pacer exclusively for wrapped OpenGL implementations whose
     * eglSwapInterval(1) call is known to return without blocking.
     */
    if (vulkanFrame) {
        if (__atomic_exchange_n(&g_vulkanStartupVsyncChecked, 1, __ATOMIC_RELAXED) == 0) {
            printf("DroidBridgeVulkanVSync: native frame sleep bypassed fifo=%d requestedPacing=%d targetFps=%d reason=%s\n",
                   droidbridgeVulkanFifoEnabled() ? 1 : 0,
                   pacingRequested ? 1 : 0,
                   droidbridgeFramePacingTargetFps(),
                   reason != NULL ? reason : "unknown");
            fflush(stdout);
        }
        g_framePaceLastNs = 0;
        g_framePaceCounter = 0;
        return;
    }

    bool enabled = pacingRequested
            && g_glSwapIntervalEnabled
            && __atomic_load_n(&g_glAdaptivePacingArmed, __ATOMIC_ACQUIRE) != 0;

    if (!enabled) {
        if (g_framePaceLastNs != 0) {
            g_framePaceLastNs = 0;
            g_framePaceCounter = 0;
        }
        return;
    }

    int targetFps = droidbridgeFramePacingTargetFps();
    const int64_t intervalNs = 1000000000LL / (int64_t) targetFps;
    int64_t now = droidbridgeMonotonicNs();

    if (g_framePaceLastNs == 0) {
        g_framePaceLastNs = now;
        g_framePaceCounter = 0;
        printf("DroidBridgeFramePacer: enabled targetFps=%d intervalNs=%lld reason=%s\n",
               targetFps,
               (long long) intervalNs,
               reason != NULL ? reason : "unknown");
        fflush(stdout);
        return;
    }

    int64_t next = g_framePaceLastNs + intervalNs;
    int64_t sleepNs = next - now;
    if (sleepNs > 0) {
        droidbridgeSleepNs(sleepNs);
        g_framePaceLastNs = next;
    } else {
        // If rendering stalled, resync instead of trying to catch up and burst frames.
        g_framePaceLastNs = now;
    }

    g_framePaceCounter++;
    // Avoid synchronous stdout/file flushes every second. Those diagnostics were
    // visible as periodic frame-time spikes on high-refresh-rate devices.
    if (g_framePaceCounter <= 2 || (g_framePaceCounter % 3600ULL) == 0ULL) {
        printf("DroidBridgeFramePacer: frame=%" PRIu64 " targetFps=%d sleptNs=%lld reason=%s\n",
               g_framePaceCounter,
               targetFps,
               (long long) (sleepNs > 0 ? sleepNs : 0),
               reason != NULL ? reason : "unknown");
        fflush(stdout);
    }
}

static void droidbridgeResetGlAdaptivePacing(void) {
    __atomic_store_n(&g_glAdaptivePacingArmed, 0, __ATOMIC_RELEASE);
    g_glFastSwapStreak = 0;
    g_glLastPresentEntryNs = 0;
    g_glBlockingSwapObserved = 0;
    g_glBlockingSwapStreak = 0;
    g_framePaceLastNs = 0;
    g_framePaceCounter = 0;
}

static void droidbridgeObserveGlSwap(
        int64_t presentEntryNs,
        int64_t swapStartNs,
        int64_t swapEndNs) {
    if (!droidbridgeFramePacingEnvEnabled()
            || !g_glSwapIntervalEnabled
            || presentEntryNs <= 0
            || swapStartNs <= 0
            || swapEndNs < swapStartNs) {
        droidbridgeResetGlAdaptivePacing();
        return;
    }

    const int targetFps = droidbridgeFramePacingTargetFps();
    const int64_t periodNs = 1000000000LL / (int64_t) targetFps;
    const int64_t swapDurationNs = swapEndNs - swapStartNs;
    const int64_t presentIntervalNs = g_glLastPresentEntryNs > 0
            ? presentEntryNs - g_glLastPresentEntryNs : 0;
    g_glLastPresentEntryNs = presentEntryNs;

    /* Ignore isolated SurfaceFlinger/world-load stalls. Four consecutive samples are
     * required before treating EGL as the pacing clock, and a sustained fast sequence
     * can recover software pacing if the provider becomes nonblocking again. */
    const bool blockingSample = swapDurationNs >= (periodNs * 3) / 5
            && presentIntervalNs > 0
            && presentIntervalNs >= (periodNs * 4) / 5;
    const bool fastSample = swapDurationNs < periodNs / 4;

    if (blockingSample) {
        if (g_glBlockingSwapStreak < 1000) g_glBlockingSwapStreak++;
        g_glFastSwapStreak = 0;
    } else {
        g_glBlockingSwapStreak = 0;
    }

    if (!g_glBlockingSwapObserved && g_glBlockingSwapStreak >= 4) {
        g_glBlockingSwapObserved = 1;
        if (__atomic_exchange_n(&g_glAdaptivePacingArmed, 0, __ATOMIC_ACQ_REL) != 0) {
            printf("DroidBridgeFramePacer: stable blocking EGL VSync observed; software pacing disarmed targetFps=%d swapDurationNs=%lld\n",
                   targetFps, (long long) swapDurationNs);
            fflush(stdout);
        }
        g_framePaceLastNs = 0;
        g_framePaceCounter = 0;
        return;
    }

    if (g_glBlockingSwapObserved) {
        if (fastSample && presentIntervalNs > 0
                && presentIntervalNs < (periodNs * 9) / 10) {
            if (g_glFastSwapStreak < 1000) g_glFastSwapStreak++;
            if (g_glFastSwapStreak >= 8) {
                g_glBlockingSwapObserved = 0;
                g_glBlockingSwapStreak = 0;
            } else {
                return;
            }
        } else {
            g_glFastSwapStreak = 0;
            return;
        }
    }

    if (presentIntervalNs > 0
            && presentIntervalNs < (periodNs * 9) / 10
            && swapDurationNs < periodNs / 4) {
        if (g_glFastSwapStreak < 1000) g_glFastSwapStreak++;
    } else if (__atomic_load_n(&g_glAdaptivePacingArmed, __ATOMIC_ACQUIRE) == 0) {
        g_glFastSwapStreak = 0;
    }

    if (g_glFastSwapStreak >= 6
            && __atomic_exchange_n(&g_glAdaptivePacingArmed, 1, __ATOMIC_ACQ_REL) == 0) {
        g_framePaceLastNs = swapEndNs;
        g_framePaceCounter = 0;
        if (!g_glAdaptivePacingLogged) {
            g_glAdaptivePacingLogged = 1;
            printf("DroidBridgeFramePacer: eglSwapInterval is nonblocking; adaptive software pacing armed targetFps=%d presentIntervalNs=%lld swapDurationNs=%lld\n",
                   targetFps,
                   (long long) presentIntervalNs,
                   (long long) swapDurationNs);
            fflush(stdout);
        }
    }
}

void calculateFPS(void) {
    frameCount++;
    time_t currentTime = time(NULL);

    if (currentTime != lastTime) {
        lastTime = currentTime;
        fps = frameCount;
        frameCount = 0;
    }
}

void setNativeWindowSwapInterval(struct ANativeWindow* nativeWindow, int swapInterval);
EXTERNAL_API void droidbridgeSwapInterval(int interval);
EXTERNAL_API int droidbridge_sdl3_set_openjdk_swap_interval(
        int requested,
        void *original_setter_ptr);

EXTERNAL_API JNIEXPORT jint JNICALL
Java_org_lwjgl_glfw_CallbackBridge_getCurrentFps(JNIEnv* env, jclass clazz) {
    return fps;
}

EXTERNAL_API JNIEXPORT void JNICALL
Java_ca_dnamobile_dbridgelauncher_GameActivity_nativeSetVsyncTargetFps(ABI_COMPAT JNIEnv* env, ABI_COMPAT jclass clazz, jint targetFps) {
    int normalized = (int) targetFps;
    if (normalized < 30 || normalized > 360) {
        normalized = 60;
    }

    int old = __atomic_exchange_n(&g_framePaceTargetFpsOverride, normalized, __ATOMIC_RELAXED);
    char buffer[32];
    snprintf(buffer, sizeof(buffer), "%d", normalized);
    setenv("DROIDBRIDGE_VSYNC_TARGET_FPS", buffer, 1);

    if (old != normalized) {
        g_framePaceLastNs = 0;
        g_framePaceCounter = 0;
        printf("DroidBridgeFramePacer: live targetFps changed %d -> %d\n", old, normalized);
        fflush(stdout);
    }
}

EXTERNAL_API JNIEXPORT void JNICALL
Java_ca_dnamobile_javalauncher_GameActivity_nativeSetVsyncTargetFps(ABI_COMPAT JNIEnv* env, ABI_COMPAT jclass clazz, jint targetFps) {
    Java_ca_dnamobile_dbridgelauncher_GameActivity_nativeSetVsyncTargetFps(env, clazz, targetFps);
}

EXTERNAL_API JNIEXPORT void JNICALL
Java_ca_dnamobile_droidbridgelauncher_GameActivity_nativeSetVsyncTargetFps(ABI_COMPAT JNIEnv* env, ABI_COMPAT jclass clazz, jint targetFps) {
    Java_ca_dnamobile_dbridgelauncher_GameActivity_nativeSetVsyncTargetFps(env, clazz, targetFps);
}

static void droidbridgeApplyMinecraftVsyncLive(int enabled) {
    const int normalized = enabled != 0 ? 1 : 0;
    __atomic_store_n(
            &g_liveMinecraftVsyncOverride,
            normalized,
            __ATOMIC_RELEASE);
    const int actualVulkan = __atomic_load_n(
            &g_actualVulkanPresentationObserved,
            __ATOMIC_ACQUIRE) != 0;
    setenv("FORCE_VSYNC", normalized ? "true" : "false", 1);
    setenv("DROIDBRIDGE_MOBILEGLUES_FORCE_VSYNC", normalized ? "1" : "0", 1);
    /* Both backends consume this live option. OpenGL uses the existing swap
     * interval/fallback pacer. Vulkan uses it only as live swapchain-mode state;
     * FIFO remains the actual pacer and event polling is never treated as a frame. */
    setenv("DROIDBRIDGE_VSYNC_FRAME_PACING", normalized ? "1" : "0", 1);
    droidbridgeRefreshForceVsyncFromEnvironment("liveMinecraftOption");

    if (!normalized) {
        g_framePaceLastNs = 0;
        g_framePaceCounter = 0;
    }

    if (droidbridge_environ == NULL) {
        printf("EGLBridge: live Minecraft VSync deferred enabled=%d; renderer environment unavailable\n", normalized);
        fflush(stdout);
        return;
    }

    if (droidbridge_environ->config_renderer == RENDERER_VULKAN || actualVulkan) {
        /* The patched LWJGL present-result hook observes this state and requests
         * one swapchain recreation when the live option changes. Do not mutate an
         * OpenGL swap interval merely because the launcher selected system Vulkan. */
        printf("EGLBridge: live Minecraft VSync enabled=%d applied to Vulkan swapchain state (FIFO authoritative)\n",
               normalized);
        fflush(stdout);
        return;
    }

    droidbridgeSwapInterval(normalized);

    /*
     * Snapshot 4's SDL3 wrapped-OpenGL path presents through the direct
     * OpenJDK shim and cached EGL surface, not the older bridge table above.
     * Apply the live option to that real presentation owner immediately so a
     * world reload is not required when changing VSync in Video Settings.
     */
    int sdl3_applied = 0;
    const char *sdl3_kind = getenv("DROIDBRIDGE_SDL3_OPENGL_KIND");
    const int direct_mesa_sdl3 = env_enabled(getenv("DROIDBRIDGE_NATIVE_GLFW_KGSL"))
            || (getenv("DROIDBRIDGE_MESA_MODE") != NULL
                && strcasecmp(getenv("DROIDBRIDGE_MESA_MODE"), "freedreno_kgsl") == 0);
    const int wrapped_sdl3 = env_enabled(getenv("DROIDBRIDGE_SDL3_WRAPPED_OPENGL"));
    if (direct_mesa_sdl3
            || wrapped_sdl3
            || (sdl3_kind != NULL
                && (strcasecmp(sdl3_kind, "mobileglues") == 0
                    || strcasecmp(sdl3_kind, "krypton") == 0))) {
        sdl3_applied = droidbridge_sdl3_set_openjdk_swap_interval(
                normalized,
                NULL);
    }

    printf("EGLBridge: live Minecraft VSync applied enabled=%d renderer=%d sdl3Presentation=%d directMesa=%d wrappedSdl3=%d kind=%s\n",
           normalized,
           droidbridge_environ->config_renderer,
           sdl3_applied,
           direct_mesa_sdl3,
           wrapped_sdl3,
           sdl3_kind != NULL ? sdl3_kind : "<none>");
    fflush(stdout);
}

EXTERNAL_API JNIEXPORT void JNICALL
Java_ca_dnamobile_dbridgelauncher_GameActivity_nativeApplyMinecraftVsync(ABI_COMPAT JNIEnv* env, ABI_COMPAT jclass clazz, jboolean enabled) {
    droidbridgeApplyMinecraftVsyncLive(enabled == JNI_TRUE ? 1 : 0);
}

EXTERNAL_API JNIEXPORT void JNICALL
Java_ca_dnamobile_javalauncher_GameActivity_nativeApplyMinecraftVsync(ABI_COMPAT JNIEnv* env, ABI_COMPAT jclass clazz, jboolean enabled) {
    Java_ca_dnamobile_dbridgelauncher_GameActivity_nativeApplyMinecraftVsync(env, clazz, enabled);
}

EXTERNAL_API JNIEXPORT void JNICALL
Java_ca_dnamobile_droidbridgelauncher_GameActivity_nativeApplyMinecraftVsync(ABI_COMPAT JNIEnv* env, ABI_COMPAT jclass clazz, jboolean enabled) {
    Java_ca_dnamobile_dbridgelauncher_GameActivity_nativeApplyMinecraftVsync(env, clazz, enabled);
}

/*
 * OpenJDK-visible state API.
 *
 * libdroidbridge_runtime is loaded by Android/ART before the embedded HotSpot VM
 * starts, so JNI methods exported directly by this library are not associated
 * with the OpenJDK class loader. droidbridge_openjdk_hooks.so is loaded from
 * HotSpot and resolves these plain C symbols with dlsym().
 */
EXTERNAL_API __attribute__((visibility("default"))) void
droidbridge_openjdk_notify_vulkan_presentation(void) {
    const int previous = __atomic_exchange_n(
            &g_actualVulkanPresentationObserved,
            1,
            __ATOMIC_ACQ_REL);
    if (previous == 0) {
        printf("DroidBridgeVulkanVSync: actual Vulkan presentation observed; FIFO requested and Vulkan image-acquire cycles own supplemental pacing/FPS\n");
        fflush(stdout);
    }
}

EXTERNAL_API __attribute__((visibility("default"))) int
droidbridge_openjdk_is_logical_vsync_enabled(void) {
    return droidbridgeLogicalVsyncEnabled() ? 1 : 0;
}

EXTERNAL_API __attribute__((visibility("default"))) int
droidbridge_openjdk_is_presentation_paused(void) {
    return droidbridgeVulkanPresentationPaused() ? 1 : 0;
}

EXTERNAL_API __attribute__((visibility("default"))) int
droidbridge_openjdk_get_presentation_generation(void) {
    return (int) __atomic_load_n(
            &g_vulkanPresentationGeneration,
            __ATOMIC_ACQUIRE);
}

EXTERNAL_API __attribute__((visibility("default"))) int
droidbridge_openjdk_get_vsync_target_fps(void) {
    return droidbridgeFramePacingTargetFps();
}

/* ART-side compatibility JNI exports. OpenJDK resolves through the dedicated
 * droidbridge_openjdk_hooks library below, not through these symbols. */
EXTERNAL_API JNIEXPORT void JNICALL
Java_org_lwjgl_system_DroidBridgeFrameCounter_nativeNotifyVulkanPresentation(
        ABI_COMPAT JNIEnv* env,
        ABI_COMPAT jclass clazz) {
    (void) env;
    (void) clazz;
    droidbridge_openjdk_notify_vulkan_presentation();
}

EXTERNAL_API JNIEXPORT jboolean JNICALL
Java_org_lwjgl_system_DroidBridgeFrameCounter_nativeIsLogicalVsyncEnabled(
        ABI_COMPAT JNIEnv* env,
        ABI_COMPAT jclass clazz) {
    (void) env;
    (void) clazz;
    return droidbridge_openjdk_is_logical_vsync_enabled() ? JNI_TRUE : JNI_FALSE;
}

EXTERNAL_API JNIEXPORT jboolean JNICALL
Java_org_lwjgl_system_DroidBridgeFrameCounter_nativeIsPresentationPaused(
        ABI_COMPAT JNIEnv* env,
        ABI_COMPAT jclass clazz) {
    (void) env;
    (void) clazz;
    return droidbridge_openjdk_is_presentation_paused() ? JNI_TRUE : JNI_FALSE;
}

EXTERNAL_API JNIEXPORT jint JNICALL
Java_org_lwjgl_system_DroidBridgeFrameCounter_nativeGetPresentationGeneration(
        ABI_COMPAT JNIEnv* env,
        ABI_COMPAT jclass clazz) {
    (void) env;
    (void) clazz;
    return (jint) droidbridge_openjdk_get_presentation_generation();
}

EXTERNAL_API JNIEXPORT jint JNICALL
Java_org_lwjgl_system_DroidBridgeFrameCounter_nativeGetVsyncTargetFps(
        ABI_COMPAT JNIEnv* env,
        ABI_COMPAT jclass clazz) {
    (void) env;
    (void) clazz;
    return (jint) droidbridge_openjdk_get_vsync_target_fps();
}

EXTERNAL_API JNIEXPORT void JNICALL
Java_ca_dnamobile_dbridgelauncher_GameActivity_nativeSetVulkanPresentationPaused(
        ABI_COMPAT JNIEnv* env,
        ABI_COMPAT jclass clazz,
        jboolean paused) {
    (void) env;
    (void) clazz;
    droidbridgeSetVulkanPresentationPaused(
            paused == JNI_TRUE ? 1 : 0,
            "Android GameActivity lifecycle");
}

EXTERNAL_API JNIEXPORT void JNICALL
Java_ca_dnamobile_javalauncher_GameActivity_nativeSetVulkanPresentationPaused(
        ABI_COMPAT JNIEnv* env,
        ABI_COMPAT jclass clazz,
        jboolean paused) {
    Java_ca_dnamobile_dbridgelauncher_GameActivity_nativeSetVulkanPresentationPaused(
            env,
            clazz,
            paused);
}

EXTERNAL_API JNIEXPORT void JNICALL
Java_ca_dnamobile_droidbridgelauncher_GameActivity_nativeSetVulkanPresentationPaused(
        ABI_COMPAT JNIEnv* env,
        ABI_COMPAT jclass clazz,
        jboolean paused) {
    Java_ca_dnamobile_dbridgelauncher_GameActivity_nativeSetVulkanPresentationPaused(
            env,
            clazz,
            paused);
}

EXTERNAL_API JNIEXPORT jlong JNICALL
Java_org_lwjgl_vulkan_VK_getVulkanDriverHandle(ABI_COMPAT JNIEnv* env, ABI_COMPAT jclass thiz) {
    printf("EGLBridge: LWJGL-side Vulkan loader requested the Vulkan handle\n");
    return (jlong) maybe_load_vulkan();
}

EXTERNAL_API JNIEXPORT void JNICALL
Java_org_lwjgl_vulkan_VK_onVKFrame(ABI_COMPAT JNIEnv* env, ABI_COMPAT jclass clazz) {
    __atomic_store_n(&g_actualVulkanPresentationObserved, 1, __ATOMIC_RELEASE);
    /* Compatibility-only backend detection for older native-declared VK classes.
     * Never sleep here: Vulkan present mode owns pacing. The patched LWJGL helper
     * coalesces duplicate queue-present wrapper hits for launcher FPS reporting. */
}

EXTERNAL_API void droidbridgeSwapInterval(int interval) {
    const int requested = interval;
    droidbridgeRefreshForceVsyncFromEnvironment("glfwSwapInterval");

    /*
     * Legacy LWJGL2 on wrapped OpenGL backends such as Krypton/GL4ES provides a
     * trustworthy live glfwSwapInterval value. Do not let the launch-time
     * FORCE_VSYNC snapshot override an explicit in-game request of 0 for that
     * route. Keep the clamp for modern Minecraft, direct NativeGLFW/Freedreno,
     * and MobileGlues, where the compatibility safeguards still require it.
     */
    const int environment_forced =
            droidbridge_environ != NULL && droidbridge_environ->force_vsync ? 1 : 0;
    const int mobileglues_forced =
            env_enabled(getenv("DROIDBRIDGE_MOBILEGLUES_FORCE_VSYNC")) ? 1 : 0;
    const int mobileglues_renderer =
            env_enabled(getenv("DROIDBRIDGE_MOBILEGLUES_RENDERER")) ? 1 : 0;
    const int legacy_lwjgl2 =
            env_enabled(getenv("DROIDBRIDGE_LEGACY_LWJGL2")) ? 1 : 0;
    const int wrapped_opengl_controls_interval =
            legacy_lwjgl2
            && droidbridge_environ != NULL
            && droidbridge_environ->config_renderer == RENDERER_GL4ES
            && !db_native_glfw_enabled()
            && !mobileglues_renderer
            && !mobileglues_forced;
    const int forced = environment_forced && !wrapped_opengl_controls_interval;
    if (forced) interval = 1;
    int normalized = interval > 0 ? 1 : 0;
    const int previous_interval = g_glSwapIntervalEnabled;
    g_glSwapIntervalEnabled = normalized;
    if (previous_interval != normalized) {
        droidbridgeResetGlAdaptivePacing();
    }

    /*
     * Krypton/GL4ES can return success from eglSwapInterval(1) while Android's
     * presentation queue remains nonblocking. Legacy LWJGL2's live interval is
     * authoritative, so mirror it into the existing logical GLFW frame pacer.
     * This also makes an in-game OFF/ON toggle immediate without waiting for
     * options.txt to be flushed by old Minecraft versions.
     */
    if (wrapped_opengl_controls_interval) {
        setenv("DROIDBRIDGE_VSYNC_FRAME_PACING", normalized ? "1" : "0", 1);
        if (previous_interval != normalized) {
            g_framePaceLastNs = 0;
            g_framePaceCounter = 0;
        }
    }

    printf("EGLBridge: droidbridgeSwapInterval requested=%d normalized=%d forced=%d renderer=%d legacyWrappedPacing=%d framePacing=%s targetFps=%s\n",
           requested,
           normalized,
           forced,
           droidbridge_environ->config_renderer,
           wrapped_opengl_controls_interval,
           getenv("DROIDBRIDGE_VSYNC_FRAME_PACING") != NULL ? getenv("DROIDBRIDGE_VSYNC_FRAME_PACING") : "<null>",
           getenv("DROIDBRIDGE_VSYNC_TARGET_FPS") != NULL ? getenv("DROIDBRIDGE_VSYNC_TARGET_FPS") : "<null>");
    fflush(stdout);

    if (db_native_glfw_enabled()) {
        /*
         * Apply to NativeGLFW's actual Mesa EGLDisplay/EGLContext. Merely
         * changing ANativeWindow's interval or sleeping in the frame pacer
         * does not synchronize Mesa presentation and can still tear.
         */
        db_native_glfw_apply_swap_interval(normalized, "glfwSwapInterval");
    } else if (droidbridge_environ->config_renderer == RENDERER_VK_ZINK ||
               droidbridge_environ->config_renderer == RENDERER_GL4ES) {
        br_swap_interval(normalized);
    }

    if (droidbridge_environ->config_renderer == RENDERER_VIRGL) {
        virglSwapInterval(normalized);
    }

    /*
     * Android Surface/ANativeWindow has its own swap interval gate. Apply it
     * for every renderer, not only the pure Vulkan path, because plugin EGL
     * providers such as MobileGlues can bypass the older bridge table path.
     */
    if (droidbridge_environ->droidbridgeWindow != NULL) {
        setNativeWindowSwapInterval(droidbridge_environ->droidbridgeWindow, normalized);
    }
}

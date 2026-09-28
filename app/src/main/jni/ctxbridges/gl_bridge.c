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

#include <EGL/egl.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <string.h>
#include <malloc.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <stdarg.h>
#include <stdint.h>
#include <environ/environ.h>
#include "gl_bridge.h"
#include "egl_loader.h"

static const char* g_LogTag = "GLBridge";

#ifndef EGL_PLATFORM_ANDROID_KHR
#define EGL_PLATFORM_ANDROID_KHR 0x3141
#endif
#ifndef EGL_EXTENSIONS
#define EGL_EXTENSIONS 0x3055
#endif
#ifndef EGL_VERSION
#define EGL_VERSION 0x3054
#endif
#ifndef EGL_VENDOR
#define EGL_VENDOR 0x3053
#endif
#ifndef EGL_OPENGL_BIT
#define EGL_OPENGL_BIT 0x0008
#endif
#ifndef EGL_CONTEXT_MAJOR_VERSION_KHR
#define EGL_CONTEXT_MAJOR_VERSION_KHR 0x3098
#endif
#ifndef EGL_CONTEXT_MINOR_VERSION_KHR
#define EGL_CONTEXT_MINOR_VERSION_KHR 0x30FB
#endif
#ifndef EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR
#define EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR 0x30FD
#endif
#ifndef EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT_KHR
#define EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT_KHR 0x00000001
#endif
#ifndef EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT_KHR
#define EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT_KHR 0x00000002
#endif
#ifndef EGL_STENCIL_SIZE
#define EGL_STENCIL_SIZE 0x3026
#endif

#ifndef EGL_SWAP_BEHAVIOR
#define EGL_SWAP_BEHAVIOR 0x3093
#endif
#ifndef EGL_BUFFER_DESTROYED
#define EGL_BUFFER_DESTROYED 0x3095
#endif
#ifndef EGL_BUFFER_PRESERVED
#define EGL_BUFFER_PRESERVED 0x3094
#endif
#ifndef EGL_SWAP_BEHAVIOR_PRESERVED_BIT
#define EGL_SWAP_BEHAVIOR_PRESERVED_BIT 0x0400
#endif

static void gl_log(int prio, const char* fmt, ...) {
    char buffer[2048];
    memset(buffer, 0, sizeof(buffer));
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buffer, sizeof(buffer) - 1, fmt ? fmt : "", ap);
    va_end(ap);
    __android_log_print(prio, g_LogTag, "%s", buffer);
    fprintf(stderr, "%s: %s\n", g_LogTag, buffer);
    fflush(stderr);
    fprintf(stdout, "%s: %s\n", g_LogTag, buffer);
    fflush(stdout);
}

static const char* db_first_non_empty(const char* a, const char* b) {
    return (a != NULL && a[0] != '\0') ? a : b;
}

static bool env_enabled(const char* name) {
    const char* value = getenv(name);
    return value != NULL && value[0] != '\0' && strcmp(value, "0") != 0 && strcmp(value, "false") != 0;
}

static bool env_is(const char* name, const char* expected) {
    const char* value = getenv(name);
    return value != NULL && expected != NULL && strcmp(value, expected) == 0;
}

static bool should_force_opaque_rgbx8888_visual(void) {
    return env_enabled("DROIDBRIDGE_EGL_FORCE_RGBX8888")
           || env_enabled("DROIDBRIDGE_DIRECT_FREEDRENO_OPAQUE_RGBX8888");
}

static bool should_force_rgba8888_visual(void) {

    if (should_force_opaque_rgbx8888_visual()) {
        return false;
    }
    return env_enabled("DROIDBRIDGE_EGL_FORCE_RGBA8888");
}

static bool should_force_desktop_gl(void) {
    /*
     * Kopper's renderer id intentionally starts with "opengles3_" for
     * Pojav/FCL compatibility, but the API it exposes to Minecraft is desktop
     * OpenGL through Zink.  Detect the renderer id directly so an env reset or
     * launch-order change cannot accidentally request EGL_OPENGL_ES2_BIT.
     */
    if (env_is("DROIDBRIDGE_RENDERER", "opengles3_desktopgl_zink_kopper")
        || env_is("POJAV_RENDERER", "opengles3_desktopgl_zink_kopper")) {
        return true;
    }

    return env_enabled("DROIDBRIDGE_EGL_FORCE_DESKTOP_GL")
           || env_enabled("DROIDBRIDGE_MESA_DESKTOP_GL")
           || (env_enabled("DROIDBRIDGE_MESA")
               && (env_is("DROIDBRIDGE_MESA_MODE", "zink_turnip")
                   || env_is("DROIDBRIDGE_RENDERER_MESA_MODE", "zink_turnip")));
}

static bool should_request_legacy_compat_profile(void) {
    if (env_enabled("DROIDBRIDGE_EGL_FORCE_CORE_PROFILE")) {
        return false;
    }
    return env_enabled("DROIDBRIDGE_GL_LEGACY_COMPAT_PROFILE")
           || env_enabled("DROIDBRIDGE_MESA_LEGACY_COMPAT_PROFILE")
           || env_enabled("DROIDBRIDGE_EGL_FORCE_COMPAT_PROFILE");
}

static __thread gl_render_window_t* currentBundle;
/* Minecraft's live Video Settings watcher can call the bridge from the
 * Android/UI thread while the EGL context is current only on the render thread.
 * Keep the requested value atomic and commit it from gl_swap_buffers(). */
static volatile int gRequestedSwapInterval = 1;

static int gl_requested_swap_interval(void) {
    return __atomic_load_n(&gRequestedSwapInterval, __ATOMIC_ACQUIRE);
}

/*
 * Android Mesa/Freedreno is sensitive to presentation-state churn.  V8/V9
 * re-applied eglSwapInterval(1) from every makeCurrent call, and the logs show
 * hundreds of makeCurrent/swapInterval pairs before the game reaches the world.
 * Keep the requested interval, but only touch EGL/ANativeWindow when the interval
 * or target surface actually changes.
 */
static EGLContext gLastSwapIntervalContext = EGL_NO_CONTEXT;
static EGLSurface gLastSwapIntervalSurface = EGL_NO_SURFACE;
static int gLastAppliedSwapInterval = -1000;
static struct ANativeWindow* gLastSwapIntervalNativeWindow = NULL;
static int gLastNativeWindowSwapInterval = -1000;

static void gl_reset_swap_interval_cache(void) {
    gLastSwapIntervalContext = EGL_NO_CONTEXT;
    gLastSwapIntervalSurface = EGL_NO_SURFACE;
    gLastAppliedSwapInterval = -1000;
    gLastSwapIntervalNativeWindow = NULL;
    gLastNativeWindowSwapInterval = -1000;
}

void setNativeWindowSwapInterval(struct ANativeWindow* nativeWindow, int swapInterval);
static int normalize_swap_interval(int swapInterval);
static void gl_apply_swap_interval(gl_render_window_t* bundle, int swapInterval, const char* reason);
static EGLDisplay g_EglDisplay = EGL_NO_DISPLAY;

static bool should_use_safe_android_swaps(void) {
    if (env_enabled("DROIDBRIDGE_EGL_NO_FORCED_DESTROYED_SWAP")) {
        return false;
    }

    /*
     * Do not force EGL_BUFFER_DESTROYED on the direct Freedreno/KGSL direct KGSL Mesa path.
     * It was originally useful for some old Zink/SurfaceView experiments, but on
     * direct Freedreno it can make shader reloads and live resolution changes flash
     * or come back black because the back buffer contents are explicitly discarded.
     * Leave it opt-in for diagnostics only.
     */
    if (env_enabled("DROIDBRIDGE_MESA")
            && !env_enabled("DROIDBRIDGE_MESA_FORCE_DESTROYED_SWAP")) {
        return false;
    }

    return should_force_desktop_gl() || env_enabled("DROIDBRIDGE_MESA_SAFE_SWAPS");
}

static bool should_preserve_android_swaps(void) {
    return env_enabled("DROIDBRIDGE_MESA")
           && !env_enabled("DROIDBRIDGE_MESA_FORCE_DESTROYED_SWAP")
           && !env_enabled("DROIDBRIDGE_MESA_DISABLE_PRESERVED_SWAP");
}

static void force_destroyed_swap_behavior(EGLSurface surface) {
    if (surface == EGL_NO_SURFACE || surface == NULL) return;

    if (eglSurfaceAttrib_p != NULL) {
        EGLBoolean ok = eglSurfaceAttrib_p(g_EglDisplay, surface, EGL_SWAP_BEHAVIOR, EGL_BUFFER_DESTROYED);
        if (ok) {
            gl_log(ANDROID_LOG_INFO, "Mesa surface: forced EGL_SWAP_BEHAVIOR=EGL_BUFFER_DESTROYED");
        } else {
            gl_log(ANDROID_LOG_WARN, "Mesa surface: eglSurfaceAttrib(EGL_BUFFER_DESTROYED) failed: %04x", eglGetError_p());
        }
    } else {
        gl_log(ANDROID_LOG_WARN, "Mesa surface: eglSurfaceAttrib unavailable; cannot force destroyed swap behavior");
    }

    if (eglQuerySurface_p != NULL) {
        EGLint swapBehavior = -1;
        if (eglQuerySurface_p(g_EglDisplay, surface, EGL_SWAP_BEHAVIOR, &swapBehavior)) {
            gl_log(ANDROID_LOG_INFO, "Mesa surface: actual EGL_SWAP_BEHAVIOR=0x%x", swapBehavior);
        }
    }
}

static void force_preserved_swap_behavior(EGLSurface surface) {
    if (surface == EGL_NO_SURFACE || surface == NULL || eglSurfaceAttrib_p == NULL) return;

    EGLBoolean ok = eglSurfaceAttrib_p(
            g_EglDisplay, surface, EGL_SWAP_BEHAVIOR, EGL_BUFFER_PRESERVED);
    if (!ok) {
        gl_log(ANDROID_LOG_WARN,
               "Mesa surface: eglSurfaceAttrib(EGL_BUFFER_PRESERVED) failed: %04x",
               eglGetError_p());
        return;
    }

    EGLint swapBehavior = -1;
    if (eglQuerySurface_p != NULL
            && eglQuerySurface_p(g_EglDisplay, surface, EGL_SWAP_BEHAVIOR, &swapBehavior)) {
        gl_log(ANDROID_LOG_INFO,
               "Mesa surface: preserved back buffer enabled actual EGL_SWAP_BEHAVIOR=0x%x",
               swapBehavior);
    } else {
        gl_log(ANDROID_LOG_INFO, "Mesa surface: preserved back buffer enabled");
    }
}


static bool try_initialize_display(EGLDisplay display, const char* label) {
    if (display == EGL_NO_DISPLAY) {
        gl_log(ANDROID_LOG_ERROR, "%s returned EGL_NO_DISPLAY", label);
        return false;
    }

    EGLint major = 0;
    EGLint minor = 0;
    if (eglInitialize_p(display, &major, &minor) == EGL_TRUE) {
        g_EglDisplay = display;
        gl_log(ANDROID_LOG_INFO, "%s eglInitialize success display=%p version=%d.%d provider=%s",
               label, display, major, minor, droidbridge_egl_get_loaded_name());
        if (eglQueryString_p != NULL) {
            const char* vendor = eglQueryString_p(display, EGL_VENDOR);
            const char* version = eglQueryString_p(display, EGL_VERSION);
            const char* extensions = eglQueryString_p(display, EGL_EXTENSIONS);
            gl_log(ANDROID_LOG_INFO, "EGL vendor=%s version=%s",
                   vendor ? vendor : "<null>", version ? version : "<null>");
            if (extensions != NULL) {
                char shortExt[768];
                memset(shortExt, 0, sizeof(shortExt));
                strncpy(shortExt, extensions, sizeof(shortExt) - 1);
                gl_log(ANDROID_LOG_INFO, "EGL extensions=%s%s", shortExt,
                       strlen(extensions) >= sizeof(shortExt) ? "..." : "");
            }
        }
        return true;
    }

    EGLint err = eglGetError_p ? eglGetError_p() : 0;
    gl_log(ANDROID_LOG_ERROR, "%s eglInitialize failed: %04x provider=%s",
           label, err, droidbridge_egl_get_loaded_name());
    if (env_enabled("DROIDBRIDGE_MESA") && err == EGL_NOT_INITIALIZED) {
        gl_log(ANDROID_LOG_ERROR,
               "Mesa KGSL diagnostic: EGL_NOT_INITIALIZED. If Logcat shows /dev/dri or swrast, Mesa did not stay on the KGSL driver path. Check for DroidBridgeCutils property_get(mesa.loader.driver.override)=kgsl and app-first namespace path.");
    }
    return false;
}

bool gl_init() {
    const char* log_renderer = db_first_non_empty(getenv("POJAV_RENDERER"), getenv("DROIDBRIDGE_RENDERER"));
    const char* log_egl = db_first_non_empty(getenv("POJAVEXEC_EGL"), getenv("DROIDBRIDGE_EGL"));
    gl_log(ANDROID_LOG_INFO, "gl_init renderer=%s mesa=%s mesaMode=%s mesaDriver=%s POJAVEXEC_EGL=%s DROIDBRIDGE_MESA_EGL=%s",
           log_renderer ? log_renderer : "<null>",
           getenv("DROIDBRIDGE_MESA") ? getenv("DROIDBRIDGE_MESA") : "<null>",
           getenv("DROIDBRIDGE_RENDERER_MESA_MODE") ? getenv("DROIDBRIDGE_RENDERER_MESA_MODE") : "<null>",
           getenv("DROIDBRIDGE_MESA_DRIVER") ? getenv("DROIDBRIDGE_MESA_DRIVER") : "<null>",
           log_egl ? log_egl : "<null>",
           getenv("DROIDBRIDGE_MESA_EGL") ? getenv("DROIDBRIDGE_MESA_EGL") : "<null>");

    dlsym_EGL();

    const bool mesa = env_enabled("DROIDBRIDGE_MESA");
    const bool preferPlatformDisplay = mesa || env_enabled("DROIDBRIDGE_MESA_EGL_PLATFORM_DISPLAY");

    if (preferPlatformDisplay && eglGetPlatformDisplay_p != NULL) {
        if (try_initialize_display(
                eglGetPlatformDisplay_p(EGL_PLATFORM_ANDROID_KHR, EGL_DEFAULT_DISPLAY, NULL),
                "eglGetPlatformDisplay(EGL_PLATFORM_ANDROID_KHR,EGL_DEFAULT_DISPLAY)")) {
            return true;
        }
        if (try_initialize_display(
                eglGetPlatformDisplay_p(EGL_PLATFORM_ANDROID_KHR, NULL, NULL),
                "eglGetPlatformDisplay(EGL_PLATFORM_ANDROID_KHR,NULL)")) {
            return true;
        }
    } else if (preferPlatformDisplay) {
        gl_log(ANDROID_LOG_ERROR, "Mesa platform display requested but eglGetPlatformDisplay is unavailable from %s",
               droidbridge_egl_get_loaded_name());
    }

    if (eglGetDisplay_p != NULL) {
        if (try_initialize_display(eglGetDisplay_p(EGL_DEFAULT_DISPLAY),
                                   "eglGetDisplay(EGL_DEFAULT_DISPLAY)")) {
            return true;
        }
    }

    gl_log(ANDROID_LOG_ERROR, "all EGL display initialization attempts failed provider=%s",
           droidbridge_egl_get_loaded_name());
    return false;
}

gl_render_window_t* gl_get_current() {
    return currentBundle;
}

static void gl4esi_get_display_dimensions(int* width, int* height) {
    if (currentBundle == NULL) goto zero;
    EGLSurface surface = currentBundle->surface;
    EGLBoolean result_width = eglQuerySurface_p(g_EglDisplay, surface, EGL_WIDTH, width);
    EGLBoolean result_height = eglQuerySurface_p(g_EglDisplay, surface, EGL_HEIGHT, height);
    if (!result_width || !result_height) goto zero;
    return;

    zero:
    *width = 0;
    *height = 0;
}

gl_render_window_t* gl_init_context(gl_render_window_t *share) {
    if (g_EglDisplay == EGL_NO_DISPLAY) {
        gl_log(ANDROID_LOG_ERROR, "gl_init_context called without initialized EGL display");
        return NULL;
    }

    gl_render_window_t* bundle = malloc(sizeof(gl_render_window_t));
    if (bundle == NULL) {
        gl_log(ANDROID_LOG_ERROR, "gl_init_context malloc failed");
        return NULL;
    }
    memset(bundle, 0, sizeof(gl_render_window_t));

    const bool desktop_gl = should_force_desktop_gl();
    const bool force_opaque_rgbx8888 = should_force_opaque_rgbx8888_visual();
    const bool force_rgba8888 = should_force_rgba8888_visual();
    const EGLint requested_renderable_type = desktop_gl ? EGL_OPENGL_BIT : EGL_OPENGL_ES2_BIT;
    const EGLint desired_alpha_size = force_rgba8888 ? 8 : (force_opaque_rgbx8888 ? 0 : (desktop_gl ? 0 : 8));

    if (force_opaque_rgbx8888) {
        gl_log(ANDROID_LOG_INFO,
               "v69 explicit direct Freedreno RGBX visual debug path: requesting opaque RGBX_8888 alpha=0");
    } else if (force_rgba8888) {
        gl_log(ANDROID_LOG_WARN,
               "v69 explicit RGBA_8888 visual requested: alpha=8");
    }

    const EGLint egl_attributes_strict[] = {
            EGL_BLUE_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_RED_SIZE, 8,
            EGL_ALPHA_SIZE, desired_alpha_size,
            EGL_DEPTH_SIZE, 24,
            EGL_STENCIL_SIZE, desktop_gl ? 8 : 0,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
            EGL_RENDERABLE_TYPE, requested_renderable_type,
            EGL_NONE
    };
    const EGLint egl_attributes_window_strict[] = {
            EGL_BLUE_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_RED_SIZE, 8,
            EGL_ALPHA_SIZE, desired_alpha_size,
            EGL_DEPTH_SIZE, 24,
            EGL_STENCIL_SIZE, desktop_gl ? 8 : 0,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RENDERABLE_TYPE, requested_renderable_type,
            EGL_NONE
    };
    const EGLint egl_attributes_no_stencil[] = {
            EGL_BLUE_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_RED_SIZE, 8,
            EGL_ALPHA_SIZE, desired_alpha_size,
            EGL_DEPTH_SIZE, 24,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
            EGL_RENDERABLE_TYPE, requested_renderable_type,
            EGL_NONE
    };
    const EGLint egl_attributes_window_no_stencil[] = {
            EGL_BLUE_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_RED_SIZE, 8,
            EGL_ALPHA_SIZE, desired_alpha_size,
            EGL_DEPTH_SIZE, 24,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RENDERABLE_TYPE, requested_renderable_type,
            EGL_NONE
    };
    const EGLint egl_attributes_no_renderable[] = {
            EGL_BLUE_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_RED_SIZE, 8,
            EGL_ALPHA_SIZE, desired_alpha_size,
            EGL_DEPTH_SIZE, 24,
            EGL_STENCIL_SIZE, desktop_gl ? 8 : 0,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
            EGL_NONE
    };
    const EGLint egl_attributes_window_no_renderable[] = {
            EGL_BLUE_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_RED_SIZE, 8,
            EGL_ALPHA_SIZE, desired_alpha_size,
            EGL_DEPTH_SIZE, 24,
            EGL_STENCIL_SIZE, desktop_gl ? 8 : 0,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_NONE
    };
    const EGLint egl_attributes_min_window[] = {
            EGL_BLUE_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_RED_SIZE, 8,
            EGL_ALPHA_SIZE, desired_alpha_size,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_NONE
    };
    const EGLint egl_attributes_alpha_fallback[] = {
            EGL_BLUE_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_RED_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_DEPTH_SIZE, 24,
            EGL_STENCIL_SIZE, desktop_gl ? 8 : 0,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
            EGL_RENDERABLE_TYPE, requested_renderable_type,
            EGL_NONE
    };

    const EGLint* chosen_attributes = egl_attributes_strict;
    const char* chosen_attributes_name = "strict";
    EGLint num_configs = 0;
    EGLBoolean choose_ok = eglChooseConfig_p(g_EglDisplay, chosen_attributes, NULL, 0, &num_configs);

    if ((!choose_ok || num_configs == 0) && desktop_gl) {
        gl_log(ANDROID_LOG_WARN,
               "desktop GL strict EGL config unavailable chooseOk=%d count=%d error=%04x; retrying window-only strict config",
               choose_ok == EGL_TRUE ? 1 : 0, num_configs, eglGetError_p());
        chosen_attributes = egl_attributes_window_strict;
        chosen_attributes_name = "window-strict";
        num_configs = 0;
        choose_ok = eglChooseConfig_p(g_EglDisplay, chosen_attributes, NULL, 0, &num_configs);
    }

    if ((!choose_ok || num_configs == 0) && desktop_gl) {
        gl_log(ANDROID_LOG_WARN,
               "desktop GL window-only strict EGL config unavailable chooseOk=%d count=%d error=%04x; retrying RGBX/depth/no-stencil config",
               choose_ok == EGL_TRUE ? 1 : 0, num_configs, eglGetError_p());
        chosen_attributes = egl_attributes_no_stencil;
        chosen_attributes_name = "no-stencil";
        num_configs = 0;
        choose_ok = eglChooseConfig_p(g_EglDisplay, chosen_attributes, NULL, 0, &num_configs);
    }

    if ((!choose_ok || num_configs == 0) && desktop_gl) {
        gl_log(ANDROID_LOG_WARN,
               "desktop GL RGBX/depth/no-stencil EGL config unavailable chooseOk=%d count=%d error=%04x; retrying window-only RGBX/depth/no-stencil config",
               choose_ok == EGL_TRUE ? 1 : 0, num_configs, eglGetError_p());
        chosen_attributes = egl_attributes_window_no_stencil;
        chosen_attributes_name = "window-no-stencil";
        num_configs = 0;
        choose_ok = eglChooseConfig_p(g_EglDisplay, chosen_attributes, NULL, 0, &num_configs);
    }

    if ((!choose_ok || num_configs == 0) && desktop_gl) {
        gl_log(ANDROID_LOG_WARN,
               "desktop GL window-only RGBX/no-stencil EGL config unavailable chooseOk=%d count=%d error=%04x; retrying without EGL_RENDERABLE_TYPE",
               choose_ok == EGL_TRUE ? 1 : 0, num_configs, eglGetError_p());
        chosen_attributes = egl_attributes_no_renderable;
        chosen_attributes_name = "no-renderable";
        num_configs = 0;
        choose_ok = eglChooseConfig_p(g_EglDisplay, chosen_attributes, NULL, 0, &num_configs);
    }

    if ((!choose_ok || num_configs == 0) && desktop_gl) {
        gl_log(ANDROID_LOG_WARN,
               "desktop GL no-renderable EGL config unavailable chooseOk=%d count=%d error=%04x; retrying window-only/no-renderable",
               choose_ok == EGL_TRUE ? 1 : 0, num_configs, eglGetError_p());
        chosen_attributes = egl_attributes_window_no_renderable;
        chosen_attributes_name = "window-no-renderable";
        num_configs = 0;
        choose_ok = eglChooseConfig_p(g_EglDisplay, chosen_attributes, NULL, 0, &num_configs);
    }

    if ((!choose_ok || num_configs == 0) && desktop_gl) {
        gl_log(ANDROID_LOG_WARN,
               "desktop GL window/no-renderable EGL config unavailable chooseOk=%d count=%d error=%04x; retrying minimal window config",
               choose_ok == EGL_TRUE ? 1 : 0, num_configs, eglGetError_p());
        chosen_attributes = egl_attributes_min_window;
        chosen_attributes_name = "minimal-window";
        num_configs = 0;
        choose_ok = eglChooseConfig_p(g_EglDisplay, chosen_attributes, NULL, 0, &num_configs);
    }

    if ((!choose_ok || num_configs == 0) && desktop_gl) {
        gl_log(ANDROID_LOG_WARN,
               "desktop GL minimal RGBX window config unavailable chooseOk=%d count=%d error=%04x; last resort RGBA/depth/stencil fallback",
               choose_ok == EGL_TRUE ? 1 : 0, num_configs, eglGetError_p());
        chosen_attributes = egl_attributes_alpha_fallback;
        chosen_attributes_name = "alpha-fallback";
        num_configs = 0;
        choose_ok = eglChooseConfig_p(g_EglDisplay, chosen_attributes, NULL, 0, &num_configs);
    }

    if (choose_ok != EGL_TRUE || num_configs == 0) {
        gl_log(ANDROID_LOG_ERROR,
               "eglChooseConfig_p() found no usable config desktop_gl=%d requestedRenderable=0x%x mode=%s chooseOk=%d count=%d error=%04x",
               desktop_gl ? 1 : 0,
               requested_renderable_type,
               chosen_attributes_name,
               choose_ok == EGL_TRUE ? 1 : 0,
               num_configs,
               eglGetError_p());
        free(bundle);
        return NULL;
    }

    if (eglChooseConfig_p(g_EglDisplay, chosen_attributes, &bundle->config, 1, &num_configs) != EGL_TRUE || num_configs == 0) {
        gl_log(ANDROID_LOG_ERROR,
               "eglChooseConfig_p() failed while retrieving config desktop_gl=%d requestedRenderable=0x%x mode=%s error=%04x",
               desktop_gl ? 1 : 0,
               requested_renderable_type,
               chosen_attributes_name,
               eglGetError_p());
        free(bundle);
        return NULL;
    }

    EGLint actual_renderable_type = 0;
    EGLint actual_alpha_size = -1;
    EGLint actual_depth_size = -1;
    EGLint actual_stencil_size = -1;
    EGLint actual_surface_type = -1;
    eglGetConfigAttrib_p(g_EglDisplay, bundle->config, EGL_NATIVE_VISUAL_ID, &bundle->format);
    eglGetConfigAttrib_p(g_EglDisplay, bundle->config, EGL_RENDERABLE_TYPE, &actual_renderable_type);
    eglGetConfigAttrib_p(g_EglDisplay, bundle->config, EGL_ALPHA_SIZE, &actual_alpha_size);
    eglGetConfigAttrib_p(g_EglDisplay, bundle->config, EGL_DEPTH_SIZE, &actual_depth_size);
    eglGetConfigAttrib_p(g_EglDisplay, bundle->config, EGL_STENCIL_SIZE, &actual_stencil_size);
    eglGetConfigAttrib_p(g_EglDisplay, bundle->config, EGL_SURFACE_TYPE, &actual_surface_type);
    gl_log(ANDROID_LOG_INFO,
           "eglChooseConfig_p() matched configs=%d nativeVisual=0x%x desktop_gl=%d requestedRenderable=0x%x actualRenderable=0x%x alpha=%d depth=%d stencil=%d surfaceType=0x%x mode=%s",
           num_configs,
           bundle->format,
           desktop_gl ? 1 : 0,
           requested_renderable_type,
           actual_renderable_type,
           actual_alpha_size,
           actual_depth_size,
           actual_stencil_size,
           actual_surface_type,
           chosen_attributes_name);

    if (desktop_gl && (actual_surface_type & EGL_SWAP_BEHAVIOR_PRESERVED_BIT) != 0) {
        gl_log(ANDROID_LOG_INFO,
               "desktop GL config supports preserved swap behavior for stable Android presentation");
    }

    if (desktop_gl && actual_alpha_size > 0 && !force_rgba8888) {
        gl_log(ANDROID_LOG_WARN,
               "desktop GL selected an alpha window config alpha=%d nativeVisual=0x%x; this can cause black sky/cloud/chunk alpha artifacts on Android",
               actual_alpha_size,
               bundle->format);
    }

    if (force_opaque_rgbx8888) {
        gl_log(ANDROID_LOG_INFO,
               "v69 opaque RGBX visual selected nativeVisual=0x%x alpha=%d mode=%s",
               bundle->format, actual_alpha_size, chosen_attributes_name);
    } else if (force_rgba8888) {
        gl_log(ANDROID_LOG_INFO,
               "Freedreno/Adreno visual workaround selected nativeVisual=0x%x alpha=%d mode=%s",
               bundle->format, actual_alpha_size, chosen_attributes_name);
    }

    if (desktop_gl && (actual_renderable_type & EGL_OPENGL_BIT) == 0) {
        gl_log(ANDROID_LOG_WARN,
               "selected EGL config does not advertise EGL_OPENGL_BIT actualRenderable=0x%x; attempting EGL_OPENGL_API anyway",
               actual_renderable_type);
    }

    EGLBoolean bindResult;
    if (desktop_gl) {
        gl_log(ANDROID_LOG_INFO, "Binding EGL_OPENGL_API for DroidBridge Mesa desktop GL");
        bindResult = eglBindAPI_p(EGL_OPENGL_API);
    } else {
        gl_log(ANDROID_LOG_INFO, "Binding EGL_OPENGL_ES_API");
        bindResult = eglBindAPI_p(EGL_OPENGL_ES_API);
    }
    if (!bindResult) {
        gl_log(ANDROID_LOG_ERROR, "eglBindAPI failed: %04x", eglGetError_p());
        free(bundle);
        return NULL;
    }

    if (desktop_gl) {
        const EGLint ctx_46_core[] = {
                EGL_CONTEXT_MAJOR_VERSION_KHR, 4,
                EGL_CONTEXT_MINOR_VERSION_KHR, 6,
                EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT_KHR,
                EGL_NONE
        };
        const EGLint ctx_43_core[] = {
                EGL_CONTEXT_MAJOR_VERSION_KHR, 4,
                EGL_CONTEXT_MINOR_VERSION_KHR, 3,
                EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT_KHR,
                EGL_NONE
        };
        const EGLint ctx_33_core[] = {
                EGL_CONTEXT_MAJOR_VERSION_KHR, 3,
                EGL_CONTEXT_MINOR_VERSION_KHR, 3,
                EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT_KHR,
                EGL_NONE
        };
        const EGLint ctx_46_compat[] = {
                EGL_CONTEXT_MAJOR_VERSION_KHR, 4,
                EGL_CONTEXT_MINOR_VERSION_KHR, 6,
                EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR, EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT_KHR,
                EGL_NONE
        };
        const EGLint ctx_33_compat[] = {
                EGL_CONTEXT_MAJOR_VERSION_KHR, 3,
                EGL_CONTEXT_MINOR_VERSION_KHR, 3,
                EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR, EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT_KHR,
                EGL_NONE
        };
        const EGLint ctx_default[] = { EGL_NONE };

        const bool legacy_compat_profile = should_request_legacy_compat_profile();
        bundle->context = EGL_NO_CONTEXT;
        if (legacy_compat_profile) {
            gl_log(ANDROID_LOG_INFO,
                   "desktop GL legacy compatibility profile requested for LWJGL2-era Minecraft");
            bundle->context = eglCreateContext_p(
                    g_EglDisplay,
                    bundle->config,
                    share == NULL ? EGL_NO_CONTEXT : share->context,
                    ctx_46_compat
            );
            if (bundle->context == EGL_NO_CONTEXT) {
                gl_log(ANDROID_LOG_WARN, "desktop GL 4.6 compatibility context failed: %04x", eglGetError_p());
                bundle->context = eglCreateContext_p(
                        g_EglDisplay,
                        bundle->config,
                        share == NULL ? EGL_NO_CONTEXT : share->context,
                        ctx_33_compat
                );
            }
            if (bundle->context == EGL_NO_CONTEXT) {
                gl_log(ANDROID_LOG_WARN, "desktop GL 3.3 compatibility context failed: %04x", eglGetError_p());
                bundle->context = eglCreateContext_p(
                        g_EglDisplay,
                        bundle->config,
                        share == NULL ? EGL_NO_CONTEXT : share->context,
                        ctx_default
                );
            }
            if (bundle->context == EGL_NO_CONTEXT) {
                gl_log(ANDROID_LOG_WARN,
                       "desktop GL legacy compatibility path failed; falling back to normal Core-profile probe");
            }
        }

        /*
         * Freedreno KGSL path reports OpenGL 4.6 Core Profile.  DroidBridge
         * was requesting 4.6 Compatibility Profile and Mesa/Oculus then used a
         * different shader/FBO path that flickers on Adreno 830.  Request Core
         * first and only fall back to Compatibility if Mesa refuses it.
         */
        if (bundle->context == EGL_NO_CONTEXT) {
            bundle->context = eglCreateContext_p(
                    g_EglDisplay,
                    bundle->config,
                    share == NULL ? EGL_NO_CONTEXT : share->context,
                    ctx_46_core
            );
        }
        if (bundle->context == EGL_NO_CONTEXT) {
            gl_log(ANDROID_LOG_WARN, "desktop GL 4.6 core context failed: %04x", eglGetError_p());
            bundle->context = eglCreateContext_p(
                    g_EglDisplay,
                    bundle->config,
                    share == NULL ? EGL_NO_CONTEXT : share->context,
                    ctx_43_core
            );
        }
        if (bundle->context == EGL_NO_CONTEXT) {
            gl_log(ANDROID_LOG_WARN, "desktop GL 4.3 core context failed: %04x", eglGetError_p());
            bundle->context = eglCreateContext_p(
                    g_EglDisplay,
                    bundle->config,
                    share == NULL ? EGL_NO_CONTEXT : share->context,
                    ctx_33_core
            );
        }
        if (bundle->context == EGL_NO_CONTEXT) {
            gl_log(ANDROID_LOG_WARN, "desktop GL 3.3 core context failed: %04x", eglGetError_p());
            bundle->context = eglCreateContext_p(
                    g_EglDisplay,
                    bundle->config,
                    share == NULL ? EGL_NO_CONTEXT : share->context,
                    ctx_46_compat
            );
        }
        if (bundle->context == EGL_NO_CONTEXT) {
            gl_log(ANDROID_LOG_WARN, "desktop GL compatibility fallback failed: %04x", eglGetError_p());
            bundle->context = eglCreateContext_p(
                    g_EglDisplay,
                    bundle->config,
                    share == NULL ? EGL_NO_CONTEXT : share->context,
                    ctx_default
            );
        }
    } else {
        const char* libgl_es_env = getenv("LIBGL_ES");
        int libgl_es = libgl_es_env != NULL && libgl_es_env[0] != '\0'
                ? (int) strtol(libgl_es_env, NULL, 0)
                : 2;
        if (libgl_es < 1 || libgl_es > INT16_MAX) libgl_es = 2;
        const EGLint egl_context_attributes[] = {
                EGL_CONTEXT_CLIENT_VERSION, libgl_es,
                EGL_NONE
        };
        bundle->context = eglCreateContext_p(
                g_EglDisplay,
                bundle->config,
                share == NULL ? EGL_NO_CONTEXT : share->context,
                egl_context_attributes
        );
    }

    if (bundle->context == EGL_NO_CONTEXT) {
        gl_log(ANDROID_LOG_ERROR, "eglCreateContext_p() finished with error: %04x desktop_gl=%d",
               eglGetError_p(), desktop_gl ? 1 : 0);
        free(bundle);
        return NULL;
    }

    gl_log(ANDROID_LOG_INFO, "eglCreateContext_p() success context=%p desktop_gl=%d", bundle->context, desktop_gl ? 1 : 0);
    return bundle;
}

void gl_swap_surface(gl_render_window_t* bundle) {
    if (bundle->nativeSurface != NULL)
        ANativeWindow_release(bundle->nativeSurface);

    if (bundle->surface != NULL) {
        eglDestroySurface_p(g_EglDisplay, bundle->surface);
        gl_reset_swap_interval_cache();
    }

    if (bundle->newNativeSurface != NULL)
    {
        gl_log(ANDROID_LOG_INFO, "Switching to new native surface");
        bundle->nativeSurface = bundle->newNativeSurface;
        bundle->newNativeSurface = NULL;
        ANativeWindow_acquire(bundle->nativeSurface);
        const int setGeometryResult = ANativeWindow_setBuffersGeometry(bundle->nativeSurface, 0, 0, bundle->format);
        if (setGeometryResult != 0) {
            gl_log(ANDROID_LOG_WARN, "v69 ANativeWindow_setBuffersGeometry format=0x%x failed result=%d", bundle->format, setGeometryResult);
        } else if (should_force_opaque_rgbx8888_visual()) {
            gl_log(ANDROID_LOG_INFO, "v69 ANativeWindow format forced opaque RGBX/nativeVisual=0x%x", bundle->format);
        }
        const EGLint droidbridge_window_surface_attrs[] = {
                EGL_RENDER_BUFFER, EGL_BACK_BUFFER,
                EGL_NONE
        };
        bundle->surface = eglCreateWindowSurface_p(
                g_EglDisplay,
                bundle->config,
                bundle->nativeSurface,
                should_use_safe_android_swaps() ? droidbridge_window_surface_attrs : NULL);
        if (bundle->surface == EGL_NO_SURFACE) {
            gl_log(ANDROID_LOG_ERROR, "eglCreateWindowSurface_p() failed: %04x", eglGetError_p());
        } else {
            gl_log(ANDROID_LOG_INFO, "eglCreateWindowSurface_p() success surface=%p", bundle->surface);
            gl_apply_swap_interval(bundle, gl_requested_swap_interval(), "windowSurface");
            if (should_use_safe_android_swaps()) {
                force_destroyed_swap_behavior(bundle->surface);
            } else if (should_preserve_android_swaps()) {
                force_preserved_swap_behavior(bundle->surface);
            }
        }
    } else {
        gl_log(ANDROID_LOG_INFO, "No new native surface, switching to 1x1 pbuffer");
        bundle->nativeSurface = NULL;
        const EGLint pbuffer_attrs[] = {EGL_WIDTH, 1 , EGL_HEIGHT, 1, EGL_NONE};
        bundle->surface = eglCreatePbufferSurface_p(g_EglDisplay, bundle->config, pbuffer_attrs);
        if (bundle->surface == EGL_NO_SURFACE) {
            gl_log(ANDROID_LOG_ERROR, "eglCreatePbufferSurface_p() failed: %04x", eglGetError_p());
        } else {
            gl_log(ANDROID_LOG_INFO, "eglCreatePbufferSurface_p() success surface=%p", bundle->surface);
        }
    }
}

void gl_make_current(gl_render_window_t* bundle) {

    if (bundle == NULL)
    {
        if (currentBundle == NULL) {
            return;
        }
        if (eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT))
        {
            currentBundle = NULL;
        }
        return;
    }

    bool hasSetMainWindow = false;
    if (droidbridge_environ->mainWindowBundle == NULL)
    {
        droidbridge_environ->mainWindowBundle = (basic_render_window_t*)bundle;
        gl_log(ANDROID_LOG_INFO, "Main window bundle is now %p", droidbridge_environ->mainWindowBundle);
        droidbridge_environ->mainWindowBundle->newNativeSurface = droidbridge_environ->droidbridgeWindow;
        hasSetMainWindow = true;
    }

    if (bundle->surface == NULL)
        gl_swap_surface(bundle);

    /*
     * V13: the Freedreno KGSL path does not churn eglMakeCurrent every frame.
     * DroidBridge's proxy can call gl_make_current repeatedly even when the
     * same EGL context/surface is already current on this render thread. Mesa
     * accepts that, but on Android KGSL it still perturbs presentation/fence
     * state enough to show as world flicker. Treat the repeated same-context
     * call as a no-op unless the native window/surface actually changed.
     */
    if (currentBundle == bundle
            && bundle->state != STATE_RENDERER_NEW_WINDOW
            && bundle->newNativeSurface == NULL
            && bundle->surface != NULL
            && eglGetCurrentContext_p != NULL
            && eglGetCurrentContext_p() == bundle->context) {
        EGLSurface drawSurface = EGL_NO_SURFACE;
        if (eglGetCurrentSurface_p != NULL) {
            drawSurface = eglGetCurrentSurface_p(EGL_DRAW);
        }
        if (eglGetCurrentSurface_p == NULL || drawSurface == bundle->surface) {
            return;
        }
    }

    if (eglMakeCurrent_p(g_EglDisplay, bundle->surface, bundle->surface, bundle->context))
    {
        currentBundle = bundle;
        static __thread int loggedInitialMakeCurrent = 0;
        const char* renderer = db_first_non_empty(getenv("DROIDBRIDGE_RENDERER"), getenv("POJAV_RENDERER"));
        if (!loggedInitialMakeCurrent && renderer != NULL && strcmp(renderer, "freedreno_kgsl") == 0) {
            loggedInitialMakeCurrent = 1;
            EGLContext current = eglGetCurrentContext_p != NULL ? eglGetCurrentContext_p() : EGL_NO_CONTEXT;
            gl_log(ANDROID_LOG_INFO,
                   "eglMakeCurrent success v13 context=%p current=%p surface=%p renderer=%s",
                   bundle->context,
                   current,
                   bundle->surface,
                   renderer);
        }
        gl_apply_swap_interval(bundle, gl_requested_swap_interval(), "makeCurrent");
    } else {
        if (hasSetMainWindow)
        {
            droidbridge_environ->mainWindowBundle->newNativeSurface = NULL;
            gl_swap_surface((gl_render_window_t*)droidbridge_environ->mainWindowBundle);
            droidbridge_environ->mainWindowBundle = NULL;
        }
        gl_log(ANDROID_LOG_ERROR, "eglMakeCurrent returned with error: %04x", eglGetError_p());
    }

}

void gl_destroy_context(gl_render_window_t* bundle) {
    if (bundle == NULL) return;

    /*
     * GLFW window destruction must release the EGLWindowSurface that owns the
     * Android ANativeWindow. Vulkan cannot create a VkSurfaceKHR for a native
     * window while EGL still has a window surface attached to it
     * (VK_ERROR_NATIVE_WINDOW_IN_USE_KHR).
     *
     * Keep DroidBridge's process-level droidbridgeWindow reference alive. The
     * GL bundle owns its own ANativeWindow reference, acquired in
     * gl_swap_surface(), and only that reference is released here. A later
     * GLFW_NO_API window can therefore reuse the same Android window for Vulkan.
     */
    EGLContext currentContext = eglGetCurrentContext_p != NULL
            ? eglGetCurrentContext_p()
            : EGL_NO_CONTEXT;
    if (currentBundle == bundle || currentContext == bundle->context) {
        if (!eglMakeCurrent_p(
                g_EglDisplay,
                EGL_NO_SURFACE,
                EGL_NO_SURFACE,
                EGL_NO_CONTEXT)) {
            gl_log(ANDROID_LOG_WARN,
                   "eglMakeCurrent(EGL_NO_CONTEXT) during window destroy failed: %04x",
                   eglGetError_p());
        }
        if (currentBundle == bundle) currentBundle = NULL;
    }

    if (bundle->surface != EGL_NO_SURFACE) {
        EGLSurface oldSurface = bundle->surface;
        if (!eglDestroySurface_p(g_EglDisplay, oldSurface)) {
            gl_log(ANDROID_LOG_WARN,
                   "eglDestroySurface during window destroy failed surface=%p error=%04x",
                   oldSurface,
                   eglGetError_p());
        } else {
            gl_log(ANDROID_LOG_INFO,
                   "destroyed EGL window surface for GLFW/Vulkan handoff surface=%p",
                   oldSurface);
        }
        bundle->surface = EGL_NO_SURFACE;
        gl_reset_swap_interval_cache();
    }

    if (bundle->context != EGL_NO_CONTEXT) {
        EGLContext oldContext = bundle->context;
        if (!eglDestroyContext_p(g_EglDisplay, oldContext)) {
            gl_log(ANDROID_LOG_WARN,
                   "eglDestroyContext during window destroy failed context=%p error=%04x",
                   oldContext,
                   eglGetError_p());
        } else {
            gl_log(ANDROID_LOG_INFO,
                   "destroyed EGL context for GLFW/Vulkan handoff context=%p",
                   oldContext);
        }
        bundle->context = EGL_NO_CONTEXT;
    }

    if (bundle->nativeSurface != NULL) {
        ANativeWindow_release(bundle->nativeSurface);
        bundle->nativeSurface = NULL;
    }
    /* newNativeSurface is borrowed until gl_swap_surface() acquires it. */
    bundle->newNativeSurface = NULL;

    if (droidbridge_environ != NULL
            && droidbridge_environ->mainWindowBundle == (basic_render_window_t*) bundle) {
        droidbridge_environ->mainWindowBundle = NULL;
    }

    free(bundle);
}

void gl_swap_buffers() {
    if (currentBundle->state == STATE_RENDERER_NEW_WINDOW)
    {
        eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        gl_swap_surface(currentBundle);
        if (eglMakeCurrent_p(g_EglDisplay, currentBundle->surface, currentBundle->surface, currentBundle->context)) {
            gl_apply_swap_interval(currentBundle, gl_requested_swap_interval(), "newWindowMakeCurrent");
        }
        currentBundle->state = STATE_RENDERER_ALIVE;
    }

    /* A live off/on toggle may have been requested from a thread with no current
     * EGL context. Re-apply from the real render thread before the next swap.
     * The context/surface/interval cache makes this a no-op after success. */
    gl_apply_swap_interval(
            currentBundle,
            gl_requested_swap_interval(),
            "swapBuffersPending");

    if (currentBundle->surface != NULL)
        if (!eglSwapBuffers_p(g_EglDisplay, currentBundle->surface) && eglGetError_p() == EGL_BAD_SURFACE)
        {
            eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            currentBundle->newNativeSurface = NULL;
            gl_swap_surface(currentBundle);
            if (eglMakeCurrent_p(g_EglDisplay, currentBundle->surface, currentBundle->surface, currentBundle->context)) {
                gl_apply_swap_interval(currentBundle, gl_requested_swap_interval(), "badSurfaceMakeCurrent");
            }
            gl_log(ANDROID_LOG_INFO, "The window has died, awaiting window change");
        }

}


static int normalize_swap_interval(int swapInterval) {
    /*
     * V16: respect Minecraft's VSync toggle for Native Mesa/Freedreno.
     * Previous builds forced 0 -> 1 whenever FORCE_VSYNC was true, which made
     * "VSync off" still behave like a refresh-locked swap chain and did not
     * match Freedreno KGSL practical behavior. Stability now comes from the fixed
     * FPS cap / DroidBridgeFramePacer, not from forcing swapInterval(1).
     */
    return swapInterval > 0 ? 1 : 0;
}

static void gl_apply_swap_interval(gl_render_window_t* bundle, int swapInterval, const char* reason) {
    int interval = normalize_swap_interval(swapInterval);

    /*
     * Always keep Android's native window interval in sync, but do not spam it.
     * Re-setting this from every makeCurrent call can churn SurfaceFlinger / KGSL
     * presentation state and shows up visually as flicker even though VSync is
     * technically enabled.
     */
    if (bundle != NULL && bundle->nativeSurface != NULL) {
        if (gLastSwapIntervalNativeWindow != bundle->nativeSurface
                || gLastNativeWindowSwapInterval != interval) {
            setNativeWindowSwapInterval(bundle->nativeSurface, interval);
            gLastSwapIntervalNativeWindow = bundle->nativeSurface;
            gLastNativeWindowSwapInterval = interval;
        }
    }

    if (eglSwapInterval_p != NULL && g_EglDisplay != EGL_NO_DISPLAY) {
        EGLContext current = eglGetCurrentContext_p != NULL ? eglGetCurrentContext_p() : EGL_NO_CONTEXT;
        EGLSurface surface = bundle != NULL ? bundle->surface : EGL_NO_SURFACE;
        if (current == EGL_NO_CONTEXT) {
            gl_log(ANDROID_LOG_INFO,
                   "eglSwapInterval(%d) reason=%s deferred; no current context",
                   interval, reason ? reason : "unknown");
            return;
        }

        if (gLastSwapIntervalContext == current
                && gLastSwapIntervalSurface == surface
                && gLastAppliedSwapInterval == interval) {
            return;
        }

        EGLBoolean ok = eglSwapInterval_p(g_EglDisplay, interval);
        if (ok == EGL_TRUE) {
            gLastSwapIntervalContext = current;
            gLastSwapIntervalSurface = surface;
            gLastAppliedSwapInterval = interval;
        }
        gl_log(ok == EGL_TRUE ? ANDROID_LOG_INFO : ANDROID_LOG_WARN,
               "eglSwapInterval(%d) reason=%s ok=%d err=%04x",
               interval, reason ? reason : "unknown", ok == EGL_TRUE ? 1 : 0, ok == EGL_TRUE ? 0 : eglGetError_p());
    }
}

void gl_setup_window() {
    if (droidbridge_environ->mainWindowBundle != NULL)
    {
        gl_log(ANDROID_LOG_INFO, "Main window bundle is not NULL, changing state");
        droidbridge_environ->mainWindowBundle->state = STATE_RENDERER_NEW_WINDOW;
        droidbridge_environ->mainWindowBundle->newNativeSurface = droidbridge_environ->droidbridgeWindow;
    }
}

void gl_swap_interval(int swapInterval) {
    const int requested = swapInterval;
    const int normalized = normalize_swap_interval(swapInterval);
    __atomic_store_n(&gRequestedSwapInterval, normalized, __ATOMIC_RELEASE);
    if (requested != normalized) {
        gl_log(ANDROID_LOG_INFO,
               "Freedreno Mesa parity swap interval requested=%d effective=%d forceVsync=%d",
               requested,
               normalized,
               0 /* forceVsync ignored by V16 */);
    }
    gl_apply_swap_interval(currentBundle, normalized, "glfwSwapInterval");
}

JNIEXPORT void JNICALL
Java_org_lwjgl_opengl_DroidRendererInit_nativeInitGl4esInternals(JNIEnv *env, jclass clazz,
                                                            jobject function_provider) {
    gl_log(ANDROID_LOG_INFO, "GL4ES internals initializing...");
    jclass funcProviderClass = (*env)->GetObjectClass(env, function_provider);
    jmethodID method_getFunctionAddress = (*env)->GetMethodID(env, funcProviderClass, "getFunctionAddress", "(Ljava/lang/CharSequence;)J");
#define GETSYM(N) ((*env)->CallLongMethod(env, function_provider, method_getFunctionAddress, (*env)->NewStringUTF(env, N)));

    void (*set_getmainfbsize)(void (*new_getMainFBSize)(int* width, int* height)) = (void*)GETSYM("set_getmainfbsize");
    if(set_getmainfbsize != NULL) {
        gl_log(ANDROID_LOG_INFO, "GL4ES internals initialized dimension callback");
        set_getmainfbsize(gl4esi_get_display_dimensions);
    }

#undef GETSYM
}


JNIEXPORT void JNICALL
Java_org_lwjgl_opengl_PojavRendererInit_nativeInitGl4esInternals(JNIEnv *env, jclass clazz,
                                                            jobject function_provider) {
    Java_org_lwjgl_opengl_DroidRendererInit_nativeInitGl4esInternals(env, clazz, function_provider);
}

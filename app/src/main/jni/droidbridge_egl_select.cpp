/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * App-local EGL selector shim for DroidBridge Mesa.
 *
 * This library is intentionally built with LOCAL_MODULE := EGL so the APK
 * contains libEGL.so. When DROIDBRIDGE_MESA_EGL points at libEGL_mesa.so, all
 * EGL calls are forwarded to Mesa. Otherwise this shim forwards to Android's
 * system EGL so non-Mesa renderers keep working.
 */

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#ifndef EGL_PLATFORM_ANGLE_TYPE_ANGLE
#define EGL_PLATFORM_ANGLE_TYPE_ANGLE 0x3203
#endif

#ifndef EGL_OPENGL_ES_API
#define EGL_OPENGL_ES_API 0x30A0
#endif

#ifndef EGL_OPENGL_API
#define EGL_OPENGL_API 0x30A2
#endif

#ifndef EGL_CONTEXT_CLIENT_VERSION
#define EGL_CONTEXT_CLIENT_VERSION 0x3098
#endif

#ifndef EGL_CONTEXT_MAJOR_VERSION
#define EGL_CONTEXT_MAJOR_VERSION 0x3098
#endif

#ifndef EGL_CONTEXT_MINOR_VERSION
#define EGL_CONTEXT_MINOR_VERSION 0x30FB
#endif

#ifndef EGL_CONTEXT_OPENGL_PROFILE_MASK
#define EGL_CONTEXT_OPENGL_PROFILE_MASK 0x30FD
#endif

#ifndef EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT
#define EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT 0x00000001
#endif

#ifndef EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT
#define EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT 0x00000002
#endif

#ifndef EGL_RENDERABLE_TYPE
#define EGL_RENDERABLE_TYPE 0x3040
#endif

#ifndef EGL_OPENGL_BIT
#define EGL_OPENGL_BIT 0x0008
#endif

static const char* TAG = "DroidBridgeEGLShim";
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static void* g_real_egl = nullptr;
static int g_tried = 0;


/*
 * Mesa current-context recovery.
 *
 * Some Android GLFW bridge paths create/make the EGL context before LWJGL's
 * EGL provider initializes on the Java render thread. When LWJGL later asks
 * eglGetCurrentContext(), Mesa can report EGL_NO_CONTEXT even though the
 * DroidBridge GLFW window has just created a valid context/surface pair.
 * Cache the last successful Mesa context/surface and, only when the Java side
 * explicitly enables DROIDBRIDGE_EGL_RECOVER_CURRENT, make that context current
 * on the thread that is querying capabilities.
 */
static pthread_mutex_t g_current_lock = PTHREAD_MUTEX_INITIALIZER;
static EGLDisplay g_last_display = EGL_NO_DISPLAY;
static EGLSurface g_last_draw_surface = EGL_NO_SURFACE;
static EGLSurface g_last_read_surface = EGL_NO_SURFACE;
static EGLContext g_last_context = EGL_NO_CONTEXT;
/*
 * Do not use __thread/thread_local here.
 * Some Android/NDK combinations emit references to __emutls_get_address for
 * TLS variables in C++ objects. When libdroidbridge_runtime is loaded by System.loadLibrary
 * in the game process, that unresolved compiler-runtime symbol aborts launch
 * before JavaRuntimeBootstrap can run. These values are only used as a fallback
 * cache for the current EGL context, so process-global storage is safer here.
 */
static EGLDisplay t_current_display = EGL_NO_DISPLAY;
static EGLSurface t_current_draw_surface = EGL_NO_SURFACE;
static EGLSurface t_current_read_surface = EGL_NO_SURFACE;
static EGLContext t_current_context = EGL_NO_CONTEXT;


static void log_info(const char* message, const char* value) {
    if (value == nullptr) value = "";
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s%s", message, value);
    fprintf(stderr, "DroidBridgeEGLShim: %s%s\n", message, value);
}

static void log_error(const char* message, const char* value) {
    if (value == nullptr) value = "";
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s%s", message, value);
    fprintf(stderr, "DroidBridgeEGLShim ERROR: %s%s\n", message, value);
}

static bool is_non_empty(const char* value) {
    return value != nullptr && value[0] != '\0';
}

static bool file_exists(const char* path) {
    return is_non_empty(path) && access(path, R_OK) == 0;
}


static bool env_truthy(const char* key) {
    const char* value = getenv(key);
    if (!is_non_empty(value)) return false;
    return strcmp(value, "0") != 0
            && strcasecmp(value, "false") != 0
            && strcasecmp(value, "no") != 0
            && strcasecmp(value, "off") != 0;
}

static bool force_desktop_gl() {
    /* DroidBridge's working KGSL path keeps the EGL bridge on the GLES-style Android
     * window/context creation path. This shim only rewrites to EGL_OPENGL_API
     * for explicit experiments. Normal DroidBridge Mesa/Freedreno should leave
     * this disabled so GLFW can create its window.
     */
    return env_truthy("DROIDBRIDGE_EGL_FORCE_DESKTOP_GL");
}

static const EGLint* rewrite_config_attribs_for_desktop_gl(const EGLint* attrib_list, EGLint* out, int cap) {
    if (!force_desktop_gl()) return attrib_list;

    int n = 0;
    bool saw_renderable = false;
    if (attrib_list != nullptr) {
        for (int i = 0; attrib_list[i] != EGL_NONE && n + 2 < cap; i += 2) {
            EGLint key = attrib_list[i];
            EGLint value = attrib_list[i + 1];
            if (key == EGL_RENDERABLE_TYPE) {
                value |= EGL_OPENGL_BIT;
                saw_renderable = true;
            }
            out[n++] = key;
            out[n++] = value;
        }
    }

    if (!saw_renderable && n + 2 < cap) {
        out[n++] = EGL_RENDERABLE_TYPE;
        out[n++] = EGL_OPENGL_BIT;
    }
    out[n++] = EGL_NONE;
    return out;
}

static const EGLint* rewrite_context_attribs_for_desktop_gl(
        const EGLint* attrib_list,
        EGLint* out,
        int cap,
        int major,
        int minor,
        bool with_profile) {
    int n = 0;
    if (attrib_list != nullptr) {
        for (int i = 0; attrib_list[i] != EGL_NONE && n + 2 < cap; i += 2) {
            EGLint key = attrib_list[i];
            EGLint value = attrib_list[i + 1];
            if (key == EGL_CONTEXT_CLIENT_VERSION
                    || key == EGL_CONTEXT_MAJOR_VERSION
                    || key == EGL_CONTEXT_MINOR_VERSION
                    || key == EGL_CONTEXT_OPENGL_PROFILE_MASK) {
                continue;
            }
            out[n++] = key;
            out[n++] = value;
        }
    }

    if (n + 2 < cap) {
        out[n++] = EGL_CONTEXT_MAJOR_VERSION;
        out[n++] = static_cast<EGLint>(major);
    }
    if (n + 2 < cap) {
        out[n++] = EGL_CONTEXT_MINOR_VERSION;
        out[n++] = static_cast<EGLint>(minor);
    }
    if (with_profile && n + 2 < cap) {
        out[n++] = EGL_CONTEXT_OPENGL_PROFILE_MASK;
        out[n++] = EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT;
    }
    out[n++] = EGL_NONE;
    return out;
}

static void* try_dlopen(const char* path) {
    if (!is_non_empty(path)) return nullptr;
    void* handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (handle != nullptr) {
        log_info("using EGL provider: ", path);
        return handle;
    }
    {
        const char* err = dlerror();
        __android_log_print(ANDROID_LOG_INFO, TAG, "EGL provider failed: %s error=%s", path, err);
        fprintf(stderr, "DroidBridgeEGLShim: EGL provider failed: %s error=%s\n", path, err ? err : "");
    }
    return nullptr;
}

static void* load_real_egl_locked() {
    if (g_real_egl != nullptr || g_tried) return g_real_egl;
    g_tried = 1;

    const char* mesa_flag = getenv("DROIDBRIDGE_MESA");
    const char* mesa_driver = getenv("DROIDBRIDGE_MESA_DRIVER");
    const char* mesa_egl = getenv("DROIDBRIDGE_MESA_EGL");

    if ((is_non_empty(mesa_flag) || is_non_empty(mesa_driver)) && file_exists(mesa_egl)) {
        g_real_egl = try_dlopen(mesa_egl);
        if (g_real_egl != nullptr) return g_real_egl;
    }

    /* Mesa may already be in the same native directory under this name. */
    if (is_non_empty(mesa_flag) || is_non_empty(mesa_driver)) {
        g_real_egl = try_dlopen("libEGL_mesa.so");
        if (g_real_egl != nullptr) return g_real_egl;

        /* For direct Mesa Freedreno, do not fall back to Android's system EGL.
         * Falling back creates a GLES/current-context mismatch and makes the
         * later crash misleading. Fail loudly so the real missing Mesa dependency
         * is visible in latestlog.txt.
         */
        log_error("direct Mesa requested but libEGL_mesa.so could not be loaded; refusing system EGL fallback", "");
        return nullptr;
    }

#if defined(__aarch64__) || defined(__x86_64__)
    g_real_egl = try_dlopen("/system/lib64/libEGL.so");
    if (g_real_egl != nullptr) return g_real_egl;
    g_real_egl = try_dlopen("/vendor/lib64/libEGL.so");
    if (g_real_egl != nullptr) return g_real_egl;
#else
    g_real_egl = try_dlopen("/system/lib/libEGL.so");
    if (g_real_egl != nullptr) return g_real_egl;
    g_real_egl = try_dlopen("/vendor/lib/libEGL.so");
    if (g_real_egl != nullptr) return g_real_egl;
#endif

    log_error("no EGL provider could be loaded", "");
    return nullptr;
}

static void* load_real_egl() {
    pthread_mutex_lock(&g_lock);
    void* handle = load_real_egl_locked();
    pthread_mutex_unlock(&g_lock);
    return handle;
}

static void* real_sym(const char* name) {
    void* handle = load_real_egl();
    if (handle == nullptr) return nullptr;
    void* symbol = dlsym(handle, name);
    if (symbol == nullptr && strcmp(name, "eglGetProcAddress") != 0) {
        typedef __eglMustCastToProperFunctionPointerType (*GetProcAddressFn)(const char*);
        GetProcAddressFn get_proc = reinterpret_cast<GetProcAddressFn>(dlsym(handle, "eglGetProcAddress"));
        if (get_proc != nullptr) symbol = reinterpret_cast<void*>(get_proc(name));
    }
    return symbol;
}

template <typename Fn>
static Fn egl_fn(const char* name) {
    return reinterpret_cast<Fn>(real_sym(name));
}


static bool recover_current_enabled() {
    return env_truthy("DROIDBRIDGE_EGL_RECOVER_CURRENT");
}

static void cache_current_locked(EGLDisplay dpy, EGLSurface draw, EGLSurface read, EGLContext ctx) {
    t_current_display = dpy;
    t_current_draw_surface = draw;
    t_current_read_surface = read;
    t_current_context = ctx;

    if (dpy != EGL_NO_DISPLAY && ctx != EGL_NO_CONTEXT) {
        g_last_display = dpy;
        g_last_context = ctx;
        if (draw != EGL_NO_SURFACE) g_last_draw_surface = draw;
        if (read != EGL_NO_SURFACE) g_last_read_surface = read;
    }
}

static void cache_context(EGLDisplay dpy, EGLContext ctx) {
    if (dpy == EGL_NO_DISPLAY || ctx == EGL_NO_CONTEXT) return;
    pthread_mutex_lock(&g_current_lock);
    g_last_display = dpy;
    g_last_context = ctx;
    pthread_mutex_unlock(&g_current_lock);
}

static void cache_surface(EGLDisplay dpy, EGLSurface surface) {
    if (dpy == EGL_NO_DISPLAY || surface == EGL_NO_SURFACE) return;
    pthread_mutex_lock(&g_current_lock);
    g_last_display = dpy;
    g_last_draw_surface = surface;
    g_last_read_surface = surface;
    pthread_mutex_unlock(&g_current_lock);
}

static bool try_recover_current_context(const char* reason) {
    if (!recover_current_enabled()) return false;

    EGLDisplay dpy;
    EGLSurface draw;
    EGLSurface read;
    EGLContext ctx;

    pthread_mutex_lock(&g_current_lock);
    dpy = g_last_display;
    draw = g_last_draw_surface;
    read = g_last_read_surface;
    ctx = g_last_context;
    pthread_mutex_unlock(&g_current_lock);

    if (dpy == EGL_NO_DISPLAY || ctx == EGL_NO_CONTEXT || draw == EGL_NO_SURFACE) {
        log_error("cannot recover current EGL context; missing cached display/surface/context from ", reason);
        return false;
    }
    if (read == EGL_NO_SURFACE) read = draw;

    typedef EGLBoolean (*MakeCurrentFn)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
    MakeCurrentFn make_current = egl_fn<MakeCurrentFn>("eglMakeCurrent");
    if (make_current == nullptr) {
        log_error("cannot recover current EGL context; eglMakeCurrent unavailable from ", reason);
        return false;
    }

    if (force_desktop_gl()) {
        typedef EGLBoolean (*BindAPIFn)(EGLenum);
        BindAPIFn bind_api = egl_fn<BindAPIFn>("eglBindAPI");
        if (bind_api != nullptr) bind_api(EGL_OPENGL_API);
    }

    EGLBoolean ok = make_current(dpy, draw, read, ctx);
    if (ok == EGL_TRUE) {
        pthread_mutex_lock(&g_current_lock);
        cache_current_locked(dpy, draw, read, ctx);
        pthread_mutex_unlock(&g_current_lock);
        log_info("recovered current EGL context for ", reason);
        return true;
    }

    log_error("failed to recover current EGL context for ", reason);
    return false;
}

extern "C" {

EGLint eglGetError(void) {
    typedef EGLint (*Fn)(void);
    Fn fn = egl_fn<Fn>("eglGetError");
    return fn ? fn() : EGL_NOT_INITIALIZED;
}

EGLDisplay eglGetDisplay(EGLNativeDisplayType display_id) {
    typedef EGLDisplay (*Fn)(EGLNativeDisplayType);
    Fn fn = egl_fn<Fn>("eglGetDisplay");
    return fn ? fn(display_id) : EGL_NO_DISPLAY;
}

EGLBoolean eglInitialize(EGLDisplay dpy, EGLint* major, EGLint* minor) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLint*, EGLint*);
    Fn fn = egl_fn<Fn>("eglInitialize");
    return fn ? fn(dpy, major, minor) : EGL_FALSE;
}

EGLBoolean eglTerminate(EGLDisplay dpy) {
    typedef EGLBoolean (*Fn)(EGLDisplay);
    Fn fn = egl_fn<Fn>("eglTerminate");
    return fn ? fn(dpy) : EGL_FALSE;
}

const char* eglQueryString(EGLDisplay dpy, EGLint name) {
    typedef const char* (*Fn)(EGLDisplay, EGLint);
    Fn fn = egl_fn<Fn>("eglQueryString");
    return fn ? fn(dpy, name) : nullptr;
}

EGLBoolean eglGetConfigs(EGLDisplay dpy, EGLConfig* configs, EGLint config_size, EGLint* num_config) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLConfig*, EGLint, EGLint*);
    Fn fn = egl_fn<Fn>("eglGetConfigs");
    return fn ? fn(dpy, configs, config_size, num_config) : EGL_FALSE;
}

EGLBoolean eglChooseConfig(EGLDisplay dpy, const EGLint* attrib_list, EGLConfig* configs, EGLint config_size, EGLint* num_config) {
    typedef EGLBoolean (*Fn)(EGLDisplay, const EGLint*, EGLConfig*, EGLint, EGLint*);
    Fn fn = egl_fn<Fn>("eglChooseConfig");
    if (!fn) return EGL_FALSE;

    EGLint rewritten[96];
    const EGLint* use_attribs = rewrite_config_attribs_for_desktop_gl(attrib_list, rewritten, 96);
    if (use_attribs != attrib_list) {
        log_info("forcing EGL config renderable type to include desktop OpenGL", "");
    }
    return fn(dpy, use_attribs, configs, config_size, num_config);
}

EGLBoolean eglGetConfigAttrib(EGLDisplay dpy, EGLConfig config, EGLint attribute, EGLint* value) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLConfig, EGLint, EGLint*);
    Fn fn = egl_fn<Fn>("eglGetConfigAttrib");
    return fn ? fn(dpy, config, attribute, value) : EGL_FALSE;
}

EGLSurface eglCreateWindowSurface(EGLDisplay dpy, EGLConfig config, EGLNativeWindowType win, const EGLint* attrib_list) {
    typedef EGLSurface (*Fn)(EGLDisplay, EGLConfig, EGLNativeWindowType, const EGLint*);
    Fn fn = egl_fn<Fn>("eglCreateWindowSurface");
    EGLSurface surface = fn ? fn(dpy, config, win, attrib_list) : EGL_NO_SURFACE;
    if (surface != EGL_NO_SURFACE) {
        cache_surface(dpy, surface);
        log_info("cached EGL window surface", "");
    }
    return surface;
}

EGLSurface eglCreatePbufferSurface(EGLDisplay dpy, EGLConfig config, const EGLint* attrib_list) {
    typedef EGLSurface (*Fn)(EGLDisplay, EGLConfig, const EGLint*);
    Fn fn = egl_fn<Fn>("eglCreatePbufferSurface");
    EGLSurface surface = fn ? fn(dpy, config, attrib_list) : EGL_NO_SURFACE;
    if (surface != EGL_NO_SURFACE) {
        cache_surface(dpy, surface);
        log_info("cached EGL pbuffer surface", "");
    }
    return surface;
}

EGLSurface eglCreatePixmapSurface(EGLDisplay dpy, EGLConfig config, EGLNativePixmapType pixmap, const EGLint* attrib_list) {
    typedef EGLSurface (*Fn)(EGLDisplay, EGLConfig, EGLNativePixmapType, const EGLint*);
    Fn fn = egl_fn<Fn>("eglCreatePixmapSurface");
    return fn ? fn(dpy, config, pixmap, attrib_list) : EGL_NO_SURFACE;
}

EGLBoolean eglDestroySurface(EGLDisplay dpy, EGLSurface surface) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLSurface);
    Fn fn = egl_fn<Fn>("eglDestroySurface");
    return fn ? fn(dpy, surface) : EGL_FALSE;
}

EGLBoolean eglQuerySurface(EGLDisplay dpy, EGLSurface surface, EGLint attribute, EGLint* value) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLSurface, EGLint, EGLint*);
    Fn fn = egl_fn<Fn>("eglQuerySurface");
    return fn ? fn(dpy, surface, attribute, value) : EGL_FALSE;
}

EGLBoolean eglBindAPI(EGLenum api) {
    typedef EGLBoolean (*Fn)(EGLenum);
    Fn fn = egl_fn<Fn>("eglBindAPI");
    if (!fn) return EGL_FALSE;
    if (force_desktop_gl() && api == EGL_OPENGL_ES_API) {
        log_info("rewriting eglBindAPI(EGL_OPENGL_ES_API) -> EGL_OPENGL_API for Mesa Freedreno", "");
        api = EGL_OPENGL_API;
    }
    return fn(api);
}

EGLenum eglQueryAPI(void) {
    typedef EGLenum (*Fn)(void);
    Fn fn = egl_fn<Fn>("eglQueryAPI");
    return fn ? fn() : EGL_NONE;
}

EGLBoolean eglWaitClient(void) {
    typedef EGLBoolean (*Fn)(void);
    Fn fn = egl_fn<Fn>("eglWaitClient");
    return fn ? fn() : EGL_FALSE;
}

EGLBoolean eglReleaseThread(void) {
    typedef EGLBoolean (*Fn)(void);
    Fn fn = egl_fn<Fn>("eglReleaseThread");
    return fn ? fn() : EGL_FALSE;
}

EGLSurface eglCreatePbufferFromClientBuffer(EGLDisplay dpy, EGLenum buftype, EGLClientBuffer buffer, EGLConfig config, const EGLint* attrib_list) {
    typedef EGLSurface (*Fn)(EGLDisplay, EGLenum, EGLClientBuffer, EGLConfig, const EGLint*);
    Fn fn = egl_fn<Fn>("eglCreatePbufferFromClientBuffer");
    return fn ? fn(dpy, buftype, buffer, config, attrib_list) : EGL_NO_SURFACE;
}

EGLBoolean eglSurfaceAttrib(EGLDisplay dpy, EGLSurface surface, EGLint attribute, EGLint value) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLSurface, EGLint, EGLint);
    Fn fn = egl_fn<Fn>("eglSurfaceAttrib");
    return fn ? fn(dpy, surface, attribute, value) : EGL_FALSE;
}

EGLBoolean eglBindTexImage(EGLDisplay dpy, EGLSurface surface, EGLint buffer) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLSurface, EGLint);
    Fn fn = egl_fn<Fn>("eglBindTexImage");
    return fn ? fn(dpy, surface, buffer) : EGL_FALSE;
}

EGLBoolean eglReleaseTexImage(EGLDisplay dpy, EGLSurface surface, EGLint buffer) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLSurface, EGLint);
    Fn fn = egl_fn<Fn>("eglReleaseTexImage");
    return fn ? fn(dpy, surface, buffer) : EGL_FALSE;
}

EGLBoolean eglSwapInterval(EGLDisplay dpy, EGLint interval) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLint);
    Fn fn = egl_fn<Fn>("eglSwapInterval");
    return fn ? fn(dpy, interval) : EGL_FALSE;
}

EGLContext eglCreateContext(EGLDisplay dpy, EGLConfig config, EGLContext share_context, const EGLint* attrib_list) {
    typedef EGLContext (*Fn)(EGLDisplay, EGLConfig, EGLContext, const EGLint*);
    Fn fn = egl_fn<Fn>("eglCreateContext");
    if (!fn) return EGL_NO_CONTEXT;

    if (force_desktop_gl()) {
        /* libdroidbridge_runtime normally asks Android EGL for an OpenGL ES context.
         * Mesa Freedreno needs a desktop OpenGL context, otherwise LWJGL sees
         * a GLES current context and then reports missing OpenGL entry points.
         */
        eglBindAPI(EGL_OPENGL_API);

        EGLint attrs46[96];
        EGLContext ctx = fn(dpy, config, share_context,
                rewrite_context_attribs_for_desktop_gl(attrib_list, attrs46, 96, 4, 6, true));
        if (ctx != EGL_NO_CONTEXT) {
            cache_context(dpy, ctx);
            log_info("created forced desktop OpenGL 4.6 compatibility context", "");
            return ctx;
        }

        EGLint attrs40[96];
        ctx = fn(dpy, config, share_context,
                rewrite_context_attribs_for_desktop_gl(attrib_list, attrs40, 96, 4, 0, true));
        if (ctx != EGL_NO_CONTEXT) {
            cache_context(dpy, ctx);
            log_info("created forced desktop OpenGL 4.0 compatibility context", "");
            return ctx;
        }

        EGLint attrs31[96];
        ctx = fn(dpy, config, share_context,
                rewrite_context_attribs_for_desktop_gl(attrib_list, attrs31, 96, 3, 1, false));
        if (ctx != EGL_NO_CONTEXT) {
            cache_context(dpy, ctx);
            log_info("created forced desktop OpenGL 3.1 context", "");
            return ctx;
        }

        EGLint attrsDefault[] = { EGL_NONE };
        ctx = fn(dpy, config, share_context, attrsDefault);
        if (ctx != EGL_NO_CONTEXT) {
            cache_context(dpy, ctx);
            log_info("created forced desktop OpenGL default context", "");
            return ctx;
        }

        log_error("failed to create forced desktop OpenGL context; falling back to caller attribs", "");
    }

    EGLContext ctx = fn(dpy, config, share_context, attrib_list);
    if (ctx != EGL_NO_CONTEXT) {
        cache_context(dpy, ctx);
        log_info("cached caller EGL context", "");
    } else {
        log_error("caller EGL context creation failed", "");
    }
    return ctx;
}

EGLBoolean eglDestroyContext(EGLDisplay dpy, EGLContext ctx) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLContext);
    Fn fn = egl_fn<Fn>("eglDestroyContext");
    return fn ? fn(dpy, ctx) : EGL_FALSE;
}

EGLBoolean eglMakeCurrent(EGLDisplay dpy, EGLSurface draw, EGLSurface read, EGLContext ctx) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
    Fn fn = egl_fn<Fn>("eglMakeCurrent");
    if (!fn) return EGL_FALSE;
    if (force_desktop_gl()) eglBindAPI(EGL_OPENGL_API);
    EGLBoolean ok = fn(dpy, draw, read, ctx);
    if (ok == EGL_TRUE) {
        pthread_mutex_lock(&g_current_lock);
        cache_current_locked(dpy, draw, read, ctx);
        pthread_mutex_unlock(&g_current_lock);
        log_info("eglMakeCurrent cached current context", "");
    } else {
        log_error("eglMakeCurrent failed", "");
    }
    return ok;
}

EGLContext eglGetCurrentContext(void) {
    typedef EGLContext (*Fn)(void);
    Fn fn = egl_fn<Fn>("eglGetCurrentContext");
    EGLContext ctx = fn ? fn() : EGL_NO_CONTEXT;
    if (ctx == EGL_NO_CONTEXT && try_recover_current_context("eglGetCurrentContext")) {
        ctx = fn ? fn() : t_current_context;
    }
    if (ctx == EGL_NO_CONTEXT && recover_current_enabled() && t_current_context != EGL_NO_CONTEXT) {
        return t_current_context;
    }
    return ctx;
}

EGLSurface eglGetCurrentSurface(EGLint readdraw) {
    typedef EGLSurface (*Fn)(EGLint);
    Fn fn = egl_fn<Fn>("eglGetCurrentSurface");
    EGLSurface surface = fn ? fn(readdraw) : EGL_NO_SURFACE;
    if (surface == EGL_NO_SURFACE && try_recover_current_context("eglGetCurrentSurface")) {
        surface = fn ? fn(readdraw) : EGL_NO_SURFACE;
    }
    if (surface == EGL_NO_SURFACE && recover_current_enabled()) {
        return readdraw == EGL_READ ? t_current_read_surface : t_current_draw_surface;
    }
    return surface;
}

EGLDisplay eglGetCurrentDisplay(void) {
    typedef EGLDisplay (*Fn)(void);
    Fn fn = egl_fn<Fn>("eglGetCurrentDisplay");
    EGLDisplay dpy = fn ? fn() : EGL_NO_DISPLAY;
    if (dpy == EGL_NO_DISPLAY && try_recover_current_context("eglGetCurrentDisplay")) {
        dpy = fn ? fn() : EGL_NO_DISPLAY;
    }
    if (dpy == EGL_NO_DISPLAY && recover_current_enabled() && t_current_display != EGL_NO_DISPLAY) {
        return t_current_display;
    }
    return dpy;
}

EGLBoolean eglQueryContext(EGLDisplay dpy, EGLContext ctx, EGLint attribute, EGLint* value) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLContext, EGLint, EGLint*);
    Fn fn = egl_fn<Fn>("eglQueryContext");
    return fn ? fn(dpy, ctx, attribute, value) : EGL_FALSE;
}

EGLBoolean eglWaitGL(void) {
    typedef EGLBoolean (*Fn)(void);
    Fn fn = egl_fn<Fn>("eglWaitGL");
    return fn ? fn() : EGL_FALSE;
}

EGLBoolean eglWaitNative(EGLint engine) {
    typedef EGLBoolean (*Fn)(EGLint);
    Fn fn = egl_fn<Fn>("eglWaitNative");
    return fn ? fn(engine) : EGL_FALSE;
}

EGLBoolean eglSwapBuffers(EGLDisplay dpy, EGLSurface surface) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLSurface);
    Fn fn = egl_fn<Fn>("eglSwapBuffers");
    return fn ? fn(dpy, surface) : EGL_FALSE;
}

EGLBoolean eglCopyBuffers(EGLDisplay dpy, EGLSurface surface, EGLNativePixmapType target) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLSurface, EGLNativePixmapType);
    Fn fn = egl_fn<Fn>("eglCopyBuffers");
    return fn ? fn(dpy, surface, target) : EGL_FALSE;
}

__eglMustCastToProperFunctionPointerType eglGetProcAddress(const char* procname) {
    typedef __eglMustCastToProperFunctionPointerType (*Fn)(const char*);
    Fn fn = egl_fn<Fn>("eglGetProcAddress");
    if (fn != nullptr) {
        __eglMustCastToProperFunctionPointerType ptr = fn(procname);
        if (ptr != nullptr) return ptr;
    }
    return reinterpret_cast<__eglMustCastToProperFunctionPointerType>(real_sym(procname));
}

/* EGL 1.5 entry points. Some loaders dlsym these before falling back to extensions. */
EGLDisplay eglGetPlatformDisplay(EGLenum platform, void* native_display, const EGLAttrib* attrib_list) {
    typedef EGLDisplay (*Fn)(EGLenum, void*, const EGLAttrib*);
    Fn fn = egl_fn<Fn>("eglGetPlatformDisplay");
    return fn ? fn(platform, native_display, attrib_list) : EGL_NO_DISPLAY;
}

EGLSurface eglCreatePlatformWindowSurface(EGLDisplay dpy, EGLConfig config, void* native_window, const EGLAttrib* attrib_list) {
    typedef EGLSurface (*Fn)(EGLDisplay, EGLConfig, void*, const EGLAttrib*);
    Fn fn = egl_fn<Fn>("eglCreatePlatformWindowSurface");
    return fn ? fn(dpy, config, native_window, attrib_list) : EGL_NO_SURFACE;
}

EGLSurface eglCreatePlatformPixmapSurface(EGLDisplay dpy, EGLConfig config, void* native_pixmap, const EGLAttrib* attrib_list) {
    typedef EGLSurface (*Fn)(EGLDisplay, EGLConfig, void*, const EGLAttrib*);
    Fn fn = egl_fn<Fn>("eglCreatePlatformPixmapSurface");
    return fn ? fn(dpy, config, native_pixmap, attrib_list) : EGL_NO_SURFACE;
}

EGLSync eglCreateSync(EGLDisplay dpy, EGLenum type, const EGLAttrib* attrib_list) {
    typedef EGLSync (*Fn)(EGLDisplay, EGLenum, const EGLAttrib*);
    Fn fn = egl_fn<Fn>("eglCreateSync");
    return fn ? fn(dpy, type, attrib_list) : EGL_NO_SYNC;
}

EGLBoolean eglDestroySync(EGLDisplay dpy, EGLSync sync) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLSync);
    Fn fn = egl_fn<Fn>("eglDestroySync");
    return fn ? fn(dpy, sync) : EGL_FALSE;
}

EGLint eglClientWaitSync(EGLDisplay dpy, EGLSync sync, EGLint flags, EGLTime timeout) {
    typedef EGLint (*Fn)(EGLDisplay, EGLSync, EGLint, EGLTime);
    Fn fn = egl_fn<Fn>("eglClientWaitSync");
    return fn ? fn(dpy, sync, flags, timeout) : EGL_FALSE;
}

EGLBoolean eglGetSyncAttrib(EGLDisplay dpy, EGLSync sync, EGLint attribute, EGLAttrib* value) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLSync, EGLint, EGLAttrib*);
    Fn fn = egl_fn<Fn>("eglGetSyncAttrib");
    return fn ? fn(dpy, sync, attribute, value) : EGL_FALSE;
}

EGLImage eglCreateImage(EGLDisplay dpy, EGLContext ctx, EGLenum target, EGLClientBuffer buffer, const EGLAttrib* attrib_list) {
    typedef EGLImage (*Fn)(EGLDisplay, EGLContext, EGLenum, EGLClientBuffer, const EGLAttrib*);
    Fn fn = egl_fn<Fn>("eglCreateImage");
    return fn ? fn(dpy, ctx, target, buffer, attrib_list) : EGL_NO_IMAGE;
}

EGLBoolean eglDestroyImage(EGLDisplay dpy, EGLImage image) {
    typedef EGLBoolean (*Fn)(EGLDisplay, EGLImage);
    Fn fn = egl_fn<Fn>("eglDestroyImage");
    return fn ? fn(dpy, image) : EGL_FALSE;
}

} // extern "C"

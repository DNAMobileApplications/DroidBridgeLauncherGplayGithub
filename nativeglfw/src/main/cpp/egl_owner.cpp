/*
 * Copyright (c) 2026 DNA Mobile Applications.
 */
#include "egl_owner.h"

#include <EGL/eglext.h>
#include <android/log.h>
#include <dlfcn.h>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <sstream>
#include <utility>

#define DB_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "DroidBridgeNativeGLFW", __VA_ARGS__)
#define DB_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "DroidBridgeNativeGLFW", __VA_ARGS__)
#define DB_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "DroidBridgeNativeGLFW", __VA_ARGS__)

#ifndef EGL_PLATFORM_ANDROID_KHR
#define EGL_PLATFORM_ANDROID_KHR 0x3141
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
#ifndef EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT_KHR
#define EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT_KHR 0x00000002
#endif

static constexpr unsigned int GL_COLOR_BUFFER_BIT_DB = 0x00004000u;
static constexpr int WINDOW_FORMAT_RGBA_8888_DB = 1;

EglOwner::EglOwner() = default;

EglOwner::~EglOwner() {
    destroy();
}

void EglOwner::configure(std::string eglLibraryPathOrName,
                         std::string driverOverride,
                         bool desktopGl,
                         int major,
                         int minor) {
    eglLibraryPathOrName_ = std::move(eglLibraryPathOrName);
    driverOverride_ = std::move(driverOverride);
    desktopGl_ = desktopGl;
    requestedMajor_ = major;
    requestedMinor_ = minor;

    if (!driverOverride_.empty()) {
        setenv("MESA_LOADER_DRIVER_OVERRIDE", driverOverride_.c_str(), 1);
        DB_LOGI("Configured MESA_LOADER_DRIVER_OVERRIDE=%s", driverOverride_.c_str());
    }

    DB_LOGI("Configured egl=%s desktopGl=%d requested=%d.%d",
            eglLibraryPathOrName_.empty() ? "<system>" : eglLibraryPathOrName_.c_str(),
            desktopGl_ ? 1 : 0,
            requestedMajor_,
            requestedMinor_);
}

bool EglOwner::attachSurface(ANativeWindow* window, int width, int height) {
    if (window == nullptr) {
        setError("attachSurface called with null ANativeWindow");
        return false;
    }

    width_ = width > 0 ? width : 1;
    height_ = height > 0 ? height : 1;

    detachSurface();

    window_ = window;
    ANativeWindow_acquire(window_);
    ANativeWindow_setBuffersGeometry(window_, width_, height_, WINDOW_FORMAT_RGBA_8888_DB);

    if (!loadEgl()) { printf("DroidBridgeNativeGLFW: attachSurface failed at loadEgl error=%s\n", lastError_.c_str()); fflush(stdout); return false; }
    if (!initializeDisplay()) { printf("DroidBridgeNativeGLFW: attachSurface failed at initializeDisplay error=%s\n", lastError_.c_str()); fflush(stdout); return false; }
    if (!chooseConfig()) { printf("DroidBridgeNativeGLFW: attachSurface failed at chooseConfig error=%s\n", lastError_.c_str()); fflush(stdout); return false; }
    if (!createContext()) { printf("DroidBridgeNativeGLFW: attachSurface failed at createContext error=%s\n", lastError_.c_str()); fflush(stdout); return false; }
    if (!createWindowSurface(window_)) { printf("DroidBridgeNativeGLFW: attachSurface failed at createWindowSurface error=%s\n", lastError_.c_str()); fflush(stdout); return false; }
    if (!makeCurrent()) { printf("DroidBridgeNativeGLFW: attachSurface failed at makeCurrent error=%s\n", lastError_.c_str()); fflush(stdout); return false; }
    if (!loadGlProcs()) { printf("DroidBridgeNativeGLFW: attachSurface failed at loadGlProcs error=%s\n", lastError_.c_str()); fflush(stdout); return false; }

    DB_LOGI("Surface attached display=%p config=%p context=%p surface=%p size=%dx%d",
            display_, config_, context_, surface_, width_, height_);
    printf("DroidBridgeNativeGLFW: Surface attached display=%p config=%p context=%p surface=%p size=%dx%d eglHandle=%p\n",
           display_, config_, context_, surface_, width_, height_, eglHandle_);
    fflush(stdout);
    return true;
}

void EglOwner::resize(int width, int height) {
    width_ = width > 0 ? width : 1;
    height_ = height > 0 ? height : 1;

    if (window_ != nullptr) {
        ANativeWindow_setBuffersGeometry(window_, width_, height_, WINDOW_FORMAT_RGBA_8888_DB);
    }

    bool current = makeCurrent();
    if (glViewport_ != nullptr && current) {
        glViewport_(0, 0, width_, height_);
    }

    printf("DroidBridgeNativeGLFW: resized existing EGL window surface size=%dx%d current=%d error=%s\n",
           width_, height_, current ? 1 : 0, lastError_.c_str());
    fflush(stdout);
}

void EglOwner::detachSurface() {
    if (display_ != EGL_NO_DISPLAY && surface_ != EGL_NO_SURFACE) {
        if (eglMakeCurrent_) {
            eglMakeCurrent_(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
        if (eglDestroySurface_) {
            eglDestroySurface_(display_, surface_);
        }
        DB_LOGI("Destroyed EGL window surface");
    }
    surface_ = EGL_NO_SURFACE;

    if (window_ != nullptr) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
}

void EglOwner::destroy() {
    detachSurface();

    if (display_ != EGL_NO_DISPLAY && context_ != EGL_NO_CONTEXT && eglDestroyContext_) {
        eglDestroyContext_(display_, context_);
        DB_LOGI("Destroyed EGL context");
    }
    context_ = EGL_NO_CONTEXT;

    if (display_ != EGL_NO_DISPLAY && eglTerminate_) {
        eglTerminate_(display_);
        DB_LOGI("Terminated EGL display");
    }
    display_ = EGL_NO_DISPLAY;
    config_ = nullptr;

    unloadEgl();
}

bool EglOwner::makeCurrent() {
    if (display_ == EGL_NO_DISPLAY || surface_ == EGL_NO_SURFACE || context_ == EGL_NO_CONTEXT) {
        setError("makeCurrent before display/surface/context ready");
        return false;
    }
    if (!eglMakeCurrent_(display_, surface_, surface_, context_)) {
        setError(eglErrorString("eglMakeCurrent failed"));
        return false;
    }
    printf("DroidBridgeNativeGLFW: makeCurrent ok display=%p surface=%p context=%p eglHandle=%p\n", display_, surface_, context_, eglHandle_);
    fflush(stdout);
    return true;
}

bool EglOwner::swapBuffers() {
    if (display_ == EGL_NO_DISPLAY || surface_ == EGL_NO_SURFACE || context_ == EGL_NO_CONTEXT) {
        setError("swapBuffers before display/surface/context ready");
        return false;
    }
    // Some Android/Mesa paths silently present nothing unless the context is current
    // on the same thread immediately before eglSwapBuffers. Keep this here even
    // though libdroidbridge_runtime also calls makeCurrent.
    if (!eglMakeCurrent_(display_, surface_, surface_, context_)) {
        setError(eglErrorString("eglMakeCurrent before eglSwapBuffers failed"));
        return false;
    }
    if (!eglSwapBuffers_(display_, surface_)) {
        setError(eglErrorString("eglSwapBuffers failed"));
        return false;
    }
    return true;
}

bool EglOwner::clearTest(float red, float green, float blue, float alpha) {
    if (!makeCurrent()) return false;
    if (!loadGlProcs()) return false;
    if (glViewport_) glViewport_(0, 0, width_, height_);
    glClearColor_(red, green, blue, alpha);
    glClear_(GL_COLOR_BUFFER_BIT_DB);
    return swapBuffers();
}

std::string EglOwner::glString(int name) {
    if (!makeCurrent()) return {};
    if (!loadGlProcs()) return {};
    const unsigned char* text = glGetString_(static_cast<unsigned int>(name));
    return text ? reinterpret_cast<const char*>(text) : std::string();
}

std::string EglOwner::lastError() const {
    return lastError_;
}

void* EglOwner::eglHandle() const {
    return eglHandle_;
}

bool EglOwner::loadEgl() {
    if (eglHandle_ != nullptr) return true;

    const char* requested = eglLibraryPathOrName_.empty() ? "libEGL.so" : eglLibraryPathOrName_.c_str();
    eglHandle_ = dlopen(requested, RTLD_NOW | RTLD_GLOBAL);
    ownsEglHandle_ = eglHandle_ != nullptr;

    if (eglHandle_ == nullptr) {
        std::ostringstream oss;
        oss << "dlopen(" << requested << ") failed: " << (dlerror() ? dlerror() : "unknown");
        setError(oss.str());
        return false;
    }

    eglGetDisplay_ = reinterpret_cast<PfnEglGetDisplay>(resolve("eglGetDisplay"));
    eglGetPlatformDisplay_ = reinterpret_cast<PfnEglGetPlatformDisplay>(resolve("eglGetPlatformDisplay", false));
    eglInitialize_ = reinterpret_cast<PfnEglInitialize>(resolve("eglInitialize"));
    eglTerminate_ = reinterpret_cast<PfnEglTerminate>(resolve("eglTerminate"));
    eglChooseConfig_ = reinterpret_cast<PfnEglChooseConfig>(resolve("eglChooseConfig"));
    eglGetConfigAttrib_ = reinterpret_cast<PfnEglGetConfigAttrib>(resolve("eglGetConfigAttrib", false));
    eglBindAPI_ = reinterpret_cast<PfnEglBindApi>(resolve("eglBindAPI"));
    eglCreateContext_ = reinterpret_cast<PfnEglCreateContext>(resolve("eglCreateContext"));
    eglDestroyContext_ = reinterpret_cast<PfnEglDestroyContext>(resolve("eglDestroyContext"));
    eglCreateWindowSurface_ = reinterpret_cast<PfnEglCreateWindowSurface>(resolve("eglCreateWindowSurface"));
    eglDestroySurface_ = reinterpret_cast<PfnEglDestroySurface>(resolve("eglDestroySurface"));
    eglMakeCurrent_ = reinterpret_cast<PfnEglMakeCurrent>(resolve("eglMakeCurrent"));
    eglSwapBuffers_ = reinterpret_cast<PfnEglSwapBuffers>(resolve("eglSwapBuffers"));
    eglGetError_ = reinterpret_cast<PfnEglGetError>(resolve("eglGetError"));
    eglQueryString_ = reinterpret_cast<PfnEglQueryString>(resolve("eglQueryString", false));
    eglGetProcAddress_ = reinterpret_cast<PfnEglGetProcAddress>(resolve("eglGetProcAddress"));

    if (!eglGetDisplay_ || !eglInitialize_ || !eglChooseConfig_ || !eglBindAPI_ ||
        !eglCreateContext_ || !eglCreateWindowSurface_ || !eglMakeCurrent_ ||
        !eglSwapBuffers_ || !eglGetError_ || !eglGetProcAddress_) {
        setError("Missing required EGL symbol(s)");
        return false;
    }

    DB_LOGI("Loaded EGL provider %s handle=%p", requested, eglHandle_);
    return true;
}

void EglOwner::unloadEgl() {
    glClearColor_ = nullptr;
    glClear_ = nullptr;
    glViewport_ = nullptr;
    glGetString_ = nullptr;

    if (eglHandle_ != nullptr && ownsEglHandle_) {
        dlclose(eglHandle_);
    }
    eglHandle_ = nullptr;
    ownsEglHandle_ = false;

    eglGetDisplay_ = nullptr;
    eglGetPlatformDisplay_ = nullptr;
    eglInitialize_ = nullptr;
    eglTerminate_ = nullptr;
    eglChooseConfig_ = nullptr;
    eglGetConfigAttrib_ = nullptr;
    eglBindAPI_ = nullptr;
    eglCreateContext_ = nullptr;
    eglDestroyContext_ = nullptr;
    eglCreateWindowSurface_ = nullptr;
    eglDestroySurface_ = nullptr;
    eglMakeCurrent_ = nullptr;
    eglSwapBuffers_ = nullptr;
    eglGetError_ = nullptr;
    eglQueryString_ = nullptr;
    eglGetProcAddress_ = nullptr;
}

bool EglOwner::initializeDisplay() {
    if (display_ != EGL_NO_DISPLAY) return true;

    if (eglGetPlatformDisplay_) {
        display_ = eglGetPlatformDisplay_(EGL_PLATFORM_ANDROID_KHR, EGL_DEFAULT_DISPLAY, nullptr);
    }
    if (display_ == EGL_NO_DISPLAY) {
        display_ = eglGetDisplay_(EGL_DEFAULT_DISPLAY);
    }
    if (display_ == EGL_NO_DISPLAY) {
        setError(eglErrorString("eglGetDisplay failed"));
        return false;
    }

    EGLint major = 0;
    EGLint minor = 0;
    if (!eglInitialize_(display_, &major, &minor)) {
        setError(eglErrorString("eglInitialize failed"));
        return false;
    }

    const char* vendor = eglQueryString_ ? eglQueryString_(display_, EGL_VENDOR) : nullptr;
    const char* version = eglQueryString_ ? eglQueryString_(display_, EGL_VERSION) : nullptr;
    DB_LOGI("EGL initialized version=%d.%d vendor=%s stringVersion=%s",
            major, minor, vendor ? vendor : "<unknown>", version ? version : "<unknown>");
    return true;
}

bool EglOwner::chooseConfig() {
    if (config_ != nullptr) return true;

    const EGLint renderable = desktopGl_ ? EGL_OPENGL_BIT : EGL_OPENGL_ES3_BIT;
    const EGLint attrs[] = {
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RENDERABLE_TYPE, renderable,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_DEPTH_SIZE, 24,
            EGL_STENCIL_SIZE, 8,
            EGL_NONE
    };

    EGLint count = 0;
    if (!eglChooseConfig_(display_, attrs, &config_, 1, &count) || count <= 0 || config_ == nullptr) {
        const EGLint fallbackAttrs[] = {
                EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                EGL_RENDERABLE_TYPE, renderable,
                EGL_RED_SIZE, 8,
                EGL_GREEN_SIZE, 8,
                EGL_BLUE_SIZE, 8,
                EGL_DEPTH_SIZE, 16,
                EGL_NONE
        };
        if (!eglChooseConfig_(display_, fallbackAttrs, &config_, 1, &count) || count <= 0 || config_ == nullptr) {
            setError(eglErrorString("eglChooseConfig failed"));
            return false;
        }
    }

    EGLint nativeVisual = 0;
    EGLint actualRenderable = 0;
    if (eglGetConfigAttrib_) {
        eglGetConfigAttrib_(display_, config_, EGL_NATIVE_VISUAL_ID, &nativeVisual);
        eglGetConfigAttrib_(display_, config_, EGL_RENDERABLE_TYPE, &actualRenderable);
    }
    DB_LOGI("EGL config selected config=%p nativeVisual=0x%x renderable=0x%x requested=0x%x",
            config_, nativeVisual, actualRenderable, renderable);
    return true;
}

bool EglOwner::createContext() {
    if (context_ != EGL_NO_CONTEXT) return true;

    const EGLenum api = desktopGl_ ? EGL_OPENGL_API : EGL_OPENGL_ES_API;
    if (!eglBindAPI_(api)) {
        setError(eglErrorString(desktopGl_ ? "eglBindAPI(EGL_OPENGL_API) failed" : "eglBindAPI(EGL_OPENGL_ES_API) failed"));
        return false;
    }

    if (desktopGl_) {
        EGLint requestedMajor = requestedMajor_ > 0 ? requestedMajor_ : 0;
        EGLint requestedMinor = requestedMinor_ >= 0 ? requestedMinor_ : 0;

        EGLint explicitAttrs[] = {
                EGL_CONTEXT_MAJOR_VERSION_KHR, requestedMajor,
                EGL_CONTEXT_MINOR_VERSION_KHR, requestedMinor,
                EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR, EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT_KHR,
                EGL_NONE
        };
        EGLint attrs46[] = {
                EGL_CONTEXT_MAJOR_VERSION_KHR, 4,
                EGL_CONTEXT_MINOR_VERSION_KHR, 6,
                EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR, EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT_KHR,
                EGL_NONE
        };
        EGLint attrs43[] = {
                EGL_CONTEXT_MAJOR_VERSION_KHR, 4,
                EGL_CONTEXT_MINOR_VERSION_KHR, 3,
                EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR, EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT_KHR,
                EGL_NONE
        };
        EGLint attrs33[] = {
                EGL_CONTEXT_MAJOR_VERSION_KHR, 3,
                EGL_CONTEXT_MINOR_VERSION_KHR, 3,
                EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR, EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT_KHR,
                EGL_NONE
        };
        EGLint defaultAttrs[] = { EGL_NONE };

        const EGLint* tries[] = {
                requestedMajor > 0 ? explicitAttrs : nullptr,
                attrs46,
                attrs43,
                attrs33,
                defaultAttrs,
                nullptr
        };
        const char* names[] = {
                "explicit",
                "4.6compat",
                "4.3compat",
                "3.3compat",
                "default",
                nullptr
        };

        for (int i = 0; names[i] != nullptr; ++i) {
            if (tries[i] == nullptr) {
                continue;
            }
            context_ = eglCreateContext_(display_, config_, EGL_NO_CONTEXT, tries[i]);
            if (context_ != EGL_NO_CONTEXT) {
                DB_LOGI("Created desktop GL context using %s attrs context=%p", names[i], context_);
                printf("DroidBridgeNativeGLFW: Created desktop GL context using %s context=%p\n", names[i], context_);
                fflush(stdout);
                return true;
            }
            DB_LOGW("eglCreateContext desktop try %s failed err=0x%x", names[i], eglGetError_ ? eglGetError_() : 0);
            printf("DroidBridgeNativeGLFW: eglCreateContext desktop try %s failed err=0x%x\n", names[i], eglGetError_ ? eglGetError_() : 0);
            fflush(stdout);
        }
    } else {
        EGLint attrs[] = {
                EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL_NONE
        };
        context_ = eglCreateContext_(display_, config_, EGL_NO_CONTEXT, attrs);
        if (context_ != EGL_NO_CONTEXT) {
            DB_LOGI("Created GLES3 context=%p", context_);
            return true;
        }

        EGLint attrs2[] = {
                EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL_NONE
        };
        context_ = eglCreateContext_(display_, config_, EGL_NO_CONTEXT, attrs2);
        if (context_ != EGL_NO_CONTEXT) {
            DB_LOGI("Created GLES2 context=%p", context_);
            return true;
        }
    }

    setError(eglErrorString("eglCreateContext failed"));
    return false;
}

bool EglOwner::createWindowSurface(ANativeWindow* window) {
    if (surface_ != EGL_NO_SURFACE) return true;

    const EGLint attrs[] = { EGL_NONE };
    surface_ = eglCreateWindowSurface_(display_, config_, window, attrs);
    if (surface_ == EGL_NO_SURFACE) {
        setError(eglErrorString("eglCreateWindowSurface failed"));
        return false;
    }
    DB_LOGI("Created EGL window surface=%p", surface_);
    return true;
}

bool EglOwner::loadGlProcs() {
    if (glClearColor_ && glClear_ && glViewport_ && glGetString_) return true;

    glClearColor_ = reinterpret_cast<PfnGlClearColor>(eglGetProcAddress_("glClearColor"));
    glClear_ = reinterpret_cast<PfnGlClear>(eglGetProcAddress_("glClear"));
    glViewport_ = reinterpret_cast<PfnGlViewport>(eglGetProcAddress_("glViewport"));
    glGetString_ = reinterpret_cast<PfnGlGetString>(eglGetProcAddress_("glGetString"));

    if (!glClearColor_ || !glClear_ || !glViewport_ || !glGetString_) {
        setError("Missing GL proc(s) from eglGetProcAddress");
        return false;
    }

    DB_LOGI("GL strings vendor=%s renderer=%s version=%s glsl=%s",
            glString(0x1F00).c_str(),
            glString(0x1F01).c_str(),
            glString(0x1F02).c_str(),
            glString(0x8B8C).c_str());
    return true;
}

void EglOwner::setError(const std::string& message) {
    lastError_ = message;
    DB_LOGE("%s", message.c_str());
}

std::string EglOwner::eglErrorString(const char* prefix) const {
    EGLint error = eglGetError_ ? eglGetError_() : 0;
    std::ostringstream oss;
    oss << prefix << " EGL error=0x" << std::hex << error;
    return oss.str();
}

void* EglOwner::resolve(const char* name, bool required) {
    void* symbol = dlsym(eglHandle_, name);
    if (required && symbol == nullptr) {
        DB_LOGE("Missing symbol %s", name);
    }
    return symbol;
}

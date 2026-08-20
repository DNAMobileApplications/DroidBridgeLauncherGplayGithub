/*
 * Copyright (c) 2026 DNA Mobile Applications.
 */
#pragma once

#include <EGL/egl.h>
#include <android/native_window.h>
#include <string>

class EglOwner {
public:
    EglOwner();
    ~EglOwner();

    void configure(std::string eglLibraryPathOrName,
                   std::string driverOverride,
                   bool desktopGl,
                   int major,
                   int minor);

    bool attachSurface(ANativeWindow* window, int width, int height);
    void resize(int width, int height);
    void detachSurface();
    void destroy();

    bool makeCurrent();
    bool swapBuffers();
    bool clearTest(float red, float green, float blue, float alpha);
    std::string glString(int name);
    std::string lastError() const;
    void* eglHandle() const;

private:
    using PfnEglGetDisplay = EGLDisplay (*)(EGLNativeDisplayType);
    using PfnEglGetPlatformDisplay = EGLDisplay (*)(EGLenum, void*, const EGLAttrib*);
    using PfnEglInitialize = EGLBoolean (*)(EGLDisplay, EGLint*, EGLint*);
    using PfnEglTerminate = EGLBoolean (*)(EGLDisplay);
    using PfnEglChooseConfig = EGLBoolean (*)(EGLDisplay, const EGLint*, EGLConfig*, EGLint, EGLint*);
    using PfnEglGetConfigAttrib = EGLBoolean (*)(EGLDisplay, EGLConfig, EGLint, EGLint*);
    using PfnEglBindApi = EGLBoolean (*)(EGLenum);
    using PfnEglCreateContext = EGLContext (*)(EGLDisplay, EGLConfig, EGLContext, const EGLint*);
    using PfnEglDestroyContext = EGLBoolean (*)(EGLDisplay, EGLContext);
    using PfnEglCreateWindowSurface = EGLSurface (*)(EGLDisplay, EGLConfig, EGLNativeWindowType, const EGLint*);
    using PfnEglDestroySurface = EGLBoolean (*)(EGLDisplay, EGLSurface);
    using PfnEglMakeCurrent = EGLBoolean (*)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
    using PfnEglSwapBuffers = EGLBoolean (*)(EGLDisplay, EGLSurface);
    using PfnEglGetError = EGLint (*)();
    using PfnEglQueryString = const char* (*)(EGLDisplay, EGLint);
    using PfnEglGetProcAddress = __eglMustCastToProperFunctionPointerType (*)(const char*);

    using PfnGlClearColor = void (*)(float, float, float, float);
    using PfnGlClear = void (*)(unsigned int);
    using PfnGlViewport = void (*)(int, int, int, int);
    using PfnGlGetString = const unsigned char* (*)(unsigned int);

    bool loadEgl();
    void unloadEgl();
    bool initializeDisplay();
    bool chooseConfig();
    bool createContext();
    bool createWindowSurface(ANativeWindow* window);
    bool loadGlProcs();
    void setError(const std::string& message);
    std::string eglErrorString(const char* prefix) const;
    void* resolve(const char* name, bool required = true);

    std::string eglLibraryPathOrName_;
    std::string driverOverride_;
    bool desktopGl_ = false;
    int requestedMajor_ = 0;
    int requestedMinor_ = 0;
    int width_ = 1;
    int height_ = 1;

    void* eglHandle_ = nullptr;
    bool ownsEglHandle_ = false;

    PfnEglGetDisplay eglGetDisplay_ = nullptr;
    PfnEglGetPlatformDisplay eglGetPlatformDisplay_ = nullptr;
    PfnEglInitialize eglInitialize_ = nullptr;
    PfnEglTerminate eglTerminate_ = nullptr;
    PfnEglChooseConfig eglChooseConfig_ = nullptr;
    PfnEglGetConfigAttrib eglGetConfigAttrib_ = nullptr;
    PfnEglBindApi eglBindAPI_ = nullptr;
    PfnEglCreateContext eglCreateContext_ = nullptr;
    PfnEglDestroyContext eglDestroyContext_ = nullptr;
    PfnEglCreateWindowSurface eglCreateWindowSurface_ = nullptr;
    PfnEglDestroySurface eglDestroySurface_ = nullptr;
    PfnEglMakeCurrent eglMakeCurrent_ = nullptr;
    PfnEglSwapBuffers eglSwapBuffers_ = nullptr;
    PfnEglGetError eglGetError_ = nullptr;
    PfnEglQueryString eglQueryString_ = nullptr;
    PfnEglGetProcAddress eglGetProcAddress_ = nullptr;

    PfnGlClearColor glClearColor_ = nullptr;
    PfnGlClear glClear_ = nullptr;
    PfnGlViewport glViewport_ = nullptr;
    PfnGlGetString glGetString_ = nullptr;

    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLConfig config_ = nullptr;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface surface_ = EGL_NO_SURFACE;
    ANativeWindow* window_ = nullptr;
    std::string lastError_;
};

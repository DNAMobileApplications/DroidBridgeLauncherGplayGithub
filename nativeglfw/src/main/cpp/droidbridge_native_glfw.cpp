/*
 * Copyright (c) 2026 DNA Mobile Applications.
 */
#include "egl_owner.h"

#include <android/native_window_jni.h>
#include <jni.h>
#include <memory>
#include <cstdio>
#include <string>

#define DB_NATIVE_GLFW_EXPORT extern "C" __attribute__((visibility("default"), used))

static EglOwner* fromHandle(jlong handle) {
    return reinterpret_cast<EglOwner*>(handle);
}

static std::string jString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_ca_dnamobile_nativeglfw_DroidBridgeNativeGlfw_nativeCreate(JNIEnv*, jclass) {
    auto* owner = new EglOwner();
    return reinterpret_cast<jlong>(owner);
}

extern "C" JNIEXPORT void JNICALL
Java_ca_dnamobile_nativeglfw_DroidBridgeNativeGlfw_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete fromHandle(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_ca_dnamobile_nativeglfw_DroidBridgeNativeGlfw_nativeConfigureRenderer(
        JNIEnv* env, jclass, jlong handle, jstring eglLibraryPathOrName,
        jstring driverOverride, jboolean desktopGl, jint major, jint minor) {
    EglOwner* owner = fromHandle(handle);
    if (!owner) return;
    owner->configure(jString(env, eglLibraryPathOrName),
                     jString(env, driverOverride),
                     desktopGl == JNI_TRUE,
                     major,
                     minor);
}

extern "C" JNIEXPORT void JNICALL
Java_ca_dnamobile_nativeglfw_DroidBridgeNativeGlfw_nativeSurfaceCreated(
        JNIEnv* env, jclass, jlong handle, jobject surface, jint width, jint height) {
    EglOwner* owner = fromHandle(handle);
    if (!owner || surface == nullptr) return;

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return;

    owner->attachSurface(window, width, height);

    // attachSurface acquires its own reference, so release the temporary JNI reference.
    ANativeWindow_release(window);
}

extern "C" JNIEXPORT void JNICALL
Java_ca_dnamobile_nativeglfw_DroidBridgeNativeGlfw_nativeSurfaceChanged(
        JNIEnv*, jclass, jlong handle, jint width, jint height) {
    EglOwner* owner = fromHandle(handle);
    if (!owner) return;
    owner->resize(width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_ca_dnamobile_nativeglfw_DroidBridgeNativeGlfw_nativeSurfaceDestroyed(JNIEnv*, jclass, jlong handle) {
    EglOwner* owner = fromHandle(handle);
    if (!owner) return;
    owner->detachSurface();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ca_dnamobile_nativeglfw_DroidBridgeNativeGlfw_nativeMakeCurrent(JNIEnv*, jclass, jlong handle) {
    EglOwner* owner = fromHandle(handle);
    return owner && owner->makeCurrent() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ca_dnamobile_nativeglfw_DroidBridgeNativeGlfw_nativeSwapBuffers(JNIEnv*, jclass, jlong handle) {
    EglOwner* owner = fromHandle(handle);
    return owner && owner->swapBuffers() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ca_dnamobile_nativeglfw_DroidBridgeNativeGlfw_nativeClearTest(
        JNIEnv*, jclass, jlong handle, jfloat red, jfloat green, jfloat blue, jfloat alpha) {
    EglOwner* owner = fromHandle(handle);
    return owner && owner->clearTest(red, green, blue, alpha) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_ca_dnamobile_nativeglfw_DroidBridgeNativeGlfw_nativeGetGlString(
        JNIEnv* env, jclass, jlong handle, jint name) {
    EglOwner* owner = fromHandle(handle);
    std::string value = owner ? owner->glString(name) : std::string();
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_ca_dnamobile_nativeglfw_DroidBridgeNativeGlfw_nativeGetLastError(JNIEnv* env, jclass, jlong handle) {
    EglOwner* owner = fromHandle(handle);
    std::string value = owner ? owner->lastError() : std::string("invalid handle");
    return env->NewStringUTF(value.c_str());
}

static EglOwner* gGlobalOwner = nullptr;
static unsigned long long gSwapCounter = 0;

static EglOwner* globalOwner() {
    if (gGlobalOwner == nullptr) {
        gGlobalOwner = new EglOwner();
    }
    return gGlobalOwner;
}

DB_NATIVE_GLFW_EXPORT
const char* droidbridge_native_glfw_abi_version() {
    return "v82";
}

DB_NATIVE_GLFW_EXPORT
int droidbridge_native_glfw_has_required_exports() {
    return 1;
}

DB_NATIVE_GLFW_EXPORT
void droidbridge_native_glfw_configure_global(const char* egl, const char* driver, int desktopGl) {
    EglOwner* owner = globalOwner();
    owner->configure(egl ? egl : "", driver ? driver : "", desktopGl != 0, 4, 6);
}

DB_NATIVE_GLFW_EXPORT
void droidbridge_native_glfw_surface_window(ANativeWindow* window, int width, int height) {
    EglOwner* owner = globalOwner();
    if (window == nullptr) {
        printf("DroidBridgeNativeGLFW: surface_window called with null window\n");
        fflush(stdout);
        return;
    }
    bool ok = owner->attachSurface(window, width > 0 ? width : 1, height > 0 ? height : 1);
    printf("DroidBridgeNativeGLFW: surface_window attach result=%d error=%s\n", ok ? 1 : 0, owner->lastError().c_str());
    fflush(stdout);
}

DB_NATIVE_GLFW_EXPORT
void droidbridge_native_glfw_surface_destroyed() {
    if (gGlobalOwner != nullptr) {
        gGlobalOwner->detachSurface();
    }
}

DB_NATIVE_GLFW_EXPORT
void droidbridge_native_glfw_resize(int width, int height) {
    EglOwner* owner = globalOwner();
    owner->resize(width > 0 ? width : 1, height > 0 ? height : 1);
    printf("DroidBridgeNativeGLFW: global resize size=%dx%d error=%s\n",
           width > 0 ? width : 1,
           height > 0 ? height : 1,
           owner->lastError().c_str());
    fflush(stdout);
}

DB_NATIVE_GLFW_EXPORT
void* droidbridge_native_glfw_create_context(void* shared) {
    (void) shared;
    EglOwner* owner = globalOwner();
    // Context/window creation happens when the Android Surface is attached. Return a
    // non-null token so LWJGL's old GLFW shim can continue.
    return reinterpret_cast<void*>(owner);
}

DB_NATIVE_GLFW_EXPORT
void droidbridge_native_glfw_make_current(void* window) {
    (void) window;
    if (gGlobalOwner != nullptr) {
        bool ok = gGlobalOwner->makeCurrent();
        printf("DroidBridgeNativeGLFW: exported make_current result=%d error=%s\n", ok ? 1 : 0, gGlobalOwner->lastError().c_str());
        fflush(stdout);
    } else {
        printf("DroidBridgeNativeGLFW: exported make_current without owner\n");
        fflush(stdout);
    }
}


DB_NATIVE_GLFW_EXPORT
void* droidbridge_native_glfw_acquire_opengl_handle() {
    if (gGlobalOwner == nullptr) {
        printf("DroidBridgeNativeGLFW: acquire_opengl_handle before global owner\n");
        fflush(stdout);
        return nullptr;
    }
    bool current = gGlobalOwner->makeCurrent();
    void* handle = gGlobalOwner->eglHandle();
    std::string version = current ? gGlobalOwner->glString(0x1F02) : std::string();
    std::string renderer = current ? gGlobalOwner->glString(0x1F01) : std::string();
    printf("DroidBridgeNativeGLFW: acquire_opengl_handle handle=%p current=%d renderer=%s version=%s error=%s\n",
           handle,
           current ? 1 : 0,
           renderer.empty() ? "<empty>" : renderer.c_str(),
           version.empty() ? "<empty>" : version.c_str(),
           gGlobalOwner->lastError().c_str());
    fflush(stdout);
    return current ? handle : nullptr;
}

DB_NATIVE_GLFW_EXPORT
void droidbridge_native_glfw_swap_buffers() {
    gSwapCounter++;
    if (gGlobalOwner == nullptr) {
        if (gSwapCounter <= 5 || (gSwapCounter % 120ULL) == 0ULL) {
            printf("DroidBridgeNativeGLFW: swap_buffers frame=%llu no global owner\n", gSwapCounter);
            fflush(stdout);
        }
        return;
    }

    bool current = gGlobalOwner->makeCurrent();
    bool swapped = current && gGlobalOwner->swapBuffers();
    if (gSwapCounter <= 5 || (gSwapCounter % 120ULL) == 0ULL || !swapped) {
        printf("DroidBridgeNativeGLFW: swap_buffers frame=%llu current=%d swapped=%d error=%s\n",
               gSwapCounter,
               current ? 1 : 0,
               swapped ? 1 : 0,
               gGlobalOwner->lastError().c_str());
        fflush(stdout);
    }
}

DB_NATIVE_GLFW_EXPORT
void droidbridge_native_glfw_terminate() {
    if (gGlobalOwner != nullptr) {
        delete gGlobalOwner;
        gGlobalOwner = nullptr;
    }
}

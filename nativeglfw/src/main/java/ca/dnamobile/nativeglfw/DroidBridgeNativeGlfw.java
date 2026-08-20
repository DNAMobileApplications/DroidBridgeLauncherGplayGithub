/*
 * Copyright (c) 2026 DNA Mobile Applications.
 *
 * DroidBridgeNativeGLFW is DroidBridge-owned code. It is not copied from
 * Mojo Launcher, GLFW, DroidBridge Launcher, Zalith, or any third-party launcher.
 *
 * Purpose:
 *   Experimental Android Surface/EGL owner for DroidBridge renderer tests.
 *   This is a small foundation layer, not a complete desktop GLFW replacement yet.
 */
package ca.dnamobile.nativeglfw;

import android.text.TextUtils;
import android.view.Surface;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

@Keep
public final class DroidBridgeNativeGlfw implements AutoCloseable {
    public static final String TAG = "DroidBridgeNativeGLFW";

    /** Use Android/system EGL. Good for smoke tests. */
    public static final String EGL_SYSTEM = "";

    /** Use Mesa EGL by soname. DroidBridge normally uses an absolute app-lib path instead. */
    public static final String EGL_MESA_SONAME = "libEGL_mesa.so";

    private long nativeHandle;
    private boolean closed;

    static {
        System.loadLibrary("droidbridge_native_glfw_v82");
    }

    public DroidBridgeNativeGlfw() {
        nativeHandle = nativeCreate();
        if (nativeHandle == 0L) {
            throw new IllegalStateException("Failed to create native DroidBridgeNativeGlfw state");
        }
    }

    /**
     * Configure the EGL provider before attaching a Surface.
     *
     * @param eglLibraryPathOrName empty/null for system EGL, or absolute path/soname such as libEGL_mesa.so
     * @param driverOverride optional MESA_LOADER_DRIVER_OVERRIDE value, e.g. "kgsl"
     * @param desktopGl true for EGL_OPENGL_API / desktop GL; false for GLES3
     * @param major requested major version; use 0 for default fallback sequence
     * @param minor requested minor version; use 0 for default fallback sequence
     */
    public synchronized void configureRenderer(@Nullable String eglLibraryPathOrName,
                                               @Nullable String driverOverride,
                                               boolean desktopGl,
                                               int major,
                                               int minor) {
        ensureOpen();
        nativeConfigureRenderer(
                nativeHandle,
                TextUtils.isEmpty(eglLibraryPathOrName) ? "" : eglLibraryPathOrName,
                TextUtils.isEmpty(driverOverride) ? "" : driverOverride,
                desktopGl,
                major,
                minor
        );
    }

    /** Attach or replace the Android Surface and create the EGL window surface/context. */
    public synchronized void surfaceCreated(@NonNull Surface surface, int width, int height) {
        ensureOpen();
        nativeSurfaceCreated(nativeHandle, surface, Math.max(width, 1), Math.max(height, 1));
    }

    public synchronized void surfaceChanged(int width, int height) {
        ensureOpen();
        nativeSurfaceChanged(nativeHandle, Math.max(width, 1), Math.max(height, 1));
    }

    public synchronized void surfaceDestroyed() {
        if (!closed && nativeHandle != 0L) {
            nativeSurfaceDestroyed(nativeHandle);
        }
    }

    public synchronized boolean makeCurrent() {
        ensureOpen();
        return nativeMakeCurrent(nativeHandle);
    }

    public synchronized boolean swapBuffers() {
        ensureOpen();
        return nativeSwapBuffers(nativeHandle);
    }

    /** Smoke test: clear the attached surface. This proves native EGL ownership works. */
    public synchronized boolean clearTest(float red, float green, float blue, float alpha) {
        ensureOpen();
        return nativeClearTest(nativeHandle, red, green, blue, alpha);
    }

    public synchronized String getGlVendor() {
        ensureOpen();
        return nativeGetGlString(nativeHandle, 0x1F00); // GL_VENDOR
    }

    public synchronized String getGlRenderer() {
        ensureOpen();
        return nativeGetGlString(nativeHandle, 0x1F01); // GL_RENDERER
    }

    public synchronized String getGlVersion() {
        ensureOpen();
        return nativeGetGlString(nativeHandle, 0x1F02); // GL_VERSION
    }

    public synchronized String getGlslVersion() {
        ensureOpen();
        return nativeGetGlString(nativeHandle, 0x8B8C); // GL_SHADING_LANGUAGE_VERSION
    }

    public synchronized String getLastError() {
        if (nativeHandle == 0L) {
            return "native handle closed";
        }
        return nativeGetLastError(nativeHandle);
    }

    public long getNativeHandleForTestingOnly() {
        return nativeHandle;
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            long handle = nativeHandle;
            nativeHandle = 0L;
            if (handle != 0L) {
                nativeDestroy(handle);
            }
        }
    }

    private void ensureOpen() {
        if (closed || nativeHandle == 0L) {
            throw new IllegalStateException("DroidBridgeNativeGlfw is closed");
        }
    }

    private static native long nativeCreate();
    private static native void nativeDestroy(long handle);
    private static native void nativeConfigureRenderer(long handle, String eglLibraryPathOrName,
                                                       String driverOverride, boolean desktopGl,
                                                       int major, int minor);
    private static native void nativeSurfaceCreated(long handle, Surface surface, int width, int height);
    private static native void nativeSurfaceChanged(long handle, int width, int height);
    private static native void nativeSurfaceDestroyed(long handle);
    private static native boolean nativeMakeCurrent(long handle);
    private static native boolean nativeSwapBuffers(long handle);
    private static native boolean nativeClearTest(long handle, float red, float green, float blue, float alpha);
    private static native String nativeGetGlString(long handle, int name);
    private static native String nativeGetLastError(long handle);
}

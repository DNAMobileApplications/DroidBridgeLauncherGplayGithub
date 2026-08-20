/*
 * Copyright (c) 2026 DNA Mobile Applications.
 */
package ca.dnamobile.nativeglfw;

import android.view.Surface;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

/**
 * Small GLFW-like facade for the future LWJGL shim.
 *
 * This intentionally does not claim to be complete GLFW. It only models the window/context
 * calls DroidBridge needs to replace the old droidbridgeCreateContext/droidbridgeMakeCurrent path.
 */
@Keep
public final class DroidBridgeGlfwCompat {
    private static DroidBridgeNativeGlfw instance;

    private DroidBridgeGlfwCompat() {}

    public static synchronized void init(String eglLibraryPathOrName, String driverOverride, boolean desktopGl) {
        if (instance == null) {
            instance = new DroidBridgeNativeGlfw();
        }
        instance.configureRenderer(eglLibraryPathOrName, driverOverride, desktopGl, 0, 0);
    }

    public static synchronized void nativeSurfaceCreated(@NonNull Surface surface, int width, int height) {
        require().surfaceCreated(surface, width, height);
    }

    public static synchronized void nativeSurfaceChanged(int width, int height) {
        require().surfaceChanged(width, height);
    }

    public static synchronized void nativeSurfaceDestroyed() {
        if (instance != null) {
            instance.surfaceDestroyed();
        }
    }

    public static synchronized long createWindow(int width, int height, String title) {
        // LWJGL expects a non-zero window token. For this bridge the singleton native handle is enough.
        return require().getNativeHandleForTestingOnly();
    }

    public static synchronized void makeContextCurrent(long window) {
        require().makeCurrent();
    }

    public static synchronized void swapBuffers(long window) {
        require().swapBuffers();
    }

    public static synchronized void terminate() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    public static synchronized String getDebugString() {
        DroidBridgeNativeGlfw glfw = require();
        return "vendor=" + glfw.getGlVendor()
                + ", renderer=" + glfw.getGlRenderer()
                + ", version=" + glfw.getGlVersion()
                + ", glsl=" + glfw.getGlslVersion()
                + ", lastError=" + glfw.getLastError();
    }

    private static DroidBridgeNativeGlfw require() {
        if (instance == null) {
            instance = new DroidBridgeNativeGlfw();
        }
        return instance;
    }
}

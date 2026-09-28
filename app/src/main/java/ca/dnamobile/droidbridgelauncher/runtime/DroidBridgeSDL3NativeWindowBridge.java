/*
 * DroidBridge Launcher SDL3 cross-VM native-window bridge.
 *
 * Android's UI runs in ART while Minecraft runs in an embedded OpenJDK VM.
 * Java Surface objects and static fields cannot be shared between those VMs.
 * This class publishes the real ANativeWindow in native process state and
 * installs a narrowly-scoped import hook in libSDL3.so.
 *
 * Copyright (c) 2026 DNA Mobile Applications.
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package ca.dnamobile.droidbridgelauncher.runtime;

import android.view.Surface;

import androidx.annotation.NonNull;

import ca.dnamobile.droidbridgelauncher.logs.LauncherLogManager;

/** Process-wide SDL3 ANativeWindow bridge shared by ART and embedded OpenJDK. */
public final class DroidBridgeSDL3NativeWindowBridge {
    private static final Object LOCK = new Object();
    private static volatile boolean libraryAttempted;
    private static volatile boolean libraryLoaded;
    private static volatile boolean hookInstalled;

    private DroidBridgeSDL3NativeWindowBridge() {
    }

    private static native boolean nativeInstallSdl3NativeWindowHook();
    private static native boolean nativePublishArtSdl3Handle();
    private static native boolean nativeStartArtSdlDispatcher();
    private static native boolean nativePublishSurface(Surface surface);
    private static native void nativeClearSurface();
    private static native boolean nativeHasPublishedWindow();
    private static native boolean nativeQuerySdlMouseState(float[] outState);
    private static native boolean nativeWarpSdlMouse(float x, float y);
    private static native boolean nativeConfigureSdlOpenGlBridge(
            String eglLibrary,
            String glLibrary,
            String rendererKind
    );

    private static boolean ensureLibraryLoaded() {
        if (libraryLoaded) return true;
        synchronized (LOCK) {
            if (libraryLoaded) return true;
            if (!libraryAttempted) {
                libraryAttempted = true;
                try {
                    System.loadLibrary("droidbridge_runtime");
                    libraryLoaded = true;
                } catch (Throwable throwable) {
                    // It may already be loaded by JREUtils in this VM. Try the
                    // native entry point once before treating this as failure.
                    append("DroidBridgeSDL3NativeWindow: loadLibrary notice: " + throwable);
                }
            }

            if (!libraryLoaded) {
                try {
                    nativeHasPublishedWindow();
                    libraryLoaded = true;
                } catch (Throwable throwable) {
                    append("DroidBridgeSDL3NativeWindow: runtime native bridge unavailable: "
                            + throwable);
                }
            }
            return libraryLoaded;
        }
    }

    /**
     * Publishes the ART/JNI-initialized SDL3 handle for reuse by embedded OpenJDK.
     *
     * Android 10 is stricter about loading the same JNI library into multiple
     * linker namespaces. Reusing this handle keeps SDL's Android JavaVM/JNIEnv
     * state attached to the same native image that Minecraft later calls.
     */
    public static boolean publishArtSdl3Handle() {
        if (!ensureLibraryLoaded()) return false;
        try {
            boolean published = nativePublishArtSdl3Handle();
            append("DroidBridgeSDL3NativeWindow: ART SDL3 handle published=" + published);
            return published;
        } catch (Throwable throwable) {
            append("DroidBridgeSDL3NativeWindow: ART SDL3 handle publish failed: "
                    + throwable);
            return false;
        }
    }

    /**
     * Starts a native SDL dispatcher thread that is attached to Android's ART VM.
     * Android 10 cannot attach Minecraft's embedded-OpenJDK render thread to ART,
     * so JNI-sensitive SDL entry points are synchronously executed by this worker.
     */
    public static boolean startArtSdlDispatcher() {
        if (!ensureLibraryLoaded()) return false;
        try {
            boolean started = nativeStartArtSdlDispatcher();
            append("DroidBridgeSDL3NativeWindow: ART SDL dispatcher started=" + started);
            return started;
        } catch (Throwable throwable) {
            append("DroidBridgeSDL3NativeWindow: ART SDL dispatcher start failed: "
                    + throwable);
            return false;
        }
    }

    /** Called after libSDL3.so is loaded and before Minecraft invokes SDL_Init. */
    public static boolean installHook() {
        synchronized (LOCK) {
            if (!ensureLibraryLoaded()) return false;
            try {
                // Always rescan. The embedded OpenJDK may map another libSDL3.so
                // after ART's first successful scan, and that mapping needs both
                // the ANativeWindow relocation and the OpenGL provider hints.
                hookInstalled = nativeInstallSdl3NativeWindowHook() || hookInstalled;
                append("DroidBridgeSDL3NativeWindow: SDL3 import hook installed="
                        + hookInstalled);
            } catch (Throwable throwable) {
                hookInstalled = false;
                append("DroidBridgeSDL3NativeWindow: hook installation failed: " + throwable);
            }
            return hookInstalled;
        }
    }

    /** Called from Android/ART with the real SurfaceHolder Surface. */
    public static boolean publish(@NonNull Surface surface) {
        if (!ensureLibraryLoaded()) return false;
        try {
            boolean published = nativePublishSurface(surface);
            append("DroidBridgeSDL3NativeWindow: published ANativeWindow=" + published
                    + " surfaceValid=" + surface.isValid());
            // ART often publishes the Surface before Minecraft loads libSDL3.so.
            // A scan here patches it immediately when already present; the embedded
            // JVM-side getNativeSurface() path retries after SDL has been loaded.
            if (published) installHook();
            return published;
        } catch (Throwable throwable) {
            append("DroidBridgeSDL3NativeWindow: publish failed: " + throwable);
            return false;
        }
    }

    public static void clear() {
        if (!ensureLibraryLoaded()) return;
        try {
            nativeClearSurface();
            append("DroidBridgeSDL3NativeWindow: cleared published ANativeWindow");
        } catch (Throwable throwable) {
            append("DroidBridgeSDL3NativeWindow: clear failed: " + throwable);
        }
    }

    /**
     * Reads SDL's own window-relative mouse cache and window geometry.
     *
     * outState layout: mouseX, mouseY, windowWidth, windowHeight,
     * pixelWidth, pixelHeight, buttonMask, windowDisplayScale,
     * windowPixelDensity.
     */
    public static boolean querySdlMouseState(@NonNull float[] outState) {
        if (outState.length < 9 || !ensureLibraryLoaded()) return false;
        try {
            return nativeQuerySdlMouseState(outState);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Forces SDL's own absolute cursor cache to the supplied window coordinate. */
    public static boolean warpSdlMouse(float x, float y) {
        if (!ensureLibraryLoaded()) return false;
        try {
            return nativeWarpSdlMouse(x, y);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Applies SDL's EGL/OpenGL provider hints directly to the loaded libSDL3.so.
     * Must run after SDL.loadLibrary("SDL3") and before SDL.setupJNI()/SDL_Init().
     */
    public static boolean configureSdlOpenGlBridge(
            @NonNull String eglLibrary,
            @NonNull String glLibrary,
            @NonNull String rendererKind
    ) {
        if (!ensureLibraryLoaded()) return false;
        try {
            boolean configured = nativeConfigureSdlOpenGlBridge(
                    eglLibrary,
                    glLibrary,
                    rendererKind
            );
            append("DroidBridgeSDL3NativeWindow: direct SDL OpenGL hints configured="
                    + configured
                    + " kind=" + rendererKind
                    + " egl=" + eglLibrary
                    + " gl=" + glLibrary);
            return configured;
        } catch (Throwable throwable) {
            append("DroidBridgeSDL3NativeWindow: direct SDL OpenGL hint failure: "
                    + throwable);
            return false;
        }
    }

    /** Safe to call from the embedded OpenJDK copy of SDLActivity. */
    public static boolean hasPublishedWindow() {
        // Re-scan here as well. If the embedded JVM loaded libSDL3 in a separate
        // Android linker namespace after ART performed the first scan, this copy
        // patches that active mapping before returning the token Surface.
        if (!installHook()) return false;
        try {
            return nativeHasPublishedWindow();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void append(String text) {
        try {
            android.util.Log.i("DroidBridgeSDL3NativeWindow", text);
        } catch (Throwable ignored) {
        }
        try {
            LauncherLogManager.append(text);
        } catch (Throwable ignored) {
        }
    }
}

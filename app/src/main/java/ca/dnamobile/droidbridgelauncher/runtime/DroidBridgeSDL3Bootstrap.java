/*
 * DroidBridge Launcher SDL3 Android platform bootstrap.
 *
 * Minecraft 26.3 Snapshot 4+ loads SDL from the embedded OpenJDK runtime, but
 * Android SDL still requires its Java/JNI frontend to be registered in the
 * Android runtime before SDL_Init(). This bridge performs that platform setup
 * only for SDL-window Minecraft versions and reuses DroidBridge's existing
 * render Surface.
 *
 * Copyright (c) 2026 DNA Mobile Applications.
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package ca.dnamobile.droidbridgelauncher.runtime;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.system.Os;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.libsdl.app.SDL;
import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLControllerManager;
import org.libsdl.app.SDLInputConnection;
import org.lwjgl.glfw.CallbackBridge;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ca.dnamobile.droidbridgelauncher.GameActivity;
import ca.dnamobile.droidbridgelauncher.input.InputEventDiagnosticLogger;
import ca.dnamobile.droidbridgelauncher.logs.LauncherLogManager;

public final class DroidBridgeSDL3Bootstrap {
    private static final Object LOCK = new Object();
    // Launcher profiles may be composite IDs such as fabric-loader-0.19.5-26.3.
    // Match the Minecraft token at a numeric boundary instead of requiring it
    // to be the first token in the launcher/profile id.
    private static final Pattern SNAPSHOT_PATTERN =
            Pattern.compile("(?:^|[^0-9])26\\.3-snapshot-(\\d+)(?:$|[^0-9].*)");
    private static final Pattern RELEASE_PATTERN =
            Pattern.compile("(?:^|[^0-9])26\\.(\\d+)(?:$|[^0-9].*)");

    /*
     * RenderPearl selects persistent mapped immutable buffers whenever desktop
     * GL 4.4 or ARB/EXT_buffer_storage is exposed. Android desktop-GL wrappers
     * such as MobileGlues and Krypton can advertise those capabilities while
     * their GLES-backed map path still returns NULL. The direct Freedreno KGSL
     * route has the same failure mode. Keep Snapshot 4 on the mutable-buffer
     * fallback across every SDL/OpenGL renderer.
     */
    private static final String SNAPSHOT4_SAFE_EXTENSION_OVERRIDE =
            "-GL_ARB_buffer_storage -GL_EXT_buffer_storage";

    private static volatile boolean requested;
    private static volatile boolean initialized;
    private static volatile boolean surfaceCreatedSent;
    private static volatile boolean inputRegistrationStarted;
    private static volatile String selectedVersion = "";

    /*
     * Exact SDL OpenGL providers selected by GameActivity. These are stored in
     * Java because the active libSDL3.so lives in the embedded OpenJDK linker
     * namespace. initializeLocked() applies them through SDL_SetHint with
     * OVERRIDE priority immediately after loading SDL and before SDL.setupJNI()
     * or SDL_Init().
     */
    private static volatile boolean sdlOpenGlCompatibilityRequested;
    @Nullable private static volatile String sdlOpenGlEglLibrary;
    @Nullable private static volatile String sdlOpenGlLibrary;
    @Nullable private static volatile String sdlOpenGlRendererKind;

    @Nullable private static volatile Surface publishedSurface;
    @Nullable private static volatile File sdlFpsSampleFile;
    @NonNull private static WeakReference<Activity> activityRef = new WeakReference<>(null);
    @NonNull private static WeakReference<MinecraftGLSurface> minecraftSurfaceRef = new WeakReference<>(null);

    // SDL/Vulkan presentation is corrected at swapchain creation. These dimensions
    // remain as a normal landscape coordinate bridge for mouse, touch and the
    // launcher-generated virtual cursor. No Android View transform is applied.
    private static volatile int visualQuarterTurnDegrees;
    private static volatile int visualViewLeft;
    private static volatile int visualViewTop;
    private static volatile int visualViewWidth = 1;
    private static volatile int visualViewHeight = 1;
    private static volatile int visualRenderWidth = 1;
    private static volatile int visualRenderHeight = 1;
    // Density is reported to SDL for display/UI scaling only. SDL Android mouse
    // events themselves use Surface pixel coordinates (the official SDLSurface
    // forwards MotionEvent x/y unchanged), so launcher cursor coordinates must stay
    // in the same pixel space as visualRenderWidth/visualRenderHeight.
    private static volatile float sdlContentScale = 1.0f;
    @Nullable private static volatile Surface lastResizeSurface;
    private static volatile int lastResizeRenderWidth = -1;
    private static volatile int lastResizeRenderHeight = -1;
    private static volatile int lastResizeDeviceWidth = -1;
    private static volatile int lastResizeDeviceHeight = -1;
    private static volatile boolean transientPauseLogged;
    private static volatile boolean hostStopped;
    private static volatile boolean surfaceProducerAttached;
    /*
     * A retained TextureView keeps its Java Surface/ANativeWindow object alive
     * across a real Android stop/resume. With a sub-100% render scale, however,
     * Android can reconnect the EGL drawable at the physical display size while
     * Minecraft keeps the smaller GL viewport. The visible result is the game
     * rendering only in the lower-left corner until a live resolution change
     * forces the TextureView producer/EGL surface geometry to be applied again.
     *
     * Arm one presentation-only repair after a genuine stop -> resume. The repair
     * is restricted to wrapped OpenGL and keeps the exact same retained Surface;
     * the Vulkan path is deliberately untouched.
     */
    private static volatile boolean retainedScaledGeometryRefreshPending;
    private static volatile int lastLoggedOverlayLeft = Integer.MIN_VALUE;
    private static volatile int lastLoggedOverlayTop = Integer.MIN_VALUE;
    private static volatile int lastLoggedOverlayWidth = -1;
    private static volatile int lastLoggedOverlayHeight = -1;

    // Android_OnMouse compares the complete button-state bitmask between calls.
    // Keep a process-side state for virtual mouse clicks generated by controllers.
    private static final Object VIRTUAL_MOUSE_LOCK = new Object();
    private static int virtualMouseButtonState;
    private static float lastVirtualCursorX;
    private static float lastVirtualCursorY;
    private static boolean virtualCursorBaselineValid;

    // Query SDL's active window geometry and mouse cache rather than deriving it
    // from Android density. The visible cursor remains in the full Android game-view
    // coordinate space and is normalized into the live SDL window for menu input.
    private static final float[] SDL_MOUSE_STATE = new float[9];
    private static long lastSdlMouseSyncLogUptimeMs;
    private static int lastSdlMouseWindowWidth = -1;
    private static int lastSdlMouseWindowHeight = -1;
    private static int lastSdlMousePixelWidth = -1;
    private static int lastSdlMousePixelHeight = -1;

    /*
     * prepareForLaunch() necessarily publishes the Android Surface before
     * Minecraft creates its real SDL_Window. Snapshot 4 ignores a same-size
     * post-window callback; a real resolution-slider transition is required to
     * initialize its menu mouse transform. Wait until the SDL window is stable
     * and rendering has started, then perform one 99% -> requested-size pulse
     * through the existing retained TextureView. This is startup-only and never
     * replaces the SurfaceTexture/ANativeWindow fixed by the v4 lifecycle work.
     */
    // Slow devices can spend >18 seconds in Java/Fabric bootstrap before the real
    // SDL_Window exists. The previous 180 x 100 ms deadline could expire literally
    // one frame before SDL created the window, leaving scaled launches with the
    // device-size menu input transform until the user nudged the resolution slider.
    // Keep this lightweight startup watcher alive for up to 60 seconds; it exits as
    // soon as the real SDL window is stable, so normal/fast devices are unchanged.
    private static final int POST_WINDOW_RESOLUTION_MAX_POLLS = 600;
    private static final long POST_WINDOW_RESOLUTION_POLL_MS = 100L;
    private static final long POST_WINDOW_RENDER_SETTLE_MS = 500L;
    // Do not leave startup input geometry waiting on an FPS sample. On 26.3 the
    // TextureView can already be scaled while Minecraft's real SDL_Window is still
    // reporting the device resolution. A live slider change fixes this immediately,
    // so perform the same non-zero resize shortly after the SDL window stabilizes.
    private static final long POST_WINDOW_RENDER_FALLBACK_MS = 900L;
    private static volatile int postWindowResolutionGeneration;
    private static volatile boolean postWindowResolutionScheduled;
    private static volatile boolean postWindowResolutionComplete;
    private static volatile boolean postWindowResolutionPulseInProgress;
    private static volatile int postWindowResolutionBaselineWidth = -1;
    private static volatile int postWindowResolutionBaselineHeight = -1;

    private DroidBridgeSDL3Bootstrap() {
    }

    /**
     * Stores the exact EGL and desktop-GL wrapper libraries for Snapshot 4.
     * The native SDL hints are applied later, after libSDL3.so exists but before
     * SDL initializes its video subsystem.
     */
    public static void configureOpenGlCompatibility(
            @NonNull String eglLibrary,
            @NonNull String glLibrary,
            @NonNull String rendererKind
    ) {
        synchronized (LOCK) {
            sdlOpenGlEglLibrary = eglLibrary.trim();
            sdlOpenGlLibrary = glLibrary.trim();
            sdlOpenGlRendererKind = rendererKind.trim();
            sdlOpenGlCompatibilityRequested = requested
                    && !sdlOpenGlEglLibrary.isEmpty()
                    && !sdlOpenGlLibrary.isEmpty();
            append("DroidBridgeSDL3: OpenGL compatibility stored requested="
                    + sdlOpenGlCompatibilityRequested
                    + " kind=" + sdlOpenGlRendererKind
                    + " egl=" + sdlOpenGlEglLibrary
                    + " gl=" + sdlOpenGlLibrary);
        }
    }

    public static void clearOpenGlCompatibility() {
        synchronized (LOCK) {
            sdlOpenGlCompatibilityRequested = false;
            sdlOpenGlEglLibrary = null;
            sdlOpenGlLibrary = null;
            sdlOpenGlRendererKind = null;
        }
    }

    public static void configure(@NonNull Activity activity, @Nullable String versionId) {
        synchronized (LOCK) {
            selectedVersion = versionId == null ? "" : versionId.trim();
            try {
                Os.unsetenv("DROIDBRIDGE_SDL3_PLATFORM_READY");
            } catch (Throwable ignored) {
            }
            requested = requiresAndroidSdlPlatform(selectedVersion);
            sdlOpenGlCompatibilityRequested = false;
            sdlOpenGlEglLibrary = null;
            sdlOpenGlLibrary = null;
            sdlOpenGlRendererKind = null;
            activityRef = new WeakReference<>(activity);
            MinecraftGLSurface.sdlWindowBackendRequested = requested;
            postWindowResolutionGeneration++;
            postWindowResolutionScheduled = false;
            postWindowResolutionComplete = false;
            postWindowResolutionPulseInProgress = false;
            postWindowResolutionBaselineWidth = -1;
            postWindowResolutionBaselineHeight = -1;
            retainedScaledGeometryRefreshPending = false;
            if (requested) {
                try {
                    Os.setenv("DROIDBRIDGE_SDL3_FORCE_VULKAN_IDENTITY", "1", true);
                    if (requiresAndroidSingleWindowReuse(selectedVersion)) {
                        Os.setenv("DROIDBRIDGE_SDL3_SINGLE_WINDOW_REUSE", "1", true);
                    } else {
                        Os.unsetenv("DROIDBRIDGE_SDL3_SINGLE_WINDOW_REUSE");
                    }
                    File fpsFile = DroidBridgeSdlFpsBridge.prepare(activity);
                    sdlFpsSampleFile = fpsFile;
                    Os.setenv("DROIDBRIDGE_SDL3_FPS_FILE", fpsFile.getAbsolutePath(), true);
                } catch (Throwable throwable) {
                    throw new IllegalStateException("Unable to configure SDL3 platform environment", throwable);
                }
                sdlContentScale = Math.max(1.0f, resolveDensity(activity));
                SDL.setContext(activity);
                SDLActivity.setDroidBridgeHostActivity(activity);
                SDLActivity.setDroidBridgeExternalSurfaceMode(true);
                append("DroidBridgeSDL3: platform requested for " + selectedVersion
                        + " VulkanSurfaceTransform=identity");
            } else {
                sdlFpsSampleFile = null;
                try {
                    Os.unsetenv("DROIDBRIDGE_SDL3_FORCE_VULKAN_IDENTITY");
                    Os.unsetenv("DROIDBRIDGE_SDL3_SINGLE_WINDOW_REUSE");
                    Os.unsetenv("DROIDBRIDGE_SDL3_FPS_FILE");
                } catch (Throwable ignored) {
                }
                minecraftSurfaceRef = new WeakReference<>(null);
                SDLActivity.setDroidBridgeInputView(null);
                SDLActivity.setDroidBridgeExternalSurfaceMode(false);
                configureVisualQuarterTurn(0, 1, 1, 1, 1);
            }
        }
    }

    public static boolean isRequested() {
        return requested;
    }

    /**
     * True once the Android SDL JNI frontend is ready to accept touch, keyboard,
     * mouse and controller events. This is intentionally separate from
     * Controlify detection: Minecraft 26.3 Snapshot 4 uses SDL as its native
     * platform even in completely vanilla installations.
     */
    public static boolean isInputReady() {
        return requested && initialized;
    }

    public static boolean requiresAndroidSdlPlatform(@Nullable String rawVersion) {
        if (rawVersion == null) return false;
        String value = rawVersion.trim().toLowerCase(Locale.ROOT)
                .replace('_', '-')
                .replace(' ', '-');
        while (value.contains("--")) value = value.replace("--", "-");

        Matcher snapshot = SNAPSHOT_PATTERN.matcher(value);
        if (snapshot.find()) {
            try {
                return Integer.parseInt(snapshot.group(1)) >= 4;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }

        Matcher release = RELEASE_PATTERN.matcher(value);
        if (release.find()) {
            try {
                return Integer.parseInt(release.group(1)) >= 3;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    /**
     * Snapshot 9 added an early graphics/backend bootstrap window before the
     * normal Minecraft Window is constructed. Desktop SDL can keep both
     * logical windows alive, but SDL's Android backend intentionally supports
     * only one SDL_Window. Reuse the first physical Android SDL window for the
     * later logical create beginning with Snapshot 9.
     */
    private static boolean requiresAndroidSingleWindowReuse(@Nullable String rawVersion) {
        if (rawVersion == null) return false;
        String value = rawVersion.trim().toLowerCase(Locale.ROOT)
                .replace('_', '-')
                .replace(' ', '-');
        while (value.contains("--")) value = value.replace("--", "-");

        Matcher snapshot = SNAPSHOT_PATTERN.matcher(value);
        if (snapshot.find()) {
            try {
                return Integer.parseInt(snapshot.group(1)) >= 9;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }

        // 26.3 release and later inherit the Snapshot 9 startup sequence.
        Matcher release = RELEASE_PATTERN.matcher(value);
        if (release.find()) {
            try {
                return Integer.parseInt(release.group(1)) >= 3;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    /**
     * Publishes the current Android render Surface without initializing SDL.
     * Surface callbacks may run before or after GameActivity reaches the common
     * launch path, so publishing is intentionally separate from initialization.
     */
    public static void publishSurface(@Nullable Surface surface) {
        if (!requested) return;
        synchronized (LOCK) {
            Surface previous = publishedSurface;
            if (surface != previous) {
                lastResizeSurface = null;
                lastResizeRenderWidth = -1;
                lastResizeRenderHeight = -1;
                lastResizeDeviceWidth = -1;
                lastResizeDeviceHeight = -1;

                // Match SDLSurface.surfaceDestroyed() ordering exactly. Android can
                // replace the BufferQueue before GameActivity reaches onStop(); if the
                // old VkSurfaceKHR is destroyed while SDL is still resumed, RenderPearl
                // races one more configure/present against it and permanently falls
                // into VK_ERROR_SURFACE_LOST_KHR. Pause first, then retire the surface,
                // and resume only after resizeSurface() publishes the replacement.
                if (initialized && surfaceCreatedSent && previous != null) {
                    pauseBeforeSurfaceLossLocked("native Surface replaced");
                    destroySdlSurfaceLocked("native Surface replaced");
                }
            }
            publishedSurface = surface;
            surfaceProducerAttached = surface != null && surface.isValid();
            SDLActivity.setDroidBridgeNativeSurface(surface);
            if (surfaceProducerAttached) {
                resumeHostIfReadyLocked("surface published");
            }
            LOCK.notifyAll();
        }
    }

    /**
     * A retained TextureView can temporarily detach its consumer while the app is
     * backgrounded. Keep the VkSurfaceKHR/ANativeWindow alive, but do not resume
     * Vulkan presentation until the same producer has been attached again.
     */
    public static void onSurfaceProducerDetached(@Nullable Surface surface) {
        if (!requested) return;
        synchronized (LOCK) {
            if (surface != null && publishedSurface != null && surface != publishedSurface) return;
            pauseHostLocked("TextureView producer detached");
            surfaceProducerAttached = false;
            append("DroidBridgeSDL3: retained surface producer detached; Vulkan acquisition gated");
            LOCK.notifyAll();
        }
    }

    public static void clearSurface(@Nullable Surface surface) {
        if (!requested) return;
        synchronized (LOCK) {
            if (surface != null && publishedSurface != null && surface != publishedSurface) return;

            // This callback is the authoritative indication that Android really
            // invalidated the render target. The stock SDL3 Java frontend pauses its
            // native thread before onNativeSurfaceDestroyed(); preserve that ordering
            // in DroidBridge's external-Surface mode as well.
            if (initialized && surfaceCreatedSent) {
                pauseBeforeSurfaceLossLocked("Android surfaceDestroyed");
                destroySdlSurfaceLocked("Android surfaceDestroyed");
            } else {
                surfaceCreatedSent = false;
            }

            publishedSurface = null;
            surfaceProducerAttached = false;
            SDLActivity.setDroidBridgeNativeSurface(null);
            LOCK.notifyAll();
        }
    }

    private static boolean resumeHostIfReadyLocked(@NonNull String reason) {
        if (!initialized || !hostStopped) return false;

        Surface surface = publishedSurface;
        if (!surfaceCreatedSent
                || !surfaceProducerAttached
                || surface == null
                || !surface.isValid()) {
            SDLActivity.mIsResumedCalled = false;
            append("DroidBridgeSDL3: resume deferred reason=" + reason
                    + " created=" + surfaceCreatedSent
                    + " producerAttached=" + surfaceProducerAttached
                    + " surfaceValid=" + (surface != null && surface.isValid()));
            return false;
        }

        try {
            SDLActivity.setDroidBridgeNativeSurface(surface);
            SDLActivity.mIsResumedCalled = true;
            SDLActivity.nativeResume();
            hostStopped = false;
            setPresentationPausedLocked(false, "resumed: " + reason);
            append("DroidBridgeSDL3: resumed SDL with retained Surface reason=" + reason);
            return true;
        } catch (Throwable throwable) {
            SDLActivity.mIsResumedCalled = false;
            append("DroidBridgeSDL3: retained Surface resume failed reason=" + reason
                    + " error=" + throwable);
            return false;
        }
    }

    private static void pauseBeforeSurfaceLossLocked(@NonNull String reason) {
        pauseHostLocked("Surface loss: " + reason);
    }

    private static void destroySdlSurfaceLocked(@NonNull String reason) {
        if (!surfaceCreatedSent) return;
        try {
            SDLActivity.onNativeSurfaceDestroyed();
            append("DroidBridgeSDL3: retired SDL Surface after pause reason=" + reason);
        } catch (Throwable throwable) {
            append("DroidBridgeSDL3: surface destroy callback failed reason=" + reason
                    + " error=" + throwable);
        } finally {
            surfaceCreatedSent = false;
        }
    }

    /**
     * Runs from GameActivity's shared launch path immediately before LaunchGame.
     * This guarantees identical ordering for MobileGlues, NativeGLFW/Freedreno,
     * Zink and every other renderer.
     */
    public static void prepareForLaunch(
            @NonNull Activity activity,
            @NonNull MinecraftGLSurface minecraftSurface
    ) {
        if (!requested) return;

        applySnapshot4OpenGlBufferCompatibility();

        MinecraftGLSurface.SdlSurfaceSnapshot snapshot =
                minecraftSurface.awaitSdlSurfaceSnapshot(4000L);
        if (snapshot == null || snapshot.surface == null || !snapshot.surface.isValid()) {
            throw new IllegalStateException(
                    "SDL3 Android platform could not obtain a valid DroidBridge render Surface");
        }

        synchronized (LOCK) {
            activityRef = new WeakReference<>(activity);
            minecraftSurfaceRef = new WeakReference<>(minecraftSurface);
            publishedSurface = snapshot.surface;
            SDLActivity.setDroidBridgeNativeSurface(snapshot.surface);
            SDLActivity.setDroidBridgeHostActivity(activity);
            SDLActivity.setDroidBridgeInputView(minecraftSurface);
            SDL.setContext(activity);

            if (!initialized) {
                initializeLocked(activity);
            }

            // SDL.initialize() resets official SDLActivity state, so always
            // republish DroidBridge's host and Surface after initialization.
            SDL.setContext(activity);
            SDLActivity.setDroidBridgeHostActivity(activity);
            SDLActivity.setDroidBridgeNativeSurface(snapshot.surface);
            SDLActivity.setDroidBridgeInputView(minecraftSurface);

            try {
                SDLActivity.applyDroidBridgeExternalSurfaceOrientation(
                        Math.max(1, snapshot.renderWidth),
                        Math.max(1, snapshot.renderHeight)
                );
                sdlContentScale = Math.max(1.0f, resolveDensity(activity));
                SDLActivity.nativeSetScreenResolution(
                        Math.max(1, snapshot.renderWidth),
                        Math.max(1, snapshot.renderHeight),
                        Math.max(1, snapshot.deviceWidth),
                        Math.max(1, snapshot.deviceHeight),
                        sdlContentScale,
                        resolveRefreshRate(activity)
                );
                lastResizeSurface = snapshot.surface;
                lastResizeRenderWidth = Math.max(1, snapshot.renderWidth);
                lastResizeRenderHeight = Math.max(1, snapshot.renderHeight);
                lastResizeDeviceWidth = Math.max(1, snapshot.deviceWidth);
                lastResizeDeviceHeight = Math.max(1, snapshot.deviceHeight);
                if (!surfaceCreatedSent) {
                    SDLActivity.onNativeSurfaceCreated();
                    surfaceCreatedSent = true;
                }
                SDLActivity.onNativeSurfaceChanged();
                SDLActivity.onNativeResize();
                SDLActivity.mIsResumedCalled = true;
                SDLActivity.mHasFocus = activity.hasWindowFocus();
                SDLActivity.nativeResume();
                hostStopped = false;
                setPresentationPausedLocked(false, "initial SDL Surface ready");
                SDLActivity.nativeFocusChanged(activity.hasWindowFocus());
                try {
                    Os.setenv("DROIDBRIDGE_SDL3_PLATFORM_READY", "1", true);
                } catch (Throwable throwable) {
                    throw new IllegalStateException("Unable to publish SDL3 platform-ready marker", throwable);
                }
                append("DroidBridgeSDL3: platform ready renderer-independent surface="
                        + snapshot.renderWidth + "x" + snapshot.renderHeight
                        + " device=" + snapshot.deviceWidth + "x" + snapshot.deviceHeight
                        + " valid=" + snapshot.surface.isValid());
            } catch (Throwable throwable) {
                throw new IllegalStateException("SDL3 Android Surface bootstrap failed", throwable);
            }
        }

        // The real SDL_Window is created later by Minecraft/RenderPearl. Wait for
        // that window before repeating the initial resolution callback once.
        schedulePostWindowResolutionInitialization();
    }

    private static void schedulePostWindowResolutionInitialization() {
        final int generation;
        synchronized (LOCK) {
            if (!requested || !initialized || postWindowResolutionComplete
                    || postWindowResolutionScheduled
                    || postWindowResolutionPulseInProgress) {
                return;
            }
            postWindowResolutionScheduled = true;
            generation = postWindowResolutionGeneration;
        }

        Handler mainHandler = new Handler(Looper.getMainLooper());
        Runnable poll = new Runnable() {
            private int attempts;
            private int stableSamples;
            private int previousWidth = -1;
            private int previousHeight = -1;
            private long windowReadyAtMs;
            private long firstRenderedFrameAtMs;

            @Override
            public void run() {
                synchronized (LOCK) {
                    if (!requested || !initialized
                            || generation != postWindowResolutionGeneration
                            || postWindowResolutionComplete
                            || postWindowResolutionPulseInProgress) {
                        postWindowResolutionScheduled = false;
                        return;
                    }
                }

                float[] state = new float[9];
                boolean ready = DroidBridgeSDL3NativeWindowBridge.querySdlMouseState(state)
                        && state[2] > 1f && state[3] > 1f;
                int windowWidth = ready ? Math.max(1, Math.round(state[2])) : -1;
                int windowHeight = ready ? Math.max(1, Math.round(state[3])) : -1;

                if (ready && windowWidth == previousWidth && windowHeight == previousHeight) {
                    stableSamples++;
                } else if (ready) {
                    previousWidth = windowWidth;
                    previousHeight = windowHeight;
                    stableSamples = 1;
                    windowReadyAtMs = 0L;
                    firstRenderedFrameAtMs = 0L;
                } else {
                    stableSamples = 0;
                    windowReadyAtMs = 0L;
                    firstRenderedFrameAtMs = 0L;
                }

                if (ready && stableSamples >= 2) {
                    long now = SystemClock.uptimeMillis();
                    if (windowReadyAtMs == 0L) {
                        windowReadyAtMs = now;
                        synchronized (LOCK) {
                            if (generation == postWindowResolutionGeneration) {
                                postWindowResolutionBaselineWidth = windowWidth;
                                postWindowResolutionBaselineHeight = windowHeight;
                            }
                        }
                    }

                    Activity activity = activityRef.get();
                    int fps = activity != null
                            ? DroidBridgeSdlFpsBridge.readCurrentFps(activity) : 0;
                    boolean recentFrameSample = fps > 0 || hasRecentSdlFpsSample();
                    if (recentFrameSample && firstRenderedFrameAtMs == 0L) {
                        firstRenderedFrameAtMs = now;
                        append("DroidBridgeSDL3: startup resolution pulse observed rendering"
                                + " fps=" + fps
                                + " sampleFile=" + (sdlFpsSampleFile != null)
                                + " window=" + windowWidth + "x" + windowHeight);
                    }

                    boolean rendererSettled = firstRenderedFrameAtMs > 0L
                            && now - firstRenderedFrameAtMs >= POST_WINDOW_RENDER_SETTLE_MS;
                    boolean windowSettled = windowReadyAtMs > 0L
                            && now - windowReadyAtMs >= POST_WINDOW_RENDER_FALLBACK_MS;
                    if ((rendererSettled || windowSettled)
                            && performPostWindowResolutionInitialization(
                            generation, windowWidth, windowHeight)) {
                        return;
                    }
                }

                attempts++;
                if (attempts >= POST_WINDOW_RESOLUTION_MAX_POLLS) {
                    // A valid SDL window is enough to make one final attempt. Never
                    // leave a scaled TextureView paired with the original device-size
                    // SDL input transform simply because FPS telemetry was late.
                    if (ready && performPostWindowResolutionInitialization(
                            generation, windowWidth, windowHeight)) {
                        append("DroidBridgeSDL3: startup resolution pulse forced at poll deadline"
                                + " window=" + windowWidth + "x" + windowHeight);
                        return;
                    }
                    synchronized (LOCK) {
                        if (generation == postWindowResolutionGeneration) {
                            postWindowResolutionScheduled = false;
                        }
                    }
                    append("DroidBridgeSDL3: startup resolution pulse timed out"
                            + " attempts=" + attempts
                            + " lastWindow=" + windowWidth + "x" + windowHeight);
                    return;
                }
                mainHandler.postDelayed(this, POST_WINDOW_RESOLUTION_POLL_MS);
            }
        };
        mainHandler.postDelayed(poll, POST_WINDOW_RESOLUTION_POLL_MS);
    }

    private static boolean hasRecentSdlFpsSample() {
        File file = sdlFpsSampleFile;
        if (file == null || !file.isFile()) return false;
        long modified = file.lastModified();
        if (modified <= 0L) return false;
        long age = System.currentTimeMillis() - modified;
        return age >= 0L && age <= 3500L;
    }

    private static boolean performPostWindowResolutionInitialization(
            int generation,
            int observedWindowWidth,
            int observedWindowHeight
    ) {
        final MinecraftGLSurface minecraftSurface;
        synchronized (LOCK) {
            if (!requested || !initialized || postWindowResolutionComplete
                    || postWindowResolutionPulseInProgress
                    || generation != postWindowResolutionGeneration) {
                postWindowResolutionScheduled = false;
                return true;
            }

            Surface surface = publishedSurface;
            Activity activity = activityRef.get();
            minecraftSurface = minecraftSurfaceRef.get();
            if (surface == null || !surface.isValid() || activity == null
                    || minecraftSurface == null || !surfaceCreatedSent || hostStopped) {
                return false;
            }

            postWindowResolutionPulseInProgress = true;
            postWindowResolutionBaselineWidth = observedWindowWidth;
            postWindowResolutionBaselineHeight = observedWindowHeight;
        }

        boolean accepted;
        try {
            accepted = minecraftSurface.primeSdlStartupResolution(
                    () -> completePostWindowResolutionInitialization(
                            generation, observedWindowWidth, observedWindowHeight),
                    () -> failPostWindowResolutionInitialization(generation)
            );
        } catch (Throwable throwable) {
            accepted = false;
            append("DroidBridgeSDL3: startup resolution pulse request failed: "
                    + throwable);
        }

        if (!accepted) {
            synchronized (LOCK) {
                if (generation == postWindowResolutionGeneration) {
                    postWindowResolutionPulseInProgress = false;
                }
            }
            return false;
        }

        append("DroidBridgeSDL3: startup resolution pulse requested"
                + " observedWindow=" + observedWindowWidth + "x"
                + observedWindowHeight
                + " generation=" + generation);
        return true;
    }

    private static void completePostWindowResolutionInitialization(
            int generation,
            int observedWindowWidth,
            int observedWindowHeight
    ) {
        boolean grabbing;
        int finalWindowWidth = observedWindowWidth;
        int finalWindowHeight = observedWindowHeight;
        float[] state = new float[9];
        if (DroidBridgeSDL3NativeWindowBridge.querySdlMouseState(state)) {
            if (state[2] > 1f) finalWindowWidth = Math.max(1, Math.round(state[2]));
            if (state[3] > 1f) finalWindowHeight = Math.max(1, Math.round(state[3]));
        }

        synchronized (LOCK) {
            if (generation != postWindowResolutionGeneration || !requested) return;
            postWindowResolutionComplete = true;
            postWindowResolutionScheduled = false;
            postWindowResolutionPulseInProgress = false;
            postWindowResolutionBaselineWidth = finalWindowWidth;
            postWindowResolutionBaselineHeight = finalWindowHeight;
            grabbing = CallbackBridge.isGrabbing();
        }

        append("DroidBridgeSDL3: startup resolution pulse complete"
                + " restoredWindow=" + finalWindowWidth + "x" + finalWindowHeight
                + " observedWindow=" + observedWindowWidth + "x"
                + observedWindowHeight
                + " generation=" + generation);

        if (grabbing) {
            recenterLauncherGrabCursorSilently("startup-resolution-pulse");
        } else {
            recenterLauncherMenuCursor("startup-resolution-pulse");
        }
    }

    private static void failPostWindowResolutionInitialization(int generation) {
        synchronized (LOCK) {
            if (generation != postWindowResolutionGeneration || !requested) return;
            postWindowResolutionPulseInProgress = false;
            postWindowResolutionScheduled = false;
        }
        append("DroidBridgeSDL3: startup resolution pulse will retry"
                + " generation=" + generation);
        new Handler(Looper.getMainLooper()).postDelayed(
                DroidBridgeSDL3Bootstrap::schedulePostWindowResolutionInitialization,
                300L
        );
    }

    private static void applySnapshot4OpenGlBufferCompatibility() {
        boolean wrappedOpenGl = sdlOpenGlCompatibilityRequested;
        boolean directFreedreno = isDirectFreedrenoKgslLaunch();
        if (!wrappedOpenGl && !directFreedreno) return;

        try {
            String kind = sdlOpenGlRendererKind == null
                    ? (directFreedreno ? "freedreno_kgsl" : "unknown")
                    : sdlOpenGlRendererKind;
            boolean ltw = "ltw".equalsIgnoreCase(kind);

            /*
             * LTW implements persistent storage itself and defaults dynamic
             * storage to coherent/persistent mappings. On 26.3 the failure was
             * caused by SDL transiently clearing LTW's live wrapper context, not
             * by RenderPearl using GL_ARB_buffer_storage. Keep LTW's advertised
             * buffer capabilities intact; the native SDL3 context guard preserves
             * the live LTW context across SDL's transient clear.
             */
            Os.setenv("DROIDBRIDGE_SDL3_DISABLE_PERSISTENT_MAPPING", ltw ? "0" : "1", true);

            /* Mesa also consumes these variables before it creates the context. */
            if (directFreedreno) {
                Os.setenv("MESA_GL_VERSION_OVERRIDE", "4.3", true);
                Os.setenv("MESA_GLSL_VERSION_OVERRIDE", "430", true);
                Os.setenv("MESA_EXTENSION_OVERRIDE", SNAPSHOT4_SAFE_EXTENSION_OVERRIDE, true);
                Os.setenv("mesa_glthread", "false", true);
                Os.setenv("DROIDBRIDGE_SDL3_FREEDRENO_MUTABLE_BUFFERS", "1", true);
            }

            append("DroidBridgeSDL3: Snapshot 4 buffer compatibility enabled"
                    + " kind=" + kind
                    + " wrapped=" + wrappedOpenGl
                    + " directFreedreno=" + directFreedreno
                    + " persistentMapping=" + ltw
                    + (ltw ? " contextGuard=true" : " maxGL=4.3 extensions='"
                    + SNAPSHOT4_SAFE_EXTENSION_OVERRIDE + "'"));
        } catch (Throwable throwable) {
            throw new IllegalStateException(
                    "Unable to configure SDL3 Snapshot 4 buffer compatibility", throwable);
        }
    }

    private static boolean isDirectFreedrenoKgslLaunch() {
        String combined = (environment("DROIDBRIDGE_NATIVE_GLFW_KGSL") + " "
                + environment("DROIDBRIDGE_NATIVE_GLFW_DRIVER") + " "
                + environment("DROIDBRIDGE_RENDERER") + " "
                + environment("DROIDBRIDGE_RENDERER_MESA_MODE") + " "
                + environment("DROIDBRIDGE_MESA_MODE") + " "
                + environment("DROIDBRIDGE_MESA_DRIVER")).toLowerCase(Locale.ROOT);

        return combined.contains("freedreno_kgsl")
                || combined.contains("droidbridge_native_glfw_kgsl")
                || "kgsl".equals(environment("DROIDBRIDGE_NATIVE_GLFW_DRIVER").toLowerCase(Locale.ROOT))
                || "1".equals(environment("DROIDBRIDGE_NATIVE_GLFW_KGSL"));
    }

    @NonNull
    private static String environment(@NonNull String key) {
        String value = System.getenv(key);
        return value == null ? "" : value.trim();
    }

    private static void startInputRegistrationLocked() {
        if (inputRegistrationStarted) return;
        inputRegistrationStarted = true;

        Handler mainHandler = new Handler(Looper.getMainLooper());
        Runnable initializeInput = () -> {
            if (!isInputReady()) return;
            try {
                SDLControllerManager.initialize();

                /*
                 * The stock SDL Android activity normally initializes the joystick,
                 * gamepad and event subsystems before polling Android InputDevices.
                 * DroidBridge embeds SDL instead, so do that explicitly once. The
                 * native helper also disables HIDAPI to avoid duplicate Android/HID
                 * registrations for the same physical controller.
                 */
                try {
                    Tools.SDL.initializeControllerSubsystems();
                } catch (Throwable subsystemError) {
                    append("DroidBridgeSDL3: controller subsystem helper unavailable: "
                            + subsystemError.getClass().getSimpleName() + ": "
                            + subsystemError.getMessage());
                }

                SDLControllerManager.pollInputDevices();
                SDLActivity.initTouch();
                append("DroidBridgeSDL3: Android SDL input devices registered; launcher gamepad ownership="
                        + (!MinecraftGLSurface.sdlEnabled));
            } catch (Throwable throwable) {
                /*
                 * Do not disable the SDL platform when one device poll fails. A later
                 * hot-plug/delayed poll may still succeed, and keyboard/touch routing
                 * remains valid independently of gamepad discovery.
                 */
                append("DroidBridgeSDL3: Android SDL input registration failed: " + throwable);
            }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            initializeInput.run();
        } else {
            mainHandler.post(initializeInput);
        }

        mainHandler.postDelayed(DroidBridgeSDL3Bootstrap::pollControllersSafely, 500L);
        mainHandler.postDelayed(DroidBridgeSDL3Bootstrap::pollControllersSafely, 1500L);
        mainHandler.postDelayed(DroidBridgeSDL3Bootstrap::pollControllersSafely, 4000L);
    }

    private static void pollControllersSafely() {
        if (!isInputReady()) return;
        try {
            SDLControllerManager.initialize();
            SDLControllerManager.pollInputDevices();
        } catch (Throwable throwable) {
            append("DroidBridgeSDL3: delayed controller poll failed: " + throwable);
        }
    }

    /** Route Android keyboard events into SDL. Controller events remain with the
     * launcher mapper unless Controlify explicitly owns SDL controller routing. */
    public static boolean routeKeyEvent(@NonNull KeyEvent event) {
        if (!isInputReady()) return false;
        if (isControllerKeyEvent(event) && !MinecraftGLSurface.sdlEnabled) {
            return false;
        }
        try {
            if (MinecraftGLSurface.sdlEnabled) {
                SDLControllerManager.initialize();
            }
            return SDLActivity.handleKeyEvent(null, event.getKeyCode(), event, null);
        } catch (Throwable throwable) {
            append("DroidBridgeSDL3: key routing failed: " + throwable);
            return false;
        }
    }

    private static boolean isControllerKeyEvent(@NonNull KeyEvent event) {
        int source = event.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD) {
            return true;
        }
        int keyCode = event.getKeyCode();
        return keyCode >= KeyEvent.KEYCODE_BUTTON_A && keyCode <= KeyEvent.KEYCODE_BUTTON_16;
    }

    /**
     * Route Android joystick axes only when Controlify owns them.
     *
     * Physical pointer events must continue through Android's normal View dispatch
     * until MinecraftGLSurface receives them. Activity.dispatchGenericMotionEvent()
     * coordinates are decor-window coordinates; consuming them here bypassed the
     * render-view-local coordinate conversion and produced the large, repeatable
     * menu-cursor offset visible on the Odin 3. MinecraftGLSurface is the single
     * owner of mouse hover, button and wheel conversion for SDL3.
     */
    public static boolean routeGenericMotionEvent(@NonNull MotionEvent event) {
        if (!isInputReady()) return false;
        try {
            int source = event.getSource();
            if ((source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                    || (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD) {
                if (!MinecraftGLSurface.sdlEnabled) return false;
                SDLControllerManager.initialize();
                return SDLControllerManager.handleJoystickMotionEvent(event);
            }

            if ((source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                    || (source & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
                    || (source & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) {
                return false;
            }
        } catch (Throwable throwable) {
            append("DroidBridgeSDL3: generic motion routing failed: " + throwable);
        }
        return false;
    }

    /**
     * Mirrors the stock SDLSurface touch conversion while reusing DroidBridge's
     * retained Android render view. Finger coordinates are normalized for SDL
     * touch events; physical mouse streams are delegated to MinecraftGLSurface.
     */
    public static boolean routeTouchEvent(
            @NonNull MotionEvent event,
            int viewWidth,
            int viewHeight
    ) {
        if (!isInputReady()) return false;

        final int width = Math.max(1, viewWidth);
        final int height = Math.max(1, viewHeight);
        final int pointerCount = event.getPointerCount();

        // Android can deliver a physical mouse through dispatchTouchEvent rather
        // than dispatchGenericMotionEvent. Do not consume that stream at the
        // Activity/bootstrap level; MinecraftGLSurface needs the View-local x/y
        // values and owns mouse button de-duplication, pointer capture and scroll.
        int source = event.getSource();
        if ((source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || (source & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
                || (source & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) {
            return false;
        }
        boolean penOnlyStream = pointerCount > 0;
        for (int i = 0; i < pointerCount; i++) {
            int toolType = event.getToolType(i);
            if (toolType == MotionEvent.TOOL_TYPE_MOUSE) return false;
            if (toolType != MotionEvent.TOOL_TYPE_STYLUS
                    && toolType != MotionEvent.TOOL_TYPE_ERASER) {
                penOnlyStream = false;
            }
        }

        /*
         * Do not feed ordinary fingers into SDL's native touch path. That path
         * activates menu widgets directly but never updates CallbackBridge.mouseX/Y,
         * so the visible launcher cursor stays behind the finger. More importantly,
         * Back to Game can switch SDL to relative mode while the same Android finger
         * stream is still down, turning the remaining MOVE/UP into a camera jump.
         *
         * Let MinecraftGLSurface's established mouse-style touch path handle fingers:
         * it moves the visible cursor on DOWN/MOVE, clicks on ACTION_UP, and clears the
         * touch stream before Minecraft re-grabs the mouse. Keep native SDL pen routing
         * for stylus/eraser input only.
         */
        if (!penOnlyStream) return false;

        int action = event.getActionMasked();
        int firstIndex = 0;
        int lastIndex = pointerCount;

        if (action == MotionEvent.ACTION_POINTER_UP
                || action == MotionEvent.ACTION_POINTER_DOWN) {
            firstIndex = event.getActionIndex();
            lastIndex = firstIndex + 1;
        }

        try {
            for (int i = firstIndex; i < lastIndex; i++) {
                int toolType = event.getToolType(i);
                float mappedX = mapPhysicalVisualToSdlX(event.getX(i), event.getY(i));
                float mappedY = mapPhysicalVisualToSdlY(event.getX(i), event.getY(i));
                if (toolType == MotionEvent.TOOL_TYPE_MOUSE) {
                    SDLActivity.onNativeMouse(
                            event.getButtonState(),
                            action,
                            mappedX,
                            mappedY,
                            false
                    );
                } else if (toolType == MotionEvent.TOOL_TYPE_STYLUS
                        || toolType == MotionEvent.TOOL_TYPE_ERASER) {
                    float pressure = Math.min(1.0f, event.getPressure(i));
                    int buttonState = (event.getButtonState() >> 4)
                            | (1 << (toolType == MotionEvent.TOOL_TYPE_STYLUS ? 0 : 30));
                    SDLActivity.onNativePen(
                            event.getPointerId(i),
                            buttonState,
                            action,
                            mappedX,
                            mappedY,
                            pressure
                    );
                } else {
                    float pressure = Math.min(1.0f, event.getPressure(i));
                    SDLActivity.onNativeTouch(
                            event.getDeviceId(),
                            event.getPointerId(i),
                            action,
                            mappedX / (float) Math.max(1, visualRenderWidth),
                            mappedY / (float) Math.max(1, visualRenderHeight),
                            pressure
                    );
                }
            }
            return true;
        } catch (Throwable throwable) {
            append("DroidBridgeSDL3: touch routing failed: " + throwable);
            return false;
        }
    }

    /**
     * Keeps the coordinate bridge in ordinary Android landscape coordinates. The
     * live SurfaceView is never rotated; Vulkan swapchain preTransform is forced to
     * identity when the Android surface reports that transform as supported.
     */
    public static void configureVisualQuarterTurn(
            int degrees,
            int viewWidth,
            int viewHeight,
            int renderWidth,
            int renderHeight
    ) {
        configureVisualLayout(0, 0, viewWidth, viewHeight, renderWidth, renderHeight);
    }

    /**
     * Records the actual Android content rectangle occupied by Minecraft's live
     * SDL SurfaceView. The launcher cursor is a normal Android overlay, while SDL
     * consumes coordinates in the render/window space. Keeping the exact content
     * rectangle here prevents the cursor from being drawn relative to the larger
     * activity root when the game surface is inset or temporarily letterboxed.
     */
    public static void configureVisualLayout(
            int viewLeft,
            int viewTop,
            int viewWidth,
            int viewHeight,
            int renderWidth,
            int renderHeight
    ) {
        int safeLeft = Math.max(0, viewLeft);
        int safeTop = Math.max(0, viewTop);
        int safeViewWidth = Math.max(1, viewWidth);
        int safeViewHeight = Math.max(1, viewHeight);
        int safeRenderWidth = Math.max(1, renderWidth);
        int safeRenderHeight = Math.max(1, renderHeight);
        boolean changed = visualQuarterTurnDegrees != 0
                || safeLeft != visualViewLeft
                || safeTop != visualViewTop
                || safeViewWidth != visualViewWidth
                || safeViewHeight != visualViewHeight
                || safeRenderWidth != visualRenderWidth
                || safeRenderHeight != visualRenderHeight;
        visualQuarterTurnDegrees = 0;
        visualViewLeft = safeLeft;
        visualViewTop = safeTop;
        visualViewWidth = safeViewWidth;
        visualViewHeight = safeViewHeight;
        visualRenderWidth = safeRenderWidth;
        visualRenderHeight = safeRenderHeight;
        if (changed && safeViewWidth > 1 && safeViewHeight > 1) {
            append("DroidBridgeSDL3: stable landscape coordinates content="
                    + safeViewWidth + "x" + safeViewHeight
                    + "@" + safeLeft + "," + safeTop
                    + " render=" + safeRenderWidth + "x" + safeRenderHeight
                    + " viewTransform=none");
        }
    }

    public static int getVisualRenderWidth() {
        return Math.max(1, visualRenderWidth);
    }

    public static int getVisualRenderHeight() {
        return Math.max(1, visualRenderHeight);
    }

    public static float getSdlContentScale() {
        return Math.max(1.0f, sdlContentScale);
    }

    public static int getSdlLogicalWidth() {
        // SDL Android forwards mouse x/y in Surface pixels. Density affects SDL's
        // display scale, not the coordinate units accepted by onNativeMouse().
        return getVisualRenderWidth();
    }

    public static int getSdlLogicalHeight() {
        return getVisualRenderHeight();
    }

    /**
     * Keep the launcher cursor in the visible Android game-view coordinate space.
     * The SDL window/backing buffer may be smaller when resolution scaling is used,
     * but that must never shrink or reposition the software cursor itself. Absolute
     * events are normalized from this stable visual space into the live SDL window
     * inside sendAbsoluteSdlMouseLocked().
     */
    public static int getSdlCursorCoordinateWidth() {
        int width = visualViewWidth;
        if (width > 1) return width;
        width = CallbackBridge.physicalWidth;
        return width > 1 ? width : getSdlLogicalWidth();
    }

    public static int getSdlCursorCoordinateHeight() {
        int height = visualViewHeight;
        if (height > 1) return height;
        height = CallbackBridge.physicalHeight;
        return height > 1 ? height : getSdlLogicalHeight();
    }

    public static float scaleVirtualCursorDeltaX(float physicalDelta) {
        return physicalDelta * getSdlLogicalWidth() / (float) Math.max(1, getVisualRenderWidth());
    }

    public static float scaleVirtualCursorDeltaY(float physicalDelta) {
        return physicalDelta * getSdlLogicalHeight() / (float) Math.max(1, getVisualRenderHeight());
    }

    /**
     * Resolves the real SDL render-view bounds relative to the Android overlay
     * parent. This is deliberately based on the live SurfaceView location rather
     * than the activity root or the selected resolution profile.
     */
    public static boolean resolveCursorOverlayBounds(
            @NonNull View overlayParent,
            @NonNull RectF outBounds
    ) {
        MinecraftGLSurface surface = minecraftSurfaceRef.get();
        if (!requested || surface == null) return false;

        Rect screenBounds = new Rect();
        if (!surface.resolveSdlVisualBoundsOnScreen(screenBounds)) return false;

        int[] parentLocation = new int[2];
        try {
            overlayParent.getLocationOnScreen(parentLocation);
        } catch (Throwable ignored) {
            return false;
        }

        int left = screenBounds.left - parentLocation[0];
        int top = screenBounds.top - parentLocation[1];
        int width = screenBounds.width();
        int height = screenBounds.height();
        outBounds.set(left, top, left + width, top + height);

        if (width > 1 && height > 1
                && (left != lastLoggedOverlayLeft
                || top != lastLoggedOverlayTop
                || width != lastLoggedOverlayWidth
                || height != lastLoggedOverlayHeight)) {
            lastLoggedOverlayLeft = left;
            lastLoggedOverlayTop = top;
            lastLoggedOverlayWidth = width;
            lastLoggedOverlayHeight = height;
            append("DroidBridgeSDL3: cursor surface bounds="
                    + width + "x" + height + "@" + left + "," + top
                    + " logical=" + getSdlLogicalWidth() + "x" + getSdlLogicalHeight()
                    + " pixels=" + getVisualRenderWidth() + "x" + getVisualRenderHeight()
                    + " scale=" + getSdlContentScale()
                    + " overlay=" + overlayParent.getWidth() + "x" + overlayParent.getHeight());
        }
        return width > 1 && height > 1;
    }

    private static float clamp(float value, float min, float max) {
        if (Float.isNaN(value)) return min;
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static float mapPhysicalVisualToSdlX(float x, float y) {
        float viewWidth = Math.max(1, visualViewWidth);
        float logicalWidth = Math.max(1, getSdlLogicalWidth());
        float localX = x - visualViewLeft;
        return clamp(logicalWidth * (localX / viewWidth), 0f, Math.max(0f, logicalWidth - 1f));
    }

    private static float mapPhysicalVisualToSdlY(float x, float y) {
        float viewHeight = Math.max(1, visualViewHeight);
        float logicalHeight = Math.max(1, getSdlLogicalHeight());
        float localY = y - visualViewTop;
        return clamp(logicalHeight * (localY / viewHeight), 0f, Math.max(0f, logicalHeight - 1f));
    }

    private static float mapVirtualVisualToSdlX(float x, float y) {
        return clamp(x, 0f, Math.max(0f, getSdlCursorCoordinateWidth() - 1f));
    }

    private static float mapVirtualVisualToSdlY(float x, float y) {
        return clamp(y, 0f, Math.max(0f, getSdlCursorCoordinateHeight() - 1f));
    }

    private static float mapEdgeToEdge(float value, float sourceSize, float targetSize) {
        float safeSource = Math.max(1f, sourceSize);
        float safeTarget = Math.max(1f, targetSize);
        if (safeSource <= 1f || safeTarget <= 1f) return 0f;
        float sourceMax = safeSource - 1f;
        float targetMax = safeTarget - 1f;
        return clamp(value, 0f, sourceMax) * targetMax / sourceMax;
    }

    /**
     * Sends one absolute launcher-cursor event in SDL's own window-coordinate
     * space, then verifies SDL's synchronous cursor cache. The public SDL query is
     * resolved from the already-loaded libSDL3 image because Minecraft loads SDL
     * in the embedded OpenJDK linker namespace while this code runs in ART.
     */
    private static void sendAbsoluteSdlMouseLocked(
            int buttonState,
            int action,
            float visualX,
            float visualY
    ) {
        float cursorWidth = Math.max(1f, getSdlCursorCoordinateWidth());
        float cursorHeight = Math.max(1f, getSdlCursorCoordinateHeight());
        float sdlWindowWidth = Math.max(1f, getSdlLogicalWidth());
        float sdlWindowHeight = Math.max(1f, getSdlLogicalHeight());
        float sdlPixelWidth = Math.max(1f, getVisualRenderWidth());
        float sdlPixelHeight = Math.max(1f, getVisualRenderHeight());
        float sdlDisplayScale = getSdlContentScale();
        float sdlPixelDensity = 1f;

        boolean queriedBefore = DroidBridgeSDL3NativeWindowBridge
                .querySdlMouseState(SDL_MOUSE_STATE);
        if (queriedBefore) {
            if (SDL_MOUSE_STATE[2] > 1f) sdlWindowWidth = SDL_MOUSE_STATE[2];
            if (SDL_MOUSE_STATE[3] > 1f) sdlWindowHeight = SDL_MOUSE_STATE[3];
            if (SDL_MOUSE_STATE[4] > 1f) sdlPixelWidth = SDL_MOUSE_STATE[4];
            if (SDL_MOUSE_STATE[5] > 1f) sdlPixelHeight = SDL_MOUSE_STATE[5];
            if (SDL_MOUSE_STATE[7] > 0f) sdlDisplayScale = SDL_MOUSE_STATE[7];
            if (SDL_MOUSE_STATE[8] > 0f) sdlPixelDensity = SDL_MOUSE_STATE[8];
        }

        float requestedVisualX = clamp(visualX, 0f, Math.max(0f, cursorWidth - 1f));
        float requestedVisualY = clamp(visualY, 0f, Math.max(0f, cursorHeight - 1f));
        float effectiveWindowX = mapEdgeToEdge(
                requestedVisualX, cursorWidth, sdlWindowWidth);
        float effectiveWindowY = mapEdgeToEdge(
                requestedVisualY, cursorHeight, sdlWindowHeight);

        // SDL's Android backend accepts absolute mouse coordinates in window pixels.
        // Display/content scale is a rendering metric and must not be applied to the
        // mouse position. The only required conversion is the normalized visual-view
        // coordinate above into the live SDL window coordinate below.
        float sentSdlX = clamp(
                effectiveWindowX,
                0f,
                Math.max(0f, sdlWindowWidth - 1f)
        );
        float sentSdlY = clamp(
                effectiveWindowY,
                0f,
                Math.max(0f, sdlWindowHeight - 1f)
        );

        // The compatible SDL path uses ACTION_MOVE for launcher-generated absolute motion.
        // SDL's Android backend accepts both MOVE and HOVER_MOVE, but MOVE also
        // guarantees the normal window mouse-motion path used by menu hover state.
        int resolvedAction = action == MotionEvent.ACTION_HOVER_MOVE
                ? MotionEvent.ACTION_MOVE : action;
        SDLActivity.onNativeMouse(
                buttonState,
                resolvedAction,
                sentSdlX,
                sentSdlY,
                false
        );

        boolean queriedAfter = DroidBridgeSDL3NativeWindowBridge
                .querySdlMouseState(SDL_MOUSE_STATE);
        float actualSdlX = sentSdlX;
        float actualSdlY = sentSdlY;
        if (queriedAfter) {
            if (SDL_MOUSE_STATE[2] > 1f) sdlWindowWidth = SDL_MOUSE_STATE[2];
            if (SDL_MOUSE_STATE[3] > 1f) sdlWindowHeight = SDL_MOUSE_STATE[3];
            if (SDL_MOUSE_STATE[4] > 1f) sdlPixelWidth = SDL_MOUSE_STATE[4];
            if (SDL_MOUSE_STATE[5] > 1f) sdlPixelHeight = SDL_MOUSE_STATE[5];
            if (SDL_MOUSE_STATE[7] > 0f) sdlDisplayScale = SDL_MOUSE_STATE[7];
            if (SDL_MOUSE_STATE[8] > 0f) sdlPixelDensity = SDL_MOUSE_STATE[8];
            actualSdlX = SDL_MOUSE_STATE[0];
            actualSdlY = SDL_MOUSE_STATE[1];

            // Some SDL builds defer the Android event until the next pump. If its
            // synchronous cache still disagrees, update the exact active window once.
            // This runs only in absolute/menu mode and does not touch TextureView,
            // ANativeWindow, Vulkan, startup, pause/resume, or surface ownership.
            if (Math.abs(actualSdlX - sentSdlX) > 1.25f
                    || Math.abs(actualSdlY - sentSdlY) > 1.25f) {
                if (DroidBridgeSDL3NativeWindowBridge.warpSdlMouse(sentSdlX, sentSdlY)
                        && DroidBridgeSDL3NativeWindowBridge
                        .querySdlMouseState(SDL_MOUSE_STATE)) {
                    if (SDL_MOUSE_STATE[2] > 1f) sdlWindowWidth = SDL_MOUSE_STATE[2];
                    if (SDL_MOUSE_STATE[3] > 1f) sdlWindowHeight = SDL_MOUSE_STATE[3];
                    if (SDL_MOUSE_STATE[4] > 1f) sdlPixelWidth = SDL_MOUSE_STATE[4];
                    if (SDL_MOUSE_STATE[5] > 1f) sdlPixelHeight = SDL_MOUSE_STATE[5];
                    if (SDL_MOUSE_STATE[7] > 0f) sdlDisplayScale = SDL_MOUSE_STATE[7];
                    if (SDL_MOUSE_STATE[8] > 0f) sdlPixelDensity = SDL_MOUSE_STATE[8];
                    actualSdlX = SDL_MOUSE_STATE[0];
                    actualSdlY = SDL_MOUSE_STATE[1];
                }
            }
        }

        // Convert SDL's verified raw window-pixel position back into the same stable
        // visual-view cursor space. This inverse normalized mapping stays correct at
        // 100%, 50%, and custom render scales without any density/content-scale math.
        float actualEffectiveWindowX = clamp(
                actualSdlX,
                0f,
                Math.max(0f, sdlWindowWidth - 1f)
        );
        float actualEffectiveWindowY = clamp(
                actualSdlY,
                0f,
                Math.max(0f, sdlWindowHeight - 1f)
        );
        float synchronizedVisualX = mapEdgeToEdge(
                actualEffectiveWindowX, sdlWindowWidth, cursorWidth);
        float synchronizedVisualY = mapEdgeToEdge(
                actualEffectiveWindowY, sdlWindowHeight, cursorHeight);
        CallbackBridge.setCursorPosSilently(synchronizedVisualX, synchronizedVisualY);

        int roundedWindowWidth = Math.max(1, Math.round(sdlWindowWidth));
        int roundedWindowHeight = Math.max(1, Math.round(sdlWindowHeight));
        int roundedPixelWidth = Math.max(1, Math.round(sdlPixelWidth));
        int roundedPixelHeight = Math.max(1, Math.round(sdlPixelHeight));
        long now = SystemClock.uptimeMillis();
        boolean geometryChanged = roundedWindowWidth != lastSdlMouseWindowWidth
                || roundedWindowHeight != lastSdlMouseWindowHeight
                || roundedPixelWidth != lastSdlMousePixelWidth
                || roundedPixelHeight != lastSdlMousePixelHeight;
        boolean meaningfulMismatch = Math.abs(actualSdlX - sentSdlX) > 1.25f
                || Math.abs(actualSdlY - sentSdlY) > 1.25f;
        if (InputEventDiagnosticLogger.isEnabled()
                && (geometryChanged || meaningfulMismatch
                || now - lastSdlMouseSyncLogUptimeMs >= 1500L)) {
            lastSdlMouseSyncLogUptimeMs = now;
            lastSdlMouseWindowWidth = roundedWindowWidth;
            lastSdlMouseWindowHeight = roundedWindowHeight;
            lastSdlMousePixelWidth = roundedPixelWidth;
            lastSdlMousePixelHeight = roundedPixelHeight;
            append("DroidBridgeSDL3MouseSync: visual="
                    + requestedVisualX + "," + requestedVisualY
                    + " cursor=" + Math.round(cursorWidth) + "x" + Math.round(cursorHeight)
                    + " sentRaw=" + sentSdlX + "," + sentSdlY
                    + " actualRaw=" + actualSdlX + "," + actualSdlY
                    + " effective=" + actualEffectiveWindowX + "," + actualEffectiveWindowY
                    + " window=" + roundedWindowWidth + "x" + roundedWindowHeight
                    + " pixels=" + roundedPixelWidth + "x" + roundedPixelHeight
                    + " displayScale=" + sdlDisplayScale
                    + " pixelDensity=" + sdlPixelDensity
                    + " normalizedVisual=true"
                    + " contentScaleIgnored=true"
                    + " queried=" + queriedAfter
                    + " action=" + resolvedAction);
        }
    }

    public static boolean routeVirtualCursor(float visualX, float visualY) {
        if (!isInputReady()) return false;
        try {
            if (CallbackBridge.isGrabbing()) {
                float deltaX;
                float deltaY;
                synchronized (VIRTUAL_MOUSE_LOCK) {
                    if (!virtualCursorBaselineValid) {
                        lastVirtualCursorX = visualX;
                        lastVirtualCursorY = visualY;
                        virtualCursorBaselineValid = true;
                        return true;
                    }
                    float visualDeltaX = visualX - lastVirtualCursorX;
                    float visualDeltaY = visualY - lastVirtualCursorY;
                    lastVirtualCursorX = visualX;
                    lastVirtualCursorY = visualY;

                    float renderWidth = Math.max(1f, visualRenderWidth);
                    float renderHeight = Math.max(1f, visualRenderHeight);
                    if (visualQuarterTurnDegrees > 0) {
                        deltaX = visualDeltaY * (renderWidth / renderHeight);
                        deltaY = -visualDeltaX * (renderHeight / renderWidth);
                    } else if (visualQuarterTurnDegrees < 0) {
                        deltaX = -visualDeltaY * (renderWidth / renderHeight);
                        deltaY = visualDeltaX * (renderHeight / renderWidth);
                    } else {
                        deltaX = visualDeltaX;
                        deltaY = visualDeltaY;
                    }
                }

                SDLActivity.onNativeMouse(
                        virtualMouseButtonState,
                        MotionEvent.ACTION_HOVER_MOVE,
                        deltaX,
                        deltaY,
                        true
                );
                return true;
            }

            synchronized (VIRTUAL_MOUSE_LOCK) {
                virtualCursorBaselineValid = false;
                sendAbsoluteSdlMouseLocked(
                        virtualMouseButtonState,
                        MotionEvent.ACTION_MOVE,
                        mapVirtualVisualToSdlX(visualX, visualY),
                        mapVirtualVisualToSdlY(visualX, visualY)
                );
            }
            return true;
        } catch (Throwable throwable) {
            append("DroidBridgeSDL3: virtual cursor routing failed: " + throwable);
            return false;
        }
    }

    /** Called by SDLActivity when Minecraft enables or disables relative mouse mode. */
    public static void onSdlRelativeMouseChanged(boolean enabled) {
        synchronized (VIRTUAL_MOUSE_LOCK) {
            virtualCursorBaselineValid = false;
        }

        // SDL is the authority for menu/game cursor mode in Snapshot 4+. Always
        // center the launcher cache at the same transition, even when the Boolean
        // state did not change (the initial main menu starts in absolute mode and
        // therefore may not trigger a normal GrabListener edge).
        CallbackBridge.setSdlGrabState(enabled);
        recenterLauncherCursorForMode(enabled, enabled ? "relative-mode" : "menu-mode");

        // SDL hides Android's hardware pointer while relative mouse mode is active.
        // Several Android SDL3/device combinations do not restore the PointerIcon
        // when Minecraft opens a GUI again, leaving a fully functional but invisible
        // mouse. Re-assert the normal arrow whenever SDL returns to absolute mode.
        if (!enabled) {
            Activity activity = activityRef.get();
            Runnable restorePointer = () -> {
                try {
                    SDLActivity.setSystemCursor(0); // SDL_SYSTEM_CURSOR_ARROW
                } catch (Throwable throwable) {
                    if (InputEventDiagnosticLogger.isEnabled()) {
                        append("DroidBridgeSDL3: unable to restore Android menu pointer: "
                                + throwable);
                    }
                }
            };
            if (activity != null) {
                activity.runOnUiThread(restorePointer);
            } else {
                new Handler(Looper.getMainLooper()).post(restorePointer);
            }
        }
        if (InputEventDiagnosticLogger.isEnabled()) {
            append("DroidBridgeSDL3: SDL relative mouse=" + enabled
                    + " launcherGrab=" + CallbackBridge.isGrabbing());
        }
    }

    public static void recenterLauncherMenuCursor(@NonNull String reason) {
        recenterLauncherCursorForMode(false, reason);
    }

    public static void recenterLauncherGrabCursorSilently(@NonNull String reason) {
        recenterLauncherCursorForMode(true, reason);
    }

    private static void recenterLauncherCursorForMode(
            boolean relativeMode,
            @NonNull String reason
    ) {
        float centerX = Math.max(0f, getSdlCursorCoordinateWidth() - 1f) / 2f;
        float centerY = Math.max(0f, getSdlCursorCoordinateHeight() - 1f) / 2f;
        CallbackBridge.setInputReady(true);
        if (relativeMode) {
            CallbackBridge.setCursorPosSilently(centerX, centerY);
        } else {
            CallbackBridge.sendCursorPos(centerX, centerY);
        }
        if (InputEventDiagnosticLogger.isEnabled()) {
            append("DroidBridgeSDL3: cursor recentered reason=" + reason
                    + " mode=" + (relativeMode ? "relative-silent" : "menu-absolute")
                    + " target=" + centerX + "," + centerY
                    + " cursor=" + getSdlCursorCoordinateWidth() + "x"
                    + getSdlCursorCoordinateHeight()
                    + " render=" + getSdlLogicalWidth() + "x" + getSdlLogicalHeight());
        }
    }

    /** Virtual mouse click generated by DroidBridge's controller mapper. */
    public static boolean routeVirtualMouseButton(int glfwButton, boolean down) {
        return routeVirtualMouseButton(
                glfwButton, down, CallbackBridge.mouseX, CallbackBridge.mouseY);
    }

    /** Virtual mouse click generated by DroidBridge's controller mapper. */
    public static boolean routeVirtualMouseButton(
            int glfwButton, boolean down, float visualX, float visualY) {
        if (!isInputReady()) return false;
        int androidMask;
        switch (glfwButton) {
            case 0:
                androidMask = MotionEvent.BUTTON_PRIMARY;
                break;
            case 1:
                androidMask = MotionEvent.BUTTON_SECONDARY;
                break;
            case 2:
                androidMask = MotionEvent.BUTTON_TERTIARY;
                break;
            case 3:
                androidMask = MotionEvent.BUTTON_BACK;
                break;
            case 4:
                androidMask = MotionEvent.BUTTON_FORWARD;
                break;
            default:
                return false;
        }

        try {
            synchronized (VIRTUAL_MOUSE_LOCK) {
                if (down) {
                    virtualMouseButtonState |= androidMask;
                } else {
                    virtualMouseButtonState &= ~androidMask;
                }
                if (CallbackBridge.isGrabbing()) {
                    // A controller click in SDL relative mode must not carry an
                    // absolute cursor coordinate. Snapshot 4 interprets that hidden
                    // coordinate as camera motion, which is why R2 could snap the
                    // player's pitch from the ground to the horizon. Send a zero
                    // relative delta with the button transition instead.
                    SDLActivity.onNativeMouse(
                            virtualMouseButtonState,
                            down ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP,
                            0.0f,
                            0.0f,
                            true
                    );
                } else {
                    sendAbsoluteSdlMouseLocked(
                            virtualMouseButtonState,
                            down ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP,
                            mapVirtualVisualToSdlX(visualX, visualY),
                            mapVirtualVisualToSdlY(visualX, visualY)
                    );
                }
            }
            return true;
        } catch (Throwable throwable) {
            append("DroidBridgeSDL3: virtual mouse button routing failed: " + throwable);
            return false;
        }
    }

    public static boolean routeVirtualScroll(double xOffset, double yOffset) {
        if (!isInputReady()) return false;
        try {
            SDLActivity.onNativeMouse(
                    virtualMouseButtonState,
                    MotionEvent.ACTION_SCROLL,
                    (float) xOffset,
                    (float) yOffset,
                    false
            );
            return true;
        } catch (Throwable throwable) {
            append("DroidBridgeSDL3: virtual scroll routing failed: " + throwable);
            return false;
        }
    }

    public static boolean routeVirtualKey(int glfwKeyCode, boolean down) {
        if (!isInputReady()) return false;
        int androidKeyCode = androidKeyCodeForGlfw(glfwKeyCode);
        if (androidKeyCode == KeyEvent.KEYCODE_UNKNOWN) return false;
        try {
            if (down) {
                SDLActivity.onNativeKeyDown(androidKeyCode);
            } else {
                SDLActivity.onNativeKeyUp(androidKeyCode);
            }
            return true;
        } catch (Throwable throwable) {
            append("DroidBridgeSDL3: virtual key routing failed: " + throwable);
            return false;
        }
    }

    /**
     * Routes text committed by DroidBridge's hidden Android IME into SDL_TEXT_INPUT.
     * Snapshot 4+ no longer consumes the legacy GLFW character callback, which is why
     * Backspace worked while ordinary world-name text was silently dropped.
     */
    public static boolean routeVirtualText(@Nullable CharSequence text) {
        if (!isInputReady() || text == null || text.length() == 0) return false;
        try {
            SDLInputConnection.nativeCommitText(text.toString(), 1);
            return true;
        } catch (Throwable throwable) {
            append("DroidBridgeSDL3: virtual text routing failed: " + throwable);
            return false;
        }
    }

    private static int androidKeyCodeForGlfw(int key) {
        if (key >= 65 && key <= 90) return KeyEvent.KEYCODE_A + (key - 65);
        if (key >= 48 && key <= 57) return KeyEvent.KEYCODE_0 + (key - 48);
        switch (key) {
            case 32: return KeyEvent.KEYCODE_SPACE;
            case 39: return KeyEvent.KEYCODE_APOSTROPHE;
            case 44: return KeyEvent.KEYCODE_COMMA;
            case 45: return KeyEvent.KEYCODE_MINUS;
            case 46: return KeyEvent.KEYCODE_PERIOD;
            case 47: return KeyEvent.KEYCODE_SLASH;
            case 59: return KeyEvent.KEYCODE_SEMICOLON;
            case 61: return KeyEvent.KEYCODE_EQUALS;
            case 91: return KeyEvent.KEYCODE_LEFT_BRACKET;
            case 92: return KeyEvent.KEYCODE_BACKSLASH;
            case 93: return KeyEvent.KEYCODE_RIGHT_BRACKET;
            case 96: return KeyEvent.KEYCODE_GRAVE;
            case 256: return KeyEvent.KEYCODE_ESCAPE;
            case 257: return KeyEvent.KEYCODE_ENTER;
            case 258: return KeyEvent.KEYCODE_TAB;
            case 259: return KeyEvent.KEYCODE_DEL;
            case 260: return KeyEvent.KEYCODE_INSERT;
            case 261: return KeyEvent.KEYCODE_FORWARD_DEL;
            case 262: return KeyEvent.KEYCODE_DPAD_RIGHT;
            case 263: return KeyEvent.KEYCODE_DPAD_LEFT;
            case 264: return KeyEvent.KEYCODE_DPAD_DOWN;
            case 265: return KeyEvent.KEYCODE_DPAD_UP;
            case 266: return KeyEvent.KEYCODE_PAGE_UP;
            case 267: return KeyEvent.KEYCODE_PAGE_DOWN;
            case 268: return KeyEvent.KEYCODE_MOVE_HOME;
            case 269: return KeyEvent.KEYCODE_MOVE_END;
            case 280: return KeyEvent.KEYCODE_CAPS_LOCK;
            case 281: return KeyEvent.KEYCODE_SCROLL_LOCK;
            case 282: return KeyEvent.KEYCODE_NUM_LOCK;
            case 283: return KeyEvent.KEYCODE_SYSRQ;
            case 284: return KeyEvent.KEYCODE_BREAK;
            case 290: return KeyEvent.KEYCODE_F1;
            case 291: return KeyEvent.KEYCODE_F2;
            case 292: return KeyEvent.KEYCODE_F3;
            case 293: return KeyEvent.KEYCODE_F4;
            case 294: return KeyEvent.KEYCODE_F5;
            case 295: return KeyEvent.KEYCODE_F6;
            case 296: return KeyEvent.KEYCODE_F7;
            case 297: return KeyEvent.KEYCODE_F8;
            case 298: return KeyEvent.KEYCODE_F9;
            case 299: return KeyEvent.KEYCODE_F10;
            case 300: return KeyEvent.KEYCODE_F11;
            case 301: return KeyEvent.KEYCODE_F12;
            case 340: return KeyEvent.KEYCODE_SHIFT_LEFT;
            case 341: return KeyEvent.KEYCODE_CTRL_LEFT;
            case 342: return KeyEvent.KEYCODE_ALT_LEFT;
            case 343: return KeyEvent.KEYCODE_META_LEFT;
            case 344: return KeyEvent.KEYCODE_SHIFT_RIGHT;
            case 345: return KeyEvent.KEYCODE_CTRL_RIGHT;
            case 346: return KeyEvent.KEYCODE_ALT_RIGHT;
            case 347: return KeyEvent.KEYCODE_META_RIGHT;
            case 348: return KeyEvent.KEYCODE_MENU;
            default: return KeyEvent.KEYCODE_UNKNOWN;
        }
    }

    public static void resizeSurface(
            int renderWidth,
            int renderHeight,
            int deviceWidth,
            int deviceHeight
    ) {
        if (!requested || !initialized) return;
        Activity activity = activityRef.get();
        if (activity == null) return;
        synchronized (LOCK) {
            Surface surface = publishedSurface;
            if (surface == null || !surface.isValid()) return;

            int safeRenderWidth = Math.max(1, renderWidth);
            int safeRenderHeight = Math.max(1, renderHeight);
            int safeDeviceWidth = Math.max(1, deviceWidth);
            int safeDeviceHeight = Math.max(1, deviceHeight);

            // GameActivity used to resend the same SDL resize three times on every
            // onResume/window-focus transition. RenderPearl interpreted each one as
            // a swapchain reconfigure, and Android WSI could report
            // VK_ERROR_SURFACE_LOST_KHR after the screenshot/share Activity closed.
            // Only notify SDL when either the Surface object or dimensions changed.
            if (surface == lastResizeSurface
                    && safeRenderWidth == lastResizeRenderWidth
                    && safeRenderHeight == lastResizeRenderHeight
                    && safeDeviceWidth == lastResizeDeviceWidth
                    && safeDeviceHeight == lastResizeDeviceHeight) {
                return;
            }

            try {
                sdlContentScale = Math.max(1.0f, resolveDensity(activity));
                SDLActivity.applyDroidBridgeExternalSurfaceOrientation(
                        safeRenderWidth,
                        safeRenderHeight
                );
                SDLActivity.nativeSetScreenResolution(
                        safeRenderWidth,
                        safeRenderHeight,
                        safeDeviceWidth,
                        safeDeviceHeight,
                        sdlContentScale,
                        resolveRefreshRate(activity)
                );
                boolean recreatedSurface = !surfaceCreatedSent;
                if (recreatedSurface) {
                    SDLActivity.onNativeSurfaceCreated();
                    surfaceCreatedSent = true;
                }
                SDLActivity.onNativeSurfaceChanged();
                SDLActivity.onNativeResize();

                // The external-surface mode does not have an SDLSurface instance to
                // drive SDLActivity.handleNativeState(). Resume explicitly only when
                // a genuinely destroyed Android Surface has just been recreated.
                if (recreatedSurface) {
                    SDLActivity.mIsResumedCalled = true;
                    SDLActivity.nativeResume();
                    hostStopped = false;
                    SDLActivity.mHasFocus = activity.hasWindowFocus();
                    SDLActivity.nativeFocusChanged(activity.hasWindowFocus());
                    if (CallbackBridge.isGrabbing()) {
                        recenterLauncherGrabCursorSilently("replacement-surface-ready");
                    } else {
                        recenterLauncherMenuCursor("replacement-surface-ready");
                    }
                }

                lastResizeSurface = surface;
                lastResizeRenderWidth = safeRenderWidth;
                lastResizeRenderHeight = safeRenderHeight;
                lastResizeDeviceWidth = safeDeviceWidth;
                lastResizeDeviceHeight = safeDeviceHeight;

                // A genuine user resolution change already performs the non-zero
                // transition needed by Snapshot 4, so cancel the automatic startup
                // pulse only when the live dimensions actually differ from the
                // stable window observed at launch. Same-size callbacks do not count.
                if (!postWindowResolutionComplete
                        && !postWindowResolutionPulseInProgress
                        && postWindowResolutionBaselineWidth > 1
                        && postWindowResolutionBaselineHeight > 1
                        && (safeRenderWidth != postWindowResolutionBaselineWidth
                        || safeRenderHeight != postWindowResolutionBaselineHeight)) {
                    postWindowResolutionComplete = true;
                    postWindowResolutionScheduled = false;
                    append("DroidBridgeSDL3: startup resolution initialized by live transition"
                            + " baseline=" + postWindowResolutionBaselineWidth + "x"
                            + postWindowResolutionBaselineHeight
                            + " new=" + safeRenderWidth + "x" + safeRenderHeight);
                }
                append("DroidBridgeSDL3: "
                        + (recreatedSurface ? "surface recreated" : "resize delivered")
                        + " surfaceChanged=" + safeRenderWidth + "x" + safeRenderHeight
                        + " device=" + safeDeviceWidth + "x" + safeDeviceHeight);
            } catch (Throwable throwable) {
                append("DroidBridgeSDL3: resize callback failed: " + throwable);
            }
        }
    }

    private static void setPresentationPausedLocked(
            boolean paused,
            @NonNull String reason
    ) {
        GameActivity.setVulkanPresentationPausedFromLifecycle(paused, reason);
    }

    private static boolean pauseHostLocked(@NonNull String reason) {
        if (!initialized) return false;
        setPresentationPausedLocked(true, reason);
        if (hostStopped) return true;

        try {
            SDLActivity.mIsResumedCalled = false;
            SDLActivity.nativePause();
            hostStopped = true;
            append("DroidBridgeSDL3: paused SDL and Vulkan acquisition reason=" + reason);
            return true;
        } catch (Throwable throwable) {
            hostStopped = true;
            append("DroidBridgeSDL3: lifecycle pause failed reason=" + reason
                    + " error=" + throwable);
            return false;
        }
    }

    public static void onStart(@NonNull Activity activity) {
        if (!requested) return;
        activityRef = new WeakReference<>(activity);
        SDL.setContext(activity);
        SDLActivity.setDroidBridgeHostActivity(activity);
        if (!initialized) return;

        synchronized (LOCK) {
            Surface surface = publishedSurface;
            if (surface != null && surface.isValid()) {
                SDLActivity.setDroidBridgeNativeSurface(surface);
            }
            resumeHostIfReadyLocked("host start");
        }
    }

    public static void onStop() {
        if (!requested || !initialized) return;
        synchronized (LOCK) {
            boolean retained = canPreserveAttachedSurfaceAcrossStop();
            retainedScaledGeometryRefreshPending = retained
                    && sdlOpenGlCompatibilityRequested
                    && lastResizeRenderWidth > 0
                    && lastResizeRenderHeight > 0
                    && lastResizeDeviceWidth > 0
                    && lastResizeDeviceHeight > 0
                    && (lastResizeRenderWidth != lastResizeDeviceWidth
                    || lastResizeRenderHeight != lastResizeDeviceHeight);
            pauseHostLocked(retained
                    ? "Android onStop with retained Surface"
                    : "Android onStop waiting for Surface");
        }
    }

    private static boolean canPreserveAttachedSurfaceAcrossStop() {
        // Snapshot 4 always uses DroidBridge's retained TextureView producer, so
        // preservation no longer depends on SurfaceView's API-34 lifecycle mode.
        Surface surface = publishedSurface;
        return surfaceCreatedSent && surface != null && surface.isValid();
    }

    public static void onResume(@NonNull Activity activity) {
        if (!requested) return;
        activityRef = new WeakReference<>(activity);
        SDL.setContext(activity);
        SDLActivity.setDroidBridgeHostActivity(activity);
        if (!initialized) return;
        boolean refreshRetainedScaledGeometry = false;
        try {
            Surface surface = publishedSurface;
            if (surface != null && surface.isValid()) {
                SDLActivity.setDroidBridgeNativeSurface(surface);
            }
            synchronized (LOCK) {
                resumeHostIfReadyLocked("host resume");
                if (!hostStopped) SDLActivity.mIsResumedCalled = true;
                if (!hostStopped && retainedScaledGeometryRefreshPending) {
                    retainedScaledGeometryRefreshPending = false;
                    refreshRetainedScaledGeometry = true;
                }
            }
            SDLActivity.mHasFocus = activity.hasWindowFocus();
            SDLActivity.nativeFocusChanged(activity.hasWindowFocus());
            transientPauseLogged = false;
        } catch (Throwable throwable) {
            append("DroidBridgeSDL3: resume focus callback failed: " + throwable);
        }

        if (refreshRetainedScaledGeometry) {
            /*
             * nativeResume() has already restored SDL's logical 1286x724-style
             * window correctly. The broken state is one layer lower: Android can
             * reconnect the retained TextureView/EGL drawable at the physical
             * 1920x1080-style size, leaving Minecraft's smaller GL viewport in the
             * lower-left corner. Reassert the existing TextureView buffer and tell
             * SDL that the SAME native Surface changed. Do not resend logical
             * resolution/orientation state and do not replace the Surface object.
             *
             * Post once instead of delaying 48 ms so the correction happens as the
             * Activity resumes rather than a few rendered frames later; this also
             * removes the visible hitch caused by the old extra logical resize.
             */
            new Handler(Looper.getMainLooper()).post(
                    () -> refreshRetainedScaledOpenGlPresentation("host resume")
            );
        }
    }

    /**
     * Repairs only the retained wrapped-OpenGL presentation after Android resume.
     *
     * The live resolution control fixes this bug because MinecraftGLSurface first
     * reapplies SurfaceTexture.setDefaultBufferSize(...) and SDL then receives a
     * native surface-change notification. The previous workaround replayed only
     * nativeSetScreenResolution()/onNativeResize(); the latest log proves SDL was
     * already reporting the correct scaled window before that replay, so it could
     * not repair the full-size resumed EGL drawable.
     */
    private static void refreshRetainedScaledOpenGlPresentation(
            @NonNull String reason
    ) {
        final int renderWidth;
        final int renderHeight;
        final int deviceWidth;
        final int deviceHeight;
        final Surface retainedSurface;
        final MinecraftGLSurface minecraftSurface;

        synchronized (LOCK) {
            if (!requested || !initialized || hostStopped
                    || !sdlOpenGlCompatibilityRequested) {
                return;
            }

            retainedSurface = publishedSurface;
            minecraftSurface = minecraftSurfaceRef.get();
            if (!surfaceCreatedSent
                    || !surfaceProducerAttached
                    || retainedSurface == null
                    || !retainedSurface.isValid()
                    || minecraftSurface == null) {
                append("DroidBridgeSDL3: retained scaled presentation refresh skipped"
                        + " reason=" + reason + " surface/view not ready");
                return;
            }

            renderWidth = lastResizeRenderWidth;
            renderHeight = lastResizeRenderHeight;
            deviceWidth = lastResizeDeviceWidth;
            deviceHeight = lastResizeDeviceHeight;

            if (renderWidth <= 0 || renderHeight <= 0
                    || deviceWidth <= 0 || deviceHeight <= 0
                    || (renderWidth == deviceWidth && renderHeight == deviceHeight)) {
                return;
            }
        }

        float[] before = new float[9];
        boolean queriedBefore = DroidBridgeSDL3NativeWindowBridge.querySdlMouseState(before);
        try {
            /*
             * Reassert only the retained TextureView producer buffer. Do not call
             * refreshSize(): that generic path is allowed to issue a new logical
             * SDL resize if Android reports transiently different insets/layout
             * dimensions during Activity resume.
             */
            boolean bufferReasserted =
                    minecraftSurface.reassertRetainedSdlOpenGlBufferAfterResume();
            if (!bufferReasserted) {
                append("DroidBridgeSDL3: retained scaled presentation refresh skipped"
                        + " reason=" + reason + " TextureView buffer not ready");
                return;
            }

            // Re-publish the exact same Surface token, then refresh SDL's native
            // surface/EGL binding. This is OpenGL-only; Vulkan never enters here.
            SDLActivity.setDroidBridgeNativeSurface(retainedSurface);
            SDLActivity.onNativeSurfaceChanged();

            float[] after = new float[9];
            boolean queriedAfter = DroidBridgeSDL3NativeWindowBridge.querySdlMouseState(after);
            String beforeText = queriedBefore
                    ? Math.round(before[2]) + "x" + Math.round(before[3])
                    + " pixels=" + Math.round(before[4]) + "x" + Math.round(before[5])
                    : "unavailable";
            String afterText = queriedAfter
                    ? Math.round(after[2]) + "x" + Math.round(after[3])
                    + " pixels=" + Math.round(after[4]) + "x" + Math.round(after[5])
                    : "unavailable";
            append("DroidBridgeSDL3: retained scaled OpenGL presentation refreshed"
                    + " reason=" + reason
                    + " render=" + renderWidth + "x" + renderHeight
                    + " device=" + deviceWidth + "x" + deviceHeight
                    + " sdlBefore=" + beforeText
                    + " sdlAfter=" + afterText
                    + " sameSurface=true logicalResizeResent=false");
        } catch (Throwable throwable) {
            append("DroidBridgeSDL3: retained scaled presentation refresh failed"
                    + " reason=" + reason + " error=" + throwable);
        }
    }

    public static void onPause() {
        if (!requested || !initialized) return;
        synchronized (LOCK) {
            try {
                SDLActivity.mHasFocus = false;
                SDLActivity.nativeFocusChanged(false);
            } catch (Throwable throwable) {
                append("DroidBridgeSDL3: pause focus callback failed: " + throwable);
            }

            // Screenshot sharing and browser/file-picker launches can stop the
            // Android BufferQueue producer during onPause without ever reaching
            // onStop. Pause immediately so Vulkan never waits on a dead producer.
            pauseHostLocked("Android onPause");
            transientPauseLogged = true;
        }
    }

    public static void onWindowFocusChanged(boolean focused) {
        if (!requested || !initialized) return;
        try {
            SDLActivity.mHasFocus = focused;
            SDLActivity.nativeFocusChanged(focused);
        } catch (Throwable throwable) {
            append("DroidBridgeSDL3: focus callback failed: " + throwable);
        }
    }

    public static void shutdown() {
        synchronized (LOCK) {
            setPresentationPausedLocked(true, "SDL shutdown");
            if (initialized) {
                try {
                    if (surfaceCreatedSent) SDLActivity.onNativeSurfaceDestroyed();
                } catch (Throwable ignored) {
                }
                try {
                    SDLActivity.nativeSendQuit();
                } catch (Throwable ignored) {
                }
            }
            SDLActivity.setDroidBridgeNativeSurface(null);
            SDLActivity.setDroidBridgeInputView(null);
            SDLActivity.setDroidBridgeExternalSurfaceMode(false);
            SDLActivity.setDroidBridgeHostActivity(null);
            SDL.setContext(null);
            publishedSurface = null;
            surfaceProducerAttached = false;
            sdlFpsSampleFile = null;
            surfaceCreatedSent = false;
            inputRegistrationStarted = false;
            initialized = false;
            requested = false;
            selectedVersion = "";
            try {
                Os.unsetenv("DROIDBRIDGE_SDL3_FORCE_VULKAN_IDENTITY");
                Os.unsetenv("DROIDBRIDGE_SDL3_FPS_FILE");
            } catch (Throwable ignored) {
            }
            visualQuarterTurnDegrees = 0;
            visualViewLeft = 0;
            visualViewTop = 0;
            visualViewWidth = 1;
            visualViewHeight = 1;
            visualRenderWidth = 1;
            visualRenderHeight = 1;
            sdlContentScale = 1.0f;
            lastResizeSurface = null;
            lastResizeRenderWidth = -1;
            lastResizeRenderHeight = -1;
            lastResizeDeviceWidth = -1;
            lastResizeDeviceHeight = -1;
            transientPauseLogged = false;
            hostStopped = false;
            retainedScaledGeometryRefreshPending = false;
            postWindowResolutionGeneration++;
            postWindowResolutionScheduled = false;
            postWindowResolutionComplete = false;
            postWindowResolutionPulseInProgress = false;
            postWindowResolutionBaselineWidth = -1;
            postWindowResolutionBaselineHeight = -1;
            lastLoggedOverlayLeft = Integer.MIN_VALUE;
            lastLoggedOverlayTop = Integer.MIN_VALUE;
            lastLoggedOverlayWidth = -1;
            lastLoggedOverlayHeight = -1;
            synchronized (VIRTUAL_MOUSE_LOCK) {
                virtualMouseButtonState = 0;
            }
            MinecraftGLSurface.sdlWindowBackendRequested = false;
            MinecraftGLSurface.sdlEnabled = false;
            activityRef = new WeakReference<>(null);
            minecraftSurfaceRef = new WeakReference<>(null);
            LOCK.notifyAll();
        }
    }

    private static void initializeLocked(@NonNull Activity activity) {
        try {
            SDL.setContext(activity);
            SDLActivity.setDroidBridgeExternalSurfaceMode(true);
            SDL.loadLibrary("SDL3", activity);

            /*
             * Environment-only SDL hints were not visible reliably across ART and
             * the embedded OpenJDK linker namespace. Apply the provider directly to
             * the loaded SDL image before SDL.setupJNI()/SDL_Init().
             */
            if (sdlOpenGlCompatibilityRequested) {
                String eglLibrary = sdlOpenGlEglLibrary;
                String glLibrary = sdlOpenGlLibrary;
                String rendererKind = sdlOpenGlRendererKind;
                boolean configured = eglLibrary != null
                        && glLibrary != null
                        && DroidBridgeSDL3NativeWindowBridge.configureSdlOpenGlBridge(
                                eglLibrary,
                                glLibrary,
                                rendererKind != null ? rendererKind : "unknown"
                        );
                append("DroidBridgeSDL3: native SDL OpenGL hints configured="
                        + configured
                        + " kind=" + rendererKind
                        + " egl=" + eglLibrary
                        + " gl=" + glLibrary);
                if (!configured) {
                    append("DroidBridgeSDL3: WARNING OpenGL compatibility hints were not applied; "
                            + "Minecraft may fall back to Vulkan");
                }
            }

            SDL.setupJNI();

            /*
             * Android 10's embedded OpenJDK can otherwise map a second SDL3 image.
             * Publish the exact ART image only after nativeSetupJNI has populated
             * its JavaVM, callback classes and per-thread JNI state. LWJGL can then
             * reuse this JNI-ready image instead of initializing an empty copy.
             */
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q
                    && "ltw".equalsIgnoreCase(sdlOpenGlRendererKind)) {
                boolean artSdlPublished =
                        DroidBridgeSDL3NativeWindowBridge.publishArtSdl3Handle();
                append("DroidBridgeSDL3: Android 10 LTW SDL compatibility"
                        + " sharedImage=" + artSdlPublished
                        + " artDispatcher=false"
                        + " touchInitBypass=true");
                if (!artSdlPublished) {
                    append("DroidBridgeSDL3: WARNING Android 10 LTW could not publish "
                            + "the ART/JNI SDL handle");
                }
            }

            SDL.initialize();
            SDL.setContext(activity);
            SDLActivity.setDroidBridgeHostActivity(activity);
            SDLActivity.setDroidBridgeNativeSurface(publishedSurface);
            SDLActivity.mBrokenLibraries = false;

            String nativeVersion;
            try {
                nativeVersion = SDLActivity.nativeGetVersion();
            } catch (Throwable ignored) {
                nativeVersion = "unknown";
            }
            initialized = true;
            setPresentationPausedLocked(true, "SDL initialized; waiting for live Surface");
            startInputRegistrationLocked();
            append("DroidBridgeSDL3: JNI platform initialized nativeVersion=" + nativeVersion);
        } catch (Throwable throwable) {
            initialized = false;
            MinecraftGLSurface.sdlEnabled = false;
            throw new IllegalStateException("Unable to initialize SDL3 Android JNI platform", throwable);
        }
    }

    @Nullable
    static Surface awaitPublishedSurface(long timeoutMillis) {
        long deadline = SystemClock.uptimeMillis() + Math.max(0L, timeoutMillis);
        synchronized (LOCK) {
            while (requested) {
                Surface surface = publishedSurface;
                if (surface != null && surface.isValid()) return surface;
                long remaining = deadline - SystemClock.uptimeMillis();
                if (remaining <= 0L) break;
                try {
                    LOCK.wait(Math.min(remaining, 250L));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return publishedSurface;
        }
    }

    private static float resolveDensity(@NonNull Activity activity) {
        try {
            DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            if (metrics != null && metrics.density > 0f) return metrics.density;
        } catch (Throwable ignored) {
        }
        return 1.0f;
    }

    private static float resolveRefreshRate(@NonNull Activity activity) {
        try {
            Display display = activity.getWindowManager().getDefaultDisplay();
            float refresh = display.getRefreshRate();
            if (refresh > 0f) return refresh;
        } catch (Throwable ignored) {
        }
        return 60.0f;
    }

    private static void append(@NonNull String message) {
        try {
            LauncherLogManager.append(message);
        } catch (Throwable ignored) {
            android.util.Log.i("DroidBridgeSDL3", message);
        }
    }
}

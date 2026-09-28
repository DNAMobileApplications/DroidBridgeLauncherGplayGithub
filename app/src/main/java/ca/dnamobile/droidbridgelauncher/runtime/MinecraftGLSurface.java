/*
 * DroidBridge Launcher runtime bridge component.
 *
 * DroidBridge modifications:
 * Copyright (c) 2026 DNA Mobile Applications.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package ca.dnamobile.droidbridgelauncher.runtime;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.SurfaceTexture;
import android.graphics.Bitmap;
import android.view.PixelCopy;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.PixelFormat;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.Gravity;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.GameActivity;
import ca.dnamobile.droidbridgelauncher.dualscreen.AynThorDisplayCompat;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.settings.GameResolutionSettings;
import ca.dnamobile.droidbridgelauncher.modcompat.ControllerModCompat;
import ca.dnamobile.droidbridgelauncher.modcompat.ControlifySDL;
import ca.dnamobile.droidbridgelauncher.modcompat.TouchControllerModCompat;
import ca.dnamobile.droidbridgelauncher.renderer.DroidBridgeMesaSupport;
import ca.dnamobile.droidbridgelauncher.controls.TouchHotbarHitbox;
import ca.dnamobile.droidbridgelauncher.controls.ControlsPreferences;
import ca.dnamobile.droidbridgelauncher.controls.MinecraftTextInputKeyboardTrigger;
import ca.dnamobile.droidbridgelauncher.controls.MinecraftGuiScaleResolver;
import ca.dnamobile.droidbridgelauncher.controls.TouchKeyboardHelper;
import ca.dnamobile.droidbridgelauncher.input.GamepadInputController;
import ca.dnamobile.droidbridgelauncher.input.InputEventDiagnosticLogger;
import ca.dnamobile.droidbridgelauncher.input.GamepadMappingStore;
import ca.dnamobile.droidbridgelauncher.logs.LauncherLogManager;
import ca.dnamobile.droidbridgelauncher.runtime.utils.JREUtils;

import org.libsdl.app.SDLControllerManager;
import org.lwjgl.glfw.CallbackBridge;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

// Renders the game on Android's native SurfaceViews that are available giving the user options to
// render graphics on either Texture or native Surface
public class MinecraftGLSurface extends FrameLayout implements GrabListener {
    private View renderView;
    private TextureView textureView;
    private SurfaceView nativeSurfaceView;
    private Surface textureSurface;
    @Nullable private SurfaceTexture retainedSdlSurfaceTexture;
    private boolean retainedSdlSurfaceTextureDetached;
    private long lastSdlHardwareCursorLogUptimeMs;

    // Android input is received in the full MinecraftGLSurface coordinate space.
    // Non-native aspect ratios render inside this centred content rectangle.
    private int contentLeft = 0;
    private int contentTop = 0;
    private int viewWidth = 1;
    private int viewHeight = 1;
    private int renderWidth = 1;
    private int renderHeight = 1;
    // Native must retain the launcher's original MATCH_PARENT surface lifecycle.
    // Rewriting an already-created SurfaceView to an explicit pixel size can replace
    // its ANativeWindow on some OEM builds before NativeGLFW calls eglMakeCurrent.
    private boolean nativeResolutionLayout = true;
    private int imeViewportBottomInset = 0;
    private float inputScaleX = 1.0f;
    private float inputScaleY = 1.0f;
    @Nullable private File minecraftOptionsFile;

    private int lastSentWindowWidth = -1;
    private int lastSentWindowHeight = -1;
    private int lastLoggedRequestedScalePercent = -1;
    private int lastLoggedScalePercent = -1;
    private int lastLoggedViewWidth = -1;
    private int lastLoggedViewHeight = -1;
    private int lastLoggedRenderWidth = -1;
    private int lastLoggedRenderHeight = -1;
    private int lastNativeMesaBridgeWidth = -1;
    private int lastNativeMesaBridgeHeight = -1;
    private int lastAppliedSurfaceBufferWidth = -1;
    private int lastAppliedSurfaceBufferHeight = -1;
    private boolean lastAppliedSurfaceSizeFromLayout;

    private SurfaceReadyListener surfaceReadyListener;
    private OnRenderingStartedListener renderingStartedListener;
    @Nullable private SpecialKeyEventListener specialKeyEventListener;
    @Nullable private AndroidVirtualMouseUiRouter androidVirtualMouseUiRouter;
    private boolean renderingStarted = false;
    private volatile boolean bridgeWindowAttached = false;
    private volatile boolean grabbed = false;

    private float lastTouchX;
    private float lastTouchY;
    private boolean trackingTouch;

    private final Handler touchHandler = new Handler(Looper.getMainLooper());
    private final int touchSlop;
    private float touchDownX;
    private float touchDownY;
    private boolean touchMovedPastSlop;
    private boolean touchUiTapCandidate;
    private boolean touchStartedWhileGrabbed;
    private boolean touchMenuMouseButtonDown;

    // Raw-surface fallback for the same two-finger menu/chat scroll gesture handled
    // by TouchControlsOverlay. This keeps scrolling available if a SurfaceView or
    // compatibility path receives the touch stream directly.
    private boolean menuTwoFingerScrollActive;
    private boolean menuTwoFingerScrollBlockingUntilAllUp;
    private int menuTwoFingerScrollPointerA = -1;
    private int menuTwoFingerScrollPointerB = -1;
    private float menuTwoFingerScrollLastY;
    private float menuTwoFingerScrollAccumulator;

    private boolean consumingMenuRegrabTouchStream;
    private long lastTouchMenuClickNanos;
    private long consumeGrabbedTouchAfterMenuClickUntilNanos;
    private float lastTouchMenuClickX;
    private float lastTouchMenuClickY;
    private boolean touchLongPressAttackActive;
    private int touchControllerLookPointerId = -1;
    private float touchControllerLookLastX;
    private float touchControllerLookLastY;
    private float touchControllerLookDownX;
    private float touchControllerLookDownY;
    private boolean touchControllerLookMovedPastSlop;
    @Nullable private Runnable touchLongPressRunnable;
    public static volatile boolean sdlEnabled = false;
    /** True only for Minecraft versions whose window backend is SDL3. */
    public static volatile boolean sdlWindowBackendRequested = false;
    private int appliedSdlVisualQuarterTurn;
    private int appliedSdlVisualWidth = -1;
    private int appliedSdlVisualHeight = -1;
    private boolean sdlStartupResolutionPulseRunning;
    private boolean sdlStartupResolutionPulseComplete;

    private final Object sdlSurfaceLock = new Object();
    @Nullable private volatile Surface activeSdlSurface;
    private volatile int activeSdlRenderWidth = 1;
    private volatile int activeSdlRenderHeight = 1;
    private volatile int activeSdlDeviceWidth = 1;
    private volatile int activeSdlDeviceHeight = 1;
    public static volatile boolean legacyBtaInputFallbackEnabled = false;
    /**
     * True while a controller mod such as Controllable/Legacy4J owns the physical
     * gamepad. The surface must not translate those Android events into
     * DroidBridge mouse/camera fallback input; at most it mirrors them into GLFW
     * for old controller mods.
     */
    public static volatile boolean controllerModOwnsGamepadInput = false;

    private static final long POINTER_REGRAB_RELATIVE_SUPPRESS_NANOS = 450_000_000L;
    private static final long TOUCH_MENU_REGRAB_CENTER_NANOS = 1_200_000_000L;
    private static final long TOUCH_MENU_REGRAB_CENTER_MS = 1_200L;
    private static final long TOUCH_MENU_REGRAB_STALE_TOUCH_NANOS = 420_000_000L;
    private static final long POINTER_REGRAB_SILENT_CURSOR_SYNC_DELAY_MS = 32L;
    private static final long MENU_TEXT_KEYBOARD_OPEN_DELAY_MS = 140L;
    private static final float MENU_TWO_FINGER_SCROLL_DP_PER_STEP = 30f;

    // Some Bluetooth mice and scrcpy UHID mice are only trustworthy after Android
    // has delivered a real pointer event. Keep a short event-based allowance so
    // pointer capture can be requested even when InputDevice.isExternal() or the
    // device-list metadata is wrong.
    private static final long HARDWARE_POINTER_KEEPALIVE_NANOS = 3_000_000_000L;
    private static final long SECONDARY_MOUSE_KEY_DEDUP_NANOS = 300_000_000L;

    private final Set<Integer> hardwareKeysDown = new HashSet<>();
    private final Set<Integer> hardwareMouseButtonsDown = new HashSet<>();
    private final Set<Integer> controllerFallbackKeysDown = new HashSet<>();
    private final Set<Integer> controllerFallbackMouseButtonsDown = new HashSet<>();
    private long suppressRelativeCursorUntilNanos;
    private long lastHardwarePointerEventNanos;
    private boolean hardwarePointerInputMode;
    private static volatile boolean sHardwareMenuSoftwareCursorActive;
    // Android Virtual Mouse: release the real OS pointer only while a DroidBridge-owned
    // Android UI (such as the in-game launcher menu) explicitly needs it. Minecraft
    // GUIs keep pointer capture and use the centered DroidBridge software cursor.
    private volatile boolean androidVirtualMouseUiInteractionMode;
    private long lastHardwareSecondaryMotionEventNanos;
    private long lastHardwareBackButtonMotionEventNanos;
    private long lastHardwareForwardButtonMotionEventNanos;
    private int lastHardwarePointerDeviceId = -1;

    // BTA / old LWJGL fallback input. BTA detects controllers, but DroidBridge
    // does not enable the SDL/Controlify route for this launch, so Android
    // gamepad events need a direct GLFW-style fallback path.
    private static final float CONTROLLER_AXIS_DEADZONE = 0.25f;
    private static final float CONTROLLER_MENU_CURSOR_STEP = 28.0f;
    private static final float CONTROLLER_LOOK_STEP = 14.0f;

    private static final int GLFW_KEY_W = 87;
    private static final int GLFW_KEY_A = 65;
    private static final int GLFW_KEY_S = 83;
    private static final int GLFW_KEY_D = 68;
    private static final int GLFW_KEY_E = 69;
    private static final int GLFW_KEY_Q = 81;
    private static final int GLFW_KEY_SPACE = 32;
    private static final int GLFW_KEY_ESCAPE = 256;

    private static final long TOUCH_POINTER_MODE_RESET_NANOS = 650_000_000L;
    private static final float HARDWARE_TOP_LEFT_EPSILON = 1.0f;

    private float lastHardwareMouseX;
    private float lastHardwareMouseY;
    private boolean hasLastHardwareMousePosition;
    private long suppressSuspiciousHardwareAbsoluteUntilNanos;

    // Some Android devices dispatch physical mouse clicks as normal touch DOWN/UP
    // events instead of generic ACTION_BUTTON_PRESS/RELEASE events. Keep the
    // hardware mouse path separate from finger touch so Minecraft menus/keybinds
    // receive a clean GLFW mouse click while touch camera controls keep their
    // existing long-press/drag behavior.

    public MinecraftGLSurface(Context context) {
        this(context, null);
    }

    public MinecraftGLSurface(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        // Letterbox/pillarbox experimental aspect ratios against a predictable background.
        setBackgroundColor(Color.BLACK);
        setFocusable(true);
        setFocusableInTouchMode(true);
        CallbackBridge.init(context);
        CallbackBridge.addGrabListener(this);
        setOnCapturedPointerListener(this::handleCapturedPointer);
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
    }


    private boolean shouldForceZinkNativeSurfaceRgba8888() {
        if (DroidBridgeMesaSupport.shouldForceNativeSurfaceRgba8888()) return true;

        // v57 fallback: in some launch flows the renderer env is finalized after the
        // Java renderer object is selected, so the static flag can be missed. Check
        // the live process env as well. This keeps the display-path workaround active
        // for both the real Vulkan Zink option and the old Freedreno alias.
        return envContains("DROIDBRIDGE_RENDERER", "vulkan_zink")
                || envContains("LIB_MESA_NAME", "libosmesa_8.so")
                || envContains("DROIDBRIDGE_EGL", "libosmesa_8.so")
                || envEquals("DROIDBRIDGE_ADRENO740_SURFACE_RGBA8888", "1")
                || envEquals("DROIDBRIDGE_ZINK_V57_FORCE_OSMESA_EGL", "1")
                || envEquals("DROIDBRIDGE_FREEDRENO_V57_PURE_ZINK_ALIAS", "1");
    }


    private boolean shouldForceDirectFreedrenoOpaqueRgbx8888() {
        // v76: DROIDBRIDGE_EGL_FORCE_RGBX8888 is a visual/config request, not a
        // general instruction to switch the Android window owner to SurfaceView.
        // Kopper uses RGBX8888 too, but must remain on TextureView; otherwise the
        // raw handheld panel transform can present the game 90 degrees sideways and
        // the SurfaceView BufferQueue can be replaced while Mesa still owns it.
        if (isKopperZinkRendererActive()) {
            return false;
        }
        // v69: do not force RGBX just because direct Freedreno is selected.
        // The tested direct-renderer path lets Mesa/Android choose the visual. Only
        // force native SurfaceView when an explicit direct-Freedreno flag asks for it,
        // or when a legacy non-Kopper route still uses the old RGBX compatibility flag.
        return envEquals("DROIDBRIDGE_DIRECT_FREEDRENO_OPAQUE_RGBX8888", "1")
                || envEquals("DROIDBRIDGE_EGL_FORCE_RGBX8888", "1");
    }

    private boolean shouldForceDirectFreedrenoNativeSurface() {
        // v70: do not force SurfaceView for the direct-renderer direct KGSL path.
        // the tested renderer path's OpenJDK/Cacio route is closer to DroidBridge's TextureView path than
        // to the forced native SurfaceView path, and the remaining Adreno 740 issue is
        // visual corruption rather than a context failure. Keep an explicit escape hatch.
        return envEquals("DROIDBRIDGE_DIRECT_FREEDRENO_NATIVE_SURFACE_V69", "1")
                && !envEquals("DROIDBRIDGE_DIRECT_FREEDRENO_TEXTUREVIEW_V70", "1");
    }

    private boolean isKopperZinkRendererActive() {
        // Kopper/Zink presents through Mesa EGL on an Android native window.
        // Current FCL/Zalith-family builds require the composited TextureView path;
        // handing Kopper a direct SurfaceView can expose the device panel transform
        // (90-degree output on some Adreno handhelds/phones) and can destabilize the
        // native window after the first world frames. Keep this independent from the
        // user's global SurfaceView preference so other renderers are unaffected.
        return envContains("DROIDBRIDGE_RENDERER", "opengles3_desktopgl_zink_kopper")
                || envContains("POJAV_RENDERER", "opengles3_desktopgl_zink_kopper")
                || envContains("DROIDBRIDGE_RENDERER_LIBRARY", "libglxshim.so")
                || envEquals("DROIDBRIDGE_KOPPER_FORCE_TEXTUREVIEW", "1");
    }

    private boolean isNativeMesaFreedrenoRendererActive() {
        // The user-facing renderer is still "DroidBridge Native Mesa", but the working
        // path now routes it internally as DROIDBRIDGE_RENDERER=freedreno_kgsl so it
        // matches the DroidBridge direct KGSL setup. Treat that routed renderer as Native
        // Mesa too, otherwise live resolution changes only update GLFW size and never
        // refresh the Mesa/Android bridge window state, which can leave a black surface.
        return envContains("DROIDBRIDGE_RENDERER", "droidbridge_native_glfw_kgsl")
                || envContains("DROIDBRIDGE_RENDERER", "native_glfw_kgsl")
                || envContains("DROIDBRIDGE_RENDERER", "freedreno_kgsl")
                || envContains("DROIDBRIDGE_RENDERER_MESA_MODE", "freedreno_kgsl")
                || envContains("DROIDBRIDGE_MESA_MODE", "freedreno_kgsl")
                || (envEquals("DROIDBRIDGE_MESA", "1")
                && (envEquals("DROIDBRIDGE_MESA_DRIVER", "kgsl")
                || envEquals("MESA_LOADER_DRIVER_OVERRIDE", "kgsl")))
                || envEquals("DROIDBRIDGE_NATIVE_GLFW", "1")
                || envEquals("DROIDBRIDGE_NATIVE_GLFW_KGSL", "1");
    }

    private static boolean envContains(String key, String needle) {
        try {
            String value = System.getenv(key);
            return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(needle);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean envEquals(String key, String expected) {
        try {
            String value = System.getenv(key);
            return expected.equals(value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void start(boolean isAlreadyRunning) {
        if (renderView != null) {
            if (isAlreadyRunning) {
                post(this::reattachBridgeWindow);
            }
            return;
        }

        renderingStarted = false;
        boolean useNativeSurfaceView = LauncherPreferences.isUseNativeSurfaceView(getContext());
        boolean kopperZink = isKopperZinkRendererActive();
        if (kopperZink) {
            // Do not allow the global Native SurfaceView toggle to leak into Kopper.
            // Kopper's Android WSI is substantially more stable when SurfaceTexture
            // owns composition/rotation instead of exposing the panel's raw transform.
            useNativeSurfaceView = false;
            Log.i("MinecraftGLSurface",
                    "Kopper Zink forcing TextureView surface owner (SurfaceView disabled)");
            LauncherLogManager.append(
                    "KopperZinkSurface: forced TextureView owner; native SurfaceView disabled");
        }

        // v76: Kopper must have absolute priority over every legacy Mesa/FreeDreno
        // native-surface compatibility branch below. In v75 the RGBX visual flag
        // DROIDBRIDGE_EGL_FORCE_RGBX8888=1 re-enabled SurfaceView after the Kopper
        // block above had disabled it, so the "force TextureView" fix never actually
        // reached the final owner selection.
        if (kopperZink) {
            useNativeSurfaceView = false;
        } else if (sdlWindowBackendRequested) {
            // Snapshot 4's Vulkan VkSurfaceKHR permanently owns the ANativeWindow
            // returned during startup. A SurfaceView receives a brand-new
            // BufferQueue when Android invalidates the Activity root surface, so
            // publishing the replacement Surface later cannot repair RenderPearl's
            // existing VkSurfaceKHR. TextureView lets DroidBridge retain the same
            // SurfaceTexture producer across Activity cover/background cycles.
            useNativeSurfaceView = false;
            Log.i("MinecraftGLSurface",
                    "SDL3 window backend using retained TextureView ANativeWindow");
            LauncherLogManager.append(
                    "DroidBridgeSDL3: selected retained TextureView surface owner v4");
        } else if (shouldForceDirectFreedrenoOpaqueRgbx8888()) {
            useNativeSurfaceView = true;
            Log.i("ResolutionScale", "v69 forcing native SurfaceView RGBX_8888/opaque for explicit direct Freedreno debug flag");
        } else if (shouldForceDirectFreedrenoNativeSurface()) {
            useNativeSurfaceView = true;
            Log.i("ResolutionScale", "v69 forcing native SurfaceView for direct Freedreno Freedreno KGSL path without forcing holder pixel format");
        } else if (shouldForceZinkNativeSurfaceRgba8888()) {
            useNativeSurfaceView = true;
            Log.i("ResolutionScale", "v59 forcing native SurfaceView RGBA_8888 for Vulkan Zink / legacy Freedreno alias");
        }

        if (useNativeSurfaceView) {
            LauncherLogManager.append(
                    "MinecraftGLSurface: final surface owner=SurfaceView renderer="
                            + String.valueOf(System.getenv("DROIDBRIDGE_RENDERER")));
            startNativeSurfaceView(isAlreadyRunning);
        } else {
            if (envEquals("DROIDBRIDGE_DIRECT_FREEDRENO_TEXTUREVIEW_V70", "1")) {
                Log.i("ResolutionScale", "v70 using TextureView path for direct Freedreno Freedreno KGSL visual test");
            }
            LauncherLogManager.append(
                    "MinecraftGLSurface: final surface owner=TextureView renderer="
                            + String.valueOf(System.getenv("DROIDBRIDGE_RENDERER"))
                            + " kopper=" + kopperZink
                            + " rgbx=" + envEquals("DROIDBRIDGE_EGL_FORCE_RGBX8888", "1"));
            startTextureView(isAlreadyRunning);
        }
    }

    private void startTextureView(boolean isAlreadyRunning) {
        textureView = sdlWindowBackendRequested
                ? new RetainedSdlTextureView(getContext())
                : new TextureView(getContext());
        renderView = textureView;
        InputEventDiagnosticLogger.installUnhandledKeyHook(
                textureView,
                "MinecraftGLSurface.TextureView"
        );

        textureView.setOpaque(true);
        textureView.setAlpha(1.0f);
        textureView.setFocusable(false);

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            private boolean called = isAlreadyRunning;

            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                RenderSize size = updateScaledSizeFromView(width, height);
                surface.setDefaultBufferSize(size.renderWidth, size.renderHeight);

                boolean reusedRetainedSdlSurface = sdlWindowBackendRequested
                        && retainedSdlSurfaceTexture == surface
                        && textureSurface != null
                        && textureSurface.isValid();

                if (!reusedRetainedSdlSurface) {
                    releaseTextureSurface();
                    textureSurface = new Surface(surface);
                }

                if (sdlWindowBackendRequested) {
                    retainedSdlSurfaceTexture = surface;
                    retainedSdlSurfaceTextureDetached = false;
                    LauncherLogManager.append(
                            "DroidBridgeSDL3: TextureView producer available reused="
                                    + reusedRetainedSdlSurface
                                    + " surface=" + Integer.toHexString(System.identityHashCode(surface))
                                    + " size=" + size.renderWidth + "x" + size.renderHeight);
                }

                publishSdlSurface(textureSurface, size);
                GameActivity.setVulkanPresentationPausedFromLifecycle(
                        false, "TextureView surface available");

                if (called) {
                    attachBridgeWindow(textureSurface, size);
                    return;
                }

                called = true;
                realStart(textureSurface, size, false);
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                RenderSize size = updateScaledSizeFromView(width, height);
                surface.setDefaultBufferSize(size.renderWidth, size.renderHeight);
                publishSdlSurface(textureSurface, size);
                refreshSize(size);
                DroidBridgeSDL3Bootstrap.resizeSurface(
                        size.renderWidth, size.renderHeight, size.availableWidth, size.availableHeight);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                GameActivity.setVulkanPresentationPausedFromLifecycle(
                        true, "TextureView surface destroyed");
                if (sdlWindowBackendRequested
                        && retainedSdlSurfaceTexture == surface
                        && textureSurface != null
                        && textureSurface.isValid()) {
                    // Returning false transfers release responsibility to
                    // DroidBridge. TextureView drops only its consumer-side layer;
                    // the SurfaceTexture producer and the Surface/ANativeWindow used
                    // by SDL/Vulkan remain exactly the same.
                    retainedSdlSurfaceTextureDetached = true;
                    DroidBridgeSDL3Bootstrap.onSurfaceProducerDetached(textureSurface);
                    LauncherLogManager.append(
                            "DroidBridgeSDL3: retained TextureView producer across host detach"
                                    + " surface=" + Integer.toHexString(System.identityHashCode(surface))
                                    + " nativeSurfaceValid=" + textureSurface.isValid());
                    return false;
                }

                bridgeWindowAttached = false;
                if (!sdlWindowBackendRequested) {
                    JREUtils.releaseBridgeWindow();
                }
                resetSentWindowSize();
                clearSdlSurface(textureSurface);
                releaseTextureSurface();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
                notifyRenderingStartedOnce();
            }
        });

        addRenderView(textureView);
    }

    /**
     * TextureView clears its private mSurface when detached even when the listener
     * returns false. Reinstall the retained SurfaceTexture before the first draw on
     * re-attachment so its consumer reconnects to the same BufferQueue.
     */
    private final class RetainedSdlTextureView extends TextureView {
        RetainedSdlTextureView(@NonNull Context context) {
            super(context);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            SurfaceTexture retained = retainedSdlSurfaceTexture;
            if (!sdlWindowBackendRequested
                    || retained == null
                    || getSurfaceTexture() != null
                    || !retainedSdlSurfaceTextureDetached) {
                return;
            }

            try {
                setSurfaceTexture(retained);
                retainedSdlSurfaceTextureDetached = false;
                invalidate();
                LauncherLogManager.append(
                        "DroidBridgeSDL3: reattached retained TextureView producer"
                                + " surface=" + Integer.toHexString(System.identityHashCode(retained))
                                + " nativeSurfaceValid="
                                + (textureSurface != null && textureSurface.isValid()));
                post(() -> {
                    if (textureSurface != null && textureSurface.isValid()) {
                        DroidBridgeSDL3Bootstrap.publishSurface(textureSurface);
                        reattachBridgeWindow();
                        GameActivity.setVulkanPresentationPausedFromLifecycle(
                                false, "retained TextureView producer reattached");
                    }
                });
            } catch (Throwable throwable) {
                LauncherLogManager.append(
                        "DroidBridgeSDL3: failed to reattach retained TextureView producer: "
                                + throwable);
            }
        }
    }

    /**
     * Android 14+ can keep a SurfaceView's native Surface alive for as long as the
     * view stays attached, instead of destroying it merely because another
     * Activity (for example the screenshot share sheet or Bluetooth picker)
     * temporarily covers GameActivity. SDL/Vulkan stores the Android surface in a
     * VkSurfaceKHR, so preserving the BufferQueue prevents the otherwise
     * unrecoverable VK_ERROR_SURFACE_LOST_KHR on return.
     *
     * Reflection keeps older compile SDKs/source variants compatible.
     */
    private void enableStableSdlSurfaceLifecycle(@NonNull SurfaceView surfaceView) {
        if (!sdlWindowBackendRequested || Build.VERSION.SDK_INT < 34) return;
        try {
            // API 34 defines SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT as 2. Resolve
            // only the method reflectively: some OEM framework jars omit the public
            // constant field even though setSurfaceLifecycle(int) is implemented.
            Method method = SurfaceView.class.getMethod(
                    "setSurfaceLifecycle",
                    int.class
            );
            method.invoke(surfaceView, 2);
            Log.i("MinecraftGLSurface",
                    "SDL3 Surface lifecycle follows attachment (Android 14+)");
            LauncherLogManager.append(
                    "DroidBridgeSDL3: SurfaceView lifecycle=follows-attachment API="
                            + Build.VERSION.SDK_INT);
        } catch (Throwable throwable) {
            Log.w("MinecraftGLSurface",
                    "Unable to enable attachment-scoped SDL3 Surface lifecycle",
                    throwable);
            LauncherLogManager.append(
                    "DroidBridgeSDL3: SurfaceView follows-attachment unavailable API="
                            + Build.VERSION.SDK_INT + " error=" + throwable);
        }
    }

    private void startNativeSurfaceView(boolean isAlreadyRunning) {
        nativeSurfaceView = new SurfaceView(getContext());
        renderView = nativeSurfaceView;
        enableStableSdlSurfaceLifecycle(nativeSurfaceView);
        InputEventDiagnosticLogger.installUnhandledKeyHook(
                nativeSurfaceView,
                "MinecraftGLSurface.SurfaceView"
        );

        try {
            if (shouldForceDirectFreedrenoOpaqueRgbx8888()) {
                nativeSurfaceView.getHolder().setFormat(PixelFormat.RGBX_8888);
                Log.i("ResolutionScale", "v69 SurfaceView holder format forced to RGBX_8888/opaque by explicit debug flag");
            } else if (shouldForceZinkNativeSurfaceRgba8888()) {
                nativeSurfaceView.getHolder().setFormat(PixelFormat.RGBA_8888);
                Log.i("ResolutionScale", "v59 SurfaceView holder format forced to RGBA_8888 for Vulkan Zink / legacy Freedreno alias");
            }
        } catch (Throwable ignored) {
        }

        nativeSurfaceView.setFocusable(false);
        nativeSurfaceView.setZOrderOnTop(false);
        nativeSurfaceView.setZOrderMediaOverlay(false);

        // Surface callbacks can precede the real View layout on Android handhelds.
        // Apply the SDL-only composition transform from the actual laid-out bounds,
        // without resizing or replacing the underlying ANativeWindow.
        nativeSurfaceView.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                     oldLeft, oldTop, oldRight, oldBottom) -> {
            if (!sdlWindowBackendRequested) return;
            int laidOutWidth = right - left;
            int laidOutHeight = bottom - top;
            if (laidOutWidth <= 1 || laidOutHeight <= 1) return;
            applySdlVisualQuarterTurn(laidOutWidth, laidOutHeight, renderWidth, renderHeight);
        });

        nativeSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            private boolean called = isAlreadyRunning;
            private boolean waitingForStableNativeMesaSurface = false;

            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                lastAppliedSurfaceBufferWidth = -1;
                lastAppliedSurfaceBufferHeight = -1;
                lastAppliedSurfaceSizeFromLayout = false;
                RenderSize size = updateScaledSizeFromView(nativeSurfaceView.getWidth(), nativeSurfaceView.getHeight());
                applyNativeSurfaceBufferSize(holder, size);
                applySdlVisualQuarterTurn(size.viewWidth, size.viewHeight, size.renderWidth, size.renderHeight);
                Surface createdSurface = holder.getSurface();
                if (createdSurface != null && createdSurface.isValid()) {
                    publishSdlSurface(createdSurface, size);
                    GameActivity.setVulkanPresentationPausedFromLifecycle(
                            false, "SurfaceView surface created");
                }

                /*
                 * Some large-screen OEM builds (notably Nubia's NP03J firmware) deliver
                 * surfaceCreated() before the SurfaceView's buffer queue can be bound by
                 * Mesa EGL. Starting LWJGL here lets the preload thread take ownership of
                 * the context and the later render thread receives EGL_BAD_ACCESS.
                 *
                 * Delay direct Native Mesa and SDL3 launches until the first valid
                 * surfaceChanged(). SDL must receive the final Android buffer queue, not
                 * the provisional Surface from surfaceCreated().
                 */
                if (sdlWindowBackendRequested || isNativeMesaFreedrenoRendererActive()) {
                    waitingForStableNativeMesaSurface = true;
                    Log.i("MinecraftGLSurface",
                            sdlWindowBackendRequested
                                    ? "SDL3 waiting for first stable surfaceChanged before JVM start"
                                    : "Native Mesa waiting for first valid surfaceChanged before JVM start");

                    // Defensive fallback for unusual firmware that omits surfaceChanged().
                    postDelayed(() -> {
                        if (!waitingForStableNativeMesaSurface || bridgeWindowAttached) return;
                        Surface fallbackSurface = holder.getSurface();
                        if (fallbackSurface == null || !fallbackSurface.isValid()) return;

                        RenderSize fallbackSize = updateScaledSizeFromView(
                                nativeSurfaceView.getWidth(),
                                nativeSurfaceView.getHeight()
                        );
                        applyNativeSurfaceBufferSize(holder, fallbackSize);
                        waitingForStableNativeMesaSurface = false;

                        if (called) {
                            attachBridgeWindow(fallbackSurface, fallbackSize);
                            notifyRenderingStartedSoon();
                        } else {
                            called = true;
                            realStart(fallbackSurface, fallbackSize, true);
                        }
                    }, 250L);
                    return;
                }

                if (called) {
                    attachBridgeWindow(holder.getSurface(), size);
                    notifyRenderingStartedSoon();
                    return;
                }

                called = true;
                realStart(holder.getSurface(), size, true);
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                RenderSize size = updateScaledSizeFromView(nativeSurfaceView.getWidth(), nativeSurfaceView.getHeight());
                applyNativeSurfaceBufferSize(holder, size);
                applySdlVisualQuarterTurn(size.viewWidth, size.viewHeight, size.renderWidth, size.renderHeight);

                Surface changedSurface = holder.getSurface();
                if (changedSurface != null && changedSurface.isValid()) {
                    publishSdlSurface(changedSurface, size);
                    GameActivity.setVulkanPresentationPausedFromLifecycle(
                            false, "SurfaceView surface changed");
                    DroidBridgeSDL3Bootstrap.resizeSurface(
                            size.renderWidth, size.renderHeight, size.availableWidth, size.availableHeight);
                    scheduleSdlVisualQuarterTurn(size);
                }

                if (holder.getSurface().isValid()) {
                    if (sdlWindowBackendRequested) {
                        waitingForStableNativeMesaSurface = false;

                        if (!called) {
                            called = true;
                            Log.i("MinecraftGLSurface",
                                    "SDL3 starting JVM from stable surfaceChanged "
                                            + size.renderWidth + "x" + size.renderHeight);
                            realStart(holder.getSurface(), size, true);
                            return;
                        }

                        bridgeWindowAttached = true;
                        updateSizeFields(size);
                        markNativeMesaBridgeSize(size);
                        sendWindowSizeIfChanged(size, "sdlSurfaceChanged");
                        CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
                        scheduleSurfaceResizeRefreshes();
                        notifyRenderingStartedSoon();
                        return;
                    }

                    if (isNativeMesaFreedrenoRendererActive()) {
                        waitingForStableNativeMesaSurface = false;

                        if (!called) {
                            called = true;
                            Log.i("MinecraftGLSurface",
                                    "Native Mesa starting JVM from stable surfaceChanged "
                                            + size.renderWidth + "x" + size.renderHeight);
                            realStart(holder.getSurface(), size, true);
                            return;
                        }

                        if (!bridgeWindowAttached) {
                            Log.i("MinecraftGLSurface",
                                    "Native Mesa reattaching recreated stable SurfaceView");
                            attachBridgeWindow(holder.getSurface(), size);
                            notifyRenderingStartedSoon();
                            return;
                        }

                        resizeNativeMesaBridgeWindow(size, "surfaceChanged");
                        CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);

                        scheduleSurfaceResizeRefreshes();
                        notifyRenderingStartedSoon();
                        return;
                    }

                    JREUtils.setupBridgeWindow(holder.getSurface());
                    bridgeWindowAttached = true;

                    updateSizeFields(size);
                    markNativeMesaBridgeSize(size);
                    sendWindowSizeIfChanged(size, "surfaceChanged");
                    CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);

                    scheduleSurfaceResizeRefreshes();
                    notifyRenderingStartedSoon();
                }
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                GameActivity.setVulkanPresentationPausedFromLifecycle(
                        true, "SurfaceView surface destroyed");
                if (sdlWindowBackendRequested) {
                    LauncherLogManager.append(
                            "DroidBridgeSDL3: Android SurfaceView surfaceDestroyed"
                                    + " valid=" + holder.getSurface().isValid()
                                    + " attached=" + nativeSurfaceView.isAttachedToWindow()
                                    + " windowVisibility=" + nativeSurfaceView.getWindowVisibility());
                }
                waitingForStableNativeMesaSurface = false;
                bridgeWindowAttached = false;
                lastAppliedSurfaceBufferWidth = -1;
                lastAppliedSurfaceBufferHeight = -1;
                lastAppliedSurfaceSizeFromLayout = false;
                resetSdlVisualQuarterTurn();
                clearSdlSurface(holder.getSurface());
                if (!sdlWindowBackendRequested) {
                    JREUtils.releaseBridgeWindow();
                }
            }
        });

        addRenderView(nativeSurfaceView);
    }

    private void addRenderView(@NonNull View child) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        addView(child, lp);
        if (getWidth() > 1 && getHeight() > 1) {
            updateScaledSizeFromView(getWidth(), getHeight());
        } else {
            post(this::refreshSize);
        }
        child.requestLayout();
    }
    /**
     * Re-attaches the currently owned Android Surface to the LWJGL/GLFW bridge.
     * This is used when DroidBridge moves the already-running game between the
     * phone display and an external Presentation display.
     */
    public void reattachBridgeWindow() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post(this::reattachBridgeWindow);
            return;
        }

        try {
            int width = safeWidth(getWidth());
            int height = safeHeight(getHeight());
            if (width <= 1 && renderView != null) width = safeWidth(renderView.getWidth());
            if (height <= 1 && renderView != null) height = safeHeight(renderView.getHeight());
            RenderSize size = updateScaledSizeFromView(width, height);

            if (nativeSurfaceView != null
                    && nativeSurfaceView.getHolder() != null
                    && nativeSurfaceView.getHolder().getSurface() != null
                    && nativeSurfaceView.getHolder().getSurface().isValid()) {
                applyNativeSurfaceBufferSize(nativeSurfaceView.getHolder(), size);
                attachBridgeWindow(nativeSurfaceView.getHolder().getSurface(), size);
                notifyRenderingStartedSoon();
                return;
            }

            if (textureSurface != null && textureSurface.isValid()) {
                attachBridgeWindow(textureSurface, size);
                return;
            }

            forceRefreshSize("reattachBridgeWindow");
        } catch (Throwable throwable) {
            Log.e("MinecraftGLSurface", "Unable to reattach bridge window", throwable);
        }
    }


    /**
     * Called by the hidden Android IME bridge while the soft keyboard is visible.
     *
     * Do not shrink the GLFW window height here. Minecraft recalculates GUI scale
     * from the reported window size; in landscape the keyboard can cut the height
     * roughly in half, causing chat and text boxes to become tiny. viewport-push IME
     * behavior is a visual push: keep Minecraft's full window size/render buffer so
     * its GUI scale stays normal, then translate the rendered surface upward by the
     * keyboard height so Minecraft's own bottom chat/input line is visible above
     * Android's keyboard.
     */
    public void setImeViewportBottomInset(int bottomInsetPx) {
        int safeInset = Math.max(0, bottomInsetPx);
        int maxInset = Math.max(0, getHeight() - 1);
        if (safeInset > maxInset) safeInset = maxInset;
        if (imeViewportBottomInset == safeInset) return;
        imeViewportBottomInset = safeInset;
        applyImeViewportLayout();
    }

    private void applyImeViewportLayout() {
        if (renderView == null) return;

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) renderView.getLayoutParams();
        if (lp == null) {
            lp = new FrameLayout.LayoutParams(
                    nativeResolutionLayout
                            ? ViewGroup.LayoutParams.MATCH_PARENT
                            : Math.max(1, viewWidth),
                    nativeResolutionLayout
                            ? ViewGroup.LayoutParams.MATCH_PARENT
                            : Math.max(1, viewHeight)
            );
        }

        // Native is intentionally a true no-op for the Android view hierarchy.
        // Keep the exact pre-resolution MATCH_PARENT path so SurfaceView/TextureView
        // does not receive a same-size explicit relayout that can invalidate the
        // ANativeWindow between setupBridgeWindow() and eglMakeCurrent().
        int desiredWidth = nativeResolutionLayout
                ? ViewGroup.LayoutParams.MATCH_PARENT
                : Math.max(1, viewWidth);
        int desiredHeight = nativeResolutionLayout
                ? ViewGroup.LayoutParams.MATCH_PARENT
                : Math.max(1, viewHeight);
        int desiredLeft = nativeResolutionLayout ? 0 : Math.max(0, contentLeft);
        int desiredTop = nativeResolutionLayout ? 0 : Math.max(0, contentTop);
        int desiredGravity = Gravity.TOP | Gravity.START;
        if (lp.width != desiredWidth
                || lp.height != desiredHeight
                || lp.leftMargin != desiredLeft
                || lp.topMargin != desiredTop
                || lp.rightMargin != 0
                || lp.bottomMargin != 0
                || lp.gravity != desiredGravity) {
            lp.width = desiredWidth;
            lp.height = desiredHeight;
            lp.leftMargin = desiredLeft;
            lp.topMargin = desiredTop;
            lp.rightMargin = 0;
            lp.bottomMargin = 0;
            lp.gravity = desiredGravity;
            renderView.setLayoutParams(lp);
        }

        float desiredTranslationY = imeViewportBottomInset > 0 ? -imeViewportBottomInset : 0f;
        if (renderView.getTranslationY() != desiredTranslationY) {
            renderView.setTranslationY(desiredTranslationY);
        }
    }
    /**
     * Snapshot 4 SDL/Vulkan owns the Android Surface directly. Never rotate, scale,
     * translate, or relayout the live SurfaceView after swapchain creation: changing
     * View composition can replace the BufferQueue and produces
     * VK_ERROR_SURFACE_LOST_KHR. Display orientation is corrected in the Vulkan
     * swapchain pre-transform instead. Input therefore stays in normal landscape
     * coordinates with no inverse View transform.
     */
    private void applySdlVisualQuarterTurn(int width, int height, int logicalWidth, int logicalHeight) {
        if (!sdlWindowBackendRequested) {
            resetSdlVisualQuarterTurn();
            return;
        }
        if (width <= 1 || height <= 1) return;

        appliedSdlVisualQuarterTurn = 0;
        appliedSdlVisualWidth = width;
        appliedSdlVisualHeight = height;
        int contentLeft = renderView != null ? Math.max(0, renderView.getLeft()) : 0;
        int contentTop = renderView != null ? Math.max(0, renderView.getTop()) : 0;
        DroidBridgeSDL3Bootstrap.configureVisualLayout(
                contentLeft,
                contentTop,
                Math.max(1, width),
                Math.max(1, height),
                Math.max(1, logicalWidth),
                Math.max(1, logicalHeight)
        );
    }

    private void scheduleSdlVisualQuarterTurn(@NonNull RenderSize size) {
        if (!sdlWindowBackendRequested) return;
        // Do not schedule delayed SurfaceView transforms. A delayed rotation was the
        // direct cause of the screenshot/app-switch VK_ERROR_SURFACE_LOST_KHR crash.
        int width = getWidth() > 1 ? getWidth() : Math.max(1, size.availableWidth);
        int height = getHeight() > 1 ? getHeight() : Math.max(1, size.availableHeight);
        applySdlVisualQuarterTurn(
                width,
                height,
                Math.max(1, size.renderWidth),
                Math.max(1, size.renderHeight)
        );
    }

    private void resetSdlVisualQuarterTurn() {
        // Intentionally do not touch SurfaceView rotation/scale here. The SDL Vulkan
        // surface must remain composition-stable for its entire lifetime.
        appliedSdlVisualQuarterTurn = 0;
        appliedSdlVisualWidth = -1;
        appliedSdlVisualHeight = -1;
        DroidBridgeSDL3Bootstrap.configureVisualQuarterTurn(0, 1, 1, 1, 1);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        if (w <= 0 || h <= 0) return;
        if (w == oldw && h == oldh) return;

        post(this::applyImeViewportLayout);
        post(() -> applySdlVisualQuarterTurn(w, h, renderWidth, renderHeight));
        if (sdlWindowBackendRequested && bridgeWindowAttached) {
            // A transient Android chooser/share overlay can cause window-size and
            // inset callbacks even though the actual SDL Surface dimensions are
            // unchanged. Do not turn those into Vulkan swapchain resize requests.
            return;
        }
        post(this::refreshSize);
        postDelayed(this::refreshSize, 120L);
        postDelayed(this::refreshSize, 350L);
    }

    public void refreshSize() {
        refreshSize(false, "refreshSize");
    }

    /**
     * Reasserts only the retained SDL TextureView producer's current buffer size.
     *
     * Android can resume a retained TextureView with an EGL drawable sized to the
     * physical display even though DroidBridge intentionally renders Minecraft at
     * a smaller resolution. In that state Minecraft's GL viewport stays scaled and
     * appears only in the lower-left corner. A live resolution change fixes it by
     * touching SurfaceTexture.setDefaultBufferSize(...), so the resume repair needs
     * to repeat that producer-side step without changing DroidBridge's logical SDL
     * window size or replacing the retained Surface/ANativeWindow.
     *
     * This method deliberately does NOT call DroidBridgeSDL3Bootstrap.resizeSurface,
     * send a Minecraft window-size callback, or alter the Surface object. The caller
     * may safely follow it with SDLActivity.onNativeSurfaceChanged() on the wrapped
     * OpenGL path so EGL rebinds the same retained window using these buffer bounds.
     */
    public boolean reassertRetainedSdlOpenGlBufferAfterResume() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            LauncherLogManager.append(
                    "DroidBridgeSDL3: retained OpenGL buffer reassert skipped; not on main thread");
            return false;
        }
        if (!sdlWindowBackendRequested || textureView == null
                || textureSurface == null || !textureSurface.isValid()) {
            return false;
        }

        SurfaceTexture producer = textureView.getSurfaceTexture();
        if (producer == null || producer != retainedSdlSurfaceTexture) {
            return false;
        }

        int targetWidth = Math.max(1, renderWidth);
        int targetHeight = Math.max(1, renderHeight);
        try {
            producer.setDefaultBufferSize(targetWidth, targetHeight);

            // Force TextureView's consumer/layer to apply the retained producer's
            // geometry again. The view stays MATCH_PARENT; only its buffer remains
            // at the user's selected render resolution.
            textureView.requestLayout();
            textureView.invalidate();
            textureView.postInvalidateOnAnimation();
            requestLayout();
            invalidate();
            postInvalidateOnAnimation();

            LauncherLogManager.append(
                    "DroidBridgeSDL3: retained OpenGL TextureView buffer reasserted"
                            + " render=" + targetWidth + "x" + targetHeight
                            + " view=" + Math.max(1, textureView.getWidth()) + "x"
                            + Math.max(1, textureView.getHeight())
                            + " sameProducer=true sameSurface=true");
            return true;
        } catch (Throwable throwable) {
            LauncherLogManager.append(
                    "DroidBridgeSDL3: retained OpenGL TextureView buffer reassert failed: "
                            + throwable);
            return false;
        }
    }

    /**
     * Re-sends the current size even when the dimensions have not changed.
     *
     * Some renderer/loader combinations create the real GLFW window after the
     * first launcher-side screen-size event. If we suppress later identical
     * events, Minecraft can keep drawing into the smaller startup viewport until
     * the user changes the resolution slider manually. A forced startup resend
     * mimics that manual refresh without requiring the user to touch the setting.
     */
    private void forceRefreshSize(@NonNull String reason) {
        refreshSize(true, reason);
    }

    private void refreshSize(boolean forceSendWindowSize, @NonNull String reason) {
        int width = safeWidth(getWidth());
        int height = safeHeight(getHeight());

        if (width <= 1 && renderView != null) width = safeWidth(renderView.getWidth());
        if (height <= 1 && renderView != null) height = safeHeight(renderView.getHeight());

        applyImeViewportLayout();
        RenderSize size = updateScaledSizeFromView(width, height);
        refreshSize(size, forceSendWindowSize, reason);
    }

    private void refreshSize(@NonNull RenderSize size) {
        refreshSize(size, false, "refreshSize");
    }

    private void refreshSize(@NonNull RenderSize size, boolean forceSendWindowSize, @NonNull String reason) {
        updateSizeFields(size);

        if (textureView != null && textureView.getSurfaceTexture() != null) {
            textureView.getSurfaceTexture().setDefaultBufferSize(size.renderWidth, size.renderHeight);
        }

        if (nativeSurfaceView != null) {
            applyNativeSurfaceBufferSize(nativeSurfaceView.getHolder(), size);
        }

        if (bridgeWindowAttached) {
            if (forceSendWindowSize) {
                resetSentWindowSize();
            }
            if (sdlWindowBackendRequested) {
                DroidBridgeSDL3Bootstrap.resizeSurface(
                        size.renderWidth,
                        size.renderHeight,
                        size.availableWidth,
                        size.availableHeight
                );
                sendWindowSizeIfChanged(size, "sdl:" + reason);
                return;
            }
            if (isNativeMesaFreedrenoRendererActive()) {
                resizeNativeMesaBridgeWindow(size, reason);
                return;
            }
            sendWindowSizeIfChanged(size, reason);
        }
    }


    /**
     * Performs one real SDL resolution transition after Snapshot 4 has created its
     * live window and begun rendering. A same-size callback is ignored by SDL and
     * RenderPearl, which is why the previous startup refresh did not correct the
     * cursor. The normal in-game resolution slider works because it changes the
     * TextureView buffer, SDL window size, and Minecraft window callback before
     * restoring the requested size. Reproduce that exact sequence once with a
     * barely-visible 99% pulse, without replacing the retained SurfaceTexture.
     */
    public boolean primeSdlStartupResolution(
            @NonNull Runnable onSuccess,
            @NonNull Runnable onFailure
    ) {
        if (!sdlWindowBackendRequested || !bridgeWindowAttached) return false;

        if (sdlStartupResolutionPulseComplete) {
            post(onSuccess);
            return true;
        }
        if (sdlStartupResolutionPulseRunning) return true;

        sdlStartupResolutionPulseRunning = true;
        post(() -> runSdlStartupResolutionPulse(onSuccess, onFailure));
        return true;
    }

    private void runSdlStartupResolutionPulse(
            @NonNull Runnable onSuccess,
            @NonNull Runnable onFailure
    ) {
        TextureView liveTextureView = textureView;
        SurfaceTexture producer = liveTextureView != null
                ? liveTextureView.getSurfaceTexture() : null;
        if (!sdlWindowBackendRequested || !bridgeWindowAttached
                || liveTextureView == null || producer == null
                || textureSurface == null || !textureSurface.isValid()) {
            sdlStartupResolutionPulseRunning = false;
            LauncherLogManager.append(
                    "DroidBridgeSDL3: startup resolution pulse deferred; "
                            + "TextureView producer is not ready");
            onFailure.run();
            return;
        }

        int width = safeWidth(getWidth());
        int height = safeHeight(getHeight());
        if (width <= 1) width = safeWidth(liveTextureView.getWidth());
        if (height <= 1) height = safeHeight(liveTextureView.getHeight());

        final RenderSize target = updateScaledSizeFromView(width, height);
        int primeWidth = Math.max(2, Math.round(target.renderWidth * 0.99f));
        int primeHeight = Math.max(2, Math.round(target.renderHeight * 0.99f));
        if (primeWidth == target.renderWidth) {
            primeWidth = target.renderWidth > 2 ? target.renderWidth - 1
                    : target.renderWidth + 1;
        }
        if (primeHeight == target.renderHeight) {
            primeHeight = target.renderHeight > 2 ? target.renderHeight - 1
                    : target.renderHeight + 1;
        }

        final RenderSize prime = new RenderSize(
                target.availableWidth,
                target.availableHeight,
                target.viewLeft,
                target.viewTop,
                target.viewWidth,
                target.viewHeight,
                primeWidth,
                primeHeight
        );

        try {
            applySdlStartupResolutionPulseSize(
                    producer, prime, "sdl-startup-prime-99");
            LauncherLogManager.append(
                    "DroidBridgeSDL3: startup resolution pulse stage=prime "
                            + target.renderWidth + "x" + target.renderHeight
                            + " -> " + prime.renderWidth + "x" + prime.renderHeight);

            postDelayed(() -> {
                try {
                    TextureView currentTextureView = textureView;
                    SurfaceTexture currentProducer = currentTextureView != null
                            ? currentTextureView.getSurfaceTexture() : null;
                    if (!sdlWindowBackendRequested || !bridgeWindowAttached
                            || currentProducer == null || currentProducer != producer
                            || textureSurface == null || !textureSurface.isValid()) {
                        throw new IllegalStateException(
                                "retained TextureView producer changed during startup pulse");
                    }

                    applySdlStartupResolutionPulseSize(
                            currentProducer, target, "sdl-startup-restore");
                    sdlStartupResolutionPulseComplete = true;
                    sdlStartupResolutionPulseRunning = false;
                    LauncherLogManager.append(
                            "DroidBridgeSDL3: startup resolution pulse stage=restore "
                                    + target.renderWidth + "x" + target.renderHeight);

                    // Let SDL and RenderPearl consume the restored window event before
                    // the launcher recentres and sends the first menu cursor position.
                    postDelayed(onSuccess, 96L);
                } catch (Throwable throwable) {
                    sdlStartupResolutionPulseRunning = false;
                    LauncherLogManager.append(
                            "DroidBridgeSDL3: startup resolution pulse restore failed: "
                                    + throwable);
                    onFailure.run();
                }
            }, 96L);
        } catch (Throwable throwable) {
            sdlStartupResolutionPulseRunning = false;
            LauncherLogManager.append(
                    "DroidBridgeSDL3: startup resolution pulse prime failed: "
                            + throwable);
            onFailure.run();
        }
    }

    private void applySdlStartupResolutionPulseSize(
            @NonNull SurfaceTexture producer,
            @NonNull RenderSize size,
            @NonNull String reason
    ) {
        producer.setDefaultBufferSize(size.renderWidth, size.renderHeight);

        contentLeft = size.viewLeft;
        contentTop = size.viewTop;
        viewWidth = size.viewWidth;
        viewHeight = size.viewHeight;
        renderWidth = size.renderWidth;
        renderHeight = size.renderHeight;
        inputScaleX = renderWidth / (float) Math.max(1, viewWidth);
        inputScaleY = renderHeight / (float) Math.max(1, viewHeight);
        updateSizeFields(size);
        applyImeViewportLayout();

        // Force both halves of the same path used by a real slider change:
        // Android/SDL surface geometry and Minecraft's launcher window callback.
        resetSentWindowSize();
        DroidBridgeSDL3Bootstrap.resizeSurface(
                size.renderWidth,
                size.renderHeight,
                size.availableWidth,
                size.availableHeight
        );
        sendWindowSizeIfChanged(size, reason);
    }

    private void realStart(@NonNull Surface surface, @NonNull RenderSize size, boolean assumeRenderingStarted) {
        attachBridgeWindow(surface, size);
        scheduleSurfaceResizeRefreshes();

        if (assumeRenderingStarted) {
            notifyRenderingStartedSoon();
        }

        if (surfaceReadyListener != null) {
            new Thread(surfaceReadyListener::isReady, "JVM Main thread").start();
        }
    }

    private void attachBridgeWindow(@NonNull Surface surface, @NonNull RenderSize size) {
        publishSdlSurface(surface, size);

        /*
         * SDL3 owns the Android ANativeWindow for Minecraft 26.3 Snapshot 4+.
         * The legacy JRE/GLFW bridge must not create a second EGLSurface or call
         * ANativeWindow_setBuffersGeometry on that same window. Doing so makes
         * SDL's OpenGL creation fail with EGL_BAD_ALLOC and can invalidate an
         * active Vulkan swapchain with VK_ERROR_SURFACE_LOST_KHR.
         */
        if (!sdlWindowBackendRequested) {
            JREUtils.setupBridgeWindow(surface);
        } else {
            Log.i("MinecraftGLSurface",
                    "SDL3 owns Surface; skipped legacy JREUtils.setupBridgeWindow");
        }
        bridgeWindowAttached = true;

        updateSizeFields(size);
        markNativeMesaBridgeSize(size);
        scheduleSdlVisualQuarterTurn(size);
        CallbackBridge.mouseX = sdlWindowBackendRequested
                ? DroidBridgeSDL3Bootstrap.getSdlCursorCoordinateWidth() / 2f
                : CallbackBridge.windowWidth / 2f;
        CallbackBridge.mouseY = sdlWindowBackendRequested
                ? DroidBridgeSDL3Bootstrap.getSdlCursorCoordinateHeight() / 2f
                : CallbackBridge.windowHeight / 2f;
        resetSentWindowSize();
        sendWindowSizeIfChanged(size, "attachBridgeWindow");
        CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
        scheduleSurfaceResizeRefreshes();
    }

    /**
     * Active instance options.txt, used only to keep launcher framebuffer scaling
     * from making Minecraft's GUI physically larger when the framebuffer becomes
     * too small for the user's normal GUI scale.
     */
    public void setMinecraftOptionsFile(@Nullable File optionsFile) {
        minecraftOptionsFile = optionsFile;
        MinecraftGuiScaleResolver.clearCache();
    }

    @NonNull
    private RenderSize updateScaledSizeFromView(int width, int height) {
        int callbackWidth = safeWidth(width);
        int callbackHeight = safeHeight(height);
        int ownWidth = getWidth();
        int ownHeight = getHeight();
        int candidateAvailableWidth = Math.max(1, ownWidth > 1 ? ownWidth : callbackWidth);
        int candidateAvailableHeight = Math.max(1, ownHeight > 1 ? ownHeight : callbackHeight);
        int requestedPercent = clampResolutionScalePercent(
                LauncherPreferences.getGameResolutionScalePercent(getContext())
        );

        GameResolutionSettings.ResolvedResolution candidateBase =
                GameResolutionSettings.resolveBaseResolution(
                        getContext(),
                        candidateAvailableWidth,
                        candidateAvailableHeight
                );
        nativeResolutionLayout = GameResolutionSettings.MODE_NATIVE.equals(candidateBase.mode);

        // In Native mode use the dimensions supplied by the actual surface callback,
        // exactly as DroidBridge did before experimental aspect-ratio support. Using
        // the parent size here can race ahead of an OEM SurfaceView's real buffer size.
        int safeAvailableWidth = nativeResolutionLayout
                ? callbackWidth
                : candidateAvailableWidth;
        int safeAvailableHeight = nativeResolutionLayout
                ? callbackHeight
                : candidateAvailableHeight;

        GameResolutionSettings.ResolvedResolution base =
                GameResolutionSettings.resolveBaseResolution(
                        getContext(),
                        safeAvailableWidth,
                        safeAvailableHeight
                );
        int percent = resolveAntiZoomResolutionScalePercent(base, requestedPercent);
        GameResolutionSettings.ResolvedResolution render;
        GameResolutionSettings.DisplayBounds bounds;
        String resolvedMode = base.mode;

        // A synthetic 1920x1080 buffer is only safe on a composited TextureView path.
        // Direct Mesa, SDL/Vulkan and SurfaceView backends bind the Android native
        // window itself; giving those paths dimensions from a different physical
        // panel can corrupt the drawable and crash the native driver.
        boolean nativeWindowOwnsThorBuffer = isNativeMesaFreedrenoRendererActive()
                || sdlWindowBackendRequested
                || nativeSurfaceView != null;
        Point thorSingleScreenFramebuffer = GameResolutionSettings.MODE_NATIVE.equals(base.mode)
                && !nativeWindowOwnsThorBuffer
                ? AynThorDisplayCompat.resolveSingleScreenGameFramebuffer(
                        getContext(),
                        safeAvailableWidth,
                        safeAvailableHeight
                )
                : null;
        if (thorSingleScreenFramebuffer != null) {
            float scale = percent / 100f;
            render = new GameResolutionSettings.ResolvedResolution(
                    Math.max(1, Math.round(thorSingleScreenFramebuffer.x * scale)),
                    Math.max(1, Math.round(thorSingleScreenFramebuffer.y * scale)),
                    GameResolutionSettings.MODE_NATIVE
            );
            // Keep the lower panel's Android view full-size while using the upper
            // panel's framebuffer. Input mapping then scales 1240-wide touches into
            // the 1920-wide Minecraft cursor space instead of clipping the right side.
            bounds = new GameResolutionSettings.DisplayBounds(
                    0,
                    0,
                    safeAvailableWidth,
                    safeAvailableHeight
            );
            resolvedMode = "native_ayn_thor_upper_panel";
        } else {
            render = GameResolutionSettings.resolveRenderResolution(
                    getContext(),
                    safeAvailableWidth,
                    safeAvailableHeight,
                    percent
            );
            bounds = GameResolutionSettings.resolveDisplayBounds(
                    safeAvailableWidth,
                    safeAvailableHeight,
                    base
            );
        }

        contentLeft = bounds.left;
        contentTop = bounds.top;
        viewWidth = bounds.width;
        viewHeight = bounds.height;
        renderWidth = render.width;
        renderHeight = render.height;
        inputScaleX = renderWidth / (float) Math.max(1, viewWidth);
        inputScaleY = renderHeight / (float) Math.max(1, viewHeight);

        RenderSize size = new RenderSize(
                safeAvailableWidth,
                safeAvailableHeight,
                contentLeft,
                contentTop,
                viewWidth,
                viewHeight,
                renderWidth,
                renderHeight
        );
        logResolutionScaleIfChanged(size, requestedPercent, percent, resolvedMode);
        updateSizeFields(size);
        applyImeViewportLayout();
        return size;
    }

    private int clampResolutionScalePercent(int percent) {
        if (percent < 1) return 1;
        if (percent > 100) return 100;
        return percent;
    }

    /**
     * Whole-framebuffer scaling also shrinks the framebuffer Minecraft uses to clamp
     * guiScale. Once that clamp reaches 1, stretching the low-resolution buffer back
     * to the Android view makes the GUI appear zoomed in. Do not let the selected
     * percentage fall below the point where scale=1 has the same on-screen size as
     * the normal full-resolution GUI. The world/framebuffer is still downscaled; we
     * only prevent percentages that Minecraft cannot represent without GUI inflation.
     */
    private int resolveAntiZoomResolutionScalePercent(
            @NonNull GameResolutionSettings.ResolvedResolution base,
            int requestedPercent
    ) {
        if (GameResolutionSettings.MODE_MCSX.equals(base.mode) || requestedPercent >= 100) {
            return requestedPercent;
        }

        int requestedGuiScale = MinecraftGuiScaleResolver.readGuiScaleCached(minecraftOptionsFile);
        int fullResolutionGuiScale = MinecraftGuiScaleResolver.resolveRequestedScaleForFramebuffer(
                requestedGuiScale,
                Math.max(1, base.width),
                Math.max(1, base.height)
        );
        fullResolutionGuiScale = Math.max(1, fullResolutionGuiScale);

        int noZoomFloor = (int) Math.ceil(100.0 / fullResolutionGuiScale);
        return Math.max(requestedPercent, Math.min(100, noZoomFloor));
    }

    private void logResolutionScaleIfChanged(
            @NonNull RenderSize size,
            int requestedPercent,
            int percent,
            @NonNull String mode
    ) {
        if (lastLoggedRequestedScalePercent == requestedPercent
                && lastLoggedScalePercent == percent
                && lastLoggedViewWidth == size.viewWidth
                && lastLoggedViewHeight == size.viewHeight
                && lastLoggedRenderWidth == size.renderWidth
                && lastLoggedRenderHeight == size.renderHeight) {
            return;
        }

        lastLoggedRequestedScalePercent = requestedPercent;
        lastLoggedScalePercent = percent;
        lastLoggedViewWidth = size.viewWidth;
        lastLoggedViewHeight = size.viewHeight;
        lastLoggedRenderWidth = size.renderWidth;
        lastLoggedRenderHeight = size.renderHeight;

        Log.i("ResolutionScale", "available=" + size.availableWidth + "x" + size.availableHeight
                + " content=" + size.viewWidth + "x" + size.viewHeight
                + "@" + size.viewLeft + "," + size.viewTop
                + " mode=" + mode
                + " requestedPercent=" + requestedPercent
                + " effectivePercent=" + percent
                + (percent != requestedPercent ? " antiZoomFloor=true" : "")
                + " render=" + size.renderWidth + "x" + size.renderHeight
                + " texture=" + (textureView != null)
                + " nativeSurface=" + (nativeSurfaceView != null));
    }

    private void sendWindowSizeIfChanged( RenderSize size,  String reason) {
        int width = Math.max(1, size.renderWidth);
        int height = Math.max(1, size.renderHeight);
        if (width == lastSentWindowWidth && height == lastSentWindowHeight) {
            return;
        }

        lastSentWindowWidth = width;
        lastSentWindowHeight = height;

        Log.i("ResolutionScale", "sendWindowSize reason=" + reason
                + " available=" + size.availableWidth + "x" + size.availableHeight
                + " content=" + size.viewWidth + "x" + size.viewHeight
                + "@" + size.viewLeft + "," + size.viewTop
                + " render=" + width + "x" + height
                + " scale=" + clampResolutionScalePercent(LauncherPreferences.getGameResolutionScalePercent(getContext())) + "%");
        CallbackBridge.sendUpdateWindowSize(width, height);
    }

    private void resetSentWindowSize() {
        lastSentWindowWidth = -1;
        lastSentWindowHeight = -1;
    }

    private void applyNativeSurfaceBufferSize(@NonNull SurfaceHolder holder, @NonNull RenderSize size) {
        int width = Math.max(1, size.renderWidth);
        int height = Math.max(1, size.renderHeight);
        boolean fromLayout = width == size.viewWidth && height == size.viewHeight;

        // Reapplying an unchanged SurfaceHolder size can replace Android's buffer
        // queue on some devices. SDL/Vulkan treats that as a destroyed native
        // window and later fails with VK_ERROR_SURFACE_LOST_KHR.
        if (lastAppliedSurfaceBufferWidth == width
                && lastAppliedSurfaceBufferHeight == height
                && lastAppliedSurfaceSizeFromLayout == fromLayout) {
            return;
        }

        // Once SDL has created its window/swapchain it exclusively owns the live
        // Surface. Resolution changes are reported through SDL callbacks; the
        // holder itself must not be resized behind the active swapchain.
        if (sdlWindowBackendRequested && bridgeWindowAttached) {
            Log.i("MinecraftGLSurface",
                    "SDL3 ignored live SurfaceHolder buffer resize "
                            + lastAppliedSurfaceBufferWidth + "x" + lastAppliedSurfaceBufferHeight
                            + " -> " + width + "x" + height);
            return;
        }

        try {
            if (fromLayout) {
                holder.setSizeFromLayout();
            } else {
                holder.setFixedSize(width, height);
            }
            lastAppliedSurfaceBufferWidth = width;
            lastAppliedSurfaceBufferHeight = height;
            lastAppliedSurfaceSizeFromLayout = fromLayout;
        } catch (Throwable ignored) {
        }
    }


    private void markNativeMesaBridgeSize(@NonNull RenderSize size) {
        if (!isNativeMesaFreedrenoRendererActive()) {
            return;
        }
        lastNativeMesaBridgeWidth = Math.max(1, size.renderWidth);
        lastNativeMesaBridgeHeight = Math.max(1, size.renderHeight);
    }

    private void resizeNativeMesaBridgeWindow(@NonNull RenderSize size, @NonNull String reason) {
        int width = Math.max(1, size.renderWidth);
        int height = Math.max(1, size.renderHeight);

        updateSizeFields(size);

        boolean changed = lastNativeMesaBridgeWidth != width || lastNativeMesaBridgeHeight != height;
        if (changed) {
            Log.i("ResolutionScale", "Native Mesa resizeBridgeWindow reason=" + reason
                    + " old=" + lastNativeMesaBridgeWidth + "x" + lastNativeMesaBridgeHeight
                    + " new=" + width + "x" + height
                    + " view=" + size.viewWidth + "x" + size.viewHeight);
            try {
                // Native Mesa/NativeGLFW must not detach/recreate the EGL window surface for
                // a live resolution-scale slider change. Recreating the EGLSurface while the
                // same Android ANativeWindow is still live can produce EGL_BAD_NATIVE_WINDOW
                // and then all swaps/input target data go invalid. This mirrors the safer
                // GLFW-display-resolution path: update the existing native window/display
                // size and let the current EGLSurface continue.
                JREUtils.resizeBridgeWindow(width, height);
                lastNativeMesaBridgeWidth = width;
                lastNativeMesaBridgeHeight = height;
            } catch (UnsatisfiedLinkError error) {
                Log.w("ResolutionScale", "Native Mesa resizeBridgeWindow native method missing", error);
            } catch (Throwable throwable) {
                Log.w("ResolutionScale", "Native Mesa resizeBridgeWindow failed", throwable);
            }
        }

        CallbackBridge.mouseX = clamp(CallbackBridge.mouseX, 0f, Math.max(0f, width - 1f));
        CallbackBridge.mouseY = clamp(CallbackBridge.mouseY, 0f, Math.max(0f, height - 1f));
        sendWindowSizeIfChanged(size, reason);
    }

    private void updateSizeFields(@NonNull RenderSize size) {
        CallbackBridge.windowWidth = Math.max(1, size.renderWidth);
        CallbackBridge.windowHeight = Math.max(1, size.renderHeight);

        // physical* describes the visible Android game-content rectangle, while window*
        // describes Minecraft's framebuffer. Keeping both spaces allows cursor speed,
        // touch mapping, and aspect-ratio letterboxing to compensate correctly.
        CallbackBridge.physicalWidth = Math.max(1, size.viewWidth);
        CallbackBridge.physicalHeight = Math.max(1, size.viewHeight);
    }

    private int safeWidth(int width) {
        return Math.max(1, width > 0 ? width : getWidth());
    }

    private int safeHeight(int height) {
        return Math.max(1, height > 0 ? height : getHeight());
    }

    private float scaleInputX(float x) {
        return x * inputScaleX;
    }

    private float scaleInputY(float y) {
        return y * inputScaleY;
    }

    private float scaleDeltaX(float dx) {
        return dx * inputScaleX;
    }

    private float scaleDeltaY(float dy) {
        return dy * inputScaleY;
    }

    private static final class RenderSize {
        final int availableWidth;
        final int availableHeight;
        final int viewLeft;
        final int viewTop;
        final int viewWidth;
        final int viewHeight;
        final int renderWidth;
        final int renderHeight;

        RenderSize(
                int availableWidth,
                int availableHeight,
                int viewLeft,
                int viewTop,
                int viewWidth,
                int viewHeight,
                int renderWidth,
                int renderHeight
        ) {
            this.availableWidth = Math.max(1, availableWidth);
            this.availableHeight = Math.max(1, availableHeight);
            this.viewLeft = Math.max(0, viewLeft);
            this.viewTop = Math.max(0, viewTop);
            this.viewWidth = Math.max(1, viewWidth);
            this.viewHeight = Math.max(1, viewHeight);
            this.renderWidth = Math.max(1, renderWidth);
            this.renderHeight = Math.max(1, renderHeight);
        }
    }

    private void notifyRenderingStartedSoon() {
        postDelayed(this::notifyRenderingStartedOnce, 100);
    }

    private void scheduleSurfaceResizeRefreshes() {
        if (sdlWindowBackendRequested) {
            // SDL owns the ANativeWindow and drives its own resize events. The
            // legacy duplicate 0.75-14 second SurfaceHolder refreshes can replace
            // the buffer queue underneath Vulkan and cause SURFACE_LOST.
            return;
        }

        // Normal layout refreshes keep Android's backing surface in sync.
        post(this::refreshSize);
        postDelayed(this::refreshSize, 120L);
        postDelayed(this::refreshSize, 350L);

        // Force a few duplicate GLFW size events after launch. The first
        // attachBridgeWindow() size update can happen before newer Forge/
        // NeoForge/MobileGlues early-display paths have their final window ready.
        // Without these, a below-100% resolution scale can leave the game drawing
        // into the smaller startup viewport until the user changes the slider.
        postDelayed(() -> forceRefreshSize("startup-resync-750"), 750L);
        postDelayed(() -> forceRefreshSize("startup-resync-1500"), 1500L);
        postDelayed(() -> forceRefreshSize("startup-resync-3000"), 3000L);
        postDelayed(() -> forceRefreshSize("startup-resync-6000"), 6000L);
        postDelayed(() -> forceRefreshSize("startup-resync-10000"), 10000L);
        postDelayed(() -> forceRefreshSize("startup-resync-14000"), 14000L);
    }

    private void notifyRenderingStartedOnce() {
        if (renderingStarted) return;
        renderingStarted = true;
        scheduleSurfaceResizeRefreshes();
        if (renderingStartedListener != null) renderingStartedListener.isStarted();
    }

    private void releaseTextureSurface() {
        if (textureSurface != null) {
            textureSurface.release();
            textureSurface = null;
        }
    }

    @Override
    public boolean dispatchTouchEvent(@NonNull MotionEvent event) {
        if (DroidBridgeSDL3Bootstrap.routeTouchEvent(
                event,
                Math.max(1, getWidth()),
                Math.max(1, getHeight()))) {
            return true;
        }

        if (isHardwareMouseLikeEvent(event) && handleHardwareMouseTouchEvent(event)) {
            return true;
        }

        // BTA / LegacyLWJGL3 can leave TextureView/SurfaceView as the touch target,
        // which means FrameLayout.onTouchEvent() may never run. Route normal finger
        // touches directly into the bridge so the BTA main menu is clickable.
        return handleTouchEventInternal(event);
    }

    /**
     * Entry point used by TouchControlsOverlay. Calling this directly avoids Android
     * routing the event into the TextureView/SurfaceView child and bypassing this bridge.
     */
    public boolean handleTouchFromOverlay(@NonNull MotionEvent event) {
        return handleTouchEventInternal(event, true);
    }

    /**
     * Cross-display dual-screen touches must not request Android View/window focus on
     * the remote Minecraft panel. Native GLFW focus is kept alive independently.
     */
    public boolean handleTouchFromOverlay(@NonNull MotionEvent event, boolean allowAndroidViewFocus) {
        return handleTouchEventInternal(event, allowAndroidViewFocus);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return handleTouchEventInternal(event, true);
    }

    private boolean handleTouchEventInternal(@NonNull MotionEvent event) {
        return handleTouchEventInternal(event, true);
    }

    private boolean handleTouchEventInternal(@NonNull MotionEvent event, boolean allowAndroidViewFocus) {
        // TouchControlsOverlay calls this entry point directly, bypassing
        // dispatchTouchEvent(). Keep the SDL-native path here as well.
        if (DroidBridgeSDL3Bootstrap.routeTouchEvent(
                event,
                Math.max(1, getWidth()),
                Math.max(1, getHeight()))) {
            return true;
        }

        if (isHardwareMouseLikeEvent(event)) {
            return handleHardwareMouseTouchEvent(event);
        }

        int action = event.getActionMasked();
        float x = event.getX();
        float y = event.getY();
        boolean currentlyGrabbed = isMinecraftGrabbingNow();
        boolean controllerModTouchCoexistence = isControllerModTouchCoexistenceActive();

        if (handleMenuTwoFingerScroll(event, currentlyGrabbed)) {
            return true;
        }

        if (consumeMenuRegrabTouchIfNeeded(action, x, y, currentlyGrabbed)) {
            return true;
        }

        if (TouchControllerModCompat.isActive()) {
            prepareTouchInputFocus(allowAndroidViewFocus);
            markTouchInputMode();
            TouchControllerModCompat.handleMotionEvent(event, this);

            // Keep menus and inventories on DroidBridge's absolute click fallback,
            // but in grabbed gameplay still provide a narrow relative-mouse fallback
            // for the free-look area. Use the immediate native grab state here, not
            // just the delayed GrabListener state, because Minecraft can re-grab in
            // the same frame that the user releases Back to Game.
            if (isMinecraftGrabbingNow() && !(trackingTouch && !touchStartedWhileGrabbed)) {
                return handleTouchControllerGrabbedLookFallback(event);
            }
        }

        prepareTouchInputFocus(allowAndroidViewFocus);
        markTouchInputMode();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                trackingTouch = true;
                touchMovedPastSlop = false;
                touchLongPressAttackActive = false;
                touchStartedWhileGrabbed = currentlyGrabbed;
                touchMenuMouseButtonDown = false;
                touchDownX = x;
                touchDownY = y;
                lastTouchX = x;
                lastTouchY = y;
                touchUiTapCandidate = isLikelyHotbarTap(x, y);
                if (currentlyGrabbed) {
                    // If the previous touch closed a GUI, the native cursor cache may still
                    // be sitting on that GUI button. Center once at the start of the next
                    // fresh grabbed touch, then let normal relative deltas flow immediately.
                    consumePendingGrabbedTouchBaselineCenter();
                    // Critical: never send an absolute cursor position on ACTION_DOWN while
                    // Minecraft is grabbing the mouse. In grabbed mode, Minecraft treats
                    // cursor movement as camera movement, so warping to the finger location
                    // makes the camera jump toward the touched edge/corner. Store the finger
                    // position only; send relative deltas after the drag passes touch slop.
                    if (!touchUiTapCandidate && ControlsPreferences.isMinecraftTouchGesturesEnabled(getContext())) {
                        scheduleTouchLongPressAttack();
                    }
                } else {
                    sendAbsoluteCursor(x, y);
                    replayBtaDirectTouchCursor(x, y);

                    if (controllerModTouchCoexistence || !allowAndroidViewFocus) {
                        // Controlify/Controllable and cross-display dual-screen touch both
                        // need a real desktop-style press/drag stream. On a secondary touch
                        // panel (allowAndroidViewFocus == false), delaying mouse-down until
                        // Android touch slop is crossed makes thin drag targets such as the
                        // Flashback replay timeline feel dead: the first part of the drag is
                        // only cursor movement and the mod never sees a held button.
                        //
                        // Send mouse-down immediately for cross-display GUI input, keep it
                        // held through MOVE, and release on UP/CANCEL. The existing
                        // finishMenuTouchThatRegrabbedGame()/onGrabState() path still releases
                        // this press safely if a GUI action (for example Back to Game)
                        // re-grabs the mouse while the finger is down.
                        sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, true);
                        touchMenuMouseButtonDown = true;
                    } else {
                        // Match the established Android launcher GUI touch model: a normal
                        // finger tap only positions the cursor on ACTION_DOWN. Do not press
                        // the left mouse button yet. Minecraft 1.21.11/26.x can close the
                        // pause menu on mouse-down, while the Android finger stream is still
                        // active; that transition is what turns the menu cursor position into
                        // a large first grabbed camera delta. A tap is clicked on ACTION_UP,
                        // after the Android stream has finished. Dragging still begins once
                        // movement passes touch slop in ACTION_MOVE below.
                    }
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!trackingTouch) {
                    trackingTouch = true;
                    touchMovedPastSlop = false;
                    touchLongPressAttackActive = false;
                    touchStartedWhileGrabbed = currentlyGrabbed;
                    touchMenuMouseButtonDown = false;
                    touchDownX = x;
                    touchDownY = y;
                    lastTouchX = x;
                    lastTouchY = y;
                    touchUiTapCandidate = isLikelyHotbarTap(x, y);
                    if (currentlyGrabbed) {
                        consumePendingGrabbedTouchBaselineCenter();
                        if (!touchUiTapCandidate && ControlsPreferences.isMinecraftTouchGesturesEnabled(getContext())) {
                            scheduleTouchLongPressAttack();
                        }
                    } else {
                        sendAbsoluteCursor(x, y);
                        replayBtaDirectTouchCursor(x, y);
                    }
                    return true;
                }

                float totalDx = x - touchDownX;
                float totalDy = y - touchDownY;
                if (!touchMovedPastSlop
                        && ((totalDx * totalDx) + (totalDy * totalDy)) > (touchSlop * touchSlop)) {
                    touchMovedPastSlop = true;
                    if (currentlyGrabbed) cancelTouchLongPressAttack(true);
                }

                if (currentlyGrabbed) {
                    if (touchStartedWhileGrabbed) {
                        if (touchMovedPastSlop) {
                            sendRelativeCursor(x - lastTouchX, y - lastTouchY);
                        }
                    } else {
                        // The touch began in a GUI/menu and Minecraft grabbed before the
                        // finger lifted. Do not turn that menu finger position into camera
                        // motion. Release the menu press, center the grabbed baseline, and
                        // wait for the next intentional gameplay touch.
                        finishMenuTouchThatRegrabbedGame();
                    }
                } else {
                    sendAbsoluteCursor(x, y);

                    // Preserve inventory/menu dragging without turning every tap into
                    // an early mouse-down. This is the same split used by the launchers
                    // whose newer Minecraft touch handling does not exhibit the camera
                    // fling: move first, press only after the drag threshold is crossed.
                    if (touchMovedPastSlop && !touchMenuMouseButtonDown) {
                        sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, true);
                        touchMenuMouseButtonDown = true;
                    }
                }

                lastTouchX = x;
                lastTouchY = y;
                return true;

            case MotionEvent.ACTION_UP:
                if (currentlyGrabbed) {
                    if (!touchStartedWhileGrabbed) {
                        finishMenuTouchThatRegrabbedGame();
                    } else {
                        cancelTouchLongPressAttack(false);
                        if (touchLongPressAttackActive) {
                            sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false);
                        } else if (trackingTouch && !touchMovedPastSlop) {
                            if (touchUiTapCandidate || isLikelyHotbarTap(x, y)) {
                                sendHotbarSlotIfNeeded(x, y);
                            } else if (ControlsPreferences.isMinecraftTouchGesturesEnabled(getContext())) {
                                sendUseTap();
                            }
                        }
                    }
                } else {
                    boolean menuTap = trackingTouch && !touchMovedPastSlop;
                    boolean keyboardWasShowing = isAndroidKeyboardShiftActive();

                    if (touchMenuMouseButtonDown) {
                        // A real menu/inventory drag was started after passing touch slop.
                        // Finish it normally at the final absolute cursor position.
                        sendAbsoluteCursor(x, y);
                        sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false);
                        touchMenuMouseButtonDown = false;
                    } else if (menuTap) {
                        // Clear the GUI touch processor state before injecting the click.
                        // If Minecraft pumps the queued mouse-down immediately and switches
                        // to grabbed mode, onGrabState() must not see a menu finger in flight.
                        resetTouchTracking();

                        // Generate the complete GUI click only after Android ACTION_UP.
                        // CallbackBridge uses a short 33 ms press/release pair, matching
                        // the established Pojav/Amethyst approach. Because the finger
                        // stream is already over, Back to Game can re-grab without any
                        // stale finger MOVE/UP becoming camera input.
                        sendTapClickAt(x, y);
                    } else {
                        sendAbsoluteCursor(x, y);
                    }

                    if (menuTap) {
                        if (keyboardWasShowing) {
                            closeAndroidKeyboardAfterMinecraftTapIfNeeded(true);
                        } else {
                            maybeOpenAndroidKeyboardFromMenuTap(x, y);
                        }
                    }
                }
                resetTouchTracking();
                return true;

            case MotionEvent.ACTION_CANCEL:
                boolean keyboardWasShowingOnCancel = !currentlyGrabbed && isAndroidKeyboardShiftActive();
                if (currentlyGrabbed) {
                    if (!touchStartedWhileGrabbed) {
                        finishMenuTouchThatRegrabbedGame();
                    } else {
                        cancelTouchLongPressAttack(true);
                    }
                } else {
                    if (touchMenuMouseButtonDown) {
                        sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false);
                        touchMenuMouseButtonDown = false;
                    }
                    // A cancelled tap never pressed the mouse button, so do not inject
                    // a stray release into Minecraft's newer mouse state machine.
                    closeAndroidKeyboardAfterMinecraftTapIfNeeded(keyboardWasShowingOnCancel);
                }
                resetTouchTracking();
                return true;

            default:
                return true;
        }
    }


    private boolean handleMenuTwoFingerScroll(@NonNull MotionEvent event, boolean currentlyGrabbed) {
        int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            resetMenuTwoFingerScroll();
            return false;
        }

        if (currentlyGrabbed) {
            if (menuTwoFingerScrollActive || menuTwoFingerScrollBlockingUntilAllUp) {
                resetMenuTwoFingerScroll();
                resetTouchTracking();
                return true;
            }
            return false;
        }

        if (menuTwoFingerScrollBlockingUntilAllUp) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                resetMenuTwoFingerScroll();
                resetTouchTracking();
            }
            return true;
        }

        if (menuTwoFingerScrollActive) {
            if (action == MotionEvent.ACTION_MOVE) {
                updateMenuTwoFingerScroll(event);
                return true;
            }

            if (action == MotionEvent.ACTION_POINTER_UP) {
                updateMenuTwoFingerScroll(event);
                menuTwoFingerScrollActive = false;
                menuTwoFingerScrollBlockingUntilAllUp = true;
                return true;
            }

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                resetMenuTwoFingerScroll();
                resetTouchTracking();
                return true;
            }

            return true;
        }

        if (action != MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() < 2) {
            return false;
        }

        int secondIndex = event.getActionIndex();
        int firstIndex = -1;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (i != secondIndex) {
                firstIndex = i;
                break;
            }
        }
        if (firstIndex < 0 || secondIndex < 0 || secondIndex >= event.getPointerCount()) {
            return false;
        }

        if (touchMenuMouseButtonDown) {
            sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false);
            touchMenuMouseButtonDown = false;
        }
        resetTouchTracking();

        menuTwoFingerScrollPointerA = event.getPointerId(firstIndex);
        menuTwoFingerScrollPointerB = event.getPointerId(secondIndex);
        menuTwoFingerScrollLastY = (event.getY(firstIndex) + event.getY(secondIndex)) * 0.5f;
        menuTwoFingerScrollAccumulator = 0f;
        menuTwoFingerScrollActive = true;
        menuTwoFingerScrollBlockingUntilAllUp = false;
        return true;
    }

    private void updateMenuTwoFingerScroll(@NonNull MotionEvent event) {
        int indexA = event.findPointerIndex(menuTwoFingerScrollPointerA);
        int indexB = event.findPointerIndex(menuTwoFingerScrollPointerB);
        if (indexA < 0 || indexB < 0) return;

        float currentY = (event.getY(indexA) + event.getY(indexB)) * 0.5f;
        float deltaY = currentY - menuTwoFingerScrollLastY;
        menuTwoFingerScrollLastY = currentY;

        float pixelsPerStep = Math.max(1f,
                MENU_TWO_FINGER_SCROLL_DP_PER_STEP * getResources().getDisplayMetrics().density);
        menuTwoFingerScrollAccumulator += deltaY / pixelsPerStep;
        if (Math.abs(menuTwoFingerScrollAccumulator) < 1f) return;

        int steps = (int) menuTwoFingerScrollAccumulator;
        menuTwoFingerScrollAccumulator -= steps;
        CallbackBridge.setInputReady(true);
        CallbackBridge.sendScroll(0d, steps);
    }

    private void resetMenuTwoFingerScroll() {
        menuTwoFingerScrollActive = false;
        menuTwoFingerScrollBlockingUntilAllUp = false;
        menuTwoFingerScrollPointerA = -1;
        menuTwoFingerScrollPointerB = -1;
        menuTwoFingerScrollLastY = 0f;
        menuTwoFingerScrollAccumulator = 0f;
    }

    private boolean consumeMenuRegrabTouchIfNeeded(int action, float x, float y, boolean currentlyGrabbed) {
        if (!currentlyGrabbed) {
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_CANCEL) {
                consumingMenuRegrabTouchStream = false;
            }
            return false;
        }

        if (consumingMenuRegrabTouchStream || shouldConsumeFreshGrabbedTouchAfterMenuClick(action)) {
            consumingMenuRegrabTouchStream = action != MotionEvent.ACTION_UP
                    && action != MotionEvent.ACTION_CANCEL
                    && action != MotionEvent.ACTION_POINTER_UP;

            // Do not let this menu-originated finger become a grabbed camera drag.
            // Keep the cursor baseline centered and release any GUI mouse press that
            // may still be held from the Back to Game click.
            cancelTouchLongPressAttack(true);
            if (touchMenuMouseButtonDown) {
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false);
                touchMenuMouseButtonDown = false;
            }
            centerGrabbedCursorAfterMenuTouch();
            lastTouchX = x;
            lastTouchY = y;

            if (!consumingMenuRegrabTouchStream) {
                resetTouchTracking();
            }
            return true;
        }

        return false;
    }

    private boolean shouldConsumeFreshGrabbedTouchAfterMenuClick(int action) {
        if (action != MotionEvent.ACTION_DOWN
                && action != MotionEvent.ACTION_MOVE
                && action != MotionEvent.ACTION_UP
                && action != MotionEvent.ACTION_CANCEL
                && action != MotionEvent.ACTION_POINTER_DOWN
                && action != MotionEvent.ACTION_POINTER_UP) {
            return false;
        }

        long until = consumeGrabbedTouchAfterMenuClickUntilNanos;
        if (until <= 0L || System.nanoTime() > until) {
            return false;
        }

        return shouldCenterAfterRecentTouchMenuClick();
    }

    private boolean isMinecraftGrabbingNow() {
        return grabbed || CallbackBridge.isGrabbing();
    }


    /**
     * Controller mods switch between controller and mouse/touch input modes. Their
     * menus need a real pointer DOWN/UP sequence rather than DroidBridge's deferred
     * tap-only compatibility path so the first touchscreen tap is accepted.
     */
    private boolean isControllerModTouchCoexistenceActive() {
        return ControllerModCompat.isControllableActive() || ControlifySDL.isActive();
    }



    private void markRecentTouchMenuClick(float x, float y) {
        long now = System.nanoTime();
        lastTouchMenuClickNanos = now;
        consumeGrabbedTouchAfterMenuClickUntilNanos = now + TOUCH_MENU_REGRAB_STALE_TOUCH_NANOS;
        lastTouchMenuClickX = x;
        lastTouchMenuClickY = y;
        CallbackBridge.centerCursorOnNextGrab(TOUCH_MENU_REGRAB_CENTER_MS);
    }

    private boolean shouldCenterAfterRecentTouchMenuClick() {
        long lastClick = lastTouchMenuClickNanos;
        return lastClick > 0L && System.nanoTime() - lastClick <= TOUCH_MENU_REGRAB_CENTER_NANOS;
    }

    private void finishMenuTouchThatRegrabbedGame() {
        markRecentTouchMenuClick(lastTouchX != 0f ? lastTouchX : touchDownX, lastTouchY != 0f ? lastTouchY : touchDownY);
        if (touchMenuMouseButtonDown) {
            sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false);
            touchMenuMouseButtonDown = false;
        }
        centerGrabbedCursorAfterMenuTouch();
    }

    private void centerGrabbedCursorAfterMenuTouch() {
        // Center the native cursor cache and consume only the still-active
        // menu-originated touch stream. This prevents the Back to Game finger
        // itself from becoming the first grabbed camera-look input, while the
        // next deliberate gameplay touch is still allowed normally.
        CallbackBridge.centerCursorOnNextGrabbedTouch(TOUCH_MENU_REGRAB_CENTER_MS);
        consumeGrabbedTouchAfterMenuClickUntilNanos = System.nanoTime() + TOUCH_MENU_REGRAB_STALE_TOUCH_NANOS;
        recenterMouse(false);
        postDelayed(() -> recenterMouse(false), POINTER_REGRAB_SILENT_CURSOR_SYNC_DELAY_MS);
        postDelayed(() -> recenterMouse(false), POINTER_REGRAB_SILENT_CURSOR_SYNC_DELAY_MS * 3L);
    }

    private void consumePendingGrabbedTouchBaselineCenter() {
        CallbackBridge.setInputReady(true);
        CallbackBridge.centerCursorForPendingGrabbedTouch();
    }

    private boolean handleTouchControllerGrabbedLookFallback(@NonNull MotionEvent event) {
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int index = event.getActionIndex();
                if (touchControllerLookPointerId == -1 && isTouchControllerLookFallbackStart(event, index)) {
                    consumePendingGrabbedTouchBaselineCenter();
                    startTouchControllerLookFallback(event, index);
                }
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (touchControllerLookPointerId == -1) {
                    return true;
                }

                int index = event.findPointerIndex(touchControllerLookPointerId);
                if (index < 0) {
                    resetTouchControllerLookFallback();
                    return true;
                }

                float x = event.getX(index);
                float y = event.getY(index);
                float totalDx = x - touchControllerLookDownX;
                float totalDy = y - touchControllerLookDownY;
                if (!touchControllerLookMovedPastSlop
                        && ((totalDx * totalDx) + (totalDy * totalDy)) > (touchSlop * touchSlop)) {
                    touchControllerLookMovedPastSlop = true;
                }

                if (touchControllerLookMovedPastSlop) {
                    sendRelativeCursor(x - touchControllerLookLastX, y - touchControllerLookLastY);
                }

                touchControllerLookLastX = x;
                touchControllerLookLastY = y;
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                int index = event.getActionIndex();
                if (index >= 0 && index < event.getPointerCount()
                        && event.getPointerId(index) == touchControllerLookPointerId) {
                    resetTouchControllerLookFallback();
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                resetTouchControllerLookFallback();
                return true;

            default:
                return true;
        }
    }

    private boolean isTouchControllerLookFallbackStart(@NonNull MotionEvent event, int index) {
        if (index < 0 || index >= event.getPointerCount()) return false;

        float x = event.getX(index);
        float y = event.getY(index);
        int width = Math.max(1, getWidth());
        int height = Math.max(1, getHeight());

        // Approximate TouchController's normal layout: left side is usually movement,
        // bottom/right is usually buttons or hotbar, and the right-middle/top area is
        // free camera look. The launcher cannot see the mod's internal widget bounds,
        // so keep this deliberately conservative.
        if (x < width * 0.45f) return false;
        if (y > height * 0.82f) return false;
        return !isLikelyHotbarTap(x, y);
    }

    private void startTouchControllerLookFallback(@NonNull MotionEvent event, int index) {
        touchControllerLookPointerId = event.getPointerId(index);
        touchControllerLookDownX = event.getX(index);
        touchControllerLookDownY = event.getY(index);
        touchControllerLookLastX = touchControllerLookDownX;
        touchControllerLookLastY = touchControllerLookDownY;
        touchControllerLookMovedPastSlop = false;
    }

    private void resetTouchControllerLookFallback() {
        touchControllerLookPointerId = -1;
        touchControllerLookLastX = 0f;
        touchControllerLookLastY = 0f;
        touchControllerLookDownX = 0f;
        touchControllerLookDownY = 0f;
        touchControllerLookMovedPastSlop = false;
    }

    private boolean handleHardwareMouseTouchEvent(@NonNull MotionEvent event) {
        int pointerIndex = findMousePointerIndex(event);
        if (pointerIndex < 0) pointerIndex = 0;

        requestFocusIfNeeded();
        markHardwarePointerInputMode(event);
        noteHardwareMouseButtonMotionEvent(event);

        float x = safeEventX(event, pointerIndex);
        float y = safeEventY(event, pointerIndex);
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (isHardwareMenuSoftwareCursorRouting()) {
                    safeRequestPointerCapture();
                    return sendHardwareMouseButtonsDownForEvent(event);
                }
                updateHardwareMousePositionIfUsable(event, x, y);
                if (!grabbed) sendHardwareAbsoluteCursor(event, x, y);
                return sendHardwareMouseButtonsDownForEvent(event);

            case MotionEvent.ACTION_UP:
                boolean keyboardWasShowingOnMouseUp = !grabbed && isAndroidKeyboardShiftActive();
                boolean hardwareMenuClick = !grabbed;
                boolean softwareMenuMouseUp = isHardwareMenuSoftwareCursorRouting();
                if (!grabbed && !softwareMenuMouseUp) sendHardwareAbsoluteCursor(event, x, y);
                releaseMouseButtonsForEvent(event);
                if (!softwareMenuMouseUp) {
                    updateHardwareMousePositionIfUsable(event, x, y);
                }
                if (hardwareMenuClick) {
                    if (keyboardWasShowingOnMouseUp) {
                        closeAndroidKeyboardAfterMinecraftTapIfNeeded(true);
                    } else if (softwareMenuMouseUp) {
                        maybeOpenAndroidKeyboardFromCurrentSoftwareCursor();
                    } else {
                        maybeOpenAndroidKeyboardFromMenuTap(x, y);
                    }
                }
                return true;

            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE:
                if (grabbed) {
                    RelativeMouseDelta delta = collectRelativeMouseDelta(event, pointerIndex, false);
                    float relX = delta.dx;
                    float relY = delta.dy;
                    if (!delta.hasMovement) {
                        if (!hasLastHardwareMousePosition || !isUsableHardwareAbsoluteCoordinate(event, x, y)) {
                            updateHardwareMousePositionIfUsable(event, x, y);
                            return true;
                        }
                        relX = x - lastHardwareMouseX;
                        relY = y - lastHardwareMouseY;
                    }
                    sendHardwareRelativeCursor(relX, relY);
                } else if (isHardwareMenuSoftwareCursorRouting()) {
                    safeRequestPointerCapture();
                    // While Android is switching into pointer-capture mode, absolute
                    // coordinates still describe the old OS pointer location. Ignore
                    // them so the freshly centered Minecraft/software cursor cannot
                    // jump back to that stale location before relative events begin.
                    if (!hasActivePointerCapture()) return true;
                    RelativeMouseDelta delta = collectRelativeMouseDelta(event, pointerIndex, true);
                    if (delta.hasMovement) {
                        sendHardwareMenuRelativeCursor(delta.dx, delta.dy);
                    }
                    return true;
                } else {
                    sendHardwareAbsoluteCursor(event, x, y);
                }
                updateHardwareMousePositionIfUsable(event, x, y);
                return true;

            case MotionEvent.ACTION_BUTTON_PRESS:
                if (isHardwareMenuSoftwareCursorRouting()) {
                    safeRequestPointerCapture();
                    return sendMouseButtonUnconvertedTracked(event, true, pointerIndex);
                }
                if (!grabbed) sendHardwareAbsoluteCursor(event, x, y);
                updateHardwareMousePositionIfUsable(event, x, y);
                return sendMouseButtonUnconvertedTracked(event, true, pointerIndex);

            case MotionEvent.ACTION_BUTTON_RELEASE:
                boolean keyboardWasShowingOnHardwareButtonRelease = !grabbed && isAndroidKeyboardShiftActive();
                boolean hardwareMenuButtonClick = !grabbed;
                boolean softwareMenuButtonRelease = isHardwareMenuSoftwareCursorRouting();
                if (!grabbed && !softwareMenuButtonRelease) sendHardwareAbsoluteCursor(event, x, y);
                if (!softwareMenuButtonRelease) {
                    updateHardwareMousePositionIfUsable(event, x, y);
                }
                boolean handledRelease = sendMouseButtonUnconvertedTracked(event, false, pointerIndex);
                if (hardwareMenuButtonClick) {
                    if (keyboardWasShowingOnHardwareButtonRelease) {
                        closeAndroidKeyboardAfterMinecraftTapIfNeeded(true);
                    } else if (softwareMenuButtonRelease) {
                        maybeOpenAndroidKeyboardFromCurrentSoftwareCursor();
                    } else {
                        maybeOpenAndroidKeyboardFromMenuTap(x, y);
                    }
                }
                return handledRelease;

            case MotionEvent.ACTION_CANCEL:
                releaseAllHardwareMouseButtons();
                resetHardwarePointerTracking(false);
                return true;

            default:
                return false;
        }
    }

    /**
     * Handles mouse-like ACTION_DOWN events without assuming that every DOWN is the
     * primary button. A number of Android/OEM input stacks (including some iQOO /
     * OriginOS devices and Bluetooth/OTG mouse combinations) deliver a secondary
     * click as ACTION_DOWN with BUTTON_SECONDARY in buttonState, followed by the
     * normal ACTION_BUTTON_PRESS event. Treating that ACTION_DOWN as left click makes
     * one physical right click become left-down + right-down inside Minecraft.
     */
    private boolean sendHardwareMouseButtonsDownForEvent(@NonNull MotionEvent event) {
        int buttonState = event.getButtonState();
        boolean handled = false;

        // buttonState is the most reliable discriminator for ACTION_DOWN on the
        // affected devices. It also preserves intentional multi-button presses.
        if ((buttonState & MotionEvent.BUTTON_PRIMARY) != 0) {
            handled |= sendHardwareMouseButtonTracked(
                    LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, true);
        }
        if ((buttonState & MotionEvent.BUTTON_SECONDARY) != 0) {
            handled |= sendHardwareMouseButtonTracked(
                    LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, true);
        }
        if ((buttonState & MotionEvent.BUTTON_TERTIARY) != 0) {
            handled |= sendHardwareMouseButtonTracked(
                    LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE, true);
        }
        if ((buttonState & MotionEvent.BUTTON_BACK) != 0) {
            handled |= sendHardwareMouseButtonTracked(
                    LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_BACK, true);
        }
        if ((buttonState & MotionEvent.BUTTON_FORWARD) != 0) {
            handled |= sendHardwareMouseButtonTracked(
                    LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_FORWARD, true);
        }

        if (handled) return true;

        // Unknown extra buttons must remain distinct; never turn them into a fake
        // primary click just because Android delivered a touch-style ACTION_DOWN.
        if (buttonState != 0) return true;

        // Some drivers omit buttonState but still provide actionButton. Prefer it
        // before falling back to the legacy assumption that ACTION_DOWN means left.
        int actionButton = androidMouseButtonToGlfw(event.getActionButton());
        if (actionButton >= 0) {
            return sendHardwareMouseButtonTracked(actionButton, true);
        }

        // Legacy Android mouse implementations use a touch-style ACTION_DOWN/UP
        // pair for primary clicks and expose neither buttonState nor actionButton.
        return sendHardwareMouseButtonTracked(
                LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, true);
    }

    private void releaseMouseButtonsForEvent(@NonNull MotionEvent event) {
        int actionButton = androidMouseButtonToGlfw(event.getActionButton());
        if (actionButton >= 0) {
            sendHardwareMouseButtonTracked(actionButton, false);
            return;
        }

        // ACTION_UP often arrives after Android has cleared buttonState. Release
        // every tracked button that is no longer represented in the current state.
        // This also preserves another button during a real multi-button chord.
        int buttonState = event.getButtonState();
        for (Integer heldButton : new HashSet<>(hardwareMouseButtonsDown)) {
            if (heldButton == null) continue;
            if (!isGlfwMouseButtonPresentInAndroidState(heldButton, buttonState)) {
                sendHardwareMouseButtonTracked(heldButton, false);
            }
        }
    }

    private static boolean isGlfwMouseButtonPresentInAndroidState(int glfwButton, int buttonState) {
        switch (glfwButton) {
            case LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT:
                return (buttonState & MotionEvent.BUTTON_PRIMARY) != 0;
            case LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT:
                return (buttonState & MotionEvent.BUTTON_SECONDARY) != 0;
            case LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE:
                return (buttonState & MotionEvent.BUTTON_TERTIARY) != 0;
            case LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_BACK:
                return (buttonState & MotionEvent.BUTTON_BACK) != 0;
            case LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_FORWARD:
                return (buttonState & MotionEvent.BUTTON_FORWARD) != 0;
            default:
                return false;
        }
    }

    private void releaseAllHardwareMouseButtons() {
        if (hardwareMouseButtonsDown.isEmpty()) return;
        for (Integer button : new HashSet<>(hardwareMouseButtonsDown)) {
            if (button != null) sendMouseButton(button, false);
        }
        hardwareMouseButtonsDown.clear();
    }

    private boolean sendHardwareMouseButtonTracked(int glfwButton, boolean status) {
        if (glfwButton < 0) return false;

        if (status) {
            if (!hardwareMouseButtonsDown.add(glfwButton)) {
                return true;
            }
        } else {
            if (!hardwareMouseButtonsDown.remove(glfwButton)) {
                return true;
            }
        }

        sendMouseButton(glfwButton, status);
        return true;
    }

    private static float safeEventX(@NonNull MotionEvent event, int pointerIndex) {
        try {
            if (pointerIndex >= 0 && pointerIndex < event.getPointerCount()) return event.getX(pointerIndex);
        } catch (Throwable ignored) {
        }
        return event.getX();
    }

    private static float safeEventY(@NonNull MotionEvent event, int pointerIndex) {
        try {
            if (pointerIndex >= 0 && pointerIndex < event.getPointerCount()) return event.getY(pointerIndex);
        } catch (Throwable ignored) {
        }
        return event.getY();
    }

    private static boolean isHardwareMouseLikeEvent(@NonNull MotionEvent event) {
        int source = event.getSource();
        if ((source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || (source & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
                || (source & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) {
            return true;
        }

        for (int i = 0; i < event.getPointerCount(); i++) {
            int toolType = event.getToolType(i);
            if (toolType == MotionEvent.TOOL_TYPE_MOUSE) return true;
        }

        return false;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (DroidBridgeSDL3Bootstrap.routeGenericMotionEvent(event)) {
            return true;
        }

        if (isGamepadMotionEvent(event)) {
            if (sdlEnabled && SDLControllerManager.handleJoystickMotionEvent(event)) {
                return true;
            }

            if (controllerModOwnsGamepadInput) {
                if (ControllerModCompat.shouldMirrorAndroidGamepadToGlfw()) {
                    GamepadInputController.feedGlfwGamepadMirrorMotion(event);
                }
                return true;
            }

            // BTA is old LWJGL input and does not use Controlify. When SDL routing
            // is disabled, keep controllers usable by mapping Android gamepad axes
            // into basic keyboard/mouse events.
            if (handleControllerMotionFallback(event)) {
                return true;
            }

            return super.dispatchGenericMotionEvent(event);
        }

        int pointerIndex = findMousePointerIndex(event);
        if (pointerIndex < 0) {
            return super.dispatchGenericMotionEvent(event);
        }

        requestFocusIfNeeded();
        markHardwarePointerInputMode(event);
        noteHardwareMouseButtonMotionEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_MOVE:
                float x = safeEventX(event, pointerIndex);
                float y = safeEventY(event, pointerIndex);
                if (grabbed) {
                    RelativeMouseDelta delta = collectRelativeMouseDelta(event, pointerIndex, false);
                    float relX = delta.dx;
                    float relY = delta.dy;
                    if (!delta.hasMovement) {
                        if (!hasLastHardwareMousePosition || !isUsableHardwareAbsoluteCoordinate(event, x, y)) {
                            updateHardwareMousePositionIfUsable(event, x, y);
                            return true;
                        }
                        relX = x - lastHardwareMouseX;
                        relY = y - lastHardwareMouseY;
                    }
                    sendHardwareRelativeCursor(relX, relY);
                    updateHardwareMousePositionIfUsable(event, x, y);
                } else if (isHardwareMenuSoftwareCursorRouting()) {
                    safeRequestPointerCapture();
                    // Do not let the stale Android absolute pointer overwrite the
                    // centered logical cursor while pointer capture is becoming live.
                    if (!hasActivePointerCapture()) return true;
                    RelativeMouseDelta delta = collectRelativeMouseDelta(event, pointerIndex, true);
                    if (delta.hasMovement) {
                        sendHardwareMenuRelativeCursor(delta.dx, delta.dy);
                    }
                } else {
                    sendHardwareAbsoluteCursor(event, x, y);
                    updateHardwareMousePositionIfUsable(event, x, y);
                }
                return true;

            case MotionEvent.ACTION_SCROLL:
                CallbackBridge.sendScroll(
                        event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                        event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                );
                return true;

            case MotionEvent.ACTION_BUTTON_PRESS:
                if (isHardwareMenuSoftwareCursorRouting()) {
                    safeRequestPointerCapture();
                    return sendMouseButtonUnconvertedTracked(event, true, pointerIndex);
                }
                if (!grabbed) sendHardwareAbsoluteCursor(event, safeEventX(event, pointerIndex), safeEventY(event, pointerIndex));
                updateHardwareMousePositionIfUsable(event, safeEventX(event, pointerIndex), safeEventY(event, pointerIndex));
                return sendMouseButtonUnconvertedTracked(event, true, pointerIndex);

            case MotionEvent.ACTION_BUTTON_RELEASE:
                float releaseX = safeEventX(event, pointerIndex);
                float releaseY = safeEventY(event, pointerIndex);
                boolean keyboardWasShowingOnGenericRelease = !grabbed && isAndroidKeyboardShiftActive();
                boolean genericMenuButtonClick = !grabbed;
                boolean softwareGenericRelease = isHardwareMenuSoftwareCursorRouting();
                if (!grabbed && !softwareGenericRelease) sendHardwareAbsoluteCursor(event, releaseX, releaseY);
                if (!softwareGenericRelease) {
                    updateHardwareMousePositionIfUsable(event, releaseX, releaseY);
                }
                boolean genericHandledRelease = sendMouseButtonUnconvertedTracked(event, false, pointerIndex);
                if (genericMenuButtonClick) {
                    if (keyboardWasShowingOnGenericRelease) {
                        closeAndroidKeyboardAfterMinecraftTapIfNeeded(true);
                    } else if (softwareGenericRelease) {
                        maybeOpenAndroidKeyboardFromCurrentSoftwareCursor();
                    } else {
                        maybeOpenAndroidKeyboardFromMenuTap(releaseX, releaseY);
                    }
                }
                return genericHandledRelease;

            default:
                return super.dispatchGenericMotionEvent(event);
        }
    }

    private boolean routeCapturedAndroidVirtualMouseUi(@NonNull MotionEvent event, int normalizedAction) {
        if (!shouldUseAndroidVirtualPhysicalMouse() || grabbed) return false;
        AndroidVirtualMouseUiRouter router = androidVirtualMouseUiRouter;
        if (router == null) return false;

        float localX = mapCursorXToViewX(CallbackBridge.mouseX);
        float localY = mapCursorYToViewY(CallbackBridge.mouseY);
        int[] location = new int[2];
        try {
            getLocationOnScreen(location);
        } catch (Throwable ignored) {
            location[0] = 0;
            location[1] = 0;
        }

        try {
            return router.onAndroidVirtualMouseUiEvent(
                    normalizedAction,
                    location[0] + localX,
                    location[1] + localY,
                    event.getActionButton(),
                    event.getButtonState(),
                    event.getEventTime()
            );
        } catch (Throwable throwable) {
            Log.w("MinecraftGLSurface", "Android Virtual Mouse UI router failed", throwable);
            return false;
        }
    }

    private boolean handleCapturedPointer(View view, MotionEvent event) {
        requestFocusIfNeeded();
        markHardwarePointerInputMode(event);
        noteHardwareMouseButtonMotionEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE:
                RelativeMouseDelta delta = collectRelativeMouseDelta(event, 0, true);
                if (!delta.hasMovement) {
                    // Under Android pointer capture, getX()/getY() can be a synthetic
                    // 0,0 position on some devices after touch input or dialogs. Treat
                    // that as "no movement" instead of warping the virtual cursor to a
                    // screen corner.
                    if (!grabbed) {
                        routeCapturedAndroidVirtualMouseUi(event, MotionEvent.ACTION_MOVE);
                    }
                    return true;
                }
                if (grabbed) {
                    sendHardwareRelativeCursor(delta.dx, delta.dy);
                } else {
                    sendHardwareMenuRelativeCursor(delta.dx, delta.dy);
                    routeCapturedAndroidVirtualMouseUi(event, MotionEvent.ACTION_MOVE);
                }
                return true;

            case MotionEvent.ACTION_SCROLL:
                CallbackBridge.sendScroll(
                        event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                        event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                );
                return true;

            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_BUTTON_PRESS:
                // Pointer-capture coordinates are relative/synthetic. Never overwrite
                // the menu cursor with event.getX()/getY() here; click exactly where
                // the software/logical cursor is currently drawn. Android Virtual Mouse
                // gets first chance to activate DroidBridge-owned Android UI at that
                // logical position. If it consumes the click, do not click Minecraft too.
                if (!grabbed && routeCapturedAndroidVirtualMouseUi(event, MotionEvent.ACTION_DOWN)) {
                    return true;
                }
                return sendMouseButtonUnconvertedTracked(event, true, 0);

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_BUTTON_RELEASE:
                if (!grabbed && routeCapturedAndroidVirtualMouseUi(event, MotionEvent.ACTION_UP)) {
                    return true;
                }
                boolean keyboardWasShowingOnCapturedRelease = !grabbed && isAndroidKeyboardShiftActive();
                boolean capturedMenuButtonClick = !grabbed;
                boolean capturedHandledRelease = sendMouseButtonUnconvertedTracked(event, false, 0);
                if (capturedMenuButtonClick) {
                    if (keyboardWasShowingOnCapturedRelease) {
                        closeAndroidKeyboardAfterMinecraftTapIfNeeded(true);
                    } else {
                        maybeOpenAndroidKeyboardFromCurrentSoftwareCursor();
                    }
                }
                return capturedHandledRelease;

            case MotionEvent.ACTION_CANCEL:
                if (!grabbed && routeCapturedAndroidVirtualMouseUi(event, MotionEvent.ACTION_CANCEL)) {
                    return true;
                }
                return false;

            default:
                return false;
        }
    }

    private static boolean isGamepadMotionEvent(@NonNull MotionEvent event) {
        int source = event.getSource();
        return (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
    }

    private static int findMousePointerIndex(@NonNull MotionEvent event) {
        for (int i = 0; i < event.getPointerCount(); i++) {
            int toolType = event.getToolType(i);
            if (toolType == MotionEvent.TOOL_TYPE_MOUSE || toolType == MotionEvent.TOOL_TYPE_STYLUS) {
                return i;
            }
        }

        int source = event.getSource();
        if ((source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || (source & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
                || (source & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) {
            return 0;
        }

        return -1;
    }

    @NonNull
    private static RelativeMouseDelta collectRelativeMouseDelta(
            @NonNull MotionEvent event,
            int pointerIndex,
            boolean capturedPointerEvent
    ) {
        int safePointerIndex = safePointerIndex(event, pointerIndex);

        // Many Android mouse paths report real relative movement through
        // AXIS_RELATIVE_X/Y. Sum historical samples too; high-polling mice can
        // batch several small deltas into one MotionEvent, and reading only the
        // latest sample makes fast flicks feel slow or stuck.
        float relX = sumRelativeAxis(event, MotionEvent.AXIS_RELATIVE_X, safePointerIndex);
        float relY = sumRelativeAxis(event, MotionEvent.AXIS_RELATIVE_Y, safePointerIndex);
        if (hasRelativeMovement(relX, relY)) {
            return new RelativeMouseDelta(relX, relY, true);
        }

        // Android pointer capture is inconsistent between devices. Some mice use
        // AXIS_RELATIVE_X/Y, while others deliver the captured relative mouse
        // movement through getX()/getY(). Use X/Y only for captured or explicitly
        // relative mouse events, never for normal absolute menu hover events.
        if (capturedPointerEvent || isRelativeMouseSource(event)) {
            relX = sumCapturedPointerCoordinate(event, safePointerIndex, true);
            relY = sumCapturedPointerCoordinate(event, safePointerIndex, false);
            if (hasRelativeMovement(relX, relY)) {
                return new RelativeMouseDelta(relX, relY, true);
            }
        }

        return RelativeMouseDelta.NONE;
    }

    private static int safePointerIndex(@NonNull MotionEvent event, int pointerIndex) {
        if (pointerIndex >= 0 && pointerIndex < event.getPointerCount()) return pointerIndex;
        return 0;
    }

    private static float sumRelativeAxis(@NonNull MotionEvent event, int axis, int pointerIndex) {
        float sum = 0f;
        int safePointerIndex = safePointerIndex(event, pointerIndex);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int historySize = event.getHistorySize();
            for (int h = 0; h < historySize; h++) {
                try {
                    sum += event.getHistoricalAxisValue(axis, safePointerIndex, h);
                } catch (Throwable ignored) {
                    sum += event.getHistoricalAxisValue(axis, h);
                }
            }

            try {
                sum += event.getAxisValue(axis, safePointerIndex);
            } catch (Throwable ignored) {
                sum += event.getAxisValue(axis);
            }
            return sum;
        }

        return event.getAxisValue(axis);
    }

    private static float sumCapturedPointerCoordinate(
            @NonNull MotionEvent event,
            int pointerIndex,
            boolean xAxis
    ) {
        float sum = 0f;
        int safePointerIndex = safePointerIndex(event, pointerIndex);
        int historySize = event.getHistorySize();
        for (int h = 0; h < historySize; h++) {
            try {
                sum += xAxis
                        ? event.getHistoricalX(safePointerIndex, h)
                        : event.getHistoricalY(safePointerIndex, h);
            } catch (Throwable ignored) {
                sum += xAxis ? event.getHistoricalX(h) : event.getHistoricalY(h);
            }
        }
        return sum + (xAxis ? safeEventX(event, safePointerIndex) : safeEventY(event, safePointerIndex));
    }

    private static boolean isRelativeMouseSource(@NonNull MotionEvent event) {
        return (event.getSource() & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE;
    }

    private static boolean hasRelativeMovement(float dx, float dy) {
        return dx != 0f || dy != 0f;
    }

    private static final class RelativeMouseDelta {
        static final RelativeMouseDelta NONE = new RelativeMouseDelta(0f, 0f, false);

        final float dx;
        final float dy;
        final boolean hasMovement;

        RelativeMouseDelta(float dx, float dy, boolean hasMovement) {
            this.dx = dx;
            this.dy = dy;
            this.hasMovement = hasMovement;
        }
    }

    private boolean isLikelyHotbarTap(float x, float y) {
        return hotbarSlotForTouch(x, y) >= 0;
    }

    private int hotbarSlotForTouch(float x, float y) {
        return TouchHotbarHitbox.slotForTouch(
                getContext(),
                getWidth(),
                getHeight(),
                CallbackBridge.physicalWidth,
                CallbackBridge.physicalHeight,
                x,
                y
        );
    }


    private void scheduleTouchLongPressAttack() {
        cancelTouchLongPressAttack(false);
        touchLongPressRunnable = () -> {
            if (!trackingTouch || touchMovedPastSlop || touchUiTapCandidate || touchLongPressAttackActive) {
                return;
            }
            touchLongPressAttackActive = true;
            sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, true);
        };
        touchHandler.postDelayed(touchLongPressRunnable, ViewConfiguration.getLongPressTimeout());
    }

    private void cancelTouchLongPressAttack(boolean releaseActivePress) {
        if (touchLongPressRunnable != null) {
            touchHandler.removeCallbacks(touchLongPressRunnable);
            touchLongPressRunnable = null;
        }
        if (releaseActivePress && touchLongPressAttackActive) {
            sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false);
            touchLongPressAttackActive = false;
        }
    }

    private void sendUseTap() {
        sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, true);
        sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, false);
    }

    private boolean sendHotbarSlotIfNeeded(float x, float y) {
        int slot = hotbarSlotForTouch(x, y);
        if (slot < 0) return false;
        sendKeyTap(49 + slot); // GLFW_KEY_1 through GLFW_KEY_9
        return true;
    }

    private void sendKeyTap(int keyCode) {
        CallbackBridge.setInputReady(true);
        CallbackBridge.setModifiers(keyCode, true);
        CallbackBridge.sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), true);
        CallbackBridge.sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), false);
        CallbackBridge.setModifiers(keyCode, false);
    }

    private void sendTapClickAt(float x, float y) {
        CallbackBridge.setInputReady(true);
        float clampedX = mapViewXToCursorX(x);
        float clampedY = mapViewYToCursorY(y);
        // Keep the grabbed-mode invariant: do not call sendCursorPos() for a tap.
        // putMouseEventWithCoords() carries the click coordinates without first warping
        // the grabbed cursor, which prevents touch taps from snapping the camera.
        CallbackBridge.putMouseEventWithCoords(
                LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT,
                clampedX,
                clampedY
        );
    }

    private void resetTouchTracking() {
        trackingTouch = false;
        touchMovedPastSlop = false;
        touchUiTapCandidate = false;
        touchStartedWhileGrabbed = false;
        touchMenuMouseButtonDown = false;
        consumingMenuRegrabTouchStream = false;
        touchLongPressAttackActive = false;
        touchDownX = 0f;
        touchDownY = 0f;
        lastTouchX = 0f;
        lastTouchY = 0f;
        resetTouchControllerLookFallback();
    }

    private void sendAbsoluteCursor(float x, float y) {
        CallbackBridge.setInputReady(true);
        CallbackBridge.mouseX = mapViewXToCursorX(x);
        CallbackBridge.mouseY = mapViewYToCursorY(y);
        CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
    }

    /**
     * BTA can consume the first absolute mouse move only as an input-mode switch
     * (CONTROLLER -> keyboard/mouse), leaving its cursor at the previous virtual
     * controller position until another move arrives. Virtual Mouse OFF means direct
     * touch, so replay the same absolute coordinate on the next UI frames. No mouse
     * button is generated here; ACTION_UP keeps the normal click semantics.
     */
    private void replayBtaDirectTouchCursor(float x, float y) {
        if (!GamepadInputController.btaNativeControllerOwnsLauncherInput
                || ControlsPreferences.isVirtualMouseEnabled(getContext())) {
            return;
        }

        final float replayX = x;
        final float replayY = y;
        post(() -> {
            if (!isMinecraftGrabbingNow()) {
                sendAbsoluteCursor(replayX, replayY);
            }
        });
        postDelayed(() -> {
            if (!isMinecraftGrabbingNow()) {
                sendAbsoluteCursor(replayX, replayY);
            }
        }, 24L);
    }

    private void sendHardwareAbsoluteCursor(@NonNull MotionEvent event, float x, float y) {
        if (!isUsableHardwareAbsoluteCoordinate(event, x, y)) {
            return;
        }

        CallbackBridge.setInputReady(true);
        float mappedX = mapViewXToCursorX(x);
        float mappedY = mapViewYToCursorY(y);
        CallbackBridge.mouseX = mappedX;
        CallbackBridge.mouseY = mappedY;
        if (shouldUseAndroidVirtualPhysicalMouse()) {
            // Older Android/absolute pointer behavior: less accurate for camera look,
            // but it keeps the OS pointer available to Android-side launcher UI.
            CallbackBridge.sendCursorPos(mappedX, mappedY);
        } else {
            CallbackBridge.sendHardwareCursorPos(mappedX, mappedY);
        }

        long now = SystemClock.uptimeMillis();
        if (sdlWindowBackendRequested
                && InputEventDiagnosticLogger.isEnabled()
                && now - lastSdlHardwareCursorLogUptimeMs >= 750L) {
            lastSdlHardwareCursorLogUptimeMs = now;
            LauncherLogManager.append(
                    "DroidBridgeSDL3Mouse: raw=" + x + "," + y
                            + " content=" + contentLeft + "," + contentTop
                            + "+" + viewWidth + "x" + viewHeight
                            + " target=" + DroidBridgeSDL3Bootstrap.getSdlCursorCoordinateWidth() + "x"
                            + DroidBridgeSDL3Bootstrap.getSdlCursorCoordinateHeight()
                            + " mapped=" + mappedX + "," + mappedY
                            + " source=0x" + Integer.toHexString(event.getSource())
                            + " device=" + event.getDeviceId());
        }
    }

    private void updateHardwareMousePositionIfUsable(@NonNull MotionEvent event, float x, float y) {
        if (!isFiniteCoordinate(x) || !isFiniteCoordinate(y)) return;
        if (isSuspiciousHardwareTopLeftCoordinate(event, x, y)) return;
        lastHardwareMouseX = x;
        lastHardwareMouseY = y;
        hasLastHardwareMousePosition = true;
    }

    private boolean isUsableHardwareAbsoluteCoordinate(@NonNull MotionEvent event, float x, float y) {
        return isFiniteCoordinate(x)
                && isFiniteCoordinate(y)
                && !isSuspiciousHardwareTopLeftCoordinate(event, x, y);
    }

    private boolean isSuspiciousHardwareTopLeftCoordinate(@NonNull MotionEvent event, float x, float y) {
        if (x > HARDWARE_TOP_LEFT_EPSILON || y > HARDWARE_TOP_LEFT_EPSILON) return false;
        if ((event.getButtonState() & (MotionEvent.BUTTON_PRIMARY
                | MotionEvent.BUTTON_SECONDARY
                | MotionEvent.BUTTON_TERTIARY
                | MotionEvent.BUTTON_BACK
                | MotionEvent.BUTTON_FORWARD)) != 0) {
            return false;
        }
        long until = suppressSuspiciousHardwareAbsoluteUntilNanos;
        return until > 0L && System.nanoTime() < until;
    }

    private static boolean isFiniteCoordinate(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private void resetHardwarePointerTracking(boolean suppressSuspiciousAbsolute) {
        hasLastHardwareMousePosition = false;
        lastHardwareMouseX = 0f;
        lastHardwareMouseY = 0f;
        lastHardwarePointerEventNanos = 0L;
        lastHardwareSecondaryMotionEventNanos = 0L;
        lastHardwareBackButtonMotionEventNanos = 0L;
        lastHardwareForwardButtonMotionEventNanos = 0L;
        lastHardwarePointerDeviceId = -1;
        if (suppressSuspiciousAbsolute) {
            suppressSuspiciousHardwareAbsoluteUntilNanos = System.nanoTime() + TOUCH_POINTER_MODE_RESET_NANOS;
        }
    }

    private float mapViewXToCursorX(float x) {
        float cursorWidth = sdlWindowBackendRequested
                ? DroidBridgeSDL3Bootstrap.getSdlCursorCoordinateWidth()
                : CallbackBridge.windowWidth;
        return mapViewCoordinateToCursorCoordinate(
                x - contentLeft,
                Math.max(1f, viewWidth),
                Math.max(1f, cursorWidth)
        );
    }

    private float mapViewYToCursorY(float y) {
        // While Android's IME is open we visually translate the rendered Minecraft
        // surface upward instead of shrinking the GLFW window. Android MotionEvent
        // coordinates still arrive in the original view space, so compensate here
        // for absolute GUI/menu clicks. This keeps taps on chat suggestions such as
        // /config hitting the row the player actually touched.
        float adjustedY = y - contentTop;
        if (imeViewportBottomInset > 0) {
            adjustedY += imeViewportBottomInset;
        }
        float cursorHeight = sdlWindowBackendRequested
                ? DroidBridgeSDL3Bootstrap.getSdlCursorCoordinateHeight()
                : CallbackBridge.windowHeight;
        return mapViewCoordinateToCursorCoordinate(
                adjustedY,
                Math.max(1f, viewHeight),
                Math.max(1f, cursorHeight)
        );
    }

    private static float mapViewCoordinateToCursorCoordinate(float value, float viewSize, float bridgeSize) {
        // Android MotionEvent coordinates are effectively 0..viewSize-1 and
        // GLFW menu coordinates are 0..bridgeSize-1. The old scaleInputX/Y math
        // used size/size, which leaves the last GUI pixel difficult or impossible
        // to hit on the right/bottom edge, especially with SurfaceView and
        // resolution scaling. Map edge-to-edge instead.
        float maxView = Math.max(0f, viewSize - 1f);
        float maxBridge = Math.max(0f, bridgeSize - 1f);
        if (maxView <= 0f || maxBridge <= 0f) return 0f;
        return clamp(clamp(value, 0f, maxView) * maxBridge / maxView, 0f, maxBridge);
    }

    private float mapCursorXToViewX(float cursorX) {
        float cursorWidth = Math.max(1f, sdlWindowBackendRequested
                ? DroidBridgeSDL3Bootstrap.getSdlCursorCoordinateWidth()
                : CallbackBridge.windowWidth);
        float maxCursor = Math.max(0f, cursorWidth - 1f);
        float maxView = Math.max(0f, Math.max(1f, viewWidth) - 1f);
        float local = maxCursor <= 0f ? 0f
                : clamp(cursorX, 0f, maxCursor) * maxView / maxCursor;
        return contentLeft + local;
    }

    private float mapCursorYToViewY(float cursorY) {
        float cursorHeight = Math.max(1f, sdlWindowBackendRequested
                ? DroidBridgeSDL3Bootstrap.getSdlCursorCoordinateHeight()
                : CallbackBridge.windowHeight);
        float maxCursor = Math.max(0f, cursorHeight - 1f);
        float maxView = Math.max(0f, Math.max(1f, viewHeight) - 1f);
        float local = maxCursor <= 0f ? 0f
                : clamp(cursorY, 0f, maxCursor) * maxView / maxCursor;
        float y = contentTop + local;
        if (imeViewportBottomInset > 0) y -= imeViewportBottomInset;
        return y;
    }

    private void sendRelativeCursor(float dx, float dy) {
        float cameraSensitivity = 1f;
        try {
            cameraSensitivity = GamepadMappingStore.get(getContext())
                    .getGameCameraSensitivityMultiplier();
        } catch (Throwable ignored) {
        }
        if (Float.isNaN(cameraSensitivity)
                || Float.isInfinite(cameraSensitivity)
                || cameraSensitivity <= 0f) {
            cameraSensitivity = 1f;
        }

        // This method is used only by direct touchscreen look and the
        // TouchController-mod grabbed-look fallback. Controller right-stick input
        // has its own path, hardware mice use sendHardwareRelativeCursor(), and
        // virtual menu cursor speed remains controlled separately.
        sendScaledRelativeCursor(dx * cameraSensitivity, dy * cameraSensitivity);
    }

    private void sendScaledRelativeCursor(float dx, float dy) {
        sendRelativeCursorInternal(scaleDeltaX(dx), scaleDeltaY(dy));
    }

    private void sendUnscaledRelativeCursor(float dx, float dy) {
        sendRelativeCursorInternal(dx, dy);
    }

    private void sendRelativeCursorInternal(float dx, float dy) {
        if (shouldSuppressRelativeCursor()) {
            return;
        }

        CallbackBridge.setInputReady(true);
        CallbackBridge.mouseX += dx;
        CallbackBridge.mouseY += dy;
        CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
    }

    private boolean shouldSuppressRelativeCursor() {
        long until = suppressRelativeCursorUntilNanos;
        return grabbed && until > 0L && System.nanoTime() < until;
    }

    private void sendHardwareRelativeCursor(float dx, float dy) {
        float mouseDpiScale = GamepadMappingStore.get(getContext()).getHardwareMouseDpiScaleMultiplier();

        // Physical mouse relative deltas are already camera movement. Do not
        // multiply them by the render-resolution scale, otherwise reducing the
        // game resolution makes hardware mouse look slower than touch/gamepad.
        if (shouldSuppressRelativeCursor()) {
            return;
        }
        CallbackBridge.setInputReady(true);
        CallbackBridge.mouseX += dx * mouseDpiScale;
        CallbackBridge.mouseY += dy * mouseDpiScale;
        if (shouldUseAndroidVirtualPhysicalMouse()) {
            CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
        } else {
            CallbackBridge.sendHardwareCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
        }
    }

    /**
     * Moves the captured physical mouse while a Minecraft GUI is open. Android
     * cannot warp its system cursor to Minecraft's requested center, so menus use
     * DroidBridge's software cursor and keep the real mouse captured. Relative
     * Android pixels are converted into the current Minecraft cursor coordinate
     * space so 50/75% render resolutions preserve desktop-like pointer speed.
     */
    private void sendHardwareMenuRelativeCursor(float dx, float dy) {
        float mouseDpiScale = GamepadMappingStore.get(getContext())
                .getHardwareMouseDpiScaleMultiplier();
        float cursorWidth = Math.max(1f, sdlWindowBackendRequested
                ? DroidBridgeSDL3Bootstrap.getSdlCursorCoordinateWidth()
                : CallbackBridge.windowWidth);
        float cursorHeight = Math.max(1f, sdlWindowBackendRequested
                ? DroidBridgeSDL3Bootstrap.getSdlCursorCoordinateHeight()
                : CallbackBridge.windowHeight);
        float localViewWidth = Math.max(1f, viewWidth);
        float localViewHeight = Math.max(1f, viewHeight);

        float scaleX = Math.max(0f, cursorWidth - 1f)
                / Math.max(1f, localViewWidth - 1f);
        float scaleY = Math.max(0f, cursorHeight - 1f)
                / Math.max(1f, localViewHeight - 1f);

        float nextX = clamp(CallbackBridge.mouseX + dx * mouseDpiScale * scaleX,
                0f, Math.max(0f, cursorWidth - 1f));
        float nextY = clamp(CallbackBridge.mouseY + dy * mouseDpiScale * scaleY,
                0f, Math.max(0f, cursorHeight - 1f));
        CallbackBridge.setInputReady(true);
        if (shouldUseAndroidVirtualPhysicalMouse()) {
            CallbackBridge.sendCursorPos(nextX, nextY);
        } else {
            CallbackBridge.sendHardwareCursorPos(nextX, nextY);
        }
    }

    public static boolean sendMouseButtonUnconverted(int button, boolean status) {
        int glfwButton = androidMouseButtonToGlfw(button);
        if (glfwButton < 0) return false;
        sendMouseButton(glfwButton, status);
        return true;
    }

    private boolean sendMouseButtonUnconvertedTracked(@NonNull MotionEvent event, boolean status, int pointerIndex) {
        int glfwButton = androidMouseButtonToGlfw(event.getActionButton());
        if (glfwButton >= 0) {
            return sendHardwareMouseButtonTracked(glfwButton, status);
        }

        int buttonState = event.getButtonState();
        if (status) {
            // Some OEM stacks omit actionButton. Reconcile every supported button
            // present in buttonState; the tracked set removes duplicates when the
            // same physical click was already delivered through ACTION_DOWN.
            boolean handled = false;
            if ((buttonState & MotionEvent.BUTTON_PRIMARY) != 0) {
                handled |= sendHardwareMouseButtonTracked(
                        LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, true);
            }
            if ((buttonState & MotionEvent.BUTTON_SECONDARY) != 0) {
                handled |= sendHardwareMouseButtonTracked(
                        LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, true);
            }
            if ((buttonState & MotionEvent.BUTTON_TERTIARY) != 0) {
                handled |= sendHardwareMouseButtonTracked(
                        LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE, true);
            }
            if ((buttonState & MotionEvent.BUTTON_BACK) != 0) {
                handled |= sendHardwareMouseButtonTracked(
                        LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_BACK, true);
            }
            if ((buttonState & MotionEvent.BUTTON_FORWARD) != 0) {
                handled |= sendHardwareMouseButtonTracked(
                        LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_FORWARD, true);
            }
            return handled;
        }

        // Some devices report ACTION_BUTTON_RELEASE with actionButton == 0.
        // Prefer the tracked button(s) no longer present in buttonState. This is
        // important for right-click and for legitimate left+right button chords.
        boolean handled = false;
        for (Integer heldButton : new HashSet<>(hardwareMouseButtonsDown)) {
            if (heldButton == null) continue;
            if (!isGlfwMouseButtonPresentInAndroidState(heldButton, buttonState)) {
                handled |= sendHardwareMouseButtonTracked(heldButton, false);
            }
        }
        if (handled) return true;

        // A few drivers expose the pre-release state instead of the post-release
        // state. When exactly one button is tracked, releasing it is unambiguous.
        if (hardwareMouseButtonsDown.size() == 1) {
            for (Integer heldButton : new HashSet<>(hardwareMouseButtonsDown)) {
                if (heldButton != null) {
                    return sendHardwareMouseButtonTracked(heldButton, false);
                }
            }
        }

        return false;
    }

    private static int androidMouseButtonToGlfw(int button) {
        switch (button) {
            case MotionEvent.BUTTON_PRIMARY:
                return LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT;
            case MotionEvent.BUTTON_TERTIARY:
                return LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE;
            case MotionEvent.BUTTON_SECONDARY:
                return LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT;
            case MotionEvent.BUTTON_BACK:
                return LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_BACK;
            case MotionEvent.BUTTON_FORWARD:
                return LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_FORWARD;
            default:
                return -1;
        }
    }

    private void maybeOpenAndroidKeyboardFromMenuTap(float x, float y) {
        MinecraftTextInputKeyboardTrigger.onMenuPointerConfirm(this, x, y, grabbed);
    }

    private void maybeOpenAndroidKeyboardFromCurrentSoftwareCursor() {
        maybeOpenAndroidKeyboardFromMenuTap(
                mapCursorXToViewX(CallbackBridge.mouseX),
                mapCursorYToViewY(CallbackBridge.mouseY)
        );
    }

    private boolean isAndroidKeyboardShiftActive() {
        return imeViewportBottomInset > 0 && TouchKeyboardHelper.isKeyboardShowing();
    }

    private void closeAndroidKeyboardAfterMinecraftTapIfNeeded(boolean keyboardWasShowing) {
        if (!keyboardWasShowing) return;

        // The tap was already sent to Minecraft using the IME-shifted coordinate
        // space. Now close Android's IME and reset the visual surface push so the
        // game goes back to fullscreen. Do this after the GLFW mouse release, not
        // before it, otherwise tapping a chat suggestion such as /config hits the
        // wrong Minecraft row after the surface snaps back down.
        postDelayed(() -> {
            if (TouchKeyboardHelper.isKeyboardShowing()) {
                TouchKeyboardHelper.hideKeyboard(false);
            } else {
                setImeViewportBottomInset(0);
            }
        }, 35L);
    }

    public static void sendMouseButton(int button, boolean status) {
        CallbackBridge.setInputReady(true);
        CallbackBridge.sendMouseButton(button, status);
    }

    private void recenterMouse(boolean sendToMinecraft) {
        CallbackBridge.setInputReady(true);
        boolean sdlCoordinates = DroidBridgeSDL3Bootstrap.isInputReady();
        float centerX = (sdlCoordinates
                ? DroidBridgeSDL3Bootstrap.getSdlCursorCoordinateWidth()
                : Math.max(1, CallbackBridge.windowWidth)) / 2f;
        float centerY = (sdlCoordinates
                ? DroidBridgeSDL3Bootstrap.getSdlCursorCoordinateHeight()
                : Math.max(1, CallbackBridge.windowHeight)) / 2f;
        if (sendToMinecraft) {
            CallbackBridge.sendCursorPos(centerX, centerY);
        } else {
            CallbackBridge.setCursorPosSilently(centerX, centerY);
        }
    }

    /**
     * Restores the SDL cursor baseline after Android focus/lifecycle transitions.
     * Menu mode receives a real absolute centre event; grabbed gameplay only updates
     * the silent baseline so resuming cannot turn the old GUI position into a look
     * delta.
     */
    public void recenterCursorForCurrentMode() {
        post(() -> {
            boolean currentlyGrabbed = grabbed || CallbackBridge.isGrabbing();
            recenterMouse(!currentlyGrabbed);
            if (currentlyGrabbed) {
                postDelayed(() -> recenterMouse(false),
                        POINTER_REGRAB_SILENT_CURSOR_SYNC_DELAY_MS);
            }
        });
    }

    private void syncCurrentMouseSilently() {
        CallbackBridge.setCursorPosSilently(CallbackBridge.mouseX, CallbackBridge.mouseY);
    }

    private void scheduleSilentRegrabCursorSyncs() {
        syncCurrentMouseSilently();
        postDelayed(this::syncCurrentMouseSilently, POINTER_REGRAB_SILENT_CURSOR_SYNC_DELAY_MS);
        postDelayed(this::syncCurrentMouseSilently, POINTER_REGRAB_SILENT_CURSOR_SYNC_DELAY_MS * 3L);
    }

    private void prepareTouchInputFocus(boolean allowAndroidViewFocus) {
        if (allowAndroidViewFocus) {
            requestFocusIfNeeded();
        } else {
            CallbackBridge.setInputReady(true);
            CallbackBridge.ensureInputFocus();
        }
    }

    private void requestFocusIfNeeded() {
        if (!hasFocus()) requestFocus();
    }

    @Override
    public void onGrabState(boolean isGrabbing) {
        grabbed = isGrabbing;
        post(() -> {
            if (isGrabbing) {
                sHardwareMenuSoftwareCursorActive = false;
                boolean hadMenuTouchInFlight = trackingTouch && !touchStartedWhileGrabbed;
                // Do not globally suppress camera deltas after every re-grab.
                // A timer here made the first fresh touch-look after Back to Game
                // feel dead, so users had to touch twice. Stale menu fingers are
                // already handled by touchStartedWhileGrabbed/finishMenuTouchThatRegrabbedGame().
                cancelTouchLongPressAttack(true);
                if (touchMenuMouseButtonDown) {
                    markRecentTouchMenuClick(lastTouchX != 0f ? lastTouchX : touchDownX, lastTouchY != 0f ? lastTouchY : touchDownY);
                    sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false);
                    touchMenuMouseButtonDown = false;
                }
                resetTouchTracking();
                resetHardwarePointerTracking(true);
                releaseAllHardwareMouseButtons();

                // When a finger tap on a GUI button closes the menu (Back to Game),
                // the last absolute cursor position is the button location itself. The
                // vanilla Back to Game button is near the top of the screen, so carrying
                // that absolute position into grabbed gameplay makes the next touch look
                // upward. Virtual mouse avoids this because it keeps a fake offset cursor.
                // If the closing touch stream is still in flight, consume its tail so
                // the release cannot leak into newly restored relative-mouse gameplay.
                if (hadMenuTouchInFlight) {
                    consumingMenuRegrabTouchStream = true;
                }

                // Cursor position is launcher-owned in SDL menu mode. Always return
                // its cache to the exact framebuffer centre when Minecraft closes a
                // GUI and re-enters relative mode, regardless of whether the menu was
                // closed by touch, controller, keyboard, or a hardware mouse.
                // This matches the corresponding absolute centre sent below when a
                // GUI opens and prevents stale menu coordinates becoming camera input.
                centerGrabbedCursorAfterMenuTouch();

                if (shouldUsePointerCapture()) {
                    safeRequestPointerCapture();
                } else {
                    // Touch/controller-only mode should not hold Android pointer capture.
                    safeReleasePointerCapture();
                }
            } else {
                suppressRelativeCursorUntilNanos = 0L;

                // A normal Android app cannot warp the OS hardware pointer. If the
                // player was using a real mouse, keep pointer capture across the
                // gameplay -> GUI transition and draw DroidBridge's own cursor instead.
                // That gives Minecraft, hover text, clicks and the visible pointer one
                // authoritative centered position on both GLFW (26.2) and SDL3 (26.3+).
                if (shouldUseMenuPointerCapture()) {
                    safeRequestPointerCapture();
                } else {
                    safeReleasePointerCapture();
                }

                recenterMouse(true);
                refreshHardwareMenuSoftwareCursorState();

                if (shouldUseMenuPointerCapture() && !hasActivePointerCapture()) {
                    postDelayed(() -> {
                        if (!grabbed && shouldUseMenuPointerCapture()) {
                            safeRequestPointerCapture();
                            refreshHardwareMenuSoftwareCursorState();
                        }
                    }, POINTER_REGRAB_SILENT_CURSOR_SYNC_DELAY_MS);
                }
            }
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if ((grabbed && shouldUsePointerCapture())
                || (!grabbed && shouldUseMenuPointerCapture())) {
            safeRequestPointerCapture();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus && ((grabbed && shouldUsePointerCapture())
                || (!grabbed && shouldUseMenuPointerCapture()))) {
            safeRequestPointerCapture();
        } else if (!hasWindowFocus
                || (grabbed && !shouldUsePointerCapture())
                || (!grabbed && !shouldUseMenuPointerCapture())) {
            safeReleasePointerCapture();
        }
    }

    @Override
    public void onPointerCaptureChange(boolean hasCapture) {
        super.onPointerCaptureChange(hasCapture);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        // requestPointerCapture()/releasePointerCapture() are asynchronous. On
        // several OEM Android builds the capture transition itself can restore
        // an old hardware-pointer location after onGrabState() already centered
        // Minecraft's cursor. Re-assert the correct launcher cursor only after
        // Android confirms the capture state change.
        post(() -> {
            boolean currentlyGrabbed = grabbed || CallbackBridge.isGrabbing();
            if (hasCapture) {
                if (currentlyGrabbed) {
                    sHardwareMenuSoftwareCursorActive = false;
                    recenterMouse(false);
                } else if (hardwarePointerInputMode) {
                    sHardwareMenuSoftwareCursorActive = true;
                    recenterMouse(true);
                }
            } else {
                sHardwareMenuSoftwareCursorActive = false;
                if (!currentlyGrabbed && shouldUseMenuPointerCapture()) {
                    safeRequestPointerCapture();
                }
            }
        });
    }

    private void markTouchInputMode() {
        // Switching from a physical mouse to touch can leave Android pointer capture
        // in a stale relative-input state on some devices. Reset the hardware-pointer
        // baseline and release capture so the next real mouse movement starts a fresh
        // mouse session instead of warping to 0,0/top-left.
        hardwarePointerInputMode = false;
        sHardwareMenuSoftwareCursorActive = false;
        resetHardwarePointerTracking(true);
        releaseAllHardwareMouseButtons();
        safeReleasePointerCapture();
    }

    private void markHardwarePointerInputMode(@Nullable MotionEvent event) {
        hardwarePointerInputMode = true;
        lastHardwarePointerEventNanos = System.nanoTime();
        if (event != null) {
            lastHardwarePointerDeviceId = event.getDeviceId();
        }
        if ((grabbed && shouldUsePointerCapture()) || shouldUseMenuPointerCapture()) {
            safeRequestPointerCapture();
        } else if (shouldUseAndroidVirtualPhysicalMouse()) {
            // Compatibility mode deliberately leaves Android's OS pointer ungrabbed so
            // it can click DroidBridge views/touch controls as well as the game surface.
            safeReleasePointerCapture();
        }
        refreshHardwareMenuSoftwareCursorState();
    }

    private void noteHardwareMouseButtonMotionEvent(@NonNull MotionEvent event) {
        int action = event.getActionMasked();
        int actionButton = event.getActionButton();
        int buttonState = event.getButtonState();
        boolean releaseLike = action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_BUTTON_RELEASE
                || action == MotionEvent.ACTION_CANCEL;

        boolean secondary = actionButton == MotionEvent.BUTTON_SECONDARY
                || (buttonState & MotionEvent.BUTTON_SECONDARY) != 0;
        boolean back = actionButton == MotionEvent.BUTTON_BACK
                || (buttonState & MotionEvent.BUTTON_BACK) != 0;
        boolean forward = actionButton == MotionEvent.BUTTON_FORWARD
                || (buttonState & MotionEvent.BUTTON_FORWARD) != 0;

        if (releaseLike) {
            if (!secondary) {
                secondary = hardwareMouseButtonsDown.contains(
                        LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT);
            }
            if (!back) {
                back = hardwareMouseButtonsDown.contains(
                        LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_BACK);
            }
            if (!forward) {
                forward = hardwareMouseButtonsDown.contains(
                        LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_FORWARD);
            }
        }

        long now = System.nanoTime();
        if (secondary) lastHardwareSecondaryMotionEventNanos = now;
        if (back) lastHardwareBackButtonMotionEventNanos = now;
        if (forward) lastHardwareForwardButtonMotionEventNanos = now;
    }

    private boolean hasRecentlySeenHardwarePointer() {
        long lastSeen = lastHardwarePointerEventNanos;
        return lastSeen > 0L && System.nanoTime() - lastSeen < HARDWARE_POINTER_KEEPALIVE_NANOS;
    }

    private boolean shouldUseAndroidVirtualPhysicalMouse() {
        return LauncherPreferences.isAndroidVirtualPhysicalMouse(getContext());
    }

    private boolean shouldUsePointerCapture() {
        // Both physical-mouse modes need Android relative pointer capture while
        // Minecraft is grabbed. Minecraft GUIs also keep capture so their centered
        // cursor stays authoritative. Android Virtual Mouse releases capture only for
        // DroidBridge-owned Android UI. Disabling capture during gameplay makes the
        // mouse hit the screen edge and breaks continuous camera movement/recentering.
        return hasRecentlySeenHardwarePointer() || hasRealExternalPointerDevice();
    }

    private boolean shouldUseMenuPointerCapture() {
        // Minecraft GUI/menu mode must keep the same captured/software-cursor path for
        // both physical mouse modes. Android cannot warp the real OS pointer to the
        // center requested by Minecraft, so releasing capture here creates a split:
        // Minecraft hovers the centered item while Android draws its pointer wherever
        // it last existed. Android Virtual Mouse releases the OS pointer only when a
        // DroidBridge-owned Android UI explicitly enables UI interaction mode below.
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && hardwarePointerInputMode
                && !androidVirtualMouseUiInteractionMode
                && shouldUsePointerCapture();
    }

    /**
     * Lets a DroidBridge-owned Android overlay temporarily use the real Android OS
     * pointer while Android Virtual Mouse is selected. Minecraft's own GUI is not an
     * Android overlay, so it keeps pointer capture and the centered software cursor.
     */
    public void setAndroidVirtualMouseUiInteractionMode(boolean enabled) {
        if (androidVirtualMouseUiInteractionMode == enabled) return;
        androidVirtualMouseUiInteractionMode = enabled;

        post(() -> {
            if (!shouldUseAndroidVirtualPhysicalMouse()) {
                androidVirtualMouseUiInteractionMode = false;
                refreshHardwareMenuSoftwareCursorState();
                return;
            }

            if (enabled) {
                // DroidBridge Android UI owns the pointer: expose the real OS cursor.
                sHardwareMenuSoftwareCursorActive = false;
                safeReleasePointerCapture();
                return;
            }

            // Returning from DroidBridge Android UI to Minecraft. If Minecraft is in
            // a GUI, restore the centered captured/software cursor. If gameplay is
            // grabbed, restore normal relative pointer capture with a silent baseline.
            if (grabbed || CallbackBridge.isGrabbing()) {
                recenterMouse(false);
                if (shouldUsePointerCapture()) safeRequestPointerCapture();
            } else {
                recenterMouse(true);
                if (shouldUseMenuPointerCapture()) safeRequestPointerCapture();
            }
            refreshHardwareMenuSoftwareCursorState();
        });
    }

    private boolean isHardwareMenuSoftwareCursorRouting() {
        return !grabbed && shouldUseMenuPointerCapture();
    }

    private boolean hasActivePointerCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        try {
            return hasPointerCapture();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void refreshHardwareMenuSoftwareCursorState() {
        sHardwareMenuSoftwareCursorActive = !grabbed
                && hardwarePointerInputMode
                && hasActivePointerCapture();
    }

    public static boolean isHardwareMenuSoftwareCursorActive() {
        return sHardwareMenuSoftwareCursorActive;
    }

    private void safeRequestPointerCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !hasWindowFocus() || !isShown()) return;
        if (!shouldUsePointerCapture()) return;
        try {
            requestPointerCapture();
        } catch (Throwable ignored) {
        }
    }

    private void safeReleasePointerCapture() {
        sHardwareMenuSoftwareCursorActive = false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            releasePointerCapture();
        } catch (Throwable ignored) {
        }
    }

    private void releaseActiveHardwareInput() {
        releaseAllHardwareMouseButtons();
        releaseControllerFallbackInput();

        if (!hardwareKeysDown.isEmpty()) {
            for (Integer key : new HashSet<>(hardwareKeysDown)) {
                if (key == null) continue;
                CallbackBridge.setInputReady(true);
                CallbackBridge.sendKeyPress(key, CallbackBridge.getCurrentMods(), false);
                CallbackBridge.setModifiers(key, false);
            }
            hardwareKeysDown.clear();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelTouchLongPressAttack(true);
        releaseActiveHardwareInput();
        sHardwareMenuSoftwareCursorActive = false;
        safeReleasePointerCapture();

        boolean preserveRetainedSdlTexture = sdlWindowBackendRequested
                && retainedSdlSurfaceTexture != null
                && textureSurface != null
                && textureSurface.isValid();
        if (preserveRetainedSdlTexture) {
            // Keep the grab listener registered as well as the producer. The same
            // MinecraftGLSurface instance is reattached after a transient host
            // cover, and removing it here leaves menu/game mode transitions stale.
            LauncherLogManager.append(
                    "DroidBridgeSDL3: MinecraftGLSurface detached; kept retained TextureView ANativeWindow");
        } else {
            CallbackBridge.removeGrabListener(this);
            bridgeWindowAttached = false;
            clearSdlSurface(activeSdlSurface);
            if (!sdlWindowBackendRequested) {
                JREUtils.releaseBridgeWindow();
            }
            resetSentWindowSize();
            releaseTextureSurface();
        }
        super.onDetachedFromWindow();
    }

    private void publishSdlSurface(@Nullable Surface surface, @NonNull RenderSize size) {
        if (!sdlWindowBackendRequested || surface == null) return;
        synchronized (sdlSurfaceLock) {
            activeSdlSurface = surface;
            activeSdlRenderWidth = Math.max(1, size.renderWidth);
            activeSdlRenderHeight = Math.max(1, size.renderHeight);
            activeSdlDeviceWidth = Math.max(1, size.availableWidth);
            activeSdlDeviceHeight = Math.max(1, size.availableHeight);
            DroidBridgeSDL3Bootstrap.publishSurface(surface);
            sdlSurfaceLock.notifyAll();
        }
    }

    private void clearSdlSurface(@Nullable Surface surface) {
        if (!sdlWindowBackendRequested) return;
        synchronized (sdlSurfaceLock) {
            if (surface != null && activeSdlSurface != null && surface != activeSdlSurface) return;
            DroidBridgeSDL3Bootstrap.clearSurface(activeSdlSurface);
            activeSdlSurface = null;
            sdlSurfaceLock.notifyAll();
        }
    }

    /**
     * Clears the Vulkan acquire gate only when Android currently has a connected,
     * valid producer. This is used by GameActivity.onResume/onWindowFocusChanged
     * for Minecraft 26.2, whose Vulkan backend does not request the SDL bootstrap.
     */
    public boolean resumeVulkanPresentationIfSurfaceReady(@NonNull String reason) {
        boolean ready = false;
        TextureView currentTextureView = textureView;
        Surface currentTextureSurface = textureSurface;
        if (currentTextureView != null && currentTextureSurface != null) {
            ready = currentTextureView.isAttachedToWindow()
                    && currentTextureView.isAvailable()
                    && currentTextureSurface.isValid()
                    && !retainedSdlSurfaceTextureDetached;
        }

        if (!ready && nativeSurfaceView != null && nativeSurfaceView.isAttachedToWindow()) {
            Surface holderSurface = nativeSurfaceView.getHolder().getSurface();
            ready = holderSurface != null && holderSurface.isValid();
        }

        if (ready) {
            GameActivity.setVulkanPresentationPausedFromLifecycle(false, reason);
        } else {
            GameActivity.setVulkanPresentationPausedFromLifecycle(
                    true, reason + " (surface not ready)");
        }
        return ready;
    }

    @Nullable
    public SdlSurfaceSnapshot awaitSdlSurfaceSnapshot(long timeoutMillis) {
        long deadline = android.os.SystemClock.uptimeMillis() + Math.max(0L, timeoutMillis);
        synchronized (sdlSurfaceLock) {
            while (sdlWindowBackendRequested) {
                Surface surface = activeSdlSurface;
                if (surface != null && surface.isValid()) {
                    return new SdlSurfaceSnapshot(
                            surface,
                            activeSdlRenderWidth,
                            activeSdlRenderHeight,
                            activeSdlDeviceWidth,
                            activeSdlDeviceHeight
                    );
                }
                long remaining = deadline - android.os.SystemClock.uptimeMillis();
                if (remaining <= 0L) break;
                try {
                    sdlSurfaceLock.wait(Math.min(remaining, 250L));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return null;
        }
    }

    /**
     * Final owner release used only when GameActivity is actually being destroyed.
     * Transient pause/stop/detach paths deliberately never call this method.
     */
    public void releaseRetainedSdlSurfaceForShutdown() {
        CallbackBridge.removeGrabListener(this);
        SurfaceTexture retained = retainedSdlSurfaceTexture;
        retainedSdlSurfaceTexture = null;
        retainedSdlSurfaceTextureDetached = false;

        bridgeWindowAttached = false;
        clearSdlSurface(activeSdlSurface);
        resetSentWindowSize();
        releaseTextureSurface();

        if (retained != null) {
            try {
                retained.release();
            } catch (Throwable ignored) {
            }
        }
        LauncherLogManager.append(
                "DroidBridgeSDL3: released retained TextureView producer for final shutdown");
    }

    /**
     * Returns the exact Android view rectangle that displays the SDL surface.
     * GameCursorOverlay uses this instead of the activity root so its hotspot
     * follows Minecraft when the render view is inset, scaled, or temporarily
     * laid out smaller than the full decor view.
     */
    public boolean resolveSdlVisualBoundsOnScreen(@NonNull Rect outBounds) {
        View target = renderView != null ? renderView : this;
        int width = target.getWidth();
        int height = target.getHeight();
        if (!target.isAttachedToWindow() || width <= 1 || height <= 1) return false;

        int[] location = new int[2];
        try {
            target.getLocationOnScreen(location);
        } catch (Throwable ignored) {
            return false;
        }
        outBounds.set(
                location[0],
                location[1],
                location[0] + width,
                location[1] + height
        );
        return true;
    }

    public static final class SdlSurfaceSnapshot {
        @NonNull public final Surface surface;
        public final int renderWidth;
        public final int renderHeight;
        public final int deviceWidth;
        public final int deviceHeight;

        private SdlSurfaceSnapshot(
                @NonNull Surface surface,
                int renderWidth,
                int renderHeight,
                int deviceWidth,
                int deviceHeight
        ) {
            this.surface = surface;
            this.renderWidth = renderWidth;
            this.renderHeight = renderHeight;
            this.deviceWidth = deviceWidth;
            this.deviceHeight = deviceHeight;
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public interface SurfaceReadyListener {
        void isReady();
    }

    public void setSurfaceReadyListener(@Nullable SurfaceReadyListener listener) {
        this.surfaceReadyListener = listener;
    }

    public interface OnRenderingStartedListener {
        void isStarted();
    }

    public void setOnRenderingStartedListener(@Nullable OnRenderingStartedListener listener) {
        this.renderingStartedListener = listener;
    }

    /** Callback used by the recording frame bridge. */
    public interface RecordingFrameCallback {
        void onFrameReady(boolean success);
    }

    /**
     * True when the actual Minecraft render layer can be copied without including launcher
     * overlays. TextureView uses its own composited texture; SurfaceView uses PixelCopy.
     */
    public boolean isRecordingFrameCaptureReady() {
        if (!isAttachedToWindow()) return false;
        if (textureView != null) {
            return textureView.isAttachedToWindow()
                    && textureView.isAvailable()
                    && textureView.getWidth() > 1
                    && textureView.getHeight() > 1;
        }
        if (nativeSurfaceView != null) {
            SurfaceHolder holder = nativeSurfaceView.getHolder();
            Surface surface = holder == null ? null : holder.getSurface();
            return nativeSurfaceView.isAttachedToWindow()
                    && nativeSurfaceView.getWidth() > 1
                    && nativeSurfaceView.getHeight() > 1
                    && surface != null
                    && surface.isValid();
        }
        return false;
    }

    public int getRecordingFrameWidth() {
        View view = renderView;
        int width = view == null ? 0 : view.getWidth();
        return Math.max(0, width);
    }

    public int getRecordingFrameHeight() {
        View view = renderView;
        int height = view == null ? 0 : view.getHeight();
        return Math.max(0, height);
    }

    /**
     * Copies only Minecraft's render layer into {@code bitmap}. Touch controls, floating launcher
     * buttons, dialogs and other GameActivity overlays are deliberately outside this source.
     */
    public boolean requestRecordingFrame(
            @NonNull Bitmap bitmap,
            @NonNull Handler callbackHandler,
            @NonNull RecordingFrameCallback callback
    ) {
        TextureView liveTexture = textureView;
        if (liveTexture != null && liveTexture.isAvailable()) {
            if (bitmap.getWidth() != liveTexture.getWidth()
                    || bitmap.getHeight() != liveTexture.getHeight()) {
                callback.onFrameReady(false);
                return false;
            }
            try {
                Bitmap result = liveTexture.getBitmap(bitmap);
                callback.onFrameReady(result != null);
                return result != null;
            } catch (Throwable throwable) {
                Log.w("MinecraftGLSurface", "TextureView recording frame copy failed", throwable);
                callback.onFrameReady(false);
                return false;
            }
        }

        SurfaceView liveSurface = nativeSurfaceView;
        if (liveSurface != null
                && liveSurface.getHolder() != null
                && liveSurface.getHolder().getSurface() != null
                && liveSurface.getHolder().getSurface().isValid()) {
            if (bitmap.getWidth() != liveSurface.getWidth()
                    || bitmap.getHeight() != liveSurface.getHeight()) {
                callback.onFrameReady(false);
                return false;
            }
            try {
                PixelCopy.request(
                        liveSurface,
                        bitmap,
                        result -> callback.onFrameReady(result == PixelCopy.SUCCESS),
                        callbackHandler
                );
                return true;
            } catch (Throwable throwable) {
                Log.w("MinecraftGLSurface", "SurfaceView recording PixelCopy failed", throwable);
                callback.onFrameReady(false);
                return false;
            }
        }

        callback.onFrameReady(false);
        return false;
    }

    /**
     * Receives OEM/special key events before the focused Minecraft surface converts
     * them into ordinary game input. AYN Odin rear buttons also use this path so
     * their BUTTON_Z/BUTTON_C events can be converted into logical M1/M2 early.
     */
    public interface SpecialKeyEventListener {
        boolean onSpecialKeyEvent(@NonNull KeyEvent event);
    }

    /**
     * Android Virtual Mouse keeps relative capture in Minecraft menus so the visible
     * centered cursor and Minecraft hover position stay identical. This callback lets
     * that logical cursor still activate DroidBridge-owned Android controls (touch
     * buttons / floating settings cog) without sending the same click into Minecraft.
     */
    public interface AndroidVirtualMouseUiRouter {
        boolean onAndroidVirtualMouseUiEvent(
                int action,
                float screenX,
                float screenY,
                int actionButton,
                int buttonState,
                long eventTime
        );
    }

    public void setAndroidVirtualMouseUiRouter(@Nullable AndroidVirtualMouseUiRouter router) {
        this.androidVirtualMouseUiRouter = router;
    }

    public void setSpecialKeyEventListener(@Nullable SpecialKeyEventListener listener) {
        this.specialKeyEventListener = listener;
    }

    private boolean dispatchSpecialKeyEvent(@Nullable KeyEvent event) {
        SpecialKeyEventListener listener = specialKeyEventListener;
        if (listener == null || event == null) return false;
        try {
            return listener.onSpecialKeyEvent(event);
        } catch (Throwable throwable) {
            Log.w("MinecraftGLSurface", "Special key listener failed", throwable);
            return false;
        }
    }

    /**
     * Lets the Activity route physical keyboard keys into Minecraft before Android
     * handles launcher/system shortcuts. Some Android/OEM HID stacks translate the
     * keyboard Escape key into KEYCODE_BACK, so normalize only confirmed keyboard
     * events back to KEYCODE_ESCAPE. Genuine Android navigation Back stays with the
     * Activity.
     */
    public boolean handleKeyEventFromActivity(@NonNull KeyEvent event) {
        KeyEvent normalizedEvent = normalizeKeyboardEscapeEvent(event);
        if (normalizedEvent == null) {
            return false;
        }
        // Keep launcher-reserved keyboard shortcuts ahead of direct GLFW injection too.
        // TouchControlsOverlay and OEM Back/Escape normalization can enter through this
        // method without traversing the Activity's normal dispatchKeyEvent path.
        if (dispatchSpecialKeyEvent(normalizedEvent)) {
            return true;
        }
        MinecraftTextInputKeyboardTrigger.onPotentialControllerConfirm(
                this, normalizedEvent, grabbed);
        return handlePhysicalKeyboardEvent(normalizedEvent);
    }

    /**
     * Consumes a KEYCODE_BACK synthesized from a hardware mouse secondary button.
     * If Android also delivered the normal MotionEvent, that path already owns the
     * click and the key event is only suppressed. If the OEM emitted only the Back
     * key, synthesize the missing GLFW right-button transition.
     */
    public boolean handlePointerBackKeyFromActivity(@Nullable KeyEvent event) {
        if (!isPointerBackKeyEvent(event)) return false;
        if (event == null) return false;

        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return true;
        }
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0) {
            return true;
        }

        long now = System.nanoTime();
        lastHardwarePointerEventNanos = now;
        lastHardwarePointerDeviceId = event.getDeviceId();

        // A matching MotionEvent is the authoritative mouse event. Avoid producing
        // a second right click from Android's redundant KEYCODE_BACK callback.
        long lastSecondaryMotion = lastHardwareSecondaryMotionEventNanos;
        if (lastSecondaryMotion > 0L
                && now - lastSecondaryMotion < SECONDARY_MOUSE_KEY_DEDUP_NANOS) {
            return true;
        }

        requestFocusIfNeeded();
        int glfwButton = isPointerSideBackScanCode(event)
                ? LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_BACK
                : LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT;
        if (glfwButton == LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_BACK
                && lastHardwareBackButtonMotionEventNanos > 0L
                && now - lastHardwareBackButtonMotionEventNanos < SECONDARY_MOUSE_KEY_DEDUP_NANOS) {
            return true;
        }
        return sendHardwareMouseButtonTracked(
                glfwButton,
                action == KeyEvent.ACTION_DOWN
        );
    }

    /**
     * Some Android mouse stacks expose the forward thumb button as KEYCODE_FORWARD
     * instead of MotionEvent.BUTTON_FORWARD. Route it as GLFW mouse button 5.
     */
    public boolean handlePointerForwardKeyFromActivity(@Nullable KeyEvent event) {
        if (!isPointerForwardKeyEvent(event) || event == null) return false;

        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return true;
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0) return true;

        long now = System.nanoTime();
        lastHardwarePointerEventNanos = now;
        lastHardwarePointerDeviceId = event.getDeviceId();
        if (lastHardwareForwardButtonMotionEventNanos > 0L
                && now - lastHardwareForwardButtonMotionEventNanos < SECONDARY_MOUSE_KEY_DEDUP_NANOS) {
            return true;
        }

        requestFocusIfNeeded();
        return sendHardwareMouseButtonTracked(
                LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_FORWARD,
                action == KeyEvent.ACTION_DOWN
        );
    }

    public boolean isPointerForwardKeyEvent(@Nullable KeyEvent event) {
        if (event == null || event.getKeyCode() != KeyEvent.KEYCODE_FORWARD) return false;
        if ((event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) != 0) return false;
        if (isGameControllerDevice(event.getDevice())) return false;

        int source = event.getSource();
        if (hasPointerSource(source)) return true;

        InputDevice device = event.getDevice();
        return deviceSupportsPointer(device)
                || (event.getDeviceId() >= 0
                && event.getDeviceId() == lastHardwarePointerDeviceId
                && hasRecentlySeenHardwarePointer());
    }

    /** True for Linux mouse side/back scan codes, not Android navigation Back. */
    private static boolean isPointerSideBackScanCode(@NonNull KeyEvent event) {
        int scanCode = safeScanCode(event);
        // Linux input-event BTN_SIDE and BTN_BACK. KEY_BACK (158) remains on
        // DroidBridge's existing OEM right-click fallback so that fix is preserved.
        return scanCode == 275 || scanCode == 278;
    }

    public boolean isPointerBackKeyEvent(@Nullable KeyEvent event) {
        if (event == null || event.getKeyCode() != KeyEvent.KEYCODE_BACK) return false;
        if ((event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) != 0) return false;

        int scanCode = safeScanCode(event);
        if (scanCode == 1) return false; // Linux KEY_ESC, not a mouse button.

        int source = event.getSource();
        if (hasPointerSource(source)) return true;

        InputDevice device = event.getDevice();
        // Mixed-source controllers can advertise KEYBOARD/DPAD alongside GAMEPAD.
        // Never reinterpret their Back/B events as mouse secondary input.
        if (isGameControllerDevice(device)) return false;

        if (scanCode == 0
                && deviceSupportsPointer(device)
                && device != null
                && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_NONE) {
            return true;
        }

        int deviceId = event.getDeviceId();
        return deviceId >= 0
                && deviceId == lastHardwarePointerDeviceId
                && hasRecentlySeenHardwarePointer();
    }

    private static boolean hasPointerSource(int source) {
        return (source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || (source & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
                || (source & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD;
    }

    private static boolean deviceSupportsPointer(@Nullable InputDevice device) {
        if (device == null) return false;
        try {
            return device.supportsSource(InputDevice.SOURCE_MOUSE)
                    || device.supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE)
                    || device.supportsSource(InputDevice.SOURCE_TOUCHPAD);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Compatibility helper for Activity/View-side Back guards. Returns true for a
     * normal KEYCODE_ESCAPE or a confirmed external keyboard Escape that Android
     * incorrectly exposed as KEYCODE_BACK.
     */
    public static boolean shouldRouteBackKeyToMinecraft(@Nullable KeyEvent event) {
        return isKeyboardEscapeKey(event);
    }

    public static boolean isKeyboardEscapeKey(@Nullable KeyEvent event) {
        if (event == null || (event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) != 0) {
            return false;
        }
        return event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE
                || isPhysicalKeyboardBackAsEsc(event);
    }

    @Nullable
    private static KeyEvent normalizeKeyboardEscapeEvent(@NonNull KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) return event;
        if (!isPhysicalKeyboardBackAsEsc(event)) return null;

        return new KeyEvent(
                event.getDownTime(),
                event.getEventTime(),
                event.getAction(),
                KeyEvent.KEYCODE_ESCAPE,
                event.getRepeatCount(),
                event.getMetaState(),
                event.getDeviceId(),
                safeScanCode(event),
                event.getFlags(),
                event.getSource()
        );
    }

    @Override
    public boolean dispatchKeyEventPreIme(KeyEvent event) {
        InputEventDiagnosticLogger.logKeyEvent(
                "MinecraftGLSurface.dispatchKeyEventPreIme",
                event
        );
        if (dispatchSpecialKeyEvent(event)) {
            return true;
        }

        // Activity owns mouse-Back and keyboard-Escape normalization. Do not repeat
        // those translations here or a single HID event can be injected twice.
        MinecraftTextInputKeyboardTrigger.onPotentialControllerConfirm(this, event, grabbed);
        if (handleControllerButtonFallback(event)) {
            return true;
        }
        if (handlePhysicalKeyboardEvent(event)) {
            return true;
        }
        return super.dispatchKeyEventPreIme(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        InputEventDiagnosticLogger.logKeyEvent(
                "MinecraftGLSurface.dispatchKeyEvent",
                event
        );
        if (dispatchSpecialKeyEvent(event)) {
            return true;
        }

        // Activity owns mouse-Back and keyboard-Escape normalization. Do not repeat
        // those translations here or a single HID event can be injected twice.
        MinecraftTextInputKeyboardTrigger.onPotentialControllerConfirm(this, event, grabbed);
        if (handleControllerButtonFallback(event)) {
            return true;
        }
        if (handlePhysicalKeyboardEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean handlePhysicalKeyboardEvent(@NonNull KeyEvent event) {
        if (!isPhysicalKeyboardEvent(event)) return false;

        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false;

        int glfwKey = androidKeyCodeToGlfw(event.getKeyCode());
        if (glfwKey < 0) return false;

        // Movement keys only need the first down + final up. Ignoring repeat
        // events avoids flooding the bridge and prevents InputDispatcher ANRs
        // when Android generates key repeats while the JVM/render thread is busy.
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0) {
            return true;
        }

        boolean down = action == KeyEvent.ACTION_DOWN;
        try {
            CallbackBridge.setInputReady(true);

            // Keep the original, stable in-world keyboard behavior while Minecraft is
            // grabbing input. Only use the richer key callback in menu/keybind screens,
            // where Minecraft's Controls UI needs Android key/scancode/char metadata to
            // capture and display the physical key properly.
            if (down) {
                CallbackBridge.setModifiers(glfwKey, true);
            }

            int modsForEvent = CallbackBridge.getCurrentMods();
            boolean sent = false;
            if (!grabbed) {
                sent = sendPhysicalKeyByReflection(
                        event.getKeyCode(),
                        glfwKey,
                        resolveKeyChar(event),
                        safeScanCode(event),
                        modsForEvent,
                        down
                );
            }

            if (!sent) {
                CallbackBridge.sendKeyPress(glfwKey, modsForEvent, down);
            }

            if (!down) {
                CallbackBridge.setModifiers(glfwKey, false);
            }

            if (down) {
                hardwareKeysDown.add(glfwKey);
            } else {
                hardwareKeysDown.remove(glfwKey);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean sendPhysicalKeyByReflection(
            int androidKeyCode,
            int glfwKey,
            char keyChar,
            int scanCode,
            int modifiers,
            boolean down
    ) {
        Class<?> bridgeClass = CallbackBridge.class;
        Object[][] attempts = new Object[][]{
                // Modern legacy Android launcher bridge. This carries the key plus the Android
                // scancode/character data that Minecraft's keybind screen often uses
                // to name and save a newly pressed physical key.
                {"sendKeycode", new Class[]{int.class, char.class, int.class, int.class, boolean.class}, new Object[]{glfwKey, keyChar, scanCode, modifiers, down}},

                // Some bridge forks kept the same signature but expect the raw Android key.
                {"sendKeycode", new Class[]{int.class, char.class, int.class, int.class, boolean.class}, new Object[]{androidKeyCode, keyChar, scanCode, modifiers, down}},

                // Older method names/signatures seen in experimental bridge ports.
                {"sendKeyCode", new Class[]{int.class, char.class, int.class, int.class, boolean.class}, new Object[]{glfwKey, keyChar, scanCode, modifiers, down}},
                {"putKeyboardEvent", new Class[]{int.class, char.class, int.class, int.class, boolean.class}, new Object[]{glfwKey, keyChar, scanCode, modifiers, down}},
                {"sendKeycode", new Class[]{int.class, int.class, boolean.class}, new Object[]{glfwKey, modifiers, down}}
        };

        for (Object[] attempt : attempts) {
            try {
                Method method = bridgeClass.getMethod((String) attempt[0], (Class<?>[]) attempt[1]);
                method.invoke(null, (Object[]) attempt[2]);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static char resolveKeyChar(@NonNull KeyEvent event) {
        try {
            int unicode = event.getUnicodeChar(event.getMetaState());
            if (unicode != 0) return (char) unicode;
        } catch (Throwable ignored) {
        }
        try {
            int unicode = event.getUnicodeChar();
            if (unicode != 0) return (char) unicode;
        } catch (Throwable ignored) {
        }
        return (char) 0;
    }

    private static int safeScanCode(@NonNull KeyEvent event) {
        try {
            return event.getScanCode();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static boolean isSoftKeyboardGeneratedEvent(@NonNull KeyEvent event) {
        // Do not consume normal Android IME events from the on-screen keyboard.
        // Scrcpy SDK keyboard input is often reported with VIRTUAL_KEYBOARD too,
        // but it normally does not carry FLAG_SOFT_KEYBOARD, so keep that path
        // available for desktop testing.
        return (event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) != 0;
    }

    private boolean isPhysicalKeyboardEvent(@NonNull KeyEvent event) {
        // Android BACK belongs to GameActivity so it can open the launcher
        // GameMapping dialog. Do not treat it as a Minecraft Escape key here.
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            return false;
        }

        if (isSoftKeyboardGeneratedEvent(event)) return false;
        if (isControllerLikeKeyCode(event.getKeyCode())) return false;

        int source = event.getSource();
        InputDevice device = event.getDevice();

        boolean sourceKeyboard = (source & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD;
        boolean sourceDpad = (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
        boolean deviceKeyboard = device != null && device.getKeyboardType() != InputDevice.KEYBOARD_TYPE_NONE;
        boolean hasHardwareScanCode = safeScanCode(event) != 0;
        boolean hasUnicode = resolveKeyChar(event) != 0;
        boolean keyboardLikeKey = isKeyboardLikeKeyCode(event.getKeyCode());

        // Some Bluetooth keyboards/keyboard-touchpad combos report keys with a
        // DPAD/button-ish source, or expose the pointer half cleanly while the
        // keyboard half has no KEYBOARD source. Accept normal keyboard keys from
        // those devices as long as Android did not mark them as soft IME events.
        boolean keyboardCandidate = sourceKeyboard
                || deviceKeyboard
                || (keyboardLikeKey && (sourceDpad || hasHardwareScanCode || hasUnicode));
        if (!keyboardCandidate) return false;

        // Do not reject a real keyboard just because Android also reports a
        // pointer/game-ish source on the same Bluetooth HID device. Only block
        // controller-looking devices when they are not keyboard candidates.
        if (isGameControllerDevice(device) && !sourceKeyboard && !deviceKeyboard && !hasUnicode && !keyboardLikeKey) {
            return false;
        }

        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return false;
            default:
                return true;
        }
    }

    public static boolean isPhysicalKeyboardBackAsEsc(@Nullable KeyEvent event) {
        if (event == null || event.getKeyCode() != KeyEvent.KEYCODE_BACK) return false;
        if ((event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) != 0) return false;

        int source = event.getSource();
        if (hasPointerSource(source)) return false;
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            return false;
        }

        int scanCode = safeScanCode(event);
        if (scanCode == 1) return true; // Linux input-event code KEY_ESC.
        if (scanCode == 158) return false; // Linux KEY_BACK / Android navigation Back.

        InputDevice device = event.getDevice();
        // Some controller HID descriptors report an alphabetic keyboard interface.
        // Reject the whole controller device before applying the keyboard fallback.
        if (isGameControllerDevice(device)) return false;

        if (scanCode == 0
                && deviceSupportsPointer(device)
                && device != null
                && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_NONE) {
            return false;
        }

        boolean sourceKeyboard = (source & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD;
        boolean externalAlphabeticKeyboard = device != null
                && device.isExternal()
                && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;

        // Some Bluetooth keyboards hide the Linux scan code but still report the
        // Escape key as BACK from an external alphabetic keyboard. Do not apply
        // this fallback to DPAD/remotes/controllers or the built-in navigation key.
        return sourceKeyboard && externalAlphabeticKeyboard;
    }

    private static boolean isControllerLikeKeyCode(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_BUTTON_A && keyCode <= KeyEvent.KEYCODE_BUTTON_MODE) {
            return true;
        }
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER;
    }

    private static boolean isKeyboardLikeKeyCode(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) return true;
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) return true;
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) return true;
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) return true;

        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_APOSTROPHE:
            case KeyEvent.KEYCODE_COMMA:
            case KeyEvent.KEYCODE_MINUS:
            case KeyEvent.KEYCODE_PERIOD:
            case KeyEvent.KEYCODE_SLASH:
            case KeyEvent.KEYCODE_SEMICOLON:
            case KeyEvent.KEYCODE_EQUALS:
            case KeyEvent.KEYCODE_LEFT_BRACKET:
            case KeyEvent.KEYCODE_BACKSLASH:
            case KeyEvent.KEYCODE_RIGHT_BRACKET:
            case KeyEvent.KEYCODE_GRAVE:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_INSERT:
            case KeyEvent.KEYCODE_FORWARD_DEL:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MOVE_HOME:
            case KeyEvent.KEYCODE_MOVE_END:
            case KeyEvent.KEYCODE_CAPS_LOCK:
            case KeyEvent.KEYCODE_SCROLL_LOCK:
            case KeyEvent.KEYCODE_NUM_LOCK:
            case KeyEvent.KEYCODE_SYSRQ:
            case KeyEvent.KEYCODE_BREAK:
            case KeyEvent.KEYCODE_NUMPAD_DOT:
            case KeyEvent.KEYCODE_NUMPAD_DIVIDE:
            case KeyEvent.KEYCODE_NUMPAD_MULTIPLY:
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT:
            case KeyEvent.KEYCODE_NUMPAD_ADD:
            case KeyEvent.KEYCODE_NUMPAD_EQUALS:
            case KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN:
            case KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN:
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_META_RIGHT:
            case KeyEvent.KEYCODE_MENU:
                return true;
            default:
                return false;
        }
    }


    private static boolean hasRealExternalPointerDevice() {
        try {
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice device = InputDevice.getDevice(id);
                if (isRealExternalPointerDevice(device)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isRealExternalPointerDevice(@Nullable InputDevice device) {
        if (device == null) return false;
        if (isGameControllerDevice(device)) return false;

        int sources = device.getSources();
        boolean hasPointerSource = (sources & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || (sources & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
                || (sources & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD
                || device.supportsSource(InputDevice.SOURCE_MOUSE)
                || device.supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE)
                || device.supportsSource(InputDevice.SOURCE_TOUCHPAD);
        if (!hasPointerSource) return false;

        String name = safeLower(device.getName());
        if (looksLikeControllerName(name) || looksLikeVirtualTouchName(name)) return false;

        // Bluetooth mice/keyboards with touchpads sometimes report bad isExternal()
        // metadata or generic names. If Android exposes a mouse/touchpad source and
        // it is not a controller/virtual touch helper, accept it as a real pointer.
        return true;
    }

    private static boolean isGameControllerDevice(@Nullable InputDevice device) {
        if (device == null) return false;
        int sources = device.getSources();
        if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            return true;
        }
        return looksLikeControllerName(safeLower(device.getName()));
    }

    private static boolean looksLikeControllerName(@NonNull String name) {
        return name.contains("controller")
                || name.contains("gamepad")
                || name.contains("joystick")
                || name.contains("xbox")
                || name.contains("dualshock")
                || name.contains("dualsense")
                || name.contains("playstation")
                || name.contains("8bitdo")
                || name.contains("gamesir")
                || name.contains("ipega")
                || name.contains("backbone")
                || name.contains("kishi")
                || name.contains("odin")
                || name.contains("retroid")
                || name.contains("anbernic")
                || name.contains("aya")
                || name.contains("gpd")
                || name.contains("legion go")
                || name.contains("steam deck")
                || name.contains("razer raiju")
                || name.contains("moga");
    }

    private static boolean looksLikeVirtualTouchName(@NonNull String name) {
        return name.contains("virtual")
                || name.contains("touchscreen")
                || name.contains("touch mapping")
                || name.contains("touchmapping")
                || name.contains("uinput")
                || name.contains("gpio")
                || name.contains("keypad");
    }

    private static boolean looksLikeMouseName(@NonNull String name) {
        return name.contains("mouse")
                || name.contains("trackball")
                || name.contains("trackpad")
                || name.contains("receiver")
                || name.contains("logitech")
                || name.contains("razer")
                || name.contains("microsoft")
                || name.contains("hid-compliant");
    }

    @NonNull
    private static String safeLower(@Nullable String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.US);
    }

    private static int androidKeyCodeToGlfw(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return 'A' + (keyCode - KeyEvent.KEYCODE_A);
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return '0' + (keyCode - KeyEvent.KEYCODE_0);
        }
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
            return 290 + (keyCode - KeyEvent.KEYCODE_F1);
        }
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            return 320 + (keyCode - KeyEvent.KEYCODE_NUMPAD_0);
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE: return 32;
            case KeyEvent.KEYCODE_APOSTROPHE: return 39;
            case KeyEvent.KEYCODE_COMMA: return 44;
            case KeyEvent.KEYCODE_MINUS: return 45;
            case KeyEvent.KEYCODE_PERIOD: return 46;
            case KeyEvent.KEYCODE_SLASH: return 47;
            case KeyEvent.KEYCODE_SEMICOLON: return 59;
            case KeyEvent.KEYCODE_EQUALS: return 61;
            case KeyEvent.KEYCODE_LEFT_BRACKET: return 91;
            case KeyEvent.KEYCODE_BACKSLASH: return 92;
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return 93;
            case KeyEvent.KEYCODE_GRAVE: return 96;

            case KeyEvent.KEYCODE_ESCAPE: return 256;
            case KeyEvent.KEYCODE_BACK: return -1; // Android BACK is handled by GameActivity, not Minecraft.
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return 257;
            case KeyEvent.KEYCODE_TAB: return 258;
            case KeyEvent.KEYCODE_DEL: return 259;
            case KeyEvent.KEYCODE_INSERT: return 260;
            case KeyEvent.KEYCODE_FORWARD_DEL: return 261;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 262;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 263;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 264;
            case KeyEvent.KEYCODE_DPAD_UP: return 265;
            case KeyEvent.KEYCODE_PAGE_UP: return 266;
            case KeyEvent.KEYCODE_PAGE_DOWN: return 267;
            case KeyEvent.KEYCODE_MOVE_HOME: return 268;
            case KeyEvent.KEYCODE_MOVE_END: return 269;
            case KeyEvent.KEYCODE_CAPS_LOCK: return 280;
            case KeyEvent.KEYCODE_SCROLL_LOCK: return 281;
            case KeyEvent.KEYCODE_NUM_LOCK: return 282;
            case KeyEvent.KEYCODE_SYSRQ: return 283;
            case KeyEvent.KEYCODE_BREAK: return 284;

            case KeyEvent.KEYCODE_NUMPAD_DOT: return 330;
            case KeyEvent.KEYCODE_NUMPAD_DIVIDE: return 331;
            case KeyEvent.KEYCODE_NUMPAD_MULTIPLY: return 332;
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT: return 333;
            case KeyEvent.KEYCODE_NUMPAD_ADD: return 334;
            case KeyEvent.KEYCODE_NUMPAD_EQUALS: return 336;
            case KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN: return 320;
            case KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN: return 321;

            case KeyEvent.KEYCODE_SHIFT_LEFT: return 340;
            case KeyEvent.KEYCODE_CTRL_LEFT: return 341;
            case KeyEvent.KEYCODE_ALT_LEFT: return 342;
            case KeyEvent.KEYCODE_META_LEFT: return 343;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return 344;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return 345;
            case KeyEvent.KEYCODE_ALT_RIGHT: return 346;
            case KeyEvent.KEYCODE_META_RIGHT: return 347;
            case KeyEvent.KEYCODE_MENU: return 348;
            default: return -1;
        }
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        InputEventDiagnosticLogger.logKeyEvent(
                "MinecraftGLSurface.onKeyPreIme",
                event
        );
        return super.onKeyPreIme(keyCode, event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        InputEventDiagnosticLogger.logMotionButtonEvent(
                "MinecraftGLSurface.onGenericMotionEvent",
                event
        );
        if (DroidBridgeSDL3Bootstrap.routeGenericMotionEvent(event)) {
            return true;
        }
        if (isGamepadMotionEvent(event)) {
            if (sdlEnabled && (event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                if (SDLControllerManager.handleJoystickMotionEvent(event)) {
                    return true;
                }
            }

            if (controllerModOwnsGamepadInput) {
                if (ControllerModCompat.shouldMirrorAndroidGamepadToGlfw()) {
                    GamepadInputController.feedGlfwGamepadMirrorMotion(event);
                }
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        InputEventDiagnosticLogger.logKeyEvent("MinecraftGLSurface.onKeyDown", event);
        if (DroidBridgeSDL3Bootstrap.routeKeyEvent(event)) {
            return true;
        }
        MinecraftTextInputKeyboardTrigger.onPotentialControllerConfirm(this, event, grabbed);
        int deviceId = event.getDeviceId();
        if (sdlEnabled && SDLControllerManager.isDeviceSDLJoystick(deviceId)) {
            if (SDLControllerManager.onNativePadDown(deviceId, keyCode)) {
                return true;
            }
        }
        if (controllerModOwnsGamepadInput && isControllerButtonEvent(event)) {
            if (ControllerModCompat.shouldMirrorAndroidGamepadToGlfw()) {
                GamepadInputController.feedGlfwGamepadMirrorKey(event);
            }
            return true;
        }
        if (handleControllerButtonFallback(event, true)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        InputEventDiagnosticLogger.logKeyEvent("MinecraftGLSurface.onKeyUp", event);
        if (DroidBridgeSDL3Bootstrap.routeKeyEvent(event)) {
            return true;
        }
        MinecraftTextInputKeyboardTrigger.onPotentialControllerConfirm(this, event, grabbed);
        int deviceId = event.getDeviceId();
        if (sdlEnabled && SDLControllerManager.isDeviceSDLJoystick(deviceId)) {
            if (SDLControllerManager.onNativePadUp(deviceId, keyCode)) {
                return true;
            }
        }
        if (controllerModOwnsGamepadInput && isControllerButtonEvent(event)) {
            if (ControllerModCompat.shouldMirrorAndroidGamepadToGlfw()) {
                GamepadInputController.feedGlfwGamepadMirrorKey(event);
            }
            return true;
        }
        if (handleControllerButtonFallback(event, false)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private boolean handleControllerMotionFallback(@NonNull MotionEvent event) {
        if (controllerModOwnsGamepadInput || ControllerModCompat.shouldMirrorAndroidGamepadToGlfw()) return false;
        if (!legacyBtaInputFallbackEnabled || sdlEnabled) return false;
        if (!isGamepadMotionEvent(event)) return false;

        requestFocusIfNeeded();
        CallbackBridge.setInputReady(true);

        float leftX = bestAxis(event, MotionEvent.AXIS_X, MotionEvent.AXIS_HAT_X);
        float leftY = bestAxis(event, MotionEvent.AXIS_Y, MotionEvent.AXIS_HAT_Y);
        float rightX = bestAxis(event, MotionEvent.AXIS_Z, MotionEvent.AXIS_RX);
        float rightY = bestAxis(event, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY);

        float leftTrigger = Math.max(axis(event, MotionEvent.AXIS_LTRIGGER), axis(event, MotionEvent.AXIS_BRAKE));
        float rightTrigger = Math.max(axis(event, MotionEvent.AXIS_RTRIGGER), axis(event, MotionEvent.AXIS_GAS));

        if (grabbed) {
            setControllerKeyPressed(GLFW_KEY_A, leftX < -CONTROLLER_AXIS_DEADZONE);
            setControllerKeyPressed(GLFW_KEY_D, leftX > CONTROLLER_AXIS_DEADZONE);
            setControllerKeyPressed(GLFW_KEY_W, leftY < -CONTROLLER_AXIS_DEADZONE);
            setControllerKeyPressed(GLFW_KEY_S, leftY > CONTROLLER_AXIS_DEADZONE);

            if (Math.abs(rightX) > CONTROLLER_AXIS_DEADZONE || Math.abs(rightY) > CONTROLLER_AXIS_DEADZONE) {
                sendUnscaledRelativeCursor(rightX * CONTROLLER_LOOK_STEP, rightY * CONTROLLER_LOOK_STEP);
            }

            setControllerMouseButtonPressed(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, rightTrigger > 0.35f);
            setControllerMouseButtonPressed(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, leftTrigger > 0.35f);
        } else {
            // Menu mode: move the virtual mouse cursor and let A/DPAD_CENTER click it.
            if (Math.abs(leftX) > CONTROLLER_AXIS_DEADZONE || Math.abs(leftY) > CONTROLLER_AXIS_DEADZONE) {
                CallbackBridge.mouseX = clamp(
                        CallbackBridge.mouseX + (leftX * CONTROLLER_MENU_CURSOR_STEP),
                        0f,
                        Math.max(1f, CallbackBridge.windowWidth - 1f)
                );
                CallbackBridge.mouseY = clamp(
                        CallbackBridge.mouseY + (leftY * CONTROLLER_MENU_CURSOR_STEP),
                        0f,
                        Math.max(1f, CallbackBridge.windowHeight - 1f)
                );
                CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
            }

            // Make sure in-game movement keys are not left pressed while a GUI/menu is open.
            setControllerKeyPressed(GLFW_KEY_A, false);
            setControllerKeyPressed(GLFW_KEY_D, false);
            setControllerKeyPressed(GLFW_KEY_W, false);
            setControllerKeyPressed(GLFW_KEY_S, false);

            // Right trigger can still be used as a menu click.
            setControllerMouseButtonPressed(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, rightTrigger > 0.35f);
            setControllerMouseButtonPressed(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, false);
        }

        return true;
    }

    private boolean handleControllerButtonFallback(@Nullable KeyEvent event) {
        if (controllerModOwnsGamepadInput || ControllerModCompat.shouldMirrorAndroidGamepadToGlfw()) return false;
        if (!legacyBtaInputFallbackEnabled || sdlEnabled) return false;
        if (event == null) return false;
        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false;
        return handleControllerButtonFallback(event, action == KeyEvent.ACTION_DOWN);
    }

    private boolean handleControllerButtonFallback(@Nullable KeyEvent event, boolean down) {
        if (controllerModOwnsGamepadInput || ControllerModCompat.shouldMirrorAndroidGamepadToGlfw()) return false;
        if (!legacyBtaInputFallbackEnabled || sdlEnabled) return false;
        if (event == null || !isControllerButtonEvent(event)) return false;
        if (down && event.getRepeatCount() > 0) return true;

        requestFocusIfNeeded();
        CallbackBridge.setInputReady(true);

        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (grabbed) {
                    setControllerKeyPressed(GLFW_KEY_SPACE, down);
                } else {
                    setControllerMouseButtonPressed(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, down);
                }
                return true;

            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_BUTTON_START:
                setControllerKeyPressed(GLFW_KEY_ESCAPE, down);
                return true;

            case KeyEvent.KEYCODE_BUTTON_X:
                if (grabbed) {
                    setControllerMouseButtonPressed(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, down);
                } else {
                    setControllerKeyPressed(GLFW_KEY_E, down);
                }
                return true;

            case KeyEvent.KEYCODE_BUTTON_Y:
                setControllerKeyPressed(GLFW_KEY_E, down);
                return true;

            case KeyEvent.KEYCODE_BUTTON_L1:
                setControllerKeyPressed(GLFW_KEY_Q, down);
                return true;

            case KeyEvent.KEYCODE_BUTTON_R1:
                setControllerMouseButtonPressed(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, down);
                return true;

            case KeyEvent.KEYCODE_DPAD_UP:
                if (grabbed) setControllerKeyPressed(GLFW_KEY_W, down);
                else if (down) nudgeControllerMenuCursor(0f, -72f);
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (grabbed) setControllerKeyPressed(GLFW_KEY_S, down);
                else if (down) nudgeControllerMenuCursor(0f, 72f);
                return true;

            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (grabbed) setControllerKeyPressed(GLFW_KEY_A, down);
                else if (down) nudgeControllerMenuCursor(-72f, 0f);
                return true;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (grabbed) setControllerKeyPressed(GLFW_KEY_D, down);
                else if (down) nudgeControllerMenuCursor(72f, 0f);
                return true;

            default:
                return false;
        }
    }

    private static boolean isControllerButtonEvent(@NonNull KeyEvent event) {
        int source = event.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD) {
            return true;
        }
        return isControllerLikeKeyCode(event.getKeyCode());
    }

    private void nudgeControllerMenuCursor(float dx, float dy) {
        if (dx == 0f && dy == 0f) return;
        CallbackBridge.setInputReady(true);
        CallbackBridge.mouseX = clamp(CallbackBridge.mouseX + dx, 0f, Math.max(1f, CallbackBridge.windowWidth - 1f));
        CallbackBridge.mouseY = clamp(CallbackBridge.mouseY + dy, 0f, Math.max(1f, CallbackBridge.windowHeight - 1f));
        CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
    }

    private void setControllerKeyPressed(int glfwKey, boolean down) {
        if (glfwKey < 0) return;

        if (down) {
            if (!controllerFallbackKeysDown.add(glfwKey)) return;
            CallbackBridge.setInputReady(true);
            CallbackBridge.setModifiers(glfwKey, true);
            CallbackBridge.sendKeyPress(glfwKey, CallbackBridge.getCurrentMods(), true);
        } else {
            if (!controllerFallbackKeysDown.remove(glfwKey)) return;
            CallbackBridge.setInputReady(true);
            CallbackBridge.sendKeyPress(glfwKey, CallbackBridge.getCurrentMods(), false);
            CallbackBridge.setModifiers(glfwKey, false);
        }
    }

    private void setControllerMouseButtonPressed(int glfwButton, boolean down) {
        if (glfwButton < 0) return;

        if (down) {
            if (!controllerFallbackMouseButtonsDown.add(glfwButton)) return;
            sendMouseButton(glfwButton, true);
        } else {
            if (!controllerFallbackMouseButtonsDown.remove(glfwButton)) return;
            sendMouseButton(glfwButton, false);
        }
    }

    private void releaseControllerFallbackInput() {
        for (Integer button : new HashSet<>(controllerFallbackMouseButtonsDown)) {
            if (button != null) sendMouseButton(button, false);
        }
        controllerFallbackMouseButtonsDown.clear();

        for (Integer key : new HashSet<>(controllerFallbackKeysDown)) {
            if (key == null) continue;
            CallbackBridge.setInputReady(true);
            CallbackBridge.sendKeyPress(key, CallbackBridge.getCurrentMods(), false);
            CallbackBridge.setModifiers(key, false);
        }
        controllerFallbackKeysDown.clear();
    }

    private static float axis(@NonNull MotionEvent event, int axis) {
        try {
            return event.getAxisValue(axis);
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    private static float bestAxis(@NonNull MotionEvent event, int primaryAxis, int fallbackAxis) {
        float primary = axis(event, primaryAxis);
        if (Math.abs(primary) > CONTROLLER_AXIS_DEADZONE) return primary;
        return axis(event, fallbackAxis);
    }

}

/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.dualscreen;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import ca.dnamobile.droidbridgelauncher.controls.ControlsPreferences;
import ca.dnamobile.droidbridgelauncher.controls.TouchControlsOverlay;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.runtime.MinecraftGLSurface;
import org.lwjgl.glfw.CallbackBridge;

/**
 * Owns DroidBridge's real dual-screen mode.
 *
 * Direction matters:
 * - external/secondary display: the real MinecraftGLSurface
 * - default phone/tablet display: the DroidBridge hotbar/HUD/action deck
 *
 * MediaProjection is intentionally not used here. Presentation gives us a real
 * Android window on the chosen display, while MediaProjection is a capture path
 * and cannot give this launcher a clean bottom-screen touch controller.
 */
public final class DualScreenController implements DisplayManager.DisplayListener {
    private static final String TAG = "DualScreenController";

    public interface ActiveSurfaceListener {
        void onActiveSurfaceChanged(@NonNull MinecraftGLSurface surface, boolean gameAlreadyRunning);
    }

    @NonNull private final Activity activity;
    @NonNull private final File hudStateFile;
    @NonNull private final File controlStateFile;
    @NonNull private final Runnable launcherMenuCallback;
    @NonNull private final MinecraftGLSurface defaultSurface;
    @NonNull private final ViewGroup defaultRoot;
    @NonNull private final ActiveSurfaceListener activeSurfaceListener;
    @NonNull private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Nullable private final DisplayManager displayManager;

    @Nullable private DualScreenGamePresentation gamePresentation;
    @Nullable private DualScreenPresentation controlsPresentation;
    @Nullable private DualScreenControlsView localControlsView;
    @Nullable private TouchControlsOverlay localTouchControlsOverlay;
    @Nullable private DualScreenBackgroundView localControlsBackgroundView;
    private boolean listenerRegistered;
    private boolean externalGameModeActive;
    private boolean controlsPresentationModeActive;
    private boolean screensSwapped;
    private boolean userRequestedExternalGameMode;
    private boolean requestedScreensSwapped;
    private boolean recoveringDisplayLoss;
    private boolean suppressPresentationDismissRecovery;

    public DualScreenController(
            @NonNull Activity activity,
            @NonNull File hudStateFile,
            @NonNull Runnable launcherMenuCallback,
            @NonNull MinecraftGLSurface defaultSurface,
            @NonNull ViewGroup defaultRoot,
            @NonNull ActiveSurfaceListener activeSurfaceListener
    ) {
        this.activity = activity;
        this.hudStateFile = hudStateFile;
        File parent = hudStateFile.getParentFile();
        this.controlStateFile = new File(parent == null ? activity.getFilesDir() : parent, "droidbridge_dual_screen_control.json");
        writeControlState(false);
        boolean dualScreenSupportEnabled = LauncherPreferences.isDualScreenSupportEnabled(activity);
        boolean savedSwapRequest = LauncherPreferences.isDualScreenLastSwapRequest(activity);
        this.userRequestedExternalGameMode = dualScreenSupportEnabled
                && (LauncherPreferences.isDualScreenLastExternalRequest(activity) || savedSwapRequest);
        this.requestedScreensSwapped = dualScreenSupportEnabled && savedSwapRequest;
        this.screensSwapped = false;
        this.launcherMenuCallback = launcherMenuCallback;
        this.defaultSurface = defaultSurface;
        this.defaultRoot = defaultRoot;
        this.activeSurfaceListener = activeSurfaceListener;
        Object service = activity.getSystemService(Context.DISPLAY_SERVICE);
        this.displayManager = service instanceof DisplayManager ? (DisplayManager) service : null;
    }

    public boolean isShowing() {
        return isExternalGameModeActive() || isControlsPresentationModeActive();
    }

    public boolean isExternalGameModeActive() {
        return externalGameModeActive && gamePresentation != null && gamePresentation.isShowing();
    }

    public boolean isScreensSwapped() {
        return screensSwapped && isShowing();
    }

    public boolean isControlsPresentationModeActive() {
        return controlsPresentationModeActive
                && controlsPresentation != null
                && controlsPresentation.isShowing();
    }

    public boolean hasSecondaryDisplay() {
        return findBestPresentationDisplay() != null;
    }

    public void toggleExternalGameMode(boolean gameAlreadyRunning) {
        if (userRequestedExternalGameMode || isShowing()) {
            disableExternalGameMode(gameAlreadyRunning);
        } else {
            enableExternalGameMode(gameAlreadyRunning, true);
        }
    }

    /**
     * Swaps the display roles while staying in dual-screen mode:
     * - normal: external display shows Minecraft, device screen shows HUD/touch deck
     * - swapped: device screen shows Minecraft, external display shows HUD/touch deck
     *
     * The requested direction is persisted even when no secondary display is currently
     * attached, so reconnecting a portable display restores the user's chosen layout.
     */
    public void toggleScreenSwap(boolean gameAlreadyRunning) {
        setScreenSwap(!isScreenSwapRequested(), gameAlreadyRunning);
    }

    /**
     * Returns the saved/requested swap direction, not merely the transient Presentation
     * state. Launcher Settings and the in-game controller dialog both use this same
     * value so one UI can never silently undo the other.
     */
    public boolean isScreenSwapRequested() {
        requestedScreensSwapped = LauncherPreferences.isDualScreenLastSwapRequest(activity);
        return requestedScreensSwapped;
    }

    /**
     * Sets an exact screen direction instead of blindly toggling the live controller.
     * This is the single runtime write path used by every swap control.
     */
    public void setScreenSwap(boolean swapped, boolean gameAlreadyRunning) {
        userRequestedExternalGameMode = true;
        requestedScreensSwapped = swapped;
        LauncherPreferences.setDualScreenSwapState(activity, swapped);
        registerDisplayListener();
        applyRequestedDisplayDirection(gameAlreadyRunning, true);
    }

    /**
     * Applies the saved dual-screen direction when a game Activity is created.
     * A first-time dual-screen launch still defaults to Minecraft on the external
     * display, but an existing swapped choice is never overwritten.
     */
    public void restoreOrEnableInitialMode(boolean gameAlreadyRunning) {
        // Settings may have changed since this controller object was constructed.
        // Always refresh the requested direction from the one persisted source of truth.
        requestedScreensSwapped = LauncherPreferences.isDualScreenLastSwapRequest(activity);
        if (userRequestedExternalGameMode) {
            Logging.i(TAG, "Restoring saved dual-screen layout="
                    + (requestedScreensSwapped ? "swapped" : "normal"));
            applyRequestedDisplayDirection(gameAlreadyRunning, false);
        } else {
            enableExternalGameMode(gameAlreadyRunning, false);
        }
    }

    public void enableExternalGameMode(boolean gameAlreadyRunning, boolean showToastWhenMissing) {
        userRequestedExternalGameMode = true;
        requestedScreensSwapped = LauncherPreferences.isDualScreenLastSwapRequest(activity);
        LauncherPreferences.setDualScreenLastExternalRequest(activity, true);
        registerDisplayListener();
        applyRequestedDisplayDirection(gameAlreadyRunning, showToastWhenMissing);
    }

    public void disableExternalGameMode(boolean gameAlreadyRunning) {
        boolean savedSwapRequest = LauncherPreferences.isDualScreenLastSwapRequest(activity);
        userRequestedExternalGameMode = false;
        LauncherPreferences.setDualScreenLastExternalRequest(activity, false);
        restoreDefaultSurface();
        removeLocalControlsView();
        dismissGamePresentation();
        dismissControlsPresentation();
        externalGameModeActive = false;
        controlsPresentationModeActive = false;
        screensSwapped = false;
        // Keep the user's preferred direction even while dual-screen mode is inactive.
        requestedScreensSwapped = savedSwapRequest;
        recoveringDisplayLoss = false;
        writeControlState(false);
        activeSurfaceListener.onActiveSurfaceChanged(defaultSurface, gameAlreadyRunning);
        unregisterDisplayListener();
        Logging.i(TAG, "Dual-screen mode disabled; Minecraft returned to default display");
    }

    public void onResume(boolean gameAlreadyRunning) {
        // Launcher Settings and the in-game mapping dialog share this preference.
        // Refresh it before reapplying the Presentation layout so a stale in-memory
        // toggle can never overwrite the user's most recent choice.
        requestedScreensSwapped = LauncherPreferences.isDualScreenLastSwapRequest(activity);
        if (userRequestedExternalGameMode) {
            registerDisplayListener();
            applyRequestedDisplayDirection(gameAlreadyRunning, false);
        }
    }

    public void onPause() {
        // Keep the Presentation alive while focus temporarily moves to dialogs/IME.
        // It is dismissed on explicit close, display removal, or GameActivity destroy.
    }

    public void release() {
        userRequestedExternalGameMode = false;
        removeLocalControlsView();
        restoreDefaultSurface();
        dismissGamePresentation();
        dismissControlsPresentation();
        screensSwapped = false;
        writeControlState(false);
        unregisterDisplayListener();
        mainHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onDisplayAdded(int displayId) {
        if (!userRequestedExternalGameMode) return;
        // Portable displays can report added before their Presentation window is ready.
        mainHandler.postDelayed(() -> restoreRequestedDisplayDirection(true), 350L);
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        boolean gameDisplayRemoved = gamePresentation != null
                && gamePresentation.getDisplay() != null
                && gamePresentation.getDisplay().getDisplayId() == displayId;
        boolean controlsDisplayRemoved = controlsPresentation != null
                && controlsPresentation.getDisplay() != null
                && controlsPresentation.getDisplay().getDisplayId() == displayId;
        if (gameDisplayRemoved || controlsDisplayRemoved) {
            // DisplayListener is already dispatched on mainHandler. Recover immediately
            // instead of posting another frame after the Vulkan surface has disappeared.
            recoverFromExternalDisplayLoss(true, gameDisplayRemoved);
        }
    }

    @Override
    public void onDisplayChanged(int displayId) {
        if (!userRequestedExternalGameMode || recoveringDisplayLoss) return;
        mainHandler.postDelayed(() -> restoreRequestedDisplayDirection(true), 150L);
    }

    private void restoreRequestedDisplayDirection(boolean gameAlreadyRunning) {
        applyRequestedDisplayDirection(gameAlreadyRunning, false);
    }

    /**
     * On Thor, "swapped" describes the physical panels, not Android display ids:
     * normal = 1920x1080 upper game + 1240x1080 lower controls. The Activity may
     * have been launched from either panel, so choose which side uses a Presentation
     * based on the Activity's actual current panel instead of forcing a relaunch.
     */
    private void applyRequestedDisplayDirection(
            boolean gameAlreadyRunning,
            boolean showToastWhenMissing
    ) {
        if (!userRequestedExternalGameMode || recoveringDisplayLoss
                || activity.isFinishing() || activity.isDestroyed()) return;

        if (AynThorDisplayCompat.isAynThorDevice(activity)) {
            Display top = AynThorDisplayCompat.findThorTopDisplay(activity);
            Display bottom = AynThorDisplayCompat.findThorBottomDisplay(activity);
            Display host = resolveActivityDisplay();
            if (top != null && bottom != null && host != null) {
                // Thor semantics are intentionally defined relative to the physical
                // external panel, not Android's default/secondary display ids:
                //   external = 1240x1080 bottom panel
                //   internal = 1920x1080 top panel
                // Swap OFF => Minecraft on external(bottom), controls on top.
                // Swap ON  => controls on external(bottom), Minecraft on top.
                Display externalDisplay = bottom;
                Display internalDisplay = top;
                Display gameTarget = requestedScreensSwapped ? internalDisplay : externalDisplay;
                Display controlsTarget = requestedScreensSwapped ? externalDisplay : internalDisplay;
                Logging.i(TAG, "Thor dual-screen topology: host=" + host.getDisplayId()
                        + " top=" + top.getDisplayId()
                        + " bottom=" + bottom.getDisplayId()
                        + " external=" + externalDisplay.getDisplayId()
                        + " requestedSwap=" + requestedScreensSwapped
                        + " gameTarget=" + gameTarget.getDisplayId()
                        + " controlsTarget=" + controlsTarget.getDisplayId());

                if (host.getDisplayId() == gameTarget.getDisplayId()) {
                    showControlsOnDisplay(
                            controlsTarget, gameAlreadyRunning, showToastWhenMissing,
                            requestedScreensSwapped);
                    return;
                }
                if (host.getDisplayId() == controlsTarget.getDisplayId()) {
                    showExternalGameOnDisplay(
                            gameTarget, gameAlreadyRunning, showToastWhenMissing,
                            requestedScreensSwapped);
                    return;
                }
            }
        }

        if (requestedScreensSwapped) {
            showControlsOnBestDisplay(gameAlreadyRunning, showToastWhenMissing);
        } else {
            showExternalGameOnBestDisplay(gameAlreadyRunning, showToastWhenMissing);
        }
    }

    private void showExternalGameOnBestDisplay(boolean gameAlreadyRunning, boolean showToastWhenMissing) {
        // Rendering the requested layout must never rewrite the saved preference.
        showExternalGameOnDisplay(
                findBestPresentationDisplay(), gameAlreadyRunning, showToastWhenMissing, false);
    }

    private void showExternalGameOnDisplay(
            @Nullable Display display,
            boolean gameAlreadyRunning,
            boolean showToastWhenMissing,
            boolean logicalSwap
    ) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        userRequestedExternalGameMode = true;
        registerDisplayListener();

        if (display == null) {
            writeControlState(false);
            if (showToastWhenMissing) {
                Toast.makeText(
                        activity,
                        "Connect or cast to a secondary display first.",
                        Toast.LENGTH_LONG
                ).show();
            }
            return;
        }

        dismissControlsPresentation();
        controlsPresentationModeActive = false;
        screensSwapped = logicalSwap;

        if (gamePresentation != null && gamePresentation.isShowing()
                && gamePresentation.getDisplay() != null
                && gamePresentation.getDisplay().getDisplayId() == display.getDisplayId()) {
            ensureLocalControlsView();
            hideDefaultSurface();
            externalGameModeActive = true;
            controlsPresentationModeActive = false;
            screensSwapped = logicalSwap;
            writeControlState(true);
            activeSurfaceListener.onActiveSurfaceChanged(gamePresentation.getMinecraftSurface(), gameAlreadyRunning);
            return;
        }

        dismissGamePresentation();

        try {
            DualScreenGamePresentation next = new DualScreenGamePresentation(activity, display);
            next.setDisplayLossListener(() -> recoverFromExternalDisplayLoss(true, true));
            next.setOnDismissListener(dialog -> {
                boolean wasCurrent = gamePresentation == dialog;
                if (wasCurrent) {
                    gamePresentation = null;
                    externalGameModeActive = false;
                }
                if (wasCurrent && !suppressPresentationDismissRecovery
                        && userRequestedExternalGameMode && !recoveringDisplayLoss) {
                    recoverFromExternalDisplayLoss(true, true);
                }
            });
            gamePresentation = next;
            next.show();

            // 1.0.144: switch Minecraft to the new live surface before hiding the old
            // default surface. Destroying/hiding the active surface first can make 26.x
            // Vulkan report VK_ERROR_SURFACE_LOST_KHR during the next acquire.
            activeSurfaceListener.onActiveSurfaceChanged(next.getMinecraftSurface(), gameAlreadyRunning);
            ensureLocalControlsView();
            hideDefaultSurface();
            externalGameModeActive = true;
            controlsPresentationModeActive = false;
            screensSwapped = logicalSwap;
            writeControlState(true);

            Logging.i(TAG, "Dual-screen mode enabled: Minecraft displayId=" + display.getDisplayId()
                    + " stateFile=" + hudStateFile.getAbsolutePath());
        } catch (Throwable throwable) {
            externalGameModeActive = false;
            controlsPresentationModeActive = false;
            screensSwapped = false;
            gamePresentation = null;
            restoreDefaultSurface();
            removeLocalControlsView();
            Logging.e(TAG, "Unable to move Minecraft to external display", throwable);
            Toast.makeText(activity, "Unable to move Minecraft to this display.", Toast.LENGTH_LONG).show();
        }
    }

    private void showControlsOnBestDisplay(boolean gameAlreadyRunning, boolean showToastWhenMissing) {
        // Rendering the requested layout must never rewrite the saved preference.
        showControlsOnDisplay(
                findBestPresentationDisplay(), gameAlreadyRunning, showToastWhenMissing, true);
    }

    private void showControlsOnDisplay(
            @Nullable Display display,
            boolean gameAlreadyRunning,
            boolean showToastWhenMissing,
            boolean logicalSwap
    ) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        userRequestedExternalGameMode = true;
        registerDisplayListener();

        if (display == null) {
            writeControlState(false);
            if (showToastWhenMissing) {
                Toast.makeText(
                        activity,
                        "Connect or cast to a secondary display first.",
                        Toast.LENGTH_LONG
                ).show();
            }
            return;
        }

        // 1.0.144: for live swap, move Minecraft back to the default surface before
        // dismissing the external Presentation. The old order destroyed the active
        // surface first, which could crash the 26.x Vulkan backend with surface-lost.
        boolean hadExternalGameSurface = gamePresentation != null && gamePresentation.isShowing();
        removeLocalControlsView();
        restoreDefaultSurface();
        externalGameModeActive = false;
        controlsPresentationModeActive = false;
        activeSurfaceListener.onActiveSurfaceChanged(defaultSurface, gameAlreadyRunning);
        if (hadExternalGameSurface) {
            // Do not create the controls Presentation underneath the still-visible
            // Minecraft Presentation on the same display. That race made Swap=ON
            // appear to do nothing until the next toggle. First move Minecraft to
            // the host surface, then dismiss the old game Presentation, then create
            // the controls Presentation on the following UI turn.
            final boolean expectedSwap = logicalSwap;
            mainHandler.postDelayed(() -> {
                if (!userRequestedExternalGameMode
                        || requestedScreensSwapped != expectedSwap
                        || activity.isFinishing()
                        || activity.isDestroyed()) {
                    return;
                }
                dismissGamePresentation();
                mainHandler.post(() -> {
                    if (!userRequestedExternalGameMode
                            || requestedScreensSwapped != expectedSwap
                            || activity.isFinishing()
                            || activity.isDestroyed()) {
                        return;
                    }
                    showControlsOnDisplay(
                            display, gameAlreadyRunning, showToastWhenMissing, logicalSwap);
                });
            }, 250L);
            return;
        } else {
            dismissGamePresentation();
        }

        if (controlsPresentation != null && controlsPresentation.isShowing()
                && controlsPresentation.getDisplay() != null
                && controlsPresentation.getDisplay().getDisplayId() == display.getDisplayId()) {
            controlsPresentation.reloadBackground();
            controlsPresentationModeActive = true;
            screensSwapped = logicalSwap;
            externalGameModeActive = false;
            writeControlState(true);
            activeSurfaceListener.onActiveSurfaceChanged(defaultSurface, gameAlreadyRunning);
            return;
        }

        dismissControlsPresentation();

        try {
            DualScreenPresentation next = new DualScreenPresentation(
                    activity,
                    display,
                    hudStateFile,
                    launcherMenuCallback,
                    defaultSurface
            );
            next.setDisplayLossListener(() -> recoverFromExternalDisplayLoss(true, false));
            next.setOnDismissListener(dialog -> {
                if (controlsPresentation == dialog) {
                    controlsPresentation = null;
                    controlsPresentationModeActive = false;
                    screensSwapped = false;
                }
            });
            controlsPresentation = next;
            next.show();

            controlsPresentationModeActive = true;
            screensSwapped = logicalSwap;
            externalGameModeActive = false;
            writeControlState(true);
            activeSurfaceListener.onActiveSurfaceChanged(defaultSurface, gameAlreadyRunning);

            Logging.i(TAG, "Dual-screen swap enabled: Minecraft on default display; controls displayId="
                    + display.getDisplayId() + " stateFile=" + hudStateFile.getAbsolutePath());
        } catch (Throwable throwable) {
            controlsPresentationModeActive = false;
            screensSwapped = false;
            controlsPresentation = null;
            writeControlState(false);
            Logging.e(TAG, "Unable to move DroidBridge HUD deck to external display", throwable);
            Toast.makeText(activity, "Unable to swap screens on this display.", Toast.LENGTH_LONG).show();
        }
    }

    private void ensureLocalControlsView() {
        MinecraftGLSurface externalSurface = gamePresentation == null ? null : gamePresentation.getMinecraftSurface();

        ensureLocalControlsBackground();
        if (localControlsView == null || localControlsView.getParent() != defaultRoot) {
            removeLocalControlsView();
            localControlsView = new DualScreenControlsView(activity, hudStateFile, launcherMenuCallback);
            defaultRoot.addView(localControlsView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            ));
        }

        ensureLocalTouchControlsOverlay(externalSurface);
        // Keep the HUD view above the touch overlay so hotbar slots can show touch/selection
        // feedback. DualScreenControlsView 1.0.110 draws a transparent background, so the
        // configurable TouchControlsOverlay remains visible underneath and still receives
        // normal gameplay touches outside hotbar/button regions.
        if (localControlsBackgroundView != null) localControlsBackgroundView.reload();
        if (localTouchControlsOverlay != null) localTouchControlsOverlay.bringToFront();
        localControlsView.bringToFront();
        // 1.0.110: do not install root/decor gamepad forwarders here.
        // L1/R1 and Scroll U/D are already handled by the normal touch/controller mapping
        // as mouse-wheel hotbar changes. The HUD selector now follows hudState.selectedSlot
        // instead of stealing/consuming those inputs.
        CallbackBridge.setInputReady(true);
        CallbackBridge.ensureInputFocus();
    }

    private void ensureLocalControlsBackground() {
        if (localControlsBackgroundView == null || localControlsBackgroundView.getParent() != defaultRoot) {
            removeLocalControlsBackground();
            localControlsBackgroundView = new DualScreenBackgroundView(activity);
            defaultRoot.addView(localControlsBackgroundView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            ));
        }
        localControlsBackgroundView.reload();
    }

    private void ensureLocalTouchControlsOverlay(@Nullable MinecraftGLSurface externalSurface) {
        if (localTouchControlsOverlay == null || localTouchControlsOverlay.getParent() != defaultRoot) {
            removeLocalTouchControlsOverlay();
            localTouchControlsOverlay = new TouchControlsOverlay(activity);
            localTouchControlsOverlay.setAppMenuListener(() -> launcherMenuCallback.run());
            localTouchControlsOverlay.loadSelectedLayout();
            localTouchControlsOverlay.applyVirtualMouseLaunchSessionState();
            defaultRoot.addView(localTouchControlsOverlay, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            ));
        }
        localTouchControlsOverlay.setPassthroughTarget(externalSurface);
        File gameDir = hudStateFile.getParentFile();
        localTouchControlsOverlay.setMinecraftOptionsFile(gameDir == null ? null : new File(gameDir, "options.txt"));
        localTouchControlsOverlay.setDualScreenBottomHudHotbarMode(true);
        localTouchControlsOverlay.setControlsVisible(ControlsPreferences.isTouchControlsEnabled(activity));
    }

    private void removeLocalControlsView() {
        removeLocalTouchControlsOverlay();
        if (localControlsView != null) {
            ViewGroup parent = (ViewGroup) localControlsView.getParent();
            if (parent != null) {
                parent.removeView(localControlsView);
            }
            localControlsView = null;
        }
        removeLocalControlsBackground();
    }

    private void removeLocalControlsBackground() {
        if (localControlsBackgroundView == null) return;
        ViewGroup parent = (ViewGroup) localControlsBackgroundView.getParent();
        if (parent != null) {
            parent.removeView(localControlsBackgroundView);
        }
        localControlsBackgroundView = null;
    }

    private void removeLocalTouchControlsOverlay() {
        if (localTouchControlsOverlay == null) return;
        ViewGroup parent = (ViewGroup) localTouchControlsOverlay.getParent();
        if (parent != null) {
            parent.removeView(localTouchControlsOverlay);
        }
        localTouchControlsOverlay = null;
    }

    private void hideDefaultSurface() {
        defaultSurface.setAlpha(0f);
        defaultSurface.setVisibility(View.GONE);
    }

    private void restoreDefaultSurface() {
        defaultSurface.setVisibility(View.VISIBLE);
        defaultSurface.setAlpha(1f);
    }

    private void dismissGamePresentation() {
        DualScreenGamePresentation old = gamePresentation;
        gamePresentation = null;
        if (old == null) return;
        boolean previousSuppression = suppressPresentationDismissRecovery;
        suppressPresentationDismissRecovery = true;
        try {
            old.setDisplayLossListener(null);
            old.dismiss();
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to dismiss external Minecraft presentation", throwable);
        } finally {
            suppressPresentationDismissRecovery = previousSuppression;
        }
    }

    private void dismissControlsPresentation() {
        DualScreenPresentation old = controlsPresentation;
        controlsPresentation = null;
        controlsPresentationModeActive = false;
        if (old == null) return;
        try {
            old.setDisplayLossListener(null);
            old.dismiss();
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to dismiss external controls presentation", throwable);
        }
    }

    /**
     * Returns the live game to the device display after a portable/secondary display
     * disappears. This is an automatic fallback, not an explicit user disable, so the
     * chosen screen direction remains saved and is restored when a display reconnects.
     */
    private void recoverFromExternalDisplayLoss(boolean gameAlreadyRunning, boolean gameSurfaceWasExternal) {
        if (recoveringDisplayLoss || activity.isFinishing() || activity.isDestroyed()) return;
        recoveringDisplayLoss = true;

        // Keep the preference stable across a cable unplug. The runtime state becomes
        // single-screen temporarily, while requestedScreensSwapped records what to
        // restore on the next display-added callback or game launch.
        userRequestedExternalGameMode = true;
        LauncherPreferences.setDualScreenLastExternalRequest(activity, true);
        LauncherPreferences.setDualScreenLastSwapRequest(activity, requestedScreensSwapped);

        if (gameSurfaceWasExternal) {
            CallbackBridge.beginWindowSurfaceTransfer();
        }

        restoreDefaultSurface();
        removeLocalControlsView();
        externalGameModeActive = false;
        controlsPresentationModeActive = false;
        screensSwapped = false;
        writeControlState(false);

        // Rebind the GLFW/Android window before dismissing the dead Presentation. For
        // Mojang's 26.x Vulkan backend this minimizes the window during the handoff so
        // acquireNextTexture cannot keep rendering against the removed ANativeWindow.
        activeSurfaceListener.onActiveSurfaceChanged(defaultSurface, gameAlreadyRunning);
        defaultSurface.post(() -> {
            defaultSurface.reattachBridgeWindow();
            defaultSurface.postDelayed(() -> {
                if (gameSurfaceWasExternal) {
                    CallbackBridge.finishWindowSurfaceTransfer();
                }
                recoveringDisplayLoss = false;
            }, 180L);
        });

        dismissGamePresentation();
        dismissControlsPresentation();

        Toast.makeText(
                activity,
                "External display disconnected. Minecraft returned to this screen; your screen layout was saved.",
                Toast.LENGTH_LONG
        ).show();
        Logging.i(TAG, "External display lost; recovered to default surface and preserved swap="
                + requestedScreensSwapped);
    }

    /**
     * Clears the HUD-mod bridge when dual-screen support is disabled. This prevents a
     * previous crash/force-close from leaving hideExternalHud=true for the next launch.
     */
    public static void resetPersistedControlState(
            @NonNull Context context,
            @NonNull File hudStateFile
    ) {
        File parent = hudStateFile.getParentFile();
        File controlFile = new File(
                parent == null ? context.getFilesDir() : parent,
                "droidbridge_dual_screen_control.json"
        );
        try {
            File controlParent = controlFile.getParentFile();
            if (controlParent != null && !controlParent.exists()) {
                controlParent.mkdirs();
            }
            FileOutputStream out = new FileOutputStream(controlFile, false);
            try {
                out.write(("{\"dualScreenActive\":false,"
                        + "\"hideExternalHud\":false,"
                        + "\"screensSwapped\":false}").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                out.close();
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to reset dual-screen control state", throwable);
        }
    }

    private void writeControlState(boolean hideExternalHud) {
        try {
            File parent = controlStateFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            String json = "{\"dualScreenActive\":" + hideExternalHud
                    + ",\"hideExternalHud\":" + hideExternalHud
                    + ",\"screensSwapped\":" + screensSwapped + "}";
            FileOutputStream out = new FileOutputStream(controlStateFile, false);
            try {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                out.close();
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to write dual-screen control state", throwable);
        }
    }

    @Nullable
    private Display findBestPresentationDisplay() {
        if (displayManager == null) return null;

        Display[] presentationDisplays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        Display best = chooseLargestSecondaryDisplay(presentationDisplays);
        if (best != null) return best;

        return chooseLargestSecondaryDisplay(displayManager.getDisplays());
    }

    @Nullable
    private Display chooseLargestSecondaryDisplay(@Nullable Display[] displays) {
        if (displays == null || displays.length == 0) return null;

        Display host = resolveActivityDisplay();
        int hostDisplayId = host == null ? Display.DEFAULT_DISPLAY : host.getDisplayId();
        Display best = null;
        int bestPixels = -1;
        android.graphics.Point size = new android.graphics.Point();
        for (Display display : displays) {
            if (display == null || display.getDisplayId() == hostDisplayId) continue;
            try {
                display.getRealSize(size);
                int pixels = Math.max(1, size.x) * Math.max(1, size.y);
                if (best == null || pixels > bestPixels) {
                    best = display;
                    bestPixels = pixels;
                }
            } catch (Throwable ignored) {
                if (best == null) best = display;
            }
        }
        return best;
    }

    @Nullable
    private Display resolveActivityDisplay() {
        try {
            if (activity.getWindow() != null && activity.getWindow().getDecorView() != null) {
                Display display = activity.getWindow().getDecorView().getDisplay();
                if (display != null && display.isValid()) return display;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void registerDisplayListener() {
        if (displayManager == null || listenerRegistered) return;
        try {
            displayManager.registerDisplayListener(this, mainHandler);
            listenerRegistered = true;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to register display listener", throwable);
        }
    }

    private void unregisterDisplayListener() {
        if (displayManager == null || !listenerRegistered) return;
        try {
            displayManager.unregisterDisplayListener(this);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to unregister display listener", throwable);
        } finally {
            listenerRegistered = false;
        }
    }
}

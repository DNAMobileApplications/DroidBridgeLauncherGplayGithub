/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 */

package ca.dnamobile.droidbridgelauncher.dualscreen;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

import ca.dnamobile.droidbridgelauncher.controls.ControlsPreferences;
import ca.dnamobile.droidbridgelauncher.controls.TouchControlsOverlay;
import ca.dnamobile.droidbridgelauncher.controls.TouchKeyboardHelper;
import ca.dnamobile.droidbridgelauncher.runtime.MinecraftGLSurface;

import org.lwjgl.glfw.CallbackBridge;

final class DualScreenPresentation extends Presentation {
    @NonNull private final File hudStateFile;
    @NonNull private final Runnable launcherMenuCallback;
    @Nullable private final Activity hostActivity;
    @Nullable private final MinecraftGLSurface passthroughTarget;
    @Nullable private DualScreenBackgroundView backgroundView;
    @Nullable private DualScreenBottomViewportLayout bottomViewport;
    @Nullable private Runnable displayLossListener;
    @Nullable private DualScreenDeckView deckView;
    @Nullable private TouchControlsOverlay touchOverlay;
    private boolean displayLossReported;
    private boolean dualScreenImeActive;
    private int imeSessionGeneration;
    @Nullable private ImeHostPresentation imeHostPresentation;
    @NonNull private final Handler imeHandler = new Handler(Looper.getMainLooper());

    DualScreenPresentation(
            @NonNull Context outerContext,
            @NonNull Display display,
            @NonNull File hudStateFile,
            @NonNull Runnable launcherMenuCallback
    ) {
        this(outerContext, display, hudStateFile, launcherMenuCallback, null);
    }

    DualScreenPresentation(
            @NonNull Context outerContext,
            @NonNull Display display,
            @NonNull File hudStateFile,
            @NonNull Runnable launcherMenuCallback,
            @Nullable MinecraftGLSurface passthroughTarget
    ) {
        super(outerContext, display);
        this.hudStateFile = hudStateFile;
        this.launcherMenuCallback = launcherMenuCallback;
        this.hostActivity = outerContext instanceof Activity ? (Activity) outerContext : null;
        this.passthroughTarget = passthroughTarget;
    }

    void setDisplayLossListener(@Nullable Runnable listener) {
        displayLossListener = listener;
    }

    void reloadBackground() {
        if (backgroundView != null) backgroundView.reload();
    }

    void previewControllerHotbarDelta(int delta) {
        DualScreenDeckView deck = deckView;
        if (deck != null) deck.previewControllerHotbarDelta(delta);
    }

    void setTouchControlsVisible(boolean visible) {
        TouchControlsOverlay overlay = touchOverlay;
        if (overlay == null) return;
        overlay.setControlsVisible(visible);
        overlay.requestLayout();
        overlay.invalidate();
    }

    /** Recording never changes the live bottom-screen artwork. */
    void setTouchControlsRecordingHidden(boolean hidden) {
        TouchControlsOverlay overlay = touchOverlay;
        if (overlay == null) return;
        overlay.setAlpha(1f);
        overlay.requestLayout();
        overlay.invalidate();
    }

    @Nullable
    View getRecordingView() {
        return bottomViewport;
    }

    void reloadTouchControlsLayout() {
        TouchControlsOverlay overlay = touchOverlay;
        if (overlay == null) return;
        overlay.loadSelectedLayout();
        overlay.applyVirtualMouseLaunchSessionState();
        overlay.setControlsVisible(ControlsPreferences.isTouchControlsEnabled(getContext()));
        overlay.requestLayout();
        overlay.invalidate();
    }

    @Override
    public void onDisplayRemoved() {
        if (!displayLossReported) {
            displayLossReported = true;
            Runnable listener = displayLossListener;
            if (listener != null) listener.run();
        }
        super.onDisplayRemoved();
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final int deckBackground = Color.rgb(7, 10, 16);

        boolean thor = AynThorDisplayCompat.isAynThorDevice(getContext());
        Window window = getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (thor) {
                // Still receives touch, but never becomes the Android key/controller focus
                // owner. This prevents a bottom-screen tap from unfocusing Minecraft.
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            }
            window.setBackgroundDrawable(new ColorDrawable(deckBackground));
            if (window.getDecorView() != null) {
                window.getDecorView().setBackgroundColor(deckBackground);
            }
        }

        FrameLayout root = new FrameLayout(getContext());
        root.setBackgroundColor(deckBackground);
        root.setFocusable(!thor);
        root.setFocusableInTouchMode(!thor);

        bottomViewport = new DualScreenBottomViewportLayout(getContext());
        FrameLayout.LayoutParams viewportParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                android.view.Gravity.CENTER
        );
        root.addView(bottomViewport, viewportParams);

        backgroundView = new DualScreenBackgroundView(getContext());
        bottomViewport.addView(backgroundView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        backgroundView.reload();

        // Host the HUD through DualScreenDeckView so the localhost live bridge is actually
        // connected. The previous Presentation instantiated DualScreenControlsView directly,
        // which meant every Minecraft-rendered ICON response was unreachable and populated
        // slots fell back to Android-side approximations or blanks.
        deckView = new DualScreenDeckView(
                getContext(),
                hudStateFile,
                launcherMenuCallback
        );
        bottomViewport.addView(deckView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // This Presentation is the controls display.  It must own the only touch overlay
        // while Minecraft remains on the activity display.  The clean project had dropped
        // this view entirely, so GameActivity recreated its normal overlay over Minecraft
        // and the bottom display could not forward touches to the game surface.
        touchOverlay = new TouchControlsOverlay(getContext());
        touchOverlay.setAppMenuListener(launcherMenuCallback::run);
        touchOverlay.setPassthroughTarget(passthroughTarget);
        File gameDir = hudStateFile.getParentFile();
        touchOverlay.setMinecraftOptionsFile(
                gameDir == null ? null : new File(gameDir, "options.txt"));
        touchOverlay.setDualScreenBottomHudHotbarMode(true);
        touchOverlay.loadSelectedLayout();
        touchOverlay.applyVirtualMouseLaunchSessionState();
        touchOverlay.setControlsVisible(ControlsPreferences.isTouchControlsEnabled(getContext()));
        bottomViewport.addView(touchOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // HUD slots need to stay tappable above the gameplay controls.  Touches outside the
        // HUD continue through the overlay's passthrough target to Minecraft.
        deckView.bringToFront();

        setContentView(root);
        bottomViewport.post(bottomViewport::refreshViewport);

        // Thor keeps this persistent controls Presentation non-focusable for its entire
        // lifetime. Keyboard entry uses a separate transient transparent Presentation, so
        // the HUD/touch deck can never become Android's stale key/gamepad focus owner.
        TouchKeyboardHelper.registerDualScreenImeHost(
                deckView,
                this::beginDualScreenImeInput,
                this::endDualScreenImeInput
        );
    }

    private void beginDualScreenImeInput() {
        dualScreenImeActive = true;
        final int generation = ++imeSessionGeneration;

        // Never change FLAG_NOT_FOCUSABLE on the persistent controls Presentation. On
        // Thor that window can remain Android's focused window after the IME closes,
        // which strands controller dispatch away from GameActivity until Recents is used.
        // A disposable transparent Presentation owns the editor/IME instead.
        dismissImeHostPresentation(false);

        Activity activity = hostActivity;
        Display display = getDisplay();
        if (activity == null || display == null || !display.isValid()) {
            TouchKeyboardHelper.setDualScreenImeSessionHost(null);
            return;
        }

        try {
            ImeHostPresentation imePresentation = new ImeHostPresentation(activity, display);
            imePresentation.setOwnerActivity(activity);
            imeHostPresentation = imePresentation;
            imePresentation.setOnDismissListener(dialog -> {
                if (imeHostPresentation == dialog) {
                    imeHostPresentation = null;
                }
                TouchKeyboardHelper.setDualScreenImeSessionHost(null);
                if (!dualScreenImeActive && generation == imeSessionGeneration) {
                    restoreGameActivityFocus(generation);
                }
            });
            imePresentation.show();
            FrameLayout imeHost = imePresentation.getImeHost();
            if (imeHost != null) {
                TouchKeyboardHelper.setDualScreenImeSessionHost(imeHost);
            }
        } catch (Throwable throwable) {
            TouchKeyboardHelper.setDualScreenImeSessionHost(null);
            imeHostPresentation = null;
        }
    }

    private void endDualScreenImeInput() {
        dualScreenImeActive = false;
        final int generation = ++imeSessionGeneration;

        // Removing the temporary focusable window lets WindowManager naturally return
        // focus to GameActivity. The permanent HUD/touch Presentation was never resized,
        // reflagged, or refocused, so its controls remain exactly where they were.
        dismissImeHostPresentation(true);
        TouchKeyboardHelper.setDualScreenImeSessionHost(null);
        restoreBottomControlsAfterIme();
        restoreGameActivityFocus(generation);
    }

    private void dismissImeHostPresentation(boolean restoreFocus) {
        ImeHostPresentation presentation = imeHostPresentation;
        imeHostPresentation = null;
        if (presentation != null) {
            try {
                presentation.setOnDismissListener(null);
                if (presentation.isShowing()) presentation.dismiss();
            } catch (Throwable ignored) {
            }
        }
        TouchKeyboardHelper.setDualScreenImeSessionHost(null);
        if (restoreFocus) restoreGameActivityFocus(imeSessionGeneration);
    }

    private void restoreGameActivityFocus(int generation) {
        Activity activity = hostActivity;
        if (activity == null) return;

        Runnable restore = () -> {
            if (dualScreenImeActive || generation != imeSessionGeneration) return;
            restoreBottomControlsAfterIme();
            try {
                Window activityWindow = activity.getWindow();
                View decor = activityWindow == null ? null : activityWindow.getDecorView();
                if (decor != null) {
                    decor.setFocusableInTouchMode(true);
                    decor.requestFocus();
                    decor.requestApplyInsets();
                }
                CallbackBridge.setInputReady(true);
                CallbackBridge.ensureInputFocus();
            } catch (Throwable ignored) {
            }
        };

        // One immediate restore handles normal WindowManager focus return. The short
        // follow-ups cover Thor firmware/Kid Emu transitions where the old IME window is
        // removed asynchronously. No Activity pause/resume or Recents cycle is required.
        activity.runOnUiThread(restore);
        imeHandler.postDelayed(restore, 80L);
        imeHandler.postDelayed(restore, 220L);
    }

    private void restoreBottomControlsAfterIme() {
        TouchControlsOverlay overlay = touchOverlay;
        if (overlay != null) {
            // Do not call setControlsVisible() here. That method re-runs controller auto-hide
            // detection and can make manually-enabled touch controls disappear simply because
            // Thor's built-in gamepad is present. The transient IME never changed visibility.
            overlay.requestLayout();
            overlay.invalidate();
        }
        DualScreenDeckView currentDeck = deckView;
        if (currentDeck != null) {
            // Keep the established HUD/cog layer ordering without hiding the touch canvas.
            currentDeck.bringToFront();
            currentDeck.requestLayout();
            currentDeck.invalidate();
        }
    }

    @Override
    public void dismiss() {
        imeHandler.removeCallbacksAndMessages(null);
        dualScreenImeActive = false;
        ++imeSessionGeneration;
        dismissImeHostPresentation(false);
        DualScreenDeckView currentDeck = deckView;
        if (currentDeck != null) {
            TouchKeyboardHelper.unregisterDualScreenImeHost(currentDeck);
        }
        super.dismiss();
    }

    /**
     * Focusable only for the lifetime of one Android keyboard session. Keeping this as a
     * separate transparent window is critical on Thor: the persistent controls window
     * remains NOT_FOCUSABLE, so it can never become the stale gamepad focus owner.
     */
    private static final class ImeHostPresentation extends Presentation {
        @Nullable private FrameLayout imeHost;

        ImeHostPresentation(@NonNull Context outerContext, @NonNull Display display) {
            super(outerContext, display, android.R.style.Theme_Translucent_NoTitleBar);
        }

        @Override
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            Window window = getWindow();
            if (window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                window.setDimAmount(0f);
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                );
            }

            FrameLayout root = new FrameLayout(getContext());
            root.setBackgroundColor(Color.TRANSPARENT);
            root.setFocusable(true);
            root.setFocusableInTouchMode(true);
            imeHost = root;
            setContentView(root);

            root.post(() -> {
                root.requestFocusFromTouch();
                root.requestFocus();
                root.requestApplyInsets();
            });
        }

        @Nullable
        FrameLayout getImeHost() {
            return imeHost;
        }
    }
}

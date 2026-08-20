/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 */

package ca.dnamobile.droidbridgelauncher.dualscreen;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

import ca.dnamobile.droidbridgelauncher.controls.ControlsPreferences;
import ca.dnamobile.droidbridgelauncher.controls.TouchControlsOverlay;
import ca.dnamobile.droidbridgelauncher.runtime.MinecraftGLSurface;

final class DualScreenPresentation extends Presentation {
    @NonNull private final File hudStateFile;
    @NonNull private final Runnable launcherMenuCallback;
    @Nullable private final MinecraftGLSurface passthroughTarget;
    @Nullable private DualScreenBackgroundView backgroundView;
    @Nullable private Runnable displayLossListener;
    private boolean displayLossReported;

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
        this.passthroughTarget = passthroughTarget;
    }

    void setDisplayLossListener(@Nullable Runnable listener) {
        displayLossListener = listener;
    }

    void reloadBackground() {
        if (backgroundView != null) backgroundView.reload();
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

        backgroundView = new DualScreenBackgroundView(getContext());
        root.addView(backgroundView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        backgroundView.reload();

        // When screens are swapped, this Presentation becomes the old bottom screen on
        // the external display. Show the same selected touch layout first, then draw the
        // mirrored HUD/hotbar above it just like the local dual-screen bottom deck.
        TouchControlsOverlay touchOverlay = new TouchControlsOverlay(getContext());
        touchOverlay.setAppMenuListener(() -> launcherMenuCallback.run());
        touchOverlay.loadSelectedLayout();
        touchOverlay.applyVirtualMouseLaunchSessionState();
        touchOverlay.setPassthroughTarget(passthroughTarget);
        File gameDir = hudStateFile.getParentFile();
        touchOverlay.setMinecraftOptionsFile(gameDir == null ? null : new File(gameDir, "options.txt"));
        touchOverlay.setDualScreenBottomHudHotbarMode(true);
        touchOverlay.setControlsVisible(ControlsPreferences.isTouchControlsEnabled(getContext()));
        root.addView(touchOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        DualScreenControlsView controlsView = new DualScreenControlsView(
                getContext(),
                hudStateFile,
                launcherMenuCallback
        );
        root.addView(controlsView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        setContentView(root);
    }
}

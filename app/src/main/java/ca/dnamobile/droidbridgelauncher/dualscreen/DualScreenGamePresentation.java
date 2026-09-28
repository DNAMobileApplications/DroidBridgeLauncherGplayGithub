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
import android.os.Bundle;
import android.view.Display;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

import ca.dnamobile.droidbridgelauncher.runtime.MinecraftGLSurface;

final class DualScreenGamePresentation extends Presentation {
    @NonNull private final File hudStateFile;
    @Nullable private final MinecraftGLSurface sharedMinecraftSurface;
    @Nullable private MinecraftGLSurface minecraftSurface;
    @Nullable private DualScreenControlsView topLegacyHudView;
    @Nullable private Runnable displayLossListener;
    private boolean displayLossReported;

    DualScreenGamePresentation(
            @NonNull Context outerContext,
            @NonNull Display display,
            @NonNull File hudStateFile
    ) {
        this(outerContext, display, hudStateFile, null);
    }

    /**
     * SDL3/Vulkan in Minecraft 26.3 binds its VkSurfaceKHR to the ANativeWindow
     * selected at startup. Reusing DroidBridge's existing MinecraftGLSurface lets
     * its retained TextureView/SurfaceTexture move between Android displays without
     * replacing that native window during a live dual-screen swap.
     */
    DualScreenGamePresentation(
            @NonNull Context outerContext,
            @NonNull Display display,
            @NonNull File hudStateFile,
            @Nullable MinecraftGLSurface sharedMinecraftSurface
    ) {
        super(outerContext, display);
        this.hudStateFile = hudStateFile;
        this.sharedMinecraftSurface = sharedMinecraftSurface;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean thor = AynThorDisplayCompat.isAynThorDevice(getContext());
        Window window = getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            if (thor) {
                // The game panel is output-only. Keeping this window out of Android's
                // input-focus chain lets the other Thor panel remain the controller/touch host.
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            }
        }

        FrameLayout root = new FrameLayout(getContext());
        root.setBackgroundColor(Color.BLACK);
        root.setFocusable(!thor);
        root.setFocusableInTouchMode(!thor);

        MinecraftGLSurface surface = sharedMinecraftSurface != null
                ? sharedMinecraftSurface
                : new MinecraftGLSurface(getContext());
        if (surface.getParent() instanceof ViewGroup) {
            ((ViewGroup) surface.getParent()).removeView(surface);
        }
        surface.setVisibility(android.view.View.VISIBLE);
        surface.setAlpha(1f);
        minecraftSurface = surface;
        root.addView(surface, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // The optional Legacy HUD is an Android overlay above the real Minecraft surface.
        // It reads the exact same state/config files as the lower deck, but never owns input.
        // Keeping it in this Presentation means it follows Minecraft when the game is hosted
        // on the external display instead of being tied to Android's default Activity window.
        DualScreenControlsView legacyHud = new DualScreenControlsView(
                getContext(), hudStateFile, () -> { }, true
        );
        topLegacyHudView = legacyHud;
        root.addView(legacyHud, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        setContentView(root);
    }

    void setDisplayLossListener(@Nullable Runnable listener) {
        displayLossListener = listener;
    }

    @Override
    public void onDisplayRemoved() {
        // Presentation receives this callback before Android automatically cancels the
        // dialog, giving DroidBridge its earliest chance to move Vulkan off the dead
        // display window.
        notifyDisplayLoss();
        super.onDisplayRemoved();
    }

    @Override
    protected void onStop() {
        boolean displayInvalid = false;
        try {
            Display display = getDisplay();
            displayInvalid = display == null || !display.isValid();
        } catch (Throwable ignored) {
            displayInvalid = true;
        }
        if (displayInvalid) notifyDisplayLoss();
        super.onStop();
    }

    private void notifyDisplayLoss() {
        if (displayLossReported) return;
        displayLossReported = true;
        Runnable listener = displayLossListener;
        if (listener != null) listener.run();
    }

    boolean isUsingSharedMinecraftSurface() {
        return sharedMinecraftSurface != null && minecraftSurface == sharedMinecraftSurface;
    }

    @NonNull
    MinecraftGLSurface getMinecraftSurface() {
        if (minecraftSurface == null) {
            minecraftSurface = sharedMinecraftSurface != null
                    ? sharedMinecraftSurface
                    : new MinecraftGLSurface(getContext());
        }
        return minecraftSurface;
    }
}

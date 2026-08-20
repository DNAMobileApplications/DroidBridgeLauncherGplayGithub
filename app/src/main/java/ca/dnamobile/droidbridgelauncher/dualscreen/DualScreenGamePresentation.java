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
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.runtime.MinecraftGLSurface;

final class DualScreenGamePresentation extends Presentation {
    @Nullable private MinecraftGLSurface minecraftSurface;
    @Nullable private Runnable displayLossListener;
    private boolean displayLossReported;

    DualScreenGamePresentation(@NonNull Context outerContext, @NonNull Display display) {
        super(outerContext, display);
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

        MinecraftGLSurface surface = new MinecraftGLSurface(getContext());
        minecraftSurface = surface;
        root.addView(surface, new FrameLayout.LayoutParams(
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

    @NonNull
    MinecraftGLSurface getMinecraftSurface() {
        if (minecraftSurface == null) {
            minecraftSurface = new MinecraftGLSurface(getContext());
        }
        return minecraftSurface;
    }
}

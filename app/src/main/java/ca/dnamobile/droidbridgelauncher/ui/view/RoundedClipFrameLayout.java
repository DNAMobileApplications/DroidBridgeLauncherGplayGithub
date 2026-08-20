/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 *
 * Files written entirely by DNA Mobile Applications are proprietary unless
 * a file header or separate license notice states otherwise.
 */

package ca.dnamobile.droidbridgelauncher.ui.view;

import android.content.Context;
import android.graphics.Outline;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Rounded container for pill-style tab groups.
 *
 * MaterialCardView can draw a thin compatibility/shadow/stroke artifact under a
 * zero-elevation card on some devices/themes. This view clips children to the
 * same rounded outline but does not draw card compat padding, shadows, or strokes.
 */
public final class RoundedClipFrameLayout extends FrameLayout {
    private float radiusPx;

    public RoundedClipFrameLayout(@NonNull Context context) {
        super(context);
        init();
    }

    public RoundedClipFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RoundedClipFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        radiusPx = 28f * getResources().getDisplayMetrics().density;
        setClipChildren(true);
        setClipToPadding(true);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
    }
}

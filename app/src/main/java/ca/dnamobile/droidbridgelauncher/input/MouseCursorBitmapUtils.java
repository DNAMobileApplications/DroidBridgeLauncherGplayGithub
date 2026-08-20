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

package ca.dnamobile.droidbridgelauncher.input;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

import ca.dnamobile.droidbridgelauncher.controls.ControlsPreferences;

/** Shared loading and hotspot rules for launcher-rendered Minecraft menu cursors. */
public final class MouseCursorBitmapUtils {
    private static final int MAX_CUSTOM_CURSOR_DECODE_SIZE = 512;

    private MouseCursorBitmapUtils() {
    }

    @Nullable
    public static Bitmap loadCursorBitmap(
            @NonNull Context context,
            @NonNull String style,
            @Nullable String customPath
    ) {
        Bitmap bitmap = null;

        if (ControlsPreferences.MOUSE_CURSOR_STYLE_CUSTOM.equals(style) && customPath != null) {
            try {
                File file = new File(customPath);
                if (file.isFile() && file.length() > 0L) {
                    bitmap = decodeSampledFile(file, MAX_CUSTOM_CURSOR_DECODE_SIZE);
                }
            } catch (Throwable ignored) {
            }
        }

        if (bitmap == null) {
            try {
                int id = context.getResources().getIdentifier(
                        ControlsPreferences.getMouseCursorResourceName(style),
                        "drawable",
                        context.getPackageName()
                );
                if (id == 0) {
                    id = context.getResources().getIdentifier(
                            "ic_cursor_arrow",
                            "drawable",
                            context.getPackageName()
                    );
                }
                bitmap = id != 0 ? BitmapFactory.decodeResource(context.getResources(), id) : null;
            } catch (Throwable ignored) {
            }
        }

        return trimTransparentPadding(bitmap);
    }

    /**
     * Crosshair-style cursors click at their centre. Mouse arrows and custom cursor
     * images click at their top-left hotspot, matching normal desktop cursor behavior.
     */
    public static boolean usesCenteredHotspot(@Nullable String style) {
        String normalized = ControlsPreferences.normalizeMouseCursorStyle(style);
        return ControlsPreferences.MOUSE_CURSOR_STYLE_CROSSHAIR.equals(normalized)
                || ControlsPreferences.MOUSE_CURSOR_STYLE_DOT.equals(normalized);
    }

    public static int getDrawWidth(@Nullable Bitmap bitmap, int maxExtentPx) {
        if (bitmap == null || bitmap.isRecycled()) return Math.max(1, maxExtentPx);
        int width = Math.max(1, bitmap.getWidth());
        int height = Math.max(1, bitmap.getHeight());
        if (width >= height) return Math.max(1, maxExtentPx);
        return Math.max(1, Math.round(maxExtentPx * (width / (float) height)));
    }

    public static int getDrawHeight(@Nullable Bitmap bitmap, int maxExtentPx) {
        if (bitmap == null || bitmap.isRecycled()) return Math.max(1, maxExtentPx);
        int width = Math.max(1, bitmap.getWidth());
        int height = Math.max(1, bitmap.getHeight());
        if (height >= width) return Math.max(1, maxExtentPx);
        return Math.max(1, Math.round(maxExtentPx * (height / (float) width)));
    }

    @Nullable
    private static Bitmap decodeSampledFile(@NonNull File file, int maxDimension) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sampleSize = 1;
        int largest = Math.max(bounds.outWidth, bounds.outHeight);
        while (largest / sampleSize > maxDimension && sampleSize < 128) {
            sampleSize *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    @Nullable
    private static Bitmap trimTransparentPadding(@Nullable Bitmap source) {
        if (source == null || source.isRecycled()) return source;

        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 1 || height <= 1 || !source.hasAlpha()) return source;

        int[] pixels;
        try {
            pixels = new int[width * height];
            source.getPixels(pixels, 0, width, 0, 0, width, height);
        } catch (Throwable ignored) {
            return source;
        }

        int left = width;
        int top = height;
        int right = -1;
        int bottom = -1;

        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                if ((pixels[row + x] >>> 24) == 0) continue;
                if (x < left) left = x;
                if (x > right) right = x;
                if (y < top) top = y;
                if (y > bottom) bottom = y;
            }
        }

        if (right < left || bottom < top) return source;
        if (left == 0 && top == 0 && right == width - 1 && bottom == height - 1) return source;

        try {
            Bitmap cropped = Bitmap.createBitmap(
                    source,
                    left,
                    top,
                    right - left + 1,
                    bottom - top + 1
            );
            if (cropped != source && !source.isRecycled()) source.recycle();
            return cropped;
        } catch (Throwable ignored) {
            return source;
        }
    }
}

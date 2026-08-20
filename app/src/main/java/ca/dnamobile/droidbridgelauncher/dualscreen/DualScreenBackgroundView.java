/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 */

package ca.dnamobile.droidbridgelauncher.dualscreen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;

/**
 * Memory-safe background image used by the dual-screen controls/HUD deck.
 *
 * The selected source image is copied into DroidBridge's private files directory.
 * This view downsamples it to approximately the current display size before drawing,
 * so a very large phone/camera image does not allocate its full-resolution bitmap.
 */
final class DualScreenBackgroundView extends ImageView {
    private static final int DEFAULT_BACKGROUND = Color.rgb(7, 10, 16);

    @Nullable private Bitmap loadedBitmap;
    private long loadedLastModified = Long.MIN_VALUE;
    private long loadedLength = Long.MIN_VALUE;
    private int loadedForWidth;
    private int loadedForHeight;

    DualScreenBackgroundView(@NonNull Context context) {
        super(context);
        setScaleType(ScaleType.CENTER_CROP);
        setBackgroundColor(DEFAULT_BACKGROUND);
        setClickable(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
    }

    void reload() {
        File file = LauncherPreferences.getDualScreenBackgroundImageFile(getContext());
        if (!file.isFile() || file.length() <= 0L) {
            clearLoadedBitmap();
            loadedLastModified = Long.MIN_VALUE;
            loadedLength = Long.MIN_VALUE;
            return;
        }

        int width = Math.max(1, getWidth());
        int height = Math.max(1, getHeight());
        if (width <= 1 || height <= 1) {
            // onSizeChanged() will reload after the controls display is measured.
            return;
        }

        long modified = file.lastModified();
        long length = file.length();
        if (loadedBitmap != null
                && !loadedBitmap.isRecycled()
                && loadedLastModified == modified
                && loadedLength == length
                && loadedForWidth == width
                && loadedForHeight == height) {
            return;
        }

        Bitmap next = decodeSampled(file, width, height);
        if (next == null) {
            clearLoadedBitmap();
            return;
        }

        Bitmap old = loadedBitmap;
        loadedBitmap = next;
        loadedLastModified = modified;
        loadedLength = length;
        loadedForWidth = width;
        loadedForHeight = height;
        setImageBitmap(next);

        if (old != null && old != next && !old.isRecycled()) {
            old.recycle();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0 && (w != oldw || h != oldh)) {
            reload();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        clearLoadedBitmap();
        super.onDetachedFromWindow();
    }

    private void clearLoadedBitmap() {
        setImageDrawable(null);
        Bitmap old = loadedBitmap;
        loadedBitmap = null;
        loadedForWidth = 0;
        loadedForHeight = 0;
        if (old != null && !old.isRecycled()) {
            old.recycle();
        }
    }

    @Nullable
    private static Bitmap decodeSampled(
            @NonNull File file,
            int targetWidth,
            int targetHeight
    ) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        while ((bounds.outWidth / (sample * 2)) >= targetWidth
                && (bounds.outHeight / (sample * 2)) >= targetHeight) {
            sample *= 2;
        }

        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = Math.max(1, sample);
        decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), decode);
    }
}

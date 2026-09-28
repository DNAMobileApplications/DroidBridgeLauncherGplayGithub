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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
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

    @NonNull private final Paint texturePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    @Nullable private Bitmap loadedBitmap;
    @Nullable private Bitmap legacyDefaultBitmap;
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
    protected void onDraw(@NonNull Canvas canvas) {
        if (loadedBitmap == null || loadedBitmap.isRecycled()) {
            if (LauncherPreferences.isDualScreen3dsHudLayout(getContext())) {
                draw3dsDefaultBackground(canvas);
            } else if (LauncherPreferences.isDualScreenLegacyHudLayout(getContext())) {
                drawLegacyDefaultBackground(canvas);
            } else {
                drawDefaultBlockBackground(canvas);
            }
        }
        super.onDraw(canvas);
    }


    /**
     * Flat lower-screen surface used by the 3DS-inspired layout. The reference UI is a
     * neutral console-gray deck with the live map and bitmap buttons providing the detail.
     * A user-selected custom background still overrides this exactly like the other layouts.
     */
    private void draw3dsDefaultBackground(@NonNull Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        final float referenceAspect = 640f / 480f;
        float actualAspect = width / (float) height;
        if (actualAspect > referenceAspect) {
            // Match the reference's black side bars on wide lower displays while leaving
            // Thor's narrower native panel untouched.
            canvas.drawColor(Color.BLACK);
            float deckWidth = height * referenceAspect;
            float left = (width - deckWidth) * 0.5f;
            texturePaint.setStyle(Paint.Style.FILL);
            texturePaint.setColor(Color.rgb(144, 140, 143));
            canvas.drawRect(left, 0f, left + deckWidth, height, texturePaint);
        } else {
            canvas.drawColor(Color.rgb(144, 140, 143));
        }
    }

    /**
     * Exact legacy-console bottom-screen background supplied for the Legacy layout.
     * It is bundled as a launcher drawable so it is available before Minecraft exports
     * any assets, and custom user backgrounds still override it through loadedBitmap.
     */
    private void drawLegacyDefaultBackground(@NonNull Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        Bitmap bitmap = legacyDefaultBitmap;
        if (bitmap == null || bitmap.isRecycled()) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            bitmap = BitmapFactory.decodeResource(
                    getResources(),
                    ca.dnamobile.droidbridgelauncher.R.drawable.droidbridge_legacy_bottom_background,
                    options
            );
            legacyDefaultBitmap = bitmap;
        }
        if (bitmap == null || bitmap.isRecycled()) {
            canvas.drawColor(Color.rgb(154, 150, 154));
            return;
        }

        texturePaint.setFilterBitmap(false);
        texturePaint.setDither(false);
        RectF target = new RectF(0f, 0f, width, height);
        canvas.drawBitmap(bitmap, null, target, texturePaint);
    }

    /**
     * Launcher-owned dark block texture inspired by the legacy-console Minecraft UI.
     * It is generated locally so the default works before any game assets are exported,
     * and a user-picked background still replaces it exactly as before.
     */
    private void drawDefaultBlockBackground(@NonNull Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        canvas.drawColor(Color.rgb(55, 49, 43));
        int tile = Math.max(dp(42f), Math.max(1, Math.min(width, height) / 8));
        int cols = (width + tile - 1) / tile;
        int rows = (height + tile - 1) / tile;

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int hash = (col * 73856093) ^ (row * 19349663);
                int variation = ((hash >>> 3) & 15) - 7;
                int r = clampColor(70 + variation);
                int g = clampColor(63 + variation);
                int b = clampColor(55 + variation);
                float left = col * tile;
                float top = row * tile;

                texturePaint.setStyle(Paint.Style.FILL);
                texturePaint.setColor(Color.rgb(r, g, b));
                canvas.drawRect(left, top, left + tile, top + tile, texturePaint);

                // Chunky pixel flecks rather than smooth noise keeps the visual language
                // close to the reference image without bundling or sampling Minecraft art.
                int quarter = Math.max(2, tile / 4);
                texturePaint.setColor(Color.argb(54, 235, 224, 204));
                if ((hash & 1) == 0) {
                    canvas.drawRect(left + quarter, top + quarter, left + quarter * 2f, top + quarter * 2f, texturePaint);
                } else {
                    canvas.drawRect(left + quarter * 2f, top + quarter, left + quarter * 3f, top + quarter * 2f, texturePaint);
                }
                texturePaint.setColor(Color.argb(76, 0, 0, 0));
                canvas.drawRect(left, top + tile - Math.max(2, tile / 12f), left + tile, top + tile, texturePaint);
                canvas.drawRect(left + tile - Math.max(2, tile / 12f), top, left + tile, top + tile, texturePaint);
            }
        }

        // One subtle dark veil makes HUD text and the map feel integrated with the deck.
        texturePaint.setColor(Color.argb(42, 0, 0, 0));
        canvas.drawRect(0f, 0f, width, height, texturePaint);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clampColor(int value) {
        return Math.max(0, Math.min(255, value));
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
        Bitmap legacy = legacyDefaultBitmap;
        legacyDefaultBitmap = null;
        if (legacy != null && !legacy.isRecycled()) legacy.recycle();
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

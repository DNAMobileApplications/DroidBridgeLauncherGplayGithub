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

package ca.dnamobile.droidbridgelauncher.controls;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Resolves Minecraft's GUI scale from options.txt for launcher-side touch hitboxes.
 *
 * The launcher cannot ask Minecraft directly for the current GUI scale, but Minecraft
 * writes guiScale to options.txt when the user changes it. This helper reads that
 * tiny file using a short throttle so the hotbar hitbox can follow in-game GUI-scale
 * changes without doing file I/O every frame/touch event.
 */
public final class MinecraftGuiScaleResolver {
    private static final long READ_THROTTLE_MS = 750L;
    private static final int MIN_SCALE = 1;
    private static final int MAX_SCALE = 8;

    @Nullable private static File lastFile;
    private static long lastReadAtMs;
    private static long lastModified;
    private static int cachedRequestedScale = 0;

    private MinecraftGuiScaleResolver() {
    }

    /**
     * Resolve the effective GUI scale Minecraft should be using for the current view.
     *
     * @param optionsFile Current instance/game directory options.txt. Null is allowed.
     * @param widthPx Current game/overlay width in physical pixels.
     * @param heightPx Current game/overlay height in physical pixels.
     * @return A safe scale in the range 1..8.
     */
    public static int resolve(@Nullable File optionsFile, float widthPx, float heightPx) {
        return resolveRequestedScale(readGuiScaleCached(optionsFile), widthPx, heightPx);
    }

    /**
     * Reads Minecraft's saved guiScale value from options.txt using the normal cache.
     * 0 means Minecraft Auto, or unknown if the file could not be read.
     */
    public static int readGuiScaleCached(@Nullable File optionsFile) {
        return readGuiScaleThrottled(optionsFile);
    }

    /**
     * Converts a saved guiScale value into the effective hitbox scale.
     * Explicit Minecraft values are trusted. Only guiScale:0 / missing values use
     * the Minecraft-style max-scale calculation.
     */
    public static int resolveRequestedScale(int requestedScale, float widthPx, float heightPx) {
        if (requestedScale <= 0) {
            return calculateMinecraftMaxScale(widthPx, heightPx);
        }
        return clamp(requestedScale, MIN_SCALE, MAX_SCALE);
    }


    /**
     * Resolves guiScale using the real/scaled Minecraft framebuffer size.
     *
     * This is different from resolveRequestedScale(...), which intentionally trusts
     * explicit saved values for callers that only know the final Android view size.
     * When the launcher resolution scaler is active, Minecraft first clamps the
     * requested GUI scale against the smaller framebuffer, then that framebuffer is
     * stretched back to the Android view.
     */
    public static int resolveRequestedScaleForFramebuffer(int requestedScale, float framebufferWidthPx, float framebufferHeightPx) {
        int maxScale = calculateMinecraftMaxScale(framebufferWidthPx, framebufferHeightPx);
        if (requestedScale <= 0) {
            return maxScale;
        }
        return clamp(requestedScale, MIN_SCALE, maxScale);
    }

    /**
     * Useful for debug text; returns the last value read from the cached options file.
     */
    public static int peekCachedRequestedScale() {
        return cachedRequestedScale;
    }

    /**
     * Useful for debug text; returns the last options.txt file read by this helper.
     */
    @Nullable
    public static File peekCachedFile() {
        return lastFile;
    }

    /**
     * Clear cached guiScale/options.txt state when the active instance changes.
     */
    public static void clearCache() {
        lastFile = null;
        lastReadAtMs = 0L;
        lastModified = 0L;
        cachedRequestedScale = 0;
    }

    /**
     * Minecraft-style max GUI scale: increasing scale must keep GUI at least 320x240.
     */
    public static int calculateMinecraftMaxScale(float widthPx, float heightPx) {
        float safeWidth = Math.max(1f, widthPx);
        float safeHeight = Math.max(1f, heightPx);

        int scale = MIN_SCALE;
        while (scale < MAX_SCALE
                && safeWidth / (scale + 1) >= 320f
                && safeHeight / (scale + 1) >= 240f) {
            scale++;
        }
        return clamp(scale, MIN_SCALE, MAX_SCALE);
    }

    private static int readGuiScaleThrottled(@Nullable File optionsFile) {
        if (optionsFile == null || !optionsFile.isFile()) {
            return 0;
        }

        long now = System.currentTimeMillis();
        long modified = safeLastModified(optionsFile);

        if (sameFile(optionsFile, lastFile)
                && modified == lastModified
                && now - lastReadAtMs < READ_THROTTLE_MS) {
            return cachedRequestedScale;
        }

        lastFile = optionsFile;
        lastModified = modified;
        lastReadAtMs = now;
        cachedRequestedScale = readGuiScale(optionsFile);
        return cachedRequestedScale;
    }

    private static int readGuiScale(@NonNull File optionsFile) {
        try (BufferedReader reader = new BufferedReader(new FileReader(optionsFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.startsWith("guiScale:")) {
                    return parseIntSafe(line.substring("guiScale:".length()).trim(), 0);
                }

                // Harmless fallback if a future/newer config writer ever uses key=value.
                if (line.startsWith("guiScale=")) {
                    return parseIntSafe(line.substring("guiScale=".length()).trim(), 0);
                }
            }
        } catch (Throwable ignored) {
        }

        return 0;
    }

    private static int parseIntSafe(@Nullable String raw, int fallback) {
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static long safeLastModified(@NonNull File file) {
        try {
            return file.lastModified();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static boolean sameFile(@Nullable File a, @Nullable File b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.getAbsolutePath().equals(b.getAbsolutePath());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

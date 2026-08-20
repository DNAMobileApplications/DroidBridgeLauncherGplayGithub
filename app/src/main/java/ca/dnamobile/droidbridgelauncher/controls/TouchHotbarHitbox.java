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

import android.content.Context;
import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.settings.GameResolutionSettings;

public final class TouchHotbarHitbox {
    public static final int SLOT_COUNT = 9;

    private TouchHotbarHitbox() {
    }

    public static final class Result {
        public final RectF hotbarBounds;
        public final RectF touchBounds;
        /** Minecraft GUI scale from options.txt/manual override before render-resolution stretching. */
        public final float scale;
        public final float slotWidth;
        public final int overrideScale;
        public final int minecraftGuiScale;
        public final boolean optionsFileFound;
        public final boolean usedEstimateFallback;

        /** Multiplier from the scaled game framebuffer back to the Android view. */
        public final float renderScaleX;
        public final float renderScaleY;
        public final float gameBufferWidth;
        public final float gameBufferHeight;
        public final int resolutionScalePercent;
        public final boolean renderScaleFromGameBuffer;

        private Result(
                @NonNull RectF hotbarBounds,
                @NonNull RectF touchBounds,
                float scale,
                int overrideScale,
                int minecraftGuiScale,
                boolean optionsFileFound,
                boolean usedEstimateFallback,
                @NonNull RenderScale renderScale
        ) {
            this.hotbarBounds = hotbarBounds;
            this.touchBounds = touchBounds;
            this.scale = scale;
            this.overrideScale = overrideScale;
            this.minecraftGuiScale = minecraftGuiScale;
            this.optionsFileFound = optionsFileFound;
            this.usedEstimateFallback = usedEstimateFallback;
            this.renderScaleX = renderScale.x;
            this.renderScaleY = renderScale.y;
            this.gameBufferWidth = renderScale.gameBufferWidth;
            this.gameBufferHeight = renderScale.gameBufferHeight;
            this.resolutionScalePercent = renderScale.resolutionScalePercent;
            this.renderScaleFromGameBuffer = renderScale.fromGameBuffer;
            this.slotWidth = Math.max(1f, hotbarBounds.width() / SLOT_COUNT);
        }

        public int slotFor(float x, float y) {
            if (!touchBounds.contains(x, y)) return -1;

            // Keep horizontal slot math tied to the real hotbar width, not touch padding.
            // This avoids outside-left/outside-right touches bleeding into slot 1/9.
            if (x < hotbarBounds.left || x >= hotbarBounds.right) return -1;

            float localX = clamp(x - hotbarBounds.left, 0f, hotbarBounds.width() - 1f);
            int slot = (int) ((localX * SLOT_COUNT) / hotbarBounds.width());
            return Math.max(0, Math.min(SLOT_COUNT - 1, slot));
        }

        @NonNull
        public String scaleSourceLabel() {
            if (overrideScale > 0) return "manual";
            if (optionsFileFound) return minecraftGuiScale <= 0 ? "mc-auto" : "mc";
            return "estimate";
        }
    }

    @NonNull
    public static Result calculate(
            @NonNull Context context,
            float viewWidth,
            float viewHeight,
            float fallbackWidth,
            float fallbackHeight
    ) {
        return calculate(context, null, viewWidth, viewHeight, fallbackWidth, fallbackHeight);
    }

    @NonNull
    public static Result calculate(
            @NonNull Context context,
            @Nullable File optionsFile,
            float viewWidth,
            float viewHeight,
            float fallbackWidth,
            float fallbackHeight
    ) {
        float availableWidth = Math.max(1f, viewWidth > 0f ? viewWidth : fallbackWidth);
        float availableHeight = Math.max(1f, viewHeight > 0f ? viewHeight : fallbackHeight);
        GameResolutionSettings.DisplayBounds displayBounds =
                GameResolutionSettings.resolveDisplayBounds(
                        context,
                        Math.max(1, Math.round(availableWidth)),
                        Math.max(1, Math.round(availableHeight))
                );
        float width = displayBounds.width;
        float height = displayBounds.height;

        float density = Math.max(1f, context.getResources().getDisplayMetrics().density);
        int scaleOverride = ControlsPreferences.getHotbarGuiScaleOverride(context);

        RenderScale renderScale = resolveRenderScale(context, width, height, fallbackWidth, fallbackHeight);

        boolean optionsFileFound = optionsFile != null && optionsFile.isFile();
        int minecraftGuiScale = optionsFileFound ? MinecraftGuiScaleResolver.readGuiScaleCached(optionsFile) : 0;
        boolean usedEstimateFallback = false;

        // Manual values 2..8 are final launcher-side overrides. They intentionally
        // bypass Minecraft/options.txt math so the user can recover on weird devices.
        // Value 0 is the user-facing "Auto match Minecraft" mode.
        float framebufferGuiScale;
        boolean shouldApplyRenderStretch = false;
        if (scaleOverride > 0) {
            framebufferGuiScale = scaleOverride;
        } else if (optionsFileFound) {
            // Important: clamp Minecraft's requested guiScale against the scaled
            // framebuffer, not the final stretched Android view. At 67% resolution,
            // a saved guiScale:4 may be effectively rendered as scale 3 inside a
            // 1608x724 framebuffer, then stretched to the screen. Multiplying the
            // raw requested 4 by the stretch factor makes the hitbox huge.
            framebufferGuiScale = MinecraftGuiScaleResolver.resolveRequestedScaleForFramebuffer(
                    minecraftGuiScale,
                    renderScale.gameBufferWidth,
                    renderScale.gameBufferHeight
            );
            shouldApplyRenderStretch = true;
        } else {
            usedEstimateFallback = true;
            framebufferGuiScale = estimateHotbarGuiScale(width, height);
        }

        float scaleX = framebufferGuiScale * (shouldApplyRenderStretch ? renderScale.x : 1f);
        float scaleY = framebufferGuiScale * (shouldApplyRenderStretch ? renderScale.y : 1f);
        float debugScale = (scaleX + scaleY) / 2f;

        float hotbarWidth = ControlsPreferences.getHotbarWidthGui(context) * scaleX;
        float hotbarHeight = ControlsPreferences.getHotbarHeightGui(context) * scaleY;
        float xOffset = ControlsPreferences.getHotbarXOffsetDp(context) * density;
        float yOffset = ControlsPreferences.getHotbarYOffsetDp(context) * density;
        float verticalPadding = Math.max(
                ControlsPreferences.getHotbarVerticalPaddingDp(context) * density,
                2f * scaleY
        );

        float left = displayBounds.left + (width / 2f) - (hotbarWidth / 2f) + xOffset;
        float top = displayBounds.top + height - hotbarHeight - yOffset;
        RectF hotbar = new RectF(left, top, left + hotbarWidth, top + hotbarHeight);

        // Keep horizontal strict. Only grow vertically for finger forgiveness.
        RectF touch = new RectF(hotbar.left, hotbar.top - verticalPadding, hotbar.right, hotbar.bottom + verticalPadding);
        return new Result(hotbar, touch, debugScale, scaleOverride, minecraftGuiScale, optionsFileFound, usedEstimateFallback, renderScale);
    }

    public static int slotForTouch(
            @NonNull Context context,
            float viewWidth,
            float viewHeight,
            float fallbackWidth,
            float fallbackHeight,
            float x,
            float y
    ) {
        return slotForTouch(context, null, viewWidth, viewHeight, fallbackWidth, fallbackHeight, x, y);
    }

    public static int slotForTouch(
            @NonNull Context context,
            @Nullable File optionsFile,
            float viewWidth,
            float viewHeight,
            float fallbackWidth,
            float fallbackHeight,
            float x,
            float y
    ) {
        return calculate(context, optionsFile, viewWidth, viewHeight, fallbackWidth, fallbackHeight).slotFor(x, y);
    }

    @NonNull
    private static RenderScale resolveRenderScale(
            @NonNull Context context,
            float viewWidth,
            float viewHeight,
            float gameBufferWidth,
            float gameBufferHeight
    ) {
        int resolutionScalePercent = LauncherPreferences.getGameResolutionScalePercent(context);

        float safeViewWidth = Math.max(1f, viewWidth);
        float safeViewHeight = Math.max(1f, viewHeight);
        float safeBufferWidth = gameBufferWidth > 1f ? gameBufferWidth : safeViewWidth;
        float safeBufferHeight = gameBufferHeight > 1f ? gameBufferHeight : safeViewHeight;

        float x = clamp(safeViewWidth / Math.max(1f, safeBufferWidth), 0.25f, 4f);
        float y = clamp(safeViewHeight / Math.max(1f, safeBufferHeight), 0.25f, 4f);
        boolean fromGameBuffer = Math.abs(x - 1f) > 0.025f || Math.abs(y - 1f) > 0.025f;

        // Some bridge paths can briefly report the Android content size before the
        // real GLFW framebuffer is ready. Resolve the selected base resolution plus
        // the percentage scale so custom/4:3 modes keep the hotbar aligned too.
        if (!fromGameBuffer && resolutionScalePercent > 0) {
            GameResolutionSettings.ResolvedResolution expectedBuffer =
                    GameResolutionSettings.resolveRenderResolution(
                            context,
                            Math.max(1, Math.round(safeViewWidth)),
                            Math.max(1, Math.round(safeViewHeight)),
                            resolutionScalePercent
                    );
            safeBufferWidth = expectedBuffer.width;
            safeBufferHeight = expectedBuffer.height;
            x = clamp(safeViewWidth / Math.max(1f, safeBufferWidth), 0.25f, 4f);
            y = clamp(safeViewHeight / Math.max(1f, safeBufferHeight), 0.25f, 4f);
            fromGameBuffer = false;
        }

        return new RenderScale(x, y, safeBufferWidth, safeBufferHeight, resolutionScalePercent, fromGameBuffer);
    }

    private static final class RenderScale {
        final float x;
        final float y;
        final float gameBufferWidth;
        final float gameBufferHeight;
        final int resolutionScalePercent;
        final boolean fromGameBuffer;

        RenderScale(
                float x,
                float y,
                float gameBufferWidth,
                float gameBufferHeight,
                int resolutionScalePercent,
                boolean fromGameBuffer
        ) {
            this.x = x;
            this.y = y;
            this.gameBufferWidth = gameBufferWidth;
            this.gameBufferHeight = gameBufferHeight;
            this.resolutionScalePercent = resolutionScalePercent;
            this.fromGameBuffer = fromGameBuffer;
        }
    }

    private static float estimateHotbarGuiScale(float width, float height) {
        // Existing safe fallback. The in-game editor can still manually override this
        // to match Minecraft GUI Scale 2..8 without rebuilding.
        float shortSide = Math.min(width, height);
        float byHeight = Math.max(1f, Math.round(shortSide / 360f));
        float vanillaMax = Math.max(1f, Math.min((float) Math.floor(width / 320f), (float) Math.floor(height / 240f)));
        return Math.max(1f, Math.min(byHeight, vanillaMax));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}

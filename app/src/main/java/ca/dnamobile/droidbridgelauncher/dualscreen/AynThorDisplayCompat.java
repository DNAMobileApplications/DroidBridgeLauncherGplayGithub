/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 */

package ca.dnamobile.droidbridgelauncher.dualscreen;

import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;

/**
 * AYN Thor display compatibility.
 *
 * Android exposes the Thor's 1240x1080 lower touch panel as the default display and
 * its 1920x1080 upper panel as a secondary/presentation display. When DroidBridge's
 * split HUD mode is disabled, the firmware can mirror the default display to the
 * upper panel while retaining the lower panel's framebuffer size. This helper keeps
 * Minecraft's native framebuffer aligned with the upper panel without forcing that
 * behaviour on ordinary phones or other dual-screen hardware.
 */
public final class AynThorDisplayCompat {
    private static final int THOR_TOP_LONG_EDGE = 1920;
    private static final int THOR_TOP_SHORT_EDGE = 1080;
    private static final int THOR_BOTTOM_LONG_EDGE = 1240;
    private static final int THOR_BOTTOM_SHORT_EDGE = 1080;
    private static final int PANEL_TOLERANCE_PX = 160;

    private AynThorDisplayCompat() {
    }

    /**
     * Returns the upper-panel framebuffer to use while Minecraft is running on the
     * default/lower panel in single-screen mode, or {@code null} when no Thor fix is
     * required. Explicit dual-screen and swapped layouts retain their own panel size.
     */
    @Nullable
    public static Point resolveSingleScreenGameFramebuffer(
            @NonNull Context context,
            int currentWidth,
            int currentHeight
    ) {
        boolean dualScreenRequested = LauncherPreferences.isDualScreenSupportEnabled(context)
                && LauncherPreferences.isDualScreenLastExternalRequest(context);
        if (dualScreenRequested) return null;

        // Never combine an upper-panel-sized buffer with a lower-panel edge-to-edge
        // native window. On Thor firmware that mismatched pair can reach Mesa as an
        // invalid drawable and terminate inside libgallium_dri.so. Dual-screen mode
        // remains the safe path for true 1920x1080 output on the upper panel.
        if (LauncherPreferences.isForceFullscreenMode(context)) {
            return null;
        }

        DisplayManager manager = displayManager(context);
        if (manager == null) return null;

        Display upperDisplay = isAynThorBuild() ? findThorTopDisplay(context) : findLargestSecondaryDisplay(manager);
        if (upperDisplay == null) return null;

        Point upperSize = realSize(upperDisplay);
        if (upperSize == null) return null;

        boolean modelMatch = isAynThorBuild();
        boolean panelPairMatch = looksLikeThorBottomPanel(currentWidth, currentHeight)
                && looksLikeThorTopPanel(upperSize.x, upperSize.y);
        if (!modelMatch && !panelPairMatch) return null;

        int currentLong = Math.max(currentWidth, currentHeight);
        int currentShort = Math.min(currentWidth, currentHeight);
        int upperLong = Math.max(upperSize.x, upperSize.y);
        int upperShort = Math.min(upperSize.x, upperSize.y);

        // The fix is only valid when the current surface is clearly the smaller panel
        // and both panels have approximately the same short edge, as on the Thor.
        if (upperLong <= currentLong + 200
                || Math.abs(upperShort - currentShort) > PANEL_TOLERANCE_PX) {
            return null;
        }
        if (!looksLikeThorBottomPanel(currentWidth, currentHeight)
                && currentLong > THOR_BOTTOM_LONG_EDGE + PANEL_TOLERANCE_PX) {
            return null;
        }

        boolean landscape = currentWidth >= currentHeight;
        return landscape
                ? new Point(upperLong, upperShort)
                : new Point(upperShort, upperLong);
    }

    /** Returns true for an AYN Thor by build identity or by its physical panel pair. */
    public static boolean isAynThorDevice(@NonNull Context context) {
        if (isAynThorBuild()) return true;
        return findThorTopDisplay(context) != null && findThorBottomDisplay(context) != null;
    }

    /** Finds the physical 1920x1080 upper panel regardless of Android display id. */
    @Nullable
    public static Display findThorTopDisplay(@NonNull Context context) {
        return findThorPanel(context, true);
    }

    /** Finds the physical 1240x1080 lower touch panel regardless of Android display id. */
    @Nullable
    public static Display findThorBottomDisplay(@NonNull Context context) {
        return findThorPanel(context, false);
    }

    public static boolean isThorTopDisplay(@Nullable Display display) {
        Point size = display == null ? null : realSize(display);
        return size != null && looksLikeThorTopPanel(size.x, size.y);
    }

    public static boolean isThorBottomDisplay(@Nullable Display display) {
        Point size = display == null ? null : realSize(display);
        return size != null && looksLikeThorBottomPanel(size.x, size.y);
    }

    @Nullable
    private static Display findThorPanel(@NonNull Context context, boolean top) {
        DisplayManager manager = displayManager(context);
        if (manager == null) return null;
        Display[] displays = manager.getDisplays();
        if (displays == null) return null;
        for (Display display : displays) {
            if (display == null || !display.isValid()) continue;
            Point size = realSize(display);
            if (size == null) continue;
            if (top ? looksLikeThorTopPanel(size.x, size.y)
                    : looksLikeThorBottomPanel(size.x, size.y)) {
                return display;
            }
        }
        return null;
    }

    public static boolean isAynThorBuild() {
        String identity = (safe(Build.MANUFACTURER) + " "
                + safe(Build.BRAND) + " "
                + safe(Build.MODEL) + " "
                + safe(Build.PRODUCT) + " "
                + safe(Build.DEVICE) + " "
                + safe(Build.HARDWARE)).toLowerCase(Locale.ROOT);
        return identity.contains("thor") && identity.contains("ayn");
    }

    @Nullable
    private static DisplayManager displayManager(@NonNull Context context) {
        Object service = context.getSystemService(Context.DISPLAY_SERVICE);
        return service instanceof DisplayManager ? (DisplayManager) service : null;
    }

    @Nullable
    private static Display findLargestSecondaryDisplay(@NonNull DisplayManager manager) {
        Display best = chooseLargestSecondary(manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION));
        return best != null ? best : chooseLargestSecondary(manager.getDisplays());
    }

    @Nullable
    private static Display chooseLargestSecondary(@Nullable Display[] displays) {
        if (displays == null) return null;
        Display best = null;
        long bestPixels = -1L;
        for (Display display : displays) {
            if (display == null || display.getDisplayId() == Display.DEFAULT_DISPLAY || !display.isValid()) {
                continue;
            }
            Point size = realSize(display);
            if (size == null) continue;
            long pixels = (long) Math.max(1, size.x) * Math.max(1, size.y);
            if (best == null || pixels > bestPixels) {
                best = display;
                bestPixels = pixels;
            }
        }
        return best;
    }

    @SuppressWarnings("deprecation")
    @Nullable
    private static Point realSize(@NonNull Display display) {
        try {
            Point size = new Point();
            display.getRealSize(size);
            return size.x > 0 && size.y > 0 ? size : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean looksLikeThorTopPanel(int width, int height) {
        return matchesNormalizedSize(width, height, THOR_TOP_LONG_EDGE, THOR_TOP_SHORT_EDGE);
    }

    private static boolean looksLikeThorBottomPanel(int width, int height) {
        return matchesNormalizedSize(width, height, THOR_BOTTOM_LONG_EDGE, THOR_BOTTOM_SHORT_EDGE);
    }

    private static boolean matchesNormalizedSize(
            int width,
            int height,
            int expectedLong,
            int expectedShort
    ) {
        int actualLong = Math.max(width, height);
        int actualShort = Math.min(width, height);
        return Math.abs(actualLong - expectedLong) <= PANEL_TOLERANCE_PX
                && Math.abs(actualShort - expectedShort) <= PANEL_TOLERANCE_PX;
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value;
    }
}

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

/**
 * Refresh-rate compatibility for Android dual-screen gaming handhelds whose two
 * built-in panels have different maximum refresh rates.
 *
 * This intentionally keys the correction to the physical display hosting the
 * Minecraft Activity instead of globally capping a model. The high-refresh panel
 * therefore keeps 120/144/165 Hz support, while the 60 Hz secondary panel cannot
 * accidentally inherit the other panel's application refresh rate.
 */
public final class DualScreenRefreshCompat {
    private static final int SIZE_TOLERANCE_PX = 160;

    // AYN Thor: 6-inch 1920x1080 120 Hz upper + 3.92-inch 1240x1080 60 Hz lower.
    private static final int THOR_BOTTOM_LONG = 1240;
    private static final int THOR_BOTTOM_SHORT = 1080;

    // AYANEO Pocket DS: 7-inch 1920x1080 up to 165 Hz main + 5-inch 1024x768 60 Hz secondary.
    private static final int POCKET_DS_SECONDARY_LONG = 1024;
    private static final int POCKET_DS_SECONDARY_SHORT = 768;

    private DualScreenRefreshCompat() {
    }

    /**
     * Returns a physical-panel refresh override in Hz, or 0 when Android's normal
     * current Display.Mode should be trusted.
     */
    public static float resolvePhysicalPanelRefreshRateHz(
            @NonNull Context context,
            @Nullable Display activeDisplay
    ) {
        if (activeDisplay == null || !activeDisplay.isValid()) return 0f;
        Point size = realSize(activeDisplay);
        if (size == null) return 0f;

        if (AynThorDisplayCompat.isAynThorDevice(context)
                && matches(size, THOR_BOTTOM_LONG, THOR_BOTTOM_SHORT)) {
            return 60f;
        }

        if (isAyaneoPocketDsDevice(context)
                && matches(size, POCKET_DS_SECONDARY_LONG, POCKET_DS_SECONDARY_SHORT)) {
            return 60f;
        }

        return 0f;
    }

    /** Returns true only for the AYANEO Pocket DS family, not arbitrary AYANEO devices. */
    public static boolean isAyaneoPocketDsDevice(@NonNull Context context) {
        String manufacturer = safe(Build.MANUFACTURER).toLowerCase(Locale.ROOT);
        String brand = safe(Build.BRAND).toLowerCase(Locale.ROOT);
        String identity = (safe(Build.MODEL) + " "
                + safe(Build.PRODUCT) + " "
                + safe(Build.DEVICE) + " "
                + safe(Build.HARDWARE)).toLowerCase(Locale.ROOT);

        boolean ayaneo = manufacturer.contains("ayaneo")
                || brand.contains("ayaneo")
                || identity.contains("ayaneo");
        if (!ayaneo) return false;

        if (identity.contains("pocket ds")
                || identity.contains("pocket_ds")
                || identity.contains("pocket-ds")) {
            return true;
        }

        // Firmware model strings can be terse. Only accept a generic "DS" identity
        // when the distinctive 1920x1080 + 1024x768 built-in panel pair is present.
        return containsToken(identity, "ds")
                && hasDisplaySize(context, 1920, 1080)
                && hasDisplaySize(context, POCKET_DS_SECONDARY_LONG, POCKET_DS_SECONDARY_SHORT);
    }

    private static boolean containsToken(@NonNull String text, @NonNull String token) {
        String[] parts = text.split("[^a-z0-9]+");
        for (String part : parts) {
            if (token.equals(part)) return true;
        }
        return false;
    }

    private static boolean hasDisplaySize(@NonNull Context context, int width, int height) {
        Object service = context.getSystemService(Context.DISPLAY_SERVICE);
        if (!(service instanceof DisplayManager)) return false;
        Display[] displays = ((DisplayManager) service).getDisplays();
        if (displays == null) return false;
        for (Display display : displays) {
            if (display == null || !display.isValid()) continue;
            Point size = realSize(display);
            if (size != null && matches(size, width, height)) return true;
        }
        return false;
    }

    private static boolean matches(@NonNull Point size, int expectedLong, int expectedShort) {
        int actualLong = Math.max(size.x, size.y);
        int actualShort = Math.min(size.x, size.y);
        return Math.abs(actualLong - expectedLong) <= SIZE_TOLERANCE_PX
                && Math.abs(actualShort - expectedShort) <= SIZE_TOLERANCE_PX;
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

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value;
    }
}

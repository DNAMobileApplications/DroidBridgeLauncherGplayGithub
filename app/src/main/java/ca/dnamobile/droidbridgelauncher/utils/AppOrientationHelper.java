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

package ca.dnamobile.droidbridgelauncher.utils;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;

import androidx.annotation.NonNull;

import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;

public final class AppOrientationHelper {
    private AppOrientationHelper() {
    }

    public static void applyToActivity(@NonNull Activity activity) {
        activity.setRequestedOrientation(resolveLauncherRequestedOrientation(activity));
    }

    public static void applyToGameActivity(@NonNull Activity activity) {
        activity.setRequestedOrientation(resolveGameRequestedOrientation(activity));
    }

    public static int resolveLauncherRequestedOrientation(@NonNull Context context) {
        return resolveMode(LauncherPreferences.getLauncherOrientationMode(context), false);
    }

    public static int resolveGameRequestedOrientation(@NonNull Context context) {
        return resolveMode(LauncherPreferences.getGameOrientationMode(context), true);
    }

    /**
     * Compatibility resolver for existing launcher callers.
     */
    public static int resolveRequestedOrientation(@NonNull Context context) {
        return resolveLauncherRequestedOrientation(context);
    }

    /**
     * Compatibility resolver preserving the previous boolean API.
     */
    public static int resolveRequestedOrientation(@NonNull Context context, boolean gameSurfaceOnly) {
        return gameSurfaceOnly
                ? resolveGameRequestedOrientation(context)
                : resolveLauncherRequestedOrientation(context);
    }

    private static int resolveMode(@NonNull String mode, boolean gameSurfaceOnly) {
        switch (mode) {
            case LauncherPreferences.APP_ORIENTATION_LANDSCAPE:
                return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            case LauncherPreferences.APP_ORIENTATION_REVERSE_LANDSCAPE:
                return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
            case LauncherPreferences.APP_ORIENTATION_PORTRAIT:
            case LauncherPreferences.APP_ORIENTATION_PORTRAIT_CENTERED_GAME:
                // Centered-game mode still keeps the Activity itself in portrait.
                // GameActivity constrains only MinecraftGLSurface to the centered
                // landscape-aspect rectangle.
                return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            case LauncherPreferences.APP_ORIENTATION_REVERSE_PORTRAIT:
                return ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
            case LauncherPreferences.APP_ORIENTATION_AUTO:
            default:
                // Launcher Auto follows Android's/user's rotation policy while allowing
                // all four orientations. FULL_SENSOR ignores the user's rotation lock,
                // which is not what the launcher setting named "Auto Rotate" should do.
                // Game Auto intentionally stays in landscape/reverse-landscape.
                return gameSurfaceOnly
                        ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        : ActivityInfo.SCREEN_ORIENTATION_FULL_USER;
        }
    }
}

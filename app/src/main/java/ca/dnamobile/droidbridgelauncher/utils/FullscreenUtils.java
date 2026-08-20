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
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;

public final class FullscreenUtils {
    /**
     * WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS.
     * Keep the raw value so this still compiles if your compileSdk does not expose it.
     */
    private static final int LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS_COMPAT = 3;

    private FullscreenUtils() {
    }

    public static void enableImmersive(@NonNull Activity activity) {
        enableImmersive(activity, false);
    }

    /**
     * Applies immersive mode with an optional runtime-only fullscreen requirement.
     * The override is intentionally not persisted; it is used by display-specific
     * compatibility paths such as the AYN Thor Presentation layout.
     */
    public static void enableImmersive(
            @NonNull Activity activity,
            boolean runtimeForceFullscreen
    ) {
        Window window = activity.getWindow();
        if (window == null) return;

        boolean forceFullscreen = runtimeForceFullscreen
                || LauncherPreferences.isForceFullscreenMode(activity);
        boolean ignoreDisplayCutout = LauncherPreferences.isIgnoreDisplayCutout(activity);
        boolean edgeToEdge = forceFullscreen || ignoreDisplayCutout;

        applyDisplayCutoutMode(activity, ignoreDisplayCutout, forceFullscreen);
        applyWindowFullscreenFlags(window, edgeToEdge);

        View decorView = null;
        try {
            decorView = window.getDecorView();
        } catch (Throwable ignored) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                window.setDecorFitsSystemWindows(!edgeToEdge);
            } catch (Throwable ignored) {
            }

            WindowInsetsController controller = null;
            if (decorView != null) {
                try {
                    // Do not call Window#getInsetsController() here. Some OEM builds can crash
                    // during Activity startup before PhoneWindow has installed its DecorView.
                    controller = decorView.getWindowInsetsController();
                } catch (Throwable ignored) {
                }
            }

            if (controller != null) {
                try {
                    if (edgeToEdge) {
                        controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                        controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    } else {
                        controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        if (decorView != null) {
            try {
                decorView.setFitsSystemWindows(false);
                decorView.setSystemUiVisibility(buildSystemUiFlags(edgeToEdge));
            } catch (Throwable ignored) {
            }
        }
    }

    public static void applyDisplayCutoutMode(
            @NonNull Activity activity,
            boolean ignoreDisplayCutout,
            boolean forceFullscreen
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;

        Window window = activity.getWindow();
        if (window == null) return;

        try {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = resolveDisplayCutoutMode(ignoreDisplayCutout, forceFullscreen);
            window.setAttributes(attributes);
        } catch (Throwable ignored) {
        }
    }

    private static void applyWindowFullscreenFlags(@NonNull Window window, boolean edgeToEdge) {
        try {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);

            if (edgeToEdge) {
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    window.setStatusBarColor(Color.TRANSPARENT);
                    window.setNavigationBarColor(Color.TRANSPARENT);
                }
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            }
        } catch (Throwable ignored) {
        }
    }

    private static int buildSystemUiFlags(boolean edgeToEdge) {
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (edgeToEdge) {
            flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        }
        return flags;
    }

    @RequiresApi(api = Build.VERSION_CODES.P)
    private static int resolveDisplayCutoutMode(boolean ignoreDisplayCutout, boolean forceFullscreen) {
        if (ignoreDisplayCutout) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS_COMPAT;
            }
            return WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        if (forceFullscreen) {
            return WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        return WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
    }
}

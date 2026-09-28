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

        // Force Fullscreen decides whether the game is immersive/edge-to-edge.
        // Ignore display notch is a separate *safe-area* policy: ON keeps content
        // out of the physical cutout even while the host window itself is edge-to-edge.
        boolean hideSystemBars = forceFullscreen;
        boolean layoutBehindSystemBars = forceFullscreen;

        applyDisplayCutoutMode(activity, ignoreDisplayCutout, forceFullscreen);
        applyWindowFullscreenFlags(window, hideSystemBars);

        View decorView = null;
        try {
            decorView = window.getDecorView();
        } catch (Throwable ignored) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                window.setDecorFitsSystemWindows(!layoutBehindSystemBars);
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
                    if (hideSystemBars) {
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
                decorView.setFitsSystemWindows(!layoutBehindSystemBars);
                decorView.setSystemUiVisibility(buildSystemUiFlags(
                        hideSystemBars, layoutBehindSystemBars));
            } catch (Throwable ignored) {
            }

            // A few OEMs replace PhoneWindow attributes once the decor is attached or
            // after an orientation transition. Reassert the cutout policy on the next
            // frame so "Ignore display notch" cannot silently fall back to DEFAULT.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                final boolean savedIgnoreDisplayCutout = ignoreDisplayCutout;
                final boolean savedForceFullscreen = forceFullscreen;
                try {
                    decorView.post(() -> applyDisplayCutoutMode(
                            activity, savedIgnoreDisplayCutout, savedForceFullscreen));
                } catch (Throwable ignored) {
                }
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
                // FLAG_LAYOUT_NO_LIMITS can bypass/blur OEM cutout policy and make the
                // notch toggle appear identical in both states. Standard edge-to-edge
                // layout flags + layoutInDisplayCutoutMode are sufficient here.
                window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
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

    private static int buildSystemUiFlags(
            boolean hideSystemBars,
            boolean layoutBehindSystemBars
    ) {
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (hideSystemBars) {
            flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        }
        if (layoutBehindSystemBars) {
            flags |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        }
        return flags;
    }

    @RequiresApi(api = Build.VERSION_CODES.P)
    private static int resolveDisplayCutoutMode(boolean ignoreDisplayCutout, boolean forceFullscreen) {
        // The UI label is literal: ON means ignore the notch as usable game space.
        // NEVER asks Android to keep the window's content out of the physical cutout.
        if (ignoreDisplayCutout) {
            return WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
        }

        // With notch avoidance OFF, immersive fullscreen may use the whole panel.
        // ALWAYS is required on modern Android; Android 9/10 only expose SHORT_EDGES.
        if (forceFullscreen) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS_COMPAT;
            }
            return WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        return WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
    }
}

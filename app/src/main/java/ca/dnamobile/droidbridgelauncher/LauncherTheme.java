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

package ca.dnamobile.droidbridgelauncher;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;

import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.ui.LauncherDialogStyle;

/**
 * Applies the user-selected DroidBridge launcher accent theme.
 *
 * Call before super.onCreate() so Material widgets inflate with the selected
 * color roles instead of the manifest default.
 */
public final class LauncherTheme {
    private LauncherTheme() {
    }

    public static void apply(@NonNull Activity activity) {
        applyNightMode(activity);
        activity.setTheme(getStyleRes(activity));
        LauncherDialogStyle.syncTheme(activity);
    }

    private static void applyNightMode(@NonNull Context context) {
        switch (LauncherPreferences.getLauncherTheme(context)) {
            case LauncherPreferences.LAUNCHER_THEME_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case LauncherPreferences.LAUNCHER_THEME_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }

    public static int getStyleRes(@NonNull Context context) {
        switch (LauncherPreferences.getLauncherTheme(context)) {
            case LauncherPreferences.LAUNCHER_THEME_LIGHT:
                return R.style.Theme_DroidBridge_Light;
            case LauncherPreferences.LAUNCHER_THEME_DARK:
                return R.style.Theme_DroidBridge_Dark;
            case LauncherPreferences.LAUNCHER_THEME_RED:
                return R.style.Theme_DroidBridge_Red;
            case LauncherPreferences.LAUNCHER_THEME_YELLOW:
                return R.style.Theme_DroidBridge_Yellow;
            case LauncherPreferences.LAUNCHER_THEME_GREEN:
                return R.style.Theme_DroidBridge_Green;
            case LauncherPreferences.LAUNCHER_THEME_BLUE:
                return R.style.Theme_DroidBridge_Blue;
            case LauncherPreferences.LAUNCHER_THEME_INDIGO:
                return R.style.Theme_DroidBridge_Indigo;
            case LauncherPreferences.LAUNCHER_THEME_VIOLET:
            case LauncherPreferences.LAUNCHER_THEME_PURPLE:
                return R.style.Theme_DroidBridge_Violet;
            case LauncherPreferences.LAUNCHER_THEME_PINK:
                return R.style.Theme_DroidBridge_Pink;
            case LauncherPreferences.LAUNCHER_THEME_RAINBOW:
                return R.style.Theme_DroidBridge_Rainbow;
            case LauncherPreferences.LAUNCHER_THEME_MONO:
                return R.style.Theme_DroidBridge_Mono;
            case LauncherPreferences.LAUNCHER_THEME_ORANGE:
            default:
                return R.style.Theme_DroidBridge;
        }
    }

    public static boolean isRainbow(@NonNull Context context) {
        return LauncherPreferences.LAUNCHER_THEME_RAINBOW.equals(
                LauncherPreferences.getLauncherTheme(context)
        );
    }

    public static void applyRainbowBackgroundIfNeeded(@NonNull Context context, @NonNull View root) {
        if (!isRainbow(context)) return;

        root.setBackground(createRainbowBackground());
    }

    /**
     * Applies the rainbow background to the actual activity content view.
     *
     * Some screens use setContentView(layoutRes) and some use generated binding roots.
     * Applying the gradient to android.R.id.content keeps the rainbow theme from only
     * working on settings/MainActivity while the related launcher activities keep a
     * plain colorBackground.
     */
    public static void applyRainbowBackgroundIfNeeded(@NonNull Activity activity) {
        if (!isRainbow(activity)) return;

        activity.getWindow().setBackgroundDrawable(createRainbowBackground());

        View content = activity.findViewById(android.R.id.content);
        if (content instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) content;
            if (group.getChildCount() > 0) {
                applyRainbowBackgroundIfNeeded(activity, group.getChildAt(0));
                return;
            }
        }

        if (content != null) {
            applyRainbowBackgroundIfNeeded(activity, content);
        }
    }

    @NonNull
    private static GradientDrawable createRainbowBackground() {
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{
                        0xFFFF3B30,
                        0xFFFF9500,
                        0xFFFFD60A,
                        0xFF34C759,
                        0xFF0A84FF,
                        0xFF5E5CE6,
                        0xFFBF5AF2,
                        0xFFFF2D95
                }
        );
        gradient.setCornerRadius(0f);
        return gradient;
    }

    @NonNull
    public static String getDisplayName(@NonNull String theme) {
        switch (LauncherPreferences.normalizeLauncherTheme(theme)) {
            case LauncherPreferences.LAUNCHER_THEME_LIGHT:
                return "Light";
            case LauncherPreferences.LAUNCHER_THEME_DARK:
                return "Dark";
            case LauncherPreferences.LAUNCHER_THEME_RED:
                return "Red";
            case LauncherPreferences.LAUNCHER_THEME_YELLOW:
                return "Yellow";
            case LauncherPreferences.LAUNCHER_THEME_GREEN:
                return "Green";
            case LauncherPreferences.LAUNCHER_THEME_BLUE:
                return "Blue";
            case LauncherPreferences.LAUNCHER_THEME_INDIGO:
                return "Indigo";
            case LauncherPreferences.LAUNCHER_THEME_VIOLET:
            case LauncherPreferences.LAUNCHER_THEME_PURPLE:
                return "Violet";
            case LauncherPreferences.LAUNCHER_THEME_PINK:
                return "Pink";
            case LauncherPreferences.LAUNCHER_THEME_RAINBOW:
                return "Rainbow";
            case LauncherPreferences.LAUNCHER_THEME_MONO:
                return "Monochrome";
            case LauncherPreferences.LAUNCHER_THEME_ORANGE:
            default:
                return "Orange";
        }
    }

    @NonNull
    public static String[] values() {
        return new String[]{
                LauncherPreferences.LAUNCHER_THEME_LIGHT,
                LauncherPreferences.LAUNCHER_THEME_DARK,
                LauncherPreferences.LAUNCHER_THEME_ORANGE,
                LauncherPreferences.LAUNCHER_THEME_RED,
                LauncherPreferences.LAUNCHER_THEME_YELLOW,
                LauncherPreferences.LAUNCHER_THEME_GREEN,
                LauncherPreferences.LAUNCHER_THEME_BLUE,
                LauncherPreferences.LAUNCHER_THEME_INDIGO,
                LauncherPreferences.LAUNCHER_THEME_VIOLET,
                LauncherPreferences.LAUNCHER_THEME_PINK,
                LauncherPreferences.LAUNCHER_THEME_RAINBOW,
                LauncherPreferences.LAUNCHER_THEME_MONO
        };
    }
}

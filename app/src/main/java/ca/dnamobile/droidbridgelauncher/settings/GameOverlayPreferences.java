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

package ca.dnamobile.droidbridgelauncher.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class GameOverlayPreferences {
    private static final String PREFS_NAME = "game_overlay_preferences";
    private static final boolean DEFAULT_SHOW_GAME_FPS_COUNTER = true;
    public static final String FPS_SIZE_SMALL = "small";
    public static final String FPS_SIZE_MEDIUM = "medium";
    public static final String FPS_SIZE_LARGE = "large";

    private static final String KEY_SHOW_GAME_FPS_COUNTER = "show_game_fps_counter";
    private static final String KEY_GAME_FPS_COUNTER_SIZE = "game_fps_counter_size";
    private static final String KEY_GAME_SETTINGS_BUTTON_PLACEMENT = "game_settings_button_placement";
    private static final String KEY_GAME_SETTINGS_BUTTON_CUSTOM_POSITION = "game_settings_button_custom_position";
    private static final String KEY_GAME_SETTINGS_BUTTON_CUSTOM_LEFT_DP = "game_settings_button_custom_left_dp";
    private static final String KEY_GAME_SETTINGS_BUTTON_CUSTOM_TOP_DP = "game_settings_button_custom_top_dp";

    public static final String PLACEMENT_TOP_LEFT = "top_left";
    public static final String PLACEMENT_TOP_RIGHT = "top_right";
    public static final String PLACEMENT_BOTTOM_LEFT = "bottom_left";
    public static final String PLACEMENT_BOTTOM_RIGHT = "bottom_right";

    private static final String[] PLACEMENT_LABELS = new String[]{
            "Top left",
            "Top right",
            "Bottom left",
            "Bottom right"
    };

    private static final String[] PLACEMENT_VALUES = new String[]{
            PLACEMENT_TOP_LEFT,
            PLACEMENT_TOP_RIGHT,
            PLACEMENT_BOTTOM_LEFT,
            PLACEMENT_BOTTOM_RIGHT
    };

    private static final String[] FPS_SIZE_LABELS = new String[]{
            "Small",
            "Medium",
            "Large"
    };

    private static final String[] FPS_SIZE_VALUES = new String[]{
            FPS_SIZE_SMALL,
            FPS_SIZE_MEDIUM,
            FPS_SIZE_LARGE
    };

    private GameOverlayPreferences() {
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isShowGameFpsCounter(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_SHOW_GAME_FPS_COUNTER, DEFAULT_SHOW_GAME_FPS_COUNTER);
    }

    public static void setShowGameFpsCounter(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SHOW_GAME_FPS_COUNTER, enabled).apply();
    }

    @NonNull
    public static String getGameFpsCounterSize(@NonNull Context context) {
        return sanitizeFpsSize(prefs(context).getString(KEY_GAME_FPS_COUNTER_SIZE, FPS_SIZE_SMALL));
    }

    public static void setGameFpsCounterSize(@NonNull Context context, @Nullable String size) {
        prefs(context).edit().putString(KEY_GAME_FPS_COUNTER_SIZE, sanitizeFpsSize(size)).apply();
    }

    @NonNull
    public static String[] getFpsSizeLabels() {
        return FPS_SIZE_LABELS.clone();
    }

    @NonNull
    public static String[] getFpsSizeValues() {
        return FPS_SIZE_VALUES.clone();
    }

    public static int indexOfFpsSize(@Nullable String size) {
        String safe = sanitizeFpsSize(size);
        for (int i = 0; i < FPS_SIZE_VALUES.length; i++) {
            if (FPS_SIZE_VALUES[i].equals(safe)) return i;
        }
        return 0;
    }

    @NonNull
    public static String fpsSizeValueForIndex(int index) {
        if (index < 0 || index >= FPS_SIZE_VALUES.length) return FPS_SIZE_SMALL;
        return FPS_SIZE_VALUES[index];
    }

    @NonNull
    public static String getFpsSizeLabel(@NonNull Context context) {
        return FPS_SIZE_LABELS[indexOfFpsSize(getGameFpsCounterSize(context))];
    }

    @NonNull
    public static String getGameSettingsButtonPlacement(@NonNull Context context) {
        return sanitizePlacement(prefs(context).getString(
                KEY_GAME_SETTINGS_BUTTON_PLACEMENT,
                PLACEMENT_BOTTOM_RIGHT
        ));
    }

    public static void setGameSettingsButtonPlacement(@NonNull Context context, @Nullable String placement) {
        prefs(context).edit()
                .putString(KEY_GAME_SETTINGS_BUTTON_PLACEMENT, sanitizePlacement(placement))
                .putBoolean(KEY_GAME_SETTINGS_BUTTON_CUSTOM_POSITION, false)
                .apply();
    }

    public static boolean hasCustomGameSettingsButtonPosition(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_GAME_SETTINGS_BUTTON_CUSTOM_POSITION, false);
    }

    public static int getGameSettingsButtonCustomLeftDp(@NonNull Context context) {
        return prefs(context).getInt(KEY_GAME_SETTINGS_BUTTON_CUSTOM_LEFT_DP, 16);
    }

    public static int getGameSettingsButtonCustomTopDp(@NonNull Context context) {
        return prefs(context).getInt(KEY_GAME_SETTINGS_BUTTON_CUSTOM_TOP_DP, 16);
    }

    public static void setGameSettingsButtonCustomPosition(@NonNull Context context, int leftDp, int topDp) {
        prefs(context).edit()
                .putBoolean(KEY_GAME_SETTINGS_BUTTON_CUSTOM_POSITION, true)
                .putInt(KEY_GAME_SETTINGS_BUTTON_CUSTOM_LEFT_DP, Math.max(0, leftDp))
                .putInt(KEY_GAME_SETTINGS_BUTTON_CUSTOM_TOP_DP, Math.max(0, topDp))
                .apply();
    }

    public static void resetGameSettingsButtonCustomPosition(@NonNull Context context) {
        prefs(context).edit()
                .putBoolean(KEY_GAME_SETTINGS_BUTTON_CUSTOM_POSITION, false)
                .remove(KEY_GAME_SETTINGS_BUTTON_CUSTOM_LEFT_DP)
                .remove(KEY_GAME_SETTINGS_BUTTON_CUSTOM_TOP_DP)
                .apply();
    }

    @NonNull
    public static String[] getPlacementLabels() {
        return PLACEMENT_LABELS.clone();
    }

    @NonNull
    public static String[] getPlacementValues() {
        return PLACEMENT_VALUES.clone();
    }

    public static int indexOfPlacement(@Nullable String placement) {
        String safe = sanitizePlacement(placement);
        for (int i = 0; i < PLACEMENT_VALUES.length; i++) {
            if (PLACEMENT_VALUES[i].equals(safe)) return i;
        }
        return PLACEMENT_VALUES.length - 1;
    }

    @NonNull
    public static String placementValueForIndex(int index) {
        if (index < 0 || index >= PLACEMENT_VALUES.length) return PLACEMENT_BOTTOM_RIGHT;
        return PLACEMENT_VALUES[index];
    }

    @NonNull
    private static String sanitizeFpsSize(@Nullable String size) {
        if (FPS_SIZE_MEDIUM.equals(size) || FPS_SIZE_LARGE.equals(size)) {
            return size;
        }
        return FPS_SIZE_SMALL;
    }

    @NonNull
    private static String sanitizePlacement(@Nullable String placement) {
        if (PLACEMENT_TOP_LEFT.equals(placement)
                || PLACEMENT_TOP_RIGHT.equals(placement)
                || PLACEMENT_BOTTOM_LEFT.equals(placement)
                || PLACEMENT_BOTTOM_RIGHT.equals(placement)) {
            return placement;
        }
        return PLACEMENT_BOTTOM_RIGHT;
    }
}

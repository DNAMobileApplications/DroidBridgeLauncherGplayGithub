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
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

import ca.dnamobile.droidbridgelauncher.dualscreen.AynThorDisplayCompat;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

public final class LauncherPreferences {
    private static final String PREFS_NAME = "launcher_preferences";
    private static final String RESOLUTION_PREFS_NAME = "launcher_resolution_preferences";
    private static final String KEY_USE_NATIVE_SURFACE_VIEW = "use_native_surface_view";
    private static final String KEY_SHOW_SHARED_INSTALLS = "show_shared_installs";
    private static final String KEY_QUICK_PLAY_LAST_WORLD_FROM_GRID = "quick_play_last_world_from_grid";
    private static final String KEY_GRID_PLAY_ICON_MODE = "grid_play_icon_mode";
    private static final String KEY_SELECTED_INSTANCE_FILTER = "selected_instance_filter";
    private static final String KEY_RECENT_INSTANCE_PREFIX = "recent_instance_last_played_";
    private static final String KEY_FAVORITE_INSTANCE_PREFIX = "favorite_instance_";
    private static final String KEY_REMOVE_INHERITED_VANILLA_AFTER_LOADER_INSTALL = "remove_inherited_vanilla_after_loader_install";
    private static final String KEY_SELECTED_RENDERER_IDENTIFIER = "selected_renderer_identifier";
    private static final String KEY_SELECTED_VULKAN_DRIVER_NAME = "selected_vulkan_driver_name";
    private static final String KEY_USE_SYSTEM_VULKAN_DRIVER = "use_system_vulkan_driver";
    private static final String KEY_ENABLE_VULKAN_VSYNC = "enable_vulkan_vsync";
    private static final String KEY_USE_OPENGL_FOR_MC_26_PLUS = "use_opengl_for_mc_26_plus";
    private static final String KEY_SHOW_GAME_LOG_OVERLAY = "show_game_log_overlay";
    private static final String KEY_SHARE_LOG_CHOOSER_ENABLED = "share_log_chooser_enabled";
    private static final String KEY_SHARE_LOG_CHOOSER_INITIALIZED_V2 = "share_log_chooser_initialized_v2";
    private static final String KEY_SHOW_IN_GAME_SETTINGS_BUTTON = "show_in_game_settings_button";
    private static final String KEY_DUAL_SCREEN_SUPPORT_ENABLED = "dual_screen_support_enabled";
    private static final String KEY_DUAL_SCREEN_LAST_EXTERNAL_REQUEST = "dual_screen_last_external_request";
    private static final String KEY_DUAL_SCREEN_LAST_SWAP_REQUEST = "dual_screen_last_swap_request";
    private static final String KEY_DUAL_SCREEN_HUD_LAYOUT = "dual_screen_hud_layout";
    private static final String KEY_DUAL_SCREEN_ASPECT_RATIO = "dual_screen_aspect_ratio";
    private static final String KEY_DUAL_SCREEN_FPS_ENABLED = "dual_screen_fps_enabled";
    private static final String KEY_THOR_DUAL_SCREEN_DEFAULT_APPLIED = "thor_dual_screen_default_applied_v3";
    private static final String SHARED_DUAL_SCREEN_STATE_FILE = "droidbridge_dual_screen_preferences.properties";
    private static final String DUAL_SCREEN_BACKGROUND_DIRECTORY = "dual_screen";
    private static final String DUAL_SCREEN_BACKGROUND_FILE = "controls_background.img";
    private static final String DUAL_SCREEN_MAP_FRAME_FILE = "map_frame_custom.img";
    private static final String SHARED_DUAL_SCREEN_ENABLED = "enabled";
    private static final String SHARED_DUAL_SCREEN_EXTERNAL_REQUEST = "externalRequested";
    private static final String SHARED_DUAL_SCREEN_SWAPPED = "swapped";
    private static final Object DUAL_SCREEN_STATE_LOCK = new Object();
    private static final String KEY_ANDROID_BACK_OPENS_IN_GAME_MENU = "android_back_opens_in_game_menu";
    private static final String KEY_ANDROID_BACK_BUTTON_ACTION = "android_back_button_action";
    private static final String KEY_IN_GAME_MENU_KEYBOARD_SHORTCUT = "in_game_menu_keyboard_shortcut_keycode";
    private static final String KEY_IN_GAME_MENU_KEYBOARD_SHORTCUT_MODIFIERS = "in_game_menu_keyboard_shortcut_modifiers";
    private static final String KEY_PHYSICAL_MOUSE_MODE = "physical_mouse_mode";
    public static final String PHYSICAL_MOUSE_MODE_NATIVE = "native";
    public static final String PHYSICAL_MOUSE_MODE_ANDROID_VIRTUAL = "android_virtual";
    private static final int DEFAULT_IN_GAME_MENU_KEYBOARD_SHORTCUT_KEYCODE = KeyEvent.KEYCODE_ESCAPE;
    private static final int DEFAULT_IN_GAME_MENU_KEYBOARD_SHORTCUT_MODIFIERS = KeyEvent.META_CTRL_ON;
    // Legacy key used before launcher and game orientation were separated.
    private static final String KEY_APP_ORIENTATION_MODE = "app_orientation_mode";
    private static final String KEY_LAUNCHER_ORIENTATION_MODE = "launcher_orientation_mode";
    private static final String KEY_GAME_ORIENTATION_MODE = "game_orientation_mode";
    private static final String KEY_ENABLE_SDL_CONTROLLER_MOD_COMPAT = "enable_sdl_controller_mod_compat";
    private static final String KEY_SHOW_CONTROLLER_MOD_COMPAT_WARNINGS = "show_controller_mod_compat_warnings";
    private static final String KEY_FORCE_SDL_CONTROLLER_BRIDGE = "force_sdl_controller_bridge";
    private static final String KEY_ALLOCATED_MEMORY_MB = "allocated_memory_mb";
    private static final String KEY_RAM_UNLOCKED = "ram_unlocked";
    private static final String KEY_GAME_RESOLUTION_SCALE_PERCENT = "game_resolution_scale_percent";
    private static final String KEY_FORCE_FULLSCREEN_MODE = "force_fullscreen_mode";
    private static final String KEY_AVOID_ROUNDED_DISPLAY_CORNERS = "avoid_rounded_display_corners";
    private static final String KEY_IGNORE_DISPLAY_CUTOUT = "ignore_display_cutout";
    private static final String KEY_IME_VIEWPORT_PUSH_ENABLED = "ime_viewport_push_enabled";
    private static final String KEY_LAUNCHER_THEME = "launcher_theme";
    private static final String KEY_USE_BMCLAPI = "use_bmclapi_download_source";
    private static final String KEY_ENABLE_SUSTAINED_PERFORMANCE = "enable_sustained_performance";

    public static final int MIN_GAME_RESOLUTION_SCALE_PERCENT = 25;
    public static final int MAX_GAME_RESOLUTION_SCALE_PERCENT = 200;
    public static final int DEFAULT_GAME_RESOLUTION_SCALE_PERCENT = 100;

    public static final String APP_ORIENTATION_AUTO = "auto";
    public static final String APP_ORIENTATION_LANDSCAPE = "landscape";
    public static final String APP_ORIENTATION_REVERSE_LANDSCAPE = "reverse_landscape";
    public static final String APP_ORIENTATION_PORTRAIT = "portrait";
    public static final String APP_ORIENTATION_REVERSE_PORTRAIT = "reverse_portrait";
    public static final String APP_ORIENTATION_PORTRAIT_CENTERED_GAME = "portrait_centered_game";

    public static final String GRID_PLAY_ICON_MODE_REGULAR = "regular";
    public static final String GRID_PLAY_ICON_MODE_LAST_WORLD = "last_world";
    public static final String GRID_PLAY_ICON_MODE_SERVER = "server";

    public static final String ANDROID_BACK_ACTION_PAUSE_GAME = "pause_game";
    public static final String ANDROID_BACK_ACTION_LAUNCHER_MENU = "launcher_menu";
    public static final String ANDROID_BACK_ACTION_DISABLED = "disabled";

    public static final String DUAL_SCREEN_HUD_LAYOUT_MODERN = "modern";
    public static final String DUAL_SCREEN_HUD_LAYOUT_LEGACY = "legacy";
    public static final String DUAL_SCREEN_HUD_LAYOUT_3DS = "3ds";
    public static final String DUAL_SCREEN_ASPECT_RATIO_16_9 = "16:9";
    public static final String DUAL_SCREEN_ASPECT_RATIO_4_3 = "4:3";

    private static final String DEFAULT_RENDERER_IDENTIFIER = "e7b90ed6-e518-4d4e-93dc-5c7133cd5b31";
    private static final String DEFAULT_VULKAN_DRIVER_NAME = "Default Mesa driver";


    public static final String LAUNCHER_THEME_LIGHT = "light";
    public static final String LAUNCHER_THEME_DARK = "dark";
    public static final String LAUNCHER_THEME_ORANGE = "orange";
    public static final String LAUNCHER_THEME_RED = "red";
    public static final String LAUNCHER_THEME_YELLOW = "yellow";
    public static final String LAUNCHER_THEME_GREEN = "green";
    public static final String LAUNCHER_THEME_BLUE = "blue";
    public static final String LAUNCHER_THEME_INDIGO = "indigo";
    public static final String LAUNCHER_THEME_VIOLET = "violet";
    public static final String LAUNCHER_THEME_PINK = "pink";
    public static final String LAUNCHER_THEME_RAINBOW = "rainbow";
    public static final String LAUNCHER_THEME_PURPLE = "purple";
    public static final String LAUNCHER_THEME_MONO = "mono";

    @NonNull
    public static String getLauncherTheme(@NonNull Context context) {
        String value = prefs(context).getString(KEY_LAUNCHER_THEME, LAUNCHER_THEME_ORANGE);
        return normalizeLauncherTheme(value);
    }

    public static void setLauncherTheme(@NonNull Context context, @NonNull String theme) {
        prefs(context).edit()
                .putString(KEY_LAUNCHER_THEME, normalizeLauncherTheme(theme))
                .commit();
    }

    @NonNull
    public static String normalizeLauncherTheme(@Nullable String theme) {
        if (theme == null) return LAUNCHER_THEME_ORANGE;
        String value = theme.trim().toLowerCase(java.util.Locale.ROOT);
        if (LAUNCHER_THEME_LIGHT.equals(value)
                || LAUNCHER_THEME_DARK.equals(value)
                || LAUNCHER_THEME_ORANGE.equals(value)
                || LAUNCHER_THEME_RED.equals(value)
                || LAUNCHER_THEME_YELLOW.equals(value)
                || LAUNCHER_THEME_GREEN.equals(value)
                || LAUNCHER_THEME_BLUE.equals(value)
                || LAUNCHER_THEME_INDIGO.equals(value)
                || LAUNCHER_THEME_VIOLET.equals(value)
                || LAUNCHER_THEME_PINK.equals(value)
                || LAUNCHER_THEME_RAINBOW.equals(value)
                || LAUNCHER_THEME_PURPLE.equals(value)
                || LAUNCHER_THEME_MONO.equals(value)) {
            return value;
        }
        return LAUNCHER_THEME_ORANGE;
    }

    private LauncherPreferences() {
    }

    @SuppressWarnings("deprecation")
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS
        );
    }

    @SuppressWarnings("deprecation")
    private static SharedPreferences resolutionPrefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(
                RESOLUTION_PREFS_NAME,
                Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS
        );
    }

    private static void saveBoolean(@NonNull Context context, @NonNull String key, boolean enabled) {
        boolean saved = prefs(context).edit().putBoolean(key, enabled).commit();
        if (!saved) {
            prefs(context).edit().putBoolean(key, enabled).apply();
        }
    }

    /**
     * false = TextureView / SurfaceTexture path
     * true  = native Android SurfaceView / SurfaceHolder path
     *
     * Keep TextureView as the default because it is the current known-working path.
     */
    public static boolean isUseNativeSurfaceView(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_USE_NATIVE_SURFACE_VIEW, false);
    }

    public static void setUseNativeSurfaceView(@NonNull Context context, boolean enabled) {
        saveBoolean(context, KEY_USE_NATIVE_SURFACE_VIEW, enabled);
    }

    /**
     * Requests Android sustained performance mode for the game window on devices
     * that implement the API. Disabled by default because the mode intentionally
     * trades short peak performance for steadier long-session performance.
     */
    public static boolean isSustainedPerformanceEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_ENABLE_SUSTAINED_PERFORMANCE, false);
    }

    public static void setSustainedPerformanceEnabled(@NonNull Context context, boolean enabled) {
        saveBoolean(context, KEY_ENABLE_SUSTAINED_PERFORMANCE, enabled);
    }

    /**
     * Uses BMCLAPI as the preferred source for supported Minecraft, Fabric,
     * Forge, and NeoForge download URLs. The original source remains available
     * as an automatic fallback and existing hash verification still applies.
     */
    public static boolean isUseBmclApi(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_USE_BMCLAPI, false);
    }

    public static void setUseBmclApi(@NonNull Context context, boolean enabled) {
        saveBoolean(context, KEY_USE_BMCLAPI, enabled);
    }

    /**
     * When true, old/global installs under .minecraft/versions appear beside isolated
     * instances as "Shared" entries.
     */
    public static boolean isShowSharedInstalls(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_SHOW_SHARED_INSTALLS, false);
    }

    public static void setShowSharedInstalls(@NonNull Context context, boolean enabled) {
        saveBoolean(context, KEY_SHOW_SHARED_INSTALLS, enabled);
    }

    /**
     * Controls what the small play icon on an instance card does.
     * regular    = normal launch
     * last_world = ask which singleplayer save to launch with Minecraft 1.20+ Quick Play
     * server     = open the instance server picker, then Quick Play the selected server
     */
    @NonNull
    public static String getGridPlayIconMode(@NonNull Context context) {
        String value = prefs(context).getString(KEY_GRID_PLAY_ICON_MODE, null);
        if (value == null) {
            // Migrate the old toggle forward without breaking existing users.
            return prefs(context).getBoolean(KEY_QUICK_PLAY_LAST_WORLD_FROM_GRID, false)
                    ? GRID_PLAY_ICON_MODE_LAST_WORLD
                    : GRID_PLAY_ICON_MODE_REGULAR;
        }
        return normalizeGridPlayIconMode(value);
    }

    public static void setGridPlayIconMode(@NonNull Context context, @NonNull String mode) {
        String normalized = normalizeGridPlayIconMode(mode);
        prefs(context).edit()
                .putString(KEY_GRID_PLAY_ICON_MODE, normalized)
                .putBoolean(KEY_QUICK_PLAY_LAST_WORLD_FROM_GRID, GRID_PLAY_ICON_MODE_LAST_WORLD.equals(normalized))
                .commit();
    }

    @NonNull
    public static String normalizeGridPlayIconMode(@Nullable String mode) {
        if (mode == null) return GRID_PLAY_ICON_MODE_REGULAR;
        String value = mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (GRID_PLAY_ICON_MODE_LAST_WORLD.equals(value) || GRID_PLAY_ICON_MODE_SERVER.equals(value)) {
            return value;
        }
        return GRID_PLAY_ICON_MODE_REGULAR;
    }

    /**
     * Legacy callers should treat this as a view over the new three-way mode.
     */
    public static boolean isQuickPlayLastWorldFromGridEnabled(@NonNull Context context) {
        return GRID_PLAY_ICON_MODE_LAST_WORLD.equals(getGridPlayIconMode(context));
    }

    public static void setQuickPlayLastWorldFromGridEnabled(@NonNull Context context, boolean enabled) {
        setGridPlayIconMode(context, enabled ? GRID_PLAY_ICON_MODE_LAST_WORLD : GRID_PLAY_ICON_MODE_REGULAR);
    }

    @NonNull
    public static String getSelectedInstanceFilter(@NonNull Context context, @NonNull String fallback) {
        String value = prefs(context).getString(KEY_SELECTED_INSTANCE_FILTER, fallback);
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    public static void setSelectedInstanceFilter(@NonNull Context context, @NonNull String filter) {
        prefs(context).edit().putString(KEY_SELECTED_INSTANCE_FILTER, filter).commit();
    }

    public static void recordInstancePlayed(@NonNull Context context, @NonNull String instanceId) {
        if (instanceId.trim().isEmpty()) return;
        prefs(context).edit()
                .putLong(KEY_RECENT_INSTANCE_PREFIX + instanceId, System.currentTimeMillis())
                .apply();
    }

    public static long getInstanceLastPlayed(@NonNull Context context, @NonNull String instanceId) {
        if (instanceId.trim().isEmpty()) return 0L;
        return prefs(context).getLong(KEY_RECENT_INSTANCE_PREFIX + instanceId, 0L);
    }

    public static void clearInstancePlayed(@NonNull Context context, @NonNull String instanceId) {
        if (instanceId.trim().isEmpty()) return;
        prefs(context).edit().remove(KEY_RECENT_INSTANCE_PREFIX + instanceId).apply();
    }

    public static boolean isInstanceFavorite(@NonNull Context context, @NonNull String instanceId) {
        if (instanceId.trim().isEmpty()) return false;
        return prefs(context).getBoolean(KEY_FAVORITE_INSTANCE_PREFIX + instanceId, false);
    }

    public static void setInstanceFavorite(@NonNull Context context, @NonNull String instanceId, boolean favorite) {
        if (instanceId.trim().isEmpty()) return;
        SharedPreferences.Editor editor = prefs(context).edit();
        if (favorite) {
            editor.putBoolean(KEY_FAVORITE_INSTANCE_PREFIX + instanceId, true);
        } else {
            editor.remove(KEY_FAVORITE_INSTANCE_PREFIX + instanceId);
        }
        editor.apply();
    }

    public static void clearInstanceFavorite(@NonNull Context context, @NonNull String instanceId) {
        setInstanceFavorite(context, instanceId, false);
    }

    /**
     * When enabled, loader installs are flattened after installation so the
     * matching vanilla version folder can be removed if no other profile still
     * inherits from it. Disabled by default because it permanently deletes the
     * shared vanilla version folder when safe.
     */
    public static boolean isRemoveInheritedVanillaAfterLoaderInstall(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_REMOVE_INHERITED_VANILLA_AFTER_LOADER_INSTALL, false);
    }

    public static void setRemoveInheritedVanillaAfterLoaderInstall(@NonNull Context context, boolean enabled) {
        saveBoolean(context, KEY_REMOVE_INHERITED_VANILLA_AFTER_LOADER_INSTALL, enabled);
    }

    @NonNull
    public static String getSelectedRendererIdentifier(@NonNull Context context) {
        String value = prefs(context).getString(KEY_SELECTED_RENDERER_IDENTIFIER, DEFAULT_RENDERER_IDENTIFIER);
        return value == null || value.trim().isEmpty() ? DEFAULT_RENDERER_IDENTIFIER : value;
    }

    public static void setSelectedRendererIdentifier(@NonNull Context context, @NonNull String rendererIdentifier) {
        prefs(context).edit().putString(KEY_SELECTED_RENDERER_IDENTIFIER, rendererIdentifier).commit();
    }

    @NonNull
    public static String getSelectedVulkanDriverName(@NonNull Context context) {
        String value = prefs(context).getString(KEY_SELECTED_VULKAN_DRIVER_NAME, DEFAULT_VULKAN_DRIVER_NAME);
        return value == null || value.trim().isEmpty() ? DEFAULT_VULKAN_DRIVER_NAME : value;
    }

    public static void setSelectedVulkanDriverName(@NonNull Context context, @NonNull String driverName) {
        prefs(context).edit().putString(KEY_SELECTED_VULKAN_DRIVER_NAME, driverName).commit();
    }

    /**
     * When enabled, launcher/Vulkan bridge code avoids custom Turnip/Adreno
     * DRIVER_PATH/VK_ICD values and lets Android's system Vulkan loader handle
     * Vulkan. This is global because Vulkan mods can use Vulkan even when the
     * selected OpenGL renderer is not Vulkan Zink.
     */
    public static boolean isUseSystemVulkanDriver(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_USE_SYSTEM_VULKAN_DRIVER, false);
    }

    public static void setUseSystemVulkanDriver(@NonNull Context context, boolean enabled) {
        // Keep every caller on the same atomic, mutually-exclusive mode update.
        setSystemVulkanMode(context, enabled);
    }

    public static boolean isVulkanVsyncEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_ENABLE_VULKAN_VSYNC, true);
    }

    public static void setVulkanVsyncEnabled(@NonNull Context context, boolean enabled) {
        saveBoolean(context, KEY_ENABLE_VULKAN_VSYNC, enabled);
    }

    /**
     * System Vulkan and forced OpenGL are mutually exclusive. For vanilla 26.2+,
     * GraphicsBackendHelper maps these launcher modes directly to options.txt.
     * Save both in one synchronous editor so launch code cannot read stale or
     * contradictory values from another process.
     */
    public static void setSystemVulkanMode(@NonNull Context context, boolean enabled) {
        SharedPreferences.Editor editor = prefs(context).edit()
                .putBoolean(KEY_USE_SYSTEM_VULKAN_DRIVER, enabled);
        if (enabled) {
            editor.putBoolean(KEY_USE_OPENGL_FOR_MC_26_PLUS, false);
        }
        boolean saved = editor.commit();
        if (!saved) {
            SharedPreferences.Editor fallback = prefs(context).edit()
                    .putBoolean(KEY_USE_SYSTEM_VULKAN_DRIVER, enabled);
            if (enabled) {
                fallback.putBoolean(KEY_USE_OPENGL_FOR_MC_26_PLUS, false);
            }
            fallback.apply();
        }
    }

    /**
     * Launcher preference for forcing OpenGL. The options.txt rewrite is intentionally
     * applied to vanilla Minecraft 26.2+; VulkanMod 26.2 is left untouched.
     */
    public static boolean isUseOpenGlForMinecraft26Plus(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_USE_OPENGL_FOR_MC_26_PLUS, false);
    }

    public static void setUseOpenGlForMinecraft26Plus(@NonNull Context context, boolean enabled) {
        SharedPreferences.Editor editor = prefs(context).edit()
                .putBoolean(KEY_USE_OPENGL_FOR_MC_26_PLUS, enabled);
        if (enabled) {
            editor.putBoolean(KEY_USE_SYSTEM_VULKAN_DRIVER, false);
        }
        boolean saved = editor.commit();
        if (!saved) {
            SharedPreferences.Editor fallback = prefs(context).edit()
                    .putBoolean(KEY_USE_OPENGL_FOR_MC_26_PLUS, enabled);
            if (enabled) {
                fallback.putBoolean(KEY_USE_SYSTEM_VULKAN_DRIVER, false);
            }
            fallback.apply();
        }
    }

    /**
     * Shows a small read-only latest-log overlay on the left side of GameActivity.
     * Disabled by default so normal gameplay remains clean.
     */
    public static boolean isShowGameLogOverlay(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_SHOW_GAME_LOG_OVERLAY, false);
    }

    public static void setShowGameLogOverlay(@NonNull Context context, boolean enabled) {
        saveBoolean(context, KEY_SHOW_GAME_LOG_OVERLAY, enabled);
    }

    /**
     * Controls the main launcher's Share Logs chooser. Enabled by default so users can
     * choose between the Minecraft latestlog and DroidBridge launcher diagnostics.
     * When disabled, the main Share Logs action immediately shares latestlog.txt.
     */
    public static boolean isShareLogChooserEnabled(@NonNull Context context) {
        SharedPreferences preferences = prefs(context);

        /*
         * v2 migration:
         * The first Share Logs chooser implementation could leave this preference
         * stored as false before the chooser flow was actually usable. Force the
         * corrected chooser ON exactly once, then respect the user's setting from
         * that point forward.
         */
        if (!preferences.getBoolean(KEY_SHARE_LOG_CHOOSER_INITIALIZED_V2, false)) {
            boolean saved = preferences.edit()
                    .putBoolean(KEY_SHARE_LOG_CHOOSER_ENABLED, true)
                    .putBoolean(KEY_SHARE_LOG_CHOOSER_INITIALIZED_V2, true)
                    .commit();
            if (!saved) {
                preferences.edit()
                        .putBoolean(KEY_SHARE_LOG_CHOOSER_ENABLED, true)
                        .putBoolean(KEY_SHARE_LOG_CHOOSER_INITIALIZED_V2, true)
                        .apply();
            }
            return true;
        }

        return preferences.getBoolean(KEY_SHARE_LOG_CHOOSER_ENABLED, true);
    }

    public static void setShareLogChooserEnabled(@NonNull Context context, boolean enabled) {
        SharedPreferences preferences = prefs(context);
        boolean saved = preferences.edit()
                .putBoolean(KEY_SHARE_LOG_CHOOSER_ENABLED, enabled)
                .putBoolean(KEY_SHARE_LOG_CHOOSER_INITIALIZED_V2, true)
                .commit();
        if (!saved) {
            preferences.edit()
                    .putBoolean(KEY_SHARE_LOG_CHOOSER_ENABLED, enabled)
                    .putBoolean(KEY_SHARE_LOG_CHOOSER_INITIALIZED_V2, true)
                    .apply();
        }
    }

    /**
     * Shows the small floating in-game settings button that opens the controller/button
     * overlay while Minecraft is running. Enabled by default so users can still reach
     * the overlay without relying on Android's Back button.
     */
    public static boolean isShowInGameSettingsButton(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_SHOW_IN_GAME_SETTINGS_BUTTON, true);
    }

    public static void setShowInGameSettingsButton(@NonNull Context context, boolean enabled) {
        saveBoolean(context, KEY_SHOW_IN_GAME_SETTINGS_BUTTON, enabled);
    }

    /**
     * The custom controls/HUD background is intentionally package-local. Android
     * document URI grants are package-specific, so DroidBridge copies the selected
     * image into its own files directory and can keep using it after reboot or if the
     * original picker grant changes.
     */
    @NonNull
    public static File getDualScreenBackgroundImageFile(@NonNull Context context) {
        File directory = new File(context.getApplicationContext().getFilesDir(), DUAL_SCREEN_BACKGROUND_DIRECTORY);
        return new File(directory, DUAL_SCREEN_BACKGROUND_FILE);
    }

    public static boolean hasDualScreenBackgroundImage(@NonNull Context context) {
        File file = getDualScreenBackgroundImageFile(context);
        return file.isFile() && file.length() > 0L;
    }

    public static void clearDualScreenBackgroundImage(@NonNull Context context) {
        File file = getDualScreenBackgroundImageFile(context);
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    /**
     * Optional user-selected texture for the exploration-map frame. The map is drawn by
     * DroidBridge rather than Fabric, so texture choice belongs to the launcher and works
     * identically across Minecraft versions.
     */
    @NonNull
    public static File getDualScreenMapFrameImageFile(@NonNull Context context) {
        File directory = new File(context.getApplicationContext().getFilesDir(), DUAL_SCREEN_BACKGROUND_DIRECTORY);
        return new File(directory, DUAL_SCREEN_MAP_FRAME_FILE);
    }

    public static boolean hasDualScreenMapFrameImage(@NonNull Context context) {
        File file = getDualScreenMapFrameImageFile(context);
        return file.isFile() && file.length() > 0L;
    }

    /**
     * Bottom-screen HUD presentation. This is deliberately launcher-owned and version-neutral:
     * Fabric/Forge companions only provide state, while Android decides how to arrange it.
     * Keeping the choice here means the same Modern/Legacy/3DS layouts can be reused when the
     * compatibility range expands to older Minecraft versions.
     */
    @NonNull
    public static String getDualScreenHudLayout(@NonNull Context context) {
        String raw = prefs(context).getString(KEY_DUAL_SCREEN_HUD_LAYOUT, DUAL_SCREEN_HUD_LAYOUT_MODERN);
        String normalized = raw == null ? "" : raw.trim();
        if (DUAL_SCREEN_HUD_LAYOUT_LEGACY.equalsIgnoreCase(normalized)) {
            return DUAL_SCREEN_HUD_LAYOUT_LEGACY;
        }
        if (DUAL_SCREEN_HUD_LAYOUT_3DS.equalsIgnoreCase(normalized)) {
            return DUAL_SCREEN_HUD_LAYOUT_3DS;
        }
        return DUAL_SCREEN_HUD_LAYOUT_MODERN;
    }

    public static boolean isDualScreenLegacyHudLayout(@NonNull Context context) {
        return DUAL_SCREEN_HUD_LAYOUT_LEGACY.equals(getDualScreenHudLayout(context));
    }

    public static boolean isDualScreen3dsHudLayout(@NonNull Context context) {
        return DUAL_SCREEN_HUD_LAYOUT_3DS.equals(getDualScreenHudLayout(context));
    }

    public static void setDualScreenHudLayout(@NonNull Context context, @NonNull String layout) {
        String raw = layout.trim();
        String normalized;
        if (DUAL_SCREEN_HUD_LAYOUT_LEGACY.equalsIgnoreCase(raw)) {
            normalized = DUAL_SCREEN_HUD_LAYOUT_LEGACY;
        } else if (DUAL_SCREEN_HUD_LAYOUT_3DS.equalsIgnoreCase(raw)) {
            normalized = DUAL_SCREEN_HUD_LAYOUT_3DS;
        } else {
            normalized = DUAL_SCREEN_HUD_LAYOUT_MODERN;
        }
        prefs(context).edit().putString(KEY_DUAL_SCREEN_HUD_LAYOUT, normalized).apply();
    }

    /**
     * Launcher-owned safe-area aspect for the lower-screen HUD/controls. This does not
     * resize Minecraft or the physical panel; it simply composes the lower-screen UI inside
     * either a centered 16:9 or 4:3 viewport so both shapes remain intentional and unstretched.
     */
    @NonNull
    public static String getDualScreenAspectRatio(@NonNull Context context) {
        String raw = prefs(context).getString(KEY_DUAL_SCREEN_ASPECT_RATIO, DUAL_SCREEN_ASPECT_RATIO_16_9);
        return DUAL_SCREEN_ASPECT_RATIO_4_3.equals(raw)
                ? DUAL_SCREEN_ASPECT_RATIO_4_3
                : DUAL_SCREEN_ASPECT_RATIO_16_9;
    }

    public static boolean isDualScreenFourThreeLayout(@NonNull Context context) {
        return DUAL_SCREEN_ASPECT_RATIO_4_3.equals(getDualScreenAspectRatio(context));
    }

    public static void setDualScreenAspectRatio(@NonNull Context context, @NonNull String aspectRatio) {
        String normalized = DUAL_SCREEN_ASPECT_RATIO_4_3.equals(aspectRatio)
                ? DUAL_SCREEN_ASPECT_RATIO_4_3
                : DUAL_SCREEN_ASPECT_RATIO_16_9;
        prefs(context).edit().putString(KEY_DUAL_SCREEN_ASPECT_RATIO, normalized).apply();
    }

    public static boolean isDualScreenFpsEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_DUAL_SCREEN_FPS_ENABLED, false);
    }

    public static void setDualScreenFpsEnabled(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DUAL_SCREEN_FPS_ENABLED, enabled).apply();
    }

    public static void clearDualScreenMapFrameImage(@NonNull Context context) {
        File file = getDualScreenMapFrameImageFile(context);
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    /**
     * Enables DroidBridge dual-screen support. The package-local preference is mirrored
     * into the selected launcher home's .minecraft folder so the GitHub and Google Play
     * packages use the same dual-screen state instead of silently diverging.
     */
    public static boolean isDualScreenSupportEnabled(@NonNull Context context) {
        synchronizeDualScreenState(context);
        applyAynThorDualScreenDefaultIfNeeded(context);
        return prefs(context).getBoolean(KEY_DUAL_SCREEN_SUPPORT_ENABLED, false);
    }

    public static void setDualScreenSupportEnabled(@NonNull Context context, boolean enabled) {
        SharedPreferences local = prefs(context);
        SharedPreferences.Editor editor = local.edit()
                .putBoolean(KEY_DUAL_SCREEN_SUPPORT_ENABLED, enabled)
                .putBoolean(KEY_THOR_DUAL_SCREEN_DEFAULT_APPLIED, true);
        // Turning support on is an explicit request to use the dual-screen layout.
        // Keep the saved swap direction, but ensure runtime restoration is enabled.
        if (enabled) {
            editor.putBoolean(KEY_DUAL_SCREEN_LAST_EXTERNAL_REQUEST, true);
        }
        boolean saved = editor.commit();
        if (!saved) {
            SharedPreferences.Editor fallback = local.edit()
                    .putBoolean(KEY_DUAL_SCREEN_SUPPORT_ENABLED, enabled)
                    .putBoolean(KEY_THOR_DUAL_SCREEN_DEFAULT_APPLIED, true);
            if (enabled) {
                fallback.putBoolean(KEY_DUAL_SCREEN_LAST_EXTERNAL_REQUEST, true);
            }
            fallback.apply();
        }
        persistDualScreenState(context);
    }

    /**
     * Thor is a purpose-built dual-screen handheld, so make the useful physical layout
     * the first-run default exactly once: upper panel = Minecraft, lower panel = controls.
     * After this marker is written, an explicit user Off choice is always respected.
     */
    private static void applyAynThorDualScreenDefaultIfNeeded(@NonNull Context context) {
        if (!AynThorDisplayCompat.isAynThorDevice(context)) return;

        SharedPreferences local = prefs(context);
        if (local.getBoolean(KEY_THOR_DUAL_SCREEN_DEFAULT_APPLIED, false)) return;

        // Never overwrite a real choice from an older DroidBridge build (or from the
        // sister GitHub/Google Play package). Only manufacture the Thor default when
        // no dual-screen preference has ever existed.
        boolean hasExistingChoice = readSharedDualScreenState(context) != null
                || local.contains(KEY_DUAL_SCREEN_SUPPORT_ENABLED)
                || local.contains(KEY_DUAL_SCREEN_LAST_EXTERNAL_REQUEST)
                || local.contains(KEY_DUAL_SCREEN_LAST_SWAP_REQUEST);

        SharedPreferences.Editor editor = local.edit()
                .putBoolean(KEY_THOR_DUAL_SCREEN_DEFAULT_APPLIED, true);
        if (!hasExistingChoice) {
            editor.putBoolean(KEY_DUAL_SCREEN_SUPPORT_ENABLED, true)
                    .putBoolean(KEY_DUAL_SCREEN_LAST_EXTERNAL_REQUEST, true)
                    .putBoolean(KEY_DUAL_SCREEN_LAST_SWAP_REQUEST, false);
        }
        boolean saved = editor.commit();
        if (!saved) {
            SharedPreferences.Editor fallback = local.edit()
                    .putBoolean(KEY_THOR_DUAL_SCREEN_DEFAULT_APPLIED, true);
            if (!hasExistingChoice) {
                fallback.putBoolean(KEY_DUAL_SCREEN_SUPPORT_ENABLED, true)
                        .putBoolean(KEY_DUAL_SCREEN_LAST_EXTERNAL_REQUEST, true)
                        .putBoolean(KEY_DUAL_SCREEN_LAST_SWAP_REQUEST, false);
            }
            fallback.apply();
        }
        if (!hasExistingChoice) persistDualScreenState(context);
    }

    /** Returns true when either package-local or shared dual-screen state exists. */
    public static boolean hasDualScreenPreferenceState(@NonNull Context context) {
        synchronized (DUAL_SCREEN_STATE_LOCK) {
            if (readSharedDualScreenState(context) != null) return true;
            SharedPreferences local = prefs(context);
            return local.contains(KEY_DUAL_SCREEN_SUPPORT_ENABLED)
                    || local.contains(KEY_DUAL_SCREEN_LAST_EXTERNAL_REQUEST)
                    || local.contains(KEY_DUAL_SCREEN_LAST_SWAP_REQUEST);
        }
    }

    /**
     * Persists the user's last requested dual-screen direction across display
     * disconnects, activity recreation, game restarts, and package variants.
     */
    public static boolean isDualScreenLastExternalRequest(@NonNull Context context) {
        synchronizeDualScreenState(context);
        return prefs(context).getBoolean(KEY_DUAL_SCREEN_LAST_EXTERNAL_REQUEST, false);
    }

    public static void setDualScreenLastExternalRequest(@NonNull Context context, boolean external) {
        saveBoolean(context, KEY_DUAL_SCREEN_LAST_EXTERNAL_REQUEST, external);
        persistDualScreenState(context);
    }

    /**
     * Persists whether the user's last dual-screen direction was swapped:
     * Minecraft on the Android/default display, HUD deck on the external display.
     */
    public static boolean isDualScreenLastSwapRequest(@NonNull Context context) {
        synchronizeDualScreenState(context);
        return prefs(context).getBoolean(KEY_DUAL_SCREEN_LAST_SWAP_REQUEST, false);
    }

    /**
     * Saves the exact requested dual-screen layout as one atomic state update.
     * Choosing either swap direction also means the user wants dual-screen/external
     * mode active the next time a usable second display is present.
     */
    public static void setDualScreenSwapState(@NonNull Context context, boolean swapped) {
        synchronized (DUAL_SCREEN_STATE_LOCK) {
            SharedPreferences local = prefs(context);
            boolean enabled = local.getBoolean(KEY_DUAL_SCREEN_SUPPORT_ENABLED, false);
            boolean saved = local.edit()
                    .putBoolean(KEY_DUAL_SCREEN_LAST_EXTERNAL_REQUEST, true)
                    .putBoolean(KEY_DUAL_SCREEN_LAST_SWAP_REQUEST, swapped)
                    .commit();
            if (!saved) {
                local.edit()
                        .putBoolean(KEY_DUAL_SCREEN_LAST_EXTERNAL_REQUEST, true)
                        .putBoolean(KEY_DUAL_SCREEN_LAST_SWAP_REQUEST, swapped)
                        .apply();
            }
            writeSharedDualScreenState(context, enabled, true, swapped);
        }
    }

    public static void setDualScreenLastSwapRequest(@NonNull Context context, boolean swapped) {
        setDualScreenSwapState(context, swapped);
    }

    private static void synchronizeDualScreenState(@NonNull Context context) {
        synchronized (DUAL_SCREEN_STATE_LOCK) {
            SharedPreferences local = prefs(context);
            DualScreenSharedState shared = readSharedDualScreenState(context);
            if (shared != null) {
                boolean differs = !local.contains(KEY_DUAL_SCREEN_SUPPORT_ENABLED)
                        || !local.contains(KEY_DUAL_SCREEN_LAST_EXTERNAL_REQUEST)
                        || !local.contains(KEY_DUAL_SCREEN_LAST_SWAP_REQUEST)
                        || local.getBoolean(KEY_DUAL_SCREEN_SUPPORT_ENABLED, false) != shared.enabled
                        || local.getBoolean(KEY_DUAL_SCREEN_LAST_EXTERNAL_REQUEST, false) != shared.externalRequested
                        || local.getBoolean(KEY_DUAL_SCREEN_LAST_SWAP_REQUEST, false) != shared.swapped;
                if (differs) {
                    boolean saved = local.edit()
                            .putBoolean(KEY_DUAL_SCREEN_SUPPORT_ENABLED, shared.enabled)
                            .putBoolean(KEY_DUAL_SCREEN_LAST_EXTERNAL_REQUEST, shared.externalRequested)
                            .putBoolean(KEY_DUAL_SCREEN_LAST_SWAP_REQUEST, shared.swapped)
                            .commit();
                    if (!saved) {
                        local.edit()
                                .putBoolean(KEY_DUAL_SCREEN_SUPPORT_ENABLED, shared.enabled)
                                .putBoolean(KEY_DUAL_SCREEN_LAST_EXTERNAL_REQUEST, shared.externalRequested)
                                .putBoolean(KEY_DUAL_SCREEN_LAST_SWAP_REQUEST, shared.swapped)
                                .apply();
                    }
                }
                return;
            }

            if (local.contains(KEY_DUAL_SCREEN_SUPPORT_ENABLED)
                    || local.contains(KEY_DUAL_SCREEN_LAST_EXTERNAL_REQUEST)
                    || local.contains(KEY_DUAL_SCREEN_LAST_SWAP_REQUEST)) {
                writeSharedDualScreenState(
                        context,
                        local.getBoolean(KEY_DUAL_SCREEN_SUPPORT_ENABLED, false),
                        local.getBoolean(KEY_DUAL_SCREEN_LAST_EXTERNAL_REQUEST, false),
                        local.getBoolean(KEY_DUAL_SCREEN_LAST_SWAP_REQUEST, false)
                );
            }
        }
    }

    private static void persistDualScreenState(@NonNull Context context) {
        synchronized (DUAL_SCREEN_STATE_LOCK) {
            SharedPreferences local = prefs(context);
            writeSharedDualScreenState(
                    context,
                    local.getBoolean(KEY_DUAL_SCREEN_SUPPORT_ENABLED, false),
                    local.getBoolean(KEY_DUAL_SCREEN_LAST_EXTERNAL_REQUEST, false),
                    local.getBoolean(KEY_DUAL_SCREEN_LAST_SWAP_REQUEST, false)
            );
        }
    }

    @Nullable
    private static DualScreenSharedState readSharedDualScreenState(@NonNull Context context) {
        File file = new File(PathManager.getMinecraftHome(context), SHARED_DUAL_SCREEN_STATE_FILE);
        if (!file.isFile()) return null;

        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(file)) {
            properties.load(input);
            String enabled = properties.getProperty(SHARED_DUAL_SCREEN_ENABLED);
            if (enabled == null) return null;
            return new DualScreenSharedState(
                    Boolean.parseBoolean(enabled),
                    Boolean.parseBoolean(properties.getProperty(SHARED_DUAL_SCREEN_EXTERNAL_REQUEST, "false")),
                    Boolean.parseBoolean(properties.getProperty(SHARED_DUAL_SCREEN_SWAPPED, "false"))
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void writeSharedDualScreenState(
            @NonNull Context context,
            boolean enabled,
            boolean externalRequested,
            boolean swapped
    ) {
        File minecraftHome = PathManager.getMinecraftHome(context);
        if (!minecraftHome.exists() && !minecraftHome.mkdirs()) return;

        File target = new File(minecraftHome, SHARED_DUAL_SCREEN_STATE_FILE);
        File temporary = new File(minecraftHome, SHARED_DUAL_SCREEN_STATE_FILE + ".tmp");
        Properties properties = new Properties();
        properties.setProperty(SHARED_DUAL_SCREEN_ENABLED, Boolean.toString(enabled));
        properties.setProperty(SHARED_DUAL_SCREEN_EXTERNAL_REQUEST, Boolean.toString(externalRequested));
        properties.setProperty(SHARED_DUAL_SCREEN_SWAPPED, Boolean.toString(swapped));

        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            properties.store(output, "DroidBridge dual-screen state shared by package variants");
            output.flush();
        } catch (Throwable ignored) {
            temporary.delete();
            return;
        }

        if (target.exists() && !target.delete()) {
            temporary.delete();
            return;
        }
        if (!temporary.renameTo(target)) {
            try (FileInputStream input = new FileInputStream(temporary);
                 FileOutputStream output = new FileOutputStream(target, false)) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) output.write(buffer, 0, read);
                }
                output.flush();
            } catch (Throwable ignored) {
                // Keep package-local SharedPreferences as the fallback source of truth.
            } finally {
                temporary.delete();
            }
        }
    }

    private static final class DualScreenSharedState {
        final boolean enabled;
        final boolean externalRequested;
        final boolean swapped;

        DualScreenSharedState(boolean enabled, boolean externalRequested, boolean swapped) {
            this.enabled = enabled;
            this.externalRequested = externalRequested;
            this.swapped = swapped;
        }
    }

    /**
     * Controls Android navigation Back / gesture Back while Minecraft is running.
     * New installs default to Minecraft Escape/pause. Existing installs that used the
     * former boolean toggle retain their launcher-menu or disabled choice.
     */
    @NonNull
    public static String getAndroidBackButtonAction(@NonNull Context context) {
        SharedPreferences preferences = prefs(context);
        if (preferences.contains(KEY_ANDROID_BACK_BUTTON_ACTION)) {
            return sanitizeAndroidBackButtonAction(
                    preferences.getString(KEY_ANDROID_BACK_BUTTON_ACTION, ANDROID_BACK_ACTION_PAUSE_GAME));
        }

        if (preferences.contains(KEY_ANDROID_BACK_OPENS_IN_GAME_MENU)) {
            return preferences.getBoolean(KEY_ANDROID_BACK_OPENS_IN_GAME_MENU, true)
                    ? ANDROID_BACK_ACTION_LAUNCHER_MENU
                    : ANDROID_BACK_ACTION_DISABLED;
        }
        return ANDROID_BACK_ACTION_PAUSE_GAME;
    }

    public static void setAndroidBackButtonAction(@NonNull Context context, @NonNull String action) {
        prefs(context).edit()
                .putString(KEY_ANDROID_BACK_BUTTON_ACTION, sanitizeAndroidBackButtonAction(action))
                .remove(KEY_ANDROID_BACK_OPENS_IN_GAME_MENU)
                .commit();
    }

    /**
     * Physical mouse routing mode. Native is the accurate/default DroidBridge path.
     * Android Virtual Mouse keeps the Android OS pointer active so it can interact
     * with launcher/touch UI as well as Minecraft, at the cost of grabbed-mouse
     * accuracy because the pointer remains bounded by Android's display.
     */
    @NonNull
    public static String getPhysicalMouseMode(@NonNull Context context) {
        String value = prefs(context).getString(KEY_PHYSICAL_MOUSE_MODE, PHYSICAL_MOUSE_MODE_NATIVE);
        return PHYSICAL_MOUSE_MODE_ANDROID_VIRTUAL.equals(value)
                ? PHYSICAL_MOUSE_MODE_ANDROID_VIRTUAL
                : PHYSICAL_MOUSE_MODE_NATIVE;
    }

    public static void setPhysicalMouseMode(@NonNull Context context, @Nullable String mode) {
        prefs(context).edit()
                .putString(KEY_PHYSICAL_MOUSE_MODE,
                        PHYSICAL_MOUSE_MODE_ANDROID_VIRTUAL.equals(mode)
                                ? PHYSICAL_MOUSE_MODE_ANDROID_VIRTUAL
                                : PHYSICAL_MOUSE_MODE_NATIVE)
                .apply();
    }

    public static boolean isAndroidVirtualPhysicalMouse(@NonNull Context context) {
        return PHYSICAL_MOUSE_MODE_ANDROID_VIRTUAL.equals(getPhysicalMouseMode(context));
    }

    /**
     * Physical-keyboard shortcut for DroidBridge's in-game launcher menu.
     * New installs default to Ctrl+Esc. Existing R4 installs keep their previously
     * selected single-key shortcut (no modifiers) so upgrades do not silently remap it.
     * A stored keyCode of zero explicitly disables the shortcut.
     */
    public static int getInGameMenuKeyboardShortcutKeyCode(@NonNull Context context) {
        SharedPreferences preferences = prefs(context);
        if (preferences.contains(KEY_IN_GAME_MENU_KEYBOARD_SHORTCUT)) {
            return Math.max(0, preferences.getInt(KEY_IN_GAME_MENU_KEYBOARD_SHORTCUT, 0));
        }
        return DEFAULT_IN_GAME_MENU_KEYBOARD_SHORTCUT_KEYCODE;
    }

    public static int getInGameMenuKeyboardShortcutModifiers(@NonNull Context context) {
        SharedPreferences preferences = prefs(context);
        if (preferences.contains(KEY_IN_GAME_MENU_KEYBOARD_SHORTCUT_MODIFIERS)) {
            return sanitizeInGameMenuShortcutModifiers(
                    preferences.getInt(KEY_IN_GAME_MENU_KEYBOARD_SHORTCUT_MODIFIERS, 0));
        }
        // R4 stored only the key code. Preserve those user choices as unmodified keys.
        if (preferences.contains(KEY_IN_GAME_MENU_KEYBOARD_SHORTCUT)) {
            return 0;
        }
        return DEFAULT_IN_GAME_MENU_KEYBOARD_SHORTCUT_MODIFIERS;
    }

    public static void setInGameMenuKeyboardShortcut(
            @NonNull Context context,
            int keyCode,
            int modifiers
    ) {
        int sanitizedKeyCode = Math.max(0, keyCode);
        int sanitizedModifiers = sanitizedKeyCode == 0
                ? 0
                : sanitizeInGameMenuShortcutModifiers(modifiers);
        prefs(context).edit()
                .putInt(KEY_IN_GAME_MENU_KEYBOARD_SHORTCUT, sanitizedKeyCode)
                .putInt(KEY_IN_GAME_MENU_KEYBOARD_SHORTCUT_MODIFIERS, sanitizedModifiers)
                .commit();
    }

    /** Compatibility setter for the R4 single-key UI/source path. */
    public static void setInGameMenuKeyboardShortcutKeyCode(@NonNull Context context, int keyCode) {
        setInGameMenuKeyboardShortcut(context, keyCode, 0);
    }

    private static int sanitizeInGameMenuShortcutModifiers(int modifiers) {
        int normalized = KeyEvent.normalizeMetaState(modifiers);
        return normalized & (KeyEvent.META_CTRL_ON
                | KeyEvent.META_ALT_ON
                | KeyEvent.META_SHIFT_ON
                | KeyEvent.META_META_ON);
    }

    @NonNull
    private static String sanitizeAndroidBackButtonAction(@Nullable String action) {
        if (ANDROID_BACK_ACTION_LAUNCHER_MENU.equals(action)) {
            return ANDROID_BACK_ACTION_LAUNCHER_MENU;
        }
        if (ANDROID_BACK_ACTION_DISABLED.equals(action)) {
            return ANDROID_BACK_ACTION_DISABLED;
        }
        return ANDROID_BACK_ACTION_PAUSE_GAME;
    }

    /** Compatibility helpers for older source paths. */
    public static boolean isAndroidBackOpensInGameMenu(@NonNull Context context) {
        return ANDROID_BACK_ACTION_LAUNCHER_MENU.equals(getAndroidBackButtonAction(context));
    }

    public static void setAndroidBackOpensInGameMenu(@NonNull Context context, boolean enabled) {
        setAndroidBackButtonAction(
                context,
                enabled ? ANDROID_BACK_ACTION_LAUNCHER_MENU : ANDROID_BACK_ACTION_DISABLED);
    }

    /**
     * Controls DroidBridge launcher screens only. Existing installs that still have
     * the old combined orientation preference inherit that value automatically.
     */
    @NonNull
    public static String getLauncherOrientationMode(@NonNull Context context) {
        SharedPreferences preferences = prefs(context);
        String value = preferences.contains(KEY_LAUNCHER_ORIENTATION_MODE)
                ? preferences.getString(KEY_LAUNCHER_ORIENTATION_MODE, APP_ORIENTATION_AUTO)
                : preferences.getString(KEY_APP_ORIENTATION_MODE, APP_ORIENTATION_AUTO);
        return normalizeAppOrientationMode(value);
    }

    public static void setLauncherOrientationMode(@NonNull Context context, @NonNull String mode) {
        prefs(context).edit()
                .putString(KEY_LAUNCHER_ORIENTATION_MODE, normalizeAppOrientationMode(mode))
                .commit();
    }

    /**
     * Controls GameActivity only. Auto mode remains sensor-landscape so Minecraft can
     * rotate between landscape and reverse landscape without entering portrait.
     * Existing installs inherit the old combined orientation preference.
     */
    @NonNull
    public static String getGameOrientationMode(@NonNull Context context) {
        SharedPreferences preferences = prefs(context);
        String value = preferences.contains(KEY_GAME_ORIENTATION_MODE)
                ? preferences.getString(KEY_GAME_ORIENTATION_MODE, APP_ORIENTATION_AUTO)
                : preferences.getString(KEY_APP_ORIENTATION_MODE, APP_ORIENTATION_AUTO);
        return normalizeAppOrientationMode(value);
    }

    public static void setGameOrientationMode(@NonNull Context context, @NonNull String mode) {
        prefs(context).edit()
                .putString(KEY_GAME_ORIENTATION_MODE, normalizeAppOrientationMode(mode))
                .commit();
    }

    /**
     * Compatibility alias for older callers. New code should use the separate launcher
     * and game orientation accessors.
     */
    @Deprecated
    @NonNull
    public static String getAppOrientationMode(@NonNull Context context) {
        return getLauncherOrientationMode(context);
    }

    /**
     * Compatibility alias preserving the old combined-setting behavior.
     */
    @Deprecated
    public static void setAppOrientationMode(@NonNull Context context, @NonNull String mode) {
        String normalized = normalizeAppOrientationMode(mode);
        prefs(context).edit()
                .putString(KEY_LAUNCHER_ORIENTATION_MODE, normalized)
                .putString(KEY_GAME_ORIENTATION_MODE, normalized)
                .commit();
    }

    @NonNull
    public static String normalizeAppOrientationMode(@Nullable String mode) {
        if (mode == null) return APP_ORIENTATION_AUTO;
        String value = mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (APP_ORIENTATION_LANDSCAPE.equals(value)
                || APP_ORIENTATION_REVERSE_LANDSCAPE.equals(value)
                || APP_ORIENTATION_PORTRAIT.equals(value)
                || APP_ORIENTATION_REVERSE_PORTRAIT.equals(value)
                || APP_ORIENTATION_PORTRAIT_CENTERED_GAME.equals(value)) {
            return value;
        }
        return APP_ORIENTATION_AUTO;
    }

    /**
     * Enables compatibility hooks for controller mods that need launcher-side SDL/native
     * behavior on Android, such as Legacy4J and Controllable. Enabled by default because
     * the hooks only activate when a matching mod jar exists.
     */
    public static boolean isSdlControllerModCompatEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_ENABLE_SDL_CONTROLLER_MOD_COMPAT, true);
    }

    public static void setSdlControllerModCompatEnabled(@NonNull Context context, boolean enabled) {
        saveBoolean(context, KEY_ENABLE_SDL_CONTROLLER_MOD_COMPAT, enabled);
    }

    public static boolean isShowControllerModCompatWarnings(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_SHOW_CONTROLLER_MOD_COMPAT_WARNINGS, true);
    }

    public static void setShowControllerModCompatWarnings(@NonNull Context context, boolean enabled) {
        saveBoolean(context, KEY_SHOW_CONTROLLER_MOD_COMPAT_WARNINGS, enabled);
    }

    public static boolean hasAllocatedMemoryMb(@NonNull Context context) {
        return prefs(context).contains(KEY_ALLOCATED_MEMORY_MB);
    }

    public static int getAllocatedMemoryMb(@NonNull Context context, int fallbackMb) {
        return prefs(context).getInt(KEY_ALLOCATED_MEMORY_MB, fallbackMb);
    }

    public static void setAllocatedMemoryMb(@NonNull Context context, int memoryMb) {
        prefs(context).edit().putInt(KEY_ALLOCATED_MEMORY_MB, memoryMb).commit();
    }

    public static boolean isRamUnlocked(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_RAM_UNLOCKED, false);
    }

    public static void setRamUnlocked(@NonNull Context context, boolean unlocked) {
        saveBoolean(context, KEY_RAM_UNLOCKED, unlocked);
    }

    /**
     * Resolution scale applied to the game render buffer.
     * 100 = native device view size, 25 = quarter-size render buffer, 200 = double-size render buffer.
     */
    public static int getGameResolutionScalePercent(@NonNull Context context) {
        SharedPreferences resolutionPreferences = resolutionPrefs(context);
        if (resolutionPreferences.contains(KEY_GAME_RESOLUTION_SCALE_PERCENT)) {
            return clampGameResolutionScalePercent(
                    resolutionPreferences.getInt(
                            KEY_GAME_RESOLUTION_SCALE_PERCENT,
                            DEFAULT_GAME_RESOLUTION_SCALE_PERCENT
                    )
            );
        }

        int legacyValue = prefs(context).getInt(
                KEY_GAME_RESOLUTION_SCALE_PERCENT,
                DEFAULT_GAME_RESOLUTION_SCALE_PERCENT
        );
        int clampedLegacyValue = clampGameResolutionScalePercent(legacyValue);
        setGameResolutionScalePercent(context, clampedLegacyValue);
        return clampedLegacyValue;
    }

    public static void setGameResolutionScalePercent(@NonNull Context context, int percent) {
        int clamped = clampGameResolutionScalePercent(percent);

        // GameActivity runs in the :game process while LauncherSettingsActivity runs in
        // the default app process. A normal SharedPreferences.apply() write can leave
        // the other process holding its old cached value, which is why the settings
        // screen could keep snapping the slider back to 70% after the in-game dialog
        // had already saved 100%. Keep this specific setting in a tiny dedicated
        // multi-process file and commit it synchronously so both processes see the
        // latest render scale before creating/resizing MinecraftGLSurface.
        boolean resolutionSaved = resolutionPrefs(context).edit()
                .putInt(KEY_GAME_RESOLUTION_SCALE_PERCENT, clamped)
                .commit();
        boolean legacySaved = prefs(context).edit()
                .putInt(KEY_GAME_RESOLUTION_SCALE_PERCENT, clamped)
                .commit();

        if (!resolutionSaved) {
            resolutionPrefs(context).edit()
                    .putInt(KEY_GAME_RESOLUTION_SCALE_PERCENT, clamped)
                    .apply();
        }
        if (!legacySaved) {
            prefs(context).edit()
                    .putInt(KEY_GAME_RESOLUTION_SCALE_PERCENT, clamped)
                    .apply();
        }
    }

    public static int clampGameResolutionScalePercent(int percent) {
        if (percent < MIN_GAME_RESOLUTION_SCALE_PERCENT) return MIN_GAME_RESOLUTION_SCALE_PERCENT;
        if (percent > MAX_GAME_RESOLUTION_SCALE_PERCENT) return MAX_GAME_RESOLUTION_SCALE_PERCENT;
        return percent;
    }

    /**
     * Keeps the existing DroidBridge behavior by default: Minecraft launches in immersive fullscreen.
     * Turning this off lets Android system bars/safe areas remain visible.
     */
    public static boolean isForceFullscreenMode(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_FORCE_FULLSCREEN_MODE, true);
    }

    public static void setForceFullscreenMode(@NonNull Context context, boolean enabled) {
        saveBoolean(context, KEY_FORCE_FULLSCREEN_MODE, enabled);
    }

    /**
     * Adds a small safe inset around the game container for devices where rounded display corners
     * or cutouts hide the game edge. This does not change the physical screen shape.
     */
    public static boolean isAvoidRoundedDisplayCorners(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_AVOID_ROUNDED_DISPLAY_CORNERS, false);
    }

    public static void setAvoidRoundedDisplayCorners(@NonNull Context context, boolean enabled) {
        saveBoolean(context, KEY_AVOID_ROUNDED_DISPLAY_CORNERS, enabled);
    }

    /**
     * Treats the physical display cutout/notch as unavailable game space. When enabled,
     * DroidBridge keeps Minecraft, touch controls and launcher overlays inside Android's
     * reported cutout-safe rectangle even while immersive fullscreen is active.
     */
    public static boolean isIgnoreDisplayCutout(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_IGNORE_DISPLAY_CUTOUT, false);
    }

    public static void setIgnoreDisplayCutout(@NonNull Context context, boolean enabled) {
        saveBoolean(context, KEY_IGNORE_DISPLAY_CUTOUT, enabled);
    }

    /**
     * When enabled, opening Android's keyboard for Minecraft chat moves the rendered game
     * view upward so the in-game text field remains visible. Enabled by default to preserve
     * DroidBridge's existing behavior; users of floating keyboards can disable it.
     */
    public static boolean isImeViewportPushEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_IME_VIEWPORT_PUSH_ENABLED, true);
    }

    public static void setImeViewportPushEnabled(@NonNull Context context, boolean enabled) {
        saveBoolean(context, KEY_IME_VIEWPORT_PUSH_ENABLED, enabled);
    }

    /**
     * Advanced/debug option. When enabled, controller mod compatibility will run
     * the full launcher SDL initialization path for mods such as Controllable.
     * Disabled by default because normal built-in controls should not use SDL routing.
     */
    public static boolean isForceSdlControllerBridge(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_FORCE_SDL_CONTROLLER_BRIDGE, false);
    }

    public static void setForceSdlControllerBridge(@NonNull Context context, boolean enabled) {
        saveBoolean(context, KEY_FORCE_SDL_CONTROLLER_BRIDGE, enabled);
    }
}

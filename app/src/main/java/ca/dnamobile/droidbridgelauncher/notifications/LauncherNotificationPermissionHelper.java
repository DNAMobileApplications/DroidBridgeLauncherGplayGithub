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

package ca.dnamobile.droidbridgelauncher.notifications;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;

/**
 * Central place for DroidBridge's notification permission and install notification setting.
 *
 * Android 13+ requires POST_NOTIFICATIONS before the user can see foreground-service
 * progress notifications in the notification drawer. The install can technically keep
 * running without that permission, but it looks invisible to the user, so the launcher
 * treats visible install notifications as an opt-in setting backed by the real Android
 * notification permission.
 */
public final class LauncherNotificationPermissionHelper {
    private static final String PREFS = "java_launcher_notification_permissions";
    private static final String KEY_LAUNCH_PROMPT_SHOWN = "notification_launch_prompt_shown";
    private static final String KEY_BACKGROUND_INSTALL_NOTIFICATIONS = "background_install_notifications";

    private LauncherNotificationPermissionHelper() {
    }

    public static boolean requiresRuntimePermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    public static boolean hasPostNotificationsPermission(@NonNull Context context) {
        if (!requiresRuntimePermission()) return true;
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean shouldShowLaunchPrompt(@NonNull Context context) {
        return requiresRuntimePermission()
                && !hasPostNotificationsPermission(context)
                && !getPrefs(context).getBoolean(KEY_LAUNCH_PROMPT_SHOWN, false);
    }

    public static void markLaunchPromptShown(@NonNull Context context) {
        getPrefs(context).edit().putBoolean(KEY_LAUNCH_PROMPT_SHOWN, true).apply();
    }

    public static boolean isBackgroundInstallNotificationsEnabled(@NonNull Context context) {
        return getPrefs(context).getBoolean(KEY_BACKGROUND_INSTALL_NOTIFICATIONS, true);
    }

    public static void setBackgroundInstallNotificationsEnabled(@NonNull Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_BACKGROUND_INSTALL_NOTIFICATIONS, enabled).apply();
    }

    public static boolean canShowBackgroundInstallNotification(@NonNull Context context) {
        return isBackgroundInstallNotificationsEnabled(context) && hasPostNotificationsPermission(context);
    }

    public static void requestPostNotificationsPermission(@NonNull ActivityResultLauncher<String> launcher) {
        if (!requiresRuntimePermission()) return;
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    public static void openAppNotificationSettings(@NonNull Activity activity) {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
        } else {
            intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + activity.getPackageName()));
        }

        try {
            activity.startActivity(intent);
        } catch (Throwable ignored) {
            Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(fallback);
        }
    }

    @NonNull
    private static SharedPreferences getPrefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

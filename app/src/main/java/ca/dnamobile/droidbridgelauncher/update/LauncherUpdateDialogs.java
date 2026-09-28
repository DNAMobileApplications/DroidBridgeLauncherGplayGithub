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

package ca.dnamobile.droidbridgelauncher.update;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import ca.dnamobile.droidbridgelauncher.BuildConfig;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class LauncherUpdateDialogs {
    private LauncherUpdateDialogs() {
    }

    public static void checkOnStartup(@NonNull Activity activity) {
        if (!LauncherUpdatePreferences.shouldAutoCheckNow(activity)) return;
        LauncherUpdatePreferences.markAutoCheckedNow(activity);

        // Small delay prevents the update dialog fighting first-launch/legal dialogs.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isActivityAlive(activity)) return;
            checkInternal(activity, false);
        }, 1500L);
    }

    public static void checkManually(@NonNull Activity activity) {
        Toast.makeText(activity, "Checking for launcher updates...", Toast.LENGTH_SHORT).show();
        LauncherUpdatePreferences.clearIgnoredTag(activity);
        checkInternal(activity, true);
    }

    private static void checkInternal(@NonNull Activity activity, boolean manual) {
        LauncherUpdateChecker.checkAsync(activity, new LauncherUpdateChecker.Callback() {
            @Override
            public void onResult(@NonNull LauncherUpdateInfo info) {
                if (!isActivityAlive(activity)) return;
                showUpdateAvailableDialog(activity, info);
            }

            @Override
            public void onNoUpdate(@NonNull LauncherUpdateInfo latestInfo) {
                if (!manual || !isActivityAlive(activity)) return;
                String version = latestInfo.getDisplayVersion();
                Toast.makeText(
                        activity,
                        "DroidBridge is up to date" + (version.isEmpty() ? "." : ": " + version),
                        Toast.LENGTH_LONG
                ).show();
            }

            @Override
            public void onError(@NonNull String message, @Nullable Throwable throwable) {
                if (!manual || !isActivityAlive(activity)) return;
                new MaterialAlertDialogBuilder(activity)
                        .setTitle("Update check failed")
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });
    }

    private static void showUpdateAvailableDialog(@NonNull Activity activity, @NonNull LauncherUpdateInfo info) {
        StringBuilder message = new StringBuilder();
        message.append("Current version: ").append(BuildConfig.VERSION_NAME).append('\n');
        message.append("Latest version: ").append(info.getDisplayVersion()).append('\n');

        if (info.hasApkAsset()) {
            message.append("Download: ").append(info.apkAssetName == null ? "APK" : info.apkAssetName)
                    .append(" (").append(info.getDisplaySize()).append(")\n");
        }

        if (info.prerelease) {
            message.append('\n').append("This release is marked as a pre-release.").append('\n');
        }

        if (!TextUtils.isEmpty(info.releaseNotes)) {
            message.append('\n').append(trimReleaseNotes(info.releaseNotes));
        }

        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(activity)
                .setTitle("Launcher update available")
                .setMessage(message.toString())
                .setNegativeButton("Later", null)
                .setNeutralButton("Ignore this version", (dialog, which) ->
                        LauncherUpdatePreferences.ignoreTag(activity, info.tagName));

        if (info.hasApkAsset()) {
            builder.setPositiveButton("Download APK", (dialog, which) ->
                    openUrl(activity, info.apkDownloadUrl, "Unable to open APK download link."));
        } else {
            builder.setPositiveButton("Open release", (dialog, which) ->
                    openUrl(activity, info.releaseUrl, "Unable to open GitHub release page."));
        }

        builder.show();
    }

    @NonNull
    private static String trimReleaseNotes(@NonNull String releaseNotes) {
        String clean = releaseNotes.trim();
        int max = 1200;
        if (clean.length() <= max) return clean;
        return clean.substring(0, max).trim() + "\n\n...";
    }

    private static void openUrl(@NonNull Activity activity, @Nullable String url, @NonNull String errorMessage) {
        if (url == null || url.trim().isEmpty()) {
            Toast.makeText(activity, errorMessage, Toast.LENGTH_LONG).show();
            return;
        }

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            activity.startActivity(intent);
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(activity, errorMessage, Toast.LENGTH_LONG).show();
        }
    }

    private static boolean isActivityAlive(@NonNull Activity activity) {
        if (activity.isFinishing()) return false;
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !activity.isDestroyed();
    }
}

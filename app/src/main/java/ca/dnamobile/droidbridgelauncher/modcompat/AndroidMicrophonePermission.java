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

package ca.dnamobile.droidbridgelauncher.modcompat;

import android.Manifest;
import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.annotation.NonNull;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Runtime permission helper for mods that need microphone access, including
 * Simple Voice Chat and Verity voice input.
 *
 * Important: Android only grants RECORD_AUDIO to the app UID after the user
 * accepts the runtime permission prompt. The game JVM runs under the same app
 * UID, so once this is granted, Java/native audio code can access the mic.
 */
public final class AndroidMicrophonePermission {
    public static final int REQUEST_CODE_RECORD_AUDIO = 0x511C; // SVC-ish stable request code

    private AndroidMicrophonePermission() {
    }

    public static boolean isGranted(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static void request(@NonNull Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || isGranted(activity)) {
            return;
        }
        activity.requestPermissions(
                new String[]{Manifest.permission.RECORD_AUDIO},
                REQUEST_CODE_RECORD_AUDIO
        );
    }

    /**
     * Shows a short explanation first, then opens Android's runtime permission prompt.
     */
    public static void showRequestDialog(@NonNull Activity activity) {
        if (isGranted(activity)) {
            showAlreadyGrantedDialog(activity);
            return;
        }

        new MaterialAlertDialogBuilder(activity)
                .setTitle("Enable microphone")
                .setMessage("Minecraft voice mods such as Verity and Simple Voice Chat need Android microphone permission before they can use your mic. This only enables microphone access while the game is running.")
                .setPositiveButton("Allow", (dialog, which) -> request(activity))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    public static void showAlreadyGrantedDialog(@NonNull Activity activity) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Microphone enabled")
                .setMessage("Android microphone permission is already granted for DroidBridge.")
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /**
     * Use this when the user previously denied permission with “Don't ask again”.
     */
    public static void openAppSettings(@NonNull Context context) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public static boolean wasGrantedFromResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        if (requestCode != REQUEST_CODE_RECORD_AUDIO) {
            return false;
        }
        for (int i = 0; i < permissions.length; i++) {
            if (Manifest.permission.RECORD_AUDIO.equals(permissions[i])) {
                return i < grantResults.length
                        && grantResults[i] == PackageManager.PERMISSION_GRANTED;
            }
        }
        return false;
    }
}

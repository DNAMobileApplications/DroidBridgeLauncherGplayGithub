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

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.Locale;

/**
 * Launcher-side microphone-mod permission helper.
 *
 * This does not modify the mods themselves. It does the Android launcher
 * work that desktop-oriented voice mods cannot do on their own:
 * - detect Simple Voice Chat or Verity in common mod locations
 * - make sure Android RECORD_AUDIO permission is granted before launch
 * - provide a Settings entry point for users to grant/recover permission
 */
public final class SimpleVoiceChatCompat {
    private static final String TAG = "SimpleVoiceChatCompat";

    private SimpleVoiceChatCompat() {
    }

    public static boolean isInstalledForInstance(@Nullable File gameDirectory) {
        if (gameDirectory == null) {
            return false;
        }

        // Most DroidBridge instances use: instance/game/mods
        if (containsMicrophoneModJar(new File(gameDirectory, "mods"))) {
            return true;
        }

        File instanceDir = gameDirectory.getParentFile();
        if (instanceDir != null && containsMicrophoneModJar(new File(instanceDir, "mods"))) {
            return true;
        }

        // Walk up a few levels and check shared .minecraft/mods if present.
        File cursor = gameDirectory;
        for (int i = 0; i < 5 && cursor != null; i++) {
            if (".minecraft".equals(cursor.getName()) && containsMicrophoneModJar(new File(cursor, "mods"))) {
                return true;
            }
            cursor = cursor.getParentFile();
        }

        return false;
    }

    public static boolean containsMicrophoneModJar(@Nullable File modsDir) {
        if (modsDir == null || !modsDir.isDirectory()) {
            return false;
        }

        File[] files = modsDir.listFiles();
        if (files == null) {
            return false;
        }

        for (File file : files) {
            if (file == null || !file.isFile()) {
                continue;
            }
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".jar")) {
                continue;
            }

            if (isSimpleVoiceChatJarName(name) || isVerityJarName(name)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Kept for callers that specifically need Simple Voice Chat detection.
     */
    public static boolean containsVoiceChatJar(@Nullable File modsDir) {
        if (modsDir == null || !modsDir.isDirectory()) {
            return false;
        }

        File[] files = modsDir.listFiles();
        if (files == null) {
            return false;
        }

        for (File file : files) {
            if (file == null || !file.isFile()) {
                continue;
            }
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".jar") && isSimpleVoiceChatJarName(name)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isSimpleVoiceChatJarName(@NonNull String name) {
        return name.startsWith("voicechat-")
                || name.contains("simple-voice-chat")
                || name.contains("simplevoicechat");
    }

    private static boolean isVerityJarName(@NonNull String name) {
        return name.equals("verity.jar")
                || name.startsWith("verity-")
                || name.startsWith("verity_");
    }

    /**
     * Call this from the Activity that owns the launch button, before starting Minecraft.
     *
     * @return true when launch can continue, false when a permission prompt was shown
     */
    public static boolean ensureMicrophoneReadyBeforeLaunch(
            @NonNull Activity activity,
            @Nullable File gameDirectory
    ) {
        if (!isInstalledForInstance(gameDirectory)) {
            return true;
        }

        if (AndroidMicrophonePermission.isGranted(activity)) {
            Log.i(TAG, "Microphone mod detected and Android microphone permission is granted");
            return true;
        }

        Log.i(TAG, "Microphone mod detected but Android microphone permission is missing");
        AndroidMicrophonePermission.showRequestDialog(activity);
        return false;
    }

    /**
     * Safe non-Activity check for launch code that cannot request permissions.
     * Use this only to warn/log. Runtime permission prompts require an Activity.
     */
    public static boolean isMicrophoneReady(@NonNull Context context) {
        return AndroidMicrophonePermission.isGranted(context);
    }
}

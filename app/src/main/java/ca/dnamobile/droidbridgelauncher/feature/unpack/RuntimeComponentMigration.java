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

package ca.dnamobile.droidbridgelauncher.feature.unpack;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

/**
 * Refreshes cached launcher runtime components after a native bridge rename.
 *
 * Android package updates keep app-private files by design. That is normally what
 * DroidBridge wants, because worlds, accounts, settings, and downloaded game data
 * must survive updates. The LWJGL bridge jars and native runtime helper folders are
 * generated launcher components though, so they must be refreshed when their native
 * load names change.
 */
public final class RuntimeComponentMigration {
    private static final String TAG = "RuntimeComponentMigration";
    private static final String MARKER_NAME = ".droidbridge-runtime-components-v2";
    private static final String PREFS_NAME = "droidbridge_runtime_migrations";
    private static final String PREF_COMPLETED = "runtime_components_v2_completed";

    private RuntimeComponentMigration() {
    }

    public static void runIfNeeded(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        File filesDir = appContext.getFilesDir();
        File marker = new File(filesDir, MARKER_NAME);
        SharedPreferences preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (marker.isFile() || preferences.getBoolean(PREF_COMPLETED, false)) return;

        Logging.i(TAG, "Refreshing generated runtime component caches for this DroidBridge build.");

        deleteComponent(filesDir, "lwjgl3.3.3");
        deleteComponent(filesDir, "lwjgl3.3.3-bta");
        deleteComponent(filesDir, "lwjgl3.4.1");

        PathManager.deleteQuietly(new File(appContext.getCacheDir(), "natives"));
        if (PathManager.DIR_RUNTIME_MOD != null) {
            PathManager.deleteQuietly(PathManager.DIR_RUNTIME_MOD);
            //noinspection ResultOfMethodCallIgnored
            PathManager.DIR_RUNTIME_MOD.mkdirs();
        }

        // Use both a file and SharedPreferences so a device-specific marker write
        // failure cannot repeat this destructive migration on every app launch.
        writeMarker(marker);
        if (!preferences.edit().putBoolean(PREF_COMPLETED, true).commit()) {
            Logging.e(TAG, "Unable to persist runtime migration completion preference", null);
        }
    }

    private static void deleteComponent(@NonNull File root, @NonNull String name) {
        PathManager.deleteQuietly(new File(root, name));
    }

    private static void writeMarker(@NonNull File marker) {
        try {
            File parent = marker.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try (FileOutputStream output = new FileOutputStream(marker, false)) {
                output.write("runtime-components-v2\n".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to write runtime migration marker", throwable);
        }
    }
}

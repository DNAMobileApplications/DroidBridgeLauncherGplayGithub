/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * DroidBridge-owned metadata path helper. New writes go to .droidbridge while
 * legacy .javalauncher metadata is read as a compatibility fallback for users
 * upgrading from older internal builds.
 */

package ca.dnamobile.droidbridgelauncher.modmanager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

public final class DroidBridgeMetadataPaths {
    public static final String PRIMARY_DIRECTORY = ".droidbridge";
    public static final String LEGACY_DIRECTORY = ".javalauncher";
    public static final String MOD_MANAGER_MANIFEST = "modmanager_installed.json";
    public static final String MODPACK_MANIFEST = "modpack_manifest.json";
    public static final String MODPACK_FILES_MANIFEST = "modpack_files_manifest.json";
    public static final String CONTENT_ICONS_DIRECTORY = "content_icons";
    public static final String MOD_MANAGER_ICONS_DIRECTORY = "modmanager_icons";

    private DroidBridgeMetadataPaths() {
    }

    @NonNull
    public static File directoryForWrite(@NonNull File gameDirectory) {
        File directory = new File(gameDirectory, PRIMARY_DIRECTORY);
        if (!directory.exists()) directory.mkdirs();
        return directory;
    }

    @NonNull
    public static File legacyDirectory(@NonNull File gameDirectory) {
        return new File(gameDirectory, LEGACY_DIRECTORY);
    }

    @NonNull
    public static File fileForWrite(@NonNull File gameDirectory, @NonNull String fileName) {
        return new File(directoryForWrite(gameDirectory), fileName);
    }

    @NonNull
    public static File fileForRead(@NonNull File gameDirectory, @NonNull String fileName) {
        File modern = new File(directoryForWrite(gameDirectory), fileName);
        if (modern.isFile()) return modern;

        File legacy = new File(legacyDirectory(gameDirectory), fileName);
        return legacy.isFile() ? legacy : modern;
    }

    @NonNull
    public static File childDirectoryForWrite(@NonNull File gameDirectory, @NonNull String directoryName) {
        File directory = new File(directoryForWrite(gameDirectory), directoryName);
        if (!directory.exists()) directory.mkdirs();
        return directory;
    }

    @Nullable
    public static File childDirectoryForRead(@NonNull File gameDirectory, @NonNull String directoryName) {
        File modern = new File(directoryForWrite(gameDirectory), directoryName);
        if (modern.isDirectory()) return modern;

        File legacy = new File(legacyDirectory(gameDirectory), directoryName);
        return legacy.isDirectory() ? legacy : modern;
    }
}

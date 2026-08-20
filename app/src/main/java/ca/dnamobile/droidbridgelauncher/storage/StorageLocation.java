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

package ca.dnamobile.droidbridgelauncher.storage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class StorageLocation {
    private final String id;
    private final String displayName;
    private final String summary;
    private final String uriString;
    @Nullable
    private final String launcherHomePath;
    @Nullable
    private final String minecraftHomePath;
    private final boolean defaultLocation;
    private final boolean usableForFileLaunch;

    public StorageLocation(
            @NonNull String id,
            @NonNull String displayName,
            @NonNull String summary,
            @Nullable String uriString,
            boolean defaultLocation
    ) {
        this(id, displayName, summary, uriString, null, null, defaultLocation, defaultLocation);
    }

    public StorageLocation(
            @NonNull String id,
            @NonNull String displayName,
            @NonNull String summary,
            @Nullable String uriString,
            @Nullable String launcherHomePath,
            @Nullable String minecraftHomePath,
            boolean defaultLocation,
            boolean usableForFileLaunch
    ) {
        this.id = id;
        this.displayName = displayName;
        this.summary = summary;
        this.uriString = uriString;
        this.launcherHomePath = launcherHomePath;
        this.minecraftHomePath = minecraftHomePath;
        this.defaultLocation = defaultLocation;
        this.usableForFileLaunch = usableForFileLaunch;
    }

    @NonNull
    public String getId() {
        return id;
    }

    @NonNull
    public String getDisplayName() {
        return displayName;
    }

    @NonNull
    public String getSummary() {
        return summary;
    }

    @Nullable
    public String getUriString() {
        return uriString;
    }

    @Nullable
    public String getLauncherHomePath() {
        return launcherHomePath;
    }

    @Nullable
    public String getMinecraftHomePath() {
        return minecraftHomePath;
    }

    public boolean isDefaultLocation() {
        return defaultLocation;
    }

    public boolean isUsableForFileLaunch() {
        return usableForFileLaunch;
    }
}

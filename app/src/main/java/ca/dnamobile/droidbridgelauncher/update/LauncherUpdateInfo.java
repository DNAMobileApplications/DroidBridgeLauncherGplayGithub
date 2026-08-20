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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Immutable result from the GitHub release update checker.
 */
public final class LauncherUpdateInfo {
    @NonNull
    public final String tagName;
    @NonNull
    public final String releaseName;
    @NonNull
    public final String releaseUrl;
    @NonNull
    public final String releaseNotes;
    @Nullable
    public final String apkDownloadUrl;
    @Nullable
    public final String apkAssetName;
    public final long apkSizeBytes;
    public final boolean prerelease;
    public final boolean updateAvailable;

    public LauncherUpdateInfo(
            @NonNull String tagName,
            @NonNull String releaseName,
            @NonNull String releaseUrl,
            @NonNull String releaseNotes,
            @Nullable String apkDownloadUrl,
            @Nullable String apkAssetName,
            long apkSizeBytes,
            boolean prerelease,
            boolean updateAvailable
    ) {
        this.tagName = tagName;
        this.releaseName = releaseName;
        this.releaseUrl = releaseUrl;
        this.releaseNotes = releaseNotes;
        this.apkDownloadUrl = apkDownloadUrl;
        this.apkAssetName = apkAssetName;
        this.apkSizeBytes = apkSizeBytes;
        this.prerelease = prerelease;
        this.updateAvailable = updateAvailable;
    }

    @NonNull
    public String getDisplayVersion() {
        if (!releaseName.trim().isEmpty()) return releaseName.trim();
        return tagName.trim();
    }

    public boolean hasApkAsset() {
        return apkDownloadUrl != null && !apkDownloadUrl.trim().isEmpty();
    }

    @NonNull
    public String getDisplaySize() {
        if (apkSizeBytes <= 0) return "Unknown size";
        double mb = apkSizeBytes / 1024.0 / 1024.0;
        return String.format(java.util.Locale.US, "%.1f MB", mb);
    }
}

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

package ca.dnamobile.droidbridgelauncher.instance;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

public final class LauncherInstance {
    private final String id;
    private final String name;
    private final String loader;
    private final String baseVersionId;
    private final String minecraftVersionId;
    private final String versionType;
    private final File rootDirectory;
    private final File gameDirectory;
    @Nullable
    private final File iconFile;
    private final String createdAt;
    private final boolean isolated;

    LauncherInstance(
            @NonNull String id,
            @NonNull String name,
            @NonNull String loader,
            @NonNull String baseVersionId,
            @NonNull String versionType,
            @NonNull File rootDirectory,
            @NonNull File gameDirectory,
            @Nullable File iconFile,
            @NonNull String createdAt
    ) {
        this(id, name, loader, baseVersionId, baseVersionId, versionType, rootDirectory, gameDirectory, iconFile, createdAt, true);
    }

    LauncherInstance(
            @NonNull String id,
            @NonNull String name,
            @NonNull String loader,
            @NonNull String baseVersionId,
            @NonNull String minecraftVersionId,
            @NonNull String versionType,
            @NonNull File rootDirectory,
            @NonNull File gameDirectory,
            @Nullable File iconFile,
            @NonNull String createdAt
    ) {
        this(id, name, loader, baseVersionId, minecraftVersionId, versionType, rootDirectory, gameDirectory, iconFile, createdAt, true);
    }

    LauncherInstance(
            @NonNull String id,
            @NonNull String name,
            @NonNull String loader,
            @NonNull String baseVersionId,
            @NonNull String versionType,
            @NonNull File rootDirectory,
            @NonNull File gameDirectory,
            @Nullable File iconFile,
            @NonNull String createdAt,
            boolean isolated
    ) {
        this(id, name, loader, baseVersionId, baseVersionId, versionType, rootDirectory, gameDirectory, iconFile, createdAt, isolated);
    }

    LauncherInstance(
            @NonNull String id,
            @NonNull String name,
            @NonNull String loader,
            @NonNull String baseVersionId,
            @NonNull String minecraftVersionId,
            @NonNull String versionType,
            @NonNull File rootDirectory,
            @NonNull File gameDirectory,
            @Nullable File iconFile,
            @NonNull String createdAt,
            boolean isolated
    ) {
        this.id = id;
        this.name = name;
        this.loader = loader;
        this.baseVersionId = baseVersionId;
        this.minecraftVersionId = minecraftVersionId.trim().isEmpty() ? baseVersionId : minecraftVersionId;
        this.versionType = versionType;
        this.rootDirectory = rootDirectory;
        this.gameDirectory = gameDirectory;
        this.iconFile = iconFile;
        this.createdAt = createdAt;
        this.isolated = isolated;
    }

    @NonNull
    public static LauncherInstance sharedInstalledVersion(
            @NonNull String versionId,
            @NonNull String versionType,
            @NonNull File minecraftHome,
            @NonNull String releaseTime
    ) {
        return sharedInstalledVersion(versionId, versionType, minecraftHome, releaseTime, "Vanilla");
    }

    @NonNull
    public static LauncherInstance sharedInstalledVersion(
            @NonNull String versionId,
            @NonNull String versionType,
            @NonNull File minecraftHome,
            @NonNull String releaseTime,
            @NonNull String loader
    ) {
        return new LauncherInstance(
                sharedInstanceId(versionId, minecraftHome),
                versionId,
                loader,
                versionId,
                versionType,
                minecraftHome,
                minecraftHome,
                null,
                releaseTime,
                false
        );
    }

    @NonNull
    public static String sharedInstanceId(@NonNull String versionId, @NonNull File minecraftHome) {
        return "shared-" + Integer.toHexString(minecraftHome.getAbsolutePath().hashCode()) + "-" + versionId;
    }

    @NonNull
    public String getId() {
        return id;
    }

    @NonNull
    public String getName() {
        return name;
    }

    @NonNull
    public String getLoader() {
        return loader;
    }

    @NonNull
    public String getBaseVersionId() {
        return baseVersionId;
    }

    /**
     * Real Minecraft game version used for content APIs.
     *
     * This intentionally stays separate from baseVersionId because Forge/NeoForge
     * launch profile ids may use the user-facing instance name.
     */
    @NonNull
    public String getMinecraftVersionId() {
        return minecraftVersionId;
    }

    @NonNull
    public String getVersionType() {
        return versionType;
    }

    @NonNull
    public File getRootDirectory() {
        return rootDirectory;
    }

    @NonNull
    public File getGameDirectory() {
        return gameDirectory;
    }

    @Nullable
    public File getIconFile() {
        return iconFile;
    }

    @NonNull
    public String getCreatedAt() {
        return createdAt;
    }

    public boolean isIsolated() {
        return isolated;
    }
}

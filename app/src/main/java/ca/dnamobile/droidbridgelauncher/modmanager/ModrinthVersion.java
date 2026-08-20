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

package ca.dnamobile.droidbridgelauncher.modmanager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModrinthVersion {
    @NonNull
    public final String id;
    @NonNull
    public final String projectId;
    @NonNull
    public final String name;
    @NonNull
    public final String versionNumber;
    @NonNull
    public final String versionType;
    @Nullable
    public final String datePublished;
    @Nullable
    public final String changelog;
    public final long downloads;
    @NonNull
    public final List<String> gameVersions;
    @NonNull
    public final List<String> loaders;
    @NonNull
    public final List<ModrinthDependency> dependencies;
    @NonNull
    public final List<ModrinthFile> files;

    public ModrinthVersion(
            @NonNull String id,
            @NonNull String projectId,
            @NonNull String name,
            @NonNull String versionNumber,
            @NonNull String versionType,
            @Nullable String datePublished,
            @Nullable String changelog,
            long downloads,
            @NonNull List<String> gameVersions,
            @NonNull List<String> loaders,
            @NonNull List<ModrinthDependency> dependencies,
            @NonNull List<ModrinthFile> files
    ) {
        this.id = id;
        this.projectId = projectId;
        this.name = name;
        this.versionNumber = versionNumber;
        this.versionType = versionType;
        this.datePublished = datePublished;
        this.changelog = changelog;
        this.downloads = downloads;
        this.gameVersions = Collections.unmodifiableList(new ArrayList<>(gameVersions));
        this.loaders = Collections.unmodifiableList(new ArrayList<>(loaders));
        this.dependencies = Collections.unmodifiableList(new ArrayList<>(dependencies));
        this.files = Collections.unmodifiableList(new ArrayList<>(files));
    }

    @Nullable
    public ModrinthFile getPrimaryFile() {
        for (ModrinthFile file : files) {
            if (file.primary) return file;
        }
        return files.isEmpty() ? null : files.get(0);
    }
}

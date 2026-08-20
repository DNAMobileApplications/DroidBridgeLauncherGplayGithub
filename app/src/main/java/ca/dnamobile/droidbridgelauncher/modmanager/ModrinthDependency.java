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

public final class ModrinthDependency {
    @Nullable
    public final String versionId;
    @Nullable
    public final String projectId;
    @Nullable
    public final String fileName;
    @NonNull
    public final String dependencyType;

    public ModrinthDependency(
            @Nullable String versionId,
            @Nullable String projectId,
            @Nullable String fileName,
            @NonNull String dependencyType
    ) {
        this.versionId = emptyToNull(versionId);
        this.projectId = emptyToNull(projectId);
        this.fileName = emptyToNull(fileName);
        this.dependencyType = dependencyType;
    }

    public boolean isRequired() {
        return "required".equalsIgnoreCase(dependencyType);
    }

    @Nullable
    private static String emptyToNull(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) return null;
        return trimmed;
    }
}

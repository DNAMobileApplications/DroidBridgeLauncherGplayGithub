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

public final class ModrinthFile {
    @NonNull
    public final String url;
    @NonNull
    public final String filename;
    @Nullable
    public final String sha1;
    public final boolean primary;
    public final long size;

    public ModrinthFile(
            @NonNull String url,
            @NonNull String filename,
            @Nullable String sha1,
            boolean primary,
            long size
    ) {
        this.url = url;
        this.filename = filename;
        this.sha1 = sha1 == null || sha1.trim().isEmpty() ? null : sha1.trim();
        this.primary = primary;
        this.size = size;
    }
}

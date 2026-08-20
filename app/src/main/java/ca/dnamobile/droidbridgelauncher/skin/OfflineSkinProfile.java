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

package ca.dnamobile.droidbridgelauncher.skin;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

public final class OfflineSkinProfile {
    public final String uniqueUuid;
    @Nullable public final File skinFile;
    @NonNull public final SkinModelType model;
    public final boolean enabled;

    public OfflineSkinProfile(@NonNull String uniqueUuid,
                              @Nullable File skinFile,
                              @NonNull SkinModelType model,
                              boolean enabled) {
        this.uniqueUuid = uniqueUuid;
        this.skinFile = skinFile;
        this.model = model;
        this.enabled = enabled;
    }
}

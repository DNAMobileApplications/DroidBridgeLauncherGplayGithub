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

public enum SkinModelType {
    NONE("none"),
    CLASSIC("classic"),
    SLIM("slim");

    public final String id;

    SkinModelType(@NonNull String id) {
        this.id = id;
    }

    @NonNull
    public static SkinModelType fromId(@Nullable String id) {
        if (id == null) return CLASSIC;
        for (SkinModelType value : values()) {
            if (value.id.equalsIgnoreCase(id)) return value;
        }
        return CLASSIC;
    }
}

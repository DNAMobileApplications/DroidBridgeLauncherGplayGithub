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

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.R;

public enum ModManagerSource {
    MODRINTH("modrinth", "Modrinth", R.drawable.ic_source_modrinth_24),
    CURSEFORGE("curseforge", "CurseForge", R.drawable.ic_source_curseforge_24),
    MANUAL("manual", "Manual", 0),
    UNKNOWN("unknown", "Unknown", 0);

    @NonNull
    private final String id;
    @NonNull
    private final String displayName;
    @DrawableRes
    private final int iconRes;

    ModManagerSource(@NonNull String id, @NonNull String displayName, @DrawableRes int iconRes) {
        this.id = id;
        this.displayName = displayName;
        this.iconRes = iconRes;
    }

    @NonNull
    public String getId() {
        return id;
    }

    @NonNull
    public String getDisplayName() {
        return displayName;
    }

    @DrawableRes
    public int getIconRes() {
        return iconRes;
    }

    public boolean hasIcon() {
        return iconRes != 0;
    }

    @NonNull
    public static ModManagerSource fromId(@Nullable String value) {
        if (value == null) return UNKNOWN;
        String normalized = value.trim().toLowerCase(Locale.US);
        if (normalized.isEmpty()) return UNKNOWN;

        for (ModManagerSource source : values()) {
            if (source.id.equals(normalized) || source.name().toLowerCase(Locale.US).equals(normalized)) {
                return source;
            }
        }

        if (normalized.contains("modrinth")) return MODRINTH;
        if (normalized.contains("curseforge") || normalized.contains("curse_forge")) return CURSEFORGE;
        if (normalized.contains("manual")) return MANUAL;
        return UNKNOWN;
    }
}

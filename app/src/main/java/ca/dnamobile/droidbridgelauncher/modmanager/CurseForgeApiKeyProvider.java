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

import ca.dnamobile.droidbridgelauncher.BuildConfig;

public final class CurseForgeApiKeyProvider {
    private CurseForgeApiKeyProvider() {
    }

    @NonNull
    public static String resolve() {
        String key = BuildConfig.CURSEFORGE_API_KEY;
        return isRealKey(key) ? key.trim() : "";
    }

    private static boolean isRealKey(String value) {
        if (value == null) return false;

        String trimmed = value.trim();
        return !trimmed.isEmpty()
                && !"YOUR_CURSEFORGE_API_KEY".equalsIgnoreCase(trimmed)
                && !"PUT_YOUR_CURSEFORGE_API_KEY_HERE".equalsIgnoreCase(trimmed)
                && !trimmed.startsWith("REPLACE_");
    }
}
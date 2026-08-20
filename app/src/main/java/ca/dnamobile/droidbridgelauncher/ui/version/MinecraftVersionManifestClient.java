/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */
package ca.dnamobile.droidbridgelauncher.ui.version;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import ca.dnamobile.droidbridgelauncher.data.model.MinecraftVersion;

/**
 * Public-source placeholder. Production Minecraft version-manifest acquisition
 * is intentionally not included in this source snapshot.
 */
public final class MinecraftVersionManifestClient {
    private MinecraftVersionManifestClient() {
    }

    @NonNull
    public static List<MinecraftVersion> loadVersions(@NonNull Context context) throws Exception {
        return new ArrayList<>();
    }

    @NonNull
    public static String downloadText(@NonNull String urlString) throws Exception {
        return downloadText(null, urlString);
    }

    @NonNull
    public static String downloadText(@Nullable Context context, @NonNull String urlString) throws Exception {
        throw new UnsupportedOperationException("Minecraft version-manifest acquisition is not included in this public source snapshot.");
    }
}

/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */
package ca.dnamobile.droidbridgelauncher.network;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Public-source placeholder. Production game-file source selection is intentionally
 * not included in this source snapshot.
 */
public final class MinecraftDownloadSource {
    private MinecraftDownloadSource() {
    }

    @NonNull
    public static List<String> getCandidateUrls(@Nullable Context context, @NonNull String originalUrl) {
        ArrayList<String> urls = new ArrayList<>();
        if (!originalUrl.trim().isEmpty()) urls.add(originalUrl);
        return urls;
    }

    public static boolean isBmclApiUrl(@Nullable String url) {
        return false;
    }

    @Nullable
    public static String toBmclApiUrl(@Nullable String url) {
        return null;
    }
}

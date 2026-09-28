/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, BMCLAPI, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.network;

import android.content.Context;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import ca.dnamobile.droidbridgelauncher.data.AccountStore;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;

/**
 * Resolves Minecraft and supported loader download URLs through the optional
 * BMCLAPI mirror. The official URL is always retained as a fallback.
 *
 * BMCLAPI is a third-party service. Enabling it changes only where files are
 * downloaded from; it does not change account ownership/authentication rules.
 */
public final class MinecraftDownloadSource {
    public static final String BMCLAPI_ROOT = "https://bmclapi2.bangbang93.com";
    private static final long ACCOUNT_SESSION_CACHE_MS = 30_000L;
    private static volatile long accountSessionCheckedAt;
    private static volatile boolean accountSessionValid;

    private MinecraftDownloadSource() {
    }

    @NonNull
    public static List<String> getCandidateUrls(
            @Nullable Context context,
            @NonNull String originalUrl
    ) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String cleanUrl = originalUrl.trim();
        if (cleanUrl.isEmpty()) return new ArrayList<>();

        boolean useBmclApi = context != null
                && LauncherPreferences.isUseBmclApi(context)
                && hasValidMicrosoftMinecraftSession(context);
        if (!useBmclApi) {
            candidates.add(cleanUrl);
            return new ArrayList<>(candidates);
        }

        String mirrorUrl = toBmclApiUrl(cleanUrl);
        if (mirrorUrl != null) candidates.add(mirrorUrl);
        candidates.add(cleanUrl);

        String inferredOfficialUrl = inferOfficialUrlFromBmclApi(cleanUrl);
        if (inferredOfficialUrl != null) candidates.add(inferredOfficialUrl);

        return new ArrayList<>(candidates);
    }

    public static boolean isBmclApiUrl(@Nullable String url) {
        if (url == null) return false;
        return url.startsWith(BMCLAPI_ROOT + "/");
    }

    @Nullable
    public static String toBmclApiUrl(@Nullable String url) {
        if (url == null) return null;
        String value = url.trim();
        if (value.isEmpty()) return null;
        if (isBmclApiUrl(value)) return value;

        String mapped;

        mapped = replaceRoot(value, "https://piston-meta.mojang.com/", BMCLAPI_ROOT + "/");
        if (mapped != null) return mapped;
        mapped = replaceRoot(value, "https://launchermeta.mojang.com/", BMCLAPI_ROOT + "/");
        if (mapped != null) return mapped;

        mapped = replaceRoot(value, "https://piston-data.mojang.com/", BMCLAPI_ROOT + "/");
        if (mapped != null) return mapped;
        mapped = replaceRoot(value, "https://launcher.mojang.com/", BMCLAPI_ROOT + "/");
        if (mapped != null) return mapped;

        mapped = replaceRoot(value, "https://resources.download.minecraft.net/", BMCLAPI_ROOT + "/assets/");
        if (mapped != null) return mapped;
        mapped = replaceRoot(value, "http://resources.download.minecraft.net/", BMCLAPI_ROOT + "/assets/");
        if (mapped != null) return mapped;

        mapped = replaceRoot(value, "https://libraries.minecraft.net/", BMCLAPI_ROOT + "/maven/");
        if (mapped != null) return mapped;
        mapped = replaceRoot(value, "http://libraries.minecraft.net/", BMCLAPI_ROOT + "/maven/");
        if (mapped != null) return mapped;

        mapped = replaceRoot(value, "https://maven.minecraftforge.net/", BMCLAPI_ROOT + "/maven/");
        if (mapped != null) return mapped;
        mapped = replaceRoot(value, "https://files.minecraftforge.net/maven/", BMCLAPI_ROOT + "/maven/");
        if (mapped != null) return mapped;

        mapped = replaceRoot(value, "https://meta.fabricmc.net/", BMCLAPI_ROOT + "/fabric-meta/");
        if (mapped != null) return mapped;
        mapped = replaceRoot(value, "https://maven.fabricmc.net/", BMCLAPI_ROOT + "/maven/");
        if (mapped != null) return mapped;

        mapped = replaceRoot(value, "https://maven.neoforged.net/releases/", BMCLAPI_ROOT + "/maven/");
        if (mapped != null) return mapped;

        return null;
    }


    private static boolean hasValidMicrosoftMinecraftSession(@NonNull Context context) {
        long now = SystemClock.elapsedRealtime();
        if (now - accountSessionCheckedAt < ACCOUNT_SESSION_CACHE_MS) {
            return accountSessionValid;
        }

        synchronized (MinecraftDownloadSource.class) {
            now = SystemClock.elapsedRealtime();
            if (now - accountSessionCheckedAt < ACCOUNT_SESSION_CACHE_MS) {
                return accountSessionValid;
            }

            boolean valid = false;
            try {
                AccountStore.Account account = new AccountStore(context).loadLastMicrosoftAccount();
                valid = account != null && account.hasMinecraftSession();
            } catch (Throwable ignored) {
            }
            accountSessionValid = valid;
            accountSessionCheckedAt = now;
            return valid;
        }
    }

    @Nullable
    private static String inferOfficialUrlFromBmclApi(@NonNull String url) {
        if (!isBmclApiUrl(url)) return null;

        String relative = url.substring((BMCLAPI_ROOT + "/").length());
        if (relative.startsWith("mc/game/")) {
            return "https://piston-meta.mojang.com/" + relative;
        }
        if (relative.startsWith("v1/packages/")) {
            return "https://piston-meta.mojang.com/" + relative;
        }
        if (relative.startsWith("v1/objects/")) {
            return "https://piston-data.mojang.com/" + relative;
        }
        if (relative.startsWith("assets/")) {
            return "https://resources.download.minecraft.net/" + relative.substring("assets/".length());
        }
        if (relative.startsWith("fabric-meta/")) {
            return "https://meta.fabricmc.net/" + relative.substring("fabric-meta/".length());
        }
        if (relative.startsWith("maven/")) {
            // Minecraft's version metadata most commonly points here for libraries.
            // Installer-specific code also keeps its original Forge/Fabric/NeoForge
            // URL as a separate fallback candidate.
            return "https://libraries.minecraft.net/" + relative.substring("maven/".length());
        }
        return null;
    }

    @Nullable
    private static String replaceRoot(
            @NonNull String value,
            @NonNull String sourceRoot,
            @NonNull String targetRoot
    ) {
        if (!value.startsWith(sourceRoot)) return null;
        return targetRoot + value.substring(sourceRoot.length());
    }
}

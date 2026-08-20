/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */
package ca.dnamobile.droidbridgelauncher.instance;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Public-source placeholder for instance version/update integration.
 * Production Minecraft acquisition/update implementation is intentionally omitted.
 */
public final class InstanceVersionUpdater {
    private InstanceVersionUpdater() {
    }

    public enum LoaderKind {
        VANILLA("Vanilla"),
        FABRIC("Fabric"),
        FORGE("Forge"),
        NEOFORGE("NeoForge");

        @NonNull public final String displayName;
        LoaderKind(@NonNull String displayName) { this.displayName = displayName; }
    }

    public interface Listener {
        void onStatus(@NonNull String message);
        void onProgress(int current, int total);
    }

    public static final class MinecraftRelease {
        @NonNull public final String id;
        @NonNull public final String url;
        MinecraftRelease(@NonNull String id, @NonNull String url) {
            this.id = id;
            this.url = url;
        }
    }

    public static final class LoaderVersion {
        @NonNull public final LoaderKind kind;
        @NonNull public final String displayVersion;
        @NonNull public final String installVersion;
        @NonNull public final String minecraftVersion;
        public final boolean stable;

        LoaderVersion(@NonNull LoaderKind kind,
                      @NonNull String displayVersion,
                      @NonNull String installVersion,
                      @NonNull String minecraftVersion,
                      boolean stable) {
            this.kind = kind;
            this.displayVersion = displayVersion;
            this.installVersion = installVersion;
            this.minecraftVersion = minecraftVersion;
            this.stable = stable;
        }

        @NonNull
        public String getDisplayLabel() {
            return displayVersion;
        }

        @NonNull
        public String getDisplayLabel(@Nullable String currentLoaderVersion) {
            String label = getDisplayLabel();
            if (isSameLoaderVersion(displayVersion, currentLoaderVersion)
                    || isSameLoaderVersion(installVersion, currentLoaderVersion)) {
                label += " (current)";
            }
            return label;
        }
    }

    public static final class UpdateResult {
        @NonNull public final String loader;
        @NonNull public final String baseVersionId;
        @NonNull public final String minecraftVersionId;
        @NonNull public final String versionType;
        @Nullable public final String loaderVersion;
        public final int metadataFilesUpdated;

        UpdateResult(@NonNull String loader,
                     @NonNull String baseVersionId,
                     @NonNull String minecraftVersionId,
                     @NonNull String versionType,
                     @Nullable String loaderVersion,
                     int metadataFilesUpdated) {
            this.loader = loader;
            this.baseVersionId = baseVersionId;
            this.minecraftVersionId = minecraftVersionId;
            this.versionType = versionType;
            this.loaderVersion = loaderVersion;
            this.metadataFilesUpdated = metadataFilesUpdated;
        }
    }

    @NonNull
    public static ArrayList<MinecraftRelease> fetchMinecraftReleases() throws Exception {
        return new ArrayList<>();
    }

    @NonNull
    public static ArrayList<LoaderVersion> fetchLoaderVersions(@NonNull LoaderKind kind,
                                                               @NonNull String minecraftVersion) throws Exception {
        return new ArrayList<>();
    }

    @Nullable
    public static LoaderVersion findLatestLoaderVersion(@NonNull LoaderKind kind,
                                                        @NonNull String minecraftVersion) throws Exception {
        return null;
    }

    @Nullable
    public static String resolveCurrentLoaderVersion(@NonNull LoaderKind kind,
                                                     @Nullable String baseVersionId,
                                                     @NonNull String minecraftVersion) {
        return null;
    }

    public static boolean isSameLoaderVersion(@Nullable String first, @Nullable String second) {
        if (first == null || second == null) return false;
        String a = first.trim().toLowerCase(Locale.US);
        String b = second.trim().toLowerCase(Locale.US);
        return !a.isEmpty() && a.equals(b);
    }

    @NonNull
    public static UpdateResult updateInstanceVersion(@NonNull Context context,
                                                     @NonNull File rootDirectory,
                                                     @NonNull File gameDirectory,
                                                     @NonNull String instanceName,
                                                     @Nullable String currentLoader,
                                                     @NonNull String targetMinecraftVersion,
                                                     @Nullable Listener listener) throws Exception {
        throw new UnsupportedOperationException("Minecraft version acquisition/update is not included in this public source snapshot.");
    }

    @NonNull
    public static UpdateResult updateInstanceLoader(@NonNull Context context,
                                                    @NonNull File rootDirectory,
                                                    @NonNull File gameDirectory,
                                                    @NonNull String instanceName,
                                                    @NonNull LoaderVersion selectedLoader,
                                                    @Nullable Listener listener) throws Exception {
        throw new UnsupportedOperationException("Minecraft version acquisition/update is not included in this public source snapshot.");
    }

    @Nullable
    public static LoaderKind resolveLoaderKind(@Nullable String loader) {
        if (loader == null) return null;
        String value = loader.trim().toLowerCase(Locale.US).replace(" ", "").replace("_", "").replace("-", "");
        if (value.isEmpty()) return null;
        if (value.contains("vanilla")) return LoaderKind.VANILLA;
        if (value.contains("neoforge")) return LoaderKind.NEOFORGE;
        if (value.contains("fabric")) return LoaderKind.FABRIC;
        if (value.contains("forge")) return LoaderKind.FORGE;
        return null;
    }
}

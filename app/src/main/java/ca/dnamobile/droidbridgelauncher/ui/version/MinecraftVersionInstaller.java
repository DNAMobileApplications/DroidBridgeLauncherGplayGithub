/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */
package ca.dnamobile.droidbridgelauncher.ui.version;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ca.dnamobile.droidbridgelauncher.data.model.MinecraftVersion;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

/**
 * Public-source view of local Minecraft installation discovery.
 * Production game acquisition/download implementation is intentionally omitted.
 */
public final class MinecraftVersionInstaller {
    private static final String TAG = "MinecraftVersionInstaller";

    private MinecraftVersionInstaller() {
    }

    public interface InstallProgressListener {
        void onProgress(int progress, @NonNull String message);
    }

    public static void installVanillaVersion(
            @NonNull Context context,
            @NonNull MinecraftVersion version,
            @Nullable InstallProgressListener listener
    ) throws Exception {
        if (listener != null) listener.onProgress(0, "Game acquisition is not included in this public source snapshot.");
        throw new UnsupportedOperationException("Game acquisition is not included in this public source snapshot.");
    }

    public static void installVersionMetadata(@NonNull Context context, @NonNull MinecraftVersion version) throws Exception {
        installVanillaVersion(context, version, null);
    }

    @NonNull
    public static File getVersionsDirectory() {
        return getVersionsDirectory(new File(PathManager.DIR_MINECRAFT_HOME));
    }

    @NonNull
    public static File getVersionsDirectory(@NonNull File minecraftHome) {
        return new File(minecraftHome, "versions");
    }

    @NonNull
    public static List<MinecraftVersion> findInstalledVersions() {
        if (PathManager.DIR_MINECRAFT_HOME == null || PathManager.DIR_MINECRAFT_HOME.isBlank()) {
            return new ArrayList<>();
        }
        return findInstalledVersions(new File(PathManager.DIR_MINECRAFT_HOME));
    }

    @NonNull
    public static List<MinecraftVersion> findInstalledVersions(@NonNull File minecraftHome) {
        ArrayList<MinecraftVersion> results = new ArrayList<>();
        File versionsDir = getVersionsDirectory(minecraftHome);
        File[] children = versionsDir.listFiles();
        if (children == null) return results;

        for (File child : children) {
            if (!child.isDirectory()) continue;
            File metadata = new File(child, child.getName() + ".json");
            if (!metadata.isFile()) continue;

            String id = child.getName();
            String type = "installed";
            String releaseTime = "";
            try {
                JSONObject versionJson = new JSONObject(readString(metadata));
                if (!hasLaunchableClientJar(minecraftHome, id, versionJson)) continue;
                type = versionJson.optString("type", type);
                releaseTime = versionJson.optString("releaseTime", versionJson.optString("time", releaseTime));
            } catch (Throwable throwable) {
                Logging.i(TAG, "Unable to read installed version metadata for " + id + ": " + throwable.getMessage());
            }
            results.add(new MinecraftVersion(id, type, releaseTime, ""));
        }
        return results;
    }

    private static boolean hasLaunchableClientJar(@NonNull File minecraftHome,
                                                   @NonNull String versionId,
                                                   @NonNull JSONObject versionJson) {
        File ownJar = new File(getVersionDirectory(minecraftHome, versionId), versionId + ".jar");
        if (ownJar.isFile()) return true;

        String referencedJarId = versionJson.optString("jar", "").trim();
        if (!referencedJarId.isEmpty() && !referencedJarId.equals(versionId)) {
            File referencedJar = new File(getVersionDirectory(minecraftHome, referencedJarId), referencedJarId + ".jar");
            if (referencedJar.isFile()) return true;
        }

        String inheritsFrom = versionJson.optString("inheritsFrom", "").trim();
        if (!inheritsFrom.isEmpty()) {
            File parentJar = new File(getVersionDirectory(minecraftHome, inheritsFrom), inheritsFrom + ".jar");
            return parentJar.isFile();
        }
        return false;
    }

    @NonNull
    public static Set<String> findInstalledVersionIds() {
        HashSet<String> results = new HashSet<>();
        for (MinecraftVersion version : findInstalledVersions()) results.add(version.getId());
        return results;
    }

    @NonNull
    public static File getVersionDirectory(@NonNull String versionId) {
        return new File(getVersionsDirectory(), versionId);
    }

    @NonNull
    public static File getVersionDirectory(@NonNull File minecraftHome, @NonNull String versionId) {
        return new File(getVersionsDirectory(minecraftHome), versionId);
    }

    @NonNull
    public static File getLibrariesDirectory() {
        return new File(PathManager.DIR_MINECRAFT_HOME, "libraries");
    }

    @NonNull
    public static File getLibrariesDirectory(@NonNull File minecraftHome) {
        return new File(minecraftHome, "libraries");
    }

    @NonNull
    public static File getAssetsDirectory() {
        return new File(PathManager.DIR_MINECRAFT_HOME, "assets");
    }

    public static void ensureJnaNativesForLaunch(@NonNull String versionId, @NonNull JSONObject versionJson) {
        // Local launch compatibility remains visible; network acquisition is omitted.
    }

    @NonNull
    public static File getNativeExtractionDirectory(@NonNull String versionId) {
        return new File(PathManager.DIR_CACHE, "natives" + File.separator + versionId);
    }

    @NonNull
    public static String downloadText(@NonNull String urlString) throws Exception {
        return downloadText(null, urlString);
    }

    @NonNull
    public static String downloadText(@Nullable Context context, @NonNull String urlString) throws Exception {
        throw new UnsupportedOperationException("Game acquisition is not included in this public source snapshot.");
    }

    @NonNull
    private static String readString(@NonNull File file) throws Exception {
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}

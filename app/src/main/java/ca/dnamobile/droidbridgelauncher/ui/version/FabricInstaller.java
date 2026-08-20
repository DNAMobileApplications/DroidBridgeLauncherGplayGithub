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

package ca.dnamobile.droidbridgelauncher.ui.version;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.data.model.MinecraftVersion;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.network.MinecraftDownloadSource;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

/**
 * Java-only Fabric installer.
 *
 * Fabric is easier than Forge/NeoForge because Fabric Meta can generate the
 * launcher profile JSON directly:
 *
 * https://meta.fabricmc.net/v2/versions/loader/<mcVersion>/<loaderVersion>/profile/json
 *
 * That JSON inherits from the vanilla Minecraft version and adds Fabric Loader
 * libraries + main class. JavaLaunchBuilder must support inheritsFrom for this
 * to launch correctly.
 */
public final class FabricInstaller {
    private static final String TAG = "FabricInstaller";
    private static final String FABRIC_META = "https://meta.fabricmc.net/v2";
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_PARALLEL_DOWNLOADS = 4;

    private FabricInstaller() {
    }

    public static final class InstallResult {
        private final String minecraftVersionId;
        private final String loaderVersion;
        private final String fabricVersionId;

        private InstallResult(
                @NonNull String minecraftVersionId,
                @NonNull String loaderVersion,
                @NonNull String fabricVersionId
        ) {
            this.minecraftVersionId = minecraftVersionId;
            this.loaderVersion = loaderVersion;
            this.fabricVersionId = fabricVersionId;
        }

        @NonNull
        public String getMinecraftVersionId() {
            return minecraftVersionId;
        }

        @NonNull
        public String getLoaderVersion() {
            return loaderVersion;
        }

        @NonNull
        public String getFabricVersionId() {
            return fabricVersionId;
        }
    }

    @NonNull
    public static InstallResult installFabricVersion(
            @NonNull Context context,
            @NonNull MinecraftVersion vanillaVersion,
            @Nullable String requestedLoaderVersion,
            @Nullable MinecraftVersionInstaller.InstallProgressListener listener
    ) throws Exception {
        PathManager.initContextConstants(context);

        String minecraftVersionId = vanillaVersion.getId();

        if (!MinecraftVersionInstaller.findInstalledVersionIds().contains(minecraftVersionId)) {
            MinecraftVersionInstaller.installVanillaVersion(context, vanillaVersion, listener);
        }

        notify(listener, 72, "Resolving Fabric Loader for " + minecraftVersionId + "...");
        String loaderVersion = requestedLoaderVersion;
        if (loaderVersion == null || loaderVersion.trim().isEmpty()) {
            loaderVersion = fetchLatestStableLoaderVersion(context, minecraftVersionId);
        }

        notify(listener, 76, "Downloading Fabric profile " + loaderVersion + "...");
        String profileJsonString = downloadText(context, profileJsonUrl(minecraftVersionId, loaderVersion));
        JSONObject profileJson = new JSONObject(profileJsonString);

        String fabricVersionId = profileJson.optString(
                "id",
                "fabric-loader-" + loaderVersion + "-" + minecraftVersionId
        );

        File versionDir = MinecraftVersionInstaller.getVersionDirectory(fabricVersionId);
        ensureDirectory(versionDir);

        File versionJsonFile = new File(versionDir, fabricVersionId + ".json");
        writeString(versionJsonFile, profileJson.toString(2));

        notify(listener, 80, "Downloading Fabric libraries...");
        downloadLibraries(context, profileJson, listener);


        writeInstallMarker(fabricVersionId, minecraftVersionId, loaderVersion, profileJson);

        flattenInheritedProfileIfEnabled(context, fabricVersionId, listener);

        notify(listener, 96, "Fabric " + loaderVersion + " is ready.");
        return new InstallResult(minecraftVersionId, loaderVersion, fabricVersionId);
    }

    private static void flattenInheritedProfileIfEnabled(
            @NonNull Context context,
            @NonNull String installedVersionId,
            @Nullable MinecraftVersionInstaller.InstallProgressListener listener
    ) throws Exception {
        if (!LauncherPreferences.isRemoveInheritedVanillaAfterLoaderInstall(context)) return;

        notify(listener, 94, "Flattening loader profile...");
        InheritedVersionFlattener.FlattenResult flattenResult =
                InheritedVersionFlattener.flattenInstalledVersionProfile(context, installedVersionId);
        if (!flattenResult.flattened) return;

        InheritedVersionFlattener.ParentDeleteResult deleteResult =
                InheritedVersionFlattener.deleteFlattenedParentVersionIfSafe(context, installedVersionId);
        Logging.i(TAG, deleteResult.message);
        if (deleteResult.deleted && deleteResult.parentVersionId != null) {
            notify(listener, 95, "Removed inherited vanilla files: " + deleteResult.parentVersionId);
        }
    }

    @NonNull
    public static String inferLoaderNameFromVersionId(@NonNull String versionId) {
        String value = versionId.toLowerCase(Locale.ROOT);
        if (value.contains("fabric-loader") || value.startsWith("fabric-")) return "Fabric";
        return "Vanilla";
    }

    @NonNull
    private static String fetchLatestStableLoaderVersion(@NonNull Context context, @NonNull String minecraftVersionId) throws Exception {
        String url = FABRIC_META + "/versions/loader/" + encode(minecraftVersionId);
        JSONArray array = new JSONArray(downloadText(context, url));

        String first = "";
        for (int i = 0; i < array.length(); i++) {
            JSONObject wrapper = array.getJSONObject(i);
            JSONObject loader = wrapper.optJSONObject("loader");
            if (loader == null) loader = wrapper;

            String version = loader.optString("version", "");
            if (version.isEmpty()) continue;
            if (first.isEmpty()) first = version;

            if (loader.optBoolean("stable", false)) {
                return version;
            }
        }

        if (!first.isEmpty()) return first;
        throw new IllegalStateException("No Fabric Loader versions are available for " + minecraftVersionId);
    }

    @NonNull
    private static String profileJsonUrl(@NonNull String minecraftVersionId, @NonNull String loaderVersion) throws Exception {
        return FABRIC_META
                + "/versions/loader/"
                + encode(minecraftVersionId)
                + "/"
                + encode(loaderVersion)
                + "/profile/json";
    }

    @NonNull
    private static String encode(@NonNull String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static void downloadLibraries(
            @NonNull Context context,
            @NonNull JSONObject profileJson,
            @Nullable MinecraftVersionInstaller.InstallProgressListener listener
    ) throws Exception {
        JSONArray libraries = profileJson.optJSONArray("libraries");
        if (libraries == null || libraries.length() == 0) return;

        ArrayList<DownloadEntry> downloads = new ArrayList<>();
        for (int i = 0; i < libraries.length(); i++) {
            JSONObject library = libraries.optJSONObject(i);
            if (library == null) continue;

            DownloadEntry entry = createLibraryDownloadEntry(library);
            if (entry != null) downloads.add(entry);
        }

        if (downloads.isEmpty()) return;

        final int total = Math.max(1, downloads.size());
        ParallelDownloadExecutor.run(
                downloads,
                MAX_PARALLEL_DOWNLOADS,
                entry -> downloadFileIfNeeded(context, entry),
                (completed, ignoredTotal, entry) -> {
                    int progress = 80 + (int) ((completed * 12L) / total);
                    notify(listener, progress, "Downloading Fabric libraries (" + completed + "/" + total + "): " + entry.displayName);
                }
        );
    }

    @Nullable
    private static DownloadEntry createLibraryDownloadEntry(@NonNull JSONObject library) {
        String name = library.optString("name", "");
        if (name.startsWith("org.lwjgl:")) {
            // DroidBridge uses its patched Android LWJGL pack.
            return null;
        }

        JSONObject downloads = library.optJSONObject("downloads");
        JSONObject artifact = downloads != null ? downloads.optJSONObject("artifact") : null;

        String path;
        String url;
        String sha1 = null;
        long size = 0L;

        if (artifact != null) {
            path = artifact.optString("path", "");
            url = artifact.optString("url", "");
            sha1 = emptyToNull(artifact.optString("sha1", null));
            size = artifact.optLong("size", 0L);
            if ((url == null || url.isEmpty()) && path != null && !path.isEmpty()) {
                url = buildLibraryUrl(library.optString("url", ""), path);
            }
        } else {
            path = artifactPathFromName(name);
            url = path == null ? null : buildLibraryUrl(library.optString("url", ""), path);
        }

        if (path == null || path.isEmpty() || url == null || url.isEmpty()) return null;

        File target = new File(MinecraftVersionInstaller.getLibrariesDirectory(), path);
        return new DownloadEntry(target, url, sha1, size, target.getName());
    }

    @Nullable
    private static String artifactPathFromName(@NonNull String name) {
        String[] parts = name.split(":");
        if (parts.length < 3) return null;

        String groupPath = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length >= 4 ? "-" + parts[3] : "";
        return groupPath + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + ".jar";
    }

    @NonNull
    private static String buildLibraryUrl(@Nullable String repository, @NonNull String path) {
        String repo = repository == null || repository.isEmpty() ? "https://maven.fabricmc.net/" : repository;
        if (repo.startsWith("http://")) repo = "https://" + repo.substring("http://".length());
        if (!repo.endsWith("/")) repo += "/";
        return repo + path;
    }

    private static void writeInstallMarker(
            @NonNull String fabricVersionId,
            @NonNull String minecraftVersionId,
            @NonNull String loaderVersion,
            @NonNull JSONObject profileJson
    ) throws Exception {
        File versionDir = MinecraftVersionInstaller.getVersionDirectory(fabricVersionId);

        JSONObject marker = new JSONObject();
        marker.put("id", fabricVersionId);
        marker.put("loader", "Fabric");
        marker.put("minecraftVersion", minecraftVersionId);
        marker.put("loaderVersion", loaderVersion);
        marker.put("inheritsFrom", profileJson.optString("inheritsFrom", minecraftVersionId));
        marker.put("versionDir", versionDir.getAbsolutePath());
        marker.put("librariesDir", MinecraftVersionInstaller.getLibrariesDirectory().getAbsolutePath());
        marker.put("installStage", "fabric_profile_ready");

        writeString(new File(versionDir, "java_launcher_fabric_install_marker.json"), marker.toString(2));
    }

    private static void downloadFileIfNeeded(
            @NonNull Context context,
            @NonNull DownloadEntry entry
    ) throws Exception {
        if (entry.targetFile.isFile()) {
            if (entry.sha1 == null || sha1(entry.targetFile).equalsIgnoreCase(entry.sha1)) {
                return;
            }
            Logging.i(TAG, "Existing file hash mismatch, redownloading: " + entry.targetFile.getAbsolutePath());
        }

        File parent = entry.targetFile.getParentFile();
        if (parent != null) ensureDirectory(parent);

        File tempFile = new File(entry.targetFile.getAbsolutePath() + ".part");
        Exception lastError = null;
        for (String candidateUrl : MinecraftDownloadSource.getCandidateUrls(context, entry.url)) {
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
            try {
                downloadToFileExact(candidateUrl, tempFile);
                if (entry.sha1 != null && !sha1(tempFile).equalsIgnoreCase(entry.sha1)) {
                    throw new IllegalStateException("SHA-1 mismatch for " + entry.displayName
                            + " from " + candidateUrl);
                }

                if (entry.targetFile.exists() && !entry.targetFile.delete()) {
                    throw new IllegalStateException("Unable to replace " + entry.targetFile.getAbsolutePath());
                }

                if (!tempFile.renameTo(entry.targetFile)) {
                    copyFile(tempFile, entry.targetFile);
                    //noinspection ResultOfMethodCallIgnored
                    tempFile.delete();
                }
                return;
            } catch (Exception error) {
                lastError = error;
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
                Logging.i(TAG, "Fabric download source failed for " + candidateUrl
                        + ": " + error.getMessage());
            }
        }

        if (lastError != null) throw lastError;
        throw new IllegalStateException("No download source available for " + entry.url);
    }

    @NonNull
    private static String downloadText(@NonNull Context context, @NonNull String urlString) throws Exception {
        Exception lastError = null;
        for (String candidateUrl : MinecraftDownloadSource.getCandidateUrls(context, urlString)) {
            HttpURLConnection connection = null;
            try {
                connection = openConnection(candidateUrl);
                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream();
                String body = readStream(stream);
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("HTTP " + code + " while downloading " + candidateUrl + " " + body);
                }
                return body;
            } catch (Exception error) {
                lastError = error;
                Logging.i(TAG, "Fabric metadata source failed for " + candidateUrl
                        + ": " + error.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
        }

        if (lastError != null) throw lastError;
        throw new IllegalStateException("No download source available for " + urlString);
    }

    private static void downloadToFileExact(@NonNull String urlString, @NonNull File target) throws Exception {
        HttpURLConnection connection = openConnection(urlString);
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            String error = readStream(connection.getErrorStream());
            connection.disconnect();
            throw new IllegalStateException("HTTP " + code + " while downloading " + urlString + " " + error);
        }

        try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        } finally {
            connection.disconnect();
        }
    }

    @NonNull
    private static HttpURLConnection openConnection(@NonNull String urlString) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(45000);
        connection.setRequestProperty("User-Agent", "DroidBridge/1.0");
        connection.setRequestMethod("GET");
        return connection;
    }

    @NonNull
    private static String readStream(@Nullable InputStream inputStream) throws Exception {
        if (inputStream == null) return "";
        try (InputStream input = inputStream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void writeString(@NonNull File file, @NonNull String value) throws Exception {
        File parent = file.getParentFile();
        if (parent != null) ensureDirectory(parent);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void copyFile(@NonNull File source, @NonNull File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null) ensureDirectory(parent);
        try (InputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static void ensureDirectory(@NonNull File directory) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Unable to create directory: " + directory.getAbsolutePath());
        }
    }

    @NonNull
    private static String sha1(@NonNull File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }

        byte[] bytes = digest.digest();
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.ROOT, "%02x", value));
        }
        return builder.toString();
    }

    @Nullable
    private static String emptyToNull(@Nullable String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static void notify(
            @Nullable MinecraftVersionInstaller.InstallProgressListener listener,
            int progress,
            @NonNull String message
    ) {
        if (listener != null) listener.onProgress(Math.max(0, Math.min(100, progress)), message);
    }

    private static final class DownloadEntry {
        final File targetFile;
        final String url;
        final String sha1;
        final long size;
        final String displayName;

        private DownloadEntry(
                @NonNull File targetFile,
                @NonNull String url,
                @Nullable String sha1,
                long size,
                @NonNull String displayName
        ) {
            this.targetFile = targetFile;
            this.url = url;
            this.sha1 = sha1;
            this.size = Math.max(0L, size);
            this.displayName = displayName;
        }
    }
}
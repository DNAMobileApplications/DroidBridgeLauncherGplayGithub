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
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import ca.dnamobile.droidbridgelauncher.data.model.MinecraftVersion;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.network.MinecraftDownloadSource;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

public final class MinecraftVersionInstaller {
    private static final String TAG = "VanillaInstaller";
    private static final String RESOURCE_BASE_URL = "https://resources.download.minecraft.net/";
    private static final int DOWNLOAD_BUFFER_SIZE = 64 * 1024;
    private static final int MAX_PARALLEL_DOWNLOADS = ParallelDownloadExecutor.defaultNetworkThreads();

    private MinecraftVersionInstaller() {
    }

    public interface InstallProgressListener {
        void onProgress(int progress, @NonNull String message);
    }

    /**
     * Installs a complete vanilla Minecraft version enough for the next launch step:
     * - .minecraft/versions/<id>/<id>.json
     * - .minecraft/versions/<id>/<id>.jar
     * - .minecraft/libraries/**
     * - app-private files/assets/indexes/** and files/assets/objects/**
     */
    public static void installVanillaVersion(
            @NonNull Context context,
            @NonNull MinecraftVersion version,
            @Nullable InstallProgressListener listener
    ) throws Exception {
        PathManager.initContextConstants(context);

        notifyProgress(listener, 0, "Preparing " + version.getId() + "...");

        File versionDir = getVersionDirectory(version.getId());
        File versionJsonFile = new File(versionDir, version.getId() + ".json");
        File clientJarFile = new File(versionDir, version.getId() + ".jar");
        ensureDirectory(versionDir);

        String metadataJson = downloadText(context, version.getMetadataUrl());
        writeString(versionJsonFile, metadataJson);

        JSONObject versionJson = new JSONObject(metadataJson);
        String assetIndexId = installAssetIndexAndBuildAssetDownloads(context, listener, versionJson, 5, 12);

        ArrayList<DownloadEntry> entries = new ArrayList<>();
        ArrayList<File> nativeAars = new ArrayList<>();
        collectClientJar(entries, versionJson, clientJarFile);
        collectLibraries(entries, nativeAars, versionJson);

        int beforeAssetsCount = entries.size();
        collectAssets(entries, getAssetIndexFile(assetIndexId));
        entries = deduplicateDownloadEntries(entries);

        final int gameFileCount = beforeAssetsCount;
        final int totalFiles = Math.max(1, entries.size());

        notifyProgress(listener, 12, "Downloading " + entries.size() + " Minecraft files...");
        ParallelDownloadExecutor.run(
                entries,
                MAX_PARALLEL_DOWNLOADS,
                entry -> downloadFileIfNeededWithRetry(context, entry),
                (completedFiles, total, entry) -> {
                    int progress = 12 + (int) ((completedFiles * 83L) / totalFiles);
                    String section = completedFiles <= gameFileCount ? "Downloading game files" : "Downloading assets";
                    notifyProgress(listener, progress, section + " (" + completedFiles + "/" + totalFiles + "): " + entry.displayName);
                }
        );

        extractNativeAars(nativeAars, version.getId(), listener);

        writeInstallMarker(version, versionJson, assetIndexId, entries.size(), clientJarFile);
        writeLaunchPlan(version, versionJson, assetIndexId, clientJarFile);

        notifyProgress(listener, 100, "Vanilla " + version.getId() + " installed.");
    }

    /**
     * Kept for compatibility with the first UI patch name. It now performs the full vanilla install.
     */
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

                if (!hasLaunchableClientJar(id, versionJson)) {
                    continue;
                }

                type = versionJson.optString("type", type);
                releaseTime = versionJson.optString(
                        "releaseTime",
                        versionJson.optString("time", releaseTime)
                );
            } catch (Throwable throwable) {
                Logging.i(TAG, "Unable to read installed version metadata for " + id + ": " + throwable.getMessage());
            }

            // Installed entries are local-only. Leave metadataUrl blank so the UI does not
            // try to install them again from the Installed tab.
            results.add(new MinecraftVersion(id, type, releaseTime, ""));
        }

        return results;
    }

    private static boolean hasLaunchableClientJar(@NonNull String versionId, @NonNull JSONObject versionJson) {
        File ownJar = new File(getVersionDirectory(versionId), versionId + ".jar");
        if (ownJar.isFile()) return true;

        String referencedJarId = versionJson.optString("jar", "").trim();
        if (!referencedJarId.isEmpty() && !referencedJarId.equals(versionId)) {
            File referencedJar = new File(getVersionDirectory(referencedJarId), referencedJarId + ".jar");
            return referencedJar.isFile();
        }

        String inheritsFrom = versionJson.optString("inheritsFrom", "").trim();
        if (!inheritsFrom.isEmpty()) {
            File parentJar = new File(getVersionDirectory(inheritsFrom), inheritsFrom + ".jar");
            return parentJar.isFile();
        }

        if (CleanroomSupport.isCleanroomProfile(versionId, versionJson)) {
            File cleanroomBaseJar = new File(
                    getVersionDirectory(CleanroomSupport.MINECRAFT_VERSION),
                    CleanroomSupport.MINECRAFT_VERSION + ".jar"
            );
            return cleanroomBaseJar.isFile();
        }

        return false;
    }

    @NonNull
    public static Set<String> findInstalledVersionIds() {
        HashSet<String> results = new HashSet<>();
        for (MinecraftVersion version : findInstalledVersions()) {
            results.add(version.getId());
        }
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
        // This is intentionally app-private and not under .minecraft.
        return new File(PathManager.DIR_MINECRAFT_HOME, "assets");
    }

    @NonNull
    private static String installAssetIndexAndBuildAssetDownloads(
            @NonNull Context context,
            @Nullable InstallProgressListener listener,
            @NonNull JSONObject versionJson,
            int startProgress,
            int endProgress
    ) throws Exception {
        JSONObject assetIndex = versionJson.optJSONObject("assetIndex");
        String fallbackId = versionJson.optString("assets", "legacy");
        if (assetIndex == null) {
            notifyProgress(listener, endProgress, "No asset index listed for this version.");
            return fallbackId;
        }

        String id = assetIndex.optString("id", fallbackId);
        String url = assetIndex.optString("url", "");
        String sha1 = assetIndex.optString("sha1", null);
        if (url.isEmpty()) {
            notifyProgress(listener, endProgress, "No asset index URL listed for this version.");
            return id;
        }

        File target = getAssetIndexFile(id);
        notifyProgress(listener, startProgress, "Downloading asset index " + id + "...");
        downloadFileIfNeeded(context, new DownloadEntry(target, url, emptyToNull(sha1), assetIndex.optLong("size", 0L), "asset index " + id, false));
        notifyProgress(listener, endProgress, "Asset index " + id + " is ready.");
        return id;
    }

    @NonNull
    private static File getAssetIndexFile(@NonNull String assetIndexId) {
        return new File(getAssetsDirectory(), "indexes" + File.separator + assetIndexId + ".json");
    }

    private static void collectClientJar(
            @NonNull List<DownloadEntry> entries,
            @NonNull JSONObject versionJson,
            @NonNull File clientJarFile
    ) throws Exception {
        JSONObject downloads = versionJson.optJSONObject("downloads");
        if (downloads == null) {
            throw new IllegalStateException("Version JSON has no downloads block.");
        }

        JSONObject client = downloads.optJSONObject("client");
        if (client == null) {
            throw new IllegalStateException("Version JSON has no client download block.");
        }

        String url = client.optString("url", "");
        if (url.isEmpty()) {
            throw new IllegalStateException("Version JSON has no client jar URL.");
        }

        entries.add(new DownloadEntry(
                clientJarFile,
                url,
                emptyToNull(client.optString("sha1", null)),
                client.optLong("size", 0L),
                clientJarFile.getName(),
                false
        ));
    }

    private static void collectLibraries(
            @NonNull List<DownloadEntry> entries,
            @NonNull List<File> nativeAars,
            @NonNull JSONObject versionJson
    ) throws Exception {
        JSONArray libraries = versionJson.optJSONArray("libraries");
        if (libraries == null) return;

        File librariesDir = getLibrariesDirectory();
        Set<String> seenTargets = new HashSet<>();

        for (int i = 0; i < libraries.length(); i++) {
            JSONObject library = libraries.optJSONObject(i);
            if (library == null) continue;

            String name = library.optString("name", "");
            if (name.startsWith("org.lwjgl:")) {
                // DroidBridge ships patched Android LWJGL packs from assets/components/lwjgl*.
                continue;
            }

            if (!isAllowedByRules(library.optJSONArray("rules"))) {
                continue;
            }

            if (name.startsWith("net.java.dev.jna:jna:")) {
                scheduleNativeAarDownload(entries, nativeAars, name);
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

            if (path == null || path.isEmpty() || url == null || url.isEmpty()) continue;

            File target = new File(librariesDir, path);
            String key = target.getCanonicalPath();
            if (!seenTargets.add(key)) continue;

            entries.add(new DownloadEntry(target, url, sha1, size, target.getName(), true));
        }
    }


    /**
     * Launch-time repair for existing installs. If a version was installed before
     * DroidBridge learned to download/extract the JNA AAR, this fixes it before launch.
     */
    public static void ensureJnaNativesForLaunch(
            @NonNull String versionId,
            @NonNull JSONObject versionJson
    ) throws Exception {
        File nativeDir = getNativeExtractionDirectory(versionId);
        File dispatch = new File(nativeDir, "libjnidispatch.so");
        if (dispatch.isFile()) {
            Logging.i(TAG, "JNA dispatch already extracted: " + dispatch.getAbsolutePath());
            return;
        }

        JSONArray libraries = versionJson.optJSONArray("libraries");
        if (libraries == null) return;

        ArrayList<DownloadEntry> entries = new ArrayList<>();
        ArrayList<File> nativeAars = new ArrayList<>();
        for (int i = 0; i < libraries.length(); i++) {
            JSONObject library = libraries.optJSONObject(i);
            if (library == null) continue;

            String name = library.optString("name", "");
            if (!name.startsWith("net.java.dev.jna:jna:")) continue;
            if (!isAllowedByRules(library.optJSONArray("rules"))) continue;

            scheduleNativeAarDownload(entries, nativeAars, name);
        }

        for (DownloadEntry entry : entries) {
            downloadFileIfNeeded(null, entry);
        }

        extractNativeAars(nativeAars, versionId, null);
    }

    private static void scheduleNativeAarDownload(
            @NonNull List<DownloadEntry> entries,
            @NonNull List<File> nativeAars,
            @NonNull String libraryName
    ) throws Exception {
        String aarPath = artifactPathFromNameWithExtension(libraryName, ".aar");
        if (aarPath == null || aarPath.isEmpty()) return;

        File target = new File(getLibrariesDirectory(), aarPath);
        if (!nativeAars.contains(target)) {
            nativeAars.add(target);
        }

        entries.add(new DownloadEntry(
                target,
                "https://repo1.maven.org/maven2/" + aarPath,
                null,
                0L,
                target.getName(),
                true
        ));
    }

    private static void extractNativeAars(
            @NonNull List<File> nativeAars,
            @NonNull String versionId,
            @Nullable InstallProgressListener listener
    ) throws Exception {
        if (nativeAars.isEmpty()) return;

        File targetDirectory = getNativeExtractionDirectory(versionId);
        ensureDirectory(targetDirectory);

        NativesExtractor extractor = new NativesExtractor(targetDirectory);
        int extracted = 0;

        for (File source : nativeAars) {
            extracted++;
            notifyProgress(listener, 95, "Extracting native libraries (" + extracted + "/" + nativeAars.size() + "): " + source.getName());

            if (!source.isFile()) {
                Logging.i(TAG, "Skipping missing native AAR: " + source.getAbsolutePath());
                continue;
            }

            extractor.extractFromAar(source);
            Logging.i(TAG, "Extracted native AAR: " + source.getAbsolutePath()
                    + " -> " + targetDirectory.getAbsolutePath());
        }
    }

    @NonNull
    public static File getNativeExtractionDirectory(@NonNull String versionId) {
        return new File(PathManager.DIR_CACHE, "natives" + File.separator + versionId);
    }

    private static void collectAssets(@NonNull List<DownloadEntry> entries, @NonNull File assetIndexFile) throws Exception {
        if (!assetIndexFile.isFile()) return;

        JSONObject assetIndexJson = new JSONObject(readString(assetIndexFile));
        JSONObject objects = assetIndexJson.optJSONObject("objects");
        if (objects == null) return;

        boolean virtual = assetIndexJson.optBoolean("virtual", false);
        boolean mapToResources = assetIndexJson.optBoolean("map_to_resources", false);
        String indexId = stripJsonExtension(assetIndexFile.getName());

        JSONArray names = objects.names();
        if (names == null) return;

        for (int i = 0; i < names.length(); i++) {
            String assetName = names.optString(i, "");
            JSONObject asset = objects.optJSONObject(assetName);
            if (asset == null) continue;

            String hash = asset.optString("hash", "");
            if (hash.length() < 2) continue;

            String hashedPath = hash.substring(0, 2) + File.separator + hash;
            File target;
            if (virtual) {
                target = new File(getAssetsDirectory(), "virtual" + File.separator + indexId + File.separator + assetName);
            } else if (mapToResources) {
                target = new File(getAssetsDirectory(), "resources" + File.separator + assetName);
            } else {
                target = new File(getAssetsDirectory(), "objects" + File.separator + hashedPath);
            }

            entries.add(new DownloadEntry(
                    target,
                    RESOURCE_BASE_URL + hashedPath.replace(File.separatorChar, '/'),
                    hash,
                    asset.optLong("size", 0L),
                    assetName,
                    false
            ));
        }
    }

    private static boolean isAllowedByRules(@Nullable JSONArray rules) {
        if (rules == null || rules.length() == 0) return true;

        boolean allowed = false;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule == null) continue;

            JSONObject os = rule.optJSONObject("os");
            if (os != null) {
                String name = os.optString("name", "");
                if (!name.isEmpty() && !"linux".equals(name)) {
                    continue;
                }
            }

            String action = rule.optString("action", "allow");
            allowed = "allow".equals(action);
        }
        return allowed;
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

    @Nullable
    private static String artifactPathFromNameWithExtension(@NonNull String name, @NonNull String extension) {
        String[] parts = name.split(":");
        if (parts.length < 3) return null;

        String groupPath = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length >= 4 ? "-" + parts[3] : "";
        return groupPath + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + extension;
    }

    @NonNull
    private static String buildLibraryUrl(@Nullable String repository, @NonNull String path) {
        String repo = repository == null || repository.isEmpty() ? "https://libraries.minecraft.net/" : repository;
        if (repo.startsWith("http://")) repo = "https://" + repo.substring("http://".length());
        if (!repo.endsWith("/")) repo += "/";
        return repo + path;
    }

    private static void writeInstallMarker(
            @NonNull MinecraftVersion manifestVersion,
            @NonNull JSONObject versionJson,
            @NonNull String assetIndexId,
            int downloadCount,
            @NonNull File clientJarFile
    ) throws Exception {
        JSONObject marker = new JSONObject();
        marker.put("id", manifestVersion.getId());
        marker.put("type", manifestVersion.getType());
        marker.put("releaseTime", manifestVersion.getReleaseTime());
        marker.put("metadataUrl", manifestVersion.getMetadataUrl());
        marker.put("installStage", "vanilla_full");
        marker.put("downloadCount", downloadCount);
        marker.put("versionDir", getVersionDirectory(manifestVersion.getId()).getAbsolutePath());
        marker.put("clientJar", clientJarFile.getAbsolutePath());
        marker.put("librariesDir", getLibrariesDirectory().getAbsolutePath());
        marker.put("assetsDir", getAssetsDirectory().getAbsolutePath());
        marker.put("assetIndex", assetIndexId);
        marker.put("mainClass", versionJson.optString("mainClass", ""));
        marker.put("note", "Vanilla files are installed. Next step is wiring launch arguments/JVM startup.");
        writeString(new File(getVersionDirectory(manifestVersion.getId()), "java_launcher_install_marker.json"), marker.toString(2));
    }

    private static void writeLaunchPlan(
            @NonNull MinecraftVersion manifestVersion,
            @NonNull JSONObject versionJson,
            @NonNull String assetIndexId,
            @NonNull File clientJarFile
    ) throws Exception {
        JSONObject plan = new JSONObject();
        plan.put("id", manifestVersion.getId());
        plan.put("type", manifestVersion.getType());
        plan.put("mainClass", versionJson.optString("mainClass", ""));
        plan.put("versionJson", new File(getVersionDirectory(manifestVersion.getId()), manifestVersion.getId() + ".json").getAbsolutePath());
        plan.put("clientJar", clientJarFile.getAbsolutePath());
        plan.put("versionDir", getVersionDirectory(manifestVersion.getId()).getAbsolutePath());
        plan.put("librariesDir", getLibrariesDirectory().getAbsolutePath());
        plan.put("assetsDir", getAssetsDirectory().getAbsolutePath());
        plan.put("assetIndex", assetIndexId);
        plan.put("gameDirectory", PathManager.DIR_MINECRAFT_HOME);
        plan.put("javaLauncherStage", "vanilla_files_ready");
        writeString(new File(getVersionDirectory(manifestVersion.getId()), "java_launcher_launch_plan.json"), plan.toString(2));
    }


    private static void downloadFileIfNeededWithRetry(@Nullable Context context, @NonNull DownloadEntry entry) throws Exception {
        Exception last = null;
        int attempts = entry.optional ? 2 : 4;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                downloadFileIfNeeded(context, entry);
                return;
            } catch (Exception e) {
                last = e;

                if (attempt >= attempts) break;

                long sleepMs = 500L * attempt;
                Logging.i(TAG, "Download failed for " + entry.displayName
                        + " attempt " + attempt + "/" + attempts
                        + ": " + e.getMessage()
                        + ". Retrying in " + sleepMs + "ms");
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }

        if (last != null) throw last;
    }

    private static void downloadFileIfNeeded(@Nullable Context context, @NonNull DownloadEntry entry) throws Exception {
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
                Logging.i(TAG, "Download source failed for " + entry.displayName
                        + " from " + candidateUrl + ": " + error.getMessage());
            }
        }

        if (entry.optional) {
            Logging.i(TAG, "Skipping optional download: " + entry.url
                    + " because " + (lastError != null ? lastError.getMessage() : "no source was available"));
            return;
        }
        if (lastError != null) throw lastError;
        throw new IllegalStateException("No download source available for " + entry.url);
    }

    @NonNull
    private static ArrayList<DownloadEntry> deduplicateDownloadEntries(@NonNull ArrayList<DownloadEntry> entries) {
        ArrayList<DownloadEntry> result = new ArrayList<>(entries.size());
        HashSet<String> seenTargets = new HashSet<>();

        for (DownloadEntry entry : entries) {
            String key;
            try {
                key = entry.targetFile.getCanonicalPath();
            } catch (Throwable ignored) {
                key = entry.targetFile.getAbsolutePath();
            }

            if (seenTargets.add(key)) {
                result.add(entry);
            }
        }

        return result;
    }

    @NonNull
    public static String downloadText(@NonNull String urlString) throws Exception {
        return downloadText(null, urlString);
    }

    @NonNull
    public static String downloadText(@Nullable Context context, @NonNull String urlString) throws Exception {
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
                    throw new IllegalStateException("HTTP " + code + " while downloading " + candidateUrl);
                }
                return body;
            } catch (Exception error) {
                lastError = error;
                Logging.i(TAG, "Text download source failed for " + candidateUrl + ": " + error.getMessage());
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
            byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE];
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
            byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    @NonNull
    private static String readString(@NonNull File file) throws Exception {
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE];
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
            byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE];
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
            byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE];
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

    @NonNull
    private static String stripJsonExtension(@NonNull String fileName) {
        return fileName.endsWith(".json") ? fileName.substring(0, fileName.length() - 5) : fileName;
    }

    private static void notifyProgress(@Nullable InstallProgressListener listener, int progress, @NonNull String message) {
        if (listener != null) listener.onProgress(Math.max(0, Math.min(100, progress)), message);
    }

    private static final class DownloadEntry {
        final File targetFile;
        final String url;
        final String sha1;
        final long size;
        final String displayName;
        final boolean optional;

        private DownloadEntry(
                @NonNull File targetFile,
                @NonNull String url,
                @Nullable String sha1,
                long size,
                @NonNull String displayName,
                boolean optional
        ) {
            this.targetFile = targetFile;
            this.url = url;
            this.sha1 = sha1;
            this.size = Math.max(0L, size);
            this.displayName = displayName;
            this.optional = optional;
        }
    }
}

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
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import ca.dnamobile.droidbridgelauncher.data.model.MinecraftVersion;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.network.MinecraftDownloadSource;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.launcher.DroidBridgeGameProcessLauncher;
import ca.dnamobile.droidbridgelauncher.utils.path.LibPath;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;
import ca.dnamobile.droidbridgelauncher.runtime.multirt.MultiRTUtils;

public final class ForgeInstaller {
    private static final String TAG = "ForgeInstaller";
    private static final String FORGE_METADATA_URL = "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml";
    private static final String FORGE_INSTALLER_URL = "https://maven.minecraftforge.net/net/minecraftforge/forge/%1$s/forge-%1$s-installer.jar";
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_PARALLEL_DOWNLOADS = 4;

    private ForgeInstaller() {
    }

    public static final class InstallResult {
        private final String minecraftVersionId;
        private final String loaderVersion;
        private final String fullForgeVersion;
        private final String forgeVersionId;

        private InstallResult(
                @NonNull String minecraftVersionId,
                @NonNull String loaderVersion,
                @NonNull String fullForgeVersion,
                @NonNull String forgeVersionId
        ) {
            this.minecraftVersionId = minecraftVersionId;
            this.loaderVersion = loaderVersion;
            this.fullForgeVersion = fullForgeVersion;
            this.forgeVersionId = forgeVersionId;
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
        public String getFullForgeVersion() {
            return fullForgeVersion;
        }

        @NonNull
        public String getForgeVersionId() {
            return forgeVersionId;
        }
    }

    @NonNull
    public static InstallResult installForgeVersion(
            @NonNull Context context,
            @NonNull MinecraftVersion vanillaVersion,
            @NonNull String requestedName,
            @Nullable String requestedLoaderVersion,
            @Nullable MinecraftVersionInstaller.InstallProgressListener listener
    ) throws Exception {
        ensureActivePathManager(context);

        String minecraftVersionId = vanillaVersion.getId();

        ensureVanillaBaseIsValid(context, vanillaVersion, listener);

        notify(listener, 68, "Resolving Forge for " + minecraftVersionId + "...");
        String fullForgeVersion = resolveFullForgeVersion(context, minecraftVersionId, requestedLoaderVersion);
        String loaderVersion = fullForgeVersion.substring((minecraftVersionId + "-").length());
        String forgeVersionId = createUniqueForgeVersionId(requestedName, minecraftVersionId);

        String reusableForgeVersionId = findReusableForgeVersionId(
                forgeVersionId,
                minecraftVersionId,
                loaderVersion,
                fullForgeVersion
        );
        if (reusableForgeVersionId != null) {
            notify(listener, 72, "Reusing existing Forge " + loaderVersion + " profile...");
            cloneInstalledVersionProfileIfNeeded(reusableForgeVersionId, forgeVersionId);
            ensureForgeVersionLibrariesDownloaded(context, forgeVersionId, listener);
            flattenInheritedProfileIfEnabled(context, forgeVersionId, listener);
            notify(listener, 100, "Forge " + loaderVersion + " installed.");
            return new InstallResult(minecraftVersionId, loaderVersion, fullForgeVersion, forgeVersionId);
        }

        notify(listener, 72, "Downloading Forge installer " + loaderVersion + "...");
        File installerJar = new File(PathManager.DIR_CACHE, "forge-" + fullForgeVersion + "-installer.jar");
        downloadFileIfNeeded(context, installerJar, getInstallerUrl(fullForgeVersion), null);

        notify(listener, 76, "Preparing Forge installer profile...");
        patchForgeInstallerProfile(installerJar, forgeVersionId);

        generateLauncherProfiles();
        cleanupForgeGeneratedOutputs(minecraftVersionId, forgeVersionId, fullForgeVersion);

        if (isLegacyForgeInstallProfile(installerJar)) {
            notify(listener, 80, "Installing legacy Forge profile directly...");
            installLegacyForgeProfile(installerJar, forgeVersionId, fullForgeVersion);

            String installedVersionId = resolveInstalledForgeVersionId(forgeVersionId, minecraftVersionId, loaderVersion, fullForgeVersion);
            ensureForgeVersionLibrariesDownloaded(context, installedVersionId, listener);
            flattenInheritedProfileIfEnabled(context, installedVersionId, listener);

            notify(listener, 100, "Forge " + loaderVersion + " installed.");
            return new InstallResult(minecraftVersionId, loaderVersion, fullForgeVersion, installedVersionId);
        }

        if (LibPath.FORGE_INSTALLER == null || !LibPath.FORGE_INSTALLER.isFile()) {
            throw new IllegalStateException("Missing Forge installer support component: "
                    + (LibPath.FORGE_INSTALLER == null ? "null" : LibPath.FORGE_INSTALLER.getAbsolutePath()));
        }

        notify(listener, 80, "Running Forge installer...");
        ArrayList<String> args = new ArrayList<>();
        args.add("-Djava.awt.headless=true");
        args.add("-Duser.home=" + PathManager.DIR_MINECRAFT_HOME);
        args.add("-Djava.io.tmpdir=" + PathManager.DIR_CACHE.getAbsolutePath());
        args.add("-javaagent:" + LibPath.FORGE_INSTALLER.getAbsolutePath() + "=" + loaderVersion);
        args.add("-jar");
        args.add(installerJar.getAbsolutePath());

        // Modern Forge installers support a headless command-line install mode.
        // Old Forge installers such as 1.7.10 do not recognize --installClient,
        // so those are handled above by installLegacyForgeProfile().
        args.add("--installClient");
        args.add(PathManager.DIR_MINECRAFT_HOME);

        File runtime = resolveInstallerRuntime(minecraftVersionId, loaderVersion);
        int exitCode = InstallerProcessRunner.launch(
                context,
                "forge-installer-" + loaderVersion,
                runtime,
                new File(PathManager.DIR_MINECRAFT_HOME),
                args,
                81,
                88,
                (progress, status) -> notify(listener, progress, status)
        );

        if (exitCode != 0) {
            notify(listener, 86, "Forge installer failed once. Cleaning generated outputs and retrying...");
            cleanupForgeGeneratedOutputs(minecraftVersionId, forgeVersionId, fullForgeVersion);

            exitCode = InstallerProcessRunner.launch(
                    context,
                    "forge-installer-retry-" + loaderVersion,
                    runtime,
                    new File(PathManager.DIR_MINECRAFT_HOME),
                    args,
                    86,
                    89,
                    (progress, status) -> notify(listener, progress, status)
            );
        }

        if (exitCode != 0) {
            throw new IllegalStateException("Forge installer exited with code " + exitCode
                    + ". Check latestlog.txt for the Forge installer command-line output.");
        }

        String installedVersionId = resolveInstalledForgeVersionId(forgeVersionId, minecraftVersionId, loaderVersion, fullForgeVersion);
        ensureForgeVersionLibrariesDownloaded(context, installedVersionId, listener);
        flattenInheritedProfileIfEnabled(context, installedVersionId, listener);

        notify(listener, 100, "Forge " + loaderVersion + " installed.");
        return new InstallResult(minecraftVersionId, loaderVersion, fullForgeVersion, installedVersionId);
    }



    private static void ensureForgeVersionLibrariesDownloaded(
            @NonNull Context context,
            @NonNull String installedVersionId,
            @Nullable MinecraftVersionInstaller.InstallProgressListener listener
    ) throws Exception {
        File versionJsonFile = new File(
                MinecraftVersionInstaller.getVersionDirectory(installedVersionId),
                installedVersionId + ".json"
        );

        if (!versionJsonFile.isFile()) {
            throw new IllegalStateException("Missing installed Forge version JSON: " + versionJsonFile.getAbsolutePath());
        }

        JSONObject versionJson = new JSONObject(readString(versionJsonFile));
        JSONArray libraries = versionJson.optJSONArray("libraries");
        if (libraries == null || libraries.length() == 0) return;

        ArrayList<LibraryDownload> downloads = new ArrayList<>();
        HashSet<String> scheduledTargets = new HashSet<>();

        for (int i = 0; i < libraries.length(); i++) {
            JSONObject library = libraries.optJSONObject(i);
            if (library == null) continue;

            String artifactPath = resolveLibraryArtifactPath(library);
            if (artifactPath == null || artifactPath.isEmpty()) continue;

            File target = new File(MinecraftVersionInstaller.getLibrariesDirectory(), artifactPath);
            String sha1 = resolveLibrarySha1(library);

            if (target.isFile()) {
                if (sha1 == null || sha1(target).equalsIgnoreCase(sha1)) {
                    continue;
                }
                Logging.i(TAG, "Forge library hash mismatch, redownloading: " + target.getAbsolutePath());
            }

            ArrayList<String> urls = resolveLibraryDownloadUrls(library, artifactPath);
            if (urls.isEmpty()) {
                Logging.i(TAG, "No download URL known for Forge library: " + library.optString("name", ""));
                continue;
            }

            if (scheduledTargets.add(canonicalPathKey(target))) {
                downloads.add(new LibraryDownload(
                        target,
                        sha1,
                        urls,
                        target.getName(),
                        library.optString("name", artifactPath)
                ));
            }
        }

        if (downloads.isEmpty()) return;

        final int total = Math.max(1, downloads.size());
        notify(listener, 90, "Downloading " + downloads.size() + " Forge libraries...");
        ParallelDownloadExecutor.run(
                downloads,
                MAX_PARALLEL_DOWNLOADS,
                download -> downloadLibraryWithFallbacks(context, download),
                (completed, ignoredTotal, download) -> {
                    int progress = 90 + (int) ((completed * 8L) / total);
                    notify(listener, progress, "Downloading Forge libraries (" + completed + "/" + total + "): " + download.displayName);
                }
        );
    }

    private static void downloadLibraryWithFallbacks(@NonNull Context context, @NonNull LibraryDownload download) throws Exception {
        Exception lastError = null;

        for (String url : download.urls) {
            try {
                downloadFileIfNeeded(context, download.targetFile, url, download.sha1);
                return;
            } catch (Exception e) {
                lastError = e;
                Logging.i(TAG, "Forge library download failed from " + url + ": " + e.getMessage());
            }
        }

        throw new IllegalStateException("Unable to download Forge library " + download.libraryName, lastError);
    }

    @NonNull
    private static String canonicalPathKey(@NonNull File file) {
        try {
            return file.getCanonicalPath();
        } catch (Throwable ignored) {
            return file.getAbsolutePath();
        }
    }

    @Nullable
    private static String resolveLibraryArtifactPath(@NonNull JSONObject library) {
        JSONObject downloads = library.optJSONObject("downloads");
        if (downloads != null) {
            JSONObject artifact = downloads.optJSONObject("artifact");
            if (artifact != null) {
                String path = artifact.optString("path", "");
                if (!path.isEmpty()) return path;
            }
        }

        return artifactPathFromName(library.optString("name", ""));
    }

    @Nullable
    private static String resolveLibrarySha1(@NonNull JSONObject library) {
        JSONObject downloads = library.optJSONObject("downloads");
        JSONObject artifact = downloads != null ? downloads.optJSONObject("artifact") : null;
        String sha1 = artifact != null ? artifact.optString("sha1", "") : "";
        return sha1 == null || sha1.trim().isEmpty() ? null : sha1.trim();
    }

    @NonNull
    private static ArrayList<String> resolveLibraryDownloadUrls(
            @NonNull JSONObject library,
            @NonNull String artifactPath
    ) {
        ArrayList<String> urls = new ArrayList<>();

        JSONObject downloads = library.optJSONObject("downloads");
        JSONObject artifact = downloads != null ? downloads.optJSONObject("artifact") : null;
        if (artifact != null) {
            String direct = artifact.optString("url", "");
            if (direct != null && !direct.trim().isEmpty()) {
                addUrlIfMissing(urls, direct.trim());
            }
        }

        String libraryRepo = library.optString("url", "");
        if (libraryRepo != null && !libraryRepo.trim().isEmpty()) {
            addUrlIfMissing(urls, joinRepoAndPath(libraryRepo.trim(), artifactPath));
        }

        // Forge installer metadata often omits direct download blocks for common
        // module-path dependencies. Try Forge Maven first because Forge mirrors most
        // of these, then Maven Central, then Mojang's library host.
        addUrlIfMissing(urls, joinRepoAndPath("https://maven.minecraftforge.net/", artifactPath));
        addUrlIfMissing(urls, joinRepoAndPath("https://repo1.maven.org/maven2/", artifactPath));
        addUrlIfMissing(urls, joinRepoAndPath("https://libraries.minecraft.net/", artifactPath));

        return urls;
    }

    private static void addUrlIfMissing(@NonNull ArrayList<String> urls, @NonNull String url) {
        if (!urls.contains(url)) {
            urls.add(url);
        }
    }

    @NonNull
    private static String joinRepoAndPath(@NonNull String repo, @NonNull String path) {
        String value = repo;
        if (value.startsWith("http://")) {
            value = "https://" + value.substring("http://".length());
        }
        if (!value.endsWith("/")) value += "/";
        return value + path;
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

    private static void ensureVanillaBaseIsValid(
            @NonNull Context context,
            @NonNull MinecraftVersion vanillaVersion,
            @Nullable MinecraftVersionInstaller.InstallProgressListener listener
    ) throws Exception {
        String minecraftVersionId = vanillaVersion.getId();
        File versionDir = MinecraftVersionInstaller.getVersionDirectory(minecraftVersionId);
        File versionJson = new File(versionDir, minecraftVersionId + ".json");
        File clientJar = new File(versionDir, minecraftVersionId + ".jar");

        boolean installed = versionJson.isFile() && clientJar.isFile();
        boolean valid = installed && isVanillaClientJarValid(versionJson, clientJar);

        if (!valid) {
            if (versionDir.exists()) {
                notify(listener, 10, "Reinstalling clean vanilla " + minecraftVersionId + " for Forge...");
                deleteRecursively(versionDir);
            }

            MinecraftVersionInstaller.installVanillaVersion(context, vanillaVersion, listener);

            versionJson = new File(versionDir, minecraftVersionId + ".json");
            clientJar = new File(versionDir, minecraftVersionId + ".jar");

            if (!versionJson.isFile() || !clientJar.isFile()) {
                throw new IllegalStateException("Vanilla " + minecraftVersionId + " did not install correctly.");
            }

            if (!isVanillaClientJarValid(versionJson, clientJar)) {
                throw new IllegalStateException("Vanilla " + minecraftVersionId
                        + " client jar hash is still invalid after reinstall: "
                        + clientJar.getAbsolutePath());
            }
        }
    }

    private static boolean isVanillaClientJarValid(@NonNull File versionJson, @NonNull File clientJar) {
        try {
            JSONObject json = new JSONObject(readString(versionJson));
            JSONObject downloads = json.optJSONObject("downloads");
            JSONObject client = downloads != null ? downloads.optJSONObject("client") : null;
            String expectedSha1 = client != null ? client.optString("sha1", "") : "";

            // Older/custom metadata may not have a client SHA-1. Do not block those.
            if (expectedSha1 == null || expectedSha1.trim().isEmpty()) return true;

            String actualSha1 = sha1(clientJar);
            boolean valid = expectedSha1.equalsIgnoreCase(actualSha1);
            if (!valid) {
                Logging.i(TAG, "Invalid vanilla client jar: " + clientJar.getAbsolutePath()
                        + " expected=" + expectedSha1
                        + " actual=" + actualSha1);
            }
            return valid;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to validate vanilla client jar for Forge", throwable);
            return false;
        }
    }

    private static void cleanupForgeGeneratedOutputs(
            @NonNull String minecraftVersionId,
            @NonNull String forgeVersionId,
            @NonNull String fullForgeVersion
    ) {
        // Forge validates exact SHA-1 values for generated client slim/extra jars.
        // If a previous failed run left stale/generated files behind, Forge aborts with:
        // "Processor failed, invalid outputs".
        File minecraftClientRoot = new File(MinecraftVersionInstaller.getLibrariesDirectory(), "net/minecraft/client");
        File[] clientChildren = minecraftClientRoot.listFiles();
        if (clientChildren != null) {
            for (File child : clientChildren) {
                if (child.getName().startsWith(minecraftVersionId + "-")) {
                    Logging.i(TAG, "Deleting stale Forge generated client output: " + child.getAbsolutePath());
                    deleteRecursively(child);
                }
            }
        }

        File customVersionDir = MinecraftVersionInstaller.getVersionDirectory(forgeVersionId);
        if (customVersionDir.exists()) {
            Logging.i(TAG, "Deleting stale Forge version dir: " + customVersionDir.getAbsolutePath());
            deleteRecursively(customVersionDir);
        }

        File forgeLibraryDir = new File(
                MinecraftVersionInstaller.getLibrariesDirectory(),
                "net/minecraftforge/forge/" + fullForgeVersion
        );
        // Keep downloaded Forge installer artifacts if present; only delete empty/partial directories.
        if (forgeLibraryDir.isDirectory()) {
            File[] files = forgeLibraryDir.listFiles();
            if (files != null && files.length == 0) {
                deleteRecursively(forgeLibraryDir);
            }
        }
    }

    private static void deleteRecursively(@NonNull File file) {
        if (!file.exists()) return;

        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }

        if (!file.delete() && file.exists()) {
            Logging.i(TAG, "Unable to delete: " + file.getAbsolutePath());
        }
    }

    private static void flattenInheritedProfileIfEnabled(
            @NonNull Context context,
            @NonNull String installedVersionId,
            @Nullable MinecraftVersionInstaller.InstallProgressListener listener
    ) throws Exception {
        if (!LauncherPreferences.isRemoveInheritedVanillaAfterLoaderInstall(context)) return;

        notify(listener, 96, "Flattening loader profile...");
        InheritedVersionFlattener.FlattenResult flattenResult =
                InheritedVersionFlattener.flattenInstalledVersionProfile(context, installedVersionId);
        if (!flattenResult.flattened) return;

        InheritedVersionFlattener.ParentDeleteResult deleteResult =
                InheritedVersionFlattener.deleteFlattenedParentVersionIfSafe(context, installedVersionId);
        Logging.i(TAG, deleteResult.message);
        if (deleteResult.deleted && deleteResult.parentVersionId != null) {
            notify(listener, 98, "Removed inherited vanilla files: " + deleteResult.parentVersionId);
        }
    }

    @NonNull
    public static String inferLoaderNameFromVersionId(@NonNull String versionId) {
        String value = versionId.toLowerCase(Locale.ROOT);
        if (value.contains("forge")) return "Forge";
        return "Vanilla";
    }

    @NonNull
    private static String resolveFullForgeVersion(
            @NonNull Context context,
            @NonNull String minecraftVersionId,
            @Nullable String requestedLoaderVersion
    ) throws Exception {
        ArrayList<String> versions = downloadForgeVersions(context);

        if (requestedLoaderVersion != null && !requestedLoaderVersion.trim().isEmpty()) {
            String requested = normalizeRequestedForgeVersion(minecraftVersionId, requestedLoaderVersion);
            String fullRequested = requested.startsWith(minecraftVersionId + "-")
                    ? requested
                    : minecraftVersionId + "-" + requested;

            for (String version : versions) {
                if (version.equals(fullRequested)) return version;
            }

            // Keep a fallback for old pack metadata that only specifies a partial
            // Forge build such as 36.2 instead of 36.2.34. Exact matches are
            // preferred above so normal packs stay deterministic.
            String partialPrefix = fullRequested + ".";
            ArrayList<String> partialMatches = new ArrayList<>();
            for (String version : versions) {
                if (version.startsWith(partialPrefix)) partialMatches.add(version);
            }
            if (!partialMatches.isEmpty()) {
                String prefix = minecraftVersionId + "-";
                partialMatches.sort((first, second) -> compareForgeBuilds(
                        second.substring(prefix.length()),
                        first.substring(prefix.length())
                ));
                return partialMatches.get(0);
            }

            throw new IllegalStateException("Forge " + requestedLoaderVersion + " was not found for " + minecraftVersionId);
        }

        ArrayList<String> filtered = new ArrayList<>();
        String prefix = minecraftVersionId + "-";
        for (String version : versions) {
            if (version.startsWith(prefix)) filtered.add(version);
        }

        if (filtered.isEmpty()) {
            throw new IllegalStateException("No Forge versions found for " + minecraftVersionId);
        }

        filtered.sort((first, second) -> compareForgeBuilds(
                second.substring(prefix.length()),
                first.substring(prefix.length())
        ));
        return filtered.get(0);
    }

    @NonNull
    private static String normalizeRequestedForgeVersion(
            @NonNull String minecraftVersionId,
            @NonNull String requestedLoaderVersion
    ) {
        String version = requestedLoaderVersion.trim();

        int lastColon = version.lastIndexOf(':');
        if (lastColon >= 0 && lastColon + 1 < version.length()) {
            version = version.substring(lastColon + 1).trim();
        }

        String lower = version.toLowerCase(Locale.ROOT);
        if (lower.startsWith("forge-")) {
            version = version.substring("forge-".length()).trim();
        }

        String minecraftPrefix = minecraftVersionId + "-";
        if (version.startsWith(minecraftPrefix)) {
            return version;
        }

        Matcher minecraftPrefixed = Pattern
                .compile("^1\\.\\d+(?:\\.\\d+)?-(.+)$")
                .matcher(version);
        if (minecraftPrefixed.matches()) {
            version = minecraftPrefixed.group(1).trim();
        }

        return version;
    }

    @NonNull
    private static ArrayList<String> downloadForgeVersions(@NonNull Context context) throws Exception {
        String xml = downloadText(context, FORGE_METADATA_URL);
        ArrayList<String> result = new ArrayList<>();

        Matcher matcher = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
        while (matcher.find()) {
            String version = matcher.group(1);
            if (version != null && !version.trim().isEmpty()) {
                result.add(version.trim());
            }
        }

        return result;
    }

    private static int compareForgeBuilds(@NonNull String left, @NonNull String right) {
        int[] a = parseBuildParts(left);
        int[] b = parseBuildParts(right);
        int max = Math.max(a.length, b.length);

        for (int i = 0; i < max; i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av != bv) return Integer.compare(av, bv);
        }

        return left.compareToIgnoreCase(right);
    }

    @NonNull
    private static int[] parseBuildParts(@NonNull String value) {
        String[] parts = value.trim().split("[.-]");
        ArrayList<Integer> numbers = new ArrayList<>();
        for (String part : parts) {
            try {
                numbers.add(Integer.parseInt(part));
            } catch (NumberFormatException ignored) {
            }
        }

        int[] out = new int[numbers.size()];
        for (int i = 0; i < numbers.size(); i++) out[i] = numbers.get(i);
        return out;
    }

    @NonNull
    private static String getInstallerUrl(@NonNull String fullForgeVersion) {
        return String.format(Locale.ROOT, FORGE_INSTALLER_URL, fullForgeVersion);
    }


    @Nullable
    private static String findReusableForgeVersionId(
            @NonNull String targetVersionId,
            @NonNull String minecraftVersionId,
            @NonNull String loaderVersion,
            @NonNull String fullForgeVersion
    ) {
        ArrayList<String> candidates = new ArrayList<>();
        candidates.add(targetVersionId);
        candidates.add(minecraftVersionId + "-forge-" + loaderVersion);
        candidates.add(minecraftVersionId + "-forge-" + fullForgeVersion);
        candidates.add(minecraftVersionId + "-forge" + fullForgeVersion);
        candidates.add(fullForgeVersion);
        candidates.add("forge-" + fullForgeVersion);

        for (String candidate : candidates) {
            if (isForgeCoordinateVersion(candidate, fullForgeVersion)) return candidate;
        }

        File versionsDir = MinecraftVersionInstaller.getVersionsDirectory();
        File[] children = versionsDir.listFiles();
        if (children == null) return null;

        for (File child : children) {
            if (child == null || !child.isDirectory()) continue;
            String id = child.getName();
            if (targetVersionId.equals(id)) continue;
            if (isForgeCoordinateVersion(id, fullForgeVersion)) return id;
        }

        return null;
    }

    private static boolean isForgeCoordinateVersion(
            @Nullable String versionId,
            @NonNull String fullForgeVersion
    ) {
        if (versionId == null || versionId.trim().isEmpty()) return false;

        File versionDir = MinecraftVersionInstaller.getVersionDirectory(versionId);
        File versionJsonFile = new File(versionDir, versionId + ".json");
        if (!versionJsonFile.isFile()) return false;

        try {
            JSONObject json = new JSONObject(readString(versionJsonFile));
            String text = json.toString();
            if (!text.contains("net.minecraftforge:forge:" + fullForgeVersion)
                    && !text.contains("net.minecraftforge:fmlloader:" + fullForgeVersion)
                    && !(text.contains(fullForgeVersion) && text.toLowerCase(Locale.ROOT).contains("forge"))) {
                return false;
            }
            return isLaunchableVersionProfile(versionId, json);
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to inspect Forge profile " + versionId + ": " + throwable.getMessage());
            return false;
        }
    }

    private static boolean isLaunchableVersionProfile(
            @NonNull String versionId,
            @NonNull JSONObject versionJson
    ) {
        File ownJar = new File(MinecraftVersionInstaller.getVersionDirectory(versionId), versionId + ".jar");
        if (ownJar.isFile()) return true;

        String inheritsFrom = versionJson.optString("inheritsFrom", "").trim();
        if (!inheritsFrom.isEmpty()) {
            File parentJar = new File(MinecraftVersionInstaller.getVersionDirectory(inheritsFrom), inheritsFrom + ".jar");
            return parentJar.isFile();
        }

        // Some Forge profiles launch completely from libraries and do not need an
        // own jar. If the JSON has a main class and Forge libraries, it is usable.
        return !versionJson.optString("mainClass", "").trim().isEmpty()
                && versionJson.toString().contains("net.minecraftforge");
    }

    private static void cloneInstalledVersionProfileIfNeeded(
            @NonNull String sourceVersionId,
            @NonNull String targetVersionId
    ) throws Exception {
        if (sourceVersionId.equals(targetVersionId)) return;

        File sourceDir = MinecraftVersionInstaller.getVersionDirectory(sourceVersionId);
        File sourceJson = new File(sourceDir, sourceVersionId + ".json");
        if (!sourceJson.isFile()) {
            throw new IllegalStateException("Reusable Forge profile JSON is missing: " + sourceJson.getAbsolutePath());
        }

        File targetDir = MinecraftVersionInstaller.getVersionDirectory(targetVersionId);
        ensureDirectory(targetDir);

        JSONObject json = new JSONObject(readString(sourceJson));
        json.put("id", targetVersionId);
        writeString(new File(targetDir, targetVersionId + ".json"), json.toString(2));

        File sourceJar = new File(sourceDir, sourceVersionId + ".jar");
        if (sourceJar.isFile()) {
            try (InputStream input = new FileInputStream(sourceJar);
                 FileOutputStream output = new FileOutputStream(new File(targetDir, targetVersionId + ".jar"))) {
                copy(input, output);
            }
        }

        Logging.i(TAG, "Cloned reusable Forge profile " + sourceVersionId + " -> " + targetVersionId);
    }

    @NonNull
    private static String createUniqueForgeVersionId(@NonNull String requestedName, @NonNull String minecraftVersionId) {
        String base = sanitizeVersionId(requestedName);
        if (base.isEmpty()) base = sanitizeVersionId(minecraftVersionId + " Forge");
        if (base.isEmpty()) base = "forge-" + UUID.randomUUID();

        File versionsDir = MinecraftVersionInstaller.getVersionsDirectory();
        File candidate = new File(versionsDir, base);
        if (!candidate.exists()) return base;

        for (int i = 2; i < 1000; i++) {
            String id = base + "-" + i;
            if (!new File(versionsDir, id).exists()) return id;
        }

        return base + "-" + UUID.randomUUID();
    }

    @NonNull
    private static String sanitizeVersionId(@NonNull String raw) {
        return raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._ -]", "")
                .replace(' ', '-')
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    private static void patchForgeInstallerProfile(@NonNull File installerJar, @NonNull String customVersionId) throws Exception {
        File tempJar = new File(installerJar.getParentFile(), installerJar.getName() + ".tmp");
        File profileJson = new File(installerJar.getParentFile(), "install_profile.json");

        if (tempJar.exists()) {
            //noinspection ResultOfMethodCallIgnored
            tempJar.delete();
        }

        try (ZipFile zipFile = new ZipFile(installerJar)) {
            ZipEntry entry = zipFile.getEntry("install_profile.json");
            if (entry == null) {
                throw new IllegalStateException("install_profile.json not found in " + installerJar.getName());
            }

            try (InputStream input = zipFile.getInputStream(entry);
                 FileOutputStream output = new FileOutputStream(profileJson)) {
                copy(input, output);
            }
        }

        JSONObject profile = new JSONObject(readString(profileJson));
        if (profile.has("spec")) {
            if (!profile.has("version")) {
                throw new IllegalStateException("Unable to find Forge install_profile version key.");
            }
            profile.put("version", customVersionId);
        } else {
            JSONObject install = profile.optJSONObject("install");
            if (install == null) {
                throw new IllegalStateException("Unable to find Forge install_profile install block.");
            }
            install.put("target", customVersionId);
            profile.put("install", install);
        }

        relaxGeneratedClientOutputHashes(profile);
        writeString(profileJson, profile.toString());

        try (ZipFile zipFile = new ZipFile(installerJar);
             ZipOutputStream output = new ZipOutputStream(new FileOutputStream(tempJar))) {
            java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();

            while (entries.hasMoreElements()) {
                ZipEntry original = entries.nextElement();

                if (shouldSkipSignatureFile(original.getName())) {
                    continue;
                }

                ZipEntry copyEntry = new ZipEntry(original.getName());
                output.putNextEntry(copyEntry);

                if (!original.isDirectory()) {
                    if ("install_profile.json".equals(original.getName())) {
                        try (InputStream input = new FileInputStream(profileJson)) {
                            copy(input, output);
                        }
                    } else {
                        try (InputStream input = zipFile.getInputStream(original)) {
                            copy(input, output);
                        }
                    }
                }

                output.closeEntry();
            }
        }

        if (!installerJar.delete()) {
            throw new IllegalStateException("Unable to replace Forge installer jar.");
        }
        if (!tempJar.renameTo(installerJar)) {
            throw new IllegalStateException("Unable to move patched Forge installer jar.");
        }

        //noinspection ResultOfMethodCallIgnored
        profileJson.delete();
    }


    private static void relaxGeneratedClientOutputHashes(@NonNull JSONObject profile) {
        /*
         * Android/OpenJDK can generate byte-identical class/resource content but different
         * zip container metadata for Forge's generated client slim/extra jars. Forge then
         * aborts at the installer validation layer with:
         *
         *   Processor failed, invalid outputs:
         *   ...client-<mc>-slim.jar
         *   ...client-<mc>-extra.jar
         *
         * The jars are generated by the installer during this same run. Remove only the
         * hash checks for those generated slim/extra outputs from install_profile.json.
         * Do not relax downloaded library checks.
         */
        JSONArray processors = profile.optJSONArray("processors");
        if (processors == null) return;

        for (int i = 0; i < processors.length(); i++) {
            JSONObject processor = processors.optJSONObject(i);
            if (processor == null) continue;

            JSONObject outputs = processor.optJSONObject("outputs");
            if (outputs == null) continue;

            JSONArray keys = outputs.names();
            if (keys == null) continue;

            for (int k = keys.length() - 1; k >= 0; k--) {
                String key = keys.optString(k, "");
                String lower = key.toLowerCase(Locale.ROOT);

                if (lower.contains("mc_slim")
                        || lower.contains("mc_extra")
                        || lower.contains("-slim.jar")
                        || lower.contains("-extra.jar")
                        || lower.contains(":slim")
                        || lower.contains(":extra")) {
                    Logging.i(TAG, "Relaxing Forge generated output hash check for " + key);
                    outputs.remove(key);
                }
            }

            if (outputs.length() == 0) {
                processor.remove("outputs");
            }
        }
    }

    private static boolean shouldSkipSignatureFile(@NonNull String entryName) {
        String upper = entryName.toUpperCase(Locale.ROOT);
        return upper.startsWith("META-INF/")
                && (upper.endsWith(".SF")
                || upper.endsWith(".RSA")
                || upper.endsWith(".DSA")
                || upper.endsWith(".EC"));
    }

    private static boolean isLegacyForgeInstallProfile(@NonNull File installerJar) {
        try {
            JSONObject profile = readInstallProfileFromJar(installerJar);

            // Forge 1.7.10 and other old installers use the legacy install/versionInfo
            // schema and do not support the --installClient command-line option. Running
            // them with that option exits immediately with UnrecognizedOptionException.
            return !profile.has("spec")
                    && profile.optJSONObject("install") != null
                    && profile.optJSONObject("versionInfo") != null;
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to inspect Forge installer profile: " + throwable.getMessage());
            return false;
        }
    }

    private static void installLegacyForgeProfile(
            @NonNull File installerJar,
            @NonNull String forgeVersionId,
            @NonNull String fullForgeVersion
    ) throws Exception {
        JSONObject profile = readInstallProfileFromJar(installerJar);
        JSONObject install = profile.optJSONObject("install");
        JSONObject versionInfo = profile.optJSONObject("versionInfo");

        if (install == null || versionInfo == null) {
            throw new IllegalStateException("Legacy Forge installer profile is missing install/versionInfo blocks.");
        }

        versionInfo.put("id", forgeVersionId);

        String parentVersion = install.optString("minecraft", "").trim();
        if (parentVersion.isEmpty()) parentVersion = install.optString("version", "").trim();
        if (!parentVersion.isEmpty() && versionInfo.optString("inheritsFrom", "").trim().isEmpty()) {
            versionInfo.put("inheritsFrom", parentVersion);
        }

        File versionDir = MinecraftVersionInstaller.getVersionDirectory(forgeVersionId);
        ensureDirectory(versionDir);
        writeString(new File(versionDir, forgeVersionId + ".json"), versionInfo.toString(2));

        String forgeCoordinate = install.optString("path", "").trim();
        String artifactPath = artifactPathFromName(forgeCoordinate);
        if (artifactPath == null || artifactPath.trim().isEmpty()) {
            artifactPath = artifactPathFromName("net.minecraftforge:forge:" + fullForgeVersion);
        }
        if (artifactPath == null || artifactPath.trim().isEmpty()) {
            throw new IllegalStateException("Unable to resolve legacy Forge artifact path for " + fullForgeVersion);
        }

        File targetJar = new File(MinecraftVersionInstaller.getLibrariesDirectory(), artifactPath);
        ensureDirectory(targetJar.getParentFile());

        String installerFilePath = install.optString("filePath", "").trim();
        try (ZipFile zipFile = new ZipFile(installerJar)) {
            ZipEntry forgeJarEntry = findLegacyForgePayload(zipFile, installerFilePath);
            if (forgeJarEntry == null) {
                throw new IllegalStateException("Unable to find legacy Forge payload jar inside " + installerJar.getName());
            }

            try (InputStream input = zipFile.getInputStream(forgeJarEntry);
                 FileOutputStream output = new FileOutputStream(targetJar)) {
                copy(input, output);
            }
        }

        Logging.i(TAG, "Installed legacy Forge profile " + forgeVersionId
                + " and payload " + targetJar.getAbsolutePath());
    }

    @NonNull
    private static JSONObject readInstallProfileFromJar(@NonNull File installerJar) throws Exception {
        try (ZipFile zipFile = new ZipFile(installerJar)) {
            ZipEntry entry = zipFile.getEntry("install_profile.json");
            if (entry == null) {
                throw new IllegalStateException("install_profile.json not found in " + installerJar.getName());
            }

            try (InputStream input = zipFile.getInputStream(entry)) {
                return new JSONObject(readStream(input));
            }
        }
    }

    @Nullable
    private static ZipEntry findLegacyForgePayload(@NonNull ZipFile zipFile, @Nullable String installerFilePath) {
        if (installerFilePath != null) {
            String normalized = installerFilePath.replace('\\', '/').trim();
            while (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }

            if (!normalized.isEmpty()) {
                ZipEntry exact = zipFile.getEntry(normalized);
                if (exact != null && !exact.isDirectory()) return exact;
            }
        }

        ZipEntry firstJar = null;
        java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry == null || entry.isDirectory()) continue;

            String name = entry.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".jar") || lower.startsWith("meta-inf/")) continue;

            if (lower.contains("universal") || lower.contains("forge")) {
                return entry;
            }
            if (firstJar == null) firstJar = entry;
        }

        return firstJar;
    }

    private static void generateLauncherProfiles() throws Exception {
        File profileFile = new File(PathManager.DIR_MINECRAFT_HOME, "launcher_profiles.json");
        if (profileFile.isFile()) return;

        JSONObject root = new JSONObject();
        JSONObject profiles = new JSONObject();
        JSONObject defaultProfile = new JSONObject();
        defaultProfile.put("lastVersionId", "latest-release");
        profiles.put("default", defaultProfile);
        root.put("profiles", profiles);
        root.put("selectedProfile", "default");

        writeString(profileFile, root.toString());
    }

    @NonNull
    private static String resolveInstalledForgeVersionId(
            @NonNull String customVersionId,
            @NonNull String minecraftVersionId,
            @NonNull String loaderVersion,
            @NonNull String fullForgeVersion
    ) {
        if (isInstalledVersion(customVersionId)) return customVersionId;

        String[] candidates = new String[]{
                minecraftVersionId + "-forge-" + loaderVersion,
                minecraftVersionId + "-forge" + fullForgeVersion,
                fullForgeVersion
        };

        for (String candidate : candidates) {
            if (isInstalledVersion(candidate)) return candidate;
        }

        throw new IllegalStateException("Forge installer finished, but no Forge version JSON was found for " + customVersionId);
    }

    private static boolean isInstalledVersion(@NonNull String versionId) {
        File json = new File(MinecraftVersionInstaller.getVersionDirectory(versionId), versionId + ".json");
        return json.isFile();
    }

    @NonNull
    private static File resolveInstallerRuntime(
            @NonNull String minecraftVersionId,
            @NonNull String loaderVersion
    ) {
        String[] preferred;
        if (isLegacyJava8ForgeVersion(minecraftVersionId, loaderVersion)) {
            preferred = new String[]{"Internal-8", "Internal-17", "Internal-21", "Internal-25"};
        } else if (isModernJavaMinecraftVersion(minecraftVersionId)) {
            preferred = new String[]{"Internal-21", "Internal-25", "Internal-17", "Internal-8"};
        } else {
            preferred = new String[]{"Internal-17", "Internal-21", "Internal-25", "Internal-8"};
        }

        for (String name : preferred) {
            File runtime = MultiRTUtils.getRuntimeDir(name);
            if (runtime.isDirectory() && new File(runtime, "bin/java").isFile()) {
                Logging.i(TAG, "Using " + name + " for Forge installer "
                        + minecraftVersionId + " / " + loaderVersion);
                return runtime;
            }
        }
        throw new IllegalStateException("No internal Java runtime is installed for Forge installer.");
    }

    private static boolean isLegacyJava8ForgeVersion(
            @NonNull String minecraftVersionId,
            @NonNull String loaderVersion
    ) {
        String[] parts = minecraftVersionId.split("\\.");
        try {
            if (parts.length >= 2 && "1".equals(parts[0])) {
                int minor = Integer.parseInt(parts[1]);
                if (minor <= 16) return true;
            }
        } catch (NumberFormatException ignored) {
        }

        String forge = loaderVersion.trim();
        return forge.startsWith("36.")
                || forge.startsWith("35.")
                || forge.startsWith("34.")
                || forge.startsWith("33.")
                || forge.startsWith("32.")
                || forge.startsWith("31.")
                || forge.startsWith("30.")
                || forge.startsWith("14.");
    }

    private static boolean isModernJavaMinecraftVersion(@NonNull String minecraftVersionId) {
        String[] parts = minecraftVersionId.split("\\.");
        try {
            if (parts.length == 0) return false;
            int first = Integer.parseInt(parts[0]);
            if (first >= 26) return true;
            if (first != 1 || parts.length < 2) return false;

            int second = Integer.parseInt(parts[1]);
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;

            // Mojang moved to Java 21 at Minecraft 1.20.5.
            return second > 20 || (second == 20 && patch >= 5);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static void downloadFileIfNeeded(
            @NonNull Context context,
            @NonNull File target,
            @NonNull String url,
            @Nullable String sha1
    ) throws Exception {
        if (target.isFile()) {
            if (sha1 == null || sha1(target).equalsIgnoreCase(sha1)) {
                return;
            }
            Logging.i(TAG, "Existing Forge file hash mismatch, redownloading: " + target.getAbsolutePath());
        }

        File parent = target.getParentFile();
        if (parent != null) ensureDirectory(parent);

        File tempFile = new File(target.getAbsolutePath() + ".part");
        Exception lastError = null;
        for (String candidateUrl : MinecraftDownloadSource.getCandidateUrls(context, url)) {
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
            try {
                downloadToFileExact(candidateUrl, tempFile);
                if (sha1 != null && !sha1(tempFile).equalsIgnoreCase(sha1)) {
                    throw new IllegalStateException("SHA-1 mismatch for " + target.getName()
                            + " from " + candidateUrl);
                }

                if (target.exists() && !target.delete()) {
                    throw new IllegalStateException("Unable to replace " + target.getAbsolutePath());
                }

                if (!tempFile.renameTo(target)) {
                    try (InputStream input = new FileInputStream(tempFile);
                         FileOutputStream output = new FileOutputStream(target)) {
                        copy(input, output);
                    }
                    //noinspection ResultOfMethodCallIgnored
                    tempFile.delete();
                }
                return;
            } catch (Exception error) {
                lastError = error;
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
                Logging.i(TAG, "Forge download source failed for " + candidateUrl
                        + ": " + error.getMessage());
            }
        }

        if (lastError != null) throw lastError;
        throw new IllegalStateException("No download source available for " + url);
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
                Logging.i(TAG, "Forge metadata source failed for " + candidateUrl
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
            copy(input, output);
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
            copy(input, output);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    @NonNull
    private static String readString(@NonNull File file) throws Exception {
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copy(input, output);
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

    private static void copy(@NonNull InputStream input, @NonNull java.io.OutputStream output) throws Exception {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
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

    private static final class LibraryDownload {
        final File targetFile;
        @Nullable
        final String sha1;
        final ArrayList<String> urls;
        final String displayName;
        final String libraryName;

        private LibraryDownload(
                @NonNull File targetFile,
                @Nullable String sha1,
                @NonNull ArrayList<String> urls,
                @NonNull String displayName,
                @NonNull String libraryName
        ) {
            this.targetFile = targetFile;
            this.sha1 = sha1;
            this.urls = urls;
            this.displayName = displayName;
            this.libraryName = libraryName;
        }
    }

    private static void ensureActivePathManager(@NonNull Context context) {
        if (PathManager.DIR_MINECRAFT_HOME == null || PathManager.DIR_MINECRAFT_HOME.trim().isEmpty()) {
            PathManager.initContextConstants(context);
        }
    }

    private static void notify(
            @Nullable MinecraftVersionInstaller.InstallProgressListener listener,
            int progress,
            @NonNull String message
    ) {
        if (listener != null) listener.onProgress(Math.max(0, Math.min(100, progress)), message);
    }
}

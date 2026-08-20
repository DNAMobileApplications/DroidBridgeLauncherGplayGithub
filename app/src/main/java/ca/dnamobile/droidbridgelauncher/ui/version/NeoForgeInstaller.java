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
import ca.dnamobile.droidbridgelauncher.launcher.DroidBridgeGameProcessLauncher;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;
import ca.dnamobile.droidbridgelauncher.runtime.multirt.MultiRTUtils;

/**
 * DroidBridge NeoForge installer.
 *
 * NeoForge has two Maven coordinates:
 * - legacy NeoForged Forge for 1.20.1: net.neoforged:forge:<mcVersion-loaderVersion>
 * - modern NeoForge: net.neoforged:neoforge:<neoForgeVersion>
 *
 * The install path intentionally mirrors ForgeInstaller because NeoForge also ships
 * a client installer jar that writes a launcher version profile into .minecraft/versions.
 */
public final class NeoForgeInstaller {
    private static final String TAG = "NeoForgeInstaller";

    private static final String NEOFORGE_METADATA_URL =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
    private static final String NEOFORGE_INSTALLER_URL =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/%1$s/neoforge-%1$s-installer.jar";

    private static final String NEOFORGED_FORGE_METADATA_URL =
            "https://maven.neoforged.net/releases/net/neoforged/forge/maven-metadata.xml";
    private static final String NEOFORGED_FORGE_INSTALLER_URL =
            "https://maven.neoforged.net/releases/net/neoforged/forge/%1$s/forge-%1$s-installer.jar";

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_PARALLEL_DOWNLOADS = 4;

    private NeoForgeInstaller() {
    }

    public static final class InstallResult {
        private final String minecraftVersionId;
        private final String loaderVersion;
        private final String fullNeoForgeVersion;
        private final String neoForgeVersionId;
        private final boolean legacyNeoForgedForge;

        private InstallResult(
                @NonNull String minecraftVersionId,
                @NonNull String loaderVersion,
                @NonNull String fullNeoForgeVersion,
                @NonNull String neoForgeVersionId,
                boolean legacyNeoForgedForge
        ) {
            this.minecraftVersionId = minecraftVersionId;
            this.loaderVersion = loaderVersion;
            this.fullNeoForgeVersion = fullNeoForgeVersion;
            this.neoForgeVersionId = neoForgeVersionId;
            this.legacyNeoForgedForge = legacyNeoForgedForge;
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
        public String getFullNeoForgeVersion() {
            return fullNeoForgeVersion;
        }

        @NonNull
        public String getNeoForgeVersionId() {
            return neoForgeVersionId;
        }

        public boolean isLegacyNeoForgedForge() {
            return legacyNeoForgedForge;
        }
    }

    private static final class VersionCoordinate {
        final String fullVersion;
        final String loaderVersion;
        final String installerUrl;
        final boolean legacyNeoForgedForge;

        VersionCoordinate(
                @NonNull String fullVersion,
                @NonNull String loaderVersion,
                @NonNull String installerUrl,
                boolean legacyNeoForgedForge
        ) {
            this.fullVersion = fullVersion;
            this.loaderVersion = loaderVersion;
            this.installerUrl = installerUrl;
            this.legacyNeoForgedForge = legacyNeoForgedForge;
        }
    }

    @NonNull
    public static InstallResult installNeoForgeVersion(
            @NonNull Context context,
            @NonNull MinecraftVersion vanillaVersion,
            @NonNull String requestedName,
            @Nullable String requestedLoaderVersion,
            @Nullable MinecraftVersionInstaller.InstallProgressListener listener
    ) throws Exception {
        ensureActivePathManager(context);

        String minecraftVersionId = vanillaVersion.getId();
        ensureVanillaBaseIsValid(context, vanillaVersion, listener);

        notify(listener, 68, "Resolving NeoForge for " + minecraftVersionId + "...");
        VersionCoordinate coordinate = resolveNeoForgeCoordinate(context, minecraftVersionId, requestedLoaderVersion);
        String neoForgeVersionId = createUniqueNeoForgeVersionId(requestedName, minecraftVersionId);

        String reusableNeoForgeVersionId = findReusableNeoForgeVersionId(
                neoForgeVersionId,
                coordinate.fullVersion,
                coordinate.legacyNeoForgedForge
        );
        if (reusableNeoForgeVersionId != null) {
            notify(listener, 72, "Reusing existing NeoForge " + coordinate.loaderVersion + " profile...");
            cloneInstalledVersionProfileIfNeeded(reusableNeoForgeVersionId, neoForgeVersionId);
            ensureNeoForgeVersionLibrariesDownloaded(context, neoForgeVersionId, listener);
            flattenInheritedProfileIfEnabled(context, neoForgeVersionId, listener);
            notify(listener, 100, "NeoForge " + coordinate.loaderVersion + " installed.");
            return new InstallResult(
                    minecraftVersionId,
                    coordinate.loaderVersion,
                    coordinate.fullVersion,
                    neoForgeVersionId,
                    coordinate.legacyNeoForgedForge
            );
        }

        notify(listener, 72, "Downloading NeoForge installer " + coordinate.loaderVersion + "...");
        String installerPrefix = coordinate.legacyNeoForgedForge ? "neoforged-forge-" : "neoforge-";
        File originalInstallerJar = new File(
                new File(PathManager.DIR_CACHE, "neoforge/original"),
                installerPrefix + coordinate.fullVersion + "-installer.jar"
        );
        downloadFileIfNeeded(context, originalInstallerJar, coordinate.installerUrl, null);

        notify(listener, 76, "Preparing NeoForge installer profile...");
        File patchedInstallerJar = new File(
                new File(PathManager.DIR_CACHE, "neoforge/patched"),
                installerPrefix
                        + coordinate.fullVersion
                        + "-"
                        + safeCacheFileName(neoForgeVersionId)
                        + "-installer.jar"
        );
        patchInstallerProfile(originalInstallerJar, patchedInstallerJar, neoForgeVersionId);

        generateLauncherProfiles();
        cleanupNeoForgeTargetVersionOnly(neoForgeVersionId);

        notify(listener, 80, "Running NeoForge installer...");
        ArrayList<String> args = buildInstallerArgs(coordinate, patchedInstallerJar);
        File runtime = resolveInstallerRuntime(minecraftVersionId);

        int exitCode = InstallerProcessRunner.launch(
                context,
                "neoforge-installer-" + coordinate.loaderVersion,
                runtime,
                new File(PathManager.DIR_MINECRAFT_HOME),
                args,
                81,
                88,
                (progress, status) -> notify(listener, progress, status)
        );

        if (exitCode != 0) {
            notify(listener, 86, "NeoForge installer failed once. Cleaning generated outputs and retrying...");
            cleanupNeoForgeGeneratedOutputs(minecraftVersionId, neoForgeVersionId, coordinate.fullVersion);

            exitCode = InstallerProcessRunner.launch(
                    context,
                    "neoforge-installer-retry-" + coordinate.loaderVersion,
                    runtime,
                    new File(PathManager.DIR_MINECRAFT_HOME),
                    args,
                    86,
                    89,
                    (progress, status) -> notify(listener, progress, status)
            );
        }

        if (exitCode != 0) {
            throw new IllegalStateException("NeoForge installer exited with code " + exitCode
                    + ". Check latestlog.txt for the NeoForge installer command-line output.");
        }

        String installedVersionId = resolveInstalledNeoForgeVersionId(
                neoForgeVersionId,
                minecraftVersionId,
                coordinate.loaderVersion,
                coordinate.fullVersion,
                coordinate.legacyNeoForgedForge
        );
        ensureNeoForgeVersionLibrariesDownloaded(context, installedVersionId, listener);
        flattenInheritedProfileIfEnabled(context, installedVersionId, listener);

        notify(listener, 100, "NeoForge " + coordinate.loaderVersion + " installed.");
        return new InstallResult(
                minecraftVersionId,
                coordinate.loaderVersion,
                coordinate.fullVersion,
                installedVersionId,
                coordinate.legacyNeoForgedForge
        );
    }

    @NonNull
    private static ArrayList<String> buildInstallerArgs(
            @NonNull VersionCoordinate coordinate,
            @NonNull File installerJar
    ) {
        ArrayList<String> args = new ArrayList<>();
        args.add("-Djava.awt.headless=true");
        args.add("-Duser.home=" + PathManager.DIR_MINECRAFT_HOME);
        args.add("-Djava.io.tmpdir=" + PathManager.DIR_CACHE.getAbsolutePath());
        args.add("-jar");
        args.add(installerJar.getAbsolutePath());

        // NeoForge expects the install root after --installClient. Keeping this
        // explicit matches the prior implementation's installer args and avoids a no-op install.
        args.add("--installClient");
        args.add(PathManager.DIR_MINECRAFT_HOME);
        return args;
    }

    @NonNull
    private static VersionCoordinate resolveNeoForgeCoordinate(
            @NonNull Context context,
            @NonNull String minecraftVersionId,
            @Nullable String requestedLoaderVersion
    ) throws Exception {
        Exception lastError = null;

        if (requestedLoaderVersion != null && !requestedLoaderVersion.trim().isEmpty()) {
            String requested = requestedLoaderVersion.trim();

            try {
                VersionCoordinate legacy = findLegacyNeoForgedForgeCoordinate(context, minecraftVersionId, requested);
                if (legacy != null) return legacy;
            } catch (Exception e) {
                lastError = e;
            }

            try {
                VersionCoordinate modern = findModernNeoForgeCoordinate(context, minecraftVersionId, requested);
                if (modern != null) return modern;
            } catch (Exception e) {
                lastError = e;
            }

            throw new IllegalStateException(
                    "NeoForge " + requested + " was not found for " + minecraftVersionId,
                    lastError
            );
        }

        try {
            VersionCoordinate latestModern = findLatestModernNeoForgeCoordinate(context, minecraftVersionId);
            if (latestModern != null) return latestModern;
        } catch (Exception e) {
            lastError = e;
        }

        try {
            VersionCoordinate latestLegacy = findLatestLegacyNeoForgedForgeCoordinate(context, minecraftVersionId);
            if (latestLegacy != null) return latestLegacy;
        } catch (Exception e) {
            lastError = e;
        }

        throw new IllegalStateException("No NeoForge versions found for " + minecraftVersionId, lastError);
    }

    @Nullable
    private static VersionCoordinate findLegacyNeoForgedForgeCoordinate(
            @NonNull Context context,
            @NonNull String minecraftVersionId,
            @NonNull String requestedLoaderVersion
    ) throws Exception {
        ArrayList<String> versions = downloadNeoForgedForgeVersions(context);
        String fullRequested = requestedLoaderVersion.startsWith(minecraftVersionId + "-")
                ? requestedLoaderVersion
                : minecraftVersionId + "-" + requestedLoaderVersion;

        for (String version : versions) {
            if (version.equals(fullRequested)) {
                String loaderVersion = version.substring((minecraftVersionId + "-").length());
                return new VersionCoordinate(version, loaderVersion, getNeoForgedForgeInstallerUrl(version), true);
            }
        }
        return null;
    }

    @Nullable
    private static VersionCoordinate findLatestLegacyNeoForgedForgeCoordinate(@NonNull Context context, @NonNull String minecraftVersionId) throws Exception {
        ArrayList<String> versions = downloadNeoForgedForgeVersions(context);
        String prefix = minecraftVersionId + "-";
        ArrayList<String> filtered = new ArrayList<>();

        for (String version : versions) {
            if (version.startsWith(prefix)) filtered.add(version);
        }

        if (filtered.isEmpty()) return null;

        filtered.sort((first, second) -> compareNeoForgeBuilds(
                second.substring(prefix.length()),
                first.substring(prefix.length())
        ));

        String fullVersion = filtered.get(0);
        String loaderVersion = fullVersion.substring(prefix.length());
        return new VersionCoordinate(fullVersion, loaderVersion, getNeoForgedForgeInstallerUrl(fullVersion), true);
    }

    @Nullable
    private static VersionCoordinate findModernNeoForgeCoordinate(
            @NonNull Context context,
            @NonNull String minecraftVersionId,
            @NonNull String requestedLoaderVersion
    ) throws Exception {
        ArrayList<String> versions = downloadNeoForgeVersions(context);

        for (String version : versions) {
            if (version.equals(requestedLoaderVersion) && minecraftVersionId.equals(formatGameVersion(version))) {
                return new VersionCoordinate(version, version, getNeoForgeInstallerUrl(version), false);
            }
        }
        return null;
    }

    @Nullable
    private static VersionCoordinate findLatestModernNeoForgeCoordinate(@NonNull Context context, @NonNull String minecraftVersionId) throws Exception {
        ArrayList<String> versions = downloadNeoForgeVersions(context);
        ArrayList<String> filtered = new ArrayList<>();

        for (String version : versions) {
            if (minecraftVersionId.equals(formatGameVersion(version))) filtered.add(version);
        }

        if (filtered.isEmpty()) return null;

        filtered.sort((first, second) -> compareNeoForgeBuilds(second, first));
        String fullVersion = filtered.get(0);
        return new VersionCoordinate(fullVersion, fullVersion, getNeoForgeInstallerUrl(fullVersion), false);
    }

    @NonNull
    private static ArrayList<String> downloadNeoForgeVersions(@NonNull Context context) throws Exception {
        return parseMavenVersions(downloadText(context, NEOFORGE_METADATA_URL));
    }

    @NonNull
    private static ArrayList<String> downloadNeoForgedForgeVersions(@NonNull Context context) throws Exception {
        return parseMavenVersions(downloadText(context, NEOFORGED_FORGE_METADATA_URL));
    }

    @NonNull
    private static String getNeoForgeInstallerUrl(@NonNull String version) {
        return String.format(Locale.ROOT, NEOFORGE_INSTALLER_URL, version);
    }

    @NonNull
    private static String getNeoForgedForgeInstallerUrl(@NonNull String version) {
        return String.format(Locale.ROOT, NEOFORGED_FORGE_INSTALLER_URL, version);
    }

    @NonNull
    private static String formatGameVersion(@NonNull String neoForgeVersion) {
        if (neoForgeVersion.contains("1.20.1")) {
            return "1.20.1";
        }

        if (neoForgeVersion.startsWith("0.")) {
            String versionPart = neoForgeVersion.substring("0.".length()).split("-", 2)[0];
            int lastDot = versionPart.lastIndexOf('.');
            return lastDot > 0 ? versionPart.substring(0, lastDot) : versionPart;
        }

        String base = neoForgeVersion.split("-", 2)[0];
        String[] rawParts = base.split("\\.");
        ArrayList<Integer> parts = new ArrayList<>();
        for (String rawPart : rawParts) {
            if (rawPart == null || rawPart.trim().isEmpty()) continue;
            try {
                parts.add(Integer.parseInt(rawPart.trim()));
            } catch (NumberFormatException ignored) {
                return neoForgeVersion;
            }
        }

        if (parts.isEmpty()) return neoForgeVersion;

        int first = parts.get(0);
        int second = parts.size() > 1 ? parts.get(1) : 0;
        int third = parts.size() > 2 ? parts.get(2) : 0;

        if (first >= 25) {
            return third == 0 ? first + "." + second : first + "." + second + "." + third;
        }

        return second == 0 ? "1." + first : "1." + first + "." + second;
    }

    @NonNull
    private static ArrayList<String> parseMavenVersions(@NonNull String xml) {
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

    private static int compareNeoForgeBuilds(@NonNull String left, @NonNull String right) {
        int[] a = parseBuildParts(left.substring(0, left.length()).split("-", 2)[0]);
        int[] b = parseBuildParts(right.substring(0, right.length()).split("-", 2)[0]);
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
        String[] parts = value.trim().split("[.+\\-]");
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

    private static void ensureNeoForgeVersionLibrariesDownloaded(
            @NonNull Context context,
            @NonNull String installedVersionId,
            @Nullable MinecraftVersionInstaller.InstallProgressListener listener
    ) throws Exception {
        File versionJsonFile = new File(
                MinecraftVersionInstaller.getVersionDirectory(installedVersionId),
                installedVersionId + ".json"
        );

        if (!versionJsonFile.isFile()) {
            throw new IllegalStateException("Missing installed NeoForge version JSON: " + versionJsonFile.getAbsolutePath());
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
                Logging.i(TAG, "NeoForge library hash mismatch, redownloading: " + target.getAbsolutePath());
            }

            ArrayList<String> urls = resolveLibraryDownloadUrls(library, artifactPath);
            if (urls.isEmpty()) {
                Logging.i(TAG, "No download URL known for NeoForge library: " + library.optString("name", ""));
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
        notify(listener, 90, "Downloading " + downloads.size() + " NeoForge libraries...");
        ParallelDownloadExecutor.run(
                downloads,
                MAX_PARALLEL_DOWNLOADS,
                download -> downloadLibraryWithFallbacks(context, download),
                (completed, ignoredTotal, download) -> {
                    int progress = 90 + (int) ((completed * 8L) / total);
                    notify(listener, progress, "Downloading NeoForge libraries (" + completed + "/" + total + "): " + download.displayName);
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
                Logging.i(TAG, "NeoForge library download failed from " + url + ": " + e.getMessage());
            }
        }

        throw new IllegalStateException("Unable to download NeoForge library " + download.libraryName, lastError);
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

        addUrlIfMissing(urls, joinRepoAndPath("https://maven.neoforged.net/releases/", artifactPath));
        addUrlIfMissing(urls, joinRepoAndPath("https://maven.minecraftforge.net/", artifactPath));
        addUrlIfMissing(urls, joinRepoAndPath("https://repo1.maven.org/maven2/", artifactPath));
        addUrlIfMissing(urls, joinRepoAndPath("https://libraries.minecraft.net/", artifactPath));

        return urls;
    }

    private static void addUrlIfMissing(@NonNull ArrayList<String> urls, @NonNull String url) {
        if (!urls.contains(url)) urls.add(url);
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
                notify(listener, 10, "Reinstalling clean vanilla " + minecraftVersionId + " for NeoForge...");
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
            Logging.e(TAG, "Unable to validate vanilla client jar for NeoForge", throwable);
            return false;
        }
    }

    private static void cleanupNeoForgeTargetVersionOnly(@NonNull String neoForgeVersionId) {
        File customVersionDir = MinecraftVersionInstaller.getVersionDirectory(neoForgeVersionId);
        if (customVersionDir.exists()) {
            Logging.i(TAG, "Deleting stale NeoForge version dir: " + customVersionDir.getAbsolutePath());
            deleteRecursively(customVersionDir);
        }
    }

    private static void cleanupNeoForgeGeneratedOutputs(
            @NonNull String minecraftVersionId,
            @NonNull String neoForgeVersionId,
            @NonNull String fullNeoForgeVersion
    ) {
        File minecraftClientRoot = new File(MinecraftVersionInstaller.getLibrariesDirectory(), "net/minecraft/client");
        File[] clientChildren = minecraftClientRoot.listFiles();
        if (clientChildren != null) {
            for (File child : clientChildren) {
                if (child.getName().startsWith(minecraftVersionId + "-")) {
                    Logging.i(TAG, "Deleting stale NeoForge generated client output: " + child.getAbsolutePath());
                    deleteRecursively(child);
                }
            }
        }

        File customVersionDir = MinecraftVersionInstaller.getVersionDirectory(neoForgeVersionId);
        if (customVersionDir.exists()) {
            Logging.i(TAG, "Deleting stale NeoForge version dir: " + customVersionDir.getAbsolutePath());
            deleteRecursively(customVersionDir);
        }

        deleteEmptyDirectory(new File(
                MinecraftVersionInstaller.getLibrariesDirectory(),
                "net/neoforged/neoforge/" + fullNeoForgeVersion
        ));
        deleteEmptyDirectory(new File(
                MinecraftVersionInstaller.getLibrariesDirectory(),
                "net/neoforged/forge/" + fullNeoForgeVersion
        ));
    }

    private static void deleteEmptyDirectory(@NonNull File directory) {
        if (!directory.isDirectory()) return;
        File[] files = directory.listFiles();
        if (files != null && files.length == 0) {
            deleteRecursively(directory);
        }
    }

    private static void flattenInheritedProfileIfEnabled(
            @NonNull Context context,
            @NonNull String installedVersionId,
            @Nullable MinecraftVersionInstaller.InstallProgressListener listener
    ) throws Exception {
        if (!LauncherPreferences.isRemoveInheritedVanillaAfterLoaderInstall(context)) return;

        notify(listener, 96, "Flattening NeoForge profile...");
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
        if (value.contains("neoforge") || value.contains("neoforged")) return "NeoForge";
        return "Vanilla";
    }


    @Nullable
    private static String findReusableNeoForgeVersionId(
            @NonNull String targetVersionId,
            @NonNull String fullNeoForgeVersion,
            boolean legacyNeoForgedForge
    ) {
        ArrayList<String> candidates = new ArrayList<>();
        candidates.add(targetVersionId);
        candidates.add(fullNeoForgeVersion);
        candidates.add("neoforge-" + fullNeoForgeVersion);
        candidates.add("neoforged-forge-" + fullNeoForgeVersion);
        candidates.add("forge-" + fullNeoForgeVersion);

        for (String candidate : candidates) {
            if (isNeoForgeCoordinateVersion(candidate, fullNeoForgeVersion, legacyNeoForgedForge)) return candidate;
        }

        File versionsDir = MinecraftVersionInstaller.getVersionsDirectory();
        File[] children = versionsDir.listFiles();
        if (children == null) return null;

        for (File child : children) {
            if (child == null || !child.isDirectory()) continue;
            String id = child.getName();
            if (targetVersionId.equals(id)) continue;
            if (isNeoForgeCoordinateVersion(id, fullNeoForgeVersion, legacyNeoForgedForge)) return id;
        }

        return null;
    }

    private static boolean isNeoForgeCoordinateVersion(
            @Nullable String versionId,
            @NonNull String fullNeoForgeVersion,
            boolean legacyNeoForgedForge
    ) {
        if (versionId == null || versionId.trim().isEmpty()) return false;

        File versionDir = MinecraftVersionInstaller.getVersionDirectory(versionId);
        File versionJsonFile = new File(versionDir, versionId + ".json");
        if (!versionJsonFile.isFile()) return false;

        try {
            JSONObject json = new JSONObject(readString(versionJsonFile));
            String coordinate = legacyNeoForgedForge
                    ? "net.neoforged:forge:" + fullNeoForgeVersion
                    : "net.neoforged:neoforge:" + fullNeoForgeVersion;
            String text = json.toString();
            String lower = text.toLowerCase(Locale.ROOT);
            if (!text.contains(coordinate)
                    && !(text.contains(fullNeoForgeVersion) && (lower.contains("neoforge") || lower.contains("neoforged")))) {
                return false;
            }
            return isLaunchableVersionProfile(versionId, json);
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to inspect NeoForge profile " + versionId + ": " + throwable.getMessage());
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

        return !versionJson.optString("mainClass", "").trim().isEmpty()
                && versionJson.toString().contains("net.neoforged");
    }

    private static void cloneInstalledVersionProfileIfNeeded(
            @NonNull String sourceVersionId,
            @NonNull String targetVersionId
    ) throws Exception {
        if (sourceVersionId.equals(targetVersionId)) return;

        File sourceDir = MinecraftVersionInstaller.getVersionDirectory(sourceVersionId);
        File sourceJson = new File(sourceDir, sourceVersionId + ".json");
        if (!sourceJson.isFile()) {
            throw new IllegalStateException("Reusable NeoForge profile JSON is missing: " + sourceJson.getAbsolutePath());
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

        Logging.i(TAG, "Cloned reusable NeoForge profile " + sourceVersionId + " -> " + targetVersionId);
    }

    @NonNull
    private static String createUniqueNeoForgeVersionId(@NonNull String requestedName, @NonNull String minecraftVersionId) {
        String base = sanitizeVersionId(requestedName);
        if (base.isEmpty()) base = sanitizeVersionId(minecraftVersionId + " NeoForge");
        if (base.isEmpty()) base = "neoforge-" + UUID.randomUUID();

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

    private static void patchInstallerProfile(
            @NonNull File originalInstallerJar,
            @NonNull File patchedInstallerJar,
            @NonNull String customVersionId
    ) throws Exception {
        File patchedParent = patchedInstallerJar.getParentFile();
        if (patchedParent != null) ensureDirectory(patchedParent);

        File tempJar = new File(patchedInstallerJar.getAbsolutePath() + ".tmp");
        File profileJson = new File(
                patchedParent != null ? patchedParent : PathManager.DIR_CACHE,
                "install_profile-" + safeCacheFileName(customVersionId) + ".json"
        );

        if (tempJar.exists() && !tempJar.delete()) {
            throw new IllegalStateException("Unable to delete old temp installer: " + tempJar.getAbsolutePath());
        }
        if (patchedInstallerJar.exists() && !patchedInstallerJar.delete()) {
            throw new IllegalStateException("Unable to delete old patched installer: " + patchedInstallerJar.getAbsolutePath());
        }

        try (ZipFile zipFile = new ZipFile(originalInstallerJar)) {
            ZipEntry entry = zipFile.getEntry("install_profile.json");
            if (entry == null) {
                throw new IllegalStateException("install_profile.json not found in " + originalInstallerJar.getName());
            }

            try (InputStream input = zipFile.getInputStream(entry);
                 FileOutputStream output = new FileOutputStream(profileJson)) {
                copy(input, output);
            }
        }

        JSONObject profile = new JSONObject(readString(profileJson));
        if (profile.has("spec")) {
            if (!profile.has("version")) {
                throw new IllegalStateException("Unable to find NeoForge install_profile version key.");
            }
            profile.put("version", customVersionId);
        } else {
            JSONObject install = profile.optJSONObject("install");
            if (install == null) {
                throw new IllegalStateException("Unable to find NeoForge install_profile install block.");
            }
            install.put("target", customVersionId);
            profile.put("install", install);
        }

        relaxGeneratedClientOutputHashes(profile);
        writeString(profileJson, profile.toString());

        try (ZipFile zipFile = new ZipFile(originalInstallerJar);
             ZipOutputStream output = new ZipOutputStream(new FileOutputStream(tempJar))) {
            java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();

            while (entries.hasMoreElements()) {
                ZipEntry original = entries.nextElement();
                if (shouldSkipSignatureFile(original.getName())) continue;

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

        if (!tempJar.renameTo(patchedInstallerJar)) {
            try (InputStream input = new FileInputStream(tempJar);
                 FileOutputStream output = new FileOutputStream(patchedInstallerJar)) {
                copy(input, output);
            }
            //noinspection ResultOfMethodCallIgnored
            tempJar.delete();
        }

        //noinspection ResultOfMethodCallIgnored
        profileJson.delete();
    }

    @NonNull
    private static String safeCacheFileName(@NonNull String value) {
        String cleaned = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        return cleaned.isEmpty() ? "neoforge" : cleaned;
    }

    private static void relaxGeneratedClientOutputHashes(@NonNull JSONObject profile) {
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
                    Logging.i(TAG, "Relaxing NeoForge generated output hash check for " + key);
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
    private static String resolveInstalledNeoForgeVersionId(
            @NonNull String customVersionId,
            @NonNull String minecraftVersionId,
            @NonNull String loaderVersion,
            @NonNull String fullNeoForgeVersion,
            boolean legacyNeoForgedForge
    ) {
        if (isInstalledVersion(customVersionId)) return customVersionId;

        ArrayList<String> candidates = new ArrayList<>();
        candidates.add("neoforge-" + fullNeoForgeVersion);
        candidates.add(minecraftVersionId + "-neoforge-" + fullNeoForgeVersion);
        candidates.add(minecraftVersionId + "-neoforge-" + loaderVersion);
        candidates.add(fullNeoForgeVersion);

        if (legacyNeoForgedForge) {
            candidates.add(minecraftVersionId + "-forge-" + loaderVersion);
            candidates.add(minecraftVersionId + "-forge" + fullNeoForgeVersion);
        }

        for (String candidate : candidates) {
            if (isInstalledVersion(candidate)) return candidate;
        }

        throw new IllegalStateException("NeoForge installer finished, but no NeoForge version JSON was found for " + customVersionId);
    }

    private static boolean isInstalledVersion(@NonNull String versionId) {
        File json = new File(MinecraftVersionInstaller.getVersionDirectory(versionId), versionId + ".json");
        return json.isFile();
    }

    @NonNull
    private static File resolveInstallerRuntime(@NonNull String minecraftVersionId) {
        String[] preferred = isModernJavaMinecraftVersion(minecraftVersionId)
                ? new String[]{"Internal-21", "Internal-25", "Internal-17", "Internal-8"}
                : new String[]{"Internal-17", "Internal-21", "Internal-25", "Internal-8"};

        for (String name : preferred) {
            File runtime = MultiRTUtils.getRuntimeDir(name);
            if (runtime.isDirectory() && new File(runtime, "bin/java").isFile()) {
                return runtime;
            }
        }
        throw new IllegalStateException("No internal Java runtime is installed for NeoForge installer.");
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
            Logging.i(TAG, "Existing NeoForge file hash mismatch, redownloading: " + target.getAbsolutePath());
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
                Logging.i(TAG, "NeoForge download source failed for " + candidateUrl
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
                Logging.i(TAG, "NeoForge metadata source failed for " + candidateUrl
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

    /**
     * Do not blindly reinitialize PathManager here.
     *
     * The create-instance flow may already have pointed PathManager at a custom
     * scoped/storage root. Re-running initContextConstants(context) can reset the
     * installer back to the default app storage, so Forge/NeoForge appears to
     * ignore the selected storage location. Only initialize when no active
     * Minecraft home has been prepared yet.
     */
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

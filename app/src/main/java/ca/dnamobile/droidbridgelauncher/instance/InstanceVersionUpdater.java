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

package ca.dnamobile.droidbridgelauncher.instance;

import static org.apache.commons.lang3.StringUtils.isBlank;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.ui.version.MinecraftVersionInstaller;

/**
 * InstanceDetails-side updater for changing an instance Minecraft version or loader.
 *
 * This intentionally uses official launcher metadata / loader metadata directly and
 * does not depend on the prior implementation-only fragments or GUI installer activities.
 */
public final class InstanceVersionUpdater {
    private static final String TAG = "InstanceVersionUpdater";

    private static final String MOJANG_VERSION_MANIFEST =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String FABRIC_META = "https://meta.fabricmc.net/v2";
    private static final String FORGE_MAVEN = "https://maven.minecraftforge.net";
    private static final String NEOFORGE_MAVEN = "https://maven.neoforged.net/releases";

    private InstanceVersionUpdater() {
    }

    public enum LoaderKind {
        VANILLA("Vanilla"),
        FABRIC("Fabric"),
        FORGE("Forge"),
        NEOFORGE("NeoForge");

        @NonNull
        public final String displayName;

        LoaderKind(@NonNull String displayName) {
            this.displayName = displayName;
        }
    }

    public interface Listener {
        void onStatus(@NonNull String message);
        void onProgress(int current, int total);
    }

    public static final class MinecraftRelease {
        @NonNull
        public final String id;
        @NonNull
        public final String url;

        MinecraftRelease(@NonNull String id, @NonNull String url) {
            this.id = id;
            this.url = url;
        }
    }

    public static final class LoaderVersion {
        @NonNull
        public final LoaderKind kind;
        @NonNull
        public final String displayVersion;
        @NonNull
        public final String installVersion;
        @NonNull
        public final String minecraftVersion;
        public final boolean stable;

        LoaderVersion(
                @NonNull LoaderKind kind,
                @NonNull String displayVersion,
                @NonNull String installVersion,
                @NonNull String minecraftVersion,
                boolean stable
        ) {
            this.kind = kind;
            this.displayVersion = displayVersion;
            this.installVersion = installVersion;
            this.minecraftVersion = minecraftVersion;
            this.stable = stable;
        }

        @NonNull
        public String getDisplayLabel() {
            return displayVersion + (shouldShowBetaLabel() ? " (beta)" : "");
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

        private boolean shouldShowBetaLabel() {
            if (stable) return false;

            // Fabric's metadata can mark newer loader entries as not stable even when
            // the version itself is not named beta/alpha. Only show beta when the
            // version string explicitly says it is a pre-release.
            if (kind == LoaderKind.FABRIC) {
                return isPreReleaseLoaderVersion(displayVersion)
                        || isPreReleaseLoaderVersion(installVersion);
            }

            return true;
        }
    }

    public static final class UpdateResult {
        @NonNull
        public final String loader;
        @NonNull
        public final String baseVersionId;
        @NonNull
        public final String minecraftVersionId;
        @NonNull
        public final String versionType;
        @Nullable
        public final String loaderVersion;
        public final int metadataFilesUpdated;

        UpdateResult(
                @NonNull String loader,
                @NonNull String baseVersionId,
                @NonNull String minecraftVersionId,
                @NonNull String versionType,
                @Nullable String loaderVersion,
                int metadataFilesUpdated
        ) {
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
        JSONObject manifest = new JSONObject(httpGetText(MOJANG_VERSION_MANIFEST));
        JSONArray versions = manifest.getJSONArray("versions");
        ArrayList<MinecraftRelease> releases = new ArrayList<>();
        for (int i = 0; i < versions.length(); i++) {
            JSONObject version = versions.getJSONObject(i);
            if (!"release".equalsIgnoreCase(version.optString("type", ""))) continue;
            String id = version.optString("id", "").trim();
            String url = version.optString("url", "").trim();
            if (!id.isEmpty() && !url.isEmpty()) releases.add(new MinecraftRelease(id, url));
        }
        return releases;
    }

    @NonNull
    public static ArrayList<LoaderVersion> fetchLoaderVersions(
            @NonNull LoaderKind kind,
            @NonNull String minecraftVersion
    ) throws Exception {
        switch (kind) {
            case FABRIC:
                return fetchFabricLoaderVersions(minecraftVersion);
            case FORGE:
                return fetchForgeLoaderVersions(minecraftVersion);
            case NEOFORGE:
                return fetchNeoForgeLoaderVersions(minecraftVersion);
            case VANILLA:
            default:
                return new ArrayList<>();
        }
    }

    @Nullable
    public static LoaderVersion findLatestLoaderVersion(
            @NonNull LoaderKind kind,
            @NonNull String minecraftVersion
    ) throws Exception {
        ArrayList<LoaderVersion> versions = fetchLoaderVersions(kind, minecraftVersion);
        if (versions.isEmpty()) return null;
        return versions.get(0);
    }

    @Nullable
    public static String resolveCurrentLoaderVersion(
            @NonNull LoaderKind kind,
            @Nullable String baseVersionId,
            @NonNull String minecraftVersion
    ) {
        String fromId = extractLoaderVersionFromVersionId(kind, baseVersionId, minecraftVersion);
        if (!isBlank(fromId)) return fromId;

        String fromJson = extractLoaderVersionFromVersionJson(kind, baseVersionId, minecraftVersion);
        if (!isBlank(fromJson)) return fromJson;

        return null;
    }

    public static boolean isSameLoaderVersion(@Nullable String first, @Nullable String second) {
        if (isBlank(first) || isBlank(second)) return false;
        return normalizeLoaderVersionForCompare(first).equals(normalizeLoaderVersionForCompare(second));
    }

    @NonNull
    public static UpdateResult updateInstanceVersion(
            @NonNull Context context,
            @NonNull File rootDirectory,
            @NonNull File gameDirectory,
            @NonNull String instanceName,
            @Nullable String currentLoader,
            @NonNull String targetMinecraftVersion,
            @Nullable Listener listener
    ) throws Exception {
        LoaderKind kind = resolveLoaderKind(currentLoader);
        if (kind == null) kind = LoaderKind.VANILLA;

        notify(listener, "Preparing Minecraft " + targetMinecraftVersion + "...");
        notifyProgress(listener, 0, 4);
        ensureVanillaVersionInstalled(targetMinecraftVersion, listener);
        notifyProgress(listener, 1, 4);

        String newBaseVersionId;
        String newLoader = kind.displayName;
        String loaderVersion = null;

        if (kind == LoaderKind.VANILLA) {
            newBaseVersionId = targetMinecraftVersion;
        } else {
            notify(listener, "Finding latest " + kind.displayName + " loader for " + targetMinecraftVersion + "...");
            LoaderVersion latestLoader = findLatestLoaderVersion(kind, targetMinecraftVersion);
            if (latestLoader == null) {
                throw new IllegalStateException("No " + kind.displayName + " loader found for Minecraft " + targetMinecraftVersion);
            }
            loaderVersion = latestLoader.displayVersion;
            notifyProgress(listener, 2, 4);
            newBaseVersionId = installLoaderProfile(latestLoader, listener);
        }

        notifyProgress(listener, 3, 4);
        int metadataUpdated = persistInstanceMetadataBestEffort(
                rootDirectory,
                gameDirectory,
                instanceName,
                newLoader,
                newBaseVersionId,
                targetMinecraftVersion,
                "release"
        );
        notifyProgress(listener, 4, 4);

        return new UpdateResult(newLoader, newBaseVersionId, targetMinecraftVersion, "release", loaderVersion, metadataUpdated);
    }

    @NonNull
    public static UpdateResult updateInstanceLoader(
            @NonNull Context context,
            @NonNull File rootDirectory,
            @NonNull File gameDirectory,
            @NonNull String instanceName,
            @NonNull LoaderVersion selectedLoader,
            @Nullable Listener listener
    ) throws Exception {
        notify(listener, "Preparing Minecraft " + selectedLoader.minecraftVersion + "...");
        notifyProgress(listener, 0, 4);
        ensureVanillaVersionInstalled(selectedLoader.minecraftVersion, listener);
        notifyProgress(listener, 1, 4);

        notify(listener, "Installing " + selectedLoader.kind.displayName + " " + selectedLoader.displayVersion + "...");
        String newBaseVersionId = installLoaderProfile(selectedLoader, listener);
        notifyProgress(listener, 3, 4);

        int metadataUpdated = persistInstanceMetadataBestEffort(
                rootDirectory,
                gameDirectory,
                instanceName,
                selectedLoader.kind.displayName,
                newBaseVersionId,
                selectedLoader.minecraftVersion,
                "release"
        );
        notifyProgress(listener, 4, 4);

        return new UpdateResult(
                selectedLoader.kind.displayName,
                newBaseVersionId,
                selectedLoader.minecraftVersion,
                "release",
                selectedLoader.displayVersion,
                metadataUpdated
        );
    }

    @Nullable
    public static LoaderKind resolveLoaderKind(@Nullable String loader) {
        if (loader == null) return null;
        String value = loader.trim().toLowerCase(Locale.US)
                .replace(" ", "")
                .replace("_", "")
                .replace("-", "");
        if (value.isEmpty()) return null;
        if (value.equals("vanilla") || value.contains("vanilla")) return LoaderKind.VANILLA;
        if (value.equals("neoforge") || value.contains("neoforge")) return LoaderKind.NEOFORGE;
        if (value.equals("fabric") || value.contains("fabric")) return LoaderKind.FABRIC;
        if (value.equals("forge") || value.contains("forge")) return LoaderKind.FORGE;
        return null;
    }

    @NonNull
    private static ArrayList<LoaderVersion> fetchFabricLoaderVersions(@NonNull String minecraftVersion) throws Exception {
        String url = FABRIC_META + "/versions/loader/" + urlEncodePath(minecraftVersion);
        JSONArray versions = new JSONArray(httpGetText(url));
        ArrayList<LoaderVersion> result = new ArrayList<>();
        for (int i = 0; i < versions.length(); i++) {
            JSONObject item = versions.getJSONObject(i);
            JSONObject loader = item.getJSONObject("loader");
            String version = loader.optString("version", "").trim();
            if (version.isEmpty()) continue;
            boolean stable = loader.optBoolean("stable", true);
            result.add(new LoaderVersion(LoaderKind.FABRIC, version, version, minecraftVersion, stable));
        }
        Collections.sort(result, (a, b) -> {
            if (a.stable != b.stable) return a.stable ? -1 : 1;
            return -compareVersionStrings(a.displayVersion, b.displayVersion);
        });
        return result;
    }

    @NonNull
    private static ArrayList<LoaderVersion> fetchForgeLoaderVersions(@NonNull String minecraftVersion) throws Exception {
        String metadata = httpGetText(FORGE_MAVEN + "/net/minecraftforge/forge/maven-metadata.xml");
        ArrayList<String> allVersions = parseMavenVersions(metadata);
        ArrayList<LoaderVersion> result = new ArrayList<>();
        String prefix = minecraftVersion + "-";
        for (String fullVersion : allVersions) {
            if (!fullVersion.startsWith(prefix)) continue;
            String loaderVersion = fullVersion.substring(prefix.length());
            boolean stable = !isPreReleaseLoaderVersion(loaderVersion);
            result.add(new LoaderVersion(LoaderKind.FORGE, loaderVersion, fullVersion, minecraftVersion, stable));
        }
        Collections.sort(result, (a, b) -> {
            if (a.stable != b.stable) return a.stable ? -1 : 1;
            return -compareVersionStrings(a.displayVersion, b.displayVersion);
        });
        return result;
    }

    @NonNull
    private static ArrayList<LoaderVersion> fetchNeoForgeLoaderVersions(@NonNull String minecraftVersion) throws Exception {
        ArrayList<LoaderVersion> result = new ArrayList<>();

        if ("1.20.1".equals(minecraftVersion)) {
            String legacyMetadata = httpGetText(NEOFORGE_MAVEN + "/net/neoforged/forge/maven-metadata.xml");
            String prefix = minecraftVersion + "-";
            for (String fullVersion : parseMavenVersions(legacyMetadata)) {
                if (!fullVersion.startsWith(prefix)) continue;
                String loaderVersion = fullVersion.substring(prefix.length());
                boolean stable = !isPreReleaseLoaderVersion(loaderVersion);
                result.add(new LoaderVersion(LoaderKind.NEOFORGE, loaderVersion, fullVersion, minecraftVersion, stable));
            }
        }

        String metadata = httpGetText(NEOFORGE_MAVEN + "/net/neoforged/neoforge/maven-metadata.xml");
        for (String version : parseMavenVersions(metadata)) {
            String gameVersion = formatNeoForgeGameVersion(version);
            if (!minecraftVersion.equals(gameVersion)) continue;
            boolean stable = !isPreReleaseLoaderVersion(version);
            result.add(new LoaderVersion(LoaderKind.NEOFORGE, version, version, minecraftVersion, stable));
        }

        Collections.sort(result, (a, b) -> {
            if (a.stable != b.stable) return a.stable ? -1 : 1;
            return -compareVersionStrings(a.displayVersion, b.displayVersion);
        });
        return result;
    }

    @Nullable
    private static String extractLoaderVersionFromVersionId(
            @NonNull LoaderKind kind,
            @Nullable String baseVersionId,
            @NonNull String minecraftVersion
    ) {
        if (isBlank(baseVersionId)) return null;

        String value = baseVersionId.trim();
        String lower = value.toLowerCase(Locale.US);
        String mcSuffix = "-" + minecraftVersion;
        String mcPrefix = minecraftVersion + "-";

        switch (kind) {
            case FABRIC: {
                String fabricPrefix = "fabric-loader-";
                if (lower.startsWith(fabricPrefix) && value.endsWith(mcSuffix)) {
                    return value.substring(fabricPrefix.length(), value.length() - mcSuffix.length());
                }

                Matcher matcher = Pattern.compile(
                        "fabric(?:[-_ ]?loader)?[-_ ]?([0-9][A-Za-z0-9._+\\-]*)[-_ ]?" + Pattern.quote(minecraftVersion),
                        Pattern.CASE_INSENSITIVE
                ).matcher(value);
                if (matcher.find()) return matcher.group(1);
                return null;
            }

            case FORGE: {
                String forgePrefix = "forge-";
                String fullVersion = null;
                if (lower.startsWith(forgePrefix)) {
                    fullVersion = value.substring(forgePrefix.length());
                } else {
                    Matcher matcher = Pattern.compile(
                            "forge[-_ ]?" + Pattern.quote(minecraftVersion) + "[-_ ]([0-9][A-Za-z0-9._+\\-]*)",
                            Pattern.CASE_INSENSITIVE
                    ).matcher(value);
                    if (matcher.find()) return matcher.group(1);

                    matcher = Pattern.compile(
                            Pattern.quote(minecraftVersion) + "[-_ ]forge[-_ ]([0-9][A-Za-z0-9._+\\-]*)",
                            Pattern.CASE_INSENSITIVE
                    ).matcher(value);
                    if (matcher.find()) return matcher.group(1);
                }

                if (!isBlank(fullVersion) && fullVersion.startsWith(mcPrefix)) {
                    return fullVersion.substring(mcPrefix.length());
                }
                return null;
            }

            case NEOFORGE: {
                String neoForgePrefix = "neoforge-";
                String rest = null;
                if (lower.startsWith(neoForgePrefix)) {
                    rest = value.substring(neoForgePrefix.length());
                } else {
                    Matcher matcher = Pattern.compile(
                            "neoforge[-_ ]?" + Pattern.quote(minecraftVersion) + "[-_ ]([0-9][A-Za-z0-9._+\\-]*)",
                            Pattern.CASE_INSENSITIVE
                    ).matcher(value);
                    if (matcher.find()) return matcher.group(1);

                    matcher = Pattern.compile(
                            Pattern.quote(minecraftVersion) + "[-_ ]neoforge[-_ ]([0-9][A-Za-z0-9._+\\-]*)",
                            Pattern.CASE_INSENSITIVE
                    ).matcher(value);
                    if (matcher.find()) return matcher.group(1);
                }

                if (!isBlank(rest)) {
                    if (rest.startsWith(mcPrefix)) return rest.substring(mcPrefix.length());
                    if (rest.startsWith(minecraftVersion + "-")) return rest.substring((minecraftVersion + "-").length());
                    if (rest.matches("^[0-9].*")) return rest;
                }
                return null;
            }

            case VANILLA:
            default:
                return null;
        }
    }

    @Nullable
    private static String extractLoaderVersionFromVersionJson(
            @NonNull LoaderKind kind,
            @Nullable String baseVersionId,
            @NonNull String minecraftVersion
    ) {
        if (isBlank(baseVersionId)) return null;

        try {
            File jsonFile = new File(getVersionDirectory(baseVersionId.trim()), baseVersionId.trim() + ".json");
            if (!jsonFile.isFile()) return null;

            JSONObject versionJson = new JSONObject(readText(jsonFile));
            JSONArray libraries = versionJson.optJSONArray("libraries");
            if (libraries == null) return null;

            for (int i = 0; i < libraries.length(); i++) {
                JSONObject library = libraries.optJSONObject(i);
                if (library == null) continue;

                String name = library.optString("name", "").trim();
                String loaderVersion = extractLoaderVersionFromLibraryName(kind, name, minecraftVersion);
                if (!isBlank(loaderVersion)) return loaderVersion;
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to read current loader version from version JSON", throwable);
        }

        return null;
    }

    @Nullable
    private static String extractLoaderVersionFromLibraryName(
            @NonNull LoaderKind kind,
            @NonNull String libraryName,
            @NonNull String minecraftVersion
    ) {
        if (isBlank(libraryName)) return null;

        String[] parts = libraryName.split(":");
        if (parts.length < 3) return null;

        String group = parts[0].toLowerCase(Locale.US);
        String artifact = parts[1].toLowerCase(Locale.US);
        String version = parts[2];

        switch (kind) {
            case FABRIC:
                if ("net.fabricmc".equals(group) && "fabric-loader".equals(artifact)) return version;
                return null;

            case FORGE:
                if ("net.minecraftforge".equals(group) && "forge".equals(artifact)) {
                    String prefix = minecraftVersion + "-";
                    return version.startsWith(prefix) ? version.substring(prefix.length()) : version;
                }
                return null;

            case NEOFORGE:
                if ("net.neoforged".equals(group) && "neoforge".equals(artifact)) return version;
                if ("net.neoforged".equals(group) && "forge".equals(artifact)) {
                    String prefix = minecraftVersion + "-";
                    return version.startsWith(prefix) ? version.substring(prefix.length()) : version;
                }
                return null;

            case VANILLA:
            default:
                return null;
        }
    }

    @NonNull
    private static String normalizeLoaderVersionForCompare(@NonNull String value) {
        String normalized = value.trim().toLowerCase(Locale.US);
        normalized = normalized.replace("_", "-");
        normalized = normalized.replace("+", "-");
        while (normalized.contains("--")) normalized = normalized.replace("--", "-");
        return normalized;
    }

    @NonNull
    private static String installLoaderProfile(@NonNull LoaderVersion loaderVersion, @Nullable Listener listener) throws Exception {
        switch (loaderVersion.kind) {
            case FABRIC:
                return installFabricProfile(loaderVersion, listener);
            case FORGE:
                return installForgeLikeProfile(loaderVersion, listener);
            case NEOFORGE:
                return installNeoForgeProfile(loaderVersion, listener);
            case VANILLA:
            default:
                return loaderVersion.minecraftVersion;
        }
    }

    @NonNull
    private static String installFabricProfile(@NonNull LoaderVersion loaderVersion, @Nullable Listener listener) throws Exception {
        String localVersionId = "fabric-loader-" + loaderVersion.displayVersion + "-" + loaderVersion.minecraftVersion;
        notify(listener, "Downloading Fabric loader profile " + loaderVersion.displayVersion + "...");
        String profileUrl = FABRIC_META
                + "/versions/loader/"
                + urlEncodePath(loaderVersion.minecraftVersion)
                + "/"
                + urlEncodePath(loaderVersion.installVersion)
                + "/profile/json";
        JSONObject versionJson = new JSONObject(httpGetText(profileUrl));
        versionJson.put("id", localVersionId);
        versionJson.put("type", "release");
        versionJson.put("inheritsFrom", loaderVersion.minecraftVersion);
        writeVersionJson(localVersionId, versionJson);
        notifyProgress(listener, 2, 4);
        return localVersionId;
    }

    @NonNull
    private static String installForgeLikeProfile(@NonNull LoaderVersion loaderVersion, @Nullable Listener listener) throws Exception {
        String fullVersion = loaderVersion.installVersion;
        String localVersionId = "forge-" + fullVersion;
        String installerUrl = FORGE_MAVEN
                + "/net/minecraftforge/forge/"
                + urlEncodePath(fullVersion)
                + "/forge-"
                + urlEncodePath(fullVersion)
                + "-installer.jar";
        File installerFile = downloadInstaller(installerUrl, "forge-" + sanitizeFilePart(fullVersion) + "-installer.jar", listener);
        JSONObject versionJson = readForgeLikeVersionJsonFromInstaller(installerFile);
        normalizeLoaderVersionJson(versionJson, localVersionId, loaderVersion.minecraftVersion);
        writeVersionJson(localVersionId, versionJson);
        extractInstallerLibraries(installerFile, listener);
        notifyProgress(listener, 2, 4);
        return localVersionId;
    }

    @NonNull
    private static String installNeoForgeProfile(@NonNull LoaderVersion loaderVersion, @Nullable Listener listener) throws Exception {
        boolean legacy1201 = loaderVersion.installVersion.startsWith(loaderVersion.minecraftVersion + "-");
        String localVersionId = legacy1201
                ? "neoforge-" + loaderVersion.installVersion
                : "neoforge-" + loaderVersion.minecraftVersion + "-" + loaderVersion.installVersion;

        String installerUrl;
        String installerName;
        if (legacy1201) {
            installerUrl = NEOFORGE_MAVEN
                    + "/net/neoforged/forge/"
                    + urlEncodePath(loaderVersion.installVersion)
                    + "/forge-"
                    + urlEncodePath(loaderVersion.installVersion)
                    + "-installer.jar";
            installerName = "neoforge-legacy-" + sanitizeFilePart(loaderVersion.installVersion) + "-installer.jar";
        } else {
            installerUrl = NEOFORGE_MAVEN
                    + "/net/neoforged/neoforge/"
                    + urlEncodePath(loaderVersion.installVersion)
                    + "/neoforge-"
                    + urlEncodePath(loaderVersion.installVersion)
                    + "-installer.jar";
            installerName = "neoforge-" + sanitizeFilePart(loaderVersion.installVersion) + "-installer.jar";
        }

        File installerFile = downloadInstaller(installerUrl, installerName, listener);
        JSONObject versionJson = readForgeLikeVersionJsonFromInstaller(installerFile);
        normalizeLoaderVersionJson(versionJson, localVersionId, loaderVersion.minecraftVersion);
        writeVersionJson(localVersionId, versionJson);
        extractInstallerLibraries(installerFile, listener);
        notifyProgress(listener, 2, 4);
        return localVersionId;
    }

    private static void ensureVanillaVersionInstalled(@NonNull String minecraftVersion, @Nullable Listener listener) throws Exception {
        File versionDir = getVersionDirectory(minecraftVersion);
        File jsonFile = new File(versionDir, minecraftVersion + ".json");
        File jarFile = new File(versionDir, minecraftVersion + ".jar");
        if (jsonFile.isFile() && jarFile.isFile()) return;

        notify(listener, "Finding Minecraft " + minecraftVersion + " metadata...");
        MinecraftRelease release = findMinecraftRelease(minecraftVersion);
        if (release == null) throw new IllegalStateException("Minecraft release not found: " + minecraftVersion);

        JSONObject versionJson = new JSONObject(httpGetText(release.url));
        if (!versionDir.exists() && !versionDir.mkdirs()) {
            throw new IOException("Unable to create version folder: " + versionDir.getAbsolutePath());
        }
        writeText(jsonFile, versionJson.toString(2));

        if (!jarFile.isFile()) {
            JSONObject downloads = versionJson.optJSONObject("downloads");
            JSONObject client = downloads == null ? null : downloads.optJSONObject("client");
            String jarUrl = client == null ? "" : client.optString("url", "").trim();
            if (jarUrl.isEmpty()) throw new IllegalStateException("Missing client jar URL for " + minecraftVersion);
            notify(listener, "Downloading Minecraft " + minecraftVersion + " client...");
            downloadToFile(jarUrl, jarFile, listener, 1, 4);
        }
    }

    @Nullable
    private static MinecraftRelease findMinecraftRelease(@NonNull String minecraftVersion) throws Exception {
        for (MinecraftRelease release : fetchMinecraftReleases()) {
            if (minecraftVersion.equals(release.id)) return release;
        }
        return null;
    }

    @NonNull
    private static JSONObject readForgeLikeVersionJsonFromInstaller(@NonNull File installerFile) throws Exception {
        try (ZipFile zipFile = new ZipFile(installerFile)) {
            ZipEntry versionEntry = zipFile.getEntry("version.json");
            if (versionEntry != null && !versionEntry.isDirectory()) {
                return new JSONObject(readZipEntryText(zipFile, versionEntry));
            }

            ZipEntry installProfileEntry = zipFile.getEntry("install_profile.json");
            if (installProfileEntry != null && !installProfileEntry.isDirectory()) {
                JSONObject installProfile = new JSONObject(readZipEntryText(zipFile, installProfileEntry));
                JSONObject versionInfo = installProfile.optJSONObject("versionInfo");
                if (versionInfo != null) return versionInfo;
            }
        }
        throw new IllegalStateException("Installer does not contain version.json or install_profile.json versionInfo: " + installerFile.getName());
    }

    private static void normalizeLoaderVersionJson(
            @NonNull JSONObject versionJson,
            @NonNull String localVersionId,
            @NonNull String minecraftVersion
    ) throws Exception {
        versionJson.put("id", localVersionId);
        versionJson.put("type", "release");
        if (versionJson.optString("inheritsFrom", "").trim().isEmpty()) {
            versionJson.put("inheritsFrom", minecraftVersion);
        }
    }

    private static void writeVersionJson(@NonNull String versionId, @NonNull JSONObject versionJson) throws Exception {
        File versionDir = getVersionDirectory(versionId);
        if (!versionDir.exists() && !versionDir.mkdirs()) {
            throw new IOException("Unable to create version folder: " + versionDir.getAbsolutePath());
        }
        writeText(new File(versionDir, versionId + ".json"), versionJson.toString(2));
    }

    private static void extractInstallerLibraries(@NonNull File installerFile, @Nullable Listener listener) {
        File librariesDir = getLibrariesDirectory();
        if (!librariesDir.exists() && !librariesDir.mkdirs()) return;

        int copied = 0;
        try (ZipFile zipFile = new ZipFile(installerFile)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;

                String name = entry.getName().replace('\\', '/');
                String relative = null;
                if (name.startsWith("maven/")) {
                    relative = name.substring("maven/".length());
                } else if (name.startsWith("libraries/")) {
                    relative = name.substring("libraries/".length());
                }

                if (relative == null || relative.trim().isEmpty()) continue;
                if (!(relative.endsWith(".jar") || relative.endsWith(".pom") || relative.endsWith(".json"))) continue;

                File output = new File(librariesDir, relative);
                File canonicalLibraries = librariesDir.getCanonicalFile();
                File canonicalOutput = output.getCanonicalFile();
                if (!canonicalOutput.getPath().startsWith(canonicalLibraries.getPath() + File.separator)) continue;
                if (output.isFile() && output.length() == entry.getSize()) continue;

                File parent = output.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) continue;
                try (InputStream input = zipFile.getInputStream(entry);
                     FileOutputStream outputStream = new FileOutputStream(output)) {
                    copy(input, outputStream);
                    copied++;
                }
            }
            if (copied > 0) notify(listener, "Copied " + copied + " embedded loader libraries...");
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to extract embedded loader libraries from " + installerFile.getName(), throwable);
        }
    }

    @NonNull
    private static File downloadInstaller(
            @NonNull String url,
            @NonNull String fileName,
            @Nullable Listener listener
    ) throws Exception {
        File installerDir = new File(getMinecraftHomeDirectory(), "launcher_cache/loaders");
        if (!installerDir.exists() && !installerDir.mkdirs()) {
            throw new IOException("Unable to create loader cache: " + installerDir.getAbsolutePath());
        }
        File installerFile = new File(installerDir, fileName);
        if (installerFile.isFile() && installerFile.length() > 0) return installerFile;

        notify(listener, "Downloading loader installer...");
        downloadToFile(url, installerFile, listener, 1, 4);
        return installerFile;
    }

    private static int persistInstanceMetadataBestEffort(
            @NonNull File rootDirectory,
            @NonNull File gameDirectory,
            @NonNull String instanceName,
            @NonNull String loader,
            @NonNull String baseVersionId,
            @NonNull String minecraftVersionId,
            @NonNull String versionType
    ) {
        int updated = 0;
        Set<String> visited = new HashSet<>();
        ArrayList<File> candidates = new ArrayList<>();
        collectJsonCandidates(rootDirectory, candidates, visited, 2);
        collectJsonCandidates(gameDirectory, candidates, visited, 1);

        for (File candidate : candidates) {
            try {
                String text = readText(candidate);
                JSONObject json = new JSONObject(text);
                if (!looksLikeInstanceMetadata(json, instanceName)) continue;
                updateMetadataObject(json, instanceName, loader, baseVersionId, minecraftVersionId, versionType, rootDirectory, gameDirectory);
                writeText(candidate, json.toString(2));
                updated++;
            } catch (Throwable ignored) {
            }
        }

        if (updated == 0) {
            try {
                File fallback = new File(rootDirectory, "instance.json");
                JSONObject json = new JSONObject();
                updateMetadataObject(json, instanceName, loader, baseVersionId, minecraftVersionId, versionType, rootDirectory, gameDirectory);
                File parent = fallback.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                writeText(fallback, json.toString(2));
                updated = 1;
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to write fallback instance metadata", throwable);
            }
        }

        return updated;
    }

    private static boolean looksLikeInstanceMetadata(@NonNull JSONObject json, @NonNull String instanceName) {
        boolean hasInstanceKeys = json.has("baseVersionId")
                || json.has("minecraftVersionId")
                || json.has("rootDirectory")
                || json.has("gameDirectory")
                || json.has("isIsolated")
                || json.has("isolated");
        if (!hasInstanceKeys) return false;

        String name = json.optString("name", json.optString("instanceName", "")).trim();
        return name.isEmpty() || name.equals(instanceName);
    }

    private static void updateMetadataObject(
            @NonNull JSONObject json,
            @NonNull String instanceName,
            @NonNull String loader,
            @NonNull String baseVersionId,
            @NonNull String minecraftVersionId,
            @NonNull String versionType,
            @NonNull File rootDirectory,
            @NonNull File gameDirectory
    ) throws Exception {
        if (!json.has("id")) json.put("id", instanceName);
        if (!json.has("name")) json.put("name", instanceName);
        if (json.has("instanceName")) json.put("instanceName", instanceName);
        json.put("loader", loader);
        json.put("baseVersionId", baseVersionId);
        json.put("minecraftVersionId", minecraftVersionId);
        json.put("versionType", versionType);
        if (json.has("baseVersion")) json.put("baseVersion", baseVersionId);
        if (json.has("versionId")) json.put("versionId", baseVersionId);
        if (json.has("minecraftVersion")) json.put("minecraftVersion", minecraftVersionId);
        if (json.has("rootDirectory")) json.put("rootDirectory", rootDirectory.getAbsolutePath());
        if (json.has("gameDirectory")) json.put("gameDirectory", gameDirectory.getAbsolutePath());
    }

    private static void collectJsonCandidates(
            @Nullable File directory,
            @NonNull ArrayList<File> out,
            @NonNull Set<String> visited,
            int depth
    ) {
        if (directory == null || depth < 0 || !directory.isDirectory()) return;
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            try {
                String canonical = file.getCanonicalPath();
                if (!visited.add(canonical)) continue;
            } catch (Throwable ignored) {
            }
            if (file.isDirectory()) {
                collectJsonCandidates(file, out, visited, depth - 1);
            } else if (file.isFile() && file.getName().toLowerCase(Locale.US).endsWith(".json")) {
                out.add(file);
            }
        }
    }

    @NonNull
    private static ArrayList<String> parseMavenVersions(@NonNull String metadataXml) {
        ArrayList<String> versions = new ArrayList<>();
        Matcher matcher = Pattern.compile("<version>([^<]+)</version>").matcher(metadataXml);
        while (matcher.find()) {
            String version = matcher.group(1).trim();
            if (!version.isEmpty()) versions.add(version);
        }
        return versions;
    }

    @NonNull
    private static String formatNeoForgeGameVersion(@NonNull String loaderVersion) {
        String base = loaderVersion.split("-", 2)[0];
        String[] parts = base.split("\\.");
        if (parts.length < 2) return "";

        int first = parseInt(parts[0], -1);
        if (first < 0) return "";

        if (first >= 26) {
            if (parts.length >= 3) return parts[0] + "." + parts[1] + "." + parts[2];
            return parts[0] + "." + parts[1];
        }

        if (first >= 20) {
            return "1." + parts[0] + "." + parts[1];
        }

        return "";
    }

    private static boolean isPreReleaseLoaderVersion(@NonNull String version) {
        String lower = version.toLowerCase(Locale.US);
        return lower.contains("snapshot")
                || lower.contains("beta")
                || lower.contains("alpha")
                || lower.contains("pre")
                || lower.contains("rc");
    }

    private static int compareVersionStrings(@NonNull String a, @NonNull String b) {
        String[] aParts = a.split("[\\.\\-\\+_]");
        String[] bParts = b.split("[\\.\\-\\+_]");
        int max = Math.max(aParts.length, bParts.length);
        for (int i = 0; i < max; i++) {
            String av = i < aParts.length ? aParts[i] : "0";
            String bv = i < bParts.length ? bParts[i] : "0";
            int ai = parseInt(av, Integer.MIN_VALUE);
            int bi = parseInt(bv, Integer.MIN_VALUE);
            int result;
            if (ai != Integer.MIN_VALUE && bi != Integer.MIN_VALUE) {
                result = Integer.compare(ai, bi);
            } else {
                result = av.compareToIgnoreCase(bv);
            }
            if (result != 0) return result;
        }
        return 0;
    }

    private static int parseInt(@Nullable String value, int fallback) {
        if (value == null) return fallback;
        try {
            Matcher matcher = Pattern.compile("\\d+").matcher(value);
            if (!matcher.find()) return fallback;
            return Integer.parseInt(matcher.group());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    @NonNull
    private static File getVersionDirectory(@NonNull String versionId) {
        return new File(MinecraftVersionInstaller.getVersionsDirectory(), versionId);
    }

    @NonNull
    private static File getMinecraftHomeDirectory() {
        File versions = MinecraftVersionInstaller.getVersionsDirectory();
        File parent = versions.getParentFile();
        return parent == null ? versions : parent;
    }

    @NonNull
    private static File getLibrariesDirectory() {
        return new File(getMinecraftHomeDirectory(), "libraries");
    }

    @NonNull
    private static String httpGetText(@NonNull String url) throws Exception {
        HttpURLConnection connection = openConnection(url);
        try (InputStream input = connection.getInputStream()) {
            return new String(readAllBytes(input), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static void downloadToFile(
            @NonNull String url,
            @NonNull File output,
            @Nullable Listener listener,
            int progressBase,
            int progressMax
    ) throws Exception {
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create folder: " + parent.getAbsolutePath());
        }

        File temp = new File(output.getParentFile(), output.getName() + ".download");
        HttpURLConnection connection = openConnection(url);
        int length = connection.getContentLength();
        try (InputStream input = connection.getInputStream();
             FileOutputStream fileOutput = new FileOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            long copied = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                fileOutput.write(buffer, 0, read);
                copied += read;
                if (length > 0 && listener != null) {
                    int current = progressBase + (int) Math.min(1, copied / Math.max(1L, length));
                    listener.onProgress(current, progressMax);
                }
            }
        } finally {
            connection.disconnect();
        }

        if (output.exists() && !output.delete()) {
            throw new IOException("Unable to replace " + output.getAbsolutePath());
        }
        if (!temp.renameTo(output)) {
            throw new IOException("Unable to move download into place: " + output.getAbsolutePath());
        }
    }

    @NonNull
    private static HttpURLConnection openConnection(@NonNull String urlValue) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlValue).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(45_000);
        connection.setRequestProperty("User-Agent", "DroidBridge Instance Updater");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + " for " + urlValue);
        }
        return connection;
    }

    @NonNull
    private static String readZipEntryText(@NonNull ZipFile zipFile, @NonNull ZipEntry entry) throws IOException {
        try (InputStream input = zipFile.getInputStream(entry)) {
            return new String(readAllBytes(input), StandardCharsets.UTF_8);
        }
    }

    @NonNull
    private static String readText(@NonNull File file) throws IOException {
        try (InputStream input = new java.io.FileInputStream(file)) {
            return new String(readAllBytes(input), StandardCharsets.UTF_8);
        }
    }

    private static void writeText(@NonNull File file, @NonNull String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create folder: " + parent.getAbsolutePath());
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    @NonNull
    private static byte[] readAllBytes(@NonNull InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy(input, output);
        return output.toByteArray();
    }

    private static void copy(@NonNull InputStream input, @NonNull OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    @NonNull
    private static String urlEncodePath(@NonNull String value) {
        // Loader/Minecraft ids only need path-segment safety here.
        return value.replace(" ", "%20").replace("+", "%2B");
    }

    @NonNull
    private static String sanitizeFilePart(@NonNull String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void notify(@Nullable Listener listener, @NonNull String message) {
        if (listener != null) listener.onStatus(message);
    }

    private static void notifyProgress(@Nullable Listener listener, int current, int total) {
        if (listener != null) listener.onProgress(current, total);
    }
}

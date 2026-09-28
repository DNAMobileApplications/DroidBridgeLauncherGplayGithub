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

package ca.dnamobile.droidbridgelauncher.modmanager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.security.MessageDigest;

public final class CurseForgeInstallManager {
    private static final ModManagerSource SOURCE = ModManagerSource.CURSEFORGE;

    private CurseForgeInstallManager() {
    }

    public static void installLatestCompatible(
            @NonNull CurseForgeApiClient api,
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject project,
            @NonNull ModrinthInstallManager.Listener listener
    ) {
        installLatestCompatible(api, gameDirectory, minecraftVersion, loader, contentType, project, null, listener);
    }

    public static void installLatestCompatible(
            @NonNull CurseForgeApiClient api,
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject project,
            @Nullable File targetDirectoryOverride,
            @NonNull ModrinthInstallManager.Listener listener
    ) {
        try {
            HashSet<String> installingProjects = new HashSet<>();
            HashSet<String> installingVersions = new HashSet<>();
            installProject(api, gameDirectory, minecraftVersion, loader, contentType, project, false, targetDirectoryOverride, installingProjects, installingVersions, listener);
            listener.onComplete("Installed " + project.title + ".");
        } catch (Throwable throwable) {
            listener.onError(throwable);
        }
    }

    public static void installSpecificVersion(
            @NonNull CurseForgeApiClient api,
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject project,
            @NonNull ModrinthVersion version,
            @NonNull ModrinthInstallManager.Listener listener
    ) {
        installSpecificVersion(api, gameDirectory, minecraftVersion, loader, contentType, project, version, null, listener);
    }

    public static void installSpecificVersion(
            @NonNull CurseForgeApiClient api,
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject project,
            @NonNull ModrinthVersion version,
            @Nullable File targetDirectoryOverride,
            @NonNull ModrinthInstallManager.Listener listener
    ) {
        try {
            HashSet<String> installingProjects = new HashSet<>();
            HashSet<String> installingVersions = new HashSet<>();
            installVersion(api, gameDirectory, minecraftVersion, loader, contentType, project, version, false, targetDirectoryOverride, installingProjects, installingVersions, listener);
            listener.onComplete("Installed " + project.title + " " + version.versionNumber + ".");
        } catch (Throwable throwable) {
            listener.onError(throwable);
        }
    }

    private static void installProject(
            @NonNull CurseForgeApiClient api,
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject project,
            boolean dependency,
            @Nullable File targetDirectoryOverride,
            @NonNull HashSet<String> installingProjects,
            @NonNull HashSet<String> installingVersions,
            @NonNull ModrinthInstallManager.Listener listener
    ) throws Exception {
        String projectKey = project.projectId == null ? "" : project.projectId.trim();
        if (projectKey.isEmpty()) projectKey = project.title;
        if (!installingProjects.add(projectKey)) {
            listener.onStatus("CurseForge dependency cycle already being resolved: " + project.title);
            return;
        }

        try {
            if (dependency && isCompatibleProjectAlreadyInstalled(gameDirectory, contentType, project.projectId, minecraftVersion, loader)) {
                listener.onStatus("Compatible CurseForge dependency already installed: " + project.title);
                return;
            }

            listener.onStatus((dependency ? "Installing CurseForge dependency " : "Finding CurseForge version for ") + project.title + "...");
            ArrayList<ModrinthVersion> versions = api.getProjectVersions(project.projectId, contentType, minecraftVersion, loader);
            ModrinthVersion selected = firstCompatibleVersion(versions, minecraftVersion, loader, contentType);
            if (selected == null) {
                throw new IllegalStateException("No compatible CurseForge file found for " + project.title
                        + " (Minecraft " + minecraftVersion + ", " + safeLoader(loader) + ").");
            }

            installVersion(api, gameDirectory, minecraftVersion, loader, contentType, project, selected, dependency, targetDirectoryOverride, installingProjects, installingVersions, listener);
        } finally {
            installingProjects.remove(projectKey);
        }
    }

    private static void installVersion(
            @NonNull CurseForgeApiClient api,
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject project,
            @NonNull ModrinthVersion version,
            boolean dependency,
            @Nullable File targetDirectoryOverride,
            @NonNull HashSet<String> installingProjects,
            @NonNull HashSet<String> installingVersions,
            @NonNull ModrinthInstallManager.Listener listener
    ) throws Exception {
        String versionKey = project.projectId + ":" + version.id;
        if (!installingVersions.add(versionKey)) return;

        try {
        if (dependency && isCompatibleProjectAlreadyInstalled(gameDirectory, contentType, project.projectId, minecraftVersion, loader)) {
            listener.onStatus("Compatible CurseForge dependency already installed: " + project.title);
            return;
        }

        if (contentType.supportsDependencies()) {
            for (ModrinthDependency dep : version.dependencies) {
                if (!dep.isRequired()) continue;
                installDependency(api, gameDirectory, minecraftVersion, loader, contentType, project, dep,
                        targetDirectoryOverride, installingProjects, installingVersions, listener);
            }
        }

        ModrinthFile file = version.getPrimaryFile();
        if (file == null || file.url.trim().isEmpty()) {
            throw new IllegalStateException("No downloadable CurseForge file found for " + project.title + " " + version.versionNumber + ".");
        }

        File targetDirectory = targetDirectoryOverride != null
                ? targetDirectoryOverride
                : contentType.getTargetDirectory(gameDirectory, minecraftVersion);
        if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
            throw new IllegalStateException("Unable to create folder: " + targetDirectory.getAbsolutePath());
        }

        ModManagerManifest.removeKnownFilesForProject(gameDirectory, contentType, SOURCE, project.projectId);

        File target = uniqueTargetFile(targetDirectory, sanitizeFileName(file.filename));
        listener.onStatus("Downloading " + project.title + " " + version.versionNumber + " from CurseForge...");
        try {
            api.downloadToFile(file.url, target);
            verifyDownloadedFile(target, file);
        } catch (Throwable downloadFailure) {
            if (target.exists() && !target.delete()) {
                // A later retry uses a unique target name, so leaving the partial file
                // behind is safe but worth preserving in the original exception path.
            }
            if (downloadFailure instanceof Exception) throw (Exception) downloadFailure;
            throw new IllegalStateException("CurseForge download failed for " + project.title, downloadFailure);
        }

        File cachedIconFile = cacheProjectIcon(api, gameDirectory, project);
        ModManagerManifest.recordInstalled(
                gameDirectory,
                contentType,
                SOURCE,
                project,
                version,
                file,
                target,
                dependency,
                minecraftVersion,
                loader,
                project.iconUrl,
                cachedIconFile
        );
        } finally {
            installingVersions.remove(versionKey);
        }
    }

    private static void installDependency(
            @NonNull CurseForgeApiClient api,
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject parentProject,
            @NonNull ModrinthDependency dep,
            @Nullable File targetDirectoryOverride,
            @NonNull HashSet<String> installingProjects,
            @NonNull HashSet<String> installingVersions,
            @NonNull ModrinthInstallManager.Listener listener
    ) throws Exception {
        if (dep.projectId == null || dep.projectId.trim().isEmpty() || "0".equals(dep.projectId.trim())) {
            throw new IllegalStateException("Required CurseForge dependency is missing a valid project id.");
        }

        String dependencyProjectId = dep.projectId.trim();
        ModrinthProject project = api.getProject(dependencyProjectId);

        if (ModDependencyCompatibility.shouldSkipRequiredDependency(parentProject, project, minecraftVersion)) {
            listener.onStatus(ModDependencyCompatibility.skippedDependencyStatus(project, minecraftVersion));
            return;
        }

        if (isCompatibleProjectAlreadyInstalled(gameDirectory, contentType, dependencyProjectId, minecraftVersion, loader)) {
            listener.onStatus("Compatible CurseForge dependency already installed: " + project.title);
            return;
        }

        installProject(api, gameDirectory, minecraftVersion, loader, contentType, project, true, targetDirectoryOverride, installingProjects, installingVersions, listener);
    }


    private static void verifyDownloadedFile(
            @NonNull File target,
            @NonNull ModrinthFile metadata
    ) throws Exception {
        if (!target.isFile() || target.length() <= 0L) {
            throw new IllegalStateException("CurseForge download produced an empty file: " + target.getName());
        }

        if (metadata.size > 0L && target.length() != metadata.size) {
            throw new IllegalStateException("CurseForge download size mismatch for " + target.getName()
                    + ": expected=" + metadata.size + " actual=" + target.length());
        }

        if (metadata.sha1 == null || metadata.sha1.trim().isEmpty()) return;
        String actual = sha1Hex(target);
        if (!metadata.sha1.equalsIgnoreCase(actual)) {
            throw new SecurityException("CurseForge SHA-1 mismatch for " + target.getName()
                    + ": expected=" + metadata.sha1 + " actual=" + actual);
        }
    }

    @NonNull
    private static String sha1Hex(@NonNull File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder out = new StringBuilder(40);
        for (byte b : digest.digest()) {
            out.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return out.toString();
    }

    @Nullable
    private static ModrinthVersion firstCompatibleVersion(
            @NonNull ArrayList<ModrinthVersion> versions,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType
    ) {
        for (ModrinthVersion candidate : versions) {
            if (isVersionCompatible(candidate, minecraftVersion, loader, contentType)) return candidate;
        }
        return null;
    }

    private static boolean isVersionCompatible(
            @NonNull ModrinthVersion version,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType
    ) {
        boolean hasMinecraftVersionMetadata = false;
        boolean minecraftMatches = false;
        for (String candidate : version.gameVersions) {
            String value = candidate == null ? "" : candidate.trim();
            if (!looksLikeMinecraftVersion(value)) continue;
            hasMinecraftVersionMetadata = true;
            if (minecraftVersion.equals(value)) minecraftMatches = true;
        }
        if (hasMinecraftVersionMetadata && !minecraftMatches) return false;
        if (!contentType.isLoaderSpecific()) return true;

        String wanted = ModrinthApiClient.normalizeLoader(loader);
        if (wanted.isEmpty() || "vanilla".equals(wanted)) return true;

        boolean hasLoaderMetadata = false;
        for (String candidate : version.loaders) {
            String normalized = ModrinthApiClient.normalizeLoader(candidate);
            if (normalized.isEmpty()) continue;
            hasLoaderMetadata = true;
            if (wanted.equals(normalized)) return true;
        }
        // CurseForge also exposes loader labels in gameVersions for some older files.
        for (String candidate : version.gameVersions) {
            String normalized = ModrinthApiClient.normalizeLoader(candidate);
            if (!isKnownLoader(normalized)) continue;
            hasLoaderMetadata = true;
            if (wanted.equals(normalized)) return true;
        }
        // The server-side modLoaderType filter is authoritative when a file omits loader metadata.
        return !hasLoaderMetadata;
    }

    private static boolean isKnownLoader(@Nullable String value) {
        return "forge".equals(value) || "fabric".equals(value) || "quilt".equals(value) || "neoforge".equals(value);
    }

    private static boolean looksLikeMinecraftVersion(@Nullable String value) {
        if (value == null) return false;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return false;
        return trimmed.matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?(?:[-+._A-Za-z0-9]*)?");
    }

    private static boolean isCompatibleProjectAlreadyInstalled(
            @NonNull File gameDirectory,
            @NonNull ModManagerContentType contentType,
            @Nullable String projectId,
            @NonNull String minecraftVersion,
            @Nullable String loader
    ) {
        if (projectId == null || projectId.trim().isEmpty()) return false;
        JSONObject entry = ModManagerManifest.getInstalledEntryForProject(
                gameDirectory, contentType, SOURCE, projectId.trim());
        if (entry == null) return false;

        String installedMinecraft = entry.optString("minecraftVersion", "").trim();
        String installedLoader = ModrinthApiClient.normalizeLoader(entry.optString("loader", ""));
        String wantedMinecraft = minecraftVersion.trim();
        String wantedLoader = ModrinthApiClient.normalizeLoader(loader);

        if (installedMinecraft.isEmpty() || !installedMinecraft.equals(wantedMinecraft)) return false;
        if (contentType.isLoaderSpecific() && !wantedLoader.isEmpty() && !"vanilla".equals(wantedLoader)) {
            if (installedLoader.isEmpty() || !wantedLoader.equals(installedLoader)) return false;
        }
        return true;
    }

    @Nullable
    private static File cacheProjectIcon(
            @NonNull CurseForgeApiClient api,
            @NonNull File gameDirectory,
            @NonNull ModrinthProject project
    ) {
        if (project.iconUrl == null || project.iconUrl.trim().isEmpty() || project.projectId.trim().isEmpty()) return null;

        try {
            File iconDirectory = DroidBridgeMetadataPaths.childDirectoryForWrite(gameDirectory, DroidBridgeMetadataPaths.MOD_MANAGER_ICONS_DIRECTORY);
            if (!iconDirectory.exists() && !iconDirectory.mkdirs()) return null;

            File iconFile = new File(iconDirectory, "curseforge-" + sanitizeFileName(project.projectId) + ".img");
            api.downloadToFile(project.iconUrl, iconFile);
            return iconFile.isFile() ? iconFile : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @NonNull
    private static File uniqueTargetFile(@NonNull File directory, @NonNull String fileName) {
        File target = new File(directory, fileName);
        if (!target.exists()) return target;

        String base = fileName;
        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }

        for (int i = 2; i < 1000; i++) {
            File candidate = new File(directory, base + "-" + i + extension);
            if (!candidate.exists()) return candidate;
        }
        return new File(directory, base + "-" + System.currentTimeMillis() + extension);
    }

    @NonNull
    private static String sanitizeFileName(@NonNull String rawName) {
        String name = rawName.trim().replace('\n', ' ').replace('\r', ' ');
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.isEmpty()) name = "download.jar";
        return name;
    }

    @NonNull
    private static String safeLoader(@Nullable String loader) {
        return loader == null || loader.trim().isEmpty() ? "unknown loader" : loader.trim().toLowerCase(Locale.US);
    }
}

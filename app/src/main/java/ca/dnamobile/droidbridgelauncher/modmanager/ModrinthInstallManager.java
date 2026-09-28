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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

public final class ModrinthInstallManager {
    private static final ModManagerSource SOURCE = ModManagerSource.MODRINTH;

    public interface Listener {
        void onStatus(@NonNull String message);
        void onComplete(@NonNull String message);
        void onError(@NonNull Throwable throwable);
    }

    private ModrinthInstallManager() {
    }

    public static void installLatestCompatible(
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject project,
            @NonNull Listener listener
    ) {
        installLatestCompatible(gameDirectory, minecraftVersion, loader, contentType, project, null, listener);
    }

    public static void installLatestCompatible(
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject project,
            @Nullable File targetDirectoryOverride,
            @NonNull Listener listener
    ) {
        try {
            ModrinthApiClient api = new ModrinthApiClient();
            HashSet<String> installingProjects = new HashSet<>();
            HashSet<String> installingVersions = new HashSet<>();
            installProject(api, gameDirectory, minecraftVersion, loader, contentType, project, false, targetDirectoryOverride, installingProjects, installingVersions, listener);
            listener.onComplete("Installed " + project.title + ".");
        } catch (Throwable throwable) {
            listener.onError(throwable);
        }
    }

    public static void installSpecificVersion(
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject project,
            @NonNull ModrinthVersion version,
            @NonNull Listener listener
    ) {
        installSpecificVersion(gameDirectory, minecraftVersion, loader, contentType, project, version, null, listener);
    }

    public static void installSpecificVersion(
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject project,
            @NonNull ModrinthVersion version,
            @Nullable File targetDirectoryOverride,
            @NonNull Listener listener
    ) {
        try {
            ModrinthApiClient api = new ModrinthApiClient();
            HashSet<String> installingProjects = new HashSet<>();
            HashSet<String> installingVersions = new HashSet<>();
            installVersion(api, gameDirectory, minecraftVersion, loader, contentType, project, version, false, targetDirectoryOverride, installingProjects, installingVersions, listener);
            listener.onComplete("Installed " + project.title + " " + version.versionNumber + ".");
        } catch (Throwable throwable) {
            listener.onError(throwable);
        }
    }

    private static void installProject(
            @NonNull ModrinthApiClient api,
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject project,
            boolean dependency,
            @Nullable File targetDirectoryOverride,
            @NonNull HashSet<String> installingProjects,
            @NonNull HashSet<String> installingVersions,
            @NonNull Listener listener
    ) throws Exception {
        String projectKey = project.projectId == null ? "" : project.projectId.trim();
        if (projectKey.isEmpty()) projectKey = project.slug == null ? project.title : project.slug.trim();
        if (!installingProjects.add(projectKey)) {
            listener.onStatus("Dependency cycle already being resolved: " + project.title);
            return;
        }

        try {
            if (dependency && isCompatibleProjectAlreadyInstalled(gameDirectory, contentType, project.projectId, minecraftVersion, loader)) {
                listener.onStatus("Compatible dependency already installed: " + project.title);
                return;
            }

            listener.onStatus((dependency ? "Installing dependency " : "Finding version for ") + project.title + "...");
            ArrayList<ModrinthVersion> versions = api.getProjectVersionsWithFallback(
                    project,
                    contentType,
                    minecraftVersion,
                    loader,
                    false
            );

            ModrinthVersion selected = firstCompatibleVersion(versions, minecraftVersion, loader, contentType);
            if (selected == null) {
                throw new IllegalStateException("No compatible Modrinth version found for " + project.title
                        + " (Minecraft " + minecraftVersion + ", " + safeLoader(loader) + ").");
            }

            installVersion(api, gameDirectory, minecraftVersion, loader, contentType, project, selected, dependency, targetDirectoryOverride, installingProjects, installingVersions, listener);
        } finally {
            installingProjects.remove(projectKey);
        }
    }

    private static void installVersion(
            @NonNull ModrinthApiClient api,
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
            @NonNull Listener listener
    ) throws Exception {
        if (!installingVersions.add(version.id)) return;

        try {
            if (dependency && isCompatibleProjectAlreadyInstalled(gameDirectory, contentType, project.projectId, minecraftVersion, loader)) {
                listener.onStatus("Compatible dependency already installed: " + project.title);
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
                throw new IllegalStateException("No downloadable file found for " + project.title + " " + version.versionNumber + ".");
            }

            File targetDirectory = targetDirectoryOverride != null
                    ? targetDirectoryOverride
                    : contentType.getTargetDirectory(gameDirectory, minecraftVersion);
            if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
                throw new IllegalStateException("Unable to create folder: " + targetDirectory.getAbsolutePath());
            }

            ModManagerManifest.removeKnownFilesForProject(gameDirectory, contentType, SOURCE, project.projectId);

            File target = uniqueTargetFile(targetDirectory, sanitizeFileName(file.filename));
            listener.onStatus("Downloading " + project.title + " " + version.versionNumber + "...");
            api.downloadToFile(file.url, target);

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
            installingVersions.remove(version.id);
        }
    }

    private static void installDependency(
            @NonNull ModrinthApiClient api,
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthProject parentProject,
            @NonNull ModrinthDependency dep,
            @Nullable File targetDirectoryOverride,
            @NonNull HashSet<String> installingProjects,
            @NonNull HashSet<String> installingVersions,
            @NonNull Listener listener
    ) throws Exception {
        Throwable exactFailure = null;

        // Prefer the publisher-selected exact dependency version when it is still
        // present and compatible.  If Modrinth has removed/staled that version,
        // fall back to the dependency project and resolve the newest compatible file.
        if (dep.versionId != null && !dep.versionId.trim().isEmpty()) {
            try {
                ModrinthVersion version = api.getVersion(dep.versionId.trim());
                ModrinthProject project = api.getProject(version.projectId);
                if (ModDependencyCompatibility.shouldSkipRequiredDependency(parentProject, project, minecraftVersion)) {
                    listener.onStatus(ModDependencyCompatibility.skippedDependencyStatus(project, minecraftVersion));
                    return;
                }
                if (!isVersionCompatible(version, minecraftVersion, loader, contentType)) {
                    throw new IllegalStateException("Exact dependency version is not compatible with Minecraft "
                            + minecraftVersion + " / " + safeLoader(loader));
                }
                if (isCompatibleProjectAlreadyInstalled(gameDirectory, contentType, project.projectId, minecraftVersion, loader)) {
                    listener.onStatus("Compatible dependency already installed: " + project.title);
                    return;
                }
                installVersion(api, gameDirectory, minecraftVersion, loader, contentType, project, version, true,
                        targetDirectoryOverride, installingProjects, installingVersions, listener);
                return;
            } catch (Throwable throwable) {
                exactFailure = throwable;
                listener.onStatus("Exact dependency version unavailable; trying a compatible project release...");
            }
        }

        if (dep.projectId != null && !dep.projectId.trim().isEmpty()) {
            String dependencyProjectId = dep.projectId.trim();

            try {
                ModrinthProject project = api.getProject(dependencyProjectId);
                if (ModDependencyCompatibility.shouldSkipRequiredDependency(parentProject, project, minecraftVersion)) {
                    listener.onStatus(ModDependencyCompatibility.skippedDependencyStatus(project, minecraftVersion));
                    return;
                }
                if (isCompatibleProjectAlreadyInstalled(gameDirectory, contentType, dependencyProjectId, minecraftVersion, loader)) {
                    listener.onStatus("Compatible dependency already installed: " + project.title);
                    return;
                }
                installProject(api, gameDirectory, minecraftVersion, loader, contentType, project, true,
                        targetDirectoryOverride, installingProjects, installingVersions, listener);
                return;
            } catch (Throwable projectFailure) {
                if (exactFailure != null) projectFailure.addSuppressed(exactFailure);
                if (projectFailure instanceof Exception) throw (Exception) projectFailure;
                throw new IllegalStateException("Unable to resolve required Modrinth dependency " + dependencyProjectId, projectFailure);
            }
        }

        if (dep.fileName != null && !dep.fileName.trim().isEmpty()) {
            File targetDirectory = targetDirectoryOverride != null
                    ? targetDirectoryOverride
                    : contentType.getTargetDirectory(gameDirectory, minecraftVersion);
            File local = new File(targetDirectory, sanitizeFileName(dep.fileName));
            if (local.isFile() && local.length() > 0L) {
                listener.onStatus("Filename-only dependency already present: " + dep.fileName);
                return;
            }
            throw new IllegalStateException("Required Modrinth dependency only supplied file_name='"
                    + dep.fileName + "' with no project_id/version_id, and that file is not installed.");
        }

        if (exactFailure != null) {
            if (exactFailure instanceof Exception) throw (Exception) exactFailure;
            throw new IllegalStateException("Unable to resolve required Modrinth dependency", exactFailure);
        }
        throw new IllegalStateException("Required Modrinth dependency has no project_id, version_id, or file_name.");
    }

    private static boolean isVersionCompatible(
            @NonNull ModrinthVersion version,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType
    ) {
        if (!version.gameVersions.isEmpty() && !version.gameVersions.contains(minecraftVersion)) return false;
        if (!contentType.isLoaderSpecific()) return true;
        String wanted = ModrinthApiClient.normalizeLoader(loader);
        if (wanted.isEmpty() || "vanilla".equals(wanted) || version.loaders.isEmpty()) return true;
        for (String candidate : version.loaders) {
            if (wanted.equals(ModrinthApiClient.normalizeLoader(candidate))) return true;
        }
        return false;
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

        // Old/partial manifest entries are not enough proof for a required dependency.
        if (installedMinecraft.isEmpty() || !installedMinecraft.equals(wantedMinecraft)) return false;
        if (contentType.isLoaderSpecific() && !wantedLoader.isEmpty() && !"vanilla".equals(wantedLoader)) {
            if (installedLoader.isEmpty() || !wantedLoader.equals(installedLoader)) return false;
        }
        return true;
    }

    @Nullable
    private static File cacheProjectIcon(
            @NonNull ModrinthApiClient api,
            @NonNull File gameDirectory,
            @NonNull ModrinthProject project
    ) {
        if (project.iconUrl == null || project.iconUrl.trim().isEmpty() || project.projectId.trim().isEmpty()) {
            return null;
        }

        try {
            File iconDirectory = DroidBridgeMetadataPaths.childDirectoryForWrite(gameDirectory, DroidBridgeMetadataPaths.MOD_MANAGER_ICONS_DIRECTORY);
            if (!iconDirectory.exists() && !iconDirectory.mkdirs()) return null;

            File iconFile = new File(iconDirectory, sanitizeFileName(project.projectId) + ".img");
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

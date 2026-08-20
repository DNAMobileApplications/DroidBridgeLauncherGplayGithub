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
        try {
            ModrinthApiClient api = new ModrinthApiClient();
            HashSet<String> installingProjects = new HashSet<>();
            HashSet<String> installingVersions = new HashSet<>();
            installProject(api, gameDirectory, minecraftVersion, loader, contentType, project, false, installingProjects, installingVersions, listener);
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
        try {
            ModrinthApiClient api = new ModrinthApiClient();
            HashSet<String> installingProjects = new HashSet<>();
            HashSet<String> installingVersions = new HashSet<>();
            installVersion(api, gameDirectory, minecraftVersion, loader, contentType, project, version, false, installingProjects, installingVersions, listener);
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
            @NonNull HashSet<String> installingProjects,
            @NonNull HashSet<String> installingVersions,
            @NonNull Listener listener
    ) throws Exception {
        if (!installingProjects.add(project.projectId)) return;

        if (dependency && isProjectAlreadyInstalled(gameDirectory, contentType, project.projectId)) {
            listener.onStatus("Dependency already installed: " + project.title);
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

        if (versions.isEmpty()) {
            throw new IllegalStateException("No compatible Modrinth version found for " + project.title
                    + " (Minecraft " + minecraftVersion + ", " + safeLoader(loader) + ").");
        }

        installVersion(api, gameDirectory, minecraftVersion, loader, contentType, project, versions.get(0), dependency, installingProjects, installingVersions, listener);
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
            @NonNull HashSet<String> installingProjects,
            @NonNull HashSet<String> installingVersions,
            @NonNull Listener listener
    ) throws Exception {
        if (!installingVersions.add(version.id)) return;

        if (dependency && isProjectAlreadyInstalled(gameDirectory, contentType, project.projectId)) {
            listener.onStatus("Dependency already installed: " + project.title);
            return;
        }

        if (contentType.supportsDependencies()) {
            for (ModrinthDependency dep : version.dependencies) {
                if (!dep.isRequired()) continue;
                installDependency(api, gameDirectory, minecraftVersion, loader, contentType, dep, installingProjects, installingVersions, listener);
            }
        }

        ModrinthFile file = version.getPrimaryFile();
        if (file == null || file.url.trim().isEmpty()) {
            throw new IllegalStateException("No downloadable file found for " + project.title + " " + version.versionNumber + ".");
        }

        File targetDirectory = contentType.getTargetDirectory(gameDirectory);
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
    }

    private static void installDependency(
            @NonNull ModrinthApiClient api,
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull ModManagerContentType contentType,
            @NonNull ModrinthDependency dep,
            @NonNull HashSet<String> installingProjects,
            @NonNull HashSet<String> installingVersions,
            @NonNull Listener listener
    ) throws Exception {
        // Prefer dependency project ids over exact dependency version ids.
        // Some Modrinth projects publish dependency records with stale/removed version_id
        // values, which can throw HTTP 404 for otherwise valid dependencies.
        // Modly solved this by resolving required dependencies by project_id and then
        // selecting the latest compatible version for the active Minecraft/loader pair.
        if (dep.projectId != null && !dep.projectId.trim().isEmpty()) {
            String dependencyProjectId = dep.projectId.trim();
            if (isProjectAlreadyInstalled(gameDirectory, contentType, dependencyProjectId)) {
                listener.onStatus("Dependency already installed: " + dependencyProjectId);
                return;
            }

            ModrinthProject project = api.getProject(dependencyProjectId);
            installProject(api, gameDirectory, minecraftVersion, loader, contentType, project, true, installingProjects, installingVersions, listener);
            return;
        }

        if (dep.versionId != null && !dep.versionId.trim().isEmpty()) {
            ModrinthVersion version = api.getVersion(dep.versionId.trim());
            ModrinthProject project = api.getProject(version.projectId);
            installVersion(api, gameDirectory, minecraftVersion, loader, contentType, project, version, true, installingProjects, installingVersions, listener);
        }
    }


    private static boolean isProjectAlreadyInstalled(
            @NonNull File gameDirectory,
            @NonNull ModManagerContentType contentType,
            @Nullable String projectId
    ) {
        return projectId != null
                && !projectId.trim().isEmpty()
                && ModManagerManifest.isProjectInstalled(gameDirectory, contentType, SOURCE.getId(), projectId.trim());
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

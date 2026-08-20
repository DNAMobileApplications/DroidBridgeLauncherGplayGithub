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

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

public final class ModpackUpdateManager {
    private static final String TAG = "ModpackUpdateManager";
    private static final String METADATA_DIRECTORY = DroidBridgeMetadataPaths.PRIMARY_DIRECTORY;
    private static final String MODPACK_METADATA_FILE = "modpack_manifest.json";
    private static final String MODPACK_FILES_METADATA_FILE = "modpack_files_manifest.json";

    private ModpackUpdateManager() {
    }

    public interface Listener {
        void onStatus(@NonNull String message);
        void onProgress(int current, int total);
    }

    public enum Platform {
        MODRINTH("Modrinth"),
        CURSEFORGE("CurseForge");

        @NonNull
        public final String displayName;

        Platform(@NonNull String displayName) {
            this.displayName = displayName;
        }
    }

    public static final class InstalledModpackInfo {
        @NonNull
        public final Platform platform;
        @NonNull
        public final String projectId;
        @NonNull
        public final String currentVersionId;
        @NonNull
        public final String currentFileId;
        @NonNull
        public final String currentVersionNumber;
        @NonNull
        public final String currentVersionLabel;
        @NonNull
        public final String displayTitle;
        @NonNull
        public final File metadataFile;

        private InstalledModpackInfo(
                @NonNull Platform platform,
                @NonNull String projectId,
                @NonNull String currentVersionId,
                @NonNull String currentFileId,
                @NonNull String currentVersionNumber,
                @NonNull String displayTitle,
                @NonNull File metadataFile
        ) {
            this.platform = platform;
            this.projectId = projectId;
            this.currentVersionId = currentVersionId;
            this.currentFileId = currentFileId;
            this.currentVersionNumber = currentVersionNumber;
            this.currentVersionLabel = firstNonBlank(currentVersionNumber, currentVersionId, currentFileId, "Unknown");
            this.displayTitle = firstNonBlank(displayTitle, projectId, "Modpack");
            this.metadataFile = metadataFile;
        }
    }

    public static final class ProjectMatch {
        @NonNull
        public final Platform platform;
        @NonNull
        public final String projectId;
        @NonNull
        public final String title;
        @NonNull
        public final String slug;
        @NonNull
        public final String summary;
        public final boolean exactInstalledProject;

        private ProjectMatch(
                @NonNull Platform platform,
                @NonNull String projectId,
                @NonNull String title,
                @NonNull String slug,
                @NonNull String summary,
                boolean exactInstalledProject
        ) {
            this.platform = platform;
            this.projectId = projectId;
            this.title = firstNonBlank(title, slug, projectId, "Modpack");
            this.slug = slug;
            this.summary = summary;
            this.exactInstalledProject = exactInstalledProject;
        }

        @NonNull
        public String getDisplayLabel() {
            return platform.displayName + " • " + title;
        }
    }

    @NonNull
    public static ProjectMatch createProjectMatch(
            @NonNull Platform platform,
            @NonNull String projectId,
            @Nullable String title,
            @Nullable String slug,
            @Nullable String summary,
            boolean exactInstalledProject
    ) {
        return new ProjectMatch(
                platform,
                projectId == null ? "" : projectId.trim(),
                title == null ? "" : title.trim(),
                slug == null ? "" : slug.trim(),
                summary == null ? "" : summary.trim(),
                exactInstalledProject
        );
    }

    public static final class VersionInfo {
        @NonNull
        public final Platform platform;
        @NonNull
        public final String projectId;
        @NonNull
        public final String versionId;
        @NonNull
        public final String fileId;
        @NonNull
        public final String versionNumber;
        @NonNull
        public final String versionName;
        @NonNull
        public final String versionLabel;
        @NonNull
        public final String datePublished;
        @NonNull
        public final String downloadUrl;
        @NonNull
        public final String fileName;
        @NonNull
        public final ArrayList<String> gameVersions;
        @NonNull
        public final ArrayList<String> loaders;
        @NonNull
        public final String primaryMinecraftVersion;
        @NonNull
        public final String primaryLoader;

        private VersionInfo(
                @NonNull Platform platform,
                @NonNull String projectId,
                @NonNull String versionId,
                @NonNull String fileId,
                @NonNull String versionNumber,
                @NonNull String versionName,
                @NonNull String datePublished,
                @NonNull String downloadUrl,
                @NonNull String fileName
        ) {
            this(platform, projectId, versionId, fileId, versionNumber, versionName, datePublished, downloadUrl, fileName, new ArrayList<>(), new ArrayList<>());
        }

        private VersionInfo(
                @NonNull Platform platform,
                @NonNull String projectId,
                @NonNull String versionId,
                @NonNull String fileId,
                @NonNull String versionNumber,
                @NonNull String versionName,
                @NonNull String datePublished,
                @NonNull String downloadUrl,
                @NonNull String fileName,
                @Nullable ArrayList<String> gameVersions,
                @Nullable ArrayList<String> loaders
        ) {
            this.platform = platform;
            this.projectId = projectId;
            this.versionId = versionId;
            this.fileId = fileId;
            this.versionNumber = versionNumber;
            this.versionName = versionName;
            this.versionLabel = firstNonBlank(versionNumber, versionName, versionId, fileId, "Unknown");
            this.datePublished = datePublished;
            this.downloadUrl = downloadUrl;
            this.fileName = fileName;
            this.gameVersions = gameVersions == null ? new ArrayList<>() : gameVersions;
            this.loaders = loaders == null ? new ArrayList<>() : loaders;
            this.primaryMinecraftVersion = this.gameVersions.isEmpty() ? "" : this.gameVersions.get(0);
            this.primaryLoader = this.loaders.isEmpty() ? "" : this.loaders.get(0);
        }

        @NonNull
        public String getMinecraftVersionsLabel() {
            return gameVersions.isEmpty() ? "Unknown MC Version" : joinStrings(gameVersions, ", " );
        }

        @NonNull
        public String getLoadersLabel() {
            return loaders.isEmpty() ? "Unknown loader" : joinStrings(loaders, ", " );
        }
    }

    public static final class UpdateCheckResult {
        @NonNull
        public final InstalledModpackInfo installed;
        @NonNull
        public final VersionInfo latest;
        public final boolean updateAvailable;

        private UpdateCheckResult(@NonNull InstalledModpackInfo installed, @NonNull VersionInfo latest, boolean updateAvailable) {
            this.installed = installed;
            this.latest = latest;
            this.updateAvailable = updateAvailable;
        }
    }

    public static final class UpdateResult {
        @NonNull
        public final String versionLabel;
        public final int installedFiles;
        public final int removedOldFiles;
        @NonNull
        public final String minecraftVersion;
        @NonNull
        public final String loader;

        private UpdateResult(@NonNull String versionLabel, int installedFiles, int removedOldFiles) {
            this(versionLabel, installedFiles, removedOldFiles, "", "");
        }

        private UpdateResult(
                @NonNull String versionLabel,
                int installedFiles,
                int removedOldFiles,
                @Nullable String minecraftVersion,
                @Nullable String loader
        ) {
            this.versionLabel = versionLabel;
            this.installedFiles = installedFiles;
            this.removedOldFiles = removedOldFiles;
            this.minecraftVersion = minecraftVersion == null ? "" : minecraftVersion;
            this.loader = loader == null ? "" : loader;
        }
    }

    private static final class ManagedFileEntry {
        @NonNull
        final String relativePath;
        @NonNull
        final ModManagerContentType contentType;
        @NonNull
        final Platform platform;
        @NonNull
        final String projectId;
        @NonNull
        final String versionId;
        @NonNull
        final String fileId;
        @NonNull
        final String versionNumber;
        @NonNull
        final String fileName;
        @NonNull
        final File file;

        ManagedFileEntry(
                @NonNull String relativePath,
                @NonNull ModManagerContentType contentType,
                @NonNull Platform platform,
                @NonNull String projectId,
                @NonNull String versionId,
                @NonNull String fileId,
                @NonNull String versionNumber,
                @NonNull String fileName,
                @NonNull File file
        ) {
            this.relativePath = normalizeRelativePath(relativePath);
            this.contentType = contentType;
            this.platform = platform;
            this.projectId = projectId;
            this.versionId = versionId;
            this.fileId = fileId;
            this.versionNumber = versionNumber;
            this.fileName = fileName;
            this.file = file;
        }
    }

    @Nullable
    public static InstalledModpackInfo readInstalledModpackInfo(@Nullable File rootDirectory, @Nullable File gameDirectory) {
        try {
            for (File file : getMetadataCandidates(rootDirectory, gameDirectory)) {
                if (!file.isFile()) continue;
                InstalledModpackInfo info = readInstalledModpackInfoFromFile(file);
                if (info != null) return info;
            }
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to read installed modpack metadata: " + readableError(throwable));
        }
        return null;
    }

    @NonNull
    public static ArrayList<ProjectMatch> findMatchingProjects(
            @NonNull Context context,
            @Nullable File rootDirectory,
            @NonNull File gameDirectory,
            @NonNull Listener listener
    ) throws Exception {
        InstalledModpackInfo installed = readInstalledModpackInfo(rootDirectory, gameDirectory);
        if (installed == null) {
            throw new IllegalStateException("This instance does not have modpack metadata. Install or import the pack from Modrinth/CurseForge first.");
        }
        return findMatchingProjects(context, installed, listener);
    }

    @NonNull
    public static ArrayList<ProjectMatch> findMatchingProjects(
            @NonNull Context context,
            @NonNull InstalledModpackInfo installed,
            @NonNull Listener listener
    ) throws Exception {
        listener.onStatus("Finding matching modpack projects...");
        listener.onProgress(0, 2);

        LinkedHashMap<String, ProjectMatch> matches = new LinkedHashMap<>();

        if (!isBlank(installed.projectId)) {
            if (installed.platform == Platform.MODRINTH) {
                addProject(matches, fetchModrinthProject(installed.projectId, true));
            } else {
                addProject(matches, fetchCurseForgeProject(installed.projectId, true));
            }
        }

        // Modrinth .mrpack files normally store the version id but not always the project id.
        // Resolve the version first so installed packs like Optimobile can still be matched
        // exactly before falling back to title/slug search.
        if (installed.platform == Platform.MODRINTH
                && isBlank(installed.projectId)
                && !isBlank(installed.currentVersionId)) {
            addProject(matches, fetchModrinthProjectFromVersion(installed.currentVersionId, true));
        }

        ArrayList<String> searchQueries = buildProjectSearchQueries(installed);
        int totalQueries = Math.max(1, searchQueries.size());
        int completedQueries = 0;
        for (String query : searchQueries) {
            listener.onStatus("Searching Modrinth for " + query + "...");
            for (ProjectMatch match : searchModrinthProjects(query)) addProject(matches, match);

            listener.onStatus("Searching CurseForge for " + query + "...");
            for (ProjectMatch match : searchCurseForgeProjects(query)) addProject(matches, match);

            completedQueries++;
            listener.onProgress(completedQueries, totalQueries);
        }

        ArrayList<ProjectMatch> result = new ArrayList<>(matches.values());
        Collections.sort(result, (left, right) -> {
            if (left.exactInstalledProject != right.exactInstalledProject) return left.exactInstalledProject ? -1 : 1;
            int titleCompare = normalizeTitle(left.title).compareTo(normalizeTitle(right.title));
            if (titleCompare != 0) return titleCompare;
            return left.platform.displayName.compareTo(right.platform.displayName);
        });
        return result;
    }

    @NonNull
    public static ArrayList<VersionInfo> loadVersions(
            @NonNull ProjectMatch project,
            @NonNull Listener listener
    ) throws Exception {
        listener.onStatus("Loading " + project.platform.displayName + " versions...");
        listener.onProgress(0, 1);
        ArrayList<VersionInfo> versions = project.platform == Platform.MODRINTH
                ? loadModrinthVersions(project.projectId)
                : loadCurseForgeVersions(project.projectId);
        versions.sort((left, right) -> {
            int dateCompare = right.datePublished.compareTo(left.datePublished);
            if (dateCompare != 0) return dateCompare;
            return compareVersionStrings(right.versionLabel, left.versionLabel);
        });
        listener.onProgress(1, 1);
        return versions;
    }

    @NonNull
    public static UpdateResult updateInstalledModpack(
            @NonNull Context context,
            @Nullable File rootDirectory,
            @NonNull File gameDirectory,
            @NonNull String currentMinecraftVersion,
            @Nullable String currentLoader,
            @NonNull InstalledModpackInfo installed,
            @NonNull ProjectMatch project,
            @NonNull VersionInfo selectedVersion,
            @NonNull Listener listener
    ) throws Exception {
        InstalledModpackInfo resolvedInstalled = new InstalledModpackInfo(
                project.platform,
                project.projectId,
                installed.currentVersionId,
                installed.currentFileId,
                installed.currentVersionNumber,
                project.title,
                installed.metadataFile
        );
        VersionInfo selected = selectedVersion;
        if (isBlank(selected.downloadUrl) && selected.platform == Platform.CURSEFORGE) {
            selected = latestWithCurseForgeDownloadUrl(selected);
        }
        if (isBlank(selected.downloadUrl)) {
            throw new IllegalStateException("The selected modpack version does not provide a download URL.");
        }

        File cacheDir = new File(context.getCacheDir(), "modpack-update");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IOException("Unable to create cache folder: " + cacheDir.getAbsolutePath());
        }

        String suffix = selected.platform == Platform.MODRINTH ? ".mrpack" : ".zip";
        File packFile = File.createTempFile("modpack-update-", suffix, cacheDir);
        try {
            listener.onStatus("Downloading " + selected.versionLabel + "...");
            listener.onProgress(0, 100);
            downloadToFile(selected.downloadUrl, packFile, selected.platform == Platform.CURSEFORGE ? CurseForgeApiKeyProvider.resolve() : null);

            if (selected.platform == Platform.MODRINTH) {
                return installModrinthPack(gameDirectory, resolvedInstalled, selected, packFile, currentMinecraftVersion, currentLoader, listener);
            }
            return installCurseForgePack(gameDirectory, resolvedInstalled, selected, packFile, currentMinecraftVersion, currentLoader, listener);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            packFile.delete();
        }
    }

    @NonNull
    public static UpdateCheckResult checkForUpdate(
            @NonNull Context context,
            @Nullable File rootDirectory,
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull Listener listener
    ) throws Exception {
        listener.onStatus("Reading installed modpack metadata...");
        listener.onProgress(0, 1);

        InstalledModpackInfo installed = readInstalledModpackInfo(rootDirectory, gameDirectory);
        if (installed == null) {
            throw new IllegalStateException("This instance does not have modpack update metadata. Reinstall the pack from Modrinth/CurseForge or import a pack that includes DroidBridge modpack metadata.");
        }

        InstalledModpackInfo resolvedInstalled = installed;
        if (isBlank(resolvedInstalled.projectId)) {
            resolvedInstalled = resolveProjectFromTitle(context, installed, minecraftVersion, loader, listener);
        }

        if (isBlank(resolvedInstalled.projectId)) {
            throw new IllegalStateException("Unable to determine the installed modpack project id for " + installed.displayTitle + ".");
        }

        listener.onStatus("Checking latest " + resolvedInstalled.platform.displayName + " version...");
        VersionInfo latest;
        if (resolvedInstalled.platform == Platform.MODRINTH) {
            latest = fetchLatestModrinthVersion(resolvedInstalled.projectId, minecraftVersion, loader);
        } else {
            latest = fetchLatestCurseForgeVersion(resolvedInstalled.projectId, minecraftVersion, loader);
        }

        boolean updateAvailable = isUpdateAvailable(resolvedInstalled, latest);
        listener.onProgress(1, 1);
        return new UpdateCheckResult(resolvedInstalled, latest, updateAvailable);
    }

    @NonNull
    public static UpdateResult updateInstalledModpack(
            @NonNull Context context,
            @Nullable File rootDirectory,
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull UpdateCheckResult checkResult,
            @NonNull Listener listener
    ) throws Exception {
        VersionInfo latest = checkResult.latest;
        if (isBlank(latest.downloadUrl) && latest.platform == Platform.CURSEFORGE) {
            latest = latestWithCurseForgeDownloadUrl(latest);
        }
        if (isBlank(latest.downloadUrl)) {
            throw new IllegalStateException("The selected modpack version does not provide a download URL.");
        }

        File cacheDir = new File(context.getCacheDir(), "modpack-update");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IOException("Unable to create cache folder: " + cacheDir.getAbsolutePath());
        }

        String suffix = latest.platform == Platform.MODRINTH ? ".mrpack" : ".zip";
        File packFile = File.createTempFile("modpack-update-", suffix, cacheDir);
        try {
            listener.onStatus("Downloading " + latest.versionLabel + "...");
            listener.onProgress(0, 100);
            downloadToFile(latest.downloadUrl, packFile, latest.platform == Platform.CURSEFORGE ? CurseForgeApiKeyProvider.resolve() : null);

            if (latest.platform == Platform.MODRINTH) {
                return installModrinthPack(gameDirectory, checkResult.installed, latest, packFile, minecraftVersion, loader, listener);
            }
            return installCurseForgePack(gameDirectory, checkResult.installed, latest, packFile, minecraftVersion, loader, listener);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            packFile.delete();
        }
    }

    @Nullable
    private static InstalledModpackInfo readInstalledModpackInfoFromFile(@NonNull File file) throws Exception {
        String fileName = file.getName().toLowerCase(Locale.US);
        JSONObject root = new JSONObject(readTextFile(file));

        if ("modrinth.index.json".equals(fileName)) {
            return new InstalledModpackInfo(
                    Platform.MODRINTH,
                    readString(root, "projectId", "project_id", "modrinthProjectId"),
                    readString(root, "versionId", "version_id", "id"),
                    "",
                    readString(root, "versionNumber", "version_number"),
                    readString(root, "name", "title"),
                    file
            );
        }

        Platform platform = readPlatform(root);
        if (platform == null && fileName.equals("manifest.json") && "minecraftModpack".equalsIgnoreCase(root.optString("manifestType", ""))) {
            platform = Platform.CURSEFORGE;
        }
        if (platform == null) return null;

        String projectId = readString(root,
                "platformProjectId",
                "projectId",
                "modrinthProjectId",
                "curseForgeProjectId",
                "curseforgeProjectId",
                "curseForgeProjectID",
                "projectID"
        );
        String versionId = readString(root, "versionId", "versionID", "modrinthVersionId", "modrinth_version_id", "id");
        String fileId = readString(root, "fileId", "fileID", "curseForgeFileId", "curseforgeFileId", "curseForgeFileID");
        String versionNumber = readString(root, "versionNumber", "version", "version_number", "displayName");
        String title = readString(root, "title", "name", "projectTitle", "displayName", "modpackName");

        JSONObject project = firstObject(root, "project", "modrinthProject", "curseForgeProject", "curseforgeProject", "data");
        if (project != null) {
            if (isBlank(projectId)) {
                projectId = readString(project, "id", "projectId", "modId", "platformProjectId");
            }
            if (isBlank(title)) {
                title = readString(project, "title", "name", "slug");
            }
        }

        return new InstalledModpackInfo(platform, projectId, versionId, fileId, versionNumber, title, file);
    }

    @NonNull
    private static ArrayList<File> getMetadataCandidates(@Nullable File rootDirectory, @Nullable File gameDirectory) {
        ArrayList<File> files = new ArrayList<>();
        addMetadataCandidates(files, gameDirectory);
        addMetadataCandidates(files, rootDirectory);
        if (rootDirectory != null && gameDirectory != null && !sameFile(rootDirectory, gameDirectory)) {
            addMetadataCandidates(files, new File(rootDirectory, "game"));
            addMetadataCandidates(files, new File(rootDirectory, ".minecraft"));
        }
        return files;
    }

    private static void addMetadataCandidates(@NonNull ArrayList<File> files, @Nullable File folder) {
        if (folder == null) return;
        files.add(new File(new File(folder, METADATA_DIRECTORY), MODPACK_METADATA_FILE));
        files.add(new File(new File(folder, METADATA_DIRECTORY), MODPACK_FILES_METADATA_FILE));
        files.add(new File(folder, "modrinth.index.json"));
        files.add(new File(folder, "manifest.json"));
    }

    private static boolean sameFile(@NonNull File a, @NonNull File b) {
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (Throwable ignored) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }

    private static void addProject(@NonNull Map<String, ProjectMatch> matches, @Nullable ProjectMatch match) {
        if (match == null || isBlank(match.projectId)) return;
        matches.put(match.platform.name() + ":" + match.projectId, match);
    }

    @Nullable
    private static ProjectMatch fetchModrinthProject(@NonNull String projectId, boolean exact) {
        try {
            String body = readNetworkText("https://api.modrinth.com/v2/project/" + Uri.encode(projectId), null);
            JSONObject project = new JSONObject(body);
            return new ProjectMatch(
                    Platform.MODRINTH,
                    firstNonBlank(project.optString("id", ""), projectId),
                    firstNonBlank(project.optString("title", ""), project.optString("slug", "")),
                    project.optString("slug", ""),
                    firstNonBlank(project.optString("description", ""), project.optString("body", "")),
                    exact
            );
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to fetch Modrinth project " + projectId + ": " + readableError(throwable));
            return null;
        }
    }

    @Nullable
    private static ProjectMatch fetchCurseForgeProject(@NonNull String projectId, boolean exact) {
        try {
            String apiKey = CurseForgeApiKeyProvider.resolve();
            if (isBlank(apiKey)) return null;
            String body = readNetworkText("https://api.curseforge.com/v1/mods/" + Uri.encode(projectId), apiKey);
            JSONObject root = new JSONObject(body);
            JSONObject project = root.optJSONObject("data");
            if (project == null) return null;
            return new ProjectMatch(
                    Platform.CURSEFORGE,
                    String.valueOf(project.optLong("id", 0L)),
                    firstNonBlank(project.optString("name", ""), project.optString("slug", "")),
                    project.optString("slug", ""),
                    firstNonBlank(project.optString("summary", ""), project.optString("description", "")),
                    exact
            );
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to fetch CurseForge project " + projectId + ": " + readableError(throwable));
            return null;
        }
    }

    @NonNull
    private static ArrayList<String> buildProjectSearchQueries(@NonNull InstalledModpackInfo installed) {
        ArrayList<String> queries = new ArrayList<>();
        addSearchQuery(queries, installed.displayTitle);

        // Some manifests store the pack title as a filename-like value. Search a cleaner
        // variant too so names such as Optimobile-1.20.1 still find Optimobile.
        String title = installed.displayTitle == null ? "" : installed.displayTitle.trim();
        if (!isBlank(title)) {
            String cleaned = title
                    .replace('_', ' ')
                    .replace('-', ' ')
                    .replaceAll("\\b(?:mc|minecraft)?\\s*1\\.\\d+(?:\\.\\d+)?\\b", " ")
                    .replaceAll("\\b(?:forge|fabric|neoforge|quilt)\\b", " ")
                    .trim();
            addSearchQuery(queries, cleaned);

            int separator = firstTitleSeparator(title);
            if (separator > 0) addSearchQuery(queries, title.substring(0, separator));
        }

        return queries;
    }

    private static void addSearchQuery(@NonNull ArrayList<String> queries, @Nullable String value) {
        if (value == null) return;
        String clean = value.trim();
        while (clean.contains("  ")) clean = clean.replace("  ", " ");
        if (clean.length() < 3) return;
        if ("modpack".equalsIgnoreCase(clean)) return;
        for (String existing : queries) {
            if (existing.equalsIgnoreCase(clean)) return;
        }
        queries.add(clean);
    }

    private static int firstTitleSeparator(@NonNull String value) {
        int best = -1;
        char[] separators = new char[]{'|', ':', '['};
        for (char separator : separators) {
            int index = value.indexOf(separator);
            if (index > 0 && (best < 0 || index < best)) best = index;
        }
        return best;
    }

    @Nullable
    private static ProjectMatch fetchModrinthProjectFromVersion(@NonNull String versionId, boolean exact) {
        try {
            String body = readNetworkText("https://api.modrinth.com/v2/version/" + Uri.encode(versionId), null);
            JSONObject version = new JSONObject(body);
            String projectId = version.optString("project_id", "");
            if (isBlank(projectId)) return null;
            return fetchModrinthProject(projectId, exact);
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to resolve Modrinth project from version " + versionId + ": " + readableError(throwable));
            return null;
        }
    }

    @NonNull
    private static ArrayList<ProjectMatch> searchModrinthProjects(@NonNull String query) throws Exception {
        ArrayList<ProjectMatch> result = new ArrayList<>();
        String facets = "[[\"project_type:modpack\"]]";
        String body = readNetworkText("https://api.modrinth.com/v2/search?query="
                + Uri.encode(query)
                + "&limit=50&facets="
                + Uri.encode(facets), null);
        JSONObject root = new JSONObject(body);
        JSONArray hits = root.optJSONArray("hits");
        if (hits == null) return result;

        for (int i = 0; i < hits.length(); i++) {
            JSONObject hit = hits.optJSONObject(i);
            if (hit == null) continue;
            String title = firstNonBlank(hit.optString("title", ""), hit.optString("slug", ""));
            String slug = hit.optString("slug", "");
            String description = hit.optString("description", "");
            if (!isSearchResultUsable(query, title, slug, description)) continue;
            result.add(new ProjectMatch(
                    Platform.MODRINTH,
                    hit.optString("project_id", hit.optString("id", "")),
                    title,
                    slug,
                    description,
                    false
            ));
        }
        return result;
    }

    @NonNull
    private static ArrayList<ProjectMatch> searchCurseForgeProjects(@NonNull String query) throws Exception {
        ArrayList<ProjectMatch> result = new ArrayList<>();
        String apiKey = CurseForgeApiKeyProvider.resolve();
        if (isBlank(apiKey)) return result;

        String body = readNetworkText("https://api.curseforge.com/v1/mods/search?gameId=432&classId=4471&pageSize=50&searchFilter="
                + Uri.encode(query), apiKey);
        JSONObject root = new JSONObject(body);
        JSONArray data = root.optJSONArray("data");
        if (data == null) return result;

        for (int i = 0; i < data.length(); i++) {
            JSONObject project = data.optJSONObject(i);
            if (project == null) continue;
            String name = firstNonBlank(project.optString("name", ""), project.optString("slug", ""));
            String slug = project.optString("slug", "");
            String summary = firstNonBlank(project.optString("summary", ""), project.optString("description", ""));
            if (!isSearchResultUsable(query, name, slug, summary)) continue;
            result.add(new ProjectMatch(
                    Platform.CURSEFORGE,
                    String.valueOf(project.optLong("id", 0L)),
                    name,
                    slug,
                    summary,
                    false
            ));
        }
        return result;
    }

    private static boolean isSearchResultUsable(
            @NonNull String query,
            @Nullable String title,
            @Nullable String slug,
            @Nullable String summary
    ) {
        String normalizedQuery = normalizeTitle(query);
        if (normalizedQuery.length() < 3) return false;

        String normalizedTitle = normalizeTitle(title);
        String normalizedSlug = normalizeTitle(slug);
        String normalizedSummary = normalizeTitle(summary);

        if (isLikelySameProject(normalizedQuery, normalizedTitle)) return true;
        if (isLikelySameProject(normalizedQuery, normalizedSlug)) return true;

        // Do not over-filter the API search results. CurseForge and Modrinth already rank
        // these by the user's pack title/search text, and packs like Optimobile may be
        // titled slightly differently across platforms. Keep results that mention the
        // query in the slug/summary, or any result for a reasonably specific query.
        if (normalizedSummary.contains(normalizedQuery)) return true;
        return normalizedQuery.length() >= 5;
    }

    private static boolean isLikelySameProject(@NonNull String normalizedQuery, @NonNull String normalizedTitle) {
        if (normalizedQuery.equals(normalizedTitle)) return true;
        if (normalizedQuery.length() >= 4 && normalizedTitle.contains(normalizedQuery)) return true;
        if (normalizedTitle.length() >= 4 && normalizedQuery.contains(normalizedTitle)) return true;
        String[] queryWords = normalizedQuery.split(" ");
        int meaningful = 0;
        int matched = 0;
        for (String word : queryWords) {
            if (word.length() < 4) continue;
            meaningful++;
            if (normalizedTitle.contains(word)) matched++;
        }
        return meaningful > 0 && matched == meaningful;
    }

    @NonNull
    private static InstalledModpackInfo resolveProjectFromTitle(
            @NonNull Context context,
            @NonNull InstalledModpackInfo installed,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull Listener listener
    ) throws Exception {
        String title = installed.displayTitle;
        if (isBlank(title) || "Modpack".equals(title)) return installed;

        ArrayList<InstalledModpackInfo> matches = new ArrayList<>();
        if (installed.platform == Platform.MODRINTH) {
            listener.onStatus("Searching Modrinth for " + title + "...");
            matches.addAll(searchModrinth(title, minecraftVersion, loader, installed.metadataFile));
        } else {
            listener.onStatus("Searching CurseForge for " + title + "...");
            matches.addAll(searchCurseForge(title, installed.metadataFile));
        }

        if (matches.size() == 1) return matches.get(0);
        if (matches.isEmpty()) return installed;
        throw new IllegalStateException("Multiple matching modpacks were found for " + title + ". Reinstall the pack so DroidBridge can save exact update metadata.");
    }

    @NonNull
    private static ArrayList<InstalledModpackInfo> searchModrinth(
            @NonNull String query,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull File metadataFile
    ) throws Exception {
        ArrayList<InstalledModpackInfo> result = new ArrayList<>();
        StringBuilder facets = new StringBuilder("[[\"project_type:modpack\"]");
        if (!isBlank(minecraftVersion)) {
            facets.append(",[\"versions:").append(escapeFacetValue(minecraftVersion)).append("\"]");
        }
        String normalizedLoader = normalizeLoader(loader);
        if (!isBlank(normalizedLoader) && !"vanilla".equals(normalizedLoader)) {
            facets.append(",[\"categories:").append(escapeFacetValue(normalizedLoader)).append("\"]");
        }
        facets.append("]");

        String body = readNetworkText("https://api.modrinth.com/v2/search?query="
                + Uri.encode(query)
                + "&limit=10&facets="
                + Uri.encode(facets.toString()), null);
        JSONObject root = new JSONObject(body);
        JSONArray hits = root.optJSONArray("hits");
        if (hits == null) return result;

        String normalizedQuery = normalizeTitle(query);
        for (int i = 0; i < hits.length(); i++) {
            JSONObject hit = hits.optJSONObject(i);
            if (hit == null) continue;
            String title = firstNonBlank(hit.optString("title", ""), hit.optString("slug", ""));
            if (!normalizedQuery.equals(normalizeTitle(title))) continue;
            result.add(new InstalledModpackInfo(
                    Platform.MODRINTH,
                    hit.optString("project_id", hit.optString("id", "")),
                    "",
                    "",
                    "",
                    title,
                    metadataFile
            ));
        }
        return result;
    }

    @NonNull
    private static ArrayList<InstalledModpackInfo> searchCurseForge(@NonNull String query, @NonNull File metadataFile) throws Exception {
        ArrayList<InstalledModpackInfo> result = new ArrayList<>();
        String apiKey = CurseForgeApiKeyProvider.resolve();
        if (isBlank(apiKey)) return result;

        String body = readNetworkText("https://api.curseforge.com/v1/mods/search?gameId=432&classId=4471&pageSize=10&searchFilter="
                + Uri.encode(query), apiKey);
        JSONObject root = new JSONObject(body);
        JSONArray data = root.optJSONArray("data");
        if (data == null) return result;

        String normalizedQuery = normalizeTitle(query);
        for (int i = 0; i < data.length(); i++) {
            JSONObject project = data.optJSONObject(i);
            if (project == null) continue;
            String name = firstNonBlank(project.optString("name", ""), project.optString("slug", ""));
            if (!normalizedQuery.equals(normalizeTitle(name))) continue;
            result.add(new InstalledModpackInfo(
                    Platform.CURSEFORGE,
                    String.valueOf(project.optLong("id", 0L)),
                    "",
                    "",
                    "",
                    name,
                    metadataFile
            ));
        }
        return result;
    }

    @NonNull
    private static ArrayList<VersionInfo> loadModrinthVersions(@NonNull String projectId) throws Exception {
        String body = readNetworkText("https://api.modrinth.com/v2/project/" + Uri.encode(projectId) + "/version", null);
        JSONArray versions = new JSONArray(body);
        ArrayList<VersionInfo> result = new ArrayList<>();
        for (int i = 0; i < versions.length(); i++) {
            JSONObject version = versions.optJSONObject(i);
            if (version == null) continue;
            JSONObject primaryFile = findPrimaryModrinthFile(version.optJSONArray("files"));
            if (primaryFile == null) continue;
            result.add(new VersionInfo(
                    Platform.MODRINTH,
                    projectId,
                    version.optString("id", ""),
                    firstNonBlank(readHashFromObject(primaryFile), primaryFile.optString("filename", "")),
                    version.optString("version_number", ""),
                    version.optString("name", ""),
                    version.optString("date_published", ""),
                    primaryFile.optString("url", ""),
                    primaryFile.optString("filename", "modpack.mrpack"),
                    jsonArrayToStringList(version.optJSONArray("game_versions")),
                    jsonArrayToStringList(version.optJSONArray("loaders"))
            ));
        }
        return result;
    }

    @NonNull
    private static ArrayList<VersionInfo> loadCurseForgeVersions(@NonNull String projectId) throws Exception {
        String apiKey = CurseForgeApiKeyProvider.resolve();
        if (isBlank(apiKey)) throw new IllegalStateException("CurseForge API key is not configured.");

        ArrayList<VersionInfo> result = new ArrayList<>();
        final int pageSize = 200;
        int index = 0;
        int totalCount = Integer.MAX_VALUE;

        // CurseForge can have several hundred pack files. Do not stop at the first
        // page, otherwise old Minecraft releases can disappear from the migration
        // picker even though the project still has those pack versions.
        while (index < totalCount) {
            String body = readNetworkText("https://api.curseforge.com/v1/mods/"
                    + Uri.encode(projectId)
                    + "/files?pageSize=" + pageSize
                    + "&index=" + index
                    + "&sortField=2&sortOrder=desc", apiKey);
            JSONObject root = new JSONObject(body);
            JSONArray data = root.optJSONArray("data");
            if (data == null || data.length() == 0) break;

            JSONObject pagination = root.optJSONObject("pagination");
            if (pagination != null) {
                totalCount = Math.max(0, pagination.optInt("totalCount", totalCount));
            }

            for (int i = 0; i < data.length(); i++) {
                JSONObject file = data.optJSONObject(i);
                if (file == null || file.optInt("isAvailable", 1) == 0) continue;
                String fileId = String.valueOf(file.optLong("id", file.optLong("fileId", 0L)));
                result.add(new VersionInfo(
                        Platform.CURSEFORGE,
                        projectId,
                        fileId,
                        fileId,
                        firstNonBlank(file.optString("displayName", ""), file.optString("fileName", "")),
                        firstNonBlank(file.optString("fileName", ""), file.optString("displayName", "")),
                        file.optString("fileDate", ""),
                        file.optString("downloadUrl", ""),
                        file.optString("fileName", "modpack.zip"),
                        readCurseForgeGameVersions(file),
                        readCurseForgeLoaders(file)
                ));
            }

            index += data.length();
            if (data.length() < pageSize) break;
        }
        return result;
    }

    @NonNull
    private static VersionInfo fetchLatestModrinthVersion(
            @NonNull String projectId,
            @NonNull String minecraftVersion,
            @Nullable String loader
    ) throws Exception {
        String body = readNetworkText("https://api.modrinth.com/v2/project/" + Uri.encode(projectId) + "/version", null);
        JSONArray versions = new JSONArray(body);
        ArrayList<JSONObject> candidates = new ArrayList<>();
        String normalizedLoader = normalizeLoader(loader);
        for (int i = 0; i < versions.length(); i++) {
            JSONObject version = versions.optJSONObject(i);
            if (version == null) continue;
            if (!jsonArrayContainsIgnoreCase(version.optJSONArray("game_versions"), minecraftVersion)) continue;
            if (!isBlank(normalizedLoader) && !"vanilla".equals(normalizedLoader)) {
                JSONArray loaders = version.optJSONArray("loaders");
                if (loaders != null && loaders.length() > 0 && !jsonArrayContainsIgnoreCase(loaders, normalizedLoader)) {
                    continue;
                }
            }
            candidates.add(version);
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No compatible Modrinth modpack version found for Minecraft " + minecraftVersion + ".");
        }

        Collections.sort(candidates, (left, right) -> right.optString("date_published", "").compareTo(left.optString("date_published", "")));
        JSONObject latest = candidates.get(0);
        JSONObject primaryFile = findPrimaryModrinthFile(latest.optJSONArray("files"));
        if (primaryFile == null) throw new IllegalStateException("Latest Modrinth version has no downloadable file.");

        return new VersionInfo(
                Platform.MODRINTH,
                projectId,
                latest.optString("id", ""),
                firstNonBlank(primaryFile.optString("hashes", ""), primaryFile.optString("filename", "")),
                latest.optString("version_number", ""),
                latest.optString("name", ""),
                latest.optString("date_published", ""),
                primaryFile.optString("url", ""),
                primaryFile.optString("filename", "modpack.mrpack")
        );
    }

    @NonNull
    private static JSONObject findPrimaryModrinthFile(@Nullable JSONArray files) {
        if (files == null || files.length() == 0) return null;
        JSONObject first = files.optJSONObject(0);
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file != null && file.optBoolean("primary", false)) return file;
        }
        return first;
    }

    @NonNull
    private static VersionInfo fetchLatestCurseForgeVersion(
            @NonNull String projectId,
            @NonNull String minecraftVersion,
            @Nullable String loader
    ) throws Exception {
        String apiKey = CurseForgeApiKeyProvider.resolve();
        if (isBlank(apiKey)) throw new IllegalStateException("CurseForge API key is not configured.");

        StringBuilder url = new StringBuilder("https://api.curseforge.com/v1/mods/")
                .append(Uri.encode(projectId))
                .append("/files?pageSize=50&sortField=2&sortOrder=desc");
        if (!isBlank(minecraftVersion)) url.append("&gameVersion=").append(Uri.encode(minecraftVersion));
        int loaderType = curseForgeModLoaderType(loader);
        if (loaderType > 0) url.append("&modLoaderType=").append(loaderType);

        String body = readNetworkText(url.toString(), apiKey);
        JSONObject root = new JSONObject(body);
        JSONArray data = root.optJSONArray("data");
        if (data == null || data.length() == 0) {
            throw new IllegalStateException("No compatible CurseForge modpack file found for Minecraft " + minecraftVersion + ".");
        }

        ArrayList<JSONObject> candidates = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject file = data.optJSONObject(i);
            if (file == null) continue;
            if (file.optInt("isAvailable", 1) == 0) continue;
            candidates.add(file);
        }
        if (candidates.isEmpty()) throw new IllegalStateException("No available CurseForge modpack file found.");
        Collections.sort(candidates, (left, right) -> right.optString("fileDate", "").compareTo(left.optString("fileDate", "")));
        JSONObject latest = candidates.get(0);

        String fileId = String.valueOf(latest.optLong("id", latest.optLong("fileId", 0L)));
        return new VersionInfo(
                Platform.CURSEFORGE,
                projectId,
                fileId,
                fileId,
                firstNonBlank(latest.optString("displayName", ""), latest.optString("fileName", "")),
                firstNonBlank(latest.optString("fileName", ""), latest.optString("displayName", "")),
                latest.optString("fileDate", ""),
                latest.optString("downloadUrl", ""),
                latest.optString("fileName", "modpack.zip")
        );
    }

    @NonNull
    private static VersionInfo latestWithCurseForgeDownloadUrl(@NonNull VersionInfo latest) throws Exception {
        String apiKey = CurseForgeApiKeyProvider.resolve();
        if (isBlank(apiKey)) throw new IllegalStateException("CurseForge API key is not configured.");
        String body = readNetworkText("https://api.curseforge.com/v1/mods/"
                + Uri.encode(latest.projectId)
                + "/files/"
                + Uri.encode(firstNonBlank(latest.fileId, latest.versionId))
                + "/download-url", apiKey);
        String downloadUrl = body.trim();
        if (downloadUrl.startsWith("\"") && downloadUrl.endsWith("\"") && downloadUrl.length() >= 2) {
            downloadUrl = downloadUrl.substring(1, downloadUrl.length() - 1).replace("\\/", "/");
        }
        return new VersionInfo(
                latest.platform,
                latest.projectId,
                latest.versionId,
                latest.fileId,
                latest.versionNumber,
                latest.versionName,
                latest.datePublished,
                downloadUrl,
                latest.fileName,
                latest.gameVersions,
                latest.loaders
        );
    }

    private static boolean isUpdateAvailable(@NonNull InstalledModpackInfo installed, @NonNull VersionInfo latest) {
        if (installed.platform == Platform.MODRINTH) {
            if (!isBlank(installed.currentVersionId)) return !installed.currentVersionId.equalsIgnoreCase(latest.versionId);
            if (!isBlank(installed.currentVersionNumber)) return !installed.currentVersionNumber.equalsIgnoreCase(latest.versionNumber);
            return true;
        }
        String currentFile = firstNonBlank(installed.currentFileId, installed.currentVersionId);
        String latestFile = firstNonBlank(latest.fileId, latest.versionId);
        if (!isBlank(currentFile) && !isBlank(latestFile)) return !currentFile.equalsIgnoreCase(latestFile);
        if (!isBlank(installed.currentVersionNumber)) return !installed.currentVersionNumber.equalsIgnoreCase(latest.versionNumber);
        return true;
    }

    @NonNull
    private static UpdateResult installModrinthPack(
            @NonNull File gameDirectory,
            @NonNull InstalledModpackInfo installed,
            @NonNull VersionInfo latest,
            @NonNull File packFile,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull Listener listener
    ) throws Exception {
        ArrayList<ManagedFileEntry> newEntries = new ArrayList<>();
        HashSet<String> newManagedPaths = new HashSet<>();
        Map<String, String> saveWorldRemaps = new LinkedHashMap<>();
        int installedCount = 0;
        int removedBeforeInstall;

        try (ZipFile zip = new ZipFile(packFile)) {
            JSONObject index = new JSONObject(readZipEntryText(zip, "modrinth.index.json"));
            JSONArray files = index.optJSONArray("files");
            int total = countPackWork(zip, files, "overrides", "client-overrides");
            int progress = 0;
            removedBeforeInstall = resetModpackContentFolders(gameDirectory, listener);

            if (files != null) {
                for (int i = 0; i < files.length(); i++) {
                    JSONObject fileEntry = files.optJSONObject(i);
                    if (fileEntry == null) continue;
                    String relativePath = normalizeRelativePath(fileEntry.optString("path", ""));
                    JSONArray downloads = fileEntry.optJSONArray("downloads");
                    String downloadUrl = downloads != null && downloads.length() > 0 ? downloads.optString(0, "") : "";
                    if (isBlank(relativePath) || isBlank(downloadUrl)) continue;

                    File target = resolveSafeChild(gameDirectory, relativePath);
                    listener.onStatus("Downloading " + target.getName() + "...");
                    downloadToFile(downloadUrl, target, null);
                    installedCount++;

                    ModManagerContentType type = resolveContentType(relativePath);
                    if (type != null) {
                        newManagedPaths.add(normalizeRelativePath(relativePath));
                        newEntries.add(new ManagedFileEntry(
                                relativePath,
                                type,
                                Platform.MODRINTH,
                                readString(fileEntry, "projectId", "project_id", "modrinthProjectId"),
                                latest.versionId,
                                readHash(fileEntry),
                                latest.versionNumber,
                                target.getName(),
                                target
                        ));
                    }

                    progress++;
                    listener.onProgress(progress, Math.max(1, total));
                }
            }

            progress = extractOverrides(zip, gameDirectory, "overrides", latest, newEntries, newManagedPaths, saveWorldRemaps, progress, total, listener);
            extractOverrides(zip, gameDirectory, "client-overrides", latest, newEntries, newManagedPaths, saveWorldRemaps, progress, total, listener);
        }

        int removed = removedBeforeInstall + pruneOldManagedContent(gameDirectory, newManagedPaths);
        writeFilesManifest(gameDirectory, installed, latest, newEntries, minecraftVersion, loader);
        writeInstalledModpackManifest(gameDirectory, installed, latest, minecraftVersion, loader);
        return new UpdateResult(latest.versionLabel, installedCount, removed, preferredMinecraftVersion(latest, minecraftVersion), preferredLoader(latest, loader));
    }

    @NonNull
    private static UpdateResult installCurseForgePack(
            @NonNull File gameDirectory,
            @NonNull InstalledModpackInfo installed,
            @NonNull VersionInfo latest,
            @NonNull File packFile,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @NonNull Listener listener
    ) throws Exception {
        ArrayList<ManagedFileEntry> newEntries = new ArrayList<>();
        HashSet<String> newManagedPaths = new HashSet<>();
        Map<String, String> saveWorldRemaps = new LinkedHashMap<>();
        int installedCount = 0;
        int removedBeforeInstall;
        String apiKey = CurseForgeApiKeyProvider.resolve();
        if (isBlank(apiKey)) throw new IllegalStateException("CurseForge API key is not configured.");

        try (ZipFile zip = new ZipFile(packFile)) {
            JSONObject manifest = new JSONObject(readZipEntryText(zip, "manifest.json"));
            JSONArray files = manifest.optJSONArray("files");
            String overridesFolder = firstNonBlank(manifest.optString("overrides", ""), "overrides");
            int total = countPackWork(zip, files, overridesFolder, null);
            int progress = 0;
            removedBeforeInstall = resetModpackContentFolders(gameDirectory, listener);

            if (files != null) {
                for (int i = 0; i < files.length(); i++) {
                    JSONObject fileEntry = files.optJSONObject(i);
                    if (fileEntry == null) continue;
                    String projectId = readString(fileEntry, "projectID", "projectId", "project_id");
                    String fileId = readString(fileEntry, "fileID", "fileId", "file_id");
                    if (isBlank(projectId) || isBlank(fileId)) continue;

                    JSONObject fileInfo = fetchCurseForgeFileInfo(projectId, fileId, apiKey);
                    String downloadUrl = fileInfo.optString("downloadUrl", "");
                    if (isBlank(downloadUrl)) {
                        downloadUrl = fetchCurseForgeFileDownloadUrl(projectId, fileId, apiKey);
                    }
                    String fileName = firstNonBlank(fileInfo.optString("fileName", ""), "curseforge-" + fileId + ".jar");
                    ModManagerContentType type = resolveCurseForgeContentType(projectId, fileName, apiKey);
                    String relativePath = normalizeRelativePath(getTargetFolderName(type) + "/" + sanitizeFileName(fileName));
                    File target = resolveSafeChild(gameDirectory, relativePath);

                    listener.onStatus("Downloading " + target.getName() + "...");
                    downloadToFile(downloadUrl, target, apiKey);
                    installedCount++;

                    newManagedPaths.add(relativePath);
                    newEntries.add(new ManagedFileEntry(
                            relativePath,
                            type,
                            Platform.CURSEFORGE,
                            projectId,
                            fileId,
                            fileId,
                            latest.versionNumber,
                            target.getName(),
                            target
                    ));

                    progress++;
                    listener.onProgress(progress, Math.max(1, total));
                }
            }

            extractOverrides(zip, gameDirectory, overridesFolder, latest, newEntries, newManagedPaths, saveWorldRemaps, progress, total, listener);
        }

        int removed = removedBeforeInstall + pruneOldManagedContent(gameDirectory, newManagedPaths);
        writeFilesManifest(gameDirectory, installed, latest, newEntries, minecraftVersion, loader);
        writeInstalledModpackManifest(gameDirectory, installed, latest, minecraftVersion, loader);
        return new UpdateResult(latest.versionLabel, installedCount, removed, preferredMinecraftVersion(latest, minecraftVersion), preferredLoader(latest, loader));
    }

    private static int extractOverrides(
            @NonNull ZipFile zip,
            @NonNull File gameDirectory,
            @Nullable String rawFolder,
            @NonNull VersionInfo latest,
            @NonNull ArrayList<ManagedFileEntry> newEntries,
            @NonNull Set<String> newManagedPaths,
            @NonNull Map<String, String> saveWorldRemaps,
            int progress,
            int total,
            @NonNull Listener listener
    ) throws Exception {
        String folder = normalizeZipPrefix(rawFolder);
        if (isBlank(folder)) return progress;

        ArrayList<ZipEntry> entries = listZipEntries(zip);
        entries.sort(Comparator.comparing(ZipEntry::getName, String.CASE_INSENSITIVE_ORDER));
        for (ZipEntry entry : entries) {
            if (entry == null || entry.isDirectory()) continue;
            String name = normalizeRelativePath(entry.getName());
            if (!name.startsWith(folder)) continue;
            String relativePath = normalizeRelativePath(name.substring(folder.length()));
            if (isBlank(relativePath)) continue;

            File target = resolveOverrideTarget(gameDirectory, relativePath, saveWorldRemaps);
            listener.onStatus("Extracting " + target.getName() + "...");
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Unable to create folder: " + parent.getAbsolutePath());
            }
            try (InputStream input = zip.getInputStream(entry); FileOutputStream output = new FileOutputStream(target)) {
                copyStream(input, output);
            }

            ModManagerContentType type = resolveContentType(relativePath);
            if (type != null) {
                String normalizedPath = normalizeRelativePath(relativePath);
                newManagedPaths.add(normalizedPath);
                newEntries.add(new ManagedFileEntry(
                        normalizedPath,
                        type,
                        latest.platform,
                        latest.projectId,
                        latest.versionId,
                        latest.fileId,
                        latest.versionNumber,
                        target.getName(),
                        target
                ));
            }

            progress++;
            listener.onProgress(progress, Math.max(1, total));
        }
        return progress;
    }

    @NonNull
    private static File resolveOverrideTarget(
            @NonNull File gameDirectory,
            @NonNull String relativePath,
            @NonNull Map<String, String> saveWorldRemaps
    ) throws IOException {
        String normalized = normalizeRelativePath(relativePath);
        if (!normalized.toLowerCase(Locale.US).startsWith("saves/")) {
            return resolveSafeChild(gameDirectory, normalized);
        }

        String[] parts = normalized.split("/", 3);
        if (parts.length < 2 || isBlank(parts[1])) {
            return resolveSafeChild(gameDirectory, normalized);
        }

        String originalWorldName = sanitizeFileName(parts[1]);
        String targetWorldName = saveWorldRemaps.get(originalWorldName);
        File savesDirectory = resolveSafeChild(gameDirectory, "saves");
        if (isBlank(targetWorldName)) {
            File requestedWorldDirectory = new File(savesDirectory, originalWorldName);
            if (requestedWorldDirectory.exists()) {
                targetWorldName = uniqueDirectory(savesDirectory, requestedWorldDirectory.getName() + " Pack World").getName();
            } else {
                targetWorldName = originalWorldName;
            }
            saveWorldRemaps.put(originalWorldName, targetWorldName);
        }

        File targetWorldDirectory = new File(savesDirectory, targetWorldName);
        if (parts.length == 2) return targetWorldDirectory;
        return resolveSafeChild(targetWorldDirectory, parts[2]);
    }

    @NonNull
    private static File uniqueDirectory(@NonNull File parent, @NonNull String baseName) {
        String cleanBase = sanitizeFileName(baseName);
        File candidate = new File(parent, cleanBase);
        if (!candidate.exists()) return candidate;
        for (int i = 2; i < 1000; i++) {
            candidate = new File(parent, cleanBase + " " + i);
            if (!candidate.exists()) return candidate;
        }
        return new File(parent, cleanBase + " " + System.currentTimeMillis());
    }

    private static int countPackWork(@NonNull ZipFile zip, @Nullable JSONArray files, @Nullable String overridesFolder, @Nullable String secondOverridesFolder) {
        int count = files == null ? 0 : files.length();
        String first = normalizeZipPrefix(overridesFolder);
        String second = normalizeZipPrefix(secondOverridesFolder);
        ArrayList<ZipEntry> entries = listZipEntries(zip);
        for (ZipEntry entry : entries) {
            if (entry == null || entry.isDirectory()) continue;
            String name = normalizeRelativePath(entry.getName());
            if ((!isBlank(first) && name.startsWith(first)) || (!isBlank(second) && name.startsWith(second))) count++;
        }
        return Math.max(1, count);
    }

    @NonNull
    private static JSONObject fetchCurseForgeFileInfo(@NonNull String projectId, @NonNull String fileId, @NonNull String apiKey) throws Exception {
        String body = readNetworkText("https://api.curseforge.com/v1/mods/"
                + Uri.encode(projectId)
                + "/files/"
                + Uri.encode(fileId), apiKey);
        JSONObject root = new JSONObject(body);
        JSONObject data = root.optJSONObject("data");
        if (data == null) throw new IOException("CurseForge file info did not include data.");
        return data;
    }

    @NonNull
    private static String fetchCurseForgeFileDownloadUrl(@NonNull String projectId, @NonNull String fileId, @NonNull String apiKey) throws Exception {
        String body = readNetworkText("https://api.curseforge.com/v1/mods/"
                + Uri.encode(projectId)
                + "/files/"
                + Uri.encode(fileId)
                + "/download-url", apiKey);
        String value = body.trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            value = value.substring(1, value.length() - 1).replace("\\/", "/");
        }
        return value;
    }

    @NonNull
    private static ModManagerContentType resolveCurseForgeContentType(@NonNull String projectId, @NonNull String fileName, @NonNull String apiKey) {
        try {
            String body = readNetworkText("https://api.curseforge.com/v1/mods/" + Uri.encode(projectId), apiKey);
            JSONObject root = new JSONObject(body);
            JSONObject data = root.optJSONObject("data");
            int classId = data == null ? 0 : data.optInt("classId", 0);
            if (classId == 12) return ModManagerContentType.RESOURCEPACKS;
            if (classId == 6552) return ModManagerContentType.SHADERPACKS;
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to resolve CurseForge content type for " + projectId + ": " + readableError(throwable));
        }

        String lowerName = fileName.toLowerCase(Locale.US);
        if (lowerName.endsWith(".zip")) return ModManagerContentType.RESOURCEPACKS;
        return ModManagerContentType.MODS;
    }


    @NonNull
    private static ArrayList<ZipEntry> listZipEntries(@NonNull ZipFile zip) {
        ArrayList<ZipEntry> entries = new ArrayList<>();
        java.util.Enumeration<? extends ZipEntry> enumeration = zip.entries();
        while (enumeration.hasMoreElements()) {
            entries.add(enumeration.nextElement());
        }
        return entries;
    }

    @NonNull
    private static String getTargetFolderName(@NonNull ModManagerContentType type) {
        if (type == ModManagerContentType.RESOURCEPACKS) return "resourcepacks";
        if (type == ModManagerContentType.SHADERPACKS) return "shaderpacks";
        return "mods";
    }

    /**
     * A modpack version is a complete pack state. Before installing the selected update,
     * clear the folders that are owned by the pack so old jars/zips from a previous
     * Minecraft/loader generation cannot survive beside the new pack files. Saves are
     * intentionally not touched.
     */
    private static int resetModpackContentFolders(
            @NonNull File gameDirectory,
            @NonNull Listener listener
    ) throws IOException {
        int removed = 0;
        String[] folderNames = new String[]{"mods", "shaderpacks", "resourcepacks"};
        for (String folderName : folderNames) {
            File folder = resolveSafeChild(gameDirectory, folderName);
            listener.onStatus("Clearing old " + folderName + "...");
            removed += deleteDirectoryTree(folder);
            if (!folder.exists() && !folder.mkdirs()) {
                throw new IOException("Unable to recreate folder: " + folder.getAbsolutePath());
            }
        }
        return removed;
    }

    private static int deleteDirectoryTree(@NonNull File file) throws IOException {
        if (!file.exists()) return 0;

        int removedFiles = 0;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    removedFiles += deleteDirectoryTree(child);
                }
            }
        } else {
            removedFiles = 1;
        }

        if (!file.delete() && file.exists()) {
            throw new IOException("Unable to delete: " + file.getAbsolutePath());
        }
        return removedFiles;
    }

    private static int pruneOldManagedContent(@NonNull File gameDirectory, @NonNull Set<String> newManagedPaths) {
        int removed = 0;
        for (String oldPath : readOldManagedContentPaths(gameDirectory)) {
            String normalized = normalizeRelativePath(oldPath);
            if (isBlank(normalized) || newManagedPaths.contains(normalized)) continue;
            ModManagerContentType type = resolveContentType(normalized);
            if (type == null) continue;

            try {
                File file = resolveSafeChild(gameDirectory, normalized);
                if (deleteIfFile(file)) removed++;
                File disabled = new File(file.getAbsolutePath() + ".disabled");
                if (deleteIfFile(disabled)) removed++;
            } catch (Throwable throwable) {
                Logging.i(TAG, "Unable to remove old modpack file " + normalized + ": " + readableError(throwable));
            }
        }
        return removed;
    }

    @NonNull
    private static HashSet<String> readOldManagedContentPaths(@NonNull File gameDirectory) {
        HashSet<String> paths = new HashSet<>();
        File manifest = getFilesManifestFile(gameDirectory);
        if (!manifest.isFile()) return paths;
        try {
            JSONObject root = new JSONObject(readTextFile(manifest));
            JSONArray files = root.optJSONArray("files");
            if (files == null) return paths;
            for (int i = 0; i < files.length(); i++) {
                JSONObject entry = files.optJSONObject(i);
                if (entry == null) continue;
                String path = firstNonBlank(entry.optString("relativePath", ""), entry.optString("filePath", ""), entry.optString("path", ""));
                ModManagerContentType type = resolveContentType(path);
                if (type != null) paths.add(normalizeRelativePath(path));
            }
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to read old modpack file list: " + readableError(throwable));
        }
        return paths;
    }

    private static boolean deleteIfFile(@NonNull File file) {
        return file.isFile() && file.delete();
    }

    private static void writeInstalledModpackManifest(
            @NonNull File gameDirectory,
            @NonNull InstalledModpackInfo installed,
            @NonNull VersionInfo latest,
            @NonNull String minecraftVersion,
            @Nullable String loader
    ) throws Exception {
        JSONObject root = new JSONObject();
        root.put("source", latest.platform.displayName.toLowerCase(Locale.US));
        root.put("platform", latest.platform.displayName.toLowerCase(Locale.US));
        root.put("modpackPlatform", latest.platform.displayName.toLowerCase(Locale.US));
        root.put("platformProjectId", firstNonBlank(latest.projectId, installed.projectId));
        root.put("projectId", firstNonBlank(latest.projectId, installed.projectId));
        root.put("title", installed.displayTitle);
        root.put("name", installed.displayTitle);
        root.put("versionId", latest.versionId);
        root.put("fileId", latest.fileId);
        root.put("versionNumber", latest.versionNumber);
        root.put("versionName", latest.versionName);
        root.put("minecraftVersion", minecraftVersion);
        root.put("loader", normalizeLoader(loader));
        root.put("updatedAt", System.currentTimeMillis());
        writeTextFile(getInstalledMetadataFile(gameDirectory), root.toString(2));
    }

    private static void writeFilesManifest(
            @NonNull File gameDirectory,
            @NonNull InstalledModpackInfo installed,
            @NonNull VersionInfo latest,
            @NonNull ArrayList<ManagedFileEntry> entries,
            @NonNull String minecraftVersion,
            @Nullable String loader
    ) throws Exception {
        JSONObject root = new JSONObject();
        root.put("source", latest.platform.displayName.toLowerCase(Locale.US));
        root.put("platform", latest.platform.displayName.toLowerCase(Locale.US));
        root.put("modpackPlatform", latest.platform.displayName.toLowerCase(Locale.US));
        root.put("platformProjectId", firstNonBlank(latest.projectId, installed.projectId));
        root.put("projectId", firstNonBlank(latest.projectId, installed.projectId));
        root.put("title", installed.displayTitle);
        root.put("versionId", latest.versionId);
        root.put("fileId", latest.fileId);
        root.put("versionNumber", latest.versionNumber);
        root.put("minecraftVersion", minecraftVersion);
        root.put("loader", normalizeLoader(loader));
        root.put("updatedAt", System.currentTimeMillis());

        JSONArray files = new JSONArray();
        for (ManagedFileEntry entry : entries) {
            JSONObject item = new JSONObject();
            item.put("source", entry.platform.displayName.toLowerCase(Locale.US));
            item.put("platform", entry.platform.displayName.toLowerCase(Locale.US));
            item.put("modpackPlatform", entry.platform.displayName.toLowerCase(Locale.US));
            item.put("contentType", entry.contentType.getIntentValue());
            item.put("type", entry.contentType.getIntentValue());
            item.put("platformProjectId", entry.projectId);
            item.put("projectId", entry.projectId);
            item.put("versionId", entry.versionId);
            item.put("fileId", entry.fileId);
            item.put("versionNumber", entry.versionNumber);
            item.put("fileName", entry.fileName);
            item.put("relativePath", entry.relativePath);
            item.put("filePath", entry.relativePath);
            item.put("absolutePath", entry.file.getAbsolutePath());
            item.put("canonicalPath", safeCanonicalPath(entry.file));
            item.put("installedAt", System.currentTimeMillis());
            item.put("updatedBy", "DroidBridge");
            files.put(item);
        }
        root.put("files", files);
        writeTextFile(getFilesManifestFile(gameDirectory), root.toString(2));
    }

    @NonNull
    private static File getInstalledMetadataFile(@NonNull File gameDirectory) {
        return new File(new File(gameDirectory, METADATA_DIRECTORY), MODPACK_METADATA_FILE);
    }

    @NonNull
    private static File getFilesManifestFile(@NonNull File gameDirectory) {
        return new File(new File(gameDirectory, METADATA_DIRECTORY), MODPACK_FILES_METADATA_FILE);
    }

    @Nullable
    private static ModManagerContentType resolveContentType(@Nullable String relativePath) {
        String normalized = normalizeRelativePath(relativePath);
        if (normalized.startsWith("mods/") && (normalized.endsWith(".jar") || normalized.endsWith(".jar.disabled"))) {
            return ModManagerContentType.MODS;
        }
        if (normalized.startsWith("resourcepacks/") && (normalized.endsWith(".zip") || normalized.endsWith(".zip.disabled"))) {
            return ModManagerContentType.RESOURCEPACKS;
        }
        if (normalized.startsWith("shaderpacks/") && (normalized.endsWith(".zip") || normalized.endsWith(".zip.disabled"))) {
            return ModManagerContentType.SHADERPACKS;
        }
        return null;
    }

    @NonNull
    private static String preferredMinecraftVersion(@NonNull VersionInfo version, @NonNull String fallback) {
        if (!isBlank(version.primaryMinecraftVersion)) return version.primaryMinecraftVersion;
        return fallback;
    }

    @NonNull
    private static String preferredLoader(@NonNull VersionInfo version, @Nullable String fallback) {
        if (!isBlank(version.primaryLoader)) return normalizeLoader(version.primaryLoader);
        return normalizeLoader(fallback);
    }

    @NonNull
    private static ArrayList<String> jsonArrayToStringList(@Nullable JSONArray array) {
        ArrayList<String> result = new ArrayList<>();
        if (array == null) return result;
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim();
            if (!value.isEmpty() && !result.contains(value)) result.add(value);
        }
        return result;
    }

    @NonNull
    private static ArrayList<String> readCurseForgeGameVersions(@NonNull JSONObject file) {
        ArrayList<String> result = new ArrayList<>();
        JSONArray gameVersions = file.optJSONArray("gameVersions");
        if (gameVersions == null) return result;
        for (int i = 0; i < gameVersions.length(); i++) {
            String value = gameVersions.optString(i, "").trim();
            if (value.matches("\\d+\\.\\d+(\\.\\d+)?") && !result.contains(value)) result.add(value);
        }
        return result;
    }

    @NonNull
    private static ArrayList<String> readCurseForgeLoaders(@NonNull JSONObject file) {
        ArrayList<String> result = new ArrayList<>();
        JSONArray gameVersions = file.optJSONArray("gameVersions");
        if (gameVersions == null) return result;
        for (int i = 0; i < gameVersions.length(); i++) {
            String normalized = normalizeLoader(gameVersions.optString(i, ""));
            if (!isBlank(normalized) && !normalized.matches("\\d+.*") && !result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    @NonNull
    private static String joinStrings(@NonNull ArrayList<String> values, @NonNull String separator) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (isBlank(value)) continue;
            if (builder.length() > 0) builder.append(separator);
            builder.append(value);
        }
        return builder.toString();
    }

    private static int compareVersionStrings(@NonNull String left, @NonNull String right) {
        ArrayList<Integer> leftParts = extractComparableVersionParts(left);
        ArrayList<Integer> rightParts = extractComparableVersionParts(right);
        int max = Math.max(leftParts.size(), rightParts.size());
        for (int i = 0; i < max; i++) {
            int l = i < leftParts.size() ? leftParts.get(i) : 0;
            int r = i < rightParts.size() ? rightParts.get(i) : 0;
            if (l != r) return Integer.compare(l, r);
        }
        return left.compareToIgnoreCase(right);
    }

    @NonNull
    private static ArrayList<Integer> extractComparableVersionParts(@NonNull String value) {
        ArrayList<Integer> result = new ArrayList<>();
        String[] parts = value.toLowerCase(Locale.US).replaceAll("[^0-9.\\-+]", " ").split("[.\\-+ ]+");
        for (String part : parts) {
            try {
                if (!part.trim().isEmpty()) result.add(Integer.parseInt(part.trim()));
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    @NonNull
    private static String readHashFromObject(@NonNull JSONObject file) {
        JSONObject hashes = file.optJSONObject("hashes");
        if (hashes == null) return "";
        return firstNonBlank(hashes.optString("sha1", ""), hashes.optString("sha512", ""));
    }

    @NonNull
    private static String readHash(@NonNull JSONObject fileEntry) {
        JSONObject hashes = fileEntry.optJSONObject("hashes");
        if (hashes == null) return "";
        return firstNonBlank(hashes.optString("sha1", ""), hashes.optString("sha512", ""));
    }

    @NonNull
    private static String readZipEntryText(@NonNull ZipFile zip, @NonNull String entryName) throws IOException {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null || entry.isDirectory()) throw new IOException("Missing " + entryName);
        try (InputStream input = zip.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copyStream(input, output);
            return output.toString("UTF-8");
        }
    }

    private static void downloadToFile(@NonNull String rawUrl, @NonNull File target, @Nullable String curseForgeApiKey) throws IOException {
        String url = rawUrl.trim();
        if (isBlank(url)) throw new IOException("Missing download URL.");

        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create folder: " + parent.getAbsolutePath());
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "DroidBridgeLauncher");
        if (!isBlank(curseForgeApiKey)) connection.setRequestProperty("x-api-key", curseForgeApiKey.trim());

        int responseCode = connection.getResponseCode();
        InputStream rawInput = responseCode >= 200 && responseCode < 300 ? connection.getInputStream() : connection.getErrorStream();
        if (rawInput == null) {
            connection.disconnect();
            throw new IOException("HTTP " + responseCode);
        }

        try (InputStream input = rawInput; FileOutputStream output = new FileOutputStream(target)) {
            if (responseCode < 200 || responseCode >= 300) {
                ByteArrayOutputStream error = new ByteArrayOutputStream();
                copyStream(input, error);
                throw new IOException("HTTP " + responseCode + ": " + error.toString("UTF-8"));
            }
            copyStream(input, output);
        } finally {
            connection.disconnect();
        }
    }

    @NonNull
    private static String readNetworkText(@NonNull String urlString, @Nullable String curseForgeApiKey) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", "DroidBridgeLauncher");
        if (!isBlank(curseForgeApiKey)) connection.setRequestProperty("x-api-key", curseForgeApiKey.trim());

        int responseCode = connection.getResponseCode();
        InputStream input = responseCode >= 200 && responseCode < 300 ? connection.getInputStream() : connection.getErrorStream();
        if (input == null) {
            connection.disconnect();
            throw new IOException("HTTP " + responseCode);
        }

        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copyStream(stream, output);
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("HTTP " + responseCode + ": " + output.toString("UTF-8"));
            }
            return output.toString("UTF-8");
        } finally {
            connection.disconnect();
        }
    }

    private static void copyStream(@NonNull InputStream input, @NonNull OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    @NonNull
    private static String readTextFile(@NonNull File file) throws IOException {
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copyStream(input, output);
            return output.toString("UTF-8");
        }
    }

    private static void writeTextFile(@NonNull File file, @NonNull String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create folder: " + parent.getAbsolutePath());
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes("UTF-8"));
        }
    }

    @NonNull
    private static File resolveSafeChild(@NonNull File root, @NonNull String relativePath) throws IOException {
        String normalized = normalizeRelativePath(relativePath);
        if (isBlank(normalized)) throw new IOException("Blank relative path.");
        File child = new File(root, normalized.replace('/', File.separatorChar));
        String rootPath = root.getCanonicalPath();
        String childPath = child.getCanonicalPath();
        if (!childPath.equals(rootPath) && !childPath.startsWith(rootPath + File.separator)) {
            throw new SecurityException("Blocked unsafe path: " + relativePath);
        }
        return child;
    }

    @NonNull
    private static String safeCanonicalPath(@NonNull File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException ignored) {
            return file.getAbsolutePath();
        }
    }

    @NonNull
    private static String sanitizeFileName(@NonNull String rawName) {
        String name = rawName.trim().replace('\n', ' ').replace('\r', ' ');
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        while (name.contains("  ")) name = name.replace("  ", " ");
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) name = "file";
        return name;
    }

    @NonNull
    private static String normalizeRelativePath(@Nullable String rawPath) {
        if (rawPath == null) return "";
        String path = rawPath.replace('\\', '/').trim();
        while (path.startsWith("/")) path = path.substring(1);
        while (path.contains("//")) path = path.replace("//", "/");
        return path;
    }

    @NonNull
    private static String normalizeZipPrefix(@Nullable String rawPath) {
        String path = normalizeRelativePath(rawPath);
        if (isBlank(path)) return "";
        return path.endsWith("/") ? path : path + "/";
    }

    @NonNull
    private static String normalizeLoader(@Nullable String loader) {
        if (loader == null) return "";
        String value = loader.trim().toLowerCase(Locale.US);
        if (value.contains("cleanroom")) return "forge";
        if (value.contains("neoforge") || value.contains("neo forge")) return "neoforge";
        if (value.contains("forge")) return "forge";
        if (value.contains("fabric")) return "fabric";
        if (value.contains("quilt")) return "quilt";
        if (value.contains("vanilla")) return "vanilla";
        return value;
    }

    private static int curseForgeModLoaderType(@Nullable String loader) {
        String normalized = normalizeLoader(loader);
        if ("forge".equals(normalized)) return 1;
        if ("fabric".equals(normalized)) return 4;
        if ("quilt".equals(normalized)) return 5;
        if ("neoforge".equals(normalized)) return 6;
        return 0;
    }

    @Nullable
    private static Platform readPlatform(@NonNull JSONObject object) {
        String value = firstNonBlank(
                object.optString("source", ""),
                object.optString("platform", ""),
                object.optString("modpackPlatform", ""),
                object.optString("provider", "")
        ).toLowerCase(Locale.US);
        if (value.contains("modrinth") || value.contains("mrpack")) return Platform.MODRINTH;
        if (value.contains("curse") || value.contains("curseforge")) return Platform.CURSEFORGE;
        return null;
    }

    @NonNull
    private static String readString(@NonNull JSONObject object, @NonNull String... names) {
        for (String name : names) {
            String value = object.optString(name, "").trim();
            if (!value.isEmpty() && !"null".equalsIgnoreCase(value) && !"0".equals(value)) return value;
        }
        return "";
    }

    @Nullable
    private static JSONObject firstObject(@NonNull JSONObject object, @NonNull String... names) {
        for (String name : names) {
            JSONObject value = object.optJSONObject(name);
            if (value != null) return value;
        }
        return null;
    }

    @NonNull
    private static String firstNonBlank(@Nullable String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value == null) continue;
            String trimmed = value.trim();
            if (!trimmed.isEmpty() && !"null".equalsIgnoreCase(trimmed)) return trimmed;
        }
        return "";
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean jsonArrayContainsIgnoreCase(@Nullable JSONArray array, @Nullable String expected) {
        if (array == null || isBlank(expected)) return false;
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "");
            if (expected.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    @NonNull
    private static String normalizeTitle(@Nullable String value) {
        if (value == null) return "";
        String normalized = value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "").trim();
        return normalized;
    }

    @NonNull
    private static String escapeFacetValue(@NonNull String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @NonNull
    private static String readableError(@NonNull Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty() ? throwable.getClass().getSimpleName() : message;
    }
}

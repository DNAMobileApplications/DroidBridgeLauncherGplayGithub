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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public final class ModManagerManifest {
    private static final String MANIFEST_FILE = "modmanager_installed.json";
    private static final int SCHEMA_VERSION = 2;

    private ModManagerManifest() {
    }

    public static void removeKnownFilesForProject(
            @NonNull File gameDirectory,
            @NonNull ModManagerContentType type,
            @NonNull String source,
            @NonNull String projectId
    ) {
        removeKnownFilesForProject(gameDirectory, type, ModManagerSource.fromId(source), projectId);
    }

    public static void removeKnownFilesForProject(
            @NonNull File gameDirectory,
            @NonNull ModManagerContentType type,
            @NonNull ModManagerSource source,
            @NonNull String projectId
    ) {
        File manifestFile = getManifestFile(gameDirectory);
        JSONArray array = readArray(manifestFile);
        JSONArray rewritten = new JSONArray();

        for (int i = 0; i < array.length(); i++) {
            JSONObject entry = array.optJSONObject(i);
            if (entry == null) continue;

            boolean match = projectMatches(entry, projectId)
                    && sourceMatches(entry, source)
                    && contentTypeMatches(entry, type);

            if (match) {
                deleteIfFile(resolveEntryFile(gameDirectory, type, entry));
                deleteIfFile(resolveDisabledEntryFile(gameDirectory, type, entry));
                deleteIfFile(resolveEnabledEntryFile(gameDirectory, type, entry));
            } else {
                rewritten.put(entry);
            }
        }

        writeArray(manifestFile, rewritten);
    }

    public static void recordInstalled(
            @NonNull File gameDirectory,
            @NonNull ModManagerContentType type,
            @NonNull String source,
            @NonNull ModrinthProject project,
            @NonNull ModrinthVersion version,
            @NonNull ModrinthFile file,
            @NonNull File targetFile,
            boolean dependency
    ) {
        recordInstalled(gameDirectory, type, ModManagerSource.fromId(source), project, version, file, targetFile, dependency, "", "");
    }

    public static void recordInstalled(
            @NonNull File gameDirectory,
            @NonNull ModManagerContentType type,
            @NonNull ModManagerSource source,
            @NonNull ModrinthProject project,
            @NonNull ModrinthVersion version,
            @NonNull ModrinthFile file,
            @NonNull File targetFile,
            boolean dependency,
            @Nullable String minecraftVersion,
            @Nullable String loader
    ) {
        recordInstalled(gameDirectory, type, source, project, version, file, targetFile, dependency, minecraftVersion, loader, project.iconUrl, null);
    }

    public static void recordInstalled(
            @NonNull File gameDirectory,
            @NonNull ModManagerContentType type,
            @NonNull ModManagerSource source,
            @NonNull ModrinthProject project,
            @NonNull ModrinthVersion version,
            @NonNull ModrinthFile file,
            @NonNull File targetFile,
            boolean dependency,
            @Nullable String minecraftVersion,
            @Nullable String loader,
            @Nullable String iconUrl,
            @Nullable File cachedIconFile
    ) {
        File manifestFile = getManifestFile(gameDirectory);
        JSONArray array = readArray(manifestFile);
        JSONArray rewritten = new JSONArray();

        for (int i = 0; i < array.length(); i++) {
            JSONObject entry = array.optJSONObject(i);
            if (entry == null) continue;
            boolean same = projectMatches(entry, project.projectId)
                    && sourceMatches(entry, source)
                    && contentTypeMatches(entry, type);
            if (!same) rewritten.put(entry);
        }

        String timestamp = now();
        JSONObject entry = new JSONObject();
        try {
            entry.put("schema", SCHEMA_VERSION);
            entry.put("source", source.getId());
            entry.put("platform", source.getId());
            entry.put("platformName", source.getDisplayName());
            entry.put("contentType", type.name());
            entry.put("projectType", type.getModrinthProjectType());
            entry.put("minecraftVersion", minecraftVersion == null ? "" : minecraftVersion);
            entry.put("loader", loader == null ? "" : loader);
            entry.put("projectId", project.projectId);
            entry.put("platformProjectId", project.projectId);
            entry.put("slug", project.slug);
            entry.put("title", project.title);
            entry.put("versionId", version.id);
            entry.put("platformVersionId", version.id);
            entry.put("versionNumber", version.versionNumber);
            entry.put("fileName", targetFile.getName());
            entry.put("targetPath", targetFile.getAbsolutePath());
            entry.put("downloadUrl", file.url);
            entry.put("iconUrl", iconUrl == null ? "" : iconUrl);
            entry.put("cachedIconPath", cachedIconFile != null && cachedIconFile.isFile() ? cachedIconFile.getAbsolutePath() : "");
            entry.put("sha1", file.sha1 == null ? "" : file.sha1);
            entry.put("dependency", dependency);
            entry.put("installedAt", timestamp);
            entry.put("updatedAt", timestamp);
        } catch (Throwable ignored) {
        }
        rewritten.put(entry);
        writeArray(manifestFile, rewritten);
    }

    @NonNull
    public static ArrayList<JSONObject> getInstalledEntries(@NonNull File gameDirectory, @Nullable ModManagerContentType type) {
        pruneMissingFiles(gameDirectory, type);
        JSONArray array = readArray(getManifestFile(gameDirectory));
        ArrayList<JSONObject> entries = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject entry = array.optJSONObject(i);
            if (entry == null) continue;
            if (type == null || contentTypeMatches(entry, type)) entries.add(entry);
        }
        return entries;
    }

    /**
     * Removes stale metadata entries whose tracked file no longer exists.
     * This is intentionally safe: it only edits DroidBridge metadata;
     * it never deletes game content. Disabled files such as mod.jar.disabled still count
     * as installed and are kept.
     */
    public static int pruneMissingFiles(@NonNull File gameDirectory) {
        return pruneMissingFiles(gameDirectory, null);
    }

    /**
     * Removes stale metadata entries for one content type, or for all types when type is null.
     */
    public static int pruneMissingFiles(@NonNull File gameDirectory, @Nullable ModManagerContentType type) {
        File manifestFile = getManifestFile(gameDirectory);
        JSONArray array = readArray(manifestFile);
        if (array.length() == 0) return 0;

        JSONArray rewritten = new JSONArray();
        int removed = 0;

        for (int i = 0; i < array.length(); i++) {
            JSONObject entry = array.optJSONObject(i);
            if (entry == null) {
                removed++;
                continue;
            }

            ModManagerContentType entryType = contentTypeFromEntry(entry);
            if (type != null && entryType != type) {
                rewritten.put(entry);
                continue;
            }

            if (entryType == null || entryFileExists(gameDirectory, entryType, entry)) {
                rewritten.put(entry);
            } else {
                removed++;
            }
        }

        if (removed > 0) writeArray(manifestFile, rewritten);
        return removed;
    }

    @Nullable
    public static JSONObject getInstalledEntryForProject(
            @NonNull File gameDirectory,
            @NonNull ModManagerContentType type,
            @NonNull String source,
            @NonNull String projectId
    ) {
        return getInstalledEntryForProject(gameDirectory, type, ModManagerSource.fromId(source), projectId);
    }

    @Nullable
    public static JSONObject getInstalledEntryForProject(
            @NonNull File gameDirectory,
            @NonNull ModManagerContentType type,
            @NonNull ModManagerSource source,
            @NonNull String projectId
    ) {
        if (projectId.trim().isEmpty()) return null;

        pruneMissingFiles(gameDirectory, type);
        JSONArray array = readArray(getManifestFile(gameDirectory));
        for (int i = 0; i < array.length(); i++) {
            JSONObject entry = array.optJSONObject(i);
            if (entry == null) continue;

            boolean match = projectMatches(entry, projectId)
                    && sourceMatches(entry, source)
                    && contentTypeMatches(entry, type);

            if (match && entryFileExists(gameDirectory, type, entry)) return entry;
        }
        JSONArray modpackFiles = readModpackFilesArray(gameDirectory);
        for (int i = 0; i < modpackFiles.length(); i++) {
            JSONObject entry = modpackFiles.optJSONObject(i);
            if (entry == null) continue;
            boolean match = projectMatches(entry, projectId)
                    && sourceMatches(entry, source)
                    && contentTypeMatches(entry, type);
            if (match && entryFileExists(gameDirectory, type, entry)) return entry;
        }
        return null;
    }

    /**
     * Allows the modpack importer to mirror update-ready files into the normal
     * installed-content database. This keeps Browse/Install detection and
     * dependency checks from downloading a file that already came from a pack.
     */
    public static void recordInstalledEntry(
            @NonNull File gameDirectory,
            @NonNull ModManagerContentType type,
            @NonNull JSONObject incoming
    ) {
        File manifestFile = getManifestFile(gameDirectory);
        JSONArray array = readArray(manifestFile);
        JSONArray rewritten = new JSONArray();
        JSONObject entry;
        try {
            entry = new JSONObject(incoming.toString());
            entry.put("schema", SCHEMA_VERSION);
            entry.put("contentType", type.name());
            entry.put("projectType", type.getModrinthProjectType());
            if (entry.optString("targetPath", "").trim().isEmpty()) {
                String absolutePath = entry.optString("absolutePath", entry.optString("canonicalPath", "")).trim();
                if (!absolutePath.isEmpty()) entry.put("targetPath", absolutePath);
            }
            if (entry.optString("fileName", "").trim().isEmpty()) {
                File file = resolveEntryFile(gameDirectory, type, entry);
                if (file != null) entry.put("fileName", file.getName());
            }
            if (entry.optString("platformProjectId", "").trim().isEmpty()) {
                String projectId = getProjectId(entry);
                if (!projectId.isEmpty()) entry.put("platformProjectId", projectId);
            }
            if (entry.optString("platformVersionId", "").trim().isEmpty()) {
                String versionId = getVersionId(entry);
                if (!versionId.isEmpty()) entry.put("platformVersionId", versionId);
            }
            if (entry.optString("updatedAt", "").trim().isEmpty()) entry.put("updatedAt", now());
        } catch (Throwable ignored) {
            return;
        }

        String projectId = getProjectId(entry);
        ModManagerSource source = getSource(entry);
        String targetPath = safeCanonicalPath(resolveEntryFile(gameDirectory, type, entry) == null
                ? new File(entry.optString("targetPath", entry.optString("absolutePath", "")))
                : resolveEntryFile(gameDirectory, type, entry));
        String fileName = entry.optString("fileName", "").trim();

        for (int i = 0; i < array.length(); i++) {
            JSONObject existing = array.optJSONObject(i);
            if (existing == null) continue;
            boolean sameProject = !projectId.isEmpty()
                    && projectMatches(existing, projectId)
                    && sourceMatches(existing, source)
                    && contentTypeMatches(existing, type);
            File existingFile = resolveEntryFile(gameDirectory, type, existing);
            boolean sameFile = existingFile != null && targetPath.equals(safeCanonicalPath(existingFile));
            if (!sameProject && !(sameFile && contentTypeMatches(existing, type))) rewritten.put(existing);
        }
        rewritten.put(entry);
        writeArray(manifestFile, rewritten);
    }

    public static boolean isProjectInstalled(
            @NonNull File gameDirectory,
            @NonNull ModManagerContentType type,
            @NonNull String source,
            @NonNull String projectId
    ) {
        return getInstalledEntryForProject(gameDirectory, type, source, projectId) != null;
    }

    @Nullable
    public static JSONObject getInstalledEntryForFile(
            @NonNull File gameDirectory,
            @NonNull ModManagerContentType type,
            @NonNull File contentFile
    ) {
        pruneMissingFiles(gameDirectory, type);
        JSONArray array = readArray(getManifestFile(gameDirectory));
        String targetPath = safeCanonicalPath(contentFile);
        String targetName = contentFile.getName();
        String targetNameEnabled = stripDisabledSuffix(targetName);
        String targetNameDisabled = targetNameEnabled + ".disabled";

        for (int i = 0; i < array.length(); i++) {
            JSONObject entry = array.optJSONObject(i);
            if (entry == null) continue;
            if (!contentTypeMatches(entry, type)) continue;

            File file = resolveEntryFile(gameDirectory, type, entry);
            if (file != null) {
                String filePath = safeCanonicalPath(file);
                String disabledPath = safeCanonicalPath(new File(file.getAbsolutePath() + ".disabled"));
                String enabledPath = safeCanonicalPath(new File(file.getParentFile() == null ? type.getTargetDirectory(gameDirectory) : file.getParentFile(), stripDisabledSuffix(file.getName())));
                if (targetPath.equals(filePath) || targetPath.equals(disabledPath) || targetPath.equals(enabledPath)) return entry;
            }

            File disabledFile = resolveDisabledEntryFile(gameDirectory, type, entry);
            if (disabledFile != null && targetPath.equals(safeCanonicalPath(disabledFile))) return entry;

            String fileName = entry.optString("fileName", "");
            if (!fileName.trim().isEmpty()) {
                String entryEnabled = stripDisabledSuffix(fileName);
                String entryDisabled = entryEnabled + ".disabled";
                if (targetName.equals(fileName)
                        || targetName.equals(entryEnabled)
                        || targetName.equals(entryDisabled)
                        || targetNameEnabled.equals(entryEnabled)
                        || targetNameDisabled.equals(entryDisabled)) {
                    return entry;
                }
            }
        }
        return null;
    }


    @Nullable
    public static File getInstalledIconFileForFile(
            @NonNull File gameDirectory,
            @NonNull ModManagerContentType type,
            @NonNull File contentFile
    ) {
        JSONObject entry = getInstalledEntryForFile(gameDirectory, type, contentFile);
        if (entry == null) return null;

        String cachedIconPath = entry.optString("cachedIconPath", "");
        if (!cachedIconPath.trim().isEmpty()) {
            File cachedIcon = new File(cachedIconPath.trim());
            if (cachedIcon.isFile()) return cachedIcon;
        }

        return null;
    }

    @NonNull
    public static ModManagerSource getInstalledSourceForFile(
            @NonNull File gameDirectory,
            @NonNull ModManagerContentType type,
            @NonNull File contentFile
    ) {
        JSONObject entry = getInstalledEntryForFile(gameDirectory, type, contentFile);
        return entry == null ? ModManagerSource.UNKNOWN : getSource(entry);
    }

    /**
     * Removes the metadata entry for a file deleted through the launcher.
     * It matches both enabled and .disabled names.
     */
    public static void removeEntryForFile(
            @NonNull File gameDirectory,
            @NonNull ModManagerContentType type,
            @NonNull File contentFile
    ) {
        File manifestFile = getManifestFile(gameDirectory);
        JSONArray array = readArray(manifestFile);
        if (array.length() == 0) return;

        JSONArray rewritten = new JSONArray();
        boolean removed = false;
        String targetPath = safeCanonicalPath(contentFile);
        String targetName = contentFile.getName();
        String targetNameEnabled = stripDisabledSuffix(targetName);

        for (int i = 0; i < array.length(); i++) {
            JSONObject entry = array.optJSONObject(i);
            if (entry == null) {
                removed = true;
                continue;
            }
            if (!contentTypeMatches(entry, type)) {
                rewritten.put(entry);
                continue;
            }

            if (entryMatchesFile(gameDirectory, type, entry, targetPath, targetName, targetNameEnabled)) {
                removed = true;
            } else {
                rewritten.put(entry);
            }
        }

        if (removed) writeArray(manifestFile, rewritten);
    }

    /**
     * Keeps metadata accurate when a file is renamed, such as mod.jar <-> mod.jar.disabled.
     */
    public static void updateEntryFileTarget(
            @NonNull File gameDirectory,
            @NonNull ModManagerContentType type,
            @NonNull File oldFile,
            @NonNull File newFile
    ) {
        File manifestFile = getManifestFile(gameDirectory);
        JSONArray array = readArray(manifestFile);
        if (array.length() == 0) return;

        JSONArray rewritten = new JSONArray();
        boolean changed = false;
        String oldPath = safeCanonicalPath(oldFile);
        String oldName = oldFile.getName();
        String oldEnabledName = stripDisabledSuffix(oldName);
        String timestamp = now();

        for (int i = 0; i < array.length(); i++) {
            JSONObject entry = array.optJSONObject(i);
            if (entry == null) continue;

            if (contentTypeMatches(entry, type)
                    && entryMatchesFile(gameDirectory, type, entry, oldPath, oldName, oldEnabledName)) {
                try {
                    entry.put("fileName", newFile.getName());
                    entry.put("targetPath", newFile.getAbsolutePath());
                    entry.put("updatedAt", timestamp);
                } catch (Throwable ignored) {
                }
                changed = true;
            }
            rewritten.put(entry);
        }

        if (changed) writeArray(manifestFile, rewritten);
    }

    @NonNull
    private static String getProjectId(@NonNull JSONObject entry) {
        String projectId = entry.optString("platformProjectId", "").trim();
        if (projectId.isEmpty()) projectId = entry.optString("projectId", "").trim();
        if (projectId.isEmpty()) projectId = entry.optString("modrinthProjectId", "").trim();
        if (projectId.isEmpty()) projectId = entry.optString("modrinth_project_id", "").trim();
        if (projectId.isEmpty()) projectId = entry.optString("curseForgeProjectId", "").trim();
        if (projectId.isEmpty()) projectId = entry.optString("curseforgeProjectId", "").trim();
        if (projectId.isEmpty()) projectId = entry.optString("curseForgeProjectID", "").trim();
        if (projectId.isEmpty()) projectId = entry.optString("projectID", "").trim();
        return projectId;
    }

    @NonNull
    private static String getVersionId(@NonNull JSONObject entry) {
        String versionId = entry.optString("platformVersionId", "").trim();
        if (versionId.isEmpty()) versionId = entry.optString("versionId", "").trim();
        if (versionId.isEmpty()) versionId = entry.optString("modrinthVersionId", "").trim();
        if (versionId.isEmpty()) versionId = entry.optString("platformFileId", "").trim();
        if (versionId.isEmpty()) versionId = entry.optString("fileId", "").trim();
        if (versionId.isEmpty()) versionId = entry.optString("curseForgeFileId", "").trim();
        if (versionId.isEmpty()) versionId = entry.optString("curseforgeFileId", "").trim();
        return versionId;
    }

    private static boolean projectMatches(@NonNull JSONObject entry, @NonNull String projectId) {
        return !projectId.trim().isEmpty() && projectId.trim().equalsIgnoreCase(getProjectId(entry));
    }

    private static boolean contentTypeMatches(@NonNull JSONObject entry, @NonNull ModManagerContentType type) {
        String value = entry.optString("contentType", "");
        if (value.trim().isEmpty()) value = entry.optString("type", "");
        if (value.trim().isEmpty()) value = entry.optString("projectType", "");
        return ModManagerContentType.fromValue(value) == type;
    }

    @NonNull
    public static ModManagerSource getSource(@NonNull JSONObject entry) {
        String platform = entry.optString("platform", "");
        if (!platform.trim().isEmpty()) return ModManagerSource.fromId(platform);
        return ModManagerSource.fromId(entry.optString("source", ""));
    }

    @Nullable
    private static ModManagerContentType contentTypeFromEntry(@NonNull JSONObject entry) {
        String value = entry.optString("contentType", "");
        if (value.trim().isEmpty()) value = entry.optString("type", "");
        if (value.trim().isEmpty()) value = entry.optString("projectType", "");
        if (value.trim().isEmpty()) return null;
        return ModManagerContentType.fromValue(value);
    }

    private static boolean entryFileExists(
            @NonNull File gameDirectory,
            @NonNull ModManagerContentType type,
            @NonNull JSONObject entry
    ) {
        File file = resolveEntryFile(gameDirectory, type, entry);
        if (file != null && file.exists()) return true;

        File disabledFile = resolveDisabledEntryFile(gameDirectory, type, entry);
        if (disabledFile != null && disabledFile.exists()) return true;

        File enabledFile = resolveEnabledEntryFile(gameDirectory, type, entry);
        return enabledFile != null && enabledFile.exists();
    }

    private static boolean entryMatchesFile(
            @NonNull File gameDirectory,
            @NonNull ModManagerContentType type,
            @NonNull JSONObject entry,
            @NonNull String targetPath,
            @NonNull String targetName,
            @NonNull String targetNameEnabled
    ) {
        File file = resolveEntryFile(gameDirectory, type, entry);
        if (file != null) {
            String filePath = safeCanonicalPath(file);
            String fileName = file.getName();
            String fileNameEnabled = stripDisabledSuffix(fileName);
            File disabledFile = resolveDisabledEntryFile(gameDirectory, type, entry);
            File enabledFile = resolveEnabledEntryFile(gameDirectory, type, entry);

            if (targetPath.equals(filePath)) return true;
            if (disabledFile != null && targetPath.equals(safeCanonicalPath(disabledFile))) return true;
            if (enabledFile != null && targetPath.equals(safeCanonicalPath(enabledFile))) return true;
            if (targetName.equals(fileName)) return true;
            if (targetNameEnabled.equals(fileNameEnabled)) return true;
        }

        String fileName = entry.optString("fileName", "");
        if (!fileName.trim().isEmpty()) {
            String entryEnabled = stripDisabledSuffix(fileName);
            return targetName.equals(fileName)
                    || targetName.equals(entryEnabled)
                    || targetName.equals(entryEnabled + ".disabled")
                    || targetNameEnabled.equals(entryEnabled);
        }
        return false;
    }

    @Nullable
    private static File resolveEntryFile(@NonNull File gameDirectory, @NonNull ModManagerContentType type, @NonNull JSONObject entry) {
        String path = entry.optString("targetPath", "").trim();
        if (path.isEmpty()) path = entry.optString("absolutePath", "").trim();
        if (path.isEmpty()) path = entry.optString("canonicalPath", "").trim();
        if (!path.isEmpty()) return new File(path);

        String relativePath = entry.optString("relativePath", entry.optString("filePath", entry.optString("path", ""))).trim();
        if (!relativePath.isEmpty()) return new File(gameDirectory, relativePath.replace('/', File.separatorChar));

        String fileName = entry.optString("fileName", "");
        if (fileName.trim().isEmpty()) return null;
        return new File(type.getTargetDirectory(gameDirectory), fileName);
    }

    @Nullable
    private static File resolveDisabledEntryFile(@NonNull File gameDirectory, @NonNull ModManagerContentType type, @NonNull JSONObject entry) {
        File file = resolveEntryFile(gameDirectory, type, entry);
        if (file == null) return null;
        String name = file.getName();
        if (name.toLowerCase(Locale.US).endsWith(".disabled")) return file;
        return new File(file.getParentFile() == null ? type.getTargetDirectory(gameDirectory) : file.getParentFile(), name + ".disabled");
    }

    @Nullable
    private static File resolveEnabledEntryFile(@NonNull File gameDirectory, @NonNull ModManagerContentType type, @NonNull JSONObject entry) {
        File file = resolveEntryFile(gameDirectory, type, entry);
        if (file == null) return null;
        String name = file.getName();
        String enabledName = stripDisabledSuffix(name);
        return new File(file.getParentFile() == null ? type.getTargetDirectory(gameDirectory) : file.getParentFile(), enabledName);
    }

    private static boolean sourceMatches(@NonNull JSONObject entry, @NonNull ModManagerSource source) {
        ModManagerSource entrySource = getSource(entry);
        return entrySource == source || source.getId().equalsIgnoreCase(entry.optString("source", ""));
    }

    @NonNull
    private static File getManifestFile(@NonNull File gameDirectory) {
        return DroidBridgeMetadataPaths.fileForRead(gameDirectory, MANIFEST_FILE);
    }


    @NonNull
    private static File getWritableManifestFile(@NonNull File gameDirectory) {
        return DroidBridgeMetadataPaths.fileForWrite(gameDirectory, MANIFEST_FILE);
    }

    @NonNull
    private static JSONArray readModpackFilesArray(@NonNull File gameDirectory) {
        File file = DroidBridgeMetadataPaths.fileForRead(gameDirectory, DroidBridgeMetadataPaths.MODPACK_FILES_MANIFEST);
        if (!file.isFile()) return new JSONArray();
        try {
            JSONObject root = new JSONObject(readString(file));
            JSONArray files = root.optJSONArray("files");
            return files == null ? new JSONArray() : files;
        } catch (Throwable ignored) {
            return new JSONArray();
        }
    }

    @NonNull
    private static JSONArray readArray(@NonNull File file) {
        if (!file.isFile()) return new JSONArray();
        try {
            String raw = readString(file);
            return new JSONArray(raw == null || raw.trim().isEmpty() ? "[]" : raw);
        } catch (Throwable ignored) {
            return new JSONArray();
        }
    }

    private static void writeArray(@NonNull File file, @NonNull JSONArray array) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream output = new FileOutputStream(file, false)) {
                output.write(array.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void deleteIfFile(@Nullable File file) {
        if (file != null && file.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @NonNull
    private static String stripDisabledSuffix(@NonNull String name) {
        return name.toLowerCase(Locale.US).endsWith(".disabled")
                ? name.substring(0, name.length() - ".disabled".length())
                : name;
    }

    @NonNull
    private static String safeCanonicalPath(@NonNull File file) {
        try {
            return file.getCanonicalPath();
        } catch (Throwable ignored) {
            return file.getAbsolutePath();
        }
    }

    @NonNull
    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date());
    }

    @NonNull
    private static String readString(@NonNull File file) throws Exception {
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}

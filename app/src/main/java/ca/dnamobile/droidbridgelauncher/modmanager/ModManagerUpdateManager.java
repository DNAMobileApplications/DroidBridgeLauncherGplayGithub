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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/** Platform-aware update checks for installed Modrinth / CurseForge content. */
public final class ModManagerUpdateManager {
    private ModManagerUpdateManager() {}

    public interface Listener {
        void onStatus(@NonNull String message);
        void onProgress(int current, int total);
    }

    public static final class UpdateCandidate {
        @NonNull public final JSONObject entry;
        @NonNull public final ModManagerContentType contentType;
        @NonNull public final ModManagerSource source;
        @NonNull public final ModrinthProject project;
        @NonNull public final ModrinthVersion latestVersion;
        @NonNull public final String currentVersionId;
        @NonNull public final String currentVersionNumber;

        UpdateCandidate(@NonNull JSONObject entry, @NonNull ModManagerContentType contentType,
                        @NonNull ModManagerSource source, @NonNull ModrinthProject project,
                        @NonNull ModrinthVersion latestVersion, @NonNull String currentVersionId,
                        @NonNull String currentVersionNumber) {
            this.entry = entry;
            this.contentType = contentType;
            this.source = source;
            this.project = project;
            this.latestVersion = latestVersion;
            this.currentVersionId = currentVersionId;
            this.currentVersionNumber = currentVersionNumber;
        }

        @NonNull public String getDisplayName() {
            String title = entry.optString("title", "").trim();
            return title.isEmpty() ? project.title : title;
        }

        @NonNull public String getProjectId() {
            String id = ModManagerUpdateManager.getProjectId(entry);
            return id.isEmpty() ? project.projectId : id;
        }
    }

    @NonNull
    public static ArrayList<UpdateCandidate> checkUpdates(@NonNull Context context, @NonNull File gameDirectory,
            @NonNull ModManagerContentType contentType, @NonNull String minecraftVersion, @Nullable String loader,
            @Nullable Listener listener) throws Exception {
        ArrayList<JSONObject> entries = ModManagerManifest.getInstalledEntries(gameDirectory, contentType);
        ArrayList<UpdateCandidate> updates = new ArrayList<>();
        int total = entries.size();
        for (int i = 0; i < entries.size(); i++) {
            JSONObject entry = entries.get(i);
            if (listener != null) listener.onProgress(i + 1, Math.max(1, total));
            String title = entry.optString("title", entry.optString("fileName", "content"));
            if (listener != null) listener.onStatus("Checking " + title + "...");
            UpdateCandidate candidate = checkUpdateForEntry(context, gameDirectory, contentType, entry, minecraftVersion, loader);
            if (candidate != null) updates.add(candidate);
        }
        if (listener != null) listener.onStatus(updates.isEmpty() ? "No updates found." : updates.size() + " update(s) available.");
        return updates;
    }

    @Nullable
    public static UpdateCandidate checkUpdateForEntry(@NonNull Context context, @NonNull File gameDirectory,
            @NonNull ModManagerContentType contentType, @NonNull JSONObject entry,
            @NonNull String minecraftVersion, @Nullable String loader) throws Exception {
        ModManagerSource source = ModManagerManifest.getSource(entry);
        if (source != ModManagerSource.MODRINTH && source != ModManagerSource.CURSEFORGE) return null;

        String projectId = getProjectId(entry);
        if (projectId.isEmpty()) return null;

        File installedFile = resolveEntryFile(gameDirectory, contentType, entry, minecraftVersion);
        String currentVersionId = getVersionId(entry);
        String currentVersionNumber = firstNonBlank(
                entry.optString("versionNumber", ""),
                entry.optString("displayName", ""),
                entry.optString("name", "")
        );
        String currentSha1 = resolveCurrentSha1(entry, installedFile);
        String currentFileName = firstNonBlank(
                entry.optString("fileName", ""),
                entry.optString("filename", ""),
                installedFile == null ? "" : installedFile.getName()
        );
        long currentFileSize = firstPositiveLong(
                entry.optLong("fileSize", 0L),
                entry.optLong("declaredFileSize", 0L),
                entry.optLong("size", 0L),
                installedFile != null && installedFile.isFile() ? installedFile.length() : 0L
        );

        ModrinthProject project;
        ModrinthVersion latest = null;

        if (source == ModManagerSource.CURSEFORGE) {
            CurseForgeApiClient api = new CurseForgeApiClient(context);
            project = api.getProject(projectId);
            ArrayList<ModrinthVersion> versions = api.getProjectVersions(project.projectId, contentType, minecraftVersion, loader);
            if (versions.isEmpty()) return null;
            latest = versions.get(0);
        } else {
            ModrinthApiClient api = new ModrinthApiClient();
            project = api.getProjectWithFallback(projectId, entry.optString("slug", ""));

            if (!currentSha1.isEmpty()) {
                try {
                    latest = api.getLatestVersionFromFileHash(currentSha1, contentType, minecraftVersion, loader);
                    if (!latest.projectId.trim().isEmpty() && !latest.projectId.equals(project.projectId)) {
                        try {
                            project = api.getProject(latest.projectId);
                        } catch (Throwable ignored) {
                            // Keep the manifest project as the display fallback.
                        }
                    }
                } catch (Throwable throwable) {
                    // Fall back to project versions for older metadata, hash lookup misses,
                    // or categories where Modrinth cannot resolve the installed file.
                    latest = null;
                }
            }

            if (latest == null) {
                ArrayList<ModrinthVersion> versions = api.getProjectVersionsWithFallback(project, contentType, minecraftVersion, loader, false);
                if (versions.isEmpty()) return null;
                latest = versions.get(0);
            }
        }

        if (isSameInstalledVersion(
                currentVersionId,
                currentVersionNumber,
                currentSha1,
                currentFileName,
                currentFileSize,
                latest
        )) {
            return null;
        }

        return new UpdateCandidate(entry, contentType, source, project, latest, currentVersionId, currentVersionNumber);
    }

    private static boolean isSameInstalledVersion(
            @NonNull String currentVersionId,
            @NonNull String currentVersionNumber,
            @NonNull String currentSha1,
            @NonNull String currentFileName,
            long currentFileSize,
            @NonNull ModrinthVersion latest
    ) {
        ModrinthFile latestFile = latest.getPrimaryFile();
        String latestSha1 = latestFile != null && latestFile.sha1 != null
                ? latestFile.sha1.trim().toLowerCase(Locale.US)
                : "";
        String latestFileName = latestFile == null ? "" : latestFile.filename.trim();
        long latestFileSize = latestFile == null ? 0L : latestFile.size;

        if (!currentVersionId.isEmpty() && currentVersionId.equals(latest.id)) return true;
        if (!currentSha1.isEmpty() && !latestSha1.isEmpty() && currentSha1.equals(latestSha1)) return true;

        if (!currentFileName.isEmpty()
                && !latestFileName.isEmpty()
                && currentFileName.equalsIgnoreCase(latestFileName)
                && currentFileSize > 0L
                && latestFileSize > 0L
                && currentFileSize == latestFileSize) {
            return true;
        }

        // Older modpack metadata sometimes has no platform version/file id and no SHA-1.
        // In that case, matching the version label is safer than repeatedly showing a
        // false update for the exact same file.
        return currentVersionId.isEmpty()
                && currentSha1.isEmpty()
                && !currentVersionNumber.isEmpty()
                && currentVersionNumber.equalsIgnoreCase(latest.versionNumber.trim());
    }

    @NonNull
    private static String resolveCurrentSha1(@NonNull JSONObject entry, @Nullable File installedFile) {
        String sha1 = firstNonBlank(
                entry.optString("sha1", ""),
                readModrinthHash(entry.optJSONObject("hashes")),
                readCurseForgeSha1(entry.optJSONArray("hashes"))
        ).trim().toLowerCase(Locale.US);
        if (!sha1.isEmpty()) return sha1;

        if (installedFile != null && installedFile.isFile()) {
            try {
                return sha1(installedFile).toLowerCase(Locale.US);
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    @NonNull
    private static String readModrinthHash(@Nullable JSONObject hashes) {
        if (hashes == null) return "";
        return hashes.optString("sha1", "").trim();
    }

    @NonNull
    private static String readCurseForgeSha1(@Nullable JSONArray hashes) {
        if (hashes == null) return "";
        for (int i = 0; i < hashes.length(); i++) {
            JSONObject hash = hashes.optJSONObject(i);
            if (hash == null) continue;
            if (hash.optInt("algo", 0) == 1) {
                String value = hash.optString("value", "").trim();
                if (!value.isEmpty()) return value;
            }
        }
        return "";
    }

    @Nullable
    private static File resolveEntryFile(
            @NonNull File gameDirectory,
            @NonNull ModManagerContentType type,
            @NonNull JSONObject entry,
            @Nullable String minecraftVersion
    ) {
        String path = firstNonBlank(
                entry.optString("targetPath", ""),
                entry.optString("absolutePath", ""),
                entry.optString("canonicalPath", "")
        );
        if (!path.isEmpty()) {
            File file = new File(path);
            if (file.isFile()) return file;
            File disabled = new File(path + ".disabled");
            if (disabled.isFile()) return disabled;
        }

        String relativePath = firstNonBlank(
                entry.optString("relativePath", ""),
                entry.optString("filePath", ""),
                entry.optString("path", "")
        );
        if (!relativePath.isEmpty()) {
            File file = new File(gameDirectory, relativePath.replace('/', File.separatorChar));
            if (file.isFile()) return file;
            File disabled = new File(gameDirectory, relativePath.replace('/', File.separatorChar) + ".disabled");
            if (disabled.isFile()) return disabled;
        }

        String fileName = entry.optString("fileName", "").trim();
        if (!fileName.isEmpty()) {
            File targetDirectory = type.getTargetDirectory(gameDirectory, minecraftVersion);
            File file = new File(targetDirectory, fileName);
            if (file.isFile()) return file;
            File disabled = new File(targetDirectory, fileName + ".disabled");
            if (disabled.isFile()) return disabled;
            String enabledName = stripDisabledSuffix(fileName);
            File enabled = new File(targetDirectory, enabledName);
            if (enabled.isFile()) return enabled;
        }
        return null;
    }

    @NonNull
    private static String getProjectId(@NonNull JSONObject entry) {
        return firstNonBlank(
                entry.optString("platformProjectId", ""),
                entry.optString("projectId", ""),
                entry.optString("modrinthProjectId", ""),
                entry.optString("modrinth_project_id", ""),
                entry.optString("curseForgeProjectId", ""),
                entry.optString("curseforgeProjectId", ""),
                entry.optString("curseForgeProjectID", ""),
                entry.optString("projectID", "")
        );
    }

    @NonNull
    private static String getVersionId(@NonNull JSONObject entry) {
        return firstNonBlank(
                entry.optString("platformVersionId", ""),
                entry.optString("versionId", ""),
                entry.optString("modrinthVersionId", ""),
                entry.optString("platformFileId", ""),
                entry.optString("fileId", ""),
                entry.optString("curseForgeFileId", ""),
                entry.optString("curseforgeFileId", "")
        );
    }

    @NonNull
    private static String firstNonBlank(@Nullable String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value == null) continue;
            String clean = value.trim();
            if (!clean.isEmpty() && !"null".equalsIgnoreCase(clean)) return clean;
        }
        return "";
    }

    private static long firstPositiveLong(long... values) {
        if (values == null) return 0L;
        for (long value : values) if (value > 0L) return value;
        return 0L;
    }

    @NonNull
    private static String stripDisabledSuffix(@NonNull String name) {
        return name.toLowerCase(Locale.US).endsWith(".disabled")
                ? name.substring(0, name.length() - ".disabled".length())
                : name;
    }

    @NonNull
    private static String sha1(@NonNull File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        byte[] bytes = digest.digest();
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) builder.append(String.format(Locale.US, "%02x", b & 0xff));
        return builder.toString();
    }

    public static void updateCandidate(@NonNull Context context, @NonNull File gameDirectory, @NonNull String minecraftVersion,
            @Nullable String loader, @NonNull UpdateCandidate candidate, @NonNull ModrinthInstallManager.Listener listener) {
        File installedFile = resolveEntryFile(gameDirectory, candidate.contentType, candidate.entry, minecraftVersion);
        File targetDirectory = installedFile != null && installedFile.getParentFile() != null
                ? installedFile.getParentFile()
                : candidate.contentType.getTargetDirectory(gameDirectory, minecraftVersion);

        if (candidate.source == ModManagerSource.CURSEFORGE) {
            CurseForgeInstallManager.installSpecificVersion(new CurseForgeApiClient(context), gameDirectory, minecraftVersion,
                    loader, candidate.contentType, candidate.project, candidate.latestVersion, targetDirectory, listener);
        } else {
            ModrinthInstallManager.installSpecificVersion(gameDirectory, minecraftVersion, loader,
                    candidate.contentType, candidate.project, candidate.latestVersion, targetDirectory, listener);
        }
    }

    public static void updateAll(@NonNull Context context, @NonNull File gameDirectory, @NonNull String minecraftVersion,
            @Nullable String loader, @NonNull ArrayList<UpdateCandidate> updates, @Nullable Listener progressListener) throws Exception {
        for (int i = 0; i < updates.size(); i++) {
            UpdateCandidate candidate = updates.get(i);
            if (progressListener != null) {
                progressListener.onProgress(i + 1, Math.max(1, updates.size()));
                progressListener.onStatus("Updating " + candidate.getDisplayName() + "...");
            }
            AtomicReference<Throwable> error = new AtomicReference<>();
            updateCandidate(context, gameDirectory, minecraftVersion, loader, candidate, new ModrinthInstallManager.Listener() {
                @Override public void onStatus(@NonNull String message) { if (progressListener != null) progressListener.onStatus(message); }
                @Override public void onComplete(@NonNull String message) { if (progressListener != null) progressListener.onStatus(message); }
                @Override public void onError(@NonNull Throwable throwable) { error.set(throwable); }
            });
            if (error.get() != null) throw new IllegalStateException("Failed to update " + candidate.getDisplayName() + ": " +
                    (error.get().getMessage() != null ? error.get().getMessage() : error.get().getClass().getSimpleName()), error.get());
        }
    }
}

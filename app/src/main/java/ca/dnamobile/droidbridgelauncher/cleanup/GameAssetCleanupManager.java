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

package ca.dnamobile.droidbridgelauncher.cleanup;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

// Attempts to cleanup unused files or assets leftover
public final class GameAssetCleanupManager {
    private static final String TAG = "GameAssetCleanup";

    private GameAssetCleanupManager() {
    }

    @NonNull
    public static CleanupResult cleanUnusedAssets(@NonNull Context context) throws Exception {
        PathManager.initContextConstants(context.getApplicationContext());
        File minecraftHome = new File(PathManager.DIR_MINECRAFT_HOME);
        return cleanUnusedAssets(minecraftHome);
    }

    @NonNull
    static CleanupResult cleanUnusedAssets(@NonNull File minecraftHome) throws Exception {
        File assetsHome = new File(minecraftHome, "assets");
        File versionsHome = new File(minecraftHome, "versions");
        File indexesHome = new File(assetsHome, "indexes");

        CleanupResult result = new CleanupResult();

        if (!assetsHome.isDirectory()) {
            result.message = "No Minecraft assets folder exists yet.";
            return result;
        }

        Set<String> effectiveAssetIndexIds = collectEffectiveAssetIndexIds(versionsHome, result);
        if (effectiveAssetIndexIds.isEmpty()) {
            result.message = "No installed Minecraft asset indexes could be found.";
            return result;
        }

        Set<String> requiredTargets = new HashSet<>();
        for (String assetIndexId : effectiveAssetIndexIds) {
            File indexFile = new File(indexesHome, assetIndexId + ".json");
            if (!indexFile.isFile()) {
                result.missingAssetIndexCount++;
                Logging.i(TAG, "Skipping missing asset index during cleanup: " + indexFile.getAbsolutePath());
                continue;
            }
            collectRequiredAssetTargets(assetsHome, indexFile, requiredTargets, result);
        }

        result.requiredFileCount = requiredTargets.size();

        if (requiredTargets.isEmpty()) {
            result.message = "No readable asset index entries were found, so no files were deleted.";
            return result;
        }

        if (result.missingAssetIndexCount > 0) {
            result.message = "Cleanup was skipped because one or more installed versions is missing an asset index. This avoids deleting files that might still be needed.";
            return result;
        }

        scanAndDeleteRedundantFiles(new File(assetsHome, "objects"), requiredTargets, result);
        scanAndDeleteRedundantFiles(new File(assetsHome, "resources"), requiredTargets, result);
        scanAndDeleteRedundantFiles(new File(assetsHome, "virtual"), requiredTargets, result);

        deleteEmptyDirectories(new File(assetsHome, "objects"));
        deleteEmptyDirectories(new File(assetsHome, "resources"));
        deleteEmptyDirectories(new File(assetsHome, "virtual"));

        result.message = result.deletedFileCount > 0
                ? "Removed unused Minecraft asset files."
                : "No unnecessary Minecraft asset files were found.";
        return result;
    }

    @NonNull
    private static Set<String> collectEffectiveAssetIndexIds(
            @NonNull File versionsHome,
            @NonNull CleanupResult result
    ) throws Exception {
        Set<String> ids = new HashSet<>();
        if (!versionsHome.isDirectory()) return ids;

        File[] versionDirectories = versionsHome.listFiles(File::isDirectory);
        if (versionDirectories == null) return ids;

        for (File versionDirectory : versionDirectories) {
            File[] jsonFiles = versionDirectory.listFiles(file -> file.isFile()
                    && file.getName().toLowerCase(Locale.US).endsWith(".json"));
            if (jsonFiles == null || jsonFiles.length == 0) continue;

            for (File jsonFile : jsonFiles) {
                result.versionJsonCount++;
                try {
                    String assetIndexId = resolveEffectiveAssetIndexId(jsonFile, versionsHome, new HashSet<>());
                    if (!isBlank(assetIndexId)) ids.add(assetIndexId);
                    else result.skippedVersionCount++;
                } catch (Exception e) {
                    result.skippedVersionCount++;
                    Logging.i(TAG, "Skipping unreadable version json during cleanup: "
                            + jsonFile.getAbsolutePath() + " because " + e.getMessage());
                }
            }
        }
        return ids;
    }

    @Nullable
    private static String resolveEffectiveAssetIndexId(
            @NonNull File versionJsonFile,
            @NonNull File versionsHome,
            @NonNull Set<String> visitedVersionIds
    ) throws Exception {
        JSONObject versionJson = new JSONObject(readString(versionJsonFile));

        JSONObject assetIndex = versionJson.optJSONObject("assetIndex");
        if (assetIndex != null) {
            String id = assetIndex.optString("id", null);
            if (!isBlank(id)) return id;
        }

        String assets = versionJson.optString("assets", null);
        if (!isBlank(assets)) return assets;

        String inheritsFrom = versionJson.optString("inheritsFrom", null);
        if (isBlank(inheritsFrom) || !visitedVersionIds.add(inheritsFrom)) return null;

        File inheritedJson = new File(new File(versionsHome, inheritsFrom), inheritsFrom + ".json");
        if (!inheritedJson.isFile()) {
            // Match the prior implementation's safer behavior: missing inherited JSON should not crash cleanup.
            return null;
        }
        return resolveEffectiveAssetIndexId(inheritedJson, versionsHome, visitedVersionIds);
    }

    private static void collectRequiredAssetTargets(
            @NonNull File assetsHome,
            @NonNull File assetIndexFile,
            @NonNull Set<String> requiredTargets,
            @NonNull CleanupResult result
    ) throws Exception {
        JSONObject assetIndexJson = new JSONObject(readString(assetIndexFile));
        JSONObject objects = assetIndexJson.optJSONObject("objects");
        if (objects == null) return;

        boolean virtual = assetIndexJson.optBoolean("virtual", false);
        boolean mapToResources = assetIndexJson.optBoolean("map_to_resources", false);
        String indexId = stripJsonExtension(assetIndexFile.getName());
        JSONArray names = objects.names();
        if (names == null) return;

        result.assetIndexCount++;
        for (int i = 0; i < names.length(); i++) {
            String assetName = names.optString(i, "");
            JSONObject asset = objects.optJSONObject(assetName);
            if (asset == null) continue;

            String hash = asset.optString("hash", "");
            if (hash.length() < 2) continue;

            File target;
            if (virtual) {
                target = new File(assetsHome, "virtual" + File.separator + indexId + File.separator + assetName);
            } else if (mapToResources) {
                target = new File(assetsHome, "resources" + File.separator + assetName);
            } else {
                target = new File(assetsHome, "objects" + File.separator + hash.substring(0, 2) + File.separator + hash);
            }
            requiredTargets.add(pathKey(target));
        }
    }

    private static void scanAndDeleteRedundantFiles(
            @NonNull File directory,
            @NonNull Set<String> requiredTargets,
            @NonNull CleanupResult result
    ) {
        if (!directory.isDirectory()) return;

        File[] children = directory.listFiles();
        if (children == null) return;

        for (File child : children) {
            if (child.isDirectory()) {
                scanAndDeleteRedundantFiles(child, requiredTargets, result);
                continue;
            }

            result.scannedFileCount++;
            if (requiredTargets.contains(pathKey(child))) {
                result.keptFileCount++;
                continue;
            }

            long size = child.length();
            if (child.delete()) {
                result.deletedFileCount++;
                result.deletedByteCount += size;
            } else {
                result.failedDeleteCount++;
                result.failedFiles.add(child.getAbsolutePath());
            }
        }
    }

    private static boolean deleteEmptyDirectories(@NonNull File directory) {
        if (!directory.isDirectory()) return false;

        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) deleteEmptyDirectories(child);
            }
        }

        File[] remaining = directory.listFiles();
        if (remaining != null && remaining.length == 0) {
            //noinspection ResultOfMethodCallIgnored
            return directory.delete();
        }
        return false;
    }

    @NonNull
    private static String pathKey(@NonNull File file) {
        try {
            return file.getCanonicalPath();
        } catch (Exception ignored) {
            return file.getAbsolutePath();
        }
    }

    @NonNull
    private static String readString(@NonNull File file) throws Exception {
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    @NonNull
    private static String stripJsonExtension(@NonNull String name) {
        return name.toLowerCase(Locale.US).endsWith(".json")
                ? name.substring(0, name.length() - 5)
                : name;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    @NonNull
    public static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double value = bytes;
        String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int unitIndex = 0;
        while (value >= 1024D && unitIndex < units.length - 1) {
            value /= 1024D;
            unitIndex++;
        }
        return String.format(Locale.US, "%.1f %s", value, units[unitIndex]);
    }

    public static final class CleanupResult {
        private String message = "Cleanup finished.";
        private int versionJsonCount;
        private int skippedVersionCount;
        private int assetIndexCount;
        private int missingAssetIndexCount;
        private int requiredFileCount;
        private int scannedFileCount;
        private int keptFileCount;
        private int deletedFileCount;
        private int failedDeleteCount;
        private long deletedByteCount;
        private final ArrayList<String> failedFiles = new ArrayList<>();

        private CleanupResult() {
        }

        @NonNull
        public String getMessage() {
            return message;
        }

        public int getVersionJsonCount() {
            return versionJsonCount;
        }

        public int getSkippedVersionCount() {
            return skippedVersionCount;
        }

        public int getAssetIndexCount() {
            return assetIndexCount;
        }

        public int getMissingAssetIndexCount() {
            return missingAssetIndexCount;
        }

        public int getRequiredFileCount() {
            return requiredFileCount;
        }

        public int getScannedFileCount() {
            return scannedFileCount;
        }

        public int getKeptFileCount() {
            return keptFileCount;
        }

        public int getDeletedFileCount() {
            return deletedFileCount;
        }

        public int getFailedDeleteCount() {
            return failedDeleteCount;
        }

        public long getDeletedByteCount() {
            return deletedByteCount;
        }

        @NonNull
        public List<String> getFailedFiles() {
            return new ArrayList<>(failedFiles);
        }
    }
}

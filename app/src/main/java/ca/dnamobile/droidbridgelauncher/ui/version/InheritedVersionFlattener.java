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

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;
public final class InheritedVersionFlattener {
    private static final String TAG = "InheritedFlattener";
    private static final String METADATA_DIR_NAME = "DroidBridge";
    private static final String PARENT_MARKER_FILE_NAME = "flattened_parent.txt";
    private static final String JSON_PARENT_FIELD = "javaLauncherFlattenedParent";
    private static final String JSON_FLATTENED_FIELD = "javaLauncherFlattened";

    private InheritedVersionFlattener() {
    }

    public static final class FlattenResult {
        public final boolean flattened;
        @Nullable
        public final String parentVersionId;
        public final boolean copiedClientJar;

        private FlattenResult(boolean flattened, @Nullable String parentVersionId, boolean copiedClientJar) {
            this.flattened = flattened;
            this.parentVersionId = parentVersionId;
            this.copiedClientJar = copiedClientJar;
        }
    }

    public static final class ParentDeleteResult {
        public final boolean deleted;
        @Nullable
        public final String parentVersionId;
        @NonNull
        public final String message;

        private ParentDeleteResult(boolean deleted, @Nullable String parentVersionId, @NonNull String message) {
            this.deleted = deleted;
            this.parentVersionId = parentVersionId;
            this.message = message;
        }
    }

    @NonNull
    public static FlattenResult flattenInstalledVersionProfile(
            @NonNull Context context,
            @NonNull String versionId
    ) throws Exception {
        ensureActivePathManager(context);

        File versionsRoot = getVersionsRoot();
        File childDir = new File(versionsRoot, versionId).getCanonicalFile();
        File childJsonFile = new File(childDir, versionId + ".json");

        if (!childJsonFile.isFile()) {
            throw new IllegalStateException("Child version JSON not found: " + childJsonFile.getAbsolutePath());
        }

        JSONObject childJson = new JSONObject(readString(childJsonFile));
        String parentId = childJson.optString("inheritsFrom", "").trim();
        if (parentId.isEmpty()) {
            return new FlattenResult(false, readFlattenedParentId(context, versionId), false);
        }

        if (versionId.equals(parentId)) {
            throw new IllegalStateException("Refusing to flatten a version that inherits from itself: " + versionId);
        }

        File parentDir = new File(versionsRoot, parentId).getCanonicalFile();
        File parentJsonFile = new File(parentDir, parentId + ".json");
        File parentJarFile = new File(parentDir, parentId + ".jar");

        assertChildOf(versionsRoot.getCanonicalFile(), childDir, "child version");
        assertChildOf(versionsRoot.getCanonicalFile(), parentDir, "parent version");

        if (!parentJsonFile.isFile()) {
            throw new IllegalStateException("Parent version JSON not found: " + parentJsonFile.getAbsolutePath());
        }
        if (!parentJarFile.isFile()) {
            throw new IllegalStateException("Parent client jar not found: " + parentJarFile.getAbsolutePath());
        }

        JSONObject parentJson = new JSONObject(readString(parentJsonFile));
        JSONObject mergedJson = mergeVersionJson(parentJson, childJson, versionId, parentId);

        File childJarFile = new File(childDir, versionId + ".jar");
        boolean copiedClientJar = false;
        if (!childJarFile.isFile()) {
            copyFile(parentJarFile, childJarFile);
            copiedClientJar = true;
        }

        writeString(childJsonFile, mergedJson.toString(2));
        writeParentMarker(childDir, parentId);

        Logging.i(TAG, "Flattened " + versionId + " inherited from " + parentId
                + ", copiedClientJar=" + copiedClientJar);
        return new FlattenResult(true, parentId, copiedClientJar);
    }

    @NonNull
    public static ParentDeleteResult deleteFlattenedParentVersionIfSafe(
            @NonNull Context context,
            @NonNull String versionId
    ) throws Exception {
        ensureActivePathManager(context);

        String parentId = readFlattenedParentId(context, versionId);
        if (parentId == null || parentId.trim().isEmpty()) {
            return new ParentDeleteResult(false, null, "No flattened parent marker found.");
        }
        parentId = parentId.trim();

        File versionsRoot = getVersionsRoot().getCanonicalFile();
        File childDir = new File(versionsRoot, versionId).getCanonicalFile();
        File childJsonFile = new File(childDir, versionId + ".json");
        if (!childJsonFile.isFile()) {
            return new ParentDeleteResult(false, parentId, "Flattened child JSON is missing.");
        }

        JSONObject childJson = new JSONObject(readString(childJsonFile));
        if (!childJson.optString("inheritsFrom", "").trim().isEmpty()) {
            return new ParentDeleteResult(false, parentId, "Child still inherits from parent.");
        }

        File parentDir = new File(versionsRoot, parentId).getCanonicalFile();
        if (versionId.equals(parentId) || childDir.equals(parentDir)) {
            return new ParentDeleteResult(false, parentId, "Refusing to delete the active child version.");
        }
        if (!parentDir.isDirectory()) {
            deleteParentMarker(childDir);
            return new ParentDeleteResult(false, parentId, "Parent version is already gone.");
        }

        assertChildOf(versionsRoot, parentDir, "parent version");

        File parentJsonFile = new File(parentDir, parentId + ".json");
        if (parentJsonFile.isFile()) {
            JSONObject parentJson = new JSONObject(readString(parentJsonFile));
            if (!parentJson.optString("inheritsFrom", "").trim().isEmpty()) {
                return new ParentDeleteResult(false, parentId, "Parent is not a standalone vanilla profile.");
            }
        }

        HashSet<String> dependents = findDirectInheritors(parentId, versionId);
        if (!dependents.isEmpty()) {
            return new ParentDeleteResult(
                    false,
                    parentId,
                    "Parent kept because other installed versions still inherit from it: " + dependents
            );
        }

        deleteDirectory(parentDir);
        deleteParentMarker(childDir);

        Logging.i(TAG, "Deleted flattened parent version " + parentId + " after flattening " + versionId);
        return new ParentDeleteResult(true, parentId, "Deleted flattened parent " + parentId + ".");
    }

    @Nullable
    public static String readFlattenedParentId(@NonNull Context context, @NonNull String versionId) {
        try {
            ensureActivePathManager(context);
            File versionDir = new File(getVersionsRoot(), versionId);
            File markerFile = new File(new File(versionDir, METADATA_DIR_NAME), PARENT_MARKER_FILE_NAME);
            if (markerFile.isFile()) {
                String marker = readString(markerFile).trim();
                if (!marker.isEmpty()) return marker;
            }

            File jsonFile = new File(versionDir, versionId + ".json");
            if (jsonFile.isFile()) {
                JSONObject json = new JSONObject(readString(jsonFile));
                String parent = json.optString(JSON_PARENT_FIELD, "").trim();
                if (!parent.isEmpty()) return parent;
            }
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to read flattened parent marker for " + versionId + ": " + throwable.getMessage());
        }
        return null;
    }
    private static void ensureActivePathManager(@NonNull Context context) {
        if (PathManager.DIR_MINECRAFT_HOME == null || PathManager.DIR_MINECRAFT_HOME.trim().isEmpty()) {
            PathManager.initContextConstants(context);
        }
    }

    @NonNull
    private static JSONObject mergeVersionJson(
            @NonNull JSONObject parentJson,
            @NonNull JSONObject childJson,
            @NonNull String childVersionId,
            @NonNull String parentVersionId
    ) throws Exception {
        JSONObject merged = new JSONObject(parentJson.toString());

        JSONArray childKeys = childJson.names();
        if (childKeys != null) {
            for (int i = 0; i < childKeys.length(); i++) {
                String key = childKeys.getString(i);
                if ("libraries".equals(key) || "arguments".equals(key) || "inheritsFrom".equals(key)) {
                    continue;
                }
                merged.put(key, childJson.get(key));
            }
        }

        merged.put("id", childVersionId);
        merged.remove("inheritsFrom");
        merged.put(JSON_FLATTENED_FIELD, true);
        merged.put(JSON_PARENT_FIELD, parentVersionId);
        merged.put("libraries", mergeLibraries(parentJson.optJSONArray("libraries"), childJson.optJSONArray("libraries")));
        merged.put("arguments", mergeArguments(parentJson.optJSONObject("arguments"), childJson.optJSONObject("arguments")));

        if (childJson.has("minecraftArguments")) {
            merged.put("minecraftArguments", childJson.optString("minecraftArguments", ""));
        } else if (parentJson.has("minecraftArguments")) {
            merged.put("minecraftArguments", parentJson.optString("minecraftArguments", ""));
        }

        return merged;
    }

    @NonNull
    private static JSONArray mergeLibraries(@Nullable JSONArray parent, @Nullable JSONArray child) throws Exception {
        LinkedHashMap<String, JSONObject> merged = new LinkedHashMap<>();

        if (parent != null) {
            for (int i = 0; i < parent.length(); i++) {
                JSONObject library = parent.optJSONObject(i);
                if (library == null) continue;
                merged.put(libraryMergeKey(library), new JSONObject(library.toString()));
            }
        }

        if (child != null) {
            for (int i = 0; i < child.length(); i++) {
                JSONObject library = child.optJSONObject(i);
                if (library == null) continue;
                merged.put(libraryMergeKey(library), new JSONObject(library.toString()));
            }
        }

        JSONArray result = new JSONArray();
        for (Map.Entry<String, JSONObject> entry : merged.entrySet()) {
            result.put(entry.getValue());
        }
        return result;
    }

    @NonNull
    private static String libraryMergeKey(@NonNull JSONObject library) {
        String name = library.optString("name", "");
        if (name.isEmpty()) return "missing-name-" + library.toString().hashCode();

        String[] parts = name.split(":");
        if (parts.length < 3) return name;

        String group = parts[0];
        String artifact = parts[1];
        String classifier = parts.length > 3 ? parts[3] : "";
        return group + ":" + artifact + ":" + classifier;
    }

    @NonNull
    private static JSONObject mergeArguments(@Nullable JSONObject parent, @Nullable JSONObject child) throws Exception {
        if (parent == null && child == null) return new JSONObject();
        if (parent == null) return new JSONObject(child.toString());
        if (child == null) return new JSONObject(parent.toString());

        JSONObject merged = new JSONObject(parent.toString());
        JSONArray childKeys = child.names();
        if (childKeys != null) {
            for (int i = 0; i < childKeys.length(); i++) {
                String key = childKeys.getString(i);
                if ("game".equals(key) || "jvm".equals(key)) {
                    merged.put(key, mergeArrays(parent.optJSONArray(key), child.optJSONArray(key)));
                } else {
                    merged.put(key, child.get(key));
                }
            }
        }
        return merged;
    }

    @NonNull
    private static JSONArray mergeArrays(@Nullable JSONArray parent, @Nullable JSONArray child) throws Exception {
        JSONArray merged = new JSONArray();
        if (parent != null) {
            for (int i = 0; i < parent.length(); i++) merged.put(parent.get(i));
        }
        if (child != null) {
            for (int i = 0; i < child.length(); i++) merged.put(child.get(i));
        }
        return merged;
    }

    @NonNull
    private static HashSet<String> findDirectInheritors(@NonNull String parentVersionId, @NonNull String allowedFlattenedChildId) {
        HashSet<String> dependents = new HashSet<>();
        File[] children = getVersionsRoot().listFiles();
        if (children == null) return dependents;

        for (File childDir : children) {
            if (!childDir.isDirectory()) continue;
            String childVersionId = childDir.getName();
            if (parentVersionId.equals(childVersionId) || allowedFlattenedChildId.equals(childVersionId)) continue;

            File childJsonFile = new File(childDir, childVersionId + ".json");
            if (!childJsonFile.isFile()) continue;

            try {
                JSONObject json = new JSONObject(readString(childJsonFile));
                if (parentVersionId.equals(json.optString("inheritsFrom", "").trim())) {
                    dependents.add(json.optString("id", childVersionId));
                }
            } catch (Throwable throwable) {
                Logging.i(TAG, "Unable to inspect version inherit link for "
                        + childVersionId + ": " + throwable.getMessage());
            }
        }

        return dependents;
    }

    private static void writeParentMarker(@NonNull File childDir, @NonNull String parentId) throws Exception {
        File metadataDir = new File(childDir, METADATA_DIR_NAME);
        ensureDirectory(metadataDir);
        writeString(new File(metadataDir, PARENT_MARKER_FILE_NAME), parentId + "\n");
    }

    private static void deleteParentMarker(@NonNull File childDir) {
        try {
            File markerFile = new File(new File(childDir, METADATA_DIR_NAME), PARENT_MARKER_FILE_NAME);
            if (markerFile.exists() && !markerFile.delete()) {
                Logging.i(TAG, "Unable to delete parent marker: " + markerFile.getAbsolutePath());
            }
        } catch (Throwable ignored) {
        }
    }

    @NonNull
    private static File getVersionsRoot() {
        return new File(PathManager.DIR_MINECRAFT_HOME, "versions");
    }

    private static void ensureDirectory(@NonNull File directory) throws Exception {
        if (directory.exists()) {
            if (!directory.isDirectory()) {
                throw new IllegalStateException("Path exists but is not a directory: " + directory.getAbsolutePath());
            }
            return;
        }
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("Unable to create directory: " + directory.getAbsolutePath());
        }
    }

    private static void assertChildOf(@NonNull File parent, @NonNull File child, @NonNull String label) throws Exception {
        File safeParent = parent.getCanonicalFile();
        File safeChild = child.getCanonicalFile();
        if (!safeChild.getAbsolutePath().startsWith(safeParent.getAbsolutePath() + File.separator)) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "Refusing unsafe %s path outside versions root: %s", label, safeChild.getAbsolutePath()));
        }
    }

    private static void deleteDirectory(@NonNull File directory) throws Exception {
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) deleteDirectory(child);
                else if (child.exists() && !child.delete()) {
                    throw new IllegalStateException("Unable to delete file: " + child.getAbsolutePath());
                }
            }
        }
        if (directory.exists() && !directory.delete()) {
            throw new IllegalStateException("Unable to delete directory: " + directory.getAbsolutePath());
        }
    }

    private static void copyFile(@NonNull File source, @NonNull File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null) ensureDirectory(parent);

        File temp = new File(target.getAbsolutePath() + ".part");
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }

        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("Unable to replace file: " + target.getAbsolutePath());
        }
        if (!temp.renameTo(target)) {
            try (FileInputStream input = new FileInputStream(temp);
                 FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    @NonNull
    private static String readString(@NonNull File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void writeString(@NonNull File file, @NonNull String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null) ensureDirectory(parent);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}

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

package ca.dnamobile.droidbridgelauncher.storage;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

/**
 * Minimal SAF mirror used for Google Play compliant scoped storage.
 *
 * Minecraft/Forge/NeoForge installers and the JVM need java.io.File paths, so the
 * launcher runs from an app-private mirror and syncs that mirror to the user-picked
 * SAF tree with ContentResolver/DocumentsContract.
 */
public final class SafMinecraftMirror {
    private static final String TAG = "SafMinecraftMirror";
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String SYNC_STATE_FILE = ".java_launcher_saf_sync_state.json";

    private SafMinecraftMirror() {
    }

    public interface Progress {
        void onProgress(int progress, @NonNull String message);
    }

    public static void copyLocalLauncherHomeToTree(
            @NonNull Context context,
            @NonNull File launcherHome,
            @NonNull Uri treeUri,
            @Nullable Progress progress
    ) throws Exception {
        if (!launcherHome.isDirectory()) return;

        Uri rootDocument = getRootDocumentUri(treeUri);
        if (rootDocument == null) {
            throw new IllegalStateException("Unable to resolve scoped storage root.");
        }

        if (progress != null) progress.onProgress(98, "Syncing scoped storage...");
        SyncState syncState = SyncState.load(new File(launcherHome, SYNC_STATE_FILE));
        copyLocalDirectoryToDocument(context, launcherHome, rootDocument, progress, launcherHome, syncState);
        syncState.save();
    }

    public static void copyTreeToLocalLauncherHome(
            @NonNull Context context,
            @NonNull Uri treeUri,
            @NonNull File launcherHome,
            @Nullable Progress progress
    ) throws Exception {
        Uri rootDocument = getRootDocumentUri(treeUri);
        if (rootDocument == null) return;

        if (!launcherHome.exists() && !launcherHome.mkdirs()) {
            throw new IllegalStateException("Unable to create scoped storage mirror: " + launcherHome.getAbsolutePath());
        }

        if (progress != null) progress.onProgress(2, "Reading scoped storage mirror...");
        copyDocumentDirectoryToLocal(context, rootDocument, launcherHome, progress, "");
    }

    /**
     * Fast metadata-only restore for the launcher UI. This intentionally avoids
     * copying Minecraft assets, libraries, saves, mods, resourcepacks, shaderpacks,
     * logs, screenshots, and jars. It is used only so the instance adapter can be
     * populated quickly after the user picks a scoped-storage folder.
     */
    public static void copyTreeMetadataToLocalLauncherHome(
            @NonNull Context context,
            @NonNull Uri treeUri,
            @NonNull File launcherHome,
            @Nullable Progress progress
    ) throws Exception {
        Uri rootDocument = getRootDocumentUri(treeUri);
        if (rootDocument == null) return;

        if (!launcherHome.exists() && !launcherHome.mkdirs()) {
            throw new IllegalStateException("Unable to create scoped storage metadata mirror: " + launcherHome.getAbsolutePath());
        }

        if (progress != null) progress.onProgress(2, "Reading launcher metadata...");
        copyDocumentMetadataToLocal(context, rootDocument, launcherHome, progress, "");
    }

    /**
     * Restores one relative path from the scoped-storage tree. Use this when the
     * user opens an instance details screen and only that instance folder is needed.
     */
    public static void copyRelativePathToLocalLauncherHome(
            @NonNull Context context,
            @NonNull Uri treeUri,
            @NonNull File launcherHome,
            @NonNull String relativePath,
            @Nullable Progress progress
    ) throws Exception {
        Uri rootDocument = getRootDocumentUri(treeUri);
        if (rootDocument == null) return;

        String cleaned = cleanRelativePath(relativePath);
        if (cleaned.isEmpty()) {
            copyDocumentDirectoryToLocal(context, rootDocument, launcherHome, progress, "");
            return;
        }

        Uri source = findDescendant(context, rootDocument, cleaned);
        if (source == null) {
            Logging.i(TAG, "Scoped relative restore target not found: " + cleaned);
            return;
        }

        File target = new File(launcherHome, cleaned.replace('/', File.separatorChar));
        if (isDirectory(context, source)) {
            copyDocumentDirectoryToLocal(context, source, target, progress, cleaned);
        } else {
            if (progress != null) progress.onProgress(3, "Reading scoped storage: " + cleaned);
            copyDocumentFileToLocal(context, source, target, queryDocumentSize(context, source), cleaned);
        }
    }

    /**
     * Resolves a path inside a previously granted SAF tree by walking child
     * documents instead of guessing provider-specific document IDs.
     *
     * This is used by Open Instance Folder so a local launcher File path can be
     * mapped back to the exact user-picked storage folder, including mirror-backed
     * scoped storage.
     */
    @Nullable
    public static Uri findRelativePathInTree(
            @NonNull Context context,
            @NonNull Uri treeUri,
            @Nullable String relativePath
    ) {
        try {
            Uri rootDocument = getRootDocumentUri(treeUri);
            if (rootDocument == null) return null;
            String cleaned = relativePath == null ? "" : cleanRelativePath(relativePath);
            return findDescendant(context, rootDocument, cleaned);
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to resolve SAF relative path " + relativePath + ": " + throwable.getMessage());
            return null;
        }
    }

    @Nullable
    private static Uri getRootDocumentUri(@NonNull Uri treeUri) {
        try {
            String rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId);
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to build root document URI: " + throwable.getMessage());
            return null;
        }
    }


    /**
     * Deletes a file or directory from the picked SAF tree using a path relative
     * to the launcher home mirror.
     *
     * Example relative paths:
     * - .minecraft/instances/my-instance
     * - .minecraft/versions/1.21.1-forge
     */
    public static boolean deleteRelativePathFromTree(
            @NonNull Context context,
            @NonNull Uri treeUri,
            @NonNull String relativePath
    ) throws Exception {
        Uri rootDocument = getRootDocumentUri(treeUri);
        if (rootDocument == null) {
            throw new IllegalStateException("Unable to resolve scoped storage root.");
        }

        Uri target = findDescendant(context, rootDocument, relativePath);
        if (target == null) {
            Logging.i(TAG, "Scoped delete target not found: " + relativePath);
            return false;
        }

        String cleaned = cleanRelativePath(relativePath);
        if (deleteDocumentFast(context, target)) {
            Logging.i(TAG, "Deleted scoped storage path: " + cleaned);
            return true;
        }

        deleteDocumentRecursively(context, target);
        Logging.i(TAG, "Deleted scoped storage path recursively: " + cleaned);
        return true;
    }

    private static boolean deleteDocumentFast(
            @NonNull Context context,
            @NonNull Uri documentUri
    ) {
        try {
            return DocumentsContract.deleteDocument(context.getContentResolver(), documentUri);
        } catch (Throwable throwable) {
            Logging.i(TAG, "Fast SAF delete failed, falling back to recursive delete: " + throwable.getMessage());
            return false;
        }
    }

    @Nullable
    private static Uri findDescendant(
            @NonNull Context context,
            @NonNull Uri rootDirectory,
            @NonNull String relativePath
    ) throws Exception {
        String cleaned = relativePath.replace('\\', '/').trim();
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        while (cleaned.endsWith("/")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        if (cleaned.isEmpty()) return rootDirectory;

        Uri current = rootDirectory;
        String[] parts = cleaned.split("/");
        for (String rawPart : parts) {
            String part = rawPart == null ? "" : rawPart.trim();
            if (part.isEmpty() || ".".equals(part)) continue;
            Uri next = findChildAny(context, current, part);
            if (next == null) return null;
            current = next;
        }
        return current;
    }

    @Nullable
    private static Uri findChildAny(
            @NonNull Context context,
            @NonNull Uri parentDirectory,
            @NonNull String name
    ) {
        try {
            for (DocumentEntry entry : listChildren(context, parentDirectory)) {
                if (name.equals(entry.displayName)) return entry.uri;
            }
        } catch (Throwable throwable) {
            Logging.i(TAG, "findChildAny failed: " + throwable.getMessage());
        }
        return null;
    }

    private static void deleteDocumentRecursively(
            @NonNull Context context,
            @NonNull Uri documentUri
    ) throws Exception {
        boolean directory = isDirectory(context, documentUri);
        if (directory) {
            for (DocumentEntry child : listChildren(context, documentUri)) {
                deleteDocumentRecursively(context, child.uri);
            }
        }

        boolean deleted = DocumentsContract.deleteDocument(context.getContentResolver(), documentUri);
        if (!deleted) {
            throw new IllegalStateException("Unable to delete SAF document: " + documentUri);
        }
    }

    private static boolean isDirectory(@NonNull Context context, @NonNull Uri documentUri) {
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(
                documentUri,
                new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE},
                null,
                null,
                null
        )) {
            if (cursor == null || !cursor.moveToFirst()) return false;
            String mimeType = cursor.getString(0);
            return DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType);
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to inspect SAF document type: " + throwable.getMessage());
            return false;
        }
    }

    private static void copyLocalDirectoryToDocument(
            @NonNull Context context,
            @NonNull File localDirectory,
            @NonNull Uri documentDirectory,
            @Nullable Progress progress,
            @NonNull File rootForDisplay,
            @NonNull SyncState syncState
    ) throws Exception {
        File[] children = localDirectory.listFiles();
        if (children == null) return;

        HashMap<String, Uri> existingDirectories = new HashMap<>();
        HashMap<String, Uri> existingFiles = new HashMap<>();
        HashMap<String, Long> existingFileSizes = new HashMap<>();
        HashMap<String, Long> existingFileModifiedTimes = new HashMap<>();
        for (DocumentEntry entry : listChildren(context, documentDirectory)) {
            if (entry.directory) {
                existingDirectories.put(entry.displayName, entry.uri);
            } else {
                existingFiles.put(entry.displayName, entry.uri);
                existingFileSizes.put(entry.displayName, entry.size);
                existingFileModifiedTimes.put(entry.displayName, entry.lastModified);
            }
        }

        java.util.Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File child : children) {
            if (child.getName().equals("launcher_log")) continue;
            if (child.getName().equals(".java_launcher_saf_tmp")) continue;
            if (child.getName().equals(SYNC_STATE_FILE)) continue;
            if (child.getName().equals(".scoped_storage_metadata_loaded")) continue;
            if (child.getName().equals(".pending_delete")) continue;

            if (child.isDirectory()) {
                Uri childDirectory = ensureDirectory(context, documentDirectory, child.getName(), existingDirectories);
                copyLocalDirectoryToDocument(context, child, childDirectory, progress, rootForDisplay, syncState);
            } else if (child.isFile()) {
                String relative = relativePath(rootForDisplay, child);
                if (progress != null) progress.onProgress(99, "Syncing scoped storage: " + relative);
                copyLocalFileToDocument(
                        context,
                        child,
                        documentDirectory,
                        child.getName(),
                        relative,
                        existingFiles,
                        existingFileSizes,
                        existingFileModifiedTimes,
                        syncState
                );
            }
        }
    }

    private static void copyDocumentDirectoryToLocal(
            @NonNull Context context,
            @NonNull Uri documentDirectory,
            @NonNull File localDirectory,
            @Nullable Progress progress,
            @NonNull String relativePrefix
    ) throws Exception {
        if (!localDirectory.exists() && !localDirectory.mkdirs()) {
            throw new IllegalStateException("Unable to create local mirror directory: " + localDirectory.getAbsolutePath());
        }

        for (DocumentEntry entry : listChildren(context, documentDirectory)) {
            if (entry.displayName == null || entry.displayName.trim().isEmpty()) continue;
            File localChild = new File(localDirectory, entry.displayName);
            String relative = relativePrefix.isEmpty() ? entry.displayName : relativePrefix + "/" + entry.displayName;

            if (entry.directory) {
                copyDocumentDirectoryToLocal(context, entry.uri, localChild, progress, relative);
            } else {
                if (progress != null) progress.onProgress(3, "Reading scoped storage: " + relative);
                copyDocumentFileToLocal(context, entry.uri, localChild, entry.size, relative);
            }
        }
    }

    private static void copyDocumentMetadataToLocal(
            @NonNull Context context,
            @NonNull Uri documentDirectory,
            @NonNull File localDirectory,
            @Nullable Progress progress,
            @NonNull String relativePrefix
    ) throws Exception {
        if (!localDirectory.exists() && !localDirectory.mkdirs()) {
            throw new IllegalStateException("Unable to create local metadata mirror directory: " + localDirectory.getAbsolutePath());
        }

        for (DocumentEntry entry : listChildren(context, documentDirectory)) {
            if (entry.displayName == null || entry.displayName.trim().isEmpty()) continue;

            String relative = relativePrefix.isEmpty() ? entry.displayName : relativePrefix + "/" + entry.displayName;
            String normalized = normalizeMetadataPath(relative);

            if (entry.directory) {
                if (!shouldDescendForLauncherMetadata(normalized)) {
                    continue;
                }

                File localChild = new File(localDirectory, entry.displayName);
                copyDocumentMetadataToLocal(context, entry.uri, localChild, progress, relative);
            } else if (shouldCopyLauncherMetadataFile(normalized)) {
                if (progress != null) progress.onProgress(3, "Reading launcher metadata: " + relative);
                copyDocumentFileToLocal(context, entry.uri, new File(localDirectory, entry.displayName), entry.size, relative);
            }
        }
    }

    @NonNull
    private static String normalizeMetadataPath(@NonNull String relative) {
        String value = cleanRelativePath(relative);
        if (value.equals(".minecraft")) return "";
        if (value.startsWith(".minecraft/")) return value.substring(".minecraft/".length());
        return value;
    }

    @NonNull
    private static String cleanRelativePath(@NonNull String relative) {
        String value = relative.replace('\\', '/').trim();
        while (value.startsWith("/")) value = value.substring(1);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static boolean shouldDescendForLauncherMetadata(@NonNull String relative) {
        if (relative.isEmpty()) return true;

        String[] parts = relative.split("/");
        if (parts.length == 0) return true;

        if ("instances".equals(parts[0])) {
            if (parts.length <= 2) return true;
            return parts.length == 3 && "game".equals(parts[2]);
        }

        if ("versions".equals(parts[0])) {
            return parts.length <= 2;
        }

        // If the picked SAF root is the launcher folder, descend into its .minecraft folder.
        return ".minecraft".equals(parts[0]);
    }

    private static boolean shouldCopyLauncherMetadataFile(@NonNull String relative) {
        if (relative.equals("launcher_profiles.json")) return true;

        String[] parts = relative.split("/");
        if (parts.length == 3
                && "instances".equals(parts[0])
                && "instance.json".equals(parts[2])) {
            return true;
        }

        if (parts.length == 3
                && "instances".equals(parts[0])
                && parts[2].startsWith("icon")) {
            return true;
        }

        if (parts.length == 4
                && "instances".equals(parts[0])
                && "game".equals(parts[2])
                && "options.txt".equals(parts[3])) {
            return true;
        }

        return parts.length == 3
                && "versions".equals(parts[0])
                && parts[2].endsWith(".json");
    }

    @NonNull
    private static Uri ensureDirectory(
            @NonNull Context context,
            @NonNull Uri parentDirectory,
            @NonNull String name
    ) throws Exception {
        return ensureDirectory(context, parentDirectory, name, null);
    }

    @NonNull
    private static Uri ensureDirectory(
            @NonNull Context context,
            @NonNull Uri parentDirectory,
            @NonNull String name,
            @Nullable HashMap<String, Uri> knownDirectories
    ) throws Exception {
        Uri existing = knownDirectories != null ? knownDirectories.get(name) : findChild(context, parentDirectory, name, true);
        if (existing != null) return existing;

        Uri created = DocumentsContract.createDocument(
                context.getContentResolver(),
                parentDirectory,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name
        );
        if (created == null) throw new IllegalStateException("Unable to create SAF directory: " + name);
        if (knownDirectories != null) knownDirectories.put(name, created);
        return created;
    }

    private static void copyLocalFileToDocument(
            @NonNull Context context,
            @NonNull File source,
            @NonNull Uri parentDirectory,
            @NonNull String name,
            @NonNull String relativePath
    ) throws Exception {
        copyLocalFileToDocument(context, source, parentDirectory, name, relativePath, null, null, null, null);
    }

    private static void copyLocalFileToDocument(
            @NonNull Context context,
            @NonNull File source,
            @NonNull Uri parentDirectory,
            @NonNull String name,
            @NonNull String relativePath,
            @Nullable HashMap<String, Uri> knownFiles,
            @Nullable HashMap<String, Long> knownFileSizes,
            @Nullable HashMap<String, Long> knownFileModifiedTimes,
            @Nullable SyncState syncState
    ) throws Exception {
        Uri fileUri = knownFiles != null ? knownFiles.get(name) : findChild(context, parentDirectory, name, false);
        if (fileUri != null) {
            long knownSize = -1L;
            if (knownFileSizes != null && knownFileSizes.containsKey(name)) {
                Long size = knownFileSizes.get(name);
                knownSize = size == null ? -1L : size;
            }
            long knownModified = -1L;
            if (knownFileModifiedTimes != null && knownFileModifiedTimes.containsKey(name)) {
                Long modified = knownFileModifiedTimes.get(name);
                knownModified = modified == null ? -1L : modified;
            }
            if (shouldSkipExistingSafWrite(
                    context,
                    source,
                    fileUri,
                    knownSize,
                    knownModified,
                    relativePath,
                    syncState
            )) {
                return;
            }
        }

        if (fileUri == null) {
            fileUri = DocumentsContract.createDocument(
                    context.getContentResolver(),
                    parentDirectory,
                    "application/octet-stream",
                    name
            );
            if (fileUri != null && knownFiles != null) {
                knownFiles.put(name, fileUri);
                if (knownFileSizes != null) knownFileSizes.put(name, -1L);
            }
        }
        if (fileUri == null) throw new IllegalStateException("Unable to create SAF file: " + name);

        try (InputStream input = new FileInputStream(source);
             OutputStream output = context.getContentResolver().openOutputStream(fileUri, "wt")) {
            if (output == null) throw new IllegalStateException("Unable to open SAF output: " + name);
            copy(input, output);
        }

        if (syncState != null) {
            syncState.record(
                    relativePath,
                    source.length(),
                    source.lastModified(),
                    queryDocumentLastModified(context, fileUri)
            );
        }
    }

    private static void copyDocumentFileToLocal(
            @NonNull Context context,
            @NonNull Uri source,
            @NonNull File target
    ) throws Exception {
        copyDocumentFileToLocal(context, source, target, queryDocumentSize(context, source), target.getName());
    }

    private static void copyDocumentFileToLocal(
            @NonNull Context context,
            @NonNull Uri source,
            @NonNull File target,
            long sourceSize,
            @NonNull String relativePath
    ) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Unable to create local mirror parent: " + parent.getAbsolutePath());
        }

        if (shouldSkipExistingLocalWrite(target, sourceSize, relativePath)) {
            return;
        }

        try (InputStream input = context.getContentResolver().openInputStream(source);
             OutputStream output = new FileOutputStream(target, false)) {
            if (input == null) throw new IllegalStateException("Unable to open SAF input: " + source);
            copy(input, output);
        }
    }

    private static boolean shouldSkipExistingSafWrite(
            @NonNull Context context,
            @NonNull File source,
            @NonNull Uri existingFileUri,
            long knownExistingSize,
            long knownExistingModified,
            @NonNull String relativePath,
            @Nullable SyncState syncState
    ) {
        long sourceSize = source.length();
        long existingSize = knownExistingSize >= 0L
                ? knownExistingSize
                : queryDocumentSize(context, existingFileUri);
        if (sourceSize < 0L || sourceSize != existingSize) return false;

        if (isImmutableMinecraftPayload(relativePath)) return true;
        if (syncState == null) return false;

        long existingModified = knownExistingModified >= 0L
                ? knownExistingModified
                : queryDocumentLastModified(context, existingFileUri);
        return syncState.matches(
                relativePath,
                sourceSize,
                source.lastModified(),
                existingModified
        );
    }

    private static boolean shouldSkipExistingLocalWrite(
            @NonNull File target,
            long sourceSize,
            @NonNull String relativePath
    ) {
        return target.isFile()
                && sourceSize >= 0L
                && target.length() == sourceSize
                && isImmutableMinecraftPayload(relativePath);
    }

    private static boolean isImmutableMinecraftPayload(@NonNull String relativePath) {
        String value = cleanRelativePath(relativePath).toLowerCase(Locale.ROOT);
        if (value.equals(".minecraft")) return false;
        if (value.startsWith(".minecraft/")) {
            value = value.substring(".minecraft/".length());
        }

        return value.startsWith("assets/objects/")
                || value.startsWith("libraries/")
                || (value.startsWith("versions/") && value.endsWith(".jar"));
    }

    private static long queryDocumentSize(@NonNull Context context, @NonNull Uri documentUri) {
        try (Cursor cursor = context.getContentResolver().query(
                documentUri,
                new String[]{DocumentsContract.Document.COLUMN_SIZE},
                null,
                null,
                null
        )) {
            if (cursor == null || !cursor.moveToFirst()) return -1L;
            return cursor.isNull(0) ? -1L : cursor.getLong(0);
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to read SAF document size: " + throwable.getMessage());
            return -1L;
        }
    }

    private static long queryDocumentLastModified(@NonNull Context context, @NonNull Uri documentUri) {
        try (Cursor cursor = context.getContentResolver().query(
                documentUri,
                new String[]{DocumentsContract.Document.COLUMN_LAST_MODIFIED},
                null,
                null,
                null
        )) {
            if (cursor == null || !cursor.moveToFirst()) return -1L;
            return cursor.isNull(0) ? -1L : cursor.getLong(0);
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to read SAF document modified time: " + throwable.getMessage());
            return -1L;
        }
    }

    @Nullable
    private static Uri findChild(
            @NonNull Context context,
            @NonNull Uri parentDirectory,
            @NonNull String name,
            boolean directory
    ) {
        try {
            for (DocumentEntry entry : listChildren(context, parentDirectory)) {
                if (name.equals(entry.displayName) && entry.directory == directory) return entry.uri;
            }
        } catch (Throwable throwable) {
            Logging.i(TAG, "findChild failed: " + throwable.getMessage());
        }
        return null;
    }

    @NonNull
    private static java.util.ArrayList<DocumentEntry> listChildren(
            @NonNull Context context,
            @NonNull Uri parentDirectory
    ) throws Exception {
        java.util.ArrayList<DocumentEntry> result = new java.util.ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        String parentDocumentId = DocumentsContract.getDocumentId(parentDirectory);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentDirectory, parentDocumentId);

        try (Cursor cursor = resolver.query(
                childrenUri,
                new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                },
                null,
                null,
                null
        )) {
            if (cursor == null) return result;
            while (cursor.moveToNext()) {
                String documentId = cursor.getString(0);
                String displayName = cursor.getString(1);
                String mimeType = cursor.getString(2);
                long size = cursor.isNull(3) ? -1L : cursor.getLong(3);
                long lastModified = cursor.isNull(4) ? -1L : cursor.getLong(4);
                if (documentId == null || displayName == null) continue;

                Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(parentDirectory, documentId);
                boolean directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType);
                result.add(new DocumentEntry(documentUri, displayName, directory, size, lastModified));
            }
        }

        return result;
    }

    private static void copy(@NonNull InputStream input, @NonNull OutputStream output) throws Exception {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    @NonNull
    private static String relativePath(@NonNull File root, @NonNull File file) {
        try {
            String rootPath = root.getCanonicalPath();
            String filePath = file.getCanonicalPath();
            if (filePath.startsWith(rootPath)) {
                String out = filePath.substring(rootPath.length());
                while (out.startsWith(File.separator)) out = out.substring(1);
                return out;
            }
        } catch (Throwable ignored) {
        }
        return file.getName();
    }

    private static final class SyncState {
        private final File stateFile;
        private final JSONObject entries;

        private SyncState(@NonNull File stateFile, @NonNull JSONObject entries) {
            this.stateFile = stateFile;
            this.entries = entries;
        }

        @NonNull
        static SyncState load(@NonNull File stateFile) {
            if (!stateFile.isFile()) return new SyncState(stateFile, new JSONObject());

            try (InputStream input = new FileInputStream(stateFile);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                copy(input, output);
                String raw = output.toString(StandardCharsets.UTF_8.name());
                return new SyncState(stateFile, new JSONObject(raw));
            } catch (Throwable throwable) {
                Logging.i(TAG, "Unable to read SAF sync state: " + throwable.getMessage());
                return new SyncState(stateFile, new JSONObject());
            }
        }

        boolean matches(
                @NonNull String relativePath,
                long localSize,
                long localModified,
                long remoteModified
        ) {
            JSONObject record = entries.optJSONObject(cleanRelativePath(relativePath));
            if (record == null) return false;
            if (record.optLong("localSize", Long.MIN_VALUE) != localSize) return false;
            if (record.optLong("localModified", Long.MIN_VALUE) != localModified) return false;

            long recordedRemoteModified = record.optLong("remoteModified", -1L);
            return recordedRemoteModified < 0L
                    || remoteModified < 0L
                    || recordedRemoteModified == remoteModified;
        }

        void record(
                @NonNull String relativePath,
                long localSize,
                long localModified,
                long remoteModified
        ) {
            try {
                JSONObject record = new JSONObject();
                record.put("localSize", localSize);
                record.put("localModified", localModified);
                record.put("remoteModified", remoteModified);
                entries.put(cleanRelativePath(relativePath), record);
            } catch (Throwable throwable) {
                Logging.i(TAG, "Unable to update SAF sync state: " + throwable.getMessage());
            }
        }

        void save() {
            try {
                File parent = stateFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) return;

                File temp = new File(stateFile.getAbsolutePath() + ".tmp");
                try (OutputStream output = new FileOutputStream(temp, false)) {
                    output.write(entries.toString().getBytes(StandardCharsets.UTF_8));
                }

                if (stateFile.exists() && !stateFile.delete()) {
                    Logging.i(TAG, "Unable to replace SAF sync state: " + stateFile.getAbsolutePath());
                    //noinspection ResultOfMethodCallIgnored
                    temp.delete();
                    return;
                }
                if (!temp.renameTo(stateFile)) {
                    try (InputStream input = new FileInputStream(temp);
                         OutputStream output = new FileOutputStream(stateFile, false)) {
                        copy(input, output);
                    }
                    //noinspection ResultOfMethodCallIgnored
                    temp.delete();
                }
            } catch (Throwable throwable) {
                Logging.i(TAG, "Unable to save SAF sync state: " + throwable.getMessage());
            }
        }
    }

    private static final class DocumentEntry {
        final Uri uri;
        final String displayName;
        final boolean directory;
        final long size;
        final long lastModified;

        DocumentEntry(
                @NonNull Uri uri,
                @NonNull String displayName,
                boolean directory,
                long size,
                long lastModified
        ) {
            this.uri = uri;
            this.displayName = displayName;
            this.directory = directory;
            this.size = size;
            this.lastModified = lastModified;
        }
    }

}

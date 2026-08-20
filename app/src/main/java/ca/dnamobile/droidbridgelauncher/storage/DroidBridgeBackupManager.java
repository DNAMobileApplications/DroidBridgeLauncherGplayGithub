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

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class DroidBridgeBackupManager {
    private static final int BUFFER_SIZE = 128 * 1024;
    private static final String MANIFEST_NAME = "backup_manifest.json";
    private static final String OLD_LAUNCHER_HOME_ROOT = "launcher_home/";
    private static final String OLD_MINECRAFT_ROOT = "launcher_home/.minecraft/";
    private static final String MINECRAFT_ROOT = ".minecraft/";
    private static final String LAUNCHER_DATA_ROOT = "launcher_data/";

    private DroidBridgeBackupManager() {
    }

    public interface ProgressCallback {
        void onProgress(@NonNull String message);
    }

    public static final class Result {
        public final String fileName;
        public final int fileCount;
        public final long byteCount;

        private Result(@NonNull String fileName, int fileCount, long byteCount) {
            this.fileName = fileName;
            this.fileCount = fileCount;
            this.byteCount = byteCount;
        }
    }

    public static final class RestoreResult {
        public final int fileCount;
        public final long byteCount;
        @Nullable
        public final String previousDataPath;

        private RestoreResult(int fileCount, long byteCount, @Nullable String previousDataPath) {
            this.fileCount = fileCount;
            this.byteCount = byteCount;
            this.previousDataPath = previousDataPath;
        }
    }

    private static final class Counter {
        int files;
        long bytes;
        long lastUpdateMs;
    }

    private static final class RestoreTarget {
        final boolean minecraft;
        final String relativePath;

        RestoreTarget(boolean minecraft, @NonNull String relativePath) {
            this.minecraft = minecraft;
            this.relativePath = relativePath;
        }
    }

    @NonNull
    public static Result createBackup(
            @NonNull Context context,
            @NonNull File launcherHomeOrMinecraftHome,
            @NonNull Uri targetTreeUri,
            @Nullable ProgressCallback callback
    ) throws Exception {
        File launcherHome = resolveLauncherHome(launcherHomeOrMinecraftHome);
        File minecraftHome = resolveMinecraftHome(launcherHomeOrMinecraftHome);
        if (!minecraftHome.isDirectory()) {
            throw new IllegalStateException("DroidBridge .minecraft folder does not exist: " + minecraftHome.getAbsolutePath());
        }

        String fileName = "DroidBridge_Backup_"
                + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date())
                + ".zip";

        notify(callback, "Creating " + fileName + "...");

        Uri parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
                targetTreeUri,
                DocumentsContract.getTreeDocumentId(targetTreeUri)
        );
        Uri backupUri = DocumentsContract.createDocument(
                context.getContentResolver(),
                parentDocumentUri,
                "application/zip",
                fileName
        );
        if (backupUri == null) {
            throw new IllegalStateException("The selected folder did not allow creating a backup file.");
        }

        Counter counter = new Counter();
        try (OutputStream rawOutput = context.getContentResolver().openOutputStream(backupUri, "w")) {
            if (rawOutput == null) {
                throw new IllegalStateException("Unable to open the selected backup file for writing.");
            }

            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(rawOutput, BUFFER_SIZE))) {
                zip.setLevel(6);
                writeManifest(context, launcherHome, minecraftHome, zip);

                // The contents of .minecraft are deliberately written at the head/root of the zip.
                // That means instances/, versions/, libraries/, assets/, etc. are top-level entries.
                zipDirectory(minecraftHome, minecraftHome, "", zip, counter, callback);

                // Launcher-home side data is kept under a namespaced folder so it never collides with
                // Minecraft's top-level folders and never creates a nested .minecraft folder.
                zipLauncherSideDirectory(launcherHome, "controlmap", zip, counter, callback);
                zipLauncherSideDirectory(launcherHome, "mouse", zip, counter, callback);
                zipLauncherSideDirectory(launcherHome, "background", zip, counter, callback);
            }
        }

        notify(callback, "Backup complete: " + counter.files + " files • " + formatBytes(counter.bytes));
        return new Result(fileName, counter.files, counter.bytes);
    }

    @NonNull
    public static RestoreResult restoreBackup(
            @NonNull Context context,
            @NonNull Uri backupZipUri,
            @NonNull File launcherHomeOrMinecraftHome,
            @Nullable ProgressCallback callback
    ) throws Exception {
        File launcherHome = resolveLauncherHome(launcherHomeOrMinecraftHome);
        File minecraftHome = new File(launcherHome, ".minecraft");
        if (!launcherHome.exists() && !launcherHome.mkdirs() && !launcherHome.isDirectory()) {
            throw new IllegalStateException("Unable to create launcher home: " + launcherHome.getAbsolutePath());
        }

        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        File tempRoot = new File(launcherHome, ".droidbridge_restore_tmp_" + stamp);
        File tempMinecraft = new File(tempRoot, ".minecraft");
        File previousData = new File(launcherHome, ".minecraft.before_restore_" + stamp);

        deleteRecursively(tempRoot);
        if (!tempMinecraft.mkdirs() && !tempMinecraft.isDirectory()) {
            throw new IllegalStateException("Unable to create temporary restore folder: " + tempMinecraft.getAbsolutePath());
        }

        notify(callback, "Reading DroidBridge backup...");
        Counter counter = new Counter();
        boolean sawManifest = false;
        boolean sawMinecraftData = false;

        try (InputStream rawInput = context.getContentResolver().openInputStream(backupZipUri)) {
            if (rawInput == null) {
                throw new IllegalStateException("Unable to open selected backup file.");
            }
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(rawInput, BUFFER_SIZE))) {
                ZipEntry entry;
                byte[] buffer = new byte[BUFFER_SIZE];
                while ((entry = zip.getNextEntry()) != null) {
                    String entryName = normalizeZipEntryName(entry.getName());
                    if (entryName.isEmpty()) {
                        zip.closeEntry();
                        continue;
                    }

                    if (MANIFEST_NAME.equals(entryName)) {
                        sawManifest = true;
                        drainEntry(zip, buffer);
                        zip.closeEntry();
                        continue;
                    }

                    RestoreTarget target = mapRestoreTarget(entryName);
                    if (target == null || target.relativePath.isEmpty()) {
                        drainEntry(zip, buffer);
                        zip.closeEntry();
                        continue;
                    }

                    File targetRoot = target.minecraft ? tempMinecraft : tempRoot;
                    File outFile = safeResolveInside(targetRoot, target.relativePath);
                    if (target.minecraft) sawMinecraftData = true;

                    if (entry.isDirectory()) {
                        if (!outFile.exists() && !outFile.mkdirs() && !outFile.isDirectory()) {
                            throw new IllegalStateException("Unable to create restore folder: " + outFile.getAbsolutePath());
                        }
                        zip.closeEntry();
                        continue;
                    }

                    File outParent = outFile.getParentFile();
                    if (outParent != null && !outParent.exists() && !outParent.mkdirs() && !outParent.isDirectory()) {
                        throw new IllegalStateException("Unable to create restore folder: " + outParent.getAbsolutePath());
                    }

                    try (OutputStream output = new BufferedOutputStream(new FileOutputStream(outFile), BUFFER_SIZE)) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            output.write(buffer, 0, read);
                            counter.bytes += read;
                        }
                    }
                    if (entry.getTime() > 0L) {
                        //noinspection ResultOfMethodCallIgnored
                        outFile.setLastModified(entry.getTime());
                    }
                    counter.files++;
                    maybeNotifyRestore(counter, callback);
                    zip.closeEntry();
                }
            }
        } catch (Throwable throwable) {
            deleteRecursively(tempRoot);
            throw throwable;
        }

        if (!sawManifest) {
            deleteRecursively(tempRoot);
            throw new IllegalStateException("Selected file is missing backup_manifest.json.");
        }
        if (!sawMinecraftData || !tempMinecraft.isDirectory() || isDirectoryEmpty(tempMinecraft)) {
            deleteRecursively(tempRoot);
            throw new IllegalStateException("Selected file does not contain DroidBridge .minecraft data.");
        }

        notify(callback, "Installing restored .minecraft into: " + minecraftHome.getAbsolutePath());
        String previousPath = null;
        if (minecraftHome.exists() && !isDirectoryEmpty(minecraftHome)) {
            if (previousData.exists()) deleteRecursively(previousData);
            copyDirectory(minecraftHome, previousData, callback, "Saving previous .minecraft");
            previousPath = previousData.getAbsolutePath();
            deleteChildren(minecraftHome);
        } else if (!minecraftHome.exists() && !minecraftHome.mkdirs() && !minecraftHome.isDirectory()) {
            deleteRecursively(tempRoot);
            throw new IllegalStateException("Unable to create .minecraft folder: " + minecraftHome.getAbsolutePath());
        }

        copyDirectory(tempMinecraft, minecraftHome, callback, "Installing restored .minecraft");
        restoreLauncherSideData(tempRoot, launcherHome, callback);
        deleteRecursively(tempRoot);

        notify(callback, "Restore complete: " + counter.files + " files • " + formatBytes(counter.bytes));
        return new RestoreResult(counter.files, counter.bytes, previousPath);
    }

    @NonNull
    private static File resolveLauncherHome(@NonNull File launcherHomeOrMinecraftHome) {
        if (".minecraft".equals(launcherHomeOrMinecraftHome.getName())) {
            File parent = launcherHomeOrMinecraftHome.getParentFile();
            return parent != null ? parent : launcherHomeOrMinecraftHome;
        }
        return launcherHomeOrMinecraftHome;
    }

    @NonNull
    private static File resolveMinecraftHome(@NonNull File launcherHomeOrMinecraftHome) {
        if (".minecraft".equals(launcherHomeOrMinecraftHome.getName())) {
            return launcherHomeOrMinecraftHome;
        }
        return new File(launcherHomeOrMinecraftHome, ".minecraft");
    }

    private static void writeManifest(
            @NonNull Context context,
            @NonNull File launcherHome,
            @NonNull File minecraftHome,
            @NonNull ZipOutputStream zip
    ) throws Exception {
        JSONObject manifest = new JSONObject();
        manifest.put("type", "droidbridge-backup");
        manifest.put("backupVersion", 3);
        manifest.put("contentRoot", "minecraft-head");
        manifest.put("createdAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date()));
        manifest.put("packageName", context.getPackageName());
        manifest.put("launcherHome", launcherHome.getAbsolutePath());
        manifest.put("minecraftHome", minecraftHome.getAbsolutePath());

        byte[] data = manifest.toString(2).getBytes(StandardCharsets.UTF_8);
        ZipEntry entry = new ZipEntry(MANIFEST_NAME);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private static void zipLauncherSideDirectory(
            @NonNull File launcherHome,
            @NonNull String childName,
            @NonNull ZipOutputStream zip,
            @NonNull Counter counter,
            @Nullable ProgressCallback callback
    ) throws Exception {
        File child = new File(launcherHome, childName);
        if (!child.exists()) return;
        zipDirectory(child, child, LAUNCHER_DATA_ROOT + childName + "/", zip, counter, callback);
    }

    private static void zipDirectory(
            @NonNull File root,
            @NonNull File current,
            @NonNull String zipPrefix,
            @NonNull ZipOutputStream zip,
            @NonNull Counter counter,
            @Nullable ProgressCallback callback
    ) throws Exception {
        File[] children = current.listFiles();
        if (children == null) return;

        java.util.Arrays.sort(children, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));
        for (File child : children) {
            if (shouldSkip(child)) continue;

            if (child.isDirectory()) {
                zipDirectory(root, child, zipPrefix, zip, counter, callback);
            } else if (child.isFile()) {
                zipFile(root, child, zipPrefix, zip, counter, callback);
            }
        }
    }

    private static void zipFile(
            @NonNull File root,
            @NonNull File file,
            @NonNull String zipPrefix,
            @NonNull ZipOutputStream zip,
            @NonNull Counter counter,
            @Nullable ProgressCallback callback
    ) throws Exception {
        String relative = root.toURI().relativize(file.toURI()).getPath();
        if (relative == null || relative.trim().isEmpty()) return;

        ZipEntry entry = new ZipEntry((zipPrefix + relative).replace('\\', '/'));
        entry.setTime(file.lastModified());
        zip.putNextEntry(entry);

        byte[] buffer = new byte[BUFFER_SIZE];
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file), BUFFER_SIZE)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                zip.write(buffer, 0, read);
                counter.bytes += read;
            }
        }
        zip.closeEntry();

        counter.files++;
        long now = System.currentTimeMillis();
        if (callback != null && (counter.files == 1 || counter.files % 50 == 0 || now - counter.lastUpdateMs > 1500L)) {
            counter.lastUpdateMs = now;
            callback.onProgress("Backing up " + counter.files + " files • " + formatBytes(counter.bytes));
        }
    }

    @Nullable
    private static RestoreTarget mapRestoreTarget(@NonNull String entryName) {
        String relative = entryName;
        while (relative.startsWith("/")) relative = relative.substring(1);
        if (relative.isEmpty() || MANIFEST_NAME.equals(relative)) return null;

        if (relative.startsWith(OLD_MINECRAFT_ROOT)) {
            return cleanTarget(true, relative.substring(OLD_MINECRAFT_ROOT.length()));
        }
        if (relative.startsWith(MINECRAFT_ROOT)) {
            return cleanTarget(true, relative.substring(MINECRAFT_ROOT.length()));
        }
        if (relative.startsWith(LAUNCHER_DATA_ROOT)) {
            return cleanTarget(false, relative.substring(LAUNCHER_DATA_ROOT.length()));
        }
        if (relative.startsWith(OLD_LAUNCHER_HOME_ROOT)) {
            String oldRelative = relative.substring(OLD_LAUNCHER_HOME_ROOT.length());
            if (oldRelative.startsWith(MINECRAFT_ROOT)) {
                return cleanTarget(true, oldRelative.substring(MINECRAFT_ROOT.length()));
            }
            if (isAllowedLauncherSidePath(oldRelative)) {
                return cleanTarget(false, oldRelative);
            }
            return null;
        }

        if (isMinecraftHeadPath(relative)) {
            return cleanTarget(true, relative);
        }
        if (isAllowedLauncherSidePath(relative)) {
            return cleanTarget(false, relative);
        }
        return null;
    }

    @Nullable
    private static RestoreTarget cleanTarget(boolean minecraft, @Nullable String rawRelative) {
        if (rawRelative == null) return null;
        String relative = rawRelative.replace('\\', '/');
        while (relative.startsWith("/")) relative = relative.substring(1);
        if (relative.isEmpty() || MANIFEST_NAME.equals(relative)) return null;
        return new RestoreTarget(minecraft, relative);
    }

    private static boolean isMinecraftHeadPath(@NonNull String relative) {
        String first = firstPathPart(relative).toLowerCase(Locale.US);
        Set<String> names = new HashSet<>();
        names.add("assets");
        names.add("config");
        names.add("crash-reports");
        names.add("defaultconfigs");
        names.add("downloads");
        names.add("instances");
        names.add("libraries");
        names.add("logs");
        names.add("mods");
        names.add("resourcepacks");
        names.add("saves");
        names.add("screenshots");
        names.add("shaderpacks");
        names.add("versions");
        names.add("options.txt");
        names.add("servers.dat");
        names.add("servers.dat_old");
        names.add("launcher_profiles.json");
        names.add("usercache.json");
        names.add("usernamecache.json");
        return names.contains(first);
    }

    private static boolean isAllowedLauncherSidePath(@NonNull String relative) {
        String first = firstPathPart(relative).toLowerCase(Locale.US);
        return "controlmap".equals(first)
                || "mouse".equals(first)
                || "background".equals(first);
    }

    @NonNull
    private static String firstPathPart(@NonNull String path) {
        String value = path.replace('\\', '/');
        int slash = value.indexOf('/');
        return slash >= 0 ? value.substring(0, slash) : value;
    }

    private static void restoreLauncherSideData(
            @NonNull File tempRoot,
            @NonNull File launcherHome,
            @Nullable ProgressCallback callback
    ) throws Exception {
        File[] children = tempRoot.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (".minecraft".equals(child.getName())) continue;
            if (!isAllowedLauncherSidePath(child.getName())) continue;
            File target = new File(launcherHome, child.getName());
            deleteRecursively(target);
            copyDirectory(child, target, callback, "Installing launcher data");
        }
    }

    private static void copyDirectory(
            @NonNull File source,
            @NonNull File target,
            @Nullable ProgressCallback callback,
            @NonNull String messagePrefix
    ) throws Exception {
        if (!source.exists()) return;
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs() && !target.isDirectory()) {
                throw new IllegalStateException("Unable to create folder: " + target.getAbsolutePath());
            }
            File[] children = source.listFiles();
            if (children == null) return;
            java.util.Arrays.sort(children, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));
            for (File child : children) {
                copyDirectory(child, new File(target, child.getName()), callback, messagePrefix);
            }
            return;
        }

        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("Unable to create folder: " + parent.getAbsolutePath());
        }

        byte[] buffer = new byte[BUFFER_SIZE];
        long copied = 0L;
        try (InputStream input = new BufferedInputStream(new FileInputStream(source), BUFFER_SIZE);
             OutputStream output = new BufferedOutputStream(new FileOutputStream(target), BUFFER_SIZE)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                copied += read;
            }
        }
        //noinspection ResultOfMethodCallIgnored
        target.setLastModified(source.lastModified());
        if (callback != null && copied > 0L) {
            callback.onProgress(messagePrefix + "... " + source.getName());
        }
    }

    private static void deleteChildren(@NonNull File directory) throws Exception {
        if (!directory.exists()) return;
        if (!directory.isDirectory()) {
            throw new IllegalStateException("Path is not a folder: " + directory.getAbsolutePath());
        }
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            deleteRecursively(child);
        }
    }

    private static boolean isDirectoryEmpty(@NonNull File directory) {
        if (!directory.exists()) return true;
        File[] children = directory.listFiles();
        return children == null || children.length == 0;
    }

    private static void maybeNotifyRestore(@NonNull Counter counter, @Nullable ProgressCallback callback) {
        long now = System.currentTimeMillis();
        if (callback != null && (counter.files == 1 || counter.files % 50 == 0 || now - counter.lastUpdateMs > 1500L)) {
            counter.lastUpdateMs = now;
            callback.onProgress("Restoring " + counter.files + " files • " + formatBytes(counter.bytes));
        }
    }

    private static void drainEntry(@NonNull ZipInputStream zip, @NonNull byte[] buffer) throws Exception {
        while (zip.read(buffer) != -1) {
            // Drain unused entry.
        }
    }

    @NonNull
    private static String normalizeZipEntryName(@Nullable String raw) {
        if (raw == null) return "";
        String value = raw.replace('\\', '/').trim();
        while (value.startsWith("/")) value = value.substring(1);
        return value;
    }

    @NonNull
    private static File safeResolveInside(@NonNull File root, @NonNull String relativePath) throws Exception {
        File target = new File(root, relativePath);
        String rootPath = root.getCanonicalPath();
        String targetPath = target.getCanonicalPath();
        if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
            throw new IllegalStateException("Backup contains an unsafe path: " + relativePath);
        }
        return target;
    }

    private static boolean shouldSkip(@NonNull File file) {
        String name = file.getName();
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);

        return lower.endsWith(".download")
                || lower.endsWith(".tmp")
                || lower.equals("tmp")
                || lower.equals("temp")
                || lower.contains(".restore_tmp")
                || lower.contains(".droidbridge_restore_tmp_")
                || lower.contains(".before_restore_");
    }

    private static void deleteRecursively(@NonNull File file) throws Exception {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete() && file.exists()) {
            throw new IllegalStateException("Unable to delete: " + file.getAbsolutePath());
        }
    }

    private static void notify(@Nullable ProgressCallback callback, @NonNull String message) {
        if (callback != null) callback.onProgress(message);
    }

    @NonNull
    public static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format(Locale.US, "%.2f GB", gb);
    }
}

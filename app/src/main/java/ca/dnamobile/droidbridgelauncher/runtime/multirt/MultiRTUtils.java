/*
 * DroidBridge Launcher runtime bridge component.
 *
 * DroidBridge modifications:
 * Copyright (c) 2026 DNA Mobile Applications.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package ca.dnamobile.droidbridgelauncher.runtime.multirt;

import android.annotation.SuppressLint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.runtime.Tools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

public final class MultiRTUtils {
    private static final String TAG = "MultiRTUtils";
    private static final long TAR_PROCESS_TIMEOUT_MS = 180_000L;
    private static final long UNPACK200_PROCESS_TIMEOUT_MS = 180_000L;
    private static final long PROCESS_PROGRESS_INTERVAL_MS = 1_000L;
    private static final long ARCHIVE_PROGRESS_INTERVAL_MS = 750L;
    private static final int MAX_PROCESS_OUTPUT_BYTES = 64 * 1024;

    public interface ProgressCallback {
        void onProgress(@NonNull String message);
    }

    private MultiRTUtils() {
    }

    @NonNull
    public static File getRuntimesHome() {
        return new File(PathManager.DIR_MULTIRT_HOME);
    }

    @NonNull
    public static File getRuntimeDir(@NonNull String runtimeName) {
        return new File(getRuntimesHome(), runtimeName);
    }

    @NonNull
    public static File getRuntimeHome(@NonNull String runtimeName) {
        return getRuntimeDir(runtimeName);
    }

    @NonNull
    public static String readInternalRuntimeVersion(@NonNull String runtimeName) throws IOException {
        File versionFile = new File(getRuntimeDir(runtimeName), "version");
        if (!versionFile.exists()) return "";
        try (InputStream input = new FileInputStream(versionFile)) {
            return Tools.read(input);
        }
    }

    public static void installRuntimeNamedBinpack(
            @NonNull InputStream universalPack,
            @NonNull InputStream binPack,
            @NonNull String runtimeName,
            @NonNull String runtimeVersion
    ) throws IOException {
        installRuntimeNamedBinpack(universalPack, binPack, runtimeName, runtimeVersion, null);
    }

    public static synchronized void installRuntimeNamedBinpack(
            @NonNull InputStream universalPack,
            @NonNull InputStream binPack,
            @NonNull String runtimeName,
            @NonNull String runtimeVersion,
            @Nullable ProgressCallback progressCallback
    ) throws IOException {
        reportProgress(progressCallback, "Preparing staged runtime...");

        File runtimesHome = getRuntimesHome();
        if (!runtimesHome.exists() && !runtimesHome.mkdirs()) {
            throw new IOException("Unable to create runtimes home: " + runtimesHome.getAbsolutePath());
        }

        File runtimeDir = getRuntimeDir(runtimeName);
        File tempDir = new File(runtimesHome, runtimeName + ".installing");

        // A killed/failed previous install may leave this directory behind.
        // Deletion must be verified; silently continuing is what used to turn a
        // recoverable stale stage into a permanent "no installed JRE" loop.
        PathManager.deleteRecursivelyChecked(tempDir);
        if (!tempDir.mkdirs()) {
            throw new IOException("Unable to create temp runtime directory: " + tempDir.getAbsolutePath());
        }

        try {
            extractTarXz(universalPack, tempDir, progressCallback, "shared Java files");
            extractTarXz(binPack, tempDir, progressCallback, "device Java binaries");

            reportProgress(progressCallback, "Normalizing runtime layout...");
            normalizeRuntimeDirIfNeeded(tempDir);

            writeText(new File(tempDir, "version"), runtimeVersion);
            reportProgress(progressCallback, "Optimizing runtime files...");
            postPrepare(tempDir);

            reportProgress(progressCallback, "Activating installed runtime...");
            activateRuntimeDirectory(tempDir, runtimeDir);
            reportProgress(progressCallback, "Runtime installation complete.");
        } catch (IOException | RuntimeException e) {
            reportProgress(progressCallback, "Installation failed; cleaning staged files...");
            try {
                PathManager.deleteRecursivelyChecked(tempDir);
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
                Logging.e(TAG, "Unable to remove failed staged runtime " + tempDir.getAbsolutePath(), cleanupFailure);
            }
            throw e;
        }
    }



    public static synchronized void installRuntimeFromArchive(
            @NonNull InputStream archiveInput,
            @NonNull String runtimeName,
            @NonNull String runtimeVersion,
            @Nullable String archiveDisplayName
    ) throws IOException {
        File runtimesHome = getRuntimesHome();
        if (!runtimesHome.exists() && !runtimesHome.mkdirs()) {
            throw new IOException("Unable to create runtimes home: " + runtimesHome.getAbsolutePath());
        }

        File runtimeDir = getRuntimeDir(runtimeName);
        File tempDir = new File(runtimesHome, runtimeName + ".custom-installing");
        PathManager.deleteRecursivelyChecked(tempDir);
        if (!tempDir.mkdirs()) {
            throw new IOException("Unable to create temp runtime directory: " + tempDir.getAbsolutePath());
        }

        String lowerName = archiveDisplayName == null ? "" : archiveDisplayName.toLowerCase(java.util.Locale.ROOT);
        try {
            if (lowerName.endsWith(".tar.xz") || lowerName.endsWith(".txz")) {
                extractTarXz(archiveInput, tempDir);
            } else {
                extractZip(archiveInput, tempDir);
            }

            normalizeRuntimeDirIfNeeded(tempDir);
            writeText(new File(tempDir, "version"), runtimeVersion);
            postPrepare(tempDir);

            activateRuntimeDirectory(tempDir, runtimeDir);
        } catch (IOException | RuntimeException e) {
            try {
                PathManager.deleteRecursivelyChecked(tempDir);
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
                Logging.e(TAG, "Unable to remove failed custom staged runtime " + tempDir.getAbsolutePath(), cleanupFailure);
            }
            throw e;
        }
    }

    private static void activateRuntimeDirectory(
            @NonNull File stagedRuntime,
            @NonNull File runtimeDir
    ) throws IOException {
        File parent = runtimeDir.getParentFile();
        if (parent == null) {
            throw new IOException("Runtime directory has no parent: " + runtimeDir.getAbsolutePath());
        }
        File backupDir = new File(parent, runtimeDir.getName() + ".backup");

        // Recover a previous interrupted swap before touching the staged copy.
        if (!runtimeDir.exists() && backupDir.exists() && !backupDir.renameTo(runtimeDir)) {
            throw new IOException("Unable to recover runtime backup: " + backupDir.getAbsolutePath());
        }

        if (runtimeDir.exists()) {
            // Never assume deleteQuietly() removed a stale backup. renameTo() fails
            // when the destination still exists, which was the root of the repeated
            // Internal-17/21/25 activation failures seen in the field.
            PathManager.deleteRecursivelyChecked(backupDir);
            if (backupDir.exists()) {
                throw new IOException("Stale runtime backup still exists: " + backupDir.getAbsolutePath());
            }
            if (!runtimeDir.renameTo(backupDir)) {
                throw new IOException("Unable to move existing runtime aside: " + runtimeDir.getAbsolutePath());
            }
        }

        try {
            if (!stagedRuntime.renameTo(runtimeDir)) {
                copyDirectory(stagedRuntime, runtimeDir);
                PathManager.deleteQuietly(stagedRuntime);
            }
            if (!runtimeDir.isDirectory()) {
                throw new IOException("Activated runtime directory is missing: " + runtimeDir.getAbsolutePath());
            }

            try {
                PathManager.deleteRecursivelyChecked(backupDir);
            } catch (IOException cleanupFailure) {
                // The new runtime is already active and verified by UnpackJreTask.
                // Keep it usable and let the next transaction retry stale-backup
                // cleanup instead of falsely marking this runtime as missing.
                Logging.e(TAG, "Installed runtime but could not remove backup "
                        + backupDir.getAbsolutePath(), cleanupFailure);
            }
        } catch (Throwable throwable) {
            try {
                PathManager.deleteRecursivelyChecked(runtimeDir);
            } catch (IOException cleanupFailure) {
                IOException restoreFailure = new IOException(
                        "Unable to remove failed replacement runtime before restoring backup: "
                                + runtimeDir.getAbsolutePath(), cleanupFailure);
                restoreFailure.addSuppressed(throwable);
                throw restoreFailure;
            }

            if (backupDir.exists() && !backupDir.renameTo(runtimeDir)) {
                IOException restoreFailure = new IOException(
                        "Unable to restore previous runtime after install failure: "
                                + backupDir.getAbsolutePath());
                restoreFailure.addSuppressed(throwable);
                throw restoreFailure;
            }
            if (throwable instanceof IOException) throw (IOException) throwable;
            if (throwable instanceof RuntimeException) throw (RuntimeException) throwable;
            throw new IOException("Unable to activate runtime " + runtimeDir.getName(), throwable);
        }
    }

    public static void postPrepare(@NonNull String runtimeName) {
        postPrepare(getRuntimeDir(runtimeName));
    }

    private static void postPrepare(@NonNull File runtimeDir) {
        setExecutableRecursive(new File(runtimeDir, "bin"));

        setJexecExecutable(runtimeDir);
        renameFreetypeIfNeeded(runtimeDir);

        try {
            unpackPack200Files(runtimeDir);
            copyAwtDummyNativeLibraries(runtimeDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to post-prepare runtime in "
                    + runtimeDir.getAbsolutePath(), e);
        }
    }

    private static void renameFreetypeIfNeeded(@NonNull File runtimeDir) {
        File runtimeLibDir = resolveRuntimeLibDir(runtimeDir);
        File oldName = new File(runtimeLibDir, "libfreetype.so.6");
        File newName = new File(runtimeLibDir, "libfreetype.so");

        if (oldName.isFile() && (!newName.exists() || oldName.length() != newName.length())) {
            //noinspection ResultOfMethodCallIgnored
            newName.delete();
            //noinspection ResultOfMethodCallIgnored
            oldName.renameTo(newName);
        }
    }

    private static void copyAwtDummyNativeLibraries(@NonNull File runtimeDir) throws IOException {
        File runtimeLibDir = resolveRuntimeLibDir(runtimeDir);
        copyDummyNativeLib("libawt_xawt.so", runtimeLibDir);
    }

    @SuppressLint("SetWorldReadable")
    private static void copyDummyNativeLib(@NonNull String libraryName, @NonNull File runtimeLibDir) throws IOException {
        File source = new File(PathManager.DIR_NATIVE_LIB, libraryName);
        File target = new File(runtimeLibDir, libraryName);

        if (!source.isFile()) {
            Logging.i(TAG, "AWT dummy native is missing from APK native dir: " + source.getAbsolutePath());
            return;
        }

        if (target.isFile() && target.length() == source.length()) {
            Logging.i(TAG, "AWT dummy native already prepared: " + target.getAbsolutePath());
            return;
        }

        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create runtime lib directory: " + parent.getAbsolutePath());
        }

        try (InputStream input = new FileInputStream(source); OutputStream output = new FileOutputStream(target)) {
            Tools.copy(input, output);
        }

        //noinspection ResultOfMethodCallIgnored
        target.setReadable(true, false);
        //noinspection ResultOfMethodCallIgnored
        target.setExecutable(true, false);

        Logging.i(TAG, "Copied AWT dummy native: " + source.getAbsolutePath()
                + " -> " + target.getAbsolutePath());
    }

    @NonNull
    private static File resolveRuntimeLibDir(@NonNull File runtimeDir) {
        String[] candidates = new String[]{
                "lib/aarch64",
                "lib/arm64",
                "lib/arm64-v8a",
                "lib/arm",
                "lib/armeabi-v7a",
                "lib/x86_64",
                "lib/amd64",
                "lib/i386",
                "lib/x86"
        };

        for (String candidate : candidates) {
            File dir = new File(runtimeDir, candidate);
            if (dir.isDirectory()) return dir;
        }

        return new File(runtimeDir, "lib");
    }

    private static void setJexecExecutable(@NonNull File runtimeDir) {
        File[] jexecCandidates = new File[]{
                new File(runtimeDir, "lib/jexec"),
                new File(runtimeDir, "jre/lib/jexec"),
                new File(runtimeDir, "lib/aarch64/jexec"),
                new File(runtimeDir, "lib/arm64/jexec"),
                new File(runtimeDir, "lib/arm64-v8a/jexec"),
                new File(runtimeDir, "lib/arm/jexec"),
                new File(runtimeDir, "lib/x86_64/jexec"),
                new File(runtimeDir, "lib/i386/jexec")
        };

        for (File jexec : jexecCandidates) {
            if (jexec.exists()) {
                //noinspection ResultOfMethodCallIgnored
                jexec.setExecutable(true, false);
            }
        }
    }

    private static void setExecutableRecursive(@NonNull File file) {
        if (!file.exists()) return;
        if (file.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            file.setExecutable(true, false);
            return;
        }

        File[] children = file.listFiles();
        if (children == null) return;
        for (File child : children) {
            setExecutableRecursive(child);
        }
    }

    private static void unpackPack200Files(@NonNull File runtimeDir) throws IOException {
        List<File> packFiles = new ArrayList<>();
        collectPackFiles(runtimeDir, packFiles);

        if (packFiles.isEmpty()) {
            return;
        }

        File nativeUnpack200 = new File(PathManager.DIR_NATIVE_LIB, "libunpack200.so");
        File runtimeUnpack200 = findUnpack200(runtimeDir);

        if (!nativeUnpack200.isFile() && (runtimeUnpack200 == null || !runtimeUnpack200.isFile())) {
            throw new IOException("Runtime has .pack files but neither APK libunpack200.so nor runtime bin/unpack200 was found. runtime="
                    + runtimeDir.getAbsolutePath()
                    + " nativeDir=" + PathManager.DIR_NATIVE_LIB);
        }

        String ldPath = buildRuntimeLdLibraryPath(runtimeDir);

        for (File packFile : packFiles) {
            String packPath = packFile.getAbsolutePath();
            if (!packPath.endsWith(".pack")) continue;

            File outputJar = new File(packPath.substring(0, packPath.length() - ".pack".length()));
            File parent = outputJar.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Unable to create directory: " + parent.getAbsolutePath());
            }

            if (outputJar.exists() && !outputJar.delete()) {
                throw new IOException("Unable to replace existing unpacked jar: " + outputJar.getAbsolutePath());
            }

            IOException nativeFailure = null;
            if (nativeUnpack200.isFile()) {
                try {
                    runApkNativeUnpack200(nativeUnpack200, ldPath, packFile, outputJar);
                    continue;
                } catch (IOException e) {
                    nativeFailure = e;
                    Logging.e(TAG, "APK libunpack200.so failed, trying runtime unpack200 if available", e);
                }
            }

            if (runtimeUnpack200 != null && runtimeUnpack200.isFile()) {
                try {
                    runRuntimeUnpack200(runtimeUnpack200, ldPath, packFile, outputJar);
                    continue;
                } catch (IOException runtimeFailure) {
                    if (nativeFailure != null) runtimeFailure.addSuppressed(nativeFailure);
                    throw runtimeFailure;
                }
            }

            if (nativeFailure != null) throw nativeFailure;
            throw new IOException("Unable to unpack pack200 file: " + packFile.getAbsolutePath());
        }
    }

    private static void collectPackFiles(@NonNull File root, @NonNull List<File> out) {
        File[] children = root.listFiles();
        if (children == null) return;

        for (File child : children) {
            if (child.isDirectory()) {
                collectPackFiles(child, out);
            } else if (child.isFile() && child.getName().endsWith(".pack")) {
                out.add(child);
            }
        }
    }

    @Nullable
    private static File findUnpack200(@NonNull File runtimeDir) {
        File[] candidates = new File[]{
                new File(runtimeDir, "bin/unpack200"),
                new File(runtimeDir, "jre/bin/unpack200")
        };

        for (File candidate : candidates) {
            if (candidate.isFile()) return candidate;
        }

        return findFileNamed(runtimeDir, "unpack200", 4);
    }

    private static void runApkNativeUnpack200(
            @NonNull File nativeUnpack200,
            @NonNull String ldPath,
            @NonNull File packFile,
            @NonNull File outputJar
    ) throws IOException {
        File nativeDir = nativeUnpack200.getParentFile();
        if (nativeDir == null) throw new IOException("libunpack200.so has no parent: " + nativeUnpack200.getAbsolutePath());

        // This mirrors the prior implementation/DroidBridge: execute the APK native helper from the APK native lib dir.
        ProcessBuilder processBuilder = new ProcessBuilder(
                "./" + nativeUnpack200.getName(),
                "-r",
                packFile.getAbsolutePath(),
                outputJar.getAbsolutePath()
        ).directory(nativeDir).redirectErrorStream(true);

        Map<String, String> env = processBuilder.environment();
        env.put("LD_LIBRARY_PATH", nativeDir.getAbsolutePath() + ":" + ldPath);
        env.put("PATH", nativeDir.getAbsolutePath() + ":" + env.getOrDefault("PATH", ""));

        runAndCheck(processBuilder, "apk-native libunpack200", packFile, outputJar, nativeDir.getAbsolutePath() + ":" + ldPath);
    }

    private static void runRuntimeUnpack200(
            @NonNull File unpack200,
            @NonNull String ldPath,
            @NonNull File packFile,
            @NonNull File outputJar
    ) throws IOException {
        // Fallback only. Android 14+ may reject executing this from writable app data.
        // The APK native libunpack200.so path above is preferred.
        //noinspection ResultOfMethodCallIgnored
        unpack200.setExecutable(true, false);

        ProcessBuilder processBuilder = new ProcessBuilder(
                unpack200.getAbsolutePath(),
                "-r",
                packFile.getAbsolutePath(),
                outputJar.getAbsolutePath()
        ).redirectErrorStream(true);

        Map<String, String> env = processBuilder.environment();
        env.put("LD_LIBRARY_PATH", ldPath);
        File parent = unpack200.getParentFile();
        if (parent != null) {
            env.put("PATH", parent.getAbsolutePath() + ":" + env.getOrDefault("PATH", ""));
        }

        runAndCheck(processBuilder, "runtime unpack200", packFile, outputJar, ldPath);
    }

    private static void runAndCheck(
            @NonNull ProcessBuilder processBuilder,
            @NonNull String label,
            @NonNull File packFile,
            @NonNull File outputJar,
            @NonNull String ldPath
    ) throws IOException {
        ProcessResult result = runProcess(
                processBuilder,
                label,
                UNPACK200_PROCESS_TIMEOUT_MS,
                null
        );

        if (result.exitCode != 0) {
            throw new IOException(label + " failed with exit " + result.exitCode
                    + "\npack=" + packFile.getAbsolutePath()
                    + "\nout=" + outputJar.getAbsolutePath()
                    + "\nLD_LIBRARY_PATH=" + ldPath
                    + "\n" + result.output);
        }

        if (!outputJar.isFile()) {
            throw new IOException(label + " returned success but output jar is missing: "
                    + outputJar.getAbsolutePath());
        }

        Logging.i(TAG, label + " OK: " + outputJar.getAbsolutePath());
    }

    @NonNull
    private static String buildRuntimeLdLibraryPath(@NonNull File runtimeDir) {
        ArrayList<String> paths = new ArrayList<>();

        addPath(paths, new File(PathManager.DIR_NATIVE_LIB));
        addPath(paths, new File(runtimeDir, "lib"));
        addPath(paths, new File(runtimeDir, "lib/aarch64"));
        addPath(paths, new File(runtimeDir, "lib/aarch64/jli"));
        addPath(paths, new File(runtimeDir, "lib/aarch64/server"));
        addPath(paths, new File(runtimeDir, "lib/arm64"));
        addPath(paths, new File(runtimeDir, "lib/arm64/jli"));
        addPath(paths, new File(runtimeDir, "lib/arm64/server"));
        addPath(paths, new File(runtimeDir, "lib/arm64-v8a"));
        addPath(paths, new File(runtimeDir, "lib/arm64-v8a/jli"));
        addPath(paths, new File(runtimeDir, "lib/arm64-v8a/server"));
        addPath(paths, new File(runtimeDir, "lib/arm"));
        addPath(paths, new File(runtimeDir, "lib/arm/jli"));
        addPath(paths, new File(runtimeDir, "lib/arm/server"));
        addPath(paths, new File(runtimeDir, "lib/x86_64"));
        addPath(paths, new File(runtimeDir, "lib/x86_64/jli"));
        addPath(paths, new File(runtimeDir, "lib/x86_64/server"));
        addPath(paths, new File(runtimeDir, "jre/lib"));
        addPath(paths, new File(runtimeDir, "jre/lib/aarch64"));
        addPath(paths, new File(runtimeDir, "jre/lib/aarch64/jli"));
        addPath(paths, new File(runtimeDir, "jre/lib/aarch64/server"));

        String systemLib = android.os.Build.SUPPORTED_64_BIT_ABIS != null
                && android.os.Build.SUPPORTED_64_BIT_ABIS.length > 0 ? "lib64" : "lib";
        paths.add("/system/" + systemLib);
        paths.add("/vendor/" + systemLib);
        paths.add("/vendor/" + systemLib + "/hw");

        return joinPathList(paths);
    }

    private static void addPath(@NonNull List<String> paths, @NonNull File file) {
        if (!file.exists()) return;
        String absolute = file.getAbsolutePath();
        if (!paths.contains(absolute)) {
            paths.add(absolute);
        }
    }

    @NonNull
    private static String joinPathList(@NonNull List<String> paths) {
        StringBuilder out = new StringBuilder();
        for (String path : paths) {
            if (path == null || path.trim().isEmpty()) continue;
            if (out.length() > 0) out.append(':');
            out.append(path);
        }
        return out.toString();
    }

    public static void normalizeRuntimeDirIfNeeded(@NonNull File runtimeDir) throws IOException {
        if (!runtimeDir.isDirectory()) return;

        if (looksLikeRuntimeRoot(runtimeDir)) {
            return;
        }

        File[] children = runtimeDir.listFiles();
        if (children == null || children.length != 1 || !children[0].isDirectory()) {
            return;
        }

        File wrapper = children[0];
        if (!looksLikeRuntimeRoot(wrapper)) {
            return;
        }

        File parent = runtimeDir.getParentFile();
        if (parent == null) return;
        File tempMoveDir = new File(parent, runtimeDir.getName() + ".normalized");
        PathManager.deleteQuietly(tempMoveDir);
        if (!tempMoveDir.mkdirs()) {
            throw new IOException("Unable to create normalize temp dir: " + tempMoveDir.getAbsolutePath());
        }

        File[] wrapperChildren = wrapper.listFiles();
        if (wrapperChildren != null) {
            for (File child : wrapperChildren) {
                File target = new File(tempMoveDir, child.getName());
                if (!child.renameTo(target)) {
                    copyDirectory(child, target);
                }
            }
        }

        PathManager.deleteQuietly(runtimeDir);
        if (!tempMoveDir.renameTo(runtimeDir)) {
            copyDirectory(tempMoveDir, runtimeDir);
            PathManager.deleteQuietly(tempMoveDir);
        }
    }

    private static boolean looksLikeRuntimeRoot(@NonNull File dir) {
        return new File(dir, "bin").isDirectory()
                || new File(dir, "lib/rt.jar").isFile()
                || new File(dir, "lib/rt.jar.pack").isFile()
                || new File(dir, "jre/lib/rt.jar").isFile()
                || new File(dir, "jre/lib/rt.jar.pack").isFile()
                || new File(dir, "lib/modules").isFile()
                || findFileNamed(dir, "rt.jar", 4) != null
                || findFileNamed(dir, "rt.jar.pack", 4) != null;
    }

    @Nullable
    private static File findFileNamed(@NonNull File root, @NonNull String name, int depthLeft) {
        if (depthLeft < 0 || !root.isDirectory()) return null;

        File direct = new File(root, name);
        if (direct.isFile()) return direct;

        File[] children = root.listFiles();
        if (children == null) return null;

        for (File child : children) {
            if (child.isDirectory()) {
                File found = findFileNamed(child, name, depthLeft - 1);
                if (found != null) return found;
            } else if (child.isFile() && child.getName().equals(name)) {
                return child;
            }
        }

        return null;
    }



    private static void extractZip(@NonNull InputStream source, @NonNull File destinationDir) throws IOException {
        try (ZipInputStream zipInput = new ZipInputStream(source)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zipInput.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.trim().isEmpty()) {
                    zipInput.closeEntry();
                    continue;
                }

                File outputFile = safeResolve(destinationDir, name);
                if (entry.isDirectory()) {
                    if (!outputFile.exists() && !outputFile.mkdirs()) {
                        throw new IOException("Unable to create directory: " + outputFile.getAbsolutePath());
                    }
                    zipInput.closeEntry();
                    continue;
                }

                File parent = outputFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Unable to create directory: " + parent.getAbsolutePath());
                }

                try (OutputStream output = new FileOutputStream(outputFile)) {
                    int read;
                    while ((read = zipInput.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                }

                String normalizedName = name.replace('\\', '/');
                if (normalizedName.contains("/bin/")
                        || normalizedName.startsWith("bin/")
                        || normalizedName.endsWith("/java")
                        || normalizedName.endsWith("/java.real")) {
                    //noinspection ResultOfMethodCallIgnored
                    outputFile.setExecutable(true, false);
                }
                //noinspection ResultOfMethodCallIgnored
                outputFile.setReadable(true, false);
                //noinspection ResultOfMethodCallIgnored
                outputFile.setWritable(true, true);
                zipInput.closeEntry();
            }
        }
    }

    private static void extractTarXz(@NonNull InputStream source, @NonNull File destinationDir) throws IOException {
        extractTarXz(source, destinationDir, null, "runtime archive");
    }

    private static void extractTarXz(
            @NonNull InputStream source,
            @NonNull File destinationDir,
            @Nullable ProgressCallback progressCallback,
            @NonNull String archiveLabel
    ) throws IOException {
        File parent = destinationDir.getParentFile();
        if (parent == null) parent = destinationDir;
        File tempArchive = File.createTempFile("runtime-pack-", ".tar.xz", parent);
        try {
            reportProgress(progressCallback, "Preparing " + archiveLabel + "...");
            try (InputStream input = source; OutputStream output = new FileOutputStream(tempArchive)) {
                copyArchiveWithProgress(input, output, progressCallback, archiveLabel);
            }

            reportProgress(progressCallback, "Extracting " + archiveLabel + "...");
            try {
                extractTarXzWithOptionalLibraries(tempArchive, destinationDir, progressCallback, archiveLabel);
            } catch (ReflectiveOperationException | LinkageError missingLibrary) {
                Logging.i(TAG, "In-process XZ extraction unavailable; using bounded Android tar fallback for "
                        + archiveLabel + ": " + missingLibrary);
                extractTarXzWithSystemTar(
                        tempArchive,
                        destinationDir,
                        missingLibrary,
                        progressCallback,
                        archiveLabel
                );
            }
            reportProgress(progressCallback, "Finished extracting " + archiveLabel + ".");
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tempArchive.delete();
        }
    }

    private static void copyArchiveWithProgress(
            @NonNull InputStream input,
            @NonNull OutputStream output,
            @Nullable ProgressCallback progressCallback,
            @NonNull String archiveLabel
    ) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long copied = 0L;
        long lastProgressNanos = System.nanoTime();
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            copied += read;

            long now = System.nanoTime();
            if (elapsedMillis(lastProgressNanos, now) >= ARCHIVE_PROGRESS_INTERVAL_MS) {
                reportProgress(progressCallback, "Preparing " + archiveLabel + " ("
                        + formatByteCount(copied) + ")...");
                lastProgressNanos = now;
            }
        }
        output.flush();
        reportProgress(progressCallback, "Prepared " + archiveLabel + " ("
                + formatByteCount(copied) + ").");
    }

    private static void extractTarXzWithOptionalLibraries(
            @NonNull File archive,
            @NonNull File destinationDir,
            @Nullable ProgressCallback progressCallback,
            @NonNull String archiveLabel
    ) throws ReflectiveOperationException, IOException {
        Class<?> tarInputStreamClass = Class.forName(
                "org.apache.commons.compress.archivers.tar.TarArchiveInputStream"
        );
        Class<?> xzInputStreamClass;
        try {
            xzInputStreamClass = Class.forName("org.tukaani.xz.XZInputStream");
        } catch (ClassNotFoundException tukaaniMissing) {
            try {
                xzInputStreamClass = Class.forName(
                        "org.apache.commons.compress.compressors.xz.XZCompressorInputStream"
                );
            } catch (ClassNotFoundException commonsXzMissing) {
                commonsXzMissing.addSuppressed(tukaaniMissing);
                throw commonsXzMissing;
            }
        }

        Constructor<?> xzConstructor = xzInputStreamClass.getConstructor(InputStream.class);
        Constructor<?> tarConstructor = tarInputStreamClass.getConstructor(InputStream.class);
        Method getNextTarEntry;
        try {
            getNextTarEntry = tarInputStreamClass.getMethod("getNextTarEntry");
        } catch (NoSuchMethodException legacyMethodMissing) {
            getNextTarEntry = tarInputStreamClass.getMethod("getNextEntry");
        }

        int extractedEntries = 0;
        long lastProgressNanos = System.nanoTime();
        try (InputStream fileInput = new FileInputStream(archive)) {
            InputStream xzInput = (InputStream) xzConstructor.newInstance(fileInput);
            try (InputStream tarInput = (InputStream) tarConstructor.newInstance(xzInput)) {
                Object entry;
                while ((entry = getNextTarEntry.invoke(tarInput)) != null) {
                    extractTarEntry(tarInput, entry, destinationDir);
                    extractedEntries++;

                    long now = System.nanoTime();
                    if (elapsedMillis(lastProgressNanos, now) >= ARCHIVE_PROGRESS_INTERVAL_MS) {
                        reportProgress(progressCallback, "Extracting " + archiveLabel + " ("
                                + extractedEntries + " entries)...");
                        lastProgressNanos = now;
                    }
                }
            }
        }

        reportProgress(progressCallback, "Extracted " + extractedEntries + " entries from "
                + archiveLabel + ".");
    }

    private static void extractTarEntry(@NonNull InputStream tarInput, @NonNull Object entry, @NonNull File destinationDir)
            throws ReflectiveOperationException, IOException {
        Class<?> entryClass = entry.getClass();
        String name = (String) entryClass.getMethod("getName").invoke(entry);
        boolean directory = (Boolean) entryClass.getMethod("isDirectory").invoke(entry);
        boolean symbolicLink = (Boolean) entryClass.getMethod("isSymbolicLink").invoke(entry);
        int mode = (Integer) entryClass.getMethod("getMode").invoke(entry);

        File outputFile = safeResolve(destinationDir, name);

        if (directory) {
            if (!outputFile.exists() && !outputFile.mkdirs()) {
                throw new IOException("Unable to create directory: " + outputFile.getAbsolutePath());
            }
            return;
        }

        File parent = outputFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create directory: " + parent.getAbsolutePath());
        }

        if (symbolicLink) {
            String target = (String) entryClass.getMethod("getLinkName").invoke(entry);
            createSymbolicLinkIfPossible(outputFile, target);
            return;
        }

        try (OutputStream output = new FileOutputStream(outputFile)) {
            Tools.copy(tarInput, output);
        }

        if ((mode & 0111) != 0) {
            //noinspection ResultOfMethodCallIgnored
            outputFile.setExecutable(true, false);
        }
        //noinspection ResultOfMethodCallIgnored
        outputFile.setReadable(true, false);
        //noinspection ResultOfMethodCallIgnored
        outputFile.setWritable(true, true);
    }

    private static void createSymbolicLinkIfPossible(@NonNull File link, @NonNull String target) throws IOException {
        if (link.exists()) {
            //noinspection ResultOfMethodCallIgnored
            link.delete();
        }

        try {
            Class<?> osClass = Class.forName("android.system.Os");
            Method symlink = osClass.getMethod("symlink", String.class, String.class);
            symlink.invoke(null, target, link.getAbsolutePath());
        } catch (Throwable ignored) {
            writeText(link, target);
        }
    }

    private static File safeResolve(@NonNull File destinationDir, @NonNull String entryName) throws IOException {
        String cleanName = entryName;
        while (cleanName.startsWith("./")) {
            cleanName = cleanName.substring(2);
        }

        File outputFile = new File(destinationDir, cleanName);
        String destinationPath = destinationDir.getCanonicalPath();
        String outputPath = outputFile.getCanonicalPath();

        if (!outputPath.equals(destinationPath) && !outputPath.startsWith(destinationPath + File.separator)) {
            throw new IOException("Blocked unsafe tar entry: " + entryName);
        }

        return outputFile;
    }

    private static void extractTarXzWithSystemTar(
            @NonNull File archive,
            @NonNull File destinationDir,
            @NonNull Throwable missingLibrary,
            @Nullable ProgressCallback progressCallback,
            @NonNull String archiveLabel
    ) throws IOException {
        IOException first = runTarCommand(new String[]{
                "tar", "-xJf", archive.getAbsolutePath(), "-C", destinationDir.getAbsolutePath()
        }, progressCallback, "Android tar is extracting " + archiveLabel);
        if (first == null) return;

        reportProgress(progressCallback, "Primary Android tar failed; trying toybox tar...");
        IOException second = runTarCommand(new String[]{
                "toybox", "tar", "-xJf", archive.getAbsolutePath(), "-C", destinationDir.getAbsolutePath()
        }, progressCallback, "Toybox tar is extracting " + archiveLabel);
        if (second == null) return;

        IOException error = new IOException(
                "Unable to extract .tar.xz runtime pack. The in-process XZ libraries were unavailable "
                        + "and both Android tar commands failed or timed out."
        );
        error.addSuppressed(missingLibrary);
        error.addSuppressed(first);
        error.addSuppressed(second);
        throw error;
    }

    private static IOException runTarCommand(
            @NonNull String[] command,
            @Nullable ProgressCallback progressCallback,
            @NonNull String progressLabel
    ) {
        try {
            ProcessResult result = runProcess(
                    new ProcessBuilder(command).redirectErrorStream(true),
                    progressLabel,
                    TAR_PROCESS_TIMEOUT_MS,
                    progressCallback
            );
            if (result.exitCode == 0) return null;

            return new IOException("Command failed with exit " + result.exitCode + ": "
                    + joinCommand(command) + "\n" + result.output);
        } catch (Throwable e) {
            return new IOException("Command failed: " + joinCommand(command), e);
        }
    }

    @NonNull
    private static ProcessResult runProcess(
            @NonNull ProcessBuilder processBuilder,
            @NonNull String label,
            long timeoutMs,
            @Nullable ProgressCallback progressCallback
    ) throws IOException {
        Process process = null;
        Thread outputThread = null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicReference<Throwable> outputFailure = new AtomicReference<>();

        try {
            process = processBuilder.start();
            Process startedProcess = process;
            outputThread = new Thread(
                    () -> drainProcessOutput(startedProcess.getInputStream(), output, outputFailure),
                    "DroidBridge process output"
            );
            outputThread.setDaemon(true);
            outputThread.start();

            int exitCode = waitForProcess(process, label, timeoutMs, progressCallback);
            outputThread.join(2_000L);
            if (outputThread.isAlive()) {
                // A child process can inherit stdout after the parent exits. Closing the
                // pipe here prevents that child from keeping the installer blocked.
                closeQuietly(process.getInputStream());
                outputThread.join(250L);
            }

            Throwable drainFailure = outputFailure.get();
            if (drainFailure != null && exitCode != 0) {
                Logging.e(TAG, "Failed while reading output from " + label, drainFailure);
            }

            return new ProcessResult(exitCode, snapshotProcessOutput(output));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running " + label, interrupted);
        } finally {
            if (process != null) {
                terminateProcess(process);
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
                closeQuietly(process.getOutputStream());
            }
            if (outputThread != null && outputThread.isAlive()) {
                outputThread.interrupt();
            }
        }
    }

    private static int waitForProcess(
            @NonNull Process process,
            @NonNull String label,
            long timeoutMs,
            @Nullable ProgressCallback progressCallback
    ) throws IOException, InterruptedException {
        long startNanos = System.nanoTime();
        long lastProgressNanos = startNanos;

        while (true) {
            try {
                return process.exitValue();
            } catch (IllegalThreadStateException stillRunning) {
                // Keep polling so vendor processes cannot block the installer forever.
            }

            long now = System.nanoTime();
            long elapsedMs = elapsedMillis(startNanos, now);
            if (elapsedMs >= timeoutMs) {
                terminateProcess(process);
                throw new IOException(label + " timed out after " + (timeoutMs / 1_000L) + " seconds");
            }

            if (elapsedMillis(lastProgressNanos, now) >= PROCESS_PROGRESS_INTERVAL_MS) {
                reportProgress(progressCallback, label + " (" + (elapsedMs / 1_000L) + "s)...");
                lastProgressNanos = now;
            }

            Thread.sleep(100L);
        }
    }

    private static void drainProcessOutput(
            @NonNull InputStream input,
            @NonNull ByteArrayOutputStream output,
            @NonNull AtomicReference<Throwable> outputFailure
    ) {
        byte[] buffer = new byte[4 * 1024];
        try (InputStream processOutput = input) {
            int read;
            while ((read = processOutput.read(buffer)) != -1) {
                synchronized (output) {
                    int remaining = MAX_PROCESS_OUTPUT_BYTES - output.size();
                    if (remaining > 0) {
                        output.write(buffer, 0, Math.min(read, remaining));
                    }
                }
            }
        } catch (Throwable throwable) {
            outputFailure.compareAndSet(null, throwable);
        }
    }

    @NonNull
    private static String snapshotProcessOutput(@NonNull ByteArrayOutputStream output) {
        synchronized (output) {
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void terminateProcess(@NonNull Process process) {
        try {
            process.destroy();
        } catch (Throwable ignored) {
        }

        try {
            process.exitValue();
            return;
        } catch (IllegalThreadStateException stillRunning) {
            // Some Android Process implementations need an explicit forced destroy.
        }

        try {
            Method destroyForcibly = Process.class.getMethod("destroyForcibly");
            destroyForcibly.invoke(process);
        } catch (Throwable ignored) {
        }
    }

    private static void closeQuietly(@Nullable java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Throwable ignored) {
        }
    }

    private static void reportProgress(
            @Nullable ProgressCallback progressCallback,
            @NonNull String message
    ) {
        if (progressCallback == null) return;
        try {
            progressCallback.onProgress(message);
        } catch (Throwable callbackFailure) {
            Logging.e(TAG, "Runtime progress callback failed", callbackFailure);
        }
    }

    private static long elapsedMillis(long startNanos, long endNanos) {
        return (endNanos - startNanos) / 1_000_000L;
    }

    @NonNull
    private static String formatByteCount(long bytes) {
        long megabyte = 1024L * 1024L;
        if (bytes >= megabyte) {
            return (bytes / megabyte) + " MB";
        }
        if (bytes >= 1024L) {
            return (bytes / 1024L) + " KB";
        }
        return bytes + " B";
    }

    private static final class ProcessResult {
        final int exitCode;
        final String output;

        ProcessResult(int exitCode, @NonNull String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private static String joinCommand(@NonNull String[] command) {
        StringBuilder builder = new StringBuilder();
        for (String part : command) {
            if (builder.length() > 0) builder.append(' ');
            builder.append(part);
        }
        return builder.toString();
    }

    private static void writeText(@NonNull File file, @NonNull String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create directory: " + parent.getAbsolutePath());
        }

        try (OutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void copyDirectory(@NonNull File source, @NonNull File destination) throws IOException {
        if (source.isDirectory()) {
            if (!destination.exists() && !destination.mkdirs()) {
                throw new IOException("Unable to create directory: " + destination.getAbsolutePath());
            }

            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyDirectory(child, new File(destination, child.getName()));
                }
            }
        } else {
            File parent = destination.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Unable to create directory: " + parent.getAbsolutePath());
            }

            try (InputStream input = new FileInputStream(source); OutputStream output = new FileOutputStream(destination)) {
                Tools.copy(input, output);
            }

            //noinspection ResultOfMethodCallIgnored
            destination.setExecutable(source.canExecute(), false);
        }
    }
}

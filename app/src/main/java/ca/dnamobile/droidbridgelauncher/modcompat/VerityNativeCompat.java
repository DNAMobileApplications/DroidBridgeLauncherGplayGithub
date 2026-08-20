/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 */

package ca.dnamobile.droidbridgelauncher.modcompat;

import android.content.Context;

import androidx.annotation.NonNull;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Makes Android ARM64 Sherpa/ONNX libraries bundled by Verity visible to the
 * embedded OpenJDK before it starts.
 *
 * Verity 6.x has shipped speech-native JARs in more than one layout. Some of
 * those JARs also contain Linux AArch64 binaries with the same file names as
 * the Android binaries. Selecting by CPU architecture alone is therefore not
 * safe: a Linux AArch64 .so is valid ELF64/AArch64 but cannot run on Android.
 */
public final class VerityNativeCompat {
    private static final String TAG = "VerityNativeCompat";
    private static final String LAYOUT_REVISION = "verity-android-arm64-packaged-sherpa-v5";
    private static final String LAYOUT_MARKER = ".droidbridge-verity-native-layout";
    private static final long MAX_NESTED_JAR_BYTES = 96L * 1024L * 1024L;
    private static final long MAX_NATIVE_BYTES = 256L * 1024L * 1024L;
    private static final int COPY_BUFFER = 64 * 1024;
    private static final int BINARY_SCAN_LIMIT = 4 * 1024 * 1024;

    private VerityNativeCompat() {
    }

    public static synchronized void prepare(@NonNull Context context, @NonNull File gameDirectory) {
        File outputDirectory = PathManager.DIR_RUNTIME_MOD;
        if (outputDirectory == null) return;
        if (!outputDirectory.isDirectory() && !outputDirectory.mkdirs()) {
            Logging.i(TAG, "Unable to create runtime native directory: " + outputDirectory);
            return;
        }

        ArrayList<File> jars = findVerityJars(gameDirectory);
        if (jars.isEmpty()) return;
        Collections.sort(jars, Comparator.comparing(File::getAbsolutePath));

        try {
            String fingerprint = buildFingerprint(jars);
            File marker = new File(outputDirectory, LAYOUT_MARKER);
            String expectedMarker = LAYOUT_REVISION + "\n" + fingerprint;
            if (marker.isFile()
                    && expectedMarker.equals(readSmallText(marker))
                    && hasUsableSpeechRuntime(outputDirectory)) {
                return;
            }

            Logging.i(TAG, "Refreshing Verity Android speech natives layout=" + LAYOUT_REVISION);
            clearSpeechRuntime(outputDirectory);

            File stagingDirectory = new File(outputDirectory, ".verity-native-staging");
            deleteRecursively(stagingDirectory);
            if (!stagingDirectory.mkdirs()) {
                throw new IllegalStateException("Unable to create Verity native staging directory");
            }

            Map<String, NativeCandidate> candidates = new HashMap<>();
            collectPackagedAndroidSherpa(context, stagingDirectory, candidates);
            for (File jar : jars) {
                collectJarCandidates(jar, stagingDirectory, candidates);
            }

            int installed = installCandidates(candidates, outputDirectory);
            deleteRecursively(stagingDirectory);

            boolean runtimeReady = hasUsableSpeechRuntime(outputDirectory);
            if (runtimeReady) {
                writeTextAtomically(marker, expectedMarker);
            } else {
                try { marker.delete(); } catch (Throwable ignored) {}
            }

            Logging.i(TAG, "Prepared Verity Android speech runtime installed=" + installed
                    + " candidates=" + candidates.size()
                    + " runtimeReady=" + runtimeReady
                    + " runtimePath=" + outputDirectory.getAbsolutePath());
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to prepare Verity Android speech runtime", throwable);
        }
    }

    @NonNull
    private static ArrayList<File> findVerityJars(@NonNull File gameDirectory) {
        ArrayList<File> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        addVerityJars(result, seen, new File(gameDirectory, "mods"));
        File parent = gameDirectory.getParentFile();
        if (parent != null) addVerityJars(result, seen, new File(parent, "mods"));
        return result;
    }

    private static void addVerityJars(
            @NonNull ArrayList<File> out,
            @NonNull Set<String> seen,
            @NonNull File directory
    ) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".jar") || !name.contains("verity")) continue;
            try {
                String key = file.getCanonicalPath();
                if (seen.add(key)) out.add(file);
            } catch (Throwable ignored) {
                if (seen.add(file.getAbsolutePath())) out.add(file);
            }
        }
    }

    private static void collectPackagedAndroidSherpa(
            @NonNull Context context,
            @NonNull File stagingDirectory,
            @NonNull Map<String, NativeCandidate> candidates
    ) {
        File nativeDirectory = null;
        try {
            if (context.getApplicationInfo() != null
                    && context.getApplicationInfo().nativeLibraryDir != null) {
                nativeDirectory = new File(context.getApplicationInfo().nativeLibraryDir);
            }
            File packaged = nativeDirectory == null
                    ? null
                    : new File(nativeDirectory, "libsherpa-onnx-jni.so");
            if (packaged == null || !packaged.isFile() || packaged.length() <= 0L) {
                Logging.i(TAG, "Packaged Verity Sherpa JNI is missing from APK native directory: "
                        + (nativeDirectory == null ? "<unknown>" : nativeDirectory.getAbsolutePath()));
                return;
            }
            try (InputStream input = new FileInputStream(packaged)) {
                collectCandidate(input,
                        "android-apk!/arm64-v8a/libsherpa-onnx-jni.so",
                        stagingDirectory,
                        candidates);
            }
            Logging.i(TAG, "Found packaged Verity Sherpa JNI bytes=" + packaged.length()
                    + " source=" + packaged.getAbsolutePath());
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to prepare packaged Verity Sherpa JNI", throwable);
        }
    }

    private static void collectJarCandidates(
            @NonNull File jar,
            @NonNull File stagingDirectory,
            @NonNull Map<String, NativeCandidate> candidates
    ) throws Exception {
        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry == null || entry.isDirectory()) continue;
                String entryName = entry.getName();
                String lower = entryName.toLowerCase(Locale.ROOT);

                if (lower.endsWith(".so") && isSpeechNativeLibrary(new File(lower).getName())) {
                    try (InputStream input = zip.getInputStream(entry)) {
                        collectCandidate(input, jar.getName() + "!/" + entryName,
                                stagingDirectory, candidates);
                    }
                    continue;
                }

                long size = entry.getSize();
                if (lower.endsWith(".jar")
                        && isSpeechNativeContainer(lower)
                        && size > 0L
                        && size <= MAX_NESTED_JAR_BYTES) {
                    try (InputStream input = zip.getInputStream(entry)) {
                        byte[] nested = readLimited(input, MAX_NESTED_JAR_BYTES);
                        collectNestedJarCandidates(nested, jar.getName() + "!/" + entryName,
                                stagingDirectory, candidates);
                    }
                }
            }
        }
    }

    private static void collectNestedJarCandidates(
            @NonNull byte[] bytes,
            @NonNull String containerName,
            @NonNull File stagingDirectory,
            @NonNull Map<String, NativeCandidate> candidates
    ) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    String entryName = entry.getName();
                    String lower = entryName.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".so")
                            && isSpeechNativeLibrary(new File(lower).getName())) {
                        collectCandidate(zip, containerName + "!/" + entryName,
                                stagingDirectory, candidates);
                    }
                }
                zip.closeEntry();
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to inspect nested Verity native JAR " + containerName, throwable);
        }
    }

    private static void collectCandidate(
            @NonNull InputStream input,
            @NonNull String sourceName,
            @NonNull File stagingDirectory,
            @NonNull Map<String, NativeCandidate> candidates
    ) {
        String fileName = new File(sourceName.substring(sourceName.lastIndexOf("!/") + 2)).getName();
        if (!isSpeechNativeLibrary(fileName)) return;

        File temporary = null;
        try {
            temporary = File.createTempFile("verity-", ".so", stagingDirectory);
            long total = copyLimited(input, temporary, MAX_NATIVE_BYTES);
            if (total <= 0L || !isAarch64Elf(temporary)) {
                temporary.delete();
                return;
            }

            int score = scoreAndroidCompatibility(sourceName, temporary);
            if (score < 0) {
                Logging.i(TAG, "Rejected non-Android Verity native " + sourceName);
                temporary.delete();
                return;
            }

            NativeCandidate previous = candidates.get(fileName);
            if (previous != null && previous.score >= score) {
                temporary.delete();
                return;
            }
            if (previous != null) previous.file.delete();

            candidates.put(fileName, new NativeCandidate(fileName, sourceName, temporary, score));
            Logging.i(TAG, "Selected Verity native candidate name=" + fileName
                    + " score=" + score + " source=" + sourceName);
        } catch (Throwable throwable) {
            if (temporary != null) temporary.delete();
            Logging.e(TAG, "Unable to inspect Verity native " + sourceName, throwable);
        }
    }

    private static int installCandidates(
            @NonNull Map<String, NativeCandidate> candidates,
            @NonNull File outputDirectory
    ) throws Exception {
        ArrayList<NativeCandidate> ordered = new ArrayList<>(candidates.values());
        Collections.sort(ordered, Comparator.comparing(candidate -> candidate.fileName));
        int installed = 0;
        for (NativeCandidate candidate : ordered) {
            File target = new File(outputDirectory, candidate.fileName);
            if (target.exists() && !target.delete()) {
                throw new IllegalStateException("Unable to replace " + target);
            }
            if (!candidate.file.renameTo(target)) {
                copyFile(candidate.file, target);
                candidate.file.delete();
            }
            target.setReadable(true, false);
            target.setExecutable(true, false);
            target.setWritable(true, true);
            installed++;
            Logging.i(TAG, "Installed Verity Android native " + candidate.fileName
                    + " bytes=" + target.length()
                    + " score=" + candidate.score
                    + " source=" + candidate.sourceName);
        }
        return installed;
    }

    private static int scoreAndroidCompatibility(@NonNull String sourceName, @NonNull File file) {
        String source = sourceName.toLowerCase(Locale.ROOT).replace('\\', '/');
        if (source.contains("x86") || source.contains("amd64")
                || source.contains("windows") || source.contains("win32")
                || source.contains("darwin") || source.contains("macos")) {
            return -1;
        }

        int score = 10;
        if (source.contains("arm64-v8a")
                || source.contains("android-aarch64")
                || source.contains("aarch64-linux-android")
                || source.contains("android/arm64")
                || source.contains("aarch64/android")) {
            score += 500;
        } else if (source.contains("android")) {
            score += 350;
        } else if (source.contains("aarch64") || source.contains("arm64")) {
            score += 80;
        }

        if (source.contains("manylinux") || source.contains("linux-aarch64")
                || source.contains("linux_aarch64") || source.contains("linux/arm64")
                || source.contains("linux/aarch64") || source.contains("musl")
                || source.contains("gnu")) {
            score -= 600;
        }

        BinaryHints hints = inspectBinaryHints(file);
        if (hints.linuxGlibc) return -1;
        if (hints.android) score += 500;
        if (hints.libcxxShared) score += 80;
        return score;
    }

    @NonNull
    private static BinaryHints inspectBinaryHints(@NonNull File file) {
        try (FileInputStream input = new FileInputStream(file)) {
            int size = (int) Math.min(file.length(), BINARY_SCAN_LIMIT);
            byte[] bytes = new byte[size];
            int offset = 0;
            while (offset < size) {
                int read = input.read(bytes, offset, size - offset);
                if (read < 0) break;
                offset += read;
            }
            String text = new String(bytes, 0, offset, StandardCharsets.ISO_8859_1);
            boolean glibc = text.contains("GLIBC_")
                    || text.contains("ld-linux-aarch64")
                    || text.contains("libstdc++.so.6")
                    || text.contains("libgcc_s.so.1");
            boolean android = text.contains("libandroid.so")
                    || text.contains("liblog.so")
                    || text.contains("Android")
                    || text.contains("android_dlopen_ext");
            boolean libcxx = text.contains("libc++_shared.so");
            return new BinaryHints(glibc, android, libcxx);
        } catch (Throwable ignored) {
            return new BinaryHints(false, false, false);
        }
    }

    private static boolean hasUsableSpeechRuntime(@NonNull File outputDirectory) {
        File[] files = outputDirectory.listFiles();
        if (files == null) return false;
        boolean foundSherpa = false;
        boolean foundOnnx = false;
        for (File file : files) {
            if (file == null || !file.isFile() || file.length() <= 0L) continue;
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (!isSpeechNativeLibrary(name) || !isAarch64Elf(file)) continue;
            if (scoreAndroidCompatibility(file.getName(), file) < 0) continue;
            if (name.contains("sherpa")) foundSherpa = true;
            if (name.contains("onnx")) foundOnnx = true;
        }
        // Some Sherpa JNI distributions statically include ONNX Runtime.
        return foundSherpa || foundOnnx;
    }

    private static void clearSpeechRuntime(@NonNull File outputDirectory) {
        File[] files = outputDirectory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file == null) continue;
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (isSpeechNativeLibrary(name)
                    || name.startsWith(".verity-native-")
                    || name.equals(LAYOUT_MARKER)
                    || name.startsWith(".verity-native-staging")) {
                deleteRecursively(file);
            }
        }
    }

    private static boolean isSpeechNativeContainer(@NonNull String path) {
        return path.contains("sherpa")
                || path.contains("onnx")
                || path.contains("kaldi")
                || path.contains("piper")
                || path.contains("espeak");
    }

    private static boolean isSpeechNativeLibrary(@NonNull String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".so")) return false;
        return lower.contains("sherpa")
                || lower.contains("onnx")
                || lower.contains("kaldi")
                || lower.contains("piper")
                || lower.contains("espeak");
    }

    private static boolean isAarch64Elf(@NonNull File file) {
        byte[] header = new byte[20];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < header.length) {
                int read = input.read(header, offset, header.length - offset);
                if (read < 0) break;
                offset += read;
            }
            if (offset < header.length) return false;
            if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F') return false;
            if (header[4] != 2 || header[5] != 1) return false;
            int machine = (header[18] & 0xff) | ((header[19] & 0xff) << 8);
            return machine == 183;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static long copyLimited(
            @NonNull InputStream input,
            @NonNull File target,
            long maximum
    ) throws Exception {
        long total = 0L;
        try (FileOutputStream output = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[COPY_BUFFER];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maximum) throw new IllegalStateException("Native library exceeds limit");
                output.write(buffer, 0, read);
            }
            output.flush();
        }
        return total;
    }

    @NonNull
    private static byte[] readLimited(@NonNull InputStream input, long maximum) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[COPY_BUFFER];
        int read;
        long total = 0L;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maximum) throw new IllegalStateException("Nested JAR exceeds limit");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void copyFile(@NonNull File source, @NonNull File target) throws Exception {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[COPY_BUFFER];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            output.flush();
        }
    }

    @NonNull
    private static String buildFingerprint(@NonNull ArrayList<File> jars) throws Exception {
        StringBuilder builder = new StringBuilder();
        for (File jar : jars) {
            builder.append(jar.getCanonicalPath())
                    .append(':').append(jar.length())
                    .append(':').append(jar.lastModified())
                    .append('\n');
        }
        return shortHash(builder.toString());
    }

    @NonNull
    private static String shortHash(@NonNull String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder(16);
        for (int i = 0; i < 8; i++) builder.append(String.format(Locale.ROOT, "%02x", bytes[i]));
        return builder.toString();
    }

    @NonNull
    private static String readSmallText(@NonNull File file) {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = readLimited(input, 4096L);
            return new String(bytes, StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void writeTextAtomically(@NonNull File file, @NonNull String value) throws Exception {
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
        if (file.exists() && !file.delete()) throw new IllegalStateException("Unable to replace " + file);
        if (!temporary.renameTo(file)) {
            copyFile(temporary, file);
            temporary.delete();
        }
    }

    private static void deleteRecursively(@NonNull File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        try { file.delete(); } catch (Throwable ignored) {}
    }

    private static final class NativeCandidate {
        final String fileName;
        final String sourceName;
        final File file;
        final int score;

        NativeCandidate(String fileName, String sourceName, File file, int score) {
            this.fileName = fileName;
            this.sourceName = sourceName;
            this.file = file;
            this.score = score;
        }
    }

    private static final class BinaryHints {
        final boolean linuxGlibc;
        final boolean android;
        final boolean libcxxShared;

        BinaryHints(boolean linuxGlibc, boolean android, boolean libcxxShared) {
            this.linuxGlibc = linuxGlibc;
            this.android = android;
            this.libcxxShared = libcxxShared;
        }
    }
}

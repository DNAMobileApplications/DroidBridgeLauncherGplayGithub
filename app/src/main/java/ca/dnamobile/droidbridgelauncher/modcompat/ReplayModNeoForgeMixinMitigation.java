/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, ReplayMod, NeoForge, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.modcompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

/**
 * Repairs affected NeoForge Replay Mod 26.1 builds before FML scans the mods
 * directory. Those builds contain accessor mixin classes which Replay Mod code
 * uses during video rendering, but omit the accessors from the generated mixin
 * JSON. NeoForge then rejects the late class load with IllegalClassLoadError.
 *
 * This mitigation is intentionally narrow. A jar must look like Replay Mod and
 * NeoForge, the relevant mixin JSON must own the expected package, and the
 * accessor class must physically exist before DroidBridge adds its registration.
 */
public final class ReplayModNeoForgeMixinMitigation {
    private static final String TAG = "ReplayModNeoForgeMixinFix";
    private static final String MARKER_ENTRY =
            "META-INF/droidbridge-replaymod-neoforge-mixin-fix-v2.txt";
    private static final String REFORGEDPLAY_TINYEXR_MARKER_ENTRY =
            "META-INF/droidbridge-reforgedplay-lwjgl-tinyexr-fix-v1.txt";

    private static final PatchSpec[] PATCH_SPECS = new PatchSpec[] {
            new PatchSpec(
                    "mixins.core.replaymod.json",
                    "com.replaymod.core.mixin",
                    "BlockableEventLoopAccessor",
                    "com/replaymod/core/mixin/BlockableEventLoopAccessor.class"
            ),
            new PatchSpec(
                    "mixins.render.replaymod.json",
                    "com.replaymod.render.mixin",
                    "ChunkRenderingDataPreparerAccessor",
                    "com/replaymod/render/mixin/ChunkRenderingDataPreparerAccessor.class"
            )
    };

    private ReplayModNeoForgeMixinMitigation() {
    }

    /**
     * @return a short message suitable for latestlog.txt when a NeoForge Replay
     * Mod jar was found, or null when there is nothing for this mitigation to do.
     */
    @Nullable
    public static String prepare(@Nullable File gameDirectory, @Nullable String launchVersionId) {
        if (gameDirectory == null) return null;

        boolean replayJarSeen = false;
        try {
            for (File modsDir : getCandidateModsDirs(gameDirectory)) {
                if (!modsDir.isDirectory()) continue;

                File[] jars = modsDir.listFiles(file -> file != null
                        && file.isFile()
                        && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
                if (jars == null) continue;

                for (File jar : jars) {
                    if (!looksLikeReplayMod(jar.getName())) continue;
                    if (!looksLikeNeoForgeReplayMod(jar, launchVersionId)) continue;
                    replayJarSeen = true;

                    if (looksLikeReForgedPlay(jar.getName())) {
                        TinyExrPatchResult tinyExrResult =
                                patchReForgedPlayTinyExrIfNeeded(gameDirectory, jar);
                        switch (tinyExrResult) {
                            case PATCHED:
                                return "ReForgedPlay NeoForge: repaired embedded LWJGL TinyEXR "
                                        + "module compatibility in " + jar.getName();
                            case ALREADY_CORRECT:
                                return "ReForgedPlay NeoForge: embedded LWJGL TinyEXR "
                                        + "module compatibility verified in " + jar.getName();
                            case TINYEXR_NOT_PRESENT:
                                return "Warning: ReForgedPlay was detected but its embedded "
                                        + "lwjgl-tinyexr library was not found in " + jar.getName();
                        }
                    }

                    PatchResult result = patchJarIfNeeded(gameDirectory, jar);
                    switch (result) {
                        case PATCHED:
                            return "Replay Mod NeoForge: repaired missing video-render accessor mixins in "
                                    + jar.getName();
                        case ALREADY_CORRECT:
                            return "Replay Mod NeoForge: video-render accessor mixins verified in "
                                    + jar.getName();
                        case MISSING_REQUIRED_CLASS:
                            return "Warning: Replay Mod NeoForge fix skipped because the affected accessor "
                                    + "class is missing from " + jar.getName();
                        case INVALID_CONFIG:
                            return "Warning: Replay Mod NeoForge fix could not validate the mixin JSON in "
                                    + jar.getName();
                        case NOT_REPLAY_MOD:
                            break;
                    }
                }
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to repair Replay Mod NeoForge mixin configuration", throwable);
            return "Warning: Replay Mod NeoForge compatibility repair failed: "
                    + throwable.getClass().getSimpleName()
                    + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage());
        }

        return replayJarSeen
                ? "Warning: Replay Mod NeoForge jar was found but no compatible render fix was applied"
                : null;
    }

    @NonNull
    private static PatchResult patchJarIfNeeded(
            @NonNull File gameDirectory,
            @NonNull File jarFile
    ) throws IOException {
        Map<String, byte[]> replacements = new HashMap<>();
        List<String> addedMixins = new ArrayList<>();
        boolean missingRequiredClass = false;
        boolean invalidConfig = false;

        try (ZipFile zip = new ZipFile(jarFile)) {
            if (!hasReplayRenderClasses(zip)) {
                return PatchResult.NOT_REPLAY_MOD;
            }

            for (PatchSpec spec : PATCH_SPECS) {
                ZipEntry configEntry = zip.getEntry(spec.configEntry);
                if (configEntry == null) {
                    invalidConfig = true;
                    Logging.i(TAG, "Missing mixin config " + spec.configEntry
                            + " in " + jarFile.getName());
                    continue;
                }

                ZipEntry classEntry = findEntry(zip, spec.classEntry);
                if (classEntry == null) {
                    missingRequiredClass = true;
                    Logging.i(TAG, "Missing accessor class " + spec.classEntry
                            + " in " + jarFile.getName());
                    continue;
                }

                byte[] original = readAll(zip.getInputStream(configEntry));
                JSONObject config;
                try {
                    config = new JSONObject(new String(original, StandardCharsets.UTF_8));
                } catch (Throwable parseError) {
                    invalidConfig = true;
                    Logging.e(TAG, "Could not parse " + spec.configEntry
                            + " in " + jarFile.getName(), parseError);
                    continue;
                }

                if (!spec.packageName.equals(config.optString("package", ""))) {
                    invalidConfig = true;
                    Logging.i(TAG, "Unexpected mixin package in " + spec.configEntry
                            + " from " + jarFile.getName());
                    continue;
                }

                JSONArray client = config.optJSONArray("client");
                if (client == null) {
                    invalidConfig = true;
                    Logging.i(TAG, "Missing client mixin array in " + spec.configEntry
                            + " from " + jarFile.getName());
                    continue;
                }

                if (containsString(client, spec.mixinName)) {
                    continue;
                }

                client.put(spec.mixinName);

                String patchedConfig;
                try {
                    patchedConfig = config.toString(2);
                } catch (Throwable jsonError) {
                    invalidConfig = true;
                    Logging.e(TAG, "Could not serialize patched " + spec.configEntry
                            + " in " + jarFile.getName(), jsonError);
                    continue;
                }

                replacements.put(
                        spec.configEntry,
                        (patchedConfig + "\n").getBytes(StandardCharsets.UTF_8)
                );
                addedMixins.add(spec.mixinName);
            }
        }

        if (replacements.isEmpty()) {
            if (missingRequiredClass) return PatchResult.MISSING_REQUIRED_CLASS;
            if (invalidConfig) return PatchResult.INVALID_CONFIG;
            return PatchResult.ALREADY_CORRECT;
        }

        File backupDir = new File(gameDirectory,
                ".droidbridge/compat-backups/replaymod-neoforge");
        if (!backupDir.isDirectory() && !backupDir.mkdirs()) {
            throw new IOException("Could not create Replay Mod backup directory: "
                    + backupDir.getAbsolutePath());
        }

        String backupSuffix = "." + jarFile.length() + "." + jarFile.lastModified() + ".original";
        File backup = new File(backupDir, jarFile.getName() + backupSuffix);
        if (!backup.isFile()) {
            copyFile(jarFile, backup);
        }

        File temp = new File(jarFile.getParentFile(), jarFile.getName() + ".droidbridge-mixin-tmp");
        deleteIfExists(temp);

        try {
            rewriteJar(jarFile, temp, replacements, addedMixins);

            // Copy over the original instead of delete+rename. This is more
            // reliable on Android's emulated/FUSE-backed shared storage.
            copyFile(temp, jarFile);

            if (!verifyPatchedJar(jarFile, addedMixins)) {
                copyFile(backup, jarFile);
                throw new IOException("Replay Mod jar verification failed after rewrite; original restored");
            }
        } catch (Throwable failure) {
            try {
                if (backup.isFile()) copyFile(backup, jarFile);
            } catch (Throwable restoreFailure) {
                Logging.e(TAG, "Replay Mod restore failed after patch failure", restoreFailure);
            }
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException("Replay Mod jar patch failed", failure);
        } finally {
            deleteIfExists(temp);
        }

        Logging.i(TAG, "Patched " + jarFile.getAbsolutePath() + " added=" + addedMixins);
        return PatchResult.PATCHED;
    }


    @NonNull
    private static TinyExrPatchResult patchReForgedPlayTinyExrIfNeeded(
            @NonNull File gameDirectory,
            @NonNull File jarFile
    ) throws IOException {
        Map<String, byte[]> replacements = new HashMap<>();
        boolean foundTinyExr = false;

        try (ZipFile zip = new ZipFile(jarFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry == null || entry.isDirectory()) continue;

                String name = entry.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".jar") || !lower.contains("lwjgl-tinyexr")) {
                    continue;
                }

                foundTinyExr = true;
                byte[] originalNestedJar = readAll(zip.getInputStream(entry));
                NestedJarPatchResult nested = removeModuleInfoFromNestedJar(originalNestedJar);

                if (nested.removedModuleDescriptors > 0) {
                    replacements.put(name, nested.jarBytes);
                    Logging.i(TAG, "ReForgedPlay embedded TinyEXR requires Android JPMS fix: "
                            + name
                            + " removedModuleDescriptors="
                            + nested.removedModuleDescriptors);
                }
            }
        }

        if (!foundTinyExr) {
            return TinyExrPatchResult.TINYEXR_NOT_PRESENT;
        }

        if (replacements.isEmpty()) {
            return TinyExrPatchResult.ALREADY_CORRECT;
        }

        File backupDir = new File(gameDirectory,
                ".droidbridge/compat-backups/reforgedplay");
        if (!backupDir.isDirectory() && !backupDir.mkdirs()) {
            throw new IOException("Could not create ReForgedPlay backup directory: "
                    + backupDir.getAbsolutePath());
        }

        String backupSuffix = "." + jarFile.length() + "." + jarFile.lastModified() + ".original";
        File backup = new File(backupDir, jarFile.getName() + backupSuffix);
        if (!backup.isFile()) {
            copyFile(jarFile, backup);
        }

        File temp = new File(
                jarFile.getParentFile(),
                jarFile.getName() + ".droidbridge-tinyexr-tmp"
        );
        deleteIfExists(temp);

        try {
            rewriteJarWithMarker(
                    jarFile,
                    temp,
                    replacements,
                    REFORGEDPLAY_TINYEXR_MARKER_ENTRY,
                    "DroidBridge ReForgedPlay LWJGL TinyEXR Android module compatibility v1\n"
                            + "Removed explicit JPMS module-info from embedded lwjgl-tinyexr.\n"
            );

            copyFile(temp, jarFile);

            if (!verifyReForgedPlayTinyExrPatch(jarFile)) {
                copyFile(backup, jarFile);
                throw new IOException(
                        "ReForgedPlay TinyEXR verification failed after rewrite; original restored"
                );
            }
        } catch (Throwable failure) {
            try {
                if (backup.isFile()) copyFile(backup, jarFile);
            } catch (Throwable restoreFailure) {
                Logging.e(TAG,
                        "ReForgedPlay restore failed after TinyEXR patch failure",
                        restoreFailure);
            }

            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException("ReForgedPlay TinyEXR jar patch failed", failure);
        } finally {
            deleteIfExists(temp);
        }

        Logging.i(TAG, "Patched ReForgedPlay embedded LWJGL TinyEXR module metadata: "
                + jarFile.getAbsolutePath());
        return TinyExrPatchResult.PATCHED;
    }

    @NonNull
    private static NestedJarPatchResult removeModuleInfoFromNestedJar(
            @NonNull byte[] sourceJar
    ) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(sourceJar.length);
        int removed = 0;

        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(sourceJar));
             ZipOutputStream output = new ZipOutputStream(bytes)) {
            byte[] buffer = new byte[16 * 1024];
            ZipEntry entry;

            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName();

                if (isModuleInfoEntry(name)) {
                    removed++;
                    input.closeEntry();
                    continue;
                }

                if (isJarSignatureEntry(name)) {
                    input.closeEntry();
                    continue;
                }

                ZipEntry outEntry = new ZipEntry(name);
                if (entry.getTime() >= 0) outEntry.setTime(entry.getTime());
                output.putNextEntry(outEntry);

                if (!entry.isDirectory()) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                }

                output.closeEntry();
                input.closeEntry();
            }
        }

        if (removed == 0) {
            return new NestedJarPatchResult(sourceJar, 0);
        }
        return new NestedJarPatchResult(bytes.toByteArray(), removed);
    }

    private static boolean verifyReForgedPlayTinyExrPatch(@NonNull File jarFile) {
        try (ZipFile zip = new ZipFile(jarFile)) {
            if (zip.getEntry(REFORGEDPLAY_TINYEXR_MARKER_ENTRY) == null) {
                return false;
            }

            boolean foundTinyExr = false;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry == null || entry.isDirectory()) continue;

                String name = entry.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".jar") || !lower.contains("lwjgl-tinyexr")) {
                    continue;
                }

                foundTinyExr = true;
                byte[] nestedBytes = readAll(zip.getInputStream(entry));
                if (nestedJarHasModuleInfo(nestedBytes)) {
                    Logging.i(TAG, "ReForgedPlay TinyEXR verification still found module-info in "
                            + name);
                    return false;
                }
            }

            return foundTinyExr;
        } catch (Throwable throwable) {
            Logging.e(TAG,
                    "Failed to verify ReForgedPlay TinyEXR compatibility patch "
                            + jarFile.getName(),
                    throwable);
            return false;
        }
    }

    private static boolean nestedJarHasModuleInfo(@NonNull byte[] jarBytes) throws IOException {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (isModuleInfoEntry(entry.getName())) {
                    return true;
                }
                input.closeEntry();
            }
        }
        return false;
    }

    private static boolean isModuleInfoEntry(@Nullable String name) {
        if (name == null) return false;
        String normalized = name.replace('\\', '/');
        return "module-info.class".equals(normalized)
                || normalized.endsWith("/module-info.class");
    }

    private static void rewriteJarWithMarker(
            @NonNull File source,
            @NonNull File target,
            @NonNull Map<String, byte[]> replacements,
            @NonNull String markerEntryName,
            @NonNull String markerText
    ) throws IOException {
        try (ZipFile zip = new ZipFile(source);
             ZipOutputStream output = new ZipOutputStream(new FileOutputStream(target, false))) {

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry inputEntry = entries.nextElement();
                String name = inputEntry.getName();

                if (markerEntryName.equals(name) || isJarSignatureEntry(name)) {
                    continue;
                }

                byte[] replacement = replacements.get(name);
                ZipEntry outputEntry = new ZipEntry(name);
                if (inputEntry.getTime() >= 0) outputEntry.setTime(inputEntry.getTime());
                output.putNextEntry(outputEntry);

                if (!inputEntry.isDirectory()) {
                    if (replacement != null) {
                        output.write(replacement);
                    } else {
                        copyStream(zip.getInputStream(inputEntry), output);
                    }
                }
                output.closeEntry();
            }

            ZipEntry marker = new ZipEntry(markerEntryName);
            output.putNextEntry(marker);
            output.write(markerText.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private static boolean verifyPatchedJar(
            @NonNull File jarFile,
            @NonNull List<String> addedMixins
    ) {
        try (ZipFile zip = new ZipFile(jarFile)) {
            if (zip.getEntry(MARKER_ENTRY) == null) return false;

            for (PatchSpec spec : PATCH_SPECS) {
                if (!addedMixins.contains(spec.mixinName)) continue;

                ZipEntry configEntry = zip.getEntry(spec.configEntry);
                if (configEntry == null) return false;

                JSONObject config = new JSONObject(new String(
                        readAll(zip.getInputStream(configEntry)), StandardCharsets.UTF_8));
                JSONArray client = config.optJSONArray("client");
                if (client == null || !containsString(client, spec.mixinName)) {
                    return false;
                }
            }
            return true;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to verify patched Replay Mod jar " + jarFile.getName(), throwable);
            return false;
        }
    }

    private static boolean hasReplayRenderClasses(@NonNull ZipFile zip) {
        return findEntry(zip, "com/replaymod/render/ReplayModRender.class") != null
                || findEntry(zip, "com/replaymod/render/rendering/VideoRenderer.class") != null;
    }

    @Nullable
    private static ZipEntry findEntry(@NonNull ZipFile zip, @NonNull String expectedName) {
        ZipEntry exact = zip.getEntry(expectedName);
        if (exact != null) return exact;

        // Some repackagers can prefix jar contents. Do not assume the class is
        // absent until we check for the complete package path as a suffix.
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry != null && entry.getName().endsWith(expectedName)) {
                return entry;
            }
        }
        return null;
    }

    private static void rewriteJar(
            @NonNull File source,
            @NonNull File target,
            @NonNull Map<String, byte[]> replacements,
            @NonNull List<String> addedMixins
    ) throws IOException {
        try (ZipFile zip = new ZipFile(source);
             ZipOutputStream output = new ZipOutputStream(new FileOutputStream(target, false))) {

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry inputEntry = entries.nextElement();
                String name = inputEntry.getName();

                if (MARKER_ENTRY.equals(name) || isJarSignatureEntry(name)) {
                    continue;
                }

                byte[] replacement = replacements.get(name);
                ZipEntry outputEntry = new ZipEntry(name);
                if (inputEntry.getTime() >= 0) outputEntry.setTime(inputEntry.getTime());
                output.putNextEntry(outputEntry);

                if (!inputEntry.isDirectory()) {
                    if (replacement != null) {
                        output.write(replacement);
                    } else {
                        copyStream(zip.getInputStream(inputEntry), output);
                    }
                }
                output.closeEntry();
            }

            ZipEntry marker = new ZipEntry(MARKER_ENTRY);
            output.putNextEntry(marker);
            output.write(("DroidBridge Replay Mod NeoForge mixin compatibility v2\n"
                    + "Added: " + addedMixins + "\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private static boolean looksLikeNeoForgeReplayMod(
            @NonNull File jar,
            @Nullable String launchVersionId
    ) {
        String version = launchVersionId == null ? "" : launchVersionId.toLowerCase(Locale.ROOT);
        String name = jar.getName().toLowerCase(Locale.ROOT);

        if (version.contains("neoforge") || name.contains("neoforge")) return true;

        try (ZipFile zip = new ZipFile(jar)) {
            return zip.getEntry("META-INF/neoforge.mods.toml") != null
                    || zip.getEntry("META-INF/mods.toml") != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean looksLikeReForgedPlay(@NonNull String fileName) {
        String normalized = fileName.toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");
        return normalized.contains("reforgedplay");
    }

    private static boolean looksLikeReplayMod(@NonNull String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        String normalized = lower.replace("-", "").replace("_", "").replace(" ", "");
        return normalized.contains("replaymod") || normalized.contains("reforgedplay");
    }

    private static boolean containsString(@NonNull JSONArray array, @NonNull String value) {
        for (int i = 0; i < array.length(); i++) {
            if (value.equals(array.optString(i, null))) return true;
        }
        return false;
    }

    @NonNull
    private static List<File> getCandidateModsDirs(@NonNull File gameDir) {
        Set<String> seen = new LinkedHashSet<>();
        ArrayList<File> dirs = new ArrayList<>();

        addDirectory(dirs, seen, new File(gameDir, "mods"));

        File cursor = gameDir.getParentFile();
        for (int depth = 0; depth < 4 && cursor != null; depth++, cursor = cursor.getParentFile()) {
            addDirectory(dirs, seen, new File(cursor, "mods"));
            if (".minecraft".equals(cursor.getName())) break;
        }
        return dirs;
    }

    private static void addDirectory(
            @NonNull List<File> out,
            @NonNull Set<String> seen,
            @Nullable File directory
    ) {
        if (directory == null) return;
        String path;
        try {
            path = directory.getCanonicalPath();
        } catch (Throwable ignored) {
            path = directory.getAbsolutePath();
        }
        if (seen.add(path)) out.add(directory);
    }

    private static boolean isJarSignatureEntry(@NonNull String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/")) return false;
        return upper.endsWith(".SF")
                || upper.endsWith(".RSA")
                || upper.endsWith(".DSA")
                || upper.endsWith(".EC");
    }

    private static void copyFile(@NonNull File source, @NonNull File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create directory: " + parent.getAbsolutePath());
        }

        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target, false)) {
            copyStream(input, output);
            output.flush();
        }
    }

    private static void copyStream(@NonNull InputStream input, @NonNull java.io.OutputStream output)
            throws IOException {
        try (InputStream closeable = input) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = closeable.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    @NonNull
    private static byte[] readAll(@NonNull InputStream input) throws IOException {
        try (InputStream closeable = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = closeable.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void deleteIfExists(@Nullable File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private enum TinyExrPatchResult {
        PATCHED,
        ALREADY_CORRECT,
        TINYEXR_NOT_PRESENT
    }

    private static final class NestedJarPatchResult {
        final byte[] jarBytes;
        final int removedModuleDescriptors;

        NestedJarPatchResult(@NonNull byte[] jarBytes, int removedModuleDescriptors) {
            this.jarBytes = jarBytes;
            this.removedModuleDescriptors = removedModuleDescriptors;
        }
    }

    private enum PatchResult {
        PATCHED,
        ALREADY_CORRECT,
        MISSING_REQUIRED_CLASS,
        INVALID_CONFIG,
        NOT_REPLAY_MOD
    }

    private static final class PatchSpec {
        final String configEntry;
        final String packageName;
        final String mixinName;
        final String classEntry;

        PatchSpec(
                @NonNull String configEntry,
                @NonNull String packageName,
                @NonNull String mixinName,
                @NonNull String classEntry
        ) {
            this.configEntry = configEntry;
            this.packageName = packageName;
            this.mixinName = mixinName;
            this.classEntry = classEntry;
        }
    }
}

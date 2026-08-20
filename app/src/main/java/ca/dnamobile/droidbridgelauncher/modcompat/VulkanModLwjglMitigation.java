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

package ca.dnamobile.droidbridgelauncher.modcompat;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.runtime.Logger;

public final class VulkanModLwjglMitigation {
    private static final String TAG = "VulkanModLwjglMitigation";
    private static final String MARKER_ENTRY = "META-INF/javalauncher/vulkanmod_lwjgl_override_v2";
    private static final String LEGACY_MARKER_ENTRY = "META-INF/javalauncher/vulkanmod_lwjgl_override";
    private static final String ZALITH_LEGACY_MARKER_ENTRY = "META-INF/zalith/vulkanmod_lwjgl_override";
    private static final String WORK_DIR_NAME = ".javalauncher_patch";

    private static final String LEGACY_1201_ANDROID_LIBS_ASSET =
            "modcompat/vulkanmod-android-libs_0.1.0.jar";
    private static final String LEGACY_1201_ANDROID_LIBS_FILE =
            "vulkanMod-android-libs_0.1.0.jar";

    // Marker from an older DroidBridge test that incorrectly lower-cased VulkanMod
    // shader resource paths. Keep the repair here so removing the Beryl mitigation
    // does not leave VulkanMod jars in a broken state.
    private static final String OLD_BERYL_VULKAN_PATH_MARKER =
            "META-INF/droidbridge/beryl_vulkan_lowercase_shader_paths_v2";

    private VulkanModLwjglMitigation() {
    }

    public static void prepare(@Nullable File gameDir) {
        prepare(null, gameDir);
    }

    public static void prepare(@Nullable Context context, @Nullable File gameDir) {
        if (gameDir == null) return;

        List<File> modsDirs = getCandidateModsDirs(gameDir);

        for (File modsDir : modsDirs) {
                File[] mods = listJarFiles(modsDir);
                if (mods == null || mods.length == 0) continue;

                boolean hasLegacy1201VulkanMod = containsLegacy1201VulkanMod(mods);
                if (hasLegacy1201VulkanMod) {
                    ensureLegacy1201AndroidLibs(context, modsDir, mods);
                    mods = listJarFiles(modsDir);
                    if (mods == null || mods.length == 0) continue;
                }

                boolean hasLegacy1201AndroidLibs = hasLegacy1201AndroidLibs(mods);

                for (File modJar : mods) {
                    String lowerName = modJar.getName().toLowerCase(java.util.Locale.ROOT);
                    if (!lowerName.contains("vulkanmod")) continue;


                    try {
                        repairVulkanModJarFromOldShaderPathPatch(modJar);

                        boolean stripLegacyVmaShaderc = hasLegacy1201AndroidLibs
                                && isLegacy1201VulkanModJar(modJar)
                                && !isLegacy1201AndroidLibsJar(modJar);

                        if (!containsBundledLwjglToStrip(modJar, stripLegacyVmaShaderc)) {
                            appendLog("VulkanMod mitigation: no bundled LWJGL entries to strip in " + modJar.getName());
                            Log.i(TAG, "VulkanMod found but no bundled LWJGL entries to strip were detected: " + modJar.getName());
                            continue;
                        }

                        if (isAlreadyPatched(modJar, stripLegacyVmaShaderc)) {
                            appendLog("VulkanMod mitigation: already patched " + modJar.getAbsolutePath());
                            Log.i(TAG, "VulkanMod already patched: " + modJar.getName());
                            continue;
                        }

                        patchVulkanModJar(modJar, stripLegacyVmaShaderc);
                        appendLog("VulkanMod mitigation: patched successfully " + modJar.getAbsolutePath());
                        Log.i(TAG, "Patched VulkanMod LWJGL compatibility: " + modJar.getAbsolutePath());
                    } catch (Throwable throwable) {
                        appendLog("VulkanMod mitigation: failed for " + modJar.getAbsolutePath() + ": " + throwable);
                        Log.e(TAG, "Failed to patch VulkanMod jar: " + modJar.getAbsolutePath(), throwable);
                    }
                }
        }
    }

    @NonNull
    private static List<File> getCandidateModsDirs(@NonNull File gameDir) {
        Set<File> dirs = new LinkedHashSet<>();

        // DroidBridge isolated instance:
        // .minecraft/instances/<instance>/game/mods
        dirs.add(new File(gameDir, "mods"));

        // Some layouts place mods beside the game folder.
        File parent = gameDir.getParentFile();
        if (parent != null) dirs.add(new File(parent, "mods"));

        // Shared/global install:
        // .minecraft/mods
        File minecraftRoot = findMinecraftRoot(gameDir);
        if (minecraftRoot != null) dirs.add(new File(minecraftRoot, "mods"));

        return new ArrayList<>(dirs);
    }

    @Nullable
    private static File findMinecraftRoot(@Nullable File start) {
        File cursor = start;
        while (cursor != null) {
            if (".minecraft".equals(cursor.getName())) return cursor;
            cursor = cursor.getParentFile();
        }
        return null;
    }

    private static void repairVulkanModJarFromOldShaderPathPatch(@NonNull File jarFile) throws IOException {
        if (!hasEntry(jarFile, OLD_BERYL_VULKAN_PATH_MARKER)) return;

        File parentDir = jarFile.getParentFile();
        if (parentDir == null) return;

        File backup = new File(new File(parentDir, WORK_DIR_NAME), jarFile.getName() + ".beryl-vulkan-paths.backup");
        if (!backup.isFile()) {
            appendLog("VulkanMod mitigation: old shader-path patch marker found but backup is missing; reinstall a clean VulkanMod jar: " + jarFile.getName());
            return;
        }

        copyFile(backup, jarFile);
        appendLog("VulkanMod mitigation: restored VulkanMod jar from old shader-path backup: " + jarFile.getName());
    }

    private static boolean hasEntry(@NonNull File jarFile, @NonNull String entryName) throws IOException {
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            return zipFile.getEntry(entryName) != null;
        }
    }

    @Nullable
    private static File[] listJarFiles(@NonNull File modsDir) {
        return modsDir.listFiles(file -> file.isFile()
                && file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"));
    }

    private static boolean containsBundledLwjglToStrip(
            @NonNull File jarFile,
            boolean stripLegacyVmaShaderc
    ) throws IOException {
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (shouldStripBundledLwjgl(entry.getName(), stripLegacyVmaShaderc)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isAlreadyPatched(
            @NonNull File jarFile,
            boolean stripLegacyVmaShaderc
    ) throws IOException {
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            if (zipFile.getEntry(MARKER_ENTRY) != null) return true;

            // Version-1 patches only stripped lwjgl-vulkan. That is enough for modern
            // VulkanMod, but legacy 1.20.1 VulkanMod still needs its bundled
            // lwjgl-vma/lwjgl-shaderc 3.3.2 jars removed when the Android helper is
            // present, otherwise it crashes with VmaVulkanFunctions/Struct mismatch.
            boolean hasOldMarker = zipFile.getEntry(LEGACY_MARKER_ENTRY) != null
                    || zipFile.getEntry(ZALITH_LEGACY_MARKER_ENTRY) != null;
            return hasOldMarker && !stripLegacyVmaShaderc;
        }
    }

    private static void patchVulkanModJar(
            @NonNull File jarFile,
            boolean stripLegacyVmaShaderc
    ) throws IOException {
        File parentDir = jarFile.getParentFile();
        if (parentDir == null) {
            throw new IOException("Could not resolve VulkanMod jar parent directory: " + jarFile.getAbsolutePath());
        }

        File workDir = new File(parentDir, WORK_DIR_NAME);
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw new IOException("Could not create mitigation work directory: " + workDir.getAbsolutePath());
        }

        File backup = new File(workDir, jarFile.getName() + ".backup");
        File tempFile = new File(workDir, jarFile.getName() + ".tmp");

        deleteIfExists(backup);
        deleteIfExists(tempFile);

        copyFile(jarFile, backup);

        boolean stripped = false;
        try (ZipFile zipFile = new ZipFile(jarFile);
             ZipOutputStream output = new ZipOutputStream(new FileOutputStream(tempFile))) {

            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            byte[] buffer = new byte[8192];

            while (entries.hasMoreElements()) {
                ZipEntry inEntry = entries.nextElement();
                String name = inEntry.getName();

                if (shouldStripBundledLwjgl(name, stripLegacyVmaShaderc)) {
                    Log.i(TAG, "Stripping nested LWJGL jar entry: " + name);
                    stripped = true;
                    continue;
                }

                ZipEntry outEntry = new ZipEntry(name);
                outEntry.setMethod(inEntry.getMethod());
                if (inEntry.getMethod() == ZipEntry.STORED) {
                    outEntry.setSize(inEntry.getSize());
                    outEntry.setCompressedSize(inEntry.getCompressedSize());
                    outEntry.setCrc(inEntry.getCrc());
                }
                outEntry.setTime(inEntry.getTime());
                output.putNextEntry(outEntry);

                if (!inEntry.isDirectory()) {
                    try (InputStream input = zipFile.getInputStream(inEntry)) {
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            output.write(buffer, 0, read);
                        }
                    }
                }

                output.closeEntry();
            }

            if (stripped) {
                ZipEntry marker = new ZipEntry(MARKER_ENTRY);
                output.putNextEntry(marker);
                output.write("patched".getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }

        if (!stripped) {
            deleteIfExists(tempFile);
            deleteIfExists(backup);
            appendLog("VulkanMod mitigation: nothing stripped, leaving original jar untouched");
            return;
        }

        File originalBackup = new File(workDir, jarFile.getName() + ".original");
        deleteIfExists(originalBackup);

        if (jarFile.exists() && !jarFile.renameTo(originalBackup)) {
            copyFile(jarFile, originalBackup);
            if (!jarFile.delete()) {
                deleteIfExists(tempFile);
                restoreOriginalJar(backup, jarFile);
                throw new IOException("Could not move original VulkanMod jar aside: " + jarFile.getAbsolutePath());
            }
        }

        boolean replaced = tempFile.renameTo(jarFile);
        if (!replaced) {
            copyFile(tempFile, jarFile);
            replaced = jarFile.exists() && jarFile.length() > 0L;
        }

        if (!replaced) {
            restoreOriginalJar(backup, jarFile);
            throw new IOException("Could not replace VulkanMod jar with patched copy: " + jarFile.getAbsolutePath());
        }

        deleteIfExists(originalBackup);
        deleteIfExists(backup);
        deleteIfExists(tempFile);
    }

    private static void restoreOriginalJar(@NonNull File backup, @NonNull File jarFile) throws IOException {
        if (jarFile.exists() && !jarFile.delete()) {
            throw new IOException("Could not delete failed patched jar: " + jarFile.getAbsolutePath());
        }
        copyFile(backup, jarFile);
    }

    private static boolean shouldStripBundledLwjgl(
            @NonNull String entryName,
            boolean stripLegacyVmaShaderc
    ) {
        String normalized = entryName.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        int slash = normalized.lastIndexOf('/');
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;

        if (!fileName.endsWith(".jar") || !fileName.contains("lwjgl")) return false;

        if (fileName.contains("vulkan")) return true;

        // VulkanMod 0.5.x for Minecraft 1.20.1 was built around LWJGL 3.3.2
        // VMA/Shaderc classes. DroidBridge launches it with the Android LWJGL
        // 3.3.3 bridge, so the old bundled Java jars can call methods that no
        // longer exist on the active Struct class. When the Android helper jar is
        // installed, strip these old nested jars so Fabric resolves the helper's
        // LWJGL 3.3.3 VMA/Shaderc artifacts instead.
        return stripLegacyVmaShaderc
                && (fileName.contains("vma") || fileName.contains("shaderc"));
    }

    private static boolean containsLegacy1201VulkanMod(@NonNull File[] mods) {
        for (File mod : mods) {
            if (isLegacy1201VulkanModJar(mod) && !isLegacy1201AndroidLibsJar(mod)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLegacy1201AndroidLibs(@NonNull File[] mods) {
        for (File mod : mods) {
            if (isLegacy1201AndroidLibsJar(mod)) return true;
        }
        return false;
    }

    private static boolean isLegacy1201AndroidLibsJar(@NonNull File jarFile) {
        String lowerName = jarFile.getName().toLowerCase(java.util.Locale.ROOT);
        return lowerName.contains("vulkanmod")
                && (lowerName.contains("android-libs_0.1")
                || lowerName.contains("android-libs-0.1")
                || lowerName.contains("an-libs"));
    }

    private static boolean isLegacy1201VulkanModJar(@NonNull File jarFile) {
        String lowerName = jarFile.getName().toLowerCase(java.util.Locale.ROOT);
        if (!lowerName.contains("vulkanmod") || isLegacy1201AndroidLibsJar(jarFile)) return false;

        // Fast path for the common release filename.
        if (lowerName.contains("1.20.1") && lowerName.contains("0.5.")) return true;

        try {
            String modJson = readZipEntryString(jarFile, "fabric.mod.json");
            if (modJson == null) return false;

            String compact = modJson.replace(" ", "")
                    .replace("\n", "")
                    .replace("\r", "")
                    .replace("\t", "");
            boolean isVulkanMod = compact.contains("\"id\":\"vulkanmod\"");
            if (!isVulkanMod) return false;

            String version = extractJsonStringValue(compact, "version");
            return version.startsWith("0.5.") || compact.contains("1.20.1");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void ensureLegacy1201AndroidLibs(
            @Nullable Context context,
            @NonNull File modsDir,
            @NonNull File[] mods
    ) {
        if (hasLegacy1201AndroidLibs(mods)) {
            appendLog("VulkanMod mitigation: legacy 1.20.1 Android LWJGL helper already present");
            return;
        }

        if (context == null) {
            appendLog("VulkanMod mitigation: legacy 1.20.1 VulkanMod detected but launcher context is unavailable; cannot install Android LWJGL helper");
            return;
        }

        File target = new File(modsDir, LEGACY_1201_ANDROID_LIBS_FILE);
        if (target.isFile() && target.length() > 0L) {
            appendLog("VulkanMod mitigation: legacy 1.20.1 Android LWJGL helper already exists: " + target.getName());
            return;
        }

        try (InputStream input = context.getAssets().open(LEGACY_1201_ANDROID_LIBS_ASSET);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            appendLog("VulkanMod mitigation: installed legacy 1.20.1 Android LWJGL helper: " + target.getName());
        } catch (Throwable throwable) {
            deleteIfExists(target);
            appendLog("VulkanMod mitigation: legacy 1.20.1 VulkanMod needs Android LWJGL helper but asset is missing: "
                    + LEGACY_1201_ANDROID_LIBS_ASSET
                    + " (add " + LEGACY_1201_ANDROID_LIBS_FILE + " to app/src/main/assets/modcompat/) error="
                    + throwable);
        }
    }

    @Nullable
    private static String readZipEntryString(@NonNull File jarFile, @NonNull String entryName) throws IOException {
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            ZipEntry entry = zipFile.getEntry(entryName);
            if (entry == null || entry.isDirectory()) return null;
            try (InputStream input = zipFile.getInputStream(entry)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }

    @NonNull
    private static String extractJsonStringValue(@NonNull String compactJson, @NonNull String key) {
        String needle = "\"" + key + "\":\"";
        int start = compactJson.indexOf(needle);
        if (start < 0) return "";
        start += needle.length();
        int end = compactJson.indexOf('"', start);
        if (end < 0 || end <= start) return "";
        return compactJson.substring(start, end);
    }

    private static void copyFile(@NonNull File source, @NonNull File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create target directory: " + parent.getAbsolutePath());
        }

        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static void deleteIfExists(@NonNull File file) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Could not delete temporary file: " + file.getAbsolutePath());
        }
    }

    private static void appendLog(@NonNull String message) {
        try {
            Logger.appendToLog(stripTrailingLineBreaks(message));
        } catch (Throwable ignored) {
            Logging.i(TAG, message);
        }
    }

    private static void appendBlankLogLine() {
        try {
            Logger.appendToLog("");
        } catch (Throwable ignored) {
            Logging.i(TAG, "");
        }
    }

    @NonNull
    private static String stripTrailingLineBreaks(@NonNull String message) {
        int end = message.length();
        while (end > 0) {
            char c = message.charAt(end - 1);
            if (c != '\n' && c != '\r') break;
            end--;
        }
        return end == message.length() ? message : message.substring(0, end);
    }
}

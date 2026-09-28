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

import org.json.JSONArray;
import org.json.JSONObject;

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
    private static final String JARJAR_METADATA_ENTRY = "META-INF/jarjar/metadata.json";
    private static final String VULKAN_CLASS_ENTRY = "net/vulkanmod/vulkan/Vulkan.class";
    private static final String VK_INSTANCE_FACTORY_CLASS_ENTRY =
            "net/vulkanmod/vulkan/VkInstanceFactory.class";
    private static final String QUEUE_CLASS_ENTRY =
            "net/vulkanmod/vulkan/queue/Queue.class";
    private static final String QUEUE_FAMILY_INDICES_OWNER =
            "net/vulkanmod/vulkan/queue/Queue$QueueFamilyIndices";
    private static final String TRANSFER_QUEUE_ERROR =
            "Failed to find queue family with transfer support";
    private static final String RAW_SURFACE_METHOD = "nglfwCreateWindowSurface";
    private static final String RAW_SURFACE_DESCRIPTOR = "(JJJJ)I";
    private static final String PUBLIC_SURFACE_METHOD = "glfwCreateWindowSurface";
    private static final String LEGACY_DROIDBRIDGE_BUNDLE_SURFACE_METHOD = "droidbridgeCreateWindowSurface";
    private static final String SAFE_SURFACE_DESCRIPTOR =
            "(Lorg/lwjgl/vulkan/VkInstance;JLorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I";
    private static final String THREAD_OWNER = "java/lang/Thread";
    private static final String THREAD_START_METHOD = "start";
    private static final String THREAD_RUN_METHOD = "run";
    private static final String VOID_METHOD_DESCRIPTOR = "()V";

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
                        boolean stripForgeJarJarVma = hasForgeJarJarMetadata(modJar);
                        boolean patchForgeRawSurface = stripForgeJarJarVma
                                && containsForgeSurfaceCompatibilityWork(modJar);
                        boolean patchForgeInstanceInitThread = stripForgeJarJarVma
                                && containsForgeInstanceInitThreadRepair(modJar);
                        boolean patchForgeTransferQueueFallback = stripForgeJarJarVma
                                && containsForgeTransferQueueFallbackWork(modJar);
                        boolean stripBundledLwjgl = containsBundledLwjglToStrip(
                                modJar,
                                stripLegacyVmaShaderc,
                                stripForgeJarJarVma
                        );

                        if (!stripBundledLwjgl
                                && !patchForgeRawSurface
                                && !patchForgeInstanceInitThread
                                && !patchForgeTransferQueueFallback) {
                            appendLog("VulkanMod mitigation: no bundled LWJGL entries or Forge Android compatibility work to patch in " + modJar.getName());
                            Log.i(TAG, "VulkanMod found but no LWJGL/JarJar or Forge Android compatibility work was detected: " + modJar.getName());
                            continue;
                        }

                        patchVulkanModJar(
                                modJar,
                                stripLegacyVmaShaderc,
                                stripForgeJarJarVma,
                                patchForgeRawSurface,
                                patchForgeInstanceInitThread,
                                patchForgeTransferQueueFallback
                        );
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

    private static boolean hasForgeJarJarMetadata(@NonNull File jarFile) throws IOException {
        return hasEntry(jarFile, JARJAR_METADATA_ENTRY);
    }

    @Nullable
    private static File[] listJarFiles(@NonNull File modsDir) {
        return modsDir.listFiles(file -> file.isFile()
                && file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"));
    }

    private static boolean containsBundledLwjglToStrip(
            @NonNull File jarFile,
            boolean stripLegacyVmaShaderc,
            boolean stripForgeJarJarVma
    ) throws IOException {
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            ZipEntry jarJarMetadata = null;

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (shouldStripBundledLwjgl(
                        entry.getName(),
                        stripLegacyVmaShaderc,
                        stripForgeJarJarVma
                )) {
                    return true;
                }
                if (JARJAR_METADATA_ENTRY.equals(entry.getName())) {
                    jarJarMetadata = entry;
                }
            }

            // Forge/NeoForge JarJar keeps a metadata entry for every embedded jar.
            // Older DroidBridge patches removed lwjgl-vulkan itself but left this
            // metadata behind, which makes ModLauncher abort while resolving a path
            // that no longer exists. Treat stale metadata as patch work too so an
            // already-modified instance repairs itself without requiring a reinstall.
            if (jarJarMetadata != null) {
                byte[] metadata = readZipEntryBytes(zipFile, jarJarMetadata);
                return jarJarMetadataContainsStrippedPath(
                        metadata,
                        stripLegacyVmaShaderc,
                        stripForgeJarJarVma
                );
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
            boolean stripLegacyVmaShaderc,
            boolean stripForgeJarJarVma,
            boolean patchForgeRawSurface,
            boolean patchForgeInstanceInitThread,
            boolean patchForgeTransferQueueFallback
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

        boolean changed = false;
        try (ZipFile zipFile = new ZipFile(jarFile);
             ZipOutputStream output = new ZipOutputStream(new FileOutputStream(tempFile))) {

            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            byte[] buffer = new byte[8192];

            while (entries.hasMoreElements()) {
                ZipEntry inEntry = entries.nextElement();
                String name = inEntry.getName();

                // A previous DroidBridge pass may already have written the marker
                // while leaving Forge/NeoForge JarJar metadata stale. Replace the
                // marker at the end instead of copying it and creating a duplicate.
                if (MARKER_ENTRY.equals(name)) {
                    continue;
                }

                if (shouldStripBundledLwjgl(
                        name,
                        stripLegacyVmaShaderc,
                        stripForgeJarJarVma
                )) {
                    Log.i(TAG, "Stripping nested LWJGL jar entry: " + name);
                    changed = true;
                    continue;
                }

                byte[] replacementBytes = null;
                if (JARJAR_METADATA_ENTRY.equals(name) && !inEntry.isDirectory()) {
                    byte[] originalMetadata = readZipEntryBytes(zipFile, inEntry);
                    replacementBytes = rewriteJarJarMetadata(
                            originalMetadata,
                            stripLegacyVmaShaderc,
                            stripForgeJarJarVma
                    );
                    if (replacementBytes != null) {
                        changed = true;
                        Log.i(TAG, "Removed stripped LWJGL paths from NeoForge/Forge JarJar metadata");
                    }
                } else if (patchForgeRawSurface
                        && VULKAN_CLASS_ENTRY.equals(name)
                        && !inEntry.isDirectory()) {
                    byte[] originalClass = readZipEntryBytes(zipFile, inEntry);
                    replacementBytes = patchForgeRawSurfaceCall(originalClass);
                    changed = true;
                    Log.i(TAG, "Redirected Forge/NeoForge VulkanMod raw GLFW surface call through the Android-safe public LWJGL surface API");
                } else if (patchForgeInstanceInitThread
                        && VK_INSTANCE_FACTORY_CLASS_ENTRY.equals(name)
                        && !inEntry.isDirectory()) {
                    byte[] originalClass = readZipEntryBytes(zipFile, inEntry);
                    replacementBytes = repairForgeInstanceInitThreadStart(originalClass);
                    changed = true;
                    Log.i(TAG, "Restored Forge/NeoForge Vulkan instance initialization to its original helper thread");
                } else if (patchForgeTransferQueueFallback
                        && QUEUE_CLASS_ENTRY.equals(name)
                        && !inEntry.isDirectory()) {
                    byte[] originalClass = readZipEntryBytes(zipFile, inEntry);
                    replacementBytes = patchForgeTransferQueueFallback(originalClass);
                    changed = true;
                    Log.i(TAG, "Patched Forge/NeoForge VulkanMod transfer queue fallback to use the graphics queue when VK_QUEUE_TRANSFER_BIT is omitted");
                }

                ZipEntry outEntry = new ZipEntry(name);
                // Rewritten metadata can no longer reuse the original STORED size/CRC.
                // Deflating only that metadata entry keeps the rest of the jar untouched.
                if (replacementBytes != null) {
                    outEntry.setMethod(ZipEntry.DEFLATED);
                } else {
                    outEntry.setMethod(inEntry.getMethod());
                    if (inEntry.getMethod() == ZipEntry.STORED) {
                        outEntry.setSize(inEntry.getSize());
                        outEntry.setCompressedSize(inEntry.getCompressedSize());
                        outEntry.setCrc(inEntry.getCrc());
                    }
                }
                outEntry.setTime(inEntry.getTime());
                output.putNextEntry(outEntry);

                if (replacementBytes != null) {
                    output.write(replacementBytes);
                } else if (!inEntry.isDirectory()) {
                    try (InputStream input = zipFile.getInputStream(inEntry)) {
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            output.write(buffer, 0, read);
                        }
                    }
                }

                output.closeEntry();
            }

            if (changed) {
                ZipEntry marker = new ZipEntry(MARKER_ENTRY);
                output.putNextEntry(marker);
                output.write("patched".getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }

        if (!changed) {
            deleteIfExists(tempFile);
            deleteIfExists(backup);
            appendLog("VulkanMod mitigation: nothing changed, leaving original jar untouched");
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

    @NonNull
    private static byte[] readZipEntryBytes(
            @NonNull ZipFile zipFile,
            @NonNull ZipEntry entry
    ) throws IOException {
        try (InputStream input = zipFile.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static boolean jarJarMetadataContainsStrippedPath(
            @NonNull byte[] metadataBytes,
            boolean stripLegacyVmaShaderc,
            boolean stripForgeJarJarVma
    ) throws IOException {
        try {
            JSONObject root = new JSONObject(new String(metadataBytes, StandardCharsets.UTF_8));
            JSONArray jars = root.optJSONArray("jars");
            if (jars == null) return false;

            for (int i = 0; i < jars.length(); i++) {
                JSONObject jar = jars.optJSONObject(i);
                if (jar == null) continue;
                String path = jar.optString("path", "");
                if (!path.isEmpty() && shouldStripBundledLwjgl(
                        path,
                        stripLegacyVmaShaderc,
                        stripForgeJarJarVma
                )) {
                    return true;
                }
            }
            return false;
        } catch (Throwable throwable) {
            throw new IOException("Could not inspect Forge/NeoForge JarJar metadata", throwable);
        }
    }

    @Nullable
    private static byte[] rewriteJarJarMetadata(
            @NonNull byte[] metadataBytes,
            boolean stripLegacyVmaShaderc,
            boolean stripForgeJarJarVma
    ) throws IOException {
        try {
            JSONObject root = new JSONObject(new String(metadataBytes, StandardCharsets.UTF_8));
            JSONArray jars = root.optJSONArray("jars");
            if (jars == null) return null;

            JSONArray kept = new JSONArray();
            int removed = 0;

            for (int i = 0; i < jars.length(); i++) {
                Object value = jars.get(i);
                if (value instanceof JSONObject) {
                    JSONObject jar = (JSONObject) value;
                    String path = jar.optString("path", "");
                    if (!path.isEmpty() && shouldStripBundledLwjgl(
                        path,
                        stripLegacyVmaShaderc,
                        stripForgeJarJarVma
                )) {
                        removed++;
                        continue;
                    }
                }
                kept.put(value);
            }

            if (removed == 0) return null;

            root.put("jars", kept);
            return (root.toString(2) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (Throwable throwable) {
            throw new IOException("Could not rewrite Forge/NeoForge JarJar metadata", throwable);
        }
    }



    private static boolean containsForgeTransferQueueFallbackWork(@NonNull File jarFile) throws IOException {
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            ZipEntry entry = zipFile.getEntry(QUEUE_CLASS_ENTRY);
            if (entry == null || entry.isDirectory()) return false;
            byte[] classBytes = readZipEntryBytes(zipFile, entry);
            return findTransferQueueThrowOffset(classBytes) >= 0;
        }
    }

    @NonNull
    private static byte[] patchForgeTransferQueueFallback(@NonNull byte[] original) throws IOException {
        byte[] rewritten = original.clone();
        ParsedConstantPool pool = parseConstantPool(rewritten);

        int graphicsFamilyRef = findFieldRef(
                pool,
                QUEUE_FAMILY_INDICES_OWNER,
                "graphicsFamily",
                "I"
        );
        if (graphicsFamilyRef <= 0) {
            throw new IOException("VulkanMod graphics queue-family field was not found");
        }

        int throwOffset = findTransferQueueThrowOffset(rewritten);
        if (throwOffset < 0) {
            throw new IOException("VulkanMod transfer-queue fallback target was not found");
        }

        // Reforged currently throws when no queue family explicitly advertises
        // VK_QUEUE_TRANSFER_BIT. Vulkan explicitly allows graphics/compute queue
        // families to omit that bit because transfer commands are implicitly
        // supported there. Preserve bytecode length and existing stack-map/frame
        // offsets by replacing only the 10-byte exception construction:
        //
        //   new RuntimeException
        //   dup
        //   ldc "Failed to find queue family with transfer support"
        //   invokespecial RuntimeException.<init>
        //   athrow
        //
        // with:
        //
        //   aload_1
        //   getfield QueueFamilyIndices.graphicsFamily
        //   istore <candidate local>
        //   nop x4
        //
        // Execution then falls through to the mod's existing
        // transferFamily = candidate assignment.
        int candidateLocal = unsigned(rewritten[throwOffset - 5]);
        rewritten[throwOffset] = 0x2b; // ALOAD_1
        rewritten[throwOffset + 1] = (byte) 0xb4; // GETFIELD
        rewritten[throwOffset + 2] = (byte) ((graphicsFamilyRef >>> 8) & 0xFF);
        rewritten[throwOffset + 3] = (byte) (graphicsFamilyRef & 0xFF);
        rewritten[throwOffset + 4] = 0x36; // ISTORE
        rewritten[throwOffset + 5] = (byte) candidateLocal;
        rewritten[throwOffset + 6] = 0;
        rewritten[throwOffset + 7] = 0;
        rewritten[throwOffset + 8] = 0;
        rewritten[throwOffset + 9] = 0;

        if (findTransferQueueThrowOffset(rewritten) >= 0) {
            throw new IOException("VulkanMod transfer-queue fallback patch did not remove the invalid explicit-transfer requirement");
        }
        return rewritten;
    }

    private static int findTransferQueueThrowOffset(@NonNull byte[] classBytes) throws IOException {
        ParsedConstantPool pool = parseConstantPool(classBytes);
        int transferErrorString = findStringConstant(pool, TRANSFER_QUEUE_ERROR);
        if (transferErrorString <= 0 || transferErrorString > 0xFF) return -1;

        ClassReader reader = new ClassReader(classBytes);
        reader.position(pool.constantPoolEnd);
        reader.skip(6); // access, this class, super class
        int interfaceCount = reader.u2();
        reader.skip(interfaceCount * 2);

        int fieldCount = reader.u2();
        for (int i = 0; i < fieldCount; i++) skipClassMember(reader);

        int methodCount = reader.u2();
        for (int i = 0; i < methodCount; i++) {
            reader.u2(); // access
            int nameIndex = reader.u2();
            int descriptorIndex = reader.u2();
            int attributeCount = reader.u2();
            String methodName = utf8At(pool.utf8, nameIndex);
            String methodDescriptor = utf8At(pool.utf8, descriptorIndex);

            for (int attribute = 0; attribute < attributeCount; attribute++) {
                int attributeNameIndex = reader.u2();
                long attributeLengthLong = reader.u4();
                if (attributeLengthLong > Integer.MAX_VALUE) {
                    throw new IOException("Class attribute is too large");
                }
                int attributeLength = (int) attributeLengthLong;
                int attributeStart = reader.position();

                if ("findQueueFamilies".equals(methodName)
                        && "(Lorg/lwjgl/vulkan/VkPhysicalDevice;)Lnet/vulkanmod/vulkan/queue/Queue$QueueFamilyIndices;".equals(methodDescriptor)
                        && "Code".equals(utf8At(pool.utf8, attributeNameIndex))) {
                    reader.u2(); // max stack
                    reader.u2(); // max locals
                    long codeLengthLong = reader.u4();
                    if (codeLengthLong > Integer.MAX_VALUE) {
                        throw new IOException("Method bytecode is too large");
                    }
                    int codeLength = (int) codeLengthLong;
                    int codeOffset = reader.position();
                    reader.require(codeLength);

                    for (int offset = codeOffset + 6; offset + 9 < codeOffset + codeLength; offset++) {
                        // The three bytes immediately before NEW are:
                        // ILOAD <candidate local>, ICONST_M1. The preceding
                        // IF_ICMPNE remains intact and skips this block when a
                        // genuine explicit transfer family was found.
                        if (unsigned(classBytes[offset - 6]) != 0x15
                                || unsigned(classBytes[offset - 4]) != 0x02
                                || unsigned(classBytes[offset - 3]) != 0xa0
                                || unsigned(classBytes[offset]) != 0xbb
                                || unsigned(classBytes[offset + 3]) != 0x59
                                || unsigned(classBytes[offset + 4]) != 0x12
                                || unsigned(classBytes[offset + 5]) != transferErrorString
                                || unsigned(classBytes[offset + 6]) != 0xb7
                                || unsigned(classBytes[offset + 9]) != 0xbf) {
                            continue;
                        }
                        return offset;
                    }
                    return -1;
                }

                reader.position(attributeStart + attributeLength);
            }
        }
        return -1;
    }

    private static boolean containsForgeInstanceInitThreadRepair(@NonNull File jarFile) throws IOException {
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            ZipEntry entry = zipFile.getEntry(VK_INSTANCE_FACTORY_CLASS_ENTRY);
            if (entry == null || entry.isDirectory()) return false;
            byte[] classBytes = readZipEntryBytes(zipFile, entry);
            ParsedConstantPool pool = parseConstantPool(classBytes);
            int startRef = findMethodRef(
                    pool,
                    THREAD_OWNER,
                    THREAD_START_METHOD,
                    VOID_METHOD_DESCRIPTOR
            );
            int runRef = findMethodRef(
                    pool,
                    THREAD_OWNER,
                    THREAD_RUN_METHOD,
                    VOID_METHOD_DESCRIPTOR
            );
            // A clean Reforged build calls Thread.start(). One of the earlier
            // Android experiments changed that exact Methodref to Thread.run().
            // Repair only that unmistakable state so unrelated Thread.run() use is
            // never touched.
            return startRef <= 0 && runRef > 0;
        }
    }

    @NonNull
    private static byte[] repairForgeInstanceInitThreadStart(@NonNull byte[] original) throws IOException {
        ParsedConstantPool pool = parseConstantPool(original);
        int startMethodRef = findMethodRef(
                pool,
                THREAD_OWNER,
                THREAD_START_METHOD,
                VOID_METHOD_DESCRIPTOR
        );
        if (startMethodRef > 0) return original;

        int runMethodRef = findMethodRef(
                pool,
                THREAD_OWNER,
                THREAD_RUN_METHOD,
                VOID_METHOD_DESCRIPTOR
        );
        if (runMethodRef <= 0) {
            throw new IOException("Forge/NeoForge Vulkan instance-init Thread.run() repair target was not found");
        }

        int nameAndTypeIndex = pool.value2[runMethodRef];
        if (!validIndex(nameAndTypeIndex, pool.tags.length)
                || pool.tags[nameAndTypeIndex] != 12) {
            throw new IOException("Invalid Vulkan instance-init Thread.run() NameAndType constant");
        }

        int nameUtf8Index = pool.value1[nameAndTypeIndex];
        ensureUtf8IsPrivateToNameAndType(pool, nameAndTypeIndex, nameUtf8Index, -1);

        byte[] rewritten = rewriteUtf8Constant(
                original,
                pool,
                nameUtf8Index,
                THREAD_START_METHOD
        );

        ParsedConstantPool rewrittenPool = parseConstantPool(rewritten);
        if (findMethodRef(
                rewrittenPool,
                THREAD_OWNER,
                THREAD_START_METHOD,
                VOID_METHOD_DESCRIPTOR
        ) <= 0) {
            throw new IOException("Could not restore Vulkan instance-init Thread.start()");
        }

        return rewritten;
    }

    private static boolean containsForgeSurfaceCompatibilityWork(@NonNull File jarFile) throws IOException {
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            ZipEntry entry = zipFile.getEntry(VULKAN_CLASS_ENTRY);
            if (entry == null || entry.isDirectory()) return false;
            byte[] classBytes = readZipEntryBytes(zipFile, entry);
            ParsedConstantPool pool = parseConstantPool(classBytes);
            return findMethodRef(
                    pool,
                    "org/lwjgl/glfw/GLFWVulkan",
                    RAW_SURFACE_METHOD,
                    RAW_SURFACE_DESCRIPTOR
            ) > 0 || findMethodRef(
                    pool,
                    "org/lwjgl/glfw/GLFWVulkan",
                    LEGACY_DROIDBRIDGE_BUNDLE_SURFACE_METHOD,
                    SAFE_SURFACE_DESCRIPTOR
            ) > 0;
        }
    }

    @NonNull
    private static byte[] patchForgeRawSurfaceCall(@NonNull byte[] original) throws IOException {
        ParsedConstantPool originalPool = parseConstantPool(original);

        // v39 test builds redirected the already-safe object call through a
        // DroidBridge bundle-unwrapping helper. Once GLFW window destruction is
        // implemented correctly, Vulkan Reforged creates a fresh GLFW_NO_API
        // window whose handle is already the ANativeWindow. Repair those jars
        // back to LWJGL's normal public surface entry point.
        int legacyBundleMethodRef = findMethodRef(
                originalPool,
                "org/lwjgl/glfw/GLFWVulkan",
                LEGACY_DROIDBRIDGE_BUNDLE_SURFACE_METHOD,
                SAFE_SURFACE_DESCRIPTOR
        );
        if (legacyBundleMethodRef > 0) {
            int nameAndTypeIndex = originalPool.value2[legacyBundleMethodRef];
            if (!validIndex(nameAndTypeIndex, originalPool.tags.length)
                    || originalPool.tags[nameAndTypeIndex] != 12) {
                throw new IOException("Invalid legacy DroidBridge GLFW surface NameAndType constant");
            }
            int nameUtf8Index = originalPool.value1[nameAndTypeIndex];
            ensureUtf8IsPrivateToNameAndType(
                    originalPool,
                    nameAndTypeIndex,
                    nameUtf8Index,
                    -1
            );
            return rewriteUtf8Constant(
                    original,
                    originalPool,
                    nameUtf8Index,
                    PUBLIC_SURFACE_METHOD
            );
        }

        int rawMethodRef = findMethodRef(
                originalPool,
                "org/lwjgl/glfw/GLFWVulkan",
                RAW_SURFACE_METHOD,
                RAW_SURFACE_DESCRIPTOR
        );
        if (rawMethodRef <= 0) {
            throw new IOException("Forge/NeoForge raw GLFW Vulkan surface target was not found");
        }

        int nameAndTypeIndex = originalPool.value2[rawMethodRef];
        if (!validIndex(nameAndTypeIndex, originalPool.tags.length)
                || originalPool.tags[nameAndTypeIndex] != 12) {
            throw new IOException("Invalid raw GLFW surface NameAndType constant");
        }

        int nameUtf8Index = originalPool.value1[nameAndTypeIndex];
        int descriptorUtf8Index = originalPool.value2[nameAndTypeIndex];
        ensureUtf8IsPrivateToNameAndType(originalPool, nameAndTypeIndex, nameUtf8Index, descriptorUtf8Index);

        byte[] rewritten = rewriteUtf8Constants(
                original,
                originalPool,
                nameUtf8Index,
                PUBLIC_SURFACE_METHOD,
                descriptorUtf8Index,
                SAFE_SURFACE_DESCRIPTOR
        );

        ParsedConstantPool pool = parseConstantPool(rewritten);
        int safeMethodRef = findMethodRef(
                pool,
                "org/lwjgl/glfw/GLFWVulkan",
                PUBLIC_SURFACE_METHOD,
                SAFE_SURFACE_DESCRIPTOR
        );
        if (safeMethodRef <= 0) {
            throw new IOException("Could not rewrite raw GLFW surface method reference");
        }

        ClassReader reader = new ClassReader(rewritten);
        reader.position(pool.constantPoolEnd);
        reader.skip(6); // access, this class, super class
        int interfaceCount = reader.u2();
        reader.skip(interfaceCount * 2);

        int fieldCount = reader.u2();
        for (int i = 0; i < fieldCount; i++) skipClassMember(reader);

        int methodCount = reader.u2();
        for (int i = 0; i < methodCount; i++) {
            reader.u2(); // access
            int nameIndex = reader.u2();
            int descriptorIndex = reader.u2();
            int attributeCount = reader.u2();
            String methodName = utf8At(pool.utf8, nameIndex);
            String methodDescriptor = utf8At(pool.utf8, descriptorIndex);

            for (int attribute = 0; attribute < attributeCount; attribute++) {
                int attributeNameIndex = reader.u2();
                long attributeLengthLong = reader.u4();
                if (attributeLengthLong > Integer.MAX_VALUE) {
                    throw new IOException("Class attribute is too large");
                }
                int attributeLength = (int) attributeLengthLong;
                int attributeStart = reader.position();

                if ("createSurface".equals(methodName)
                        && "(J)V".equals(methodDescriptor)
                        && "Code".equals(utf8At(pool.utf8, attributeNameIndex))) {
                    reader.u2(); // max stack
                    reader.u2(); // max locals
                    long codeLengthLong = reader.u4();
                    if (codeLengthLong > Integer.MAX_VALUE) {
                        throw new IOException("Method bytecode is too large");
                    }
                    int codeLength = (int) codeLengthLong;
                    int codeOffset = reader.position();
                    reader.require(codeLength);

                    int sequenceOffset = findForgeRawSurfaceSequence(
                            rewritten,
                            codeOffset,
                            codeLength,
                            pool,
                            safeMethodRef
                    );
                    if (sequenceOffset < 0) {
                        throw new IOException("Exact Forge/NeoForge raw GLFW surface bytecode sequence was not found");
                    }

                    // Original:
                    //   GETSTATIC instance
                    //   INVOKEVIRTUAL VkInstance.address()J
                    //   GETSTATIC window
                    //   LCONST_0
                    //   ALOAD_3
                    //   INVOKESTATIC MemoryUtil.memAddress(LongBuffer)J
                    //   INVOKESTATIC GLFWVulkan.nglfwCreateWindowSurface(JJJJ)I
                    //
                    // Rewritten, preserving bytecode length and all branch/frame offsets:
                    //   GETSTATIC instance
                    //   NOP x3
                    //   GETSTATIC window
                    //   ACONST_NULL
                    //   ALOAD_3
                    //   NOP x3
                    //   INVOKESTATIC GLFWVulkan.glfwCreateWindowSurface(VkInstance, long, callbacks, LongBuffer)I
                    for (int offset = 3; offset <= 5; offset++) {
                        rewritten[sequenceOffset + offset] = 0;
                    }
                    rewritten[sequenceOffset + 9] = 0x01; // ACONST_NULL
                    for (int offset = 11; offset <= 13; offset++) {
                        rewritten[sequenceOffset + offset] = 0;
                    }
                    return rewritten;
                }

                reader.position(attributeStart + attributeLength);
            }
        }

        throw new IOException("Vulkan.createSurface(long) was not found");
    }

    private static int findForgeRawSurfaceSequence(
            @NonNull byte[] bytes,
            int codeOffset,
            int codeLength,
            @NonNull ParsedConstantPool pool,
            int safeMethodRef
    ) {
        int end = codeOffset + codeLength - 17;
        for (int offset = codeOffset; offset <= end; offset++) {
            if (unsigned(bytes[offset]) != 0xB2
                    || unsigned(bytes[offset + 3]) != 0xB6
                    || unsigned(bytes[offset + 6]) != 0xB2
                    || unsigned(bytes[offset + 9]) != 0x09
                    || unsigned(bytes[offset + 10]) != 0x2D
                    || unsigned(bytes[offset + 11]) != 0xB8
                    || unsigned(bytes[offset + 14]) != 0xB8) {
                continue;
            }

            int instanceFieldRef = readU2(bytes, offset + 1);
            int addressMethodRef = readU2(bytes, offset + 4);
            int windowFieldRef = readU2(bytes, offset + 7);
            int memAddressMethodRef = readU2(bytes, offset + 12);
            int surfaceMethodRef = readU2(bytes, offset + 15);

            if (surfaceMethodRef != safeMethodRef) continue;
            if (!referenceMatches(
                    pool,
                    instanceFieldRef,
                    9,
                    "net/vulkanmod/vulkan/Vulkan",
                    "instance",
                    "Lorg/lwjgl/vulkan/VkInstance;"
            )) continue;
            if (!referenceMatches(
                    pool,
                    addressMethodRef,
                    10,
                    "org/lwjgl/vulkan/VkInstance",
                    "address",
                    "()J"
            )) continue;
            if (!referenceMatches(
                    pool,
                    windowFieldRef,
                    9,
                    "net/vulkanmod/vulkan/Vulkan",
                    "window",
                    "J"
            )) continue;
            if (!referenceMatches(
                    pool,
                    memAddressMethodRef,
                    10,
                    "org/lwjgl/system/MemoryUtil",
                    "memAddress",
                    "(Ljava/nio/LongBuffer;)J"
            )) continue;

            return offset;
        }
        return -1;
    }

    private static void ensureUtf8IsPrivateToNameAndType(
            @NonNull ParsedConstantPool pool,
            int targetNameAndType,
            int nameUtf8Index,
            int descriptorUtf8Index
    ) throws IOException {
        for (int index = 1; index < pool.tags.length; index++) {
            if (index == targetNameAndType || pool.tags[index] != 12) continue;
            boolean sharesName = pool.value1[index] == nameUtf8Index;
            boolean sharesDescriptor = descriptorUtf8Index > 0
                    && pool.value2[index] == descriptorUtf8Index;
            if (sharesName || sharesDescriptor) {
                throw new IOException("Target method UTF8 constants are shared; refusing unsafe rewrite");
            }
        }
    }

    @NonNull
    private static byte[] rewriteUtf8Constant(
            @NonNull byte[] original,
            @NonNull ParsedConstantPool pool,
            int targetIndex,
            @NonNull String targetValue
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(original.length + 32);
        output.write(original, 0, 10); // magic, versions, constant-pool count

        for (int index = 1; index < pool.tags.length; index++) {
            if (pool.tags[index] == 0) continue; // second slot of long/double
            int start = pool.entryStart[index];
            int end = pool.entryEnd[index];
            if (start < 0 || end <= start) {
                throw new IOException("Invalid constant-pool entry boundaries");
            }

            if (index == targetIndex) {
                byte[] utf8Bytes = targetValue.getBytes(StandardCharsets.UTF_8);
                if (utf8Bytes.length > 0xFFFF) throw new IOException("UTF8 constant is too long");
                output.write(1);
                output.write((utf8Bytes.length >>> 8) & 0xFF);
                output.write(utf8Bytes.length & 0xFF);
                output.write(utf8Bytes, 0, utf8Bytes.length);
            } else {
                output.write(original, start, end - start);
            }
        }

        output.write(original, pool.constantPoolEnd, original.length - pool.constantPoolEnd);
        return output.toByteArray();
    }

    @NonNull
    private static byte[] rewriteUtf8Constants(
            @NonNull byte[] original,
            @NonNull ParsedConstantPool pool,
            int firstIndex,
            @NonNull String firstValue,
            int secondIndex,
            @NonNull String secondValue
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(original.length + 128);
        output.write(original, 0, 10); // magic, versions, constant-pool count

        for (int index = 1; index < pool.tags.length; index++) {
            if (pool.tags[index] == 0) continue; // second slot of long/double
            int start = pool.entryStart[index];
            int end = pool.entryEnd[index];
            if (start < 0 || end <= start) {
                throw new IOException("Invalid constant-pool entry boundaries");
            }

            if (index == firstIndex || index == secondIndex) {
                String value = index == firstIndex ? firstValue : secondValue;
                byte[] utf8Bytes = value.getBytes(StandardCharsets.UTF_8);
                if (utf8Bytes.length > 0xFFFF) throw new IOException("UTF8 constant is too long");
                output.write(1);
                output.write((utf8Bytes.length >>> 8) & 0xFF);
                output.write(utf8Bytes.length & 0xFF);
                output.write(utf8Bytes, 0, utf8Bytes.length);
            } else {
                output.write(original, start, end - start);
            }
        }

        output.write(original, pool.constantPoolEnd, original.length - pool.constantPoolEnd);
        return output.toByteArray();
    }

    @NonNull
    private static ParsedConstantPool parseConstantPool(@NonNull byte[] bytes) throws IOException {
        ClassReader reader = new ClassReader(bytes);
        if (reader.u4() != 0xCAFEBABEL) throw new IOException("Invalid class magic");
        reader.u2(); // minor
        reader.u2(); // major
        int constantPoolCount = reader.u2();

        int[] tags = new int[constantPoolCount];
        int[] value1 = new int[constantPoolCount];
        int[] value2 = new int[constantPoolCount];
        int[] entryStart = new int[constantPoolCount];
        int[] entryEnd = new int[constantPoolCount];
        String[] utf8 = new String[constantPoolCount];

        for (int index = 1; index < constantPoolCount; index++) {
            entryStart[index] = reader.position();
            int tag = reader.u1();
            tags[index] = tag;
            switch (tag) {
                case 1: {
                    int length = reader.u2();
                    utf8[index] = new String(reader.bytes(length), StandardCharsets.UTF_8);
                    break;
                }
                case 3:
                case 4:
                    reader.skip(4);
                    break;
                case 5:
                case 6:
                    reader.skip(8);
                    entryEnd[index] = reader.position();
                    index++;
                    if (index < constantPoolCount) {
                        entryStart[index] = -1;
                        entryEnd[index] = -1;
                    }
                    continue;
                case 7:
                case 8:
                case 16:
                case 19:
                case 20:
                    value1[index] = reader.u2();
                    break;
                case 9:
                case 10:
                case 11:
                case 12:
                case 17:
                case 18:
                    value1[index] = reader.u2();
                    value2[index] = reader.u2();
                    break;
                case 15:
                    value1[index] = reader.u1();
                    value2[index] = reader.u2();
                    break;
                default:
                    throw new IOException("Unsupported class constant tag " + tag);
            }
            entryEnd[index] = reader.position();
        }

        return new ParsedConstantPool(
                tags,
                value1,
                value2,
                entryStart,
                entryEnd,
                utf8,
                reader.position()
        );
    }

    private static int findMethodRef(
            @NonNull ParsedConstantPool pool,
            @NonNull String owner,
            @NonNull String name,
            @NonNull String descriptor
    ) {
        for (int index = 1; index < pool.tags.length; index++) {
            if (referenceMatches(pool, index, 10, owner, name, descriptor)) return index;
        }
        return -1;
    }

    private static int findFieldRef(
            @NonNull ParsedConstantPool pool,
            @NonNull String owner,
            @NonNull String name,
            @NonNull String descriptor
    ) {
        for (int index = 1; index < pool.tags.length; index++) {
            if (referenceMatches(pool, index, 9, owner, name, descriptor)) return index;
        }
        return -1;
    }

    private static int findStringConstant(
            @NonNull ParsedConstantPool pool,
            @NonNull String value
    ) {
        for (int index = 1; index < pool.tags.length; index++) {
            if (pool.tags[index] != 8) continue;
            if (value.equals(utf8At(pool.utf8, pool.value1[index]))) return index;
        }
        return -1;
    }

    private static boolean referenceMatches(
            @NonNull ParsedConstantPool pool,
            int referenceIndex,
            int expectedTag,
            @NonNull String expectedOwner,
            @NonNull String expectedName,
            @NonNull String expectedDescriptor
    ) {
        if (!validIndex(referenceIndex, pool.tags.length)
                || pool.tags[referenceIndex] != expectedTag) {
            return false;
        }
        int classIndex = pool.value1[referenceIndex];
        int nameAndTypeIndex = pool.value2[referenceIndex];
        if (!validIndex(classIndex, pool.tags.length) || pool.tags[classIndex] != 7) return false;
        if (!validIndex(nameAndTypeIndex, pool.tags.length) || pool.tags[nameAndTypeIndex] != 12) return false;

        String owner = utf8At(pool.utf8, pool.value1[classIndex]);
        String name = utf8At(pool.utf8, pool.value1[nameAndTypeIndex]);
        String descriptor = utf8At(pool.utf8, pool.value2[nameAndTypeIndex]);
        return expectedOwner.equals(owner)
                && expectedName.equals(name)
                && expectedDescriptor.equals(descriptor);
    }

    private static void skipClassMember(@NonNull ClassReader reader) throws IOException {
        reader.skip(6); // access, name, descriptor
        int attributeCount = reader.u2();
        for (int i = 0; i < attributeCount; i++) {
            reader.u2();
            long length = reader.u4();
            if (length > Integer.MAX_VALUE) throw new IOException("Class attribute is too large");
            reader.skip((int) length);
        }
    }

    private static boolean validIndex(int index, int length) {
        return index > 0 && index < length;
    }

    @Nullable
    private static String utf8At(@NonNull String[] utf8, int index) {
        return validIndex(index, utf8.length) ? utf8[index] : null;
    }

    private static int readU2(@NonNull byte[] bytes, int offset) {
        return (unsigned(bytes[offset]) << 8) | unsigned(bytes[offset + 1]);
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }

    private static final class ParsedConstantPool {
        final int[] tags;
        final int[] value1;
        final int[] value2;
        final int[] entryStart;
        final int[] entryEnd;
        final String[] utf8;
        final int constantPoolEnd;

        ParsedConstantPool(
                @NonNull int[] tags,
                @NonNull int[] value1,
                @NonNull int[] value2,
                @NonNull int[] entryStart,
                @NonNull int[] entryEnd,
                @NonNull String[] utf8,
                int constantPoolEnd
        ) {
            this.tags = tags;
            this.value1 = value1;
            this.value2 = value2;
            this.entryStart = entryStart;
            this.entryEnd = entryEnd;
            this.utf8 = utf8;
            this.constantPoolEnd = constantPoolEnd;
        }
    }

    private static final class ClassReader {
        private final byte[] bytes;
        private int position;

        ClassReader(@NonNull byte[] bytes) {
            this.bytes = bytes;
        }

        int position() {
            return position;
        }

        void position(int newPosition) throws IOException {
            if (newPosition < 0 || newPosition > bytes.length) {
                throw new IOException("Invalid class position " + newPosition);
            }
            position = newPosition;
        }

        int u1() throws IOException {
            require(1);
            return unsigned(bytes[position++]);
        }

        int u2() throws IOException {
            require(2);
            int value = readU2(bytes, position);
            position += 2;
            return value;
        }

        long u4() throws IOException {
            require(4);
            long value = ((long) unsigned(bytes[position]) << 24)
                    | ((long) unsigned(bytes[position + 1]) << 16)
                    | ((long) unsigned(bytes[position + 2]) << 8)
                    | unsigned(bytes[position + 3]);
            position += 4;
            return value;
        }

        @NonNull
        byte[] bytes(int length) throws IOException {
            require(length);
            byte[] value = new byte[length];
            System.arraycopy(bytes, position, value, 0, length);
            position += length;
            return value;
        }

        void skip(int length) throws IOException {
            require(length);
            position += length;
        }

        void require(int length) throws IOException {
            if (length < 0 || position > bytes.length - length) {
                throw new IOException("Truncated class file");
            }
        }
    }

    private static boolean shouldStripBundledLwjgl(
            @NonNull String entryName,
            boolean stripLegacyVmaShaderc,
            boolean stripForgeJarJarVma
    ) {
        String normalized = entryName.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        int slash = normalized.lastIndexOf('/');
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;

        if (!fileName.endsWith(".jar") || !fileName.contains("lwjgl")) return false;

        if (fileName.contains("vulkan")) return true;

        // DroidBridge's Android LWJGL bridge already contains the VMA Java package
        // and Android VMA native. Forge/NeoForge exposes JarJar dependencies as
        // modules, so keeping VulkanMod's bundled lwjgl-vma creates a split-package
        // module collision before the game can start. Shaderc is intentionally kept
        // because the DroidBridge bridge does not provide the Shaderc Java package.
        if (stripForgeJarJarVma && fileName.contains("vma")) return true;

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

/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, VulkanMod, or any other third-party project.
 */

package ca.dnamobile.droidbridgelauncher.modcompat;

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
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.runtime.Logger;

/**
 * Repairs the duplicate Android Vulkan surface creation in VulkanMod's early
 * Minecraft 26.2 backend port.
 *
 * VkBackend.createDevice() calls Vulkan.initVulkan(window), which creates and
 * stores the VkSurfaceKHR. The affected VkGpuDevice constructor then calls
 * VRenderSystem.initRenderer(), whose first two bytecode instructions create a
 * second surface for the same Android native window. Android correctly rejects
 * that second call with VK_ERROR_NATIVE_WINDOW_IN_USE_KHR (-1000000001).
 *
 * This patch NOPs only VRenderSystem.initRenderer's redundant
 * Vulkan.createSurface(window) call. It deliberately leaves the following
 * setShaderColor initialization intact. It is enabled only by the launcher's
 * VulkanMod 26.2+ / Use System Vulkan Driver compatibility decision and applies
 * only when the exact owner, field, method and descriptor bytecode signature is
 * present. Unknown or future VulkanMod layouts are left untouched.
 */
public final class VulkanMod262SurfaceMitigation {
    private static final String TAG = "VulkanMod262Surface";
    private static final String TARGET_CLASS = "net/vulkanmod/vulkan/VRenderSystem.class";
    private static final String MARKER_ENTRY =
            "META-INF/droidbridge/vulkanmod_26_2_duplicate_surface_v1";
    private static final String WORK_DIR_NAME = ".javalauncher_patch";

    private VulkanMod262SurfaceMitigation() {
    }

    public static void prepare(@Nullable File gameDirectory, boolean enabled) {
        if (!enabled || gameDirectory == null) return;

        for (File modsDirectory : getCandidateModsDirs(gameDirectory)) {
            File[] jars = modsDirectory.listFiles(file -> file.isFile()
                    && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
            if (jars == null || jars.length == 0) continue;

            for (File jar : jars) {
                if (!looksLikeVulkanMod(jar)) continue;

                try {
                    PatchResult result = patchJarIfNeeded(jar);
                    if (result == PatchResult.PATCHED) {
                        appendLog("VulkanMod 26.2+ surface mitigation: removed duplicate "
                                + "Android surface creation in " + jar.getName());
                    } else if (result == PatchResult.ALREADY_PATCHED) {
                        appendLog("VulkanMod 26.2+ surface mitigation: already patched "
                                + jar.getName());
                    } else if (result == PatchResult.SIGNATURE_NOT_FOUND) {
                        appendLog("VulkanMod 26.2+ surface mitigation: exact duplicate-surface "
                                + "bytecode signature not found; left jar untouched: "
                                + jar.getName());
                    }
                } catch (Throwable throwable) {
                    appendLog("VulkanMod 26.2+ surface mitigation: failed for "
                            + jar.getName() + ": " + throwable);
                    Logging.e(TAG, "Failed to patch " + jar.getAbsolutePath(), throwable);
                }
            }
        }
    }

    @NonNull
    private static PatchResult patchJarIfNeeded(@NonNull File jarFile) throws IOException {
        byte[] originalClass;
        boolean hasMarker;

        try (ZipFile zipFile = new ZipFile(jarFile)) {
            hasMarker = zipFile.getEntry(MARKER_ENTRY) != null;
            ZipEntry classEntry = zipFile.getEntry(TARGET_CLASS);
            if (classEntry == null || classEntry.isDirectory()) {
                return PatchResult.NOT_TARGET;
            }
            originalClass = readAll(zipFile.getInputStream(classEntry));
        }

        ClassPatchResult classPatch = patchVRenderSystemClass(originalClass);
        if (classPatch.state == ClassPatchState.SIGNATURE_NOT_FOUND) {
            return PatchResult.SIGNATURE_NOT_FOUND;
        }
        if (classPatch.state == ClassPatchState.ALREADY_PATCHED && hasMarker) {
            return PatchResult.ALREADY_PATCHED;
        }

        File parent = jarFile.getParentFile();
        if (parent == null) {
            throw new IOException("Could not resolve VulkanMod jar parent: "
                    + jarFile.getAbsolutePath());
        }

        File workDir = new File(parent, WORK_DIR_NAME);
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw new IOException("Could not create patch directory: " + workDir.getAbsolutePath());
        }

        File backup = new File(workDir, jarFile.getName() + ".surface-v1.backup");
        File temp = new File(workDir, jarFile.getName() + ".surface-v1.tmp");
        deleteIfExists(temp);
        if (!backup.isFile()) copyFile(jarFile, backup);

        boolean replacedClass = false;
        try (ZipFile zipFile = new ZipFile(jarFile);
             ZipOutputStream output = new ZipOutputStream(new FileOutputStream(temp))) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry inputEntry = entries.nextElement();
                String name = inputEntry.getName();

                if (MARKER_ENTRY.equals(name) || isJarSignatureEntry(name)) {
                    continue;
                }

                boolean isTargetClass = TARGET_CLASS.equals(name);
                byte[] entryBytes = inputEntry.isDirectory()
                        ? new byte[0]
                        : readAll(zipFile.getInputStream(inputEntry));
                if (isTargetClass) {
                    entryBytes = classPatch.bytes;
                    replacedClass = true;
                }

                ZipEntry outputEntry = createOutputEntry(inputEntry, isTargetClass, entryBytes);
                output.putNextEntry(outputEntry);
                if (!inputEntry.isDirectory()) output.write(entryBytes);
                output.closeEntry();
            }

            if (!replacedClass) {
                throw new IOException("Target class disappeared while rewriting " + jarFile.getName());
            }

            ZipEntry marker = new ZipEntry(MARKER_ENTRY);
            output.putNextEntry(marker);
            output.write("VulkanMod 26.2 duplicate Android surface fix v1\n"
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        } catch (Throwable throwable) {
            deleteIfExists(temp);
            if (throwable instanceof IOException) throw (IOException) throwable;
            throw new IOException("Could not rewrite VulkanMod jar", throwable);
        }

        replaceFileSafely(jarFile, temp, backup);
        deleteIfExists(temp);
        return classPatch.state == ClassPatchState.ALREADY_PATCHED
                ? PatchResult.ALREADY_PATCHED
                : PatchResult.PATCHED;
    }

    @NonNull
    private static ZipEntry createOutputEntry(
            @NonNull ZipEntry inputEntry,
            boolean modified,
            @NonNull byte[] bytes
    ) {
        ZipEntry outputEntry = new ZipEntry(inputEntry.getName());
        if (inputEntry.getTime() >= 0L) outputEntry.setTime(inputEntry.getTime());
        if (inputEntry.getComment() != null) outputEntry.setComment(inputEntry.getComment());
        if (inputEntry.getExtra() != null) outputEntry.setExtra(inputEntry.getExtra());

        if (!modified && inputEntry.getMethod() == ZipEntry.STORED) {
            outputEntry.setMethod(ZipEntry.STORED);
            outputEntry.setSize(bytes.length);
            outputEntry.setCompressedSize(bytes.length);
            outputEntry.setCrc(inputEntry.getCrc());
        } else {
            outputEntry.setMethod(ZipEntry.DEFLATED);
        }
        return outputEntry;
    }

    private static boolean looksLikeVulkanMod(@NonNull File jarFile) {
        String lowerName = jarFile.getName().toLowerCase(Locale.ROOT);
        if (!lowerName.contains("vulkanmod") || lowerName.contains("android-libs")) return false;

        try (ZipFile zipFile = new ZipFile(jarFile)) {
            return zipFile.getEntry(TARGET_CLASS) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isJarSignatureEntry(@NonNull String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/")) return false;
        return upper.endsWith(".SF")
                || upper.endsWith(".RSA")
                || upper.endsWith(".DSA")
                || upper.endsWith(".EC");
    }

    @NonNull
    private static List<File> getCandidateModsDirs(@NonNull File gameDirectory) {
        Set<File> directories = new LinkedHashSet<>();
        directories.add(new File(gameDirectory, "mods"));

        File parent = gameDirectory.getParentFile();
        if (parent != null) directories.add(new File(parent, "mods"));

        File minecraftRoot = findMinecraftRoot(gameDirectory);
        if (minecraftRoot != null) directories.add(new File(minecraftRoot, "mods"));
        return new ArrayList<>(directories);
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

    @NonNull
    private static ClassPatchResult patchVRenderSystemClass(@NonNull byte[] original)
            throws IOException {
        byte[] bytes = original.clone();
        ClassReader reader = new ClassReader(bytes);
        if (reader.u4() != 0xCAFEBABEL) throw new IOException("Invalid class magic");
        reader.u2(); // minor
        reader.u2(); // major

        int constantPoolCount = reader.u2();
        int[] tags = new int[constantPoolCount];
        int[] value1 = new int[constantPoolCount];
        int[] value2 = new int[constantPoolCount];
        String[] utf8 = new String[constantPoolCount];

        for (int index = 1; index < constantPoolCount; index++) {
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
                    index++;
                    break;
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
        }

        reader.skip(6); // access, this class, super class
        int interfaceCount = reader.u2();
        reader.skip(interfaceCount * 2);

        int fieldCount = reader.u2();
        for (int i = 0; i < fieldCount; i++) skipMember(reader);

        int methodCount = reader.u2();
        for (int i = 0; i < methodCount; i++) {
            reader.u2(); // access
            int nameIndex = reader.u2();
            int descriptorIndex = reader.u2();
            int attributeCount = reader.u2();
            String methodName = utf8At(utf8, nameIndex);
            String descriptor = utf8At(utf8, descriptorIndex);

            for (int attribute = 0; attribute < attributeCount; attribute++) {
                int attributeNameIndex = reader.u2();
                long attributeLengthLong = reader.u4();
                if (attributeLengthLong > Integer.MAX_VALUE) {
                    throw new IOException("Class attribute is too large");
                }
                int attributeLength = (int) attributeLengthLong;
                int attributeStart = reader.position();

                if ("initRenderer".equals(methodName)
                        && "()V".equals(descriptor)
                        && "Code".equals(utf8At(utf8, attributeNameIndex))) {
                    reader.u2(); // max stack
                    reader.u2(); // max locals
                    long codeLengthLong = reader.u4();
                    if (codeLengthLong > Integer.MAX_VALUE) {
                        throw new IOException("Method bytecode is too large");
                    }
                    int codeLength = (int) codeLengthLong;
                    int codeOffset = reader.position();
                    reader.require(codeLength);

                    if (codeLength >= 13 && areNops(bytes, codeOffset, 6)) {
                        return new ClassPatchResult(bytes, ClassPatchState.ALREADY_PATCHED);
                    }

                    if (codeLength < 13
                            || unsigned(bytes[codeOffset]) != 0xB2
                            || unsigned(bytes[codeOffset + 3]) != 0xB8) {
                        return new ClassPatchResult(original, ClassPatchState.SIGNATURE_NOT_FOUND);
                    }

                    int fieldRef = readU2(bytes, codeOffset + 1);
                    int methodRef = readU2(bytes, codeOffset + 4);
                    boolean fieldMatches = referenceMatches(
                            fieldRef,
                            9,
                            "net/vulkanmod/vulkan/VRenderSystem",
                            "window",
                            "J",
                            tags,
                            value1,
                            value2,
                            utf8
                    );
                    boolean methodMatches = referenceMatches(
                            methodRef,
                            10,
                            "net/vulkanmod/vulkan/Vulkan",
                            "createSurface",
                            "(J)V",
                            tags,
                            value1,
                            value2,
                            utf8
                    );
                    boolean keepsColorInitialization = unsigned(bytes[codeOffset + 6]) == 0x0C
                            && unsigned(bytes[codeOffset + 7]) == 0x0C
                            && unsigned(bytes[codeOffset + 8]) == 0x0C
                            && unsigned(bytes[codeOffset + 9]) == 0x0C;

                    if (!fieldMatches || !methodMatches || !keepsColorInitialization) {
                        return new ClassPatchResult(original, ClassPatchState.SIGNATURE_NOT_FOUND);
                    }

                    for (int byteIndex = 0; byteIndex < 6; byteIndex++) {
                        bytes[codeOffset + byteIndex] = 0;
                    }
                    return new ClassPatchResult(bytes, ClassPatchState.PATCHED);
                }

                reader.position(attributeStart + attributeLength);
            }
        }

        return new ClassPatchResult(original, ClassPatchState.SIGNATURE_NOT_FOUND);
    }

    private static void skipMember(@NonNull ClassReader reader) throws IOException {
        reader.skip(6); // access, name, descriptor
        int attributeCount = reader.u2();
        for (int i = 0; i < attributeCount; i++) {
            reader.u2();
            long length = reader.u4();
            if (length > Integer.MAX_VALUE) throw new IOException("Class attribute is too large");
            reader.skip((int) length);
        }
    }

    private static boolean referenceMatches(
            int referenceIndex,
            int expectedTag,
            @NonNull String expectedOwner,
            @NonNull String expectedName,
            @NonNull String expectedDescriptor,
            @NonNull int[] tags,
            @NonNull int[] value1,
            @NonNull int[] value2,
            @NonNull String[] utf8
    ) {
        if (!validIndex(referenceIndex, tags.length) || tags[referenceIndex] != expectedTag) {
            return false;
        }

        int classIndex = value1[referenceIndex];
        int nameAndTypeIndex = value2[referenceIndex];
        if (!validIndex(classIndex, tags.length) || tags[classIndex] != 7) return false;
        if (!validIndex(nameAndTypeIndex, tags.length) || tags[nameAndTypeIndex] != 12) return false;

        String owner = utf8At(utf8, value1[classIndex]);
        String name = utf8At(utf8, value1[nameAndTypeIndex]);
        String descriptor = utf8At(utf8, value2[nameAndTypeIndex]);
        return expectedOwner.equals(owner)
                && expectedName.equals(name)
                && expectedDescriptor.equals(descriptor);
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

    private static boolean areNops(@NonNull byte[] bytes, int offset, int count) {
        for (int i = 0; i < count; i++) {
            if (bytes[offset + i] != 0) return false;
        }
        return true;
    }

    @NonNull
    private static byte[] readAll(@NonNull InputStream input) throws IOException {
        try (InputStream closeable = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = closeable.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void replaceFileSafely(
            @NonNull File target,
            @NonNull File replacement,
            @NonNull File backup
    ) throws IOException {
        File movedOriginal = new File(target.getParentFile(), target.getName() + ".surface-v1.original");
        deleteIfExists(movedOriginal);

        if (target.exists() && !target.renameTo(movedOriginal)) {
            copyFile(target, movedOriginal);
            if (!target.delete()) {
                throw new IOException("Could not move original VulkanMod jar aside");
            }
        }

        boolean replaced = replacement.renameTo(target);
        if (!replaced) {
            copyFile(replacement, target);
            replaced = target.isFile() && target.length() > 0L;
        }

        if (!replaced) {
            restoreBackup(backup, target);
            throw new IOException("Could not install patched VulkanMod jar");
        }

        deleteIfExists(movedOriginal);
    }

    private static void restoreBackup(@NonNull File backup, @NonNull File target) throws IOException {
        if (target.exists() && !target.delete()) {
            throw new IOException("Could not remove failed patched jar");
        }
        copyFile(backup, target);
    }

    private static void copyFile(@NonNull File source, @NonNull File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create directory: " + parent.getAbsolutePath());
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
            Logging.i(TAG, "Could not delete temporary file: " + file.getAbsolutePath());
        }
    }

    private static void appendLog(@NonNull String message) {
        try {
            Logger.appendToLog(message);
        } catch (Throwable ignored) {
            Logging.i(TAG, message);
        }
    }

    private enum PatchResult {
        PATCHED,
        ALREADY_PATCHED,
        SIGNATURE_NOT_FOUND,
        NOT_TARGET
    }

    private enum ClassPatchState {
        PATCHED,
        ALREADY_PATCHED,
        SIGNATURE_NOT_FOUND
    }

    private static final class ClassPatchResult {
        final byte[] bytes;
        final ClassPatchState state;

        ClassPatchResult(@NonNull byte[] bytes, @NonNull ClassPatchState state) {
            this.bytes = bytes;
            this.state = state;
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
}

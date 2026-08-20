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
 * Adds an Android-safe transfer queue fallback to VulkanMod's early
 * Minecraft 26.2 backend port.
 *
 * The affected Queue.findQueueFamilies() implementation throws when the
 * driver does not expose a queue family with VK_QUEUE_TRANSFER_BIT. Vulkan
 * permits transfer commands on graphics-capable queues, so this patch keeps
 * VulkanMod's normal dedicated transfer queue search and changes only the
 * terminal failure branch to use graphicsFamily as transferFamily.
 *
 * The patch is enabled only by the launcher's VulkanMod 26.2+ / Use System
 * Vulkan Driver compatibility decision and applies only when the exact class,
 * method, field and exception bytecode signature is present. Unknown or future
 * VulkanMod layouts are left untouched.
 */
public final class VulkanMod262QueueMitigation {
    private static final String TAG = "VulkanMod262Queue";
    private static final String TARGET_CLASS = "net/vulkanmod/vulkan/queue/Queue.class";
    private static final String MARKER_ENTRY =
            "META-INF/droidbridge/vulkanmod_26_2_transfer_queue_fallback_v1";
    private static final String WORK_DIR_NAME = ".javalauncher_patch";

    private VulkanMod262QueueMitigation() {
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
                        appendLog("VulkanMod 26.2+ queue mitigation: added graphics-queue "
                                + "fallback for transfer operations in " + jar.getName());
                    } else if (result == PatchResult.ALREADY_PATCHED) {
                        appendLog("VulkanMod 26.2+ queue mitigation: already patched "
                                + jar.getName());
                    } else if (result == PatchResult.SIGNATURE_NOT_FOUND) {
                        appendLog("VulkanMod 26.2+ queue mitigation: exact transfer-queue "
                                + "failure signature not found; left jar untouched: "
                                + jar.getName());
                    }
                } catch (Throwable throwable) {
                    appendLog("VulkanMod 26.2+ queue mitigation: failed for "
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

        ClassPatchResult classPatch = patchQueueClass(originalClass);
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

        File backup = new File(workDir, jarFile.getName() + ".queue-v1.backup");
        File temp = new File(workDir, jarFile.getName() + ".queue-v1.tmp");
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
                throw new IOException("Target class disappeared while rewriting "
                        + jarFile.getName());
            }

            ZipEntry marker = new ZipEntry(MARKER_ENTRY);
            output.putNextEntry(marker);
            output.write("VulkanMod 26.2 graphics queue transfer fallback v1\n"
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
    private static ClassPatchResult patchQueueClass(@NonNull byte[] original) throws IOException {
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

                if ("findQueueFamilies".equals(methodName)
                        && "(Lorg/lwjgl/vulkan/VkPhysicalDevice;)"
                        .concat("Lnet/vulkanmod/vulkan/queue/Queue$QueueFamilyIndices;")
                        .equals(descriptor)
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

                    ClassPatchState state = patchKnownFailureBranch(
                            bytes,
                            codeOffset,
                            codeLength,
                            tags,
                            value1,
                            value2,
                            utf8
                    );
                    return new ClassPatchResult(
                            state == ClassPatchState.SIGNATURE_NOT_FOUND ? original : bytes,
                            state
                    );
                }

                reader.position(attributeStart + attributeLength);
            }
        }

        return new ClassPatchResult(original, ClassPatchState.SIGNATURE_NOT_FOUND);
    }

    @NonNull
    private static ClassPatchState patchKnownFailureBranch(
            @NonNull byte[] bytes,
            int codeOffset,
            int codeLength,
            @NonNull int[] tags,
            @NonNull int[] value1,
            @NonNull int[] value2,
            @NonNull String[] utf8
    ) {
        int codeEnd = codeOffset + codeLength;

        // Already-patched sequence:
        // aload_1; getfield graphicsFamily; istore 6; nop x4;
        // aload_1; iload 6; putfield transferFamily
        for (int cursor = codeOffset + 6; cursor <= codeEnd - 17; cursor++) {
            if (unsigned(bytes[cursor]) != 0x2B
                    || unsigned(bytes[cursor + 1]) != 0xB4
                    || unsigned(bytes[cursor + 4]) != 0x36
                    || unsigned(bytes[cursor + 5]) != 0x06
                    || !areNops(bytes, cursor + 6, 4)
                    || unsigned(bytes[cursor + 10]) != 0x2B
                    || unsigned(bytes[cursor + 11]) != 0x15
                    || unsigned(bytes[cursor + 12]) != 0x06
                    || unsigned(bytes[cursor + 13]) != 0xB5) {
                continue;
            }

            int graphicsFieldRef = readU2(bytes, cursor + 2);
            int transferFieldRef = readU2(bytes, cursor + 14);
            if (fieldReferenceMatches(
                    graphicsFieldRef,
                    "net/vulkanmod/vulkan/queue/Queue$QueueFamilyIndices",
                    "graphicsFamily",
                    "I",
                    tags,
                    value1,
                    value2,
                    utf8
            ) && fieldReferenceMatches(
                    transferFieldRef,
                    "net/vulkanmod/vulkan/queue/Queue$QueueFamilyIndices",
                    "transferFamily",
                    "I",
                    tags,
                    value1,
                    value2,
                    utf8
            ) && hasMissingTransferConditional(bytes, cursor, codeOffset)) {
                return ClassPatchState.ALREADY_PATCHED;
            }
        }

        // Original exact sequence:
        // new RuntimeException; dup; ldc "Failed ... transfer support";
        // invokespecial RuntimeException.<init>(String); athrow;
        // aload_1; iload 6; putfield transferFamily
        for (int cursor = codeOffset + 6; cursor <= codeEnd - 21; cursor++) {
            if (unsigned(bytes[cursor]) != 0xBB
                    || unsigned(bytes[cursor + 3]) != 0x59
                    || unsigned(bytes[cursor + 4]) != 0x12
                    || unsigned(bytes[cursor + 6]) != 0xB7
                    || unsigned(bytes[cursor + 9]) != 0xBF
                    || unsigned(bytes[cursor + 10]) != 0x2B
                    || unsigned(bytes[cursor + 11]) != 0x15
                    || unsigned(bytes[cursor + 12]) != 0x06
                    || unsigned(bytes[cursor + 13]) != 0xB5) {
                continue;
            }

            int exceptionClass = readU2(bytes, cursor + 1);
            int messageString = unsigned(bytes[cursor + 5]);
            int constructorRef = readU2(bytes, cursor + 7);
            int transferFieldRef = readU2(bytes, cursor + 14);

            boolean exceptionMatches = classMatches(
                    exceptionClass,
                    "java/lang/RuntimeException",
                    tags,
                    value1,
                    utf8
            );
            boolean messageMatches = stringMatches(
                    messageString,
                    "Failed to find queue family with transfer support",
                    tags,
                    value1,
                    utf8
            );
            boolean constructorMatches = methodReferenceMatches(
                    constructorRef,
                    "java/lang/RuntimeException",
                    "<init>",
                    "(Ljava/lang/String;)V",
                    tags,
                    value1,
                    value2,
                    utf8
            );
            boolean transferMatches = fieldReferenceMatches(
                    transferFieldRef,
                    "net/vulkanmod/vulkan/queue/Queue$QueueFamilyIndices",
                    "transferFamily",
                    "I",
                    tags,
                    value1,
                    value2,
                    utf8
            );

            if (!exceptionMatches || !messageMatches || !constructorMatches
                    || !transferMatches || !hasMissingTransferConditional(bytes, cursor, codeOffset)) {
                continue;
            }

            int graphicsFieldRef = findFieldReference(
                    "net/vulkanmod/vulkan/queue/Queue$QueueFamilyIndices",
                    "graphicsFamily",
                    "I",
                    tags,
                    value1,
                    value2,
                    utf8
            );
            if (graphicsFieldRef <= 0) return ClassPatchState.SIGNATURE_NOT_FOUND;

            // Replace the ten-byte throw block while preserving all offsets and
            // StackMapTable entries: aload_1; getfield graphicsFamily;
            // istore 6; nop; nop; nop; nop.
            bytes[cursor] = 0x2B;
            bytes[cursor + 1] = (byte) 0xB4;
            writeU2(bytes, cursor + 2, graphicsFieldRef);
            bytes[cursor + 4] = 0x36;
            bytes[cursor + 5] = 0x06;
            for (int i = 6; i < 10; i++) bytes[cursor + i] = 0;
            return ClassPatchState.PATCHED;
        }

        return ClassPatchState.SIGNATURE_NOT_FOUND;
    }

    private static boolean hasMissingTransferConditional(
            @NonNull byte[] bytes,
            int blockOffset,
            int codeOffset
    ) {
        int condition = blockOffset - 6;
        if (condition < codeOffset
                || unsigned(bytes[condition]) != 0x15
                || unsigned(bytes[condition + 1]) != 0x06
                || unsigned(bytes[condition + 2]) != 0x02
                || unsigned(bytes[condition + 3]) != 0xA0) {
            return false;
        }
        int branch = (short) readU2(bytes, condition + 4);
        return condition + 3 + branch == blockOffset + 10;
    }

    private static int findFieldReference(
            @NonNull String owner,
            @NonNull String name,
            @NonNull String descriptor,
            @NonNull int[] tags,
            @NonNull int[] value1,
            @NonNull int[] value2,
            @NonNull String[] utf8
    ) {
        for (int index = 1; index < tags.length; index++) {
            if (fieldReferenceMatches(index, owner, name, descriptor,
                    tags, value1, value2, utf8)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean fieldReferenceMatches(
            int referenceIndex,
            @NonNull String owner,
            @NonNull String name,
            @NonNull String descriptor,
            @NonNull int[] tags,
            @NonNull int[] value1,
            @NonNull int[] value2,
            @NonNull String[] utf8
    ) {
        return referenceMatches(referenceIndex, 9, owner, name, descriptor,
                tags, value1, value2, utf8);
    }

    private static boolean methodReferenceMatches(
            int referenceIndex,
            @NonNull String owner,
            @NonNull String name,
            @NonNull String descriptor,
            @NonNull int[] tags,
            @NonNull int[] value1,
            @NonNull int[] value2,
            @NonNull String[] utf8
    ) {
        return referenceMatches(referenceIndex, 10, owner, name, descriptor,
                tags, value1, value2, utf8);
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

    private static boolean classMatches(
            int classIndex,
            @NonNull String expectedName,
            @NonNull int[] tags,
            @NonNull int[] value1,
            @NonNull String[] utf8
    ) {
        return validIndex(classIndex, tags.length)
                && tags[classIndex] == 7
                && expectedName.equals(utf8At(utf8, value1[classIndex]));
    }

    private static boolean stringMatches(
            int stringIndex,
            @NonNull String expectedValue,
            @NonNull int[] tags,
            @NonNull int[] value1,
            @NonNull String[] utf8
    ) {
        return validIndex(stringIndex, tags.length)
                && tags[stringIndex] == 8
                && expectedValue.equals(utf8At(utf8, value1[stringIndex]));
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
        File movedOriginal = new File(target.getParentFile(), target.getName() + ".queue-v1.original");
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

    private static void writeU2(@NonNull byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) ((value >>> 8) & 0xFF);
        bytes[offset + 1] = (byte) (value & 0xFF);
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
                throw new IOException("Unexpected end of class file");
            }
        }
    }
}

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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;
import ca.dnamobile.droidbridgelauncher.runtime.Architecture;

/**
 * Extracts Android native libraries from Maven AAR files into cache/natives/<version>.
 *
 * This is what lets JNA use Android's libjnidispatch.so instead of unpacking a
 * desktop linux-aarch64 native that fails on Android with "libc.so.6 not found".
 */
public final class NativesExtractor {
    private static final ArrayList<String> LIBRARY_BLACKLIST = createLibraryBlacklist();

    private final File destinationDir;
    private final String libraryLocation;

    public NativesExtractor(@NonNull File destinationDir) {
        this.destinationDir = destinationDir;
        this.libraryLocation = "jni/" + getAarArchitectureName() + "/";
    }

    @NonNull
    private static ArrayList<String> createLibraryBlacklist() {
        ArrayList<String> blacklist = new ArrayList<>();
        if (PathManager.DIR_NATIVE_LIB == null || PathManager.DIR_NATIVE_LIB.trim().isEmpty()) {
            return blacklist;
        }

        String[] includedLibraryNames = new File(PathManager.DIR_NATIVE_LIB).list();
        if (includedLibraryNames == null) {
            return blacklist;
        }

        for (String libraryName : includedLibraryNames) {
            // Allow overriding jnidispatch because the integrated/native copy may be too old.
            if ("libjnidispatch.so".equals(libraryName)) continue;
            blacklist.add(libraryName);
        }

        blacklist.trimToSize();
        return blacklist;
    }

    @NonNull
    private static String getAarArchitectureName() {
        int architecture = Architecture.getDeviceArchitecture();
        switch (architecture) {
            case Architecture.ARCH_ARM:
                return "armeabi-v7a";
            case Architecture.ARCH_ARM64:
                return "arm64-v8a";
            case Architecture.ARCH_X86:
                return "x86";
            case Architecture.ARCH_X86_64:
                return "x86_64";
            default:
                throw new RuntimeException("Unknown CPU architecture: " + architecture);
        }
    }

    public void extractFromAar(@NonNull File source) throws IOException {
        if (!source.isFile()) {
            throw new IOException("Missing native AAR: " + source.getAbsolutePath());
        }
        if (!destinationDir.exists() && !destinationDir.mkdirs() && !destinationDir.isDirectory()) {
            throw new IOException("Unable to create native directory: " + destinationDir.getAbsolutePath());
        }

        byte[] buffer = new byte[8192];
        try (FileInputStream fileInputStream = new FileInputStream(source);
             ZipInputStream zipInputStream = new ZipInputStream(fileInputStream)) {

            NonCloseableInputStream entryCopyStream = new NonCloseableInputStream(zipInputStream);
            ZipEntry entry;

            while ((entry = zipInputStream.getNextEntry()) != null) {
                String entryName = entry.getName();

                if (!entryName.startsWith(libraryLocation) || entry.isDirectory()) {
                    continue;
                }

                entryName = getFileName(entryName);
                if (entryName == null || LIBRARY_BLACKLIST.contains(entryName)) {
                    continue;
                }

                processEntry(entryCopyStream, entry, new File(destinationDir, entryName), buffer);
            }
        }
    }

    @Nullable
    private static String getFileName(@Nullable String path) {
        if (path == null || path.trim().isEmpty()) return null;

        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;

        return name.trim().isEmpty() ? null : name;
    }

    private static long fileCrc32(@NonNull File target, byte[] buffer) throws IOException {
        try (FileInputStream fileInputStream = new FileInputStream(target)) {
            CRC32 crc32 = new CRC32();
            int len;
            while ((len = fileInputStream.read(buffer)) != -1) {
                crc32.update(buffer, 0, len);
            }
            return crc32.getValue();
        }
    }

    private void processEntry(
            @NonNull InputStream sourceStream,
            @NonNull ZipEntry zipEntry,
            @NonNull File entryDestination,
            byte[] buffer
    ) throws IOException {
        if (entryDestination.exists()) {
            long expectedSize = zipEntry.getSize();
            long expectedCrc32 = zipEntry.getCrc();
            long realSize = entryDestination.length();
            long realCrc32 = fileCrc32(entryDestination, buffer);
            if (realSize == expectedSize && realCrc32 == expectedCrc32) {
                return;
            }
        }

        File parent = entryDestination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Unable to create directory: " + parent);
        }

        try (FileOutputStream outputStream = new FileOutputStream(entryDestination)) {
            int read;
            while ((read = sourceStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
        }

        entryDestination.setReadable(true, false);
        entryDestination.setExecutable(true, false);
    }

    private static final class NonCloseableInputStream extends FilterInputStream {
        private NonCloseableInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // Keep ZipInputStream open for the next entry.
        }
    }
}

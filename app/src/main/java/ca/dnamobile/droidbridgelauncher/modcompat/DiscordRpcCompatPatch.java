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
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.launcher.LaunchPlan;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

public final class DiscordRpcCompatPatch {
    private static final String TAG = "DiscordRpcCompatPatch";
    private static final String ASSET_ARM64_SO = "components/arm64-v8a/libdiscord-rpc.so";
    private static final String MARKER_ENTRY = "META-INF/javalauncher-discordrpc-arm64.txt";
    private static final int BUFFER_SIZE = 64 * 1024;

    private DiscordRpcCompatPatch() {
    }

    public static void apply(@NonNull Context context, @NonNull LaunchPlan plan) {
        if (!isArm64Device()) return;

        File modsDirectory = new File(plan.getGameDirectory(), "mods");
        if (!modsDirectory.isDirectory()) return;

        File androidDiscordRpc = resolveAndroidArm64Library(context);
        if (androidDiscordRpc == null || !androidDiscordRpc.isFile()) {
            Logging.i(TAG, "No Android ARM64 libdiscord-rpc.so found; DiscordRPC mod patch skipped.");
            return;
        }

        File[] jars = modsDirectory.listFiles((dir, name) -> {
            String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
            return lower.endsWith(".jar");
        });
        if (jars == null || jars.length == 0) return;

        String sourceSha1;
        try {
            sourceSha1 = sha1(androidDiscordRpc);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to hash Android DiscordRPC library", throwable);
            return;
        }

        for (File jar : jars) {
            try {
                patchJarIfNeeded(jar, androidDiscordRpc, sourceSha1);
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to patch DiscordRPC native inside " + jar.getName(), throwable);
            }
        }
    }

    @Nullable
    private static File resolveAndroidArm64Library(@NonNull Context context) {
        // If you place the uploaded .so in app/src/main/jniLibs/arm64-v8a/,
        // Android extracts it here automatically.
        String nativeLibraryDir = context.getApplicationInfo() == null
                ? null
                : context.getApplicationInfo().nativeLibraryDir;
        if (nativeLibraryDir != null && !nativeLibraryDir.trim().isEmpty()) {
            File fromJniLibs = new File(nativeLibraryDir, "libdiscord-rpc.so");
            if (fromJniLibs.isFile()) return fromJniLibs;
        }

        // If you use the included asset path instead, copy it out to a real file.
        try {
            File out = new File(PathManager.DIR_CACHE,
                    "discordrpc/android-arm64/libdiscord-rpc.so");
            copyAssetIfNeeded(context, ASSET_ARM64_SO, out);
            if (out.isFile()) return out;
        } catch (Throwable throwable) {
            Logging.i(TAG, "DiscordRPC asset library not available: " + throwable.getMessage());
        }

        // Last fallback for local development builds where the file was copied
        // into the launcher's private native folder manually.
        String nativeDir = PathManager.DIR_NATIVE_LIB;
        if (nativeDir != null && !nativeDir.trim().isEmpty()) {
            File fromNativeDir = new File(nativeDir, "libdiscord-rpc.so");
            if (fromNativeDir.isFile()) return fromNativeDir;
        }

        return null;
    }

    private static boolean patchJarIfNeeded(
            @NonNull File jar,
            @NonNull File androidDiscordRpc,
            @NonNull String sourceSha1
    ) throws Exception {
        if (!jar.isFile()) return false;

        boolean hasDiscordRpcNative = false;
        boolean needsRewrite = false;

        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                if (!isDiscordRpcNativeEntry(entry.getName())) continue;

                hasDiscordRpcNative = true;
                String existingSha1 = sha1(zip, entry);
                if (!sourceSha1.equalsIgnoreCase(existingSha1)) {
                    needsRewrite = true;
                    break;
                }
            }
        }

        if (!hasDiscordRpcNative || !needsRewrite) return false;

        File temp = new File(jar.getParentFile(), jar.getName() + ".discordrpc.tmp");
        File backup = new File(jar.getParentFile(), jar.getName() + ".before-discordrpc-patch");

        if (temp.exists() && !temp.delete()) {
            throw new IllegalStateException("Unable to delete old temp jar: " + temp.getAbsolutePath());
        }

        int replaced = 0;
        Set<String> writtenNames = new HashSet<>();
        try (ZipFile zip = new ZipFile(jar);
             ZipOutputStream output = new ZipOutputStream(new FileOutputStream(temp))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry original = entries.nextElement();
                String name = normalizeZipName(original.getName());

                // Rewriting a jar invalidates signatures. Most mod jars are not
                // signed, but skip signature files so Java does not try to verify
                // stale signatures later.
                if (shouldSkipSignatureFile(name) || MARKER_ENTRY.equals(name)) continue;
                if (!writtenNames.add(name)) continue;

                ZipEntry copyEntry = new ZipEntry(name);
                copyEntry.setTime(original.getTime());
                output.putNextEntry(copyEntry);

                if (!original.isDirectory()) {
                    if (isDiscordRpcNativeEntry(name)) {
                        try (InputStream input = new FileInputStream(androidDiscordRpc)) {
                            copy(input, output);
                        }
                        replaced++;
                    } else {
                        try (InputStream input = zip.getInputStream(original)) {
                            copy(input, output);
                        }
                    }
                }

                output.closeEntry();
            }

            ZipEntry marker = new ZipEntry(MARKER_ENTRY);
            output.putNextEntry(marker);
            String markerText = "Patched by DroidBridge for Android ARM64 DiscordRPC compatibility.\n"
                    + "sourceSha1=" + sourceSha1 + "\n"
                    + "replacedEntries=" + replaced + "\n";
            output.write(markerText.getBytes("UTF-8"));
            output.closeEntry();
        }

        if (replaced <= 0) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return false;
        }

        if (!backup.isFile()) {
            copyFile(jar, backup);
        }

        replaceFile(temp, jar);
        Logging.i(TAG, "Patched " + jar.getName() + " with Android ARM64 libdiscord-rpc.so (" + replaced + " entr" + (replaced == 1 ? "y" : "ies") + ").");
        return true;
    }

    private static boolean isDiscordRpcNativeEntry(@Nullable String rawName) {
        if (rawName == null) return false;
        String name = normalizeZipName(rawName).toLowerCase(Locale.ROOT);
        return name.endsWith("libdiscord-rpc.so")
                || name.endsWith("/libdiscord-rpc.so");
    }

    private static boolean shouldSkipSignatureFile(@NonNull String entryName) {
        String upper = entryName.toUpperCase(Locale.ROOT);
        return upper.startsWith("META-INF/")
                && (upper.endsWith(".SF")
                || upper.endsWith(".RSA")
                || upper.endsWith(".DSA")
                || upper.endsWith(".EC"));
    }

    @NonNull
    private static String normalizeZipName(@NonNull String value) {
        return value.replace('\\', '/');
    }

    private static boolean isArm64Device() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis == null) return false;
        for (String abi : abis) {
            if (abi == null) continue;
            String lower = abi.toLowerCase(Locale.ROOT);
            if (lower.contains("arm64") || lower.contains("aarch64")) return true;
        }
        return false;
    }

    private static void copyAssetIfNeeded(
            @NonNull Context context,
            @NonNull String assetPath,
            @NonNull File target
    ) throws Exception {
        if (target.isFile() && target.length() > 0) return;
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Unable to create folder: " + parent.getAbsolutePath());
        }
        try (InputStream input = context.getAssets().open(assetPath);
             FileOutputStream output = new FileOutputStream(target)) {
            copy(input, output);
        }
        //noinspection ResultOfMethodCallIgnored
        target.setReadable(true, false);
        //noinspection ResultOfMethodCallIgnored
        target.setExecutable(true, false);
    }

    @NonNull
    private static String sha1(@NonNull File file) throws Exception {
        try (InputStream input = new FileInputStream(file)) {
            return sha1(input);
        }
    }

    @NonNull
    private static String sha1(@NonNull ZipFile zip, @NonNull ZipEntry entry) throws Exception {
        try (InputStream input = zip.getInputStream(entry)) {
            return sha1(input);
        }
    }

    @NonNull
    private static String sha1(@NonNull InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        byte[] bytes = digest.digest();
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    private static void copyFile(@NonNull File source, @NonNull File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Unable to create folder: " + parent.getAbsolutePath());
        }
        try (InputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            copy(input, output);
        }
    }

    private static void replaceFile(@NonNull File source, @NonNull File target) throws Exception {
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("Unable to replace " + target.getAbsolutePath());
        }
        if (!source.renameTo(target)) {
            copyFile(source, target);
            //noinspection ResultOfMethodCallIgnored
            source.delete();
        }
    }

    private static void copy(@NonNull InputStream input, @NonNull OutputStream output) throws Exception {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }
}

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

package ca.dnamobile.droidbridgelauncher.modmanager;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Reads mod display names and icons from normal jars and nested JarJar jars.
 *
 * Some Forge/NeoForge mods, such as Kotlin For Forge, ship the actual mod metadata
 * and logo inside a nested jar under META-INF/jarjar instead of at the top level.
 * Android's installed-content list needs to look inside those nested jars to show
 * the proper mod name/icon.
 */
public final class ModJarMetadataExtractor {
    private static final int MAX_TEXT_BYTES = 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_NESTED_JAR_BYTES = 12 * 1024 * 1024;
    private static final int MAX_NESTED_JARS = 48;
    private static final int MAX_RECURSION_DEPTH = 2;

    private ModJarMetadataExtractor() {
    }

    /**
     * Reads both display name and icon in one pass. Use this from background threads
     * when binding installed mod rows so large JarJar mods are not scanned twice.
     */
    @Nullable
    public static Result read(@NonNull File jarFile) {
        Metadata metadata = readMetadata(jarFile);
        if (metadata == null || !metadata.hasAny()) return null;
        return new Result(metadata.displayName, metadata.icon);
    }

    @Nullable
    public static String readDisplayName(@NonNull File jarFile) {
        Result result = read(jarFile);
        return result == null ? null : result.getDisplayName();
    }

    @Nullable
    public static Bitmap readIcon(@NonNull File jarFile) {
        Result result = read(jarFile);
        return result == null ? null : result.getIcon();
    }

    @Nullable
    private static Metadata readMetadata(@NonNull File jarFile) {
        if (!jarFile.isFile()) return null;

        Metadata result = new Metadata();
        try (ZipFile zip = new ZipFile(jarFile)) {
            result.merge(readZipFileMetadata(zip));
            if (!result.isComplete()) {
                result.merge(readNestedJarMetadata(zip));
            }
        } catch (Throwable ignored) {
            return null;
        }

        return result.hasAny() ? result : null;
    }

    @NonNull
    private static Metadata readZipFileMetadata(@NonNull ZipFile zip) throws IOException {
        Metadata metadata = new Metadata();

        String fabricJson = readZipEntryText(zip, "fabric.mod.json");
        metadata.displayName = firstNonBlank(metadata.displayName, extractJsonString(fabricJson, "name"));
        Bitmap icon = decodeZipBitmap(zip, extractJsonIconString(fabricJson));
        if (metadata.icon == null) metadata.icon = icon;

        String quiltJson = readZipEntryText(zip, "quilt.mod.json");
        metadata.displayName = firstNonBlank(metadata.displayName, extractJsonString(quiltJson, "name"));
        icon = decodeZipBitmap(zip, extractJsonIconString(quiltJson));
        if (metadata.icon == null) metadata.icon = icon;

        String forgeToml = readZipEntryText(zip, "META-INF/mods.toml");
        metadata.displayName = firstNonBlank(metadata.displayName, extractTomlString(forgeToml, "displayName"));
        icon = decodeZipBitmap(zip, firstNonBlank(
                extractTomlString(forgeToml, "logoFile"),
                extractTomlString(forgeToml, "catalogueImageIcon")
        ));
        if (metadata.icon == null) metadata.icon = icon;

        String neoForgeToml = readZipEntryText(zip, "META-INF/neoforge.mods.toml");
        metadata.displayName = firstNonBlank(metadata.displayName, extractTomlString(neoForgeToml, "displayName"));
        icon = decodeZipBitmap(zip, firstNonBlank(
                extractTomlString(neoForgeToml, "logoFile"),
                extractTomlString(neoForgeToml, "catalogueImageIcon")
        ));
        if (metadata.icon == null) metadata.icon = icon;

        String mcmodInfo = readZipEntryText(zip, "mcmod.info");
        metadata.displayName = firstNonBlank(metadata.displayName, extractJsonString(mcmodInfo, "name"));

        if (metadata.icon == null) {
            metadata.icon = decodeFirstLikelyIcon(zip);
        }

        return metadata;
    }

    @NonNull
    private static Metadata readNestedJarMetadata(@NonNull ZipFile zip) throws IOException {
        Metadata result = new Metadata();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        int scanned = 0;

        while (entries.hasMoreElements() && scanned < MAX_NESTED_JARS && !result.isComplete()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;

            String name = normalizeZipPath(entry.getName()).toLowerCase(Locale.US);
            if (!name.endsWith(".jar")) continue;

            long size = entry.getSize();
            if (size > MAX_NESTED_JAR_BYTES) continue;

            byte[] bytes = readZipEntryBytes(zip, entry, MAX_NESTED_JAR_BYTES);
            if (bytes == null || bytes.length == 0) continue;

            scanned++;
            result.merge(readMemoryZipMetadata(bytes, 1));
        }

        return result;
    }

    @NonNull
    private static Metadata readMemoryZipMetadata(@NonNull byte[] zipBytes, int depth) throws IOException {
        Metadata result = new Metadata();
        Map<String, String> textEntries = new LinkedHashMap<>();
        Map<String, byte[]> imageEntries = new LinkedHashMap<>();
        ArrayList<byte[]> nestedJars = new ArrayList<>();

        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                String name = normalizeZipPath(entry.getName());
                String lower = name.toLowerCase(Locale.US);

                if (isMetadataEntry(lower)) {
                    byte[] bytes = readStreamBytes(input, MAX_TEXT_BYTES);
                    if (bytes != null) textEntries.put(lower, new String(bytes, "UTF-8"));
                } else if (isImageEntry(lower)) {
                    byte[] bytes = readStreamBytes(input, MAX_IMAGE_BYTES);
                    if (bytes != null) imageEntries.put(lower, bytes);
                } else if (depth < MAX_RECURSION_DEPTH && lower.endsWith(".jar")) {
                    byte[] bytes = readStreamBytes(input, MAX_NESTED_JAR_BYTES);
                    if (bytes != null && nestedJars.size() < MAX_NESTED_JARS) nestedJars.add(bytes);
                }
            }
        }

        String fabricJson = textEntries.get("fabric.mod.json");
        result.displayName = firstNonBlank(result.displayName, extractJsonString(fabricJson, "name"));
        result.icon = firstNonNull(result.icon, decodeMemoryBitmap(imageEntries, extractJsonIconString(fabricJson)));

        String quiltJson = textEntries.get("quilt.mod.json");
        result.displayName = firstNonBlank(result.displayName, extractJsonString(quiltJson, "name"));
        result.icon = firstNonNull(result.icon, decodeMemoryBitmap(imageEntries, extractJsonIconString(quiltJson)));

        String forgeToml = textEntries.get("meta-inf/mods.toml");
        result.displayName = firstNonBlank(result.displayName, extractTomlString(forgeToml, "displayName"));
        result.icon = firstNonNull(result.icon, decodeMemoryBitmap(imageEntries, firstNonBlank(
                extractTomlString(forgeToml, "logoFile"),
                extractTomlString(forgeToml, "catalogueImageIcon")
        )));

        String neoForgeToml = textEntries.get("meta-inf/neoforge.mods.toml");
        result.displayName = firstNonBlank(result.displayName, extractTomlString(neoForgeToml, "displayName"));
        result.icon = firstNonNull(result.icon, decodeMemoryBitmap(imageEntries, firstNonBlank(
                extractTomlString(neoForgeToml, "logoFile"),
                extractTomlString(neoForgeToml, "catalogueImageIcon")
        )));

        String mcmodInfo = textEntries.get("mcmod.info");
        result.displayName = firstNonBlank(result.displayName, extractJsonString(mcmodInfo, "name"));

        if (result.icon == null) {
            result.icon = decodeFirstLikelyMemoryIcon(imageEntries);
        }

        if (!result.isComplete()) {
            for (byte[] nested : nestedJars) {
                if (result.isComplete()) break;
                result.merge(readMemoryZipMetadata(nested, depth + 1));
            }
        }

        return result;
    }

    @Nullable
    private static String readZipEntryText(@NonNull ZipFile zip, @NonNull String entryName) throws IOException {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null || entry.isDirectory()) return null;
        byte[] bytes = readZipEntryBytes(zip, entry, MAX_TEXT_BYTES);
        return bytes == null ? null : new String(bytes, "UTF-8");
    }

    @Nullable
    private static byte[] readZipEntryBytes(@NonNull ZipFile zip, @NonNull ZipEntry entry, int maxBytes) throws IOException {
        try (InputStream input = zip.getInputStream(entry)) {
            return readStreamBytes(input, maxBytes);
        }
    }

    @Nullable
    private static byte[] readStreamBytes(@NonNull InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) return null;
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    @Nullable
    private static Bitmap decodeZipBitmap(@NonNull ZipFile zip, @Nullable String entryName) throws IOException {
        if (isBlank(entryName)) return null;

        String normalized = normalizeZipPath(entryName.trim());
        while (normalized.startsWith("/")) normalized = normalized.substring(1);

        ZipEntry entry = zip.getEntry(normalized);
        if (entry == null && normalized.startsWith("./")) entry = zip.getEntry(normalized.substring(2));
        if (entry == null) entry = findZipEntryBySuffix(zip, normalized);
        if (entry == null || entry.isDirectory()) return null;

        try (InputStream input = zip.getInputStream(entry)) {
            return BitmapFactory.decodeStream(input);
        }
    }

    @Nullable
    private static Bitmap decodeMemoryBitmap(@NonNull Map<String, byte[]> images, @Nullable String entryName) {
        if (isBlank(entryName)) return null;

        String normalized = normalizeZipPath(entryName.trim()).toLowerCase(Locale.US);
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (normalized.startsWith("./")) normalized = normalized.substring(2);

        byte[] bytes = images.get(normalized);
        if (bytes == null) {
            for (Map.Entry<String, byte[]> entry : images.entrySet()) {
                String name = entry.getKey();
                if (name.equals(normalized) || name.endsWith("/" + normalized)) {
                    bytes = entry.getValue();
                    break;
                }
            }
        }

        return bytes == null ? null : BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    @Nullable
    private static Bitmap decodeFirstLikelyIcon(@NonNull ZipFile zip) throws IOException {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;

            String name = normalizeZipPath(entry.getName()).toLowerCase(Locale.US);
            if (!isImageEntry(name) || !isLikelyIconName(name)) continue;

            try (InputStream input = zip.getInputStream(entry)) {
                Bitmap bitmap = BitmapFactory.decodeStream(input);
                if (bitmap != null) return bitmap;
            }
        }
        return null;
    }

    @Nullable
    private static Bitmap decodeFirstLikelyMemoryIcon(@NonNull Map<String, byte[]> images) {
        for (Map.Entry<String, byte[]> entry : images.entrySet()) {
            if (!isLikelyIconName(entry.getKey())) continue;
            byte[] bytes = entry.getValue();
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap != null) return bitmap;
        }
        return null;
    }

    @Nullable
    private static ZipEntry findZipEntryBySuffix(@NonNull ZipFile zip, @NonNull String normalizedName) {
        String lowerName = normalizeZipPath(normalizedName).toLowerCase(Locale.US);
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            String entryName = normalizeZipPath(entry.getName()).toLowerCase(Locale.US);
            if (entryName.equals(lowerName) || entryName.endsWith("/" + lowerName)) return entry;
        }
        return null;
    }

    @Nullable
    private static String extractJsonString(@Nullable String text, @NonNull String key) {
        if (text == null) return null;
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    @Nullable
    private static String extractJsonIconString(@Nullable String text) {
        String directIcon = extractJsonString(text, "icon");
        if (!isBlank(directIcon)) return directIcon;
        if (text == null) return null;

        Matcher objectMatcher = Pattern.compile("\\\"icon\\\"\\s*:\\s*\\{([^}]+)\\}", Pattern.DOTALL).matcher(text);
        if (!objectMatcher.find()) return null;

        Matcher imageMatcher = Pattern.compile("\\\"[^\\\"]+\\\"\\s*:\\s*\\\"([^\\\"]+\\.(?:png|jpg|jpeg|webp))\\\"").matcher(objectMatcher.group(1));
        return imageMatcher.find() ? imageMatcher.group(1) : null;
    }

    @Nullable
    private static String extractTomlString(@Nullable String text, @NonNull String key) {
        if (text == null) return null;
        Matcher matcher = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*=\\s*(?:\\\"([^\\\"]+)\\\"|'([^']+)'|([^#\\r\\n]+))").matcher(text);
        if (!matcher.find()) return null;

        String doubleQuoted = matcher.group(1);
        String singleQuoted = matcher.group(2);
        String raw = matcher.group(3);
        String value = !isBlank(doubleQuoted) ? doubleQuoted : (!isBlank(singleQuoted) ? singleQuoted : raw);
        if (value == null) return null;
        int comment = value.indexOf('#');
        if (comment >= 0) value = value.substring(0, comment);
        return value.trim();
    }

    private static boolean isMetadataEntry(@NonNull String lowerName) {
        return lowerName.equals("fabric.mod.json")
                || lowerName.equals("quilt.mod.json")
                || lowerName.equals("mcmod.info")
                || lowerName.equals("meta-inf/mods.toml")
                || lowerName.equals("meta-inf/neoforge.mods.toml");
    }

    private static boolean isImageEntry(@NonNull String lowerName) {
        return lowerName.endsWith(".png")
                || lowerName.endsWith(".jpg")
                || lowerName.endsWith(".jpeg")
                || lowerName.endsWith(".webp");
    }

    private static boolean isLikelyIconName(@NonNull String lowerName) {
        String name = lowerName;
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < name.length()) name = name.substring(slash + 1);

        return name.equals("icon.png")
                || name.equals("logo.png")
                || name.equals("pack.png")
                || name.equals("mod_icon.png")
                || name.equals("modicon.png")
                || name.endsWith("_icon.png")
                || name.endsWith("-icon.png")
                || name.contains("icon")
                || name.contains("logo");
    }

    @NonNull
    private static String normalizeZipPath(@NonNull String path) {
        return path.replace('\\', '/');
    }

    @Nullable
    private static String firstNonBlank(@Nullable String first, @Nullable String second) {
        return !isBlank(first) ? first : (!isBlank(second) ? second : null);
    }

    @Nullable
    private static Bitmap firstNonNull(@Nullable Bitmap first, @Nullable Bitmap second) {
        return first != null ? first : second;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    public static final class Result {
        @Nullable
        private final String displayName;
        @Nullable
        private final Bitmap icon;

        private Result(@Nullable String displayName, @Nullable Bitmap icon) {
            this.displayName = displayName;
            this.icon = icon;
        }

        @Nullable
        public String getDisplayName() {
            return displayName;
        }

        @Nullable
        public Bitmap getIcon() {
            return icon;
        }
    }

    private static final class Metadata {
        @Nullable
        String displayName;
        @Nullable
        Bitmap icon;

        boolean hasAny() {
            return !isBlank(displayName) || icon != null;
        }

        boolean isComplete() {
            return !isBlank(displayName) && icon != null;
        }

        void merge(@Nullable Metadata other) {
            if (other == null) return;
            if (isBlank(displayName) && !isBlank(other.displayName)) displayName = other.displayName;
            if (icon == null && other.icon != null) icon = other.icon;
        }
    }
}

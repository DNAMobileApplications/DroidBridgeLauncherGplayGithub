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

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

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

/**
 * Android workaround for Not Enough Vulkan builds that still ship the optional fullscreen
 * monitor selector mixin.
 *
 * Desktop-created Modrinth packs can carry the same mod list as a DroidBridge-created pack,
 * but opening VulkanMod's Video Settings may crash on Android when that optional monitor
 * selector is active and VulkanMod cannot resolve a desktop monitor for the Android window.
 * Strip only the monitor-selector mixin declarations from Not Enough Vulkan's mixin JSON; do
 * not remove the rest of Not Enough Vulkan.
 */
public final class NotEnoughVulkanMonitorMitigation {
    private static final String TAG = "NotEnoughVulkanMonitorMitigation";
    private static final String WORK_DIR_NAME = ".javalauncher_patch";
    private static final String MARKER_ENTRY = "META-INF/javalauncher/not_enough_vulkan_android_monitor_workaround";

    private NotEnoughVulkanMonitorMitigation() {
    }

    public static void prepare(@Nullable File gameDir) {
        if (gameDir == null) return;

        List<File> modsDirs = getCandidateModsDirs(gameDir);

        for (File modsDir : modsDirs) {
                File[] mods = modsDir.listFiles(file -> file.isFile() && file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"));
                if (mods == null || mods.length == 0) continue;

                for (File modJar : mods) {
                    if (!isNotEnoughVulkanJarName(modJar.getName())) continue;


                    try {
                        if (isAlreadyPatched(modJar)) {
                            appendLog("Not Enough Vulkan monitor mitigation: already patched " + modJar.getName());
                            continue;
                        }

                        PatchPlan plan = inspectJar(modJar);
                        if (!plan.hasMonitorSelectorReference) {
                            appendLog("Not Enough Vulkan monitor mitigation: no fullscreen monitor selector references found in " + modJar.getName());
                            continue;
                        }

                        if (!plan.hasPatchableJson) {
                            appendLog("Not Enough Vulkan monitor mitigation: monitor selector classes found but no patchable mixin JSON found in " + modJar.getName());
                            continue;
                        }

                        patchJar(modJar);
                        appendLog("Not Enough Vulkan monitor mitigation: disabled fullscreen monitor selector in " + modJar.getName());
                    } catch (Throwable throwable) {
                        appendLog("Not Enough Vulkan monitor mitigation: failed for " + modJar.getAbsolutePath() + ": " + throwable);
                        Log.e(TAG, "Failed to patch Not Enough Vulkan monitor selector: " + modJar.getAbsolutePath(), throwable);
                    }
                }
        }
    }

    @NonNull
    private static List<File> getCandidateModsDirs(@NonNull File gameDir) {
        Set<File> dirs = new LinkedHashSet<>();
        dirs.add(new File(gameDir, "mods"));

        File parent = gameDir.getParentFile();
        if (parent != null) dirs.add(new File(parent, "mods"));

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

    private static boolean isNotEnoughVulkanJarName(@NonNull String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".jar")
                && (lower.contains("not-enough-vulkan")
                || lower.contains("notenoughvulkan")
                || lower.contains("not_enough_vulkan"));
    }

    private static boolean isAlreadyPatched(@NonNull File jarFile) throws IOException {
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            return zipFile.getEntry(MARKER_ENTRY) != null;
        }
    }

    @NonNull
    private static PatchPlan inspectJar(@NonNull File jarFile) throws IOException {
        boolean hasMonitorSelectorReference = false;
        boolean hasPatchableJson = false;

        try (ZipFile zipFile = new ZipFile(jarFile)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                String normalized = name.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);

                if (isMonitorSelectorEntryName(normalized)) {
                    hasMonitorSelectorReference = true;
                }

                if (!entry.isDirectory() && normalized.endsWith(".json")) {
                    byte[] bytes = readEntryBytes(zipFile, entry);
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    if (containsMonitorSelectorText(text)) {
                        hasMonitorSelectorReference = true;
                        if (canPatchJson(text)) {
                            hasPatchableJson = true;
                        }
                    }
                }
            }
        }

        return new PatchPlan(hasMonitorSelectorReference, hasPatchableJson);
    }

    private static void patchJar(@NonNull File jarFile) throws IOException {
        File parentDir = jarFile.getParentFile();
        if (parentDir == null) {
            throw new IOException("Could not resolve Not Enough Vulkan jar parent directory: " + jarFile.getAbsolutePath());
        }

        File workDir = new File(parentDir, WORK_DIR_NAME);
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw new IOException("Could not create mitigation work directory: " + workDir.getAbsolutePath());
        }

        File backup = new File(workDir, jarFile.getName() + ".not-enough-vulkan-monitor-selector.backup");
        File tempFile = new File(workDir, jarFile.getName() + ".not-enough-vulkan-monitor-selector.tmp");

        deleteIfExists(backup);
        deleteIfExists(tempFile);

        copyFile(jarFile, backup);
        appendLog("Not Enough Vulkan monitor mitigation: created backup beside mod jar " + backup.getAbsolutePath());

        boolean changed = false;
        try (ZipFile zipFile = new ZipFile(jarFile);
             ZipOutputStream output = new ZipOutputStream(new FileOutputStream(tempFile))) {

            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            byte[] buffer = new byte[8192];

            while (entries.hasMoreElements()) {
                ZipEntry inEntry = entries.nextElement();
                String name = inEntry.getName();
                String normalized = name.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);

                byte[] replacementBytes = null;
                if (!inEntry.isDirectory() && normalized.endsWith(".json")) {
                    byte[] original = readEntryBytes(zipFile, inEntry);
                    String patched = patchJsonTextIfNeeded(new String(original, StandardCharsets.UTF_8), name);
                    if (patched != null) {
                        replacementBytes = patched.getBytes(StandardCharsets.UTF_8);
                        changed = true;
                    } else {
                        replacementBytes = original;
                    }
                }

                ZipEntry outEntry = new ZipEntry(name);
                outEntry.setTime(inEntry.getTime());

                if (replacementBytes == null && inEntry.getMethod() == ZipEntry.STORED) {
                    outEntry.setMethod(ZipEntry.STORED);
                    outEntry.setSize(inEntry.getSize());
                    outEntry.setCompressedSize(inEntry.getCompressedSize());
                    outEntry.setCrc(inEntry.getCrc());
                }

                output.putNextEntry(outEntry);

                if (!inEntry.isDirectory()) {
                    if (replacementBytes != null) {
                        output.write(replacementBytes);
                    } else {
                        try (InputStream input = zipFile.getInputStream(inEntry)) {
                            int read;
                            while ((read = input.read(buffer)) != -1) {
                                output.write(buffer, 0, read);
                            }
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
            appendLog("Not Enough Vulkan monitor mitigation: nothing changed, leaving original jar untouched");
            return;
        }

        File originalBackup = new File(workDir, jarFile.getName() + ".not-enough-vulkan-monitor-selector.original");
        deleteIfExists(originalBackup);

        if (jarFile.exists() && !jarFile.renameTo(originalBackup)) {
            copyFile(jarFile, originalBackup);
            if (!jarFile.delete()) {
                deleteIfExists(tempFile);
                restoreOriginalJar(backup, jarFile);
                throw new IOException("Could not move original Not Enough Vulkan jar aside: " + jarFile.getAbsolutePath());
            }
        }

        boolean replaced = tempFile.renameTo(jarFile);
        if (!replaced) {
            copyFile(tempFile, jarFile);
            replaced = jarFile.exists() && jarFile.length() > 0L;
        }

        if (!replaced) {
            restoreOriginalJar(backup, jarFile);
            throw new IOException("Could not replace Not Enough Vulkan jar with patched copy: " + jarFile.getAbsolutePath());
        }

        deleteIfExists(originalBackup);
        deleteIfExists(backup);
        deleteIfExists(tempFile);
        appendLog("Not Enough Vulkan monitor mitigation: cleaned temporary files for " + jarFile.getName());
    }

    @Nullable
    private static String patchJsonTextIfNeeded(@NonNull String text, @NonNull String entryName) {
        if (!containsMonitorSelectorText(text)) return null;

        try {
            Object parsed = new JSONTokener(text).nextValue();
            boolean changed = removeMonitorSelectorReferences(parsed);
            if (!changed) return null;

            appendLog("Not Enough Vulkan monitor mitigation: removed monitor selector mixin reference from " + entryName);
            if (parsed instanceof JSONObject) {
                return ((JSONObject) parsed).toString(2);
            }
            if (parsed instanceof JSONArray) {
                return ((JSONArray) parsed).toString(2);
            }
        } catch (Throwable throwable) {
            appendLog("Not Enough Vulkan monitor mitigation: could not parse JSON entry " + entryName + ": " + throwable);
        }

        return null;
    }

    private static boolean canPatchJson(@NonNull String text) {
        try {
            Object parsed = new JSONTokener(text).nextValue();
            return containsRemovableMonitorSelectorReference(parsed);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean containsRemovableMonitorSelectorReference(@Nullable Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONArray names = object.names();
            if (names == null) return false;
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i, null);
                if (key == null) continue;
                if (containsRemovableMonitorSelectorReference(object.opt(key))) return true;
            }
            return false;
        }

        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                if (containsRemovableMonitorSelectorReference(array.opt(i))) return true;
            }
            return false;
        }

        return value instanceof String && isMonitorSelectorMixinValue((String) value);
    }

    private static boolean removeMonitorSelectorReferences(@Nullable Object value) {
        boolean changed = false;

        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONArray names = object.names();
            if (names == null) return false;

            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i, null);
                if (key == null) continue;
                Object child = object.opt(key);
                if (removeMonitorSelectorReferences(child)) changed = true;
            }
            return changed;
        }

        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = array.length() - 1; i >= 0; i--) {
                Object child = array.opt(i);
                if (child instanceof String && isMonitorSelectorMixinValue((String) child)) {
                    array.remove(i);
                    changed = true;
                } else if (removeMonitorSelectorReferences(child)) {
                    changed = true;
                }
            }
        }

        return changed;
    }

    private static boolean containsMonitorSelectorText(@NonNull String text) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("monitor_selector")
                || lower.contains("monitorselector")
                || lower.contains("fullscreenmonitor")
                || lower.contains("fullscreen_monitor");
    }

    private static boolean isMonitorSelectorMixinValue(@NonNull String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("monitor_selector")
                || lower.contains("monitorselector")
                || lower.contains("fullscreenmonitor")
                || lower.contains("fullscreen_monitor");
    }

    private static boolean isMonitorSelectorEntryName(@NonNull String normalizedEntryName) {
        return normalizedEntryName.contains("monitor_selector")
                || normalizedEntryName.contains("monitorselector")
                || normalizedEntryName.contains("fullscreenmonitor")
                || normalizedEntryName.contains("fullscreen_monitor");
    }

    @NonNull
    private static byte[] readEntryBytes(@NonNull ZipFile zipFile, @NonNull ZipEntry entry) throws IOException {
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

    private static void restoreOriginalJar(@NonNull File backup, @NonNull File jarFile) throws IOException {
        if (jarFile.exists() && !jarFile.delete()) {
            throw new IOException("Could not delete failed patched jar: " + jarFile.getAbsolutePath());
        }
        copyFile(backup, jarFile);
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

    private static final class PatchPlan {
        final boolean hasMonitorSelectorReference;
        final boolean hasPatchableJson;

        PatchPlan(boolean hasMonitorSelectorReference, boolean hasPatchableJson) {
            this.hasMonitorSelectorReference = hasMonitorSelectorReference;
            this.hasPatchableJson = hasPatchableJson;
        }
    }
}

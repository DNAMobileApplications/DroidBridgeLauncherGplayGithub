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
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
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

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.runtime.Logger;

/**
 * Podium patched Sodium's old pre-launch compatibility checks for desktop-launcherLauncher. Newer Sodium
 * builds removed/changed that helper, which makes Podium crash during mixin apply before Minecraft
 * can open. Podium is no longer required by DroidBridge, so disable it before Fabric scans mods.
 */
public final class PodiumAutoDisableMitigation {
    private static final String TAG = "PodiumAutoDisable";
    private static final String DISABLED_SUFFIX = ".disabled";

    private PodiumAutoDisableMitigation() {
    }

    public static void prepare(@Nullable File gameDir) {
        if (gameDir == null) return;

        try {
            List<File> modsDirs = getCandidateModsDirs(gameDir);

            for (File modsDir : modsDirs) {
                File[] mods = modsDir.listFiles(file -> file.isFile()
                        && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
                if (mods == null || mods.length == 0) continue;

                for (File modJar : mods) {
                    try {
                        if (!isPodiumJar(modJar)) continue;
                        disablePodiumJar(modJar);
                    } catch (Throwable throwable) {
                        appendLog("Podium auto-disable mitigation: failed for " + modJar.getAbsolutePath() + ": " + throwable);
                        Log.e(TAG, "Failed to disable Podium jar: " + modJar.getAbsolutePath(), throwable);
                    }
                }
            }

        } catch (Throwable throwable) {
            appendLog("Podium auto-disable mitigation failed: " + throwable);
            Log.e(TAG, "Podium auto-disable mitigation failed", throwable);
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

    private static boolean isPodiumJar(@NonNull File jarFile) throws IOException {
        String lowerName = jarFile.getName().toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".jar")) return false;

        boolean nameLooksLikePodium = lowerName.equals("podium.jar")
                || lowerName.startsWith("podium-")
                || lowerName.startsWith("podium_")
                || lowerName.contains("-podium-")
                || lowerName.contains("_podium_");

        try (ZipFile zipFile = new ZipFile(jarFile)) {
            JSONObject metadata = readModMetadata(zipFile, "fabric.mod.json");
            if (metadata != null && hasPodiumModId(metadata)) return true;

            metadata = readModMetadata(zipFile, "quilt.mod.json");
            if (metadata != null && hasPodiumModId(metadata)) return true;
        } catch (Throwable throwable) {
            if (nameLooksLikePodium) {
                appendLog("Podium auto-disable mitigation: metadata read failed, trusting filename for " + jarFile.getName() + ": " + throwable);
            } else {
                appendLog("Podium auto-disable mitigation: metadata read failed, skipping " + jarFile.getName() + ": " + throwable);
            }
        }

        return nameLooksLikePodium;
    }

    @Nullable
    private static JSONObject readModMetadata(@NonNull ZipFile zipFile, @NonNull String entryName) throws IOException, JSONException {
        ZipEntry entry = zipFile.getEntry(entryName);
        if (entry == null || entry.isDirectory()) return null;

        String text = readText(zipFile, entry).trim();
        if (text.isEmpty()) return null;

        Object parsed = new JSONTokener(text).nextValue();
        return parsed instanceof JSONObject ? (JSONObject) parsed : null;
    }

    private static boolean hasPodiumModId(@NonNull JSONObject metadata) {
        if ("podium".equalsIgnoreCase(metadata.optString("id", "").trim())) return true;

        // Quilt metadata often stores the id under quilt_loader.id.
        JSONObject quiltLoader = metadata.optJSONObject("quilt_loader");
        if (quiltLoader != null && "podium".equalsIgnoreCase(quiltLoader.optString("id", "").trim())) return true;

        JSONObject loader = metadata.optJSONObject("loader");
        if (loader != null && "podium".equalsIgnoreCase(loader.optString("id", "").trim())) return true;

        JSONArray jars = metadata.optJSONArray("jars");
        if (jars != null) {
            for (int i = 0; i < jars.length(); i++) {
                JSONObject child = jars.optJSONObject(i);
                if (child != null && "podium".equalsIgnoreCase(child.optString("id", "").trim())) return true;
            }
        }
        return false;
    }

    private static void disablePodiumJar(@NonNull File jarFile) {
        File parent = jarFile.getParentFile();
        if (parent == null) {
            appendLog("Podium auto-disable mitigation: could not resolve parent for " + jarFile.getAbsolutePath());
            return;
        }

        File disabledFile = new File(parent, jarFile.getName() + DISABLED_SUFFIX);
        if (disabledFile.exists()) {
            if (jarFile.delete()) {
                appendLog("Podium auto-disable mitigation: removed duplicate enabled Podium because disabled copy already exists: " + jarFile.getName());
            } else {
                appendLog("Podium auto-disable mitigation: disabled copy already exists but active Podium could not be removed: " + jarFile.getAbsolutePath());
            }
            return;
        }

        if (jarFile.renameTo(disabledFile)) {
            appendLog("Podium auto-disable mitigation: disabled obsolete Podium mod: " + jarFile.getName() + " -> " + disabledFile.getName());
        } else {
            appendLog("Podium auto-disable mitigation: failed to rename Podium jar to disabled: " + jarFile.getAbsolutePath());
        }
    }

    @NonNull
    private static String readText(@NonNull ZipFile zipFile, @NonNull ZipEntry entry) throws IOException {
        byte[] bytes = readEntryBytes(zipFile, entry);
        return new String(bytes, StandardCharsets.UTF_8);
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

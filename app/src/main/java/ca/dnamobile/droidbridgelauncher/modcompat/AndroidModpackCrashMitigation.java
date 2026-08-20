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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.runtime.Logger;

/**
 * Android-only launch guards for desktop modpacks that assume desktop Java/AWT/audio/native media.
 *
 * The MCSX CurseForge pack is a good example: it includes optional menu/media/client visual mods
 * that crash or allocate huge native buffers on Android before the user reaches the title screen.
 * These guards disable only the known Android-incompatible optional pieces while leaving the core
 * gameplay/content mods enabled.
 */
public final class AndroidModpackCrashMitigation {
    private static final String TAG = "AndroidModpackCrash";
    private static final String DISABLED_SUFFIX = ".disabled";

    private AndroidModpackCrashMitigation() {
    }

    public static void prepare(@Nullable File gameDir) {
        if (gameDir == null) return;

        try {
            List<File> modsDirs = getCandidateModsDirs(gameDir);
            int disabledCount = 0;
            boolean hasEntityModelFeatures = hasEnabledModJar(modsDirs, "entity_model_features")
                    || hasEnabledModJar(modsDirs, "entitymodelfeatures");

            for (File modsDir : modsDirs) {
                File[] mods = modsDir.listFiles(file -> file.isFile()
                        && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
                if (mods == null) continue;

                for (File modJar : mods) {
                    String lower = modJar.getName().toLowerCase(Locale.ROOT);
                    String normalized = normalize(lower);
                    String reason = null;

                    if (normalized.contains("villagerarmor") && hasEntityModelFeatures) {
                        reason = "prevents Android/EMF model-layer crash: villagerarmor:illager#inner_armor";
                    } else if (normalized.contains("fmaudioextension") || normalized.startsWith("auudio") || normalized.contains("auudioforge")) {
                        reason = "prevents FancyMenu/Konkrete javax.sound.sampled Clip crash on Android";
                    } else if (normalized.contains("soundphysics")) {
                        reason = "prevents Sound Physics null-config sound-system crash on Android";
                    } else if (normalized.startsWith("videoplayer") || normalized.contains("watermedia")) {
                        reason = "prevents unsupported VLC/native media discovery and WaterMedia native-buffer OOM on Android";
                    }

                    if (reason != null && disableJar(modJar, reason)) {
                        disabledCount++;
                    }
                }
            }

            int disabledAudioFiles = disableFancyMenuAnimationAudio(gameDir);
            if (disabledAudioFiles > 0) {
                appendLog("Android modpack crash mitigation: disabled " + disabledAudioFiles + " FancyMenu animation audio file(s)");
            }

        } catch (Throwable throwable) {
            appendLog("Android modpack crash mitigation: failed: " + throwable);
            Logging.e(TAG, "Failed to apply Android modpack crash mitigation", throwable);
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

    private static boolean hasEnabledModJar(@NonNull List<File> modsDirs, @NonNull String needle) {
        String normalizedNeedle = normalize(needle);
        for (File modsDir : modsDirs) {
            File[] mods = modsDir.listFiles(file -> file.isFile()
                    && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
            if (mods == null) continue;
            for (File mod : mods) {
                if (normalize(mod.getName()).contains(normalizedNeedle)) return true;
            }
        }
        return false;
    }

    private static boolean disableJar(@NonNull File jarFile, @NonNull String reason) {
        File parent = jarFile.getParentFile();
        if (parent == null) return false;

        File disabledFile = new File(parent, jarFile.getName() + DISABLED_SUFFIX);
        if (disabledFile.exists()) {
            if (jarFile.delete()) {
                appendLog("Android modpack crash mitigation: removed duplicate enabled jar because disabled copy already exists: "
                        + jarFile.getName() + " (" + reason + ")");
                return true;
            }
            appendLog("Android modpack crash mitigation: disabled copy already exists but active jar could not be removed: "
                    + jarFile.getAbsolutePath());
            return false;
        }

        if (jarFile.renameTo(disabledFile)) {
            appendLog("Android modpack crash mitigation: disabled " + jarFile.getName()
                    + " -> " + disabledFile.getName() + " (" + reason + ")");
            return true;
        }

        appendLog("Android modpack crash mitigation: failed to disable " + jarFile.getAbsolutePath());
        return false;
    }

    private static int disableFancyMenuAnimationAudio(@NonNull File gameDir) {
        File fancyMenuDir = new File(new File(gameDir, "config"), "fancymenu");
        if (!fancyMenuDir.isDirectory()) return 0;
        return disableAudioFilesRecursive(fancyMenuDir, 0);
    }

    private static int disableAudioFilesRecursive(@NonNull File dir, int depth) {
        if (depth > 8) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;

        int count = 0;
        for (File file : files) {
            if (file.isDirectory()) {
                count += disableAudioFilesRecursive(file, depth + 1);
                continue;
            }
            if (!file.isFile()) continue;

            String lower = file.getName().toLowerCase(Locale.ROOT);
            if (lower.endsWith(DISABLED_SUFFIX)) continue;
            if (!(lower.endsWith(".wav") || lower.endsWith(".ogg") || lower.endsWith(".mp3"))) continue;

            File disabled = new File(file.getParentFile(), file.getName() + DISABLED_SUFFIX);
            if (disabled.exists()) {
                if (file.delete()) count++;
            } else if (file.renameTo(disabled)) {
                count++;
            }
        }
        return count;
    }

    @NonNull
    private static String normalize(@NonNull String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");
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

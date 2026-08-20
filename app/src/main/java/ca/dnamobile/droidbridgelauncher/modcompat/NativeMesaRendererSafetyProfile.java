/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */
package ca.dnamobile.droidbridgelauncher.modcompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.renderer.DroidBridgeMesaSupport;
import ca.dnamobile.droidbridgelauncher.renderer.RendererInterface;
import ca.dnamobile.droidbridgelauncher.runtime.Logger;

/** Keeps known optional 1.20.1 renderer add-ons away from direct KGSL. */
public final class NativeMesaRendererSafetyProfile {
    private static final String TAG = "NativeMesaSafety";
    private static final String DISABLED_SUFFIX = ".droidbridge-native-mesa-disabled";

    private NativeMesaRendererSafetyProfile() { }

    public static void prepare(@Nullable File gameDir, @Nullable String minecraftVersion,
                               @Nullable RendererInterface renderer) {
        if (gameDir == null) return;
        boolean isolate = isMinecraft1201(minecraftVersion)
                && DroidBridgeMesaSupport.isDroidBridgeMesaRenderer(renderer);
        try {
            List<File> dirs = getCandidateModsDirs(gameDir);
            int restored = restoreAllPreviouslyDisabled(dirs, isolate);
            int disabled = isolate ? disableKnownOptionalRenderHooks(dirs) : 0;
            if (restored > 0 || disabled > 0) {
                appendLog("Native Mesa safety profile: restored=" + restored
                        + " disabled=" + disabled);
            }
        } catch (Throwable t) {
            appendLog("Native Mesa safety profile failed: " + t);
            Logging.e(TAG, "Native Mesa safety profile failed", t);
        }
    }

    private static boolean isMinecraft1201(@Nullable String v) {
        if (v == null) return false;
        String s = v.toLowerCase(Locale.ROOT);
        return s.contains("1.20.1") && !s.contains("1.20.10") && !s.contains("1.20.11");
    }

    private static int disableKnownOptionalRenderHooks(List<File> dirs) {
        int count = 0;
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            File[] files = dir.listFiles(File::isFile);
            if (files == null) continue;
            Arrays.sort(files, (a,b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : files) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (!n.endsWith(".jar")) continue;
                boolean optional = n.contains("embeddiumextra") || n.contains("embeddium-extra")
                        || n.contains("rubidiumextra") || n.contains("rubidium-extra")
                        || n.contains("sodium-extra") || n.contains("sodiumextra")
                        || n.contains("dynamiclights") || n.contains("dynamic-lights")
                        || n.contains("oculus") || n.contains("iris");
                if (!optional) continue;
                File target = new File(f.getParentFile(), f.getName() + DISABLED_SUFFIX);
                if (!target.exists() && f.renameTo(target)) {
                    count++;
                    appendLog("Native Mesa safety profile: disabled optional renderer hook " + f.getName());
                }
            }
        }
        return count;
    }

    private static int restoreAllPreviouslyDisabled(List<File> dirs, boolean keepIsolation) {
        int restored = 0;
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            File[] files = dir.listFiles(File::isFile);
            if (files == null) continue;
            for (File f : files) {
                String n = f.getName();
                if (!n.endsWith(DISABLED_SUFFIX)) continue;
                String original = n.substring(0, n.length() - DISABLED_SUFFIX.length());
                String lower = original.toLowerCase(Locale.ROOT);
                boolean isolated = lower.contains("embeddiumextra") || lower.contains("embeddium-extra")
                        || lower.contains("rubidiumextra") || lower.contains("rubidium-extra")
                        || lower.contains("sodium-extra") || lower.contains("sodiumextra")
                        || lower.contains("dynamiclights") || lower.contains("dynamic-lights")
                        || lower.contains("oculus") || lower.contains("iris");
                if (keepIsolation && isolated) continue;
                File target = new File(f.getParentFile(), original);
                if (!target.exists() && f.renameTo(target)) restored++;
            }
        }
        return restored;
    }

    private static List<File> getCandidateModsDirs(@NonNull File gameDir) {
        List<File> dirs = new ArrayList<>();
        dirs.add(new File(gameDir, "mods"));
        File instance = gameDir.getParentFile();
        if (instance != null) dirs.add(new File(instance, "mods"));
        File root = instance != null ? instance.getParentFile() : null;
        if (root != null) dirs.add(new File(root, "mods"));
        return dirs;
    }

    private static void appendLog(String s) {
        try { Logger.appendToLog(s); } catch (Throwable ignored) { }
    }
}

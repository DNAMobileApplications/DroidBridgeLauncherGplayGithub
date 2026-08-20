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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.renderer.RendererInterface;
import ca.dnamobile.droidbridgelauncher.runtime.Logger;

/**
 * Android renderer compatibility for Sodium Extra / Embeddium Extra / Rubidium Extra.
 *
 * Sodium Extra/Rubidium Extra adds Android-hostile option-page mixins for
 * adaptive sync, display resolution, and vsync. On DroidBridge's Android GLFW
 * stubs the injected sliders can receive an invalid min/max range and crash
 * when the Video Settings screen opens. The mixins are optional UI add-ons;
 * disabling them keeps Sodium/Embeddium, Iris/Oculus, Sodium Options API, and
 * the rest of Sodium Extra loaded while avoiding the broken Android monitor
 * path.
 */
public final class NativeMesaSodiumExtraMixinMitigation {
    private static final String TAG = "NativeMesaSodiumExtra";
    private static final String CONFIG_FILE = "sodium-extra.properties";

    private static final String[] DISABLED_MIXINS = new String[] {
            "mixin.adaptive_sync",
            "mixin.sodium.resolution",
            "mixin.sodium.vsync",
            "mixin.reduce_resolution_on_mac"
    };

    private NativeMesaSodiumExtraMixinMitigation() {
    }

    public static void prepare(
            @Nullable File gameDir,
            @Nullable String minecraftVersion,
            @Nullable RendererInterface renderer
    ) {
        if (gameDir == null) return;
        if (!isAffectedAndroidRenderer(renderer)) return;

        try {
            List<File> modsDirs = getCandidateModsDirs(gameDir);
            if (!hasSodiumExtraLikeMod(modsDirs)) {
                return;
            }

            File configDir = new File(gameDir, "config");
            if (!configDir.isDirectory() && !configDir.mkdirs()) {
                appendLog("Android Sodium Extra mitigation: unable to create config directory "
                        + configDir.getAbsolutePath());
                return;
            }

            File configFile = new File(configDir, CONFIG_FILE);
            Properties properties = new Properties();
            if (configFile.isFile()) {
                try (FileInputStream input = new FileInputStream(configFile)) {
                    properties.load(input);
                }
            }

            boolean changed = false;
            for (String key : DISABLED_MIXINS) {
                Object old = properties.setProperty(key, "false");
                if (!"false".equals(String.valueOf(old))) {
                    changed = true;
                }
            }

            if (changed || !configFile.isFile()) {
                try (FileOutputStream output = new FileOutputStream(configFile)) {
                    properties.store(output,
                            "DroidBridge Android renderer compatibility: disable Sodium Extra monitor/vsync/resolution mixins");
                }
            }

            appendLog("Android Sodium Extra compatibility: monitor/vsync/resolution mixins "
                    + (changed ? "updated" : "verified"));
        } catch (Throwable throwable) {
            appendLog("Android Sodium Extra compatibility failed: " + throwable);
            Logging.e(TAG, "Failed to apply Android Sodium Extra mixin mitigation", throwable);
        }
    }

    private static boolean hasSodiumExtraLikeMod(@NonNull List<File> modsDirs) {
        for (File modsDir : modsDirs) {
            if (!modsDir.isDirectory()) continue;

            File[] files = modsDir.listFiles(File::isFile);
            if (files == null) continue;

            Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File file : files) {
                String lower = file.getName().toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".jar")) continue;
                String normalized = lower.replace("-", "").replace("_", "").replace(" ", "");
                if (normalized.contains("rubidiumextra")
                        || normalized.contains("embeddiumextra")
                        || normalized.contains("sodiumextra")) {
                    return true;
                }
            }
        }
        return false;
    }

    @NonNull
    private static List<File> getCandidateModsDirs(@NonNull File gameDir) {
        List<File> dirs = new ArrayList<>();
        dirs.add(new File(gameDir, "mods"));

        File instanceDir = gameDir.getParentFile();
        if (instanceDir != null) {
            File instanceMods = new File(instanceDir, "mods");
            if (!sameFilePath(instanceMods, dirs.get(0))) dirs.add(instanceMods);
        }

        File root = gameDir.getParentFile();
        if (root != null) root = root.getParentFile();
        if (root != null) {
            File sharedMods = new File(root, "mods");
            boolean seen = false;
            for (File dir : dirs) {
                if (sameFilePath(dir, sharedMods)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) dirs.add(sharedMods);
        }

        return dirs;
    }

    private static boolean sameFilePath(@NonNull File a, @NonNull File b) {
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (Throwable ignored) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }

    private static boolean isAffectedAndroidRenderer(@Nullable RendererInterface renderer) {
        if (renderer == null) return false;
        String combined = rendererName(renderer).toLowerCase(Locale.ROOT);
        return combined.contains("mobileglues")
                || combined.contains("com.fcl.plugin.mobileglues")
                || combined.contains("native mesa")
                || combined.contains("native_glfw_kgsl")
                || combined.contains("freedreno_kgsl")
                || combined.contains("opengles3");
    }

    @NonNull
    private static String rendererName(@Nullable RendererInterface renderer) {
        if (renderer == null) return "<null>";
        StringBuilder sb = new StringBuilder();
        try {
            sb.append(renderer.getRendererName());
        } catch (Throwable ignored) {
            sb.append(renderer.getClass().getSimpleName());
        }
        try {
            sb.append('/').append(renderer.getRendererId());
        } catch (Throwable ignored) {
        }
        try {
            sb.append('/').append(renderer.getUniqueIdentifier());
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value != null ? value : "<null>";
    }

    private static void appendBlankLogLine() {
        try {
            Logger.appendToLog("");
        } catch (Throwable ignored) {
        }
    }

    private static void appendLog(@NonNull String message) {
        try {
            Logger.appendToLog(message);
        } catch (Throwable ignored) {
        }
    }
}

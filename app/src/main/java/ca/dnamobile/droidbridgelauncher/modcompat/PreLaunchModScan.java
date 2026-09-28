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
import java.util.zip.ZipFile;

/**
 * One inexpensive filename scan shared by the pre-launch compatibility path.
 *
 * The individual mitigations still perform their own exact validation before
 * changing a jar or config. This class only prevents unrelated mitigations from
 * repeatedly walking the same mod directories and writing "not found" blocks
 * into latestlog.txt on every launch.
 */
public final class PreLaunchModScan {
    private static final String SODIUM_MOBILEGLUES_DISABLED_SUFFIX =
            ".disabled-by-droidbridge-mobileglues";
    private static final String NATIVE_MESA_DISABLED_SUFFIX =
            ".droidbridge-native-mesa-disabled";
    private static final String SODIUM_MOBILEGLUES_WORK_DIR =
            ".droidbridge/compat-backups/sodium-mobileglues";

    public final boolean hasVulkanMod;
    public final boolean hasNotEnoughVulkan;
    public final boolean hasDistantHorizons;
    public final boolean hasVoxy;
    public final boolean hasIris;
    public final boolean hasOculus;
    public final boolean hasSodiumLike;
    public final boolean hasSodiumExtraLike;
    public final boolean hasAndroidCrashCandidate;
    public final boolean hasSodiumMobileGluesDisabledJar;
    public final boolean hasSodiumMobileGluesBackupState;
    public final boolean hasNativeMesaDisabledJar;
    public final boolean hasNativeMesaOptionalHook;

    private PreLaunchModScan(
            boolean hasVulkanMod,
            boolean hasNotEnoughVulkan,
            boolean hasDistantHorizons,
            boolean hasVoxy,
            boolean hasIris,
            boolean hasOculus,
            boolean hasSodiumLike,
            boolean hasSodiumExtraLike,
            boolean hasAndroidCrashCandidate,
            boolean hasSodiumMobileGluesDisabledJar,
            boolean hasSodiumMobileGluesBackupState,
            boolean hasNativeMesaDisabledJar,
            boolean hasNativeMesaOptionalHook
    ) {
        this.hasVulkanMod = hasVulkanMod;
        this.hasNotEnoughVulkan = hasNotEnoughVulkan;
        this.hasDistantHorizons = hasDistantHorizons;
        this.hasVoxy = hasVoxy;
        this.hasIris = hasIris;
        this.hasOculus = hasOculus;
        this.hasSodiumLike = hasSodiumLike;
        this.hasSodiumExtraLike = hasSodiumExtraLike;
        this.hasAndroidCrashCandidate = hasAndroidCrashCandidate;
        this.hasSodiumMobileGluesDisabledJar = hasSodiumMobileGluesDisabledJar;
        this.hasSodiumMobileGluesBackupState = hasSodiumMobileGluesBackupState;
        this.hasNativeMesaDisabledJar = hasNativeMesaDisabledJar;
        this.hasNativeMesaOptionalHook = hasNativeMesaOptionalHook;
    }

    @NonNull
    public static PreLaunchModScan scan(@Nullable File gameDir) {
        if (gameDir == null) {
            return empty();
        }

        boolean vulkanMod = false;
        boolean notEnoughVulkan = false;
        boolean distantHorizons = false;
        boolean voxy = false;
        boolean iris = false;
        boolean oculus = false;
        boolean sodiumLike = false;
        boolean sodiumExtraLike = false;
        boolean androidCrashCandidate = false;
        boolean sodiumMobileGluesDisabledJar = false;
        boolean sodiumMobileGluesBackupState =
                new File(gameDir, SODIUM_MOBILEGLUES_WORK_DIR).exists();
        boolean nativeMesaDisabledJar = false;
        boolean nativeMesaOptionalHook = false;
        boolean entityModelFeatures = false;
        boolean villagerArmor = false;

        for (File modsDir : getCandidateModsDirs(gameDir)) {
            File[] files = modsDir.listFiles(File::isFile);
            if (files == null || files.length == 0) continue;

            for (File file : files) {
                String lower = file.getName().toLowerCase(Locale.ROOT);

                if (lower.endsWith(SODIUM_MOBILEGLUES_DISABLED_SUFFIX)) {
                    sodiumMobileGluesDisabledJar = true;
                }
                if (lower.endsWith(NATIVE_MESA_DISABLED_SUFFIX)) {
                    nativeMesaDisabledJar = true;
                }
                if (!lower.endsWith(".jar")) continue;

                String normalized = normalize(lower);

                if (normalized.contains("vulkanmod")
                        && !normalized.contains("vulkanmodandroidlibs")) {
                    vulkanMod = true;
                }
                if (normalized.contains("notenoughvulkan")) {
                    notEnoughVulkan = true;
                }
                if (!distantHorizons
                        && (normalized.contains("distanthorizons")
                        || isDistantHorizonsJar(file))) {
                    distantHorizons = true;
                }
                if (normalized.startsWith("voxy") || normalized.contains("cortexvoxy")) {
                    voxy = true;
                }
                if (normalized.contains("iris")) {
                    iris = true;
                }
                if (normalized.contains("oculus")) {
                    oculus = true;
                }

                boolean sodiumExtra = normalized.contains("sodiumextra")
                        || normalized.contains("embeddiumextra")
                        || normalized.contains("rubidiumextra");
                if (sodiumExtra) {
                    sodiumExtraLike = true;
                    nativeMesaOptionalHook = true;
                }

                boolean sodiumFamily = normalized.contains("sodium")
                        || normalized.contains("embeddium")
                        || normalized.contains("rubidium");
                if (sodiumFamily && !sodiumExtra) {
                    sodiumLike = true;
                }

                if (normalized.contains("dynamiclights")
                        || normalized.contains("oculus")
                        || normalized.contains("iris")) {
                    nativeMesaOptionalHook = true;
                }

                if (normalized.contains("entitymodelfeatures")) {
                    entityModelFeatures = true;
                }
                if (normalized.contains("villagerarmor")) {
                    villagerArmor = true;
                }
                if (normalized.contains("fmaudioextension")
                        || normalized.startsWith("auudio")
                        || normalized.contains("auudioforge")
                        || normalized.contains("soundphysics")
                        || normalized.startsWith("videoplayer")
                        || normalized.contains("watermedia")) {
                    androidCrashCandidate = true;
                }
            }
        }

        if (villagerArmor && entityModelFeatures) {
            androidCrashCandidate = true;
        }

        // FancyMenu media is config-driven and may remain after its optional audio
        // extension jar has already been disabled by an earlier launch.
        if (new File(new File(gameDir, "config"), "fancymenu").isDirectory()) {
            androidCrashCandidate = true;
        }

        return new PreLaunchModScan(
                vulkanMod,
                notEnoughVulkan,
                distantHorizons,
                voxy,
                iris,
                oculus,
                sodiumLike,
                sodiumExtraLike,
                androidCrashCandidate,
                sodiumMobileGluesDisabledJar,
                sodiumMobileGluesBackupState,
                nativeMesaDisabledJar,
                nativeMesaOptionalHook
        );
    }

    public boolean hasDistantHorizonsIrisPair() {
        return hasDistantHorizons && hasIris;
    }

    public boolean needsSodiumMobileGluesMaintenance() {
        // The shader rewrite is retired. Only enter the class when an older
        // DroidBridge build left a backup or disabled jar that needs cleanup.
        return hasSodiumMobileGluesDisabledJar || hasSodiumMobileGluesBackupState;
    }

    public boolean hasReportedCompatibilityMod() {
        return hasVulkanMod
                || hasNotEnoughVulkan
                || hasDistantHorizons
                || hasVoxy
                || hasSodiumExtraLike;
    }

    /**
     * True only when the pre-launch compatibility path has useful work to do
     * or a relevant mod should be shown in the concise latestlog summary.
     */
    public boolean hasCompatibilityWork() {
        return hasReportedCompatibilityMod()
                || hasIris
                || hasOculus
                || hasAndroidCrashCandidate
                || needsSodiumMobileGluesMaintenance()
                || hasNativeMesaOptionalHook
                || hasNativeMesaDisabledJar;
    }

    @NonNull
    public String describeReportedCompatibilityMods() {
        return describeCompatibilityMods();
    }

    @NonNull
    public String describeCompatibilityMods() {
        ArrayList<String> names = new ArrayList<>();
        if (hasVulkanMod) names.add("VulkanMod");
        if (hasNotEnoughVulkan) names.add("Not Enough Vulkan");
        if (hasDistantHorizons) names.add("Distant Horizons");
        if (hasVoxy) names.add("Voxy");
        if (hasIris) names.add("Iris");
        if (hasOculus) names.add("Oculus");
        if (hasSodiumExtraLike) names.add("Sodium/Embeddium/Rubidium Extra");
        if (hasAndroidCrashCandidate) names.add("Android modpack safety");
        if (names.isEmpty() && hasNativeMesaOptionalHook) {
            names.add("Native Mesa compatibility");
        }
        if (names.isEmpty() && (needsSodiumMobileGluesMaintenance() || hasNativeMesaDisabledJar)) {
            names.add("Legacy compatibility cleanup");
        }
        return join(names);
    }

    @NonNull
    private static PreLaunchModScan empty() {
        return new PreLaunchModScan(
                false, false, false, false, false, false, false,
                false, false, false, false, false, false
        );
    }

    @NonNull
    private static List<File> getCandidateModsDirs(@NonNull File gameDir) {
        Set<String> seenPaths = new LinkedHashSet<>();
        ArrayList<File> dirs = new ArrayList<>();

        addDirectory(dirs, seenPaths, new File(gameDir, "mods"));

        File parent = gameDir.getParentFile();
        if (parent != null) {
            addDirectory(dirs, seenPaths, new File(parent, "mods"));
        }

        File grandParent = parent != null ? parent.getParentFile() : null;
        if (grandParent != null) {
            addDirectory(dirs, seenPaths, new File(grandParent, "mods"));
        }

        File greatGrandParent = grandParent != null ? grandParent.getParentFile() : null;
        if (greatGrandParent != null) {
            addDirectory(dirs, seenPaths, new File(greatGrandParent, "mods"));
        }

        File minecraftRoot = findMinecraftRoot(gameDir);
        if (minecraftRoot != null) {
            addDirectory(dirs, seenPaths, new File(minecraftRoot, "mods"));
        }

        return dirs;
    }

    private static void addDirectory(
            @NonNull List<File> out,
            @NonNull Set<String> seenPaths,
            @Nullable File directory
    ) {
        if (directory == null) return;

        String path;
        try {
            path = directory.getCanonicalPath();
        } catch (Throwable ignored) {
            path = directory.getAbsolutePath();
        }

        if (seenPaths.add(path)) {
            out.add(directory);
        }
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


    /**
     * Distant Horizons can replace/update its own jar on shutdown.  The replacement
     * filename is not guaranteed to keep the words "DistantHorizons", which made
     * the old filename-only pre-launch scan silently miss a mod Fabric could still
     * load.  Use an exact DH API class marker as a fallback so GC/SQLite/Zstd and
     * shutdown compatibility remain active even after a self-update or manual rename.
     */
    private static boolean isDistantHorizonsJar(@NonNull File file) {
        try (ZipFile zip = new ZipFile(file)) {
            return zip.getEntry("com/seibel/distanthorizons/api/DhApi.class") != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @NonNull
    private static String normalize(@NonNull String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "")
                .replace(".", "");
    }

    @NonNull
    private static String join(@NonNull List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append(", ");
            out.append(value);
        }
        return out.toString();
    }
}

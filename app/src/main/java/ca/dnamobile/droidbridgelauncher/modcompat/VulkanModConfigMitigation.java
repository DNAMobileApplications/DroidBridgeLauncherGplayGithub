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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.runtime.Logger;

public final class VulkanModConfigMitigation {
    private static final String TAG = "VulkanModConfigMitigation";

    private static final Pattern VERSION_AFTER_DASH_PATTERN = Pattern.compile(
            "(?i)vulkanmod(?:[_-][0-9]+\\.[0-9]+(?:\\.[0-9]+)?)?-([0-9]+\\.[0-9]+\\.[0-9]+).*?\\.jar$"
    );
    private static final Pattern ANY_VERSION_PATTERN = Pattern.compile(
            "([0-9]+\\.[0-9]+\\.[0-9]+)"
    );

    private VulkanModConfigMitigation() {
    }

    public static void prepare(@Nullable File gameDir) {
        if (gameDir == null) return;

        try {
            File modJar = findVulkanModJar(gameDir);
            if (modJar == null) {
                appendLog("VulkanMod config mitigation: no VulkanMod jar found.");
                return;
            }

            String version = extractVulkanModVersion(modJar.getName());
            if (version == null || version.trim().isEmpty()) {
                appendLog("VulkanMod config mitigation: VulkanMod found but version could not be parsed: " + modJar.getName());
                return;
            }

            boolean needsWindowModeMitigation = compareVersions(version, "0.6.0") >= 0;
            boolean needsLegacyTerrainRollback = isAffectedMinecraft12111TerrainBuild(modJar, version);

            if (!needsWindowModeMitigation && !needsLegacyTerrainRollback) {
                appendLog("VulkanMod config mitigation: skipped for VulkanMod " + version);
                return;
            }

            File configDir = new File(gameDir, "config");
            if (!configDir.exists() && !configDir.mkdirs()) {
                appendLog("VulkanMod config mitigation: could not create config dir: " + configDir.getAbsolutePath());
                return;
            }

            File configFile = new File(configDir, "vulkanmod_settings.json");

            JSONObject json = readJson(configFile);

            boolean rolledBackLegacyTerrainOverride = needsLegacyTerrainRollback
                    && rollbackUnsafeLegacyTerrainOverride(json);

            if (needsWindowModeMitigation) {
                /*
                 * DroidBridge needs the same VulkanMod windowMode guard that worked in the prior implementation.
                 * Apply this to the old 1.21.10 VulkanMod line too. The previous code could
                 * accidentally pick vulkanMod-android-libs-0.2.0.jar first and then skip the real
                 * VulkanMod_1.21.10-0.6.0.jar.
                 */
                sanitizeVulkanModJson(json);
            }

            Files.write(
                    configFile.toPath(),
                    json.toString(2).getBytes(StandardCharsets.UTF_8)
            );

            if (needsWindowModeMitigation) {
                sanitizeMinecraftOptions(gameDir);
                appendLog("VulkanMod config mitigation: forced Android-safe video state for VulkanMod " + version);
            }

            if (rolledBackLegacyTerrainOverride) {
                appendLog("VulkanMod terrain mitigation rollback: removed unsafe legacy culling/layer override for VulkanMod " + version);
            }
        } catch (Throwable throwable) {
            appendLog("VulkanMod config mitigation: failed: " + throwable);
            Logging.e(TAG, "Failed to apply VulkanMod config mitigation", throwable);
        }
    }

    private static boolean isAffectedMinecraft12111TerrainBuild(
            @NonNull File modJar,
            @NonNull String version
    ) {
        // Restrict cleanup to the build that received DroidBridge's short-lived
        // terrain setting override. Newer VulkanMod releases were never modified.
        if (compareVersions(version, "0.6.2") >= 0) return false;

        String lowerName = modJar.getName().toLowerCase(java.util.Locale.ROOT);
        return lowerName.contains("1.21.11");
    }

    private static boolean rollbackUnsafeLegacyTerrainOverride(@NonNull JSONObject json) {
        try {
            // DroidBridge 0.7.8 briefly forced this exact three-value combination
            // for VulkanMod 0.6.1. It can stall the first terrain frame on Android.
            // Remove only that exact combination so unrelated user settings remain intact.
            boolean matchesUnsafeOverride = json.has("advCulling")
                    && json.optInt("advCulling", Integer.MIN_VALUE) == 0
                    && json.has("backFaceCulling")
                    && !json.optBoolean("backFaceCulling", true)
                    && json.has("uniqueOpaqueLayer")
                    && !json.optBoolean("uniqueOpaqueLayer", true);
            if (!matchesUnsafeOverride) return false;

            json.remove("advCulling");
            json.remove("backFaceCulling");
            json.remove("uniqueOpaqueLayer");
            return true;
        } catch (Throwable throwable) {
            appendLog("VulkanMod terrain mitigation rollback failed: " + throwable);
            return false;
        }
    }

    private static void sanitizeVulkanModJson(@NonNull JSONObject json) {
        try {
            JSONObject videoMode = json.optJSONObject("videoMode");
            if (videoMode == null) {
                videoMode = new JSONObject();
            }

            videoMode.put("width", -1);
            videoMode.put("height", -1);
            videoMode.put("bitDepth", -1);
            videoMode.put("refreshRate", -1);

            json.put("videoMode", videoMode);
            json.put("windowMode", 2);

            /*
             * Some PC-created packs or compatibility mods can leave monitor selector state in the
             * VulkanMod config. Android's GLFW bridge exposes a synthetic display, so desktop monitor
             * names/ids are not portable. Remove monitor hints but keep normal rendering options.
             */
            removeDesktopMonitorKeys(json);
        } catch (Throwable throwable) {
            appendLog("VulkanMod config mitigation: could not sanitize VulkanMod JSON: " + throwable);
        }
    }

    private static boolean removeDesktopMonitorKeys(@Nullable Object value) {
        boolean changed = false;

        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONArray names = object.names();
            if (names == null) return false;

            for (int i = names.length() - 1; i >= 0; i--) {
                String key = names.optString(i, null);
                if (key == null) continue;

                if (isDesktopMonitorKey(key)) {
                    object.remove(key);
                    changed = true;
                } else if (removeDesktopMonitorKeys(object.opt(key))) {
                    changed = true;
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                if (removeDesktopMonitorKeys(array.opt(i))) changed = true;
            }
        }

        return changed;
    }

    private static boolean isDesktopMonitorKey(@NonNull String key) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("monitor")
                || lower.contains("display") && lower.contains("name")
                || lower.contains("fullscreen") && lower.contains("resolution");
    }

    private static void sanitizeMinecraftOptions(@NonNull File gameDir) {
        File optionsFile = new File(gameDir, "options.txt");
        if (!optionsFile.isFile()) return;

        try {
            List<String> lines = Files.readAllLines(optionsFile.toPath(), StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>();
            boolean sawFullscreen = false;
            boolean sawFullscreenResolution = false;
            boolean changed = false;

            for (String line : lines) {
                if (line == null) continue;

                if (line.startsWith("fullscreen:")) {
                    sawFullscreen = true;
                    if (!"fullscreen:false".equals(line)) changed = true;
                    out.add("fullscreen:false");
                } else if (line.startsWith("fullscreenResolution:")) {
                    sawFullscreenResolution = true;
                    if (!"fullscreenResolution:current".equals(line)) changed = true;
                    out.add("fullscreenResolution:current");
                } else {
                    out.add(line);
                }
            }

            if (!sawFullscreen) {
                out.add("fullscreen:false");
                changed = true;
            }
            if (!sawFullscreenResolution) {
                out.add("fullscreenResolution:current");
                changed = true;
            }

            if (changed) {
                Files.write(optionsFile.toPath(), out, StandardCharsets.UTF_8);
                appendLog("VulkanMod config mitigation: reset Minecraft fullscreen/options.txt monitor state");
            }
        } catch (Throwable throwable) {
            appendLog("VulkanMod config mitigation: could not sanitize options.txt: " + throwable);
        }
    }

    @NonNull
    private static JSONObject readJson(@NonNull File configFile) {
        if (!configFile.isFile()) {
            return new JSONObject();
        }

        try {
            String text = new String(
                    Files.readAllBytes(configFile.toPath()),
                    StandardCharsets.UTF_8
            );
            return new JSONObject(text);
        } catch (Throwable throwable) {
            appendLog("VulkanMod config mitigation: invalid config JSON, recreating: " + configFile.getAbsolutePath());
            return new JSONObject();
        }
    }

    @Nullable
    private static File findVulkanModJar(@NonNull File gameDir) {
        File parent = gameDir.getParentFile();
        File minecraftRoot = findMinecraftRoot(gameDir);

        File[] candidates = minecraftRoot != null
                ? new File[]{
                new File(gameDir, "mods"),
                parent != null ? new File(parent, "mods") : null,
                new File(minecraftRoot, "mods")
        }
                : new File[]{
                new File(gameDir, "mods"),
                parent != null ? new File(parent, "mods") : null
        };

        for (File dir : candidates) {
            if (dir == null || !dir.isDirectory()) continue;

            File[] files = dir.listFiles();
            if (files == null) continue;

            for (File file : files) {
                if (!file.isFile()) continue;

                String name = file.getName();
                String lowerName = name.toLowerCase(java.util.Locale.ROOT);
                if (!lowerName.endsWith(".jar")) continue;
                if (!lowerName.contains("vulkanmod")) continue;

                // Do not select helper/library jars like vulkanMod-android-libs-0.2.0.jar.
                // We need the real VulkanMod jar, otherwise version parsing sees 0.2.0 and skips.
                if (lowerName.contains("android-libs") || lowerName.contains("android_libs")) continue;

                return file;
            }
        }

        return null;
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

    @Nullable
    private static String extractVulkanModVersion(@NonNull String fileName) {
        Matcher afterDash = VERSION_AFTER_DASH_PATTERN.matcher(fileName);
        if (afterDash.find()) {
            return afterDash.group(1);
        }

        Matcher any = ANY_VERSION_PATTERN.matcher(fileName);
        String last = null;
        while (any.find()) {
            last = any.group(1);
        }
        return last;
    }

    private static int compareVersions(@NonNull String first, @NonNull String second) {
        String[] a = first.split("\\.");
        String[] b = second.split("\\.");
        int max = Math.max(a.length, b.length);

        for (int i = 0; i < max; i++) {
            int av = parseVersionPart(a, i);
            int bv = parseVersionPart(b, i);

            if (av != bv) {
                return Integer.compare(av, bv);
            }
        }

        return 0;
    }

    private static int parseVersionPart(@NonNull String[] parts, int index) {
        if (index >= parts.length) return 0;

        try {
            return Integer.parseInt(parts[index]);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void appendLog(@NonNull String message) {
        try {
            Logger.appendToLog(message);
        } catch (Throwable ignored) {
            Logging.i(TAG, message);
        }
    }
}

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
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstance;
import ca.dnamobile.droidbridgelauncher.launcher.DistantHorizonsGcMitigation;
import ca.dnamobile.droidbridgelauncher.launcher.InstanceLaunchSettings;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstanceManager;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Central place for pre-launch warnings that are tied to specific mods.
 *
 * Keep the rule checks here so future mod-specific warnings can be added without
 * spreading jar scans and reminder counters throughout GameActivity.
 */
public final class ModPreLaunchWarningManager {
    private static final String TAG = "ModPreLaunchWarningManager";
    private static final String PREFS_NAME = "droidbridge_mod_prelaunch_warnings";
    private static final String KEY_SHOWN_PREFIX = "shown_count_";

    private static final String RULE_VULKANMOD_12111_DYNAMIC_RENDERING =
            "vulkanmod_1_21_11_dynamic_rendering_checker";
    private static final String RULE_SODIUM_1165_RENDERER =
            "sodium_1_16_5_or_older_renderer_warning";
    private static final String RULE_DISTANT_HORIZONS_GC_MODE =
            "distant_horizons_gc_mode_explanation";

    private static final int MAX_DISTANT_HORIZONS_GC_WARNING_SHOWS = 1;
    private static final int MAX_VULKANMOD_12111_WARNING_SHOWS = 1;
    private static final int MAX_SODIUM_1165_WARNING_SHOWS = 2;

    public static final String NEUTRAL_ACTION_VULKAN_EXTENSION_CHECKER =
            "vulkan_extension_checker";

    private static final Pattern MINECRAFT_VERSION_PATTERN = Pattern.compile(
            "(?:^|[^0-9])([0-9]+)\\.([0-9]+)(?:\\.([0-9]+))?"
    );

    private ModPreLaunchWarningManager() {
    }

    @Nullable
    public static Warning findPendingWarning(@NonNull Context context, @NonNull String versionId) {
        LaunchContext launchContext = resolveLaunchContext(context, versionId);

        try {
            Warning warning = checkDistantHorizonsGcMode(context, launchContext);
            if (warning != null && getShownCount(context, warning.ruleId) < warning.maxShows) {
                return warning;
            }

            warning = checkVulkanMod12111DynamicRendering(launchContext);
            if (warning != null && getShownCount(context, warning.ruleId) < warning.maxShows) {
                return warning;
            }

            warning = checkSodium1165RendererWarning(launchContext);
            if (warning != null && getShownCount(context, warning.ruleId) < warning.maxShows) {
                return warning;
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Mod pre-launch warning check failed", throwable);
        }

        return null;
    }

    public static void recordShown(@NonNull Context context, @NonNull Warning warning) {
        int current = getShownCount(context, warning.ruleId);
        int next = Math.max(0, Math.min(warning.maxShows, current + 1));
        prefs(context).edit().putInt(KEY_SHOWN_PREFIX + warning.ruleId, next).apply();
    }

    private static int getShownCount(@NonNull Context context, @NonNull String ruleId) {
        return Math.max(0, prefs(context).getInt(KEY_SHOWN_PREFIX + ruleId, 0));
    }

    @SuppressWarnings("deprecation")
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS
        );
    }

    @Nullable
    private static Warning checkDistantHorizonsGcMode(
            @NonNull Context context,
            @NonNull LaunchContext launchContext
    ) {
        File gameDir = launchContext.gameDirectory;
        if (gameDir == null || !DistantHorizonsGcMitigation.hasDistantHorizons(gameDir)) {
            return null;
        }

        InstanceLaunchSettings.Settings settings = InstanceLaunchSettings.load(
                context,
                launchContext.instanceSettingsKey
        );
        DistantHorizonsGcMitigation.DeviceRecommendation recommendation =
                DistantHorizonsGcMitigation.getDeviceRecommendation(context);

        String configuredMode = InstanceLaunchSettings.sanitizeGcMode(settings.gcMode);
        boolean hasSavedChoice = settings.hasGcOverride();
        String selectedMode = hasSavedChoice
                ? configuredMode
                : recommendation.recommendedGcMode;
        String selectedLabel = DistantHorizonsGcMitigation.displayGcMode(selectedMode);

        String summary = "Distant Horizons was detected. DroidBridge can use ZGC or G1GC "
                + "for this instance instead of forcing the same garbage collector on every device.";
        String cardBody = hasSavedChoice
                ? "This instance is currently set to " + selectedLabel + ". "
                + recommendation.summary
                : "DroidBridge selected " + selectedLabel + " automatically for this device. "
                + recommendation.summary
                + " Lower-end devices default to G1GC, while capable devices default to ZGC.";

        return new Warning(
                RULE_DISTANT_HORIZONS_GC_MODE,
                MAX_DISTANT_HORIZONS_GC_WARNING_SHOWS,
                "Distant Horizons garbage collection",
                summary,
                "Selected mode: " + selectedLabel,
                cardBody,
                "This explanation is shown only once. You can change the choice later in Per Instance Settings under Distant Horizons garbage collection.",
                displayVersion(launchContext),
                displayGameDirectory(gameDir),
                null,
                null
        );
    }

    @Nullable
    private static Warning checkVulkanMod12111DynamicRendering(@NonNull LaunchContext launchContext) {
        if (!requiresVulkanDynamicRenderingCheck(launchContext.versionText)) {
            return null;
        }

        File gameDir = launchContext.gameDirectory;
        if (gameDir == null || !hasVulkanMod(gameDir)) {
            return null;
        }

        return new Warning(
                RULE_VULKANMOD_12111_DYNAMIC_RENDERING,
                MAX_VULKANMOD_12111_WARNING_SHOWS,
                "VulkanMod device check",
                "This instance uses VulkanMod on Minecraft 1.21.11 or a 26.x+ Minecraft version. Before launching, use Vulkan Extension Checker to confirm your device supports Vulkan 1.2+ and Dynamic Rendering.",
                "What to check",
                "Open Vulkan Extension Checker and confirm both Vulkan 1.2 or newer and Dynamic Rendering are supported. If either one is missing, this VulkanMod instance may crash, fail to open Video Settings, or render incorrectly.",
                "This reminder is shown only once. After it has been displayed, DroidBridge will not show it again.",
                displayVersion(launchContext),
                displayGameDirectory(gameDir),
                "Install Checker",
                NEUTRAL_ACTION_VULKAN_EXTENSION_CHECKER
        );
    }

    @Nullable
    private static Warning checkSodium1165RendererWarning(@NonNull LaunchContext launchContext) {
        GameVersion gameVersion = parseMinecraftGameVersion(launchContext.minecraftVersionText);
        if (gameVersion == null || !isMinecraft1165OrOlder(gameVersion)) {
            Logging.i(TAG, "Sodium renderer warning check skipped: minecraftVersion="
                    + launchContext.minecraftVersionText + " versionText=" + launchContext.versionText);
            return null;
        }

        File gameDir = launchContext.gameDirectory;
        boolean hasSodium = gameDir != null && hasSodiumMod(gameDir);
        Logging.i(TAG, "Sodium renderer warning check: minecraftVersion=" + gameVersion.raw
                + " hasSodium=" + hasSodium
                + " gameDir=" + (gameDir == null ? "<null>" : gameDir.getAbsolutePath()));

        if (gameDir == null || !hasSodium) {
            return null;
        }

        return new Warning(
                RULE_SODIUM_1165_RENDERER,
                MAX_SODIUM_1165_WARNING_SHOWS,
                "Sodium renderer warning",
                "This instance uses Sodium on Minecraft 1.16.5 or older. On Android, these older Sodium versions can crash unless the instance is launched with a compatible renderer.",
                "Recommended renderer",
                "Use Krypton or Vulkan Zink for this Sodium instance. If you launch this older Sodium setup with the wrong renderer, the game may crash during startup, crash when loading a world, or render incorrectly.",
                "DroidBridge will only show this reminder twice. It appears again even after the first confirmation because many users skip warning text.",
                displayVersion(launchContext),
                displayGameDirectory(gameDir),
                null,
                null
        );
    }

    @NonNull
    private static LaunchContext resolveLaunchContext(@NonNull Context context, @NonNull String versionId) {
        String versionText = versionId;
        String minecraftVersionText = extractMinecraftVersionText(versionId);
        String instanceSettingsKey = InstanceLaunchSettings.resolveInstanceKey(versionId, versionId);
        File gameDirectory = null;

        try {
            PathManager.initContextConstants(context);
            LauncherInstance instance = findInstanceBestEffort(context, versionId);
            if (instance != null) {
                minecraftVersionText = firstNonEmpty(
                        instance.getMinecraftVersionId(),
                        extractMinecraftVersionText(instance.getBaseVersionId()),
                        extractMinecraftVersionText(versionId)
                );
                versionText = safeJoin(versionId, instance.getBaseVersionId(), instance.getMinecraftVersionId(), instance.getName(), instance.getId());
                instanceSettingsKey = InstanceLaunchSettings.resolveInstanceKey(instance.getId(), instance.getName());
                gameDirectory = instance.getGameDirectory();
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to resolve instance for mod pre-launch warning: " + versionId, throwable);
        }

        if (gameDirectory == null) {
            try {
                gameDirectory = new File(PathManager.DIR_MINECRAFT_HOME);
            } catch (Throwable ignored) {
                gameDirectory = null;
            }
        }

        if (minecraftVersionText.trim().isEmpty()) {
            minecraftVersionText = extractMinecraftVersionText(versionText);
        }
        if (minecraftVersionText.trim().isEmpty()) {
            minecraftVersionText = versionText;
        }

        return new LaunchContext(versionText, minecraftVersionText, gameDirectory, instanceSettingsKey);
    }

    @Nullable
    private static LauncherInstance findInstanceBestEffort(@NonNull Context context, @NonNull String versionId) {
        LauncherInstance exact = LauncherInstanceManager.findByNameOrId(context, versionId);
        if (exact != null) return exact;

        String needle = normalizeInstanceLookup(versionId);
        for (LauncherInstance instance : LauncherInstanceManager.findInstances(context)) {
            if (needle.equals(normalizeInstanceLookup(instance.getId()))
                    || needle.equals(normalizeInstanceLookup(instance.getName()))) {
                return instance;
            }
        }
        return null;
    }

    @NonNull
    private static String normalizeInstanceLookup(@Nullable String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    @NonNull
    private static String firstNonEmpty(@Nullable String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    @NonNull
    private static String safeJoin(@Nullable String... values) {
        StringBuilder builder = new StringBuilder();
        if (values == null) return "";
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) continue;
            if (builder.length() > 0) builder.append(' ');
            builder.append(value.trim());
        }
        return builder.toString();
    }

    @NonNull
    private static String displayVersion(@NonNull LaunchContext launchContext) {
        String minecraftVersion = launchContext.minecraftVersionText.trim();
        if (!minecraftVersion.isEmpty()) return minecraftVersion;
        return launchContext.versionText;
    }

    @NonNull
    private static String displayGameDirectory(@Nullable File gameDir) {
        return gameDir == null ? "<unknown>" : gameDir.getAbsolutePath();
    }

    private static boolean requiresVulkanDynamicRenderingCheck(@NonNull String text) {
        Matcher matcher = MINECRAFT_VERSION_PATTERN.matcher(text);
        while (matcher.find()) {
            int major = parseInt(matcher.group(1), -1);
            int minor = parseInt(matcher.group(2), -1);
            int patch = parseInt(matcher.group(3), 0);

            // Mojang's versioning split means this warning must cover both the
            // classic 1.x line where 1.21.11 first needs the extra Vulkan checks,
            // and the newer 26.x+ line such as 26.1, 26.1.1, 26.1.2, and 26.2.
            // Fabric/loader versions such as 0.19.3 are ignored because major=0.
            if (major == 1) {
                if (minor > 21) return true;
                if (minor == 21 && patch >= 11) return true;
                continue;
            }

            if (major >= 26) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMinecraft1165OrOlder(@NonNull GameVersion version) {
        if (version.major != 1) return false;
        if (version.minor < 16) return true;
        return version.minor == 16 && version.patch <= 5;
    }

    @Nullable
    private static GameVersion parseMinecraftGameVersion(@NonNull String text) {
        Matcher matcher = MINECRAFT_VERSION_PATTERN.matcher(text);
        GameVersion best = null;
        while (matcher.find()) {
            int major = parseInt(matcher.group(1), -1);
            int minor = parseInt(matcher.group(2), -1);
            int patch = parseInt(matcher.group(3), 0);
            if (major != 1 && major < 26) {
                continue;
            }
            best = new GameVersion(major, minor, patch, matcher.group(0).replaceAll("^[^0-9]+", ""));
        }
        return best;
    }

    @NonNull
    private static String extractMinecraftVersionText(@Nullable String text) {
        if (text == null) return "";
        GameVersion version = parseMinecraftGameVersion(text);
        return version == null ? "" : version.raw;
    }

    private static int parseInt(@Nullable String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean hasSodiumMod(@NonNull File gameDir) {
        for (File modsDir : getCandidateModsDirs(gameDir)) {
            File[] jars = modsDir.listFiles(file -> file != null
                    && file.isFile()
                    && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
            if (jars == null || jars.length == 0) continue;

            for (File jar : jars) {
                if (isSodiumJar(jar)) {
                    Logging.i(TAG, "Sodium renderer warning check: found Sodium jar " + jar.getAbsolutePath());
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasVulkanMod(@NonNull File gameDir) {
        for (File modsDir : getCandidateModsDirs(gameDir)) {
            File[] jars = modsDir.listFiles(file -> file != null
                    && file.isFile()
                    && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
            if (jars == null || jars.length == 0) continue;

            for (File jar : jars) {
                if (isVulkanModJar(jar)) {
                    return true;
                }
            }
        }
        return false;
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

    private static boolean isSodiumJar(@NonNull File jar) {
        String lowerName = jar.getName().toLowerCase(Locale.ROOT);
        if (lowerName.contains("sodium-extra")) return false;
        if (lowerName.contains("reeses-sodium-options")) return false;
        if (lowerName.contains("sodiumoptions")) return false;
        if (lowerName.contains("sodium-options")) return false;

        // Prefer the real Fabric id when available. The filename fallback is kept
        // intentionally simple because older Sodium jars are commonly named
        // sodium-fabric-*.jar and users expect this warning whenever Sodium itself
        // exists in a 1.16.5-or-older instance.
        if (hasFabricModId(jar, "sodium")) return true;
        return lowerName.startsWith("sodium-")
                || lowerName.startsWith("sodium_")
                || lowerName.equals("sodium.jar")
                || lowerName.contains("sodium-fabric");
    }

    private static boolean hasFabricModId(@NonNull File jar, @NonNull String modId) {
        try (ZipFile zipFile = new ZipFile(jar)) {
            ZipEntry fabricJson = zipFile.getEntry("fabric.mod.json");
            if (fabricJson == null) return false;

            try (InputStream input = zipFile.getInputStream(fabricJson);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                int total = 0;
                while ((read = input.read(buffer)) != -1 && total < 256 * 1024) {
                    output.write(buffer, 0, read);
                    total += read;
                }
                String json = new String(output.toByteArray(), StandardCharsets.UTF_8);
                Pattern idPattern = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"" + Pattern.quote(modId) + "\\\"", Pattern.CASE_INSENSITIVE);
                return idPattern.matcher(json).find();
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isVulkanModJar(@NonNull File jar) {
        String lowerName = jar.getName().toLowerCase(Locale.ROOT);
        if (lowerName.contains("vulkandreno")) return false;
        if (lowerName.contains("vulkanmod-android")) return false;
        if (lowerName.contains("android-libs")) return false;
        if (lowerName.contains("vulkanmod")) return true;

        try (ZipFile zipFile = new ZipFile(jar)) {
            ZipEntry fabricJson = zipFile.getEntry("fabric.mod.json");
            if (fabricJson == null) return false;

            try (InputStream input = zipFile.getInputStream(fabricJson);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                int total = 0;
                while ((read = input.read(buffer)) != -1 && total < 256 * 1024) {
                    output.write(buffer, 0, read);
                    total += read;
                }
                String json = new String(output.toByteArray(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                return json.contains("\"id\"") && json.contains("\"vulkanmod\"");
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static final class Warning {
        @NonNull public final String ruleId;
        public final int maxShows;
        @NonNull public final String title;
        @NonNull public final String summary;
        @NonNull public final String cardTitle;
        @NonNull public final String cardBody;
        @NonNull public final String reminderText;
        @NonNull public final String versionText;
        @NonNull public final String gameDirectory;
        @Nullable public final String neutralButtonLabel;
        @Nullable public final String neutralAction;

        private Warning(
                @NonNull String ruleId,
                int maxShows,
                @NonNull String title,
                @NonNull String summary,
                @NonNull String cardTitle,
                @NonNull String cardBody,
                @NonNull String reminderText,
                @NonNull String versionText,
                @NonNull String gameDirectory,
                @Nullable String neutralButtonLabel,
                @Nullable String neutralAction
        ) {
            this.ruleId = ruleId;
            this.maxShows = maxShows;
            this.title = title;
            this.summary = summary;
            this.cardTitle = cardTitle;
            this.cardBody = cardBody;
            this.reminderText = reminderText;
            this.versionText = versionText;
            this.gameDirectory = gameDirectory;
            this.neutralButtonLabel = neutralButtonLabel;
            this.neutralAction = neutralAction;
        }
    }

    private static final class LaunchContext {
        @NonNull final String versionText;
        @NonNull final String minecraftVersionText;
        @Nullable final File gameDirectory;
        @NonNull final String instanceSettingsKey;

        LaunchContext(
                @NonNull String versionText,
                @NonNull String minecraftVersionText,
                @Nullable File gameDirectory,
                @NonNull String instanceSettingsKey
        ) {
            this.versionText = versionText;
            this.minecraftVersionText = minecraftVersionText;
            this.gameDirectory = gameDirectory;
            this.instanceSettingsKey = instanceSettingsKey;
        }
    }

    private static final class GameVersion {
        final int major;
        final int minor;
        final int patch;
        @NonNull final String raw;

        GameVersion(int major, int minor, int patch, @NonNull String raw) {
            this.major = major;
            this.minor = minor;
            this.patch = patch;
            this.raw = raw;
        }
    }
}

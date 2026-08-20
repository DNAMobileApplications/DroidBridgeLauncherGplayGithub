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

package ca.dnamobile.droidbridgelauncher.instance;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.modmanager.ModManagerVersionResolver;

/**
 * Seeds a small Android-friendly options.txt for clean launcher-created instances only.
 *
 * Important policy:
 * - Never overwrite an existing options.txt.
 * - Never seed into a non-empty game directory unless explicitly allowed.
 * - Never keep re-seeding after the instance has already made a decision once.
 *
 * This protects:
 * - modpack-provided options.txt
 * - copied options.txt from another instance
 * - options.txt edited by Minecraft while the user is in-game
 * - users who intentionally delete options.txt and want Minecraft to recreate it
 *
 * Asset files must be stored in:
 * app/src/main/assets/minecraft_defaults/
 *
 * Expected files:
 * - minecraft_defaults/options-beta-legacy-optional.txt
 * - minecraft_defaults/options-release-1.8-to-1.16.txt
 * - minecraft_defaults/options-modern-1.17-plus.txt
 */
public final class DefaultMinecraftOptionsInstaller {
    private static final String TAG = "DefaultOptions";

    private static final String ASSET_BETA_LEGACY = "minecraft_defaults/options-beta-legacy-optional.txt";
    private static final String ASSET_RELEASE_1_8_TO_1_16 = "minecraft_defaults/options-release-1.8-to-1.16.txt";
    private static final String ASSET_MODERN_1_17_PLUS = "minecraft_defaults/options-modern-1.17-plus.txt";

    private static final String INSTANCE_METADATA_DIR = "metadata";
    private static final String INSTANCE_JSON = "instance.json";
    private static final String FALLBACK_MARKER_DIR = ".droidbridge";
    private static final String MARKER_FILE = "default-options.seeded";

    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");

    private DefaultMinecraftOptionsInstaller() {
    }

    /**
     * Backwards-compatible entry point.
     *
     * This intentionally does not seed when the Minecraft version is unknown. Passing no version
     * is unsafe because Beta/Alpha/Classic and modern releases use different option keys.
     * Prefer installIfMissingForNewInstance(context, gameDirectory, minecraftVersionId).
     */
    public static void installIfMissingForNewInstance(
            @NonNull Context context,
            @NonNull File gameDirectory
    ) {
        installIfMissingForNewInstance(context, gameDirectory, null);
    }

    /**
     * Safe default entry point.
     *
     * It only seeds once, only if options.txt is missing, and only if the game directory still
     * looks like a clean launcher-created folder.
     */
    public static void installIfMissingForNewInstance(
            @NonNull Context context,
            @NonNull File gameDirectory,
            @Nullable String minecraftVersionId
    ) {
        tryInstallIfMissingForNewInstance(context, gameDirectory, minecraftVersionId, false);
    }

    /**
     * Launch-time safety net used by the JVM handoff.
     *
     * Normal launcher-created instances should already be seeded when the instance folder is
     * created. This method exists for older instances created before that fix, and for any
     * missing-options edge case that reaches launch. It still never overwrites an existing
     * options.txt, but it intentionally allows a missing file to be seeded even when mods,
     * config, saves, or other game content already exist. That preserves the old launcher
     * behavior of creating options.txt before Minecraft starts, while using the same
     * version-specific presets as the instance installer instead of the old generic fallback.
     */
    public static boolean tryInstallIfMissingForLaunch(
            @NonNull Context context,
            @NonNull File gameDirectory,
            @Nullable String launchOrMinecraftVersionId
    ) {
        String resolvedVersion = resolveMinecraftVersionForGameDirectory(gameDirectory, launchOrMinecraftVersionId);
        return tryInstallIfMissingInternal(context, gameDirectory, resolvedVersion, true, true, "launch");
    }


    /**
     * Returns true when this game directory's options.txt was created by DroidBridge's
     * default-options seeder. The JVM handoff uses this to add launcher-only dynamic
     * window keys without touching user-authored or modpack-provided options.txt files.
     */
    public static boolean isDroidBridgeSeededDefaultOptions(@NonNull File gameDirectory) {
        try {
            File markerFile = resolveMarkerFile(gameDirectory);
            if (!markerFile.isFile()) return false;

            String text = readTextFile(markerFile, 16 * 1024).toLowerCase(Locale.US);
            return text.contains("seeded-") || text.contains("-seeded-");
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to read default-options marker", throwable);
            return false;
        }
    }

    /**
     * Advanced entry point.
     *
     * allowInNonEmptyGameDirectory should stay false for modpacks, imports, restores, and
     * copies. The JVM handoff uses tryInstallIfMissingForLaunch() so existing instances
     * created before this fix can still receive the correct version-specific defaults.
     *
     * @return true when this method actually wrote DroidBridge's default options.txt.
     */
    public static boolean tryInstallIfMissingForNewInstance(
            @NonNull Context context,
            @NonNull File gameDirectory,
            @Nullable String minecraftVersionId,
            boolean allowInNonEmptyGameDirectory
    ) {
        return tryInstallIfMissingInternal(
                context,
                gameDirectory,
                minecraftVersionId,
                allowInNonEmptyGameDirectory,
                false,
                "new-instance"
        );
    }

    private static boolean tryInstallIfMissingInternal(
            @NonNull Context context,
            @NonNull File gameDirectory,
            @Nullable String minecraftVersionId,
            boolean allowInNonEmptyGameDirectory,
            boolean ignoreExistingMarker,
            @NonNull String source
    ) {
        File optionsFile = new File(gameDirectory, "options.txt");
        File markerFile = resolveMarkerFile(gameDirectory);

        if (optionsFile.exists()) {
            if (!ignoreExistingMarker) {
                writeMarkerIfMissing(markerFile, "existing-options", minecraftVersionId);
            }
            Logging.i(TAG, "options.txt already exists, not overwriting: " + optionsFile.getAbsolutePath());
            return false;
        }

        if (!ignoreExistingMarker && markerFile.isFile()) {
            Logging.i(TAG, "Default options decision already recorded, not creating options.txt again: "
                    + markerFile.getAbsolutePath());
            return false;
        }

        if (!allowInNonEmptyGameDirectory && hasMeaningfulGameContent(gameDirectory)) {
            writeMarkerIfMissing(markerFile, "non-empty-game-directory", minecraftVersionId);
            Logging.i(TAG, "Game directory is not empty, not creating default options.txt: "
                    + gameDirectory.getAbsolutePath());
            return false;
        }

        OptionsPreset preset = choosePreset(minecraftVersionId);
        if (preset == OptionsPreset.SKIP_UNKNOWN) {
            if (!ignoreExistingMarker) {
                writeMarkerIfMissing(markerFile, "unknown-version", minecraftVersionId);
            }
            Logging.i(TAG, "Skipping default options.txt for unknown Minecraft version: "
                    + String.valueOf(minecraftVersionId));
            return false;
        }

        File parent = optionsFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Logging.i(TAG, "Unable to create game directory for default options: " + parent.getAbsolutePath());
            return false;
        }

        try (InputStream input = context.getAssets().open(preset.assetPath);
             FileOutputStream output = new FileOutputStream(optionsFile, false)) {

            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();

            writeMarker(markerFile, source + "-seeded-" + preset.logName, minecraftVersionId);

            Logging.i(TAG, "Seeded " + preset.logName + " options.txt for "
                    + String.valueOf(minecraftVersionId) + ": " + optionsFile.getAbsolutePath());
            return true;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to seed default options.txt from asset "
                    + preset.assetPath + " to " + optionsFile.getAbsolutePath(), throwable);
            return false;
        }
    }

    @NonNull
    private static String resolveMinecraftVersionForGameDirectory(
            @NonNull File gameDirectory,
            @Nullable String launchOrMinecraftVersionId
    ) {
        String metadataVersion = readInstanceMetadataMinecraftVersion(gameDirectory);
        if (metadataVersion.length() > 0) {
            return metadataVersion;
        }

        String resolved = ModManagerVersionResolver.resolveGameVersionForContent(launchOrMinecraftVersionId);
        return resolved.trim();
    }

    @NonNull
    private static String readInstanceMetadataMinecraftVersion(@NonNull File gameDirectory) {
        try {
            File parent = gameDirectory.getParentFile();
            if (parent == null || !"game".equalsIgnoreCase(gameDirectory.getName())) return "";

            File jsonFile = new File(parent, INSTANCE_JSON);
            if (!jsonFile.isFile()) return "";

            String text = readTextFile(jsonFile, 256 * 1024);
            Matcher matcher = Pattern.compile(
                    "\\\"minecraftVersionId\\\"\\s*:\\s*\\\"([^\\\"]*)\\\""
            ).matcher(text);
            if (!matcher.find()) return "";

            return matcher.group(1).trim();
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to read instance metadata Minecraft version for default options", throwable);
            return "";
        }
    }

    @NonNull
    private static String readTextFile(@NonNull File file, int maxBytes) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                int allowed = Math.min(read, maxBytes - total);
                if (allowed > 0) {
                    output.write(buffer, 0, allowed);
                    total += allowed;
                }
                if (total >= maxBytes) break;
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    @NonNull
    private static OptionsPreset choosePreset(@Nullable String minecraftVersionId) {
        if (minecraftVersionId == null) {
            return OptionsPreset.SKIP_UNKNOWN;
        }

        String version = minecraftVersionId.trim().toLowerCase(Locale.US);
        if (version.length() == 0) {
            return OptionsPreset.SKIP_UNKNOWN;
        }

        // Beta/Alpha/Classic/old development versions need the legacy-safe options file.
        if (version.startsWith("b")
                || version.startsWith("a")
                || version.startsWith("rd")
                || version.startsWith("c0")
                || version.contains("beta")
                || version.contains("alpha")
                || version.contains("classic")
                || version.contains("infdev")
                || version.contains("indev")) {
            return OptionsPreset.BETA_LEGACY;
        }

        // Mojang-style weekly snapshots like 24w45a/25w31a are modern enough for modern options.
        if (version.matches("^\\d{2}w\\d{2}[a-z].*")) {
            return OptionsPreset.MODERN_1_17_PLUS;
        }

        int[] numbers = extractFirstThreeNumbers(version);
        int major = numbers[0];
        int minor = numbers[1];

        if (major < 0) {
            return OptionsPreset.SKIP_UNKNOWN;
        }

        // DroidBridge/modern Minecraft naming such as 26.1.2 / 26.2-snapshot-2.
        if (major >= 26) {
            return OptionsPreset.MODERN_1_17_PLUS;
        }

        // Standard Java Edition releases such as 1.8, 1.12.2, 1.16.5, 1.21.11.
        if (major == 1) {
            if (minor >= 17) {
                return OptionsPreset.MODERN_1_17_PLUS;
            }
            if (minor >= 8) {
                return OptionsPreset.RELEASE_1_8_TO_1_16;
            }

            // 1.0 through 1.7.x are closer to legacy options than the 1.8+ format.
            return OptionsPreset.BETA_LEGACY;
        }

        return OptionsPreset.SKIP_UNKNOWN;
    }

    @NonNull
    private static int[] extractFirstThreeNumbers(@NonNull String text) {
        int[] result = new int[]{-1, -1, -1};
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        int index = 0;
        while (matcher.find() && index < result.length) {
            try {
                result[index] = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                result[index] = -1;
            }
            index++;
        }
        return result;
    }

    @NonNull
    private static File resolveMarkerFile(@NonNull File gameDirectory) {
        File parent = gameDirectory.getParentFile();
        if (parent != null && "game".equalsIgnoreCase(gameDirectory.getName())) {
            File instanceMetadata = new File(parent, INSTANCE_METADATA_DIR);
            if (instanceMetadata.isDirectory() || new File(parent, INSTANCE_JSON).isFile()) {
                return new File(instanceMetadata, MARKER_FILE);
            }
        }

        return new File(new File(gameDirectory, FALLBACK_MARKER_DIR), MARKER_FILE);
    }

    private static boolean hasMeaningfulGameContent(@NonNull File gameDirectory) {
        File[] children = gameDirectory.listFiles();
        if (children == null || children.length == 0) return false;

        for (File child : children) {
            if (child == null) continue;

            String name = child.getName();
            if ("options.txt".equalsIgnoreCase(name)) continue;
            if (FALLBACK_MARKER_DIR.equalsIgnoreCase(name)) continue;

            if (child.isDirectory() && isLauncherCreatedEmptyFolder(name) && isDirectoryEffectivelyEmpty(child)) {
                continue;
            }

            return true;
        }

        return false;
    }

    private static boolean isLauncherCreatedEmptyFolder(@NonNull String name) {
        return "saves".equalsIgnoreCase(name)
                || "resourcepacks".equalsIgnoreCase(name)
                || "shaderpacks".equalsIgnoreCase(name)
                || "mods".equalsIgnoreCase(name)
                || "config".equalsIgnoreCase(name)
                || "logs".equalsIgnoreCase(name);
    }

    private static boolean isDirectoryEffectivelyEmpty(@NonNull File directory) {
        File[] children = directory.listFiles();
        if (children == null || children.length == 0) return true;

        for (File child : children) {
            if (child == null) continue;
            if (child.isDirectory()) {
                if (!isDirectoryEffectivelyEmpty(child)) return false;
            } else {
                return false;
            }
        }

        return true;
    }

    private static void writeMarkerIfMissing(
            @NonNull File markerFile,
            @NonNull String reason,
            @Nullable String minecraftVersionId
    ) {
        if (markerFile.isFile()) return;
        writeMarker(markerFile, reason, minecraftVersionId);
    }

    private static void writeMarker(
            @NonNull File markerFile,
            @NonNull String reason,
            @Nullable String minecraftVersionId
    ) {
        try {
            File parent = markerFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Logging.i(TAG, "Unable to create default-options marker directory: " + parent.getAbsolutePath());
                return;
            }

            String createdAt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date());
            String text = "reason=" + reason + "\n"
                    + "minecraftVersionId=" + String.valueOf(minecraftVersionId) + "\n"
                    + "createdAt=" + createdAt + "\n";

            try (FileOutputStream output = new FileOutputStream(markerFile, false)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to write default-options marker: " + markerFile.getAbsolutePath(), throwable);
        }
    }

    private enum OptionsPreset {
        SKIP_UNKNOWN("skip", ""),
        BETA_LEGACY("beta-legacy", ASSET_BETA_LEGACY),
        RELEASE_1_8_TO_1_16("release-1.8-to-1.16", ASSET_RELEASE_1_8_TO_1_16),
        MODERN_1_17_PLUS("modern-1.17-plus", ASSET_MODERN_1_17_PLUS);

        final String logName;
        final String assetPath;

        OptionsPreset(@NonNull String logName, @NonNull String assetPath) {
            this.logName = logName;
            this.assetPath = assetPath;
        }
    }
}

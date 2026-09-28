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

package ca.dnamobile.droidbridgelauncher.launcher;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.instance.DefaultMinecraftOptionsInstaller;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;

/**
 * Handles Mojang's built-in Graphics API setting for vanilla Minecraft 26.2+.
 *
 * DroidBridge intentionally leaves this setting alone for pre-26.2 Minecraft versions
 * and for 26.2 instances that contain VulkanMod. VulkanMod has its own backend
 * compatibility path and must not have its options.txt preference rewritten here.
 */
public final class GraphicsBackendHelper {
    private static final String TAG = "GraphicsBackendHelper";
    private static final String PREFERRED_BACKEND_KEY = "preferredGraphicsBackend";
    private static final String LEGACY_BACKEND_KEY = "graphicsBackend";
    private static final String VSYNC_KEY = "enableVsync";
    private static final String STARTED_CLEANLY_KEY = "startedCleanly";

    private GraphicsBackendHelper() {
    }

    public static void applyBeforeLaunch(
            @NonNull Context context,
            @NonNull String launchVersionId,
            @NonNull JSONObject versionJson,
            @NonNull File gameDirectory,
            @NonNull InstanceLaunchSettings.Settings settings
    ) {
        applyBeforeLaunch(context, launchVersionId, versionJson, gameDirectory, settings, false);
    }

    /**
     * Applies the launcher's selected Graphics API directly to vanilla Minecraft 26.2+.
     *
     * Semantics are intentionally strict:
     * - Default is canonicalized to OpenGL before launch.
     * - OpenGL always writes opengl before launch.
     * - System Vulkan always writes vulkan before launch.
     * - VulkanMod 26.2 is never rewritten here because its own compatibility layer
     *   owns backend selection.
     */
    public static void applyBeforeLaunch(
            @NonNull Context context,
            @NonNull String launchVersionId,
            @NonNull JSONObject versionJson,
            @NonNull File gameDirectory,
            @NonNull InstanceLaunchSettings.Settings settings,
            boolean hasVulkanMod
    ) {
        String effectiveVersion = resolveEffectiveMinecraftVersion(launchVersionId, versionJson);
        if (!isMinecraft262OrNewerVersion(effectiveVersion)) {
            Logging.i(TAG, "Skipping graphics API override for pre-26.2 version: " + effectiveVersion);
            return;
        }

        // Keep the existing 26.2 VulkanMod exception. 26.2 VulkanMod owns its own
        // compatibility path and must not be confused with Mojang's built-in Vulkan backend.
        if (hasVulkanMod && isMinecraft262Version(effectiveVersion)) {
            Logging.i(TAG, "Skipping Minecraft 26.2 Graphics API options override because VulkanMod "
                    + "was detected; leaving options.txt backend selection to VulkanMod compatibility");
            return;
        }

        String backend = InstanceLaunchSettings.resolveEffectiveGraphicsApiMode(context, settings);
        if (InstanceLaunchSettings.GRAPHICS_API_DEFAULT.equals(backend)
                || InstanceLaunchSettings.GRAPHICS_API_INHERIT.equals(backend)) {
            backend = InstanceLaunchSettings.GRAPHICS_API_OPENGL;
        }
        File optionsFile = new File(gameDirectory, "options.txt");

        try {
            // Always seed a complete options file before forcing the backend. This keeps the
            // first-launch path from replacing a tiny backend-only options.txt later in startup.
            if (!optionsFile.isFile()) {
                boolean seeded = DefaultMinecraftOptionsInstaller.tryInstallIfMissingForLaunch(
                        context,
                        gameDirectory,
                        launchVersionId
                );
                Logging.i(TAG, "First-launch graphics API preparation seededOptions=" + seeded
                        + " backend=" + backend
                        + " options=" + optionsFile.getAbsolutePath());
            }

            // System Vulkan also owns the launcher's Vulkan VSync preference.
            // OpenGL leaves Minecraft's existing enableVsync value alone.
            Boolean forcedVsync = InstanceLaunchSettings.GRAPHICS_API_VULKAN.equals(backend)
                    ? LauncherPreferences.isVulkanVsyncEnabled(context)
                    : null;

            /*
             * Minecraft 26.3 final serializes the built-in Graphics API preference as a
             * normal raw options.txt enum value (for example:
             * preferredGraphicsBackend:vulkan). The older 26.2 helper intentionally used
             * a quoted JSON-style value. Keeping that quoted form on 26.3 makes the option
             * fail decoding and Minecraft falls back to OpenGL even though DroidBridge has
             * already selected/loaded the System Vulkan driver.
             *
             * Keep 26.2 byte-for-byte compatible with the existing launcher behavior and
             * switch only 26.3+ to the final game's raw enum representation.
             */
            boolean rawBackendOptionValue = isMinecraft263OrNewerVersion(effectiveVersion);
            writeBackendValue(
                    optionsFile,
                    backend,
                    forcedVsync,
                    true,
                    rawBackendOptionValue
            );
            Logging.i(TAG, "Forced Minecraft 26.2+ graphics backend=" + backend
                    + " (preferredGraphicsBackend + graphicsBackend)"
                    + " encoding=" + (rawBackendOptionValue ? "raw-26.3+" : "quoted-26.2")
                    + " startedCleanly=true"
                    + (forcedVsync != null ? " enableVsync=" + forcedVsync : "")
                    + " for Minecraft " + effectiveVersion
                    + " options=" + optionsFile.getAbsolutePath());
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to set Graphics API backend for Minecraft "
                    + effectiveVersion + " backend=" + backend, throwable);
        }
    }


    public static void applyBeforeLaunch(
            @NonNull Context context,
            @NonNull String launchVersionId,
            @NonNull JSONObject versionJson,
            @NonNull File gameDirectory
    ) {
        applyBeforeLaunch(
                context,
                launchVersionId,
                versionJson,
                gameDirectory,
                new InstanceLaunchSettings.Settings(),
                false
        );
    }
    @NonNull
    private static String resolveEffectiveMinecraftVersion(
            @NonNull String launchVersionId,
            @NonNull JSONObject versionJson
    ) {
        String inheritsFrom = versionJson.optString("inheritsFrom", "").trim();
        if (!inheritsFrom.isEmpty()) return inheritsFrom;

        String flattenedParent = versionJson.optString("javaLauncherFlattenedParent", "").trim();
        if (!flattenedParent.isEmpty()) return flattenedParent;

        String id = versionJson.optString("id", launchVersionId).trim();
        return id.isEmpty() ? launchVersionId : id;
    }

    public static boolean isMinecraft262(
            @NonNull String launchVersionId,
            @NonNull JSONObject versionJson
    ) {
        return isMinecraft262Version(
                resolveEffectiveMinecraftVersion(launchVersionId, versionJson));
    }

    public static boolean supportsBuiltInGraphicsApi(
            @NonNull String launchVersionId,
            @NonNull JSONObject versionJson
    ) {
        return isMinecraft262OrNewerVersion(
                resolveEffectiveMinecraftVersion(launchVersionId, versionJson));
    }

    private static boolean isMinecraft262Version(@NonNull String rawVersion) {
        int[] version = findLastDottedVersion(rawVersion.trim().toLowerCase(Locale.ROOT));
        return version != null && version[0] == 26 && version[1] == 2;
    }

    private static boolean isMinecraft262OrNewerVersion(@NonNull String rawVersion) {
        int[] version = findLastDottedVersion(rawVersion.trim().toLowerCase(Locale.ROOT));
        if (version == null) return false;
        return version[0] > 26 || (version[0] == 26 && version[1] >= 2);
    }

    private static boolean isMinecraft263OrNewerVersion(@NonNull String rawVersion) {
        int[] version = findLastDottedVersion(rawVersion.trim().toLowerCase(Locale.ROOT));
        if (version == null) return false;
        return version[0] > 26 || (version[0] == 26 && version[1] >= 3);
    }

    @Nullable
    private static int[] findLastDottedVersion(@NonNull String value) {
        int[] last = null;
        int length = value.length();
        for (int i = 0; i < length; i++) {
            if (!Character.isDigit(value.charAt(i))) continue;

            int majorStart = i;
            int majorEnd = majorStart;
            while (majorEnd < length && Character.isDigit(value.charAt(majorEnd))) majorEnd++;
            if (majorEnd >= length || value.charAt(majorEnd) != '.') {
                i = majorEnd;
                continue;
            }

            int minorStart = majorEnd + 1;
            int minorEnd = minorStart;
            while (minorEnd < length && Character.isDigit(value.charAt(minorEnd))) minorEnd++;
            if (minorEnd == minorStart) {
                i = minorEnd;
                continue;
            }

            try {
                int major = Integer.parseInt(value.substring(majorStart, majorEnd));
                int minor = Integer.parseInt(value.substring(minorStart, minorEnd));
                last = new int[]{major, minor};
            } catch (Throwable ignored) {
                // Keep scanning for the Minecraft version after a loader version.
            }
            i = minorEnd - 1;
        }
        return last;
    }

    private static void writeBackendValue(
            @NonNull File optionsFile,
            @NonNull String backend,
            @Nullable Boolean forcedVsync,
            boolean clearStaleStartupRecovery,
            boolean rawBackendOptionValue
    ) throws Exception {
        File parent = optionsFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Unable to create options directory: " + parent.getAbsolutePath());
        }

        List<String> lines = new ArrayList<>();
        if (optionsFile.isFile()) {
            String text = readFile(optionsFile);
            for (String line : text.split("\\r?\\n", -1)) {
                if (!line.isEmpty()) lines.add(line);
            }
        }

        boolean replacedPreferred = false;
        boolean replacedLegacy = false;
        boolean replacedVsync = false;
        boolean replacedStartedCleanly = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith(PREFERRED_BACKEND_KEY + ":")) {
                lines.set(i, formatBackendOptionLine(PREFERRED_BACKEND_KEY, backend, rawBackendOptionValue));
                replacedPreferred = true;
            } else if (line.startsWith(LEGACY_BACKEND_KEY + ":")) {
                lines.set(i, formatBackendOptionLine(LEGACY_BACKEND_KEY, backend, rawBackendOptionValue));
                replacedLegacy = true;
            } else if (forcedVsync != null && line.startsWith(VSYNC_KEY + ":")) {
                lines.set(i, VSYNC_KEY + ":" + forcedVsync);
                replacedVsync = true;
            } else if (clearStaleStartupRecovery && line.startsWith(STARTED_CLEANLY_KEY + ":")) {
                lines.set(i, STARTED_CLEANLY_KEY + ":true");
                replacedStartedCleanly = true;
            }
        }

        // Minecraft 26.2 builds have used both names during the backend-option
        // rollout. Always write and synchronize both keys. Previously the legacy
        // key was only updated after Minecraft had launched once and created it,
        // which is why Vulkan/OpenGL appeared as Default on a brand-new instance.
        if (!replacedPreferred) {
            lines.add(formatBackendOptionLine(PREFERRED_BACKEND_KEY, backend, rawBackendOptionValue));
        }
        if (!replacedLegacy) {
            lines.add(formatBackendOptionLine(LEGACY_BACKEND_KEY, backend, rawBackendOptionValue));
        }
        if (forcedVsync != null && !replacedVsync) {
            lines.add(VSYNC_KEY + ":" + forcedVsync);
        }
        if (clearStaleStartupRecovery && !replacedStartedCleanly) {
            lines.add(STARTED_CLEANLY_KEY + ":true");
        }

        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) continue;
            out.append(line).append('\n');
        }

        try (FileOutputStream output = new FileOutputStream(optionsFile)) {
            output.write(out.toString().getBytes(StandardCharsets.UTF_8));
        }
    }


    @NonNull
    private static String formatBackendOptionLine(
            @NonNull String key,
            @NonNull String backend,
            boolean rawBackendOptionValue
    ) {
        if (rawBackendOptionValue) {
            return key + ":" + backend;
        }
        return key + ":\"" + backend + "\"";
    }

    @NonNull
    private static String readFile(@NonNull File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            int offset = 0;
            while (offset < buffer.length) {
                int read = input.read(buffer, offset, buffer.length - offset);
                if (read < 0) break;
                offset += read;
            }
            return new String(buffer, 0, offset, StandardCharsets.UTF_8);
        }
    }
}

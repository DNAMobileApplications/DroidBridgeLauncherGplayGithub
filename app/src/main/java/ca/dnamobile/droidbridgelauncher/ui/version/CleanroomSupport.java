/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.ui.version;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared Cleanroom loader detection and Java-runtime rules. */
public final class CleanroomSupport {
    public static final String LOADER_NAME = "Cleanroom";
    public static final String MINECRAFT_VERSION = "1.12.2";

    private static final Pattern CLEANROOM_VERSION_PATTERN = Pattern.compile(
            "(?i)(?:cleanroom(?:loader)?)[\\s:/_\\-]*([0-9]+(?:\\.[0-9]+){1,2}(?:[-+][0-9A-Za-z.\\-]+)?)"
    );
    private static final Pattern VERSION_ONLY_PATTERN = Pattern.compile(
            "^\\s*([0-9]+)(?:\\.([0-9]+))?(?:\\.([0-9]+))?.*$"
    );

    private CleanroomSupport() {
    }

    public static boolean supportsMinecraftVersion(@Nullable String minecraftVersion) {
        return minecraftVersion != null && MINECRAFT_VERSION.equals(minecraftVersion.trim());
    }

    /**
     * Zalith Launcher 2's current rule is Java 21 through 0.4.4-alpha and
     * Java 25 for versions newer than 0.4.4-alpha (0.5.0-alpha and newer).
     */
    public static int requiredJavaForVersion(@Nullable String cleanroomVersion) {
        int[] parsed = parseVersion(cleanroomVersion);
        if (parsed == null) {
            // Current Cleanroom builds target Java 25. Prefer the safe modern
            // runtime when a third-party profile does not expose its version.
            return 25;
        }

        if (parsed[0] > 0) return 25;
        if (parsed[1] > 4) return 25;
        if (parsed[1] < 4) return 21;
        return parsed[2] > 4 ? 25 : 21;
    }

    public static boolean isCleanroomProfile(
            @Nullable String versionId,
            @Nullable JSONObject versionJson
    ) {
        if (containsCleanroomMarker(versionId)) return true;
        if (versionJson == null) return false;

        if (containsCleanroomMarker(versionJson.optString("id", ""))) return true;
        if (containsCleanroomMarker(versionJson.optString("mainClass", ""))) return true;
        if (containsCleanroomMarker(versionJson.optString("inheritsFrom", ""))) return true;
        if (containsCleanroomMarker(versionJson.optString("droidbridgeLoader", ""))) return true;
        if (containsCleanroomMarker(versionJson.optString("droidbridgeCleanroomVersion", ""))) return true;

        return containsCleanroomMarker(versionJson.toString());
    }

    /** Returns 0 when the supplied profile is not Cleanroom. */
    public static int resolveRequiredJava(
            @Nullable String versionId,
            @Nullable JSONObject versionJson
    ) {
        if (!isCleanroomProfile(versionId, versionJson)) return 0;
        return requiredJavaForVersion(detectVersion(versionId, versionJson));
    }

    @Nullable
    public static String detectVersion(
            @Nullable String versionId,
            @Nullable JSONObject versionJson
    ) {
        String detected = detectVersionInText(versionId);
        if (detected != null) return detected;

        if (versionJson == null) return null;

        detected = cleanVersion(versionJson.optString("droidbridgeCleanroomVersion", ""));
        if (detected != null) return detected;

        detected = detectVersionInText(versionJson.optString("id", ""));
        if (detected != null) return detected;

        detected = detectVersionInText(versionJson.optString("mainClass", ""));
        if (detected != null) return detected;

        return detectVersionInText(versionJson.toString());
    }

    public static void markInstalledProfile(
            @NonNull JSONObject versionJson,
            @NonNull String cleanroomVersion
    ) throws Exception {
        String cleanVersion = cleanroomVersion.trim();
        int javaMajor = requiredJavaForVersion(cleanVersion);

        JSONObject javaVersion = new JSONObject();
        javaVersion.put("component", javaMajor >= 25 ? "java-runtime-epsilon" : "java-runtime-delta");
        javaVersion.put("majorVersion", javaMajor);
        versionJson.put("javaVersion", javaVersion);

        // Unknown keys are ignored by the normal launcher format, but these let
        // DroidBridge reliably identify imported/installed Cleanroom profiles.
        versionJson.put("droidbridgeLoader", LOADER_NAME);
        versionJson.put("droidbridgeCleanroomVersion", cleanVersion);
    }

    @Nullable
    private static int[] parseVersion(@Nullable String version) {
        String clean = cleanVersion(version);
        if (clean == null) return null;

        Matcher matcher = VERSION_ONLY_PATTERN.matcher(clean);
        if (!matcher.matches()) return null;

        try {
            int major = Integer.parseInt(matcher.group(1));
            int minor = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
            int patch = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
            return new int[]{major, minor, patch};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Nullable
    private static String detectVersionInText(@Nullable String text) {
        if (text == null || text.trim().isEmpty()) return null;
        Matcher matcher = CLEANROOM_VERSION_PATTERN.matcher(text);
        return matcher.find() ? cleanVersion(matcher.group(1)) : null;
    }

    @Nullable
    private static String cleanVersion(@Nullable String value) {
        if (value == null) return null;
        String clean = value.trim();
        if (clean.isEmpty()) return null;
        return clean;
    }

    private static boolean containsCleanroomMarker(@Nullable String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains("cleanroom");
    }
}

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

package ca.dnamobile.droidbridgelauncher.modmanager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the real Minecraft game version from launcher profile/version ids.
 *
 * This is intentionally strict enough to avoid treating loader versions such as
 * Fabric 0.19.2 or Forge 47.4.0 as Minecraft versions, while still allowing
 * DroidBridge's custom instance/profile suffixes.
 *
 * Examples:
 *   fabric-loader-0.19.2-26.1.2       -> 26.1.2
 *   1.20.1-JimBobsNuts                -> 1.20.1
 *   1.20.1-JimBobsNuts-forge-47.4.0   -> 1.20.1
 *   1.20.1-forge-47.4.0               -> 1.20.1
 *   1.21.1-neoforge-21.1.90           -> 1.21.1
 *   24w14a-JimBobsNuts                -> 24w14a
 */
public final class ModManagerVersionResolver {
    private static final Pattern RELEASE_VERSION_PATTERN =
            Pattern.compile("(?<![0-9A-Za-z])([0-9]+(?:\\.[0-9]+){1,3})(?![0-9A-Za-z])");

    private static final Pattern SNAPSHOT_VERSION_PATTERN =
            Pattern.compile("(?<![0-9A-Za-z])([0-9]{2}w[0-9]{2}[a-z])(?![0-9A-Za-z])", Pattern.CASE_INSENSITIVE);

    private static final Pattern NAMED_SNAPSHOT_PATTERN =
            Pattern.compile("(?<![0-9A-Za-z])([0-9]+(?:\\.[0-9]+){1,3}-(?:pre|rc)[0-9]+)(?![0-9A-Za-z])", Pattern.CASE_INSENSITIVE);

    private ModManagerVersionResolver() {
    }

    @NonNull
    public static String resolveGameVersionForContent(@Nullable String versionId) {
        if (versionId == null) return "";

        String id = versionId.trim();
        if (id.isEmpty()) return "";

        String namedSnapshot = findNamedSnapshotVersion(id);
        if (!namedSnapshot.isEmpty()) return namedSnapshot;

        String weeklySnapshot = findWeeklySnapshotVersion(id);
        if (!weeklySnapshot.isEmpty()) return weeklySnapshot;

        String explicit = resolveKnownProfileFormat(id);
        if (!explicit.isEmpty() && isMinecraftReleaseVersion(explicit)) {
            return explicit;
        }

        String detected = findFirstMinecraftReleaseVersion(id);
        if (!detected.isEmpty()) return detected;

        // Last-resort fallback for unknown profile ids. Keep the old behavior instead
        // of returning blank so existing vanilla/shared IDs still work.
        return id;
    }

    @NonNull
    private static String resolveKnownProfileFormat(@NonNull String id) {
        String lower = id.toLowerCase(Locale.US);

        // fabric-loader-0.19.2-26.1.2 -> 26.1.2
        // quilt-loader-0.28.0-1.21.1 -> 1.21.1
        if (lower.startsWith("fabric-loader-") || lower.startsWith("quilt-loader-")) {
            String detected = findLastMinecraftReleaseVersion(id);
            if (!detected.isEmpty()) return detected;
        }

        // 1.20.1-forge-47.4.0 -> 1.20.1
        int forgeIndex = lower.indexOf("-forge-");
        if (forgeIndex > 0) {
            String beforeForge = id.substring(0, forgeIndex).trim();
            String detected = findFirstMinecraftReleaseVersion(beforeForge);
            if (!detected.isEmpty()) return detected;
        }

        // 1.21.1-neoforge-21.1.90 -> 1.21.1
        int neoForgeIndex = lower.indexOf("-neoforge-");
        if (neoForgeIndex > 0) {
            String beforeNeoForge = id.substring(0, neoForgeIndex).trim();
            String detected = findFirstMinecraftReleaseVersion(beforeNeoForge);
            if (!detected.isEmpty()) return detected;
        }

        return "";
    }

    @NonNull
    private static String findNamedSnapshotVersion(@NonNull String id) {
        Matcher matcher = NAMED_SNAPSHOT_PATTERN.matcher(id);
        return matcher.find() ? matcher.group(1) : "";
    }

    @NonNull
    private static String findWeeklySnapshotVersion(@NonNull String id) {
        Matcher matcher = SNAPSHOT_VERSION_PATTERN.matcher(id);
        return matcher.find() ? matcher.group(1) : "";
    }

    @NonNull
    private static String findFirstMinecraftReleaseVersion(@NonNull String id) {
        Matcher matcher = RELEASE_VERSION_PATTERN.matcher(id);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (isMinecraftReleaseVersion(candidate)) return candidate;
        }
        return "";
    }

    @NonNull
    private static String findLastMinecraftReleaseVersion(@NonNull String id) {
        Matcher matcher = RELEASE_VERSION_PATTERN.matcher(id);
        String result = "";
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (isMinecraftReleaseVersion(candidate)) result = candidate;
        }
        return result;
    }

    /**
     * Accept Minecraft-like versions while rejecting common loader versions.
     *
     * - 1.x.y is classic Java Minecraft.
     * - 20.x through 39.x covers the new DroidBridge/Minecraft version scheme.
     * - Rejects Fabric/Quilt loader versions like 0.19.2.
     * - Rejects Forge build versions like 47.4.0.
     */
    private static boolean isMinecraftReleaseVersion(@Nullable String version) {
        if (version == null || version.trim().isEmpty()) return false;

        String[] parts = version.trim().split("\\.");
        if (parts.length < 2) return false;

        int major;
        try {
            major = Integer.parseInt(parts[0]);
        } catch (NumberFormatException ignored) {
            return false;
        }

        if (major == 1) return true;
        return major >= 20 && major <= 39;
    }
}

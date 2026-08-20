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

package ca.dnamobile.droidbridgelauncher.renderer;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

/**
 * Up to three launcher-wide renderer defaults keyed by stable Minecraft release ranges.
 *
 * Priority is intentionally handled by callers:
 * special renderer locks -> per-instance override -> version rule -> launcher default.
 * This class therefore never changes the launcher's normal selected renderer.
 */
public final class RendererVersionRules {
    private static final String TAG = "RendererVersionRules";
    private static final String PREFS_NAME = "renderer_version_rules";
    private static final int MAX_RULES = 3;
    public static final String LATEST_SENTINEL = "__latest_release__";

    private static final Pattern EXACT_RELEASE_PATTERN = Pattern.compile("^\\d+(?:\\.\\d+){1,2}$");
    private static final Pattern VERSION_TOKEN_PATTERN = Pattern.compile("(?<!\\d)(\\d+(?:\\.\\d+){1,2})(?!\\d)");

    /**
     * Stable Java Edition releases only. Snapshots, pre-releases, release candidates,
     * alpha and beta builds are deliberately excluded from the settings picker.
     * Keep this list manually updated when a new stable release ships.
     */
    private static final String[] STABLE_RELEASES = new String[]{
            "1.0", "1.1",
            "1.2.1", "1.2.2", "1.2.3", "1.2.4", "1.2.5",
            "1.3.1", "1.3.2",
            "1.4.2", "1.4.4", "1.4.5", "1.4.6", "1.4.7",
            "1.5", "1.5.1", "1.5.2",
            "1.6.1", "1.6.2", "1.6.4",
            "1.7.2", "1.7.4", "1.7.5", "1.7.6", "1.7.7", "1.7.8", "1.7.9", "1.7.10",
            "1.8", "1.8.1", "1.8.2", "1.8.3", "1.8.4", "1.8.5", "1.8.6", "1.8.7", "1.8.8", "1.8.9",
            "1.9", "1.9.1", "1.9.2", "1.9.3", "1.9.4",
            "1.10", "1.10.1", "1.10.2",
            "1.11", "1.11.1", "1.11.2",
            "1.12", "1.12.1", "1.12.2",
            "1.13", "1.13.1", "1.13.2",
            "1.14", "1.14.1", "1.14.2", "1.14.3", "1.14.4",
            "1.15", "1.15.1", "1.15.2",
            "1.16", "1.16.1", "1.16.2", "1.16.3", "1.16.4", "1.16.5",
            "1.17", "1.17.1",
            "1.18", "1.18.1", "1.18.2",
            "1.19", "1.19.1", "1.19.2", "1.19.3", "1.19.4",
            "1.20", "1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.20.5", "1.20.6",
            "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11",
            "26.1", "26.1.1", "26.1.2", "26.2"
    };

    private RendererVersionRules() {
    }

    public static final class Rule {
        public boolean enabled;
        @NonNull public String minimumVersion;
        @NonNull public String maximumVersion;
        @NonNull public String rendererIdentifier;

        public Rule() {
            this(false, "1.17", LATEST_SENTINEL, "");
        }

        public Rule(
                boolean enabled,
                @NonNull String minimumVersion,
                @NonNull String maximumVersion,
                @NonNull String rendererIdentifier
        ) {
            this.enabled = enabled;
            this.minimumVersion = sanitizeKnownRelease(minimumVersion, "1.17");
            this.maximumVersion = LATEST_SENTINEL.equals(maximumVersion)
                    ? LATEST_SENTINEL
                    : sanitizeKnownRelease(maximumVersion, getCurrentLatestRelease());
            this.rendererIdentifier = rendererIdentifier == null ? "" : rendererIdentifier.trim();
        }

        @NonNull
        public Rule copy() {
            return new Rule(enabled, minimumVersion, maximumVersion, rendererIdentifier);
        }
    }

    public static int getMaxRules() {
        return MAX_RULES;
    }

    @NonNull
    public static String getCurrentLatestRelease() {
        return STABLE_RELEASES[STABLE_RELEASES.length - 1];
    }

    @NonNull
    public static List<String> getStableReleaseVersions() {
        return new ArrayList<>(Arrays.asList(STABLE_RELEASES));
    }

    @NonNull
    public static List<String> getMaximumVersionChoices() {
        ArrayList<String> result = new ArrayList<>(Arrays.asList(STABLE_RELEASES));
        result.add(LATEST_SENTINEL);
        return result;
    }

    @NonNull
    public static String getVersionChoiceLabel(@NonNull String value) {
        if (LATEST_SENTINEL.equals(value)) {
            return "Latest release (" + getCurrentLatestRelease() + "+)";
        }
        return value;
    }

    @NonNull
    public static List<Rule> loadRules(@NonNull Context context) {
        SharedPreferences prefs = prefs(context);
        ArrayList<Rule> rules = new ArrayList<>(MAX_RULES);
        for (int i = 0; i < MAX_RULES; i++) {
            String prefix = "rule_" + i + "_";
            rules.add(new Rule(
                    prefs.getBoolean(prefix + "enabled", false),
                    prefs.getString(prefix + "min", "1.17"),
                    prefs.getString(prefix + "max", LATEST_SENTINEL),
                    prefs.getString(prefix + "renderer", "")
            ));
        }
        return rules;
    }

    public static void saveRules(@NonNull Context context, @NonNull List<Rule> rules) {
        SharedPreferences.Editor editor = prefs(context).edit();
        for (int i = 0; i < MAX_RULES; i++) {
            Rule rule = i < rules.size() && rules.get(i) != null ? rules.get(i) : new Rule();
            String prefix = "rule_" + i + "_";
            editor.putBoolean(prefix + "enabled", rule.enabled);
            editor.putString(prefix + "min", sanitizeKnownRelease(rule.minimumVersion, "1.17"));
            editor.putString(prefix + "max", LATEST_SENTINEL.equals(rule.maximumVersion)
                    ? LATEST_SENTINEL
                    : sanitizeKnownRelease(rule.maximumVersion, getCurrentLatestRelease()));
            editor.putString(prefix + "renderer", rule.rendererIdentifier == null ? "" : rule.rendererIdentifier.trim());
        }
        if (!editor.commit()) {
            editor.apply();
        }
    }

    public static int countEnabledRules(@NonNull Context context) {
        int count = 0;
        for (Rule rule : loadRules(context)) {
            if (rule.enabled && !rule.rendererIdentifier.trim().isEmpty()) count++;
        }
        return count;
    }

    @Nullable
    public static RendererInterface resolveRenderer(
            @NonNull Context context,
            @Nullable String minecraftVersion
    ) {
        String release = normalizeMinecraftRelease(minecraftVersion);
        if (release == null) {
            Logging.i(TAG, "No stable Minecraft release could be resolved from " + safe(minecraftVersion)
                    + "; version renderer rules skipped.");
            return null;
        }

        List<Rule> rules = loadRules(context);
        for (int i = 0; i < rules.size(); i++) {
            Rule rule = rules.get(i);
            if (!rule.enabled || rule.rendererIdentifier.trim().isEmpty()) continue;
            if (!matchesRange(release, rule.minimumVersion, rule.maximumVersion)) continue;

            RendererInterface renderer = Renderers.findRenderer(context, rule.rendererIdentifier);
            if (renderer != null) {
                Logging.i(TAG, "Rule " + (i + 1) + " selected renderer=" + renderer.getRendererName()
                        + " for Minecraft " + release
                        + " range=" + rule.minimumVersion + ".." + getVersionChoiceLabel(rule.maximumVersion));
                return renderer;
            }

            Logging.i(TAG, "Rule " + (i + 1) + " matched Minecraft " + release
                    + " but renderer is unavailable: " + rule.rendererIdentifier);
        }
        return null;
    }

    public static boolean matchesRange(
            @Nullable String minecraftVersion,
            @Nullable String minimumVersion,
            @Nullable String maximumVersion
    ) {
        String version = normalizeMinecraftRelease(minecraftVersion);
        String min = normalizeMinecraftRelease(minimumVersion);
        if (version == null || min == null) return false;
        if (compareVersions(version, min) < 0) return false;
        if (LATEST_SENTINEL.equals(maximumVersion)) return true;
        String max = normalizeMinecraftRelease(maximumVersion);
        return max != null && compareVersions(version, max) <= 0;
    }

    public static boolean isValidRange(@Nullable String minimumVersion, @Nullable String maximumVersion) {
        String min = normalizeMinecraftRelease(minimumVersion);
        if (min == null) return false;
        if (LATEST_SENTINEL.equals(maximumVersion)) return true;
        String max = normalizeMinecraftRelease(maximumVersion);
        return max != null && compareVersions(min, max) <= 0;
    }

    public static int compareVersions(@NonNull String left, @NonNull String right) {
        int[] a = parseVersion(left);
        int[] b = parseVersion(right);
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    /**
     * Returns only stable release-looking versions. Loader profile IDs are tolerated by
     * searching for known release tokens (for example fabric-loader-...-1.21.4).
     */
    @Nullable
    public static String normalizeMinecraftRelease(@Nullable String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;

        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("snapshot")
                || lower.contains("pre-release")
                || lower.contains("pre_release")
                || lower.matches(".*(?:^|[-_. ])pre\\d+.*")
                || lower.matches(".*(?:^|[-_. ])rc\\d+.*")
                || lower.contains("release candidate")
                || lower.contains("alpha")
                || lower.contains("beta")
                || lower.contains("infdev")
                || lower.contains("indev")) {
            return null;
        }

        if (EXACT_RELEASE_PATTERN.matcher(value).matches()) {
            return value;
        }

        Matcher matcher = VERSION_TOKEN_PATTERN.matcher(value);
        String fallback = null;
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (isKnownRelease(candidate)) return candidate;
            if (fallback == null && looksLikeMinecraftRelease(candidate)) fallback = candidate;
        }
        return fallback;
    }

    private static boolean looksLikeMinecraftRelease(@NonNull String candidate) {
        int[] parts = parseVersion(candidate);
        if (parts.length < 2) return false;
        if (parts[0] == 1) return parts[1] >= 0 && parts[1] <= 99;
        return parts[0] >= 26 && parts[0] <= 99;
    }

    private static boolean isKnownRelease(@NonNull String value) {
        for (String release : STABLE_RELEASES) {
            if (release.equals(value)) return true;
        }
        return false;
    }

    @NonNull
    private static String sanitizeKnownRelease(@Nullable String value, @NonNull String fallback) {
        if (value != null && isKnownRelease(value.trim())) return value.trim();
        return fallback;
    }

    private static int[] parseVersion(@NonNull String value) {
        String[] tokens = value.split("\\.");
        int[] result = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            try {
                result[i] = Integer.parseInt(tokens[i]);
            } catch (NumberFormatException ignored) {
                result[i] = 0;
            }
        }
        return result;
    }

    @SuppressWarnings("deprecation")
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS
        );
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "<null>" : value;
    }
}

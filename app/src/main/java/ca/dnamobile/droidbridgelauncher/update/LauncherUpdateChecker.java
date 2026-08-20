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

package ca.dnamobile.droidbridgelauncher.update;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ca.dnamobile.droidbridgelauncher.BuildConfig;

/**
 * GitHub Releases update checker.
 *
 * This intentionally does not auto-install APKs. It only checks the release tag
 * and opens the release page or APK asset in the user's browser when they agree.
 */
public final class LauncherUpdateChecker {
    private static final String OWNER = "DNAMobileApplications";
    private static final String REPO = "DroidBridgeLauncher";

    /** Keep false for normal users. GitHub /releases/latest ignores prereleases. */
    private static final boolean INCLUDE_PRERELEASES = false;

    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/DNAMobileApplications/DroidBridgeLauncher/releases/latest";
    private static final String RELEASES_URL =
            "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases?per_page=10";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");

    private LauncherUpdateChecker() {
    }

    public interface Callback {
        void onResult(@NonNull LauncherUpdateInfo info);

        void onNoUpdate(@NonNull LauncherUpdateInfo latestInfo);

        void onError(@NonNull String message, @Nullable Throwable throwable);
    }

    public static void checkAsync(@NonNull Context context, @NonNull Callback callback) {
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                LauncherUpdateInfo info = fetchLatest(appContext);
                MAIN.post(() -> {
                    if (info.updateAvailable) {
                        callback.onResult(info);
                    } else {
                        callback.onNoUpdate(info);
                    }
                });
            } catch (Throwable throwable) {
                String message = throwable.getMessage();
                if (message == null || message.trim().isEmpty()) {
                    message = "Unable to check for launcher updates.";
                }
                final String finalMessage = message;
                MAIN.post(() -> callback.onError(finalMessage, throwable));
            }
        });
    }

    @NonNull
    private static LauncherUpdateInfo fetchLatest(@NonNull Context context) throws Exception {
        String json = getJson(INCLUDE_PRERELEASES ? RELEASES_URL : LATEST_RELEASE_URL);
        JSONObject release;
        if (INCLUDE_PRERELEASES) {
            JSONArray releases = new JSONArray(json);
            release = pickFirstPublishedRelease(releases);
        } else {
            release = new JSONObject(json);
        }
        return parseRelease(context, release);
    }

    @NonNull
    private static JSONObject pickFirstPublishedRelease(@NonNull JSONArray releases) throws Exception {
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            if (!release.optBoolean("draft", false)) {
                return release;
            }
        }
        throw new IllegalStateException("No published GitHub release was found.");
    }

    @NonNull
    private static LauncherUpdateInfo parseRelease(@NonNull Context context, @NonNull JSONObject release) {
        String tagName = release.optString("tag_name", "").trim();
        String releaseName = release.optString("name", "").trim();
        String releaseUrl = release.optString("html_url", "").trim();
        String releaseNotes = release.optString("body", "").trim();
        boolean prerelease = release.optBoolean("prerelease", false);

        String apkUrl = null;
        String apkName = null;
        long apkSize = 0L;

        JSONArray assets = release.optJSONArray("assets");
        if (assets != null) {
            Asset selected = chooseBestApkAsset(assets);
            if (selected != null) {
                apkUrl = selected.url;
                apkName = selected.name;
                apkSize = selected.sizeBytes;
            }
        }

        boolean updateAvailable = isRemoteVersionNewer(BuildConfig.VERSION_NAME, tagName)
                && !LauncherUpdatePreferences.isTagIgnored(context, tagName);

        return new LauncherUpdateInfo(
                tagName,
                releaseName,
                releaseUrl,
                releaseNotes,
                apkUrl,
                apkName,
                apkSize,
                prerelease,
                updateAvailable
        );
    }

    @Nullable
    private static Asset chooseBestApkAsset(@NonNull JSONArray assets) {
        Asset fallback = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;

            String name = asset.optString("name", "").trim();
            String url = asset.optString("browser_download_url", "").trim();
            long size = asset.optLong("size", 0L);
            String lower = name.toLowerCase(Locale.US);
            if (!lower.endsWith(".apk") || url.isEmpty()) continue;

            Asset candidate = new Asset(name, url, size);
            if (fallback == null) fallback = candidate;

            if (lower.contains("universal") || lower.contains("release")) {
                return candidate;
            }
        }
        return fallback;
    }

    private static boolean isRemoteVersionNewer(@NonNull String currentVersion, @NonNull String remoteTag) {
        List<Integer> current = extractVersionNumbers(currentVersion);
        List<Integer> remote = extractVersionNumbers(remoteTag);
        if (remote.isEmpty()) return false;
        if (current.isEmpty()) return true;

        int max = Math.max(current.size(), remote.size());
        for (int i = 0; i < max; i++) {
            int c = i < current.size() ? current.get(i) : 0;
            int r = i < remote.size() ? remote.get(i) : 0;
            if (r > c) return true;
            if (r < c) return false;
        }
        return false;
    }

    @NonNull
    private static List<Integer> extractVersionNumbers(@NonNull String value) {
        String clean = value.trim();
        if (clean.startsWith("v") || clean.startsWith("V")) {
            clean = clean.substring(1);
        }

        ArrayList<Integer> numbers = new ArrayList<>();
        Matcher matcher = NUMBER_PATTERN.matcher(clean);
        while (matcher.find()) {
            try {
                numbers.add(Integer.parseInt(matcher.group(1)));
            } catch (Throwable ignored) {
            }
        }
        return numbers;
    }

    @NonNull
    private static String getJson(@NonNull String requestUrl) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(requestUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(12_000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("User-Agent", "DroidBridgeLauncher/" + BuildConfig.VERSION_NAME);

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("GitHub update check failed. HTTP " + code);
            }

            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static final class Asset {
        @NonNull final String name;
        @NonNull final String url;
        final long sizeBytes;

        private Asset(@NonNull String name, @NonNull String url, long sizeBytes) {
            this.name = name;
            this.url = url;
            this.sizeBytes = sizeBytes;
        }
    }
}

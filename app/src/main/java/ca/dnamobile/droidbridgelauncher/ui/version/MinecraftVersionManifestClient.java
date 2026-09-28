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

package ca.dnamobile.droidbridgelauncher.ui.version;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import ca.dnamobile.droidbridgelauncher.data.model.MinecraftVersion;
import ca.dnamobile.droidbridgelauncher.network.MinecraftDownloadSource;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

public final class MinecraftVersionManifestClient {
    private static final String VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private MinecraftVersionManifestClient() {
    }

    @NonNull
    public static List<MinecraftVersion> loadVersions(@NonNull Context context) throws Exception {
        PathManager.initContextConstants(context);

        File cacheFile = new File(PathManager.FILE_VERSION_LIST);
        String manifestJson;
        try {
            manifestJson = downloadText(context, VERSION_MANIFEST_URL);
            writeString(cacheFile, manifestJson);
        } catch (Throwable networkError) {
            if (!cacheFile.exists()) throw networkError;
            manifestJson = readString(cacheFile);
        }

        return parseVersions(manifestJson);
    }

    @NonNull
    public static String downloadText(@NonNull String urlString) throws Exception {
        return downloadText(null, urlString);
    }

    @NonNull
    public static String downloadText(@Nullable Context context, @NonNull String urlString) throws Exception {
        Exception lastError = null;
        for (String candidateUrl : MinecraftDownloadSource.getCandidateUrls(context, urlString)) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(candidateUrl).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "DroidBridge/1.0");
                connection.setRequestMethod("GET");

                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream();
                String body = readStream(stream);
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("HTTP " + code + " while downloading " + candidateUrl);
                }
                return body;
            } catch (Exception error) {
                lastError = error;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }

        if (lastError != null) throw lastError;
        throw new IllegalStateException("No download URL available for " + urlString);
    }

    @NonNull
    private static List<MinecraftVersion> parseVersions(@NonNull String manifestJson) throws Exception {
        JSONObject root = new JSONObject(manifestJson);
        JSONArray versionsJson = root.getJSONArray("versions");
        ArrayList<MinecraftVersion> versions = new ArrayList<>(versionsJson.length());

        for (int i = 0; i < versionsJson.length(); i++) {
            JSONObject item = versionsJson.getJSONObject(i);
            versions.add(new MinecraftVersion(
                    item.optString("id"),
                    item.optString("type"),
                    item.optString("releaseTime"),
                    item.optString("url")
            ));
        }

        return versions;
    }

    @NonNull
    private static String readStream(@NonNull InputStream inputStream) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        }
    }

    @NonNull
    private static String readString(@NonNull File file) throws Exception {
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void writeString(@NonNull File file, @NonNull String value) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}

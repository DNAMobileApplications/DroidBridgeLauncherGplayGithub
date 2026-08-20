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

package ca.dnamobile.droidbridgelauncher.skin;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Small Minecraft Services client for the Microsoft-account cape picker.
 *
 * Important behavior:
 * - The launcher only lists capes returned by the account's /minecraft/profile response.
 * - There is no custom cape upload path here. Microsoft/Mojang only allow selecting or hiding
 *   an already-owned official cape through this normal Java profile API.
 */
public final class MicrosoftCapeService {
    private static final String PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
    private static final String ACTIVE_CAPE_URL = "https://api.minecraftservices.com/minecraft/profile/capes/active";

    private MicrosoftCapeService() {
    }

    @NonNull
    public static Profile fetchProfile(@NonNull String minecraftAccessToken) throws Exception {
        HttpURLConnection connection = openConnection(PROFILE_URL, "GET", minecraftAccessToken);
        int responseCode = connection.getResponseCode();
        String body = readBody(connection, responseCode);
        if (responseCode < 200 || responseCode >= 300) {
            throw new IllegalStateException(buildError("Unable to read Minecraft profile", responseCode, body));
        }

        JSONObject root = new JSONObject(body);
        String id = root.optString("id", "");
        String name = root.optString("name", "");
        JSONArray capesJson = root.optJSONArray("capes");
        ArrayList<CapeEntry> capes = new ArrayList<>();
        if (capesJson != null) {
            for (int i = 0; i < capesJson.length(); i++) {
                JSONObject capeJson = capesJson.optJSONObject(i);
                if (capeJson == null) continue;
                String capeId = capeJson.optString("id", "").trim();
                if (capeId.isEmpty()) continue;
                capes.add(new CapeEntry(
                        capeId,
                        capeJson.optString("alias", "").trim(),
                        capeJson.optString("url", "").trim(),
                        capeJson.optString("state", "").trim()
                ));
            }
        }
        return new Profile(id, name, capes);
    }

    public static void activateCape(
            @NonNull String minecraftAccessToken,
            @NonNull String capeId
    ) throws Exception {
        JSONObject request = new JSONObject();
        request.put("capeId", capeId);
        byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection connection = openConnection(ACTIVE_CAPE_URL, "PUT", minecraftAccessToken);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setDoOutput(true);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(payload);
        }

        int responseCode = connection.getResponseCode();
        String body = readBody(connection, responseCode);
        if (responseCode < 200 || responseCode >= 300) {
            throw new IllegalStateException(buildError("Unable to activate cape", responseCode, body));
        }
    }

    public static void hideActiveCape(@NonNull String minecraftAccessToken) throws Exception {
        // Minecraft Services can reject a body-less DELETE as HTTP 415 when the request does not
        // carry an explicit supported media type. Send JSON metadata even though the hide operation
        // itself has no logical payload. Some gateways additionally insist on a syntactically valid
        // JSON body, so retry once with {} only for 400/415. Hiding a cape is idempotent.
        CapeMutationResponse first = sendHideCapeDelete(minecraftAccessToken, false);
        if (isSuccess(first.responseCode)) return;

        if (first.responseCode == HttpURLConnection.HTTP_BAD_REQUEST
                || first.responseCode == HttpURLConnection.HTTP_UNSUPPORTED_TYPE) {
            CapeMutationResponse fallback = sendHideCapeDelete(minecraftAccessToken, true);
            if (isSuccess(fallback.responseCode)) return;
            throw new IllegalStateException(buildError(
                    "Unable to hide cape",
                    fallback.responseCode,
                    fallback.body
            ));
        }

        throw new IllegalStateException(buildError("Unable to hide cape", first.responseCode, first.body));
    }

    @NonNull
    private static CapeMutationResponse sendHideCapeDelete(
            @NonNull String minecraftAccessToken,
            boolean includeEmptyJsonBody
    ) throws Exception {
        HttpURLConnection connection = openConnection(ACTIVE_CAPE_URL, "DELETE", minecraftAccessToken);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        if (includeEmptyJsonBody) {
            byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
        } else {
            // Force an explicit zero-length entity so Android's HttpURLConnection does not leave the
            // DELETE request's media type ambiguous to the Minecraft Services gateway.
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(0);
            try (OutputStream ignored = connection.getOutputStream()) {
                // Intentionally empty.
            }
        }

        int responseCode = connection.getResponseCode();
        String body = readBody(connection, responseCode);
        connection.disconnect();
        return new CapeMutationResponse(responseCode, body);
    }

    private static boolean isSuccess(int responseCode) {
        return responseCode >= 200 && responseCode < 300;
    }

    private static final class CapeMutationResponse {
        final int responseCode;
        @NonNull
        final String body;

        CapeMutationResponse(int responseCode, @Nullable String body) {
            this.responseCode = responseCode;
            this.body = body == null ? "" : body;
        }
    }

    @NonNull
    private static HttpURLConnection openConnection(
            @NonNull String url,
            @NonNull String method,
            @NonNull String minecraftAccessToken
    ) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Authorization", "Bearer " + minecraftAccessToken);
        connection.setRequestProperty("Accept", "application/json");
        return connection;
    }

    @NonNull
    private static String readBody(@NonNull HttpURLConnection connection, int responseCode) {
        InputStream stream = null;
        try {
            stream = responseCode >= 200 && responseCode < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            if (stream == null) return "";
            StringBuilder builder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                }
            }
            return builder.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    @NonNull
    private static String buildError(@NonNull String prefix, int code, @Nullable String body) {
        String cleanBody = body == null ? "" : body.trim();
        if (cleanBody.length() > 300) cleanBody = cleanBody.substring(0, 300) + "...";
        return cleanBody.isEmpty()
                ? prefix + " (HTTP " + code + ")"
                : prefix + " (HTTP " + code + "): " + cleanBody;
    }


    @NonNull
    private static String normalizeTextureUrl(@Nullable String rawUrl) {
        if (rawUrl == null) return "";
        String value = rawUrl.trim();
        if (value.startsWith("//")) return "https:" + value;
        if (value.startsWith("http://textures.minecraft.net/")) {
            return "https://textures.minecraft.net/" + value.substring("http://textures.minecraft.net/".length());
        }
        return value;
    }

    public static final class Profile {
        @NonNull
        public final String id;
        @NonNull
        public final String name;
        @NonNull
        public final List<CapeEntry> capes;

        private Profile(
                @NonNull String id,
                @NonNull String name,
                @NonNull List<CapeEntry> capes
        ) {
            this.id = id;
            this.name = name;
            this.capes = Collections.unmodifiableList(capes);
        }

        @Nullable
        public CapeEntry getActiveCape() {
            for (CapeEntry cape : capes) {
                if (cape.isActive()) return cape;
            }
            return null;
        }
    }

    public static final class CapeEntry {
        @NonNull
        public final String id;
        @NonNull
        public final String alias;
        @NonNull
        public final String url;
        @NonNull
        public final String state;

        private CapeEntry(
                @NonNull String id,
                @NonNull String alias,
                @NonNull String url,
                @NonNull String state
        ) {
            this.id = id;
            this.alias = alias;
            this.url = normalizeTextureUrl(url);
            this.state = state;
        }

        public boolean isActive() {
            return "ACTIVE".equalsIgnoreCase(state);
        }

        @NonNull
        public String getDisplayName() {
            if (!alias.trim().isEmpty()) {
                return prettify(alias);
            }
            return prettify(id);
        }

        @NonNull
        private static String prettify(@NonNull String raw) {
            String normalized = raw.replace('_', ' ').replace('-', ' ').trim();
            if (normalized.isEmpty()) return "Cape";
            String[] parts = normalized.split("\\s+");
            StringBuilder builder = new StringBuilder();
            for (String part : parts) {
                if (part.isEmpty()) continue;
                if (builder.length() > 0) builder.append(' ');
                builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
                if (part.length() > 1) builder.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
            return builder.length() == 0 ? raw : builder.toString();
        }
    }
}

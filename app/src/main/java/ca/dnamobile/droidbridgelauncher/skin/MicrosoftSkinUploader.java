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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Uploads a skin to the signed-in Minecraft Java profile. */
public final class MicrosoftSkinUploader {
    private static final String SKIN_UPLOAD_URL = "https://api.minecraftservices.com/minecraft/profile/skins";

    private MicrosoftSkinUploader() {
    }

    public static void uploadSkin(
            @NonNull String minecraftAccessToken,
            @NonNull File skinFile,
            @NonNull SkinModelType model
    ) throws IOException {
        if (minecraftAccessToken.trim().isEmpty()) {
            throw new IOException("Missing Minecraft access token. Refresh the Microsoft account and try again.");
        }
        if (!skinFile.isFile()) {
            throw new IOException("Selected skin file was not found.");
        }
        if (!CustomSkinStore.isSkinValid(skinFile)) {
            throw new IOException("Invalid skin. Use a 64x64 or 64x32 PNG skin.");
        }

        String variant = model == SkinModelType.SLIM ? "slim" : "classic";
        String boundary = "DroidBridgeSkinBoundary" + UUID.randomUUID().toString().replace("-", "");

        HttpURLConnection connection = (HttpURLConnection) new URL(SKIN_UPLOAD_URL).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoInput(true);
        connection.setDoOutput(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("Authorization", "Bearer " + minecraftAccessToken);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (OutputStream output = connection.getOutputStream()) {
            writeTextPart(output, boundary, "variant", variant);
            writeFilePart(output, boundary, "file", "skin.png", "image/png", skinFile);
            output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }

        int code = connection.getResponseCode();
        String body = readFully(code >= 200 && code < 300
                ? connection.getInputStream()
                : connection.getErrorStream());
        connection.disconnect();

        if (code < 200 || code >= 300) {
            throw new IOException("Minecraft skin upload failed: HTTP " + code + (body.isEmpty() ? "" : " " + body));
        }
    }

    private static void writeTextPart(
            @NonNull OutputStream output,
            @NonNull String boundary,
            @NonNull String name,
            @NonNull String value
    ) throws IOException {
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFilePart(
            @NonNull OutputStream output,
            @NonNull String boundary,
            @NonNull String name,
            @NonNull String filename,
            @NonNull String contentType,
            @NonNull File file
    ) throws IOException {
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));

        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    @NonNull
    private static String readFully(InputStream inputStream) throws IOException {
        if (inputStream == null) return "";
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }
}

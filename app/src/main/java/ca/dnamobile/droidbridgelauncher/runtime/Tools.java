/*
 * DroidBridge Launcher runtime bridge component.
 *
 * DroidBridge modifications:
 * Copyright (c) 2026 DNA Mobile Applications.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package ca.dnamobile.droidbridgelauncher.runtime;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

public final class Tools {
    public static final int DEVICE_ARCHITECTURE = Architecture.getDeviceArchitecture();

    private Tools() {
    }

    @NonNull
    public static String read(@NonNull InputStream inputStream) throws IOException {
        try (InputStream input = inputStream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copy(input, output);
            return output.toString(StandardCharsets.UTF_8.name()).trim();
        }
    }

    public static void copy(@NonNull InputStream input, @NonNull OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    public static void copyAssetFile(
            @NonNull Context context,
            @NonNull String assetPath,
            @NonNull String outputDirectory,
            boolean replace
    ) throws IOException {
        String outputName;
        int slash = assetPath.lastIndexOf('/');
        outputName = slash >= 0 ? assetPath.substring(slash + 1) : assetPath;
        copyAssetFile(context, assetPath, outputDirectory, outputName, replace);
    }

    public static void copyAssetFile(
            @NonNull Context context,
            @NonNull String assetPath,
            @NonNull String outputDirectory,
            @NonNull String outputName,
            boolean replace
    ) throws IOException {
        File outputDir = new File(outputDirectory);
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("Unable to create directory: " + outputDir.getAbsolutePath());
        }

        File outputFile = new File(outputDir, outputName);
        if (outputFile.exists() && !replace) return;

        AssetManager assetManager = context.getAssets();
        try (InputStream input = assetManager.open(assetPath); OutputStream output = new FileOutputStream(outputFile)) {
            copy(input, output);
        }
    }

    public static boolean checkStorageRoot() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state);
    }

    @NonNull
    public static String printToString(@Nullable Throwable throwable) {
        if (throwable == null) return "";
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
    public static final class SDL {
        private SDL() {}

        public static native void initializeControllerSubsystems();
    }
}

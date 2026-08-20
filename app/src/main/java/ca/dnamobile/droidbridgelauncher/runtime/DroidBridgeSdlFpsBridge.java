/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */
package ca.dnamobile.droidbridgelauncher.runtime;

import android.content.Context;
import android.os.SystemClock;
import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Cross-VM FPS handoff for SDL3 RenderPearl.
 *
 * Minecraft runs in the embedded OpenJDK VM, while the floating FPS badge runs
 * in Android ART. The tiny native frame marker writes one sample per second to
 * the app cache so both VMs can share the count without loading the large
 * renderer bridge into the wrong VM.
 */
public final class DroidBridgeSdlFpsBridge {
    private static final String FILE_NAME = "droidbridge_sdl_fps.txt";
    private static final long MAX_SAMPLE_AGE_MS = 2500L;

    private DroidBridgeSdlFpsBridge() {
    }

    @NonNull
    public static File prepare(@NonNull Context context) {
        File file = new File(context.getCacheDir(), FILE_NAME);
        try {
            if (file.exists() && !file.delete()) {
                // A stale sample is rejected by timestamp even if deletion fails.
            }
        } catch (Throwable ignored) {
        }
        return file;
    }

    public static int readCurrentFps(@NonNull Context context) {
        File file = new File(context.getCacheDir(), FILE_NAME);
        if (!file.isFile() || !file.canRead()) return 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            if (line == null) return 0;
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2) return 0;

            int fps = Integer.parseInt(parts[0]);
            long sampleTimestampMs = Long.parseLong(parts[1]);
            long age = System.currentTimeMillis() - sampleTimestampMs;
            if (age < 0L || age > MAX_SAMPLE_AGE_MS) {
                // The Java LWJGL counter writes wall time. The optional native
                // counter writes CLOCK_MONOTONIC time. Accept either format so
                // Vulkan FPS survives whichever bridge is active on the device.
                age = SystemClock.elapsedRealtime() - sampleTimestampMs;
            }
            if (age < 0L || age > MAX_SAMPLE_AGE_MS) return 0;
            return Math.max(0, Math.min(10000, fps));
        } catch (Throwable ignored) {
            return 0;
        }
    }
}

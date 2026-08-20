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

package ca.dnamobile.droidbridgelauncher.modcompat;

import android.content.Context;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.system.Os;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.logs.LauncherLogManager;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;
import ca.dnamobile.droidbridgelauncher.runtime.Logger;
import top.fifthlight.touchcontroller.proxy.client.LauncherProxyClient;
import top.fifthlight.touchcontroller.proxy.client.MessageTransport;
import top.fifthlight.touchcontroller.proxy.client.android.transport.UnixSocketTransportKt;
import top.fifthlight.touchcontroller.proxy.message.VibrateMessage;

/**
 * Launcher-side bridge for the TouchController mod.
 *
 * TouchController needs Android raw multi-touch contacts, not DroidBridge's normal
 * finger-to-GLFW-mouse emulation. When the mod is installed, this class starts the
 * TouchController proxy socket before Minecraft launches and routes finger MotionEvents
 * directly to the proxy client.
 */
public final class TouchControllerModCompat {
    private static final String TAG = "TouchController";
    private static final String SOCKET_NAME = "DroidBridge";
    private static final int DEFAULT_VIBRATION_MS = 120;
    private static final int MIN_VIBRATION_MS = 80;
    private static final int MAX_VIBRATION_MS = 500;

    private static final SparseIntArray POINTER_ID_MAP = new SparseIntArray();
    private static LauncherProxyClient proxyClient;
    private static boolean active;
    private static int nextPointerId = 1;

    private TouchControllerModCompat() {
    }

    public static synchronized void prepare(@NonNull Context context, @Nullable File gameDirectory) {
        if (!hasTouchController(gameDirectory)) {
            reset();
            append("TouchController mod not found; launcher raw touch proxy disabled");
            return;
        }

        active = true;
        startProxy(context.getApplicationContext());
    }

    public static synchronized void reset() {
        active = false;
        POINTER_ID_MAP.clear();
        nextPointerId = 1;
        proxyClient = null;
        try {
            Os.unsetenv("TOUCH_CONTROLLER_PROXY_SOCKET");
        } catch (Throwable ignored) {
        }
    }

    public static boolean isActive() {
        return active && proxyClient != null;
    }

    /** Clears active contacts without stopping the proxy connection. */
    public static synchronized void clearPointers() {
        POINTER_ID_MAP.clear();
        nextPointerId = 1;

        LauncherProxyClient client = proxyClient;
        if (!active || client == null) return;

        try {
            client.clearPointer();
        } catch (Throwable throwable) {
            append("TouchController pointer clear failed: " + throwable);
        }
    }

    public static boolean handleMotionEvent(@NonNull MotionEvent event, @NonNull View view) {
        LauncherProxyClient client = proxyClient;
        if (!active || client == null) return false;

        try {
            progressEvent(event, view, client);
            return true;
        } catch (Throwable throwable) {
            append("TouchController pointer route failed: " + throwable);
            return true;
        }
    }

    private static synchronized void startProxy(@NonNull Context context) {
        if (proxyClient != null) {
            append("TouchController proxy already running");
            return;
        }

        try {
            MessageTransport transport = UnixSocketTransportKt.UnixSocketTransport(SOCKET_NAME);
            Os.setenv("TOUCH_CONTROLLER_PROXY_SOCKET", SOCKET_NAME, true);

            LauncherProxyClient client = new LauncherProxyClient(transport);
            Vibrator vibrator = context.getSystemService(Vibrator.class);
            if (vibrator != null) {
                client.setVibrationHandler(new DroidBridgeVibrationHandler(vibrator));
            }
            client.run();

            proxyClient = client;
            active = true;
            append("TouchController proxy client created with socket=" + SOCKET_NAME);
        } catch (Throwable throwable) {
            proxyClient = null;
            active = false;
            append("TouchController proxy client create failed: " + throwable);
            Logging.e(TAG, "TouchController proxy client create failed", throwable);
        }
    }

    private static void progressEvent(
            @NonNull MotionEvent event,
            @NonNull View view,
            @NonNull LauncherProxyClient client
    ) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                handlePointerDown(event, view, client, 0);
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                handlePointerDown(event, view, client, event.getActionIndex());
                break;

            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int androidPointerId = event.getPointerId(i);
                    int touchControllerPointerId = getOrCreateTouchControllerPointerId(androidPointerId);
                    client.addPointer(touchControllerPointerId, getOffsetX(event, i, view), getOffsetY(event, i, view));
                }
                break;

            case MotionEvent.ACTION_POINTER_UP: {
                int index = event.getActionIndex();
                int androidPointerId = event.getPointerId(index);
                int touchControllerPointerId = getTouchControllerPointerId(androidPointerId);
                if (touchControllerPointerId != 0) {
                    POINTER_ID_MAP.delete(androidPointerId);
                    client.removePointer(touchControllerPointerId);
                }
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                client.clearPointer();
                POINTER_ID_MAP.clear();
                nextPointerId = 1;
                break;

            default:
                break;
        }
    }

    private static void handlePointerDown(
            @NonNull MotionEvent event,
            @NonNull View view,
            @NonNull LauncherProxyClient client,
            int index
    ) {
        int androidPointerId = event.getPointerId(index);
        int touchControllerPointerId = getOrCreateTouchControllerPointerId(androidPointerId);
        client.addPointer(touchControllerPointerId, getOffsetX(event, index, view), getOffsetY(event, index, view));
    }

    private static int getTouchControllerPointerId(int androidPointerId) {
        return POINTER_ID_MAP.get(androidPointerId, 0);
    }

    private static int getOrCreateTouchControllerPointerId(int androidPointerId) {
        int existing = getTouchControllerPointerId(androidPointerId);
        if (existing != 0) return existing;

        int created = nextPointerId++;
        if (nextPointerId == Integer.MAX_VALUE) nextPointerId = 1;
        POINTER_ID_MAP.put(androidPointerId, created);
        return created;
    }

    private static float getOffsetX(@NonNull MotionEvent event, int index, @NonNull View view) {
        float width = Math.max(1.0f, view.getWidth());
        return clamp01(event.getX(index) / width);
    }

    private static float getOffsetY(@NonNull MotionEvent event, int index, @NonNull View view) {
        float height = Math.max(1.0f, view.getHeight());
        return clamp01(event.getY(index) / height);
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value)) return 0.0f;
        if (value < 0.0f) return 0.0f;
        if (value > 1.0f) return 1.0f;
        return value;
    }

    private static boolean hasTouchController(@Nullable File gameDirectory) {
        if (containsTouchControllerJar(gameDirectory == null ? null : new File(gameDirectory, "mods"))) {
            return true;
        }

        if (gameDirectory != null) {
            File parent = gameDirectory.getParentFile();
            if (parent != null && containsTouchControllerJar(new File(parent, "mods"))) {
                return true;
            }
        }

        try {
            if (PathManager.DIR_MINECRAFT_HOME != null
                    && containsTouchControllerJar(new File(PathManager.DIR_MINECRAFT_HOME, "mods"))) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static boolean containsTouchControllerJar(@Nullable File modsDir) {
        if (modsDir == null || !modsDir.isDirectory()) return false;

        File[] files = modsDir.listFiles();
        if (files == null) return false;

        for (File file : files) {
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (!file.isFile() || !name.endsWith(".jar")) continue;

            if (name.contains("touchcontroller") || name.contains("touch-controller")) {
                append("TouchController mod jar found: " + file.getAbsolutePath());
                return true;
            }

            if (jarContainsTouchControllerMetadata(file)) {
                append("TouchController mod metadata found: " + file.getAbsolutePath());
                return true;
            }
        }

        return false;
    }

    private static boolean jarContainsTouchControllerMetadata(@NonNull File jarFile) {
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            ZipEntry fabric = zipFile.getEntry("fabric.mod.json");
            if (fabric != null) {
                String text = readZipEntry(zipFile, fabric).toLowerCase(Locale.ROOT);
                if (text.contains("\"id\"") && text.contains("touchcontroller")) return true;
            }

            ZipEntry quilt = zipFile.getEntry("quilt.mod.json");
            if (quilt != null) {
                String text = readZipEntry(zipFile, quilt).toLowerCase(Locale.ROOT);
                if (text.contains("touchcontroller")) return true;
            }

            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @NonNull
    private static String readZipEntry(@NonNull ZipFile zipFile, @NonNull ZipEntry entry) throws java.io.IOException {
        try (java.io.InputStream input = zipFile.getInputStream(entry);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) >= 0 && total < 128 * 1024) {
                output.write(buffer, 0, read);
                total += read;
            }
            return output.toString("UTF-8");
        }
    }

    private static void append(@NonNull String message) {
        String clean = TAG + ": " + message;
        try {
            LauncherLogManager.append(clean);
        } catch (Throwable ignored) {
            try {
                Logger.appendToLog(clean);
            } catch (Throwable ignoredAgain) {
            }
        }
    }

    private static final class DroidBridgeVibrationHandler implements LauncherProxyClient.VibrationHandler {
        @NonNull
        private final Vibrator vibrator;

        DroidBridgeVibrationHandler(@NonNull Vibrator vibrator) {
            this.vibrator = vibrator;
        }

        @Override
        public void vibrate(@NonNull VibrateMessage.Kind kind) {
            try {
                int durationMs = Math.max(MIN_VIBRATION_MS, Math.min(MAX_VIBRATION_MS, DEFAULT_VIBRATION_MS));
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE));
            } catch (Throwable throwable) {
                append("Failed to vibrate device: " + throwable);
            }
        }
    }
}

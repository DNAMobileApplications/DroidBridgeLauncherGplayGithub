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
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;
import ca.dnamobile.droidbridgelauncher.runtime.DroidBridgeSDL3Bootstrap;
import ca.dnamobile.droidbridgelauncher.runtime.Logger;
import ca.dnamobile.droidbridgelauncher.runtime.MinecraftGLSurface;
import ca.dnamobile.droidbridgelauncher.runtime.Tools;

import org.libsdl.app.SDL;
import org.libsdl.app.SDLControllerManager;

/**
 * Launcher-side compatibility for Controlify.
 *
 * Important:
 * - MinecraftGLSurface.sdlEnabled is only an event-routing switch.
 * - SDLControllerManager.pollInputDevices() is what actually discovers Android controllers
 *   and registers them with SDL.
 */
public final class ControlifySDL {
    private static final String TAG = "ControlifySDL";
    private static volatile boolean initialized;

    private ControlifySDL() {
    }

    public static synchronized void initializeIfNeeded(@NonNull Context context, @Nullable File gameDirectory) {
        if (!hasControlify(gameDirectory)) {
            initialized = false;
            // SDL remains the window/mouse/keyboard platform for Snapshot 4, but
            // vanilla Minecraft does not consume SDL game-controller events. Keep
            // controller ownership with DroidBridge so its virtual cursor and mapped
            // keyboard/mouse actions remain available.
            MinecraftGLSurface.sdlEnabled = false;
            if (DroidBridgeSDL3Bootstrap.isRequested()) {
                append("ControlifySDL: Controlify not found; SDL window input active, launcher gamepad cursor enabled");
            } else {
                append("ControlifySDL: Controlify not found, SDL controller routing disabled");
            }
            return;
        }

        initialize(context);
    }

    public static synchronized void initialize(@NonNull Context context) {
        Context appContext = context.getApplicationContext();

        // This must be true for Controlify launches. If it stays false, DroidBridge's
        // own in-game controller path keeps ownership and SDL/Controlify never receives input.
        MinecraftGLSurface.sdlEnabled = true;

        if (initialized) {
            append("ControlifySDL: already initialized; SDL routing forced enabled");
            pollNowAndLater();
            return;
        }

        try {
            SDL.loadLibrary("SDL3", appContext);
            SDL.setupJNI();
            SDL.initialize();
            SDL.setContext(appContext);

            SDLControllerManager.initialize();
            Tools.SDL.initializeControllerSubsystems();

            initialized = true;
            append("ControlifySDL: SDL controller bridge initialized; sdlEnabled=true");
            logAndroidInputDevices();
            pollNowAndLater();
        } catch (Throwable t) {
            initialized = false;
            MinecraftGLSurface.sdlEnabled = false;
            append("ControlifySDL: failed to initialize optional controller bridge; launcher gamepad cursor restored: " + t);
        }
    }

    public static synchronized void reset() {
        initialized = false;
        // Only Controlify owns direct SDL controller routing. Vanilla Snapshot 4
        // keeps DroidBridge's mapped controller cursor/actions.
        MinecraftGLSurface.sdlEnabled = false;
    }

    /**
     * True only after Controlify has successfully initialized DroidBridge's SDL
     * controller route for the current game session. Controllable may also enable
     * the shared SDL bridge, so checking MinecraftGLSurface.sdlEnabled alone is
     * intentionally not sufficient.
     */
    public static boolean isActive() {
        return initialized && MinecraftGLSurface.sdlEnabled;
    }

    private static void pollNowAndLater() {
        try {
            SDLControllerManager.initialize();
            SDLControllerManager.pollInputDevices();
            append("ControlifySDL: pollInputDevices() completed immediately");
        } catch (Throwable t) {
            append("ControlifySDL: immediate pollInputDevices() failed: " + t);
        }

        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(ControlifySDL::safePollInputDevices, 250L);
        handler.postDelayed(ControlifySDL::safePollInputDevices, 1000L);
    }

    private static void safePollInputDevices() {
        try {
            SDLControllerManager.initialize();
            SDLControllerManager.pollInputDevices();
            append("ControlifySDL: delayed pollInputDevices() completed");
        } catch (Throwable t) {
            append("ControlifySDL: delayed pollInputDevices() failed: " + t);
        }
    }

    private static void logAndroidInputDevices() {
        try {
            int count = 0;
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice device = InputDevice.getDevice(id);
                if (device == null) continue;

                int source = device.getSources();
                boolean gamepad = (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
                boolean joystick = (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
                if (!gamepad && !joystick) continue;

                count++;
                append("ControlifySDL: Android controller device id=" + id
                        + " name=" + device.getName()
                        + " descriptor=" + device.getDescriptor()
                        + " sources=0x" + Integer.toHexString(source));
            }
            append("ControlifySDL: Android controller count=" + count);
        } catch (Throwable t) {
            append("ControlifySDL: failed to list Android input devices: " + t);
        }
    }

    public static boolean hasControlify(@Nullable File gameDirectory) {
        if (containsControlifyJar(new File(gameDirectory == null ? "" : gameDirectory.getAbsolutePath(), "mods"))) {
            return true;
        }

        if (gameDirectory != null) {
            File parent = gameDirectory.getParentFile();
            if (parent != null && containsControlifyJar(new File(parent, "mods"))) {
                return true;
            }
        }

        try {
            if (PathManager.DIR_MINECRAFT_HOME != null
                    && containsControlifyJar(new File(PathManager.DIR_MINECRAFT_HOME, "mods"))) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static boolean containsControlifyJar(@Nullable File modsDir) {
        if (modsDir == null || !modsDir.isDirectory()) {
            return false;
        }

        File[] files = modsDir.listFiles();
        if (files == null) return false;

        for (File file : files) {
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (file.isFile() && name.endsWith(".jar") && name.contains("controlify")) {
                append("ControlifySDL: found " + file.getAbsolutePath());
                return true;
            }
        }
        return false;
    }

    private static void append(@NonNull String message) {
        try {
            Logger.appendToLog(stripTrailingLineBreaks(message));
        } catch (Throwable ignored) {
        }
    }

    @NonNull
    private static String stripTrailingLineBreaks(@NonNull String message) {
        int end = message.length();
        while (end > 0) {
            char c = message.charAt(end - 1);
            if (c != '\n' && c != '\r') break;
            end--;
        }
        return end == message.length() ? message : message.substring(0, end);
    }
}

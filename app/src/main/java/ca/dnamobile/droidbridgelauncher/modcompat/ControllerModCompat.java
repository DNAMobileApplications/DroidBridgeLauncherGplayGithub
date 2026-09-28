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

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import ca.dnamobile.droidbridgelauncher.input.GamepadInputController;
import ca.dnamobile.droidbridgelauncher.launcher.LaunchPlan;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;
import ca.dnamobile.droidbridgelauncher.runtime.Logger;
import ca.dnamobile.droidbridgelauncher.runtime.MinecraftGLSurface;
import ca.dnamobile.droidbridgelauncher.runtime.Tools;

import org.libsdl.app.SDL;
import org.libsdl.app.SDLControllerManager;
import org.libsdl.app.SDLSurface;

import org.lwjgl.glfw.DroidBridgeBtaGamepad;

/**
 * Compatibility hooks for controller mods that do their own native/controller work.
 *
 * Controlify is handled by ControlifySDL. This class handles Legacy4J and Controllable.
 */
public final class ControllerModCompat {
    private static final String TAG = "ControllerModCompat";

    private static Logger.eventLogListener legacy4JLogListener;
    private static Logger.eventLogListener controllableLogListener;
    private static boolean suppressLauncherGamepadInput;
    private static boolean mirrorAndroidGamepadToGlfw;
    private static volatile boolean controllableActive;

    private ControllerModCompat() {
    }

    public static synchronized void prepare(@NonNull Context context, @Nullable File gameDirectory) {
        if (gameDirectory == null) return;

        if (!LauncherPreferences.isSdlControllerModCompatEnabled(context)) {
            append("disabled by preference");
            suppressLauncherGamepadInput = false;
            mirrorAndroidGamepadToGlfw = false;
            controllableActive = false;
            GamepadInputController.glfwGamepadMirrorEnabled = false;
            GamepadInputController.btaNativeControllerBridgeEnabled = false;
            GamepadInputController.btaNativeControllerOwnsLauncherInput = false;
            MinecraftGLSurface.controllerModOwnsGamepadInput = false;
            MinecraftGLSurface.sdlEnabled = false;
            return;
        }

        suppressLauncherGamepadInput = false;
        mirrorAndroidGamepadToGlfw = false;
        controllableActive = false;
        GamepadInputController.glfwGamepadMirrorEnabled = false;
        MinecraftGLSurface.controllerModOwnsGamepadInput = false;

        startLegacy4JMitigation(context, gameDirectory);
        startControllableMitigation(context, gameDirectory);
    }

    public static synchronized void reset() {
        if (legacy4JLogListener != null) {
            Logger.removeLogListener(legacy4JLogListener);
            legacy4JLogListener = null;
        }
        if (controllableLogListener != null) {
            Logger.removeLogListener(controllableLogListener);
            controllableLogListener = null;
        }
        suppressLauncherGamepadInput = false;
        mirrorAndroidGamepadToGlfw = false;
        controllableActive = false;
        GamepadInputController.glfwGamepadMirrorEnabled = false;
        GamepadInputController.btaNativeControllerBridgeEnabled = false;
        GamepadInputController.btaNativeControllerOwnsLauncherInput = false;
        MinecraftGLSurface.controllerModOwnsGamepadInput = false;
        MinecraftGLSurface.sdlEnabled = false;
    }

    public static boolean shouldSuppressLauncherGamepadInput() {
        return suppressLauncherGamepadInput;
    }

    public static boolean shouldMirrorAndroidGamepadToGlfw() {
        return mirrorAndroidGamepadToGlfw;
    }

    public static boolean isControllableActive() {
        return controllableActive;
    }

    /**
     * True while a controller mod owns its own cursor/gamepad presentation.
     * DroidBridge should keep its software cursor hidden in this state so the
     * mod's own cursor/camera affordances are the only visible controller UI.
     */
    public static boolean shouldHideLauncherCursorForControllerMod() {
        return suppressLauncherGamepadInput
                || mirrorAndroidGamepadToGlfw
                || MinecraftGLSurface.controllerModOwnsGamepadInput
                || MinecraftGLSurface.sdlEnabled;
    }

    public static boolean hasControllable(@Nullable File gameDirectory) {
        return gameDirectory != null && hasMod(gameDirectory, "controllable");
    }

    /**
     * Pass the Android-controller shared-state path to the Minecraft JVM.
     *
     * Do not add a replacement LWJGL jar here. DroidBridge already ships its patched
     * LWJGL/GLFW classes as part of the runtime. Older Controllable builds only need
     * the game-side GLFW class to know where the launcher-side Android bridge writes
     * the virtual gamepad state.
     */
    @NonNull
    public static LaunchPlan applyLaunchPlanCompatibility(
            @NonNull Context context,
            @NonNull LaunchPlan plan,
            @Nullable File gameDirectory
    ) {
        if (gameDirectory == null || !needsGlfwGamepadStateJvmArg(gameDirectory)) {
            return plan;
        }

        final boolean legacy4JPresent = hasMod(gameDirectory, "legacy4j", "legacy-4j", "legacy");
        File sharedStateFile = getGlfwGamepadStateFile(context);
        String sharedStateArg = "-Ddroidbridge.glfw.gamepad.state=" + sharedStateFile.getAbsolutePath();

        List<String> current = plan.getJvmArgs();
        ArrayList<String> patched = new ArrayList<>(current.size() + 2);
        patched.addAll(current);

        boolean foundSharedState = false;
        for (int i = 0; i < patched.size(); i++) {
            String value = patched.get(i);
            if (value == null || !value.startsWith("-Ddroidbridge.glfw.gamepad.state=")) continue;
            foundSharedState = true;
            if (!sharedStateArg.equals(value)) {
                patched.set(i, sharedStateArg);
                append("GLFW gamepad shared-state JVM arg updated: " + sharedStateFile.getAbsolutePath());
            } else {
                append("GLFW gamepad shared-state JVM arg already active: " + sharedStateFile.getAbsolutePath());
            }
        }

        if (!foundSharedState) {
            patched.add(0, sharedStateArg);
            append("GLFW gamepad shared-state JVM arg active: " + sharedStateFile.getAbsolutePath());
        }

        if (legacy4JPresent) {
            if (Legacy4JControllerCompatAgentInstaller.addJavaAgentArg(context, patched)) {
                append("Legacy4J runtime controller compatibility agent active; mod jar is untouched");
            } else {
                append("Legacy4J runtime controller compatibility agent unavailable; keeping GLFW shared-state bridge active");
            }
        }

        return plan.copyWithJvmArgs(patched);
    }

    private static boolean needsGlfwGamepadStateJvmArg(@NonNull File gameDirectory) {
        return hasControllable(gameDirectory) || hasMod(gameDirectory, "legacy4j", "legacy-4j", "legacy");
    }

    @NonNull
    private static File getGlfwGamepadStateFile(@NonNull Context context) {
        return new File(context.getCacheDir(), "droidbridge_glfw_gamepad_state.bin");
    }

    @NonNull
    public static String buildJnaLibraryPath(
            @NonNull Context context,
            @Nullable File gameDirectory,
            @NonNull String baseJnaPath
    ) {
        StringBuilder builder = new StringBuilder();
        addPathList(builder, baseJnaPath);

        if (gameDirectory != null && hasControllable(gameDirectory)) {
            String version = findControllableSdlVersion(gameDirectory);

            // First choice: load SDL2 directly from the APK/private native dir.
            addPath(builder, new File(context.getApplicationInfo().nativeLibraryDir));

            // Second choice: private app cache. JNA can execute/load from here.
            addPath(builder, new File(PathManager.DIR_CACHE, "ControllableSDL/" + version));
            addPath(builder, new File(PathManager.DIR_CACHE, "controllable_natives/SDL/" + version));

            // Last choice: Controllable's normal extraction path. This is useful for
            // diagnostics, but on Android external storage can be noexec, so do not
            // rely on this as the only path.
            addPath(builder, new File(gameDirectory, "controllable_natives/SDL/" + version));
            addPath(builder, new File(gameDirectory, "controllable_natives/" + version));

            if (PathManager.DIR_NATIVE_LIB != null) {
                addPath(builder, new File(PathManager.DIR_NATIVE_LIB));
            }
        }

        return builder.toString();
    }

    private static void startLegacy4JMitigation(@NonNull Context context, @NonNull File gameDirectory) {
        if (!hasMod(gameDirectory, "legacy4j", "legacy-4j")) return;

        append("Legacy4J detected; enabling launcher SDL bridge before Minecraft starts");
        logAndroidControllers("Legacy4J");

        // Legacy4J's libsdl4j path often falls back to GLFW on Android. The GLFW
        // controller path still needs the launcher-side SDL controller backend to
        // be initialized before Legacy4J checks for controllers. Do this up-front,
        // instead of waiting for the warning after Legacy4J has already failed.
        suppressLauncherGamepadInput = true;
        MinecraftGLSurface.controllerModOwnsGamepadInput = true;
        enableGlfwGamepadMirror(context, "Legacy4J");

        if (tryEnableLauncherSdlBridge(context, "Legacy4J")) {
            scheduleSdlPoll("Legacy4J initial poll", 0L);
            scheduleSdlPoll("Legacy4J delayed poll", 750L);
            scheduleSdlPoll("Legacy4J late poll", 2500L);
        } else {
            append("Legacy4J launcher SDL bridge did not initialize; DroidBridge gamepad overlay stays suppressed so GLFW fallback can try to receive input");
        }

        showWarning(context, "Legacy4J controller compatibility mode is active.");

        if (legacy4JLogListener != null) {
            Logger.removeLogListener(legacy4JLogListener);
        }

        legacy4JLogListener = line -> {
            if (line == null) return;
            if (line.contains(TAG + ":")) return;

            if (line.contains("Added SDL Controller Mappings")) {
                append("Legacy4J SDL mappings loaded successfully");
                scheduleSdlPoll("Legacy4J mappings poll", 0L);
                scheduleSdlPoll("Legacy4J mappings delayed poll", 1000L);
                Logger.removeLogListener(legacy4JLogListener);
                legacy4JLogListener = null;
                return;
            }

            if (line.contains("SDL Game Controller failed to start")
                    || line.contains("GLFW will be used instead")
                    || line.contains("SDL3 (isXander's libsdl4j)")) {
                append("Legacy4J SDL/GLFW fallback detected; keeping launcher SDL bridge and gamepad overlay suppression active");
                scheduleSdlPoll("Legacy4J fallback poll", 0L);
                scheduleSdlPoll("Legacy4J fallback delayed poll", 1500L);
                return;
            }

            if (line.contains("Sound engine started")) {
                scheduleSdlPoll("Legacy4J sound engine poll", 0L);
                scheduleSdlPoll("Legacy4J sound engine delayed poll", 1500L);
                return;
            }

            if (line.contains("Stopping!") || line.contains("Game crashed!")) {
                Logger.removeLogListener(legacy4JLogListener);
                legacy4JLogListener = null;
            }
        };

        Logger.addLogListener(legacy4JLogListener);
    }

    private static void startControllableMitigation(@NonNull Context context, @NonNull File gameDirectory) {
        if (!hasMod(gameDirectory, "controllable")) return;

        controllableActive = true;

        // Controllable owns controller presentation. DroidBridge only provides
        // the Android SDL/GLFW state that the mod consumes.
        suppressLauncherGamepadInput = true;
        MinecraftGLSurface.controllerModOwnsGamepadInput = true;
        MinecraftGLSurface.sdlEnabled = false;
        enableGlfwGamepadMirror(context, "Controllable");
        patchControllableClientConfigForAndroid(gameDirectory);

        boolean legacyGlfwControllable = isLegacyGlfwControllable(gameDirectory);
        boolean forceSdlBridge = false;
        boolean sdlBridgeReady = false;

        if (legacyGlfwControllable) {
            MinecraftGLSurface.sdlEnabled = false;
            showWarning(context, "Controllable compatibility mode is active. Legacy GLFW controller mirror is enabled.");
        } else {
            try {
                prepareAndroidSdl2ForControllable(context, gameDirectory);
            } catch (Throwable throwable) {
                append("Failed to prepare Android SDL2 for Controllable: " + throwable);
            }

            forceSdlBridge = LauncherPreferences.isForceSdlControllerBridge(context);
            if (forceSdlBridge) {
                sdlBridgeReady = tryEnableLauncherSdlBridge(context, "Controllable");
                if (sdlBridgeReady) {
                    scheduleSdlPoll("Controllable initial delayed poll", 750L);
                    scheduleSdlPoll("Controllable initial late poll", 2500L);
                }
            }

            showWarning(context, forceSdlBridge
                    ? "Controllable compatibility mode is active. Force SDL bridge is enabled."
                    : "Controllable compatibility mode is active. Enable Force SDL bridge in settings if the controller is not detected.");
        }

        if (legacyGlfwControllable) {
            appendStatus("Controllable loaded successfully (GLFW controller bridge active)");
        } else if (forceSdlBridge && sdlBridgeReady) {
            appendStatus("Controllable loaded successfully (SDL controller bridge active)");
        } else if (forceSdlBridge) {
            appendStatus("Controllable loaded with limited compatibility (GLFW bridge active; SDL bridge failed)");
        } else {
            appendStatus("Controllable loaded successfully (GLFW bridge active; Force SDL disabled)");
        }

        if (controllableLogListener != null) {
            Logger.removeLogListener(controllableLogListener);
        }

        controllableLogListener = line -> {
            if (line == null) return;
            if (line.contains(TAG + ":")) return;

            if (line.contains("Sound engine started")) {
                scheduleSdlPoll("Controllable sound engine poll", 0L);
                scheduleSdlPoll("Controllable sound engine delayed poll", 1500L);
            } else if (line.contains("Applying gamepad mappings")
                    || line.contains("Successfully updated")
                    || line.contains("Finished downloading mappings")) {
                scheduleSdlPoll("Controllable mapping poll", 0L);
                scheduleSdlPoll("Controllable mapping delayed poll", 750L);
            } else if (line.contains("libm.so.6") || line.contains("libc.so.6")) {
                append("Controllable is still loading a desktop SDL2 native; embedded jar patch did not take effect.");
                Logger.removeLogListener(controllableLogListener);
                controllableLogListener = null;
            } else if (line.contains("java.io.File.getName()") && line.contains("file")) {
                append("Controllable hit the JNA nounpack File.getName() bug; make sure -Djna.nounpack is not forced true.");
                Logger.removeLogListener(controllableLogListener);
                controllableLogListener = null;
            } else if (line.contains("Stopping!") || line.contains("Game crashed!")) {
                Logger.removeLogListener(controllableLogListener);
                controllableLogListener = null;
            }
        };
        Logger.addLogListener(controllableLogListener);
    }

    private static boolean isLegacyGlfwControllable(@NonNull File gameDirectory) {
        File modsDir = new File(gameDirectory, "mods");
        File[] mods = modsDir.listFiles(file -> file.isFile()
                && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")
                && file.getName().toLowerCase(Locale.ROOT).contains("controllable"));
        if (mods == null || mods.length == 0) return false;

        for (File mod : mods) {
            String name = mod.getName().toLowerCase(Locale.ROOT);
            if (name.contains("1.18.2")
                    || name.contains("mc1.18.2")
                    || name.contains("0.16.")
                    || name.contains("0.17.")) {
                return true;
            }
        }
        return false;
    }

    private static void patchControllableClientConfigForAndroid(@NonNull File gameDirectory) {
        File configDir = new File(gameDirectory, "config");
        File configFile = new File(configDir, "controllable-client.toml");

        try {
            if (!configDir.exists() && !configDir.mkdirs() && !configDir.isDirectory()) {
                append("Controllable Android config patch skipped: unable to create " + configDir.getAbsolutePath());
                return;
            }

            String text = configFile.isFile() ? readTextFile(configFile) : "";
            String patched = removeLegacyStandaloneOptionsSection(text);
            // Keep Controllable's own virtual cursor enabled. Disabling this made
            // DroidBridge/vanilla mouse behavior appear to override the mod in menus.
            patched = setTomlSectionBoolean(patched, "client.options", "virtualMouse", true);
            patched = setTomlSectionBoolean(patched, "client.options", "autoSelect", true);
            // Controllable intentionally slows its virtual cursor while hovering
            // buttons, widgets, and inventory slots via hoverModifier. That feels
            // broken on Android because the user sees the cursor decelerate exactly
            // when passing over menu buttons. Keep the mod cursor enabled, but make
            // hover movement use the same speed as normal movement.
            patched = setTomlSectionDouble(patched, "client.options", "hoverModifier", 1.0d);

            if (!patched.equals(text)) {
                writeTextFile(configFile, patched);
                append("Controllable Android config patch: client.options.virtualMouse=true, autoSelect=true, hoverModifier=1.0");
            } else {
                append("Controllable Android config patch: already safe");
            }
        } catch (Throwable throwable) {
            append("Controllable Android config patch failed/non-fatal: "
                    + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    /**
     * v18-v23 created a top-level [options] block. Controllable 1.18.x actually
     * defines these keys under [client.options], so Forge discarded/corrected the
     * file and the launcher patch never reliably applied. Remove only that legacy
     * DroidBridge-created block before writing the correct section.
     */
    @NonNull
    private static String removeLegacyStandaloneOptionsSection(@NonNull String text) {
        String[] lines = text.split("\\R", -1);
        StringBuilder output = new StringBuilder(text.length());
        boolean inStandaloneOptions = false;
        boolean skippedSomething = false;

        for (String line : lines) {
            String trimmed = line.trim();
            java.util.regex.Matcher sectionMatcher = java.util.regex.Pattern
                    .compile("^\\s*\\[([^]]+)]\\s*$")
                    .matcher(line);
            if (sectionMatcher.matches()) {
                inStandaloneOptions = "options".equals(sectionMatcher.group(1).trim());
                if (inStandaloneOptions) {
                    skippedSomething = true;
                    continue;
                }
            }

            if (inStandaloneOptions) {
                if (trimmed.isEmpty()
                        || trimmed.startsWith("#")
                        || trimmed.startsWith("virtualMouse")
                        || trimmed.startsWith("autoSelect")
                        || trimmed.startsWith("hoverModifier")) {
                    skippedSomething = true;
                    continue;
                }
                // Unknown content means this was not our tiny legacy block. Keep it.
                inStandaloneOptions = false;
            }

            output.append(line).append('\n');
        }

        if (!skippedSomething) return text;
        return trimTrailingExtraNewlines(output.toString());
    }

    @NonNull
    private static String setTomlSectionBoolean(
            @NonNull String text,
            @NonNull String sectionName,
            @NonNull String key,
            boolean value
    ) {
        String[] lines = text.split("\\R", -1);
        StringBuilder output = new StringBuilder(Math.max(128, text.length() + 64));
        boolean inTargetSection = false;
        boolean sawTargetSection = false;
        boolean wroteKey = false;
        int targetSectionLineIndex = -1;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            java.util.regex.Matcher sectionMatcher = java.util.regex.Pattern
                    .compile("^\\s*\\[([^]]+)]\\s*$")
                    .matcher(line);
            if (sectionMatcher.matches()) {
                if (inTargetSection && !wroteKey) {
                    output.append(key).append(" = ").append(value).append('\n');
                    wroteKey = true;
                }
                inTargetSection = sectionName.equals(sectionMatcher.group(1).trim());
                if (inTargetSection) {
                    sawTargetSection = true;
                    targetSectionLineIndex = i;
                }
                output.append(line).append('\n');
                continue;
            }

            if (inTargetSection && isTomlKeyLine(line, key)) {
                output.append(key).append(" = ").append(value);
                String comment = extractInlineComment(line);
                if (comment != null) output.append(' ').append(comment);
                output.append('\n');
                wroteKey = true;
                continue;
            }

            output.append(line).append('\n');
        }

        if (inTargetSection && !wroteKey) {
            output.append(key).append(" = ").append(value).append('\n');
            wroteKey = true;
        }

        if (!sawTargetSection) {
            String base = trimTrailingExtraNewlines(output.toString());
            StringBuilder builder = new StringBuilder(base.length() + 64);
            builder.append(base);
            if (builder.length() > 0) builder.append("\n\n");
            builder.append('[').append(sectionName).append("]\n");
            builder.append(key).append(" = ").append(value).append('\n');
            return builder.toString();
        }

        if (targetSectionLineIndex >= 0 && !wroteKey) {
            // Defensive fallback; normally the section close/end paths write the key.
            return insertAfterSectionHeader(output.toString(), sectionName, key + " = " + value + "\n");
        }

        return trimTrailingExtraNewlines(output.toString());
    }

    @NonNull
    private static String setTomlSectionDouble(
            @NonNull String text,
            @NonNull String sectionName,
            @NonNull String key,
            double value
    ) {
        String stringValue = String.format(Locale.ROOT, "%.1f", value);
        String[] lines = text.split("\\R", -1);
        StringBuilder output = new StringBuilder(Math.max(128, text.length() + 64));
        boolean inTargetSection = false;
        boolean sawTargetSection = false;
        boolean wroteKey = false;
        int targetSectionLineIndex = -1;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            java.util.regex.Matcher sectionMatcher = java.util.regex.Pattern
                    .compile("^\\s*\\[([^]]+)]\\s*$")
                    .matcher(line);
            if (sectionMatcher.matches()) {
                if (inTargetSection && !wroteKey) {
                    output.append(key).append(" = ").append(stringValue).append('\n');
                    wroteKey = true;
                }
                inTargetSection = sectionName.equals(sectionMatcher.group(1).trim());
                if (inTargetSection) {
                    sawTargetSection = true;
                    targetSectionLineIndex = i;
                }
                output.append(line).append('\n');
                continue;
            }

            if (inTargetSection && isTomlKeyLine(line, key)) {
                output.append(key).append(" = ").append(stringValue);
                String comment = extractInlineComment(line);
                if (comment != null) output.append(' ').append(comment);
                output.append('\n');
                wroteKey = true;
                continue;
            }

            output.append(line).append('\n');
        }

        if (inTargetSection && !wroteKey) {
            output.append(key).append(" = ").append(stringValue).append('\n');
            wroteKey = true;
        }

        if (!sawTargetSection) {
            String base = trimTrailingExtraNewlines(output.toString());
            StringBuilder builder = new StringBuilder(base.length() + 64);
            builder.append(base);
            if (builder.length() > 0) builder.append("\n\n");
            builder.append('[').append(sectionName).append("]\n");
            builder.append(key).append(" = ").append(stringValue).append('\n');
            return builder.toString();
        }

        if (targetSectionLineIndex >= 0 && !wroteKey) {
            // Defensive fallback; normally the section close/end paths write the key.
            return insertAfterSectionHeader(output.toString(), sectionName, key + " = " + stringValue + "\n");
        }

        return trimTrailingExtraNewlines(output.toString());
    }

    private static boolean isTomlKeyLine(@NonNull String line, @NonNull String key) {
        return java.util.regex.Pattern
                .compile("^\\s*" + java.util.regex.Pattern.quote(key) + "\\s*=")
                .matcher(line)
                .find();
    }

    @Nullable
    private static String extractInlineComment(@NonNull String line) {
        int index = line.indexOf('#');
        return index >= 0 ? line.substring(index).trim() : null;
    }

    @NonNull
    private static String insertAfterSectionHeader(
            @NonNull String text,
            @NonNull String sectionName,
            @NonNull String insertion
    ) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^\\s*\\[" + java.util.regex.Pattern.quote(sectionName) + "]\\s*$")
                .matcher(text);
        if (!matcher.find()) return text + "\n" + insertion;
        return text.substring(0, matcher.end()) + "\n" + insertion + text.substring(matcher.end());
    }

    @NonNull
    private static String trimTrailingExtraNewlines(@NonNull String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
            end--;
        }
        return text.substring(0, end) + "\n";
    }

    @NonNull
    private static String readTextFile(@NonNull File file) throws IOException {
        return new String(readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static void writeTextFile(@NonNull File file, @NonNull String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Unable to create directory: " + parent);
        }
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void enableGlfwGamepadMirror(@NonNull Context context, @NonNull String reason) {
        mirrorAndroidGamepadToGlfw = true;
        GamepadInputController.glfwGamepadMirrorEnabled = true;
        // Mirror-only controller mods must not enable DroidBridge's own BTA/Input
        // Gamepad mapper. They only need the virtual GLFW gamepad state updated.
        if (!GamepadInputController.btaNativeControllerOwnsLauncherInput) {
            GamepadInputController.btaNativeControllerBridgeEnabled = false;
        }
        try {
            File sharedStateFile = getGlfwGamepadStateFile(context);
            System.setProperty("droidbridge.glfw.gamepad.state", sharedStateFile.getAbsolutePath());
            DroidBridgeBtaGamepad.setSharedStateFilePath(sharedStateFile.getAbsolutePath());
            append(reason + " GLFW gamepad mirror shared state=" + sharedStateFile.getAbsolutePath());
        } catch (Throwable throwable) {
            append(reason + " GLFW gamepad mirror shared state setup failed/non-fatal: "
                    + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }

        try {
            GamepadInputController.initializeGlfwGamepadMirror(context);
            append(reason + " GLFW gamepad mirror enabled without DroidBridge launcher input takeover");
        } catch (Throwable throwable) {
            append(reason + " GLFW gamepad mirror seed failed/non-fatal: "
                    + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private static boolean tryEnableLauncherSdlBridge(@NonNull Context context, @NonNull String reason) {
        try {
            File sdl2 = findAndroidSdl2Library(context);
            if (sdl2 == null || !sdl2.isFile()) {
                MinecraftGLSurface.sdlEnabled = false;
                append(reason + " Force SDL bridge not enabled: Android libSDL2.so was not found");
                return false;
            }

            // Load SDL3 if it exists because some launcher-side SDL Java glue is SDL3-based.
            // Some builds only ship SDL2, so SDL3 loading is optional.
            try {
                SDL.loadLibrary("SDL3", context);
                append(reason + " Force SDL bridge: SDL3 loaded");
            } catch (Throwable throwable) {
                append(reason + " Force SDL bridge: SDL3 not loaded/available: "
                        + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }

            try {
                SDL.loadLibrary("SDL2", context);
                append(reason + " Force SDL bridge: SDL2 loaded via SDL.loadLibrary");
            } catch (Throwable throwable) {
                append(reason + " Force SDL bridge: SDL.loadLibrary(SDL2) failed, using System.load: "
                        + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                System.load(sdl2.getAbsolutePath());
            }

            try {
                SDL.initialize();
                append(reason + " Force SDL bridge: SDL.initialize() completed");
            } catch (Throwable throwable) {
                append(reason + " Force SDL bridge: SDL.initialize() failed/non-fatal: "
                        + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }

            try {
                SDL.setupJNI();
                append(reason + " Force SDL bridge: SDL.setupJNI() completed");
            } catch (Throwable throwable) {
                append(reason + " Force SDL bridge: SDL.setupJNI() failed/non-fatal: "
                        + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }

            SDL.setContext(context);

            try {
                // This attaches SDLActivity's generic motion listener and prepares
                // Android-side controller/sensor plumbing. It is not displayed.
                new SDLSurface(context);
                append(reason + " Force SDL bridge: SDLSurface created for controller plumbing");
            } catch (Throwable throwable) {
                append(reason + " Force SDL bridge: SDLSurface creation failed/non-fatal: "
                        + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }

            try {
                Tools.SDL.initializeControllerSubsystems();
                append(reason + " Force SDL bridge: Tools.SDL.initializeControllerSubsystems() completed");
            } catch (Throwable throwable) {
                append(reason + " Force SDL bridge: controller subsystem init failed/non-fatal: "
                        + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }

            SDLControllerManager.initialize();
            MinecraftGLSurface.sdlEnabled = true;
            SDLControllerManager.pollInputDevices();
            append(reason + " Force SDL bridge initialized; launcher SDL routing enabled");
            return true;
        } catch (Throwable throwable) {
            MinecraftGLSurface.sdlEnabled = false;
            append(reason + " Force SDL bridge not enabled: "
                    + throwable.getClass().getName() + ": " + throwable.getMessage());
            return false;
        }
    }

    private static void scheduleSdlPoll(@NonNull String reason, long delayMs) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!MinecraftGLSurface.sdlEnabled) {
                append(reason + ": skipped because launcher SDL routing is disabled");
                return;
            }

            try {
                SDLControllerManager.initialize();
                SDLControllerManager.pollInputDevices();
                append(reason + ": SDLControllerManager.pollInputDevices() completed");
            } catch (Throwable throwable) {
                MinecraftGLSurface.sdlEnabled = false;
                append(reason + ": SDL poll failed, routing disabled: "
                        + throwable.getClass().getName() + ": " + throwable.getMessage());
            }
        }, delayMs);
    }

    private static void prepareAndroidSdl2ForControllable(
            @NonNull Context context,
            @NonNull File gameDirectory
    ) throws IOException {
        File androidSdl2 = findAndroidSdl2Library(context);
        if (androidSdl2 == null || !androidSdl2.isFile()) {
            append("Android libSDL2.so missing. Controllable will likely extract its desktop linux-aarch64 SDL2 and crash.");
            return;
        }

        String version = findControllableSdlVersion(gameDirectory);
        append("Preparing Controllable Android SDL2 version=" + version + " from " + androidSdl2.getAbsolutePath());

        patchControllableJarForAndroidSdl2(gameDirectory, androidSdl2);

        // Controllable normally calls SdlNativeLibraryLoader.setExtractionPath(gameDir/controllable_natives/SDL).
        copyNative(androidSdl2, new File(gameDirectory, "controllable_natives/SDL/" + version + "/libSDL2.so"));

        // Some older/forked builds use controllable_natives/<version> directly.
        copyNative(androidSdl2, new File(gameDirectory, "controllable_natives/" + version + "/libSDL2.so"));

        // Private app-cache locations are executable/loadable by JNA on Android.
        copyNative(androidSdl2, new File(PathManager.DIR_CACHE, "ControllableSDL/" + version + "/libSDL2.so"));
        copyNative(androidSdl2, new File(PathManager.DIR_CACHE, "controllable_natives/SDL/" + version + "/libSDL2.so"));
    }

    private static void patchControllableJarForAndroidSdl2(
            @NonNull File gameDirectory,
            @NonNull File androidSdl2
    ) {
        File modsDir = new File(gameDirectory, "mods");
        File[] mods = modsDir.listFiles(file -> file.isFile()
                && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")
                && file.getName().toLowerCase(Locale.ROOT).contains("controllable"));
        if (mods == null || mods.length == 0) return;

        byte[] androidSdl2Bytes;
        try {
            androidSdl2Bytes = readAllBytes(androidSdl2);
        } catch (IOException e) {
            append("Unable to read Android SDL2 for jar patch: " + e);
            return;
        }

        for (File modJar : mods) {
            try {
                if (patchOuterControllableJar(modJar, androidSdl2Bytes)) {
                    append("Patched Controllable embedded SDL2 resource: " + modJar.getAbsolutePath());
                }
            } catch (Throwable throwable) {
                append("Failed to patch Controllable jar " + modJar.getName() + ": " + throwable);
            }
        }
    }

    private static boolean patchOuterControllableJar(
            @NonNull File modJar,
            @NonNull byte[] androidSdl2Bytes
    ) throws IOException {
        File tempJar = new File(modJar.getParentFile(), modJar.getName() + ".javalauncher.tmp");
        boolean changed = false;
        boolean sawControllableSdl = false;

        try (ZipInputStream input = new ZipInputStream(new FileInputStream(modJar));
             ZipOutputStream output = new ZipOutputStream(new FileOutputStream(tempJar, false))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName();
                if (isJarSignatureEntry(name)) {
                    changed = true;
                    continue;
                }

                byte[] entryBytes = readEntryBytes(input, buffer);
                String lowerName = name.toLowerCase(Locale.ROOT);

                if (lowerName.endsWith(".jar") && lowerName.contains("controllable-sdl")) {
                    sawControllableSdl = true;
                    PatchBytesResult nested = patchNestedControllableSdlJar(entryBytes, androidSdl2Bytes);
                    if (nested.changed) {
                        changed = true;
                        entryBytes = nested.bytes;
                    }
                } else if (isControllableSdlNativeResource(lowerName)) {
                    if (!Arrays.equals(entryBytes, androidSdl2Bytes)) {
                        entryBytes = androidSdl2Bytes;
                        changed = true;
                    }
                }

                writeZipEntry(output, name, entryBytes);
            }
        }

        if (!sawControllableSdl) {
            append("Controllable jar did not contain an embedded controllable-sdl jar: " + modJar.getName());
        }

        if (!changed) {
            //noinspection ResultOfMethodCallIgnored
            tempJar.delete();
            return false;
        }

        File backup = new File(modJar.getParentFile(), modJar.getName() + ".javalauncher.bak");
        if (!backup.exists()) {
            copyFile(modJar, backup);
        }

        if (!tempJar.renameTo(modJar)) {
            copyFile(tempJar, modJar);
            //noinspection ResultOfMethodCallIgnored
            tempJar.delete();
        }
        return true;
    }

    private static PatchBytesResult patchNestedControllableSdlJar(
            @NonNull byte[] originalJar,
            @NonNull byte[] androidSdl2Bytes
    ) throws IOException {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream(Math.max(originalJar.length, androidSdl2Bytes.length));
        boolean changed = false;
        boolean replacedTarget = false;

        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(originalJar));
             ZipOutputStream output = new ZipOutputStream(outBytes)) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName();
                if (isJarSignatureEntry(name)) {
                    changed = true;
                    continue;
                }

                byte[] entryBytes = readEntryBytes(input, buffer);
                String lowerName = name.toLowerCase(Locale.ROOT);
                if (isControllableSdlNativeResource(lowerName)) {
                    replacedTarget = true;
                    if (!Arrays.equals(entryBytes, androidSdl2Bytes)) {
                        entryBytes = androidSdl2Bytes;
                        changed = true;
                    }
                }

                writeZipEntry(output, name, entryBytes);
            }

            if (!replacedTarget) {
                writeZipEntry(output, "linux-aarch64/libSDL2.so", androidSdl2Bytes);
                changed = true;
            }
        }

        return new PatchBytesResult(outBytes.toByteArray(), changed);
    }

    private static boolean isControllableSdlNativeResource(@NonNull String lowerName) {
        return lowerName.equals("linux-aarch64/libsdl2.so")
                || lowerName.equals("linux-aarch64/libsdl2-2.0.so")
                || lowerName.equals("linux-arm64/libsdl2.so")
                || lowerName.equals("linux-arm64/libsdl2-2.0.so");
    }

    private static boolean isJarSignatureEntry(@NonNull String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.startsWith("META-INF/")
                && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC"));
    }

    private static void writeZipEntry(
            @NonNull ZipOutputStream output,
            @NonNull String name,
            @NonNull byte[] bytes
    ) throws IOException {
        ZipEntry outEntry = new ZipEntry(name);
        output.putNextEntry(outEntry);
        output.write(bytes);
        output.closeEntry();
    }

    @NonNull
    private static byte[] readEntryBytes(@NonNull ZipInputStream input, @NonNull byte[] buffer) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    @NonNull
    private static byte[] readAllBytes(@NonNull File source) throws IOException {
        try (FileInputStream input = new FileInputStream(source)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(source.length(), 8 * 1024 * 1024));
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void copyFile(@NonNull File source, @NonNull File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Unable to create directory: " + parent);
        }
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination, false)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static final class PatchBytesResult {
        final byte[] bytes;
        final boolean changed;

        PatchBytesResult(@NonNull byte[] bytes, boolean changed) {
            this.bytes = bytes;
            this.changed = changed;
        }
    }

    @Nullable
    private static File findAndroidSdl2Library(@NonNull Context context) {
        String[] names = {
                "libSDL2.so",
                "libSDL2-2.0.so",
                "libSDL2_2.0.so"
        };

        File appNativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        for (String name : names) {
            File candidate = new File(appNativeDir, name);
            if (candidate.isFile()) return candidate;
        }

        if (PathManager.DIR_NATIVE_LIB != null) {
            File pathManagerNativeDir = new File(PathManager.DIR_NATIVE_LIB);
            for (String name : names) {
                File candidate = new File(pathManagerNativeDir, name);
                if (candidate.isFile()) return candidate;
            }
        }

        return null;
    }

    @NonNull
    private static String findControllableSdlVersion(@NonNull File gameDirectory) {
        File modsDir = new File(gameDirectory, "mods");
        File[] mods = modsDir.listFiles(file -> file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (mods != null) {
            for (File mod : mods) {
                String version = findControllableSdlVersionInJar(mod);
                if (version != null) return version;
            }
        }

        // 1.20.1 Controllable 0.21.x bundles controllable-sdl-2.30.12-1.1.0.jar.
        return "2.30.12";
    }

    @Nullable
    private static String findControllableSdlVersionInJar(@NonNull File jarFile) {
        try (ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(jarFile))) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase(Locale.ROOT);
                int index = name.indexOf("controllable-sdl-");
                if (index < 0 || !name.endsWith(".jar")) continue;

                String remaining = name.substring(index + "controllable-sdl-".length());
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("^(\\d+\\.\\d+\\.\\d+)")
                        .matcher(remaining);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void copyNative(@NonNull File source, @NonNull File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Unable to create directory: " + parent);
        }

        try (FileInputStream inputStream = new FileInputStream(source);
             FileOutputStream outputStream = new FileOutputStream(destination, false)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
        }

        destination.setReadable(true, false);
        destination.setExecutable(true, false);
        append("Prepared Controllable SDL2 native: " + destination.getAbsolutePath());
    }

    private static void logAndroidControllers(@NonNull String reason) {
        int count = 0;
        try {
            int[] ids = InputDevice.getDeviceIds();
            for (int id : ids) {
                InputDevice device = InputDevice.getDevice(id);
                if (device == null) continue;

                int sources = device.getSources();
                boolean isController = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                        || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                        || (sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
                if (!isController) continue;

                count++;
                append(reason + ": Android controller device id=" + id
                        + " name=" + device.getName()
                        + " descriptor=" + device.getDescriptor()
                        + " sources=0x" + Integer.toHexString(sources));
            }
        } catch (Throwable throwable) {
            append(reason + ": Android controller scan failed: " + throwable);
        }
        append(reason + ": Android controller count=" + count);
    }

    private static boolean hasMod(@NonNull File gameDirectory, @NonNull String... tokens) {
        File modsDir = new File(gameDirectory, "mods");
        File[] mods = modsDir.listFiles(file -> file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (mods == null) return false;

        for (File file : mods) {
            String name = file.getName().toLowerCase(Locale.ROOT);
            for (String token : tokens) {
                if (name.contains(token.toLowerCase(Locale.ROOT))) return true;
            }
        }
        return false;
    }

    private static void showWarning(@NonNull Context context, @NonNull String message) {
        // Keep controller compatibility messages in latestlog.txt instead of Toasts.
        // This avoids interrupting the user while still making diagnostics visible.
        if (LauncherPreferences.isShowControllerModCompatWarnings(context)) {
            append(message);
        }
    }

    private static void addPathList(@NonNull StringBuilder builder, @NonNull String pathList) {
        if (pathList.trim().isEmpty()) return;
        String[] parts = pathList.split(":");
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) continue;
            addPath(builder, new File(part.trim()));
        }
    }

    private static void addPath(@NonNull StringBuilder builder, @NonNull File dir) {
        String path = dir.getAbsolutePath();
        if (path.trim().isEmpty()) return;
        String current = builder.toString();
        if (current.equals(path) || current.startsWith(path + ":") || current.contains(":" + path + ":") || current.endsWith(":" + path)) {
            return;
        }
        if (builder.length() > 0) builder.append(':');
        builder.append(path);
    }

    private static void appendStatus(@NonNull String message) {
        appendRaw(message);
    }

    private static void append(@NonNull String message) {
        if (!isImportantCompatibilityMessage(message)
                && !Boolean.getBoolean("droidbridge.controller.compat.diagnostics")) {
            return;
        }
        appendRaw(message);
    }

    private static boolean isImportantCompatibilityMessage(@NonNull String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("failed")
                || lower.contains("failure")
                || lower.contains(" error")
                || lower.startsWith("error")
                || lower.contains(" missing")
                || lower.startsWith("missing")
                || lower.contains("unable")
                || lower.contains("still loading")
                || lower.contains("did not")
                || lower.contains("not enabled:")
                || lower.contains("hit the jna")
                || lower.contains("crash")
                || lower.contains("disabled by preference");
    }

    private static void appendRaw(@NonNull String message) {
        try {
            Logger.appendToLog(TAG + ": " + (message.endsWith("\n") ? message : message + "\n"));
        } catch (Throwable ignored) {
        }
    }
}

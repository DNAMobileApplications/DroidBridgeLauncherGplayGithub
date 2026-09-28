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

package ca.dnamobile.droidbridgelauncher.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstance;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstanceManager;
import ca.dnamobile.droidbridgelauncher.renderer.RendererInterface;
import ca.dnamobile.droidbridgelauncher.renderer.Renderers;
import ca.dnamobile.droidbridgelauncher.renderer.RendererVersionRules;
import ca.dnamobile.droidbridgelauncher.renderer.BtaRendererPolicy;
import ca.dnamobile.droidbridgelauncher.settings.GameResolutionSettings;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.settings.MemoryAllocationUtils;

public final class InstanceLaunchSettings {
    private static final String TAG = "InstanceLaunchSettings";
    private static final String PREFS = "per_instance_launch_settings";

    public static final int RAM_DEFAULT = -1;
    public static final String RUNTIME_DEFAULT = "";
    public static final String RENDERER_DEFAULT = "";
    public static final String GRAPHICS_API_INHERIT = "";
    public static final String GRAPHICS_API_DEFAULT = "default";
    public static final String GRAPHICS_API_VULKAN = "vulkan";
    public static final String GRAPHICS_API_OPENGL = "opengl";
    public static final String VULKAN_COMPAT_AUTO = "auto";
    // Passive proxy mode: observes Vulkan traffic without rewriting it.
    public static final String VULKAN_COMPAT_DIAGNOSTICS = "diagnostics";
    public static final String VULKAN_COMPAT_ATLAS_SYNC = "atlas_sync";
    public static final String VULKAN_COMPAT_ATLAS_SOURCE_SYNC = "atlas_source_sync";
    public static final String VULKAN_COMPAT_ATLAS_BUILD_INPUT_AUDIT = "atlas_build_input_audit";
    public static final String VULKAN_COMPAT_STATIC_ATLAS_LAYOUT_REPAIR = "static_atlas_layout_repair";
    // Stable persisted ID retained from earlier experiments; now used for the proven Qualcomm SpriteMatrix direct-atlas repair.
    public static final String VULKAN_COMPAT_SPRITE_UPLOAD_LAYOUT_REPAIR = "sprite_upload_layout_repair";
    public static final String VULKAN_COMPAT_SPECIAL_DRAW_SYNC = "special_draw_sync";
    // Dedicated persisted ID for the read-only entity-shader descriptor diagnostic.
    public static final String VULKAN_COMPAT_ENTITY_AUX_DIAGNOSTICS = "entity_aux_diagnostics";
    // Qualcomm-only diagnostic isolation for the exact MC 26.2 banner/painting fragment shader
    // isolated by pipeline correlation on Adreno 830. The persisted ID is retained for compatibility.
    public static final String VULKAN_COMPAT_ENTITY_SHADER_FALLBACK = "entity_shader_fallback";
    // Legacy ID from an earlier experimental normal-format slot. Kept only for migration.
    public static final String VULKAN_COMPAT_ENTITY_NORMAL_FORMAT = "entity_normal_format";
    public static final String VULKAN_COMPAT_ZERO_DIVISOR = "zero_divisor";
    public static final String VULKAN_COMPAT_SHIELD_MODEL = "shield_model";
    public static final String VULKAN_COMPAT_PUSH_DESCRIPTORS = "push_descriptors";
    public static final String VULKAN_COMPAT_PUSH_AND_MULTI_DRAW = "push_and_multi_draw";
    public static final String VULKAN_COMPAT_MAXIMUM = "maximum";
    public static final String VULKAN_COMPAT_DISABLED = "disabled";
    public static final String GC_MODE_DEFAULT = "";
    public static final String GC_MODE_ZGC = "zgc";
    public static final String GC_MODE_G1GC = "g1gc";
    public static final String RESOLUTION_MODE_INHERIT = "";
    public static final String LAUNCH_TARGET_INHERIT = "";
    public static final String LAUNCH_TARGET_REGULAR = "regular";
    public static final String LAUNCH_TARGET_LAST_WORLD = "last_world";
    public static final String LAUNCH_TARGET_SERVER = "server";

    private InstanceLaunchSettings() {
    }

    public static final class Settings {
        @NonNull public String rendererIdentifier = RENDERER_DEFAULT;
        @NonNull public String runtimeName = RUNTIME_DEFAULT;
        @NonNull public String customJvmArgs = "";
        @NonNull public String graphicsApiMode = GRAPHICS_API_INHERIT;
        @NonNull public String vulkanCompatibilityMode = VULKAN_COMPAT_AUTO;
        @NonNull public String gcMode = GC_MODE_DEFAULT;
        @NonNull public String resolutionMode = RESOLUTION_MODE_INHERIT;
        @NonNull public String launchTargetMode = LAUNCH_TARGET_INHERIT;
        public int resolutionCustomWidth = GameResolutionSettings.DEFAULT_CUSTOM_WIDTH;
        public int resolutionCustomHeight = GameResolutionSettings.DEFAULT_CUSTOM_HEIGHT;
        public int ramMb = RAM_DEFAULT;

        public boolean hasRendererOverride() {
            return !isBlank(rendererIdentifier);
        }

        public boolean hasRuntimeOverride() {
            return !isBlank(runtimeName);
        }

        public boolean hasRamOverride() {
            return ramMb > 0;
        }

        public boolean hasCustomJvmArgs() {
            return !isBlank(customJvmArgs);
        }

        public boolean hasGraphicsApiOverride() {
            return !isBlank(graphicsApiMode);
        }

        public boolean hasVulkanCompatibilityOverride() {
            return !VULKAN_COMPAT_AUTO.equals(
                    sanitizeVulkanCompatibilityMode(vulkanCompatibilityMode));
        }

        public boolean hasGcOverride() {
            return !isBlank(gcMode);
        }

        public boolean hasResolutionOverride() {
            return !isBlank(resolutionMode);
        }

        public boolean hasLaunchTargetOverride() {
            return !isBlank(launchTargetMode);
        }

        public boolean hasAnyOverride() {
            return hasRendererOverride()
                    || hasRuntimeOverride()
                    || hasRamOverride()
                    || hasCustomJvmArgs()
                    || hasGraphicsApiOverride()
                    || hasVulkanCompatibilityOverride()
                    || hasGcOverride()
                    || hasResolutionOverride()
                    || hasLaunchTargetOverride();
        }
    }

    @NonNull
    public static String resolveInstanceKey(@Nullable String instanceId, @Nullable String instanceName) {
        String value = firstNonBlank(instanceId, instanceName, "default");
        return value.trim().replace('\n', ' ').replace('\r', ' ');
    }

    @NonNull
    public static Settings load(@NonNull Context context, @NonNull String instanceKey) {
        SharedPreferences prefs = prefs(context);
        String prefix = prefix(instanceKey);
        Settings settings = new Settings();
        settings.rendererIdentifier = prefs.getString(prefix + "renderer", RENDERER_DEFAULT);
        settings.runtimeName = prefs.getString(prefix + "runtime", RUNTIME_DEFAULT);
        settings.customJvmArgs = prefs.getString(prefix + "jvm_args", "");
        settings.graphicsApiMode = sanitizeGraphicsApiMode(prefs.getString(prefix + "graphics_api_mode", GRAPHICS_API_INHERIT));
        settings.vulkanCompatibilityMode = sanitizeVulkanCompatibilityMode(
                prefs.getString(prefix + "vulkan_compatibility_mode", VULKAN_COMPAT_AUTO));
        settings.gcMode = sanitizeGcMode(prefs.getString(prefix + "gc_mode", GC_MODE_DEFAULT));
        settings.resolutionMode = sanitizeResolutionMode(prefs.getString(prefix + "resolution_mode", RESOLUTION_MODE_INHERIT));
        settings.launchTargetMode = sanitizeLaunchTargetMode(prefs.getString(prefix + "launch_target_mode", LAUNCH_TARGET_INHERIT));
        settings.resolutionCustomWidth = GameResolutionSettings.clampCustomDimension(
                prefs.getInt(prefix + "resolution_custom_width", GameResolutionSettings.DEFAULT_CUSTOM_WIDTH));
        settings.resolutionCustomHeight = GameResolutionSettings.clampCustomDimension(
                prefs.getInt(prefix + "resolution_custom_height", GameResolutionSettings.DEFAULT_CUSTOM_HEIGHT));
        settings.ramMb = prefs.getInt(prefix + "ram_mb", RAM_DEFAULT);
        if (settings.rendererIdentifier == null) settings.rendererIdentifier = RENDERER_DEFAULT;
        if (settings.runtimeName == null) settings.runtimeName = RUNTIME_DEFAULT;
        if (settings.customJvmArgs == null) settings.customJvmArgs = "";
        if (settings.graphicsApiMode == null) settings.graphicsApiMode = GRAPHICS_API_INHERIT;
        if (settings.vulkanCompatibilityMode == null) settings.vulkanCompatibilityMode = VULKAN_COMPAT_AUTO;
        if (settings.gcMode == null) settings.gcMode = GC_MODE_DEFAULT;
        if (settings.resolutionMode == null) settings.resolutionMode = RESOLUTION_MODE_INHERIT;
        if (settings.launchTargetMode == null) settings.launchTargetMode = LAUNCH_TARGET_INHERIT;
        return settings;
    }

    public static void save(@NonNull Context context, @NonNull String instanceKey, @NonNull Settings settings) {
        String prefix = prefix(instanceKey);
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putString(prefix + "renderer", safe(settings.rendererIdentifier));
        editor.putString(prefix + "runtime", safe(settings.runtimeName));
        editor.putString(prefix + "jvm_args", safe(settings.customJvmArgs));
        editor.putString(prefix + "graphics_api_mode", sanitizeGraphicsApiMode(settings.graphicsApiMode));
        editor.putString(prefix + "vulkan_compatibility_mode",
                sanitizeVulkanCompatibilityMode(settings.vulkanCompatibilityMode));
        editor.putString(prefix + "gc_mode", sanitizeGcMode(settings.gcMode));
        editor.putString(prefix + "resolution_mode", sanitizeResolutionMode(settings.resolutionMode));
        editor.putString(prefix + "launch_target_mode", sanitizeLaunchTargetMode(settings.launchTargetMode));
        editor.putInt(prefix + "resolution_custom_width",
                GameResolutionSettings.clampCustomDimension(settings.resolutionCustomWidth));
        editor.putInt(prefix + "resolution_custom_height",
                GameResolutionSettings.clampCustomDimension(settings.resolutionCustomHeight));
        editor.putInt(prefix + "ram_mb", settings.ramMb > 0 ? settings.ramMb : RAM_DEFAULT);
        boolean committed = editor.commit();
        Logging.i(TAG, "Saved per-instance settings for " + instanceKey
                + ": renderer=" + safe(settings.rendererIdentifier)
                + ", runtime=" + safe(settings.runtimeName)
                + ", ram=" + (settings.ramMb > 0 ? settings.ramMb : RAM_DEFAULT)
                + ", graphicsApi=" + sanitizeGraphicsApiMode(settings.graphicsApiMode)
                + ", vulkanCompat=" + sanitizeVulkanCompatibilityMode(settings.vulkanCompatibilityMode)
                + ", gcMode=" + sanitizeGcMode(settings.gcMode)
                + ", resolutionMode=" + sanitizeResolutionMode(settings.resolutionMode)
                + (GameResolutionSettings.MODE_CUSTOM.equals(sanitizeResolutionMode(settings.resolutionMode))
                ? "@" + GameResolutionSettings.clampCustomDimension(settings.resolutionCustomWidth)
                + "x" + GameResolutionSettings.clampCustomDimension(settings.resolutionCustomHeight)
                : "")
                + ", launchTarget=" + sanitizeLaunchTargetMode(settings.launchTargetMode)
                + ", jvmArgs=" + (!isBlank(settings.customJvmArgs))
                + ", committed=" + committed);
    }

    public static void clear(@NonNull Context context, @NonNull String instanceKey) {
        String prefix = prefix(instanceKey);
        prefs(context).edit()
                .remove(prefix + "renderer")
                .remove(prefix + "runtime")
                .remove(prefix + "jvm_args")
                .remove(prefix + "graphics_api_mode")
                .remove(prefix + "vulkan_compatibility_mode")
                .remove(prefix + "gc_mode")
                .remove(prefix + "resolution_mode")
                .remove(prefix + "launch_target_mode")
                .remove(prefix + "resolution_custom_width")
                .remove(prefix + "resolution_custom_height")
                .remove(prefix + "ram_mb")
                .commit();
        Logging.i(TAG, "Cleared per-instance settings for " + instanceKey);
    }

    @NonNull
    public static String[] getLaunchTargetModeLabels() {
        return new String[]{
                "Use global grid play action",
                "Regular launch",
                "World Save",
                "Server"
        };
    }

    public static int launchTargetModeIndex(@Nullable String launchTargetMode) {
        String mode = sanitizeLaunchTargetMode(launchTargetMode);
        if (LAUNCH_TARGET_REGULAR.equals(mode)) return 1;
        if (LAUNCH_TARGET_LAST_WORLD.equals(mode)) return 2;
        if (LAUNCH_TARGET_SERVER.equals(mode)) return 3;
        return 0;
    }

    @NonNull
    public static String launchTargetModeForIndex(int index) {
        switch (index) {
            case 1:
                return LAUNCH_TARGET_REGULAR;
            case 2:
                return LAUNCH_TARGET_LAST_WORLD;
            case 3:
                return LAUNCH_TARGET_SERVER;
            default:
                return LAUNCH_TARGET_INHERIT;
        }
    }

    @NonNull
    public static String sanitizeLaunchTargetMode(@Nullable String value) {
        if (isBlank(value)) return LAUNCH_TARGET_INHERIT;
        String mode = value.trim().toLowerCase(Locale.ROOT);
        if (LAUNCH_TARGET_REGULAR.equals(mode)
                || LAUNCH_TARGET_LAST_WORLD.equals(mode)
                || LAUNCH_TARGET_SERVER.equals(mode)) {
            return mode;
        }
        return LAUNCH_TARGET_INHERIT;
    }

    @NonNull
    public static String resolveEffectiveGridPlayIconMode(
            @NonNull Context context,
            @NonNull Settings settings
    ) {
        String perInstance = sanitizeLaunchTargetMode(settings.launchTargetMode);
        return isBlank(perInstance)
                ? LauncherPreferences.getGridPlayIconMode(context)
                : perInstance;
    }

    @NonNull
    public static String resolveEffectiveGridPlayIconMode(
            @NonNull Context context,
            @NonNull LauncherInstance instance
    ) {
        Settings settings = load(context, resolveInstanceKey(instance.getId(), instance.getName()));
        return resolveEffectiveGridPlayIconMode(context, settings);
    }

    @NonNull
    public static String[] getResolutionModeLabels() {
        return new String[]{
                "Use global setting",
                "Native",
                "1920 × 1080",
                "4:3 (best fit for device)",
                "MCSX (1280 × 960)",
                "Custom"
        };
    }

    public static int resolutionModeIndex(@Nullable String resolutionMode) {
        String mode = sanitizeResolutionMode(resolutionMode);
        if (GameResolutionSettings.MODE_NATIVE.equals(mode)) return 1;
        if (GameResolutionSettings.MODE_1920_1080.equals(mode)) return 2;
        if (GameResolutionSettings.MODE_BEST_4_3.equals(mode)) return 3;
        if (GameResolutionSettings.MODE_MCSX.equals(mode)) return 4;
        if (GameResolutionSettings.MODE_CUSTOM.equals(mode)) return 5;
        return 0;
    }

    @NonNull
    public static String resolutionModeForIndex(int index) {
        switch (index) {
            case 1:
                return GameResolutionSettings.MODE_NATIVE;
            case 2:
                return GameResolutionSettings.MODE_1920_1080;
            case 3:
                return GameResolutionSettings.MODE_BEST_4_3;
            case 4:
                return GameResolutionSettings.MODE_MCSX;
            case 5:
                return GameResolutionSettings.MODE_CUSTOM;
            default:
                return RESOLUTION_MODE_INHERIT;
        }
    }

    @NonNull
    public static String sanitizeResolutionMode(@Nullable String value) {
        if (isBlank(value)) return RESOLUTION_MODE_INHERIT;
        String raw = value.trim().toLowerCase(Locale.ROOT);
        if (GameResolutionSettings.MODE_NATIVE.equals(raw)
                || GameResolutionSettings.MODE_1920_1080.equals(raw)
                || GameResolutionSettings.MODE_BEST_4_3.equals(raw)
                || GameResolutionSettings.MODE_MCSX.equals(raw)
                || GameResolutionSettings.MODE_CUSTOM.equals(raw)) {
            return raw;
        }
        return RESOLUTION_MODE_INHERIT;
    }

    @Nullable
    public static GameResolutionSettings.Profile resolveResolutionProfileOverride(
            @NonNull Settings settings
    ) {
        String mode = sanitizeResolutionMode(settings.resolutionMode);
        if (isBlank(mode)) return null;
        return new GameResolutionSettings.Profile(
                mode,
                settings.resolutionCustomWidth,
                settings.resolutionCustomHeight
        );
    }

    @NonNull
    public static String[] getRuntimeNames() {
        return new String[]{"Internal-8", "Internal-17", "Internal-21", "Internal-25"};
    }

    @NonNull
    public static String[] getRuntimeDisplayLabels() {
        String[] runtimes = getRuntimeNames();
        String[] labels = new String[runtimes.length + 1];
        labels[0] = "Default for Minecraft version";
        for (int i = 0; i < runtimes.length; i++) {
            labels[i + 1] = RuntimeCompat.isRuntimeInstalledForDisplay(runtimes[i])
                    ? runtimes[i]
                    : runtimes[i] + " (not installed)";
        }
        return labels;
    }

    public static int runtimeIndexForName(@Nullable String runtimeName) {
        if (isBlank(runtimeName)) return 0;
        String[] runtimes = getRuntimeNames();
        for (int i = 0; i < runtimes.length; i++) {
            if (runtimes[i].equals(runtimeName)) return i + 1;
        }
        return 0;
    }

    @NonNull
    public static String runtimeNameForIndex(int index) {
        String[] runtimes = getRuntimeNames();
        int runtimeIndex = index - 1;
        if (runtimeIndex < 0 || runtimeIndex >= runtimes.length) return RUNTIME_DEFAULT;
        return runtimes[runtimeIndex];
    }

    @NonNull
    public static File resolveRuntimeDirectory(
            @NonNull Settings settings,
            @NonNull File defaultRuntimeDirectory
    ) {
        if (!settings.hasRuntimeOverride()) return defaultRuntimeDirectory;

        File override = RuntimeCompat.getRuntimeDirectory(settings.runtimeName);
        int javaMajor = RuntimeCompat.javaMajorForRuntimeName(settings.runtimeName);
        if (RuntimeCompat.isRuntimeInstalledForJava(settings.runtimeName, override, javaMajor)) {
            Logging.i(TAG, "Using per-instance Java runtime: " + settings.runtimeName + " -> " + override.getAbsolutePath());
            return override;
        }

        Logging.i(TAG, "Per-instance Java runtime is missing/broken, falling back to default: "
                + settings.runtimeName
                + " state=" + RuntimeCompat.describeRuntimeState(settings.runtimeName, override));
        return defaultRuntimeDirectory;
    }

    @NonNull
    public static LaunchPlan applyJvmOverrides(
            @NonNull Context context,
            @NonNull LaunchPlan plan,
            @NonNull Settings settings
    ) {
        if (!settings.hasRamOverride() && !settings.hasCustomJvmArgs()) return plan;

        ArrayList<String> args = new ArrayList<>(plan.getJvmArgs());

        if (settings.hasRamOverride()) {
            int ramMb = MemoryAllocationUtils.clampToAllowedRam(context, settings.ramMb);
            int startRamMb = resolveStartHeapMb(ramMb);

            purgeArg(args, "-Xms");
            purgeArg(args, "-Xmx");
            insertBeforeClasspath(args, "-Xms" + startRamMb + "M");
            insertBeforeClasspath(args, "-Xmx" + ramMb + "M");

            Logging.i(TAG, "Applied per-instance RAM: Xms=" + startRamMb
                    + " MB, Xmx=" + ramMb + " MB");
        }

        if (settings.hasCustomJvmArgs()) {
            for (String customArg : sanitizeCustomJvmArgs(tokenizeJvmArgs(settings.customJvmArgs))) {
                insertBeforeClasspath(args, customArg);
            }
            Logging.i(TAG, "Applied per-instance JVM args: " + settings.customJvmArgs);
        }

        return plan.copyWithJvmArgs(args);
    }


    @NonNull
    public static String[] getVulkanCompatibilityModeLabels() {
        return new String[]{
                "Automatic (recommended)",
                "Shield model fallback",
                "Entity shader + pipeline diagnostics (read-only)",
                "Experimental: Qualcomm atlas render-write audit v2",
                "Experimental: Qualcomm atlas source-read visibility repair",
                "Experimental: Qualcomm atlas build-input audit V2",
                "Experimental: Qualcomm static atlas shader-read layout repair",
                "Qualcomm SpriteMatrix direct atlas repair (manual)",
                "Passive Vulkan diagnostics (vanilla resources, read-only)",
                "Vulkan pipeline diagnostics (read-only)",
                "Experimental: push descriptor rewrite",
                "Experimental: push descriptors + disable multi-draw",
                "Experimental: maximum Vulkan compatibility",
                "Disabled"
        };
    }

    /**
     * Stable mode IDs corresponding one-to-one with getVulkanCompatibilityModeLabels().
     * Keep this separate from adapter positions: MaterialAutoCompleteTextView may return
     * positions from a filtered suggestion list, which are not necessarily the indexes
     * of the full labels array.
     */
    @NonNull
    public static String[] getVulkanCompatibilityModes() {
        return new String[]{
                VULKAN_COMPAT_AUTO,
                VULKAN_COMPAT_SHIELD_MODEL,
                VULKAN_COMPAT_ENTITY_AUX_DIAGNOSTICS,
                VULKAN_COMPAT_ENTITY_SHADER_FALLBACK,
                VULKAN_COMPAT_ATLAS_SOURCE_SYNC,
                VULKAN_COMPAT_ATLAS_BUILD_INPUT_AUDIT,
                VULKAN_COMPAT_STATIC_ATLAS_LAYOUT_REPAIR,
                VULKAN_COMPAT_SPRITE_UPLOAD_LAYOUT_REPAIR,
                VULKAN_COMPAT_DIAGNOSTICS,
                VULKAN_COMPAT_ZERO_DIVISOR,
                VULKAN_COMPAT_PUSH_DESCRIPTORS,
                VULKAN_COMPAT_PUSH_AND_MULTI_DRAW,
                VULKAN_COMPAT_MAXIMUM,
                VULKAN_COMPAT_DISABLED
        };
    }

    public static int vulkanCompatibilityModeIndex(@Nullable String value) {
        String mode = sanitizeVulkanCompatibilityMode(value);
        String[] modes = getVulkanCompatibilityModes();
        for (int i = 0; i < modes.length; i++) {
            if (modes[i].equals(mode)) return i;
        }
        return 0;
    }

    @NonNull
    public static String vulkanCompatibilityModeForIndex(int index) {
        String[] modes = getVulkanCompatibilityModes();
        if (index < 0 || index >= modes.length) return VULKAN_COMPAT_AUTO;
        return modes[index];
    }

    @NonNull
    public static String vulkanCompatibilityModeForLabel(@Nullable String label) {
        if (label == null) return VULKAN_COMPAT_AUTO;
        String[] labels = getVulkanCompatibilityModeLabels();
        String[] modes = getVulkanCompatibilityModes();
        for (int i = 0; i < labels.length && i < modes.length; i++) {
            if (labels[i].equals(label)) return modes[i];
        }
        return VULKAN_COMPAT_AUTO;
    }

    @NonNull
    public static String sanitizeVulkanCompatibilityMode(@Nullable String value) {
        if (value == null) return VULKAN_COMPAT_AUTO;
        String mode = value.trim().toLowerCase(Locale.ROOT);

        // Migrate the old repurposed entity-normal slot to the dedicated diagnostic ID.
        if (VULKAN_COMPAT_ENTITY_NORMAL_FORMAT.equals(mode)) {
            return VULKAN_COMPAT_ENTITY_AUX_DIAGNOSTICS;
        }

        // These two Qualcomm synchronization experiments were conclusively negative in
        // device testing. Retire saved selections to Automatic instead of silently
        // continuing an invasive workaround after an app update.
        if (VULKAN_COMPAT_ATLAS_SYNC.equals(mode)
                || VULKAN_COMPAT_SPECIAL_DRAW_SYNC.equals(mode)) {
            return VULKAN_COMPAT_AUTO;
        }

        if (VULKAN_COMPAT_SHIELD_MODEL.equals(mode)
                || VULKAN_COMPAT_ENTITY_AUX_DIAGNOSTICS.equals(mode)
                || VULKAN_COMPAT_ENTITY_SHADER_FALLBACK.equals(mode)
                || VULKAN_COMPAT_ATLAS_SOURCE_SYNC.equals(mode)
                || VULKAN_COMPAT_ATLAS_BUILD_INPUT_AUDIT.equals(mode)
                || VULKAN_COMPAT_STATIC_ATLAS_LAYOUT_REPAIR.equals(mode)
                || VULKAN_COMPAT_SPRITE_UPLOAD_LAYOUT_REPAIR.equals(mode)
                || VULKAN_COMPAT_DIAGNOSTICS.equals(mode)
                || VULKAN_COMPAT_ZERO_DIVISOR.equals(mode)
                || VULKAN_COMPAT_PUSH_DESCRIPTORS.equals(mode)
                || VULKAN_COMPAT_PUSH_AND_MULTI_DRAW.equals(mode)
                || VULKAN_COMPAT_MAXIMUM.equals(mode)
                || VULKAN_COMPAT_DISABLED.equals(mode)) {
            return mode;
        }
        return VULKAN_COMPAT_AUTO;
    }

    @NonNull
    public static String[] getGraphicsApiModeLabels() {
        return new String[]{
                "Use launcher default",
                "Default",
                "Use System Vulkan Driver",
                "Use OpenGL"
        };
    }

    public static int graphicsApiModeIndex(@Nullable String graphicsApiMode) {
        String mode = sanitizeGraphicsApiMode(graphicsApiMode);
        if (GRAPHICS_API_DEFAULT.equals(mode)) return 1;
        if (GRAPHICS_API_VULKAN.equals(mode)) return 2;
        if (GRAPHICS_API_OPENGL.equals(mode)) return 3;
        return 0;
    }

    @NonNull
    public static String graphicsApiModeForIndex(int index) {
        switch (index) {
            case 1:
                return GRAPHICS_API_DEFAULT;
            case 2:
                return GRAPHICS_API_VULKAN;
            case 3:
                return GRAPHICS_API_OPENGL;
            default:
                return GRAPHICS_API_INHERIT;
        }
    }

    /**
     * Resolves the visible Graphics API label back to its stable persisted mode.
     * MaterialAutoCompleteTextView may report an item position from a filtered
     * adapter, so callers must not treat that position as the full-list index.
     */
    @NonNull
    public static String graphicsApiModeForLabel(@Nullable String label) {
        if (label == null) return GRAPHICS_API_INHERIT;
        String[] labels = getGraphicsApiModeLabels();
        for (int i = 0; i < labels.length; i++) {
            if (labels[i].equals(label)) return graphicsApiModeForIndex(i);
        }
        return GRAPHICS_API_INHERIT;
    }

    @NonNull
    public static String resolveEffectiveGraphicsApiMode(
            @NonNull Context context,
            @NonNull Settings settings
    ) {
        /*
         * An explicit per-instance Graphics API choice is authoritative. This must be
         * evaluated before the global launcher switches; otherwise stale global state
         * can silently replace the API the user selected for this instance.
         */
        String perInstance = sanitizeGraphicsApiMode(settings.graphicsApiMode);
        if (GRAPHICS_API_VULKAN.equals(perInstance)) return GRAPHICS_API_VULKAN;
        if (GRAPHICS_API_OPENGL.equals(perInstance)) return GRAPHICS_API_OPENGL;

        /*
         * "Default" intentionally remains DroidBridge's safe Minecraft backend.
         * "Use launcher default" falls through to the mutually-exclusive global mode.
         */
        if (GRAPHICS_API_DEFAULT.equals(perInstance)) return GRAPHICS_API_OPENGL;

        if (LauncherPreferences.isUseSystemVulkanDriver(context)) {
            return GRAPHICS_API_VULKAN;
        }
        if (LauncherPreferences.isUseOpenGlForMinecraft26Plus(context)) {
            return GRAPHICS_API_OPENGL;
        }
        return GRAPHICS_API_OPENGL;
    }

    public static boolean resolveEffectiveSystemVulkanDriver(
            @NonNull Context context,
            @NonNull Settings settings
    ) {
        return GRAPHICS_API_VULKAN.equals(resolveEffectiveGraphicsApiMode(context, settings));
    }

    public static boolean resolveEffectiveSystemVulkanDriverForLaunch(
            @NonNull Context context,
            @Nullable String versionId
    ) {
        return resolveEffectiveSystemVulkanDriverForLaunch(context, versionId, null);
    }

    public static boolean resolveEffectiveSystemVulkanDriverForLaunch(
            @NonNull Context context,
            @Nullable String versionId,
            @Nullable String explicitInstanceKey
    ) {
        Settings settings = loadForLaunch(context, versionId, explicitInstanceKey);
        return resolveEffectiveSystemVulkanDriver(context, settings);
    }

    @NonNull
    public static Settings loadForLaunch(
            @NonNull Context context,
            @Nullable String versionId
    ) {
        return loadForLaunch(context, versionId, null);
    }

    /**
     * Loads launch overrides using the stable instance preference key supplied by the
     * screen that started GameActivity. The visible name/base-version token remains a
     * fallback for old shortcuts and external launch intents.
     */
    @NonNull
    public static Settings loadForLaunch(
            @NonNull Context context,
            @Nullable String versionId,
            @Nullable String explicitInstanceKey
    ) {
        if (!isBlank(explicitInstanceKey)) {
            String key = resolveInstanceKey(explicitInstanceKey, explicitInstanceKey);
            Logging.i(TAG, "Loading per-instance settings using explicit launch key=" + key
                    + " versionId=" + safe(versionId));
            return load(context, key);
        }

        LauncherInstance instance = null;
        try {
            if (!isBlank(versionId)) {
                instance = LauncherInstanceManager.findByNameOrId(context, versionId);
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to resolve instance settings for launch id=" + safe(versionId), throwable);
        }

        String key = resolveInstanceKey(
                instance != null ? instance.getId() : versionId,
                instance != null ? instance.getName() : versionId
        );
        Logging.i(TAG, "Loading per-instance settings using legacy launch key=" + key
                + " versionId=" + safe(versionId));
        return load(context, key);
    }

    /**
     * Resolves the renderer that must be used before native graphics libraries are loaded.
     * Per-instance selection wins over the launcher default. Legacy BTA remains
     * locked to Krypton, while BTA 8+ respects the selected renderer.
     */
    @NonNull
    public static RendererInterface resolveEffectiveRendererForLaunch(
            @NonNull Context context,
            @Nullable String versionOrInstanceId
    ) {
        return resolveEffectiveRendererForLaunch(context, versionOrInstanceId, null);
    }

    /**
     * Resolves the renderer from the exact instance settings key before any native
     * graphics library is loaded. This prevents display names and shared base-version
     * IDs from accidentally selecting the launcher-wide renderer.
     */
    @NonNull
    public static RendererInterface resolveEffectiveRendererForLaunch(
            @NonNull Context context,
            @Nullable String versionOrInstanceId,
            @Nullable String explicitInstanceKey
    ) {
        LauncherInstance instance = null;
        try {
            if (!isBlank(versionOrInstanceId)) {
                instance = LauncherInstanceManager.findByNameOrId(context, versionOrInstanceId);
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to resolve renderer instance for " + safe(versionOrInstanceId), throwable);
        }

        String key;
        if (!isBlank(explicitInstanceKey)) {
            key = resolveInstanceKey(explicitInstanceKey, explicitInstanceKey);
        } else {
            key = resolveInstanceKey(
                    instance != null ? instance.getId() : versionOrInstanceId,
                    instance != null ? instance.getName() : versionOrInstanceId
            );
        }
        Settings settings = load(context, key);

        RendererInterface renderer = null;
        if (settings.hasRendererOverride()) {
            renderer = Renderers.findRenderer(context, settings.rendererIdentifier);
            if (renderer == null) {
                Logging.i(TAG, "Per-instance renderer is unavailable for " + key
                        + "; falling back to launcher default: " + settings.rendererIdentifier);
            } else {
                Logging.i(TAG, "Resolved per-instance renderer key=" + key
                        + " renderer=" + renderer.getRendererName()
                        + " identifier=" + settings.rendererIdentifier);
            }
        }
        if (renderer == null) {
            String minecraftVersion = instance != null
                    ? instance.getMinecraftVersionId()
                    : versionOrInstanceId;
            renderer = RendererVersionRules.resolveRenderer(context, minecraftVersion);
            if (renderer != null) {
                Logging.i(TAG, "Using version-specific renderer default for key=" + key
                        + " minecraft=" + safe(minecraftVersion)
                        + " renderer=" + renderer.getRendererName());
            }
        }
        if (renderer == null) {
            renderer = Renderers.getSelectedRenderer(context);
            Logging.i(TAG, "Using launcher default renderer for key=" + key
                    + " renderer=" + renderer.getRendererName());
        }
        return BtaRendererPolicy.resolveRendererForLaunch(context, versionOrInstanceId, instance, renderer);
    }

    @NonNull
    public static String describeRendererChoice(@Nullable RendererInterface renderer) {
        if (renderer == null) return "Default launcher renderer";
        return renderer.getRendererName() + (renderer.isExternalPlugin() ? "  •  Plugin" : "");
    }

    private static void purgeArg(@NonNull ArrayList<String> args, @NonNull String prefix) {
        args.removeIf(arg -> arg != null && arg.startsWith(prefix));
    }

    private static int resolveStartHeapMb(int maxHeapMb) {
        if (maxHeapMb <= 0) return 512;

        // Android needs native/GPU headroom. Xmx is the real cap,
        // while Xms should stay small so startup does not pre-commit everything.
        int quarter = maxHeapMb / 4;
        return Math.min(768, Math.max(512, quarter));
    }

    private static void insertBeforeClasspath(@NonNull ArrayList<String> args, @NonNull String value) {
        if (isBlank(value)) return;
        int index = findClasspathIndex(args);
        if (index < 0) index = args.size();
        args.add(index, value);
    }

    private static int findClasspathIndex(@NonNull List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if ("-cp".equals(arg) || "-classpath".equals(arg) || "--class-path".equals(arg)) {
                return i;
            }
        }
        return -1;
    }

    @NonNull
    private static ArrayList<String> sanitizeCustomJvmArgs(@NonNull ArrayList<String> args) {
        ArrayList<String> cleaned = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (isBlank(arg)) continue;

            // The launcher owns classpath and memory. Use the dialog RAM slider for memory.
            if ("-cp".equals(arg) || "-classpath".equals(arg) || "--class-path".equals(arg)) {
                i++; // skip classpath value too
                continue;
            }
            if (arg.startsWith("-Xms") || arg.startsWith("-Xmx")) {
                continue;
            }
            if ("java".equals(arg) || arg.endsWith("/java") || arg.endsWith("\\java.exe")) {
                continue;
            }
            cleaned.add(arg);
        }
        return cleaned;
    }

    @NonNull
    public static ArrayList<String> tokenizeJvmArgs(@Nullable String text) {
        ArrayList<String> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return result;

        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaping = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (escaping) {
                current.append(c);
                escaping = false;
                continue;
            }

            if (c == '\\') {
                escaping = true;
                continue;
            }

            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }

            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }

            if (Character.isWhitespace(c) && !inSingleQuote && !inDoubleQuote) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }

            current.append(c);
        }

        if (escaping) current.append('\\');
        if (current.length() > 0) result.add(current.toString());
        return result;
    }


    @NonNull
    public static String sanitizeGcMode(@Nullable String value) {
        if (value == null) return GC_MODE_DEFAULT;
        String mode = value.trim().toLowerCase(Locale.ROOT);
        if (GC_MODE_ZGC.equals(mode) || GC_MODE_G1GC.equals(mode)) {
            return mode;
        }
        return GC_MODE_DEFAULT;
    }

    @NonNull
    private static String sanitizeGraphicsApiMode(@Nullable String value) {
        if (value == null) return GRAPHICS_API_INHERIT;
        String mode = value.trim().toLowerCase(Locale.ROOT);
        if (GRAPHICS_API_DEFAULT.equals(mode)
                || GRAPHICS_API_VULKAN.equals(mode)
                || GRAPHICS_API_OPENGL.equals(mode)) {
            return mode;
        }
        return GRAPHICS_API_INHERIT;
    }

    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @NonNull
    private static String prefix(@NonNull String instanceKey) {
        return "instance." + sanitizeKey(instanceKey) + ".";
    }

    @NonNull
    private static String sanitizeKey(@NonNull String key) {
        String trimmed = key.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) return "default";
        return trimmed.replaceAll("[^a-z0-9._-]", "_");
    }

    @NonNull
    private static String firstNonBlank(@Nullable String... values) {
        if (values != null) {
            for (String value : values) {
                if (!isBlank(value)) return value.trim();
            }
        }
        return "";
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}

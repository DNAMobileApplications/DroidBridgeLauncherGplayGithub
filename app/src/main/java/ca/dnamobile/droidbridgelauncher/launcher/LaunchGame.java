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
import android.content.pm.PackageInfo;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.modcompat.ControlifySDL;
import ca.dnamobile.droidbridgelauncher.modcompat.DistantHorizonsIrisConfigMitigation;
import ca.dnamobile.droidbridgelauncher.modcompat.DistantHorizonsZstdCompat;
import ca.dnamobile.droidbridgelauncher.modcompat.DistantHorizonsSqliteCompat;
import ca.dnamobile.droidbridgelauncher.modcompat.AndroidModpackCrashMitigation;
import ca.dnamobile.droidbridgelauncher.modcompat.ControllerModCompat;
import ca.dnamobile.droidbridgelauncher.modcompat.TouchControllerModCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;

import ca.dnamobile.droidbridgelauncher.modcompat.MethodInjectorAgentInstaller;
import ca.dnamobile.droidbridgelauncher.modcompat.NativeMesaRendererSafetyProfile;
import ca.dnamobile.droidbridgelauncher.modcompat.NativeMesaSodiumExtraMixinMitigation;
import ca.dnamobile.droidbridgelauncher.modcompat.SodiumMobileGluesShaderPatch;
import ca.dnamobile.droidbridgelauncher.data.AccountStore;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.logs.LauncherLogManager;
import ca.dnamobile.droidbridgelauncher.logs.ForgeNeoForgeModList;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstance;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstanceManager;
import ca.dnamobile.droidbridgelauncher.modcompat.VulkanModConfigMitigation;
import ca.dnamobile.droidbridgelauncher.modcompat.VulkanModLwjglMitigation;
import ca.dnamobile.droidbridgelauncher.modcompat.VulkanMod262SurfaceMitigation;
import ca.dnamobile.droidbridgelauncher.modcompat.VulkanMod262QueueMitigation;
import ca.dnamobile.droidbridgelauncher.modcompat.NotEnoughVulkanMonitorMitigation;
import ca.dnamobile.droidbridgelauncher.modcompat.PodiumAutoDisableMitigation;
import ca.dnamobile.droidbridgelauncher.modcompat.PreLaunchModScan;
import ca.dnamobile.droidbridgelauncher.modcompat.VoxyCompat;
import ca.dnamobile.droidbridgelauncher.modcompat.ReplayModNeoForgeMixinMitigation;
import ca.dnamobile.droidbridgelauncher.renderer.BtaRendererPolicy;
import ca.dnamobile.droidbridgelauncher.renderer.Driver;
import ca.dnamobile.droidbridgelauncher.renderer.DriverPluginManager;
import ca.dnamobile.droidbridgelauncher.renderer.MesaZinkTurnipDriver;
import ca.dnamobile.droidbridgelauncher.renderer.MobileGluesConfigHelper;
import ca.dnamobile.droidbridgelauncher.renderer.RendererInterface;
import ca.dnamobile.droidbridgelauncher.renderer.Renderers;
import ca.dnamobile.droidbridgelauncher.renderer.RendererVersionRules;
import ca.dnamobile.droidbridgelauncher.security.LauncherSecurity;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.settings.GameResolutionSettings;
import ca.dnamobile.droidbridgelauncher.ui.version.CleanroomSupport;
import ca.dnamobile.droidbridgelauncher.ui.version.MinecraftVersionInstaller;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;
import ca.dnamobile.droidbridgelauncher.runtime.Architecture;
import org.json.JSONArray;
import org.json.JSONObject;

// Main Launch arguments to help run the game
public final class LaunchGame {
    private static final String TAG = "LaunchGame";
    private static final AtomicBoolean IS_LAUNCHING = new AtomicBoolean(false);

    private LaunchGame() {
    }

    public interface StatusListener {
        void onStatus(@NonNull String status);
    }

    public static void resetLaunchState() {
        IS_LAUNCHING.set(false);
    }

    public static int runGame(
            @NonNull Context context,
            @NonNull String versionId,
            @Nullable AccountStore.Account account,
            int width,
            int height,
            @Nullable StatusListener listener
    ) throws Exception {
        return runGame(context, versionId, null, account, width, height, null, null, listener);
    }

    public static int runGame(
            @NonNull Context context,
            @NonNull String versionId,
            @Nullable AccountStore.Account account,
            int width,
            int height,
            @Nullable String quickPlayWorldName,
            @Nullable StatusListener listener
    ) throws Exception {
        return runGame(context, versionId, null, account, width, height, quickPlayWorldName, null, listener);
    }

    public static int runGame(
            @NonNull Context context,
            @NonNull String versionId,
            @Nullable AccountStore.Account account,
            int width,
            int height,
            @Nullable String quickPlayWorldName,
            @Nullable String quickPlayServerAddress,
            @Nullable StatusListener listener
    ) throws Exception {
        return runGame(
                context,
                versionId,
                null,
                account,
                width,
                height,
                quickPlayWorldName,
                quickPlayServerAddress,
                listener
        );
    }

    public static int runGame(
            @NonNull Context context,
            @NonNull String versionId,
            @Nullable String explicitInstanceSettingsKey,
            @Nullable AccountStore.Account account,
            int width,
            int height,
            @Nullable String quickPlayWorldName,
            @Nullable String quickPlayServerAddress,
            @Nullable StatusListener listener
    ) throws Exception {
        if (!IS_LAUNCHING.compareAndSet(false, true)) {
            safeAppendLog("Warning: Launch request ignored because another launch is already in progress.");
            return -1000;
        }

        try {
            PathManager.initContextConstants(context);
            LauncherSecurity.requireOfficialBuildAndMicrosoftSession(context, account, "launch Minecraft");
            notify(listener, "Preparing launch for " + versionId + "...");

            LauncherInstance instance = LauncherInstanceManager.findByNameOrId(context, versionId);
            String launchVersionId = instance != null ? instance.getBaseVersionId() : versionId;
            File gameDirectory = instance != null ? instance.getGameDirectory() : new File(PathManager.DIR_MINECRAFT_HOME);

            if (instance != null) {
                File instanceLauncherHome = PathManager.inferLauncherHomeFromGameDirectory(gameDirectory);
                PathManager.initContextConstants(context, instanceLauncherHome);
                gameDirectory = instance.getGameDirectory();
            }

            LauncherLogManager.beginLatestLog(context, versionId);
            appendLaunchHeader(context, versionId, launchVersionId, instance, account);

            String fallbackInstanceSettingsKey = InstanceLaunchSettings.resolveInstanceKey(
                    instance != null ? instance.getId() : versionId,
                    instance != null ? instance.getName() : versionId
            );
            String instanceSettingsKey = InstanceLaunchSettings.resolveInstanceKey(
                    explicitInstanceSettingsKey,
                    fallbackInstanceSettingsKey
            );
            InstanceLaunchSettings.Settings perInstanceSettings =
                    InstanceLaunchSettings.load(context, instanceSettingsKey);
            Logging.i(TAG, "Instance settings key=" + instanceSettingsKey
                    + ", explicit=" + (explicitInstanceSettingsKey != null
                    && !explicitInstanceSettingsKey.trim().isEmpty()));

            String effectiveGraphicsApiMode =
                    InstanceLaunchSettings.resolveEffectiveGraphicsApiMode(context, perInstanceSettings);
            boolean effectiveSystemVulkan =
                    InstanceLaunchSettings.resolveEffectiveSystemVulkanDriver(context, perInstanceSettings);

            ensureInstalled(launchVersionId);
            PreLaunchModScan modScan = PreLaunchModScan.scan(gameDirectory);
            Renderers.reload(context);
            RendererInterface renderer = resolveRendererForLaunch(
                    context,
                    versionId,
                    launchVersionId,
                    instance,
                    perInstanceSettings
            );
            renderer = VoxyCompat.selectRendererForLaunch(context, renderer, modScan);

            JSONObject versionJson = readVersionJson(launchVersionId);
            boolean vulkanMod262Compatibility =
                    modScan.hasVulkanMod
                            && effectiveSystemVulkan
                            && GraphicsBackendHelper.isMinecraft262(
                                    launchVersionId,
                                    versionJson
                            );
            boolean skipVanillaGraphicsApiOverride =
                    modScan.hasVulkanMod
                            && GraphicsBackendHelper.isMinecraft262(
                                    launchVersionId,
                                    versionJson
                            );

            int targetJava = resolveTargetJava(launchVersionId, versionJson);
            File defaultRuntime = resolveRuntimeDirectory(targetJava);
            File runtime = InstanceLaunchSettings.resolveRuntimeDirectory(perInstanceSettings, defaultRuntime);
            int selectedRuntimeJava = RuntimeCompat.javaMajorForRuntimeName(runtime.getName());
            if (selectedRuntimeJava > 0 && selectedRuntimeJava < targetJava) {
                safeAppendLog("Warning: Ignoring incompatible per-instance Java runtime "
                        + runtime.getName() + "; Java " + targetJava + " is required by "
                        + launchVersionId + ".");
                runtime = defaultRuntime;
            }

            safeAppendSection("Launch configuration");
            appendRendererSettingsSummary(
                    context,
                    renderer,
                    perInstanceSettings,
                    effectiveGraphicsApiMode,
                    effectiveSystemVulkan,
                    width,
                    height
            );
            if (skipVanillaGraphicsApiOverride) {
                safeAppendLog("VulkanMod 26.2 detected: leaving Minecraft's Graphics API option "
                        + "untouched; VulkanMod compatibility manages its own backend"
                        + (effectiveSystemVulkan ? " with system Vulkan enabled" : ""));
            }
            if (effectiveSystemVulkan) {
                int vulkanCompatIndex = InstanceLaunchSettings.vulkanCompatibilityModeIndex(
                        perInstanceSettings.vulkanCompatibilityMode);
                String[] vulkanCompatLabels = InstanceLaunchSettings.getVulkanCompatibilityModeLabels();
                String vulkanCompatLabel = vulkanCompatIndex >= 0 && vulkanCompatIndex < vulkanCompatLabels.length
                        ? vulkanCompatLabels[vulkanCompatIndex]
                        : InstanceLaunchSettings.sanitizeVulkanCompatibilityMode(
                                perInstanceSettings.vulkanCompatibilityMode);
                safeAppendLog("System Vulkan compatibility: "
                        + vulkanCompatLabel
                        + (perInstanceSettings.hasVulkanCompatibilityOverride()
                        ? " (per-instance override)" : ""));
            }
            safeAppendLog("Java: " + targetJava + " / " + runtime.getName()
                    + (perInstanceSettings.hasRuntimeOverride() ? " (per-instance)" : ""));
            if (perInstanceSettings.hasAnyOverride()) {
                safeAppendLog("Instance overrides: active");
            }

            appendForgeNeoForgeModSummary(gameDirectory, launchVersionId, versionJson);

            Logging.i(TAG, "Runtime path=" + runtime.getAbsolutePath()
                    + ", exists=" + runtime.isDirectory());

            GraphicsBackendHelper.applyBeforeLaunch(
                    context,
                    launchVersionId,
                    versionJson,
                    gameDirectory,
                    perInstanceSettings,
                    modScan.hasVulkanMod
            );

            if (modScan.hasCompatibilityWork()) {
                safeAppendSection("Compatibility");
                String detectedMods = modScan.describeCompatibilityMods();
                if (!detectedMods.isEmpty()) {
                    safeAppendLog("Detected: " + detectedMods);
                }
                if (modScan.hasVoxy) {
                    if (VoxyCompat.isUsingRequiredRenderer(renderer)) {
                        safeAppendLog("Voxy: Kopper Zink desktop OpenGL compatibility enabled");
                    } else {
                        safeAppendLog("Warning: Voxy requires Kopper Zink on DroidBridge, but it is unavailable on this device");
                    }
                    safeAppendLog("Voxy: Android native fallback enabled (JavaSafe LZ4 + session memory storage)");
                }
            }

            runPreLaunchModMitigations(
                    context,
                    gameDirectory,
                    launchVersionId,
                    renderer,
                    modScan,
                    vulkanMod262Compatibility,
                    effectiveGraphicsApiMode
            );

            String launchVulkanCompatibilityMode = perInstanceSettings.vulkanCompatibilityMode;
            if (vulkanMod262Compatibility
                    && InstanceLaunchSettings.VULKAN_COMPAT_AUTO.equals(
                            InstanceLaunchSettings.sanitizeVulkanCompatibilityMode(
                                    launchVulkanCompatibilityMode))) {
                // VulkanMod 26.2 has its own Android/System-Vulkan compatibility path.
                // Keep DroidBridge's vanilla Minecraft SpriteMatrix repair out of that
                // automatic path unless the user explicitly selects a manual mode.
                launchVulkanCompatibilityMode = InstanceLaunchSettings.VULKAN_COMPAT_DISABLED;
                safeAppendLog("System Vulkan compatibility: automatic vanilla 26.2 repair "
                        + "bypassed for VulkanMod");
            }

            notify(listener, "Building launch arguments...");
            LaunchPlan plan = new JavaLaunchBuilder(context, launchVersionId, account, width, height)
                    .setRuntimeDirectory(runtime)
                    .setGameDirectory(gameDirectory)
                    .setRenderer(renderer)
                    .setUseSystemVulkanDriver(effectiveSystemVulkan)
                    .setVulkanCompatibilityMode(launchVulkanCompatibilityMode)
                    .build();

            plan = applyDistantHorizonsWorldgenShutdownRuntimeCompatibility(
                    plan,
                    modScan
            );

            plan = applyDistantHorizonsIrisVulkanRuntimeCompatibility(
                    plan,
                    modScan,
                    effectiveGraphicsApiMode
            );

            plan = appendQuickPlayArgs(
                    plan,
                    quickPlayWorldName,
                    quickPlayServerAddress,
                    launchVersionId,
                    versionJson,
                    gameDirectory,
                    listener
            );
            plan = appendMethodInjectorAgentIfNeeded(context, plan, targetJava, gameDirectory);
            plan = InstanceLaunchSettings.applyJvmOverrides(context, plan, perInstanceSettings);
            plan = VoxyCompat.applyLaunchPlanCompatibility(plan, modScan);
            plan = ControllerModCompat.applyLaunchPlanCompatibility(context, plan, gameDirectory);

            boolean dhDetectedForGc = modScan.hasDistantHorizons
                    || DistantHorizonsGcMitigation.hasDistantHorizons(gameDirectory);
            if (dhDetectedForGc) {
                if (!modScan.hasDistantHorizons) {
                    safeAppendLog("Distant Horizons: detected by jar class marker fallback");
                }
                DistantHorizonsGcMitigation.Result dhGcResult = DistantHorizonsGcMitigation.applyIfNeeded(
                        context,
                        plan,
                        perInstanceSettings
                );
                plan = dhGcResult.plan;
                if (dhGcResult.applied) {
                    String selectedMode =
                            DistantHorizonsGcMitigation.displayGcMode(dhGcResult.effectiveGcMode);
                    String selectionSource = InstanceLaunchSettings.GC_MODE_DEFAULT.equals(
                            InstanceLaunchSettings.sanitizeGcMode(perInstanceSettings.gcMode)
                    ) ? "automatic" : "per-instance";
                    safeAppendLog("Distant Horizons: GC=" + selectedMode
                            + " (" + selectionSource + ")");
                }
                for (String message : dhGcResult.messages) {
                    if (message.startsWith("ZGC probe") || message.startsWith("ZGC external")) {
                        safeAppendLog("Distant Horizons GC: " + message);
                    } else if (message.startsWith("Failed:") || message.startsWith("Fell back")) {
                        safeAppendLog("Warning: Distant Horizons GC: " + message);
                    }
                }
            }

            plan = DroidBridgeOpenGlProxyArgs.applyIfNeeded(plan, renderer);

            notify(listener, "Checking controller compatibility...");
            ControlifySDL.initializeIfNeeded(context, gameDirectory);
            ControllerModCompat.prepare(context, gameDirectory);
            TouchControllerModCompat.prepare(context, gameDirectory);

            safeAppendSection("Starting Minecraft");
            safeAppendLog("Main class: " + plan.getMainClass());
            appendNetworkSummary(plan);
            Logging.i(TAG, "LWJGL native directory="
                    + plan.getLwjglNativeDirectory().getAbsolutePath());

            return JavaGameLauncher.launchPreparedPlan(
                    context,
                    plan,
                    renderer,
                    listener == null ? null : listener::onStatus
            );
        } catch (Throwable throwable) {
            IS_LAUNCHING.set(false);
            safeAppendLog("Error: Launch failed: " + throwable.getClass().getSimpleName()
                    + (throwable.getMessage() == null || throwable.getMessage().trim().isEmpty()
                    ? ""
                    : ": " + throwable.getMessage().trim()));
            Logging.e(TAG, "Launch failed", throwable);
            if (throwable instanceof Exception) throw (Exception) throwable;
            throw new RuntimeException(throwable);
        } finally {
            LauncherLogManager.preserveLatestLogIfEnabled(context, versionId);
        }
    }

    @NonNull
    private static LaunchPlan appendMethodInjectorAgentIfNeeded(
            @NonNull Context context,
            @NonNull LaunchPlan plan,
            int targetJava,
            @NonNull File gameDirectory
    ) {
        /*
         * The methods_injector_agent is required for Veil/ImGui compatibility,
         * but the current agent is compiled for Java 17 bytecode. Java 8 can only
         * load class file version 52.0, so injecting this agent into legacy
         * clients such as b1.7.3 or 1.8.9 hard-crashes before Minecraft starts.
         *
         * Keep the agent for modern Veil launches, but never attach it to a Java 8
         * JVM. This fixes old versions without removing the Veil fix.
         */
        if (targetJava < 17) {
            return stripMethodInjectorAgent(plan);
        }

        if (!hasVeilOrImguiMod(gameDirectory)) {
            return stripMethodInjectorAgent(plan);
        }

        File agentJar = MethodInjectorAgentInstaller.install(context);
        if (agentJar == null) {
            safeAppendLog("Warning: Veil/ImGui compatibility bridge unavailable (agent jar missing).");
            return stripMethodInjectorAgent(plan);
        }

        ArrayList<String> compatibilityArgs = buildVeilImguiCompatibilityJvmArgs(context, agentJar);

        try {
            LaunchPlan patchedPlan = prependJvmArgsToLaunchPlan(plan, compatibilityArgs);
            safeAppendLog("Veil/ImGui: compatibility bridge enabled");
            Logging.i(TAG, "Veil/ImGui compatibility JVM args=" + compatibilityArgs);
            return patchedPlan;
        } catch (Throwable throwable) {
            safeAppendLog("Warning: Veil/ImGui compatibility bridge disabled: "
                    + throwable.getClass().getSimpleName());
            Logging.e(TAG, "Unable to add Veil/ImGui compatibility JVM args", throwable);
            return stripMethodInjectorAgent(plan);
        }
    }

    @NonNull
    private static LaunchPlan stripMethodInjectorAgent(@NonNull LaunchPlan plan) {
        try {
            Object currentArgsObject = findJvmArgsObject(plan);
            if (!(currentArgsObject instanceof List)) {
                return plan;
            }

            ArrayList<String> cleanedArgs = new ArrayList<>();
            boolean changed = false;
            for (Object value : (List<?>) currentArgsObject) {
                if (value instanceof String && isVeilImguiCompatibilityArg((String) value)) {
                    changed = true;
                    continue;
                }
                if (value instanceof String) {
                    cleanedArgs.add((String) value);
                }
            }

            if (!changed) return plan;

            LaunchPlan copied = copyLaunchPlanWithJvmArgs(plan, cleanedArgs);
            if (copied != null) return copied;
            if (replaceJvmArgsField(plan, cleanedArgs)) return plan;

            @SuppressWarnings("unchecked")
            List<String> mutableArgs = (List<String>) currentArgsObject;
            removeExistingVeilImguiCompatibilityArgs(mutableArgs);
        } catch (Throwable throwable) {
            safeAppendLog("Warning: Unable to remove stale Veil/ImGui compatibility arguments.");
            Logging.e(TAG, "Unable to strip Veil/ImGui compatibility JVM args", throwable);
        }
        return plan;
    }

    private static boolean hasVeilOrImguiMod(@NonNull File gameDirectory) {
        ArrayList<File> modDirectories = new ArrayList<>();
        addModDirectory(modDirectories, new File(gameDirectory, "mods"));

        File instanceRoot = gameDirectory.getParentFile();
        if (instanceRoot != null) {
            addModDirectory(modDirectories, new File(instanceRoot, "mods"));
        }

        if (PathManager.DIR_MINECRAFT_HOME != null && !PathManager.DIR_MINECRAFT_HOME.trim().isEmpty()) {
            addModDirectory(modDirectories, new File(PathManager.DIR_MINECRAFT_HOME, "mods"));
        }

        for (File modsDir : modDirectories) {
            File[] files = modsDir.listFiles((dir, name) -> {
                String lower = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
                return lower.endsWith(".jar");
            });

            if (files == null) continue;

            java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File file : files) {
                if (isVeilOrImguiJar(file)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean isVeilOrImguiJar(@NonNull File jarFile) {
        String lowerName = jarFile.getName().toLowerCase(java.util.Locale.ROOT);
        if (lowerName.contains("veil") || lowerName.contains("imgui")) {
            return true;
        }

        try (ZipFile zipFile = new ZipFile(jarFile)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            int scanned = 0;
            while (entries.hasMoreElements() && scanned++ < 4096) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName() == null
                        ? ""
                        : entry.getName().toLowerCase(java.util.Locale.ROOT);

                /*
                 * Some modpacks ship Veil as a nested jar, for example:
                 * META-INF/jars/veil-1.0.0.jar
                 * The previous detection only checked top-level mod file names,
                 * so it missed bundled Veil and skipped the ImGui javaagent.
                 */
                if (entryName.contains("veil")
                        || entryName.contains("imgui")
                        || entryName.contains("foundry/veil")) {
                    return true;
                }

                if (entry.isDirectory()) continue;

                boolean metadata = entryName.endsWith("fabric.mod.json")
                        || entryName.endsWith("quilt.mod.json")
                        || entryName.endsWith("mods.toml")
                        || entryName.endsWith("neoforge.mods.toml");
                if (!metadata) continue;

                String text = readZipEntryText(zipFile, entry, 256 * 1024)
                        .toLowerCase(java.util.Locale.ROOT);
                if (text.contains("foundry.veil")
                        || text.contains("\"veil\"")
                        || text.contains("imgui")) {
                    return true;
                }
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to inspect possible Veil/ImGui mod jar "
                    + jarFile.getName(), throwable);
        }

        return false;
    }

    @NonNull
    private static String readZipEntryText(
            @NonNull ZipFile zipFile,
            @NonNull ZipEntry entry,
            int maxBytes
    ) throws Exception {
        try (InputStream input = zipFile.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                int allowed = Math.min(read, maxBytes - total);
                if (allowed > 0) {
                    output.write(buffer, 0, allowed);
                    total += allowed;
                }
                if (total >= maxBytes) break;
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void addModDirectory(@NonNull ArrayList<File> directories, @NonNull File directory) {
        if (directory.isDirectory() && !directories.contains(directory)) {
            directories.add(directory);
        }
    }

    @NonNull
    private static ArrayList<String> buildVeilImguiCompatibilityJvmArgs(
            @NonNull Context context,
            @NonNull File agentJar
    ) {
        ArrayList<String> args = new ArrayList<>();

        // legacy profile uses this javaagent to disable Veil's broken ARM64 ImGui path override.
        args.add("-javaagent:" + agentJar.getAbsolutePath());

        // legacy profile also forces imgui-java to use the Android-friendly native name.
        // Without this, imgui-java falls back to libimgui-java64.so on Linux/aarch64,
        // which is an x86_64 Linux native and crashes on Android arm64.
        args.add("-Dimgui.library.name=imgui-java");

        // Do NOT set -Dimgui.library.path to the full .so file.
        // imgui-java treats imgui.library.path as a DIRECTORY and appends imgui.library.name to it.
        // When this was set to /.../libimgui-java.so, ImGui tried to load:
        // /.../libimgui-java.so/imgui-java
        //
        // The app native dir is already included in java.library.path by DroidBridge/Android,
        // so System.loadLibrary("imgui-java") can resolve app/src/main/jniLibs/arm64-v8a/libimgui-java.so.
        return args;
    }

    @NonNull
    private static LaunchPlan prependJvmArgsToLaunchPlan(
            @NonNull LaunchPlan plan,
            @NonNull ArrayList<String> argsToPrepend
    ) throws Exception {
        Object currentArgsObject = findJvmArgsObject(plan);
        if (!(currentArgsObject instanceof List)) {
            throw new IllegalStateException("LaunchPlan does not expose a JVM args List");
        }

        ArrayList<String> jvmArgs = new ArrayList<>();
        for (Object value : (List<?>) currentArgsObject) {
            if (value instanceof String) {
                String arg = (String) value;
                if (!isVeilImguiCompatibilityArg(arg)) {
                    jvmArgs.add(arg);
                }
            }
        }
        jvmArgs.addAll(0, argsToPrepend);

        LaunchPlan copied = copyLaunchPlanWithJvmArgs(plan, jvmArgs);
        if (copied != null) {
            return copied;
        }

        if (replaceJvmArgsField(plan, jvmArgs)) {
            return plan;
        }

        @SuppressWarnings("unchecked")
        List<String> mutableArgs = (List<String>) currentArgsObject;
        removeExistingVeilImguiCompatibilityArgs(mutableArgs);
        mutableArgs.addAll(0, argsToPrepend);
        return plan;
    }

    private static boolean isMethodInjectorAgentArg(@Nullable String arg) {
        return arg != null
                && arg.startsWith("-javaagent:")
                && arg.contains("methods_injector_agent.jar");
    }

    private static boolean isVeilImguiCompatibilityArg(@Nullable String arg) {
        return isMethodInjectorAgentArg(arg)
                || (arg != null && arg.startsWith("-Dimgui.library.name="))
                || (arg != null && arg.startsWith("-Dimgui.library.path="));
    }

    private static void removeExistingVeilImguiCompatibilityArgs(@NonNull List<String> jvmArgs) {
        for (int i = jvmArgs.size() - 1; i >= 0; i--) {
            if (isVeilImguiCompatibilityArg(jvmArgs.get(i))) {
                jvmArgs.remove(i);
            }
        }
    }

    @Nullable
    private static Object findJvmArgsObject(@NonNull LaunchPlan plan) throws Exception {
        String[] methodNames = {
                "getJvmArgs",
                "getJavaArgs",
                "getVmArgs",
                "getJvmArguments",
                "getJavaArguments",
                "jvmArgs",
                "javaArgs",
                "vmArgs"
        };

        for (String name : methodNames) {
            try {
                Method method = plan.getClass().getMethod(name);
                method.setAccessible(true);
                Object value = method.invoke(plan);
                if (value instanceof List) return value;
            } catch (NoSuchMethodException ignored) {
            }
        }

        String[] fieldNames = {
                "jvmArgs",
                "javaArgs",
                "vmArgs",
                "jvmArguments",
                "javaArguments"
        };

        for (String name : fieldNames) {
            Field field = findField(plan.getClass(), name);
            if (field == null) continue;
            field.setAccessible(true);
            Object value = field.get(plan);
            if (value instanceof List) return value;
        }

        return null;
    }

    @Nullable
    private static LaunchPlan copyLaunchPlanWithJvmArgs(
            @NonNull LaunchPlan plan,
            @NonNull ArrayList<String> jvmArgs
    ) throws Exception {
        String[] methodNames = {
                "copyWithJvmArgs",
                "copyWithJavaArgs",
                "copyWithVmArgs",
                "copyWithJvmArguments",
                "copyWithJavaArguments",
                "withJvmArgs",
                "withJavaArgs",
                "withVmArgs"
        };

        for (String name : methodNames) {
            try {
                Method method = plan.getClass().getMethod(name, List.class);
                method.setAccessible(true);
                Object value = method.invoke(plan, jvmArgs);
                if (value instanceof LaunchPlan) return (LaunchPlan) value;
            } catch (NoSuchMethodException ignored) {
            }
        }

        return null;
    }

    private static boolean replaceJvmArgsField(
            @NonNull LaunchPlan plan,
            @NonNull ArrayList<String> jvmArgs
    ) {
        String[] fieldNames = {
                "jvmArgs",
                "javaArgs",
                "vmArgs",
                "jvmArguments",
                "javaArguments"
        };

        for (String name : fieldNames) {
            try {
                Field field = findField(plan.getClass(), name);
                if (field == null) continue;
                field.setAccessible(true);
                Object current = field.get(plan);
                if (!(current instanceof List)) continue;
                field.set(plan, jvmArgs);
                return true;
            } catch (Throwable ignored) {
            }
        }

        return false;
    }

    @Nullable
    private static Field findField(@Nullable Class<?> type, @NonNull String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    @NonNull
    private static RendererInterface resolveRendererForLaunch(
            @NonNull Context context,
            @Nullable String requestedVersionId,
            @NonNull String launchVersionId,
            @Nullable LauncherInstance instance,
            @NonNull InstanceLaunchSettings.Settings settings
    ) {
        boolean btaLaunch = BtaRendererPolicy.isBtaLaunch(requestedVersionId, instance)
                || BtaRendererPolicy.isBtaLaunch(launchVersionId, instance);
        boolean modernBta = BtaRendererPolicy.isBta8OrNewer(requestedVersionId, instance)
                || BtaRendererPolicy.isBta8OrNewer(launchVersionId, instance);
        if (btaLaunch && !modernBta) {
            RendererInterface krypton = BtaRendererPolicy.findKryptonRenderer(context);
            if (krypton != null) {
                if (settings.hasRendererOverride()) {
                    Logging.i(TAG, "Legacy BTA renderer lock ignored per-instance renderer override "
                            + settings.rendererIdentifier);
                }
                Logging.i(TAG, "Legacy BTA renderer lock selected " + krypton.getRendererName()
                        + " (" + krypton.getUniqueIdentifier() + ")");
                return krypton;
            }
            safeAppendLog("Warning: Legacy Better Than Adventure requested Krypton, but Krypton was unavailable; using the normal renderer selection.");
        } else if (btaLaunch) {
            Logging.i(TAG, "BTA 8+ renderer policy: respecting normal/per-instance renderer selection");
        }

        if (settings.hasRendererOverride()) {
            RendererInterface renderer = Renderers.findRenderer(context, settings.rendererIdentifier);
            if (renderer != null) {
                Logging.i(TAG, "Per-instance renderer override selected "
                        + renderer.getRendererName() + " (" + renderer.getUniqueIdentifier() + ")");
                return renderer;
            }
            safeAppendLog("Warning: Renderer override unavailable; using the global renderer ("
                    + settings.rendererIdentifier + ").");
        }

        String ruleVersion = instance != null ? instance.getMinecraftVersionId() : launchVersionId;
        if (ruleVersion == null || ruleVersion.trim().isEmpty()) ruleVersion = requestedVersionId;
        RendererInterface versionRenderer = RendererVersionRules.resolveRenderer(context, ruleVersion);
        if (versionRenderer != null) {
            Logging.i(TAG, "Version-specific renderer default selected "
                    + versionRenderer.getRendererName() + " for Minecraft " + ruleVersion);
            return versionRenderer;
        }
        return Renderers.getSelectedRenderer(context);
    }

    @NonNull
    private static LaunchPlan appendQuickPlayArgs(
            @NonNull LaunchPlan plan,
            @Nullable String quickPlayWorldName,
            @Nullable String quickPlayServerAddress,
            @NonNull String launchVersionId,
            @NonNull JSONObject versionJson,
            @NonNull File gameDirectory,
            @Nullable StatusListener listener
    ) {
        String serverAddress = quickPlayServerAddress == null ? "" : quickPlayServerAddress.trim();
        String worldName = quickPlayWorldName == null ? "" : quickPlayWorldName.trim();
        if (serverAddress.isEmpty() && worldName.isEmpty()) {
            return plan;
        }

        ArrayList<String> gameArgs = new ArrayList<>(plan.getGameArgs());
        removeExistingQuickPlayArgs(gameArgs);
        removeExistingServerArgs(gameArgs);

        QuickPlaySupport support = getQuickPlaySupport(launchVersionId, versionJson, gameDirectory);

        if (!serverAddress.isEmpty()) {
            if (support.supported) {
                gameArgs.add("--quickPlayMultiplayer");
                gameArgs.add(serverAddress);
                gameArgs.add("--quickPlayPath");
                gameArgs.add("quickPlay/log.json");
                safeAppendLog("Server Quick Play enabled for server: "
                        + serverAddress
                        + " ("
                        + support.reason
                        + ")");
                return plan.copyWithGameArgs(gameArgs);
            }

            QuickPlayHelper.ParsedServerAddress parsed = QuickPlayHelper.parseServerAddress(serverAddress);
            if (parsed == null) {
                notify(listener, "Unable to launch server: invalid address " + serverAddress);
                safeAppendLog("Server Direct Play skipped: invalid server address " + serverAddress);
                return plan;
            }

            gameArgs.add("--server");
            gameArgs.add(parsed.host);
            gameArgs.add("--port");
            gameArgs.add(String.valueOf(parsed.port));
            safeAppendLog("Server Direct Play fallback enabled for server: "
                    + serverAddress
                    + " -> host="
                    + parsed.host
                    + " port="
                    + parsed.port
                    + " ("
                    + support.reason
                    + ")");
            return plan.copyWithGameArgs(gameArgs);
        }

        if (!support.supported) {
            String message = "Quick Play is not supported for Minecraft "
                    + launchVersionId + ". Launching Minecraft normally.";
            notify(listener, message);
            safeAppendLog("Quick Play skipped for world '" + worldName + "': " + support.reason);
            return plan;
        }

        gameArgs.add("--quickPlaySingleplayer");
        gameArgs.add(worldName);
        gameArgs.add("--quickPlayPath");
        gameArgs.add("quickPlay/log.json");

        safeAppendLog("Quick Play enabled for world folder: " + worldName + " (" + support.reason + ")");
        return plan.copyWithGameArgs(gameArgs);
    }

    @NonNull
    private static QuickPlaySupport getQuickPlaySupport(
            @NonNull String versionId,
            @NonNull JSONObject versionJson,
            @NonNull File gameDirectory
    ) {
        if (versionTreeContainsQuickPlayArguments(versionId, versionJson, new HashSet<>())) {
            return QuickPlaySupport.supported("version JSON contains Quick Play arguments");
        }

        if (supportsQuickPlayVersionId(versionId)) {
            return QuickPlaySupport.supported("version id is Quick Play capable");
        }

        String jsonId = versionJson.optString("id", "");
        if (supportsQuickPlayVersionId(jsonId)) {
            return QuickPlaySupport.supported("version JSON id is Quick Play capable");
        }

        String inheritsFrom = versionJson.optString("inheritsFrom", "");
        if (supportsQuickPlayVersionId(inheritsFrom)) {
            return QuickPlaySupport.supported("inherited Minecraft version is Quick Play capable");
        }

        if (hasQuickPlayCompatibilityMod(gameDirectory)) {
            return QuickPlaySupport.supported("Quick Play compatibility mod detected");
        }

        return QuickPlaySupport.unsupported(
                "vanilla Quick Play starts at Java Edition 1.20; older versions need a compatibility mod"
        );
    }

    private static boolean versionTreeContainsQuickPlayArguments(
            @NonNull String versionId,
            @NonNull JSONObject versionJson,
            @NonNull Set<String> visited
    ) {
        if (!visited.add(versionId)) return false;
        if (containsQuickPlayToken(versionJson)) return true;

        String inheritsFrom = versionJson.optString("inheritsFrom", "").trim();
        if (inheritsFrom.isEmpty()) return false;

        try {
            return versionTreeContainsQuickPlayArguments(inheritsFrom, readVersionJson(inheritsFrom), visited);
        } catch (Throwable throwable) {
            safeAppendLog("Unable to inspect inherited Quick Play args from " + inheritsFrom + ": " + throwable);
            return false;
        }
    }

    private static boolean containsQuickPlayToken(@Nullable Object value) {
        if (value == null || value == JSONObject.NULL) return false;

        if (value instanceof String) {
            return ((String) value).toLowerCase(java.util.Locale.ROOT).contains("quickplay");
        }

        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                if (containsQuickPlayToken(array.opt(i))) return true;
            }
            return false;
        }

        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key != null && key.toLowerCase(java.util.Locale.ROOT).contains("quickplay")) {
                    return true;
                }
                if (containsQuickPlayToken(object.opt(key))) return true;
            }
        }

        return false;
    }

    private static boolean supportsQuickPlayVersionId(@Nullable String versionId) {
        if (versionId == null) return false;

        String value = versionId.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.isEmpty()) return false;

        // DroidBridge uses 26.x ids for modern snapshots/releases, so those support Quick Play.
        int[] numbers = parseVersionNumbers(value);
        if (numbers.length > 0 && numbers[0] >= 26) return true;

        // Snapshot support began around the 23w14a Quick Play rollout.
        if (value.matches("^\\d{2}w\\d{2}[a-z].*$")) {
            try {
                int year = Integer.parseInt(value.substring(0, 2));
                int week = Integer.parseInt(value.substring(3, 5));
                return year > 23 || (year == 23 && week >= 14);
            } catch (Throwable ignored) {
            }
        }

        // Release/loader ids can be "1.20.1", "fabric-loader-0.16.14-1.21.5", etc.
        for (int i = 0; i + 1 < numbers.length; i++) {
            if (numbers[i] == 1 && numbers[i + 1] >= 20) {
                return true;
            }
        }

        return false;
    }

    private static boolean hasQuickPlayCompatibilityMod(@NonNull File gameDirectory) {
        File modsDir = new File(gameDirectory, "mods");
        File[] candidates = modsDir.listFiles((dir, name) -> {
            String lower = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
            return lower.endsWith(".jar")
                    && (lower.contains("quickplay") || lower.contains("quick-play"));
        });

        return candidates != null && candidates.length > 0;
    }

    private static void removeExistingQuickPlayArgs(@NonNull ArrayList<String> args) {
        removeOptionAndValue(args, "--quickPlaySingleplayer");
        removeOptionAndValue(args, "--quickPlayMultiplayer");
        removeOptionAndValue(args, "--quickPlayRealms");
        removeOptionAndValue(args, "--quickPlayPath");
    }

    private static void removeExistingServerArgs(@NonNull ArrayList<String> args) {
        removeOptionAndValue(args, "--server");
        removeOptionAndValue(args, "--port");
    }

    private static void removeOptionAndValue(@NonNull ArrayList<String> args, @NonNull String option) {
        for (int i = 0; i < args.size(); i++) {
            if (!option.equals(args.get(i))) continue;

            args.remove(i);
            if (i < args.size() && !args.get(i).startsWith("--")) {
                args.remove(i);
            }
            i--;
        }
    }

    @NonNull
    private static int[] parseVersionNumbers(@NonNull String versionId) {
        String normalized = versionId.replaceAll("[^0-9]+", ".");
        String[] parts = normalized.split("\\.+");
        ArrayList<Integer> numbers = new ArrayList<>();

        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) continue;
            try {
                numbers.add(Integer.parseInt(part.trim()));
            } catch (Throwable ignored) {
            }
        }

        int[] values = new int[numbers.size()];
        for (int i = 0; i < numbers.size(); i++) {
            values[i] = numbers.get(i);
        }
        return values;
    }

    private static final class QuickPlaySupport {
        final boolean supported;
        final String reason;

        private QuickPlaySupport(boolean supported, @NonNull String reason) {
            this.supported = supported;
            this.reason = reason;
        }

        @NonNull
        static QuickPlaySupport supported(@NonNull String reason) {
            return new QuickPlaySupport(true, reason);
        }

        @NonNull
        static QuickPlaySupport unsupported(@NonNull String reason) {
            return new QuickPlaySupport(false, reason);
        }
    }

    private static boolean hasJvmArgContaining(@NonNull LaunchPlan plan, @NonNull String value) {
        for (String arg : plan.getJvmArgs()) {
            if (arg != null && arg.contains(value)) return true;
        }
        return false;
    }

    @NonNull
    private static String findJvmArgPrefix(@NonNull LaunchPlan plan, @NonNull String prefix) {
        for (String arg : plan.getJvmArgs()) {
            if (arg != null && arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
        }
        return "<missing>";
    }

    public static void onJvmExited(@NonNull Context context, @NonNull String versionId, int exitCode) {
        safeAppendLog("");
        safeAppendLog("===== Minecraft exited (code " + exitCode + ") =====");
        LauncherLogManager.preserveLatestLogIfEnabled(context, versionId);
        ControllerModCompat.reset();
        ControlifySDL.reset();
        resetLaunchState();
    }

    private static void runPreLaunchModMitigations(
            @NonNull Context context,
            @NonNull File gameDirectory,
            @NonNull String launchVersionId,
            @NonNull RendererInterface renderer,
            @NonNull PreLaunchModScan modScan,
            boolean vulkanMod262Compatibility,
            @NonNull String effectiveGraphicsApiMode
    ) {
        if (modScan.hasVulkanMod) {
            // Run after the LWJGL cleanup because that mitigation may rewrite the
            // VulkanMod jar. The surface and queue patches are exact-signature
            // guarded and active only for the VulkanMod 26.2 system-Vulkan compatibility path.
            VulkanModLwjglMitigation.prepare(context, gameDirectory);
            VulkanMod262SurfaceMitigation.prepare(gameDirectory, vulkanMod262Compatibility);
            VulkanMod262QueueMitigation.prepare(gameDirectory, vulkanMod262Compatibility);
            VulkanModConfigMitigation.prepare(gameDirectory);
        }

        if (modScan.hasNotEnoughVulkan) {
            NotEnoughVulkanMonitorMitigation.prepare(gameDirectory);
        }

        // Repair affected NeoForge Replay Mod 26.1 jars before FML builds its
        // mod classloader. Keep the result in latestlog so a failed/ skipped
        // repair is visible instead of silently reaching the same render crash.
        String replayModNeoForgeFix = ReplayModNeoForgeMixinMitigation.prepare(
                gameDirectory,
                launchVersionId
        );
        if (replayModNeoForgeFix != null && !replayModNeoForgeFix.trim().isEmpty()) {
            safeAppendLog(replayModNeoForgeFix);
        }

        // Keep Podium's metadata-based detection exact; it now stays silent
        // when no Podium jar is present.
        PodiumAutoDisableMitigation.prepare(gameDirectory);

        if (modScan.hasAndroidCrashCandidate) {
            AndroidModpackCrashMitigation.prepare(gameDirectory);
        }

        // MobileGlues no longer needs the Sodium shader rewrite. This call is
        // gated to legacy state and only restores files modified by old builds.
        if (modScan.needsSodiumMobileGluesMaintenance()) {
            SodiumMobileGluesShaderPatch.prepare(gameDirectory, launchVersionId, renderer);
        }

        // This path may need to restore jars disabled by an earlier renderer.
        if (modScan.hasNativeMesaOptionalHook || modScan.hasNativeMesaDisabledJar) {
            NativeMesaRendererSafetyProfile.prepare(gameDirectory, launchVersionId, renderer);
        }

        if (modScan.hasSodiumExtraLike) {
            NativeMesaSodiumExtraMixinMitigation.prepare(gameDirectory, launchVersionId, renderer);
        }

        if (modScan.hasDistantHorizons) {
            DistantHorizonsZstdCompat.Result zstd =
                    DistantHorizonsZstdCompat.prepare(context, gameDirectory);
            if (zstd.detected) {
                safeAppendLog("Distant Horizons Zstd: " + zstd.summary);
            }

            DistantHorizonsSqliteCompat.Result sqlite =
                    DistantHorizonsSqliteCompat.prepare(context, gameDirectory);
            if (sqlite.detected) {
                safeAppendLog("Distant Horizons SQLite: " + sqlite.summary);
            }
        }

        if (modScan.hasDistantHorizonsIrisPair()) {
            ArrayList<String> messages = DistantHorizonsIrisConfigMitigation.prepare(
                    gameDirectory,
                    effectiveGraphicsApiMode
            );
            int updates = 0;
            boolean failed = false;
            for (String message : messages) {
                if (message.startsWith("Created ") || message.startsWith("Forced ")) {
                    updates++;
                } else if (message.startsWith("Failed:")) {
                    failed = true;
                    safeAppendLog("Warning: Distant Horizons + Iris: " + message);
                }
            }
            if (!failed) {
                boolean vulkan = effectiveGraphicsApiMode.toLowerCase(java.util.Locale.ROOT).contains("vulkan");
                String dhRenderingEngine = vulkan ? "BLAZE_3D" : "OPEN_GL";
                safeAppendLog("Distant Horizons + Iris: renderingEngine=" + dhRenderingEngine + " config "
                        + (updates > 0 ? "updated" : "verified"));
            }
        }
    }

    @NonNull
    private static LaunchPlan applyDistantHorizonsWorldgenShutdownRuntimeCompatibility(
            @NonNull LaunchPlan plan,
            @NonNull PreLaunchModScan modScan
    ) {
        if (!modScan.hasDistantHorizons) {
            return plan;
        }

        ArrayList<String> jvmArgs = new ArrayList<>(plan.getJvmArgs());
        final String propertyPrefix = "-Ddroidbridge.dh.worldgen_shutdown_compat=";
        for (int i = jvmArgs.size() - 1; i >= 0; i--) {
            String arg = jvmArgs.get(i);
            if (arg != null && arg.startsWith(propertyPrefix)) {
                jvmArgs.remove(i);
            }
        }
        jvmArgs.add("-Ddroidbridge.dh.worldgen_shutdown_compat=true");
        safeAppendLog("Distant Horizons: Android worldgen shutdown guard enabled");
        return plan.copyWithJvmArgs(jvmArgs);
    }

    @NonNull
    private static LaunchPlan applyDistantHorizonsIrisVulkanRuntimeCompatibility(
            @NonNull LaunchPlan plan,
            @NonNull PreLaunchModScan modScan,
            @NonNull String effectiveGraphicsApiMode
    ) {
        boolean vulkan = effectiveGraphicsApiMode.toLowerCase(java.util.Locale.ROOT).contains("vulkan");
        if (!vulkan || !modScan.hasDistantHorizonsIrisPair()) {
            return plan;
        }

        ArrayList<String> jvmArgs = new ArrayList<>(plan.getJvmArgs());
        final String propertyPrefix = "-Ddroidbridge.dh.iris_vulkan_compat=";
        for (int i = jvmArgs.size() - 1; i >= 0; i--) {
            String arg = jvmArgs.get(i);
            if (arg != null && arg.startsWith(propertyPrefix)) {
                jvmArgs.remove(i);
            }
        }
        jvmArgs.add("-Ddroidbridge.dh.iris_vulkan_compat=true");
        safeAppendLog("Distant Horizons + Iris: Vulkan runtime renderer bridge enabled "
                + "(DH OPEN_GL override -> BLAZE_3D)");
        return plan.copyWithJvmArgs(jvmArgs);
    }

    private static void ensureInstalled(@NonNull String versionId) {
        File versionDir = new File(MinecraftVersionInstaller.getVersionsDirectory(), versionId);
        File json = new File(versionDir, versionId + ".json");
        File jar = new File(versionDir, versionId + ".jar");
        if (!json.isFile()) throw new IllegalStateException("Missing version json: " + json.getAbsolutePath());
        if (jar.isFile()) return;

        try {
            JSONObject versionJson = readVersionJson(versionId);

            String referencedJarId = versionJson.optString("jar", "").trim();
            if (!referencedJarId.isEmpty() && !referencedJarId.equals(versionId)) {
                File referencedJar = new File(
                        new File(MinecraftVersionInstaller.getVersionsDirectory(), referencedJarId),
                        referencedJarId + ".jar"
                );
                if (referencedJar.isFile()) return;
                throw new IllegalStateException("Missing referenced client jar: " + referencedJar.getAbsolutePath());
            }

            String inheritsFrom = versionJson.optString("inheritsFrom", "").trim();
            if (!inheritsFrom.isEmpty()) {
                File parentJar = new File(new File(MinecraftVersionInstaller.getVersionsDirectory(), inheritsFrom), inheritsFrom + ".jar");
                if (parentJar.isFile()) return;
                throw new IllegalStateException("Missing inherited client jar: " + parentJar.getAbsolutePath());
            }

            // Cleanroom 0.5.x profiles generated by the installer may omit both
            // standard client-jar linkage fields. Cleanroom only targets 1.12.2,
            // therefore use the already installed vanilla 1.12.2 client jar.
            if (CleanroomSupport.isCleanroomProfile(versionId, versionJson)) {
                File cleanroomBaseJar = new File(
                        new File(MinecraftVersionInstaller.getVersionsDirectory(), CleanroomSupport.MINECRAFT_VERSION),
                        CleanroomSupport.MINECRAFT_VERSION + ".jar"
                );
                if (cleanroomBaseJar.isFile()) {
                    safeAppendLog("Cleanroom: using the installed 1.12.2 client jar");
                    Logging.i(TAG, "Cleanroom client jar fallback=" + cleanroomBaseJar.getAbsolutePath());
                    return;
                }
                throw new IllegalStateException("Missing Cleanroom base client jar: "
                        + cleanroomBaseJar.getAbsolutePath());
            }
        } catch (Exception e) {
            if (e instanceof IllegalStateException) throw (IllegalStateException) e;
            throw new IllegalStateException("Unable to check inherited client jar for " + versionId, e);
        }

        throw new IllegalStateException("Missing client jar: " + jar.getAbsolutePath());
    }

    @NonNull
    private static JSONObject readVersionJson(@NonNull String versionId) throws Exception {
        File json = new File(new File(MinecraftVersionInstaller.getVersionsDirectory(), versionId), versionId + ".json");
        try (java.io.FileInputStream input = new java.io.FileInputStream(json)) {
            return new JSONObject(ca.dnamobile.droidbridgelauncher.runtime.Tools.read(input));
        }
    }

    private static int resolveTargetJava(@NonNull String versionId, @NonNull JSONObject versionJson) {
        if (BtaRendererPolicy.isBta8OrNewer(versionId, null)) {
            Logging.i(TAG, "BTA 8+ requires Java 17 for " + versionId);
            return 17;
        }

        int cleanroomJava = CleanroomSupport.resolveRequiredJava(versionId, versionJson);
        if (cleanroomJava > 0) {
            Logging.i(TAG, "Cleanroom requires Java " + cleanroomJava);
            return cleanroomJava;
        }

        JSONObject javaVersion = versionJson.optJSONObject("javaVersion");
        if (javaVersion != null) {
            int major = javaVersion.optInt("majorVersion", 0);
            if (major == 0) major = javaVersion.optInt("version", 8);
            return Math.max(8, major);
        }

        String inheritsFrom = versionJson.optString("inheritsFrom", "");
        if (!inheritsFrom.isEmpty() && !inheritsFrom.equals(versionId)) {
            try {
                return resolveTargetJava(inheritsFrom, readVersionJson(inheritsFrom));
            } catch (Throwable throwable) {
                safeAppendLog("Warning: Unable to resolve inherited Java version from "
                        + inheritsFrom + "; using Java 8 fallback.");
                Logging.e(TAG, "Unable to resolve inherited Java version from " + inheritsFrom, throwable);
            }
        }

        return 8;
    }

    @NonNull
    private static File resolveRuntimeDirectory(int targetJava) {
        File runtime = RuntimeCompat.resolveRuntimeForJava(targetJava);
        Logging.i(TAG, "Runtime compatibility patch=" + RuntimeCompat.PATCH_ID);
        return runtime;
    }

    private static void notify(@Nullable StatusListener listener, @NonNull String status) {
        if (listener != null) listener.onStatus(status);
        Logging.i(TAG, status);
    }

    private static void appendRendererSettingsSummary(
            @NonNull Context context,
            @NonNull RendererInterface renderer,
            @NonNull InstanceLaunchSettings.Settings perInstanceSettings,
            @NonNull String effectiveGraphicsApiMode,
            boolean effectiveSystemVulkan,
            int width,
            int height
    ) {
        safeAppendLog("Renderer: " + renderer.getRendererName() + " (" + renderer.getRendererId() + ")"
                + (perInstanceSettings.hasRendererOverride() ? " / per-instance override" : ""));
        if (renderer.isExternalPlugin()) {
            safeAppendLog("Renderer plugin: " + renderer.getUniqueIdentifier());
        }

        safeAppendLog("Graphics API: " + friendlyGraphicsApi(effectiveGraphicsApiMode)
                + (perInstanceSettings.hasGraphicsApiOverride() ? " / per-instance override" : ""));
        safeAppendLog("System Vulkan driver (effective): " + onOff(effectiveSystemVulkan));
        safeAppendLog("Use System Vulkan Driver setting: "
                + onOff(LauncherPreferences.isUseSystemVulkanDriver(context)));
        safeAppendLog("Use OpenGL for Minecraft 26+: "
                + onOff(LauncherPreferences.isUseOpenGlForMinecraft26Plus(context)));
        int activeRendererRules = RendererVersionRules.countEnabledRules(context);
        safeAppendLog("Version-specific renderer defaults: "
                + (activeRendererRules == 0
                ? "off"
                : activeRendererRules + (activeRendererRules == 1 ? " active rule" : " active rules")));
        safeAppendLog("Vulkan VSync: "
                + (LauncherPreferences.isVulkanVsyncEnabled(context)
                ? "on (FIFO present mode)"
                : "off (mailbox/unlimited present mode)"));

        if (DriverPluginManager.isVulkanZinkRenderer(renderer)) {
            appendVulkanZinkDriverSummary(context, effectiveSystemVulkan);
        }

        boolean alternativeSurfaceRendering = LauncherPreferences.isUseNativeSurfaceView(context);
        safeAppendLog("Alternative surface rendering: "
                + (alternativeSurfaceRendering ? "on (SurfaceView)" : "off (TextureView)"));
        safeAppendLog("Sustained performance: "
                + onOff(LauncherPreferences.isSustainedPerformanceEnabled(context)));

        GameResolutionSettings.Profile resolutionOverride =
                InstanceLaunchSettings.resolveResolutionProfileOverride(perInstanceSettings);
        GameResolutionSettings.Profile resolutionProfile = resolutionOverride != null
                ? resolutionOverride
                : GameResolutionSettings.getProfile(context);
        safeAppendLog("Game resolution: " + describeResolutionProfile(resolutionProfile)
                + (resolutionOverride != null ? " / per-instance override" : " / global"));
        safeAppendLog("Resolution scale: "
                + LauncherPreferences.getGameResolutionScalePercent(context) + "%");
        safeAppendLog("Surface size at launch: " + Math.max(1, width) + "x" + Math.max(1, height));

        safeAppendLog("Force fullscreen: "
                + onOff(LauncherPreferences.isForceFullscreenMode(context)));
        safeAppendLog("Ignore notch: "
                + onOff(LauncherPreferences.isIgnoreDisplayCutout(context)));
        safeAppendLog("Avoid rounded display corners: "
                + onOff(LauncherPreferences.isAvoidRoundedDisplayCorners(context)));

        if (MobileGluesConfigHelper.isMobileGluesRenderer(renderer)) {
            appendMobileGluesSettings(context, renderer);
        }
    }

    private static void appendVulkanZinkDriverSummary(
            @NonNull Context context,
            boolean effectiveSystemVulkan
    ) {
        if (effectiveSystemVulkan) {
            safeAppendLog("Vulkan Zink driver: Android system Vulkan driver");
            return;
        }

        Driver selected = DriverPluginManager.getSelectedDriver(context);
        Driver effective = selected;
        boolean resolvedDefault = false;
        if (selected.getType() == Driver.Type.DEFAULT_MESA) {
            Driver bundledTurnip = MesaZinkTurnipDriver.createDriverIfAvailable(context);
            if (bundledTurnip != null) {
                effective = bundledTurnip;
                resolvedDefault = true;
            }
        }

        StringBuilder line = new StringBuilder("Vulkan Zink driver: ")
                .append(effective.getName());
        if (resolvedDefault) {
            line.append(" (selected ").append(selected.getName()).append(')');
        }
        if (effective.getVulkanLibrary() != null) {
            line.append(" / ").append(effective.getVulkanLibrary().getName());
        }
        safeAppendLog(line.toString());
    }

    private static void appendMobileGluesSettings(
            @NonNull Context context,
            @NonNull RendererInterface renderer
    ) {
        safeAppendLog("MobileGlues: active");
        String summary;
        try {
            summary = MobileGluesConfigHelper.buildSettingsSummary(context, renderer);
        } catch (Throwable throwable) {
            safeAppendLog("MobileGlues: config unavailable: " + throwable.getClass().getSimpleName());
            Logging.e(TAG, "Unable to read MobileGlues settings for latestlog", throwable);
            return;
        }

        if (summary == null || summary.trim().isEmpty()) {
            safeAppendLog("MobileGlues: config unavailable");
            return;
        }

        String[] lines = summary.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty() || "Other values:".equalsIgnoreCase(line)) continue;
            if (line.startsWith("Selected MG folder config:")) {
                safeAppendLog("MobileGlues config source: selected MG folder");
                continue;
            }
            if (line.startsWith("Direct config:")) {
                safeAppendLog("MobileGlues config source: direct MG/config.json");
                continue;
            }
            if (line.startsWith("Mirrored launch config:")) {
                safeAppendLog("MobileGlues config source: DroidBridge mirrored config");
                continue;
            }
            if (line.startsWith("Launch MG_DIR_PATH:")) {
                continue;
            }
            if (line.startsWith("•")) line = line.substring(1).trim();
            safeAppendLog("MobileGlues: " + line);
        }
    }

    private static void appendForgeNeoForgeModSummary(
            @NonNull File gameDirectory,
            @NonNull String launchVersionId,
            @NonNull JSONObject versionJson
    ) {
        ForgeNeoForgeModList.Summary summary = ForgeNeoForgeModList.scan(
                gameDirectory,
                launchVersionId,
                versionJson
        );
        if (summary == null) return;

        safeAppendSection(summary.loader.label + " mods");
        safeAppendLog("Mods: " + summary.entries.size()
                + " mod entr" + (summary.entries.size() == 1 ? "y" : "ies")
                + " from " + summary.jarCount
                + " JAR" + (summary.jarCount == 1 ? "" : "s"));
        for (ForgeNeoForgeModList.Entry entry : summary.entries) {
            safeAppendLog("Mod: " + entry.toLogLine());
        }
        if (summary.jarsWithoutReadableMetadata > 0) {
            safeAppendLog("Mods: " + summary.jarsWithoutReadableMetadata
                    + " JAR" + (summary.jarsWithoutReadableMetadata == 1 ? "" : "s")
                    + " had no readable Forge/NeoForge metadata; filename fallback used");
        }
    }

    @NonNull
    private static String friendlyGraphicsApi(@Nullable String mode) {
        if (InstanceLaunchSettings.GRAPHICS_API_VULKAN.equals(mode)) return "Vulkan";
        if (InstanceLaunchSettings.GRAPHICS_API_OPENGL.equals(mode)) return "OpenGL";
        if (InstanceLaunchSettings.GRAPHICS_API_DEFAULT.equals(mode)) return "Default";
        return mode == null || mode.trim().isEmpty() ? "OpenGL" : mode;
    }

    @NonNull
    private static String describeResolutionProfile(@NonNull GameResolutionSettings.Profile profile) {
        if (GameResolutionSettings.MODE_1920_1080.equals(profile.mode)) {
            return "1920x1080";
        }
        if (GameResolutionSettings.MODE_BEST_4_3.equals(profile.mode)) {
            return "4:3 best fit";
        }
        if (GameResolutionSettings.MODE_MCSX.equals(profile.mode)) {
            return GameResolutionSettings.MCSX_WIDTH + "x" + GameResolutionSettings.MCSX_HEIGHT + " (MCSX)";
        }
        if (GameResolutionSettings.MODE_CUSTOM.equals(profile.mode)) {
            return profile.customWidth + "x" + profile.customHeight + " (custom)";
        }
        return "native";
    }

    @NonNull
    private static String onOff(boolean enabled) {
        return enabled ? "on" : "off";
    }

    private static void appendLaunchHeader(
            @NonNull Context context,
            @NonNull String selectedVersionId,
            @NonNull String launchVersionId,
            @Nullable LauncherInstance instance,
            @Nullable AccountStore.Account account
    ) {
        safeAppendLog("===== DroidBridge launch =====");
        safeAppendLog("Launcher: " + getLauncherVersionLabel(context)
                + " / " + context.getPackageName());
        safeAppendLog("Device: "
                + Build.MANUFACTURER
                + " "
                + Build.MODEL
                + " / Android API "
                + Build.VERSION.SDK_INT
                + " / "
                + Architecture.androidAbiAsString(Architecture.getDeviceArchitecture()));

        if (selectedVersionId.equals(launchVersionId)) {
            safeAppendLog("Game: " + selectedVersionId);
        } else {
            safeAppendLog("Game: " + selectedVersionId + " / base " + launchVersionId);
        }

        if (instance != null) {
            safeAppendLog("Instance: " + instance.getName());
        }

        safeAppendLog("Account: " + getAccountLogLabel(account));
    }

    @NonNull
    private static String getAccountLogLabel(@Nullable AccountStore.Account account) {
        if (account == null) {
            return "None";
        }

        String name;
        try {
            name = account.getBestDisplayName();
        } catch (Throwable ignored) {
            name = "";
        }

        if (name == null || name.trim().isEmpty()) {
            name = "unknown";
        } else {
            name = name.trim();
        }

        boolean microsoft = false;
        try {
            microsoft = account.isMicrosoftAccount() && account.hasMinecraftSession();
        } catch (Throwable ignored) {
        }

        return microsoft ? "Microsoft (" + name + ")" : "Offline (" + name + ")";
    }
    @NonNull
    private static String getLauncherVersionLabel(@NonNull Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            String versionName = info.versionName == null || info.versionName.trim().isEmpty()
                    ? "unknown"
                    : info.versionName.trim();

            long versionCode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                versionCode = info.getLongVersionCode();
            } else {
                //noinspection deprecation
                versionCode = info.versionCode;
            }

            return "DroidBridge Launcher "
                    + versionName
                    + " ("
                    + versionCode
                    + ", "
                    + getBuildChannel(context)
                    + ")";
        } catch (Throwable throwable) {
            return "DroidBridge Launcher unknown (" + getBuildChannel(context) + ")";
        }
    }

    @NonNull
    private static String getBuildChannel(@NonNull Context context) {
        String packageName = context.getPackageName();
        return packageName != null && packageName.endsWith(".debug") ? "debug" : "release";
    }

    private static void safeAppendSection(@NonNull String title) {
        safeAppendLog("");
        safeAppendLog("----- " + title + " -----");
    }

    private static void appendNetworkSummary(@NonNull LaunchPlan plan) {
        boolean javaNaming = hasJvmArgContaining(plan, "java.naming");
        boolean jdkDns = hasJvmArgContaining(plan, "jdk.naming.dns");
        String resolvPath = findJvmArgPrefix(plan, "-Dext.net.resolvPath=");
        boolean hasResolvPath = !"<missing>".equals(resolvPath) && !resolvPath.trim().isEmpty();

        if (javaNaming && jdkDns && hasResolvPath) {
            safeAppendLog("Network: DNS compatibility ready");
        } else {
            safeAppendLog("Warning: DNS compatibility incomplete"
                    + " (java.naming=" + javaNaming
                    + ", jdk.naming.dns=" + jdkDns
                    + ", resolvPath=" + hasResolvPath + ")");
        }
    }

    private static void safeAppendLog(@NonNull String text) {
        try {
            LauncherLogManager.append(stripTrailingLineBreaks(text));
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to append latestlog line", throwable);
            Logging.i(TAG, text);
        }
    }

    @NonNull
    private static String stripTrailingLineBreaks(@NonNull String text) {
        int end = text.length();
        while (end > 0) {
            char c = text.charAt(end - 1);
            if (c != '\n' && c != '\r') break;
            end--;
        }
        return end == text.length() ? text : text.substring(0, end);
    }
}

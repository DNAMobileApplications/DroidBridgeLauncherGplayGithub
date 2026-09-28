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
import android.system.Os;
import android.view.Display;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.oracle.dalvik.VMLauncher;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import ca.dnamobile.droidbridgelauncher.data.AccountStore;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.instance.DefaultMinecraftOptionsInstaller;
import ca.dnamobile.droidbridgelauncher.modcompat.SableRapierSupport;
import ca.dnamobile.droidbridgelauncher.modcompat.SystemVulkanShieldModelFallback;
import ca.dnamobile.droidbridgelauncher.renderer.BtaRendererPolicy;
import ca.dnamobile.droidbridgelauncher.renderer.RendererInterface;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;
import ca.dnamobile.droidbridgelauncher.runtime.Architecture;
import ca.dnamobile.droidbridgelauncher.runtime.utils.JREUtils;

/**
 * Low-level JVM launcher. Launch sequencing belongs in LaunchGame.
 */
public final class DroidBridgeGameProcessLauncher {
    private static final String TAG = "DroidBridgeGameProcessLauncher";

    private static final boolean ENABLE_NATIVE_EXIT_HOOK = true;
    private static final long INSTALLER_HEARTBEAT_SECONDS = 5L;
    private static final long INSTALLER_MAIN_TIMEOUT_MINUTES = 15L;

    private DroidBridgeGameProcessLauncher() {
    }

    public interface StatusListener {
        void onStatus(@NonNull String status);
    }

    public static int launch(
            @NonNull Context context,
            @NonNull String versionId,
            @Nullable AccountStore.Account account,
            int width,
            int height,
            @Nullable StatusListener listener
    ) throws Exception {
        return LaunchGame.runGame(context, versionId, account, width, height, listener == null ? null : listener::onStatus);
    }

    public static int launchPreparedPlan(
            @NonNull Context context,
            @NonNull LaunchPlan plan,
            @NonNull RendererInterface renderer,
            @Nullable StatusListener listener
    ) throws Exception {
        PathManager.initContextConstants(context);
        configureNativeGlfwContextPolicy(plan.getVersionId());

        notify(listener, "Preparing " + renderer.getRendererName() + " runtime and native bridge...");
        hardDisableLegacyForgeSplashIfNeeded(plan, "before runtime bootstrap");
        JavaRuntimeBootstrap.prepare(context, plan, renderer);
        plan = JavaRuntimeBootstrap.applyPreparedVulkanCompatibilityJvmArgs(plan);
        writeOptions(context, plan);
        plan = applyRendererSpecificGameOptions(context, plan, renderer);
        SystemVulkanShieldModelFallback.apply(
                context,
                plan,
                JavaRuntimeBootstrap.shouldApplySystemVulkanShieldModelFallback(plan)
        );
        setupExitHookIfAvailable(context);
        preloadGlfwBridgeBeforeMinecraftMain(plan);

        notify(listener, "Starting Minecraft JVM...");
        int exitCode = launchWithVmLauncher(context, plan);
        LaunchGame.onJvmExited(context, plan.getVersionId(), exitCode);
        notify(listener, "Minecraft JVM exited with code " + exitCode + ".");
        return exitCode;
    }

    public interface RawJavaProgressListener {
        void onProgress(int progress, @NonNull String status);
    }

    public static int launchRawJavaArgs(
            @NonNull Context context,
            @NonNull String taskName,
            @NonNull File runtimeDirectory,
            @NonNull File workingDirectory,
            @NonNull List<String> javaArgs,
            @Nullable StatusListener listener
    ) throws Exception {
        return launchRawJavaArgsWithProgress(
                context,
                taskName,
                runtimeDirectory,
                workingDirectory,
                javaArgs,
                81,
                88,
                listener == null ? null : (progress, status) -> listener.onStatus(status)
        );
    }

    /**
     * Runs Forge/NeoForge installer jars as an external Java process.
     *
     * Android 16 may reject executing /data/user/0/.../bin/java directly. When
     * that happens, retry through Android's system linker (/system/bin/linker64
     * or /system/bin/linker). This avoids the in-process VMLauncher path that can
     * crash before the installer starts or fail with missing JNI registration.
     */
    public static int launchRawJavaArgsWithProgress(
            @NonNull Context context,
            @NonNull String taskName,
            @NonNull File runtimeDirectory,
            @NonNull File workingDirectory,
            @NonNull List<String> javaArgs,
            int startProgress,
            int endProgress,
            @Nullable RawJavaProgressListener listener
    ) throws Exception {
        ensureActivePathManager(context);

        File javaBinary = new File(runtimeDirectory, "bin/java");
        if (!javaBinary.isFile()) {
            throw new IllegalStateException("Missing Java binary: " + javaBinary.getAbsolutePath());
        }

        if (!javaBinary.canExecute()) {
            //noinspection ResultOfMethodCallIgnored
            javaBinary.setExecutable(true, false);
        }

        if (!workingDirectory.exists() && !workingDirectory.mkdirs()) {
            throw new IllegalStateException("Unable to create working directory: " + workingDirectory.getAbsolutePath());
        }

        resetInstallerLatestLogs(taskName, runtimeDirectory, workingDirectory, javaArgs);

        notifyRaw(listener, startProgress, "Preparing installer runtime...");
        prepareRawInstallerEnvironment(context, runtimeDirectory);

        ArrayList<String> args = new ArrayList<>();
        args.add("java");

        // These are harmless for an external Java process and make the runtime
        // layout explicit on Android's linker path.
        addInstallerJvmArgIfMissing(args, "-Djava.home=" + runtimeDirectory.getAbsolutePath());
        addInstallerJvmArgIfMissing(args, "-Djava.library.path=" + buildInstallerLdLibraryPath(runtimeDirectory));
        addInstallerJvmArgIfMissing(args, "-Dsun.boot.library.path=" + buildInstallerLdLibraryPath(runtimeDirectory));
        addInstallerJvmArgIfMissing(args, "-Duser.dir=" + workingDirectory.getAbsolutePath());

        // Keep desktop Java semantics here. External Java supports -jar directly,
        // and Forge/NeoForge installers expect their manifest classpath handling.
        args.addAll(javaArgs);

        for (String arg : args) {
            logJvmArg(arg);
        }

        try {
            System.setProperty("user.dir", workingDirectory.getAbsolutePath());
            safeAppendLog("Installer user.dir=" + workingDirectory.getAbsolutePath());
            safeAppendLog("Installer native chdir skipped; external Java process owns its cwd.");
        } catch (Throwable throwable) {
            Logging.e(TAG, "installer working directory setup failed; launch will continue", throwable);
            safeAppendLog("installer working directory setup failed: " + throwable);
        }

        notifyRaw(listener, Math.min(100, startProgress + 1), "Starting installer JVM...");
        int exitCode = launchInstallerProcessWithFallback(
                context,
                taskName,
                javaBinary,
                workingDirectory,
                runtimeDirectory,
                args,
                Math.min(100, startProgress + 2),
                Math.max(startProgress + 2, Math.min(100, endProgress - 1)),
                listener
        );
        notifyRaw(listener, endProgress, "Installer JVM exited with code " + exitCode + ".");
        return exitCode;
    }

    private static int launchInstallerProcessWithFallback(
            @NonNull Context context,
            @NonNull String taskName,
            @NonNull File javaBinary,
            @NonNull File workingDirectory,
            @NonNull File runtimeDirectory,
            @NonNull List<String> args,
            int heartbeatStartProgress,
            int heartbeatEndProgress,
            @Nullable RawJavaProgressListener listener
    ) throws Exception {
        try {
            int directExitCode = launchInstallerProcess(
                    context,
                    taskName,
                    javaBinary,
                    workingDirectory,
                    runtimeDirectory,
                    args,
                    false,
                    heartbeatStartProgress,
                    heartbeatEndProgress,
                    listener
            );

            if (directExitCode != 126 && directExitCode != 127) {
                return directExitCode;
            }

            safeAppendLog("Installer direct Java process exited with " + directExitCode
                    + "; retrying through Android system linker.");
        } catch (IOException directStartFailure) {
            safeAppendLog("Installer direct Java exec failed: " + directStartFailure);
        }

        File linker = resolveSystemLinker();
        if (linker == null || !linker.isFile()) {
            throw new IOException("Direct Java start was blocked and no Android system linker was found.");
        }

        notifyRaw(listener, heartbeatStartProgress,
                "Direct Java start was blocked. Retrying through Android system linker...");
        safeAppendLog("Retrying installer through system linker: " + linker.getAbsolutePath());

        return launchInstallerProcess(
                context,
                taskName,
                javaBinary,
                workingDirectory,
                runtimeDirectory,
                args,
                true,
                heartbeatStartProgress,
                heartbeatEndProgress,
                listener
        );
    }

    private static int launchInstallerProcess(
            @NonNull Context context,
            @NonNull String taskName,
            @NonNull File javaBinary,
            @NonNull File workingDirectory,
            @NonNull File runtimeDirectory,
            @NonNull List<String> args,
            boolean useSystemLinker,
            int heartbeatStartProgress,
            int heartbeatEndProgress,
            @Nullable RawJavaProgressListener listener
    ) throws Exception {
        ArrayList<String> command = new ArrayList<>();

        if (useSystemLinker) {
            File linker = resolveSystemLinker();
            if (linker == null || !linker.isFile()) {
                throw new IOException("No Android system linker found for installer fallback.");
            }
            command.add(linker.getAbsolutePath());
            command.add(javaBinary.getAbsolutePath());
        } else {
            command.add(javaBinary.getAbsolutePath());
        }

        for (int i = 1; i < args.size(); i++) {
            command.add(args.get(i));
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory);
        builder.redirectErrorStream(true);

        Map<String, String> env = builder.environment();
        sanitizeInstallerChildEnvironment(env);
        env.put("JAVA_HOME", runtimeDirectory.getAbsolutePath());
        env.put("HOME", PathManager.DIR_MINECRAFT_HOME);
        env.put("TMPDIR", PathManager.DIR_CACHE.getAbsolutePath());
        env.put("LD_LIBRARY_PATH", buildInstallerLdLibraryPath(runtimeDirectory));

        File heapTaggingPreload = resolveHeapTaggingPreloadLibrary(context);
        if (heapTaggingPreload.isFile()) {
            env.put("LD_PRELOAD", heapTaggingPreload.getAbsolutePath());
            safeAppendLog("Installer child LD_PRELOAD=" + heapTaggingPreload.getAbsolutePath());
        } else {
            env.remove("LD_PRELOAD");
            safeAppendLog("Installer child LD_PRELOAD disabled; missing " + heapTaggingPreload.getAbsolutePath());
        }

        String existingPath = env.get("PATH");
        String pathValue = new File(runtimeDirectory, "bin").getAbsolutePath();
        if (existingPath != null && !existingPath.isEmpty()) {
            pathValue = pathValue + ":" + existingPath;
        }
        env.put("PATH", pathValue);

        String launchMode = useSystemLinker ? "system-linker" : "direct-exec";
        Logging.i(TAG, "Installer process launchMode=" + launchMode + " command=" + command);
        Logging.i(TAG, "Installer process cwd=" + workingDirectory.getAbsolutePath());
        Logging.i(TAG, "Installer process LD_LIBRARY_PATH=" + env.get("LD_LIBRARY_PATH"));
        safeAppendLog("Installer process launchMode=" + launchMode);
        safeAppendLog("Installer process command=" + command);
        safeAppendLog("Installer process cwd=" + workingDirectory.getAbsolutePath());
        safeAppendLog("Installer process LD_LIBRARY_PATH=" + env.get("LD_LIBRARY_PATH"));

        Process process = builder.start();
        safeAppendLog("Installer process started: " + taskName + " mode=" + launchMode);

        Thread outputThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Logging.i(TAG, "[installer:" + taskName + "] " + line);
                    safeAppendLog("[installer:" + taskName + "] " + line);
                }
            } catch (Throwable throwable) {
                Logging.e(TAG, "Installer output reader failed", throwable);
                safeAppendLog("Installer output reader failed (" + taskName + "): " + throwable);
            }
        }, "InstallerOutput-" + taskName);
        outputThread.setDaemon(true);
        outputThread.start();

        long startMs = System.currentTimeMillis();
        long timeoutMs = TimeUnit.MINUTES.toMillis(INSTALLER_MAIN_TIMEOUT_MINUTES);
        String friendlyName = friendlyInstallerName(taskName);
        String[] messages = new String[]{
                "Finalizing " + friendlyName + " install...",
                "Finalizing " + friendlyName + " install... still working",
                "Finalizing " + friendlyName + " install... running processors",
                "Finalizing " + friendlyName + " install... writing version profile",
                "Finalizing " + friendlyName + " install... checking generated files"
        };

        int safeStart = Math.max(0, Math.min(100, heartbeatStartProgress));
        int safeEnd = Math.max(safeStart, Math.min(100, heartbeatEndProgress));
        notifyRaw(listener, safeStart, messages[0] + " (0s)");

        while (true) {
            if (process.waitFor(INSTALLER_HEARTBEAT_SECONDS, TimeUnit.SECONDS)) {
                outputThread.join(5000L);
                int exitCode = process.exitValue();
                safeAppendLog("Installer process finished: " + taskName
                        + " mode=" + launchMode
                        + " exitCode=" + exitCode);
                return exitCode;
            }

            long elapsedMs = System.currentTimeMillis() - startMs;
            long elapsedSeconds = TimeUnit.MILLISECONDS.toSeconds(elapsedMs);
            int messageIndex = (int) ((elapsedSeconds / INSTALLER_HEARTBEAT_SECONDS) % messages.length);
            int progressSpan = Math.max(0, safeEnd - safeStart);
            int progressStep = (int) Math.min(progressSpan, elapsedSeconds / INSTALLER_HEARTBEAT_SECONDS);
            int progress = safeStart + progressStep;
            String message = messages[messageIndex] + " (" + elapsedSeconds + "s)";

            notifyRaw(listener, progress, message);
            safeAppendLog("Installer process still running: " + taskName
                    + " mode=" + launchMode
                    + " elapsed=" + elapsedSeconds + "s");

            if (elapsedMs >= timeoutMs) {
                safeAppendLog("Installer process timed out: " + taskName + ". Killing child process.");
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                outputThread.join(5000L);
                throw new IllegalStateException("Installer process timed out after "
                        + INSTALLER_MAIN_TIMEOUT_MINUTES
                        + " minutes. Check latestlog.txt for command/env details.");
            }
        }
    }

    @Nullable
    private static File resolveSystemLinker() {
        File linker64 = new File("/system/bin/linker64");
        if (linker64.isFile()) return linker64;

        File linker = new File("/system/bin/linker");
        if (linker.isFile()) return linker;

        return null;
    }

    /**
     * Preserve an already-selected scoped/custom storage root.
     *
     * Forge/NeoForge installers receive a normal File working directory. If the
     * create-instance flow already initialized PathManager for a selected storage
     * root, reinitializing it here can silently move the installer back to the
     * default .minecraft folder.
     */
    private static void ensureActivePathManager(@NonNull Context context) {
        if (PathManager.DIR_MINECRAFT_HOME == null || PathManager.DIR_MINECRAFT_HOME.trim().isEmpty()) {
            PathManager.initContextConstants(context);
        }
    }

    private static void addInstallerJvmArgIfMissing(
            @NonNull ArrayList<String> args,
            @NonNull String value
    ) {
        String key;
        int equals = value.indexOf('=');
        if (equals > 0) {
            key = value.substring(0, equals + 1);
            for (String arg : args) {
                if (arg != null && arg.startsWith(key)) return;
            }
        } else if (args.contains(value)) {
            return;
        }
        args.add(value);
    }

    @NonNull
    private static Thread startInstallerHeartbeat(
            @NonNull String taskName,
            @NonNull java.util.concurrent.atomic.AtomicBoolean running,
            int startProgress,
            int endProgress,
            @Nullable RawJavaProgressListener listener
    ) {
        Thread thread = new Thread(() -> {
            long startMs = System.currentTimeMillis();
            String friendlyName = friendlyInstallerName(taskName);
            String[] messages = new String[]{
                    "Finalizing " + friendlyName + " install...",
                    "Finalizing " + friendlyName + " install... still working",
                    "Finalizing " + friendlyName + " install... running processors",
                    "Finalizing " + friendlyName + " install... writing version profile",
                    "Finalizing " + friendlyName + " install... checking generated files"
            };

            int safeStart = Math.max(0, Math.min(100, startProgress));
            int safeEnd = Math.max(safeStart, Math.min(100, endProgress));

            while (running.get()) {
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(INSTALLER_HEARTBEAT_SECONDS));
                } catch (InterruptedException ignored) {
                    break;
                }

                if (!running.get()) break;

                long elapsedSeconds = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - startMs);
                int messageIndex = (int) ((elapsedSeconds / INSTALLER_HEARTBEAT_SECONDS) % messages.length);
                int progressSpan = Math.max(0, safeEnd - safeStart);
                int progressStep = (int) Math.min(progressSpan, elapsedSeconds / INSTALLER_HEARTBEAT_SECONDS);
                int progress = safeStart + progressStep;
                String message = messages[messageIndex] + " (" + elapsedSeconds + "s)";

                notifyRaw(listener, progress, message);
                safeAppendLog("Installer VMLauncher still running: " + taskName
                        + " elapsed=" + elapsedSeconds + "s");
            }
        }, "InstallerHeartbeat-" + taskName);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    @NonNull
    private static String friendlyInstallerName(@NonNull String taskName) {
        String value = taskName.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("neoforge")) return "NeoForge";
        if (value.contains("forge")) return "Forge";
        return "loader";
    }

    private static void notifyRaw(
            @Nullable RawJavaProgressListener listener,
            int progress,
            @NonNull String status
    ) {
        if (listener != null) listener.onProgress(Math.max(0, Math.min(100, progress)), status);
        Logging.i(TAG, status);
        safeAppendLog(status);
    }

    @NonNull
    private static File resolveHeapTaggingPreloadLibrary(@NonNull Context context) {
        String nativeLibraryDir = context.getApplicationInfo() != null
                ? context.getApplicationInfo().nativeLibraryDir
                : null;

        if (nativeLibraryDir != null && !nativeLibraryDir.isEmpty()) {
            return new File(nativeLibraryDir, "libdisable_heap_tagging.so");
        }

        String nativeLibPath = PathManager.DIR_NATIVE_LIB;
        if (nativeLibPath != null && !nativeLibPath.isEmpty()) {
            return new File(nativeLibPath, "libdisable_heap_tagging.so");
        }

        return new File("libdisable_heap_tagging.so");
    }

    @NonNull
    private static String buildInstallerLdLibraryPath(@NonNull File runtimeDirectory) {
        File runtimeLibDir = resolveRuntimeLibDir(runtimeDirectory);
        File jvmLibraryDir = resolveJvmLibraryDir(runtimeLibDir);

        StringBuilder ldPath = new StringBuilder();
        appendPath(ldPath, jvmLibraryDir);
        appendPath(ldPath, new File(runtimeLibDir, "jli"));
        appendPath(ldPath, runtimeLibDir);

        String nativeLibDir = PathManager.DIR_NATIVE_LIB;
        if (nativeLibDir != null && !nativeLibDir.isEmpty()) {
            appendPath(ldPath, new File(nativeLibDir));
        }

        /*
         * Keep installer LD_LIBRARY_PATH clean. Do not append the current process
         * LD_LIBRARY_PATH because game renderer paths can leak into the installer
         * JVM and make Forge/NeoForge fail before their main class starts.
         */
        return ldPath.toString();
    }

    @NonNull
    private static String buildInstallerNativeLinkerPath(@NonNull File runtimeDirectory) {
        File runtimeLibDir = resolveRuntimeLibDir(runtimeDirectory);
        File jvmLibraryDir = resolveJvmLibraryDir(runtimeLibDir);
        String ldPath = buildInstallerLdLibraryPath(runtimeDirectory);
        return jvmLibraryDir.getAbsolutePath() + (ldPath.isEmpty() ? "" : ":" + ldPath);
    }

    @NonNull
    private static File resolveRuntimeLibDir(@NonNull File runtimeDirectory) {
        File baseLib = new File(runtimeDirectory, "lib");
        for (String part : getRuntimeArchCandidates()) {
            File candidate = new File(baseLib, part);
            if (candidate.isDirectory()) return candidate;
        }
        return baseLib;
    }

    @NonNull
    private static File resolveJvmLibraryDir(@NonNull File runtimeLibDir) {
        File server = new File(runtimeLibDir, "server");
        if (new File(server, "libjvm.so").isFile()) return server;

        File client = new File(runtimeLibDir, "client");
        if (new File(client, "libjvm.so").isFile()) return client;

        return server;
    }

    @NonNull
    private static List<String> getRuntimeArchCandidates() {
        ArrayList<String> candidates = new ArrayList<>();
        String arch = Architecture.archAsString(Architecture.getDeviceArchitecture());
        addArchCandidate(candidates, arch);

        if (Architecture.getDeviceArchitecture() == Architecture.ARCH_ARM64 || arch.contains("arm64") || arch.contains("aarch64")) {
            addArchCandidate(candidates, "aarch64");
            addArchCandidate(candidates, "arm64");
            addArchCandidate(candidates, "arm64-v8a");
        } else if (Architecture.getDeviceArchitecture() == Architecture.ARCH_ARM || arch.contains("arm")) {
            addArchCandidate(candidates, "arm");
            addArchCandidate(candidates, "armeabi-v7a");
        } else if (Architecture.getDeviceArchitecture() == Architecture.ARCH_X86) {
            addArchCandidate(candidates, "i386");
            addArchCandidate(candidates, "i486");
            addArchCandidate(candidates, "i586");
            addArchCandidate(candidates, "x86");
        } else if (Architecture.getDeviceArchitecture() == Architecture.ARCH_X86_64 || arch.contains("x86_64") || arch.contains("amd64")) {
            addArchCandidate(candidates, "amd64");
            addArchCandidate(candidates, "x86_64");
        }

        return candidates;
    }

    private static void addArchCandidate(@NonNull ArrayList<String> candidates, @Nullable String value) {
        if (value == null || value.trim().isEmpty()) return;
        for (String part : value.split("/")) {
            String cleaned = part.trim();
            if (!cleaned.isEmpty() && !candidates.contains(cleaned)) candidates.add(cleaned);
        }
    }

    private static void prepareRawInstallerEnvironment(@NonNull Context context, @NonNull File runtimeDirectory) {
        sanitizeInstallerProcessEnvironment();

        String ldPath = buildInstallerLdLibraryPath(runtimeDirectory);

        setInstallerEnv("JAVA_HOME", runtimeDirectory.getAbsolutePath());
        setInstallerEnv("HOME", PathManager.DIR_MINECRAFT_HOME);
        setInstallerEnv("TMPDIR", PathManager.DIR_CACHE.getAbsolutePath());
        setInstallerEnv("LD_LIBRARY_PATH", ldPath);

        File heapTaggingPreload = resolveHeapTaggingPreloadLibrary(context);

        // Do not set LD_PRELOAD globally in the Android app process. The child
        // installer process gets its own LD_PRELOAD in ProcessBuilder when the
        // helper library is present.
        unsetInstallerEnv("LD_PRELOAD");
        if (heapTaggingPreload.isFile()) {
            Logging.i(TAG, "Installer global LD_PRELOAD disabled; child will use: "
                    + heapTaggingPreload.getAbsolutePath());
            safeAppendLog("Installer global LD_PRELOAD disabled; child will use: "
                    + heapTaggingPreload.getAbsolutePath());
        } else {
            Logging.i(TAG, "Installer LD_PRELOAD unavailable: " + heapTaggingPreload.getAbsolutePath());
            safeAppendLog("Installer LD_PRELOAD unavailable: " + heapTaggingPreload.getAbsolutePath());
        }

        String existingPath = System.getenv("PATH");
        String pathValue = new File(runtimeDirectory, "bin").getAbsolutePath();
        if (existingPath != null && !existingPath.isEmpty()) {
            pathValue = pathValue + ":" + existingPath;
        }
        setInstallerEnv("PATH", pathValue);

        // Do not call JREUtils.setLdLibraryPath(), JREUtils.chdir(), or
        // JREUtils.dlopen() here. The Android 16 test device died inside that
        // native bridge path before NeoForge started. This installer path now
        // uses an external Java process with a clean child environment instead.
        Logging.i(TAG, "Installer native linker bridge skipped for Android 16 compatibility.");
        safeAppendLog("Installer native linker bridge skipped for Android 16 compatibility.");
        Logging.i(TAG, "Installer runtime preloading skipped; child Java process will load runtime libs.");
        safeAppendLog("Installer runtime preloading skipped; child Java process will load runtime libs.");

        Logging.i(TAG, "Installer runtimeHome=" + runtimeDirectory.getAbsolutePath());
        Logging.i(TAG, "Installer LD_LIBRARY_PATH=" + ldPath);
        safeAppendLog("Installer runtimeHome=" + runtimeDirectory.getAbsolutePath());
        safeAppendLog("Installer LD_LIBRARY_PATH=" + ldPath);
    }

    private static void sanitizeInstallerChildEnvironment(@NonNull Map<String, String> env) {
        String[] keysToRemove = new String[]{
                "CLASSPATH",
                "JAVA_TOOL_OPTIONS",
                "JDK_JAVA_OPTIONS",
                "_JAVA_OPTIONS",
                "DROIDBRIDGE_RENDERER",
                "DROIDBRIDGE_EGL",
                "DROIDBRIDGE_EGL_LIBRARY",
                "DROIDBRIDGE_EGL_LIBRARY",
                "DROIDBRIDGE_RENDERER_LIBRARY",
                "DROIDBRIDGE_RENDERER_LIBRARY",
                "OSMESA_LIB",
                "LIB_MESA_NAME",
                "MESA_LOADER_DRIVER_OVERRIDE",
                "GALLIUM_DRIVER",
                "VK_ICD_FILENAMES",
                "VK_DRIVER_FILES",
                "DRIVER_PATH",
                "LIBGL_DRIVERS_PATH",
                "EGL_DRIVERS_PATH",
                "LTW_NEVER_FLUSH_BUFFERS",
                "LTW_COHERENT_DYNAMIC_STORAGE"
        };

        for (String key : keysToRemove) {
            env.remove(key);
        }
    }

    private static void sanitizeInstallerProcessEnvironment() {
        String[] keysToRemove = new String[]{
                "CLASSPATH",
                "JAVA_TOOL_OPTIONS",
                "JDK_JAVA_OPTIONS",
                "_JAVA_OPTIONS",
                "DROIDBRIDGE_RENDERER",
                "DROIDBRIDGE_EGL",
                "DROIDBRIDGE_EGL_LIBRARY",
                "DROIDBRIDGE_EGL_LIBRARY",
                "DROIDBRIDGE_RENDERER_LIBRARY",
                "DROIDBRIDGE_RENDERER_LIBRARY",
                "OSMESA_LIB",
                "LIB_MESA_NAME",
                "MESA_LOADER_DRIVER_OVERRIDE",
                "GALLIUM_DRIVER",
                "VK_ICD_FILENAMES",
                "VK_DRIVER_FILES",
                "DRIVER_PATH",
                "LIBGL_DRIVERS_PATH",
                "EGL_DRIVERS_PATH",
                "LTW_NEVER_FLUSH_BUFFERS",
                "LTW_COHERENT_DYNAMIC_STORAGE"
        };

        for (String key : keysToRemove) {
            unsetInstallerEnv(key);
        }
    }

    private static void preloadRawInstallerRuntime(@NonNull File runtimeDirectory) {
        File runtimeLibDir = resolveRuntimeLibDir(runtimeDirectory);
        File jvmLibraryDir = resolveJvmLibraryDir(runtimeLibDir);

        dlopenOptional(new File(runtimeLibDir, "jli/libjli.so"));
        dlopenOptional(new File(runtimeLibDir, "libjli.so"));
        dlopenOptional(new File(jvmLibraryDir, "libjvm.so"));
        dlopenOptional(new File(runtimeLibDir, "libverify.so"));
        dlopenOptional(new File(runtimeLibDir, "libjava.so"));
        dlopenOptional(new File(runtimeLibDir, "libzip.so"));
        dlopenOptional(new File(runtimeLibDir, "libnet.so"));
        dlopenOptional(new File(runtimeLibDir, "libnio.so"));
        dlopenOptional(new File(runtimeLibDir, "libawt.so"));
        dlopenOptional(new File(runtimeLibDir, "libawt_headless.so"));
        dlopenOptional(new File(runtimeLibDir, "libfreetype.so"));
        dlopenOptional(new File(runtimeLibDir, "libfontmanager.so"));

    }

    private static boolean dlopenOptional(@NonNull File file) {
        if (!file.isFile()) return false;
        return dlopenOptional(file.getAbsolutePath());
    }

    private static boolean dlopenOptional(@Nullable String path) {
        if (path == null || path.trim().isEmpty()) return false;
        try {
            boolean loaded = JREUtils.dlopen(path);
            Logging.i(TAG, "installer dlopen " + path + " = " + loaded);
            safeAppendLog("installer dlopen " + path + " = " + loaded);
            return loaded;
        } catch (Throwable throwable) {
            Logging.e(TAG, "installer dlopen failed for " + path, throwable);
            safeAppendLog("installer dlopen failed for " + path + ": " + throwable);
            return false;
        }
    }

    private static void setInstallerEnv(@NonNull String key, @NonNull String value) {
        try {
            Os.setenv(key, value, true);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to set installer env " + key, throwable);
            safeAppendLog("Unable to set installer env " + key + ": " + throwable);
        }
    }

    private static void unsetInstallerEnv(@NonNull String key) {
        try {
            Os.unsetenv(key);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to unset installer env " + key, throwable);
            safeAppendLog("Unable to unset installer env " + key + ": " + throwable);
        }
    }

    private static void appendPath(@NonNull StringBuilder builder, @NonNull File file) {
        if (!file.isDirectory()) return;
        String path = file.getAbsolutePath();
        if (containsPath(builder.toString(), path)) return;
        if (builder.length() > 0) builder.append(':');
        builder.append(path);
    }

    private static boolean containsPath(@NonNull String pathList, @NonNull String path) {
        if (pathList.isEmpty()) return false;
        String[] parts = pathList.split(":");
        for (String part : parts) {
            if (path.equals(part)) return true;
        }
        return false;
    }

    @NonNull
    private static File resolveAnyLwjglNativeDir() {
        String abi = Architecture.androidAbiAsString(Architecture.getDeviceArchitecture());
        File[] candidates = new File[]{
                new File(PathManager.DIR_FILE, "lwjgl3.4.1/natives/" + abi),
                new File(PathManager.DIR_FILE, "lwjgl3.3.3-bta/natives/" + abi),
                new File(PathManager.DIR_FILE, "lwjgl3.3.3/natives/" + abi),
                new File(PathManager.DIR_FILE, "lwjgl3.4.1/natives/arm64-v8a"),
                new File(PathManager.DIR_FILE, "lwjgl3.3.3-bta/natives/arm64-v8a"),
                new File(PathManager.DIR_FILE, "lwjgl3.3.3/natives/arm64-v8a")
        };

        for (File candidate : candidates) {
            if (candidate.isDirectory()) return candidate;
        }

        File empty = new File(PathManager.DIR_CACHE, "installer-empty-natives");
        //noinspection ResultOfMethodCallIgnored
        empty.mkdirs();
        return empty;
    }

    private static void preloadGlfwBridgeBeforeMinecraftMain(@NonNull LaunchPlan plan) {
        if (!shouldPreloadGlfwBridge(plan.getVersionId())) {
            return;
        }

        try {
            Class<?> glfwClass = Class.forName("org.lwjgl.glfw.GLFW");
            safeAppendLog("DroidBridge Pre5: GLFW bridge preloaded before Minecraft Main from "
                    + describeCodeSource(glfwClass));
            safeAppendLog("DroidBridge Pre5: GLFW monitorName="
                    + hasMethod(glfwClass, "glfwGetMonitorName", long.class)
                    + " preedit="
                    + hasAnyMethodNamed(glfwClass, "glfwSetPreeditCallback"));
        } catch (Throwable throwable) {
            safeAppendLog("DroidBridge Pre5: GLFW bridge preload failed before Minecraft Main: "
                    + throwable);
            Logging.e(TAG, "GLFW bridge preload failed before Minecraft Main", throwable);
        }
    }

    private static boolean shouldPreloadGlfwBridge(@Nullable String versionId) {
        if (versionId == null) return false;
        String lower = versionId.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("26.")
                || lower.contains("snapshot")
                || lower.contains("pre")
                || lower.contains("rc");
    }

    @NonNull
    private static String describeCodeSource(@NonNull Class<?> type) {
        try {
            java.security.CodeSource source = type.getProtectionDomain() != null
                    ? type.getProtectionDomain().getCodeSource()
                    : null;
            if (source != null && source.getLocation() != null) {
                return String.valueOf(source.getLocation());
            }
        } catch (Throwable ignored) {
        }
        return "<unknown>";
    }

    private static boolean hasAnyMethodNamed(@NonNull Class<?> type, @NonNull String name) {
        for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
            if (name.equals(method.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMethod(
            @NonNull Class<?> type,
            @NonNull String name,
            @NonNull Class<?>... parameterTypes
    ) {
        try {
            type.getDeclaredMethod(name, parameterTypes);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int launchWithVmLauncher(@NonNull Context context, @NonNull LaunchPlan plan) {
        ArrayList<String> args = new ArrayList<>();
        args.add("java");

        ArrayList<String> jvmArgs = normalizeJvmArgsForVmLauncher(plan.getJvmArgs());

        // Put the Sable property and agent in the normalized JVM list so the always-on
        // DroidBridgeLibPatcher is deduplicated instead of being launched twice.
        SableRapierSupport.addJvmArgsIfNeeded(context, plan, jvmArgs);
        applyLegacyInfdevJvmWorkarounds(plan, jvmArgs);
        String mainClass = resolveMainClassForVmLauncher(plan, jvmArgs);

        args.addAll(jvmArgs);
        args.add(mainClass);
        args.addAll(plan.getGameArgs());

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if ("--accessToken".equals(arg) && i + 1 < args.size()) {
                logJvmArg(arg);
                logJvmArg("<hidden>");
                i++;
            } else {
                logJvmArg(arg);
            }
        }

        try {
            JREUtils.chdir(plan.getGameDirectory().getAbsolutePath());
        } catch (Throwable throwable) {
            Logging.e(TAG, "chdir failed; launch will continue", throwable);
            safeAppendLog("chdir failed: " + throwable);
        }

        sanitizeNetworkSystemPropertiesForGameLaunch();

        safeAppendLog("VMLauncher: launching Minecraft mainClass=" + mainClass
                + " argCount=" + args.size()
                + " cwd=" + plan.getGameDirectory().getAbsolutePath());
        return VMLauncher.launchJVM(args.toArray(new String[0]));
    }


    private static void applyLegacyInfdevJvmWorkarounds(
            @NonNull LaunchPlan plan,
            @NonNull ArrayList<String> jvmArgs
    ) {
        boolean stubOcclusionQueries = shouldStubLegacyOcclusionQueries(plan.getVersionId());
        replaceJvmSystemProperty(
                jvmArgs,
                "droidbridge.legacyOcclusionQueryStub",
                stubOcclusionQueries ? "true" : "false"
        );
        if (stubOcclusionQueries) {
            safeAppendLog("Legacy pre-release renderer: enabled ARB occlusion-query stub through JVM property for " + plan.getVersionId() + ".");
        }
    }

    private static boolean shouldStubLegacyOcclusionQueries(@Nullable String rawVersionId) {
        if (rawVersionId == null) return false;
        String versionId = rawVersionId.trim().toLowerCase(Locale.ROOT);
        return versionId.startsWith("rd-")
                || versionId.startsWith("classic")
                || versionId.startsWith("indev")
                || versionId.startsWith("inf-")
                || versionId.startsWith("infdev")
                || versionId.startsWith("a")
                || versionId.startsWith("b");
    }

    private static void replaceJvmSystemProperty(
            @NonNull ArrayList<String> jvmArgs,
            @NonNull String key,
            @NonNull String value
    ) {
        String prefix = "-D" + key + "=";
        for (int i = jvmArgs.size() - 1; i >= 0; i--) {
            String arg = jvmArgs.get(i);
            if (arg != null && arg.startsWith(prefix)) {
                jvmArgs.remove(i);
            }
        }
        jvmArgs.add(0, prefix + value);
    }

    private static void sanitizeNetworkSystemPropertiesForGameLaunch() {
        String[] keysToClear = new String[]{
                "http.proxyHost",
                "http.proxyPort",
                "https.proxyHost",
                "https.proxyPort",
                "socksProxyHost",
                "socksProxyPort",
                "java.net.socks.username",
                "java.net.socks.password",
                "proxyHost",
                "proxyPort",
                "jdk.http.auth.tunneling.disabledSchemes",
                "jdk.http.auth.proxying.disabledSchemes",
                "sun.net.spi.nameservice.provider.1",
                "sun.net.spi.nameservice.provider.2",
                "sun.net.spi.nameservice.nameservers",
                "sun.net.spi.nameservice.domain",
                "networkaddress.cache.ttl",
                "networkaddress.cache.negative.ttl",
                "DROIDBRIDGE_P2P_PROXY",
                "DROIDBRIDGE_WEBRTC_PROXY"
        };

        for (String key : keysToClear) {
            try {
                System.clearProperty(key);
            } catch (Throwable ignored) {
            }
        }

        try {
            System.setProperty("java.net.useSystemProxies", "false");
            System.setProperty("java.net.preferIPv6Addresses", "false");
        } catch (Throwable ignored) {
        }

        safeAppendLog("VMLauncher: sanitized proxy/DNS system properties before Minecraft launch.");
    }


    @NonNull
    private static ArrayList<String> normalizeJvmArgsForVmLauncher(@NonNull List<String> originalArgs) {
        ArrayList<String> fixedArgs = new ArrayList<>(originalArgs.size() + 2);

        for (String arg : originalArgs) {
            if (arg == null || arg.trim().isEmpty()) continue;

            // Forge 1.18+ uses -p module path
            if (looksLikeStandaloneClasspath(arg)
                    && !hasJvmPathValueFlagImmediatelyBefore(fixedArgs)) {
                fixedArgs.add("-cp");
                safeAppendLog("Recovered missing -cp before launch classpath JVM argument.");
            }

            fixedArgs.add(arg);
        }

        return fixedArgs;
    }
    private static boolean hasJvmPathValueFlagImmediatelyBefore(@NonNull ArrayList<String> args) {
        if (args.isEmpty()) return false;
        String previous = args.get(args.size() - 1);
        return isClasspathFlag(previous) || isModulePathFlag(previous);
    }

    private static boolean hasClasspathFlagImmediatelyBefore(@NonNull ArrayList<String> args) {
        if (args.isEmpty()) return false;
        return isClasspathFlag(args.get(args.size() - 1));
    }

    private static boolean isClasspathFlag(@Nullable String value) {
        return "-cp".equals(value)
                || "-classpath".equals(value)
                || "--class-path".equals(value);
    }

    private static boolean isModulePathFlag(@Nullable String value) {
        return "-p".equals(value)
                || "--module-path".equals(value)
                || "--upgrade-module-path".equals(value);
    }

    @NonNull
    private static String resolveMainClassForVmLauncher(
            @NonNull LaunchPlan plan,
            @NonNull ArrayList<String> jvmArgs
    ) {
        String mainClass = plan.getMainClass();
        if (mainClass == null) mainClass = "";
        mainClass = mainClass.trim();

        if (!looksLikeStandaloneClasspath(mainClass)) {
            return mainClass;
        }

        if (!classpathAlreadyPresent(jvmArgs, mainClass)) {
            jvmArgs.add("-cp");
            jvmArgs.add(mainClass);
            safeAppendLog("Moved classpath-looking LaunchPlan mainClass back into JVM classpath args.");
        }

        String recoveredMainClass = readInstalledVersionMainClass(plan.getVersionId());
        if (!recoveredMainClass.isEmpty() && !looksLikeStandaloneClasspath(recoveredMainClass)) {
            safeAppendLog("Recovered LaunchPlan mainClass from installed version JSON: " + recoveredMainClass);
            return recoveredMainClass;
        }

        throw new IllegalStateException(
                "Launch plan mainClass is a classpath, not a Java class. "
                        + "The version argument builder is dropping or misplacing -cp for "
                        + plan.getVersionId()
                        + ". Send LaunchGame/LaunchPlan builder classes if this still happens."
        );
    }

    private static boolean classpathAlreadyPresent(
            @NonNull ArrayList<String> jvmArgs,
            @NonNull String classpath
    ) {
        for (int i = 1; i < jvmArgs.size(); i++) {
            if (isClasspathFlag(jvmArgs.get(i - 1)) && classpath.equals(jvmArgs.get(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeStandaloneClasspath(@Nullable String value) {
        if (value == null) return false;

        String arg = value.trim();
        if (arg.isEmpty() || arg.startsWith("-")) return false;

        boolean hasJar = arg.contains(".jar");
        boolean hasFilePath = arg.contains("/") || arg.contains("\\");
        boolean hasPathSeparator = arg.contains(File.pathSeparator);
        boolean hasMinecraftLibraryPath = arg.contains("/libraries/") || arg.contains("\\libraries\\");

        return hasJar && hasFilePath && (hasPathSeparator || hasMinecraftLibraryPath);
    }

    /**
     * Selects the NativeGLFW context ownership policy from the resolved base
     * Minecraft version.
     *
     * Minecraft 26.x preloads OpenGL on one thread and creates the game window
     * on a later render thread, so it needs an explicit linker-hook handoff.
     * All older supported versions, including LWJGLX/Cleanroom/BTA, need the
     * context to remain current until GL.createCapabilities() completes.
     */
    private static void configureNativeGlfwContextPolicy(@NonNull String versionId) {
        String baseVersion = resolveBaseMinecraftVersionId(versionId);
        boolean handoff = isMinecraft26OrNewer(baseVersion);

        try {
            if (handoff) {
                Os.setenv("DROIDBRIDGE_NATIVE_GLFW_CONTEXT_HANDOFF", "1", true);
            } else {
                Os.unsetenv("DROIDBRIDGE_NATIVE_GLFW_CONTEXT_HANDOFF");
            }
            String policy = handoff ? "handoff" : "keep-current";
            Logging.i(TAG, "NativeGLFW context policy=" + policy
                    + " version=" + versionId
                    + " base=" + baseVersion);
            safeAppendLog("DroidBridgeNativeGLFW: context policy=" + policy
                    + " version=" + versionId
                    + " base=" + baseVersion);
        } catch (Throwable throwable) {
            try {
                Os.unsetenv("DROIDBRIDGE_NATIVE_GLFW_CONTEXT_HANDOFF");
            } catch (Throwable ignored) {
            }
            Logging.e(TAG, "Unable to configure NativeGLFW context policy; defaulting to keep-current", throwable);
            safeAppendLog("DroidBridgeNativeGLFW: context policy setup failed; defaulting to keep-current: "
                    + throwable);
        }
    }

    @NonNull
    private static String resolveBaseMinecraftVersionId(@NonNull String versionId) {
        String current = versionId.trim();
        if (current.isEmpty()) return versionId;

        for (int depth = 0; depth <= 12; depth++) {
            try {
                File jsonFile = new File(
                        new File(new File(PathManager.DIR_MINECRAFT_HOME, "versions"), current),
                        current + ".json"
                );
                if (!jsonFile.isFile()) return current;

                JSONObject json = new JSONObject(readFileString(jsonFile));
                String inheritsFrom = json.optString("inheritsFrom", "").trim();
                if (!inheritsFrom.isEmpty() && !inheritsFrom.equals(current)) {
                    current = inheritsFrom;
                    continue;
                }

                String jar = json.optString("jar", "").trim();
                if (!jar.isEmpty() && !jar.equals(current)) {
                    current = jar;
                    continue;
                }

                String declaredId = json.optString("id", "").trim();
                return declaredId.isEmpty() ? current : declaredId;
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to resolve base Minecraft version for " + current, throwable);
                return current;
            }
        }

        return current;
    }

    private static boolean isMinecraft26OrNewer(@Nullable String rawVersionId) {
        if (rawVersionId == null) return false;
        String value = rawVersionId.trim().toLowerCase(java.util.Locale.ROOT);

        if (value.matches("^\\d{2}w\\d{2}[a-z].*$")
                || value.matches("^\\d{2}\\..*$")) {
            try {
                return Integer.parseInt(value.substring(0, 2)) >= 26;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }

        return false;
    }

    @NonNull
    private static String readInstalledVersionMainClass(@NonNull String versionId) {
        try {
            File versionJson = new File(
                    new File(new File(PathManager.DIR_MINECRAFT_HOME, "versions"), versionId),
                    versionId + ".json"
            );
            return readMainClassFromVersionJson(versionJson, 0);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to recover mainClass from installed version JSON", throwable);
            safeAppendLog("Unable to recover mainClass from installed version JSON: " + throwable);
            return "";
        }
    }

    @NonNull
    private static String readMainClassFromVersionJson(@NonNull File versionJson, int depth) throws Exception {
        if (depth > 8 || !versionJson.isFile()) return "";

        JSONObject json = new JSONObject(readFileString(versionJson));
        String mainClass = json.optString("mainClass", "").trim();
        if (!mainClass.isEmpty()) return mainClass;

        String inheritsFrom = json.optString("inheritsFrom", "").trim();
        if (inheritsFrom.isEmpty()) return "";

        File parentJson = new File(
                new File(new File(PathManager.DIR_MINECRAFT_HOME, "versions"), inheritsFrom),
                inheritsFrom + ".json"
        );
        return readMainClassFromVersionJson(parentJson, depth + 1);
    }

    @NonNull
    private static String readFileString(@NonNull File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int offset = 0;
            while (offset < data.length) {
                int read = input.read(data, offset, data.length - offset);
                if (read < 0) break;
                offset += read;
            }
            return new String(data, 0, offset, StandardCharsets.UTF_8);
        }
    }

    private static void setupExitHookIfAvailable(@NonNull Context context) {
        if (!ENABLE_NATIVE_EXIT_HOOK) {
            safeAppendLog("Native exit hook disabled.");
            return;
        }

        try {
            JREUtils.setupExitMethod(context.getApplicationContext());
            safeAppendLog("Native exit method registered.");
        } catch (Throwable throwable) {
            Logging.e(TAG, "setupExitMethod failed; launch will continue", throwable);
            safeAppendLog("setupExitMethod failed: " + throwable);
            return;
        }

        try {
            JREUtils.initializeGameExitHook();
            safeAppendLog("Native exit hook initialized.");
        } catch (Throwable throwable) {
            Logging.e(TAG, "initializeGameExitHook failed; launch will continue", throwable);
            safeAppendLog("initializeGameExitHook failed: " + throwable);
        }
    }



    private static void hardDisableLegacyForgeSplashIfNeeded(
            @NonNull LaunchPlan plan,
            @NonNull String reason
    ) {
        if (!isLegacyForgeLikePlan(plan)) return;

        File configDir = new File(plan.getGameDirectory(), "config");
        File splashFile = new File(configDir, "splash.properties");

        try {
            if (!configDir.exists() && !configDir.mkdirs()) {
                safeAppendLog("Unable to create Forge config directory for splash disable: " + configDir.getAbsolutePath());
                return;
            }

            java.util.LinkedHashMap<String, String> values = readColonOrEqualsOptionsFile(splashFile);
            boolean changed = false;
            changed |= setOption(values, "enabled", "false");
            changed |= setOption(values, "rotate", "false");
            changed |= setOption(values, "showMemory", "false");

            if (changed || !splashFile.isFile()) {
                writeEqualsOptionsFile(splashFile, values);
                safeAppendLog("DroidBridge RLCraft clean source v5: disabled legacy Forge splash (" + reason + "): " + splashFile.getAbsolutePath());
            } else {
                safeAppendLog("DroidBridge RLCraft clean source v5: legacy Forge splash already disabled (" + reason + "): " + splashFile.getAbsolutePath());
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to disable legacy Forge splash", throwable);
            safeAppendLog("Failed to disable legacy Forge splash: " + throwable);
        }
    }

    private static boolean isLegacyForgeLikePlan(@NonNull LaunchPlan plan) {
        StringBuilder combined = new StringBuilder();
        appendIdentity(combined, plan.getVersionId());
        appendIdentity(combined, plan.getMainClass());
        for (String arg : plan.getJvmArgs()) appendIdentity(combined, arg);
        for (String arg : plan.getGameArgs()) appendIdentity(combined, arg);

        String text = combined.toString().toLowerCase(java.util.Locale.ROOT);
        if (text.contains("neoforge") || text.contains("net.neoforged")) return false;

        boolean forgeLike = text.contains("net.minecraftforge")
                || text.contains("fmltweaker")
                || text.contains("launchwrapper")
                || text.contains("forge");
        if (!forgeLike) return false;

        int[] parsed = parseFirstMinecraftReleaseVersion(text);
        if (parsed != null) {
            return parsed[0] == 1 && parsed[1] <= 12;
        }

        return hasLegacyAwtStub(plan.getJvmArgs());
    }

    private static void appendIdentity(@NonNull StringBuilder out, @Nullable String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (out.length() > 0) out.append(' ');
        out.append(value);
    }

    @Nullable
    private static int[] parseFirstMinecraftReleaseVersion(@NonNull String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(^|[^0-9])(1)\\.(\\d+)(?:\\.(\\d+))?")
                .matcher(text);
        if (!matcher.find()) return null;

        try {
            int major = Integer.parseInt(matcher.group(2));
            int minor = Integer.parseInt(matcher.group(3));
            int patch = matcher.group(4) != null ? Integer.parseInt(matcher.group(4)) : 0;
            return new int[]{major, minor, patch};
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean hasLegacyAwtStub(@NonNull List<String> args) {
        for (String arg : args) {
            if (arg == null) continue;
            if (arg.startsWith("-Dcacio.managed.screensize=")) return true;
            if (arg.startsWith("-Dawt.toolkit=net.java.openjdk.cacio.ctc.")) return true;
            if (arg.startsWith("-Djava.awt.graphicsenv=net.java.openjdk.cacio.ctc.")) return true;
        }
        return false;
    }

    @NonNull
    private static java.util.LinkedHashMap<String, String> readColonOrEqualsOptionsFile(@NonNull File file) throws Exception {
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        if (!file.isFile()) return values;

        byte[] data;
        try (FileInputStream input = new FileInputStream(file)) {
            data = new byte[(int) file.length()];
            int offset = 0;
            while (offset < data.length) {
                int read = input.read(data, offset, data.length - offset);
                if (read < 0) break;
                offset += read;
            }
        }

        String text = new String(data, StandardCharsets.UTF_8);
        for (String line : text.split("\\r?\\n")) {
            if (line == null) continue;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            int equals = trimmed.indexOf('=');
            int colon = trimmed.indexOf(':');
            int index;
            if (equals >= 0 && colon >= 0) {
                index = Math.min(equals, colon);
            } else {
                index = Math.max(equals, colon);
            }
            if (index <= 0) continue;

            String key = trimmed.substring(0, index).trim();
            String value = trimmed.substring(index + 1).trim();
            if (!key.isEmpty()) values.put(key, value);
        }
        return values;
    }

    private static void writeEqualsOptionsFile(
            @NonNull File file,
            @NonNull java.util.LinkedHashMap<String, String> values
    ) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            out.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }

        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(out.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    @NonNull
    private static LaunchPlan applyRendererSpecificGameOptions(
            @NonNull Context context,
            @NonNull LaunchPlan plan,
            @NonNull RendererInterface renderer
    ) {
        if (isBetterThanAdventureLaunch(plan)
                && !BtaRendererPolicy.isBta8OrNewer(plan.getVersionId(), null)
                && isBtaSafeVisualRenderer(renderer)) {
            plan = appendJvmArgIfMissing(plan, "-Ddroidbridge.bta.disableGlShaders=true");
            safeAppendLog("BTA Android visual profile: LWJGL shader-only capability override enabled for "
                    + renderer.getRendererName());

            File options = new File(plan.getGameDirectory(), "options.txt");
            try {
                java.util.LinkedHashMap<String, String> values = readOptionsFile(options);
                boolean changed = false;

                // BTA's shader path links invalid programs through Android GLES wrappers.
                // Do NOT disable FBO here: with FBO hidden, BTA's fallback terrain/lightmap
                // path can render the world as a solid blue/grey/black scene. Keep FBOs
                // available and only force shaders off for wrapper renderers.
                changed |= setOption(values, "fboEnable", "true");
                changed |= setOption(values, "mipmapLevels", "0");
                changed |= setOption(values, "enableShaders", "false");

                if (changed) {
                    writeOptionsFile(options, values);
                    safeAppendLog("BTA Android visual profile applied: fboEnable=true, mipmapLevels=0, enableShaders=false");
                } else {
                    safeAppendLog("BTA Android visual profile already present.");
                }
            } catch (Throwable throwable) {
                Logging.e(TAG, "Failed to apply BTA Android visual profile", throwable);
                safeAppendLog("Failed to apply BTA Android visual profile: " + throwable);
            }
            return plan;
        }

        if (isBetterThanAdventureLaunch(plan)
                && BtaRendererPolicy.isBta8OrNewer(plan.getVersionId(), null)) {
            /*
             * Older DroidBridge BTA compatibility forced enableShaders=false for
             * wrapper renderers and that value persists in the instance options.
             * BTA 8's first-person/player pipeline depends on its modern shader
             * path, so repair the stale launcher-forced value for every BTA 8+
             * renderer instead of carrying the old BTA 7 workaround forward.
             */
            File options = new File(plan.getGameDirectory(), "options.txt");
            try {
                java.util.LinkedHashMap<String, String> values = readOptionsFile(options);
                boolean changed = false;
                changed |= setOption(values, "fboEnable", "true");
                changed |= setOption(values, "enableShaders", "true");
                if (changed) {
                    writeOptionsFile(options, values);
                    safeAppendLog("BTA 8+ visual profile repaired: fboEnable=true, enableShaders=true");
                } else {
                    safeAppendLog("BTA 8+ visual profile already native: shaders/FBO enabled for "
                            + renderer.getRendererName());
                }
            } catch (Throwable throwable) {
                Logging.e(TAG, "Failed to repair BTA 8+ visual profile", throwable);
                safeAppendLog("Failed to repair BTA 8+ visual profile: " + throwable);
            }
        }

        if (isLtwRenderer(renderer)) {
            File options = new File(plan.getGameDirectory(), "options.txt");
            try {
                java.util.LinkedHashMap<String, String> values = readOptionsFile(options);
                boolean changed = false;

                // LTW is an external renderer plugin and is already loading correctly.
                // The remaining crash is Minecraft's render-thread GL buffer mapping path.
                // Match the stable the prior implementation/viewport-push LTW profile from the working log:
                // low render distance, low simulation distance, no mipmaps, and fast graphics.
                changed |= capIntegerOption(values, "renderDistance", 4);
                changed |= capIntegerOption(values, "simulationDistance", 5);
                changed |= capIntegerOption(values, "mipmapLevels", 0);
                changed |= setOption(values, "graphicsMode", "fast");
                changed |= setOption(values, "clouds", "false");
                changed |= capFloatOption(values, "entityDistanceScaling", 0.75f);
                changed |= capIntegerOption(values, "biomeBlendRadius", 0);

                if (changed) {
                    writeOptionsFile(options, values);
                    safeAppendLog("LTW safe options applied: renderDistance<=4, simulationDistance<=5, mipmapLevels=0, graphicsMode=fast");
                } else {
                    safeAppendLog("LTW safe options already present.");
                }
            } catch (Throwable throwable) {
                Logging.e(TAG, "Failed to apply LTW safe options", throwable);
                safeAppendLog("Failed to apply LTW safe options: " + throwable);
            }
            return plan;
        }

        if (isFreedrenoKgslRenderer(renderer)) {
            File options = new File(plan.getGameDirectory(), "options.txt");
            try {
                java.util.LinkedHashMap<String, String> values = readOptionsFile(options);
                boolean changed = false;

                // v70: The the tested renderer path comparison log shows the working the tested renderer path run creating the
                // blocks atlas with mipmaps disabled ("blocks.png-atlas" ends in x0),
                // while DroidBridge creates it with one mip level (x1). On Adreno 740 the
                // remaining issue is visual corruption, not a load/context failure, so keep
                // the renderer route intact and remove the one concrete game-side delta.
                changed |= setOption(values, "mipmapLevels", "0");

                // v16: Direct Mesa/Freedreno must never run truly Unlimited, but
                // do not rewrite an intentional user cap to the current display Hz.
                // Rewriting 120 -> 60 when Android is in 60 Hz mode made "VSync off"
                // look refresh-locked and diverged from Freedreno KGSL reference behavior. Preserve
                // explicit 60/120/144/etc caps and only replace Unlimited/invalid.
                int stableUnlimitedCap = resolveNativeMesaUnlimitedFpsCap(context);
                changed |= stabilizeNativeMesaFpsOption(values, "maxFps", stableUnlimitedCap);

                if (changed) {
                    writeOptionsFile(options, values);
                    safeAppendLog("Freedreno KGSL v16 FPS profile applied: mipmapLevels=0, preserve enableVsync, maxFps unlimited->" + stableUnlimitedCap);
                } else {
                    safeAppendLog("Freedreno KGSL v16 FPS profile already present: mipmapLevels=0, explicit maxFps preserved, enableVsync preserved");
                }
            } catch (Throwable throwable) {
                Logging.e(TAG, "Failed to apply Freedreno KGSL visual/FPS profile", throwable);
                safeAppendLog("Failed to apply Freedreno KGSL visual/FPS profile: " + throwable);
            }
        }

        return plan;
    }

    @NonNull
    private static LaunchPlan appendJvmArgIfMissing(@NonNull LaunchPlan plan, @NonNull String arg) {
        for (String existing : plan.getJvmArgs()) {
            if (arg.equals(existing)) {
                return plan;
            }
        }
        ArrayList<String> args = new ArrayList<>(plan.getJvmArgs());
        args.add(arg);
        safeAppendLog("Added JVM arg: " + arg);
        return plan.copyWithJvmArgs(args);
    }

    private static boolean isLtwRenderer(@Nullable RendererInterface renderer) {
        if (renderer == null) return false;
        String combined = rendererFingerprint(renderer);
        return combined.contains("ltw") || combined.contains("libltw.so");
    }

    private static boolean isBtaSafeVisualRenderer(@Nullable RendererInterface renderer) {
        if (renderer == null) return false;
        String combined = rendererFingerprint(renderer);
        return combined.contains("gl4es")
                || combined.contains("opengles2")
                || combined.contains("krypton")
                || combined.contains("opengles3")
                || combined.contains("mobileglues")
                || combined.contains("litegles")
                || combined.contains("libgl4es");
    }

    private static boolean isBetterThanAdventureLaunch(@NonNull LaunchPlan plan) {
        String id = String.valueOf(plan.getVersionId()).trim().toLowerCase(java.util.Locale.ROOT);
        return id.startsWith("bta")
                || id.contains("betterthanadventure")
                || id.contains("better-than-adventure")
                || id.contains("better_than_adventure");
    }

    @NonNull
    private static String rendererFingerprint(@Nullable RendererInterface renderer) {
        if (renderer == null) return "";
        return (String.valueOf(renderer.getUniqueIdentifier()) + " "
                + String.valueOf(renderer.getRendererName()) + " "
                + String.valueOf(renderer.getRendererId()) + " "
                + String.valueOf(renderer.getRendererLibrary()) + " "
                + String.valueOf(renderer.getRendererEGL())).toLowerCase(java.util.Locale.ROOT);
    }


    private static boolean isFreedrenoKgslRenderer(@Nullable RendererInterface renderer) {
        if (renderer == null) return false;
        String combined = rendererFingerprint(renderer);
        return combined.contains("freedreno_kgsl")
                || combined.contains("freedreno kgsl")
                || combined.contains("1ad7249f-5784-4f00-bc72-174b3578ee46");
    }

    private static int resolveNativeMesaUnlimitedFpsCap(@NonNull Context context) {
        // V16: use a renderer-stable fallback for Minecraft's "Unlimited" value.
        // Do not tie this to the current Android refresh rate; users can switch
        // 60/120 Hz and still keep an explicit 120 FPS cap when VSync is off.
        return 120;
    }

    private static boolean stabilizeNativeMesaFpsOption(
            @NonNull java.util.LinkedHashMap<String, String> values,
            @NonNull String key,
            int unlimitedReplacement
    ) {
        unlimitedReplacement = Math.max(30, Math.min(240, unlimitedReplacement));
        String current = values.get(key);
        int currentValue;
        try {
            currentValue = current != null ? Integer.parseInt(current.trim()) : Integer.MAX_VALUE;
        } catch (Throwable ignored) {
            currentValue = Integer.MAX_VALUE;
        }

        // Minecraft 1.20.x commonly stores Unlimited as 260. Preserve real user
        // caps such as 60 or 120, even when they are above/below the current
        // Android display refresh. Only replace missing, invalid, zero/negative,
        // or effectively-unlimited values.
        if (current == null || current.trim().isEmpty() || currentValue <= 0 || currentValue >= 255) {
            values.put(key, String.valueOf(unlimitedReplacement));
            return true;
        }
        return false;
    }

    private static java.util.LinkedHashMap<String, String> readOptionsFile(@NonNull File options) throws Exception {
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        if (!options.isFile()) return values;

        byte[] data;
        try (FileInputStream input = new FileInputStream(options)) {
            data = new byte[(int) options.length()];
            int offset = 0;
            while (offset < data.length) {
                int read = input.read(data, offset, data.length - offset);
                if (read < 0) break;
                offset += read;
            }
        }

        String text = new String(data, StandardCharsets.UTF_8);
        for (String line : text.split("\\r?\\n")) {
            if (line == null) continue;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            int index = trimmed.indexOf(':');
            if (index <= 0) continue;
            String key = trimmed.substring(0, index).trim();
            String value = trimmed.substring(index + 1).trim();
            if (!key.isEmpty()) values.put(key, value);
        }
        return values;
    }

    private static void writeOptionsFile(
            @NonNull File options,
            @NonNull java.util.LinkedHashMap<String, String> values
    ) throws Exception {
        File parent = options.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            out.append(entry.getKey()).append(':').append(entry.getValue()).append('\n');
        }

        try (FileOutputStream output = new FileOutputStream(options, false)) {
            output.write(out.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static boolean setOption(
            @NonNull java.util.LinkedHashMap<String, String> values,
            @NonNull String key,
            @NonNull String value
    ) {
        String current = values.get(key);
        if (current != null && value.equalsIgnoreCase(current.trim())) return false;
        values.put(key, value);
        return true;
    }

    private static boolean capIntegerOption(
            @NonNull java.util.LinkedHashMap<String, String> values,
            @NonNull String key,
            int max
    ) {
        String current = values.get(key);
        int currentValue;
        try {
            currentValue = current != null ? Integer.parseInt(current.trim()) : Integer.MAX_VALUE;
        } catch (Throwable ignored) {
            currentValue = Integer.MAX_VALUE;
        }

        if (currentValue <= max) return false;
        values.put(key, String.valueOf(max));
        return true;
    }

    private static boolean capFloatOption(
            @NonNull java.util.LinkedHashMap<String, String> values,
            @NonNull String key,
            float max
    ) {
        String current = values.get(key);
        float currentValue;
        try {
            currentValue = current != null ? Float.parseFloat(current.trim()) : Float.MAX_VALUE;
        } catch (Throwable ignored) {
            currentValue = Float.MAX_VALUE;
        }

        if (currentValue <= max) return false;
        values.put(key, trimFloat(max));
        return true;
    }

    @NonNull
    private static String trimFloat(float value) {
        if (value == (long) value) return String.valueOf((long) value);
        return String.valueOf(value);
    }

    private static void ensureLauncherWindowOptionsIfMissing(@NonNull File options) {
        try {
            java.util.LinkedHashMap<String, String> values = readOptionsFile(options);
            boolean changed = false;

            changed |= setOptionIfMissing(values, "fullscreen", "false");
            changed |= setOptionIfMissing(values, "overrideWidth",
                    String.valueOf(Math.max(1, org.lwjgl.glfw.CallbackBridge.windowWidth)));
            changed |= setOptionIfMissing(values, "overrideHeight",
                    String.valueOf(Math.max(1, org.lwjgl.glfw.CallbackBridge.windowHeight)));
            changed |= setOptionIfMissing(values, "fboEnable", "true");

            if (changed) {
                writeOptionsFile(options, values);
                safeAppendLog("Added launcher window defaults to seeded options.txt");
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to add launcher window defaults to options.txt", throwable);
            safeAppendLog("Failed to add launcher window defaults to options.txt: " + throwable);
        }
    }

    private static boolean setOptionIfMissing(
            @NonNull java.util.LinkedHashMap<String, String> values,
            @NonNull String key,
            @NonNull String value
    ) {
        String current = values.get(key);
        if (current != null && current.trim().length() > 0) return false;
        values.put(key, value);
        return true;
    }

    private static void writeOptions(@NonNull Context context, @NonNull LaunchPlan plan) {
        File options = new File(plan.getGameDirectory(), "options.txt");
        if (options.exists()) {
            if (DefaultMinecraftOptionsInstaller.isDroidBridgeSeededDefaultOptions(plan.getGameDirectory())) {
                ensureLauncherWindowOptionsIfMissing(options);
            }
            return;
        }

        if (DefaultMinecraftOptionsInstaller.tryInstallIfMissingForLaunch(
                context,
                plan.getGameDirectory(),
                plan.getVersionId()
        )) {
            ensureLauncherWindowOptionsIfMissing(options);
            safeAppendLog("Seeded version-specific default options.txt for " + plan.getVersionId());
            return;
        }

        // Last-resort fallback for unknown/custom version ids. Keep this tiny and safe so
        // Minecraft still starts even if no preset matches, but normal instances now use
        // the version-specific defaults from assets/minecraft_defaults/.
        String text = "fullscreen:false\n" +
                "overrideWidth:" + Math.max(1, org.lwjgl.glfw.CallbackBridge.windowWidth) + "\n" +
                "overrideHeight:" + Math.max(1, org.lwjgl.glfw.CallbackBridge.windowHeight) + "\n" +
                "fboEnable:true\n";
        try {
            File parent = options.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream output = new FileOutputStream(options)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
            }
            safeAppendLog("Seeded fallback default options.txt for unknown/custom version " + plan.getVersionId());
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to write default options.txt", throwable);
            safeAppendLog("Failed to write options.txt: " + throwable);
        }
    }

    private static void logJvmArg(@NonNull String arg) {
        Logging.i(TAG, "JVMArg: " + arg);
        safeAppendLog("JVMArg: " + arg);
    }

    private static void notify(@Nullable StatusListener listener, @NonNull String status) {
        if (listener != null) listener.onStatus(status);
        Logging.i(TAG, status);
        safeAppendLog(status);
    }

    private static void resetInstallerLatestLogs(
            @NonNull String taskName,
            @NonNull File runtimeDirectory,
            @NonNull File workingDirectory,
            @NonNull List<String> javaArgs
    ) {
        String header = "==== DroidBridge installer log ====" + "\n"
                + "task=" + taskName + "\n"
                + "runtime=" + runtimeDirectory.getAbsolutePath() + "\n"
                + "workingDirectory=" + workingDirectory.getAbsolutePath() + "\n"
                + "args=" + javaArgs + "\n";

        writeLogFile(getLauncherLatestLogFile(), header, false);
        writeLogFile(getMinecraftLatestLogTxtFile(), header, false);
        writeLogFile(getMinecraftLatestDotLogFile(), header, false);

        Logging.i(TAG, "Installer latest logs reset for " + taskName);
    }

    private static void safeAppendLog(@NonNull String text) {
        String value = text.endsWith("\n") ? text : text + "\n";

        boolean wrote = false;
        wrote |= writeLogFile(getLauncherLatestLogFile(), value, true);
        wrote |= writeLogFile(getMinecraftLatestLogTxtFile(), value, true);
        wrote |= writeLogFile(getMinecraftLatestDotLogFile(), value, true);

        if (!wrote) {
            Logging.i(TAG, text);
        }
    }

    @NonNull
    private static File getLauncherLatestLogFile() {
        return new File(PathManager.DIR_LAUNCHER_LOG, "latestlog.txt");
    }

    @NonNull
    private static File getMinecraftLatestLogTxtFile() {
        return new File(new File(PathManager.DIR_MINECRAFT_HOME, "logs"), "latestlog.txt");
    }

    @NonNull
    private static File getMinecraftLatestDotLogFile() {
        return new File(new File(PathManager.DIR_MINECRAFT_HOME, "logs"), "latest.log");
    }

    private static boolean writeLogFile(@NonNull File file, @NonNull String value, boolean append) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false;
            }

            try (FileOutputStream output = new FileOutputStream(file, append)) {
                output.write(value.getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}

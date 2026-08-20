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

package ca.dnamobile.droidbridgelauncher.ui.version;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;
import ca.dnamobile.droidbridgelauncher.runtime.Architecture;

/**
 * Quiet external installer runner for Forge/NeoForge.
 *
 * Forge's binary patcher can print thousands of class names and patch checksum
 * lines. Logging every line through Logcat/latestlog makes Android installs look
 * like they are frozen for 60-90 seconds. This runner still drains the process
 * output, but it only logs useful status/error lines and throttles UI updates.
 */
final class InstallerProcessRunner {
    private static final String TAG = "InstallerProcessRunner";
    private static final long HEARTBEAT_SECONDS = 5L;
    private static final long TIMEOUT_MINUTES = 15L;
    private static final int TAIL_LIMIT = 120;

    private InstallerProcessRunner() {
    }

    static int launch(
            @NonNull Context context,
            @NonNull String taskName,
            @NonNull File runtimeDirectory,
            @NonNull File workingDirectory,
            @NonNull List<String> javaArgs,
            int startProgress,
            int endProgress,
            @Nullable MinecraftVersionInstaller.InstallProgressListener listener
    ) throws Exception {
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

        ArrayList<String> effectiveArgs = buildEffectiveArgs(runtimeDirectory, workingDirectory, javaArgs);
        notify(listener, startProgress, "Starting " + friendlyName(taskName) + " installer JVM...");

        try {
            int directExit = runProcess(
                    context,
                    taskName,
                    javaBinary,
                    workingDirectory,
                    runtimeDirectory,
                    effectiveArgs,
                    false,
                    startProgress,
                    endProgress,
                    listener
            );
            if (directExit != 126 && directExit != 127) return directExit;
            Logging.i(TAG, "Direct installer exec returned " + directExit + "; retrying through Android linker.");
        } catch (IOException startFailure) {
            Logging.i(TAG, "Direct installer exec failed; retrying through Android linker: " + startFailure.getMessage());
        }

        File linker = resolveSystemLinker();
        if (linker == null || !linker.isFile()) {
            throw new IOException("Direct Java start was blocked and no Android system linker was found.");
        }
        notify(listener, Math.min(100, startProgress + 1), "Retrying installer through Android system linker...");
        return runProcess(
                context,
                taskName,
                javaBinary,
                workingDirectory,
                runtimeDirectory,
                effectiveArgs,
                true,
                startProgress,
                endProgress,
                listener
        );
    }

    @NonNull
    private static ArrayList<String> buildEffectiveArgs(
            @NonNull File runtimeDirectory,
            @NonNull File workingDirectory,
            @NonNull List<String> javaArgs
    ) {
        ArrayList<String> args = new ArrayList<>();
        addJvmArgIfMissing(args, "-Djava.home=" + runtimeDirectory.getAbsolutePath());
        addJvmArgIfMissing(args, "-Djava.library.path=" + buildLdLibraryPath(runtimeDirectory));
        addJvmArgIfMissing(args, "-Dsun.boot.library.path=" + buildLdLibraryPath(runtimeDirectory));
        addJvmArgIfMissing(args, "-Duser.dir=" + workingDirectory.getAbsolutePath());

        // Quiet common Java loggers. The Forge patcher can still print directly,
        // so the output reader below also filters aggressively.
        addJvmArgIfMissing(args, "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn");
        addJvmArgIfMissing(args, "-Dlog4j2.level=warn");
        addJvmArgIfMissing(args, "-Djava.util.logging.ConsoleHandler.level=WARNING");

        args.addAll(javaArgs);
        return args;
    }

    private static int runProcess(
            @NonNull Context context,
            @NonNull String taskName,
            @NonNull File javaBinary,
            @NonNull File workingDirectory,
            @NonNull File runtimeDirectory,
            @NonNull List<String> effectiveArgs,
            boolean useSystemLinker,
            int startProgress,
            int endProgress,
            @Nullable MinecraftVersionInstaller.InstallProgressListener listener
    ) throws Exception {
        ArrayList<String> command = new ArrayList<>();
        if (useSystemLinker) {
            File linker = resolveSystemLinker();
            if (linker == null || !linker.isFile()) {
                throw new IOException("No Android system linker found for installer fallback.");
            }
            command.add(linker.getAbsolutePath());
        }
        command.add(javaBinary.getAbsolutePath());
        command.addAll(effectiveArgs);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory);
        builder.redirectErrorStream(true);

        Map<String, String> env = builder.environment();
        sanitizeEnvironment(env);
        env.put("JAVA_HOME", runtimeDirectory.getAbsolutePath());
        env.put("HOME", PathManager.DIR_MINECRAFT_HOME);
        env.put("TMPDIR", PathManager.DIR_CACHE.getAbsolutePath());
        env.put("LD_LIBRARY_PATH", buildLdLibraryPath(runtimeDirectory));
        String path = new File(runtimeDirectory, "bin").getAbsolutePath();
        String oldPath = env.get("PATH");
        if (oldPath != null && !oldPath.isEmpty()) path += ":" + oldPath;
        env.put("PATH", path);

        File heapTaggingPreload = resolveHeapTaggingPreloadLibrary(context);
        if (heapTaggingPreload.isFile()) env.put("LD_PRELOAD", heapTaggingPreload.getAbsolutePath());
        else env.remove("LD_PRELOAD");

        String launchMode = useSystemLinker ? "system-linker" : "direct-exec";
        Logging.i(TAG, "Installer launch mode=" + launchMode + " task=" + taskName);
        Logging.i(TAG, "Installer cwd=" + workingDirectory.getAbsolutePath());

        Process process = builder.start();
        ArrayDeque<String> tail = new ArrayDeque<>(TAIL_LIMIT);
        AtomicInteger drainedLines = new AtomicInteger(0);
        AtomicInteger noisyLines = new AtomicInteger(0);
        AtomicInteger lastUiBucket = new AtomicInteger(-1);

        Thread outputThread = new Thread(() -> drainOutput(
                taskName,
                process,
                tail,
                drainedLines,
                noisyLines,
                lastUiBucket,
                listener
        ), "QuietInstallerOutput-" + taskName);
        outputThread.setDaemon(true);
        outputThread.start();

        long startMs = System.currentTimeMillis();
        long timeoutMs = TimeUnit.MINUTES.toMillis(TIMEOUT_MINUTES);
        int safeStart = Math.max(0, Math.min(100, startProgress));
        int safeEnd = Math.max(safeStart, Math.min(100, endProgress));
        String name = friendlyName(taskName);

        while (true) {
            if (process.waitFor(HEARTBEAT_SECONDS, TimeUnit.SECONDS)) {
                outputThread.join(5000L);
                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    logTail(taskName, tail);
                }
                notify(listener, safeEnd, name + " installer JVM exited with code " + exitCode + ".");
                Logging.i(TAG, "Installer finished task=" + taskName
                        + " mode=" + launchMode
                        + " exitCode=" + exitCode
                        + " drainedLines=" + drainedLines.get()
                        + " filteredNoise=" + noisyLines.get());
                return exitCode;
            }

            long elapsedMs = System.currentTimeMillis() - startMs;
            long elapsedSeconds = TimeUnit.MILLISECONDS.toSeconds(elapsedMs);
            int progressSpan = Math.max(0, safeEnd - safeStart);
            int progressStep = (int) Math.min(progressSpan, elapsedSeconds / HEARTBEAT_SECONDS);
            int progress = safeStart + progressStep;
            notify(listener, progress, name + " installer is patching files... (" + elapsedSeconds + "s)");

            if (elapsedMs >= timeoutMs) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
                outputThread.join(5000L);
                logTail(taskName, tail);
                throw new IllegalStateException(name + " installer timed out after " + TIMEOUT_MINUTES + " minutes.");
            }
        }
    }

    private static void drainOutput(
            @NonNull String taskName,
            @NonNull Process process,
            @NonNull ArrayDeque<String> tail,
            @NonNull AtomicInteger drainedLines,
            @NonNull AtomicInteger noisyLines,
            @NonNull AtomicInteger lastUiBucket,
            @Nullable MinecraftVersionInstaller.InstallProgressListener listener
    ) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                drainedLines.incrementAndGet();
                rememberTail(tail, line);

                if (isNoisyInstallerLine(line)) {
                    int filtered = noisyLines.incrementAndGet();
                    int bucket = filtered / 500;
                    if (bucket > 0 && lastUiBucket.getAndSet(bucket) != bucket) {
                        notify(listener, 0, friendlyName(taskName) + " installer is applying patches... (" + filtered + " lines)");
                    }
                    continue;
                }

                Logging.i(TAG, "[installer:" + taskName + "] " + line);
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Installer output reader failed for " + taskName, throwable);
        }
    }

    private static void rememberTail(@NonNull ArrayDeque<String> tail, @NonNull String line) {
        synchronized (tail) {
            if (tail.size() >= TAIL_LIMIT) tail.removeFirst();
            tail.addLast(line);
        }
    }

    private static void logTail(@NonNull String taskName, @NonNull ArrayDeque<String> tail) {
        synchronized (tail) {
            Logging.i(TAG, "Last installer output lines for " + taskName + ":");
            for (String line : tail) {
                Logging.i(TAG, "[installer-tail:" + taskName + "] " + line);
            }
        }
    }

    private static boolean isNoisyInstallerLine(@Nullable String raw) {
        if (raw == null) return true;
        String line = raw.trim();
        if (line.isEmpty()) return true;
        String lower = line.toLowerCase(Locale.ROOT);

        if (lower.contains("error")
                || lower.contains("exception")
                || lower.contains("failed")
                || lower.contains("warning")
                || lower.contains("successfully installed")
                || lower.contains("processor failed")) {
            return false;
        }

        return lower.endsWith(".class")
                || lower.endsWith(".class/")
                || lower.endsWith("/")
                || lower.startsWith("reading patch ")
                || lower.startsWith("patching ")
                || lower.startsWith("checksum:")
                || lower.startsWith("copying ")
                || lower.startsWith("extracting ")
                || lower.startsWith("scanning ")
                || lower.startsWith("java.io.file ");
    }

    private static void sanitizeEnvironment(@NonNull Map<String, String> env) {
        String[] keys = new String[]{
                "CLASSPATH", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
                "DROIDBRIDGE_RENDERER", "DROIDBRIDGE_EGL", "DROIDBRIDGE_EGL_LIBRARY", "DROIDBRIDGE_EGL_LIBRARY",
                "DROIDBRIDGE_RENDERER_LIBRARY", "DROIDBRIDGE_RENDERER_LIBRARY", "OSMESA_LIB", "LIB_MESA_NAME",
                "MESA_LOADER_DRIVER_OVERRIDE", "GALLIUM_DRIVER", "VK_ICD_FILENAMES", "VK_DRIVER_FILES",
                "DRIVER_PATH", "LIBGL_DRIVERS_PATH", "EGL_DRIVERS_PATH", "LTW_NEVER_FLUSH_BUFFERS",
                "LTW_COHERENT_DYNAMIC_STORAGE"
        };
        for (String key : keys) env.remove(key);
    }

    @Nullable
    private static File resolveSystemLinker() {
        File linker64 = new File("/system/bin/linker64");
        if (linker64.isFile()) return linker64;
        File linker = new File("/system/bin/linker");
        return linker.isFile() ? linker : null;
    }

    @NonNull
    private static File resolveHeapTaggingPreloadLibrary(@NonNull Context context) {
        String nativeLibraryDir = context.getApplicationInfo() != null ? context.getApplicationInfo().nativeLibraryDir : null;
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
    private static String buildLdLibraryPath(@NonNull File runtimeDirectory) {
        File runtimeLibDir = resolveRuntimeLibDir(runtimeDirectory);
        File jvmLibraryDir = resolveJvmLibraryDir(runtimeLibDir);
        StringBuilder out = new StringBuilder();
        appendPath(out, jvmLibraryDir);
        appendPath(out, new File(runtimeLibDir, "jli"));
        appendPath(out, runtimeLibDir);
        String nativeLibDir = PathManager.DIR_NATIVE_LIB;
        if (nativeLibDir != null && !nativeLibDir.isEmpty()) appendPath(out, new File(nativeLibDir));
        return out.toString();
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
    private static ArrayList<String> getRuntimeArchCandidates() {
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

    private static void appendPath(@NonNull StringBuilder builder, @NonNull File file) {
        if (!file.isDirectory()) return;
        String path = file.getAbsolutePath();
        if (containsPath(builder.toString(), path)) return;
        if (builder.length() > 0) builder.append(':');
        builder.append(path);
    }

    private static boolean containsPath(@NonNull String pathList, @NonNull String path) {
        if (pathList.isEmpty()) return false;
        for (String part : pathList.split(":")) {
            if (path.equals(part)) return true;
        }
        return false;
    }

    private static void addJvmArgIfMissing(@NonNull ArrayList<String> args, @NonNull String value) {
        int equals = value.indexOf('=');
        if (equals > 0) {
            String key = value.substring(0, equals + 1);
            for (String arg : args) if (arg != null && arg.startsWith(key)) return;
        } else if (args.contains(value)) {
            return;
        }
        args.add(value);
    }

    @NonNull
    private static String friendlyName(@NonNull String taskName) {
        String value = taskName.toLowerCase(Locale.ROOT);
        if (value.contains("neoforge")) return "NeoForge";
        if (value.contains("forge")) return "Forge";
        return "Loader";
    }

    private static void notify(
            @Nullable MinecraftVersionInstaller.InstallProgressListener listener,
            int progress,
            @NonNull String message
    ) {
        if (listener != null && progress > 0) listener.onProgress(Math.max(0, Math.min(100, progress)), message);
        Logging.i(TAG, message);
    }
}

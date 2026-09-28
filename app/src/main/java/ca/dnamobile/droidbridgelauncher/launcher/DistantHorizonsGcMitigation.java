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
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.settings.MemoryAllocationUtils;

/**
 * Selects a Distant Horizons garbage collector without changing non-DH launches.
 *
 * Unconfigured instances use a device-aware recommendation. Lower-end or 32-bit
 * devices fall back to G1GC, while capable 64-bit devices use ZGC. A saved
 * per-instance choice overrides the performance recommendation, except when ZGC
 * is impossible because the selected runtime is older than Java 17 or the device
 * does not expose a 64-bit ABI.
 */
public final class DistantHorizonsGcMitigation {
    private static final String TAG = "DistantHorizonsGc";

    public static final int ZGC_RECOMMENDED_MIN_TOTAL_RAM_MB = 6 * 1024;
    public static final int ZGC_RECOMMENDED_MIN_CPU_CORES = 6;

    private static final String ZGC_PROBE_PREFS = "droidbridge_zgc_probe";
    private static final int ZGC_PROBE_SCHEMA = 3;

    // Only present when the external bin/java probe could not make a reliable
    // decision (for example Android killed it with SIGSYS).  The real Minecraft
    // VMLauncher path then proves itself by surviving this short startup window.
    public static final String ZGC_STARTUP_GUARD_PROPERTY = "droidbridge.zgc.startup.guard";
    private static final String ZGC_STARTUP_GUARD_ARG_PREFIX =
            "-D" + ZGC_STARTUP_GUARD_PROPERTY + "=";
    private static final long ZGC_STARTUP_GUARD_DELAY_MS = 6_000L;

    private static final int PROBE_FAIL = 0;
    private static final int PROBE_PASS = 1;
    private static final int PROBE_INCONCLUSIVE = -1;

    private DistantHorizonsGcMitigation() {
    }

    @NonNull
    public static Result applyIfNeeded(
            @NonNull Context context,
            @NonNull LaunchPlan plan,
            @NonNull InstanceLaunchSettings.Settings settings
    ) {
        ArrayList<String> messages = new ArrayList<>();
        File gameDir = plan.getGameDirectory();

        try {
            if (!hasDistantHorizons(gameDir)) {
                messages.add("Skipped: Distant Horizons=false");
                logMessages(messages);
                return new Result(plan, false, InstanceLaunchSettings.GC_MODE_DEFAULT, messages);
            }

            int runtimeMajor = resolveRuntimeMajor(plan.getRuntimeDirectory());
            DeviceRecommendation recommendation = getDeviceRecommendation(context);
            String requestedMode = InstanceLaunchSettings.sanitizeGcMode(settings.gcMode);
            boolean automatic = InstanceLaunchSettings.GC_MODE_DEFAULT.equals(requestedMode);
            String effectiveMode = automatic ? recommendation.recommendedGcMode : requestedMode;

            if (InstanceLaunchSettings.GC_MODE_ZGC.equals(effectiveMode)
                    && (runtimeMajor < 17 || !recommendation.is64Bit)) {
                effectiveMode = InstanceLaunchSettings.GC_MODE_G1GC;
                messages.add("Fell back to G1GC because ZGC requires Java 17+ and a 64-bit runtime/device.");
            }

            String startupGuardKey = null;
            if (InstanceLaunchSettings.GC_MODE_ZGC.equals(effectiveMode)) {
                String maxHeapArg = findJvmArgWithPrefix(plan.getJvmArgs(), "-Xmx");
                ZgcProbeDecision probeDecision = getOrRunZgcProbe(
                        context,
                        plan.getRuntimeDirectory(),
                        runtimeMajor,
                        maxHeapArg
                );
                messages.add("ZGC probe " + (probeDecision.cached ? "cache" : "run")
                        + ": " + probeDecision.verdictName()
                        + "; " + probeDecision.summary);

                if (probeDecision.verdict == PROBE_FAIL) {
                    effectiveMode = InstanceLaunchSettings.GC_MODE_G1GC;
                    messages.add("Fell back to G1GC because ZGC was conclusively rejected by the selected runtime or a previous real VMLauncher startup did not survive.");
                } else if (probeDecision.verdict == PROBE_INCONCLUSIVE) {
                    // Android can SIGSYS an exec'ed bin/java even on devices that
                    // run ZGC correctly through the actual VMLauncher/JLI path.
                    // Keep the requested ZGC for this launch and let the persisted
                    // real-VM startup guard decide for the next one.
                    startupGuardKey = probeDecision.cacheKey;
                    messages.add("ZGC external probe was inconclusive; keeping ZGC and arming the real VMLauncher startup guard.");
                }
            }

            ArrayList<String> args = new ArrayList<>(plan.getJvmArgs());
            int removed = removeConflictingGcArgs(args);

            if (InstanceLaunchSettings.GC_MODE_ZGC.equals(effectiveMode)) {
                insertBeforeClasspath(args, "-XX:+UseZGC");

                // Java 21 supports the ZGenerational toggle. Java 24+ made
                // generational ZGC the only mode and obsoleted this flag.
                if (runtimeMajor >= 21 && runtimeMajor < 24) {
                    insertBeforeClasspath(args, "-XX:+ZGenerational");
                    messages.add("Applied ZGC + Generational ZGC for Distant Horizons on Java "
                            + runtimeMajor + ".");
                } else {
                    messages.add("Applied ZGC for Distant Horizons on Java " + runtimeMajor + ".");
                }
                if (startupGuardKey != null) {
                    insertBeforeClasspath(args, ZGC_STARTUP_GUARD_ARG_PREFIX + startupGuardKey);
                }
            } else {
                insertBeforeClasspath(args, "-XX:+UseG1GC");
                messages.add("Applied G1GC for Distant Horizons on Java " + runtimeMajor + ".");
            }

            if (automatic) {
                messages.add("Per-instance GC mode is unset; automatic device recommendation selected "
                        + displayGcMode(effectiveMode) + ".");
                messages.add("Device profile: " + recommendation.summary);
            } else {
                messages.add("Per-instance GC mode requested: " + displayGcMode(requestedMode) + ".");
            }

            if (removed > 0) {
                messages.add("Removed " + removed + " conflicting/old GC argument(s).");
            }
            messages.add("Distant Horizons=true; effective GC=" + displayGcMode(effectiveMode) + ".");

            LaunchPlan patchedPlan = plan.copyWithJvmArgs(args);
            logMessages(messages);
            return new Result(patchedPlan, true, effectiveMode, messages);
        } catch (Throwable throwable) {
            messages.add("Failed: " + throwable.getClass().getSimpleName() + ": "
                    + (throwable.getMessage() == null ? "" : throwable.getMessage()));
            Logging.e(TAG, "Failed to apply Distant Horizons GC mitigation", throwable);
            logMessages(messages);
            return new Result(plan, false, InstanceLaunchSettings.GC_MODE_DEFAULT, messages);
        }
    }

    @NonNull
    public static DeviceRecommendation getDeviceRecommendation(@NonNull Context context) {
        int totalMemoryMb;
        try {
            totalMemoryMb = MemoryAllocationUtils.getTotalMemoryMb(context);
        } catch (Throwable throwable) {
            totalMemoryMb = 0;
            Logging.e(TAG, "Unable to read device memory for GC recommendation", throwable);
        }

        int cpuCores;
        try {
            cpuCores = Math.max(1, Runtime.getRuntime().availableProcessors());
        } catch (Throwable throwable) {
            cpuCores = 1;
        }

        boolean is64Bit = Build.SUPPORTED_64_BIT_ABIS != null
                && Build.SUPPORTED_64_BIT_ABIS.length > 0;

        String recommendedMode;
        String reason;
        if (!is64Bit) {
            recommendedMode = InstanceLaunchSettings.GC_MODE_G1GC;
            reason = "a 64-bit ABI was not reported";
        } else if (totalMemoryMb > 0 && totalMemoryMb < ZGC_RECOMMENDED_MIN_TOTAL_RAM_MB) {
            recommendedMode = InstanceLaunchSettings.GC_MODE_G1GC;
            reason = "physical RAM is below 6 GB";
        } else if (cpuCores < ZGC_RECOMMENDED_MIN_CPU_CORES) {
            recommendedMode = InstanceLaunchSettings.GC_MODE_G1GC;
            reason = "the device reports fewer than 6 CPU cores";
        } else {
            recommendedMode = InstanceLaunchSettings.GC_MODE_ZGC;
            reason = "the device meets the launcher recommendation for ZGC";
        }

        String memoryText = totalMemoryMb > 0 ? totalMemoryMb + " MB RAM" : "unknown RAM";
        String summary = memoryText
                + ", " + cpuCores + " CPU core(s), "
                + (is64Bit ? "64-bit" : "32-bit")
                + "; recommended " + displayGcMode(recommendedMode)
                + " because " + reason + ".";

        return new DeviceRecommendation(
                recommendedMode,
                totalMemoryMb,
                cpuCores,
                is64Bit,
                reason,
                summary
        );
    }

    @NonNull
    private static ZgcProbeDecision getOrRunZgcProbe(
            @NonNull Context context,
            @NonNull File runtimeDirectory,
            int runtimeMajor,
            @Nullable String maxHeapArg
    ) {
        String cacheKey = buildZgcProbeCacheKey(runtimeDirectory, runtimeMajor, maxHeapArg);
        SharedPreferences prefs = context.getSharedPreferences(ZGC_PROBE_PREFS, Context.MODE_PRIVATE);
        String cachedValue = prefs.getString(cacheKey, null);

        if (cachedValue != null) {
            if (cachedValue.startsWith("pass|")) {
                return new ZgcProbeDecision(
                        PROBE_PASS,
                        true,
                        cacheKey,
                        cachedSummary(cachedValue, "real/runtime ZGC pass")
                );
            }
            if (cachedValue.startsWith("fail|")) {
                return new ZgcProbeDecision(
                        PROBE_FAIL,
                        true,
                        cacheKey,
                        cachedSummary(cachedValue, "real/runtime ZGC failure")
                );
            }
            if (cachedValue.startsWith("pending|")) {
                String summary = "previous real VMLauncher ZGC startup did not survive the "
                        + ZGC_STARTUP_GUARD_DELAY_MS + " ms startup guard; "
                        + cachedSummary(cachedValue, "pending launch");
                // A pending marker can only survive when the Android process dies
                // before the guard thread can confirm HotSpot startup.  Persist the
                // failure so the very next launch automatically uses G1GC.
                prefs.edit().putString(cacheKey, "fail|" + summary).commit();
                return new ZgcProbeDecision(PROBE_FAIL, true, cacheKey, summary);
            }
        }

        JavaGameLauncher.ZgcProbeResult probe = JavaGameLauncher.probeZgcSupport(
                context,
                runtimeDirectory,
                runtimeMajor,
                maxHeapArg
        );

        String summary = "runtime=" + runtimeDirectory.getName()
                + ", heap=" + (maxHeapArg == null ? "launcher-default" : maxHeapArg)
                + ", mode=" + probe.launchMode
                + ", exit=" + probe.exitCode
                + ", detail=" + sanitizeProbeDetail(probe.detail);

        if (probe.supported) {
            prefs.edit().putString(cacheKey, "pass|" + summary).commit();
            return new ZgcProbeDecision(PROBE_PASS, false, cacheKey, summary);
        }
        if (probe.conclusive) {
            prefs.edit().putString(cacheKey, "fail|" + summary).commit();
            return new ZgcProbeDecision(PROBE_FAIL, false, cacheKey, summary);
        }

        // Do not cache an external-process failure as a ZGC failure.  Android's
        // SIGSYS/seccomp behavior is launch-path-specific.  The real-VM guard is
        // what converts an actual VMLauncher startup crash into a cached fallback.
        return new ZgcProbeDecision(PROBE_INCONCLUSIVE, false, cacheKey, summary);
    }

    @NonNull
    private static String cachedSummary(@NonNull String cachedValue, @NonNull String fallback) {
        int split = cachedValue.indexOf('|');
        if (split < 0 || split + 1 >= cachedValue.length()) return fallback;
        return cachedValue.substring(split + 1);
    }

    @NonNull
    private static String buildZgcProbeCacheKey(
            @NonNull File runtimeDirectory,
            int runtimeMajor,
            @Nullable String maxHeapArg
    ) {
        File javaBinary = new File(runtimeDirectory, "bin/java");
        String fingerprint = Build.FINGERPRINT == null ? "unknown" : Build.FINGERPRINT;
        String raw = "v" + ZGC_PROBE_SCHEMA
                + "|" + fingerprint
                + "|api=" + Build.VERSION.SDK_INT
                + "|runtime=" + runtimeDirectory.getAbsolutePath()
                + "|major=" + runtimeMajor
                + "|javaMtime=" + javaBinary.lastModified()
                + "|javaSize=" + javaBinary.length()
                + "|heap=" + (maxHeapArg == null ? "default" : maxHeapArg);
        return "probe_" + Integer.toHexString(raw.hashCode());
    }

    @Nullable
    private static String findJvmArgWithPrefix(@NonNull List<String> args, @NonNull String prefix) {
        for (String arg : args) {
            if (arg != null && arg.startsWith(prefix)) {
                return arg;
            }
        }
        return null;
    }

    @Nullable
    private static String findStartupGuardKey(@NonNull LaunchPlan plan) {
        String arg = findJvmArgWithPrefix(plan.getJvmArgs(), ZGC_STARTUP_GUARD_ARG_PREFIX);
        if (arg == null || arg.length() <= ZGC_STARTUP_GUARD_ARG_PREFIX.length()) return null;
        return arg.substring(ZGC_STARTUP_GUARD_ARG_PREFIX.length());
    }

    /**
     * Called immediately before VMLauncher/JLI starts the real Minecraft VM.
     * The marker is intentionally persisted before entering HotSpot. If ZGC
     * aborts the Android process during VM initialization, the marker survives
     * and the next launch falls back to G1GC automatically.
     */
    public static void armRealVmStartupGuardIfNeeded(
            @NonNull Context context,
            @NonNull LaunchPlan plan
    ) {
        final String cacheKey = findStartupGuardKey(plan);
        if (cacheKey == null) return;

        final Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        final SharedPreferences prefs = appContext.getSharedPreferences(ZGC_PROBE_PREFS, Context.MODE_PRIVATE);
        final long armedAt = System.currentTimeMillis();
        prefs.edit().putString(
                cacheKey,
                "pending|armed=" + armedAt + ", real VMLauncher/JLI ZGC trial"
        ).commit();

        Logging.i(TAG, "ZGC real-VM startup guard armed key=" + cacheKey
                + " delayMs=" + ZGC_STARTUP_GUARD_DELAY_MS);

        Thread guard = new Thread(() -> {
            try {
                Thread.sleep(ZGC_STARTUP_GUARD_DELAY_MS);
                String current = prefs.getString(cacheKey, null);
                if (current != null && current.startsWith("pending|")) {
                    prefs.edit().putString(
                            cacheKey,
                            "pass|real VMLauncher/JLI ZGC survived "
                                    + ZGC_STARTUP_GUARD_DELAY_MS + " ms"
                    ).commit();
                    Logging.i(TAG, "ZGC real-VM startup guard PASS key=" + cacheKey);
                }
            } catch (Throwable throwable) {
                Logging.e(TAG, "ZGC real-VM startup guard thread failed", throwable);
            }
        }, "DroidBridge-ZGC-StartupGuard");
        guard.setDaemon(true);
        guard.start();
    }

    /**
     * Completes a still-pending guard if VMLauncher returns before the delayed
     * confirmation thread. A normal exit proves HotSpot/ZGC initialized; an
     * immediate non-zero return is conservatively cached as a ZGC startup fail.
     */
    public static void completeRealVmStartupGuardIfNeeded(
            @NonNull Context context,
            @NonNull LaunchPlan plan,
            int exitCode
    ) {
        String cacheKey = findStartupGuardKey(plan);
        if (cacheKey == null) return;

        SharedPreferences prefs = context.getSharedPreferences(ZGC_PROBE_PREFS, Context.MODE_PRIVATE);
        String current = prefs.getString(cacheKey, null);
        if (current == null || !current.startsWith("pending|")) return;

        if (exitCode == 0) {
            prefs.edit().putString(cacheKey, "pass|real VMLauncher/JLI ZGC exited normally").commit();
            Logging.i(TAG, "ZGC real-VM startup guard PASS on normal exit key=" + cacheKey);
        } else {
            prefs.edit().putString(
                    cacheKey,
                    "fail|real VMLauncher/JLI returned before startup guard with exit=" + exitCode
            ).commit();
            Logging.i(TAG, "ZGC real-VM startup guard FAIL on early exit key=" + cacheKey
                    + " exit=" + exitCode);
        }
    }

    @NonNull
    private static String sanitizeProbeDetail(@Nullable String detail) {
        if (detail == null || detail.trim().isEmpty()) return "none";
        String compact = detail.replace('\n', ' ').replace('\r', ' ').trim();
        while (compact.contains("  ")) compact = compact.replace("  ", " ");
        return compact.length() > 240 ? compact.substring(0, 240) + "..." : compact;
    }

    private static final class ZgcProbeDecision {
        final int verdict;
        final boolean cached;
        @NonNull final String cacheKey;
        @NonNull final String summary;

        ZgcProbeDecision(
                int verdict,
                boolean cached,
                @NonNull String cacheKey,
                @NonNull String summary
        ) {
            this.verdict = verdict;
            this.cached = cached;
            this.cacheKey = cacheKey;
            this.summary = summary;
        }

        @NonNull
        String verdictName() {
            if (verdict == PROBE_PASS) return "PASS";
            if (verdict == PROBE_FAIL) return "FAIL";
            return "INCONCLUSIVE";
        }
    }

    public static boolean hasDistantHorizons(@NonNull File gameDir) {
        if (hasModJar(gameDir, "distanthorizons")
                || hasModJar(gameDir, "distant-horizons")
                || hasModJar(gameDir, "distant_horizons")
                || hasModJar(gameDir, "distant horizons")) {
            return true;
        }

        // DH's self-updater may replace the jar with a filename that no longer
        // contains the mod name.  Match the exact public API class so renamed
        // official jars still receive the GC compatibility path.
        for (File modsDir : getCandidateModsDirs(gameDir)) {
            File[] files = modsDir.listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (!file.isFile() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    continue;
                }
                try (ZipFile zip = new ZipFile(file)) {
                    if (zip.getEntry("com/seibel/distanthorizons/api/DhApi.class") != null) {
                        return true;
                    }
                } catch (Throwable ignored) {
                    // A broken/unreadable unrelated mod jar must never block launch.
                }
            }
        }
        return false;
    }

    @NonNull
    public static String displayGcMode(@Nullable String mode) {
        return InstanceLaunchSettings.GC_MODE_ZGC.equals(InstanceLaunchSettings.sanitizeGcMode(mode)) ? "ZGC" : "G1GC";
    }

    private static boolean hasModJar(@NonNull File gameDir, @NonNull String nameNeedle) {
        String needle = normalizeName(nameNeedle);
        for (File modsDir : getCandidateModsDirs(gameDir)) {
            File[] files = modsDir.listFiles();
            if (files == null) continue;

            for (File file : files) {
                if (!file.isFile()) continue;
                String name = file.getName();
                if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
                if (normalizeName(name).contains(needle)) return true;
            }
        }
        return false;
    }

    @NonNull
    private static List<File> getCandidateModsDirs(@NonNull File gameDir) {
        ArrayList<File> dirs = new ArrayList<>();
        dirs.add(new File(gameDir, "mods"));

        File instanceDir = gameDir.getParentFile();
        if (instanceDir != null) {
            dirs.add(new File(instanceDir, "mods"));

            File instancesDir = instanceDir.getParentFile();
            File minecraftDir = instancesDir != null ? instancesDir.getParentFile() : null;
            if (minecraftDir != null) {
                dirs.add(new File(minecraftDir, "mods"));
            }
        }

        return dirs;
    }

    private static int resolveRuntimeMajor(@NonNull File runtimeDir) {
        String name = runtimeDir.getName();
        if (name == null || name.trim().isEmpty()) return 8;
        return RuntimeCompat.javaMajorForRuntimeName(name);
    }

    private static int removeConflictingGcArgs(@NonNull ArrayList<String> args) {
        int removed = 0;
        for (int i = args.size() - 1; i >= 0; i--) {
            String arg = args.get(i);
            if (isConflictingGcArg(arg)) {
                args.remove(i);
                removed++;
            }
        }
        return removed;
    }

    private static boolean isConflictingGcArg(@Nullable String arg) {
        if (arg == null) return false;
        return arg.equals("-XX:+UseG1GC")
                || arg.equals("-XX:-UseG1GC")
                || arg.equals("-XX:+UseParallelGC")
                || arg.equals("-XX:-UseParallelGC")
                || arg.equals("-XX:+UseSerialGC")
                || arg.equals("-XX:-UseSerialGC")
                || arg.equals("-XX:+UseConcMarkSweepGC")
                || arg.equals("-XX:-UseConcMarkSweepGC")
                || arg.equals("-XX:+UseShenandoahGC")
                || arg.equals("-XX:-UseShenandoahGC")
                || arg.equals("-XX:+UseEpsilonGC")
                || arg.equals("-XX:-UseEpsilonGC")
                || arg.equals("-XX:+UseZGC")
                || arg.equals("-XX:-UseZGC")
                || arg.equals("-XX:+ZGenerational")
                || arg.equals("-XX:-ZGenerational")
                || arg.startsWith(ZGC_STARTUP_GUARD_ARG_PREFIX)
                || arg.startsWith("-XX:G1")
                || arg.startsWith("-XX:Z");
    }

    private static void insertBeforeClasspath(@NonNull ArrayList<String> args, @NonNull String value) {
        if (args.contains(value)) return;

        int index = findClasspathIndex(args);
        if (index >= 0) {
            args.add(index, value);
        } else {
            args.add(value);
        }
    }

    private static int findClasspathIndex(@NonNull ArrayList<String> args) {
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if ("-cp".equals(arg) || "-classpath".equals(arg) || "--class-path".equals(arg)) {
                return i;
            }
        }
        return -1;
    }

    @NonNull
    private static String normalizeName(@NonNull String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");
    }

    private static void logMessages(@NonNull ArrayList<String> messages) {
        String summary = null;
        for (String message : messages) {
            if (message.startsWith("Failed:")
                    || message.startsWith("Fell back")
                    || message.startsWith("ZGC probe")) {
                Logging.i(TAG, message);
            }
            if (message.startsWith("Distant Horizons=true;")) {
                summary = message;
            }
        }
        if (summary != null) {
            Logging.i(TAG, summary);
        }
    }

    public static final class DeviceRecommendation {
        @NonNull public final String recommendedGcMode;
        public final int totalMemoryMb;
        public final int cpuCores;
        public final boolean is64Bit;
        @NonNull public final String reason;
        @NonNull public final String summary;

        private DeviceRecommendation(
                @NonNull String recommendedGcMode,
                int totalMemoryMb,
                int cpuCores,
                boolean is64Bit,
                @NonNull String reason,
                @NonNull String summary
        ) {
            this.recommendedGcMode = recommendedGcMode;
            this.totalMemoryMb = totalMemoryMb;
            this.cpuCores = cpuCores;
            this.is64Bit = is64Bit;
            this.reason = reason;
            this.summary = summary;
        }
    }

    public static final class Result {
        @NonNull public final LaunchPlan plan;
        public final boolean applied;
        @NonNull public final String effectiveGcMode;
        @NonNull public final ArrayList<String> messages;

        private Result(
                @NonNull LaunchPlan plan,
                boolean applied,
                @NonNull String effectiveGcMode,
                @NonNull ArrayList<String> messages
        ) {
            this.plan = plan;
            this.applied = applied;
            this.effectiveGcMode = effectiveGcMode;
            this.messages = messages;
        }
    }
}

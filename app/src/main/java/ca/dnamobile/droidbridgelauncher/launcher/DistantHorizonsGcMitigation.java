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
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    public static boolean hasDistantHorizons(@NonNull File gameDir) {
        return hasModJar(gameDir, "distanthorizons")
                || hasModJar(gameDir, "distant-horizons")
                || hasModJar(gameDir, "distant_horizons")
                || hasModJar(gameDir, "distant horizons");
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
            if (message.startsWith("Failed:") || message.startsWith("Fell back")) {
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

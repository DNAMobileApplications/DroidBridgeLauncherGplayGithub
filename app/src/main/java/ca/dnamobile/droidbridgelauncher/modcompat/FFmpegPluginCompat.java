package ca.dnamobile.droidbridgelauncher.modcompat;

/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * DroidBridge FFmpeg companion-plugin discovery.
 */

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

public final class FFmpegPluginCompat {
    private static final String TAG = "FFmpegPluginCompat";

    public static final String JAVALAUNCHER_PACKAGE = "ca.dnamobile.javalauncher.ffmpeg";

    private static final String[] KNOWN_PACKAGES = new String[] {
            JAVALAUNCHER_PACKAGE,
            "ca.dnamobile.javalauncher.ffmpeg.debug",
            "ca.dnamobile.javalauncher.debug.ffmpeg",
            "ca.dnamobile.droidbridge.ffmpeg",
            "ca.dnamobile.droidbridge.ffmpeg.debug"
    };

    private FFmpegPluginCompat() {
    }

    @NonNull
    public static Result discoverForReplayMod(@NonNull Context context, @NonNull File gameDirectory) {
        boolean replayModPresent = hasReplayMod(gameDirectory);
        if (!replayModPresent) {
            return new Result(
                    false,
                    false,
                    null,
                    null,
                    null,
                    null,
                    "Replay Mod is not installed in this instance"
            );
        }

        Result result = discoverInstalled(context);
        return new Result(
                result.available,
                true,
                result.packageName,
                result.libraryPath,
                result.executablePath,
                result.ffprobePath,
                result.errorMessage
        );
    }

    @NonNull
    public static Result discoverInstalled(@NonNull Context context) {
        StringBuilder errors = new StringBuilder();
        for (String packageName : KNOWN_PACKAGES) {
            Result result = discoverPackage(context, packageName);
            if (result.available) return result;
            if (result.errorMessage != null && result.errorMessage.length() > 0) {
                if (errors.length() > 0) errors.append(" | ");
                errors.append(packageName).append(": ").append(result.errorMessage);
            }
        }

        return Result.missing(errors.length() > 0
                ? errors.toString()
                : "DroidBridge FFmpeg plugin is not installed");
    }

    @NonNull
    private static Result discoverPackage(@NonNull Context context, @NonNull String packageName) {
        try {
            PackageManager manager = context.getPackageManager();
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= 33) {
                info = manager.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_SHARED_LIBRARY_FILES)
                );
            } else {
                //noinspection deprecation
                info = manager.getPackageInfo(packageName, PackageManager.GET_SHARED_LIBRARY_FILES);
            }

            ApplicationInfo applicationInfo = info.applicationInfo;
            if (applicationInfo == null || applicationInfo.nativeLibraryDir == null) {
                return Result.missing("FFmpeg plugin has no nativeLibraryDir");
            }

            File libraryDir = new File(applicationInfo.nativeLibraryDir);
            File ffmpeg = new File(libraryDir, "libffmpeg.so");
            File ffprobe = new File(libraryDir, "libffprobe.so");

            if (!ffmpeg.isFile()) {
                return Result.missing("FFmpeg executable missing: " + ffmpeg.getAbsolutePath());
            }

            // Do not copy libffmpeg.so into DroidBridge's private files dir.
            // Android 10+ blocks exec() from writable app-private directories,
            // which returns EACCES even when File#setExecutable(true) succeeds.
            // The native exec hook runs this trusted APK native-library path through
            // /system/bin/linker64 instead.
            //noinspection ResultOfMethodCallIgnored
            ffmpeg.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            ffprobe.setReadable(true, false);

            String ffprobePath = ffprobe.isFile() ? ffprobe.getAbsolutePath() : null;

            Logging.i(TAG, "Discovered DroidBridge FFmpeg plugin"
                    + " package=" + packageName
                    + " libraryPath=" + libraryDir.getAbsolutePath()
                    + " ffmpeg=" + ffmpeg.getAbsolutePath()
                    + " ffprobe=" + (ffprobePath != null ? ffprobePath : "<missing>"));

            return new Result(
                    true,
                    false,
                    packageName,
                    libraryDir.getAbsolutePath(),
                    ffmpeg.getAbsolutePath(),
                    ffprobePath,
                    null
            );
        } catch (Throwable throwable) {
            Logging.e(TAG, "DroidBridge FFmpeg plugin not available: " + packageName, throwable);
            return Result.missing(throwable.toString());
        }
    }

    public static boolean hasReplayMod(@NonNull File gameDirectory) {
        File modsDir = new File(gameDirectory, "mods");
        if (!modsDir.isDirectory()) return false;

        File[] files = modsDir.listFiles();
        if (files == null) return false;

        for (File file : files) {
            if (file == null || !file.isFile()) continue;

            String name = file.getName().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".jar") && !name.endsWith(".disabled")) {
                continue;
            }

            // Fast path for official Replay Mod and known NeoForge ports.
            if (name.contains("replaymod")
                    || name.contains("replay-mod")
                    || name.contains("replay_mod")
                    || name.contains("reforgedplay")
                    || name.contains("reforged-play")) {
                Logging.i(TAG, "Replay Mod detected by filename: " + file.getAbsolutePath());
                return true;
            }

            // Loader-independent fallback. Detect Replay Mod by its actual classes
            // instead of relying only on the artifact filename.
            if (jarContainsReplayMod(file)) {
                Logging.i(TAG, "Replay Mod detected by JAR contents: " + file.getAbsolutePath());
                return true;
            }
        }

        return false;
    }

    private static boolean jarContainsReplayMod(@NonNull File file) {
        try (ZipFile zip = new ZipFile(file)) {
            boolean hasRenderModule = false;
            boolean hasReplayCore = false;

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry == null) continue;

                String entryName = entry.getName();

                if ("com/replaymod/render/FFmpegWriter.class".equals(entryName)
                        || "com/replaymod/render/ReplayModRender.class".equals(entryName)) {
                    hasRenderModule = true;
                }

                if ("com/replaymod/core/ReplayMod.class".equals(entryName)
                        || "com/replaymod/replay/ReplayModReplay.class".equals(entryName)) {
                    hasReplayCore = true;
                }

                if (hasRenderModule && hasReplayCore) {
                    return true;
                }
            }

            // FFmpegWriter/ReplayModRender is already a strong enough signature
            // for Replay Mod or a loader port derived from Replay Mod.
            return hasRenderModule;
        } catch (IOException exception) {
            Logging.i(TAG, "Unable to inspect mod JAR for Replay Mod: "
                    + file.getAbsolutePath() + " - " + exception);
            return false;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Replay Mod JAR inspection failed: "
                    + file.getAbsolutePath(), throwable);
            return false;
        }
    }

    public static final class Result {
        public final boolean available;
        public final boolean replayModPresent;
        @Nullable public final String packageName;
        @Nullable public final String libraryPath;
        @Nullable public final String executablePath;
        @Nullable public final String ffprobePath;
        @Nullable public final String errorMessage;

        private Result(
                boolean available,
                boolean replayModPresent,
                @Nullable String packageName,
                @Nullable String libraryPath,
                @Nullable String executablePath,
                @Nullable String ffprobePath,
                @Nullable String errorMessage
        ) {
            this.available = available;
            this.replayModPresent = replayModPresent;
            this.packageName = packageName;
            this.libraryPath = libraryPath;
            this.executablePath = executablePath;
            this.ffprobePath = ffprobePath;
            this.errorMessage = errorMessage;
        }

        @NonNull
        static Result missing(@Nullable String errorMessage) {
            return new Result(false, false, null, null, null, null, errorMessage);
        }
    }
}

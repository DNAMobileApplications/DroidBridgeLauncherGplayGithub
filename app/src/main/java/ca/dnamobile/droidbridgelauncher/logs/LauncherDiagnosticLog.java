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

package ca.dnamobile.droidbridgelauncher.logs;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.R;

public final class LauncherDiagnosticLog {
    private static final String PREFS = "launcher_logs";
    private static final String KEY_ENABLED = "launcher_diagnostic_logs_enabled";
    private static final String LOG_FILE_NAME = "launcherlog.txt";
    private static final long MAX_LOG_BYTES = 1024L * 1024L;
    private static final long TRIM_TO_BYTES = 768L * 1024L;

    @Nullable
    private static Context appContext;

    private LauncherDiagnosticLog() {
    }

    public static void init(@NonNull Context context) {
        appContext = context.getApplicationContext();
        write("Launcher", "I", "Launcher diagnostic logging initialized for " + getInstalledLauncherVersion(context), null);
    }

    public static boolean isEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (enabled) {
            init(context);
            write("Launcher", "I", "Launcher diagnostic logs enabled", null);
        }
    }

    public static void i(@NonNull String tag, @NonNull String message) {
        write(tag, "I", message, null);
    }

    public static void e(@NonNull String tag, @NonNull String message, @Nullable Throwable throwable) {
        write(tag, "E", message, throwable);
    }

    public static void share(@NonNull Activity activity) {
        init(activity);

        File logFile = getLogFile(activity);
        if (!logFile.isFile() || logFile.length() <= 0L) {
            Toast.makeText(activity, R.string.launcher_logs_missing, Toast.LENGTH_LONG).show();
            return;
        }

        File shareDir = new File(activity.getCacheDir(), "shared_logs");
        // Keep the on-disk diagnostic source as launcherlog.txt for compatibility, but
        // present the attachment using the clearer plural filename requested by the UI.
        File shareFile = new File(shareDir, "launcherlogs.txt");

        try {
            copyFile(logFile, shareFile);
            shareFile.setReadable(true, false);
            LauncherLogManager.shareTextAttachment(
                    activity,
                    shareFile,
                    "launcherlogs.txt",
                    "DroidBridge launcherlogs.txt",
                    activity.getString(R.string.share_logs_android_chooser_title)
            );
        } catch (ActivityNotFoundException throwable) {
            Toast.makeText(activity, R.string.launcher_logs_share_failed, Toast.LENGTH_LONG).show();
        } catch (Throwable throwable) {
            String message = throwable.getMessage();
            Toast.makeText(
                    activity,
                    message != null && !message.trim().isEmpty()
                            ? message
                            : activity.getString(R.string.launcher_logs_share_failed),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    @NonNull
    public static File getLogFile(@NonNull Context context) {
        File root = context.getExternalFilesDir(null);
        if (root == null) root = context.getFilesDir();
        File dir = new File(root, "launcher-logs");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return new File(dir, LOG_FILE_NAME);
    }

    private static synchronized void write(
            @NonNull String tag,
            @NonNull String level,
            @NonNull String message,
            @Nullable Throwable throwable
    ) {
        Context context = appContext;
        if (context == null || !isEnabled(context)) return;

        try {
            File logFile = getLogFile(context);
            trimIfNeeded(logFile);

            StringBuilder builder = new StringBuilder();
            builder.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()));
            builder.append(' ')
                    .append(level)
                    .append('/')
                    .append(tag)
                    .append(": ")
                    .append(message == null ? "" : message)
                    .append('\n');

            if (throwable != null) {
                StringWriter stringWriter = new StringWriter();
                throwable.printStackTrace(new PrintWriter(stringWriter));
                builder.append(stringWriter).append('\n');
            }

            try (FileOutputStream out = new FileOutputStream(logFile, true)) {
                out.write(builder.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void trimIfNeeded(@NonNull File logFile) {
        if (!logFile.isFile() || logFile.length() <= MAX_LOG_BYTES) return;

        try (FileInputStream in = new FileInputStream(logFile)) {
            long length = logFile.length();
            long skip = Math.max(0L, length - TRIM_TO_BYTES);
            while (skip > 0L) {
                long skipped = in.skip(skip);
                if (skipped <= 0L) break;
                skip -= skipped;
            }

            byte[] data = new byte[(int) Math.min(TRIM_TO_BYTES, Integer.MAX_VALUE)];
            int offset = 0;
            while (offset < data.length) {
                int read = in.read(data, offset, data.length - offset);
                if (read == -1) break;
                offset += read;
            }

            String prefix = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                    + " I/Launcher: launcherlog.txt trimmed to keep file size reasonable\n";
            try (FileOutputStream out = new FileOutputStream(logFile, false)) {
                out.write(prefix.getBytes(StandardCharsets.UTF_8));
                out.write(data, 0, offset);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void copyFile(@NonNull File source, @NonNull File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    @NonNull
    private static String getInstalledLauncherVersion(@NonNull Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            String versionName = info.versionName == null || info.versionName.trim().isEmpty()
                    ? "unknown"
                    : info.versionName.trim();
            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode()
                    : info.versionCode;
            return "DroidBridge Launcher " + versionName + " (" + versionCode + ")";
        } catch (Throwable throwable) {
            return "DroidBridge Launcher unknown";
        }
    }

    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

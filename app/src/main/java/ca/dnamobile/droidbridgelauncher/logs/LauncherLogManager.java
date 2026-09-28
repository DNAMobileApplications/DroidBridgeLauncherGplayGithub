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

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.R;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;
import ca.dnamobile.droidbridgelauncher.runtime.Logger;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;

public final class LauncherLogManager {
    private static final String TAG = "LauncherLogManager";
    private static final String PREFS = "launcher_logs";
    private static final String KEY_KEEP_LOG_HISTORY = "keep_log_history";
    private static final String KEY_LAST_LATEST_LOG_PATH = "last_latest_log_path";
    private static final long MAX_IN_MEMORY_LOG_BYTES = 12L * 1024L * 1024L;

    private static boolean nativeLogStarted = false;
    @Nullable
    private static File activeLatestLogFile = null;

    private LauncherLogManager() {
    }

    public static boolean isKeepLogHistoryEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_KEEP_LOG_HISTORY, true);
    }

    public static void setKeepLogHistoryEnabled(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_KEEP_LOG_HISTORY, enabled).apply();
    }

    @NonNull
    public static File getLatestLogFile(@NonNull Context context) {
        PathManager.initContextConstants(context);
        return new File(PathManager.DIR_MINECRAFT_HOME, "latestlog.txt");
    }

    @NonNull
    public static File resolveLatestLogFile(@NonNull Context context) {
        // Do not return the first cached path. With custom/scoped launcher homes the
        // native logger, the persisted path and PathManager can briefly describe different
        // roots after returning from the game. Compare every usable candidate and share the
        // file that was actually updated most recently.
        File newest = null;
        for (File candidate : buildLatestLogCandidates(context)) {
            if (!isUsableLog(candidate)) continue;
            if (newest == null || candidate.lastModified() > newest.lastModified()) {
                newest = candidate;
            }
        }

        if (newest != null) {
            rememberLatestLogPath(context, newest);
            return newest;
        }

        return getLatestLogFile(context);
    }

    @NonNull
    public static File getLogHistoryDirectory(@NonNull Context context) {
        PathManager.initContextConstants(context);
        File dir = new File(PathManager.DIR_MINECRAFT_HOME, "launcher_log");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    @NonNull
    private static File getLogHistoryDirectoryForLatest(@NonNull Context context, @NonNull File latest) {
        File minecraftHome = latest.getParentFile();
        File dir = minecraftHome != null ? new File(minecraftHome, "launcher_log") : getLogHistoryDirectory(context);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static synchronized void beginLatestLog(@NonNull Context context, @NonNull String versionId) {
        File latest = getLatestLogFileForActivePath(context);
        rememberLatestLogPath(context, latest);

        File parent = latest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        boolean firstStartForThisProcess = !nativeLogStarted;
        boolean switchingFile = activeLatestLogFile == null || !activeLatestLogFile.equals(latest);

        if (firstStartForThisProcess || switchingFile || latest.length() == 0) {
            try (FileOutputStream out = new FileOutputStream(latest, false)) {
                out.write(buildFileHeader(context, versionId).getBytes(StandardCharsets.UTF_8));
            } catch (Throwable throwable) {
                Logging.e(TAG, "Failed to initialize latestlog.txt", throwable);
            }
        }

        if (!nativeLogStarted || switchingFile) {
            Logger.beginLog(latest);
            nativeLogStarted = true;
            activeLatestLogFile = latest;
        } else {
            // LaunchGame writes the complete structured header. Keep only one
            // blank line between launches in the same launcher process.
            append("");
        }
    }

    public static void append(@NonNull String text) {
        String clean = LatestLogTextFilter.cleanLauncherLine(text);
        if (clean == null) return;

        try {
            Logger.appendToLog(clean);
        } catch (Throwable throwable) {
            Logging.e(TAG, "appendToLog failed", throwable);
            appendFallback(clean);
        }
    }

    public static synchronized void cleanLatestLogInPlace(@NonNull Context context) {
        File latest = resolveLatestLogFile(context);
        if (!latest.isFile() || latest.length() <= 0L) return;

        if (latest.length() > MAX_IN_MEMORY_LOG_BYTES) {
            Logging.i(TAG, "Skipping in-place latestlog cleanup because file is too large: " + latest.length());
            return;
        }

        try {
            String raw = readTextFile(latest);
            String clean = LatestLogTextFilter.cleanWholeLog(raw);
            if (clean.equals(raw)) return;

            try (FileOutputStream out = new FileOutputStream(latest, false)) {
                out.write(clean.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to clean latestlog.txt", throwable);
        }
    }

    private static void appendFallback(@NonNull String text) {
        File latest = activeLatestLogFile;
        if (latest == null) return;
        try (FileOutputStream out = new FileOutputStream(latest, true)) {
            out.write((LatestLogTextFilter.normalizeLauncherLine(text) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    public static void preserveLatestLogIfEnabled(@NonNull Context context, @NonNull String versionId) {
        // Always leave latestlog.txt readable when a game session ends. History is
        // optional, but cleanup of duplicate lines and accidental blank separators
        // should not depend on that preference.
        cleanLatestLogInPlace(context);

        if (!isKeepLogHistoryEnabled(context)) return;

        File latest = resolveLatestLogFile(context);
        if (!latest.isFile() || latest.length() <= 0) return;

        String safeVersion = versionId.replaceAll("[^A-Za-z0-9._-]", "_");
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File target = new File(getLogHistoryDirectoryForLatest(context, latest), "latestlog-" + safeVersion + "-" + stamp + ".txt");

        try {
            copyFile(latest, target);
            Logging.i(TAG, "Saved launch log history: " + target.getAbsolutePath());
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to save launch log history", throwable);
        }
    }

    /**
     * Main launcher Share Logs action.
     *
     * By default this presents a small in-app chooser for latestlog.txt vs launcherlogs.txt.
     * Users can disable that chooser in Launcher Settings; when disabled this goes straight
     * to sharing latestlog.txt.
     */
    public static void shareLogs(@NonNull Activity activity) {
        if (!LauncherPreferences.isShareLogChooserEnabled(activity)) {
            shareLatestLog(activity);
            return;
        }

        /*
         * Always create/show the source picker on the Activity UI thread.
         * This keeps the in-app source selection separate from Android's
         * ACTION_SEND chooser that appears after a log source is selected.
         */
        activity.runOnUiThread(() -> {
            if (activity.isFinishing()
                    || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                    && activity.isDestroyed())) {
                return;
            }

            new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.share_logs_dialog_title)
                    .setItems(
                            new CharSequence[]{
                                    activity.getString(R.string.share_logs_latest_option),
                                    activity.getString(R.string.share_logs_launcher_option)
                            },
                            (dialog, which) -> {
                                dialog.dismiss();

                                if (which == 0) {
                                    shareLatestLog(activity);
                                } else if (which == 1) {
                                    LauncherDiagnosticLog.share(activity);
                                }
                            }
                    )
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }

    public static void shareLatestLog(@NonNull Activity activity) {
        cleanLatestLogInPlace(activity);

        File latest = resolveLatestLogFile(activity);
        if (!latest.isFile() || latest.length() <= 0) {
            Toast.makeText(activity, R.string.log_latest_missing, Toast.LENGTH_LONG).show();
            return;
        }

        rememberLatestLogPath(activity, latest);

        File shareDir = new File(activity.getCacheDir(), "shared_logs");
        File shareFile = new File(shareDir, "latestlog.txt");

        try {
            copyFile(latest, shareFile);
            shareFile.setReadable(true, false);
            shareTextAttachment(
                    activity,
                    shareFile,
                    "latestlog.txt",
                    "DroidBridge latestlog.txt",
                    activity.getString(R.string.share_logs_android_chooser_title)
            );
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to share latestlog.txt", throwable);
            String message = throwable.getMessage();
            Toast.makeText(
                    activity,
                    message != null && !message.trim().isEmpty()
                            ? message
                            : activity.getString(R.string.share_logs_share_failed),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    /**
     * Shares an actual text file using ACTION_SEND as the primary intent.
     *
     * The previous latestlog path used ACTION_VIEW as the chooser's primary intent and
     * inserted ACTION_SEND only as an EXTRA_INITIAL_INTENTS entry. Android therefore
     * populated the chooser with file viewers rather than normal share targets on some
     * devices, which could hide Quick Share and Discord. This method deliberately uses
     * ACTION_SEND + EXTRA_STREAM + ClipData + URI grants so Android shows the standard
     * share sheet and receiving apps can read the FileProvider URI.
     */
    public static void shareTextAttachment(
            @NonNull Activity activity,
            @NonNull File file,
            @NonNull String displayName,
            @NonNull String subject,
            @NonNull String chooserTitle
    ) {
        Uri uri = FileProvider.getUriForFile(
                activity,
                activity.getPackageName() + ".fileprovider",
                file
        );

        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        sendIntent.setType("text/plain");
        sendIntent.putExtra(Intent.EXTRA_STREAM, uri);
        sendIntent.putExtra(Intent.EXTRA_SUBJECT, subject);
        // Do not set EXTRA_TEXT here. Quick Share and some OEM share targets prefer
        // EXTRA_TEXT over EXTRA_STREAM when both are present, which turns this into a
        // text share (for example "DroidBridge latestlog.txt") instead of a file share.
        sendIntent.setClipData(
                ClipData.newUri(activity.getContentResolver(), displayName, uri)
        );
        sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        // Some OEM share sheets and older target apps inspect explicit grants instead of
        // inheriting only the chooser flag. Grant each resolved receiver read access too.
        try {
            java.util.List<android.content.pm.ResolveInfo> receivers =
                    activity.getPackageManager().queryIntentActivities(
                            sendIntent,
                            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
                    );
            for (android.content.pm.ResolveInfo receiver : receivers) {
                if (receiver == null || receiver.activityInfo == null) continue;
                String packageName = receiver.activityInfo.packageName;
                if (packageName == null || packageName.trim().isEmpty()) continue;
                activity.grantUriPermission(
                        packageName,
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            }
        } catch (Throwable ignored) {
            // FLAG_GRANT_READ_URI_PERMISSION + ClipData remain the standard fallback.
        }

        Intent chooser = Intent.createChooser(sendIntent, chooserTitle);
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(chooser);
    }

    @NonNull
    private static String buildFileHeader(@NonNull Context context, @NonNull String versionId) {
        // The detailed header is written by LaunchGame after native logging is
        // attached. Returning an empty string avoids duplicating launcher,
        // package and Minecraft information at the top of every latestlog.
        return "";
    }

    @NonNull
    public static String getInstalledLauncherVersion(@NonNull Context context) {
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

    @NonNull
    private static File getLatestLogFileForActivePath(@NonNull Context context) {
        if (PathManager.DIR_MINECRAFT_HOME == null || PathManager.DIR_MINECRAFT_HOME.trim().isEmpty()) {
            PathManager.initContextConstants(context);
        }
        return new File(PathManager.DIR_MINECRAFT_HOME, "latestlog.txt");
    }

    @NonNull
    private static ArrayList<File> buildLatestLogCandidates(@NonNull Context context) {
        ArrayList<File> candidates = new ArrayList<>();

        try {
            addCandidate(candidates, Logger.getCurrentLogFile());
        } catch (Throwable ignored) {
        }

        addCandidate(candidates, activeLatestLogFile);

        String persistedPath = prefs(context).getString(KEY_LAST_LATEST_LOG_PATH, "");
        if (persistedPath != null && !persistedPath.trim().isEmpty()) {
            addCandidate(candidates, new File(persistedPath.trim()));
        }

        try {
            PathManager.initContextConstants(context);
            addCandidate(candidates, new File(PathManager.DIR_MINECRAFT_HOME, "latestlog.txt"));
        } catch (Throwable ignored) {
        }

        try {
            File defaultHome = PathManager.getDefaultLauncherHome(context);
            addCandidate(candidates, new File(new File(defaultHome, ".minecraft"), "latestlog.txt"));
        } catch (Throwable ignored) {
        }

        return candidates;
    }

    private static void addCandidate(@NonNull ArrayList<File> candidates, @Nullable File file) {
        if (file == null) return;
        String path = file.getAbsolutePath();
        for (File existing : candidates) {
            if (existing.getAbsolutePath().equals(path)) return;
        }
        candidates.add(file);
    }

    private static boolean isUsableLog(@Nullable File file) {
        return file != null && file.isFile() && file.length() > 0L;
    }

    private static void rememberLatestLogPath(@NonNull Context context, @NonNull File latest) {
        prefs(context).edit().putString(KEY_LAST_LATEST_LOG_PATH, latest.getAbsolutePath()).apply();
    }

    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @NonNull
    private static String readTextFile(@NonNull File file) throws Exception {
        long length = file.length();
        if (length > MAX_IN_MEMORY_LOG_BYTES) {
            return readTailTextFile(file, MAX_IN_MEMORY_LOG_BYTES);
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(length, Integer.MAX_VALUE)];
            int offset = 0;
            while (offset < data.length) {
                int read = in.read(data, offset, data.length - offset);
                if (read == -1) break;
                offset += read;
            }
            return new String(data, 0, offset, StandardCharsets.UTF_8);
        }
    }

    @NonNull
    private static String readTailTextFile(@NonNull File file, long maxBytes) throws Exception {
        long length = file.length();
        int bytesToRead = (int) Math.min(Math.max(maxBytes, 0L), Math.min(length, Integer.MAX_VALUE));
        byte[] data = new byte[bytesToRead];
        try (FileInputStream in = new FileInputStream(file)) {
            long skip = Math.max(0L, length - bytesToRead);
            while (skip > 0L) {
                long skipped = in.skip(skip);
                if (skipped <= 0L) break;
                skip -= skipped;
            }
            int offset = 0;
            while (offset < data.length) {
                int read = in.read(data, offset, data.length - offset);
                if (read == -1) break;
                offset += read;
            }
            return new String(data, 0, offset, StandardCharsets.UTF_8);
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
}

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
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.R;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;
import ca.dnamobile.droidbridgelauncher.runtime.Logger;

public final class LauncherLogManager {
    private static final String TAG = "LauncherLogManager";
    private static final String PREFS = "launcher_logs";
    private static final String KEY_KEEP_LOG_HISTORY = "keep_log_history";
    private static final String KEY_LAST_LATEST_LOG_PATH = "last_latest_log_path";
    private static final long MAX_IN_MEMORY_LOG_BYTES = 12L * 1024L * 1024L;
    private static final long MAX_HTML_PREVIEW_BYTES = 4L * 1024L * 1024L;

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

    public static void shareLatestLog(@NonNull Activity activity) {
        cleanLatestLogInPlace(activity);

        File latest = resolveLatestLogFile(activity);
        if (!latest.isFile() || latest.length() <= 0) {
            Toast.makeText(activity, R.string.log_latest_missing, Toast.LENGTH_LONG).show();
            return;
        }

        rememberLatestLogPath(activity, latest);

        File shareDir = new File(activity.getCacheDir(), "shared_logs");
        File shareTextFile = new File(shareDir, "latestlog.txt");
        File chromeHtmlFile = new File(shareDir, "latestlog.html");

        try {
            copyFile(latest, shareTextFile);
            writeChromeHtmlPreview(shareTextFile, chromeHtmlFile);
            shareTextFile.setReadable(true, false);
            chromeHtmlFile.setReadable(true, false);
            openOrShareTextFile(activity, shareTextFile, chromeHtmlFile);
            return;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to share cached latestlog.txt", throwable);
        }

        try {
            openOrShareTextFile(activity, latest, null);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to share latestlog.txt", throwable);
            Toast.makeText(activity, throwable.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static void openOrShareTextFile(
            @NonNull Activity activity,
            @NonNull File textFile,
            @Nullable File htmlPreviewFile
    ) {
        Uri textUri = FileProvider.getUriForFile(
                activity,
                activity.getPackageName() + ".fileprovider",
                textFile
        );

        Intent sendIntent = buildSendIntent(activity, textUri);

        Intent primaryIntent;
        if (htmlPreviewFile != null && htmlPreviewFile.isFile()) {
            Uri htmlUri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".fileprovider",
                    htmlPreviewFile
            );
            primaryIntent = buildViewIntent(activity, htmlUri, "text/html", "latestlog.html");
        } else {
            primaryIntent = buildViewIntent(activity, textUri, "text/plain", "latestlog.txt");
        }

        Intent chooser = Intent.createChooser(primaryIntent, activity.getString(R.string.button_share_latest_log));
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{sendIntent});
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            activity.startActivity(chooser);
        } catch (ActivityNotFoundException throwable) {
            activity.startActivity(Intent.createChooser(sendIntent, activity.getString(R.string.button_share_latest_log)));
        }
    }

    @NonNull
    private static Intent buildSendIntent(@NonNull Activity activity, @NonNull Uri textUri) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_STREAM, textUri);
        intent.putExtra(Intent.EXTRA_SUBJECT, "DroidBridge latestlog.txt");
        intent.putExtra(Intent.EXTRA_TEXT, "DroidBridge latestlog.txt");
        intent.setClipData(ClipData.newUri(activity.getContentResolver(), "latestlog.txt", textUri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    @NonNull
    private static Intent buildViewIntent(
            @NonNull Activity activity,
            @NonNull Uri uri,
            @NonNull String mimeType,
            @NonNull String label
    ) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mimeType);
        intent.setClipData(ClipData.newUri(activity.getContentResolver(), label, uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    private static void writeChromeHtmlPreview(@NonNull File textFile, @NonNull File htmlFile) throws Exception {
        File parent = htmlFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        String text = textFile.length() > MAX_HTML_PREVIEW_BYTES
                ? readTailTextFile(textFile, MAX_HTML_PREVIEW_BYTES)
                : readTextFile(textFile);
        String prefix = textFile.length() > MAX_HTML_PREVIEW_BYTES
                ? "[DroidBridge latestlog.txt is very large: " + textFile.length() + " bytes. Showing the tail only.]\n\n"
                : "";
        String html = "<!doctype html>\n"
                + "<html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>DroidBridge latestlog.txt</title>"
                + "<style>"
                + "body{margin:0;padding:16px;background:#111;color:#eee;font-family:monospace;font-size:13px;line-height:1.35;}"
                + "pre{white-space:pre-wrap;word-wrap:break-word;margin:0;}"
                + "</style></head><body><pre>"
                + escapeHtml(prefix + text)
                + "</pre></body></html>\n";

        try (FileOutputStream out = new FileOutputStream(htmlFile, false)) {
            out.write(html.getBytes(StandardCharsets.UTF_8));
        }
    }

    @NonNull
    private static String escapeHtml(@Nullable String text) {
        if (text == null || text.isEmpty()) return "";

        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&':
                    builder.append("&amp;");
                    break;
                case '<':
                    builder.append("&lt;");
                    break;
                case '>':
                    builder.append("&gt;");
                    break;
                case '"':
                    builder.append("&quot;");
                    break;
                case '\'':
                    builder.append("&#39;");
                    break;
                default:
                    builder.append(c);
                    break;
            }
        }
        return builder.toString();
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

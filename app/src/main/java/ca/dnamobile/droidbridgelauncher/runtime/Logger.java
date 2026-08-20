/*
 * DroidBridge Launcher runtime bridge component.
 *
 * DroidBridge modifications:
 * Copyright (c) 2026 DNA Mobile Applications.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package ca.dnamobile.droidbridgelauncher.runtime;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.concurrent.CopyOnWriteArrayList;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.logs.LatestLogTextFilter;

public final class Logger {
    private static final String TAG = "DroidBridgeLogger";
    private static volatile File currentLogFile;
    private static volatile boolean nativeLogReady;
    private static final CopyOnWriteArrayList<eventLogListener> LOG_LISTENERS = new CopyOnWriteArrayList<>();

    /*
     * Keep appendToLog(String) as the native method.
     *
     * Do not rename it unless you also update the JNI side in droidbridge_runtime.
     * This dispatcher only cleans what is sent to Java listeners/log overlay.
     * The actual latestlog.txt file is cleaned safely by LauncherLogManager
     * after launch output is written.
     */
    private static final eventLogListener DISPATCHER = rawLine -> {
        String line = LatestLogTextFilter.cleanRealtimeLine(rawLine);
        if (line == null) return;

        for (eventLogListener listener : LOG_LISTENERS) {
            try {
                listener.onEventLogged(line);
            } catch (Throwable throwable) {
                Log.w(TAG, "Log listener failed", throwable);
            }
        }
    };

    static {
        try {
            System.loadLibrary("droidbridge_runtime");
        } catch (Throwable ignored) {
        }
    }

    private Logger() {
    }

    public interface eventLogListener {
        void onEventLogged(String line);
    }

    public static void beginLog(@NonNull File logFile) {
        File parent = logFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        currentLogFile = logFile;
        nativeLogReady = false;

        try {
            begin(logFile.getAbsolutePath());
            nativeLogReady = true;
            Log.i(TAG, "Native log redirection started at " + logFile.getAbsolutePath());
        } catch (Throwable throwable) {
            nativeLogReady = false;
            Log.e(TAG, "Native log redirection failed", throwable);
            Logging.e(TAG, "Native log redirection failed", throwable);
        }
    }

    public static boolean isNativeLogReady() {
        return nativeLogReady;
    }

    @Nullable
    public static File getCurrentLogFile() {
        return currentLogFile;
    }

    public static void addLogListener(@Nullable eventLogListener listener) {
        if (listener == null) return;
        if (!LOG_LISTENERS.contains(listener)) {
            LOG_LISTENERS.add(listener);
        }
        installDispatcherIfNeeded();
    }

    public static void removeLogListener(@Nullable eventLogListener listener) {
        if (listener == null) return;
        LOG_LISTENERS.remove(listener);
        if (LOG_LISTENERS.isEmpty()) {
            try {
                setLogListener(null);
            } catch (Throwable throwable) {
                Log.w(TAG, "Unable to clear native log listener", throwable);
            }
        }
    }

    public static void clearLogListeners() {
        LOG_LISTENERS.clear();
        try {
            setLogListener(null);
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to clear native log listeners", throwable);
        }
    }

    private static void installDispatcherIfNeeded() {
        try {
            setLogListener(DISPATCHER);
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to install native log dispatcher", throwable);
        }
    }

    public static native void begin(String logPath);

    public static native void appendToLog(String text);

    public static native void setLogListener(eventLogListener listener);
}

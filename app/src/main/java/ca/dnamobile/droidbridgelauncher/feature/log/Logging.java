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

package ca.dnamobile.droidbridgelauncher.feature.log;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.logs.LauncherDiagnosticLog;

public final class Logging {
    private static final String DEFAULT_TAG = "DroidBridge";

    private Logging() {
    }

    public static void init(@NonNull Context context) {
        LauncherDiagnosticLog.init(context);
    }

    public static void i(@NonNull String tag, @NonNull String message) {
        Log.i(tag, message);
        LauncherDiagnosticLog.i(tag, message);
    }

    public static void i(@NonNull String message) {
        Log.i(DEFAULT_TAG, message);
        LauncherDiagnosticLog.i(DEFAULT_TAG, message);
    }

    public static void e(@NonNull String tag, @NonNull String message) {
        Log.e(tag, message);
        LauncherDiagnosticLog.e(tag, message, null);
    }

    public static void e(@NonNull String tag, @NonNull String message, @Nullable Throwable throwable) {
        Log.e(tag, message, throwable);
        LauncherDiagnosticLog.e(tag, message, throwable);
    }

    public static void e(@NonNull String message, @Nullable Throwable throwable) {
        Log.e(DEFAULT_TAG, message, throwable);
        LauncherDiagnosticLog.e(DEFAULT_TAG, message, throwable);
    }
}

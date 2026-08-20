/*
 * DroidBridge Launcher runtime bridge component.
 *
 * DroidBridge modifications:
 * Copyright (c) 2026 DNA Mobile Applications.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package ca.dnamobile.droidbridgelauncher.runtime;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
public final class MainActivity {
    private static final String TAG = "DroidBridgeMainActivityABI";
    private static WeakReference<Activity> currentActivity = new WeakReference<>(null);

    private MainActivity() {
    }

    public static void setCurrentActivity(@Nullable Activity activity) {
        currentActivity = new WeakReference<>(activity);
    }

    public static void clearCurrentActivity(@Nullable Activity activity) {
        Activity current = currentActivity.get();
        if (current == null || current == activity) {
            currentActivity.clear();
        }
    }

    public static void openLink(@Nullable String value) {
        if (value == null || value.trim().isEmpty()) return;

        Activity activity = currentActivity.get();
        if (activity == null) {
            Log.w(TAG, "openLink ignored because no activity is active: " + value);
            return;
        }

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(value));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(activity, value, Toast.LENGTH_LONG).show();
        } catch (Throwable throwable) {
            Log.e(TAG, "openLink failed", throwable);
        }
    }

    public static void querySystemClipboard() {
        Activity activity = currentActivity.get();
        if (activity == null) return;

        try {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip() || clipboard.getPrimaryClip() == null) {
                AWTInputBridge.nativeClipboardReceived("", "text/plain");
                return;
            }

            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() <= 0) {
                AWTInputBridge.nativeClipboardReceived("", "text/plain");
                return;
            }

            CharSequence text = clip.getItemAt(0).coerceToText(activity);
            ca.dnamobile.droidbridgelauncher.runtime.AWTInputBridge.nativeClipboardReceived(text == null ? "" : text.toString(), "text/plain");
        } catch (Throwable throwable) {
            Log.e(TAG, "querySystemClipboard failed", throwable);
        }
    }

    public static void putClipboardData(@Nullable String data, @Nullable String mime) {
        Activity activity = currentActivity.get();
        if (activity == null) return;

        try {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return;
            String safeMime = mime == null || mime.trim().isEmpty() ? "text/plain" : mime;
            String safeData = data == null ? "" : data;
            clipboard.setPrimaryClip(ClipData.newPlainText(safeMime, safeData));
        } catch (Throwable throwable) {
            Log.e(TAG, "putClipboardData failed", throwable);
        }
    }
}

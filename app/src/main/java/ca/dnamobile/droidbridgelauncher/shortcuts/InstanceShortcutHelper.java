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

package ca.dnamobile.droidbridgelauncher.shortcuts;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.InstanceShortcutActivity;
import ca.dnamobile.droidbridgelauncher.R;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstance;
import ca.dnamobile.droidbridgelauncher.ui.instance.InstanceIconResolver;

/** Utility for Android pinned shortcuts that launch a saved DroidBridge instance. */
public final class InstanceShortcutHelper {
    private static final String TAG = "InstanceShortcut";

    public static final String ACTION_LAUNCH_INSTANCE =
            "ca.dnamobile.droidbridgelauncher.action.LAUNCH_INSTANCE";
    public static final String EXTRA_INSTANCE_ID =
            "ca.dnamobile.droidbridgelauncher.extra.SHORTCUT_INSTANCE_ID";
    /** Legacy/simple key accepted for third-party front ends and older Kid Emu builds. */
    public static final String EXTRA_INSTANCE_ID_LEGACY = "instance_id";

    public static final String ACTION_PICK_INSTANCE =
            "ca.dnamobile.droidbridgelauncher.action.PICK_INSTANCE";
    public static final String EXTRA_PICKED_INSTANCE_ID = EXTRA_INSTANCE_ID;
    public static final String EXTRA_PICKED_INSTANCE_ID_LEGACY = EXTRA_INSTANCE_ID_LEGACY;
    public static final String EXTRA_PICKED_INSTANCE_NAME =
            "ca.dnamobile.droidbridgelauncher.extra.INSTANCE_NAME";

    private static final int MAX_SHORT_LABEL_LENGTH = 10;
    private static final int MAX_LONG_LABEL_LENGTH = 35;
    private static final int SHORTCUT_ICON_MAX_SIZE_PX = 192;

    private InstanceShortcutHelper() {
    }

    public static final class ShortcutData {
        @NonNull
        public final String instanceId;
        @NonNull
        public final String instanceName;
        @NonNull
        public final String loader;
        @NonNull
        public final String baseVersionId;
        @NonNull
        public final String minecraftVersionId;
        @Nullable
        public final File iconFile;

        public ShortcutData(
                @NonNull String instanceId,
                @NonNull String instanceName,
                @NonNull String loader,
                @NonNull String baseVersionId,
                @NonNull String minecraftVersionId,
                @Nullable File iconFile
        ) {
            this.instanceId = instanceId;
            this.instanceName = instanceName;
            this.loader = loader;
            this.baseVersionId = baseVersionId;
            this.minecraftVersionId = minecraftVersionId;
            this.iconFile = iconFile;
        }

        @NonNull
        public static ShortcutData fromInstance(@NonNull LauncherInstance instance) {
            return new ShortcutData(
                    instance.getId(),
                    instance.getName(),
                    instance.getLoader(),
                    instance.getBaseVersionId(),
                    instance.getMinecraftVersionId(),
                    instance.getIconFile()
            );
        }
    }

    public static boolean isLaunchInstanceIntent(@Nullable Intent intent) {
        return intent != null && ACTION_LAUNCH_INSTANCE.equals(intent.getAction());
    }

    @Nullable
    public static String readInstanceId(@Nullable Intent intent) {
        if (!isLaunchInstanceIntent(intent)) return null;
        String value = intent.getStringExtra(EXTRA_INSTANCE_ID);
        if (value == null || value.trim().isEmpty()) {
            value = intent.getStringExtra(EXTRA_INSTANCE_ID_LEGACY);
        }
        return value == null ? null : value.trim();
    }

    public static void requestPinShortcut(
            @NonNull Context context,
            @NonNull LauncherInstance instance
    ) {
        requestPinShortcut(context, ShortcutData.fromInstance(instance));
    }

    public static void requestPinShortcut(
            @NonNull Context context,
            @NonNull ShortcutData shortcutData
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(context, "Home screen shortcuts require Android 8.0 or newer.", Toast.LENGTH_LONG).show();
            return;
        }

        ShortcutManager shortcutManager = context.getSystemService(ShortcutManager.class);
        if (shortcutManager == null || !shortcutManager.isRequestPinShortcutSupported()) {
            Toast.makeText(context, "Your Android launcher does not support pinned shortcuts.", Toast.LENGTH_LONG).show();
            return;
        }

        Intent launchIntent = new Intent(context, InstanceShortcutActivity.class);
        launchIntent.setAction(ACTION_LAUNCH_INSTANCE);
        launchIntent.putExtra(EXTRA_INSTANCE_ID, shortcutData.instanceId);
        launchIntent.putExtra(EXTRA_INSTANCE_ID_LEGACY, shortcutData.instanceId);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        String instanceName = safeLabel(shortcutData.instanceName, "Minecraft");
        ShortcutInfo shortcut = new ShortcutInfo.Builder(context, shortcutId(shortcutData.instanceId))
                .setShortLabel(trimLabel(instanceName, MAX_SHORT_LABEL_LENGTH))
                .setLongLabel(trimLabel("Launch " + instanceName, MAX_LONG_LABEL_LENGTH))
                .setIcon(resolveShortcutIcon(context, shortcutData))
                .setIntent(launchIntent)
                .build();

        try {
            shortcutManager.requestPinShortcut(shortcut, null);
            Toast.makeText(context, "Shortcut request sent.", Toast.LENGTH_SHORT).show();
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to request pinned shortcut for " + shortcutData.instanceName, throwable);
            Toast.makeText(context, "Unable to add shortcut: " + readableError(throwable), Toast.LENGTH_LONG).show();
        }
    }

    @NonNull
    public static String shortcutId(@NonNull String instanceId) {
        String cleaned = instanceId.trim().toLowerCase(Locale.US).replaceAll("[^a-z0-9._-]", "_");
        if (cleaned.isEmpty()) cleaned = Integer.toHexString(instanceId.hashCode());
        return "instance_" + cleaned;
    }

    @NonNull
    private static Icon resolveShortcutIcon(
            @NonNull Context context,
            @NonNull ShortcutData shortcutData
    ) {
        Bitmap bitmap = decodeShortcutBitmap(shortcutData.iconFile);
        if (bitmap != null) {
            return Icon.createWithAdaptiveBitmap(bitmap);
        }

        int defaultIcon = InstanceIconResolver.getDefaultIcon(
                shortcutData.loader,
                shortcutData.baseVersionId,
                shortcutData.minecraftVersionId,
                shortcutData.instanceName
        );
        if (defaultIcon != 0) {
            return Icon.createWithResource(context, defaultIcon);
        }
        return Icon.createWithResource(context, R.mipmap.ic_launcher_droidbridge);
    }

    @Nullable
    private static Bitmap decodeShortcutBitmap(@Nullable File iconFile) {
        if (iconFile == null || !iconFile.isFile()) return null;

        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(iconFile.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sampleSize = 1;
            while ((bounds.outWidth / sampleSize) > SHORTCUT_ICON_MAX_SIZE_PX
                    || (bounds.outHeight / sampleSize) > SHORTCUT_ICON_MAX_SIZE_PX) {
                sampleSize *= 2;
            }

            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inSampleSize = Math.max(1, sampleSize);
            decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(iconFile.getAbsolutePath(), decode);
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to decode shortcut icon " + iconFile.getAbsolutePath()
                    + ": " + readableError(throwable));
            return null;
        }
    }

    @NonNull
    private static String safeLabel(@Nullable String value, @NonNull String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    @NonNull
    private static String trimLabel(@NonNull String value, int maxLength) {
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) return trimmed;
        if (maxLength <= 1) return trimmed.substring(0, maxLength);
        return trimmed.substring(0, maxLength - 1).trim() + "…";
    }

    @NonNull
    private static String readableError(@NonNull Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName()
                : message;
    }
}

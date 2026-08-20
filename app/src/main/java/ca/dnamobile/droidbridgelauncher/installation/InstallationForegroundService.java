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

package ca.dnamobile.droidbridgelauncher.installation;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.launcher.DroidBridgeLaunchActivity;

public final class InstallationForegroundService extends Service {
    private static final String TAG = "InstallForeground";
    private static final String CHANNEL_ID = "launcher_installation";
    private static final int NOTIFICATION_ID = 4317;

    private static final String ACTION_START = "ca.dnamobile.droidbridgelauncher.installation.START";
    private static final String ACTION_UPDATE = "ca.dnamobile.droidbridgelauncher.installation.UPDATE";
    private static final String ACTION_STOP = "ca.dnamobile.droidbridgelauncher.installation.STOP";

    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_MESSAGE = "message";
    private static final String EXTRA_PROGRESS = "progress";
    private static final String EXTRA_INDETERMINATE = "indeterminate";

    private String title = "Installing Minecraft";
    private String message = "Preparing installation...";
    private int progress = 0;
    private boolean indeterminate = true;
    private boolean foregroundStarted = false;

    public static void start(
            @NonNull Context context,
            @NonNull String title,
            @NonNull String message,
            int progress,
            boolean indeterminate
    ) {
        Intent intent = new Intent(context, InstallationForegroundService.class);
        intent.setAction(ACTION_START);
        putProgress(intent, title, message, progress, indeterminate);
        safeStart(context, intent);
    }

    public static void update(
            @NonNull Context context,
            @NonNull String title,
            @NonNull String message,
            int progress,
            boolean indeterminate
    ) {
        Intent intent = new Intent(context, InstallationForegroundService.class);
        intent.setAction(ACTION_UPDATE);
        putProgress(intent, title, message, progress, indeterminate);
        safeStart(context, intent);
    }

    public static void stop(@NonNull Context context) {
        Intent intent = new Intent(context, InstallationForegroundService.class);
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to stop install foreground service: " + throwable.getMessage());
        }
    }

    private static void safeStart(@NonNull Context context, @NonNull Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to start install foreground service", throwable);
        }
    }

    private static void putProgress(
            @NonNull Intent intent,
            @NonNull String title,
            @NonNull String message,
            int progress,
            boolean indeterminate
    ) {
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_MESSAGE, message);
        intent.putExtra(EXTRA_PROGRESS, Math.max(0, Math.min(100, progress)));
        intent.putExtra(EXTRA_INDETERMINATE, indeterminate);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        readIntent(intent);
        Notification notification = buildNotification();

        if (!foregroundStarted) {
            startForeground(NOTIFICATION_ID, notification);
            foregroundStarted = true;
        } else {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(NOTIFICATION_ID, notification);
        }

        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void readIntent(@Nullable Intent intent) {
        if (intent == null) return;
        title = intent.getStringExtra(EXTRA_TITLE) != null ? intent.getStringExtra(EXTRA_TITLE) : title;
        message = intent.getStringExtra(EXTRA_MESSAGE) != null ? intent.getStringExtra(EXTRA_MESSAGE) : message;
        progress = Math.max(0, Math.min(100, intent.getIntExtra(EXTRA_PROGRESS, progress)));
        indeterminate = intent.getBooleanExtra(EXTRA_INDETERMINATE, indeterminate);
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, DroidBridgeLaunchActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent, pendingIntentFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        builder.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, progress, indeterminate);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(Notification.CATEGORY_PROGRESS);
        }

        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Installations",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Shows Minecraft, Fabric, Forge, and NeoForge installation progress.");
        manager.createNotificationChannel(channel);
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        foregroundStarted = false;
    }
}

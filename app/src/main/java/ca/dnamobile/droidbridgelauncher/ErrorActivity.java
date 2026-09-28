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

package ca.dnamobile.droidbridgelauncher;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import ca.dnamobile.droidbridgelauncher.logs.LauncherLogManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Clean DroidBridge exit-message target for the native exit hook.
 * This is intentionally in ca.dnamobile.droidbridgelauncher, not com.movtery.*.
 */
public class ErrorActivity extends AppCompatActivity {
    public static final String EXTRA_MESSAGE = "message";

    public static void showExitMessage(Context context, int exitCode, boolean isSignal) {
        String message = isSignal
                ? "Minecraft stopped from signal " + exitCode
                : "Minecraft exited with code " + exitCode;

        Intent intent = new Intent(context, ErrorActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(EXTRA_MESSAGE, message);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherTheme.apply(this);
        super.onCreate(savedInstanceState);
        String message = getIntent().getStringExtra(EXTRA_MESSAGE);
        if (message == null || message.trim().isEmpty()) {
            message = "Minecraft exited.";
        }

        TextView textView = new TextView(this);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        textView.setPadding(padding, padding, padding, padding);
        textView.setText(message + "\n\nUse Share latestlog.txt so the crash can be checked.");
        textView.setTextSize(16);
        setContentView(textView);
        LauncherTheme.applyRainbowBackgroundIfNeeded(this);

        final String finalMessage = message;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Game exited")
                .setMessage(finalMessage + "\n\nShare latestlog.txt so the crash can be checked?")
                .setPositiveButton(R.string.button_share_latest_log, (dialog, which) -> LauncherLogManager.shareLatestLog(this))
                .setNegativeButton(android.R.string.ok, (dialog, which) -> finish())
                .show();
    }
}

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

package ca.dnamobile.droidbridgelauncher.settings;

import ca.dnamobile.droidbridgelauncher.LauncherTheme;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.modcompat.AndroidMicrophonePermission;

/**
 * Small settings screen for enabling Android microphone permission for mods
 * such as Verity and Simple Voice Chat.
 *
 * Add this Activity to AndroidManifest.xml, then launch it from your existing
 * Settings screen with SimpleVoiceChatPermissionActivity.open(context).
 */
public class SimpleVoiceChatPermissionActivity extends Activity {
    private TextView statusText;

    public static void open(Context context) {
        Intent intent = new Intent(context, SimpleVoiceChatPermissionActivity.class);
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherTheme.apply(this);
        super.onCreate(savedInstanceState);

        int padding = dp(20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        root.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        TextView title = new TextView(this);
        title.setText("Voice mod microphone");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(10));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView description = new TextView(this);
        description.setText("Enable microphone permission so Minecraft mods like Verity and Simple Voice Chat can access your device microphone while the game is running.");
        description.setTextSize(16);
        description.setPadding(0, 0, 0, dp(18));
        root.addView(description, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        statusText.setPadding(0, 0, 0, dp(18));
        root.addView(statusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        Button requestButton = new Button(this);
        requestButton.setText("Enable microphone permission");
        requestButton.setOnClickListener(v -> AndroidMicrophonePermission.showRequestDialog(this));
        root.addView(requestButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        Button appSettingsButton = new Button(this);
        appSettingsButton.setText("Open Android app settings");
        appSettingsButton.setOnClickListener(v -> AndroidMicrophonePermission.openAppSettings(this));
        root.addView(appSettingsButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        Button closeButton = new Button(this);
        closeButton.setText("Close");
        closeButton.setOnClickListener(v -> finish());
        root.addView(closeButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        setContentView(root);
        LauncherTheme.applyRainbowBackgroundIfNeeded(this);
        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        updateStatus();
    }

    private void updateStatus() {
        if (statusText == null) {
            return;
        }

        boolean granted = AndroidMicrophonePermission.isGranted(this);
        statusText.setText(granted
                ? "Status: microphone permission granted"
                : "Status: microphone permission not granted");
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}

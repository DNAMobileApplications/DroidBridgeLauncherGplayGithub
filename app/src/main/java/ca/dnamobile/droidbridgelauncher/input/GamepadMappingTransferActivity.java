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

package ca.dnamobile.droidbridgelauncher.input;

import ca.dnamobile.droidbridgelauncher.LauncherTheme;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Tiny document-picker bridge for controller profile import/export.
 *
 * Keeping the ActivityResult/file-picker work here avoids making every caller
 * carry extra launchers. GamepadMappingDialog and LauncherSettingsActivity can
 * both start this Activity with the selected profile key.
 */
public final class GamepadMappingTransferActivity extends AppCompatActivity {
    private static final String EXTRA_MODE = "ca.dnamobile.droidbridgelauncher.input.extra.MODE";
    private static final String EXTRA_PROFILE_KEY = "ca.dnamobile.droidbridgelauncher.input.extra.PROFILE_KEY";
    private static final String MODE_EXPORT = "export";
    private static final String MODE_IMPORT = "import";
    private static final int REQUEST_EXPORT = 4101;
    private static final int REQUEST_IMPORT = 4102;

    private static final int COLOR_TEXT_PRIMARY = Color.rgb(238, 241, 248);
    private static final int COLOR_TEXT_SECONDARY = Color.rgb(198, 204, 216);
    private static final int COLOR_ACCENT = Color.rgb(37, 211, 128);

    @Nullable private String profileKey;
    @Nullable private String pendingExportJson;
    private boolean launchedPicker;

    public static void startExport(@NonNull Context context, @Nullable String profileKey) {
        Intent intent = new Intent(context, GamepadMappingTransferActivity.class);
        intent.putExtra(EXTRA_MODE, MODE_EXPORT);
        intent.putExtra(EXTRA_PROFILE_KEY, profileKey);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public static void startImport(@NonNull Context context, @Nullable String profileKey) {
        Intent intent = new Intent(context, GamepadMappingTransferActivity.class);
        intent.putExtra(EXTRA_MODE, MODE_IMPORT);
        intent.putExtra(EXTRA_PROFILE_KEY, profileKey);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /** Optional settings-page wiring. Call this from LauncherSettingsActivity.setupControllerSettings(). */
    public static void installSettingsUi(@NonNull Activity activity, @NonNull LinearLayout parent) {
        if (parent.findViewWithTag("gamepad_mapping_transfer") != null) return;

        LinearLayout container = new LinearLayout(activity);
        container.setTag("gamepad_mapping_transfer");
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, dp(activity, 14), 0, 0);

        View divider = new View(activity);
        divider.setBackgroundColor(0x33000000);
        container.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(activity, 1))
        ));

        TextView title = new TextView(activity);
        title.setText("Controller profile import / export");
        title.setTextSize(16f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleParams.topMargin = dp(activity, 12);
        container.addView(title, titleParams);

        TextView summary = new TextView(activity);
        summary.setText("Save the selected built-in controller mapping as a portable JSON file, or import one into the currently selected profile.");
        summary.setTextSize(13f);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        summaryParams.topMargin = dp(activity, 2);
        container.addView(summary, summaryParams);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowParams.topMargin = dp(activity, 8);
        container.addView(row, rowParams);

        Button exportButton = buildSettingsButton(activity, "Export gamepad profile");
        Button importButton = buildSettingsButton(activity, "Import gamepad profile");
        row.addView(exportButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        importParams.leftMargin = dp(activity, 8);
        row.addView(importButton, importParams);

        exportButton.setOnClickListener(view -> {
            GamepadMappingStore store = GamepadMappingStore.get(activity);
            startExport(activity, store.getActiveProfileKey());
        });
        importButton.setOnClickListener(view -> {
            GamepadMappingStore store = GamepadMappingStore.get(activity);
            startImport(activity, store.getActiveProfileKey());
        });

        parent.addView(container, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherTheme.apply(this);
        super.onCreate(savedInstanceState);
        profileKey = getIntent().getStringExtra(EXTRA_PROFILE_KEY);

        TextView placeholder = new TextView(this);
        placeholder.setText("Opening controller profile picker…");
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setPadding(dp(this, 24), dp(this, 24), dp(this, 24), dp(this, 24));
        setContentView(placeholder);
        LauncherTheme.applyRainbowBackgroundIfNeeded(this);

        if (savedInstanceState == null) {
            launchRequestedPicker();
        }
    }

    private void launchRequestedPicker() {
        if (launchedPicker) return;
        launchedPicker = true;

        String mode = getIntent().getStringExtra(EXTRA_MODE);
        if (MODE_IMPORT.equals(mode)) {
            launchImportPicker();
        } else {
            launchExportPicker();
        }
    }

    private void launchExportPicker() {
        try {
            GamepadMappingStore store = GamepadMappingStore.get(this);
            String safeProfile = profileKey == null ? store.getActiveProfileKey() : profileKey;
            pendingExportJson = store.exportProfileToJson(safeProfile).toString(2);

            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, buildExportFileName(store.getProfileDisplayName(safeProfile)));
            startActivityForResult(intent, REQUEST_EXPORT);
        } catch (Throwable throwable) {
            toastFailure("Unable to prepare controller export", throwable);
            finish();
        }
    }

    private void launchImportPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/json",
                "text/json",
                "text/plain",
                "application/octet-stream"
        });
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            finish();
            return;
        }

        Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT) {
            writeExport(uri);
        } else if (requestCode == REQUEST_IMPORT) {
            readImport(uri);
        } else {
            finish();
        }
    }

    private void writeExport(@NonNull Uri uri) {
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new IllegalStateException("Unable to open export file.");
            String json = pendingExportJson == null ? "{}" : pendingExportJson;
            output.write(json.getBytes(StandardCharsets.UTF_8));
            output.flush();
            Toast.makeText(this, "Controller profile exported.", Toast.LENGTH_LONG).show();
        } catch (Throwable throwable) {
            toastFailure("Controller export failed", throwable);
        }
        finish();
    }

    private void readImport(@NonNull Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("Unable to open selected file.");
            String rawJson = new String(readAllBytes(input), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(rawJson);
            GamepadMappingStore store = GamepadMappingStore.get(this);
            String safeProfile = profileKey == null ? store.getActiveProfileKey() : profileKey;
            store.importProfileFromJson(root, safeProfile);
            Toast.makeText(this, "Controller profile imported.", Toast.LENGTH_LONG).show();
        } catch (Throwable throwable) {
            toastFailure("Controller import failed", throwable);
        }
        finish();
    }

    @NonNull
    private static byte[] readAllBytes(@NonNull InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    @NonNull
    private static String buildExportFileName(@NonNull String profileName) {
        String safeName = profileName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (safeName.isEmpty()) safeName = "controller-profile";
        return "droidbridge-gamepad-" + safeName + ".json";
    }

    private void toastFailure(@NonNull String prefix, @NonNull Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.trim().isEmpty()) message = throwable.toString();
        Toast.makeText(this, prefix + ": " + message, Toast.LENGTH_LONG).show();
    }

    @NonNull
    private static Button buildSettingsButton(@NonNull Activity activity, @NonNull String text) {
        Button button = new Button(activity);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(COLOR_ACCENT);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 10), dp(activity, 6));
        return button;
    }

    private static int dp(@NonNull Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}

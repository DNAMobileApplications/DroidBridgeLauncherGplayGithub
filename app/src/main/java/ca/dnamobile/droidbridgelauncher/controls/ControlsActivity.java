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

package ca.dnamobile.droidbridgelauncher.controls;

import ca.dnamobile.droidbridgelauncher.LauncherTheme;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.ui.LauncherDialogStyle;
import ca.dnamobile.droidbridgelauncher.utils.FullscreenUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class ControlsActivity extends AppCompatActivity {
    private static final int REQUEST_IMPORT_CONTROLS = 9011;
    private static final int REQUEST_EXPORT_CONTROLS = 9012;

    private final ArrayList<File> layoutFiles = new ArrayList<>();
    @Nullable private File pendingExportFile;
    private int pendingImportMode = TouchControlsLayoutData.IMPORT_MODE_DROIDBRIDGE;
    private ArrayAdapter<File> adapter;
    private ListView listView;
    private TextView summary;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherTheme.apply(this);
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(12);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);
        setContentView(root);
        LauncherTheme.applyRainbowBackgroundIfNeeded(this);

        TextView title = new TextView(this);
        title.setText("Touch Controls");
        title.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(22f);
        title.setGravity(Gravity.START);
        title.setPadding(dp(2), 0, dp(2), dp(2));
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        summary = new TextView(this);
        summary.setTextSize(13f);
        summary.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        summary.setLineSpacing(dp(1), 1.0f);
        summary.setPadding(dp(2), 0, dp(2), dp(8));
        root.addView(summary, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actionsCard = new LinearLayout(this);
        actionsCard.setOrientation(LinearLayout.VERTICAL);
        actionsCard.setPadding(dp(10), dp(9), dp(10), dp(9));
        actionsCard.setBackground(cardBackground(false));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, dp(8));
        root.addView(actionsCard, cardParams);

        TextView actionsTitle = new TextView(this);
        actionsTitle.setText("Layouts");
        actionsTitle.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        actionsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        actionsTitle.setTextSize(14f);
        actionsTitle.setPadding(0, 0, 0, dp(6));
        actionsCard.addView(actionsTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actionsCard.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button importButton = actionButton("Import");
        importButton.setOnClickListener(view -> showImportModeDialog());
        actions.addView(importButton, actionButtonParams());

        Button editButton = actionButton("Edit Current");
        editButton.setOnClickListener(view -> startActivity(new Intent(this, ControlsEditorActivity.class)));
        actions.addView(editButton, actionButtonParams());

        Button closeButton = actionButton("Close");
        closeButton.setOnClickListener(view -> finish());
        actions.addView(closeButton, actionButtonParams());

        listView = new ListView(this);
        listView.setDivider(new ColorDrawable(LauncherDialogStyle.COLOR_CARD_STROKE));
        listView.setDividerHeight(dp(1));
        listView.setCacheColorHint(0x00000000);
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(3), 0, dp(3));
        listView.setBackground(cardBackground(false));
        adapter = new ArrayAdapter<File>(this, 0, new ArrayList<>()) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                LayoutRowHolder holder;
                LinearLayout row;
                if (convertView instanceof LinearLayout && convertView.getTag() instanceof LayoutRowHolder) {
                    row = (LinearLayout) convertView;
                    holder = (LayoutRowHolder) row.getTag();
                } else {
                    row = new LinearLayout(ControlsActivity.this);
                    row.setOrientation(LinearLayout.VERTICAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setMinimumHeight(dp(44));
                    row.setPadding(dp(14), dp(5), dp(14), dp(5));

                    TextView titleView = new TextView(ControlsActivity.this);
                    titleView.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
                    titleView.setTextSize(13.5f);
                    titleView.setTypeface(Typeface.DEFAULT_BOLD);
                    titleView.setSingleLine(true);
                    titleView.setEllipsize(TextUtils.TruncateAt.END);
                    titleView.setIncludeFontPadding(false);
                    row.addView(titleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                    TextView subtitleView = new TextView(ControlsActivity.this);
                    subtitleView.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
                    subtitleView.setTextSize(12f);
                    subtitleView.setSingleLine(true);
                    subtitleView.setEllipsize(TextUtils.TruncateAt.END);
                    subtitleView.setIncludeFontPadding(false);
                    subtitleView.setPadding(0, dp(3), 0, 0);
                    row.addView(subtitleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                    holder = new LayoutRowHolder(titleView, subtitleView);
                    row.setTag(holder);
                }

                File file = getItem(position);
                if (file == null) {
                    holder.title.setText("");
                    holder.subtitle.setText("");
                    row.setBackgroundColor(0x00000000);
                    return row;
                }

                TouchControlsLayoutData data = TouchControlsStore.loadLayout(file);
                boolean selected = file.getAbsolutePath().equals(ControlsPreferences.getSelectedLayoutPath(ControlsActivity.this));
                holder.title.setText((selected ? "✓ " : "") + displayLayoutTitle(data, file));
                holder.subtitle.setText(layoutSubtitle(data, file));
                row.setBackground(rowBackground(selected));
                return row;
            }
        };
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> selectLayout(position));
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            showLayoutOptions(position);
            return true;
        });
        root.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        refreshList();
        root.post(this::enableImmersiveSafely);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (listView != null) listView.post(this::enableImmersiveSafely);
        else enableImmersiveSafely();
        refreshList();
    }

    private void enableImmersiveSafely() {
        try { FullscreenUtils.enableImmersive(this); }
        catch (Throwable throwable) { Logging.e("ControlsActivity", "Unable to enable immersive mode", throwable); }
    }

    private void refreshList() {
        layoutFiles.clear();
        layoutFiles.addAll(TouchControlsStore.listLayouts(this));
        adapter.clear();
        adapter.addAll(layoutFiles);
        adapter.notifyDataSetChanged();
        summary.setText("Import DroidBridge layouts or keep other-launcher coordinate rules. Long-press a layout for options.");
    }

    private void selectLayout(int position) {
        if (position < 0 || position >= layoutFiles.size()) return;
        File file = layoutFiles.get(position);
        ControlsPreferences.setSelectedLayoutPath(this, file.getAbsolutePath());
        Toast.makeText(this, "Selected " + file.getName(), Toast.LENGTH_SHORT).show();
        refreshList();
    }

    private void showLayoutOptions(int position) {
        if (position < 0 || position >= layoutFiles.size()) return;
        File file = layoutFiles.get(position);

        TouchControlsLayoutData layoutData = TouchControlsStore.loadLayout(file);
        LinearLayout root = LauncherDialogStyle.createDialogRoot(
                this,
                displayLayoutTitle(layoutData, file),
                "Choose what to do with this controller profile."
        );
        final AlertDialog[] holder = new AlertDialog[1];

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        boolean useTwoColumns = screenWidth >= dp(600) || screenWidth > screenHeight;

        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);

        LinearLayout firstTarget = options;
        LinearLayout secondTarget = options;
        if (useTwoColumns) {
            firstTarget = createDialogOptionRow();
            secondTarget = createDialogOptionRow();
            options.addView(firstTarget, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            options.addView(secondTarget, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }

        ScrollView optionsScroll = new ScrollView(this);
        optionsScroll.setFillViewport(false);
        optionsScroll.setClipToPadding(false);
        optionsScroll.setVerticalScrollBarEnabled(!useTwoColumns);
        optionsScroll.addView(options, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout.LayoutParams optionsScrollParams;
        if (useTwoColumns) {
            // Landscape/wide devices get a compact 2x2 option grid so Export and Delete
            // remain visible without the Material dialog clipping the bottom rows.
            optionsScrollParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
        } else {
            int maxOptionsHeight = Math.min(dp(420), Math.max(dp(220), Math.round(screenHeight * 0.55f)));
            optionsScrollParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    maxOptionsHeight
            );
        }
        root.addView(optionsScroll, optionsScrollParams);

        addLayoutOption(
                firstTarget,
                "Use this layout",
                "Select this profile for the next game launch.",
                false,
                view -> {
                    if (holder[0] != null) holder[0].dismiss();
                    selectLayout(position);
                }
        );
        addLayoutOption(
                firstTarget,
                "Edit",
                "Open this profile in the touch-control editor.",
                false,
                view -> {
                    if (holder[0] != null) holder[0].dismiss();
                    ControlsPreferences.setSelectedLayoutPath(this, file.getAbsolutePath());
                    startActivity(new Intent(this, ControlsEditorActivity.class));
                }
        );
        addLayoutOption(
                secondTarget,
                "Export JSON",
                "Save or share a copy of this controller profile.",
                false,
                view -> {
                    if (holder[0] != null) holder[0].dismiss();
                    openExportPicker(file);
                }
        );
        addLayoutOption(
                secondTarget,
                "Delete",
                "Remove this profile from DroidBridge.",
                true,
                view -> {
                    if (holder[0] != null) holder[0].dismiss();
                    confirmDelete(file);
                }
        );

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        holder[0] = dialog;
        dialog.setOnShowListener(d -> LauncherDialogStyle.styleDialogChrome(this, dialog));
        dialog.show();
        LauncherDialogStyle.styleDialogChrome(this, dialog);
    }

    private void addLayoutOption(
            @NonNull LinearLayout root,
            @NonNull String title,
            @NonNull String description,
            boolean destructive,
            @NonNull View.OnClickListener listener
    ) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(listener);

        GradientDrawable background = LauncherDialogStyle.roundedDrawable(
                this,
                destructive ? LauncherDialogStyle.COLOR_CARD_BG : LauncherDialogStyle.COLOR_CARD_BG_PRESSED,
                destructive ? LauncherDialogStyle.COLOR_ERROR : LauncherDialogStyle.COLOR_CARD_STROKE,
                14
        );
        card.setBackground(background);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(15f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(destructive
                ? LauncherDialogStyle.COLOR_ERROR
                : LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        titleView.setIncludeFontPadding(false);
        card.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView descriptionView = new TextView(this);
        descriptionView.setText(description);
        descriptionView.setTextSize(12.5f);
        descriptionView.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        descriptionView.setPadding(0, dp(3), 0, 0);
        descriptionView.setIncludeFontPadding(false);
        card.addView(descriptionView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout.LayoutParams params;
        if (root.getOrientation() == LinearLayout.HORIZONTAL) {
            params = new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
            );
            params.setMargins(dp(4), dp(4), dp(4), dp(4));
        } else {
            params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, dp(4), 0, dp(4));
        }
        root.addView(card, params);
    }

    @NonNull
    private LinearLayout createDialogOptionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        return row;
    }

    private void confirmDelete(@NonNull File file) {
        if (file.equals(TouchControlsStore.getDefaultLayoutFile(this))) {
            Toast.makeText(this, "Default layout cannot be deleted.", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Delete layout?")
                .setMessage(file.getName())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Delete", (unused, which) -> {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                    if (file.getAbsolutePath().equals(ControlsPreferences.getSelectedLayoutPath(this))) {
                        ControlsPreferences.setSelectedLayoutPath(this, TouchControlsStore.getDefaultLayoutFile(this).getAbsolutePath());
                    }
                    refreshList();
                })
                .create();
        dialog.setOnShowListener(d -> LauncherDialogStyle.styleDialogChrome(this, dialog));
        dialog.show();
        LauncherDialogStyle.styleDialogChrome(this, dialog);
    }

    private void showImportModeDialog() {
        LinearLayout root = LauncherDialogStyle.createDialogRoot(
                this,
                "Import touch controls",
                "Choose how DroidBridge should treat the selected JSON layout."
        );

        final AlertDialog[] holder = new AlertDialog[1];
        addImportChoice(
                root,
                "DroidBridge profile",
                "Convert/import using DroidBridge scaling, sizing, and positioning rules. Use this for DroidBridge-made layouts or when you want the default DroidBridge behavior.",
                view -> {
                    if (holder[0] != null) holder[0].dismiss();
                    openImportPicker(TouchControlsLayoutData.IMPORT_MODE_DROIDBRIDGE);
                }
        );
        addImportChoice(
                root,
                "Legacy launcher profile",
                "Use for compatible third-party controls.json files. Preserves their screen-position formulas, density conversions, saved button scale, and legacy version rules.",
                view -> {
                    if (holder[0] != null) holder[0].dismiss();
                    openImportPicker(TouchControlsLayoutData.IMPORT_MODE_OTHER_LAUNCHER);
                }
        );

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        holder[0] = dialog;
        dialog.setOnShowListener(unused -> LauncherDialogStyle.styleDialogChrome(this, dialog));
        dialog.show();
        LauncherDialogStyle.styleDialogChrome(this, dialog);
    }

    private void addImportChoice(
            @NonNull LinearLayout root,
            @NonNull String title,
            @NonNull String message,
            @NonNull View.OnClickListener listener
    ) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setClickable(true);
        card.setFocusable(true);
        card.setBackground(cardBackground(true));
        card.setOnClickListener(listener);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(16f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        card.addView(titleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView messageView = new TextView(this);
        messageView.setText(message);
        messageView.setTextSize(13f);
        messageView.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        messageView.setLineSpacing(dp(1), 1.0f);
        messageView.setPadding(0, dp(4), 0, 0);
        card.addView(messageView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(6), 0, dp(6));
        root.addView(card, params);
    }

    private void openImportPicker(int importMode) {
        pendingImportMode = importMode;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, REQUEST_IMPORT_CONTROLS); }
        catch (ActivityNotFoundException throwable) { Toast.makeText(this, "No file picker found.", Toast.LENGTH_LONG).show(); }
    }

    private void openExportPicker(@NonNull File file) {
        pendingExportFile = file;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, safeExportName(file));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { startActivityForResult(intent, REQUEST_EXPORT_CONTROLS); }
        catch (ActivityNotFoundException throwable) {
            pendingExportFile = null;
            Toast.makeText(this, "No file picker found.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT_CONTROLS) { handleImportResult(resultCode, data); return; }
        if (requestCode == REQUEST_EXPORT_CONTROLS) handleExportResult(resultCode, data);
    }

    private void handleImportResult(int resultCode, @Nullable Intent data) {
        int importMode = pendingImportMode;
        pendingImportMode = TouchControlsLayoutData.IMPORT_MODE_DROIDBRIDGE;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Throwable ignored) { }
        try {
            File imported = TouchControlsStore.saveImportedLayout(this, uri, importMode);
            Toast.makeText(this, "Imported " + imported.getName(), Toast.LENGTH_SHORT).show();
            refreshList();
        } catch (Throwable throwable) {
            Logging.e("ControlsActivity", "Unable to import controls", throwable);
            Toast.makeText(this, "Import failed: " + throwable.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void handleExportResult(int resultCode, @Nullable Intent data) {
        File source = pendingExportFile;
        pendingExportFile = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || source == null) return;
        Uri uri = data.getData();
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new IllegalStateException("Unable to open export destination.");
            String json = TouchControlsStore.readText(source);
            output.write(json.getBytes(StandardCharsets.UTF_8));
            output.flush();
            Toast.makeText(this, "Exported " + source.getName(), Toast.LENGTH_SHORT).show();
        } catch (Throwable throwable) {
            Logging.e("ControlsActivity", "Unable to export controls", throwable);
            Toast.makeText(this, "Export failed: " + throwable.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @NonNull
    private Button actionButton(@NonNull String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setTextSize(13f);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setBackground(LauncherDialogStyle.roundedDrawable(this, LauncherDialogStyle.COLOR_CARD_BG_PRESSED, LauncherDialogStyle.COLOR_CARD_STROKE, 14));
        button.setElevation(0f);
        button.setStateListAnimator(null);
        return button;
    }

    @NonNull
    private LinearLayout.LayoutParams actionButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1f);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    @NonNull
    private GradientDrawable rowBackground(boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(selected ? LauncherDialogStyle.COLOR_CARD_BG_PRESSED : 0x00000000);
        drawable.setCornerRadius(dp(8));
        return drawable;
    }

    @NonNull
    private String displayLayoutTitle(@NonNull TouchControlsLayoutData data, @NonNull File file) {
        String imported = data.importedFileName.trim();
        if (!imported.isEmpty()) {
            String importedBase = baseNameWithoutJson(imported);
            if (!importedBase.isEmpty()) return importedBase;
        }

        String name = data.name == null ? "" : data.name.trim();
        if (isGenericImportedName(name) && !file.equals(TouchControlsStore.getDefaultLayoutFile(this))) {
            String fileBase = baseNameWithoutJson(file.getName());
            if (!fileBase.isEmpty()) return fileBase;
        }
        return name.isEmpty() ? baseNameWithoutJson(file.getName()) : name;
    }

    @NonNull
    private String layoutSubtitle(@NonNull TouchControlsLayoutData data, @NonNull File file) {
        String profile = data.usesOtherLauncherProfile() ? "Other launcher" : "DroidBridge";
        String source = data.importedFileName.trim().isEmpty() ? file.getName() : data.importedFileName.trim();
        if (source.trim().isEmpty()) return profile;
        return profile + " • " + source;
    }

    private static boolean isGenericImportedName(@NonNull String name) {
        String lower = name.trim().toLowerCase();
        return lower.equals("imported controls")
                || lower.equals("imported droidbridge/zalith controls")
                || lower.equals("touch controls");
    }

    @NonNull
    private static String baseNameWithoutJson(@NonNull String name) {
        String clean = name.trim();
        int slash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < clean.length()) clean = clean.substring(slash + 1);
        if (clean.toLowerCase().endsWith(".json")) clean = clean.substring(0, clean.length() - 5);
        return clean.trim();
    }

    @NonNull
    private GradientDrawable cardBackground(boolean pressedTone) {
        return LauncherDialogStyle.roundedDrawable(
                this,
                pressedTone ? LauncherDialogStyle.COLOR_CARD_BG_PRESSED : LauncherDialogStyle.COLOR_CARD_BG,
                LauncherDialogStyle.COLOR_CARD_STROKE,
                18
        );
    }

    private static final class LayoutRowHolder {
        @NonNull final TextView title;
        @NonNull final TextView subtitle;

        LayoutRowHolder(@NonNull TextView title, @NonNull TextView subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    @NonNull
    private static String safeExportName(@NonNull File file) {
        String name = file.getName();
        if (!name.toLowerCase().endsWith(".json")) name += ".json";
        return name.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

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

package ca.dnamobile.droidbridgelauncher.modmanager;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.modmanager.ModpackExportManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class ModpackExportOptionsDialog {
    private static final int COLOR_DIALOG_BG = Color.rgb(30, 34, 42);
    private static final int COLOR_CARD_BG = Color.rgb(38, 43, 53);
    private static final int COLOR_CARD_BG_PRESSED = Color.rgb(43, 49, 60);
    private static final int COLOR_CARD_STROKE = Color.rgb(54, 61, 74);
    private static final int COLOR_TEXT_PRIMARY = Color.rgb(238, 241, 248);
    private static final int COLOR_TEXT_SECONDARY = Color.rgb(198, 204, 216);
    private static final int COLOR_TEXT_MUTED = Color.rgb(150, 159, 176);
    private static final int COLOR_ACCENT = Color.rgb(37, 211, 128);
    private static final float DIALOG_DIM_NORMAL = 0.58f;

    public interface Listener {
        void onExport(@NonNull ModpackExportManager.ExportOptions options);
    }

    private ModpackExportOptionsDialog() {
    }

    public static void show(
            @NonNull AppCompatActivity activity,
            @NonNull File gameDirectory,
            @NonNull ModpackExportManager.Platform platform,
            @NonNull Listener listener
    ) {
        show(activity, gameDirectory, null, platform, listener);
    }

    public static void show(
            @NonNull AppCompatActivity activity,
            @NonNull File gameDirectory,
            @Nullable File iconFile,
            @NonNull ModpackExportManager.Platform platform,
            @NonNull Listener listener
    ) {
        ScrollView scrollView = new ScrollView(activity);
        scrollView.setFillViewport(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setClipToPadding(false);
        scrollView.setBackgroundColor(COLOR_DIALOG_BG);
        scrollView.setPadding(0, 0, 0, dp(activity, 4));

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_DIALOG_BG);
        int padding = dp(activity, 18);
        root.setPadding(padding, padding, padding, dp(activity, 8));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(activity);
        title.setText("Export Modpack");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(COLOR_TEXT_PRIMARY);
        title.setPadding(dp(activity, 2), 0, dp(activity, 2), dp(activity, 6));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView info = new TextView(activity);
        info.setText("Choose what goes into this export. Required manifest, loader metadata, and instance configuration stay locked so edited mod settings are not lost. Modified Modrinth files are bundled as overrides automatically so imports do not fail with SHA-1 mismatches.");
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        info.setTextColor(COLOR_TEXT_SECONDARY);
        info.setLineSpacing(0f, 1.08f);
        info.setPadding(dp(activity, 2), 0, dp(activity, 2), dp(activity, 12));
        root.addView(info, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout requiredCard = addCard(activity, root);
        addCardTitle(activity, requiredCard, "Required export metadata");
        addLockedRow(
                activity,
                requiredCard,
                "Platform manifest",
                platform == ModpackExportManager.Platform.MODRINTH
                        ? "modrinth.index.json and dependency metadata"
                        : platform == ModpackExportManager.Platform.CURSEFORGE
                        ? "manifest.json, modlist.html, and dependency metadata"
                        : "instance.cfg and mmc-pack.json"
        );
        addLockedRow(activity, requiredCard, "Minecraft + loader", "Minecraft version and selected loader are always included");

        LinearLayout contentCard = addCard(activity, root);
        addCardTitle(activity, contentCard, "Content to include");
        addInfoText(activity, contentCard, "Saves are available for private sharing, but stay off by default. Config/defaultconfigs/KubeJS/scripts and other known mod configuration data are always preserved. If DroidBridge or another tool patched a mod jar, it is exported as an override instead of a stale remote download.");

        boolean hasIcon = iconFile != null && iconFile.isFile() && iconFile.length() > 0;
        CheckBox includeIcon = addCheckRow(
                activity,
                contentCard,
                "Instance icon",
                hasIcon ? "Custom pack icon found" : "No custom instance icon was found",
                true,
                hasIcon
        );
        CheckBox includeMods = addCheckRow(
                activity,
                contentCard,
                "Mods",
                describeFolder(gameDirectory, "mods", ".jar", ".zip"),
                true,
                folderExists(gameDirectory, "mods")
        );
        CheckBox includeResourcePacks = addCheckRow(
                activity,
                contentCard,
                "Resource / texture packs",
                describePackFolders(gameDirectory),
                true,
                folderExists(gameDirectory, "resourcepacks") || folderExists(gameDirectory, "texturepacks")
        );
        CheckBox includeShaderPacks = addCheckRow(
                activity,
                contentCard,
                "Shader packs",
                describeFolder(gameDirectory, "shaderpacks", ".zip"),
                true,
                folderExists(gameDirectory, "shaderpacks")
        );
        addLockedRow(
                activity,
                contentCard,
                "Mod configuration",
                describeConfigurationData(gameDirectory)
        );
        CheckBox includeOptionsTxt = addCheckRow(
                activity,
                contentCard,
                "options.txt",
                new File(gameDirectory, "options.txt").isFile()
                        ? "Personal game settings; included by default to match the old exporter"
                        : "Missing from this instance",
                true,
                new File(gameDirectory, "options.txt").isFile()
        );
        CheckBox includeSaves = addCheckRow(
                activity,
                contentCard,
                "Saves / worlds",
                describeWorldsFolder(gameDirectory),
                false,
                folderExists(gameDirectory, "saves")
        );

        int maxContentHeight = Math.min(
                dp(activity, 620),
                Math.max(dp(activity, 300), activity.getResources().getDisplayMetrics().heightPixels - dp(activity, 150))
        );
        scrollView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                maxContentHeight
        ));

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Export", null)
                .create();

        dialog.setOnShowListener(value -> {
            styleDialogChrome(activity, dialog);
            TextView positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positiveButton != null) {
                positiveButton.setOnClickListener(view -> {
                    listener.onExport(new ModpackExportManager.ExportOptions(
                            includeIcon.isChecked(),
                            includeMods.isChecked(),
                            includeResourcePacks.isChecked(),
                            includeShaderPacks.isChecked(),
                            true,
                            true,
                            true,
                            true,
                            includeOptionsTxt.isChecked(),
                            includeSaves.isChecked()
                    ));
                    dialog.dismiss();
                });
            }
        });
        dialog.setOnDismissListener(value -> activity.getWindow().getDecorView().postDelayed(() -> {
            View decorView = activity.getWindow().getDecorView();
            decorView.setSystemUiVisibility(decorView.getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }, 80L));
        dialog.show();
        styleDialogChrome(activity, dialog);
    }

    @NonNull
    private static LinearLayout addCard(@NonNull Activity activity, @NonNull LinearLayout root) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = dp(activity, 14);
        card.setPadding(p, p, p, p);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_CARD_BG);
        bg.setCornerRadius(dp(activity, 18));
        bg.setStroke(dp(activity, 1), COLOR_CARD_STROKE);
        card.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, 0, 0, dp(activity, 12));
        root.addView(card, lp);
        return card;
    }

    private static void addCardTitle(@NonNull Activity activity, @NonNull LinearLayout root, @NonNull String title) {
        TextView header = new TextView(activity);
        header.setText(title);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(COLOR_TEXT_PRIMARY);
        header.setPadding(0, 0, 0, dp(activity, 8));
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    @NonNull
    private static TextView addInfoText(@NonNull Activity activity, @NonNull LinearLayout root, @NonNull String text) {
        TextView info = new TextView(activity);
        info.setText(text);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        info.setTextColor(COLOR_TEXT_SECONDARY);
        info.setPadding(0, 0, 0, dp(activity, 8));
        root.addView(info, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return info;
    }

    private static void addLockedRow(
            @NonNull Activity activity,
            @NonNull LinearLayout parent,
            @NonNull String title,
            @NonNull String subtitle
    ) {
        LinearLayout row = buildBaseRow(activity, true);

        LinearLayout textColumn = buildTextColumn(activity, title, subtitle, true);
        row.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView locked = new TextView(activity);
        locked.setText("Locked");
        locked.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        locked.setTypeface(Typeface.DEFAULT_BOLD);
        locked.setTextColor(COLOR_TEXT_SECONDARY);
        locked.setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 8), dp(activity, 4));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Color.TRANSPARENT);
        pill.setStroke(dp(activity, 1), COLOR_CARD_STROKE);
        pill.setCornerRadius(dp(activity, 20));
        locked.setBackground(pill);
        row.addView(locked, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        addRow(parent, row, activity);
    }

    @NonNull
    private static CheckBox addCheckRow(
            @NonNull Activity activity,
            @NonNull LinearLayout parent,
            @NonNull String title,
            @NonNull String subtitle,
            boolean checked,
            boolean enabled
    ) {
        LinearLayout row = buildBaseRow(activity, enabled);
        row.setClickable(enabled);
        row.setFocusable(enabled);

        LinearLayout textColumn = buildTextColumn(activity, title, subtitle, enabled);
        row.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        CheckBox checkBox = new CheckBox(activity);
        checkBox.setChecked(enabled && checked);
        checkBox.setEnabled(enabled);
        checkBox.setButtonTintList(buildCheckboxTint());
        row.addView(checkBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        row.setOnClickListener(view -> {
            if (checkBox.isEnabled()) checkBox.setChecked(!checkBox.isChecked());
        });

        addRow(parent, row, activity);
        return checkBox;
    }

    @NonNull
    private static LinearLayout buildBaseRow(@NonNull Activity activity, boolean enabled) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(activity, 62));
        row.setPadding(dp(activity, 14), dp(activity, 9), dp(activity, 10), dp(activity, 9));
        row.setEnabled(enabled);
        row.setAlpha(enabled ? 1f : 0.48f);
        row.setBackground(rowBackground(activity));
        return row;
    }

    @NonNull
    private static LinearLayout buildTextColumn(
            @NonNull Activity activity,
            @NonNull String title,
            @NonNull String subtitle,
            boolean enabled
    ) {
        LinearLayout textColumn = new LinearLayout(activity);
        textColumn.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        titleView.setTextColor(enabled ? COLOR_TEXT_PRIMARY : COLOR_TEXT_MUTED);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        textColumn.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView subtitleView = new TextView(activity);
        subtitleView.setText(subtitle);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitleView.setTextColor(enabled ? COLOR_TEXT_SECONDARY : COLOR_TEXT_MUTED);
        subtitleView.setSingleLine(false);
        subtitleView.setMaxLines(2);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        subtitleParams.topMargin = dp(activity, 2);
        textColumn.addView(subtitleView, subtitleParams);
        return textColumn;
    }

    private static void addRow(@NonNull LinearLayout parent, @NonNull View row, @NonNull Activity activity) {
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rowParams.topMargin = dp(activity, 8);
        parent.addView(row, rowParams);
    }

    @NonNull
    private static GradientDrawable rowBackground(@NonNull Activity activity) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(COLOR_CARD_BG_PRESSED);
        background.setStroke(dp(activity, 1), COLOR_CARD_STROKE);
        background.setCornerRadius(dp(activity, 14));
        return background;
    }

    @NonNull
    private static ColorStateList buildCheckboxTint() {
        return new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked, android.R.attr.state_enabled},
                        new int[]{android.R.attr.state_enabled},
                        new int[]{}
                },
                new int[]{COLOR_ACCENT, COLOR_TEXT_MUTED, COLOR_TEXT_MUTED}
        );
    }

    private static void styleDialogChrome(@NonNull Activity activity, @NonNull AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(roundedDrawable(activity, COLOR_DIALOG_BG, COLOR_DIALOG_BG, 22));
            window.setDimAmount(DIALOG_DIM_NORMAL);
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            window.setLayout(Math.min(screenWidth - dp(activity, 36), dp(activity, 760)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        tintDialogButton(dialog, AlertDialog.BUTTON_POSITIVE);
        tintDialogButton(dialog, AlertDialog.BUTTON_NEGATIVE);
        tintDialogButton(dialog, AlertDialog.BUTTON_NEUTRAL);
    }

    private static void tintDialogButton(@NonNull AlertDialog dialog, int whichButton) {
        TextView button = dialog.getButton(whichButton);
        if (button != null) {
            button.setTextColor(COLOR_ACCENT);
            button.setTypeface(Typeface.DEFAULT_BOLD);
        }
    }

    @NonNull
    private static GradientDrawable roundedDrawable(
            @NonNull Activity activity,
            int fillColor,
            int strokeColor,
            int cornerDp
    ) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fillColor);
        bg.setCornerRadius(dp(activity, cornerDp));
        bg.setStroke(dp(activity, 1), strokeColor);
        return bg;
    }

    @NonNull
    private static String describeFolder(@NonNull File gameDirectory, @NonNull String folder, @NonNull String... extensions) {
        File directory = new File(gameDirectory, folder);
        if (!directory.isDirectory()) return "Missing from this instance";
        int count = 0;
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (!file.isFile() || file.isHidden()) continue;
                String lower = file.getName().toLowerCase(Locale.US);
                for (String extension : extensions) {
                    if (lower.endsWith(extension)) {
                        count++;
                        break;
                    }
                }
            }
        }
        if (count == 0) return "Folder exists, but no matching files were found";
        return count + " file" + (count == 1 ? "" : "s") + " found";
    }

    @NonNull
    private static String describePackFolders(@NonNull File gameDirectory) {
        int resourceCount = countMatchingFiles(new File(gameDirectory, "resourcepacks"), ".zip");
        int textureCount = countMatchingFiles(new File(gameDirectory, "texturepacks"), ".zip");
        int total = resourceCount + textureCount;
        if (total <= 0) {
            if (folderExists(gameDirectory, "resourcepacks") || folderExists(gameDirectory, "texturepacks")) {
                return "Pack folder exists, but no .zip packs were found";
            }
            return "Missing from this instance";
        }
        if (resourceCount > 0 && textureCount > 0) {
            return resourceCount + " resource pack" + (resourceCount == 1 ? "" : "s")
                    + " + " + textureCount + " texture pack" + (textureCount == 1 ? "" : "s") + " found";
        }
        if (textureCount > 0) return textureCount + " texture pack" + (textureCount == 1 ? "" : "s") + " found";
        return resourceCount + " resource pack" + (resourceCount == 1 ? "" : "s") + " found";
    }

    private static int countMatchingFiles(@NonNull File directory, @NonNull String extension) {
        if (!directory.isDirectory()) return 0;
        int count = 0;
        File[] files = directory.listFiles();
        if (files == null) return 0;
        for (File file : files) {
            if (file.isFile() && !file.isHidden()
                    && file.getName().toLowerCase(Locale.US).endsWith(extension)) count++;
        }
        return count;
    }

    @NonNull
    private static String describeConfigurationData(@NonNull File gameDirectory) {
        String[] folders = {"config", "defaultconfigs", "kubejs", "scripts", "openloader", "global_packs", "paxi", "patchouli_books", "resources"};
        int existingFolders = 0;
        int items = 0;
        for (String folder : folders) {
            File directory = new File(gameDirectory, folder);
            if (!directory.isDirectory()) continue;
            existingFolders++;
            items += countChildren(directory);
        }
        if (existingFolders <= 0) return "No known mod configuration folders found; root config files are still preserved automatically";
        return existingFolders + " config folder" + (existingFolders == 1 ? "" : "s")
                + " found (" + items + " top-level item" + (items == 1 ? "" : "s") + "); always included";
    }

    @NonNull
    private static String describeAnyFolder(@NonNull File gameDirectory, @NonNull String folder) {
        File directory = new File(gameDirectory, folder);
        if (!directory.isDirectory()) return "Missing from this instance";
        int count = countChildren(directory);
        return count <= 0 ? "Folder is empty" : count + " item" + (count == 1 ? "" : "s") + " found";
    }

    @NonNull
    private static String describeWorldsFolder(@NonNull File gameDirectory) {
        File directory = new File(gameDirectory, "saves");
        if (!directory.isDirectory()) return "Missing from this instance";
        int worlds = 0;
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory() && new File(child, "level.dat").isFile()) worlds++;
            }
        }
        if (worlds <= 0) return "Folder exists, but no worlds were found";
        return worlds + " world" + (worlds == 1 ? "" : "s") + " found; off by default for privacy";
    }

    private static int countChildren(@NonNull File directory) {
        File[] files = directory.listFiles();
        return files == null ? 0 : files.length;
    }

    private static boolean folderExists(@NonNull File gameDirectory, @NonNull String folder) {
        return new File(gameDirectory, folder).isDirectory();
    }

    private static int dp(@NonNull Activity activity, int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                activity.getResources().getDisplayMetrics()
        ));
    }
}

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

package ca.dnamobile.droidbridgelauncher.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import ca.dnamobile.droidbridgelauncher.utils.FullscreenUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Non-cancelable install progress dialog for modpack imports/installs.
 * It owns FLAG_KEEP_SCREEN_ON while visible, so every caller gets the same
 * screen-awake behavior and the same launcher dialog styling.
 */
public final class ModpackInstallProgressDialog {
    @NonNull
    private final Activity activity;
    @Nullable
    private AlertDialog dialog;
    @Nullable
    private TextView statusView;
    @Nullable
    private TextView detailView;
    @Nullable
    private ProgressBar progressBar;

    public ModpackInstallProgressDialog(@NonNull Activity activity) {
        this.activity = activity;
    }

    public void show(@NonNull String modpackTitle) {
        dismiss();
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        int outerPadding = LauncherDialogStyle.dp(activity, 18);
        int cardPadding = LauncherDialogStyle.dp(activity, 14);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(outerPadding, outerPadding, outerPadding, LauncherDialogStyle.dp(activity, 10));
        root.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);

        TextView title = new TextView(activity);
        title.setText("Installing modpack");
        title.setTextSize(23);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        title.setPadding(LauncherDialogStyle.dp(activity, 2), 0, LauncherDialogStyle.dp(activity, 2), LauncherDialogStyle.dp(activity, 6));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView subtitle = new TextView(activity);
        subtitle.setText(cleanTitle(modpackTitle));
        subtitle.setTextSize(14);
        subtitle.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        subtitle.setMaxLines(2);
        subtitle.setPadding(LauncherDialogStyle.dp(activity, 2), 0, LauncherDialogStyle.dp(activity, 2), LauncherDialogStyle.dp(activity, 12));
        root.addView(subtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(cardPadding, cardPadding, cardPadding, cardPadding);
        card.setBackground(LauncherDialogStyle.roundedDrawable(
                activity,
                LauncherDialogStyle.COLOR_CARD_BG,
                LauncherDialogStyle.COLOR_CARD_STROKE,
                18
        ));

        statusView = new TextView(activity);
        statusView.setText("Preparing modpack install…");
        statusView.setTextSize(15);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        card.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        detailView = new TextView(activity);
        detailView.setText("Keep DroidBridge open while files are downloaded and imported.");
        detailView.setTextSize(13);
        detailView.setTextColor(LauncherDialogStyle.COLOR_TEXT_MUTED);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        detailParams.topMargin = LauncherDialogStyle.dp(activity, 6);
        card.addView(detailView, detailParams);

        progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        tintProgressBar(progressBar);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        progressParams.topMargin = LauncherDialogStyle.dp(activity, 14);
        card.addView(progressBar, progressParams);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        root.addView(card, cardParams);

        TextView footer = new TextView(activity);
        footer.setText("The screen will stay awake until this install finishes.");
        footer.setTextSize(12);
        footer.setTextColor(LauncherDialogStyle.COLOR_TEXT_MUTED);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        footerParams.topMargin = LauncherDialogStyle.dp(activity, 12);
        root.addView(footer, footerParams);

        dialog = new MaterialAlertDialogBuilder(activity)
                .setView(root)
                .setCancelable(false)
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnShowListener(unused -> {
            LauncherDialogStyle.styleDialogChrome(activity, dialog);
            FullscreenUtils.enableImmersive(activity);
        });
        dialog.setOnDismissListener(unused -> {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            statusView = null;
            detailView = null;
            progressBar = null;
            FullscreenUtils.enableImmersive(activity);
        });
        dialog.show();
        LauncherDialogStyle.styleDialogChrome(activity, dialog);
        FullscreenUtils.enableImmersive(activity);
    }

    public void setStatus(@NonNull String message) {
        TextView status = statusView;
        if (status == null) return;
        String clean = message.trim();
        if (clean.isEmpty()) return;
        status.setText(clean);
    }

    public void setProgress(int current, int total) {
        ProgressBar progress = progressBar;
        TextView detail = detailView;
        if (progress == null) return;

        if (total > 0) {
            int safeCurrent = Math.max(0, Math.min(current, total));
            progress.setIndeterminate(false);
            progress.setMax(total);
            progress.setProgress(safeCurrent);
            if (detail != null) detail.setText("Installing " + safeCurrent + " / " + total);
        } else if (current < 0) {
            progress.setIndeterminate(true);
            if (detail != null) detail.setText("Working… this can take a moment for larger packs.");
        }
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    public void dismiss() {
        AlertDialog current = dialog;
        dialog = null;
        if (current != null) {
            current.dismiss();
        } else {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        statusView = null;
        detailView = null;
        progressBar = null;
    }

    @NonNull
    private static String cleanTitle(@NonNull String title) {
        String clean = title.trim();
        return clean.isEmpty() ? "Modpack" : clean;
    }

    private static void tintProgressBar(@NonNull ProgressBar progressBar) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        progressBar.setProgressTintList(ColorStateList.valueOf(LauncherDialogStyle.COLOR_ACCENT));
        progressBar.setIndeterminateTintList(ColorStateList.valueOf(LauncherDialogStyle.COLOR_ACCENT));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(LauncherDialogStyle.COLOR_CARD_STROKE));
    }
}

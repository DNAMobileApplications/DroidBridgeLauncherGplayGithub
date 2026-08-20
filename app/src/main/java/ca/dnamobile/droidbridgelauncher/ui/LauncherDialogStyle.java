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
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Window;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

/**
 * Shared launcher dialog chrome. Keep new custom dialogs routed through this
 * helper so install/progress/settings/dialog-list screens do not drift into
 * mismatched Android-default styling.
 */
public final class LauncherDialogStyle {
    public static final int COLOR_DIALOG_BG = Color.rgb(30, 34, 42);
    public static final int COLOR_CARD_BG = Color.rgb(38, 43, 53);
    public static final int COLOR_CARD_BG_PRESSED = Color.rgb(43, 49, 60);
    public static final int COLOR_CARD_STROKE = Color.rgb(54, 61, 74);
    public static final int COLOR_TEXT_PRIMARY = Color.rgb(238, 241, 248);
    public static final int COLOR_TEXT_SECONDARY = Color.rgb(198, 204, 216);
    public static final int COLOR_TEXT_MUTED = Color.rgb(150, 159, 176);
    public static final int COLOR_ACCENT = Color.rgb(37, 211, 128);
    public static final int COLOR_ACCENT_MUTED = Color.rgb(86, 135, 110);

    public static final float DIALOG_DIM_NORMAL = 0.58f;

    private LauncherDialogStyle() {
    }

    public static void styleDialogChrome(@NonNull Activity activity, @Nullable AlertDialog dialog) {
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(roundedDrawable(activity, COLOR_DIALOG_BG, COLOR_DIALOG_BG, 22));
            window.setDimAmount(DIALOG_DIM_NORMAL);
        }

        tintDialogButton(dialog, AlertDialog.BUTTON_POSITIVE);
        tintDialogButton(dialog, AlertDialog.BUTTON_NEGATIVE);
        tintDialogButton(dialog, AlertDialog.BUTTON_NEUTRAL);
    }

    public static void tintDialogButton(@NonNull AlertDialog dialog, int whichButton) {
        TextView button = dialog.getButton(whichButton);
        if (button != null) {
            button.setTextColor(COLOR_ACCENT);
            button.setTypeface(Typeface.DEFAULT_BOLD);
        }
    }

    /**
     * Creates a custom content root with the same title/body treatment used by
     * the launcher progress/settings dialogs. Use this instead of Builder.setTitle()
     * for custom RecyclerView dialogs; Android's default title/content panels are
     * what caused the gray, mismatched dialog shown in the modpack version picker.
     */
    @NonNull
    public static LinearLayout createDialogRoot(
            @NonNull Activity activity,
            @NonNull CharSequence titleText,
            @Nullable CharSequence messageText
    ) {
        int outerPadding = dp(activity, 18);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(outerPadding, outerPadding, outerPadding, dp(activity, 8));
        root.setBackgroundColor(COLOR_DIALOG_BG);

        TextView title = new TextView(activity);
        title.setText(titleText);
        title.setTextSize(23);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(COLOR_TEXT_PRIMARY);
        title.setPadding(dp(activity, 2), 0, dp(activity, 2), dp(activity, 6));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        if (messageText != null && messageText.length() > 0) {
            TextView message = new TextView(activity);
            message.setText(messageText);
            message.setTextSize(14);
            message.setTextColor(COLOR_TEXT_SECONDARY);
            message.setLineSpacing(dp(activity, 1), 1.0f);
            message.setPadding(dp(activity, 2), 0, dp(activity, 2), dp(activity, 10));
            root.addView(message, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }

        return root;
    }

    @NonNull
    public static AlertDialog showStyledMessageDialog(
            @NonNull Activity activity,
            @NonNull CharSequence titleText,
            @NonNull CharSequence messageText,
            @NonNull CharSequence positiveText,
            @NonNull DialogInterface.OnClickListener positiveListener,
            @NonNull CharSequence negativeText
    ) {
        int messagePadding = dp(activity, 14);

        LinearLayout root = createDialogRoot(activity, titleText, null);

        ScrollView messageScroll = new ScrollView(activity);
        messageScroll.setFillViewport(false);
        messageScroll.setClipToPadding(false);

        TextView message = new TextView(activity);
        message.setText(messageText);
        message.setTextSize(14);
        message.setTextColor(COLOR_TEXT_SECONDARY);
        message.setLineSpacing(dp(activity, 2), 1.0f);
        message.setPadding(messagePadding, messagePadding, messagePadding, messagePadding);
        message.setBackground(roundedDrawable(activity, COLOR_CARD_BG, COLOR_CARD_STROKE, 18));
        messageScroll.addView(message, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        scrollParams.topMargin = dp(activity, 2);
        root.addView(messageScroll, scrollParams);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(root)
                .setNegativeButton(negativeText, null)
                .setPositiveButton(positiveText, positiveListener)
                .create();
        dialog.setOnShowListener(unused -> styleDialogChrome(activity, dialog));
        dialog.show();
        styleDialogChrome(activity, dialog);
        return dialog;
    }

    @NonNull
    public static GradientDrawable roundedDrawable(
            @NonNull Context context,
            int fillColor,
            int strokeColor,
            int cornerDp
    ) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fillColor);
        bg.setCornerRadius(dp(context, cornerDp));
        bg.setStroke(dp(context, 1), strokeColor);
        return bg;
    }

    public static int dp(@NonNull Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}

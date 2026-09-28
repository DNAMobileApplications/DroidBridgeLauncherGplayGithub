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
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Window;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Shared launcher dialog chrome.
 *
 * The palette is synchronized from the currently selected Material theme by
 * LauncherTheme.apply(). Keeping the public color fields preserves compatibility
 * with older programmatic screens while preventing those screens from being
 * permanently stuck on the old dark/green dialog palette.
 */
public final class LauncherDialogStyle {
    public static int COLOR_DIALOG_BG = Color.rgb(30, 34, 42);
    public static int COLOR_CARD_BG = Color.rgb(38, 43, 53);
    public static int COLOR_CARD_BG_PRESSED = Color.rgb(43, 49, 60);
    public static int COLOR_CARD_STROKE = Color.rgb(54, 61, 74);
    public static int COLOR_TEXT_PRIMARY = Color.rgb(238, 241, 248);
    public static int COLOR_TEXT_SECONDARY = Color.rgb(198, 204, 216);
    public static int COLOR_TEXT_MUTED = Color.rgb(150, 159, 176);
    public static int COLOR_ACCENT = Color.rgb(37, 211, 128);
    public static int COLOR_ACCENT_MUTED = Color.rgb(86, 135, 110);
    public static int COLOR_ERROR = Color.rgb(186, 26, 26);
    public static int COLOR_ON_ERROR = Color.WHITE;

    public static final float DIALOG_DIM_NORMAL = 0.58f;

    private LauncherDialogStyle() {
    }

    /** Refreshes all compatibility color fields from the active Material theme. */
    public static void syncTheme(@NonNull Context context) {
        COLOR_DIALOG_BG = resolveColor(
                context,
                com.google.android.material.R.attr.colorSurface,
                resolveColor(context, android.R.attr.colorBackground, COLOR_DIALOG_BG)
        );
        COLOR_CARD_BG = resolveColor(
                context,
                com.google.android.material.R.attr.colorSurfaceVariant,
                COLOR_CARD_BG
        );
        COLOR_CARD_BG_PRESSED = resolveColor(
                context,
                com.google.android.material.R.attr.colorPrimaryContainer,
                COLOR_CARD_BG
        );
        COLOR_CARD_STROKE = resolveColor(
                context,
                com.google.android.material.R.attr.colorOutlineVariant,
                resolveColor(context, com.google.android.material.R.attr.colorOutline, COLOR_CARD_STROKE)
        );
        COLOR_TEXT_PRIMARY = resolveColor(
                context,
                com.google.android.material.R.attr.colorOnSurface,
                COLOR_TEXT_PRIMARY
        );
        COLOR_TEXT_SECONDARY = resolveColor(
                context,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                COLOR_TEXT_SECONDARY
        );
        COLOR_TEXT_MUTED = resolveColor(
                context,
                com.google.android.material.R.attr.colorOutline,
                COLOR_TEXT_MUTED
        );
        COLOR_ACCENT = resolveColor(
                context,
                com.google.android.material.R.attr.colorPrimary,
                COLOR_ACCENT
        );
        COLOR_ACCENT_MUTED = resolveColor(
                context,
                com.google.android.material.R.attr.colorSecondary,
                COLOR_ACCENT_MUTED
        );
        COLOR_ERROR = resolveColor(
                context,
                com.google.android.material.R.attr.colorError,
                COLOR_ERROR
        );
        COLOR_ON_ERROR = resolveColor(
                context,
                com.google.android.material.R.attr.colorOnError,
                COLOR_ON_ERROR
        );
    }

    public static void styleDialogChrome(@NonNull Activity activity, @Nullable AlertDialog dialog) {
        if (dialog == null) return;
        syncTheme(activity);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(roundedDrawable(activity, COLOR_DIALOG_BG, COLOR_CARD_STROKE, 22));
            window.setDimAmount(DIALOG_DIM_NORMAL);

            // Material's stock max dialog width can become unnecessarily narrow on
            // tablets/landscape handhelds. Use a responsive cap while keeping safe
            // side margins on phones so long profile names/options are not squished.
            DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            int sideMargin = dp(activity, metrics.widthPixels > metrics.heightPixels ? 24 : 16);
            int available = Math.max(dp(activity, 280), metrics.widthPixels - (sideMargin * 2));
            int width = Math.min(available, dp(activity, 720));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
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
            button.setMaxLines(1);
            button.setEllipsize(TextUtils.TruncateAt.END);
        }
    }

    /**
     * Creates a custom content root with the same title/body treatment used by
     * launcher progress/settings/dialog-list screens.
     */
    @NonNull
    public static LinearLayout createDialogRoot(
            @NonNull Activity activity,
            @NonNull CharSequence titleText,
            @Nullable CharSequence messageText
    ) {
        syncTheme(activity);
        int outerPadding = dp(activity, 18);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(outerPadding, outerPadding, outerPadding, dp(activity, 8));
        root.setBackgroundColor(COLOR_DIALOG_BG);

        TextView title = new TextView(activity);
        title.setText(titleText);
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(COLOR_TEXT_PRIMARY);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
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
        syncTheme(activity);
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

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
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

    private static int resolveColor(@NonNull Context context, int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attr, value, true)) return fallback;
        if (value.resourceId != 0) {
            try {
                return ContextCompat.getColor(context, value.resourceId);
            } catch (Throwable ignored) {
            }
        }
        return value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT
                ? value.data
                : fallback;
    }

    public static int dp(@NonNull Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}

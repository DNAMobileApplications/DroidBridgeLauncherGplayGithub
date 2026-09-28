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

import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ca.dnamobile.droidbridgelauncher.ui.LauncherDialogStyle;

/**
 * Shared full-screen keyboard-style key picker used by touch-control editing,
 * the send-key overlay, and controller/gamepad mapping. Keep this helper as the
 * single visual source so the launcher does not drift back to spinner pickers.
 */
public final class TouchKeyPickerDialog {
    public static final int GAMEPAD_CURSOR_UP = Integer.MIN_VALUE + 1041;
    public static final int GAMEPAD_CURSOR_DOWN = Integer.MIN_VALUE + 1042;
    public static final int GAMEPAD_CURSOR_LEFT = Integer.MIN_VALUE + 1043;
    public static final int GAMEPAD_CURSOR_RIGHT = Integer.MIN_VALUE + 1044;

    public interface Callback {
        /** Return true to accept the key and close the picker. */
        boolean onKeyPicked(int keyCode);
    }

    public static final class KeySpec {
        @NonNull final String label;
        final int keyCode;
        final float widthDp;

        private KeySpec(@NonNull String label, int keyCode, float widthDp) {
            this.label = label;
            this.keyCode = keyCode;
            this.widthDp = widthDp;
        }
    }

    private TouchKeyPickerDialog() {
    }

    @NonNull
    public static KeySpec extraKey(@NonNull String label, int keyCode, float widthDp) {
        return new KeySpec(label, keyCode, widthDp);
    }

    @NonNull
    public static String labelForKeyCode(int keyCode) {
        return TouchInputBinding.labelForKeyCode(keyCode);
    }

    public static void showPicker(
            @NonNull Context context,
            int slotIndex,
            @NonNull Callback callback
    ) {
        showPicker(
                context,
                "Pick key for Position " + slotIndex,
                "Tap a key. Extra launcher/mouse actions are below.",
                null,
                callback
        );
    }

    public static void showPicker(
            @NonNull Context context,
            @NonNull String titleText,
            @NonNull String hintText,
            @Nullable List<KeySpec> extraKeys,
            @NonNull Callback callback
    ) {
        int rowHeightPx = keyboardPickerRowHeightPx(context);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(context, 10f), dp(context, 8f), dp(context, 10f), dp(context, 8f));
        content.setBackground(makeKeyboardPickerBackground(context));

        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, 0, 0, dp(context, 4f));

        TextView title = new TextView(context);
        title.setText(titleText);
        title.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        title.setTextSize(15f);
        title.setSingleLine(true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button cancel = new Button(context);
        cancel.setText("Cancel");
        cancel.setTextColor(LauncherDialogStyle.COLOR_ACCENT);
        cancel.setAllCaps(false);
        cancel.setMinHeight(0);
        cancel.setMinimumHeight(0);
        cancel.setPadding(dp(context, 8f), 0, dp(context, 8f), 0);
        titleRow.addView(cancel, new LinearLayout.LayoutParams(dp(context, 92f), dp(context, 34f)));
        content.addView(titleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView hint = valueLabel(context, hintText);
        hint.setTextSize(11f);
        hint.setSingleLine(false);
        hint.setPadding(0, 0, 0, dp(context, 2f));
        content.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        final AlertDialog[] dialogRef = new AlertDialog[1];
        KeySelection selection = keyCode -> {
            if (callback.onKeyPicked(keyCode) && dialogRef[0] != null) {
                dialogRef[0].dismiss();
            }
        };

        ScrollView keyboardScroll = new ScrollView(context);
        keyboardScroll.setFillViewport(false);
        keyboardScroll.setClipToPadding(false);
        keyboardScroll.setPadding(0, 0, 0, dp(context, 6f));

        LinearLayout keyboardPage = new LinearLayout(context);
        keyboardPage.setOrientation(LinearLayout.VERTICAL);
        keyboardScroll.addView(keyboardPage, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        content.addView(keyboardScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        addKeyboardPickerRow(context, keyboardPage, rowHeightPx, selection,
                new KeySpec("Esc", 256, 1.2f),
                new KeySpec("F1", 290, 1f), new KeySpec("F2", 291, 1f), new KeySpec("F3", 292, 1f), new KeySpec("F4", 293, 1f),
                new KeySpec("F5", 294, 1f), new KeySpec("F6", 295, 1f), new KeySpec("F7", 296, 1f), new KeySpec("F8", 297, 1f),
                new KeySpec("F9", 298, 1f), new KeySpec("F10", 299, 1.08f), new KeySpec("F11", 300, 1.08f), new KeySpec("F12", 301, 1.08f)
        );
        addKeyboardPickerRow(context, keyboardPage, rowHeightPx, selection,
                new KeySpec("`", 96, 1f),
                new KeySpec("1", 49, 1f), new KeySpec("2", 50, 1f), new KeySpec("3", 51, 1f), new KeySpec("4", 52, 1f), new KeySpec("5", 53, 1f),
                new KeySpec("6", 54, 1f), new KeySpec("7", 55, 1f), new KeySpec("8", 56, 1f), new KeySpec("9", 57, 1f), new KeySpec("0", 48, 1f),
                new KeySpec("-", 45, 1f), new KeySpec("=", 61, 1f), new KeySpec("Back", 259, 1.9f)
        );
        addKeyboardPickerRow(context, keyboardPage, rowHeightPx, selection,
                new KeySpec("Tab", 258, 1.45f),
                new KeySpec("Q", 81, 1f), new KeySpec("W", 87, 1f), new KeySpec("E", 69, 1f), new KeySpec("R", 82, 1f), new KeySpec("T", 84, 1f),
                new KeySpec("Y", 89, 1f), new KeySpec("U", 85, 1f), new KeySpec("I", 73, 1f), new KeySpec("O", 79, 1f), new KeySpec("P", 80, 1f),
                new KeySpec("[", 91, 1f), new KeySpec("]", 93, 1f), new KeySpec("\\", 92, 1.25f)
        );
        addKeyboardPickerRow(context, keyboardPage, rowHeightPx, selection,
                new KeySpec("A", 65, 1f), new KeySpec("S", 83, 1f), new KeySpec("D", 68, 1f), new KeySpec("F", 70, 1f), new KeySpec("G", 71, 1f),
                new KeySpec("H", 72, 1f), new KeySpec("J", 74, 1f), new KeySpec("K", 75, 1f), new KeySpec("L", 76, 1f),
                new KeySpec(";", 59, 1f), new KeySpec("'", 39, 1f), new KeySpec("Enter", 257, 1.85f)
        );
        addKeyboardPickerRow(context, keyboardPage, rowHeightPx, selection,
                new KeySpec("Shift", 340, 1.75f),
                new KeySpec("Z", 90, 1f), new KeySpec("X", 88, 1f), new KeySpec("C", 67, 1f), new KeySpec("V", 86, 1f), new KeySpec("B", 66, 1f),
                new KeySpec("N", 78, 1f), new KeySpec("M", 77, 1f), new KeySpec(",", 44, 1f), new KeySpec(".", 46, 1f), new KeySpec("/", 47, 1f),
                new KeySpec("RShift", 344, 1.75f)
        );
        addKeyboardPickerRow(context, keyboardPage, rowHeightPx, selection,
                new KeySpec("Ctrl", 341, 1.15f), new KeySpec("Alt", 342, 1.1f), new KeySpec("Space", 32, 5.4f),
                new KeySpec("RAlt", 346, 1.15f), new KeySpec("RCtrl", 345, 1.25f), new KeySpec("Menu", 348, 1.25f)
        );
        addKeyboardPickerRow(context, keyboardPage, rowHeightPx, selection,
                new KeySpec("Ins", 260, 1.15f), new KeySpec("Del", 261, 1.15f), new KeySpec("Home", 268, 1.2f), new KeySpec("End", 269, 1.1f),
                new KeySpec("PgUp", 266, 1.2f), new KeySpec("PgDn", 267, 1.2f),
                new KeySpec("←", 263, 0.9f), new KeySpec("↑", 265, 0.9f), new KeySpec("↓", 264, 0.9f), new KeySpec("→", 262, 0.9f)
        );

        addKeyboardPickerSection(context, keyboardPage, "Extra actions");
        addKeyboardPickerRow(context, keyboardPage, rowHeightPx, selection,
                new KeySpec("None", 0, 0.95f),
                new KeySpec("Left click", TouchControlData.SPECIAL_MOUSE_LEFT, 1.35f),
                new KeySpec("Right click", TouchControlData.SPECIAL_MOUSE_RIGHT, 1.42f),
                new KeySpec("Middle click", TouchControlData.SPECIAL_MOUSE_MIDDLE, 1.55f),
                new KeySpec("Wheel up", TouchControlData.SPECIAL_SCROLL_UP, 1.25f),
                new KeySpec("Wheel down", TouchControlData.SPECIAL_SCROLL_DOWN, 1.35f)
        );
        addKeyboardPickerRow(context, keyboardPage, rowHeightPx, selection,
                new KeySpec("Android keyboard", TouchControlData.SPECIAL_KEYBOARD, 1.55f),
                new KeySpec("Key sender", TouchControlData.SPECIAL_KEY_SENDER_KEYBOARD, 1.35f),
                new KeySpec("Launcher menu", TouchControlData.SPECIAL_MENU, 1.55f),
                new KeySpec("Hide controls", TouchControlData.SPECIAL_TOGGLE_CONTROLS, 1.45f),
                new KeySpec("Virtual cursor", TouchControlData.SPECIAL_VIRTUAL_MOUSE, 1.5f),
                new KeySpec("Swap screens", TouchControlData.SPECIAL_DUAL_SCREEN_SWAP, 1.45f)
        );

        if (extraKeys != null && !extraKeys.isEmpty()) {
            addKeyboardPickerSection(context, keyboardPage, "Gamepad cursor actions");
            addKeyboardPickerDynamicRows(context, keyboardPage, rowHeightPx, selection, extraKeys);
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setView(content)
                .create();
        dialogRef[0] = dialog;
        cancel.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnShowListener(shown -> styleKeyboardPickerWindow(dialog));
        dialog.show();
    }

    private interface KeySelection {
        void onKeyPicked(int keyCode);
    }

    private static int keyboardPickerRowHeightPx(@NonNull Context context) {
        // The page scrolls vertically as one piece, so rows should not shrink on short displays.
        return Math.round(46f * Math.max(0.1f, context.getResources().getDisplayMetrics().density));
    }

    private static void styleKeyboardPickerWindow(@NonNull AlertDialog dialog) {
        if (dialog.getWindow() == null) return;
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        try {
            View decor = dialog.getWindow().getDecorView();
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
            View content = decor.findViewById(android.R.id.content);
            if (content instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) content;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    ViewGroup.LayoutParams params = child.getLayoutParams();
                    if (params != null) {
                        params.width = ViewGroup.LayoutParams.MATCH_PARENT;
                        params.height = ViewGroup.LayoutParams.MATCH_PARENT;
                        child.setLayoutParams(params);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @NonNull
    private static GradientDrawable makeKeyboardPickerBackground(@NonNull Context context) {
        LauncherDialogStyle.syncTheme(context);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(LauncherDialogStyle.COLOR_DIALOG_BG);
        drawable.setCornerRadius(0f);
        return drawable;
    }

    private static void addKeyboardPickerSection(@NonNull Context context, @NonNull LinearLayout parent, @NonNull String title) {
        TextView section = new TextView(context);
        section.setText(title);
        section.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        section.setTextSize(12f);
        section.setSingleLine(true);
        section.setPadding(0, dp(context, 4f), 0, dp(context, 1f));
        parent.addView(section, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    private static void addKeyboardPickerDynamicRows(
            @NonNull Context context,
            @NonNull LinearLayout parent,
            int rowHeightPx,
            @NonNull KeySelection selection,
            @NonNull List<KeySpec> keys
    ) {
        int index = 0;
        while (index < keys.size()) {
            int end = Math.min(index + 4, keys.size());
            KeySpec[] row = new KeySpec[end - index];
            for (int i = index; i < end; i++) row[i - index] = keys.get(i);
            addKeyboardPickerRow(context, parent, rowHeightPx, selection, row);
            index = end;
        }
    }

    private static void addKeyboardPickerRow(
            @NonNull Context context,
            @NonNull LinearLayout parent,
            int rowHeightPx,
            @NonNull KeySelection selection,
            @NonNull KeySpec... keys
    ) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, 0);
        for (KeySpec key : keys) {
            addKeyboardPickerKey(context, row, key, rowHeightPx, selection);
        }
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                rowHeightPx
        ));
    }

    private static void addKeyboardPickerKey(
            @NonNull Context context,
            @NonNull LinearLayout row,
            @NonNull KeySpec key,
            int rowHeightPx,
            @NonNull KeySelection selection
    ) {
        Button button = new Button(context);
        button.setText(key.label);
        button.setTextSize(rowHeightPx <= dp(context, 32f) ? 9.5f : rowHeightPx >= dp(context, 46f) ? 12f : 10.5f);
        button.setAllCaps(false);
        button.setSingleLine(true);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(context, 2f), 0, dp(context, 2f), 0);
        button.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        button.setBackground(makeKeyboardKeyBackground(context));
        button.setOnClickListener(v -> selection.onKeyPicked(key.keyCode));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Math.max(0.1f, key.widthDp));
        params.setMargins(dp(context, 1.5f), dp(context, 1.5f), dp(context, 1.5f), dp(context, 1.5f));
        row.addView(button, params);
    }

    @NonNull
    private static GradientDrawable makeKeyboardKeyBackground(@NonNull Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(LauncherDialogStyle.COLOR_CARD_BG_PRESSED);
        drawable.setCornerRadius(dp(context, 7f));
        drawable.setStroke(Math.max(1, dp(context, 1f)), LauncherDialogStyle.COLOR_CARD_STROKE);
        return drawable;
    }

    @NonNull
    private static TextView valueLabel(@NonNull Context context, @NonNull String text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        label.setTextSize(13f);
        label.setPadding(0, dp(context, 4f), 0, dp(context, 2f));
        return label;
    }

    private static int dp(@NonNull Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}

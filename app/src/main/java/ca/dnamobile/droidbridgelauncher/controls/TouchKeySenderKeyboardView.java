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

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen in-game key sender keyboard.
 *
 * This view deliberately sends GLFW keyboard keys by their real keyboard key codes.
 * For example, the number row queues GLFW_KEY_1 through GLFW_KEY_0, not DroidBridge
 * hotbar touch slots. The owner decides how to deliver the queued keys so chords
 * such as F3 + 1 can be sent with F3 held while 1 is pressed.
 */
public final class TouchKeySenderKeyboardView extends FrameLayout {
    public interface Listener {
        void onCloseRequested();
        void onSendRequested(@NonNull List<Integer> keyCodes);
    }

    private static final int ACTION_CLOSE = Integer.MIN_VALUE + 903;

    @NonNull private final Listener listener;
    @NonNull private final ArrayList<Integer> pendingKeys = new ArrayList<>();
    @NonNull private final ArrayList<Button> highlightedButtons = new ArrayList<>();
    @NonNull private TextView queuedLabel;

    public TouchKeySenderKeyboardView(@NonNull Context context, @NonNull Listener listener) {
        super(context);
        this.listener = listener;
        setClickable(true);
        setFocusable(true);
        setBackgroundColor(0xCC000000);
        buildLayout();
    }

    private void buildLayout() {
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10f), dp(8f), dp(10f), dp(8f));
        content.setBackground(makeKeyboardBackground());
        addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout titleRow = new LinearLayout(getContext());
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, 0, 0, dp(4f));

        TextView title = new TextView(getContext());
        title.setText("DroidBridge key keyboard");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15f);
        title.setSingleLine(true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button send = compactButton("Send Key");
        titleRow.addView(send, new LinearLayout.LayoutParams(dp(112f), dp(34f)));

        Button clear = compactButton("Clear");
        titleRow.addView(clear, new LinearLayout.LayoutParams(dp(86f), dp(34f)));

        Button close = compactButton("Close");
        close.setOnClickListener(v -> listener.onCloseRequested());
        titleRow.addView(close, new LinearLayout.LayoutParams(dp(86f), dp(34f)));
        content.addView(titleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView hint = label("Tap keys to queue them. Press Send Key to send the highlighted keys to Minecraft as one keyboard chord.");
        hint.setTextSize(11f);
        hint.setSingleLine(false);
        hint.setPadding(0, 0, 0, dp(2f));
        content.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        queuedLabel = label("Queued: none");
        queuedLabel.setTextSize(12f);
        queuedLabel.setSingleLine(false);
        queuedLabel.setPadding(0, 0, 0, dp(4f));
        content.addView(queuedLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        clear.setOnClickListener(v -> clearQueuedKeys());
        send.setOnClickListener(v -> sendQueuedKeys());

        ScrollView keyboardScroll = new ScrollView(getContext());
        keyboardScroll.setFillViewport(false);
        keyboardScroll.setClipToPadding(false);
        keyboardScroll.setPadding(0, 0, 0, dp(6f));

        LinearLayout keyboardPage = new LinearLayout(getContext());
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

        int rowHeightPx = keyboardRowHeightPx();
        addKeyboardRows(keyboardPage, rowHeightPx);
    }

    private void addKeyboardRows(@NonNull LinearLayout keyboardPage, int rowHeightPx) {
        addKeyboardRow(keyboardPage, rowHeightPx,
                key("Esc", 256, 1.2f),
                key("F1", 290, 1f), key("F2", 291, 1f), key("F3", 292, 1f), key("F4", 293, 1f),
                key("F5", 294, 1f), key("F6", 295, 1f), key("F7", 296, 1f), key("F8", 297, 1f),
                key("F9", 298, 1f), key("F10", 299, 1.08f), key("F11", 300, 1.08f), key("F12", 301, 1.08f)
        );
        addKeyboardRow(keyboardPage, rowHeightPx,
                key("`", 96, 1f),
                key("1", 49, 1f), key("2", 50, 1f), key("3", 51, 1f), key("4", 52, 1f), key("5", 53, 1f),
                key("6", 54, 1f), key("7", 55, 1f), key("8", 56, 1f), key("9", 57, 1f), key("0", 48, 1f),
                key("-", 45, 1f), key("=", 61, 1f), key("Back", 259, 1.9f)
        );
        addKeyboardRow(keyboardPage, rowHeightPx,
                key("Tab", 258, 1.45f),
                key("Q", 81, 1f), key("W", 87, 1f), key("E", 69, 1f), key("R", 82, 1f), key("T", 84, 1f),
                key("Y", 89, 1f), key("U", 85, 1f), key("I", 73, 1f), key("O", 79, 1f), key("P", 80, 1f),
                key("[", 91, 1f), key("]", 93, 1f), key("\\", 92, 1.25f)
        );
        addKeyboardRow(keyboardPage, rowHeightPx,
                key("A", 65, 1f), key("S", 83, 1f), key("D", 68, 1f), key("F", 70, 1f), key("G", 71, 1f),
                key("H", 72, 1f), key("J", 74, 1f), key("K", 75, 1f), key("L", 76, 1f),
                key(";", 59, 1f), key("'", 39, 1f), key("Enter", 257, 1.85f)
        );
        addKeyboardRow(keyboardPage, rowHeightPx,
                key("Shift", 340, 1.75f),
                key("Z", 90, 1f), key("X", 88, 1f), key("C", 67, 1f), key("V", 86, 1f), key("B", 66, 1f),
                key("N", 78, 1f), key("M", 77, 1f), key(",", 44, 1f), key(".", 46, 1f), key("/", 47, 1f),
                key("RShift", 344, 1.75f)
        );
        addKeyboardRow(keyboardPage, rowHeightPx,
                key("Ctrl", 341, 1.15f), key("Alt", 342, 1.1f), key("Space", 32, 5.4f),
                key("RAlt", 346, 1.15f), key("RCtrl", 345, 1.25f), key("Menu", 348, 1.25f)
        );
        addKeyboardRow(keyboardPage, rowHeightPx,
                key("Ins", 260, 1.15f), key("Del", 261, 1.15f), key("Home", 268, 1.2f), key("End", 269, 1.1f),
                key("PgUp", 266, 1.2f), key("PgDn", 267, 1.2f),
                key("←", 263, 0.9f), key("↑", 265, 0.9f), key("↓", 264, 0.9f), key("→", 262, 0.9f)
        );

        addSection(keyboardPage, "Actions");
        addKeyboardRow(keyboardPage, rowHeightPx,
                key("Close", ACTION_CLOSE, 1.0f),
                key("Left click", TouchControlData.SPECIAL_MOUSE_LEFT, 1.35f),
                key("Right click", TouchControlData.SPECIAL_MOUSE_RIGHT, 1.42f),
                key("Middle click", TouchControlData.SPECIAL_MOUSE_MIDDLE, 1.55f),
                key("Wheel up", TouchControlData.SPECIAL_SCROLL_UP, 1.25f),
                key("Wheel down", TouchControlData.SPECIAL_SCROLL_DOWN, 1.35f),
                key("Android keyboard", TouchControlData.SPECIAL_KEYBOARD, 1.65f),
                key("Launcher menu", TouchControlData.SPECIAL_MENU, 1.55f),
                key("Swap screens", TouchControlData.SPECIAL_DUAL_SCREEN_SWAP, 1.45f)
        );
    }

    private void addSection(@NonNull LinearLayout parent, @NonNull String title) {
        TextView section = new TextView(getContext());
        section.setText(title);
        section.setTextColor(Color.WHITE);
        section.setTextSize(12f);
        section.setSingleLine(true);
        section.setPadding(0, dp(4f), 0, dp(1f));
        parent.addView(section, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    private void addKeyboardRow(@NonNull LinearLayout parent, int rowHeightPx, @NonNull KeySpec... keys) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, 0);
        for (KeySpec key : keys) {
            addKeyboardKey(row, key, rowHeightPx);
        }
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                rowHeightPx
        ));
    }

    private void addKeyboardKey(@NonNull LinearLayout row, @NonNull KeySpec key, int rowHeightPx) {
        Button button = new Button(getContext());
        button.setText(key.label);
        button.setTextSize(rowHeightPx <= dp(32f) ? 9.5f : rowHeightPx >= dp(46f) ? 12f : 10.5f);
        button.setAllCaps(false);
        button.setSingleLine(true);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(2f), 0, dp(2f), 0);
        button.setBackground(makeKeyBackground(false));
        button.setOnClickListener(v -> queueKey(key, button));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(0.1f, key.widthDp)
        );
        params.setMargins(dp(1.5f), dp(1.5f), dp(1.5f), dp(1.5f));
        row.addView(button, params);
    }

    private void queueKey(@NonNull KeySpec key, @NonNull Button button) {
        if (key.keyCode == ACTION_CLOSE) {
            listener.onCloseRequested();
            return;
        }
        if (key.keyCode == TouchControlData.SPECIAL_KEY_SENDER_KEYBOARD) {
            return;
        }

        pendingKeys.add(key.keyCode);
        if (!highlightedButtons.contains(button)) {
            highlightedButtons.add(button);
            button.setBackground(makeKeyBackground(true));
        }
        updateQueuedLabel();
    }

    private void clearQueuedKeys() {
        pendingKeys.clear();
        for (Button button : highlightedButtons) {
            if (button != null) {
                button.setBackground(makeKeyBackground(false));
            }
        }
        highlightedButtons.clear();
        updateQueuedLabel();
    }

    private void sendQueuedKeys() {
        if (pendingKeys.isEmpty()) {
            Toast.makeText(getContext(), "Pick at least one key first.", Toast.LENGTH_SHORT).show();
            return;
        }
        listener.onSendRequested(new ArrayList<>(pendingKeys));
    }

    private void updateQueuedLabel() {
        if (pendingKeys.isEmpty()) {
            queuedLabel.setText("Queued: none");
            return;
        }

        StringBuilder builder = new StringBuilder("Queued: ");
        for (int i = 0; i < pendingKeys.size(); i++) {
            if (i > 0) builder.append("  •  ");
            builder.append(TouchInputBinding.labelForKeyCode(pendingKeys.get(i)));
        }
        queuedLabel.setText(builder.toString());
    }

    @NonNull
    private Button compactButton(@NonNull String text) {
        Button button = new Button(getContext());
        button.setText(text);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(8f), 0, dp(8f), 0);
        return button;
    }

    @NonNull
    private TextView label(@NonNull String text) {
        TextView label = new TextView(getContext());
        label.setText(text);
        label.setTextColor(0xFFE0E0E0);
        label.setTextSize(13f);
        label.setPadding(0, dp(4f), 0, dp(2f));
        return label;
    }

    private int keyboardRowHeightPx() {
        float density = Math.max(0.1f, getResources().getDisplayMetrics().density);
        return Math.round(46f * density);
    }

    @NonNull
    private GradientDrawable makeKeyboardBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0xF4202124);
        drawable.setCornerRadius(0f);
        return drawable;
    }

    @NonNull
    private GradientDrawable makeKeyBackground(boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(selected ? 0xEE5E7CE2 : 0xEE303236);
        drawable.setCornerRadius(dp(7f));
        drawable.setStroke(Math.max(1, dp(selected ? 2f : 1f)), selected ? 0xFFFFFFFF : 0x55FFFFFF);
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @NonNull
    private static KeySpec key(@NonNull String label, int keyCode, float widthDp) {
        return new KeySpec(label, keyCode, widthDp);
    }

    private static final class KeySpec {
        @NonNull final String label;
        final int keyCode;
        final float widthDp;

        KeySpec(@NonNull String label, int keyCode, float widthDp) {
            this.label = label;
            this.keyCode = keyCode;
            this.widthDp = widthDp;
        }
    }
}

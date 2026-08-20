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

import android.os.Build;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Stable button names used by DroidBridge's controller mapper.
 */
public enum GamepadButton {
    // Generic/customizable Android controller buttons. AYN and other handhelds
    // commonly expose rear paddles through BUTTON_1/BUTTON_2. Newer Android
    // builds may instead use MACRO_1/MACRO_2 (numeric keycodes 313/314).
    M1(KeyEvent.KEYCODE_BUTTON_1, "M1 / Rear Left"),
    M2(KeyEvent.KEYCODE_BUTTON_2, "M2 / Rear Right"),

    BUTTON_A(KeyEvent.KEYCODE_BUTTON_A, "A"),
    BUTTON_B(KeyEvent.KEYCODE_BUTTON_B, "B"),
    BUTTON_X(KeyEvent.KEYCODE_BUTTON_X, "X"),
    BUTTON_Y(KeyEvent.KEYCODE_BUTTON_Y, "Y"),

    BUTTON_L1(KeyEvent.KEYCODE_BUTTON_L1, "L1"),
    BUTTON_R1(KeyEvent.KEYCODE_BUTTON_R1, "R1"),
    BUTTON_L2(KeyEvent.KEYCODE_BUTTON_L2, "L2"),
    BUTTON_R2(KeyEvent.KEYCODE_BUTTON_R2, "R2"),

    BUTTON_THUMBL(KeyEvent.KEYCODE_BUTTON_THUMBL, "Left Stick Press"),
    BUTTON_THUMBR(KeyEvent.KEYCODE_BUTTON_THUMBR, "Right Stick Press"),

    BUTTON_START(KeyEvent.KEYCODE_BUTTON_START, "Start"),
    BUTTON_SELECT(KeyEvent.KEYCODE_BUTTON_SELECT, "Select"),

    DPAD_UP(KeyEvent.KEYCODE_DPAD_UP, "D-Pad Up"),
    DPAD_DOWN(KeyEvent.KEYCODE_DPAD_DOWN, "D-Pad Down"),
    DPAD_LEFT(KeyEvent.KEYCODE_DPAD_LEFT, "D-Pad Left"),
    DPAD_RIGHT(KeyEvent.KEYCODE_DPAD_RIGHT, "D-Pad Right"),
    DPAD_CENTER(KeyEvent.KEYCODE_DPAD_CENTER, "D-Pad Center");

    // KEYCODE_MACRO_1/2 were added after older compile SDKs. Keep numeric
    // compatibility so the project still compiles against its current SDK.
    private static final int KEYCODE_MACRO_1_COMPAT = 313;
    private static final int KEYCODE_MACRO_2_COMPAT = 314;

    // Confirmed from the Odin 3 raw input logger. The rear buttons retain these
    // Linux scan codes even when AYN's launcher remaps their Android keycodes.
    private static final int AYN_ODIN_M1_SCAN_CODE = 309;
    private static final int AYN_ODIN_M2_SCAN_CODE = 306;

    public final int androidKeyCode;
    private final String displayName;

    GamepadButton(int androidKeyCode, String displayName) {
        this.androidKeyCode = androidKeyCode;
        this.displayName = displayName;
    }

    @Nullable
    public static GamepadButton fromAndroidKeyCode(int keyCode) {
        if (keyCode == KEYCODE_MACRO_1_COMPAT) return M1;
        if (keyCode == KEYCODE_MACRO_2_COMPAT) return M2;

        for (GamepadButton button : values()) {
            if (button.androidKeyCode == keyCode) {
                return button;
            }
        }
        return null;
    }


    /**
     * Resolves buttons that require the complete Android event.
     *
     * Odin 3 exposes the rear buttons through its built-in Xbox-compatible input
     * node. The confirmed default events are KEYCODE_BUTTON_Z / scan 309 for M1
     * and KEYCODE_BUTTON_C / scan 306 for M2. Scan codes are checked first so the
     * rear buttons remain identifiable after AYN's launcher remaps their keycodes.
     */
    @Nullable
    public static GamepadButton fromAndroidKeyEvent(@Nullable KeyEvent event) {
        if (event == null) return null;

        if (isAynOdinBuild()) {
            int scanCode = event.getScanCode();
            if (scanCode == AYN_ODIN_M1_SCAN_CODE) return M1;
            if (scanCode == AYN_ODIN_M2_SCAN_CODE) return M2;

            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_BUTTON_Z) return M1;
            if (keyCode == KeyEvent.KEYCODE_BUTTON_C) return M2;
        }

        return fromAndroidKeyCode(event.getKeyCode());
    }

    /**
     * Returns true for the confirmed AYN/Odin rear-button events.
     *
     * External keyboard Z/C keys are unaffected because the rear controls use
     * gamepad BUTTON_Z/BUTTON_C keycodes and unique physical scan codes.
     */
    public static boolean isAynOdinRearButtonEvent(@Nullable KeyEvent event) {
        if (event == null || !isAynOdinBuild()) return false;

        int scanCode = event.getScanCode();
        if (scanCode == AYN_ODIN_M1_SCAN_CODE
                || scanCode == AYN_ODIN_M2_SCAN_CODE) {
            return true;
        }

        int keyCode = event.getKeyCode();
        return keyCode == KeyEvent.KEYCODE_BUTTON_Z
                || keyCode == KeyEvent.KEYCODE_BUTTON_C;
    }

    public static boolean isAynOdinBuild() {
        String identity = safeLower(Build.MANUFACTURER) + " "
                + safeLower(Build.BRAND) + " "
                + safeLower(Build.MODEL) + " "
                + safeLower(Build.DEVICE) + " "
                + safeLower(Build.PRODUCT);
        return identity.contains("ayn") || identity.contains("odin");
    }

    @NonNull
    private static String safeLower(@Nullable String value) {
        return value == null ? "" : value.toLowerCase(Locale.US);
    }

    @Override
    public String toString() {
        return displayName;
    }
}

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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

final class TouchInputBinding {
    static final class Option {
        @NonNull final String label;
        final int value;

        Option(@NonNull String label, int value) {
            this.label = label;
            this.value = value;
        }
    }

    private static final Option[] KEY_OPTIONS = new Option[]{
            new Option("None", 0),

            new Option("Mouse left click / Attack", TouchControlData.SPECIAL_MOUSE_LEFT),
            new Option("Mouse right click / Use", TouchControlData.SPECIAL_MOUSE_RIGHT),
            new Option("Mouse middle click / Pick block", TouchControlData.SPECIAL_MOUSE_MIDDLE),
            new Option("Mouse wheel up", TouchControlData.SPECIAL_SCROLL_UP),
            new Option("Mouse wheel down", TouchControlData.SPECIAL_SCROLL_DOWN),
            new Option("Open Android keyboard", TouchControlData.SPECIAL_KEYBOARD),
            new Option("Open key sender keyboard", TouchControlData.SPECIAL_KEY_SENDER_KEYBOARD),
            new Option("Open launcher menu", TouchControlData.SPECIAL_MENU),
            new Option("Show / hide touch controls", TouchControlData.SPECIAL_TOGGLE_CONTROLS),
            new Option("Toggle virtual cursor", TouchControlData.SPECIAL_VIRTUAL_MOUSE),
            new Option("Swap dual screens", TouchControlData.SPECIAL_DUAL_SCREEN_SWAP),

            new Option("W / Forward", 87),
            new Option("A / Left", 65),
            new Option("S / Back", 83),
            new Option("D / Right", 68),
            new Option("Space / Jump", 32),
            new Option("Left Shift / Sneak", 340),
            new Option("Left Ctrl / Sprint", 341),
            new Option("Left Alt", 342),
            new Option("Right Shift", 344),
            new Option("Right Ctrl", 345),
            new Option("Right Alt", 346),

            new Option("Escape / Pause", 256),
            new Option("Tab / Player List", 258),
            new Option("Enter", 257),
            new Option("Backspace", 259),
            new Option("Insert", 260),
            new Option("Delete", 261),
            new Option("Home", 268),
            new Option("End", 269),
            new Option("Page Up", 266),
            new Option("Page Down", 267),
            new Option("Menu Key", 348),

            new Option("Arrow Up", 265),
            new Option("Arrow Down", 264),
            new Option("Arrow Left", 263),
            new Option("Arrow Right", 262),

            new Option("E / Inventory", 69),
            new Option("Q / Drop", 81),
            new Option("F / Swap Offhand", 70),
            new Option("T / Chat", 84),
            new Option("Slash / Command", 47),

            new Option("B", 66),
            new Option("C", 67),
            new Option("G", 71),
            new Option("H", 72),
            new Option("I", 73),
            new Option("J", 74),
            new Option("K", 75),
            new Option("L", 76),
            new Option("M", 77),
            new Option("N", 78),
            new Option("O", 79),
            new Option("P", 80),
            new Option("R", 82),
            new Option("U", 85),
            new Option("V", 86),
            new Option("X", 88),
            new Option("Y", 89),
            new Option("Z", 90),

            new Option("1 / Hotbar 1", 49),
            new Option("2 / Hotbar 2", 50),
            new Option("3 / Hotbar 3", 51),
            new Option("4 / Hotbar 4", 52),
            new Option("5 / Hotbar 5", 53),
            new Option("6 / Hotbar 6", 54),
            new Option("7 / Hotbar 7", 55),
            new Option("8 / Hotbar 8", 56),
            new Option("9 / Hotbar 9", 57),
            new Option("0", 48),

            new Option("F1", 290),
            new Option("F2 / Screenshot", 291),
            new Option("F3 / Debug", 292),
            new Option("F4", 293),
            new Option("F5 / Perspective", 294),
            new Option("F6", 295),
            new Option("F7", 296),
            new Option("F8", 297),
            new Option("F9", 298),
            new Option("F10", 299),
            new Option("F11", 300),
            new Option("F12", 301),

            new Option("Minus / -", 45),
            new Option("Equals / =", 61),
            new Option("Left bracket / [", 91),
            new Option("Right bracket / ]", 93),
            new Option("Backslash / \\", 92),
            new Option("Semicolon / ;", 59),
            new Option("Apostrophe / '", 39),
            new Option("Grave / `", 96),
            new Option("Comma / ,", 44),
            new Option("Period / .", 46)
    };

    private static final Option[] MOUSE_OPTIONS = new Option[]{
            new Option("Left click / Attack", 0),
            new Option("Right click / Use", 1),
            new Option("Middle click / Pick block", 2)
    };

    private static final Option[] SCROLL_OPTIONS = new Option[]{
            new Option("Scroll up", 1),
            new Option("Scroll down", -1)
    };

    private static final Option[] EMPTY_OPTIONS = new Option[]{
            new Option("No extra binding needed", 0)
    };

    private TouchInputBinding() {
    }

    @NonNull
    static String[] actionLabels() {
        return new String[]{
                "Keyboard / mouse slots",
                "Single mouse button",
                "Single scroll wheel",
                "Open launcher menu",
                "Show / hide touch controls",
                "Open Android keyboard",
                "Open key sender keyboard",
                "Joystick / WASD",
                "Toggle virtual cursor",
                "Drawer / expand controls"
        };
    }

    @NonNull
    static String[] actionValues() {
        return new String[]{
                TouchControlActions.KEY,
                TouchControlActions.MOUSE,
                TouchControlActions.SCROLL,
                TouchControlActions.MENU,
                TouchControlActions.TOGGLE_CONTROLS,
                TouchControlActions.KEYBOARD,
                TouchControlActions.KEY_SENDER_KEYBOARD,
                TouchControlActions.JOYSTICK,
                TouchControlActions.VIRTUAL_MOUSE,
                TouchControlActions.DRAWER
        };
    }

    static int actionIndex(@Nullable String action) {
        String[] values = actionValues();
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(action)) return i;
        }
        return 0;
    }

    @NonNull
    static Option[] optionsForAction(@Nullable String action) {
        if (TouchControlActions.MOUSE.equals(action)) return MOUSE_OPTIONS;
        if (TouchControlActions.SCROLL.equals(action)) return SCROLL_OPTIONS;
        if (TouchControlActions.KEY.equals(action)) return KEY_OPTIONS;
        if (TouchControlActions.JOYSTICK.equals(action)) return EMPTY_OPTIONS;
        if (TouchControlActions.KEY_SENDER_KEYBOARD.equals(action)) return EMPTY_OPTIONS;
        if (TouchControlActions.VIRTUAL_MOUSE.equals(action)) return EMPTY_OPTIONS;
        if (TouchControlActions.DRAWER.equals(action)) return EMPTY_OPTIONS;
        return EMPTY_OPTIONS;
    }

    static int selectedOptionIndex(@Nullable String action, @NonNull TouchControlData data) {
        int value = data.keyCode;
        if (TouchControlActions.MOUSE.equals(action)) value = data.mouseButton;
        if (TouchControlActions.SCROLL.equals(action)) value = data.scrollY;

        Option[] options = optionsForAction(action);
        return selectedOptionIndexForValue(options, value);
    }

    static int selectedKeyOptionIndex(int keyCode) {
        return selectedOptionIndexForValue(KEY_OPTIONS, keyCode);
    }

    private static int selectedOptionIndexForValue(@NonNull Option[] options, int value) {
        for (int i = 0; i < options.length; i++) {
            if (options[i].value == value) return i;
        }
        return 0;
    }

    static void applyOption(@NonNull TouchControlData data, @NonNull String action, @NonNull Option option) {
        data.action = action;
        if (TouchControlActions.KEY.equals(action)) {
            data.keyCode = option.value > 0 ? option.value : 0;
            data.setKeyCodes(option.value == 0 ? new int[0] : new int[]{option.value});
        } else if (TouchControlActions.MOUSE.equals(action)) {
            data.mouseButton = option.value;
        } else if (TouchControlActions.SCROLL.equals(action)) {
            data.scrollY = option.value;
        }
    }

    @NonNull
    static String friendlyKeyCombo(@NonNull int[] codes) {
        if (codes.length == 0) return "No bindings";

        StringBuilder builder = new StringBuilder();
        for (int code : codes) {
            if (code == 0) continue;
            if (builder.length() > 0) builder.append(" + ");
            builder.append(labelForKeyCode(code));
        }
        return builder.length() == 0 ? "No bindings" : builder.toString();
    }

    @NonNull
    static String labelForKeyCode(int keyCode) {
        for (Option option : KEY_OPTIONS) {
            if (option.value == keyCode) return option.label;
        }

        // Common GLFW keys not listed in the small editor spinner.
        if (keyCode >= 65 && keyCode <= 90) return String.valueOf((char) keyCode);
        if (keyCode >= 48 && keyCode <= 57) return String.valueOf((char) keyCode);

        switch (keyCode) {
            case 32: return "Space / Jump";
            case 39: return "Apostrophe / '";
            case 44: return "Comma / ,";
            case 45: return "Minus / -";
            case 46: return "Period / .";
            case 47: return "Slash / Command";
            case 59: return "Semicolon / ;";
            case 61: return "Equals / =";
            case 91: return "Left bracket / [";
            case 92: return "Backslash / \\";
            case 93: return "Right bracket / ]";
            case 96: return "Grave / `";
            case 256: return "Escape / Pause";
            case 257: return "Enter";
            case 258: return "Tab / Player List";
            case 259: return "Backspace";
            case 260: return "Insert";
            case 261: return "Delete";
            case 262: return "Arrow Right";
            case 263: return "Arrow Left";
            case 264: return "Arrow Down";
            case 265: return "Arrow Up";
            case 266: return "Page Up";
            case 267: return "Page Down";
            case 268: return "Home";
            case 269: return "End";
            case 290: return "F1";
            case 291: return "F2 / Screenshot";
            case 292: return "F3 / Debug";
            case 293: return "F4";
            case 294: return "F5 / Perspective";
            case 295: return "F6";
            case 296: return "F7";
            case 297: return "F8";
            case 298: return "F9";
            case 299: return "F10";
            case 300: return "F11";
            case 301: return "F12";
            case 340: return "Left Shift / Sneak";
            case 341: return "Left Ctrl / Sprint";
            case 342: return "Left Alt";
            case 344: return "Right Shift";
            case 345: return "Right Ctrl";
            case 346: return "Right Alt";
            case 348: return "Menu Key";

            case TouchControlData.SPECIAL_MOUSE_LEFT: return "Mouse left click / Attack";
            case TouchControlData.SPECIAL_MOUSE_RIGHT: return "Mouse right click / Use";
            case TouchControlData.SPECIAL_MOUSE_MIDDLE: return "Mouse middle click / Pick block";
            case TouchControlData.SPECIAL_SCROLL_UP: return "Mouse wheel up";
            case TouchControlData.SPECIAL_SCROLL_DOWN: return "Mouse wheel down";
            case TouchControlData.SPECIAL_KEYBOARD: return "Open Android keyboard";
            case TouchControlData.SPECIAL_KEY_SENDER_KEYBOARD: return "Open key sender keyboard";
            case TouchControlData.SPECIAL_MENU: return "Open launcher menu";
            case TouchControlData.SPECIAL_TOGGLE_CONTROLS: return "Show / hide touch controls";
            case TouchControlData.SPECIAL_VIRTUAL_MOUSE: return "Toggle virtual cursor";
            case TouchControlData.SPECIAL_DUAL_SCREEN_SWAP: return "Swap dual screens";

            default: return "Key " + keyCode;
        }
    }

    @NonNull
    static String[] optionLabels(@NonNull Option[] options) {
        String[] labels = new String[options.length];
        for (int i = 0; i < options.length; i++) labels[i] = options[i].label;
        return labels;
    }
}

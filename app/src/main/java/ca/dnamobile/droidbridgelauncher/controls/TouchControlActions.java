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

/** Shared action names used by DroidBridge touch-control JSON. */
public final class TouchControlActions {
    public static final String KEY = "key";
    public static final String MOUSE = "mouse";
    public static final String SCROLL = "scroll";
    public static final String MENU = "menu";
    public static final String TOGGLE_CONTROLS = "toggle_controls";
    public static final String KEYBOARD = "keyboard";
    public static final String KEY_SENDER_KEYBOARD = "key_sender_keyboard";
    public static final String JOYSTICK = "joystick";
    public static final String VIRTUAL_MOUSE = "virtual_mouse";
    public static final String DUAL_SCREEN_SWAP = "dual_screen_swap";
    /** Expand/collapse a user-defined group of touch controls. */
    public static final String DRAWER = "drawer";

    private TouchControlActions() {
    }
}

/*
 * DroidBridge Launcher runtime bridge component.
 *
 * DroidBridge modifications:
 * Copyright (c) 2026 DNA Mobile Applications.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package ca.dnamobile.droidbridgelauncher.runtime;

/**
 * Small Java-side wrapper around libdroidbridge_awt.so.
 *
 * DroidBridge already ships the native methods in jni/awt_bridge.c, but the
 * original Java class only exposed nativeSendData().  GUI jar installers need
 * a higher-level bridge so the Activity can feed mouse/key/text events into
 * Cacio/Caciocavallo.
 */
public final class AWTInputBridge {
    private AWTInputBridge() {
    }

    public static final int EVENT_TYPE_CHAR = 1000;
    public static final int EVENT_TYPE_MOUSE_POS = 1003;
    public static final int EVENT_TYPE_KEY = 1005;
    public static final int EVENT_TYPE_MOUSE_BUTTON = 1006;

    /** java.awt.event.InputEvent.BUTTON1_DOWN_MASK without importing java.awt on Android. */
    public static final int BUTTON1_DOWN_MASK = 1 << 10;
    public static final int BUTTON2_DOWN_MASK = 1 << 11;
    public static final int BUTTON3_DOWN_MASK = 1 << 12;

    public static native void nativeSendData(int type, int i1, int i2, int i3, int i4);

    /** Native implementation already exists in jni/awt_bridge.c. */
    public static native void nativeMoveWindow(int xoff, int yoff);

    public static native void nativeClipboardReceived(String clipboardData, String clipboardDataMime);

    public static void sendMousePos(int x, int y) {
        nativeSendData(EVENT_TYPE_MOUSE_POS, x, y, 0, 0);
    }

    public static void sendMouseButton(int awtButtonMask, boolean down) {
        nativeSendData(EVENT_TYPE_MOUSE_BUTTON, awtButtonMask, down ? 1 : 0, 0, 0);
    }

    public static void sendLeftClick(int x, int y) {
        sendMousePos(x, y);
        sendMouseButton(BUTTON1_DOWN_MASK, true);
        sendMouseButton(BUTTON1_DOWN_MASK, false);
    }

    public static void sendKey(char keyChar, int awtKeyCode, boolean down) {
        nativeSendData(EVENT_TYPE_KEY, keyChar, awtKeyCode, down ? 1 : 0, 0);
    }

    public static void sendKeyTap(char keyChar, int awtKeyCode) {
        sendKey(keyChar, awtKeyCode, true);
        sendKey(keyChar, awtKeyCode, false);
    }

    public static void sendChar(char ch) {
        nativeSendData(EVENT_TYPE_CHAR, ch, 0, 0, 0);
    }

    public static void moveAllWindows(int xOffset, int yOffset) {
        nativeMoveWindow(xOffset, yOffset);
    }
}

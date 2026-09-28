/*
 * DroidBridge Launcher runtime bridge component.
 *
 * DroidBridge modifications:
 * Copyright (c) 2026 DNA Mobile Applications.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package org.lwjgl.glfw;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.view.Choreographer;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.runtime.DroidBridgeSDL3Bootstrap;
import ca.dnamobile.droidbridgelauncher.runtime.GrabListener;
import ca.dnamobile.droidbridgelauncher.runtime.LwjglGlfwKeycode;
import ca.dnamobile.droidbridgelauncher.runtime.utils.JREUtils;
import ca.dnamobile.droidbridgelauncher.storage.AndroidGameFolderOpener;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

import dalvik.annotation.optimization.CriticalNative;

public class CallbackBridge {
    public static final Choreographer sChoreographer = Choreographer.getInstance();
    private static boolean isGrabbing = false;
    private static volatile long sLastGuiLeftClickNanos;
    private static volatile long sLastControllerMouseSignalNanos;
    private static volatile boolean sControllerMouseSignalPhase;
    private static final ArrayList<GrabListener> grabListeners = new ArrayList<>();
    private static @Nullable WeakReference<Object> sDirectGamepadEnableHandler;

    private static @Nullable Context sAppContext;
    private static @Nullable ClipboardManager sClipboard;

    private static volatile boolean sInputReady;
    private static volatile boolean sUseInputStackQueue;

    public static final int CLIPBOARD_COPY = 2000;
    public static final int CLIPBOARD_PASTE = 2001;
    public static final int CLIPBOARD_OPEN = 2002;

    private static final long TEXT_CURSOR_RECENT_MS = 350L;

    private static volatile int sCurrentCursorShape = LwjglGlfwKeycode.GLFW_ARROW_CURSOR;
    private static volatile long sLastTextCursorUptimeMs;
    private static volatile long sCenterCursorOnNextGrabDeadlineUptimeMs;
    private static volatile long sCenterCursorOnNextTouchDeadlineUptimeMs;

    public static volatile int windowWidth, windowHeight;
    public static volatile int physicalWidth, physicalHeight;
    public static float mouseX, mouseY;
    private static volatile int sLastGlfwSetWindowWidth = -1;
    private static volatile int sLastGlfwSetWindowHeight = -1;
    public volatile static boolean holdingAlt, holdingCapslock, holdingCtrl,
            holdingNumlock, holdingShift;
    public static boolean sGamepadDirectInput = false;

    public static void init(Context context) {
        sAppContext = context.getApplicationContext();
        sClipboard = (ClipboardManager) sAppContext.getSystemService(Context.CLIPBOARD_SERVICE);
    }

    /**
     * DroidBridge set this before launch.
     *
     * Newer Minecraft/LWJGL versions use the input stack queue path. If this is left false,
     * mouse cursor movement can appear to work visually in DroidBridge while Minecraft ignores
     * button events because the native bridge is trying the wrong callback path.
     */
    public static void setUseInputStackQueue(boolean useInputStackQueue) {
        sUseInputStackQueue = useInputStackQueue;
        try {
            nativeSetUseInputStackQueue(useInputStackQueue);
            Log.i("CallbackBridge", "Input stack queue=" + useInputStackQueue);
        } catch (Throwable throwable) {
            Log.e("CallbackBridge", "nativeSetUseInputStackQueue failed: " + useInputStackQueue, throwable);
        }
    }

    public static boolean isUseInputStackQueue() {
        return sUseInputStackQueue;
    }

    /**
     * Queries Minecraft from the embedded OpenJDK VM, not Android ART. Minecraft's
     * Controls screen and selected KeyMapping do not exist in the Android classloader,
     * so launcher-side reflection can never reliably detect key-binding capture.
     */
    public static boolean isMinecraftKeybindCaptureActiveNative() {
        try {
            return nativeIsMinecraftKeybindCaptureActive();
        } catch (Throwable throwable) {
            Log.w("CallbackBridge", "Runtime-JVM keybind capture query unavailable", throwable);
            return false;
        }
    }

    /** Returns whether the embedded Minecraft JVM currently shows a Controls screen. */
    public static boolean isMinecraftControlsScreenOpenNative() {
        try {
            return nativeIsMinecraftControlsScreenOpen();
        } catch (Throwable throwable) {
            Log.w("CallbackBridge", "Runtime-JVM Controls screen query unavailable", throwable);
            return false;
        }
    }

    /**
     * Required by input_bridge_v3.c. Native input is dropped while this is false.
     */
    public static boolean setInputReady(boolean ready) {
        sInputReady = ready;
        try {
            boolean nativeStackMode = nativeSetInputReady(ready);
            // Keep Java's cached state aligned if native already had a value.
            sUseInputStackQueue = nativeStackMode;
            return nativeStackMode;
        } catch (Throwable throwable) {
            Log.e("CallbackBridge", "nativeSetInputReady failed: " + ready, throwable);
            return false;
        }
    }

    private static void ensureNativeInputReady() {
        if (!sInputReady) {
            setInputReady(true);
        }
    }

    public static void ensureInputFocus() {
        ensureNativeInputReady();
        try {
            nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 1);
            nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 1);
            nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 1);
        } catch (Throwable ignored) {
            // GLFW window may not exist yet.
        }
    }

    public static void clearInputFocus() {
        try {
            nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 0);
            nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 0);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Temporarily minimizes the GLFW window while DroidBridge moves an already-running
     * game between Android displays. Minecraft 26.x native Vulkan otherwise continues
     * acquiring images from an ANativeWindow that Android has just destroyed.
     */
    public static void beginWindowSurfaceTransfer() {
        sInputReady = false;
        try {
            nativeSetInputReady(false);
        } catch (Throwable ignored) {
        }
        try {
            nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 0);
            nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 0);
            nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_ICONIFIED, 1);
        } catch (Throwable ignored) {
            // The GLFW window may not have been created yet.
        }
    }

    /** Completes a display/surface transfer after the replacement Android window is live. */
    public static void finishWindowSurfaceTransfer() {
        try {
            nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 1);
            nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_ICONIFIED, 0);
            nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 1);
            nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 1);
        } catch (Throwable ignored) {
        }
        setInputReady(true);
    }

    public static void putMouseEventWithCoords(int button, float x, float y) {
        putMouseEventWithCoords(button, true, x, y);
        sChoreographer.postFrameCallbackDelayed(
                ignored -> putMouseEventWithCoords(button, false, x, y),
                33
        );
    }

    public static void putMouseEventWithCoords(int button, boolean isDown, float x, float y) {
        sendCursorPos(x, y);
        sendMouseKeycode(button, getCurrentMods(), isDown);
    }

    public static void sendCursorPos(float x, float y) {
        mouseX = x;
        mouseY = y;
        if (DroidBridgeSDL3Bootstrap.routeVirtualCursor(mouseX, mouseY)) {
            sInputReady = true;
            return;
        }
        ensureNativeInputReady();
        nativeSendCursorPos(mouseX, mouseY);
    }

    /**
     * Sends a cursor position that came from a real Android hardware mouse.
     *
     * This is intentionally separate from sendCursorPos(). Controller mods,
     * touch controls, and Minecraft itself also move/warp the logical cursor.
     * Only genuine hardware-mouse input is allowed to force the native cursor
     * pump baseline to refresh, which preserves PC-style mouse recentering
     * without making Controlify/Legacy4J see synthetic mouse movement.
     */
    public static void sendHardwareCursorPos(float x, float y) {
        mouseX = x;
        mouseY = y;
        if (DroidBridgeSDL3Bootstrap.routeVirtualCursor(mouseX, mouseY)) {
            sInputReady = true;
            return;
        }
        ensureNativeInputReady();
        nativeSendHardwareCursorPos(mouseX, mouseY);
    }

    /**
     * Marks Controllable right-stick look as genuine mouse activity without
     * replacing Controllable's camera movement. A sub-pixel alternating pulse
     * is enough for Minecraft's mouse-update path while remaining bounded and
     * avoiding the old grabbed-cursor fling regression.
     */
    public static void signalControllerMouseActivity(float rightX, float rightY) {
        final float activityDeadzone = 0.12f;
        if (!isGrabbing
                || (Math.abs(rightX) <= activityDeadzone
                && Math.abs(rightY) <= activityDeadzone)) {
            sControllerMouseSignalPhase = false;
            return;
        }

        long now = System.nanoTime();
        if (now - sLastControllerMouseSignalNanos < 20_000_000L) return;
        sLastControllerMouseSignalNanos = now;

        sControllerMouseSignalPhase = !sControllerMouseSignalPhase;
        float pulse = sControllerMouseSignalPhase ? 0.5f : -0.5f;
        float xStep = Math.abs(rightX) > activityDeadzone
                ? Math.copySign(pulse, rightX)
                : 0f;
        float yStep = Math.abs(rightY) > activityDeadzone
                ? Math.copySign(pulse, rightY)
                : 0f;

        sendCursorPos(mouseX + xStep, mouseY + yStep);
    }

    /**
     * Updates both the Java and native GLFW cursor caches without dispatching a
     * CursorPos callback into Minecraft. This is needed when Minecraft switches
     * from a GUI/menu back into grabbed camera mode: the last GUI cursor position
     * may be near the top/bottom of the screen, and letting the native pump see
     * that stale position as the grabbed baseline can become a huge first-look
     * delta, snapping the camera to the sky or ground.
     */
    public static void setCursorPosSilently(float x, float y) {
        mouseX = x;
        mouseY = y;
        try {
            nativeSetCursorPosSilently(mouseX, mouseY);
        } catch (Throwable ignored) {
            // Older native bridge builds may not have this helper yet. Keep the
            // Java cache update so relative touch input still starts from a sane
            // baseline instead of the last menu cursor position.
        }
    }

    public static void sendMouseButton(int button, boolean status) {
        sendMouseKeycode(button, CallbackBridge.getCurrentMods(), status);
    }

    public static void sendMouseKeycode(int button, int modifiers, boolean isDown) {
        if (button == LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT && isDown && !isGrabbing) {
            sLastGuiLeftClickNanos = System.nanoTime();
        }
        if (DroidBridgeSDL3Bootstrap.routeVirtualMouseButton(
                button, isDown, mouseX, mouseY)) {
            sInputReady = true;
            return;
        }
        ensureInputFocus();
        nativeSendMouseButton(button, isDown ? 1 : 0, modifiers);
    }

    /**
     * Timestamp of the most recent left-click delivered while Minecraft was in a
     * GUI. GamepadInputController uses this only as a short-lived hint that a
     * vanilla keybind row may now be waiting for the next keyboard/mouse event.
     */
    public static long getLastGuiLeftClickNanos() {
        return sLastGuiLeftClickNanos;
    }

    public static void clickMouseButtonAtCurrentPosition(int button) {
        ensureInputFocus();
        putMouseEventWithCoords(button, mouseX, mouseY);
    }

    public static void sendScroll(double xoffset, double yoffset) {
        if (DroidBridgeSDL3Bootstrap.routeVirtualScroll(xoffset, yoffset)) {
            sInputReady = true;
            return;
        }
        ensureInputFocus();
        nativeSendScroll(xoffset, yoffset);
    }

    public static void sendUpdateWindowSize(int w, int h) {
        nativeSendScreenSize(w, h);
    }

    /**
     * Called from patched LWJGL GLFW builds when Minecraft changes the logical
     * window size through glfwSetWindowSize.
     *
     * Important: this must NOT recreate or reattach the Android/EGL native
     * surface. Forge/Embeddium/Oculus call glfwSetWindowSize many times during
     * startup, shader reloads, and resolution slider changes. Treating each
     * logical GLFW size change as an Android surface resize causes the Mesa
     * KGSL backend to constantly recreate the same EGL window surface, which
     * produces flicker/black frames.
     */
    public static void onGlfwSetWindowSize(int w, int h) {
        int width = Math.max(1, w);
        int height = Math.max(1, h);

        if (sLastGlfwSetWindowWidth == width && sLastGlfwSetWindowHeight == height) {
            return;
        }
        sLastGlfwSetWindowWidth = width;
        sLastGlfwSetWindowHeight = height;

        windowWidth = width;
        windowHeight = height;
        physicalWidth = width;
        physicalHeight = height;
        mouseX = Math.max(0f, Math.min(mouseX, width - 1f));
        mouseY = Math.max(0f, Math.min(mouseY, height - 1f));

        try {
            nativeSendScreenSize(width, height);
        } catch (Throwable ignored) {
        }
    }

    public static boolean isGrabbing() {
        return isGrabbing;
    }

    /**
     * SDL3 replaces GLFW as Minecraft's window/input backend in Snapshot 4+.
     * Mirror SDL relative-mouse mode into the launcher grab state so the existing
     * controller mapper switches between menu cursor mode and in-game WASD/camera
     * mode at the same moment Minecraft does.
     */
    public static void setSdlGrabState(boolean grabbing) {
        if (isGrabbing == grabbing) return;
        onGrabStateChanged(grabbing);
    }

    /**
     * A direct Android touch click in a Minecraft GUI can leave the native GLFW
     * cursor at the tapped button location. If that button closes the GUI, Minecraft
     * switches to disabled/grabbed cursor mode before the launcher-side GrabListener
     * is notified. Center the native cursor cache inside the immediate grab callback
     * so the GUI click position cannot become the first gameplay camera baseline.
     */
    public static void centerCursorOnNextGrab(long timeoutMs) {
        long now = android.os.SystemClock.uptimeMillis();
        long deadline = now + Math.max(1L, timeoutMs);
        sCenterCursorOnNextGrabDeadlineUptimeMs = deadline;
        sCenterCursorOnNextTouchDeadlineUptimeMs = deadline;
    }

    /**
     * Re-arm only the next touch-start baseline center. This is used after the
     * Java-side grab listener has already run but before the user's first fresh
     * gameplay finger starts. It fixes stale GUI cursor baselines without eating
     * a whole timeout window of camera movement.
     */
    public static void centerCursorOnNextGrabbedTouch(long timeoutMs) {
        long now = android.os.SystemClock.uptimeMillis();
        sCenterCursorOnNextTouchDeadlineUptimeMs = now + Math.max(1L, timeoutMs);
    }

    private static boolean consumeCenterCursorOnNextGrab() {
        long deadline = sCenterCursorOnNextGrabDeadlineUptimeMs;
        if (deadline <= 0L) return false;

        long now = android.os.SystemClock.uptimeMillis();
        sCenterCursorOnNextGrabDeadlineUptimeMs = 0L;
        return now <= deadline;
    }

    /**
     * Consumes a pending menu-to-game touch baseline reset and centers the native
     * cursor cache silently. Call this once at the start of a fresh grabbed touch,
     * before relative camera deltas are generated.
     */
    public static boolean centerCursorForPendingGrabbedTouch() {
        long deadline = sCenterCursorOnNextTouchDeadlineUptimeMs;
        if (deadline <= 0L) return false;

        long now = android.os.SystemClock.uptimeMillis();
        sCenterCursorOnNextTouchDeadlineUptimeMs = 0L;
        if (now > deadline) return false;

        centerCursorSilentlyForGrab();
        return true;
    }

    public static void centerCursorSilently() {
        centerCursorSilentlyForGrab();
    }

    private static void centerCursorSilentlyForGrab() {
        float centerX = Math.max(1, windowWidth) / 2f;
        float centerY = Math.max(1, windowHeight) / 2f;
        setCursorPosSilently(centerX, centerY);
    }

    public static boolean isTextInputCursor() {
        if (sCurrentCursorShape == LwjglGlfwKeycode.GLFW_IBEAM_CURSOR) return true;

        // Some Minecraft screens restore the normal cursor shortly after the click
        // focuses the EditBox. Keep a tiny grace window so SurfaceView ACTION_UP
        // can still open Android's IME for the text field the user just tapped.
        long lastTextCursor = sLastTextCursorUptimeMs;
        return lastTextCursor > 0L
                && android.os.SystemClock.uptimeMillis() - lastTextCursor <= TEXT_CURSOR_RECENT_MS;
    }

    public static int getCurrentCursorShape() {
        return sCurrentCursorShape;
    }

    public static void sendKeycode(int keycode, char keychar, int scancode, int modifiers, boolean isDown) {
        if (keycode != 0 && DroidBridgeSDL3Bootstrap.routeVirtualKey(keycode, isDown)) {
            sInputReady = true;
            if (isDown && keychar != '\u0000' && !Character.isISOControl(keychar)) {
                DroidBridgeSDL3Bootstrap.routeVirtualText(String.valueOf(keychar));
            }
            return;
        }

        ensureInputFocus();
        if (keycode != 0) {
            nativeSendKey(keycode, scancode, isDown ? 1 : 0, modifiers);
        }

        if (isDown && keychar != '\u0000' && !Character.isISOControl(keychar)) {
            nativeSendCharMods(keychar, modifiers);
            nativeSendChar(keychar);
        }
    }

    public static void sendKeyPress(int keyCode, int modifiers, boolean status) {
        sendKeyPress(keyCode, '\u0000', 0, modifiers, status);
    }

    public static void sendKeyPress(int keyCode, char keyChar, int modifiers, boolean status) {
        sendKeyPress(keyCode, keyChar, 0, modifiers, status);
    }

    public static void sendKeyPress(int keyCode, char keyChar, int scancode, int modifiers, boolean status) {
        sendKeycode(keyCode, keyChar, scancode, modifiers, status);
    }

    public static void sendKeyPress(int keyCode) {
        sendKeyPress(keyCode, getCurrentMods(), true);
        sendKeyPress(keyCode, getCurrentMods(), false);
    }

    public static void sendChar(char keychar, int modifiers) {
        if (DroidBridgeSDL3Bootstrap.routeVirtualText(String.valueOf(keychar))) {
            sInputReady = true;
            return;
        }
        ensureInputFocus();
        nativeSendCharMods(keychar, modifiers);
        nativeSendChar(keychar);
    }

    /**
     * BTA runs inside the OpenJDK/LWJGL JVM, while Android controller events arrive
     * on the Android/ART side. These native calls store the latest Android gamepad
     * state in the shared native bridge so the BTA-only LWJGL helper can read it from
     * the OpenJDK side. This is intentionally separate from SDL/Controlify routing.
     */
    public static void setBtaGamepadPresent(boolean present) {
        try {
            nativeBtaSetGamepadPresent(present);
        } catch (Throwable ignored) {
        }

        try {
            DroidBridgeBtaGamepad.setPresent(present);
        } catch (Throwable ignored) {
        }
    }

    public static void setBtaGamepadIdentity(int deviceId, String name, String descriptor) {
        try {
            nativeBtaSetGamepadIdentity(deviceId, name, descriptor);
        } catch (Throwable ignored) {
        }

        try {
            DroidBridgeBtaGamepad.updateIdentity(
                    deviceId,
                    name != null ? name : "DroidBridge Android Controller"
            );
        } catch (Throwable ignored) {
        }
    }

    public static void sendBtaGamepadMotion(
            int deviceId,
            String name,
            String descriptor,
            float leftX,
            float leftY,
            float rightX,
            float rightY,
            float leftTrigger,
            float rightTrigger,
            boolean hatUp,
            boolean hatRight,
            boolean hatDown,
            boolean hatLeft
    ) {
        try {
            DroidBridgeBtaGamepad.updateMotion(
                    deviceId,
                    name != null ? name : "DroidBridge Android Controller",
                    leftX,
                    leftY,
                    rightX,
                    rightY,
                    leftTrigger,
                    rightTrigger,
                    hatUp,
                    hatRight,
                    hatDown,
                    hatLeft
            );
        } catch (Throwable ignored) {
        }

        try {
            nativeBtaSetGamepadMotion(
                    deviceId,
                    name,
                    descriptor,
                    leftX,
                    leftY,
                    rightX,
                    rightY,
                    leftTrigger,
                    rightTrigger,
                    hatUp,
                    hatRight,
                    hatDown,
                    hatLeft
            );
        } catch (Throwable ignored) {
        }
    }

    public static void sendBtaGamepadButton(int deviceId, String name, String descriptor, int androidKeyCode, boolean down) {
        try {
            DroidBridgeBtaGamepad.updateButton(
                    deviceId,
                    name != null ? name : "DroidBridge Android Controller",
                    androidKeyCode,
                    down
            );
        } catch (Throwable ignored) {
        }

        try {
            nativeBtaSetGamepadButton(deviceId, name, descriptor, androidKeyCode, down);
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("unused")
    public static @Nullable String accessAndroidClipboard(int type, String copy) {
        switch (type) {
            case CLIPBOARD_COPY:
                if (sClipboard != null) {
                    sClipboard.setPrimaryClip(ClipData.newPlainText("Copy", copy));
                }
                return null;
            case CLIPBOARD_PASTE:
                if (sClipboard != null
                        && sClipboard.hasPrimaryClip()
                        && sClipboard.getPrimaryClipDescription() != null
                        && sClipboard.getPrimaryClipDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
                    ClipData clipData = sClipboard.getPrimaryClip();
                    if (clipData == null || clipData.getItemCount() <= 0) return "";
                    CharSequence text = clipData.getItemAt(0).getText();
                    return text != null ? text.toString() : "";
                }
                return "";
            case CLIPBOARD_OPEN:
                if (sAppContext != null && copy != null && copy.trim().length() > 0) {
                    try {
                        boolean handled = AndroidGameFolderOpener.openFromGame(sAppContext, copy);
                        return handled ? "true" : "false";
                    } catch (Throwable t) {
                        Log.e("CallbackBridge", "Failed to open in-game URL/folder request", t);
                    }
                }
                return "false";
            default:
                return null;
        }
    }

    public static int getCurrentMods() {
        int currMods = 0;
        if (holdingAlt) currMods |= LwjglGlfwKeycode.GLFW_MOD_ALT;
        if (holdingCapslock) currMods |= LwjglGlfwKeycode.GLFW_MOD_CAPS_LOCK;
        if (holdingCtrl) currMods |= LwjglGlfwKeycode.GLFW_MOD_CONTROL;
        if (holdingNumlock) currMods |= LwjglGlfwKeycode.GLFW_MOD_NUM_LOCK;
        if (holdingShift) currMods |= LwjglGlfwKeycode.GLFW_MOD_SHIFT;
        return currMods;
    }

    public static void setModifiers(int keyCode, boolean isDown) {
        switch (keyCode) {
            case LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT:
            case LwjglGlfwKeycode.GLFW_KEY_RIGHT_SHIFT:
                holdingShift = isDown;
                return;
            case LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL:
            case LwjglGlfwKeycode.GLFW_KEY_RIGHT_CONTROL:
                holdingCtrl = isDown;
                return;
            case LwjglGlfwKeycode.GLFW_KEY_LEFT_ALT:
            case LwjglGlfwKeycode.GLFW_KEY_RIGHT_ALT:
                holdingAlt = isDown;
                return;
            case LwjglGlfwKeycode.GLFW_KEY_CAPS_LOCK:
                holdingCapslock = isDown;
                return;
            case LwjglGlfwKeycode.GLFW_KEY_NUM_LOCK:
                holdingNumlock = isDown;
        }
    }

    @SuppressWarnings("unused")
    @Keep
    private static void onDirectInputEnable() {
        sGamepadDirectInput = true;
        Object enableHandler = sDirectGamepadEnableHandler != null ? sDirectGamepadEnableHandler.get() : null;
        if (enableHandler instanceof Runnable) {
            ((Runnable) enableHandler).run();
        }
    }

    @SuppressWarnings("unused")
    @Keep
    private static void onCursorShapeChanged(int shape) {
        sCurrentCursorShape = shape;
        if (shape == LwjglGlfwKeycode.GLFW_IBEAM_CURSOR) {
            sLastTextCursorUptimeMs = android.os.SystemClock.uptimeMillis();
        }
    }

    @SuppressWarnings("unused")
    @Keep
    private static void onNativeCursorPosSilentlyChanged(float x, float y) {
        mouseX = x;
        mouseY = y;
    }

    @SuppressWarnings("unused")
    private static void onGrabStateChanged(final boolean grabbing) {
        isGrabbing = grabbing;
        sControllerMouseSignalPhase = false;
        sLastControllerMouseSignalNanos = 0L;

        // Do this immediately, not in the delayed GrabListener dispatch. The game
        // calls glfwSetInputMode(GLFW_CURSOR_DISABLED) while handling the Back to
        // Game click. Waiting even one frame lets Minecraft reuse the top-screen GUI
        // cursor position as a grabbed camera baseline, which snaps the player up.
        if (grabbing && consumeCenterCursorOnNextGrab()) {
            centerCursorSilentlyForGrab();
        }

        sChoreographer.postFrameCallbackDelayed((time) -> {
            if (isGrabbing != grabbing) return;
            synchronized (grabListeners) {
                for (GrabListener g : grabListeners) g.onGrabState(grabbing);
            }
        }, 16);
    }

    public static void setDirectGamepadEnableHandler(Object h) {
        sDirectGamepadEnableHandler = new WeakReference<>(h);
    }

    public static void addGrabListener(GrabListener listener) {
        synchronized (grabListeners) {
            listener.onGrabState(isGrabbing);
            grabListeners.add(listener);
        }
    }

    public static void removeGrabListener(GrabListener listener) {
        synchronized (grabListeners) {
            grabListeners.remove(listener);
        }
    }

    @Keep public static native boolean nativeSetInputReady(boolean inputReady);

    @Keep @CriticalNative public static native void nativeSetUseInputStackQueue(boolean useInputStackQueue);
    @Keep @CriticalNative private static native boolean nativeSendChar(char codepoint);
    @Keep @CriticalNative private static native boolean nativeSendCharMods(char codepoint, int mods);
    @Keep @CriticalNative private static native void nativeSendKey(int key, int scancode, int action, int mods);
    @Keep @CriticalNative private static native void nativeSendCursorPos(float x, float y);
    @Keep @CriticalNative private static native void nativeSendHardwareCursorPos(float x, float y);
    @Keep @CriticalNative private static native void nativeSetCursorPosSilently(float x, float y);
    @Keep @CriticalNative private static native void nativeSendMouseButton(int button, int action, int mods);
    @Keep private static native boolean nativeIsMinecraftKeybindCaptureActive();
    @Keep private static native boolean nativeIsMinecraftControlsScreenOpen();
    @Keep @CriticalNative private static native void nativeSendScroll(double xoffset, double yoffset);
    @Keep @CriticalNative private static native void nativeSendScreenSize(int width, int height);
    @Keep public static native void nativeSetWindowAttrib(int attrib, int value);
    @Keep public static native int getCurrentFps();
    @Keep @CriticalNative public static native void nativeSetCursorShape(int shape);

    @Keep public static native boolean nativeSdlGlSwapWindow(long window, long originalFunction);
    @Keep public static native boolean nativeSdlGlSetSwapInterval(int interval, long originalFunction);

    /**
     * Called before SDLVideo caches its function table inside the embedded OpenJDK VM.
     * The uniquely named library belongs to OpenJDK and supplies the direct
     * SDL_GL_SwapWindow presentation method without rebinding HotSpot native methods.
     */
    public static void installOpenJdkRuntimeHooks() {
        try {
            /*
             * libdroidbridge_runtime is already owned by Android/ART. Load a unique
             * OpenJDK-owned shim so CallbackBridge.nativeSdlGlSwapWindow resolves in
             * the correct VM without calling RegisterNatives during class startup.
             */
            System.loadLibrary("droidbridge_openjdk_hooks");
            System.err.println("DroidBridgeSDL3GL: OpenJDK direct presentation shim v16 loaded");
        } catch (Throwable throwable) {
            System.err.println("DroidBridgeSDL3GL: OpenJDK direct presentation shim v16 failed: " + throwable);
            throwable.printStackTrace(System.err);
        }
    }

    static {
        System.loadLibrary("droidbridge_runtime");
    }
    private static native void nativeBtaSetGamepadPresent(boolean present);
    private static native void nativeBtaSetGamepadIdentity(int deviceId, String name, String descriptor);
    private static native void nativeBtaSetGamepadMotion(
            int deviceId,
            String name,
            String descriptor,
            float leftX,
            float leftY,
            float rightX,
            float rightY,
            float leftTrigger,
            float rightTrigger,
            boolean hatUp,
            boolean hatRight,
            boolean hatDown,
            boolean hatLeft
    );
    private static native void nativeBtaSetGamepadButton(int deviceId, String name, String descriptor, int androidKeyCode, boolean down);

}

/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * Temporary Android input diagnostics for DroidBridge debug builds.
 */

package ca.dnamobile.droidbridgelauncher.input;

import android.os.Build;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

import ca.dnamobile.droidbridgelauncher.BuildConfig;
import ca.dnamobile.droidbridgelauncher.logs.LauncherDiagnosticLog;
import ca.dnamobile.droidbridgelauncher.logs.LauncherLogManager;

/**
 * Writes raw Android input callbacks to Logcat, launcherlog.txt and the active
 * latestlog.txt. This intentionally logs the same physical event at multiple
 * callback layers so we can see exactly where an OEM key disappears.
 */
public final class InputEventDiagnosticLogger {
    private static final String TAG = "DroidBridgeInput";
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Set<View> UNHANDLED_HOOKS =
            Collections.newSetFromMap(new WeakHashMap<>());

    private InputEventDiagnosticLogger() {
    }

    public static boolean isEnabled() {
        // Raw INPUT# traces are useful when diagnosing one device, but they make
        // normal latestlog.txt files unreadable. Keep them opt-in even in debug builds.
        return BuildConfig.DEBUG
                && Boolean.getBoolean("droidbridge.input.diagnostics");
    }

    public static void mark(@NonNull String message) {
        if (!isEnabled()) return;
        write("INPUT-MARK " + sanitize(message));
    }

    public static void logKeyEvent(@NonNull String origin, @Nullable KeyEvent event) {
        if (!isEnabled()) return;
        if (event == null) {
            write("INPUT#" + SEQUENCE.incrementAndGet()
                    + " origin=" + sanitize(origin) + " event=<null>");
            return;
        }

        InputDevice device = null;
        try {
            device = event.getDevice();
        } catch (Throwable ignored) {
        }

        StringBuilder out = new StringBuilder(512);
        out.append("INPUT#").append(SEQUENCE.incrementAndGet())
                .append(" origin=").append(sanitize(origin))
                .append(" action=").append(actionName(event.getAction()))
                .append('(').append(event.getAction()).append(')')
                .append(" keyCode=").append(event.getKeyCode())
                .append('(').append(KeyEvent.keyCodeToString(event.getKeyCode())).append(')')
                .append(" scanCode=").append(event.getScanCode())
                .append(" repeat=").append(event.getRepeatCount())
                .append(" source=0x").append(Integer.toHexString(event.getSource()))
                .append('(').append(sourceNames(event.getSource())).append(')')
                .append(" flags=0x").append(Integer.toHexString(event.getFlags()))
                .append(" meta=0x").append(Integer.toHexString(event.getMetaState()))
                .append(" deviceId=").append(event.getDeviceId())
                .append(" downTime=").append(event.getDownTime())
                .append(" eventTime=").append(event.getEventTime())
                .append(" canceled=").append(event.isCanceled())
                .append(" tracking=").append(event.isTracking());

        try {
            out.append(" unicode=").append(event.getUnicodeChar(event.getMetaState()));
        } catch (Throwable ignored) {
        }
        try {
            String characters = event.getCharacters();
            if (characters != null && !characters.isEmpty()) {
                out.append(" chars=\"").append(sanitize(characters)).append('\"');
            }
        } catch (Throwable ignored) {
        }

        appendDevice(out, device);
        write(out.toString());
    }

    /** Logs only motion events carrying an actual Android button transition. */
    public static void logMotionButtonEvent(
            @NonNull String origin,
            @Nullable MotionEvent event
    ) {
        if (!isEnabled() || event == null) return;

        int action = event.getActionMasked();
        int buttonState = event.getButtonState();
        int actionButton = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? event.getActionButton() : 0;
        if (buttonState == 0
                && actionButton == 0
                && action != MotionEvent.ACTION_BUTTON_PRESS
                && action != MotionEvent.ACTION_BUTTON_RELEASE) {
            return;
        }

        StringBuilder out = new StringBuilder(420);
        out.append("INPUT-MOTION#").append(SEQUENCE.incrementAndGet())
                .append(" origin=").append(sanitize(origin))
                .append(" action=").append(MotionEvent.actionToString(event.getAction()))
                .append(" actionMasked=").append(action)
                .append(" actionButton=0x").append(Integer.toHexString(actionButton))
                .append(" buttonState=0x").append(Integer.toHexString(buttonState))
                .append(" source=0x").append(Integer.toHexString(event.getSource()))
                .append('(').append(sourceNames(event.getSource())).append(')')
                .append(" deviceId=").append(event.getDeviceId())
                .append(" eventTime=").append(event.getEventTime());
        appendDevice(out, event.getDevice());
        write(out.toString());
    }

    public static void installUnhandledKeyHook(
            @Nullable View view,
            @NonNull String origin
    ) {
        if (!isEnabled() || view == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        synchronized (UNHANDLED_HOOKS) {
            if (!UNHANDLED_HOOKS.add(view)) return;
        }
        Api28Impl.install(view, origin);
        mark("installed unhandled-key hook origin=" + origin
                + " view=" + view.getClass().getName()
                + " id=" + view.getId()
                + " focus=" + view.hasFocus()
                + " windowFocus=" + view.hasWindowFocus());
    }

    private static void appendDevice(
            @NonNull StringBuilder out,
            @Nullable InputDevice device
    ) {
        if (device == null) {
            out.append(" device=<null>");
            return;
        }

        out.append(" deviceName=\"").append(sanitize(safeDeviceName(device))).append('\"')
                .append(" descriptor=\"").append(sanitize(safeDescriptor(device))).append('\"')
                .append(" vendor=").append(safeVendorId(device))
                .append(" product=").append(safeProductId(device))
                .append(" controllerNumber=").append(safeControllerNumber(device))
                .append(" keyboardType=").append(device.getKeyboardType())
                .append(" deviceSources=0x").append(Integer.toHexString(device.getSources()))
                .append('(').append(sourceNames(device.getSources())).append(')')
                .append(" external=").append(safeIsExternal(device));
    }

    @NonNull
    private static String safeDeviceName(@NonNull InputDevice device) {
        try {
            String value = device.getName();
            return value == null ? "<null>" : value;
        } catch (Throwable ignored) {
            return "<error>";
        }
    }

    @NonNull
    private static String safeDescriptor(@NonNull InputDevice device) {
        try {
            String value = device.getDescriptor();
            return value == null ? "<null>" : value;
        } catch (Throwable ignored) {
            return "<error>";
        }
    }

    private static int safeVendorId(@NonNull InputDevice device) {
        try {
            return device.getVendorId();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int safeProductId(@NonNull InputDevice device) {
        try {
            return device.getProductId();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int safeControllerNumber(@NonNull InputDevice device) {
        try {
            return device.getControllerNumber();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String safeIsExternal(@NonNull InputDevice device) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "unsupported";
        try {
            return Boolean.toString(device.isExternal());
        } catch (Throwable ignored) {
            return "error";
        }
    }

    @NonNull
    private static String actionName(int action) {
        switch (action) {
            case KeyEvent.ACTION_DOWN:
                return "DOWN";
            case KeyEvent.ACTION_UP:
                return "UP";
            case KeyEvent.ACTION_MULTIPLE:
                return "MULTIPLE";
            default:
                return "UNKNOWN";
        }
    }

    @NonNull
    private static String sourceNames(int source) {
        StringBuilder names = new StringBuilder();
        appendSource(names, source, InputDevice.SOURCE_KEYBOARD, "KEYBOARD");
        appendSource(names, source, InputDevice.SOURCE_DPAD, "DPAD");
        appendSource(names, source, InputDevice.SOURCE_GAMEPAD, "GAMEPAD");
        appendSource(names, source, InputDevice.SOURCE_JOYSTICK, "JOYSTICK");
        appendSource(names, source, InputDevice.SOURCE_MOUSE, "MOUSE");
        appendSource(names, source, InputDevice.SOURCE_MOUSE_RELATIVE, "MOUSE_RELATIVE");
        appendSource(names, source, InputDevice.SOURCE_TOUCHPAD, "TOUCHPAD");
        appendSource(names, source, InputDevice.SOURCE_TOUCHSCREEN, "TOUCHSCREEN");
        appendSource(names, source, InputDevice.SOURCE_STYLUS, "STYLUS");
        if (names.length() == 0) return "UNKNOWN";
        return names.toString();
    }

    private static void appendSource(
            @NonNull StringBuilder out,
            int source,
            int mask,
            @NonNull String name
    ) {
        if ((source & mask) != mask) return;
        if (out.length() > 0) out.append('|');
        out.append(name);
    }

    @NonNull
    private static String sanitize(@Nullable String value) {
        if (value == null) return "<null>";
        return value.replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .trim();
    }

    private static void write(@NonNull String line) {
        try {
            Log.i(TAG, line);
        } catch (Throwable ignored) {
        }
        try {
            LauncherDiagnosticLog.i(TAG, line);
        } catch (Throwable ignored) {
        }
        try {
            LauncherLogManager.append(line);
        } catch (Throwable ignored) {
        }
    }

    private static final class Api28Impl {
        private Api28Impl() {
        }

        static void install(@NonNull View view, @NonNull String origin) {
            view.addOnUnhandledKeyEventListener((target, event) -> {
                logKeyEvent(origin + ".unhandled", event);
                return false;
            });
        }
    }
}

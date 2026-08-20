package org.lwjgl.glfw;

import android.view.KeyEvent;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Locale;

/**
 * Java-side virtual GLFW gamepad state used by Android controller fallback paths.
 *
 * DroidBridge also mirrors controller state into the native bridge for the OpenJDK
 * side, but older GLFW-based controller mods such as Controllable 1.18.2 can read
 * the Java LWJGL GLFW methods directly. Keeping this state here prevents those
 * mods from seeing "no gamepad" even though Android already delivered the input.
 */
public final class DroidBridgeBtaGamepad {
    private static final int GLFW_JOYSTICK_1 = 0;

    private static final int GLFW_GAMEPAD_BUTTON_A = 0;
    private static final int GLFW_GAMEPAD_BUTTON_B = 1;
    private static final int GLFW_GAMEPAD_BUTTON_X = 2;
    private static final int GLFW_GAMEPAD_BUTTON_Y = 3;
    private static final int GLFW_GAMEPAD_BUTTON_LEFT_BUMPER = 4;
    private static final int GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER = 5;
    private static final int GLFW_GAMEPAD_BUTTON_BACK = 6;
    private static final int GLFW_GAMEPAD_BUTTON_START = 7;
    private static final int GLFW_GAMEPAD_BUTTON_GUIDE = 8;
    private static final int GLFW_GAMEPAD_BUTTON_LEFT_THUMB = 9;
    private static final int GLFW_GAMEPAD_BUTTON_RIGHT_THUMB = 10;
    private static final int GLFW_GAMEPAD_BUTTON_DPAD_UP = 11;
    private static final int GLFW_GAMEPAD_BUTTON_DPAD_RIGHT = 12;
    private static final int GLFW_GAMEPAD_BUTTON_DPAD_DOWN = 13;
    private static final int GLFW_GAMEPAD_BUTTON_DPAD_LEFT = 14;
    private static final int GLFW_GAMEPAD_BUTTON_LAST = GLFW_GAMEPAD_BUTTON_DPAD_LEFT;

    private static final int GLFW_GAMEPAD_AXIS_LEFT_X = 0;
    private static final int GLFW_GAMEPAD_AXIS_LEFT_Y = 1;
    private static final int GLFW_GAMEPAD_AXIS_RIGHT_X = 2;
    private static final int GLFW_GAMEPAD_AXIS_RIGHT_Y = 3;
    private static final int GLFW_GAMEPAD_AXIS_LEFT_TRIGGER = 4;
    private static final int GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER = 5;
    private static final int GLFW_GAMEPAD_AXIS_LAST = GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER;

    private static final byte GLFW_PRESS = 1;
    private static final byte GLFW_RELEASE = 0;

    private static final byte GLFW_HAT_CENTERED = 0;
    private static final byte GLFW_HAT_UP = 1;
    private static final byte GLFW_HAT_RIGHT = 2;
    private static final byte GLFW_HAT_DOWN = 4;
    private static final byte GLFW_HAT_LEFT = 8;

    private static final Object LOCK = new Object();
    private static final int SHARED_STATE_VERSION = 1;
    private static final String SHARED_STATE_PROPERTY = "droidbridge.glfw.gamepad.state";
    private static final long SHARED_STATE_MIN_READ_INTERVAL_MS = 8L;
    private static final float[] AXES = new float[GLFW_GAMEPAD_AXIS_LAST + 1];
    private static final byte[] BUTTONS = new byte[GLFW_GAMEPAD_BUTTON_LAST + 1];

    private static boolean present;
    private static int deviceId = -1;
    private static String name = "DroidBridge Android Controller";
    private static String guid = "droidbridge-android-controller";
    private static byte hat = GLFW_HAT_CENTERED;
    private static String sharedStateFilePath;
    private static long lastSharedStateReadMs;
    private static long lastSharedStateModifiedMs = -1L;

    private DroidBridgeBtaGamepad() {
    }

    /*
     * The native input bridge registers these readers when libglfw/lwjgl loads.
     * The Java/shared-state path is used first for Controllable, but the methods
     * still have to exist so RegisterNatives does not throw NoSuchMethodError.
     */
    private static native boolean nativeIsPresent(int jid);
    private static native String nativeName(int jid);
    private static native String nativeGuid(int jid);
    private static native boolean nativeReadAxes(int jid, FloatBuffer buffer);
    private static native boolean nativeReadButtons(int jid, ByteBuffer buffer);
    private static native boolean nativeReadHats(int jid, ByteBuffer buffer);

    public static void setSharedStateFilePath(String path) {
        synchronized (LOCK) {
            sharedStateFilePath = path;
            lastSharedStateReadMs = 0L;
            lastSharedStateModifiedMs = -1L;
            if (path != null && !path.trim().isEmpty()) {
                try {
                    System.setProperty(SHARED_STATE_PROPERTY, path.trim());
                } catch (Throwable ignored) {
                }
            }
        }
    }


    public static void setPresent(boolean newPresent) {
        synchronized (LOCK) {
            present = newPresent;
            if (present && (name == null || name.trim().isEmpty())) {
                name = "DroidBridge Android Controller";
                guid = buildStableGuid(name, deviceId);
            }
            writeSharedStateLocked();
        }
    }

    public static void updateIdentity(int androidDeviceId, String androidName) {
        synchronized (LOCK) {
            present = true;
            updateIdentityLocked(androidDeviceId, androidName);
            writeSharedStateLocked();
        }
    }

    public static void updateMotion(
            int androidDeviceId,
            String androidName,
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
        synchronized (LOCK) {
            present = true;
            updateIdentityLocked(androidDeviceId, androidName);

            AXES[GLFW_GAMEPAD_AXIS_LEFT_X] = clampAxis(leftX);
            AXES[GLFW_GAMEPAD_AXIS_LEFT_Y] = clampAxis(leftY);
            AXES[GLFW_GAMEPAD_AXIS_RIGHT_X] = clampAxis(rightX);
            AXES[GLFW_GAMEPAD_AXIS_RIGHT_Y] = clampAxis(rightY);
            // Android trigger axes normally rest at 0 and press toward 1, but
            // GLFW gamepad trigger axes rest at -1 and press toward 1. Store the
            // GLFW form so Controllable reads neutral triggers correctly.
            AXES[GLFW_GAMEPAD_AXIS_LEFT_TRIGGER] = triggerToGlfwAxis(leftTrigger);
            AXES[GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER] = triggerToGlfwAxis(rightTrigger);

            BUTTONS[GLFW_GAMEPAD_BUTTON_DPAD_UP] = hatUp ? GLFW_PRESS : GLFW_RELEASE;
            BUTTONS[GLFW_GAMEPAD_BUTTON_DPAD_RIGHT] = hatRight ? GLFW_PRESS : GLFW_RELEASE;
            BUTTONS[GLFW_GAMEPAD_BUTTON_DPAD_DOWN] = hatDown ? GLFW_PRESS : GLFW_RELEASE;
            BUTTONS[GLFW_GAMEPAD_BUTTON_DPAD_LEFT] = hatLeft ? GLFW_PRESS : GLFW_RELEASE;

            byte h = GLFW_HAT_CENTERED;
            if (hatUp) h |= GLFW_HAT_UP;
            if (hatRight) h |= GLFW_HAT_RIGHT;
            if (hatDown) h |= GLFW_HAT_DOWN;
            if (hatLeft) h |= GLFW_HAT_LEFT;
            hat = h;
            writeSharedStateLocked();
        }
    }

    public static void updateButton(int androidDeviceId, String androidName, int androidKeyCode, boolean down) {
        int glfwButton = toGlfwGamepadButton(androidKeyCode);
        synchronized (LOCK) {
            present = true;
            updateIdentityLocked(androidDeviceId, androidName);
            if (glfwButton >= 0 && glfwButton < BUTTONS.length) {
                BUTTONS[glfwButton] = down ? GLFW_PRESS : GLFW_RELEASE;
                updateHatFromButtonLocked(glfwButton, down);
            }
            writeSharedStateLocked();
        }
    }

    public static boolean joystickPresent(int jid) {
        synchronized (LOCK) {
            loadSharedStateIfNeededLocked();
            return isVirtualJoystick(jid) && present;
        }
    }

    public static String joystickName(int jid) {
        synchronized (LOCK) {
            loadSharedStateIfNeededLocked();
            return isVirtualJoystick(jid) && present ? name : null;
        }
    }

    public static String joystickGuid(int jid) {
        synchronized (LOCK) {
            loadSharedStateIfNeededLocked();
            return isVirtualJoystick(jid) && present ? guid : null;
        }
    }

    public static FloatBuffer joystickAxes(int jid) {
        synchronized (LOCK) {
            loadSharedStateIfNeededLocked();
            if (!isVirtualJoystick(jid) || !present) return null;
            FloatBuffer buffer = FloatBuffer.allocate(AXES.length);
            buffer.put(AXES);
            buffer.flip();
            return buffer;
        }
    }

    public static ByteBuffer joystickButtons(int jid) {
        synchronized (LOCK) {
            loadSharedStateIfNeededLocked();
            if (!isVirtualJoystick(jid) || !present) return null;
            ByteBuffer buffer = ByteBuffer.allocate(BUTTONS.length);
            buffer.put(BUTTONS);
            buffer.flip();
            return buffer;
        }
    }

    public static ByteBuffer joystickHats(int jid) {
        synchronized (LOCK) {
            loadSharedStateIfNeededLocked();
            if (!isVirtualJoystick(jid) || !present) return null;
            ByteBuffer buffer = ByteBuffer.allocate(1);
            buffer.put(hat);
            buffer.flip();
            return buffer;
        }
    }

    public static boolean joystickIsGamepad(int jid) {
        synchronized (LOCK) {
            loadSharedStateIfNeededLocked();
            return isVirtualJoystick(jid) && present;
        }
    }

    public static String gamepadName(int jid) {
        synchronized (LOCK) {
            loadSharedStateIfNeededLocked();
            return isVirtualJoystick(jid) && present ? name : null;
        }
    }

    public static boolean gamepadState(int jid, GLFWGamepadState state) {
        if (state == null) return false;
        synchronized (LOCK) {
            loadSharedStateIfNeededLocked();
            if (!isVirtualJoystick(jid) || !present) return false;

            ByteBuffer stateButtons = state.buttons();
            if (stateButtons != null) {
                for (int i = 0; i < BUTTONS.length && i < stateButtons.capacity(); i++) {
                    stateButtons.put(i, BUTTONS[i]);
                }
            }

            FloatBuffer stateAxes = state.axes();
            if (stateAxes != null) {
                for (int i = 0; i < AXES.length && i < stateAxes.capacity(); i++) {
                    stateAxes.put(i, AXES[i]);
                }
            }
            return true;
        }
    }

    private static void loadSharedStateIfNeededLocked() {
        File stateFile = resolveSharedStateFileLocked();
        if (stateFile == null || !stateFile.isFile()) return;

        long now = System.currentTimeMillis();
        long modified = stateFile.lastModified();
        if (modified == lastSharedStateModifiedMs
                && now - lastSharedStateReadMs < SHARED_STATE_MIN_READ_INTERVAL_MS) {
            return;
        }

        try (DataInputStream input = new DataInputStream(new FileInputStream(stateFile))) {
            int version = input.readInt();
            if (version != SHARED_STATE_VERSION) return;

            boolean loadedPresent = input.readBoolean();
            int loadedDeviceId = input.readInt();
            String loadedName = input.readUTF();
            String loadedGuid = input.readUTF();
            byte loadedHat = input.readByte();

            for (int i = 0; i < AXES.length; i++) {
                AXES[i] = clampAxis(input.readFloat());
            }
            for (int i = 0; i < BUTTONS.length; i++) {
                BUTTONS[i] = input.readByte();
            }

            present = loadedPresent;
            deviceId = loadedDeviceId;
            name = loadedName == null || loadedName.trim().isEmpty()
                    ? "DroidBridge Android Controller"
                    : loadedName;
            guid = loadedGuid == null || loadedGuid.trim().isEmpty()
                    ? buildStableGuid(name, deviceId)
                    : loadedGuid;
            hat = loadedHat;
            lastSharedStateModifiedMs = modified;
            lastSharedStateReadMs = now;
        } catch (Throwable ignored) {
            lastSharedStateReadMs = now;
        }
    }

    private static void writeSharedStateLocked() {
        File stateFile = resolveSharedStateFileLocked();
        if (stateFile == null) return;

        File parent = stateFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return;

        File tempFile = new File(stateFile.getAbsolutePath() + ".tmp");
        try (DataOutputStream output = new DataOutputStream(new FileOutputStream(tempFile, false))) {
            output.writeInt(SHARED_STATE_VERSION);
            output.writeBoolean(present);
            output.writeInt(deviceId);
            output.writeUTF(name != null ? name : "DroidBridge Android Controller");
            output.writeUTF(guid != null ? guid : buildStableGuid(name, deviceId));
            output.writeByte(hat);
            for (float axis : AXES) output.writeFloat(axis);
            for (byte button : BUTTONS) output.writeByte(button);
            output.flush();

            if (!tempFile.renameTo(stateFile)) {
                copyFile(tempFile, stateFile);
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }
            lastSharedStateModifiedMs = stateFile.lastModified();
            lastSharedStateReadMs = System.currentTimeMillis();
        } catch (Throwable ignored) {
            // The in-memory path still works for same-classloader callers.
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
        }
    }

    private static File resolveSharedStateFileLocked() {
        if (sharedStateFilePath == null || sharedStateFilePath.trim().isEmpty()) {
            try {
                String propertyPath = System.getProperty(SHARED_STATE_PROPERTY);
                if (propertyPath != null && !propertyPath.trim().isEmpty()) {
                    sharedStateFilePath = propertyPath.trim();
                }
            } catch (Throwable ignored) {
            }
        }
        return sharedStateFilePath == null || sharedStateFilePath.trim().isEmpty()
                ? null
                : new File(sharedStateFilePath);
    }

    private static void copyFile(File source, File destination) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination, false)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static boolean isVirtualJoystick(int jid) {
        return jid == GLFW_JOYSTICK_1;
    }

    private static void updateIdentityLocked(int androidDeviceId, String androidName) {
        deviceId = androidDeviceId;
        if (androidName != null && !androidName.trim().isEmpty()) {
            name = androidName.trim();
        }
        guid = buildStableGuid(name, deviceId);
    }

    private static String buildStableGuid(String controllerName, int controllerDeviceId) {
        String safeName = controllerName == null ? "controller" : controllerName;
        safeName = safeName.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "-");
        if (safeName.length() == 0) safeName = "controller";
        return "droidbridge-" + safeName + "-" + controllerDeviceId;
    }

    private static float clampAxis(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return 0f;
        if (value < -1f) return -1f;
        if (value > 1f) return 1f;
        return value;
    }

    private static float triggerToGlfwAxis(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return -1f;
        if (value >= 0f && value <= 1f) return clampAxis((value * 2f) - 1f);
        return clampAxis(value);
    }

    private static int toGlfwGamepadButton(int androidKeyCode) {
        switch (androidKeyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
                return GLFW_GAMEPAD_BUTTON_A;
            case KeyEvent.KEYCODE_BUTTON_B:
                return GLFW_GAMEPAD_BUTTON_B;
            case KeyEvent.KEYCODE_BUTTON_X:
                return GLFW_GAMEPAD_BUTTON_X;
            case KeyEvent.KEYCODE_BUTTON_Y:
                return GLFW_GAMEPAD_BUTTON_Y;
            case KeyEvent.KEYCODE_BUTTON_L1:
                return GLFW_GAMEPAD_BUTTON_LEFT_BUMPER;
            case KeyEvent.KEYCODE_BUTTON_R1:
                return GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BACK:
                return GLFW_GAMEPAD_BUTTON_BACK;
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_MENU:
                return GLFW_GAMEPAD_BUTTON_START;
            case KeyEvent.KEYCODE_BUTTON_MODE:
                return GLFW_GAMEPAD_BUTTON_GUIDE;
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
                return GLFW_GAMEPAD_BUTTON_LEFT_THUMB;
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                return GLFW_GAMEPAD_BUTTON_RIGHT_THUMB;
            case KeyEvent.KEYCODE_DPAD_UP:
                return GLFW_GAMEPAD_BUTTON_DPAD_UP;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return GLFW_GAMEPAD_BUTTON_DPAD_RIGHT;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return GLFW_GAMEPAD_BUTTON_DPAD_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return GLFW_GAMEPAD_BUTTON_DPAD_LEFT;
            default:
                return -1;
        }
    }

    private static void updateHatFromButtonLocked(int glfwButton, boolean down) {
        switch (glfwButton) {
            case GLFW_GAMEPAD_BUTTON_DPAD_UP:
                setHatBit(GLFW_HAT_UP, down);
                break;
            case GLFW_GAMEPAD_BUTTON_DPAD_RIGHT:
                setHatBit(GLFW_HAT_RIGHT, down);
                break;
            case GLFW_GAMEPAD_BUTTON_DPAD_DOWN:
                setHatBit(GLFW_HAT_DOWN, down);
                break;
            case GLFW_GAMEPAD_BUTTON_DPAD_LEFT:
                setHatBit(GLFW_HAT_LEFT, down);
                break;
            default:
                break;
        }
    }

    private static void setHatBit(byte bit, boolean down) {
        if (down) {
            hat = (byte) (hat | bit);
        } else {
            hat = (byte) (hat & ~bit);
        }
    }
}

package org.lwjgl.glfw;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Lightweight DroidBridge/LWJGL Android fallback for GLFWGamepadState.
 *
 * DroidBridge ships a patched GLFW bridge inside the Android app, but the full
 * LWJGL source tree is not compiled with the launcher. Older controller mods can
 * still allocate this object and pass it to GLFW.glfwGetGamepadState(...). This
 * class keeps the API surface that those mods normally use without requiring the
 * desktop LWJGL native struct implementation at Android compile time.
 */
public final class GLFWGamepadState implements Closeable {
    public static final int BUTTON_COUNT = 15;
    public static final int AXIS_COUNT = 6;
    public static final int SIZEOF = BUTTON_COUNT + (AXIS_COUNT * 4);
    public static final int ALIGNOF = 4;

    private final ByteBuffer buttons;
    private final FloatBuffer axes;

    public GLFWGamepadState() {
        ByteBuffer raw = ByteBuffer.allocateDirect(SIZEOF).order(ByteOrder.nativeOrder());
        raw.limit(BUTTON_COUNT);
        this.buttons = raw.slice().order(ByteOrder.nativeOrder());
        raw.clear();
        raw.position(BUTTON_COUNT);
        raw.limit(SIZEOF);
        this.axes = raw.slice().order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    public static GLFWGamepadState create() {
        return new GLFWGamepadState();
    }

    public static GLFWGamepadState create(long address) {
        return new GLFWGamepadState();
    }

    public static GLFWGamepadState createSafe(long address) {
        return address == 0L ? null : new GLFWGamepadState();
    }

    public static GLFWGamepadState malloc() {
        return new GLFWGamepadState();
    }

    public static GLFWGamepadState calloc() {
        GLFWGamepadState state = new GLFWGamepadState();
        state.clear();
        return state;
    }

    /**
     * Kept for LWJGL call sites that use stack allocation without needing the
     * actual native stack object on Android.
     */
    public static GLFWGamepadState mallocStack() {
        return malloc();
    }

    public static GLFWGamepadState callocStack() {
        return calloc();
    }

    public ByteBuffer buttons() {
        ByteBuffer copy = buttons.duplicate().order(ByteOrder.nativeOrder());
        copy.position(0);
        copy.limit(BUTTON_COUNT);
        return copy;
    }

    public byte buttons(int index) {
        return buttons.get(index);
    }

    public GLFWGamepadState buttons(int index, byte value) {
        buttons.put(index, value);
        return this;
    }

    public FloatBuffer axes() {
        FloatBuffer copy = axes.duplicate();
        copy.position(0);
        copy.limit(AXIS_COUNT);
        return copy;
    }

    public float axes(int index) {
        return axes.get(index);
    }

    public GLFWGamepadState axes(int index, float value) {
        axes.put(index, value);
        return this;
    }

    public GLFWGamepadState set(ByteBuffer sourceButtons, FloatBuffer sourceAxes) {
        if (sourceButtons != null) {
            for (int i = 0; i < BUTTON_COUNT && i < sourceButtons.capacity(); i++) {
                buttons.put(i, sourceButtons.get(i));
            }
        }
        if (sourceAxes != null) {
            for (int i = 0; i < AXIS_COUNT && i < sourceAxes.capacity(); i++) {
                axes.put(i, sourceAxes.get(i));
            }
        }
        return this;
    }

    public GLFWGamepadState set(GLFWGamepadState source) {
        if (source == null) return this;
        return set(source.buttons(), source.axes());
    }

    public GLFWGamepadState clear() {
        for (int i = 0; i < BUTTON_COUNT; i++) {
            buttons.put(i, (byte) 0);
        }
        for (int i = 0; i < AXIS_COUNT; i++) {
            axes.put(i, 0.0f);
        }
        return this;
    }

    public long address() {
        return 0L;
    }

    public ByteBuffer container() {
        return null;
    }

    public void free() {
        // Direct buffer lifetime is managed by the VM on Android.
    }

    @Override
    public void close() {
        free();
    }
}

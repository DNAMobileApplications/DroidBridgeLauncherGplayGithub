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

import android.content.Context;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.controls.MinecraftTextInputKeyboardTrigger;
import ca.dnamobile.droidbridgelauncher.modcompat.ControllerModCompat;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.settings.GameResolutionSettings;
import ca.dnamobile.droidbridgelauncher.runtime.DroidBridgeSDL3Bootstrap;
import ca.dnamobile.droidbridgelauncher.runtime.Logger;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Set;

/**
 * Built-in Android controller support.
 *
 * In menu mode:
 * - left stick moves the visible cursor
 * - right stick X can still move the cursor horizontally, while right stick Y sends Minecraft mouse-wheel scroll
 * - D-pad never moves the visible cursor unless that exact D-pad direction is manually mapped to a Cursor action
 * - A/R2/D-pad center are guarded to left-click even if old saved prefs mapped them to Enter
 * - A/D-pad center use a complete menu click pulse so held controller input cannot
 *   leak into Minecraft as a stuck Left Button; R2 remains holdable for sliders and
 *   click-drag operations.
 * - Minecraft keybind capture is detected from the actual Controls screen state. A
 *   normal inventory click never changes the mapping profile of the next button.
 */
public final class GamepadInputController {
    private static final String TAG = "GamepadInputController";

    /**
     * BTA has its own controller UI/input type system. When this is enabled,
     * Android controller events update the BTA-only LWJGL virtual controller
     * instead of being translated through DroidBridge's keyboard/mouse mapper.
     */
    public static volatile boolean btaNativeControllerBridgeEnabled = false;

    /**
     * True only for the real BTA/BTW controller route. Controllable/Legacy4J also
     * need the Android -> GLFW mirror, but they must not let DroidBridge's own
     * gamepad mapper, menu cursor, or right-stick scroll layer generate mouse input.
     */
    public static volatile boolean btaNativeControllerOwnsLauncherInput = false;

    /**
     * Mirror-only route for mods like old Controllable/Legacy4J. This updates
     * DroidBridge's virtual GLFW gamepad state, but it must never let the
     * launcher gamepad mapper generate mouse, scroll, keyboard, or camera input.
     */
    public static volatile boolean glfwGamepadMirrorEnabled = false;

    private static volatile boolean loggedBtaRightStickAxisChoice;
    private static volatile boolean loggedBtaRightStickMenuSample;
    private static volatile boolean loggedBtaLauncherMenuCursorRoute;
    private static volatile boolean loggedBtaNativeMenuButtonCursorPin;

    private static final float DEADZONE = 0.25f;
    private static final float TRIGGER_THRESHOLD = 0.50f;
    private static final float HAT_THRESHOLD = 0.85f;

    // Base values. User sensitivity prefs multiply these.
    private static final float BASE_GAME_CAMERA_SENSITIVITY = 18f;
    private static final float BASE_MENU_CURSOR_SENSITIVITY = 26f;
    private static final float BASE_MENU_SCROLL_SENSITIVITY = 0.16f;
    private static final float BTA_MENU_SCROLL_SENSITIVITY = 0.18f;
    private static final float BASE_DPAD_CURSOR_STEP = 14f;
    private static final float CURSOR_ACTION_BASE_STEP = 72f;

    // When Minecraft closes a GUI/inventory and re-grabs the pointer, Android can
    // still report the stick value that was being used to move the menu cursor.
    // If the right stick was already neutral, only swallow a tiny settle window so
    // the first real camera movement after leaving the menu is not lost. If the
    // stick was active, keep the stale-input guard but time it out so the camera
    // cannot stay stuck until the user moves the stick a second time.
    private static final long GAME_REGRAB_CAMERA_SETTLE_NANOS = 120_000_000L;
    private static final long GAME_REGRAB_STALE_STICK_TIMEOUT_NANOS = 650_000_000L;

    // Controller mappings must follow the actual Minecraft screen, not a timer or the
    // previous click. The reflection probe falls back to GLFW grab state on versions
    // where the current screen cannot be inspected.

    private static final int DIRECTION_NONE = -1;
    private static final int DIRECTION_EAST = 0;
    private static final int DIRECTION_NORTH_EAST = 1;
    private static final int DIRECTION_NORTH = 2;
    private static final int DIRECTION_NORTH_WEST = 3;
    private static final int DIRECTION_WEST = 4;
    private static final int DIRECTION_SOUTH_WEST = 5;
    private static final int DIRECTION_SOUTH = 6;
    private static final int DIRECTION_SOUTH_EAST = 7;

    public interface MappingRequestListener {
        void onRequestControllerMapping();
    }

    /**
     * Optional observer for gameplay actions that have already been resolved through
     * the active controller profile. This is deliberately downstream of mapping so
     * dual-screen HUD feedback never guesses what a physical shoulder/trigger means.
     */
    public interface MappedGameActionListener {
        void onHotbarScroll(int delta);
    }

    private final Choreographer choreographer = Choreographer.getInstance();
    @NonNull private final View hostView;
    private final Context context;
    private final GamepadMappingStore mappingStore;
    private final MappingRequestListener mappingRequestListener;
    @Nullable private final MappedGameActionListener mappedGameActionListener;
    private final EnumMap<GamepadButton, ActiveMappedAction[]> activeButtonActions = new EnumMap<>(GamepadButton.class);
    private final Set<String> latchedToggleBindings = new HashSet<>();
    private final EnumMap<GamepadAction, Integer> mappedKeyHoldCounts = new EnumMap<>(GamepadAction.class);
    private final EnumMap<GamepadAction, Boolean> deliveredMappedKeyStates = new EnumMap<>(GamepadAction.class);

    private boolean removed;
    private long lastFrameNanos = System.nanoTime();

    private float leftX;
    private float leftY;
    private float rightX;
    private float rightY;

    @Nullable private InputDevice activeDevice;

    // The same injected OEM keyboard event can occasionally visit Activity and
    // focused-view dispatch paths. Keep one logical M1/M2 transition per event.
    private long lastAynRearEventTime = Long.MIN_VALUE;
    private int lastAynRearAction = -1;
    private int lastAynRearKeyCode = KeyEvent.KEYCODE_UNKNOWN;

    private int currentDirection = DIRECTION_NONE;

    private boolean hatUp;
    private boolean hatDown;
    private boolean hatLeft;
    private boolean hatRight;
    private boolean leftTriggerDown;
    private boolean rightTriggerDown;

    // I love cheese
    private boolean lastGameMode;
    private boolean requireRightStickNeutralBeforeCamera;
    private long suppressCameraUntilNanos;
    private float menuScrollAccumulator;

    // A / D-pad center use a short menu click pulse so opening Minecraft's
    // keybind capture screen cannot leave Left Button held. New snapshot sliders,
    // however, require the same press to remain down while the controller cursor
    // moves. Keep the pulse for ordinary clicks, then promote it to a held drag as
    // soon as cursor movement is detected before the button is released.
    private static final long MENU_CLICK_PULSE_MILLIS = 40L;
    private int heldPulseMenuClickCount;
    private boolean pulseMenuMouseDown;
    private boolean pulseMenuDragActive;
    private long pulseMenuClickGeneration;


    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            tick(frameTimeNanos);
            if (!removed) {
                choreographer.postFrameCallback(this);
            }
        }
    };

    public GamepadInputController(@NonNull View hostView) {
        this(hostView, null, null);
    }

    /**
     * BTA reads gamepad state from the OpenJDK/LWJGL side before the user has
     * moved a stick. Seed the native bridge from Android InputDevice at launch
     * so BTA's own controller menu can see a real controller immediately.
     */
    public static void initializeBtaNativeControllerBridge(@NonNull Context context) {
        BtaNativeControllerBridge.initializeFromAndroidDevices(context);
    }

    /**
     * Initializes the same virtual GLFW gamepad state used by BTA, but without
     * enabling DroidBridge's own controller mapper. Controllable should see the
     * virtual controller through GLFW while owning all cursor/camera behavior itself.
     */
    public static void initializeGlfwGamepadMirror(@NonNull Context context) {
        BtaNativeControllerBridge.initializeFromAndroidDevices(context);
    }

    /**
     * Direct BTA route used by GameActivity before DroidBridge's normal launcher mapper.
     * This lets BTA's own controller system see real Android/Odin/Xbox controller state
     * instead of translated keyboard/mouse events.
     */
    public static boolean feedBtaNativeControllerMotion(@NonNull MotionEvent event) {
        return feedVirtualGlfwControllerMotion(event, true);
    }

    public static boolean feedGlfwGamepadMirrorMotion(@NonNull MotionEvent event) {
        return feedVirtualGlfwControllerMotion(event, false);
    }

    private static boolean feedVirtualGlfwControllerMotion(@NonNull MotionEvent event, boolean btaRoute) {
        if (btaRoute) {
            if (!btaNativeControllerBridgeEnabled) return false;
        } else {
            if (!glfwGamepadMirrorEnabled) return false;
        }
        if (event.getActionMasked() != MotionEvent.ACTION_MOVE) return false;

        InputDevice device = event.getDevice();
        if (device == null && event.getDeviceId() >= 0) {
            device = InputDevice.getDevice(event.getDeviceId());
        }

        int source = event.getSource();
        boolean fromController = ((source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
                || ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                || isAndroidGameController(device);
        if (!fromController) return false;

        InputDevice axisDevice = device;

        float lx = readAxis(event, axisDevice, MotionEvent.AXIS_X);
        float ly = readAxis(event, axisDevice, MotionEvent.AXIS_Y);

        float[] rightStick = readAndroidRightStickPair(event, axisDevice);
        float rx = rightStick[0];
        float ry = rightStick[1];

        float lt = readPositiveAxis(event, axisDevice, MotionEvent.AXIS_LTRIGGER);
        float rt = readPositiveAxis(event, axisDevice, MotionEvent.AXIS_RTRIGGER);
        if (lt == 0f) lt = readPositiveAxis(event, axisDevice, MotionEvent.AXIS_BRAKE);
        if (rt == 0f) rt = readPositiveAxis(event, axisDevice, MotionEvent.AXIS_GAS);

        float hx = readAxis(event, axisDevice, MotionEvent.AXIS_HAT_X);
        float hy = readAxis(event, axisDevice, MotionEvent.AXIS_HAT_Y);

        if (btaRoute && btaNativeControllerOwnsLauncherInput) {
            // GameActivity routes BTA joystick MotionEvents through this static bridge
            // before the instance controller gets a chance to copy the axes into its
            // leftX/leftY fields. Keep the menu-stick samples here as well so the very
            // first left-stick motion can drive DroidBridge's menu cursor immediately.
            BtaNativeControllerBridge.updateLatestMenuLeftStick(lx, ly);
            BtaNativeControllerBridge.updateLatestMenuRightStickY(ry);
        }

        if (!btaRoute) {
            markControllerModGamepadActivity(lx, ly, rx, ry, lt, rt, hx, hy);
        }

        return BtaNativeControllerBridge.updateMotion(
                axisDevice,
                lx, ly,
                rx, ry,
                lt, rt,
                hy < -HAT_THRESHOLD,
                hx > HAT_THRESHOLD,
                hy > HAT_THRESHOLD,
                hx < -HAT_THRESHOLD
        );
    }

    public static boolean feedBtaNativeControllerKey(@NonNull KeyEvent event) {
        return feedVirtualGlfwControllerKey(event, true);
    }

    public static boolean feedGlfwGamepadMirrorKey(@NonNull KeyEvent event) {
        return feedVirtualGlfwControllerKey(event, false);
    }

    private static boolean feedVirtualGlfwControllerKey(@NonNull KeyEvent event, boolean btaRoute) {
        if (btaRoute) {
            if (!btaNativeControllerBridgeEnabled) return false;
        } else {
            if (!glfwGamepadMirrorEnabled) return false;
        }

        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false;
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0) return true;

        InputDevice device = event.getDevice();
        if (device == null && event.getDeviceId() >= 0) {
            device = InputDevice.getDevice(event.getDeviceId());
        }

        int source = event.getSource();
        boolean fromController = ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                || ((source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
                || ((source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD)
                || isAndroidGameController(device)
                || isKnownControllerKey(event.getKeyCode());
        if (!fromController) return false;

        if (!btaRoute) {
            markControllerModButtonActivity();
        }

        // Keep BTA's native controller buttons intact. BTA 8 changes its input type
        // to CONTROLLER when the first native menu button is consumed, and that mode
        // transition can overwrite the menu cursor with an internal (0,0) baseline.
        // Preserve the current real cursor across that short transition instead of
        // translating the button into launcher keyboard/mouse mappings.
        if (btaRoute
                && btaNativeControllerOwnsLauncherInput
                && action == KeyEvent.ACTION_DOWN
                && !org.lwjgl.glfw.CallbackBridge.isGrabbing()) {
            BtaNativeControllerBridge.armNativeMenuButtonCursorPin();
        }

        return BtaNativeControllerBridge.updateButton(device, event.getKeyCode(), action == KeyEvent.ACTION_DOWN);
    }

    private static void markControllerModGamepadActivity(
            float leftX,
            float leftY,
            float rightX,
            float rightY,
            float leftTrigger,
            float rightTrigger,
            float hatX,
            float hatY
    ) {
        try {
            org.lwjgl.glfw.CallbackBridge.setInputReady(true);
            org.lwjgl.glfw.CallbackBridge.ensureInputFocus();
            if (Math.abs(leftX) > DEADZONE
                    || Math.abs(leftY) > DEADZONE
                    || Math.abs(rightX) > DEADZONE
                    || Math.abs(rightY) > DEADZONE
                    || leftTrigger > TRIGGER_THRESHOLD
                    || rightTrigger > TRIGGER_THRESHOLD
                    || Math.abs(hatX) > HAT_THRESHOLD
                    || Math.abs(hatY) > HAT_THRESHOLD) {
                org.lwjgl.glfw.CallbackBridge.sGamepadDirectInput = true;
            }

            // Controllable rotates the player directly from its gamepad state.
            // Desktop Minecraft also receives mouse movement during that action,
            // which advances mouse-driven tutorial/input activity. Emit a tiny,
            // bounded GLFW cursor pulse only while Controllable owns a grabbed
            // gameplay window; it never takes over the camera or menu cursor.
            if (ControllerModCompat.isControllableActive()) {
                org.lwjgl.glfw.CallbackBridge.signalControllerMouseActivity(rightX, rightY);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void markControllerModButtonActivity() {
        try {
            org.lwjgl.glfw.CallbackBridge.setInputReady(true);
            org.lwjgl.glfw.CallbackBridge.ensureInputFocus();
            org.lwjgl.glfw.CallbackBridge.sGamepadDirectInput = true;
        } catch (Throwable ignored) {
        }
    }

    private static float readAxis(@NonNull MotionEvent event, @Nullable InputDevice device, int axis) {
        InputDevice.MotionRange range = device != null ? getMotionRangeCompat(device, axis, event.getSource()) : null;

        float value = event.getAxisValue(axis);
        float flat = range != null ? Math.max(range.getFlat(), DEADZONE) : DEADZONE;
        return Math.abs(value) > flat ? value : 0f;
    }

    private static float readPositiveAxis(@NonNull MotionEvent event, @Nullable InputDevice device, int axis) {
        InputDevice.MotionRange range = device != null ? getMotionRangeCompat(device, axis, event.getSource()) : null;

        float value = event.getAxisValue(axis);
        float flat = range != null ? Math.max(range.getFlat(), 0.05f) : 0.05f;
        if (Math.abs(value) <= flat) return 0f;
        if (value < 0f) return 0f;
        return Math.min(value, 1f);
    }

    /**
     * Android controllers are inconsistent: some pads report the right stick on
     * Z/RZ, while others expose Z/RZ as resting trigger axes at -1 and use RX/RY
     * for the real right stick. BTA then sees the right stick jammed to top-left
     * and menu scrolling never receives Y movement. Prefer the centered pair when
     * Z/RZ looks like trigger rest and RX/RY is available.
     */
    @NonNull
    private static float[] readAndroidRightStickPair(@NonNull MotionEvent event, @Nullable InputDevice device) {
        float z = readAxis(event, device, MotionEvent.AXIS_Z);
        float rz = readAxis(event, device, MotionEvent.AXIS_RZ);
        float rx = readAxis(event, device, MotionEvent.AXIS_RX);
        float ry = readAxis(event, device, MotionEvent.AXIS_RY);

        boolean hasZR = hasAxis(device, event.getSource(), MotionEvent.AXIS_Z)
                || hasAxis(device, event.getSource(), MotionEvent.AXIS_RZ);
        boolean hasRXRY = hasAxis(device, event.getSource(), MotionEvent.AXIS_RX)
                || hasAxis(device, event.getSource(), MotionEvent.AXIS_RY);

        boolean zrLooksLikeTriggerRest = hasRXRY
                && z < -0.70f
                && rz < -0.70f
                && Math.abs(rx) <= DEADZONE
                && Math.abs(ry) <= DEADZONE;

        if (zrLooksLikeTriggerRest || (!hasZR && hasRXRY)) {
            logRightStickAxisChoiceOnce(device, "RX/RY", z, rz, rx, ry);
            return new float[]{rx, ry};
        }

        if (hasZR) {
            logRightStickAxisChoiceOnce(device, "Z/RZ", z, rz, rx, ry);
            return new float[]{z, rz};
        }

        logRightStickAxisChoiceOnce(device, "RX/RY fallback", z, rz, rx, ry);
        return new float[]{rx, ry};
    }

    private static boolean hasAxis(@Nullable InputDevice device, int source, int axis) {
        if (device == null) return false;
        return getMotionRangeCompat(device, axis, source) != null;
    }

    @Nullable
    private static InputDevice.MotionRange getMotionRangeCompat(@NonNull InputDevice device, int axis, int source) {
        InputDevice.MotionRange range = device.getMotionRange(axis, source);
        return range != null ? range : device.getMotionRange(axis);
    }

    private static void logRightStickAxisChoiceOnce(
            @Nullable InputDevice device,
            @NonNull String pair,
            float z,
            float rz,
            float rx,
            float ry
    ) {
        if (!btaNativeControllerBridgeEnabled || loggedBtaRightStickAxisChoice) return;
        loggedBtaRightStickAxisChoice = true;
        String name = device != null ? device.getName() : "unknown";
        try {
            Logger.appendToLog("BTA controller bridge: Android right-stick axis pair=" + pair
                    + " device=" + name
                    + " z/rz=" + z + "/" + rz
                    + " rx/ry=" + rx + "/" + ry);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isKnownControllerKey(int keyCode) {
        return GamepadButton.fromAndroidKeyCode(keyCode) != null
                || keyCode == KeyEvent.KEYCODE_BUTTON_MODE
                || keyCode == KeyEvent.KEYCODE_MENU;
    }


    public GamepadInputController(@NonNull View hostView, MappingRequestListener mappingRequestListener) {
        this(hostView, mappingRequestListener, null);
    }

    public GamepadInputController(
            @NonNull View hostView,
            @Nullable MappingRequestListener mappingRequestListener,
            @Nullable MappedGameActionListener mappedGameActionListener
    ) {
        this.hostView = hostView;
        context = hostView.getContext().getApplicationContext();
        mappingStore = GamepadMappingStore.get(hostView.getContext());
        this.mappingRequestListener = mappingRequestListener;
        this.mappedGameActionListener = mappedGameActionListener;

        hostView.setFocusable(true);
        hostView.setFocusableInTouchMode(true);
        hostView.requestFocus();

        if (btaNativeControllerBridgeEnabled) {
            BtaNativeControllerBridge.initializeFromAndroidDevices(hostView.getContext());
        }
        org.lwjgl.glfw.CallbackBridge.sendCursorPos(
                Math.max(1, org.lwjgl.glfw.CallbackBridge.windowWidth) / 2f,
                Math.max(1, org.lwjgl.glfw.CallbackBridge.windowHeight) / 2f
        );

        lastGameMode = isGameMode();
        choreographer.postFrameCallback(frameCallback);
    }

    public void removeSelf() {
        removed = true;
        releaseDirection();
        releaseAllMappedButtons();
        releaseAllToggleKeys("controller removed");
        releasePulseMenuMouse("controller removed");
    }

    /**
     * Called after the in-game controller mapper saves or resets a profile. Any
     * latched keys from the old mapping must be released before the new mapping
     * is allowed to take effect, otherwise changing L3 from Shift to another key
     * could leave Shift held until the activity is destroyed.
     */
    public void onMappingsChanged() {
        releaseDirection();
        releaseAllMappedButtons();
        releaseAllToggleKeys("controller mappings changed");
    }

    public boolean handleKeyEvent(@NonNull KeyEvent event) {
        InputEventDiagnosticLogger.logKeyEvent(
                "GamepadInputController.handleKeyEvent",
                event
        );
        if (!isGamepadKeyEvent(event)) return false;

        if (glfwGamepadMirrorEnabled && !btaNativeControllerOwnsLauncherInput) {
            feedGlfwGamepadMirrorKey(event);
            return true;
        }

        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return false;
        }

        InputDevice device = event.getDevice();
        GamepadButton button = GamepadButton.fromAndroidKeyEvent(event);
        boolean aynRearButton = GamepadButton.isAynOdinRearButtonEvent(event);

        if (!aynRearButton) {
            rememberDevice(device);
        }

        if (!aynRearButton && btaNativeControllerBridgeEnabled
                && BtaNativeControllerBridge.updateButton(device, event.getKeyCode(), action == KeyEvent.ACTION_DOWN)) {
            return true;
        }

        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_MODE
                || event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (action == KeyEvent.ACTION_UP && mappingRequestListener != null) {
                mappingRequestListener.onRequestControllerMapping();
            }
            return true;
        }

        if (button == null) return false;

        // Android can generate very fast repeat KeyEvents for D-pad directions.
        // The controller bridge keeps button/hat state itself, so repeated ACTION_DOWN
        // events are not needed and can make a remapped D-pad look like a mouse again.
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0) {
            return true;
        }

        boolean down = action == KeyEvent.ACTION_DOWN;
        if (!aynRearButton && btaNativeControllerBridgeEnabled
                && BtaNativeControllerBridge.updateButton(device, event.getKeyCode(), down)) {
            return btaNativeControllerOwnsLauncherInput;
        }

        if (!aynRearButton && btaNativeControllerBridgeEnabled && !btaNativeControllerOwnsLauncherInput) {
            return true;
        }

        InputDevice mappingDevice = aynRearButton ? activeDevice : device;
        if (aynRearButton && InputEventDiagnosticLogger.isEnabled()) {
            InputEventDiagnosticLogger.mark("AYN rear event "
                    + KeyEvent.keyCodeToString(event.getKeyCode())
                    + " scanCode=" + event.getScanCode()
                    + " source=0x" + Integer.toHexString(event.getSource())
                    + " device=" + describeInputDevice(device)
                    + " routedAs=" + button
                    + " activeProfile=" + mappingStore.getActiveProfileKey());
        }

        sendMappedButton(button, down, mappingDevice);
        return true;
    }

    /**
     * Direct route used by GameActivity before Android's normal keyboard pipeline.
     * This guarantees Odin rear events are treated as M1/M2 using their confirmed
     * BUTTON_Z/BUTTON_C keycodes and raw scan codes before normal game handling.
     */
    public boolean handleAynRearButtonEvent(
            @NonNull KeyEvent event,
            @NonNull GamepadButton button
    ) {
        InputEventDiagnosticLogger.logKeyEvent(
                "GamepadInputController.handleAynRearButtonEvent." + button,
                event
        );
        if (button != GamepadButton.M1 && button != GamepadButton.M2) {
            return false;
        }
        if (!GamepadButton.isAynOdinRearButtonEvent(event)) {
            return false;
        }

        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return true;
        }
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0) {
            return true;
        }

        long eventTime = event.getEventTime();
        int keyCode = event.getKeyCode();
        if (eventTime == lastAynRearEventTime
                && action == lastAynRearAction
                && keyCode == lastAynRearKeyCode) {
            return true;
        }
        lastAynRearEventTime = eventTime;
        lastAynRearAction = action;
        lastAynRearKeyCode = keyCode;

        InputDevice rawDevice = event.getDevice();
        InputDevice mappingDevice = activeDevice;

        if (InputEventDiagnosticLogger.isEnabled()) {
            InputEventDiagnosticLogger.mark("AYN rear direct route "
                    + KeyEvent.keyCodeToString(event.getKeyCode())
                    + " scanCode=" + event.getScanCode()
                    + " action=" + (action == KeyEvent.ACTION_DOWN ? "DOWN" : "UP")
                    + " source=0x" + Integer.toHexString(event.getSource())
                    + " rawDevice=" + describeInputDevice(rawDevice)
                    + " routedAs=" + button
                    + " activeProfile=" + mappingStore.getActiveProfileKey());
        }

        sendMappedButton(button, action == KeyEvent.ACTION_DOWN, mappingDevice);
        return true;
    }

    @NonNull
    private static String describeInputDevice(@Nullable InputDevice device) {
        if (device == null) return "<none>";
        String name;
        String descriptor;
        try {
            name = device.getName();
        } catch (Throwable ignored) {
            name = "<unknown>";
        }
        try {
            descriptor = device.getDescriptor();
        } catch (Throwable ignored) {
            descriptor = "<unknown>";
        }
        return name + "[" + descriptor + ",id=" + device.getId()
                + ",vendor=" + device.getVendorId()
                + ",product=" + device.getProductId() + "]";
    }

    public boolean handleMotionEvent(@NonNull MotionEvent event) {
        // Do not ever claim normal touchscreen/mouse events. This class is only for
        // physical controller axes/buttons. Check gamepad first because some
        // handhelds expose mixed sources like JOYSTICK | MOUSE/TOUCHPAD.
        if (!isGamepadMotionEvent(event)) return false;
        if (isPointerMotionEvent(event)) return false;

        if (glfwGamepadMirrorEnabled && !btaNativeControllerOwnsLauncherInput) {
            feedGlfwGamepadMirrorMotion(event);
            clearLeftStickAxes();
            clearRightStickAxes();
            releaseDirection();
            return true;
        }

        InputDevice device = event.getDevice();
        if (device == null) return false;
        rememberDevice(device);

        leftX = getCenteredAxis(event, device, MotionEvent.AXIS_X);
        leftY = getCenteredAxis(event, device, MotionEvent.AXIS_Y);

        float[] rightStick = readAndroidRightStickPair(event, device);
        rightX = rightStick[0];
        rightY = rightStick[1];

        float hatX = getCenteredAxis(event, device, MotionEvent.AXIS_HAT_X);
        float hatY = getCenteredAxis(event, device, MotionEvent.AXIS_HAT_Y);
        float leftTriggerAxis = getCenteredAxis(event, device, MotionEvent.AXIS_LTRIGGER);
        float rightTriggerAxis = getCenteredAxis(event, device, MotionEvent.AXIS_RTRIGGER);
        if (leftTriggerAxis == 0f) leftTriggerAxis = getCenteredAxis(event, device, MotionEvent.AXIS_BRAKE);
        if (rightTriggerAxis == 0f) rightTriggerAxis = getCenteredAxis(event, device, MotionEvent.AXIS_GAS);

        boolean hatLeftNow = hatX < -HAT_THRESHOLD;
        boolean hatRightNow = hatX > HAT_THRESHOLD;
        boolean hatUpNow = hatY < -HAT_THRESHOLD;
        boolean hatDownNow = hatY > HAT_THRESHOLD;

        if (btaNativeControllerBridgeEnabled) {
            if (btaNativeControllerOwnsLauncherInput) {
                BtaNativeControllerBridge.updateLatestMenuLeftStick(leftX, leftY);
                BtaNativeControllerBridge.updateLatestMenuRightStickY(rightY);
            }
            if (BtaNativeControllerBridge.updateMotion(
                    device,
                    leftX, leftY,
                    rightX, rightY,
                    Math.max(0f, leftTriggerAxis),
                    Math.max(0f, rightTriggerAxis),
                    hatUpNow, hatRightNow, hatDownNow, hatLeftNow)) {
                return true;
            }
        }

        updateDirection();

        updateHatButton(GamepadButton.DPAD_LEFT, hatLeftNow, device);
        updateHatButton(GamepadButton.DPAD_RIGHT, hatRightNow, device);
        updateHatButton(GamepadButton.DPAD_UP, hatUpNow, device);
        updateHatButton(GamepadButton.DPAD_DOWN, hatDownNow, device);

        updateTrigger(true, leftTriggerAxis > TRIGGER_THRESHOLD, device);
        updateTrigger(false, rightTriggerAxis > TRIGGER_THRESHOLD, device);

        return true;
    }

    private void rememberDevice(@Nullable InputDevice device) {
        if (device == null) return;
        activeDevice = device;
        mappingStore.rememberDevice(device);
    }

    private static boolean isGamepadKeyEvent(@NonNull KeyEvent event) {
        int source = event.getSource();
        InputDevice device = event.getDevice();

        boolean fromGamepad = (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (device != null && ((device.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (device.getSources() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK));

        if (fromGamepad) return true;

        return GamepadButton.fromAndroidKeyEvent(event) != null
                || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_MODE
                || event.getKeyCode() == KeyEvent.KEYCODE_MENU;
    }


    private static boolean isAndroidGameController(@Nullable InputDevice device) {
        if (device == null) return false;

        int sources;
        try {
            sources = device.getSources();
        } catch (Throwable ignored) {
            return false;
        }

        if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) return true;
        if ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return true;
        if ((sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD) return true;

        if (isAndroidPointerOnlyDevice(device, sources)) return false;

        String name;
        try {
            name = device.getName();
        } catch (Throwable ignored) {
            return false;
        }

        if (name == null) return false;
        String lower = name.toLowerCase(java.util.Locale.US);
        return lower.contains("controller")
                || lower.contains("gamepad")
                || lower.contains("joystick")
                || lower.contains("xbox")
                || lower.contains("playstation")
                || lower.contains("dualsense")
                || lower.contains("dualshock")
                || lower.contains("odin")
                || lower.contains("retroid")
                || lower.contains("anbernic")
                || lower.contains("8bitdo")
                || lower.contains("gamesir")
                || lower.contains("razer");
    }

    private static boolean isAndroidPointerOnlyDevice(@NonNull InputDevice device, int sources) {
        boolean controllerSource = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
        if (controllerSource) return false;

        boolean pointerSource = (sources & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || (sources & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
                || (sources & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD
                || (sources & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
                || (sources & InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS;
        if (!pointerSource) return false;

        String name;
        try {
            name = device.getName();
        } catch (Throwable ignored) {
            return true;
        }

        String lower = name != null ? name.toLowerCase(java.util.Locale.US) : "";
        return lower.contains("mouse")
                || lower.contains("virtual mouse")
                || lower.contains("touchpad")
                || lower.contains("pointer")
                || lower.contains("cursor");
    }

    private static boolean isPointerMotionEvent(@NonNull MotionEvent event) {
        int source = event.getSource();
        boolean gamepad = (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
        if (gamepad) return false;

        return (source & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
                || (source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || (source & InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS
                || event.getPointerCount() > 1;
    }

    private static boolean isGamepadMotionEvent(@NonNull MotionEvent event) {
        int source = event.getSource();
        return ((source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                && event.getActionMasked() == MotionEvent.ACTION_MOVE;
    }

    private static float getCenteredAxis(@NonNull MotionEvent event, @NonNull InputDevice device, int axis) {
        InputDevice.MotionRange range = getMotionRangeCompat(device, axis, event.getSource());
        if (range == null) return 0f;

        float value = event.getAxisValue(axis);
        float flat = Math.max(range.getFlat(), DEADZONE);
        return Math.abs(value) > flat ? value : 0f;
    }

    private boolean isGameMode() {
        return org.lwjgl.glfw.CallbackBridge.isGrabbing() || mappingStore.isForceGameMode();
    }

    /**
     * Resolves the normal menu/game profile without crossing into Minecraft's JVM.
     * Active keybind capture forces the saved game mapping through a separate path.
     */
    private boolean isGameModeForButtonMapping() {
        // Cursor grab is maintained by the native bridge and is safe on the controller
        // hot path. Keybind capture forces the saved game mapping separately.
        return mappingStore.isForceGameMode() || org.lwjgl.glfw.CallbackBridge.isGrabbing();
    }

    private void tick(long frameTimeNanos) {
        float deltaScale = (frameTimeNanos - lastFrameNanos) / 16_666_666f;
        if (deltaScale <= 0f || deltaScale > 4f) deltaScale = 1f;

        boolean gameMode = isGameMode();
        if (gameMode != lastGameMode) {
            handleInputModeChanged(gameMode, frameTimeNanos);
            lastGameMode = gameMode;
            // Do not let the frame that changed modes consume stale delta/axis data.
            lastFrameNanos = frameTimeNanos;
            return;
        }

        if (glfwGamepadMirrorEnabled && !btaNativeControllerOwnsLauncherInput) {
            clearLeftStickAxes();
            clearRightStickAxes();
            releaseDirection();
            lastFrameNanos = frameTimeNanos;
            return;
        }

        if (btaNativeControllerBridgeEnabled) {
            if (btaNativeControllerOwnsLauncherInput) {
                if (gameMode) {
                    BtaNativeControllerBridge.resetMenuScroll();
                } else {
                    // BTA 8 resets its own controller virtual-cursor to (0,0) when
                    // the first stick sample switches the game into CONTROLLER input
                    // mode. Do not use that cursor on Android. Keep BTA's controller
                    // device/buttons alive, but let DroidBridge own menu pointer motion
                    // exactly like the normal launcher controller path.
                    tickBtaMenuCursor(deltaScale);
                    BtaNativeControllerBridge.tickMenuScroll(deltaScale);
                }
            }
            lastFrameNanos = frameTimeNanos;
            return;
        }

        if (gameMode) {
            tickCamera(deltaScale, frameTimeNanos);
        } else {
            tickMenuCursor(deltaScale);
        }

        lastFrameNanos = frameTimeNanos;
    }

    private void handleInputModeChanged(boolean gameMode, long frameTimeNanos) {
        releaseDirection();
        menuScrollAccumulator = 0f;

        if (gameMode) {
            // Never carry a controller-created menu drag into grabbed camera mode.
            releasePulseMenuMouse("Minecraft input re-grabbed");
            blockCameraAfterMenuClose(frameTimeNanos, "Minecraft input re-grabbed");
        } else {
            requireRightStickNeutralBeforeCamera = false;
            suppressCameraUntilNanos = 0L;
        }
    }

    private void prepareForLikelyMenuCloseFromController(@NonNull GamepadButton button) {
        blockCameraAfterMenuClose(System.nanoTime(), "Menu close requested by " + button);
    }

    private void blockCameraAfterMenuClose(long nowNanos, @NonNull String reason) {
        boolean rightStickWasActive = !isRightStickNeutral();

        clearLeftStickAxes();
        if (!rightStickWasActive) {
            clearRightStickAxes();
        }
        releaseDirection();

        requireRightStickNeutralBeforeCamera = rightStickWasActive;
        suppressCameraUntilNanos = Math.max(
                suppressCameraUntilNanos,
                nowNanos + (rightStickWasActive
                        ? GAME_REGRAB_STALE_STICK_TIMEOUT_NANOS
                        : GAME_REGRAB_CAMERA_SETTLE_NANOS)
        );

        Logging.i(TAG, reason + (rightStickWasActive
                ? "; right stick was active, guarding camera until neutral or timeout"
                : "; right stick was neutral, short camera settle only"));
    }

    private boolean isRightStickNeutral() {
        return rightX == 0f && rightY == 0f;
    }

    private void clearLeftStickAxes() {
        leftX = 0f;
        leftY = 0f;
    }

    private void clearRightStickAxes() {
        rightX = 0f;
        rightY = 0f;
        menuScrollAccumulator = 0f;
    }

    private void tickCamera(float deltaScale, long frameTimeNanos) {
        if (shouldBlockCameraInput(frameTimeNanos)) return;
        if (rightX == 0f && rightY == 0f) return;

        float magnitude = Math.min(1f, (float) Math.sqrt(rightX * rightX + rightY * rightY));

        // The old magnitude-squared curve multiplied small right-stick values by
        // themselves twice: rightX * magnitude * magnitude. Around the normal
        // precision-look range this reduced camera output so much that even large
        // sensitivity changes felt almost identical. A single magnitude curve
        // keeps fine control near centre while allowing the sensitivity setting to
        // produce an obvious, predictable difference.
        float acceleration = magnitude;

        float sensitivity = BASE_GAME_CAMERA_SENSITIVITY
                * mappingStore.getGameCameraSensitivityMultiplier()
                * cameraSurfaceCoordinateScale();

        // Keep the user's chosen render-resolution scale out of camera speed, but
        // correct any *additional* mismatch between the GLFW window and Android's
        // physical Surface. A few devices report a second, device-specific Surface
        // scale here, which otherwise makes the same 100% controller sensitivity
        // dramatically faster or slower than it is on other phones.
        float deltaX = rightX * acceleration * sensitivity * deltaScale;
        float deltaY = rightY * acceleration * sensitivity * deltaScale;

        org.lwjgl.glfw.CallbackBridge.mouseX += deltaX;
        org.lwjgl.glfw.CallbackBridge.mouseY += deltaY;
        org.lwjgl.glfw.CallbackBridge.sendCursorPos(org.lwjgl.glfw.CallbackBridge.mouseX, org.lwjgl.glfw.CallbackBridge.mouseY);
    }


    /**
     * Returns only the unexpected Surface/window correction. The expected ratio is
     * the user's selected game-resolution percentage, so lowering resolution does
     * not silently change controller sensitivity.
     */
    private float cameraSurfaceCoordinateScale() {
        try {
            float windowWidth = org.lwjgl.glfw.CallbackBridge.windowWidth;
            float windowHeight = org.lwjgl.glfw.CallbackBridge.windowHeight;
            float physicalWidth = org.lwjgl.glfw.CallbackBridge.physicalWidth;
            float physicalHeight = org.lwjgl.glfw.CallbackBridge.physicalHeight;
            if (windowWidth <= 1f || windowHeight <= 1f || physicalWidth <= 1f || physicalHeight <= 1f) {
                return 1f;
            }

            float actualX = windowWidth / physicalWidth;
            float actualY = windowHeight / physicalHeight;
            if (actualX <= 0.05f || actualX >= 4f || actualY <= 0.05f || actualY >= 4f) {
                return 1f;
            }

            int percent = LauncherPreferences.getGameResolutionScalePercent(context);
            GameResolutionSettings.ResolvedResolution expectedRender =
                    GameResolutionSettings.resolveRenderResolution(
                            context,
                            Math.max(1, Math.round(physicalWidth)),
                            Math.max(1, Math.round(physicalHeight)),
                            percent
                    );
            float expectedX = expectedRender.width / Math.max(1f, physicalWidth);
            float expectedY = expectedRender.height / Math.max(1f, physicalHeight);
            float expected = (float) Math.sqrt(expectedX * expectedY);
            float actual = (float) Math.sqrt(actualX * actualY);
            float unexpectedScale = actual / Math.max(0.01f, expected);

            // Ignore normal rounding/aspect noise. Correct only a meaningful extra
            // device Surface transform, and clamp bad vendor-reported dimensions.
            if (Math.abs(unexpectedScale - 1f) < 0.06f) return 1f;
            return clamp(1f / unexpectedScale, 0.50f, 2.0f);
        } catch (Throwable ignored) {
            return 1f;
        }
    }

    private boolean shouldBlockCameraInput(long frameTimeNanos) {
        boolean rightStickNeutral = isRightStickNeutral();
        boolean timedSuppressActive = frameTimeNanos < suppressCameraUntilNanos;

        if (requireRightStickNeutralBeforeCamera && (rightStickNeutral || !timedSuppressActive)) {
            // Clear the latch as soon as the stale stick returns neutral. Also clear
            // it after the stale timeout so a noisy controller cannot leave camera
            // input stuck until the user moves the right stick a second time.
            requireRightStickNeutralBeforeCamera = false;

            if (rightStickNeutral && timedSuppressActive) {
                suppressCameraUntilNanos = Math.min(
                        suppressCameraUntilNanos,
                        frameTimeNanos + GAME_REGRAB_CAMERA_SETTLE_NANOS
                );
                timedSuppressActive = frameTimeNanos < suppressCameraUntilNanos;
            }
        }

        return timedSuppressActive || requireRightStickNeutralBeforeCamera;
    }

    /**
     * BTA 8 menu cursor workaround. BTA's internal controller cursor is initialized
     * after the first controller-mode transition and can start at the top-left even
     * though the visible mouse was centered. Move the real GLFW/LWJGL mouse directly
     * from the LEFT stick instead, while updateMotion() feeds neutral stick axes to
     * BTA whenever the mouse is ungrabbed. In grabbed gameplay BTA receives the real
     * axes unchanged.
     */
    private void tickBtaMenuCursor(float deltaScale) {
        // A native BTA menu button can switch BTA into CONTROLLER mode and reset its
        // private cursor baseline after Android has already delivered the KeyEvent.
        // Restore the real menu cursor before applying this frame's stick motion.
        BtaNativeControllerBridge.restoreNativeMenuButtonCursorPinIfActive();

        // BTA joystick events are normally consumed by GameActivity's static
        // native-controller route before handleMotionEvent() updates this instance's
        // leftX/leftY fields. Read the samples captured by that route instead. This is
        // what makes the FIRST stick movement work without requiring a touch/button to
        // wake a different input path first.
        float x = BtaNativeControllerBridge.latestMenuLeftStickX();
        float y = BtaNativeControllerBridge.latestMenuLeftStickY();
        if (x == 0f && y == 0f) return;

        float magnitude = Math.min(1f, (float) Math.sqrt(x * x + y * y));
        float acceleration = Math.max(0.35f, magnitude * magnitude);
        float sensitivity = BASE_MENU_CURSOR_SENSITIVITY
                * mappingStore.getMenuCursorSensitivityMultiplier()
                * menuCursorResolutionScale();
        float dx = x * acceleration * sensitivity * deltaScale;
        float dy = y * acceleration * sensitivity * deltaScale;

        GamepadAction.moveCursorBy(dx, dy);
        BtaNativeControllerBridge.refreshNativeMenuButtonCursorPinFromCurrent();

        if (!loggedBtaLauncherMenuCursorRoute) {
            loggedBtaLauncherMenuCursorRoute = true;
            try {
                Logger.appendToLog("BTA controller bridge: left-stick menu cursor owned by DroidBridge; BTA menu stick axes suppressed");
            } catch (Throwable ignored) {
            }
        }
    }

    private void tickMenuCursor(float deltaScale) {
        // Match launcher behavior users expect in Minecraft menus: the right stick's
        // vertical axis is a mouse wheel, not a second vertical cursor stick. This
        // makes mod lists, world lists, inventory recipe/book panels, and normal
        // Minecraft scrollbars react when the user pushes the right stick up/down.
        tickMenuRightStickScroll(deltaScale);

        float x = Math.abs(rightX) > Math.abs(leftX) ? rightX : leftX;
        float y = leftY;

        float dx = 0f;
        float dy = 0f;

        float sensitivityMultiplier = mappingStore.getMenuCursorSensitivityMultiplier();
        float menuResolutionScale = menuCursorResolutionScale();

        if (x != 0f || y != 0f) {
            float magnitude = Math.min(1f, (float) Math.sqrt(x * x + y * y));
            float acceleration = Math.max(0.35f, magnitude * magnitude);
            float sensitivity = BASE_MENU_CURSOR_SENSITIVITY * sensitivityMultiplier * menuResolutionScale;
            dx += x * acceleration * sensitivity * deltaScale;
            dy += y * acceleration * sensitivity * deltaScale;
        }

        // Only repeat D-pad cursor movement when that D-pad direction is actually mapped
        // to a Cursor action. This fixes remapped D-pad buttons still behaving like a joystick.
        float cursorRepeatScale = (BASE_DPAD_CURSOR_STEP / CURSOR_ACTION_BASE_STEP)
                * sensitivityMultiplier
                * menuResolutionScale
                * deltaScale;
        float[] dpadDelta = addDpadCursorRepeat(cursorRepeatScale);
        dx += dpadDelta[0];
        dy += dpadDelta[1];

        if (dx != 0f || dy != 0f) {
            // Promote A / D-pad-center's safe click pulse into a real held mouse
            // press before moving the pointer. This preserves normal one-shot menu
            // clicks while allowing sliders and scrollbars to drag in 26.x snapshots.
            promotePulseMenuClickToDrag();
            GamepadAction.moveCursorBy(dx, dy);
        }
    }

    private void tickMenuRightStickScroll(float deltaScale) {
        if (rightY == 0f) {
            menuScrollAccumulator = 0f;
            return;
        }

        float strength = Math.min(1f, Math.abs(rightY));
        float acceleration = Math.max(0.35f, strength * strength);
        float sensitivity = BASE_MENU_SCROLL_SENSITIVITY
                * mappingStore.getMenuCursorSensitivityMultiplier();

        // Android right-stick up is negative Y. GLFW/Minecraft mouse-wheel up is
        // positive Y, so invert the joystick axis before sending the scroll event.
        menuScrollAccumulator += -rightY * acceleration * sensitivity * deltaScale;

        if (Math.abs(menuScrollAccumulator) < 1f) return;

        int scrollSteps = (int) menuScrollAccumulator;
        menuScrollAccumulator -= scrollSteps;

        try {
            org.lwjgl.glfw.CallbackBridge.setInputReady(true);
            org.lwjgl.glfw.CallbackBridge.sendScroll(0d, scrollSteps);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to send right-stick menu scroll", throwable);
        }
    }

    /**
     * Menu cursor coordinates are visual/window coordinates, so lower render
     * resolution can make the visible cursor travel too far. Keep this correction
     * limited to menu cursor movement only; grabbed in-game camera movement must
     * stay unscaled.
     */
    private float menuCursorResolutionScale() {
        // Snapshot 4's SDL cursor is stored in the stable visible Android view
        // coordinate space. Applying the framebuffer resolution percentage here
        // would make controller movement scale-dependent a second time.
        if (DroidBridgeSDL3Bootstrap.isRequested()) {
            return 1f;
        }

        try {
            float windowWidth = org.lwjgl.glfw.CallbackBridge.windowWidth;
            float physicalWidth = org.lwjgl.glfw.CallbackBridge.physicalWidth;
            if (windowWidth > 1f && physicalWidth > 1f) {
                float ratio = windowWidth / physicalWidth;
                if (ratio > 0.05f && ratio < 4f && Math.abs(ratio - 1f) > 0.025f) {
                    return clamp(ratio, 0.35f, 2.5f);
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            int percent = LauncherPreferences.getGameResolutionScalePercent(context);
            if (percent > 0) return clamp(percent / 100f, 0.35f, 2.5f);
        } catch (Throwable ignored) {
        }

        return 1f;
    }

    @NonNull
    private float[] addDpadCursorRepeat(float scale) {
        float[] delta = new float[]{0f, 0f};
        addCursorRepeat(delta, GamepadButton.DPAD_LEFT, hatLeft, scale);
        addCursorRepeat(delta, GamepadButton.DPAD_RIGHT, hatRight, scale);
        addCursorRepeat(delta, GamepadButton.DPAD_UP, hatUp, scale);
        addCursorRepeat(delta, GamepadButton.DPAD_DOWN, hatDown, scale);
        return delta;
    }

    private void addCursorRepeat(
            @NonNull float[] delta,
            @NonNull GamepadButton button,
            boolean pressed,
            float scale
    ) {
        if (!pressed) return;
        GamepadAction[] actions = mappingStore.getButtonActions(button, false, activeDevice);
        for (GamepadAction action : actions) {
            if (action == null || !action.isCursorAction()) continue;
            delta[0] += action.getCursorDx() * scale;
            delta[1] += action.getCursorDy() * scale;
        }
    }

    private void updateDirection() {
        if (!isGameMode()) {
            releaseDirection();
            return;
        }

        int newDirection = directionFor(leftX, leftY);
        if (newDirection == currentDirection) return;

        sendDirectional(currentDirection, false);
        currentDirection = newDirection;
        sendDirectional(currentDirection, true);
    }

    private void releaseDirection() {
        sendDirectional(currentDirection, false);
        currentDirection = DIRECTION_NONE;
    }

    private static int directionFor(float x, float y) {
        if (Math.sqrt(x * x + y * y) < DEADZONE) return DIRECTION_NONE;

        double angle = Math.toDegrees(Math.atan2(-y, x));
        if (angle < 0) angle += 360.0;

        return ((int) ((angle + 22.5) / 45.0)) % 8;
    }

    private void sendDirectional(int direction, boolean isDown) {
        switch (direction) {
            case DIRECTION_NORTH:
                setMappedMomentaryKeyHeld(GamepadAction.FORWARD, isDown);
                break;
            case DIRECTION_NORTH_EAST:
                setMappedMomentaryKeyHeld(GamepadAction.FORWARD, isDown);
                setMappedMomentaryKeyHeld(GamepadAction.RIGHT, isDown);
                break;
            case DIRECTION_EAST:
                setMappedMomentaryKeyHeld(GamepadAction.RIGHT, isDown);
                break;
            case DIRECTION_SOUTH_EAST:
                setMappedMomentaryKeyHeld(GamepadAction.RIGHT, isDown);
                setMappedMomentaryKeyHeld(GamepadAction.BACKWARD, isDown);
                break;
            case DIRECTION_SOUTH:
                setMappedMomentaryKeyHeld(GamepadAction.BACKWARD, isDown);
                break;
            case DIRECTION_SOUTH_WEST:
                setMappedMomentaryKeyHeld(GamepadAction.BACKWARD, isDown);
                setMappedMomentaryKeyHeld(GamepadAction.LEFT, isDown);
                break;
            case DIRECTION_WEST:
                setMappedMomentaryKeyHeld(GamepadAction.LEFT, isDown);
                break;
            case DIRECTION_NORTH_WEST:
                setMappedMomentaryKeyHeld(GamepadAction.FORWARD, isDown);
                setMappedMomentaryKeyHeld(GamepadAction.LEFT, isDown);
                break;
            case DIRECTION_NONE:
            default:
                break;
        }
    }

    private void sendMappedButton(
            @NonNull GamepadButton button,
            boolean isDown,
            @Nullable InputDevice device
    ) {
        // Some controllers report D-pad/buttons through both KeyEvent and joystick
        // hat state. Do not let the second DOWN overwrite the gameplay mapping that
        // was selected for the one-shot Minecraft keybind capture.
        if (isDown && activeButtonActions.containsKey(button)) {
            if (InputEventDiagnosticLogger.isEnabled()) {
                InputEventDiagnosticLogger.mark("Ignoring duplicate controller DOWN for " + button);
            }
            return;
        }

        ActiveMappedAction[] mapped;
        boolean keybindCapturePress = false;

        if (isDown) {
            // Only the real Minecraft Controls/Key Binds capture state may use the
            // gameplay mapping while a GUI is open. Inventory clicks, moving stacks,
            // recipe-book clicks, and normal menu buttons must never arm this path.
            keybindCapturePress = MinecraftTextInputKeyboardTrigger.consumeMinecraftKeybindCaptureForControllerPress();
            mapped = resolveMappedActions(button, device, keybindCapturePress);
            activeButtonActions.put(button, mapped);
        } else {
            mapped = activeButtonActions.remove(button);
            if (mapped == null) {
                // Fallback for devices that send an UP without the matching DOWN.
                mapped = resolveMappedActions(button, device, false);
            }
        }

        if (isDown && containsMenuEscape(mapped)) {
            prepareForLikelyMenuCloseFromController(button);
        }

        if (InputEventDiagnosticLogger.isEnabled()) {
            InputEventDiagnosticLogger.mark("Button=" + button + ", down=" + isDown
                    + ", profile=" + mappingStore.profileKeyForDevice(device)
                    + ", keybindCapture=" + keybindCapturePress
                    + ", actions=" + describeMappedActions(mapped)
                    + ", cursor=" + org.lwjgl.glfw.CallbackBridge.mouseX + ","
                    + org.lwjgl.glfw.CallbackBridge.mouseY);
        }

        performMappedActions(mapped, isDown);

        if (!isDown && containsMenuPointerConfirm(mapped)) {
            MinecraftTextInputKeyboardTrigger.onLauncherMenuConfirmReleased(hostView);
        }
    }

    private static boolean containsMenuPointerConfirm(@NonNull ActiveMappedAction[] mapped) {
        for (ActiveMappedAction action : mapped) {
            if (action != null
                    && !action.gameMode
                    && action.action == GamepadAction.MOUSE_LEFT) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    private ActiveMappedAction[] resolveMappedActions(
            @NonNull GamepadButton button,
            @Nullable InputDevice device,
            boolean forceGameMappingForKeybindCapture
    ) {
        boolean gameMode = forceGameMappingForKeybindCapture || isGameModeForButtonMapping();
        String profileKey = mappingStore.profileKeyForDevice(device);
        GamepadAction[] actions = mappingStore.getButtonActions(button, gameMode, profileKey);
        ActiveMappedAction[] mapped = new ActiveMappedAction[actions.length];

        for (int slot = 0; slot < actions.length; slot++) {
            GamepadAction action = actions[slot] == null ? GamepadAction.NONE : actions[slot];

            // Guard against old saved prefs from earlier patches where menu A/R2 were ENTER.
            // Those prefs survive reinstall/rebuild and make it look like A is not mapped to click.
            if (!gameMode && (button == GamepadButton.BUTTON_A
                    || button == GamepadButton.BUTTON_R2
                    || button == GamepadButton.DPAD_CENTER)
                    && action == GamepadAction.ENTER) {
                Logging.i(TAG, "Overriding old menu " + button + " slot " + slot + " ENTER mapping to MOUSE_LEFT");
                action = GamepadAction.MOUSE_LEFT;
            }

            // A held Mouse Left leaks into Minecraft's keybind-capture state: the
            // controller confirm press that opens a keybind row can then immediately
            // become "Left Button" before the user's real mapped key (for example J)
            // arrives. Pulse A / D-pad Center as a complete click instead. Keep R2 as
            // the holdable left-click route so sliders and click-drag still work.
            boolean pulseMenuMouseClick = !gameMode
                    && action == GamepadAction.MOUSE_LEFT
                    && (button == GamepadButton.BUTTON_A
                    || button == GamepadButton.DPAD_CENTER);
            boolean toggleKey = !forceGameMappingForKeybindCapture
                    && action.isToggleableKey()
                    && mappingStore.isButtonActionToggle(button, gameMode, profileKey, slot);
            String toggleBindingId = profileKey + ":" + (gameMode ? "game" : "menu")
                    + ":" + button.name() + ":" + slot + ":" + action.name();
            mapped[slot] = new ActiveMappedAction(
                    action,
                    pulseMenuMouseClick,
                    gameMode,
                    toggleKey,
                    toggleBindingId
            );
        }
        return mapped;
    }

    private static boolean containsMenuEscape(@NonNull ActiveMappedAction[] mapped) {
        for (ActiveMappedAction action : mapped) {
            if (action != null && !action.gameMode && action.action == GamepadAction.ESCAPE) {
                return true;
            }
        }
        return false;
    }


    private void performMappedActions(@NonNull ActiveMappedAction[] mapped, boolean isDown) {
        if (isDown) {
            for (ActiveMappedAction action : mapped) {
                if (action != null && action.action != GamepadAction.NONE) {
                    performMappedAction(action, true);
                }
            }
        } else {
            for (int i = mapped.length - 1; i >= 0; i--) {
                ActiveMappedAction action = mapped[i];
                if (action != null && action.action != GamepadAction.NONE) {
                    performMappedAction(action, false);
                }
            }
        }
    }

    private void performMappedAction(@NonNull ActiveMappedAction mapped, boolean isDown) {
        if (isDown && mapped.gameMode && mappedGameActionListener != null) {
            if (mapped.action == GamepadAction.SCROLL_UP) {
                mappedGameActionListener.onHotbarScroll(-1);
            } else if (mapped.action == GamepadAction.SCROLL_DOWN) {
                mappedGameActionListener.onHotbarScroll(1);
            }
        }

        if (mapped.pulseMenuMouseClick && mapped.action == GamepadAction.MOUSE_LEFT) {
            performPulseMenuMouseClick(isDown);
            return;
        }

        if (mapped.toggleKey && mapped.action.isToggleableKey()) {
            if (isDown) {
                toggleMappedKey(mapped);
            }
            return;
        }

        if (mapped.action.isToggleableKey()) {
            setMappedMomentaryKeyHeld(mapped.action, isDown);
            return;
        }

        mapped.action.perform(isDown, false, hostView);
    }

    private void toggleMappedKey(@NonNull ActiveMappedAction mapped) {
        boolean releasing = latchedToggleBindings.remove(mapped.toggleBindingId);
        if (releasing) {
            changeMappedKeyHoldCount(mapped.action, -1);
            Logging.i(TAG, "Toggle key released: " + mapped.toggleBindingId);
        } else {
            latchedToggleBindings.add(mapped.toggleBindingId);
            changeMappedKeyHoldCount(mapped.action, 1);
            Logging.i(TAG, "Toggle key held: " + mapped.toggleBindingId);
        }
    }

    private void setMappedMomentaryKeyHeld(@NonNull GamepadAction action, boolean down) {
        if (!action.isToggleableKey()) {
            action.perform(down, false, hostView);
            return;
        }
        changeMappedKeyHoldCount(action, down ? 1 : -1);
    }

    private void changeMappedKeyHoldCount(@NonNull GamepadAction action, int delta) {
        action = action.keyIdentity();
        int previous = mappedKeyHoldCounts.containsKey(action) ? mappedKeyHoldCounts.get(action) : 0;
        int next = Math.max(0, previous + delta);

        if (next == 0) {
            mappedKeyHoldCounts.remove(action);
        } else {
            mappedKeyHoldCounts.put(action, next);
        }

        boolean desiredDown = next > 0;
        boolean deliveredDown = Boolean.TRUE.equals(deliveredMappedKeyStates.get(action));
        if (desiredDown == deliveredDown) return;

        action.perform(desiredDown, false, hostView);
        if (desiredDown) {
            deliveredMappedKeyStates.put(action, true);
        } else {
            deliveredMappedKeyStates.remove(action);
        }
    }

    private void releaseAllToggleKeys(@NonNull String reason) {
        if (latchedToggleBindings.isEmpty()) return;

        latchedToggleBindings.clear();
        mappedKeyHoldCounts.clear();
        if (!deliveredMappedKeyStates.isEmpty()) {
            for (GamepadAction action : new HashSet<>(deliveredMappedKeyStates.keySet())) {
                action.perform(false, false, hostView);
            }
            deliveredMappedKeyStates.clear();
        }
        Logging.i(TAG, "Released all toggled gamepad keys: " + reason);
    }

    /**
     * Starts the same short click previously used for A / D-pad center, but keeps
     * enough state to convert it into a held drag when the cursor moves.
     */
    private void performPulseMenuMouseClick(boolean isDown) {
        if (isDown) {
            heldPulseMenuClickCount++;
            if (heldPulseMenuClickCount > 1) return;

            pulseMenuDragActive = false;
            long generation = ++pulseMenuClickGeneration;
            setPulseMenuMouseDown(true);

            hostView.postDelayed(() -> {
                if (removed || generation != pulseMenuClickGeneration) return;
                if (pulseMenuDragActive || heldPulseMenuClickCount <= 0) return;

                // Preserve the historical one-shot menu click when the user holds
                // A without moving the cursor. This is the keybind-capture guard.
                setPulseMenuMouseDown(false);
            }, MENU_CLICK_PULSE_MILLIS);
            return;
        }

        if (heldPulseMenuClickCount > 0) {
            heldPulseMenuClickCount--;
        }
        if (heldPulseMenuClickCount == 0) {
            releasePulseMenuMouse("controller click released");
        }
    }

    private void promotePulseMenuClickToDrag() {
        if (heldPulseMenuClickCount <= 0 || pulseMenuDragActive || isGameMode()) return;

        pulseMenuDragActive = true;
        // Cancel the delayed pulse release. If the short pulse already completed,
        // press Left Button again at the current slider position before movement.
        pulseMenuClickGeneration++;
        setPulseMenuMouseDown(true);
        Logging.i(TAG, "Promoted controller menu click to held slider drag at cursor="
                + org.lwjgl.glfw.CallbackBridge.mouseX + ","
                + org.lwjgl.glfw.CallbackBridge.mouseY);
    }

    private void releasePulseMenuMouse(@NonNull String reason) {
        heldPulseMenuClickCount = 0;
        pulseMenuClickGeneration++;
        pulseMenuDragActive = false;
        if (pulseMenuMouseDown) {
            setPulseMenuMouseDown(false);
            Logging.i(TAG, "Released controller menu drag: " + reason);
        }
    }

    private void setPulseMenuMouseDown(boolean down) {
        if (pulseMenuMouseDown == down) return;
        pulseMenuMouseDown = down;
        try {
            org.lwjgl.glfw.CallbackBridge.setInputReady(true);
            org.lwjgl.glfw.CallbackBridge.sendCursorPos(
                    org.lwjgl.glfw.CallbackBridge.mouseX,
                    org.lwjgl.glfw.CallbackBridge.mouseY
            );
            org.lwjgl.glfw.CallbackBridge.sendMouseButton(
                    ca.dnamobile.droidbridgelauncher.runtime.LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT,
                    down
            );
        } catch (Throwable throwable) {
            pulseMenuMouseDown = false;
            Logging.e(TAG, "Unable to route controller menu slider drag", throwable);
        }
    }

    @NonNull
    private static String describeMappedActions(@NonNull ActiveMappedAction[] mapped) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < mapped.length; i++) {
            ActiveMappedAction action = mapped[i];
            if (action == null || action.action == GamepadAction.NONE) continue;
            if (builder.length() > 0) builder.append(" + ");
            builder.append(i).append(":").append(action.action.name());
            if (action.toggleKey) builder.append("[toggle]");
        }
        return builder.length() == 0 ? "NONE" : builder.toString();
    }

    private void releaseAllMappedButtons() {
        if (!activeButtonActions.isEmpty()) {
            for (ActiveMappedAction[] mapped : activeButtonActions.values()) {
                if (mapped != null) {
                    performMappedActions(mapped, false);
                }
            }
            activeButtonActions.clear();
        }
        releasePulseMenuMouse("all mapped buttons released");
    }

    private void updateHatButton(
            @NonNull GamepadButton button,
            boolean isDown,
            @NonNull InputDevice device
    ) {
        switch (button) {
            case DPAD_UP:
                if (hatUp == isDown) return;
                hatUp = isDown;
                sendMappedButton(button, isDown, device);
                break;
            case DPAD_DOWN:
                if (hatDown == isDown) return;
                hatDown = isDown;
                sendMappedButton(button, isDown, device);
                break;
            case DPAD_LEFT:
                if (hatLeft == isDown) return;
                hatLeft = isDown;
                sendMappedButton(button, isDown, device);
                break;
            case DPAD_RIGHT:
                if (hatRight == isDown) return;
                hatRight = isDown;
                sendMappedButton(button, isDown, device);
                break;
            default:
                break;
        }
    }

    private void updateTrigger(boolean left, boolean isDown, @NonNull InputDevice device) {
        if (left) {
            if (leftTriggerDown == isDown) return;
            leftTriggerDown = isDown;
            sendMappedButton(GamepadButton.BUTTON_L2, isDown, device);
        } else {
            if (rightTriggerDown == isDown) return;
            rightTriggerDown = isDown;
            sendMappedButton(GamepadButton.BUTTON_R2, isDown, device);
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class ActiveMappedAction {
        @NonNull final GamepadAction action;
        final boolean pulseMenuMouseClick;
        final boolean gameMode;
        final boolean toggleKey;
        @NonNull final String toggleBindingId;

        ActiveMappedAction(
                @NonNull GamepadAction action,
                boolean pulseMenuMouseClick,
                boolean gameMode,
                boolean toggleKey,
                @NonNull String toggleBindingId
        ) {
            this.action = action;
            this.pulseMenuMouseClick = pulseMenuMouseClick;
            this.gameMode = gameMode;
            this.toggleKey = toggleKey;
            this.toggleBindingId = toggleBindingId;
        }
    }

    private static final class BtaNativeControllerBridge {
        private static volatile boolean initialized;
        private static volatile boolean loggedMotion;
        private static volatile boolean loggedButton;
        private static volatile boolean loggedMenuScroll;
        private static volatile boolean menuCursorBaselineSeeded;
        private static volatile float latestMenuLeftStickX;
        private static volatile float latestMenuLeftStickY;
        private static volatile float latestMenuRightStickY;
        private static volatile long nativeMenuButtonCursorPinUntilNanos;
        private static volatile float nativeMenuButtonCursorPinX;
        private static volatile float nativeMenuButtonCursorPinY;
        private static float menuScrollAccumulator;

        // Long enough to straddle BTA's next one or two game-thread controller polls,
        // but short enough that this never behaves like a persistent cursor lock.
        private static final long NATIVE_MENU_BUTTON_CURSOR_PIN_NANOS = 350_000_000L;

        private BtaNativeControllerBridge() {
        }

        static void initializeFromAndroidDevices(@NonNull Context context) {
            if (initialized) return;
            initialized = true;
            try {
                int selectedId = -1;
                String selectedName = null;
                String selectedDescriptor = null;
                int count = 0;

                int selectedScore = -1;
                for (int id : InputDevice.getDeviceIds()) {
                    InputDevice device = InputDevice.getDevice(id);
                    if (!isAndroidGameController(device)) continue;

                    int score = scoreAndroidGameController(device);
                    count++;
                    appendBta("BTA controller bridge: Android controller id=" + id
                            + " name=" + device.getName()
                            + " descriptor=" + device.getDescriptor()
                            + " sources=0x" + Integer.toHexString(device.getSources())
                            + " score=" + score);

                    if (score > selectedScore) {
                        selectedScore = score;
                        selectedId = id;
                        selectedName = device.getName();
                        selectedDescriptor = device.getDescriptor();
                    }
                }

                if (selectedId >= 0) {
                    org.lwjgl.glfw.CallbackBridge.setBtaGamepadIdentity(
                            selectedId,
                            selectedName,
                            selectedDescriptor
                    );
                    org.lwjgl.glfw.CallbackBridge.sendBtaGamepadMotion(
                            selectedId,
                            selectedName,
                            selectedDescriptor,
                            0f, 0f,
                            0f, 0f,
                            0f, 0f,
                            false, false, false, false
                    );
                    appendBta("BTA controller bridge: initialized native gamepad from Android device count="
                            + count + " selected=" + selectedName);
                } else {
                    // Keep BTA's controller UI visible even if Android does not expose the device
                    // until the first motion/key event. The first real controller event replaces this.
                    org.lwjgl.glfw.CallbackBridge.setBtaGamepadIdentity(
                            -1,
                            "DroidBridge Android Controller",
                            "droidbridge-bta-controller"
                    );
                    appendBta("BTA controller bridge: no Android controller devices found at launch; using visible fallback until first controller event");
                }
            } catch (Throwable throwable) {
                appendBta("BTA controller bridge: Android device initialization failed: "
                        + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }
        }

        static void updateLatestMenuLeftStick(float leftX, float leftY) {
            latestMenuLeftStickX = leftX;
            latestMenuLeftStickY = leftY;
        }

        static float latestMenuLeftStickX() {
            return latestMenuLeftStickX;
        }

        static float latestMenuLeftStickY() {
            return latestMenuLeftStickY;
        }

        static void updateLatestMenuRightStickY(float rightY) {
            latestMenuRightStickY = rightY;
            if (!loggedBtaRightStickMenuSample && Math.abs(rightY) > DEADZONE) {
                loggedBtaRightStickMenuSample = true;
                appendBta("BTA controller bridge: right-stick Y sample for menu scroll=" + rightY);
            }
        }

        static void armNativeMenuButtonCursorPin() {
            try {
                if (!btaNativeControllerOwnsLauncherInput
                        || org.lwjgl.glfw.CallbackBridge.isGrabbing()) {
                    return;
                }

                float x = org.lwjgl.glfw.CallbackBridge.mouseX;
                float y = org.lwjgl.glfw.CallbackBridge.mouseY;
                int width = org.lwjgl.glfw.CallbackBridge.windowWidth > 1
                        ? org.lwjgl.glfw.CallbackBridge.windowWidth
                        : org.lwjgl.glfw.CallbackBridge.physicalWidth;
                int height = org.lwjgl.glfw.CallbackBridge.windowHeight > 1
                        ? org.lwjgl.glfw.CallbackBridge.windowHeight
                        : org.lwjgl.glfw.CallbackBridge.physicalHeight;
                if (width <= 1 || height <= 1) return;

                if (Float.isNaN(x) || Float.isInfinite(x) || x < 0f || x >= width) {
                    x = (width - 1f) * 0.5f;
                }
                if (Float.isNaN(y) || Float.isInfinite(y) || y < 0f || y >= height) {
                    y = (height - 1f) * 0.5f;
                }

                nativeMenuButtonCursorPinX = x;
                nativeMenuButtonCursorPinY = y;
                nativeMenuButtonCursorPinUntilNanos = System.nanoTime()
                        + NATIVE_MENU_BUTTON_CURSOR_PIN_NANOS;

                // Synchronize every cursor cache immediately as well. The previous
                // LWJGL2 native bridge fix makes this update BTA's Mouse state without
                // generating a fake mouse event or stealing controller input type.
                org.lwjgl.glfw.CallbackBridge.setCursorPosSilently(x, y);

                if (!loggedBtaNativeMenuButtonCursorPin) {
                    loggedBtaNativeMenuButtonCursorPin = true;
                    appendBta("BTA controller bridge: native menu buttons preserved; cursor pin armed across CONTROLLER mode switch");
                }
            } catch (Throwable throwable) {
                Logging.e(TAG, "BTA native menu-button cursor pin arm failed", throwable);
            }
        }

        static void restoreNativeMenuButtonCursorPinIfActive() {
            long until = nativeMenuButtonCursorPinUntilNanos;
            if (until == 0L) return;
            if (org.lwjgl.glfw.CallbackBridge.isGrabbing()) {
                nativeMenuButtonCursorPinUntilNanos = 0L;
                return;
            }
            if (System.nanoTime() >= until) {
                nativeMenuButtonCursorPinUntilNanos = 0L;
                return;
            }

            try {
                org.lwjgl.glfw.CallbackBridge.setCursorPosSilently(
                        nativeMenuButtonCursorPinX,
                        nativeMenuButtonCursorPinY
                );
            } catch (Throwable throwable) {
                Logging.e(TAG, "BTA native menu-button cursor pin restore failed", throwable);
                nativeMenuButtonCursorPinUntilNanos = 0L;
            }
        }

        static void refreshNativeMenuButtonCursorPinFromCurrent() {
            long until = nativeMenuButtonCursorPinUntilNanos;
            if (until == 0L || System.nanoTime() >= until) return;
            if (org.lwjgl.glfw.CallbackBridge.isGrabbing()) {
                nativeMenuButtonCursorPinUntilNanos = 0L;
                return;
            }
            nativeMenuButtonCursorPinX = org.lwjgl.glfw.CallbackBridge.mouseX;
            nativeMenuButtonCursorPinY = org.lwjgl.glfw.CallbackBridge.mouseY;
        }

        static void resetMenuScroll() {
            menuScrollAccumulator = 0f;
        }

        /**
         * BTA centers its visible controller cursor when the menu opens, but that
         * visual center does not necessarily update DroidBridge's GLFW cursor cache.
         * If the cache is still at its startup default (0,0), BTA's first controller
         * motion can use that stale value as its virtual-mouse baseline and visibly
         * snap the cursor to the top-left corner.
         *
         * Seed the Java/native GLFW caches silently before the first intentional BTA
         * controller action. Do not emit a CursorPos callback: BTA already drew the
         * cursor in the center and only needs glfwGetCursorPos() to agree with it.
         */
        private static void seedMenuCursorBaselineIfNeeded(boolean intentionalControllerActivity) {
            if (!btaNativeControllerOwnsLauncherInput
                    || menuCursorBaselineSeeded
                    || !intentionalControllerActivity) {
                return;
            }

            try {
                if (org.lwjgl.glfw.CallbackBridge.isGrabbing()) {
                    // Never recenter while BTA owns the cursor for in-game camera look.
                    return;
                }

                int width = org.lwjgl.glfw.CallbackBridge.windowWidth > 1
                        ? org.lwjgl.glfw.CallbackBridge.windowWidth
                        : org.lwjgl.glfw.CallbackBridge.physicalWidth;
                int height = org.lwjgl.glfw.CallbackBridge.windowHeight > 1
                        ? org.lwjgl.glfw.CallbackBridge.windowHeight
                        : org.lwjgl.glfw.CallbackBridge.physicalHeight;
                if (width <= 1 || height <= 1) return;

                float x = org.lwjgl.glfw.CallbackBridge.mouseX;
                float y = org.lwjgl.glfw.CallbackBridge.mouseY;
                boolean invalid = Float.isNaN(x) || Float.isInfinite(x)
                        || Float.isNaN(y) || Float.isInfinite(y)
                        || x < 0f || y < 0f || x >= width || y >= height;
                boolean startupOrigin = Math.abs(x) < 0.001f && Math.abs(y) < 0.001f;

                float baselineX = (invalid || startupOrigin) ? (width - 1f) * 0.5f : x;
                float baselineY = (invalid || startupOrigin) ? (height - 1f) * 0.5f : y;

                // Always write the Java cursor position back into the native GLFW
                // cache once. GameActivity can already have centered the software
                // cursor while BTA's first glfwGetCursorPos() still sees the native
                // startup origin. This silent sync makes both sides agree before BTA
                // consumes the first controller event.
                org.lwjgl.glfw.CallbackBridge.setCursorPosSilently(baselineX, baselineY);
                appendBta("BTA controller bridge: synchronized first menu cursor baseline="
                        + baselineX + "," + baselineY + " size=" + width + "x" + height
                        + (invalid || startupOrigin ? " source=center" : " source=existing"));

                menuCursorBaselineSeeded = true;
            } catch (Throwable throwable) {
                Logging.e(TAG, "BTA menu cursor baseline seed failed", throwable);
            }
        }

        static void tickMenuScroll(float deltaScale) {
            float rightY = latestMenuRightStickY;
            if (Math.abs(rightY) <= DEADZONE) {
                menuScrollAccumulator = 0f;
                return;
            }

            float strength = Math.min(1f, Math.abs(rightY));
            float acceleration = Math.max(0.35f, strength * strength);

            // Android right-stick up is negative Y. GLFW/Minecraft mouse-wheel up is
            // positive Y, so invert the joystick axis before sending the scroll event.
            menuScrollAccumulator += -rightY * acceleration * BTA_MENU_SCROLL_SENSITIVITY * deltaScale;

            if (Math.abs(menuScrollAccumulator) < 1f) return;

            int scrollSteps = (int) menuScrollAccumulator;
            menuScrollAccumulator -= scrollSteps;

            try {
                org.lwjgl.glfw.CallbackBridge.setInputReady(true);
                org.lwjgl.glfw.CallbackBridge.sendScroll(0d, scrollSteps);
                if (!loggedMenuScroll) {
                    loggedMenuScroll = true;
                    appendBta("BTA controller bridge: right-stick menu scroll routed as GLFW scroll");
                }
            } catch (Throwable throwable) {
                Logging.e(TAG, "BTA right-stick menu scroll failed", throwable);
            }
        }

        static boolean updateMotion(
                @Nullable InputDevice device,
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
                seedMenuCursorBaselineIfNeeded(
                        Math.abs(leftX) > DEADZONE
                                || Math.abs(leftY) > DEADZONE
                                || Math.abs(rightX) > DEADZONE
                                || Math.abs(rightY) > DEADZONE
                                || hatUp || hatRight || hatDown || hatLeft
                );

                if (device != null) {
                    org.lwjgl.glfw.CallbackBridge.setBtaGamepadIdentity(
                            device.getId(),
                            device.getName(),
                            device.getDescriptor()
                    );
                }
                boolean launcherOwnsBtaMenuCursor = btaNativeControllerOwnsLauncherInput
                        && !org.lwjgl.glfw.CallbackBridge.isGrabbing();

                org.lwjgl.glfw.CallbackBridge.sendBtaGamepadMotion(
                        device != null ? device.getId() : -1,
                        device != null ? device.getName() : "DroidBridge Android Controller",
                        device != null ? device.getDescriptor() : "droidbridge-bta-controller",
                        launcherOwnsBtaMenuCursor ? 0f : leftX,
                        launcherOwnsBtaMenuCursor ? 0f : leftY,
                        launcherOwnsBtaMenuCursor ? 0f : rightX,
                        launcherOwnsBtaMenuCursor ? 0f : rightY,
                        leftTrigger,
                        rightTrigger,
                        hatUp,
                        hatRight,
                        hatDown,
                        hatLeft
                );
                if (!loggedMotion) {
                    loggedMotion = true;
                    appendBta("BTA controller bridge: first Android motion event delivered to native gamepad state"
                            + (device != null ? " device=" + device.getName() : ""));
                }
                return true;
            } catch (Throwable throwable) {
                Logging.e(TAG, "BTA native controller bridge motion update failed", throwable);
                return false;
            }
        }

        static boolean updateButton(@Nullable InputDevice device, int androidKeyCode, boolean down) {
            try {
                seedMenuCursorBaselineIfNeeded(down);

                if (device != null) {
                    org.lwjgl.glfw.CallbackBridge.setBtaGamepadIdentity(
                            device.getId(),
                            device.getName(),
                            device.getDescriptor()
                    );
                }
                org.lwjgl.glfw.CallbackBridge.sendBtaGamepadButton(
                        device != null ? device.getId() : -1,
                        device != null ? device.getName() : "DroidBridge Android Controller",
                        device != null ? device.getDescriptor() : "droidbridge-bta-controller",
                        androidKeyCode,
                        down
                );
                if (!loggedButton) {
                    loggedButton = true;
                    appendBta("BTA controller bridge: first Android button event delivered to native gamepad state key="
                            + androidKeyCode + " down=" + down
                            + (device != null ? " device=" + device.getName() : ""));
                }
                return true;
            } catch (Throwable throwable) {
                Logging.e(TAG, "BTA native controller bridge button update failed", throwable);
                return false;
            }
        }

        private static boolean isAndroidGameController(@Nullable InputDevice device) {
            if (device == null) return false;
            int sources = device.getSources();
            if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) return true;
            if ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return true;
            if ((sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD) return true;
            if (isPointerOnlyDevice(device, sources)) return false;

            String name = device.getName();
            if (name == null) return false;
            String lower = name.toLowerCase(java.util.Locale.US);
            return lower.contains("controller")
                    || lower.contains("gamepad")
                    || lower.contains("joystick")
                    || lower.contains("xbox")
                    || lower.contains("playstation")
                    || lower.contains("dualsense")
                    || lower.contains("dualshock")
                    || lower.contains("odin")
                    || lower.contains("retroid")
                    || lower.contains("anbernic")
                    || lower.contains("8bitdo")
                    || lower.contains("gamesir")
                    || lower.contains("razer");
        }

        private static int scoreAndroidGameController(@NonNull InputDevice device) {
            int sources = device.getSources();
            if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                return 3;
            }
            if ((sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD) {
                return 2;
            }
            return 1;
        }

        private static boolean isPointerOnlyDevice(@NonNull InputDevice device, int sources) {
            boolean controllerSource = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                    || (sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
            if (controllerSource) return false;

            boolean pointerSource = (sources & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                    || (sources & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
                    || (sources & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD
                    || (sources & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
                    || (sources & InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS;
            if (!pointerSource) return false;

            String name = device.getName();
            String lower = name != null ? name.toLowerCase(java.util.Locale.US) : "";
            return lower.contains("mouse")
                    || lower.contains("virtual mouse")
                    || lower.contains("touchpad")
                    || lower.contains("pointer")
                    || lower.contains("cursor");
        }

        private static void appendBta(@NonNull String message) {
            // Controllable reuses the BTA virtual-gamepad state internally. Those
            // per-device/first-event messages are implementation details, not BTA
            // diagnostics, and previously flooded every Controllable latestlog.
            if (!ControllerModCompat.isControllableActive()
                    || InputEventDiagnosticLogger.isEnabled()
                    || message.toLowerCase(java.util.Locale.ROOT).contains("failed")) {
                try {
                    Logger.appendToLog(message);
                } catch (Throwable ignored) {
                }
            }
            Logging.i(TAG, message);
        }
    }

}

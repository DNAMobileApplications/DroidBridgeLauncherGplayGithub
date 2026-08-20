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

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.controls.ControlsPreferences;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.settings.GameResolutionSettings;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;

/**
 * Converts the Android device gyroscope into Minecraft camera movement.
 *
 * The gyroscope is intentionally routed through the same GLFW cursor bridge used
 * by touch/controller camera look. It only emits movement while Minecraft has
 * grabbed the cursor, so launcher/game menus remain stable.
 */
public final class GyroInputController implements SensorEventListener {
    private static final String TAG = "GyroInput";
    private static final float BASE_CURSOR_UNITS_PER_RADIAN = 380f;
    private static final float RATE_DEAD_ZONE_RADIANS_PER_SECOND = 0.018f;
    private static final float MAX_EVENT_DELTA_SECONDS = 0.050f;

    @NonNull
    private final Activity activity;
    @Nullable
    private final SensorManager sensorManager;
    @Nullable
    private final Sensor gyroscope;

    private boolean registered;
    private long lastTimestampNanos;

    public GyroInputController(@NonNull Activity activity) {
        this.activity = activity;
        this.sensorManager = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
        this.gyroscope = sensorManager == null
                ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
    }

    public static boolean isGyroscopeAvailable(@NonNull Context context) {
        try {
            SensorManager manager =
                    (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            return manager != null
                    && manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void refreshFromPreferences() {
        if (ControlsPreferences.isGyroscopeCameraEnabled(activity)) {
            start();
        } else {
            stop();
        }
    }

    public void start() {
        if (registered) return;
        if (sensorManager == null || gyroscope == null) {
            Logging.i(TAG, "Gyroscope camera requested, but this device has no gyroscope sensor.");
            return;
        }

        lastTimestampNanos = 0L;
        try {
            registered = sensorManager.registerListener(
                    this,
                    gyroscope,
                    SensorManager.SENSOR_DELAY_GAME
            );
            Logging.i(TAG, registered
                    ? "Gyroscope camera enabled."
                    : "Gyroscope listener registration failed.");
        } catch (Throwable throwable) {
            registered = false;
            Logging.e(TAG, "Unable to enable gyroscope camera.", throwable);
        }
    }

    public void stop() {
        lastTimestampNanos = 0L;
        if (!registered) return;

        try {
            sensorManager.unregisterListener(this);
        } catch (Throwable ignored) {
        }
        registered = false;
        Logging.i(TAG, "Gyroscope camera disabled.");
    }

    @Override
    public void onSensorChanged(@NonNull SensorEvent event) {
        if (event.sensor == null
                || event.sensor.getType() != Sensor.TYPE_GYROSCOPE
                || event.values == null
                || event.values.length < 2) {
            return;
        }

        long timestampNanos = event.timestamp;
        long previousTimestampNanos = lastTimestampNanos;
        lastTimestampNanos = timestampNanos;

        if (previousTimestampNanos <= 0L || timestampNanos <= previousTimestampNanos) {
            return;
        }

        float dt = (timestampNanos - previousTimestampNanos) / 1_000_000_000f;
        if (dt <= 0f || dt > MAX_EVENT_DELTA_SECONDS) {
            return;
        }

        try {
            if (!activity.hasWindowFocus() || !org.lwjgl.glfw.CallbackBridge.isGrabbing()) {
                return;
            }
        } catch (Throwable ignored) {
            return;
        }

        float deviceRateX = applyDeadZone(event.values[0]);
        float deviceRateY = applyDeadZone(event.values[1]);

        float screenRateX;
        float screenRateY;
        switch (getDisplayRotation()) {
            case Surface.ROTATION_90:
                screenRateX = deviceRateY;
                screenRateY = -deviceRateX;
                break;
            case Surface.ROTATION_180:
                screenRateX = -deviceRateX;
                screenRateY = -deviceRateY;
                break;
            case Surface.ROTATION_270:
                screenRateX = -deviceRateY;
                screenRateY = deviceRateX;
                break;
            case Surface.ROTATION_0:
            default:
                screenRateX = deviceRateX;
                screenRateY = deviceRateY;
                break;
        }

        if (screenRateX == 0f && screenRateY == 0f) return;

        float sensitivity = GamepadMappingStore.get(activity)
                .getGameCameraSensitivityMultiplier();
        float coordinateScale = cameraSurfaceCoordinateScale();
        float cursorUnitsPerRadian =
                BASE_CURSOR_UNITS_PER_RADIAN * sensitivity * coordinateScale;

        float deltaX = screenRateY * dt * cursorUnitsPerRadian;
        float deltaY = screenRateX * dt * cursorUnitsPerRadian;

        try {
            org.lwjgl.glfw.CallbackBridge.setInputReady(true);
            org.lwjgl.glfw.CallbackBridge.mouseX += deltaX;
            org.lwjgl.glfw.CallbackBridge.mouseY += deltaY;
            org.lwjgl.glfw.CallbackBridge.sendCursorPos(
                    org.lwjgl.glfw.CallbackBridge.mouseX,
                    org.lwjgl.glfw.CallbackBridge.mouseY
            );
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to deliver gyroscope camera motion.", throwable);
            stop();
        }
    }

    @Override
    public void onAccuracyChanged(@NonNull Sensor sensor, int accuracy) {
    }

    private int getDisplayRotation() {
        try {
            return activity.getWindowManager().getDefaultDisplay().getRotation();
        } catch (Throwable ignored) {
            return Surface.ROTATION_0;
        }
    }

    private float cameraSurfaceCoordinateScale() {
        try {
            float windowWidth = org.lwjgl.glfw.CallbackBridge.windowWidth;
            float windowHeight = org.lwjgl.glfw.CallbackBridge.windowHeight;
            float physicalWidth = org.lwjgl.glfw.CallbackBridge.physicalWidth;
            float physicalHeight = org.lwjgl.glfw.CallbackBridge.physicalHeight;
            if (windowWidth <= 1f
                    || windowHeight <= 1f
                    || physicalWidth <= 1f
                    || physicalHeight <= 1f) {
                return 1f;
            }

            float actualX = windowWidth / physicalWidth;
            float actualY = windowHeight / physicalHeight;
            if (actualX <= 0.05f
                    || actualX >= 4f
                    || actualY <= 0.05f
                    || actualY >= 4f) {
                return 1f;
            }

            int percent = LauncherPreferences.getGameResolutionScalePercent(activity);
            GameResolutionSettings.ResolvedResolution expectedRender =
                    GameResolutionSettings.resolveRenderResolution(
                            activity,
                            Math.max(1, Math.round(physicalWidth)),
                            Math.max(1, Math.round(physicalHeight)),
                            percent
                    );
            float expectedX = expectedRender.width / Math.max(1f, physicalWidth);
            float expectedY = expectedRender.height / Math.max(1f, physicalHeight);
            float expected = (float) Math.sqrt(expectedX * expectedY);
            float actual = (float) Math.sqrt(actualX * actualY);
            float unexpectedScale = actual / Math.max(0.01f, expected);

            if (Math.abs(unexpectedScale - 1f) < 0.06f) return 1f;
            return clamp(1f / unexpectedScale, 0.50f, 2.0f);
        } catch (Throwable ignored) {
            return 1f;
        }
    }

    private static float applyDeadZone(float value) {
        if (Math.abs(value) <= RATE_DEAD_ZONE_RADIANS_PER_SECOND) return 0f;

        return value > 0f
                ? value - RATE_DEAD_ZONE_RADIANS_PER_SECOND
                : value + RATE_DEAD_ZONE_RADIANS_PER_SECOND;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}

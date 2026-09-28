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

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.InputStream;

import org.lwjgl.glfw.CallbackBridge;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.dualscreen.DualScreenSwapActionBus;

/** A single touch control button. */
@SuppressLint("ViewConstructor")
final class TouchControlButtonView extends TextView {
    interface Listener {
        void onChanged();
        void onMoveStarted(@NonNull TouchControlButtonView view, @NonNull TouchControlData data);
        void onMoveRequested(
                @NonNull TouchControlButtonView view,
                @NonNull TouchControlData data,
                float proposedX,
                float proposedY
        );
        void onResizeStarted(@NonNull TouchControlButtonView view, @NonNull TouchControlData data);
        void onResizeRequested(
                @NonNull TouchControlButtonView view,
                @NonNull TouchControlData data,
                float proposedScreenWidth,
                float proposedScreenHeight
        );
        void onEditRequested(@NonNull TouchControlButtonView view, @NonNull TouchControlData data);
        void onMenuRequested();
        void onToggleControlsRequested();
        void onVirtualMouseToggleRequested();
        void onKeySenderKeyboardRequested();
        void onDrawerToggleRequested(@NonNull TouchControlButtonView view, @NonNull TouchControlData data);
    }

    private static final String TAG = "TouchButton";

    private static final int GLFW_KEY_W = 87;
    private static final int GLFW_KEY_A = 65;
    private static final int GLFW_KEY_S = 83;
    private static final int GLFW_KEY_D = 68;
    private static final int GLFW_KEY_T = 84;
    private static final int GLFW_KEY_SLASH = 47;

    private static final int GLFW_MOUSE_BUTTON_LEFT = 0;
    private static final int GLFW_MOUSE_BUTTON_RIGHT = 1;
    private static final int GLFW_MOUSE_BUTTON_MIDDLE = 2;

    private static final float GAME_PRESS_FEEDBACK_ALPHA = 0.60f;

    private final TouchControlData data;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final int touchSlop;
    private final int editTapSlop;

    private final Paint joystickBasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint joystickStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint joystickKnobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint joystickGuidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint resizeHandlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint resizeHandleStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path imageClipPath = new Path();
    @Nullable private Bitmap controlImageBitmap;
    @Nullable private String loadedImageUri;

    private boolean editMode;
    private boolean editSelected;
    private float responsiveVisualScale;
    private boolean pressedState;
    private boolean editLongPressTriggered;
    private boolean editDragging;
    private boolean editResizing;
    private boolean touchFeedbackActive;
    private float touchOffsetX;
    private float touchOffsetY;
    private float downRawX;
    private float downRawY;
    private float resizeStartRawX;
    private float resizeStartRawY;
    private float resizeStartWidth;
    private float resizeStartHeight;
    private Runnable editLongPressRunnable;

    private static final long JOYSTICK_FORWARD_DOUBLE_TAP_MS = 350L;

    private boolean joystickForwardLocked;
    private boolean joystickForwardGestureHandled;
    private long joystickLastForwardPressUptimeMs;
    private boolean joystickWDown;
    private boolean joystickADown;
    private boolean joystickSDown;
    private boolean joystickDDown;
    private float joystickCenterX;
    private float joystickCenterY;
    private float joystickKnobX;
    private float joystickKnobY;

    TouchControlButtonView(@NonNull Context context, @NonNull TouchControlData data, @NonNull Listener listener) {
        super(context);
        this.data = data;
        this.listener = listener;
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        this.editTapSlop = Math.max(this.touchSlop * 2, Math.round(18f * context.getResources().getDisplayMetrics().density));
        setGravity(Gravity.CENTER);
        setTextColor(Color.WHITE);
        setTextSize(13f);
        setIncludeFontPadding(false);
        setSingleLine(false);
        setAllCaps(false);
        updateDisplayedLabel();
        setBackground(makeBackground(false));
        setAlpha(resolvedDisplayAlpha());
        setLongClickable(true);
        setWillNotDraw(false);
        setupJoystickPaints();
        setupResizeHandlePaints();
        resetJoystickKnob();
    }

    void setEditMode(boolean editMode) {
        this.editMode = editMode;
        if (!editMode) editSelected = false;
        refreshVisualState();
    }

    void setEditSelected(boolean selected) {
        if (editSelected == selected) return;
        editSelected = selected;
        refreshVisualState();
    }

    boolean isEditSelected() {
        return editSelected;
    }

    void refreshVisualState() {
        updateDisplayedLabel();
        if (!sameNullableString(loadedImageUri, data.imageUri)) {
            releaseControlImage();
        }
        setupJoystickPaints();
        setBackground(makeBackground(editMode));
        updateInteractionAlpha();
        invalidate();
    }

    private void updateDisplayedLabel() {
        boolean imageOnly = hasControlImageConfigured()
                && TouchControlData.IMAGE_MODE_REPLACE.equals(TouchControlData.normalizeImageMode(data.imageMode));
        setText(imageOnly ? "" : (data.label == null ? "" : data.label));
    }

    @NonNull
    TouchControlData getData() {
        return data;
    }

    /**
     * Responsive native profiles store appearance values in their reference
     * canvas pixels. A zero value keeps the legacy dp styling used by imported
     * Pojav-family profiles and older non-responsive layouts.
     */
    void setResponsiveVisualScale(float scale) {
        float safeScale = scale > 0f && !Float.isNaN(scale) && !Float.isInfinite(scale)
                ? scale
                : 0f;
        if (Math.abs(responsiveVisualScale - safeScale) < 0.0001f) {
            updateResponsiveTextSize();
            return;
        }
        responsiveVisualScale = safeScale;
        setupJoystickPaints();
        setupResizeHandlePaints();
        updateResponsiveTextSize();
        setBackground(makeBackground(editMode));
        invalidate();
    }

    private float visualUnitScale() {
        if (responsiveVisualScale > 0f) return responsiveVisualScale;
        return Math.max(0.1f, getResources().getDisplayMetrics().density);
    }

    private void updateResponsiveTextSize() {
        if (responsiveVisualScale <= 0f) {
            setTextSize(13f);
            return;
        }

        float density = Math.max(0.1f, getResources().getDisplayMetrics().density);
        float shortest = Math.max(1f, Math.min(getWidth(), getHeight()));
        if (shortest <= 1f) return;

        float targetPx = shortest * 0.24f;
        float minPx = 10f * density;
        float maxPx = 28f * density;
        setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                Math.max(minPx, Math.min(maxPx, targetPx))
        );
    }

    private float resolvedDisplayAlpha() {
        float localOpacity = Math.max(0f, Math.min(1f, data.opacity));
        float globalOpacity = Math.max(0f, Math.min(1f, ControlsPreferences.getGlobalOpacity(getContext())));
        float alpha = localOpacity * globalOpacity;
        return editMode ? Math.max(0.25f, alpha) : alpha;
    }

    private void updateInteractionAlpha() {
        float displayAlpha = resolvedDisplayAlpha();
        if (!editMode && touchFeedbackActive) {
            // Press feedback must make the control visibly dimmer than its normal
            // state. Using max(displayAlpha, feedbackAlpha) made an opaque control
            // remain at 100%, so presses were invisible. Multiply the resolved
            // local/global opacity instead so every visible control changes while
            // held, while a deliberately hidden control remains hidden.
            setAlpha(displayAlpha <= 0.001f
                    ? 0f
                    : displayAlpha * GAME_PRESS_FEEDBACK_ALPHA);
            return;
        }
        setAlpha(displayAlpha);
    }

    private void setTouchFeedbackActive(boolean active) {
        if (touchFeedbackActive == active) return;
        touchFeedbackActive = active;
        // Keep Android's drawable pressed state in sync as well. This matters for
        // any imported/custom drawable that reacts to state_pressed in addition to
        // DroidBridge's explicit alpha feedback.
        if (!editMode) setPressed(active);
        updateInteractionAlpha();
        invalidate();
    }

    private void setupJoystickPaints() {
        joystickBasePaint.setColor(data.backgroundColor);
        joystickBasePaint.setStyle(Paint.Style.FILL);
        joystickStrokePaint.setColor(data.strokeColor);
        joystickStrokePaint.setStyle(Paint.Style.STROKE);
        joystickStrokePaint.setStrokeWidth(Math.max(1f, Math.max(0f, data.strokeWidth) * visualUnitScale()));
        joystickKnobPaint.setColor(data.strokeColor);
        joystickKnobPaint.setStyle(Paint.Style.FILL);
        joystickGuidePaint.setColor(withAlphaFraction(data.strokeColor, 0.45f));
        joystickGuidePaint.setStyle(Paint.Style.STROKE);
        joystickGuidePaint.setStrokeWidth(Math.max(1f, 1.25f * visualUnitScale()));
    }

    private void setupResizeHandlePaints() {
        resizeHandlePaint.setColor(0xAA25D380);
        resizeHandlePaint.setStyle(Paint.Style.FILL);
        resizeHandleStrokePaint.setColor(0xFFFFFFFF);
        resizeHandleStrokePaint.setStyle(Paint.Style.STROKE);
        resizeHandleStrokePaint.setStrokeWidth(1.5f * visualUnitScale());
    }

    private void resetJoystickKnob() {
        joystickKnobX = getWidth() > 0 ? getWidth() / 2f : 0f;
        joystickKnobY = getHeight() > 0 ? getHeight() / 2f : 0f;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (TouchControlActions.JOYSTICK.equals(data.action) && !pressedState) {
            if (joystickForwardLocked) {
                positionJoystickKnobAtForwardLock();
            } else {
                joystickKnobX = w / 2f;
                joystickKnobY = h / 2f;
            }
        }
        updateResponsiveTextSize();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (TouchControlActions.JOYSTICK.equals(data.action)) {
            drawJoystick(canvas);
            super.onDraw(canvas);
        } else {
            drawControlImage(canvas);
            super.onDraw(canvas);
        }
        if (editMode && editSelected) drawResizeHandle(canvas);
    }

    private void drawJoystick(@NonNull Canvas canvas) {
        float width = Math.max(1f, getWidth());
        float height = Math.max(1f, getHeight());
        float centerX = width / 2f;
        float centerY = height / 2f;
        float outerRadius = joystickOuterRadius();
        float guideRadius = Math.min(width, height) * 0.28f;
        float knobRadius = joystickKnobRadius();
        boolean imageOnly = hasControlImageConfigured()
                && TouchControlData.IMAGE_MODE_REPLACE.equals(TouchControlData.normalizeImageMode(data.imageMode));
        if (!imageOnly) {
            canvas.drawCircle(centerX, centerY, outerRadius, joystickBasePaint);
            if (data.strokeWidth > 0f) canvas.drawCircle(centerX, centerY, outerRadius, joystickStrokePaint);
        }
        drawControlImage(canvas);
        canvas.drawCircle(centerX, centerY, guideRadius, joystickGuidePaint);
        float knobX = joystickKnobX > 0f ? joystickKnobX : centerX;
        float knobY = joystickKnobY > 0f ? joystickKnobY : centerY;
        float[] safeKnob = clampPointToJoystickCircle(knobX, knobY);
        canvas.drawCircle(safeKnob[0], safeKnob[1], knobRadius, joystickKnobPaint);
        if (data.strokeWidth > 0f) {
            canvas.drawCircle(safeKnob[0], safeKnob[1], knobRadius, joystickStrokePaint);
        }
    }

    private void drawResizeHandle(@NonNull Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        float handle = resizeHandleSize();
        float w = Math.max(1f, getWidth());
        float h = Math.max(1f, getHeight());

        // Make the editor resize affordance easier to see and grab. Instead of a
        // tiny triangle fully inside the control, draw a larger rounded pull tab
        // anchored to the lower-right corner. The center is biased toward the
        // corner so it visually feels like it is hanging off the button.
        float radius = handle * 0.5f;
        float centerInset = Math.max(3f * density, radius * 0.42f);
        float centerX = w - centerInset;
        float centerY = h - centerInset;
        canvas.drawCircle(centerX, centerY, radius, resizeHandlePaint);
        canvas.drawCircle(centerX, centerY, radius, resizeHandleStrokePaint);

        float lineGap = Math.max(4f * density, handle * 0.16f);
        float lineLength = Math.max(10f * density, handle * 0.38f);
        float startX = centerX + lineGap - lineLength;
        float startY = centerY + lineGap;
        canvas.drawLine(startX, startY, startX + lineLength, startY - lineLength, resizeHandleStrokePaint);
        canvas.drawLine(startX + (lineGap * 0.9f), startY, startX + lineLength, startY - (lineLength * 0.65f), resizeHandleStrokePaint);
    }

    private boolean isInResizeHandle(float x, float y) {
        if (!editMode || !editSelected) return false;

        // Keep the resize grip easy to grab without letting the enlarged hit area
        // swallow most of small buttons. A circular target around the visible
        // bottom-right tab is friendlier than the old full rectangular corner box,
        // which could turn normal edit taps into accidental resize starts.
        float density = getResources().getDisplayMetrics().density;
        float visualHandle = resizeHandleSize();
        float hitRadius = Math.max(24f * density, visualHandle * 0.78f);
        float overhang = resizeHandleOutsideHitOverhang();
        float centerInset = Math.max(3f * density, (visualHandle * 0.5f) * 0.42f);
        float centerX = getWidth() - centerInset;
        float centerY = getHeight() - centerInset;

        if (x < centerX - hitRadius || y < centerY - hitRadius) return false;
        if (x > getWidth() + overhang || y > getHeight() + overhang) return false;

        float dx = x - centerX;
        float dy = y - centerY;
        return (dx * dx) + (dy * dy) <= hitRadius * hitRadius;
    }

    boolean isInResizeHandleFromParent(float parentX, float parentY) {
        return isInResizeHandle(parentX - getX(), parentY - getY());
    }

    private float eventParentX(@NonNull MotionEvent event) {
        return getX() + event.getX();
    }

    private float eventParentY(@NonNull MotionEvent event) {
        return getY() + event.getY();
    }

    private float resizeHandleSize() {
        float density = getResources().getDisplayMetrics().density;
        return Math.max(30f * density, Math.min(getWidth(), getHeight()) * 0.38f);
    }

    private float resizeHandleHitSize() {
        float density = getResources().getDisplayMetrics().density;
        return Math.max(48f * density, resizeHandleSize());
    }

    private float resizeHandleOutsideHitOverhang() {
        return 18f * getResources().getDisplayMetrics().density;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        if (editMode) return handleEditTouch(event);
        return handleGameTouch(event);
    }

    private boolean handleEditTouch(@NonNull MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                requestParentDisallowIntercept(true);
                setPressed(true);
                editLongPressTriggered = false;
                editDragging = false;
                editResizing = false;
                float parentDownX = eventParentX(event);
                float parentDownY = eventParentY(event);
                touchOffsetX = parentDownX - getX();
                touchOffsetY = parentDownY - getY();
                downRawX = parentDownX;
                downRawY = parentDownY;
                if (isInResizeHandle(event.getX(), event.getY())) {
                    editResizing = true;
                    resizeStartRawX = parentDownX;
                    resizeStartRawY = parentDownY;
                    resizeStartWidth = getWidth();
                    resizeStartHeight = getHeight();
                    listener.onResizeStarted(this, data);
                    return true;
                }
                // In the editor, a normal tap opens the edit dialog. Dragging still
                // moves the control, and the bottom-right pull tab still resizes it.
                return true;
            case MotionEvent.ACTION_MOVE:
                if (editResizing) {
                    float proposedWidth = resizeStartWidth + (eventParentX(event) - resizeStartRawX);
                    float proposedHeight = resizeStartHeight + (eventParentY(event) - resizeStartRawY);
                    listener.onResizeRequested(this, data, proposedWidth, proposedHeight);
                    return true;
                }
                if (editLongPressTriggered) return true;
                float dx = eventParentX(event) - downRawX;
                float dy = eventParentY(event) - downRawY;
                if (!editDragging && ((dx * dx) + (dy * dy)) > (editTapSlop * editTapSlop)) {
                    editDragging = true;
                    cancelEditLongPress();
                    listener.onMoveStarted(this, data);
                }
                if (editDragging) {
                    listener.onMoveRequested(this, data, Math.max(0f, eventParentX(event) - touchOffsetX), Math.max(0f, eventParentY(event) - touchOffsetY));
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelEditLongPress();
                setPressed(false);
                requestParentDisallowIntercept(false);
                if ((editDragging || editResizing) && !editLongPressTriggered) {
                    listener.onChanged();
                }
                editResizing = false;
                return true;
            case MotionEvent.ACTION_UP:
                cancelEditLongPress();
                setPressed(false);
                requestParentDisallowIntercept(false);
                boolean wasDragging = editDragging;
                boolean wasResizing = editResizing;
                if (wasDragging || wasResizing) {
                    listener.onChanged();
                } else if (!editLongPressTriggered) {
                    performClick();
                    listener.onEditRequested(this, data);
                }
                editDragging = false;
                editResizing = false;
                return true;
            default:
                return true;
        }
    }

    private void scheduleEditLongPress() {
        cancelEditLongPress();
        editLongPressRunnable = () -> {
            editLongPressRunnable = null;
            if (!isAttachedToWindow() || !editMode) return;
            editLongPressTriggered = true;
            setPressed(false);
            requestParentDisallowIntercept(false);
            listener.onEditRequested(this, data);
        };
        mainHandler.postDelayed(editLongPressRunnable, ViewConfiguration.getLongPressTimeout());
    }

    private void cancelEditLongPress() {
        if (editLongPressRunnable != null) {
            mainHandler.removeCallbacks(editLongPressRunnable);
            editLongPressRunnable = null;
        }
    }

    private void requestParentDisallowIntercept(boolean disallow) {
        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow);
        }
    }

    private boolean handleGameTouch(@NonNull MotionEvent event) {
        if (TouchControlActions.JOYSTICK.equals(data.action)) return handleJoystickTouch(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                setTouchFeedbackActive(true);
                if (TouchControlActions.MENU.equals(data.action)) {
                    listener.onMenuRequested();
                    performClick();
                    clearTouchFeedbackSoon();
                    return true;
                }
                if (TouchControlActions.TOGGLE_CONTROLS.equals(data.action)) {
                    listener.onToggleControlsRequested();
                    performClick();
                    clearTouchFeedbackSoon();
                    return true;
                }
                if (TouchControlActions.VIRTUAL_MOUSE.equals(data.action)) {
                    listener.onVirtualMouseToggleRequested();
                    performClick();
                    clearTouchFeedbackSoon();
                    return true;
                }
                if (TouchControlActions.KEY_SENDER_KEYBOARD.equals(data.action)) {
                    listener.onKeySenderKeyboardRequested();
                    performClick();
                    clearTouchFeedbackSoon();
                    return true;
                }
                if (TouchControlActions.DUAL_SCREEN_SWAP.equals(data.action)) {
                    DualScreenSwapActionBus.requestSwap();
                    performClick();
                    clearTouchFeedbackSoon();
                    return true;
                }
                if (TouchControlActions.DRAWER.equals(data.action)) {
                    listener.onDrawerToggleRequested(this, data);
                    performClick();
                    clearTouchFeedbackSoon();
                    return true;
                }
                if (data.toggle) {
                    pressedState = !pressedState;
                    send(pressedState);
                } else {
                    pressedState = true;
                    send(true);
                }
                setActivated(pressedState);
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                setTouchFeedbackActive(false);
                if (!data.toggle && pressedState) {
                    pressedState = false;
                    send(false);
                    setActivated(false);
                }
                performClick();
                return true;
            default:
                return true;
        }
    }

    private boolean handleJoystickTouch(@NonNull MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                setTouchFeedbackActive(true);
                pressedState = true;
                joystickForwardGestureHandled = false;
                setActivated(true);
                if (data.joystickAbsolute) {
                    float[] clampedCenter = clampPointToJoystickCircle(event.getX(), event.getY());
                    joystickCenterX = clampedCenter[0];
                    joystickCenterY = clampedCenter[1];
                    // Starting in a square corner outside the visible joystick no longer
                    // puts half of the knob outside the view or generates an instant direction.
                    updateJoystick(joystickCenterX, joystickCenterY);
                } else {
                    joystickCenterX = getWidth() / 2f;
                    joystickCenterY = getHeight() / 2f;
                    updateJoystick(event.getX(), event.getY());
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                updateJoystick(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_CANCEL:
                // A cancelled Android gesture must never leave movement latched.
                // Hard-release here so focus/window changes cannot create a stuck W key.
                releaseJoystick();
                setTouchFeedbackActive(false);
                pressedState = false;
                setActivated(false);
                return true;
            case MotionEvent.ACTION_UP:
                finishJoystickGesture();
                setTouchFeedbackActive(false);
                pressedState = false;
                setActivated(joystickForwardLocked);
                performClick();
                return true;
            default:
                return true;
        }
    }

    private void updateJoystick(float x, float y) {
        float dx = x - joystickCenterX;
        float dy = y - joystickCenterY;
        float maxKnobTravel = joystickSafeTravelRadius();
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        float unitX = distance > 0f ? dx / distance : 0f;
        float unitY = distance > 0f ? dy / distance : 0f;
        float knobDistance = Math.min(distance, maxKnobTravel);
        float clampedDx = unitX * knobDistance;
        float clampedDy = unitY * knobDistance;
        float[] safeKnob = clampPointToJoystickCircle(
                joystickCenterX + clampedDx,
                joystickCenterY + clampedDy
        );
        joystickKnobX = safeKnob[0];
        joystickKnobY = safeKnob[1];
        invalidate();
        float configuredDeadzone = TouchControlData.clampJoystickDeadzonePercent(data.joystickDeadzonePercent) / 100f;
        float deadzone = Math.max(touchSlop, maxKnobTravel * configuredDeadzone);
        boolean wDown = clampedDy < -deadzone;
        boolean aDown = clampedDx < -deadzone;
        boolean sDown = clampedDy > deadzone;
        boolean dDown = clampedDx > deadzone;

        boolean forwardPress = data.joystickForwardLock
                && wDown
                && (-clampedDy >= Math.abs(clampedDx));
        if (forwardPress && !joystickForwardGestureHandled) {
            joystickForwardGestureHandled = true;

            if (joystickForwardLocked) {
                // Once running is latched, one deliberate forward press releases it.
                // Keep W physically down for this gesture; ACTION_UP will release it.
                joystickForwardLocked = false;
                joystickLastForwardPressUptimeMs = 0L;
            } else {
                long now = SystemClock.uptimeMillis();
                long sinceLastForward = now - joystickLastForwardPressUptimeMs;
                boolean doubleTap = joystickLastForwardPressUptimeMs > 0L
                        && sinceLastForward > 0L
                        && sinceLastForward <= JOYSTICK_FORWARD_DOUBLE_TAP_MS;

                if (doubleTap) {
                    // First tap is a normal W press/release. The second tap arrives
                    // inside Minecraft's sprint-style double-tap window, and this
                    // latch keeps that second W press held after the finger lifts.
                    // This gives Minecraft the required tap-release-tap pattern and
                    // leaves the player running instead of locking into a walk.
                    joystickForwardLocked = true;
                    joystickLastForwardPressUptimeMs = 0L;
                } else {
                    // A single forward press must remain completely normal. It only
                    // arms the short double-tap window; it does not latch movement.
                    joystickLastForwardPressUptimeMs = now;
                }
            }
        }

        // While latched, W stays down after the second forward press. A later forward
        // gesture clears the latch above but still keeps W down until that touch ends.
        setJoystickKeyStates(wDown || joystickForwardLocked, aDown, sDown, dDown);
    }

    private float joystickOuterRadius() {
        return Math.max(1f, Math.min(getWidth(), getHeight()) * 0.48f);
    }

    private float joystickKnobRadius() {
        float size = Math.max(1f, Math.min(getWidth(), getHeight()));
        float desired = Math.max(10f * visualUnitScale(), size * 0.18f);
        float halfStroke = Math.max(0f, joystickStrokePaint.getStrokeWidth() * 0.5f);
        float maximum = Math.max(1f, joystickOuterRadius() - halfStroke - 1f);
        return Math.min(desired, maximum);
    }

    private float joystickSafeTravelRadius() {
        float halfStroke = Math.max(0.5f, joystickStrokePaint.getStrokeWidth() * 0.5f);
        return Math.max(1f, joystickOuterRadius() - joystickKnobRadius() - halfStroke);
    }

    @NonNull
    private float[] clampPointToJoystickCircle(float x, float y) {
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float dx = x - centerX;
        float dy = y - centerY;
        float distance = (float) Math.sqrt((dx * dx) + (dy * dy));
        float maxDistance = joystickSafeTravelRadius();
        if (distance <= maxDistance || distance <= 0.0001f) {
            return new float[]{x, y};
        }
        float scale = maxDistance / distance;
        return new float[]{centerX + (dx * scale), centerY + (dy * scale)};
    }

    private void setJoystickKeyStates(boolean wDown, boolean aDown, boolean sDown, boolean dDown) {
        if (joystickWDown != wDown) { joystickWDown = wDown; sendKey(GLFW_KEY_W, wDown); }
        if (joystickADown != aDown) { joystickADown = aDown; sendKey(GLFW_KEY_A, aDown); }
        if (joystickSDown != sDown) { joystickSDown = sDown; sendKey(GLFW_KEY_S, sDown); }
        if (joystickDDown != dDown) { joystickDDown = dDown; sendKey(GLFW_KEY_D, dDown); }
    }

    private void finishJoystickGesture() {
        joystickForwardGestureHandled = false;
        if (joystickForwardLocked && data.joystickForwardLock) {
            // Keep forward held and leave the knob visibly parked at the top so the
            // control matches the input state after the user's finger is lifted.
            setJoystickKeyStates(true, false, false, false);
            positionJoystickKnobAtForwardLock();
            return;
        }
        joystickForwardLocked = false;
        setJoystickKeyStates(false, false, false, false);
        resetJoystickKnob();
    }

    private void positionJoystickKnobAtForwardLock() {
        joystickKnobX = getWidth() / 2f;
        joystickKnobY = (getHeight() / 2f) - joystickSafeTravelRadius();
        invalidate();
    }

    /** Hard release used for cancellation, detaching, hiding controls, or rebuilding. */
    private void releaseJoystick() {
        joystickForwardLocked = false;
        joystickForwardGestureHandled = false;
        joystickLastForwardPressUptimeMs = 0L;
        setJoystickKeyStates(false, false, false, false);
        resetJoystickKnob();
    }

    void releaseInputState() {
        cancelEditLongPress();
        releaseJoystick();
        if (pressedState) {
            pressedState = false;
            send(false);
        }
        editLongPressTriggered = false;
        editDragging = false;
        editResizing = false;
        touchFeedbackActive = false;
        setPressed(false);
        setActivated(false);
        updateInteractionAlpha();
    }

    private void clearTouchFeedbackSoon() {
        mainHandler.postDelayed(() -> setTouchFeedbackActive(false), 90L);
    }

    private void send(boolean down) {
        try {
            CallbackBridge.setInputReady(true);
            if (TouchControlActions.KEY.equals(data.action)) {
                for (int binding : data.normalizedKeyCodes()) {
                    sendSlotBinding(binding, down);
                }
                return;
            }
            if (TouchControlActions.MOUSE.equals(data.action)) {
                CallbackBridge.sendMouseButton(data.mouseButton, down);
                return;
            }
            if (TouchControlActions.SCROLL.equals(data.action)) {
                if (!down) CallbackBridge.sendScroll(0d, data.scrollY);
                return;
            }
            if (TouchControlActions.KEYBOARD.equals(data.action)) {
                if (down) TouchKeyboardHelper.showKeyboard(this);
                return;
            }
            if (TouchControlActions.KEY_SENDER_KEYBOARD.equals(data.action)) {
                if (down) listener.onKeySenderKeyboardRequested();
                return;
            }
            if (TouchControlActions.DUAL_SCREEN_SWAP.equals(data.action)) {
                if (down) DualScreenSwapActionBus.requestSwap();
                return;
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to send touch control input", throwable);
        }
    }

    private void sendSlotBinding(int binding, boolean down) {
        if (binding == 0) return;

        switch (binding) {
            case TouchControlData.SPECIAL_MOUSE_LEFT:
                CallbackBridge.sendMouseButton(GLFW_MOUSE_BUTTON_LEFT, down);
                return;
            case TouchControlData.SPECIAL_MOUSE_RIGHT:
                CallbackBridge.sendMouseButton(GLFW_MOUSE_BUTTON_RIGHT, down);
                return;
            case TouchControlData.SPECIAL_MOUSE_MIDDLE:
                CallbackBridge.sendMouseButton(GLFW_MOUSE_BUTTON_MIDDLE, down);
                return;
            case TouchControlData.SPECIAL_SCROLL_UP:
                if (!down) CallbackBridge.sendScroll(0d, 1d);
                return;
            case TouchControlData.SPECIAL_SCROLL_DOWN:
                if (!down) CallbackBridge.sendScroll(0d, -1d);
                return;
            case TouchControlData.SPECIAL_KEYBOARD:
                if (down) TouchKeyboardHelper.showKeyboard(this);
                return;
            case TouchControlData.SPECIAL_KEY_SENDER_KEYBOARD:
                if (down) listener.onKeySenderKeyboardRequested();
                return;
            case TouchControlData.SPECIAL_MENU:
                if (down) listener.onMenuRequested();
                return;
            case TouchControlData.SPECIAL_TOGGLE_CONTROLS:
                if (down) listener.onToggleControlsRequested();
                return;
            case TouchControlData.SPECIAL_VIRTUAL_MOUSE:
                if (down) listener.onVirtualMouseToggleRequested();
                return;
            case TouchControlData.SPECIAL_DUAL_SCREEN_SWAP:
                if (down) DualScreenSwapActionBus.requestSwap();
                return;
            default:
                if (binding > 0) sendKey(binding, down);
        }
    }

    private void sendKey(int keyCode, boolean down) {
        CallbackBridge.setInputReady(true);
        if (down && isChatOpenKey(keyCode)) {
            TouchKeyboardHelper.markChatKeyPressed();
        }
        CallbackBridge.sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), down);
        CallbackBridge.setModifiers(keyCode, down);
    }

    private static boolean isChatOpenKey(int keyCode) {
        return keyCode == GLFW_KEY_T || keyCode == GLFW_KEY_SLASH;
    }

    private GradientDrawable makeBackground(boolean editing) {
        GradientDrawable drawable = new GradientDrawable();
        boolean joystick = TouchControlActions.JOYSTICK.equals(data.action);
        drawable.setShape(joystick ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
        boolean imageOnly = hasControlImageConfigured()
                && TouchControlData.IMAGE_MODE_REPLACE.equals(TouchControlData.normalizeImageMode(data.imageMode));

        /*
         * The editor must render the same colours as gameplay. The old edit-mode
         * preview replaced every normal button with a purple fill and white border,
         * which made colour-wheel changes look broken even though the model value had
         * changed. Keep the real fill/stroke visible in edit mode; selection is already
         * communicated by the resize handle and editor panel.
         */
        drawable.setColor(joystick || imageOnly ? Color.TRANSPARENT : data.backgroundColor);
        float unitScale = visualUnitScale();
        int strokePx = joystick || imageOnly ? 0 : Math.round(
                Math.max(0f, data.strokeWidth) * unitScale
        );
        if (strokePx > 0) drawable.setStroke(strokePx, data.strokeColor);
        float radius = joystick ? 9999f : Math.max(0f, data.cornerRadius) * unitScale;
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private boolean hasControlImageConfigured() {
        return data.imageUri != null && !data.imageUri.trim().isEmpty();
    }

    private void drawControlImage(@NonNull Canvas canvas) {
        Bitmap bitmap = ensureControlImageLoaded();
        if (bitmap == null || bitmap.isRecycled()) return;

        float viewWidth = Math.max(1f, getWidth());
        float viewHeight = Math.max(1f, getHeight());
        boolean imageOnly = TouchControlData.IMAGE_MODE_REPLACE.equals(
                TouchControlData.normalizeImageMode(data.imageMode));
        float strokeInset = imageOnly ? 0f : Math.max(0f, data.strokeWidth * visualUnitScale());
        RectF clip = new RectF(
                strokeInset,
                strokeInset,
                Math.max(strokeInset + 1f, viewWidth - strokeInset),
                Math.max(strokeInset + 1f, viewHeight - strokeInset)
        );

        float bitmapWidth = Math.max(1f, bitmap.getWidth());
        float bitmapHeight = Math.max(1f, bitmap.getHeight());
        float fitScale = Math.min(clip.width() / bitmapWidth, clip.height() / bitmapHeight);
        float userScale = TouchControlData.clampImageScalePercent(data.imageScalePercent) / 100f;
        float drawWidth = bitmapWidth * fitScale * userScale;
        float drawHeight = bitmapHeight * fitScale * userScale;
        float offsetX = (TouchControlData.clampImageOffsetPercent(data.imageOffsetXPercent) / 100f) * clip.width();
        float offsetY = (TouchControlData.clampImageOffsetPercent(data.imageOffsetYPercent) / 100f) * clip.height();
        float centerX = clip.centerX() + offsetX;
        float centerY = clip.centerY() + offsetY;
        RectF destination = new RectF(
                centerX - (drawWidth / 2f),
                centerY - (drawHeight / 2f),
                centerX + (drawWidth / 2f),
                centerY + (drawHeight / 2f)
        );

        int save = canvas.save();
        imageClipPath.reset();
        if (TouchControlActions.JOYSTICK.equals(data.action)) {
            imageClipPath.addOval(clip, Path.Direction.CW);
        } else {
            float radius = Math.max(0f, data.cornerRadius) * visualUnitScale();
            imageClipPath.addRoundRect(clip, radius, radius, Path.Direction.CW);
        }
        canvas.clipPath(imageClipPath);
        canvas.drawBitmap(bitmap, null, destination, imagePaint);
        canvas.restoreToCount(save);
    }

    @Nullable
    private Bitmap ensureControlImageLoaded() {
        String uriText = data.imageUri == null ? null : data.imageUri.trim();
        if (uriText == null || uriText.isEmpty()) {
            releaseControlImage();
            return null;
        }
        if (controlImageBitmap != null && !controlImageBitmap.isRecycled()
                && uriText.equals(loadedImageUri)) {
            return controlImageBitmap;
        }

        releaseControlImage();
        loadedImageUri = uriText;
        try {
            Uri uri = Uri.parse(uriText);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = getContext().getContentResolver().openInputStream(uri)) {
                if (input != null) BitmapFactory.decodeStream(input, null, bounds);
            }
            int maxDimension = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (maxDimension / sample > 1024) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, sample);
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream input = getContext().getContentResolver().openInputStream(uri)) {
                if (input != null) controlImageBitmap = BitmapFactory.decodeStream(input, null, options);
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to load custom touch-control image " + uriText, throwable);
            controlImageBitmap = null;
        }
        return controlImageBitmap;
    }

    private void releaseControlImage() {
        if (controlImageBitmap != null && !controlImageBitmap.isRecycled()) {
            controlImageBitmap.recycle();
        }
        controlImageBitmap = null;
        loadedImageUri = null;
    }

    private static boolean sameNullableString(@Nullable String a, @Nullable String b) {
        if (a == b) return true;
        return a != null && a.equals(b);
    }

    private static int withAlphaFraction(int color, float fraction) {
        int alpha = Math.round(Color.alpha(color) * Math.max(0f, Math.min(1f, fraction)));
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }


    @Override
    protected void onDetachedFromWindow() {
        releaseInputState();
        releaseControlImage();
        super.onDetachedFromWindow();
    }
}

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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.TextView;

import androidx.annotation.NonNull;

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
    }

    private static final String TAG = "TouchButton";

    private static final int GLFW_KEY_W = 87;
    private static final int GLFW_KEY_A = 65;
    private static final int GLFW_KEY_S = 83;
    private static final int GLFW_KEY_D = 68;
    private static final int GLFW_KEY_LEFT_CONTROL = 341;
    private static final int GLFW_KEY_T = 84;
    private static final int GLFW_KEY_SLASH = 47;

    private static final int GLFW_MOUSE_BUTTON_LEFT = 0;
    private static final int GLFW_MOUSE_BUTTON_RIGHT = 1;
    private static final int GLFW_MOUSE_BUTTON_MIDDLE = 2;

    private static final float GAME_PRESS_FEEDBACK_ALPHA = 0.50f;

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

    private boolean joystickForwardLockDown;
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
        setText(data.label == null ? "" : data.label);
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
        setText(data.label == null ? "" : data.label);
        setBackground(makeBackground(editMode));
        updateInteractionAlpha();
        invalidate();
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
            // A hidden control must stay fully invisible even while pressed. The old
            // fixed 50% press feedback made 0% opacity buttons flash on-screen.
            if (displayAlpha <= 0.001f) {
                setAlpha(0f);
            } else {
                setAlpha(Math.max(displayAlpha, GAME_PRESS_FEEDBACK_ALPHA));
            }
            return;
        }
        setAlpha(displayAlpha);
    }

    private void setTouchFeedbackActive(boolean active) {
        if (touchFeedbackActive == active) return;
        touchFeedbackActive = active;
        updateInteractionAlpha();
    }

    private void setupJoystickPaints() {
        joystickBasePaint.setColor(0x33000000);
        joystickBasePaint.setStyle(Paint.Style.FILL);
        joystickStrokePaint.setColor(0xAAFFFFFF);
        joystickStrokePaint.setStyle(Paint.Style.STROKE);
        joystickStrokePaint.setStrokeWidth(2f * visualUnitScale());
        joystickKnobPaint.setColor(0x99FFFFFF);
        joystickKnobPaint.setStyle(Paint.Style.FILL);
        joystickGuidePaint.setColor(0x66FFFFFF);
        joystickGuidePaint.setStyle(Paint.Style.STROKE);
        joystickGuidePaint.setStrokeWidth(1.25f * visualUnitScale());
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
            joystickKnobX = w / 2f;
            joystickKnobY = h / 2f;
        }
        updateResponsiveTextSize();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (TouchControlActions.JOYSTICK.equals(data.action)) drawJoystick(canvas);
        super.onDraw(canvas);
        if (editMode && editSelected) drawResizeHandle(canvas);
    }

    private void drawJoystick(@NonNull Canvas canvas) {
        float width = Math.max(1f, getWidth());
        float height = Math.max(1f, getHeight());
        float centerX = width / 2f;
        float centerY = height / 2f;
        float outerRadius = Math.min(width, height) * 0.48f;
        float guideRadius = Math.min(width, height) * 0.28f;
        float knobRadius = Math.max(10f * getResources().getDisplayMetrics().density, Math.min(width, height) * 0.18f);
        canvas.drawCircle(centerX, centerY, outerRadius, joystickBasePaint);
        canvas.drawCircle(centerX, centerY, outerRadius, joystickStrokePaint);
        canvas.drawCircle(centerX, centerY, guideRadius, joystickGuidePaint);
        float knobX = joystickKnobX > 0f ? joystickKnobX : centerX;
        float knobY = joystickKnobY > 0f ? joystickKnobY : centerY;
        canvas.drawCircle(knobX, knobY, knobRadius, joystickKnobPaint);
        canvas.drawCircle(knobX, knobY, knobRadius, joystickStrokePaint);
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
                setActivated(true);
                joystickCenterX = data.joystickAbsolute ? event.getX() : getWidth() / 2f;
                joystickCenterY = data.joystickAbsolute ? event.getY() : getHeight() / 2f;
                updateJoystick(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                updateJoystick(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                releaseJoystick();
                setTouchFeedbackActive(false);
                pressedState = false;
                setActivated(false);
                performClick();
                return true;
            default:
                return true;
        }
    }

    private void updateJoystick(float x, float y) {
        float dx = x - joystickCenterX;
        float dy = y - joystickCenterY;
        float size = Math.max(1f, Math.min(getWidth(), getHeight()));
        float maxKnobTravel = Math.max(1f, size * 0.43f);
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        float unitX = distance > 0f ? dx / distance : 0f;
        float unitY = distance > 0f ? dy / distance : 0f;
        float knobDistance = Math.min(distance, maxKnobTravel);
        float clampedDx = unitX * knobDistance;
        float clampedDy = unitY * knobDistance;
        joystickKnobX = joystickCenterX + clampedDx;
        joystickKnobY = joystickCenterY + clampedDy;
        invalidate();
        float configuredDeadzone = TouchControlData.clampJoystickDeadzonePercent(data.joystickDeadzonePercent) / 100f;
        float deadzone = Math.max(touchSlop, maxKnobTravel * configuredDeadzone);
        setJoystickKeyStates(clampedDy < -deadzone, clampedDx < -deadzone, clampedDy > deadzone, clampedDx > deadzone);
        boolean shouldForwardLock = data.joystickForwardLock && clampedDy < -deadzone && distance > (maxKnobTravel * 0.88f);
        if (shouldForwardLock != joystickForwardLockDown) {
            joystickForwardLockDown = shouldForwardLock;
            sendKey(GLFW_KEY_LEFT_CONTROL, shouldForwardLock);
        }
    }

    private void setJoystickKeyStates(boolean wDown, boolean aDown, boolean sDown, boolean dDown) {
        if (joystickWDown != wDown) { joystickWDown = wDown; sendKey(GLFW_KEY_W, wDown); }
        if (joystickADown != aDown) { joystickADown = aDown; sendKey(GLFW_KEY_A, aDown); }
        if (joystickSDown != sDown) { joystickSDown = sDown; sendKey(GLFW_KEY_S, sDown); }
        if (joystickDDown != dDown) { joystickDDown = dDown; sendKey(GLFW_KEY_D, dDown); }
    }

    private void releaseJoystick() {
        setJoystickKeyStates(false, false, false, false);
        resetJoystickKnob();
        if (joystickForwardLockDown) {
            joystickForwardLockDown = false;
            sendKey(GLFW_KEY_LEFT_CONTROL, false);
        }
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
        drawable.setColor(editing ? 0x663F51B5 : data.backgroundColor);
        float unitScale = visualUnitScale();
        int editingStroke = editing ? Math.max(2, Math.round(1.5f * unitScale)) : 0;
        int strokePx = Math.max(
                editingStroke,
                Math.round(Math.max(0f, data.strokeWidth) * unitScale)
        );
        int strokeColor = editing ? 0xFFFFFFFF : data.strokeColor;
        if (strokePx > 0) drawable.setStroke(strokePx, strokeColor);
        float radius = joystick ? 9999f : Math.max(0f, data.cornerRadius) * unitScale;
        drawable.setCornerRadius(radius);
        return drawable;
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseInputState();
        super.onDetachedFromWindow();
    }
}

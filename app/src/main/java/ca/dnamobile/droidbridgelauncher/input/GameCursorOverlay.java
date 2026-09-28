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
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


import ca.dnamobile.droidbridgelauncher.controls.ControlsPreferences;
import ca.dnamobile.droidbridgelauncher.modcompat.ControllerModCompat;
import ca.dnamobile.droidbridgelauncher.settings.GameResolutionSettings;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.runtime.DroidBridgeSDL3Bootstrap;
import ca.dnamobile.droidbridgelauncher.runtime.MinecraftGLSurface;

import org.lwjgl.glfw.CallbackBridge;
public final class GameCursorOverlay extends View {
    private static final float CURSOR_CANVAS_DP = 28f;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path cursorPath = new Path();
    private final GamepadMappingStore mappingStore;
    @Nullable private Bitmap cursorBitmap;
    @Nullable private Drawable androidDefaultPointerDrawable;
    private boolean androidDefaultPointerLookupAttempted;
    private boolean useAndroidDefaultPointerVisual;
    @NonNull private String loadedCursorStyle = "";
    @Nullable private String loadedCustomCursorPath;
    private int loadedCursorSizePercent = -1;

    @Nullable private ViewGroup overlayParent;
    /** The actual Minecraft child surface inside overlayParent, when available. */
    @Nullable private View viewportTarget;
    private boolean drawableAdded;
    private boolean removed;
    private boolean cursorVisible;
    private boolean lastMenuMode;
    private boolean lastShouldShow;
    private boolean lastSdlInputReady;
    private int lastCursorCoordinateWidth = -1;
    private int lastCursorCoordinateHeight = -1;
    private long lastSdlGeometryLogUptimeMs;

    private final Drawable cursorDrawable = new Drawable() {
        @Override
        public void draw(@NonNull Canvas canvas) {
            if (!cursorVisible) return;

            Rect bounds = getBounds();
            if (bounds.width() <= 0 || bounds.height() <= 0) return;

            Drawable androidPointer = useAndroidDefaultPointerVisual
                    ? resolveAndroidDefaultPointerDrawable() : null;
            if (androidPointer != null) {
                canvas.save();
                canvas.translate(bounds.left, bounds.top);
                androidPointer.setBounds(0, 0, bounds.width(), bounds.height());
                androidPointer.draw(canvas);
                canvas.restore();
                return;
            }

            Bitmap bitmap = cursorBitmap;
            if (bitmap != null && !bitmap.isRecycled()) {
                canvas.drawBitmap(bitmap, null, bounds, null);
                return;
            }

            canvas.save();
            canvas.translate(bounds.left, bounds.top);
            drawFallbackCrosshair(canvas, bounds.width(), bounds.height());
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            fillPaint.setAlpha(alpha);
            strokePaint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            fillPaint.setColorFilter(colorFilter);
            strokePaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    };

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            updateFromBridge();
            if (!removed) {
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    };

    public GameCursorOverlay(@NonNull Context context) {
        super(context);
        mappingStore = GamepadMappingStore.get(context);
        reloadCursorBitmapIfNeeded(true);

        // This view is only a lifecycle owner for the overlay drawable.
        // Keep it out of layout hit testing completely.
        setVisibility(GONE);
        setWillNotDraw(true);
        setClickable(false);
        setLongClickable(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setHapticFeedbackEnabled(false);
        setSoundEffectsEnabled(false);
        setEnabled(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        fillPaint.setColor(Color.WHITE);
        fillPaint.setStyle(Paint.Style.FILL);

        strokePaint.setColor(Color.BLACK);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(4.0f));
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        fillPaint.setStrokeWidth(dp(1.8f));
        fillPaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setStrokeJoin(Paint.Join.ROUND);
        fillPaint.setStyle(Paint.Style.STROKE);

        buildPath();

        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setVisibility(GONE);
        attachDrawableToParent();
    }

    @Override
    protected void onDetachedFromWindow() {
        detachDrawableFromParent();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Stay effectively non-existent for hit testing/layout.
        setMeasuredDimension(1, 1);
    }

    public void setViewportTarget(@Nullable View target) {
        viewportTarget = target;
        cursorDrawable.invalidateSelf();
    }

    public void removeSelf() {
        removed = true;
        cursorVisible = false;
        Bitmap bitmap = cursorBitmap;
        cursorBitmap = null;
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        cursorDrawable.setBounds(0, 0, 0, 0);
        cursorDrawable.invalidateSelf();
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        detachDrawableFromParent();
    }

    private void attachDrawableToParent() {
        if (drawableAdded) return;
        if (!(getParent() instanceof ViewGroup)) return;

        overlayParent = (ViewGroup) getParent();
        overlayParent.getOverlay().add(cursorDrawable);
        drawableAdded = true;
    }

    private void detachDrawableFromParent() {
        if (!drawableAdded || overlayParent == null) return;

        overlayParent.getOverlay().remove(cursorDrawable);
        drawableAdded = false;
        overlayParent = null;
    }

    private void buildPath() {
        // The old GameCursorOverlay drew a classic arrow here. That caused the
        // visible "mouse over crosshair" bug once TouchControlsOverlay also
        // drew the direct-renderer pointer. Keep the method for source compatibility;
        // drawing now happens through drawFallbackCrosshair() or the bundled
        // ic_gamepad_pointer.png asset.
        cursorPath.reset();
    }

    private void drawFallbackCrosshair(@NonNull Canvas canvas, int width, int height) {
        float centerX = width / 2f;
        float centerY = height / 2f;
        float density = getResources().getDisplayMetrics().density;
        float arm = 11f * density;
        float gap = 3.5f * density;
        float ringRadius = 6.5f * density;

        drawCrosshairLines(canvas, centerX, centerY, arm, gap, ringRadius, strokePaint);
        drawCrosshairLines(canvas, centerX, centerY, arm, gap, ringRadius, fillPaint);
    }

    private static void drawCrosshairLines(
            @NonNull Canvas canvas,
            float x,
            float y,
            float arm,
            float gap,
            float ringRadius,
            @NonNull Paint paint
    ) {
        canvas.drawLine(x - arm, y, x - gap, y, paint);
        canvas.drawLine(x + gap, y, x + arm, y, paint);
        canvas.drawLine(x, y - arm, x, y - gap, paint);
        canvas.drawLine(x, y + gap, x, y + arm, paint);
        canvas.drawCircle(x, y, ringRadius, paint);
    }

    @Nullable
    private Drawable resolveAndroidDefaultPointerDrawable() {
        if (androidDefaultPointerLookupAttempted) return androidDefaultPointerDrawable;
        androidDefaultPointerLookupAttempted = true;

        // While a physical pointer is captured Android hides the real OS cursor.
        // Use the framework's own default arrow artwork for Android Virtual Mouse
        // instead of DroidBridge's configurable touch/controller cursor. OEMs that
        // expose a themed framework pointer keep their device-specific appearance.
        String[] candidates = {"pointer_arrow", "pointer_icon_arrow", "cursor_arrow"};
        Resources resources = Resources.getSystem();
        for (String name : candidates) {
            try {
                int id = resources.getIdentifier(name, "drawable", "android");
                if (id == 0) continue;
                Drawable drawable = getContext().getDrawable(id);
                if (drawable != null) {
                    androidDefaultPointerDrawable = drawable.mutate();
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
        return androidDefaultPointerDrawable;
    }

    private void reloadCursorBitmapIfNeeded(boolean force) {
        // The physical-mouse software cursor is still the user's DroidBridge cursor.
        // Do not force an arrow here: built-in styles (including crosshair/dot) and
        // imported custom cursor images must remain authoritative regardless of
        // whether the cursor is driven by touch, controller, or a captured mouse.
        String style = ControlsPreferences.getMouseCursorStyle(getContext());
        String customPath = ControlsPreferences.getCustomMouseCursorPath(getContext());
        int sizePercent = ControlsPreferences.getMouseCursorSizePercent(getContext());

        boolean sameStyle = style.equals(loadedCursorStyle);
        boolean samePath = customPath == null ? loadedCustomCursorPath == null : customPath.equals(loadedCustomCursorPath);
        boolean sameSize = sizePercent == loadedCursorSizePercent;
        if (!force && sameStyle && samePath && sameSize) return;

        loadedCursorStyle = style;
        loadedCustomCursorPath = customPath;
        loadedCursorSizePercent = sizePercent;

        Bitmap previous = cursorBitmap;
        cursorBitmap = MouseCursorBitmapUtils.loadCursorBitmap(getContext(), style, customPath);
        if (previous != null && previous != cursorBitmap && !previous.isRecycled()) {
            previous.recycle();
        }
        cursorDrawable.invalidateSelf();
    }

    private void updateFromBridge() {
        attachDrawableToParent();
        reloadCursorBitmapIfNeeded(false);

        boolean physicalMouseSoftwareCursor = MinecraftGLSurface
                .isHardwareMenuSoftwareCursorActive();
        if (ControllerModCompat.shouldHideLauncherCursorForControllerMod()
                && !physicalMouseSoftwareCursor) {
            hideCursorUntilLauncherOwnsInputAgain();
            return;
        }

        boolean menuMode = !mappingStore.isForceGameMode() && !CallbackBridge.isGrabbing();
        boolean physicalPointerConnected = hasPhysicalPointerDevice();

        // Touch virtual mouse and controller cursor are both menu cursors.
        // The controller cursor must still show when a controller is attached;
        // some Android handhelds expose controller hardware with pointer-ish
        // sources, so do not let physicalPointerConnected hide controller mode.
        boolean showTouchVirtualCursor = ControlsPreferences.isVirtualMouseEnabled(getContext())
                && menuMode
                && !physicalPointerConnected;
        boolean showControllerMenuCursor = mappingStore.isShowCursorOverlay()
                && menuMode;
        // Android applications cannot warp the OS hardware pointer to Minecraft's
        // centered GUI position. When MinecraftGLSurface keeps a real mouse captured
        // in menu mode, this overlay becomes the authoritative visible hardware cursor
        // too. It follows the same CallbackBridge position Minecraft uses for hover
        // and clicks, eliminating the old "center item highlighted, real cursor left"
        // split on both GLFW and SDL3 versions.
        boolean showPhysicalMouseCursor = menuMode
                && physicalMouseSoftwareCursor;
        useAndroidDefaultPointerVisual = showPhysicalMouseCursor
                && LauncherPreferences.isAndroidVirtualPhysicalMouse(getContext())
                && resolveAndroidDefaultPointerDrawable() != null;
        boolean shouldShow = showTouchVirtualCursor
                || showControllerMenuCursor
                || showPhysicalMouseCursor;
        boolean sdlInputReady = DroidBridgeSDL3Bootstrap.isInputReady();
        int cursorCoordinateWidth = Math.max(1, sdlInputReady
                ? DroidBridgeSDL3Bootstrap.getSdlCursorCoordinateWidth()
                : (CallbackBridge.windowWidth > 0
                ? CallbackBridge.windowWidth : CallbackBridge.physicalWidth));
        int cursorCoordinateHeight = Math.max(1, sdlInputReady
                ? DroidBridgeSDL3Bootstrap.getSdlCursorCoordinateHeight()
                : (CallbackBridge.windowHeight > 0
                ? CallbackBridge.windowHeight : CallbackBridge.physicalHeight));
        boolean coordinateSpaceChanged = cursorCoordinateWidth != lastCursorCoordinateWidth
                || cursorCoordinateHeight != lastCursorCoordinateHeight;
        boolean sdlBecameReady = sdlInputReady && !lastSdlInputReady;

        if (shouldShow && (!lastShouldShow
                || (!lastMenuMode && menuMode)
                || sdlBecameReady
                || coordinateSpaceChanged)) {
            String reason;
            if (sdlBecameReady) {
                reason = "overlay-sdl-ready";
            } else if (coordinateSpaceChanged) {
                reason = "overlay-coordinate-space-changed";
            } else if (!lastMenuMode && menuMode) {
                reason = "overlay-menu-opened";
            } else {
                reason = "overlay-first-visible";
            }
            resetBridgeCursorToCenter(reason);
        }

        lastSdlInputReady = sdlInputReady;
        lastCursorCoordinateWidth = cursorCoordinateWidth;
        lastCursorCoordinateHeight = cursorCoordinateHeight;
        lastMenuMode = menuMode;
        lastShouldShow = shouldShow;
        cursorVisible = shouldShow;

        if (!shouldShow || overlayParent == null) {
            cursorDrawable.setBounds(0, 0, 0, 0);
            cursorDrawable.invalidateSelf();
            return;
        }

        int rootWidth = Math.max(1, overlayParent.getWidth());
        int rootHeight = Math.max(1, overlayParent.getHeight());
        RectF viewportBounds = resolveViewportBounds(rootWidth, rootHeight);
        int viewportWidth = Math.max(1, Math.round(viewportBounds.width()));
        int viewportHeight = Math.max(1, Math.round(viewportBounds.height()));
        int cursorExtent = Math.max(1, Math.round(
                dp(CURSOR_CANVAS_DP)
                        * ControlsPreferences.getMouseCursorSizePercent(getContext())
                        / 100f
        ));
        Bitmap bitmap = cursorBitmap;
        Drawable androidPointer = useAndroidDefaultPointerVisual
                ? resolveAndroidDefaultPointerDrawable() : null;
        int cursorWidth;
        int cursorHeight;
        if (androidPointer != null) {
            int intrinsicWidth = androidPointer.getIntrinsicWidth();
            int intrinsicHeight = androidPointer.getIntrinsicHeight();
            cursorWidth = intrinsicWidth > 0 ? intrinsicWidth : Math.max(1, Math.round(dp(24f)));
            cursorHeight = intrinsicHeight > 0 ? intrinsicHeight : Math.max(1, Math.round(dp(24f)));
        } else {
            cursorWidth = MouseCursorBitmapUtils.getDrawWidth(bitmap, cursorExtent);
            cursorHeight = MouseCursorBitmapUtils.getDrawHeight(bitmap, cursorExtent);
        }

        float bridgeWidth = Math.max(1f, CallbackBridge.windowWidth > 0
                ? CallbackBridge.windowWidth : CallbackBridge.physicalWidth);
        float bridgeHeight = Math.max(1f, CallbackBridge.windowHeight > 0
                ? CallbackBridge.windowHeight : CallbackBridge.physicalHeight);

        float boundsLeft;
        float boundsTop;
        float boundsWidth;
        float boundsHeight;
        boolean sdlWindowActive = DroidBridgeSDL3Bootstrap.isRequested();
        if (sdlWindowActive) {
            /*
             * SDL coordinates are still mapped against the physical Minecraft surface,
             * not necessarily the full Activity root. Portrait (Centered Game View)
             * deliberately makes that surface a smaller landscape rectangle inside a
             * portrait window, so drawing against the root puts the software cursor in
             * the black bars even while Minecraft highlights an item in the center.
             * A normal fullscreen surface resolves to the same full-root bounds as before.
             */
            boundsLeft = viewportBounds.left;
            boundsTop = viewportBounds.top;
            boundsWidth = viewportBounds.width();
            boundsHeight = viewportBounds.height();
            bridgeWidth = Math.max(1f,
                    DroidBridgeSDL3Bootstrap.getSdlCursorCoordinateWidth());
            bridgeHeight = Math.max(1f,
                    DroidBridgeSDL3Bootstrap.getSdlCursorCoordinateHeight());
        } else {
            GameResolutionSettings.DisplayBounds displayBounds =
                    GameResolutionSettings.resolveDisplayBounds(
                            getContext(), viewportWidth, viewportHeight);
            boundsLeft = viewportBounds.left + displayBounds.left;
            boundsTop = viewportBounds.top + displayBounds.top;
            boundsWidth = displayBounds.width;
            boundsHeight = displayBounds.height;
        }

        // Match the exact edge-to-edge mapping used when Android pointer events are
        // converted into CallbackBridge coordinates. Using size/size here leaves the
        // software cursor increasingly offset as it moves away from the top-left.
        float maxBridgeX = Math.max(0f, bridgeWidth - 1f);
        float maxBridgeY = Math.max(0f, bridgeHeight - 1f);
        float maxDrawX = Math.max(0f, boundsWidth - 1f);
        float maxDrawY = Math.max(0f, boundsHeight - 1f);
        float drawX = boundsLeft + (maxBridgeX <= 0f
                ? 0f : clamp(CallbackBridge.mouseX, 0f, maxBridgeX) * maxDrawX / maxBridgeX);
        float drawY = boundsTop + (maxBridgeY <= 0f
                ? 0f : clamp(CallbackBridge.mouseY, 0f, maxBridgeY) * maxDrawY / maxBridgeY);

        // The Minecraft/SDL coordinate is the click hotspot and must never move
        // when the user changes cursor size or imports a differently sized image.
        drawX = clamp(drawX, boundsLeft, boundsLeft + Math.max(0f, boundsWidth - 1f));
        drawY = clamp(drawY, boundsTop, boundsTop + Math.max(0f, boundsHeight - 1f));

        if (sdlWindowActive && InputEventDiagnosticLogger.isEnabled()) {
            long now = SystemClock.uptimeMillis();
            if (now - lastSdlGeometryLogUptimeMs >= 1000L) {
                lastSdlGeometryLogUptimeMs = now;
                ca.dnamobile.droidbridgelauncher.logs.LauncherLogManager.append(
                        "DroidBridgeSDL3Cursor: root=" + rootWidth + "x" + rootHeight
                                + " viewport=" + Math.round(viewportBounds.left) + ","
                                + Math.round(viewportBounds.top) + "+"
                                + viewportWidth + "x" + viewportHeight
                                + " cursorSpace=" + bridgeWidth + "x" + bridgeHeight
                                + " render=" + DroidBridgeSDL3Bootstrap.getSdlLogicalWidth()
                                + "x" + DroidBridgeSDL3Bootstrap.getSdlLogicalHeight()
                                + " bridge=" + CallbackBridge.mouseX + "," + CallbackBridge.mouseY
                                + " draw=" + drawX + "," + drawY);
            }
        }

        boolean centeredHotspot = !useAndroidDefaultPointerVisual
                && MouseCursorBitmapUtils.usesCenteredHotspot(loadedCursorStyle);
        int left = centeredHotspot
                ? Math.round(drawX - (cursorWidth / 2f))
                : Math.round(drawX);
        int top = centeredHotspot
                ? Math.round(drawY - (cursorHeight / 2f))
                : Math.round(drawY);
        cursorDrawable.setBounds(left, top, left + cursorWidth, top + cursorHeight);
        cursorDrawable.invalidateSelf();
    }

    @NonNull
    private RectF resolveViewportBounds(int rootWidth, int rootHeight) {
        RectF full = new RectF(0f, 0f, Math.max(1, rootWidth), Math.max(1, rootHeight));
        ViewGroup parent = overlayParent;
        View target = viewportTarget;
        if (parent == null || target == null || target.getWidth() <= 1 || target.getHeight() <= 1) {
            return full;
        }

        try {
            if (parent.getDisplay() != null && target.getDisplay() != null
                    && parent.getDisplay().getDisplayId() != target.getDisplay().getDisplayId()) {
                return full;
            }

            int[] parentLocation = new int[2];
            int[] targetLocation = new int[2];
            parent.getLocationOnScreen(parentLocation);
            target.getLocationOnScreen(targetLocation);

            float left = targetLocation[0] - parentLocation[0];
            float top = targetLocation[1] - parentLocation[1];
            float right = left + target.getWidth();
            float bottom = top + target.getHeight();

            left = clamp(left, 0f, full.right);
            top = clamp(top, 0f, full.bottom);
            right = clamp(right, left, full.right);
            bottom = clamp(bottom, top, full.bottom);
            if (right - left > 1f && bottom - top > 1f) {
                return new RectF(left, top, right, bottom);
            }
        } catch (Throwable ignored) {
        }
        return full;
    }

    private void hideCursorUntilLauncherOwnsInputAgain() {
        lastShouldShow = false;
        lastMenuMode = false;
        cursorVisible = false;
        cursorDrawable.setBounds(0, 0, 0, 0);
        cursorDrawable.invalidateSelf();
    }

    private static void resetBridgeCursorToCenter(@NonNull String reason) {
        try {
            if (DroidBridgeSDL3Bootstrap.isInputReady()) {
                DroidBridgeSDL3Bootstrap.recenterLauncherMenuCursor(reason);
                return;
            }

            float width = Math.max(1f, CallbackBridge.windowWidth > 0
                    ? CallbackBridge.windowWidth : CallbackBridge.physicalWidth);
            float height = Math.max(1f, CallbackBridge.windowHeight > 0
                    ? CallbackBridge.windowHeight : CallbackBridge.physicalHeight);
            CallbackBridge.setInputReady(true);
            CallbackBridge.mouseX = Math.max(0f, width - 1f) / 2f;
            CallbackBridge.mouseY = Math.max(0f, height - 1f) / 2f;
            CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
        } catch (Throwable ignored) {
        }
    }

    private static boolean hasPhysicalPointerDevice() {
        try {
            for (int id : android.view.InputDevice.getDeviceIds()) {
                android.view.InputDevice device = android.view.InputDevice.getDevice(id);
                if (isRealExternalPointerDevice(device)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Real USB/Bluetooth mice should hide the launcher software cursor.
     * Controllers must not. Some Android handhelds and Bluetooth pads expose
     * misleading pointer-like sources, especially TOUCHPAD/MOUSE, even though
     * they are still the game controller. The previous detector trusted those
     * sources too much, so attaching a controller could hide the virtual cursor.
     *
     * Keep this intentionally conservative: only a confident external mouse
     * source hides the virtual cursor. A controller/touchpad-like device should
     * not stop the user from toggling the on-screen cursor with the touch button.
     */
    private static boolean isRealExternalPointerDevice(@Nullable android.view.InputDevice device) {
        if (device == null) return false;

        int sources = device.getSources();
        if (isControllerLikeDevice(device, sources)) return false;

        boolean hasPointerSource = (sources & android.view.InputDevice.SOURCE_MOUSE) == android.view.InputDevice.SOURCE_MOUSE
                || (sources & android.view.InputDevice.SOURCE_MOUSE_RELATIVE) == android.view.InputDevice.SOURCE_MOUSE_RELATIVE
                || (sources & android.view.InputDevice.SOURCE_TOUCHPAD) == android.view.InputDevice.SOURCE_TOUCHPAD
                || device.supportsSource(android.view.InputDevice.SOURCE_MOUSE)
                || device.supportsSource(android.view.InputDevice.SOURCE_MOUSE_RELATIVE)
                || device.supportsSource(android.view.InputDevice.SOURCE_TOUCHPAD);
        if (!hasPointerSource) return false;

        String name = safeLower(device.getName());
        if (looksLikeControllerName(name) || looksLikeVirtualTouchName(name)) return false;

        // Bluetooth mice and keyboard-touchpads can report generic names or bad
        // isExternal() metadata. If Android exposes a mouse/touchpad source and
        // the device is not a controller/virtual touch helper, treat it as a real
        // pointer so the launcher software cursor hides correctly.
        return true;
    }

    private static boolean isControllerLikeDevice(@NonNull android.view.InputDevice device, int sources) {
        if ((sources & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD
                || (sources & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK) {
            return true;
        }
        return looksLikeControllerName(safeLower(device.getName()));
    }

    private static boolean looksLikeControllerName(@NonNull String name) {
        return name.contains("controller")
                || name.contains("gamepad")
                || name.contains("joystick")
                || name.contains("xbox")
                || name.contains("dualshock")
                || name.contains("dualsense")
                || name.contains("playstation")
                || name.contains("8bitdo")
                || name.contains("gamesir")
                || name.contains("ipega")
                || name.contains("backbone")
                || name.contains("kishi")
                || name.contains("odin")
                || name.contains("retroid")
                || name.contains("anbernic")
                || name.contains("aya")
                || name.contains("gpd")
                || name.contains("legion go")
                || name.contains("steam deck")
                || name.contains("razer raiju")
                || name.contains("moga");
    }

    private static boolean looksLikeVirtualTouchName(@NonNull String name) {
        return name.contains("virtual")
                || name.contains("touchscreen")
                || name.contains("touch mapping")
                || name.contains("touchmapping")
                || name.contains("uinput")
                || name.contains("gpio")
                || name.contains("keypad");
    }

    private static boolean looksLikeMouseName(@NonNull String name) {
        return name.contains("mouse")
                || name.contains("trackball")
                || name.contains("trackpad")
                || name.contains("receiver")
                || name.contains("logitech")
                || name.contains("razer")
                || name.contains("microsoft")
                || name.contains("hid-compliant");
    }

    @NonNull
    private static String safeLower(@Nullable String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.US);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // Drawing happens through the parent ViewGroupOverlay instead.
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}

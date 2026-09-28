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

import androidx.appcompat.app.AlertDialog;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.hardware.input.InputManager;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import java.util.Locale;
import java.util.List;
import java.util.Objects;
import java.lang.ref.WeakReference;

import org.json.JSONObject;
import org.lwjgl.glfw.CallbackBridge;

import ca.dnamobile.droidbridgelauncher.settings.GameResolutionSettings;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.runtime.DroidBridgeSDL3Bootstrap;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.dualscreen.DualScreenSwapActionBus;
import ca.dnamobile.droidbridgelauncher.input.GameCursorOverlay;
import ca.dnamobile.droidbridgelauncher.input.GamepadMappingStore;
import ca.dnamobile.droidbridgelauncher.input.MouseCursorBitmapUtils;
import ca.dnamobile.droidbridgelauncher.modcompat.ControllerModCompat;
import ca.dnamobile.droidbridgelauncher.modcompat.ControlifySDL;
import ca.dnamobile.droidbridgelauncher.modcompat.TouchControllerModCompat;
import ca.dnamobile.droidbridgelauncher.ui.LauncherDialogStyle;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;
import ca.dnamobile.droidbridgelauncher.runtime.MinecraftGLSurface;
import ca.dnamobile.droidbridgelauncher.runtime.LwjglGlfwKeycode;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Runtime/editor overlay. It deliberately avoids XML so it can be injected over the
 * existing Minecraft surface without rewriting activity_game.xml.
 */
public final class TouchControlsOverlay extends FrameLayout implements TouchControlButtonView.Listener {
    public interface AppMenuListener {
        void onTouchControlsMenuRequested();
    }

    /**
     * Keeps the real in-game overlay synchronized with the temporary editor overlay.
     * The editor lives in a separate dialog window, so without this bridge both
     * overlays draw the same control and a moved button leaves its old runtime copy
     * visible as a ghost.
     */
    public interface EditorPreviewListener {
        void onEditorLayoutPreviewChanged(@NonNull TouchControlsOverlay editorOverlay);

        void onEditorControlPreviewSuppressed(@NonNull String controlId, boolean suppressed);
    }

    private static final String TAG = "TouchControlsOverlay";

    /**
     * The dual-screen recorder renders DroidBridge's view directly into an encoder surface.
     * Use a UI-thread-local suppression flag so that recording can omit only this overlay's
     * artwork without changing the live view or disabling any touch hit targets.
     */
    @NonNull
    private static final ThreadLocal<Boolean> RECORDING_ARTWORK_SUPPRESSED = new ThreadLocal<>();
    private static final int MAX_EDIT_HISTORY = 4;
    private static final int MAX_PANEL_EDIT_HISTORY = 32;
    private static final String MOUSE_PASS_THROUGH_PREFS = "touch_control_mouse_pass_through";
    private static final String SWIPE_GESTURE_PREFS = "touch_control_swipe_gesture";
    private static final String PROFILE_SETTINGS_MIGRATION_PREFS = "touch_control_profile_settings_migration";
    private static final String PROFILE_SETTINGS_MIGRATION_V1 = "legacy_global_settings_imported_v1";
    private static final float MIN_RESPONSIVE_CANVAS_SCALE = 0.55f;
    private static final float MAX_RESPONSIVE_CANVAS_SCALE = 2.25f;
    private static final int REQUEST_TOUCH_CONTROL_IMAGE = 0x44D1;
    @Nullable private static WeakReference<TouchControlsOverlay> pendingImagePickerOwner;

    @NonNull private final ArrayDeque<String> undoHistory = new ArrayDeque<>();
    @NonNull private final ArrayDeque<String> redoHistory = new ArrayDeque<>();

    private boolean editMode;
    private boolean controlsVisible = true;
    private boolean controllerAutoHidden;
    @Nullable private InputManager controllerInputManager;
    private boolean controllerInputListenerRegistered;
    @NonNull private final Handler controllerAutoHideHandler = new Handler(Looper.getMainLooper());
    @NonNull private final InputManager.InputDeviceListener controllerInputDeviceListener =
            new InputManager.InputDeviceListener() {
                @Override
                public void onInputDeviceAdded(int deviceId) {
                    refreshControllerAutoHideState();
                }

                @Override
                public void onInputDeviceRemoved(int deviceId) {
                    refreshControllerAutoHideState();
                }

                @Override
                public void onInputDeviceChanged(int deviceId) {
                    refreshControllerAutoHideState();
                }
            };
    private boolean rebuildPending;
    private boolean responsiveCanvasSavePending;
    @Nullable private File layoutFile;
    @NonNull private TouchControlsLayoutData layoutData = TouchControlsLayoutData.defaultLayout();
    @Nullable private AppMenuListener appMenuListener;
    @Nullable private Runnable editorPanelHideRequest;
    @Nullable private EditorPreviewListener editorPreviewListener;
    @NonNull private final HashSet<String> editorPreviewSuppressedControlIds = new HashSet<>();
    /** Drawer expansion is runtime state; profiles store only membership/default-open. */
    @NonNull private final HashSet<String> expandedDrawerIds = new HashSet<>();
    @NonNull private final HashSet<String> initializedDrawerIds = new HashSet<>();
    @Nullable private String activeGeometryPreviewControlId;
    @Nullable private String bulkAppearanceUndoSnapshot;
    @Nullable private String editorSessionStartSnapshot;
    @Nullable private TouchControlButtonView selectedEditButton;
    @Nullable private AlertDialog activeEditDialog;
    @Nullable private ActiveEditGeometryUi activeEditGeometryUi;
    @Nullable private ActivePanelEditHistory activePanelEditHistory;
    @Nullable private EditPanelSwitchController activeEditPanelSwitchController;
    @Nullable private TouchControlData pendingImageControl;
    @Nullable private Runnable pendingImagePickedCallback;
    private boolean syncingActiveEditGeometryUi;

    private interface EditPanelSwitchController {
        boolean hasUnsavedChanges();
        void closeForSwitch();
    }

    @Nullable private View passthroughTarget;
    /**
     * Optional same-display Minecraft viewport used only for input/cursor/hotbar mapping.
     *
     * Portrait (Centered Game View) intentionally keeps the touch-control canvas on the
     * full portrait screen so controls can live above and below the centered game image
     * and can be edited naturally in portrait. Raw Minecraft touches still need to be
     * translated into the smaller centered surface, which is why input and layout are
     * tracked separately.
     */
    @Nullable private View inputViewportTarget;
    private boolean dualScreenBottomHudHotbarMode;

    /**
     * Optional explicit Minecraft options.txt for the active instance.
     * If GameActivity does not set this, the overlay falls back to the current
     * PathManager.DIR_MINECRAFT_HOME/options.txt so the hotbar hitbox can still
     * follow Minecraft GUI-scale changes in game.
     */
    @Nullable private File minecraftOptionsFile;
    @Nullable private File cachedMinecraftOptionsFile;
    private long lastMinecraftOptionsResolveAtMs;
    private static final long OPTIONS_FILE_RESOLVE_THROTTLE_MS = 1000L;

    /**
     * Virtual cursor is only a GUI/menu cursor. While Minecraft has grabbed the
     * mouse for normal gameplay, the launcher must not draw or route the fake
     * cursor or it will fight camera movement. Keep this cursor separate from
     * CallbackBridge.mouseX/mouseY because those coordinates are also used by
     * grabbed camera-look deltas while the player is moving around the world.
     */
    private boolean lastVirtualMousePreference;
    private boolean lastKnownMouseGrabbed = true;
    private boolean androidPointerIconHidden;
    private boolean pointerIconReapplyPending;
    @Nullable private PointerIcon transparentPointerIcon;
    private boolean virtualCursorInitialized;
    private float virtualCursorBridgeX;
    private float virtualCursorBridgeY;
    @Nullable private Bitmap virtualCursorBitmap;
    @NonNull private String loadedVirtualCursorStyle = "";
    @Nullable private String loadedVirtualCursorCustomPath;
    private int loadedVirtualCursorSizePercent = -1;

    /**
     * Runtime multi-touch routing:
     * - pointers that begin on a touch button are owned by that button
     * - the first pointer that begins on empty space is forwarded to MinecraftGLSurface as
     *   a clean single-pointer mouse/camera stream
     *
     * Android may reorder pointer indexes when a finger lifts/re-enters. Tracking by
     * pointer ID keeps the camera finger stable while other fingers hold buttons.
     *
     * Do not forward the full MotionEvent or a split multi-pointer stream to Minecraft
     * while buttons are held. The game surface expects a normal DOWN/MOVE/UP sequence
     * for camera look; sending ACTION_POINTER_DOWN/UP while another finger owns a button
     * can make the camera jump left/right when the look finger is lifted and placed back.
     */
    private static final int NO_POINTER_ID = -1;
    private static final int MOUSE_BUTTON_LEFT = 0;
    private static final int MOUSE_BUTTON_RIGHT = 1;

    private boolean keySenderKeyboardVisible;
    @Nullable private View keySenderKeyboardView;

    private static final long REGRAB_TOUCH_DELTA_SUPPRESS_MS = 450L;

    private final Handler gestureHandler = new Handler(Looper.getMainLooper());
    private final int cameraTouchSlop;
    private long suppressCameraDeltaUntilUptimeMs;

    /** Pointer ID for the right-thumb look/attack stream. */
    private int cameraPointerId = NO_POINTER_ID;
    private float cameraDownX;
    private float cameraDownY;
    private float cameraLastX;
    private float cameraLastY;
    private boolean cameraMovedPastSlop;
    private boolean cameraLongPressAttackActive;
    @Nullable private Runnable cameraLongPressRunnable;

    /** GUI fallback: used only when Minecraft is not grabbing the mouse. */
    private int passthroughPointerId = NO_POINTER_ID;
    private long passthroughDownTime;
    private float passthroughDownX;
    private float passthroughDownY;
    private boolean passthroughMovedPastSlop;

    /**
     * Two-finger menu/chat scrolling. The overlay normally forwards only one empty-space
     * finger to Minecraft so touch buttons and camera look stay stable. Track a deliberate
     * two-finger gesture here and translate its vertical centroid movement into GLFW wheel
     * steps without allowing either finger to become a menu click.
     */
    private boolean menuTwoFingerScrollActive;
    private boolean menuTwoFingerScrollBlockingUntilAllUp;
    private int menuTwoFingerScrollPointerA = NO_POINTER_ID;
    private int menuTwoFingerScrollPointerB = NO_POINTER_ID;
    private float menuTwoFingerScrollLastY;
    private float menuTwoFingerScrollAccumulator;
    private static final float MENU_TWO_FINGER_SCROLL_DP_PER_STEP = 30f;
    /**
     * Menu fake-mouse routing. When the virtual mouse preference is enabled,
     * empty-space touches in Minecraft GUIs act like a small touchpad instead
     * of absolute touchscreen clicks. The cursor itself is drawn by this overlay
     * so devices do not depend on Android/Minecraft cursor visibility.
     */
    private int virtualMousePointerId = NO_POINTER_ID;
    private float virtualMouseDownX;
    private float virtualMouseDownY;
    private float virtualMouseLastX;
    private float virtualMouseLastY;
    private float virtualMouseSpeedMultiplier = 1f;
    /** Shared in-game camera multiplier used by touch look and controller look. */
    private float touchCameraSensitivityMultiplier = 1f;
    private boolean virtualMouseMovedPastSlop;

    /** In-game hotbar touch routing. Keep this separate from camera/buttons. */
    private int hotbarPointerId = NO_POINTER_ID;
    private int hotbarLastSlot = -1;
    private boolean hotbarDoubleTapConsumed;
    private int lastHotbarTapSlot = -1;
    private long lastHotbarTapTimeMs;

    @NonNull private final SparseArray<TouchControlButtonView> controlPointerTargets = new SparseArray<>();
    @Nullable private TouchControlButtonView capturedPhysicalMouseControlTarget;
    private long capturedPhysicalMouseControlDownTime;
    /**
     * Pointer IDs owned by the TouchController mod proxy. Launcher buttons and
     * TouchController contacts can coexist because ownership is decided once on
     * ACTION_DOWN and kept stable until that pointer is released.
     */
    @NonNull private final SparseBooleanArray touchControllerPointerTargets = new SparseBooleanArray();
    @NonNull private final SparseArray<MousePassThroughState> mousePassThroughPointers = new SparseArray<>();
    @NonNull private final SparseArray<SwipeGestureState> swipeGesturePointers = new SparseArray<>();

    private final Paint hotbarDebugFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hotbarDebugStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hotbarDebugSlotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hotbarDebugTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint virtualCursorFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint virtualCursorStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint virtualCursorShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path virtualCursorPath = new Path();

    private static final class MousePassThroughState {
        final float downX;
        final float downY;
        float lastX;
        float lastY;
        boolean movedPastSlop;

        MousePassThroughState(float x, float y) {
            downX = x;
            downY = y;
            lastX = x;
            lastY = y;
        }
    }

    private static final class SwipeGestureState {
        @NonNull final TouchControlButtonView primaryControl;
        @Nullable TouchControlButtonView activeSwipeControl;

        SwipeGestureState(@NonNull TouchControlButtonView primaryControl) {
            this.primaryControl = primaryControl;
            this.activeSwipeControl = primaryControl;
        }
    }

    private static final class ScaledControlItem {
        @NonNull final TouchControlButtonView button;
        final float baseX;
        final float baseY;
        final float baseWidth;
        final float baseHeight;
        final float scaledWidth;
        final float scaledHeight;
        float x;
        float y;

        ScaledControlItem(
                @NonNull TouchControlButtonView button,
                float baseX,
                float baseY,
                float baseWidth,
                float baseHeight,
                float scaledWidth,
                float scaledHeight,
                float x,
                float y
        ) {
            this.button = button;
            this.baseX = baseX;
            this.baseY = baseY;
            this.baseWidth = baseWidth;
            this.baseHeight = baseHeight;
            this.scaledWidth = scaledWidth;
            this.scaledHeight = scaledHeight;
            this.x = x;
            this.y = y;
        }
    }

    public TouchControlsOverlay(@NonNull Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(true);
        setMotionEventSplittingEnabled(true);
        setWillNotDraw(false);

        hotbarDebugFillPaint.setColor(0x44FFEB3B);
        hotbarDebugFillPaint.setStyle(Paint.Style.FILL);

        hotbarDebugStrokePaint.setColor(Color.YELLOW);
        hotbarDebugStrokePaint.setStyle(Paint.Style.STROKE);
        hotbarDebugStrokePaint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);

        hotbarDebugSlotPaint.setColor(0xCCFF9800);
        hotbarDebugSlotPaint.setStyle(Paint.Style.STROKE);
        hotbarDebugSlotPaint.setStrokeWidth(1.5f * getResources().getDisplayMetrics().density);

        hotbarDebugTextPaint.setColor(Color.WHITE);
        hotbarDebugTextPaint.setTextAlign(Paint.Align.CENTER);
        hotbarDebugTextPaint.setTextSize(12f * getResources().getDisplayMetrics().scaledDensity);
        hotbarDebugTextPaint.setShadowLayer(3f, 0f, 0f, Color.BLACK);

        virtualCursorFillPaint.setColor(Color.WHITE);
        virtualCursorFillPaint.setStyle(Paint.Style.STROKE);
        virtualCursorFillPaint.setStrokeJoin(Paint.Join.ROUND);
        virtualCursorFillPaint.setStrokeCap(Paint.Cap.ROUND);
        virtualCursorFillPaint.setStrokeWidth(2.0f * getResources().getDisplayMetrics().density);

        virtualCursorStrokePaint.setColor(0xFF111111);
        virtualCursorStrokePaint.setStyle(Paint.Style.STROKE);
        virtualCursorStrokePaint.setStrokeJoin(Paint.Join.ROUND);
        virtualCursorStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        virtualCursorStrokePaint.setStrokeWidth(4.2f * getResources().getDisplayMetrics().density);

        // Kept for binary/source compatibility with the previous patch, but the
        // cursor no longer uses a drop shadow. The old shadow was what made the
        // pointer look like two mice stacked on top of each other.
        virtualCursorShadowPaint.setColor(Color.TRANSPARENT);
        virtualCursorShadowPaint.setStyle(Paint.Style.STROKE);
        virtualCursorShadowPaint.setStrokeJoin(Paint.Join.ROUND);
        virtualCursorShadowPaint.setStrokeCap(Paint.Cap.ROUND);
        virtualCursorShadowPaint.setStrokeWidth(0f);

        reloadVirtualCursorBitmapIfNeeded(true);

        cameraTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setPassthroughTarget(@Nullable View passthroughTarget) {
        this.passthroughTarget = passthroughTarget;
        boolean shouldHidePointer = shouldDrawLauncherVirtualCursor();
        androidPointerIconHidden = !shouldHidePointer;
        applyAndroidPointerIconPolicy(shouldHidePointer, true);
    }

    /**
     * Sets the Minecraft viewport used for raw touch/cursor/hotbar coordinate mapping.
     * This never changes the visual touch-control canvas. In centered portrait mode the
     * buttons remain laid out against the full portrait overlay while empty touches are
     * translated only when they land inside the centered Minecraft rectangle.
     */
    public void setInputViewportTarget(@Nullable View target) {
        if (inputViewportTarget == target) {
            invalidate();
            return;
        }
        inputViewportTarget = target;
        clearLastHotbarTap();
        invalidate();
    }

    /**
     * Compatibility alias for source trees that still call the old centered-viewport
     * method. It now affects input mapping only; visual control layout stays fullscreen.
     */
    public void setLayoutViewportTarget(@Nullable View target) {
        setInputViewportTarget(target);
    }

    public void setDualScreenBottomHudHotbarMode(boolean enabled) {
        if (dualScreenBottomHudHotbarMode == enabled) return;
        dualScreenBottomHudHotbarMode = enabled;
        clearLastHotbarTap();
        // The bottom display is the user's dedicated touch deck. A connected controller
        // must not auto-hide it; only the explicit touch-controls Off setting may do that.
        refreshControllerAutoHideState();
        rebuildWhenSized();
        postInvalidateOnAnimation();
    }

    public void setMinecraftOptionsFile(@Nullable File minecraftOptionsFile) {
        this.minecraftOptionsFile = minecraftOptionsFile;
        this.cachedMinecraftOptionsFile = minecraftOptionsFile != null && minecraftOptionsFile.isFile()
                ? minecraftOptionsFile
                : null;
        this.lastMinecraftOptionsResolveAtMs = 0L;
        MinecraftGuiScaleResolver.clearCache();
        invalidate();
    }

    public void setAppMenuListener(@Nullable AppMenuListener appMenuListener) {
        this.appMenuListener = appMenuListener;
    }

    public void setEditorPanelHideRequest(@Nullable Runnable editorPanelHideRequest) {
        this.editorPanelHideRequest = editorPanelHideRequest;
    }

    public void setEditorPreviewListener(@Nullable EditorPreviewListener editorPreviewListener) {
        this.editorPreviewListener = editorPreviewListener;
    }

    /** Apply the editor's current in-memory layout without writing it again. */
    public void applyEditorLayoutPreviewFrom(@NonNull TouchControlsOverlay editorOverlay) {
        if (editorOverlay == this) return;

        String snapshot = editorOverlay.snapshotLayoutSafely();
        if (snapshot == null || snapshot.trim().isEmpty()) return;

        try {
            restoreEditSnapshot(snapshot);
            rebuildWhenSized();
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to apply live touch-editor preview", throwable);
        }
    }

    /**
     * Temporarily hides one control in the runtime overlay while the dialog overlay
     * moves, resizes, or edits that same control. The editor copy remains visible,
     * so no stale button is left behind at the old position.
     */
    public void setEditorControlPreviewSuppressed(
            @NonNull String controlId,
            boolean suppressed
    ) {
        if (controlId.trim().isEmpty()) return;

        boolean changed = suppressed
                ? editorPreviewSuppressedControlIds.add(controlId)
                : editorPreviewSuppressedControlIds.remove(controlId);
        if (changed) applyControlsVisualState();
    }

    public void clearEditorControlPreviewSuppressions() {
        if (editorPreviewSuppressedControlIds.isEmpty()) return;
        editorPreviewSuppressedControlIds.clear();
        applyControlsVisualState();
    }

    private void notifyEditorLayoutPreviewChanged() {
        if (!editMode) return;
        EditorPreviewListener listener = editorPreviewListener;
        if (listener == null) return;
        try {
            listener.onEditorLayoutPreviewChanged(this);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to synchronize live touch-editor preview", throwable);
        }
    }

    private void notifyEditorControlPreviewSuppressed(
            @NonNull String controlId,
            boolean suppressed
    ) {
        if (!editMode) return;
        EditorPreviewListener listener = editorPreviewListener;
        if (listener == null) return;
        try {
            listener.onEditorControlPreviewSuppressed(controlId, suppressed);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to update touch-editor control preview", throwable);
        }
    }

    private void beginGeometryPreviewSuppression(@NonNull TouchControlData data) {
        String controlId = data.id == null ? "" : data.id.trim();
        if (controlId.isEmpty()) return;

        if (activeGeometryPreviewControlId != null
                && !activeGeometryPreviewControlId.equals(controlId)) {
            notifyEditorControlPreviewSuppressed(activeGeometryPreviewControlId, false);
        }
        activeGeometryPreviewControlId = controlId;
        notifyEditorControlPreviewSuppressed(controlId, true);
    }

    private void releaseGeometryPreviewSuppression() {
        String controlId = activeGeometryPreviewControlId;
        activeGeometryPreviewControlId = null;
        if (controlId == null || controlId.trim().isEmpty()) return;

        TouchControlButtonView selected = selectedEditButton;
        boolean stillSelected = selected != null
                && controlId.equals(selected.getData().id);
        if (!stillSelected) {
            notifyEditorControlPreviewSuppressed(controlId, false);
        }
    }

    public void setEditMode(boolean editMode) {
        this.editMode = editMode;
        clearRuntimeTouchRouting();
        if (editMode) {
            hideKeySenderKeyboard();
        } else {
            AlertDialog dialog = activeEditDialog;
            activeEditDialog = null;
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
            releaseGeometryPreviewSuppression();
            setSelectedEditButton(null);
        }
        rebuildWhenSized();
    }

    public boolean isEditMode() {
        return editMode;
    }

    public static void setRecordingArtworkSuppressed(boolean suppressed) {
        if (suppressed) {
            RECORDING_ARTWORK_SUPPRESSED.set(Boolean.TRUE);
        } else {
            RECORDING_ARTWORK_SUPPRESSED.remove();
        }
    }

    private static boolean isRecordingArtworkSuppressed() {
        return Boolean.TRUE.equals(RECORDING_ARTWORK_SUPPRESSED.get());
    }

    /**
     * True from the moment a per-button editor starts opening until its window has
     * fully closed. The in-game editor uses this to ignore transient dialog-window
     * inset/size changes that must never be treated as a new touch-control canvas.
     */
    public boolean isControlEditPanelActive() {
        return activeEditDialog != null;
    }

    public void setControlsVisible(boolean visible) {
        controlsVisible = visible;
        setVisibility(VISIBLE);
        refreshControllerAutoHideState();
    }

    private void registerControllerAutoHideListener() {
        if (controllerInputListenerRegistered) {
            refreshControllerAutoHideState();
            return;
        }

        Object service = getContext().getSystemService(Context.INPUT_SERVICE);
        if (!(service instanceof InputManager)) {
            controllerInputManager = null;
            refreshControllerAutoHideState();
            return;
        }

        controllerInputManager = (InputManager) service;
        try {
            controllerInputManager.registerInputDeviceListener(
                    controllerInputDeviceListener,
                    controllerAutoHideHandler
            );
            controllerInputListenerRegistered = true;
        } catch (Throwable throwable) {
            controllerInputManager = null;
            controllerInputListenerRegistered = false;
            Logging.e(TAG, "Unable to register controller auto-hide listener", throwable);
        }
        refreshControllerAutoHideState();
    }

    private void unregisterControllerAutoHideListener() {
        if (controllerInputManager != null && controllerInputListenerRegistered) {
            try {
                controllerInputManager.unregisterInputDeviceListener(controllerInputDeviceListener);
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to unregister controller auto-hide listener", throwable);
            }
        }
        controllerInputListenerRegistered = false;
        controllerInputManager = null;
        controllerAutoHideHandler.removeCallbacksAndMessages(null);
    }

    private void refreshControllerAutoHideState() {
        // Match single-screen semantics in dual-screen mode too: when the user enables
        // “Hide touch controls while controller attached”, a connected controller wins
        // over the normal bottom-screen touch-control visibility setting.
        boolean nextAutoHidden = ControlsPreferences.isAutoHideTouchControlsWithControllerEnabled(getContext())
                && hasConnectedGameController();
        boolean changed = controllerAutoHidden != nextAutoHidden;
        controllerAutoHidden = nextAutoHidden;

        if (changed && controllerAutoHidden) {
            clearRuntimeTouchRouting();
        }
        if (changed) {
            Logging.i(
                    TAG,
                    "Controller touch auto-hide active=" + controllerAutoHidden
            );
        }
        applyControlsVisualState();
    }

    private boolean hasConnectedGameController() {
        try {
            for (int deviceId : InputDevice.getDeviceIds()) {
                InputDevice device = InputDevice.getDevice(deviceId);
                if (isGameControllerDevice(device)) return true;
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to scan Android controllers for touch auto-hide", throwable);
        }
        return false;
    }

    private static boolean isGameControllerDevice(@Nullable InputDevice device) {
        if (device == null) return false;

        int sources;
        try {
            sources = device.getSources();
        } catch (Throwable ignored) {
            return false;
        }

        if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) return true;
        if ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return true;

        String name;
        try {
            name = device.getName();
        } catch (Throwable ignored) {
            name = "";
        }
        String lowerName = name == null ? "" : name.toLowerCase(Locale.US);
        boolean controllerName = lowerName.contains("controller")
                || lowerName.contains("gamepad")
                || lowerName.contains("joystick")
                || lowerName.contains("xbox")
                || lowerName.contains("playstation")
                || lowerName.contains("dualsense")
                || lowerName.contains("dualshock")
                || lowerName.contains("8bitdo")
                || lowerName.contains("gamesir")
                || lowerName.contains("razer")
                || lowerName.contains("odin")
                || lowerName.contains("retroid")
                || lowerName.contains("anbernic");

        if (controllerName) return true;

        boolean dpadSource = (sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
        if (!dpadSource) return false;

        try {
            return device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_NONE;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void refreshButtonVisuals() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof TouchControlButtonView) {
                ((TouchControlButtonView) child).refreshVisualState();
            }
        }
        postInvalidateOnAnimation();
    }

    public void refreshButtonGeometry() {
        rebuildWhenSized();
    }

    public float getProfileGlobalOpacity() {
        return clamp(layoutData.globalOpacity, 0f, 1f);
    }

    public void previewProfileGlobalOpacity(float opacity) {
        layoutData.globalOpacity = clamp(opacity, 0f, 1f);
        layoutData.migrateGlobalOpacityFromPreferences = false;
        ControlsPreferences.setGlobalOpacity(getContext(), layoutData.globalOpacity);
        refreshButtonVisuals();
        notifyEditorLayoutPreviewChanged();
    }

    public void applyProfileGlobalOpacity(float opacity) {
        previewProfileGlobalOpacity(opacity);
        saveLayout();
    }

    public int getProfileGlobalButtonScalePercent() {
        return clampProfileButtonScale(layoutData.globalButtonScalePercent);
    }

    public boolean isProfileVirtualMouseEnabled() {
        return layoutData.virtualMouseEnabled;
    }

    public void setProfileVirtualMouseEnabled(boolean enabled) {
        layoutData.virtualMouseEnabled = enabled;
        layoutData.migrateVirtualMouseFromPreferences = false;
        ControlsPreferences.setVirtualMouseLaunchSessionEnabled(getContext(), enabled);
        saveLayout();
        refreshButtonVisuals();
    }

    /**
     * Applies the current game-session cursor state after a touch-control layout
     * is loaded. This deliberately does not save the state into the profile, so
     * the launcher setting remains a launch default and the in-game touch button
     * remains the session override.
     */
    public void applyVirtualMouseLaunchSessionState() {
        boolean enabled = ControlsPreferences.getVirtualMouseLaunchSessionEnabled(getContext());
        layoutData.virtualMouseEnabled = enabled;
        layoutData.migrateVirtualMouseFromPreferences = false;
        ControlsPreferences.setVirtualMouseEnabled(getContext(), enabled);

        // Force the existing cursor state transition to run even when the profile
        // happened to contain the same value as the launch default.
        lastVirtualMousePreference = !enabled;
        boolean grabbed = isMouseGrabbed();
        updateVirtualMousePreferenceState(enabled, grabbed);
        applyAndroidPointerIconPolicy(enabled && !grabbed && !shouldLetControllerModOwnCursor(), true);
        refreshButtonVisuals();
        postInvalidateOnAnimation();
    }

    public boolean isProfileSnapControlsEnabled() {
        return layoutData.snapControlsEnabled;
    }

    public void setProfileSnapControlsEnabled(boolean enabled) {
        layoutData.snapControlsEnabled = enabled;
        layoutData.migrateSnapControlsFromPreferences = false;
        ControlsPreferences.setSnapControlsEnabled(getContext(), enabled);
        saveLayout();
    }

    public void beginGlobalButtonScaleChange() {
        pushUndoSnapshot();
    }

    public void applyGlobalButtonScalePercent(int percent) {
        layoutData.globalButtonScalePercent = clampProfileButtonScale(percent);
        layoutData.migrateGlobalButtonScaleFromPreferences = false;
        ControlsPreferences.setGlobalButtonScalePercent(getContext(), layoutData.globalButtonScalePercent);
        rebuildWhenSized();
        notifyEditorLayoutPreviewChanged();
    }

    public void finishGlobalButtonScaleChange(int percent) {
        applyGlobalButtonScalePercent(percent);
        saveLayout();
    }

    private boolean migrateAndApplyProfileSettings() {
        boolean migrated = false;
        boolean hasMissingGlobalSettings = layoutData.migrateGlobalOpacityFromPreferences
                || layoutData.migrateGlobalButtonScaleFromPreferences
                || layoutData.migrateVirtualMouseFromPreferences
                || layoutData.migrateSnapControlsFromPreferences;

        // The old launcher had only one global value for these settings. Import that
        // value into the currently selected legacy profile exactly once. Every other
        // old profile starts from the JSON defaults instead of inheriting whichever
        // profile happened to be used immediately before it.
        boolean importLegacyGlobalSettings = hasMissingGlobalSettings && claimLegacyGlobalSettingsMigration();

        if (layoutData.migrateGlobalOpacityFromPreferences) {
            if (importLegacyGlobalSettings) {
                layoutData.globalOpacity = clamp(ControlsPreferences.getGlobalOpacity(getContext()), 0f, 1f);
            }
            layoutData.migrateGlobalOpacityFromPreferences = false;
            migrated = true;
        }
        if (layoutData.migrateGlobalButtonScaleFromPreferences) {
            if (importLegacyGlobalSettings) {
                layoutData.globalButtonScalePercent = clampProfileButtonScale(
                        ControlsPreferences.getGlobalButtonScalePercent(getContext())
                );
            }
            layoutData.migrateGlobalButtonScaleFromPreferences = false;
            migrated = true;
        }
        if (layoutData.migrateVirtualMouseFromPreferences) {
            if (importLegacyGlobalSettings) {
                layoutData.virtualMouseEnabled = ControlsPreferences.isVirtualMouseEnabled(getContext());
            }
            layoutData.migrateVirtualMouseFromPreferences = false;
            migrated = true;
        }
        if (layoutData.migrateSnapControlsFromPreferences) {
            if (importLegacyGlobalSettings) {
                layoutData.snapControlsEnabled = ControlsPreferences.isSnapControlsEnabled(getContext());
            }
            layoutData.migrateSnapControlsFromPreferences = false;
            migrated = true;
        }

        SharedPreferences mousePrefs = mousePassThroughPrefs();
        SharedPreferences swipePrefs = swipeGesturePrefs();
        for (TouchControlData control : layoutData.controls) {
            if (control.migrateMousePassThroughFromPreferences) {
                String key = mousePassThroughPreferenceKey(control);
                if (mousePrefs.contains(key)) {
                    control.mousePassThrough = mousePrefs.getBoolean(key, false);
                    mousePrefs.edit().remove(key).apply();
                }
                control.migrateMousePassThroughFromPreferences = false;
                migrated = true;
            }
            if (control.migrateSwipeGestureFromPreferences) {
                String key = swipeGesturePreferenceKey(control);
                if (swipePrefs.contains(key)) {
                    control.swipeGesture = swipePrefs.getBoolean(key, false);
                    swipePrefs.edit().remove(key).apply();
                }
                control.migrateSwipeGestureFromPreferences = false;
                migrated = true;
            }
        }
        applyProfileSettingsToRuntimePreferences();
        return migrated;
    }

    private boolean claimLegacyGlobalSettingsMigration() {
        SharedPreferences prefs = getContext().getSharedPreferences(PROFILE_SETTINGS_MIGRATION_PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(PROFILE_SETTINGS_MIGRATION_V1, false)) return false;
        return prefs.edit().putBoolean(PROFILE_SETTINGS_MIGRATION_V1, true).commit();
    }

    private void applyProfileSettingsToRuntimePreferences() {
        layoutData.globalOpacity = clamp(layoutData.globalOpacity, 0f, 1f);
        layoutData.globalButtonScalePercent = clampProfileButtonScale(layoutData.globalButtonScalePercent);
        ControlsPreferences.setGlobalOpacity(getContext(), layoutData.globalOpacity);
        ControlsPreferences.setGlobalButtonScalePercent(getContext(), layoutData.globalButtonScalePercent);
        ControlsPreferences.setVirtualMouseEnabled(getContext(), layoutData.virtualMouseEnabled);
        ControlsPreferences.setSnapControlsEnabled(getContext(), layoutData.snapControlsEnabled);
    }

    private void clearProfileMigrationFlags() {
        layoutData.migrateGlobalOpacityFromPreferences = false;
        layoutData.migrateGlobalButtonScaleFromPreferences = false;
        layoutData.migrateVirtualMouseFromPreferences = false;
        layoutData.migrateSnapControlsFromPreferences = false;
        for (TouchControlData control : layoutData.controls) {
            control.migrateMousePassThroughFromPreferences = false;
            control.migrateSwipeGestureFromPreferences = false;
        }
    }

    private static int clampProfileButtonScale(int value) {
        return Math.max(
                ControlsPreferences.MIN_GLOBAL_BUTTON_SCALE_PERCENT,
                Math.min(ControlsPreferences.MAX_GLOBAL_BUTTON_SCALE_PERCENT, value)
        );
    }

    public void toggleControlVisible() {
        setControlsVisible(!controlsVisible);
        ControlsPreferences.setTouchControlsEnabled(getContext(), controlsVisible);
    }

    public void loadSelectedLayout() {
        layoutFile = TouchControlsStore.getSelectedLayoutFile(getContext());
        layoutData = TouchControlsStore.loadLayout(layoutFile);
        resetDrawerRuntimeState();
        boolean migrated = migrateAndApplyProfileSettings();
        clearEditHistory();
        markEditorSessionSaved();
        rebuildWhenSized();
        if (migrated) saveLayout();
    }

    public void loadLayout(@NonNull File file) {
        layoutFile = file;
        layoutData = TouchControlsStore.loadLayout(file);
        resetDrawerRuntimeState();
        ControlsPreferences.setSelectedLayoutPath(getContext(), file.getAbsolutePath());
        boolean migrated = migrateAndApplyProfileSettings();
        clearEditHistory();
        markEditorSessionSaved();
        rebuildWhenSized();
        if (migrated) saveLayout();
    }

    public void saveLayout() {
        try {
            normalizeDrawerMembership();
            normalizeUnstablePixelLayoutBeforeSave();
            File target = layoutFile != null ? layoutFile : TouchControlsStore.getSelectedLayoutFile(getContext());
            TouchControlsStore.saveLayout(target, layoutData);
            layoutFile = target;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to save touch controls", throwable);
            String message = throwable.getMessage();
            if (message == null || message.trim().isEmpty()) {
                message = throwable.getClass().getSimpleName();
            }
            Toast.makeText(getContext(), "Unable to save touch controls: " + message, Toast.LENGTH_LONG).show();
        }
        // The editor and game use separate Android windows. Keep the real game
        // overlay on the exact same in-memory state before exposing a control again.
        notifyEditorLayoutPreviewChanged();
    }

    public void markEditorSessionSaved() {
        editorSessionStartSnapshot = snapshotLayoutSafely();
    }

    public boolean hasEditorSessionChanges() {
        String currentSnapshot = snapshotLayoutSafely();
        if (currentSnapshot == null) {
            return !undoHistory.isEmpty();
        }
        return editorSessionStartSnapshot == null || !currentSnapshot.equals(editorSessionStartSnapshot);
    }

    public void discardEditorSessionChanges() {
        if (editorSessionStartSnapshot == null) return;
        try {
            restoreEditSnapshot(editorSessionStartSnapshot);
            saveLayout();
            clearEditHistory();
            markEditorSessionSaved();
            rebuildWhenSized();
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to discard touch editor changes", throwable);
            Toast.makeText(getContext(), "Unable to discard touch changes.", Toast.LENGTH_LONG).show();
        }
    }

    public void addControl(@NonNull TouchControlData data) {
        pushUndoSnapshot();
        layoutData.controls.add(data);
        saveLayout();
        rebuildWhenSized();
    }

    public boolean undoLastChange() {
        if (undoHistory.isEmpty()) return false;
        try {
            String current = snapshotLayout();
            String previous = undoHistory.removeLast();
            pushBounded(redoHistory, current);
            restoreEditSnapshot(previous);
            saveLayout();
            rebuildWhenSized();
            return true;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to undo touch layout change", throwable);
            return false;
        }
    }

    public boolean redoLastChange() {
        if (redoHistory.isEmpty()) return false;
        try {
            String current = snapshotLayout();
            String next = redoHistory.removeLast();
            pushBounded(undoHistory, current);
            restoreEditSnapshot(next);
            saveLayout();
            rebuildWhenSized();
            return true;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to redo touch layout change", throwable);
            return false;
        }
    }

    /**
     * Undo while the per-button side panel is open without dismissing and recreating
     * the dialog. The normal editor-level undo path rebuilds every child view, which
     * invalidates the view/data references captured by the open panel. This variant
     * restores the snapshot into the existing button instances when the control set is
     * structurally unchanged (the normal case while editing one button).
     */
    private boolean undoLastChangeInPlace() {
        if (undoHistory.isEmpty()) return false;
        try {
            String current = snapshotLayout();
            String previous = undoHistory.removeLast();
            if (!restoreEditSnapshotPreservingViews(previous)) {
                undoHistory.addLast(previous);
                return false;
            }
            pushBounded(redoHistory, current);
            saveLayout();
            return true;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to undo touch layout change in open panel", throwable);
            return false;
        }
    }

    private boolean redoLastChangeInPlace() {
        if (redoHistory.isEmpty()) return false;
        try {
            String current = snapshotLayout();
            String next = redoHistory.removeLast();
            if (!restoreEditSnapshotPreservingViews(next)) {
                redoHistory.addLast(next);
                return false;
            }
            pushBounded(undoHistory, current);
            saveLayout();
            return true;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to redo touch layout change in open panel", throwable);
            return false;
        }
    }

    /**
     * Restores a snapshot while preserving the TouchControlData and
     * TouchControlButtonView objects currently referenced by an open properties panel.
     * Structural changes are rejected here and remain handled by the normal full
     * rebuild path after the panel is closed.
     */
    private boolean restoreEditSnapshotPreservingViews(@NonNull String snapshot) throws Exception {
        String currentSnapshot = snapshotLayout();
        ArrayList<TouchControlButtonView> existingButtons = new ArrayList<>();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof TouchControlButtonView) {
                existingButtons.add((TouchControlButtonView) child);
            }
        }

        restoreEditSnapshot(snapshot);
        if (layoutData.controls.size() != existingButtons.size()) {
            restoreEditSnapshot(currentSnapshot);
            return false;
        }

        ArrayList<TouchControlButtonView> matchedButtons = new ArrayList<>();
        for (TouchControlData restored : layoutData.controls) {
            TouchControlButtonView match = null;
            for (TouchControlButtonView candidate : existingButtons) {
                if (restored.id.equals(candidate.getData().id)) {
                    match = candidate;
                    break;
                }
            }
            if (match == null || matchedButtons.contains(match)) {
                restoreEditSnapshot(currentSnapshot);
                return false;
            }
            matchedButtons.add(match);
        }

        for (int i = 0; i < layoutData.controls.size(); i++) {
            TouchControlData restored = layoutData.controls.get(i);
            TouchControlButtonView button = matchedButtons.get(i);
            TouchControlData existing = button.getData();
            copyControlState(restored, existing);
            layoutData.controls.set(i, existing);
        }

        for (TouchControlButtonView button : matchedButtons) {
            TouchControlData control = button.getData();
            applyControlPreview(button, control);
            button.setEditMode(editMode);
            button.setVisibility(shouldShowControlButton(control) ? VISIBLE : INVISIBLE);
        }
        if (selectedEditButton != null) {
            selectedEditButton.setEditSelected(true);
        }
        invalidate();
        return true;
    }

    private static void copyControlState(
            @NonNull TouchControlData source,
            @NonNull TouchControlData target
    ) {
        target.id = source.id;
        target.label = source.label;
        target.action = source.action;
        target.keyCode = source.keyCode;
        target.keyCodes = source.keyCodes != null ? source.keyCodes.clone() : new int[0];
        target.keySlots = source.keySlots != null ? source.keySlots.clone() : new int[0];
        target.mouseButton = source.mouseButton;
        target.scrollY = source.scrollY;
        target.x = source.x;
        target.y = source.y;
        target.width = source.width;
        target.height = source.height;
        target.sizePercent = source.sizePercent;
        target.opacity = source.opacity;
        target.cornerRadius = source.cornerRadius;
        target.strokeWidth = source.strokeWidth;
        target.strokeColor = source.strokeColor;
        target.backgroundColor = source.backgroundColor;
        target.imageUri = source.imageUri;
        target.imageMode = source.imageMode;
        target.imageScalePercent = source.imageScalePercent;
        target.imageOffsetXPercent = source.imageOffsetXPercent;
        target.imageOffsetYPercent = source.imageOffsetYPercent;
        target.toggle = source.toggle;
        target.visibleInGame = source.visibleInGame;
        target.visibleInMenu = source.visibleInMenu;
        target.visibleWhenControlsHidden = source.visibleWhenControlsHidden;
        target.mousePassThrough = source.mousePassThrough;
        target.swipeGesture = source.swipeGesture;
        target.bitmapTag = source.bitmapTag;
        target.migrateMousePassThroughFromPreferences = source.migrateMousePassThroughFromPreferences;
        target.migrateSwipeGestureFromPreferences = source.migrateSwipeGestureFromPreferences;
        target.joystickAbsolute = source.joystickAbsolute;
        target.joystickForwardLock = source.joystickForwardLock;
        target.joystickDeadzonePercent = source.joystickDeadzonePercent;
        target.drawerParentId = source.drawerParentId;
        target.drawerOrientation = source.drawerOrientation;
        target.drawerOpenByDefault = source.drawerOpenByDefault;
        target.rawX = source.rawX;
        target.rawY = source.rawY;
        target.positionAnchorX = source.positionAnchorX;
        target.positionAnchorY = source.positionAnchorY;
    }

    @NonNull
    private static TouchControlData copyControlForHistory(@NonNull TouchControlData source) {
        TouchControlData copy = new TouchControlData();
        copyControlState(source, copy);
        return copy;
    }

    private static final class PanelEditSnapshot {
        @NonNull final TouchControlData control;
        final boolean virtualMouseEnabled;

        PanelEditSnapshot(@NonNull TouchControlData control, boolean virtualMouseEnabled) {
            this.control = copyControlForHistory(control);
            this.virtualMouseEnabled = virtualMouseEnabled;
        }
    }

    /**
     * Per-button live history used while the side editor is open. The normal editor
     * history intentionally records the completed dialog as one operation; this stack
     * records each slider drag, resize gesture, checkbox, spinner, and text-edit
     * transaction so Undo works before OK is pressed.
     */
    private final class ActivePanelEditHistory {
        @NonNull final TouchControlButtonView view;
        @NonNull final TouchControlData data;
        @NonNull final ArrayDeque<PanelEditSnapshot> undo = new ArrayDeque<>();
        @NonNull final ArrayDeque<PanelEditSnapshot> redo = new ArrayDeque<>();
        @Nullable Button undoButton;
        @Nullable Button redoButton;
        @Nullable Runnable refreshUi;
        @Nullable View pendingTextField;
        @Nullable PanelEditSnapshot pendingTextBaseline;
        boolean restoring;
        boolean geometryDirty;
        int restoreCallbackSuppressionGeneration;

        ActivePanelEditHistory(
                @NonNull TouchControlButtonView view,
                @NonNull TouchControlData data
        ) {
            this.view = view;
            this.data = data;
        }

        void bindButtons(@NonNull Button undoButton, @NonNull Button redoButton) {
            this.undoButton = undoButton;
            this.redoButton = redoButton;
            updateButtons();
        }

        void setRefreshUi(@NonNull Runnable refreshUi) {
            this.refreshUi = refreshUi;
        }

        void beginDiscreteChange() {
            if (restoring) return;
            finishTextEdit();
            pushPanelSnapshot(undo, capture());
            redo.clear();
            updateButtons();
        }

        void beginTextEdit(@NonNull View field) {
            if (restoring || pendingTextField == field) return;
            finishTextEdit();
            pendingTextField = field;
            pendingTextBaseline = capture();
        }

        void finishTextEdit() {
            PanelEditSnapshot baseline = pendingTextBaseline;
            pendingTextBaseline = null;
            pendingTextField = null;
            if (restoring || baseline == null) return;

            PanelEditSnapshot current = capture();
            if (!samePanelSnapshot(baseline, current)) {
                pushPanelSnapshot(undo, baseline);
                redo.clear();
            }
            updateButtons();
        }

        boolean undo() {
            finishTextEdit();
            if (undo.isEmpty()) return false;
            PanelEditSnapshot current = capture();
            PanelEditSnapshot previous = undo.removeLast();
            pushPanelSnapshot(redo, current);
            restore(previous);
            updateButtons();
            return true;
        }

        boolean redo() {
            finishTextEdit();
            if (redo.isEmpty()) return false;
            PanelEditSnapshot current = capture();
            PanelEditSnapshot next = redo.removeLast();
            pushPanelSnapshot(undo, current);
            restore(next);
            updateButtons();
            return true;
        }

        private PanelEditSnapshot capture() {
            return new PanelEditSnapshot(data, isProfileVirtualMouseEnabled());
        }

        private void restore(@NonNull PanelEditSnapshot snapshot) {
            final int suppressionGeneration = ++restoreCallbackSuppressionGeneration;
            restoring = true;
            try {
                copyControlState(snapshot.control, data);
                if (isProfileVirtualMouseEnabled() != snapshot.virtualMouseEnabled) {
                    setProfileVirtualMouseEnabled(snapshot.virtualMouseEnabled);
                }
                applyControlPreview(view, data);
                syncActiveEditGeometryUi(data);
                if (refreshUi != null) refreshUi.run();
                postInvalidateOnAnimation();
            } finally {
                /*
                 * Spinner.setSelection() and a few CompoundButton implementations can
                 * deliver their callbacks after the synchronous panel refresh returns.
                 * Clearing restoring immediately therefore makes those delayed no-op
                 * callbacks look like a brand-new user edit, which clears the Redo
                 * stack as soon as Undo finishes.
                 *
                 * Keep history callbacks suppressed through two main-loop turns. This
                 * lets Android finish selection/layout notifications before normal live
                 * editing resumes, while still leaving the panel responsive by the next
                 * frame the user can interact with it.
                 */
                gestureHandler.post(() -> gestureHandler.post(() -> {
                    if (restoreCallbackSuppressionGeneration != suppressionGeneration) return;
                    restoring = false;
                    updateButtons();
                }));
            }
        }

        private void updateButtons() {
            if (undoButton != null) {
                updateCompactEditActionState(
                        undoButton,
                        !undo.isEmpty() || pendingTextBaseline != null
                );
            }
            if (redoButton != null) {
                updateCompactEditActionState(redoButton, !redo.isEmpty());
            }
        }
    }

    private static void pushPanelSnapshot(
            @NonNull ArrayDeque<PanelEditSnapshot> target,
            @NonNull PanelEditSnapshot snapshot
    ) {
        if (!target.isEmpty() && samePanelSnapshot(target.peekLast(), snapshot)) return;
        target.addLast(snapshot);
        while (target.size() > MAX_PANEL_EDIT_HISTORY) target.removeFirst();
    }

    private static boolean samePanelSnapshot(
            @Nullable PanelEditSnapshot first,
            @Nullable PanelEditSnapshot second
    ) {
        if (first == second) return true;
        if (first == null || second == null) return false;
        if (first.virtualMouseEnabled != second.virtualMouseEnabled) return false;
        try {
            return first.control.toJson().toString().equals(second.control.toJson().toString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void pushUndoSnapshot() {
        pushUndoSnapshot(snapshotLayoutSafely());
    }

    private void pushUndoSnapshot(@Nullable String snapshot) {
        if (snapshot == null || snapshot.trim().isEmpty()) return;
        redoHistory.clear();
        if (!undoHistory.isEmpty() && snapshot.equals(undoHistory.peekLast())) return;
        pushBounded(undoHistory, snapshot);
    }

    private void pushBounded(@NonNull ArrayDeque<String> target, @NonNull String snapshot) {
        if (!target.isEmpty() && snapshot.equals(target.peekLast())) return;
        target.addLast(snapshot);
        while (target.size() > MAX_EDIT_HISTORY) {
            target.removeFirst();
        }
    }

    @Nullable
    private String snapshotLayoutSafely() {
        try {
            return snapshotLayout();
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to snapshot touch layout", throwable);
            return null;
        }
    }

    @NonNull
    private String snapshotLayout() throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", "DroidBridgeTouchControlsEditorState");
        root.put("version", 1);
        root.put("globalButtonScalePercent", getProfileGlobalButtonScalePercent());
        root.put("globalOpacity", getProfileGlobalOpacity());
        root.put("layout", layoutData.toJson());
        return root.toString();
    }

    private void restoreEditSnapshot(@NonNull String snapshot) throws Exception {
        JSONObject root = new JSONObject(snapshot);
        JSONObject layout = root.optJSONObject("layout");
        if (layout != null) {
            layoutData = TouchControlsLayoutData.fromJson(layout);
            if (root.has("globalButtonScalePercent") && !layout.has("profileSettings")) {
                layoutData.globalButtonScalePercent = clampProfileButtonScale(root.optInt(
                        "globalButtonScalePercent",
                        TouchControlsLayoutData.DEFAULT_PROFILE_BUTTON_SCALE_PERCENT
                ));
            }
            if (root.has("globalOpacity") && !layout.has("profileSettings")) {
                layoutData.globalOpacity = clamp(
                        (float) root.optDouble("globalOpacity", TouchControlsLayoutData.DEFAULT_PROFILE_OPACITY),
                        0f,
                        1f
                );
            }
            clearProfileMigrationFlags();
            applyProfileSettingsToRuntimePreferences();
            return;
        }

        // Backward compatibility for undo entries captured before editor-state
        // snapshots stored the global scale preference alongside the layout JSON.
        layoutData = TouchControlsLayoutData.fromJson(root);
        migrateAndApplyProfileSettings();
    }

    private void clearEditHistory() {
        undoHistory.clear();
        redoHistory.clear();
    }

    @NonNull
    public TouchControlsLayoutData getLayoutData() {
        return layoutData;
    }

    private void rebuildWhenSized() {
        if (getWidth() <= 1 || getHeight() <= 1) {
            if (!rebuildPending) {
                rebuildPending = true;
                post(() -> {
                    rebuildPending = false;
                    if (getWidth() > 1 && getHeight() > 1) {
                        rebuild();
                    }
                });
            }
            return;
        }
        rebuild();
    }

    private void rebuild() {
        if (getWidth() <= 1 || getHeight() <= 1) {
            rebuildWhenSized();
            return;
        }

        syncDrawerRuntimeStateWithLayout();
        removeAllViews();
        int parentWidth = Math.max(1, getWidth());
        int parentHeight = Math.max(1, getHeight());
        // The control profile always uses the full overlay canvas. Centered portrait
        // affects only Minecraft input mapping, never button placement/sizing.
        int canvasWidth = parentWidth;
        int canvasHeight = parentHeight;
        boolean migratedResponsiveCanvas = migrateNativeLayoutToResponsiveCanvas(canvasWidth, canvasHeight);
        LayoutMetrics metrics = layoutMetrics(canvasWidth, canvasHeight);
        float density = getResources().getDisplayMetrics().density;
        float scale = renderGlobalButtonScaleMultiplier();
        ArrayList<ScaledControlItem> scaledItems = new ArrayList<>();

        for (TouchControlData control : layoutData.controls) {
            if (!editMode && !shouldCreateControlButton(control)) continue;
            TouchControlButtonView button = new TouchControlButtonView(getContext(), control, this);
            button.setEditMode(editMode);
            button.setVisibility(shouldShowControlButton(control) ? VISIBLE : INVISIBLE);
            button.setResponsiveVisualScale(layoutData.usesResponsiveCanvas()
                    ? metrics.sizeScale * renderGlobalButtonScaleMultiplier()
                    : 0f);

            int baseWidth = baseControlScreenWidth(metrics, control, canvasWidth);
            int baseHeight = baseControlScreenHeight(metrics, control, canvasHeight);
            int width = scaledControlScreenWidth(metrics, control, canvasWidth);
            int height = scaledControlScreenHeight(metrics, control, canvasHeight);
            if (TouchControlActions.JOYSTICK.equals(control.action)) {
                int baseSquare = Math.max(1, Math.min(Math.min(canvasWidth, canvasHeight), Math.max(baseWidth, baseHeight)));
                int scaledSquare = Math.max(1, Math.min(Math.min(canvasWidth, canvasHeight), Math.max(width, height)));
                baseWidth = baseSquare;
                baseHeight = baseSquare;
                width = scaledSquare;
                height = scaledSquare;
            }

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
            addView(button, params);

            float fallbackX = metrics.toScreenX(control, baseWidth);
            float fallbackY = metrics.toScreenY(control, baseHeight);
            float baseX = control.rawX == null
                    ? fallbackX
                    : ExpressionResolver.resolve(control.rawX, fallbackX, canvasWidth, canvasHeight, baseWidth, baseHeight, density, expressionPreferredScale(), metrics.formulaPixelScale, metrics.formulaDpScale);
            float baseY = control.rawY == null
                    ? fallbackY
                    : ExpressionResolver.resolve(control.rawY, fallbackY, canvasWidth, canvasHeight, baseWidth, baseHeight, density, expressionPreferredScale(), metrics.formulaPixelScale, metrics.formulaDpScale);

            float resolvedX = scaledControlScreenX(
                    baseX, baseWidth, width, canvasWidth, scale, control.positionAnchorX);
            float resolvedY = scaledControlScreenY(
                    baseY, baseHeight, height, canvasHeight, scale, control.positionAnchorY);
            scaledItems.add(new ScaledControlItem(
                    button,
                    baseX,
                    baseY,
                    baseWidth,
                    baseHeight,
                    width,
                    height,
                    resolvedX,
                    resolvedY
            ));
        }

        // Pojav-family formulas already encode the intended edge anchoring. Do not
        // run DroidBridge's cluster nudge on imported profiles or the whole layout can
        // shift away from the compatible third-party launcher position.
        if (!layoutData.usesOtherLauncherProfile()) {
            keepAttachedScaledControlsInsideScreen(scaledItems, canvasWidth, canvasHeight);
        }
        for (ScaledControlItem item : scaledItems) {
            // Controls are intentionally laid out against the full overlay canvas,
            // including centered-portrait mode. Only raw Minecraft touches are
            // translated through the centered game viewport.
            item.button.setX(item.x);
            item.button.setY(item.y);
        }

        if (keySenderKeyboardVisible && !editMode) {
            attachKeySenderKeyboardView();
        }

        if (migratedResponsiveCanvas && !responsiveCanvasSavePending) {
            responsiveCanvasSavePending = true;
            post(() -> {
                responsiveCanvasSavePending = false;
                saveLayout();
            });
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        registerControllerAutoHideListener();
        rebuildWhenSized();
        post(() -> applyAndroidPointerIconPolicy(shouldUseLauncherVirtualCursorNoPolicy(), true));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) rebuildWhenSized();
        post(() -> applyAndroidPointerIconPolicy(shouldUseLauncherVirtualCursorNoPolicy(), true));
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        if (isRecordingArtworkSuppressed()) return;
        super.dispatchDraw(canvas);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (isRecordingArtworkSuppressed()) return;
        super.onDraw(canvas);
        if (TouchControllerModCompat.isActive() && !editMode) return;
        drawVirtualMouseCursor(canvas);
        drawHotbarHitboxDebug(canvas);
    }

    private void drawVirtualMouseCursor(@NonNull Canvas canvas) {
        // In dual-screen bottom-deck mode the visible cursor belongs on the Minecraft
        // display, not on the 1240x1080 control panel. The GameCursorOverlay lives in
        // the other Presentation/window, so hasGameCursorOverlayInViewTree() cannot see
        // it from here. Suppress this local duplicate explicitly while still keeping
        // the Android system pointer hidden when DroidBridge owns the virtual cursor.
        if (dualScreenBottomHudHotbarMode && passthroughTarget != null) {
            applyAndroidPointerIconPolicy(!shouldLetControllerModOwnCursor()
                    && shouldUseLauncherVirtualCursorNoPolicy());
            return;
        }

        // GameCursorOverlay is now the single visible menu cursor renderer for
        // both controller cursor mode and touch virtual-mouse mode. If it is
        // attached, do not draw a second cursor from this overlay; otherwise
        // Redmagic/handheld devices show the old arrow or a doubled cursor.
        // Keep the Android pointer hide policy active while the virtual mouse
        // is in GUI/menu mode, but leave the pixels to GameCursorOverlay.
        if (hasGameCursorOverlayInViewTree()) {
            applyAndroidPointerIconPolicy(!shouldLetControllerModOwnCursor()
                    && shouldUseLauncherVirtualCursorNoPolicy());
            return;
        }

        if (!shouldDrawLauncherVirtualCursor()) return;

        reloadVirtualCursorBitmapIfNeeded(false);
        ensureVirtualMouseCursorInBounds();

        float cursorX = bridgeCursorToViewX(virtualCursorBridgeX);
        float cursorY = bridgeCursorToViewY(virtualCursorBridgeY);
        if (Float.isNaN(cursorX) || Float.isInfinite(cursorX)) cursorX = getWidth() / 2f;
        if (Float.isNaN(cursorY) || Float.isInfinite(cursorY)) cursorY = getHeight() / 2f;

        GameResolutionSettings.DisplayBounds displayBounds = currentGameDisplayBounds();
        cursorX = clamp(
                cursorX,
                displayBounds.left,
                displayBounds.left + Math.max(0f, displayBounds.width - 1f)
        );
        cursorY = clamp(
                cursorY,
                displayBounds.top,
                displayBounds.top + Math.max(0f, displayBounds.height - 1f)
        );

        Bitmap bitmap = virtualCursorBitmap;
        if (bitmap != null && !bitmap.isRecycled()) {
            int maxExtent = Math.max(1, Math.round(
                    28f
                            * getResources().getDisplayMetrics().density
                            * ControlsPreferences.getMouseCursorSizePercent(getContext())
                            / 100f
            ));
            int drawWidth = MouseCursorBitmapUtils.getDrawWidth(bitmap, maxExtent);
            int drawHeight = MouseCursorBitmapUtils.getDrawHeight(bitmap, maxExtent);
            boolean centeredHotspot = MouseCursorBitmapUtils.usesCenteredHotspot(loadedVirtualCursorStyle);
            float left = centeredHotspot ? cursorX - (drawWidth / 2f) : cursorX;
            float top = centeredHotspot ? cursorY - (drawHeight / 2f) : cursorY;

            // Keep the GLFW/Minecraft cursor coordinate as the click hotspot.
            // Resizing or importing an image changes only the pixels around it.
            RectF dst = new RectF(left, top, left + drawWidth, top + drawHeight);
            canvas.drawBitmap(bitmap, null, dst, null);
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        float arm = 13f * density;
        float gap = 4f * density;
        float ringRadius = 7.5f * density;

        // direct-renderer virtual mouse: a clean crosshair instead of a large arrow.
        // Draw black first as an outline, then white on top. No offset shadow,
        // because the old shadow looked like a second cursor on some screens.
        drawVirtualCursorCrosshair(canvas, cursorX, cursorY, arm, gap, ringRadius, virtualCursorStrokePaint);
        drawVirtualCursorCrosshair(canvas, cursorX, cursorY, arm, gap, ringRadius, virtualCursorFillPaint);
    }

    private void drawVirtualCursorCrosshair(
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

    private void reloadVirtualCursorBitmapIfNeeded(boolean force) {
        String style = ControlsPreferences.getMouseCursorStyle(getContext());
        String customPath = ControlsPreferences.getCustomMouseCursorPath(getContext());
        int sizePercent = ControlsPreferences.getMouseCursorSizePercent(getContext());

        boolean sameStyle = style.equals(loadedVirtualCursorStyle);
        boolean samePath = customPath == null
                ? loadedVirtualCursorCustomPath == null
                : customPath.equals(loadedVirtualCursorCustomPath);
        boolean sameSize = sizePercent == loadedVirtualCursorSizePercent;
        if (!force && sameStyle && samePath && sameSize) return;

        loadedVirtualCursorStyle = style;
        loadedVirtualCursorCustomPath = customPath;
        loadedVirtualCursorSizePercent = sizePercent;

        Bitmap previous = virtualCursorBitmap;
        virtualCursorBitmap = MouseCursorBitmapUtils.loadCursorBitmap(getContext(), style, customPath);
        if (previous != null && previous != virtualCursorBitmap && !previous.isRecycled()) {
            previous.recycle();
        }
        postInvalidateOnAnimation();
    }

    private void applyAndroidPointerIconPolicy(boolean hidePointerIcon) {
        applyAndroidPointerIconPolicy(hidePointerIcon, false);
    }

    private void applyAndroidPointerIconPolicy(boolean hidePointerIcon, boolean force) {
        applyAndroidPointerIconPolicy(hidePointerIcon, force, true);
    }

    private void applyAndroidPointerIconPolicy(boolean hidePointerIcon, boolean force, boolean scheduleReapply) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;

        // When hiding the system/native pointer, do not skip re-applying just
        // because our last request was also hidden. Several devices reset the
        // pointer icon when the focused child view changes between the overlay,
        // TextureView, SurfaceView, and MinecraftGLSurface. Re-applying to the
        // entire view tree is what prevents the native arrow from sitting on top
        // of the launcher-drawn direct-renderer crosshair.
        if (!hidePointerIcon && !force && !androidPointerIconHidden) return;

        try {
            PointerIcon icon = hidePointerIcon
                    ? invisiblePointerIcon()
                    : PointerIcon.getSystemIcon(getContext(), PointerIcon.TYPE_DEFAULT);

            applyPointerIconToViewTree(getRootView(), icon);
            applyPointerIconToViewTree(this, icon);
            applyPointerIconToViewTree(passthroughTarget, icon);

            ViewParent parent = getParent();
            if (parent instanceof View) {
                applyPointerIconToViewTree((View) parent, icon);
            }

            androidPointerIconHidden = hidePointerIcon;
            if (hidePointerIcon && scheduleReapply) schedulePointerIconReapply();
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to update Android pointer icon", throwable);
        }
    }

    @NonNull
    private PointerIcon invisiblePointerIcon() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                if (transparentPointerIcon == null) {
                    Bitmap transparent = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
                    transparentPointerIcon = PointerIcon.create(transparent, 0f, 0f);
                }
                if (transparentPointerIcon != null) return transparentPointerIcon;
            } catch (Throwable ignored) {
            }
        }
        return PointerIcon.getSystemIcon(getContext(), PointerIcon.TYPE_NULL);
    }

    private void applyPointerIconToViewTree(@Nullable View view, @NonNull PointerIcon icon) {
        if (view == null) return;

        try {
            view.setPointerIcon(icon);
        } catch (Throwable ignored) {
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyPointerIconToViewTree(group.getChildAt(i), icon);
            }
        }
    }

    private void schedulePointerIconReapply() {
        if (pointerIconReapplyPending) return;
        pointerIconReapplyPending = true;
        postDelayed(() -> reapplyHiddenPointerIconIfNeeded(false), 90L);
        postDelayed(() -> reapplyHiddenPointerIconIfNeeded(true), 320L);
    }

    private void reapplyHiddenPointerIconIfNeeded(boolean finalPass) {
        if (shouldUseLauncherVirtualCursorNoPolicy()) {
            applyAndroidPointerIconPolicy(true, true, false);
        }
        if (finalPass) pointerIconReapplyPending = false;
    }

    private boolean shouldUseLauncherVirtualCursorNoPolicy() {
        return !editMode
                && !shouldLetControllerModOwnCursor()
                && ControlsPreferences.isVirtualMouseEnabled(getContext())
                && !updateMouseGrabState();
    }

    private boolean shouldDrawLauncherVirtualCursor() {
        if (editMode || shouldLetControllerModOwnCursor()) {
            virtualCursorInitialized = false;
            applyAndroidPointerIconPolicy(false);
            return false;
        }

        boolean enabled = ControlsPreferences.isVirtualMouseEnabled(getContext());
        boolean grabbed = updateMouseGrabState();
        updateVirtualMousePreferenceState(enabled, grabbed);

        boolean draw = enabled && !grabbed;
        applyAndroidPointerIconPolicy(draw);
        return draw;
    }

    private boolean shouldLetControllerModOwnCursor() {
        return ControllerModCompat.shouldHideLauncherCursorForControllerMod();
    }

    private void updateVirtualMousePreferenceState(boolean enabled, boolean grabbed) {
        if (enabled == lastVirtualMousePreference) return;
        lastVirtualMousePreference = enabled;
        if (enabled) {
            if (!grabbed) resetVirtualMouseCursorToCenter(true);
            else virtualCursorInitialized = false;
        } else {
            virtualCursorInitialized = false;
            applyAndroidPointerIconPolicy(false);
        }
    }

    /**
     * Returns the current grab state and resets the fake cursor exactly once when
     * Minecraft opens a GUI. This prevents camera movement during normal gameplay
     * from dragging the menu cursor away from the center before the menu appears.
     */
    private boolean updateMouseGrabState() {
        boolean grabbed = isMouseGrabbed();
        if (grabbed != lastKnownMouseGrabbed) {
            lastKnownMouseGrabbed = grabbed;
            if (!grabbed && ControlsPreferences.isVirtualMouseEnabled(getContext())) {
                resetVirtualMouseCursorToCenter(true);
            } else if (grabbed) {
                // Do not suppress the first fresh camera drag after returning to the game.
                // The re-grab transition cancels any existing menu/GUI pointer below;
                // blocking all deltas for a fixed time made users need to touch twice.
                suppressCameraDeltaUntilUptimeMs = 0L;
                cancelCameraPointer(true);
                cancelAllMousePassThroughPointers();
                cancelVirtualMousePointer();
                passthroughPointerId = NO_POINTER_ID;
                applyAndroidPointerIconPolicy(false);
            }
            applyControlsVisualStateForGrabState(grabbed);
            postInvalidateOnAnimation();
        }
        return grabbed;
    }

    private void drawHotbarHitboxDebug(@NonNull Canvas canvas) {
        if (!ControlsPreferences.isHotbarHitboxDebugEnabled(getContext())) return;

        File optionsFile = resolveMinecraftOptionsFile();
        TouchHotbarHitbox.Result hitbox = TouchHotbarHitbox.calculate(
                getContext(),
                optionsFile,
                getWidth(),
                getHeight(),
                resolveGameBufferWidth(),
                resolveGameBufferHeight()
        );

        RectF touchBounds = hitbox.touchBounds;
        RectF hotbarBounds = hitbox.hotbarBounds;

        canvas.drawRect(touchBounds, hotbarDebugFillPaint);
        canvas.drawRect(touchBounds, hotbarDebugStrokePaint);

        for (int i = 0; i <= TouchHotbarHitbox.SLOT_COUNT; i++) {
            float x = hotbarBounds.left + (i * hitbox.slotWidth);
            canvas.drawLine(x, touchBounds.top, x, touchBounds.bottom, hotbarDebugSlotPaint);
        }

        float textY = touchBounds.top - (6f * getResources().getDisplayMetrics().density);
        if (textY < hotbarDebugTextPaint.getTextSize() + 2f) {
            textY = touchBounds.bottom + hotbarDebugTextPaint.getTextSize() + 4f;
        }

        canvas.drawText(
                "Hotbar hitbox  scale=" + formatScale(hitbox.scale)
                        + "  src=" + hitbox.scaleSourceLabel()
                        + "  mcGui=" + hitbox.minecraftGuiScale
                        + "  override=" + hitbox.overrideScale,
                hotbarBounds.centerX(),
                textY,
                hotbarDebugTextPaint
        );

        canvas.drawText(
                "render=" + formatScale(hitbox.renderScaleX) + "x/" + formatScale(hitbox.renderScaleY)
                        + "  res=" + hitbox.resolutionScalePercent + "%"
                        + "  buffer=" + Math.round(hitbox.gameBufferWidth) + "x" + Math.round(hitbox.gameBufferHeight),
                hotbarBounds.centerX(),
                textY + hotbarDebugTextPaint.getTextSize() + (3f * getResources().getDisplayMetrics().density),
                hotbarDebugTextPaint
        );

        canvas.drawText(
                "options=" + debugOptionsFileName(optionsFile),
                hotbarBounds.centerX(),
                textY + (hotbarDebugTextPaint.getTextSize() * 2f) + (6f * getResources().getDisplayMetrics().density),
                hotbarDebugTextPaint
        );

        for (int slot = 0; slot < TouchHotbarHitbox.SLOT_COUNT; slot++) {
            float centerX = hotbarBounds.left + (slot * hitbox.slotWidth) + (hitbox.slotWidth / 2f);
            canvas.drawText(
                    String.valueOf(slot + 1),
                    centerX,
                    touchBounds.centerY() + (hotbarDebugTextPaint.getTextSize() / 3f),
                    hotbarDebugTextPaint
            );
        }

        postInvalidateOnAnimation();
    }

    @Override
    public boolean dispatchTouchEvent(@NonNull MotionEvent event) {
        // Keep the full-screen overlay attached whether visual controls are shown or hidden.
        // Hidden controls should still allow hotbar taps, camera dragging, and menu passthrough
        // through the same safe routing path instead of dropping to the raw SurfaceView path.
        if (editMode) {
            return dispatchEditModeTouchEvent(event);
        }

        if (dispatchKeySenderKeyboardTouch(event)) {
            return true;
        }

        if (isHardwarePointerEvent(event)
                && !LauncherPreferences.isAndroidVirtualPhysicalMouse(getContext())) {
            return dispatchWholeTouchEventToPassthrough(event) || super.dispatchTouchEvent(event);
        }

        if (handleMenuTwoFingerScroll(event)) {
            return true;
        }

        if (TouchControllerModCompat.isActive()) {
            return dispatchTouchControllerHybridEvent(event);
        }

        int action = event.getActionMasked();
        int actionIndex = event.getActionIndex();

        if (ControlsPreferences.isHotbarHitboxDebugEnabled(getContext())) {
            invalidate();
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                clearRuntimeTouchRouting();
                return routePointerDown(event, actionIndex);

            case MotionEvent.ACTION_POINTER_DOWN:
                routePointerDown(event, actionIndex);
                return hasActiveTouchRoute();

            case MotionEvent.ACTION_MOVE:
                dispatchActiveControlPointers(event, MotionEvent.ACTION_MOVE);
                dispatchActiveSwipeGesturePointers(event);
                dispatchActiveMousePassThroughPointers(event);
                dispatchActiveHotbarPointer(event);
                dispatchActiveCameraPointer(event);
                dispatchActiveVirtualMousePointer(event);
                dispatchActivePassthroughPointer(event, MotionEvent.ACTION_MOVE);
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                routePointerUp(event, actionIndex, false);
                return true;

            case MotionEvent.ACTION_UP:
                routePointerUp(event, actionIndex, true);
                return true;

            case MotionEvent.ACTION_CANCEL:
                dispatchCancelToControlPointers(event);
                cancelAllSwipeGesturePointers(event);
                cancelAllMousePassThroughPointers();
                cancelCameraPointer(true);
                cancelVirtualMousePointer();
                dispatchActivePassthroughPointer(event, MotionEvent.ACTION_CANCEL);
                clearRuntimeTouchRouting();
                return true;

            default:
                dispatchActivePassthroughPointer(event, MotionEvent.ACTION_MOVE);
                return true;
        }
    }

    @Override
    public boolean dispatchGenericMotionEvent(@NonNull MotionEvent event) {
        if (!editMode && isHardwarePointerEvent(event)) {
            if (LauncherPreferences.isAndroidVirtualPhysicalMouse(getContext())) {
                // Android Virtual Mouse deliberately keeps normal Android View routing first,
                // so the OS pointer can hover/click DroidBridge controls. If no Android-side
                // view consumes the event, pass it through to Minecraft as usual.
                if (super.dispatchGenericMotionEvent(event)) return true;
                return dispatchWholeGenericEventToPassthrough(event);
            }
            return dispatchWholeGenericEventToPassthrough(event) || super.dispatchGenericMotionEvent(event);
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        if (!editMode && keySenderKeyboardVisible && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                hideKeySenderKeyboard();
            }
            return true;
        }

        if (!editMode && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            // Android navigation Back / gesture Back is the recovery shortcut for the
            // launcher in-game controls dialog. Do not forward it into Minecraft as
            // Escape, otherwise Minecraft opens its own pause menu and users who hid
            // the cog have no reliable way back to DroidBridge settings.
            if (MinecraftGLSurface.shouldRouteBackKeyToMinecraft(event)) {
                MinecraftGLSurface minecraftSurface = findMinecraftSurfaceTarget();
                if (minecraftSurface != null && minecraftSurface.handleKeyEventFromActivity(event)) {
                    return true;
                }
                if (passthroughTarget != null && passthroughTarget.dispatchKeyEvent(event)) {
                    return true;
                }
            }

            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (openLauncherInGameDialogFromBack()) {
                    return true;
                }
            } else if (appMenuListener != null) {
                return true;
            }
        }

        if (!editMode && MinecraftGLSurface.shouldRouteBackKeyToMinecraft(event)) {
            MinecraftGLSurface minecraftSurface = findMinecraftSurfaceTarget();
            if (minecraftSurface != null && minecraftSurface.handleKeyEventFromActivity(event)) {
                return true;
            }
            if (passthroughTarget != null && passthroughTarget.dispatchKeyEvent(event)) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * Opens the same launcher in-game controls dialog as the floating cog/touch menu.
     * GameActivity can also call this from OnBackPressedDispatcher so Android 13+
     * predictive/gesture Back is handled even when no View receives KEYCODE_BACK.
     */
    public boolean openLauncherInGameDialogFromBack() {
        if (editMode || appMenuListener == null) return false;

        hideKeySenderKeyboard();
        cancelRuntimeTouchRoutingForLauncherDialog();

        appMenuListener.onTouchControlsMenuRequested();
        return true;
    }

    private void cancelRuntimeTouchRoutingForLauncherDialog() {
        controlPointerTargets.clear();
        swipeGesturePointers.clear();
        cancelAllMousePassThroughPointers();
        cancelCameraPointer(true);
        cancelVirtualMousePointer();
        clearRuntimeTouchRouting();
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        return editMode && super.onTouchEvent(event);
    }

    private boolean dispatchEditModeTouchEvent(@NonNull MotionEvent event) {
        int action = event.getActionMasked();
        int actionIndex = event.getActionIndex();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                controlPointerTargets.clear();
                return dispatchEditControlDown(event, actionIndex) || super.dispatchTouchEvent(event);

            case MotionEvent.ACTION_POINTER_DOWN:
                return dispatchEditControlDown(event, actionIndex) || super.dispatchTouchEvent(event);

            case MotionEvent.ACTION_MOVE:
                if (controlPointerTargets.size() > 0) {
                    dispatchActiveControlPointers(event, MotionEvent.ACTION_MOVE);
                    return true;
                }
                return super.dispatchTouchEvent(event);

            case MotionEvent.ACTION_POINTER_UP:
                if (dispatchEditPointerUp(event, actionIndex)) return true;
                return super.dispatchTouchEvent(event);

            case MotionEvent.ACTION_UP:
                if (dispatchEditPointerUp(event, actionIndex)) {
                    controlPointerTargets.clear();
                    return true;
                }
                controlPointerTargets.clear();
                return super.dispatchTouchEvent(event);

            case MotionEvent.ACTION_CANCEL:
                if (controlPointerTargets.size() > 0) {
                    dispatchCancelToControlPointers(event);
                    controlPointerTargets.clear();
                    return true;
                }
                return super.dispatchTouchEvent(event);

            default:
                return super.dispatchTouchEvent(event);
        }
    }

    private boolean dispatchEditControlDown(@NonNull MotionEvent event, int pointerIndex) {
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) return false;
        float x = event.getX(pointerIndex);
        float y = event.getY(pointerIndex);

        // Route every editor button touch by pointer ID instead of relying on the
        // normal Android child dispatch path. This keeps taps reliable even with
        // translated controls, overlapping buttons, and the resize handle hanging
        // outside the lower-right corner. Prefer the resize handle when it overlaps
        // the normal button body.
        TouchControlButtonView control = findResizeHandleControlUnder(x, y);
        if (control == null) {
            control = findControlUnder(x, y);
        }
        if (control == null) return false;

        // The side panel is non-modal. A tap on another button may switch the
        // editor immediately when the current button is still untouched. Once any
        // value has changed, keep the current explicit OK/Cancel protection.
        if (activeEditDialog != null
                && activeEditDialog.isShowing()
                && selectedEditButton != null
                && control != selectedEditButton) {
            requestEditTargetSwitch(control);
            return true;
        }

        int pointerId = event.getPointerId(pointerIndex);
        controlPointerTargets.put(pointerId, control);
        dispatchSinglePointerToControl(event, pointerIndex, MotionEvent.ACTION_DOWN, control);
        return true;
    }

    private boolean dispatchEditPointerUp(@NonNull MotionEvent event, int pointerIndex) {
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) return false;
        int pointerId = event.getPointerId(pointerIndex);
        TouchControlButtonView control = controlPointerTargets.get(pointerId);
        if (control == null) return false;

        dispatchSinglePointerToControl(event, pointerIndex, MotionEvent.ACTION_UP, control);
        controlPointerTargets.remove(pointerId);
        return true;
    }

    @Nullable
    private TouchControlButtonView findResizeHandleControlUnder(float x, float y) {
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View child = getChildAt(i);
            if (!(child instanceof TouchControlButtonView)) continue;
            if (child.getVisibility() != VISIBLE) continue;
            TouchControlButtonView control = (TouchControlButtonView) child;
            if (control.isInResizeHandleFromParent(x, y)) {
                return control;
            }
        }
        return null;
    }


    @Override
    public void onChanged() {
        // Synchronize the completed geometry while the old runtime copy is still
        // suppressed, then reveal it at the new location.
        saveLayout();
        releaseGeometryPreviewSuppression();
    }

    @Override
    public void onMoveStarted(@NonNull TouchControlButtonView view, @NonNull TouchControlData data) {
        beginGeometryPreviewSuppression(data);
        ActivePanelEditHistory panelHistory = activePanelEditHistory;
        if (panelHistory != null && panelHistory.data == data) {
            panelHistory.beginDiscreteChange();
            panelHistory.geometryDirty = true;
        } else {
            pushUndoSnapshot();
        }
    }

    @Override
    public void onMoveRequested(
            @NonNull TouchControlButtonView view,
            @NonNull TouchControlData data,
            float proposedX,
            float proposedY
    ) {
        float[] snapped = resolveDraggedPosition(view, proposedX, proposedY);
        LayoutMetrics metrics = layoutMetrics(getWidth(), getHeight());

        float globalScale = renderGlobalButtonScaleMultiplier();
        float scaledWidth = Math.max(1f, view.getWidth());
        float scaledHeight = Math.max(1f, view.getHeight());
        float baseWidth = Math.max(1f, scaledWidth / Math.max(0.001f, globalScale));
        float baseHeight = Math.max(1f, scaledHeight / Math.max(0.001f, globalScale));
        float baseX = unscaledControlScreenX(snapped[0], baseWidth, scaledWidth, getWidth(), globalScale, data.positionAnchorX);
        float baseY = unscaledControlScreenY(snapped[1], baseHeight, scaledHeight, getHeight(), globalScale, data.positionAnchorY);

        // Persist the anchor chosen by the control's new visible location before
        // converting back to source-canvas units. Re-inferring the anchor from old
        // x/y values after Save/Undo/Redo was what made controls jump on surfaces
        // whose size differs from the launcher editor.
        data.positionAnchorX = metrics.horizontalAnchorForScreenPosition(baseX, baseWidth);
        data.positionAnchorY = metrics.verticalAnchorForScreenPosition(baseY, baseHeight);

        // X/Y are stored in the layout's own units at the unscaled 100% geometry.
        // The global scale renderer then moves the button centers together, so
        // undo/redo and per-button edits do not get polluted by the live scale.
        data.x = metrics.fromScreenX(data, baseX, baseWidth);
        data.y = metrics.fromScreenY(data, baseY, baseHeight);
        data.rawX = null;
        data.rawY = null;

        view.setX(snapped[0]);
        view.setY(snapped[1]);
        syncActiveEditGeometryUi(data);
    }

    @Override
    public void onResizeStarted(@NonNull TouchControlButtonView view, @NonNull TouchControlData data) {
        beginGeometryPreviewSuppression(data);
        ActivePanelEditHistory panelHistory = activePanelEditHistory;
        if (panelHistory != null && panelHistory.data == data) {
            panelHistory.beginDiscreteChange();
            panelHistory.geometryDirty = true;
        } else {
            pushUndoSnapshot();
        }
    }

    @Override
    public void onResizeRequested(
            @NonNull TouchControlButtonView view,
            @NonNull TouchControlData data,
            float proposedScreenWidth,
            float proposedScreenHeight
    ) {
        if (getWidth() <= 1 || getHeight() <= 1) return;

        float left = view.getX();
        float top = view.getY();
        int minSize = Math.max(dp(32f), Math.round(32f * getResources().getDisplayMetrics().density));
        int maxWidth = Math.max(minSize, Math.round(getWidth() - left));
        int maxHeight = Math.max(minSize, Math.round(getHeight() - top));
        int newWidth = Math.max(minSize, Math.min(maxWidth, Math.round(proposedScreenWidth)));
        int newHeight = Math.max(minSize, Math.min(maxHeight, Math.round(proposedScreenHeight)));
        if (TouchControlActions.JOYSTICK.equals(data.action)) {
            int square = Math.max(minSize, Math.min(Math.min(maxWidth, maxHeight), Math.max(newWidth, newHeight)));
            newWidth = square;
            newHeight = square;
        }

        LayoutMetrics metrics = layoutMetrics(getWidth(), getHeight());
        float oldScreenWidth = Math.max(1f, view.getWidth());
        float oldScreenHeight = Math.max(1f, view.getHeight());
        float ratio = Math.min(newWidth / oldScreenWidth, newHeight / oldScreenHeight);

        float globalScale = renderGlobalButtonScaleMultiplier();
        data.width = Math.max(24f, metrics.fromScreenWidth(newWidth / globalScale));
        data.height = Math.max(24f, metrics.fromScreenHeight(newHeight / globalScale));
        if (TouchControlActions.JOYSTICK.equals(data.action)) {
            float squareUnits = Math.max(data.width, data.height);
            data.width = squareUnits;
            data.height = squareUnits;
        }
        data.sizePercent = clamp(data.sizePercent * ratio, 30f, 250f);

        // Resizing changes the source-side width/height used by right/center and
        // bottom/center mappings. Re-capture the current visible top-left using
        // the new dimensions so a rebuild cannot shift the control.
        float baseScreenWidth = Math.max(1f, newWidth / Math.max(0.001f, globalScale));
        float baseScreenHeight = Math.max(1f, newHeight / Math.max(0.001f, globalScale));
        float baseScreenX = unscaledControlScreenX(
                left,
                baseScreenWidth,
                newWidth,
                getWidth(),
                globalScale,
                data.positionAnchorX
        );
        float baseScreenY = unscaledControlScreenY(
                top,
                baseScreenHeight,
                newHeight,
                getHeight(),
                globalScale,
                data.positionAnchorY
        );
        data.positionAnchorX = metrics.horizontalAnchorForScreenPosition(
                baseScreenX,
                baseScreenWidth
        );
        data.positionAnchorY = metrics.verticalAnchorForScreenPosition(
                baseScreenY,
                baseScreenHeight
        );
        data.x = metrics.fromScreenX(data, baseScreenX, baseScreenWidth);
        data.y = metrics.fromScreenY(data, baseScreenY, baseScreenHeight);
        data.rawX = null;
        data.rawY = null;

        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null) {
            params.width = newWidth;
            params.height = newHeight;
            view.setLayoutParams(params);
        }
        view.refreshVisualState();
        view.requestLayout();
        view.invalidate();
        syncActiveEditGeometryUi(data);
    }

    @Override
    public void onEditRequested(@NonNull TouchControlButtonView view, @NonNull TouchControlData data) {
        if (!isAttachedToWindow()) return;

        AlertDialog existing = activeEditDialog;
        if (existing != null && existing.isShowing()) {
            if (selectedEditButton == view) return;
            requestEditTargetSwitch(view);
            return;
        }

        Runnable hideRequest = editorPanelHideRequest;
        if (hideRequest != null) {
            try {
                hideRequest.run();
            } catch (Throwable ignored) {
            }
        } else if (getContext() instanceof ControlsEditorActivity) {
            ((ControlsEditorActivity) getContext()).hideGlobalEditorPanelForControlEdit();
        }
        setSelectedEditButton(view);
        view.bringToFront();
        showEditDialog(view, data);
    }

    private void requestEditTargetSwitch(@NonNull TouchControlButtonView target) {
        EditPanelSwitchController controller = activeEditPanelSwitchController;
        if (controller == null || controller.hasUnsavedChanges()) {
            Toast.makeText(
                    getContext(),
                    "Finish or cancel the changed button first.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        String targetId = target.getData().id;
        controller.closeForSwitch();
        reopenEditDialogForControlId(targetId);
    }

    private void setSelectedEditButton(@Nullable TouchControlButtonView selected) {
        TouchControlButtonView previous = selectedEditButton;
        if (previous != selected && previous != null) {
            String previousId = previous.getData().id;
            if (activeGeometryPreviewControlId == null
                    || !activeGeometryPreviewControlId.equals(previousId)) {
                notifyEditorControlPreviewSuppressed(previousId, false);
            }
        }

        selectedEditButton = selected;

        if (previous != selected && selected != null) {
            notifyEditorControlPreviewSuppressed(selected.getData().id, true);
        }

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof TouchControlButtonView) {
                ((TouchControlButtonView) child).setEditSelected(child == selected);
            }
        }
        postInvalidateOnAnimation();
    }

    @Override
    public void onMenuRequested() {
        if (appMenuListener != null) {
            appMenuListener.onTouchControlsMenuRequested();
        }
    }

    @Override
    public void onToggleControlsRequested() {
        toggleControlVisible();
    }

    @Override
    public void onDrawerToggleRequested(
            @NonNull TouchControlButtonView view,
            @NonNull TouchControlData data
    ) {
        if (!TouchControlActions.DRAWER.equals(data.action)) return;

        String drawerId = data.id == null ? "" : data.id.trim();
        if (drawerId.isEmpty()) return;

        boolean expanded;
        if (expandedDrawerIds.contains(drawerId)) {
            expandedDrawerIds.remove(drawerId);
            expanded = false;
            releaseDrawerChildInput(drawerId);
        } else {
            expandedDrawerIds.add(drawerId);
            expanded = true;
        }
        initializedDrawerIds.add(drawerId);
        view.setActivated(expanded);
        applyControlsVisualState();
    }

    @Override
    public void onVirtualMouseToggleRequested() {
        boolean enabled = !isProfileVirtualMouseEnabled();
        setProfileVirtualMouseEnabled(enabled);
        Toast.makeText(
                getContext(),
                enabled ? "Virtual cursor shown for this profile" : "Virtual cursor hidden for this profile",
                Toast.LENGTH_SHORT
        ).show();
    }

    @Override
    public void onKeySenderKeyboardRequested() {
        showKeySenderKeyboard();
    }

    @NonNull
    private float[] resolveDraggedPosition(@NonNull View movingView, float proposedX, float proposedY) {
        int width = Math.max(1, movingView.getWidth());
        int height = Math.max(1, movingView.getHeight());
        float maxX = Math.max(0f, getWidth() - width);
        float maxY = Math.max(0f, getHeight() - height);
        float x = clamp(proposedX, 0f, maxX);
        float y = clamp(proposedY, 0f, maxY);

        if (!ControlsPreferences.isSnapControlsEnabled(getContext())) {
            return new float[]{x, y};
        }

        float threshold = 12f * getResources().getDisplayMetrics().density;
        float bestX = x;
        float bestY = y;
        float bestXDelta = threshold + 1f;
        float bestYDelta = threshold + 1f;

        float[] screenXTargets = new float[]{0f, maxX / 2f, maxX};
        for (float target : screenXTargets) {
            float candidateX = clamp(target, 0f, maxX);
            float delta = Math.abs(x - candidateX);
            if (delta <= threshold
                    && delta < bestXDelta
                    && !positionOverlapsAnotherControl(movingView, candidateX, bestY, width, height)) {
                bestX = candidateX;
                bestXDelta = delta;
            }
        }

        float[] screenYTargets = new float[]{0f, maxY / 2f, maxY};
        for (float target : screenYTargets) {
            float candidateY = clamp(target, 0f, maxY);
            float delta = Math.abs(y - candidateY);
            if (delta <= threshold
                    && delta < bestYDelta
                    && !positionOverlapsAnotherControl(movingView, bestX, candidateY, width, height)) {
                bestY = candidateY;
                bestYDelta = delta;
            }
        }

        for (int i = 0; i < getChildCount(); i++) {
            View other = getChildAt(i);
            if (other == movingView || other.getVisibility() != VISIBLE) continue;
            if (!(other instanceof TouchControlButtonView)) continue;
            if (other.getWidth() <= 0 || other.getHeight() <= 0) continue;

            float otherLeft = other.getX();
            float otherTop = other.getY();
            float otherRight = otherLeft + other.getWidth();
            float otherBottom = otherTop + other.getHeight();
            float otherCenterX = otherLeft + other.getWidth() / 2f;
            float otherCenterY = otherTop + other.getHeight() / 2f;

            // Prefer true adjacent-edge snaps first. Alignment snaps are still allowed,
            // but only when the resulting rectangle does not overlap another button.
            float[] xTargets = new float[]{
                    otherLeft - width,
                    otherRight,
                    otherLeft,
                    otherRight - width,
                    otherCenterX - width / 2f
            };
            for (float target : xTargets) {
                float candidateX = clamp(target, 0f, maxX);
                float delta = Math.abs(x - candidateX);
                if (delta <= threshold
                        && delta < bestXDelta
                        && !positionOverlapsAnotherControl(movingView, candidateX, bestY, width, height)) {
                    bestX = candidateX;
                    bestXDelta = delta;
                }
            }

            float[] yTargets = new float[]{
                    otherTop - height,
                    otherBottom,
                    otherTop,
                    otherBottom - height,
                    otherCenterY - height / 2f
            };
            for (float target : yTargets) {
                float candidateY = clamp(target, 0f, maxY);
                float delta = Math.abs(y - candidateY);
                if (delta <= threshold
                        && delta < bestYDelta
                        && !positionOverlapsAnotherControl(movingView, bestX, candidateY, width, height)) {
                    bestY = candidateY;
                    bestYDelta = delta;
                }
            }
        }

        return new float[]{
                clamp(bestX, 0f, maxX),
                clamp(bestY, 0f, maxY)
        };
    }

    private boolean positionOverlapsAnotherControl(
            @NonNull View movingView,
            float x,
            float y,
            int width,
            int height
    ) {
        float left = x;
        float top = y;
        float right = x + Math.max(1, width);
        float bottom = y + Math.max(1, height);

        for (int i = 0; i < getChildCount(); i++) {
            View other = getChildAt(i);
            if (other == movingView || other.getVisibility() != VISIBLE) continue;
            if (!(other instanceof TouchControlButtonView)) continue;
            if (other.getWidth() <= 0 || other.getHeight() <= 0) continue;

            float otherLeft = other.getX();
            float otherTop = other.getY();
            float otherRight = otherLeft + other.getWidth();
            float otherBottom = otherTop + other.getHeight();
            if (rectanglesOverlap(left, top, right, bottom, otherLeft, otherTop, otherRight, otherBottom)) {
                return true;
            }
        }

        return false;
    }

    private static boolean rectanglesOverlap(
            float left,
            float top,
            float right,
            float bottom,
            float otherLeft,
            float otherTop,
            float otherRight,
            float otherBottom
    ) {
        return left < otherRight
                && right > otherLeft
                && top < otherBottom
                && bottom > otherTop;
    }

    private void showEditDialog(@NonNull TouchControlButtonView editingView, @NonNull TouchControlData data) {
        Context context = getContext();

        String originalId = data.id;
        String originalLabel = data.label;
        String originalAction = data.action;
        int originalKeyCode = data.keyCode;
        int[] originalKeyCodes = data.normalizedKeyCodes().clone();
        int[] originalKeySlots = data.normalizedKeySlots().clone();
        int originalMouseButton = data.mouseButton;
        int originalScrollY = data.scrollY;
        float originalX = data.x;
        float originalY = data.y;
        float originalWidth = data.width;
        float originalHeight = data.height;
        float originalSizePercent = data.sizePercent;
        String originalLayoutSnapshot = snapshotLayoutSafely();
        float originalOpacity = data.opacity;
        float originalCornerRadius = data.cornerRadius;
        float originalStrokeWidth = data.strokeWidth;
        int originalStrokeColor = data.strokeColor;
        int originalBackgroundColor = data.backgroundColor;
        String originalImageUri = data.imageUri;
        String originalImageMode = data.imageMode;
        float originalImageScalePercent = data.imageScalePercent;
        float originalImageOffsetXPercent = data.imageOffsetXPercent;
        float originalImageOffsetYPercent = data.imageOffsetYPercent;
        boolean originalToggle = data.toggle;
        boolean originalVisibleInGame = data.visibleInGame;
        boolean originalVisibleInMenu = data.visibleInMenu;
        boolean originalVisibleWhenControlsHidden = data.visibleWhenControlsHidden;
        boolean originalMousePassThrough = data.mousePassThrough;
        boolean originalSwipeGesture = data.swipeGesture;
        boolean originalVirtualMouseEnabled = isProfileVirtualMouseEnabled();
        boolean originalJoystickAbsolute = data.joystickAbsolute;
        boolean originalJoystickForwardLock = data.joystickForwardLock;
        float originalJoystickDeadzonePercent = data.joystickDeadzonePercent;
        String originalRawX = data.rawX;
        String originalRawY = data.rawY;
        String originalPositionAnchorX = data.positionAnchorX;
        String originalPositionAnchorY = data.positionAnchorY;
        String originalDrawerParentId = data.drawerParentId;
        String originalDrawerOrientation = data.drawerOrientation;
        boolean originalDrawerOpenByDefault = data.drawerOpenByDefault;
        HashSet<String> selectedDrawerMemberIds = drawerMemberIdsFor(data.id);

        LayoutMetrics metrics = layoutMetrics(getWidth(), getHeight());
        int parentWidthUnits = Math.max(1, Math.round(metrics.maxLayoutXUnits()));
        int parentHeightUnits = Math.max(1, Math.round(metrics.maxLayoutYUnits()));

        float liveScale = renderGlobalButtonScaleMultiplier();
        float liveScaledWidth = Math.max(1f, editingView.getWidth());
        float liveScaledHeight = Math.max(1f, editingView.getHeight());
        float liveBaseWidth = Math.max(1f, liveScaledWidth / Math.max(0.001f, liveScale));
        float liveBaseHeight = Math.max(1f, liveScaledHeight / Math.max(0.001f, liveScale));
        float liveBaseX = unscaledControlScreenX(
                editingView.getX(),
                liveBaseWidth,
                liveScaledWidth,
                getWidth(),
                liveScale,
                data.positionAnchorX
        );
        float liveBaseY = unscaledControlScreenY(
                editingView.getY(),
                liveBaseHeight,
                liveScaledHeight,
                getHeight(),
                liveScale,
                data.positionAnchorY
        );
        float initialLayoutX = data.rawX == null
                ? data.x
                : metrics.fromScreenX(data, liveBaseX, liveBaseWidth);
        float initialLayoutY = data.rawY == null
                ? data.y
                : metrics.fromScreenY(data, liveBaseY, liveBaseHeight);

        LinearLayout panelRoot = new LinearLayout(context);
        panelRoot.setOrientation(LinearLayout.VERTICAL);
        panelRoot.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);

        // Use a fully custom header/footer instead of AlertDialog's built-in title and
        // button bar. The framework button bar has a large minimum width and was
        // expanding this side panel to more than half of compact landscape screens.
        TextView panelTitle = new TextView(context);
        panelTitle.setText("Edit touch button");
        panelTitle.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        panelTitle.setTextSize(17f);
        panelTitle.setPadding(dp(14f), dp(8f), dp(14f), 0);
        panelRoot.addView(panelTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // At the responsive 38% drawer width all three quick actions fit cleanly in
        // one compact row. Keeping them together matches the bottom action bar and
        // leaves more vertical room for the actual button properties.
        LinearLayout quickActionGroup = new LinearLayout(context);
        quickActionGroup.setOrientation(LinearLayout.HORIZONTAL);
        quickActionGroup.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        quickActionGroup.setWeightSum(3f);
        quickActionGroup.setPadding(dp(8f), dp(2f), dp(8f), dp(3f));
        // This row is laid out inside a child dialog when editing in game. Reserve
        // its intended height so a transient first-frame measure cannot squeeze it.
        quickActionGroup.setMinimumHeight(dp(35f));

        Button undoButton = compactEditActionButton(context, "UNDO");
        Button redoButton = compactEditActionButton(context, "REDO");
        Button copyButton = compactEditActionButton(context, "COPY");
        ActivePanelEditHistory panelHistory = new ActivePanelEditHistory(editingView, data);
        panelHistory.bindButtons(undoButton, redoButton);
        activePanelEditHistory = panelHistory;
        final boolean[] syncingPanelUi = new boolean[]{false};
        final boolean[] panelControlsReady = new boolean[]{false};
        final boolean[] updatingActionUi = new boolean[]{false};
        final Runnable[] applyPreviewRef = new Runnable[1];
        quickActionGroup.addView(undoButton, new LinearLayout.LayoutParams(
                0,
                dp(30f),
                1f
        ));
        quickActionGroup.addView(redoButton, new LinearLayout.LayoutParams(
                0,
                dp(30f),
                1f
        ));
        quickActionGroup.addView(copyButton, new LinearLayout.LayoutParams(
                0,
                dp(30f),
                1f
        ));
        panelRoot.addView(quickActionGroup, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        ScrollView scrollView = new ScrollView(context);
        scrollView.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);
        int padding = dp(14f);
        layout.setPadding(padding, dp(2f), padding, dp(10f));
        scrollView.addView(layout, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        panelRoot.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        LinearLayout footerActionRow = new LinearLayout(context);
        footerActionRow.setOrientation(LinearLayout.HORIZONTAL);
        footerActionRow.setGravity(Gravity.CENTER_VERTICAL);
        footerActionRow.setWeightSum(3f);
        footerActionRow.setPadding(dp(6f), dp(2f), dp(6f), dp(5f));
        // DELETE / CANCEL / OK are fixed-height controls. Keep their container tall
        // enough even if Android reports a short child-window measure for one frame.
        footerActionRow.setMinimumHeight(dp(45f));

        Button deleteButton = compactEditActionButton(context, "DELETE");
        Button cancelButton = compactEditActionButton(context, "CANCEL");
        Button okButton = compactEditActionButton(context, "OK");
        deleteButton.setTextColor(LauncherDialogStyle.COLOR_ERROR);
        cancelButton.setTextColor(LauncherDialogStyle.COLOR_ACCENT);
        okButton.setTextColor(LauncherDialogStyle.COLOR_ACCENT);

        footerActionRow.addView(deleteButton, new LinearLayout.LayoutParams(
                0, dp(38f), 1f
        ));
        footerActionRow.addView(cancelButton, new LinearLayout.LayoutParams(
                0, dp(38f), 1f
        ));
        footerActionRow.addView(okButton, new LinearLayout.LayoutParams(
                0, dp(38f), 1f
        ));
        panelRoot.addView(footerActionRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView summary = new TextView(context);
        summary.setText("Editing: " + displayLabelForDialog(data.label));
        summary.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        summary.setTextSize(15f);
        summary.setPadding(0, 0, 0, dp(10f));
        layout.addView(summary);

        addSectionHeader(layout, "Identity", "Set the display name, action, ID, and optional key combo.");

        EditText label = textField(context, "Button label", data.label, false);
        addFieldRow(layout, "Label", label);

        CheckBox emptyLabel = new CheckBox(context);
        emptyLabel.setText("Leave button text empty");
        emptyLabel.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        emptyLabel.setChecked(data.label == null || data.label.isEmpty());
        emptyLabel.setOnCheckedChangeListener((buttonView, checked) -> {
            if (checked && label.getText() != null && label.getText().length() > 0) {
                label.setText("");
            }
        });
        layout.addView(emptyLabel);

        EditText idField = textField(context, "Stable button ID", data.id, false);
        addFieldRow(layout, "Button ID", idField);

        String[] actionLabels = TouchInputBinding.actionLabels();
        String[] actionValues = TouchInputBinding.actionValues();
        Spinner actionSpinner = new Spinner(context);
        ArrayAdapter<String> actionAdapter = dialogSpinnerAdapter(context, actionLabels);
        actionSpinner.setAdapter(actionAdapter);
        actionSpinner.setSelection(TouchInputBinding.actionIndex(data.action));
        addSpinnerRow(layout, "Action", actionSpinner);

        Spinner bindingSpinner = new Spinner(context);
        addSpinnerRow(layout, "Binding", bindingSpinner);
        final TouchInputBinding.Option[][] currentOptions = new TouchInputBinding.Option[1][];

        // Keep the raw numeric key slot list internally for saving, but do not show it
        // as the main UI. Users should see friendly names like "Position 0: Left Shift",
        // not GLFW key IDs like "340, 89".
        EditText keyCodes = textField(context, "Internal key slots", joinKeyCodes(data.normalizedKeySlots()), false);
        keyCodes.setVisibility(GONE);
        layout.addView(keyCodes, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0
        ));

        TextView boundKeys = valueLabel(context, "Bound buttons: " + TouchInputBinding.friendlyKeyCombo(data.normalizedKeyCodes()));
        layout.addView(boundKeys);

        TextView keySlotHint = valueLabel(context, "Set up to 4 button positions. Each position can be a keyboard key, mouse click, or wheel action and can be changed or cleared without touching the others.");
        layout.addView(keySlotHint);

        TouchInputBinding.Option[] keyOptions = TouchInputBinding.optionsForAction(TouchControlActions.KEY);
        Spinner[] keySlotSpinners = new Spinner[TouchControlData.MAX_ACTION_SLOTS];
        LinearLayout[] keySlotRows = new LinearLayout[TouchControlData.MAX_ACTION_SLOTS];
        int[] startingKeySlots = data.normalizedKeySlots();
        for (int slot = 0; slot < TouchControlData.MAX_ACTION_SLOTS; slot++) {
            LinearLayout slotRow = new LinearLayout(context);
            slotRow.setOrientation(LinearLayout.VERTICAL);
            slotRow.setPadding(0, dp(3f), 0, dp(5f));

            TextView slotLabel = new TextView(context);
            slotLabel.setText("Position " + slot);
            slotLabel.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
            slotLabel.setTextSize(12f);
            slotRow.addView(slotLabel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            Spinner slotSpinner = new Spinner(context);
            ArrayAdapter<String> slotAdapter = dialogSpinnerAdapter(
                    context,
                    TouchInputBinding.optionLabels(keyOptions)
            );
            slotSpinner.setAdapter(slotAdapter);
            int slotValue = slot < startingKeySlots.length ? startingKeySlots[slot] : 0;
            slotSpinner.setSelection(TouchInputBinding.selectedKeyOptionIndex(slotValue), false);
            slotRow.addView(slotSpinner, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            LinearLayout slotActionRow = new LinearLayout(context);
            slotActionRow.setOrientation(LinearLayout.HORIZONTAL);
            slotActionRow.setWeightSum(2f);

            Button pickSlot = slotEditActionButton(context, "Pick", true);
            Button clearSlot = slotEditActionButton(context, "Clear", false);
            LinearLayout.LayoutParams pickSlotParams = new LinearLayout.LayoutParams(0, dp(36f), 1f);
            pickSlotParams.setMarginEnd(dp(4f));
            LinearLayout.LayoutParams clearSlotParams = new LinearLayout.LayoutParams(0, dp(36f), 1f);
            clearSlotParams.setMarginStart(dp(4f));
            slotActionRow.addView(pickSlot, pickSlotParams);
            slotActionRow.addView(clearSlot, clearSlotParams);
            slotRow.addView(slotActionRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            final int slotIndex = slot;
            pickSlot.setOnClickListener(v -> showKeyboardKeyPickerDialog(
                    context,
                    slotIndex,
                    keySlotSpinners[slotIndex],
                    keyCodes,
                    boundKeys,
                    keySlotSpinners,
                    keyOptions
            ));
            clearSlot.setOnClickListener(v -> {
                keySlotSpinners[slotIndex].setSelection(TouchInputBinding.selectedKeyOptionIndex(0));
                updateKeySlotSummary(keyCodes, boundKeys, keySlotSpinners, keyOptions);
            });
            slotSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    boolean liveChange = panelControlsReady[0]
                            && !syncingPanelUi[0]
                            && !updatingActionUi[0];
                    if (liveChange) panelHistory.beginDiscreteChange();
                    updateKeySlotSummary(keyCodes, boundKeys, keySlotSpinners, keyOptions);
                    if (liveChange && applyPreviewRef[0] != null) applyPreviewRef[0].run();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });

            layout.addView(slotRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            keySlotSpinners[slot] = slotSpinner;
            keySlotRows[slot] = slotRow;
        }
        updateKeySlotSummary(keyCodes, boundKeys, keySlotSpinners, keyOptions);

        LinearLayout drawerOptions = new LinearLayout(context);
        drawerOptions.setOrientation(LinearLayout.VERTICAL);
        addSectionHeader(
                drawerOptions,
                "Drawer",
                "Choose which existing buttons this drawer expands and collapses. Child buttons keep their normal positions."
        );
        Button drawerMembersButton = new Button(context);
        drawerMembersButton.setAllCaps(false);
        updateDrawerMembersButtonText(drawerMembersButton, selectedDrawerMemberIds.size());
        drawerOptions.addView(drawerMembersButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        CheckBox drawerOpenByDefault = new CheckBox(context);
        drawerOpenByDefault.setText("Expanded by default");
        drawerOpenByDefault.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        drawerOpenByDefault.setChecked(data.drawerOpenByDefault);
        drawerOptions.addView(drawerOpenByDefault);
        layout.addView(drawerOptions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        drawerMembersButton.setOnClickListener(v -> showDrawerMembersDialog(
                context,
                data,
                selectedDrawerMemberIds,
                drawerMembersButton
        ));

        final View[] joystickOptionViews = new View[5];

        AdapterView.OnItemSelectedListener actionListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean liveChange = panelControlsReady[0]
                        && !syncingPanelUi[0]
                        && !updatingActionUi[0];
                if (liveChange) panelHistory.beginDiscreteChange();

                updatingActionUi[0] = true;
                try {
                    String action = actionValues[Math.max(0, Math.min(position, actionValues.length - 1))];
                    TouchInputBinding.Option[] options = TouchInputBinding.optionsForAction(action);
                    currentOptions[0] = options;
                    ArrayAdapter<String> bindingAdapter = dialogSpinnerAdapter(
                            context,
                            TouchInputBinding.optionLabels(options)
                    );
                    bindingSpinner.setAdapter(bindingAdapter);
                    boolean keyAction = TouchControlActions.KEY.equals(action);
                    int selected = keyAction && TouchControlActions.KEY.equals(data.action)
                            || TouchControlActions.MOUSE.equals(action) && TouchControlActions.MOUSE.equals(data.action)
                            || TouchControlActions.SCROLL.equals(action) && TouchControlActions.SCROLL.equals(data.action)
                            ? TouchInputBinding.selectedOptionIndex(action, data)
                            : 0;
                    if (options.length > 0) {
                        bindingSpinner.setSelection(Math.max(0, Math.min(selected, options.length - 1)), false);
                    }
                    bindingSpinner.setEnabled(!keyAction && options.length > 1);
                    keyCodes.setVisibility(GONE);
                    boundKeys.setVisibility(keyAction ? VISIBLE : GONE);
                    keySlotHint.setVisibility(keyAction ? VISIBLE : GONE);
                    for (LinearLayout slotRow : keySlotRows) {
                        if (slotRow != null) slotRow.setVisibility(keyAction ? VISIBLE : GONE);
                    }
                    boolean drawerSelected = TouchControlActions.DRAWER.equals(action);
                    drawerOptions.setVisibility(drawerSelected ? VISIBLE : GONE);
                    boolean joystickSelected = TouchControlActions.JOYSTICK.equals(action);
                    for (View joystickOptionView : joystickOptionViews) {
                        if (joystickOptionView != null) joystickOptionView.setVisibility(joystickSelected ? VISIBLE : GONE);
                    }
                } finally {
                    updatingActionUi[0] = false;
                }

                if (liveChange && applyPreviewRef[0] != null) applyPreviewRef[0].run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        actionSpinner.setOnItemSelectedListener(actionListener);
        actionListener.onItemSelected(actionSpinner, actionSpinner, actionSpinner.getSelectedItemPosition(), 0L);
        bindingSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean liveChange = panelControlsReady[0]
                        && !syncingPanelUi[0]
                        && !updatingActionUi[0];
                if (!liveChange) return;
                panelHistory.beginDiscreteChange();
                if (applyPreviewRef[0] != null) applyPreviewRef[0].run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        addSectionHeader(layout, "Position", "Move the selected control while watching it update live.");
        EditText x = textField(context, "X position", String.valueOf(Math.round(initialLayoutX)), true);
        EditText y = textField(context, "Y position", String.valueOf(Math.round(initialLayoutY)), true);
        addPairedFieldRow(layout, "X", x, "Y", y);
        SeekBar[] positionSliders = addPairedSliderRow(
                layout,
                parentWidthUnits,
                Math.round(initialLayoutX),
                parentHeightUnits,
                Math.round(initialLayoutY)
        );
        SeekBar xSlider = positionSliders[0];
        SeekBar ySlider = positionSliders[1];

        addSectionHeader(layout, "Size", "Width and height can be adjusted together or independently.");
        EditText width = textField(context, "Width", String.valueOf(Math.round(data.width)), true);
        EditText height = textField(context, "Height", String.valueOf(Math.round(data.height)), true);
        addPairedFieldRow(layout, "Width", width, "Height", height);
        SeekBar[] dimensionSliders = addPairedSliderRow(
                layout,
                400,
                Math.round(data.width),
                400,
                Math.round(data.height)
        );
        SeekBar widthSlider = dimensionSliders[0];
        SeekBar heightSlider = dimensionSliders[1];

        int initialPercent = Math.round(clamp(data.sizePercent, 30f, 250f));
        TextView sizeLabel = valueLabel(context, "Button size: " + initialPercent + "%");
        layout.addView(sizeLabel);
        SeekBar sizeSlider = addSlider(layout, 250, initialPercent);

        addSectionHeader(layout, "Appearance", "Adjust opacity, rounded corners, and stroke width.");
        EditText opacity = textField(context, "Opacity 0.0 - 1.0", String.valueOf(data.opacity), false);
        addFieldRow(layout, "Opacity", opacity);
        SeekBar opacitySlider = addSlider(layout, 100, Math.round(clamp(data.opacity, 0f, 1f) * 100f));

        EditText cornerRadius = textField(context, "Corner radius", String.valueOf(Math.round(data.cornerRadius)), true);
        addFieldRow(layout, "Corner radius", cornerRadius);
        SeekBar cornerSlider = addSlider(layout, 100, Math.round(data.cornerRadius));

        EditText strokeWidth = textField(context, "Stroke width", String.valueOf(Math.round(data.strokeWidth)), true);
        addFieldRow(layout, "Stroke", strokeWidth);
        SeekBar strokeSlider = addSlider(layout, 20, Math.round(data.strokeWidth));

        final int[] selectedBackgroundColor = new int[]{data.backgroundColor};
        final int[] selectedStrokeColor = new int[]{data.strokeColor};
        Button backgroundColorButton = slotEditActionButton(context, "Button colour", true);
        Button strokeColorButton = slotEditActionButton(context, "Border / joystick", true);
        backgroundColorButton.setSingleLine(false);
        strokeColorButton.setSingleLine(false);
        backgroundColorButton.setGravity(Gravity.CENTER);
        strokeColorButton.setGravity(Gravity.CENTER);
        updateColorPickerButton(backgroundColorButton, selectedBackgroundColor[0], "Button colour");
        updateColorPickerButton(strokeColorButton, selectedStrokeColor[0], "Border / joystick");

        // Colour pickers should read like the other primary editor actions rather
        // than two small field widgets. Keep them large, equal-width, and side by side.
        LinearLayout colorActions = new LinearLayout(context);
        colorActions.setOrientation(LinearLayout.HORIZONTAL);
        colorActions.setWeightSum(2f);
        colorActions.setPadding(0, dp(3f), 0, dp(5f));
        LinearLayout.LayoutParams backgroundColorParams = new LinearLayout.LayoutParams(
                0, dp(52f), 1f
        );
        backgroundColorParams.setMarginEnd(dp(4f));
        LinearLayout.LayoutParams strokeColorParams = new LinearLayout.LayoutParams(
                0, dp(52f), 1f
        );
        strokeColorParams.setMarginStart(dp(4f));
        colorActions.addView(backgroundColorButton, backgroundColorParams);
        colorActions.addView(strokeColorButton, strokeColorParams);
        layout.addView(colorActions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        addSectionHeader(layout, "Button image", "Pick an image to use behind the label or replace the normal button artwork entirely.");
        TextView imageStatus = valueLabel(context, hasControlImage(data)
                ? "Image selected"
                : "No image selected");
        layout.addView(imageStatus);

        LinearLayout imageActions = new LinearLayout(context);
        imageActions.setOrientation(LinearLayout.HORIZONTAL);
        imageActions.setWeightSum(2f);
        Button chooseImageButton = slotEditActionButton(context, "Choose image", true);
        Button clearImageButton = slotEditActionButton(context, "Clear image", false);
        LinearLayout.LayoutParams imageActionParams = new LinearLayout.LayoutParams(0, dp(38f), 1f);
        imageActionParams.setMargins(dp(2f), dp(2f), dp(2f), dp(2f));
        imageActions.addView(chooseImageButton, new LinearLayout.LayoutParams(imageActionParams));
        imageActions.addView(clearImageButton, new LinearLayout.LayoutParams(imageActionParams));
        layout.addView(imageActions);

        Spinner imageModeSpinner = new Spinner(context);
        String[] imageModeLabels = new String[]{
                "Background — keep text and stroke",
                "Replace button — image only"
        };
        imageModeSpinner.setAdapter(dialogSpinnerAdapter(context, imageModeLabels));
        imageModeSpinner.setSelection(TouchControlData.IMAGE_MODE_REPLACE.equals(
                TouchControlData.normalizeImageMode(data.imageMode)) ? 1 : 0);
        addViewFieldRow(layout, "Image mode", imageModeSpinner);

        int initialImageScale = Math.round(TouchControlData.clampImageScalePercent(data.imageScalePercent));
        TextView imageScaleLabel = valueLabel(context, "Image size: " + initialImageScale + "%");
        layout.addView(imageScaleLabel);
        SeekBar imageScaleSlider = addSlider(layout, 300, initialImageScale);
        if (imageScaleSlider.getProgress() < 25) imageScaleSlider.setProgress(25);

        TextView imageOffsetXLabel = valueLabel(context, "Image horizontal offset: " + Math.round(data.imageOffsetXPercent) + "%");
        layout.addView(imageOffsetXLabel);
        SeekBar imageOffsetXSlider = addSlider(layout, 200, Math.round(TouchControlData.clampImageOffsetPercent(data.imageOffsetXPercent)) + 100);

        TextView imageOffsetYLabel = valueLabel(context, "Image vertical offset: " + Math.round(data.imageOffsetYPercent) + "%");
        layout.addView(imageOffsetYLabel);
        SeekBar imageOffsetYSlider = addSlider(layout, 200, Math.round(TouchControlData.clampImageOffsetPercent(data.imageOffsetYPercent)) + 100);

        CheckBox toggle = new CheckBox(context);
        toggle.setText("Toggle button");
        toggle.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        toggle.setChecked(data.toggle);
        layout.addView(toggle);

        CheckBox visibleInGame = new CheckBox(context);
        visibleInGame.setText("Visible in game");
        visibleInGame.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        visibleInGame.setChecked(data.visibleInGame);
        layout.addView(visibleInGame);

        CheckBox visibleInMenu = new CheckBox(context);
        visibleInMenu.setText("Visible in menu");
        visibleInMenu.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        visibleInMenu.setChecked(data.visibleInMenu);
        layout.addView(visibleInMenu);

        CheckBox visibleWhenControlsHidden = new CheckBox(context);
        visibleWhenControlsHidden.setText("Stay visible when touch controls are hidden");
        visibleWhenControlsHidden.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        visibleWhenControlsHidden.setChecked(data.visibleWhenControlsHidden
                || TouchControlData.shouldStayVisibleWhenControlsHiddenByDefault(data.action));
        layout.addView(visibleWhenControlsHidden);

        CheckBox joystickAbsolute = new CheckBox(context);
        joystickAbsolute.setText("Joystick center follows finger");
        joystickAbsolute.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        joystickAbsolute.setChecked(data.joystickAbsolute);
        layout.addView(joystickAbsolute);

        CheckBox joystickForwardLock = new CheckBox(context);
        joystickForwardLock.setText("Joystick forward lock (double-tap forward; press forward again to release)");
        joystickForwardLock.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        joystickForwardLock.setChecked(data.joystickForwardLock);
        layout.addView(joystickForwardLock);

        TextView deadzoneLabel = valueLabel(context, "Joystick deadzone: " + Math.round(TouchControlData.clampJoystickDeadzonePercent(data.joystickDeadzonePercent)) + "%");
        layout.addView(deadzoneLabel);
        EditText joystickDeadzone = textField(context, "Deadzone percent", String.valueOf(Math.round(TouchControlData.clampJoystickDeadzonePercent(data.joystickDeadzonePercent))), true);
        addFieldRow(layout, "Deadzone", joystickDeadzone);
        SeekBar joystickDeadzoneSlider = addSlider(layout, 80, Math.round(TouchControlData.clampJoystickDeadzonePercent(data.joystickDeadzonePercent)));
        joystickOptionViews[0] = joystickAbsolute;
        joystickOptionViews[1] = joystickForwardLock;
        joystickOptionViews[2] = deadzoneLabel;
        joystickOptionViews[3] = joystickDeadzone;
        joystickOptionViews[4] = joystickDeadzoneSlider;

        boolean joystickControl = TouchControlActions.JOYSTICK.equals(data.action);
        joystickAbsolute.setVisibility(joystickControl ? VISIBLE : GONE);
        joystickForwardLock.setVisibility(joystickControl ? VISIBLE : GONE);
        deadzoneLabel.setVisibility(joystickControl ? VISIBLE : GONE);
        joystickDeadzone.setVisibility(joystickControl ? VISIBLE : GONE);
        joystickDeadzoneSlider.setVisibility(joystickControl ? VISIBLE : GONE);

        CheckBox mousePassThrough = new CheckBox(context);
        mousePassThrough.setText("Mouse pass through");
        mousePassThrough.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        mousePassThrough.setChecked(isMousePassThroughEnabled(data));
        layout.addView(mousePassThrough);

        TextView mousePassThroughHint = valueLabel(context,
                "When enabled, dragging on this button also moves the in-game camera while the button stays pressed. Useful for Jump, Sneak, Sprint, or Use.");
        layout.addView(mousePassThroughHint);

        CheckBox swipeGesture = new CheckBox(context);
        swipeGesture.setText("Swipeable gesture");
        swipeGesture.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        swipeGesture.setChecked(isSwipeGestureEnabled(data));
        layout.addView(swipeGesture);

        TextView swipeGestureHint = valueLabel(context,
                "When enabled on a group of buttons, sliding between them transfers the held action without lifting your finger. Enable it on W, A, S, and D for swipe movement.");
        layout.addView(swipeGestureHint);

        CheckBox virtualMouse = new CheckBox(context);
        virtualMouse.setText("Show virtual cursor");
        virtualMouse.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        virtualMouse.setChecked(isProfileVirtualMouseEnabled());
        virtualMouse.setOnCheckedChangeListener((buttonView, isChecked) -> {
            setProfileVirtualMouseEnabled(isChecked);
            postInvalidateOnAnimation();
        });
        layout.addView(virtualMouse);

        final boolean[] sliderChangingText = new boolean[]{false};
        final boolean[] textChangingSlider = new boolean[]{false};
        final boolean[] geometryInputDirty = new boolean[]{false};
        final boolean[] accepted = new boolean[]{false};
        final boolean[] deleted = new boolean[]{false};
        final AlertDialog[] dialogRef = new AlertDialog[1];
        final float[] currentPercent = new float[]{initialPercent};
        final float[] lastGeometryValues = new float[]{
                initialLayoutX,
                initialLayoutY,
                data.width,
                data.height
        };
        activeEditGeometryUi = new ActiveEditGeometryUi(
                data,
                x,
                y,
                width,
                height,
                xSlider,
                ySlider,
                widthSlider,
                heightSlider,
                sizeSlider,
                sizeLabel,
                currentPercent,
                lastGeometryValues
        );
        final float[] baseWidth = new float[]{Math.max(24f, data.width * 100f / Math.max(1f, initialPercent))};
        final float[] baseHeight = new float[]{Math.max(24f, data.height * 100f / Math.max(1f, initialPercent))};

        Runnable applyPreview = () -> {
            float previousScreenX = editingView.getX();
            float previousScreenY = editingView.getY();
            float previousScreenWidth = Math.max(1f, editingView.getWidth());
            float previousScreenHeight = Math.max(1f, editingView.getHeight());

            data.label = emptyLabel.isChecked()
                    ? ""
                    : (label.getText() == null ? data.label : label.getText().toString());
            data.id = idField.getText() == null ? data.id : idField.getText().toString().trim();

            int actionPosition = Math.max(0, actionSpinner.getSelectedItemPosition());
            String selectedAction = actionValues[Math.min(actionPosition, actionValues.length - 1)];
            TouchInputBinding.Option[] selectedOptions = currentOptions[0] != null
                    ? currentOptions[0]
                    : TouchInputBinding.optionsForAction(selectedAction);
            int bindingPosition = Math.max(0, bindingSpinner.getSelectedItemPosition());
            if (TouchControlActions.KEY.equals(selectedAction)) {
                data.action = TouchControlActions.KEY;
                data.setKeySlots(readKeySlotsFromSpinners(keySlotSpinners, keyOptions));
            } else if (selectedOptions.length > 0) {
                TouchInputBinding.Option selectedOption = selectedOptions[Math.min(
                        bindingPosition,
                        selectedOptions.length - 1
                )];
                TouchInputBinding.applyOption(data, selectedAction, selectedOption);
            } else {
                data.action = selectedAction;
            }

            /*
             * Geometry is intentionally opt-in. Opening the panel initializes several
             * Spinners/EditTexts and Android may deliver one of those callbacks after
             * the panel is visible. Copied or dragged controls commonly have fractional
             * source-canvas coordinates, while the visible fields are rounded for the
             * user. Re-parsing those rounded fields from an unrelated callback could
             * change the inferred edge anchor and fling the button across the screen.
             */
            boolean applyGeometryInput = geometryInputDirty[0];
            float requestedX = applyGeometryInput
                    ? parseFloat(x, lastGeometryValues[0])
                    : lastGeometryValues[0];
            float requestedY = applyGeometryInput
                    ? parseFloat(y, lastGeometryValues[1])
                    : lastGeometryValues[1];
            float requestedWidth = applyGeometryInput
                    ? Math.max(24f, parseFloat(width, lastGeometryValues[2]))
                    : lastGeometryValues[2];
            float requestedHeight = applyGeometryInput
                    ? Math.max(24f, parseFloat(height, lastGeometryValues[3]))
                    : lastGeometryValues[3];
            data.sizePercent = clamp(currentPercent[0], 30f, 250f);
            if (applyGeometryInput && TouchControlActions.JOYSTICK.equals(data.action)) {
                float squareSize = Math.max(requestedWidth, requestedHeight);
                requestedWidth = squareSize;
                requestedHeight = squareSize;
            }
            data.opacity = clamp(parseFloat(opacity, data.opacity), 0f, 1f);
            data.cornerRadius = Math.max(0f, parseFloat(cornerRadius, data.cornerRadius));
            data.strokeWidth = Math.max(0f, parseFloat(strokeWidth, data.strokeWidth));
            data.backgroundColor = selectedBackgroundColor[0];
            data.strokeColor = selectedStrokeColor[0];
            data.imageMode = imageModeSpinner.getSelectedItemPosition() == 1
                    ? TouchControlData.IMAGE_MODE_REPLACE
                    : TouchControlData.IMAGE_MODE_BACKGROUND;
            data.imageScalePercent = TouchControlData.clampImageScalePercent(imageScaleSlider.getProgress());
            data.imageOffsetXPercent = TouchControlData.clampImageOffsetPercent(imageOffsetXSlider.getProgress() - 100f);
            data.imageOffsetYPercent = TouchControlData.clampImageOffsetPercent(imageOffsetYSlider.getProgress() - 100f);
            data.toggle = TouchControlActions.DRAWER.equals(data.action) ? false : toggle.isChecked();
            data.drawerOpenByDefault = TouchControlActions.DRAWER.equals(data.action)
                    && drawerOpenByDefault.isChecked();
            data.visibleInGame = visibleInGame.isChecked();
            data.visibleInMenu = visibleInMenu.isChecked();
            data.visibleWhenControlsHidden = visibleWhenControlsHidden.isChecked()
                    || TouchControlData.shouldStayVisibleWhenControlsHiddenByDefault(data.action);
            data.mousePassThrough = mousePassThrough.isChecked();
            data.migrateMousePassThroughFromPreferences = false;
            data.swipeGesture = swipeGesture.isChecked();
            data.migrateSwipeGestureFromPreferences = false;
            data.joystickAbsolute = joystickAbsolute.isChecked();
            data.joystickForwardLock = joystickForwardLock.isChecked();
            data.joystickDeadzonePercent = TouchControlData.clampJoystickDeadzonePercent(
                    parseFloat(joystickDeadzone, data.joystickDeadzonePercent)
            );
            deadzoneLabel.setText("Joystick deadzone: "
                    + Math.round(data.joystickDeadzonePercent)
                    + "%");
            boolean positionChanged = applyGeometryInput
                    && (materiallyDifferent(lastGeometryValues[0], requestedX)
                    || materiallyDifferent(lastGeometryValues[1], requestedY));
            boolean dimensionsChanged = applyGeometryInput
                    && (materiallyDifferent(lastGeometryValues[2], requestedWidth)
                    || materiallyDifferent(lastGeometryValues[3], requestedHeight));
            if (positionChanged || dimensionsChanged) {
                panelHistory.geometryDirty = true;
                data.x = requestedX;
                data.y = requestedY;
                data.width = requestedWidth;
                data.height = requestedHeight;
                data.positionAnchorX = metrics.horizontalAnchorForSourcePosition(
                        requestedX,
                        requestedWidth
                );
                data.positionAnchorY = metrics.verticalAnchorForSourcePosition(
                        requestedY,
                        requestedHeight
                );
                data.rawX = null;
                data.rawY = null;
                lastGeometryValues[0] = requestedX;
                lastGeometryValues[1] = requestedY;
                lastGeometryValues[2] = requestedWidth;
                lastGeometryValues[3] = requestedHeight;
            }
            if (dimensionsChanged && !positionChanged) {
                preserveControlScreenAnchorAfterSizeChange(
                        editingView,
                        data,
                        previousScreenX,
                        previousScreenY,
                        previousScreenWidth,
                        previousScreenHeight
                );
                syncActiveEditGeometryUi(data);
            }
            geometryInputDirty[0] = false;

            summary.setText("Editing: " + displayLabelForDialog(data.label));
            if (positionChanged || dimensionsChanged) {
                applyControlPreview(editingView, data);
            } else {
                // Label, binding, visibility, and appearance edits must not run the
                // position resolver. The selected control may currently include a
                // cluster edge-nudge, and recomputing it alone is what made scaled
                // A/D buttons fling away as soon as the panel opened.
                applyControlAppearancePreview(editingView, data);
            }
            panelHistory.updateButtons();
        };
        applyPreviewRef[0] = applyPreview;

        backgroundColorButton.setOnClickListener(v -> TouchColorPickerDialog.show(
                context,
                "Button colour",
                selectedBackgroundColor[0],
                false,
                true,
                color -> {
                    if (panelHistory.restoring) return;
                    panelHistory.beginDiscreteChange();
                    // Clear fill is represented by fully transparent black. Otherwise
                    // the dedicated control Opacity slider owns transparency, so a
                    // colour chosen from the wheel is stored fully opaque.
                    int chosenColor = Color.alpha(color) == 0
                            ? Color.TRANSPARENT
                            : Color.rgb(
                                    Color.red(color),
                                    Color.green(color),
                                    Color.blue(color)
                            );
                    selectedBackgroundColor[0] = chosenColor;
                    data.backgroundColor = chosenColor;
                    updateColorPickerButton(backgroundColorButton, chosenColor, "Button colour");
                    applyControlAppearancePreview(editingView, data);
                    panelHistory.updateButtons();
                }
        ));
        strokeColorButton.setOnClickListener(v -> TouchColorPickerDialog.show(
                context,
                "Border / joystick colour",
                selectedStrokeColor[0],
                color -> {
                    if (panelHistory.restoring) return;
                    panelHistory.beginDiscreteChange();
                    selectedStrokeColor[0] = color;
                    data.strokeColor = color;
                    updateColorPickerButton(strokeColorButton, color, "Border / joystick");
                    applyControlAppearancePreview(editingView, data);
                    panelHistory.updateButtons();
                }
        ));

        chooseImageButton.setOnClickListener(v -> {
            if (panelHistory.restoring) return;
            panelHistory.beginDiscreteChange();
            requestControlImage(data, () -> {
                imageStatus.setText(hasControlImage(data) ? "Image selected" : "No image selected");
                applyPreview.run();
            });
        });
        clearImageButton.setOnClickListener(v -> {
            if (!hasControlImage(data) || panelHistory.restoring) return;
            panelHistory.beginDiscreteChange();
            data.imageUri = null;
            imageStatus.setText("No image selected");
            applyPreview.run();
        });

        imageModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!panelControlsReady[0] || syncingPanelUi[0] || panelHistory.restoring) return;
                panelHistory.beginDiscreteChange();
                applyPreview.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        imageScaleSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || !panelControlsReady[0] || syncingPanelUi[0] || panelHistory.restoring) return;
                int clamped = Math.round(TouchControlData.clampImageScalePercent(progress));
                imageScaleLabel.setText("Image size: " + clamped + "%");
                applyPreview.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if (panelControlsReady[0] && !panelHistory.restoring) panelHistory.beginDiscreteChange();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (seekBar.getProgress() < 25) seekBar.setProgress(25);
                if (panelControlsReady[0] && !panelHistory.restoring) applyPreview.run();
            }
        });

        imageOffsetXSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || !panelControlsReady[0] || syncingPanelUi[0] || panelHistory.restoring) return;
                imageOffsetXLabel.setText("Image horizontal offset: " + (progress - 100) + "%");
                applyPreview.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if (panelControlsReady[0] && !panelHistory.restoring) panelHistory.beginDiscreteChange();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (panelControlsReady[0] && !panelHistory.restoring) applyPreview.run();
            }
        });

        imageOffsetYSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || !panelControlsReady[0] || syncingPanelUi[0] || panelHistory.restoring) return;
                imageOffsetYLabel.setText("Image vertical offset: " + (progress - 100) + "%");
                applyPreview.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if (panelControlsReady[0] && !panelHistory.restoring) panelHistory.beginDiscreteChange();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (panelControlsReady[0] && !panelHistory.restoring) applyPreview.run();
            }
        });

        emptyLabel.setOnCheckedChangeListener((buttonView, checked) -> {
            if (!panelControlsReady[0] || syncingPanelUi[0] || panelHistory.restoring) return;
            panelHistory.beginDiscreteChange();
            if (checked && label.getText() != null && label.getText().length() > 0) {
                syncingPanelUi[0] = true;
                try {
                    label.setText("");
                } finally {
                    syncingPanelUi[0] = false;
                }
            }
            applyPreview.run();
        });

        android.widget.CompoundButton.OnCheckedChangeListener liveOptionListener =
                (buttonView, isChecked) -> {
                    if (!panelControlsReady[0] || syncingPanelUi[0] || panelHistory.restoring) return;
                    panelHistory.beginDiscreteChange();
                    applyPreview.run();
                };
        toggle.setOnCheckedChangeListener(liveOptionListener);
        visibleInGame.setOnCheckedChangeListener(liveOptionListener);
        visibleInMenu.setOnCheckedChangeListener(liveOptionListener);
        visibleWhenControlsHidden.setOnCheckedChangeListener(liveOptionListener);
        joystickAbsolute.setOnCheckedChangeListener(liveOptionListener);
        joystickForwardLock.setOnCheckedChangeListener(liveOptionListener);
        drawerOpenByDefault.setOnCheckedChangeListener(liveOptionListener);
        mousePassThrough.setOnCheckedChangeListener(liveOptionListener);
        swipeGesture.setOnCheckedChangeListener(liveOptionListener);
        virtualMouse.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!panelControlsReady[0] || syncingPanelUi[0] || panelHistory.restoring) return;
            panelHistory.beginDiscreteChange();
            setProfileVirtualMouseEnabled(isChecked);
            panelHistory.updateButtons();
            postInvalidateOnAnimation();
        });

        EditText[] liveTextFields = new EditText[]{
                label, idField, x, y, width, height, opacity,
                cornerRadius, strokeWidth, joystickDeadzone
        };
        for (EditText field : liveTextFields) {
            final boolean geometryField = field == x
                    || field == y
                    || field == width
                    || field == height;
            field.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
                @Override public void afterTextChanged(Editable s) {
                    if (!panelControlsReady[0]
                            || syncingActiveEditGeometryUi
                            || syncingPanelUi[0]
                            || sliderChangingText[0]
                            || panelHistory.restoring) {
                        return;
                    }
                    if (geometryField) geometryInputDirty[0] = true;
                    applyPreview.run();
                    textChangingSlider[0] = true;
                    setSliderProgress(xSlider, Math.round(parseFloat(x, data.x)));
                    setSliderProgress(ySlider, Math.round(parseFloat(y, data.y)));
                    setSliderProgress(widthSlider, Math.round(parseFloat(width, data.width)));
                    setSliderProgress(heightSlider, Math.round(parseFloat(height, data.height)));
                    setSliderProgress(opacitySlider, Math.round(clamp(parseFloat(opacity, data.opacity), 0f, 1f) * 100f));
                    setSliderProgress(cornerSlider, Math.round(parseFloat(cornerRadius, data.cornerRadius)));
                    setSliderProgress(strokeSlider, Math.round(parseFloat(strokeWidth, data.strokeWidth)));
                    setSliderProgress(joystickDeadzoneSlider, Math.round(TouchControlData.clampJoystickDeadzonePercent(parseFloat(joystickDeadzone, data.joystickDeadzonePercent))));
                    textChangingSlider[0] = false;
                }
            });
            field.setOnFocusChangeListener((focusedView, hasFocus) -> {
                if (syncingPanelUi[0] || syncingActiveEditGeometryUi) return;
                if (hasFocus) {
                    panelHistory.beginTextEdit(focusedView);
                } else {
                    panelHistory.finishTextEdit();
                }
            });
        }

        addPreviewSliderListener(xSlider, dialogRef, panelHistory::beginDiscreteChange, () -> {
            if (!textChangingSlider[0]) {
                geometryInputDirty[0] = true;
                setTextFromSlider(x, xSlider);
                // setText() may suppress a same-value callback on some Android builds.
                if (geometryInputDirty[0]) applyPreview.run();
            }
        });
        addPreviewSliderListener(ySlider, dialogRef, panelHistory::beginDiscreteChange, () -> {
            if (!textChangingSlider[0]) {
                geometryInputDirty[0] = true;
                setTextFromSlider(y, ySlider);
                // setText() may suppress a same-value callback on some Android builds.
                if (geometryInputDirty[0]) applyPreview.run();
            }
        });
        addPreviewSliderListener(widthSlider, dialogRef, panelHistory::beginDiscreteChange, () -> {
            if (!textChangingSlider[0]) {
                geometryInputDirty[0] = true;
                setTextFromSlider(width, widthSlider);
                // setText() may suppress a same-value callback on some Android builds.
                if (geometryInputDirty[0]) applyPreview.run();
            }
        });
        addPreviewSliderListener(heightSlider, dialogRef, panelHistory::beginDiscreteChange, () -> {
            if (!textChangingSlider[0]) {
                geometryInputDirty[0] = true;
                setTextFromSlider(height, heightSlider);
                // setText() may suppress a same-value callback on some Android builds.
                if (geometryInputDirty[0]) applyPreview.run();
            }
        });
        addPreviewSliderListener(opacitySlider, dialogRef, panelHistory::beginDiscreteChange, () -> {
            if (!textChangingSlider[0]) {
                sliderChangingText[0] = true;
                opacity.setText(String.valueOf(opacitySlider.getProgress() / 100f));
                sliderChangingText[0] = false;
                applyPreview.run();
            }
        });
        addPreviewSliderListener(cornerSlider, dialogRef, panelHistory::beginDiscreteChange, () -> { if (!textChangingSlider[0]) setTextFromSlider(cornerRadius, cornerSlider); });
        addPreviewSliderListener(strokeSlider, dialogRef, panelHistory::beginDiscreteChange, () -> { if (!textChangingSlider[0]) setTextFromSlider(strokeWidth, strokeSlider); });
        addPreviewSliderListener(joystickDeadzoneSlider, dialogRef, panelHistory::beginDiscreteChange, () -> { if (!textChangingSlider[0]) setTextFromSlider(joystickDeadzone, joystickDeadzoneSlider); });

        sizeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int percent = Math.max(30, progress);
                currentPercent[0] = percent;
                data.sizePercent = percent;
                sizeLabel.setText("Button size: " + percent + "%");
                if (fromUser) {
                    geometryInputDirty[0] = true;
                    sliderChangingText[0] = true;
                    int newWidth = Math.max(24, Math.round(baseWidth[0] * percent / 100f));
                    int newHeight = Math.max(24, Math.round(baseHeight[0] * percent / 100f));
                    if (TouchControlActions.JOYSTICK.equals(data.action)) {
                        int squareSize = Math.max(newWidth, newHeight);
                        newWidth = squareSize;
                        newHeight = squareSize;
                    }
                    width.setText(String.valueOf(newWidth));
                    height.setText(String.valueOf(newHeight));
                    sliderChangingText[0] = false;
                    applyPreview.run();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                panelHistory.beginDiscreteChange();
                setEditDialogPreviewAlpha(dialogRef[0], true);
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                setEditDialogPreviewAlpha(dialogRef[0], false);
            }
        });

        Runnable refreshOpenEditorPanel = () -> {
            syncingPanelUi[0] = true;
            try {
                summary.setText("Editing: " + displayLabelForDialog(data.label));
                label.setText(data.label == null ? "" : data.label);
                emptyLabel.setChecked(data.label == null || data.label.isEmpty());
                idField.setText(data.id);

                int actionIndex = TouchInputBinding.actionIndex(data.action);
                actionSpinner.setSelection(actionIndex, false);
                actionListener.onItemSelected(actionSpinner, actionSpinner, actionIndex, 0L);

                int[] slots = data.normalizedKeySlots();
                for (int slot = 0; slot < keySlotSpinners.length; slot++) {
                    Spinner spinner = keySlotSpinners[slot];
                    if (spinner == null) continue;
                    int value = slot < slots.length ? slots[slot] : 0;
                    spinner.setSelection(TouchInputBinding.selectedKeyOptionIndex(value), false);
                }
                updateKeySlotSummary(keyCodes, boundKeys, keySlotSpinners, keyOptions);

                LayoutMetrics refreshMetrics = layoutMetrics(getWidth(), getHeight());
                float refreshScale = renderGlobalButtonScaleMultiplier();
                float scaledWidth = Math.max(1f, editingView.getWidth());
                float scaledHeight = Math.max(1f, editingView.getHeight());
                float baseScreenWidth = Math.max(1f, scaledWidth / Math.max(0.001f, refreshScale));
                float baseScreenHeight = Math.max(1f, scaledHeight / Math.max(0.001f, refreshScale));
                float baseScreenX = unscaledControlScreenX(
                        editingView.getX(),
                        baseScreenWidth,
                        scaledWidth,
                        getWidth(),
                        refreshScale,
                        data.positionAnchorX
                );
                float baseScreenY = unscaledControlScreenY(
                        editingView.getY(),
                        baseScreenHeight,
                        scaledHeight,
                        getHeight(),
                        refreshScale,
                        data.positionAnchorY
                );
                float layoutX = data.rawX == null
                        ? data.x
                        : refreshMetrics.fromScreenX(data, baseScreenX, baseScreenWidth);
                float layoutY = data.rawY == null
                        ? data.y
                        : refreshMetrics.fromScreenY(data, baseScreenY, baseScreenHeight);

                setEditFieldText(x, Math.round(layoutX));
                setEditFieldText(y, Math.round(layoutY));
                setEditFieldText(width, Math.round(data.width));
                setEditFieldText(height, Math.round(data.height));
                setSliderProgress(xSlider, Math.round(layoutX));
                setSliderProgress(ySlider, Math.round(layoutY));
                setSliderProgress(widthSlider, Math.round(data.width));
                setSliderProgress(heightSlider, Math.round(data.height));
                lastGeometryValues[0] = layoutX;
                lastGeometryValues[1] = layoutY;
                lastGeometryValues[2] = data.width;
                lastGeometryValues[3] = data.height;

                int percent = Math.round(clamp(data.sizePercent, 30f, 250f));
                currentPercent[0] = percent;
                baseWidth[0] = Math.max(24f, data.width * 100f / Math.max(1f, percent));
                baseHeight[0] = Math.max(24f, data.height * 100f / Math.max(1f, percent));
                setSliderProgress(sizeSlider, percent);
                sizeLabel.setText("Button size: " + percent + "%");

                opacity.setText(String.valueOf(data.opacity));
                setSliderProgress(opacitySlider, Math.round(clamp(data.opacity, 0f, 1f) * 100f));
                cornerRadius.setText(String.valueOf(Math.round(data.cornerRadius)));
                setSliderProgress(cornerSlider, Math.round(data.cornerRadius));
                strokeWidth.setText(String.valueOf(Math.round(data.strokeWidth)));
                setSliderProgress(strokeSlider, Math.round(data.strokeWidth));
                selectedBackgroundColor[0] = data.backgroundColor;
                selectedStrokeColor[0] = data.strokeColor;
                updateColorPickerButton(backgroundColorButton, selectedBackgroundColor[0], "Button colour");
                updateColorPickerButton(strokeColorButton, selectedStrokeColor[0], "Border / joystick");
                imageStatus.setText(hasControlImage(data) ? "Image selected" : "No image selected");
                imageModeSpinner.setSelection(TouchControlData.IMAGE_MODE_REPLACE.equals(
                        TouchControlData.normalizeImageMode(data.imageMode)) ? 1 : 0);
                setSliderProgress(imageScaleSlider, Math.round(TouchControlData.clampImageScalePercent(data.imageScalePercent)));
                setSliderProgress(imageOffsetXSlider, Math.round(TouchControlData.clampImageOffsetPercent(data.imageOffsetXPercent)) + 100);
                setSliderProgress(imageOffsetYSlider, Math.round(TouchControlData.clampImageOffsetPercent(data.imageOffsetYPercent)) + 100);
                imageScaleLabel.setText("Image size: " + Math.round(TouchControlData.clampImageScalePercent(data.imageScalePercent)) + "%");
                imageOffsetXLabel.setText("Image horizontal offset: " + Math.round(TouchControlData.clampImageOffsetPercent(data.imageOffsetXPercent)) + "%");
                imageOffsetYLabel.setText("Image vertical offset: " + Math.round(TouchControlData.clampImageOffsetPercent(data.imageOffsetYPercent)) + "%");

                toggle.setChecked(data.toggle);
                visibleInGame.setChecked(data.visibleInGame);
                visibleInMenu.setChecked(data.visibleInMenu);
                visibleWhenControlsHidden.setChecked(data.visibleWhenControlsHidden
                        || TouchControlData.shouldStayVisibleWhenControlsHiddenByDefault(data.action));
                joystickAbsolute.setChecked(data.joystickAbsolute);
                joystickForwardLock.setChecked(data.joystickForwardLock);
                joystickDeadzone.setText(String.valueOf(Math.round(
                        TouchControlData.clampJoystickDeadzonePercent(data.joystickDeadzonePercent)
                )));
                setSliderProgress(
                        joystickDeadzoneSlider,
                        Math.round(TouchControlData.clampJoystickDeadzonePercent(data.joystickDeadzonePercent))
                );
                deadzoneLabel.setText("Joystick deadzone: "
                        + Math.round(TouchControlData.clampJoystickDeadzonePercent(data.joystickDeadzonePercent))
                        + "%");
                mousePassThrough.setChecked(isMousePassThroughEnabled(data));
                swipeGesture.setChecked(isSwipeGestureEnabled(data));
                virtualMouse.setChecked(isProfileVirtualMouseEnabled());
                drawerOpenByDefault.setChecked(data.drawerOpenByDefault);
                selectedDrawerMemberIds.clear();
                selectedDrawerMemberIds.addAll(drawerMemberIdsFor(data.id));
                updateDrawerMembersButtonText(drawerMembersButton, selectedDrawerMemberIds.size());
                drawerOptions.setVisibility(
                        TouchControlActions.DRAWER.equals(data.action) ? VISIBLE : GONE
                );

                boolean joystickSelected = TouchControlActions.JOYSTICK.equals(data.action);
                for (View joystickOptionView : joystickOptionViews) {
                    if (joystickOptionView != null) {
                        joystickOptionView.setVisibility(joystickSelected ? VISIBLE : GONE);
                    }
                }
            } finally {
                geometryInputDirty[0] = false;
                syncingPanelUi[0] = false;
            }

            panelHistory.updateButtons();
        };
        panelHistory.setRefreshUi(refreshOpenEditorPanel);
        // Keep live listeners disabled until the dialog is attached and all
        // Spinner/CheckBox initialization callbacks have drained. Android may
        // deliver those callbacks after setSelection(), even when nothing was
        // touched, which previously caused an immediate geometry preview.
        panelControlsReady[0] = false;
        panelHistory.updateButtons();

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setView(panelRoot)
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialogRef[0] = dialog;
        activeEditDialog = dialog;
        final EditPanelSwitchController switchController = new EditPanelSwitchController() {
            @Override
            public boolean hasUnsavedChanges() {
                String currentSnapshot = snapshotLayoutSafely();
                if (originalLayoutSnapshot == null || currentSnapshot == null) {
                    return true;
                }
                return !originalLayoutSnapshot.equals(currentSnapshot);
            }

            @Override
            public void closeForSwitch() {
                panelHistory.finishTextEdit();
                accepted[0] = true;
                dialog.dismiss();
            }
        };
        activeEditPanelSwitchController = switchController;

        copyButton.setOnClickListener(v -> {
            panelHistory.finishTextEdit();
            pushUndoSnapshot(originalLayoutSnapshot);

            /*
             * Copy the button from the geometry that is actually visible in this
             * editor. Stock and imported controls may still carry rawX/rawY formulas;
             * changing only x/y on such a copy does nothing because the formulas win
             * during the next rebuild. Resolving the live geometry first also keeps
             * copies stable when the game surface uses a different aspect ratio.
             */
            TouchControlData copy = createOffsetCopyFromLiveGeometry(editingView, data);
            if (isMousePassThroughEnabled(data)) {
                setMousePassThroughEnabled(copy, true);
            }
            if (isSwipeGestureEnabled(data)) {
                setSwipeGestureEnabled(copy, true);
            }
            layoutData.controls.add(copy);
            accepted[0] = true;
            saveLayout();
            rebuildWhenSized();
            Toast.makeText(context, "Copied " + data.label, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        undoButton.setOnClickListener(v -> {
            if (panelHistory.undo()) {
                Toast.makeText(context, "Undid last button change.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "Nothing to undo.", Toast.LENGTH_SHORT).show();
            }
        });

        redoButton.setOnClickListener(v -> {
            if (panelHistory.redo()) {
                Toast.makeText(context, "Redid last button change.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "Nothing to redo.", Toast.LENGTH_SHORT).show();
            }
        });

        deleteButton.setOnClickListener(button -> {
            panelHistory.finishTextEdit();
            pushUndoSnapshot(originalLayoutSnapshot);
            deleted[0] = true;
            setMousePassThroughEnabled(data, false);
            setSwipeGestureEnabled(data, false);
            layoutData.controls.remove(data);
            saveLayout();
            rebuildWhenSized();
            dialog.dismiss();
        });

        cancelButton.setOnClickListener(button -> dialog.dismiss());

        okButton.setOnClickListener(button -> {
            panelHistory.finishTextEdit();
            pushUndoSnapshot(originalLayoutSnapshot);
            String oldLabel = originalLabel;
            String newLabel = label.getText() == null ? oldLabel : label.getText().toString().trim();
            int actionPosition = Math.max(0, actionSpinner.getSelectedItemPosition());
            String action = actionValues[Math.min(actionPosition, actionValues.length - 1)];
            TouchInputBinding.Option[] options = currentOptions[0] != null
                    ? currentOptions[0]
                    : TouchInputBinding.optionsForAction(action);
            int bindingPosition = Math.max(0, bindingSpinner.getSelectedItemPosition());
            if (options.length > 0) {
                TouchInputBinding.Option option = options[Math.min(bindingPosition, options.length - 1)];
                TouchInputBinding.applyOption(data, action, option);
            } else {
                data.action = action;
            }

            if (TouchControlActions.KEY.equals(action)) {
                data.setKeySlots(readKeySlotsFromSpinners(keySlotSpinners, keyOptions));
            }

            // Preserve exactly what the user typed in the Label field.
            // Changing the binding should never rename an existing/custom button.
            data.id = safeControlId(idField.getText() == null ? "" : idField.getText().toString());
            data.drawerOpenByDefault = TouchControlActions.DRAWER.equals(data.action)
                    && drawerOpenByDefault.isChecked();
            data.drawerOrientation = TouchControlData.normalizeDrawerOrientation(data.drawerOrientation);
            applyDrawerMembershipAfterEdit(originalId, data, selectedDrawerMemberIds);
            setMousePassThroughEnabled(data, mousePassThrough.isChecked());
            setSwipeGestureEnabled(data, swipeGesture.isChecked());
            data.label = emptyLabel.isChecked() ? "" : newLabel;

            // Only capture live geometry after a real move/resize/size edit.
            // Capturing an untouched, already-clamped bottom control baked the
            // renderer's temporary screen nudge into the JSON and moved A/D upward
            // every time the user simply opened and accepted the editor.
            boolean geometryActuallyChanged = panelHistory.geometryDirty
                    && (materiallyDifferent(originalX, data.x)
                    || materiallyDifferent(originalY, data.y)
                    || materiallyDifferent(originalWidth, data.width)
                    || materiallyDifferent(originalHeight, data.height)
                    || !Objects.equals(originalPositionAnchorX, data.positionAnchorX)
                    || !Objects.equals(originalPositionAnchorY, data.positionAnchorY));
            if (geometryActuallyChanged) {
                captureLiveControlGeometry(editingView, data);
            } else {
                data.x = originalX;
                data.y = originalY;
                data.width = originalWidth;
                data.height = originalHeight;
                data.rawX = originalRawX;
                data.rawY = originalRawY;
                data.positionAnchorX = originalPositionAnchorX;
                data.positionAnchorY = originalPositionAnchorY;
            }
            data.sizePercent = clamp(currentPercent[0], 30f, 250f);
            data.opacity = clamp(parseFloat(opacity, data.opacity), 0f, 1f);
            data.cornerRadius = Math.max(0f, parseFloat(cornerRadius, data.cornerRadius));
            data.strokeWidth = Math.max(0f, parseFloat(strokeWidth, data.strokeWidth));
            data.backgroundColor = selectedBackgroundColor[0];
            data.strokeColor = selectedStrokeColor[0];
            data.imageMode = imageModeSpinner.getSelectedItemPosition() == 1
                    ? TouchControlData.IMAGE_MODE_REPLACE
                    : TouchControlData.IMAGE_MODE_BACKGROUND;
            data.imageScalePercent = TouchControlData.clampImageScalePercent(imageScaleSlider.getProgress());
            data.imageOffsetXPercent = TouchControlData.clampImageOffsetPercent(imageOffsetXSlider.getProgress() - 100f);
            data.imageOffsetYPercent = TouchControlData.clampImageOffsetPercent(imageOffsetYSlider.getProgress() - 100f);
            data.joystickAbsolute = joystickAbsolute.isChecked();
            data.joystickForwardLock = joystickForwardLock.isChecked();
            data.joystickDeadzonePercent = TouchControlData.clampJoystickDeadzonePercent(
                    parseFloat(joystickDeadzone, data.joystickDeadzonePercent)
            );
            data.toggle = TouchControlActions.DRAWER.equals(data.action) ? false : toggle.isChecked();
            data.visibleInGame = visibleInGame.isChecked();
            data.visibleInMenu = visibleInMenu.isChecked();
            data.visibleWhenControlsHidden = visibleWhenControlsHidden.isChecked()
                    || TouchControlData.shouldStayVisibleWhenControlsHiddenByDefault(data.action);

            /*
             * Do not erase an untouched control's responsive formulas. The branch
             * above deliberately restores originalRawX/originalRawY when geometry did
             * not change; clearing them here converted a formula-positioned button to
             * stale fallback x/y values and made Save, Undo, or Redo move it.
             */
            accepted[0] = true;
            saveLayout();
            rebuildWhenSized();
            dialog.dismiss();
        });

        dialog.setOnShowListener(shown -> {
            // AlertDialog applies its themed minimum width during show(). Re-apply the
            // exact responsive drawer geometry afterwards so the framework cannot expand
            // the panel back over the selected control.
            panelControlsReady[0] = false;
            prepareControlEditSidePanel(dialog, editingView);
            refreshOpenEditorPanel.run();
            animatePreparedControlEditSidePanel(dialog);

            // Do not recalculate or replace the dialog's window geometry here. The
            // in-game editor is already a dialog, so this control editor is a nested
            // child window. Re-running side-panel positioning while that child is
            // taking focus can produce a zero/invalid viewport on some devices.
            // A lightweight content-only remeasure is enough to correct the
            // first-frame compressed action rows observed in game.
            Runnable remeasurePanelContent = () -> {
                if (activeEditDialog != dialog || !dialog.isShowing()) return;

                /*
                 * Keep the side-panel window geometry untouched. The in-game editor
                 * is a nested Dialog and changing that child window's bounds while it
                 * is taking focus caused the disappearing-panel regression. Instead,
                 * reserve the hidden navigation/gesture bar inside the panel content.
                 * This moves DELETE / CANCEL / OK above the system-bar-sized strip
                 * while the panel background, gravity, x/y and animation stay exactly
                 * as they were. Re-read the inset on each delayed measure pass because
                 * some Android vendors deliver the stable inset one frame late.
                 */
                int bottomSystemInset = controlEditBottomSystemInset(getRootView());
                if (panelRoot.getPaddingBottom() != bottomSystemInset) {
                    panelRoot.setPadding(
                            panelRoot.getPaddingLeft(),
                            panelRoot.getPaddingTop(),
                            panelRoot.getPaddingRight(),
                            bottomSystemInset
                    );
                }

                quickActionGroup.requestLayout();
                footerActionRow.requestLayout();
                panelRoot.requestLayout();
                View content = dialog.findViewById(android.R.id.content);
                if (content != null) content.requestLayout();
                Window panelWindow = dialog.getWindow();
                if (panelWindow != null) {
                    View decor = panelWindow.getDecorView();
                    if (decor != null) decor.requestLayout();
                }
            };

            panelRoot.post(() -> {
                remeasurePanelContent.run();
                if (activeEditDialog == dialog && dialog.isShowing()) {
                    panelControlsReady[0] = true;
                }
            });
            // A second content-only pass catches the vendor/inset pass that used to
            // be triggered incidentally by taking a screenshot. Window size, gravity,
            // x/y and entrance translation are deliberately left untouched.
            panelRoot.postDelayed(remeasurePanelContent, 96L);
            panelRoot.postDelayed(remeasurePanelContent, 220L);
        });

        dialog.setOnDismissListener(dismissed -> {
            panelControlsReady[0] = false;
            setEditDialogPreviewAlpha(dialog, false);
            if (activeEditDialog == dialog) activeEditDialog = null;
            if (activeEditGeometryUi != null && activeEditGeometryUi.data == data) {
                activeEditGeometryUi = null;
            }
            if (activePanelEditHistory == panelHistory) {
                activePanelEditHistory = null;
            }
            if (activeEditPanelSwitchController == switchController) {
                activeEditPanelSwitchController = null;
            }
            if (!accepted[0] && !deleted[0]) {
                data.id = originalId;
                data.label = originalLabel;
                data.action = originalAction;
                data.keyCode = originalKeyCode;
                data.keyCodes = originalKeyCodes;
                data.keySlots = originalKeySlots;
                data.mouseButton = originalMouseButton;
                data.scrollY = originalScrollY;
                data.x = originalX;
                data.y = originalY;
                data.width = originalWidth;
                data.height = originalHeight;
                data.sizePercent = originalSizePercent;
                data.opacity = originalOpacity;
                data.cornerRadius = originalCornerRadius;
                data.strokeWidth = originalStrokeWidth;
                data.strokeColor = originalStrokeColor;
                data.backgroundColor = originalBackgroundColor;
                data.imageUri = originalImageUri;
                data.imageMode = originalImageMode;
                data.imageScalePercent = originalImageScalePercent;
                data.imageOffsetXPercent = originalImageOffsetXPercent;
                data.imageOffsetYPercent = originalImageOffsetYPercent;
                data.toggle = originalToggle;
                data.visibleInGame = originalVisibleInGame;
                data.visibleInMenu = originalVisibleInMenu;
                data.visibleWhenControlsHidden = originalVisibleWhenControlsHidden;
                data.mousePassThrough = originalMousePassThrough;
                data.migrateMousePassThroughFromPreferences = false;
                data.swipeGesture = originalSwipeGesture;
                data.migrateSwipeGestureFromPreferences = false;
                if (isProfileVirtualMouseEnabled() != originalVirtualMouseEnabled) {
                    setProfileVirtualMouseEnabled(originalVirtualMouseEnabled);
                }
                data.joystickAbsolute = originalJoystickAbsolute;
                data.joystickForwardLock = originalJoystickForwardLock;
                data.joystickDeadzonePercent = originalJoystickDeadzonePercent;
                data.rawX = originalRawX;
                data.rawY = originalRawY;
                data.positionAnchorX = originalPositionAnchorX;
                data.positionAnchorY = originalPositionAnchorY;
                data.drawerParentId = originalDrawerParentId;
                data.drawerOrientation = originalDrawerOrientation;
                data.drawerOpenByDefault = originalDrawerOpenByDefault;
                // The side panel is non-modal, so the resize handle can update the
                // layout while it is open. Persist the restored values on Cancel.
                saveLayout();
                rebuildWhenSized();
            }
            setSelectedEditButton(null);
        });

        prepareControlEditSidePanel(dialog, editingView);

        try {
            if (!isAttachedToWindow()) {
                if (activeEditDialog == dialog) activeEditDialog = null;
                if (activeEditGeometryUi != null && activeEditGeometryUi.data == data) {
                    activeEditGeometryUi = null;
                }
                if (activePanelEditHistory == panelHistory) activePanelEditHistory = null;
                if (activeEditPanelSwitchController == switchController) activeEditPanelSwitchController = null;
                setSelectedEditButton(null);
                return;
            }
            dialog.show();
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to show touch control editor", throwable);
            if (activeEditDialog == dialog) activeEditDialog = null;
            if (activeEditGeometryUi != null && activeEditGeometryUi.data == data) {
                activeEditGeometryUi = null;
            }
            if (activePanelEditHistory == panelHistory) activePanelEditHistory = null;
            if (activeEditPanelSwitchController == switchController) activeEditPanelSwitchController = null;
            setSelectedEditButton(null);
            setEditDialogPreviewAlpha(dialog, false);
            Toast.makeText(context, "Unable to open touch editor.", Toast.LENGTH_LONG).show();
        }
    }



    public void beginBulkControlAppearanceChange() {
        if (bulkAppearanceUndoSnapshot == null) {
            bulkAppearanceUndoSnapshot = snapshotLayoutSafely();
        }
    }

    public void applyAllButtonCornerRadius(float radius) {
        applyAllButtonAppearance(Math.max(0f, radius), true);
    }

    public void applyAllButtonStrokeWidth(float strokeWidth) {
        applyAllButtonAppearance(Math.max(0f, strokeWidth), false);
    }

    private void applyAllButtonAppearance(float value, boolean radius) {
        if (layoutData.controls.isEmpty()) return;
        if (bulkAppearanceUndoSnapshot == null) {
            pushUndoSnapshot();
        }
        for (TouchControlData control : layoutData.controls) {
            if (radius) {
                control.cornerRadius = Math.max(0f, value);
            } else {
                control.strokeWidth = Math.max(0f, value);
            }
        }
        saveLayout();
        rebuildWhenSized();
    }

    public void finishBulkControlAppearanceChange() {
        if (bulkAppearanceUndoSnapshot != null) {
            pushUndoSnapshot(bulkAppearanceUndoSnapshot);
            bulkAppearanceUndoSnapshot = null;
        }
        saveLayout();
    }

    public int averageButtonCornerRadius() {
        if (layoutData.controls.isEmpty()) return 16;
        float total = 0f;
        for (TouchControlData control : layoutData.controls) total += Math.max(0f, control.cornerRadius);
        return Math.round(total / Math.max(1, layoutData.controls.size()));
    }

    public int averageButtonStrokeWidth() {
        if (layoutData.controls.isEmpty()) return 2;
        float total = 0f;
        for (TouchControlData control : layoutData.controls) total += Math.max(0f, control.strokeWidth);
        return Math.round(total / Math.max(1, layoutData.controls.size()));
    }

    @NonNull
    private static String displayLabelForDialog(@Nullable String label) {
        if (label == null || label.isEmpty()) return "(empty label)";
        String trimmed = label.trim();
        return trimmed.isEmpty() ? "(empty label)" : trimmed;
    }

    private boolean migrateNativeLayoutToResponsiveCanvas(int parentWidth, int parentHeight) {
        if (!layoutData.needsResponsiveCanvasMigration()) return false;
        if (parentWidth <= 1 || parentHeight <= 1 || layoutData.controls.isEmpty()) return false;

        // Resolve the legacy profile exactly as it is currently displayed, then store
        // that geometry against this overlay's reference canvas. Runtime scaling can
        // now use the target device resolution without guessing the original density.
        LayoutMetrics legacyMetrics = layoutMetrics(parentWidth, parentHeight);
        float density = Math.max(0.1f, getResources().getDisplayMetrics().density);

        for (TouchControlData control : layoutData.controls) {
            // Legacy native profiles already store dimensions in dp. Resolve only
            // the position to the current pixel canvas; keep width/height in dp so
            // another device applies its own Android density just like Pojav does.
            float storedWidthDp = Math.max(1f, control.width);
            float storedHeightDp = Math.max(1f, control.height);
            int baseWidth = baseControlScreenWidth(legacyMetrics, control, parentWidth);
            int baseHeight = baseControlScreenHeight(legacyMetrics, control, parentHeight);
            if (TouchControlActions.JOYSTICK.equals(control.action)) {
                int square = Math.max(1, Math.max(baseWidth, baseHeight));
                baseWidth = square;
                baseHeight = square;
                float storedSquareDp = Math.max(storedWidthDp, storedHeightDp);
                storedWidthDp = storedSquareDp;
                storedHeightDp = storedSquareDp;
            }

            float fallbackX = legacyMetrics.toScreenX(control, baseWidth);
            float fallbackY = legacyMetrics.toScreenY(control, baseHeight);
            float baseX = control.rawX == null
                    ? fallbackX
                    : ExpressionResolver.resolve(
                            control.rawX,
                            fallbackX,
                            parentWidth,
                            parentHeight,
                            baseWidth,
                            baseHeight,
                            density,
                            expressionPreferredScale(),
                            legacyMetrics.formulaPixelScale,
                            legacyMetrics.formulaDpScale
                    );
            float baseY = control.rawY == null
                    ? fallbackY
                    : ExpressionResolver.resolve(
                            control.rawY,
                            fallbackY,
                            parentWidth,
                            parentHeight,
                            baseWidth,
                            baseHeight,
                            density,
                            expressionPreferredScale(),
                            legacyMetrics.formulaPixelScale,
                            legacyMetrics.formulaDpScale
                    );

            control.x = baseX;
            control.y = baseY;
            control.width = storedWidthDp;
            control.height = storedHeightDp;

            // Corner radius and stroke width were also authored in dp. Leaving them
            // in dp keeps the complete control appearance physically consistent.
            control.cornerRadius = Math.max(0f, control.cornerRadius);
            control.strokeWidth = Math.max(0f, control.strokeWidth);
            control.rawX = null;
            control.rawY = null;
        }

        layoutData.coordinateUnit = TouchControlsLayoutData.UNIT_PX;
        layoutData.coordinateProfile = TouchControlsLayoutData.PROFILE_DROIDBRIDGE;
        layoutData.controlSizeUnit = TouchControlsLayoutData.UNIT_DP;
        layoutData.sourceWidth = parentWidth;
        layoutData.sourceHeight = parentHeight;
        layoutData.sourceDensity = density;
        layoutData.responsiveCanvas = true;
        layoutData.version = Math.max(
                layoutData.version,
                TouchControlsLayoutData.RESPONSIVE_FORMAT_VERSION
        );

        Logging.i(
                TAG,
                "Migrated native touch profile to responsive canvas "
                        + parentWidth + "x" + parentHeight
                        + " controls=" + layoutData.controls.size()
        );
        return true;
    }

    @NonNull
    private LayoutMetrics layoutMetrics(float parentWidth, float parentHeight) {
        float density = Math.max(0.1f, getResources().getDisplayMetrics().density);
        float safeParentWidth = Math.max(1f, parentWidth);
        float safeParentHeight = Math.max(1f, parentHeight);

        if (layoutData.usesOtherLauncherRuntimeRules()) {
            // compatible third-party launchers store dimensions in dp and authored them at
            // scaledAt/preferredScale. Their runtime first normalizes dimensions back
            // to 100%, then applies the user's current button-size setting. DroidBridge's
            // per-profile globalButtonScalePercent is that current setting.
            float authoredScale = layoutData.preferredScale > 0f
                    && !Float.isNaN(layoutData.preferredScale)
                    && !Float.isInfinite(layoutData.preferredScale)
                    ? layoutData.preferredScale
                    : 100f;
            float importedSizeScale = density * (getProfileGlobalButtonScalePercent() / authoredScale);

            // Dynamic formulas and legacy fixed x/y positions use actual screen pixels.
            // In Pojav-family expressions px(value) means dp->px, while dp(value) means
            // px->dp, so the two function scales intentionally differ here.
            return new LayoutMetrics(
                    false,
                    false,
                    safeParentWidth,
                    safeParentHeight,
                    safeParentWidth,
                    safeParentHeight,
                    1f,
                    1f,
                    importedSizeScale,
                    density,
                    1f / density
            );
        }

        if (layoutData.usesResponsiveCanvas()) {
            float sourceWidth = layoutData.resolvedSourceWidth(safeParentWidth);
            float sourceHeight = layoutData.resolvedSourceHeight(safeParentHeight);
            float positionXScale = safeParentWidth / Math.max(1f, sourceWidth);
            float positionYScale = safeParentHeight / Math.max(1f, sourceHeight);

            if (layoutData.usesResponsiveDpSizes()) {
                // Pojav's universal behavior: button dimensions and visual styling
                // follow Android density, while their placement adapts separately to
                // the current canvas. An ultrawide screen must not shrink every button
                // merely because its height is shorter than a 16:9 reference.
                return new LayoutMetrics(
                        true,
                        true,
                        safeParentWidth,
                        safeParentHeight,
                        sourceWidth,
                        sourceHeight,
                        positionXScale,
                        positionYScale,
                        density,
                        Math.min(positionXScale, positionYScale),
                        density,
                        layoutData.resolvedSourceDensity()
                );
            }

            // Version-7 responsive profiles stored width/height in source-canvas
            // pixels. Keep their legacy uniform behavior until a stock profile is
            // upgraded or the user edits/saves it in the new dp-sized format.
            float[] realDisplay = realDisplaySize(safeParentWidth, safeParentHeight);
            float parentScale = Math.min(positionXScale, positionYScale);
            float realScale = Math.min(
                    realDisplay[0] / Math.max(1f, sourceWidth),
                    realDisplay[1] / Math.max(1f, sourceHeight)
            );
            float uniformScale = clamp(
                    Math.max(parentScale, realScale),
                    MIN_RESPONSIVE_CANVAS_SCALE,
                    MAX_RESPONSIVE_CANVAS_SCALE
            );

            return new LayoutMetrics(
                    true,
                    true,
                    safeParentWidth,
                    safeParentHeight,
                    sourceWidth,
                    sourceHeight,
                    uniformScale,
                    uniformScale,
                    uniformScale,
                    uniformScale,
                    density
            );
        }

        if (layoutData.usesPixelCoordinates()) {
            float sourceWidth = layoutData.resolvedSourceWidth(safeParentWidth);
            float sourceHeight = layoutData.resolvedSourceHeight(safeParentHeight);
            float[] realDisplay = realDisplaySize(safeParentWidth, safeParentHeight);

            float parentScale = Math.min(
                    safeParentWidth / Math.max(1f, sourceWidth),
                    safeParentHeight / Math.max(1f, sourceHeight)
            );
            float realScale = Math.min(
                    realDisplay[0] / Math.max(1f, sourceWidth),
                    realDisplay[1] / Math.max(1f, sourceHeight)
            );

            boolean screenSizedCanvas = Math.abs(sourceWidth - safeParentWidth) <= Math.max(4f, safeParentWidth * 0.05f)
                    && Math.abs(sourceHeight - safeParentHeight) <= Math.max(4f, safeParentHeight * 0.05f);
            float uniformScale = screenSizedCanvas ? parentScale : Math.max(parentScale, realScale);

            // Legacy default_touch.json files from a 1920x1080 device normally land
            // as an 854x480-ish logical canvas. Ultrawide devices can report a shorter
            // app surface even though the physical game buffer is still 1080p-class,
            // which made the overlay shrink vertically. Keep those layouts at least at
            // their original 1080p scale when the current display is 1080p-class.
            boolean legacy1080pCanvas = sourceWidth <= 960f
                    && sourceHeight <= 540f
                    && Math.max(safeParentWidth, realDisplay[0]) >= 1800f
                    && Math.max(safeParentHeight, realDisplay[1]) >= 900f;
            if (legacy1080pCanvas) {
                uniformScale = Math.max(uniformScale, 1080f / Math.max(1f, sourceHeight));
            }

            return new LayoutMetrics(
                    true,
                    false,
                    safeParentWidth,
                    safeParentHeight,
                    sourceWidth,
                    sourceHeight,
                    uniformScale,
                    uniformScale,
                    uniformScale,
                    uniformScale,
                    density
            );
        }

        return new LayoutMetrics(
                false,
                false,
                safeParentWidth,
                safeParentHeight,
                safeParentWidth / density,
                safeParentHeight / density,
                density,
                density,
                density,
                density,
                density
        );
    }

    private float globalButtonScaleMultiplier() {
        return getProfileGlobalButtonScalePercent() / 100f;
    }

    private float renderGlobalButtonScaleMultiplier() {
        // Pojav-family profiles already include their current per-profile size in
        // LayoutMetrics. Applying DroidBridge's profile scale a second time would
        // double-scale them, but the dedicated bottom-screen multiplier still needs
        // to work so dual-screen controls are not tiny on a phone/TV setup.
        float scale = layoutData.usesOtherLauncherRuntimeRules() ? 1f : globalButtonScaleMultiplier();
        if (dualScreenBottomHudHotbarMode) {
            scale *= ControlsPreferences.getDualScreenButtonScalePercent(getContext()) / 100f;
        }
        return Math.max(0.50f, Math.min(4.00f, scale));
    }

    private float expressionPreferredScale() {
        return layoutData.usesOtherLauncherRuntimeRules()
                ? getProfileGlobalButtonScalePercent()
                : layoutData.preferredScale;
    }

    private int baseControlScreenWidth(
            @NonNull LayoutMetrics metrics,
            @NonNull TouchControlData control,
            int parentWidth
    ) {
        return Math.min(Math.max(1, parentWidth), Math.max(1, metrics.toScreenWidth(control.width)));
    }

    private int baseControlScreenHeight(
            @NonNull LayoutMetrics metrics,
            @NonNull TouchControlData control,
            int parentHeight
    ) {
        return Math.min(Math.max(1, parentHeight), Math.max(1, metrics.toScreenHeight(control.height)));
    }

    private int scaledControlScreenWidth(
            @NonNull LayoutMetrics metrics,
            @NonNull TouchControlData control,
            int parentWidth
    ) {
        int baseWidth = baseControlScreenWidth(metrics, control, parentWidth);
        int scaledWidth = Math.round(baseWidth * renderGlobalButtonScaleMultiplier());
        return Math.min(Math.max(1, parentWidth), Math.max(1, scaledWidth));
    }

    private int scaledControlScreenHeight(
            @NonNull LayoutMetrics metrics,
            @NonNull TouchControlData control,
            int parentHeight
    ) {
        int baseHeight = baseControlScreenHeight(metrics, control, parentHeight);
        int scaledHeight = Math.round(baseHeight * renderGlobalButtonScaleMultiplier());
        return Math.min(Math.max(1, parentHeight), Math.max(1, scaledHeight));
    }

    private void keepAttachedScaledControlsInsideScreen(
            @NonNull ArrayList<ScaledControlItem> items,
            int parentWidth,
            int parentHeight
    ) {
        int count = items.size();
        if (count == 0) return;

        boolean[] visited = new boolean[count];
        // Controls that are visually arranged as a row/cluster must move together
        // while the global scaler is active.  The old 6dp tolerance was too small
        // for normal 12-24px button gaps on 1080p layouts, so edge clamping could
        // move neighbouring buttons independently and make them overlap.
        float attachTolerance = Math.max(24f, 12f * getResources().getDisplayMetrics().density);
        int[] stack = new int[count];

        for (int start = 0; start < count; start++) {
            if (visited[start]) continue;

            int stackSize = 0;
            int clusterSize = 0;
            int[] cluster = new int[count];
            stack[stackSize++] = start;
            visited[start] = true;

            while (stackSize > 0) {
                int index = stack[--stackSize];
                cluster[clusterSize++] = index;
                ScaledControlItem current = items.get(index);

                for (int other = 0; other < count; other++) {
                    if (visited[other]) continue;
                    if (!areBaseRectsAttached(current, items.get(other), attachTolerance)) continue;
                    visited[other] = true;
                    stack[stackSize++] = other;
                }
            }

            nudgeScaledClusterInsideScreen(
                    items,
                    cluster,
                    clusterSize,
                    parentWidth,
                    parentHeight,
                    attachTolerance
            );
        }
    }

    private boolean areBaseRectsAttached(
            @NonNull ScaledControlItem a,
            @NonNull ScaledControlItem b,
            float tolerance
    ) {
        float aLeft = a.baseX - tolerance;
        float aTop = a.baseY - tolerance;
        float aRight = a.baseX + a.baseWidth + tolerance;
        float aBottom = a.baseY + a.baseHeight + tolerance;

        float bLeft = b.baseX;
        float bTop = b.baseY;
        float bRight = b.baseX + b.baseWidth;
        float bBottom = b.baseY + b.baseHeight;

        return aLeft < bRight
                && aRight > bLeft
                && aTop < bBottom
                && aBottom > bTop;
    }

    private void nudgeScaledClusterInsideScreen(
            @NonNull ArrayList<ScaledControlItem> items,
            @NonNull int[] cluster,
            int clusterSize,
            int parentWidth,
            int parentHeight,
            float attachTolerance
    ) {
        if (clusterSize <= 0) return;

        float left = Float.MAX_VALUE;
        float top = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;

        for (int i = 0; i < clusterSize; i++) {
            ScaledControlItem item = items.get(cluster[i]);
            left = Math.min(left, item.x);
            top = Math.min(top, item.y);
            right = Math.max(right, item.x + item.scaledWidth);
            bottom = Math.max(bottom, item.y + item.scaledHeight);
        }

        // If scaling makes a connected horizontal control group wider than the
        // display, simply clamping the group cannot work: the buttons physically
        // no longer fit on one line.  Reflow the group onto another line while
        // preserving the original order and scaled gaps.  This is what makes a
        // dense toolbar behave like "buttons get bigger and the neighbours shift"
        // instead of growing on top of each other.
        if (right - left > parentWidth && clusterSize > 1) {
            reflowOversizedScaledCluster(
                    items,
                    cluster,
                    clusterSize,
                    parentWidth,
                    parentHeight,
                    attachTolerance
            );
            return;
        }

        float dx = 0f;
        float dy = 0f;

        if (left < 0f) dx = -left;
        else if (right > parentWidth) dx = parentWidth - right;

        if (bottom - top <= parentHeight) {
            if (top < 0f) dy = -top;
            else if (bottom > parentHeight) dy = parentHeight - bottom;
        } else {
            dy = top < 0f ? -top : 0f;
        }

        if (dx == 0f && dy == 0f) return;
        for (int i = 0; i < clusterSize; i++) {
            ScaledControlItem item = items.get(cluster[i]);
            item.x += dx;
            item.y += dy;
        }
    }

    private void reflowOversizedScaledCluster(
            @NonNull ArrayList<ScaledControlItem> items,
            @NonNull int[] cluster,
            int clusterSize,
            int parentWidth,
            int parentHeight,
            float attachTolerance
    ) {
        if (clusterSize <= 1 || parentWidth <= 1) return;

        ArrayList<Integer> ordered = new ArrayList<>(clusterSize);
        for (int i = 0; i < clusterSize; i++) ordered.add(cluster[i]);
        ordered.sort((a, b) -> {
            ScaledControlItem ia = items.get(a);
            ScaledControlItem ib = items.get(b);
            int yCompare = Float.compare(ia.baseY, ib.baseY);
            return yCompare != 0 ? yCompare : Float.compare(ia.baseX, ib.baseX);
        });

        // Build logical rows from the 100% geometry.  We deliberately use the
        // unscaled positions here so changing the global scale never changes which
        // controls are considered neighbours.
        ArrayList<ArrayList<Integer>> rows = new ArrayList<>();
        ArrayList<Float> rowCenters = new ArrayList<>();
        ArrayList<Float> rowMaxHeights = new ArrayList<>();

        for (Integer index : ordered) {
            ScaledControlItem item = items.get(index);
            float centerY = item.baseY + (item.baseHeight / 2f);
            int rowIndex = -1;
            for (int r = 0; r < rows.size(); r++) {
                float threshold = Math.max(item.baseHeight, rowMaxHeights.get(r)) * 0.40f;
                if (Math.abs(centerY - rowCenters.get(r)) <= threshold) {
                    rowIndex = r;
                    break;
                }
            }

            if (rowIndex < 0) {
                ArrayList<Integer> row = new ArrayList<>();
                row.add(index);
                rows.add(row);
                rowCenters.add(centerY);
                rowMaxHeights.add(item.baseHeight);
            } else {
                ArrayList<Integer> row = rows.get(rowIndex);
                row.add(index);
                float sum = 0f;
                float maxHeight = 0f;
                for (Integer rowItemIndex : row) {
                    ScaledControlItem rowItem = items.get(rowItemIndex);
                    sum += rowItem.baseY + (rowItem.baseHeight / 2f);
                    maxHeight = Math.max(maxHeight, rowItem.baseHeight);
                }
                rowCenters.set(rowIndex, sum / row.size());
                rowMaxHeights.set(rowIndex, maxHeight);
            }
        }

        float scale = renderGlobalButtonScaleMultiplier();
        float density = getResources().getDisplayMetrics().density;
        float wrapGapY = Math.max(6f, 6f * density) * Math.max(1f, scale);
        float cursorBottom = Float.NEGATIVE_INFINITY;
        float previousBaseBottom = Float.NaN;

        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            ArrayList<Integer> row = rows.get(rowIndex);
            row.sort((a, b) -> Float.compare(items.get(a).baseX, items.get(b).baseX));

            float baseTop = Float.MAX_VALUE;
            float baseBottom = -Float.MAX_VALUE;
            float desiredTop = Float.MAX_VALUE;
            for (Integer index : row) {
                ScaledControlItem item = items.get(index);
                baseTop = Math.min(baseTop, item.baseY);
                baseBottom = Math.max(baseBottom, item.baseY + item.baseHeight);
                desiredTop = Math.min(desiredTop, item.y);
            }

            float rowTop;
            if (rowIndex == 0 || cursorBottom == Float.NEGATIVE_INFINITY) {
                rowTop = Math.max(0f, desiredTop);
            } else {
                float baseGap = Float.isNaN(previousBaseBottom)
                        ? wrapGapY
                        : Math.max(wrapGapY, Math.max(0f, baseTop - previousBaseBottom) * scale);
                rowTop = Math.max(desiredTop, cursorBottom + baseGap);
            }

            // Split a row into horizontal sub-groups.  This keeps intentionally
            // separated left/right groups separated while still treating a dense
            // toolbar as one ordered strip that can wrap.
            ArrayList<ArrayList<Integer>> segments = new ArrayList<>();
            ArrayList<Integer> segment = new ArrayList<>();
            for (Integer index : row) {
                if (segment.isEmpty()) {
                    segment.add(index);
                    continue;
                }
                ScaledControlItem previous = items.get(segment.get(segment.size() - 1));
                ScaledControlItem current = items.get(index);
                float baseGap = current.baseX - (previous.baseX + previous.baseWidth);
                if (baseGap <= attachTolerance) {
                    segment.add(index);
                } else {
                    segments.add(segment);
                    segment = new ArrayList<>();
                    segment.add(index);
                }
            }
            if (!segment.isEmpty()) segments.add(segment);

            float rowBottom = rowTop;
            for (ArrayList<Integer> currentSegment : segments) {
                if (currentSegment.isEmpty()) continue;

                float totalWidth = items.get(currentSegment.get(0)).scaledWidth;
                for (int i = 1; i < currentSegment.size(); i++) {
                    ScaledControlItem previous = items.get(currentSegment.get(i - 1));
                    ScaledControlItem current = items.get(currentSegment.get(i));
                    float gap = Math.max(0f,
                            current.baseX - (previous.baseX + previous.baseWidth)) * scale;
                    totalWidth += gap + current.scaledWidth;
                }

                if (totalWidth <= parentWidth) {
                    float desiredLeft = Float.MAX_VALUE;
                    for (Integer index : currentSegment) {
                        desiredLeft = Math.min(desiredLeft, items.get(index).x);
                    }
                    float x = Math.max(0f, Math.min(parentWidth - totalWidth, desiredLeft));
                    float maxHeight = 0f;
                    for (int i = 0; i < currentSegment.size(); i++) {
                        ScaledControlItem item = items.get(currentSegment.get(i));
                        if (i > 0) {
                            ScaledControlItem previous = items.get(currentSegment.get(i - 1));
                            float gap = Math.max(0f,
                                    item.baseX - (previous.baseX + previous.baseWidth)) * scale;
                            x += gap;
                        }
                        item.x = x;
                        item.y = rowTop;
                        x += item.scaledWidth;
                        maxHeight = Math.max(maxHeight, item.scaledHeight);
                    }
                    rowBottom = Math.max(rowBottom, rowTop + maxHeight);
                    continue;
                }

                // The segment cannot fit on one line at this scale.  Wrap it in
                // source order.  No button size is reduced and no overlap is
                // introduced; only the positions move.
                float x = 0f;
                float y = rowTop;
                float lineHeight = 0f;
                ScaledControlItem previous = null;
                boolean lineHasItem = false;
                for (Integer index : currentSegment) {
                    ScaledControlItem item = items.get(index);
                    float gap = 0f;
                    if (previous != null && lineHasItem) {
                        gap = Math.max(0f,
                                item.baseX - (previous.baseX + previous.baseWidth)) * scale;
                    }

                    if (lineHasItem && x + gap + item.scaledWidth > parentWidth) {
                        y += lineHeight + wrapGapY;
                        x = 0f;
                        lineHeight = 0f;
                        gap = 0f;
                        lineHasItem = false;
                    }

                    item.x = x + gap;
                    item.y = y;
                    x = item.x + item.scaledWidth;
                    lineHeight = Math.max(lineHeight, item.scaledHeight);
                    lineHasItem = true;
                    previous = item;
                }
                rowBottom = Math.max(rowBottom, y + lineHeight);
            }

            cursorBottom = rowBottom;
            previousBaseBottom = baseBottom;
        }

        float clusterTop = Float.MAX_VALUE;
        float clusterBottom = -Float.MAX_VALUE;
        for (int i = 0; i < clusterSize; i++) {
            ScaledControlItem item = items.get(cluster[i]);
            clusterTop = Math.min(clusterTop, item.y);
            clusterBottom = Math.max(clusterBottom, item.y + item.scaledHeight);
        }
        if (clusterBottom > parentHeight) {
            float dy = parentHeight - clusterBottom;
            if (clusterTop + dy < 0f) dy = -clusterTop;
            for (int i = 0; i < clusterSize; i++) items.get(cluster[i]).y += dy;
        }
    }

    private float scaledControlScreenX(float baseX, float baseWidth, float scaledWidth, float parentWidth, float scale) {
        if (scale <= 0.001f) scale = 1f;
        float parentCenter = parentWidth / 2f;
        float baseCenter = baseX + (baseWidth / 2f);
        float scaledCenter = parentCenter + ((baseCenter - parentCenter) * scale);
        return scaledCenter - (scaledWidth / 2f);
    }

    private float scaledControlScreenX(
            float baseX,
            float baseWidth,
            float scaledWidth,
            float parentWidth,
            float scale,
            @Nullable String anchorValue
    ) {
        // Global scaling is deliberately centre-relative.  Left/right anchoring here
        // makes the two halves of a dense row grow toward each other and overlap.
        return scaledControlScreenX(baseX, baseWidth, scaledWidth, parentWidth, scale);
    }

    private float scaledControlScreenY(float baseY, float baseHeight, float scaledHeight, float parentHeight, float scale) {
        if (scale <= 0.001f) scale = 1f;
        float parentCenter = parentHeight / 2f;
        float baseCenter = baseY + (baseHeight / 2f);
        float scaledCenter = parentCenter + ((baseCenter - parentCenter) * scale);
        return scaledCenter - (scaledHeight / 2f);
    }

    private float scaledControlScreenY(
            float baseY,
            float baseHeight,
            float scaledHeight,
            float parentHeight,
            float scale,
            @Nullable String anchorValue
    ) {
        return scaledControlScreenY(baseY, baseHeight, scaledHeight, parentHeight, scale);
    }

    private float unscaledControlScreenX(float scaledX, float baseWidth, float scaledWidth, float parentWidth, float scale) {
        if (scale <= 0.001f) scale = 1f;
        float parentCenter = parentWidth / 2f;
        float scaledCenter = scaledX + (scaledWidth / 2f);
        float baseCenter = parentCenter + ((scaledCenter - parentCenter) / scale);
        return baseCenter - (baseWidth / 2f);
    }

    private float unscaledControlScreenX(
            float scaledX,
            float baseWidth,
            float scaledWidth,
            float parentWidth,
            float scale,
            @Nullable String anchorValue
    ) {
        return unscaledControlScreenX(scaledX, baseWidth, scaledWidth, parentWidth, scale);
    }

    private float unscaledControlScreenY(float scaledY, float baseHeight, float scaledHeight, float parentHeight, float scale) {
        if (scale <= 0.001f) scale = 1f;
        float parentCenter = parentHeight / 2f;
        float scaledCenter = scaledY + (scaledHeight / 2f);
        float baseCenter = parentCenter + ((scaledCenter - parentCenter) / scale);
        return baseCenter - (baseHeight / 2f);
    }

    private float unscaledControlScreenY(
            float scaledY,
            float baseHeight,
            float scaledHeight,
            float parentHeight,
            float scale,
            @Nullable String anchorValue
    ) {
        return unscaledControlScreenY(scaledY, baseHeight, scaledHeight, parentHeight, scale);
    }

    @NonNull
    private String resolveScaleHorizontalAnchor(
            @Nullable String anchorValue,
            float screenX,
            float screenControlWidth,
            float parentWidth
    ) {
        String explicit = TouchControlData.normalizeHorizontalPositionAnchor(anchorValue);
        if (explicit != null) return explicit;

        float center = screenX + (screenControlWidth / 2f);
        if (center <= parentWidth * 0.42f) return TouchControlData.POSITION_ANCHOR_LEFT;
        if (center >= parentWidth * 0.58f) return TouchControlData.POSITION_ANCHOR_RIGHT;
        return TouchControlData.POSITION_ANCHOR_CENTER;
    }

    @NonNull
    private String resolveScaleVerticalAnchor(
            @Nullable String anchorValue,
            float screenY,
            float screenControlHeight,
            float parentHeight
    ) {
        String explicit = TouchControlData.normalizeVerticalPositionAnchor(anchorValue);
        if (explicit != null) return explicit;

        float center = screenY + (screenControlHeight / 2f);
        if (center <= parentHeight * 0.42f) return TouchControlData.POSITION_ANCHOR_TOP;
        if (center >= parentHeight * 0.58f) return TouchControlData.POSITION_ANCHOR_BOTTOM;
        return TouchControlData.POSITION_ANCHOR_CENTER;
    }

    @NonNull
    private float[] realDisplaySize(float parentWidth, float parentHeight) {
        float realWidth = Math.max(1f, parentWidth);
        float realHeight = Math.max(1f, parentHeight);

        // A dual-screen controls deck must scale to the canvas it is actually drawn on.
        // Centered portrait keeps using the full portrait control canvas; only raw game
        // input is mapped into the centered Minecraft rectangle.
        if (dualScreenBottomHudHotbarMode) {
            return new float[]{realWidth, realHeight};
        }

        try {
            WindowManager windowManager = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
            if (windowManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Rect current = windowManager.getCurrentWindowMetrics().getBounds();
                    Rect maximum = windowManager.getMaximumWindowMetrics().getBounds();
                    realWidth = Math.max(realWidth, Math.max(current.width(), maximum.width()));
                    realHeight = Math.max(realHeight, Math.max(current.height(), maximum.height()));
                } else {
                    DisplayMetrics metrics = new DisplayMetrics();
                    //noinspection deprecation
                    windowManager.getDefaultDisplay().getRealMetrics(metrics);
                    realWidth = Math.max(realWidth, metrics.widthPixels);
                    realHeight = Math.max(realHeight, metrics.heightPixels);
                }
            }
        } catch (Throwable ignored) {
        }

        boolean landscape = parentWidth >= parentHeight;
        float longSide = Math.max(realWidth, realHeight);
        float shortSide = Math.min(realWidth, realHeight);
        return landscape ? new float[]{longSide, shortSide} : new float[]{shortSide, longSide};
    }

    private static final class LayoutMetrics {
        final boolean pixelCoordinates;
        final boolean responsiveAnchors;
        final float parentWidth;
        final float parentHeight;
        final float sourceWidth;
        final float sourceHeight;
        final float xScale;
        final float yScale;
        final float sizeScale;
        final float formulaPixelScale;
        final float formulaDpScale;
        final float sourceSizeScale;

        LayoutMetrics(
                boolean pixelCoordinates,
                boolean responsiveAnchors,
                float parentWidth,
                float parentHeight,
                float sourceWidth,
                float sourceHeight,
                float xScale,
                float yScale,
                float sizeScale,
                float formulaPixelScale,
                float formulaDpScale
        ) {
            this(
                    pixelCoordinates,
                    responsiveAnchors,
                    parentWidth,
                    parentHeight,
                    sourceWidth,
                    sourceHeight,
                    xScale,
                    yScale,
                    sizeScale,
                    formulaPixelScale,
                    formulaDpScale,
                    1f
            );
        }

        LayoutMetrics(
                boolean pixelCoordinates,
                boolean responsiveAnchors,
                float parentWidth,
                float parentHeight,
                float sourceWidth,
                float sourceHeight,
                float xScale,
                float yScale,
                float sizeScale,
                float formulaPixelScale,
                float formulaDpScale,
                float sourceSizeScale
        ) {
            this.pixelCoordinates = pixelCoordinates;
            this.responsiveAnchors = responsiveAnchors;
            this.parentWidth = Math.max(1f, parentWidth);
            this.parentHeight = Math.max(1f, parentHeight);
            this.sourceWidth = Math.max(1f, sourceWidth);
            this.sourceHeight = Math.max(1f, sourceHeight);
            this.xScale = Math.max(0.1f, xScale);
            this.yScale = Math.max(0.1f, yScale);
            this.sizeScale = Math.max(0.1f, sizeScale);
            this.formulaPixelScale = Math.max(0.1f, formulaPixelScale);
            this.formulaDpScale = Math.max(0.0001f, formulaDpScale);
            this.sourceSizeScale = Math.max(0.0001f, sourceSizeScale);
        }

        int toScreenWidth(float value) {
            return Math.max(32, Math.round(Math.max(1f, value) * sizeScale));
        }

        int toScreenHeight(float value) {
            return Math.max(32, Math.round(Math.max(1f, value) * sizeScale));
        }

        float toScreenX(@NonNull TouchControlData control, float screenControlWidth) {
            if (!pixelCoordinates) return control.x * xScale;

            float sourceControlWidth = Math.max(1f, control.width * sourceSizeScale);
            String anchor = resolvedHorizontalAnchor(control, sourceControlWidth);
            return screenXForSourcePosition(
                    control.x,
                    sourceControlWidth,
                    screenControlWidth,
                    anchor
            );
        }

        @Nullable
        String horizontalAnchorForScreenPosition(float screenX, float screenControlWidth) {
            if (!pixelCoordinates) return null;
            float screenCenterX = screenX + (screenControlWidth / 2f);
            if (screenCenterX <= parentWidth * 0.42f) {
                return TouchControlData.POSITION_ANCHOR_LEFT;
            }
            if (screenCenterX >= parentWidth * 0.58f) {
                return TouchControlData.POSITION_ANCHOR_RIGHT;
            }
            return TouchControlData.POSITION_ANCHOR_CENTER;
        }

        @Nullable
        String horizontalAnchorForSourcePosition(float sourceX, float controlWidth) {
            if (!pixelCoordinates) return null;
            float sourceControlWidth = Math.max(1f, controlWidth * sourceSizeScale);
            return inferredHorizontalAnchor(sourceX, sourceControlWidth);
        }

        @NonNull
        private String resolvedHorizontalAnchor(
                @NonNull TouchControlData control,
                float sourceControlWidth
        ) {
            String explicit = TouchControlData.normalizeHorizontalPositionAnchor(
                    control.positionAnchorX
            );
            return explicit != null
                    ? explicit
                    : inferredHorizontalAnchor(control.x, sourceControlWidth);
        }

        @NonNull
        private String inferredHorizontalAnchor(float sourceX, float sourceControlWidth) {
            if (sourceX < 0f || sourceX + sourceControlWidth > sourceWidth) {
                return TouchControlData.POSITION_ANCHOR_CENTER;
            }

            float sourceCenterX = sourceX + (sourceControlWidth / 2f);
            if (sourceCenterX >= sourceWidth * 0.58f) {
                return TouchControlData.POSITION_ANCHOR_RIGHT;
            }
            if (sourceCenterX <= sourceWidth * 0.42f) {
                return TouchControlData.POSITION_ANCHOR_LEFT;
            }
            return TouchControlData.POSITION_ANCHOR_CENTER;
        }

        private float screenXForSourcePosition(
                float sourceX,
                float sourceControlWidth,
                float screenControlWidth,
                @Nullable String anchorValue
        ) {
            String anchor = TouchControlData.normalizeHorizontalPositionAnchor(anchorValue);
            if (TouchControlData.POSITION_ANCHOR_RIGHT.equals(anchor)) {
                float sourceRightOffset = sourceWidth - sourceX - sourceControlWidth;
                return parentWidth - (sourceRightOffset * xScale) - screenControlWidth;
            }
            if (TouchControlData.POSITION_ANCHOR_LEFT.equals(anchor)) {
                return sourceX * xScale;
            }

            float sourceCenterX = sourceX + (sourceControlWidth / 2f);
            return centerMappedScreenX(sourceCenterX, screenControlWidth);
        }

        private float centerMappedScreenX(float sourceCenterX, float screenControlWidth) {
            float sourceCenterOffset = sourceCenterX - (sourceWidth / 2f);
            return (parentWidth / 2f) + (sourceCenterOffset * xScale) - (screenControlWidth / 2f);
        }

        private float centerMappedSourceX(float screenX, float screenControlWidth, float sourceControlWidth) {
            float screenCenter = screenX + (screenControlWidth / 2f);
            return (sourceWidth / 2f) + ((screenCenter - (parentWidth / 2f)) / xScale) - (sourceControlWidth / 2f);
        }

        float toScreenY(@NonNull TouchControlData control, float screenControlHeight) {
            if (!pixelCoordinates) return control.y * yScale;
            if (!responsiveAnchors) return control.y * yScale;

            float sourceControlHeight = Math.max(1f, control.height * sourceSizeScale);
            String anchor = resolvedVerticalAnchor(control, sourceControlHeight);
            return screenYForSourcePosition(
                    control.y,
                    sourceControlHeight,
                    screenControlHeight,
                    anchor
            );
        }

        @Nullable
        String verticalAnchorForScreenPosition(float screenY, float screenControlHeight) {
            if (!pixelCoordinates || !responsiveAnchors) return null;
            float screenCenterY = screenY + (screenControlHeight / 2f);
            if (screenCenterY <= parentHeight * 0.42f) {
                return TouchControlData.POSITION_ANCHOR_TOP;
            }
            if (screenCenterY >= parentHeight * 0.58f) {
                return TouchControlData.POSITION_ANCHOR_BOTTOM;
            }
            return TouchControlData.POSITION_ANCHOR_CENTER;
        }

        @Nullable
        String verticalAnchorForSourcePosition(float sourceY, float controlHeight) {
            if (!pixelCoordinates || !responsiveAnchors) return null;
            float sourceControlHeight = Math.max(1f, controlHeight * sourceSizeScale);
            return inferredVerticalAnchor(sourceY, sourceControlHeight);
        }

        @NonNull
        private String resolvedVerticalAnchor(
                @NonNull TouchControlData control,
                float sourceControlHeight
        ) {
            String explicit = TouchControlData.normalizeVerticalPositionAnchor(
                    control.positionAnchorY
            );
            return explicit != null
                    ? explicit
                    : inferredVerticalAnchor(control.y, sourceControlHeight);
        }

        @NonNull
        private String inferredVerticalAnchor(float sourceY, float sourceControlHeight) {
            if (sourceY < 0f || sourceY + sourceControlHeight > sourceHeight) {
                return TouchControlData.POSITION_ANCHOR_CENTER;
            }

            float sourceCenterY = sourceY + (sourceControlHeight / 2f);
            if (sourceCenterY >= sourceHeight * 0.58f) {
                return TouchControlData.POSITION_ANCHOR_BOTTOM;
            }
            if (sourceCenterY <= sourceHeight * 0.42f) {
                return TouchControlData.POSITION_ANCHOR_TOP;
            }
            return TouchControlData.POSITION_ANCHOR_CENTER;
        }

        private float screenYForSourcePosition(
                float sourceY,
                float sourceControlHeight,
                float screenControlHeight,
                @Nullable String anchorValue
        ) {
            String anchor = TouchControlData.normalizeVerticalPositionAnchor(anchorValue);
            if (TouchControlData.POSITION_ANCHOR_BOTTOM.equals(anchor)) {
                float sourceBottomOffset = sourceHeight - sourceY - sourceControlHeight;
                return parentHeight - (sourceBottomOffset * yScale) - screenControlHeight;
            }
            if (TouchControlData.POSITION_ANCHOR_TOP.equals(anchor)) {
                return sourceY * yScale;
            }

            float sourceCenterY = sourceY + (sourceControlHeight / 2f);
            return centerMappedScreenY(sourceCenterY, screenControlHeight);
        }

        private float centerMappedScreenY(float sourceCenterY, float screenControlHeight) {
            float sourceCenterOffset = sourceCenterY - (sourceHeight / 2f);
            return (parentHeight / 2f)
                    + (sourceCenterOffset * yScale)
                    - (screenControlHeight / 2f);
        }

        private float centerMappedSourceY(
                float screenY,
                float screenControlHeight,
                float sourceControlHeight
        ) {
            float screenCenter = screenY + (screenControlHeight / 2f);
            return (sourceHeight / 2f)
                    + ((screenCenter - (parentHeight / 2f)) / yScale)
                    - (sourceControlHeight / 2f);
        }

        float fromScreenX(@NonNull TouchControlData control, float screenX, float screenControlWidth) {
            if (!pixelCoordinates) return screenX / xScale;

            float sourceControlWidth = Math.max(1f, control.width * sourceSizeScale);
            String anchor = TouchControlData.normalizeHorizontalPositionAnchor(
                    control.positionAnchorX
            );
            if (anchor == null) {
                anchor = horizontalAnchorForScreenPosition(screenX, screenControlWidth);
            }

            float sourceX;
            if (TouchControlData.POSITION_ANCHOR_RIGHT.equals(anchor)) {
                sourceX = sourceWidth
                        - ((parentWidth - screenX - screenControlWidth) / xScale)
                        - sourceControlWidth;
            } else if (TouchControlData.POSITION_ANCHOR_LEFT.equals(anchor)) {
                sourceX = screenX / xScale;
            } else {
                sourceX = centerMappedSourceX(
                        screenX,
                        screenControlWidth,
                        sourceControlWidth
                );
            }
            return clampCandidate(
                    sourceX,
                    0f,
                    Math.max(0f, sourceWidth - sourceControlWidth)
            );
        }

        float fromScreenY(
                @NonNull TouchControlData control,
                float screenY,
                float screenControlHeight
        ) {
            if (!pixelCoordinates) return screenY / yScale;
            if (!responsiveAnchors) return screenY / yScale;

            float sourceControlHeight = Math.max(1f, control.height * sourceSizeScale);
            String anchor = TouchControlData.normalizeVerticalPositionAnchor(
                    control.positionAnchorY
            );
            if (anchor == null) {
                anchor = verticalAnchorForScreenPosition(screenY, screenControlHeight);
            }

            float sourceY;
            if (TouchControlData.POSITION_ANCHOR_BOTTOM.equals(anchor)) {
                sourceY = sourceHeight
                        - ((parentHeight - screenY - screenControlHeight) / yScale)
                        - sourceControlHeight;
            } else if (TouchControlData.POSITION_ANCHOR_TOP.equals(anchor)) {
                sourceY = screenY / yScale;
            } else {
                sourceY = centerMappedSourceY(
                        screenY,
                        screenControlHeight,
                        sourceControlHeight
                );
            }
            return clampCandidate(
                    sourceY,
                    0f,
                    Math.max(0f, sourceHeight - sourceControlHeight)
            );
        }

        private static float clampCandidate(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }

        float fromScreenWidth(float screenWidth) {
            return screenWidth / sizeScale;
        }

        float fromScreenHeight(float screenHeight) {
            return screenHeight / sizeScale;
        }

        float maxLayoutXUnits() {
            return pixelCoordinates ? sourceWidth : parentWidth / xScale;
        }

        float maxLayoutYUnits() {
            return pixelCoordinates ? sourceHeight : parentHeight / yScale;
        }
    }

    private void addSectionHeader(@NonNull LinearLayout parent, @NonNull String title, @Nullable String subtitle) {
        TextView header = new TextView(getContext());
        header.setText(title);
        header.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        header.setTextSize(16f);
        header.setPadding(0, dp(11f), 0, dp(2f));
        parent.addView(header);
        if (subtitle != null && !subtitle.trim().isEmpty()) {
            TextView sub = new TextView(getContext());
            sub.setText(subtitle);
            sub.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
            sub.setTextSize(12f);
            sub.setPadding(0, 0, 0, dp(6f));
            parent.addView(sub);
        }
    }

    private void addFieldRow(@NonNull LinearLayout parent, @NonNull String title, @NonNull EditText field) {
        // A quarter-width side panel cannot support desktop-style label/value columns.
        // Stack the label above the field so the value remains readable and editable.
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(3f), 0, dp(4f));

        TextView label = new TextView(getContext());
        label.setText(title);
        label.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        label.setTextSize(11f);
        row.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        row.addView(field, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        parent.addView(row);
    }

    private void addViewFieldRow(@NonNull LinearLayout parent, @NonNull String title, @NonNull View field) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(3f), 0, dp(4f));

        TextView label = new TextView(getContext());
        label.setText(title);
        label.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        label.setTextSize(11f);
        row.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        row.addView(field, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        parent.addView(row);
    }

    private void updateColorPickerButton(
            @NonNull Button button,
            int color,
            @NonNull String prefix
    ) {
        boolean transparent = Color.alpha(color) == 0;
        if (transparent) {
            button.setText(prefix + ": None");
            button.setTextColor(Color.WHITE);
        } else {
            button.setText(prefix + ": " + formatColor(color));
            double luminance = (0.2126 * Color.red(color))
                    + (0.7152 * Color.green(color))
                    + (0.0722 * Color.blue(color));
            button.setTextColor(luminance > 150d ? Color.BLACK : Color.WHITE);
        }

        GradientDrawable background = new GradientDrawable();
        // A cleared fill should read as "none" instead of showing opaque black.
        // Real colours are previewed at full RGB strength because control opacity is
        // edited independently by the Opacity slider.
        background.setColor(transparent
                ? LauncherDialogStyle.COLOR_CARD_BG
                : Color.rgb(Color.red(color), Color.green(color), Color.blue(color)));
        background.setCornerRadius(dp(8f));
        background.setStroke(Math.max(1, dp(1f)), LauncherDialogStyle.COLOR_CARD_STROKE);
        button.setBackground(background);
    }

    private static boolean hasControlImage(@NonNull TouchControlData data) {
        return data.imageUri != null && !data.imageUri.trim().isEmpty();
    }

    private void addPairedFieldRow(
            @NonNull LinearLayout parent,
            @NonNull String firstTitle,
            @NonNull EditText firstField,
            @NonNull String secondTitle,
            @NonNull EditText secondField
    ) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setWeightSum(2f);
        row.setPadding(0, dp(3f), 0, dp(1f));

        LinearLayout firstColumn = makeDialogFieldColumn(firstTitle, firstField);
        LinearLayout secondColumn = makeDialogFieldColumn(secondTitle, secondField);

        LinearLayout.LayoutParams firstParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        firstParams.setMarginEnd(dp(6f));
        row.addView(firstColumn, firstParams);

        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        secondParams.setMarginStart(dp(6f));
        row.addView(secondColumn, secondParams);
        parent.addView(row);
    }

    @NonNull
    private LinearLayout makeDialogFieldColumn(
            @NonNull String title,
            @NonNull View field
    ) {
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(LinearLayout.VERTICAL);

        TextView label = new TextView(getContext());
        label.setText(title);
        label.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        label.setTextSize(11f);
        column.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        column.addView(field, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return column;
    }

    @NonNull
    private SeekBar[] addPairedSliderRow(
            @NonNull LinearLayout parent,
            int firstMax,
            int firstValue,
            int secondMax,
            int secondValue
    ) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setWeightSum(2f);
        row.setPadding(0, 0, 0, dp(4f));

        SeekBar first = createDialogSlider(firstMax, firstValue);
        SeekBar second = createDialogSlider(secondMax, secondValue);

        LinearLayout.LayoutParams firstParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        firstParams.setMarginEnd(dp(4f));
        row.addView(first, firstParams);

        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        secondParams.setMarginStart(dp(4f));
        row.addView(second, secondParams);
        parent.addView(row);
        return new SeekBar[]{first, second};
    }

    private void addSpinnerRow(@NonNull LinearLayout parent, @NonNull String title, @NonNull Spinner spinner) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(3f), 0, dp(4f));

        TextView label = new TextView(getContext());
        label.setText(title);
        label.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        label.setTextSize(11f);
        row.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        row.addView(spinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        parent.addView(row);
    }

    @NonNull
    private TextView valueLabel(@NonNull Context context, @NonNull String text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        label.setTextSize(13f);
        label.setPadding(0, dp(4f), 0, dp(2f));
        return label;
    }

    @NonNull
    private SeekBar addSlider(@NonNull LinearLayout parent, int max, int value) {
        SeekBar slider = createDialogSlider(max, value);
        parent.addView(slider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return slider;
    }

    @NonNull
    private SeekBar createDialogSlider(int max, int value) {
        SeekBar slider = new SeekBar(getContext());
        slider.setMax(Math.max(1, max));
        setSliderProgress(slider, value);
        return slider;
    }

    private void addPreviewSliderListener(
            @NonNull SeekBar slider,
            @NonNull AlertDialog[] dialogRef,
            @NonNull Runnable onStartChange,
            @NonNull Runnable onUserChange
    ) {
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) onUserChange.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                onStartChange.run();
                setEditDialogPreviewAlpha(dialogRef[0], true);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                setEditDialogPreviewAlpha(dialogRef[0], false);
            }
        });
    }

    private void setTextFromSlider(@NonNull EditText field, @NonNull SeekBar slider) {
        field.setText(String.valueOf(slider.getProgress()));
    }

    private void setSliderProgress(@NonNull SeekBar slider, int value) {
        int progress = Math.max(0, Math.min(slider.getMax(), value));
        if (slider.getProgress() != progress) slider.setProgress(progress);
    }

    private void setEditDialogPreviewAlpha(@Nullable AlertDialog dialog, boolean previewing) {
        if (dialog == null || dialog.getWindow() == null) return;

        // The editor is now a non-modal side panel, so fading its decor while a
        // slider is touched produces a full-screen-looking flash. Keep the panel
        // stable while the selected control updates live behind it.
        dialog.getWindow().setDimAmount(0f);
        dialog.getWindow().getDecorView().setAlpha(1f);
    }

    private static boolean materiallyDifferent(float first, float second) {
        return Math.abs(first - second) > 0.01f;
    }

    private void preserveControlScreenAnchorAfterSizeChange(
            @NonNull TouchControlButtonView view,
            @NonNull TouchControlData data,
            float oldScreenX,
            float oldScreenY,
            float oldScreenWidth,
            float oldScreenHeight
    ) {
        int parentWidth = Math.max(1, getWidth());
        int parentHeight = Math.max(1, getHeight());
        LayoutMetrics metrics = layoutMetrics(parentWidth, parentHeight);
        float scale = renderGlobalButtonScaleMultiplier();
        int baseWidth = baseControlScreenWidth(metrics, data, parentWidth);
        int baseHeight = baseControlScreenHeight(metrics, data, parentHeight);
        int newScreenWidth = scaledControlScreenWidth(metrics, data, parentWidth);
        int newScreenHeight = scaledControlScreenHeight(metrics, data, parentHeight);

        float oldCenterX = oldScreenX + (oldScreenWidth / 2f);
        float oldCenterY = oldScreenY + (oldScreenHeight / 2f);
        float targetScreenX;
        float targetScreenY;

        if (oldCenterX <= parentWidth * 0.42f) {
            targetScreenX = oldScreenX;
        } else if (oldCenterX >= parentWidth * 0.58f) {
            targetScreenX = oldScreenX + oldScreenWidth - newScreenWidth;
        } else {
            targetScreenX = oldCenterX - (newScreenWidth / 2f);
        }

        if (oldCenterY <= parentHeight * 0.42f) {
            targetScreenY = oldScreenY;
        } else if (oldCenterY >= parentHeight * 0.58f) {
            targetScreenY = oldScreenY + oldScreenHeight - newScreenHeight;
        } else {
            targetScreenY = oldCenterY - (newScreenHeight / 2f);
        }

        targetScreenX = clamp(targetScreenX, 0f, Math.max(0f, parentWidth - newScreenWidth));
        targetScreenY = clamp(targetScreenY, 0f, Math.max(0f, parentHeight - newScreenHeight));
        float baseScreenX = unscaledControlScreenX(
                targetScreenX,
                baseWidth,
                newScreenWidth,
                parentWidth,
                scale,
                data.positionAnchorX
        );
        float baseScreenY = unscaledControlScreenY(
                targetScreenY,
                baseHeight,
                newScreenHeight,
                parentHeight,
                scale,
                data.positionAnchorY
        );
        data.positionAnchorX = metrics.horizontalAnchorForScreenPosition(
                baseScreenX,
                baseWidth
        );
        data.positionAnchorY = metrics.verticalAnchorForScreenPosition(
                baseScreenY,
                baseHeight
        );
        data.x = metrics.fromScreenX(data, baseScreenX, baseWidth);
        data.y = metrics.fromScreenY(data, baseScreenY, baseHeight);
        data.rawX = null;
        data.rawY = null;
    }

    private void applyControlAppearancePreview(
            @NonNull TouchControlButtonView view,
            @NonNull TouchControlData data
    ) {
        view.setText(data.label);
        view.setAlpha(clamp(data.opacity, 0f, 1f) * getProfileGlobalOpacity());
        view.refreshVisualState();
        view.invalidate();
    }

    private void applyControlPreview(@NonNull TouchControlButtonView view, @NonNull TouchControlData data) {
        LayoutMetrics metrics = layoutMetrics(getWidth(), getHeight());
        int parentWidth = Math.max(1, getWidth());
        int parentHeight = Math.max(1, getHeight());
        int baseWidth = baseControlScreenWidth(metrics, data, parentWidth);
        int baseHeight = baseControlScreenHeight(metrics, data, parentHeight);
        int width = scaledControlScreenWidth(metrics, data, parentWidth);
        int height = scaledControlScreenHeight(metrics, data, parentHeight);
        view.setResponsiveVisualScale(layoutData.usesResponsiveCanvas()
                ? metrics.sizeScale * renderGlobalButtonScaleMultiplier()
                : 0f);

        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null) {
            params.width = width;
            params.height = height;
            view.setLayoutParams(params);
        }

        float scale = renderGlobalButtonScaleMultiplier();
        float density = getResources().getDisplayMetrics().density;
        float fallbackX = metrics.toScreenX(data, baseWidth);
        float fallbackY = metrics.toScreenY(data, baseHeight);
        float baseX = data.rawX == null
                ? fallbackX
                : ExpressionResolver.resolve(
                        data.rawX,
                        fallbackX,
                        parentWidth,
                        parentHeight,
                        baseWidth,
                        baseHeight,
                        density,
                        expressionPreferredScale(),
                        metrics.formulaPixelScale,
                        metrics.formulaDpScale
                );
        float baseY = data.rawY == null
                ? fallbackY
                : ExpressionResolver.resolve(
                        data.rawY,
                        fallbackY,
                        parentWidth,
                        parentHeight,
                        baseWidth,
                        baseHeight,
                        density,
                        expressionPreferredScale(),
                        metrics.formulaPixelScale,
                        metrics.formulaDpScale
                );
        float scaledX = scaledControlScreenX(
                baseX, baseWidth, width, parentWidth, scale, data.positionAnchorX);
        float scaledY = scaledControlScreenY(
                baseY, baseHeight, height, parentHeight, scale, data.positionAnchorY);
        view.setX(clamp(scaledX, 0f, Math.max(0f, parentWidth - width)));
        view.setY(clamp(scaledY, 0f, Math.max(0f, parentHeight - height)));
        view.setText(data.label);
        view.setAlpha(clamp(data.opacity, 0f, 1f) * getProfileGlobalOpacity());
        view.refreshVisualState();
        view.requestLayout();
        view.invalidate();
    }

    @NonNull
    private TouchControlData createOffsetCopyFromLiveGeometry(
            @NonNull TouchControlButtonView sourceView,
            @NonNull TouchControlData sourceData
    ) {
        TouchControlData copy = sourceData.copy();
        copy.id = UUID.randomUUID().toString();
        copy.label = sourceData.label + " Copy";

        if (getWidth() <= 1 || getHeight() <= 1
                || sourceView.getWidth() <= 0 || sourceView.getHeight() <= 0) {
            // This path should be rare because the button editor can only open for
            // an attached view. Still remove formulas so the requested offset is not
            // silently ignored when the layout rebuilds.
            copy.rawX = null;
            copy.rawY = null;
            copy.positionAnchorX = null;
            copy.positionAnchorY = null;
            copy.x = sourceData.x + 24f;
            copy.y = sourceData.y + 24f;
            return copy;
        }

        float offset = Math.max(12f, dp(16f));
        float targetX = offsetCopyCoordinate(
                sourceView.getX(),
                sourceView.getWidth(),
                getWidth(),
                offset
        );
        float targetY = offsetCopyCoordinate(
                sourceView.getY(),
                sourceView.getHeight(),
                getHeight(),
                offset
        );
        captureControlGeometry(sourceView, copy, targetX, targetY);
        return copy;
    }

    private static float offsetCopyCoordinate(
            float sourcePosition,
            float controlSize,
            float parentSize,
            float offset
    ) {
        float maxPosition = Math.max(0f, parentSize - Math.max(1f, controlSize));
        float forward = sourcePosition + offset;
        if (forward <= maxPosition) {
            return Math.max(0f, forward);
        }
        return Math.max(0f, Math.min(maxPosition, sourcePosition - offset));
    }

    private void captureLiveControlGeometry(
            @NonNull TouchControlButtonView view,
            @NonNull TouchControlData data
    ) {
        captureControlGeometry(view, data, view.getX(), view.getY());
    }

    private void captureControlGeometry(
            @NonNull TouchControlButtonView view,
            @NonNull TouchControlData data,
            float screenX,
            float screenY
    ) {
        if (getWidth() <= 1 || getHeight() <= 1) return;

        LayoutMetrics metrics = layoutMetrics(getWidth(), getHeight());
        float scale = renderGlobalButtonScaleMultiplier();
        float scaledWidth = Math.max(1f, view.getWidth());
        float scaledHeight = Math.max(1f, view.getHeight());
        float baseScreenWidth = Math.max(1f, scaledWidth / Math.max(0.001f, scale));
        float baseScreenHeight = Math.max(1f, scaledHeight / Math.max(0.001f, scale));
        float clampedScreenX = clamp(
                screenX,
                0f,
                Math.max(0f, getWidth() - scaledWidth)
        );
        float clampedScreenY = clamp(
                screenY,
                0f,
                Math.max(0f, getHeight() - scaledHeight)
        );
        float baseScreenX = unscaledControlScreenX(
                clampedScreenX,
                baseScreenWidth,
                scaledWidth,
                getWidth(),
                scale,
                data.positionAnchorX
        );
        float baseScreenY = unscaledControlScreenY(
                clampedScreenY,
                baseScreenHeight,
                scaledHeight,
                getHeight(),
                scale,
                data.positionAnchorY
        );

        // Save the anchor from the actual visible destination before resolving
        // source units. This keeps copies, drag edits, and history restores stable
        // even when the game surface is resized between editor sessions.
        data.positionAnchorX = metrics.horizontalAnchorForScreenPosition(
                baseScreenX,
                baseScreenWidth
        );
        data.positionAnchorY = metrics.verticalAnchorForScreenPosition(
                baseScreenY,
                baseScreenHeight
        );

        // Resolve position before replacing width/height because the mapper needs
        // the source-side size to determine the stable left/center/right anchor.
        float capturedX = metrics.fromScreenX(data, baseScreenX, baseScreenWidth);
        float capturedY = metrics.fromScreenY(data, baseScreenY, baseScreenHeight);
        float capturedWidth = Math.max(24f, metrics.fromScreenWidth(baseScreenWidth));
        float capturedHeight = Math.max(24f, metrics.fromScreenHeight(baseScreenHeight));

        data.x = capturedX;
        data.y = capturedY;
        data.width = capturedWidth;
        data.height = capturedHeight;
        if (TouchControlActions.JOYSTICK.equals(data.action)) {
            float squareSize = Math.max(data.width, data.height);
            data.width = squareSize;
            data.height = squareSize;
        }
        data.rawX = null;
        data.rawY = null;
    }

    private void reopenEditDialogForControlId(@Nullable String controlId) {
        if (controlId == null || controlId.trim().isEmpty()) return;
        post(() -> {
            if (!editMode || activeEditDialog != null) return;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (!(child instanceof TouchControlButtonView)) continue;
                TouchControlButtonView button = (TouchControlButtonView) child;
                TouchControlData candidate = button.getData();
                if (!controlId.equals(candidate.id)) continue;
                onEditRequested(button, candidate);
                return;
            }
        });
    }

    private void syncActiveEditGeometryUi(@NonNull TouchControlData data) {
        ActiveEditGeometryUi ui = activeEditGeometryUi;
        if (ui == null || ui.data != data) return;

        syncingActiveEditGeometryUi = true;
        try {
            setEditFieldText(ui.x, Math.round(data.x));
            setEditFieldText(ui.y, Math.round(data.y));
            setEditFieldText(ui.width, Math.round(data.width));
            setEditFieldText(ui.height, Math.round(data.height));
            setSliderProgress(ui.xSlider, Math.round(data.x));
            setSliderProgress(ui.ySlider, Math.round(data.y));
            setSliderProgress(ui.widthSlider, Math.round(data.width));
            setSliderProgress(ui.heightSlider, Math.round(data.height));
            ui.lastGeometryValues[0] = data.x;
            ui.lastGeometryValues[1] = data.y;
            ui.lastGeometryValues[2] = data.width;
            ui.lastGeometryValues[3] = data.height;

            int percent = Math.round(clamp(data.sizePercent, 30f, 250f));
            ui.currentPercent[0] = percent;
            setSliderProgress(ui.sizeSlider, percent);
            ui.sizeLabel.setText("Button size: " + percent + "%");
        } finally {
            syncingActiveEditGeometryUi = false;
        }
    }

    private void setEditFieldText(@NonNull EditText field, int value) {
        String text = String.valueOf(value);
        if (field.getText() == null || !text.contentEquals(field.getText())) {
            field.setText(text);
            field.setSelection(field.length());
        }
    }

    private static final class ActiveEditGeometryUi {
        @NonNull final TouchControlData data;
        @NonNull final EditText x;
        @NonNull final EditText y;
        @NonNull final EditText width;
        @NonNull final EditText height;
        @NonNull final SeekBar xSlider;
        @NonNull final SeekBar ySlider;
        @NonNull final SeekBar widthSlider;
        @NonNull final SeekBar heightSlider;
        @NonNull final SeekBar sizeSlider;
        @NonNull final TextView sizeLabel;
        @NonNull final float[] currentPercent;
        @NonNull final float[] lastGeometryValues;

        ActiveEditGeometryUi(
                @NonNull TouchControlData data,
                @NonNull EditText x,
                @NonNull EditText y,
                @NonNull EditText width,
                @NonNull EditText height,
                @NonNull SeekBar xSlider,
                @NonNull SeekBar ySlider,
                @NonNull SeekBar widthSlider,
                @NonNull SeekBar heightSlider,
                @NonNull SeekBar sizeSlider,
                @NonNull TextView sizeLabel,
                @NonNull float[] currentPercent,
                @NonNull float[] lastGeometryValues
        ) {
            this.data = data;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.xSlider = xSlider;
            this.ySlider = ySlider;
            this.widthSlider = widthSlider;
            this.heightSlider = heightSlider;
            this.sizeSlider = sizeSlider;
            this.sizeLabel = sizeLabel;
            this.currentPercent = currentPercent;
            this.lastGeometryValues = lastGeometryValues;
        }
    }

    @NonNull
    private Button slotEditActionButton(
            @NonNull Context context,
            @NonNull String text,
            boolean primary
    ) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(11.5f);
        button.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(8f), 0, dp(8f), 0);

        GradientDrawable background = new GradientDrawable();
        background.setColor(primary
                ? LauncherDialogStyle.COLOR_CARD_BG_PRESSED
                : LauncherDialogStyle.COLOR_CARD_BG);
        background.setCornerRadius(dp(8f));
        background.setStroke(
                Math.max(1, dp(primary ? 1.5f : 1f)),
                LauncherDialogStyle.COLOR_CARD_STROKE
        );
        button.setBackground(background);
        button.setElevation(0f);
        button.setStateListAnimator(null);
        return button;
    }

    @NonNull
    private Button compactEditActionButton(@NonNull Context context, @NonNull String text) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(10.5f);
        button.setSingleLine(true);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(8f), 0, dp(8f), 0);

        // Text-only actions were difficult to distinguish from headings on small
        // screens. Give every action a real card/button surface while keeping the
        // compact footprint required by the side drawer.
        GradientDrawable background = new GradientDrawable();
        background.setColor(LauncherDialogStyle.COLOR_CARD_BG_PRESSED);
        background.setCornerRadius(dp(8f));
        background.setStroke(
                Math.max(1, dp(1f)),
                LauncherDialogStyle.COLOR_CARD_STROKE
        );
        button.setBackground(background);
        button.setElevation(0f);
        button.setStateListAnimator(null);
        updateCompactEditActionState(button, true);
        return button;
    }

    private void updateCompactEditActionState(
            @NonNull Button button,
            boolean enabled
    ) {
        button.setEnabled(enabled);
        // Keep disabled actions visible so users can tell that Redo exists and
        // understand when it becomes available. The reduced alpha still makes the
        // enabled state unambiguous without turning the text nearly black.
        button.setAlpha(enabled ? 1f : 0.48f);
        button.setTextColor(enabled
                ? LauncherDialogStyle.COLOR_TEXT_PRIMARY
                : LauncherDialogStyle.COLOR_TEXT_SECONDARY);
    }

    private void prepareControlEditSidePanel(
            @NonNull AlertDialog dialog,
            @NonNull TouchControlButtonView editingView
    ) {
        Window window = dialog.getWindow();
        if (window == null) return;

        window.setWindowAnimations(0);
        window.setBackgroundDrawable(makeDialogBackground());
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        window.setDimAmount(0f);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        DisplayMetrics display = getResources().getDisplayMetrics();
        View root = getRootView();
        int screenWidth = root != null && root.getWidth() > 1
                ? root.getWidth()
                : Math.max(1, display.widthPixels);
        int screenHeight = root != null && root.getHeight() > 1
                ? root.getHeight()
                : Math.max(1, display.heightPixels);
        boolean landscape = screenWidth > screenHeight;
        int horizontalMargin = dp(6f);
        int verticalMargin = landscape ? dp(8f) : dp(6f);

        // Match the practical Zalith-style drawer proportion: wide enough for paired
        // controls and readable mappings, but still shy of 40% so most of the editor
        // canvas and the selected button remain visible.
        int targetPanelWidth = Math.max(1, Math.round(screenWidth * 0.38f));

        int[] rootLocation = new int[2];
        int[] buttonLocation = new int[2];
        if (root != null) root.getLocationOnScreen(rootLocation);
        editingView.getLocationOnScreen(buttonLocation);

        int buttonLeft = buttonLocation[0] - rootLocation[0];
        int buttonRight = buttonLeft + Math.max(1, editingView.getWidth());
        int protectedGap = dp(28f); // selected button plus resize-handle clearance
        int availableLeft = Math.max(1, buttonLeft - horizontalMargin - protectedGap);
        int availableRight = Math.max(1, screenWidth - buttonRight - horizontalMargin - protectedGap);

        // Prefer a side that can hold the full responsive drawer. If both can, use
        // the opposite side of the selected control; otherwise use the roomier side.
        boolean controlOnLeftHalf = (buttonLeft + buttonRight) / 2f < screenWidth / 2f;
        boolean panelOnRight;
        if (controlOnLeftHalf && availableRight >= targetPanelWidth) {
            panelOnRight = true;
        } else if (!controlOnLeftHalf && availableLeft >= targetPanelWidth) {
            panelOnRight = false;
        } else {
            panelOnRight = availableRight >= availableLeft;
        }

        int sideAvailable = panelOnRight ? availableRight : availableLeft;
        int panelWidth = Math.max(1, Math.min(targetPanelWidth, sideAvailable));
        int panelHeight = Math.max(1, screenHeight - verticalMargin * 2);

        window.setGravity(Gravity.TOP | (panelOnRight ? Gravity.RIGHT : Gravity.LEFT));

        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.width = panelWidth;
        attributes.height = panelHeight;
        attributes.x = horizontalMargin;
        attributes.y = verticalMargin;
        attributes.dimAmount = 0f;
        attributes.windowAnimations = 0;
        window.setAttributes(attributes);
        window.setLayout(panelWidth, panelHeight);

        View decor = window.getDecorView();
        decor.animate().cancel();
        decor.setMinimumWidth(0);
        decor.setPadding(0, 0, 0, 0);
        decor.setAlpha(1f);
        decor.setTranslationX(panelOnRight ? panelWidth : -panelWidth);
        decor.setTag(Boolean.TRUE);

        View content = dialog.findViewById(android.R.id.content);
        if (content != null) {
            content.setMinimumWidth(0);
            content.setPadding(0, 0, 0, 0);
        }
    }

    private int controlEditBottomSystemInset(@Nullable View root) {
        Activity hostActivity = findActivity(getContext());
        if (hostActivity instanceof ControlsEditorActivity) return 0;

        View hostDecor = null;
        try {
            Window hostWindow = hostActivity == null ? null : hostActivity.getWindow();
            if (hostWindow != null) hostDecor = hostWindow.getDecorView();
        } catch (Throwable ignored) {
        }

        int bottomInset = 0;
        View[] insetSources = new View[]{root, hostDecor};
        for (View insetSource : insetSources) {
            if (insetSource == null) continue;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowInsets insets = insetSource.getRootWindowInsets();
                    if (insets != null) {
                        android.graphics.Insets navigation = insets.getInsetsIgnoringVisibility(
                                WindowInsets.Type.navigationBars()
                        );
                        bottomInset = Math.max(bottomInset, Math.max(0, navigation.bottom));
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    WindowInsets insets = insetSource.getRootWindowInsets();
                    if (insets != null) {
                        // In immersive mode the visible system-window inset may be zero,
                        // while the stable inset still contains the navigation-bar size.
                        bottomInset = Math.max(bottomInset, Math.max(
                                Math.max(0, insets.getStableInsetBottom()),
                                Math.max(0, insets.getSystemWindowInsetBottom())
                        ));
                    }
                }
            } catch (Throwable ignored) {
                // Try the other inset source. Some vendor child-window implementations
                // return incomplete insets even though the host activity has them.
            }
        }
        return bottomInset;
    }

    private void animatePreparedControlEditSidePanel(@NonNull AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        View decor = window.getDecorView();
        decor.animate().cancel();
        decor.animate()
                .translationX(0f)
                .setDuration(180L)
                .start();
    }

    @NonNull
    private GradientDrawable makeDialogBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(LauncherDialogStyle.COLOR_DIALOG_BG);
        drawable.setCornerRadius(dp(22f));
        drawable.setStroke(Math.max(1, dp(1f)), LauncherDialogStyle.COLOR_CARD_STROKE);
        return drawable;
    }



    private void showKeyboardKeyPickerDialog(
            @NonNull Context context,
            int slotIndex,
            @Nullable Spinner slotSpinner,
            @NonNull EditText hiddenKeySlots,
            @NonNull TextView boundKeys,
            @NonNull Spinner[] keySlotSpinners,
            @NonNull TouchInputBinding.Option[] keyOptions
    ) {
        if (slotSpinner == null) return;

        TouchKeyPickerDialog.showPicker(
                context,
                slotIndex,
                keyCode -> {
                    int selected = TouchInputBinding.selectedKeyOptionIndex(keyCode);
                    slotSpinner.setSelection(selected, false);
                    updateKeySlotSummary(hiddenKeySlots, boundKeys, keySlotSpinners, keyOptions);
                    return true;
                }
        );
    }


    @NonNull
    private int[] readKeySlotsFromSpinners(
            @NonNull Spinner[] spinners,
            @NonNull TouchInputBinding.Option[] options
    ) {
        int[] slots = new int[TouchControlData.MAX_ACTION_SLOTS];
        for (int slot = 0; slot < slots.length && slot < spinners.length; slot++) {
            Spinner spinner = spinners[slot];
            if (spinner == null || options.length == 0) {
                slots[slot] = 0;
                continue;
            }
            int index = Math.max(0, Math.min(spinner.getSelectedItemPosition(), options.length - 1));
            slots[slot] = options[index].value;
        }
        return slots;
    }

    private void updateKeySlotSummary(
            @NonNull EditText hiddenKeySlots,
            @NonNull TextView boundKeys,
            @NonNull Spinner[] spinners,
            @NonNull TouchInputBinding.Option[] options
    ) {
        int[] slots = readKeySlotsFromSpinners(spinners, options);
        hiddenKeySlots.setText(joinKeyCodes(slots));
        java.util.ArrayList<Integer> active = new java.util.ArrayList<>();
        StringBuilder detail = new StringBuilder();
        for (int slot = 0; slot < slots.length; slot++) {
            int value = slots[slot];
            if (value == 0) continue;
            active.add(value);
            if (detail.length() > 0) detail.append("  •  ");
            detail.append(slot).append(": ").append(TouchInputBinding.labelForKeyCode(value));
        }
        int[] activeCodes = new int[active.size()];
        for (int i = 0; i < active.size(); i++) activeCodes[i] = active.get(i);
        boundKeys.setText(detail.length() == 0
                ? "Bound buttons: No bindings"
                : "Bound buttons: " + TouchInputBinding.friendlyKeyCombo(activeCodes) + "\nSlots: " + detail);
    }

    private int[] parseKeyCodes(@NonNull String text, int fallback) {
        String[] parts = text.split("[,\\s]+");
        java.util.ArrayList<Integer> values = new java.util.ArrayList<>();
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) continue;
            try { values.add(Integer.parseInt(part.trim())); } catch (Throwable ignored) { }
        }
        if (values.isEmpty()) return new int[]{fallback};
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
        return result;
    }

    @NonNull
    private String joinKeyCodes(@NonNull int[] codes) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < codes.length; i++) {
            if (i > 0) builder.append(", ");
            builder.append(codes[i]);
        }
        return builder.toString();
    }

    @NonNull
    private String appendKeyCodeText(@NonNull String current, int value) {
        String trimmed = current.trim();
        if (trimmed.isEmpty()) return String.valueOf(value);
        for (String part : trimmed.split("[,\\s]+")) {
            try { if (Integer.parseInt(part.trim()) == value) return trimmed; } catch (Throwable ignored) { }
        }
        return trimmed + ", " + value;
    }

    @NonNull
    private String safeControlId(@NonNull String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed) ? UUID.randomUUID().toString() : trimmed;
    }

    private void requestControlImage(
            @NonNull TouchControlData control,
            @NonNull Runnable onPicked
    ) {
        Activity activity = findActivity(getContext());
        if (activity == null) {
            Toast.makeText(getContext(), "Image picker is unavailable on this display.", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        pendingImageControl = control;
        pendingImagePickedCallback = onPicked;
        pendingImagePickerOwner = new WeakReference<>(this);
        try {
            activity.startActivityForResult(intent, REQUEST_TOUCH_CONTROL_IMAGE);
        } catch (Throwable throwable) {
            pendingImageControl = null;
            pendingImagePickedCallback = null;
            pendingImagePickerOwner = null;
            Logging.e(TAG, "Unable to open touch-control image picker", throwable);
            Toast.makeText(getContext(), "Unable to open image picker.", Toast.LENGTH_SHORT).show();
        }
    }

    /** Called by activities that can host a touch-control editor. */
    public static boolean dispatchActivityResult(
            int requestCode,
            int resultCode,
            @Nullable Intent resultData
    ) {
        if (requestCode != REQUEST_TOUCH_CONTROL_IMAGE) return false;
        TouchControlsOverlay owner = pendingImagePickerOwner == null
                ? null
                : pendingImagePickerOwner.get();
        pendingImagePickerOwner = null;
        if (owner != null) owner.finishControlImagePick(resultCode, resultData);
        return true;
    }

    private void finishControlImagePick(int resultCode, @Nullable Intent resultData) {
        TouchControlData target = pendingImageControl;
        Runnable callback = pendingImagePickedCallback;
        pendingImageControl = null;
        pendingImagePickedCallback = null;
        if (resultCode != Activity.RESULT_OK || resultData == null || target == null) return;
        Uri uri = resultData.getData();
        if (uri == null) return;

        try {
            int takeFlags = resultData.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (takeFlags != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                getContext().getContentResolver().takePersistableUriPermission(uri, takeFlags);
            }
        } catch (Throwable throwable) {
            // Some document providers grant long-lived access without supporting
            // takePersistableUriPermission. Keep the URI and let normal read access work.
            Logging.i(TAG, "Image provider did not expose a persistable URI permission: " + throwable);
        }

        target.imageUri = uri.toString();
        if (callback != null) callback.run();
    }

    @Nullable
    private static Activity findActivity(@Nullable Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) return (Activity) current;
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current) break;
            current = base;
        }
        return current instanceof Activity ? (Activity) current : null;
    }

    @NonNull
    private static String formatColor(int color) {
        return String.format(java.util.Locale.US, "#%08X", color);
    }

    private static int parseColorValue(@NonNull String value, int fallback) {
        String text = value.trim();
        if (text.isEmpty()) return fallback;
        try {
            if (!text.startsWith("#")) text = "#" + text;
            if (text.length() == 7) {
                // #RRGGBB => force fully opaque border.
                return (int) (0xFF000000L | Long.parseLong(text.substring(1), 16));
            }
            if (text.length() == 9) {
                return (int) Long.parseLong(text.substring(1), 16);
            }
            return Color.parseColor(text);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @NonNull
    private static TextView labelView(@NonNull Context context, @NonNull String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        view.setTextSize(13f);
        view.setPadding(0, 12, 0, 0);
        return view;
    }

    @NonNull
    private ArrayAdapter<String> dialogSpinnerAdapter(
            @NonNull Context context,
            @NonNull String[] values
    ) {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                context,
                android.R.layout.simple_spinner_item,
                values
        ) {
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                styleDialogSpinnerText(view, false);
                return view;
            }

            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                styleDialogSpinnerText(view, true);
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private void styleDialogSpinnerText(@NonNull View view, boolean dropdown) {
        if (!(view instanceof TextView)) return;
        TextView text = (TextView) view;
        text.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        text.setTextSize(12f);
        text.setSingleLine(!dropdown);
        text.setPadding(dp(6f), dp(4f), dp(6f), dp(4f));
        if (dropdown) text.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);
    }

    @NonNull
    private static EditText textField(@NonNull Context context, @NonNull String hint, @NonNull String value, boolean number) {
        EditText field = new EditText(context);
        field.setHint(hint);
        field.setHintTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        field.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        field.setTextSize(13f);
        field.setSingleLine(true);
        if (number) {
            field.setInputType(InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_FLAG_SIGNED
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        } else {
            field.setInputType(InputType.TYPE_CLASS_TEXT);
        }

        // Every editor field is independent. IME_ACTION_NEXT implied that users had
        // to step through the entire long drawer even when they were finished changing
        // one value. Always expose Done and close the keyboard when it is pressed.
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        field.setImeActionLabel("Done", android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        field.setOnEditorActionListener((view, actionId, event) -> {
            boolean enterReleased = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_UP;
            if (actionId != android.view.inputmethod.EditorInfo.IME_ACTION_DONE && !enterReleased) {
                return false;
            }

            view.clearFocus();
            try {
                android.view.inputmethod.InputMethodManager inputMethodManager =
                        (android.view.inputmethod.InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (inputMethodManager != null) {
                    inputMethodManager.hideSoftInputFromWindow(view.getWindowToken(), 0);
                }
            } catch (Throwable ignored) {
            }
            return true;
        });

        field.setText(value);
        field.setSelection(field.length());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            field.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    LauncherDialogStyle.COLOR_CARD_STROKE
            ));
        }
        return field;
    }

    private static float parseFloat(@NonNull EditText field, float fallback) {
        try {
            return Float.parseFloat(field.getText() == null ? "" : field.getText().toString().trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }


    private void normalizeUnstablePixelLayoutBeforeSave() {
        if (!editMode || getWidth() <= 1 || getHeight() <= 1) return;
        if (layoutData.usesResponsiveCanvas()) return;
        if (!layoutData.usesPixelCoordinates()) return;
        if (!hasUnstablePixelCoordinates()) return;

        int parentWidth = Math.max(1, getWidth());
        int parentHeight = Math.max(1, getHeight());

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child instanceof TouchControlButtonView)) continue;

            TouchControlButtonView button = (TouchControlButtonView) child;
            TouchControlData data = button.getData();
            int width = Math.max(1, button.getWidth());
            int height = Math.max(1, button.getHeight());

            data.x = clamp(button.getX(), 0f, Math.max(0f, parentWidth - width));
            data.y = clamp(button.getY(), 0f, Math.max(0f, parentHeight - height));
            data.width = width;
            data.height = height;
            data.rawX = null;
            data.rawY = null;
        }

        layoutData.coordinateUnit = TouchControlsLayoutData.UNIT_PX;
        layoutData.sourceWidth = parentWidth;
        layoutData.sourceHeight = parentHeight;
        layoutData.version = Math.max(layoutData.version, 4);
    }

    private boolean hasUnstablePixelCoordinates() {
        float sourceWidth = layoutData.resolvedSourceWidth(getWidth());
        float sourceHeight = layoutData.resolvedSourceHeight(getHeight());

        for (TouchControlData control : layoutData.controls) {
            if (control.rawX != null || control.rawY != null) return true;
            float width = Math.max(1f, control.width);
            float height = Math.max(1f, control.height);
            if (control.x < 0f || control.y < 0f) return true;
            if (control.x + width > sourceWidth || control.y + height > sourceHeight) return true;
        }

        return false;
    }

    private static float maxCursorCoordinate(float size) {
        return Math.max(0f, size - 1f);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }


    private boolean dispatchKeySenderKeyboardTouch(@NonNull MotionEvent event) {
        if (!keySenderKeyboardVisible || keySenderKeyboardView == null || keySenderKeyboardView.getVisibility() != VISIBLE) {
            return false;
        }

        // While the key-sender keyboard is open, it owns the whole touch stream.
        // This prevents a keyboard tap from also becoming camera movement or a hotbar tap.
        try {
            super.dispatchTouchEvent(event);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to dispatch key sender keyboard touch", throwable);
        }
        return true;
    }

    private void showKeySenderKeyboard() {
        if (editMode) return;
        keySenderKeyboardVisible = true;
        attachKeySenderKeyboardView();
    }

    private void hideKeySenderKeyboard() {
        keySenderKeyboardVisible = false;
        View view = keySenderKeyboardView;
        keySenderKeyboardView = null;
        if (view != null && view.getParent() == this) {
            removeView(view);
        }
    }

    private void attachKeySenderKeyboardView() {
        if (!keySenderKeyboardVisible || editMode) return;

        if (keySenderKeyboardView != null && keySenderKeyboardView.getParent() == this) {
            keySenderKeyboardView.bringToFront();
            return;
        }

        View keyboard = createKeySenderKeyboardView();
        keySenderKeyboardView = keyboard;
        addView(keyboard, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        keyboard.bringToFront();
    }

    @NonNull
    private View createKeySenderKeyboardView() {
        return new TouchKeySenderKeyboardView(getContext(), new TouchKeySenderKeyboardView.Listener() {
            @Override
            public void onCloseRequested() {
                hideKeySenderKeyboard();
            }

            @Override
            public void onSendRequested(@NonNull List<Integer> keyCodes) {
                sendQueuedKeySenderInputs(keyCodes);
            }
        });
    }


    private void sendQueuedKeySenderInputs(@NonNull List<Integer> pendingKeys) {
        if (pendingKeys.isEmpty()) {
            Toast.makeText(getContext(), "Pick at least one key first.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            java.util.ArrayList<Integer> chordKeys = new java.util.ArrayList<>();
            for (int keyCode : pendingKeys) {
                if (isPlainGlfwKeyboardKey(keyCode)) {
                    chordKeys.add(keyCode);
                    continue;
                }

                flushKeySenderChord(chordKeys);
                sendKeySenderInput(keyCode);
            }
            flushKeySenderChord(chordKeys);
        } finally {
            hideKeySenderKeyboard();
        }
    }

    private void flushKeySenderChord(@NonNull java.util.ArrayList<Integer> chordKeys) {
        if (chordKeys.isEmpty()) return;
        sendKeyChord(chordKeys);
        chordKeys.clear();
    }

    private boolean isPlainGlfwKeyboardKey(int keyCode) {
        // GLFW keyboard keys are positive. DroidBridge special actions are stored
        // as negative/sentinel values and must keep their existing action handlers.
        return keyCode > 0;
    }

    private void sendKeyChord(@NonNull List<Integer> keyCodes) {
        java.util.ArrayList<Integer> pressedKeys = new java.util.ArrayList<>();
        try {
            CallbackBridge.setInputReady(true);

            for (int keyCode : keyCodes) {
                if (!isPlainGlfwKeyboardKey(keyCode)) continue;
                if (keyCode == 84 || keyCode == 47) {
                    TouchKeyboardHelper.markChatKeyPressed();
                }
                CallbackBridge.sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), true);
                CallbackBridge.setModifiers(keyCode, true);
                pressedKeys.add(keyCode);
            }

            for (int i = pressedKeys.size() - 1; i >= 0; i--) {
                int keyCode = pressedKeys.get(i);
                CallbackBridge.sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), false);
                CallbackBridge.setModifiers(keyCode, false);
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to send key keyboard chord", throwable);

            // Best effort release in case an exception happened after one or more
            // key-down events. This prevents sticky F3/Shift/Ctrl style input.
            for (int i = pressedKeys.size() - 1; i >= 0; i--) {
                try {
                    int keyCode = pressedKeys.get(i);
                    CallbackBridge.sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), false);
                    CallbackBridge.setModifiers(keyCode, false);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void sendKeySenderInput(int keyCode) {
        try {
            switch (keyCode) {
                case TouchControlData.SPECIAL_MOUSE_LEFT:
                    sendLeftMouse(true);
                    sendLeftMouse(false);
                    return;
                case TouchControlData.SPECIAL_MOUSE_RIGHT:
                    sendRightMouse(true);
                    sendRightMouse(false);
                    return;
                case TouchControlData.SPECIAL_MOUSE_MIDDLE:
                    sendMouseButton(2, true, "Unable to send middle mouse from key keyboard");
                    sendMouseButton(2, false, "Unable to send middle mouse from key keyboard");
                    return;
                case TouchControlData.SPECIAL_SCROLL_UP:
                    sendScrollFromKeySender(1d);
                    return;
                case TouchControlData.SPECIAL_SCROLL_DOWN:
                    sendScrollFromKeySender(-1d);
                    return;
                case TouchControlData.SPECIAL_KEYBOARD:
                    TouchKeyboardHelper.showKeyboard(this);
                    return;
                case TouchControlData.SPECIAL_MENU:
                    onMenuRequested();
                    return;
                case TouchControlData.SPECIAL_TOGGLE_CONTROLS:
                    toggleControlVisible();
                    return;
                case TouchControlData.SPECIAL_VIRTUAL_MOUSE:
                    toggleVirtualMouseFromKeySender();
                    return;
                case TouchControlData.SPECIAL_DUAL_SCREEN_SWAP:
                    DualScreenSwapActionBus.requestSwap();
                    return;
                case TouchControlData.SPECIAL_KEY_SENDER_KEYBOARD:
                    return;
                default:
                    if (keyCode > 0) sendKeyTap(keyCode);
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to send queued key keyboard input", throwable);
        }
    }

    private void sendScrollFromKeySender(double amount) {
        try {
            CallbackBridge.setInputReady(true);
            CallbackBridge.sendScroll(0d, amount);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to send scroll from key keyboard", throwable);
        }
    }

    private void toggleVirtualMouseFromKeySender() {
        boolean enabled = !isProfileVirtualMouseEnabled();
        setProfileVirtualMouseEnabled(enabled);
        Toast.makeText(getContext(), enabled ? "Virtual cursor shown" : "Virtual cursor hidden", Toast.LENGTH_SHORT).show();
        postInvalidateOnAnimation();
    }

    /**
     * Keeps DroidBridge's configured on-screen buttons available while routing all
     * other contacts to TouchController.
     *
     * The TouchController proxy must receive a real, internally consistent Android
     * MotionEvent stream. Do not translate contacts into individual add/remove calls
     * here: doing so bypasses MinecraftGLSurface's focus/input-mode setup and loses
     * Android's ACTION_DOWN/ACTION_POINTER_DOWN/ACTION_POINTER_UP conversion rules.
     *
     * Instead, each pointer is assigned once on pointer-down. We then rebuild a
     * public-SDK MotionEvent containing only the contacts owned by TouchController and
     * send it through the same MinecraftGLSurface path that worked before the launcher
     * controls were made visible. MotionEvent.split(int) cannot be used here because it
     * is a hidden Android framework API and is unavailable to normal application builds.
     */
    private boolean dispatchTouchControllerHybridEvent(@NonNull MotionEvent event) {
        int action = event.getActionMasked();
        int actionIndex = event.getActionIndex();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                clearRuntimeTouchRouting();
                routeTouchControllerPointerDown(event, actionIndex);
                dispatchFilteredTouchControllerEvent(event);
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                routeTouchControllerPointerDown(event, actionIndex);
                dispatchFilteredTouchControllerEvent(event);
                return true;

            case MotionEvent.ACTION_MOVE:
                dispatchActiveControlPointers(event, MotionEvent.ACTION_MOVE);
                dispatchActiveSwipeGesturePointers(event);
                dispatchFilteredTouchControllerEvent(event);
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                // The lifted proxy pointer must still be present in the ownership set
                // while the filtered POINTER_UP/UP event is built for TouchController.
                dispatchFilteredTouchControllerEvent(event);
                finishTouchControllerHybridPointer(event, actionIndex, false);
                return true;

            case MotionEvent.ACTION_UP:
                dispatchFilteredTouchControllerEvent(event);
                finishTouchControllerHybridPointer(event, actionIndex, true);
                return true;

            case MotionEvent.ACTION_CANCEL:
                dispatchCancelToControlPointers(event);
                cancelAllSwipeGesturePointers(event);
                dispatchFilteredTouchControllerEvent(event);
                touchControllerPointerTargets.clear();
                clearRuntimeTouchRouting();
                return true;

            default:
                dispatchFilteredTouchControllerEvent(event);
                return true;
        }
    }

    private void routeTouchControllerPointerDown(@NonNull MotionEvent event, int pointerIndex) {
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) return;

        int pointerId = event.getPointerId(pointerIndex);
        float x = event.getX(pointerIndex);
        float y = event.getY(pointerIndex);

        TouchControlButtonView control = findControlUnder(x, y);
        if (control != null) {
            dispatchSinglePointerToControl(event, pointerIndex, MotionEvent.ACTION_DOWN, control);
            if (updateMouseGrabState() && shouldUseSwipeGesture(control.getData())) {
                startSwipeGesturePointer(pointerId, control);
            } else {
                controlPointerTargets.put(pointerId, control);
            }
            return;
        }

        touchControllerPointerTargets.put(pointerId, true);
    }

    /**
     * Sends a valid MotionEvent containing only TouchController-owned contacts.
     *
     * Android's MotionEvent.split(int) performs this operation internally, but that
     * method is hidden from the public Android SDK and therefore cannot be referenced
     * by an application compiled with Javac. This implementation uses only public SDK
     * APIs and performs the required POINTER_DOWN/POINTER_UP action conversion itself.
     */
    private boolean dispatchFilteredTouchControllerEvent(@NonNull MotionEvent event) {
        MotionEvent filtered = null;
        try {
            filtered = createFilteredTouchControllerEvent(event);
            if (filtered == null) return false;
            return dispatchWholeTouchEventToPassthrough(filtered);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to dispatch filtered TouchController event", throwable);
            return false;
        } finally {
            if (filtered != null) filtered.recycle();
        }
    }

    @Nullable
    private MotionEvent createFilteredTouchControllerEvent(@NonNull MotionEvent source) {
        int sourcePointerCount = source.getPointerCount();
        if (sourcePointerCount <= 0) return null;

        int selectedCount = 0;
        int sourceActionIndex = source.getActionIndex();
        int filteredActionIndex = -1;

        for (int i = 0; i < sourcePointerCount; i++) {
            int pointerId = source.getPointerId(i);
            if (!touchControllerPointerTargets.get(pointerId, false)) continue;
            if (i == sourceActionIndex) filteredActionIndex = selectedCount;
            selectedCount++;
        }

        if (selectedCount == 0) return null;

        MotionEvent.PointerProperties[] properties =
                new MotionEvent.PointerProperties[selectedCount];
        MotionEvent.PointerCoords[] coordinates =
                new MotionEvent.PointerCoords[selectedCount];

        int filteredIndex = 0;
        for (int i = 0; i < sourcePointerCount; i++) {
            int pointerId = source.getPointerId(i);
            if (!touchControllerPointerTargets.get(pointerId, false)) continue;

            MotionEvent.PointerProperties pointerProperties =
                    new MotionEvent.PointerProperties();
            source.getPointerProperties(i, pointerProperties);
            properties[filteredIndex] = pointerProperties;

            MotionEvent.PointerCoords pointerCoords = new MotionEvent.PointerCoords();
            source.getPointerCoords(i, pointerCoords);
            coordinates[filteredIndex] = pointerCoords;
            filteredIndex++;
        }

        int filteredAction = getFilteredTouchControllerAction(
                source.getActionMasked(),
                selectedCount,
                filteredActionIndex
        );

        MotionEvent filtered = MotionEvent.obtain(
                source.getDownTime(),
                source.getEventTime(),
                filteredAction,
                selectedCount,
                properties,
                coordinates,
                source.getMetaState(),
                source.getButtonState(),
                source.getXPrecision(),
                source.getYPrecision(),
                source.getDeviceId(),
                source.getEdgeFlags(),
                source.getSource() != 0 ? source.getSource() : InputDevice.SOURCE_TOUCHSCREEN,
                source.getFlags()
        );

        return filtered;
    }

    private static int getFilteredTouchControllerAction(
            int sourceAction,
            int selectedCount,
            int filteredActionIndex
    ) {
        switch (sourceAction) {
            case MotionEvent.ACTION_DOWN:
                return MotionEvent.ACTION_DOWN;

            case MotionEvent.ACTION_UP:
                return MotionEvent.ACTION_UP;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (filteredActionIndex < 0) return MotionEvent.ACTION_MOVE;
                if (selectedCount == 1) return MotionEvent.ACTION_DOWN;
                return MotionEvent.ACTION_POINTER_DOWN
                        | (filteredActionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);

            case MotionEvent.ACTION_POINTER_UP:
                if (filteredActionIndex < 0) return MotionEvent.ACTION_MOVE;
                if (selectedCount == 1) return MotionEvent.ACTION_UP;
                return MotionEvent.ACTION_POINTER_UP
                        | (filteredActionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);

            case MotionEvent.ACTION_CANCEL:
                return MotionEvent.ACTION_CANCEL;

            case MotionEvent.ACTION_MOVE:
            default:
                return MotionEvent.ACTION_MOVE;
        }
    }

    private void finishTouchControllerHybridPointer(
            @NonNull MotionEvent event,
            int pointerIndex,
            boolean finalUp
    ) {
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) {
            if (finalUp) clearRuntimeTouchRouting();
            return;
        }

        int pointerId = event.getPointerId(pointerIndex);
        touchControllerPointerTargets.delete(pointerId);
        finishSwipeGesturePointer(event, pointerIndex, false);

        TouchControlButtonView control = controlPointerTargets.get(pointerId);
        if (control != null) {
            dispatchSinglePointerToControl(event, pointerIndex, MotionEvent.ACTION_UP, control);
            controlPointerTargets.remove(pointerId);
        }

        if (finalUp) clearRuntimeTouchRouting();
    }

    private boolean handleMenuTwoFingerScroll(@NonNull MotionEvent event) {
        int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            resetMenuTwoFingerScroll();
            return false;
        }

        if (menuTwoFingerScrollBlockingUntilAllUp) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                resetMenuTwoFingerScroll();
                clearRuntimeTouchRouting();
            }
            return true;
        }

        if (menuTwoFingerScrollActive) {
            if (action == MotionEvent.ACTION_MOVE) {
                updateMenuTwoFingerScroll(event);
                return true;
            }

            if (action == MotionEvent.ACTION_POINTER_UP) {
                updateMenuTwoFingerScroll(event);
                menuTwoFingerScrollActive = false;
                menuTwoFingerScrollBlockingUntilAllUp = true;
                return true;
            }

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                resetMenuTwoFingerScroll();
                clearRuntimeTouchRouting();
                return true;
            }

            return true;
        }

        if (action != MotionEvent.ACTION_POINTER_DOWN
                || event.getPointerCount() < 2
                || updateMouseGrabState()) {
            return false;
        }

        int actionIndex = event.getActionIndex();
        if (actionIndex < 0 || actionIndex >= event.getPointerCount()) return false;

        int firstIndex = -1;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (i != actionIndex) {
                firstIndex = i;
                break;
            }
        }
        if (firstIndex < 0) return false;

        // A gesture that begins on a launcher control belongs to that control, not
        // menu scrolling. Both contacts must be on empty game/menu space.
        if (findControlUnder(event.getX(firstIndex), event.getY(firstIndex)) != null
                || findControlUnder(event.getX(actionIndex), event.getY(actionIndex)) != null) {
            return false;
        }

        cancelSingleFingerMenuRouteForTwoFingerScroll(event);

        menuTwoFingerScrollPointerA = event.getPointerId(firstIndex);
        menuTwoFingerScrollPointerB = event.getPointerId(actionIndex);
        menuTwoFingerScrollLastY = (event.getY(firstIndex) + event.getY(actionIndex)) * 0.5f;
        menuTwoFingerScrollAccumulator = 0f;
        menuTwoFingerScrollActive = true;
        menuTwoFingerScrollBlockingUntilAllUp = false;
        return true;
    }

    private void cancelSingleFingerMenuRouteForTwoFingerScroll(@NonNull MotionEvent event) {
        if (passthroughPointerId != NO_POINTER_ID) {
            int pointerIndex = event.findPointerIndex(passthroughPointerId);
            if (pointerIndex >= 0) {
                dispatchSinglePointerToPassthrough(event, pointerIndex, MotionEvent.ACTION_CANCEL);
            }
        }

        // TouchController receives its own filtered stream. Clear that ownership so
        // the first finger cannot later turn into a click when the scroll gesture ends.
        if (touchControllerPointerTargets.size() > 0) {
            TouchControllerModCompat.clearPointers();
            touchControllerPointerTargets.clear();
        }

        cancelVirtualMousePointer();
        passthroughPointerId = NO_POINTER_ID;
        passthroughDownTime = 0L;
        passthroughDownX = 0f;
        passthroughDownY = 0f;
        passthroughMovedPastSlop = false;
    }

    private void updateMenuTwoFingerScroll(@NonNull MotionEvent event) {
        int indexA = event.findPointerIndex(menuTwoFingerScrollPointerA);
        int indexB = event.findPointerIndex(menuTwoFingerScrollPointerB);
        if (indexA < 0 || indexB < 0) return;

        float currentY = (event.getY(indexA) + event.getY(indexB)) * 0.5f;
        float deltaY = currentY - menuTwoFingerScrollLastY;
        menuTwoFingerScrollLastY = currentY;

        float pixelsPerStep = Math.max(1f,
                MENU_TWO_FINGER_SCROLL_DP_PER_STEP * getResources().getDisplayMetrics().density);
        menuTwoFingerScrollAccumulator += deltaY / pixelsPerStep;
        if (Math.abs(menuTwoFingerScrollAccumulator) < 1f) return;

        int steps = (int) menuTwoFingerScrollAccumulator;
        menuTwoFingerScrollAccumulator -= steps;
        try {
            CallbackBridge.setInputReady(true);
            // Dragging fingers down reveals older/upward content, matching a positive
            // mouse-wheel step; dragging up scrolls downward through menus/chat.
            CallbackBridge.sendScroll(0d, steps);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to send two-finger menu scroll", throwable);
        }
    }

    private void resetMenuTwoFingerScroll() {
        menuTwoFingerScrollActive = false;
        menuTwoFingerScrollBlockingUntilAllUp = false;
        menuTwoFingerScrollPointerA = NO_POINTER_ID;
        menuTwoFingerScrollPointerB = NO_POINTER_ID;
        menuTwoFingerScrollLastY = 0f;
        menuTwoFingerScrollAccumulator = 0f;
    }

    private static boolean isControllerModTouchCoexistenceActive() {
        return ControllerModCompat.isControllableActive() || ControlifySDL.isActive();
    }

    private boolean routePointerDown(@NonNull MotionEvent event, int pointerIndex) {
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) return false;

        int pointerId = event.getPointerId(pointerIndex);
        float x = event.getX(pointerIndex);
        float y = event.getY(pointerIndex);
        boolean grabbed = updateMouseGrabState();

        if (grabbed) {
            // Virtual cursor visibility must never change touch routing.
            // In a grabbed world, the real Minecraft hotbar still needs to win
            // so normal touch-only users can select slots whether the cursor is
            // shown or hidden.
            int hotbarSlot = hotbarSlotForTouch(x, y);
            if (hotbarSlot >= 0) {
                startHotbarPointer(pointerId, hotbarSlot);
                return true;
            }
        }

        /*
         * DroidBridge's own visible touch controls always get first refusal, even
         * while Controllable/Controlify owns the physical gamepad. Controller-mod
         * ownership must not make launcher touch buttons non-interactive. Only a
         * touch that did not land on a launcher control may be handed through to
         * the controller mod's menu cursor/input path below.
         */
        TouchControlButtonView control = findControlUnder(x, y);
        if (control != null) {
            dispatchSinglePointerToControl(event, pointerIndex, MotionEvent.ACTION_DOWN, control);
            boolean swipeOwned = grabbed && shouldUseSwipeGesture(control.getData());
            if (swipeOwned) {
                // Swipe routing exclusively owns explicitly swipeable buttons and
                // transfers the held action as the finger crosses W/A/S/D. Normal
                // buttons retain their original hold-until-release behavior.
                startSwipeGesturePointer(pointerId, control);
            } else {
                controlPointerTargets.put(pointerId, control);
            }
            if (grabbed && shouldUseMousePassThrough(control.getData())) {
                startMousePassThroughPointer(event, pointerIndex, pointerId);
            }
            return true;
        }

        // In centered portrait mode the black bars are outside Minecraft. Do not turn
        // touches there into invisible GUI clicks or camera movement. Visible controls
        // above still get first refusal because their hit-test ran before this guard.
        if (inputViewportTarget != null && !isPointInsideInputViewport(x, y)) {
            return false;
        }

        // Controllable and Controlify own empty menu space on a single display.
        // Cross-display dual-screen input is handled below as a trackpad so it cannot warp
        // the remote cursor on ACTION_DOWN.
        if (!grabbed
                && isControllerModTouchCoexistenceActive()
                && !isCrossDisplayPassthroughTarget()) {
            if (passthroughPointerId == NO_POINTER_ID && passthroughTarget != null) {
                passthroughPointerId = pointerId;
                passthroughDownTime = event.getEventTime();
                passthroughDownX = x;
                passthroughDownY = y;
                passthroughMovedPastSlop = false;
                dispatchSinglePointerToPassthrough(event, pointerIndex, MotionEvent.ACTION_DOWN);
                return true;
            }
            return false;
        }

        // In-game look/attack: track one empty-space pointer by ID and send
        // relative deltas. Do not switch this into absolute mouse mode just
        // because the virtual cursor is visible.
        if (grabbed) {
            if (cameraPointerId == NO_POINTER_ID) {
                startCameraPointer(event, pointerIndex, pointerId);
                return true;
            }
            return hasActiveTouchRoute();
        }

        // A cross-display control panel behaves like a laptop trackpad in Minecraft
        // GUIs. Touch-control buttons above already had first refusal; empty-space drags
        // now move the existing cursor relatively, while a quick tap clicks exactly where
        // that cursor is currently hovering instead of warping it to the finger position.
        if (isCrossDisplayPassthroughTarget()
                && ControlsPreferences.isVirtualMouseEnabled(getContext())) {
            // Cross-display behaves like a relative trackpad only when the user
            // explicitly enabled Virtual Mouse. With Virtual Mouse OFF, fall through
            // to the absolute passthrough path below so the cursor lands under the
            // finger immediately on the very first touch.
            if (virtualMousePointerId == NO_POINTER_ID) {
                startVirtualMousePointer(event, pointerIndex, pointerId, true);
                return true;
            }
            return hasActiveTouchRoute();
        }

        if (!grabbed
                && !isControllerModTouchCoexistenceActive()
                && ControlsPreferences.isVirtualMouseEnabled(getContext())) {
            if (virtualMousePointerId == NO_POINTER_ID) {
                startVirtualMousePointer(event, pointerIndex, pointerId);
                return true;
            }
            return hasActiveTouchRoute();
        }

        // Menus/inventory are not grabbed, so empty screen touches pass through
        // to Minecraft as an absolute GUI click. If the passthrough target was
        // not wired for any reason, return false so Android can continue hit
        // testing lower siblings instead of the overlay eating the touch.
        if (passthroughPointerId == NO_POINTER_ID && passthroughTarget != null) {
            passthroughPointerId = pointerId;
            passthroughDownTime = event.getEventTime();
            passthroughDownX = x;
            passthroughDownY = y;
            passthroughMovedPastSlop = false;
            dispatchSinglePointerToPassthrough(event, pointerIndex, MotionEvent.ACTION_DOWN);
            return true;
        }

        return false;
    }

    private void routePointerUp(@NonNull MotionEvent event, int pointerIndex, boolean finalUp) {
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) {
            if (finalUp) clearRuntimeTouchRouting();
            return;
        }

        int pointerId = event.getPointerId(pointerIndex);

        if (pointerId == cameraPointerId) {
            finishCameraPointer(event, pointerIndex, false);
        }

        if (pointerId == hotbarPointerId) {
            finishHotbarPointer(true);
        }

        if (pointerId == virtualMousePointerId) {
            finishVirtualMousePointer(event, pointerIndex, false);
        }

        finishSwipeGesturePointer(event, pointerIndex, false);
        finishMousePassThroughPointer(event, pointerIndex, false);

        if (pointerId == passthroughPointerId) {
            dispatchSinglePointerToPassthrough(event, pointerIndex, MotionEvent.ACTION_UP);
            passthroughPointerId = NO_POINTER_ID;
            passthroughDownTime = 0L;
            passthroughDownX = 0f;
            passthroughDownY = 0f;
            passthroughMovedPastSlop = false;
        }

        TouchControlButtonView control = controlPointerTargets.get(pointerId);
        if (control != null) {
            dispatchSinglePointerToControl(event, pointerIndex, MotionEvent.ACTION_UP, control);
            controlPointerTargets.remove(pointerId);
        }

        if (finalUp) {
            clearRuntimeTouchRouting();
        }
    }

    private void dispatchCancelToControlPointers(@NonNull MotionEvent event) {
        for (int i = 0; i < controlPointerTargets.size(); i++) {
            int pointerId = controlPointerTargets.keyAt(i);
            int pointerIndex = event.findPointerIndex(pointerId);
            TouchControlButtonView control = controlPointerTargets.valueAt(i);
            if (pointerIndex >= 0 && control != null) {
                dispatchSinglePointerToControl(event, pointerIndex, MotionEvent.ACTION_CANCEL, control);
            }
        }
    }

    private void dispatchActiveControlPointers(@NonNull MotionEvent event, int action) {
        for (int i = 0; i < controlPointerTargets.size(); i++) {
            int pointerId = controlPointerTargets.keyAt(i);
            int pointerIndex = event.findPointerIndex(pointerId);
            TouchControlButtonView control = controlPointerTargets.valueAt(i);
            if (pointerIndex >= 0 && control != null) {
                dispatchSinglePointerToControl(event, pointerIndex, action, control);
            }
        }
    }


    private boolean shouldUseSwipeGesture(@NonNull TouchControlData data) {
        if (!isSwipeGestureEnabled(data)) return false;
        return !TouchControlActions.JOYSTICK.equals(data.action);
    }

    private boolean isSwipeGestureEnabled(@NonNull TouchControlData data) {
        return data.swipeGesture;
    }

    private void setSwipeGestureEnabled(@NonNull TouchControlData data, boolean enabled) {
        data.swipeGesture = enabled;
        data.migrateSwipeGestureFromPreferences = false;
        // Remove the old global preference entry so another profile can never inherit it.
        swipeGesturePrefs().edit().remove(swipeGesturePreferenceKey(data)).apply();
    }

    @NonNull
    private SharedPreferences swipeGesturePrefs() {
        return getContext().getSharedPreferences(SWIPE_GESTURE_PREFS, Context.MODE_PRIVATE);
    }

    @NonNull
    private static String swipeGesturePreferenceKey(@NonNull TouchControlData data) {
        String id = data.id == null ? "" : data.id.trim();
        if (id.isEmpty() || "null".equalsIgnoreCase(id)) {
            id = data.label == null ? "button" : data.label.trim();
        }
        return "button." + id;
    }

    private void startSwipeGesturePointer(int pointerId, @NonNull TouchControlButtonView primaryControl) {
        swipeGesturePointers.put(pointerId, new SwipeGestureState(primaryControl));
    }

    private void dispatchActiveSwipeGesturePointers(@NonNull MotionEvent event) {
        if (swipeGesturePointers.size() <= 0) return;
        if (!updateMouseGrabState()) {
            cancelAllSwipeGesturePointers(event);
            return;
        }

        for (int i = 0; i < swipeGesturePointers.size(); i++) {
            int pointerId = swipeGesturePointers.keyAt(i);
            int pointerIndex = event.findPointerIndex(pointerId);
            if (pointerIndex < 0) continue;
            SwipeGestureState state = swipeGesturePointers.valueAt(i);
            dispatchSwipeGesturePointerMove(event, pointerIndex, state);
        }
    }

    private void finishSwipeGesturePointer(@NonNull MotionEvent event, int pointerIndex, boolean cancelled) {
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) return;
        int pointerId = event.getPointerId(pointerIndex);
        SwipeGestureState state = swipeGesturePointers.get(pointerId);
        if (state == null) return;

        if (!cancelled && updateMouseGrabState()) {
            dispatchSwipeGesturePointerMove(event, pointerIndex, state);
        }
        releaseActiveSwipeGestureControl(event, pointerIndex, state);
        swipeGesturePointers.remove(pointerId);
    }

    private void dispatchSwipeGesturePointerMove(
            @NonNull MotionEvent event,
            int pointerIndex,
            @NonNull SwipeGestureState state
    ) {
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) return;

        float x = event.getX(pointerIndex);
        float y = event.getY(pointerIndex);
        TouchControlButtonView target = findSwipeGestureTargetUnder(x, y, state.primaryControl);

        if (target == state.activeSwipeControl) {
            if (target != null) {
                dispatchSinglePointerToControl(event, pointerIndex, MotionEvent.ACTION_MOVE, target);
            }
            return;
        }

        releaseActiveSwipeGestureControl(event, pointerIndex, state);

        if (target != null) {
            state.activeSwipeControl = target;
            dispatchSinglePointerToControl(event, pointerIndex, MotionEvent.ACTION_DOWN, target);
        }
    }

    private void releaseActiveSwipeGestureControl(
            @NonNull MotionEvent event,
            int pointerIndex,
            @NonNull SwipeGestureState state
    ) {
        TouchControlButtonView active = state.activeSwipeControl;
        if (active == null) return;
        dispatchSinglePointerToControl(event, pointerIndex, MotionEvent.ACTION_UP, active);
        state.activeSwipeControl = null;
    }

    @Nullable
    private TouchControlButtonView findSwipeGestureTargetUnder(
            float x,
            float y,
            @NonNull TouchControlButtonView primaryControl
    ) {
        TouchControlButtonView nearest = null;
        float nearestDistanceSquared = Float.MAX_VALUE;
        float transferPadding = Math.max(
                cameraTouchSlop * 2f,
                24f * getResources().getDisplayMetrics().density
        );
        float maxDistanceSquared = transferPadding * transferPadding;

        for (int i = getChildCount() - 1; i >= 0; i--) {
            View child = getChildAt(i);
            if (!(child instanceof TouchControlButtonView)) continue;
            if (child.getVisibility() != VISIBLE) continue;

            TouchControlButtonView control = (TouchControlButtonView) child;
            if (!shouldUseSwipeGesture(control.getData())) continue;

            float left = child.getX();
            float top = child.getY();
            float right = left + child.getWidth();
            float bottom = top + child.getHeight();
            if (x >= left && x <= right && y >= top && y <= bottom) {
                return control;
            }

            float dx = x < left ? left - x : (x > right ? x - right : 0f);
            float dy = y < top ? top - y : (y > bottom ? y - bottom : 0f);
            float distanceSquared = (dx * dx) + (dy * dy);
            if (distanceSquared <= maxDistanceSquared
                    && distanceSquared < nearestDistanceSquared) {
                nearest = control;
                nearestDistanceSquared = distanceSquared;
            }
        }
        return nearest;
    }

    private void cancelAllSwipeGesturePointers(@NonNull MotionEvent event) {
        for (int i = 0; i < swipeGesturePointers.size(); i++) {
            int pointerId = swipeGesturePointers.keyAt(i);
            int pointerIndex = event.findPointerIndex(pointerId);
            SwipeGestureState state = swipeGesturePointers.valueAt(i);
            if (pointerIndex >= 0 && state != null) {
                releaseActiveSwipeGestureControl(event, pointerIndex, state);
            }
        }
        swipeGesturePointers.clear();
    }


    private boolean shouldUseMousePassThrough(@NonNull TouchControlData data) {
        if (!isMousePassThroughEnabled(data)) return false;
        return !TouchControlActions.JOYSTICK.equals(data.action);
    }

    private boolean isMousePassThroughEnabled(@NonNull TouchControlData data) {
        return data.mousePassThrough;
    }

    private void setMousePassThroughEnabled(@NonNull TouchControlData data, boolean enabled) {
        data.mousePassThrough = enabled;
        data.migrateMousePassThroughFromPreferences = false;
        // Remove the old global preference entry so another profile can never inherit it.
        mousePassThroughPrefs().edit().remove(mousePassThroughPreferenceKey(data)).apply();
    }

    @NonNull
    private SharedPreferences mousePassThroughPrefs() {
        return getContext().getSharedPreferences(MOUSE_PASS_THROUGH_PREFS, Context.MODE_PRIVATE);
    }

    @NonNull
    private static String mousePassThroughPreferenceKey(@NonNull TouchControlData data) {
        String id = data.id == null ? "" : data.id.trim();
        if (id.isEmpty() || "null".equalsIgnoreCase(id)) {
            id = data.label == null ? "button" : data.label.trim();
        }
        return "button." + id;
    }

    private void startMousePassThroughPointer(@NonNull MotionEvent event, int pointerIndex, int pointerId) {
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) return;
        float x = event.getX(pointerIndex);
        float y = event.getY(pointerIndex);
        refreshTouchCameraSensitivityMultiplier();
        mousePassThroughPointers.put(pointerId, new MousePassThroughState(x, y));
    }

    private void dispatchActiveMousePassThroughPointers(@NonNull MotionEvent event) {
        if (mousePassThroughPointers.size() <= 0) return;
        if (!updateMouseGrabState()) {
            cancelAllMousePassThroughPointers();
            return;
        }

        for (int i = 0; i < mousePassThroughPointers.size(); i++) {
            int pointerId = mousePassThroughPointers.keyAt(i);
            int pointerIndex = event.findPointerIndex(pointerId);
            if (pointerIndex < 0) continue;
            MousePassThroughState state = mousePassThroughPointers.valueAt(i);
            dispatchMousePassThroughPointerMove(event, pointerIndex, state);
        }
    }

    private void finishMousePassThroughPointer(@NonNull MotionEvent event, int pointerIndex, boolean cancelled) {
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) return;
        int pointerId = event.getPointerId(pointerIndex);
        MousePassThroughState state = mousePassThroughPointers.get(pointerId);
        if (state == null) return;

        if (!cancelled && updateMouseGrabState()) {
            dispatchMousePassThroughPointerMove(event, pointerIndex, state);
        }
        mousePassThroughPointers.remove(pointerId);
    }

    private void dispatchMousePassThroughPointerMove(
            @NonNull MotionEvent event,
            int pointerIndex,
            @NonNull MousePassThroughState state
    ) {
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) return;

        float x = event.getX(pointerIndex);
        float y = event.getY(pointerIndex);
        float dx = x - state.lastX;
        float dy = y - state.lastY;
        state.lastX = x;
        state.lastY = y;

        float totalDx = x - state.downX;
        float totalDy = y - state.downY;
        if (!state.movedPastSlop
                && ((totalDx * totalDx) + (totalDy * totalDy)) > (cameraTouchSlop * cameraTouchSlop)) {
            state.movedPastSlop = true;
        }

        if (!state.movedPastSlop || (dx == 0f && dy == 0f)) return;
        sendRelativeCameraDelta(dx, dy);
    }

    private void cancelAllMousePassThroughPointers() {
        mousePassThroughPointers.clear();
    }

    private void startVirtualMousePointer(@NonNull MotionEvent event, int pointerIndex, int pointerId) {
        startVirtualMousePointer(event, pointerIndex, pointerId, false);
    }

    private void startVirtualMousePointer(
            @NonNull MotionEvent event,
            int pointerIndex,
            int pointerId,
            boolean syncFromRemoteCursor
    ) {
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) return;

        virtualMousePointerId = pointerId;
        virtualMouseDownX = event.getX(pointerIndex);
        virtualMouseDownY = event.getY(pointerIndex);
        virtualMouseLastX = virtualMouseDownX;
        virtualMouseLastY = virtualMouseDownY;
        virtualMouseSpeedMultiplier =
                ControlsPreferences.getVirtualMouseSpeedPercent(getContext()) / 100f;
        virtualMouseMovedPastSlop = false;

        // Cross-display menu input must begin from Minecraft's CURRENT cursor. The
        // bottom TouchControlsOverlay has its own virtual-cursor cache, and that cache
        // may be centered/stale because the visible cursor is actually rendered on the
        // other display. Reusing the stale cache would itself cause a jump as soon as a
        // new finger touches the control screen.
        if (syncFromRemoteCursor) {
            syncVirtualMouseCursorFromBridge();
        } else {
            ensureVirtualMouseCursorInBounds();
        }
        sendVirtualMouseCursorPosition();
        postInvalidateOnAnimation();
    }

    private void syncVirtualMouseCursorFromBridge() {
        try {
            float maxX = maxCursorCoordinate(resolveVirtualCursorCoordinateWidth());
            float maxY = maxCursorCoordinate(resolveVirtualCursorCoordinateHeight());
            float bridgeX = CallbackBridge.mouseX;
            float bridgeY = CallbackBridge.mouseY;

            if (Float.isNaN(bridgeX) || Float.isInfinite(bridgeX)
                    || Float.isNaN(bridgeY) || Float.isInfinite(bridgeY)) {
                resetVirtualMouseCursorToCenter(false);
                return;
            }

            virtualCursorBridgeX = clamp(bridgeX, 0f, maxX);
            virtualCursorBridgeY = clamp(bridgeY, 0f, maxY);
            virtualCursorInitialized = true;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to sync dual-screen cursor from Minecraft", throwable);
            resetVirtualMouseCursorToCenter(false);
        }
    }

    private void dispatchActiveVirtualMousePointer(@NonNull MotionEvent event) {
        if (virtualMousePointerId == NO_POINTER_ID) return;
        if (updateMouseGrabState()) {
            cancelVirtualMousePointer();
            return;
        }

        int pointerIndex = event.findPointerIndex(virtualMousePointerId);
        if (pointerIndex < 0) return;

        float x = event.getX(pointerIndex);
        float y = event.getY(pointerIndex);
        float dx = x - virtualMouseLastX;
        float dy = y - virtualMouseLastY;
        virtualMouseLastX = x;
        virtualMouseLastY = y;

        float totalDx = x - virtualMouseDownX;
        float totalDy = y - virtualMouseDownY;
        if (!virtualMouseMovedPastSlop
                && ((totalDx * totalDx) + (totalDy * totalDy)) > (cameraTouchSlop * cameraTouchSlop)) {
            virtualMouseMovedPastSlop = true;
        }

        if (dx == 0f && dy == 0f) {
            postInvalidateOnAnimation();
            return;
        }
        if (isCrossDisplayPassthroughTarget()) {
            // Trackpad deltas are relative motion. Do not stretch X from the Thor's
            // 1240-wide lower panel to the 1920-wide upper panel; that conversion is
            // correct for ABSOLUTE GUI coordinates, but wrong for a trackpad and makes
            // horizontal movement feel offset. One control-panel pixel is one cursor
            // delta (subject only to the user's virtual-mouse speed multiplier).
            sendVirtualMouseDeltaUnscaled(
                    dx * virtualMouseSpeedMultiplier,
                    dy * virtualMouseSpeedMultiplier
            );
        } else {
            sendVirtualMouseDelta(
                    dx * virtualMouseSpeedMultiplier,
                    dy * virtualMouseSpeedMultiplier
            );
        }
    }

    private void finishVirtualMousePointer(@NonNull MotionEvent event, int pointerIndex, boolean cancelled) {
        if (pointerIndex >= 0 && pointerIndex < event.getPointerCount() && !cancelled) {
            dispatchActiveVirtualMousePointer(event);
        }

        if (!cancelled && !virtualMouseMovedPastSlop) {
            // Quick tap in fake-mouse mode clicks at the visible cursor, not at
            // the finger position. Use a normal Mouse Left button for click-hold.
            sendVirtualMouseCursorPosition();
            sendLeftMouse(true);
            sendLeftMouse(false);
        }

        cancelVirtualMousePointer();
        postInvalidateOnAnimation();
    }

    private void cancelVirtualMousePointer() {
        virtualMousePointerId = NO_POINTER_ID;
        virtualMouseDownX = virtualMouseDownY = virtualMouseLastX = virtualMouseLastY = 0f;
        virtualMouseMovedPastSlop = false;
    }

    private void startCameraPointer(@NonNull MotionEvent event, int pointerIndex, int pointerId) {
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) return;

        cameraPointerId = pointerId;
        refreshTouchCameraSensitivityMultiplier();
        // If the last GUI touch closed Back to Game/Done, center the native cursor
        // cache once before this fresh camera pointer produces relative deltas.
        // Do not use a time window that swallows movement; this simply fixes the
        // stale baseline and lets the first touch react immediately.
        CallbackBridge.centerCursorForPendingGrabbedTouch();
        cameraDownX = event.getX(pointerIndex);
        cameraDownY = event.getY(pointerIndex);
        cameraLastX = cameraDownX;
        cameraLastY = cameraDownY;
        cameraMovedPastSlop = false;
        cameraLongPressAttackActive = false;
        suppressCameraDeltaUntilUptimeMs = 0L;
        if (ControlsPreferences.isMinecraftTouchGesturesEnabled(getContext())) {
            scheduleCameraLongPressAttack();
        }
    }

    private void dispatchActiveCameraPointer(@NonNull MotionEvent event) {
        if (cameraPointerId == NO_POINTER_ID) return;

        int pointerIndex = event.findPointerIndex(cameraPointerId);
        if (pointerIndex < 0) return;

        float x = event.getX(pointerIndex);
        float y = event.getY(pointerIndex);
        float dx = x - cameraLastX;
        float dy = y - cameraLastY;
        cameraLastX = x;
        cameraLastY = y;

        float totalDx = x - cameraDownX;
        float totalDy = y - cameraDownY;
        if (!cameraMovedPastSlop && ((totalDx * totalDx) + (totalDy * totalDy)) > (cameraTouchSlop * cameraTouchSlop)) {
            cameraMovedPastSlop = true;
            cancelCameraLongPressAttack(false);
        }

        if (dx == 0f && dy == 0f) return;
        sendRelativeCameraDelta(dx, dy);
    }

    private void finishCameraPointer(@NonNull MotionEvent event, int pointerIndex, boolean cancelled) {
        if (pointerIndex >= 0 && pointerIndex < event.getPointerCount() && !cancelled) {
            dispatchActiveCameraPointer(event);
        }

        cancelCameraLongPressAttack(cancelled);

        if (!cancelled && cameraLongPressAttackActive) {
            sendLeftMouse(false);
        } else if (!cancelled
                && !cameraMovedPastSlop
                && ControlsPreferences.isMinecraftTouchGesturesEnabled(getContext())) {
            // Quick tap on the look area should use/place, not attack.
            // Long press remains left mouse for digging / punching.
            sendRightMouse(true);
            sendRightMouse(false);
        }

        cameraLongPressAttackActive = false;
        cameraPointerId = NO_POINTER_ID;
        cameraDownX = cameraDownY = cameraLastX = cameraLastY = 0f;
        cameraMovedPastSlop = false;
    }

    private void cancelCameraPointer(boolean sendRelease) {
        if (sendRelease && cameraLongPressAttackActive) {
            sendLeftMouse(false);
        }
        cancelCameraLongPressAttack(true);
        cameraLongPressAttackActive = false;
        cameraPointerId = NO_POINTER_ID;
        cameraMovedPastSlop = false;
    }

    private void scheduleCameraLongPressAttack() {
        cancelCameraLongPressAttack(false);
        cameraLongPressRunnable = () -> {
            if (cameraPointerId == NO_POINTER_ID || cameraMovedPastSlop || cameraLongPressAttackActive) return;
            cameraLongPressAttackActive = true;
            sendLeftMouse(true);
        };
        gestureHandler.postDelayed(cameraLongPressRunnable, ViewConfiguration.getLongPressTimeout());
    }

    private void cancelCameraLongPressAttack(boolean cancelActivePress) {
        if (cameraLongPressRunnable != null) {
            gestureHandler.removeCallbacks(cameraLongPressRunnable);
            cameraLongPressRunnable = null;
        }
        if (cancelActivePress && cameraLongPressAttackActive) {
            sendLeftMouse(false);
            cameraLongPressAttackActive = false;
        }
    }

    private void startHotbarPointer(int pointerId, int slot) {
        hotbarPointerId = pointerId;
        hotbarLastSlot = slot;
        hotbarDoubleTapConsumed = false;

        boolean doubleTapToOffhandEnabled =
                ControlsPreferences.isDoubleTapHotbarToOffhandEnabled(getContext());
        boolean doubleTap = doubleTapToOffhandEnabled && isHotbarDoubleTap(slot);
        sendKeyTap(49 + slot); // GLFW_KEY_1 through GLFW_KEY_9

        if (doubleTap) {
            hotbarDoubleTapConsumed = true;
            clearLastHotbarTap();
            sendKeyTap(LwjglGlfwKeycode.GLFW_KEY_F);
        }
    }

    private void dispatchActiveHotbarPointer(@NonNull MotionEvent event) {
        if (hotbarPointerId == NO_POINTER_ID) return;

        int pointerIndex = event.findPointerIndex(hotbarPointerId);
        if (pointerIndex < 0) return;

        int slot = hotbarSlotForTouch(event.getX(pointerIndex), event.getY(pointerIndex));
        if (slot < 0 || slot == hotbarLastSlot) return;

        hotbarLastSlot = slot;
        hotbarDoubleTapConsumed = false;
        sendKeyTap(49 + slot);
    }

    private boolean isHotbarDoubleTap(int slot) {
        if (slot < 0 || slot != lastHotbarTapSlot || lastHotbarTapTimeMs <= 0L) return false;
        long elapsed = SystemClock.uptimeMillis() - lastHotbarTapTimeMs;
        return elapsed >= 0L && elapsed <= ViewConfiguration.getDoubleTapTimeout();
    }

    private void finishHotbarPointer(boolean recordTap) {
        boolean doubleTapToOffhandEnabled =
                ControlsPreferences.isDoubleTapHotbarToOffhandEnabled(getContext());
        if (recordTap
                && doubleTapToOffhandEnabled
                && !hotbarDoubleTapConsumed
                && hotbarLastSlot >= 0) {
            lastHotbarTapSlot = hotbarLastSlot;
            lastHotbarTapTimeMs = SystemClock.uptimeMillis();
        } else if (!doubleTapToOffhandEnabled) {
            // Do not retain a hidden first tap while the preference is disabled.
            clearLastHotbarTap();
        }

        hotbarPointerId = NO_POINTER_ID;
        hotbarLastSlot = -1;
        hotbarDoubleTapConsumed = false;
    }

    private void clearLastHotbarTap() {
        lastHotbarTapSlot = -1;
        lastHotbarTapTimeMs = 0L;
    }

    private int hotbarSlotForTouch(float x, float y) {
        if (dualScreenBottomHudHotbarMode) {
            return dualScreenBottomHudSlotForTouch(x, y);
        }

        int overlayWidth = Math.max(1, getWidth());
        int overlayHeight = Math.max(1, getHeight());
        RectF viewport = resolveInputViewportBounds(overlayWidth, overlayHeight);
        if (inputViewportTarget != null && !viewport.contains(x, y)) {
            return -1;
        }

        float localX = x - viewport.left;
        float localY = y - viewport.top;
        return TouchHotbarHitbox.slotForTouch(
                getContext(),
                resolveMinecraftOptionsFile(),
                Math.max(1, Math.round(viewport.width())),
                Math.max(1, Math.round(viewport.height())),
                resolveGameBufferWidth(),
                resolveGameBufferHeight(),
                localX,
                localY
        );
    }

    private int dualScreenBottomHudSlotForTouch(float x, float y) {
        float width = Math.max(1f, getWidth());
        float height = Math.max(1f, getHeight());
        float d = Math.max(1f, getResources().getDisplayMetrics().density);
        float pad = Math.max(10f * d, Math.min(width, height) * 0.025f);
        float hotbarHeight = Math.max(52f * d, Math.min(94f * d, height * 0.110f));
        float hotbarWidth = Math.min(width - (pad * 2f), hotbarHeight * TouchHotbarHitbox.SLOT_COUNT * 1.055f);
        float hotbarLeft = (width - hotbarWidth) / 2f;
        float hotbarTop = height - pad - hotbarHeight;
        float slotGap = Math.max(2f * d, hotbarWidth * 0.0045f);
        float slotWidth = (hotbarWidth - (slotGap * (TouchHotbarHitbox.SLOT_COUNT + 1))) / TouchHotbarHitbox.SLOT_COUNT;
        if (slotWidth <= 1f) return -1;

        // Match DualScreenControlsView exactly. Only add a small vertical forgiveness
        // band; horizontally the slot boundaries stay strict so slot 1/2/3 do not bleed.
        float touchTop = hotbarTop - Math.max(4f * d, hotbarHeight * 0.12f);
        float touchBottom = hotbarTop + hotbarHeight + Math.max(4f * d, hotbarHeight * 0.12f);
        if (y < touchTop || y > touchBottom) return -1;

        for (int i = 0; i < TouchHotbarHitbox.SLOT_COUNT; i++) {
            float left = hotbarLeft + slotGap + (i * (slotWidth + slotGap));
            float right = left + slotWidth;
            if (x >= left && x < right) return i;
        }
        return -1;
    }

    @Nullable
    private File resolveMinecraftOptionsFile() {
        if (minecraftOptionsFile != null && minecraftOptionsFile.isFile()) {
            return minecraftOptionsFile;
        }

        long now = SystemClock.uptimeMillis();
        if (cachedMinecraftOptionsFile != null
                && cachedMinecraftOptionsFile.isFile()
                && now - lastMinecraftOptionsResolveAtMs < OPTIONS_FILE_RESOLVE_THROTTLE_MS) {
            return cachedMinecraftOptionsFile;
        }

        File resolved = findBestMinecraftOptionsFile();
        cachedMinecraftOptionsFile = resolved;
        lastMinecraftOptionsResolveAtMs = now;
        return resolved;
    }

    @Nullable
    private File findBestMinecraftOptionsFile() {
        try {
            String minecraftHome = PathManager.DIR_MINECRAFT_HOME;
            if (minecraftHome == null || minecraftHome.trim().isEmpty()) return null;

            File home = new File(minecraftHome);
            java.util.ArrayList<File> candidates = new java.util.ArrayList<>();

            // These cover the common cases:
            // - PathManager points directly at the active game directory
            // - PathManager points at an isolated instance root
            // - PathManager points at the global .minecraft directory
            addOptionsCandidate(candidates, home);
            addOptionsCandidate(candidates, new File(home, "game"));

            File parent = home.getParentFile();
            if (parent != null) {
                addOptionsCandidate(candidates, parent);
                addOptionsCandidate(candidates, new File(parent, "game"));
            }

            File grandparent = parent == null ? null : parent.getParentFile();
            if (grandparent != null) {
                addOptionsCandidate(candidates, grandparent);
                addOptionsCandidate(candidates, new File(grandparent, "game"));
            }

            // If the launcher is in isolated-instance mode, the active options.txt
            // usually lives under .minecraft/instances/<instance>/game/options.txt.
            scanInstancesDirectory(candidates, new File(home, "instances"));
            if (parent != null) scanInstancesDirectory(candidates, new File(parent, "instances"));
            if (grandparent != null) scanInstancesDirectory(candidates, new File(grandparent, "instances"));

            return newestReadableOptionsFile(candidates);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void addOptionsCandidate(@NonNull java.util.ArrayList<File> candidates, @Nullable File directory) {
        if (directory == null) return;
        candidates.add(new File(directory, "options.txt"));
    }

    private static void scanInstancesDirectory(@NonNull java.util.ArrayList<File> candidates, @Nullable File instancesDir) {
        if (instancesDir == null || !instancesDir.isDirectory()) return;

        File[] children;
        try {
            children = instancesDir.listFiles();
        } catch (Throwable ignored) {
            children = null;
        }
        if (children == null) return;

        for (File instance : children) {
            if (instance == null || !instance.isDirectory()) continue;
            addOptionsCandidate(candidates, new File(instance, "game"));
            addOptionsCandidate(candidates, instance);
            addOptionsCandidate(candidates, new File(instance, ".minecraft"));
        }
    }

    @Nullable
    private static File newestReadableOptionsFile(@NonNull java.util.ArrayList<File> candidates) {
        File best = null;
        long bestModified = Long.MIN_VALUE;

        for (File candidate : candidates) {
            if (candidate == null || !candidate.isFile()) continue;
            long modified;
            try {
                modified = candidate.lastModified();
            } catch (Throwable ignored) {
                modified = 0L;
            }

            if (best == null || modified > bestModified) {
                best = candidate;
                bestModified = modified;
            }
        }

        return best;
    }

    @NonNull
    private static String debugOptionsFileName(@Nullable File file) {
        if (file == null) return "none";

        try {
            String path = file.getAbsolutePath();
            String marker = File.separator + "instances" + File.separator;
            int index = path.lastIndexOf(marker);
            if (index >= 0) {
                return path.substring(index + 1);
            }

            File parent = file.getParentFile();
            return parent == null ? file.getName() : parent.getName() + File.separator + file.getName();
        } catch (Throwable ignored) {
            return file.getName();
        }
    }

    @NonNull
    private static String formatScale(float scale) {
        int rounded = Math.round(scale);
        if (Math.abs(scale - rounded) < 0.01f) return String.valueOf(rounded);
        return String.format(Locale.US, "%.2f", scale);
    }

    private float resolveGameBufferWidth() {
        try {
            if (CallbackBridge.windowWidth > 1) return CallbackBridge.windowWidth;
            if (CallbackBridge.physicalWidth > 1) return CallbackBridge.physicalWidth;
        } catch (Throwable ignored) {
        }
        return getWidth();
    }

    private float resolveGameBufferHeight() {
        try {
            if (CallbackBridge.windowHeight > 1) return CallbackBridge.windowHeight;
            if (CallbackBridge.physicalHeight > 1) return CallbackBridge.physicalHeight;
        } catch (Throwable ignored) {
        }
        return getHeight();
    }

    private void sendKeyTap(int keyCode) {
        try {
            CallbackBridge.setInputReady(true);
            if (keyCode == 84 || keyCode == 47) {
                TouchKeyboardHelper.markChatKeyPressed();
            }
            CallbackBridge.sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), true);
            CallbackBridge.setModifiers(keyCode, true);
            CallbackBridge.sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), false);
            CallbackBridge.setModifiers(keyCode, false);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to send key tap", throwable);
        }
    }


    private void sendVirtualMouseDelta(float dx, float dy) {
        try {
            ensureVirtualMouseCursorInBounds();
            virtualCursorBridgeX = clamp(
                    virtualCursorBridgeX + viewDeltaToBridgeX(dx),
                    0f,
                    maxCursorCoordinate(resolveVirtualCursorCoordinateWidth())
            );
            virtualCursorBridgeY = clamp(
                    virtualCursorBridgeY + viewDeltaToBridgeY(dy),
                    0f,
                    maxCursorCoordinate(resolveVirtualCursorCoordinateHeight())
            );
            sendVirtualMouseCursorPosition();
            postInvalidateOnAnimation();
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to move virtual mouse cursor", throwable);
        }
    }

    private void sendVirtualMouseDeltaUnscaled(float dx, float dy) {
        try {
            ensureVirtualMouseCursorInBounds();
            virtualCursorBridgeX = clamp(
                    virtualCursorBridgeX + dx,
                    0f,
                    maxCursorCoordinate(resolveVirtualCursorCoordinateWidth())
            );
            virtualCursorBridgeY = clamp(
                    virtualCursorBridgeY + dy,
                    0f,
                    maxCursorCoordinate(resolveVirtualCursorCoordinateHeight())
            );
            sendVirtualMouseCursorPosition();
            postInvalidateOnAnimation();
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to move dual-screen trackpad cursor", throwable);
        }
    }

    private void resetVirtualMouseCursorToCenter(boolean sendToMinecraft) {
        try {
            float maxX = maxCursorCoordinate(resolveVirtualCursorCoordinateWidth());
            float maxY = maxCursorCoordinate(resolveVirtualCursorCoordinateHeight());
            virtualCursorBridgeX = maxX / 2f;
            virtualCursorBridgeY = maxY / 2f;
            virtualCursorInitialized = true;
            if (sendToMinecraft) sendVirtualMouseCursorPosition();
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to reset virtual mouse cursor", throwable);
        }
    }

    private void ensureVirtualMouseCursorInBounds() {
        try {
            float maxX = maxCursorCoordinate(resolveVirtualCursorCoordinateWidth());
            float maxY = maxCursorCoordinate(resolveVirtualCursorCoordinateHeight());

            if (!virtualCursorInitialized
                    || Float.isNaN(virtualCursorBridgeX)
                    || Float.isInfinite(virtualCursorBridgeX)
                    || Float.isNaN(virtualCursorBridgeY)
                    || Float.isInfinite(virtualCursorBridgeY)) {
                resetVirtualMouseCursorToCenter(false);
                return;
            }

            virtualCursorBridgeX = clamp(virtualCursorBridgeX, 0f, maxX);
            virtualCursorBridgeY = clamp(virtualCursorBridgeY, 0f, maxY);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to prepare virtual mouse cursor", throwable);
        }
    }

    private void sendVirtualMouseCursorPosition() {
        try {
            ensureVirtualMouseCursorInBounds();
            CallbackBridge.setInputReady(true);
            CallbackBridge.mouseX = virtualCursorBridgeX;
            CallbackBridge.mouseY = virtualCursorBridgeY;
            CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to send virtual mouse cursor", throwable);
        }
    }

    @NonNull
    private GameResolutionSettings.DisplayBounds currentGameDisplayBounds() {
        int width = Math.max(1, getWidth());
        int height = Math.max(1, getHeight());
        if (inputViewportTarget != null) {
            RectF viewport = resolveInputViewportBounds(width, height);
            return new GameResolutionSettings.DisplayBounds(
                    Math.round(viewport.left),
                    Math.round(viewport.top),
                    Math.max(1, Math.round(viewport.width())),
                    Math.max(1, Math.round(viewport.height()))
            );
        }
        if (DroidBridgeSDL3Bootstrap.isRequested()) {
            // SDL3 Android window/mouse coordinates are physical game-root pixels.
            // Do not reuse a launcher resolution-profile box for virtual cursor
            // drawing or delta conversion; that creates the same visual/input split
            // as the old GameCursorOverlay path when the render buffer is scaled.
            return new GameResolutionSettings.DisplayBounds(0, 0, width, height);
        }
        return GameResolutionSettings.resolveDisplayBounds(getContext(), width, height);
    }

    private float bridgeCursorToViewX(float bridgeX) {
        float maxBridge = maxCursorCoordinate(resolveVirtualCursorCoordinateWidth());
        GameResolutionSettings.DisplayBounds bounds = currentGameDisplayBounds();
        float maxView = Math.max(0f, bounds.width - 1f);
        if (maxBridge <= 0f || maxView <= 0f) return bounds.left;
        return bounds.left + clamp(bridgeX, 0f, maxBridge) * maxView / maxBridge;
    }

    private float bridgeCursorToViewY(float bridgeY) {
        float maxBridge = maxCursorCoordinate(resolveVirtualCursorCoordinateHeight());
        GameResolutionSettings.DisplayBounds bounds = currentGameDisplayBounds();
        float maxView = Math.max(0f, bounds.height - 1f);
        if (maxBridge <= 0f || maxView <= 0f) return bounds.top;
        return bounds.top + clamp(bridgeY, 0f, maxBridge) * maxView / maxBridge;
    }

    private float viewDeltaToBridgeX(float viewDx) {
        float maxBridge = maxCursorCoordinate(resolveVirtualCursorCoordinateWidth());
        GameResolutionSettings.DisplayBounds bounds = currentGameDisplayBounds();
        float maxView = Math.max(0f, bounds.width - 1f);
        if (maxBridge <= 0f || maxView <= 0f) return viewDx;
        return viewDx * maxBridge / maxView;
    }

    private float viewDeltaToBridgeY(float viewDy) {
        float maxBridge = maxCursorCoordinate(resolveVirtualCursorCoordinateHeight());
        GameResolutionSettings.DisplayBounds bounds = currentGameDisplayBounds();
        float maxView = Math.max(0f, bounds.height - 1f);
        if (maxBridge <= 0f || maxView <= 0f) return viewDy;
        return viewDy * maxBridge / maxView;
    }

    private float resolveVirtualCursorCoordinateWidth() {
        if (DroidBridgeSDL3Bootstrap.isInputReady()) {
            return Math.max(1f,
                    DroidBridgeSDL3Bootstrap.getSdlCursorCoordinateWidth());
        }
        if (CallbackBridge.windowWidth > 1) return CallbackBridge.windowWidth;
        if (CallbackBridge.physicalWidth > 1) return CallbackBridge.physicalWidth;
        return Math.max(1f, getWidth());
    }

    private float resolveVirtualCursorCoordinateHeight() {
        if (DroidBridgeSDL3Bootstrap.isInputReady()) {
            return Math.max(1f,
                    DroidBridgeSDL3Bootstrap.getSdlCursorCoordinateHeight());
        }
        if (CallbackBridge.windowHeight > 1) return CallbackBridge.windowHeight;
        if (CallbackBridge.physicalHeight > 1) return CallbackBridge.physicalHeight;
        return Math.max(1f, getHeight());
    }

    private void sendAbsoluteCursor(float x, float y) {
        try {
            CallbackBridge.setInputReady(true);
            CallbackBridge.mouseX = clamp(
                    x, 0f, maxCursorCoordinate(resolveVirtualCursorCoordinateWidth()));
            CallbackBridge.mouseY = clamp(
                    y, 0f, maxCursorCoordinate(resolveVirtualCursorCoordinateHeight()));
            CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to send virtual mouse cursor", throwable);
        }
    }

    private void refreshTouchCameraSensitivityMultiplier() {
        try {
            float multiplier = GamepadMappingStore.get(getContext())
                    .getGameCameraSensitivityMultiplier();
            touchCameraSensitivityMultiplier = !Float.isNaN(multiplier)
                    && !Float.isInfinite(multiplier)
                    && multiplier > 0f
                    ? multiplier
                    : 1f;
        } catch (Throwable ignored) {
            touchCameraSensitivityMultiplier = 1f;
        }
    }

    private void sendRelativeCameraDelta(float dx, float dy) {
        try {
            if (shouldSuppressCameraDeltaAfterRegrab()) {
                return;
            }
            float multiplier = touchCameraSensitivityMultiplier;
            if (Float.isNaN(multiplier) || Float.isInfinite(multiplier) || multiplier <= 0f) {
                multiplier = 1f;
            }
            CallbackBridge.setInputReady(true);
            CallbackBridge.mouseX += dx * multiplier;
            CallbackBridge.mouseY += dy * multiplier;
            CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to send camera touch delta", throwable);
        }
    }

    private boolean shouldSuppressCameraDeltaAfterRegrab() {
        long until = suppressCameraDeltaUntilUptimeMs;
        if (until <= 0L) return false;
        if (SystemClock.uptimeMillis() < until) return true;
        suppressCameraDeltaUntilUptimeMs = 0L;
        return false;
    }

    private void sendLeftMouse(boolean down) {
        sendMouseButton(MOUSE_BUTTON_LEFT, down, "Unable to send touch attack");
    }

    private void sendRightMouse(boolean down) {
        sendMouseButton(MOUSE_BUTTON_RIGHT, down, "Unable to send touch use/place");
    }

    private void sendMouseButton(int button, boolean down, @NonNull String errorMessage) {
        try {
            CallbackBridge.setInputReady(true);
            CallbackBridge.sendMouseButton(button, down);
        } catch (Throwable throwable) {
            Logging.e(TAG, errorMessage, throwable);
        }
    }

    private static boolean isMouseGrabbed() {
        try {
            return CallbackBridge.isGrabbing();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private void applyControlsVisualState() {
        applyControlsVisualStateForGrabState(isMouseGrabbed());
    }

    private void applyControlsVisualStateForGrabState(boolean grabbed) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof TouchControlButtonView) {
                TouchControlButtonView button = (TouchControlButtonView) child;
                button.refreshVisualState();
                child.setVisibility(shouldShowControlButton(button.getData(), grabbed) ? VISIBLE : INVISIBLE);
            }
        }
        postInvalidateOnAnimation();
    }

    private boolean shouldCreateControlButton(@NonNull TouchControlData data) {
        return data.visibleInGame
                || data.visibleInMenu
                || data.visibleWhenControlsHidden
                || TouchControlData.shouldStayVisibleWhenControlsHiddenByDefault(data.action);
    }

    private boolean shouldShowControlButton(@NonNull TouchControlData data) {
        return shouldShowControlButton(data, isMouseGrabbed());
    }

    private boolean shouldShowControlButton(@NonNull TouchControlData data, boolean grabbed) {
        if (editMode) return true;
        if (editorPreviewSuppressedControlIds.contains(data.id)) return false;
        // Use the same hidden-state rules on the bottom screen as single-screen mode.
        // In particular, the GUI/show-hide control may stay visible when its touch-layout
        // setting says so. Controller auto-hide is the stronger override and hides all
        // touch controls while a controller is attached.
        if (controllerAutoHidden) return false;

        if (!isControlAllowedByMinecraftState(data, grabbed)) return false;

        String parentId = normalizedDrawerParentId(data.drawerParentId);
        if (parentId != null) {
            TouchControlData parent = findDrawerById(parentId);
            // Orphaned references degrade safely to a normal control. A real drawer owns
            // visibility: its children cannot leak into a state where the toggle itself
            // is unavailable, and they remain hidden until that drawer is expanded.
            if (parent != null) {
                if (!expandedDrawerIds.contains(parentId)) return false;
                if (!isControlAllowedByMinecraftState(parent, grabbed)) return false;
            }
        }

        return true;
    }

    private boolean isControlAllowedByMinecraftState(@NonNull TouchControlData data, boolean grabbed) {
        boolean allowedInCurrentMinecraftState = grabbed ? data.visibleInGame : data.visibleInMenu;
        if (!allowedInCurrentMinecraftState) return false;
        if (controlsVisible) return true;
        return data.visibleWhenControlsHidden
                || TouchControlData.shouldStayVisibleWhenControlsHiddenByDefault(data.action);
    }

    private void resetDrawerRuntimeState() {
        expandedDrawerIds.clear();
        initializedDrawerIds.clear();
    }

    private void syncDrawerRuntimeStateWithLayout() {
        HashSet<String> validDrawerIds = new HashSet<>();
        for (TouchControlData control : layoutData.controls) {
            if (!TouchControlActions.DRAWER.equals(control.action)) continue;
            String drawerId = control.id == null ? "" : control.id.trim();
            if (drawerId.isEmpty()) continue;
            validDrawerIds.add(drawerId);
            if (initializedDrawerIds.add(drawerId) && control.drawerOpenByDefault) {
                expandedDrawerIds.add(drawerId);
            }
        }
        expandedDrawerIds.retainAll(validDrawerIds);
        initializedDrawerIds.retainAll(validDrawerIds);
    }

    private void normalizeDrawerMembership() {
        HashSet<String> validDrawerIds = new HashSet<>();
        for (TouchControlData control : layoutData.controls) {
            if (TouchControlActions.DRAWER.equals(control.action)
                    && control.id != null
                    && !control.id.trim().isEmpty()) {
                validDrawerIds.add(control.id.trim());
                control.drawerParentId = null;
                control.drawerOrientation = TouchControlData.normalizeDrawerOrientation(control.drawerOrientation);
            }
        }
        for (TouchControlData control : layoutData.controls) {
            if (TouchControlActions.DRAWER.equals(control.action)) continue;
            String parentId = normalizedDrawerParentId(control.drawerParentId);
            control.drawerParentId = parentId != null && validDrawerIds.contains(parentId)
                    ? parentId
                    : null;
        }
    }

    @Nullable
    private static String normalizedDrawerParentId(@Nullable String drawerParentId) {
        if (drawerParentId == null) return null;
        String value = drawerParentId.trim();
        return value.isEmpty() ? null : value;
    }

    @Nullable
    private TouchControlData findDrawerById(@NonNull String drawerId) {
        for (TouchControlData control : layoutData.controls) {
            if (TouchControlActions.DRAWER.equals(control.action) && drawerId.equals(control.id)) {
                return control;
            }
        }
        return null;
    }

    private void releaseDrawerChildInput(@NonNull String drawerId) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child instanceof TouchControlButtonView)) continue;
            TouchControlButtonView button = (TouchControlButtonView) child;
            if (drawerId.equals(normalizedDrawerParentId(button.getData().drawerParentId))) {
                button.releaseInputState();
            }
        }
    }

    @NonNull
    private HashSet<String> drawerMemberIdsFor(@Nullable String drawerId) {
        HashSet<String> ids = new HashSet<>();
        String normalizedId = normalizedDrawerParentId(drawerId);
        if (normalizedId == null) return ids;
        for (TouchControlData control : layoutData.controls) {
            if (normalizedId.equals(normalizedDrawerParentId(control.drawerParentId))
                    && control.id != null
                    && !control.id.trim().isEmpty()) {
                ids.add(control.id.trim());
            }
        }
        return ids;
    }

    private static void updateDrawerMembersButtonText(
            @NonNull Button button,
            int selectedCount
    ) {
        button.setText(selectedCount == 1
                ? "Select drawer buttons (1 selected)"
                : "Select drawer buttons (" + selectedCount + " selected)");
    }

    private void showDrawerMembersDialog(
            @NonNull Context context,
            @NonNull TouchControlData drawer,
            @NonNull HashSet<String> selectedIds,
            @NonNull Button summaryButton
    ) {
        ArrayList<TouchControlData> candidates = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        for (TouchControlData control : layoutData.controls) {
            if (control == drawer || TouchControlActions.DRAWER.equals(control.action)) continue;
            String controlId = control.id == null ? "" : control.id.trim();
            if (controlId.isEmpty()) continue;
            candidates.add(control);
            labels.add(displayLabelForDialog(control.label) + "  •  " + controlId);
        }

        if (candidates.isEmpty()) {
            Toast.makeText(context, "Add some buttons before assigning drawer members.", Toast.LENGTH_SHORT).show();
            return;
        }

        HashSet<String> working = new HashSet<>(selectedIds);
        boolean[] checked = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            checked[i] = working.contains(candidates.get(i).id);
        }

        new MaterialAlertDialogBuilder(context)
                .setTitle("Drawer buttons")
                .setMultiChoiceItems(
                        labels.toArray(new String[0]),
                        checked,
                        (dialog, which, isChecked) -> {
                            String controlId = candidates.get(which).id;
                            if (isChecked) working.add(controlId);
                            else working.remove(controlId);
                        }
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Done", (dialog, which) -> {
                    selectedIds.clear();
                    selectedIds.addAll(working);
                    updateDrawerMembersButtonText(summaryButton, selectedIds.size());
                })
                .show();
    }

    private void applyDrawerMembershipAfterEdit(
            @Nullable String previousDrawerId,
            @NonNull TouchControlData editedControl,
            @NonNull HashSet<String> selectedIds
    ) {
        String oldId = normalizedDrawerParentId(previousDrawerId);
        String newId = normalizedDrawerParentId(editedControl.id);
        boolean remainsDrawer = TouchControlActions.DRAWER.equals(editedControl.action)
                && newId != null;

        for (TouchControlData control : layoutData.controls) {
            if (control == editedControl) continue;
            String parentId = normalizedDrawerParentId(control.drawerParentId);
            if ((oldId != null && oldId.equals(parentId))
                    || (newId != null && newId.equals(parentId))) {
                control.drawerParentId = null;
            }
        }

        if (!remainsDrawer) return;
        editedControl.drawerParentId = null;
        for (TouchControlData control : layoutData.controls) {
            if (control == editedControl || TouchControlActions.DRAWER.equals(control.action)) continue;
            String controlId = control.id == null ? "" : control.id.trim();
            if (!controlId.isEmpty() && selectedIds.contains(controlId)) {
                control.drawerParentId = newId;
            }
        }
    }

    private boolean hasGameCursorOverlayInViewTree() {
        try {
            View root = getRootView();
            if (containsGameCursorOverlay(root)) return true;

            ViewParent parent = getParent();
            return parent instanceof View && containsGameCursorOverlay((View) parent);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean containsGameCursorOverlay(@Nullable View view) {
        if (view == null || view == this) return false;
        if (view instanceof GameCursorOverlay) return true;
        if (!(view instanceof ViewGroup)) return false;

        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (containsGameCursorOverlay(group.getChildAt(i))) return true;
        }
        return false;
    }

    @Nullable
    private MinecraftGLSurface findMinecraftSurfaceTarget() {
        View current = passthroughTarget;
        while (current != null) {
            if (current instanceof MinecraftGLSurface) {
                return (MinecraftGLSurface) current;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }

        View root = getRootView();
        MinecraftGLSurface found = findMinecraftSurfaceInTree(root);
        if (found != null) return found;

        ViewParent parent = getParent();
        return parent instanceof View ? findMinecraftSurfaceInTree((View) parent) : null;
    }

    @Nullable
    private MinecraftGLSurface findMinecraftSurfaceInTree(@Nullable View view) {
        if (view == null || view == this) return null;
        if (view instanceof MinecraftGLSurface) return (MinecraftGLSurface) view;
        if (!(view instanceof ViewGroup)) return null;

        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            MinecraftGLSurface found = findMinecraftSurfaceInTree(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private boolean dispatchActivePassthroughPointer(@NonNull MotionEvent event, int action) {
        if (passthroughPointerId == NO_POINTER_ID) {
            return false;
        }

        int pointerIndex = event.findPointerIndex(passthroughPointerId);
        if (pointerIndex < 0) {
            return false;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            updatePassthroughMoveState(event, pointerIndex);
        }

        return dispatchSinglePointerToPassthrough(event, pointerIndex, action);
    }

    private void updatePassthroughMoveState(@NonNull MotionEvent event, int pointerIndex) {
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) return;
        float dx = event.getX(pointerIndex) - passthroughDownX;
        float dy = event.getY(pointerIndex) - passthroughDownY;
        if ((dx * dx) + (dy * dy) > (cameraTouchSlop * cameraTouchSlop)) {
            passthroughMovedPastSlop = true;
        }
    }


    private boolean dispatchSinglePointerToPassthrough(
            @NonNull MotionEvent source,
            int pointerIndex,
            int action
    ) {
        if (pointerIndex < 0 || pointerIndex >= source.getPointerCount()) {
            return false;
        }

        MinecraftGLSurface minecraftSurface = findMinecraftSurfaceTarget();
        if (minecraftSurface == null && passthroughTarget == null) {
            return false;
        }

        long downTime = passthroughDownTime > 0L ? passthroughDownTime : source.getDownTime();
        MotionEvent single = MotionEvent.obtain(
                downTime,
                source.getEventTime(),
                action,
                source.getX(pointerIndex),
                source.getY(pointerIndex),
                source.getMetaState()
        );
        int sourceClass = source.getSource();
        single.setSource(sourceClass != 0 ? sourceClass : InputDevice.SOURCE_TOUCHSCREEN);
        try {
            View target = minecraftSurface != null ? minecraftSurface : passthroughTarget;
            transformPassthroughCoordinates(single, target);
            if (minecraftSurface != null) {
                return minecraftSurface.handleTouchFromOverlay(
                        single, shouldAllowAndroidFocusForPassthrough(minecraftSurface));
            }
            return passthroughTarget != null && passthroughTarget.dispatchTouchEvent(single);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to dispatch passthrough touch event", throwable);
            return false;
        } finally {
            single.recycle();
        }
    }

    private boolean dispatchWholeTouchEventToPassthrough(@NonNull MotionEvent event) {
        MinecraftGLSurface minecraftSurface = findMinecraftSurfaceTarget();
        if (minecraftSurface == null && passthroughTarget == null) {
            return false;
        }

        MotionEvent copy = MotionEvent.obtain(event);
        try {
            if (copy.getSource() == 0) copy.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            View target = minecraftSurface != null ? minecraftSurface : passthroughTarget;
            transformPassthroughCoordinates(copy, target);
            return minecraftSurface != null
                    ? minecraftSurface.handleTouchFromOverlay(
                            copy, shouldAllowAndroidFocusForPassthrough(minecraftSurface))
                    : passthroughTarget != null && passthroughTarget.dispatchTouchEvent(copy);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to dispatch whole passthrough touch event", throwable);
            return false;
        } finally {
            copy.recycle();
        }
    }

    private boolean dispatchWholeGenericEventToPassthrough(@NonNull MotionEvent event) {
        MinecraftGLSurface minecraftSurface = findMinecraftSurfaceTarget();
        if (minecraftSurface == null && passthroughTarget == null) {
            return false;
        }

        MotionEvent copy = MotionEvent.obtain(event);
        try {
            View target = minecraftSurface != null ? minecraftSurface : passthroughTarget;
            transformPassthroughCoordinates(copy, target);
            return minecraftSurface != null
                    ? minecraftSurface.dispatchGenericMotionEvent(copy)
                    : passthroughTarget != null && passthroughTarget.dispatchGenericMotionEvent(copy);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to dispatch hardware pointer passthrough event", throwable);
            return false;
        } finally {
            copy.recycle();
        }
    }

    /**
     * Converts an event from this overlay's panel coordinates into the destination
     * Minecraft surface coordinates. This is a no-op on matching panels, but fixes
     * devices such as the AYN Thor where the lower 1240x1080 touch panel controls a
     * 1920x1080 game surface on the upper display.
     */
    private void transformPassthroughCoordinates(
            @NonNull MotionEvent event,
            @Nullable View target
    ) {
        if (target == null) return;

        /*
         * Dual-screen input has two different coordinate jobs:
         *
         *  1. GUI/menu input is absolute. A touch at 50% of the 1240x1080 Thor
         *     lower panel must land at 50% of the 1920x1080 upper Minecraft panel.
         *  2. Grabbed gameplay input is relative. Scaling that same MotionEvent by
         *     1920/1240 makes horizontal camera movement ~1.55x faster than vertical
         *     movement and makes the lower screen feel offset/warped.
         *
         * Only normalize coordinates while Minecraft is NOT grabbing the mouse.
         * Grabbed camera/relative motion keeps the lower panel's native deltas.
         */
        try {
            if (CallbackBridge.isGrabbing()) return;
        } catch (Throwable ignored) {
        }

        // Portrait (Centered Game View) is a same-window child input viewport, not a
        // second display. Convert the fullscreen overlay coordinate into the centered
        // surface's *layout* coordinate before Minecraft maps it to GLFW/SDL space.
        // Use getLeft()/getTop() for sibling views so IME translation is not counted
        // twice; MinecraftGLSurface already compensates imeViewportBottomInset.
        // All normal fullscreen and cross-display paths keep their established scaling.
        if (inputViewportTarget != null
                && target == inputViewportTarget
                && !isCrossDisplayTarget(target)) {
            try {
                float offsetX;
                float offsetY;
                if (target.getParent() == getParent()) {
                    offsetX = getLeft() - target.getLeft();
                    offsetY = getTop() - target.getTop();
                } else {
                    int[] sourceLocation = new int[2];
                    int[] targetLocation = new int[2];
                    getLocationOnScreen(sourceLocation);
                    target.getLocationOnScreen(targetLocation);
                    offsetX = sourceLocation[0] - targetLocation[0];
                    offsetY = sourceLocation[1] - targetLocation[1];
                }
                Matrix translate = new Matrix();
                translate.setTranslate(offsetX, offsetY);
                event.transform(translate);
                return;
            } catch (Throwable ignored) {
                // Fall through to the legacy normalized path if a view is not yet laid out.
            }
        }

        int sourceWidth = getWidth();
        int sourceHeight = getHeight();
        int targetWidth = target.getWidth();
        int targetHeight = target.getHeight();

        // A Presentation can receive its first touch before every child has completed
        // layout. Fall back to the actual display pixel size instead of sending an
        // unscaled 1240-wide touch into a 1920-wide Minecraft GUI.
        if (sourceWidth <= 1 || sourceHeight <= 1) {
            try {
                Display sourceDisplay = getDisplay();
                if (sourceDisplay != null) {
                    android.graphics.Point size = new android.graphics.Point();
                    sourceDisplay.getRealSize(size);
                    if (sourceWidth <= 1) sourceWidth = size.x;
                    if (sourceHeight <= 1) sourceHeight = size.y;
                }
            } catch (Throwable ignored) {
            }
        }
        if (targetWidth <= 1 || targetHeight <= 1) {
            try {
                Display targetDisplay = target.getDisplay();
                if (targetDisplay != null) {
                    android.graphics.Point size = new android.graphics.Point();
                    targetDisplay.getRealSize(size);
                    if (targetWidth <= 1) targetWidth = size.x;
                    if (targetHeight <= 1) targetHeight = size.y;
                }
            } catch (Throwable ignored) {
            }
        }

        if (sourceWidth <= 1 || sourceHeight <= 1 || targetWidth <= 1 || targetHeight <= 1) {
            return;
        }
        if (sourceWidth == targetWidth && sourceHeight == targetHeight) return;

        float scaleX = (targetWidth - 1f) / (sourceWidth - 1f);
        float scaleY = (targetHeight - 1f) / (sourceHeight - 1f);
        if (Float.isNaN(scaleX) || Float.isInfinite(scaleX)
                || Float.isNaN(scaleY) || Float.isInfinite(scaleY)
                || scaleX <= 0f || scaleY <= 0f) {
            return;
        }

        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY);
        event.transform(matrix);
    }

    @NonNull
    private RectF resolveInputViewportBounds(int parentWidth, int parentHeight) {
        RectF full = new RectF(0f, 0f, Math.max(1, parentWidth), Math.max(1, parentHeight));
        View target = inputViewportTarget;
        if (target == null || target.getWidth() <= 1 || target.getHeight() <= 1) {
            return full;
        }
        if (isCrossDisplayTarget(target)) {
            return full;
        }

        try {
            float left;
            float top;
            if (target.getParent() == getParent()) {
                // Layout coordinates intentionally ignore transient translationY from
                // the IME viewport controller. Input mapping compensates that shift
                // inside MinecraftGLSurface, so the touch-control canvas stays stable.
                left = target.getLeft() - getLeft();
                top = target.getTop() - getTop();
            } else {
                int[] sourceLocation = new int[2];
                int[] targetLocation = new int[2];
                getLocationOnScreen(sourceLocation);
                target.getLocationOnScreen(targetLocation);
                left = targetLocation[0] - sourceLocation[0];
                top = targetLocation[1] - sourceLocation[1];
            }
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

    private boolean isPointInsideInputViewport(float x, float y) {
        RectF viewport = resolveInputViewportBounds(
                Math.max(1, getWidth()),
                Math.max(1, getHeight())
        );
        return viewport.contains(x, y);
    }

    private boolean isCrossDisplayTarget(@Nullable View target) {
        if (target == null) return false;
        try {
            Display sourceDisplay = getDisplay();
            Display targetDisplay = target.getDisplay();
            return sourceDisplay != null
                    && targetDisplay != null
                    && sourceDisplay.getDisplayId() != targetDisplay.getDisplayId();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isCrossDisplayPassthroughTarget() {
        return isCrossDisplayTarget(findMinecraftSurfaceTarget());
    }

    private boolean shouldAllowAndroidFocusForPassthrough(@NonNull View target) {
        try {
            Display sourceDisplay = getDisplay();
            Display targetDisplay = target.getDisplay();
            if (sourceDisplay != null && targetDisplay != null) {
                return sourceDisplay.getDisplayId() == targetDisplay.getDisplayId();
            }
        } catch (Throwable ignored) {
        }
        // Same-screen overlays need normal View focus behavior; only suppress it when
        // we can positively identify a cross-display dual-screen passthrough.
        return true;
    }

    private static boolean isHardwarePointerEvent(@NonNull MotionEvent event) {
        int source = event.getSource();
        if ((source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || (source & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
                || (source & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) {
            return true;
        }

        for (int i = 0; i < event.getPointerCount(); i++) {
            if (event.getToolType(i) == MotionEvent.TOOL_TYPE_MOUSE) return true;
        }
        return false;
    }

    /**
     * Routes the centered Android Virtual Mouse software cursor into DroidBridge touch
     * controls while Minecraft keeps the physical mouse captured for correct menu
     * centering. This path intentionally never falls through to Minecraft; callers
     * use the return value to decide whether the same physical click should continue
     * to the game.
     */
    public boolean dispatchCapturedPhysicalMouseToControl(
            int action,
            float screenX,
            float screenY,
            long eventTime
    ) {
        if (editMode || getVisibility() != VISIBLE || getAlpha() <= 0f) return false;

        int[] location = new int[2];
        try {
            getLocationOnScreen(location);
        } catch (Throwable ignored) {
            location[0] = 0;
            location[1] = 0;
        }
        float localX = screenX - location[0];
        float localY = screenY - location[1];

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                if (capturedPhysicalMouseControlTarget != null) return true;
                TouchControlButtonView target = findControlUnder(localX, localY);
                if (target == null) {
                    capturedPhysicalMouseControlTarget = null;
                    capturedPhysicalMouseControlDownTime = 0L;
                    return false;
                }
                capturedPhysicalMouseControlTarget = target;
                capturedPhysicalMouseControlDownTime = eventTime > 0L
                        ? eventTime : SystemClock.uptimeMillis();
                dispatchCapturedPhysicalMouseToTarget(
                        target, MotionEvent.ACTION_DOWN, localX, localY, eventTime);
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                TouchControlButtonView target = capturedPhysicalMouseControlTarget;
                if (target == null) return false;
                dispatchCapturedPhysicalMouseToTarget(
                        target, MotionEvent.ACTION_MOVE, localX, localY, eventTime);
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                TouchControlButtonView target = capturedPhysicalMouseControlTarget;
                if (target == null) return false;
                dispatchCapturedPhysicalMouseToTarget(
                        target, action, localX, localY, eventTime);
                capturedPhysicalMouseControlTarget = null;
                capturedPhysicalMouseControlDownTime = 0L;
                return true;
            }

            default:
                return false;
        }
    }

    private void dispatchCapturedPhysicalMouseToTarget(
            @NonNull TouchControlButtonView target,
            int action,
            float overlayX,
            float overlayY,
            long eventTime
    ) {
        long now = eventTime > 0L ? eventTime : SystemClock.uptimeMillis();
        long downTime = capturedPhysicalMouseControlDownTime > 0L
                ? capturedPhysicalMouseControlDownTime : now;
        float localX = overlayX - target.getX();
        float localY = overlayY - target.getY();
        MotionEvent synthetic = MotionEvent.obtain(
                downTime,
                now,
                action,
                localX,
                localY,
                0
        );
        synthetic.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            target.dispatchTouchEvent(synthetic);
        } finally {
            synthetic.recycle();
        }
    }

    private void dispatchSinglePointerToControl(
            @NonNull MotionEvent source,
            int pointerIndex,
            int action,
            @NonNull TouchControlButtonView control
    ) {
        if (pointerIndex < 0 || pointerIndex >= source.getPointerCount()) return;

        float localX = source.getX(pointerIndex) - control.getX();
        float localY = source.getY(pointerIndex) - control.getY();
        MotionEvent single = MotionEvent.obtain(
                source.getDownTime(),
                source.getEventTime(),
                action,
                localX,
                localY,
                source.getMetaState()
        );
        try {
            control.dispatchTouchEvent(single);
        } finally {
            single.recycle();
        }
    }

    @Nullable
    private TouchControlButtonView findControlUnder(float x, float y) {
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View child = getChildAt(i);
            if (!(child instanceof TouchControlButtonView)) continue;
            if (child.getVisibility() != VISIBLE) continue;
            if (x >= child.getX()
                    && x <= child.getX() + child.getWidth()
                    && y >= child.getY()
                    && y <= child.getY() + child.getHeight()) {
                return (TouchControlButtonView) child;
            }
        }
        return null;
    }

    private void clearRuntimeTouchRouting() {
        if (touchControllerPointerTargets.size() > 0) {
            TouchControllerModCompat.clearPointers();
            touchControllerPointerTargets.clear();
        }
        cancelCameraPointer(true);
        swipeGesturePointers.clear();
        cancelAllMousePassThroughPointers();
        finishHotbarPointer(false);
        cancelVirtualMousePointer();
        passthroughPointerId = NO_POINTER_ID;
        passthroughDownTime = 0L;
        passthroughDownX = 0f;
        passthroughDownY = 0f;
        passthroughMovedPastSlop = false;
        controlPointerTargets.clear();
    }


    private boolean hasActiveTouchRoute() {
        return cameraPointerId != NO_POINTER_ID
                || hotbarPointerId != NO_POINTER_ID
                || virtualMousePointerId != NO_POINTER_ID
                || passthroughPointerId != NO_POINTER_ID
                || swipeGesturePointers.size() > 0
                || mousePassThroughPointers.size() > 0
                || controlPointerTargets.size() > 0
                || touchControllerPointerTargets.size() > 0;
    }

    @Override
    protected void onDetachedFromWindow() {
        unregisterControllerAutoHideListener();
        hideKeySenderKeyboard();
        resetMenuTwoFingerScroll();
        clearRuntimeTouchRouting();
        applyAndroidPointerIconPolicy(false, true);
        super.onDetachedFromWindow();
    }
}

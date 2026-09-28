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

import ca.dnamobile.droidbridgelauncher.LauncherTheme;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDialog;

import java.util.Collections;

import ca.dnamobile.droidbridgelauncher.ui.LauncherDialogStyle;
import ca.dnamobile.droidbridgelauncher.utils.FullscreenUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Drag buttons to move; tap a button to edit/delete it. */
public final class InGameControlsEditorDialog extends AppCompatDialog {
    private static final String UI_PREFS = "touch_controls_editor_ui";
    private static final String KEY_MENU_X = "floating_menu_x";
    private static final String KEY_MENU_Y = "floating_menu_y";

    @NonNull private final Activity hostActivity;
    @Nullable private final View coordinateReference;
    @Nullable private final TouchControlsOverlay liveOverlay;
    @Nullable private final Runnable onClosed;
    private boolean closed;

    public InGameControlsEditorDialog(
            @NonNull Activity hostActivity,
            @Nullable Runnable onClosed
    ) {
        this(hostActivity, null, onClosed);
    }

    public InGameControlsEditorDialog(
            @NonNull Activity hostActivity,
            @Nullable View coordinateReference,
            @Nullable Runnable onClosed
    ) {
        super(hostActivity, LauncherTheme.getStyleRes(hostActivity));
        this.hostActivity = hostActivity;
        this.coordinateReference = coordinateReference;
        this.liveOverlay = coordinateReference instanceof TouchControlsOverlay
                ? (TouchControlsOverlay) coordinateReference
                : null;
        this.onClosed = onClosed;
        setCanceledOnTouchOutside(false);
    }

    private TouchControlsOverlay overlay;
    private FrameLayout windowRoot;
    private FrameLayout root;
    private LinearLayout editorPanel;
    private BoundedScrollView editorScroll;
    private LinearLayout editorContent;
    private Button menuButton;
    private TextView globalOpacityValue;
    private SeekBar globalOpacitySlider;
    private Button snapButton;
    private Button mouseToggleButton;
    private TextView globalScaleValue;
    private SeekBar globalScaleSlider;
    private TextView globalRadiusValue;
    private SeekBar globalRadiusSlider;
    private TextView globalStrokeValue;
    private SeekBar globalStrokeSlider;

    private int menuTouchSlop;
    private float menuDownRawX;
    private float menuDownRawY;
    private float menuStartX;
    private float menuStartY;
    private boolean menuDragging;
    private boolean editorViewportInitialized;

    private final Runnable immersiveReapplyRunnable = this::enableImmersiveSafely;
    private final View.OnLayoutChangeListener coordinateReferenceLayoutListener =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    synchronizeEditorViewport();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        configureDialogWindow();

        // GameActivity intentionally keeps its own game-facing theme. The editor must
        // not inherit that theme or its Material dialogs/color picker diverge from the
        // standalone touch editor. AppCompatDialog#getContext() is already wrapped in
        // the selected DroidBridge launcher theme passed to super(...).
        final Context editorContext = getContext();
        LauncherDialogStyle.syncTheme(editorContext);

        menuTouchSlop = ViewConfiguration.get(editorContext).getScaledTouchSlop();

        windowRoot = new FrameLayout(editorContext);
        windowRoot.setBackgroundColor(Color.TRANSPARENT);
        windowRoot.setClipChildren(false);
        windowRoot.setClipToPadding(false);
        setContentView(windowRoot);

        // Keep the editor controls in the exact same coordinate space as the live
        // in-game TouchControlsOverlay. A Dialog owns a separate Android window,
        // whose decor/inset bounds can differ from GameActivity on cutout, gesture-
        // navigation, external-display, and some vendor fullscreen implementations.
        // Saving positions against the dialog's full canvas therefore shifted the
        // buttons when the real game overlay reloaded them.
        root = new FrameLayout(editorContext);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        windowRoot.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP | Gravity.START
        ));

        windowRoot.setOnSystemUiVisibilityChangeListener(visibility -> {
            boolean barsVisible = (visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0
                    || (visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0;
            if (barsVisible) {
                windowRoot.removeCallbacks(immersiveReapplyRunnable);
                windowRoot.postDelayed(immersiveReapplyRunnable, 350L);
            }
        });
        windowRoot.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            synchronizeEditorViewport();
            updateSystemGestureExclusionRects();
        });
        if (coordinateReference != null) {
            coordinateReference.addOnLayoutChangeListener(coordinateReferenceLayoutListener);
        }
        enableImmersiveSafely();

        overlay = new TouchControlsOverlay(editorContext);
        overlay.setEditorPanelHideRequest(this::hideGlobalEditorPanelForControlEdit);
        if (liveOverlay != null) {
            overlay.setEditorPreviewListener(new TouchControlsOverlay.EditorPreviewListener() {
                @Override
                public void onEditorLayoutPreviewChanged(
                        @NonNull TouchControlsOverlay editorOverlay
                ) {
                    liveOverlay.applyEditorLayoutPreviewFrom(editorOverlay);
                }

                @Override
                public void onEditorControlPreviewSuppressed(
                        @NonNull String controlId,
                        boolean suppressed
                ) {
                    liveOverlay.setEditorControlPreviewSuppressed(controlId, suppressed);
                }
            });
        }
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        editorPanel = new LinearLayout(editorContext);
        editorPanel.setOrientation(LinearLayout.VERTICAL);
        editorPanel.setGravity(Gravity.TOP);
        editorPanel.setPadding(dp(12), dp(10), dp(12), dp(10));
        editorPanel.setBackground(makePanelBackground());
        editorPanel.setVisibility(View.GONE);
        root.addView(editorPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START
        ));

        buildEditorPanel();

        menuButton = new Button(editorContext);
        menuButton.setText("⚙");
        menuButton.setTextSize(22f);
        menuButton.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        menuButton.setTypeface(Typeface.DEFAULT_BOLD);
        menuButton.setAllCaps(false);
        menuButton.setAlpha(0.76f);
        menuButton.setBackground(makeGearBackground());
        menuButton.setOnTouchListener(this::handleMenuButtonTouch);
        root.addView(menuButton, new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP | Gravity.START));

        editorPanel.bringToFront();
        menuButton.bringToFront();

        windowRoot.post(() -> {
            synchronizeEditorViewport();
            root.post(() -> {
                restoreMenuButtonPosition();
                if (overlay != null) {
                    overlay.setEditMode(true);
                    overlay.loadSelectedLayout();
                    syncGlobalAppearanceSlidersFromLayout();
                    if (snapButton != null) updateSnapButtonText(snapButton);
                    if (mouseToggleButton != null) updateMouseButtonText(mouseToggleButton);
                }
                enableImmersiveSafely();
                updateSystemGestureExclusionRects();
            });
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        configureDialogWindow();
        enableImmersiveSafely();
        if (windowRoot != null) windowRoot.post(this::synchronizeEditorViewport);
    }


    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enableImmersiveSafely();
            synchronizeEditorViewport();
            View decor = getWindow() == null ? null : getWindow().getDecorView();
            if (decor != null) decor.postDelayed(immersiveReapplyRunnable, 250L);
            updateSystemGestureExclusionRects();
        }
    }

    @Override
    public void dismiss() {
        if (closed) {
            super.dismiss();
            return;
        }
        closed = true;
        if (windowRoot != null) {
            windowRoot.removeCallbacks(immersiveReapplyRunnable);
            windowRoot.setOnSystemUiVisibilityChangeListener(null);
        }
        if (coordinateReference != null) {
            try {
                coordinateReference.removeOnLayoutChangeListener(coordinateReferenceLayoutListener);
            } catch (Throwable ignored) {
            }
        }
        if (overlay != null) {
            overlay.setEditorPreviewListener(null);
        }
        if (liveOverlay != null) {
            liveOverlay.clearEditorControlPreviewSuppressions();
        }
        super.dismiss();
        if (onClosed != null) {
            try {
                onClosed.run();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onBackPressed() {
        requestCloseEditor();
    }

    private void requestCloseEditor() {
        if (overlay == null || !overlay.hasEditorSessionChanges()) {
            dismiss();
            return;
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(getContext())
                .setTitle("Close touch editor?")
                .setMessage("Save your touch-control changes before closing, or close without saving to restore the layout from when you opened the editor.")
                .setNegativeButton("Close without saving", (unused, which) -> {
                    if (overlay != null) {
                        overlay.discardEditorSessionChanges();
                    }
                    dismiss();
                })
                .setNeutralButton(android.R.string.cancel, null)
                .setPositiveButton("Save & Close", (unused, which) -> {
                    if (overlay != null) {
                        overlay.saveLayout();
                        overlay.markEditorSessionSaved();
                    }
                    dismiss();
                })
                .create();
        dialog.setOnShowListener(unused -> styleEditorDialogChrome(dialog));
        dialog.setOnDismissListener(unused -> {
            if (windowRoot != null) {
                windowRoot.removeCallbacks(immersiveReapplyRunnable);
                windowRoot.postDelayed(immersiveReapplyRunnable, 150L);
            }
        });
        dialog.show();
        styleEditorDialogChrome(dialog);
    }


    private void styleEditorDialogChrome(@NonNull AlertDialog dialog) {
        Context editorContext = getContext();
        LauncherDialogStyle.syncTheme(editorContext);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(LauncherDialogStyle.roundedDrawable(
                    editorContext,
                    LauncherDialogStyle.COLOR_DIALOG_BG,
                    LauncherDialogStyle.COLOR_CARD_STROKE,
                    22
            ));
            window.setDimAmount(LauncherDialogStyle.DIALOG_DIM_NORMAL);

            int sideMargin = dp(
                    editorContext.getResources().getDisplayMetrics().widthPixels
                            > editorContext.getResources().getDisplayMetrics().heightPixels
                            ? 24 : 16
            );
            int available = Math.max(
                    dp(280),
                    editorContext.getResources().getDisplayMetrics().widthPixels - (sideMargin * 2)
            );
            window.setLayout(Math.min(available, dp(720)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        LauncherDialogStyle.tintDialogButton(dialog, AlertDialog.BUTTON_POSITIVE);
        LauncherDialogStyle.tintDialogButton(dialog, AlertDialog.BUTTON_NEGATIVE);
        LauncherDialogStyle.tintDialogButton(dialog, AlertDialog.BUTTON_NEUTRAL);
    }

    private void configureDialogWindow() {
        Window window = getWindow();
        if (window == null) return;
        try {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0f);
            // FLAG_LAYOUT_NO_LIMITS gives some vendor dialog windows a canvas
            // larger than the GameActivity content. The editor viewport is aligned
            // explicitly below, so keep the dialog itself within normal fullscreen
            // window bounds.
            window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            window.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
        } catch (Throwable ignored) {
        }
    }

    private void synchronizeEditorViewport() {
        if (windowRoot == null || root == null) return;
        if (windowRoot.getWidth() <= 1 || windowRoot.getHeight() <= 1) return;

        /*
         * A per-button editor is another Android dialog window. Some devices briefly
         * report different fullscreen/inset bounds while that child window takes
         * focus. Rebuilding against that temporary canvas is what made a moved copy
         * jump the instant it was tapped for editing. Keep the already-established
         * game-overlay viewport frozen until the child panel has fully closed.
         */
        if (editorViewportInitialized
                && overlay != null
                && overlay.isControlEditPanelActive()) {
            return;
        }

        View reference = coordinateReference;
        int targetWidth = windowRoot.getWidth();
        int targetHeight = windowRoot.getHeight();
        int targetLeft = 0;
        int targetTop = 0;

        if (reference != null
                && reference.isAttachedToWindow()
                && reference.getWidth() > 1
                && reference.getHeight() > 1) {
            int[] referenceLocation = new int[2];
            int[] dialogLocation = new int[2];
            try {
                reference.getLocationOnScreen(referenceLocation);
                windowRoot.getLocationOnScreen(dialogLocation);
                targetLeft = referenceLocation[0] - dialogLocation[0];
                targetTop = referenceLocation[1] - dialogLocation[1];
                targetWidth = reference.getWidth();
                targetHeight = reference.getHeight();
            } catch (Throwable ignored) {
                targetLeft = 0;
                targetTop = 0;
                targetWidth = windowRoot.getWidth();
                targetHeight = windowRoot.getHeight();
            }
        }

        targetWidth = Math.max(1, targetWidth);
        targetHeight = Math.max(1, targetHeight);

        FrameLayout.LayoutParams params;
        ViewGroup.LayoutParams current = root.getLayoutParams();
        if (current instanceof FrameLayout.LayoutParams) {
            params = (FrameLayout.LayoutParams) current;
        } else {
            params = new FrameLayout.LayoutParams(targetWidth, targetHeight, Gravity.TOP | Gravity.START);
        }

        boolean sizeChanged = params.width != targetWidth
                || params.height != targetHeight;
        boolean changed = sizeChanged
                || params.leftMargin != targetLeft
                || params.topMargin != targetTop;
        editorViewportInitialized = true;
        if (!changed) return;

        params.width = targetWidth;
        params.height = targetHeight;
        params.leftMargin = targetLeft;
        params.topMargin = targetTop;
        params.gravity = Gravity.TOP | Gravity.START;
        root.setLayoutParams(params);
        root.requestLayout();

        // The geometry change can happen after the first dialog frame while
        // immersive bars are disappearing. Rebuild the editor overlay only after
        // it has the canonical game-overlay dimensions.
        if (sizeChanged && overlay != null) {
            overlay.requestLayout();
            overlay.post(overlay::refreshButtonGeometry);
        }
        if (menuButton != null) {
            root.post(() -> {
                moveMenuButton(menuButton.getX(), menuButton.getY());
                if (editorPanel != null && editorPanel.getVisibility() == View.VISIBLE) {
                    positionPanelNearMenuButton();
                }
                updateSystemGestureExclusionRects();
            });
        }
    }

    private void buildEditorPanel() {
        LinearLayout headerRow = new LinearLayout(getContext());
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(0, 0, 0, dp(6));

        TextView header = new TextView(getContext());
        header.setText("Touch editor");
        header.setTextSize(15f);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        header.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.addView(header, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button topClose = panelButton("Hide panel");
        topClose.setOnClickListener(view -> setPanelVisible(false));
        headerRow.addView(topClose, new LinearLayout.LayoutParams(dp(112), dp(38)));

        editorPanel.addView(headerRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        editorScroll = new BoundedScrollView(getContext());
        editorScroll.setFillViewport(false);
        editorScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        editorScroll.setVerticalScrollBarEnabled(true);
        editorScroll.setScrollbarFadingEnabled(true);

        editorContent = new LinearLayout(getContext());
        editorContent.setOrientation(LinearLayout.VERTICAL);
        editorContent.setGravity(Gravity.CENTER_HORIZONTAL);
        editorContent.setPadding(0, 0, 0, dp(4));
        editorScroll.addView(editorContent, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        editorPanel.addView(editorScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        addGlobalOpacityControls();

        LinearLayout rowOne = panelRow();
        LinearLayout rowTwo = panelRow();
        LinearLayout rowThree = panelRow();
        LinearLayout rowFour = panelRow();

        Button addKey = panelButton("Add Key");
        addKey.setOnClickListener(view -> {
            overlay.addControl(TouchControlData.key("Key", 32, 120, 120, 72, 52));
            Toast.makeText(hostActivity, "Tap the new button to edit it.", Toast.LENGTH_SHORT).show();
        });
        rowOne.addView(addKey, panelButtonParams());

        Button addMouse = panelButton("Add Mouse");
        addMouse.setOnClickListener(view -> overlay.addControl(TouchControlData.mouse("Mouse", 0, 220, 120)));
        rowOne.addView(addMouse, panelButtonParams());

        Button addJoystick = panelButton("Add Stick");
        addJoystick.setOnClickListener(view -> {
            overlay.addControl(TouchControlData.joystick("Joystick", 48, 330, 128, 128));
            Toast.makeText(hostActivity, "Added joystick. Long-press it to resize or move it.", Toast.LENGTH_SHORT).show();
        });
        rowOne.addView(addJoystick, panelButtonParams());

        snapButton = panelButton("");
        updateSnapButtonText(snapButton);
        snapButton.setOnClickListener(view -> {
            boolean enabled = overlay != null
                    ? !overlay.isProfileSnapControlsEnabled()
                    : !ControlsPreferences.isSnapControlsEnabled(hostActivity);
            if (overlay != null) overlay.setProfileSnapControlsEnabled(enabled);
            else ControlsPreferences.setSnapControlsEnabled(hostActivity, enabled);
            updateSnapButtonText(snapButton);
            Toast.makeText(hostActivity, enabled ? "Snap enabled for this profile." : "Snap disabled for this profile.", Toast.LENGTH_SHORT).show();
        });
        rowTwo.addView(snapButton, panelButtonParams());

        mouseToggleButton = panelButton("");
        updateMouseButtonText(mouseToggleButton);
        mouseToggleButton.setOnClickListener(view -> {
            boolean enabled = overlay != null
                    ? !overlay.isProfileVirtualMouseEnabled()
                    : !ControlsPreferences.isVirtualMouseEnabled(hostActivity);
            if (overlay != null) overlay.setProfileVirtualMouseEnabled(enabled);
            else ControlsPreferences.setVirtualMouseEnabled(hostActivity, enabled);
            updateMouseButtonText(mouseToggleButton);
            Toast.makeText(hostActivity, enabled ? "Virtual cursor shown for this profile." : "Virtual cursor hidden for this profile.", Toast.LENGTH_SHORT).show();
        });
        rowTwo.addView(mouseToggleButton, panelButtonParams());

        Button save = panelButton("Save");
        save.setOnClickListener(view -> {
            if (overlay != null) {
                overlay.saveLayout();
                overlay.markEditorSessionSaved();
            }
            Toast.makeText(hostActivity, "Touch controls saved.", Toast.LENGTH_SHORT).show();
        });
        rowTwo.addView(save, panelButtonParams());

        Button undo = panelButton("Undo");
        undo.setOnClickListener(view -> {
            if (overlay.undoLastChange()) {
                Toast.makeText(hostActivity, "Undid last touch edit.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(hostActivity, "Nothing to undo.", Toast.LENGTH_SHORT).show();
            }
        });
        rowThree.addView(undo, panelButtonParams());

        Button redo = panelButton("Redo");
        redo.setOnClickListener(view -> {
            if (overlay.redoLastChange()) {
                Toast.makeText(hostActivity, "Redid touch edit.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(hostActivity, "Nothing to redo.", Toast.LENGTH_SHORT).show();
            }
        });
        rowThree.addView(redo, panelButtonParams());

        // Keep this action set in parity with ControlsEditorActivity. The in-game
        // editor used to be an older fork and silently missed newer actions such as
        // Drawer creation.
        Button addDrawer = panelButton("Add Drawer");
        addDrawer.setOnClickListener(view -> {
            overlay.addControl(TouchControlData.drawer("Drawer", 320, 120, 96, 52));
            Toast.makeText(
                    getContext(),
                    "Added drawer. Tap it to choose which buttons it shows and hides.",
                    Toast.LENGTH_SHORT
            ).show();
        });
        rowFour.addView(addDrawer, panelButtonParams());

        Button closeEditor = panelButton("Close Editor");
        closeEditor.setOnClickListener(view -> requestCloseEditor());
        rowFour.addView(closeEditor, panelButtonParams());

        editorContent.addView(rowOne);
        editorContent.addView(rowTwo);
        editorContent.addView(rowThree);
        editorContent.addView(rowFour);
    }


    private void addGlobalOpacityControls() {
        TextView title = new TextView(getContext());
        title.setText("All button opacity");
        title.setTextSize(12f);
        title.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(4), 0, 0);
        editorContent.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        globalOpacityValue = new TextView(getContext());
        globalOpacityValue.setTextSize(11f);
        globalOpacityValue.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        globalOpacityValue.setGravity(Gravity.CENTER);
        globalOpacityValue.setPadding(0, 0, 0, dp(2));
        editorContent.addView(globalOpacityValue, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        globalOpacitySlider = new SeekBar(getContext());
        globalOpacitySlider.setMax(100);
        int progress = Math.round((overlay != null ? overlay.getProfileGlobalOpacity() : ControlsPreferences.getGlobalOpacity(hostActivity)) * 100f);
        globalOpacitySlider.setProgress(Math.max(0, Math.min(100, progress)));
        updateGlobalOpacityLabel(globalOpacitySlider.getProgress());
        globalOpacitySlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int safeProgress = Math.max(0, Math.min(100, progress));
                updateGlobalOpacityLabel(safeProgress);
                if (fromUser) {
                    if (overlay != null) overlay.previewProfileGlobalOpacity(safeProgress / 100f);
                    else ControlsPreferences.setGlobalOpacity(hostActivity, safeProgress / 100f);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int safeProgress = Math.max(0, Math.min(100, seekBar.getProgress()));
                if (overlay != null) overlay.applyProfileGlobalOpacity(safeProgress / 100f);
                else ControlsPreferences.setGlobalOpacity(hostActivity, safeProgress / 100f);
                updateGlobalOpacityLabel(safeProgress);
            }
        });
        editorContent.addView(globalOpacitySlider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(38)
        ));

        addGlobalButtonScaleControls();
    }

    private void addGlobalButtonScaleControls() {
        TextView title = new TextView(getContext());
        title.setText("All button scale");
        title.setTextSize(12f);
        title.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(2), 0, 0);
        editorContent.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        globalScaleValue = new TextView(getContext());
        globalScaleValue.setTextSize(11f);
        globalScaleValue.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        globalScaleValue.setGravity(Gravity.CENTER);
        globalScaleValue.setPadding(0, 0, 0, dp(2));
        editorContent.addView(globalScaleValue, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        globalScaleSlider = new SeekBar(getContext());
        globalScaleSlider.setMax(ControlsPreferences.MAX_GLOBAL_BUTTON_SCALE_PERCENT);
        int progress = overlay != null ? overlay.getProfileGlobalButtonScalePercent() : ControlsPreferences.getGlobalButtonScalePercent(hostActivity);
        globalScaleSlider.setProgress(Math.max(
                ControlsPreferences.MIN_GLOBAL_BUTTON_SCALE_PERCENT,
                Math.min(ControlsPreferences.MAX_GLOBAL_BUTTON_SCALE_PERCENT, progress)
        ));
        updateGlobalScaleLabel(globalScaleSlider.getProgress());
        globalScaleSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int safeProgress = Math.max(
                        ControlsPreferences.MIN_GLOBAL_BUTTON_SCALE_PERCENT,
                        Math.min(ControlsPreferences.MAX_GLOBAL_BUTTON_SCALE_PERCENT, progress)
                );
                if (seekBar.getProgress() != safeProgress) {
                    seekBar.setProgress(safeProgress);
                    return;
                }
                updateGlobalScaleLabel(safeProgress);
                if (fromUser) {
                    if (overlay != null) {
                        overlay.applyGlobalButtonScalePercent(safeProgress);
                    } else {
                        ControlsPreferences.setGlobalButtonScalePercent(hostActivity, safeProgress);
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if (overlay != null) overlay.beginGlobalButtonScaleChange();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int safeProgress = Math.max(
                        ControlsPreferences.MIN_GLOBAL_BUTTON_SCALE_PERCENT,
                        Math.min(ControlsPreferences.MAX_GLOBAL_BUTTON_SCALE_PERCENT, seekBar.getProgress())
                );
                if (overlay != null) {
                    overlay.finishGlobalButtonScaleChange(safeProgress);
                } else {
                    ControlsPreferences.setGlobalButtonScalePercent(hostActivity, safeProgress);
                }
                updateGlobalScaleLabel(safeProgress);
            }
        });
        editorContent.addView(globalScaleSlider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(38)
        ));

        addGlobalButtonAppearanceControls();
    }

    private void addGlobalButtonAppearanceControls() {
        TextView radiusTitle = new TextView(getContext());
        radiusTitle.setText("All button radius");
        radiusTitle.setTextSize(12f);
        radiusTitle.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        radiusTitle.setGravity(Gravity.CENTER);
        radiusTitle.setPadding(0, dp(2), 0, 0);
        editorContent.addView(radiusTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        globalRadiusValue = new TextView(getContext());
        globalRadiusValue.setTextSize(11f);
        globalRadiusValue.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        globalRadiusValue.setGravity(Gravity.CENTER);
        globalRadiusValue.setPadding(0, 0, 0, dp(2));
        editorContent.addView(globalRadiusValue, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        globalRadiusSlider = new SeekBar(getContext());
        globalRadiusSlider.setMax(100);
        int radiusProgress = overlay == null ? 16 : Math.max(0, Math.min(100, overlay.averageButtonCornerRadius()));
        globalRadiusSlider.setProgress(radiusProgress);
        updateGlobalRadiusLabel(radiusProgress);
        globalRadiusSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int safeProgress = Math.max(0, Math.min(100, progress));
                updateGlobalRadiusLabel(safeProgress);
                if (fromUser && overlay != null) overlay.applyAllButtonCornerRadius(safeProgress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if (overlay != null) overlay.beginBulkControlAppearanceChange();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int safeProgress = Math.max(0, Math.min(100, seekBar.getProgress()));
                if (overlay != null) {
                    overlay.applyAllButtonCornerRadius(safeProgress);
                    overlay.finishBulkControlAppearanceChange();
                }
                updateGlobalRadiusLabel(safeProgress);
            }
        });
        editorContent.addView(globalRadiusSlider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(38)
        ));

        TextView strokeTitle = new TextView(getContext());
        strokeTitle.setText("All button stroke");
        strokeTitle.setTextSize(12f);
        strokeTitle.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        strokeTitle.setGravity(Gravity.CENTER);
        strokeTitle.setPadding(0, dp(2), 0, 0);
        editorContent.addView(strokeTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        globalStrokeValue = new TextView(getContext());
        globalStrokeValue.setTextSize(11f);
        globalStrokeValue.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        globalStrokeValue.setGravity(Gravity.CENTER);
        globalStrokeValue.setPadding(0, 0, 0, dp(2));
        editorContent.addView(globalStrokeValue, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        globalStrokeSlider = new SeekBar(getContext());
        globalStrokeSlider.setMax(20);
        int strokeProgress = overlay == null ? 2 : Math.max(0, Math.min(20, overlay.averageButtonStrokeWidth()));
        globalStrokeSlider.setProgress(strokeProgress);
        updateGlobalStrokeLabel(strokeProgress);
        globalStrokeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int safeProgress = Math.max(0, Math.min(20, progress));
                updateGlobalStrokeLabel(safeProgress);
                if (fromUser && overlay != null) overlay.applyAllButtonStrokeWidth(safeProgress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if (overlay != null) overlay.beginBulkControlAppearanceChange();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int safeProgress = Math.max(0, Math.min(20, seekBar.getProgress()));
                if (overlay != null) {
                    overlay.applyAllButtonStrokeWidth(safeProgress);
                    overlay.finishBulkControlAppearanceChange();
                }
                updateGlobalStrokeLabel(safeProgress);
            }
        });
        editorContent.addView(globalStrokeSlider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(38)
        ));
    }

    private void updateGlobalOpacityLabel(int progress) {
        if (globalOpacityValue != null) {
            globalOpacityValue.setText("Opacity: " + Math.max(0, Math.min(100, progress)) + "%");
        }
    }

    private void updateGlobalScaleLabel(int progress) {
        if (globalScaleValue != null) {
            int safeProgress = Math.max(
                    ControlsPreferences.MIN_GLOBAL_BUTTON_SCALE_PERCENT,
                    Math.min(ControlsPreferences.MAX_GLOBAL_BUTTON_SCALE_PERCENT, progress)
            );
            globalScaleValue.setText("Scale: " + safeProgress + "%");
        }
    }

    private void updateGlobalRadiusLabel(int progress) {
        if (globalRadiusValue != null) {
            globalRadiusValue.setText("Radius: " + Math.max(0, Math.min(100, progress)) + " dp");
        }
    }

    private void updateGlobalStrokeLabel(int progress) {
        if (globalStrokeValue != null) {
            globalStrokeValue.setText("Stroke: " + Math.max(0, Math.min(20, progress)) + " dp");
        }
    }

    private boolean handleMenuButtonTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                menuDownRawX = event.getRawX();
                menuDownRawY = event.getRawY();
                menuStartX = menuButton.getX();
                menuStartY = menuButton.getY();
                menuDragging = false;
                requestParentDisallowIntercept(view, true);
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - menuDownRawX;
                float dy = event.getRawY() - menuDownRawY;
                if (!menuDragging && ((dx * dx) + (dy * dy)) > (menuTouchSlop * menuTouchSlop)) {
                    menuDragging = true;
                    setPanelVisible(false);
                }
                if (menuDragging) moveMenuButton(menuStartX + dx, menuStartY + dy);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                requestParentDisallowIntercept(view, false);
                if (menuDragging) saveMenuButtonPosition();
                else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    view.performClick();
                    setPanelVisible(editorPanel.getVisibility() != View.VISIBLE);
                }
                menuDragging = false;
                return true;
            default:
                return true;
        }
    }

    private void requestParentDisallowIntercept(View view, boolean disallow) {
        ViewParent parent = view == null ? null : view.getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow);
        }
    }

    void hideGlobalEditorPanelForControlEdit() {
        setPanelVisible(false);
    }

    private void setPanelVisible(boolean visible) {
        if (visible) syncGlobalAppearanceSlidersFromLayout();
        editorPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        menuButton.setAlpha(visible ? 1.0f : 0.76f);
        if (visible) {
            if (editorScroll != null) editorScroll.post(() -> editorScroll.smoothScrollTo(0, 0));
            editorPanel.post(this::positionPanelNearMenuButton);
        }
    }

    private void syncGlobalAppearanceSlidersFromLayout() {
        if (overlay == null) return;
        if (globalOpacitySlider != null) {
            int opacity = Math.round(overlay.getProfileGlobalOpacity() * 100f);
            globalOpacitySlider.setProgress(Math.max(0, Math.min(100, opacity)));
            updateGlobalOpacityLabel(opacity);
        }
        if (globalScaleSlider != null) {
            int scale = overlay.getProfileGlobalButtonScalePercent();
            globalScaleSlider.setProgress(scale);
            updateGlobalScaleLabel(scale);
        }
        if (globalRadiusSlider != null) {
            int radius = Math.max(0, Math.min(100, overlay.averageButtonCornerRadius()));
            globalRadiusSlider.setProgress(radius);
            updateGlobalRadiusLabel(radius);
        }
        if (globalStrokeSlider != null) {
            int stroke = Math.max(0, Math.min(20, overlay.averageButtonStrokeWidth()));
            globalStrokeSlider.setProgress(stroke);
            updateGlobalStrokeLabel(stroke);
        }
    }

    private void restoreMenuButtonPosition() {
        SharedPreferences prefs = hostActivity.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE);
        float defaultX = Math.max(dp(4), (root.getWidth() - menuButton.getWidth()) / 2f);
        float defaultY = Math.max(dp(4), (root.getHeight() - menuButton.getHeight()) / 2f);
        boolean hasSavedPosition = prefs.contains(KEY_MENU_X) && prefs.contains(KEY_MENU_Y);
        float x = hasSavedPosition ? prefs.getFloat(KEY_MENU_X, defaultX) : defaultX;
        float y = hasSavedPosition ? prefs.getFloat(KEY_MENU_Y, defaultY) : defaultY;
        moveMenuButton(x, y);
    }

    private void saveMenuButtonPosition() {
        hostActivity.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_MENU_X, menuButton.getX())
                .putFloat(KEY_MENU_Y, menuButton.getY())
                .apply();
    }

    private void moveMenuButton(float x, float y) {
        float maxX = Math.max(0f, root.getWidth() - menuButton.getWidth() - dp(4));
        float maxY = Math.max(0f, root.getHeight() - menuButton.getHeight() - dp(4));
        menuButton.setX(clamp(x, dp(4), maxX));
        menuButton.setY(clamp(y, dp(4), maxY));
        if (editorPanel.getVisibility() == View.VISIBLE) positionPanelNearMenuButton();
    }

    private void positionPanelNearMenuButton() {
        if (root.getWidth() <= 0 || root.getHeight() <= 0) return;
        updateEditorScrollLimit();
        int maxPanelWidth = Math.max(dp(320), root.getWidth() - dp(8));
        int maxPanelHeight = Math.max(dp(260), root.getHeight() - dp(8));
        editorPanel.measure(
                View.MeasureSpec.makeMeasureSpec(maxPanelWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(maxPanelHeight, View.MeasureSpec.AT_MOST)
        );
        int panelWidth = Math.max(1, editorPanel.getMeasuredWidth());
        int panelHeight = Math.max(1, editorPanel.getMeasuredHeight());
        float spacing = dp(8);
        boolean openRight = menuButton.getX() + menuButton.getWidth() / 2f < root.getWidth() / 2f;
        float x = openRight ? menuButton.getX() + menuButton.getWidth() + spacing : menuButton.getX() - panelWidth - spacing;
        float y = menuButton.getY();
        if (x < dp(4) || x + panelWidth > root.getWidth() - dp(4)) {
            x = clamp(menuButton.getX(), dp(4), Math.max(dp(4), root.getWidth() - panelWidth - dp(4)));
            y = menuButton.getY() > root.getHeight() / 2f ? menuButton.getY() - panelHeight - spacing : menuButton.getY() + menuButton.getHeight() + spacing;
        }
        editorPanel.setX(clamp(x, dp(4), Math.max(dp(4), root.getWidth() - panelWidth - dp(4))));
        editorPanel.setY(clamp(y, dp(4), Math.max(dp(4), root.getHeight() - panelHeight - dp(4))));
    }

    private void updateEditorScrollLimit() {
        if (editorScroll == null || root == null || root.getHeight() <= 0) return;

        // Keep the title/Hide panel row fixed, but let the slider/action area scroll on
        // shorter landscape screens so Undo/Redo/Close Editor are not clipped.
        int maxPanelHeight = Math.max(dp(260), root.getHeight() - dp(8));
        int fixedHeaderAndPadding = dp(66);
        editorScroll.setMaxHeight(Math.max(dp(180), maxPanelHeight - fixedHeaderAndPadding));
    }

    private static final class BoundedScrollView extends ScrollView {
        private int maxHeight;

        BoundedScrollView(Context context) {
            super(context);
        }

        void setMaxHeight(int maxHeight) {
            if (this.maxHeight == maxHeight) return;
            this.maxHeight = Math.max(0, maxHeight);
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (maxHeight > 0) {
                int mode = MeasureSpec.getMode(heightMeasureSpec);
                int size = MeasureSpec.getSize(heightMeasureSpec);
                if (mode == MeasureSpec.UNSPECIFIED || size > maxHeight) {
                    heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
                }
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    private LinearLayout panelRow() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(2));
        return row;
    }

    private Button panelButton(String text) {
        Button button = new Button(getContext());
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackground(LauncherDialogStyle.roundedDrawable(getContext(), LauncherDialogStyle.COLOR_CARD_BG_PRESSED, LauncherDialogStyle.COLOR_CARD_STROKE, 14));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        return button;
    }

    private LinearLayout.LayoutParams panelButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(112), dp(42));
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private GradientDrawable makePanelBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(LauncherDialogStyle.COLOR_CARD_BG);
        drawable.setCornerRadius(dp(18));
        drawable.setStroke(dp(1), LauncherDialogStyle.COLOR_CARD_STROKE);
        return drawable;
    }

    private GradientDrawable makeGearBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(LauncherDialogStyle.COLOR_CARD_BG);
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setStroke(dp(1), LauncherDialogStyle.COLOR_CARD_STROKE);
        return drawable;
    }

    private void updateSystemGestureExclusionRects() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || root == null) return;
        int width = root.getWidth();
        int height = root.getHeight();
        if (width <= 0 || height <= 0) return;
        root.setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, width, height)));
    }

    private void enableImmersiveSafely() {
        try { FullscreenUtils.enableImmersive(hostActivity); } catch (Throwable ignored) { }

        updateSystemGestureExclusionRects();

        try {
            Window window = getWindow();
            if (window == null) return;

            window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

            View decor = window.getDecorView();
            if (decor != null) {
                decor.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                );

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowInsetsController controller = decor.getWindowInsetsController();
                    if (controller != null) {
                        controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                        controller.setSystemBarsBehavior(
                                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        );
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void updateSnapButtonText(Button button) {
        boolean enabled = overlay != null
                ? overlay.isProfileSnapControlsEnabled()
                : ControlsPreferences.isSnapControlsEnabled(hostActivity);
        button.setText(enabled ? "Snap: ON" : "Snap: OFF");
    }

    private void updateMouseButtonText(Button button) {
        boolean enabled = overlay != null
                ? overlay.isProfileVirtualMouseEnabled()
                : ControlsPreferences.isVirtualMouseEnabled(hostActivity);
        button.setText(enabled ? "Cursor: ON" : "Cursor: OFF");
    }

    private int dp(float value) {
        return (int) (value * hostActivity.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}

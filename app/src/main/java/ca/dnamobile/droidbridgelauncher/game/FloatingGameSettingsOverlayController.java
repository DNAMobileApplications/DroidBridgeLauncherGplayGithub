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

package ca.dnamobile.droidbridgelauncher.game;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.R;
import ca.dnamobile.droidbridgelauncher.settings.GameOverlayPreferences;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.runtime.DroidBridgeSdlFpsBridge;
import org.lwjgl.glfw.CallbackBridge;

public final class FloatingGameSettingsOverlayController {
    private static final String WRAPPER_TAG = "game_settings_floating_wrapper";
    private static final long FPS_POLL_MS = 500L;
    private static final int DEFAULT_MARGIN_DP = 16;
    private static final int WRAPPER_SIZE_DP = 58;
    private static final int BUTTON_SIZE_DP = 48;
    private static final int LARGE_FPS_BUTTON_OVERLAP_DP = BUTTON_SIZE_DP / 4;

    @NonNull private final Context context;
    @NonNull private final ImageButton settingsButton;
    @NonNull private final Handler handler = new Handler(Looper.getMainLooper());
    private final int touchSlop;

    @Nullable private FrameLayout floatingContainer;
    @Nullable private TextView fpsText;
    private boolean attached;
    private boolean suppressed;
    private boolean fpsSuppressed;
    private boolean dragging;
    private boolean capturedPhysicalMouseGestureActive;
    private float downRawX;
    private float downRawY;
    private int startLeft;
    private int startTop;

    private final Runnable fpsTicker = new Runnable() {
        @Override
        public void run() {
            refreshFromPreferences(false);
            handler.postDelayed(this, FPS_POLL_MS);
        }
    };

    public FloatingGameSettingsOverlayController(
            @NonNull Context context,
            @NonNull ImageButton settingsButton
    ) {
        this.context = context;
        this.settingsButton = settingsButton;
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void attach() {
        if (attached) return;
        attached = true;
        ensureWrapped();
        settingsButton.setOnTouchListener(this::onFloatingOverlayTouch);
        refreshFromPreferences(true);
    }

    public void resume() {
        handler.removeCallbacks(fpsTicker);
        refreshFromPreferences(true);
        handler.post(fpsTicker);
    }

    public void pause() {
        handler.removeCallbacks(fpsTicker);
    }

    public void detach() {
        pause();
        settingsButton.setOnTouchListener(null);
        if (fpsText != null) {
            fpsText.setOnTouchListener(null);
        }
        attached = false;
    }

    public void bringToFront() {
        FrameLayout wrapper = ensureWrapped();
        if (wrapper == null) return;
        wrapper.bringToFront();
        wrapper.setElevation(10000f);
        wrapper.setTranslationZ(10000f);
    }

    public void refreshFromPreferences() {
        refreshFromPreferences(true);
    }

    /** The controls display owns this affordance while a dual-screen session is active. */
    public void setSuppressed(boolean suppressed) {
        if (this.suppressed == suppressed) return;
        this.suppressed = suppressed;
        refreshFromPreferences(true);
    }

    /** Allows the dual-screen deck to replace only the Android-font FPS badge with its own
     * Minecraft-font counter while keeping the lower-screen settings button available. */
    public void setFpsSuppressed(boolean suppressed) {
        if (this.fpsSuppressed == suppressed) return;
        this.fpsSuppressed = suppressed;
        refreshFromPreferences(true);
    }

    private void refreshFromPreferences(boolean applyPosition) {
        FrameLayout wrapper = ensureWrapped();
        if (wrapper == null) return;

        boolean showButton = !suppressed && LauncherPreferences.isShowInGameSettingsButton(context);
        boolean showFps = !suppressed && !fpsSuppressed && GameOverlayPreferences.isShowGameFpsCounter(context);
        boolean showAnything = showButton || showFps;

        wrapper.setVisibility(showAnything ? View.VISIBLE : View.GONE);
        settingsButton.setVisibility(showButton ? View.VISIBLE : View.GONE);

        if (applyPosition && showAnything) {
            wrapper.post(this::applySavedPosition);
        }

        updateFpsText(showButton, showFps);
        if (showAnything) bringToFront();
    }

    @Nullable
    private FrameLayout ensureWrapped() {
        if (floatingContainer != null) return floatingContainer;

        ViewGroup parent = settingsButton.getParent() instanceof ViewGroup
                ? (ViewGroup) settingsButton.getParent()
                : null;
        if (parent == null) return null;

        if (parent instanceof FrameLayout && WRAPPER_TAG.equals(parent.getTag())) {
            floatingContainer = (FrameLayout) parent;
            fpsText = findOrCreateFpsText(floatingContainer);
            return floatingContainer;
        }

        ViewGroup.LayoutParams originalParams = settingsButton.getLayoutParams();
        int index = parent.indexOfChild(settingsButton);
        parent.removeView(settingsButton);

        FrameLayout wrapper = new FrameLayout(context);
        wrapper.setTag(WRAPPER_TAG);
        wrapper.setClipChildren(false);
        wrapper.setClipToPadding(false);
        wrapper.setClickable(false);
        wrapper.setFocusable(false);

        ViewGroup.LayoutParams wrapperParams = buildWrapperLayoutParams(originalParams);
        parent.addView(wrapper, Math.max(0, index), wrapperParams);

        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                dpToPx(BUTTON_SIZE_DP),
                dpToPx(BUTTON_SIZE_DP),
                Gravity.CENTER
        );
        settingsButton.setLayoutParams(buttonParams);
        wrapper.addView(settingsButton);

        fpsText = findOrCreateFpsText(wrapper);
        floatingContainer = wrapper;
        return wrapper;
    }

    @NonNull
    private ViewGroup.LayoutParams buildWrapperLayoutParams(@Nullable ViewGroup.LayoutParams originalParams) {
        int size = dpToPx(WRAPPER_SIZE_DP);

        if (originalParams instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams old = (FrameLayout.LayoutParams) originalParams;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    Math.max(size, old.width > 0 ? old.width : size),
                    Math.max(size, old.height > 0 ? old.height : size)
            );
            lp.gravity = old.gravity;
            lp.leftMargin = old.leftMargin;
            lp.topMargin = old.topMargin;
            lp.rightMargin = old.rightMargin;
            lp.bottomMargin = old.bottomMargin;
            return lp;
        }

        return new FrameLayout.LayoutParams(
                size,
                size,
                Gravity.END | Gravity.BOTTOM
        );
    }

    @NonNull
    private TextView findOrCreateFpsText(@NonNull FrameLayout wrapper) {
        View existing = wrapper.findViewWithTag("game_settings_fps_badge");
        if (existing instanceof TextView) {
            TextView text = (TextView) existing;
            text.setClickable(true);
            text.setFocusable(false);
            text.setOnTouchListener(this::onFloatingOverlayTouch);
            return text;
        }

        TextView text = new TextView(context);
        text.setTag("game_settings_fps_badge");
        text.setBackgroundResource(R.drawable.bg_fps_badge);
        text.setGravity(Gravity.CENTER);
        text.setIncludeFontPadding(false);
        text.setMinWidth(dpToPx(42));
        text.setPadding(dpToPx(5), 0, dpToPx(5), 0);
        text.setText("FPS");
        text.setTextColor(0xFFFFFFFF);
        text.setTextSize(8f);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setVisibility(View.GONE);
        text.setClickable(true);
        text.setFocusable(false);
        text.setOnTouchListener(this::onFloatingOverlayTouch);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dpToPx(18),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
        );
        wrapper.addView(text, params);
        return text;
    }

    private void updateFpsText(boolean showButton, boolean showFps) {
        TextView text = fpsText;
        if (text == null) return;

        if (!showFps) {
            text.setVisibility(View.GONE);
            resetButtonOnlyLayout(showButton);
            return;
        }

        applyFpsCounterSize(text, showButton);

        // The cross-VM FPS file is used by every Vulkan presentation path,
        // including Minecraft 26.2 and VulkanMod launches that do not request
        // the newer SDL3 presentation bootstrap. Restricting this read to
        // DroidBridgeSDL3Bootstrap.isRequested() made those valid samples
        // invisible to the launcher overlay. Always prefer a fresh file sample,
        // then fall back to the in-process native OpenGL counter.
        int fps = DroidBridgeSdlFpsBridge.readCurrentFps(context);
        if (fps <= 0) {
            try {
                fps = Math.max(0, CallbackBridge.getCurrentFps());
            } catch (Throwable ignored) {
                fps = 0;
            }
        }

        text.setText(fps > 0 ? fps + " FPS" : "FPS");
        text.setVisibility(View.VISIBLE);
    }

    private void applyFpsCounterSize(@NonNull TextView text, boolean showButton) {
        String size = GameOverlayPreferences.getGameFpsCounterSize(context);
        boolean large = GameOverlayPreferences.FPS_SIZE_LARGE.equals(size);
        int minWidthDp;
        int horizontalPaddingDp;
        int verticalPaddingDp;
        float textSizeSp;

        if (large) {
            minWidthDp = 0;
            horizontalPaddingDp = 10;
            verticalPaddingDp = 4;
            textSizeSp = 16f;
        } else if (GameOverlayPreferences.FPS_SIZE_MEDIUM.equals(size)) {
            minWidthDp = 58;
            horizontalPaddingDp = 8;
            verticalPaddingDp = 3;
            textSizeSp = 12f;
        } else {
            minWidthDp = 42;
            horizontalPaddingDp = 5;
            verticalPaddingDp = 2;
            textSizeSp = 8f;
        }

        text.setSingleLine(true);
        text.setMinWidth(dpToPx(minWidthDp));
        text.setMinimumWidth(dpToPx(minWidthDp));
        text.setPadding(
                dpToPx(horizontalPaddingDp),
                dpToPx(verticalPaddingDp),
                dpToPx(horizontalPaddingDp),
                dpToPx(verticalPaddingDp)
        );
        text.setTextSize(textSizeSp);

        ViewGroup.LayoutParams params = text.getLayoutParams();
        if (params instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) params;
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.topMargin = large && showButton ? dpToPx(BUTTON_SIZE_DP - LARGE_FPS_BUTTON_OVERLAP_DP) : 0;
            lp.gravity = showButton
                    ? Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
                    : Gravity.CENTER;
            text.setLayoutParams(lp);
        }

        applyOverlayChildLayout(showButton, true, large);
    }

    private void resetButtonOnlyLayout(boolean showButton) {
        applyOverlayChildLayout(showButton, false, false);
    }

    private void applyOverlayChildLayout(boolean showButton, boolean showFps, boolean largeFps) {
        FrameLayout wrapper = floatingContainer;
        if (wrapper == null) return;

        ViewGroup.LayoutParams wrapperParams = wrapper.getLayoutParams();
        if (wrapperParams != null) {
            boolean wrapContainer = showFps && (!showButton || largeFps);
            wrapperParams.width = wrapContainer
                    ? ViewGroup.LayoutParams.WRAP_CONTENT
                    : Math.max(dpToPx(WRAPPER_SIZE_DP), wrapperParams.width > 0 ? wrapperParams.width : dpToPx(WRAPPER_SIZE_DP));
            wrapperParams.height = wrapContainer
                    ? ViewGroup.LayoutParams.WRAP_CONTENT
                    : Math.max(dpToPx(WRAPPER_SIZE_DP), wrapperParams.height > 0 ? wrapperParams.height : dpToPx(WRAPPER_SIZE_DP));
            wrapper.setLayoutParams(wrapperParams);
        }

        ViewGroup.LayoutParams buttonParams = settingsButton.getLayoutParams();
        if (buttonParams instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) buttonParams;
            lp.width = dpToPx(BUTTON_SIZE_DP);
            lp.height = dpToPx(BUTTON_SIZE_DP);
            lp.gravity = showFps && largeFps && showButton
                    ? Gravity.TOP | Gravity.CENTER_HORIZONTAL
                    : Gravity.CENTER;
            lp.leftMargin = 0;
            lp.topMargin = 0;
            lp.rightMargin = 0;
            lp.bottomMargin = 0;
            settingsButton.setLayoutParams(lp);
            if (showFps && largeFps && showButton && fpsText != null) {
                fpsText.bringToFront();
            }
        }

        wrapper.requestLayout();
    }

    /**
     * Feeds the centered Android Virtual Mouse cursor into the same floating-Cog drag
     * behavior used by normal touch. The coordinates are absolute screen coordinates
     * because the physical pointer is captured and the real Android pointer is hidden.
     * Returning true means the gesture belongs to the floating Cog and must not also
     * be delivered to Minecraft.
     */
    public boolean dispatchCapturedPhysicalMouseGesture(
            int action,
            float screenX,
            float screenY
    ) {
        FrameLayout wrapper = ensureWrapped();
        if (wrapper == null || wrapper.getVisibility() != View.VISIBLE
                || settingsButton.getVisibility() != View.VISIBLE || !settingsButton.isShown()) {
            capturedPhysicalMouseGestureActive = false;
            return false;
        }

        if (action == MotionEvent.ACTION_DOWN) {
            if (!isScreenPointInsideView(settingsButton, screenX, screenY)) return false;

            capturedPhysicalMouseGestureActive = true;
            dragging = false;
            downRawX = screenX;
            downRawY = screenY;
            startLeft = wrapper.getLeft();
            startTop = wrapper.getTop();
            settingsButton.setPressed(true);
            if (settingsButton.getParent() != null) {
                settingsButton.getParent().requestDisallowInterceptTouchEvent(true);
            }
            return true;
        }

        if (!capturedPhysicalMouseGestureActive) return false;

        switch (action) {
            case MotionEvent.ACTION_MOVE: {
                float dx = screenX - downRawX;
                float dy = screenY - downRawY;
                if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                    dragging = true;
                    forceAbsolutePosition(startLeft, startTop);
                }
                if (dragging) {
                    settingsButton.setPressed(false);
                    moveTo(Math.round(startLeft + dx), Math.round(startTop + dy), false);
                } else {
                    settingsButton.setPressed(isScreenPointInsideView(settingsButton, screenX, screenY));
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean wasDragging = dragging;
                boolean click = action == MotionEvent.ACTION_UP
                        && !wasDragging
                        && isScreenPointInsideView(settingsButton, screenX, screenY);

                capturedPhysicalMouseGestureActive = false;
                dragging = false;
                settingsButton.setPressed(false);
                if (settingsButton.getParent() != null) {
                    settingsButton.getParent().requestDisallowInterceptTouchEvent(false);
                }

                if (wasDragging) {
                    saveCurrentPosition();
                } else if (click) {
                    settingsButton.performClick();
                }
                return true;
            }

            default:
                return true;
        }
    }

    private static boolean isScreenPointInsideView(
            @NonNull View view,
            float screenX,
            float screenY
    ) {
        if (view.getVisibility() != View.VISIBLE || !view.isShown()
                || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return false;
        }
        int[] location = new int[2];
        try {
            view.getLocationOnScreen(location);
        } catch (Throwable ignored) {
            return false;
        }
        return screenX >= location[0]
                && screenX < location[0] + view.getWidth()
                && screenY >= location[1]
                && screenY < location[1] + view.getHeight();
    }

    private boolean onFloatingOverlayTouch(View view, MotionEvent event) {
        FrameLayout wrapper = ensureWrapped();
        if (wrapper == null) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = false;
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                startLeft = wrapper.getLeft();
                startTop = wrapper.getTop();
                if (view.getParent() != null) {
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                    dragging = true;
                    forceAbsolutePosition(startLeft, startTop);
                }
                if (dragging) {
                    moveTo(Math.round(startLeft + dx), Math.round(startTop + dy), false);
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (view.getParent() != null) {
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                }
                if (dragging) {
                    saveCurrentPosition();
                    dragging = false;
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP && view == settingsButton) {
                    view.performClick();
                }
                return true;

            default:
                return true;
        }
    }

    private void applySavedPosition() {
        FrameLayout wrapper = ensureWrapped();
        ViewGroup parent = parentViewGroup();
        if (wrapper == null || parent == null) return;

        if (GameOverlayPreferences.hasCustomGameSettingsButtonPosition(context)) {
            int left = dpToPx(GameOverlayPreferences.getGameSettingsButtonCustomLeftDp(context));
            int top = dpToPx(GameOverlayPreferences.getGameSettingsButtonCustomTopDp(context));
            moveTo(left, top, false);
            return;
        }

        FrameLayout.LayoutParams lp = ensureFrameLayoutParams(wrapper);
        lp.gravity = gravityForPlacement(GameOverlayPreferences.getGameSettingsButtonPlacement(context));
        int margin = dpToPx(DEFAULT_MARGIN_DP);
        lp.leftMargin = margin;
        lp.topMargin = margin;
        lp.rightMargin = margin;
        lp.bottomMargin = margin;
        wrapper.setTranslationX(0f);
        wrapper.setTranslationY(0f);
        wrapper.setLayoutParams(lp);
    }

    private void forceAbsolutePosition(int left, int top) {
        FrameLayout wrapper = ensureWrapped();
        if (wrapper == null) return;
        FrameLayout.LayoutParams lp = ensureFrameLayoutParams(wrapper);
        lp.gravity = Gravity.START | Gravity.TOP;
        lp.rightMargin = 0;
        lp.bottomMargin = 0;
        wrapper.setLayoutParams(lp);
        moveTo(left, top, false);
    }

    private void moveTo(int requestedLeft, int requestedTop, boolean save) {
        FrameLayout wrapper = ensureWrapped();
        ViewGroup parent = parentViewGroup();
        if (wrapper == null || parent == null) return;

        int maxLeft = Math.max(0, parent.getWidth() - wrapper.getWidth());
        int maxTop = Math.max(0, parent.getHeight() - wrapper.getHeight());
        int left = clamp(requestedLeft, 0, maxLeft);
        int top = clamp(requestedTop, 0, maxTop);

        FrameLayout.LayoutParams lp = ensureFrameLayoutParams(wrapper);
        lp.gravity = Gravity.START | Gravity.TOP;
        lp.leftMargin = left;
        lp.topMargin = top;
        lp.rightMargin = 0;
        lp.bottomMargin = 0;
        wrapper.setTranslationX(0f);
        wrapper.setTranslationY(0f);
        wrapper.setLayoutParams(lp);

        if (save) {
            GameOverlayPreferences.setGameSettingsButtonCustomPosition(context, pxToDp(left), pxToDp(top));
        }
    }

    private void saveCurrentPosition() {
        FrameLayout wrapper = ensureWrapped();
        if (wrapper == null) return;
        moveTo(wrapper.getLeft(), wrapper.getTop(), true);
    }

    @Nullable
    private ViewGroup parentViewGroup() {
        FrameLayout wrapper = floatingContainer;
        return wrapper != null && wrapper.getParent() instanceof ViewGroup
                ? (ViewGroup) wrapper.getParent()
                : null;
    }

    @NonNull
    private FrameLayout.LayoutParams ensureFrameLayoutParams(@NonNull FrameLayout wrapper) {
        ViewGroup.LayoutParams current = wrapper.getLayoutParams();
        if (current instanceof FrameLayout.LayoutParams) {
            return (FrameLayout.LayoutParams) current;
        }
        return new FrameLayout.LayoutParams(current.width, current.height);
    }

    private static int gravityForPlacement(@Nullable String placement) {
        if (GameOverlayPreferences.PLACEMENT_TOP_LEFT.equals(placement)) {
            return Gravity.START | Gravity.TOP;
        }
        if (GameOverlayPreferences.PLACEMENT_TOP_RIGHT.equals(placement)) {
            return Gravity.END | Gravity.TOP;
        }
        if (GameOverlayPreferences.PLACEMENT_BOTTOM_LEFT.equals(placement)) {
            return Gravity.START | Gravity.BOTTOM;
        }
        return Gravity.END | Gravity.BOTTOM;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    private int pxToDp(int px) {
        return Math.round(px / context.getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

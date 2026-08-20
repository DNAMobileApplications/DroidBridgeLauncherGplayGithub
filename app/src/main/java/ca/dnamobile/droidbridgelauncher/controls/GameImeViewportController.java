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

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;

import java.lang.ref.WeakReference;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.runtime.MinecraftGLSurface;

/**
 * Keeps Minecraft's GLFW viewport above Android's IME.
 *
 * the legacy implementation's useful behavior is not a visible Android dialog. It uses a real Android
 * EditText only as the IME anchor, while the game surface/window is allowed to
 * resize so Minecraft's own chat/edit widgets render above the keyboard.
 *
 * DroidBridge is immersive/fullscreen, so Android may not physically resize the
 * Activity for us. This controller mirrors the visible-window bottom inset into
 * MinecraftGLSurface; the surface then shrinks its child render view and sends a
 * smaller GLFW window height to Minecraft. That makes the in-game chat line sit
 * directly above the Android keyboard instead of behind it.
 */
public final class GameImeViewportController {
    @Nullable private static GameImeViewportController active;
    @Nullable private static WeakReference<MinecraftGLSurface> registeredMinecraftSurface;

    @NonNull private final View anchor;
    @NonNull private final View root;
    @Nullable private final MinecraftGLSurface minecraftSurface;
    @NonNull private final Rect visibleFrame = new Rect();

    private int lastBottomInset = -1;
    private boolean detached;
    private boolean fallbackInsetAllowed;
    private boolean sawRealKeyboardInset;

    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::updateImeInset;

    private GameImeViewportController(@NonNull View anchor, @NonNull View root, @Nullable MinecraftGLSurface minecraftSurface) {
        this.anchor = anchor;
        this.root = root;
        this.minecraftSurface = minecraftSurface;
    }

    /**
     * Register the real Minecraft surface from GameActivity.
     *
     * This is important because the Input touch button may live inside a controls
     * overlay root that is not a direct sibling of the game surface. Searching
     * source.getRootView() alone can therefore miss the surface and the IME resize
     * path becomes a no-op.
     */
    public static void registerMinecraftSurface(@Nullable MinecraftGLSurface surface) {
        registeredMinecraftSurface = surface == null ? null : new WeakReference<>(surface);
    }

    public static void attach(@NonNull View imeAnchor, @NonNull View searchRoot) {
        detachActive(false);

        View root = bestRootFor(searchRoot);
        MinecraftGLSurface surface = findMinecraftSurface(root);
        if (surface == null) {
            surface = getRegisteredMinecraftSurface();
            if (surface != null) {
                root = bestRootFor(surface);
            }
        }
        GameImeViewportController controller = new GameImeViewportController(imeAnchor, root, surface);
        active = controller;
        controller.attachInternal();
    }

    public static void detachActive(boolean keyboardClosing) {
        GameImeViewportController controller = active;
        active = null;
        if (controller != null) {
            controller.detachInternal(keyboardClosing);
        }
    }

    public static void clearImeViewportInset(@NonNull View searchRoot) {
        MinecraftGLSurface surface = findMinecraftSurface(bestRootFor(searchRoot));
        if (surface == null) {
            surface = getRegisteredMinecraftSurface();
        }
        if (surface != null) {
            surface.setImeViewportBottomInset(0);
        }
    }

    private void attachInternal() {
        try {
            root.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        } catch (Throwable ignored) {
        }
        root.post(this::updateImeInset);
        root.postDelayed(this::updateImeInset, 80L);
        root.postDelayed(() -> {
            fallbackInsetAllowed = true;
            updateImeInset();
        }, 160L);
        root.postDelayed(this::updateImeInset, 260L);
        root.postDelayed(this::updateImeInset, 520L);
        root.postDelayed(this::updateImeInset, 1000L);
    }

    private void detachInternal(boolean keyboardClosing) {
        if (detached) return;
        detached = true;
        try {
            root.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
        } catch (Throwable ignored) {
        }
        if (minecraftSurface != null) {
            minecraftSurface.setImeViewportBottomInset(0);
        }
        if (!keyboardClosing) {
            clearImeViewportInset(anchor);
        }
    }

    private void updateImeInset() {
        if (detached) return;
        int bottomInset = calculateKeyboardBottomInset(root);

        if (bottomInset > 0) {
            sawRealKeyboardInset = true;
        } else if (sawRealKeyboardInset) {
            // The Android IME was visible and then disappeared through the keyboard
            // minimize button or another system path that does not deliver Back to
            // the hidden EditText. Close DroidBridge's IME bridge too; otherwise the
            // old fallback inset keeps the Minecraft surface stuck halfway up until
            // Enter is pressed.
            applyBottomInset(0);
            root.post(TouchKeyboardHelper::notifyImeHiddenBySystem);
            return;
        }

        // In immersive fullscreen, some Android builds report zero visible-frame and
        // WindowInsets IME height even though the keyboard is plainly overlaying the
        // app. legacy Android launcher/legacy dynamic-layout behavior still needs the game viewport to shrink, so
        // after the IME has had a moment to open, use a conservative landscape-phone
        // fallback until a real inset appears. Once a real inset has appeared, never
        // fall back again because zero then means the keyboard really closed.
        if (bottomInset <= 0 && fallbackInsetAllowed && !sawRealKeyboardInset) {
            bottomInset = estimateKeyboardBottomInset(root);
        }

        applyBottomInset(bottomInset);
    }

    private void applyBottomInset(int bottomInset) {
        if (bottomInset == lastBottomInset) return;
        lastBottomInset = bottomInset;
        MinecraftGLSurface surface = minecraftSurface != null ? minecraftSurface : getRegisteredMinecraftSurface();
        if (surface != null) {
            surface.setImeViewportBottomInset(bottomInset);
        }
    }

    private static int calculateKeyboardBottomInset(@NonNull View root) {
        int minKeyboard = minKeyboardPx(root);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                WindowInsets insets = root.getRootWindowInsets();
                if (insets != null) {
                    int imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
                    boolean imeVisible = insets.isVisible(WindowInsets.Type.ime());
                    if (imeBottom >= minKeyboard || imeVisible) {
                        return clampKeyboardInset(root, imeBottom);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            Rect rect = new Rect();
            root.getWindowVisibleDisplayFrame(rect);

            int[] location = new int[2];
            root.getLocationOnScreen(location);
            int rootBottomOnScreen = location[1] + root.getHeight();
            int inset = rootBottomOnScreen - rect.bottom;

            // Filter out small navigation/cutout differences. The keyboard is always
            // much taller than this threshold, while gesture-nav/system-bar drift is not.
            if (inset < minKeyboard) return 0;
            return clampKeyboardInset(root, inset);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int estimateKeyboardBottomInset(@NonNull View root) {
        int height = Math.max(1, root.getHeight());
        int minKeyboard = minKeyboardPx(root);

        // Landscape Android keyboards commonly cover roughly 45-55% of the screen.
        // Use this only as an immersive-fullscreen fallback when Android refuses to
        // report the real IME inset. Keep at least a small playable viewport visible.
        int estimated = Math.max(minKeyboard, Math.round(height * 0.48f));
        return clampKeyboardInset(root, estimated);
    }

    private static int minKeyboardPx(@NonNull View root) {
        return Math.max(80, Math.round(root.getResources().getDisplayMetrics().density * 96f));
    }

    private static int clampKeyboardInset(@NonNull View root, int inset) {
        int height = Math.max(1, root.getHeight());
        int minVisible = Math.max(120, Math.round(root.getResources().getDisplayMetrics().density * 120f));
        return Math.max(0, Math.min(inset, Math.max(1, height - minVisible)));
    }

    @NonNull
    private static View bestRootFor(@NonNull View view) {
        View root = view.getRootView();
        if (root != null) return root;

        Activity activity = findActivity(view.getContext());
        if (activity != null) {
            View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
            if (decor != null) return decor;
        }

        return view;
    }

    @Nullable
    private static Activity findActivity(@Nullable Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    @Nullable
    private static MinecraftGLSurface getRegisteredMinecraftSurface() {
        WeakReference<MinecraftGLSurface> ref = registeredMinecraftSurface;
        return ref == null ? null : ref.get();
    }

    @Nullable
    private static MinecraftGLSurface findMinecraftSurface(@Nullable View view) {
        if (view == null) return null;
        if (view instanceof MinecraftGLSurface) return (MinecraftGLSurface) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                MinecraftGLSurface found = findMinecraftSurface(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}

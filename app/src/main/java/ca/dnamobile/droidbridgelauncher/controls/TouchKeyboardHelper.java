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
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.lwjgl.glfw.CallbackBridge;

import java.lang.reflect.Method;
import java.lang.ref.WeakReference;

import ca.dnamobile.droidbridgelauncher.GameActivity;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.runtime.MinecraftGLSurface;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;

/**
 * Android IME bridge for Minecraft's LWJGL text input.
 *
 * Minecraft text boxes are not Android EditText widgets. This helper therefore
 * creates a tiny invisible Android EditText only to own the Android IME, then
 * forwards committed text/backspace/Done into Minecraft through the existing
 * GLFW bridge. There is no visible DroidBridge text dialog anymore; Minecraft's
 * own chat/input field stays visible because GameImeViewportController visually
 * pushes the Minecraft surface above the Android keyboard without resizing GLFW.
 */
public final class TouchKeyboardHelper {
    private static final String TAG = "TouchKeyboardHelper";

    private static final int GLFW_PRESS_KEY_ENTER = 257;
    private static final int GLFW_PRESS_KEY_BACKSPACE = 259;
    private static final int GLFW_PRESS_KEY_ESCAPE = 256;
    private static final int DEFAULT_CLEAR_BACKSPACES = 64;
    private static final long CHAT_KEYBOARD_MODE_GRACE_MS = 10_000L;
    // Android does not finish hiding the IME synchronously. On the Thor/Gboard path
    // the hide request -> onHidden transition is roughly 200-250 ms. Removing the
    // editor and resetting MinecraftGLSurface in that same frame can leave Android
    // compositing a stale IME/focus layer over the game until the next ESC/menu focus
    // cycle. Keep the editor/surface relationship stable until the IME animation ends.
    private static final long IME_SUBMIT_TEARDOWN_DELAY_MS = 320L;
    static final String DEFAULT_WORLD_NAME_TEXT = "New World";

    @Nullable private static NativeKeyboardInputView activeInput;
    @Nullable private static WeakReference<View> dualScreenImeHost;
    @Nullable private static WeakReference<View> dualScreenImeSessionHost;
    @Nullable private static Runnable dualScreenImeBeforeShow;
    @Nullable private static Runnable dualScreenImeAfterHide;
    @Nullable private static WeakReference<MinecraftGLSurface> guardedMinecraftSurface;
    private static boolean guardedMinecraftSurfaceFocusable;
    private static boolean guardedMinecraftSurfaceFocusableInTouchMode;
    @Nullable private static WeakReference<View> postImeFocusSink;
    private static long lastChatKeyPressUptimeMs;
    private static boolean chatImeSessionActive;

    private TouchKeyboardHelper() {
    }

    /**
     * Registers the Android view that owns DroidBridge's dual-screen HUD/touch deck.
     * When present, every explicit IME request is anchored to this view's display instead
     * of the Minecraft display. The optional callbacks let a Presentation temporarily
     * become focusable while Android's IME is visible, then return controller/key focus
     * to Minecraft after the keyboard closes.
     */
    public static void registerDualScreenImeHost(
            @NonNull View host,
            @Nullable Runnable beforeShow,
            @Nullable Runnable afterHide
    ) {
        dualScreenImeHost = new WeakReference<>(host);
        dualScreenImeBeforeShow = beforeShow;
        dualScreenImeAfterHide = afterHide;
        Logging.i(TAG, "Dual-screen IME host registered displayId=" + displayId(host));
    }

    public static void registerDualScreenImeHost(@NonNull View host) {
        WeakReference<View> reference = dualScreenImeHost;
        View registered = reference == null ? null : reference.get();
        // Presentation registration can happen before the view is physically attached.
        // Do not let DualScreenDeckView.onAttachedToWindow replace those focus callbacks
        // with the generic local-display registration for the exact same host.
        if (registered == host
                && (dualScreenImeBeforeShow != null || dualScreenImeAfterHide != null)) {
            return;
        }
        registerDualScreenImeHost(host, null, null);
    }

    public static void unregisterDualScreenImeHost(@NonNull View host) {
        WeakReference<View> reference = dualScreenImeHost;
        View registered = reference == null ? null : reference.get();
        if (registered != host) return;

        NativeKeyboardInputView input = activeInput;
        if (input != null && input.isDualScreenSession()) {
            hideKeyboard(false);
        }
        dualScreenImeHost = null;
        dualScreenImeSessionHost = null;
        dualScreenImeBeforeShow = null;
        dualScreenImeAfterHide = null;
        Logging.i(TAG, "Dual-screen IME host unregistered displayId=" + displayId(host));
    }

    @Nullable
    private static View getDualScreenImeHost() {
        WeakReference<View> reference = dualScreenImeHost;
        View host = reference == null ? null : reference.get();
        if (host == null || !host.isAttachedToWindow()) return null;
        return host;
    }

    /**
     * Sets the temporary focusable window used only to own Android's IME on a secondary
     * display. The persistent controls Presentation must stay NOT_FOCUSABLE on Thor or it
     * can steal Activity/gamepad focus until the user cycles through Recents.
     */
    public static void setDualScreenImeSessionHost(@Nullable View host) {
        dualScreenImeSessionHost = host == null ? null : new WeakReference<>(host);
        if (host != null) {
            Logging.i(TAG, "Dual-screen transient IME host ready displayId=" + displayId(host));
        }
    }

    @Nullable
    private static View getDualScreenImeTargetHost() {
        WeakReference<View> sessionReference = dualScreenImeSessionHost;
        View sessionHost = sessionReference == null ? null : sessionReference.get();
        if (sessionHost != null) return sessionHost;
        return getDualScreenImeHost();
    }

    private static int displayId(@NonNull View view) {
        try {
            return view.getDisplay() == null ? -1 : view.getDisplay().getDisplayId();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public static void showKeyboard(@NonNull View source) {
        boolean dualScreenIme = getDualScreenImeHost() != null;

        if (dualScreenIme && CallbackBridge.isGrabbing()) {
            // In dual-screen play the explicit IME button is the complete chat action:
            // open Minecraft chat, then put Android's keyboard on the HUD/touch display.
            // There is no reason to require a separate T binding because the keyboard
            // no longer covers or resizes the Minecraft display.
            markChatKeyPressed();
            sendKeyTap(84); // GLFW_KEY_T
            source.postDelayed(() -> showNativeKeyboard(source, true, false), 90L);
            return;
        }

        if (CallbackBridge.isGrabbing()) {
            /*
             * Keep the existing single-screen safety behavior. Standalone Input only
             * opens after Minecraft chat has actually been requested, because the same
             * display would otherwise be shifted for an input field that does not exist.
             */
            if (!shouldUseChatImeMode()) {
                GameImeViewportController.detachActive(true);
                GameImeViewportController.clearImeViewportInset(source);
                Logging.i(TAG, "Android keyboard request ignored: Minecraft chat is not open/requested.");
                return;
            }

            source.postDelayed(() -> showNativeKeyboard(source, true, true), 90L);
            return;
        }

        // In dual-screen mode Android's IME belongs to the controls display, so never
        // apply the single-screen Minecraft viewport push. Text and Enter are still
        // forwarded through the same GLFW bridge.
        boolean chatMode = shouldUseChatImeMode();
        showNativeKeyboard(source, chatMode, !dualScreenIme && chatMode);
    }

    public static void showChatKeyboard(@NonNull View source) {
        // Explicit launcher keyboard buttons are intended for Minecraft chat. Do not
        // depend on CallbackBridge.isGrabbing() here, because opening chat releases
        // mouse grab before the user presses the Android keyboard button.
        boolean dualScreenIme = getDualScreenImeHost() != null;
        if (CallbackBridge.isGrabbing()) {
            markChatKeyPressed();
            sendKeyTap(84); // GLFW_KEY_T
            source.postDelayed(() -> showNativeKeyboard(source, true, !dualScreenIme), 90L);
            return;
        }

        boolean chatMode = shouldUseChatImeMode();
        showNativeKeyboard(source, chatMode, !dualScreenIme && chatMode);
    }

    public static void showMenuTextKeyboard(@NonNull View source) {
        // Usually menu text boxes use Done, but when the focused Minecraft text box
        // belongs to ChatScreen we must keep Android Enter as Minecraft Enter so
        // commands/messages submit instead of only closing the IME. Only ChatScreen
        // gets the viewport-push viewport push; normal menus/world-name screens stay
        // fullscreen so the menu layout is not shifted or broken.
        boolean chatMode = shouldUseChatImeMode();
        showNativeKeyboard(source, chatMode, getDualScreenImeHost() == null && chatMode);
    }

    public static void showWorldNameKeyboard(@NonNull View source) {
        // World-name editing is a Minecraft-side text field. Do not seed a fake
        // "New World" buffer; Android only owns the IME and Minecraft owns the field.
        showNativeKeyboard(source, false, false);
    }

    public static void markChatKeyPressed() {
        // T and / only mean "chat will open" while Minecraft is in grabbed
        // gameplay mode. In menus, T is just normal text/key input. Keep this
        // state alive while the chat box is likely still open, even if Android's
        // IME is minimized. That lets Input reopen the keyboard and reapply the
        // viewport-push viewport push without requiring the user to press T again.
        if (!CallbackBridge.isGrabbing()) return;
        chatImeSessionActive = true;
        lastChatKeyPressUptimeMs = SystemClock.uptimeMillis();
    }

    private static void clearChatImeState() {
        chatImeSessionActive = false;
        lastChatKeyPressUptimeMs = 0L;
    }

    private static boolean shouldSubmitWithEnterByDefault() {
        if (chatImeSessionActive) return true;
        long ageMs = SystemClock.uptimeMillis() - lastChatKeyPressUptimeMs;
        return ageMs >= 0L && ageMs <= CHAT_KEYBOARD_MODE_GRACE_MS;
    }

    private static boolean shouldUseChatImeMode() {
        // ChatScreen reflection is not reliable on every Minecraft version/mapping.
        // The reliable signal for the launcher Input button is the grabbed-mode
        // T/open-chat request. Keep chatImeSessionActive after Android IME minimize
        // because Minecraft chat can remain open while the Android keyboard is gone.
        return MinecraftTextInputKeyboardTrigger.isMinecraftChatScreenOpen()
                || chatImeSessionActive
                || shouldSubmitWithEnterByDefault();
    }

    static void showKeyboard(@NonNull View source, boolean submitSendsEnter) {
        showNativeKeyboard(source, submitSendsEnter, submitSendsEnter);
    }

    private static void showNativeKeyboard(@NonNull View source, boolean submitSendsEnter, boolean pushMinecraftViewport) {
        hideKeyboard(false);

        View registeredDualHost = getDualScreenImeHost();
        boolean dualScreenSession = registeredDualHost != null;
        Runnable beforeShow = dualScreenSession ? dualScreenImeBeforeShow : null;
        Runnable afterHide = dualScreenSession ? dualScreenImeAfterHide : null;

        // A Presentation-backed dual-screen session prepares a short-lived, transparent,
        // focusable IME window here. Re-resolve the target after beforeShow: using the
        // persistent controls Presentation itself as the editor owner is what stranded
        // Android window focus on Thor and killed both touch/gamepad input after closing.
        if (beforeShow != null) {
            try {
                beforeShow.run();
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to prepare dual-screen IME window", throwable);
            }
        }

        View dualTarget = dualScreenSession ? getDualScreenImeTargetHost() : null;
        View root = dualTarget != null ? dualTarget : (dualScreenSession ? registeredDualHost : source.getRootView());
        if (root == null) root = source;

        FrameLayout host = findFrameLayout(root);
        if (host == null) {
            Logging.i(TAG, "Android keyboard host unavailable displayId=" + displayId(root));
            if (dualScreenSession && afterHide != null) {
                try {
                    afterHide.run();
                } catch (Throwable ignored) {
                }
            }
            return;
        }

        // A dual-screen keyboard must never resize/push the Minecraft display. The
        // keyboard occupies the HUD/touch display and Minecraft keeps its full viewport.
        boolean effectiveViewportPush = pushMinecraftViewport
                && !dualScreenSession
                && LauncherPreferences.isImeViewportPushEnabled(host.getContext());
        NativeKeyboardInputView inputView = new NativeKeyboardInputView(
                host.getContext(),
                source,
                submitSendsEnter,
                effectiveViewportPush,
                dualScreenSession,
                afterHide
        );
        activeInput = inputView;

        if (effectiveViewportPush) {
            chatImeSessionActive = true;
            GameImeViewportController.attach(inputView, source);
        } else {
            GameImeViewportController.detachActive(true);
            GameImeViewportController.clearImeViewportInset(source);
        }

        // Keep the IME owner physically inside the controls display. Android 13 can defer
        // showSoftInput() for an editor whose entire bounds are outside the visible window.
        // The old -8dp/-8dp position produced a fully off-screen 2x2 view on Thor and
        // matched the several-second delay seen in InputMethodManager logs.
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                Math.max(1, dp(host.getContext(), 1f)),
                Math.max(1, dp(host.getContext(), 1f))
        );
        params.leftMargin = 0;
        params.topMargin = 0;
        host.addView(inputView, params);

        // While Minecraft chat owns an Android IME session, the hidden EditText must be
        // the Android View focus owner. MinecraftGLSurface's normal touch path calls
        // requestFocus() before delivering the click; that is correct for normal menus
        // but steals the editor focus from chat and can blank/pause the live renderer on
        // some devices. Temporarily make only the Minecraft surface non-focusable. The
        // editor is already attached here, so focus transfers directly instead of passing
        // through a transient no-focus state. GLFW input remains enabled and every touch
        // is still delivered to Minecraft.
        guardMinecraftSurfaceFocus(source);
        disableAndroidFocusHighlightOnMinecraftTarget(source);

        Logging.i(TAG, "Opening Android IME on displayId=" + displayId(host)
                + " dualScreen=" + dualScreenSession
                + " submitEnter=" + submitSendsEnter);
        inputView.openKeyboard();
    }

    public static void hideKeyboard(boolean clearText) {
        // MinecraftGLSurface intentionally calls hideKeyboard(false) after a menu tap
        // while the viewport is shifted. A chat/command suggestion is still a Minecraft
        // text interaction, so that tap must not dismiss Android's keyboard. The
        // NativeKeyboardInputView's submit flag is authoritative here; stack origin keeps
        // Android Back/minimize and explicit keyboard replacement on the normal hide path.
        NativeKeyboardInputView current = activeInput;
        if (!clearText
                && current != null
                && current.submitsEnter()
                && isMinecraftSurfaceTapHideRequest()) {
            current.preserveAfterMinecraftSurfaceTap();
            Logging.i(TAG, "Preserved Android IME after Minecraft chat/command suggestion tap.");
            return;
        }

        // clearText=true is used for submit/finished text entry, so chat is done.
        // clearText=false is used for IME hide/minimize/back where Minecraft chat
        // may still be open. Preserve chatImeSessionActive in that case so pressing
        // Input again reopens the Android keyboard and pushes the GLSurface back up.
        if (clearText) {
            clearChatImeState();
        }
        NativeKeyboardInputView input = activeInput;
        if (input == null) {
            restoreMinecraftSurfaceFocus();
            GameImeViewportController.detachActive(true);
            return;
        }

        if (clearText) {
            // Keep activeInput pointing at the closing editor until Android's IME has
            // actually finished its hide animation. This keeps touch-coordinate/IME
            // state truthful during the transition and prevents another code path from
            // treating the still-visible keyboard as already gone. finishClose() clears
            // this reference only if it still belongs to this editor.
            input.close(true);
            return;
        }

        activeInput = null;
        input.close(false);
    }

    public static void cancelMinecraftTextInputFromGame(@Nullable View source) {
        clearChatImeState();
        NativeKeyboardInputView input = activeInput;
        if (input != null) {
            activeInput = null;
            input.close(false);
            return;
        }
        if (source != null) {
            focusPostImeSink(source);
        }
        restoreMinecraftSurfaceFocus();
        GameImeViewportController.detachActive(true);
        if (source != null) {
            GameImeViewportController.clearImeViewportInset(source);
            restoreMinecraftAfterImeClose(source, "cancel");
        }
    }

    static void notifyImeHiddenBySystem() {
        // Android keyboard minimize hides only the system IME. Minecraft chat can
        // remain open. Do not clear chatImeSessionActive here; otherwise the next
        // Input press opens a plain hidden EditText and does not push the viewport.
        NativeKeyboardInputView input = activeInput;
        if (input == null) {
            restoreMinecraftSurfaceFocus();
            GameImeViewportController.detachActive(true);
            return;
        }
        activeInput = null;
        input.closeFromSystemImeHidden();
    }

    public static boolean isKeyboardShowing() {
        return activeInput != null;
    }

    static boolean isChatKeyboardShowing() {
        NativeKeyboardInputView input = activeInput;
        return input != null && input.submitsEnter();
    }

    static void notifyMinecraftTextChangedExternally() {
        NativeKeyboardInputView input = activeInput;
        if (input != null) {
            input.rebaseAfterExternalMinecraftEdit();
        }
    }

    private static boolean isMinecraftSurfaceTapHideRequest() {
        try {
            StackTraceElement[] trace = Thread.currentThread().getStackTrace();
            if (trace == null) return false;
            for (StackTraceElement element : trace) {
                if (element == null) continue;
                String className = element.getClassName();
                String methodName = element.getMethodName();
                if ("ca.dnamobile.droidbridgelauncher.runtime.MinecraftGLSurface".equals(className)
                        && methodName != null
                        && methodName.contains("closeAndroidKeyboardAfterMinecraftTapIfNeeded")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void guardMinecraftSurfaceFocus(@NonNull View source) {
        restoreMinecraftSurfaceFocus();

        MinecraftGLSurface surface = findMinecraftSurface(source);
        if (surface == null) {
            Activity activity = findActivity(source.getContext());
            if (activity != null && activity.getWindow() != null) {
                View decor = activity.getWindow().getDecorView();
                surface = findMinecraftSurface(decor);
            }
        }
        if (surface == null) return;

        guardedMinecraftSurfaceFocusable = surface.isFocusable();
        guardedMinecraftSurfaceFocusableInTouchMode = surface.isFocusableInTouchMode();
        guardedMinecraftSurface = new WeakReference<>(surface);

        surface.setFocusable(false);
        surface.setFocusableInTouchMode(false);
        try {
            CallbackBridge.setInputReady(true);
            CallbackBridge.ensureInputFocus();
        } catch (Throwable ignored) {
        }

        Logging.i(TAG, "Guarding MinecraftGLSurface Android View focus while IME is active.");
    }

    private static void restoreMinecraftSurfaceFocus() {
        WeakReference<MinecraftGLSurface> reference = guardedMinecraftSurface;
        MinecraftGLSurface surface = reference == null ? null : reference.get();
        guardedMinecraftSurface = null;
        if (surface == null) return;

        try {
            surface.setFocusable(guardedMinecraftSurfaceFocusable);
            surface.setFocusableInTouchMode(guardedMinecraftSurfaceFocusableInTouchMode);
        } catch (Throwable ignored) {
        }
    }

    @Nullable
    private static MinecraftGLSurface findMinecraftSurface(@Nullable View view) {
        if (view == null) return null;
        if (view instanceof MinecraftGLSurface) {
            return (MinecraftGLSurface) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                MinecraftGLSurface found = findMinecraftSurface(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    @Nullable
    private static Activity findActivity(@Nullable Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) return (Activity) current;
            Context next = ((ContextWrapper) current).getBaseContext();
            if (next == current) break;
            current = next;
        }
        return current instanceof Activity ? (Activity) current : null;
    }

    /**
     * Keeps Android View focus away from the full-screen MinecraftGLSurface while an
     * IME editor is being removed.
     *
     * Minecraft/GLFW input focus is NOT Android View focus. The launcher only needs a
     * harmless Android focus owner so WindowManager does not automatically focus the
     * full-screen game container during IME teardown. A transparent 1x1 plain View is
     * intentionally kept attached for the rest of the Activity lifetime. It owns no IME,
     * draws nothing, receives no clicks, and the next real Minecraft touch can take View
     * focus normally through MinecraftGLSurface.prepareTouchInputFocus().
     */
    private static void focusPostImeSink(@NonNull View returnFocusTarget) {
        View root = returnFocusTarget.getRootView();
        if (root == null) root = returnFocusTarget;

        FrameLayout host = findFrameLayout(root);
        if (host == null) {
            // The normal GameActivity hierarchy is FrameLayout based. If an unusual
            // compatibility layout is encountered, simply leave native GLFW focus to
            // restoreMinecraftAfterImeClose() rather than refocusing the game surface.
            Logging.i(TAG, "Post-IME focus sink host unavailable.");
            return;
        }

        View sink = postImeFocusSink == null ? null : postImeFocusSink.get();
        if (sink == null || sink.getParent() != host) {
            if (sink != null && sink.getParent() instanceof ViewGroup) {
                try {
                    ((ViewGroup) sink.getParent()).removeView(sink);
                } catch (Throwable ignored) {
                }
            }

            sink = new View(host.getContext());
            sink.setFocusable(true);
            sink.setFocusableInTouchMode(true);
            sink.setClickable(false);
            sink.setLongClickable(false);
            sink.setEnabled(true);
            sink.setVisibility(View.VISIBLE);
            sink.setAlpha(0f);
            sink.setBackgroundColor(Color.TRANSPARENT);
            sink.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    sink.setDefaultFocusHighlightEnabled(false);
                } catch (Throwable ignored) {
                }
            }

            FrameLayout.LayoutParams sinkParams = new FrameLayout.LayoutParams(
                    Math.max(1, dp(host.getContext(), 1f)),
                    Math.max(1, dp(host.getContext(), 1f))
            );
            sinkParams.leftMargin = 0;
            sinkParams.topMargin = 0;
            host.addView(sink, sinkParams);
            postImeFocusSink = new WeakReference<>(sink);
        }

        try {
            sink.setFocusable(true);
            sink.setFocusableInTouchMode(true);
            boolean focused = sink.requestFocus();
            Logging.i(TAG, "Post-IME Android focus parked on 1x1 sink focused=" + focused
                    + " displayId=" + displayId(sink));
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to park Android focus after IME: " + throwable);
        }

        // Android View focus is now safely parked. Restore the independent native
        // input focus immediately so Minecraft never needs an ESC/menu round trip.
        try {
            CallbackBridge.setInputReady(true);
            CallbackBridge.ensureInputFocus();
        } catch (Throwable ignored) {
        }
    }

    private static void disableAndroidFocusHighlightOnMinecraftTarget(@Nullable View source) {
        MinecraftGLSurface surface = findMinecraftSurface(source);
        if (surface == null && source != null) {
            Activity activity = findActivity(source.getContext());
            if (activity != null && activity.getWindow() != null) {
                surface = findMinecraftSurface(activity.getWindow().getDecorView());
            }
        }
        if (surface == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                surface.setDefaultFocusHighlightEnabled(false);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void restoreMinecraftAfterImeClose(
            @NonNull View returnFocusTarget,
            @NonNull String reason
    ) {
        MinecraftGLSurface surface = findMinecraftSurface(returnFocusTarget);
        if (surface == null) {
            Activity activity = findActivity(returnFocusTarget.getContext());
            if (activity != null && activity.getWindow() != null) {
                surface = findMinecraftSurface(activity.getWindow().getDecorView());
            }
        }

        final MinecraftGLSurface minecraftSurface = surface;
        final Runnable restoreNativeFocus = () -> {
            // Do not let a delayed WindowInsets/focus callback auto-select the full-screen
            // Minecraft View after the IME has gone away.
            focusPostImeSink(returnFocusTarget);
            try {
                CallbackBridge.setInputReady(true);
                CallbackBridge.ensureInputFocus();
            } catch (Throwable ignored) {
            }

            if (minecraftSurface == null || !minecraftSurface.isAttachedToWindow()) {
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    minecraftSurface.setDefaultFocusHighlightEnabled(false);
                } catch (Throwable ignored) {
                }
            }

            try {
                minecraftSurface.resumeVulkanPresentationIfSurfaceReady(
                        "TouchKeyboardHelper IME " + reason
                );
            } catch (Throwable throwable) {
                Logging.i(TAG, "Unable to resume Minecraft presentation after IME "
                        + reason + ": " + throwable);
            }
        };

        // IME/window teardown is asynchronous, especially on Thor's secondary
        // Presentation. Reassert the native focus/presentation gate across the short
        // transition, but never request Android View focus on the full-screen surface.
        returnFocusTarget.post(restoreNativeFocus);
        returnFocusTarget.postDelayed(restoreNativeFocus, 32L);
        returnFocusTarget.postDelayed(restoreNativeFocus, 96L);
        returnFocusTarget.postDelayed(restoreNativeFocus, 180L);
    }

    @Nullable
    private static FrameLayout findFrameLayout(@NonNull View view) {
        if (view instanceof FrameLayout) return (FrameLayout) view;

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                FrameLayout found = findFrameLayout(group.getChildAt(i));
                if (found != null) return found;
            }
        }

        return null;
    }

    private static int dp(@NonNull Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /**
     * A normal EditText does not always report Backspace when it is already empty.
     * That breaks Minecraft fields that already contain text before this hidden IME
     * anchor is opened, because Android thinks there is nothing to delete while
     * Minecraft still has text like "New World".
     */
    private static class MinecraftKeyboardEditText extends EditText {
        MinecraftKeyboardEditText(@NonNull Context context) {
            super(context);
        }

        @Nullable
        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            InputConnection base = super.onCreateInputConnection(outAttrs);
            if (base == null) return null;

            outAttrs.imeOptions |= EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN;

            return new InputConnectionWrapper(base, true) {
                @Override
                public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                    if (shouldForwardEmptyBackspace(beforeLength, afterLength)) {
                        sendRepeatedBackspace(Math.max(1, beforeLength));
                        return true;
                    }
                    return super.deleteSurroundingText(beforeLength, afterLength);
                }

                @Override
                public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
                    if (shouldForwardEmptyBackspace(beforeLength, afterLength)) {
                        sendRepeatedBackspace(Math.max(1, beforeLength));
                        return true;
                    }
                    return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength);
                }

                @Override
                public boolean sendKeyEvent(KeyEvent event) {
                    if (event != null
                            && event.getAction() == KeyEvent.ACTION_DOWN
                            && event.getRepeatCount() == 0
                            && event.getKeyCode() == KeyEvent.KEYCODE_DEL
                            && isOverlayTextEmpty()) {
                        sendKeyTap(GLFW_PRESS_KEY_BACKSPACE);
                        return true;
                    }
                    return super.sendKeyEvent(event);
                }
            };
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_DEL && isOverlayTextEmpty()) {
                sendKeyTap(GLFW_PRESS_KEY_BACKSPACE);
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        @Override
        public boolean onKeyPreIme(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK && event != null) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    TouchKeyboardHelper.hideKeyboard(false);
                }
                return true;
            }
            return super.onKeyPreIme(keyCode, event);
        }

        private boolean shouldForwardEmptyBackspace(int beforeLength, int afterLength) {
            return beforeLength > 0 && afterLength == 0 && isOverlayTextEmpty();
        }

        private boolean isOverlayTextEmpty() {
            Editable editable = getText();
            return editable == null || editable.length() == 0;
        }
    }

    private static final class NativeKeyboardInputView extends MinecraftKeyboardEditText {
        private final Handler handler = new Handler(Looper.getMainLooper());
        @NonNull private final View returnFocusTarget;
        private final boolean submitSendsEnter;
        private final boolean pushMinecraftViewport;
        private final boolean dualScreenSession;
        @Nullable private final Runnable dualScreenAfterHide;
        private String lastText = "";
        private boolean closing;
        private boolean internalChange;
        private boolean imeWasVisible;
        @Nullable private ViewTreeObserver.OnWindowFocusChangeListener windowFocusListener;

        NativeKeyboardInputView(
                @NonNull Context context,
                @NonNull View returnFocusTarget,
                boolean submitSendsEnter,
                boolean pushMinecraftViewport,
                boolean dualScreenSession,
                @Nullable Runnable dualScreenAfterHide
        ) {
            super(context);
            this.returnFocusTarget = returnFocusTarget;
            this.submitSendsEnter = submitSendsEnter;
            this.pushMinecraftViewport = pushMinecraftViewport;
            this.dualScreenSession = dualScreenSession;
            this.dualScreenAfterHide = dualScreenAfterHide;

            setSingleLine(true);
            setMinLines(1);
            setMaxLines(1);
            setTextColor(Color.TRANSPARENT);
            setHintTextColor(Color.TRANSPARENT);
            setBackgroundColor(Color.TRANSPARENT);
            setAlpha(0.01f);
            setCursorVisible(false);
            setSelectAllOnFocus(false);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            setImeOptions((submitSendsEnter ? EditorInfo.IME_ACTION_SEND : EditorInfo.IME_ACTION_DONE)
                    | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    | EditorInfo.IME_FLAG_NO_FULLSCREEN);

            if (dualScreenSession && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setOnApplyWindowInsetsListener((view, insets) -> {
                    boolean imeVisible = insets.isVisible(WindowInsets.Type.ime());
                    if (imeVisible) {
                        imeWasVisible = true;
                    } else if (imeWasVisible && !closing) {
                        handler.post(() -> {
                            if (!closing && TouchKeyboardHelper.activeInput == this) {
                                TouchKeyboardHelper.notifyImeHiddenBySystem();
                            }
                        });
                    }
                    return insets;
                });
            }

            addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
                @Override public void afterTextChanged(Editable editable) {
                    if (closing || internalChange) return;
                    String current = editable == null ? "" : editable.toString();
                    dispatchTextDelta(lastText, current);
                    lastText = current;
                }
            });

            setOnEditorActionListener((v, actionId, event) -> {
                boolean editorAction = actionId == EditorInfo.IME_ACTION_DONE
                        || actionId == EditorInfo.IME_ACTION_SEND
                        || actionId == EditorInfo.IME_ACTION_GO
                        || actionId == EditorInfo.IME_ACTION_UNSPECIFIED;
                boolean enterKey = event != null
                        && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getRepeatCount() == 0;
                if (editorAction || enterKey) {
                    submitCurrentText();
                    return true;
                }
                return false;
            });

            setOnKeyListener((v, keyCode, event) -> {
                if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    TouchKeyboardHelper.hideKeyboard(false);
                    return true;
                }
                return false;
            });
        }

        boolean submitsEnter() {
            return submitSendsEnter;
        }

        boolean isDualScreenSession() {
            return dualScreenSession;
        }

        void openKeyboard() {
            setVisibility(VISIBLE);
            setEnabled(true);
            requestFocusFromTouch();
            requestFocus();

            // A freshly-shown secondary Presentation can be attached before WindowManager
            // grants it key focus. Retry immediately when that window-focus callback arrives
            // so the user never has to tap the bottom screen just to wake the keyboard.
            windowFocusListener = hasWindowFocus -> {
                if (hasWindowFocus && !closing) {
                    requestFocusFromTouch();
                    requestFocus();
                    handler.post(this::showSoftInputAgain);
                }
            };
            post(() -> {
                if (closing || windowFocusListener == null) return;
                ViewTreeObserver observer = getViewTreeObserver();
                if (observer.isAlive()) {
                    observer.addOnWindowFocusChangeListener(windowFocusListener);
                }
            });

            // This is an explicit user-requested keyboard action. Ask immediately, again on
            // the next traversal after the transient Presentation is committed, and once
            // more after short guard intervals for Thor's multi-display WindowManager.
            showSoftInputAgain();
            handler.post(this::showSoftInputAgain);
            handler.postDelayed(this::showSoftInputAgain, 48L);
            handler.postDelayed(this::showSoftInputAgain, 140L);
            handler.postDelayed(this::showSoftInputAgain, 280L);
        }

        void preserveAfterMinecraftSurfaceTap() {
            if (closing) return;

            // A Minecraft suggestion click can replace the field contents on the game
            // side. The invisible Android EditText therefore cannot keep its old delta
            // baseline or the next character may replay stale backspaces/text.
            rebaseAfterExternalMinecraftEdit();

            handler.post(() -> {
                if (closing || TouchKeyboardHelper.activeInput != this) return;
                requestFocusFromTouch();
                requestFocus();
                showSoftInputAgain();
            });
            handler.postDelayed(() -> {
                if (closing || TouchKeyboardHelper.activeInput != this) return;
                requestFocus();
                showSoftInputAgain();
            }, 70L);
        }

        void rebaseAfterExternalMinecraftEdit() {
            if (!submitSendsEnter || closing) return;
            handler.post(() -> {
                if (closing) return;
                internalChange = true;
                setText("");
                setSelection(0);
                lastText = "";
                internalChange = false;
                requestFocus();
                showSoftInputAgain();
            });
        }

        void close(boolean clearText) {
            if (clearText) {
                closeAfterSubmit();
            } else {
                closeInternal(false, true);
            }
        }

        void closeFromSystemImeHidden() {
            closeInternal(false, false);
        }

        /**
         * Enter/Done is special: InputMethodManager.hideSoftInputFromWindow() only starts
         * the IME hide animation. Android still considers this EditText/window to be the
         * IME owner for a few hundred milliseconds. The old code removed this view and
         * reset MinecraftGLSurface immediately, which races Surface/WindowManager
         * composition and can leave the game under a persistent pale focus/IME layer.
         *
         * Keep the 1px editor attached and focused until the hide animation is over, then
         * perform the normal teardown. No Minecraft SurfaceView/TextureView focus or
         * viewport mutation happens while Android is still hiding the keyboard.
         */
        private void closeAfterSubmit() {
            if (closing) return;
            closing = true;
            removeWindowFocusRetryListener();

            // Enter/Done must never hand Android View focus directly from the hidden
            // IME editor back to the full-screen MinecraftGLSurface. On some Android
            // builds that leaves the game surface in a persistent focused/activated
            // composition state (the pale/white wash the user sees) until Minecraft
            // performs another menu focus cycle. Park focus on a permanent invisible
            // 1x1 View first; GLFW/native input is restored independently below.
            TouchKeyboardHelper.focusPostImeSink(returnFocusTarget);

            InputMethodManager manager = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null) {
                manager.hideSoftInputFromWindow(getWindowToken(), 0);
            }

            // Prevent any late IME/editor callbacks from forwarding stale text while the
            // keyboard animation is running. Keep the View itself attached until finishClose.
            internalChange = true;
            setText("");
            lastText = "";
            internalChange = false;

            handler.postDelayed(() -> {
                if (!closing) return;
                finishClose(true);
            }, IME_SUBMIT_TEARDOWN_DELAY_MS);
        }

        private void closeInternal(boolean clearText, boolean hideAndroidIme) {
            if (closing) return;
            closing = true;
            removeWindowFocusRetryListener();

            TouchKeyboardHelper.focusPostImeSink(returnFocusTarget);

            if (hideAndroidIme) {
                InputMethodManager manager = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (manager != null) {
                    manager.hideSoftInputFromWindow(getWindowToken(), 0);
                }
            }
            finishClose(clearText);
        }

        private void removeWindowFocusRetryListener() {
            ViewTreeObserver.OnWindowFocusChangeListener listener = windowFocusListener;
            windowFocusListener = null;
            if (listener != null) {
                try {
                    ViewTreeObserver observer = getViewTreeObserver();
                    if (observer.isAlive()) observer.removeOnWindowFocusChangeListener(listener);
                } catch (Throwable ignored) {
                }
            }
        }

        private void finishClose(boolean clearText) {
            if (TouchKeyboardHelper.activeInput == this) {
                TouchKeyboardHelper.activeInput = null;
            }

            // Do not clear the insets listener until teardown actually happens. In
            // particular, an Enter/Done close must leave the IME owner/window stable for
            // the whole Android hide animation.
            setOnApplyWindowInsetsListener(null);

            if (clearText) {
                internalChange = true;
                setText("");
                lastText = "";
                internalChange = false;
            }

            ViewGroup parent = (ViewGroup) getParent();
            if (parent != null) parent.removeView(this);

            if (pushMinecraftViewport) {
                GameImeViewportController.detachActive(true);
            } else {
                GameImeViewportController.clearImeViewportInset(returnFocusTarget);
            }

            // Keep the tiny sink as Android's View-focus owner while restoring the
            // full-screen Minecraft surface's focusability. Merely making that surface
            // focusable again while no other View owns focus lets Android auto-select it,
            // which is the bad state that produced the persistent white/pale wash.
            TouchKeyboardHelper.focusPostImeSink(returnFocusTarget);
            TouchKeyboardHelper.restoreMinecraftSurfaceFocus();
            TouchKeyboardHelper.disableAndroidFocusHighlightOnMinecraftTarget(returnFocusTarget);

            if (dualScreenAfterHide != null) {
                try {
                    dualScreenAfterHide.run();
                } catch (Throwable throwable) {
                    Logging.e(TAG, "Unable to restore dual-screen focus after IME", throwable);
                }
            }

            TouchKeyboardHelper.restoreMinecraftAfterImeClose(
                    returnFocusTarget,
                    clearText ? "submit-after-ime-hidden" : "hide"
            );
        }

        private void showSoftInputAgain() {
            if (closing || !isAttachedToWindow()) return;
            if (!hasFocus()) {
                requestFocusFromTouch();
                requestFocus();
            }

            InputMethodManager manager = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null) {
                int flags = dualScreenSession
                        ? InputMethodManager.SHOW_FORCED
                        : InputMethodManager.SHOW_IMPLICIT;
                manager.showSoftInput(this, flags);
            }

            // On Android 11+ this also asks the WindowInsets controller for IME visibility.
            // It is especially useful after a Presentation changes from NOT_FOCUSABLE to
            // focusable in the same frame as the keyboard-button tap.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && getWindowInsetsController() != null) {
                getWindowInsetsController().show(WindowInsets.Type.ime());
            }
        }

        private void submitCurrentText() {
            // Text is sent as the user types. For Minecraft menus/world name,
            // Android Done only closes the keyboard. For chat/command input,
            // Android Enter/Send must also press Minecraft Enter.
            if (shouldSendMinecraftEnterOnSubmit()) {
                sendKeyTap(GLFW_PRESS_KEY_ENTER);
            }
            TouchKeyboardHelper.hideKeyboard(true);
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if ((keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                    && event != null
                    && event.getRepeatCount() == 0) {
                submitCurrentText();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        private boolean shouldSendMinecraftEnterOnSubmit() {
            return submitSendsEnter
                    || MinecraftTextInputKeyboardTrigger.isMinecraftChatScreenOpen();
        }

        private void dispatchTextDelta(@NonNull String oldText, @NonNull String newText) {
            int prefix = 0;
            int minLength = Math.min(oldText.length(), newText.length());
            while (prefix < minLength && oldText.charAt(prefix) == newText.charAt(prefix)) {
                prefix++;
            }

            int oldSuffix = oldText.length() - 1;
            int newSuffix = newText.length() - 1;
            while (oldSuffix >= prefix
                    && newSuffix >= prefix
                    && oldText.charAt(oldSuffix) == newText.charAt(newSuffix)) {
                oldSuffix--;
                newSuffix--;
            }

            int removed = oldSuffix - prefix + 1;
            for (int i = 0; i < removed; i++) {
                sendKeyTap(GLFW_PRESS_KEY_BACKSPACE);
            }

            if (newSuffix >= prefix) {
                String inserted = newText.substring(prefix, newSuffix + 1);
                for (int i = 0; i < inserted.length(); i++) {
                    char c = inserted.charAt(i);
                    if (c == '\n' || c == '\r') {
                        if (shouldSendMinecraftEnterOnSubmit()) {
                            sendKeyTap(GLFW_PRESS_KEY_ENTER);
                        }
                        TouchKeyboardHelper.hideKeyboard(true);
                    } else {
                        sendChar(c);
                    }
                }
            }
        }
    }

    private static void sendChar(char c) {
        CallbackBridge.setInputReady(true);

        if (sendCharByReflection(c)) {
            return;
        }

        // Fallback for bridges that do not expose a char callback. This will at least
        // handle control keys and some old text fields, but the reflection path above is
        // the preferred path for normal Minecraft chat/sign input.
        sendAsciiFallback(c);
    }

    private static boolean sendCharByReflection(char c) {
        Class<?> clazz = CallbackBridge.class;
        Object[][] attempts = new Object[][]{
                {"sendChar", new Class[]{char.class, int.class}, new Object[]{c, CallbackBridge.getCurrentMods()}},
                {"sendChar", new Class[]{char.class, int.class}, new Object[]{c, 0}},
                {"sendChar", new Class[]{int.class}, new Object[]{(int) c}},
                {"sendChar", new Class[]{char.class}, new Object[]{c}},
                {"sendCharMods", new Class[]{int.class, int.class}, new Object[]{(int) c, CallbackBridge.getCurrentMods()}},
                {"sendCharMods", new Class[]{char.class, int.class}, new Object[]{c, CallbackBridge.getCurrentMods()}},
                {"putChar", new Class[]{int.class}, new Object[]{(int) c}},
                {"putCharEvent", new Class[]{int.class}, new Object[]{(int) c}},
                {"sendKeycode", new Class[]{int.class, char.class, int.class, int.class, boolean.class}, new Object[]{0, c, 0, CallbackBridge.getCurrentMods(), true}}
        };

        for (Object[] attempt : attempts) {
            try {
                String methodName = (String) attempt[0];
                Class<?>[] parameterTypes = (Class<?>[]) attempt[1];
                Object[] args = (Object[]) attempt[2];
                Method method = clazz.getMethod(methodName, parameterTypes);
                method.invoke(null, args);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static void sendAsciiFallback(char c) {
        if (c == '\b') {
            sendKeyTap(GLFW_PRESS_KEY_BACKSPACE);
            return;
        }
        if (c == '\n' || c == '\r') {
            sendKeyTap(GLFW_PRESS_KEY_ENTER);
            return;
        }
        if (c == 27) {
            sendKeyTap(GLFW_PRESS_KEY_ESCAPE);
            return;
        }

        int key = keyCodeForChar(c);
        if (key >= 0) {
            sendKeyTap(key);
        }
    }

    private static int keyCodeForChar(char c) {
        if (c >= 'a' && c <= 'z') return 'A' + (c - 'a');
        if (c >= 'A' && c <= 'Z') return c;
        if (c >= '0' && c <= '9') return c;
        if (c == ' ') return 32;
        if (c == '-') return 45;
        if (c == '=') return 61;
        if (c == '[') return 91;
        if (c == ']') return 93;
        if (c == '\\') return 92;
        if (c == ';') return 59;
        if (c == '\'') return 39;
        if (c == ',') return 44;
        if (c == '.') return 46;
        if (c == '/') return 47;
        if (c == '`') return 96;
        return -1;
    }

    private static void sendRepeatedBackspace(int count) {
        int safeCount = Math.max(1, Math.min(DEFAULT_CLEAR_BACKSPACES, count));
        for (int i = 0; i < safeCount; i++) {
            sendKeyTap(GLFW_PRESS_KEY_BACKSPACE);
        }
    }

    private static void sendKeyTap(int keyCode) {
        try {
            CallbackBridge.setInputReady(true);
            CallbackBridge.sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), true);
            CallbackBridge.setModifiers(keyCode, true);
            CallbackBridge.sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), false);
            CallbackBridge.setModifiers(keyCode, false);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to send keyboard key tap " + keyCode, throwable);
        }
    }
}

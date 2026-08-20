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

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
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

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

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
    static final String DEFAULT_WORLD_NAME_TEXT = "New World";

    @Nullable private static NativeKeyboardInputView activeInput;
    private static long lastChatKeyPressUptimeMs;
    private static boolean chatImeSessionActive;

    private TouchKeyboardHelper() {
    }

    public static void showKeyboard(@NonNull View source) {
        if (CallbackBridge.isGrabbing()) {
            /*
             * Do not let the standalone Input action push the game halfway up.
             * The legacy Android launcher/legacy Android launcher behavior only makes sense after Minecraft chat was
             * actually requested (T or /). Controller mappings can now bind one
             * button to T and another to Input, or the same button to both. The T
             * action marks the chat grace window; Input then opens the IME and
             * attaches the viewport push. If the user presses Input by itself while
             * still in grabbed gameplay, there is no Minecraft text field to type
             * into, so keep the surface fullscreen and do nothing.
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

        // v11 was too strict here: it refused to open the Android IME unless the
        // reflection/I-beam text-field probe had already succeeded. That made the
        // Input button appear completely broken on Create World / World Name and
        // on some chat/menu states where the probe cannot see Minecraft's widget.
        //
        // Keep the important safety rule instead: always allow the explicit Input
        // button to open the hidden IME, but only use Minecraft Enter + viewport
        // push for chat. Chat is detected either by the actual ChatScreen or by the
        // recent grabbed-mode T/open-chat request, because ChatScreen reflection is
        // not reliable across every Minecraft version. Normal menu fields stay
        // fullscreen and Android Done only closes the IME.
        boolean chatMode = shouldUseChatImeMode();
        showNativeKeyboard(source, chatMode, chatMode);
    }

    public static void showChatKeyboard(@NonNull View source) {
        // Explicit launcher keyboard buttons are intended for Minecraft chat. Do not
        // depend on CallbackBridge.isGrabbing() here, because opening chat releases
        // mouse grab before the user presses the Android keyboard button.
        if (CallbackBridge.isGrabbing()) {
            markChatKeyPressed();
            sendKeyTap(84); // GLFW_KEY_T
            source.postDelayed(() -> showNativeKeyboard(source, true, true), 90L);
            return;
        }

        boolean chatMode = shouldUseChatImeMode();
        showNativeKeyboard(source, chatMode, chatMode);
    }

    public static void showMenuTextKeyboard(@NonNull View source) {
        // Usually menu text boxes use Done, but when the focused Minecraft text box
        // belongs to ChatScreen we must keep Android Enter as Minecraft Enter so
        // commands/messages submit instead of only closing the IME. Only ChatScreen
        // gets the viewport-push viewport push; normal menus/world-name screens stay
        // fullscreen so the menu layout is not shifted or broken.
        boolean chatMode = shouldUseChatImeMode();
        showNativeKeyboard(source, chatMode, chatMode);
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

        View root = source.getRootView();
        if (root == null) root = source;

        FrameLayout host = findFrameLayout(root);
        if (host == null) {
            source.requestFocus();
            InputMethodManager manager = (InputMethodManager) source.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null) {
                manager.showSoftInput(source, InputMethodManager.SHOW_IMPLICIT);
            }
            return;
        }

        NativeKeyboardInputView inputView = new NativeKeyboardInputView(host.getContext(), source, submitSendsEnter, pushMinecraftViewport);
        activeInput = inputView;
        if (pushMinecraftViewport) {
            chatImeSessionActive = true;
            GameImeViewportController.attach(inputView, source);
        } else {
            GameImeViewportController.detachActive(true);
            GameImeViewportController.clearImeViewportInset(source);
        }

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(host.getContext(), 1f), dp(host.getContext(), 1f));
        params.leftMargin = -dp(host.getContext(), 8f);
        params.topMargin = -dp(host.getContext(), 8f);
        host.addView(inputView, params);
        inputView.openKeyboard();
    }

    public static void hideKeyboard(boolean clearText) {
        // clearText=true is used for submit/finished text entry, so chat is done.
        // clearText=false is used for IME hide/minimize/back where Minecraft chat
        // may still be open. Preserve chatImeSessionActive in that case so pressing
        // Input again reopens the Android keyboard and pushes the GLSurface back up.
        if (clearText) {
            clearChatImeState();
        }
        NativeKeyboardInputView input = activeInput;
        if (input == null) {
            GameImeViewportController.detachActive(true);
            return;
        }
        activeInput = null;
        input.close(clearText);
    }

    public static void cancelMinecraftTextInputFromGame(@Nullable View source) {
        clearChatImeState();
        NativeKeyboardInputView input = activeInput;
        if (input != null) {
            activeInput = null;
            input.close(false);
            return;
        }
        GameImeViewportController.detachActive(true);
        if (source != null) {
            GameImeViewportController.clearImeViewportInset(source);
            source.requestFocus();
        }
    }

    static void notifyImeHiddenBySystem() {
        // Android keyboard minimize hides only the system IME. Minecraft chat can
        // remain open. Do not clear chatImeSessionActive here; otherwise the next
        // Input press opens a plain hidden EditText and does not push the viewport.
        NativeKeyboardInputView input = activeInput;
        if (input == null) {
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
        private String lastText = "";
        private boolean closing;
        private boolean internalChange;

        NativeKeyboardInputView(@NonNull Context context, @NonNull View returnFocusTarget, boolean submitSendsEnter, boolean pushMinecraftViewport) {
            super(context);
            this.returnFocusTarget = returnFocusTarget;
            this.submitSendsEnter = submitSendsEnter;
            this.pushMinecraftViewport = pushMinecraftViewport;

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

        void openKeyboard() {
            requestFocus();
            handler.postDelayed(this::showSoftInputAgain, 60L);
            handler.postDelayed(this::showSoftInputAgain, 160L);
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
            closeInternal(clearText, true);
        }

        void closeFromSystemImeHidden() {
            closeInternal(false, false);
        }

        private void closeInternal(boolean clearText, boolean hideAndroidIme) {
            closing = true;
            if (hideAndroidIme) {
                InputMethodManager manager = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (manager != null) {
                    manager.hideSoftInputFromWindow(getWindowToken(), 0);
                }
            }
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
            returnFocusTarget.requestFocus();
        }

        private void showSoftInputAgain() {
            InputMethodManager manager = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null) {
                manager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
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

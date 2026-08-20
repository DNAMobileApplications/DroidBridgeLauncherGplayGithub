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

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.lwjgl.glfw.CallbackBridge;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Opens Android's real IME when Minecraft is likely focused on one of its own
 * OpenGL/LWJGL text boxes.
 *
 * Minecraft text boxes are not Android EditText views; they are game widgets
 * rendered inside the surface. This helper combines cheap launcher-side signals
 * with a best-effort reflection probe so finger/mouse clicks and controller A
 * presses can open a hidden Android EditText only after Minecraft has focused
 * its internal text widget.
 */
public final class MinecraftTextInputKeyboardTrigger {
    private static final long POINTER_OPEN_DELAY_MS = 140L;
    private static final long CONTROLLER_OPEN_DELAY_MS = 180L;
    private static final long DUPLICATE_CONTROLLER_EVENT_MS = 80L;
    private static final long KEYBIND_CAPTURE_CONFIRMED_WINDOW_MS = 6000L;
    private static final long KEYBIND_CAPTURE_PROBE_DELAY_MS = 110L;
    private static final long KEYBIND_CAPTURE_CONFIRM_REARM_GUARD_MS = 650L;
    private static final int MAX_REFLECTION_DEPTH = 5;
    private static final int MAX_REFLECTION_VISITS = 260;

    private static volatile long sLastControllerConfirmUptimeMs;
    private static volatile long sKeybindCaptureArmedUntilUptimeMs;
    private static volatile long sKeybindCaptureProbeGeneration;
    private static volatile long sSuppressControllerConfirmArmUntilUptimeMs;
    @Nullable private static volatile ClassLoader sMinecraftClassLoader;
    private static final ExecutorService KEYBIND_CAPTURE_PROBE_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "DroidBridge-Keybind-Capture-Probe");
                thread.setDaemon(true);
                return thread;
            });

    private MinecraftTextInputKeyboardTrigger() {
    }

    public static void onMenuPointerConfirm(@NonNull View source, float x, float y, boolean grabbed) {
        if (grabbed || TouchKeyboardHelper.isKeyboardShowing()) return;

        scheduleKeybindCaptureProbe(source);

        // Do not guess text-field coordinates. Minecraft UI scaling, custom GUIs,
        // mods, and old/new widget layouts place text boxes anywhere on the surface.
        // Probe after the click has reached Minecraft and retry while focus settles.
        scheduleKeyboardOpen(source, 80L);
        scheduleKeyboardOpen(source, POINTER_OPEN_DELAY_MS);
        scheduleKeyboardOpen(source, 280L);
        scheduleKeyboardOpen(source, 480L);
    }

    public static void onPotentialControllerConfirm(@NonNull View source, @Nullable KeyEvent event, boolean grabbed) {
        if (event == null || grabbed || TouchKeyboardHelper.isKeyboardShowing()) return;
        if (!isControllerConfirmRelease(event)) return;

        long now = SystemClock.uptimeMillis();
        if (now < sSuppressControllerConfirmArmUntilUptimeMs) return;
        long last = sLastControllerConfirmUptimeMs;
        if (last > 0L && now - last < DUPLICATE_CONTROLLER_EVENT_MS) return;
        sLastControllerConfirmUptimeMs = now;

        scheduleKeybindCaptureProbe(source);

        // Let Minecraft/controller mapping handle A first. If that focused an EditBox,
        // the delayed probe below opens Android's IME for that focused game widget.
        scheduleKeyboardOpen(source, CONTROLLER_OPEN_DELAY_MS);
    }

    /**
     * Called by the built-in launcher gamepad path after a menu-mapped left-click
     * finishes. The normal Surface key callbacks do not receive those consumed
     * controller events, so this explicit handoff is required when A/R2 selects a
     * Minecraft key-binding row.
     */
    public static void onLauncherMenuConfirmReleased(@NonNull View source) {
        if (CallbackBridge.isGrabbing()) return;
        if (SystemClock.uptimeMillis() < sSuppressControllerConfirmArmUntilUptimeMs) return;
        scheduleKeybindCaptureProbe(source);
    }

    /**
     * Consumes exactly one controller press for Minecraft's key-binding capture.
     * The native OpenJDK-side query is authoritative. The short armed window is
     * only retained after that native query already confirmed an active capture.
     */
    public static boolean consumeMinecraftKeybindCaptureForControllerPress() {
        long now = SystemClock.uptimeMillis();

        // Consume an already-confirmed capture without inspecting the game VM.
        long armedUntil = sKeybindCaptureArmedUntilUptimeMs;
        if (armedUntil > 0L && now <= armedUntil) {
            sKeybindCaptureArmedUntilUptimeMs = 0L;
            sSuppressControllerConfirmArmUntilUptimeMs = now + KEYBIND_CAPTURE_CONFIRM_REARM_GUARD_MS;
            return true;
        }
        if (armedUntil > 0L) {
            sKeybindCaptureArmedUntilUptimeMs = 0L;
        }

        // Never inspect the embedded JVM from Android's controller/input callback.
        // A menu click schedules one coalesced background probe. Until that probe
        // confirms capture, this press follows the normal saved menu mapping.
        return false;
    }

    public static boolean isMinecraftControlsScreenOpen() {
        // Minecraft runs in the embedded OpenJDK VM. Query that VM through the native
        // input bridge first; Android/ART reflection cannot see game classes.
        if (CallbackBridge.isMinecraftControlsScreenOpenNative()) {
            return true;
        }
        try {
            Object minecraft = findMinecraftInstance();
            return minecraft != null && looksLikeControlsScreen(findCurrentScreen(minecraft));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void scheduleKeybindCaptureProbe(@NonNull View source) {
        final long generation = ++sKeybindCaptureProbeGeneration;
        source.postDelayed(() -> {
            if (generation != sKeybindCaptureProbeGeneration) return;
            if (!source.isShown() || CallbackBridge.isGrabbing()) return;
            if (SystemClock.uptimeMillis() < sSuppressControllerConfirmArmUntilUptimeMs) return;

            // Minecraft lives in the embedded OpenJDK VM. Run one coalesced native
            // inspection on the dedicated probe thread; never block Android's UI or
            // controller callback and never scan Android/ART classes for Minecraft.
            KEYBIND_CAPTURE_PROBE_EXECUTOR.execute(() -> {
                if (generation != sKeybindCaptureProbeGeneration) return;
                if (CallbackBridge.isGrabbing()) return;
                if (SystemClock.uptimeMillis() < sSuppressControllerConfirmArmUntilUptimeMs) return;
                if (!CallbackBridge.isMinecraftKeybindCaptureActiveNative()) return;
                long now = SystemClock.uptimeMillis();
                sKeybindCaptureArmedUntilUptimeMs = Math.max(
                        sKeybindCaptureArmedUntilUptimeMs,
                        now + KEYBIND_CAPTURE_CONFIRMED_WINDOW_MS
                );
            });
        }, KEYBIND_CAPTURE_PROBE_DELAY_MS);
    }

    private static void scheduleKeyboardOpen(@NonNull View source, long delayMs) {
        source.postDelayed(() -> {
            if (!source.isShown()) return;
            if (CallbackBridge.isGrabbing()) return;
            if (TouchKeyboardHelper.isKeyboardShowing()) return;
            if (!isMinecraftTextInputFocused()) return;
            TouchKeyboardHelper.showMenuTextKeyboard(source);
        }, delayMs);
    }

    public static boolean isMinecraftTextInputFocused() {
        // Fast path: modern Minecraft/LWJGL asks for an I-beam cursor over text inputs.
        if (CallbackBridge.isTextInputCursor()) return true;

        // Controller focus often has no mouse hover/cursor shape. Reflection is only
        // a best-effort fallback and fails safely on versions/screens we do not know.
        return isMinecraftTextInputFocusedByReflection();
    }

    public static boolean isMinecraftChatScreenOpen() {
        try {
            Object minecraft = findMinecraftInstance();
            if (minecraft == null) return false;
            Object screen = findCurrentScreen(minecraft);
            return looksLikeChatScreen(screen);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Returns whether Minecraft currently has a GUI screen open. A null result means
     * the Minecraft instance could not be inspected and callers should fall back to
     * GLFW cursor-grab state.
     */
    @Nullable
    public static Boolean queryMinecraftGuiOpen() {
        try {
            Object minecraft = findMinecraftInstance();
            if (minecraft == null) return null;
            return findCurrentScreen(minecraft) != null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * True only while Minecraft's Controls/Key Binds screen is actually waiting for
     * the next key or mouse input. This intentionally does not infer capture from an
     * arbitrary GUI left-click because inventory slot clicks are normal mouse clicks.
     */
    public static boolean isMinecraftKeybindCaptureActive() {
        // The embedded OpenJDK VM is authoritative. Android/ART reflection cannot
        // see Minecraft classes and only adds thread enumeration and object-walk cost.
        return CallbackBridge.isMinecraftKeybindCaptureActiveNative();
    }

    private static boolean looksLikeControlsScreen(@Nullable Object screen) {
        if (screen == null) return false;
        String name = screen.getClass().getName().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String simple = dot >= 0 ? name.substring(dot + 1) : name;
        return simple.contains("keybind")
                || simple.contains("keymapping")
                || simple.equals("controlsscreen")
                || simple.equals("guicontrols")
                || simple.contains("controlsoptions")
                || simple.equals("class_6599")
                || simple.equals("class_458");
    }

    private static boolean isActiveKeybindSelection(@Nullable Object value) {
        if (value == null) return false;
        if (value instanceof Number) return ((Number) value).intValue() >= 0;
        if (value instanceof Boolean) return (Boolean) value;
        return true;
    }

    private static boolean looksLikeChatScreen(@Nullable Object screen) {
        if (screen == null) return false;
        String name = screen.getClass().getName().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String simple = dot >= 0 ? name.substring(dot + 1) : name;

        // Vanilla names across common eras/mappings:
        // - net.minecraft.client.gui.screens.ChatScreen
        // - net.minecraft.client.gui.screen.ChatScreen
        // - net.minecraft.client.gui.GuiChat
        // Keep this intentionally narrow so create-world/server-name EditBoxes do
        // not accidentally submit their parent screen when Android Done is pressed.
        return simple.equals("chatscreen")
                || simple.equals("guichat")
                || simple.endsWith("$chatscreen")
                || simple.endsWith("$guichat");
    }

    private static boolean isControllerConfirmRelease(@NonNull KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_UP) return false;
        if (event.getRepeatCount() > 0) return false;

        int keyCode = event.getKeyCode();
        boolean confirmKey = keyCode == KeyEvent.KEYCODE_BUTTON_A
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER;
        if (!confirmKey) return false;

        int source = event.getSource();
        boolean controllerSource = (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;

        InputDevice device = event.getDevice();
        boolean controllerDevice = device != null && (((device.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                || ((device.getSources() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK));

        return controllerSource || controllerDevice || keyCode == KeyEvent.KEYCODE_BUTTON_A;
    }

    private static boolean isMinecraftTextInputFocusedByReflection() {
        try {
            Object minecraft = findMinecraftInstance();
            if (minecraft == null) return false;

            Object screen = findCurrentScreen(minecraft);
            if (screen == null) return false;

            Object focused = invokeAnyNoArg(screen, "getFocused", "getFocusedWidget", "getFocusedChild");
            if (focused != null && looksLikeTextInput(focused)) {
                return true;
            }

            return scanForFocusedTextInput(screen);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Nullable
    private static Object findMinecraftInstance() {
        Class<?> clazz = findClass("net.minecraft.client.Minecraft");
        if (clazz == null) return null;

        Object instance = invokeAnyStaticNoArg(clazz, "getInstance", "getMinecraft");
        if (instance != null) return instance;

        for (String fieldName : new String[]{"instance", "theMinecraft", "minecraft"}) {
            try {
                Field field = findField(clazz, fieldName);
                if (field == null || !Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object value = field.get(null);
                if (value != null) return value;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    @Nullable
    private static Object findCurrentScreen(@NonNull Object minecraft) {
        Object screen = getAnyField(minecraft, "screen", "currentScreen");
        if (screen != null) return screen;

        Class<?> clazz = minecraft.getClass();
        while (clazz != null && clazz != Object.class) {
            Field[] fields;
            try {
                fields = clazz.getDeclaredFields();
            } catch (Throwable ignored) {
                fields = new Field[0];
            }

            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Class<?> type = field.getType();
                String typeName = type.getName().toLowerCase(Locale.ROOT);
                if (!typeName.contains("screen") && !typeName.contains("guiscreen")) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(minecraft);
                    if (value != null) return value;
                } catch (Throwable ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private static boolean scanForFocusedTextInput(@NonNull Object root) {
        Map<Object, Boolean> visited = Collections.synchronizedMap(new IdentityHashMap<>());
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(root, 0));

        int visits = 0;
        while (!queue.isEmpty() && visits < MAX_REFLECTION_VISITS) {
            Node node = queue.removeFirst();
            Object object = node.value;
            if (object == null) continue;
            if (visited.put(object, Boolean.TRUE) != null) continue;
            visits++;

            if (looksLikeTextInput(object) && isObjectFocused(object)) {
                return true;
            }

            if (node.depth >= MAX_REFLECTION_DEPTH) continue;
            enqueueChildren(queue, object, node.depth + 1);
        }
        return false;
    }

    private static void enqueueChildren(@NonNull ArrayDeque<Node> queue, @NonNull Object object, int depth) {
        Class<?> clazz = object.getClass();
        if (isIgnoredReflectionClass(clazz)) return;

        if (clazz.isArray()) {
            int length = Math.min(Array.getLength(object), 96);
            for (int i = 0; i < length; i++) {
                try {
                    Object value = Array.get(object, i);
                    if (value != null) queue.add(new Node(value, depth));
                } catch (Throwable ignored) {
                }
            }
            return;
        }

        if (object instanceof Collection<?>) {
            int count = 0;
            for (Object value : (Collection<?>) object) {
                if (value != null) queue.add(new Node(value, depth));
                if (++count >= 96) break;
            }
            return;
        }

        Class<?> walk = clazz;
        while (walk != null && walk != Object.class) {
            Field[] fields;
            try {
                fields = walk.getDeclaredFields();
            } catch (Throwable ignored) {
                fields = new Field[0];
            }

            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Class<?> type = field.getType();
                if (type.isPrimitive()) continue;
                if (type == String.class || Number.class.isAssignableFrom(type) || type == Boolean.class || type == Character.class) continue;

                try {
                    field.setAccessible(true);
                    Object value = field.get(object);
                    if (value != null) queue.add(new Node(value, depth));
                } catch (Throwable ignored) {
                }
            }
            walk = walk.getSuperclass();
        }
    }

    private static boolean looksLikeTextInput(@NonNull Object object) {
        String name = object.getClass().getName().toLowerCase(Locale.ROOT);
        return name.contains("editbox")
                || name.contains("edit_box")
                || name.contains("textfield")
                || name.contains("text_field")
                || name.contains("textinput")
                || name.contains("text_input")
                || name.endsWith("guitextfield");
    }

    private static boolean isObjectFocused(@NonNull Object object) {
        Object methodValue = invokeAnyNoArg(object, "isFocused", "focused");
        if (methodValue instanceof Boolean && (Boolean) methodValue) return true;

        Object fieldValue = getAnyField(object, "focused", "isFocused", "field_146226_p");
        return fieldValue instanceof Boolean && (Boolean) fieldValue;
    }

    @Nullable
    private static Object invokeAnyNoArg(@NonNull Object target, @NonNull String... names) {
        Class<?> clazz = target.getClass();
        while (clazz != null && clazz != Object.class) {
            for (String name : names) {
                try {
                    Method method = clazz.getDeclaredMethod(name);
                    if (Modifier.isStatic(method.getModifiers())) continue;
                    method.setAccessible(true);
                    return method.invoke(target);
                } catch (Throwable ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    @Nullable
    private static Object invokeAnyStaticNoArg(@NonNull Class<?> clazz, @NonNull String... names) {
        for (String name : names) {
            try {
                Method method = clazz.getDeclaredMethod(name);
                if (!Modifier.isStatic(method.getModifiers())) continue;
                method.setAccessible(true);
                return method.invoke(null);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    @Nullable
    private static Object getAnyField(@NonNull Object target, @NonNull String... names) {
        Class<?> clazz = target.getClass();
        while (clazz != null && clazz != Object.class) {
            for (String name : names) {
                Field field = findField(clazz, name);
                if (field == null || Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    return field.get(target);
                } catch (Throwable ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    @Nullable
    private static Field findField(@NonNull Class<?> start, @NonNull String name) {
        Class<?> clazz = start;
        while (clazz != null && clazz != Object.class) {
            try {
                return clazz.getDeclaredField(name);
            } catch (Throwable ignored) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    @Nullable
    private static Class<?> findClass(@NonNull String name) {
        ArrayDeque<ClassLoader> loaders = new ArrayDeque<>();
        Map<ClassLoader, Boolean> seen = new IdentityHashMap<>();

        addClassLoader(loaders, seen, sMinecraftClassLoader);
        addClassLoader(loaders, seen, Thread.currentThread().getContextClassLoader());
        addClassLoader(loaders, seen, MinecraftTextInputKeyboardTrigger.class.getClassLoader());
        addClassLoader(loaders, seen, ClassLoader.getSystemClassLoader());

        // Android input callbacks execute on the Activity thread, while Minecraft is
        // loaded by ModLauncher/Fabric on the JVM/render thread. Search those thread
        // context classloaders once and cache the one that owns Minecraft. Without
        // this, the key-bind reflection probe always returned false and menu mappings
        // such as Mouse Left / Arrow Right leaked into the binding screen.
        try {
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread == null) continue;
                try {
                    addClassLoader(loaders, seen, thread.getContextClassLoader());
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }

        while (!loaders.isEmpty()) {
            ClassLoader loader = loaders.removeFirst();
            try {
                Class<?> found = Class.forName(name, false, loader);
                if ("net.minecraft.client.Minecraft".equals(name)) {
                    sMinecraftClassLoader = loader;
                }
                return found;
            } catch (Throwable ignored) {
            }
        }

        try {
            Class<?> found = Class.forName(name);
            if ("net.minecraft.client.Minecraft".equals(name)) {
                sMinecraftClassLoader = found.getClassLoader();
            }
            return found;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void addClassLoader(
            @NonNull ArrayDeque<ClassLoader> loaders,
            @NonNull Map<ClassLoader, Boolean> seen,
            @Nullable ClassLoader loader
    ) {
        if (loader == null || seen.put(loader, Boolean.TRUE) != null) return;
        loaders.addLast(loader);
    }

    private static boolean isIgnoredReflectionClass(@NonNull Class<?> clazz) {
        String name = clazz.getName();
        return name.startsWith("java.lang.")
                || name.startsWith("java.time.")
                || name.startsWith("android.")
                || name.startsWith("dalvik.");
    }

    private static final class Node {
        final Object value;
        final int depth;

        Node(@NonNull Object value, int depth) {
            this.value = value;
            this.depth = depth;
        }
    }
}

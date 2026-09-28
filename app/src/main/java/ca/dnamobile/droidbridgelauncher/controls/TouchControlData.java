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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

/** One visible on-screen control button. */
public final class TouchControlData {
    public static final int SPECIAL_KEYBOARD = -1;
    public static final int SPECIAL_TOGGLE_CONTROLS = -2;
    public static final int SPECIAL_MOUSE_LEFT = -3;
    public static final int SPECIAL_MOUSE_RIGHT = -4;
    public static final int SPECIAL_VIRTUAL_MOUSE = -5;
    public static final int SPECIAL_MOUSE_MIDDLE = -6;
    public static final int SPECIAL_SCROLL_UP = -7;
    public static final int SPECIAL_SCROLL_DOWN = -8;
    public static final int SPECIAL_MENU = -9;
    public static final int SPECIAL_KEY_SENDER_KEYBOARD = -10;
    public static final int SPECIAL_DUAL_SCREEN_SWAP = -11;
    public static final int MAX_ACTION_SLOTS = 4;

    public static final String POSITION_ANCHOR_LEFT = "left";
    public static final String POSITION_ANCHOR_CENTER = "center";
    public static final String POSITION_ANCHOR_RIGHT = "right";
    public static final String POSITION_ANCHOR_TOP = "top";
    public static final String POSITION_ANCHOR_BOTTOM = "bottom";

    @NonNull public String id = UUID.randomUUID().toString();
    @NonNull public String label = "Button";
    @NonNull public String action = TouchControlActions.KEY;
    public int keyCode = 32;
    @NonNull public int[] keyCodes = new int[0];
    @NonNull public int[] keySlots = new int[0];
    public int mouseButton = 0;
    public int scrollY = 0;
    public float x = 32f;
    public float y = 32f;
    public float width = 64f;
    public float height = 48f;
    public float sizePercent = 100f;
    public float opacity = 0.72f;
    public float cornerRadius = 16f;
    public float strokeWidth = 2f;
    public int strokeColor = 0x99FFFFFF;
    public int backgroundColor = 0x66000000;
    public static final String IMAGE_MODE_BACKGROUND = "background";
    public static final String IMAGE_MODE_REPLACE = "replace";
    /** Optional user-selected image rendered inside this control. */
    @Nullable public String imageUri;
    /** "background" keeps the button text/stroke; "replace" renders the image only. */
    @NonNull public String imageMode = IMAGE_MODE_BACKGROUND;
    /** User image transform controls used by the touch-control editor. */
    public float imageScalePercent = 100f;
    public float imageOffsetXPercent = 0f;
    public float imageOffsetYPercent = 0f;
    public boolean toggle;
    public boolean visibleInGame = true;
    public boolean visibleInMenu = true;
    /**
     * When true, this control remains visible and clickable after the
     * Show / hide touch controls action hides the normal on-screen buttons.
     */
    public boolean visibleWhenControlsHidden;
    /** Legacy/Pojav mouse pass-through behavior, stored in the profile JSON. */
    public boolean mousePassThrough;
    /** Legacy/Pojav swipeable-button behavior, stored in the profile JSON. */
    public boolean swipeGesture;
    /** Optional Pojav-family bitmap tag. Preserved even when no custom bitmap is available. */
    @Nullable public String bitmapTag;
    public transient boolean migrateMousePassThroughFromPreferences;
    public transient boolean migrateSwipeGestureFromPreferences;
    public boolean joystickAbsolute;
    public boolean joystickForwardLock;
    public float joystickDeadzonePercent = 16f;

    /** ID of the drawer control that owns this control, or null when ungrouped. */
    @Nullable public String drawerParentId;
    /** Preserved Pojav/Zalith drawer orientation. Runtime visibility does not depend on it. */
    @NonNull public String drawerOrientation = "right";
    /** When true, this drawer starts expanded when a profile is first loaded. */
    public boolean drawerOpenByDefault;

    @Nullable public String rawX;
    @Nullable public String rawY;

    /**
     * Optional persistent anchor selected when a control is moved in the editor.
     * Older profiles leave these null and continue using position-based inference.
     */
    @Nullable public String positionAnchorX;
    @Nullable public String positionAnchorY;

    @NonNull
    public static TouchControlData key(@NonNull String label, int keyCode, float x, float y, float width, float height) {
        TouchControlData data = new TouchControlData();
        data.label = label;
        data.action = TouchControlActions.KEY;
        data.keyCode = keyCode;
        data.setKeyCodes(new int[]{keyCode});
        data.x = x;
        data.y = y;
        data.width = width;
        data.height = height;
        return data;
    }

    @NonNull
    public static TouchControlData mouse(@NonNull String label, int mouseButton, float x, float y) {
        TouchControlData data = new TouchControlData();
        data.label = label;
        data.action = TouchControlActions.MOUSE;
        data.mouseButton = mouseButton;
        data.x = x;
        data.y = y;
        data.width = 58f;
        data.height = 58f;
        return data;
    }

    @NonNull
    public static TouchControlData joystick(@NonNull String label, float x, float y, float width, float height) {
        TouchControlData data = new TouchControlData();
        data.label = label;
        data.action = TouchControlActions.JOYSTICK;
        data.x = x;
        data.y = y;
        data.width = width;
        data.height = height;
        data.opacity = 0.55f;
        data.visibleInGame = true;
        data.visibleInMenu = false;
        data.cornerRadius = 999f;
        return data;
    }

    @NonNull
    public static TouchControlData drawer(@NonNull String label, float x, float y, float width, float height) {
        TouchControlData data = new TouchControlData();
        data.label = label;
        data.action = TouchControlActions.DRAWER;
        data.keyCode = 0;
        data.setKeyCodes(new int[0]);
        data.x = x;
        data.y = y;
        data.width = width;
        data.height = height;
        data.visibleInGame = true;
        data.visibleInMenu = true;
        return data;
    }

    @NonNull
    public TouchControlData copy() {
        TouchControlData copy = new TouchControlData();
        copy.id = UUID.randomUUID().toString();
        copy.label = label;
        copy.action = action;
        copy.keyCode = keyCode;
        copy.keyCodes = keyCodes != null ? keyCodes.clone() : new int[0];
        copy.keySlots = keySlots != null ? keySlots.clone() : new int[0];
        copy.mouseButton = mouseButton;
        copy.scrollY = scrollY;
        copy.x = x;
        copy.y = y;
        copy.width = width;
        copy.height = height;
        copy.sizePercent = sizePercent;
        copy.opacity = opacity;
        copy.cornerRadius = cornerRadius;
        copy.strokeWidth = strokeWidth;
        copy.strokeColor = strokeColor;
        copy.backgroundColor = backgroundColor;
        copy.imageUri = imageUri;
        copy.imageMode = imageMode;
        copy.imageScalePercent = imageScalePercent;
        copy.imageOffsetXPercent = imageOffsetXPercent;
        copy.imageOffsetYPercent = imageOffsetYPercent;
        copy.toggle = toggle;
        copy.visibleInGame = visibleInGame;
        copy.visibleInMenu = visibleInMenu;
        copy.visibleWhenControlsHidden = visibleWhenControlsHidden;
        copy.mousePassThrough = mousePassThrough;
        copy.swipeGesture = swipeGesture;
        copy.bitmapTag = bitmapTag;
        copy.joystickAbsolute = joystickAbsolute;
        copy.joystickForwardLock = joystickForwardLock;
        copy.joystickDeadzonePercent = joystickDeadzonePercent;
        copy.drawerParentId = drawerParentId;
        copy.drawerOrientation = drawerOrientation;
        copy.drawerOpenByDefault = drawerOpenByDefault;
        copy.rawX = rawX;
        copy.rawY = rawY;
        copy.positionAnchorX = positionAnchorX;
        copy.positionAnchorY = positionAnchorY;
        return copy;
    }

    @NonNull
    public JSONObject toJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("label", label);
        json.put("action", action);
        json.put("keyCode", keyCode);
        JSONArray keys = new JSONArray();
        for (int code : normalizedKeyCodes()) keys.put(code);
        json.put("keyCodes", keys);
        JSONArray slots = new JSONArray();
        for (int code : normalizedKeySlots()) slots.put(code);
        json.put("keySlots", slots);
        json.put("mouseButton", mouseButton);
        json.put("scrollY", scrollY);
        json.put("x", x);
        json.put("y", y);
        json.put("width", width);
        json.put("height", height);
        json.put("sizePercent", sizePercent);
        json.put("opacity", opacity);
        json.put("cornerRadius", cornerRadius);
        json.put("strokeWidth", strokeWidth);
        json.put("strokeColor", strokeColor);
        json.put("backgroundColor", backgroundColor);
        if (imageUri != null && !imageUri.trim().isEmpty()) json.put("imageUri", imageUri.trim());
        json.put("imageMode", normalizeImageMode(imageMode));
        json.put("imageScalePercent", clampImageScalePercent(imageScalePercent));
        json.put("imageOffsetXPercent", clampImageOffsetPercent(imageOffsetXPercent));
        json.put("imageOffsetYPercent", clampImageOffsetPercent(imageOffsetYPercent));
        json.put("toggle", toggle);
        json.put("visibleInGame", visibleInGame);
        json.put("visibleInMenu", visibleInMenu);
        json.put("visibleWhenControlsHidden", visibleWhenControlsHidden);
        json.put("mousePassThrough", mousePassThrough);
        json.put("swipeGesture", swipeGesture);
        if (bitmapTag != null && !bitmapTag.trim().isEmpty()) json.put("bitmapTag", bitmapTag.trim());
        json.put("joystickAbsolute", joystickAbsolute);
        json.put("joystickForwardLock", joystickForwardLock);
        json.put("joystickDeadzonePercent", joystickDeadzonePercent);
        if (drawerParentId != null && !drawerParentId.trim().isEmpty()) {
            json.put("drawerParentId", drawerParentId.trim());
        }
        if (TouchControlActions.DRAWER.equals(action)) {
            json.put("drawerOrientation", normalizeDrawerOrientation(drawerOrientation));
            json.put("drawerOpenByDefault", drawerOpenByDefault);
        }
        if (rawX != null) json.put("rawX", rawX);
        if (rawY != null) json.put("rawY", rawY);
        String normalizedAnchorX = normalizeHorizontalPositionAnchor(positionAnchorX);
        String normalizedAnchorY = normalizeVerticalPositionAnchor(positionAnchorY);
        if (normalizedAnchorX != null) json.put("positionAnchorX", normalizedAnchorX);
        if (normalizedAnchorY != null) json.put("positionAnchorY", normalizedAnchorY);
        return json;
    }

    @NonNull
    public static TouchControlData fromJson(@NonNull JSONObject json) {
        TouchControlData data = new TouchControlData();
        data.id = sanitizeId(json.optString("id", data.id));
        data.label = json.optString("label", json.optString("name", data.label));
        data.action = json.optString("action", data.action);
        data.keyCode = json.optInt("keyCode", data.keyCode);
        data.keySlots = readKeySlots(json.optJSONArray("keySlots"), json.optJSONArray("keyCodes"), data.keyCode);
        data.keyCodes = activeCodesFromSlots(data.keySlots);
        data.keyCode = firstUsableKey(data.keyCodes, 0);
        data.mouseButton = json.optInt("mouseButton", data.mouseButton);
        data.scrollY = json.optInt("scrollY", data.scrollY);
        data.x = (float) json.optDouble("x", data.x);
        data.y = (float) json.optDouble("y", data.y);
        data.width = (float) json.optDouble("width", data.width);
        data.height = (float) json.optDouble("height", data.height);
        data.sizePercent = clampSizePercent((float) json.optDouble("sizePercent", data.sizePercent));
        data.opacity = (float) json.optDouble("opacity", data.opacity);
        data.cornerRadius = (float) json.optDouble("cornerRadius", data.cornerRadius);
        data.strokeWidth = (float) json.optDouble("strokeWidth", data.strokeWidth);
        data.strokeColor = json.optInt("strokeColor", data.strokeColor);
        data.backgroundColor = json.optInt("backgroundColor", json.optInt("bgColor", data.backgroundColor));
        data.imageUri = optNullableString(json, "imageUri", null);
        data.imageMode = normalizeImageMode(json.optString("imageMode", data.imageMode));
        data.imageScalePercent = clampImageScalePercent((float) json.optDouble("imageScalePercent", data.imageScalePercent));
        data.imageOffsetXPercent = clampImageOffsetPercent((float) json.optDouble("imageOffsetXPercent", data.imageOffsetXPercent));
        data.imageOffsetYPercent = clampImageOffsetPercent((float) json.optDouble("imageOffsetYPercent", data.imageOffsetYPercent));
        data.toggle = json.optBoolean("toggle", json.optBoolean("isToggle", data.toggle));
        data.visibleInGame = json.optBoolean("visibleInGame", json.optBoolean("displayInGame", data.visibleInGame));
        data.visibleInMenu = json.optBoolean("visibleInMenu", json.optBoolean("displayInMenu", data.visibleInMenu));
        data.visibleWhenControlsHidden = readVisibleWhenControlsHidden(json, data.action);
        boolean hasMousePassThrough = json.has("mousePassThrough") || json.has("passThruEnabled");
        boolean hasSwipeGesture = json.has("swipeGesture") || json.has("isSwipeable");
        data.mousePassThrough = json.optBoolean("mousePassThrough", json.optBoolean("passThruEnabled", false));
        data.swipeGesture = json.optBoolean("swipeGesture", json.optBoolean("isSwipeable", false));
        data.bitmapTag = optNullableString(json, "bitmapTag", null);
        data.migrateMousePassThroughFromPreferences = !hasMousePassThrough;
        data.migrateSwipeGestureFromPreferences = !hasSwipeGesture;
        data.joystickAbsolute = json.optBoolean("joystickAbsolute", json.optBoolean("absolute", data.joystickAbsolute));
        data.joystickForwardLock = json.optBoolean("joystickForwardLock", json.optBoolean("forwardLock", data.joystickForwardLock));
        data.joystickDeadzonePercent = clampJoystickDeadzonePercent((float) json.optDouble("joystickDeadzonePercent", json.optDouble("deadzone", json.optDouble("deadzonePercent", data.joystickDeadzonePercent))));
        data.drawerParentId = optNullableString(json, "drawerParentId", optNullableString(json, "drawerId", null));
        data.drawerOrientation = normalizeDrawerOrientation(json.optString("drawerOrientation", data.drawerOrientation));
        data.drawerOpenByDefault = json.optBoolean("drawerOpenByDefault", json.optBoolean("drawerInitiallyOpen", false));
        data.rawX = optNullableString(json, "rawX", optNullableString(json, "dynamicX", null));
        data.rawY = optNullableString(json, "rawY", optNullableString(json, "dynamicY", null));
        readPositionAnchors(data, json);
        return data;
    }

    @NonNull
    public static TouchControlData fromDroidBridgeControl(@NonNull JSONObject json) {
        return fromDroidBridgeControl(json, 8, 1f);
    }

    @NonNull
    public static TouchControlData fromDroidBridgeControl(@NonNull JSONObject json, int legacyVersion) {
        return fromDroidBridgeControl(json, legacyVersion, 1f);
    }

    @NonNull
    public static TouchControlData fromDroidBridgeControl(
            @NonNull JSONObject json,
            int legacyVersion,
            float density
    ) {
        TouchControlData data = new TouchControlData();
        data.id = sanitizeId(json.optString("id", data.id));
        data.label = json.optString("name", json.optString("label", "Button"));
        float safeDensity = density > 0f && !Float.isNaN(density) && !Float.isInfinite(density) ? density : 1f;
        data.width = Math.max(1f, (float) json.optDouble("width", 64d));
        data.height = Math.max(1f, (float) json.optDouble("height", 48d));
        if (legacyVersion <= 1) {
            // Pojav layout v1 stored dimensions in physical pixels. Its converter
            // calls setWidth/setHeight, which convert those values back to dp.
            data.width /= safeDensity;
            data.height /= safeDensity;
        }

        if (json.has("opacity")) {
            data.opacity = clamp01((float) json.optDouble("opacity", 0.72d));
        } else if (json.has("transparency")) {
            data.opacity = clamp01((100f - (float) json.optDouble("transparency", 28d)) / 100f);
        }

        // Pojav-family JSON stores cornerRadius as a percentage of half the
        // shortest button side, whereas DroidBridge stores an absolute dp radius.
        // Convert at import so the visible shape matches compatible third-party launchers.
        float legacyCornerRadiusPercent = json.has("cornerRadius")
                ? (float) json.optDouble("cornerRadius", 0d)
                : (json.optBoolean("isRound", false) ? 35f : 0f);
        data.cornerRadius = legacyCornerRadiusDp(
                legacyCornerRadiusPercent,
                data.width,
                data.height
        );

        data.strokeWidth = legacyVersion <= 1
                ? 0f
                : (float) json.optDouble("strokeWidth", data.strokeWidth);
        if (legacyVersion >= 2 && legacyVersion <= 5 && json.has("strokeWidth")) {
            // Pojav-family v2-v5 stored stroke width as a percentage of half the
            // largest button dimension. Current layouts store dp directly.
            float percent = data.strokeWidth;
            data.strokeWidth = (Math.max(data.width, data.height) / 2f) * (percent / 100f);
        }
        data.strokeColor = json.optInt("strokeColor", data.strokeColor);
        data.backgroundColor = legacyVersion <= 1
                ? 0x4D000000
                : json.optInt("bgColor", json.optInt("backgroundColor", data.backgroundColor));
        data.toggle = json.optBoolean("isToggle", json.optBoolean("toggle", false));
        data.visibleInGame = json.optBoolean("displayInGame", json.optBoolean("visibleInGame", true));
        data.visibleInMenu = json.optBoolean("displayInMenu", json.optBoolean("visibleInMenu", true));
        data.rawX = optNullableString(json, "dynamicX", optNullableString(json, "rawX", null));
        data.rawY = optNullableString(json, "dynamicY", optNullableString(json, "rawY", null));

        int[] importedKeys;
        JSONArray keyArray = json.optJSONArray("keycodes");
        if (keyArray != null && keyArray.length() > 0) {
            importedKeys = readKeyCodes(keyArray, 32);
        } else {
            importedKeys = readLegacyV1KeyCodes(json);
        }
        int firstKey = firstUsableKey(importedKeys, 32);
        applyImportedKey(data, firstKey, importedKeys);

        data.mousePassThrough = json.optBoolean("passThruEnabled", json.optBoolean("mousePassThrough", false));
        data.swipeGesture = json.optBoolean("isSwipeable", json.optBoolean("swipeGesture", false));
        data.bitmapTag = optNullableString(json, "bitmapTag", null);
        data.migrateMousePassThroughFromPreferences = false;
        data.migrateSwipeGestureFromPreferences = false;
        data.visibleWhenControlsHidden = readVisibleWhenControlsHidden(json, data.action);
        if (json.has("isHideable") && !json.has("visibleWhenControlsHidden")) {
            data.visibleWhenControlsHidden = !json.optBoolean("isHideable", true);
        }
        data.x = (float) json.optDouble("x", data.x);
        data.y = (float) json.optDouble("y", data.y);
        readPositionAnchors(data, json);
        return data;
    }

    @NonNull
    public static TouchControlData fromDroidBridgeJoystick(@NonNull JSONObject json) {
        return fromDroidBridgeJoystick(json, 8, 1f);
    }

    @NonNull
    public static TouchControlData fromDroidBridgeJoystick(@NonNull JSONObject json, int legacyVersion) {
        return fromDroidBridgeJoystick(json, legacyVersion, 1f);
    }

    @NonNull
    public static TouchControlData fromDroidBridgeJoystick(
            @NonNull JSONObject json,
            int legacyVersion,
            float density
    ) {
        float safeDensity = density > 0f && !Float.isNaN(density) && !Float.isInfinite(density) ? density : 1f;
        float importedWidth = Math.max(1f, (float) json.optDouble("width", 120d));
        float importedHeight = Math.max(1f, (float) json.optDouble("height", 120d));
        if (legacyVersion <= 1) {
            importedWidth /= safeDensity;
            importedHeight /= safeDensity;
        }
        TouchControlData data = joystick(
                json.optString("name", json.optString("label", "Joystick")),
                (float) json.optDouble("x", 32d),
                (float) json.optDouble("y", 360d),
                importedWidth,
                importedHeight
        );
        data.id = sanitizeId(json.optString("id", data.id));
        if (json.has("opacity")) {
            data.opacity = clamp01((float) json.optDouble("opacity", data.opacity));
        } else if (json.has("transparency")) {
            data.opacity = clamp01((100f - (float) json.optDouble("transparency", 28d)) / 100f);
        }
        float legacyCornerRadiusPercent = json.has("cornerRadius")
                ? (float) json.optDouble("cornerRadius", 100d)
                : (json.optBoolean("isRound", true) ? 100f : 0f);
        data.cornerRadius = legacyCornerRadiusDp(
                legacyCornerRadiusPercent,
                data.width,
                data.height
        );
        data.strokeWidth = legacyVersion <= 1
                ? 0f
                : (float) json.optDouble("strokeWidth", data.strokeWidth);
        if (legacyVersion >= 2 && legacyVersion <= 5 && json.has("strokeWidth")) {
            float percent = data.strokeWidth;
            data.strokeWidth = (Math.max(data.width, data.height) / 2f) * (percent / 100f);
        }
        data.strokeColor = json.optInt("strokeColor", data.strokeColor);
        data.backgroundColor = json.optInt("bgColor", json.optInt("backgroundColor", data.backgroundColor));
        data.visibleInGame = json.optBoolean("displayInGame", true);
        data.visibleInMenu = json.optBoolean("displayInMenu", false);
        data.mousePassThrough = json.optBoolean("passThruEnabled", json.optBoolean("mousePassThrough", false));
        data.swipeGesture = json.optBoolean("isSwipeable", json.optBoolean("swipeGesture", false));
        data.bitmapTag = optNullableString(json, "bitmapTag", null);
        data.migrateMousePassThroughFromPreferences = false;
        data.migrateSwipeGestureFromPreferences = false;
        data.visibleWhenControlsHidden = readVisibleWhenControlsHidden(json, data.action);
        data.rawX = optNullableString(json, "dynamicX", null);
        data.rawY = optNullableString(json, "dynamicY", null);
        data.joystickAbsolute = json.optBoolean("absolute", false);
        data.joystickForwardLock = json.optBoolean("forwardLock", false);
        data.joystickDeadzonePercent = clampJoystickDeadzonePercent((float) json.optDouble("joystickDeadzonePercent", json.optDouble("deadzone", json.optDouble("deadzonePercent", data.joystickDeadzonePercent))));
        readPositionAnchors(data, json);
        return data;
    }

    private static float legacyCornerRadiusDp(float percentage, float widthDp, float heightDp) {
        if (Float.isNaN(percentage) || Float.isInfinite(percentage)) return 0f;
        float clamped = Math.max(0f, Math.min(100f, percentage));
        return (Math.min(Math.max(1f, widthDp), Math.max(1f, heightDp)) / 2f) * (clamped / 100f);
    }

    @NonNull
    private static int[] readLegacyV1KeyCodes(@NonNull JSONObject json) {
        ArrayList<Integer> codes = new ArrayList<>(MAX_ACTION_SLOTS);
        if (json.optBoolean("holdShift", false)) codes.add(340);
        if (json.optBoolean("holdCtrl", false)) codes.add(341);
        if (json.optBoolean("holdAlt", false)) codes.add(342);
        if (json.has("keycode")) codes.add(json.optInt("keycode", 32));
        if (codes.isEmpty()) codes.add(32);
        int[] result = new int[Math.min(MAX_ACTION_SLOTS, codes.size())];
        for (int i = 0; i < result.length; i++) result[i] = codes.get(i);
        return result;
    }

    @NonNull
    public static String normalizeDrawerOrientation(@Nullable String orientation) {
        if (orientation == null) return "right";
        String value = orientation.trim().toLowerCase(Locale.ROOT);
        if ("down".equals(value) || "left".equals(value) || "up".equals(value)
                || "right".equals(value) || "free".equals(value)) {
            return value;
        }
        return "right";
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return 1f;
        return Math.max(0f, Math.min(1f, value));
    }



    private static boolean readVisibleWhenControlsHidden(@NonNull JSONObject json, @NonNull String action) {
        boolean fallback = shouldStayVisibleWhenControlsHiddenByDefault(action);
        return json.optBoolean(
                "visibleWhenControlsHidden",
                json.optBoolean(
                        "keepVisibleWhenControlsHidden",
                        json.optBoolean(
                                "keepVisibleWhenHidden",
                                json.optBoolean(
                                        "displayWhenHidden",
                                        json.optBoolean("alwaysVisible", fallback)
                                )
                        )
                )
        );
    }

    /**
     * The control that hides the overlay must always survive its own hide action.
     * Other controls, including the launcher GUI/menu button, only stay visible when
     * the layout explicitly enables visibleWhenControlsHidden.
     */
    public static boolean shouldStayVisibleWhenControlsHiddenByDefault(@Nullable String action) {
        return TouchControlActions.TOGGLE_CONTROLS.equals(action);
    }

    @NonNull
    public int[] normalizedKeyCodes() {
        if (keySlots != null && keySlots.length > 0) {
            return activeCodesFromSlots(toFixedSlots(keySlots, 0));
        }
        if (keyCodes != null && keyCodes.length > 0) return activeCodesFromSlots(toFixedSlots(keyCodes, keyCode));
        return keyCode == 0 ? new int[0] : new int[]{keyCode};
    }

    @NonNull
    public int[] normalizedKeySlots() {
        if (keySlots != null && keySlots.length > 0) return toFixedSlots(keySlots, 0);
        if (keyCodes != null && keyCodes.length > 0) return toFixedSlots(keyCodes, keyCode);
        return toFixedSlots(keyCode == 0 ? new int[0] : new int[]{keyCode}, keyCode);
    }

    public int getKeySlot(int slot) {
        int safeSlot = Math.max(0, Math.min(MAX_ACTION_SLOTS - 1, slot));
        int[] slots = normalizedKeySlots();
        return safeSlot < slots.length ? slots[safeSlot] : 0;
    }

    public void setKeySlot(int slot, int keyCodeValue) {
        int safeSlot = Math.max(0, Math.min(MAX_ACTION_SLOTS - 1, slot));
        int[] slots = normalizedKeySlots();
        slots[safeSlot] = keyCodeValue;
        setKeySlots(slots);
    }

    public void clearKeySlot(int slot) {
        setKeySlot(slot, 0);
    }

    public void setKeyCodes(@NonNull int[] codes) {
        setKeySlots(toFixedSlots(codes, 0));
    }

    public void setKeySlots(@NonNull int[] slots) {
        keySlots = toFixedSlots(slots, 0);
        keyCodes = activeCodesFromSlots(keySlots);
        keyCode = firstUsableKey(keyCodes, 0);
    }

    @NonNull
    private static int[] readKeySlots(@Nullable JSONArray slotArray, @Nullable JSONArray legacyArray, int fallback) {
        if (slotArray != null && slotArray.length() > 0) {
            return toFixedSlots(readRawCodes(slotArray), fallback);
        }
        return toFixedSlots(readKeyCodes(legacyArray, fallback), fallback);
    }

    @NonNull
    private static int[] readKeyCodes(@Nullable JSONArray array, int fallback) {
        int[] raw = readRawCodes(array);
        if (raw.length == 0) return fallback == 0 ? new int[0] : new int[]{fallback};
        return activeCodesFromSlots(raw);
    }

    @NonNull
    private static int[] readRawCodes(@Nullable JSONArray array) {
        if (array == null || array.length() == 0) return new int[0];
        ArrayList<Integer> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            int value = array.optInt(i, Integer.MIN_VALUE);
            if (value == Integer.MIN_VALUE) continue;
            values.add(value);
        }
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
        return result;
    }

    @NonNull
    private static int[] toFixedSlots(@NonNull int[] codes, int fallback) {
        int[] result = new int[MAX_ACTION_SLOTS];
        int write = 0;
        if (codes.length == MAX_ACTION_SLOTS) {
            for (int i = 0; i < MAX_ACTION_SLOTS; i++) result[i] = codes[i];
            return result;
        }
        for (int code : codes) {
            if (write >= MAX_ACTION_SLOTS) break;
            if (code == 0) continue;
            result[write++] = code;
        }
        if (write == 0 && fallback > 0) result[0] = fallback;
        return result;
    }

    @NonNull
    private static int[] activeCodesFromSlots(@NonNull int[] slots) {
        ArrayList<Integer> values = new ArrayList<>();
        for (int slot = 0; slot < Math.min(MAX_ACTION_SLOTS, slots.length); slot++) {
            int value = slots[slot];
            if (value != 0) values.add(value);
        }
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
        return result;
    }

    private static int firstUsableKey(@NonNull int[] keycodes, int fallback) {
        for (int key : keycodes) {
            if (key != 0) return key;
        }
        return fallback;
    }

    private static void readPositionAnchors(
            @NonNull TouchControlData data,
            @NonNull JSONObject json
    ) {
        data.positionAnchorX = normalizeHorizontalPositionAnchor(optNullableString(
                json,
                "positionAnchorX",
                optNullableString(
                        json,
                        "anchorX",
                        optNullableString(json, "horizontalAnchor", null)
                )
        ));
        data.positionAnchorY = normalizeVerticalPositionAnchor(optNullableString(
                json,
                "positionAnchorY",
                optNullableString(
                        json,
                        "anchorY",
                        optNullableString(json, "verticalAnchor", null)
                )
        ));
    }

    @Nullable
    public static String normalizeHorizontalPositionAnchor(@Nullable String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return null;
        if ("start".equals(normalized) || POSITION_ANCHOR_LEFT.equals(normalized)) {
            return POSITION_ANCHOR_LEFT;
        }
        if ("middle".equals(normalized) || POSITION_ANCHOR_CENTER.equals(normalized)) {
            return POSITION_ANCHOR_CENTER;
        }
        if ("end".equals(normalized) || POSITION_ANCHOR_RIGHT.equals(normalized)) {
            return POSITION_ANCHOR_RIGHT;
        }
        return null;
    }

    @Nullable
    public static String normalizeVerticalPositionAnchor(@Nullable String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("start".equals(normalized) || POSITION_ANCHOR_TOP.equals(normalized)) {
            return POSITION_ANCHOR_TOP;
        }
        if ("middle".equals(normalized) || POSITION_ANCHOR_CENTER.equals(normalized)) {
            return POSITION_ANCHOR_CENTER;
        }
        if ("end".equals(normalized) || POSITION_ANCHOR_BOTTOM.equals(normalized)) {
            return POSITION_ANCHOR_BOTTOM;
        }
        return null;
    }

    @NonNull
    public static String normalizeImageMode(@Nullable String mode) {
        return IMAGE_MODE_REPLACE.equalsIgnoreCase(mode) ? IMAGE_MODE_REPLACE : IMAGE_MODE_BACKGROUND;
    }

    public static float clampImageScalePercent(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return 100f;
        return Math.max(25f, Math.min(300f, value));
    }

    public static float clampImageOffsetPercent(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return 0f;
        return Math.max(-100f, Math.min(100f, value));
    }

    @Nullable
    private static String optNullableString(@NonNull JSONObject json, @NonNull String key, @Nullable String fallback) {
        if (!json.has(key) || json.isNull(key)) return fallback;
        String value = json.optString(key, null);
        return value == null || value.trim().isEmpty() || "null".equalsIgnoreCase(value.trim()) ? fallback : value;
    }

    @NonNull
    private static String sanitizeId(@Nullable String value) {
        if (value == null || value.trim().isEmpty() || "null".equalsIgnoreCase(value.trim())) {
            return UUID.randomUUID().toString();
        }
        return value.trim();
    }

    private static float clampSizePercent(float value) {
        return Math.max(30f, Math.min(250f, value));
    }

    public static float clampJoystickDeadzonePercent(float value) {
        return Math.max(0f, Math.min(80f, value));
    }

    private static void applyImportedKey(@NonNull TouchControlData data, int key, @NonNull int[] allKeys) {
        switch (key) {
            case SPECIAL_TOGGLE_CONTROLS:
                data.action = TouchControlActions.TOGGLE_CONTROLS;
                return;
            case SPECIAL_MOUSE_LEFT:
                data.action = TouchControlActions.MOUSE;
                data.mouseButton = 0;
                return;
            case SPECIAL_MOUSE_RIGHT:
                data.action = TouchControlActions.MOUSE;
                data.mouseButton = 1;
                return;
            case SPECIAL_MOUSE_MIDDLE:
                data.action = TouchControlActions.MOUSE;
                data.mouseButton = 2;
                return;
            case SPECIAL_SCROLL_UP:
                data.action = TouchControlActions.SCROLL;
                data.scrollY = 1;
                return;
            case SPECIAL_SCROLL_DOWN:
                data.action = TouchControlActions.SCROLL;
                data.scrollY = -1;
                return;
            case SPECIAL_MENU:
                data.action = TouchControlActions.MENU;
                return;
            case SPECIAL_KEYBOARD:
                data.action = TouchControlActions.KEYBOARD;
                return;
            case SPECIAL_KEY_SENDER_KEYBOARD:
                data.action = TouchControlActions.KEY_SENDER_KEYBOARD;
                return;
            case SPECIAL_VIRTUAL_MOUSE:
                data.action = TouchControlActions.VIRTUAL_MOUSE;
                return;
            case SPECIAL_DUAL_SCREEN_SWAP:
                data.action = TouchControlActions.DUAL_SCREEN_SWAP;
                return;
            default:
                data.action = TouchControlActions.KEY;
                data.keyCode = key;
                data.setKeyCodes(allKeys);
        }
    }
}

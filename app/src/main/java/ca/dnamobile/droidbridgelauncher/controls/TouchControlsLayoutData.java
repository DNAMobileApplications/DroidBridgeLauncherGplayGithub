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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Serializable DroidBridge touch-control layout. */
public final class TouchControlsLayoutData {
    public static final int RESPONSIVE_FORMAT_VERSION = 8;
    public static final float RESPONSIVE_REFERENCE_WIDTH = 1920f;
    public static final float RESPONSIVE_REFERENCE_HEIGHT = 1080f;

    public static final String UNIT_DP = "dp";
    public static final String UNIT_PX = "px";

    public static final int IMPORT_MODE_DROIDBRIDGE = 0;
    public static final int IMPORT_MODE_OTHER_LAUNCHER = 1;

    public static final String PROFILE_DROIDBRIDGE = "droidbridge";
    public static final String PROFILE_OTHER_LAUNCHER = "other_launcher";

    private static final float DEFAULT_IMPORTED_SOURCE_WIDTH = 854f;
    private static final float DEFAULT_IMPORTED_SOURCE_HEIGHT = 480f;

    public static final float DEFAULT_PROFILE_OPACITY = 1.0f;
    public static final int DEFAULT_PROFILE_BUTTON_SCALE_PERCENT = 100;

    public int version = RESPONSIVE_FORMAT_VERSION;
    @NonNull public String name = "Touch Controls";
    @NonNull public String importedFileName = "";
    public float preferredScale = 100f;
    @NonNull public String coordinateUnit = UNIT_DP;
    @NonNull public String coordinateProfile = PROFILE_DROIDBRIDGE;
    @NonNull public String controlSizeUnit = UNIT_DP;
    public float sourceWidth = 0f;
    public float sourceHeight = 0f;
    public float sourceDensity = 1f;
    public boolean responsiveCanvas;

    // Runtime/editor settings that belong to this controller profile. SharedPreferences
    // is now only a runtime mirror for code paths that have not yet been profile-aware.
    public float globalOpacity = DEFAULT_PROFILE_OPACITY;
    public int globalButtonScalePercent = DEFAULT_PROFILE_BUTTON_SCALE_PERCENT;
    public boolean virtualMouseEnabled = false;
    public boolean snapControlsEnabled = true;

    // Transient migration flags for profiles created before per-profile settings existed.
    public transient boolean migrateGlobalOpacityFromPreferences;
    public transient boolean migrateGlobalButtonScaleFromPreferences;
    public transient boolean migrateVirtualMouseFromPreferences;
    public transient boolean migrateSnapControlsFromPreferences;

    @NonNull public final List<TouchControlData> controls = new ArrayList<>();

    @NonNull
    public JSONObject toJson() throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", "DroidBridgeTouchControls");
        root.put("version", Math.max(version, RESPONSIVE_FORMAT_VERSION));
        root.put("name", name);
        if (!importedFileName.trim().isEmpty()) root.put("importedFileName", importedFileName.trim());
        root.put("preferredScale", preferredScale);
        root.put("coordinateUnit", normalizeCoordinateUnit(coordinateUnit));
        root.put("coordinateProfile", normalizeCoordinateProfile(coordinateProfile));
        root.put("controlSizeUnit", normalizeCoordinateUnit(controlSizeUnit));
        if (sourceWidth > 0f) root.put("sourceWidth", sourceWidth);
        if (sourceHeight > 0f) root.put("sourceHeight", sourceHeight);
        if (sourceDensity > 0f) root.put("sourceDensity", sourceDensity);
        root.put("responsiveCanvas", usesResponsiveCanvas());
        JSONObject profileSettings = new JSONObject();
        profileSettings.put("globalOpacity", clampOpacity(globalOpacity));
        profileSettings.put("globalButtonScalePercent", clampButtonScale(globalButtonScalePercent));
        profileSettings.put("virtualMouseEnabled", virtualMouseEnabled);
        profileSettings.put("snapControlsEnabled", snapControlsEnabled);
        root.put("profileSettings", profileSettings);
        JSONArray array = new JSONArray();
        for (TouchControlData control : controls) {
            array.put(control.toJson());
        }
        root.put("controls", array);
        return root;
    }

    @NonNull
    public static TouchControlsLayoutData fromJson(@NonNull JSONObject root) throws Exception {
        return fromJson(root, IMPORT_MODE_DROIDBRIDGE);
    }

    @NonNull
    public static TouchControlsLayoutData fromJson(@NonNull JSONObject root, int importMode) throws Exception {
        return fromJson(root, importMode, 0f, 0f, 1f);
    }

    @NonNull
    public static TouchControlsLayoutData fromJson(
            @NonNull JSONObject root,
            int importMode,
            float legacyScreenWidth,
            float legacyScreenHeight,
            float density
    ) throws Exception {
        if (root.has("controls")) {
            TouchControlsLayoutData data = new TouchControlsLayoutData();
            data.version = root.optInt("version", 1);
            data.name = root.optString("name", "Touch Controls");
            data.importedFileName = readImportedFileName(root);
            data.preferredScale = (float) root.optDouble("preferredScale", root.optDouble("scaledAt", 100d));
            boolean hasExplicitUnit = hasCoordinateUnit(root);
            data.coordinateUnit = readCoordinateUnit(root, UNIT_DP);
            data.coordinateProfile = readCoordinateProfile(root, importMode);
            readSourceCanvas(root, data);
            data.sourceDensity = readSourceDensity(root, data);
            data.responsiveCanvas = readResponsiveCanvas(root, data);
            data.controlSizeUnit = readControlSizeUnit(root, data);
            readProfileSettings(root, data, importMode == IMPORT_MODE_DROIDBRIDGE);
            applyImportedPreferredScaleIfMissing(root, data, importMode);
            JSONArray controls = root.optJSONArray("controls");
            if (controls != null) {
                for (int i = 0; i < controls.length(); i++) {
                    JSONObject object = controls.optJSONObject(i);
                    if (object != null) data.controls.add(TouchControlData.fromJson(object));
                }
            }
            if (shouldForceOtherLauncherCoordinateRules(root, importMode)) {
                forceOtherLauncherCoordinateRules(data);
                return data;
            }
            boolean legacyImportedCanvas = looksLikeImportedCanvasLayout(data);
            if (legacyImportedCanvas && (!hasExplicitUnit || data.version < 3)) {
                data.coordinateUnit = UNIT_PX;
                inferPixelSourceCanvasIfNeeded(data, true);
            } else if (!hasExplicitUnit && looksLikePixelAuthoredLayout(data)) {
                data.coordinateUnit = UNIT_PX;
                inferPixelSourceCanvasIfNeeded(data, true);
            } else {
                inferPixelSourceCanvasIfNeeded(data, false);
            }
            if (data.usesPixelCoordinates() && legacyImportedCanvas) {
                data.version = Math.max(data.version, 3);
            }
            normalizeSuspiciousImportedSourceCanvas(data);
            normalizeOutOfBoundsPixelCanvas(data);
            return data;
        }

        // Pojav-family layouts normally carry mControlDataList.
        if (root.has("mControlDataList") || root.has("mJoystickDataList") || root.has("mDrawerDataList")) {
            return fromDroidBridgeLikeJson(
                    root,
                    importMode,
                    legacyScreenWidth,
                    legacyScreenHeight,
                    density
            );
        }

        // Some launchers export a flat buttons array. Treat it as best-effort.
        if (root.has("buttons")) {
            TouchControlsLayoutData data = new TouchControlsLayoutData();
            data.name = root.optString("name", "Imported Controls");
            data.importedFileName = readImportedFileName(root);
            data.preferredScale = (float) root.optDouble("preferredScale", root.optDouble("scaledAt", 100d));
            boolean hasExplicitUnit = hasCoordinateUnit(root);
            data.coordinateUnit = readCoordinateUnit(root, hasSourceCanvas(root) ? UNIT_PX : UNIT_DP);
            data.coordinateProfile = readCoordinateProfile(root, importMode);
            readSourceCanvas(root, data);
            data.sourceDensity = readSourceDensity(root, data);
            data.responsiveCanvas = readResponsiveCanvas(root, data);
            data.controlSizeUnit = readControlSizeUnit(root, data);
            readProfileSettings(root, data, importMode == IMPORT_MODE_DROIDBRIDGE);
            applyImportedPreferredScaleIfMissing(root, data, importMode);
            JSONArray buttons = root.optJSONArray("buttons");
            if (buttons != null) {
                for (int i = 0; i < buttons.length(); i++) {
                    JSONObject object = buttons.optJSONObject(i);
                    if (object != null) data.controls.add(TouchControlData.fromJson(object));
                }
            }
            if (shouldForceOtherLauncherCoordinateRules(root, importMode)) {
                forceOtherLauncherCoordinateRules(data);
                return data;
            }
            boolean legacyImportedCanvas = looksLikeImportedCanvasLayout(data);
            if (legacyImportedCanvas && (!hasExplicitUnit || data.version < 3)) {
                data.coordinateUnit = UNIT_PX;
                inferPixelSourceCanvasIfNeeded(data, true);
            } else if (!hasExplicitUnit && looksLikePixelAuthoredLayout(data)) {
                data.coordinateUnit = UNIT_PX;
                inferPixelSourceCanvasIfNeeded(data, true);
            } else {
                inferPixelSourceCanvasIfNeeded(data, false);
            }
            if (data.usesPixelCoordinates() && legacyImportedCanvas) {
                data.version = Math.max(data.version, 3);
            }
            normalizeSuspiciousImportedSourceCanvas(data);
            normalizeOutOfBoundsPixelCanvas(data);
            return data;
        }

        throw new IllegalArgumentException("Unsupported touch control layout format.");
    }

    @NonNull
    private static TouchControlsLayoutData fromDroidBridgeLikeJson(
            @NonNull JSONObject root,
            int importMode,
            float legacyScreenWidth,
            float legacyScreenHeight,
            float density
    ) {
        TouchControlsLayoutData data = new TouchControlsLayoutData();
        data.name = root.optString("name", "Imported legacy Android launcher Controls");
        data.importedFileName = readImportedFileName(root);
        int legacyVersion = root.has("version") ? root.optInt("version", 1) : 1;
        data.version = Math.max(1, legacyVersion);
        data.preferredScale = (float) root.optDouble("scaledAt", root.optDouble("preferredScale", 100d));
        data.coordinateProfile = importMode == IMPORT_MODE_OTHER_LAUNCHER ? PROFILE_OTHER_LAUNCHER : PROFILE_DROIDBRIDGE;
        data.coordinateUnit = importMode == IMPORT_MODE_OTHER_LAUNCHER ? UNIT_DP : UNIT_PX;
        readProfileSettings(root, data, false);
        applyImportedPreferredScaleIfMissing(root, data, importMode);
        if (importMode != IMPORT_MODE_OTHER_LAUNCHER) readSourceCanvas(root, data);
        float safeDensity = density > 0f && !Float.isNaN(density) && !Float.isInfinite(density) ? density : 1f;
        float safeLegacyWidth = legacyScreenWidth > 1f ? legacyScreenWidth : DEFAULT_IMPORTED_SOURCE_WIDTH;
        float safeLegacyHeight = legacyScreenHeight > 1f ? legacyScreenHeight : DEFAULT_IMPORTED_SOURCE_HEIGHT;

        JSONArray controls = root.optJSONArray("mControlDataList");
        if (controls != null) {
            for (int i = 0; i < controls.length(); i++) {
                JSONObject object = controls.optJSONObject(i);
                if (object != null) {
                    TouchControlData control = TouchControlData.fromDroidBridgeControl(object, legacyVersion, safeDensity);
                    normalizeLegacyFixedPosition(control, object, legacyVersion, safeLegacyWidth, safeLegacyHeight);
                    data.controls.add(control);
                }
            }
        }
        JSONArray joysticks = root.optJSONArray("mJoystickDataList");
        if (joysticks != null) {
            for (int i = 0; i < joysticks.length(); i++) {
                JSONObject object = joysticks.optJSONObject(i);
                if (object != null) {
                    TouchControlData control = TouchControlData.fromDroidBridgeJoystick(object, legacyVersion, safeDensity);
                    normalizeLegacyFixedPosition(control, object, legacyVersion, safeLegacyWidth, safeLegacyHeight);
                    data.controls.add(control);
                }
            }
        }

        JSONArray drawers = root.optJSONArray("mDrawerDataList");
        if (drawers != null) {
            for (int i = 0; i < drawers.length(); i++) {
                JSONObject drawer = drawers.optJSONObject(i);
                if (drawer == null) continue;
                JSONObject properties = drawer.optJSONObject("properties");
                TouchControlData drawerControl;
                if (properties != null) {
                    drawerControl = TouchControlData.fromDroidBridgeControl(properties, legacyVersion, safeDensity);
                    normalizeLegacyFixedPosition(drawerControl, properties, legacyVersion, safeLegacyWidth, safeLegacyHeight);
                } else {
                    drawerControl = TouchControlData.drawer(
                            drawer.optString("name", "Drawer"),
                            32f,
                            32f,
                            64f,
                            48f
                    );
                }

                int drawerOrientation = readDrawerOrientation(drawer);
                drawerControl.action = TouchControlActions.DRAWER;
                drawerControl.keyCode = 0;
                drawerControl.setKeyCodes(new int[0]);
                drawerControl.toggle = false;
                drawerControl.drawerOrientation = drawerOrientationName(drawerOrientation);
                drawerControl.drawerOpenByDefault = drawer.optBoolean(
                        "drawerOpenByDefault",
                        drawer.optBoolean("isOpen", false)
                );
                data.controls.add(drawerControl);

                JSONArray subButtons = drawer.optJSONArray("buttonProperties");
                if (subButtons != null) {
                    for (int j = 0; j < subButtons.length(); j++) {
                        JSONObject sub = subButtons.optJSONObject(j);
                        if (sub != null) {
                            TouchControlData control = TouchControlData.fromDroidBridgeControl(sub, legacyVersion, safeDensity);
                            // Pojav-family v2-v5 converted drawer sub-button stroke width
                            // against the drawer button width, not the child button size.
                            if (drawerControl != null
                                    && legacyVersion >= 2
                                    && legacyVersion <= 5
                                    && sub.has("strokeWidth")) {
                                float percent = (float) sub.optDouble("strokeWidth", 0d);
                                control.strokeWidth = (Math.max(1f, drawerControl.width) / 2f) * (percent / 100f);
                            }
                            normalizeLegacyFixedPosition(control, sub, legacyVersion, safeLegacyWidth, safeLegacyHeight);
                            applyImportedDrawerPlacement(control, drawerControl, drawerOrientation, j);
                            control.drawerParentId = drawerControl.id;
                            data.controls.add(control);
                        }
                    }
                }
            }
        }

        if (importMode == IMPORT_MODE_OTHER_LAUNCHER) {
            forceOtherLauncherCoordinateRules(data);
            normalizePojavFamilyVersion(data, legacyVersion);
            return data;
        }

        // Old default_touch.json files usually store Android logical layout units
        // from a 1920x1080-class phone, which is about an 854x480 canvas at xxhdpi.
        // Use that as the fallback, and grow only when the coordinates prove it must.
        inferPixelSourceCanvasIfNeeded(data, true);
        normalizeSuspiciousImportedSourceCanvas(data);
        normalizeOutOfBoundsPixelCanvas(data);
        return data;
    }

    public boolean usesPixelCoordinates() {
        return UNIT_PX.equals(normalizeCoordinateUnit(coordinateUnit));
    }

    public boolean usesOtherLauncherProfile() {
        return PROFILE_OTHER_LAUNCHER.equals(normalizeCoordinateProfile(coordinateProfile));
    }

    /**
     * True only for profiles that still use the original Pojav-family mixed
     * coordinate model: button dimensions are dp while x/y and formulas resolve
     * against the current screen in pixels.
     *
     * Older DroidBridge imports can also carry coordinateProfile=other_launcher,
     * but those files were already converted to a pixel canvas and include
     * coordinateUnit=px plus sourceWidth/sourceHeight. Treating those converted
     * files as raw Pojav layouts is what makes every control grow and overlap.
     */
    public boolean usesOtherLauncherRuntimeRules() {
        return usesOtherLauncherProfile() && !usesPixelCoordinates();
    }

    /**
     * Native DroidBridge profiles authored against a reference pixel canvas.
     * Positions are mapped from that canvas while button dimensions can use
     * Android dp, matching Pojav-family physical sizing across devices.
     */
    public boolean usesResponsiveCanvas() {
        return responsiveCanvas
                && !usesOtherLauncherProfile()
                && usesPixelCoordinates()
                && sourceWidth > 0f
                && sourceHeight > 0f;
    }

    public boolean usesResponsiveDpSizes() {
        return usesResponsiveCanvas()
                && UNIT_DP.equals(normalizeCoordinateUnit(controlSizeUnit));
    }

    /**
     * Legacy native profiles used fixed dp geometry. They are converted once,
     * after the overlay has a real size, so shared layouts can scale between
     * phones, tablets, handhelds, and different display resolutions.
     */
    public boolean needsResponsiveCanvasMigration() {
        return !usesOtherLauncherProfile()
                && !usesResponsiveCanvas()
                && !usesPixelCoordinates();
    }

    public float resolvedSourceWidth(float fallback) {
        return sourceWidth > 0f ? sourceWidth : Math.max(1f, fallback);
    }

    public float resolvedSourceHeight(float fallback) {
        return sourceHeight > 0f ? sourceHeight : Math.max(1f, fallback);
    }

    public float resolvedSourceDensity() {
        return sourceDensity > 0f && !Float.isNaN(sourceDensity) && !Float.isInfinite(sourceDensity)
                ? sourceDensity
                : 1f;
    }

    private static float readSourceDensity(
            @NonNull JSONObject root,
            @NonNull TouchControlsLayoutData data
    ) {
        float value = firstPositiveFloat(root,
                "sourceDensity",
                "source_density",
                "referenceDensity",
                "reference_density",
                "density"
        );
        if (value > 0f) return value;
        // Version-8 responsive dp profiles use a logical 1 px-per-dp reference
        // unless a migrated profile records the density it was authored with.
        return 1f;
    }

    private static boolean readResponsiveCanvas(
            @NonNull JSONObject root,
            @NonNull TouchControlsLayoutData data
    ) {
        if (data.usesOtherLauncherProfile()) return false;
        if (root.has("responsiveCanvas")) {
            return root.optBoolean("responsiveCanvas", false);
        }
        // Version 7+ native pixel profiles are responsive even when an early
        // prerelease omitted the explicit marker.
        return data.version >= 7
                && UNIT_PX.equals(normalizeCoordinateUnit(data.coordinateUnit))
                && data.sourceWidth > 0f
                && data.sourceHeight > 0f;
    }

    @NonNull
    private static String readImportedFileName(@NonNull JSONObject root) {
        return firstString(root,
                "importedFileName",
                "imported_file_name",
                "sourceFileName",
                "source_file_name",
                "originalFileName",
                "original_file_name"
        );
    }

    @NonNull
    private static String readCoordinateUnit(@NonNull JSONObject root, @NonNull String fallback) {
        String value = firstString(root,
                "coordinateUnit",
                "coordinate_unit",
                "layoutUnit",
                "layout_unit",
                "units",
                "unit"
        );
        if (value.trim().isEmpty()) return normalizeCoordinateUnit(fallback);
        return normalizeCoordinateUnit(value);
    }

    @NonNull
    private static String readControlSizeUnit(
            @NonNull JSONObject root,
            @NonNull TouchControlsLayoutData data
    ) {
        String value = firstString(root,
                "controlSizeUnit",
                "control_size_unit",
                "buttonSizeUnit",
                "button_size_unit",
                "dimensionUnit",
                "dimension_unit"
        );
        if (!value.trim().isEmpty()) return normalizeCoordinateUnit(value);

        // Raw Pojav-family profiles and pre-responsive DroidBridge profiles keep
        // button dimensions in dp. Version-7 responsive profiles stored dimensions
        // in reference-canvas pixels and must retain that behavior unless upgraded.
        if (data.usesOtherLauncherProfile() || !data.responsiveCanvas) return UNIT_DP;
        return data.version >= RESPONSIVE_FORMAT_VERSION ? UNIT_DP : UNIT_PX;
    }

    private static boolean hasCoordinateUnit(@NonNull JSONObject root) {
        return root.has("coordinateUnit")
                || root.has("coordinate_unit")
                || root.has("layoutUnit")
                || root.has("layout_unit")
                || root.has("units")
                || root.has("unit");
    }

    @NonNull
    private static String normalizeCoordinateUnit(@NonNull String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("pixel".equals(normalized) || "pixels".equals(normalized) || "px".equals(normalized)) return UNIT_PX;
        return UNIT_DP;
    }

    @NonNull
    private static String readCoordinateProfile(@NonNull JSONObject root, int importMode) {
        String value = firstString(root,
                "coordinateProfile",
                "coordinate_profile",
                "profile",
                "importProfile",
                "import_profile"
        );
        if (!value.trim().isEmpty()) {
            String normalized = normalizeCoordinateProfile(value);
            if (PROFILE_OTHER_LAUNCHER.equals(normalized)
                    && isKnownMisclassifiedDroidBridgeDefault(root)) {
                return PROFILE_DROIDBRIDGE;
            }
            return normalized;
        }

        // A DroidBridge-exported profile must remain a native DroidBridge profile even
        // when the user accidentally selects the legacy-launcher import option. Only
        // source files that do not identify themselves as DroidBridge may inherit the
        // legacy import mode.
        if (isDroidBridgeFormat(root)) return PROFILE_DROIDBRIDGE;
        return importMode == IMPORT_MODE_OTHER_LAUNCHER ? PROFILE_OTHER_LAUNCHER : PROFILE_DROIDBRIDGE;
    }

    private static boolean shouldForceOtherLauncherCoordinateRules(
            @NonNull JSONObject root,
            int importMode
    ) {
        if (importMode != IMPORT_MODE_OTHER_LAUNCHER) return false;
        if (isKnownMisclassifiedDroidBridgeDefault(root)) return false;

        // A DroidBridge export is already normalized and must keep the coordinate
        // unit/source canvas written into the file. Re-running the raw Pojav import
        // conversion here used to erase sourceWidth/sourceHeight and change px to dp,
        // which permanently broke shared or re-imported DroidBridge control files.
        if (isDroidBridgeFormat(root)) return false;

        // Explicit metadata always wins for non-DroidBridge source formats.
        String explicitProfile = firstString(root,
                "coordinateProfile",
                "coordinate_profile",
                "profile",
                "importProfile",
                "import_profile"
        );
        if (!explicitProfile.trim().isEmpty()) {
            return PROFILE_OTHER_LAUNCHER.equals(normalizeCoordinateProfile(explicitProfile));
        }

        return !isDroidBridgeFormat(root);
    }

    private static boolean isDroidBridgeFormat(@NonNull JSONObject root) {
        String format = firstString(root, "format", "layoutFormat", "layout_format");
        return "droidbridgetouchcontrols".equals(format.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean isKnownMisclassifiedDroidBridgeDefault(@NonNull JSONObject root) {
        if (!isDroidBridgeFormat(root) || root.optInt("version", 1) >= 6) return false;

        String explicitProfile = firstString(root,
                "coordinateProfile",
                "coordinate_profile",
                "profile",
                "importProfile",
                "import_profile"
        );
        if (!PROFILE_OTHER_LAUNCHER.equals(normalizeCoordinateProfile(explicitProfile))) return false;

        String name = root.optString("name", "").trim().toLowerCase(Locale.ROOT);
        String importedName = readImportedFileName(root).trim().toLowerCase(Locale.ROOT);
        return "default".equals(name)
                || name.contains("default touch")
                || name.contains("droidbridge default")
                || "default.json".equals(importedName)
                || "droidbridge_default.json".equals(importedName);
    }

    @NonNull
    private static String normalizeCoordinateProfile(@NonNull String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("other".equals(normalized)
                || "other_launcher".equals(normalized)
                || "other-launcher".equals(normalized)
                || "pojav".equals(normalized)
                || "pojavlauncher".equals(normalized)
                || "zalith".equals(normalized)
                || "zalithlauncher".equals(normalized)
                || "amethyst".equals(normalized)) {
            return PROFILE_OTHER_LAUNCHER;
        }
        return PROFILE_DROIDBRIDGE;
    }

    private static void forceOtherLauncherCoordinateRules(@NonNull TouchControlsLayoutData data) {
        data.coordinateProfile = PROFILE_OTHER_LAUNCHER;
        data.responsiveCanvas = false;
        // Pojav-family layouts use dp for button dimensions, but dynamic formulas and
        // legacy fixed x/y values resolve in actual screen pixels. TouchControlsOverlay
        // handles that mixed model when this profile marker is present.
        data.coordinateUnit = UNIT_DP;
        data.controlSizeUnit = UNIT_DP;
        data.sourceWidth = 0f;
        data.sourceHeight = 0f;
        data.sourceDensity = 1f;
        data.version = Math.max(data.version, 6);
    }

    private static void applyImportedPreferredScaleIfMissing(
            @NonNull JSONObject root,
            @NonNull TouchControlsLayoutData data,
            int importMode
    ) {
        if (importMode != IMPORT_MODE_OTHER_LAUNCHER) return;
        JSONObject settings = root.optJSONObject("profileSettings");
        boolean hasExplicitScale = settings != null
                ? settings.has("globalButtonScalePercent")
                : root.has("globalButtonScalePercent");
        if (hasExplicitScale) return;

        // Pojav-family scaledAt is the button-size preference active when the profile
        // was saved. Use it as DroidBridge's initial per-profile scale so the import
        // opens at the same physical size instead of inheriting another profile.
        data.globalButtonScalePercent = clampButtonScale(Math.round(data.preferredScale));
    }

    private static void readProfileSettings(
            @NonNull JSONObject root,
            @NonNull TouchControlsLayoutData data,
            boolean migrateMissingFromPreferences
    ) {
        JSONObject settings = root.optJSONObject("profileSettings");
        JSONObject source = settings != null ? settings : root;

        boolean hasOpacity = source.has("globalOpacity");
        boolean hasScale = source.has("globalButtonScalePercent");
        boolean hasVirtualMouse = source.has("virtualMouseEnabled");
        boolean hasSnap = source.has("snapControlsEnabled");

        data.globalOpacity = clampOpacity((float) source.optDouble("globalOpacity", DEFAULT_PROFILE_OPACITY));
        data.globalButtonScalePercent = clampButtonScale(source.optInt(
                "globalButtonScalePercent",
                DEFAULT_PROFILE_BUTTON_SCALE_PERCENT
        ));
        data.virtualMouseEnabled = source.optBoolean("virtualMouseEnabled", false);
        data.snapControlsEnabled = source.optBoolean("snapControlsEnabled", true);

        if (migrateMissingFromPreferences) {
            data.migrateGlobalOpacityFromPreferences = !hasOpacity;
            data.migrateGlobalButtonScaleFromPreferences = !hasScale;
            data.migrateVirtualMouseFromPreferences = !hasVirtualMouse;
            data.migrateSnapControlsFromPreferences = !hasSnap;
        }
    }

    private static float clampOpacity(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return DEFAULT_PROFILE_OPACITY;
        return Math.max(0f, Math.min(1f, value));
    }

    private static int clampButtonScale(int value) {
        return Math.max(25, Math.min(200, value));
    }

    private static void normalizeLegacyFixedPosition(
            @NonNull TouchControlData control,
            @NonNull JSONObject source,
            int legacyVersion,
            float screenWidth,
            float screenHeight
    ) {
        if (legacyVersion > 2) return;

        // compatible third-party launchers convert v1/v2 fixed positions into screen-relative
        // expressions during import. This preserves the authored placement when the
        // control layout is opened on a different resolution or aspect ratio.
        if ((control.rawX == null || control.rawX.trim().isEmpty()) && source.has("x")) {
            float ratio = (float) source.optDouble("x", control.x) / Math.max(1f, screenWidth);
            control.rawX = formatRatio(ratio) + " * ${screen_width}";
        }
        if ((control.rawY == null || control.rawY.trim().isEmpty()) && source.has("y")) {
            float ratio = (float) source.optDouble("y", control.y) / Math.max(1f, screenHeight);
            control.rawY = formatRatio(ratio) + " * ${screen_height}";
        }
    }

    @NonNull
    private static String formatRatio(float ratio) {
        if (Float.isNaN(ratio) || Float.isInfinite(ratio)) ratio = 0f;
        return String.format(Locale.US, "%.8f", ratio);
    }

    // ControlDrawerData.Orientation order used by Pojav-family launchers:
    // DOWN=0, LEFT=1, UP=2, RIGHT=3, FREE=4.
    private static int readDrawerOrientation(@NonNull JSONObject drawer) {
        Object value = drawer.opt("orientation");
        if (value instanceof Number) {
            int numeric = ((Number) value).intValue();
            return numeric >= 0 && numeric <= 4 ? numeric : 1;
        }
        if (value != null) {
            String name = String.valueOf(value).trim().toUpperCase(Locale.ROOT);
            if ("DOWN".equals(name)) return 0;
            if ("LEFT".equals(name)) return 1;
            if ("UP".equals(name)) return 2;
            if ("RIGHT".equals(name)) return 3;
            if ("FREE".equals(name)) return 4;
        }
        return 1;
    }

    @NonNull
    private static String drawerOrientationName(int orientation) {
        switch (orientation) {
            case 0: return "down";
            case 1: return "left";
            case 2: return "up";
            case 3: return "right";
            case 4: return "free";
            default: return "right";
        }
    }

    private static void applyImportedDrawerPlacement(
            @NonNull TouchControlData child,
            TouchControlData parent,
            int orientation,
            int childIndex
    ) {
        if (parent == null || orientation == 4) return;

        // Preserve Pojav/Zalith's non-FREE expanded geometry while retaining the
        // native drawer relationship. The drawer now owns child visibility at runtime,
        // but each child still occupies the exact position the source launcher computes.
        child.width = parent.width;
        child.height = parent.height;

        String parentX = expressionOrNumber(parent.rawX, parent.x);
        String parentY = expressionOrNumber(parent.rawY, parent.y);
        int step = Math.max(1, childIndex + 1);
        String horizontalOffset = "(" + step + " * (${width} + ${margin}))";
        String verticalOffset = "(" + step + " * (${height} + ${margin}))";

        switch (orientation) {
            case 0: // DOWN
                child.rawX = parentX;
                child.rawY = "(" + parentY + ") + " + verticalOffset;
                break;
            case 1: // LEFT
                child.rawX = "(" + parentX + ") - " + horizontalOffset;
                child.rawY = parentY;
                break;
            case 2: // UP
                child.rawX = parentX;
                child.rawY = "(" + parentY + ") - " + verticalOffset;
                break;
            case 3: // RIGHT
                child.rawX = "(" + parentX + ") + " + horizontalOffset;
                child.rawY = parentY;
                break;
            default:
                break;
        }
    }

    @NonNull
    private static String expressionOrNumber(String expression, float fallback) {
        if (expression != null && !expression.trim().isEmpty()) return expression.trim();
        return Float.toString(fallback);
    }

    private static void normalizePojavFamilyVersion(@NonNull TouchControlsLayoutData data, int legacyVersion) {
        // compatible third-party launchers normalize tall v6/v7 joysticks to a square while
        // preserving the old height contribution inside dynamic formulas.
        if (legacyVersion == 6 || legacyVersion == 7) {
            for (TouchControlData control : data.controls) {
                if (!TouchControlActions.JOYSTICK.equals(control.action) || control.height <= control.width) continue;
                float ratio = control.height / Math.max(1f, control.width);
                if (control.rawX != null) {
                    control.rawX = control.rawX.replace("${height}", "(" + ratio + " * ${height})");
                }
                if (control.rawY != null) {
                    control.rawY = control.rawY.replace("${height}", "(" + ratio + " * ${height})")
                            + " + (" + (ratio - 1f) + " * ${height})";
                }
                control.height = control.width;
            }
        }
    }

    private static void readSourceCanvas(@NonNull JSONObject root, @NonNull TouchControlsLayoutData data) {
        data.sourceWidth = firstPositiveFloat(root,
                "sourceWidth",
                "source_width",
                "baseWidth",
                "base_width",
                "canvasWidth",
                "canvas_width",
                "layoutWidth",
                "layout_width",
                "screenWidth",
                "screen_width",
                "displayWidth",
                "display_width",
                "deviceWidth",
                "device_width",
                "physicalWidth",
                "physical_width",
                "width"
        );
        data.sourceHeight = firstPositiveFloat(root,
                "sourceHeight",
                "source_height",
                "baseHeight",
                "base_height",
                "canvasHeight",
                "canvas_height",
                "layoutHeight",
                "layout_height",
                "screenHeight",
                "screen_height",
                "displayHeight",
                "display_height",
                "deviceHeight",
                "device_height",
                "physicalHeight",
                "physical_height",
                "height"
        );
    }

    private static boolean hasSourceCanvas(@NonNull JSONObject root) {
        return firstPositiveFloat(root,
                "sourceWidth", "source_width", "baseWidth", "base_width", "canvasWidth", "canvas_width",
                "layoutWidth", "layout_width", "screenWidth", "screen_width", "displayWidth", "display_width",
                "deviceWidth", "device_width", "physicalWidth", "physical_width", "width"
        ) > 0f && firstPositiveFloat(root,
                "sourceHeight", "source_height", "baseHeight", "base_height", "canvasHeight", "canvas_height",
                "layoutHeight", "layout_height", "screenHeight", "screen_height", "displayHeight", "display_height",
                "deviceHeight", "device_height", "physicalHeight", "physical_height", "height"
        ) > 0f;
    }

    private static float firstPositiveFloat(@NonNull JSONObject root, @NonNull String... keys) {
        for (String key : keys) {
            if (!root.has(key) || root.isNull(key)) continue;
            double value = root.optDouble(key, 0d);
            if (value > 0d && !Double.isNaN(value) && !Double.isInfinite(value)) return (float) value;
        }
        return 0f;
    }

    @NonNull
    private static String firstString(@NonNull JSONObject root, @NonNull String... keys) {
        for (String key : keys) {
            if (!root.has(key) || root.isNull(key)) continue;
            String value = root.optString(key, "");
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static boolean looksLikePixelAuthoredLayout(@NonNull TouchControlsLayoutData data) {
        float maxRight = 0f;
        float maxBottom = 0f;
        float maxWidth = 0f;
        float maxHeight = 0f;

        for (TouchControlData control : data.controls) {
            maxWidth = Math.max(maxWidth, control.width);
            maxHeight = Math.max(maxHeight, control.height);
            if (control.rawX == null) maxRight = Math.max(maxRight, control.x + Math.max(1f, control.width));
            if (control.rawY == null) maxBottom = Math.max(maxBottom, control.y + Math.max(1f, control.height));
        }

        return maxRight > 1000f
                || maxBottom > 700f
                || maxWidth > 260f
                || maxHeight > 180f;
    }

    private static boolean looksLikeImportedCanvasLayout(@NonNull TouchControlsLayoutData data) {
        // Native DroidBridge profiles use DroidBridge's own DP/pixel rules. Never infer
        // another launcher's source canvas from the profile name alone.
        if (!data.usesOtherLauncherProfile()) return false;

        String name = data.name == null ? "" : data.name.trim().toLowerCase(Locale.ROOT);
        boolean importedName = name.contains("pojav")
                || name.contains("zalith")
                || name.contains("amethyst")
                || !data.importedFileName.trim().isEmpty();
        if (!importedName) return false;

        float maxRight = 0f;
        float maxBottom = 0f;
        for (TouchControlData control : data.controls) {
            if (control.rawX == null) maxRight = Math.max(maxRight, control.x + Math.max(1f, control.width));
            if (control.rawY == null) maxBottom = Math.max(maxBottom, control.y + Math.max(1f, control.height));
        }

        // This catches the already-converted DroidBridge JSON you pasted: right edge
        // around 833 and bottom edge around 407. Those values are not normal modern
        // Android pixels; they are a source canvas that must be scaled to the current view.
        return maxRight >= 500f || maxBottom >= 250f;
    }

    private static void normalizeSuspiciousImportedSourceCanvas(@NonNull TouchControlsLayoutData data) {
        if (!data.usesPixelCoordinates() || !looksLikeImportedCanvasLayout(data)) return;

        float maxRight = 0f;
        float maxBottom = 0f;
        for (TouchControlData control : data.controls) {
            if (control.rawX == null) maxRight = Math.max(maxRight, control.x + Math.max(1f, control.width));
            if (control.rawY == null) maxBottom = Math.max(maxBottom, control.y + Math.max(1f, control.height));
        }

        if (maxRight > 1f && maxRight <= 960f && data.sourceWidth >= 1000f) {
            data.sourceWidth = inferCanvasAxis(maxRight, DEFAULT_IMPORTED_SOURCE_WIDTH, true, true);
        }
        if (maxBottom > 1f && maxBottom <= 540f && data.sourceHeight >= 700f) {
            data.sourceHeight = inferCanvasAxis(maxBottom, DEFAULT_IMPORTED_SOURCE_HEIGHT, true, false);
        }
    }


    private static void normalizeOutOfBoundsPixelCanvas(@NonNull TouchControlsLayoutData data) {
        if (!data.usesPixelCoordinates() || data.controls.isEmpty()) return;

        float minLeft = Float.MAX_VALUE;
        float minTop = Float.MAX_VALUE;
        float maxRight = 0f;
        float maxBottom = 0f;
        boolean hasAbsoluteCoordinate = false;

        for (TouchControlData control : data.controls) {
            if (control.rawX == null) {
                float width = Math.max(1f, control.width);
                minLeft = Math.min(minLeft, control.x);
                maxRight = Math.max(maxRight, control.x + width);
                hasAbsoluteCoordinate = true;
            }
            if (control.rawY == null) {
                float height = Math.max(1f, control.height);
                minTop = Math.min(minTop, control.y);
                maxBottom = Math.max(maxBottom, control.y + height);
                hasAbsoluteCoordinate = true;
            }
        }

        if (!hasAbsoluteCoordinate) return;

        float originalWidth = data.sourceWidth > 0f ? data.sourceWidth : Math.max(DEFAULT_IMPORTED_SOURCE_WIDTH, maxRight);
        float originalHeight = data.sourceHeight > 0f ? data.sourceHeight : Math.max(DEFAULT_IMPORTED_SOURCE_HEIGHT, maxBottom);
        float shiftX = minLeft < 0f ? -minLeft : 0f;
        float shiftY = minTop < 0f ? -minTop : 0f;
        float normalizedWidth = Math.max(originalWidth + shiftX, maxRight + shiftX);
        float normalizedHeight = Math.max(originalHeight + shiftY, maxBottom + shiftY);

        boolean needsNormalize = shiftX > 0f
                || shiftY > 0f
                || maxRight > originalWidth
                || maxBottom > originalHeight;
        if (!needsNormalize) return;

        if (shiftX > 0f || shiftY > 0f) {
            for (TouchControlData control : data.controls) {
                if (shiftX > 0f && control.rawX == null) control.x += shiftX;
                if (shiftY > 0f && control.rawY == null) control.y += shiftY;
            }
        }

        data.sourceWidth = Math.max(1f, normalizedWidth);
        data.sourceHeight = Math.max(1f, normalizedHeight);
        data.version = Math.max(data.version, 4);
    }

    private static void inferPixelSourceCanvasIfNeeded(@NonNull TouchControlsLayoutData data, boolean importedDroidBridgeLike) {
        if (!data.usesPixelCoordinates()) return;

        float maxRight = 0f;
        float maxBottom = 0f;
        for (TouchControlData control : data.controls) {
            if (control.rawX == null) maxRight = Math.max(maxRight, control.x + Math.max(1f, control.width));
            if (control.rawY == null) maxBottom = Math.max(maxBottom, control.y + Math.max(1f, control.height));
        }

        if (data.sourceWidth <= 0f) {
            data.sourceWidth = inferCanvasAxis(maxRight, DEFAULT_IMPORTED_SOURCE_WIDTH, importedDroidBridgeLike, true);
        }
        if (data.sourceHeight <= 0f) {
            data.sourceHeight = inferCanvasAxis(maxBottom, DEFAULT_IMPORTED_SOURCE_HEIGHT, importedDroidBridgeLike, false);
        }
    }

    private static float inferCanvasAxis(float maxExtent, float fallback, boolean preferFallback, boolean horizontal) {
        if (maxExtent <= 1f) return preferFallback ? fallback : 0f;

        // If this was a legacy Pojav-family layout and the file does not say
        // otherwise, use the common logical canvas for a 1920x1080-class xxhdpi phone
        // (about 854x480). If coordinates prove a larger source, grow to the nearest
        // common canvas size instead.
        if (preferFallback && maxExtent <= fallback) return fallback;

        float padded = maxExtent + 16f;
        float[] common = horizontal
                ? new float[]{720f, 854f, 960f, 1024f, 1280f, 1366f, 1440f, 1600f, 1920f, 2160f, 2340f, 2400f, 2560f, 2800f, 3200f, 3840f}
                : new float[]{480f, 540f, 600f, 720f, 768f, 800f, 900f, 1080f, 1200f, 1440f, 1600f, 1800f, 2160f};
        for (float candidate : common) {
            if (candidate >= padded) return candidate;
        }
        return Math.max(padded, fallback);
    }

    @NonNull
    public static TouchControlsLayoutData defaultLayout() {
        TouchControlsLayoutData data = new TouchControlsLayoutData();
        data.version = RESPONSIVE_FORMAT_VERSION;
        data.name = "Default Touch Controls";
        data.coordinateUnit = UNIT_PX;
        data.coordinateProfile = PROFILE_DROIDBRIDGE;
        data.controlSizeUnit = UNIT_PX;
        data.sourceWidth = RESPONSIVE_REFERENCE_WIDTH;
        data.sourceHeight = RESPONSIVE_REFERENCE_HEIGHT;
        data.sourceDensity = 1f;
        data.responsiveCanvas = true;

        // Author the stock layout on a 1920x1080 reference canvas and scale the
        // complete geometry from the actual game surface. Fixed dp dimensions made
        // controls extremely small on gaming phones/handhelds that expose a low
        // Android density despite a high-resolution display. Edge-aware formulas
        // keep the same 24 px reference margins on ultrawide and tablet canvases.
        TouchControlData esc = defaultKey("Esc", 256, 24, 24, 120, 48);
        setFormula(esc, "px(24)", "px(24)");
        data.controls.add(esc);

        TouchControlData chat = defaultKey("Chat", 84, 156, 24, 120, 48);
        setFormula(chat, "px(156)", "px(24)");
        data.controls.add(chat);

        TouchControlData keyboard = defaultSpecial("Keyboard", TouchControlActions.KEYBOARD, 288, 24, 168, 48);
        setFormula(keyboard, "px(288)", "px(24)");
        data.controls.add(keyboard);

        TouchControlData tab = defaultKey("Tab", 258, 468, 24, 120, 48);
        setFormula(tab, "px(468)", "px(24)");
        data.controls.add(tab);

        TouchControlData perspective = defaultKey("3rd", 294, 24, 84, 120, 48);
        setFormula(perspective, "px(24)", "px(84)");
        data.controls.add(perspective);

        TouchControlData debug = defaultKey("Debug", 292, 156, 84, 120, 48);
        setFormula(debug, "px(156)", "px(84)");
        data.controls.add(debug);

        TouchControlData mouse = defaultSpecial("Mouse", TouchControlActions.VIRTUAL_MOUSE, 1716, 24, 180, 48);
        setFormula(mouse, "${right} - px(24)", "px(24)");
        data.controls.add(mouse);

        TouchControlData scrollUp = defaultScroll("▲", 1, 1820, 84, 76, 76);
        setFormula(scrollUp, "${right} - px(24)", "px(84)");
        data.controls.add(scrollUp);

        TouchControlData scrollDown = defaultScroll("▼", -1, 1820, 172, 76, 76);
        setFormula(scrollDown, "${right} - px(24)", "px(172)");
        data.controls.add(scrollDown);

        TouchControlData gui = defaultSpecial("GUI", TouchControlActions.TOGGLE_CONTROLS, 24, 780, 84, 84);
        gui.visibleWhenControlsHidden = true;
        setFormula(gui, "px(24)", "${bottom} - px(216)");
        data.controls.add(gui);

        TouchControlData sneak = defaultKey("Sneak", 340, 120, 780, 84, 84);
        sneak.toggle = true;
        setFormula(sneak, "px(120)", "${bottom} - px(216)");
        data.controls.add(sneak);

        TouchControlData forward = defaultKey("▲", 87, 120, 876, 84, 84);
        setFormula(forward, "px(120)", "${bottom} - px(120)");
        data.controls.add(forward);

        TouchControlData left = defaultKey("◀", 65, 24, 972, 84, 84);
        setFormula(left, "px(24)", "${bottom} - px(24)");
        data.controls.add(left);

        TouchControlData back = defaultKey("▼", 83, 120, 972, 84, 84);
        setFormula(back, "px(120)", "${bottom} - px(24)");
        data.controls.add(back);

        TouchControlData right = defaultKey("▶", 68, 216, 972, 84, 84);
        setFormula(right, "px(216)", "${bottom} - px(24)");
        data.controls.add(right);

        TouchControlData attack = defaultMouse("Attack", 0, 1596, 780, 84, 84);
        setFormula(attack, "${right} - px(240)", "${bottom} - px(216)");
        data.controls.add(attack);

        TouchControlData use = defaultMouse("Use", 1, 1692, 780, 84, 84);
        setFormula(use, "${right} - px(144)", "${bottom} - px(216)");
        data.controls.add(use);

        TouchControlData inventory = defaultKey("Inv", 69, 1692, 876, 84, 84);
        setFormula(inventory, "${right} - px(144)", "${bottom} - px(120)");
        data.controls.add(inventory);

        TouchControlData jump = defaultKey("Jump", 32, 1788, 864, 108, 96);
        setFormula(jump, "${right} - px(24)", "${bottom} - px(120)");
        data.controls.add(jump);

        return data;
    }

    private static void setFormula(
            @NonNull TouchControlData control,
            @NonNull String rawX,
            @NonNull String rawY
    ) {
        control.rawX = rawX;
        control.rawY = rawY;
    }

    @NonNull
    private static TouchControlData defaultKey(
            @NonNull String label,
            int keyCode,
            float x,
            float y,
            float width,
            float height
    ) {
        TouchControlData control = TouchControlData.key(label, keyCode, x, y, width, height);
        applyDefaultVisuals(control);
        return control;
    }

    @NonNull
    private static TouchControlData defaultMouse(
            @NonNull String label,
            int mouseButton,
            float x,
            float y,
            float width,
            float height
    ) {
        TouchControlData control = TouchControlData.mouse(label, mouseButton, x, y);
        control.width = width;
        control.height = height;
        applyDefaultVisuals(control);
        return control;
    }

    @NonNull
    private static TouchControlData defaultScroll(
            @NonNull String label,
            int direction,
            float x,
            float y,
            float width,
            float height
    ) {
        TouchControlData control = defaultSpecial(label, TouchControlActions.SCROLL, x, y, width, height);
        control.scrollY = direction;
        return control;
    }

    @NonNull
    private static TouchControlData defaultSpecial(
            @NonNull String label,
            @NonNull String action,
            float x,
            float y,
            float width,
            float height
    ) {
        TouchControlData control = new TouchControlData();
        control.label = label;
        control.action = action;
        control.x = x;
        control.y = y;
        control.width = width;
        control.height = height;
        applyDefaultVisuals(control);
        return control;
    }

    private static void applyDefaultVisuals(@NonNull TouchControlData control) {
        control.opacity = 0.88f;
        control.cornerRadius = 4f;
        control.strokeWidth = 1f;
        control.strokeColor = 0xCCFFFFFF;
        control.backgroundColor = 0x66000000;
        control.visibleInGame = true;
        control.visibleInMenu = true;
    }
}

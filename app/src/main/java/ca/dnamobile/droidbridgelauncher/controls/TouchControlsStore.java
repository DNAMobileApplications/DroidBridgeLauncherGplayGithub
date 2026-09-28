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
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.DisplayMetrics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

public final class TouchControlsStore {
    private static final String TAG = "TouchControlsStore";
    private static final String DEFAULT_FILE = "droidbridge_default.json";
    private static final String DEFAULT_ASSET = "touch_controls/droidbridge_default.json";
    private static final String[] DEFAULT_ASSET_CANDIDATES = new String[]{
            DEFAULT_ASSET,
            "touch_controls/default.json",
            "touch_controls/default_touch_controls.json",
            "droidbridge_default.json",
            "default.json",
            "default_touch_controls.json"
    };

    private TouchControlsStore() {
    }

    @NonNull
    public static File getControlsDir(@NonNull Context context) {
        File dir = new File(context.getFilesDir(), "touch_controls");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return dir;
    }

    @NonNull
    public static File getDefaultLayoutFile(@NonNull Context context) {
        return new File(getControlsDir(context), DEFAULT_FILE);
    }

    @NonNull
    public static File ensureDefaultLayout(@NonNull Context context) {
        File target = getDefaultLayoutFile(context);

        // Do not overwrite this file once it exists. After first launch it is the user's
        // editable copy. If they edit the default layout, their changes must win.
        if (!target.isFile() || target.length() == 0) {
            try {
                TouchControlsLayoutData bundled = loadBundledDefaultLayout(context);
                saveLayout(target, bundled);
                Logging.i(TAG, "Created default touch controls from bundled asset: " + DEFAULT_ASSET);
            } catch (Throwable assetThrowable) {
                Logging.e(TAG, "Bundled default_touch.json missing or invalid. Falling back to emergency in-code layout.", assetThrowable);
                try {
                    saveLayout(target, TouchControlsLayoutData.defaultLayout());
                } catch (Throwable fallbackThrowable) {
                    Logging.e(TAG, "Unable to create fallback default touch controls", fallbackThrowable);
                }
            }
        }

        // Repair builds where the bundled DroidBridge default was accidentally tagged
        // as an other-launcher profile. Preserve every user-edited button and profile
        // setting; only restore the default file's coordinate identity.
        repairDefaultLayoutProfile(target);
        upgradeLegacyStockDefaultLayout(target);
        return target;
    }

    @NonNull
    public static File getSelectedLayoutFile(@NonNull Context context) {
        File defaultFile = ensureDefaultLayout(context);
        String selected = ControlsPreferences.getSelectedLayoutPath(context);
        if (selected != null) {
            File selectedFile = new File(selected);
            if (selectedFile.isFile()) return selectedFile;
        }

        ControlsPreferences.setSelectedLayoutPath(context, defaultFile.getAbsolutePath());
        return defaultFile;
    }

    @NonNull
    public static TouchControlsLayoutData loadSelectedLayout(@NonNull Context context) {
        return loadLayout(getSelectedLayoutFile(context));
    }

    @NonNull
    public static TouchControlsLayoutData loadLayout(@NonNull File file) {
        try {
            return readLayoutFromFile(file);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to read touch layout " + file.getAbsolutePath(), throwable);

            File backup = backupFileFor(file);
            if (backup.isFile()) {
                try {
                    TouchControlsLayoutData data = readLayoutFromFile(backup);
                    Logging.i(TAG, "Recovered touch layout from backup: " + backup.getAbsolutePath());
                    return data;
                } catch (Throwable backupThrowable) {
                    Logging.e(TAG, "Unable to read backup touch layout " + backup.getAbsolutePath(), backupThrowable);
                }
            }

            return TouchControlsLayoutData.defaultLayout();
        }
    }

    public static void saveLayout(@NonNull File file, @NonNull TouchControlsLayoutData data) throws Exception {
        writeTextAtomically(file, data.toJson().toString(2));
    }

    @NonNull
    private static TouchControlsLayoutData readLayoutFromFile(@NonNull File file) throws Exception {
        String text = readText(file);
        return TouchControlsLayoutData.fromJson(new JSONObject(text));
    }

    @NonNull
    public static File saveImportedLayout(@NonNull Context context, @NonNull Uri uri) throws Exception {
        return saveImportedLayout(context, uri, TouchControlsLayoutData.IMPORT_MODE_DROIDBRIDGE);
    }

    @NonNull
    public static File saveImportedLayout(@NonNull Context context, @NonNull Uri uri, int importMode) throws Exception {
        String source = readUriText(context, uri);
        DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
        TouchControlsLayoutData data = TouchControlsLayoutData.fromJson(
                new JSONObject(source),
                importMode,
                Math.max(1, displayMetrics.widthPixels),
                Math.max(1, displayMetrics.heightPixels),
                Math.max(0.1f, displayMetrics.density)
        );

        // The visible layout name should match the JSON file the user picked.
        // This makes imported Pojav-family profiles easier to identify than
        // every file showing the generic internal layout name from the JSON body.
        String importedDisplayName = displayNameForUri(context, uri);
        String importedBaseName = baseNameWithoutJson(importedDisplayName);
        if (importedBaseName.trim().isEmpty()) importedBaseName = baseNameWithoutJson(data.name);
        if (importedBaseName.trim().isEmpty()) importedBaseName = "imported_controls";
        data.name = importedBaseName.trim();
        data.importedFileName = normalizeJsonFileName(importedDisplayName.trim().isEmpty() ? importedBaseName : importedDisplayName);

        String cleanName = sanitizeFileName(importedBaseName);
        if (cleanName.isEmpty()) cleanName = "imported_controls";
        File target = uniqueFile(getControlsDir(context), cleanName, ".json");
        saveLayout(target, data);
        ControlsPreferences.setSelectedLayoutPath(context, target.getAbsolutePath());
        return target;
    }

    @NonNull
    public static List<File> listLayouts(@NonNull Context context) {
        ensureDefaultLayout(context);
        ArrayList<File> layouts = new ArrayList<>();
        File[] files = getControlsDir(context).listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().toLowerCase().endsWith(".json")) {
                    layouts.add(file);
                }
            }
        }
        return layouts;
    }

    @NonNull
    public static String readText(@NonNull File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            return readStreamText(input);
        }
    }

    @NonNull
    private static TouchControlsLayoutData loadBundledDefaultLayout(@NonNull Context context) throws Exception {
        Throwable lastError = null;

        for (String assetPath : DEFAULT_ASSET_CANDIDATES) {
            try (InputStream input = context.getAssets().open(assetPath)) {
                String text = readStreamText(input);

                // Important: parse through the same importer as manual Import.
                // This keeps DroidBridge JSON and Pojav-family
                // mControlDataList / mJoystickDataList layouts working the same way.
                TouchControlsLayoutData data = TouchControlsLayoutData.fromJson(new JSONObject(text));
                forceNativeDefaultProfile(data);
                if (data.name == null || data.name.trim().isEmpty()) {
                    data.name = "Default Touch Controls";
                }
                Logging.i(TAG, "Loaded bundled default touch controls from asset: " + assetPath);
                return data;
            } catch (Throwable throwable) {
                lastError = throwable;
            }
        }

        throw new IllegalStateException("No bundled default touch layout found. Expected " + DEFAULT_ASSET, lastError);
    }

    private static void repairDefaultLayoutProfile(@NonNull File target) {
        if (!target.isFile() || target.length() == 0) return;
        try {
            JSONObject root = new JSONObject(readText(target));
            TouchControlsLayoutData parsed = TouchControlsLayoutData.fromJson(root);
            if (!parsed.usesOtherLauncherProfile()) return;

            // Patch only the incorrect identity fields in the existing file. Do not
            // round-trip the whole object here: older profiles may still need their
            // one-time SharedPreferences-to-profileSettings migration when opened.
            root.put("coordinateProfile", TouchControlsLayoutData.PROFILE_DROIDBRIDGE);
            root.remove("importedFileName");
            writeTextAtomically(target, root.toString(2));
            Logging.i(TAG, "Repaired default touch controls coordinate profile to DroidBridge");
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to repair default touch controls coordinate profile", throwable);
        }
    }

    private static void upgradeLegacyStockDefaultLayout(@NonNull File target) {
        if (!target.isFile() || target.length() == 0) return;
        try {
            TouchControlsLayoutData legacy = readLayoutFromFile(target);
            // Replace both the old compact dp stock layout and the exact
            // version-7 1920x1080 rescaled stock layout. The replacement keeps
            // the larger geometry but adds edge-aware formulas so it adapts to
            // ultrawide phones, tablets, and handheld displays.
            boolean compactDpStock = (!legacy.usesResponsiveCanvas()
                    || legacy.usesResponsiveDpSizes())
                    && looksLikeLegacyStockDefault(legacy);
            boolean version7CanvasStock = legacy.usesResponsiveCanvas()
                    && !legacy.usesResponsiveDpSizes()
                    && looksLikeVersion7ResponsiveStockDefault(legacy);
            if (!compactDpStock && !version7CanvasStock) return;

            TouchControlsLayoutData replacement = TouchControlsLayoutData.defaultLayout();
            replacement.name = legacy.name == null || legacy.name.trim().isEmpty()
                    ? "Default Touch Controls"
                    : legacy.name;
            replacement.globalOpacity = legacy.globalOpacity;
            replacement.globalButtonScalePercent = legacy.globalButtonScalePercent;
            replacement.virtualMouseEnabled = legacy.virtualMouseEnabled;
            replacement.snapControlsEnabled = legacy.snapControlsEnabled;
            replacement.migrateGlobalOpacityFromPreferences =
                    legacy.migrateGlobalOpacityFromPreferences;
            replacement.migrateGlobalButtonScaleFromPreferences =
                    legacy.migrateGlobalButtonScaleFromPreferences;
            replacement.migrateVirtualMouseFromPreferences =
                    legacy.migrateVirtualMouseFromPreferences;
            replacement.migrateSnapControlsFromPreferences =
                    legacy.migrateSnapControlsFromPreferences;

            // Preserve stable ids and user appearance/visibility choices. Only the
            // old fixed-dp geometry and confusing stock arrangement are replaced.
            for (TouchControlData updated : replacement.controls) {
                TouchControlData previous = findMatchingStockControl(legacy, updated);
                if (previous == null) continue;
                updated.id = previous.id;
                updated.label = previous.label;
                updated.opacity = previous.opacity;
                updated.cornerRadius = previous.cornerRadius;
                updated.strokeWidth = previous.strokeWidth;
                updated.backgroundColor = previous.backgroundColor;
                updated.strokeColor = previous.strokeColor;
                updated.imageUri = previous.imageUri;
                updated.imageMode = previous.imageMode;
                updated.imageScalePercent = previous.imageScalePercent;
                updated.imageOffsetXPercent = previous.imageOffsetXPercent;
                updated.imageOffsetYPercent = previous.imageOffsetYPercent;
                updated.toggle = previous.toggle;
                updated.visibleInGame = previous.visibleInGame;
                updated.visibleInMenu = previous.visibleInMenu;
                updated.visibleWhenControlsHidden = previous.visibleWhenControlsHidden
                        || TouchControlData.shouldStayVisibleWhenControlsHiddenByDefault(updated.action);
                updated.mousePassThrough = previous.mousePassThrough;
                updated.swipeGesture = previous.swipeGesture;
                updated.migrateMousePassThroughFromPreferences =
                        previous.migrateMousePassThroughFromPreferences;
                updated.migrateSwipeGestureFromPreferences =
                        previous.migrateSwipeGestureFromPreferences;
            }

            saveLayout(target, replacement);
            Logging.i(
                    TAG,
                    "Upgraded compact stock controls to responsive pixel sizing on "
                            + Math.round(replacement.sourceWidth)
                            + "x"
                            + Math.round(replacement.sourceHeight)
                            + " position canvas"
            );
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to upgrade legacy stock default controls", throwable);
        }
    }

    private static boolean looksLikeLegacyStockDefault(@NonNull TouchControlsLayoutData data) {
        if (data.usesOtherLauncherProfile() || data.controls.size() != 19) return false;

        int recognized = 0;
        int stockSized = 0;
        for (TouchControlData control : data.controls) {
            if (isKnownStockSignature(control)) recognized++;
            boolean compactUtility = Math.abs(control.width - 80f) <= 4f
                    && Math.abs(control.height - 30f) <= 4f;
            boolean squareAction = Math.abs(control.width - 50f) <= 4f
                    && Math.abs(control.height - 50f) <= 4f;
            if (compactUtility || squareAction) stockSized++;
        }
        return recognized == 19 && stockSized >= 15;
    }

    private static boolean looksLikeVersion7ResponsiveStockDefault(
            @NonNull TouchControlsLayoutData data
    ) {
        if (data.usesOtherLauncherProfile()
                || data.controls.size() != 19
                || data.version > 7
                || Math.abs(data.sourceWidth - 1920f) > 96f
                || Math.abs(data.sourceHeight - 1080f) > 54f) {
            return false;
        }

        int recognized = 0;
        int version7Sized = 0;
        int version7Positioned = 0;
        for (TouchControlData control : data.controls) {
            if (isKnownStockSignature(control)) recognized++;
            if (matchesVersion7StockPosition(control)) version7Positioned++;

            boolean utility = Math.abs(control.width - 120f) <= 6f
                    && Math.abs(control.height - 48f) <= 4f;
            boolean wideUtility = (Math.abs(control.width - 168f) <= 8f
                    || Math.abs(control.width - 180f) <= 8f)
                    && Math.abs(control.height - 48f) <= 4f;
            boolean squareAction = Math.abs(control.width - 84f) <= 5f
                    && Math.abs(control.height - 84f) <= 5f;
            boolean scrollAction = Math.abs(control.width - 76f) <= 5f
                    && Math.abs(control.height - 76f) <= 5f;
            boolean jumpAction = Math.abs(control.width - 108f) <= 6f
                    && Math.abs(control.height - 96f) <= 6f;
            if (utility || wideUtility || squareAction || scrollAction || jumpAction) {
                version7Sized++;
            }
        }
        return recognized == 19 && version7Sized >= 17 && version7Positioned == 19;
    }

    private static boolean matchesVersion7StockPosition(@NonNull TouchControlData control) {
        if (control.rawX != null || control.rawY != null) return false;

        float expectedX;
        float expectedY;
        if (TouchControlActions.KEYBOARD.equals(control.action)) {
            expectedX = 288f;
            expectedY = 24f;
        } else if (TouchControlActions.TOGGLE_CONTROLS.equals(control.action)) {
            expectedX = 24f;
            expectedY = 780f;
        } else if (TouchControlActions.VIRTUAL_MOUSE.equals(control.action)) {
            expectedX = 1716f;
            expectedY = 24f;
        } else if (TouchControlActions.MOUSE.equals(control.action)) {
            if (control.mouseButton == 0) {
                expectedX = 1596f;
                expectedY = 780f;
            } else if (control.mouseButton == 1) {
                expectedX = 1692f;
                expectedY = 780f;
            } else {
                return false;
            }
        } else if (TouchControlActions.SCROLL.equals(control.action)) {
            expectedX = 1820f;
            if (control.scrollY == 1) expectedY = 84f;
            else if (control.scrollY == -1) expectedY = 172f;
            else return false;
        } else if (TouchControlActions.KEY.equals(control.action)) {
            switch (control.keyCode) {
                case 256: expectedX = 24f; expectedY = 24f; break;
                case 84: expectedX = 156f; expectedY = 24f; break;
                case 258: expectedX = 468f; expectedY = 24f; break;
                case 294: expectedX = 24f; expectedY = 84f; break;
                case 292: expectedX = 156f; expectedY = 84f; break;
                case 340: expectedX = 120f; expectedY = 780f; break;
                case 87: expectedX = 120f; expectedY = 876f; break;
                case 65: expectedX = 24f; expectedY = 972f; break;
                case 83: expectedX = 120f; expectedY = 972f; break;
                case 68: expectedX = 216f; expectedY = 972f; break;
                case 69: expectedX = 1692f; expectedY = 876f; break;
                case 32: expectedX = 1788f; expectedY = 864f; break;
                default: return false;
            }
        } else {
            return false;
        }

        return Math.abs(control.x - expectedX) <= 8f
                && Math.abs(control.y - expectedY) <= 8f;
    }

    private static boolean isKnownStockSignature(@NonNull TouchControlData control) {
        if (TouchControlActions.KEYBOARD.equals(control.action)
                || TouchControlActions.TOGGLE_CONTROLS.equals(control.action)
                || TouchControlActions.VIRTUAL_MOUSE.equals(control.action)) {
            return true;
        }
        if (TouchControlActions.MOUSE.equals(control.action)) {
            return control.mouseButton == 0 || control.mouseButton == 1;
        }
        if (TouchControlActions.SCROLL.equals(control.action)) {
            return control.scrollY == 1 || control.scrollY == -1;
        }
        if (!TouchControlActions.KEY.equals(control.action)) return false;

        switch (control.keyCode) {
            case 256: // Esc
            case 84:  // Chat
            case 258: // Tab
            case 294: // Perspective
            case 292: // Debug
            case 87:  // W
            case 65:  // A
            case 83:  // S
            case 68:  // D
            case 69:  // Inventory
            case 340: // Sneak
            case 32:  // Jump
                return true;
            default:
                return false;
        }
    }

    @Nullable
    private static TouchControlData findMatchingStockControl(
            @NonNull TouchControlsLayoutData data,
            @NonNull TouchControlData target
    ) {
        for (TouchControlData candidate : data.controls) {
            if (!candidate.action.equals(target.action)) continue;
            if (TouchControlActions.KEY.equals(target.action)
                    && candidate.keyCode != target.keyCode) {
                continue;
            }
            if (TouchControlActions.MOUSE.equals(target.action)
                    && candidate.mouseButton != target.mouseButton) {
                continue;
            }
            if (TouchControlActions.SCROLL.equals(target.action)
                    && candidate.scrollY != target.scrollY) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    private static void forceNativeDefaultProfile(@NonNull TouchControlsLayoutData data) {
        data.coordinateProfile = TouchControlsLayoutData.PROFILE_DROIDBRIDGE;
        data.importedFileName = "";
    }

    @NonNull
    private static String displayNameForUri(@NonNull Context context, @NonNull Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) return name.trim();
                }
            }
        } catch (Throwable ignored) {
        }

        String last = uri.getLastPathSegment();
        return last == null ? "" : last.trim();
    }

    @NonNull
    private static String normalizeJsonFileName(@NonNull String name) {
        String clean = name.trim();
        int slash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < clean.length()) clean = clean.substring(slash + 1);
        if (clean.trim().isEmpty()) clean = "imported_controls";
        if (!clean.toLowerCase().endsWith(".json")) clean += ".json";
        return clean.trim();
    }

    @NonNull
    private static String baseNameWithoutJson(@NonNull String name) {
        String clean = name.trim();
        int slash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < clean.length()) clean = clean.substring(slash + 1);
        if (clean.toLowerCase().endsWith(".json")) clean = clean.substring(0, clean.length() - 5);
        return clean.trim();
    }

    @NonNull
    private static String readUriText(@NonNull Context context, @NonNull Uri uri) throws Exception {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("Unable to open selected controls file.");
            return readStreamText(input);
        }
    }

    @NonNull
    private static String readStreamText(@NonNull InputStream input) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        StringBuilder builder = new StringBuilder();
        int read;
        while ((read = input.read(buffer)) != -1) {
            builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private static void writeTextAtomically(@NonNull File file, @NonNull String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Unable to create touch controls directory: " + parent.getAbsolutePath());
        }

        File directory = parent != null ? parent : new File(".");
        File temp = new File(directory, file.getName() + ".tmp");
        File backup = backupFileFor(file);

        writeText(temp, text);

        if (file.isFile()) {
            try {
                copyFile(file, backup);
            } catch (Throwable backupThrowable) {
                Logging.e(TAG, "Unable to update touch layout backup " + backup.getAbsolutePath(), backupThrowable);
            }
        }

        if (file.exists() && !file.delete()) {
            // Some file systems refuse delete immediately after the copy. Fall back
            // to overwriting the target after the temp file has been fully synced.
            copyFile(temp, file);
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return;
        }

        if (!temp.renameTo(file)) {
            copyFile(temp, file);
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private static void writeText(@NonNull File file, @NonNull String text) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            output.write(bytes);
            output.flush();
            try {
                output.getFD().sync();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void copyFile(@NonNull File source, @NonNull File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Unable to create touch controls directory: " + parent.getAbsolutePath());
        }

        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target, false)) {
            copy(input, output);
            output.flush();
            try {
                output.getFD().sync();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void copy(@NonNull InputStream input, @NonNull OutputStream output) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    @NonNull
    private static File backupFileFor(@NonNull File file) {
        File parent = file.getParentFile();
        File directory = parent != null ? parent : new File(".");
        return new File(directory, file.getName() + ".bak");
    }

    @NonNull
    private static File uniqueFile(@NonNull File dir, @NonNull String base, @NonNull String suffix) {
        File file = new File(dir, base + suffix);
        int index = 2;
        while (file.exists()) {
            file = new File(dir, base + "_" + index + suffix);
            index++;
        }
        return file;
    }

    @NonNull
    private static String sanitizeFileName(@NonNull String name) {
        return name.trim().replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("_+", "_");
    }
}

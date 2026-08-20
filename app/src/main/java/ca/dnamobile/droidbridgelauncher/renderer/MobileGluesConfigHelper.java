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

package ca.dnamobile.droidbridgelauncher.renderer;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.system.Os;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

public final class MobileGluesConfigHelper {
    private static final String TAG = "MobileGluesConfig";

    private static final String CONFIG_DIR_NAME = "MG";
    private static final String CONFIG_FILE_NAME = "config.json";
    private static final String LOCAL_LAUNCH_DIR_NAME = "MobileGlues";

    private static final String PREFS_NAME = "mobile_glues_config";
    private static final String PREF_TREE_URI = "mg_tree_uri";

    private static final String[] MOBILE_GLUES_PACKAGES = new String[]{
            "com.fcl.plugin.mobileglues",
            "com.fcl.plugin.renderer.mobileglues",
    };

    private MobileGluesConfigHelper() {
    }

    public static final class SelectionResult {
        public final boolean success;
        @NonNull public final String message;
        @Nullable public final Uri treeUri;
        @Nullable public final File launchConfigDirectory;

        private SelectionResult(
                boolean success,
                @NonNull String message,
                @Nullable Uri treeUri,
                @Nullable File launchConfigDirectory
        ) {
            this.success = success;
            this.message = message;
            this.treeUri = treeUri;
            this.launchConfigDirectory = launchConfigDirectory;
        }
    }

    public static boolean isMobileGluesRenderer(@Nullable RendererInterface renderer) {
        if (renderer == null) return false;
        String combined = (safe(renderer.getUniqueIdentifier()) + " "
                + safe(renderer.getRendererName()) + " "
                + safe(renderer.getRendererId()) + " "
                + safe(renderer.getRendererLibrary()) + " "
                + safe(renderer.getRendererEGL()))
                .toLowerCase(Locale.ROOT);
        return combined.contains("mobileglues") || combined.contains("mobile glues") || combined.contains("libmobileglues");
    }

    @NonNull
    public static File getConfigDirectory() {
        return new File(Environment.getExternalStorageDirectory(), CONFIG_DIR_NAME);
    }

    @NonNull
    public static File getConfigFile() {
        return new File(getConfigDirectory(), CONFIG_FILE_NAME);
    }

    @NonNull
    public static File getLaunchConfigDirectory(@NonNull Context context) {
        return new File(context.getApplicationContext().getFilesDir(), LOCAL_LAUNCH_DIR_NAME);
    }

    @NonNull
    public static File getLaunchConfigFile(@NonNull Context context) {
        return new File(getLaunchConfigDirectory(context), CONFIG_FILE_NAME);
    }

    public static boolean hasStorageAccess(@NonNull Context context) {
        return canReadConfigFile()
                || findSafConfigFile(context) != null
                || getLaunchConfigFile(context).isFile();
    }

    public static boolean shouldShowStorageAccessPrompt(
            @NonNull Context context,
            @Nullable RendererInterface renderer
    ) {
        return isMobileGluesRenderer(renderer) && !hasStorageAccess(context);
    }

    public static boolean canReadConfigFile() {
        File configFile = getConfigFile();
        return configFile.isFile() && configFile.canRead();
    }

    public static boolean hasSelectedConfigTree(@NonNull Context context) {
        return getSelectedConfigTreeUri(context) != null;
    }

    @NonNull
    public static SelectionResult setSelectedConfigTreeUri(@NonNull Context context, @Nullable Uri treeUri) {
        Context appContext = context.getApplicationContext();
        if (treeUri == null) {
            getPrefs(appContext).edit().remove(PREF_TREE_URI).apply();
            return new SelectionResult(false, "MobileGlues folder selection cleared.", null, null);
        }

        DocumentFile picked = documentFromTreeUri(appContext, treeUri);
        if (picked == null || !picked.exists() || !picked.isDirectory()) {
            return new SelectionResult(false, "That folder could not be opened. Choose the MG folder again.", treeUri, null);
        }

        DocumentFile config = findConfigFileInTree(appContext, treeUri);
        if (config == null) {
            return new SelectionResult(
                    false,
                    "No config.json was found. Choose the MG folder at the root of internal storage: /storage/emulated/0/MG.",
                    treeUri,
                    null
            );
        }

        getPrefs(appContext).edit().putString(PREF_TREE_URI, treeUri.toString()).apply();

        try {
            File launchDir = prepareLaunchConfig(appContext);
            return new SelectionResult(
                    true,
                    "MobileGlues MG folder saved. Launches will use: " + launchDir.getAbsolutePath(),
                    treeUri,
                    launchDir
            );
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to mirror selected MobileGlues config", throwable);
            return new SelectionResult(
                    false,
                    "MG folder was saved, but config.json could not be mirrored: " + safeMessage(throwable),
                    treeUri,
                    null
            );
        }
    }

    @Nullable
    public static Uri getSelectedConfigTreeUri(@NonNull Context context) {
        String value = getPrefs(context).getString(PREF_TREE_URI, null);
        if (value == null || value.trim().isEmpty()) return null;
        try {
            return Uri.parse(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    public static DocumentFile getSelectedConfigTree(@NonNull Context context) {
        Uri treeUri = getSelectedConfigTreeUri(context);
        return treeUri != null ? documentFromTreeUri(context, treeUri) : null;
    }

    @NonNull
    public static File prepareLaunchConfig(@NonNull Context context) throws Exception {
        Context appContext = context.getApplicationContext();
        File launchDir = getLaunchConfigDirectory(appContext);
        if (!launchDir.exists() && !launchDir.mkdirs() && !launchDir.isDirectory()) {
            throw new IllegalStateException("Unable to create MobileGlues launch config folder: " + launchDir.getAbsolutePath());
        }

        String jsonText = readBestConfigJson(appContext);
        if (jsonText == null || jsonText.trim().isEmpty()) {
            throw new IllegalStateException("MobileGlues config.json could not be read from the selected MG folder.");
        }

        // Mirror the user-selected file exactly as supplied. DroidBridge only bridges
        // Android SAF storage to a native-readable directory; it must not add, remove,
        // or override any MobileGlues setting.
        writeFile(getLaunchConfigFile(appContext), jsonText);
        Logging.i(TAG, "Mirrored user MobileGlues config without changing values: source="
                + describeConfigSource(appContext)
                + " destination=" + getLaunchConfigFile(appContext).getAbsolutePath()
                + " size=" + getLaunchConfigFile(appContext).length());

        return launchDir;
    }

    @NonNull
    public static File applyLaunchEnvironment(@NonNull Context context) throws Exception {
        File launchDir = prepareLaunchConfig(context);
        applyLaunchEnvironment(context, launchDir);
        return launchDir;
    }

    public static void applyLaunchEnvironment(@NonNull Context context, @NonNull File launchDir) throws Exception {
        if (!launchDir.exists() && !launchDir.mkdirs() && !launchDir.isDirectory()) {
            throw new IllegalStateException("Unable to create MobileGlues launch config folder: " + launchDir.getAbsolutePath());
        }
        Os.setenv("MG_DIR_PATH", launchDir.getAbsolutePath(), true);
        Logging.i(TAG, "Applied MobileGlues MG_DIR_PATH=" + launchDir.getAbsolutePath()
                + " configExists=" + new File(launchDir, CONFIG_FILE_NAME).isFile());
    }

    public static void addMgPickerHints(@NonNull Intent intent) {
        intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
        try {
            Uri initialUri = DocumentsContract.buildTreeDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:" + CONFIG_DIR_NAME
            );
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
        } catch (Throwable ignored) {
        }
    }

    @NonNull
    public static Intent buildStorageAccessIntent(@NonNull Context context) {
        Intent pluginIntent = buildOpenPluginIntent(context);
        if (pluginIntent != null) return pluginIntent;

        Intent fallback = new Intent(Intent.ACTION_MAIN);
        fallback.addCategory(Intent.CATEGORY_LAUNCHER);
        fallback.setPackage(MOBILE_GLUES_PACKAGES[0]);
        fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return fallback;
    }

    @Nullable
    public static Intent buildOpenPluginIntent(@NonNull Context context) {
        PackageManager packageManager = context.getPackageManager();
        for (String packageName : MOBILE_GLUES_PACKAGES) {
            try {
                Intent intent = packageManager.getLaunchIntentForPackage(packageName);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    return intent;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    @NonNull
    public static String buildSettingsSummary(@NonNull Context context, @Nullable RendererInterface renderer) {
        if (!isMobileGluesRenderer(renderer)) {
            return "No external MobileGlues configuration is used by this renderer.";
        }

        Context appContext = context.getApplicationContext();
        String sourceLabel = "";
        String jsonText = null;

        try {
            DocumentFile safFile = findSafConfigFile(appContext);
            if (safFile != null) {
                jsonText = readDocumentFile(appContext, safFile);
                sourceLabel = "Selected MG folder config: " + safe(safFile.getUri() != null ? safFile.getUri().toString() : "");
            } else if (canReadConfigFile()) {
                jsonText = readFile(getConfigFile());
                sourceLabel = "Direct config: " + getConfigFile().getAbsolutePath();
            } else if (getLaunchConfigFile(appContext).isFile()) {
                jsonText = readFile(getLaunchConfigFile(appContext));
                sourceLabel = "Mirrored launch config: " + getLaunchConfigFile(appContext).getAbsolutePath();
            }
        } catch (Throwable throwable) {
            return "MobileGlues config could not be read: " + safeMessage(throwable)
                    + "\nExpected MG folder: " + getConfigDirectory().getAbsolutePath()
                    + "\nLaunch MG_DIR_PATH: " + getLaunchConfigDirectory(appContext).getAbsolutePath();
        }

        if (jsonText == null) {
            return "MobileGlues config not found."
                    + "\nChoose the MG folder at: " + getConfigDirectory().getAbsolutePath()
                    + "\nSelected SAF folder: " + safe(getSelectedConfigTreeUri(appContext) != null ? getSelectedConfigTreeUri(appContext).toString() : "None")
                    + "\nLaunch MG_DIR_PATH: " + getLaunchConfigDirectory(appContext).getAbsolutePath();
        }

        try {
            File launchDir = getLaunchConfigDirectory(appContext);
            JSONObject json = new JSONObject(jsonText);
            StringBuilder out = new StringBuilder();
            out.append(sourceLabel).append('\n');
            out.append("Launch MG_DIR_PATH: ").append(launchDir.getAbsolutePath()).append('\n');
            appendKnown(out, json, "enableANGLE", "ANGLE");
            appendKnown(out, json, "enableAngle", "ANGLE");
            appendKnown(out, json, "enableNoError", "Ignore GL errors");
            appendKnown(out, json, "ignoreError", "Ignore GL errors");
            appendKnown(out, json, "enableExtGL43", "OpenGL 4.3 extension set");
            appendKnown(out, json, "enableExtComputeShader", "ARB_compute_shader");
            appendKnown(out, json, "enableExtTimerQuery", "timer_query");
            appendKnown(out, json, "enableExtDirectStateAccess", "direct_state_access");
            appendKnown(out, json, "maxGlslCacheSize", "GLSL cache MB");
            appendKnown(out, json, "multidrawMode", "MultiDraw mode");
            appendKnown(out, json, "angleDepthClearFixMode", "ANGLE depth clear fix");
            appendKnown(out, json, "bufferCoherentAsFlush", "Coherent buffer as flush");
            appendKnown(out, json, "customGLVersion", "Custom GL version");
            appendKnown(out, json, "fsr1Setting", "FSR1");
            appendKnown(out, json, "hideMGEnvLevel", "Hide environment level");

            boolean hasUnknown = false;
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (isKnownKey(key)) continue;
                if (!hasUnknown) {
                    out.append("Other values:\n");
                    hasUnknown = true;
                }
                out.append("• ").append(key).append(" = ").append(json.opt(key)).append('\n');
            }
            return out.toString().trim();
        } catch (Throwable throwable) {
            return sourceLabel
                    + "\nThe config exists, but DroidBridge could not parse it: " + safeMessage(throwable)
                    + "\nLaunch MG_DIR_PATH: " + getLaunchConfigDirectory(appContext).getAbsolutePath();
        }
    }


    @NonNull
    private static String describeConfigSource(@NonNull Context context) {
        DocumentFile safFile = findSafConfigFile(context);
        if (safFile != null && safFile.getUri() != null) {
            return safFile.getUri().toString();
        }
        if (canReadConfigFile()) {
            return getConfigFile().getAbsolutePath();
        }
        File mirrored = getLaunchConfigFile(context);
        return mirrored.isFile() ? mirrored.getAbsolutePath() : "unavailable";
    }

    @Nullable
    private static String readBestConfigJson(@NonNull Context context) throws Exception {
        DocumentFile safFile = findSafConfigFile(context);
        if (safFile != null) return readDocumentFile(context, safFile);
        if (canReadConfigFile()) return readFile(getConfigFile());
        File mirrored = getLaunchConfigFile(context);
        return mirrored.isFile() ? readFile(mirrored) : null;
    }

    @Nullable
    private static DocumentFile findSafConfigFile(@NonNull Context context) {
        Uri treeUri = getSelectedConfigTreeUri(context);
        return treeUri != null ? findConfigFileInTree(context, treeUri) : null;
    }

    @Nullable
    private static DocumentFile findConfigFileInTree(@NonNull Context context, @NonNull Uri treeUri) {
        DocumentFile root = documentFromTreeUri(context, treeUri);
        if (root == null || !root.exists() || !root.isDirectory()) return null;

        DocumentFile direct = root.findFile(CONFIG_FILE_NAME);
        if (direct != null && direct.isFile()) return direct;

        DocumentFile mgDir = root.findFile(CONFIG_DIR_NAME);
        if (mgDir != null && mgDir.isDirectory()) {
            DocumentFile nested = mgDir.findFile(CONFIG_FILE_NAME);
            if (nested != null && nested.isFile()) return nested;
        }

        return null;
    }

    @Nullable
    private static DocumentFile documentFromTreeUri(@NonNull Context context, @NonNull Uri treeUri) {
        try {
            return DocumentFile.fromTreeUri(context.getApplicationContext(), treeUri);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void appendKnown(
            @NonNull StringBuilder out,
            @NonNull JSONObject json,
            @NonNull String key,
            @NonNull String label
    ) {
        if (!json.has(key)) return;
        out.append("• ").append(label).append(" = ").append(json.opt(key)).append('\n');
    }

    private static boolean isKnownKey(@NonNull String key) {
        return "enableANGLE".equals(key)
                || "enableAngle".equals(key)
                || "enableNoError".equals(key)
                || "ignoreError".equals(key)
                || "enableExtGL43".equals(key)
                || "enableExtComputeShader".equals(key)
                || "enableExtTimerQuery".equals(key)
                || "enableExtDirectStateAccess".equals(key)
                || "maxGlslCacheSize".equals(key)
                || "multidrawMode".equals(key)
                || "angleDepthClearFixMode".equals(key)
                || "bufferCoherentAsFlush".equals(key)
                || "customGLVersion".equals(key)
                || "fsr1Setting".equals(key)
                || "hideMGEnvLevel".equals(key);
    }

    @NonNull
    private static String readFile(@NonNull File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    @NonNull
    private static String readDocumentFile(@NonNull Context context, @NonNull DocumentFile file) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        try (InputStream input = resolver.openInputStream(file.getUri());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IllegalStateException("Could not open selected MobileGlues config.");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void writeFile(@NonNull File file, @NonNull String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("Unable to create directory: " + parent.getAbsolutePath());
        }
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    @NonNull
    private static SharedPreferences getPrefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value;
    }

    @NonNull
    private static String safeMessage(@NonNull Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty() ? throwable.toString() : message;
    }
}

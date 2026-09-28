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

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;
import ca.dnamobile.droidbridgelauncher.runtime.Architecture;

public final class RendererPluginManager {
    private static final String TAG = "RendererPluginManager";

    private static final String[] KNOWN_RENDERER_PLUGIN_PACKAGES = new String[]{
            "com.fcl.plugin.mobileglues",
            "com.bzlzhh.plugin.ngg",
            "com.bzlzhh.plugin.ngg.angleless",
            "com.fcl.plugin.renderer.mobileglues",
            "com.fcl.plugin.renderer.ltw",
            "com.fcl.plugin.renderer.gl4es",
            "com.fcl.plugin.renderer.angle",
            "com.fcl.plugin.renderer.mesa"
    };

    private static final String[] META_RENDERER_ID = new String[]{
            "renderer_id", "rendererId", "RENDERER_ID", "com.movtery.zalithlauncher.renderer.ID", "ca.dnamobile.droidbridgelauncher.runtime.renderer.ID"
    };
    private static final String[] META_RENDERER_NAME = new String[]{
            "renderer_name", "rendererName", "RENDERER_NAME", "com.movtery.zalithlauncher.renderer.NAME", "ca.dnamobile.droidbridgelauncher.runtime.renderer.NAME"
    };
    private static final String[] META_RENDERER_DESC = new String[]{
            "renderer_description", "rendererDescription", "RENDERER_DESCRIPTION", "com.movtery.zalithlauncher.renderer.DESCRIPTION", "ca.dnamobile.droidbridgelauncher.runtime.renderer.DESCRIPTION"
    };
    private static final String[] META_RENDERER_LIBRARY = new String[]{
            "renderer_library", "rendererLibrary", "RENDERER_LIBRARY", "com.movtery.zalithlauncher.renderer.LIBRARY", "ca.dnamobile.droidbridgelauncher.runtime.renderer.LIBRARY"
    };
    private static final String[] META_RENDERER_EGL = new String[]{
            "renderer_egl", "rendererEGL", "RENDERER_EGL", "com.movtery.zalithlauncher.renderer.EGL", "ca.dnamobile.droidbridgelauncher.runtime.renderer.EGL"
    };
    private static final String[] META_RENDERER_ENV = new String[]{
            "renderer_env", "rendererEnv", "RENDERER_ENV", "com.movtery.zalithlauncher.renderer.ENV", "ca.dnamobile.droidbridgelauncher.runtime.renderer.ENV"
    };
    private static final String[] META_RENDERER_DLOPEN = new String[]{
            "renderer_dlopen", "rendererDlopen", "RENDERER_DLOPEN", "com.movtery.zalithlauncher.renderer.DLOPEN", "ca.dnamobile.droidbridgelauncher.runtime.renderer.DLOPEN"
    };

    private RendererPluginManager() {
    }

    @NonNull
    public static List<RendererInterface> discoverPlugins(@NonNull Context context) {
        PathManager.initContextConstants(context);
        LinkedHashMap<String, RendererInterface> discovered = new LinkedHashMap<>();

        for (String packageName : KNOWN_RENDERER_PLUGIN_PACKAGES) {
            RendererInterface renderer = loadInstalledPackagePlugin(context, packageName);
            if (renderer != null) discovered.put(renderer.getUniqueIdentifier(), renderer);
        }

        // Best effort fallback. On Android 11+ this only returns packages visible to this app.
        // Keep the explicit <queries><package .../></queries> entries in AndroidManifest.xml.
        for (PackageInfo packageInfo : getInstalledPackages(context)) {
            String packageName = packageInfo.packageName;
            if (packageName == null || discovered.containsKey(packageName)) continue;
            if (!looksLikeRendererPlugin(packageName, packageInfo)) continue;

            RendererInterface renderer = buildPluginRenderer(context, packageInfo, extractInstalledNativeLibDir(packageInfo));
            if (renderer != null) discovered.put(renderer.getUniqueIdentifier(), renderer);
        }

        return new ArrayList<>(discovered.values());
    }

    /**
     * Kept for old builds/tests, but the Settings UI no longer uses APK import.
     * Installed plugin apps are preferred so their own settings and storage access remain valid.
     */
    @Deprecated
    @NonNull
    public static RendererInterface importRendererApk(@NonNull Context context, @NonNull Uri uri) throws Exception {
        PathManager.initContextConstants(context);
        File pluginRoot = PathManager.DIR_INSTALLED_RENDERER_PLUGIN;
        if (!pluginRoot.exists() && !pluginRoot.mkdirs()) {
            throw new IllegalStateException("Unable to create renderer plugin directory: " + pluginRoot.getAbsolutePath());
        }

        File tempApk = new File(pluginRoot, "importing_renderer_plugin.apk");
        copyUriToFile(context, uri, tempApk);

        PackageInfo packageInfo = getArchivePackageInfo(context, tempApk);
        if (packageInfo == null || packageInfo.packageName == null || packageInfo.packageName.trim().isEmpty()) {
            //noinspection ResultOfMethodCallIgnored
            tempApk.delete();
            throw new IllegalStateException("Selected file is not a valid Android APK plugin.");
        }

        String safeName = packageInfo.packageName.replaceAll("[^A-Za-z0-9_.-]", "_");
        File finalApk = new File(pluginRoot, safeName + ".apk");
        if (finalApk.exists() && !finalApk.delete()) {
            throw new IllegalStateException("Unable to replace old plugin APK: " + finalApk.getAbsolutePath());
        }
        if (!tempApk.renameTo(finalApk)) {
            copyFile(tempApk, finalApk);
            //noinspection ResultOfMethodCallIgnored
            tempApk.delete();
        }

        File libDir = extractNativeLibraries(finalApk, packageInfo.packageName);
        RendererInterface renderer = buildPluginRenderer(context, getArchivePackageInfo(context, finalApk), libDir);
        if (renderer == null) {
            throw new IllegalStateException("Could not find renderer metadata or native libraries inside " + finalApk.getName());
        }
        Logging.i(TAG, "Imported renderer APK: " + renderer.getRendererName() + " (" + renderer.getUniqueIdentifier() + ")");
        return renderer;
    }

    public static boolean hasImportedOrCachedRendererPlugins(@NonNull Context context) {
        PathManager.initContextConstants(context);
        File root = PathManager.DIR_INSTALLED_RENDERER_PLUGIN;
        File[] files = root != null && root.isDirectory() ? root.listFiles() : null;
        return files != null && files.length > 0;
    }

    public static void clearImportedAndCachedRendererPlugins(@NonNull Context context) {
        PathManager.initContextConstants(context);
        File root = PathManager.DIR_INSTALLED_RENDERER_PLUGIN;
        if (root == null || !root.exists()) return;
        clearDirectory(root);
    }

    public static boolean isStorageConfigurablePlugin(@Nullable RendererInterface renderer) {
        if (renderer == null || !renderer.isExternalPlugin()) return false;
        String combined = (renderer.getUniqueIdentifier() + " "
                + renderer.getRendererName() + " "
                + renderer.getRendererId()).toLowerCase(Locale.ROOT);
        return combined.contains("mobileglues") || combined.contains("mobile glues");
    }

    public static boolean openPluginApp(@NonNull Context context, @Nullable RendererInterface renderer) {
        if (renderer == null || !renderer.isExternalPlugin()) return false;
        String packageName = renderer.getUniqueIdentifier();
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent == null) return openPluginAppSettings(context, renderer);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to open renderer plugin app: " + packageName, throwable);
            return openPluginAppSettings(context, renderer);
        }
    }

    public static boolean openPluginAppSettings(@NonNull Context context, @Nullable RendererInterface renderer) {
        if (renderer == null || !renderer.isExternalPlugin()) return false;
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + renderer.getUniqueIdentifier()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to open renderer plugin app settings: " + renderer.getUniqueIdentifier(), throwable);
            return false;
        }
    }

    @Nullable
    private static RendererInterface loadInstalledPackagePlugin(@NonNull Context context, @NonNull String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info;
            long flags = PackageManager.GET_META_DATA | PackageManager.GET_ACTIVITIES;
            if (Build.VERSION.SDK_INT >= 33) {
                info = pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags));
            } else {
                //noinspection deprecation
                info = pm.getPackageInfo(packageName, (int) flags);
            }
            return buildPluginRenderer(context, info, extractInstalledNativeLibDir(info));
        } catch (Throwable throwable) {
            Logging.i(TAG, "Renderer plugin not installed or not visible: " + packageName);
            return null;
        }
    }

    @Nullable
    private static File extractInstalledNativeLibDir(@NonNull PackageInfo info) {
        ApplicationInfo appInfo = info.applicationInfo;
        File nativeDir = getInstalledNativeLibraryDir(appInfo);

        /*
         * Android 10 keeps native libraries from separately installed APKs in a
         * linker namespace that the embedded OpenJDK process cannot reliably
         * reopen by absolute path. LTW works when Android/ART preloads it, but
         * Minecraft 26.3/LWJGL 3.4 initializes org.lwjgl.opengl.GL before the
         * SDL3 bridge and attempts to dlopen the renderer again from OpenJDK.
         * On the LG G7 that cross-APK reopen fails and LWJGL reports that no
         * OpenGL context-management API is available.
         *
         * DroidBridge already has a renderer-plugin extraction path for APKs
         * whose nativeLibraryDir is unavailable. Reuse that same local staging
         * path for the installed LTW plugin on API 29 and older so every launch
         * layer (LWJGL, SDL3 and the native bridge) resolves one DroidBridge-
         * owned libltw.so instead of crossing the plugin APK namespace.
         */
        boolean android10LtwNamespaceCompat = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q
                && info.packageName != null
                && info.packageName.toLowerCase(Locale.ROOT).contains("ltw");
        if (android10LtwNamespaceCompat && appInfo != null) {
            for (File apkFile : getPackageApkFiles(appInfo)) {
                try {
                    File extracted = extractNativeLibraries(apkFile, info.packageName);
                    if (hasSharedLibraries(extracted)) {
                        Logging.i(TAG, "Android 10 LTW namespace compatibility using extracted plugin natives: "
                                + extracted.getAbsolutePath());
                        return extracted;
                    }
                } catch (Throwable throwable) {
                    Logging.e(TAG, "Android 10 LTW plugin extraction failed from "
                            + apkFile.getAbsolutePath(), throwable);
                }
            }
            Logging.i(TAG, "Android 10 LTW namespace compatibility could not extract plugin; "
                    + "falling back to installed nativeLibraryDir="
                    + (nativeDir != null ? nativeDir.getAbsolutePath() : "<none>"));
        }

        if (hasSharedLibraries(nativeDir)) return nativeDir;

        if (appInfo == null) return nativeDir;
        for (File apkFile : getPackageApkFiles(appInfo)) {
            try {
                File extracted = extractNativeLibraries(apkFile, info.packageName);
                if (hasSharedLibraries(extracted)) return extracted;
            } catch (Throwable throwable) {
                Logging.e(TAG, "Failed to extract renderer plugin libraries from " + apkFile.getAbsolutePath(), throwable);
            }
        }

        return nativeDir;
    }

    @NonNull
    private static List<File> getPackageApkFiles(@NonNull ApplicationInfo appInfo) {
        ArrayList<File> files = new ArrayList<>();
        addApkFile(files, appInfo.publicSourceDir);
        addApkFile(files, appInfo.sourceDir);
        if (appInfo.splitPublicSourceDirs != null) {
            for (String path : appInfo.splitPublicSourceDirs) addApkFile(files, path);
        }
        if (appInfo.splitSourceDirs != null) {
            for (String path : appInfo.splitSourceDirs) addApkFile(files, path);
        }
        return files;
    }

    private static void addApkFile(@NonNull List<File> files, @Nullable String path) {
        if (path == null || path.trim().isEmpty()) return;
        File file = new File(path);
        if (!file.isFile()) return;
        for (File existing : files) {
            if (existing.getAbsolutePath().equals(file.getAbsolutePath())) return;
        }
        files.add(file);
    }

    private static List<PackageInfo> getInstalledPackages(@NonNull Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            long flags = PackageManager.GET_META_DATA | PackageManager.GET_ACTIVITIES;
            if (Build.VERSION.SDK_INT >= 33) {
                return pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags));
            }
            //noinspection deprecation
            return pm.getInstalledPackages((int) flags);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to enumerate installed renderer plugins", throwable);
            return Collections.emptyList();
        }
    }

    @Nullable
    private static PackageInfo getArchivePackageInfo(@NonNull Context context, @NonNull File apkFile) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= 33) {
                info = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA));
            } else {
                //noinspection deprecation
                info = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), PackageManager.GET_META_DATA);
            }
            if (info != null && info.applicationInfo != null) {
                info.applicationInfo.sourceDir = apkFile.getAbsolutePath();
                info.applicationInfo.publicSourceDir = apkFile.getAbsolutePath();
            }
            return info;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to parse renderer plugin APK: " + apkFile.getAbsolutePath(), throwable);
            return null;
        }
    }

    private static boolean looksLikeRendererPlugin(@NonNull String packageName, @NonNull PackageInfo packageInfo) {
        String lower = packageName.toLowerCase(Locale.ROOT);
        if (lower.contains("renderer")
                || lower.contains("mobileglues")
                || lower.contains("ngg")
                || lower.contains("gl4es")
                || lower.contains("ltw")
                || lower.contains("mesa")) {
            return true;
        }
        Bundle meta = packageInfo.applicationInfo != null ? packageInfo.applicationInfo.metaData : null;
        return readMeta(meta, META_RENDERER_LIBRARY) != null || readMeta(meta, META_RENDERER_ID) != null;
    }

    @Nullable
    private static RendererInterface buildPluginRenderer(
            @NonNull Context context,
            @Nullable PackageInfo packageInfo,
            @Nullable File forcedNativeLibDir
    ) {
        if (packageInfo == null || packageInfo.packageName == null) return null;
        String packageName = packageInfo.packageName;
        ApplicationInfo appInfo = packageInfo.applicationInfo;
        Bundle meta = appInfo != null ? appInfo.metaData : null;

        File nativeLibDir = forcedNativeLibDir != null ? forcedNativeLibDir : getInstalledNativeLibraryDir(appInfo);
        if (nativeLibDir == null || !nativeLibDir.isDirectory()) {
            Logging.i(TAG, "Renderer plugin has no native library directory: " + packageName);
        }

        ArrayList<File> nativeDirs = new ArrayList<>();
        if (nativeLibDir != null && nativeLibDir.isDirectory()) nativeDirs.add(nativeLibDir);

        String rendererId = firstNonEmpty(readMeta(meta, META_RENDERER_ID), inferRendererId(packageName));
        String rendererName = firstNonEmpty(readMeta(meta, META_RENDERER_NAME), getApplicationLabel(context, appInfo), inferRendererName(packageName));
        String rendererDescription = firstNonEmpty(readMeta(meta, META_RENDERER_DESC), "Installed renderer plugin: " + packageName);
        String rendererLibraryName = firstNonEmpty(readMeta(meta, META_RENDERER_LIBRARY), inferRendererLibraryName(packageName, nativeLibDir));
        String rendererEgl = firstNonEmpty(readMeta(meta, META_RENDERER_EGL), inferRendererEgl(packageName, rendererLibraryName));

        if (rendererLibraryName == null || rendererLibraryName.trim().isEmpty()) {
            Logging.i(TAG, "Renderer plugin skipped because no renderer library was found: " + packageName);
            return null;
        }

        File rendererLibrary = resolveLibraryFile(rendererLibraryName, nativeLibDir);
        String rendererLibraryValue = rendererLibrary != null && rendererLibrary.isFile()
                ? rendererLibrary.getAbsolutePath()
                : rendererLibraryName;

        LinkedHashMap<String, String> env = parseEnv(readMeta(meta, META_RENDERER_ENV));
        applyInferredEnv(packageName, env);

        ArrayList<String> dlopen = parseList(readMeta(meta, META_RENDERER_DLOPEN));
        addNativeLibrariesToDlopen(dlopen, nativeLibDir, rendererLibraryValue);

        return new PluginRenderer(
                rendererId,
                packageName,
                rendererName,
                rendererDescription,
                env,
                dlopen,
                rendererLibraryValue,
                rendererEgl,
                nativeDirs
        );
    }

    private static void applyInferredEnv(@NonNull String packageName, @NonNull LinkedHashMap<String, String> env) {
        String lower = packageName.toLowerCase(Locale.ROOT);
        if (lower.contains("mobileglues") || lower.contains("mobile glues")) {
            putIfMissing(env, "LIBGL_ES", "3");
            putIfMissing(env, "MG_DIR_PATH", "/sdcard/MG");
        } else if (lower.contains("ngg") || lower.contains("krypton")) {
            putIfMissing(env, "LIBGL_USE_MC_COLOR", "1");
            putIfMissing(env, "LIBGL_GL", "31");
            putIfMissing(env, "LIBGL_ES", "3");
            putIfMissing(env, "LIBGL_NORMALIZE", "1");
            putIfMissing(env, "LIBGL_NOERROR", "1");
        } else if (lower.contains("gl4es")) {
            putIfMissing(env, "LIBGL_USE_MC_COLOR", "1");
            putIfMissing(env, "LIBGL_GL", "21");
            putIfMissing(env, "LIBGL_ES", "3");
            putIfMissing(env, "LIBGL_NORMALIZE", "1");
            putIfMissing(env, "LIBGL_NOERROR", "1");
            putIfMissing(env, "LIBGL_MIPMAP", "3");
            putIfMissing(env, "LIBGL_USEVBO", "1");
        } else if (lower.contains("mesa") || lower.contains("zink")) {
            putIfMissing(env, "MESA_GL_VERSION_OVERRIDE", "4.6");
            putIfMissing(env, "MESA_GLSL_VERSION_OVERRIDE", "460");
        }
    }

    private static void putIfMissing(@NonNull LinkedHashMap<String, String> env, @NonNull String key, @NonNull String value) {
        if (!env.containsKey(key)) env.put(key, value);
    }

    private static boolean hasSharedLibraries(@Nullable File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        File[] files = dir.listFiles((file, name) -> name.endsWith(".so"));
        return files != null && files.length > 0;
    }

    @Nullable
    private static File getInstalledNativeLibraryDir(@Nullable ApplicationInfo appInfo) {
        if (appInfo == null || appInfo.nativeLibraryDir == null || appInfo.nativeLibraryDir.trim().isEmpty()) return null;
        return new File(appInfo.nativeLibraryDir);
    }

    @NonNull
    private static String getApplicationLabel(@NonNull Context context, @Nullable ApplicationInfo appInfo) {
        if (appInfo == null) return "";
        try {
            CharSequence label = context.getPackageManager().getApplicationLabel(appInfo);
            return label != null ? label.toString() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    @NonNull
    private static String inferRendererId(@NonNull String packageName) {
        String lower = packageName.toLowerCase(Locale.ROOT);
        if (lower.contains("zink") || lower.contains("vulkan") || lower.contains("mesa")) return "vulkan_zink";
        if (lower.contains("mobileglues") || lower.contains("ltw")) return "opengles3";
        if (lower.contains("gl4es")) return "opengles2";
        if (lower.contains("gallium")) return "custom_gallium";
        return "opengles3";
    }

    @NonNull
    private static String inferRendererName(@NonNull String packageName) {
        String lower = packageName.toLowerCase(Locale.ROOT);
        if (lower.contains("mobileglues")) return "MobileGlues (OpenGL 4.0, 1.17+)";
        if (lower.contains("ltw")) return "LTW Renderer";
        if (lower.contains("angleless")) return "Krypton Wrapper, NO-ANGLE (OpenGL ~3.0+)";
        if (lower.contains("ngg")) return "Krypton Wrapper (OpenGL ~3.0+)";
        if (lower.contains("gl4es")) return "Holy-GL4ES (Legacy)";
        if (lower.contains("mesa")) return "Mesa Renderer Plugin";
        return "Renderer Plugin";
    }


    @Nullable
    private static String inferRendererEgl(@NonNull String packageName, @Nullable String rendererLibraryName) {
        String lower = packageName.toLowerCase(Locale.ROOT);
        if (lower.contains("mobileglues")) return "libmobileglues.so";
        if (lower.contains("gl4es")) return "libEGL.so";
        if (lower.contains("ngg") || lower.contains("krypton")) return "libng_gl4es.so";
        if (rendererLibraryName == null || rendererLibraryName.trim().isEmpty()) return null;
        return new File(rendererLibraryName).getName();
    }

    @Nullable
    private static String inferRendererLibraryName(@NonNull String packageName, @Nullable File nativeLibDir) {
        String lower = packageName.toLowerCase(Locale.ROOT);
        if (lower.contains("mobileglues")) return firstExistingLibrary(nativeLibDir, "libmobileglues.so", "libMobileGlues.so");
        if (lower.contains("ltw")) return firstExistingLibrary(nativeLibDir, "libltw.so", "libLTW.so", "libng_gl4es.so");
        if (lower.contains("ngg")) return firstExistingLibrary(nativeLibDir, "libng_gl4es.so");
        if (lower.contains("gl4es")) return firstExistingLibrary(nativeLibDir, "libgl4es_114.so", "libgl4es.so");
        if (lower.contains("mesa")) return firstExistingLibrary(nativeLibDir, "libOSMesa_8.so", "libOSMesa.so");

        File candidate = findBestNativeLibrary(nativeLibDir);
        return candidate != null ? candidate.getName() : null;
    }

    @Nullable
    private static String firstExistingLibrary(@Nullable File nativeLibDir, @NonNull String... names) {
        if (nativeLibDir != null && nativeLibDir.isDirectory()) {
            for (String name : names) {
                if (new File(nativeLibDir, name).isFile()) return name;
            }
        }
        return names.length > 0 ? names[0] : null;
    }

    @Nullable
    private static File findBestNativeLibrary(@Nullable File nativeLibDir) {
        if (nativeLibDir == null || !nativeLibDir.isDirectory()) return null;
        File[] files = nativeLibDir.listFiles((dir, name) -> name.endsWith(".so"));
        if (files == null || files.length == 0) return null;
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File file : files) {
            String lower = file.getName().toLowerCase(Locale.ROOT);
            if (lower.contains("mobileglues") || lower.contains("ltw") || lower.contains("ng_gl4es") || lower.contains("gl4es") || lower.contains("osmesa")) {
                return file;
            }
        }
        return files[0];
    }

    @Nullable
    private static File resolveLibraryFile(@NonNull String library, @Nullable File nativeLibDir) {
        File direct = new File(library);
        if (direct.isAbsolute() && direct.isFile()) return direct;
        if (nativeLibDir == null) return null;
        File candidate = new File(nativeLibDir, library);
        return candidate.isFile() ? candidate : null;
    }

    private static void addNativeLibrariesToDlopen(
            @NonNull List<String> dlopen,
            @Nullable File nativeLibDir,
            @NonNull String rendererLibraryValue
    ) {
        if (nativeLibDir == null || !nativeLibDir.isDirectory()) return;
        File[] files = nativeLibDir.listFiles((dir, name) -> name.endsWith(".so"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File file : files) {
            String path = file.getAbsolutePath();
            if (path.equals(rendererLibraryValue)) continue;
            if (!dlopen.contains(path)) dlopen.add(path);
        }
    }

    @NonNull
    private static LinkedHashMap<String, String> parseEnv(@Nullable String value) {
        LinkedHashMap<String, String> env = new LinkedHashMap<>();
        if (value == null || value.trim().isEmpty()) return env;
        for (String item : value.split("[;\\n]")) {
            int index = item.indexOf('=');
            if (index <= 0) continue;
            String key = item.substring(0, index).trim();
            String envValue = item.substring(index + 1).trim();
            if (!key.isEmpty()) env.put(key, envValue);
        }
        return env;
    }

    @NonNull
    private static ArrayList<String> parseList(@Nullable String value) {
        ArrayList<String> list = new ArrayList<>();
        if (value == null || value.trim().isEmpty()) return list;
        for (String item : value.split("[;,:\\n]")) {
            String cleaned = item.trim();
            if (!cleaned.isEmpty() && !list.contains(cleaned)) list.add(cleaned);
        }
        return list;
    }

    @Nullable
    private static String readMeta(@Nullable Bundle meta, @NonNull String[] keys) {
        if (meta == null) return null;
        for (String key : keys) {
            if (!meta.containsKey(key)) continue;
            Object value = meta.get(key);
            if (value == null) continue;
            String string = String.valueOf(value).trim();
            if (!string.isEmpty()) return string;
        }
        return null;
    }

    @Nullable
    private static String firstNonEmpty(@Nullable String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return null;
    }

    @NonNull
    private static File extractNativeLibraries(@NonNull File apkFile, @NonNull String packageName) throws Exception {
        File packageRoot = new File(PathManager.DIR_INSTALLED_RENDERER_PLUGIN, packageName.replaceAll("[^A-Za-z0-9_.-]", "_"));
        File libRoot = new File(packageRoot, "lib");
        String abi = Architecture.androidAbiAsString(Architecture.getDeviceArchitecture());
        File abiDir = new File(libRoot, abi);

        if (!abiDir.exists() && !abiDir.mkdirs()) {
            throw new IllegalStateException("Unable to create plugin native directory: " + abiDir.getAbsolutePath());
        }

        Set<String> preferredAbis = new LinkedHashSet<>();
        preferredAbis.add(abi);
        if (Build.SUPPORTED_ABIS != null) preferredAbis.addAll(Arrays.asList(Build.SUPPORTED_ABIS));
        if (Architecture.getDeviceArchitecture() == Architecture.ARCH_ARM64) preferredAbis.add("arm64-v8a");
        if (Architecture.getDeviceArchitecture() == Architecture.ARCH_ARM) preferredAbis.add("armeabi-v7a");

        boolean extracted = false;
        try (ZipFile zip = new ZipFile(apkFile)) {
            for (String candidateAbi : preferredAbis) {
                ArrayList<ZipEntry> entries = collectLibEntries(zip, candidateAbi);
                if (entries.isEmpty()) continue;
                clearDirectory(abiDir);
                for (ZipEntry entry : entries) {
                    File output = new File(abiDir, new File(entry.getName()).getName());
                    try (InputStream input = zip.getInputStream(entry); OutputStream outputStream = new FileOutputStream(output)) {
                        copy(input, outputStream);
                    }
                    //noinspection ResultOfMethodCallIgnored
                    output.setReadable(true, false);
                    //noinspection ResultOfMethodCallIgnored
                    output.setExecutable(true, false);
                }
                extracted = true;
                break;
            }
        }

        if (!extracted) {
            Logging.i(TAG, "No native renderer libraries matched device ABI in " + apkFile.getName());
        }
        return abiDir;
    }

    @NonNull
    private static ArrayList<ZipEntry> collectLibEntries(@NonNull ZipFile zip, @NonNull String abi) {
        ArrayList<ZipEntry> entries = new ArrayList<>();
        String prefix = "lib/" + abi + "/";
        java.util.Enumeration<? extends ZipEntry> enumeration = zip.entries();
        while (enumeration.hasMoreElements()) {
            ZipEntry entry = enumeration.nextElement();
            if (!entry.isDirectory() && entry.getName().startsWith(prefix) && entry.getName().endsWith(".so")) {
                entries.add(entry);
            }
        }
        entries.sort(Comparator.comparing(ZipEntry::getName, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    private static void copyUriToFile(@NonNull Context context, @NonNull Uri uri, @NonNull File target) throws Exception {
        try (InputStream input = context.getContentResolver().openInputStream(uri); OutputStream output = new FileOutputStream(target)) {
            if (input == null) throw new IllegalStateException("Unable to open selected APK.");
            copy(input, output);
        }
    }

    private static void copyFile(@NonNull File source, @NonNull File target) throws Exception {
        try (InputStream input = new FileInputStream(source); OutputStream output = new FileOutputStream(target)) {
            copy(input, output);
        }
    }

    private static void copy(@NonNull InputStream input, @NonNull OutputStream output) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    private static void clearDirectory(@NonNull File directory) {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            deleteRecursively(child);
        }
    }

    private static void deleteRecursively(@NonNull File file) {
        if (file.isDirectory()) clearDirectory(file);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}

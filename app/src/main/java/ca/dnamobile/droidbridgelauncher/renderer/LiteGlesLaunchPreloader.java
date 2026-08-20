/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * DroidBridge launcher-side helper for renderer APK plugins.
 */

package ca.dnamobile.droidbridgelauncher.renderer;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.system.Os;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

public final class LiteGlesLaunchPreloader {
    public static final String PACKAGE_NAME = "ca.dnamobile.renderer.litegles";
    public static final String ENTRY_CLASS = "ca.dnamobile.renderer.litegles.plugin.LiteGlesPlugin";

    private static final Uri SETTINGS_URI = Uri.parse("content://ca.dnamobile.renderer.litegles.settings/config");
    private static final String METHOD_GET_CONFIG = "getConfig";

    private LiteGlesLaunchPreloader() {
    }

    public static Result prepare(@NonNull Context launcherContext,
                                 @NonNull String rendererPackageName,
                                 @NonNull File stagedNativeDir) throws Exception {
        Context pluginContext = launcherContext.createPackageContext(
                rendererPackageName,
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY
        );

        ApplicationInfo appInfo = pluginContext.getApplicationInfo();
        File pluginNativeDir = new File(appInfo.nativeLibraryDir);
        if (!pluginNativeDir.isDirectory()) {
            throw new IOException("Renderer nativeLibraryDir does not exist: " + pluginNativeDir);
        }
        if (!stagedNativeDir.isDirectory() && !stagedNativeDir.mkdirs()) {
            throw new IOException("Could not create staged native dir: " + stagedNativeDir);
        }

        Bundle providerConfig = readProviderBundle(launcherContext);
        String providerEnvironment = providerConfig != null ? providerConfig.getString("environmentBlock", "") : "";
        String providerProfile = providerConfig != null ? providerConfig.getString("profile", "missing") : "missing";
        boolean providerDirectVbo = providerConfig == null || providerConfig.getBoolean("directVbo", true);
        boolean providerCacheLists = providerConfig == null || providerConfig.getBoolean("cacheLists", true);

        System.out.println("DroidBridgeLiteGLES: provider profile before native load="
                + providerProfile + ", directVbo=" + providerDirectVbo + ", cacheLists=" + providerCacheLists);

        File stagedMain = stageAliases(pluginNativeDir, stagedNativeDir);
        File stagedOSMesa = new File(stagedNativeDir, "libOSMesa.so");
        File stagedOSMesa8 = new File(stagedNativeDir, "libOSMesa_8.so");
        File stagedGL = new File(stagedNativeDir, "libGL.so");

        setEnv("DB_LITEGLES_PLUGIN_NATIVE_DIR", pluginNativeDir.getAbsolutePath());
        setEnv("DB_LITEGLES_STAGED_NATIVE_DIR", stagedNativeDir.getAbsolutePath());
        setEnv("DB_LITEGLES_OSMESA_PATH", stagedOSMesa.getAbsolutePath());
        setEnv("DROIDBRIDGE_OSMESA_LIBRARY", stagedOSMesa.getAbsolutePath());
        setEnv("OSMESA_LIBRARY", stagedOSMesa.getAbsolutePath());
        setEnv("OSMESA_LIB", stagedOSMesa.getAbsolutePath());
        setEnv("LIBGL_OSMESA", stagedOSMesa.getAbsolutePath());
        setEnv("LIBGL_ES", "2");
        setEnv("LIBGL_GL", "21");
        setEnv("LIBGL_FBO", "1");
        setEnv("DROIDBRIDGE_RENDERER", "opengles3");

        applyEnvironmentBlock(providerEnvironment);

        String mergedNativePath = mergePaths(
                stagedNativeDir.getAbsolutePath(),
                pluginNativeDir.getAbsolutePath(),
                System.getProperty("java.library.path"),
                System.getProperty("org.lwjgl.librarypath"),
                System.getProperty("org.lwjgl.system.librarypath")
        );
        System.setProperty("java.library.path", mergedNativePath);
        System.setProperty("org.lwjgl.librarypath", mergedNativePath);
        System.setProperty("org.lwjgl.system.librarypath", mergedNativePath);
        System.setProperty("org.lwjgl.system.SharedLibraryExtractPath", stagedNativeDir.getAbsolutePath());
        System.setProperty("droidbridge.osmesa.path", stagedOSMesa.getAbsolutePath());
        System.setProperty("droidbridge.renderer.plugin.nativeDir", pluginNativeDir.getAbsolutePath());
        System.setProperty("droidbridge.renderer.stagedNativeDir", stagedNativeDir.getAbsolutePath());
        System.setProperty("droidbridge.litegles.profile", providerProfile);

        System.load(stagedOSMesa.getAbsolutePath());

        Boolean ok = callPluginPrepare(pluginContext, stagedNativeDir);
        if (!Boolean.TRUE.equals(ok)) {
            throw new IllegalStateException("LiteGLES plugin prepareForLaunch returned false");
        }

        applyEnvironmentBlock(providerEnvironment);

        System.out.println("DroidBridgeLiteGLES: staged libOSMesa=" + stagedOSMesa.getAbsolutePath());
        System.out.println("DroidBridgeLiteGLES: staged libOSMesa_8=" + stagedOSMesa8.getAbsolutePath());
        System.out.println("DroidBridgeLiteGLES: staged libGL=" + stagedGL.getAbsolutePath());

        return new Result(pluginNativeDir, stagedNativeDir, stagedMain, stagedOSMesa, mergedNativePath);
    }

    @Nullable
    private static Bundle readProviderBundle(@NonNull Context context) {
        try {
            Bundle b = context.getContentResolver().call(SETTINGS_URI, METHOD_GET_CONFIG, null, null);
            if (b != null) {
                System.out.println("DroidBridgeLiteGLES: provider config version="
                        + b.getString("version", "")
                        + ", profile=" + b.getString("profile", "")
                        + ", directVbo=" + b.getBoolean("directVbo", true)
                        + ", cacheLists=" + b.getBoolean("cacheLists", true));
            }
            return b;
        } catch (Throwable t) {
            System.out.println("DroidBridgeLiteGLES: provider config unavailable -> " + t);
            return null;
        }
    }

    private static void applyEnvironmentBlock(@Nullable String block) {
        if (TextUtils.isEmpty(block)) return;
        String[] lines = block.split("\\n");
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (!TextUtils.isEmpty(key)) setEnv(key, value);
        }
    }

    private static Boolean callPluginPrepare(@NonNull Context pluginContext,
                                             @NonNull File stagedNativeDir) throws Exception {
        Class<?> entry = pluginContext.getClassLoader().loadClass(ENTRY_CLASS);
        try {
            Method method = entry.getMethod("prepareForLaunch", Context.class, String.class);
            Object value = method.invoke(null, pluginContext, stagedNativeDir.getAbsolutePath());
            return value instanceof Boolean ? (Boolean) value : Boolean.TRUE;
        } catch (NoSuchMethodException ignored) {
            Method method = entry.getMethod("prepareForLaunch", Context.class);
            Object value = method.invoke(null, pluginContext);
            return value instanceof Boolean ? (Boolean) value : Boolean.TRUE;
        }
    }

    private static File stageAliases(@NonNull File pluginNativeDir,
                                     @NonNull File stagedNativeDir) throws IOException {
        File main = firstExisting(pluginNativeDir,
                "libdroidbridge_litegl.so",
                "libOSMesa.so",
                "libOSMesa_8.so",
                "libGL.so");
        if (main == null) {
            throw new IOException("LiteGLES native library was not found in " + pluginNativeDir);
        }

        copyAlways(main, new File(stagedNativeDir, "libdroidbridge_litegl.so"));
        copyAlways(firstExistingOr(main, pluginNativeDir, "libOSMesa.so"), new File(stagedNativeDir, "libOSMesa.so"));
        copyAlways(firstExistingOr(main, pluginNativeDir, "libOSMesa_8.so"), new File(stagedNativeDir, "libOSMesa_8.so"));
        copyAlways(firstExistingOr(main, pluginNativeDir, "libOSMesa8.so"), new File(stagedNativeDir, "libOSMesa8.so"));
        copyAlways(firstExistingOr(main, pluginNativeDir, "libGL.so"), new File(stagedNativeDir, "libGL.so"));
        return new File(stagedNativeDir, "libdroidbridge_litegl.so");
    }

    @Nullable
    private static File firstExisting(@NonNull File dir, @NonNull String... names) {
        for (String name : names) {
            File file = new File(dir, name);
            if (file.isFile() && file.length() > 0L) return file;
        }
        return null;
    }

    @NonNull
    private static File firstExistingOr(@NonNull File fallback, @NonNull File dir, @NonNull String name) {
        File file = new File(dir, name);
        return file.isFile() && file.length() > 0L ? file : fallback;
    }

    private static void copyAlways(@NonNull File src, @NonNull File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        File tmp = new File(dst.getParentFile(), dst.getName() + ".tmp");
        byte[] buf = new byte[1024 * 64];
        try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(tmp)) {
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            out.getFD().sync();
        }
        if (Build.VERSION.SDK_INT >= 21) {
            tmp.setReadable(true, false);
            tmp.setExecutable(true, false);
        }
        if (dst.exists() && !dst.delete()) {
            throw new IOException("Could not replace " + dst);
        }
        if (!tmp.renameTo(dst)) {
            throw new IOException("Could not move " + tmp + " to " + dst);
        }
    }

    private static void setEnv(@NonNull String key, @NonNull String value) {
        try {
            Os.setenv(key, value, true);
        } catch (Throwable ignored) {
        }
    }

    private static String mergePaths(@Nullable String... paths) {
        Set<String> out = new LinkedHashSet<>();
        if (paths != null) {
            for (String path : paths) {
                if (TextUtils.isEmpty(path)) continue;
                String[] split = path.split(File.pathSeparator);
                for (String item : split) {
                    if (!TextUtils.isEmpty(item)) out.add(item);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String path : out) {
            if (sb.length() > 0) sb.append(File.pathSeparator);
            sb.append(path);
        }
        return sb.toString();
    }

    public static final class Result {
        public final File pluginNativeDir;
        public final File stagedNativeDir;
        public final File stagedMainLibrary;
        public final File stagedOSMesaLibrary;
        public final String mergedNativePath;

        Result(File pluginNativeDir, File stagedNativeDir, File stagedMainLibrary,
               File stagedOSMesaLibrary, String mergedNativePath) {
            this.pluginNativeDir = pluginNativeDir;
            this.stagedNativeDir = stagedNativeDir;
            this.stagedMainLibrary = stagedMainLibrary;
            this.stagedOSMesaLibrary = stagedOSMesaLibrary;
            this.mergedNativePath = mergedNativePath;
        }
    }
}

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
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

/**
 * Vulkan driver manager used only by Vulkan Zink.
 *
 * Renderer plugins are OpenGL wrappers. Driver plugins are Vulkan ICDs such as
 * Turnip/Freedreno. Keep these separate so the Zink renderer dropdown does not
 * become polluted with normal renderer plugins like MobileGlues/GL4ES.
 */
public final class DriverPluginManager {
    private static final String TAG = "DriverPluginManager";

    public static final String DEFAULT_MESA_DRIVER = "Default Mesa driver";
    public static final String SYSTEM_VULKAN_DRIVER = "System Vulkan driver";

    private static final String[] KNOWN_DRIVER_PLUGIN_PACKAGES = new String[]{
            "com.fcl.plugin.driver.freedreno",
            "com.fcl.plugin.driver.turnip",
            "com.fcl.plugin.turnip",
            "com.fcl.plugin.adreno",
            "com.bzlzhh.plugin.driver.freedreno",
            "com.bzlzhh.plugin.driver.turnip",
            "com.bzlzhh.plugin.turnip",
            "com.bzlzhh.plugin.adreno",
            "com.tungsten.fcl.plugin.driver.freedreno",
            "com.tungsten.fcl.plugin.driver.turnip"
    };

    private static final String[] VULKAN_LIBRARY_NAMES = new String[]{
            "libvulkan_freedreno.so",
            "libvulkan_turnip.so",
            "libvulkan_adreno.so"
    };

    private static final ArrayList<Driver> DRIVERS = new ArrayList<>();
    private static boolean initialized;

    private DriverPluginManager() {
    }

    public static synchronized void reload(@NonNull Context context) {
        initialized = false;
        init(context);
    }

    public static synchronized void init(@NonNull Context context) {
        PathManager.initContextConstants(context);
        if (initialized) return;
        initialized = true;
        DRIVERS.clear();

        addDriver(new Driver(DEFAULT_MESA_DRIVER, Driver.Type.DEFAULT_MESA, null, null, null));
        addBuiltInTurnipIfAvailable(context);
        discoverInstalledDriverPlugins(context);
    }

    @NonNull
    public static synchronized List<Driver> getDrivers(@NonNull Context context) {
        init(context);
        return new ArrayList<>(DRIVERS);
    }

    @NonNull
    public static synchronized Driver getSelectedDriver(@NonNull Context context) {
        init(context);
        String selected = LauncherPreferences.getSelectedVulkanDriverName(context);
        for (int i = DRIVERS.size() - 1; i >= 0; i--) {
            Driver driver = DRIVERS.get(i);
            if (driver.getName().equals(selected)) return driver;
        }

        return DRIVERS.get(0);
    }

    public static synchronized int indexOfDriver(@NonNull Context context, @Nullable String selectedName) {
        init(context);
        Driver selected = getSelectedDriver(context);
        String effectiveName = selectedName != null ? selectedName : selected.getName();
        for (int i = 0; i < DRIVERS.size(); i++) {
            if (effectiveName.equals(DRIVERS.get(i).getName())) return i;
        }
        for (int i = 0; i < DRIVERS.size(); i++) {
            if (selected.getName().equals(DRIVERS.get(i).getName())) return i;
        }
        return 0;
    }

    public static boolean isVulkanZinkRenderer(@Nullable RendererInterface renderer) {
        if (renderer == null) return false;
        String combined = (renderer.getRendererId() + " " + renderer.getRendererName() + " " + renderer.getRendererLibrary())
                .toLowerCase(Locale.ROOT);
        return combined.contains("vulkan_zink")
                || combined.contains("zink")
                || combined.contains("osmesa")
                || DroidBridgeMesaSupport.isMesaZinkTurnipRenderer(renderer);
    }

    public static boolean isKopperZinkRenderer(@Nullable RendererInterface renderer) {
        return KopperZinkRenderer.isRenderer(renderer);
    }

    @NonNull
    public static List<File> getSelectedDriverLibrarySearchPaths(@NonNull Context context, @Nullable RendererInterface renderer) {
        return getSelectedDriverLibrarySearchPaths(
                context,
                renderer,
                LauncherPreferences.isUseSystemVulkanDriver(context)
        );
    }

    @NonNull
    public static List<File> getSelectedDriverLibrarySearchPaths(
            @NonNull Context context,
            @Nullable RendererInterface renderer,
            boolean useSystemVulkan
    ) {
        ArrayList<File> paths = new ArrayList<>();

        // Driver plugins are Vulkan ICDs. Do not leak Turnip/Freedreno into LTW,
        // MobileGlues, GL4ES, or Krypton. LTW especially must use the normal
        // system Vulkan loader path used by the prior implementation, otherwise AdrenoSupp/Turnip
        // can be loaded before LTW and glMapBuffer/glDraw paths become unstable.
        if (!isVulkanZinkRenderer(renderer)) {
            return paths;
        }

        if (useSystemVulkan) {
            return paths;
        }

        Driver driver = getSelectedDriver(context);
        File dir;

        if (driver.getType() == Driver.Type.TURNIP) {
            dir = driver.getNativeLibraryDir();
        } else {
            dir = findBundledDriverDir(context);
        }

        if (dir != null && dir.isDirectory()) paths.add(dir);
        return paths;
    }

    @NonNull
    public static Map<String, String> buildEnvironment(@NonNull Context context, @Nullable RendererInterface renderer) {
        return buildEnvironment(
                context,
                renderer,
                LauncherPreferences.isUseSystemVulkanDriver(context)
        );
    }

    @NonNull
    public static Map<String, String> buildEnvironment(
            @NonNull Context context,
            @Nullable RendererInterface renderer,
            boolean useSystemVulkan
    ) {
        LinkedHashMap<String, String> env = new LinkedHashMap<>();
        boolean zink = isVulkanZinkRenderer(renderer);
        Driver driver = getSelectedDriver(context);

        // Zink must not silently fall through to Android's Qualcomm ICD when the
        // user has System Vulkan disabled. If the generic "Default Mesa driver" is
        // selected, make bundled Turnip/Freedreno the effective Vulkan driver.
        if (zink && !useSystemVulkan && driver.getType() == Driver.Type.DEFAULT_MESA) {
            Driver bundledTurnip = MesaZinkTurnipDriver.createDriverIfAvailable(context);
            if (bundledTurnip != null) {
                driver = bundledTurnip;
                Logging.i(TAG, "Zink: Default Mesa driver resolved to bundled Turnip/Freedreno");
            } else {
                Logging.i(TAG, "Zink: bundled Turnip/Freedreno unavailable; custom Vulkan cannot be forced");
            }
        }

        env.put("JAVA_LAUNCHER_USE_SYSTEM_VULKAN_DRIVER", useSystemVulkan ? "1" : "0");
        env.put("JAVA_LAUNCHER_VULKAN_DRIVER", useSystemVulkan ? SYSTEM_VULKAN_DRIVER : driver.getName());

        if (!zink) {
            // Non-Zink renderers are OpenGL wrappers. Force them away from custom
            // Vulkan ICD plugins and clear stale driver variables from previous launches.
            env.put("DROIDBRIDGE_USE_SYSTEM_VULKAN", "1");
            env.put("DRIVER_PATH", "");
            env.put("VK_ICD_FILENAMES", "");
            env.put("VK_DRIVER_FILES", "");
            env.put("LIBGL_DRIVERS_PATH", "");
            env.put("EGL_DRIVERS_PATH", "");
            env.put("MESA_LOADER_DRIVER_OVERRIDE", "");
            env.put("GALLIUM_DRIVER", "");
            env.put("force_gl_vendor", "");
            return env;
        }

        applyGlobalVulkanDriverEnvironment(context, env, driver, true, useSystemVulkan);
        if (isKopperZinkRenderer(renderer)) {
            applyKopperZinkEnvironment(context, env);
            // Kopper owns presentation through Mesa EGL/native-window. Never enable
            // the legacy OSMesa RGBA workaround for this renderer.
            DroidBridgeMesaSupport.applyZinkSurfaceWorkaround(null);
        } else {
            applyZinkMesaEnvironment(context, env);

            // v56: first force the safe Android presentation path for all legacy Vulkan Zink
            // launches. The log proves Zink creates a context, while the Adreno 740
            // visual issue remains in the final displayed frame.
            DroidBridgeMesaSupport.applyZinkSurfaceWorkaround(renderer);

            // Explicit custom Mesa-Zink entries can still use the experimental route.
            DroidBridgeMesaSupport.applyZinkTurnipEnvironment(context, renderer, env);
        }
        if (useSystemVulkan) {
            applyGlobalVulkanDriverEnvironment(context, env, driver, true, true);
            env.put("JAVA_LAUNCHER_USE_SYSTEM_VULKAN_DRIVER", "1");
            env.put("JAVA_LAUNCHER_VULKAN_DRIVER", SYSTEM_VULKAN_DRIVER);
        }
        return env;
    }

    private static void applyDirectFreedrenoKgslEnvironment(@NonNull LinkedHashMap<String, String> env) {
        // v61: saved Mesa Freedreno KGSL is no longer a Vulkan Zink alias.
        // Reinforce the renderer's direct-renderer direct Mesa env here because
        // DriverPluginManager is merged after renderer.getRendererEnv().
        env.put("DROIDBRIDGE_MESA", "1");
        env.put("DROIDBRIDGE_MESA_MODE", "freedreno_kgsl");
        env.put("DROIDBRIDGE_MESA_SAFE_SWAPS", "1");
        env.put("DROIDBRIDGE_MESA_DRIVER", "kgsl");
        env.put("force_gl_vendor", "freedreno/DroidBridge");
        env.put("DROIDBRIDGE_FREEDRENO_MESA_V61", "1");
        env.put("DROIDBRIDGE_MESA_AAR_SINGLE_SOURCE", "1");
        env.put("DROIDBRIDGE_FREEDRENO_MESA_AAR_V72", "1");

        env.put("DROIDBRIDGE_RENDERER", "freedreno_kgsl");
        env.put("DROIDBRIDGE_RENDERER_MESA_MODE", "freedreno_kgsl");
        env.put("MESA_LOADER_DRIVER_OVERRIDE", "kgsl");
        env.put("GALLIUM_DRIVER", "");
        env.put("EGL_PLATFORM", "android");
        env.put("FORCE_VSYNC", "false");

        env.put("LIBGL_ES", "2");
        env.put("MESA_GL_VERSION_OVERRIDE", "3.3COMPAT");
        env.put("MESA_GLSL_VERSION_OVERRIDE", "330");
        env.put("MESA_GLSL_CACHE_DISABLE", "false");
        env.put("MESA_SHADER_CACHE_DISABLE", "false");
        env.put("LIBGL_MIPMAP", "3");
        env.put("LIBGL_NOINTOVLHACK", "1");
        env.put("LIBGL_NORMALIZE", "1");
        env.put("LIBGL_NOERROR", "0");
        env.put("allow_higher_compat_version", "true");
        env.put("force_glsl_extensions_warn", "true");
        env.put("allow_glsl_extension_directive_midshader", "true");

        env.put("DROIDBRIDGE_MESA_DESKTOP_GL", "1");
        env.put("DROIDBRIDGE_EGL_FORCE_DESKTOP_GL", "1");
        env.put("DROIDBRIDGE_EGL_NO_SYSTEM_FALLBACK", "1");

        env.put("DROIDBRIDGE_EGL", DroidBridgeMesaSupport.LIB_EGL_MESA);
        env.put("DROIDBRIDGE_EGL_LIBRARY", DroidBridgeMesaSupport.LIB_EGL_MESA);
        env.put("DROIDBRIDGE_EGL_LIBRARY", DroidBridgeMesaSupport.LIB_EGL_MESA);
        env.put("DROIDBRIDGE_RENDERER_LIBRARY", DroidBridgeMesaSupport.LIB_EGL_MESA);
        env.put("DROIDBRIDGE_RENDERER_LIBRARY", DroidBridgeMesaSupport.LIB_EGL_MESA);
        env.put("LIB_MESA_NAME", DroidBridgeMesaSupport.LIB_EGL_MESA);

        env.put("DROIDBRIDGE_USE_SYSTEM_VULKAN", "");
        env.put("DRIVER_PATH", "");
        env.put("VK_ICD_FILENAMES", "");
        env.put("VK_DRIVER_FILES", "");
        env.put("DROIDBRIDGE_LOAD_TURNIP", "");
        env.put("DROIDBRIDGE_LOAD_TURNIP", "");
        env.put("DROIDBRIDGE_USE_CUSTOM_TURNIP", "");
        env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", "");
        env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", "");
        env.put("ZINK_DEBUG", "");
        env.put("ZINK_DESCRIPTORS", "");

        env.put("OSMESA_LIB", "");
        env.put("DROIDBRIDGE_OSMESA_LIBRARY", "");
        env.put("OSMESA_LIBRARY", "");
        env.put("LIBGL_OSMESA", "");
    }

    private static void applyGlobalVulkanDriverEnvironment(
            @NonNull Context context,
            @NonNull LinkedHashMap<String, String> env,
            @NonNull Driver driver,
            boolean zink,
            boolean useSystemVulkan
    ) {
        if (useSystemVulkan) {
            env.put("DROIDBRIDGE_USE_SYSTEM_VULKAN", "1");
            env.put("DRIVER_PATH", "");
            env.put("VK_ICD_FILENAMES", "");
            env.put("VK_DRIVER_FILES", "");
            return;
        }

        env.put("DROIDBRIDGE_USE_SYSTEM_VULKAN", "0");

        if (driver.getType() == Driver.Type.TURNIP) {
            applyTurnipDriverEnvironment(context, env, driver);
            return;
        }

        if (zink) {
            File bundled = findBundledDriverDir(context);
            if (bundled != null && bundled.isDirectory()) {
                env.put("DRIVER_PATH", bundled.getAbsolutePath());
            } else {
                // No custom/bundled Turnip is available. Fall back to system Vulkan
                // so Zink still has a usable Vulkan loader instead of a null path.
                env.put("DROIDBRIDGE_USE_SYSTEM_VULKAN", "1");
                env.put("DRIVER_PATH", "");
            }
        } else {
            // Non-Zink renderers can still launch Vulkan mods. If no custom driver
            // is selected, clear any stale Turnip env left from an earlier launch.
            env.put("DRIVER_PATH", "");
            env.put("VK_ICD_FILENAMES", "");
            env.put("VK_DRIVER_FILES", "");
        }
    }

    private static void applyTurnipDriverEnvironment(
            @NonNull Context context,
            @NonNull LinkedHashMap<String, String> env,
            @NonNull Driver driver
    ) {
        File nativeDir = driver.getNativeLibraryDir();
        File vulkan = driver.getVulkanLibrary();
        if (nativeDir != null && nativeDir.isDirectory()) {
            env.put("DRIVER_PATH", nativeDir.getAbsolutePath());
            env.put("DROIDBRIDGE_TURNIP_DRIVER_DIR", nativeDir.getAbsolutePath());
        }
        if (vulkan != null && vulkan.isFile()) {
            env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", vulkan.getAbsolutePath());
            env.put("DROIDBRIDGE_TURNIP_DRIVER_LIBRARY", vulkan.getAbsolutePath());
        }
        env.put("DROIDBRIDGE_LOAD_TURNIP", "1");
        env.put("DROIDBRIDGE_USE_CUSTOM_TURNIP", "1");
        env.put("DROIDBRIDGE_ZINK_PREFER_SYSTEM_DRIVER", "");
        env.put("POJAV_ZINK_PREFER_SYSTEM_DRIVER", "");

        File icd = buildIcdFile(context, driver);
        if (icd != null && icd.isFile()) {
            env.put("VK_ICD_FILENAMES", icd.getAbsolutePath());
            env.put("VK_DRIVER_FILES", icd.getAbsolutePath());
        } else {
            env.put("VK_ICD_FILENAMES", "");
            env.put("VK_DRIVER_FILES", "");
        }
    }

    private static void applyKopperZinkEnvironment(
            @NonNull Context context,
            @NonNull LinkedHashMap<String, String> env
    ) {
        env.put("DROIDBRIDGE_RENDERER", KopperZinkRenderer.RENDERER_ID);
        env.put("POJAV_RENDERER", KopperZinkRenderer.RENDERER_ID);
        env.put("DROIDBRIDGE_RENDERER_LIBRARY", KopperZinkRenderer.MAIN_LIBRARY);
        env.put("POJAV_RENDERER_LIBRARY", KopperZinkRenderer.MAIN_LIBRARY);
        env.put("DROIDBRIDGE_EGL", KopperZinkRenderer.EGL_LIBRARY);
        env.put("DROIDBRIDGE_EGL_LIBRARY", KopperZinkRenderer.EGL_LIBRARY);
        env.put("POJAVEXEC_EGL", KopperZinkRenderer.EGL_LIBRARY);
        env.put("POJAVEXEC_EGL_LIBRARY", KopperZinkRenderer.EGL_LIBRARY);

        env.put("GALLIUM_DRIVER", "zink");
        env.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
        env.put("force_gl_vendor", "Mesa/DroidBridge");
        env.put("MESA_ANDROID_NO_KMS_SWRAST", "1");
        env.put("LIBGL_ES", "3");
        env.put("MESA_GL_VERSION_OVERRIDE", "4.6");
        env.put("MESA_GLSL_VERSION_OVERRIDE", "460");
        env.put("MESA_NO_ERROR", "0");
        env.put("LIBGL_NOERROR", "0");
        env.put("ZINK_DESCRIPTORS", "lazy");
        env.put("ZINK_DEBUG", "compact,noreorder");
        env.put("mesa_glthread", "false");
        env.put("MESA_VK_WSI_PRESENT_MODE", LauncherPreferences.isVulkanVsyncEnabled(context) ? "fifo" : "immediate");
        env.put("DROIDBRIDGE_EGL_FORCE_RGBX8888", "1");
        env.put("DROIDBRIDGE_EGL_FORCE_DESKTOP_GL", "1");
        env.put("DROIDBRIDGE_MESA_DESKTOP_GL", "1");
        env.put("DROIDBRIDGE_EGL_NO_SYSTEM_FALLBACK", "1");
        env.put("DROIDBRIDGE_KOPPER_FORCE_NAMESPACE_VULKAN", "1");

        // Load Mesa EGL inside the same Kopper/Turnip namespace. v14 gives that
        // namespace a dedicated Vulkan-only alias directory first, then Android
        // /system, so Mesa's own libvulkan lookup cannot escape to Qualcomm while
        // platform dependencies still resolve against the real Android libraries.
        File mesaNativeDir = DroidBridgeMesaSupport.resolveMesaNativeDir(context);
        File mesaAliasDir = DroidBridgeMesaSupport.prepareMesaLibraryAliases(context, null);
        File kopperVulkanAliasDir = new File(PathManager.DIR_CACHE, "kopper-vulkan-loader");
        //noinspection ResultOfMethodCallIgnored
        kopperVulkanAliasDir.mkdirs();
        File staleKopperVulkanAlias = new File(kopperVulkanAliasDir, "libvulkan.so");
        if (staleKopperVulkanAlias.isFile() && !staleKopperVulkanAlias.delete()) {
            Logging.i(TAG, "Kopper Zink: could not delete stale Vulkan alias before launch: "
                    + staleKopperVulkanAlias.getAbsolutePath());
        }
        env.put("DROIDBRIDGE_KOPPER_VULKAN_ALIAS_DIR", kopperVulkanAliasDir.getAbsolutePath());
        String driverPath = env.get("DRIVER_PATH");
        StringBuilder namespacePath = new StringBuilder(kopperVulkanAliasDir.getAbsolutePath())
                .append(File.pathSeparator)
                .append(mesaNativeDir.getAbsolutePath());
        if (mesaAliasDir != null && mesaAliasDir.isDirectory()) {
            namespacePath.append(File.pathSeparator).append(mesaAliasDir.getAbsolutePath());
            env.put("DROIDBRIDGE_MESA_ALIAS_DIR", mesaAliasDir.getAbsolutePath());
        }
        if (driverPath != null && !driverPath.trim().isEmpty()
                && !driverPath.equals(mesaNativeDir.getAbsolutePath())) {
            namespacePath.append(File.pathSeparator).append(driverPath);
        }
        env.put("DROIDBRIDGE_MESA", "1");
        env.put("DROIDBRIDGE_MESA_MODE", "kopper_zink");
        env.put("DROIDBRIDGE_MESA_DRIVER", "zink");
        env.put("DROIDBRIDGE_MESA_NATIVE_DIR", mesaNativeDir.getAbsolutePath());
        env.put("DROIDBRIDGE_MESA_NAMESPACE", "1");
        env.put("DROIDBRIDGE_MESA_NAMESPACE_PATH", namespacePath.toString());
        env.put("DROIDBRIDGE_MESA_EGL", new File(mesaNativeDir, KopperZinkRenderer.EGL_LIBRARY).getAbsolutePath());
        env.put("DROIDBRIDGE_MESA_EGL_SINGLE_INSTANCE", "1");

        // Most important difference from legacy Vulkan Zink: no OSMesa aliases.
        env.put("LIB_MESA_NAME", "");
        env.put("OSMESA_LIB", "");
        env.put("DROIDBRIDGE_OSMESA_LIBRARY", "");
        env.put("OSMESA_LIBRARY", "");
        env.put("LIBGL_OSMESA", "");
        env.put("DROIDBRIDGE_ZINK_V57_FORCE_OSMESA_EGL", "");
        env.put("DROIDBRIDGE_ZINK_V61_CLEAN_ZINK", "");
        Logging.i(TAG, "Kopper Zink: dedicated Vulkan alias dir="
                + kopperVulkanAliasDir.getAbsolutePath()
                + " namespacePath=" + namespacePath);
    }

    private static void applyZinkMesaEnvironment(@NonNull Context context, @NonNull LinkedHashMap<String, String> env) {
        // Regular Vulkan Zink still uses the OSMesa frontend, but it must use the
        // same private Android Vulkan-loader alias that Kopper uses when System
        // Vulkan is disabled. The old generic unique-loader path can fail on
        // Android 13 while resolving libhidlbase/native_handle_close, which makes
        // OSMesa silently fall back to Qualcomm system Vulkan.
        String turnipLibrary = env.get("DROIDBRIDGE_TURNIP_DRIVER_LIBRARY");
        boolean customVulkanRequested = !"1".equals(env.get("DROIDBRIDGE_USE_SYSTEM_VULKAN"))
                && "1".equals(env.get("DROIDBRIDGE_USE_CUSTOM_TURNIP"))
                && turnipLibrary != null
                && turnipLibrary.endsWith(MesaZinkTurnipDriver.LIB_VULKAN_FREEDRENO);
        if (customVulkanRequested) {
            File zinkVulkanAliasDir = new File(PathManager.DIR_CACHE, "zink-vulkan-loader");
            //noinspection ResultOfMethodCallIgnored
            zinkVulkanAliasDir.mkdirs();
            File staleVulkanAlias = new File(zinkVulkanAliasDir, "libvulkan.so");
            if (staleVulkanAlias.isFile() && !staleVulkanAlias.delete()) {
                Logging.i(TAG, "Vulkan Zink: could not delete stale Vulkan alias before launch: "
                        + staleVulkanAlias.getAbsolutePath());
            }

            // Reuse the already-shipped native alias-loader implementation. This
            // flag name originated in the Kopper path, but the helper itself is
            // renderer-agnostic: it creates a private libvulkan.so clone and hooks
            // that loader to bundled Turnip/Freedreno before publishing VULKAN_PTR.
            env.put("DROIDBRIDGE_KOPPER_FORCE_NAMESPACE_VULKAN", "1");
            env.put("DROIDBRIDGE_KOPPER_VULKAN_ALIAS_DIR", zinkVulkanAliasDir.getAbsolutePath());
            Logging.i(TAG, "Vulkan Zink: using private Turnip Vulkan-loader alias dir="
                    + zinkVulkanAliasDir.getAbsolutePath());
        } else {
            // Do not leave strict custom-loader state behind when the user explicitly
            // selected System Vulkan or the bundled Turnip payload is unavailable.
            env.put("DROIDBRIDGE_KOPPER_FORCE_NAMESPACE_VULKAN", "");
            env.put("DROIDBRIDGE_KOPPER_VULKAN_ALIAS_DIR", "");
        }

        // v57: Always make the Java environment match the known-good Vulkan Zink
        // route. The latest Freedreno-alias log still had DROIDBRIDGE_RENDERER=vulkan_zink
        // but DROIDBRIDGE_EGL was overwritten back to libEGL_mesa.so, which made
        // LWJGL report "no OpenGL context current" even though OSMesaMakeCurrent
        // succeeded. Keep these values set for every Zink launch, not just the
        // legacy alias helper.
        env.put("DROIDBRIDGE_RENDERER", "vulkan_zink");
        env.put("DROIDBRIDGE_EGL", "libOSMesa_8.so");
        env.put("DROIDBRIDGE_EGL_LIBRARY", "libOSMesa_8.so");
        env.put("DROIDBRIDGE_EGL_LIBRARY", "libOSMesa_8.so");
        env.put("DROIDBRIDGE_RENDERER_LIBRARY", "libOSMesa_8.so");
        env.put("DROIDBRIDGE_RENDERER_LIBRARY", "libOSMesa_8.so");
        env.put("LIB_MESA_NAME", "libOSMesa_8.so");
        env.put("OSMESA_LIB", "libOSMesa_8.so");
        env.put("DROIDBRIDGE_OSMESA_LIBRARY", "libOSMesa_8.so");
        env.put("OSMESA_LIBRARY", "libOSMesa_8.so");
        env.put("LIBGL_OSMESA", "libOSMesa_8.so");
        env.put("DROIDBRIDGE_ZINK_V57_FORCE_OSMESA_EGL", "1");
        env.put("DROIDBRIDGE_ZINK_V61_CLEAN_ZINK", "1");

        // legacy Android launcher's OSMesa/Zink path expects these basic Mesa values.
        env.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
        env.put("GALLIUM_DRIVER", "zink");
        env.put("LIBGL_ES", "3");
        env.put("LIBGL_NOERROR", "1");
        env.put("LIBGL_NORMALIZE", "1");
        env.put("LIBGL_MIPMAP", "3");
        env.put("MESA_GLSL_CACHE_DIR", PathManager.DIR_CACHE.getAbsolutePath());
        env.put("MESA_SHADER_CACHE_DIR", PathManager.DIR_CACHE.getAbsolutePath());
        env.put("MESA_VK_WSI_PRESENT_MODE", LauncherPreferences.isVulkanVsyncEnabled(context) ? "fifo" : "immediate");
    }

    @Nullable
    private static File findBundledDriverDir(@NonNull Context context) {
        ArrayList<File> dirs = new ArrayList<>();
        if (context.getApplicationInfo().nativeLibraryDir != null) {
            addDirIfValid(dirs, new File(context.getApplicationInfo().nativeLibraryDir));
        }
        if (PathManager.DIR_NATIVE_LIB != null) addDirIfValid(dirs, new File(PathManager.DIR_NATIVE_LIB));
        for (File dir : dirs) {
            if (findVulkanLibrary(dir) != null) return dir;
        }
        return null;
    }

    @Nullable
    private static File buildIcdFile(@NonNull Context context, @NonNull Driver driver) {
        File vulkan = driver.getVulkanLibrary();
        if (vulkan == null || !vulkan.isFile()) return null;

        File dir = new File(PathManager.DIR_CACHE, "vulkan_icd");
        if (!dir.exists() && !dir.mkdirs()) return null;

        String libraryPath = vulkan.getAbsolutePath();
        String safeName = (driver.getPackageName() != null ? driver.getPackageName() : driver.getName())
                .replaceAll("[^A-Za-z0-9_.-]", "_");
        File icd = new File(dir, safeName + ".json");

        try {
            JSONObject root = new JSONObject();
            JSONObject icdObject = new JSONObject();
            icdObject.put("library_path", libraryPath);
            icdObject.put("api_version", "1.3.0");
            root.put("file_format_version", "1.0.0");
            root.put("ICD", icdObject);

            try (FileOutputStream outputStream = new FileOutputStream(icd, false)) {
                outputStream.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            return icd;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to write Vulkan ICD for " + driver.getName(), throwable);
            return null;
        }
    }

    private static void addBuiltInTurnipIfAvailable(@NonNull Context context) {
        ArrayList<File> dirs = new ArrayList<>();
        if (context.getApplicationInfo().nativeLibraryDir != null) {
            addDirIfValid(dirs, new File(context.getApplicationInfo().nativeLibraryDir));
        }
        if (PathManager.DIR_NATIVE_LIB != null) addDirIfValid(dirs, new File(PathManager.DIR_NATIVE_LIB));

        for (File dir : dirs) {
            File vulkan = findVulkanLibrary(dir);
            if (vulkan != null) {
                addDriver(new Driver("Bundled Turnip/Freedreno", Driver.Type.TURNIP, null, dir, vulkan));
                return;
            }
        }
    }

    private static void addDirIfValid(@NonNull ArrayList<File> dirs, @Nullable File dir) {
        if (dir != null && dir.isDirectory() && !dirs.contains(dir)) dirs.add(dir);
    }

    private static void discoverInstalledDriverPlugins(@NonNull Context context) {
        PackageManager pm = context.getPackageManager();

        for (String packageName : KNOWN_DRIVER_PLUGIN_PACKAGES) {
            try {
                PackageInfo info = getPackageInfo(pm, packageName);
                if (info != null) parsePluginPackage(context, info);
            } catch (Throwable ignored) {
            }
        }

        try {
            List<ApplicationInfo> apps;
            int flags = PackageManager.GET_META_DATA;
            if (Build.VERSION.SDK_INT >= 33) {
                apps = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags));
            } else {
                //noinspection deprecation
                apps = pm.getInstalledApplications(flags);
            }
            for (ApplicationInfo app : apps) {
                if (app == null || app.packageName == null) continue;
                PackageInfo info = getPackageInfo(pm, app.packageName);
                if (info != null) parsePluginPackage(context, info);
            }
        } catch (Throwable throwable) {
            Logging.i(TAG, "Installed driver plugin scan was limited by Android package visibility: " + throwable);
        }
    }

    @Nullable
    private static PackageInfo getPackageInfo(@NonNull PackageManager pm, @NonNull String packageName) {
        try {
            long flags = PackageManager.GET_META_DATA;
            if (Build.VERSION.SDK_INT >= 33) {
                return pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags));
            } else {
                //noinspection deprecation
                return pm.getPackageInfo(packageName, (int) flags);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void parsePluginPackage(@NonNull Context context, @NonNull PackageInfo info) {
        ApplicationInfo app = info.applicationInfo;
        if (app == null || app.packageName == null) return;
        if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return;

        String lowerPackage = app.packageName.toLowerCase(Locale.ROOT);
        Bundle meta = app.metaData;
        boolean fclPlugin = meta != null && meta.getBoolean("fclPlugin", false);
        String declaredDriver = meta != null ? meta.getString("driver") : null;
        boolean packageLooksLikeDriver = lowerPackage.contains("driver")
                || lowerPackage.contains("turnip")
                || lowerPackage.contains("freedreno")
                || lowerPackage.contains("adreno");

        File nativeDir = app.nativeLibraryDir != null ? new File(app.nativeLibraryDir) : null;
        File vulkan = nativeDir != null ? findVulkanLibrary(nativeDir) : null;

        // Only real Vulkan driver plugins belong in this dropdown. Do not show
        // renderer plugins or generic legacy Android launcher plugins unless a Turnip/Freedreno
        // Vulkan library is actually present.
        if (vulkan == null) return;
        if (!fclPlugin && !packageLooksLikeDriver) return;
        if (nativeDir == null || !nativeDir.isDirectory()) return;

        String label = declaredDriver;
        if (label == null || label.trim().isEmpty()) {
            try {
                CharSequence appLabel = context.getPackageManager().getApplicationLabel(app);
                label = appLabel != null ? appLabel.toString() : app.packageName;
            } catch (Throwable ignored) {
                label = app.packageName;
            }
        }

        addDriver(new Driver(label, Driver.Type.TURNIP, app.packageName, nativeDir, vulkan));
    }

    @Nullable
    private static File findVulkanLibrary(@NonNull File nativeDir) {
        for (String name : VULKAN_LIBRARY_NAMES) {
            File candidate = new File(nativeDir, name);
            if (candidate.isFile()) return candidate;
        }
        return null;
    }

    private static void addDriver(@NonNull Driver driver) {
        for (int i = 0; i < DRIVERS.size(); i++) {
            Driver existing = DRIVERS.get(i);
            if (sameDriver(existing, driver)) return;
        }
        DRIVERS.add(driver);
    }

    private static boolean sameDriver(@NonNull Driver a, @NonNull Driver b) {
        if (a.getPackageName() != null && a.getPackageName().equals(b.getPackageName())) return true;
        File av = a.getVulkanLibrary();
        File bv = b.getVulkanLibrary();
        return av != null && bv != null && av.getAbsolutePath().equals(bv.getAbsolutePath());
    }
}

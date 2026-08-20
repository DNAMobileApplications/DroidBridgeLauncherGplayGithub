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
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;
import ca.dnamobile.droidbridgelauncher.runtime.utils.JREUtils;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;

public final class DroidBridgeMesaSupport {
    private static final String TAG = "DroidBridgeMesa";

    public static final String LEGACY_FREEDRENO_RENDERER_UUID = "1ad7249f-5784-4f00-bc72-174b3578ee46";

    public static final String RENDERER_ID_ZINK_TURNIP = "vulkan_zink";
    public static final String RENDERER_ID_FREEDRENO_KGSL = "freedreno_kgsl";
    public static final String RENDERER_ID_FREEDRENO = RENDERER_ID_FREEDRENO_KGSL;

    public static final String LIB_EGL_MESA = "libEGL_mesa.so";
    public static final String LIB_GALLIUM_DRI = "libgallium_dri.so";
    public static final String LIB_DRM = "libdrm.so";
    public static final String LIB_GL = "libGL.so";
    public static final String LIB_CUTILS = "libcutils.so";
    public static final String LIB_HARDWARE = "libhardware.so";
    public static final String LIB_GLAPI = "libglapi.so";
    public static final String LIB_GBM = "libgbm.so";

    private static final String[] OPTIONAL_MESA_DEPS = new String[]{
            "liblog.so",
            LIB_CUTILS,
            "libsync.so",
            "libutils.so",
            LIB_HARDWARE,
            "libnativewindow.so",
            LIB_GLAPI,
            LIB_GBM
    };
    private static volatile boolean sForceNativeSurfaceRgba8888;

    public static boolean shouldForceNativeSurfaceRgba8888() {
        return sForceNativeSurfaceRgba8888;
    }

    private DroidBridgeMesaSupport() {
    }

    public static boolean isLegacyFreedrenoRenderer(@Nullable RendererInterface renderer) {
        if (renderer == null) return false;
        String unique = safeLower(renderer.getUniqueIdentifier());
        String id = safeLower(renderer.getRendererId());
        String combined = rendererIdentity(renderer);

        boolean directName = combined.contains("direct")
                || combined.contains("kgsl direct")
                || combined.contains("experimental kgsl")
                || combined.contains("freedreno_kgsl_direct");

        if (LEGACY_FREEDRENO_RENDERER_UUID.equalsIgnoreCase(unique)) return true;
        if (directName) return false;
        if (RENDERER_ID_FREEDRENO_KGSL.equals(id) && combined.contains("mesa freedreno")) return true;
        return combined.contains("mesa freedreno kgsl");
    }

    public static boolean isPureVulkanZinkRenderer(@Nullable RendererInterface renderer) {
        if (renderer == null) return false;
        String id = safeLower(renderer.getRendererId());
        String unique = safeLower(renderer.getUniqueIdentifier());
        String combined = rendererIdentity(renderer);
        return "vulkan_zink".equals(id)
                || "vulkan_zink".equals(unique)
                || combined.contains("vulkan zink")
                || combined.contains("libosmesa_8.so");
    }

    public static boolean isVulkanZinkOrLegacyAlias(@Nullable RendererInterface renderer) {
        // v61: the legacy Freedreno UUID is no longer a Zink alias.
        // It is a real direct-renderer libEGL_mesa/freedreno_kgsl route, so do not
        // force Zink/OSMesa presentation workarounds for it.
        return isPureVulkanZinkRenderer(renderer);
    }

    public static void applyZinkSurfaceWorkaround(@Nullable RendererInterface renderer) {
        boolean force = isVulkanZinkOrLegacyAlias(renderer);
        sForceNativeSurfaceRgba8888 = force;
        if (force) {
            Logging.i(TAG, "v61 forcing native SurfaceView RGBA_8888 for Vulkan Zink presentation path renderer="
                    + rendererIdentity(renderer));
        }
    }

    public static boolean isDroidBridgeMesaRenderer(@Nullable RendererInterface renderer) {
        // v51: Zink/Turnip must use the launcher's known-good Vulkan-Zink/OSMesa
        // rollback path. Do not route Zink through DroidBridge's libEGL_mesa.so
        // bridge; the uploaded v50 log shows Mesa EGL/Zink failing eglInitialize
        // with EGL_NOT_INITIALIZED before Minecraft can create a window.
        return isNativeGlfwKgslRenderer(renderer);
    }

    public static boolean isNativeGlfwKgslRenderer(@Nullable RendererInterface renderer) {
        return DroidBridgeNativeGlfwKgslRenderer.isRenderer(renderer);
    }

    /**
     * The launcher ships two context routes for the Freedreno/KGSL renderer.
     * LWJGL 3.4.x (Minecraft 26.x) must use the dedicated NativeGLFW bridge,
     * while older LWJGL 3.3.x Forge builds retain the generic GLBridge fallback.
     */
    public static boolean shouldUseNativeGlfwForModernLwjgl(
            @Nullable String versionId,
            @Nullable File lwjglNativeDirectory
    ) {
        if (lwjglNativeDirectory != null) {
            String nativePath = safeLower(lwjglNativeDirectory.getAbsolutePath());
            if (nativePath.contains("lwjgl3.4") || nativePath.contains("lwjgl-3.4")) {
                return true;
            }
        }

        String value = safeLower(versionId);
        if (value.isEmpty()) return false;

        java.util.regex.Matcher release = java.util.regex.Pattern
                .compile("(?<!\\d)(\\d{2})(?:\\.|w)")
                .matcher(value);
        while (release.find()) {
            try {
                if (Integer.parseInt(release.group(1)) >= 26) return true;
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
    }

    public static boolean isNativeGlfwLibraryAvailable(@NonNull Context context) {
        File nativeDir = resolveMesaNativeDir(context);
        return new File(nativeDir, "libdroidbridge_native_glfw_v82.so").isFile()
                && new File(nativeDir, LIB_EGL_MESA).isFile();
    }

    public static boolean isMesaZinkTurnipRenderer(@Nullable RendererInterface renderer) {
        if (renderer == null) return false;

        String id = safeLower(renderer.getRendererId());
        String unique = safeLower(renderer.getUniqueIdentifier());
        String combined = rendererIdentity(renderer);

        if ("vulkan_zink".equals(id) || "vulkan_zink".equals(unique)) return false;
        if (isLegacyFreedrenoRenderer(renderer)) return false;
        if (combined.contains("zink_turnip")) return true;
        if (combined.contains("mesa unified zink")) return true;
        if (combined.contains("droidbridge mesa zink")) return true;
        if (combined.contains("mesa freedreno") && !combined.contains("direct") && !combined.contains("kgsl direct")) return true;

        return false;
    }

    public static boolean isBundledMesaAvailable(@NonNull Context context) {
        File nativeDir = resolveMesaNativeDir(context);
        return new File(nativeDir, LIB_EGL_MESA).isFile()
                && new File(nativeDir, LIB_GALLIUM_DRI).isFile()
                && new File(nativeDir, LIB_DRM).isFile();
    }

    public static boolean isMesaZinkTurnipAvailable(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < 29) return false;
        return isBundledMesaAvailable(context) && MesaZinkTurnipDriver.isAvailable(context);
    }

    public static boolean isMesaFreedrenoAvailable(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < 29) return false;
        return isBundledMesaAvailable(context);
    }

    @NonNull
    public static File resolveMesaNativeDir(@NonNull Context context) {
        try {
            String dir = context.getApplicationInfo() != null
                    ? context.getApplicationInfo().nativeLibraryDir
                    : null;
            if (dir != null && !dir.trim().isEmpty()) {
                File nativeDir = new File(dir);
                if (new File(nativeDir, LIB_EGL_MESA).isFile()) return nativeDir;
            }
        } catch (Throwable ignored) {
        }

        if (PathManager.DIR_NATIVE_LIB != null && !PathManager.DIR_NATIVE_LIB.trim().isEmpty()) {
            File pathManagerDir = new File(PathManager.DIR_NATIVE_LIB);
            if (new File(pathManagerDir, LIB_EGL_MESA).isFile()) return pathManagerDir;
        }

        try {
            String dir = context.getApplicationInfo() != null
                    ? context.getApplicationInfo().nativeLibraryDir
                    : null;
            if (dir != null && !dir.trim().isEmpty()) return new File(dir);
        } catch (Throwable ignored) {
        }

        return new File(PathManager.DIR_NATIVE_LIB != null ? PathManager.DIR_NATIVE_LIB : ".");
    }

    /**
     * LWJGL's EGL backend looks for desktop-style names such as libEGL.so.1.
     * These aliases are symlinks when Android allows it, otherwise copies.
     */
    @NonNull
    public static File prepareMesaLibraryAliases(@NonNull Context context, @Nullable RendererInterface renderer) {
        File aliasDir = resolveMesaAliasDir();
        if (!isDroidBridgeMesaRenderer(renderer)) return aliasDir;

        File nativeDir = resolveMesaNativeDir(context);
        //noinspection ResultOfMethodCallIgnored
        aliasDir.mkdirs();

        purgeMesaAliasDirectory(aliasDir);

        linkOrCopyAlias(new File(nativeDir, LIB_EGL_MESA), new File(aliasDir, LIB_EGL_MESA));
        linkOrCopyAlias(new File(nativeDir, LIB_EGL_MESA), new File(aliasDir, "libEGL.so.1"));
        linkOrCopyAlias(new File(nativeDir, LIB_EGL_MESA), new File(aliasDir, "libEGL.so"));

        copyAlias(new File(nativeDir, LIB_GALLIUM_DRI), new File(aliasDir, LIB_GALLIUM_DRI));

        // For Zink/Turnip, do not copy app-local libdrm/libgbm into the alias
        // directory. Those names may be Linux/WebRTC compatibility shims and can
        // shadow Android/vendor DRM, causing libEGL_mesa.so to fail with
        // missing symbols such as drmGetDevice2. Direct KGSL can still keep the
        // old colocated layout.
        if (!isMesaZinkTurnipRenderer(renderer)) {
            copyAlias(new File(nativeDir, LIB_DRM), new File(aliasDir, LIB_DRM));
            copyAlias(new File(nativeDir, LIB_GBM), new File(aliasDir, LIB_GBM));
        }

        copyAlias(new File(nativeDir, LIB_CUTILS), new File(aliasDir, LIB_CUTILS));
        copyAlias(new File(nativeDir, LIB_HARDWARE), new File(aliasDir, LIB_HARDWARE));
        copyAlias(new File(nativeDir, LIB_GLAPI), new File(aliasDir, LIB_GLAPI));
        linkOrCopyAlias(new File(nativeDir, LIB_GL), new File(aliasDir, LIB_GL));
        linkOrCopyAlias(new File(nativeDir, LIB_GL), new File(aliasDir, "libGL.so.1"));

        copyAlias(new File(nativeDir, MesaZinkTurnipDriver.LIB_VULKAN_FREEDRENO),
                new File(aliasDir, MesaZinkTurnipDriver.LIB_VULKAN_FREEDRENO));
        copyAlias(new File(nativeDir, MesaZinkTurnipDriver.LIB_VULKAN_TURNIP),
                new File(aliasDir, MesaZinkTurnipDriver.LIB_VULKAN_TURNIP));
        copyAlias(new File(nativeDir, MesaZinkTurnipDriver.LIB_VULKAN_ADRENO),
                new File(aliasDir, MesaZinkTurnipDriver.LIB_VULKAN_ADRENO));

        Logging.i(TAG, "Mesa alias dir=" + aliasDir.getAbsolutePath()
                + " libEGL.so.1=" + new File(aliasDir, "libEGL.so.1").isFile());
        return aliasDir;
    }

    @NonNull
    public static File resolveMesaAliasDir() {
        File cacheRoot = PathManager.DIR_CACHE != null ? PathManager.DIR_CACHE : null;
        if (cacheRoot == null) return new File("mesa-lwjgl-aliases");
        return new File(cacheRoot, "mesa-lwjgl-aliases");
    }

    /**
     * Namespace/search path used by the native Mesa loader.
     *
     * Zink/Turnip must not load the launcher's app-local Linux/WebRTC shim
     * libdrm.so before Android/vendor DRM. When app-local libdrm shadows the
     * vendor DRM provider, libEGL_mesa.so fails at dlopen with missing symbols
     * such as drmGetDevice2. Keep the Mesa alias directory first for our Mesa
     * entry points, then system/vendor libraries, and only then the APK native
     * directory as the final fallback.
     */
    @NonNull
    public static String buildMesaNamespacePath(@NonNull Context context, @Nullable RendererInterface renderer) {
        File nativeDir = resolveMesaNativeDir(context);
        File aliasDir = resolveMesaAliasDir();

        ArrayList<String> paths = new ArrayList<>();
        addSearchPath(paths, aliasDir);

        if (isMesaZinkTurnipRenderer(renderer)) {
            addSearchPath(paths, new File("/vendor/lib64"));
            addSearchPath(paths, new File("/system_ext/lib64"));
            addSearchPath(paths, new File("/odm/lib64"));
            addSearchPath(paths, new File("/product/lib64"));
            addSearchPath(paths, new File("/apex/com.android.runtime/lib64"));
            addSearchPath(paths, new File("/apex/com.android.art/lib64"));
            addSearchPath(paths, new File("/system/lib64"));
        }

        addSearchPath(paths, nativeDir);
        return joinSearchPath(paths);
    }

    private static void addSearchPath(@NonNull ArrayList<String> paths, @Nullable File file) {
        if (file == null) return;
        String absolute = file.getAbsolutePath();
        if (absolute.trim().isEmpty() || paths.contains(absolute)) return;
        if (file.exists() || absolute.startsWith("/vendor/") || absolute.startsWith("/system")
                || absolute.startsWith("/odm/") || absolute.startsWith("/product/")
                || absolute.startsWith("/apex/")) {
            paths.add(absolute);
        }
    }

    @NonNull
    private static String joinSearchPath(@NonNull ArrayList<String> paths) {
        StringBuilder out = new StringBuilder();
        for (String path : paths) {
            if (path == null || path.trim().isEmpty()) continue;
            if (out.length() > 0) out.append(File.pathSeparator);
            out.append(path);
        }
        return out.toString();
    }

    /**
     * Pure alias for old per-instance Mesa Freedreno KGSL selections.
     *
     * Do not route this through applyZinkTurnipEnvironment(). The real Vulkan Zink
     * selection works, while the alias crashed because extra DroidBridge Mesa/KGSL
     * variables and native shims were still involved. This method intentionally
     * leaves the env as close as possible to the normal Vulkan Zink renderer.
     */
    public static void applyLegacyFreedrenoAsVulkanZinkAlias(
            @NonNull Context context,
            @Nullable RendererInterface renderer,
            @NonNull LinkedHashMap<String, String> env
    ) {
        if (!isLegacyFreedrenoRenderer(renderer)) return;

        sForceNativeSurfaceRgba8888 = true;

        env.put("DROIDBRIDGE_FREEDRENO_V57_PURE_ZINK_ALIAS", "1");
        env.put("DROIDBRIDGE_ADRENO740_SURFACE_RGBA8888", "1");

        // Make the native bridge see exactly the real Vulkan Zink frontend.
        env.put("DROIDBRIDGE_RENDERER", "vulkan_zink");
        env.put("DROIDBRIDGE_RENDERER_MESA_MODE", "");
        env.put("DROIDBRIDGE_EGL", "libOSMesa_8.so");
        env.put("DROIDBRIDGE_EGL_LIBRARY", "libOSMesa_8.so");
        env.put("DROIDBRIDGE_EGL_LIBRARY", "libOSMesa_8.so");
        env.put("DROIDBRIDGE_RENDERER_LIBRARY", "libOSMesa_8.so");
        env.put("DROIDBRIDGE_RENDERER_LIBRARY", "libOSMesa_8.so");
        env.put("LIB_MESA_NAME", "libOSMesa_8.so");

        // Clear every DroidBridge direct-Mesa/KGSL switch that can load libhardware
        // or app-local Mesa shims before OSMesa/Zink creates its context.
        env.put("DROIDBRIDGE_MESA", "");
        env.put("DROIDBRIDGE_MESA_MODE", "");
        env.put("DROIDBRIDGE_MESA_DRIVER", "");
        env.put("DROIDBRIDGE_MESA_EGL", "");
        env.put("DROIDBRIDGE_MESA_GL", "");
        env.put("DROIDBRIDGE_MESA_NAMESPACE", "");
        env.put("DROIDBRIDGE_MESA_NAMESPACE_PATH", "");
        env.put("DROIDBRIDGE_EGL_FORCE_DESKTOP_GL", "");
        env.put("DROIDBRIDGE_EGL_NO_SYSTEM_FALLBACK", "");
        env.put("DROIDBRIDGE_EGL_FORCE_RGBA8888", "");
        env.put("DROIDBRIDGE_EGL_NO_FORCED_DESTROYED_SWAP", "");
        env.put("DROIDBRIDGE_OSMESA_ENABLE_ATTRIBS", "");
        env.put("DROIDBRIDGE_OSMESA_ZINK_NO_EXTENSION_BLACKLIST", "");
        env.put("DROIDBRIDGE_ADRENO740_ROUTE", "");
        env.put("DROIDBRIDGE_FREEDRENO_V55_ROLLBACK", "");

        // Do not force the brittle GL/version/extension overrides from the failed
        // Freedreno alias attempts. Normal Vulkan Zink launched without them.
        env.put("MESA_GL_VERSION_OVERRIDE", "");
        env.put("MESA_GLSL_VERSION_OVERRIDE", "");
        env.put("MESA_EXTENSION_OVERRIDE", "");
        env.put("ZINK_DEBUG", "");
        env.put("TU_DEBUG", "");

        Logging.i(TAG, "v57 legacy Freedreno KGSL treated as pure Vulkan Zink alias renderer="
                + rendererIdentity(renderer)
                + " nativeDir=" + resolveMesaNativeDir(context).getAbsolutePath());
    }

    /**
     * Final runtime env for explicit Mesa Unified Zink + Turnip/Freedreno renderers.
     */
    public static void applyZinkTurnipEnvironment(
            @NonNull Context context,
            @NonNull RendererInterface renderer,
            @NonNull LinkedHashMap<String, String> env
    ) {
        if (!isMesaZinkTurnipRenderer(renderer)) return;

        sForceNativeSurfaceRgba8888 = true;

        File nativeDir = resolveMesaNativeDir(context);
        File mesaCacheDir = new File(PathManager.DIR_CACHE, "mesa");
        //noinspection ResultOfMethodCallIgnored
        mesaCacheDir.mkdirs();

        /*
         * v51: This is intentionally NOT DroidBridge_Mesa_EGL mode.
         *
         * v50 proved the old saved "Mesa Freedreno KGSL" renderer UUID was
         * remapped into zink_turnip, but then libdroidbridge_runtime entered the custom
         * DroidBridge libEGL_mesa.so bridge. On the uploaded Adreno 740 log,
         * Mesa reported "MESA-LOADER: failed to retrieve device information"
         * and every eglInitialize() display attempt failed with 0x3001.
         *
         * So the correct fix is to make the legacy Freedreno selection behave
         * like the known-good Vulkan Zink renderer again: libOSMesa_8.so + Zink
         * + Turnip/Freedreno Vulkan. This avoids the broken Mesa EGL display
         * initialization path entirely.
         */
        env.put("DROIDBRIDGE_ADRENO740_ROUTE", "legacy_vulkan_zink_osmesa_turnip_v55_match_vulkan_zink_env");
        env.put("DROIDBRIDGE_FREEDRENO_V55_ROLLBACK", "1");

        // Hard-disable the custom DroidBridge Mesa EGL bridge for this path.
        env.put("DROIDBRIDGE_MESA", "");
        env.put("DROIDBRIDGE_MESA_MODE", "");
        env.put("DROIDBRIDGE_MESA_DRIVER", "");
        env.put("DROIDBRIDGE_MESA_EGL", "");
        env.put("DROIDBRIDGE_MESA_GL", "");
        env.put("DROIDBRIDGE_MESA_NAMESPACE", "");
        env.put("DROIDBRIDGE_MESA_NAMESPACE_PATH", "");
        env.put("DROIDBRIDGE_EGL_FORCE_DESKTOP_GL", "");
        env.put("DROIDBRIDGE_EGL_NO_SYSTEM_FALLBACK", "");
        env.put("DROIDBRIDGE_EGL_FORCE_RGBA8888", "");
        env.put("DROIDBRIDGE_EGL_NO_FORCED_DESTROYED_SWAP", "");
        // v55: Match the normal Vulkan Zink route exactly.
        // The direct Vulkan Zink log succeeds with DROIDBRIDGE_EGL=libOSMesa_8.so,
        // while the remapped Freedreno path crashed when this was left empty.
        env.put("DROIDBRIDGE_EGL", "libOSMesa_8.so");
        env.put("DROIDBRIDGE_EGL_LIBRARY", "libOSMesa_8.so");
        env.put("DROIDBRIDGE_EGL_LIBRARY", "libOSMesa_8.so");
        env.put("DROIDBRIDGE_RENDERER_LIBRARY", "libOSMesa_8.so");
        env.put("DROIDBRIDGE_RENDERER_LIBRARY", "libOSMesa_8.so");

        // Enter the same renderer family as the normal/stable Vulkan Zink option.
        env.put("DROIDBRIDGE_RENDERER", "vulkan_zink");
        env.put("DROIDBRIDGE_RENDERER_MESA_MODE", "");
        env.put("DROIDBRIDGE_NATIVEDIR", nativeDir.getAbsolutePath());
        env.put("POJAV_NATIVEDIR", nativeDir.getAbsolutePath());

        // The packaged jniLibs contains libOSMesa_8.so. That is the intended
        // legacy Android launcher-style Zink frontend, unlike libEGL_mesa.so which failed
        // during v50 display initialization.
        env.put("LIB_MESA_NAME", "libOSMesa_8.so");
        env.put("OSMESA_LIB", "libOSMesa_8.so");
        env.put("DROIDBRIDGE_OSMESA_LIBRARY", "libOSMesa_8.so");
        env.put("OSMESA_LIBRARY", "libOSMesa_8.so");
        env.put("LIBGL_OSMESA", "libOSMesa_8.so");

        env.put("GALLIUM_DRIVER", "zink");
        env.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
        env.put("LIBGL_ES", "3");
        env.put("LIBGL_NOERROR", "1");
        env.put("MESA_NO_ERROR", "1");
        env.put("LIBGL_NORMALIZE", "1");
        env.put("LIBGL_MIPMAP", "3");
        env.put("LIBGL_NOINTOVLHACK", "1");
        // v53: Use the plain Zink override used by the stable Vulkan-Zink path.
        // Do not request the COMPAT suffix here; the Mesa 23.0.4 OSMesa frontend
        // is brittle on Adreno 740 when mixed with newer Turnip payloads.
        env.put("MESA_GL_VERSION_OVERRIDE", "4.3");
        env.put("MESA_GLSL_VERSION_OVERRIDE", "430");
        env.put("DROIDBRIDGE_OSMESA_ENABLE_ATTRIBS", "0");

        // v53: Do NOT apply the v49 direct-KGSL extension blacklist to OSMesa Zink.
        // The latest logs show Mesa warning that DSA/vertex-attrib-binding cannot
        // be disabled immediately before libOSMesa_8.so crashes. That blacklist
        // remains scoped to explicit direct-KGSL tests below, but it is unsafe for
        // the legacy saved renderer now routed through Vulkan Zink/OSMesa with the normal Zink DROIDBRIDGE_EGL value.
        env.put("MESA_EXTENSION_OVERRIDE", "");
        env.put("DROIDBRIDGE_OSMESA_ZINK_NO_EXTENSION_BLACKLIST", "1");
        env.put("mesa_glthread", "false");
        env.put("MESA_DEBUG", "");
        env.put("MESA_VK_WSI_PRESENT_MODE", LauncherPreferences.isVulkanVsyncEnabled(context) ? "fifo" : "immediate");
        env.put("ZINK_DESCRIPTORS", "db");
        env.put("ZINK_DEBUG", "");
        env.put("TU_DEBUG", "");
        env.put("MESA_GLSL_CACHE_DIR", mesaCacheDir.getAbsolutePath());
        env.put("MESA_SHADER_CACHE_DIR", mesaCacheDir.getAbsolutePath());

        // Let the existing driver manager point Zink at bundled/custom Turnip.
        MesaZinkTurnipDriver.applyEnvironment(context, env);

        Logging.i(TAG, "Mesa/Freedreno legacy renderer rerouted to Vulkan Zink OSMesa v55 nativeDir="
                + nativeDir.getAbsolutePath()
                + " osmesa=" + new File(nativeDir, "libOSMesa_8.so").isFile()
                + " turnipAvailable=" + MesaZinkTurnipDriver.isAvailable(context));
    }

    /**
     * Final environment override for explicit direct Mesa Freedreno/KGSL tests only.
     * The legacy saved Freedreno UUID now routes to Zink/Turnip above.
     */
    public static void applyEnvironment(
            @NonNull Context context,
            @NonNull RendererInterface renderer,
            @NonNull LinkedHashMap<String, String> env
    ) {
        applyEnvironment(context, renderer, env, false);
    }

    public static void applyEnvironment(
            @NonNull Context context,
            @NonNull RendererInterface renderer,
            @NonNull LinkedHashMap<String, String> env,
            boolean preferNativeGlfw
    ) {
        if (isNativeGlfwKgslRenderer(renderer)) {
            applyNativeGlfwKgslEnvironment(context, renderer, env, preferNativeGlfw);
            return;
        }

        sForceNativeSurfaceRgba8888 = true;

        File nativeDir = resolveMesaNativeDir(context);
        File aliasDir = prepareMesaLibraryAliases(context, renderer);
        File eglMesa = new File(nativeDir, LIB_EGL_MESA);
        File vulkanFreedreno = new File(nativeDir, "libvulkan_freedreno.so");
        File mesaCacheDir = new File(PathManager.DIR_CACHE, "mesa");
        //noinspection ResultOfMethodCallIgnored
        mesaCacheDir.mkdirs();

        String mesaSearchPath = nativeDir.getAbsolutePath() + File.pathSeparator + aliasDir.getAbsolutePath();

        env.put("DROIDBRIDGE_MESA", "1");
        env.put("MESA_PROCESS_NAME", "DroidBridge");
        env.put("MESA_DRICONF_EXECUTABLE_OVERRIDE", "DroidBridge");
        env.put("force_gl_vendor", "freedreno/DroidBridge");
        env.put("DROIDBRIDGE_MESA_MODE", "freedreno_kgsl");
        env.put("DROIDBRIDGE_MESA_DRIVER", "kgsl");
        env.put("DROIDBRIDGE_MESA_NATIVE_DIR", nativeDir.getAbsolutePath());
        env.put("DROIDBRIDGE_MESA_ALIAS_DIR", aliasDir.getAbsolutePath());
        env.put("DROIDBRIDGE_MESA_NAMESPACE", "1");
        env.put("DROIDBRIDGE_MESA_NAMESPACE_PATH", mesaSearchPath);
        env.put("DROIDBRIDGE_MESA_EGL", eglMesa.getAbsolutePath());
        File glMesa = new File(nativeDir, LIB_GL);
        if (glMesa.isFile()) {
            env.put("DROIDBRIDGE_MESA_GL", glMesa.getAbsolutePath());
        }
        env.put("DROIDBRIDGE_MESA_EGL_SINGLE_INSTANCE", "1");
        env.put("DROIDBRIDGE_NATIVEDIR", nativeDir.getAbsolutePath());
        env.put("POJAV_NATIVEDIR", nativeDir.getAbsolutePath());

        env.put("DROIDBRIDGE_RENDERER", "freedreno_kgsl");
        env.put("DROIDBRIDGE_RENDERER_MESA_MODE", "freedreno_kgsl");
        env.put("DROIDBRIDGE_EGL", LIB_EGL_MESA);
        env.put("DROIDBRIDGE_EGL_LIBRARY", LIB_EGL_MESA);
        env.put("DROIDBRIDGE_EGL_LIBRARY", LIB_EGL_MESA);
        env.put("DROIDBRIDGE_RENDERER_LIBRARY", LIB_EGL_MESA);
        env.put("DROIDBRIDGE_RENDERER_LIBRARY", LIB_EGL_MESA);
        env.put("LIB_MESA_NAME", LIB_EGL_MESA);
        env.put("LIBGL_ES", "2");
        env.put("MESA_GL_VERSION_OVERRIDE", "");
        env.put("MESA_GLSL_VERSION_OVERRIDE", "");
        env.put("DROIDBRIDGE_MESA_EGL_SHIM", "");

        env.put("MESA_PROCESS_NAME", "DroidBridge");
        env.put("MESA_DRICONF_EXECUTABLE_OVERRIDE", "DroidBridge");
        env.put("force_gl_vendor", "freedreno/DroidBridge");
        env.put("GALLIUM_DRIVER", "");
        env.put("MESA_LOADER_DRIVER_OVERRIDE", "kgsl");
        env.put("EGL_PLATFORM", "android");
        env.put("DROIDBRIDGE_MESA_EGL_PLATFORM_DISPLAY", "1");
        // v64: the tested renderer path Mesa direct KGSL must use an opaque Android window surface.
        // The older RGBA_8888/alpha=8 workaround makes Adreno 740/750 leak alpha/depth
        // into sky/cloud rendering; Adreno 8 Gen 3 can black-screen.  Keep Vulkan Zink
        // on its own OSMesa path and make only direct Freedreno use RGBX/opaque.
        env.put("DROIDBRIDGE_EGL_FORCE_RGBA8888", "");
        env.put("DROIDBRIDGE_EGL_FORCE_RGBX8888", "");
        env.put("DROIDBRIDGE_DIRECT_FREEDRENO_OPAQUE_RGBX8888", "");
        env.put("DROIDBRIDGE_DIRECT_FREEDRENO_NATIVE_SURFACE_V69", "");
        env.put("DROIDBRIDGE_DIRECT_FREEDRENO_TEXTUREVIEW_V70", "1");
        env.put("DROIDBRIDGE_DIRECT_FREEDRENO_TURNIP_ZINK_V68", "");
        env.put("DROIDBRIDGE_DIRECT_FREEDRENO_LOAD_TURNIP", "");
        env.put("DROIDBRIDGE_LOAD_TURNIP", "");
        env.put("DROIDBRIDGE_LOAD_TURNIP", "");
        env.put("DROIDBRIDGE_USE_CUSTOM_TURNIP", "");
        env.put("DROIDBRIDGE_USE_SYSTEM_VULKAN", "1");
        env.put("DROIDBRIDGE_EGL_NO_FORCED_DESTROYED_SWAP", "1");
        env.put("DROIDBRIDGE_MESA_SAFE_SWAPS", "");
        env.put("MESA_ANDROID_NO_KMS_SWRAST", "1");
        // V16: do not force Minecraft VSync on Native Mesa. DroidBridge should let the
        // game toggle drive swapInterval while still using a fixed FPS cap.
        env.put("FORCE_VSYNC", "false");
        env.put("DROIDBRIDGE_DISABLE_NATIVE_FRAME_PACER_FOR_MESA", "");
        env.put("DROIDBRIDGE_NATIVE_MESA_SOFT_FPS_CAP", "1");
        env.put("DROIDBRIDGE_VSYNC_FRAME_PACING", "1");
        // Native Mesa/Freedreno needs Android ANativeWindow.setSwapInterval too;
        // eglSwapInterval alone can report OK while the Surface keeps presenting unlocked.
        env.put("DROIDBRIDGE_VSYNC_IN_ZINK", "1");
        env.put("LIBGL_MIPMAP", "3");
        env.put("LIBGL_NOINTOVLHACK", "1");
        env.put("LIBGL_NORMALIZE", "1");
        env.put("LIBGL_NOERROR", "1");
        env.put("allow_higher_compat_version", "true");
        env.put("force_glsl_extensions_warn", "true");
        env.put("allow_glsl_extension_directive_midshader", "true");

        // v64: do not hide Mesa's normal desktop GL extensions on the the tested renderer path Mesa path.
        // The old blacklist was useful for isolating Adreno 740 bugs, but it makes
        // this path differ from the tested renderer path and can push Minecraft into broken fallback code.
        env.put("DROIDBRIDGE_ADRENO740_SAFE_GL_EXTENSIONS", "");
        env.put("MESA_EXTENSION_OVERRIDE", "");

        env.put("DROIDBRIDGE_MESA_DESKTOP_GL", "1");
        env.put("DROIDBRIDGE_EGL_FORCE_DESKTOP_GL", "1");
        env.put("DROIDBRIDGE_EGL_RECOVER_CURRENT", "");
        env.put("DROIDBRIDGE_EGL_NO_SYSTEM_FALLBACK", "1");
        env.put("MESA_GLSL_CACHE_DIR", mesaCacheDir.getAbsolutePath());
        env.put("MESA_SHADER_CACHE_DIR", mesaCacheDir.getAbsolutePath());
        env.put("LIBGL_DRIVERS_PATH", mesaSearchPath);
        env.put("EGL_DRIVERS_PATH", mesaSearchPath);
        // driver_helper/loadTurnipVulkan expects DRIVER_PATH to be one native dir, not a colon-separated search path.
        env.put("DRIVER_PATH", nativeDir.getAbsolutePath());

        // v69: the tested renderer path's known-good Adreno 740 freedreno_kgsl path does not force Turnip/Zink.
        env.put("DROIDBRIDGE_LOAD_TURNIP", "");
        env.put("DROIDBRIDGE_LOAD_TURNIP", "");
        env.put("DROIDBRIDGE_USE_CUSTOM_TURNIP", "");
        env.put("DROIDBRIDGE_DIRECT_FREEDRENO_LOAD_TURNIP", "");
        env.put("DROIDBRIDGE_USE_SYSTEM_VULKAN", "1");
        env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", "");
        env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", "");
        env.put("VK_ICD_FILENAMES", "");
        env.put("VK_DRIVER_FILES", "");
        env.put("VK_INSTANCE_LAYERS", "");
        env.put("TU_DEBUG", "");
        env.put("ZINK_DEBUG", "");
        env.put("ZINK_DESCRIPTORS", "");

        env.put("OSMESA_LIB", "");
        env.put("DROIDBRIDGE_OSMESA_LIBRARY", "");
        env.put("OSMESA_LIBRARY", "");
        env.put("LIBGL_OSMESA", "");

        Logging.i(TAG, "Mesa Freedreno KGSL direct experimental env nativeDir=" + nativeDir.getAbsolutePath()
                + " aliasDir=" + aliasDir.getAbsolutePath()
                + " egl=" + eglMesa.getAbsolutePath()
                + " eglExists=" + eglMesa.isFile());
    }

    public static void applyNativeGlfwKgslEnvironment(
            @NonNull Context context,
            @NonNull RendererInterface renderer,
            @NonNull LinkedHashMap<String, String> env
    ) {
        applyNativeGlfwKgslEnvironment(context, renderer, env, false);
    }

    public static void applyNativeGlfwKgslEnvironment(
            @NonNull Context context,
            @NonNull RendererInterface renderer,
            @NonNull LinkedHashMap<String, String> env,
            boolean preferNativeGlfw
    ) {
        if (!isNativeGlfwKgslRenderer(renderer)) return;

        sForceNativeSurfaceRgba8888 = true;

        File nativeDir = resolveMesaNativeDir(context);
        File aliasDir = prepareMesaLibraryAliases(context, renderer);
        File eglMesa = new File(nativeDir, LIB_EGL_MESA);
        File nativeGlfw = new File(nativeDir, "libdroidbridge_native_glfw_v82.so");
        File mesaCacheDir = new File(PathManager.DIR_CACHE, "mesa");
        //noinspection ResultOfMethodCallIgnored
        mesaCacheDir.mkdirs();

        String mesaSearchPath = nativeDir.getAbsolutePath() + File.pathSeparator + aliasDir.getAbsolutePath();

        env.putAll(renderer.getRendererEnv());
        boolean enableNativeGlfw = preferNativeGlfw
                && nativeGlfw.isFile()
                && eglMesa.isFile();

        // LWJGL 3.4.x no longer uses DroidBridge's old LWJGLX context-capability
        // handoff. Restore the dedicated NativeGLFW bridge for modern Minecraft,
        // but keep the generic GLBridge fallback for older Forge/LWJGL 3.3.x.
        env.put("DROIDBRIDGE_NATIVE_GLFW", enableNativeGlfw ? "1" : "");
        env.put("DROIDBRIDGE_NATIVE_GLFW_KGSL", enableNativeGlfw ? "1" : "");
        env.put("DROIDBRIDGE_NATIVE_GLFW_DESKTOP_GL", enableNativeGlfw ? "1" : "");
        env.put("DROIDBRIDGE_NATIVE_GLFW_LIB", enableNativeGlfw ? nativeGlfw.getAbsolutePath() : "");
        env.put("DROIDBRIDGE_NATIVE_GLFW_EGL", enableNativeGlfw ? eglMesa.getAbsolutePath() : "");
        env.put("DROIDBRIDGE_NATIVE_GLFW_DRIVER", enableNativeGlfw ? "kgsl" : "");

        env.put("DROIDBRIDGE_MESA", "1");
        env.put("DROIDBRIDGE_MESA_MODE", "freedreno_kgsl");
        env.put("DROIDBRIDGE_MESA_DRIVER", "kgsl");
        env.put("DROIDBRIDGE_MESA_NATIVE_DIR", nativeDir.getAbsolutePath());
        env.put("DROIDBRIDGE_MESA_ALIAS_DIR", aliasDir.getAbsolutePath());
        env.put("DROIDBRIDGE_MESA_NAMESPACE", "1");
        env.put("DROIDBRIDGE_MESA_NAMESPACE_PATH", mesaSearchPath);
        env.put("DROIDBRIDGE_MESA_EGL", eglMesa.getAbsolutePath());
        env.put("DROIDBRIDGE_MESA_EGL_SINGLE_INSTANCE", "1");
        env.put("DROIDBRIDGE_MESA_DESKTOP_GL", "1");
        env.put("DROIDBRIDGE_NATIVEDIR", nativeDir.getAbsolutePath());
        env.put("POJAV_NATIVEDIR", nativeDir.getAbsolutePath());

        env.put("DROIDBRIDGE_RENDERER", "freedreno_kgsl");
        env.put("DROIDBRIDGE_RENDERER_MESA_MODE", "freedreno_kgsl");
        env.put("DROIDBRIDGE_EGL", LIB_EGL_MESA);
        env.put("DROIDBRIDGE_EGL_LIBRARY", LIB_EGL_MESA);
        env.put("DROIDBRIDGE_EGL_LIBRARY", LIB_EGL_MESA);
        env.put("DROIDBRIDGE_RENDERER_LIBRARY", LIB_EGL_MESA);
        env.put("DROIDBRIDGE_RENDERER_LIBRARY", LIB_EGL_MESA);
        env.put("LIB_MESA_NAME", LIB_EGL_MESA);

        env.put("MESA_PROCESS_NAME", "DroidBridge");
        env.put("MESA_DRICONF_EXECUTABLE_OVERRIDE", "DroidBridge");
        env.put("force_gl_vendor", "freedreno/DroidBridge");
        env.put("GALLIUM_DRIVER", "");
        env.put("MESA_LOADER_DRIVER_OVERRIDE", "kgsl");
        env.put("EGL_PLATFORM", "android");
        env.put("LIBGL_ES", "2");
        env.put("MESA_GL_VERSION_OVERRIDE", "");
        env.put("MESA_GLSL_VERSION_OVERRIDE", "");
        env.put("LIBGL_MIPMAP", "3");
        env.put("LIBGL_NOINTOVLHACK", "1");
        env.put("LIBGL_NORMALIZE", "1");
        env.put("LIBGL_NOERROR", "1");
        env.put("allow_higher_compat_version", "true");
        env.put("force_glsl_extensions_warn", "true");
        env.put("allow_glsl_extension_directive_midshader", "true");

        env.put("DROIDBRIDGE_EGL_FORCE_DESKTOP_GL", "1");
        env.put("DROIDBRIDGE_EGL_NO_SYSTEM_FALLBACK", "1");
        env.put("DROIDBRIDGE_DIRECT_FREEDRENO_NATIVE_SURFACE_V69", "1");
        env.put("DROIDBRIDGE_DIRECT_FREEDRENO_TEXTUREVIEW_V70", "");
        env.put("MESA_EXTENSION_OVERRIDE", "");
        env.put("mesa_glthread", "");
        env.put("FORCE_VSYNC", "false");
        env.put("DROIDBRIDGE_DISABLE_NATIVE_FRAME_PACER_FOR_MESA", "");
        env.put("DROIDBRIDGE_NATIVE_MESA_SOFT_FPS_CAP", "1");
        env.put("DROIDBRIDGE_VSYNC_FRAME_PACING", "1");
        // Native Mesa/Freedreno needs Android ANativeWindow.setSwapInterval too;
        // eglSwapInterval alone can report OK while the Surface keeps presenting unlocked.
        env.put("DROIDBRIDGE_VSYNC_IN_ZINK", "1");
        env.put("MESA_GLSL_CACHE_DIR", mesaCacheDir.getAbsolutePath());
        env.put("MESA_SHADER_CACHE_DIR", mesaCacheDir.getAbsolutePath());
        env.put("LIBGL_DRIVERS_PATH", mesaSearchPath);
        env.put("EGL_DRIVERS_PATH", mesaSearchPath);
        env.put("DRIVER_PATH", nativeDir.getAbsolutePath());

        env.put("DROIDBRIDGE_LOAD_TURNIP", "");
        env.put("DROIDBRIDGE_LOAD_TURNIP", "");
        env.put("DROIDBRIDGE_USE_CUSTOM_TURNIP", "");
        env.put("DROIDBRIDGE_DIRECT_FREEDRENO_LOAD_TURNIP", "");
        env.put("DROIDBRIDGE_USE_SYSTEM_VULKAN", "1");
        env.put("VK_ICD_FILENAMES", "");
        env.put("VK_DRIVER_FILES", "");
        env.put("VK_INSTANCE_LAYERS", "");
        env.put("TU_DEBUG", "");
        env.put("ZINK_DEBUG", "");
        env.put("ZINK_DESCRIPTORS", "");

        env.put("OSMESA_LIB", "");
        env.put("DROIDBRIDGE_OSMESA_LIBRARY", "");
        env.put("OSMESA_LIBRARY", "");
        env.put("LIBGL_OSMESA", "");

        Logging.i(TAG, "Native Mesa renderer environment nativeDir=" + nativeDir.getAbsolutePath()
                + " aliasDir=" + aliasDir.getAbsolutePath()
                + " egl=" + eglMesa.getAbsolutePath()
                + " nativeGlfwEnabled=" + enableNativeGlfw
                + " nativeGlfwRequested=" + preferNativeGlfw
                + " nativeGlfw=" + nativeGlfw.getAbsolutePath()
                + " nativeGlfwExists=" + nativeGlfw.isFile());
    }

    public static void preload(@NonNull Context context, @NonNull RendererInterface renderer) {
        if (isLegacyFreedrenoRenderer(renderer)) {
            Logging.i(TAG, "v65 direct Freedreno uses native Freedreno Mesa/KGSL path; skipping Java Mesa preload");
            return;
        }
        if (isMesaZinkTurnipRenderer(renderer)) {
            Logging.i(TAG, "v51 Zink/Turnip rollback uses libOSMesa_8.so; skipping DroidBridge Mesa EGL preload");
            return;
        }
        if (!isDroidBridgeMesaRenderer(renderer)) return;

        File nativeDir = resolveMesaNativeDir(context);
        File aliasDir = prepareMesaLibraryAliases(context, renderer);
        Logging.i(TAG, "Mesa preload nativeDir=" + nativeDir.getAbsolutePath()
                + " aliasDir=" + aliasDir.getAbsolutePath()
                + " renderer=" + renderer.getRendererName());

        boolean renderSpecConfigured = DroidBridgeRenderSpec.configureForMesa(context, renderer);
        Logging.i(TAG, "DroidBridge RenderSpec configured=" + renderSpecConfigured);

        for (String dep : OPTIONAL_MESA_DEPS) {
            preloadOptional(new File(nativeDir, dep));
            preloadOptional(new File(aliasDir, dep));
        }

        // Do not preload libdrm/libgbm for Zink/Turnip. If an app-local DRM shim
        // is loaded into the process before Mesa EGL, the linker can bind Mesa
        // against that shim and fail on newer libdrm symbols such as drmGetDevice2.
        if (!isMesaZinkTurnipRenderer(renderer)) {
            preloadRequired(new File(nativeDir, LIB_DRM));
        }
        preloadOptional(new File(nativeDir, LIB_GLAPI));
        preloadRequired(new File(nativeDir, LIB_GALLIUM_DRI));

        Logging.i(TAG, "Mesa EGL reserved for native bridge: "
                + new File(nativeDir, LIB_EGL_MESA).getAbsolutePath()
                + " exists=" + new File(nativeDir, LIB_EGL_MESA).isFile());
    }


    private static void purgeMesaAliasDirectory(@NonNull File aliasDir) {
        File[] files = aliasDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file == null) continue;
            try {
                Files.deleteIfExists(file.toPath());
                Logging.i(TAG, "Removed stale Mesa alias " + file.getAbsolutePath());
            } catch (Throwable throwable) {
                Logging.e(TAG, "Failed to remove stale Mesa alias " + file.getAbsolutePath(), throwable);
            }
        }
    }

    private static void linkOrCopyAlias(@NonNull File source, @NonNull File target) {
        if (!source.isFile()) {
            try {
                Files.deleteIfExists(target.toPath());
            } catch (Throwable ignored) {
            }
            return;
        }

        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }

        try {
            Files.deleteIfExists(target.toPath());
        } catch (Throwable ignored) {
        }

        try {
            Files.createSymbolicLink(target.toPath(), source.toPath());
            //noinspection ResultOfMethodCallIgnored
            target.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            target.setExecutable(true, false);
            Logging.i(TAG, "Prepared Mesa symlink alias " + target.getAbsolutePath()
                    + " -> " + source.getAbsolutePath());
            return;
        } catch (Throwable throwable) {
            Logging.i(TAG, "Mesa symlink alias unavailable, falling back to copy for "
                    + target.getAbsolutePath() + ": " + throwable);
        }

        copyAlias(source, target);
    }

    private static void copyAlias(@NonNull File source, @NonNull File target) {
        if (!source.isFile()) {
            try {
                Files.deleteIfExists(target.toPath());
            } catch (Throwable ignored) {
            }
            return;
        }
        try {
            Files.deleteIfExists(target.toPath());
        } catch (Throwable ignored) {
        }

        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }

        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            //noinspection ResultOfMethodCallIgnored
            target.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            target.setExecutable(true, false);
            Logging.i(TAG, "Prepared Mesa alias " + target.getAbsolutePath() + " from " + source.getName());
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to prepare Mesa alias " + target.getAbsolutePath(), throwable);
        }
    }

    private static boolean preloadRequired(@NonNull File file) {
        if (!file.isFile()) {
            Logging.e(TAG, "Required Mesa library missing: " + file.getAbsolutePath());
            return false;
        }
        return dlopen(file, true);
    }

    private static boolean preloadOptional(@NonNull File file) {
        if (!file.isFile()) return false;
        return dlopen(file, false);
    }

    private static boolean dlopen(@NonNull File file, boolean required) {
        try {
            boolean loaded = JREUtils.dlopen(file.getAbsolutePath());
            Logging.i(TAG, "Mesa dlopen " + file.getAbsolutePath() + " = " + loaded);
            return loaded;
        } catch (Throwable throwable) {
            if (required) {
                Logging.e(TAG, "Mesa required dlopen failed: " + file.getAbsolutePath(), throwable);
            } else {
                Logging.e(TAG, "Mesa optional dlopen failed: " + file.getAbsolutePath(), throwable);
            }
            return false;
        }
    }

    @NonNull
    private static String rendererIdentity(@NonNull RendererInterface renderer) {
        return safeLower(renderer.getRendererId()) + " "
                + safeLower(renderer.getRendererName()) + " "
                + safeLower(renderer.getRendererLibrary()) + " "
                + safeLower(renderer.getRendererEGL()) + " "
                + safeLower(renderer.getUniqueIdentifier());
    }

    @NonNull
    private static String safeLower(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}

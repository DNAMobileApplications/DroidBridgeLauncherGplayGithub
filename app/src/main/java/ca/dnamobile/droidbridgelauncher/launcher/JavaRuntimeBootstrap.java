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

package ca.dnamobile.droidbridgelauncher.launcher;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.system.Os;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.logs.LauncherLogManager;
import ca.dnamobile.droidbridgelauncher.modcompat.DiscordRpcCompatPatch;
import ca.dnamobile.droidbridgelauncher.modcompat.ControllerModCompat;
import ca.dnamobile.droidbridgelauncher.modcompat.FFmpegPluginCompat;
import ca.dnamobile.droidbridgelauncher.renderer.DriverPluginManager;
import ca.dnamobile.droidbridgelauncher.renderer.BtaRendererPolicy;
import ca.dnamobile.droidbridgelauncher.renderer.DroidBridgeMesaSupport;
import ca.dnamobile.droidbridgelauncher.renderer.LiteGlesLaunchPreloader;
import ca.dnamobile.droidbridgelauncher.renderer.KopperZinkRenderer;
import ca.dnamobile.droidbridgelauncher.renderer.MobileGluesConfigHelper;
import ca.dnamobile.droidbridgelauncher.renderer.AdrenoSyncFenceFdGuard;
import ca.dnamobile.droidbridgelauncher.renderer.RendererInterface;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;
import ca.dnamobile.droidbridgelauncher.runtime.Architecture;
import ca.dnamobile.droidbridgelauncher.runtime.DroidBridgeSdlFpsBridge;
import ca.dnamobile.droidbridgelauncher.runtime.utils.JREUtils;
public final class JavaRuntimeBootstrap {
    private static final String TAG = "JavaRuntimeBootstrap";
    private static final Pattern MINECRAFT_RELEASE_PATTERN = Pattern.compile("(?<!\\d)(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    /*
     * prepare() runs immediately before applyPreparedVulkanCompatibilityJvmArgs().
     * Remember only the renderer class needed for pre-JVM native-loader routing so
     * LWJGL 3.4 cannot load Android's system Vulkan loader before Kopper/Turnip.
     * This value is overwritten on every launch.
     */
    private static volatile boolean sPreparedKopperZink = false;
    private static volatile boolean sPreparedLtwRenderer = false;

    private JavaRuntimeBootstrap() {
    }

    @NonNull
    public static RuntimePaths prepare(
            @NonNull Context context,
            @NonNull LaunchPlan plan,
            @NonNull RendererInterface renderer
    ) {
        PathManager.initContextConstants(context);
        sPreparedKopperZink = KopperZinkRenderer.isRenderer(renderer);
        sPreparedLtwRenderer = isLtwRenderer(renderer);

        // Some Adreno GPU devices require this because of the FD Guard loading too many files which causes crashing
        AdrenoSyncFenceFdGuard.startIfNeeded(renderer);

        FFmpegPluginCompat.Result ffmpeg = FFmpegPluginCompat.discoverForReplayMod(context, plan.getGameDirectory());
        LiteGlesLaunchPreloader.Result liteGles = prepareLiteGlesIfNeeded(context, plan, renderer);
        RuntimePaths paths = RuntimePaths.resolve(context, plan, renderer, ffmpeg, liteGles);
        DiscordRpcCompatPatch.apply(context, plan);
        applyEnvironment(context, plan, renderer, paths, ffmpeg, liteGles);
        pushLdLibraryPath(paths);

        DroidBridgeMesaSupport.preload(context, renderer);

        boolean enableWebRtcNative = shouldEnableWebRtcNativeForLaunch(plan);
        if (enableWebRtcNative) {
            preloadAndroidWebRtcNativeTest(context);
            if (shouldPreloadLinuxDesktopShims(renderer)) {
                preloadWebRtcLinuxDesktopShims(context);
            } else {
                Logging.i(TAG, "Skipping Linux desktop/WebRTC shims for DroidBridge Mesa renderer to avoid libdrm/libgbm/X11 shadowing");
            }
        } else {
            Logging.i(TAG, "Skipping WebRTC/P2P native preloads for normal Minecraft multiplayer launch.");
        }

        if (MobileGluesConfigHelper.isMobileGluesRenderer(renderer)) {
            safeAppendLog("DroidBridgeMobileGlues: bridge source v19 active"
                    + " MG_DIR_PATH=" + safeEnv("MG_DIR_PATH")
                    + " rendererLibrary=" + sanitizeLibraryName(renderer.getRendererLibrary()));
        }
        safeAppendLog("RuntimeBootstrap: preloading runtime and graphics libraries");
        preloadRuntimeAndGraphics(plan, renderer, paths, liteGles);
        safeAppendLog("RuntimeBootstrap: runtime and graphics preload complete");
        return paths;
    }

    @Nullable
    private static LiteGlesLaunchPreloader.Result prepareLiteGlesIfNeeded(
            @NonNull Context context,
            @NonNull LaunchPlan plan,
            @NonNull RendererInterface renderer
    ) {
        if (!isLiteGlesRenderer(renderer)) {
            return null;
        }

        File stagedNativeDir = new File(PathManager.DIR_CACHE, "natives/" + plan.getVersionId());
        try {
            Logging.i(TAG, "Preparing LiteGLES renderer plugin into " + stagedNativeDir.getAbsolutePath());
            LiteGlesLaunchPreloader.Result result = LiteGlesLaunchPreloader.prepare(
                    context,
                    LiteGlesLaunchPreloader.PACKAGE_NAME,
                    stagedNativeDir
            );
            Logging.i(TAG, "LiteGLES renderer staged OSMesa=" + result.stagedOSMesaLibrary.getAbsolutePath());
            return result;
        } catch (Throwable throwable) {
            Logging.e(TAG, "LiteGLES renderer plugin preload failed", throwable);
            throw new IllegalStateException("LiteGLES renderer plugin preload failed", throwable);
        }
    }

    private static void applyEnvironment(
            @NonNull Context context,
            @NonNull LaunchPlan plan,
            @NonNull RendererInterface renderer,
            @NonNull RuntimePaths paths,
            @NonNull FFmpegPluginCompat.Result ffmpeg,
            @Nullable LiteGlesLaunchPreloader.Result liteGles
    ) {
        LinkedHashMap<String, String> env = new LinkedHashMap<>();
        seedRendererEnvironmentReset(env);

        env.put("JAVA_HOME", plan.getRuntimeDirectory().getAbsolutePath());
        env.put("HOME", plan.getGameDirectory().getAbsolutePath());
        env.put("TMPDIR", PathManager.DIR_CACHE.getAbsolutePath());
        env.put("DROIDBRIDGE_NATIVEDIR", PathManager.DIR_NATIVE_LIB);
        env.put("LD_LIBRARY_PATH", paths.ldLibraryPath);
        env.put("PATH", new File(plan.getRuntimeDirectory(), "bin").getAbsolutePath() + ":" + safeEnv("PATH"));
        env.put("AWTSTUB_WIDTH", resolveJvmArgValue(plan, "-Dglfwstub.windowWidth=", "1"));
        env.put("AWTSTUB_HEIGHT", resolveJvmArgValue(plan, "-Dglfwstub.windowHeight=", "1"));
        env.put("MOD_ANDROID_RUNTIME", PathManager.DIR_RUNTIME_MOD != null ? PathManager.DIR_RUNTIME_MOD.getAbsolutePath() : "");
        // Republish this explicitly for the embedded OpenJDK VM. Relying only on
        // the ART-side Os.setenv call made the 26+ Vulkan frame counter lose its
        // output path on some devices/package variants.
        // Reset the cross-VM sample for every launch, not only SDL3 launches.
        // Minecraft 26.2 and VulkanMod also publish through this file.
        File fpsSampleFile = DroidBridgeSdlFpsBridge.prepare(context);
        env.put("DROIDBRIDGE_SDL3_FPS_FILE", fpsSampleFile.getAbsolutePath());
        // Controllable moves its virtual mouse through glfwSetCursorPos(). The
        // desktop GLFW implementation later emits a cursor-position callback, but
        // DroidBridge keeps cursor warps silent to prevent grabbed-camera flings.
        // Enable callbacks only for Controllable and only while the cursor is not
        // grabbed; the native bridge enforces the grabbed-state restriction.
        boolean controllableVirtualMouse = ControllerModCompat.hasControllable(plan.getGameDirectory());
        env.put("DROIDBRIDGE_GLFW_CURSOR_WARP_CALLBACKS", controllableVirtualMouse ? "1" : "0");
        // Legacy LWJGL2 clients report live glfwSwapInterval changes reliably.
        // Export this independently of the renderer so the native EGL bridge can
        // honor an in-game VSync toggle without weakening modern 26+ safeguards.
        env.put("DROIDBRIDGE_LEGACY_LWJGL2",
                shouldUseLegacyMesaCompatibilityProfile(plan) ? "1" : "");
        env.put("DROIDBRIDGE_MOBILEGLUES_RENDERER",
                isMobileGluesRenderer(renderer) ? "1" : "");
        env.put("DROIDBRIDGE_RENDERER", isLtwRenderer(renderer) ? "opengles3_ltw" : renderer.getRendererId());
        env.putAll(renderer.getRendererEnv());
        if (shouldApplyDriverPluginEnvironment(renderer)) {
            env.putAll(DriverPluginManager.buildEnvironment(context, renderer, plan.isUseSystemVulkanDriver()));
        }
        if (plan.isUseSystemVulkanDriver()) {
            applySystemVulkanEnvironment(env);
        }

        if (isLtwRenderer(renderer)) {
            boolean minecraft26Plus = isMinecraft26OrNewer(plan.getEffectiveMinecraftVersionId());
            env.put("DROIDBRIDGE_RENDERER", "opengles3_ltw");
            env.put("POJAV_RENDERER", "opengles3_ltw");
            env.put("DROIDBRIDGE_EGL", "libltw.so");
            env.put("DROIDBRIDGE_EGL_LIBRARY", "libltw.so");
            env.put("DROIDBRIDGE_RENDERER_LIBRARY", "libltw.so");
            env.put("POJAV_RENDERER_LIBRARY", "libltw.so");
            env.put("POJAVEXEC_EGL", "libltw.so");
            env.put("POJAVEXEC_EGL_LIBRARY", "libltw.so");
            env.put("LIBGL_ES", minecraft26Plus ? "2" : "3");
            env.put("LIBGL_NOERROR", minecraft26Plus ? "1" : "");
            env.put("LTW_NEVER_FLUSH_BUFFERS", minecraft26Plus ? "1" : "0");
            env.put("LTW_COHERENT_DYNAMIC_STORAGE", minecraft26Plus ? "1" : "0");
            env.put("DROIDBRIDGE_USE_SYSTEM_VULKAN", "1");
            env.put("DRIVER_PATH", "");
            env.put("VK_ICD_FILENAMES", "");
            env.put("VK_DRIVER_FILES", "");
            env.put("LIBGL_DRIVERS_PATH", "");
            env.put("EGL_DRIVERS_PATH", "");
            env.put("MESA_LOADER_DRIVER_OVERRIDE", "");
            env.put("GALLIUM_DRIVER", "");
            env.put("OSMESA_LIB", "");
            String message = "RuntimeBootstrap: LTW compatibility profile minecraft26Plus="
                    + minecraft26Plus + " version=" + plan.getEffectiveMinecraftVersionId();
            Logging.i(TAG, message);
            safeAppendLog(message);
        }

        // DroidBridge Mesa needs a strict env order. Do not run the generic
        // OSMesa/vulkan_zink alias block for it; that block sets OSMESA_LIB and
        // pushes libdroidbridge_runtime into osm_init_context. The Mesa-specific block below
        // owns all DROIDBRIDGE*/MESA aliases for Zink/Turnip and KGSL.
        String eglName = sanitizeLibraryName(renderer.getRendererEGL());
        if (!DroidBridgeMesaSupport.isDroidBridgeMesaRenderer(renderer)) {
            // Some renderer bridge/native paths expect DROIDBRIDGE_EGL to be a concrete
            // library name. Do not blindly reuse the renderer library for OpenGL ES
            // wrappers: libgl4es/libng_gl4es are GL wrappers, not EGL providers.
            // Using them as DROIDBRIDGE_EGL makes eglGetProcAddress/eglCreateContext
            // resolve to null and crashes libdroidbridge_runtime gl_init.
            if (eglName.isEmpty()) {
                eglName = inferDroidBridgeExecEgl(renderer);
            }
            if (!eglName.isEmpty()) {
                env.put("DROIDBRIDGE_EGL", eglName);
            }
            applyRendererBridgeAliases(env, renderer, eglName);
        }

        applyLiteGlesEnvironmentOverrides(env, liteGles);
        applyMobileGluesConfigEnvironment(context, renderer, env);
        applyMobileGluesZeroToOneDepthCompatibility(plan, renderer, env);
        applySnapshot5PlusMobileGluesShaderCompatibility(plan, renderer, env);

        String mesaDriverPath = isLtwRenderer(renderer) || liteGles != null || DroidBridgeMesaSupport.isDroidBridgeMesaRenderer(renderer)
                ? null
                : findMesaDriverPath(paths);
        if (mesaDriverPath != null) {
            env.put("LIBGL_DRIVERS_PATH", mesaDriverPath);
            env.put("EGL_DRIVERS_PATH", mesaDriverPath);
        }

        if (DroidBridgeMesaSupport.isMesaZinkTurnipRenderer(renderer)) {
            DroidBridgeMesaSupport.applyZinkTurnipEnvironment(context, renderer, env);
        } else if (DroidBridgeMesaSupport.isDroidBridgeMesaRenderer(renderer)) {
            boolean useNativeGlfw = DroidBridgeMesaSupport.isNativeGlfwKgslRenderer(renderer)
                    && DroidBridgeMesaSupport.shouldUseNativeGlfwForModernLwjgl(
                            plan.getVersionId(),
                            plan.getLwjglNativeDirectory()
                    )
                    && DroidBridgeMesaSupport.isNativeGlfwLibraryAvailable(context);
            DroidBridgeMesaSupport.applyEnvironment(context, renderer, env, useNativeGlfw);
            if (DroidBridgeMesaSupport.isNativeGlfwKgslRenderer(renderer)) {
                safeAppendLog("RuntimeBootstrap: Freedreno context route="
                        + (useNativeGlfw ? "NativeGLFW" : "GLBridge")
                        + " lwjglNatives=" + plan.getLwjglNativeDirectory().getAbsolutePath());
            }
        }
        // DroidBridgeMesaSupport may apply Turnip defaults for Zink. Re-assert
        // System Vulkan last when the user selected it.
        if (plan.isUseSystemVulkanDriver()) {
            applySystemVulkanEnvironment(env);
        }
        applySystemVulkanCompatibilityEnvironment(plan, env);
        applyLegacyMesaCompatibilityProfileIfNeeded(plan, renderer, env);
        applyLegacyOptiFineShaderCompatibilityProfileIfNeeded(plan, renderer, env);
        applyBta8MesaCompatibilityProfileIfNeeded(plan, renderer, env);
        applySnapshot5PlusFreedrenoPersistentMapCompatibility(plan, renderer, env);
        applyVulkanVsyncEnvironment(context, plan, env);
        applyNativeMesaVsyncEnvironment(plan, renderer, env);
        applyWrappedSdl3OpenGlVsyncEnvironment(context, plan, renderer, env);
        applyLtwSdl3ColorCompatibility(plan, renderer, env);

        String jsph = findJsphLibrary(paths);
        if (jsph != null) {
            env.put("JSP", jsph);
        }

        if (ffmpeg.available && ffmpeg.executablePath != null) {
            env.put("JAVALAUNCHER_FFMPEG_PATH", ffmpeg.executablePath);
            if (ffmpeg.ffprobePath != null) {
                env.put("JAVALAUNCHER_FFPROBE_PATH", ffmpeg.ffprobePath);
            }
            if (ffmpeg.libraryPath != null) {
                env.put("JAVALAUNCHER_FFMPEG_LIBRARY_PATH", ffmpeg.libraryPath);
            }
            env.put("JAVALAUNCHER_FFMPEG_USE_LINKER", "1");
            Logging.i(TAG, "Replay Mod FFmpeg executable=" + ffmpeg.executablePath
                    + " ffprobe=" + (ffmpeg.ffprobePath != null ? ffmpeg.ffprobePath : "<missing>"));
        } else if (ffmpeg.replayModPresent) {
            Logging.i(TAG, "Replay Mod detected, but DroidBridge FFmpeg plugin is unavailable: "
                    + (ffmpeg.errorMessage != null ? ffmpeg.errorMessage : "unknown reason"));
        }

        for (Map.Entry<String, String> entry : env.entrySet()) {
            setEnv(entry.getKey(), entry.getValue());
        }
    }





    private static void applyLegacyMesaCompatibilityProfileIfNeeded(
            @NonNull LaunchPlan plan,
            @NonNull RendererInterface renderer,
            @NonNull LinkedHashMap<String, String> env
    ) {
        if (!DroidBridgeMesaSupport.isDroidBridgeMesaRenderer(renderer)
                && !KopperZinkRenderer.isRenderer(renderer)) return;
        if (!shouldUseLegacyMesaCompatibilityProfile(plan)) return;

        /*
         * Minecraft <= 1.12.x/LWJGL2-era clients still use fixed-function and
         * compatibility-only OpenGL entry points. The direct Freedreno/KGSL path
         * normally requests a Core profile first for modern Minecraft, but a Core
         * profile makes 1.12.2 render as a white screen with repeated GL 1282/1280
         * errors. Match the working legacy launcher path by forcing a Mesa
         * compatibility profile only for these old clients.
         */
        env.put("DROIDBRIDGE_GL_LEGACY_COMPAT_PROFILE", "1");
        env.put("DROIDBRIDGE_MESA_LEGACY_COMPAT_PROFILE", "1");
        env.put("DROIDBRIDGE_EGL_FORCE_COMPAT_PROFILE", "1");
        env.put("DROIDBRIDGE_EGL_FORCE_CORE_PROFILE", "");
        env.put("MESA_GL_VERSION_OVERRIDE", "4.6COMPAT");
        env.put("MESA_GLSL_VERSION_OVERRIDE", "460");

        if (shouldDisableLegacyOcclusionQuery(plan)) {
            env.put("DROIDBRIDGE_DISABLE_LEGACY_OCCLUSION_QUERY", "1");
            env.put("DROIDBRIDGE_LEGACY_OCCLUSION_QUERY_STUB", "1");
            Logging.i(TAG, "Legacy Mesa GL profile: enabling occlusion-query stub for "
                    + plan.getVersionId());
        } else {
            env.put("DROIDBRIDGE_DISABLE_LEGACY_OCCLUSION_QUERY", "");
            env.put("DROIDBRIDGE_LEGACY_OCCLUSION_QUERY_STUB", "");
        }

        Logging.i(TAG, "Legacy Mesa GL profile: forcing compatibility context for "
                + plan.getVersionId()
                + " renderer="
                + renderer.getRendererId());
    }

    private static void applyLegacyOptiFineShaderCompatibilityProfileIfNeeded(
            @NonNull LaunchPlan plan,
            @NonNull RendererInterface renderer,
            @NonNull LinkedHashMap<String, String> env
    ) {
        if (!isLegacyOptiFineLaunch(plan)) return;

        boolean mesaDesktopRenderer = DroidBridgeMesaSupport.isDroidBridgeMesaRenderer(renderer)
                || DroidBridgeMesaSupport.isMesaZinkTurnipRenderer(renderer)
                || DroidBridgeMesaSupport.isPureVulkanZinkRenderer(renderer)
                || KopperZinkRenderer.isRenderer(renderer);
        if (!mesaDesktopRenderer) return;

        /*
         * OptiFine's LWJGL2-era shader pipeline compiles old GLSL sources such as
         * Minecraft's #version 120 post shaders. Do not globally advertise GLSL
         * 4.60 to this path; legacy compatibility code may use the reported language
         * version when selecting shader behavior. Keep the desktop compatibility
         * context and let each shader's own #version directive define its grammar.
         *
         * Validation also stays enabled for this path. Legacy OptiFine can reach
         * GL_INVALID_VALUE/GL_INVALID_OPERATION during shader/FBO construction, so
         * suppressing validation is unsafe here.
         */
        env.put("DROIDBRIDGE_LEGACY_OPTIFINE_SHADER_COMPAT", "1");
        env.put("DROIDBRIDGE_EGL_FORCE_COMPAT_PROFILE", "1");
        env.put("DROIDBRIDGE_EGL_FORCE_CORE_PROFILE", "");
        env.put("MESA_GL_VERSION_OVERRIDE", "4.6COMPAT");
        env.put("MESA_GLSL_VERSION_OVERRIDE", "");
        env.put("DROIDBRIDGE_PROP_MESA_GL_VERSION_OVERRIDE", "4.6COMPAT");
        env.put("DROIDBRIDGE_PROP_MESA_GLSL_VERSION_OVERRIDE", "");
        env.put("MESA_NO_ERROR", "0");
        env.put("LIBGL_NOERROR", "0");
        env.put("mesa_glthread", "false");

        String message = "RuntimeBootstrap: legacy OptiFine shader compatibility profile"
                + " version=" + plan.getVersionId()
                + " renderer=" + renderer.getRendererId()
                + " gl=4.6COMPAT glslOverride=unset validation=on glthread=off";
        Logging.i(TAG, message);
        safeAppendLog(message);
    }

    private static boolean isLegacyOptiFineLaunch(@NonNull LaunchPlan plan) {
        String versionId = safeLower(plan.getVersionId());
        String mainClass = safeLower(plan.getMainClass());
        String classPath = safeLower(plan.getClassPath());
        boolean optiFine = versionId.contains("optifine") || classPath.contains("optifine");
        if (!optiFine) return false;
        return shouldUseLegacyMesaCompatibilityProfile(plan)
                || mainClass.contains("launchwrapper");
    }

    /**
     * BTA 8 is modern LWJGL3 and requires desktop OpenGL 4.1+, but its renderer
     * still carries compatibility-era player/item state. A strict Mesa Core
     * profile renders the world correctly while the player and first-person hand
     * can collapse to black. Give BTA 8+ a modern 4.6 compatibility context
     * without enabling any of the old LWJGL2/fixed-function launcher shims.
     */
    private static void applyBta8MesaCompatibilityProfileIfNeeded(
            @NonNull LaunchPlan plan,
            @NonNull RendererInterface renderer,
            @NonNull LinkedHashMap<String, String> env
    ) {
        if (!BtaRendererPolicy.isBta8OrNewer(plan.getVersionId(), null)) return;
        if (!DroidBridgeMesaSupport.isDroidBridgeMesaRenderer(renderer)
                && !KopperZinkRenderer.isRenderer(renderer)) return;

        // Context profile only: BTA 8 remains on the modern LWJGL3/GLFW path.
        env.put("DROIDBRIDGE_GL_LEGACY_COMPAT_PROFILE", "");
        env.put("DROIDBRIDGE_LEGACY_OPTIFINE_SHADER_COMPAT", "");
        env.put("DROIDBRIDGE_MESA_LEGACY_COMPAT_PROFILE", "");
        env.put("DROIDBRIDGE_EGL_FORCE_COMPAT_PROFILE", "1");
        env.put("DROIDBRIDGE_EGL_FORCE_CORE_PROFILE", "");
        env.put("MESA_GL_VERSION_OVERRIDE", "4.6COMPAT");
        env.put("MESA_GLSL_VERSION_OVERRIDE", "460");
        env.put("DROIDBRIDGE_DISABLE_LEGACY_OCCLUSION_QUERY", "");
        env.put("DROIDBRIDGE_LEGACY_OCCLUSION_QUERY_STUB", "");

        /*
         * BTA 8+ exercises compatibility-profile state, dynamic textures and
         * mipmap/resource updates immediately before entering a world. With
         * Kopper running on Turnip, Mesa 26.1.x can otherwise crash inside
         * libgallium_dri.so while those operations are still being reordered or
         * compiled asynchronously. Keep this workaround scoped to BTA + Kopper:
         * modern Minecraft retains the normal fast Kopper profile.
         *
         * - auto: use Mesa's normal descriptor manager instead of forcing lazy.
         * - sync: full Vulkan barriers before every draw/dispatch.
         * - flushsync: make flush/present completion synchronous.
         * - noreorder: preserve GL command order.
         * - nobgc: disable asynchronous background pipeline compilation.
         */
        if (KopperZinkRenderer.isRenderer(renderer)) {
            env.put("ZINK_DESCRIPTORS", "auto");
            env.put("ZINK_DEBUG", "sync,flushsync,noreorder,nobgc");
            env.put("mesa_glthread", "false");

            /*
             * BTA 8.0.x can poison Mesa's persistent shader/disk cache during
             * its first renderer/world transition on Zink/Turnip. Once that
             * happens, later launches reproducibly fault in the same Gallium
             * worker address before the menu/world is usable, even with Zink's
             * own background compiler and Mesa glthread disabled. Do not let
             * BTA read or write that persistent cache at all. This is purposely
             * BTA + Kopper only; normal Minecraft keeps the disk cache.
             *
             * Set both names because Mesa 26.x uses MESA_SHADER_CACHE_DISABLE,
             * while older Mesa compatibility code may still consult the GLSL
             * spelling. "true" is the value expected by Mesa's boolean option
             * parser and overrides any earlier renderer/plugin default.
             */
            env.put("MESA_SHADER_CACHE_DISABLE", "true");
            env.put("MESA_GLSL_CACHE_DISABLE", "true");

            /*
             * mesa_glthread only disables Mesa's GL command marshalling thread.
             * Gallium has a second, independent threaded_context worker selected
             * through GALLIUM_THREAD. The BTA 8/Kopper failure is consistently a
             * native libgallium_dri worker-thread SIGSEGV at the same offset, even
             * with glthread and Zink background compilation disabled. Disable the
             * Gallium threaded context as well so BTA's startup texture/mipmap
             * uploads execute synchronously on the owning GL context.
             *
             * This stays BTA + Kopper only; other games keep Gallium threading.
             */
            env.put("GALLIUM_THREAD", "0");
            env.put("DROIDBRIDGE_KOPPER_BTA_STABILITY", "1");
        } else {
            env.put("DROIDBRIDGE_KOPPER_BTA_STABILITY", "");
        }

        String message = "RuntimeBootstrap: BTA 8+ Mesa visual compatibility profile"
                + " version=" + plan.getVersionId()
                + " profile=4.6COMPAT lwjgl=modern legacyShims=false"
                + (KopperZinkRenderer.isRenderer(renderer)
                ? " kopperStability=sync+flushsync+noreorder+nobgc descriptors=auto shaderCache=off galliumThread=off"
                : "");
        Logging.i(TAG, message);
        safeAppendLog(message);
    }

    private static boolean shouldUseLegacyMesaCompatibilityProfile(@NonNull LaunchPlan plan) {
        String versionId = safeLower(plan.getVersionId());
        String mainClass = safeLower(plan.getMainClass());
        String classPath = safeLower(plan.getClassPath());

        // BTA 8+ still inherits beta 1.7.3, but its client is modern LWJGL3 and
        // requires desktop OpenGL 4.1+. Do not classify bta-v8.x as legacy merely
        // because the version id begins with the letter 'b'.
        if (BtaRendererPolicy.isBta8OrNewer(plan.getVersionId(), null)) return false;

        if (isLegacyNamedMinecraftVersion(versionId)) return true;
        if (isLegacyReleaseString(versionId)) return true;

        if (mainClass.contains("launchwrapper")
                || classPath.contains("launchwrapper-")
                || classPath.contains("net/minecraft/launchwrapper")) {
            return true;
        }

        return false;
    }

    private static boolean isLegacyNamedMinecraftVersion(@NonNull String value) {
        return value.startsWith("rd-")
                || value.startsWith("classic")
                || value.startsWith("inf-")
                || value.startsWith("infdev")
                || value.startsWith("indev")
                || value.startsWith("a")
                || value.startsWith("b");
    }

    private static boolean shouldDisableLegacyOcclusionQuery(@NonNull LaunchPlan plan) {
        String versionId = safeLower(plan.getVersionId());
        return isPreReleaseLegacyOcclusionQueryVersion(versionId);
    }

    private static boolean isPreReleaseLegacyOcclusionQueryVersion(@NonNull String versionId) {
        return versionId.startsWith("rd-")
                || versionId.startsWith("classic")
                || versionId.startsWith("inf-")
                || versionId.startsWith("infdev")
                || versionId.startsWith("indev")
                || versionId.startsWith("a")
                || versionId.startsWith("b");
    }

    private static boolean isLegacyReleaseString(@NonNull String value) {
        Matcher matcher = MINECRAFT_RELEASE_PATTERN.matcher(value);
        while (matcher.find()) {
            try {
                int major = Integer.parseInt(matcher.group(1));
                int minor = Integer.parseInt(matcher.group(2));
                if (major == 1 && minor <= 12) return true;
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
    }

    @NonNull
    private static String safeLower(@Nullable String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static void applyVulkanVsyncEnvironment(
            @NonNull Context context,
            @NonNull LaunchPlan plan,
            @NonNull LinkedHashMap<String, String> env
    ) {
        // options.txt is the authoritative state for both the launch-time setting
        // and Minecraft's live Video Settings toggle. The launcher setting seeds
        // this file before the runtime bootstrap reaches this point.
        boolean enabled = readMinecraftVsyncOption(plan.getGameDirectory());
        int targetFps = resolveDisplayRefreshRateFps(context);

        env.put("DROIDBRIDGE_VULKAN_FORCE_FIFO", enabled ? "1" : "0");
        // Pace once at GLFW's logical game-frame boundary. Android Vulkan FIFO
        // does not reliably block Minecraft's CPU/render loop on every driver, so
        // the logical pacer is required to make the game itself match display Hz.
        env.put("DROIDBRIDGE_VSYNC_FRAME_PACING", enabled ? "1" : "0");
        env.put("DROIDBRIDGE_VSYNC_TARGET_FPS", Integer.toString(targetFps));

        // MAILBOX can still throttle the render loop to a display-related
        // cadence on Android. IMMEDIATE is the uncapped/tearing-allowed mode
        // requested when Minecraft VSync is disabled.
        env.put("MESA_VK_WSI_PRESENT_MODE", enabled ? "fifo" : "immediate");

        Logging.i(TAG, "Vulkan VSync enabled=" + enabled
                + " targetFps=" + targetFps
                + " MESA_VK_WSI_PRESENT_MODE=" + env.get("MESA_VK_WSI_PRESENT_MODE"));
    }

    private static void applyNativeMesaVsyncEnvironment(
            @NonNull LaunchPlan plan,
            @NonNull RendererInterface renderer,
            @NonNull LinkedHashMap<String, String> env
    ) {
        if (!DroidBridgeMesaSupport.isNativeGlfwKgslRenderer(renderer)) {
            return;
        }

        boolean enabled = readMinecraftVsyncOption(plan.getGameDirectory());
        int targetFps = parseTargetFps(env.get("DROIDBRIDGE_VSYNC_TARGET_FPS"));

        // Direct Freedreno is OpenGL. The separate Vulkan/Zink preference must
        // never decide whether its Surface swap interval is available.
        env.put("DROIDBRIDGE_VSYNC_IN_ZINK", "1");
        env.put("DROIDBRIDGE_VULKAN_FORCE_FIFO", "0");
        env.put("MESA_VK_WSI_PRESENT_MODE", "");

        boolean bta8 = BtaRendererPolicy.isBta8OrNewer(plan.getVersionId(), null);
        if (bta8) {
            /*
             * BTA 8 toggles VSync live with GLFW. Do not clamp that call to the
             * launch-time options.txt value and do not arm the generic native
             * software pacer. The isolated BTA GLFW shim owns an adaptive
             * single-pacer fallback: real eglSwapInterval gets first chance to
             * block, and Java only waits for the remaining frame time when EGL
             * returns early. This avoids the 10-15 FPS multi-wait failure.
             */
            env.put("FORCE_VSYNC", "");
            env.put("DROIDBRIDGE_VSYNC_FRAME_PACING", "0");
        } else {
            // Minecraft 26.2/Sodium currently requests glfwSwapInterval(0) even
            // when options.txt says enableVsync:true (the working log proves this).
            // Reuse the native bridge's existing FORCE_VSYNC clamp so the game
            // option, not the Vulkan setting, controls direct Mesa presentation.
            env.put("FORCE_VSYNC", enabled ? "true" : "false");
            env.put("DROIDBRIDGE_VSYNC_FRAME_PACING", "1");
        }
        env.put("DROIDBRIDGE_VSYNC_TARGET_FPS", Integer.toString(targetFps));

        String message = "RuntimeBootstrap: Freedreno VSync "
                + (enabled ? "enabled" : "disabled")
                + " from options.txt enableVsync=" + enabled
                + " targetFps=" + targetFps
                + " bta8LiveGlfw=" + bta8
                + "; Vulkan/Zink VSync preference ignored for direct KGSL OpenGL";
        Logging.i(TAG, message);
        safeAppendLog(message);
    }

    private static boolean isEnabledEnvironmentValue(@Nullable String value) {
        if (value == null) return false;
        String normalized = value.trim();
        return !normalized.isEmpty()
                && !"0".equals(normalized)
                && !"false".equalsIgnoreCase(normalized)
                && !"off".equalsIgnoreCase(normalized)
                && !"no".equalsIgnoreCase(normalized);
    }

    private static int parseTargetFps(@Nullable String value) {
        if (value != null) {
            try {
                int parsed = Integer.parseInt(value.trim());
                if (parsed >= 30 && parsed <= 360) return parsed;
            } catch (Throwable ignored) {
            }
        }
        return 60;
    }

    private static void applyWrappedSdl3OpenGlVsyncEnvironment(
            @NonNull Context context,
            @NonNull LaunchPlan plan,
            @NonNull RendererInterface renderer,
            @NonNull LinkedHashMap<String, String> env
    ) {
        final boolean mobileGlues = isMobileGluesRenderer(renderer);
        final boolean krypton = isKryptonRenderer(renderer);
        final boolean ltw = isLtwRenderer(renderer);
        final boolean ltwSdl3 = ltw
                && (isMinecraft26_3Snapshot(plan.getVersionId())
                    || isMinecraft26_3Snapshot(plan.getEffectiveMinecraftVersionId()));
        final boolean legacyKryptonLwjgl2 = krypton
                && shouldUseLegacyMesaCompatibilityProfile(plan);

        /*
         * MobileGlues and Krypton both present through DroidBridge's EGL/GLFW
         * bridge on pre-26.2 releases and through the SDL3-aware path on newer
         * releases. LTW joins that wrapped SDL3 presentation path on 26.3.
         * Do not version-gate Krypton's launch-time VSync setup, but keep LTW
         * scoped to 26.3 so its already-working 26.1.2/26.2 GLFW path is unchanged.
         *
         * The old 26.2-only Krypton gate incorrectly sent normal releases such
         * as 1.20.1 and 1.21.11 through the generic cleanup branch below. That
         * branch reset DROIDBRIDGE_VSYNC_FRAME_PACING to 0 and the display target
         * to 60 after options.txt had already enabled VSync. eglSwapInterval(1)
         * was still accepted, but Android presentation did not reliably block
         * until the player toggled VSync off and on in Video Settings.
         *
         * Treat every Krypton launch as wrapped OpenGL. The legacy flag remains
         * separate because only old LWJGL2 clients make glfwSwapInterval itself
         * authoritative for live OFF requests.
         */
        final boolean supportedWrappedOpenGl = mobileGlues || krypton || ltwSdl3;

        if (supportedWrappedOpenGl
                && BtaRendererPolicy.isBta8OrNewer(plan.getVersionId(), null)) {
            boolean launchVsync = readMinecraftVsyncOption(plan.getGameDirectory());
            int targetFps = resolveDisplayRefreshRateFps(context);

            // BTA 8 owns live VSync through glfwSwapInterval. Never freeze the
            // state to options.txt and never stack DroidBridge's generic native
            // pacer on top of the BTA GLFW adaptive fallback.
            env.put("DROIDBRIDGE_MOBILEGLUES_FORCE_VSYNC", "0");
            env.put("DROIDBRIDGE_VSYNC_FRAME_PACING", "0");
            env.put("DROIDBRIDGE_VSYNC_TARGET_FPS", Integer.toString(targetFps));

            // Do not add a color-space workaround here. BTA 8's visual repair is
            // shader/FBO-state specific; changing the default framebuffer transfer
            // function would risk altering the otherwise-correct world colors.
            env.put("DROIDBRIDGE_MOBILEGLUES_DISABLE_FRAMEBUFFER_SRGB", "0");
            env.put("DROIDBRIDGE_SDL3_FORCE_OPAQUE_SURFACE", "0");
            env.put("DROIDBRIDGE_SDL3_LEGACY_SRGB_PASSTHROUGH", "0");

            String message = "RuntimeBootstrap: BTA 8+ wrapped OpenGL live VSync profile"
                    + " renderer=" + renderer.getRendererName()
                    + " launchVsync=" + launchVsync
                    + " nativePacer=false adaptiveGlfwPacer=true"
                    + " targetFps=" + targetFps;
            Logging.i(TAG, message);
            safeAppendLog(message);
            return;
        }

        if (!supportedWrappedOpenGl) {
            /*
             * Direct Freedreno Mesa also presents through SDL3 from Snapshot 4
             * onward. applyNativeMesaVsyncEnvironment() has already configured
             * the Minecraft VSync state and the real display refresh target.
             * Do not overwrite those shared SDL3 presentation variables with
             * the wrapped-renderer defaults, otherwise Mesa receives interval 1
             * but loses both its 120 Hz fallback pacer and launch-time target.
             */
            if (DroidBridgeMesaSupport.isNativeGlfwKgslRenderer(renderer)) {
                env.put("DROIDBRIDGE_MOBILEGLUES_FORCE_VSYNC", "0");
                env.put("DROIDBRIDGE_MOBILEGLUES_DISABLE_FRAMEBUFFER_SRGB", "0");
                env.put("DROIDBRIDGE_SDL3_FORCE_OPAQUE_SURFACE", "0");
                env.put("DROIDBRIDGE_SDL3_LEGACY_SRGB_PASSTHROUGH", "0");
                return;
            }

            /*
             * Minecraft 26.2 can use its native Vulkan backend while Krypton is
             * still the selected fallback renderer. applyVulkanVsyncEnvironment()
             * has already armed FIFO and the display-rate pacer for that route.
             * The old generic OpenGL cleanup below reset frame pacing to zero,
             * leaving FIFO selected but uncapped until Video Settings rewrote the
             * live VSync state. Preserve the launch-time pacing state only for an
             * actual System Vulkan launch; normal Krypton/OpenGL remains unchanged.
             */
            String rendererRoute = safeLower(env.get("DROIDBRIDGE_RENDERER"));
            String mesaMode = safeLower(env.get("DROIDBRIDGE_MESA_MODE"));
            boolean zinkPresentation = rendererRoute.contains("zink")
                    || mesaMode.contains("zink")
                    || safeLower(renderer.getRendererName()).contains("zink");
            boolean preserveVulkanFifo = isEnabledEnvironmentValue(
                    env.get("DROIDBRIDGE_VULKAN_FORCE_FIFO"))
                    && ((plan.isUseSystemVulkanDriver()
                            && isEnabledEnvironmentValue(env.get("DROIDBRIDGE_USE_SYSTEM_VULKAN")))
                        || zinkPresentation);
            int targetFps = preserveVulkanFifo
                    ? parseTargetFps(env.get("DROIDBRIDGE_VSYNC_TARGET_FPS"))
                    : 60;

            env.put("DROIDBRIDGE_MOBILEGLUES_FORCE_VSYNC", "0");
            // Preserve the live VSync state for a real System Vulkan launch.
            // Vulkan FIFO owns pacing; GLFW/SDL event polling never sleeps or counts frames.
            env.put("DROIDBRIDGE_VSYNC_FRAME_PACING", preserveVulkanFifo ? "1" : "0");
            env.put("DROIDBRIDGE_VSYNC_TARGET_FPS", Integer.toString(targetFps));
            env.put("DROIDBRIDGE_MOBILEGLUES_DISABLE_FRAMEBUFFER_SRGB", "0");
            env.put("DROIDBRIDGE_SDL3_FORCE_OPAQUE_SURFACE", "0");
            env.put("DROIDBRIDGE_SDL3_LEGACY_SRGB_PASSTHROUGH", "0");

            if (preserveVulkanFifo) {
                String message = "RuntimeBootstrap: preserved initial Vulkan/Zink VSync state"
                        + " renderer=" + renderer.getRendererName()
                        + " targetFps=" + targetFps
                        + " version=" + plan.getVersionId();
                Logging.i(TAG, message);
                safeAppendLog(message);
            }
            return;
        }

        boolean enabled = readMinecraftVsyncOption(plan.getGameDirectory());
        int targetFps = resolveDisplayRefreshRateFps(context);
        boolean snapshot4LegacySrgbPassthrough =
                isMinecraft26_3Snapshot(plan.getVersionId());

        /*
         * Krypton 26.2 still presents through GLFW/GLBridge, while Snapshot 4
         * moves the wrapped OpenGL route to SDL3. Both routes need the launch-time
         * frame-pacing flag because some Android EGL implementations accept
         * eglSwapInterval(1) without actually blocking presentation. Keep Krypton
         * 26.2+ aligned with MobileGlues so VSync works before the user toggles it.
         */
        // Legacy LWJGL2/Krypton reports the live game option through
        // glfwSwapInterval. Keep that request authoritative so turning VSync OFF
        // is not re-clamped by a launch-time compatibility flag. The shared
        // logical frame pacer below still supplies the Android presentation wait
        // that Krypton's accepted eglSwapInterval does not provide on all devices.
        env.put("DROIDBRIDGE_MOBILEGLUES_FORCE_VSYNC",
                legacyKryptonLwjgl2 ? "0" : (enabled ? "1" : "0"));

        /*
         * Snapshot 5 can reject MobileGlues' OpenGL EGL config and continue with
         * RenderPearl's Vulkan backend in the same process. Keep logical-frame
         * VSync state armed whenever either Minecraft VSync or the launch-time Vulkan
         * FIFO switch is enabled. OpenGL can consume the software-pacing flag, while
         * Vulkan treats it only as live state because FIFO owns presentation timing.
         */
        String vulkanForceFifoValue = env.get("DROIDBRIDGE_VULKAN_FORCE_FIFO");
        boolean vulkanFallbackVsync = vulkanForceFifoValue != null
                && !vulkanForceFifoValue.trim().isEmpty()
                && !"0".equals(vulkanForceFifoValue.trim())
                && !"false".equalsIgnoreCase(vulkanForceFifoValue.trim())
                && !"off".equalsIgnoreCase(vulkanForceFifoValue.trim())
                && !"no".equalsIgnoreCase(vulkanForceFifoValue.trim());
        boolean systemVulkanActive = isEnabledEnvironmentValue(
                env.get("DROIDBRIDGE_USE_SYSTEM_VULKAN"));

        /*
         * Minecraft's option must be authoritative here. Keeping the old
         * launcher-side Vulkan fallback flag ORed into this value made VSync OFF
         * remain capped at a display-related cadence after the player disabled it.
         */
        boolean presentationPacingEnabled = enabled;
        env.put("DROIDBRIDGE_VSYNC_FRAME_PACING", presentationPacingEnabled ? "1" : "0");
        env.put("DROIDBRIDGE_VULKAN_FORCE_FIFO", enabled ? "1" : "0");
        env.put("MESA_VK_WSI_PRESENT_MODE", enabled ? "fifo" : "immediate");
        env.put("DROIDBRIDGE_VSYNC_TARGET_FPS", Integer.toString(targetFps));

        /*
         * Snapshot 4 asks SDL for an sRGB EGL default framebuffer. MobileGlues and
         * Krypton then render Minecraft's already display-encoded UI/world values
         * into that attachment, causing a second sRGB transfer (pink Mojang screen
         * and raised/faded mid-tones). Keep the Android layer opaque, but ask the
         * EGL shim to remove SDL's EGL_GL_COLORSPACE_SRGB_KHR attribute so the GL
         * default framebuffer behaves like the legacy desktop pass-through path.
         */
        env.put("DROIDBRIDGE_SDL3_FORCE_OPAQUE_SURFACE",
                snapshot4LegacySrgbPassthrough ? "1" : "0");
        env.put("DROIDBRIDGE_SDL3_LEGACY_SRGB_PASSTHROUGH",
                snapshot4LegacySrgbPassthrough ? "1" : "0");
        env.put("DROIDBRIDGE_MOBILEGLUES_DISABLE_FRAMEBUFFER_SRGB", "0");

        String rendererName = mobileGlues
                ? "MobileGlues"
                : (ltwSdl3
                    ? "LTW"
                    : (legacyKryptonLwjgl2 ? "Legacy LWJGL2 Krypton" : "Krypton"));
        String message = "RuntimeBootstrap: " + rendererName + " presentation"
                + " vsync=" + enabled
                + " vulkanFallbackVsync=" + vulkanFallbackVsync
                + " systemVulkan=" + systemVulkanActive
                + " optionsAuthoritative=true"
                + " presentationPacing=" + presentationPacingEnabled
                + " targetFps=" + targetFps
                + " legacySrgbPassthrough=" + snapshot4LegacySrgbPassthrough
                + " forceOpaqueSurface=" + snapshot4LegacySrgbPassthrough
                + " version=" + plan.getVersionId();
        Logging.i(TAG, message);
        safeAppendLog(message);
    }

    private static void applyLtwSdl3ColorCompatibility(
            @NonNull LaunchPlan plan,
            @NonNull RendererInterface renderer,
            @NonNull LinkedHashMap<String, String> env
    ) {
        if (!isLtwRenderer(renderer)) return;

        /*
         * Minecraft 26.3 moved the OpenGL presentation path to SDL3. SDL asks
         * for an sRGB EGL default framebuffer there, while LTW exposes desktop
         * OpenGL over GLES and Minecraft's output is already display encoded.
         * Leaving SDL's sRGB surface request intact applies the transfer twice:
         * Mojang red becomes pink and the whole game looks washed out/too bright.
         *
         * DroidBridge already fixes this exact presentation mismatch for
         * MobileGlues/Krypton in the SDL3 EGL shim. v5 also put LTW through the
         * same guarded EGL path, so enable that existing byte-pass-through
         * behavior for LTW on 26.3 rather than changing LTW shaders or GL state.
         */
        boolean legacySrgbPassthrough =
                isMinecraft26_3Snapshot(plan.getVersionId())
                        || isMinecraft26_3Snapshot(plan.getEffectiveMinecraftVersionId());

        env.put("DROIDBRIDGE_SDL3_FORCE_OPAQUE_SURFACE",
                legacySrgbPassthrough ? "1" : "0");
        env.put("DROIDBRIDGE_SDL3_LEGACY_SRGB_PASSTHROUGH",
                legacySrgbPassthrough ? "1" : "0");
        env.put("DROIDBRIDGE_MOBILEGLUES_DISABLE_FRAMEBUFFER_SRGB", "0");

        String message = "RuntimeBootstrap: LTW SDL3 color compatibility"
                + " legacySrgbPassthrough=" + legacySrgbPassthrough
                + " forceOpaqueSurface=" + legacySrgbPassthrough
                + " version=" + plan.getEffectiveMinecraftVersionId();
        Logging.i(TAG, message);
        safeAppendLog(message);
    }

    private static boolean isMinecraft26OrNewer(@NonNull String minecraftVersion) {
        String value = minecraftVersion.trim().toLowerCase(Locale.ROOT);
        Matcher matcher = MINECRAFT_RELEASE_PATTERN.matcher(value);
        int minecraftMajor = -1;
        while (matcher.find()) {
            try {
                minecraftMajor = Integer.parseInt(matcher.group(1));
            } catch (Throwable ignored) {
                // Keep the last successfully parsed Minecraft token.
            }
        }
        return minecraftMajor >= 26;
    }

    private static boolean isMinecraft26_2OrNewer(@NonNull String minecraftVersion) {
        String value = minecraftVersion.trim().toLowerCase(Locale.ROOT);
        Matcher matcher = MINECRAFT_RELEASE_PATTERN.matcher(value);
        int minecraftMajor = -1;
        int minecraftMinor = -1;
        while (matcher.find()) {
            try {
                // Loader profiles normally end with the inherited Minecraft
                // version, e.g. fabric-loader-0.19.3-26.2. Keep the last dotted
                // version instead of treating the loader's own version as Minecraft.
                minecraftMajor = Integer.parseInt(matcher.group(1));
                minecraftMinor = Integer.parseInt(matcher.group(2));
            } catch (Throwable ignored) {
                // Keep the last successfully parsed token.
            }
        }
        return minecraftMajor > 26
                || (minecraftMajor == 26 && minecraftMinor >= 2);
    }

    private static boolean isMinecraft26_3Snapshot(@NonNull String minecraftVersion) {
        /*
         * Snapshot 4's SDL3/default-framebuffer presentation behavior carried
         * through the 26.3 pre-releases, release candidates and final release.
         * Launcher profile IDs are not guaranteed to begin with the Minecraft
         * version (Fabric commonly uses e.g. fabric-loader-0.19.5-26.3), so
         * checking startsWith("26.3-...") incorrectly disables the legacy sRGB
         * byte-pass-through/opaque-surface path on final Fabric 26.3.
         *
         * Resolve the last dotted version token just like isMinecraft26_2OrNewer()
         * does. The inherited Minecraft version is normally the last such token.
         */
        String value = minecraftVersion.trim().toLowerCase(Locale.ROOT);
        Matcher matcher = MINECRAFT_RELEASE_PATTERN.matcher(value);
        int minecraftMajor = -1;
        int minecraftMinor = -1;
        while (matcher.find()) {
            try {
                minecraftMajor = Integer.parseInt(matcher.group(1));
                minecraftMinor = Integer.parseInt(matcher.group(2));
            } catch (Throwable ignored) {
                // Keep the last successfully parsed token.
            }
        }
        return minecraftMajor == 26 && minecraftMinor == 3;
    }

    /**
     * Minecraft 26.3 Snapshot 5 changed RenderPearl's first GUI staging buffer
     * to an immutable persistent-mapped allocation. Direct Freedreno/KGSL can
     * create the buffer and compile shaders correctly, but Mesa 26.1.2 returns
     * NULL for that first persistent map on the SDL3 Android context.
     *
     * Keep Mesa's real 4.6 context, buffer-storage and direct-state-access
     * implementation. Earlier revisions hid buffer_storage as a temporary
     * workaround; v18 retries the native persistent path now that SDL3 context
     * continuity and real object-name creation are fixed. Snapshot 4 and all
     * older Minecraft versions remain unchanged.
     */
    private static void applySnapshot5PlusFreedrenoPersistentMapCompatibility(
            @NonNull LaunchPlan plan,
            @NonNull RendererInterface renderer,
            @NonNull LinkedHashMap<String, String> env
    ) {
        if (!DroidBridgeMesaSupport.isNativeGlfwKgslRenderer(renderer)) return;
        if (!requiresSnapshot5PlusPersistentMapCompatibility(plan.getVersionId())) return;

        /*
         * v18: the original Snapshot 5 persistent-map failure happened before
         * the SDL3 cached-context and real buffer-name fixes existed. Now that
         * glCreateBuffers/glCreateFramebuffers and every map call run on SDL's
         * verified Mesa context, keep RenderPearl's intended immutable
         * persistent-buffer path instead of forcing the slower 4.3 mutable path.
         */
        env.remove("DROIDBRIDGE_SDL3_DISABLE_PERSISTENT_MAPPING");
        env.put("DROIDBRIDGE_SDL3_FREEDRENO_MUTABLE_BUFFERS", "1");
        env.put("DROIDBRIDGE_SDL3_FREEDRENO_FRAMEBUFFER_NAMES", "1");
        env.put("DROIDBRIDGE_SDL3_FREEDRENO_PERSISTENT_RETRY", "1");
        env.put("DROIDBRIDGE_SDL3_QUIET_GLSL_WARNINGS", "1");

        env.put("MESA_GL_VERSION_OVERRIDE", "4.6");
        env.put("MESA_GLSL_VERSION_OVERRIDE", "460");
        env.remove("MESA_EXTENSION_OVERRIDE");
        env.remove("mesa_glthread");

        String message = "RuntimeBootstrap: Snapshot 5+ Freedreno native persistent-buffer compatibility"
                + " version=" + plan.getVersionId()
                + " maxGL=4.6 persistentMapping=true"
                + " bufferStorage=real directStateAccess=enabled"
                + " nativeMap=preferred staging=nonPersistentFallbackOnly"
                + " framebufferNames=guarded adaptiveVsync=true";
        Logging.i(TAG, message);
        safeAppendLog(message);
    }

    private static boolean requiresMobileGluesZeroToOneDepth(
            @NonNull String minecraftVersion
    ) {
        String value = minecraftVersion.trim().toLowerCase(Locale.ROOT)
                .replace('_', '-')
                .replace(' ', '-');
        while (value.contains("--")) value = value.replace("--", "-");

        // Minecraft 26.2 is the first release where the OpenGL renderer used by
        // DH expects the modern 0..1 clip-depth convention. Keep this narrowly
        // scoped to the two versions under test so 26.1.2 and older keep their
        // known-good -1..1 MobileGlues path.
        return value.equals("26.2")
                || value.startsWith("26.2-")
                || value.startsWith("26.2.")
                || value.equals("26.3")
                || value.startsWith("26.3-")
                || value.startsWith("26.3.");
    }

    private static void applyMobileGluesZeroToOneDepthCompatibility(
            @NonNull LaunchPlan plan,
            @NonNull RendererInterface renderer,
            @NonNull LinkedHashMap<String, String> env
    ) {
        if (!MobileGluesConfigHelper.isMobileGluesRenderer(renderer)) return;
        if (plan.isUseSystemVulkanDriver()) return;

        String effective = plan.getEffectiveMinecraftVersionId();
        String launch = plan.getVersionId();
        boolean enabled = (effective != null && requiresMobileGluesZeroToOneDepth(effective))
                || (launch != null && requiresMobileGluesZeroToOneDepth(launch));
        if (!enabled) return;

        env.put("DROIDBRIDGE_MOBILEGLUES_ZERO_TO_ONE_DEPTH", "1");
        String message = "RuntimeBootstrap: MobileGlues 26.2+ zero-to-one clip-depth compatibility enabled"
                + " version=" + plan.getVersionId()
                + " origin=LOWER_LEFT depth=ZERO_TO_ONE";
        Logging.i(TAG, message);
        safeAppendLog(message);
    }

    private static boolean requiresSnapshot5PlusPersistentMapCompatibility(
            @NonNull String minecraftVersion
    ) {
        String value = minecraftVersion.trim().toLowerCase(Locale.ROOT)
                .replace('_', '-')
                .replace(' ', '-');
        while (value.contains("--")) value = value.replace("--", "-");

        String snapshotPrefix = "26.3-snapshot-";
        int snapshotIndex = value.indexOf(snapshotPrefix);
        if (snapshotIndex >= 0) {
            int start = snapshotIndex + snapshotPrefix.length();
            int end = start;
            while (end < value.length() && Character.isDigit(value.charAt(end))) {
                end++;
            }
            if (end > start) {
                try {
                    return Integer.parseInt(value.substring(start, end)) >= 5;
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
            return false;
        }

        return value.startsWith("26.3-pre")
                || value.startsWith("26.3-rc")
                || value.equals("26.3")
                || value.startsWith("26.3.");
    }

    /**
     * Snapshot 5+ moved Mojang's OpenGL shader path onto the ShaderC/SPIR-V
     * pipeline used by RenderPearl. MobileGlues 2.0 receives generated GLSL
     * containing an internal identifier named `_uniform`; its shader translator
     * can mis-tokenize the `uniform` substring and produce invalid GLSL.
     *
     * Keep this workaround strictly on MobileGlues + OpenGL. Krypton and native
     * OpenGL do not show the corruption and System Vulkan never passes through
     * MobileGlues' GL shader translator.
     */
    private static void applySnapshot5PlusMobileGluesShaderCompatibility(
            @NonNull LaunchPlan plan,
            @NonNull RendererInterface renderer,
            @NonNull LinkedHashMap<String, String> env
    ) {
        if (!MobileGluesConfigHelper.isMobileGluesRenderer(renderer)) return;
        if (plan.isUseSystemVulkanDriver()) return;

        String effective = plan.getEffectiveMinecraftVersionId();
        String launch = plan.getVersionId();
        boolean enabled = (effective != null && requiresSnapshot5PlusPersistentMapCompatibility(effective))
                || (launch != null && requiresSnapshot5PlusPersistentMapCompatibility(launch));
        if (!enabled) return;

        env.put("DROIDBRIDGE_MOBILEGLUES_SHADER_IDENTIFIER_FIX", "1");
        String message = "RuntimeBootstrap: Snapshot 5+ MobileGlues shader identifier compatibility enabled"
                + " version=" + plan.getVersionId()
                + " rename=_uniform->_db_uvar";
        Logging.i(TAG, message);
        safeAppendLog(message);
    }

    private static boolean readMinecraftVsyncOption(@NonNull File gameDirectory) {
        File options = new File(gameDirectory, "options.txt");
        if (!options.isFile()) return false;

        try (FileInputStream input = new FileInputStream(options);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }

            String text = output.toString(StandardCharsets.UTF_8.name());
            for (String line : text.split("\\r?\\n")) {
                if (line == null) continue;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                int index = trimmed.indexOf(':');
                if (index <= 0) continue;

                String key = trimmed.substring(0, index).trim();
                if (!"enableVsync".equalsIgnoreCase(key)) continue;

                String value = trimmed.substring(index + 1).trim();
                return "true".equalsIgnoreCase(value)
                        || "1".equals(value)
                        || "yes".equalsIgnoreCase(value)
                        || "on".equalsIgnoreCase(value);
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to read Minecraft VSync option from " + options.getAbsolutePath(), throwable);
            safeAppendLog("RuntimeBootstrap: unable to read MobileGlues VSync option; respecting Minecraft/Sodium swap interval: " + throwable);
        }

        return false;
    }

    private static int resolveDisplayRefreshRateFps(@NonNull Context context) {
        float refreshRate = 0f;
        try {
            Object service = context.getSystemService(Context.DISPLAY_SERVICE);
            if (service instanceof DisplayManager) {
                Display display = ((DisplayManager) service).getDisplay(Display.DEFAULT_DISPLAY);
                if (display != null) {
                    refreshRate = display.getRefreshRate();
                    Display.Mode mode = display.getMode();
                    if (!isSaneRefreshRate(refreshRate) && mode != null) {
                        refreshRate = mode.getRefreshRate();
                    }
                }
            }
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to read display refresh rate: " + throwable);
        }

        int fps = Math.round(refreshRate);
        if (fps < 30 || fps > 360) fps = 60;
        // Normalize common Android fractional modes so 59.94/119.88 do not create noisy caps.
        if (Math.abs(fps - 60) <= 1) return 60;
        if (Math.abs(fps - 90) <= 1) return 90;
        if (Math.abs(fps - 120) <= 2) return 120;
        if (Math.abs(fps - 144) <= 2) return 144;
        if (Math.abs(fps - 165) <= 2) return 165;
        if (Math.abs(fps - 240) <= 3) return 240;
        return fps;
    }

    private static boolean isSaneRefreshRate(float refreshRate) {
        return refreshRate >= 30f && refreshRate <= 360f;
    }

    /**
     * Forces LWJGL to open DroidBridge's Vulkan loader proxy as its authoritative
     * function provider. Merely preloading the proxy is not enough: Minecraft
     * 26.2/LWJGL 3.4 can open the Android Vulkan loader by its own configured
     * library name and bypass the DynamicLinkLoader ndlopen hook entirely.
     *
     * This must be applied after RuntimeBootstrap has resolved the effective
     * per-instance mode, but before the embedded OpenJDK VM is created.
     */
    @NonNull
    public static LaunchPlan applyPreparedVulkanCompatibilityJvmArgs(
            @NonNull LaunchPlan plan
    ) {
        String effectiveMode = resolveEffectiveSystemVulkanCompatibilityMode(plan);

        // Reset Android-10 SDL compatibility switches for every prepared launch.
        // LTW + LWJGL 3.4.1 on API 29 and older enables only the verified
        // SDL 3.2.22 touch-enumeration bypass below; whole-video ART dispatch
        // remains disabled so SDL video ownership stays on Minecraft's thread.
        setEnv("DROIDBRIDGE_SDL3_ART_DISPATCH", "0");
        setEnv("DROIDBRIDGE_SDL3_ANDROID10_SKIP_INIT_TOUCH", "0");

        // Minecraft 26.2's Android System Vulkan path still emits a generic LWJGL
        // native-library failure before the Vulkan backend initializes. Enable
        // LWJGL's own loader diagnostics for this path even when Automatic uses
        // the shield-only fallback, so latestlog.txt names the exact module and
        // path that failed instead of hiding it behind the generic warning.
        ArrayList<String> args = new ArrayList<>(plan.getJvmArgs());
        boolean lwjglVulkanLoaderDiagnostics = plan.isUseSystemVulkanDriver()
                && isMinecraft26_2Or26_3Family(plan.getEffectiveMinecraftVersionId());
        if (lwjglVulkanLoaderDiagnostics) {
            args.removeIf(arg -> arg.startsWith("-Dorg.lwjgl.util.Debug=")
                    || arg.startsWith("-Dorg.lwjgl.util.DebugLoader="));
            args.add(0, "-Dorg.lwjgl.util.Debug=true");
            args.add(1, "-Dorg.lwjgl.util.DebugLoader=true");
            String debugMessage = "RuntimeBootstrap: LWJGL Vulkan native-loader diagnostics enabled";
            Logging.i(TAG, debugMessage);
            safeAppendLog(debugMessage);
        }

        // Minecraft 26.2+ uses the LWJGL 3.4.1 SPIRV-Cross bindings generated
        // against SPVC C API 0.68.0. The launcher previously preloaded the older
        // global app-native libspirv-cross.so (0.65.0), while a matching 0.68.0
        // Android build was already packaged under a different basename. Keep the
        // modern LWJGL component self-contained and point LWJGL at those exact
        // component natives so Java bindings and C ABI cannot drift apart.
        boolean modernLwjgl341Component =
                plan.getLwjglNativeDirectory().getAbsolutePath().contains("lwjgl3.4.1");
        if (modernLwjgl341Component) {
            File componentNatives = plan.getLwjglNativeDirectory();
            File componentSpvc = new File(componentNatives, "libspirv-cross.so");
            File spvc = componentSpvc;
            File componentShaderc = new File(componentNatives, "libshaderc.so");
            File shaderc = componentShaderc;
            File componentVma = new File(componentNatives, "liblwjgl_vma.so");
            File vma = componentVma;

            // The SPIRV-Cross file currently staged in the LWJGL 3.4.1 component
            // is the desktop GNU/Linux build (same bytes as the app's
            // libspirv-cross-c-shared.so), not an Android ELF. Android 10's
            // linker correctly rejects it before SDL/LTW starts. For LTW on
            // API 29 and older, use DroidBridge's Android-21 SPVC build instead.
            // Its exported SPVC C entry-point set matches the component build,
            // and LTW/OpenGL only needs a loadable SPVC provider during the
            // 26.3 NativeLibrariesBootstrap phase.
            boolean android10ModernLwjglNativeCompatibility =
                    Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q;
            if (android10ModernLwjglNativeCompatibility) {
                // Android 10's linker namespace cannot dlopen libraries from the
                // launcher's private files/lwjgl3.4.1 directory when the request
                // originates from the embedded JVM. Route the 26.2+ shader toolchain
                // through DroidBridge's APK-native copies for every renderer, not
                // only LTW. This is the same class of namespace-safe loading MJ uses
                // by preparing native providers before the JVM resolves LWJGL modules.
                File compatibilitySpvc = new File(PathManager.DIR_NATIVE_LIB, "libspirv-cross.so");
                File compatibilityShaderc = new File(PathManager.DIR_NATIVE_LIB, "libshaderc.so");
                File compatibilityVma = new File(PathManager.DIR_NATIVE_LIB, "liblwjgl_vma.so");

                if (compatibilitySpvc.isFile()) spvc = compatibilitySpvc;
                if (compatibilityShaderc.isFile()) shaderc = compatibilityShaderc;
                if (compatibilityVma.isFile()) vma = compatibilityVma;

                args.removeIf(arg -> arg.startsWith("-Dorg.lwjgl.librarypath="));
                args.add("-Dorg.lwjgl.librarypath=" + componentNatives.getAbsolutePath());

                String nativePathMessage = "RuntimeBootstrap: Android 10 LWJGL 3.4 native compatibility"
                        + " api=" + Build.VERSION.SDK_INT
                        + " renderer=" + (sPreparedLtwRenderer ? "ltw" : "other")
                        + " libraryPath=" + componentNatives.getAbsolutePath()
                        + " spvc=" + spvc.getAbsolutePath()
                        + " shaderc=" + shaderc.getAbsolutePath()
                        + " vmaPreload=" + vma.getAbsolutePath();
                Logging.i(TAG, nativePathMessage);
                safeAppendLog(nativePathMessage);

                if (sPreparedLtwRenderer) {
                    // Keep the verified Android-10 SDL 3.2 touch-enumeration bypass
                    // only for LTW/SDL3. Other renderers must not inherit it.
                    setEnv("DROIDBRIDGE_SDL3_ART_DISPATCH", "0");
                    setEnv("DROIDBRIDGE_SDL3_ANDROID10_SKIP_INIT_TOUCH", "1");

                    String artSdlHandle = System.getenv("DROIDBRIDGE_SDL3_ART_HANDLE");
                    String artSdlMessage = "RuntimeBootstrap: Android 10 LTW ART SDL publication"
                            + " available=" + (artSdlHandle != null && !artSdlHandle.isEmpty())
                            + " handle=" + (artSdlHandle == null || artSdlHandle.isEmpty()
                                    ? "<missing>" : artSdlHandle);
                    Logging.i(TAG, artSdlMessage);
                    safeAppendLog(artSdlMessage);
                }
            }

            args.removeIf(arg -> arg.startsWith("-Dorg.lwjgl.spvc.libname=")
                    || arg.startsWith("-Dorg.lwjgl.shaderc.libname=")
                    || arg.startsWith("-Dorg.lwjgl.system.allocator="));

            // The LWJGL 3.4.1 OpenAL component is built with the newer Oboe/NDK
            // toolchain. Some Android 10 vendor linkers (notably the LG G7 /
            // LM-G710) reject that library before Minecraft ever reaches SDL or
            // the selected renderer. DroidBridge already ships an Android-21
            // OpenAL build with the same public AL/ALC entry points, so keep the
            // modern LWJGL Java classes and redirect only the audio provider on
            // API 29 and older.
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                File compatibilityOpenAl = new File(PathManager.DIR_NATIVE_LIB, "libopenal.so");
                args.removeIf(arg -> arg.startsWith("-Dorg.lwjgl.openal.libname="));
                if (compatibilityOpenAl.isFile()) {
                    args.add("-Dorg.lwjgl.openal.libname=" + compatibilityOpenAl.getAbsolutePath());
                    String openAlMessage = "RuntimeBootstrap: Android 10 OpenAL compatibility provider"
                            + " api=" + Build.VERSION.SDK_INT
                            + " path=" + compatibilityOpenAl.getAbsolutePath();
                    Logging.i(TAG, openAlMessage);
                    safeAppendLog(openAlMessage);
                } else {
                    String openAlMessage = "RuntimeBootstrap: Android 10 OpenAL compatibility provider missing"
                            + " api=" + Build.VERSION.SDK_INT
                            + " path=" + compatibilityOpenAl.getAbsolutePath();
                    Logging.e(TAG, openAlMessage);
                    safeAppendLog(openAlMessage);
                }
            }

            // Android has no bundled jemalloc in this component. LWJGL already
            // falls back to StdlibAllocator after a noisy UnsatisfiedLinkError;
            // selecting the same allocator explicitly avoids the failed load and
            // removes one unnecessary native-library ambiguity from Vulkan startup.
            args.add("-Dorg.lwjgl.system.allocator=system");

            if (spvc.isFile()) {
                args.add("-Dorg.lwjgl.spvc.libname=" + spvc.getAbsolutePath());
            }
            if (shaderc.isFile()) {
                args.add("-Dorg.lwjgl.shaderc.libname=" + shaderc.getAbsolutePath());
            }

            String toolchainMessage = "RuntimeBootstrap: LWJGL 3.4 Android shader toolchain"
                    + " spvcExpected=0.68.0"
                    + " spvcComponent=" + componentSpvc.isFile()
                    + " spvcCompatibility=" + (spvc != componentSpvc)
                    + " shadercComponent=" + componentShaderc.isFile()
                    + " shadercCompatibility=" + (shaderc != componentShaderc)
                    + " vmaComponent=" + componentVma.isFile()
                    + " vmaCompatibility=" + (vma != componentVma)
                    + " allocator=system"
                    + " spvcPath=" + spvc.getAbsolutePath()
                    + " shadercPath=" + shaderc.getAbsolutePath()
                    + " vmaPath=" + vma.getAbsolutePath();
            Logging.i(TAG, toolchainMessage);
            safeAppendLog(toolchainMessage);
        }

        /*
         * Modern Minecraft/LWJGL 3.4 eagerly loads org.lwjgl.vulkan during
         * NativeLibrariesBootstrap even when the game renderer is OpenGL. For
         * Kopper that happens before DroidBridge's ndlopen hook exists, so the
         * Android Qualcomm loader can become resident first. Force the very first
         * Vulkan load through DroidBridge's proxy; the proxy bootstraps the
         * Turnip-bound loader when Kopper + System Vulkan OFF is active.
         */
        if (sPreparedKopperZink && !plan.isUseSystemVulkanDriver()) {
            File proxy = new File(PathManager.DIR_NATIVE_LIB, "libdroidbridge_vulkan_proxy.so");
            if (!proxy.isFile()) {
                String message = "RuntimeBootstrap: Kopper early Vulkan proxy is missing: "
                        + proxy.getAbsolutePath();
                Logging.e(TAG, message);
                safeAppendLog(message);
                return (lwjglVulkanLoaderDiagnostics || modernLwjgl341Component)
                        ? plan.copyWithJvmArgs(args)
                        : plan;
            }

            args.removeIf(arg -> arg.startsWith("-Dorg.lwjgl.vulkan.libname="));
            args.add("-Dorg.lwjgl.vulkan.libname=" + proxy.getAbsolutePath());

            String message = "RuntimeBootstrap: Kopper forcing early LWJGL Vulkan proxy"
                    + " systemVulkan=false"
                    + " path=" + proxy.getAbsolutePath();
            Logging.i(TAG, message);
            safeAppendLog(message);
            return plan.copyWithJvmArgs(args);
        }

        if (InstanceLaunchSettings.VULKAN_COMPAT_DISABLED.equals(effectiveMode)
                || InstanceLaunchSettings.VULKAN_COMPAT_SHIELD_MODEL.equals(effectiveMode)) {
            if (InstanceLaunchSettings.VULKAN_COMPAT_SHIELD_MODEL.equals(effectiveMode)) {
                String message = "RuntimeBootstrap: Vulkan shield model fallback selected; native Vulkan proxy disabled";
                Logging.i(TAG, message);
                safeAppendLog(message);
            }
            return (lwjglVulkanLoaderDiagnostics || modernLwjgl341Component)
                    ? plan.copyWithJvmArgs(args)
                    : plan;
        }

        File proxy = new File(PathManager.DIR_NATIVE_LIB, "libdroidbridge_vulkan_proxy.so");
        if (!proxy.isFile()) {
            String message = "RuntimeBootstrap: Vulkan compatibility proxy is missing: "
                    + proxy.getAbsolutePath();
            Logging.e(TAG, message);
            safeAppendLog(message);
            return plan;
        }

        args.removeIf(arg -> arg.startsWith("-Dorg.lwjgl.vulkan.libname="));
        args.add("-Dorg.lwjgl.vulkan.libname=" + proxy.getAbsolutePath());

        String message = "RuntimeBootstrap: forcing LWJGL Vulkan loader proxy"
                + " effective=" + effectiveMode
                + " path=" + proxy.getAbsolutePath();
        Logging.i(TAG, message);
        safeAppendLog(message);
        return plan.copyWithJvmArgs(args);
    }

    @NonNull
    private static String resolveEffectiveSystemVulkanCompatibilityMode(
            @NonNull LaunchPlan plan
    ) {
        String requestedMode = InstanceLaunchSettings.sanitizeVulkanCompatibilityMode(
                plan.getVulkanCompatibilityMode());
        if (!plan.isUseSystemVulkanDriver()
                || InstanceLaunchSettings.VULKAN_COMPAT_DISABLED.equals(requestedMode)) {
            return InstanceLaunchSettings.VULKAN_COMPAT_DISABLED;
        }

        if (InstanceLaunchSettings.VULKAN_COMPAT_AUTO.equals(requestedMode)) {
            boolean autoEligible = isSystemVulkanAutoCompatibilityEligible(plan);
            if (!autoEligible) {
                return InstanceLaunchSettings.VULKAN_COMPAT_DISABLED;
            }

            // V5 is now the production Automatic path for vanilla Minecraft 26.2
            // on Qualcomm/Adreno when System Vulkan is selected. It keeps Mojang's
            // shaders and final atlas objects intact, decodes SpriteMatrix placement
            // from the 140-byte atlas UBO, and repairs the affected static atlases
            // with direct source-image copies. 26.3 remains on the lightweight
            // shield baseline until its atlas path is separately validated.
            if (isMinecraft26_2Family(plan.getEffectiveMinecraftVersionId())) {
                return InstanceLaunchSettings.VULKAN_COMPAT_SPRITE_UPLOAD_LAYOUT_REPAIR;
            }

            /*
             * AYN Thor firmware currently exposes the Adreno 740 system Vulkan 1.3.128
             * driver. Minecraft 26.3 can create its device/swapchain on that driver but
             * the process can die natively as soon as real presentation/resource work
             * begins, before Java can produce a crash report. Use DroidBridge's existing
             * maximum-compatibility proxy only for this exact 26.3 + Thor + System Vulkan
             * automatic path. Newer Qualcomm devices (including the Odin 3/Adreno 830)
             * retain the validated lightweight shield-only 26.3 path.
             */
            if (isMinecraft26_3Family(plan.getEffectiveMinecraftVersionId())
                    && isAynThorBuild()) {
                return InstanceLaunchSettings.VULKAN_COMPAT_MAXIMUM;
            }
            return InstanceLaunchSettings.VULKAN_COMPAT_SHIELD_MODEL;
        }
        if (InstanceLaunchSettings.VULKAN_COMPAT_SHIELD_MODEL.equals(requestedMode)) {
            return isMinecraft26_2Or26_3Family(plan.getEffectiveMinecraftVersionId())
                    ? InstanceLaunchSettings.VULKAN_COMPAT_SHIELD_MODEL
                    : InstanceLaunchSettings.VULKAN_COMPAT_DISABLED;
        }
        // The persisted zero_divisor value used to hide the entire required
        // VK_EXT_vertex_attribute_divisor extension and caused backend creation
        // to fail. Preserve the saved selection, but reinterpret it as a safe
        // read-only graphics-pipeline diagnostic mode.
        if (InstanceLaunchSettings.VULKAN_COMPAT_ZERO_DIVISOR.equals(requestedMode)) {
            return InstanceLaunchSettings.VULKAN_COMPAT_ZERO_DIVISOR;
        }
        if (InstanceLaunchSettings.VULKAN_COMPAT_ENTITY_SHADER_FALLBACK.equals(requestedMode)) {
            return isSystemVulkanAutoCompatibilityEligible(plan)
                    ? InstanceLaunchSettings.VULKAN_COMPAT_ENTITY_SHADER_FALLBACK
                    : InstanceLaunchSettings.VULKAN_COMPAT_DISABLED;
        }
        return requestedMode;
    }

    private static void applySystemVulkanCompatibilityEnvironment(
            @NonNull LaunchPlan plan,
            @NonNull LinkedHashMap<String, String> env
    ) {
        env.put("DROIDBRIDGE_VK_FULL_PUSH_DESCRIPTORS", "0");
        env.put("DROIDBRIDGE_VK_DISABLE_MULTI_DRAW", "0");
        env.put("DROIDBRIDGE_VK_CAP_API_1_3", "0");
        env.put("DROIDBRIDGE_VK_DISABLE_ZERO_DIVISOR", "0");
        env.put("DROIDBRIDGE_VK_IMAGE_DIAGNOSTICS", "0");
        env.put("DROIDBRIDGE_VK_PIPELINE_DIAGNOSTICS", "0");
        env.put("DROIDBRIDGE_VK_ATLAS_SYNC_REPAIR", "0");
        env.put("DROIDBRIDGE_VK_ATLAS_SOURCE_SYNC_REPAIR", "0");
        env.put("DROIDBRIDGE_VK_ATLAS_BUILD_INPUT_AUDIT", "0");
        env.put("DROIDBRIDGE_VK_STATIC_ATLAS_LAYOUT_REPAIR", "0");
        env.put("DROIDBRIDGE_VK_SPRITE_UPLOAD_LAYOUT_REPAIR", "0");
        env.put("DROIDBRIDGE_VK_ASSET_STAGING_REPAIR", "0");
        env.put("DROIDBRIDGE_VK_ATLAS_UBO_FLUSH_REPAIR", "0");
        env.put("DROIDBRIDGE_VK_ATLAS_IMAGE_COPY_TEST", "0");
        env.put("DROIDBRIDGE_VK_SPECIAL_DRAW_SYNC_REPAIR", "0");
        env.put("DROIDBRIDGE_VK_ENTITY_NORMAL_FORMAT_REPAIR", "0");
        env.put("DROIDBRIDGE_VK_ENTITY_SHADER_FALLBACK", "0");

        String requestedMode = InstanceLaunchSettings.sanitizeVulkanCompatibilityMode(
                plan.getVulkanCompatibilityMode());
        if (!plan.isUseSystemVulkanDriver()
                || InstanceLaunchSettings.VULKAN_COMPAT_DISABLED.equals(requestedMode)) {
            String message = "RuntimeBootstrap: System Vulkan compatibility disabled"
                    + " requested=" + requestedMode
                    + " systemVulkan=" + plan.isUseSystemVulkanDriver();
            Logging.i(TAG, message);
            safeAppendLog(message);
            return;
        }

        // Automatic is restored to the known-good shield-only baseline on affected
        // Qualcomm/Adreno System Vulkan launches. Passive observation and every
        // native command-stream modification are explicit modes, which keeps
        // regressions attributable to one compatibility mechanism at a time.
        String effectiveMode = resolveEffectiveSystemVulkanCompatibilityMode(plan);

        boolean shieldModelFallback = shouldApplySystemVulkanShieldModelFallback(plan);
        boolean pushDescriptors =
                InstanceLaunchSettings.VULKAN_COMPAT_PUSH_DESCRIPTORS.equals(effectiveMode)
                        || InstanceLaunchSettings.VULKAN_COMPAT_PUSH_AND_MULTI_DRAW.equals(effectiveMode)
                        || InstanceLaunchSettings.VULKAN_COMPAT_MAXIMUM.equals(effectiveMode);
        boolean disableMultiDraw =
                InstanceLaunchSettings.VULKAN_COMPAT_PUSH_AND_MULTI_DRAW.equals(effectiveMode)
                        || InstanceLaunchSettings.VULKAN_COMPAT_MAXIMUM.equals(effectiveMode);
        boolean capApi13 = InstanceLaunchSettings.VULKAN_COMPAT_MAXIMUM.equals(effectiveMode);
        // Minecraft 26.2 treats VK_EXT_vertex_attribute_divisor as a required
        // device extension. Do not hide the extension: the previous isolation
        // test proved that doing so prevents Vulkan backend creation entirely.
        // Keep the old feature-bit workaround only inside Maximum, where it is
        // still harmless if Minecraft does not query that feature structure.
        boolean disableZeroDivisor =
                InstanceLaunchSettings.VULKAN_COMPAT_MAXIMUM.equals(effectiveMode);
        boolean atlasSyncRepair =
                InstanceLaunchSettings.VULKAN_COMPAT_ATLAS_SYNC.equals(effectiveMode);
        boolean atlasSourceSyncRepair =
                InstanceLaunchSettings.VULKAN_COMPAT_ATLAS_SOURCE_SYNC.equals(effectiveMode);
        boolean atlasSpriteMatrixDirectRepair =
                InstanceLaunchSettings.VULKAN_COMPAT_SPRITE_UPLOAD_LAYOUT_REPAIR.equals(effectiveMode);
        boolean atlasBuildInputAudit =
                InstanceLaunchSettings.VULKAN_COMPAT_ATLAS_BUILD_INPUT_AUDIT.equals(effectiveMode)
                        || InstanceLaunchSettings.VULKAN_COMPAT_STATIC_ATLAS_LAYOUT_REPAIR.equals(effectiveMode);
        boolean staticAtlasLayoutRepair =
                InstanceLaunchSettings.VULKAN_COMPAT_STATIC_ATLAS_LAYOUT_REPAIR.equals(effectiveMode);
        // Stable persisted mode ID retained. V5 is the real repair path derived from
        // the V4 vertex/UBO capture: keep Mojang's normal atlas shaders intact, decode
        // SpriteMatrix placement from the 140-byte UBO, and directly copy only the small
        // static painting/banner/shield source images into their original atlas targets.
        boolean spriteUploadLayoutRepair = false;
        boolean assetStagingRepair = false;
        boolean atlasUboFlushRepair = false;
        boolean atlasImageCopyTest = false;
        boolean atlasDirectComposeRepair = atlasSpriteMatrixDirectRepair;
        boolean atlasFragmentRepair = false;
        boolean specialDrawSyncRepair =
                InstanceLaunchSettings.VULKAN_COMPAT_SPECIAL_DRAW_SYNC.equals(effectiveMode);
        // The former entity-normal slot is now a read-only entity-shader
        // descriptor diagnostic. Pipeline logs proved Minecraft already uses
        // the padded R8G8B8A8_SNORM normal format on the affected device.
        boolean entityShaderFallback =
                InstanceLaunchSettings.VULKAN_COMPAT_ENTITY_SHADER_FALLBACK.equals(effectiveMode);
        boolean entityAuxDiagnostics =
                InstanceLaunchSettings.VULKAN_COMPAT_ENTITY_AUX_DIAGNOSTICS.equals(effectiveMode)
                        || entityShaderFallback;
        boolean entityNormalFormatRepair = false;
        boolean pipelineDiagnostics =
                InstanceLaunchSettings.VULKAN_COMPAT_DIAGNOSTICS.equals(effectiveMode)
                        || InstanceLaunchSettings.VULKAN_COMPAT_ZERO_DIVISOR.equals(effectiveMode)
                        || atlasSyncRepair || atlasSourceSyncRepair || atlasBuildInputAudit
                        || specialDrawSyncRepair || entityAuxDiagnostics;
        boolean imageDiagnostics =
                InstanceLaunchSettings.VULKAN_COMPAT_DIAGNOSTICS.equals(effectiveMode)
                        || atlasSyncRepair || atlasSourceSyncRepair || atlasBuildInputAudit
                        || spriteUploadLayoutRepair || assetStagingRepair || atlasUboFlushRepair || atlasImageCopyTest
                        || atlasDirectComposeRepair || atlasFragmentRepair
                        || specialDrawSyncRepair || entityAuxDiagnostics || pushDescriptors || disableMultiDraw || capApi13;

        env.put("DROIDBRIDGE_VK_FULL_PUSH_DESCRIPTORS", pushDescriptors ? "1" : "0");
        env.put("DROIDBRIDGE_VK_DISABLE_MULTI_DRAW", disableMultiDraw ? "1" : "0");
        env.put("DROIDBRIDGE_VK_CAP_API_1_3", capApi13 ? "1" : "0");
        env.put("DROIDBRIDGE_VK_DISABLE_ZERO_DIVISOR", disableZeroDivisor ? "1" : "0");
        env.put("DROIDBRIDGE_VK_IMAGE_DIAGNOSTICS", imageDiagnostics ? "1" : "0");
        env.put("DROIDBRIDGE_VK_PIPELINE_DIAGNOSTICS", pipelineDiagnostics ? "1" : "0");
        env.put("DROIDBRIDGE_VK_ATLAS_SYNC_REPAIR", atlasSyncRepair ? "1" : "0");
        env.put("DROIDBRIDGE_VK_ATLAS_SOURCE_SYNC_REPAIR", atlasSourceSyncRepair ? "1" : "0");
        env.put("DROIDBRIDGE_VK_ATLAS_BUILD_INPUT_AUDIT", atlasBuildInputAudit ? "1" : "0");
        env.put("DROIDBRIDGE_VK_STATIC_ATLAS_LAYOUT_REPAIR", staticAtlasLayoutRepair ? "1" : "0");
        env.put("DROIDBRIDGE_VK_SPRITE_UPLOAD_LAYOUT_REPAIR", spriteUploadLayoutRepair ? "1" : "0");
        env.put("DROIDBRIDGE_VK_ASSET_STAGING_REPAIR", assetStagingRepair ? "1" : "0");
        env.put("DROIDBRIDGE_VK_ATLAS_UBO_FLUSH_REPAIR", atlasUboFlushRepair ? "1" : "0");
        env.put("DROIDBRIDGE_VK_ATLAS_IMAGE_COPY_TEST", atlasImageCopyTest ? "1" : "0");
        env.put("DROIDBRIDGE_VK_ATLAS_DIRECT_COMPOSE_REPAIR", atlasDirectComposeRepair ? "1" : "0");
        env.put("DROIDBRIDGE_VK_ATLAS_FRAGMENT_REPAIR", atlasFragmentRepair ? "1" : "0");
        env.put("DROIDBRIDGE_VK_SPECIAL_DRAW_SYNC_REPAIR", specialDrawSyncRepair ? "1" : "0");
        env.put("DROIDBRIDGE_VK_ENTITY_NORMAL_FORMAT_REPAIR", "0");
        env.put("DROIDBRIDGE_VK_ENTITY_SHADER_FALLBACK", entityShaderFallback ? "1" : "0");
        env.put("DROIDBRIDGE_VK_ENTITY_AUX_DIAGNOSTICS", entityAuxDiagnostics ? "1" : "0");

        String message = "RuntimeBootstrap: System Vulkan compatibility"
                + " requested=" + requestedMode
                + " effective=" + effectiveMode
                + " shieldModelFallback=" + shieldModelFallback
                + " pushDescriptors=" + pushDescriptors
                + " disableMultiDraw=" + disableMultiDraw
                + " capApi13=" + capApi13
                + " disableZeroDivisor=" + disableZeroDivisor
                + " imageDiagnostics=" + imageDiagnostics
                + " pipelineDiagnostics=" + pipelineDiagnostics
                + " atlasSyncRepair=" + atlasSyncRepair
                + " atlasSourceSyncRepair=" + atlasSourceSyncRepair
                + " atlasBuildInputAudit=" + atlasBuildInputAudit
                + " staticAtlasLayoutRepair=" + staticAtlasLayoutRepair
                + " spriteUploadLayoutRepair=" + spriteUploadLayoutRepair
                + " assetStagingRepair=" + assetStagingRepair
                + " atlasUboFlushRepair=" + atlasUboFlushRepair
                + " atlasImageCopyTest=" + atlasImageCopyTest
                + " atlasDirectComposeRepair=" + atlasDirectComposeRepair
                + " atlasFragmentRepair=" + atlasFragmentRepair
                + " specialDrawSyncRepair=" + specialDrawSyncRepair
                + " entityNormalFormatRepair=" + entityNormalFormatRepair
                + " entityShaderFallback=" + entityShaderFallback
                + " entityFragmentSolidIsolation=" + entityShaderFallback
                + " entityAuxDiagnostics=" + entityAuxDiagnostics
                + " qualcomm=" + isLikelyQualcommDevice()
                + " minecraft=" + plan.getEffectiveMinecraftVersionId();
        Logging.i(TAG, message);
        safeAppendLog(message);
    }

    public static boolean shouldApplySystemVulkanShieldModelFallback(
            @NonNull LaunchPlan plan
    ) {
        if (!plan.isUseSystemVulkanDriver()) return false;
        String requestedMode = InstanceLaunchSettings.sanitizeVulkanCompatibilityMode(
                plan.getVulkanCompatibilityMode());
        if (InstanceLaunchSettings.VULKAN_COMPAT_SHIELD_MODEL.equals(requestedMode)) {
            return isMinecraft26_2Or26_3Family(plan.getEffectiveMinecraftVersionId());
        }
        // Passive diagnostics must be a true vanilla-resource baseline. Leaving
        // the shield fallback enabled changes the item atlas and special-item
        // model path, which can contaminate the exact banner/painting test this
        // mode is intended to observe. The diagnostics proxy itself is read-only.
        if (InstanceLaunchSettings.VULKAN_COMPAT_DIAGNOSTICS.equals(requestedMode)) {
            return false;
        }
        if (InstanceLaunchSettings.VULKAN_COMPAT_ATLAS_SYNC.equals(requestedMode)
                || InstanceLaunchSettings.VULKAN_COMPAT_ATLAS_SOURCE_SYNC.equals(requestedMode)
                || InstanceLaunchSettings.VULKAN_COMPAT_ATLAS_BUILD_INPUT_AUDIT.equals(requestedMode)
                || InstanceLaunchSettings.VULKAN_COMPAT_STATIC_ATLAS_LAYOUT_REPAIR.equals(requestedMode)
                || InstanceLaunchSettings.VULKAN_COMPAT_SPRITE_UPLOAD_LAYOUT_REPAIR.equals(requestedMode)
                || InstanceLaunchSettings.VULKAN_COMPAT_SPECIAL_DRAW_SYNC.equals(requestedMode)
                || InstanceLaunchSettings.VULKAN_COMPAT_ENTITY_AUX_DIAGNOSTICS.equals(requestedMode)
                || InstanceLaunchSettings.VULKAN_COMPAT_ENTITY_SHADER_FALLBACK.equals(requestedMode)) {
            return isSystemVulkanAutoCompatibilityEligible(plan);
        }
        return InstanceLaunchSettings.VULKAN_COMPAT_AUTO.equals(requestedMode)
                && isSystemVulkanAutoCompatibilityEligible(plan);
    }

    private static boolean isSystemVulkanAutoCompatibilityEligible(
            @NonNull LaunchPlan plan
    ) {
        return plan.isUseSystemVulkanDriver()
                && isLikelyQualcommDevice()
                && isMinecraft26_2Or26_3Family(plan.getEffectiveMinecraftVersionId());
    }

    private static boolean isLikelyQualcommDevice() {
        StringBuilder identity = new StringBuilder();
        appendDeviceIdentity(identity, Build.HARDWARE);
        appendDeviceIdentity(identity, Build.BOARD);
        appendDeviceIdentity(identity, Build.DEVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appendDeviceIdentity(identity, Build.SOC_MANUFACTURER);
            appendDeviceIdentity(identity, Build.SOC_MODEL);
        }
        String value = identity.toString().toLowerCase(Locale.ROOT);
        return value.contains("qualcomm")
                || value.contains("qti")
                || value.contains("qcom")
                || value.contains("snapdragon")
                || value.contains("sm8")
                || value.contains("pineapple")
                || value.contains("kalama")
                || value.contains("taro")
                || value.contains("waipio");
    }

    private static void appendDeviceIdentity(
            @NonNull StringBuilder out,
            @Nullable String value
    ) {
        if (value == null || value.trim().isEmpty()) return;
        if (out.length() > 0) out.append(' ');
        out.append(value.trim());
    }

    private static boolean isMinecraft26_2Family(@Nullable String versionId) {
        if (versionId == null) return false;
        Matcher matcher = MINECRAFT_RELEASE_PATTERN.matcher(
                versionId.trim().toLowerCase(Locale.ROOT));
        int major = -1;
        int minor = -1;
        while (matcher.find()) {
            try {
                major = Integer.parseInt(matcher.group(1));
                minor = Integer.parseInt(matcher.group(2));
            } catch (Throwable ignored) {
                // Keep the last successfully parsed Minecraft version token.
            }
        }
        return major == 26 && minor == 2;
    }

    private static boolean isMinecraft26_3Family(@Nullable String versionId) {
        if (versionId == null) return false;
        Matcher matcher = MINECRAFT_RELEASE_PATTERN.matcher(
                versionId.trim().toLowerCase(Locale.ROOT));
        int major = -1;
        int minor = -1;
        while (matcher.find()) {
            try {
                major = Integer.parseInt(matcher.group(1));
                minor = Integer.parseInt(matcher.group(2));
            } catch (Throwable ignored) {
                // Keep the last successfully parsed Minecraft version token.
            }
        }
        return major == 26 && minor == 3;
    }

    private static boolean isAynThorBuild() {
        StringBuilder identity = new StringBuilder();
        appendDeviceIdentity(identity, Build.MANUFACTURER);
        appendDeviceIdentity(identity, Build.BRAND);
        appendDeviceIdentity(identity, Build.MODEL);
        appendDeviceIdentity(identity, Build.PRODUCT);
        appendDeviceIdentity(identity, Build.DEVICE);
        String value = identity.toString().toLowerCase(Locale.ROOT);
        return value.contains("ayn") && value.contains("thor");
    }

    private static boolean isMinecraft26_2Or26_3Family(@Nullable String versionId) {
        if (versionId == null) return false;
        Matcher matcher = MINECRAFT_RELEASE_PATTERN.matcher(
                versionId.trim().toLowerCase(Locale.ROOT));
        int major = -1;
        int minor = -1;
        while (matcher.find()) {
            try {
                major = Integer.parseInt(matcher.group(1));
                minor = Integer.parseInt(matcher.group(2));
            } catch (Throwable ignored) {
                // Keep the last successfully parsed Minecraft version token.
            }
        }
        return major == 26 && (minor == 2 || minor == 3);
    }

    private static void applySystemVulkanEnvironment(@NonNull LinkedHashMap<String, String> env) {
        env.put("DROIDBRIDGE_USE_SYSTEM_VULKAN", "1");
        env.put("JAVA_LAUNCHER_USE_SYSTEM_VULKAN_DRIVER", "1");
        env.put("JAVA_LAUNCHER_VULKAN_DRIVER", "system");
        env.put("DROIDBRIDGE_LOAD_TURNIP", "");
        env.put("DROIDBRIDGE_LOAD_TURNIP", "");
        env.put("DROIDBRIDGE_USE_CUSTOM_TURNIP", "");
        env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", "");
        env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", "");
        env.put("VK_ICD_FILENAMES", "");
        env.put("VK_DRIVER_FILES", "");
        env.put("VK_INSTANCE_LAYERS", "");
        env.put("DRIVER_PATH", "");
    }

    private static boolean shouldPreloadLinuxDesktopShims(@Nullable RendererInterface renderer) {
        if (renderer == null) return true;

        /*
         * Do not preload the Linux desktop/WebRTC compatibility shims for
         * DroidBridge Mesa renderers. Those shims include libdrm/libgbm/X11-style
         * libraries and can shadow the Mesa/Turnip-matched copies that libEGL_mesa.so
         * must resolve inside the app-first namespace.
         */
        if (DroidBridgeMesaSupport.isDroidBridgeMesaRenderer(renderer)) return false;
        if (DroidBridgeMesaSupport.isVulkanZinkOrLegacyAlias(renderer)) return false;
        if (DriverPluginManager.isVulkanZinkRenderer(renderer)) return false;
        return true;
    }

    private static void applyMobileGluesConfigEnvironment(
            @NonNull Context context,
            @NonNull RendererInterface renderer,
            @NonNull LinkedHashMap<String, String> env
    ) {
        if (!MobileGluesConfigHelper.isMobileGluesRenderer(renderer)) return;

        try {
            File launchConfigDir = MobileGluesConfigHelper.prepareLaunchConfig(context);
            File launchConfigFile = MobileGluesConfigHelper.getLaunchConfigFile(context);

            /*
             * MobileGlues reads config.json from getenv("MG_DIR_PATH") while its native
             * library is being loaded. The settings screen can read a picked SAF folder,
             * but native code cannot read a content:// tree URI. Always override any
             * plugin-provided /sdcard/MG default with the launch-readable mirror before
             * preloadRuntimeAndGraphics() dlopens libmobileglues.so.
             */
            env.put("MG_DIR_PATH", launchConfigDir.getAbsolutePath());
            env.put("MOBILEGLUES_CONFIG_DIR", launchConfigDir.getAbsolutePath());

            Logging.i(TAG, "MobileGlues config env prepared before renderer preload: MG_DIR_PATH="
                    + launchConfigDir.getAbsolutePath()
                    + " configExists="
                    + launchConfigFile.isFile()
                    + " configSize="
                    + (launchConfigFile.isFile() ? launchConfigFile.length() : 0));
        } catch (Throwable throwable) {
            File fallbackDir = MobileGluesConfigHelper.getConfigDirectory();
            env.put("MG_DIR_PATH", fallbackDir.getAbsolutePath());
            Logging.e(TAG, "Unable to prepare MobileGlues config before renderer preload; using fallback MG_DIR_PATH="
                    + fallbackDir.getAbsolutePath(), throwable);
        }
    }

    private static void applyLiteGlesEnvironmentOverrides(
            @NonNull LinkedHashMap<String, String> env,
            @Nullable LiteGlesLaunchPreloader.Result liteGles
    ) {
        if (liteGles == null) return;

        String stagedDir = liteGles.stagedNativeDir.getAbsolutePath();
        String pluginDir = liteGles.pluginNativeDir.getAbsolutePath();
        String osmesa = liteGles.stagedOSMesaLibrary.getAbsolutePath();
        String main = liteGles.stagedMainLibrary.getAbsolutePath();

        env.put("DB_LITEGLES_PLUGIN_NATIVE_DIR", pluginDir);
        env.put("DB_LITEGLES_STAGED_NATIVE_DIR", stagedDir);
        env.put("DB_LITEGLES_OSMESA_PATH", osmesa);

        env.put("DROIDBRIDGE_OSMESA_LIBRARY", osmesa);
        env.put("OSMESA_LIBRARY", osmesa);
        env.put("OSMESA_LIB", osmesa);
        env.put("LIBGL_OSMESA", osmesa);
        env.put("LIB_MESA_NAME", osmesa);

        env.put("DROIDBRIDGE_RENDERER", "opengles3");
        env.put("DROIDBRIDGE_RENDERER_LIBRARY", main);
        env.put("DROIDBRIDGE_RENDERER_LIBRARY", main);

        env.put("DROIDBRIDGE_EGL", "libEGL.so");
        env.put("DROIDBRIDGE_EGL_LIBRARY", "libEGL.so");
        env.put("DROIDBRIDGE_EGL_LIBRARY", "libEGL.so");

        env.put("LIBGL_ES", "2");
        env.put("LIBGL_GL", "21");
        env.put("LIBGL_FBO", "1");

        Logging.i(TAG, "LiteGLES env stagedDir=" + stagedDir);
        Logging.i(TAG, "LiteGLES env OSMesa=" + osmesa);
    }


    private static void applyRendererBridgeAliases(
            @NonNull LinkedHashMap<String, String> env,
            @NonNull RendererInterface renderer,
            @NonNull String eglName
    ) {
        String rendererLibrary = sanitizeLibraryName(renderer.getRendererLibrary());
        String combined = (renderer.getRendererId() + " " + renderer.getRendererName() + " " + rendererLibrary)
                .toLowerCase(Locale.ROOT);

        if (isLtwRenderer(renderer)) {
            env.put("DROIDBRIDGE_RENDERER", "opengles3_ltw");
            env.put("POJAV_RENDERER", "opengles3_ltw");
            env.put("DROIDBRIDGE_EGL", "libltw.so");
            env.put("DROIDBRIDGE_EGL_LIBRARY", "libltw.so");
            env.put("DROIDBRIDGE_RENDERER_LIBRARY", "libltw.so");
            env.put("POJAV_RENDERER_LIBRARY", "libltw.so");
            env.put("POJAVEXEC_EGL", "libltw.so");
            env.put("POJAVEXEC_EGL_LIBRARY", "libltw.so");
            // LIBGL_ES/LIBGL_NOERROR and LTW's storage/flush profile are set in
            // the version-aware block above. Do not overwrite them here.
            env.put("DROIDBRIDGE_USE_SYSTEM_VULKAN", "1");
            env.put("DRIVER_PATH", "");
            env.put("VK_ICD_FILENAMES", "");
            env.put("VK_DRIVER_FILES", "");
            env.put("LIBGL_DRIVERS_PATH", "");
            env.put("EGL_DRIVERS_PATH", "");
            env.put("OSMESA_LIB", "");
            env.put("GALLIUM_DRIVER", "");
            env.put("MESA_LOADER_DRIVER_OVERRIDE", "");
            return;
        }

        if (KopperZinkRenderer.isRenderer(renderer)) {
            env.put("DROIDBRIDGE_RENDERER", KopperZinkRenderer.RENDERER_ID);
            env.put("POJAV_RENDERER", KopperZinkRenderer.RENDERER_ID);
            env.put("DROIDBRIDGE_RENDERER_LIBRARY", KopperZinkRenderer.MAIN_LIBRARY);
            env.put("POJAV_RENDERER_LIBRARY", KopperZinkRenderer.MAIN_LIBRARY);
            env.put("DROIDBRIDGE_EGL", KopperZinkRenderer.EGL_LIBRARY);
            env.put("DROIDBRIDGE_EGL_LIBRARY", KopperZinkRenderer.EGL_LIBRARY);
            env.put("POJAVEXEC_EGL", KopperZinkRenderer.EGL_LIBRARY);
            env.put("POJAVEXEC_EGL_LIBRARY", KopperZinkRenderer.EGL_LIBRARY);
            env.put("LIBGL_ES", "3");
            env.put("GALLIUM_DRIVER", "zink");
            env.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
            env.put("force_gl_vendor", "Mesa/DroidBridge");
            env.put("MESA_ANDROID_NO_KMS_SWRAST", "1");
            env.put("MESA_NO_ERROR", "0");
            env.put("LIBGL_NOERROR", "0");
            env.put("ZINK_DESCRIPTORS", "lazy");
            env.put("ZINK_DEBUG", "compact,noreorder");
            env.put("mesa_glthread", "false");
            // Kopper is desktop OpenGL-over-Vulkan.  Do not let the generic
            // opengles* renderer prefix select an EGL/GLES2 context.
            env.put("DROIDBRIDGE_EGL_FORCE_DESKTOP_GL", "1");
            env.put("DROIDBRIDGE_MESA_DESKTOP_GL", "1");
            env.put("DROIDBRIDGE_EGL_FORCE_RGBX8888", "1");
            // Preserve the Kopper-specific surface owner across the process bootstrap.
            env.put("DROIDBRIDGE_KOPPER_FORCE_TEXTUREVIEW", "1");
            env.put("DROIDBRIDGE_KOPPER_FORCE_NAMESPACE_VULKAN", "1");
            env.put("POJAV_ZINK_PREFER_SYSTEM_DRIVER", "");
            env.put("LIB_MESA_NAME", "");
            env.put("OSMESA_LIB", "");
            env.put("DROIDBRIDGE_OSMESA_LIBRARY", "");
            env.put("OSMESA_LIBRARY", "");
            env.put("LIBGL_OSMESA", "");
            return;
        }

        if (isMobileGluesRenderer(renderer)) {
            // Keep libdroidbridge_runtime on the generic GLES bridge id. MobileGlues is the
            // EGL provider, not a separate native bridge renderer id. This avoids
            // VulkanMod entering the wrong OpenGL init path when System Vulkan is on.
            String bridgeRendererId = renderer.getRendererId();
            if (bridgeRendererId == null || bridgeRendererId.trim().isEmpty()
                    || bridgeRendererId.toLowerCase(Locale.ROOT).contains("mobileglues")) {
                bridgeRendererId = "opengles3";
            }
            env.put("DROIDBRIDGE_RENDERER", bridgeRendererId);
            env.put("DROIDBRIDGE_RENDERER_LIBRARY", "libmobileglues.so");
            env.put("DROIDBRIDGE_RENDERER_LIBRARY", "libmobileglues.so");
            env.put("DROIDBRIDGE_EGL", "libmobileglues.so");
            env.put("DROIDBRIDGE_EGL_LIBRARY", "libmobileglues.so");
            env.put("DROIDBRIDGE_EGL_LIBRARY", "libmobileglues.so");

            // Keep the 0.3.19 MobileGlues/LWJGL bridge aliases too.  The known-good
            // Sodium 26.2 path logged POJAV_RENDERER/POJAVEXEC_EGL, and the old
            // LWJGL 3.4.1 component still looks for these names in a few places.
            env.put("POJAV_RENDERER", bridgeRendererId);
            env.put("POJAV_RENDERER_LIBRARY", "libmobileglues.so");
            env.put("POJAVEXEC_EGL", "libmobileglues.so");
            env.put("POJAVEXEC_EGL_LIBRARY", "libmobileglues.so");

            clearMesaAndVulkanDriverEnvironment(env);
            return;
        }

        if (!rendererLibrary.isEmpty()) {
            // Different droidbridge_runtime/libOSMesa builds have used slightly different
            // environment names. Setting aliases is harmless for renderers that
            // ignore them and prevents the native side from seeing a null library.
            env.put("DROIDBRIDGE_RENDERER_LIBRARY", rendererLibrary);
            env.put("DROIDBRIDGE_RENDERER_LIBRARY", rendererLibrary);
            env.put("OSMESA_LIB", rendererLibrary);
        }

        if (!eglName.isEmpty()) {
            env.put("DROIDBRIDGE_EGL_LIBRARY", eglName);
            env.put("DROIDBRIDGE_EGL_LIBRARY", eglName);
        }

        if (combined.contains("zink") || combined.contains("osmesa")) {
            env.put("DROIDBRIDGE_RENDERER", "vulkan_zink");
            env.put("DROIDBRIDGE_EGL", rendererLibrary.isEmpty() ? "libOSMesa_8.so" : rendererLibrary);
            env.put("LIBGL_ES", "3");
            env.put("LIB_MESA_NAME", rendererLibrary.isEmpty() ? "libOSMesa_8.so" : rendererLibrary);
            env.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
            env.put("GALLIUM_DRIVER", "zink");
        }
    }

    @NonNull
    private static String resolveJvmArgValue(
            @NonNull LaunchPlan plan,
            @NonNull String prefix,
            @NonNull String fallback
    ) {
        for (String arg : plan.getJvmArgs()) {
            if (arg != null && arg.startsWith(prefix)) {
                String value = arg.substring(prefix.length()).trim();
                if (!value.isEmpty()) return value;
            }
        }
        return fallback;
    }

    /**
     * Clear renderer-native environment on every launch before applying the selected renderer.
     * Android keeps these variables in the launcher process, so a failed Freedreno launch can
     * poison the next Vulkan Zink launch with DROIDBRIDGE_MESA_MODE=freedreno_kgsl unless we
     * actively unset the old values. This also makes testing rebuilt libdroidbridge_runtime deterministic.
     */
    private static void seedRendererEnvironmentReset(@NonNull LinkedHashMap<String, String> env) {
        String[] keys = new String[]{
                "DROIDBRIDGE_RENDERER",
                "POJAV_RENDERER",
                "POJAV_RENDERER_LIBRARY",
                "POJAVEXEC_EGL",
                "POJAVEXEC_EGL_LIBRARY",
                "DROIDBRIDGE_LEGACY_LWJGL2",
                "DROIDBRIDGE_MOBILEGLUES_RENDERER",
                "DROIDBRIDGE_MOBILEGLUES_SHADER_IDENTIFIER_FIX",
                "DROIDBRIDGE_EGL",
                "DROIDBRIDGE_EGL_LIBRARY",
                "DROIDBRIDGE_EGL_LIBRARY",
                "DROIDBRIDGE_RENDERER_LIBRARY",
                "DROIDBRIDGE_RENDERER_LIBRARY",
                "DROIDBRIDGE_OSMESA_LIBRARY",
                "OSMESA_LIBRARY",
                "OSMESA_LIB",
                "LIBGL_OSMESA",
                "LIB_MESA_NAME",
                "LIBGL_ES",
                "LIBGL_GL",
                "LIBGL_FBO",
                "LIBGL_MIPMAP",
                "LIBGL_NOINTOVLHACK",
                "LIBGL_NORMALIZE",
                "LIBGL_NOERROR",
                "DROIDBRIDGE_MESA",
                "DROIDBRIDGE_MESA_MODE",
                "DROIDBRIDGE_MESA_DRIVER",
                "DROIDBRIDGE_MESA_NATIVE_DIR",
                "DROIDBRIDGE_MESA_ALIAS_DIR",
                "DROIDBRIDGE_KOPPER_VULKAN_ALIAS_DIR",
                "DROIDBRIDGE_MESA_NAMESPACE",
                "DROIDBRIDGE_MESA_NAMESPACE_PATH",
                "DROIDBRIDGE_MESA_EGL",
                "DROIDBRIDGE_MESA_GL",
                "DROIDBRIDGE_MESA_EGL_SINGLE_INSTANCE",
                "DROIDBRIDGE_MESA_EGL_PLATFORM_DISPLAY",
                "DROIDBRIDGE_MESA_EGL_SHIM",
                "DROIDBRIDGE_MESA_DESKTOP_GL",
                "DROIDBRIDGE_MESA_SAFE_SWAPS",
                "DROIDBRIDGE_ZINK_V59_CLEAN_ALIAS",
                "DROIDBRIDGE_LEGACY_FREEDRENO_ALIAS_V59",
                "DROIDBRIDGE_EGL_FORCE_DESKTOP_GL",
                "DROIDBRIDGE_EGL_FORCE_RGBX8888",
                "DROIDBRIDGE_GL_LEGACY_COMPAT_PROFILE",
                "DROIDBRIDGE_LEGACY_OPTIFINE_SHADER_COMPAT",
                "DROIDBRIDGE_MESA_LEGACY_COMPAT_PROFILE",
                "DROIDBRIDGE_EGL_FORCE_COMPAT_PROFILE",
                "DROIDBRIDGE_EGL_FORCE_CORE_PROFILE",
                "DROIDBRIDGE_DISABLE_LEGACY_OCCLUSION_QUERY",
                "DROIDBRIDGE_LEGACY_OCCLUSION_QUERY_STUB",
                "DROIDBRIDGE_EGL_NO_SYSTEM_FALLBACK",
                "DROIDBRIDGE_EGL_RECOVER_CURRENT",
                "DROIDBRIDGE_NATIVE_GLFW",
                "DROIDBRIDGE_NATIVE_GLFW_KGSL",
                "DROIDBRIDGE_NATIVE_GLFW_DESKTOP_GL",
                "DROIDBRIDGE_NATIVE_GLFW_LIB",
                "DROIDBRIDGE_NATIVE_GLFW_EGL",
                "DROIDBRIDGE_NATIVE_GLFW_DRIVER",
                "DROIDBRIDGE_DIRECT_FREEDRENO_NATIVE_SURFACE_V69",
                "DROIDBRIDGE_DIRECT_FREEDRENO_TEXTUREVIEW_V70",
                "DROIDBRIDGE_RENDERER_MESA_MODE",
                "MESA_PROCESS_NAME",
                "MESA_DRICONF_EXECUTABLE_OVERRIDE",
                "force_gl_vendor",
                "MESA_LOADER_DRIVER_OVERRIDE",
                "GALLIUM_DRIVER",
                "MESA_GL_VERSION_OVERRIDE",
                "MESA_GLSL_VERSION_OVERRIDE",
                "MESA_EXTENSION_OVERRIDE",
                "MESA_GLSL_CACHE_DIR",
                "MESA_SHADER_CACHE_DIR",
                "LIBGL_DRIVERS_PATH",
                "EGL_DRIVERS_PATH",
                "DRIVER_PATH",
                "VK_ICD_FILENAMES",
                "VK_DRIVER_FILES",
                "VK_INSTANCE_LAYERS",
                "VULKAN_PTR",
                "DROIDBRIDGE_LOAD_TURNIP",
                "DROIDBRIDGE_LOAD_TURNIP",
                "DROIDBRIDGE_USE_CUSTOM_TURNIP",
                "DROIDBRIDGE_CUSTOM_VULKAN_DRIVER",
                "DROIDBRIDGE_CUSTOM_VULKAN_DRIVER",
                "DROIDBRIDGE_USE_SYSTEM_VULKAN",
                "JAVA_LAUNCHER_USE_SYSTEM_VULKAN_DRIVER",
                "JAVA_LAUNCHER_VULKAN_DRIVER",
                "TU_DEBUG",
                "ZINK_DEBUG",
                "ZINK_DESCRIPTORS",
                "EGL_PLATFORM",
                "MESA_ANDROID_NO_KMS_SWRAST",
                "DROIDBRIDGE_MOBILEGLUES_FORCE_VSYNC",
                "allow_higher_compat_version",
                "force_glsl_extensions_warn",
                "allow_glsl_extension_directive_midshader",
                "mesa_glthread",
                "MESA_DEBUG",
                "MESA_NO_ERROR",
                "MESA_VK_WSI_PRESENT_MODE",
                "DROIDBRIDGE_VSYNC_IN_ZINK",
                "DROIDBRIDGE_VULKAN_FORCE_FIFO",
                "DROIDBRIDGE_VSYNC_FRAME_PACING",
                "DROIDBRIDGE_VSYNC_TARGET_FPS",
                "http_proxy",
                "https_proxy",
                "all_proxy",
                "HTTP_PROXY",
                "HTTPS_PROXY",
                "ALL_PROXY",
                "NO_PROXY",
                "no_proxy",
                "JAVA_TOOL_OPTIONS",
                "JDK_JAVA_OPTIONS",
                "_JAVA_OPTIONS",
                "DROIDBRIDGE_P2P_PROXY",
                "DROIDBRIDGE_P2P_HOST",
                "DROIDBRIDGE_P2P_PORT",
                "DROIDBRIDGE_WEBRTC_PROXY",
                "WEBRTC_PROXY"
        };

        for (String key : keys) {
            env.put(key, "");
        }
    }

    private static void setEnv(@NonNull String key, @Nullable String value) {
        if (value == null) return;
        try {
            if (value.isEmpty()) {
                Os.unsetenv(key);
                Logging.i(TAG, "env unset " + key);
            } else {
                Os.setenv(key, value, true);
                Logging.i(TAG, "env " + key + "=" + value);
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to update env " + key, throwable);
        }
    }

    @NonNull
    private static String safeEnv(@NonNull String key) {
        String value = Os.getenv(key);
        return value != null ? value : "";
    }

    @NonNull
    private static String inferDroidBridgeExecEgl(@NonNull RendererInterface renderer) {
        String id = renderer.getRendererId().toLowerCase(Locale.ROOT);
        String name = renderer.getRendererName().toLowerCase(Locale.ROOT);
        String library = sanitizeLibraryName(renderer.getRendererLibrary()).toLowerCase(Locale.ROOT);
        String combined = id + " " + name + " " + library;

        if (isLtwRenderer(renderer)) {
            return "libltw.so";
        }

        if (KopperZinkRenderer.isRenderer(renderer)) {
            return KopperZinkRenderer.EGL_LIBRARY;
        }

        if (isLiteGlesRenderer(renderer)) {
            return "libEGL.so";
        }

        if (combined.contains("gl4es")
                || combined.contains("opengles")
                || combined.contains("krypton")
                || combined.contains("ng_gl4es")) {
            return "libEGL.so";
        }

        if (isMobileGluesRenderer(renderer)) {
            return "libmobileglues.so";
        }

        if (combined.contains("osmesa")
                || combined.contains("zink")
                || combined.contains("mesa")
                || combined.contains("virgl")
                || combined.contains("freedreno")
                || combined.contains("panfrost")) {
            return sanitizeLibraryName(renderer.getRendererLibrary());
        }

        return "";
    }


    private static boolean shouldApplyDriverPluginEnvironment(@Nullable RendererInterface renderer) {
        // DriverPluginManager carries the global Vulkan-driver selection used by
        // VulkanMod as well as Zink, so export it for every renderer and let the
        // renderer-specific environment blocks clear custom driver paths when needed.
        return renderer != null;
    }

    private static boolean isMobileGluesRenderer(@Nullable RendererInterface renderer) {
        if (renderer == null) return false;
        String combined = (renderer.getUniqueIdentifier() + " "
                + renderer.getRendererName() + " "
                + renderer.getRendererId() + " "
                + renderer.getRendererLibrary() + " "
                + sanitizeLibraryName(renderer.getRendererEGL())).toLowerCase(Locale.ROOT);
        return combined.contains("mobileglues")
                || combined.contains("mobile glues")
                || combined.contains("com.fcl.plugin.mobileglues");
    }

    private static boolean isKryptonRenderer(@Nullable RendererInterface renderer) {
        if (renderer == null) return false;
        String combined = (renderer.getUniqueIdentifier() + " "
                + renderer.getRendererName() + " "
                + renderer.getRendererId() + " "
                + renderer.getRendererLibrary() + " "
                + sanitizeLibraryName(renderer.getRendererEGL())).toLowerCase(Locale.ROOT);
        return combined.contains("krypton") || combined.contains("ng_gl4es");
    }

    private static void clearMesaAndVulkanDriverEnvironment(@NonNull LinkedHashMap<String, String> env) {
        env.put("DROIDBRIDGE_OSMESA_LIBRARY", "");
        env.put("OSMESA_LIBRARY", "");
        env.put("OSMESA_LIB", "");
        env.put("LIBGL_OSMESA", "");
        env.put("LIB_MESA_NAME", "");

        env.put("DROIDBRIDGE_MESA", "");
        env.put("DROIDBRIDGE_MESA_MODE", "");
        env.put("DROIDBRIDGE_MESA_DRIVER", "");
        env.put("DROIDBRIDGE_MESA_NATIVE_DIR", "");
        env.put("DROIDBRIDGE_MESA_ALIAS_DIR", "");
        env.put("DROIDBRIDGE_KOPPER_VULKAN_ALIAS_DIR", "");
        env.put("DROIDBRIDGE_MESA_NAMESPACE", "");
        env.put("DROIDBRIDGE_MESA_NAMESPACE_PATH", "");
        env.put("DROIDBRIDGE_MESA_EGL", "");
        env.put("DROIDBRIDGE_MESA_EGL_SINGLE_INSTANCE", "");
        env.put("DROIDBRIDGE_MESA_GL", "");
        env.put("DROIDBRIDGE_MESA_DESKTOP_GL", "");
        env.put("DROIDBRIDGE_MESA_SAFE_SWAPS", "");
        env.put("DROIDBRIDGE_EGL_FORCE_DESKTOP_GL", "");
        env.put("DROIDBRIDGE_GL_LEGACY_COMPAT_PROFILE", "");
        env.put("DROIDBRIDGE_LEGACY_OPTIFINE_SHADER_COMPAT", "");
        env.put("DROIDBRIDGE_MESA_LEGACY_COMPAT_PROFILE", "");
        env.put("DROIDBRIDGE_EGL_FORCE_COMPAT_PROFILE", "");
        env.put("DROIDBRIDGE_EGL_FORCE_CORE_PROFILE", "");
        env.put("DROIDBRIDGE_DISABLE_LEGACY_OCCLUSION_QUERY", "");
            env.put("DROIDBRIDGE_LEGACY_OCCLUSION_QUERY_STUB", "");
        env.put("DROIDBRIDGE_EGL_NO_SYSTEM_FALLBACK", "");
        env.put("DROIDBRIDGE_EGL_RECOVER_CURRENT", "");

        env.put("MESA_PROCESS_NAME", "");
        env.put("MESA_DRICONF_EXECUTABLE_OVERRIDE", "");
        env.put("force_gl_vendor", "");
        env.put("MESA_LOADER_DRIVER_OVERRIDE", "");
        env.put("GALLIUM_DRIVER", "");
        env.put("MESA_GL_VERSION_OVERRIDE", "");
        env.put("MESA_GLSL_VERSION_OVERRIDE", "");
        env.put("MESA_EXTENSION_OVERRIDE", "");
        env.put("MESA_GLSL_CACHE_DIR", "");
        env.put("MESA_SHADER_CACHE_DIR", "");
        env.put("MESA_DEBUG", "");
        env.put("MESA_NO_ERROR", "");
        env.put("mesa_glthread", "");
        env.put("EGL_PLATFORM", "");
        env.put("LIBGL_ES", "");
        env.put("LIBGL_GL", "");
        env.put("LIBGL_FBO", "");
        env.put("LIBGL_MIPMAP", "");
        env.put("LIBGL_NOINTOVLHACK", "");
        env.put("LIBGL_NORMALIZE", "");
        env.put("LIBGL_NOERROR", "");
        env.put("LIBGL_DRIVERS_PATH", "");
        env.put("EGL_DRIVERS_PATH", "");
        env.put("DRIVER_PATH", "");

        env.put("DROIDBRIDGE_LOAD_TURNIP", "");
        env.put("DROIDBRIDGE_LOAD_TURNIP", "");
        env.put("DROIDBRIDGE_USE_CUSTOM_TURNIP", "");
        env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", "");
        env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", "");
        env.put("VK_ICD_FILENAMES", "");
        env.put("VK_DRIVER_FILES", "");
        env.put("VK_INSTANCE_LAYERS", "");
        env.put("VULKAN_PTR", "");
        env.put("TU_DEBUG", "");
        env.put("ZINK_DEBUG", "");
        env.put("ZINK_DESCRIPTORS", "");
        env.put("MESA_VK_WSI_PRESENT_MODE", "");
        env.put("DROIDBRIDGE_VSYNC_IN_ZINK", "");
        env.put("DROIDBRIDGE_VULKAN_FORCE_FIFO", "");
        env.put("DROIDBRIDGE_USE_SYSTEM_VULKAN", "1");
        env.put("JAVA_LAUNCHER_USE_SYSTEM_VULKAN_DRIVER", "1");
        env.put("JAVA_LAUNCHER_VULKAN_DRIVER", "system");
    }

    private static boolean isLiteGlesRenderer(@Nullable RendererInterface renderer) {
        if (renderer == null) return false;
        String combined = (renderer.getUniqueIdentifier() + " "
                + renderer.getRendererName() + " "
                + renderer.getRendererId() + " "
                + renderer.getRendererLibrary() + " "
                + sanitizeLibraryName(renderer.getRendererEGL())).toLowerCase(Locale.ROOT);
        return combined.contains("litegles")
                || combined.contains("litegl")
                || combined.contains("droidbridge_litegl")
                || combined.contains("ca.dnamobile.renderer.litegles");
    }

    private static boolean isLtwRenderer(@Nullable RendererInterface renderer) {
        if (renderer == null) return false;
        String combined = (renderer.getUniqueIdentifier() + " "
                + renderer.getRendererName() + " "
                + renderer.getRendererId() + " "
                + renderer.getRendererLibrary() + " "
                + sanitizeLibraryName(renderer.getRendererEGL())).toLowerCase(Locale.ROOT);
        return combined.contains("ltw") || combined.contains("libltw.so");
    }

    private static void pushLdLibraryPath(@NonNull RuntimePaths paths) {
        try {
            JREUtils.setLdLibraryPath(paths.nativeLinkerPath);
            Logging.i(TAG, "setLdLibraryPath=" + paths.nativeLinkerPath);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to push LD_LIBRARY_PATH into native linker", throwable);
        }
    }

    private static void preloadRuntimeAndGraphics(
            @NonNull LaunchPlan plan,
            @NonNull RendererInterface renderer,
            @NonNull RuntimePaths paths,
            @Nullable LiteGlesLaunchPreloader.Result liteGles
    ) {
        // Load these before the runtime.
        dlopenOptional(new File(PathManager.DIR_NATIVE_LIB, "libSDL3.so"));
        dlopenOptional(new File(PathManager.DIR_NATIVE_LIB, "libSDL2.so"));

        boolean modernLwjgl341Component =
                plan.getLwjglNativeDirectory().getAbsolutePath().contains("lwjgl3.4.1");
        File componentNatives = plan.getLwjglNativeDirectory();
        File componentSpvc = new File(componentNatives, "libspirv-cross.so");
        File componentShaderc = new File(componentNatives, "libshaderc.so");
        File componentVma = new File(componentNatives, "liblwjgl_vma.so");

        if (modernLwjgl341Component) {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                // Never preload the private component copies on Android 10. They
                // live outside the anonymous linker namespace permitted paths and
                // can poison the later LWJGL load even when libname is redirected.
                File compatibilitySpvc = new File(PathManager.DIR_NATIVE_LIB, "libspirv-cross.so");
                File compatibilityShaderc = new File(PathManager.DIR_NATIVE_LIB, "libshaderc.so");
                File compatibilityVma = new File(PathManager.DIR_NATIVE_LIB, "liblwjgl_vma.so");
                dlopenOptional(compatibilitySpvc);
                dlopenOptional(compatibilityShaderc);
                dlopenOptional(compatibilityVma);
            } else {
                dlopenOptional(componentSpvc);
                dlopenOptional(componentShaderc);
                dlopenOptional(componentVma);
            }
        } else {
            dlopenOptional(new File(PathManager.DIR_NATIVE_LIB, "libspirv-cross.so"));
            dlopenOptional(new File(PathManager.DIR_NATIVE_LIB, "libshaderc.so"));
            dlopenOptional(new File(PathManager.DIR_NATIVE_LIB, "libshaderc_shared.so"));
            dlopenOptional(new File(PathManager.DIR_NATIVE_LIB, "liblwjgl_vma.so"));
        }

        if (plan.isUseSystemVulkanDriver()
                && isMinecraft26_2Or26_3Family(plan.getEffectiveMinecraftVersionId())) {
            File lwjglCore = new File(componentNatives, "liblwjgl.so");
            File lwjglStb = new File(componentNatives, "liblwjgl_stb.so");
            String audit = "RuntimeBootstrap: LWJGL 3.4 Vulkan native audit"
                    + " componentNatives=" + componentNatives.getAbsolutePath()
                    + " core=" + lwjglCore.isFile()
                    + " stb=" + lwjglStb.isFile()
                    + " spvc=" + componentSpvc.isFile()
                    + " shaderc=" + componentShaderc.isFile()
                    + " vma=" + componentVma.isFile()
                    + " spvcPath=" + componentSpvc.getAbsolutePath();
            Logging.i(TAG, audit);
            safeAppendLog(audit);
        }
        dlopenOptional("libzstd-jni_dh-1.5.7-6.so");

        boolean needsAwtNative = shouldPrepareAwtNative(plan);

        // Required JVM/runtime libraries. Missing optional ones are logged but do not abort.
        if (needsAwtNative) {
            prepareAwtDummyNative(paths);
        }

        dlopenOptional(new File(paths.runtimeLibDir, "jli/libjli.so"));
        dlopenOptional(new File(paths.runtimeLibDir, "libjli.so"));
        dlopenOptional(new File(paths.jvmLibraryDir, "libjvm.so"));
        dlopenOptional(new File(paths.runtimeLibDir, "libverify.so"));
        dlopenOptional(new File(paths.runtimeLibDir, "libjava.so"));
        dlopenOptional(new File(paths.runtimeLibDir, "libnet.so"));
        dlopenOptional(new File(paths.runtimeLibDir, "libnio.so"));
        dlopenOptional(new File(paths.runtimeLibDir, "libawt.so"));
        dlopenOptional(new File(paths.runtimeLibDir, "libawt_headless.so"));
        if (needsAwtNative) {
            dlopenOptional(new File(paths.runtimeLibDir, "libawt_xawt.so"));
            dlopenOptional(new File(PathManager.DIR_NATIVE_LIB, "libawt_xawt.so"));
        }
        dlopenOptional(new File(paths.runtimeLibDir, "libfreetype.so"));
        dlopenOptional(new File(paths.runtimeLibDir, "libfontmanager.so"));

        // Keep this after the core JVM libs, mirroring the prior implementation's full runtime preloading pass.
        for (File soFile : listSharedLibraries(paths.runtimeLibDir)) {
            dlopenOptional(soFile);
        }

        // Graphics and audio layer. Keep older Android devices on the
        // app-bundled Android-21 OpenAL provider. Loading the LWJGL 3.4.1 Oboe
        // build as well can make LG's Android 10 linker fail before SDL starts.
        boolean android10OpenAlCompat = modernLwjgl341Component
                && Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q;
        File compatibilityOpenAl = new File(PathManager.DIR_NATIVE_LIB, "libopenal.so");
        if (android10OpenAlCompat) {
            dlopenOptional(compatibilityOpenAl);
        } else {
            dlopenOptional(compatibilityOpenAl);
            dlopenOptional(new File(plan.getLwjglNativeDirectory(), "libopenal.so"));
        }

        if (isOpenGlesWrapperRenderer(renderer)) {
            dlopenOptional("libEGL.so");
            dlopenOptional("libGLESv2.so");
            dlopenOptional("libGLESv3.so");
        }

        for (String library : renderer.getDlopenLibrary()) {
            dlopenOptional(library);
        }

        if (liteGles != null) {
            dlopenOptional(liteGles.stagedOSMesaLibrary);
            dlopenOptional(new File(liteGles.stagedNativeDir, "libOSMesa_8.so"));
            dlopenOptional(new File(liteGles.stagedNativeDir, "libOSMesa8.so"));
            dlopenOptional(new File(liteGles.stagedNativeDir, "libGL.so"));
            dlopenOptional(liteGles.stagedMainLibrary);
        }

        String rendererLibrary = sanitizeLibraryName(renderer.getRendererLibrary());
        if (DroidBridgeMesaSupport.isDroidBridgeMesaRenderer(renderer)) {
            Logging.i(TAG, "Skipping generic renderer dlopen for Mesa renderer; native bridge will load "
                    + rendererLibrary + " for " + renderer.getRendererName());
        } else if (!rendererLibrary.isEmpty()) {
            for (File searchPath : renderer.getLibrarySearchPaths()) {
                dlopenOptional(new File(searchPath, rendererLibrary));
            }
            dlopenOptional(new File(PathManager.DIR_NATIVE_LIB, rendererLibrary));
            dlopenOptional(rendererLibrary);
        } else {
            Logging.i(TAG, "Skipping renderer preload because renderer library is empty: " + renderer.getRendererName());
        }

        // LWJGL native entry points from the selected component. On Android 10
        // and older, skip the component OpenAL because LWJGL is explicitly
        // redirected to DroidBridge's Android-21 compatibility provider above.
        for (File soFile : listSharedLibraries(plan.getLwjglNativeDirectory())) {
            if (android10OpenAlCompat && "libopenal.so".equals(soFile.getName())) {
                continue;
            }
            dlopenOptional(soFile);
        }
    }



    private static boolean isOpenGlesWrapperRenderer(@NonNull RendererInterface renderer) {
        String id = renderer.getRendererId().toLowerCase(Locale.ROOT);
        String name = renderer.getRendererName().toLowerCase(Locale.ROOT);
        String library = sanitizeLibraryName(renderer.getRendererLibrary()).toLowerCase(Locale.ROOT);
        String combined = id + " " + name + " " + library;
        return isLiteGlesRenderer(renderer)
                || combined.contains("opengles")
                || combined.contains("gl4es")
                || combined.contains("ng_gl4es")
                || combined.contains("krypton");
    }

    /**
     * The AWT xawt dummy native should only be prepared for launches that actually
     * enabled Cacio/AWT. Do not globally load it for modern LWJGL/GLFW versions.
     */
    private static boolean shouldPrepareAwtNative(@NonNull LaunchPlan plan) {
        for (String arg : plan.getJvmArgs()) {
            if (arg == null) continue;
            String lower = arg.toLowerCase(Locale.ROOT);
            if (lower.contains("cacio")
                    || lower.startsWith("-dawt.toolkit=")
                    || lower.startsWith("-djava.awt.graphicsenv=")) {
                return true;
            }
        }
        return isPre16Version(plan.getVersionId());
    }

    private static boolean isPre16Version(@NonNull String rawVersion) {
        String id = rawVersion.trim().toLowerCase(Locale.ROOT);
        if (id.startsWith("rd-")
                || id.startsWith("classic")
                || id.startsWith("infdev")
                || id.startsWith("indev")
                || id.startsWith("a")
                || id.startsWith("b")) {
            return true;
        }

        if (id.startsWith("1.")) {
            String[] parts = id.split("[^0-9]+");
            int found = 0;
            int major = -1;
            int minor = -1;
            for (String part : parts) {
                if (part == null || part.isEmpty()) continue;
                try {
                    if (found == 0) major = Integer.parseInt(part);
                    if (found == 1) {
                        minor = Integer.parseInt(part);
                        break;
                    }
                    found++;
                } catch (NumberFormatException ignored) {
                }
            }
            return major == 1 && minor >= 0 && minor < 6;
        }

        return false;
    }

    /**
     * Cacio/old AWT needs the fake xawt JNI library where the JRE can find it.
     *
     * This mirrors the prior implementation's MultiRTUtils.copyDummyNativeLib("libawt_xawt.so", ...).
     * It prevents old clients from crashing at java.awt.Component.initIDs().
     */
    private static void prepareAwtDummyNative(@NonNull RuntimePaths paths) {
        copyRuntimeNativeIfNeeded(new File(PathManager.DIR_NATIVE_LIB, "libawt_xawt.so"),
                new File(paths.runtimeLibDir, "libawt_xawt.so"));
    }

    private static void copyRuntimeNativeIfNeeded(@NonNull File source, @NonNull File target) {
        if (!source.isFile()) {
            Logging.i(TAG, "Missing optional native source: " + source.getAbsolutePath());
            return;
        }

        if (target.isFile() && target.length() == source.length()) {
            return;
        }

        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Logging.i(TAG, "Unable to create native target folder: " + parent.getAbsolutePath());
            return;
        }

        try (java.io.InputStream input = new java.io.FileInputStream(source);
             java.io.OutputStream output = new java.io.FileOutputStream(target)) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }

            //noinspection ResultOfMethodCallIgnored
            target.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            target.setExecutable(true, false);

            Logging.i(TAG, "Prepared native " + source.getAbsolutePath()
                    + " -> " + target.getAbsolutePath());
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to prepare native " + target.getAbsolutePath(), throwable);
        }
    }

    @NonNull
    private static List<File> listSharedLibraries(@NonNull File root) {
        if (!root.isDirectory()) return Collections.emptyList();
        ArrayList<File> result = new ArrayList<>();
        collectSharedLibraries(root, result);
        result.sort((a, b) -> a.getAbsolutePath().compareToIgnoreCase(b.getAbsolutePath()));
        return result;
    }

    private static void collectSharedLibraries(@NonNull File root, @NonNull List<File> out) {
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                collectSharedLibraries(child, out);
            } else if (child.isFile() && child.getName().endsWith(".so")) {
                out.add(child);
            }
        }
    }

    private static boolean dlopenOptional(@NonNull File file) {
        if (!file.isFile()) return false;
        return dlopenOptional(file.getAbsolutePath());
    }

    private static boolean dlopenOptional(@Nullable String path) {
        if (path == null || path.trim().isEmpty()) return false;
        try {
            boolean loaded = JREUtils.dlopen(path);
            Logging.i(TAG, "dlopen " + path + " = " + loaded);
            return loaded;
        } catch (Throwable throwable) {
            Logging.e(TAG, "dlopen failed for " + path, throwable);
            return false;
        }
    }

    @NonNull
    private static String sanitizeLibraryName(@Nullable String library) {
        if (library == null) return "";
        String cleaned = library.trim();
        if (cleaned.isEmpty() || "null".equalsIgnoreCase(cleaned) || "(null)".equalsIgnoreCase(cleaned)) {
            return "";
        }
        return new File(cleaned).getName();
    }

    @Nullable
    private static String findMesaDriverPath(@NonNull RuntimePaths paths) {
        ArrayList<File> candidates = new ArrayList<>();
        candidates.add(new File(PathManager.DIR_NATIVE_LIB, "dri"));
        candidates.add(new File(PathManager.DIR_NATIVE_LIB, "gallium"));
        candidates.add(new File(PathManager.DIR_NATIVE_LIB));
        for (String item : paths.ldLibraryPath.split(":")) {
            if (item == null || item.trim().isEmpty()) continue;
            File base = new File(item.trim());
            candidates.add(new File(base, "dri"));
            candidates.add(new File(base, "gallium"));
        }
        for (File candidate : candidates) {
            if (candidate.isDirectory()) return candidate.getAbsolutePath();
        }
        return null;
    }

    @Nullable
    private static String findJsphLibrary(@NonNull RuntimePaths paths) {
        int javaVersion = parseRuntimeJavaVersion(paths.runtimeHome.getName());
        if (javaVersion <= 11) return null;

        String prefix = javaVersion == 17 ? "libjsph17" : "libjsph21";
        File nativeDir = new File(PathManager.DIR_NATIVE_LIB);
        File[] files = nativeDir.listFiles((dir, name) -> name.startsWith(prefix) && name.endsWith(".so"));
        if (files == null || files.length == 0) return null;
        return files[0].getAbsolutePath();
    }

    private static int parseRuntimeJavaVersion(@NonNull String runtimeName) {
        if (runtimeName.contains("25")) return 25;
        if (runtimeName.contains("21")) return 21;
        if (runtimeName.contains("17")) return 17;
        if (runtimeName.contains("8")) return 8;
        return 0;
    }

    public static final class RuntimePaths {
        @NonNull public final File runtimeHome;
        @NonNull public final File runtimeLibDir;
        @NonNull public final File jvmLibraryDir;
        @NonNull public final String ldLibraryPath;
        @NonNull public final String nativeLinkerPath;

        private RuntimePaths(
                @NonNull File runtimeHome,
                @NonNull File runtimeLibDir,
                @NonNull File jvmLibraryDir,
                @NonNull String ldLibraryPath,
                @NonNull String nativeLinkerPath
        ) {
            this.runtimeHome = runtimeHome;
            this.runtimeLibDir = runtimeLibDir;
            this.jvmLibraryDir = jvmLibraryDir;
            this.ldLibraryPath = ldLibraryPath;
            this.nativeLinkerPath = nativeLinkerPath;
        }

        @NonNull
        static RuntimePaths resolve(
                @NonNull Context context,
                @NonNull LaunchPlan plan,
                @NonNull RendererInterface renderer,
                @NonNull FFmpegPluginCompat.Result ffmpeg,
                @Nullable LiteGlesLaunchPreloader.Result liteGles
        ) {
            File runtimeHome = plan.getRuntimeDirectory();
            File runtimeLibDir = resolveRuntimeLibDir(runtimeHome);
            File jvmLibraryDir = new File(runtimeLibDir, "server/libjvm.so").isFile()
                    ? new File(runtimeLibDir, "server")
                    : new File(runtimeLibDir, "client");

            ArrayList<String> paths = new ArrayList<>();

            /*
             * Keep the JVM runtime paths first. OpenJDK's launcher can re-exec
             * java when LD_LIBRARY_PATH does not begin with the expected runtime
             * server/lib path. If the FFmpeg plugin path is prepended here, some
             * Android builds fail immediately with:
             *
             *   Bad address
             *   Error: trying to exec .../bin/java.
             *
             * FFmpeg only needs to be discoverable by child process/native lookup,
             * so append its plugin directory after the core JVM paths instead of
             * before them.
             */
            addPath(paths, jvmLibraryDir);
            addPath(paths, new File(runtimeLibDir, "jli"));
            addPath(paths, runtimeLibDir);

            if (liteGles != null) {
                addPath(paths, liteGles.stagedNativeDir);
                addPath(paths, liteGles.pluginNativeDir);
            }

            if (ffmpeg.available && ffmpeg.libraryPath != null) {
                addPath(paths, new File(ffmpeg.libraryPath));
            }

            addPath(paths, plan.getLwjglNativeDirectory());
            for (File rendererPath : renderer.getLibrarySearchPaths()) {
                addPath(paths, rendererPath);
            }
            if (shouldApplyDriverPluginEnvironment(renderer)) {
                for (File driverPath : DriverPluginManager.getSelectedDriverLibrarySearchPaths(context, renderer, plan.isUseSystemVulkanDriver())) {
                    addPath(paths, driverPath);
                }
            }
            addSystemVendorPaths(paths);
            if (PathManager.DIR_RUNTIME_MOD != null) addPath(paths, PathManager.DIR_RUNTIME_MOD);
            addPath(paths, new File(PathManager.DIR_NATIVE_LIB));

            String ldLibraryPath = joinPathList(paths);
            String nativeLinkerPath = jvmLibraryDir.getAbsolutePath() + ":" + ldLibraryPath;

            Logging.i(TAG, "runtimeHome=" + runtimeHome.getAbsolutePath());
            Logging.i(TAG, "runtimeLibDir=" + runtimeLibDir.getAbsolutePath());
            Logging.i(TAG, "jvmLibraryDir=" + jvmLibraryDir.getAbsolutePath());
            Logging.i(TAG, "LD_LIBRARY_PATH=" + ldLibraryPath);

            return new RuntimePaths(runtimeHome, runtimeLibDir, jvmLibraryDir, ldLibraryPath, nativeLinkerPath);
        }

        @NonNull
        private static File resolveRuntimeLibDir(@NonNull File runtimeHome) {
            for (String arch : getRuntimeArchCandidates()) {
                File candidate = new File(runtimeHome, "lib/" + arch);
                if (candidate.isDirectory()) return candidate;
            }

            return new File(runtimeHome, "lib");
        }

        @NonNull
        private static List<String> getRuntimeArchCandidates() {
            ArrayList<String> candidates = new ArrayList<>();
            String archName = Architecture.archAsString(Architecture.getDeviceArchitecture());
            addArchCandidate(candidates, archName);

            if (Architecture.getDeviceArchitecture() == Architecture.ARCH_ARM64 || archName.contains("arm64") || archName.contains("aarch64")) {
                addArchCandidate(candidates, "aarch64");
                addArchCandidate(candidates, "arm64");
                addArchCandidate(candidates, "arm64-v8a");
            } else if (Architecture.getDeviceArchitecture() == Architecture.ARCH_ARM || archName.contains("arm")) {
                addArchCandidate(candidates, "arm");
                addArchCandidate(candidates, "armeabi-v7a");
            } else if (Architecture.getDeviceArchitecture() == Architecture.ARCH_X86) {
                addArchCandidate(candidates, "i386");
                addArchCandidate(candidates, "i486");
                addArchCandidate(candidates, "i586");
                addArchCandidate(candidates, "x86");
            } else if (Architecture.getDeviceArchitecture() == Architecture.ARCH_X86_64 || archName.contains("x86_64") || archName.contains("amd64")) {
                addArchCandidate(candidates, "amd64");
                addArchCandidate(candidates, "x86_64");
            }

            return candidates;
        }

        private static void addArchCandidate(@NonNull List<String> candidates, @Nullable String value) {
            if (value == null || value.trim().isEmpty()) return;
            for (String part : value.split("/")) {
                if (!part.trim().isEmpty() && !candidates.contains(part)) candidates.add(part);
            }
        }

        private static void addSystemVendorPaths(@NonNull List<String> paths) {
            String libName = is64BitDevice() ? "lib64" : "lib";
            paths.add("/system/" + libName);
            paths.add("/vendor/" + libName);
            paths.add("/vendor/" + libName + "/hw");
        }

        private static boolean is64BitDevice() {
            return Build.SUPPORTED_64_BIT_ABIS != null && Build.SUPPORTED_64_BIT_ABIS.length > 0;
        }

        private static void addPath(@NonNull List<String> paths, @NonNull File path) {
            if (!path.exists()) return;
            String absolutePath = path.getAbsolutePath();
            if (!paths.contains(absolutePath)) paths.add(absolutePath);
        }

        @NonNull
        private static String joinPathList(@NonNull List<String> paths) {
            StringBuilder out = new StringBuilder();
            for (String path : paths) {
                if (path == null || path.trim().isEmpty()) continue;
                if (out.length() > 0) out.append(':');
                out.append(path);
            }
            return out.toString();
        }
    }
    private static boolean shouldEnableWebRtcNativeForLaunch(@NonNull LaunchPlan plan) {
        String classPath = plan.getClassPath().toLowerCase(Locale.ROOT);
        if (classPath.contains("webrtc_bridge")
                || classPath.contains("devonvoid")
                || classPath.contains("dev.onvoid.webrtc")
                || classPath.contains("webrtc-java")
                || classPath.contains("peerconnection")) {
            return true;
        }

        if (modsDirectoryContainsWebRtcFeature(new File(plan.getGameDirectory(), "mods"))) return true;

        File instanceDir = plan.getGameDirectory().getParentFile();
        if (instanceDir != null && modsDirectoryContainsWebRtcFeature(new File(instanceDir, "mods"))) return true;

        File cursor = plan.getGameDirectory();
        for (int depth = 0; depth < 5 && cursor != null; depth++) {
            if (".minecraft".equals(cursor.getName())
                    && modsDirectoryContainsWebRtcFeature(new File(cursor, "mods"))) {
                return true;
            }
            cursor = cursor.getParentFile();
        }

        return false;
    }

    private static boolean modsDirectoryContainsWebRtcFeature(@Nullable File modsDir) {
        if (modsDir == null || !modsDir.isDirectory()) return false;

        File[] files = modsDir.listFiles();
        if (files == null) return false;

        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".jar")) continue;

            if (name.contains("webrtc")
                    || name.contains("p2p")
                    || name.startsWith("voicechat-")
                    || name.contains("simple-voice-chat")
                    || name.contains("simplevoicechat")) {
                return true;
            }
        }

        return false;
    }


    private static void preloadWebRtcLinuxDesktopShims(@NonNull Context context) {
        preloadPackagedNativeShim(context, "pulse", "libpulse.so");
        preloadPackagedNativeShim(context, "udev", "libudev.so");
        preloadPackagedNativeShim(context, "dbus-1", "libdbus-1.so");
        preloadPackagedNativeShim(context, "X11", "libX11.so");
        preloadPackagedNativeShim(context, "Xfixes", "libXfixes.so");
        preloadPackagedNativeShim(context, "Xrandr", "libXrandr.so");
        preloadPackagedNativeShim(context, "Xcomposite", "libXcomposite.so");
        preloadPackagedNativeShim(context, "Xdamage", "libXdamage.so");
        preloadPackagedNativeShim(context, "Xrender", "libXrender.so");
        preloadPackagedNativeShim(context, "Xext", "libXext.so");
        preloadPackagedNativeShim(context, "Xi", "libXi.so");
        preloadPackagedNativeShim(context, "Xtst", "libXtst.so");
        preloadPackagedNativeShim(context, "Xcursor", "libXcursor.so");
        preloadPackagedNativeShim(context, "Xinerama", "libXinerama.so");
        preloadPackagedNativeShim(context, "Xss", "libXss.so");
        preloadPackagedNativeShim(context, "xcb", "libxcb.so");
        preloadPackagedNativeShim(context, "Xau", "libXau.so");
        preloadPackagedNativeShim(context, "Xdmcp", "libXdmcp.so");
        preloadPackagedNativeShim(context, "drm", "libdrm.so");
        preloadPackagedNativeShim(context, "gbm", "libgbm.so");
    }

    private static void preloadPackagedNativeShim(
            @NonNull Context context,
            @NonNull String loadLibraryName,
            @NonNull String fileName
    ) {
        File nativeDir = null;

        try {
            if (context.getApplicationInfo() != null
                    && context.getApplicationInfo().nativeLibraryDir != null
                    && !context.getApplicationInfo().nativeLibraryDir.trim().isEmpty()) {
                nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
            }
        } catch (Throwable ignored) {
        }

        if (nativeDir == null && PathManager.DIR_NATIVE_LIB != null && !PathManager.DIR_NATIVE_LIB.trim().isEmpty()) {
            nativeDir = new File(PathManager.DIR_NATIVE_LIB);
        }

        File library = nativeDir != null ? new File(nativeDir, fileName) : null;

        if (library != null && library.isFile()) {
            try {
                boolean loaded = JREUtils.dlopen(library.getAbsolutePath());
                Logging.i(TAG, "WebRTC Linux shim dlopen " + library.getAbsolutePath() + " = " + loaded);
                return;
            } catch (Throwable throwable) {
                Logging.e(TAG, "WebRTC Linux shim dlopen failed: " + library.getAbsolutePath(), throwable);
            }
        }

        try {
            System.loadLibrary(loadLibraryName);
            Logging.i(TAG, "WebRTC Linux shim loaded with System.loadLibrary(\"" + loadLibraryName + "\")");
        } catch (Throwable throwable) {
            Logging.e(TAG, "WebRTC Linux shim unavailable: " + loadLibraryName, throwable);
        }
    }

    private static void preloadAndroidWebRtcNativeTest(@NonNull Context context) {
        File nativeDir = null;

        try {
            if (context.getApplicationInfo() != null
                    && context.getApplicationInfo().nativeLibraryDir != null
                    && !context.getApplicationInfo().nativeLibraryDir.trim().isEmpty()) {
                nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
            }
        } catch (Throwable ignored) {
        }

        if (nativeDir == null && PathManager.DIR_NATIVE_LIB != null && !PathManager.DIR_NATIVE_LIB.trim().isEmpty()) {
            nativeDir = new File(PathManager.DIR_NATIVE_LIB);
        }

        File library = nativeDir != null ? new File(nativeDir, "libjingle_peerconnection_so.so") : null;

        if (library != null && library.isFile()) {
            try {
                boolean loaded = JREUtils.dlopen(library.getAbsolutePath());
                Logging.i(TAG, "Android WebRTC native test dlopen " + library.getAbsolutePath() + " = " + loaded);
                return;
            } catch (Throwable throwable) {
                Logging.e(TAG, "Android WebRTC native test dlopen failed: " + library.getAbsolutePath(), throwable);
            }
        }

        try {
            System.loadLibrary("jingle_peerconnection_so");
            Logging.i(TAG, "Android WebRTC native test loaded with System.loadLibrary(\"jingle_peerconnection_so\")");
        } catch (Throwable throwable) {
            Logging.e(TAG, "Android WebRTC native test unavailable", throwable);
        }
    }


    private static void safeAppendLog(@NonNull String message) {
        try {
            LauncherLogManager.append(message);
        } catch (Throwable ignored) {
        }
        try {
            Logging.i(TAG, message);
        } catch (Throwable ignored) {
        }
    }

}

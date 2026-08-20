/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, DroidBridge Launcher, third-party launcher,
 * or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.renderer;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DroidBridgeNativeGlfwKgslRenderer implements RendererInterface {
    public static final String RENDERER_ID = "droidbridge_native_glfw_kgsl";
    public static final String RENDERER_UUID = "9cda3f9b-7d58-4c55-a763-64c5c8c1f001";

    public static boolean isRenderer(RendererInterface renderer) {
        return renderer != null
                && (RENDERER_ID.equals(renderer.getRendererId())
                || RENDERER_UUID.equalsIgnoreCase(renderer.getUniqueIdentifier()));
    }

    @NonNull
    @Override
    public String getRendererId() {
        return RENDERER_ID;
    }

    @NonNull
    @Override
    public String getUniqueIdentifier() {
        return RENDERER_UUID;
    }

    @NonNull
    @Override
    public String getRendererName() {
        return "Freedreno Mesa (Adreno-only)";
    }

    @NonNull
    @Override
    public String getRendererDescription() {
        return "Freedreno Mesa/KGSL path for Adreno GPUs only. Uses DroidBridge's direct Android Surface/EGL bridge.";
    }

    @NonNull
    @Override
    public Map<String, String> getRendererEnv() {
        LinkedHashMap<String, String> env = new LinkedHashMap<>();

        // Keep these explicitly empty so stale values from older NativeGLFW tests
        // cannot leak into JavaRuntimeBootstrap.
        env.put("DROIDBRIDGE_NATIVE_GLFW", "");
        env.put("DROIDBRIDGE_NATIVE_GLFW_KGSL", "");
        env.put("DROIDBRIDGE_NATIVE_GLFW_DESKTOP_GL", "");
        env.put("DROIDBRIDGE_NATIVE_GLFW_LIB", "");
        env.put("DROIDBRIDGE_NATIVE_GLFW_EGL", "");
        env.put("DROIDBRIDGE_NATIVE_GLFW_DRIVER", "");

        env.put("DROIDBRIDGE_MESA", "1");
        env.put("DROIDBRIDGE_MESA_AAR_SINGLE_SOURCE", "1");
        env.put("DROIDBRIDGE_MESA_MODE", "freedreno_kgsl");
        env.put("DROIDBRIDGE_MESA_DRIVER", "kgsl");
        env.put("DROIDBRIDGE_MESA_DESKTOP_GL", "1");

        env.put("DROIDBRIDGE_RENDERER", "freedreno_kgsl");
        env.put("DROIDBRIDGE_RENDERER_MESA_MODE", "freedreno_kgsl");
        env.put("DROIDBRIDGE_EGL", DroidBridgeMesaSupport.LIB_EGL_MESA);
        env.put("DROIDBRIDGE_EGL_LIBRARY", DroidBridgeMesaSupport.LIB_EGL_MESA);
        env.put("DROIDBRIDGE_EGL_LIBRARY", DroidBridgeMesaSupport.LIB_EGL_MESA);
        env.put("DROIDBRIDGE_RENDERER_LIBRARY", DroidBridgeMesaSupport.LIB_EGL_MESA);
        env.put("DROIDBRIDGE_RENDERER_LIBRARY", DroidBridgeMesaSupport.LIB_EGL_MESA);
        env.put("LIB_MESA_NAME", DroidBridgeMesaSupport.LIB_EGL_MESA);

        env.put("MESA_PROCESS_NAME", "DroidBridge");
        env.put("MESA_DRICONF_EXECUTABLE_OVERRIDE", "DroidBridge");
        env.put("force_gl_vendor", "freedreno/DroidBridge");
        env.put("MESA_LOADER_DRIVER_OVERRIDE", "kgsl");
        env.put("GALLIUM_DRIVER", "");
        env.put("EGL_PLATFORM", "android");
        env.put("LIBGL_ES", "2");
        // Do not force Mesa into a COMPAT profile from the environment.
        // The native GL bridge now requests a 4.6 Core Profile first; forcing
        // 4.6COMPAT here made Oculus/Embeddium run a different shader/FBO path.
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
        env.put("DROIDBRIDGE_MESA_EGL_SINGLE_INSTANCE", "1");
        env.put("DROIDBRIDGE_DIRECT_FREEDRENO_NATIVE_SURFACE_V69", "1");
        env.put("DROIDBRIDGE_DIRECT_FREEDRENO_TEXTUREVIEW_V70", "");
        env.put("MESA_EXTENSION_OVERRIDE", "");
        env.put("mesa_glthread", "");
        // V16: do not force Minecraft VSync on Native Mesa. DroidBridge should let the
        // toggle drives swapInterval while still using a fixed FPS cap.
        env.put("FORCE_VSYNC", "false");
        env.put("DROIDBRIDGE_DISABLE_NATIVE_FRAME_PACER_FOR_MESA", "");
        env.put("DROIDBRIDGE_NATIVE_MESA_SOFT_FPS_CAP", "1");
        env.put("DROIDBRIDGE_VSYNC_FRAME_PACING", "1");
        // Freedreno Mesa needs Android ANativeWindow.setSwapInterval too;
        // eglSwapInterval alone can report OK while the Surface keeps presenting unlocked.
        env.put("DROIDBRIDGE_VSYNC_IN_ZINK", "1");

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

        return env;
    }

    @NonNull
    @Override
    public List<String> getDlopenLibrary() {
        return Collections.emptyList();
    }

    @NonNull
    @Override
    public String getRendererLibrary() {
        return DroidBridgeMesaSupport.LIB_EGL_MESA;
    }

    @Override
    public String getRendererEGL() {
        return DroidBridgeMesaSupport.LIB_EGL_MESA;
    }
}

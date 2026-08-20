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

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class VulkanZinkRenderer implements RendererInterface {
    @NonNull
    @Override
    public String getRendererId() {
        return "vulkan_zink";
    }

    @NonNull
    @Override
    public String getUniqueIdentifier() {
        return "0fa435e2-46df-45c9-906c-b29606aaef00";
    }

    @NonNull
    @Override
    public String getRendererName() {
        return "Vulkan Zink";
    }

    @NonNull
    @Override
    public String getRendererDescription() {
        return "Vulkan-backed OpenGL renderer. Useful for newer Minecraft versions when the device supports Vulkan.";
    }

    @NonNull
    @Override
    public Map<String, String> getRendererEnv() {
        LinkedHashMap<String, String> env = new LinkedHashMap<>();
        env.put("MESA_GL_VERSION_OVERRIDE", "4.6");
        env.put("MESA_GLSL_VERSION_OVERRIDE", "460");
        env.put("GALLIUM_DRIVER", "zink");
        env.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
        env.put("MESA_NO_ERROR", "1");
        env.put("LIB_MESA_NAME", getRendererLibrary());
        env.put("LIBGL_ES", "3");
        env.put("LIBGL_NOERROR", "1");
        env.put("LIBGL_NORMALIZE", "1");
        env.put("LIBGL_MIPMAP", "3");
        env.put("LIBGL_NOINTOVLHACK", "1");
        env.put("allow_higher_compat_version", "true");
        env.put("allow_glsl_extension_directive_midshader", "true");
        env.put("force_glsl_extensions_warn", "true");
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
        return "libOSMesa_8.so";
    }

    @Override
    public String getRendererEGL() {
        return getRendererLibrary();
    }
}

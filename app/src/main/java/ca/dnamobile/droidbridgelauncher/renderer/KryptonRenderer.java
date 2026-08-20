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

public final class KryptonRenderer implements RendererInterface {
    @NonNull
    @Override
    public String getRendererId() {
        return "opengles3";
    }

    @NonNull
    @Override
    public String getUniqueIdentifier() {
        return "e7b90ed6-e518-4d4e-93dc-5c7133cd5b31";
    }

    @NonNull
    @Override
    public String getRendererName() {
        return "Krypton Wrapper";
    }

    @NonNull
    @Override
    public String getRendererDescription() {
        return "Works with all versions but can have some graphics issues.";
    }

    @NonNull
    @Override
    public Map<String, String> getRendererEnv() {
        LinkedHashMap<String, String> env = new LinkedHashMap<>();
        env.put("LIBGL_USE_MC_COLOR", "1");
        env.put("LIBGL_GL", "31");
        env.put("LIBGL_ES", "3");
        env.put("LIBGL_NORMALIZE", "1");
        env.put("LIBGL_NOERROR", "1");
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
        return "libng_gl4es.so";
    }

    @Override
    public String getRendererEGL() {
        return getRendererLibrary();
    }
}

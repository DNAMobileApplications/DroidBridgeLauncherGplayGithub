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

public final class GL4ESRenderer implements RendererInterface {
    @NonNull
    @Override
    public String getRendererId() {
        return "opengles2";
    }

    @NonNull
    @Override
    public String getUniqueIdentifier() {
        return "8b52d82d-8f6d-4d3a-a767-dc93f8b72fc7";
    }

    @NonNull
    @Override
    public String getRendererName() {
        return "GL4ES";
    }

    @NonNull
    @Override
    public String getRendererDescription() {
        return "Legacy OpenGL wrapper. Works best for Minecraft 1.21.4 and below.";
    }

    @NonNull
    @Override
    public Map<String, String> getRendererEnv() {
        LinkedHashMap<String, String> env = new LinkedHashMap<>();

        /*
         * Holy GL4ES/GL4ES Plus expects these values to be explicit.
         * Leaving them unset lets the Android bridge/native side fall back to
         * device/default parsing, which can crash during EGL/OpenGL binding on
         * modern Minecraft even though the GL4ES wrapper itself loaded.
         *
         * Keep the OpenGL target at 2.1. Holy GL4ES provides shader/extension
         * translation for newer vanilla clients, while forcing a fake GL 3.x
         * target can break older versions and some devices. Use GLES 3 when
         * available because modern devices expose better precision/extensions
         * through the GLES 3 backend.
         */
        env.put("LIBGL_USE_MC_COLOR", "1");
        env.put("LIBGL_GL", "21");
        env.put("LIBGL_ES", "3");
        env.put("LIBGL_NORMALIZE", "1");
        env.put("LIBGL_NOERROR", "1");
        env.put("LIBGL_MIPMAP", "3");
        env.put("LIBGL_USEVBO", "1");
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
        return "libgl4es_114.so";
    }

    @Override
    public String getRendererEGL() {
        // GL4ES is the OpenGL wrapper. The droidbridge_runtime bridge still needs
        // the real Android EGL library for eglGetProcAddress/eglCreateContext/etc.
        // Passing libgl4es_114.so here causes egl symbol lookups to fail and
        // crashes in libdroidbridge_runtime gl_init.
        return "libEGL.so";
    }
}

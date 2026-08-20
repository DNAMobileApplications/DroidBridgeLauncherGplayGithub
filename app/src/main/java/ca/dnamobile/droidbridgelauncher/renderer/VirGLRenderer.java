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

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

public final class VirGLRenderer implements RendererInterface {
    @NonNull
    @Override
    public String getRendererId() {
        return "gallium_virgl";
    }

    @NonNull
    @Override
    public String getUniqueIdentifier() {
        return "a3ccc1fe-de3f-4a81-8c45-2485181b63b3";
    }

    @NonNull
    @Override
    public String getRendererName() {
        return "VirGLRenderer";
    }

    @NonNull
    @Override
    public String getRendererDescription() {
        return "Legacy VirGL renderer for older device setups.";
    }

    @NonNull
    @Override
    public Map<String, String> getRendererEnv() {
        LinkedHashMap<String, String> env = new LinkedHashMap<>();
        if (PathManager.DIR_CACHE != null) {
            env.put("VTEST_SOCKET_NAME", new File(PathManager.DIR_CACHE, ".virgl_test").getAbsolutePath());
        }
        env.put("GALLIUM_DRIVER", "virpipe");
        env.put("MESA_LOADER_DRIVER_OVERRIDE", "virpipe");
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
        return "libOSMesa_2121.so";
    }

    @Override
    public String getRendererEGL() {
        return getRendererLibrary();
    }
}

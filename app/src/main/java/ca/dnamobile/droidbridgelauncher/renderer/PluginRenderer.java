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
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PluginRenderer implements RendererInterface {
    @NonNull private final String rendererId;
    @NonNull private final String uniqueIdentifier;
    @NonNull private final String rendererName;
    @NonNull private final String rendererDescription;
    @NonNull private final Map<String, String> rendererEnv;
    @NonNull private final List<String> dlopenLibrary;
    @NonNull private final String rendererLibrary;
    @Nullable private final String rendererEgl;
    @NonNull private final List<File> librarySearchPaths;

    public PluginRenderer(
            @NonNull String rendererId,
            @NonNull String uniqueIdentifier,
            @NonNull String rendererName,
            @NonNull String rendererDescription,
            @NonNull Map<String, String> rendererEnv,
            @NonNull List<String> dlopenLibrary,
            @NonNull String rendererLibrary,
            @Nullable String rendererEgl,
            @NonNull List<File> librarySearchPaths
    ) {
        this.rendererId = rendererId;
        this.uniqueIdentifier = uniqueIdentifier;
        this.rendererName = rendererName;
        this.rendererDescription = rendererDescription;
        this.rendererEnv = Collections.unmodifiableMap(new LinkedHashMap<>(rendererEnv));
        this.dlopenLibrary = Collections.unmodifiableList(new ArrayList<>(dlopenLibrary));
        this.rendererLibrary = rendererLibrary;
        this.rendererEgl = rendererEgl;
        this.librarySearchPaths = Collections.unmodifiableList(new ArrayList<>(librarySearchPaths));
    }

    @NonNull
    @Override
    public String getRendererId() {
        return rendererId;
    }

    @NonNull
    @Override
    public String getUniqueIdentifier() {
        return uniqueIdentifier;
    }

    @NonNull
    @Override
    public String getRendererName() {
        return rendererName;
    }

    @NonNull
    @Override
    public String getRendererDescription() {
        return rendererDescription;
    }

    @NonNull
    @Override
    public Map<String, String> getRendererEnv() {
        return rendererEnv;
    }

    @NonNull
    @Override
    public List<String> getDlopenLibrary() {
        return dlopenLibrary;
    }

    @NonNull
    @Override
    public String getRendererLibrary() {
        return rendererLibrary;
    }

    @Nullable
    @Override
    public String getRendererEGL() {
        return rendererEgl;
    }

    @NonNull
    @Override
    public List<File> getLibrarySearchPaths() {
        return librarySearchPaths;
    }

    @Override
    public boolean isExternalPlugin() {
        return true;
    }
}

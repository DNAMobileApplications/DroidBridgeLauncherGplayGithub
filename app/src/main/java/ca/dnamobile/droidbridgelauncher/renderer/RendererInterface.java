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
import java.util.Collections;
import java.util.List;
import java.util.Map;

public interface RendererInterface {
    @NonNull
    String getRendererId();

    @NonNull
    String getUniqueIdentifier();

    @NonNull
    String getRendererName();

    @NonNull
    String getRendererDescription();

    @NonNull
    Map<String, String> getRendererEnv();

    @NonNull
    List<String> getDlopenLibrary();

    @NonNull
    String getRendererLibrary();

    @Nullable
    default String getRendererEGL() {
        return null;
    }

    /**
     * Extra native directories used by external renderer plugins.
     * These are added to LD_LIBRARY_PATH before Minecraft starts so plugin
     * renderer libraries can resolve their own native dependencies.
     */
    @NonNull
    default List<File> getLibrarySearchPaths() {
        return Collections.emptyList();
    }

    default boolean isExternalPlugin() {
        return false;
    }
}

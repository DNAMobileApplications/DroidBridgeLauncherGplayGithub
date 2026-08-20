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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RenderersList {
    @NonNull
    private final List<String> rendererIdentifier;
    @NonNull
    private final List<String> rendererNames;

    public RenderersList(@NonNull List<String> rendererIdentifier, @NonNull List<String> rendererNames) {
        this.rendererIdentifier = Collections.unmodifiableList(new ArrayList<>(rendererIdentifier));
        this.rendererNames = Collections.unmodifiableList(new ArrayList<>(rendererNames));
    }

    @NonNull
    public List<String> getRendererIdentifier() {
        return rendererIdentifier;
    }

    @NonNull
    public List<String> getRendererNames() {
        return rendererNames;
    }
}

/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * Clean-main rewrite pass: DroidBridge-owned implementation surface.
 */

package ca.dnamobile.droidbridgelauncher.dualscreen;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

/**
 * Launcher-side Android asset translation entry point for the dual-screen HUD.
 *
 * Important: this is NOT a Minecraft/Fabric class and must not be bundled into the
 * Fabric mod jar.  Minecraft/Fabric only writes the live HUD state.  Android reads
 * the selected item id, resolves the installed Minecraft assets, translates 26.x
 * item definitions into renderable JSON model data, and DualScreenControlsView then
 * rasterizes/caches the result into the bottom HUD.
 */
final class DualScreenMinecraftAssetRendererService {
    private static final String TAG = "DualScreenAssetRenderer";
    private static final Set<String> LOGGED_ACTIVE_KEYS = new HashSet<>();
    private static final Set<String> LOGGED_UNSUPPORTED_SPECIALS = new HashSet<>();

    private DualScreenMinecraftAssetRendererService() { }

    interface AssetResolver {
        @Nullable File resolveAssetFile(@NonNull String relativePath);
    }

    @NonNull
    static String renderedIconsRelativeDir() {
        return "droidbridge_dual_screen_assets/rendered_icons";
    }

    @Nullable
    static DualScreenItemDefinitionTranslator.RenderPlan resolveInstalledItemDefinition(
            @NonNull final String itemId,
            @NonNull final AssetResolver resolver,
            long nowMillis
    ) {
        DualScreenItemDefinitionTranslator.RenderPlan plan =
                DualScreenItemDefinitionTranslator.resolveInstalledItemDefinition(
                        itemId,
                        new DualScreenItemDefinitionTranslator.AssetResolver() {
                            @Override public File resolveAssetFile(@NonNull String relativePath) {
                                return resolver.resolveAssetFile(relativePath);
                            }
                        },
                        nowMillis
                );
        if (plan != null && !plan.isEmpty()) {
            logActiveOnce(itemId, plan);
        }
        return plan;
    }

    @Nullable
    static JSONObject buildGeneratedSpecialModel(
            @NonNull DualScreenItemDefinitionTranslator.Layer layer
    ) throws Exception {
        try {
            return DualScreenSpecialItemModelFactory.buildModelJsonForSpecialLayer(layer);
        } catch (IllegalArgumentException unsupported) {
            logUnsupportedSpecialOnce(layer, unsupported);
            return null;
        }
    }

    private static void logActiveOnce(
            @NonNull String itemId,
            @NonNull DualScreenItemDefinitionTranslator.RenderPlan plan
    ) {
        synchronized (LOGGED_ACTIVE_KEYS) {
            if (!LOGGED_ACTIVE_KEYS.add(itemId)) return;
        }
        Logging.i(TAG, "Installed item-definition render plan active: "
                + itemId + " layers=" + plan.layers.size() + " source=" + plan.source);
    }

    private static void logUnsupportedSpecialOnce(
            @NonNull DualScreenItemDefinitionTranslator.Layer layer,
            @NonNull Throwable throwable
    ) {
        String key = layer.specialType + "|" + layer.texture + "|" + layer.part;
        synchronized (LOGGED_UNSUPPORTED_SPECIALS) {
            if (!LOGGED_UNSUPPORTED_SPECIALS.add(key)) return;
        }
        Logging.e(TAG, "Unsupported installed special item-definition layer: " + key, throwable);
    }
}

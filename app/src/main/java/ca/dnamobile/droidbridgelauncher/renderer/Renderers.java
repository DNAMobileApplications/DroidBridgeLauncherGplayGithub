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

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;
import ca.dnamobile.droidbridgelauncher.runtime.Architecture;

public final class Renderers {
    public static final String DEFAULT_RENDERER_ID = "e7b90ed6-e518-4d4e-93dc-5c7133cd5b31";

    private static final String TAG = "Renderers";
    private static final LinkedHashMap<String, RendererInterface> RENDERERS = new LinkedHashMap<>();
    @Nullable private static RendererInterface currentRenderer;
    private static boolean initialized;

    private Renderers() {
    }

    public static synchronized void init(@NonNull Context context, boolean reset) {
        PathManager.initContextConstants(context);
        if (initialized && !reset) return;
        initialized = true;
        RENDERERS.clear();
        currentRenderer = null;

        addRenderers(
                new KryptonRenderer(),
                new GL4ESRenderer(),
                new VulkanZinkRenderer(),
                new KopperZinkRenderer(),
                new VirGLRenderer(),
                new DroidBridgeNativeGlfwKgslRenderer(),
                new PanfrostRenderer()
        );

        for (RendererInterface plugin : RendererPluginManager.discoverPlugins(context)) {
            addRenderer(plugin);
        }
    }

    public static synchronized void reload(@NonNull Context context) {
        init(context, true);
    }

    @Deprecated
    public static synchronized void importRendererApk(@NonNull Context context, @NonNull Uri uri) throws Exception {
        RendererInterface imported = RendererPluginManager.importRendererApk(context, uri);
        reload(context);
        LauncherPreferences.setSelectedRendererIdentifier(context, imported.getUniqueIdentifier());
        setCurrentRenderer(context, imported.getUniqueIdentifier(), true);
    }

    public static synchronized void clearPluginCache(@NonNull Context context) {
        RendererPluginManager.clearImportedAndCachedRendererPlugins(context);
        reload(context);
        setCurrentRenderer(context, LauncherPreferences.getSelectedRendererIdentifier(context), true);
    }

    public static synchronized boolean addRenderer(@NonNull RendererInterface renderer) {
        String id = renderer.getUniqueIdentifier();
        if (RENDERERS.containsKey(id)) {
            Logging.i(TAG, "Skipping duplicate renderer: " + renderer.getRendererName() + " (" + id + ")");
            return false;
        }
        RENDERERS.put(id, renderer);
        Logging.i(TAG, "Renderer loaded: " + renderer.getRendererName()
                + " (" + renderer.getRendererId() + " - " + renderer.getUniqueIdentifier() + ")");
        return true;
    }

    public static synchronized void addRenderers(@NonNull RendererInterface... renderers) {
        for (RendererInterface renderer : renderers) addRenderer(renderer);
    }

    @NonNull
    public static synchronized List<RendererInterface> getAllRenderers(@NonNull Context context) {
        init(context, false);
        return new ArrayList<>(RENDERERS.values());
    }

    @NonNull
    public static synchronized List<RendererInterface> getCompatibleRenderers(@NonNull Context context) {
        init(context, false);
        ArrayList<RendererInterface> compatible = new ArrayList<>();
        boolean hasVulkan = hasVulkan(context);
        boolean hasZinkBinary = !(Architecture.getDeviceArchitecture() == Architecture.ARCH_X86);

        for (RendererInterface renderer : RENDERERS.values()) {
            String rendererId = renderer.getRendererId().toLowerCase(Locale.ROOT);
            String uniqueId = renderer.getUniqueIdentifier().toLowerCase(Locale.ROOT);
            if ((rendererId.contains("vulkan") || rendererId.contains("zink") || uniqueId.contains("zink")) && !hasVulkan) {
                continue;
            }
            if (rendererId.contains("zink") && !hasZinkBinary) {
                continue;
            }
            compatible.add(renderer);
        }
        return compatible;
    }

    @NonNull
    public static synchronized RenderersList getRenderersList(@NonNull Context context) {
        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        for (RendererInterface renderer : getCompatibleRenderers(context)) {
            ids.add(renderer.getUniqueIdentifier());
            names.add(renderer.getRendererName());
        }
        return new RenderersList(ids, names);
    }

    public static synchronized void setCurrentRenderer(
            @NonNull Context context,
            @Nullable String uniqueIdentifier,
            boolean retryToFirstOnFailure
    ) {
        List<RendererInterface> compatible = getCompatibleRenderers(context);
        currentRenderer = null;

        if (uniqueIdentifier != null && !uniqueIdentifier.trim().isEmpty()) {
            for (RendererInterface renderer : compatible) {
                if (renderer.getUniqueIdentifier().equals(uniqueIdentifier)) {
                    currentRenderer = renderer;
                    return;
                }
            }
        }

        if (retryToFirstOnFailure && !compatible.isEmpty()) {
            currentRenderer = compatible.get(0);
            LauncherPreferences.setSelectedRendererIdentifier(context, currentRenderer.getUniqueIdentifier());
            Logging.i(TAG, "Renderer fallback selected: " + currentRenderer.getRendererName());
        }
    }

    @NonNull
    public static synchronized RendererInterface getCurrentRenderer(@NonNull Context context) {
        if (currentRenderer == null) {
            setCurrentRenderer(context, LauncherPreferences.getSelectedRendererIdentifier(context), true);
        }
        if (currentRenderer == null) {
            currentRenderer = new KryptonRenderer();
        }
        return currentRenderer;
    }

    @NonNull
    public static synchronized RendererInterface getSelectedRenderer(@NonNull Context context) {
        init(context, false);
        setCurrentRenderer(context, LauncherPreferences.getSelectedRendererIdentifier(context), true);
        return getCurrentRenderer(context);
    }

    @Nullable
    public static synchronized RendererInterface findRenderer(@NonNull Context context, @Nullable String uniqueIdentifier) {
        if (uniqueIdentifier == null) return null;
        init(context, false);
        return RENDERERS.get(uniqueIdentifier);
    }

    public static synchronized int indexOfRenderer(@NonNull List<RendererInterface> renderers, @Nullable String uniqueIdentifier) {
        if (uniqueIdentifier == null) return 0;
        for (int i = 0; i < renderers.size(); i++) {
            if (uniqueIdentifier.equals(renderers.get(i).getUniqueIdentifier())) return i;
        }
        return 0;
    }

    private static boolean hasVulkan(@NonNull Context context) {
        try {
            return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
                    || context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL);
        } catch (Throwable ignored) {
            return true;
        }
    }
}

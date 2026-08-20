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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.instance.LauncherInstance;

/**
 * Launch-time renderer policy for Better Than Adventure.
 *
 * BTA is very sensitive to the GLES wrapper/capability path.  Krypton is the
 * known-good renderer for the current DroidBridge BTA LWJGL3.3.3 component, so
 * BTA launches are forced to Krypton regardless of the global or per-instance
 * renderer choice.
 */
public final class BtaRendererPolicy {
    public static final String KRYPTON_RENDERER_IDENTIFIER = Renderers.DEFAULT_RENDERER_ID;

    private BtaRendererPolicy() {
    }

    public static boolean isBtaLaunch(
            @Nullable String versionId,
            @Nullable LauncherInstance instance
    ) {
        if (instance == null) {
            return isBtaIdentity(null, versionId, versionId, versionId);
        }
        return isBtaIdentity(
                instance.getLoader(),
                instance.getBaseVersionId(),
                instance.getMinecraftVersionId(),
                instance.getName()
        ) || isBtaIdentity(null, versionId, null, null);
    }

    public static boolean isBtaIdentity(
            @Nullable String loader,
            @Nullable String baseVersionId,
            @Nullable String minecraftVersionId,
            @Nullable String instanceName
    ) {
        String combined = normalize(loader)
                + " " + normalize(baseVersionId)
                + " " + normalize(minecraftVersionId)
                + " " + normalize(instanceName);

        return combined.contains("betterthanadventure")
                || combined.contains("better-than-adventure")
                || combined.contains("better_than_adventure")
                || combined.contains("better than adventure")
                || combined.equals("bta")
                || combined.startsWith("bta ")
                || combined.contains(" bta ")
                || combined.contains(" bta(")
                || combined.contains("bta(")
                || combined.contains("bta-v")
                || combined.contains("bta_")
                || combined.contains("bta-");
    }

    @NonNull
    public static RendererInterface resolveRendererForLaunch(
            @NonNull Context context,
            @Nullable String versionId,
            @Nullable LauncherInstance instance,
            @Nullable RendererInterface fallback
    ) {
        if (isBtaLaunch(versionId, instance)) {
            RendererInterface krypton = findKryptonRenderer(context);
            if (krypton != null) return krypton;
        }
        return fallback != null ? fallback : Renderers.getSelectedRenderer(context);
    }

    @NonNull
    public static RendererInterface resolveRendererForLaunch(
            @NonNull Context context,
            @Nullable String loader,
            @Nullable String baseVersionId,
            @Nullable String minecraftVersionId,
            @Nullable String instanceName,
            @Nullable RendererInterface fallback
    ) {
        if (isBtaIdentity(loader, baseVersionId, minecraftVersionId, instanceName)) {
            RendererInterface krypton = findKryptonRenderer(context);
            if (krypton != null) return krypton;
        }
        return fallback != null ? fallback : Renderers.getSelectedRenderer(context);
    }

    @Nullable
    public static RendererInterface findKryptonRenderer(@NonNull Context context) {
        RendererInterface renderer = Renderers.findRenderer(context, KRYPTON_RENDERER_IDENTIFIER);
        if (renderer != null) return renderer;

        for (RendererInterface candidate : Renderers.getCompatibleRenderers(context)) {
            String identity = normalize(candidate.getRendererName())
                    + " " + normalize(candidate.getRendererId())
                    + " " + normalize(candidate.getUniqueIdentifier())
                    + " " + normalize(candidate.getRendererLibrary())
                    + " " + normalize(candidate.getRendererEGL());
            if (identity.contains("krypton")
                    || identity.contains("opengles3") && identity.contains("gl4es")
                    || identity.contains("libng_gl4es")) {
                return candidate;
            }
        }
        return null;
    }

    @NonNull
    private static String normalize(@Nullable String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT);
    }
}

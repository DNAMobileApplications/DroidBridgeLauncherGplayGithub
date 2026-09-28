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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ca.dnamobile.droidbridgelauncher.instance.LauncherInstance;

/**
 * Launch-time renderer compatibility policy for Better Than Adventure.
 *
 * BTA 7.x and older keep the known-good Krypton compatibility lock. BTA 8+
 * requires Java 17 and desktop OpenGL 4.1+, so forcing Krypton 0.4.x (which
 * advertises OpenGL 3.1) makes the user's Mesa selection impossible to use.
 * Modern BTA therefore respects the normal global/per-instance renderer choice.
 */
public final class BtaRendererPolicy {
    public static final String KRYPTON_RENDERER_IDENTIFIER = Renderers.DEFAULT_RENDERER_ID;
    private static final Pattern BTA_MAJOR_PATTERN = Pattern.compile(
            "(?:better[\\s_-]*than[\\s_-]*adventure|bta)[^0-9]{0,24}(\\d+)",
            Pattern.CASE_INSENSITIVE);

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

    /**
     * Returns true for the modern BTA generation (8.x+). The version can be
     * present in the launch id (bta-v8.0.1), Minecraft id, or display name.
     */
    public static boolean isBta8OrNewer(
            @Nullable String versionId,
            @Nullable LauncherInstance instance
    ) {
        int major = detectBtaMajor(versionId);
        if (instance != null) {
            major = Math.max(major, detectBtaMajor(instance.getMinecraftVersionId()));
            major = Math.max(major, detectBtaMajor(instance.getBaseVersionId()));
            major = Math.max(major, detectBtaMajor(instance.getName()));
        }
        return major >= 8;
    }

    public static boolean isBta8OrNewer(
            @Nullable String loader,
            @Nullable String baseVersionId,
            @Nullable String minecraftVersionId,
            @Nullable String instanceName
    ) {
        if (!isBtaIdentity(loader, baseVersionId, minecraftVersionId, instanceName)) {
            return false;
        }
        int major = detectBtaMajor(loader);
        major = Math.max(major, detectBtaMajor(baseVersionId));
        major = Math.max(major, detectBtaMajor(minecraftVersionId));
        major = Math.max(major, detectBtaMajor(instanceName));
        return major >= 8;
    }

    private static int detectBtaMajor(@Nullable String value) {
        if (value == null) return -1;
        Matcher matcher = BTA_MAJOR_PATTERN.matcher(value);
        if (!matcher.find()) return -1;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    @NonNull
    public static RendererInterface resolveRendererForLaunch(
            @NonNull Context context,
            @Nullable String versionId,
            @Nullable LauncherInstance instance,
            @Nullable RendererInterface fallback
    ) {
        if (isBtaLaunch(versionId, instance) && !isBta8OrNewer(versionId, instance)) {
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
        if (isBtaIdentity(loader, baseVersionId, minecraftVersionId, instanceName)
                && !isBta8OrNewer(loader, baseVersionId, minecraftVersionId, instanceName)) {
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

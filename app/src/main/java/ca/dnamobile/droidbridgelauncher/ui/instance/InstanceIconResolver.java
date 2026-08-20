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

package ca.dnamobile.droidbridgelauncher.ui.instance;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.R;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstance;

public final class InstanceIconResolver {
    private InstanceIconResolver() {
    }

    @DrawableRes
    public static int getDefaultIcon(@NonNull LauncherInstance instance) {
        return getDefaultIcon(
                instance.getLoader(),
                instance.getBaseVersionId(),
                instance.getMinecraftVersionId(),
                instance.getName()
        );
    }

    @DrawableRes
    public static int getDefaultIcon(
            @Nullable String loader,
            @Nullable String baseVersionId,
            @Nullable String minecraftVersionId,
            @Nullable String instanceName
    ) {
        String combined = normalize(loader)
                + " " + normalize(baseVersionId)
                + " " + normalize(minecraftVersionId)
                + " " + normalize(instanceName);

        // Better Than Adventure should use its own icon even though it inherits b1.7.3
        // and may otherwise fall back to the old grass block icon.
        if (isBetterThanAdventure(combined)) {
            return R.drawable.bta_icon;
        }

        // OptiFine should use its own icon whenever no custom instance icon is set.
        // Keep this before Forge/Fabric because OptiFine instances may also mention
        // another loader in the version id or instance name.
        if (isOptiFine(combined)) {
            return R.drawable.ic_optifine;
        }

        // Check NeoForge before Forge because "neoforge" contains "forge".
        if (combined.contains("neoforge") || combined.contains("neo forge")) {
            return R.drawable.ic_neoforge;
        }

        if (combined.contains("forge")) {
            return R.drawable.ic_forge;
        }

        if (combined.contains("fabric")) {
            return R.drawable.ic_fabric;
        }

        return R.drawable.ic_old_grass_block;
    }

    private static boolean isBetterThanAdventure(@NonNull String combined) {
        return combined.contains("betterthanadventure")
                || combined.contains("better than adventure")
                || combined.contains("better-than-adventure")
                || combined.contains("better_than_adventure")
                || combined.startsWith("bta ")
                || combined.contains(" bta ")
                || combined.contains("bta-")
                || combined.contains("bta_")
                || combined.contains("bta(")
                || combined.contains("bta (");
    }

    private static boolean isOptiFine(@NonNull String combined) {
        return combined.contains("optifine")
                || combined.contains("opti fine")
                || combined.contains("optifine-")
                || combined.contains("optifine_")
                || combined.contains("preview_optifine")
                || combined.contains("preview-optifine");
    }

    @NonNull
    private static String normalize(@Nullable String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT);
    }
}

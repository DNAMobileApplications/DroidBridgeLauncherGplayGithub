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

package ca.dnamobile.droidbridgelauncher.modmanager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.Locale;

/**
 * Shared content categories used by the browser, install manifest, and updater.
 *
 * Keep this enum backward-compatible with the existing mod/resource/shader manager
 * helpers. Several existing classes already call isLoaderSpecific(),
 * supportsDependencies(), and getTargetDirectory(File). The modpack patch also
 * adds MODPACKS, but modpacks are installed as full instances, not into one of
 * the normal per-instance content folders.
 */
public enum ModManagerContentType {
    MODS("mods", "mod", "mods", 6),
    MODPACKS("modpacks", "modpack", "modpacks", 4471),
    RESOURCEPACKS("resourcepacks", "resourcepack", "resourcepacks", 12),
    DATAPACKS("datapacks", "datapack", "datapacks", 6945),
    SHADERPACKS("shaderpacks", "shader", "shaderpacks", 6552);

    @NonNull
    private final String intentValue;
    @NonNull
    private final String modrinthProjectType;
    @NonNull
    private final String targetFolderName;
    private final int curseForgeClassId;

    ModManagerContentType(
            @NonNull String intentValue,
            @NonNull String modrinthProjectType,
            @NonNull String targetFolderName,
            int curseForgeClassId
    ) {
        this.intentValue = intentValue;
        this.modrinthProjectType = modrinthProjectType;
        this.targetFolderName = targetFolderName;
        this.curseForgeClassId = curseForgeClassId;
    }

    @NonNull
    public String getIntentValue() {
        return intentValue;
    }

    @NonNull
    public String getModrinthProjectType() {
        return modrinthProjectType;
    }

    @NonNull
    public String getTargetFolderName() {
        return targetFolderName;
    }

    public int getCurseForgeClassId() {
        return curseForgeClassId;
    }

    /**
     * Existing mod/resource/shader install code needs a folder to copy into.
     * MODPACKS are full-instance installs, so returning the game directory keeps
     * old generic call sites safe while the modpack installer handles real layout.
     */
    @NonNull
    public File getTargetDirectory(@NonNull File gameDirectory) {
        if (this == MODPACKS) return gameDirectory;
        return new File(gameDirectory, targetFolderName);
    }

    /**
     * Version-aware content target. Java Edition used the texturepacks folder through
     * 1.5.2; resource packs replaced texture packs starting with the 1.6 generation
     * (released as 1.6.1). Data packs are world-scoped and normally use an explicit
     * saves/<world>/datapacks target supplied by the UI.
     */
    @NonNull
    public File getTargetDirectory(@NonNull File gameDirectory, @Nullable String minecraftVersion) {
        if (this == RESOURCEPACKS) {
            return new File(gameDirectory, getResourcePackFolderName(minecraftVersion));
        }
        return getTargetDirectory(gameDirectory);
    }

    @NonNull
    public static String getResourcePackFolderName(@Nullable String minecraftVersion) {
        return usesLegacyTexturePacks(minecraftVersion) ? "texturepacks" : "resourcepacks";
    }

    public static boolean usesLegacyTexturePacks(@Nullable String minecraftVersion) {
        if (minecraftVersion == null) return false;
        String value = minecraftVersion.trim().toLowerCase(Locale.US);
        if (value.isEmpty()) return false;

        // Release-style versions: 1.5.2 and older use texturepacks. 1.6.x and newer
        // use resourcepacks. Modern year-style versions (26.x) are resource packs.
        if (value.startsWith("1.")) {
            int index = 2;
            int start = index;
            while (index < value.length() && Character.isDigit(value.charAt(index))) index++;
            if (index > start) {
                try {
                    int minor = Integer.parseInt(value.substring(start, index));
                    return minor <= 5;
                } catch (NumberFormatException ignored) {
                }
            }
        }

        // Resource packs landed in the 1.6 snapshot cycle at 13w24a. Preserve the
        // old texturepacks directory for earlier snapshots too.
        if (value.length() >= 5
                && Character.isDigit(value.charAt(0))
                && Character.isDigit(value.charAt(1))
                && value.charAt(2) == 'w'
                && Character.isDigit(value.charAt(3))
                && Character.isDigit(value.charAt(4))) {
            try {
                int year = Integer.parseInt(value.substring(0, 2));
                int week = Integer.parseInt(value.substring(3, 5));
                if (year < 13) return true;
                if (year > 13) return false;
                return week < 24;
            } catch (NumberFormatException ignored) {
            }
        }

        // Alpha, beta, and other pre-1.6 version identifiers predate resource packs.
        return value.startsWith("a")
                || value.startsWith("b")
                || value.startsWith("infdev")
                || value.startsWith("indev")
                || value.startsWith("classic");
    }

    /**
     * Only normal mods should trigger dependency resolution in the single-content
     * installers. Modpacks resolve their own file manifest separately.
     */
    public boolean supportsDependencies() {
        return this == MODS;
    }

    /**
     * Search filtering by loader should only be applied to content that actually
     * declares Fabric/Forge/NeoForge compatibility.
     */
    public boolean isLoaderSpecific() {
        return this == MODS || this == MODPACKS;
    }

    public boolean isInstallableIntoExistingInstance() {
        return this != MODPACKS;
    }

    @NonNull
    public static ModManagerContentType fromValue(@Nullable String rawValue) {
        if (rawValue == null) return MODS;
        String value = rawValue.trim().toLowerCase(Locale.US).replace('-', '_');
        if (value.isEmpty()) return MODS;

        for (ModManagerContentType type : values()) {
            if (type.intentValue.equalsIgnoreCase(value)
                    || type.name().equalsIgnoreCase(value)
                    || type.modrinthProjectType.equalsIgnoreCase(value)
                    || type.targetFolderName.equalsIgnoreCase(value)) {
                return type;
            }
        }

        if ("resource_packs".equals(value) || "resource-pack".equals(value)
                || "texturepack".equals(value) || "texturepacks".equals(value)
                || "texture_pack".equals(value) || "texture_packs".equals(value)) return RESOURCEPACKS;
        if ("data_pack".equals(value) || "data_packs".equals(value) || "data-pack".equals(value)) return DATAPACKS;
        if ("shader".equals(value) || "shaders".equals(value)) return SHADERPACKS;
        if ("pack".equals(value) || "packs".equals(value) || "modpack".equals(value)) return MODPACKS;
        return MODS;
    }
}

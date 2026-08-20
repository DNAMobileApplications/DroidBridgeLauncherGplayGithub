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

public final class Driver {
    public enum Type {
        DEFAULT_MESA,
        SYSTEM_VULKAN,
        TURNIP
    }

    @NonNull private final String name;
    @NonNull private final Type type;
    @Nullable private final String packageName;
    @Nullable private final File nativeLibraryDir;
    @Nullable private final File vulkanLibrary;

    public Driver(
            @NonNull String name,
            @NonNull Type type,
            @Nullable String packageName,
            @Nullable File nativeLibraryDir,
            @Nullable File vulkanLibrary
    ) {
        this.name = name;
        this.type = type;
        this.packageName = packageName;
        this.nativeLibraryDir = nativeLibraryDir;
        this.vulkanLibrary = vulkanLibrary;
    }

    @NonNull
    public String getName() {
        return name;
    }

    @NonNull
    public Type getType() {
        return type;
    }

    @Nullable
    public String getPackageName() {
        return packageName;
    }

    @Nullable
    public File getNativeLibraryDir() {
        return nativeLibraryDir;
    }

    @Nullable
    public File getVulkanLibrary() {
        return vulkanLibrary;
    }

    public boolean hasNativeLibraryDir() {
        return nativeLibraryDir != null && nativeLibraryDir.isDirectory();
    }

    @NonNull
    public String getDescription() {
        switch (type) {
            case SYSTEM_VULKAN:
                return "Uses Android's system Vulkan driver. Best for Mali/PowerVR/other non-Adreno devices, and for Adreno when Turnip is not desired.";
            case TURNIP:
                String path = vulkanLibrary != null ? vulkanLibrary.getAbsolutePath() : "not found";
                return "Uses a Turnip/Adreno Vulkan driver through VK_ICD_FILENAMES. Driver library: " + path;
            case DEFAULT_MESA:
            default:
                return "Uses DroidBridge's default Mesa/Vulkan setup without forcing a custom Turnip ICD.";
        }
    }

    @Override
    public String toString() {
        return name;
    }
}

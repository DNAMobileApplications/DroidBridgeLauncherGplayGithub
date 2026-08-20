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

package ca.dnamobile.droidbridgelauncher.launcher;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LaunchPlan {
    private final String versionId;
    private final String mainClass;
    private final File gameDirectory;
    private final File runtimeDirectory;
    private final File javaBinary;
    private final File lwjglNativeDirectory;
    private final String classPath;
    private final ArrayList<String> jvmArgs;
    private final ArrayList<String> gameArgs;
    private final boolean useSystemVulkanDriver;
    private final String vulkanCompatibilityMode;
    private final String effectiveMinecraftVersionId;

    LaunchPlan(
            @NonNull String versionId,
            @NonNull String mainClass,
            @NonNull File gameDirectory,
            @NonNull File runtimeDirectory,
            @NonNull File javaBinary,
            @NonNull File lwjglNativeDirectory,
            @NonNull String classPath,
            @NonNull List<String> jvmArgs,
            @NonNull List<String> gameArgs,
            boolean useSystemVulkanDriver,
            @NonNull String vulkanCompatibilityMode,
            @NonNull String effectiveMinecraftVersionId
    ) {
        this.versionId = versionId;
        this.mainClass = mainClass;
        this.gameDirectory = gameDirectory;
        this.runtimeDirectory = runtimeDirectory;
        this.javaBinary = javaBinary;
        this.lwjglNativeDirectory = lwjglNativeDirectory;
        this.classPath = classPath;
        this.jvmArgs = new ArrayList<>(jvmArgs);
        this.gameArgs = new ArrayList<>(gameArgs);
        this.useSystemVulkanDriver = useSystemVulkanDriver;
        this.vulkanCompatibilityMode = vulkanCompatibilityMode;
        this.effectiveMinecraftVersionId = effectiveMinecraftVersionId;
    }

    @NonNull
    public String getVersionId() {
        return versionId;
    }

    @NonNull
    public String getMainClass() {
        return mainClass;
    }

    @NonNull
    public File getGameDirectory() {
        return gameDirectory;
    }

    @NonNull
    public File getRuntimeDirectory() {
        return runtimeDirectory;
    }

    @NonNull
    public File getJavaBinary() {
        return javaBinary;
    }

    @NonNull
    public File getLwjglNativeDirectory() {
        return lwjglNativeDirectory;
    }

    @NonNull
    public String getClassPath() {
        return classPath;
    }

    @NonNull
    public List<String> getJvmArgs() {
        return Collections.unmodifiableList(jvmArgs);
    }

    @NonNull
    public List<String> getGameArgs() {
        return Collections.unmodifiableList(gameArgs);
    }

    public boolean isUseSystemVulkanDriver() {
        return useSystemVulkanDriver;
    }

    @NonNull
    public String getVulkanCompatibilityMode() {
        return vulkanCompatibilityMode;
    }

    @NonNull
    public String getEffectiveMinecraftVersionId() {
        return effectiveMinecraftVersionId;
    }

    @NonNull
    public LaunchPlan copyWithJvmArgs(@NonNull List<String> updatedJvmArgs) {
        return new LaunchPlan(
                versionId,
                mainClass,
                gameDirectory,
                runtimeDirectory,
                javaBinary,
                lwjglNativeDirectory,
                classPath,
                updatedJvmArgs,
                gameArgs,
                useSystemVulkanDriver,
                vulkanCompatibilityMode,
                effectiveMinecraftVersionId
        );
    }

    @NonNull
    LaunchPlan copyWithGameArgs(@NonNull List<String> updatedGameArgs) {
        return new LaunchPlan(
                versionId,
                mainClass,
                gameDirectory,
                runtimeDirectory,
                javaBinary,
                lwjglNativeDirectory,
                classPath,
                jvmArgs,
                updatedGameArgs,
                useSystemVulkanDriver,
                vulkanCompatibilityMode,
                effectiveMinecraftVersionId
        );
    }
}

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

import java.io.File;
import java.util.LinkedHashMap;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

/**
 * Built-in bundled Turnip/Freedreno Vulkan driver helper used by the
 * Mesa Unified Zink renderer path.
 *
 * Keep this separate from normal renderer plugins: this is a Vulkan ICD/driver
 * selection helper, not an OpenGL wrapper renderer.
 */
public final class MesaZinkTurnipDriver {
    private static final String TAG = "MesaZinkTurnipDriver";

    public static final String DRIVER_NAME = "Built-in Mesa Turnip / Freedreno";

    public static final String LIB_VULKAN_FREEDRENO = "libvulkan_freedreno.so";
    public static final String LIB_VULKAN_TURNIP = "libvulkan_turnip.so";
    public static final String LIB_VULKAN_ADRENO = "libvulkan_adreno.so";

    private static final String[] VULKAN_DRIVER_LIBRARIES = new String[]{
            LIB_VULKAN_FREEDRENO,
            LIB_VULKAN_TURNIP,
            LIB_VULKAN_ADRENO
    };

    private MesaZinkTurnipDriver() {
    }

    @Nullable
    public static Driver createDriverIfAvailable(@NonNull Context context) {
        File vulkan = findVulkanLibrary(context);
        if (vulkan == null || !vulkan.isFile()) return null;
        File nativeDir = vulkan.getParentFile();
        if (nativeDir == null || !nativeDir.isDirectory()) return null;
        return new Driver(DRIVER_NAME, Driver.Type.TURNIP, context.getPackageName(), nativeDir, vulkan);
    }

    @Nullable
    public static File findNativeLibraryDir(@NonNull Context context) {
        File vulkan = findVulkanLibrary(context);
        if (vulkan != null && vulkan.isFile()) return vulkan.getParentFile();

        try {
            String appNativeDir = context.getApplicationInfo() != null
                    ? context.getApplicationInfo().nativeLibraryDir
                    : null;
            if (appNativeDir != null && !appNativeDir.trim().isEmpty()) {
                File dir = new File(appNativeDir);
                if (dir.isDirectory()) return dir;
            }
        } catch (Throwable ignored) {
        }

        if (PathManager.DIR_NATIVE_LIB != null && !PathManager.DIR_NATIVE_LIB.trim().isEmpty()) {
            File dir = new File(PathManager.DIR_NATIVE_LIB);
            if (dir.isDirectory()) return dir;
        }

        return null;
    }

    @Nullable
    public static File findVulkanLibrary(@NonNull Context context) {
        File fromApp = findVulkanLibraryInApplicationNativeDir(context);
        if (fromApp != null) return fromApp;

        if (PathManager.DIR_NATIVE_LIB != null && !PathManager.DIR_NATIVE_LIB.trim().isEmpty()) {
            File fromPathManager = findVulkanLibraryInDir(new File(PathManager.DIR_NATIVE_LIB));
            if (fromPathManager != null) return fromPathManager;
        }

        return null;
    }

    @Nullable
    private static File findVulkanLibraryInApplicationNativeDir(@NonNull Context context) {
        try {
            String appNativeDir = context.getApplicationInfo() != null
                    ? context.getApplicationInfo().nativeLibraryDir
                    : null;
            if (appNativeDir == null || appNativeDir.trim().isEmpty()) return null;
            return findVulkanLibraryInDir(new File(appNativeDir));
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static File findVulkanLibraryInDir(@Nullable File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        for (String name : VULKAN_DRIVER_LIBRARIES) {
            File candidate = new File(dir, name);
            if (candidate.isFile()) return candidate;
        }
        return null;
    }

    public static boolean isAvailable(@NonNull Context context) {
        File vulkan = findVulkanLibrary(context);
        return vulkan != null && vulkan.isFile();
    }

    public static void applyEnvironment(
            @NonNull Context context,
            @NonNull LinkedHashMap<String, String> env
    ) {
        File vulkan = findVulkanLibrary(context);
        File nativeDir = vulkan != null ? vulkan.getParentFile() : findNativeLibraryDir(context);

        env.put("JAVA_LAUNCHER_VULKAN_DRIVER", DRIVER_NAME);
        env.put("DROIDBRIDGE_USE_SYSTEM_VULKAN", "0");
        env.put("DROIDBRIDGE_LOAD_TURNIP", "1");
        env.put("DROIDBRIDGE_LOAD_TURNIP", "1");
        env.put("DROIDBRIDGE_USE_CUSTOM_TURNIP", "1");

        if (nativeDir != null && nativeDir.isDirectory()) {
            env.put("DRIVER_PATH", nativeDir.getAbsolutePath());
            env.put("DROIDBRIDGE_TURNIP_DRIVER_DIR", nativeDir.getAbsolutePath());
        }

        if (vulkan != null && vulkan.isFile()) {
            env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", vulkan.getAbsolutePath());
            env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", vulkan.getAbsolutePath());
            env.put("DROIDBRIDGE_TURNIP_DRIVER_LIBRARY", vulkan.getAbsolutePath());
            Logging.i(TAG, "Using bundled Turnip/Freedreno Vulkan driver: " + vulkan.getAbsolutePath());
        } else {
            env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", "");
            env.put("DROIDBRIDGE_CUSTOM_VULKAN_DRIVER", "");
            env.put("DROIDBRIDGE_TURNIP_DRIVER_LIBRARY", "");
            Logging.i(TAG, "Bundled Turnip/Freedreno Vulkan driver was not found; DRIVER_PATH="
                    + (nativeDir != null ? nativeDir.getAbsolutePath() : "<missing>"));
        }

        /*
         * Keep stale external ICD JSON files from a previous selected driver from
         * winning over the bundled libvulkan_freedreno.so path. DroidBridge's native
         * bridge reads DRIVER_PATH / DROIDBRIDGE_CUSTOM_VULKAN_DRIVER for this path.
         */
        env.put("VK_ICD_FILENAMES", "");
        env.put("VK_DRIVER_FILES", "");
        env.put("VK_INSTANCE_LAYERS", "");
    }
}

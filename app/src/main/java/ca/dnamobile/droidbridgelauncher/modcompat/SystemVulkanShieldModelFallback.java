/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.modcompat;

import android.content.Context;
import android.content.res.AssetManager;

import androidx.annotation.NonNull;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.launcher.LaunchPlan;
import ca.dnamobile.droidbridgelauncher.logs.LauncherLogManager;

/**
 * Installs a launcher-owned resource pack that bypasses Minecraft 26.2/26.3's
 * special shield renderer on affected Qualcomm System Vulkan devices.
 *
 * The Vulkan bridge can see a valid shield atlas and valid draw submission,
 * but the special shield renderer still produces a black or missing item on
 * affected Adreno drivers. A regular data-driven item model uses the same path
 * as the other inventory and held items that already render correctly.
 */
public final class SystemVulkanShieldModelFallback {
    private static final String TAG = "VulkanShieldFallback";
    private static final String ASSET_ROOT = "vulkan_shield_fallback";
    private static final String PACK_FOLDER = "DroidBridge-Vulkan-Shield-Fallback";
    private static final String PACK_ID = "file/" + PACK_FOLDER;
    private static final String PACK_VERSION = "4";
    private static final String VERSION_FILE = ".droidbridge-pack-version";

    private SystemVulkanShieldModelFallback() {
    }

    public static void apply(
            @NonNull Context context,
            @NonNull LaunchPlan plan,
            boolean enabled
    ) {
        File resourcePacks = new File(plan.getGameDirectory(), "resourcepacks");
        File packDirectory = new File(resourcePacks, PACK_FOLDER);
        File options = new File(plan.getGameDirectory(), "options.txt");

        try {
            if (enabled) {
                installPackIfNeeded(context, packDirectory);
                boolean optionsChanged = setPackEnabled(options, true);
                append("DroidBridgeVulkanShieldFallback: enabled"
                        + " pack=" + packDirectory.getAbsolutePath()
                        + " optionsChanged=" + optionsChanged
                        + " renderer=vanilla-geometry-item-model"
                        + " specialShieldRenderer=bypassed texture=vanilla-entity-shield-base-nopattern-atlased-as-item-sprite"
                        + " bannerPatterns=not-rendered resourcePackFormats=88.0-999.0 minecraft="
                        + plan.getEffectiveMinecraftVersionId());
            } else {
                boolean optionsChanged = setPackEnabled(options, false);
                if (optionsChanged) {
                    append("DroidBridgeVulkanShieldFallback: disabled and removed from resourcePacks");
                }
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to apply Vulkan shield model fallback", throwable);
            append("DroidBridgeVulkanShieldFallback: failed " + throwable);
        }
    }

    private static void installPackIfNeeded(
            @NonNull Context context,
            @NonNull File packDirectory
    ) throws Exception {
        File marker = new File(packDirectory, VERSION_FILE);
        File packMetadata = new File(packDirectory, "pack.mcmeta");
        File itemDefinition = new File(packDirectory,
                "assets/minecraft/items/shield.json");
        File entityTextureSource = new File(packDirectory,
                "assets/droidbridge/textures/entity/shield_base_nopattern.png");
        File itemAtlasTexture = new File(packDirectory,
                "assets/droidbridge/textures/item/shield_base_nopattern.png");
        File normalModel = new File(packDirectory,
                "assets/droidbridge/models/item/vulkan_shield.json");
        File blockingModel = new File(packDirectory,
                "assets/droidbridge/models/item/vulkan_shield_blocking.json");

        if (marker.isFile()
                && packMetadata.isFile()
                && itemDefinition.isFile()
                && entityTextureSource.isFile()
                && itemAtlasTexture.isFile()
                && normalModel.isFile()
                && blockingModel.isFile()
                && PACK_VERSION.equals(readUtf8(marker).trim())) {
            return;
        }

        deleteRecursively(packDirectory);
        if (!packDirectory.mkdirs() && !packDirectory.isDirectory()) {
            throw new IllegalStateException("Unable to create " + packDirectory);
        }

        copyAssetTree(context.getAssets(), ASSET_ROOT, packDirectory);

        // The first fallback used a launcher-drawn flat item texture. It is no
        // longer referenced, and must not remain in an already-updated pack.
        // The actual vanilla entity texture is duplicated under textures/item so
        // Minecraft's regular item atlas can stitch it for the JSON model. JSON
        // item models cannot sample an arbitrary entity texture directly.
        File legacyPlaceholder = new File(packDirectory,
                "assets/droidbridge/textures/item/vulkan_shield.png");
        if (legacyPlaceholder.exists() && !legacyPlaceholder.delete()) {
            append("DroidBridgeVulkanShieldFallback: unable to remove legacy placeholder "
                    + legacyPlaceholder.getAbsolutePath());
        }

        writeUtf8Atomic(marker, PACK_VERSION + "\n");
    }

    private static void copyAssetTree(
            @NonNull AssetManager assets,
            @NonNull String assetPath,
            @NonNull File destination
    ) throws Exception {
        String[] children = assets.list(assetPath);
        if (children != null && children.length > 0) {
            if (!destination.exists() && !destination.mkdirs()) {
                throw new IllegalStateException("Unable to create " + destination);
            }
            for (String child : children) {
                copyAssetTree(
                        assets,
                        assetPath + "/" + child,
                        new File(destination, child)
                );
            }
            return;
        }

        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Unable to create " + parent);
        }
        try (InputStream input = assets.open(assetPath);
             FileOutputStream output = new FileOutputStream(destination, false)) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
    }

    private static boolean setPackEnabled(
            @NonNull File options,
            boolean enabled
    ) throws Exception {
        if (!options.isFile()) {
            if (!enabled) return false;
            File parent = options.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IllegalStateException("Unable to create " + parent);
            }
            writeUtf8Atomic(options,
                    "resourcePacks:[\"vanilla\",\"" + PACK_ID + "\"]\n");
            return true;
        }

        String original = readUtf8(options);
        String newline = original.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = original.split("\\r?\\n", -1);
        ArrayList<String> output = new ArrayList<>(lines.length + 1);
        boolean found = false;
        boolean changed = false;

        for (String line : lines) {
            int separator = line.indexOf(':');
            if (separator <= 0
                    || !"resourcePacks".equals(line.substring(0, separator).trim())) {
                output.add(line);
                continue;
            }

            found = true;
            String value = line.substring(separator + 1).trim();
            JSONArray current;
            try {
                current = new JSONArray(value);
            } catch (Throwable parseFailure) {
                append("DroidBridgeVulkanShieldFallback: resourcePacks value was not valid JSON; preserving it unchanged");
                output.add(line);
                continue;
            }

            JSONArray updated = new JSONArray();
            boolean hadPack = false;
            for (int i = 0; i < current.length(); i++) {
                Object entry = current.opt(i);
                if (PACK_ID.equals(entry)) {
                    hadPack = true;
                    continue;
                }
                updated.put(entry);
            }
            if (enabled) updated.put(PACK_ID);

            changed |= enabled ? !hadPack : hadPack;
            output.add("resourcePacks:" + updated.toString());
        }

        if (!found && enabled) {
            // Keep vanilla explicit because Minecraft-generated files normally do.
            output.add("resourcePacks:[\"vanilla\",\"" + PACK_ID + "\"]");
            changed = true;
        }

        if (!changed) return false;
        writeUtf8Atomic(options, joinLines(output, newline));
        return true;
    }

    @NonNull
    private static String joinLines(
            @NonNull List<String> lines,
            @NonNull String newline
    ) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) builder.append(newline);
            builder.append(lines.get(i));
        }
        return builder.toString();
    }

    @NonNull
    private static String readUtf8(@NonNull File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void writeUtf8Atomic(
            @NonNull File file,
            @NonNull String value
    ) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Unable to create " + parent);
        }
        File temporary = new File(parent != null ? parent : file.getAbsoluteFile().getParentFile(),
                file.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (file.exists() && !file.delete()) {
            throw new IllegalStateException("Unable to replace " + file);
        }
        if (!temporary.renameTo(file)) {
            throw new IllegalStateException("Unable to commit " + file);
        }
    }

    private static void deleteRecursively(@NonNull File file) {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        if (!file.delete() && file.exists()) {
            throw new IllegalStateException("Unable to delete " + file);
        }
    }

    private static void append(@NonNull String text) {
        Logging.i(TAG, text);
        LauncherLogManager.append(text);
    }
}

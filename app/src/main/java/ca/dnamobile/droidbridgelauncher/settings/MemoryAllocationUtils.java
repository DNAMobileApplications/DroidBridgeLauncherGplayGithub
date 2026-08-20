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

package ca.dnamobile.droidbridgelauncher.settings;

import android.app.ActivityManager;
import android.content.Context;

import androidx.annotation.NonNull;

/**
 * Shared RAM allocation helper for settings UI and launch args.
 *
 * The first-run default is based on the RAM Android currently reports as available,
 * then saved so it does not change on every launch. In locked mode, the slider
 * can use Android's currently available RAM. Unlocking raises the slider ceiling
 * to the device-reported installed/physical RAM.
 */
public final class MemoryAllocationUtils {
    public static final int RAM_STEP_MB = 256;
    public static final int RAM_DEFAULT_TARGET_MB = 2048;
    public static final int RAM_SAFETY_RESERVE_MB = 48;
    private static final int ABSOLUTE_MIN_MEMORY_MB = 512;

    private MemoryAllocationUtils() {
    }

    public static int getTotalMemoryMb(@NonNull Context context) {
        ActivityManager.MemoryInfo info = readMemoryInfo(context);
        long totalMb = info.totalMem / 1024L / 1024L;
        return (int) Math.max(ABSOLUTE_MIN_MEMORY_MB, Math.min(Integer.MAX_VALUE, totalMb));
    }

    public static int getAvailableMemoryMb(@NonNull Context context) {
        ActivityManager.MemoryInfo info = readMemoryInfo(context);
        long availableMb = info.availMem / 1024L / 1024L;
        return (int) Math.max(ABSOLUTE_MIN_MEMORY_MB, Math.min(Integer.MAX_VALUE, availableMb));
    }

    /**
     * Maximum currently exposed to the RAM sliders.
     *
     * Locked mode uses Android's currently available RAM as the ceiling. Unlocking
     * allows the slider/per-instance override to use the device-reported physical
     * RAM ceiling instead. The first-run default remains separate and is saved once.
     */
    public static int getMaxAllocatableMemoryMb(@NonNull Context context) {
        return LauncherPreferences.isRamUnlocked(context)
                ? getUnlockedMaxAllocatableMemoryMb(context)
                : getLockedMaxAllocatableMemoryMb(context);
    }

    public static int getLockedMaxAllocatableMemoryMb(@NonNull Context context) {
        int availableMb = getAvailableMemoryMb(context);
        int totalMb = getTotalMemoryMb(context);
        int max = Math.min(availableMb, totalMb);
        max = roundDownToStep(max, RAM_STEP_MB);
        return Math.max(ABSOLUTE_MIN_MEMORY_MB, max);
    }

    public static int getUnlockedMaxAllocatableMemoryMb(@NonNull Context context) {
        int totalMb = getTotalMemoryMb(context);
        int max = roundDownToStep(totalMb, RAM_STEP_MB);
        return Math.max(ABSOLUTE_MIN_MEMORY_MB, max);
    }

    public static int getMinimumMemoryMb(int maxMemoryMb) {
        return Math.min(ABSOLUTE_MIN_MEMORY_MB, Math.max(RAM_STEP_MB, maxMemoryMb));
    }

    /**
     * First-run default only. We save this value the first time it is resolved so
     * it does not keep changing every launch as Android's available RAM changes.
     */
    public static int getDefaultAllocatedMemoryMb(@NonNull Context context) {
        int availableMb = getAvailableMemoryMb(context);
        int requestedMb = availableMb > RAM_DEFAULT_TARGET_MB
                ? RAM_DEFAULT_TARGET_MB
                : Math.max(ABSOLUTE_MIN_MEMORY_MB, availableMb - RAM_SAFETY_RESERVE_MB);
        requestedMb = Math.max(ABSOLUTE_MIN_MEMORY_MB, roundDownToStep(requestedMb, RAM_STEP_MB));
        return clampToAllowedRam(context, requestedMb);
    }

    public static int resolveAllocatedMemoryMb(@NonNull Context context) {
        if (!LauncherPreferences.hasAllocatedMemoryMb(context)) {
            int initial = getDefaultAllocatedMemoryMb(context);
            LauncherPreferences.setAllocatedMemoryMb(context, initial);
            return initial;
        }

        int saved = LauncherPreferences.getAllocatedMemoryMb(context, getDefaultAllocatedMemoryMb(context));
        return clampToAllowedRam(context, saved);
    }

    public static int clampToAllowedRam(@NonNull Context context, int requestedMb) {
        int maxMemoryMb = getMaxAllocatableMemoryMb(context);
        int minMemoryMb = getMinimumMemoryMb(maxMemoryMb);
        int rounded = roundToNearestStep(requestedMb, RAM_STEP_MB);

        if (rounded < minMemoryMb) return minMemoryMb;
        if (rounded > maxMemoryMb) return maxMemoryMb;
        return rounded;
    }

    public static boolean isRamUnlocked(@NonNull Context context) {
        return LauncherPreferences.isRamUnlocked(context);
    }

    public static void setRamUnlocked(@NonNull Context context, boolean unlocked) {
        LauncherPreferences.setRamUnlocked(context, unlocked);
    }

    private static ActivityManager.MemoryInfo readMemoryInfo(@NonNull Context context) {
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        ActivityManager activityManager = (ActivityManager) context.getApplicationContext()
                .getSystemService(Context.ACTIVITY_SERVICE);

        if (activityManager != null) {
            activityManager.getMemoryInfo(info);
        }

        return info;
    }

    private static int roundDownToStep(int value, int step) {
        if (step <= 0) return value;
        return Math.max(0, (value / step) * step);
    }

    private static int roundToNearestStep(int value, int step) {
        if (step <= 0) return value;
        return Math.round(value / (float) step) * step;
    }
}

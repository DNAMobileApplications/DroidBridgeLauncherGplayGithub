/*
 * DroidBridge Launcher runtime bridge component.
 *
 * DroidBridge modifications:
 * Copyright (c) 2026 DNA Mobile Applications.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package ca.dnamobile.droidbridgelauncher.runtime;

import android.os.Build;

import java.util.Locale;

public final class Architecture {
    public static final int ARCH_ARM = 0;
    public static final int ARCH_ARM64 = 1;
    public static final int ARCH_X86 = 2;
    public static final int ARCH_X86_64 = 3;
    public static final int ARCH_UNKNOWN = -1;

    private Architecture() {
    }

    public static int getDeviceArchitecture() {
        String abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0]
                : Build.CPU_ABI;
        if (abi == null) return ARCH_UNKNOWN;
        switch (abi.toLowerCase(Locale.ROOT)) {
            case "armeabi-v7a":
            case "armeabi":
                return ARCH_ARM;
            case "arm64-v8a":
                return ARCH_ARM64;
            case "x86":
                return ARCH_X86;
            case "x86_64":
                return ARCH_X86_64;
            default:
                return ARCH_UNKNOWN;
        }
    }

    public static String archAsString(int architecture) {
        switch (architecture) {
            case ARCH_ARM:
                return "arm";
            case ARCH_ARM64:
                return "arm64";
            case ARCH_X86:
                return "x86";
            case ARCH_X86_64:
                return "x86_64";
            default:
                return "unknown";
        }
    }

    public static String androidAbiAsString(int architecture) {
        switch (architecture) {
            case ARCH_ARM:
                return "armeabi-v7a";
            case ARCH_ARM64:
                return "arm64-v8a";
            case ARCH_X86:
                return "x86";
            case ARCH_X86_64:
                return "x86_64";
            default:
                return "unknown";
        }
    }
}

/*
 * DroidBridge Launcher runtime bridge component.
 *
 * DroidBridge modifications:
 * Copyright (c) 2026 DNA Mobile Applications.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.oracle.dalvik;

import androidx.annotation.NonNull;

public final class VMLauncher {
    private VMLauncher() {}

    public static native int launchJVM(@NonNull String[] args);
}

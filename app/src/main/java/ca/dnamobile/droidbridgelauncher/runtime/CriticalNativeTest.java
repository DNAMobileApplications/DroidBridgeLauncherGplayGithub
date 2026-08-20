/*
 * DroidBridge Launcher runtime bridge component.
 *
 * DroidBridge modifications:
 * Copyright (c) 2026 DNA Mobile Applications.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */


package ca.dnamobile.droidbridgelauncher.runtime;

import dalvik.annotation.optimization.CriticalNative;

public class CriticalNativeTest {
    @CriticalNative
    public static native void testCriticalNative(int arg0, int arg1);
    public static void invokeTest() {
        testCriticalNative(0, 0);
    }
}

/*
 * DroidBridge Launcher runtime bridge component.
 *
 * DroidBridge modifications:
 * Copyright (c) 2026 DNA Mobile Applications.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */


package ca.dnamobile.droidbridgelauncher.runtime;

public interface GrabListener {
    void onGrabState(boolean isGrabbing);
}

/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * Clean-main rewrite pass: DroidBridge-owned implementation surface.
 */

package ca.dnamobile.droidbridgelauncher.dualscreen;

import androidx.annotation.Nullable;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-local dispatch point used by UI controls that need to request a
 * dual-screen swap without holding a direct Activity reference.
 */
public final class DualScreenSwapActionBus {
    public interface Listener {
        void onDualScreenSwapRequested();
    }

    private static final AtomicReference<Listener> LISTENER = new AtomicReference<>();

    private DualScreenSwapActionBus() {
    }

    public static void setListener(@Nullable Listener next) {
        LISTENER.set(next);
    }

    public static void clearListener(@Nullable Listener expected) {
        if (expected == null) {
            LISTENER.set(null);
            return;
        }
        LISTENER.compareAndSet(expected, null);
    }

    public static void requestSwap() {
        Listener current = LISTENER.get();
        if (current != null) {
            current.onDualScreenSwapRequested();
        }
    }
}

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

package ca.dnamobile.droidbridgelauncher.feature.unpack;

public abstract class AbstractUnpackTask implements Runnable {
    protected Listener listener;

    public interface Listener {
        default void onTaskStart() {
        }

        default void onTaskProgress(String message) {
        }

        default void onTaskEnd() {
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    protected final void dispatchProgress(String message) {
        Listener current = listener;
        if (current != null) {
            current.onTaskProgress(message);
        }
    }

    public abstract boolean isNeedUnpack();
}

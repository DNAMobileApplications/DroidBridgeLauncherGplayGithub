/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */
package ca.dnamobile.droidbridgelauncher.skin;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;

/**
 * Public-source placeholder. The local skin authentication service implementation
 * is intentionally not included in this source snapshot.
 */
public final class OfflineYggdrasilServer {
    public OfflineYggdrasilServer() {
    }

    public OfflineYggdrasilServer(@NonNull String serverName,
                                  @NonNull String implementationName,
                                  @NonNull String implementationVersion) {
    }

    public OfflineYggdrasilServer(int requestedPort,
                                  @NonNull String serverName,
                                  @NonNull String implementationName,
                                  @NonNull String implementationVersion) {
    }

    public synchronized void start() throws IOException {
    }

    public synchronized void stop() {
    }

    public int getPort() {
        return -1;
    }

    public void addCharacter(@NonNull String uuid,
                             @NonNull String name,
                             @Nullable File skinFile,
                             @NonNull SkinModelType model) {
    }
}

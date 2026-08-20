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

package ca.dnamobile.droidbridgelauncher.data.model;

public class MinecraftVersion {
    private final String id;
    private final String type;
    private final String releaseTime;
    private final String metadataUrl;

    public MinecraftVersion(String id, String type, String releaseTime, String metadataUrl) {
        this.id = id;
        this.type = type;
        this.releaseTime = releaseTime;
        this.metadataUrl = metadataUrl;
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getReleaseTime() {
        return releaseTime;
    }

    public String getMetadataUrl() {
        return metadataUrl;
    }
}

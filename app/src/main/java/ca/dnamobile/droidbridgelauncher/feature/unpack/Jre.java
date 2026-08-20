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

import ca.dnamobile.droidbridgelauncher.R;

public enum Jre {
    JRE_8("components/jre-8", "Internal-8", R.string.splash_screen_jre_8),
    JRE_17("components/jre-17", "Internal-17", R.string.splash_screen_jre_17),
    JRE_21("components/jre-21", "Internal-21", R.string.splash_screen_jre_21),
    JRE_25("components/jre-25", "Internal-25", R.string.splash_screen_jre_25);

    public final String jrePath;
    public final String jreName;
    public final int summary;

    Jre(String jrePath, String jreName, int summary) {
        this.jrePath = jrePath;
        this.jreName = jreName;
        this.summary = summary;
    }
}

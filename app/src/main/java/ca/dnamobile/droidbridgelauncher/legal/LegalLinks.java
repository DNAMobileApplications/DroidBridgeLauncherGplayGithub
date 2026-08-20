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

package ca.dnamobile.droidbridgelauncher.legal;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class LegalLinks {
    public static final String MINECRAFT_EULA_URL = "https://www.minecraft.net/en-us/eula";

    /** Fill these once the public pages exist. Empty values keep the settings buttons disabled. */
    public static final String DROIDBRIDGE_PRIVACY_POLICY_URL = "https://dnamobilegaming.com/privacy";
    public static final String DROIDBRIDGE_TERMS_URL = "https://www.dnamobilegaming.com/terms";
    public static final String DROIDBRIDGE_LICENSING = "https://www.dnamobilegaming.com/license";
    private LegalLinks() {
    }

    public static boolean open(@NonNull Context context, @Nullable String url) {
        if (url == null || url.trim().isEmpty()) return false;

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(context, url, Toast.LENGTH_LONG).show();
            return false;
        } catch (Throwable throwable) {
            Toast.makeText(context, "Unable to open link.", Toast.LENGTH_LONG).show();
            return false;
        }
    }
}

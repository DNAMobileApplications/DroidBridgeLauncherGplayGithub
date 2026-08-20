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

package ca.dnamobile.droidbridgelauncher.skin;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import ca.dnamobile.droidbridgelauncher.data.AccountStore;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

/**
 * Local cache for Microsoft/Minecraft skin PNGs.
 *
 * This is primarily for launcher previews. Official Microsoft skins still use the real
 * Minecraft session when the account has a valid Minecraft token/name/UUID.
 */
public final class AccountSkinCache {
    private static final String TAG = "AccountSkinCache";
    private static final String DIR_NAME = "account_skins";

    private AccountSkinCache() {
    }

    @NonNull
    public static File getSkinFile(@NonNull Context context, @NonNull String uuid) {
        File dir = new File(context.getApplicationContext().getFilesDir(), DIR_NAME);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return new File(dir, uuid.replace("-", "").toLowerCase() + ".png");
    }

    @Nullable
    public static File getCachedSkinFileIfPresent(@NonNull Context context, @Nullable AccountStore.Account account) {
        if (account == null || isBlank(account.minecraftUuid)) return null;
        File file = getSkinFile(context, account.minecraftUuid);
        return file.isFile() ? file : null;
    }

    public static void cacheMicrosoftSkinAsync(@NonNull Context context, @Nullable AccountStore.Account account) {
        if (account == null || isBlank(account.minecraftUuid) || isBlank(account.skinUrl)) return;
        Context appContext = context.getApplicationContext();
        new Thread(() -> cacheMicrosoftSkin(appContext, account), "DroidBridgeMicrosoftSkinCache").start();
    }

    public static boolean cacheMicrosoftSkin(@NonNull Context context, @Nullable AccountStore.Account account) {
        if (account == null || isBlank(account.minecraftUuid) || isBlank(account.skinUrl)) return false;

        File destination = getSkinFile(context, account.minecraftUuid);
        File temp = new File(destination.getParentFile(), destination.getName() + ".tmp");
        HttpURLConnection connection = null;

        try {
            String skinUrl = normalizeSkinUrl(account.skinUrl);
            connection = (HttpURLConnection) new URL(skinUrl).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setUseCaches(true);
            connection.setRequestProperty("User-Agent", "DroidBridge");

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                Logging.i(TAG, "Skin download failed with HTTP " + code + " for " + account.minecraftName);
                return false;
            }

            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(temp)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }

            if (!CustomSkinStore.isSkinValid(temp)) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                Logging.i(TAG, "Downloaded Microsoft skin was not a valid 64x64/64x32 skin.");
                return false;
            }

            if (destination.exists()) {
                //noinspection ResultOfMethodCallIgnored
                destination.delete();
            }
            if (!temp.renameTo(destination)) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                return false;
            }

            Logging.i(TAG, "Cached Microsoft skin for " + account.minecraftName + " at " + destination.getAbsolutePath());
            return true;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to cache Microsoft skin", throwable);
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @NonNull
    public static String normalizeSkinUrl(@Nullable String url) {
        if (url == null) return "";
        String value = url.trim();
        if (value.startsWith("http://textures.minecraft.net/")) {
            return "https://textures.minecraft.net/" + value.substring("http://textures.minecraft.net/".length());
        }
        return value;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }
}

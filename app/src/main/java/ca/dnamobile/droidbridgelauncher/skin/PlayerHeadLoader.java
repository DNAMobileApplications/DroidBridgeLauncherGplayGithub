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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ca.dnamobile.droidbridgelauncher.R;
import ca.dnamobile.droidbridgelauncher.data.AccountStore;

/** Loads the active profile head for the settings UI. */
public final class PlayerHeadLoader {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private PlayerHeadLoader() {
    }

    public static void loadInto(@NonNull Context context,
                                @NonNull ImageView imageView,
                                @Nullable AccountStore.Account account,
                                @Nullable CustomSkinStore legacyCustomSkinStore) {
        imageView.setImageResource(R.drawable.ic_player_head_placeholder);

        final Context appContext = context.getApplicationContext();
        final File activeOfflineSkin = resolveActiveOfflineSkin(account);
        final File legacySkin = activeOfflineSkin == null && legacyCustomSkinStore != null && legacyCustomSkinStore.isEnabled()
                ? legacyCustomSkinStore.getSkinFile()
                : null;
        final String skinUrl = account != null ? AccountSkinCache.normalizeSkinUrl(account.skinUrl) : "";
        final File cachedMicrosoftSkin = AccountSkinCache.getCachedSkinFileIfPresent(appContext, account);

        EXECUTOR.execute(() -> {
            Bitmap head = null;

            if (activeOfflineSkin != null && activeOfflineSkin.isFile()) {
                head = loadHeadFromSkinFile(activeOfflineSkin);
            }

            if (head == null && legacySkin != null && legacySkin.isFile()) {
                head = loadHeadFromSkinFile(legacySkin);
            }

            if (head == null && cachedMicrosoftSkin != null && cachedMicrosoftSkin.isFile()) {
                head = loadHeadFromSkinFile(cachedMicrosoftSkin);
            }

            if (head == null && skinUrl.length() > 0) {
                File cachedSkin = getCachedSkinFile(appContext, skinUrl);
                if (!cachedSkin.exists()) {
                    downloadSkinQuietly(skinUrl, cachedSkin);
                }
                if (cachedSkin.exists()) {
                    head = loadHeadFromSkinFile(cachedSkin);
                }
            }

            final Bitmap finalHead = head;
            imageView.post(() -> {
                if (finalHead != null) {
                    imageView.setImageBitmap(finalHead);
                } else {
                    imageView.setImageResource(R.drawable.ic_player_head_placeholder);
                }
            });
        });
    }

    @Nullable
    private static File resolveActiveOfflineSkin(@Nullable AccountStore.Account account) {
        if (account == null || !account.isOfflineAccount() || account.offlineSkinPath == null || account.offlineSkinPath.trim().isEmpty()) {
            return null;
        }
        File file = new File(account.offlineSkinPath);
        return file.isFile() ? file : null;
    }

    @Nullable
    public static Bitmap loadHeadFromSkinFile(@NonNull File skinFile) {
        Bitmap skin = BitmapFactory.decodeFile(skinFile.getAbsolutePath());
        if (skin == null || skin.getWidth() < 64 || skin.getHeight() < 32) return null;

        Bitmap head = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(head);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

        Bitmap face = Bitmap.createBitmap(skin, 8, 8, 8, 8);
        canvas.drawBitmap(face, 0, 0, paint);

        if (skin.getWidth() >= 48 && skin.getHeight() >= 16) {
            Bitmap overlay = Bitmap.createBitmap(skin, 40, 8, 8, 8);
            canvas.drawBitmap(overlay, 0, 0, paint);
            overlay.recycle();
        }
        face.recycle();

        Bitmap scaled = Bitmap.createScaledBitmap(head, 128, 128, false);
        head.recycle();
        return scaled;
    }

    @NonNull
    private static File getCachedSkinFile(@NonNull Context context, @NonNull String url) {
        File dir = new File(context.getCacheDir(), "player_skins");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return new File(dir, sha1(url) + ".png");
    }

    private static void downloadSkinQuietly(@NonNull String url, @NonNull File destination) {
        HttpURLConnection connection = null;
        try {
            String normalizedUrl = AccountSkinCache.normalizeSkinUrl(url);
            connection = (HttpURLConnection) new URL(normalizedUrl).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setUseCaches(true);
            connection.setRequestProperty("User-Agent", "DroidBridge");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) return;

            File tmp = new File(destination.getParentFile(), destination.getName() + ".tmp");
            try (InputStream inputStream = connection.getInputStream();
                 FileOutputStream outputStream = new FileOutputStream(tmp)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, read);
                }
            }

            if (CustomSkinStore.isSkinValid(tmp)) {
                if (destination.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    destination.delete();
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(destination);
            } else {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Throwable ignored) {
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @NonNull
    private static String sha1(@NonNull String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes("UTF-8"));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) builder.append(String.format("%02x", b));
            return builder.toString();
        } catch (Throwable ignored) {
            return String.valueOf(value.hashCode());
        }
    }
}

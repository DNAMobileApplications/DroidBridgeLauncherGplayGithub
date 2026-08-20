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

package ca.dnamobile.droidbridgelauncher.modmanager;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public final class NetworkImageLoader {
    private NetworkImageLoader() {
    }

    public static void load(@NonNull ImageView imageView, @Nullable String url, @DrawableRes int fallbackRes) {
        imageView.setTag(url == null ? "" : url);
        imageView.setImageResource(fallbackRes);
        if (url == null || url.trim().isEmpty()) return;

        Thread thread = new Thread(() -> {
            Bitmap bitmap = null;
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("User-Agent", "DroidBridge/1.0 (Android Minecraft Launcher)");
                try (InputStream input = connection.getInputStream()) {
                    bitmap = BitmapFactory.decodeStream(input);
                } finally {
                    connection.disconnect();
                }
            } catch (Throwable ignored) {
            }

            Bitmap finalBitmap = bitmap;
            imageView.post(() -> {
                Object tag = imageView.getTag();
                if (tag instanceof String && tag.equals(url)) {
                    if (finalBitmap != null) imageView.setImageBitmap(finalBitmap);
                    else imageView.setImageResource(fallbackRes);
                }
            });
        }, "ModrinthIconLoader");
        thread.setDaemon(true);
        thread.start();
    }
}

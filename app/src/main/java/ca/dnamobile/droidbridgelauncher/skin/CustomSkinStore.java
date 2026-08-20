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
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Stores one launcher-wide custom offline skin. */
public final class CustomSkinStore {
    private static final String PREFS = "java_launcher_custom_skin";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_MODEL = "model";
    private static final String SKIN_DIR = "custom_skins";
    private static final String SKIN_FILE = "selected_skin.png";

    private final Context context;
    private final SharedPreferences preferences;

    public CustomSkinStore(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @NonNull
    public SkinModelType importSkin(@NonNull Uri sourceUri) throws IOException {
        File dir = getSkinDirectory();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create skin folder: " + dir.getAbsolutePath());
        }

        File temp = new File(dir, SKIN_FILE + ".tmp");
        File destination = new File(dir, SKIN_FILE);
        copyUriToFile(sourceUri, temp);

        if (!isSkinValid(temp)) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IOException("Invalid skin. Use a 64x64 or 64x32 PNG skin.");
        }

        SkinModelType model = getSkinModel(temp);
        if (destination.exists() && !destination.delete()) {
            throw new IOException("Could not replace old skin.");
        }
        if (!temp.renameTo(destination)) {
            copyFile(temp, destination);
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }

        preferences.edit()
                .putBoolean(KEY_ENABLED, true)
                .putString(KEY_MODEL, model.id)
                .apply();
        return model;
    }

    public void clear() {
        preferences.edit().clear().apply();
        File file = getSkinFile();
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    public boolean isEnabled() {
        return preferences.getBoolean(KEY_ENABLED, false) && getSkinFile().exists();
    }

    @NonNull
    public File getSkinFile() {
        return new File(getSkinDirectory(), SKIN_FILE);
    }

    @NonNull
    public SkinModelType getSkinModel() {
        return SkinModelType.fromId(preferences.getString(KEY_MODEL, SkinModelType.CLASSIC.id));
    }

    @NonNull
    public OfflineSkinProfile buildOfflineProfile(@NonNull String username) {
        boolean enabled = isEnabled();
        SkinModelType model = enabled ? getSkinModel() : SkinModelType.NONE;
        return new OfflineSkinProfile(
                getOfflineUuidWithSkinModel(username, model),
                enabled ? getSkinFile() : null,
                model,
                enabled
        );
    }

    @NonNull
    private File getSkinDirectory() {
        return new File(context.getFilesDir(), SKIN_DIR);
    }

    private void copyUriToFile(@NonNull Uri sourceUri, @NonNull File destination) throws IOException {
        try (InputStream inputStream = context.getContentResolver().openInputStream(sourceUri)) {
            if (inputStream == null) throw new IOException("Could not open selected skin.");
            try (FileOutputStream outputStream = new FileOutputStream(destination)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, read);
                }
            }
        }
    }

    private static void copyFile(@NonNull File source, @NonNull File destination) throws IOException {
        try (FileInputStream inputStream = new FileInputStream(source);
             FileOutputStream outputStream = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
        }
    }

    public static boolean isSkinValid(@NonNull File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        return (options.outWidth == 64 && options.outHeight == 64)
                || (options.outWidth == 64 && options.outHeight == 32);
    }

    @NonNull
    public static SkinModelType getSkinModel(@NonNull File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) return SkinModelType.CLASSIC;
        if (bitmap.getHeight() == 32) return SkinModelType.CLASSIC;
        return detectSkinModel(bitmap);
    }

    @NonNull
    private static SkinModelType detectSkinModel(@NonNull Bitmap bitmap) {
        if (bitmap.getWidth() < 64 || bitmap.getHeight() < 64) return SkinModelType.CLASSIC;
        return isTransparent(bitmap, 54, 20, 2, 12) ? SkinModelType.SLIM : SkinModelType.CLASSIC;
    }

    private static boolean isTransparent(@NonNull Bitmap bitmap, int x, int y, int width, int height) {
        for (int i = x; i < x + width; i++) {
            for (int j = y; j < y + height; j++) {
                int alpha = (bitmap.getPixel(i, j) >>> 24) & 0xFF;
                if (alpha != 0) return false;
            }
        }
        return true;
    }

    @NonNull
    public static String getOfflineUuidWithSkinModel(@NonNull String username, @NonNull SkinModelType modelType) {
        UUID originalUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        if (modelType == SkinModelType.NONE) return originalUuid.toString();

        boolean isOriginalSlim = (originalUuid.hashCode() & 1) == 1;
        boolean isTargetSlim = modelType == SkinModelType.SLIM;
        if (isOriginalSlim == isTargetSlim) return originalUuid.toString();

        String uuidString = originalUuid.toString();
        char lastChar = uuidString.charAt(uuidString.length() - 1);
        int lastInt = Character.digit(lastChar, 16);
        if (lastInt == -1) return originalUuid.toString();

        int newLastInt = lastInt ^ 1;
        char newLastChar = Integer.toHexString(newLastInt).charAt(0);
        return uuidString.substring(0, uuidString.length() - 1) + newLastChar;
    }
}

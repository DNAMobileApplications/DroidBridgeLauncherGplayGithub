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

import android.content.Context;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.utils.CopyDefaultFromAssets;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

public class UnpackSingleFilesTask implements Runnable {
    private final Context context;

    public UnpackSingleFilesTask(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void run() {
        // The stripped DroidBridge base currently has no loose one-off files in assets. Keep this
        // task as a safe place to prepare directories and default files before MainActivity opens.
        try {
            PathManager.initContextConstants(context);
            try {
                CopyDefaultFromAssets.copyFromAssets(context);
            } catch (IOException ignored) {
                // The stripped base project may not have assets/default.json yet.
                // Keep a tiny fallback control file so FILE_CTRLDEF_FILE still exists.
                ensureFile(new File(PathManager.FILE_CTRLDEF_FILE), "{}\n");
            }
        } catch (Throwable throwable) {
            Logging.e("UnpackSingleFiles", "Failed to prepare single files", throwable);
        }
    }

    private static void ensureFile(@NonNull File file, @NonNull String defaultContent) throws IOException {
        if (file.exists()) return;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create directory: " + parent.getAbsolutePath());
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(defaultContent.getBytes(StandardCharsets.UTF_8));
        }
    }
}

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
import android.content.res.AssetManager;

import androidx.annotation.NonNull;

import ca.dnamobile.droidbridgelauncher.runtime.Tools;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

public class UnpackComponentsTask extends AbstractUnpackTask {
    private static final Object INSTALL_LOCK = new Object();

    private final Context context;
    private final Components component;
    private AssetManager assetManager;
    private String rootDir;
    private File versionFile;
    private boolean checkFailed;
    private Throwable checkFailure;

    public UnpackComponentsTask(@NonNull Context context, @NonNull Components component) {
        this.context = context.getApplicationContext();
        this.component = component;

        initializeBundledState();
    }

    private void initializeBundledState() {
        checkFailed = false;
        checkFailure = null;
        try {
            assetManager = context.getAssets();
            rootDir = resolveRootDir(component);
            File componentDir = new File(rootDir, component.component);
            versionFile = new File(componentDir, "version");

            // Validate that this component actually exists in assets before adding it to the install list.
            try (InputStream ignored = assetManager.open("components/" + component.assetComponent + "/version")) {
                // Just checking the file exists.
            }
        } catch (Throwable throwable) {
            checkFailed = true;
            checkFailure = throwable;
            Logging.e("UnpackComponentsTask", "Component check failed for " + component.component, throwable);
        }
    }

    public boolean isCheckFailed() {
        return checkFailed;
    }

    @Override
    public boolean isNeedUnpack() {
        if (checkFailed) initializeBundledState();
        if (checkFailed) return true;

        if (!versionFile.isFile()) {
            Logging.i("UnpackComponents", component.component + ": pack missing, installing...");
            return true;
        }

        try (InputStream assetVersion = assetManager.open("components/" + component.assetComponent + "/version");
             InputStream installedVersion = new FileInputStream(versionFile)) {
            String bundled = Tools.read(assetVersion).trim();
            String installed = Tools.read(installedVersion).trim();
            if (!bundled.equals(installed)) {
                Logging.i("UnpackComponents", component.component
                        + ": bundled version " + bundled
                        + " differs from installed version " + installed
                        + ", installing update...");
                return true;
            }
        } catch (Throwable throwable) {
            Logging.e("UnpackComponents", "Failed to compare component version for "
                    + component.component, throwable);
            return true;
        }

        // Installation is verified byte-for-byte in the staging directory before
        // activation. Do not tie components to APK install timestamps or scan all
        // files again on every launcher start. Manual Re-install Components remains
        // available for user-requested repair of a damaged installation.
        Logging.i("UnpackComponents", component.component + ": installed version is current.");
        return false;
    }

    @Override
    public void run() {
        if (listener != null) listener.onTaskStart();
        try {
            if (checkFailed) initializeBundledState();
            if (checkFailed) {
                throw new IOException("Bundled component is unavailable: " + component.displayName, checkFailure);
            }

            synchronized (INSTALL_LOCK) {
                File targetDir = new File(rootDir, component.component);
                File stagingDir = new File(rootDir, component.component + ".installing");
                File backupDir = new File(rootDir, component.component + ".backup");

                // Recover an interrupted activation before starting another
                // transaction. Never discard the only known-good copy.
                if (!targetDir.exists() && backupDir.exists() && !backupDir.renameTo(targetDir)) {
                    throw new IOException("Unable to recover previous component backup: "
                            + backupDir.getAbsolutePath());
                }
                PathManager.deleteRecursivelyChecked(stagingDir);
                if (targetDir.exists()) {
                    PathManager.deleteRecursivelyChecked(backupDir);
                }
                if (!stagingDir.mkdirs()) {
                    throw new IOException("Unable to create staging directory: " + stagingDir.getAbsolutePath());
                }

                try {
                    copyAssetDirectoryRecursively(
                            "components/" + component.assetComponent,
                            stagingDir.getAbsolutePath()
                    );
                    String failed = findFirstMismatchedAssetExact(
                            "components/" + component.assetComponent,
                            stagingDir
                    );
                    if (failed != null) {
                        throw new IOException("Staged component verification failed: " + failed);
                    }

                    if (targetDir.exists() && !targetDir.renameTo(backupDir)) {
                        throw new IOException("Unable to move existing component aside: " + targetDir.getAbsolutePath());
                    }
                    if (!stagingDir.renameTo(targetDir)) {
                        if (backupDir.exists()) {
                            //noinspection ResultOfMethodCallIgnored
                            backupDir.renameTo(targetDir);
                        }
                        throw new IOException("Unable to activate staged component: " + targetDir.getAbsolutePath());
                    }
                    try {
                        PathManager.deleteRecursivelyChecked(backupDir);
                    } catch (IOException cleanupFailure) {
                        // The new component is already active and was verified
                        // byte-for-byte in staging. Keep it usable; the next
                        // reinstall will retry stale-backup cleanup.
                        Logging.e("UnpackComponents", "Installed component but could not remove backup "
                                + backupDir.getAbsolutePath(), cleanupFailure);
                    }
                } catch (Throwable throwable) {
                    try {
                        PathManager.deleteRecursivelyChecked(stagingDir);
                    } catch (IOException cleanupFailure) {
                        throwable.addSuppressed(cleanupFailure);
                    }
                    if (!targetDir.exists() && backupDir.exists() && !backupDir.renameTo(targetDir)) {
                        IOException restoreFailure = new IOException(
                                "Unable to restore previous component after install failure: "
                                        + backupDir.getAbsolutePath());
                        restoreFailure.addSuppressed(throwable);
                        throw restoreFailure;
                    }
                    throw throwable;
                }
            }
        } catch (Throwable throwable) {
            Logging.e("UnpackComponents", "Failed to unpack " + component.component, throwable);
            throw new RuntimeException("Failed to unpack " + component.displayName, throwable);
        } finally {
            if (listener != null) listener.onTaskEnd();
        }
    }

    private static String resolveRootDir(@NonNull Components component) {
        switch (component) {
            case COMPONENTS:
            case WEBRTC_BRIDGE:
                return PathManager.DIR_DATA;

            case LWJGL3:
            case LWJGL333_BTA:
            case LWJGL341:
            case OTHER_LOGIN:
            case CACIOCAVALLO:
            case CACIOCAVALLO17:
                return PathManager.DIR_FILE.getAbsolutePath();

            default:
                return component.privateDirectory
                        ? PathManager.DIR_FILE.getAbsolutePath()
                        : PathManager.DIR_GAME_HOME;
        }
    }

    private String findFirstMismatchedAssetExact(
            @NonNull String assetPath,
            @NonNull File outputPath
    ) throws IOException {
        String[] entries = assetManager.list(assetPath);
        if (entries == null) {
            return assetPath + " could not be listed";
        }
        if (entries.length == 0) {
            if (!outputPath.isFile()) return outputPath.getAbsolutePath() + " missing";
            try (InputStream assetInput = assetManager.open(assetPath);
                 InputStream fileInput = new FileInputStream(outputPath)) {
                byte[] assetBuffer = new byte[64 * 1024];
                byte[] fileBuffer = new byte[64 * 1024];
                while (true) {
                    int assetRead = assetInput.read(assetBuffer);
                    int fileRead = fileInput.read(fileBuffer);
                    if (assetRead != fileRead) return outputPath.getAbsolutePath() + " length mismatch";
                    if (assetRead < 0) return null;
                    if (!Arrays.equals(
                            Arrays.copyOf(assetBuffer, assetRead),
                            Arrays.copyOf(fileBuffer, fileRead))) {
                        return outputPath.getAbsolutePath() + " content mismatch";
                    }
                }
            }
        }
        if (!outputPath.isDirectory()) return outputPath.getAbsolutePath() + " directory missing";
        for (String entry : entries) {
            String failed = findFirstMismatchedAssetExact(
                    assetPath + "/" + entry,
                    new File(outputPath, entry)
            );
            if (failed != null) return failed;
        }
        return null;
    }


    private void copyAssetDirectoryRecursively(@NonNull String assetPath, @NonNull String outputPath) throws IOException {
        String[] entries = assetManager.list(assetPath);
        if (entries == null) return;

        if (entries.length == 0) {
            File outFile = new File(outputPath);
            File parent = outFile.getParentFile();
            if (parent == null) return;
            Tools.copyAssetFile(context, assetPath, parent.getAbsolutePath(), outFile.getName(), true);
            return;
        }

        File outputDir = new File(outputPath);
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("Unable to create directory: " + outputDir.getAbsolutePath());
        }

        for (String entry : entries) {
            String childAssetPath = assetPath + "/" + entry;
            String childOutputPath = outputPath + File.separator + entry;
            copyAssetDirectoryRecursively(childAssetPath, childOutputPath);
        }
    }

}

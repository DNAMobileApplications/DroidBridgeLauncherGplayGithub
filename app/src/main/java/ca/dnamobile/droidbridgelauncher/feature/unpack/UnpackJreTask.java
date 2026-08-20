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

import ca.dnamobile.droidbridgelauncher.runtime.Architecture;
import ca.dnamobile.droidbridgelauncher.runtime.Tools;
import ca.dnamobile.droidbridgelauncher.runtime.multirt.MultiRTUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.launcher.RuntimeCompat;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

/**
 * DroidBridge-compatible internal runtime unpack task.
 *
 * Important for Android 14-16:
 * - Do not call MultiRTUtils.getRuntimeHome() before installing. It throws when
 *   the runtime is missing/broken, which prevents a fresh install from starting.
 * - Do not invent a custom "version" marker. MultiRTUtils writes/reads
 *   the marker through readInternalRuntimeVersion().
 * - Do not require Java 8 bin/java. Game launches use VMLauncher/JLI. Java 8 is
 *   valid when rt.jar + libjvm.so + droidbridge_version are present.
 */
public class UnpackJreTask extends AbstractUnpackTask {
    private static final String TAG = "UnpackJreTask";

    private final Context context;
    private final Jre jre;

    private AssetManager assetManager;
    private String launcherRuntimeVersion;
    private boolean checkFailed;
    private Throwable checkFailure;

    public UnpackJreTask(@NonNull Context context, @NonNull Jre jre) {
        this.context = context.getApplicationContext();
        this.jre = jre;

        initializeBundledState();
    }

    private void initializeBundledState() {
        checkFailed = false;
        checkFailure = null;
        launcherRuntimeVersion = null;
        try {
            assetManager = context.getAssets();
            try (InputStream versionInput = assetManager.open(jre.jrePath + "/version")) {
                launcherRuntimeVersion = Tools.read(versionInput).trim();
            }
            if ("Internal-8".equals(jre.jreName)) {
                Logging.i(TAG, "Runtime patch active: " + RuntimeCompat.PATCH_ID);
            }
        } catch (Throwable throwable) {
            checkFailed = true;
            checkFailure = throwable;
            Logging.e(TAG, "Failed to read bundled runtime version for " + jre.jreName, throwable);
        }
    }

    public boolean isCheckFailed() {
        return checkFailed;
    }

    @Override
    public boolean isNeedUnpack() {
        if (checkFailed) initializeBundledState();
        if (checkFailed) return true;

        try {
            String installedRuntimeVersion = MultiRTUtils.readInternalRuntimeVersion(jre.jreName);
            String installed = installedRuntimeVersion != null ? installedRuntimeVersion.trim() : null;
            if (launcherRuntimeVersion != null && launcherRuntimeVersion.equals(installed)) {
                File runtimeHome = runtimeHomeFile();
                int javaMajor = RuntimeCompat.javaMajorForRuntimeName(jre.jreName);
                if (RuntimeCompat.isRuntimeInstalledForJava(jre.jreName, runtimeHome, javaMajor)) {
                    Logging.i(TAG, jre.jreName + " installed version is current and usable.");
                    return false;
                }

                Logging.e(TAG, jre.jreName + " has the current version marker but is incomplete: "
                        + RuntimeCompat.describeRuntimeState(jre.jreName, runtimeHome), null);
                return true;
            }

            Logging.i(TAG, jre.jreName + " needs install/update. installedVersion="
                    + installed + " bundledVersion=" + launcherRuntimeVersion);
            return true;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Version check failed for internal runtime " + jre.jreName, throwable);
            return true;
        }
    }

    @Override
    public void run() {
        if (listener != null) listener.onTaskStart();

        try {
            if (checkFailed) initializeBundledState();
            if (checkFailed) {
                throw new IOException("Bundled runtime is unavailable: " + jre.jreName, checkFailure);
            }

            try (InputStream universal = assetManager.open(jre.jrePath + "/universal.tar.xz");
                 InputStream bin = openBinPack()) {
                MultiRTUtils.installRuntimeNamedBinpack(
                        universal,
                        bin,
                        jre.jreName,
                        launcherRuntimeVersion,
                        message -> dispatchProgress(jre.jreName + ": " + message)
                );
            }

            File runtimeHome = runtimeHomeFile();
            int javaMajor = RuntimeCompat.javaMajorForRuntimeName(jre.jreName);
            Logging.i(TAG, "After unpack " + jre.jreName + ": " + RuntimeCompat.describeRuntimeState(jre.jreName, runtimeHome));

            if (!RuntimeCompat.isRuntimeInstalledForJava(jre.jreName, runtimeHome, javaMajor)) {
                throw new IOException(jre.jreName + " unpack finished but runtime is still not usable. "
                        + RuntimeCompat.describeRuntimeState(jre.jreName, runtimeHome));
            }

            String installedVersion = MultiRTUtils.readInternalRuntimeVersion(jre.jreName);
            if (launcherRuntimeVersion == null
                    || !launcherRuntimeVersion.equals(installedVersion != null ? installedVersion.trim() : null)) {
                throw new IOException(jre.jreName + " version verification failed. bundled="
                        + launcherRuntimeVersion + " installed=" + installedVersion);
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Internal JRE unpack failed for " + jre.jreName, throwable);
            throw new RuntimeException("Failed to install " + jre.jreName, throwable);
        } finally {
            if (listener != null) listener.onTaskEnd();
        }
    }

    @NonNull
    private File runtimeHomeFile() {
        return new File(PathManager.DIR_MULTIRT_HOME, jre.jreName);
    }

    @NonNull
    private InputStream openBinPack() throws IOException {
        String primary = Architecture.archAsString(Tools.DEVICE_ARCHITECTURE);

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(primary);

        if ("arm64-v8a".equals(primary)) {
            candidates.add("arm64");
            candidates.add("aarch64");
        } else if ("arm64".equals(primary)) {
            candidates.add("arm64-v8a");
            candidates.add("aarch64");
        } else if ("aarch64".equals(primary)) {
            candidates.add("arm64");
            candidates.add("arm64-v8a");
        }

        if ("armeabi-v7a".equals(primary)) {
            candidates.add("arm");
        } else if ("arm".equals(primary)) {
            candidates.add("armeabi-v7a");
        }

        IOException last = null;
        for (String arch : candidates) {
            String path = jre.jrePath + "/bin-" + arch + ".tar.xz";
            try {
                Logging.i(TAG, "Trying runtime bin pack: " + path);
                return assetManager.open(path);
            } catch (IOException e) {
                last = e;
            }
        }

        throw last != null ? last : new IOException("Unable to open runtime bin pack for " + primary);
    }
}

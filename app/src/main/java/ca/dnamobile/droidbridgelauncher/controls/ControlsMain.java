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

package ca.dnamobile.droidbridgelauncher.controls;

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.BuildConfig;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
public final class ControlsMain {
    private ControlsMain() {
    }
    private static final boolean ENFORCE_IN_DEBUG_BUILDS = false;

    private static final String BLOCK_TITLE = "App integrity check failed";
    private static final String BLOCK_MESSAGE =
            "This copy of DroidBridge Launcher was not signed by DNA Mobile Applications. " +
                    "For your safety, this build is blocked.";
    private static final String CLOSE_BUTTON = "Close";
    public static boolean shouldEnforceSignatureCheck() {
        return !BuildConfig.DEBUG || ENFORCE_IN_DEBUG_BUILDS;
    }
    public static boolean isExpectedSignature(@NonNull Context context) {
        if (!shouldEnforceSignatureCheck()) {
            return true;
        }

        List<String> expectedHashes = getExpectedHashes();
        if (expectedHashes.isEmpty()) {
            return true;
        }

        try {
            Signature[] signatures = getInstalledSignatures(context);
            if (signatures == null || signatures.length == 0) {
                return false;
            }

            for (Signature signature : signatures) {
                String actual = sha256(signature.toByteArray());
                if (expectedHashes.contains(actual)) {
                    return true;
                }
            }

            // Google Play App Signing signs the APK delivered to users with the Play app
            // signing certificate, which is intentionally different from the developer's
            // upload certificate in many projects. A release build that accepts only the
            // upload certificate therefore blocks the genuine Play Store build when the
            // launcher activity opens. Trust a signature-mismatched build only when Android
            // reports Google Play itself as the installer; sideloaded builds still require
            // one of the configured certificate hashes above.
            return isInstalledFromGooglePlay(context);
        } catch (Throwable ignored) {
            // If certificate inspection itself fails, retain the same Play-distribution
            // escape hatch instead of killing a legitimate Play-installed release.
            return isInstalledFromGooglePlay(context);
        }
    }

    private static boolean isInstalledFromGooglePlay(@NonNull Context context) {
        try {
            PackageManager packageManager = context.getPackageManager();
            String packageName = context.getPackageName();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.content.pm.InstallSourceInfo source =
                        packageManager.getInstallSourceInfo(packageName);
                if (source != null) {
                    if (isGooglePlayPackage(source.getInstallingPackageName())
                            || isGooglePlayPackage(source.getInitiatingPackageName())
                            || isGooglePlayPackage(source.getOriginatingPackageName())) {
                        return true;
                    }
                }
            }

            @SuppressWarnings("deprecation")
            String installer = packageManager.getInstallerPackageName(packageName);
            return isGooglePlayPackage(installer);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isGooglePlayPackage(@Nullable String packageName) {
        return "com.android.vending".equals(packageName);
    }
    public static boolean blockIfInvalidSignature(@NonNull Activity activity) {
        if (isExpectedSignature(activity)) {
            return false;
        }

        showBlockingDialog(activity);
        return true;
    }
    public static boolean shouldBlockSensitiveAction(@NonNull Context context) {
        return !isExpectedSignature(context);
    }
    public static void throwIfInvalidSignature(@NonNull Context context) {
        if (shouldBlockSensitiveAction(context)) {
            throw new SecurityException("Blocked modified or re-signed DroidBridge Launcher build.");
        }
    }
    public static boolean toastAndBlockIfInvalidSignature(@NonNull Context context) {
        if (!shouldBlockSensitiveAction(context)) {
            return false;
        }

        Toast.makeText(context, BLOCK_TITLE, Toast.LENGTH_LONG).show();
        return true;
    }

    private static void showBlockingDialog(@NonNull Activity activity) {
        if (activity.isFinishing()) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed()) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            if (activity.isFinishing()) {
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed()) {
                return;
            }

            try {
                new MaterialAlertDialogBuilder(activity)
                        .setTitle(BLOCK_TITLE)
                        .setMessage(BLOCK_MESSAGE)
                        .setCancelable(false)
                        .setPositiveButton(CLOSE_BUTTON, (dialog, which) -> {
                            dialog.dismiss();
                            closeApp(activity);
                        })
                        .show();
            } catch (Throwable ignored) {
                closeApp(activity);
            }
        });
    }

    @NonNull
    private static List<String> getExpectedHashes() {
        ArrayList<String> hashes = new ArrayList<>();
        String raw = safeString(BuildConfig.EXPECTED_RELEASE_CERT_SHA256);
        if (raw.isEmpty() || raw.contains("PUT_YOUR") || raw.contains("CHANGE_ME")) {
            return hashes;
        }

        String[] parts = raw.split("[,;\\n\\r]+");
        for (String part : parts) {
            String normalized = normalizeSha256(part);
            if (normalized.length() == 64 && !hashes.contains(normalized)) {
                hashes.add(normalized);
            }
        }

        return hashes;
    }

    @Nullable
    private static Signature[] getInstalledSignatures(@NonNull Context context) throws Exception {
        PackageManager packageManager = context.getPackageManager();
        String packageName = context.getPackageName();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageInfo packageInfo = packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
            );

            if (packageInfo.signingInfo == null) {
                return null;
            }

            if (packageInfo.signingInfo.hasMultipleSigners()) {
                return packageInfo.signingInfo.getApkContentsSigners();
            }

            return packageInfo.signingInfo.getSigningCertificateHistory();
        } else {
            @SuppressWarnings("deprecation")
            PackageInfo packageInfo = packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNATURES
            );

            @SuppressWarnings("deprecation")
            Signature[] signatures = packageInfo.signatures;

            return signatures;
        }
    }

    @NonNull
    private static String sha256(@NonNull byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);

        StringBuilder out = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }

        return out.toString();
    }

    @NonNull
    private static String normalizeSha256(@Nullable String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace(":", "")
                .replace(" ", "")
                .replace("-", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    @NonNull
    private static String safeString(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private static void closeApp(@NonNull Activity activity) {
        try {
            activity.finishAffinity();
            return;
        } catch (Throwable ignored) {
        }

        try {
            activity.finish();
        } catch (Throwable ignored) {
        }
    }
}

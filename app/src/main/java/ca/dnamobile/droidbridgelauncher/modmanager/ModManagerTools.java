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

import android.app.Activity;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ca.dnamobile.droidbridgelauncher.R;
import ca.dnamobile.droidbridgelauncher.data.AccountStore;
import ca.dnamobile.droidbridgelauncher.security.LauncherSecurity;

public final class ModManagerTools {
    private ModManagerTools() {
    }

    public static boolean hasActiveMicrosoftAccount(@Nullable AccountStore accountStore) {
        AccountStore.Account account = loadActiveAccount(accountStore);
        return isMicrosoftAccount(account);
    }
    public static boolean hasCompletedMicrosoftLoginOnce(@Nullable AccountStore accountStore) {
        if (accountStore == null) return false;

        try {
            return accountStore.hasMicrosoftLoginCompletedOnce();
        } catch (Throwable ignored) {
            return false;
        }
    }
    public static boolean canInstallGame(@Nullable AccountStore accountStore) {
        return hasActiveMicrosoftAccount(accountStore);
    }
    public static boolean canUseOfflineMode(@Nullable AccountStore accountStore) {
        if (hasActiveMicrosoftAccount(accountStore)) return true;
        return LauncherSecurity.allowsOfflineProfileAuth() && hasCompletedMicrosoftLoginOnce(accountStore);
    }
    public static boolean requireActiveMicrosoftAccountBeforeInstall(
            @NonNull Activity activity,
            @Nullable AccountStore accountStore,
            @NonNull Runnable signInAction
    ) {
        if (canInstallGame(accountStore)) {
            return false;
        }

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.account_required_title)
                .setMessage(R.string.account_required_before_install_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.button_sign_in, (dialog, which) -> signInAction.run())
                .show();
        return true;
    }
    public static boolean requireMicrosoftLoginHistoryBeforeLaunch(
            @NonNull Activity activity,
            @Nullable AccountStore accountStore,
            @NonNull Runnable signInAction
    ) {
        if (canUseOfflineMode(accountStore)) {
            return false;
        }

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.offline_locked_title)
                .setMessage(R.string.offline_locked_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.button_sign_in, (dialog, which) -> signInAction.run())
                .show();
        return true;
    }
    public static boolean blockInstallIfNeeded(@NonNull Activity activity, @NonNull AccountStore accountStore) {
        if (canInstallGame(accountStore)) return false;
        Toast.makeText(activity, R.string.microsoft_login_required_install, Toast.LENGTH_LONG).show();
        return true;
    }
    public static boolean blockOfflineIfNeeded(@NonNull Activity activity, @NonNull AccountStore accountStore) {
        if (canUseOfflineMode(accountStore)) return false;
        Toast.makeText(activity, R.string.microsoft_login_required_offline, Toast.LENGTH_LONG).show();
        return true;
    }

    @Nullable
    private static AccountStore.Account loadActiveAccount(@Nullable AccountStore accountStore) {
        if (accountStore == null) return null;

        try {
            return accountStore.load();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isMicrosoftAccount(@Nullable AccountStore.Account account) {
        return LauncherSecurity.hasValidMicrosoftSession(account);
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }
}

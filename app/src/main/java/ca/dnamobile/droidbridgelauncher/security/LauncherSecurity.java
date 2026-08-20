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

package ca.dnamobile.droidbridgelauncher.security;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.BuildConfig;
import ca.dnamobile.droidbridgelauncher.controls.ControlsMain;
import ca.dnamobile.droidbridgelauncher.data.AccountStore;


public final class LauncherSecurity {
    private LauncherSecurity() {
    }

    public static boolean isHardenedBuild() {
        return BuildConfig.HARDENED_BUILD;
    }

    public static boolean allowsOfflineProfileAuth() {
        return !BuildConfig.HARDENED_BUILD || BuildConfig.ALLOW_OFFLINE_PROFILE_AUTH;
    }

    public static boolean hasValidMicrosoftSession(@Nullable AccountStore.Account account) {
        return account != null
                && account.isMicrosoftAccount()
                && account.hasMinecraftSession()
                && !isBlank(account.minecraftAccessToken)
                && !"0".equals(account.minecraftAccessToken.trim())
                && !isBlank(account.minecraftName)
                && !isBlank(account.minecraftUuid);
    }

    public static boolean hasValidMicrosoftSession(@Nullable AccountStore accountStore) {
        if (accountStore == null) return false;
        try {
            return hasValidMicrosoftSession(accountStore.load());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void requireOfficialBuild(@NonNull Context context) {
        if (!BuildConfig.HARDENED_BUILD) return;
        ControlsMain.throwIfInvalidSignature(context);
    }

    public static void requireOfficialBuildAndMicrosoftSession(
            @NonNull Context context,
            @Nullable AccountStore.Account account,
            @NonNull String action
    ) {
        requireOfficialBuild(context);

        if (allowsOfflineProfileAuth()) {
            return;
        }

        if (!hasValidMicrosoftSession(account)) {
            throw new SecurityException(
                    "A valid Microsoft/Minecraft account is required to " + action + "."
            );
        }
    }

    @NonNull
    public static AccountStore.Account requireActiveMicrosoftAccount(
            @NonNull Context context,
            @NonNull String action
    ) {
        requireOfficialBuild(context);
        AccountStore.Account account = null;
        try {
            account = new AccountStore(context).load();
        } catch (Throwable ignored) {
        }

        if (!hasValidMicrosoftSession(account)) {
            throw new SecurityException(
                    "A valid Microsoft/Minecraft account is required to " + action + "."
            );
        }
        return account;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }
}

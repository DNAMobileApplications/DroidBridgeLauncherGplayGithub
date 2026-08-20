/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */
package ca.dnamobile.droidbridgelauncher.fancymenu;

import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.data.AccountStore;

/**
 * Public-source placeholder. Production Microsoft sign-in and token exchange
 * are intentionally not included in this source snapshot.
 */
public final class MicrosoftAuthManagerPersonal {
    public interface Listener {
        void onSignedIn(@NonNull AccountStore.Account account);
        void onError(@NonNull String message);
    }

    private final AccountStore accountStore;
    @Nullable private Listener listener;

    public MicrosoftAuthManagerPersonal(@NonNull Activity activity, @NonNull AccountStore accountStore) {
        this.accountStore = accountStore;
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public void signIn() {
        notifyUnavailable();
    }

    public void signOut() {
        try {
            accountStore.signOutMicrosoftAccount();
        } catch (Throwable ignored) {
            try {
                accountStore.clear();
            } catch (Throwable ignoredAgain) {
            }
        }
    }

    public boolean hasLoggedIntoMicrosoftAtLeastOnce() {
        return accountStore.hasMicrosoftLoginCompletedOnce();
    }

    public boolean canUseOfflineMode() {
        return accountStore.canUseOfflineMode();
    }

    public void dispose() {
        listener = null;
    }

    public void refreshMicrosoftAccount() {
        notifyUnavailable();
    }

    private void notifyUnavailable() {
        Listener current = listener;
        if (current != null) {
            current.onError("Microsoft sign-in is not included in this public source snapshot.");
        }
    }
}

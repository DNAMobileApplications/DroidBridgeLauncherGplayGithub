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

package ca.dnamobile.droidbridgelauncher.legal;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

public final class LegalConsentStore {
    private static final String PREFS_NAME = "droidbridge_legal_consent";
    private static final String KEY_TERMS_VERSION = "accepted_terms_version";
    private static final String KEY_ACCEPTED_AT = "accepted_terms_at";

    /** Increase this if you need users to accept updated legal terms again. */
    private static final int CURRENT_TERMS_VERSION = 1;

    private LegalConsentStore() {
    }

    public static boolean hasAcceptedCurrentTerms(@NonNull Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return preferences.getInt(KEY_TERMS_VERSION, 0) >= CURRENT_TERMS_VERSION;
    }

    public static void markCurrentTermsAccepted(@NonNull Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_TERMS_VERSION, CURRENT_TERMS_VERSION)
                .putLong(KEY_ACCEPTED_AT, System.currentTimeMillis())
                .apply();
    }
}

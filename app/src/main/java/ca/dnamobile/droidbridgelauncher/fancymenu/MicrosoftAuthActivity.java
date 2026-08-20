/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */
package ca.dnamobile.droidbridgelauncher.fancymenu;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;

/**
 * Public-source placeholder. Production Microsoft sign-in UI is intentionally
 * not included in this source snapshot.
 */
public final class MicrosoftAuthActivity extends Activity {
    public static final String EXTRA_AUTH_URL = "ca.dnamobile.droidbridgelauncher.auth.AUTH_URL";
    public static final String EXTRA_AUTH_CODE = "ca.dnamobile.droidbridgelauncher.auth.AUTH_CODE";
    public static final String EXTRA_CODE_VERIFIER = "ca.dnamobile.droidbridgelauncher.auth.CODE_VERIFIER";
    public static final String EXTRA_ERROR = "ca.dnamobile.droidbridgelauncher.auth.ERROR";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent result = new Intent();
        result.putExtra(EXTRA_ERROR, "Microsoft sign-in is not included in this public source snapshot.");
        setResult(RESULT_CANCELED, result);
        finish();
    }
}

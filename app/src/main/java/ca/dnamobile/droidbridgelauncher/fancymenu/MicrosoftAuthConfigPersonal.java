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

package ca.dnamobile.droidbridgelauncher.fancymenu;

import android.net.Uri;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Microsoft/Xbox/Minecraft authentication configuration for DroidBridge.
 *
 * This class intentionally supports only the approved Azure/Microsoft identity
 * platform OAuth2 v2 + PKCE flow. The legacy Live/DroidBridge client flow is not a
 * fallback anymore.
 *
 * The client ID is a public OAuth client identifier. Do not put a client secret
 * in the APK. Inject MICROSOFT_CLIENT_ID through Gradle/local.properties or CI.
 */
public final class MicrosoftAuthConfigPersonal {
    /** Kept for older call-sites that branch on this value. It is always true now. */
    public static final boolean USE_AZURE_APP_REGISTRATION = true;

    /**
     * Preferred BuildConfig field name:
     *   buildConfigField "String", "MICROSOFT_CLIENT_ID", "\"...\""
     */
    private static final String DEFAULT_PUBLIC_CLIENT_ID = "480930d0-ec63-4373-b0bd-496ab9841506";
    private static final String DEFAULT_REDIRECT_URI = "droidbridge://auth";
    private static final String DEFAULT_SCOPE = "XboxLive.signin offline_access";

    public static final String CLIENT_ID = resolveClientId();
    public static final String REDIRECT_URI = resolveBuildConfigString("MICROSOFT_REDIRECT_URI", DEFAULT_REDIRECT_URI);
    public static final String REDIRECT_URL = REDIRECT_URI;
    public static final String SCOPE = resolveBuildConfigString("MICROSOFT_SCOPE", DEFAULT_SCOPE);
    public static final String CALLBACK_SCHEME = resolveCallbackScheme(REDIRECT_URI);

    private static final String AZURE_AUTHORITY = "https://login.microsoftonline.com/consumers";
    public static final String AUTH_URL = AZURE_AUTHORITY + "/oauth2/v2.0/authorize";
    public static final String TOKEN_URL = AZURE_AUTHORITY + "/oauth2/v2.0/token";

    public static final String XBL_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate";
    public static final String XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    public static final String MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
    public static final String MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
    public static final String MC_STORE_URL = "https://api.minecraftservices.com/entitlements/mcstore";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Defensive in-memory fallback only. The primary path passes the verifier from
     * MicrosoftAuthManagerPersonal -> MicrosoftAuthActivity -> result Intent.
     */
    @Nullable
    private static String pendingCodeVerifier;

    private MicrosoftAuthConfigPersonal() {
    }

    public static boolean isConfigured() {
        return !isBlank(CLIENT_ID) && !"CHANGE_ME".equals(CLIENT_ID);
    }

    /** Compatibility helper. Prefer buildAuthorizationUriWithPkce(...) from the manager. */
    @NonNull
    public static synchronized Uri getAuthorizationUri() {
        pendingCodeVerifier = createCodeVerifier();
        return buildAuthorizationUriWithPkce(pendingCodeVerifier);
    }

    @NonNull
    public static synchronized Uri buildAuthorizationUriWithPkce(@NonNull String codeVerifier) {
        pendingCodeVerifier = codeVerifier;
        return Uri.parse(AUTH_URL).buildUpon()
                .appendQueryParameter("client_id", CLIENT_ID)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("scope", SCOPE)
                .appendQueryParameter("redirect_uri", REDIRECT_URI)
                .appendQueryParameter("response_mode", "query")
                .appendQueryParameter("prompt", "select_account")
                .appendQueryParameter("code_challenge", createCodeChallenge(codeVerifier))
                .appendQueryParameter("code_challenge_method", "S256")
                .build();
    }

    @NonNull
    public static String getRedirectParameterName() {
        return "redirect_uri";
    }

    @NonNull
    public static synchronized String requireCodeVerifier() {
        if (pendingCodeVerifier == null || pendingCodeVerifier.length() == 0) {
            throw new IllegalStateException("Missing PKCE code verifier. Start Microsoft sign-in again.");
        }
        return pendingCodeVerifier;
    }

    public static synchronized void clearCodeVerifier() {
        pendingCodeVerifier = null;
    }

    @Nullable
    public static synchronized String getPendingCodeVerifier() {
        return pendingCodeVerifier;
    }

    @NonNull
    public static String createCodeVerifier() {
        byte[] bytes = new byte[64];
        SECURE_RANDOM.nextBytes(bytes);
        return base64UrlNoPadding(bytes);
    }

    @NonNull
    public static String createCodeChallenge(@NonNull String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return base64UrlNoPadding(hash);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create PKCE code challenge", exception);
        }
    }

    @NonNull
    public static String formatRpsTicket(@NonNull String microsoftAccessToken) {
        return microsoftAccessToken.startsWith("d=")
                ? microsoftAccessToken
                : "d=" + microsoftAccessToken;
    }

    public static boolean isRedirect(@Nullable String url) {
        if (url == null) return false;

        String lower = url.toLowerCase(java.util.Locale.ROOT);
        String redirectLower = REDIRECT_URI.toLowerCase(java.util.Locale.ROOT);
        String callbackLower = CALLBACK_SCHEME.toLowerCase(java.util.Locale.ROOT) + ":";

        return lower.startsWith(redirectLower) || lower.startsWith(callbackLower);
    }

    @Nullable
    public static String extractCode(@Nullable Uri uri) {
        if (uri == null) return null;

        String code = uri.getQueryParameter("code");
        if (code != null && code.length() > 0) return code;

        Uri fragmentUri = parseFragment(uri);
        if (fragmentUri != null) {
            code = fragmentUri.getQueryParameter("code");
            if (code != null && code.length() > 0) return code;
        }

        return null;
    }

    @Nullable
    public static String extractError(@Nullable Uri uri) {
        if (uri == null) return null;

        String error = uri.getQueryParameter("error_description");
        if (error == null || error.length() == 0) error = uri.getQueryParameter("error");
        if (error != null && error.length() > 0) return error;

        Uri fragmentUri = parseFragment(uri);
        if (fragmentUri != null) {
            error = fragmentUri.getQueryParameter("error_description");
            if (error == null || error.length() == 0) error = fragmentUri.getQueryParameter("error");
            if (error != null && error.length() > 0) return error;
        }

        return null;
    }

    @Nullable
    private static Uri parseFragment(@NonNull Uri uri) {
        String fragment = uri.getFragment();
        if (fragment == null || fragment.length() == 0) return null;
        return Uri.parse("https://localhost/?" + fragment);
    }

    @NonNull
    private static String base64UrlNoPadding(@NonNull byte[] bytes) {
        return Base64.encodeToString(
                bytes,
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP
        );
    }

    @NonNull
    private static String resolveClientId() {
        String value = readBuildConfigString("MICROSOFT_CLIENT_ID");
        if (!isBlank(value)) return value.trim();

        // Optional aliases, in case your Gradle field already uses one of these names.
        value = readBuildConfigString("AZURE_CLIENT_ID");
        if (!isBlank(value)) return value.trim();

        value = readBuildConfigString("MICROSOFT_AUTH_CLIENT_ID");
        if (!isBlank(value)) return value.trim();

        return DEFAULT_PUBLIC_CLIENT_ID;
    }

    @NonNull
    private static String resolveBuildConfigString(@NonNull String fieldName, @NonNull String fallback) {
        String value = readBuildConfigString(fieldName);
        return isBlank(value) ? fallback : value.trim();
    }

    @NonNull
    private static String resolveCallbackScheme(@NonNull String redirectUri) {
        int separator = redirectUri.indexOf(':');
        if (separator <= 0) return "droidbridge";
        return redirectUri.substring(0, separator);
    }

    @Nullable
    private static String readBuildConfigString(@NonNull String fieldName) {
        try {
            Class<?> buildConfig = Class.forName("ca.dnamobile.droidbridgelauncher.BuildConfig");
            Object value = buildConfig.getField(fieldName).get(null);
            if (value == null) return null;
            String text = String.valueOf(value).trim();
            if (text.length() == 0 || "null".equalsIgnoreCase(text)) return null;
            return text;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }
}

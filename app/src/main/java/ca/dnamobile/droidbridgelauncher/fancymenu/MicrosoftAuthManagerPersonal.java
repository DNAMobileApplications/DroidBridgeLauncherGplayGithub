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

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewDatabase;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ca.dnamobile.droidbridgelauncher.data.AccountStore;
import ca.dnamobile.droidbridgelauncher.skin.AccountSkinCache;

public final class MicrosoftAuthManagerPersonal {
    public interface Listener {
        void onSignedIn(@NonNull AccountStore.Account account);
        void onError(@NonNull String message);
    }

    private static final ExecutorService AUTH_EXECUTOR = Executors.newSingleThreadExecutor();

    private final ComponentActivity activity;
    private final AccountStore accountStore;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<Intent> authLauncher;
    private Listener listener;

    public MicrosoftAuthManagerPersonal(@NonNull Activity activity, @NonNull AccountStore accountStore) {
        if (!(activity instanceof ComponentActivity)) {
            throw new IllegalArgumentException("MicrosoftAuthManager requires an androidx.activity.ComponentActivity.");
        }

        this.activity = (ComponentActivity) activity;
        this.accountStore = accountStore;
        this.authLauncher = this.activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Intent data = result.getData();
                    if (result.getResultCode() != Activity.RESULT_OK || data == null) {
                        MicrosoftAuthSessionStore.clear(this.activity);
                        String error = data != null ? data.getStringExtra(MicrosoftAuthActivity.EXTRA_ERROR) : null;
                        notifyError(error != null && error.length() > 0 ? error : "Authorization canceled");
                        return;
                    }

                    String code = data.getStringExtra(MicrosoftAuthActivity.EXTRA_AUTH_CODE);
                    if (code == null || code.length() == 0) {
                        MicrosoftAuthSessionStore.clear(this.activity);
                        notifyError("Authorization finished without a code.");
                        return;
                    }

                    String codeVerifier = data.getStringExtra(MicrosoftAuthActivity.EXTRA_CODE_VERIFIER);
                    if (isBlank(codeVerifier)) {
                        MicrosoftAuthSessionStore.clear(this.activity);
                        notifyError("Authorization finished without a PKCE verifier. Please start Microsoft sign-in again.");
                        return;
                    }

                    exchangeCodeForMinecraftAccount(code, codeVerifier);
                }
        );
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public void signIn() {
        if (!MicrosoftAuthConfigPersonal.isConfigured()) {
            notifyError("Microsoft client ID is not configured.");
            return;
        }

        Intent intent = new Intent(activity, MicrosoftAuthActivity.class);

        String codeVerifier = MicrosoftAuthConfigPersonal.createCodeVerifier();
        String authUrl = MicrosoftAuthConfigPersonal
                .buildAuthorizationUriWithPkce(codeVerifier)
                .toString();

        MicrosoftAuthSessionStore.save(activity, codeVerifier, authUrl);

        intent.putExtra(MicrosoftAuthActivity.EXTRA_AUTH_URL, authUrl);
        intent.putExtra(MicrosoftAuthActivity.EXTRA_CODE_VERIFIER, codeVerifier);

        authLauncher.launch(intent);
    }

    /**
     * Full user-facing sign out.
     *
     * This clears the active/remembered Microsoft session from AccountStore and also
     * clears WebView authentication state so the next sign-in does not silently reuse
     * the previous Microsoft browser session. Offline profiles and the "login was
     * completed once" unlock are intentionally left to AccountStore policy.
     */
    public void signOut() {
        MicrosoftAuthSessionStore.clear(activity);
        MicrosoftAuthConfigPersonal.clearCodeVerifier();
        clearStoredMicrosoftAccount();
        MicrosoftAccountRegistry.clear(activity);
        clearWebAuthenticationState();
    }

    private void clearStoredMicrosoftAccount() {
        if (invokeNoArgAccountStoreMethod("signOutMicrosoftAccount")) return;
        if (invokeNoArgAccountStoreMethod("clearMicrosoftAccount")) return;
        if (invokeNoArgAccountStoreMethod("clearStoredMicrosoftAccount")) return;
        if (invokeNoArgAccountStoreMethod("deleteStoredMicrosoftAccount")) return;

        // Last resort for older AccountStore builds.
        try {
            accountStore.clear();
        } catch (Throwable ignored) {
        }
    }

    private boolean invokeNoArgAccountStoreMethod(@NonNull String methodName) {
        try {
            java.lang.reflect.Method method = accountStore.getClass().getMethod(methodName);
            method.invoke(accountStore);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void clearWebAuthenticationState() {
        mainHandler.post(() -> {
            try {
                CookieManager cookieManager = CookieManager.getInstance();
                cookieManager.removeAllCookies(null);
                cookieManager.removeSessionCookies(null);
                cookieManager.flush();
            } catch (Throwable ignored) {
            }

            try {
                WebStorage.getInstance().deleteAllData();
            } catch (Throwable ignored) {
            }

            try {
                WebViewDatabase database = WebViewDatabase.getInstance(activity);
                database.clearFormData();
                database.clearHttpAuthUsernamePassword();
            } catch (Throwable ignored) {
            }

            try {
                WebView.clearClientCertPreferences(null);
            } catch (Throwable ignored) {
            }
        });
    }

    public boolean hasLoggedIntoMicrosoftAtLeastOnce() {
        return accountStore.hasMicrosoftLoginCompletedOnce();
    }

    public boolean canUseOfflineMode() {
        return accountStore.canUseOfflineMode();
    }

    public void dispose() {
        // Do not cancel AUTH_EXECUTOR here. The activity can be destroyed during
        // rotation or while the user is approving sign-in in another app.
        // Any in-flight token exchange must be allowed to finish and save the
        // account to AccountStore. The recreated activity will refresh from disk.
        listener = null;
    }

    /**
     * Refresh the remembered Microsoft account and its skin. If there is no usable
     * refresh token, fall back to the normal Microsoft sign-in screen.
     */
    public void refreshMicrosoftAccount() {
        AccountStore.Account account = accountStore.load();
        if (account == null || !account.isMicrosoftAccount() || isBlank(account.refreshToken)) {
            account = accountStore.loadLastMicrosoftAccount();
        }

        if (account == null || isBlank(account.refreshToken)) {
            signIn();
            return;
        }

        String refreshToken = account.refreshToken;
        AUTH_EXECUTOR.execute(() -> {
            try {
                AccountStore.Account refreshed = loginWithMicrosoftRefreshToken(refreshToken);
                accountStore.saveMicrosoftAccount(refreshed);
                MicrosoftAccountRegistry.save(activity, refreshed);
                accountStore.markMicrosoftLoginCompletedOnce();
                AccountSkinCache.cacheMicrosoftSkinAsync(activity, refreshed);
                mainHandler.post(() -> {
                    if (listener != null) listener.onSignedIn(refreshed);
                });
            } catch (Throwable throwable) {
                notifyError(throwable.getMessage() != null ? throwable.getMessage() : throwable.toString());
            }
        });
    }

    private void exchangeCodeForMinecraftAccount(
            @NonNull String authCode,
            @NonNull String codeVerifier
    ) {
        AUTH_EXECUTOR.execute(() -> {
            try {
                AccountStore.Account account = loginWithMicrosoftCode(authCode, codeVerifier);
                accountStore.saveMicrosoftAccount(account);
                MicrosoftAccountRegistry.save(activity, account);
                accountStore.markMicrosoftLoginCompletedOnce();
                MicrosoftAuthSessionStore.clear(activity);
                MicrosoftAuthConfigPersonal.clearCodeVerifier();
                AccountSkinCache.cacheMicrosoftSkinAsync(activity, account);
                mainHandler.post(() -> {
                    if (listener != null) listener.onSignedIn(account);
                });
            } catch (Throwable throwable) {
                MicrosoftAuthSessionStore.clear(activity);
                MicrosoftAuthConfigPersonal.clearCodeVerifier();
                notifyError(throwable.getMessage() != null ? throwable.getMessage() : throwable.toString());
            }
        });
    }

    @NonNull
    private AccountStore.Account loginWithMicrosoftCode(
            @NonNull String authCode,
            @NonNull String codeVerifier
    ) throws IOException, JSONException {
        return loginWithLiveTokens(acquireLiveAccessToken(false, authCode, codeVerifier), "");
    }

    @NonNull
    private AccountStore.Account loginWithMicrosoftRefreshToken(@NonNull String refreshToken) throws IOException, JSONException {
        return loginWithLiveTokens(acquireLiveAccessToken(true, refreshToken, null), refreshToken);
    }

    @NonNull
    private AccountStore.Account loginWithLiveTokens(
            @NonNull LiveTokens liveTokens,
            @NonNull String oldRefreshToken
    ) throws IOException, JSONException {
        String refreshToken = liveTokens.refreshToken.length() > 0 ? liveTokens.refreshToken : oldRefreshToken;
        String xblToken = acquireXblToken(liveTokens.accessToken);
        XstsToken xstsToken = acquireXstsToken(xblToken);
        String minecraftToken = acquireMinecraftToken(xstsToken.userHash, xstsToken.token);

        fetchOwnedItemsQuietly(minecraftToken);

        MinecraftProfile profile = fetchMinecraftProfile(minecraftToken);
        String playerName = profile.name.length() > 0 ? profile.name : "Microsoft Player";

        return new AccountStore.Account(
                "",
                playerName,
                "",
                minecraftToken,
                refreshToken,
                minecraftToken,
                playerName,
                profile.uuidWithDashes,
                xstsToken.userHash,
                profile.skinUrl,
                profile.skinVariant
        );
    }

    @NonNull
    private LiveTokens acquireLiveAccessToken(
            boolean isRefresh,
            @NonNull String codeOrRefreshToken,
            @Nullable String codeVerifier
    ) throws IOException, JSONException {
        String formData = createTokenRequestFormData(isRefresh, codeOrRefreshToken, codeVerifier);

        try {
            JSONObject json = postForm(MicrosoftAuthConfigPersonal.TOKEN_URL, formData);
            return new LiveTokens(
                    json.getString("access_token"),
                    json.optString("refresh_token", "")
            );
        } finally {
            if (MicrosoftAuthConfigPersonal.USE_AZURE_APP_REGISTRATION && !isRefresh) {
                MicrosoftAuthConfigPersonal.clearCodeVerifier();
            }
        }
    }

    @NonNull
    private String createTokenRequestFormData(
            boolean isRefresh,
            @NonNull String codeOrRefreshToken,
            @Nullable String codeVerifier
    ) throws IOException {
        if (isRefresh) {
            return toFormData(
                    "client_id", MicrosoftAuthConfigPersonal.CLIENT_ID,
                    "grant_type", "refresh_token",
                    "refresh_token", codeOrRefreshToken,
                    "scope", MicrosoftAuthConfigPersonal.SCOPE
            );
        }

        String verifier = !isBlank(codeVerifier)
                ? codeVerifier
                : MicrosoftAuthConfigPersonal.requireCodeVerifier();

        return toFormData(
                "client_id", MicrosoftAuthConfigPersonal.CLIENT_ID,
                "grant_type", "authorization_code",
                "code", codeOrRefreshToken,
                "redirect_uri", MicrosoftAuthConfigPersonal.REDIRECT_URI,
                "scope", MicrosoftAuthConfigPersonal.SCOPE,
                "code_verifier", verifier
        );
    }

    @NonNull
    private String acquireXblToken(@NonNull String accessToken) throws IOException, JSONException {
        JSONObject properties = new JSONObject();
        properties.put("AuthMethod", "RPS");
        properties.put("SiteName", "user.auth.xboxlive.com");
        properties.put("RpsTicket", MicrosoftAuthConfigPersonal.formatRpsTicket(accessToken));

        JSONObject body = new JSONObject();
        body.put("Properties", properties);
        body.put("RelyingParty", "http://auth.xboxlive.com");
        body.put("TokenType", "JWT");

        return postJson(MicrosoftAuthConfigPersonal.XBL_AUTH_URL, body).getString("Token");
    }

    @NonNull
    private XstsToken acquireXstsToken(@NonNull String xblToken) throws IOException, JSONException {
        JSONObject properties = new JSONObject();
        properties.put("SandboxId", "RETAIL");
        properties.put("UserTokens", new JSONArray(Collections.singleton(xblToken)));

        JSONObject body = new JSONObject();
        body.put("Properties", properties);
        body.put("RelyingParty", "rp://api.minecraftservices.com/");
        body.put("TokenType", "JWT");

        JSONObject json = postJson(MicrosoftAuthConfigPersonal.XSTS_AUTH_URL, body);
        String userHash = json.getJSONObject("DisplayClaims")
                .getJSONArray("xui")
                .getJSONObject(0)
                .getString("uhs");

        return new XstsToken(userHash, json.getString("Token"));
    }

    @NonNull
    private String acquireMinecraftToken(
            @NonNull String userHash,
            @NonNull String xstsToken
    ) throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);
        return postJson(MicrosoftAuthConfigPersonal.MC_LOGIN_URL, body).getString("access_token");
    }

    private void fetchOwnedItemsQuietly(@NonNull String minecraftAccessToken) {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(MicrosoftAuthConfigPersonal.MC_STORE_URL);
            connection.setRequestProperty("Authorization", "Bearer " + minecraftAccessToken);
            int code = connection.getResponseCode();
            readFully(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
        } catch (Throwable ignored) {
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @NonNull
    private MinecraftProfile fetchMinecraftProfile(@NonNull String minecraftAccessToken)
            throws IOException, JSONException {
        HttpURLConnection connection = openConnection(MicrosoftAuthConfigPersonal.MC_PROFILE_URL);
        connection.setRequestProperty("Authorization", "Bearer " + minecraftAccessToken);

        int code = connection.getResponseCode();
        String response = readFully(code >= 200 && code < 300
                ? connection.getInputStream()
                : connection.getErrorStream());
        connection.disconnect();

        if (code < 200 || code >= 300) {
            if (code == 404) {
                throw new IOException("This Microsoft account does not own Minecraft: Java Edition.");
            }
            throw new IOException("Minecraft profile request failed: HTTP " + code + " " + response);
        }

        JSONObject json = new JSONObject(response);
        String skinUrl = "";
        String skinVariant = "classic";

        JSONArray skins = json.optJSONArray("skins");
        if (skins != null) {
            for (int i = 0; i < skins.length(); i++) {
                JSONObject skin = skins.optJSONObject(i);
                if (skin == null) continue;

                String state = skin.optString("state", "ACTIVE");
                String url = skin.optString("url", "");
                if (url.length() == 0) continue;

                // Prefer the active skin, but accept the first valid skin when the API does not include state.
                if (skinUrl.length() == 0 || "ACTIVE".equalsIgnoreCase(state)) {
                    skinUrl = AccountSkinCache.normalizeSkinUrl(url);
                    String variant = skin.optString("variant", "CLASSIC");
                    skinVariant = "SLIM".equalsIgnoreCase(variant) ? "slim" : "classic";
                    if ("ACTIVE".equalsIgnoreCase(state)) break;
                }
            }
        }

        return new MinecraftProfile(
                json.optString("name", "Microsoft Player"),
                addDashesToUuid(json.getString("id")),
                skinUrl,
                skinVariant
        );
    }

    @NonNull
    private static JSONObject postForm(@NonNull String url, @NonNull String formData)
            throws IOException, JSONException {
        HttpURLConnection connection = openConnection(url);
        byte[] bytes = formData.getBytes(StandardCharsets.UTF_8);

        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        connection.setRequestProperty("charset", "utf-8");
        connection.setRequestProperty("Content-Length", Integer.toString(bytes.length));
        connection.setUseCaches(false);
        connection.setDoInput(true);
        connection.setDoOutput(true);

        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(bytes);
        }

        return readJsonResponse(connection);
    }

    @NonNull
    private static JSONObject postJson(@NonNull String url, @NonNull JSONObject body)
            throws IOException, JSONException {
        HttpURLConnection connection = openConnection(url);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);

        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("charset", "utf-8");
        connection.setRequestProperty("Content-Length", Integer.toString(bytes.length));
        connection.setUseCaches(false);
        connection.setDoInput(true);
        connection.setDoOutput(true);

        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(bytes);
        }

        return readJsonResponse(connection);
    }

    @NonNull
    private static JSONObject readJsonResponse(@NonNull HttpURLConnection connection)
            throws IOException, JSONException {
        int code = connection.getResponseCode();
        String response = readFully(code >= 200 && code < 300
                ? connection.getInputStream()
                : connection.getErrorStream());

        connection.disconnect();

        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + ": " + response);
        }

        return new JSONObject(response);
    }

    @NonNull
    private static HttpURLConnection openConnection(@NonNull String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(30_000);
        return connection;
    }

    @NonNull
    private static String readFully(@Nullable InputStream inputStream) throws IOException {
        if (inputStream == null) return "";

        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }

        return builder.toString();
    }

    @NonNull
    private static String toFormData(@NonNull String... data) throws IOException {
        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < data.length; i += 2) {
            if (builder.length() > 0) builder.append('&');
            builder.append(URLEncoder.encode(data[i], "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(data[i + 1], "UTF-8"));
        }

        return builder.toString();
    }

    @NonNull
    private static String addDashesToUuid(@NonNull String rawUuid) {
        if (rawUuid.contains("-") || rawUuid.length() != 32) return rawUuid;
        return rawUuid.replaceFirst(
                "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                "$1-$2-$3-$4-$5"
        );
    }

    private void notifyError(@NonNull String message) {
        mainHandler.post(() -> {
            if (listener != null) listener.onError(message);
        });
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class LiveTokens {
        final String accessToken;
        final String refreshToken;

        LiveTokens(@NonNull String accessToken, @NonNull String refreshToken) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
        }
    }

    private static final class XstsToken {
        final String userHash;
        final String token;

        XstsToken(@NonNull String userHash, @NonNull String token) {
            this.userHash = userHash;
            this.token = token;
        }
    }

    private static final class MinecraftProfile {
        final String name;
        final String uuidWithDashes;
        final String skinUrl;
        final String skinVariant;

        MinecraftProfile(
                @NonNull String name,
                @NonNull String uuidWithDashes,
                @NonNull String skinUrl,
                @NonNull String skinVariant
        ) {
            this.name = name;
            this.uuidWithDashes = uuidWithDashes;
            this.skinUrl = skinUrl;
            this.skinVariant = skinVariant;
        }
    }
}

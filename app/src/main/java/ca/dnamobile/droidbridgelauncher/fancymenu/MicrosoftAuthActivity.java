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

import ca.dnamobile.droidbridgelauncher.LauncherTheme;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

public final class MicrosoftAuthActivity extends Activity {
    private static final String TAG = "MicrosoftAuth";

    public static final String EXTRA_AUTH_URL = "ca.dnamobile.droidbridgelauncher.auth.AUTH_URL";
    public static final String EXTRA_AUTH_CODE = "ca.dnamobile.droidbridgelauncher.auth.AUTH_CODE";
    public static final String EXTRA_CODE_VERIFIER = "ca.dnamobile.droidbridgelauncher.auth.CODE_VERIFIER";
    public static final String EXTRA_ERROR = "ca.dnamobile.droidbridgelauncher.auth.ERROR";

    private static final String STATE_AUTH_URL = "ca.dnamobile.droidbridgelauncher.auth.STATE_AUTH_URL";
    private static final String STATE_CODE_VERIFIER = "ca.dnamobile.droidbridgelauncher.auth.STATE_CODE_VERIFIER";

    private WebView webView;
    private ProgressBar progressBar;
    private boolean finished;
    @Nullable
    private String authUrl;
    @Nullable
    private String pkceCodeVerifier;
    private boolean restoredFromPendingSession;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherTheme.apply(this);
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            authUrl = savedInstanceState.getString(STATE_AUTH_URL);
            pkceCodeVerifier = savedInstanceState.getString(STATE_CODE_VERIFIER);
        }

        if (isBlank(authUrl)) {
            authUrl = getIntent().getStringExtra(EXTRA_AUTH_URL);
        }
        if (isBlank(pkceCodeVerifier)) {
            pkceCodeVerifier = getIntent().getStringExtra(EXTRA_CODE_VERIFIER);
        }

        if (isBlank(authUrl) || isBlank(pkceCodeVerifier)) {
            MicrosoftAuthSessionStore.Pending pending = MicrosoftAuthSessionStore.load(this);
            if (pending != null) {
                authUrl = pending.authUrl;
                pkceCodeVerifier = pending.codeVerifier;
                restoredFromPendingSession = true;
            }
        }

        createContentView();
        setupWebView();

        if (savedInstanceState == null) {
            // A brand-new sign-in intentionally clears browser state. A restored
            // pending session does not, otherwise returning from Authenticator or
            // a rotation can wipe the Microsoft web login mid-flow.
            startSession(!restoredFromPendingSession);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private void createContentView() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        progressBar = new ProgressBar(this);
        root.addView(progressBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        ));
        setContentView(root);
        LauncherTheme.applyRainbowBackgroundIfNeeded(this);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);
        settings.setSaveFormData(false);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new LoginWebChromeClient());
        webView.setWebViewClient(new LoginWebViewClient());
    }

    private void startSession(boolean clearBrowserState) {
        progressBar.setVisibility(View.VISIBLE);

        Runnable load = () -> {
            if (webView == null) return;

            if (clearBrowserState) {
                webView.clearHistory();
                webView.clearCache(true);
                webView.clearFormData();
            }

            ensureAuthUrl();
            if (isBlank(authUrl) || isBlank(pkceCodeVerifier)) {
                cancelLogin("Unable to create Microsoft authorization URL.");
                return;
            }

            MicrosoftAuthSessionStore.save(this, pkceCodeVerifier, authUrl);
            Logging.i(TAG, "Opening Microsoft login: " + sanitizeUrlForLog(authUrl));
            webView.loadUrl(authUrl);
        };

        if (!clearBrowserState) {
            load.run();
            return;
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.removeAllCookies(ignored -> {
            try {
                CookieManager.getInstance().flush();
            } catch (Throwable ignoredFlush) {
            }
            load.run();
        });
    }

    private void ensureAuthUrl() {
        if (authUrl != null && authUrl.length() > 0) return;

        if (pkceCodeVerifier == null || pkceCodeVerifier.length() == 0) {
            pkceCodeVerifier = MicrosoftAuthConfigPersonal.createCodeVerifier();
        }
        authUrl = MicrosoftAuthConfigPersonal
                .buildAuthorizationUriWithPkce(pkceCodeVerifier)
                .toString();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (authUrl != null && authUrl.length() > 0) {
            outState.putString(STATE_AUTH_URL, authUrl);
        }
        if (pkceCodeVerifier != null && pkceCodeVerifier.length() > 0) {
            outState.putString(STATE_CODE_VERIFIER, pkceCodeVerifier);
        }
        if (webView != null) webView.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        cancelLogin("Authorization canceled");
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private boolean handleUrl(@Nullable String url) {
        if (finished || url == null) return false;

        if (url.contains("res=cancel")) {
            cancelLogin("Authorization canceled");
            return true;
        }

        if (!MicrosoftAuthConfigPersonal.isRedirect(url)) {
            return false;
        }

        Uri uri = Uri.parse(url);
        String code = MicrosoftAuthConfigPersonal.extractCode(uri);
        String error = MicrosoftAuthConfigPersonal.extractError(uri);
        Intent result = new Intent();

        if (code == null || code.length() == 0) {
            String message = error != null && error.length() > 0
                    ? error
                    : "Microsoft login finished without an authorization code.";
            Logging.i(TAG, message + " url=" + sanitizeUrlForLog(url));
            MicrosoftAuthSessionStore.clear(this);
            result.putExtra(EXTRA_ERROR, message);
            finished = true;
            setResult(Activity.RESULT_CANCELED, result);
        } else {
            Logging.i(TAG, "Microsoft authorization code received.");
            result.putExtra(EXTRA_AUTH_CODE, code);

            if (pkceCodeVerifier == null || pkceCodeVerifier.length() == 0) {
                pkceCodeVerifier = MicrosoftAuthConfigPersonal.getPendingCodeVerifier();
            }
            if (pkceCodeVerifier == null || pkceCodeVerifier.length() == 0) {
                String message = "Authorization finished without a PKCE verifier. Please start Microsoft sign-in again.";
                Logging.i(TAG, message);
                MicrosoftAuthSessionStore.clear(this);
                result.putExtra(EXTRA_ERROR, message);
                finished = true;
                setResult(Activity.RESULT_CANCELED, result);
                finish();
                return true;
            }
            result.putExtra(EXTRA_CODE_VERIFIER, pkceCodeVerifier);

            finished = true;
            setResult(Activity.RESULT_OK, result);
        }

        finish();
        return true;
    }

    private void cancelLogin(@NonNull String message) {
        if (finished) return;
        finished = true;
        MicrosoftAuthSessionStore.clear(this);
        Intent result = new Intent();
        result.putExtra(EXTRA_ERROR, message);
        setResult(Activity.RESULT_CANCELED, result);
        finish();
    }

    @NonNull
    private String sanitizeUrlForLog(@NonNull String url) {
        int codeIndex = url.indexOf("code=");
        if (codeIndex >= 0) return url.substring(0, codeIndex) + "code=<hidden>";

        int challengeIndex = url.indexOf("code_challenge=");
        if (challengeIndex >= 0) {
            int end = url.indexOf('&', challengeIndex);
            if (end >= 0) {
                return url.substring(0, challengeIndex) + "code_challenge=<hidden>" + url.substring(end);
            }
            return url.substring(0, challengeIndex) + "code_challenge=<hidden>";
        }

        return url;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    private final class LoginWebChromeClient extends WebChromeClient {
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            if (webView == null || resultMsg == null) return false;
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(webView);
            resultMsg.sendToTarget();
            return true;
        }
    }

    private final class LoginWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrl(url) || super.shouldOverrideUrlLoading(view, url);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request != null && request.getUrl() != null ? request.getUrl().toString() : null;
            return handleUrl(url) || super.shouldOverrideUrlLoading(view, request);
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
            handleUrl(url);
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            super.doUpdateVisitedHistory(view, url, isReload);
            handleUrl(url);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            handleUrl(url);
            if (progressBar != null) progressBar.setVisibility(View.GONE);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            super.onReceivedError(view, request, error);
            if (request == null || !request.isForMainFrame()) return;

            String url = request.getUrl() != null ? request.getUrl().toString() : "";
            if (MicrosoftAuthConfigPersonal.isRedirect(url)) {
                handleUrl(url);
                return;
            }

            String description = error != null ? String.valueOf(error.getDescription()) : "Unknown WebView error";
            Logging.i(TAG, "Microsoft login WebView main-frame error: " + description + " url=" + sanitizeUrlForLog(url));
        }
    }
}

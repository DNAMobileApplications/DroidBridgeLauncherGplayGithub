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

package ca.dnamobile.droidbridgelauncher.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;

import ca.dnamobile.droidbridgelauncher.security.LauncherSecurity;
import ca.dnamobile.droidbridgelauncher.skin.CustomSkinStore;
import ca.dnamobile.droidbridgelauncher.skin.SkinModelType;

/**
 * Account storage for DroidBridge.
 *
 * One Microsoft account is remembered separately from the active account. That lets the
 * Microsoft account remain the ownership/default account while the user switches to one
 * of several offline profiles for no-internet play.
 */
public final class AccountStore {
    private static final String PREFS = "java_launcher_accounts";
    private static final String KEY_ACCOUNT_JSON = "active_account_json";
    private static final String KEY_LAST_MICROSOFT_ACCOUNT_JSON = "last_microsoft_account_json";
    private static final String KEY_MICROSOFT_LOGIN_COMPLETED_ONCE = "microsoft_login_completed_once";
    private static final String KEY_OFFLINE_ACCOUNTS_JSON = "offline_accounts_json";

    private static final String OFFLINE_DIR = "offline_accounts";

    private final Context context;
    private final SharedPreferences preferences;

    public AccountStore(@NonNull Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Backwards-compatible save. Microsoft accounts update the remembered online
     * account. Offline accounts are added/updated in the offline profile list.
     */
    public void save(@NonNull Account account) {
        if (account.isMicrosoftAccount()) {
            saveMicrosoftAccount(account);
        } else {
            saveOfflineAccount(account);
        }
    }

    public void saveMicrosoftAccount(@NonNull Account account) {
        Account microsoft = account.asMicrosoftAccount();
        preferences.edit()
                .putString(KEY_ACCOUNT_JSON, microsoft.toJson().toString())
                .putString(KEY_LAST_MICROSOFT_ACCOUNT_JSON, microsoft.toJson().toString())
                .putBoolean(KEY_MICROSOFT_LOGIN_COMPLETED_ONCE, true)
                .apply();
    }

    public void useLastMicrosoftAccount() {
        Account account = loadLastMicrosoftAccount();
        if (account == null) {
            throw new IllegalStateException("No remembered Microsoft account is available.");
        }
        saveActiveOnly(account.asMicrosoftAccount());
    }

    public void saveOfflineAccount(@NonNull String username) {
        saveOrUpdateOfflineAccount(null, username, null, false);
    }

    @NonNull
    public Account saveOrUpdateOfflineAccount(@Nullable String existingAccountId,
                                              @NonNull String username,
                                              @Nullable Uri selectedSkinUri,
                                              boolean clearSkin) {
        if (!canUseOfflineMode()) {
            throw new IllegalStateException("Offline profiles are disabled in this build. Sign in with a Microsoft account to continue.");
        }

        String safeName = Account.sanitizePlayerName(username);
        if (safeName.length() < 3 || safeName.length() > 16) {
            throw new IllegalStateException("Offline username must be 3-16 letters, numbers, or underscores.");
        }

        ArrayList<Account> accounts = listOfflineAccounts();
        Account previous = null;
        if (existingAccountId != null && existingAccountId.trim().length() > 0) {
            for (Account account : accounts) {
                if (existingAccountId.equals(account.accountId)) {
                    previous = account;
                    break;
                }
            }
        }

        String accountId = previous != null ? previous.accountId : UUID.randomUUID().toString();
        File skinFile = getOfflineSkinFile(accountId);
        String skinPath = previous != null ? previous.offlineSkinPath : "";
        SkinModelType skinModel = previous != null ? SkinModelType.fromId(previous.offlineSkinModel) : SkinModelType.NONE;

        try {
            if (selectedSkinUri != null) {
                ensureParent(skinFile);
                File temp = new File(skinFile.getParentFile(), skinFile.getName() + ".tmp");
                copyUriToFile(selectedSkinUri, temp);
                if (!CustomSkinStore.isSkinValid(temp)) {
                    //noinspection ResultOfMethodCallIgnored
                    temp.delete();
                    throw new IllegalStateException("Invalid skin. Use a 64x64 or 64x32 PNG skin.");
                }
                skinModel = CustomSkinStore.getSkinModel(temp);
                if (skinFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    skinFile.delete();
                }
                if (!temp.renameTo(skinFile)) {
                    copyFile(temp, skinFile);
                    //noinspection ResultOfMethodCallIgnored
                    temp.delete();
                }
                skinPath = skinFile.getAbsolutePath();
            } else if (clearSkin) {
                if (skinFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    skinFile.delete();
                }
                skinPath = "";
                skinModel = SkinModelType.NONE;
            } else if (skinPath.length() > 0 && !new File(skinPath).isFile()) {
                skinPath = "";
                skinModel = SkinModelType.NONE;
            }
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage() != null ? e.getMessage() : e.toString(), e);
        }

        String offlineUuid = CustomSkinStore.getOfflineUuidWithSkinModel(safeName, skinModel);
        Account updated = Account.offline(accountId, safeName, offlineUuid, skinPath, skinModel.id);

        boolean replaced = false;
        for (int i = 0; i < accounts.size(); i++) {
            if (updated.accountId.equals(accounts.get(i).accountId)) {
                accounts.set(i, updated);
                replaced = true;
                break;
            }
        }
        if (!replaced) accounts.add(updated);

        saveOfflineAccounts(accounts);
        saveActiveOnly(updated);
        return updated;
    }

    public void saveOfflineAccount(@NonNull Account account) {
        if (!account.isOfflineAccount()) {
            throw new IllegalArgumentException("Expected offline account");
        }
        if (!canUseOfflineMode()) {
            throw new IllegalStateException("Offline profiles are disabled in this build. Sign in with a Microsoft account to continue.");
        }

        ArrayList<Account> accounts = listOfflineAccounts();
        Account offline = account.asOfflineAccount();
        boolean replaced = false;
        for (int i = 0; i < accounts.size(); i++) {
            if (offline.accountId.equals(accounts.get(i).accountId)) {
                accounts.set(i, offline);
                replaced = true;
                break;
            }
        }
        if (!replaced) accounts.add(offline);
        saveOfflineAccounts(accounts);
        saveActiveOnly(offline);
    }

    public void activateOfflineAccount(@NonNull String accountId) {
        if (!canUseOfflineMode()) {
            throw new IllegalStateException("Offline profiles are disabled in this build. Sign in with a Microsoft account to continue.");
        }

        for (Account account : listOfflineAccounts()) {
            if (accountId.equals(account.accountId)) {
                saveActiveOnly(account);
                return;
            }
        }
        throw new IllegalStateException("Offline account was not found.");
    }

    public void deleteOfflineAccount(@NonNull String accountId) {
        ArrayList<Account> accounts = listOfflineAccounts();
        Account removed = null;
        for (int i = 0; i < accounts.size(); i++) {
            if (accountId.equals(accounts.get(i).accountId)) {
                removed = accounts.remove(i);
                break;
            }
        }

        if (removed != null) {
            File skinFile = getOfflineSkinFile(accountId);
            if (skinFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                skinFile.delete();
            }
        }

        saveOfflineAccounts(accounts);

        Account active = load();
        if (active != null && active.isOfflineAccount() && accountId.equals(active.accountId)) {
            Account microsoft = loadLastMicrosoftAccount();
            if (microsoft != null) saveActiveOnly(microsoft.asMicrosoftAccount());
            else clear();
        }
    }

    @NonNull
    public ArrayList<Account> listOfflineAccounts() {
        ArrayList<Account> out = new ArrayList<>();
        String raw = preferences.getString(KEY_OFFLINE_ACCOUNTS_JSON, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                Account account = Account.fromJson(object);
                if (account.isOfflineAccount()) out.add(account);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private void saveOfflineAccounts(@NonNull ArrayList<Account> accounts) {
        JSONArray array = new JSONArray();
        for (Account account : accounts) array.put(account.toJson());
        preferences.edit().putString(KEY_OFFLINE_ACCOUNTS_JSON, array.toString()).apply();
    }

    private void saveActiveOnly(@NonNull Account account) {
        preferences.edit()
                .putString(KEY_ACCOUNT_JSON, account.toJson().toString())
                .apply();
    }

    @Nullable
    public Account load() {
        return readAccount(KEY_ACCOUNT_JSON);
    }

    @Nullable
    public Account loadLastMicrosoftAccount() {
        Account account = readAccount(KEY_LAST_MICROSOFT_ACCOUNT_JSON);
        if (account != null && account.isMicrosoftAccount()) return account;

        // Migration path for old builds where the active account was the only stored copy.
        account = load();
        return account != null && account.isMicrosoftAccount() ? account : null;
    }

    @Nullable
    private Account readAccount(@NonNull String key) {
        String json = preferences.getString(key, null);
        if (json == null || json.length() == 0) return null;
        try {
            return Account.fromJson(new JSONObject(json));
        } catch (JSONException e) {
            return null;
        }
    }

    public boolean hasActiveAccount() {
        return load() != null;
    }

    public boolean hasActiveMicrosoftAccount() {
        Account account = load();
        return account != null && account.isMicrosoftAccount() && account.hasMinecraftSession();
    }

    public boolean hasStoredMicrosoftAccount() {
        return loadLastMicrosoftAccount() != null;
    }

    public boolean hasMicrosoftLoginCompletedOnce() {
        if (preferences.getBoolean(KEY_MICROSOFT_LOGIN_COMPLETED_ONCE, false)) return true;
        Account account = loadLastMicrosoftAccount();
        return account != null && account.isMicrosoftAccount();
    }

    public boolean canUseOfflineMode() {
        return LauncherSecurity.allowsOfflineProfileAuth() && hasMicrosoftLoginCompletedOnce();
    }

    public void markMicrosoftLoginCompletedOnce() {
        preferences.edit().putBoolean(KEY_MICROSOFT_LOGIN_COMPLETED_ONCE, true).apply();
    }

    /**
     * Full Microsoft sign out.
     *
     * This removes the active account, the remembered Microsoft account, and the
     * Microsoft-login-completed flag so offline profiles remain saved but locked
     * until the user signs into Microsoft again.
     */
    public void signOutMicrosoftAccount() {
        preferences.edit()
                .remove(KEY_ACCOUNT_JSON)
                .remove(KEY_LAST_MICROSOFT_ACCOUNT_JSON)
                .remove(KEY_MICROSOFT_LOGIN_COMPLETED_ONCE)
                .commit();
    }

    /** Sign out clears the current account only. It keeps offline permission and the remembered Microsoft account. */
    public void clear() {
        preferences.edit().remove(KEY_ACCOUNT_JSON).apply();
    }

    /** Only call this for a full reset/clear-data style action, not normal sign out. */
    public void clearMicrosoftLoginHistoryForFullResetOnly() {
        preferences.edit()
                .remove(KEY_ACCOUNT_JSON)
                .remove(KEY_LAST_MICROSOFT_ACCOUNT_JSON)
                .remove(KEY_MICROSOFT_LOGIN_COMPLETED_ONCE)
                .remove(KEY_OFFLINE_ACCOUNTS_JSON)
                .apply();
    }

    @NonNull
    private File getOfflineSkinFile(@NonNull String accountId) {
        return new File(new File(context.getFilesDir(), OFFLINE_DIR + "/" + accountId), "skin.png");
    }

    private void copyUriToFile(@NonNull Uri sourceUri, @NonNull File destination) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(sourceUri)) {
            if (input == null) throw new IOException("Could not open selected skin.");
            ensureParent(destination);
            try (FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            }
        }
    }

    private static void copyFile(@NonNull File source, @NonNull File destination) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        }
    }

    private static void ensureParent(@NonNull File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create folder: " + parent.getAbsolutePath());
        }
    }

    private static boolean notEmpty(@Nullable String value) {
        return value != null && value.trim().length() > 0;
    }

    public static final class Account {
        public static final String TYPE_MICROSOFT = "microsoft";
        public static final String TYPE_OFFLINE = "offline";

        public final String accountType;
        public final String accountId;
        public final String email;
        public final String displayName;
        public final String idToken;
        public final String accessToken;
        public final String refreshToken;
        public final String minecraftAccessToken;
        public final String minecraftName;
        public final String minecraftUuid;
        public final String xuid;
        public final String skinUrl;
        public final String skinVariant;
        public final String offlineSkinPath;
        public final String offlineSkinModel;

        public Account(@NonNull String email, @NonNull String displayName, @NonNull String idToken, @NonNull String accessToken) {
            this(TYPE_MICROSOFT, "", email, displayName, idToken, accessToken, "", "", "", "", "", "", "classic", "", "none");
        }

        public Account(@NonNull String email,
                       @NonNull String displayName,
                       @NonNull String idToken,
                       @NonNull String accessToken,
                       @NonNull String refreshToken,
                       @NonNull String minecraftAccessToken,
                       @NonNull String minecraftName,
                       @NonNull String minecraftUuid,
                       @NonNull String xuid) {
            this(TYPE_MICROSOFT, "", email, displayName, idToken, accessToken, refreshToken, minecraftAccessToken,
                    minecraftName, minecraftUuid, xuid, "", "classic", "", "none");
        }

        public Account(@NonNull String email,
                       @NonNull String displayName,
                       @NonNull String idToken,
                       @NonNull String accessToken,
                       @NonNull String refreshToken,
                       @NonNull String minecraftAccessToken,
                       @NonNull String minecraftName,
                       @NonNull String minecraftUuid,
                       @NonNull String xuid,
                       @NonNull String skinUrl,
                       @NonNull String skinVariant) {
            this(TYPE_MICROSOFT, "", email, displayName, idToken, accessToken, refreshToken, minecraftAccessToken,
                    minecraftName, minecraftUuid, xuid, skinUrl, skinVariant, "", "none");
        }

        public Account(@NonNull String accountType,
                       @NonNull String accountId,
                       @NonNull String email,
                       @NonNull String displayName,
                       @NonNull String idToken,
                       @NonNull String accessToken,
                       @NonNull String refreshToken,
                       @NonNull String minecraftAccessToken,
                       @NonNull String minecraftName,
                       @NonNull String minecraftUuid,
                       @NonNull String xuid,
                       @NonNull String skinUrl,
                       @NonNull String skinVariant,
                       @NonNull String offlineSkinPath,
                       @NonNull String offlineSkinModel) {
            this.accountType = accountType;
            this.accountId = accountId;
            this.email = email;
            this.displayName = displayName;
            this.idToken = idToken;
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.minecraftAccessToken = minecraftAccessToken;
            this.minecraftName = minecraftName;
            this.minecraftUuid = minecraftUuid;
            this.xuid = xuid;
            this.skinUrl = skinUrl;
            this.skinVariant = skinVariant;
            this.offlineSkinPath = offlineSkinPath;
            this.offlineSkinModel = offlineSkinModel;
        }

        @NonNull
        public static Account offline(@NonNull String username) {
            String safeName = sanitizePlayerName(username);
            String accountId = UUID.randomUUID().toString();
            String uuid = CustomSkinStore.getOfflineUuidWithSkinModel(safeName, SkinModelType.NONE);
            return offline(accountId, safeName, uuid, "", SkinModelType.NONE.id);
        }

        @NonNull
        public static Account offline(@NonNull String accountId,
                                      @NonNull String username,
                                      @NonNull String offlineUuid,
                                      @NonNull String skinPath,
                                      @NonNull String skinModel) {
            String safeName = sanitizePlayerName(username);
            return new Account(TYPE_OFFLINE, accountId, "", safeName, "", "0", "", "",
                    safeName, offlineUuid, "", "", "classic", skinPath, skinModel);
        }

        @NonNull
        Account asMicrosoftAccount() {
            if (TYPE_MICROSOFT.equals(accountType)) return this;
            return new Account(TYPE_MICROSOFT, accountId, email, displayName, idToken, accessToken, refreshToken,
                    minecraftAccessToken, minecraftName, minecraftUuid, xuid, skinUrl, skinVariant, "", "none");
        }

        @NonNull
        Account asOfflineAccount() {
            if (TYPE_OFFLINE.equals(accountType)) return this;
            return offline(accountId.length() > 0 ? accountId : UUID.randomUUID().toString(), getBestDisplayName(),
                    CustomSkinStore.getOfflineUuidWithSkinModel(getBestDisplayName(), SkinModelType.fromId(offlineSkinModel)),
                    offlineSkinPath, offlineSkinModel);
        }

        public boolean isOfflineAccount() {
            return TYPE_OFFLINE.equals(accountType);
        }

        public boolean isMicrosoftAccount() {
            return TYPE_MICROSOFT.equals(accountType) || (!TYPE_OFFLINE.equals(accountType) && hasMinecraftSession());
        }

        public boolean hasMinecraftSession() {
            return notEmpty(minecraftAccessToken) && notEmpty(minecraftName) && notEmpty(minecraftUuid);
        }

        public boolean hasOfflineSkin() {
            return notEmpty(offlineSkinPath) && new File(offlineSkinPath).isFile();
        }

        @NonNull
        public String getBestDisplayName() {
            if (notEmpty(displayName)) return displayName;
            if (notEmpty(minecraftName)) return minecraftName;
            if (notEmpty(email)) return email;
            return isOfflineAccount() ? "Offline Player" : "Microsoft Player";
        }

        @NonNull
        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("accountType", accountType);
                json.put("accountId", accountId);
                json.put("email", email);
                json.put("displayName", displayName);
                json.put("idToken", idToken);
                json.put("accessToken", accessToken);
                json.put("refreshToken", refreshToken);
                json.put("minecraftAccessToken", minecraftAccessToken);
                json.put("minecraftName", minecraftName);
                json.put("minecraftUuid", minecraftUuid);
                json.put("xuid", xuid);
                json.put("skinUrl", skinUrl);
                json.put("skinVariant", skinVariant);
                json.put("offlineSkinPath", offlineSkinPath);
                json.put("offlineSkinModel", offlineSkinModel);
            } catch (JSONException ignored) {
            }
            return json;
        }

        @NonNull
        static Account fromJson(@NonNull JSONObject json) {
            String inferredType = json.optString("accountType", "");
            if (inferredType.length() == 0) {
                inferredType = notEmpty(json.optString("minecraftAccessToken", "")) ? TYPE_MICROSOFT : TYPE_OFFLINE;
            }

            String displayName = json.optString("displayName", "");
            if (displayName.length() == 0 && TYPE_MICROSOFT.equals(inferredType)) displayName = "Microsoft Player";

            String accountId = json.optString("accountId", "");
            if (TYPE_OFFLINE.equals(inferredType) && accountId.length() == 0) {
                accountId = UUID.randomUUID().toString();
            }

            return new Account(
                    inferredType,
                    accountId,
                    json.optString("email", ""),
                    displayName,
                    json.optString("idToken", ""),
                    json.optString("accessToken", ""),
                    json.optString("refreshToken", ""),
                    json.optString("minecraftAccessToken", ""),
                    json.optString("minecraftName", displayName),
                    json.optString("minecraftUuid", ""),
                    json.optString("xuid", ""),
                    json.optString("skinUrl", ""),
                    json.optString("skinVariant", "classic"),
                    json.optString("offlineSkinPath", ""),
                    json.optString("offlineSkinModel", "none")
            );
        }

        @NonNull
        public static String sanitizePlayerName(@NonNull String raw) {
            String cleaned = raw.trim().replaceAll("[^A-Za-z0-9_]", "");
            if (cleaned.length() < 3) return "Player";
            return cleaned.length() > 16 ? cleaned.substring(0, 16) : cleaned;
        }
    }
}

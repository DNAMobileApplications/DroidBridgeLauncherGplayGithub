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

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.data.AccountStore;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

/**
 * Compatibility multi-account registry for Microsoft/Minecraft accounts.
 *
 * AccountStore still owns the single active account used by launch. This registry
 * remembers additional Microsoft accounts and lets the settings screen switch one
 * into AccountStore before launch/refresh. It avoids a breaking AccountStore
 * migration while still giving DroidBridge a Modrinth-style account picker.
 */
public final class MicrosoftAccountRegistry {
    private static final String TAG = "MicrosoftAccounts";
    private static final String PREFS = "droidbridge_microsoft_accounts";
    private static final String KEY_ACCOUNTS = "accounts_json";

    private MicrosoftAccountRegistry() {
    }

    public static void save(@NonNull Context context, @Nullable AccountStore.Account account) {
        if (!isUsableMicrosoftAccount(account)) return;

        LinkedHashMap<String, AccountStore.Account> accounts = loadMap(context);
        accounts.put(getStableId(account), account);
        writeMap(context, accounts);
    }

    @NonNull
    public static ArrayList<AccountStore.Account> list(@NonNull Context context) {
        return new ArrayList<>(loadMap(context).values());
    }

    public static int count(@NonNull Context context) {
        return loadMap(context).size();
    }

    public static boolean hasAny(@NonNull Context context) {
        return count(context) > 0;
    }

    public static void remove(@NonNull Context context, @Nullable AccountStore.Account account) {
        if (account == null) return;
        remove(context, getStableId(account));
    }

    public static void remove(@NonNull Context context, @Nullable String stableId) {
        if (isBlank(stableId)) return;
        LinkedHashMap<String, AccountStore.Account> accounts = loadMap(context);
        accounts.remove(stableId);
        writeMap(context, accounts);
    }

    public static void clear(@NonNull Context context) {
        prefs(context).edit().remove(KEY_ACCOUNTS).apply();
    }

    public static boolean containsSame(@NonNull ArrayList<AccountStore.Account> accounts,
                                       @Nullable AccountStore.Account account) {
        if (account == null) return false;
        String wanted = getStableId(account);
        for (AccountStore.Account candidate : accounts) {
            if (wanted.equals(getStableId(candidate))) return true;
        }
        return false;
    }

    @NonNull
    public static String getStableId(@Nullable AccountStore.Account account) {
        if (account == null) return "";
        if (!isBlank(account.minecraftUuid)) return "uuid:" + clean(account.minecraftUuid);
        if (!isBlank(account.xuid)) return "xuid:" + clean(account.xuid);
        if (!isBlank(account.accountId)) return "id:" + clean(account.accountId);
        if (!isBlank(account.minecraftName)) return "name:" + clean(account.minecraftName).toLowerCase(Locale.ROOT);
        if (!isBlank(account.displayName)) return "display:" + clean(account.displayName).toLowerCase(Locale.ROOT);
        return "unknown:" + Integer.toHexString(account.hashCode());
    }

    public static boolean isSameAccount(@Nullable AccountStore.Account first,
                                        @Nullable AccountStore.Account second) {
        if (first == null || second == null) return false;
        return getStableId(first).equals(getStableId(second));
    }

    public static boolean isUsableMicrosoftAccount(@Nullable AccountStore.Account account) {
        return account != null
                && account.isMicrosoftAccount()
                && !isBlank(account.minecraftAccessToken)
                && !isBlank(account.minecraftName)
                && !isBlank(account.minecraftUuid);
    }

    @NonNull
    private static LinkedHashMap<String, AccountStore.Account> loadMap(@NonNull Context context) {
        LinkedHashMap<String, AccountStore.Account> accounts = new LinkedHashMap<>();
        String raw = prefs(context).getString(KEY_ACCOUNTS, "");
        if (isBlank(raw)) return accounts;

        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json == null) continue;
                AccountStore.Account account = fromJson(json);
                if (!isUsableMicrosoftAccount(account)) continue;
                accounts.put(getStableId(account), account);
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to read saved Microsoft account registry", throwable);
        }
        return accounts;
    }

    private static void writeMap(@NonNull Context context,
                                 @NonNull LinkedHashMap<String, AccountStore.Account> accounts) {
        JSONArray array = new JSONArray();
        for (AccountStore.Account account : accounts.values()) {
            if (!isUsableMicrosoftAccount(account)) continue;
            array.put(toJson(account));
        }
        prefs(context).edit().putString(KEY_ACCOUNTS, array.toString()).apply();
    }

    @NonNull
    private static JSONObject toJson(@NonNull AccountStore.Account account) {
        JSONObject json = new JSONObject();
        try {
            json.put("accountType", safe(account.accountType));
            json.put("accountId", safe(account.accountId));
            json.put("displayName", safe(account.displayName));
            json.put("email", safe(account.email));
            json.put("accessToken", safe(account.accessToken));
            json.put("refreshToken", safe(account.refreshToken));
            json.put("minecraftAccessToken", safe(account.minecraftAccessToken));
            json.put("minecraftName", safe(account.minecraftName));
            json.put("minecraftUuid", safe(account.minecraftUuid));
            json.put("xuid", safe(account.xuid));
            json.put("skinUrl", safe(account.skinUrl));
            json.put("skinVariant", safe(account.skinVariant));
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to serialize Microsoft account", throwable);
        }
        return json;
    }

    @Nullable
    private static AccountStore.Account fromJson(@NonNull JSONObject json) {
        try {
            return new AccountStore.Account(
                    AccountStore.Account.TYPE_MICROSOFT,
                    json.optString("accountId", ""),
                    json.optString("email", ""),
                    json.optString("displayName", ""),
                    json.optString("idToken", ""),
                    json.optString("accessToken", ""),
                    json.optString("refreshToken", ""),
                    json.optString("minecraftAccessToken", ""),
                    json.optString("minecraftName", json.optString("displayName", "Microsoft Player")),
                    json.optString("minecraftUuid", ""),
                    json.optString("xuid", ""),
                    json.optString("skinUrl", ""),
                    json.optString("skinVariant", "classic"),
                    "",
                    "none"
            );
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to deserialize Microsoft account", throwable);
            return null;
        }
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value;
    }

    @NonNull
    private static String clean(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }
}

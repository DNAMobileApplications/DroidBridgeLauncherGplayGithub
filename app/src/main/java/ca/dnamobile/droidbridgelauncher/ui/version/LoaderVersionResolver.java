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

package ca.dnamobile.droidbridgelauncher.ui.version;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
public final class LoaderVersionResolver {
    private static final String FABRIC_META = "https://meta.fabricmc.net/v2";
    private static final String FORGE_METADATA_URL = "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml";
    private static final String NEOFORGE_METADATA_URL = "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
    private static final String NEOFORGE_LEGACY_METADATA_URL = "https://maven.neoforged.net/releases/net/neoforged/forge/maven-metadata.xml";
    private static final String CLEANROOM_METADATA_URL = "https://hmcl-dev.github.io/metadata/cleanroom/index.json";
    private static final int BUFFER_SIZE = 64 * 1024;

    private LoaderVersionResolver() {
    }

    public static final class LoaderVersionOption {
        public final String displayName;
        @Nullable
        public final String loaderVersion;

        public LoaderVersionOption(@NonNull String displayName, @Nullable String loaderVersion) {
            this.displayName = displayName;
            this.loaderVersion = loaderVersion;
        }
    }

    @NonNull
    public static ArrayList<LoaderVersionOption> resolveVersions(
            @NonNull String loader,
            @NonNull String minecraftVersion
    ) throws Exception {
        String normalized = loader.trim().toLowerCase(Locale.ROOT);

        if ("fabric".equals(normalized)) {
            return resolveFabricVersions(minecraftVersion);
        }

        if ("forge".equals(normalized)) {
            return resolveForgeVersions(minecraftVersion);
        }

        if ("neoforge".equals(normalized)) {
            return resolveNeoForgeVersions(minecraftVersion);
        }

        if ("cleanroom".equals(normalized)) {
            return resolveCleanroomVersions(minecraftVersion);
        }

        ArrayList<LoaderVersionOption> vanilla = new ArrayList<>();
        vanilla.add(new LoaderVersionOption("Vanilla", ""));
        return vanilla;
    }

    @NonNull
    private static ArrayList<LoaderVersionOption> resolveCleanroomVersions(
            @NonNull String minecraftVersion
    ) throws Exception {
        ArrayList<LoaderVersionOption> result = new ArrayList<>();
        if (!CleanroomSupport.supportsMinecraftVersion(minecraftVersion)) return result;

        String body = downloadText(CLEANROOM_METADATA_URL).trim();
        JSONArray versions;

        if (body.startsWith("[")) {
            versions = new JSONArray(body);
        } else {
            JSONObject root = new JSONObject(body);
            versions = root.optJSONArray("versions");
            if (versions == null) versions = root.optJSONArray("items");
            if (versions == null) versions = root.optJSONArray("releases");
            if (versions == null) {
                throw new IllegalStateException("Cleanroom metadata did not contain a versions array.");
            }
        }

        for (int i = 0; i < versions.length(); i++) {
            String version = "";
            Object entry = versions.opt(i);

            if (entry instanceof JSONObject) {
                JSONObject object = (JSONObject) entry;
                version = object.optString("name", "");
                if (version.trim().isEmpty()) version = object.optString("version", "");
                if (version.trim().isEmpty()) version = object.optString("id", "");
            } else if (entry != null) {
                version = String.valueOf(entry);
            }

            version = version.trim();
            if (version.isEmpty()) continue;

            int javaMajor = CleanroomSupport.requiredJavaForVersion(version);
            result.add(new LoaderVersionOption(
                    version + "  • Java " + javaMajor,
                    version
            ));
        }

        return result;
    }

    @NonNull
    private static ArrayList<LoaderVersionOption> resolveFabricVersions(@NonNull String minecraftVersion) throws Exception {
        String url = FABRIC_META + "/versions/loader/" + encode(minecraftVersion);
        JSONArray array = new JSONArray(downloadText(url));

        ArrayList<LoaderVersionOption> stable = new ArrayList<>();
        ArrayList<LoaderVersionOption> unstable = new ArrayList<>();

        for (int i = 0; i < array.length(); i++) {
            JSONObject wrapper = array.optJSONObject(i);
            if (wrapper == null) continue;

            JSONObject loader = wrapper.optJSONObject("loader");
            if (loader == null) loader = wrapper;

            String version = loader.optString("version", "");
            if (version.trim().isEmpty()) continue;

            boolean isStable = loader.optBoolean("stable", false);
            LoaderVersionOption option = new LoaderVersionOption(
                    isStable ? version + "  • stable" : version,
                    version
            );

            if (isStable) stable.add(option);
            else unstable.add(option);
        }

        stable.addAll(unstable);
        return stable;
    }

    @NonNull
    private static ArrayList<LoaderVersionOption> resolveForgeVersions(@NonNull String minecraftVersion) throws Exception {
        ArrayList<String> versions = parseMavenVersions(downloadText(FORGE_METADATA_URL));
        String prefix = minecraftVersion + "-";

        ArrayList<String> filtered = new ArrayList<>();
        for (String version : versions) {
            if (version.startsWith(prefix)) filtered.add(version);
        }

        filtered.sort((first, second) -> compareBuildVersions(
                second.substring(prefix.length()),
                first.substring(prefix.length())
        ));

        ArrayList<LoaderVersionOption> result = new ArrayList<>();
        for (String fullVersion : filtered) {
            String loaderVersion = fullVersion.substring(prefix.length());
            result.add(new LoaderVersionOption(loaderVersion, loaderVersion));
        }
        return result;
    }

    @NonNull
    private static ArrayList<LoaderVersionOption> resolveNeoForgeVersions(@NonNull String minecraftVersion) throws Exception {
        ArrayList<LoaderVersionOption> result = new ArrayList<>();

        // Legacy NeoForged Forge for 1.20.1 uses full coordinates like 1.20.1-47.x.x.
        try {
            ArrayList<String> legacyVersions = parseMavenVersions(downloadText(NEOFORGE_LEGACY_METADATA_URL));
            String legacyPrefix = minecraftVersion + "-";
            ArrayList<String> filteredLegacy = new ArrayList<>();

            for (String version : legacyVersions) {
                if (version.startsWith(legacyPrefix)) filteredLegacy.add(version);
            }

            filteredLegacy.sort((first, second) -> compareBuildVersions(
                    second.substring(legacyPrefix.length()),
                    first.substring(legacyPrefix.length())
            ));

            for (String fullVersion : filteredLegacy) {
                String loaderVersion = fullVersion.substring(legacyPrefix.length());
                result.add(new LoaderVersionOption(loaderVersion + "  • NeoForged Forge", loaderVersion));
            }
        } catch (Throwable ignored) {
        }

        // Modern NeoForge versions are not always a simple prefix when Minecraft moves
        // past 1.x style numbers, so map each NeoForge version back to its game version.
        try {
            ArrayList<String> versions = parseMavenVersions(downloadText(NEOFORGE_METADATA_URL));
            ArrayList<String> filtered = new ArrayList<>();

            for (String version : versions) {
                if (minecraftVersion.equals(formatNeoForgeGameVersion(version))) {
                    filtered.add(version);
                }
            }

            filtered.sort((first, second) -> compareBuildVersions(second, first));

            for (String version : filtered) {
                result.add(new LoaderVersionOption(version, version));
            }
        } catch (Throwable throwable) {
            if (result.isEmpty()) {
                if (throwable instanceof Exception) throw (Exception) throwable;
                throw new Exception(throwable);
            }
        }

        return result;
    }

    @NonNull
    private static String formatNeoForgeGameVersion(@NonNull String neoForgeVersion) {
        if (neoForgeVersion.contains("1.20.1")) {
            return "1.20.1";
        }

        if (neoForgeVersion.startsWith("0.")) {
            String versionPart = neoForgeVersion.substring("0.".length()).split("-", 2)[0];
            int lastDot = versionPart.lastIndexOf('.');
            return lastDot > 0 ? versionPart.substring(0, lastDot) : versionPart;
        }

        String base = neoForgeVersion.split("-", 2)[0];
        String[] rawParts = base.split("\\.");
        ArrayList<Integer> parts = new ArrayList<>();
        for (String rawPart : rawParts) {
            if (rawPart == null || rawPart.trim().isEmpty()) continue;
            try {
                parts.add(Integer.parseInt(rawPart.trim()));
            } catch (NumberFormatException ignored) {
                return neoForgeVersion;
            }
        }

        if (parts.isEmpty()) return neoForgeVersion;

        int first = parts.get(0);
        int second = parts.size() > 1 ? parts.get(1) : 0;
        int third = parts.size() > 2 ? parts.get(2) : 0;

        if (first >= 25) {
            // 26.1.0.5-beta -> 26.1, 26.1.2.12-beta -> 26.1.2
            return third == 0 ? first + "." + second : first + "." + second + "." + third;
        }

        // 21.0.x -> 1.21, 21.1.x -> 1.21.1, 21.11.x -> 1.21.11
        return second == 0 ? "1." + first : "1." + first + "." + second;
    }

    @NonNull
    private static ArrayList<String> parseMavenVersions(@NonNull String xml) {
        ArrayList<String> result = new ArrayList<>();
        Matcher matcher = Pattern.compile("<version>([^<]+)</version>").matcher(xml);

        while (matcher.find()) {
            String version = matcher.group(1);
            if (version != null && !version.trim().isEmpty()) {
                result.add(version.trim());
            }
        }

        return result;
    }

    private static int compareBuildVersions(@NonNull String left, @NonNull String right) {
        int[] a = parseBuildParts(left);
        int[] b = parseBuildParts(right);
        int max = Math.max(a.length, b.length);

        for (int i = 0; i < max; i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av != bv) return Integer.compare(av, bv);
        }

        return left.compareToIgnoreCase(right);
    }

    @NonNull
    private static int[] parseBuildParts(@NonNull String value) {
        String[] parts = value.trim().split("[.+\\-]");
        ArrayList<Integer> numbers = new ArrayList<>();

        for (String part : parts) {
            try {
                numbers.add(Integer.parseInt(part));
            } catch (NumberFormatException ignored) {
            }
        }

        int[] out = new int[numbers.size()];
        for (int i = 0; i < numbers.size(); i++) out[i] = numbers.get(i);
        return out;
    }

    @NonNull
    private static String encode(@NonNull String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    @NonNull
    private static String downloadText(@NonNull String urlString) throws Exception {
        HttpURLConnection connection = openConnection(urlString);
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String body = readStream(stream);
        connection.disconnect();

        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + " " + body);
        }

        return body;
    }

    @NonNull
    private static HttpURLConnection openConnection(@NonNull String urlString) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(45000);
        connection.setRequestProperty("User-Agent", "DroidBridge/1.0");
        connection.setRequestMethod("GET");
        return connection;
    }

    @NonNull
    private static String readStream(@Nullable InputStream inputStream) throws Exception {
        if (inputStream == null) return "";
        try (InputStream input = inputStream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}

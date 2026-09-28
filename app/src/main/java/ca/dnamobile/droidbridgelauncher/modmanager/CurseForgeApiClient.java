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

package ca.dnamobile.droidbridgelauncher.modmanager;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

public final class CurseForgeApiClient {
    private static final String BASE_URL = "https://api.curseforge.com/v1";
    private static final String USER_AGENT = "DroidBridge/1.0 (Android Minecraft Launcher)";
    private static final int MINECRAFT_GAME_ID = 432;

    private final String apiKey;

    public static final class SearchResult {
        @NonNull
        public final ArrayList<ModrinthProject> hits;
        public final int offset;
        public final int limit;
        public final int totalHits;

        SearchResult(@NonNull ArrayList<ModrinthProject> hits, int offset, int limit, int totalHits) {
            this.hits = hits;
            this.offset = offset;
            this.limit = limit;
            this.totalHits = totalHits;
        }
    }

    public CurseForgeApiClient(@NonNull Context context) {
        this.apiKey = CurseForgeApiKeyProvider.resolve();
    }

    @NonNull
    public SearchResult searchProjects(
            @NonNull String query,
            @NonNull ModManagerContentType contentType,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            int limit,
            int offset,
            @NonNull String sort
    ) throws Exception {
        ensureApiKey();

        StringBuilder url = new StringBuilder(BASE_URL).append("/mods/search?");
        appendQuery(url, "gameId", String.valueOf(MINECRAFT_GAME_ID));
        appendQuery(url, "classId", String.valueOf(getClassId(contentType)));
        appendQuery(url, "index", String.valueOf(Math.max(0, offset)));
        appendQuery(url, "pageSize", String.valueOf(Math.max(1, Math.min(50, limit))));
        appendQuery(url, "sortField", String.valueOf(getSortField(sort)));
        appendQuery(url, "sortOrder", "desc");
        if (!isBlank(query)) appendQuery(url, "searchFilter", query.trim());
        if (!isBlank(minecraftVersion)) appendQuery(url, "gameVersion", minecraftVersion.trim());

        int loaderType = getModLoaderType(loader);
        if (contentType.isLoaderSpecific() && loaderType > 0) {
            appendQuery(url, "modLoaderType", String.valueOf(loaderType));
        }

        JSONObject root = new JSONObject(get(url.toString()));
        JSONArray data = root.optJSONArray("data");
        JSONObject pagination = root.optJSONObject("pagination");
        ArrayList<ModrinthProject> projects = new ArrayList<>();
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item != null) projects.add(parseProject(item));
            }
        }

        int total = pagination != null ? pagination.optInt("totalCount", projects.size()) : projects.size();
        int resultOffset = pagination != null ? pagination.optInt("index", offset) : offset;
        int resultLimit = pagination != null ? pagination.optInt("pageSize", limit) : limit;
        return new SearchResult(projects, resultOffset, resultLimit, total);
    }

    @NonNull
    public ModrinthProject getProject(@NonNull String projectId) throws Exception {
        ensureApiKey();
        JSONObject root = new JSONObject(get(BASE_URL + "/mods/" + encodePath(projectId)));
        JSONObject data = root.optJSONObject("data");
        if (data == null) throw new IllegalStateException("CurseForge project response is empty.");
        return parseProject(data);
    }

    @NonNull
    public String getProjectDescription(@NonNull String projectId) throws Exception {
        ensureApiKey();
        JSONObject root = new JSONObject(get(BASE_URL
                + "/mods/"
                + encodePath(projectId)
                + "/description"));
        return root.optString("data", "").trim();
    }

    @NonNull
    public ArrayList<ModrinthVersion> getProjectVersions(
            @NonNull String projectId,
            @NonNull ModManagerContentType contentType,
            @NonNull String minecraftVersion,
            @Nullable String loader
    ) throws Exception {
        ensureApiKey();

        StringBuilder url = new StringBuilder(BASE_URL)
                .append("/mods/")
                .append(encodePath(projectId))
                .append("/files?");
        appendQuery(url, "pageSize", "50");
        appendQuery(url, "index", "0");
        if (!isBlank(minecraftVersion)) appendQuery(url, "gameVersion", minecraftVersion.trim());

        int loaderType = getModLoaderType(loader);
        if (contentType.isLoaderSpecific() && loaderType > 0) {
            appendQuery(url, "modLoaderType", String.valueOf(loaderType));
        }

        JSONObject root = new JSONObject(get(url.toString()));
        JSONArray data = root.optJSONArray("data");
        ArrayList<ModrinthVersion> versions = new ArrayList<>();
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item != null) versions.add(parseVersion(projectId, item));
            }
        }
        return versions;
    }

    @NonNull
    public String getDownloadUrl(@NonNull String projectId, @NonNull String fileId) throws Exception {
        ensureApiKey();
        JSONObject root = new JSONObject(get(BASE_URL
                + "/mods/" + encodePath(projectId)
                + "/files/" + encodePath(fileId)
                + "/download-url"));
        String data = root.optString("data", "");
        if (isBlank(data)) throw new IllegalStateException("CurseForge did not provide a download URL.");
        return data.trim();
    }


    /**
     * Resolve a CurseForge file download URL even when the API intentionally leaves
     * downloadUrl blank or the /download-url endpoint refuses the request.
     *
     * CurseForge's public CDN layout is deterministic from the file id and filename.
     * DroidBridge already uses this same fallback for CurseForge modpacks; keeping it
     * here as well makes single-mod installs behave consistently instead of failing
     * with "No downloadable CurseForge file found" for otherwise public files.
     */
    @NonNull
    public String resolveDownloadUrl(
            @NonNull String projectId,
            @NonNull String fileId,
            @NonNull String fileName,
            @Nullable String directUrl
    ) {
        String direct = cleanDownloadUrl(directUrl);
        if (isHttpUrl(direct)) return direct;

        long numericFileId = parsePositiveLong(fileId);
        if (numericFileId > 0L && !isBlank(fileName)) {
            return buildCurseForgeCdnUrl(numericFileId, fileName);
        }

        if (!isBlank(projectId) && !isBlank(fileId)) {
            try {
                String endpoint = getDownloadUrl(projectId, fileId);
                if (isHttpUrl(endpoint)) return endpoint;
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    @NonNull
    private static String buildCurseForgeCdnUrl(long fileId, @NonNull String fileName) {
        long firstFolder = fileId / 1000L;
        long secondFolder = fileId % 1000L;
        String encodedName;
        try {
            encodedName = encodePath(fileName);
        } catch (Throwable ignored) {
            encodedName = fileName.replace(" ", "%20");
        }
        return "https://edge.forgecdn.net/files/"
                + firstFolder
                + "/"
                + String.format(Locale.US, "%03d", secondFolder)
                + "/"
                + encodedName;
    }

    private static long parsePositiveLong(@Nullable String value) {
        if (isBlank(value)) return -1L;
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0L ? parsed : -1L;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    @NonNull
    private static String cleanDownloadUrl(@Nullable String rawValue) {
        if (rawValue == null) return "";
        String value = rawValue.trim();
        if (value.isEmpty()) return "";
        if ("null".equalsIgnoreCase(value) || "<null>".equalsIgnoreCase(value)) return "";
        if ("\"null\"".equalsIgnoreCase(value) || "'null'".equalsIgnoreCase(value)) return "";
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return "null".equalsIgnoreCase(value) ? "" : value;
    }

    private static boolean isHttpUrl(@Nullable String value) {
        String url = cleanDownloadUrl(value);
        return url.startsWith("http://") || url.startsWith("https://");
    }

    public void downloadToFile(@NonNull String url, @NonNull File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Unable to create folder: " + parent.getAbsolutePath());
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("Download failed with HTTP " + code + ": " + url);
        }

        try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        } finally {
            connection.disconnect();
        }
    }

    @NonNull
    private ModrinthProject parseProject(@NonNull JSONObject object) {
        String id = String.valueOf(object.optLong("id", 0L));
        String slug = object.optString("slug", id);
        String title = object.optString("name", slug);
        String description = object.optString("summary", "");
        long downloads = object.optLong("downloadCount", 0L);
        String dateModified = object.optString("dateModified", null);
        String websiteUrl = null;
        JSONObject links = object.optJSONObject("links");
        if (links != null) websiteUrl = links.optString("websiteUrl", null);

        String iconUrl = null;
        JSONObject logo = object.optJSONObject("logo");
        if (logo != null) {
            iconUrl = logo.optString("thumbnailUrl", logo.optString("url", null));
        }

        ArrayList<String> authors = new ArrayList<>();
        JSONArray authorArray = object.optJSONArray("authors");
        if (authorArray != null) {
            for (int i = 0; i < authorArray.length(); i++) {
                JSONObject author = authorArray.optJSONObject(i);
                if (author == null) continue;
                String name = author.optString("name", "");
                if (!isBlank(name)) authors.add(name);
            }
        }

        ArrayList<String> categories = new ArrayList<>();
        JSONArray categoryArray = object.optJSONArray("categories");
        if (categoryArray != null) {
            for (int i = 0; i < categoryArray.length(); i++) {
                JSONObject category = categoryArray.optJSONObject(i);
                if (category == null) continue;
                String name = category.optString("name", category.optString("slug", ""));
                if (!isBlank(name)) categories.add(name);
            }
        }

        ArrayList<String> gallery = new ArrayList<>();
        JSONArray screenshots = object.optJSONArray("screenshots");
        if (screenshots != null) {
            for (int i = 0; i < screenshots.length(); i++) {
                JSONObject screenshot = screenshots.optJSONObject(i);
                if (screenshot == null) continue;
                String url = screenshot.optString("url", screenshot.optString("thumbnailUrl", ""));
                if (!isBlank(url)) gallery.add(url);
            }
        }

        return new ModrinthProject(
                id,
                slug,
                title,
                join(authors, ", "),
                description,
                description,
                iconUrl,
                getProjectType(object.optInt("classId", 0)),
                downloads,
                0L,
                dateModified,
                categories,
                gallery,
                ModManagerSource.CURSEFORGE,
                websiteUrl
        );
    }

    @NonNull
    private ModrinthVersion parseVersion(@NonNull String projectId, @NonNull JSONObject object) throws Exception {
        String id = String.valueOf(object.optLong("id", 0L));
        String displayName = object.optString("displayName", object.optString("fileName", id));
        String fileName = object.optString("fileName", displayName);
        String downloadUrl = resolveDownloadUrl(
                projectId,
                id,
                fileName,
                object.optString("downloadUrl", "")
        );

        ArrayList<String> gameVersions = readStringArray(object.optJSONArray("gameVersions"));
        ArrayList<String> loaders = new ArrayList<>();
        // Older CurseForge files often put the loader label in gameVersions rather
        // than sortableGameVersions.modLoader. Preserve that metadata so dependency
        // selection can reject Forge/Fabric mismatches locally as well as server-side.
        for (String gameVersionLabel : gameVersions) {
            String normalized = normalizeLoaderLabel(gameVersionLabel);
            if (!normalized.isEmpty() && !loaders.contains(normalized)) loaders.add(normalized);
        }
        JSONArray indexes = object.optJSONArray("sortableGameVersions");
        if (indexes != null) {
            for (int i = 0; i < indexes.length(); i++) {
                JSONObject index = indexes.optJSONObject(i);
                if (index == null) continue;
                int modLoader = index.optInt("modLoader", 0);
                String loader = modLoaderToString(modLoader);
                if (!isBlank(loader) && !loaders.contains(loader)) loaders.add(loader);
            }
        }

        ArrayList<ModrinthDependency> dependencies = new ArrayList<>();
        JSONArray depArray = object.optJSONArray("dependencies");
        if (depArray != null) {
            for (int i = 0; i < depArray.length(); i++) {
                JSONObject dep = depArray.optJSONObject(i);
                if (dep == null) continue;
                String relation = dep.optInt("relationType", 0) == 3 ? "required" : "optional";
                long dependencyModId = dep.optLong("modId", 0L);
                dependencies.add(new ModrinthDependency(null,
                        dependencyModId > 0L ? String.valueOf(dependencyModId) : null,
                        null,
                        relation));
            }
        }

        String sha1 = null;
        JSONArray hashes = object.optJSONArray("hashes");
        if (hashes != null) {
            for (int i = 0; i < hashes.length(); i++) {
                JSONObject hash = hashes.optJSONObject(i);
                if (hash == null) continue;
                int algo = hash.optInt("algo", 0);
                if (algo == 1) {
                    sha1 = hash.optString("value", null);
                    break;
                }
            }
        }

        ArrayList<ModrinthFile> files = new ArrayList<>();
        files.add(new ModrinthFile(downloadUrl, fileName, sha1, true, object.optLong("fileLength", object.optLong("fileSizeOnDisk", 0L))));

        return new ModrinthVersion(
                id,
                projectId,
                displayName,
                displayName,
                releaseTypeToString(object.optInt("releaseType", 1)),
                object.optString("fileDate", null),
                null,
                object.optLong("downloadCount", 0L),
                gameVersions,
                loaders,
                dependencies,
                files
        );
    }

    @NonNull
    private String get(@NonNull String url) throws Exception {
        HttpURLConnection connection = openConnection(url);
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String body = readText(stream);
        connection.disconnect();

        if (code < 200 || code >= 300) {
            throw new IllegalStateException("CurseForge API HTTP " + code + ": " + body);
        }
        return body;
    }

    @NonNull
    private HttpURLConnection openConnection(@NonNull String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("x-api-key", apiKey);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        return connection;
    }

    private void ensureApiKey() {
        if (isBlank(apiKey)) {
            throw new IllegalStateException("Missing CurseForge API key. Add res/values/curseforge_api_key.xml or BuildConfig.CURSEFORGE_API_KEY.");
        }
    }

    private static int getClassId(@NonNull ModManagerContentType type) {
        switch (type) {
            case RESOURCEPACKS:
                return 12;
            case DATAPACKS:
                return 6945;
            case SHADERPACKS:
                return 6552;
            case MODS:
            default:
                return 6;
        }
    }

    @NonNull
    private static String getProjectType(int classId) {
        if (classId == 12) return "resourcepack";
        if (classId == 6945) return "datapack";
        if (classId == 6552) return "shader";
        return "mod";
    }


    @NonNull
    private static String normalizeLoaderLabel(@Nullable String raw) {
        if (raw == null) return "";
        String value = raw.trim().toLowerCase(Locale.US);
        if (value.equals("forge") || value.startsWith("forge ") || value.endsWith(" forge")) return "forge";
        if (value.equals("fabric") || value.startsWith("fabric ") || value.endsWith(" fabric")) return "fabric";
        if (value.equals("quilt") || value.startsWith("quilt ") || value.endsWith(" quilt")) return "quilt";
        if (value.equals("neoforge") || value.equals("neo forge") || value.startsWith("neoforge ") || value.endsWith(" neoforge")) return "neoforge";
        return "";
    }

    private static int getSortField(@NonNull String sort) {
        String value = sort.toLowerCase(Locale.US);
        if (value.contains("updated") || value.contains("date")) return 3;
        if (value.contains("name")) return 4;
        if (value.contains("download")) return 6;
        return 2;
    }

    public static int getModLoaderType(@Nullable String loader) {
        if (loader == null) return 0;
        String value = loader.toLowerCase(Locale.US);
        if (value.contains("cleanroom")) return 1;
        if (value.contains("neoforge") || value.contains("neo forge")) return 6;
        if (value.contains("quilt")) return 5;
        if (value.contains("fabric")) return 4;
        if (value.contains("forge")) return 1;
        return 0;
    }

    @NonNull
    private static String modLoaderToString(int value) {
        switch (value) {
            case 1:
                return "forge";
            case 4:
                return "fabric";
            case 5:
                return "quilt";
            case 6:
                return "neoforge";
            default:
                return "";
        }
    }

    @NonNull
    private static String releaseTypeToString(int releaseType) {
        switch (releaseType) {
            case 2:
                return "beta";
            case 3:
                return "alpha";
            case 1:
            default:
                return "release";
        }
    }

    @NonNull
    private static ArrayList<String> readStringArray(@Nullable JSONArray array) {
        ArrayList<String> values = new ArrayList<>();
        if (array == null) return values;
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "");
            if (!isBlank(value)) values.add(value);
        }
        return values;
    }

    @NonNull
    private static String join(@NonNull ArrayList<String> values, @NonNull String separator) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (isBlank(value)) continue;
            if (builder.length() > 0) builder.append(separator);
            builder.append(value);
        }
        return builder.toString();
    }

    private static void appendQuery(@NonNull StringBuilder builder, @NonNull String key, @NonNull String value) throws Exception {
        if (builder.charAt(builder.length() - 1) != '?' && builder.charAt(builder.length() - 1) != '&') builder.append('&');
        builder.append(URLEncoder.encode(key, "UTF-8"));
        builder.append('=');
        builder.append(URLEncoder.encode(value, "UTF-8"));
    }

    @NonNull
    private static String encodePath(@NonNull String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    @NonNull
    private static String readText(@Nullable InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = source.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty() || "null".equalsIgnoreCase(value.trim());
    }
}

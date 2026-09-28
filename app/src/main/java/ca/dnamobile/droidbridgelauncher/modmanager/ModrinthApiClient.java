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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ModrinthApiClient {
    private static final String BASE_URL = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "DroidBridge/1.0 (Android Minecraft Launcher)";

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

    @NonNull
    public SearchResult searchProjects(
            @NonNull String query,
            @NonNull ModManagerContentType contentType,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            int limit,
            int offset,
            @NonNull String index
    ) throws Exception {
        StringBuilder url = new StringBuilder(BASE_URL).append("/search?");
        appendQuery(url, "query", query);
        appendQuery(url, "limit", String.valueOf(Math.max(1, Math.min(100, limit))));
        appendQuery(url, "offset", String.valueOf(Math.max(0, offset)));
        appendQuery(url, "index", index);
        appendQuery(url, "facets", buildSearchFacets(contentType, minecraftVersion, loader).toString());

        JSONObject object = new JSONObject(get(url.toString()));
        JSONArray hits = object.optJSONArray("hits");
        ArrayList<ModrinthProject> projects = new ArrayList<>();
        if (hits != null) {
            for (int i = 0; i < hits.length(); i++) {
                projects.add(parseProject(hits.getJSONObject(i), false));
            }
        }

        return new SearchResult(
                projects,
                object.optInt("offset", offset),
                object.optInt("limit", limit),
                object.optInt("total_hits", projects.size())
        );
    }

    @NonNull
    public ModrinthProject getProject(@NonNull String projectIdOrSlug) throws Exception {
        JSONObject object = new JSONObject(get(BASE_URL + "/project/" + encodePath(projectIdOrSlug)));
        return parseProject(object, true);
    }

    @NonNull
    public ModrinthProject getProjectWithFallback(
            @NonNull String projectIdOrSlug,
            @Nullable String fallbackSlug
    ) throws Exception {
        try {
            return getProject(projectIdOrSlug);
        } catch (Throwable first) {
            if (isBlank(fallbackSlug) || projectIdOrSlug.equals(fallbackSlug.trim())) {
                throw first;
            }
            return getProject(fallbackSlug.trim());
        }
    }

    @NonNull
    public ArrayList<ModrinthVersion> getProjectVersions(
            @NonNull String projectIdOrSlug,
            @NonNull ModManagerContentType contentType,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            boolean includeChangelog
    ) throws Exception {
        StringBuilder url = new StringBuilder(BASE_URL)
                .append("/project/")
                .append(encodePath(projectIdOrSlug))
                .append("/version?");
        appendQuery(url, "include_changelog", includeChangelog ? "true" : "false");

        JSONArray gameVersions = new JSONArray();
        if (!isBlank(minecraftVersion)) gameVersions.put(minecraftVersion.trim());
        if (gameVersions.length() > 0) appendQuery(url, "game_versions", gameVersions.toString());

        JSONArray loaders = buildVersionLoaders(contentType, loader);
        if (loaders.length() > 0) appendQuery(url, "loaders", loaders.toString());

        JSONArray array = new JSONArray(get(url.toString()));
        ArrayList<ModrinthVersion> versions = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            versions.add(parseVersion(array.getJSONObject(i)));
        }
        return versions;
    }

    @NonNull
    public ArrayList<ModrinthVersion> getProjectVersionsWithFallback(
            @NonNull ModrinthProject project,
            @NonNull ModManagerContentType contentType,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            boolean includeChangelog
    ) throws Exception {
        try {
            return getProjectVersions(project.projectId, contentType, minecraftVersion, loader, includeChangelog);
        } catch (Throwable first) {
            if (isBlank(project.slug) || project.projectId.equals(project.slug.trim())) {
                throw first;
            }
            return getProjectVersions(project.slug.trim(), contentType, minecraftVersion, loader, includeChangelog);
        }
    }

    @NonNull
    public ModrinthVersion getLatestVersionFromFileHash(
            @NonNull String sha1,
            @NonNull ModManagerContentType contentType,
            @NonNull String minecraftVersion,
            @Nullable String loader
    ) throws Exception {
        JSONObject request = new JSONObject();

        JSONArray gameVersions = new JSONArray();
        if (!isBlank(minecraftVersion)) gameVersions.put(minecraftVersion.trim());
        request.put("game_versions", gameVersions);

        JSONArray loaders = buildUpdateLoaders(contentType, loader);
        request.put("loaders", loaders);

        String url = BASE_URL
                + "/version_file/"
                + encodePath(sha1.trim().toLowerCase(Locale.US))
                + "/update?algorithm=sha1";
        JSONObject object = new JSONObject(post(url, request));
        return parseVersion(object);
    }

    @NonNull
    public ModrinthVersion getVersion(@NonNull String versionId) throws Exception {
        JSONObject object = new JSONObject(get(BASE_URL + "/version/" + encodePath(versionId)));
        return parseVersion(object);
    }

    public void downloadToFile(@NonNull String url, @NonNull File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Unable to create folder: " + parent.getAbsolutePath());
        }

        HttpURLConnection connection = openConnection(url);
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("Download failed with HTTP " + code + ": " + url);
        }

        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        } finally {
            connection.disconnect();
        }
    }

    @NonNull
    private String get(@NonNull String url) throws Exception {
        HttpURLConnection connection = openConnection(url);
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String body = readText(stream);
        connection.disconnect();

        if (code < 200 || code >= 300) {
            throw new IllegalStateException("Modrinth API HTTP " + code + ": " + body);
        }
        return body;
    }

    @NonNull
    private String post(@NonNull String url, @NonNull JSONObject body) throws Exception {
        HttpURLConnection connection = openConnection(url);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String response = readText(stream);
        connection.disconnect();

        if (code < 200 || code >= 300) {
            throw new IllegalStateException("Modrinth API HTTP " + code + ": " + response);
        }
        return response;
    }

    @NonNull
    private HttpURLConnection openConnection(@NonNull String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        return connection;
    }

    @NonNull
    private JSONArray buildSearchFacets(
            @NonNull ModManagerContentType contentType,
            @NonNull String minecraftVersion,
            @Nullable String loader
    ) {
        JSONArray facets = new JSONArray();
        facets.put(new JSONArray().put("project_type:" + contentType.getModrinthProjectType()));
        if (!isBlank(minecraftVersion)) {
            facets.put(new JSONArray().put("versions:" + minecraftVersion.trim()));
        }

        String loaderSlug = normalizeLoader(loader);
        if (contentType.isLoaderSpecific() && !isBlank(loaderSlug) && !"vanilla".equals(loaderSlug)) {
            facets.put(new JSONArray().put("categories:" + loaderSlug));
        }
        return facets;
    }

    @NonNull
    private JSONArray buildVersionLoaders(@NonNull ModManagerContentType contentType, @Nullable String loader) {
        JSONArray array = new JSONArray();
        String loaderSlug = normalizeLoader(loader);
        if (contentType.isLoaderSpecific() && !isBlank(loaderSlug) && !"vanilla".equals(loaderSlug)) {
            array.put(loaderSlug);
        }
        return array;
    }

    @NonNull
    private JSONArray buildUpdateLoaders(@NonNull ModManagerContentType contentType, @Nullable String loader) {
        JSONArray array = new JSONArray();
        String loaderSlug = normalizeLoader(loader);
        if (contentType.isLoaderSpecific()) {
            if (!isBlank(loaderSlug) && !"vanilla".equals(loaderSlug)) {
                array.put(loaderSlug);
            }
        } else if (contentType == ModManagerContentType.RESOURCEPACKS) {
            array.put("minecraft");
        } else if (contentType == ModManagerContentType.DATAPACKS) {
            array.put("datapack");
        } else if (contentType == ModManagerContentType.SHADERPACKS) {
            array.put("iris");
            array.put("optifine");
            array.put("canvas");
            array.put("minecraft");
        }
        return array;
    }

    @NonNull
    public static String normalizeLoader(@Nullable String loader) {
        if (loader == null) return "";
        String value = loader.trim().toLowerCase(Locale.US);
        if (value.contains("cleanroom")) return "forge";
        if (value.contains("neoforge") || value.contains("neo forge")) return "neoforge";
        if (value.contains("forge")) return "forge";
        if (value.contains("fabric")) return "fabric";
        if (value.contains("quilt")) return "quilt";
        if (value.contains("vanilla")) return "vanilla";
        return value.replace(' ', '-');
    }

    @NonNull
    private static ModrinthProject parseProject(@NonNull JSONObject object, boolean fullProject) {
        String projectId = object.optString("project_id", object.optString("id", ""));
        String slug = object.optString("slug", projectId);
        String title = object.optString("title", slug);
        String author = object.optString("author", object.optString("team", ""));
        String description = object.optString("description", object.optString("summary", ""));
        String body = object.optString("body", null);
        String iconUrl = object.optString("icon_url", null);
        String projectType = object.optString("project_type", "mod");
        long downloads = object.optLong("downloads", 0L);
        long followers = object.optLong("follows", object.optLong("followers", 0L));
        String dateModified = object.optString("date_modified", object.optString("updated", null));

        ArrayList<String> categories = readStringArray(object.optJSONArray("categories"));
        JSONArray additional = object.optJSONArray("additional_categories");
        if (additional != null) categories.addAll(readStringArray(additional));
        ArrayList<String> galleryUrls = new ArrayList<>();
        JSONArray gallery = object.optJSONArray("gallery");
        if (gallery != null) {
            for (int i = 0; i < gallery.length(); i++) {
                JSONObject item = gallery.optJSONObject(i);
                if (item == null) continue;
                String url = item.optString("url", "");
                if (!url.trim().isEmpty()) galleryUrls.add(url);
            }
        }

        if (!fullProject && categories.isEmpty()) {
            categories.add(projectType);
        }

        return new ModrinthProject(
                projectId,
                slug,
                title,
                author,
                description,
                body,
                iconUrl,
                projectType,
                downloads,
                followers,
                dateModified,
                categories,
                galleryUrls
        );
    }

    @NonNull
    private static ModrinthVersion parseVersion(@NonNull JSONObject object) {
        ArrayList<ModrinthDependency> dependencies = new ArrayList<>();
        JSONArray dependencyArray = object.optJSONArray("dependencies");
        if (dependencyArray != null) {
            for (int i = 0; i < dependencyArray.length(); i++) {
                JSONObject dep = dependencyArray.optJSONObject(i);
                if (dep == null) continue;
                dependencies.add(new ModrinthDependency(
                        dep.optString("version_id", null),
                        dep.optString("project_id", null),
                        dep.optString("file_name", null),
                        dep.optString("dependency_type", "required")
                ));
            }
        }

        ArrayList<ModrinthFile> files = new ArrayList<>();
        JSONArray fileArray = object.optJSONArray("files");
        if (fileArray != null) {
            for (int i = 0; i < fileArray.length(); i++) {
                JSONObject file = fileArray.optJSONObject(i);
                if (file == null) continue;
                JSONObject hashes = file.optJSONObject("hashes");
                files.add(new ModrinthFile(
                        file.optString("url", ""),
                        file.optString("filename", "download.jar"),
                        hashes != null ? hashes.optString("sha1", null) : null,
                        file.optBoolean("primary", false),
                        file.optLong("size", 0L)
                ));
            }
        }

        return new ModrinthVersion(
                object.optString("id", ""),
                object.optString("project_id", ""),
                object.optString("name", object.optString("version_number", "")),
                object.optString("version_number", ""),
                object.optString("version_type", "release"),
                object.optString("date_published", null),
                object.optString("changelog", null),
                object.optLong("downloads", 0L),
                readStringArray(object.optJSONArray("game_versions")),
                readStringArray(object.optJSONArray("loaders")),
                dependencies,
                files
        );
    }

    @NonNull
    private static ArrayList<String> readStringArray(@Nullable JSONArray array) {
        ArrayList<String> values = new ArrayList<>();
        if (array == null) return values;
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "");
            if (!value.trim().isEmpty()) values.add(value);
        }
        return values;
    }

    @NonNull
    private static String readText(@Nullable InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void appendQuery(@NonNull StringBuilder builder, @NonNull String key, @NonNull String value) throws Exception {
        if (builder.charAt(builder.length() - 1) != '?' && builder.charAt(builder.length() - 1) != '&') {
            builder.append('&');
        }
        builder.append(URLEncoder.encode(key, "UTF-8"))
                .append('=')
                .append(URLEncoder.encode(value, "UTF-8"));
        builder.append('&');
    }

    @NonNull
    private static String encodePath(@NonNull String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }
}

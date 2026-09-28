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
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Locale;

/** Search-only bridge for modpacks, independent from the existing mod/resource browser clients. */
public final class ModpackSearchApiClient {
    private static final int CURSEFORGE_MINECRAFT_GAME_ID = 432;
    private static final int CURSEFORGE_MODPACK_CLASS_ID = 4471;
    private static final String FEATURED_OPTIMOBILE_QUERY = "OptiMobile";
    private static final String FEATURED_OPTIMOBILE_FABRIC_CURSEFORGE_PROJECT_ID = "1456510";
    private static final String FEATURED_OPTIMOBILE_FORGE_CURSEFORGE_PROJECT_ID = "1385364";
    private static final String FEATURED_VULKAN_DROID_MODRINTH_PROJECT_ID = "PXY8IEra";
    private static final String FEATURED_VULKAN_DROID_CURSEFORGE_PROJECT_ID = "1421646";
    private static final String FEATURED_VULKAN_DROID_SLUG = "vulkan-droid";
    private static final String FEATURED_VULKAN_DROID_AUTHOR = "MisterDNEH";


    private ModpackSearchApiClient() {
    }

    public static final class SearchResult {
        @NonNull public final ArrayList<ModrinthProject> hits;
        public final int totalHits;

        public SearchResult(@NonNull ArrayList<ModrinthProject> hits, int totalHits) {
            this.hits = hits;
            this.totalHits = totalHits;
        }
    }

    @NonNull
    public static SearchResult search(
            @NonNull Context context,
            @NonNull ModManagerSource source,
            @NonNull String query,
            @Nullable String gameVersion,
            @Nullable String loader,
            int limit,
            int offset
    ) throws Exception {
        return search(context, source, query, gameVersion, loader, limit, offset, null);
    }

    @NonNull
    public static SearchResult search(
            @NonNull Context context,
            @NonNull ModManagerSource source,
            @NonNull String query,
            @Nullable String gameVersion,
            @Nullable String loader,
            int limit,
            int offset,
            @Nullable String sortKey
    ) throws Exception {
        // Normal search is intentionally neutral. Developer picks now live in the
        // user-controlled Favourites section instead of being injected/pinned at
        // the top of every Modrinth/CurseForge result page.
        if (source == ModManagerSource.CURSEFORGE) {
            return searchCurseForge(context, query, gameVersion, loader, limit, offset, sortKey, false);
        }
        return searchModrinth(query, gameVersion, loader, limit, offset, sortKey, false);
    }

    /** Resolve one persisted favourite to a fresh provider project row. */
    @NonNull
    public static ModrinthProject resolveFavourite(
            @NonNull Context context,
            @NonNull ModManagerSource source,
            @NonNull ModpackFavouritesStore.FavouriteRecord record,
            @Nullable String loader
    ) throws Exception {
        if (ModpackFavouritesStore.KEY_DEVELOPER_OPTIMOBILE.equals(record.key)) {
            if (source == ModManagerSource.CURSEFORGE) {
                ArrayList<String> ids = new ArrayList<>();
                // Prefer the active loader's exact OptiMobile project first, then
                // fall back to the stored record. This avoids showing the Fabric
                // favourite when the modpack browser is filtered for Forge.
                for (String id : featuredOptiMobileCurseForgeProjectIds(loader)) {
                    if (!ids.contains(id)) ids.add(id);
                }
                if (!isBlank(record.projectId) && !ids.contains(record.projectId.trim())) {
                    ids.add(record.projectId.trim());
                }
                for (String id : ids) {
                    try {
                        ModrinthProject project = getCurseForgeProject(context, id);
                        if (isOptiMobileModpack(project)) return project;
                    } catch (Throwable ignored) {
                    }
                }
                throw new IOException("Unable to resolve favourite OptiMobile project on CurseForge.");
            }

            ArrayList<String> slugs = new ArrayList<>();
            for (String slug : featuredOptiMobileModrinthSlugs(loader)) {
                if (!slugs.contains(slug)) slugs.add(slug);
            }
            if (!isBlank(record.slug) && !slugs.contains(record.slug.trim())) {
                slugs.add(record.slug.trim());
            }
            for (String slug : slugs) {
                try {
                    ModrinthProject project = new ModrinthApiClient().getProject(slug);
                    if (isOptiMobileModpack(project)) return project;
                } catch (Throwable ignored) {
                }
            }
            throw new IOException("Unable to resolve favourite OptiMobile project on Modrinth.");
        }

        if (ModpackFavouritesStore.KEY_DEVELOPER_VULKAN_DROID.equals(record.key)) {
            if (source == ModManagerSource.CURSEFORGE) {
                String id = isBlank(record.projectId)
                        ? FEATURED_VULKAN_DROID_CURSEFORGE_PROJECT_ID
                        : record.projectId.trim();
                ModrinthProject project = getCurseForgeProject(context, id);
                applyFeaturedMetadata(project);
                return project;
            }
            ModrinthProject project = new ModrinthApiClient().getProjectWithFallback(
                    isBlank(record.projectId) ? FEATURED_VULKAN_DROID_MODRINTH_PROJECT_ID : record.projectId.trim(),
                    isBlank(record.slug) ? FEATURED_VULKAN_DROID_SLUG : record.slug.trim()
            );
            applyFeaturedMetadata(project);
            return project;
        }

        if (source == ModManagerSource.CURSEFORGE) {
            if (isBlank(record.projectId)) {
                throw new IOException("Favourite CurseForge project is missing its project id.");
            }
            return getCurseForgeProject(context, record.projectId.trim());
        }

        String primary = !isBlank(record.projectId) ? record.projectId.trim() : record.slug.trim();
        String fallback = !isBlank(record.projectId) ? record.slug.trim() : null;
        if (isBlank(primary)) throw new IOException("Favourite Modrinth project is missing its id and slug.");
        return new ModrinthApiClient().getProjectWithFallback(primary, fallback);
    }

    @NonNull
    private static SearchResult searchModrinth(
            @NonNull String query,
            @Nullable String gameVersion,
            @Nullable String loader,
            int limit,
            int offset,
            @Nullable String sortKey,
            boolean includeFeatured
    ) throws Exception {
        JSONArray facets = new JSONArray();
        facets.put(new JSONArray().put("project_type:modpack"));
        if (!isBlank(gameVersion)) facets.put(new JSONArray().put("versions:" + gameVersion.trim()));
        String normalizedLoader = normalizeLoader(loader);
        if (!isBlank(normalizedLoader)) facets.put(new JSONArray().put("categories:" + normalizedLoader));

        String url = "https://api.modrinth.com/v2/search?limit=" + limit
                + "&offset=" + offset
                + "&index=" + resolveModrinthSortIndex(query, sortKey)
                + "&facets=" + urlEncode(facets.toString());
        if (!query.trim().isEmpty()) url += "&query=" + urlEncode(query.trim());

        JSONObject response = new JSONObject(httpGetString(url, null));
        JSONArray hitsJson = response.optJSONArray("hits");
        ArrayList<ModrinthProject> hits = new ArrayList<>();
        for (int i = 0; hitsJson != null && i < hitsJson.length(); i++) {
            JSONObject item = hitsJson.optJSONObject(i);
            if (item == null) continue;
            ArrayList<String> categories = stringArray(item.optJSONArray("categories"));
            categories.add("modpack");
            hits.add(new ModrinthProject(
                    item.optString("project_id", ""),
                    item.optString("slug", ""),
                    item.optString("title", "Modpack"),
                    item.optString("description", ""),
                    item.optString("author", null),
                    item.optString("icon_url", null),
                    categories,
                    item.optLong("downloads", 0L),
                    item.optLong("follows", 0L),
                    item.optString("date_modified", null),
                    ModManagerSource.MODRINTH
            ));
        }
        if (includeFeatured && offset == 0) {
            ensureFeaturedModrinthModpack(gameVersion, loader, hits, limit);
            pinFeaturedModpackFirst(hits, limit);
        }
        return new SearchResult(hits, response.optInt("total_hits", hits.size()));
    }

    @NonNull
    private static SearchResult searchCurseForge(
            @NonNull Context context,
            @NonNull String query,
            @Nullable String gameVersion,
            @Nullable String loader,
            int limit,
            int offset,
            @Nullable String sortKey,
            boolean includeFeatured
    ) throws Exception {
        String apiKey = CurseForgeApiKeyProvider.resolve();
        if (isBlank(apiKey)) throw new IOException("Missing CurseForge API key.");

        String normalizedSortKey = normalizeSortKey(sortKey);
        StringBuilder url = new StringBuilder("https://api.curseforge.com/v1/mods/search?gameId=")
                .append(CURSEFORGE_MINECRAFT_GAME_ID)
                .append("&classId=")
                .append(CURSEFORGE_MODPACK_CLASS_ID)
                .append("&pageSize=")
                .append(limit)
                .append("&index=")
                .append(offset)
                .append("&sortField=")
                .append(resolveCurseForgeSortField(normalizedSortKey))
                .append("&sortOrder=")
                .append(resolveCurseForgeSortOrder(normalizedSortKey));
        if (!query.trim().isEmpty()) url.append("&searchFilter=").append(urlEncode(query.trim()));
        if (!isBlank(gameVersion)) url.append("&gameVersion=").append(urlEncode(gameVersion.trim()));
        int loaderType = curseForgeLoaderType(loader);
        if (loaderType > 0) url.append("&modLoaderType=").append(loaderType);

        JSONObject response = new JSONObject(httpGetString(url.toString(), apiKey));
        JSONArray data = response.optJSONArray("data");
        ArrayList<ModrinthProject> hits = new ArrayList<>();
        for (int i = 0; data != null && i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) continue;
            hits.add(parseCurseForgeProject(item));
        }
        if (includeFeatured && offset == 0) {
            ensureFeaturedCurseForgeModpack(context, gameVersion, loader, hits, limit);
            pinFeaturedModpackFirst(hits, limit);
        }

        JSONObject pagination = response.optJSONObject("pagination");
        int total = pagination == null ? hits.size() : pagination.optInt("totalCount", hits.size());
        return new SearchResult(hits, total);
    }

    private static void ensureFeaturedModrinthModpack(
            @Nullable String gameVersion,
            @Nullable String loader,
            @NonNull ArrayList<ModrinthProject> hits,
            int limit
    ) {
        ensureFeaturedOptiMobileModrinth(gameVersion, loader, hits, limit);
        ensureFeaturedVulkanDroidModrinth(hits);
    }

    private static void ensureFeaturedOptiMobileModrinth(
            @Nullable String gameVersion,
            @Nullable String loader,
            @NonNull ArrayList<ModrinthProject> hits,
            int limit
    ) {
        if (containsOptiMobileModpack(hits)) return;

        // Do not trust a loose "OptiMobile" search by itself. Modrinth can
        // return similarly named forks such as "OptiMobile+" before the real
        // featured pack. Try exact known slugs first, then fall back to the
        // search endpoint but still only accept an exact OptiMobile match.
        for (String slug : featuredOptiMobileModrinthSlugs(loader)) {
            try {
                ModrinthProject project = new ModrinthApiClient().getProject(slug);
                if (isOptiMobileModpack(project)) {
                    hits.add(0, project);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            SearchResult featuredResult = searchModrinth(
                    FEATURED_OPTIMOBILE_QUERY,
                    gameVersion,
                    loader,
                    Math.max(5, Math.min(10, Math.max(1, limit))),
                    0,
                    "downloads",
                    false
            );
            addFirstOptiMobileMatch(hits, featuredResult.hits);
        } catch (Throwable ignored) {
        }
    }

    private static void ensureFeaturedVulkanDroidModrinth(@NonNull ArrayList<ModrinthProject> hits) {
        if (containsVulkanDroidModpack(hits)) return;

        try {
            ModrinthProject project = new ModrinthApiClient().getProjectWithFallback(
                    FEATURED_VULKAN_DROID_MODRINTH_PROJECT_ID,
                    FEATURED_VULKAN_DROID_SLUG
            );
            if (isVulkanDroidModpack(project)) {
                applyFeaturedMetadata(project);
                hits.add(0, project);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void ensureFeaturedCurseForgeModpack(
            @NonNull Context context,
            @Nullable String gameVersion,
            @Nullable String loader,
            @NonNull ArrayList<ModrinthProject> hits,
            int limit
    ) {
        ensureFeaturedOptiMobileCurseForge(context, loader, hits);
        ensureFeaturedVulkanDroidCurseForge(context, hits);
    }

    private static void ensureFeaturedOptiMobileCurseForge(
            @NonNull Context context,
            @Nullable String loader,
            @NonNull ArrayList<ModrinthProject> hits
    ) {
        if (containsOptiMobileModpack(hits)) return;

        // CurseForge search can return similarly named packs first, such as
        // "OptiMobile+". Pin DroidBridge's featured OptiMobile entries by
        // exact CurseForge project ID instead of trusting a loose name search.
        for (String projectId : featuredOptiMobileCurseForgeProjectIds(loader)) {
            try {
                ModrinthProject project = getCurseForgeProject(context, projectId);
                if (isOptiMobileModpack(project)) {
                    hits.add(0, project);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void ensureFeaturedVulkanDroidCurseForge(
            @NonNull Context context,
            @NonNull ArrayList<ModrinthProject> hits
    ) {
        if (containsVulkanDroidModpack(hits)) return;

        try {
            ModrinthProject project = getCurseForgeProject(context, FEATURED_VULKAN_DROID_CURSEFORGE_PROJECT_ID);
            if (isVulkanDroidModpack(project)) {
                applyFeaturedMetadata(project);
                hits.add(0, project);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void addFirstFeaturedMatch(
            @NonNull ArrayList<ModrinthProject> hits,
            @NonNull ArrayList<ModrinthProject> candidates
    ) {
        addFirstOptiMobileMatch(hits, candidates);
    }

    private static void addFirstOptiMobileMatch(
            @NonNull ArrayList<ModrinthProject> hits,
            @NonNull ArrayList<ModrinthProject> candidates
    ) {
        for (ModrinthProject candidate : candidates) {
            if (!isOptiMobileModpack(candidate)) continue;
            hits.add(0, candidate);
            return;
        }
    }

    private static void pinFeaturedModpackFirst(@NonNull ArrayList<ModrinthProject> hits, int limit) {
        ModrinthProject optiMobile = null;
        ModrinthProject vulkanDroid = null;
        for (int i = hits.size() - 1; i >= 0; i--) {
            ModrinthProject project = hits.get(i);
            if (isOptiMobileModpack(project)) {
                if (optiMobile == null) optiMobile = project;
                hits.remove(i);
            } else if (isVulkanDroidModpack(project)) {
                if (vulkanDroid == null) vulkanDroid = project;
                hits.remove(i);
            }
        }

        int insertIndex = 0;
        if (optiMobile != null) {
            hits.add(insertIndex++, optiMobile);
        }
        if (vulkanDroid != null) {
            applyFeaturedMetadata(vulkanDroid);
            int vulkanIndex = optiMobile != null
                    ? insertIndex
                    : Math.min(1, hits.size());
            hits.add(vulkanIndex, vulkanDroid);
        }
        if (limit > 0) {
            while (hits.size() > limit) hits.remove(hits.size() - 1);
        }
    }

    private static void applyFeaturedMetadata(@Nullable ModrinthProject project) {
        if (project == null) return;
        if (isVulkanDroidModpack(project)) {
            project.author = FEATURED_VULKAN_DROID_AUTHOR;
        }
    }

    private static boolean containsOptiMobileModpack(@NonNull ArrayList<ModrinthProject> hits) {
        for (ModrinthProject project : hits) {
            if (isOptiMobileModpack(project)) return true;
        }
        return false;
    }

    private static boolean containsVulkanDroidModpack(@NonNull ArrayList<ModrinthProject> hits) {
        for (ModrinthProject project : hits) {
            if (isVulkanDroidModpack(project)) return true;
        }
        return false;
    }

    private static boolean isFeaturedModpack(@Nullable ModrinthProject project) {
        return isOptiMobileModpack(project) || isVulkanDroidModpack(project);
    }

    private static boolean isOptiMobileModpack(@Nullable ModrinthProject project) {
        if (project == null) return false;

        String title = normalizeIdentity(project.title);
        String slug = normalizeIdentity(project.slug);
        String id = project.projectId == null ? "" : project.projectId.trim();

        if (project.source == ModManagerSource.CURSEFORGE) {
            return id.equals(FEATURED_OPTIMOBILE_FABRIC_CURSEFORGE_PROJECT_ID)
                    || id.equals(FEATURED_OPTIMOBILE_FORGE_CURSEFORGE_PROJECT_ID)
                    || slug.equals("optimobile-fabric")
                    || slug.endsWith("/optimobile-fabric")
                    || slug.equals("optimobile-forge")
                    || slug.endsWith("/optimobile-forge")
                    || title.equals("optimobile fabric")
                    || title.equals("optimobile forge");
        }

        return title.equals("optimobile")
                || title.equals("optimobile fabric")
                || title.equals("optimobile forge")
                || slug.equals("optimobile")
                || slug.equals("optimobile-fabric")
                || slug.equals("optimobile-forge")
                || id.equalsIgnoreCase("optimobile");
    }

    private static boolean isVulkanDroidModpack(@Nullable ModrinthProject project) {
        if (project == null) return false;
        String title = project.title == null ? "" : project.title.trim().toLowerCase(Locale.US);
        String slug = project.slug == null ? "" : project.slug.trim().toLowerCase(Locale.US);
        String id = project.projectId == null ? "" : project.projectId.trim();
        if (project.source == ModManagerSource.CURSEFORGE) {
            return id.equals(FEATURED_VULKAN_DROID_CURSEFORGE_PROJECT_ID)
                    || slug.equals(FEATURED_VULKAN_DROID_SLUG)
                    || title.contains("vulkan droid");
        }
        return id.equalsIgnoreCase(FEATURED_VULKAN_DROID_MODRINTH_PROJECT_ID)
                || slug.equals(FEATURED_VULKAN_DROID_SLUG)
                || title.contains("vulkan droid");
    }

    @NonNull
    private static ArrayList<String> featuredOptiMobileModrinthSlugs(@Nullable String loader) {
        ArrayList<String> slugs = new ArrayList<>();
        String normalizedLoader = normalizeLoader(loader);
        if ("forge".equals(normalizedLoader)) {
            slugs.add("optimobile-forge");
        } else if ("fabric".equals(normalizedLoader)) {
            slugs.add("optimobile-fabric");
        } else {
            slugs.add("optimobile");
            slugs.add("optimobile-fabric");
            slugs.add("optimobile-forge");
        }
        return slugs;
    }

    @NonNull
    private static ArrayList<String> featuredOptiMobileCurseForgeProjectIds(@Nullable String loader) {
        ArrayList<String> ids = new ArrayList<>();
        String normalizedLoader = normalizeLoader(loader);
        if ("forge".equals(normalizedLoader)) {
            ids.add(FEATURED_OPTIMOBILE_FORGE_CURSEFORGE_PROJECT_ID);
        } else if ("fabric".equals(normalizedLoader)) {
            ids.add(FEATURED_OPTIMOBILE_FABRIC_CURSEFORGE_PROJECT_ID);
        } else {
            ids.add(FEATURED_OPTIMOBILE_FABRIC_CURSEFORGE_PROJECT_ID);
            ids.add(FEATURED_OPTIMOBILE_FORGE_CURSEFORGE_PROJECT_ID);
        }
        return ids;
    }

    @NonNull
    private static ModrinthProject getCurseForgeProject(
            @NonNull Context context,
            @NonNull String projectId
    ) throws Exception {
        String apiKey = CurseForgeApiKeyProvider.resolve();
        if (isBlank(apiKey)) throw new IOException("Missing CurseForge API key.");

        JSONObject response = new JSONObject(httpGetString(
                "https://api.curseforge.com/v1/mods/" + urlEncode(projectId),
                apiKey
        ));
        JSONObject data = response.optJSONObject("data");
        if (data == null) throw new IOException("CurseForge project response is empty.");
        return parseCurseForgeProject(data);
    }

    @NonNull
    private static ModrinthProject parseCurseForgeProject(@NonNull JSONObject item) {
        JSONObject logo = item.optJSONObject("logo");
        JSONObject links = item.optJSONObject("links");
        ArrayList<String> categories = curseForgeCategories(item.optJSONArray("categories"));
        categories.add("modpack");
        return new ModrinthProject(
                String.valueOf(item.optInt("id", 0)),
                links == null ? item.optString("slug", "") : links.optString("websiteUrl", item.optString("slug", "")),
                item.optString("name", "Modpack"),
                item.optString("summary", ""),
                readFirstAuthor(item.optJSONArray("authors")),
                logo == null ? null : logo.optString("thumbnailUrl", logo.optString("url", null)),
                categories,
                item.optLong("downloadCount", 0L),
                0L,
                item.optString("dateModified", null),
                ModManagerSource.CURSEFORGE
        );
    }

    @NonNull
    private static String normalizeIdentity(@Nullable String value) {
        if (value == null) return "";
        return value.trim()
                .toLowerCase(Locale.US)
                .replace('+', '-')
                .replace('(', ' ')
                .replace(')', ' ')
                .replace('_', '-')
                .replaceAll("\\s+", " ")
                .trim();
    }

    @NonNull
    private static String resolveModrinthSortIndex(@NonNull String query, @Nullable String sortKey) {
        String key = normalizeSortKey(sortKey);
        if (key.isEmpty()) return query.trim().isEmpty() ? "downloads" : "relevance";

        switch (key) {
            case "downloads":
                return "downloads";
            case "updated":
                return "updated";
            case "versions":
                return "downloads";
            case "newest":
                return "newest";
            case "followers":
                return "follows";
            case "name":
                return query.trim().isEmpty() ? "downloads" : "relevance";
            case "relevance":
                return "relevance";
            default:
                return query.trim().isEmpty() ? "downloads" : "relevance";
        }
    }

    private static int resolveCurseForgeSortField(@NonNull String normalizedSortKey) {
        switch (normalizedSortKey) {
            case "updated":
            case "newest":
                return 3; // Last Updated
            case "name":
                return 4; // Name
            case "versions":
                return 6; // Filter by gameVersion, then keep Total Downloads first.
            case "followers":
            case "relevance":
                return 2; // Popularity
            case "downloads":
            default:
                return 6; // Total Downloads
        }
    }

    @NonNull
    private static String resolveCurseForgeSortOrder(@NonNull String normalizedSortKey) {
        return "name".equals(normalizedSortKey) ? "asc" : "desc";
    }

    @NonNull
    private static String normalizeSortKey(@Nullable String sortKey) {
        return sortKey == null ? "" : sortKey.trim().toLowerCase(Locale.US);
    }

    @NonNull
    private static ArrayList<String> curseForgeCategories(@Nullable JSONArray array) {
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; array != null && i < array.length(); i++) {
            JSONObject category = array.optJSONObject(i);
            if (category == null) continue;
            String slug = category.optString("slug", "").trim();
            String name = category.optString("name", "").trim();
            if (!slug.isEmpty()) out.add(slug);
            else if (!name.isEmpty()) out.add(name.toLowerCase(Locale.US).replace(' ', '-'));
        }
        return out;
    }

    @Nullable
    private static String readFirstAuthor(@Nullable JSONArray authors) {
        if (authors == null || authors.length() == 0) return null;
        JSONObject author = authors.optJSONObject(0);
        return author == null ? null : author.optString("name", null);
    }

    @NonNull
    private static ArrayList<String> stringArray(@Nullable JSONArray array) {
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; array != null && i < array.length(); i++) {
            String value = array.optString(i, "").trim();
            if (!value.isEmpty()) out.add(value);
        }
        return out;
    }

    @NonNull
    private static String httpGetString(@NonNull String url, @Nullable String curseForgeApiKey) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", "DroidBridge/ModpackSearch");
        if (!isBlank(curseForgeApiKey)) connection.setRequestProperty("x-api-key", curseForgeApiKey.trim());
        int response = connection.getResponseCode();
        InputStream stream = response / 100 == 2 ? connection.getInputStream() : connection.getErrorStream();
        String text = stream == null ? "" : readToString(stream);
        connection.disconnect();
        if (response / 100 != 2) throw new IOException("HTTP " + response + ": " + text);
        return text;
    }

    @NonNull
    private static String readToString(@NonNull InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toString("UTF-8");
    }

    @NonNull
    private static String normalizeLoader(@Nullable String loader) {
        String value = loader == null ? "" : loader.trim().toLowerCase(Locale.US);
        if (value.contains("cleanroom")) return "forge";
        if (value.equals("vanilla") || value.equals("minecraft")) return "";
        if (value.equals("fabric-loader")) return "fabric";
        if (value.equals("neoforge")) return "neoforge";
        return value;
    }

    private static int curseForgeLoaderType(@Nullable String loader) {
        String value = normalizeLoader(loader);
        if ("forge".equals(value)) return 1;
        if ("fabric".equals(value)) return 4;
        if ("quilt".equals(value)) return 5;
        if ("neoforge".equals(value)) return 6;
        return 0;
    }

    @NonNull
    private static String urlEncode(@NonNull String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }
}

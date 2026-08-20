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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared lightweight project row/details model used by Modrinth and CurseForge.
 *
 * This class intentionally keeps the older constructor while adding the longer
 * signatures used by the current API clients/details screen. That avoids forcing
 * every caller to be rewritten at once.
 */
public class ModrinthProject {
    @NonNull public String projectId = "";
    @NonNull public String slug = "";
    @NonNull public String title = "";
    @NonNull public String description = "";
    @Nullable public String author = null;
    @Nullable public String iconUrl = null;
    @NonNull public String body = "";
    @NonNull public String projectType = "mod";
    @NonNull public ArrayList<String> categories = new ArrayList<>();
    @NonNull public ArrayList<String> galleryUrls = new ArrayList<>();
    @NonNull public ArrayList<String> gameVersions = new ArrayList<>();
    @NonNull public ArrayList<String> loaders = new ArrayList<>();
    public long downloads = 0L;
    public long followers = 0L;
    @Nullable public String dateModified = null;
    @NonNull public ModManagerSource source = ModManagerSource.MODRINTH;
    @Nullable public String websiteUrl = null;

    public ModrinthProject() {
    }

    public ModrinthProject(
            @NonNull String projectId,
            @NonNull String slug,
            @NonNull String title,
            @NonNull String description,
            @Nullable String author,
            @Nullable String iconUrl,
            @Nullable List<String> categories,
            long downloads,
            long followers,
            @Nullable String dateModified,
            @NonNull ModManagerSource source
    ) {
        this.projectId = safe(projectId);
        this.slug = safe(slug);
        this.title = safe(title);
        this.description = safe(description);
        this.author = author;
        this.iconUrl = normalizeIconUrl(iconUrl);
        if (categories != null) this.categories.addAll(categories);
        this.downloads = downloads;
        this.followers = followers;
        this.dateModified = dateModified;
        this.source = source;
        inferProjectTypeFromCategories();
    }

    /**
     * Compatibility constructor used by the current ModrinthApiClient.
     *
     * Important: the current API clients pass the expanded fields in this order:
     * author, description/summary, body/long description, icon URL, project type.
     * Keeping that order here prevents the details screen from showing the summary
     * as the author and the icon/project id as the description.
     */
    public ModrinthProject(
            @NonNull String projectId,
            @NonNull String slug,
            @NonNull String title,
            @Nullable String author,
            @NonNull String description,
            @Nullable String body,
            @Nullable String iconUrl,
            @Nullable String projectType,
            long downloads,
            long followers,
            @Nullable String dateModified,
            @Nullable ArrayList<String> categories,
            @Nullable ArrayList<String> galleryUrls
    ) {
        this(projectId, slug, title, description, author, iconUrl, categories, downloads, followers, dateModified, ModManagerSource.MODRINTH);
        this.body = safe(body);
        if (galleryUrls != null) this.galleryUrls.addAll(galleryUrls);
        this.iconUrl = firstImageUrl(iconUrl, body, firstGalleryUrl(this.galleryUrls));
        String normalizedType = normalizeProjectType(projectType);
        if (!normalizedType.isEmpty()) this.projectType = normalizedType;
        inferProjectTypeFromCategories();
    }

    /**
     * Compatibility constructor used by the current CurseForgeApiClient.
     *
     * The expanded field order matches the Modrinth constructor above:
     * author, description/summary, body/long description, icon URL, project type.
     */
    public ModrinthProject(
            @NonNull String projectId,
            @NonNull String slug,
            @NonNull String title,
            @Nullable String author,
            @NonNull String description,
            @Nullable String body,
            @Nullable String iconUrl,
            @Nullable String projectType,
            long downloads,
            long followers,
            @Nullable String dateModified,
            @Nullable ArrayList<String> categories,
            @Nullable ArrayList<String> galleryUrls,
            @NonNull ModManagerSource source,
            @Nullable String websiteUrl
    ) {
        this(projectId, slug, title, description, author, iconUrl, categories, downloads, followers, dateModified, source);
        this.body = safe(body);
        if (galleryUrls != null) this.galleryUrls.addAll(galleryUrls);
        this.iconUrl = firstImageUrl(iconUrl, body, firstGalleryUrl(this.galleryUrls));
        String normalizedType = normalizeProjectType(projectType);
        if (!normalizedType.isEmpty()) this.projectType = normalizedType;
        this.websiteUrl = websiteUrl;
        inferProjectTypeFromCategories();
    }

    @NonNull
    public String getWebsiteUrl() {
        if (websiteUrl != null && !websiteUrl.trim().isEmpty()) {
            return websiteUrl.trim();
        }

        if (source == ModManagerSource.CURSEFORGE) {
            if (!slug.trim().isEmpty() && slug.startsWith("http")) return slug;
            if (!slug.trim().isEmpty()) {
                String section = isModpack() ? "modpacks" : "mc-mods";
                return "https://www.curseforge.com/minecraft/" + section + "/" + slug;
            }
            return "https://www.curseforge.com/minecraft/search?search=" + title.replace(' ', '+');
        }

        String value = !slug.trim().isEmpty() ? slug : projectId;
        String type = isModpack() ? "modpack" : "mod";
        return "https://modrinth.com/" + type + "/" + value;
    }

    public boolean isModpack() {
        return "modpack".equalsIgnoreCase(projectType) || categoriesContain("modpack") || categoriesContain("modpacks");
    }

    private void inferProjectTypeFromCategories() {
        if (categoriesContain("modpack") || categoriesContain("modpacks")) {
            projectType = "modpack";
            return;
        }
        if (categoriesContain("resourcepack") || categoriesContain("resourcepacks")) {
            projectType = "resourcepack";
            return;
        }
        if (categoriesContain("shader")
                || categoriesContain("shaders")
                || categoriesContain("shaderpack")
                || categoriesContain("shaderpacks")) {
            projectType = "shaderpack";
        }
    }

    private boolean categoriesContain(@NonNull String value) {
        for (String category : categories) {
            if (category != null && value.equalsIgnoreCase(category.trim())) return true;
        }
        return false;
    }

    @NonNull
    public String normalizedTitleKey() {
        return title.trim().toLowerCase(Locale.US);
    }

    @Nullable
    private static String firstImageUrl(@Nullable String... values) {
        if (values == null) return null;
        for (String value : values) {
            String normalized = normalizeIconUrl(value);
            if (normalized != null) return normalized;
        }
        return null;
    }

    @Nullable
    private static String firstGalleryUrl(@Nullable ArrayList<String> galleryUrls) {
        if (galleryUrls == null) return null;
        for (String galleryUrl : galleryUrls) {
            String normalized = normalizeIconUrl(galleryUrl);
            if (normalized != null) return normalized;
        }
        return null;
    }

    @NonNull
    private static String normalizeProjectType(@Nullable String value) {
        if (value == null) return "";
        String type = value.trim().toLowerCase(Locale.US).replace('_', '-');
        if (type.isEmpty() || type.startsWith("http://") || type.startsWith("https://") || "null".equals(type)) {
            return "";
        }
        if ("mods".equals(type)) return "mod";
        if ("modpacks".equals(type)) return "modpack";
        if ("resourcepacks".equals(type) || "resource-pack".equals(type) || "resource-packs".equals(type)) return "resourcepack";
        if ("shader".equals(type) || "shaders".equals(type) || "shaderpacks".equals(type) || "shader-pack".equals(type) || "shader-packs".equals(type)) return "shaderpack";
        if ("mod".equals(type) || "modpack".equals(type) || "resourcepack".equals(type) || "shaderpack".equals(type)) return type;
        return "";
    }

    @Nullable
    private static String normalizeIconUrl(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) return null;
        if (trimmed.startsWith("//")) return "https:" + trimmed;
        return trimmed.startsWith("http://") || trimmed.startsWith("https://") ? trimmed : null;
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value;
    }
}

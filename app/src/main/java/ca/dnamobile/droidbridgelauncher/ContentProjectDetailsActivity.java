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

package ca.dnamobile.droidbridgelauncher;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Html;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstance;
import ca.dnamobile.droidbridgelauncher.modmanager.CurseForgeApiClient;
import ca.dnamobile.droidbridgelauncher.modmanager.CurseForgeInstallManager;
import ca.dnamobile.droidbridgelauncher.modmanager.DatapackWorldTargetPicker;
import ca.dnamobile.droidbridgelauncher.modmanager.ModManagerContentType;
import ca.dnamobile.droidbridgelauncher.modmanager.ModManagerSource;
import ca.dnamobile.droidbridgelauncher.modmanager.ModManagerVersionResolver;
import ca.dnamobile.droidbridgelauncher.modmanager.ModpackInstallManager;
import ca.dnamobile.droidbridgelauncher.modmanager.ModrinthApiClient;
import ca.dnamobile.droidbridgelauncher.modmanager.ModrinthInstallManager;
import ca.dnamobile.droidbridgelauncher.modmanager.ModrinthProject;
import ca.dnamobile.droidbridgelauncher.modmanager.ModrinthVersion;
import ca.dnamobile.droidbridgelauncher.modmanager.NetworkImageLoader;
import ca.dnamobile.droidbridgelauncher.ui.LauncherDialogStyle;
import ca.dnamobile.droidbridgelauncher.ui.ModpackInstallProgressDialog;
import ca.dnamobile.droidbridgelauncher.ui.version.CleanroomMigrationDialog;
import ca.dnamobile.droidbridgelauncher.utils.AppOrientationHelper;
import ca.dnamobile.droidbridgelauncher.utils.FullscreenUtils;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class ContentProjectDetailsActivity extends AppCompatActivity {
    private static final int TAB_DESCRIPTION = 0;
    private static final int TAB_VERSIONS = 1;
    private static final int TAB_GALLERY = 2;
    private static final int VERSION_PAGE_SIZE = 10;
    private static final String VERSION_FILTER_ALL = "All Minecraft versions";
    private static final String VERSION_FILTER_UNKNOWN = "Unknown Minecraft version";

    private ImageView imageProjectIcon;
    private TextView textTitle;
    private TextView textMeta;
    private ImageView imageDescriptionMain;
    private TextView textDescription;
    private TextView textGallery;
    private TextView textStatus;
    private TabLayout tabSections;
    private View sectionDescription;
    private View sectionVersions;
    private View sectionGallery;
    private RecyclerView recyclerVersions;
    private RecyclerView recyclerGallery;
    private MaterialButton buttonVersionMinecraft;
    private TextView textVersionFilterSummary;
    private TextView textVersionPageIndicator;
    private MaterialButton buttonVersionPagePrevious;
    private MaterialButton buttonVersionPageNext;
    private MaterialButton buttonOpenWebsite;
    private MaterialButton buttonBack;

    private final VersionAdapter adapter = new VersionAdapter();
    private final GalleryAdapter galleryAdapter = new GalleryAdapter();
    private final ArrayList<VersionRow> allVersionRows = new ArrayList<>();
    private final ArrayList<VersionRow> filteredVersionRows = new ArrayList<>();
    private final ArrayList<String> versionMinecraftFilters = new ArrayList<>();
    private int versionCurrentPage = 0;
    private String selectedMinecraftVersionFilter = VERSION_FILTER_ALL;

    private String instanceId = "";
    private String instanceName = "";
    private String loader = "";
    private String baseVersionId = "";
    private String gameVersionId = "";
    private String gameDirectoryPath = "";
    private String projectId = "";
    private String projectSlug = "";
    private ModManagerContentType contentType = ModManagerContentType.MODS;
    private ModManagerSource source = ModManagerSource.MODRINTH;
    @Nullable
    private ModrinthProject project;
    @Nullable
    private ModpackInstallProgressDialog modpackInstallDialog;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherTheme.apply(this);
        super.onCreate(savedInstanceState);
        Logging.init(this);
        AppOrientationHelper.applyToActivity(this);
        PathManager.initContextConstants(this);
        setContentView(R.layout.activity_content_project_details);
        LauncherTheme.applyRainbowBackgroundIfNeeded(this);
        FullscreenUtils.enableImmersive(this);

        readExtras();
        bindViews();
        setupViews();
        loadProject();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppOrientationHelper.applyToActivity(this);
        FullscreenUtils.enableImmersive(this);
    }

    @Override
    protected void onDestroy() {
        dismissModpackInstallDialog();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) FullscreenUtils.enableImmersive(this);
    }

    private void readExtras() {
        instanceId = safeExtra(InstanceDetailsActivity.EXTRA_INSTANCE_ID, "");
        instanceName = safeExtra(InstanceDetailsActivity.EXTRA_INSTANCE_NAME, "Unknown instance");
        loader = safeExtra(InstanceDetailsActivity.EXTRA_INSTANCE_LOADER, "Vanilla");
        baseVersionId = safeExtra(InstanceDetailsActivity.EXTRA_BASE_VERSION_ID, "");
        gameVersionId = safeExtra(InstanceDetailsActivity.EXTRA_MINECRAFT_VERSION_ID, "");
        if (gameVersionId.isEmpty()) {
            gameVersionId = ModManagerVersionResolver.resolveGameVersionForContent(baseVersionId);
        }
        gameDirectoryPath = safeExtra(InstanceDetailsActivity.EXTRA_GAME_DIRECTORY, "");
        projectId = safeExtra(ContentBrowserActivity.EXTRA_PROJECT_ID, "");
        projectSlug = safeExtra(ContentBrowserActivity.EXTRA_PROJECT_SLUG, "");

        String category = safeExtra(InstanceDetailsActivity.EXTRA_CONTENT_CATEGORY, "");
        if (category.isEmpty()) category = safeExtra(ContentBrowserActivity.EXTRA_PROJECT_TYPE, "mods");
        contentType = ModManagerContentType.fromValue(category);

        source = ModManagerSource.fromId(safeExtra(ContentBrowserActivity.EXTRA_PROJECT_SOURCE, ModManagerSource.MODRINTH.getId()));
    }

    @NonNull
    private String safeExtra(@NonNull String key, @NonNull String fallback) {
        String value = getIntent().getStringExtra(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private void bindViews() {
        imageProjectIcon = findViewById(R.id.imageProjectDetailsIcon);
        textTitle = findViewById(R.id.textProjectDetailsTitle);
        textMeta = findViewById(R.id.textProjectDetailsMeta);
        imageDescriptionMain = findViewById(R.id.imageProjectDetailsMainImage);
        textDescription = findViewById(R.id.textProjectDetailsDescription);
        textGallery = findViewById(R.id.textProjectDetailsGallery);
        textStatus = findViewById(R.id.textProjectDetailsStatus);
        tabSections = findViewById(R.id.tabProjectDetailsSections);
        sectionDescription = findViewById(R.id.sectionProjectDescription);
        sectionVersions = findViewById(R.id.sectionProjectVersions);
        sectionGallery = findViewById(R.id.sectionProjectGallery);
        recyclerVersions = findViewById(R.id.recyclerProjectVersions);
        recyclerGallery = findViewById(R.id.recyclerProjectGallery);
        buttonVersionMinecraft = findViewById(R.id.buttonProjectDetailsMinecraftVersion);
        textVersionFilterSummary = findViewById(R.id.textProjectDetailsVersionFilterSummary);
        textVersionPageIndicator = findViewById(R.id.textProjectDetailsVersionPageIndicator);
        buttonVersionPagePrevious = findViewById(R.id.buttonProjectDetailsVersionPrevious);
        buttonVersionPageNext = findViewById(R.id.buttonProjectDetailsVersionNext);
        buttonOpenWebsite = findViewById(R.id.buttonOpenProjectWebsite);
        buttonBack = findViewById(R.id.buttonProjectDetailsBack);
    }

    private void setupViews() {
        buttonBack.setOnClickListener(view -> finish());
        buttonOpenWebsite.setEnabled(false);

        recyclerVersions.setLayoutManager(new LinearLayoutManager(this));
        recyclerVersions.setNestedScrollingEnabled(false);
        recyclerVersions.setAdapter(adapter);
        setupVersionControls();

        int columns = getGalleryColumnCount();
        recyclerGallery.setLayoutManager(new GridLayoutManager(this, columns));
        recyclerGallery.setNestedScrollingEnabled(false);
        recyclerGallery.setAdapter(galleryAdapter);

        setupTabs();

        textTitle.setText(safeExtra(ContentBrowserActivity.EXTRA_PROJECT_TITLE, getString(R.string.content_project_details_loading)));
        textMeta.setText(buildInitialMetaLine());
        imageDescriptionMain.setVisibility(View.GONE);
        textDescription.setText("");
        textDescription.setMovementMethod(LinkMovementMethod.getInstance());
        textDescription.setLinksClickable(true);
        textGallery.setText("");
        textStatus.setText(R.string.content_project_details_loading);

        String icon = safeExtra(ContentBrowserActivity.EXTRA_PROJECT_ICON_URL, "");
        NetworkImageLoader.load(imageProjectIcon, icon, getFallbackIcon());
    }

    private void setupTabs() {
        tabSections.removeAllTabs();
        tabSections.addTab(tabSections.newTab().setText("Description"));
        tabSections.addTab(tabSections.newTab().setText("Versions"));
        tabSections.addTab(tabSections.newTab().setText("Gallery"));
        tabSections.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showSection(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                showSection(tab.getPosition());
            }
        });
        showSection(TAB_DESCRIPTION);
    }

    private void showSection(int tab) {
        sectionDescription.setVisibility(tab == TAB_DESCRIPTION ? View.VISIBLE : View.GONE);
        sectionVersions.setVisibility(tab == TAB_VERSIONS ? View.VISIBLE : View.GONE);
        sectionGallery.setVisibility(tab == TAB_GALLERY ? View.VISIBLE : View.GONE);
    }

    private void setTabText(int index, @NonNull String text) {
        TabLayout.Tab tab = tabSections.getTabAt(index);
        if (tab != null) tab.setText(text);
    }

    private void setupVersionControls() {
        buttonVersionPagePrevious.setFocusable(false);
        buttonVersionPagePrevious.setFocusableInTouchMode(false);
        buttonVersionPageNext.setFocusable(false);
        buttonVersionPageNext.setFocusableInTouchMode(false);

        buttonVersionPagePrevious.setOnClickListener(view -> {
            if (versionCurrentPage <= 0) return;
            versionCurrentPage--;
            refreshVisibleVersionPage();
        });

        buttonVersionPageNext.setOnClickListener(view -> {
            int pageCount = getVersionPageCount(filteredVersionRows.size());
            if (versionCurrentPage + 1 >= pageCount) return;
            versionCurrentPage++;
            refreshVisibleVersionPage();
        });

        buttonVersionMinecraft.setFocusable(false);
        buttonVersionMinecraft.setFocusableInTouchMode(false);
        buttonVersionMinecraft.setEnabled(false);
        buttonVersionMinecraft.setText(VERSION_FILTER_ALL);
        buttonVersionMinecraft.setOnClickListener(view -> showMinecraftVersionFilterDialog());

        textVersionFilterSummary.setText("Loading versions...");
        textVersionPageIndicator.setText("Page 1 of 1");
        buttonVersionPagePrevious.setEnabled(false);
        buttonVersionPageNext.setEnabled(false);
    }

    private void submitVersionRows(@NonNull ArrayList<VersionRow> rows) {
        allVersionRows.clear();
        allVersionRows.addAll(rows);
        versionCurrentPage = 0;
        setupMinecraftVersionFilter(rows);
        applyVersionFilter();
    }

    private void setupMinecraftVersionFilter(@NonNull ArrayList<VersionRow> rows) {
        versionMinecraftFilters.clear();
        versionMinecraftFilters.add(VERSION_FILTER_ALL);

        for (VersionRow row : rows) {
            for (String version : row.minecraftVersions()) {
                String clean = normalizeMinecraftVersionKey(version);
                if (clean.isEmpty()) clean = VERSION_FILTER_UNKNOWN;
                if (!containsIgnoreCase(versionMinecraftFilters, clean)) versionMinecraftFilters.add(clean);
            }
        }

        if (versionMinecraftFilters.size() > 2) {
            ArrayList<String> sorted = new ArrayList<>(versionMinecraftFilters.subList(1, versionMinecraftFilters.size()));
            Collections.sort(sorted, this::compareMinecraftVersionKeysDescending);
            versionMinecraftFilters.clear();
            versionMinecraftFilters.add(VERSION_FILTER_ALL);
            versionMinecraftFilters.addAll(sorted);
        }

        int defaultIndex = getDefaultMinecraftFilterIndex();
        if (defaultIndex < 0 || defaultIndex >= versionMinecraftFilters.size()) defaultIndex = 0;
        selectedMinecraftVersionFilter = versionMinecraftFilters.get(defaultIndex);
        updateMinecraftVersionButtonText();
        buttonVersionMinecraft.setEnabled(!rows.isEmpty());
    }

    private int getDefaultMinecraftFilterIndex() {
        String wanted = normalizeMinecraftVersionKey(gameVersionId);
        if (wanted.isEmpty()) return 0;
        for (int i = 1; i < versionMinecraftFilters.size(); i++) {
            if (wanted.equalsIgnoreCase(versionMinecraftFilters.get(i))) return i;
        }
        return 0;
    }

    private void applyVersionFilter() {
        filteredVersionRows.clear();
        String selected = getSelectedMinecraftVersionFilter();
        for (VersionRow row : allVersionRows) {
            if (matchesMinecraftVersionFilter(row, selected)) filteredVersionRows.add(row);
        }
        refreshVisibleVersionPage();
    }

    private void refreshVisibleVersionPage() {
        int pageCount = getVersionPageCount(filteredVersionRows.size());
        if (versionCurrentPage >= pageCount) versionCurrentPage = pageCount - 1;
        if (versionCurrentPage < 0) versionCurrentPage = 0;

        int start = versionCurrentPage * VERSION_PAGE_SIZE;
        int end = Math.min(start + VERSION_PAGE_SIZE, filteredVersionRows.size());
        ArrayList<VersionRow> pageRows = new ArrayList<>();
        if (start < end) pageRows.addAll(filteredVersionRows.subList(start, end));
        adapter.submit(pageRows);

        buttonVersionPagePrevious.setEnabled(versionCurrentPage > 0);
        buttonVersionPageNext.setEnabled(versionCurrentPage + 1 < pageCount);
        textVersionPageIndicator.setText("Page " + (versionCurrentPage + 1) + " of " + pageCount);
        textVersionFilterSummary.setText(buildVersionFilterSummary(start, end));
    }

    @NonNull
    private String buildVersionFilterSummary(int start, int end) {
        if (allVersionRows.isEmpty()) return "No versions were found for this project.";

        String selected = getSelectedMinecraftVersionFilter();
        String prefix = VERSION_FILTER_ALL.equals(selected)
                ? "All Minecraft versions"
                : "Minecraft " + selected;

        if (filteredVersionRows.isEmpty()) {
            return prefix + " · No matching versions";
        }

        return prefix
                + " · Showing "
                + (start + 1)
                + "-"
                + end
                + " of "
                + filteredVersionRows.size()
                + (allVersionRows.size() == filteredVersionRows.size() ? " versions" : " matching versions");
    }

    private boolean matchesMinecraftVersionFilter(@NonNull VersionRow row, @NonNull String selected) {
        if (VERSION_FILTER_ALL.equals(selected)) return true;
        for (String version : row.minecraftVersions()) {
            String clean = normalizeMinecraftVersionKey(version);
            if (clean.isEmpty()) clean = VERSION_FILTER_UNKNOWN;
            if (selected.equalsIgnoreCase(clean)) return true;
        }
        return false;
    }

    @NonNull
    private String getSelectedMinecraftVersionFilter() {
        String clean = selectedMinecraftVersionFilter == null ? "" : selectedMinecraftVersionFilter.trim();
        return clean.isEmpty() ? VERSION_FILTER_ALL : clean;
    }

    private void updateMinecraftVersionButtonText() {
        String selected = getSelectedMinecraftVersionFilter();
        buttonVersionMinecraft.setText(selected);
    }

    private void selectMinecraftVersionFilter(@NonNull String selected) {
        selectedMinecraftVersionFilter = selected.trim().isEmpty() ? VERSION_FILTER_ALL : selected.trim();
        updateMinecraftVersionButtonText();
        versionCurrentPage = 0;
        applyVersionFilter();
    }

    private void showMinecraftVersionFilterDialog() {
        if (versionMinecraftFilters.isEmpty()) return;

        LinearLayout layout = LauncherDialogStyle.createDialogRoot(
                this,
                "Pick Minecraft Version",
                "Choose the Minecraft version to show. Versions are limited to 10 per page after this filter is applied."
        );

        RecyclerView versionList = new RecyclerView(this);
        versionList.setLayoutManager(new LinearLayoutManager(this));
        versionList.setNestedScrollingEnabled(true);
        versionList.setClipToPadding(false);
        versionList.setPadding(0, dp(8), 0, dp(12));

        AlertDialog[] dialogHolder = new AlertDialog[1];
        versionList.setAdapter(new MinecraftVersionFilterDialogAdapter(versionMinecraftFilters, getSelectedMinecraftVersionFilter(), selected -> {
            AlertDialog dialog = dialogHolder[0];
            if (dialog != null) dialog.dismiss();
            selectMinecraftVersionFilter(selected);
        }));

        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int listHeight = Math.min(Math.max(dp(260), screenHeight - dp(300)), dp(540));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                listHeight
        );
        listParams.topMargin = dp(4);
        layout.addView(versionList, listParams);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(layout)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialogHolder[0] = dialog;
        dialog.setOnShowListener(unused -> {
            LauncherDialogStyle.styleDialogChrome(this, dialog);
            FullscreenUtils.enableImmersive(this);
        });
        dialog.setOnDismissListener(unused -> FullscreenUtils.enableImmersive(this));
        dialog.show();
        LauncherDialogStyle.styleDialogChrome(this, dialog);
        FullscreenUtils.enableImmersive(this);
    }


    private int getMinecraftVersionFilterCount(@NonNull String selected) {
        int count = 0;
        for (VersionRow row : allVersionRows) {
            if (matchesMinecraftVersionFilter(row, selected)) count++;
        }
        return count;
    }

    private int resolveThemeColor(int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (!getTheme().resolveAttribute(attr, value, true)) return fallback;
        if (value.resourceId != 0) {
            try {
                return getResources().getColor(value.resourceId);
            } catch (Throwable ignored) {
                return fallback;
            }
        }
        return value.data;
    }

    private int getVersionPageCount(int count) {
        if (count <= 0) return 1;
        return (int) Math.ceil(count / (double) VERSION_PAGE_SIZE);
    }

    @NonNull
    private String normalizeMinecraftVersionKey(@Nullable String value) {
        if (value == null) return "";
        String clean = value.trim();
        if (clean.toLowerCase(Locale.US).startsWith("minecraft ")) {
            clean = clean.substring("minecraft ".length()).trim();
        }
        return clean;
    }

    private boolean containsIgnoreCase(@NonNull ArrayList<String> values, @NonNull String target) {
        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(target)) return true;
        }
        return false;
    }

    private int compareMinecraftVersionKeysDescending(@NonNull String left, @NonNull String right) {
        boolean leftUnknown = left.equalsIgnoreCase(VERSION_FILTER_UNKNOWN);
        boolean rightUnknown = right.equalsIgnoreCase(VERSION_FILTER_UNKNOWN);
        if (leftUnknown && rightUnknown) return 0;
        if (leftUnknown) return 1;
        if (rightUnknown) return -1;

        int labelCompare = compareVersionLabelsDescending(left, right);
        if (labelCompare != 0) return labelCompare;
        return right.compareToIgnoreCase(left);
    }

    private int getGalleryColumnCount() {
        int screenWidthDp = getResources().getConfiguration().screenWidthDp;
        if (screenWidthDp >= 900) return 4;
        if (screenWidthDp >= 600) return 3;
        return 2;
    }

    @NonNull
    private String buildInitialMetaLine() {
        if (contentType == ModManagerContentType.MODPACKS) {
            if (gameDirectoryPath.trim().isEmpty()) {
                return "Modpack · " + source.getDisplayName() + " · Installs to the selected launcher storage location";
            }
            return "Modpack · " + source.getDisplayName() + " · Current instance: " + instanceName;
        }

        return getString(
                R.string.content_project_details_instance_meta,
                instanceName,
                displayLoader(loader),
                gameVersionId.isEmpty() ? getString(R.string.content_browser_unknown_version) : gameVersionId
        );
    }

    private void loadProject() {
        if (projectId.trim().isEmpty() && projectSlug.trim().isEmpty()) {
            textStatus.setText(R.string.content_project_details_missing_project);
            return;
        }

        textStatus.setText(R.string.content_project_details_loading);
        Thread thread = new Thread(() -> {
            try {
                ModrinthProject loadedProject;
                ArrayList<VersionRow> rows;

                if (source == ModManagerSource.CURSEFORGE) {
                    CurseForgeApiClient api = new CurseForgeApiClient(this);
                    loadedProject = api.getProject(projectId);
                    try {
                        String fullDescription = api.getProjectDescription(projectId);
                        if (!fullDescription.trim().isEmpty()) loadedProject.body = fullDescription;
                    } catch (Throwable ignored) {
                        // CurseForge can still show the summary and versions when the
                        // optional full-description endpoint fails.
                    }
                    rows = contentType == ModManagerContentType.MODPACKS
                            ? loadModpackVersionRows(loadedProject)
                            : loadNormalVersionRows(api.getProjectVersions(projectId, contentType, gameVersionId, loader));
                } else {
                    ModrinthApiClient api = new ModrinthApiClient();
                    loadedProject = api.getProjectWithFallback(projectId, projectSlug);
                    rows = contentType == ModManagerContentType.MODPACKS
                            ? loadModpackVersionRows(loadedProject)
                            : loadNormalVersionRows(api.getProjectVersionsWithFallback(
                            loadedProject,
                            contentType,
                            gameVersionId,
                            loader,
                            true
                    ));
                }

                ModrinthProject finalProject = loadedProject;
                ArrayList<VersionRow> finalRows = rows;
                runOnUiThread(() -> bindLoadedProject(finalProject, finalRows));
            } catch (Throwable throwable) {
                runOnUiThread(() -> textStatus.setText(getString(
                        R.string.content_project_details_load_failed,
                        throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName()
                )));
            }
        }, contentType == ModManagerContentType.MODPACKS ? "ModpackProjectDetails" : "ContentProjectDetails");
        thread.start();
    }

    @NonNull
    private ArrayList<VersionRow> loadNormalVersionRows(@NonNull ArrayList<ModrinthVersion> versions) {
        ArrayList<VersionRow> rows = new ArrayList<>();
        for (ModrinthVersion version : versions) {
            if (version != null) rows.add(VersionRow.normal(version));
        }
        sortRowsNewestFirst(rows);
        return rows;
    }

    @NonNull
    private ArrayList<VersionRow> loadModpackVersionRows(@NonNull ModrinthProject loadedProject) throws Exception {
        ArrayList<ModpackInstallManager.ModpackVersionChoice> choices = ModpackInstallManager.listProjectVersions(
                this,
                source,
                safeProjectId(loadedProject),
                safeProjectSlug(loadedProject)
        );
        sortModpackVersionsNewestFirst(choices);

        ArrayList<VersionRow> rows = new ArrayList<>();
        for (ModpackInstallManager.ModpackVersionChoice choice : choices) {
            if (choice != null) rows.add(VersionRow.modpack(choice));
        }
        return rows;
    }

    private void bindLoadedProject(@NonNull ModrinthProject loadedProject, @NonNull ArrayList<VersionRow> rows) {
        project = loadedProject;
        textTitle.setText(loadedProject.title);
        textMeta.setText(buildProjectMetaText(loadedProject));
        bindDescriptionMainImage(firstMainImageUrl(loadedProject));
        bindFormattedDescription(loadedProject);
        textStatus.setText(buildVersionStatusText(rows.size()));

        ArrayList<String> galleryUrls = buildGalleryUrls(loadedProject);
        bindGalleryStatus(galleryUrls.size());
        galleryAdapter.submit(galleryUrls);

        setTabText(TAB_DESCRIPTION, "Description");
        setTabText(TAB_VERSIONS, rows.isEmpty() ? "Versions" : "Versions (" + rows.size() + ")");
        setTabText(TAB_GALLERY, galleryUrls.isEmpty() ? "Gallery" : "Gallery (" + galleryUrls.size() + ")");

        NetworkImageLoader.load(imageProjectIcon, firstUsableImageUrl(loadedProject), getFallbackIcon());
        buttonOpenWebsite.setEnabled(true);
        buttonOpenWebsite.setOnClickListener(view -> openWebsite(loadedProject));
        submitVersionRows(rows);
    }

    @NonNull
    private String buildDescriptionSectionText(@NonNull ModrinthProject loadedProject) {
        String body = buildDescriptionText(loadedProject);
        String details = buildProjectDetailsLine(loadedProject);
        if (details.isEmpty()) return body;
        return body + "\n\n" + details;
    }

    private void bindFormattedDescription(@NonNull ModrinthProject loadedProject) {
        String body = buildDescriptionText(loadedProject);
        String descriptionHtml = source == ModManagerSource.CURSEFORGE || looksLikeHtml(body)
                ? sanitizeProjectHtml(body)
                : markdownToHtml(body);
        String detailsHtml = buildProjectDetailsHtml(loadedProject);

        String fullHtml = descriptionHtml;
        if (!detailsHtml.isEmpty()) {
            fullHtml += "<br><br>" + detailsHtml;
        }

        Spanned formatted = htmlToSpanned(fullHtml);
        textDescription.setText(formatted);
        textDescription.setMovementMethod(LinkMovementMethod.getInstance());
        textDescription.setLinksClickable(true);
    }

    @NonNull
    private Spanned htmlToSpanned(@NonNull String html) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY);
        }
        //noinspection deprecation
        return Html.fromHtml(html);
    }

    private boolean looksLikeHtml(@NonNull String value) {
        String lower = value.toLowerCase(Locale.US);
        return lower.contains("<p")
                || lower.contains("<br")
                || lower.contains("<h1")
                || lower.contains("<h2")
                || lower.contains("<ul")
                || lower.contains("<ol")
                || lower.contains("<li")
                || lower.contains("<div")
                || lower.contains("<span")
                || lower.contains("<strong")
                || lower.contains("<img");
    }

    @NonNull
    private String sanitizeProjectHtml(@NonNull String rawHtml) {
        String html = rawHtml.trim();
        if (html.isEmpty()) return "<p>No description was provided for this project.</p>";

        html = html.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        html = html.replaceAll("(?is)<style[^>]*>.*?</style>", "");
        html = html.replaceAll("(?is)<iframe[^>]*>.*?</iframe>", "");
        html = html.replaceAll("(?is)<img[^>]*>", "");
        html = html.replaceAll("(?is)<video[^>]*>.*?</video>", "");
        html = html.replaceAll("(?is)<source[^>]*>", "");
        html = html.replaceAll("(?is)<hr[^>]*>", "<br><br>");

        // Android's Html renderer ignores many layout tags/classes. Convert the common
        // CurseForge/Modrinth page wrappers into clean readable spacing instead of raw blocks.
        html = html.replaceAll("(?is)</div>", "</p>");
        html = html.replaceAll("(?is)<div[^>]*>", "<p>");
        html = html.replaceAll("(?is)</section>", "</p>");
        html = html.replaceAll("(?is)<section[^>]*>", "<p>");

        return html.trim().isEmpty() ? "<p>No description was provided for this project.</p>" : html;
    }

    @NonNull
    private String markdownToHtml(@NonNull String markdown) {
        String text = markdown.trim();
        if (text.isEmpty()) return "<p>No description was provided for this project.</p>";

        String[] lines = text.split("\\r?\\n");
        StringBuilder html = new StringBuilder();
        boolean inUnorderedList = false;
        boolean inOrderedList = false;
        boolean inCodeBlock = false;
        StringBuilder paragraph = new StringBuilder();
        StringBuilder codeBlock = new StringBuilder();

        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine;
            String trimmed = line.trim();

            if (trimmed.startsWith("```")) {
                closeParagraph(html, paragraph);
                if (inUnorderedList) { html.append("</ul>"); inUnorderedList = false; }
                if (inOrderedList) { html.append("</ol>"); inOrderedList = false; }
                if (inCodeBlock) {
                    html.append("<pre>").append(escapeHtml(codeBlock.toString().trim())).append("</pre>");
                    codeBlock.setLength(0);
                    inCodeBlock = false;
                } else {
                    inCodeBlock = true;
                }
                continue;
            }

            if (inCodeBlock) {
                codeBlock.append(line).append('\n');
                continue;
            }

            if (trimmed.isEmpty()) {
                closeParagraph(html, paragraph);
                if (inUnorderedList) { html.append("</ul>"); inUnorderedList = false; }
                if (inOrderedList) { html.append("</ol>"); inOrderedList = false; }
                continue;
            }

            if (isStandaloneImageReference(trimmed)) {
                closeParagraph(html, paragraph);
                continue;
            }

            if (trimmed.equals("---") || trimmed.equals("***") || trimmed.equals("___")) {
                closeParagraph(html, paragraph);
                if (inUnorderedList) { html.append("</ul>"); inUnorderedList = false; }
                if (inOrderedList) { html.append("</ol>"); inOrderedList = false; }
                html.append("<br><br>");
                continue;
            }

            int headingLevel = markdownHeadingLevel(trimmed);
            if (headingLevel > 0) {
                closeParagraph(html, paragraph);
                if (inUnorderedList) { html.append("</ul>"); inUnorderedList = false; }
                if (inOrderedList) { html.append("</ol>"); inOrderedList = false; }
                String content = trimmed.substring(headingLevel).trim();
                int htmlLevel = Math.min(3, headingLevel);
                html.append("<h").append(htmlLevel).append(">")
                        .append(formatMarkdownInline(content))
                        .append("</h").append(htmlLevel).append(">");
                continue;
            }

            String listText = markdownUnorderedListText(trimmed);
            if (listText != null) {
                closeParagraph(html, paragraph);
                if (inOrderedList) { html.append("</ol>"); inOrderedList = false; }
                if (!inUnorderedList) { html.append("<ul>"); inUnorderedList = true; }
                html.append("<li>").append(formatMarkdownInline(listText)).append("</li>");
                continue;
            }

            listText = markdownOrderedListText(trimmed);
            if (listText != null) {
                closeParagraph(html, paragraph);
                if (inUnorderedList) { html.append("</ul>"); inUnorderedList = false; }
                if (!inOrderedList) { html.append("<ol>"); inOrderedList = true; }
                html.append("<li>").append(formatMarkdownInline(listText)).append("</li>");
                continue;
            }

            if (trimmed.startsWith(">")) {
                closeParagraph(html, paragraph);
                if (inUnorderedList) { html.append("</ul>"); inUnorderedList = false; }
                if (inOrderedList) { html.append("</ol>"); inOrderedList = false; }
                html.append("<blockquote>").append(formatMarkdownInline(trimmed.substring(1).trim())).append("</blockquote>");
                continue;
            }

            if (inUnorderedList) { html.append("</ul>"); inUnorderedList = false; }
            if (inOrderedList) { html.append("</ol>"); inOrderedList = false; }
            if (paragraph.length() > 0) paragraph.append(' ');
            paragraph.append(trimmed);
        }

        closeParagraph(html, paragraph);
        if (inUnorderedList) html.append("</ul>");
        if (inOrderedList) html.append("</ol>");
        if (inCodeBlock) html.append("<pre>").append(escapeHtml(codeBlock.toString().trim())).append("</pre>");

        String result = html.toString().trim();
        return result.isEmpty() ? "<p>No description was provided for this project.</p>" : result;
    }

    private void closeParagraph(@NonNull StringBuilder html, @NonNull StringBuilder paragraph) {
        if (paragraph.length() == 0) return;
        html.append("<p>").append(formatMarkdownInline(paragraph.toString())).append("</p>");
        paragraph.setLength(0);
    }

    private int markdownHeadingLevel(@NonNull String line) {
        int count = 0;
        while (count < line.length() && count < 6 && line.charAt(count) == '#') count++;
        return count > 0 && count < line.length() && Character.isWhitespace(line.charAt(count)) ? count : 0;
    }

    @Nullable
    private String markdownUnorderedListText(@NonNull String line) {
        if (line.length() < 3) return null;
        char first = line.charAt(0);
        if ((first == '-' || first == '*' || first == '+') && Character.isWhitespace(line.charAt(1))) {
            return line.substring(2).trim();
        }
        return null;
    }

    @Nullable
    private String markdownOrderedListText(@NonNull String line) {
        Matcher matcher = Pattern.compile("^\\d+[.)]\\s+(.+)$").matcher(line);
        return matcher.matches() ? matcher.group(1).trim() : null;
    }

    @NonNull
    private String formatMarkdownInline(@Nullable String rawText) {
        String text = escapeHtml(rawText == null ? "" : rawText.trim());
        if (text.isEmpty()) return "";

        text = text.replaceAll("!\\[[^\\]]*\\]\\([^\\)]*\\)", "");
        text = replaceMarkdownLinks(text);
        text = text.replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>");
        text = text.replaceAll("__([^_]+)__", "<b>$1</b>");
        text = text.replaceAll("(?<!\\*)\\*([^*]+)\\*(?!\\*)", "<i>$1</i>");
        text = text.replaceAll("(?<!_)_([^_]+)_(?!_)", "<i>$1</i>");
        text = text.replaceAll("`([^`]+)`", "<tt>$1</tt>");
        return text;
    }

    @NonNull
    private String replaceMarkdownLinks(@NonNull String escapedText) {
        Matcher matcher = Pattern.compile("\\[([^\\]]+)\\]\\((https?://[^\\s)]+)\\)").matcher(escapedText);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String label = matcher.group(1);
            String url = matcher.group(2);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("<a href=\"" + url + "\">" + label + "</a>"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    @NonNull
    private String buildProjectDetailsHtml(@NonNull ModrinthProject loadedProject) {
        ArrayList<String> lines = new ArrayList<>();

        String categories = formatTags(loadedProject.categories);
        if (!categories.isEmpty()) lines.add("<b>Categories:</b> " + escapeHtml(categories));

        String project = safeProjectId(loadedProject);
        if (!project.isEmpty()) lines.add("<b>Project ID:</b> " + escapeHtml(project));

        String slug = safeProjectSlug(loadedProject);
        if (!slug.isEmpty() && !slug.equals(project)) lines.add("<b>Slug:</b> " + escapeHtml(slug));

        if (contentType == ModManagerContentType.MODPACKS) {
            lines.add("<b>Install target:</b> selected launcher storage location");
        } else if (!gameDirectoryPath.trim().isEmpty()) {
            lines.add("<b>Install target:</b> " + escapeHtml(gameDirectoryPath));
        }

        if (lines.isEmpty()) return "";
        return "<h2>Project details</h2><p>" + joinParts(lines, "<br>") + "</p>";
    }

    @NonNull
    private String escapeHtml(@Nullable String text) {
        if (text == null) return "";
        return String.valueOf(text)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private void bindGalleryStatus(int count) {
        if (count <= 0) {
            textGallery.setVisibility(View.VISIBLE);
            textGallery.setText("No screenshots were provided for this project.");
            return;
        }
        textGallery.setVisibility(View.VISIBLE);
        textGallery.setText(count == 1 ? "1 screenshot" : count + " screenshots");
    }

    @NonNull
    private ArrayList<String> buildGalleryUrls(@NonNull ModrinthProject loadedProject) {
        ArrayList<String> urls = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        if (loadedProject.galleryUrls != null) {
            for (String value : loadedProject.galleryUrls) {
                String normalized = normalizeImageUrl(value);
                if (normalized == null || !seen.add(normalized)) continue;
                urls.add(normalized);
            }
        }
        return urls;
    }

    @Nullable
    private String firstMainImageUrl(@NonNull ModrinthProject loadedProject) {
        if (loadedProject.galleryUrls != null) {
            for (String url : loadedProject.galleryUrls) {
                String normalized = normalizeImageUrl(url);
                if (normalized != null) return normalized;
            }
        }
        return normalizeImageUrl(loadedProject.iconUrl);
    }

    private void bindDescriptionMainImage(@Nullable String url) {
        setDescriptionTextTopMargin(url == null ? 0 : dp(14));
        if (url == null) {
            imageDescriptionMain.setVisibility(View.GONE);
            imageDescriptionMain.setOnClickListener(null);
            return;
        }

        imageDescriptionMain.setVisibility(View.VISIBLE);
        NetworkImageLoader.load(imageDescriptionMain, url, getFallbackIcon());
        imageDescriptionMain.setOnClickListener(view -> showScreenshotDialog(url, 0, 1));
    }

    private void setDescriptionTextTopMargin(int marginPx) {
        ViewGroup.LayoutParams rawParams = textDescription.getLayoutParams();
        if (rawParams instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) rawParams;
            params.topMargin = marginPx;
            textDescription.setLayoutParams(params);
        }
    }

    @NonNull
    private String buildDescriptionText(@NonNull ModrinthProject loadedProject) {
        String body = sanitizeDescriptionText(loadedProject.body);
        if (!body.isEmpty()) return body;

        String description = sanitizeDescriptionText(loadedProject.description);
        return description.isEmpty() ? "No description was provided for this project." : description;
    }

    @NonNull
    private String sanitizeDescriptionText(@Nullable String rawText) {
        if (rawText == null) return "";
        String text = rawText.trim();
        if (text.isEmpty()) return "";

        String[] lines = text.split("\\r?\\n");
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            String clean = line == null ? "" : line.trim();
            if (isStandaloneImageReference(clean)) continue;
            if (builder.length() > 0) builder.append('\n');
            builder.append(line);
        }
        return builder.toString().trim();
    }

    private boolean isStandaloneImageReference(@NonNull String line) {
        if (line.isEmpty()) return false;

        String directUrl = normalizeImageUrl(line);
        if (directUrl != null && looksLikeImageUrl(directUrl)) return true;

        if (line.startsWith("![") && line.contains("](") && line.endsWith(")")) {
            int start = line.indexOf("](") + 2;
            int end = line.lastIndexOf(')');
            if (start >= 2 && end > start) {
                String markdownUrl = normalizeImageUrl(line.substring(start, end));
                return markdownUrl != null && looksLikeImageUrl(markdownUrl);
            }
        }

        String lower = line.toLowerCase(Locale.US);
        return lower.startsWith("<img ") && lower.contains("src=");
    }

    private boolean looksLikeImageUrl(@NonNull String url) {
        String lower = url.toLowerCase(Locale.US);
        int queryIndex = lower.indexOf('?');
        if (queryIndex >= 0) lower = lower.substring(0, queryIndex);
        int fragmentIndex = lower.indexOf('#');
        if (fragmentIndex >= 0) lower = lower.substring(0, fragmentIndex);
        return lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".webp")
                || lower.endsWith(".gif");
    }

    @NonNull
    private String buildProjectMetaText(@NonNull ModrinthProject loadedProject) {
        ArrayList<String> topParts = new ArrayList<>();
        topParts.add(getContentTypeTitle(contentType));
        topParts.add(resolveSource(loadedProject).getDisplayName());

        String author = loadedProject.author == null ? "" : loadedProject.author.trim();
        if (!author.isEmpty()) topParts.add("By " + author);

        ArrayList<String> statParts = new ArrayList<>();
        statParts.add(formatNumber(loadedProject.downloads) + " downloads");
        if (loadedProject.followers > 0) statParts.add(formatNumber(loadedProject.followers) + " followers");

        String updated = trimDate(loadedProject.dateModified);
        if (!updated.isEmpty()) statParts.add("Updated " + updated);

        String summary = sanitizeOneLineDescription(loadedProject.description);
        ArrayList<String> lines = new ArrayList<>();
        lines.add(joinParts(topParts, " · "));
        if (!summary.isEmpty()) lines.add(summary);
        lines.add(joinParts(statParts, " · "));
        return joinParts(lines, "\n");
    }

    @NonNull
    private String buildProjectDetailsLine(@NonNull ModrinthProject loadedProject) {
        ArrayList<String> lines = new ArrayList<>();

        String categories = formatTags(loadedProject.categories);
        if (!categories.isEmpty()) lines.add("Categories: " + categories);

        if (contentType == ModManagerContentType.MODPACKS) {
            lines.add("Install target: selected launcher storage location");
        } else if (!gameDirectoryPath.trim().isEmpty()) {
            lines.add("Install target: " + gameDirectoryPath);
        }

        return joinParts(lines, "\n");
    }

    @NonNull
    private String buildVersionStatusText(int count) {
        if (contentType == ModManagerContentType.MODPACKS) {
            return count == 1
                    ? "1 installable modpack version"
                    : count + " installable modpack versions";
        }
        return getString(R.string.content_project_details_versions_value, count);
    }

    @Nullable
    private String firstUsableImageUrl(@NonNull ModrinthProject loadedProject) {
        String icon = normalizeImageUrl(loadedProject.iconUrl);
        if (icon != null) return icon;
        if (loadedProject.galleryUrls != null) {
            for (String url : loadedProject.galleryUrls) {
                String normalized = normalizeImageUrl(url);
                if (normalized != null) return normalized;
            }
        }
        return null;
    }

    private void openWebsite(@NonNull ModrinthProject project) {
        openRawUrl(project.getWebsiteUrl());
    }

    private void openRawUrl(@NonNull String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException throwable) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
    }

    private void showScreenshotDialog(@NonNull String url, int position, int total) {
        FrameLayout container = new FrameLayout(this);
        int padding = dp(12);
        container.setPadding(padding, padding, padding, 0);

        ImageView preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int imageHeight = Math.min(Math.max(dp(260), getResources().getDisplayMetrics().heightPixels - dp(260)), dp(620));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                imageHeight,
                Gravity.CENTER
        );
        container.addView(preview, params);
        NetworkImageLoader.load(preview, url, getFallbackIcon());

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Screenshot " + (position + 1) + " of " + total)
                .setView(container)
                .setNegativeButton("Close", null)
                .setPositiveButton("Open image", (unused, which) -> openRawUrl(url))
                .create();
        dialog.setOnShowListener(unused -> FullscreenUtils.enableImmersive(this));
        dialog.setOnDismissListener(unused -> FullscreenUtils.enableImmersive(this));
        dialog.show();
        FullscreenUtils.enableImmersive(this);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void confirmInstallVersion(@NonNull VersionRow row) {
        ModrinthProject currentProject = project;
        if (currentProject == null) return;

        if (row.modpackVersion != null) {
            confirmInstallModpackVersion(currentProject, row.modpackVersion);
            return;
        }

        if (row.normalVersion == null) return;
        ModrinthVersion version = row.normalVersion;
        String targetVersion = gameVersionId.isEmpty()
                ? getString(R.string.content_browser_unknown_version)
                : gameVersionId;

        AlertDialog dialog = LauncherDialogStyle.showStyledMessageDialog(
                this,
                getString(R.string.content_project_details_install_version_title, currentProject.title, version.versionNumber),
                getString(R.string.content_project_details_install_version_message, targetVersion, displayLoader(loader)),
                getString(R.string.content_browser_install),
                (dialogInterface, which) -> installNormalVersion(currentProject, version),
                getString(android.R.string.cancel)
        );
        dialog.setOnDismissListener(unused -> FullscreenUtils.enableImmersive(this));
        FullscreenUtils.enableImmersive(this);
    }

    private void confirmInstallModpackVersion(
            @NonNull ModrinthProject currentProject,
            @NonNull ModpackInstallManager.ModpackVersionChoice version
    ) {
        StringBuilder message = new StringBuilder();
        message.append("Install ").append(currentProject.title).append(" as a new launcher instance?");
        message.append("\n\nSelected version:\n").append(version.getDisplayTitle());
        message.append("\n").append(version.getDisplaySubtitle());
        message.append("\n\nThis uses the launcher storage location currently selected in settings, including scoped storage if the user selected one.");

        if (!gameVersionId.trim().isEmpty() && !version.isCompatibleWith(gameVersionId, loader)) {
            message.append("\n\nThis modpack version does not match the current instance filter. It will still install using the pack's own Minecraft version and loader metadata.");
        }

        AlertDialog dialog = LauncherDialogStyle.showStyledMessageDialog(
                this,
                "Install Modpack",
                message.toString(),
                getString(R.string.content_browser_install),
                (dialogInterface, which) -> installModpackVersion(currentProject, version),
                getString(android.R.string.cancel)
        );
        dialog.setOnDismissListener(unused -> FullscreenUtils.enableImmersive(this));
        FullscreenUtils.enableImmersive(this);
    }

    private void installNormalVersion(@NonNull ModrinthProject project, @NonNull ModrinthVersion version) {
        if (gameDirectoryPath.trim().isEmpty()) {
            Toast.makeText(this, R.string.content_browser_missing_game_dir, Toast.LENGTH_LONG).show();
            return;
        }

        File gameDirectory = new File(gameDirectoryPath);
        if (contentType == ModManagerContentType.DATAPACKS) {
            DatapackWorldTargetPicker.choose(this, gameDirectory,
                    targetDirectory -> installNormalVersionIntoTarget(project, version, targetDirectory));
            return;
        }
        installNormalVersionIntoTarget(project, version, null);
    }

    private void installNormalVersionIntoTarget(
            @NonNull ModrinthProject project,
            @NonNull ModrinthVersion version,
            @Nullable File targetDirectoryOverride
    ) {
        File gameDirectory = new File(gameDirectoryPath);
        Logging.i("ContentInstall", "Starting " + resolveSource(project).getDisplayName() + " " + contentType.name().toLowerCase(Locale.US)
                + " install: " + project.title + " version=" + version.versionNumber
                + " mc=" + gameVersionId + " loader=" + loader + " gameDir=" + gameDirectory.getAbsolutePath()
                + (targetDirectoryOverride == null ? "" : " target=" + targetDirectoryOverride.getAbsolutePath()));
        textStatus.setText(getString(R.string.content_browser_install_started, project.title));
        ModrinthInstallManager.Listener listener = new ModrinthInstallManager.Listener() {
            @Override
            public void onStatus(@NonNull String message) {
                Logging.i("ContentInstall", message);
                runOnUiThread(() -> textStatus.setText(message));
            }

            @Override
            public void onComplete(@NonNull String message) {
                Logging.i("ContentInstall", message);
                runOnUiThread(() -> {
                    textStatus.setText(message);
                    Toast.makeText(ContentProjectDetailsActivity.this, message, Toast.LENGTH_LONG).show();
                    setResult(RESULT_OK);
                });
            }

            @Override
            public void onError(@NonNull Throwable throwable) {
                Logging.e("ContentInstall", "Content install failed for " + project.title + " " + version.versionNumber, throwable);
                runOnUiThread(() -> {
                    String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
                    textStatus.setText(getString(R.string.content_browser_install_failed, message));
                    Toast.makeText(ContentProjectDetailsActivity.this, getString(R.string.content_browser_install_failed, message), Toast.LENGTH_LONG).show();
                });
            }
        };

        Thread thread = new Thread(() -> {
            if (resolveSource(project) == ModManagerSource.CURSEFORGE) {
                CurseForgeInstallManager.installSpecificVersion(
                        new CurseForgeApiClient(this),
                        gameDirectory,
                        gameVersionId,
                        loader,
                        contentType,
                        project,
                        version,
                        targetDirectoryOverride,
                        listener
                );
            } else {
                ModrinthInstallManager.installSpecificVersion(
                        gameDirectory,
                        gameVersionId,
                        loader,
                        contentType,
                        project,
                        version,
                        targetDirectoryOverride,
                        listener
                );
            }
        }, resolveSource(project) == ModManagerSource.CURSEFORGE ? "CurseForgeInstallVersion" : "ModrinthInstallVersion");
        thread.start();
    }

    private void showModpackInstallDialog(@NonNull String title) {
        dismissModpackInstallDialog();
        modpackInstallDialog = new ModpackInstallProgressDialog(this);
        modpackInstallDialog.show(title);
    }

    private void updateModpackInstallDialog(@Nullable String message, int current, int total) {
        if (modpackInstallDialog == null) return;
        if (message != null) modpackInstallDialog.setStatus(message);
        modpackInstallDialog.setProgress(current, total);
    }

    private void dismissModpackInstallDialog() {
        if (modpackInstallDialog != null) {
            modpackInstallDialog.dismiss();
            modpackInstallDialog = null;
        }
    }

    private void installModpackVersion(
            @NonNull ModrinthProject currentProject,
            @NonNull ModpackInstallManager.ModpackVersionChoice selectedVersion
    ) {
        Logging.i("ModpackInstall", "Starting " + resolveSource(currentProject).getDisplayName() + " modpack install: "
                + currentProject.title + " version=" + selectedVersion.getDisplayTitle());
        showModpackInstallDialog(currentProject.title);
        textStatus.setText(getString(R.string.content_browser_install_started, currentProject.title));

        ModpackInstallManager.Listener listener = new ModpackInstallManager.Listener() {
            @Override
            public void onStatus(@NonNull String message) {
                Logging.i("ModpackInstall", message);
                runOnUiThread(() -> {
                    textStatus.setText(message);
                    updateModpackInstallDialog(message, -1, -1);
                });
            }

            @Override
            public void onProgress(int current, int total) {
                runOnUiThread(() -> {
                    if (total > 0) {
                        textStatus.setText("Installing " + Math.max(0, current) + " / " + total);
                    }
                    updateModpackInstallDialog(null, current, total);
                });
            }

            @Override
            public void onComplete(@NonNull String message) {
                onComplete(message, null);
            }

            @Override
            public void onComplete(@NonNull String message, @Nullable LauncherInstance instance) {
                Logging.i("ModpackInstall", message + (instance == null ? "" : " instance=" + instance.getName()));
                runOnUiThread(() -> {
                    dismissModpackInstallDialog();
                    if (instance == null) {
                        textStatus.setText(message);
                        Toast.makeText(ContentProjectDetailsActivity.this, message, Toast.LENGTH_LONG).show();
                        setResult(RESULT_OK);
                        return;
                    }
                    CleanroomMigrationDialog.offerAfterModpackInstall(
                            ContentProjectDetailsActivity.this,
                            message,
                            instance,
                            (finalInstance, finalMessage) -> {
                                textStatus.setText(finalMessage);
                                Toast.makeText(ContentProjectDetailsActivity.this, finalMessage, Toast.LENGTH_LONG).show();
                                setResult(RESULT_OK);
                                startActivity(InstanceDetailsActivity.createIntent(ContentProjectDetailsActivity.this, finalInstance));
                                finish();
                            }
                    );
                });
            }

            @Override
            public void onError(@NonNull Throwable throwable) {
                Logging.e("ModpackInstall", "Modpack install failed for " + currentProject.title + " " + selectedVersion.getDisplayTitle(), throwable);
                runOnUiThread(() -> {
                    dismissModpackInstallDialog();
                    String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
                    textStatus.setText(getString(R.string.content_browser_install_failed, message));
                    Toast.makeText(ContentProjectDetailsActivity.this, getString(R.string.content_browser_install_failed, message), Toast.LENGTH_LONG).show();
                });
            }
        };

        Thread thread = new Thread(() -> ModpackInstallManager.installFromProjectVersion(
                this,
                resolveSource(currentProject),
                safeProjectId(currentProject),
                safeProjectSlug(currentProject),
                currentProject.title,
                currentProject.iconUrl,
                selectedVersion,
                listener
        ), resolveSource(currentProject) == ModManagerSource.CURSEFORGE ? "CurseForgeModpackInstallVersion" : "ModrinthModpackInstallVersion");
        thread.start();
    }

    @NonNull
    private String safeProjectId(@NonNull ModrinthProject loadedProject) {
        String id = loadedProject.projectId == null ? "" : loadedProject.projectId.trim();
        return id.isEmpty() ? projectId : id;
    }

    @NonNull
    private String safeProjectSlug(@NonNull ModrinthProject loadedProject) {
        String slug = loadedProject.slug == null ? "" : loadedProject.slug.trim();
        return slug.isEmpty() ? projectSlug : slug;
    }

    @NonNull
    private ModManagerSource resolveSource(@NonNull ModrinthProject loadedProject) {
        return loadedProject.source == null || loadedProject.source == ModManagerSource.UNKNOWN ? source : loadedProject.source;
    }

    private int getFallbackIcon() {
        switch (contentType) {
            case MODPACKS:
                return R.drawable.ic_content_mod_24;
            case RESOURCEPACKS:
                return R.drawable.ic_content_resourcepack_24;
            case DATAPACKS:
                return R.drawable.ic_content_datapack_24;
            case SHADERPACKS:
                return R.drawable.ic_content_shaderpack_24;
            case MODS:
            default:
                return R.drawable.ic_content_mod_24;
        }
    }

    @NonNull
    private String displayLoader(@Nullable String value) {
        if (value == null || value.trim().isEmpty()) return "Vanilla";
        String trimmed = value.trim();
        return trimmed.substring(0, 1).toUpperCase(Locale.US) + trimmed.substring(1);
    }

    @NonNull
    private String getContentTypeTitle(@NonNull ModManagerContentType type) {
        switch (type) {
            case MODPACKS:
                return "Modpack";
            case RESOURCEPACKS:
                return ModManagerContentType.usesLegacyTexturePacks(gameVersionId) ? "Texture Pack" : "Resource Pack";
            case DATAPACKS:
                return "Data Pack";
            case SHADERPACKS:
                return "Shader Pack";
            case MODS:
            default:
                return "Mod";
        }
    }

    @NonNull
    private String formatNumber(long value) {
        if (value >= 1_000_000_000L) return String.format(Locale.US, "%.1fB", value / 1_000_000_000.0);
        if (value >= 1_000_000L) return String.format(Locale.US, "%.1fM", value / 1_000_000.0);
        if (value >= 1_000L) return String.format(Locale.US, "%.1fK", value / 1_000.0);
        return String.valueOf(value);
    }

    @NonNull
    private String sanitizeOneLineDescription(@Nullable String value) {
        if (value == null) return "";
        String clean = value.replace(' ', ' ').replace(' ', ' ').trim();
        while (clean.contains("  ")) clean = clean.replace("  ", " ");
        if (clean.length() > 150) clean = clean.substring(0, 147).trim() + "...";
        return clean;
    }

    @NonNull
    private String formatTags(@Nullable List<String> tags) {
        if (tags == null || tags.isEmpty()) return "Unknown";
        StringBuilder builder = new StringBuilder();
        int count = Math.min(4, tags.size());
        for (int i = 0; i < count; i++) {
            String tag = tags.get(i);
            if (tag == null || tag.trim().isEmpty()) continue;
            if (builder.length() > 0) builder.append(", ");
            builder.append(formatTag(tag));
        }
        if (tags.size() > count) builder.append(" +").append(tags.size() - count);
        return builder.length() == 0 ? "Unknown" : builder.toString();
    }

    @NonNull
    private String formatTag(@NonNull String value) {
        String clean = value.replace('-', ' ').replace('_', ' ').trim();
        if (clean.isEmpty()) return value;
        return clean.substring(0, 1).toUpperCase(Locale.US) + clean.substring(1);
    }

    @NonNull
    private String joinParts(@NonNull List<String> values, @NonNull String separator) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) continue;
            if (builder.length() > 0) builder.append(separator);
            builder.append(value.trim());
        }
        return builder.toString();
    }

    @Nullable
    private String normalizeImageUrl(@Nullable String rawUrl) {
        if (rawUrl == null) return null;
        String url = rawUrl.trim();
        if ((url.startsWith("\"") && url.endsWith("\"")) || (url.startsWith("'") && url.endsWith("'"))) {
            url = url.substring(1, url.length() - 1).trim();
        }
        if (url.isEmpty() || "null".equalsIgnoreCase(url)) return null;
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        return null;
    }

    @NonNull
    private String trimDate(@Nullable String value) {
        if (value == null) return "";
        String clean = value.trim();
        return clean.substring(0, Math.min(10, clean.length()));
    }

    private void sortRowsNewestFirst(@NonNull ArrayList<VersionRow> rows) {
        Collections.sort(rows, (left, right) -> compareNullableIsoDatesDescending(left.datePublished(), right.datePublished()));
    }

    private void sortModpackVersionsNewestFirst(
            @NonNull ArrayList<ModpackInstallManager.ModpackVersionChoice> versions
    ) {
        Collections.sort(versions, (left, right) -> {
            int dateCompare = compareNullableIsoDatesDescending(left.datePublished, right.datePublished);
            if (dateCompare != 0) return dateCompare;

            int versionCompare = compareVersionLabelsDescending(left.versionNumber, right.versionNumber);
            if (versionCompare != 0) return versionCompare;

            versionCompare = compareVersionLabelsDescending(left.versionName, right.versionName);
            if (versionCompare != 0) return versionCompare;

            return right.getDisplayTitle().compareToIgnoreCase(left.getDisplayTitle());
        });
    }

    private int compareNullableIsoDatesDescending(@Nullable String left, @Nullable String right) {
        String a = left == null ? "" : left.trim();
        String b = right == null ? "" : right.trim();
        boolean aEmpty = a.isEmpty();
        boolean bEmpty = b.isEmpty();
        if (aEmpty && bEmpty) return 0;
        if (aEmpty) return 1;
        if (bEmpty) return -1;
        return b.compareToIgnoreCase(a);
    }

    private int compareVersionLabelsDescending(@Nullable String left, @Nullable String right) {
        String a = left == null ? "" : left.trim();
        String b = right == null ? "" : right.trim();
        boolean aEmpty = a.isEmpty();
        boolean bEmpty = b.isEmpty();
        if (aEmpty && bEmpty) return 0;
        if (aEmpty) return 1;
        if (bEmpty) return -1;

        ArrayList<Integer> leftParts = extractVersionNumberParts(a);
        ArrayList<Integer> rightParts = extractVersionNumberParts(b);
        int max = Math.max(leftParts.size(), rightParts.size());
        for (int i = 0; i < max; i++) {
            int leftValue = i < leftParts.size() ? leftParts.get(i) : 0;
            int rightValue = i < rightParts.size() ? rightParts.get(i) : 0;
            if (leftValue != rightValue) {
                return Integer.compare(rightValue, leftValue);
            }
        }

        int prereleaseCompare = comparePrereleaseWeightDescending(a, b);
        if (prereleaseCompare != 0) return prereleaseCompare;

        return b.compareToIgnoreCase(a);
    }

    private int comparePrereleaseWeightDescending(@NonNull String left, @NonNull String right) {
        int leftWeight = getPrereleaseWeight(left);
        int rightWeight = getPrereleaseWeight(right);
        return Integer.compare(rightWeight, leftWeight);
    }

    private int getPrereleaseWeight(@NonNull String value) {
        String lower = value.toLowerCase(Locale.US);
        if (lower.contains("snapshot")) return 0;
        if (lower.contains("alpha")) return 1;
        if (lower.contains("beta")) return 2;
        if (lower.contains("rc") || lower.contains("release-candidate")) return 3;
        return 4;
    }

    @NonNull
    private ArrayList<Integer> extractVersionNumberParts(@NonNull String value) {
        ArrayList<Integer> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isDigit(ch)) {
                current.append(ch);
            } else if (current.length() > 0) {
                addParsedVersionNumberPart(parts, current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) addParsedVersionNumberPart(parts, current.toString());
        return parts;
    }

    private void addParsedVersionNumberPart(@NonNull ArrayList<Integer> parts, @NonNull String raw) {
        try {
            parts.add(Integer.parseInt(raw));
        } catch (Throwable ignored) {
            parts.add(0);
        }
    }

    @NonNull
    private String reflectedString(@NonNull Object object, @NonNull String fieldName) {
        try {
            Field field = object.getClass().getField(fieldName);
            Object value = field.get(object);
            return value == null ? "" : String.valueOf(value).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    @NonNull
    private ArrayList<String> reflectedStringList(@NonNull Object object, @NonNull String fieldName) {
        ArrayList<String> values = new ArrayList<>();
        try {
            Field field = object.getClass().getField(fieldName);
            Object raw = field.get(object);
            if (raw instanceof Iterable) {
                for (Object value : (Iterable<Object>) raw) {
                    if (value == null) continue;
                    String clean = String.valueOf(value).trim();
                    if (!clean.isEmpty()) values.add(clean);
                }
            }
        } catch (Throwable ignored) {
        }
        return values;
    }

    @NonNull
    private String joinShortList(@NonNull ArrayList<String> values, int limit) {
        StringBuilder builder = new StringBuilder();
        int count = Math.min(values.size(), Math.max(1, limit));
        for (int i = 0; i < count; i++) {
            if (builder.length() > 0) builder.append(", ");
            builder.append(values.get(i));
        }
        if (values.size() > count) builder.append(" +").append(values.size() - count);
        return builder.toString();
    }

    private static final class VersionRow {
        @Nullable
        final ModrinthVersion normalVersion;
        @Nullable
        final ModpackInstallManager.ModpackVersionChoice modpackVersion;

        private VersionRow(
                @Nullable ModrinthVersion normalVersion,
                @Nullable ModpackInstallManager.ModpackVersionChoice modpackVersion
        ) {
            this.normalVersion = normalVersion;
            this.modpackVersion = modpackVersion;
        }

        @NonNull
        static VersionRow normal(@NonNull ModrinthVersion version) {
            return new VersionRow(version, null);
        }

        @NonNull
        static VersionRow modpack(@NonNull ModpackInstallManager.ModpackVersionChoice version) {
            return new VersionRow(null, version);
        }

        @NonNull
        String datePublished() {
            if (modpackVersion != null) return modpackVersion.datePublished;
            return normalVersion == null || normalVersion.datePublished == null ? "" : normalVersion.datePublished;
        }

        @NonNull
        ArrayList<String> minecraftVersions() {
            ArrayList<String> values = new ArrayList<>();
            if (modpackVersion != null) {
                values.addAll(modpackVersion.gameVersions);
            } else if (normalVersion != null) {
                values.addAll(readStringList(normalVersion, "gameVersions"));
            }
            if (values.isEmpty()) values.add(VERSION_FILTER_UNKNOWN);
            return values;
        }

        @SuppressWarnings("unchecked")
        @NonNull
        private static ArrayList<String> readStringList(@NonNull Object object, @NonNull String fieldName) {
            ArrayList<String> values = new ArrayList<>();
            try {
                Field field = object.getClass().getField(fieldName);
                Object raw = field.get(object);
                if (raw instanceof Iterable) {
                    for (Object value : (Iterable<Object>) raw) {
                        if (value == null) continue;
                        String clean = String.valueOf(value).trim();
                        if (!clean.isEmpty()) values.add(clean);
                    }
                }
            } catch (Throwable ignored) {
            }
            return values;
        }
    }

    private interface MinecraftVersionFilterClickListener {
        void onMinecraftVersionClicked(@NonNull String version);
    }

    private final class MinecraftVersionFilterDialogAdapter extends RecyclerView.Adapter<MinecraftVersionFilterDialogAdapter.ViewHolder> {
        @NonNull
        private final ArrayList<String> versions;
        @NonNull
        private final String selectedVersion;
        @NonNull
        private final MinecraftVersionFilterClickListener listener;

        MinecraftVersionFilterDialogAdapter(
                @NonNull ArrayList<String> versions,
                @NonNull String selectedVersion,
                @NonNull MinecraftVersionFilterClickListener listener
        ) {
            this.versions = new ArrayList<>(versions);
            this.selectedVersion = selectedVersion;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setMinimumHeight(dp(72));

            GradientDrawable rowBackground = new GradientDrawable();
            rowBackground.setColor(LauncherDialogStyle.COLOR_CARD_BG);
            rowBackground.setCornerRadius(dp(16));
            rowBackground.setStroke(dp(1), LauncherDialogStyle.COLOR_CARD_STROKE);
            row.setBackground(rowBackground);

            TextView title = new TextView(parent.getContext());
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            title.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
            title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            TextView subtitle = new TextView(parent.getContext());
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            subtitle.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
            subtitle.setSingleLine(false);
            subtitle.setMaxLines(2);
            LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            subtitleParams.topMargin = dp(4);
            row.addView(subtitle, subtitleParams);

            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, 0, 0, dp(8));
            row.setLayoutParams(params);

            return new ViewHolder(row, title, subtitle);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            String version = versions.get(position);
            boolean selected = version.equalsIgnoreCase(selectedVersion);
            holder.title.setText(version);

            int count = getMinecraftVersionFilterCount(version);
            StringBuilder subtitle = new StringBuilder();
            subtitle.append(count).append(count == 1 ? " version" : " versions");
            if (VERSION_FILTER_ALL.equals(version)) {
                subtitle.append(" · Show everything");
            }
            if (selected) {
                subtitle.append(" · Selected");
            }
            holder.subtitle.setText(subtitle.toString());
            holder.itemView.setAlpha(selected ? 1.0f : 0.92f);
            holder.itemView.setOnClickListener(view -> listener.onMinecraftVersionClicked(version));
        }

        @Override
        public int getItemCount() {
            return versions.size();
        }

        final class ViewHolder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView subtitle;

            ViewHolder(@NonNull View itemView, @NonNull TextView title, @NonNull TextView subtitle) {
                super(itemView);
                this.title = title;
                this.subtitle = subtitle;
            }
        }
    }

    private final class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.ViewHolder> {
        private final ArrayList<String> images = new ArrayList<>();

        void submit(@NonNull List<String> values) {
            images.clear();
            images.addAll(values);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = getLayoutInflater().inflate(R.layout.item_content_gallery_image, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            String url = images.get(position);
            holder.index.setText(String.valueOf(position + 1));
            NetworkImageLoader.load(holder.image, url, getFallbackIcon());
            holder.itemView.setOnClickListener(view -> showScreenshotDialog(url, position, images.size()));
        }

        @Override
        public int getItemCount() {
            return images.size();
        }

        final class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView image;
            final TextView index;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                image = itemView.findViewById(R.id.imageGalleryScreenshot);
                index = itemView.findViewById(R.id.textGalleryIndex);
            }
        }
    }

    private final class VersionAdapter extends RecyclerView.Adapter<VersionAdapter.ViewHolder> {
        private final ArrayList<VersionRow> versions = new ArrayList<>();

        void submit(@NonNull List<VersionRow> values) {
            versions.clear();
            versions.addAll(values);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = getLayoutInflater().inflate(R.layout.item_content_version, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            VersionRow row = versions.get(position);
            if (row.modpackVersion != null) {
                bindModpackVersion(holder, row.modpackVersion);
            } else if (row.normalVersion != null) {
                bindNormalVersion(holder, row.normalVersion);
            }
            holder.install.setOnClickListener(view -> confirmInstallVersion(row));
        }

        private void bindNormalVersion(@NonNull ViewHolder holder, @NonNull ModrinthVersion version) {
            String name = version.name == null || version.name.trim().isEmpty()
                    ? version.versionNumber
                    : version.name;
            holder.name.setText(name);

            ArrayList<String> parts = new ArrayList<>();
            if (version.versionNumber != null && !version.versionNumber.trim().isEmpty()) {
                parts.add(version.versionNumber.trim());
            }
            if (version.versionType != null && !version.versionType.trim().isEmpty()) {
                parts.add(formatTag(version.versionType));
            }

            ArrayList<String> gameVersions = reflectedStringList(version, "gameVersions");
            if (!gameVersions.isEmpty()) parts.add("Minecraft " + joinShortList(gameVersions, 3));

            ArrayList<String> loaders = reflectedStringList(version, "loaders");
            if (!loaders.isEmpty()) parts.add("Loader " + joinShortList(loaders, 2));

            String date = trimDate(version.datePublished);
            if (!date.isEmpty()) parts.add(date);

            String fileName = reflectedString(version, "fileName");
            if (!fileName.isEmpty()) parts.add(fileName);

            holder.meta.setText(joinParts(parts, " · "));
        }

        private void bindModpackVersion(@NonNull ViewHolder holder, @NonNull ModpackInstallManager.ModpackVersionChoice version) {
            holder.name.setText(version.getDisplayTitle());

            ArrayList<String> parts = new ArrayList<>();
            String subtitle = version.getDisplaySubtitle();
            if (subtitle != null && !subtitle.trim().isEmpty()) parts.add(subtitle.trim());
            String fileName = version.fileName == null ? "" : version.fileName.trim();
            if (!fileName.isEmpty()) parts.add(fileName);

            boolean incompatible = !gameVersionId.trim().isEmpty() && !version.isCompatibleWith(gameVersionId, loader);
            if (incompatible) {
                parts.add("Not filtered out: installs using this pack version's own Minecraft version and loader");
            }

            holder.meta.setText(joinParts(parts, "\n"));
        }

        @Override
        public int getItemCount() {
            return versions.size();
        }

        final class ViewHolder extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView meta;
            final MaterialButton install;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.textVersionName);
                meta = itemView.findViewById(R.id.textVersionMeta);
                install = itemView.findViewById(R.id.buttonInstallVersion);
                install.setFocusable(false);
                install.setFocusableInTouchMode(false);
            }
        }
    }
}

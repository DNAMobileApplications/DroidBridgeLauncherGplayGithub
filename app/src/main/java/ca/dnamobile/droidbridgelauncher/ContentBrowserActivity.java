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
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstance;
import ca.dnamobile.droidbridgelauncher.modmanager.ModManagerContentType;
import ca.dnamobile.droidbridgelauncher.modmanager.ModManagerManifest;
import ca.dnamobile.droidbridgelauncher.modmanager.ModManagerSource;
import ca.dnamobile.droidbridgelauncher.modmanager.ModManagerVersionResolver;
import ca.dnamobile.droidbridgelauncher.modmanager.CurseForgeApiClient;
import ca.dnamobile.droidbridgelauncher.modmanager.CurseForgeInstallManager;
import ca.dnamobile.droidbridgelauncher.modmanager.CurseForgeApiKeyProvider;
import ca.dnamobile.droidbridgelauncher.modmanager.DatapackWorldTargetPicker;
import ca.dnamobile.droidbridgelauncher.modmanager.ModrinthApiClient;
import ca.dnamobile.droidbridgelauncher.modmanager.ModrinthInstallManager;
import ca.dnamobile.droidbridgelauncher.modmanager.ModpackInstallManager;
import ca.dnamobile.droidbridgelauncher.modmanager.ModpackSearchApiClient;
import ca.dnamobile.droidbridgelauncher.modmanager.ModpackFavouritesStore;
import ca.dnamobile.droidbridgelauncher.modmanager.ModrinthProject;
import ca.dnamobile.droidbridgelauncher.modmanager.NetworkImageLoader;

import org.json.JSONArray;
import org.json.JSONObject;
import ca.dnamobile.droidbridgelauncher.ui.LauncherDialogStyle;
import ca.dnamobile.droidbridgelauncher.ui.ModpackInstallProgressDialog;
import ca.dnamobile.droidbridgelauncher.ui.version.CleanroomMigrationDialog;
import ca.dnamobile.droidbridgelauncher.utils.AppOrientationHelper;
import ca.dnamobile.droidbridgelauncher.utils.FullscreenUtils;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class ContentBrowserActivity extends AppCompatActivity {
    public static final String EXTRA_PROJECT_ID = "ca.dnamobile.droidbridgelauncher.extra.PROJECT_ID";
    public static final String EXTRA_PROJECT_SLUG = "ca.dnamobile.droidbridgelauncher.extra.PROJECT_SLUG";
    public static final String EXTRA_PROJECT_TITLE = "ca.dnamobile.droidbridgelauncher.extra.PROJECT_TITLE";
    public static final String EXTRA_PROJECT_TYPE = "ca.dnamobile.droidbridgelauncher.extra.PROJECT_TYPE";
    public static final String EXTRA_PROJECT_ICON_URL = "ca.dnamobile.droidbridgelauncher.extra.PROJECT_ICON_URL";
    public static final String EXTRA_PROJECT_SOURCE = "ca.dnamobile.droidbridgelauncher.extra.PROJECT_SOURCE";

    private static final int PAGE_SIZE = 20;
    private static final long SEARCH_DEBOUNCE_DELAY_MS = 350L;

    private ImageView imageInstanceIcon;
    private TextView textInstanceName;
    private TextView textInstanceMeta;
    private MaterialButton buttonContentBrowserVersionLock;
    private TextView textContentTitle;
    private TextView textVersionChip;
    private TextView textLoaderChip;
    private TextView textResultSummary;
    private TextInputEditText editSearch;
    @Nullable
    private Drawable searchClearDrawable;
    private MaterialButtonToggleGroup sourceToggleGroup;
    private TabLayout tabContentTypes;
    private View layoutContentTypeTabsContainer;
    private View layoutContentFilterChips;
    private View layoutModpackBrowseModeTabs;
    private MaterialButtonToggleGroup modpackBrowseModeToggleGroup;
    private RecyclerView recyclerContentProjects;
    private MaterialButton buttonSortContent;
    @Nullable
    private MaterialButton buttonPagePreviousTop;
    @Nullable
    private MaterialButton buttonPageNextTop;
    @Nullable
    private TextView textPageIndicatorTop;
    @Nullable
    private MaterialButton buttonPagePreviousBottom;
    @Nullable
    private MaterialButton buttonPageNextBottom;
    @Nullable
    private TextView textPageIndicatorBottom;
    private NestedScrollView scrollRoot;

    private final ContentProjectAdapter adapter = new ContentProjectAdapter();
    private final AtomicInteger requestGeneration = new AtomicInteger(0);
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    @Nullable
    private Runnable pendingSearchRunnable;

    private int currentPage = 0;
    private int totalHits = 0;
    private ContentSource selectedSource = ContentSource.MODRINTH;
    private ModManagerContentType selectedType = ModManagerContentType.MODS;
    private ContentSort selectedSort = ContentSort.DOWNLOADS;
    private String selectedModpackMinecraftVersionFilter = "";
    private boolean showModpackFavourites = false;
    @Nullable
    private ArrayList<String> cachedReleaseMinecraftVersions;
    @Nullable
    private AlertDialog minecraftVersionLoadingDialog;
    private final ArrayList<ModManagerContentType> visibleTabTypes = new ArrayList<>();

    private String instanceId = "";
    private String instanceName = "";
    private String loader = "";
    private String baseVersionId = "";
    private String gameVersionId = "";
    private String instanceGameVersionId = "";
    private String iconPath = "";
    private String gameDirectoryPath = "";


    @Nullable
    private ModpackInstallProgressDialog modpackInstallDialog;
    private AlertDialog modpackVersionLoadingDialog;
    @Nullable
    private AlertDialog currentModpackVersionDialog;

    private final Map<String, String> resolvedProjectIconUrls = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> resolvingProjectIconUrls = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherTheme.apply(this);
        super.onCreate(savedInstanceState);
        Logging.init(this);
        AppOrientationHelper.applyToActivity(this);
        PathManager.initContextConstants(this);
        setContentView(R.layout.activity_content_browser);
        LauncherTheme.applyRainbowBackgroundIfNeeded(this);
        FullscreenUtils.enableImmersive(this);

        readExtras();
        bindViews();
        setupHeader();
        setupSourceToggle();
        setupTabs();
        setupModpackBrowseModeToggle();
        setupSearch();
        setupSortDropdown();
        setupRecycler();
        setupPagination();
        prepareTopFocus();
        loadContent(true);
        forceScrollTop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppOrientationHelper.applyToActivity(this);
        FullscreenUtils.enableImmersive(this);
        pruneInstalledManifestForCurrentTab();
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) FullscreenUtils.enableImmersive(this);
    }

    @Override
    protected void onDestroy() {
        clearPendingSearch();
        dismissModpackVersionLoadingDialog();
        dismissMinecraftVersionLoadingDialog();
        if (currentModpackVersionDialog != null) {
            currentModpackVersionDialog.dismiss();
            currentModpackVersionDialog = null;
        }
        dismissModpackInstallDialog();
        super.onDestroy();
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
        instanceGameVersionId = gameVersionId;
        iconPath = safeExtra(InstanceDetailsActivity.EXTRA_ICON_FILE, "");
        gameDirectoryPath = safeExtra(InstanceDetailsActivity.EXTRA_GAME_DIRECTORY, "");
        selectedType = ModManagerContentType.fromValue(safeExtra(InstanceDetailsActivity.EXTRA_CONTENT_CATEGORY, "mods"));
        if (gameDirectoryPath.isEmpty() && instanceId.isEmpty()) {
            // MainActivity global browser entry point: this is the only place Modpacks belongs.
            selectedType = ModManagerContentType.MODPACKS;
        } else if (selectedType == ModManagerContentType.MODPACKS) {
            // Instance/loader content browsing should never show Modpacks in the Mods/Resources/Shaders tab row.
            selectedType = ModManagerContentType.MODS;
        }
    }

    @NonNull
    private String safeExtra(@NonNull String key, @NonNull String fallback) {
        String value = getIntent().getStringExtra(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private void bindViews() {
        scrollRoot = findViewById(R.id.scrollContentBrowserRoot);
        imageInstanceIcon = findViewById(R.id.imageContentBrowserInstanceIcon);
        textInstanceName = findViewById(R.id.textContentBrowserInstanceName);
        textInstanceMeta = findViewById(R.id.textContentBrowserInstanceMeta);
        buttonContentBrowserVersionLock = findViewById(R.id.buttonContentBrowserVersionLock);
        textContentTitle = findViewById(R.id.textContentBrowserTitle);
        textVersionChip = findViewById(R.id.textContentBrowserVersionChip);
        textLoaderChip = findViewById(R.id.textContentBrowserLoaderChip);
        textResultSummary = findViewById(R.id.textContentBrowserResultSummary);
        editSearch = findViewById(R.id.editContentSearch);
        sourceToggleGroup = findViewById(R.id.toggleContentSource);
        tabContentTypes = findViewById(R.id.tabContentTypes);
        layoutContentTypeTabsContainer = findViewById(R.id.layoutContentTypeTabsContainer);
        layoutContentFilterChips = findViewById(R.id.layoutContentFilterChips);
        layoutModpackBrowseModeTabs = findViewById(R.id.layoutModpackBrowseModeTabs);
        modpackBrowseModeToggleGroup = findViewById(R.id.toggleModpackBrowseMode);
        recyclerContentProjects = findViewById(R.id.recyclerContentProjects);
        buttonSortContent = findViewById(R.id.buttonSortContent);
        buttonPagePreviousTop = findViewById(R.id.buttonPagePreviousTop);
        buttonPageNextTop = findViewById(R.id.buttonPageNextTop);
        textPageIndicatorTop = findViewById(R.id.textPageIndicatorTop);
        buttonPagePreviousBottom = findViewById(R.id.buttonPagePreviousBottom);
        buttonPageNextBottom = findViewById(R.id.buttonPageNextBottom);
        textPageIndicatorBottom = findViewById(R.id.textPageIndicatorBottom);

        MaterialButton back = findViewById(R.id.buttonBackToInstance);
        back.setOnClickListener(view -> finish());
    }

    private void setupHeader() {
        updateInstanceHeaderText();

        if (buttonContentBrowserVersionLock != null) {
            buttonContentBrowserVersionLock.setVisibility(isBrowseModpacksOnlyMode() ? View.GONE : View.VISIBLE);
            buttonContentBrowserVersionLock.setOnClickListener(view -> showInstanceContentVersionSelectorDialog());
        }

        if (imageInstanceIcon != null) {
            imageInstanceIcon.setVisibility(isBrowseModpacksOnlyMode() ? View.GONE : View.VISIBLE);
            if (!iconPath.isEmpty()) {
                File file = new File(iconPath);
                if (file.isFile()) imageInstanceIcon.setImageURI(Uri.fromFile(file));
                else imageInstanceIcon.setImageResource(R.mipmap.ic_launcher);
            } else {
                imageInstanceIcon.setImageResource(R.mipmap.ic_launcher);
            }
        }

        updateSectionLabel();
        updateFilterChips();
    }

    private void updateInstanceHeaderText() {
        if (isBrowseModpacksOnlyMode()) {
            textInstanceName.setText("Browse Modpacks");
            textInstanceMeta.setText("Install modpacks as new launcher instances");
            return;
        }

        textInstanceName.setText(instanceName);
        textInstanceMeta.setText(getString(
                R.string.content_browser_instance_meta,
                displayLoader(loader),
                gameVersionId.isEmpty() ? getString(R.string.content_browser_unknown_version) : gameVersionId
        ));
    }

    private void showInstanceContentVersionSelectorDialog() {
        if (isBrowseModpacksOnlyMode()) return;

        if (cachedReleaseMinecraftVersions != null && !cachedReleaseMinecraftVersions.isEmpty()) {
            showInstanceContentVersionSelectorDialog(cachedReleaseMinecraftVersions);
            return;
        }

        showMinecraftVersionLoadingDialog();
        Thread thread = new Thread(() -> {
            try {
                ArrayList<String> versions = fetchReleaseMinecraftVersions();
                runOnUiThread(() -> {
                    dismissMinecraftVersionLoadingDialog();
                    cachedReleaseMinecraftVersions = versions;
                    showInstanceContentVersionSelectorDialog(versions);
                });
            } catch (Throwable throwable) {
                runOnUiThread(() -> {
                    dismissMinecraftVersionLoadingDialog();
                    String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
                    Toast.makeText(this, "Unable to load Minecraft versions: " + message, Toast.LENGTH_LONG).show();
                    showInstanceContentVersionSelectorDialog(buildFallbackReleaseMinecraftVersions());
                });
            }
        }, "LoadInstanceContentReleaseVersions");
        thread.start();
    }

    private void showInstanceContentVersionSelectorDialog(@NonNull ArrayList<String> releaseVersions) {
        ArrayList<String> choices = new ArrayList<>();
        addReleaseVersionChoice(choices, instanceGameVersionId);
        addReleaseVersionChoice(choices, gameVersionId);
        for (String version : releaseVersions) {
            addReleaseVersionChoice(choices, version);
        }

        if (choices.isEmpty()) {
            Toast.makeText(this, "No release Minecraft versions were found.", Toast.LENGTH_LONG).show();
            return;
        }

        LinearLayout root = LauncherDialogStyle.createDialogRoot(
                this,
                "Select Minecraft Version",
                "Only release versions are shown. This changes the content browser filter only; it does not change or update the instance itself. Installing content for a different Minecraft version is up to your discretion."
        );

        RecyclerView versionList = new RecyclerView(this);
        versionList.setLayoutManager(new LinearLayoutManager(this));
        versionList.setNestedScrollingEnabled(false);
        versionList.setClipToPadding(false);
        versionList.setPadding(0, dp(6), 0, dp(8));

        AlertDialog[] dialogRef = new AlertDialog[1];
        ReleaseVersionDialogAdapter versionAdapter = new ReleaseVersionDialogAdapter(choices, gameVersionId, version -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
            applyUnlockedContentMinecraftVersion(version);
        });
        versionList.setAdapter(versionAdapter);

        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(dp(360), Math.max(dp(180), dp(48) * Math.min(7, choices.size())))
        );
        listParams.topMargin = dp(10);
        root.addView(versionList, listParams);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialogRef[0] = dialog;
        dialog.setOnShowListener(unused -> LauncherDialogStyle.styleDialogChrome(this, dialog));
        dialog.setOnDismissListener(unused -> FullscreenUtils.enableImmersive(this));
        dialog.show();
        LauncherDialogStyle.styleDialogChrome(this, dialog);
        FullscreenUtils.enableImmersive(this);
    }

    private void applyUnlockedContentMinecraftVersion(@NonNull String newVersion) {
        String clean = newVersion.trim();
        if (clean.isEmpty()) return;
        if (clean.equalsIgnoreCase(gameVersionId == null ? "" : gameVersionId.trim())) return;

        gameVersionId = clean;
        selectedModpackMinecraftVersionFilter = "";
        updateInstanceHeaderText();
        updateFilterChips();
        clearPendingSearch();
        loadContent(true);
        Toast.makeText(this, "Content filter changed to Minecraft " + clean, Toast.LENGTH_SHORT).show();
    }

    private void addReleaseVersionChoice(@NonNull ArrayList<String> choices, @Nullable String version) {
        String clean = version == null ? "" : version.trim();
        if (clean.isEmpty()) return;
        if (isSnapshotMinecraftVersion(clean)) return;
        if (containsIgnoreCase(choices, clean)) return;
        choices.add(clean);
    }

    private void setupSourceToggle() {
        sourceToggleGroup.check(R.id.buttonSourceModrinth);
        sourceToggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            selectedSource = checkedId == R.id.buttonSourceCurseForge
                    ? ContentSource.CURSEFORGE
                    : ContentSource.MODRINTH;
            updateSortButtonLabel();
            clearPendingSearch();
            loadContent(true);
        });
    }

    private void setupTabs() {
        tabContentTypes.removeAllTabs();
        visibleTabTypes.clear();

        if (isBrowseModpacksOnlyMode()) {
            // MainActivity/global modpack browser mode: only show Modpacks.
            selectedType = ModManagerContentType.MODPACKS;
            visibleTabTypes.add(ModManagerContentType.MODPACKS);
        } else {
            // Instance/loader content browser mode: this tab layout is ONLY for content inside a loader instance.
            // Do not use ModManagerContentType.values() here because that re-adds Modpacks.
            visibleTabTypes.add(ModManagerContentType.MODS);
            visibleTabTypes.add(ModManagerContentType.RESOURCEPACKS);
            visibleTabTypes.add(ModManagerContentType.DATAPACKS);
            visibleTabTypes.add(ModManagerContentType.SHADERPACKS);

            if (selectedType == ModManagerContentType.MODPACKS || !visibleTabTypes.contains(selectedType)) {
                selectedType = ModManagerContentType.MODS;
            }
        }

        int selectedIndex = 0;
        for (int i = 0; i < visibleTabTypes.size(); i++) {
            ModManagerContentType category = visibleTabTypes.get(i);
            tabContentTypes.addTab(tabContentTypes.newTab().setText(getTabTitle(category)));
            if (category == selectedType) selectedIndex = i;
        }

        if (layoutContentTypeTabsContainer != null) {
            layoutContentTypeTabsContainer.setVisibility(visibleTabTypes.size() <= 1 ? View.GONE : View.VISIBLE);
        }

        TabLayout.Tab tab = tabContentTypes.getTabAt(selectedIndex);
        if (tab != null) tab.select();

        tabContentTypes.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int position = tab.getPosition();
                if (position >= 0 && position < visibleTabTypes.size()) {
                    selectedType = visibleTabTypes.get(position);
                    normalizeSortForSelectedType();
                    clearPendingSearch();
                    loadContent(true);
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                normalizeSortForSelectedType();
                clearPendingSearch();
                loadContent(true);
            }
        });
    }

    private void setupModpackBrowseModeToggle() {
        boolean visible = isBrowseModpacksOnlyMode();
        if (layoutModpackBrowseModeTabs != null) {
            layoutModpackBrowseModeTabs.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (!visible || modpackBrowseModeToggleGroup == null) {
            showModpackFavourites = false;
            return;
        }

        showModpackFavourites = false;
        modpackBrowseModeToggleGroup.check(R.id.buttonModpackAll);
        modpackBrowseModeToggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            boolean favourites = checkedId == R.id.buttonModpackFavourites;
            if (showModpackFavourites == favourites) return;
            showModpackFavourites = favourites;
            currentPage = 0;
            clearPendingSearch();
            loadContent(true);
        });
    }

    private void setupSearch() {
        editSearch.setHint(showModpackFavourites && selectedType == ModManagerContentType.MODPACKS
                ? getString(R.string.content_browser_search_favourites_hint)
                : getSearchHint(selectedType));
        editSearch.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        editSearch.setSingleLine(true);
        configureSearchClearButton();
        editSearch.setOnEditorActionListener((view, actionId, event) -> {
            boolean searchAction = actionId == EditorInfo.IME_ACTION_SEARCH;
            boolean enterKey = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_UP;

            if (searchAction || enterKey) {
                runSearchNow();
                return true;
            }
            return false;
        });
        editSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateSearchClearButtonVisibility();
                scheduleSearchFromTyping();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void setupSortDropdown() {
        normalizeSortForSelectedType();
        updateSortButtonLabel();
        if (buttonSortContent == null) return;

        buttonSortContent.setOnClickListener(view -> showSortDialog());
    }

    private void showSortDialog() {
        ArrayList<ContentSort> availableSorts = new ArrayList<>();
        for (ContentSort sort : ContentSort.values()) {
            if (sort.isAvailableFor(selectedType)) availableSorts.add(sort);
        }
        if (availableSorts.isEmpty()) return;

        String[] labels = new String[availableSorts.size()];
        int checkedIndex = 0;
        for (int i = 0; i < availableSorts.size(); i++) {
            ContentSort sort = availableSorts.get(i);
            labels[i] = sort.label;
            if (sort == selectedSort) checkedIndex = i;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(selectedType == ModManagerContentType.MODPACKS ? "Sort Modpacks" : "Sort Content")
                .setSingleChoiceItems(labels, checkedIndex, (dialog, which) -> {
                    dialog.dismiss();
                    ContentSort sort = availableSorts.get(which);
                    if (sort == ContentSort.VERSIONS && selectedType == ModManagerContentType.MODPACKS) {
                        showMinecraftVersionFilterDialog();
                        return;
                    }

                    boolean changed = selectedSort != sort || !selectedModpackMinecraftVersionFilter.trim().isEmpty();
                    selectedSort = sort;
                    selectedModpackMinecraftVersionFilter = "";
                    updateSortButtonLabel();
                    if (changed) {
                        clearPendingSearch();
                        loadContent(true);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        FullscreenUtils.enableImmersive(this);
    }

    private void showMinecraftVersionFilterDialog() {
        if (cachedReleaseMinecraftVersions != null && !cachedReleaseMinecraftVersions.isEmpty()) {
            showMinecraftVersionFilterDialog(cachedReleaseMinecraftVersions);
            return;
        }

        showMinecraftVersionLoadingDialog();
        Thread thread = new Thread(() -> {
            try {
                ArrayList<String> versions = fetchReleaseMinecraftVersions();
                runOnUiThread(() -> {
                    dismissMinecraftVersionLoadingDialog();
                    cachedReleaseMinecraftVersions = versions;
                    showMinecraftVersionFilterDialog(versions);
                });
            } catch (Throwable throwable) {
                runOnUiThread(() -> {
                    dismissMinecraftVersionLoadingDialog();
                    String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
                    Toast.makeText(this, "Unable to load Minecraft versions: " + message, Toast.LENGTH_LONG).show();
                    showMinecraftVersionFilterDialog(buildFallbackReleaseMinecraftVersions());
                });
            }
        }, "LoadMinecraftReleaseVersions");
        thread.start();
    }

    private void showMinecraftVersionFilterDialog(@NonNull ArrayList<String> releaseVersions) {
        ArrayList<String> choices = new ArrayList<>();
        choices.add("All Minecraft versions");
        for (String version : releaseVersions) {
            String clean = version == null ? "" : version.trim();
            if (clean.isEmpty() || isSnapshotMinecraftVersion(clean) || containsIgnoreCase(choices, clean)) continue;
            choices.add(clean);
        }

        if (choices.size() == 1) {
            Toast.makeText(this, "No release Minecraft versions were found.", Toast.LENGTH_LONG).show();
            return;
        }

        int checkedIndex = 0;
        if (!selectedModpackMinecraftVersionFilter.trim().isEmpty()) {
            for (int i = 1; i < choices.size(); i++) {
                if (choices.get(i).equalsIgnoreCase(selectedModpackMinecraftVersionFilter.trim())) {
                    checkedIndex = i;
                    break;
                }
            }
        }

        String[] labels = choices.toArray(new String[0]);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Minecraft Version")
                .setSingleChoiceItems(labels, checkedIndex, (dialog, which) -> {
                    dialog.dismiss();
                    selectedSort = ContentSort.VERSIONS;
                    selectedModpackMinecraftVersionFilter = which == 0 ? "" : choices.get(which);
                    updateSortButtonLabel();
                    clearPendingSearch();
                    loadContent(true);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        FullscreenUtils.enableImmersive(this);
    }

    @NonNull
    private ArrayList<String> fetchReleaseMinecraftVersions() throws Exception {
        String response = httpGet("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json", null);
        JSONObject root = new JSONObject(response);
        JSONArray versionsJson = root.optJSONArray("versions");
        ArrayList<String> releases = new ArrayList<>();
        for (int i = 0; versionsJson != null && i < versionsJson.length(); i++) {
            JSONObject item = versionsJson.optJSONObject(i);
            if (item == null) continue;
            String type = item.optString("type", "").trim();
            String id = item.optString("id", "").trim();
            if (!"release".equalsIgnoreCase(type)) continue;
            if (id.isEmpty() || isSnapshotMinecraftVersion(id) || containsIgnoreCase(releases, id)) continue;
            releases.add(id);
        }
        if (releases.isEmpty()) releases.addAll(buildFallbackReleaseMinecraftVersions());
        return releases;
    }

    @NonNull
    private ArrayList<String> buildFallbackReleaseMinecraftVersions() {
        ArrayList<String> versions = new ArrayList<>();
        addFallbackVersion(versions, gameVersionId);
        String[] common = new String[]{
                "1.21.10", "1.21.9", "1.21.8", "1.21.7", "1.21.6", "1.21.5", "1.21.4", "1.21.3", "1.21.2", "1.21.1", "1.21",
                "1.20.6", "1.20.5", "1.20.4", "1.20.3", "1.20.2", "1.20.1", "1.20",
                "1.19.4", "1.19.3", "1.19.2", "1.19.1", "1.19",
                "1.18.2", "1.18.1", "1.18",
                "1.17.1", "1.17",
                "1.16.5", "1.16.4", "1.16.3", "1.16.2", "1.16.1", "1.16",
                "1.15.2", "1.15.1", "1.15",
                "1.14.4", "1.14.3", "1.14.2", "1.14.1", "1.14",
                "1.13.2", "1.13.1", "1.13",
                "1.12.2", "1.12.1", "1.12",
                "1.11.2", "1.11.1", "1.11",
                "1.10.2", "1.10",
                "1.9.4", "1.9.2", "1.9",
                "1.8.9", "1.8.8", "1.8",
                "1.7.10", "1.7.2",
                "1.6.4", "1.6.2", "1.5.2", "1.4.7", "1.3.2", "1.2.5", "1.1", "1.0"
        };
        for (String version : common) addFallbackVersion(versions, version);
        return versions;
    }

    private void addFallbackVersion(@NonNull ArrayList<String> versions, @Nullable String version) {
        String clean = version == null ? "" : version.trim();
        if (clean.isEmpty() || isSnapshotMinecraftVersion(clean) || containsIgnoreCase(versions, clean)) return;
        versions.add(clean);
    }

    private boolean isSnapshotMinecraftVersion(@NonNull String version) {
        String lower = version.trim().toLowerCase(Locale.US);
        if (lower.isEmpty()) return true;
        if (lower.contains("snapshot") || lower.contains("pre") || lower.contains("rc")) return true;
        if (lower.matches("\\d{2}w\\d{2}[a-z]")) return true;
        return lower.startsWith("combat") || lower.startsWith("pending") || lower.startsWith("experimental");
    }

    private void showMinecraftVersionLoadingDialog() {
        dismissMinecraftVersionLoadingDialog();
        minecraftVersionLoadingDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Loading Minecraft Versions")
                .setMessage("Fetching release versions...")
                .setCancelable(true)
                .create();
        minecraftVersionLoadingDialog.show();
        FullscreenUtils.enableImmersive(this);
    }

    private void dismissMinecraftVersionLoadingDialog() {
        if (minecraftVersionLoadingDialog != null) {
            minecraftVersionLoadingDialog.dismiss();
            minecraftVersionLoadingDialog = null;
        }
    }

    private void normalizeSortForSelectedType() {
        if (!selectedSort.isAvailableFor(selectedType)) {
            selectedSort = ContentSort.DOWNLOADS;
            selectedModpackMinecraftVersionFilter = "";
        }
        if (selectedSort != ContentSort.VERSIONS) {
            selectedModpackMinecraftVersionFilter = "";
        }
        updateSortButtonLabel();
    }

    private void updateSortButtonLabel() {
        if (buttonSortContent == null) return;
        if (selectedType == ModManagerContentType.MODPACKS && selectedSort == ContentSort.VERSIONS) {
            String version = selectedModpackMinecraftVersionFilter.trim();
            buttonSortContent.setText(version.isEmpty() ? "Versions: All" : "Versions: " + version);
            return;
        }
        buttonSortContent.setText(selectedSort.label);
    }

    @NonNull
    private String getEffectiveModpackGameVersionFilter(@NonNull ContentSort sort) {
        if (sort == ContentSort.VERSIONS) {
            return selectedModpackMinecraftVersionFilter.trim();
        }
        return gameVersionId == null ? "" : gameVersionId.trim();
    }

    private void configureSearchClearButton() {
        try {
            searchClearDrawable = getResources().getDrawable(android.R.drawable.ic_menu_close_clear_cancel);
            if (searchClearDrawable != null) {
                int size = dp(22);
                searchClearDrawable.setBounds(0, 0, size, size);
            }
        } catch (Throwable ignored) {
            searchClearDrawable = null;
        }

        editSearch.setCompoundDrawablePadding(dp(8));
        editSearch.setOnTouchListener((view, event) -> {
            if (event.getAction() != MotionEvent.ACTION_UP) return false;
            if (!hasSearchText()) return false;

            Drawable endDrawable = editSearch.getCompoundDrawablesRelative()[2];
            if (endDrawable == null) return false;

            int clearStart = editSearch.getWidth() - editSearch.getPaddingEnd() - endDrawable.getBounds().width() - dp(16);
            if (event.getX() < clearStart) return false;

            clearPendingSearch();
            editSearch.setText("");
            clearPendingSearch();
            updateSearchClearButtonVisibility();
            loadContent(true, true);
            return true;
        });
        updateSearchClearButtonVisibility();
    }

    private boolean hasSearchText() {
        return editSearch != null
                && editSearch.getText() != null
                && editSearch.getText().toString().trim().length() > 0;
    }

    private void updateSearchClearButtonVisibility() {
        if (editSearch == null) return;
        Drawable endDrawable = hasSearchText() ? searchClearDrawable : null;
        editSearch.setCompoundDrawablesRelative(null, null, endDrawable, null);
    }

    private void scheduleSearchFromTyping() {
        clearPendingSearch();
        pendingSearchRunnable = () -> {
            pendingSearchRunnable = null;
            loadContent(true, false);
        };
        searchHandler.postDelayed(pendingSearchRunnable, SEARCH_DEBOUNCE_DELAY_MS);
    }

    private void runSearchNow() {
        clearPendingSearch();
        hideSearchKeyboardAndClearFocus();
        loadContent(true, true);
    }

    private void hideSearchKeyboardAndClearFocus() {
        if (editSearch == null) return;

        try {
            InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputMethodManager != null) {
                inputMethodManager.hideSoftInputFromWindow(editSearch.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
        }

        editSearch.clearFocus();
    }

    private void clearPendingSearch() {
        if (pendingSearchRunnable != null) {
            searchHandler.removeCallbacks(pendingSearchRunnable);
            pendingSearchRunnable = null;
        }
    }

    private void setupRecycler() {
        recyclerContentProjects.setLayoutManager(new LinearLayoutManager(this));
        recyclerContentProjects.setNestedScrollingEnabled(false);
        recyclerContentProjects.setFocusable(false);
        recyclerContentProjects.setHasFixedSize(false);
        recyclerContentProjects.setAdapter(adapter);
    }

    private void setupPagination() {
        setupPaginationButton(buttonPagePreviousTop, false);
        setupPaginationButton(buttonPagePreviousBottom, false);
        setupPaginationButton(buttonPageNextTop, true);
        setupPaginationButton(buttonPageNextBottom, true);
    }

    private void setupPaginationButton(@Nullable MaterialButton button, boolean next) {
        if (button == null) return;
        button.setFocusable(false);
        button.setOnClickListener(view -> {
            int totalPages = getTotalPages(totalHits);
            if (next) {
                if (currentPage + 1 >= totalPages) return;
                currentPage++;
            } else {
                if (currentPage <= 0) return;
                currentPage--;
            }
            clearPendingSearch();
            loadContent(false);
        });
    }

    private void prepareTopFocus() {
        if (scrollRoot == null) return;
        scrollRoot.setFocusableInTouchMode(true);
        scrollRoot.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        scrollRoot.requestFocus();
        if (editSearch != null) editSearch.clearFocus();
    }

    private void forceScrollTop() {
        if (scrollRoot == null) return;
        scrollRoot.post(() -> {
            scrollRoot.fullScroll(View.FOCUS_UP);
            scrollRoot.scrollTo(0, 0);
        });
        scrollRoot.postDelayed(() -> {
            scrollRoot.fullScroll(View.FOCUS_UP);
            scrollRoot.scrollTo(0, 0);
        }, 120L);
    }

    private void pruneInstalledManifestForCurrentTab() {
        if (selectedType == ModManagerContentType.MODPACKS) return;
        if (gameDirectoryPath == null || gameDirectoryPath.trim().isEmpty()) return;
        ModManagerManifest.pruneMissingFiles(new File(gameDirectoryPath), selectedType);
    }

    private void loadContent(boolean resetPage) {
        loadContent(resetPage, true);
    }

    private void loadContent(boolean resetPage, boolean scrollToTopWhenLoaded) {
        normalizeSortForSelectedType();
        editSearch.setHint(showModpackFavourites && selectedType == ModManagerContentType.MODPACKS
                ? getString(R.string.content_browser_search_favourites_hint)
                : getSearchHint(selectedType));
        updateSearchClearButtonVisibility();
        updateSectionLabel();
        updateFilterChips();
        pruneInstalledManifestForCurrentTab();

        if (resetPage) currentPage = 0;
        int generation = requestGeneration.incrementAndGet();

        String query = editSearch.getText() == null ? "" : editSearch.getText().toString().trim();
        int offset = currentPage * PAGE_SIZE;
        ModManagerContentType requestedType = selectedType;
        ContentSource requestedSource = selectedSource;
        ContentSort requestedSort = selectedSort;
        boolean requestedFavourites = requestedType == ModManagerContentType.MODPACKS && showModpackFavourites;
        String requestedModpackGameVersion = getEffectiveModpackGameVersionFilter(requestedSort);
        showResultSummary(getString(R.string.content_browser_loading_source, getSelectedSourceLabel()));
        updatePaginationControls();

        Thread thread = new Thread(() -> {
            try {
                ArrayList<ModrinthProject> hits;
                int total;

                if (requestedType == ModManagerContentType.MODPACKS) {
                    ModManagerSource provider = requestedSource == ContentSource.CURSEFORGE
                            ? ModManagerSource.CURSEFORGE
                            : ModManagerSource.MODRINTH;
                    if (requestedFavourites) {
                        hits = loadFavouriteModpacks(provider, query, loader);
                        total = hits.size();
                    } else {
                        ModpackSearchApiClient.SearchResult result = ModpackSearchApiClient.search(
                                this,
                                provider,
                                query,
                                requestedModpackGameVersion,
                                loader,
                                PAGE_SIZE,
                                offset,
                                requestedSort.apiKey
                        );
                        hits = result.hits;
                        total = result.totalHits;
                    }
                    applyClientSideSortIfNeeded(hits, requestedType, requestedSort);
                } else if (requestedSource == ContentSource.CURSEFORGE) {
                    CurseForgeApiClient.SearchResult result = new CurseForgeApiClient(this).searchProjects(
                            query,
                            requestedType,
                            gameVersionId,
                            loader,
                            PAGE_SIZE,
                            offset,
                            query.isEmpty() ? "downloads" : "popularity"
                    );
                    hits = result.hits;
                    total = result.totalHits;
                } else {
                    ModrinthApiClient.SearchResult result = new ModrinthApiClient().searchProjects(
                            query,
                            requestedType,
                            gameVersionId,
                            loader,
                            PAGE_SIZE,
                            offset,
                            query.isEmpty() ? "downloads" : "relevance"
                    );
                    hits = result.hits;
                    total = result.totalHits;
                }

                runOnUiThread(() -> {
                    if (generation != requestGeneration.get()) return;
                    totalHits = total;
                    adapter.submit(hits);
                    updatePaginationControls();
                    updateResultSummary();
                    if (scrollToTopWhenLoaded && (editSearch == null || !editSearch.hasFocus())) {
                        forceScrollTop();
                    }
                });
            } catch (Throwable throwable) {
                runOnUiThread(() -> {
                    if (generation != requestGeneration.get()) return;
                    adapter.submit(new ArrayList<>());
                    totalHits = 0;
                    updatePaginationControls();
                    showResultSummary(getString(R.string.content_browser_load_failed, throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName()));
                });
            }
        }, requestedSource == ContentSource.CURSEFORGE ? "CurseForgeSearch" : "ModrinthSearch");
        thread.start();
    }

    @NonNull
    private ArrayList<ModrinthProject> loadFavouriteModpacks(
            @NonNull ModManagerSource source,
            @NonNull String query,
            @Nullable String requestedLoader
    ) {
        ArrayList<ModrinthProject> hits = new ArrayList<>();
        for (ModpackFavouritesStore.FavouriteRecord record : ModpackFavouritesStore.list(this, source)) {
            try {
                ModrinthProject project = ModpackSearchApiClient.resolveFavourite(this, source, record, requestedLoader);
                if (matchesFavouriteQuery(project, query)) hits.add(project);
            } catch (Throwable throwable) {
                Logging.e("ModpackFavourites", "Unable to resolve favourite " + record.title + " from " + source.getDisplayName(), throwable);
            }
        }
        return hits;
    }

    private boolean matchesFavouriteQuery(@NonNull ModrinthProject project, @NonNull String query) {
        String needle = query.trim().toLowerCase(Locale.US);
        if (needle.isEmpty()) return true;
        String author = project.author == null ? "" : project.author;
        return (project.title + " " + project.description + " " + author + " " + project.slug)
                .toLowerCase(Locale.US)
                .contains(needle);
    }

    private void applyClientSideSortIfNeeded(
            @NonNull ArrayList<ModrinthProject> hits,
            @NonNull ModManagerContentType type,
            @NonNull ContentSort sort
    ) {
        if (type != ModManagerContentType.MODPACKS || hits.size() < 2) return;

        if (sort == ContentSort.NAME) {
            Collections.sort(hits, (left, right) -> safeSortText(left.title).compareToIgnoreCase(safeSortText(right.title)));
        } else if (sort == ContentSort.UPDATED || sort == ContentSort.NEWEST) {
            Collections.sort(hits, (left, right) -> safeSortText(right.dateModified).compareToIgnoreCase(safeSortText(left.dateModified)));
        } else if (sort == ContentSort.FOLLOWERS) {
            Collections.sort(hits, (left, right) -> Long.compare(right.followers, left.followers));
        } else {
            // Downloads is the default popularity order. Versions is a Minecraft-version filter,
            // not a date sort, so it should still show the most-downloaded compatible modpacks first.
            Collections.sort(hits, (left, right) -> Long.compare(right.downloads, left.downloads));
        }

    }

    private void pinFeaturedModpacksFirst(@NonNull ArrayList<ModrinthProject> hits) {
        if (hits.isEmpty()) return;

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
            int vulkanIndex = optiMobile != null
                    ? insertIndex
                    : Math.min(1, hits.size());
            hits.add(vulkanIndex, vulkanDroid);
        }
    }

    private boolean isOptiMobileModpack(@NonNull ModrinthProject project) {
        String title = normalizeProjectIdentity(project.title);
        String slug = normalizeProjectIdentity(project.slug);
        String id = project.projectId == null ? "" : project.projectId.trim();

        if (project.source == ModManagerSource.CURSEFORGE) {
            return id.equals("1456510")
                    || id.equals("1385364")
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

    @NonNull
    private String normalizeProjectIdentity(@Nullable String value) {
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

    private boolean isVulkanDroidModpack(@NonNull ModrinthProject project) {
        String title = project.title == null ? "" : project.title.trim().toLowerCase(Locale.US);
        String slug = project.slug == null ? "" : project.slug.trim().toLowerCase(Locale.US);
        String id = project.projectId == null ? "" : project.projectId.trim().toLowerCase(Locale.US);
        if (project.source == ModManagerSource.CURSEFORGE) {
            return id.equals("1421646")
                    || slug.equals("vulkan-droid")
                    || title.contains("vulkan droid");
        }
        return id.equalsIgnoreCase("PXY8IEra")
                || slug.equals("vulkan-droid")
                || title.contains("vulkan droid");
    }

    @NonNull
    private String getProjectAuthorDisplayName(@NonNull ModrinthProject project) {
        if (isVulkanDroidModpack(project)) {
            return "MisterDNEH";
        }
        return project.author == null || project.author.trim().isEmpty()
                ? project.source.getDisplayName()
                : project.author;
    }

    @NonNull
    private String safeSortText(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private void updateResultSummary() {
        hideResultSummary();
    }

    private void showResultSummary(@NonNull String message) {
        if (textResultSummary == null) return;
        textResultSummary.setText(message);
        textResultSummary.setVisibility(View.VISIBLE);
    }

    private void hideResultSummary() {
        if (textResultSummary == null) return;
        textResultSummary.setText("");
        textResultSummary.setVisibility(View.GONE);
    }

    private void updatePaginationControls() {
        if (selectedType == ModManagerContentType.MODPACKS && showModpackFavourites) {
            setPaginationButtonEnabled(buttonPagePreviousTop, false);
            setPaginationButtonEnabled(buttonPagePreviousBottom, false);
            setPaginationButtonEnabled(buttonPageNextTop, false);
            setPaginationButtonEnabled(buttonPageNextBottom, false);
            String indicator = getString(R.string.content_browser_page_indicator, 1, 1);
            setPaginationIndicatorText(textPageIndicatorTop, indicator);
            setPaginationIndicatorText(textPageIndicatorBottom, indicator);
            return;
        }
        int totalPages = getTotalPages(totalHits);
        boolean hasPrevious = currentPage > 0;
        boolean hasNext = currentPage + 1 < totalPages;
        String indicator = getString(R.string.content_browser_page_indicator, currentPage + 1, totalPages);
        setPaginationButtonEnabled(buttonPagePreviousTop, hasPrevious);
        setPaginationButtonEnabled(buttonPagePreviousBottom, hasPrevious);
        setPaginationButtonEnabled(buttonPageNextTop, hasNext);
        setPaginationButtonEnabled(buttonPageNextBottom, hasNext);
        setPaginationIndicatorText(textPageIndicatorTop, indicator);
        setPaginationIndicatorText(textPageIndicatorBottom, indicator);
    }

    private void setPaginationButtonEnabled(@Nullable MaterialButton button, boolean enabled) {
        if (button != null) button.setEnabled(enabled);
    }

    private void setPaginationIndicatorText(@Nullable TextView view, @NonNull String text) {
        if (view != null) view.setText(text);
    }

    private int getTotalPages(int totalItems) {
        if (totalItems <= 0) return 1;
        return (int) Math.ceil(totalItems / (double) PAGE_SIZE);
    }

    private void updateSectionLabel() {
        if (textContentTitle == null) return;

        // The global Browse Modpacks screen already says Browse Modpacks in the header and
        // has All Modpacks / Favourites directly above this controls row. Repeating
        // "Modpacks" beside the sort dropdown wastes scarce portrait width, so keep this
        // small section label only for per-instance Mods / Resource Packs / Shaders.
        if (isBrowseModpacksOnlyMode()) {
            textContentTitle.setVisibility(View.GONE);
            return;
        }

        textContentTitle.setVisibility(View.VISIBLE);
        textContentTitle.setText(getTabTitle(selectedType));
    }

    private void updateFilterChips() {
        // The active Minecraft version and loader are already shown in the top toolbar
        // beside the lock button. Showing the same values again below the sort row
        // made the content browser feel duplicated and cluttered, especially on the
        // Mods/Resource Packs/Shaders screen. Keep the chip views bound for older layout
        // compatibility, but keep the whole row hidden.
        if (textVersionChip != null) textVersionChip.setVisibility(View.GONE);
        if (textLoaderChip != null) textLoaderChip.setVisibility(View.GONE);
        setFilterChipContainerVisibility(false);
    }

    private void setFilterChipContainerVisibility(boolean visible) {
        if (layoutContentFilterChips != null) {
            layoutContentFilterChips.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void showProjectMenu(@NonNull View anchor, @NonNull ModrinthProject project) {
        // Use Material's native list-dialog layout here rather than hand-built TextView cards.
        // It automatically follows the selected DroidBridge theme and handles portrait,
        // landscape, font scaling and short screens without squishing/cropping the actions.
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();

        if (selectedType == ModManagerContentType.MODPACKS) {
            boolean currentlyFavourite = ModpackFavouritesStore.isFavourite(this, project);
            labels.add(getString(currentlyFavourite
                    ? R.string.content_browser_remove_favourite
                    : R.string.content_browser_add_favourite));
            actions.add(() -> {
                ModpackFavouritesStore.setFavourite(this, project, !currentlyFavourite);
                Toast.makeText(
                        this,
                        currentlyFavourite
                                ? getString(R.string.content_browser_favourite_removed)
                                : getString(R.string.content_browser_favourite_added),
                        Toast.LENGTH_SHORT
                ).show();
                if (showModpackFavourites) loadContent(true);
                else adapter.notifyDataSetChanged();
            });
        }

        if (!isProjectInstalled(project)) {
            labels.add(getString(R.string.content_browser_install));
            actions.add(() -> confirmInstall(project));
        }

        labels.add("View Details");
        actions.add(() -> openProjectDetails(project));

        labels.add(getString(R.string.content_browser_open_website));
        actions.add(() -> openProjectWebsite(project));

        CharSequence[] items = labels.toArray(new CharSequence[0]);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(project.title == null || project.title.trim().isEmpty()
                        ? "Content options"
                        : project.title)
                .setItems(items, (unused, which) -> {
                    if (which >= 0 && which < actions.size()) actions.get(which).run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(unused -> LauncherDialogStyle.styleDialogChrome(this, dialog));
        dialog.setOnDismissListener(unused -> FullscreenUtils.enableImmersive(this));
        dialog.show();
        LauncherDialogStyle.styleDialogChrome(this, dialog);
        FullscreenUtils.enableImmersive(this);
    }

    private void confirmInstall(@NonNull ModrinthProject project) {
        if (selectedType == ModManagerContentType.MODPACKS) {
            showModpackVersionPicker(project);
            return;
        }

        if (gameDirectoryPath.trim().isEmpty()) {
            Toast.makeText(this, R.string.content_browser_missing_game_dir, Toast.LENGTH_LONG).show();
            return;
        }

        String message = getString(
                R.string.content_browser_install_message,
                project.title,
                getPluralLabel(selectedType),
                gameVersionId.isEmpty() ? getString(R.string.content_browser_unknown_version) : gameVersionId,
                displayLoader(loader)
        );

        AlertDialog dialog = LauncherDialogStyle.showStyledMessageDialog(
                this,
                getString(R.string.content_browser_install_title_value, project.title),
                message,
                getString(R.string.content_browser_install),
                (dialogInterface, which) -> installProject(project),
                getString(android.R.string.cancel)
        );
        dialog.setOnDismissListener(unused -> FullscreenUtils.enableImmersive(this));
        FullscreenUtils.enableImmersive(this);
    }

    private void installProject(@NonNull ModrinthProject project) {
        File gameDirectory = new File(gameDirectoryPath);
        if (selectedType == ModManagerContentType.DATAPACKS) {
            DatapackWorldTargetPicker.choose(this, gameDirectory, targetDirectory -> installProjectIntoTarget(project, targetDirectory));
            return;
        }
        installProjectIntoTarget(project, null);
    }

    private void installProjectIntoTarget(@NonNull ModrinthProject project, @Nullable File targetDirectoryOverride) {
        File gameDirectory = new File(gameDirectoryPath);
        Logging.i("ContentInstall", "Starting " + project.source.getDisplayName() + " " + selectedType.name().toLowerCase(Locale.US) + " install: " + project.title
                + " mc=" + gameVersionId + " loader=" + loader + " gameDir=" + gameDirectory.getAbsolutePath()
                + (targetDirectoryOverride == null ? "" : " target=" + targetDirectoryOverride.getAbsolutePath()));
        Toast.makeText(this, getString(R.string.content_browser_install_started, project.title), Toast.LENGTH_SHORT).show();

        if (selectedType == ModManagerContentType.MODPACKS) {
            showModpackVersionPicker(project);
            return;
        }

        ModrinthInstallManager.Listener installListener = new ModrinthInstallManager.Listener() {
            @Override
            public void onStatus(@NonNull String message) {
                Logging.i("ContentInstall", message);
                runOnUiThread(() -> showResultSummary(message));
            }

            @Override
            public void onComplete(@NonNull String message) {
                Logging.i("ContentInstall", message);
                runOnUiThread(() -> {
                    showResultSummary(message);
                    Toast.makeText(ContentBrowserActivity.this, message, Toast.LENGTH_LONG).show();
                    setResult(RESULT_OK);
                    adapter.notifyDataSetChanged();
                });
            }

            @Override
            public void onError(@NonNull Throwable throwable) {
                Logging.e("ContentInstall", "Content install failed for " + project.title, throwable);
                runOnUiThread(() -> {
                    String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
                    showResultSummary(getString(R.string.content_browser_install_failed, message));
                    Toast.makeText(ContentBrowserActivity.this, getString(R.string.content_browser_install_failed, message), Toast.LENGTH_LONG).show();
                });
            }
        };

        Thread thread = new Thread(() -> {
            if (project.source == ModManagerSource.CURSEFORGE) {
                CurseForgeInstallManager.installLatestCompatible(
                        new CurseForgeApiClient(this),
                        gameDirectory,
                        gameVersionId,
                        loader,
                        selectedType,
                        project,
                        targetDirectoryOverride,
                        installListener
                );
            } else {
                ModrinthInstallManager.installLatestCompatible(
                        gameDirectory,
                        gameVersionId,
                        loader,
                        selectedType,
                        project,
                        targetDirectoryOverride,
                        installListener
                );
            }
        }, project.source == ModManagerSource.CURSEFORGE ? "CurseForgeInstall" : "ModrinthInstall");
        thread.start();
    }

    private void installModpackProject(@NonNull ModrinthProject project, @Nullable ModpackInstallManager.ModpackVersionChoice selectedVersion) {
        Logging.i("ModpackInstall", "Starting " + project.source.getDisplayName() + " modpack install: " + project.title
                + (selectedVersion == null ? " latest compatible" : " version=" + selectedVersion.getDisplayTitle())
                + " mc=" + gameVersionId + " loader=" + loader);
        showModpackInstallDialog(project.title);

        ModpackInstallManager.Listener listener = new ModpackInstallManager.Listener() {
            @Override
            public void onStatus(@NonNull String message) {
                Logging.i("ModpackInstall", message);
                runOnUiThread(() -> {
                    showResultSummary(message);
                    updateModpackInstallDialog(message, -1, -1);
                });
            }

            @Override
            public void onProgress(int current, int total) {
                runOnUiThread(() -> updateModpackInstallDialog(null, current, total));
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
                        showResultSummary(message);
                        Toast.makeText(ContentBrowserActivity.this, message, Toast.LENGTH_LONG).show();
                        setResult(RESULT_OK);
                        return;
                    }
                    CleanroomMigrationDialog.offerAfterModpackInstall(
                            ContentBrowserActivity.this,
                            message,
                            instance,
                            (finalInstance, finalMessage) -> {
                                showResultSummary(finalMessage);
                                Toast.makeText(ContentBrowserActivity.this, finalMessage, Toast.LENGTH_LONG).show();
                                setResult(RESULT_OK);
                                openInstalledModpackInstance(finalInstance);
                            }
                    );
                });
            }

            @Override
            public void onError(@NonNull Throwable throwable) {
                Logging.e("ModpackInstall", "Modpack install failed for " + project.title, throwable);
                runOnUiThread(() -> {
                    dismissModpackInstallDialog();
                    String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
                    showResultSummary(getString(R.string.content_browser_install_failed, message));
                    Toast.makeText(ContentBrowserActivity.this, getString(R.string.content_browser_install_failed, message), Toast.LENGTH_LONG).show();
                });
            }
        };

        Thread thread = new Thread(() -> {
            if (selectedVersion != null) {
                ModpackInstallManager.installFromProjectVersion(
                        this,
                        project.source,
                        project.projectId,
                        project.slug,
                        project.title,
                        project.iconUrl,
                        selectedVersion,
                        listener
                );
            } else {
                ModpackInstallManager.installFromProject(
                        this,
                        project.source,
                        project.projectId,
                        project.slug,
                        project.title,
                        project.iconUrl,
                        gameVersionId,
                        loader,
                        listener
                );
            }
        }, project.source == ModManagerSource.CURSEFORGE ? "CurseForgeModpackInstall" : "ModrinthModpackInstall");
        thread.start();
    }


    private void showModpackVersionPicker(@NonNull ModrinthProject project) {
        showModpackVersionLoadingDialog(project.title);

        Thread thread = new Thread(() -> {
            try {
                ArrayList<ModpackInstallManager.ModpackVersionChoice> versions =
                        ModpackInstallManager.listProjectVersions(
                                this,
                                project.source,
                                project.projectId,
                                project.slug
                        );

                runOnUiThread(() -> {
                    dismissModpackVersionLoadingDialog();
                    if (versions.isEmpty()) {
                        Toast.makeText(
                                ContentBrowserActivity.this,
                                "No installable modpack versions were found for " + project.title + ".",
                                Toast.LENGTH_LONG
                        ).show();
                        return;
                    }
                    showModpackVersionSelectionDialog(project, versions);
                });
            } catch (Throwable throwable) {
                runOnUiThread(() -> {
                    dismissModpackVersionLoadingDialog();
                    String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
                    Toast.makeText(
                            ContentBrowserActivity.this,
                            "Unable to load modpack versions: " + message,
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        }, project.source == ModManagerSource.CURSEFORGE ? "CurseForgeModpackVersions" : "ModrinthModpackVersions");
        thread.start();
    }

    private void showModpackVersionLoadingDialog(@NonNull String title) {
        dismissModpackVersionLoadingDialog();
        modpackVersionLoadingDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Loading Versions")
                .setMessage("Fetching available versions for " + title + "...")
                .setCancelable(true)
                .create();
        modpackVersionLoadingDialog.show();
        FullscreenUtils.enableImmersive(this);
    }

    private void dismissModpackVersionLoadingDialog() {
        if (modpackVersionLoadingDialog != null) {
            modpackVersionLoadingDialog.dismiss();
            modpackVersionLoadingDialog = null;
        }
    }

    private void showModpackVersionSelectionDialog(
            @NonNull ModrinthProject project,
            @NonNull ArrayList<ModpackInstallManager.ModpackVersionChoice> versions
    ) {
        LinkedHashMap<String, ArrayList<ModpackInstallManager.ModpackVersionChoice>> grouped =
                groupModpackVersionsByMinecraftVersion(versions);

        if (grouped.isEmpty()) {
            Toast.makeText(
                    ContentBrowserActivity.this,
                    "No installable modpack versions were found for " + project.title + ".",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        showMinecraftVersionPickerDialog(project, grouped);
    }

    private void showMinecraftVersionPickerDialog(
            @NonNull ModrinthProject project,
            @NonNull LinkedHashMap<String, ArrayList<ModpackInstallManager.ModpackVersionChoice>> grouped
    ) {
        LinearLayout layout = LauncherDialogStyle.createDialogRoot(
                this,
                "Pick Minecraft Version",
                "Choose the Minecraft version first. The next screen will show every pack version available for that Minecraft version."
        );

        ArrayList<ModpackMinecraftVersionGroup> groups = new ArrayList<>();
        for (Map.Entry<String, ArrayList<ModpackInstallManager.ModpackVersionChoice>> entry : grouped.entrySet()) {
            groups.add(new ModpackMinecraftVersionGroup(entry.getKey(), entry.getValue()));
        }

        RecyclerView minecraftVersionList = new RecyclerView(this);
        minecraftVersionList.setLayoutManager(new LinearLayoutManager(this));
        minecraftVersionList.setNestedScrollingEnabled(false);
        minecraftVersionList.setClipToPadding(false);
        minecraftVersionList.setPadding(0, dp(8), 0, dp(12));
        minecraftVersionList.setAdapter(new ModpackMinecraftVersionDialogAdapter(groups, group -> {
            AlertDialog dialog = currentModpackVersionDialog;
            if (dialog != null) dialog.dismiss();
            showModpackVersionsForMinecraftDialog(project, grouped, group.minecraftVersion, group.versions);
        }));

        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int listHeight = Math.min(Math.max(dp(240), screenHeight - dp(300)), dp(460));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                listHeight
        );
        listParams.topMargin = dp(4);
        layout.addView(minecraftVersionList, listParams);

        AlertDialog minecraftVersionDialog = new MaterialAlertDialogBuilder(this)
                .setView(layout)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        currentModpackVersionDialog = minecraftVersionDialog;

        minecraftVersionDialog.setOnDismissListener(dialog -> {
            if (currentModpackVersionDialog == minecraftVersionDialog) {
                currentModpackVersionDialog = null;
            }
            FullscreenUtils.enableImmersive(this);
        });
        minecraftVersionDialog.setOnShowListener(dialog -> {
            LauncherDialogStyle.styleDialogChrome(this, minecraftVersionDialog);
            FullscreenUtils.enableImmersive(this);
        });
        minecraftVersionDialog.show();
        LauncherDialogStyle.styleDialogChrome(this, minecraftVersionDialog);
        FullscreenUtils.enableImmersive(this);
    }


    private void showModpackVersionsForMinecraftDialog(
            @NonNull ModrinthProject project,
            @NonNull LinkedHashMap<String, ArrayList<ModpackInstallManager.ModpackVersionChoice>> grouped,
            @NonNull String minecraftVersion,
            @NonNull ArrayList<ModpackInstallManager.ModpackVersionChoice> versions
    ) {
        ArrayList<ModpackInstallManager.ModpackVersionChoice> sortedVersions = new ArrayList<>(versions);
        sortModpackVersionsNewestFirst(sortedVersions);

        LinearLayout layout = LauncherDialogStyle.createDialogRoot(
                this,
                formatMinecraftVersionTitle(minecraftVersion),
                "Choose which " + project.title + " version to install. Newest pack versions are listed first."
        );

        RecyclerView versionList = new RecyclerView(this);
        versionList.setLayoutManager(new LinearLayoutManager(this));
        versionList.setNestedScrollingEnabled(false);
        versionList.setClipToPadding(false);
        versionList.setPadding(0, dp(8), 0, dp(12));

        ArrayList<ModpackVersionDialogRow> rows = new ArrayList<>();
        for (ModpackInstallManager.ModpackVersionChoice version : sortedVersions) {
            rows.add(ModpackVersionDialogRow.version(version));
        }

        ModpackVersionDialogAdapter versionAdapter = new ModpackVersionDialogAdapter(rows, version -> {
            AlertDialog dialog = currentModpackVersionDialog;
            if (dialog != null) dialog.dismiss();
            confirmInstallModpackVersion(project, version);
        });
        versionList.setAdapter(versionAdapter);

        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int listHeight = Math.min(Math.max(dp(260), screenHeight - dp(300)), dp(540));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                listHeight
        );
        listParams.topMargin = dp(4);
        layout.addView(versionList, listParams);

        AlertDialog packVersionDialog = new MaterialAlertDialogBuilder(this)
                .setView(layout)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton("Minecraft Versions", null)
                .create();
        currentModpackVersionDialog = packVersionDialog;

        packVersionDialog.setOnDismissListener(dialog -> {
            if (currentModpackVersionDialog == packVersionDialog) {
                currentModpackVersionDialog = null;
            }
            FullscreenUtils.enableImmersive(this);
        });
        packVersionDialog.setOnShowListener(dialog -> {
            LauncherDialogStyle.styleDialogChrome(this, packVersionDialog);
            FullscreenUtils.enableImmersive(this);
        });
        packVersionDialog.show();
        LauncherDialogStyle.styleDialogChrome(this, packVersionDialog);
        packVersionDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            packVersionDialog.dismiss();
            showMinecraftVersionPickerDialog(project, grouped);
        });
        FullscreenUtils.enableImmersive(this);
    }


    private void confirmInstallModpackVersion(
            @NonNull ModrinthProject project,
            @NonNull ModpackInstallManager.ModpackVersionChoice version
    ) {
        StringBuilder message = new StringBuilder();
        message.append("Install ").append(project.title).append(" as a new launcher instance?");
        message.append("\n\nSelected version:\n").append(version.getDisplayTitle());
        message.append("\n").append(version.getDisplaySubtitle());
        if (!isGlobalBrowserMode() && !version.isCompatibleWith(gameVersionId, loader)) {
            message.append("\n\nWarning: this version does not match the current instance filter. The installed modpack will still use the pack's own Minecraft version and loader.");
        }

        AlertDialog dialog = LauncherDialogStyle.showStyledMessageDialog(
                this,
                "Install Modpack",
                message.toString(),
                getString(R.string.content_browser_install),
                (dialogInterface, which) -> installModpackProject(project, version),
                getString(android.R.string.cancel)
        );
        dialog.setOnDismissListener(unused -> FullscreenUtils.enableImmersive(this));
        FullscreenUtils.enableImmersive(this);
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

    private void openInstalledModpackInstance(@Nullable LauncherInstance instance) {
        if (instance == null) {
            finish();
            return;
        }
        Intent intent = InstanceDetailsActivity.createIntent(this, instance);
        startActivity(intent);
        finish();
    }

    private void openProjectDetails(@NonNull ModrinthProject project) {
        Intent intent = new Intent(this, ContentProjectDetailsActivity.class);
        intent.putExtra(InstanceDetailsActivity.EXTRA_INSTANCE_ID, instanceId);
        intent.putExtra(InstanceDetailsActivity.EXTRA_INSTANCE_NAME, instanceName);
        intent.putExtra(InstanceDetailsActivity.EXTRA_INSTANCE_LOADER, loader);
        intent.putExtra(InstanceDetailsActivity.EXTRA_BASE_VERSION_ID, baseVersionId);
        intent.putExtra(InstanceDetailsActivity.EXTRA_MINECRAFT_VERSION_ID, gameVersionId);
        intent.putExtra(InstanceDetailsActivity.EXTRA_GAME_DIRECTORY, gameDirectoryPath);
        intent.putExtra(InstanceDetailsActivity.EXTRA_CONTENT_CATEGORY, selectedType.getIntentValue());
        intent.putExtra(EXTRA_PROJECT_ID, project.projectId);
        intent.putExtra(EXTRA_PROJECT_SLUG, project.slug);
        intent.putExtra(EXTRA_PROJECT_TITLE, project.title);
        intent.putExtra(EXTRA_PROJECT_TYPE, selectedType.getIntentValue());
        intent.putExtra(EXTRA_PROJECT_ICON_URL, project.iconUrl == null ? "" : project.iconUrl);
        intent.putExtra(EXTRA_PROJECT_SOURCE, project.source.getId());
        startActivity(intent);
    }

    private void openProjectWebsite(@NonNull ModrinthProject project) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(project.getWebsiteUrl())));
        } catch (ActivityNotFoundException throwable) {
            Toast.makeText(this, project.getWebsiteUrl(), Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean isGlobalBrowserMode() {
        return gameDirectoryPath == null || gameDirectoryPath.trim().isEmpty();
    }

    private boolean isBrowseModpacksOnlyMode() {
        return (gameDirectoryPath == null || gameDirectoryPath.trim().isEmpty())
                && (instanceId == null || instanceId.trim().isEmpty())
                && selectedType == ModManagerContentType.MODPACKS;
    }

    @NonNull
    private String displayLoader(@Nullable String value) {
        if (value == null || value.trim().isEmpty()) return "Vanilla";
        String trimmed = value.trim();
        return trimmed.substring(0, 1).toUpperCase(Locale.US) + trimmed.substring(1);
    }

    @NonNull
    private String getTabTitle(@NonNull ModManagerContentType type) {
        switch (type) {
            case MODPACKS:
                return "Modpacks";
            case RESOURCEPACKS:
                return ModManagerContentType.usesLegacyTexturePacks(gameVersionId)
                        ? getString(R.string.content_browser_tab_texturepacks)
                        : getString(R.string.content_browser_tab_resourcepacks);
            case DATAPACKS:
                return getString(R.string.content_browser_tab_datapacks);
            case SHADERPACKS:
                return getString(R.string.content_browser_tab_shaders);
            case MODS:
            default:
                return getString(R.string.content_browser_tab_mods);
        }
    }

    @NonNull
    private String getSearchHint(@NonNull ModManagerContentType type) {
        switch (type) {
            case MODPACKS:
                return "Search modpacks";
            case RESOURCEPACKS:
                return ModManagerContentType.usesLegacyTexturePacks(gameVersionId)
                        ? getString(R.string.content_browser_search_texturepacks)
                        : getString(R.string.content_browser_search_resourcepacks);
            case DATAPACKS:
                return getString(R.string.content_browser_search_datapacks);
            case SHADERPACKS:
                return getString(R.string.content_browser_search_shaders);
            case MODS:
            default:
                return getString(R.string.content_browser_search_mods);
        }
    }

    @NonNull
    private String getPluralLabel(@NonNull ModManagerContentType type) {
        switch (type) {
            case MODPACKS:
                return "modpacks";
            case RESOURCEPACKS:
                return ModManagerContentType.usesLegacyTexturePacks(gameVersionId)
                        ? getString(R.string.content_browser_texturepacks_plural)
                        : getString(R.string.content_browser_resourcepacks_plural);
            case DATAPACKS:
                return getString(R.string.content_browser_datapacks_plural);
            case SHADERPACKS:
                return getString(R.string.content_browser_shaders_plural);
            case MODS:
            default:
                return getString(R.string.content_browser_mods_plural);
        }
    }

    @NonNull
    private ModManagerContentType resolveProjectDisplayType(
            @NonNull ModrinthProject project,
            @NonNull ModManagerContentType fallback
    ) {
        String projectType = project.projectType == null ? "" : project.projectType.trim().toLowerCase(Locale.US);
        if ("modpack".equals(projectType) || categoryContains(project, "modpack") || categoryContains(project, "modpacks")) {
            return ModManagerContentType.MODPACKS;
        }
        if ("resourcepack".equals(projectType)
                || "resourcepacks".equals(projectType)
                || categoryContains(project, "resourcepack")
                || categoryContains(project, "resourcepacks")) {
            return ModManagerContentType.RESOURCEPACKS;
        }
        if ("datapack".equals(projectType)
                || "datapacks".equals(projectType)
                || categoryContains(project, "datapack")
                || categoryContains(project, "datapacks")
                || categoryContains(project, "data-pack")
                || categoryContains(project, "data-packs")) {
            return ModManagerContentType.DATAPACKS;
        }
        if ("shader".equals(projectType)
                || "shaderpack".equals(projectType)
                || "shaderpacks".equals(projectType)
                || categoryContains(project, "shader")
                || categoryContains(project, "shaders")
                || categoryContains(project, "shaderpack")
                || categoryContains(project, "shaderpacks")) {
            return ModManagerContentType.SHADERPACKS;
        }
        return fallback;
    }

    private boolean categoryContains(@NonNull ModrinthProject project, @NonNull String value) {
        for (String category : project.categories) {
            if (category != null && value.equalsIgnoreCase(category.trim())) return true;
        }
        return false;
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

    @Nullable
    private String getImmediateProjectImageUrl(@NonNull ModrinthProject project) {
        String cacheKey = buildProjectIconCacheKey(project);
        String cached = normalizeImageUrl(resolvedProjectIconUrls.get(cacheKey));
        if (cached != null) return cached;

        String iconUrl = normalizeImageUrl(project.iconUrl);
        if (iconUrl != null) return iconUrl;

        for (String galleryUrl : project.galleryUrls) {
            String normalized = normalizeImageUrl(galleryUrl);
            if (normalized != null) return normalized;
        }
        return null;
    }

    private void bindProjectIcon(
            @NonNull ImageView imageView,
            @NonNull ModrinthProject project,
            @NonNull ModManagerContentType itemType
    ) {
        String cacheKey = buildProjectIconCacheKey(project);
        int fallbackIcon = getFallbackIcon(itemType);
        imageView.setTag(cacheKey);

        String immediateUrl = getImmediateProjectImageUrl(project);
        NetworkImageLoader.load(imageView, immediateUrl, fallbackIcon);

        if (itemType != ModManagerContentType.RESOURCEPACKS
                && itemType != ModManagerContentType.SHADERPACKS
                && immediateUrl != null) {
            return;
        }
        resolveProjectIconUrlAsync(imageView, project, cacheKey, fallbackIcon);
    }

    @NonNull
    private String buildProjectIconCacheKey(@NonNull ModrinthProject project) {
        String id = project.projectId == null ? "" : project.projectId.trim();
        if (id.isEmpty()) id = project.slug == null ? "" : project.slug.trim();
        if (id.isEmpty()) id = project.title == null ? "" : project.title.trim();
        return project.source.getId() + ":" + id;
    }

    private void resolveProjectIconUrlAsync(
            @NonNull ImageView imageView,
            @NonNull ModrinthProject project,
            @NonNull String cacheKey,
            @DrawableRes int fallbackIcon
    ) {
        String cached = normalizeImageUrl(resolvedProjectIconUrls.get(cacheKey));
        if (cached != null) {
            Object tag = imageView.getTag();
            if (cacheKey.equals(tag)) NetworkImageLoader.load(imageView, cached, fallbackIcon);
            return;
        }

        if (!resolvingProjectIconUrls.add(cacheKey)) return;

        Thread thread = new Thread(() -> {
            String resolvedUrl = null;
            try {
                resolvedUrl = normalizeImageUrl(fetchProjectIconUrl(project));
                if (resolvedUrl != null) resolvedProjectIconUrls.put(cacheKey, resolvedUrl);
            } catch (Throwable ignored) {
            } finally {
                resolvingProjectIconUrls.remove(cacheKey);
            }

            final String finalResolvedUrl = resolvedUrl;
            if (finalResolvedUrl == null) return;
            runOnUiThread(() -> {
                Object tag = imageView.getTag();
                if (cacheKey.equals(tag)) NetworkImageLoader.load(imageView, finalResolvedUrl, fallbackIcon);
            });
        }, "ResolveContentIcon");
        thread.start();
    }

    @Nullable
    private String fetchProjectIconUrl(@NonNull ModrinthProject project) throws Exception {
        if (project.source == ModManagerSource.CURSEFORGE) {
            return fetchCurseForgeProjectIconUrl(project);
        }
        return fetchModrinthProjectIconUrl(project);
    }

    @Nullable
    private String fetchModrinthProjectIconUrl(@NonNull ModrinthProject project) throws Exception {
        String projectKey = project.projectId == null ? "" : project.projectId.trim();
        if (projectKey.isEmpty()) projectKey = project.slug == null ? "" : project.slug.trim();
        if (projectKey.isEmpty()) return null;

        String response = httpGet("https://api.modrinth.com/v2/project/" + projectKey, null);
        JSONObject json = new JSONObject(response);
        String iconUrl = normalizeImageUrl(json.optString("icon_url", ""));
        if (iconUrl != null) return iconUrl;

        JSONArray gallery = json.optJSONArray("gallery");
        if (gallery != null) {
            for (int i = 0; i < gallery.length(); i++) {
                JSONObject image = gallery.optJSONObject(i);
                if (image == null) continue;
                iconUrl = normalizeImageUrl(image.optString("raw_url", image.optString("url", "")));
                if (iconUrl != null) return iconUrl;
            }
        }
        return null;
    }

    @Nullable
    private String fetchCurseForgeProjectIconUrl(@NonNull ModrinthProject project) throws Exception {
        String projectId = project.projectId == null ? "" : project.projectId.trim();
        if (projectId.isEmpty()) return null;

        String apiKey = CurseForgeApiKeyProvider.resolve();
        if (apiKey == null || apiKey.trim().isEmpty()) return null;

        String response = httpGet("https://api.curseforge.com/v1/mods/" + projectId, apiKey.trim());
        JSONObject root = new JSONObject(response);
        JSONObject data = root.optJSONObject("data");
        if (data == null) return null;

        JSONObject logo = data.optJSONObject("logo");
        if (logo != null) {
            String iconUrl = normalizeImageUrl(logo.optString("thumbnailUrl", ""));
            if (iconUrl != null) return iconUrl;
            iconUrl = normalizeImageUrl(logo.optString("url", ""));
            if (iconUrl != null) return iconUrl;
        }
        return null;
    }

    @NonNull
    private String httpGet(@NonNull String url, @Nullable String apiKey) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "DroidBridge/1.0");
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            connection.setRequestProperty("x-api-key", apiKey.trim());
        }

        int code = connection.getResponseCode();
        InputStream input = code >= 200 && code < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        if (input == null) throw new IllegalStateException("HTTP " + code);
        try (InputStream in = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) output.write(buffer, 0, read);
            String text = output.toString("UTF-8");
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + ": " + text);
            return text;
        } finally {
            connection.disconnect();
        }
    }

    @DrawableRes
    private int getFallbackIcon(@NonNull ModManagerContentType type) {
        switch (type) {
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
    private String formatNumber(long value) {
        if (value >= 1_000_000_000L) return String.format(Locale.US, "%.1fB", value / 1_000_000_000.0);
        if (value >= 1_000_000L) return String.format(Locale.US, "%.1fM", value / 1_000_000.0);
        if (value >= 1_000L) return String.format(Locale.US, "%.1fK", value / 1_000.0);
        return String.valueOf(value);
    }

    @NonNull
    private String formatTags(@NonNull List<String> tags) {
        if (tags.isEmpty()) return getString(R.string.content_browser_tag_unknown);
        StringBuilder builder = new StringBuilder();
        int count = Math.min(2, tags.size());
        for (int i = 0; i < count; i++) {
            if (builder.length() > 0) builder.append("  ");
            builder.append(formatTag(tags.get(i)));
        }
        if (tags.size() > count) builder.append("  +").append(tags.size() - count);
        return builder.toString();
    }

    @NonNull
    private String formatTag(@NonNull String value) {
        String clean = value.replace('-', ' ').replace('_', ' ').trim();
        if (clean.isEmpty()) return value;
        return clean.substring(0, 1).toUpperCase(Locale.US) + clean.substring(1);
    }

    private boolean isProjectInstalled(@NonNull ModrinthProject project) {
        if (selectedType == ModManagerContentType.MODPACKS) return false;
        if (gameDirectoryPath.trim().isEmpty() || project.projectId.trim().isEmpty()) return false;
        return ModManagerManifest.isProjectInstalled(
                new File(gameDirectoryPath),
                selectedType,
                project.source.getId(),
                project.projectId
        );
    }


    @NonNull
    private ModManagerSource getInstalledSource(@NonNull ModrinthProject project) {
        if (gameDirectoryPath.trim().isEmpty() || project.projectId.trim().isEmpty()) return ModManagerSource.UNKNOWN;
        org.json.JSONObject entry = ModManagerManifest.getInstalledEntryForProject(
                new File(gameDirectoryPath),
                selectedType,
                project.source,
                project.projectId
        );
        return entry == null ? ModManagerSource.UNKNOWN : ModManagerManifest.getSource(entry);
    }


    @NonNull
    private String getSelectedSourceLabel() {
        return getString(selectedSource == ContentSource.CURSEFORGE
                ? R.string.content_browser_source_curseforge
                : R.string.content_browser_source_modrinth);
    }

    @NonNull
    private LinkedHashMap<String, ArrayList<ModpackInstallManager.ModpackVersionChoice>> groupModpackVersionsByMinecraftVersion(
            @NonNull ArrayList<ModpackInstallManager.ModpackVersionChoice> versions
    ) {
        LinkedHashMap<String, ArrayList<ModpackInstallManager.ModpackVersionChoice>> unsorted = new LinkedHashMap<>();

        for (ModpackInstallManager.ModpackVersionChoice version : versions) {
            ArrayList<String> gameVersions = version.gameVersions;
            if (gameVersions.isEmpty()) {
                addModpackVersionToGroup(unsorted, "Unknown Minecraft version", version);
                continue;
            }

            for (String gameVersion : gameVersions) {
                String key = normalizeMinecraftVersionKey(gameVersion);
                if (key.isEmpty()) key = "Unknown Minecraft version";
                addModpackVersionToGroup(unsorted, key, version);
            }
        }

        ArrayList<String> keys = new ArrayList<>(unsorted.keySet());
        Collections.sort(keys, this::compareMinecraftVersionKeysDescending);

        LinkedHashMap<String, ArrayList<ModpackInstallManager.ModpackVersionChoice>> sorted = new LinkedHashMap<>();
        for (String key : keys) {
            ArrayList<ModpackInstallManager.ModpackVersionChoice> bucket = unsorted.get(key);
            if (bucket == null || bucket.isEmpty()) continue;
            sortModpackVersionsNewestFirst(bucket);
            sorted.put(key, bucket);
        }
        return sorted;
    }

    private void addModpackVersionToGroup(
            @NonNull LinkedHashMap<String, ArrayList<ModpackInstallManager.ModpackVersionChoice>> grouped,
            @NonNull String minecraftVersion,
            @NonNull ModpackInstallManager.ModpackVersionChoice version
    ) {
        ArrayList<ModpackInstallManager.ModpackVersionChoice> bucket = grouped.get(minecraftVersion);
        if (bucket == null) {
            bucket = new ArrayList<>();
            grouped.put(minecraftVersion, bucket);
        }

        String versionKey = buildModpackVersionIdentity(version);
        for (ModpackInstallManager.ModpackVersionChoice existing : bucket) {
            if (versionKey.equals(buildModpackVersionIdentity(existing))) return;
        }
        bucket.add(version);
    }

    @NonNull
    private String buildModpackVersionIdentity(@NonNull ModpackInstallManager.ModpackVersionChoice version) {
        if (!version.versionId.trim().isEmpty()) return version.source.getId() + ":" + version.versionId;
        if (version.fileId > 0) return version.source.getId() + ":file:" + version.fileId;
        return version.source.getId() + ":" + version.fileName + ":" + version.downloadUrl;
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

    private int compareMinecraftVersionKeysDescending(@NonNull String left, @NonNull String right) {
        boolean leftUnknown = left.equalsIgnoreCase("Unknown Minecraft version");
        boolean rightUnknown = right.equalsIgnoreCase("Unknown Minecraft version");
        if (leftUnknown && rightUnknown) return 0;
        if (leftUnknown) return 1;
        if (rightUnknown) return -1;

        int labelCompare = compareVersionLabelsDescending(left, right);
        if (labelCompare != 0) return labelCompare;
        return right.compareToIgnoreCase(left);
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
    private String normalizeMinecraftVersionKey(@Nullable String value) {
        if (value == null) return "";
        String clean = value.trim();
        if (clean.toLowerCase(Locale.US).startsWith("minecraft ")) {
            clean = clean.substring("minecraft ".length()).trim();
        }
        return clean;
    }

    @NonNull
    private String formatMinecraftVersionTitle(@NonNull String minecraftVersion) {
        if (minecraftVersion.equalsIgnoreCase("Unknown Minecraft version")) return minecraftVersion;
        return "Minecraft " + minecraftVersion;
    }

    @NonNull
    private String buildMinecraftVersionGroupSubtitle(@NonNull ModpackMinecraftVersionGroup group) {
        StringBuilder builder = new StringBuilder();
        builder.append(group.versions.size()).append(group.versions.size() == 1 ? " pack version" : " pack versions");

        String loaderSummary = buildLoaderSummary(group.versions);
        if (!loaderSummary.isEmpty()) builder.append(" · ").append(loaderSummary);

        String newestDate = getNewestPublishedDate(group.versions);
        if (!newestDate.isEmpty()) builder.append(" · Newest ").append(newestDate);

        return builder.toString();
    }

    @NonNull
    private String buildLoaderSummary(@NonNull ArrayList<ModpackInstallManager.ModpackVersionChoice> versions) {
        ArrayList<String> loaders = new ArrayList<>();
        for (ModpackInstallManager.ModpackVersionChoice version : versions) {
            for (String loaderName : version.loaders) {
                String clean = loaderName == null ? "" : loaderName.trim();
                if (clean.isEmpty() || containsIgnoreCase(loaders, clean)) continue;
                loaders.add(clean);
            }
        }
        if (loaders.isEmpty()) return "";
        return "Loader " + joinShortList(loaders, 3);
    }

    @NonNull
    private String getNewestPublishedDate(@NonNull ArrayList<ModpackInstallManager.ModpackVersionChoice> versions) {
        String newest = "";
        for (ModpackInstallManager.ModpackVersionChoice version : versions) {
            String date = version.datePublished == null ? "" : version.datePublished.trim();
            if (date.isEmpty()) continue;
            if (newest.isEmpty() || date.compareToIgnoreCase(newest) > 0) newest = date;
        }
        if (newest.isEmpty()) return "";
        return newest.substring(0, Math.min(10, newest.length()));
    }

    private boolean containsIgnoreCase(@NonNull ArrayList<String> values, @NonNull String target) {
        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(target)) return true;
        }
        return false;
    }

    @NonNull
    private ArrayList<ModpackVersionDialogRow> buildModpackVersionDialogRows(
            @NonNull ArrayList<ModpackInstallManager.ModpackVersionChoice> versions
    ) {
        LinkedHashMap<String, ArrayList<ModpackInstallManager.ModpackVersionChoice>> grouped = new LinkedHashMap<>();
        for (ModpackInstallManager.ModpackVersionChoice version : versions) {
            String key = getPrimaryGameVersion(version);
            ArrayList<ModpackInstallManager.ModpackVersionChoice> bucket = grouped.get(key);
            if (bucket == null) {
                bucket = new ArrayList<>();
                grouped.put(key, bucket);
            }
            bucket.add(version);
        }

        ArrayList<ModpackVersionDialogRow> rows = new ArrayList<>();
        for (Map.Entry<String, ArrayList<ModpackInstallManager.ModpackVersionChoice>> entry : grouped.entrySet()) {
            String title = "Unknown Minecraft version".equals(entry.getKey())
                    ? entry.getKey()
                    : "Minecraft " + entry.getKey();
            rows.add(ModpackVersionDialogRow.header(title, entry.getValue().size()));
            for (ModpackInstallManager.ModpackVersionChoice version : entry.getValue()) {
                rows.add(ModpackVersionDialogRow.version(version));
            }
        }
        return rows;
    }

    @NonNull
    private String getPrimaryGameVersion(@NonNull ModpackInstallManager.ModpackVersionChoice version) {
        if (!version.gameVersions.isEmpty()) return version.gameVersions.get(0);
        return "Unknown Minecraft version";
    }

    @NonNull
    private String buildVersionMetaLine(@NonNull ModpackInstallManager.ModpackVersionChoice version) {
        StringBuilder builder = new StringBuilder();
        if (!version.gameVersions.isEmpty()) {
            builder.append("Minecraft ").append(joinShortList(version.gameVersions, 3));
        }
        if (!version.loaders.isEmpty()) {
            if (builder.length() > 0) builder.append(" · ");
            builder.append("Loader ").append(joinShortList(version.loaders, 2));
        }
        if (!version.datePublished.trim().isEmpty()) {
            if (builder.length() > 0) builder.append(" · ");
            builder.append(version.datePublished.substring(0, Math.min(10, version.datePublished.length())));
        }
        return builder.length() == 0 ? "No version metadata available" : builder.toString();
    }

    @NonNull
    private String buildVersionFileLine(@NonNull ModpackInstallManager.ModpackVersionChoice version) {
        String fileName = version.fileName == null ? "" : version.fileName.trim();
        if (!fileName.isEmpty()) return fileName;
        if (version.source == ModManagerSource.CURSEFORGE && version.fileId > 0) {
            return "CurseForge file " + version.fileId;
        }
        return version.versionId;
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

    private int resolveSelectableItemBackground() {
        TypedValue value = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true)) {
            return value.resourceId;
        }
        return 0;
    }

    private interface ModpackMinecraftVersionClickListener {
        void onMinecraftVersionClicked(@NonNull ModpackMinecraftVersionGroup group);
    }

    private static final class ModpackMinecraftVersionGroup {
        @NonNull
        final String minecraftVersion;
        @NonNull
        final ArrayList<ModpackInstallManager.ModpackVersionChoice> versions;

        ModpackMinecraftVersionGroup(
                @NonNull String minecraftVersion,
                @NonNull ArrayList<ModpackInstallManager.ModpackVersionChoice> versions
        ) {
            this.minecraftVersion = minecraftVersion;
            this.versions = new ArrayList<>(versions);
        }
    }

    private final class ModpackMinecraftVersionDialogAdapter extends RecyclerView.Adapter<ModpackMinecraftVersionDialogAdapter.ViewHolder> {
        @NonNull
        private final ArrayList<ModpackMinecraftVersionGroup> groups;
        @NonNull
        private final ModpackMinecraftVersionClickListener listener;

        ModpackMinecraftVersionDialogAdapter(
                @NonNull ArrayList<ModpackMinecraftVersionGroup> groups,
                @NonNull ModpackMinecraftVersionClickListener listener
        ) {
            this.groups = groups;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(14), dp(16), dp(14));
            row.setMinimumHeight(dp(78));

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
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
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
            ModpackMinecraftVersionGroup group = groups.get(position);
            holder.title.setText(formatMinecraftVersionTitle(group.minecraftVersion));
            holder.subtitle.setText(buildMinecraftVersionGroupSubtitle(group));
            holder.itemView.setOnClickListener(view -> listener.onMinecraftVersionClicked(group));
        }

        @Override
        public int getItemCount() {
            return groups.size();
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

    private interface ModpackVersionClickListener {
        void onVersionClicked(@NonNull ModpackInstallManager.ModpackVersionChoice version);
    }

    private static final class ModpackVersionDialogRow {
        static final int TYPE_HEADER = 0;
        static final int TYPE_VERSION = 1;

        final int type;
        @NonNull
        final String headerTitle;
        final int headerCount;
        @Nullable
        final ModpackInstallManager.ModpackVersionChoice version;

        private ModpackVersionDialogRow(
                int type,
                @NonNull String headerTitle,
                int headerCount,
                @Nullable ModpackInstallManager.ModpackVersionChoice version
        ) {
            this.type = type;
            this.headerTitle = headerTitle;
            this.headerCount = headerCount;
            this.version = version;
        }

        @NonNull
        static ModpackVersionDialogRow header(@NonNull String title, int count) {
            return new ModpackVersionDialogRow(TYPE_HEADER, title, count, null);
        }

        @NonNull
        static ModpackVersionDialogRow version(@NonNull ModpackInstallManager.ModpackVersionChoice version) {
            return new ModpackVersionDialogRow(TYPE_VERSION, "", 0, version);
        }
    }

    private final class ModpackVersionDialogAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final ArrayList<ModpackVersionDialogRow> rows;
        private final ModpackVersionClickListener listener;

        ModpackVersionDialogAdapter(
                @NonNull ArrayList<ModpackVersionDialogRow> rows,
                @NonNull ModpackVersionClickListener listener
        ) {
            this.rows = rows;
            this.listener = listener;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == ModpackVersionDialogRow.TYPE_HEADER) {
                TextView header = new TextView(parent.getContext());
                header.setGravity(Gravity.CENTER_VERTICAL);
                header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                header.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
                header.setTypeface(header.getTypeface(), android.graphics.Typeface.BOLD);
                header.setPadding(dp(2), dp(14), dp(2), dp(6));
                return new HeaderViewHolder(header);
            }

            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setMinimumHeight(dp(92));
            GradientDrawable rowBackground = new GradientDrawable();
            rowBackground.setColor(LauncherDialogStyle.COLOR_CARD_BG);
            rowBackground.setCornerRadius(dp(16));
            rowBackground.setStroke(dp(1), LauncherDialogStyle.COLOR_CARD_STROKE);
            row.setBackground(rowBackground);

            TextView title = new TextView(parent.getContext());
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            title.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
            title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
            title.setSingleLine(false);
            title.setMaxLines(2);
            row.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            TextView meta = new TextView(parent.getContext());
            meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            meta.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
            meta.setSingleLine(false);
            meta.setMaxLines(2);
            LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            metaParams.topMargin = dp(4);
            row.addView(meta, metaParams);

            TextView file = new TextView(parent.getContext());
            file.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            file.setTextColor(LauncherDialogStyle.COLOR_TEXT_MUTED);
            file.setSingleLine(true);
            file.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams fileParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            fileParams.topMargin = dp(4);
            row.addView(file, fileParams);

            TextView warning = new TextView(parent.getContext());
            warning.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            warning.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
            warning.setSingleLine(false);
            LinearLayout.LayoutParams warningParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            warningParams.topMargin = dp(6);
            row.addView(warning, warningParams);

            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, 0, 0, dp(8));
            row.setLayoutParams(params);

            return new VersionViewHolder(row, title, meta, file, warning);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ModpackVersionDialogRow row = rows.get(position);
            if (holder instanceof HeaderViewHolder) {
                HeaderViewHolder headerHolder = (HeaderViewHolder) holder;
                headerHolder.header.setText(row.headerTitle + "  ·  " + row.headerCount + " " + (row.headerCount == 1 ? "version" : "versions"));
                return;
            }

            VersionViewHolder versionHolder = (VersionViewHolder) holder;
            ModpackInstallManager.ModpackVersionChoice version = row.version;
            if (version == null) return;

            versionHolder.title.setText(version.getDisplayTitle());
            versionHolder.meta.setText(buildVersionMetaLine(version));
            versionHolder.file.setText(buildVersionFileLine(version));

            boolean incompatible = !isGlobalBrowserMode() && !version.isCompatibleWith(gameVersionId, loader);
            versionHolder.warning.setVisibility(incompatible ? View.VISIBLE : View.GONE);
            if (incompatible) {
                versionHolder.warning.setText("Does not match the current instance filter. It will still install using this pack version's Minecraft version and loader.");
            } else {
                versionHolder.warning.setText("");
            }

            versionHolder.itemView.setOnClickListener(view -> listener.onVersionClicked(version));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        final class HeaderViewHolder extends RecyclerView.ViewHolder {
            final TextView header;

            HeaderViewHolder(@NonNull TextView itemView) {
                super(itemView);
                header = itemView;
            }
        }

        final class VersionViewHolder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView meta;
            final TextView file;
            final TextView warning;

            VersionViewHolder(
                    @NonNull View itemView,
                    @NonNull TextView title,
                    @NonNull TextView meta,
                    @NonNull TextView file,
                    @NonNull TextView warning
            ) {
                super(itemView);
                this.title = title;
                this.meta = meta;
                this.file = file;
                this.warning = warning;
            }
        }
    }

    private interface ReleaseVersionClickListener {
        void onReleaseVersionClicked(@NonNull String version);
    }

    private final class ReleaseVersionDialogAdapter extends RecyclerView.Adapter<ReleaseVersionDialogAdapter.ViewHolder> {
        private final ArrayList<String> versions;
        private final String selectedVersion;
        private final ReleaseVersionClickListener listener;

        ReleaseVersionDialogAdapter(
                @NonNull ArrayList<String> versions,
                @Nullable String selectedVersion,
                @NonNull ReleaseVersionClickListener listener
        ) {
            this.versions = new ArrayList<>(versions);
            this.selectedVersion = selectedVersion == null ? "" : selectedVersion.trim();
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setBackground(LauncherDialogStyle.roundedDrawable(
                    ContentBrowserActivity.this,
                    LauncherDialogStyle.COLOR_CARD_BG,
                    LauncherDialogStyle.COLOR_CARD_STROKE,
                    16
            ));

            TextView title = new TextView(parent.getContext());
            title.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
            title.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(title, titleParams);

            TextView check = new TextView(parent.getContext());
            check.setTextColor(LauncherDialogStyle.COLOR_ACCENT);
            check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            check.setGravity(Gravity.CENTER);
            check.setText("✓");
            row.addView(check, new LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT));

            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, 0, 0, dp(8));
            row.setLayoutParams(params);
            return new ViewHolder(row, title, check);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            String version = versions.get(position);
            holder.title.setText(version);
            boolean selected = version.equalsIgnoreCase(selectedVersion);
            holder.check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            holder.itemView.setOnClickListener(view -> listener.onReleaseVersionClicked(version));
        }

        @Override
        public int getItemCount() {
            return versions.size();
        }

        final class ViewHolder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView check;

            ViewHolder(@NonNull View itemView, @NonNull TextView title, @NonNull TextView check) {
                super(itemView);
                this.title = title;
                this.check = check;
            }
        }
    }

    private enum ContentSource {
        MODRINTH,
        CURSEFORGE
    }

    private enum ContentSort {
        DOWNLOADS(1, "Downloads", "downloads", true, true),
        UPDATED(2, "Recently Updated", "updated", true, false),
        NEWEST(3, "Newest", "newest", true, false),
        FOLLOWERS(4, "Followers", "followers", true, false),
        VERSIONS(5, "Versions", "versions", true, false),
        NAME(6, "Name", "name", true, false);

        final int menuId;
        @NonNull final String label;
        @NonNull final String apiKey;
        final boolean modpacks;
        final boolean regularContent;

        ContentSort(int menuId, @NonNull String label, @NonNull String apiKey, boolean modpacks, boolean regularContent) {
            this.menuId = menuId;
            this.label = label;
            this.apiKey = apiKey;
            this.modpacks = modpacks;
            this.regularContent = regularContent;
        }

        boolean isAvailableFor(@NonNull ModManagerContentType type) {
            return type == ModManagerContentType.MODPACKS ? modpacks : regularContent;
        }

        @Nullable
        static ContentSort fromMenuId(int menuId) {
            for (ContentSort sort : values()) {
                if (sort.menuId == menuId) return sort;
            }
            return null;
        }
    }

    private final class ContentProjectAdapter extends RecyclerView.Adapter<ContentProjectAdapter.ViewHolder> {
        private final ArrayList<ModrinthProject> items = new ArrayList<>();

        private ModManagerContentType boundType = ModManagerContentType.MODS;

        void submit(@NonNull List<ModrinthProject> newItems) {
            boundType = selectedType;
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            View view = getLayoutInflater().inflate(R.layout.item_content_project, parent, false);
            view.setFocusable(false);
            view.setFocusableInTouchMode(false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ModrinthProject item = items.get(position);
            ModManagerContentType itemType = resolveProjectDisplayType(item, boundType);
            bindProjectIcon(holder.icon, item, itemType);
            boolean favourite = boundType == ModManagerContentType.MODPACKS
                    && ModpackFavouritesStore.isFavourite(ContentBrowserActivity.this, item);
            holder.name.setText((favourite ? "★ " : "") + item.title);
            String authorLine = getString(R.string.content_browser_project_author, getProjectAuthorDisplayName(item));
            if (boundType == ModManagerContentType.MODPACKS && ModpackFavouritesStore.isDeveloperPick(item)) {
                authorLine = getString(R.string.content_browser_developer_pick) + " • " + authorLine;
            }
            holder.author.setText(authorLine);
            holder.description.setText(item.description);
            holder.tags.setText(formatTags(item.categories));
            holder.downloads.setText(formatNumber(item.downloads));
            holder.likes.setText(formatNumber(item.followers));
            holder.updated.setText(item.dateModified == null || item.dateModified.trim().isEmpty()
                    ? getString(R.string.content_browser_updated_unknown)
                    : item.dateModified.substring(0, Math.min(10, item.dateModified.length())));

            boolean installed = isProjectInstalled(item);
            ModManagerSource installedSource = installed ? getInstalledSource(item) : ModManagerSource.UNKNOWN;
            holder.sourceIcon.setVisibility(installedSource.hasIcon() ? View.VISIBLE : View.GONE);
            if (installedSource.hasIcon()) {
                holder.sourceIcon.setImageResource(installedSource.getIconRes());
                holder.sourceIcon.setContentDescription(getString(R.string.modmanager_installed_from, installedSource.getDisplayName()));
            }
            holder.install.setEnabled(!installed);
            holder.install.setText(installed ? R.string.content_browser_installed : R.string.content_browser_install);
            if (installed) {
                holder.install.setIcon(null);
            } else {
                holder.install.setIconResource(R.drawable.ic_add_24);
            }
            holder.install.setOnClickListener(installed ? null : view -> confirmInstall(item));

            holder.menu.setOnClickListener(view -> showProjectMenu(view, item));
            holder.itemView.setOnClickListener(view -> openProjectDetails(item));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name;
            final TextView author;
            final TextView description;
            final TextView tags;
            final TextView downloads;
            final TextView likes;
            final TextView updated;
            final ImageView sourceIcon;
            final MaterialButton install;
            final MaterialButton menu;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.imageProjectIcon);
                name = itemView.findViewById(R.id.textProjectName);
                author = itemView.findViewById(R.id.textProjectAuthor);
                description = itemView.findViewById(R.id.textProjectDescription);
                tags = itemView.findViewById(R.id.textProjectTags);
                downloads = itemView.findViewById(R.id.textProjectDownloads);
                likes = itemView.findViewById(R.id.textProjectLikes);
                updated = itemView.findViewById(R.id.textProjectUpdated);
                sourceIcon = itemView.findViewById(R.id.imageProjectInstalledSource);
                install = itemView.findViewById(R.id.buttonInstallProject);
                menu = itemView.findViewById(R.id.buttonProjectMenu);
                install.setFocusable(false);
                install.setFocusableInTouchMode(false);
                menu.setFocusable(false);
                menu.setFocusableInTouchMode(false);
            }
        }
    }
}

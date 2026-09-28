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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.instance.InstanceVersionUpdater;
import ca.dnamobile.droidbridgelauncher.modmanager.ModpackUpdateManager;
import ca.dnamobile.droidbridgelauncher.utils.FullscreenUtils;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class ModpackUpdateDetailsActivity extends AppCompatActivity {
    public static final String PLATFORM_MODRINTH = "modrinth";
    public static final String PLATFORM_CURSEFORGE = "curseforge";

    private static final String EXTRA_PLATFORM = "ca.dnamobile.droidbridgelauncher.extra.MODPACK_UPDATE_PLATFORM";
    private static final String EXTRA_PROJECT_ID = "ca.dnamobile.droidbridgelauncher.extra.MODPACK_UPDATE_PROJECT_ID";
    private static final String EXTRA_PROJECT_SLUG = "ca.dnamobile.droidbridgelauncher.extra.MODPACK_UPDATE_PROJECT_SLUG";
    private static final String EXTRA_PROJECT_TITLE = "ca.dnamobile.droidbridgelauncher.extra.MODPACK_UPDATE_PROJECT_TITLE";
    private static final String EXTRA_PROJECT_SUMMARY = "ca.dnamobile.droidbridgelauncher.extra.MODPACK_UPDATE_PROJECT_SUMMARY";
    private static final String EXTRA_CURRENT_PACK_VERSION = "ca.dnamobile.droidbridgelauncher.extra.MODPACK_UPDATE_CURRENT_VERSION";

    private static final String TAG = "ModpackUpdateDetails";
    private static final String FILTER_ALL = "All Minecraft versions";
    private static final String FILTER_UNKNOWN = "Unknown Minecraft version";

    // Match the launcher instance/details theme instead of the green controller dialog accent.
    // These are fallback values only; buttons/dialog chrome resolve android:colorAccent when available.
    private static final int COLOR_DIALOG_BG = Color.rgb(12, 10, 8);
    private static final int COLOR_CARD_BG = Color.rgb(24, 24, 25);
    private static final int COLOR_CARD_BG_PRESSED = Color.rgb(35, 30, 24);
    private static final int COLOR_CARD_STROKE = Color.rgb(66, 48, 26);
    private static final int COLOR_TEXT_PRIMARY = Color.rgb(241, 238, 232);
    private static final int COLOR_TEXT_SECONDARY = Color.rgb(205, 198, 188);
    private static final int COLOR_TEXT_MUTED = Color.rgb(157, 147, 132);
    private static final int COLOR_ACCENT = Color.rgb(145, 91, 14);

    @NonNull private String instanceId = "";
    @NonNull private String instanceName = "";
    @NonNull private String loader = "";
    @NonNull private String baseVersionId = "";
    @NonNull private String minecraftVersionId = "";
    @NonNull private String versionType = "";
    @Nullable private File rootDirectory;
    @Nullable private File gameDirectory;
    @Nullable private File iconFile;
    private boolean isolated;

    @NonNull private String projectPlatform = PLATFORM_MODRINTH;
    @NonNull private String projectId = "";
    @NonNull private String projectSlug = "";
    @NonNull private String projectTitle = "Modpack";
    @NonNull private String projectSummary = "";
    @NonNull private String currentPackVersion = "Unknown";

    private TextView textTitle;
    private TextView textMeta;
    private TextView textStatus;
    private TextView textFilterSummary;
    private TextView textPageIndicator;
    private TextView buttonFilter;
    private TextView buttonPrevious;
    private TextView buttonNext;
    private RecyclerView recyclerVersions;
    private ProgressBar progressBar;

    private final VersionAdapter adapter = new VersionAdapter();
    private final ArrayList<ModpackUpdateManager.VersionInfo> allVersions = new ArrayList<>();
    private final ArrayList<ModpackUpdateManager.VersionInfo> filteredVersions = new ArrayList<>();
    private final ArrayList<VersionGroup> versionGroups = new ArrayList<>();
    private final ArrayList<VersionDisplayRow> visibleRows = new ArrayList<>();
    private final ArrayList<String> minecraftFilters = new ArrayList<>();
    private final HashSet<String> collapsedGroups = new HashSet<>();
    private boolean collapseAllOnNextRebuild = true;
    @NonNull private String selectedFilter = FILTER_ALL;

    @NonNull
    public static Intent createIntent(
            @NonNull Context context,
            @Nullable String instanceId,
            @NonNull String instanceName,
            @NonNull String loader,
            @NonNull String baseVersionId,
            @NonNull String minecraftVersionId,
            @NonNull String versionType,
            @Nullable File rootDirectory,
            @NonNull File gameDirectory,
            @Nullable File iconFile,
            boolean isolated,
            @NonNull String platform,
            @NonNull String projectId,
            @NonNull String projectSlug,
            @NonNull String projectTitle,
            @NonNull String projectSummary,
            @NonNull String currentPackVersion
    ) {
        Intent intent = new Intent(context, ModpackUpdateDetailsActivity.class);
        intent.putExtra(InstanceDetailsActivity.EXTRA_INSTANCE_ID, instanceId == null ? "" : instanceId);
        intent.putExtra(InstanceDetailsActivity.EXTRA_INSTANCE_NAME, instanceName);
        intent.putExtra(InstanceDetailsActivity.EXTRA_INSTANCE_LOADER, loader);
        intent.putExtra(InstanceDetailsActivity.EXTRA_BASE_VERSION_ID, baseVersionId);
        intent.putExtra(InstanceDetailsActivity.EXTRA_MINECRAFT_VERSION_ID, minecraftVersionId);
        intent.putExtra(InstanceDetailsActivity.EXTRA_VERSION_TYPE, versionType);
        intent.putExtra(InstanceDetailsActivity.EXTRA_ROOT_DIRECTORY, rootDirectory == null ? "" : rootDirectory.getAbsolutePath());
        intent.putExtra(InstanceDetailsActivity.EXTRA_GAME_DIRECTORY, gameDirectory.getAbsolutePath());
        intent.putExtra(InstanceDetailsActivity.EXTRA_ICON_FILE, iconFile == null ? "" : iconFile.getAbsolutePath());
        intent.putExtra(InstanceDetailsActivity.EXTRA_ISOLATED, isolated);
        intent.putExtra(EXTRA_PLATFORM, platform);
        intent.putExtra(EXTRA_PROJECT_ID, projectId);
        intent.putExtra(EXTRA_PROJECT_SLUG, projectSlug);
        intent.putExtra(EXTRA_PROJECT_TITLE, projectTitle);
        intent.putExtra(EXTRA_PROJECT_SUMMARY, projectSummary);
        intent.putExtra(EXTRA_CURRENT_PACK_VERSION, currentPackVersion);
        return intent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherTheme.apply(this);
        super.onCreate(savedInstanceState);
        PathManager.initContextConstants(this);
        readExtras();
        buildLayout();
        FullscreenUtils.enableImmersive(this);
        loadVersions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        FullscreenUtils.enableImmersive(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) FullscreenUtils.enableImmersive(this);
    }

    private void readExtras() {
        Intent intent = getIntent();
        instanceId = safeExtra(intent, InstanceDetailsActivity.EXTRA_INSTANCE_ID, "");
        instanceName = safeExtra(intent, InstanceDetailsActivity.EXTRA_INSTANCE_NAME, "Unknown instance");
        loader = safeExtra(intent, InstanceDetailsActivity.EXTRA_INSTANCE_LOADER, "Vanilla");
        baseVersionId = safeExtra(intent, InstanceDetailsActivity.EXTRA_BASE_VERSION_ID, "");
        minecraftVersionId = safeExtra(intent, InstanceDetailsActivity.EXTRA_MINECRAFT_VERSION_ID, baseVersionId);
        versionType = safeExtra(intent, InstanceDetailsActivity.EXTRA_VERSION_TYPE, "release");
        isolated = intent.getBooleanExtra(InstanceDetailsActivity.EXTRA_ISOLATED, true);

        String rootPath = safeExtra(intent, InstanceDetailsActivity.EXTRA_ROOT_DIRECTORY, "");
        String gamePath = safeExtra(intent, InstanceDetailsActivity.EXTRA_GAME_DIRECTORY, "");
        String iconPath = safeExtra(intent, InstanceDetailsActivity.EXTRA_ICON_FILE, "");
        rootDirectory = isBlank(rootPath) ? null : new File(rootPath);
        gameDirectory = isBlank(gamePath) ? null : new File(gamePath);
        iconFile = isBlank(iconPath) ? null : new File(iconPath);

        projectPlatform = safeExtra(intent, EXTRA_PLATFORM, PLATFORM_MODRINTH);
        projectId = safeExtra(intent, EXTRA_PROJECT_ID, "");
        projectSlug = safeExtra(intent, EXTRA_PROJECT_SLUG, "");
        projectTitle = safeExtra(intent, EXTRA_PROJECT_TITLE, "Modpack");
        projectSummary = safeExtra(intent, EXTRA_PROJECT_SUMMARY, "");
        currentPackVersion = safeExtra(intent, EXTRA_CURRENT_PACK_VERSION, "Unknown");
    }

    @NonNull
    private String safeExtra(@NonNull Intent intent, @NonNull String key, @NonNull String fallback) {
        String value = intent.getStringExtra(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_DIALOG_BG);
        int padding = dp(20);
        root.setPadding(padding, dp(14), padding, dp(12));
        setContentView(root);
        LauncherTheme.applyRainbowBackgroundIfNeeded(this);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView back = createTextButton("‹", false);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout titleColumn = new LinearLayout(this);
        titleColumn.setOrientation(LinearLayout.VERTICAL);
        titleColumn.setPadding(dp(12), 0, 0, 0);
        header.addView(titleColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        textTitle = new TextView(this);
        textTitle.setText(projectTitle);
        textTitle.setTextColor(COLOR_TEXT_PRIMARY);
        textTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 25);
        textTitle.setTypeface(Typeface.DEFAULT_BOLD);
        textTitle.setSingleLine(true);
        textTitle.setEllipsize(TextUtils.TruncateAt.END);
        titleColumn.addView(textTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        textMeta = new TextView(this);
        textMeta.setText(getPlatformLabel() + " update · Current pack: " + currentPackVersion + " · Instance: " + instanceName);
        textMeta.setTextColor(COLOR_TEXT_SECONDARY);
        textMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        textMeta.setSingleLine(false);
        titleColumn.addView(textMeta, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout infoCard = addCard(root);
        addCardTitle(infoCard, "Worlds are preserved");
        addCardText(infoCard, "Existing saves stay in place. If the new pack includes saves, they are installed too. Conflicting bundled worlds are copied with a new name.", COLOR_TEXT_MUTED, 12);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(0, dp(2), 0, dp(10));
        root.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setGravity(Gravity.CENTER_VERTICAL);
        controls.addView(filterRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        buttonFilter = createTextButton(FILTER_ALL, false);
        buttonFilter.setGravity(Gravity.CENTER_VERTICAL);
        buttonFilter.setOnClickListener(view -> showMinecraftFilterDialog());
        filterRow.addView(buttonFilter, new LinearLayout.LayoutParams(0, dp(50), 1f));

        textPageIndicator = new TextView(this);
        textPageIndicator.setText("Grouped by Minecraft version");
        textPageIndicator.setTextColor(COLOR_TEXT_MUTED);
        textPageIndicator.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        textPageIndicator.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        LinearLayout.LayoutParams pageParams = new LinearLayout.LayoutParams(dp(170), ViewGroup.LayoutParams.WRAP_CONTENT);
        pageParams.leftMargin = dp(12);
        filterRow.addView(textPageIndicator, pageParams);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        LinearLayout.LayoutParams actionRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        actionRowParams.topMargin = dp(10);
        controls.addView(actionRow, actionRowParams);

        textFilterSummary = new TextView(this);
        textFilterSummary.setText("Loading versions...");
        textFilterSummary.setTextColor(COLOR_TEXT_SECONDARY);
        textFilterSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        textFilterSummary.setSingleLine(false);
        actionRow.addView(textFilterSummary, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        buttonPrevious = createTextButton("Collapse all", false);
        buttonPrevious.setOnClickListener(view -> setAllGroupsCollapsed(true));
        LinearLayout.LayoutParams collapseParams = new LinearLayout.LayoutParams(dp(132), dp(46));
        collapseParams.leftMargin = dp(12);
        actionRow.addView(buttonPrevious, collapseParams);

        buttonNext = createTextButton("Expand all", true);
        buttonNext.setOnClickListener(view -> setAllGroupsCollapsed(false));
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(dp(122), dp(46));
        nextParams.leftMargin = dp(8);
        actionRow.addView(buttonNext, nextParams);

        FrameLayout listFrame = new FrameLayout(this);
        GradientDrawable listBg = roundedDrawable(COLOR_CARD_BG, COLOR_CARD_STROKE, 20);
        listFrame.setBackground(listBg);
        listFrame.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(listFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        recyclerVersions = new RecyclerView(this);
        recyclerVersions.setLayoutManager(new LinearLayoutManager(this));
        recyclerVersions.setNestedScrollingEnabled(true);
        recyclerVersions.setClipToPadding(false);
        recyclerVersions.setPadding(0, 0, 0, dp(12));
        recyclerVersions.setAdapter(adapter);
        listFrame.addView(recyclerVersions, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(dp(4), dp(10), dp(4), 0);
        root.addView(statusRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        statusRow.addView(progressBar, new LinearLayout.LayoutParams(dp(28), dp(28)));

        textStatus = new TextView(this);
        textStatus.setText("Loading versions...");
        textStatus.setTextColor(COLOR_TEXT_SECONDARY);
        textStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        textStatus.setPadding(dp(12), 0, 0, 0);
        textStatus.setSingleLine(false);
        statusRow.addView(textStatus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private void loadVersions() {
        if (gameDirectory == null) {
            textStatus.setText("Missing instance game directory.");
            return;
        }
        if (isBlank(projectId)) {
            textStatus.setText("Missing modpack project id.");
            return;
        }

        setBusy(true, "Loading " + getPlatformLabel() + " versions...");
        Thread thread = new Thread(() -> {
            try {
                ModpackUpdateManager.ProjectMatch project = createProjectMatch();
                ArrayList<ModpackUpdateManager.VersionInfo> versions = ModpackUpdateManager.loadVersions(
                        project,
                        new ModpackUpdateManager.Listener() {
                            @Override
                            public void onStatus(@NonNull String message) {
                                runOnUiThread(() -> textStatus.setText(message));
                            }

                            @Override
                            public void onProgress(int current, int total) {
                            }
                        }
                );
                runOnUiThread(() -> {
                    setBusy(false, versions.isEmpty() ? "No versions were returned." : versions.size() + " versions loaded.");
                    submitVersions(versions);
                });
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to load modpack versions", throwable);
                runOnUiThread(() -> {
                    setBusy(false, "Unable to load versions: " + readableError(throwable));
                    Toast.makeText(this, "Unable to load modpack versions: " + readableError(throwable), Toast.LENGTH_LONG).show();
                });
            }
        }, "Load Modpack Update Versions");
        thread.start();
    }

    @NonNull
    private ModpackUpdateManager.ProjectMatch createProjectMatch() {
        return ModpackUpdateManager.createProjectMatch(
                toPlatform(projectPlatform),
                projectId,
                projectTitle,
                projectSlug,
                projectSummary,
                true
        );
    }

    private void submitVersions(@NonNull ArrayList<ModpackUpdateManager.VersionInfo> versions) {
        allVersions.clear();
        allVersions.addAll(versions);
        collapseAllOnNextRebuild = true;
        setupMinecraftFilters();
        applyFilter();
    }

    private void setupMinecraftFilters() {
        minecraftFilters.clear();
        minecraftFilters.add(FILTER_ALL);

        for (ModpackUpdateManager.VersionInfo version : allVersions) {
            for (String mc : version.gameVersions) {
                String clean = normalizeMinecraftVersion(mc);
                if (clean.isEmpty()) clean = FILTER_UNKNOWN;
                if (!containsIgnoreCase(minecraftFilters, clean)) minecraftFilters.add(clean);
            }
        }

        if (minecraftFilters.size() > 2) {
            ArrayList<String> sorted = new ArrayList<>(minecraftFilters.subList(1, minecraftFilters.size()));
            Collections.sort(sorted, this::compareMinecraftKeysDescending);
            minecraftFilters.clear();
            minecraftFilters.add(FILTER_ALL);
            minecraftFilters.addAll(sorted);
        }

        selectedFilter = FILTER_ALL;
        updateFilterButton();
    }

    private void applyFilter() {
        filteredVersions.clear();
        for (ModpackUpdateManager.VersionInfo version : allVersions) {
            if (matchesFilter(version, selectedFilter)) filteredVersions.add(version);
        }
        rebuildVersionGroups();
        refreshVisibleRows();
    }

    private void rebuildVersionGroups() {
        versionGroups.clear();

        LinkedHashMap<String, ArrayList<ModpackUpdateManager.VersionInfo>> grouped = new LinkedHashMap<>();
        for (ModpackUpdateManager.VersionInfo version : filteredVersions) {
            String groupKey = resolveGroupMinecraftVersion(version);
            ArrayList<ModpackUpdateManager.VersionInfo> values = grouped.get(groupKey);
            if (values == null) {
                values = new ArrayList<>();
                grouped.put(groupKey, values);
            }
            values.add(version);
        }

        ArrayList<String> orderedKeys = new ArrayList<>();
        for (String filter : minecraftFilters) {
            if (FILTER_ALL.equals(filter)) continue;
            if (grouped.containsKey(filter)) orderedKeys.add(filter);
        }
        for (String key : grouped.keySet()) {
            if (!containsIgnoreCase(orderedKeys, key)) orderedKeys.add(key);
        }

        for (String key : orderedKeys) {
            ArrayList<ModpackUpdateManager.VersionInfo> versions = grouped.get(key);
            if (versions == null || versions.isEmpty()) continue;
            versionGroups.add(new VersionGroup(key, versions));
        }

        HashSet<String> validCollapsed = new HashSet<>();
        for (VersionGroup group : versionGroups) {
            if (collapseAllOnNextRebuild || collapsedGroups.contains(group.minecraftVersion)) {
                validCollapsed.add(group.minecraftVersion);
            }
        }
        collapsedGroups.clear();
        collapsedGroups.addAll(validCollapsed);
        collapseAllOnNextRebuild = false;
    }

    @NonNull
    private String resolveGroupMinecraftVersion(@NonNull ModpackUpdateManager.VersionInfo version) {
        if (!FILTER_ALL.equals(selectedFilter)) return selectedFilter;

        String best = normalizeMinecraftVersion(version.primaryMinecraftVersion);
        if (best.isEmpty()) best = FILTER_UNKNOWN;

        for (String raw : version.gameVersions) {
            String clean = normalizeMinecraftVersion(raw);
            if (clean.isEmpty()) clean = FILTER_UNKNOWN;
            if (compareMinecraftKeysDescending(clean, best) < 0) best = clean;
        }
        return best;
    }

    private void refreshVisibleRows() {
        visibleRows.clear();
        int visibleVersionCount = 0;
        int hiddenVersionCount = 0;

        for (VersionGroup group : versionGroups) {
            visibleRows.add(VersionDisplayRow.group(group));
            boolean collapsed = collapsedGroups.contains(group.minecraftVersion);
            if (collapsed) {
                hiddenVersionCount += group.versions.size();
            } else {
                for (ModpackUpdateManager.VersionInfo version : group.versions) {
                    visibleRows.add(VersionDisplayRow.version(version));
                    visibleVersionCount++;
                }
            }
        }

        adapter.submit(visibleRows);
        textPageIndicator.setText(versionGroups.size() == 1 ? "1 MC version" : versionGroups.size() + " MC versions");
        textFilterSummary.setText(buildFilterSummary(visibleVersionCount, hiddenVersionCount));

        boolean hasExpanded = false;
        boolean hasCollapsed = false;
        for (VersionGroup group : versionGroups) {
            if (collapsedGroups.contains(group.minecraftVersion)) hasCollapsed = true;
            else hasExpanded = true;
        }
        buttonPrevious.setEnabled(hasExpanded);
        buttonNext.setEnabled(hasCollapsed);
        buttonPrevious.setAlpha(hasExpanded ? 1f : 0.45f);
        buttonNext.setAlpha(hasCollapsed ? 1f : 0.45f);
    }

    private void toggleGroup(@NonNull String minecraftVersion) {
        if (collapsedGroups.contains(minecraftVersion)) {
            collapsedGroups.remove(minecraftVersion);
        } else {
            collapsedGroups.add(minecraftVersion);
        }
        refreshVisibleRows();
    }

    private void setAllGroupsCollapsed(boolean collapsed) {
        collapsedGroups.clear();
        if (collapsed) {
            for (VersionGroup group : versionGroups) collapsedGroups.add(group.minecraftVersion);
        }
        refreshVisibleRows();
    }

    @NonNull
    private String buildFilterSummary(int visibleVersionCount, int hiddenVersionCount) {
        if (allVersions.isEmpty()) return "No versions were found for this modpack.";
        String prefix = FILTER_ALL.equals(selectedFilter)
                ? "All Minecraft versions"
                : "Minecraft " + selectedFilter;
        if (filteredVersions.isEmpty()) return prefix + " · No matching versions";

        String summary = prefix + " · " + filteredVersions.size() + " versions"
                + " · " + versionGroups.size() + (versionGroups.size() == 1 ? " group" : " groups");
        if (hiddenVersionCount > 0) {
            summary += " · " + hiddenVersionCount + " hidden";
        }
        return summary;
    }

    private boolean matchesFilter(@NonNull ModpackUpdateManager.VersionInfo version, @NonNull String selected) {
        if (FILTER_ALL.equals(selected)) return true;
        for (String mc : version.gameVersions) {
            String clean = normalizeMinecraftVersion(mc);
            if (clean.isEmpty()) clean = FILTER_UNKNOWN;
            if (selected.equalsIgnoreCase(clean)) return true;
        }
        return false;
    }

    private void showMinecraftFilterDialog() {
        if (minecraftFilters.isEmpty()) return;
        String[] labels = new String[minecraftFilters.size()];
        for (int i = 0; i < minecraftFilters.size(); i++) {
            String filter = minecraftFilters.get(i);
            labels[i] = filter + "  ·  " + countForFilter(filter) + " versions";
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Pick Minecraft Version")
                .setItems(labels, (unused, which) -> {
                    selectedFilter = minecraftFilters.get(which);
                    collapsedGroups.clear();
                    collapseAllOnNextRebuild = true;
                    updateFilterButton();
                    applyFilter();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(unused -> styleDialog(dialog));
        dialog.setOnDismissListener(unused -> FullscreenUtils.enableImmersive(this));
        dialog.show();
    }

    private int countForFilter(@NonNull String filter) {
        int count = 0;
        for (ModpackUpdateManager.VersionInfo version : allVersions) {
            if (matchesFilter(version, filter)) count++;
        }
        return count;
    }

    private void updateFilterButton() {
        buttonFilter.setText(selectedFilter);
    }

    private void confirmUpdate(@NonNull ModpackUpdateManager.VersionInfo version) {
        StringBuilder message = new StringBuilder();
        message.append("Update ").append(instanceName).append(" with this modpack version?");
        message.append("\n\nSelected version:\n").append(version.versionLabel);
        message.append("\nMinecraft ").append(version.getMinecraftVersionsLabel()).append(" · ").append(version.getLoadersLabel());
        if (!isBlank(version.datePublished)) message.append("\n").append(trimDate(version.datePublished));
        message.append("\n\nBack up your worlds before updating. Modpack updates can replace configs and other pack-managed files.");
        message.append("\n\nThe old mods, shaderpacks, and resourcepacks folders will be deleted and rebuilt from the selected pack so stale files do not survive the update.");
        message.append("\n\nExisting saves stay in place. If this pack includes saves, those saves are installed too. Conflicting bundled worlds are copied with a new name instead of overwriting your worlds.");

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Update Modpack")
                .setMessage(message.toString())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Update", (unused, which) -> runUpdate(version))
                .create();
        dialog.setOnShowListener(unused -> styleDialog(dialog));
        dialog.setOnDismissListener(unused -> FullscreenUtils.enableImmersive(this));
        dialog.show();
    }

    private void runUpdate(@NonNull ModpackUpdateManager.VersionInfo selectedVersion) {
        if (gameDirectory == null) {
            Toast.makeText(this, "Missing instance folder.", Toast.LENGTH_LONG).show();
            return;
        }

        ModpackUpdateManager.InstalledModpackInfo installed = ModpackUpdateManager.readInstalledModpackInfo(rootDirectory, gameDirectory);
        if (installed == null) {
            Toast.makeText(this, "This instance does not have modpack update metadata.", Toast.LENGTH_LONG).show();
            return;
        }

        setBusy(true, "Preparing modpack update...");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        recyclerVersions.setAlpha(0.55f);

        Thread thread = new Thread(() -> {
            try {
                ModpackUpdateManager.UpdateResult packResult = ModpackUpdateManager.updateInstalledModpack(
                        this,
                        rootDirectory,
                        gameDirectory,
                        minecraftVersionId,
                        loader,
                        installed,
                        createProjectMatch(),
                        selectedVersion,
                        new ModpackUpdateManager.Listener() {
                            @Override
                            public void onStatus(@NonNull String message) {
                                runOnUiThread(() -> textStatus.setText(message));
                            }

                            @Override
                            public void onProgress(int current, int total) {
                                if (total > 0) {
                                    runOnUiThread(() -> textStatus.setText("Updating " + Math.max(0, current) + " / " + total));
                                }
                            }
                        }
                );

                InstanceVersionUpdater.UpdateResult versionResult = null;
                if (shouldUpdateInstanceBaseForModpack(packResult)) {
                    String targetLoader = isBlank(packResult.loader) ? loader : packResult.loader;
                    String targetMinecraft = isBlank(packResult.minecraftVersion) ? minecraftVersionId : packResult.minecraftVersion;
                    runOnUiThread(() -> textStatus.setText("Updating instance to Minecraft " + targetMinecraft + "..."));
                    versionResult = InstanceVersionUpdater.updateInstanceVersion(
                            this,
                            rootDirectory,
                            gameDirectory,
                            instanceName,
                            targetLoader,
                            targetMinecraft,
                            new InstanceVersionUpdater.Listener() {
                                @Override
                                public void onStatus(@NonNull String message) {
                                    runOnUiThread(() -> textStatus.setText(message));
                                }

                                @Override
                                public void onProgress(int current, int total) {
                                }
                            }
                    );
                }

                InstanceVersionUpdater.UpdateResult finalVersionResult = versionResult;
                runOnUiThread(() -> finishUpdate(packResult, finalVersionResult));
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to update modpack", throwable);
                runOnUiThread(() -> {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    recyclerVersions.setAlpha(1f);
                    setBusy(false, "Modpack update failed: " + readableError(throwable));
                    Toast.makeText(this, "Modpack update failed: " + readableError(throwable), Toast.LENGTH_LONG).show();
                });
            }
        }, "Update Modpack");
        thread.start();
    }

    private void finishUpdate(
            @NonNull ModpackUpdateManager.UpdateResult packResult,
            @Nullable InstanceVersionUpdater.UpdateResult versionResult
    ) {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        recyclerVersions.setAlpha(1f);
        setBusy(false, "Update complete.");

        Intent data = new Intent();
        String versionSuffix = "";
        if (versionResult != null) {
            loader = versionResult.loader;
            baseVersionId = versionResult.baseVersionId;
            minecraftVersionId = versionResult.minecraftVersionId;
            versionType = versionResult.versionType;
            data.putExtra(InstanceDetailsActivity.EXTRA_INSTANCE_LOADER, versionResult.loader);
            data.putExtra(InstanceDetailsActivity.EXTRA_BASE_VERSION_ID, versionResult.baseVersionId);
            data.putExtra(InstanceDetailsActivity.EXTRA_MINECRAFT_VERSION_ID, versionResult.minecraftVersionId);
            data.putExtra(InstanceDetailsActivity.EXTRA_VERSION_TYPE, versionResult.versionType);
            versionSuffix = " · Minecraft " + versionResult.minecraftVersionId
                    + (isBlank(versionResult.loaderVersion) ? "" : " · " + versionResult.loader + " " + versionResult.loaderVersion);
        }

        String cleanup = packResult.removedOldFiles <= 0
                ? ""
                : " · removed " + packResult.removedOldFiles + " old " + (packResult.removedOldFiles == 1 ? "file" : "files");
        String message = "Modpack updated to " + packResult.versionLabel + versionSuffix + cleanup;
        setResult(Activity.RESULT_OK, data);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    private boolean shouldUpdateInstanceBaseForModpack(@NonNull ModpackUpdateManager.UpdateResult result) {
        if (isBlank(result.minecraftVersion)) return false;
        if (!result.minecraftVersion.equalsIgnoreCase(minecraftVersionId)) return true;
        String targetLoader = normalizeLoaderNameForUpdate(result.loader);
        String currentLoader = normalizeLoaderNameForUpdate(loader);
        return !isBlank(targetLoader) && !targetLoader.equals(currentLoader);
    }

    @NonNull
    private String normalizeLoaderNameForUpdate(@Nullable String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.US);
        if (normalized.contains("neoforge") || normalized.contains("neo forge")) return "neoforge";
        if (normalized.contains("forge")) return "forge";
        if (normalized.contains("fabric")) return "fabric";
        if (normalized.contains("quilt")) return "quilt";
        if (normalized.contains("vanilla")) return "vanilla";
        return normalized;
    }

    private void setBusy(boolean busy, @NonNull String message) {
        if (progressBar != null) progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (textStatus != null) textStatus.setText(message);
        if (buttonFilter != null) buttonFilter.setEnabled(!busy && !allVersions.isEmpty());
        if (buttonPrevious != null) buttonPrevious.setEnabled(!busy && !versionGroups.isEmpty());
        if (buttonNext != null) buttonNext.setEnabled(!busy && !versionGroups.isEmpty());
    }

    @NonNull
    private ModpackUpdateManager.Platform toPlatform(@NonNull String value) {
        return PLATFORM_CURSEFORGE.equalsIgnoreCase(value)
                ? ModpackUpdateManager.Platform.CURSEFORGE
                : ModpackUpdateManager.Platform.MODRINTH;
    }

    @NonNull
    private String getPlatformLabel() {
        return toPlatform(projectPlatform).displayName;
    }

    @NonNull
    private String normalizeMinecraftVersion(@Nullable String value) {
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

    private int compareMinecraftKeysDescending(@NonNull String left, @NonNull String right) {
        boolean leftUnknown = FILTER_UNKNOWN.equalsIgnoreCase(left);
        boolean rightUnknown = FILTER_UNKNOWN.equalsIgnoreCase(right);
        if (leftUnknown && rightUnknown) return 0;
        if (leftUnknown) return 1;
        if (rightUnknown) return -1;
        int compare = compareVersionLabelsDescending(left, right);
        if (compare != 0) return compare;
        return right.compareToIgnoreCase(left);
    }

    private int compareVersionLabelsDescending(@Nullable String left, @Nullable String right) {
        String a = left == null ? "" : left.trim();
        String b = right == null ? "" : right.trim();
        if (a.isEmpty() && b.isEmpty()) return 0;
        if (a.isEmpty()) return 1;
        if (b.isEmpty()) return -1;
        ArrayList<Integer> aParts = extractVersionNumberParts(a);
        ArrayList<Integer> bParts = extractVersionNumberParts(b);
        int max = Math.max(aParts.size(), bParts.size());
        for (int i = 0; i < max; i++) {
            int av = i < aParts.size() ? aParts.get(i) : 0;
            int bv = i < bParts.size() ? bParts.get(i) : 0;
            if (av != bv) return Integer.compare(bv, av);
        }
        return b.compareToIgnoreCase(a);
    }

    @NonNull
    private ArrayList<Integer> extractVersionNumberParts(@NonNull String value) {
        ArrayList<Integer> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isDigit(ch)) current.append(ch);
            else if (current.length() > 0) {
                addVersionPart(result, current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) addVersionPart(result, current.toString());
        return result;
    }

    private void addVersionPart(@NonNull ArrayList<Integer> result, @NonNull String value) {
        try {
            result.add(Integer.parseInt(value));
        } catch (Throwable ignored) {
            result.add(0);
        }
    }

    @NonNull
    private String trimDate(@Nullable String value) {
        if (value == null) return "";
        String clean = value.trim();
        return clean.substring(0, Math.min(10, clean.length()));
    }

    private boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    @NonNull
    private String readableError(@NonNull Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    @NonNull
    private LinearLayout addCard(@NonNull LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(14));
        card.setBackground(roundedDrawable(COLOR_CARD_BG, COLOR_CARD_STROKE, 18));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(12), 0, dp(14));
        parent.addView(card, params);
        return card;
    }

    private void addCardTitle(@NonNull LinearLayout parent, @NonNull String value) {
        TextView title = new TextView(this);
        title.setText(value);
        title.setTextColor(COLOR_TEXT_PRIMARY);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(6));
        parent.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    private void addCardText(@NonNull LinearLayout parent, @NonNull String value, int color, int sp) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(color);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        text.setSingleLine(false);
        text.setPadding(0, 0, 0, dp(4));
        parent.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    @NonNull
    private TextView createTextButton(@NonNull String text, boolean filled) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        int accent = resolveThemeColor(android.R.attr.colorAccent, COLOR_ACCENT);
        button.setTextColor(filled ? COLOR_TEXT_PRIMARY : accent);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setBackground(roundedDrawable(filled ? accent : Color.TRANSPARENT, accent, 18));
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    @NonNull
    private GradientDrawable roundedDrawable(int fillColor, int strokeColor, int cornerDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fillColor);
        bg.setCornerRadius(dp(cornerDp));
        bg.setStroke(dp(1), strokeColor);
        return bg;
    }

    private void styleDialog(@NonNull AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(roundedDrawable(COLOR_DIALOG_BG, COLOR_DIALOG_BG, 22));
            window.setDimAmount(0.58f);
        }
        TextView positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        TextView negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        int accent = resolveThemeColor(android.R.attr.colorAccent, COLOR_ACCENT);
        if (positive != null) positive.setTextColor(accent);
        if (negative != null) negative.setTextColor(accent);
        FullscreenUtils.enableImmersive(this);
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class VersionGroup {
        @NonNull final String minecraftVersion;
        @NonNull final ArrayList<ModpackUpdateManager.VersionInfo> versions;

        VersionGroup(
                @NonNull String minecraftVersion,
                @NonNull ArrayList<ModpackUpdateManager.VersionInfo> versions
        ) {
            this.minecraftVersion = minecraftVersion;
            this.versions = versions;
        }
    }

    private static final class VersionDisplayRow {
        @Nullable final VersionGroup group;
        @Nullable final ModpackUpdateManager.VersionInfo version;

        private VersionDisplayRow(
                @Nullable VersionGroup group,
                @Nullable ModpackUpdateManager.VersionInfo version
        ) {
            this.group = group;
            this.version = version;
        }

        @NonNull
        static VersionDisplayRow group(@NonNull VersionGroup group) {
            return new VersionDisplayRow(group, null);
        }

        @NonNull
        static VersionDisplayRow version(@NonNull ModpackUpdateManager.VersionInfo version) {
            return new VersionDisplayRow(null, version);
        }
    }

    private final class VersionAdapter extends RecyclerView.Adapter<VersionAdapter.ViewHolder> {
        private static final int TYPE_GROUP = 0;
        private static final int TYPE_VERSION = 1;
        private final ArrayList<VersionDisplayRow> rows = new ArrayList<>();

        void submit(@NonNull ArrayList<VersionDisplayRow> values) {
            rows.clear();
            rows.addAll(values);
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            VersionDisplayRow row = rows.get(position);
            return row.group != null ? TYPE_GROUP : TYPE_VERSION;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_GROUP) {
                LinearLayout row = new LinearLayout(parent.getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(18), dp(14), dp(18), dp(14));
                row.setMinimumHeight(dp(70));
                row.setBackground(roundedDrawable(COLOR_CARD_BG_PRESSED, COLOR_CARD_STROKE, 16));

                LinearLayout textColumn = new LinearLayout(parent.getContext());
                textColumn.setOrientation(LinearLayout.VERTICAL);
                textColumn.setGravity(Gravity.CENTER_VERTICAL);
                row.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView title = new TextView(parent.getContext());
                title.setTextColor(COLOR_TEXT_PRIMARY);
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                title.setTypeface(Typeface.DEFAULT_BOLD);
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                textColumn.addView(title, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                ));

                TextView meta = new TextView(parent.getContext());
                meta.setTextColor(COLOR_TEXT_MUTED);
                meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                meta.setSingleLine(true);
                meta.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                metaParams.topMargin = dp(3);
                textColumn.addView(meta, metaParams);

                TextView indicator = new TextView(parent.getContext());
                indicator.setTextColor(resolveThemeColor(android.R.attr.colorAccent, COLOR_ACCENT));
                indicator.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
                indicator.setGravity(Gravity.CENTER);
                row.addView(indicator, new LinearLayout.LayoutParams(dp(42), dp(42)));

                RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                params.setMargins(0, 0, 0, dp(10));
                row.setLayoutParams(params);

                return new ViewHolder(row, title, meta, indicator, true);
            }

            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(18), dp(14), dp(18), dp(14));
            row.setMinimumHeight(dp(104));
            row.setBackground(roundedDrawable(COLOR_CARD_BG, COLOR_CARD_STROKE, 16));

            TextView title = new TextView(parent.getContext());
            title.setTextColor(COLOR_TEXT_PRIMARY);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            TextView meta = new TextView(parent.getContext());
            meta.setTextColor(COLOR_TEXT_SECONDARY);
            meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            meta.setSingleLine(false);
            meta.setMaxLines(3);
            LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            metaParams.topMargin = dp(5);
            row.addView(meta, metaParams);

            LinearLayout footer = new LinearLayout(parent.getContext());
            footer.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            footerParams.topMargin = dp(12);
            row.addView(footer, footerParams);

            TextView update = createTextButton("Update", true);
            footer.addView(update, new LinearLayout.LayoutParams(dp(112), dp(44)));

            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(dp(20), 0, 0, dp(12));
            row.setLayoutParams(params);

            return new ViewHolder(row, title, meta, update, false);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            VersionDisplayRow row = rows.get(position);
            if (row.group != null) {
                bindGroup(holder, row.group);
                return;
            }

            if (row.version != null) {
                bindVersion(holder, row.version);
            }
        }

        private void bindGroup(@NonNull ViewHolder holder, @NonNull VersionGroup group) {
            boolean collapsed = collapsedGroups.contains(group.minecraftVersion);
            holder.title.setText((collapsed ? "▸ " : "▾ ") + "Minecraft " + group.minecraftVersion);
            holder.meta.setText(group.versions.size() + (group.versions.size() == 1 ? " version" : " versions"));
            holder.action.setText(collapsed ? "+" : "–");
            holder.itemView.setOnClickListener(view -> toggleGroup(group.minecraftVersion));
            holder.action.setOnClickListener(view -> toggleGroup(group.minecraftVersion));
        }

        private void bindVersion(@NonNull ViewHolder holder, @NonNull ModpackUpdateManager.VersionInfo version) {
            holder.title.setText((isLatestVersion(version) ? "Latest available: " : "") + version.versionLabel);

            ArrayList<String> parts = new ArrayList<>();
            parts.add("Minecraft " + version.getMinecraftVersionsLabel() + " · " + version.getLoadersLabel());
            String date = trimDate(version.datePublished);
            if (!date.isEmpty()) parts.add(date);
            if (!isBlank(version.fileName)) parts.add(version.fileName);
            holder.meta.setText(join(parts, "\n"));
            holder.action.setText("Update");
            holder.action.setOnClickListener(view -> confirmUpdate(version));
            holder.itemView.setOnClickListener(view -> confirmUpdate(version));
        }

        private boolean isLatestVersion(@NonNull ModpackUpdateManager.VersionInfo version) {
            return !allVersions.isEmpty() && allVersions.get(0) == version;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        final class ViewHolder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView meta;
            final TextView action;
            final boolean groupRow;

            ViewHolder(
                    @NonNull View itemView,
                    @NonNull TextView title,
                    @NonNull TextView meta,
                    @NonNull TextView action,
                    boolean groupRow
            ) {
                super(itemView);
                this.title = title;
                this.meta = meta;
                this.action = action;
                this.groupRow = groupRow;
            }
        }
    }

    @NonNull
    private String join(@NonNull ArrayList<String> values, @NonNull String separator) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) continue;
            if (builder.length() > 0) builder.append(separator);
            builder.append(value.trim());
        }
        return builder.toString();
    }
}

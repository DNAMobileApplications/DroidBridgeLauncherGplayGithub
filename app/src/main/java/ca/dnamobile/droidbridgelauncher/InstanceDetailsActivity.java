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

import android.annotation.SuppressLint;
import androidx.appcompat.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import ca.dnamobile.droidbridgelauncher.fancymenu.MicrosoftAuthConfigPersonal;
import ca.dnamobile.droidbridgelauncher.fancymenu.MicrosoftAuthManagerPersonal;
import ca.dnamobile.droidbridgelauncher.modmanager.ModManagerTools;
import ca.dnamobile.droidbridgelauncher.data.AccountStore;
import ca.dnamobile.droidbridgelauncher.data.model.MinecraftVersion;
import ca.dnamobile.droidbridgelauncher.databinding.ActivityInstanceDetailsBinding;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.modmanager.ModpackExportOptionsDialog;
import ca.dnamobile.droidbridgelauncher.storage.DroidBridgeDocumentsProvider;
import ca.dnamobile.droidbridgelauncher.ui.LauncherDialogStyle;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstance;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstanceManager;
import ca.dnamobile.droidbridgelauncher.launcher.QuickPlayHelper;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstanceDeleteManager;
import ca.dnamobile.droidbridgelauncher.instance.InstanceVersionUpdater;
import ca.dnamobile.droidbridgelauncher.launcher.InstanceLaunchSettings;
import ca.dnamobile.droidbridgelauncher.modmanager.DroidBridgeMetadataPaths;
import ca.dnamobile.droidbridgelauncher.modmanager.ModManagerContentType;
import ca.dnamobile.droidbridgelauncher.modmanager.ModManagerManifest;
import ca.dnamobile.droidbridgelauncher.modmanager.ModJarMetadataExtractor;
import ca.dnamobile.droidbridgelauncher.modmanager.ModManagerSource;
import ca.dnamobile.droidbridgelauncher.modmanager.ModManagerUpdateManager;
import ca.dnamobile.droidbridgelauncher.modmanager.ModManagerVersionResolver;
import ca.dnamobile.droidbridgelauncher.renderer.BtaRendererPolicy;
import ca.dnamobile.droidbridgelauncher.renderer.MobileGluesConfigHelper;
import ca.dnamobile.droidbridgelauncher.renderer.RendererInterface;
import ca.dnamobile.droidbridgelauncher.renderer.RendererPluginManager;
import ca.dnamobile.droidbridgelauncher.renderer.Renderers;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.shortcuts.InstanceShortcutHelper;
import ca.dnamobile.droidbridgelauncher.settings.MemoryAllocationUtils;
import ca.dnamobile.droidbridgelauncher.storage.SafMinecraftMirror;
import ca.dnamobile.droidbridgelauncher.storage.StorageLocation;
import ca.dnamobile.droidbridgelauncher.storage.StorageLocationStore;
import ca.dnamobile.droidbridgelauncher.utils.AppOrientationHelper;
import ca.dnamobile.droidbridgelauncher.utils.FullscreenUtils;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;
import ca.dnamobile.droidbridgelauncher.ui.version.MinecraftVersionInstaller;
import ca.dnamobile.droidbridgelauncher.ui.version.MinecraftVersionManifestClient;
import ca.dnamobile.droidbridgelauncher.ui.version.CleanroomCompatibilityManager;
import ca.dnamobile.droidbridgelauncher.ui.version.CleanroomMigrationDialog;
import ca.dnamobile.droidbridgelauncher.ui.version.CleanroomMigrationManager;
import ca.dnamobile.droidbridgelauncher.ui.instance.PerInstanceSettingsDialog;
import ca.dnamobile.droidbridgelauncher.ui.instance.InstanceIconResolver;
import ca.dnamobile.droidbridgelauncher.modmanager.ModrinthInstallManager;
import ca.dnamobile.droidbridgelauncher.modmanager.ModpackExportManager;
import ca.dnamobile.droidbridgelauncher.modmanager.ModpackInstallManager;
import ca.dnamobile.droidbridgelauncher.modmanager.ModpackUpdateManager;
import ca.dnamobile.droidbridgelauncher.modmanager.CurseForgeApiKeyProvider;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class InstanceDetailsActivity extends AppCompatActivity {
    public static final String EXTRA_INSTANCE_ID = "ca.dnamobile.droidbridgelauncher.extra.INSTANCE_ID";
    public static final String EXTRA_INSTANCE_NAME = "ca.dnamobile.droidbridgelauncher.extra.INSTANCE_NAME";
    public static final String EXTRA_INSTANCE_LOADER = "ca.dnamobile.droidbridgelauncher.extra.INSTANCE_LOADER";
    public static final String EXTRA_BASE_VERSION_ID = "ca.dnamobile.droidbridgelauncher.extra.BASE_VERSION_ID";
    public static final String EXTRA_MINECRAFT_VERSION_ID = "ca.dnamobile.droidbridgelauncher.extra.MINECRAFT_VERSION_ID";
    public static final String EXTRA_VERSION_TYPE = "ca.dnamobile.droidbridgelauncher.extra.VERSION_TYPE";
    public static final String EXTRA_ROOT_DIRECTORY = "ca.dnamobile.droidbridgelauncher.extra.ROOT_DIRECTORY";
    public static final String EXTRA_GAME_DIRECTORY = "ca.dnamobile.droidbridgelauncher.extra.GAME_DIRECTORY";
    public static final String EXTRA_ICON_FILE = "ca.dnamobile.droidbridgelauncher.extra.ICON_FILE";
    public static final String EXTRA_ISOLATED = "ca.dnamobile.droidbridgelauncher.extra.ISOLATED";
    public static final String EXTRA_CONTENT_CATEGORY = "ca.dnamobile.droidbridgelauncher.extra.CONTENT_CATEGORY";

    private static final int REQUEST_PICK_CONTENT = 9124;
    private static final int REQUEST_PICK_INSTANCE_ICON = 9125;
    private static final int REQUEST_EXPORT_MODPACK = 9126;
    private static final int REQUEST_IMPORT_MODPACK = 9127;
    private static final int REQUEST_EXPORT_WORLD = 9128;
    private static final int REQUEST_UPDATE_MODPACK = 9129;
    private static final String TAG = "InstanceDetails";

    private static final int MENU_VIEW_FOLDER = 1;
    private static final int MENU_DELETE_INSTANCE = 2;
    private static final int MENU_EDIT_INSTANCE_NAME = 3;
    private static final int MENU_EDIT_INSTANCE_ICON = 4;
    private static final int MENU_EXPORT_MODPACK = 5;
    private static final int MENU_UPDATE_VERSION = 6;
    private static final int MENU_UPDATE_LOADER = 7;
    private static final int MENU_IMPORT_MODPACK = 8;
    private static final int MENU_PER_INSTANCE_SETTINGS = 9;
    private static final int MENU_REPAIR_INSTANCE = 10;
    private static final int MENU_UPDATE_MODPACK = 11;
    private static final int MENU_SHARE_CRASH_LOG = 12;
    private static final int MENU_MIGRATE_CLEANROOM = 13;
    private static final int MENU_REPAIR_CLEANROOM = 14;

    private ActivityInstanceDetailsBinding binding;
    private AccountStore accountStore;
    private MicrosoftAuthManagerPersonal authManager;
    @Nullable
    private Runnable pendingAfterMicrosoftSignIn;
    private String instanceId;
    private String instanceName;
    private String loader;
    private String baseVersionId;
    private String minecraftVersionId;
    private String versionType;
    private File rootDirectory;
    private File gameDirectory;
    @Nullable
    private File iconFile;
    private boolean isolated;

    private File modsDirectory;
    private File shaderpacksDirectory;
    private File resourcepacksDirectory;
    private File worldsDirectory;
    private File screenshotsDirectory;

    private ResourceCategory selectedCategory = ResourceCategory.MODS;
    private ResourceCategory pendingImportCategory = ResourceCategory.MODS;
    private static final int CONTENT_PAGE_SIZE = 10;
    private final ArrayList<InstanceContentItem> allContentItems = new ArrayList<>();
    private final ArrayList<InstanceContentItem> filteredContentItems = new ArrayList<>();
    private final ArrayList<InstanceContentItem> contentItems = new ArrayList<>();
    private int contentCurrentPage;
    private volatile int contentPageGeneration;
    private final Map<String, JSONObject> installedEntryCache = new ConcurrentHashMap<>();
    private final Set<String> missingInstalledEntryCache = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<ModManagerContentType, ArrayList<JSONObject>> modpackInstalledEntriesCache = new ConcurrentHashMap<>();
    private final Map<String, String> contentSearchMetadata = new ConcurrentHashMap<>();
    private String contentSearchQuery = "";
    private ContentVisibilityFilter contentVisibilityFilter = ContentVisibilityFilter.ALL;
    private boolean contentSearchFilterApplyQueued;
    @Nullable
    private Runnable pendingMetadataSearchFilterRunnable;
    private InstanceContentAdapter contentAdapter;
    private ScreenshotAdapter screenshotAdapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService iconExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService contentRefreshExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService contentOperationExecutor = Executors.newSingleThreadExecutor();
    private int contentRefreshGeneration;
    private boolean skipNextResumeContentRefresh;
    private boolean contentRefreshRunning;
    private boolean contentOperationRunning;
    @Nullable
    private View contentLoadingOverlay;
    @Nullable
    private TextView contentLoadingTitle;
    @Nullable
    private TextView contentLoadingMessage;
    @Nullable
    private Runnable pendingContentLoadingRunnable;
    private final Map<String, ModManagerUpdateManager.UpdateCandidate> updateCandidates = new ConcurrentHashMap<>();
    private final Map<String, UpdateState> updateStates = new ConcurrentHashMap<>();
    private final Map<String, String> updateMessages = new ConcurrentHashMap<>();
    private final Set<String> selectedContentKeys = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private boolean contentSelectionMode;
    @Nullable
    private AlertDialog updateProgressDialog;
    @Nullable
    private TextView updateProgressMessage;
    @Nullable
    private ProgressBar updateProgressBar;
    @Nullable
    private ModpackExportManager.Platform pendingExportPlatform;
    @Nullable
    private ModpackExportManager.ExportOptions pendingExportOptions;
    @Nullable
    private File pendingWorldExportDirectory;
    @Nullable
    private ActivityResultLauncher<Intent> mobileGluesFolderPickerLauncher;
    @Nullable
    private String pendingMobileGluesQuickPlayWorldFolderName;
    @Nullable
    private String pendingMobileGluesQuickPlayServerAddress;


    @NonNull
    public static Intent createIntent(@NonNull Context context, @NonNull LauncherInstance instance) {
        Intent intent = new Intent(context, InstanceDetailsActivity.class);
        intent.putExtra(EXTRA_INSTANCE_ID, instance.getId());
        intent.putExtra(EXTRA_INSTANCE_NAME, instance.getName());
        intent.putExtra(EXTRA_INSTANCE_LOADER, instance.getLoader());
        intent.putExtra(EXTRA_BASE_VERSION_ID, instance.getBaseVersionId());
        intent.putExtra(EXTRA_MINECRAFT_VERSION_ID, instance.getMinecraftVersionId());
        intent.putExtra(EXTRA_VERSION_TYPE, instance.getVersionType());
        intent.putExtra(EXTRA_ROOT_DIRECTORY, instance.getRootDirectory().getAbsolutePath());
        intent.putExtra(EXTRA_GAME_DIRECTORY, instance.getGameDirectory().getAbsolutePath());
        intent.putExtra(EXTRA_ICON_FILE, instance.getIconFile() != null ? instance.getIconFile().getAbsolutePath() : "");
        intent.putExtra(EXTRA_ISOLATED, instance.isIsolated());
        return intent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherTheme.apply(this);
        super.onCreate(savedInstanceState);
        AppOrientationHelper.applyToActivity(this);
        PathManager.initContextConstants(this);

        binding = ActivityInstanceDetailsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        LauncherTheme.applyRainbowBackgroundIfNeeded(this);
        enableFullscreen();
        registerMobileGluesFolderPickerLauncher();

        binding.buttonBackFromInstanceDetails.setOnClickListener(view -> finish());
        if (!readExtras()) {
            Toast.makeText(this, R.string.hint_select_instance, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setupAccountGate();
        bindHeader();
        setupActions();
        setupContentSearch();
        setupContentTabs();
        scheduleInitialContentRefresh();
        skipNextResumeContentRefresh = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppOrientationHelper.applyToActivity(this);
        enableFullscreen();
        if (skipNextResumeContentRefresh) {
            skipNextResumeContentRefresh = false;
            return;
        }
        if (binding != null && gameDirectory != null) {
            refreshContentList();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enableFullscreen();
        }
    }

    private void enableFullscreen() {
        FullscreenUtils.enableImmersive(this);
        applyFullscreenToWindow(getWindow());
    }

    private int getImmersiveSystemUiFlags() {
        return View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
    }

    private void applyFullscreenToWindow(@Nullable Window window) {
        if (window == null) return;

        View decorView = window.getDecorView();
        decorView.setSystemUiVisibility(getImmersiveSystemUiFlags());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    private void showFullscreenSafeDialog(@NonNull AlertDialog dialog) {
        // Do not replace an existing OnShowListener here.
        // Some dialogs, including Per Instance Settings, install button handlers in OnShow.
        // Replacing that listener makes Save/Reset look clickable but prevents the custom
        // save logic from running.
        dialog.setOnDismissListener(value -> mainHandler.postDelayed(this::enableFullscreen, 80L));
        dialog.show();
        applyFullscreenToWindow(dialog.getWindow());
        mainHandler.postDelayed(() -> {
            applyFullscreenToWindow(dialog.getWindow());
            enableFullscreen();
        }, 120L);
    }

    @Override
    protected void onDestroy() {
        cancelPendingContentLoadingOverlay();
        cancelPendingMetadataSearchFilter();
        contentRefreshExecutor.shutdownNow();
        contentOperationExecutor.shutdownNow();
        iconExecutor.shutdownNow();
        if (screenshotAdapter != null) screenshotAdapter.clearThumbnailCache();
        if (authManager != null) {
            authManager.dispose();
        }
        super.onDestroy();
    }

    private void setupAccountGate() {
        try {
            accountStore = new AccountStore(this);
            authManager = new MicrosoftAuthManagerPersonal(this, accountStore);
            authManager.setListener(new MicrosoftAuthManagerPersonal.Listener() {
                @Override
                public void onSignedIn(@NonNull AccountStore.Account account) {
                    Runnable pending = pendingAfterMicrosoftSignIn;
                    pendingAfterMicrosoftSignIn = null;
                    if (pending != null) {
                        pending.run();
                    }
                }

                @Override
                public void onError(@NonNull String message) {
                    pendingAfterMicrosoftSignIn = null;
                    Toast.makeText(InstanceDetailsActivity.this, message, Toast.LENGTH_LONG).show();
                }
            });
        } catch (Throwable throwable) {
            Logging.e(TAG, "Microsoft account gate initialization failed", throwable);
            accountStore = null;
            authManager = null;
        }
    }

    private void signInWithMicrosoftThen(@Nullable Runnable afterSignIn) {
        if (authManager == null) return;

        if (!MicrosoftAuthConfigPersonal.isConfigured()) {
            Toast.makeText(this, R.string.msg_configure_client_id, Toast.LENGTH_LONG).show();
            return;
        }

        pendingAfterMicrosoftSignIn = afterSignIn;
        authManager.signIn();
    }

    private boolean requireMicrosoftLoginHistoryBeforeLaunch(@NonNull Runnable afterSignIn) {
        return ModManagerTools.requireMicrosoftLoginHistoryBeforeLaunch(
                this,
                accountStore,
                () -> signInWithMicrosoftThen(afterSignIn)
        );
    }

    private boolean readExtras() {
        Intent intent = getIntent();
        instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID);
        instanceName = intent.getStringExtra(EXTRA_INSTANCE_NAME);
        loader = intent.getStringExtra(EXTRA_INSTANCE_LOADER);
        baseVersionId = intent.getStringExtra(EXTRA_BASE_VERSION_ID);
        minecraftVersionId = intent.getStringExtra(EXTRA_MINECRAFT_VERSION_ID);
        versionType = intent.getStringExtra(EXTRA_VERSION_TYPE);
        String rootPath = intent.getStringExtra(EXTRA_ROOT_DIRECTORY);
        String gamePath = intent.getStringExtra(EXTRA_GAME_DIRECTORY);
        String iconPath = intent.getStringExtra(EXTRA_ICON_FILE);
        isolated = intent.getBooleanExtra(EXTRA_ISOLATED, true);

        if (isBlank(instanceName) || isBlank(baseVersionId) || isBlank(gamePath)) {
            return false;
        }

        if (isBlank(instanceId)) instanceId = instanceName;
        if (isBlank(loader)) loader = "Vanilla";
        if (isBlank(minecraftVersionId)) minecraftVersionId = ModManagerVersionResolver.resolveGameVersionForContent(baseVersionId);
        if (isBlank(minecraftVersionId)) minecraftVersionId = baseVersionId;
        if (isBlank(versionType)) versionType = "release";
        rootDirectory = new File(isBlank(rootPath) ? gamePath : rootPath);
        gameDirectory = new File(gamePath);
        iconFile = isBlank(iconPath) ? null : new File(iconPath);
        resetContentDirectories();
        return true;
    }

    private void bindHeader() {
        binding.textInstanceName.setText(instanceName);
        binding.textInstanceMeta.setText(getString(
                R.string.instance_details_meta_value,
                displayLoader(loader),
                minecraftVersionId,
                displayVersionType(versionType)
        ));

        bindInstanceIcon();
    }

    private void bindInstanceIcon() {
        if (binding == null || binding.imageInstanceIcon == null) return;

        binding.imageInstanceIcon.setImageDrawable(null);

        if (iconFile != null && iconFile.isFile()) {
            try {
                binding.imageInstanceIcon.setImageURI(Uri.fromFile(iconFile));
                if (binding.imageInstanceIcon.getDrawable() != null) {
                    return;
                }
            } catch (Throwable throwable) {
                Logging.i(TAG, "Unable to load custom instance icon: " + readableError(throwable));
            }
        }

        binding.imageInstanceIcon.setImageResource(InstanceIconResolver.getDefaultIcon(
                loader,
                baseVersionId,
                minecraftVersionId,
                instanceName
        ));
    }

    private void setupActions() {
        binding.buttonPlay.setOnClickListener(view -> launchInstance());
        binding.buttonPlayServer.setOnClickListener(view -> showServerQuickPlayDialog());
        binding.buttonInstanceSettings.setOnClickListener(this::showInstanceSettingsMenu);
        binding.buttonBrowseContent.setOnClickListener(view -> browseSelectedContent());
        binding.buttonAddMods.setOnClickListener(view -> pickSelectedContent());
        binding.buttonCheckContentUpdates.setOnClickListener(view -> checkUpdatesForSelectedCategory());
        binding.buttonUpdateAllContent.setOnClickListener(view -> updateAllAvailableForSelectedCategory());
        binding.buttonContentFilter.setOnClickListener(view -> showContentFilterDialog());
        binding.buttonSelectAllContent.setOnClickListener(view -> toggleSelectAllVisibleContent());
        binding.buttonUpdateSelectedContent.setOnClickListener(view -> updateSelectedContentItems());
        binding.buttonEnableSelectedContent.setOnClickListener(view -> updateSelectedContentItemsEnabled(true));
        binding.buttonDisableSelectedContent.setOnClickListener(view -> updateSelectedContentItemsEnabled(false));
        binding.buttonDeleteSelectedContent.setOnClickListener(view -> showDeleteSelectedContentDialog());
        binding.buttonClearContentSelection.setOnClickListener(view -> clearContentSelection());
    }

    private void scheduleInitialContentRefresh() {
        if (binding == null) return;
        bindImportButtonForCategory(selectedCategory);
        updateContentFilterButtonUi();
        updateContentPagerUi();
        binding.textModsHint.setText("Preparing " + getCategoryPluralLabel(selectedCategory).toLowerCase(Locale.US) + "...");
        // Large CurseForge packs can contain hundreds of files. Starting the folder scan during
        // Activity creation competes with the first focus/layout pass and can trip Android's input
        // timeout. Let the details screen draw first, then scan the folder on the background worker.
        mainHandler.postDelayed(() -> {
            if (binding == null || isFinishing() || isDestroyed()) return;
            refreshContentList();
        }, 500L);
    }

    private void setupContentSearch() {
        if (binding.editTextContentSearch == null) return;

        binding.editTextContentSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                contentSearchQuery = s == null ? "" : s.toString();
                contentCurrentPage = 0;
                requestContentSearchFilter(true);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        binding.editTextContentSearch.setOnEditorActionListener((view, actionId, event) -> {
            boolean keyboardDone = actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_GO;
            boolean enterReleased = event != null
                    && event.getAction() == KeyEvent.ACTION_UP
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;

            if (keyboardDone || enterReleased) {
                finishContentSearchInput(view);
                return true;
            }
            return false;
        });
    }

    private void finishContentSearchInput(@NonNull View view) {
        view.clearFocus();
        hideKeyboardFromView(view);
    }

    private void hideKeyboardFromView(@NonNull View view) {
        try {
            InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null) {
                manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
        }
        mainHandler.postDelayed(this::enableFullscreen, 80L);
    }

    private boolean usesLegacyTexturePacks() {
        return ModManagerContentType.usesLegacyTexturePacks(getGameVersionIdForContent());
    }

    @NonNull
    private CharSequence getCategoryTabTitle(@NonNull ResourceCategory category) {
        if (category == ResourceCategory.RESOURCEPACKS && usesLegacyTexturePacks()) {
            return getString(R.string.instance_tab_texturepacks);
        }
        return getString(category.tabTitleRes);
    }

    @NonNull
    private String getCategoryPluralLabel(@NonNull ResourceCategory category) {
        if (category == ResourceCategory.RESOURCEPACKS && usesLegacyTexturePacks()) {
            return getString(R.string.instance_content_texturepacks_plural);
        }
        return getString(category.pluralLabelRes);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupContentTabs() {
        contentAdapter = new InstanceContentAdapter();
        screenshotAdapter = new ScreenshotAdapter();
        configureContentRecyclerForCategory(selectedCategory);
        binding.recyclerResourceItems.setNestedScrollingEnabled(true);
        binding.recyclerResourceItems.setItemViewCacheSize(0);
        binding.recyclerResourceItems.setOnTouchListener((view, event) -> {
            if (binding.editTextContentSearch.hasFocus()) {
                finishContentSearchInput(binding.editTextContentSearch);
            }
            return false;
        });
        constrainResourceRecyclerHeightIfNeeded();
        binding.buttonContentPagePrevious.setOnClickListener(view -> setContentPage(contentCurrentPage - 1));
        binding.buttonContentPageNext.setOnClickListener(view -> setContentPage(contentCurrentPage + 1));
        updateContentPagerUi();

        ResourceCategory[] categories = ResourceCategory.values();
        for (ResourceCategory category : categories) {
            binding.tabResourceCategories.addTab(
                    binding.tabResourceCategories.newTab().setText(getCategoryTabTitle(category))
            );
        }

        binding.tabResourceCategories.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int position = tab.getPosition();
                ResourceCategory[] categories = ResourceCategory.values();
                if (position >= 0 && position < categories.length) {
                    ResourceCategory nextCategory = categories[position];
                    if (selectedCategory != nextCategory) {
                        allContentItems.clear();
                        filteredContentItems.clear();
                        contentItems.clear();
                        contentPageGeneration++;
                    }
                    selectedCategory = nextCategory;
                    configureContentRecyclerForCategory(selectedCategory);
                    notifyActiveContentAdapter();
                    normalizeContentVisibilityFilterForSelectedCategory();
                    contentCurrentPage = 0;
                    clearContentSelection();
                    updateContentFilterButtonUi();
                    refreshContentList();
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                normalizeContentVisibilityFilterForSelectedCategory();
                contentCurrentPage = 0;
                clearContentSelection();
                updateContentFilterButtonUi();
                refreshContentList();
            }
        });
    }

    private void configureContentRecyclerForCategory(@NonNull ResourceCategory category) {
        if (binding == null || binding.recyclerResourceItems == null) return;

        RecyclerView recyclerView = binding.recyclerResourceItems;
        if (category == ResourceCategory.SCREENSHOTS) {
            GridLayoutManager gridLayoutManager = new GridLayoutManager(this, 2);
            gridLayoutManager.setInitialPrefetchItemCount(4);
            recyclerView.setLayoutManager(gridLayoutManager);
            recyclerView.setAdapter(screenshotAdapter);
            if (binding.editTextContentSearch != null) {
                binding.editTextContentSearch.setHint(R.string.instance_screenshots_search_hint);
            }
        } else {
            LinearLayoutManager contentLayoutManager = new LinearLayoutManager(this);
            contentLayoutManager.setInitialPrefetchItemCount(0);
            recyclerView.setLayoutManager(contentLayoutManager);
            recyclerView.setAdapter(contentAdapter);
            if (binding.editTextContentSearch != null) {
                binding.editTextContentSearch.setHint(R.string.instance_content_search_hint);
            }
        }

        updateCategoryActionVisibility(category);
    }

    private void updateCategoryActionVisibility(@NonNull ResourceCategory category) {
        if (binding == null) return;
        boolean screenshots = category == ResourceCategory.SCREENSHOTS;
        binding.buttonBrowseContent.setVisibility(screenshots ? View.GONE : View.VISIBLE);
        binding.buttonAddMods.setVisibility(screenshots ? View.GONE : View.VISIBLE);
        if (screenshots && binding.layoutContentSelection != null) {
            binding.layoutContentSelection.setVisibility(View.GONE);
        }
    }

    private void notifyActiveContentAdapter() {
        if (selectedCategory == ResourceCategory.SCREENSHOTS) {
            if (screenshotAdapter != null) screenshotAdapter.notifyDataSetChanged();
        } else if (contentAdapter != null) {
            contentAdapter.notifyDataSetChanged();
        }
    }

    private void constrainResourceRecyclerHeightIfNeeded() {
        if (binding == null || binding.recyclerResourceItems == null) return;

        ViewGroup.LayoutParams params = binding.recyclerResourceItems.getLayoutParams();
        if (params == null || params.height != ViewGroup.LayoutParams.WRAP_CONTENT) return;

        // If the RecyclerView is wrap_content inside a scrolling parent, Android may measure/bind
        // every mod row at once. Huge modpacks then freeze the transition and toggle refreshes.
        // Give the list a real viewport so RecyclerView can recycle rows normally.
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        params.height = Math.max(dp(180), screenHeight - dp(260));
        binding.recyclerResourceItems.setLayoutParams(params);
    }

    private void bindImportButtonForCategory(@NonNull ResourceCategory category) {
        if (binding == null || binding.buttonAddMods == null) return;

        if (category == ResourceCategory.SCREENSHOTS) {
            binding.buttonAddMods.setVisibility(View.GONE);
            binding.buttonAddMods.setEnabled(false);
            return;
        }

        binding.buttonAddMods.setVisibility(View.VISIBLE);
        binding.buttonAddMods.setText(category == ResourceCategory.RESOURCEPACKS && usesLegacyTexturePacks()
                ? R.string.button_upload_texturepacks
                : category.uploadButtonTextRes);
        if (category == ResourceCategory.WORLDS) {
            binding.buttonAddMods.setIconResource(R.drawable.ic_arrow_downward_24);
            binding.buttonAddMods.setContentDescription("Import World");
        } else {
            binding.buttonAddMods.setIcon(null);
            binding.buttonAddMods.setContentDescription(null);
        }
    }

    private void showContentFilterDialog() {
        if (binding == null) return;

        if (!selectedCategory.supportsDisableToggle) {
            contentVisibilityFilter = ContentVisibilityFilter.ALL;
            updateContentFilterButtonUi();
            Toast.makeText(this, R.string.instance_content_filter_not_available, Toast.LENGTH_SHORT).show();
            return;
        }

        finishContentSearchInput(binding.editTextContentSearch);

        String[] filterOptions = {
                getString(R.string.instance_content_filter_all),
                getString(R.string.instance_content_filter_enabled),
                getString(R.string.instance_content_filter_disabled)
        };

        int checkedItem;
        switch (contentVisibilityFilter) {
            case ENABLED_ONLY:
                checkedItem = 1;
                break;
            case DISABLED_ONLY:
                checkedItem = 2;
                break;
            case ALL:
            default:
                checkedItem = 0;
                break;
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.instance_content_filter_title)
                .setSingleChoiceItems(filterOptions, checkedItem, (dialogInterface, which) -> {
                    ContentVisibilityFilter selectedFilter;
                    if (which == 1) {
                        selectedFilter = ContentVisibilityFilter.ENABLED_ONLY;
                    } else if (which == 2) {
                        selectedFilter = ContentVisibilityFilter.DISABLED_ONLY;
                    } else {
                        selectedFilter = ContentVisibilityFilter.ALL;
                    }
                    if (contentVisibilityFilter != selectedFilter) {
                        contentVisibilityFilter = selectedFilter;
                        contentCurrentPage = 0;
                        clearContentSelection();
                        updateContentFilterButtonUi();
                        requestContentSearchFilter(true);
                    }
                    dialogInterface.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnDismissListener(unused -> enableFullscreen());
        dialog.show();
    }

    private void normalizeContentVisibilityFilterForSelectedCategory() {
        if (!selectedCategory.supportsDisableToggle) {
            contentVisibilityFilter = ContentVisibilityFilter.ALL;
        }
    }

    private void updateContentFilterButtonUi() {
        if (binding == null || binding.buttonContentFilter == null) return;

        normalizeContentVisibilityFilterForSelectedCategory();
        boolean filterAvailable = selectedCategory.supportsDisableToggle;
        binding.buttonContentFilter.setVisibility(filterAvailable ? View.VISIBLE : View.GONE);
        binding.buttonContentFilter.setEnabled(filterAvailable && !contentOperationRunning && !contentRefreshRunning);
        int filterLabelRes;
        int filterDescriptionRes;
        switch (contentVisibilityFilter) {
            case ENABLED_ONLY:
                filterLabelRes = R.string.instance_content_filter_enabled_short;
                filterDescriptionRes = R.string.instance_content_filter_enabled_description;
                break;
            case DISABLED_ONLY:
                filterLabelRes = R.string.instance_content_filter_disabled_short;
                filterDescriptionRes = R.string.instance_content_filter_disabled_description;
                break;
            case ALL:
            default:
                filterLabelRes = R.string.instance_content_filter_all_short;
                filterDescriptionRes = R.string.instance_content_filter_all_description;
                break;
        }
        binding.buttonContentFilter.setText(filterLabelRes);
        binding.buttonContentFilter.setContentDescription(getString(filterDescriptionRes));
    }

    private void showInstanceSettingsMenu(@NonNull View anchor) {
        enableFullscreen();
        LauncherDialogStyle.syncTheme(this);

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        View decorView = getWindow().getDecorView();
        if (decorView.getWidth() > 0) screenWidth = decorView.getWidth();
        if (decorView.getHeight() > 0) screenHeight = decorView.getHeight();

        int dialogHeight = Math.min(Math.max(dp(320), screenHeight - dp(48)), dp(720));
        int dialogWidth = Math.min(Math.max(dp(300), screenWidth - dp(32)), dp(560));

        FrameLayout dialogFrame = new FrameLayout(this);
        dialogFrame.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);
        dialogFrame.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dialogHeight
        ));

        LinearLayout dialogRoot = new LinearLayout(this);
        dialogRoot.setOrientation(LinearLayout.VERTICAL);
        dialogRoot.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);
        dialogFrame.addView(dialogRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dialogHeight
        ));

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setClipToPadding(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setHorizontalScrollBarEnabled(false);
        scrollView.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);
        int padding = dp(18);
        root.setPadding(padding, padding, padding, dp(4));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("Instance Settings");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        title.setPadding(dp(2), 0, dp(2), dp(6));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView info = new TextView(this);
        info.setText("Choose an action for this instance. Crash sharing uses the newest file from the instance game/crash-reports folder.");
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        info.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        info.setPadding(dp(2), 0, dp(2), dp(12));
        info.setSingleLine(false);
        root.addView(info, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout menuLayout = addModpackDialogCard(root);
        final AlertDialog[] dialogRef = new AlertDialog[1];

        addInstanceSettingsMenuRow(menuLayout, R.string.instance_settings_view_folder, dialogRef, MENU_VIEW_FOLDER);
        if (findLatestInstanceCrashOrLogFile() != null) {
            addInstanceSettingsMenuRow(menuLayout, "Share Crash Log", dialogRef, MENU_SHARE_CRASH_LOG);
        }
        addInstanceSettingsMenuRow(menuLayout, R.string.button_delete_instance, dialogRef, MENU_DELETE_INSTANCE);
        addInstanceSettingsMenuRow(menuLayout, R.string.instance_settings_edit_name, dialogRef, MENU_EDIT_INSTANCE_NAME);
        addInstanceSettingsMenuRow(menuLayout, R.string.instance_settings_edit_icon, dialogRef, MENU_EDIT_INSTANCE_ICON);
        addInstanceSettingsMenuRow(menuLayout, "Per Instance Settings", dialogRef, MENU_PER_INSTANCE_SETTINGS);
        addInstanceSettingsMenuRow(menuLayout, "Update Version", dialogRef, MENU_UPDATE_VERSION);
        if (ModpackUpdateManager.readInstalledModpackInfo(rootDirectory, gameDirectory) != null) {
            addInstanceSettingsMenuRow(menuLayout, "Update Modpack", dialogRef, MENU_UPDATE_MODPACK);
        }
        addInstanceSettingsMenuRow(menuLayout, "Repair Instance", dialogRef, MENU_REPAIR_INSTANCE);
        if (getSupportedLoaderKind() != null) {
            addInstanceSettingsMenuRow(menuLayout, "Update Loader", dialogRef, MENU_UPDATE_LOADER);
        }
        LauncherInstance cleanroomCandidate = findCurrentInstance();
        if (CleanroomMigrationManager.isEligible(cleanroomCandidate)) {
            addInstanceSettingsMenuRow(menuLayout, "Migrate to Cleanroom (Java 25)", dialogRef, MENU_MIGRATE_CLEANROOM);
        } else if (CleanroomCompatibilityManager.isRepairEligible(cleanroomCandidate)) {
            addInstanceSettingsMenuRow(menuLayout, "Repair Cleanroom Modpack Compatibility", dialogRef, MENU_REPAIR_CLEANROOM);
        }
        addInstanceSettingsMenuRow(menuLayout, R.string.instance_settings_export_modpack, dialogRef, MENU_EXPORT_MODPACK);
        addInstanceSettingsMenuRow(menuLayout, "Import Modpack", dialogRef, MENU_IMPORT_MODPACK);

        dialogRoot.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        footer.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);
        footer.setPadding(dp(18), dp(4), dp(18), dp(12));

        TextView cancelButton = createModpackFooterButton("Cancel", false);
        cancelButton.setOnClickListener(view -> {
            AlertDialog dialog = dialogRef[0];
            if (dialog != null) dialog.dismiss();
        });
        footer.addView(cancelButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        dialogRoot.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(dialogFrame)
                .create();
        dialogRef[0] = dialog;
        showStyledModpackDialog(dialog);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(dialogWidth, dialogHeight);
        }
    }

    private void addInstanceSettingsMenuRow(
            @NonNull LinearLayout parent,
            @StringRes int textRes,
            @NonNull AlertDialog[] dialogRef,
            int itemId
    ) {
        addInstanceSettingsMenuRow(parent, getString(textRes), dialogRef, itemId);
    }

    private void addInstanceSettingsMenuRow(
            @NonNull LinearLayout parent,
            @NonNull String text,
            @NonNull AlertDialog[] dialogRef,
            int itemId
    ) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinHeight(dp(52));
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        row.setTypeface(row.getTypeface(), android.graphics.Typeface.BOLD);
        row.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        row.setPadding(dp(14), 0, dp(14), 0);
        row.setSingleLine(true);
        row.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.setClickable(true);
        row.setFocusable(true);

        GradientDrawable rowBackground = new GradientDrawable();
        rowBackground.setColor(LauncherDialogStyle.COLOR_CARD_BG_PRESSED);
        rowBackground.setStroke(dp(1), LauncherDialogStyle.COLOR_CARD_STROKE);
        rowBackground.setCornerRadius(dp(14));
        row.setBackground(rowBackground);

        row.setOnClickListener(view -> {
            AlertDialog dialog = dialogRef[0];
            if (dialog != null) dialog.dismiss();
            handleInstanceSettingsMenuItem(itemId);
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
        );
        params.setMargins(0, 0, 0, dp(8));
        parent.addView(row, params);
    }

    private void handleInstanceSettingsMenuItem(int itemId) {
        enableFullscreen();
        if (itemId == MENU_SHARE_CRASH_LOG) {
            shareLatestInstanceCrashLog();
            return;
        }
        if (itemId == MENU_VIEW_FOLDER) {
            showFolderLocation();
            return;
        }
        if (itemId == MENU_DELETE_INSTANCE) {
            showDeleteInstanceDialog();
            return;
        }
        if (itemId == MENU_EDIT_INSTANCE_NAME) {
            showEditInstanceNameDialog();
            return;
        }
        if (itemId == MENU_EDIT_INSTANCE_ICON) {
            pickInstanceIcon();
            return;
        }
        if (itemId == MENU_UPDATE_VERSION) {
            showUpdateVersionDialog();
            return;
        }
        if (itemId == MENU_UPDATE_MODPACK) {
            showUpdateModpackDialog();
            return;
        }
        if (itemId == MENU_UPDATE_LOADER) {
            showUpdateLoaderDialog();
            return;
        }
        if (itemId == MENU_EXPORT_MODPACK) {
            showExportModpackPlatformDialog();
            return;
        }
        if (itemId == MENU_IMPORT_MODPACK) {
            openModpackImportPicker();
            return;
        }
        if (itemId == MENU_REPAIR_INSTANCE) {
            showRepairInstanceDialog();
            return;
        }
        if (itemId == MENU_PER_INSTANCE_SETTINGS) {
            showPerInstanceSettingsDialog();
            return;
        }
        if (itemId == MENU_MIGRATE_CLEANROOM) {
            showCleanroomMigrationDialog();
            return;
        }
        if (itemId == MENU_REPAIR_CLEANROOM) {
            showCleanroomCompatibilityRepairDialog();
        }
    }

    @Nullable
    private LauncherInstance findCurrentInstance() {
        try {
            LauncherInstance instance = LauncherInstanceManager.findByNameOrId(this, instanceId);
            if (instance == null && !isBlank(instanceName)) {
                instance = LauncherInstanceManager.findByNameOrId(this, instanceName);
            }
            return instance;
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to resolve current instance for Cleanroom migration: " + readableError(throwable));
            return null;
        }
    }

    private void showCleanroomMigrationDialog() {
        LauncherInstance instance = findCurrentInstance();
        CleanroomMigrationDialog.showForInstance(this, instance, (updated, message) -> {
            applyUpdatedInstance(updated);
            setResult(RESULT_OK);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void showCleanroomCompatibilityRepairDialog() {
        LauncherInstance instance = findCurrentInstance();
        CleanroomMigrationDialog.showRepairForInstance(this, instance, (updated, message) -> {
            applyUpdatedInstance(updated);
            setResult(RESULT_OK);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void showExportModpackPlatformDialog() {
        enableFullscreen();

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setClipToPadding(false);
        scrollView.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);
        scrollView.setPadding(0, 0, 0, dp(4));

        LinearLayout root = createModpackDialogRoot(
                scrollView,
                "Export Modpack",
                "Choose the export format. DroidBridge will keep the required manifest and Minecraft loader metadata locked, then let you pick the optional folders on the next screen."
        );

        LinearLayout noteCard = addModpackDialogCard(root);
        addModpackCardTitle(noteCard, "Sharing note");
        addModpackCardText(
                noteCard,
                "Only upload a pack publicly when every included mod, resource pack, shader, config, and file is allowed on the platform you choose.",
                LauncherDialogStyle.COLOR_TEXT_SECONDARY,
                13,
                false
        );

        LinearLayout formatCard = addModpackDialogCard(root);
        addModpackCardTitle(formatCard, "Export format");
        addModpackCardText(
                formatCard,
                "Pick the format that matches where this pack will be imported or published.",
                LauncherDialogStyle.COLOR_TEXT_SECONDARY,
                13,
                false
        );

        AlertDialog[] dialogRef = new AlertDialog[1];
        addExportPlatformRow(
                formatCard,
                "Modrinth",
                ".mrpack export · best for Modrinth publishing",
                () -> {
                    AlertDialog dialog = dialogRef[0];
                    if (dialog != null) dialog.dismiss();
                    startModpackExport(ModpackExportManager.Platform.MODRINTH);
                }
        );
        addExportPlatformRow(
                formatCard,
                "CurseForge",
                ".zip export · best for CurseForge publishing",
                () -> {
                    AlertDialog dialog = dialogRef[0];
                    if (dialog != null) dialog.dismiss();
                    startModpackExport(ModpackExportManager.Platform.CURSEFORGE);
                }
        );
        addExportPlatformRow(
                formatCard,
                "MultiMC / Prism",
                ".zip instance export · bundles .minecraft files directly",
                () -> {
                    AlertDialog dialog = dialogRef[0];
                    if (dialog != null) dialog.dismiss();
                    startModpackExport(ModpackExportManager.Platform.MULTIMC);
                }
        );

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialogRef[0] = dialog;
        showStyledModpackDialog(dialog);

        Window window = dialog.getWindow();
        if (window != null) {
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            window.setLayout(Math.min(screenWidth - dp(36), dp(760)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void addExportPlatformRow(
            @NonNull LinearLayout parent,
            @NonNull String title,
            @NonNull String subtitle,
            @NonNull Runnable clickAction
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(62));
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        row.setBackground(createExportPlatformRowBackground());
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(view -> clickAction.run());

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleView.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        titleView.setTypeface(titleView.getTypeface(), android.graphics.Typeface.BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView subtitleView = new TextView(this);
        subtitleView.setText(subtitle);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitleView.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        subtitleParams.topMargin = dp(2);
        row.addView(subtitleView, subtitleParams);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rowParams.topMargin = dp(8);
        parent.addView(row, rowParams);
    }

    @NonNull
    private GradientDrawable createExportPlatformRowBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(LauncherDialogStyle.COLOR_CARD_BG_PRESSED);
        background.setStroke(dp(1), LauncherDialogStyle.COLOR_CARD_STROKE);
        background.setCornerRadius(dp(14));
        return background;
    }

    private void startModpackExport(@NonNull ModpackExportManager.Platform platform) {
        ModpackExportOptionsDialog.show(
                this,
                gameDirectory,
                iconFile,
                platform,
                options -> beginModpackExport(platform, options)
        );
    }

    private void beginModpackExport(
            @NonNull ModpackExportManager.Platform platform,
            @NonNull ModpackExportManager.ExportOptions exportOptions
    ) {
        pendingExportPlatform = platform;
        pendingExportOptions = exportOptions;

        String extension = platform == ModpackExportManager.Platform.MODRINTH ? ".mrpack" : ".zip";
        String mimeType = platform == ModpackExportManager.Platform.MODRINTH
                ? "application/x-modrinth-modpack+zip"
                : "application/zip";

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_TITLE, sanitizeImportedFileName(instanceName, null) + extension);

        try {
            startActivityForResult(intent, REQUEST_EXPORT_MODPACK);
        } catch (ActivityNotFoundException throwable) {
            pendingExportPlatform = null;
            pendingExportOptions = null;
            Toast.makeText(this, "No file picker is available for exporting.", Toast.LENGTH_LONG).show();
        }
    }

    private void exportModpackToUri(
            @NonNull Uri uri,
            @NonNull ModpackExportManager.Platform platform,
            @NonNull ModpackExportManager.ExportOptions exportOptions
    ) {
        showUpdateProgressDialog("Export Modpack", "Preparing export...", false, 100, false);
        Thread thread = new Thread(() -> ModpackExportManager.exportToUri(
                this,
                gameDirectory,
                instanceName,
                getGameVersionIdForContent(),
                loader,
                baseVersionId,
                iconFile,
                platform,
                uri,
                exportOptions,
                new ModpackExportManager.Listener() {
                    @Override
                    public void onStatus(@NonNull String message) {
                        runOnUiThread(() -> setUpdateProgressMessage(message));
                    }

                    @Override
                    public void onProgress(int current, int total) {
                        runOnUiThread(() -> setUpdateProgress(current, total));
                    }

                    @Override
                    public void onComplete(@NonNull String message) {
                        runOnUiThread(() -> {
                            dismissUpdateProgressDialog();
                            Toast.makeText(InstanceDetailsActivity.this, message, Toast.LENGTH_LONG).show();
                        });
                    }

                    @Override
                    public void onError(@NonNull Throwable throwable) {
                        runOnUiThread(() -> {
                            dismissUpdateProgressDialog();
                            String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
                            Toast.makeText(InstanceDetailsActivity.this, "Export failed: " + message, Toast.LENGTH_LONG).show();
                        });
                    }
                }
        ), "Export Modpack");
        thread.start();
    }

    private void openModpackImportPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip",
                "application/x-zip-compressed",
                "application/x-modrinth-modpack+zip",
                "application/octet-stream"
        });
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivityForResult(Intent.createChooser(intent, "Import Modpack (.mrpack, CurseForge .zip, MultiMC/Prism .zip)"), REQUEST_IMPORT_MODPACK);
        } catch (ActivityNotFoundException throwable) {
            Toast.makeText(this, "No file picker is available.", Toast.LENGTH_LONG).show();
        }
    }

    private void importModpackFromUri(@NonNull Uri uri) {
        showUpdateProgressDialog("Import Modpack", "Preparing import...", false, 100, false);
        Thread thread = new Thread(() -> ModpackInstallManager.importFromUri(
                this,
                uri,
                new ModpackInstallManager.Listener() {
                    @Override
                    public void onStatus(@NonNull String message) {
                        runOnUiThread(() -> setUpdateProgressMessage(message));
                    }

                    @Override
                    public void onProgress(int current, int total) {
                        runOnUiThread(() -> setUpdateProgress(current, total));
                    }

                    @Override
                    public void onComplete(@NonNull String message) {
                        onComplete(message, null);
                    }

                    @Override
                    public void onComplete(@NonNull String message, @Nullable LauncherInstance importedInstance) {
                        runOnUiThread(() -> {
                            dismissUpdateProgressDialog();
                            setResult(RESULT_OK);
                            if (importedInstance == null) {
                                Toast.makeText(InstanceDetailsActivity.this, message, Toast.LENGTH_LONG).show();
                                return;
                            }
                            CleanroomMigrationDialog.offerAfterModpackInstall(
                                    InstanceDetailsActivity.this,
                                    message,
                                    importedInstance,
                                    (finalInstance, finalMessage) -> {
                                        Toast.makeText(InstanceDetailsActivity.this, finalMessage, Toast.LENGTH_LONG).show();
                                        startActivity(InstanceDetailsActivity.createIntent(InstanceDetailsActivity.this, finalInstance));
                                    }
                            );
                        });
                    }

                    @Override
                    public void onError(@NonNull Throwable throwable) {
                        runOnUiThread(() -> {
                            dismissUpdateProgressDialog();
                            String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
                            Toast.makeText(InstanceDetailsActivity.this, "Import failed: " + message, Toast.LENGTH_LONG).show();
                        });
                    }
                }
        ), "Import Modpack");
        thread.start();
    }

    private int getDropdownBottomSafePadding() {
        int navigationBarHeight = 0;
        int resourceId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (resourceId > 0) {
            navigationBarHeight = getResources().getDimensionPixelSize(resourceId);
        }
        return Math.max(dp(16), navigationBarHeight);
    }

    private int resolveThemeColor(int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (!getTheme().resolveAttribute(attr, value, true)) {
            return fallback;
        }
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


    @Nullable
    private InstanceVersionUpdater.LoaderKind getSupportedLoaderKind() {
        InstanceVersionUpdater.LoaderKind kind = InstanceVersionUpdater.resolveLoaderKind(loader);
        if (kind == InstanceVersionUpdater.LoaderKind.FABRIC
                || kind == InstanceVersionUpdater.LoaderKind.FORGE
                || kind == InstanceVersionUpdater.LoaderKind.NEOFORGE) {
            return kind;
        }
        return null;
    }

    private void showUpdateVersionDialog() {
        showUpdateProgressDialog("Loading Minecraft versions", "Fetching release versions...", true, 1, true);
        Thread thread = new Thread(() -> {
            try {
                ArrayList<InstanceVersionUpdater.MinecraftRelease> releases = InstanceVersionUpdater.fetchMinecraftReleases();
                runOnUiThread(() -> {
                    dismissUpdateProgressDialog();
                    showMinecraftReleaseSelector(releases);
                });
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to load Minecraft release versions", throwable);
                runOnUiThread(() -> {
                    dismissUpdateProgressDialog();
                    Toast.makeText(this, "Unable to load versions: " + readableError(throwable), Toast.LENGTH_LONG).show();
                });
            }
        }, "Load Minecraft Releases");
        thread.start();
    }

    private void showMinecraftReleaseSelector(@NonNull ArrayList<InstanceVersionUpdater.MinecraftRelease> releases) {
        if (releases.isEmpty()) {
            Toast.makeText(this, "No release versions found.", Toast.LENGTH_LONG).show();
            return;
        }

        String[] labels = new String[releases.size()];
        String currentMc = getGameVersionIdForContent();
        for (int i = 0; i < releases.size(); i++) {
            String id = releases.get(i).id;
            labels[i] = id.equals(currentMc) ? id + " (current)" : id;
        }

        showVersionSelectionDialog(
                "Update Version",
                "Pick the Minecraft release to update this instance to. Snapshots are hidden.",
                labels,
                (dialog, which) -> confirmUpdateVersion(releases.get(which).id)
        );
    }

    private void showVersionSelectionDialog(
            @NonNull String title,
            @NonNull String message,
            @NonNull String[] labels,
            @NonNull DialogInterface.OnClickListener itemClickListener
    ) {
        enableFullscreen();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int horizontalPadding = dp(24);
        layout.setPadding(horizontalPadding, dp(4), horizontalPadding, 0);

        TextView messageView = new TextView(this);
        messageView.setText(message);
        messageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        messageView.setTextColor(resolveThemeColor(android.R.attr.textColorPrimary, Color.WHITE));
        layout.addView(messageView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        ListView listView = new ListView(this);
        listView.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
        listView.setChoiceMode(ListView.CHOICE_MODE_NONE);
        listView.setDividerHeight(0);
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(8), 0, getDropdownBottomSafePadding());

        int visibleRows = Math.min(Math.max(labels.length, 1), 8);
        int listHeight = Math.min(dp(420), visibleRows * dp(52) + dp(8) + getDropdownBottomSafePadding());
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                listHeight
        );
        listParams.topMargin = dp(8);
        layout.addView(listView, listParams);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(layout)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        listView.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            itemClickListener.onClick(dialog, position);
        });

        showFullscreenSafeDialog(dialog);
    }

    private void confirmUpdateVersion(@NonNull String targetMinecraftVersion) {
        InstanceVersionUpdater.LoaderKind kind = InstanceVersionUpdater.resolveLoaderKind(loader);
        String message;
        if (kind == InstanceVersionUpdater.LoaderKind.FABRIC
                || kind == InstanceVersionUpdater.LoaderKind.FORGE
                || kind == InstanceVersionUpdater.LoaderKind.NEOFORGE) {
            message = "Update this instance to Minecraft " + targetMinecraftVersion + "?\n\n"
                    + kind.displayName + " will also be updated to the latest loader available for that Minecraft version.";
        } else {
            message = "Update this vanilla instance to Minecraft " + targetMinecraftVersion + "?";
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("Update Version")
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Update", (dialog, which) -> runVersionUpdate(targetMinecraftVersion))
                .show();
    }

    private void runVersionUpdate(@NonNull String targetMinecraftVersion) {
        if (rootDirectory == null || gameDirectory == null) {
            Toast.makeText(this, "Missing instance folder.", Toast.LENGTH_LONG).show();
            return;
        }

        setVersionUpdateInProgress(true);
        showUpdateProgressDialog("Update Version", "Preparing update...", false, 4, false);
        Thread thread = new Thread(() -> {
            try {
                InstanceVersionUpdater.UpdateResult result = InstanceVersionUpdater.updateInstanceVersion(
                        this,
                        rootDirectory,
                        gameDirectory,
                        instanceName,
                        loader,
                        targetMinecraftVersion,
                        new InstanceVersionUpdater.Listener() {
                            @Override
                            public void onStatus(@NonNull String message) {
                                runOnUiThread(() -> setUpdateProgressMessage(message));
                            }

                            @Override
                            public void onProgress(int current, int total) {
                                runOnUiThread(() -> setUpdateProgress(current, total));
                            }
                        }
                );
                runOnUiThread(() -> applyVersionUpdateResult(result, "Version updated"));
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to update instance version", throwable);
                runOnUiThread(() -> {
                    dismissUpdateProgressDialog();
                    setVersionUpdateInProgress(false);
                    Toast.makeText(this, "Version update failed: " + readableError(throwable), Toast.LENGTH_LONG).show();
                });
            }
        }, "Update Instance Version");
        thread.start();
    }

    private void showUpdateModpackDialog() {
        ModpackUpdateManager.InstalledModpackInfo installed = ModpackUpdateManager.readInstalledModpackInfo(rootDirectory, gameDirectory);
        if (installed == null) {
            Toast.makeText(this, "This instance does not have modpack update metadata.", Toast.LENGTH_LONG).show();
            return;
        }

        showUpdateProgressDialog("Update Modpack", "Finding matching Modrinth and CurseForge projects...", true, 2, true);
        Thread thread = new Thread(() -> {
            try {
                ArrayList<ModpackUpdateManager.ProjectMatch> matches = ModpackUpdateManager.findMatchingProjects(
                        this,
                        installed,
                        new ModpackUpdateManager.Listener() {
                            @Override
                            public void onStatus(@NonNull String message) {
                                runOnUiThread(() -> setUpdateProgressMessage(message));
                            }

                            @Override
                            public void onProgress(int current, int total) {
                                runOnUiThread(() -> setUpdateProgress(current, total));
                            }
                        }
                );
                runOnUiThread(() -> {
                    dismissUpdateProgressDialog();
                    if (matches.isEmpty()) {
                        Toast.makeText(this, "No matching Modrinth or CurseForge modpack was found for " + installed.displayTitle + ".", Toast.LENGTH_LONG).show();
                        return;
                    }
                    showModpackProjectChooser(installed, matches);
                });
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to find matching modpack projects", throwable);
                runOnUiThread(() -> {
                    dismissUpdateProgressDialog();
                    Toast.makeText(this, "Modpack lookup failed: " + readableError(throwable), Toast.LENGTH_LONG).show();
                });
            }
        }, "Find Modpack Projects");
        thread.start();
    }

    private void showModpackProjectChooser(
            @NonNull ModpackUpdateManager.InstalledModpackInfo installed,
            @NonNull ArrayList<ModpackUpdateManager.ProjectMatch> projects
    ) {
        enableFullscreen();

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);

        LinearLayout root = createModpackDialogRoot(
                scrollView,
                "Update Modpack",
                "Choose which platform/project to update from. DroidBridge searches both Modrinth and CurseForge for the installed pack so you can switch platform when the same pack exists on both."
        );

        LinearLayout installedCard = addModpackDialogCard(root);
        addModpackCardTitle(installedCard, "Installed pack");
        addModpackCardText(installedCard, installed.displayTitle, LauncherDialogStyle.COLOR_TEXT_SECONDARY, 14, false);
        addModpackCardText(installedCard,
                "Current source: " + installed.platform.displayName + " • Current version: " + installed.currentVersionLabel,
                LauncherDialogStyle.COLOR_TEXT_MUTED,
                12,
                false
        );

        final AlertDialog[] dialogRef = new AlertDialog[1];
        for (ModpackUpdateManager.ProjectMatch project : projects) {
            String subtitle = project.platform.displayName
                    + (project.exactInstalledProject ? " • installed project" : " • matched by name")
                    + (isBlank(project.summary) ? "" : "\n" + project.summary);
            addModpackActionCard(root, project.title, subtitle, () -> {
                AlertDialog dialog = dialogRef[0];
                if (dialog != null) dialog.dismiss();
                openModpackUpdateDetails(installed, project);
            });
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialogRef[0] = dialog;
        showStyledModpackDialog(dialog);
    }

    private void openModpackUpdateDetails(
            @NonNull ModpackUpdateManager.InstalledModpackInfo installed,
            @NonNull ModpackUpdateManager.ProjectMatch project
    ) {
        Intent intent = ModpackUpdateDetailsActivity.createIntent(
                this,
                instanceId,
                instanceName,
                loader,
                baseVersionId,
                getGameVersionIdForContent(),
                versionType,
                rootDirectory,
                gameDirectory,
                iconFile,
                isolated,
                project.platform == ModpackUpdateManager.Platform.CURSEFORGE
                        ? ModpackUpdateDetailsActivity.PLATFORM_CURSEFORGE
                        : ModpackUpdateDetailsActivity.PLATFORM_MODRINTH,
                project.projectId,
                project.slug,
                project.title,
                project.summary,
                installed.currentVersionLabel
        );

        try {
            startActivityForResult(intent, REQUEST_UPDATE_MODPACK);
        } catch (ActivityNotFoundException throwable) {
            Toast.makeText(this, "Modpack update details screen is not available.", Toast.LENGTH_LONG).show();
        }
    }

    private void loadModpackVersions(
            @NonNull ModpackUpdateManager.InstalledModpackInfo installed,
            @NonNull ModpackUpdateManager.ProjectMatch project
    ) {
        showUpdateProgressDialog("Update Modpack", "Loading " + project.platform.displayName + " versions...", true, 1, true);
        Thread thread = new Thread(() -> {
            try {
                ArrayList<ModpackUpdateManager.VersionInfo> versions = ModpackUpdateManager.loadVersions(
                        project,
                        new ModpackUpdateManager.Listener() {
                            @Override
                            public void onStatus(@NonNull String message) {
                                runOnUiThread(() -> setUpdateProgressMessage(message));
                            }

                            @Override
                            public void onProgress(int current, int total) {
                                runOnUiThread(() -> setUpdateProgress(current, total));
                            }
                        }
                );
                runOnUiThread(() -> {
                    dismissUpdateProgressDialog();
                    if (versions.isEmpty()) {
                        Toast.makeText(this, "No versions were returned for " + project.title + ".", Toast.LENGTH_LONG).show();
                        return;
                    }
                    showModpackVersionChooser(installed, project, versions);
                });
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to load modpack versions", throwable);
                runOnUiThread(() -> {
                    dismissUpdateProgressDialog();
                    Toast.makeText(this, "Unable to load modpack versions: " + readableError(throwable), Toast.LENGTH_LONG).show();
                });
            }
        }, "Load Modpack Versions");
        thread.start();
    }

    private void showModpackVersionChooser(
            @NonNull ModpackUpdateManager.InstalledModpackInfo installed,
            @NonNull ModpackUpdateManager.ProjectMatch project,
            @NonNull ArrayList<ModpackUpdateManager.VersionInfo> versions
    ) {
        enableFullscreen();

        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int availableHeight = Math.max(dp(360), screenHeight - dp(96));
        int dialogHeight = Math.min(availableHeight, dp(760));
        int dialogWidth = Math.max(dp(340), Math.min(screenWidth - dp(72), dp(860)));

        FrameLayout dialogFrame = new FrameLayout(this);
        dialogFrame.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);
        dialogFrame.setLayoutParams(new ViewGroup.LayoutParams(dialogWidth, dialogHeight));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);
        int padding = dp(18);
        root.setPadding(padding, padding, padding, dp(12));
        dialogFrame.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dialogHeight
        ));

        TextView title = new TextView(this);
        title.setText(project.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        title.setSingleLine(false);
        title.setPadding(dp(2), 0, dp(2), dp(6));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView info = new TextView(this);
        info.setText("Pick the latest version or any specific version of this modpack. The list is not locked to the current Minecraft version, so a 1.16.5 pack can migrate to a 1.20.1 pack when the project provides one.");
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        info.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        info.setPadding(dp(2), 0, dp(2), dp(12));
        info.setSingleLine(false);
        root.addView(info, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout selectedCard = addModpackDialogCard(root);
        addModpackCardTitle(selectedCard, "Selected version");
        TextView selectedText = new TextView(this);
        selectedText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        selectedText.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        selectedText.setPadding(0, 0, 0, dp(4));
        selectedText.setSingleLine(false);
        selectedCard.addView(selectedText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout versionsCard = createModpackDialogCard();
        addModpackCardTitle(versionsCard, "Available versions");
        addModpackCardText(versionsCard, "Tap a version, then press Install selected. The first row is the latest version returned by the platform.", LauncherDialogStyle.COLOR_TEXT_MUTED, 12, false);

        final int[] selectedIndex = new int[]{0};
        updateSelectedModpackVersionText(selectedText, versions.get(0));

        ScrollView versionScroll = new ScrollView(this);
        versionScroll.setFillViewport(false);
        versionScroll.setClipToPadding(false);
        versionScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        versionScroll.setPadding(0, dp(6), 0, dp(8));
        versionScroll.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout versionRows = new LinearLayout(this);
        versionRows.setOrientation(LinearLayout.VERTICAL);
        versionRows.setPadding(0, 0, 0, dp(6));
        versionScroll.addView(versionRows, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        ArrayList<View> rowViews = new ArrayList<>();
        for (int i = 0; i < versions.size(); i++) {
            final int index = i;
            View row = addModpackVersionRow(versionRows, versions.get(i), i == 0, () -> {
                selectedIndex[0] = index;
                updateSelectedModpackVersionText(selectedText, versions.get(index));
                updateModpackVersionRowSelection(rowViews, selectedIndex[0]);
            });
            rowViews.add(row);
        }
        updateModpackVersionRowSelection(rowViews, selectedIndex[0]);

        versionsCard.addView(versionScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        LinearLayout.LayoutParams versionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        versionsParams.setMargins(0, 0, 0, dp(10));
        root.addView(versionsCard, versionsParams);

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        footer.setPadding(0, dp(2), 0, 0);

        final AlertDialog[] dialogRef = new AlertDialog[1];
        TextView cancelButton = createModpackFooterButton("Cancel", false);
        cancelButton.setOnClickListener(view -> {
            AlertDialog dialog = dialogRef[0];
            if (dialog != null) dialog.dismiss();
        });

        TextView installButton = createModpackFooterButton("Install selected", true);
        installButton.setOnClickListener(view -> {
            int index = Math.max(0, Math.min(selectedIndex[0], versions.size() - 1));
            ModpackUpdateManager.VersionInfo selected = versions.get(index);
            AlertDialog dialog = dialogRef[0];
            if (dialog != null) dialog.dismiss();
            showModpackBackupWarning(installed, project, selected);
        });

        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cancelParams.rightMargin = dp(8);
        footer.addView(cancelButton, cancelParams);
        footer.addView(installButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        root.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(dialogFrame)
                .create();
        dialogRef[0] = dialog;
        showStyledModpackDialog(dialog);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    @NonNull
    private View addModpackVersionRow(
            @NonNull LinearLayout parent,
            @NonNull ModpackUpdateManager.VersionInfo version,
            boolean latest,
            @NonNull Runnable clickAction
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(76));
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(view -> clickAction.run());

        TextView name = new TextView(this);
        name.setText((latest ? "Latest available: " : "") + version.versionLabel);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        name.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        name.setSingleLine(false);
        row.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView meta = new TextView(this);
        meta.setText("Minecraft " + version.getMinecraftVersionsLabel() + " • " + version.getLoadersLabel());
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        meta.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        meta.setSingleLine(false);
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        metaParams.topMargin = dp(2);
        row.addView(meta, metaParams);

        if (!isBlank(version.datePublished)) {
            TextView date = new TextView(this);
            date.setText(version.datePublished);
            date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            date.setTextColor(LauncherDialogStyle.COLOR_TEXT_MUTED);
            date.setSingleLine(false);
            LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            dateParams.topMargin = dp(2);
            row.addView(date, dateParams);
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(8));
        parent.addView(row, params);
        return row;
    }

    private void updateModpackVersionRowSelection(@NonNull ArrayList<View> rowViews, int selectedIndex) {
        for (int i = 0; i < rowViews.size(); i++) {
            View row = rowViews.get(i);
            boolean selected = i == selectedIndex;
            GradientDrawable background = new GradientDrawable();
            background.setColor(selected ? LauncherDialogStyle.COLOR_CARD_BG_PRESSED : LauncherDialogStyle.COLOR_CARD_BG);
            background.setCornerRadius(dp(16));
            background.setStroke(dp(selected ? 2 : 1), selected ? LauncherDialogStyle.COLOR_ACCENT : LauncherDialogStyle.COLOR_CARD_STROKE);
            row.setBackground(background);
        }
    }

    private void updateSelectedModpackVersionText(
            @NonNull TextView selectedText,
            @NonNull ModpackUpdateManager.VersionInfo version
    ) {
        selectedText.setText(version.versionLabel
                + "\nMinecraft " + version.getMinecraftVersionsLabel()
                + " • " + version.getLoadersLabel()
                + (isBlank(version.datePublished) ? "" : "\n" + version.datePublished));
    }

    private void showModpackBackupWarning(
            @NonNull ModpackUpdateManager.InstalledModpackInfo installed,
            @NonNull ModpackUpdateManager.ProjectMatch project,
            @NonNull ModpackUpdateManager.VersionInfo selectedVersion
    ) {
        enableFullscreen();

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);

        LinearLayout root = createModpackDialogRoot(
                scrollView,
                "Backup worlds first",
                "Updating a modpack can replace mods, configs, resource packs, shader packs, and other pack-managed files. A bad update can make worlds fail to load."
        );

        LinearLayout targetCard = addModpackDialogCard(root);
        addModpackCardTitle(targetCard, "Selected update");
        addModpackCardText(targetCard, project.platform.displayName + " • " + project.title, LauncherDialogStyle.COLOR_TEXT_SECONDARY, 14, false);
        addModpackCardText(targetCard, "Current: " + installed.currentVersionLabel, LauncherDialogStyle.COLOR_TEXT_MUTED, 12, false);
        addModpackCardText(targetCard, "Selected: " + selectedVersion.versionLabel, LauncherDialogStyle.COLOR_TEXT_MUTED, 12, false);
        addModpackCardText(targetCard, "Minecraft: " + selectedVersion.getMinecraftVersionsLabel(), LauncherDialogStyle.COLOR_TEXT_MUTED, 12, false);
        addModpackCardText(targetCard, "Loader: " + selectedVersion.getLoadersLabel(), LauncherDialogStyle.COLOR_TEXT_MUTED, 12, false);

        LinearLayout savesCard = addModpackDialogCard(root);
        addModpackCardTitle(savesCard, "World/saves behavior");
        addModpackCardText(savesCard,
                "DroidBridge keeps your existing saves folder. If the selected modpack includes saves, those saves are installed too. If a bundled world conflicts with an existing world folder, the bundled world is copied with a Pack World suffix instead of deleting your existing world.",
                LauncherDialogStyle.COLOR_TEXT_SECONDARY,
                13,
                false
        );

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Update", (d, which) -> runModpackUpdate(installed, project, selectedVersion))
                .create();
        showStyledModpackDialog(dialog);
    }

    private void runModpackUpdate(
            @NonNull ModpackUpdateManager.InstalledModpackInfo installed,
            @NonNull ModpackUpdateManager.ProjectMatch project,
            @NonNull ModpackUpdateManager.VersionInfo selectedVersion
    ) {
        if (rootDirectory == null || gameDirectory == null) {
            Toast.makeText(this, "Missing instance folder.", Toast.LENGTH_LONG).show();
            return;
        }

        setVersionUpdateInProgress(true);
        showUpdateProgressDialog("Update Modpack", "Preparing modpack update...", false, 100, false);
        Thread thread = new Thread(() -> {
            try {
                ModpackUpdateManager.UpdateResult packResult = ModpackUpdateManager.updateInstalledModpack(
                        this,
                        rootDirectory,
                        gameDirectory,
                        getGameVersionIdForContent(),
                        loader,
                        installed,
                        project,
                        selectedVersion,
                        new ModpackUpdateManager.Listener() {
                            @Override
                            public void onStatus(@NonNull String message) {
                                runOnUiThread(() -> setUpdateProgressMessage(message));
                            }

                            @Override
                            public void onProgress(int current, int total) {
                                runOnUiThread(() -> setUpdateProgress(current, total));
                            }
                        }
                );

                InstanceVersionUpdater.UpdateResult versionResult = null;
                if (shouldUpdateInstanceBaseForModpack(packResult)) {
                    String targetLoader = isBlank(packResult.loader) ? loader : packResult.loader;
                    String targetMinecraft = isBlank(packResult.minecraftVersion) ? getGameVersionIdForContent() : packResult.minecraftVersion;
                    runOnUiThread(() -> {
                        setUpdateProgressMessage("Updating instance to Minecraft " + targetMinecraft + "...");
                        setUpdateProgress(0, 4);
                    });
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
                                    runOnUiThread(() -> setUpdateProgressMessage(message));
                                }

                                @Override
                                public void onProgress(int current, int total) {
                                    runOnUiThread(() -> setUpdateProgress(current, total));
                                }
                            }
                    );
                }

                InstanceVersionUpdater.UpdateResult finalVersionResult = versionResult;
                runOnUiThread(() -> applyModpackUpdateResult(packResult, finalVersionResult));
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to update modpack", throwable);
                runOnUiThread(() -> {
                    dismissUpdateProgressDialog();
                    setVersionUpdateInProgress(false);
                    Toast.makeText(this, "Modpack update failed: " + readableError(throwable), Toast.LENGTH_LONG).show();
                });
            }
        }, "Update Modpack");
        thread.start();
    }

    private boolean shouldUpdateInstanceBaseForModpack(@NonNull ModpackUpdateManager.UpdateResult result) {
        if (isBlank(result.minecraftVersion)) return false;
        if (!result.minecraftVersion.equalsIgnoreCase(getGameVersionIdForContent())) return true;
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

    private void applyModpackUpdateResult(
            @NonNull ModpackUpdateManager.UpdateResult packResult,
            @Nullable InstanceVersionUpdater.UpdateResult versionResult
    ) {
        if (versionResult != null) {
            loader = versionResult.loader;
            baseVersionId = versionResult.baseVersionId;
            minecraftVersionId = versionResult.minecraftVersionId;
            versionType = versionResult.versionType;
            updateIntentExtras();
            bindHeader();
        }

        dismissUpdateProgressDialog();
        setVersionUpdateInProgress(false);
        refreshContentList();
        setResult(RESULT_OK);

        String versionSuffix = versionResult == null
                ? ""
                : " · Minecraft " + versionResult.minecraftVersionId + (isBlank(versionResult.loaderVersion) ? "" : " · " + versionResult.loader + " " + versionResult.loaderVersion);
        Toast.makeText(
                this,
                "Modpack updated to " + packResult.versionLabel + versionSuffix + formatModpackUpdateRemovedSuffix(packResult.removedOldFiles),
                Toast.LENGTH_LONG
        ).show();
    }

    @NonNull
    private String formatModpackUpdateRemovedSuffix(int removedOldFiles) {
        if (removedOldFiles <= 0) return "";
        return " · removed " + removedOldFiles + " old " + (removedOldFiles == 1 ? "file" : "files");
    }

    @NonNull
    private LinearLayout createModpackDialogRoot(
            @NonNull ScrollView scrollView,
            @NonNull String titleText,
            @NonNull String infoText
    ) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);
        int padding = dp(18);
        root.setPadding(padding, padding, padding, dp(8));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        title.setPadding(dp(2), 0, dp(2), dp(6));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView info = new TextView(this);
        info.setText(infoText);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        info.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        info.setPadding(dp(2), 0, dp(2), dp(12));
        root.addView(info, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return root;
    }

    @NonNull
    private LinearLayout createModpackDialogCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(14);
        card.setPadding(padding, padding, padding, padding);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(LauncherDialogStyle.COLOR_CARD_BG);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), LauncherDialogStyle.COLOR_CARD_STROKE);
        card.setBackground(bg);
        return card;
    }

    @NonNull
    private LinearLayout addModpackDialogCard(@NonNull LinearLayout root) {
        LinearLayout card = createModpackDialogCard();
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(12));
        root.addView(card, params);
        return card;
    }

    private void addModpackActionCard(
            @NonNull LinearLayout root,
            @NonNull String title,
            @NonNull String subtitle,
            @NonNull Runnable action
    ) {
        LinearLayout card = addModpackDialogCard(root);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> action.run());
        addModpackCardTitle(card, title);
        addModpackCardText(card, subtitle, LauncherDialogStyle.COLOR_TEXT_SECONDARY, 13, false);
    }

    private void addModpackCardTitle(@NonNull LinearLayout root, @NonNull String title) {
        TextView view = new TextView(this);
        view.setText(title);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        view.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        view.setPadding(0, 0, 0, dp(6));
        root.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    private void addModpackCardText(
            @NonNull LinearLayout root,
            @NonNull String text,
            int color,
            int sp,
            boolean bold
    ) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        view.setPadding(0, 0, 0, dp(4));
        view.setSingleLine(false);
        root.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    @NonNull
    private TextView createModpackFooterButton(@NonNull String label, boolean filled) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTypeface(button.getTypeface(), android.graphics.Typeface.BOLD);
        button.setTextColor(filled ? LauncherDialogStyle.COLOR_DIALOG_BG : LauncherDialogStyle.COLOR_ACCENT);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(44));
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setClickable(true);
        button.setFocusable(true);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(filled ? LauncherDialogStyle.COLOR_ACCENT : Color.TRANSPARENT);
        bg.setStroke(dp(1), LauncherDialogStyle.COLOR_ACCENT);
        button.setBackground(bg);
        return button;
    }

    @NonNull
    private ArrayAdapter<String> createModpackDarkAdapter(@NonNull String[] labels) {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                styleModpackListText(view);
                return view;
            }
        };
        return adapter;
    }

    @NonNull
    private ArrayAdapter<String> createModpackDarkChoiceAdapter(@NonNull String[] labels) {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice, labels) {
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                styleModpackListText(view);
                return view;
            }
        };
        return adapter;
    }

    private void styleModpackListText(@NonNull View view) {
        view.setBackgroundColor(LauncherDialogStyle.COLOR_CARD_BG_PRESSED);
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            textView.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            textView.setSingleLine(false);
            textView.setPadding(dp(14), dp(10), dp(14), dp(10));
        }
    }

    private void showStyledModpackDialog(@NonNull AlertDialog dialog) {
        LauncherDialogStyle.syncTheme(this);
        showFullscreenSafeDialog(dialog);
        styleModpackDialogChrome(dialog);
    }

    private void styleModpackDialogChrome(@NonNull AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            GradientDrawable background = new GradientDrawable();
            background.setColor(LauncherDialogStyle.COLOR_DIALOG_BG);
            background.setCornerRadius(dp(22));
            background.setStroke(dp(1), LauncherDialogStyle.COLOR_DIALOG_BG);
            window.setBackgroundDrawable(background);
            window.setDimAmount(0.58f);
        }
        tintModpackDialogButton(dialog, AlertDialog.BUTTON_POSITIVE);
        tintModpackDialogButton(dialog, AlertDialog.BUTTON_NEGATIVE);
        tintModpackDialogButton(dialog, AlertDialog.BUTTON_NEUTRAL);
    }

    private void tintModpackDialogButton(@NonNull AlertDialog dialog, int whichButton) {
        TextView button = dialog.getButton(whichButton);
        if (button != null) button.setTextColor(LauncherDialogStyle.COLOR_ACCENT);
    }

    private void showUpdateLoaderDialog() {
        InstanceVersionUpdater.LoaderKind kind = getSupportedLoaderKind();
        if (kind == null) {
            Toast.makeText(this, "Update Loader is only available for Fabric, Forge, and NeoForge instances.", Toast.LENGTH_LONG).show();
            return;
        }

        String mcVersion = getGameVersionIdForContent();
        showUpdateProgressDialog("Loading " + kind.displayName + " versions", "Fetching loader versions for Minecraft " + mcVersion + "...", true, 1, true);
        Thread thread = new Thread(() -> {
            try {
                ArrayList<InstanceVersionUpdater.LoaderVersion> versions = InstanceVersionUpdater.fetchLoaderVersions(kind, mcVersion);
                runOnUiThread(() -> {
                    dismissUpdateProgressDialog();
                    showLoaderVersionSelector(kind, versions);
                });
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to load loader versions", throwable);
                runOnUiThread(() -> {
                    dismissUpdateProgressDialog();
                    Toast.makeText(this, "Unable to load loader versions: " + readableError(throwable), Toast.LENGTH_LONG).show();
                });
            }
        }, "Load Loader Versions");
        thread.start();
    }

    private void showLoaderVersionSelector(
            @NonNull InstanceVersionUpdater.LoaderKind kind,
            @NonNull ArrayList<InstanceVersionUpdater.LoaderVersion> versions
    ) {
        if (versions.isEmpty()) {
            Toast.makeText(this, "No " + kind.displayName + " versions found for Minecraft " + getGameVersionIdForContent() + ".", Toast.LENGTH_LONG).show();
            return;
        }

        String currentLoaderVersion = InstanceVersionUpdater.resolveCurrentLoaderVersion(
                kind,
                baseVersionId,
                getGameVersionIdForContent()
        );

        String[] labels = new String[versions.size()];
        for (int i = 0; i < versions.size(); i++) {
            labels[i] = versions.get(i).getDisplayLabel(currentLoaderVersion);
        }

        String currentMessage = isBlank(currentLoaderVersion)
                ? ""
                : "\n\nCurrent " + kind.displayName + " loader: " + currentLoaderVersion;

        showVersionSelectionDialog(
                "Update Loader",
                "Pick the " + kind.displayName + " loader version to install for Minecraft " + getGameVersionIdForContent() + "." + currentMessage,
                labels,
                (dialog, which) -> confirmUpdateLoader(versions.get(which), currentLoaderVersion)
        );
    }

    private void confirmUpdateLoader(
            @NonNull InstanceVersionUpdater.LoaderVersion selectedLoader,
            @Nullable String currentLoaderVersion
    ) {
        boolean alreadyCurrent = InstanceVersionUpdater.isSameLoaderVersion(selectedLoader.displayVersion, currentLoaderVersion)
                || InstanceVersionUpdater.isSameLoaderVersion(selectedLoader.installVersion, currentLoaderVersion);

        String message = alreadyCurrent
                ? "This instance is already using "
                + selectedLoader.kind.displayName
                + " "
                + selectedLoader.displayVersion
                + " for Minecraft "
                + selectedLoader.minecraftVersion
                + "."
                : "Update this instance to "
                + selectedLoader.kind.displayName
                + " "
                + selectedLoader.displayVersion
                + " for Minecraft "
                + selectedLoader.minecraftVersion
                + "?";

        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("Update Loader")
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null);

        if (alreadyCurrent) {
            builder.setPositiveButton(android.R.string.ok, null);
        } else {
            builder.setPositiveButton("Update", (dialog, which) -> runLoaderUpdate(selectedLoader));
        }

        builder.show();
    }

    private void runLoaderUpdate(@NonNull InstanceVersionUpdater.LoaderVersion selectedLoader) {
        if (rootDirectory == null || gameDirectory == null) {
            Toast.makeText(this, "Missing instance folder.", Toast.LENGTH_LONG).show();
            return;
        }

        setVersionUpdateInProgress(true);
        showUpdateProgressDialog("Update Loader", "Preparing loader update...", false, 4, false);
        Thread thread = new Thread(() -> {
            try {
                InstanceVersionUpdater.UpdateResult result = InstanceVersionUpdater.updateInstanceLoader(
                        this,
                        rootDirectory,
                        gameDirectory,
                        instanceName,
                        selectedLoader,
                        new InstanceVersionUpdater.Listener() {
                            @Override
                            public void onStatus(@NonNull String message) {
                                runOnUiThread(() -> setUpdateProgressMessage(message));
                            }

                            @Override
                            public void onProgress(int current, int total) {
                                runOnUiThread(() -> setUpdateProgress(current, total));
                            }
                        }
                );
                runOnUiThread(() -> applyVersionUpdateResult(result, "Loader updated"));
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to update instance loader", throwable);
                runOnUiThread(() -> {
                    dismissUpdateProgressDialog();
                    setVersionUpdateInProgress(false);
                    Toast.makeText(this, "Loader update failed: " + readableError(throwable), Toast.LENGTH_LONG).show();
                });
            }
        }, "Update Instance Loader");
        thread.start();
    }

    private void applyVersionUpdateResult(
            @NonNull InstanceVersionUpdater.UpdateResult result,
            @NonNull String successPrefix
    ) {
        loader = result.loader;
        baseVersionId = result.baseVersionId;
        minecraftVersionId = result.minecraftVersionId;
        versionType = result.versionType;
        updateIntentExtras();
        bindHeader();
        refreshContentList();
        dismissUpdateProgressDialog();
        setVersionUpdateInProgress(false);
        setResult(RESULT_OK);

        String loaderMessage = result.loaderVersion == null ? "" : " · " + result.loader + " " + result.loaderVersion;
        Toast.makeText(
                this,
                successPrefix + ": Minecraft " + result.minecraftVersionId + loaderMessage,
                Toast.LENGTH_LONG
        ).show();
    }

    private void setVersionUpdateInProgress(boolean updating) {
        binding.buttonPlay.setEnabled(!updating);
        binding.buttonPlayServer.setEnabled(!updating);
        binding.buttonInstanceSettings.setEnabled(!updating);
        binding.buttonBrowseContent.setEnabled(!updating && canBrowseSelectedCategory());
        binding.buttonAddMods.setEnabled(!updating && canUploadSelectedCategory());
        binding.buttonCheckContentUpdates.setEnabled(!updating && canCheckUpdatesForSelectedCategory());
        binding.buttonUpdateAllContent.setEnabled(!updating && hasAvailableUpdatesForSelectedCategory());
        updateContentFilterButtonUi();
    }

    @NonNull
    private String readableError(@NonNull Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    private void showRepairInstanceDialog() {
        String minecraftVersion = getGameVersionIdForContent();
        String activeHome = PathManager.DIR_MINECRAFT_HOME;

        StringBuilder message = new StringBuilder();
        message.append("Repair this instance for Minecraft ").append(minecraftVersion).append("?\n\n");
        message.append("This will redownload the vanilla game files, libraries, asset index, and missing assets needed by the current launcher storage location.\n\n");
        message.append("Active launcher home:\n").append(activeHome == null ? "(unknown)" : activeHome).append("\n\n");
        message.append("This does not delete saves, mods, shaderpacks, or resource packs.");

        new MaterialAlertDialogBuilder(this)
                .setTitle("Repair Instance")
                .setMessage(message.toString())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Repair", (dialog, which) -> runRepairInstance())
                .show();
    }

    private void runRepairInstance() {
        String minecraftVersion = getGameVersionIdForContent();
        if (isBlank(minecraftVersion)) {
            Toast.makeText(this, "Missing Minecraft version.", Toast.LENGTH_LONG).show();
            return;
        }

        setVersionUpdateInProgress(true);
        showUpdateProgressDialog("Repair Instance", "Preparing repair...", false, 100, false);

        Thread thread = new Thread(() -> {
            try {
                PathManager.initContextConstants(this);

                MinecraftVersion manifestVersion = resolveRepairManifestVersion(minecraftVersion);
                if (manifestVersion.getMetadataUrl() == null || manifestVersion.getMetadataUrl().trim().isEmpty()) {
                    throw new IllegalStateException("No Mojang metadata URL found for " + minecraftVersion);
                }

                MinecraftVersionInstaller.installVanillaVersion(
                        this,
                        manifestVersion,
                        (progress, message) -> runOnUiThread(() -> {
                            setUpdateProgress(progress, 100);
                            setUpdateProgressMessage(message);
                        })
                );

                runOnUiThread(() -> {
                    dismissUpdateProgressDialog();
                    setVersionUpdateInProgress(false);
                    setResult(RESULT_OK);
                    Toast.makeText(
                            this,
                            "Repair complete for Minecraft " + minecraftVersion + ".",
                            Toast.LENGTH_LONG
                    ).show();
                });
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to repair instance " + instanceName, throwable);
                runOnUiThread(() -> {
                    dismissUpdateProgressDialog();
                    setVersionUpdateInProgress(false);
                    Toast.makeText(
                            this,
                            "Repair failed: " + readableError(throwable),
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        }, "Repair Instance");
        thread.start();
    }

    @NonNull
    private MinecraftVersion resolveRepairManifestVersion(@NonNull String minecraftVersion) throws Exception {
        java.util.List<MinecraftVersion> versions = MinecraftVersionManifestClient.loadVersions(this);
        for (MinecraftVersion version : versions) {
            if (minecraftVersion.equals(version.getId())) {
                return version;
            }
        }
        throw new IllegalStateException("Minecraft version not found in Mojang manifest: " + minecraftVersion);
    }

    private void launchInstance() {
        launchInstance(null, null);
    }

    private void launchInstance(@Nullable String quickPlayWorldFolderName) {
        launchInstance(quickPlayWorldFolderName, null);
    }

    private void launchInstance(@Nullable String quickPlayWorldFolderName, @Nullable String quickPlayServerAddress) {
        if (requireMicrosoftLoginHistoryBeforeLaunch(() -> launchInstance(quickPlayWorldFolderName, quickPlayServerAddress))) {
            return;
        }

        Renderers.reload(this);
        RendererInterface selectedRenderer = Renderers.getSelectedRenderer(this);
        RendererInterface renderer = InstanceLaunchSettings.resolveEffectiveRendererForLaunch(this, instanceId);

        if (renderer != selectedRenderer) {
            Logging.i(TAG, "Launch renderer resolved to "
                    + renderer.getRendererName() + " instead of global default "
                    + selectedRenderer.getRendererName() + " for instance " + instanceId);
        }

        if (MobileGluesConfigHelper.isMobileGluesRenderer(renderer)
                && !MobileGluesConfigHelper.hasStorageAccess(this)) {
            showRendererPluginStorageDialog(renderer, quickPlayWorldFolderName, quickPlayServerAddress);
            return;
        }

        continueLaunchInstance(quickPlayWorldFolderName, quickPlayServerAddress);
    }

    private void continueLaunchInstance(@Nullable String quickPlayWorldFolderName, @Nullable String quickPlayServerAddress) {
        LauncherPreferences.recordInstancePlayed(this, instanceId);

        Intent intent = new Intent(this, GameActivity.class);
        intent.putExtra(
                GameActivity.EXTRA_VERSION_ID,
                isolated ? instanceName : baseVersionId
        );
        intent.putExtra(GameActivity.EXTRA_INSTANCE_SETTINGS_KEY, getPerInstanceSettingsKey());

        if (quickPlayWorldFolderName != null && !quickPlayWorldFolderName.trim().isEmpty()) {
            intent.putExtra(GameActivity.EXTRA_QUICK_PLAY_WORLD, quickPlayWorldFolderName.trim());
        }

        if (quickPlayServerAddress != null && !quickPlayServerAddress.trim().isEmpty()) {
            intent.putExtra(GameActivity.EXTRA_QUICK_PLAY_SERVER, quickPlayServerAddress.trim());
        }

        startActivity(intent);
    }
    private void showServerQuickPlayDialog() {
        ArrayList<QuickPlayHelper.ServerEntry> servers;
        try {
            servers = QuickPlayHelper.readServerList(gameDirectory);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to read server list for " + instanceName, throwable);
            Toast.makeText(this, readableError(throwable), Toast.LENGTH_LONG).show();
            return;
        }

        if (servers.isEmpty()) {
            Toast.makeText(this, R.string.server_quick_play_no_servers, Toast.LENGTH_LONG).show();
            return;
        }

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        View decorView = getWindow().getDecorView();
        if (decorView.getWidth() > 0) screenWidth = decorView.getWidth();
        if (decorView.getHeight() > 0) screenHeight = decorView.getHeight();

        int dialogHeight = Math.min(Math.max(dp(280), screenHeight - dp(72)), dp(640));
        int dialogWidth = Math.min(Math.max(dp(300), screenWidth - dp(32)), dp(560));

        FrameLayout dialogFrame = new FrameLayout(this);
        dialogFrame.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);
        dialogFrame.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dialogHeight
        ));

        LinearLayout dialogRoot = new LinearLayout(this);
        dialogRoot.setOrientation(LinearLayout.VERTICAL);
        dialogRoot.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);
        dialogFrame.addView(dialogRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dialogHeight
        ));

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setClipToPadding(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setHorizontalScrollBarEnabled(false);
        scrollView.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);

        LinearLayout root = createModpackDialogRoot(
                scrollView,
                getString(R.string.server_quick_play_title),
                "Choose a server from this instance's Minecraft server list."
        );

        LinearLayout serverCard = addModpackDialogCard(root);
        final AlertDialog[] dialogRef = new AlertDialog[1];
        for (QuickPlayHelper.ServerEntry server : servers) {
            addServerQuickPlayRow(serverCard, server, dialogRef);
        }

        dialogRoot.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        footer.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);
        footer.setPadding(dp(18), dp(4), dp(18), dp(12));

        TextView cancelButton = createModpackFooterButton(getString(android.R.string.cancel), false);
        cancelButton.setOnClickListener(view -> {
            AlertDialog dialog = dialogRef[0];
            if (dialog != null) dialog.dismiss();
        });
        footer.addView(cancelButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        dialogRoot.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(dialogFrame)
                .create();
        dialogRef[0] = dialog;
        showStyledModpackDialog(dialog);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(dialogWidth, dialogHeight);
        }
    }

    private void addServerQuickPlayRow(
            @NonNull LinearLayout parent,
            @NonNull QuickPlayHelper.ServerEntry server,
            @NonNull AlertDialog[] dialogRef
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(66));
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        row.setClickable(true);
        row.setFocusable(true);

        GradientDrawable rowBackground = new GradientDrawable();
        rowBackground.setColor(LauncherDialogStyle.COLOR_CARD_BG_PRESSED);
        rowBackground.setStroke(dp(1), LauncherDialogStyle.COLOR_CARD_STROKE);
        rowBackground.setCornerRadius(dp(14));
        row.setBackground(rowBackground);

        TextView name = new TextView(this);
        name.setText(server.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        name.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        if (!server.name.equals(server.address)) {
            TextView address = new TextView(this);
            address.setText(server.address);
            address.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            address.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
            address.setSingleLine(true);
            address.setEllipsize(android.text.TextUtils.TruncateAt.END);
            address.setPadding(0, dp(3), 0, 0);
            row.addView(address, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }

        row.setOnClickListener(view -> {
            AlertDialog dialog = dialogRef[0];
            if (dialog != null) dialog.dismiss();
            launchInstance(null, server.address);
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(8));
        parent.addView(row, params);
    }

    private void registerMobileGluesFolderPickerLauncher() {
        mobileGluesFolderPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    String quickPlayWorldFolderName = pendingMobileGluesQuickPlayWorldFolderName;
                    String quickPlayServerAddress = pendingMobileGluesQuickPlayServerAddress;
                    pendingMobileGluesQuickPlayWorldFolderName = null;
                    pendingMobileGluesQuickPlayServerAddress = null;

                    if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                        enableFullscreen();
                        return;
                    }

                    Uri treeUri = result.getData().getData();
                    if (treeUri == null) {
                        enableFullscreen();
                        return;
                    }

                    try {
                        final int flags = result.getData().getFlags()
                                & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                        if (flags != 0) {
                            getContentResolver().takePersistableUriPermission(treeUri, flags);
                        }
                    } catch (Throwable throwable) {
                        Logging.i(TAG, "Unable to persist MobileGlues folder permission: " + readableError(throwable));
                    }

                    MobileGluesConfigHelper.setSelectedConfigTreeUri(this, treeUri);

                    if (MobileGluesConfigHelper.hasStorageAccess(this)) {
                        Toast.makeText(this, "MobileGlues folder saved.", Toast.LENGTH_SHORT).show();
                        continueLaunchInstance(quickPlayWorldFolderName, quickPlayServerAddress);
                    } else {
                        Toast.makeText(this, "MobileGlues folder was selected, but storage access is still unavailable.", Toast.LENGTH_LONG).show();
                        enableFullscreen();
                    }
                }
        );
    }

    private void openMobileGluesFolderPicker(@Nullable String quickPlayWorldFolderName, @Nullable String quickPlayServerAddress) {
        if (mobileGluesFolderPickerLauncher == null) {
            Toast.makeText(this, R.string.renderer_storage_access_open_failed, Toast.LENGTH_LONG).show();
            return;
        }

        pendingMobileGluesQuickPlayWorldFolderName = quickPlayWorldFolderName;
        pendingMobileGluesQuickPlayServerAddress = quickPlayServerAddress;

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);

        try {
            mobileGluesFolderPickerLauncher.launch(intent);
        } catch (Throwable throwable) {
            pendingMobileGluesQuickPlayWorldFolderName = null;
            pendingMobileGluesQuickPlayServerAddress = null;
            Logging.e(TAG, "Unable to open MobileGlues folder picker", throwable);
            Toast.makeText(this, R.string.renderer_storage_access_open_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void showRendererPluginStorageDialog(@NonNull RendererInterface renderer, @Nullable String quickPlayWorldFolderName, @Nullable String quickPlayServerAddress) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.renderer_plugin_storage_title)
                .setMessage(renderer.getRendererName()
                        + " needs the MobileGlues folder selected before launch.\n\n"
                        + "Pick the MG folder so the launcher can save the MobileGlues config here:\n"
                        + MobileGluesConfigHelper.getConfigFile().getAbsolutePath())
                .setNegativeButton(R.string.renderer_plugin_continue_anyway, (dialog, which) -> continueLaunchInstance(quickPlayWorldFolderName, quickPlayServerAddress))
                .setNeutralButton(R.string.renderer_plugin_open_settings, (dialog, which) -> RendererPluginManager.openPluginApp(this, renderer))
                .setPositiveButton("Pick MG folder", (dialog, which) -> openMobileGluesFolderPicker(quickPlayWorldFolderName, quickPlayServerAddress))
                .show();
    }

    private void continueLaunchInstance() {
        continueLaunchInstance(null, null);
    }

    @Nullable
    private File findLatestInstanceCrashOrLogFile() {
        if (gameDirectory == null) return null;
        return findNewestTextLikeFile(new File(gameDirectory, "crash-reports"));
    }

    @NonNull
    private ArrayList<File> buildInstanceCrashReportDirectories() {
        ArrayList<File> directories = new ArrayList<>();
        if (gameDirectory != null) directories.add(new File(gameDirectory, "crash-reports"));
        return directories;
    }

    @NonNull
    private ArrayList<File> buildInstanceLatestLogCandidates() {
        return new ArrayList<>();
    }

    @Nullable
    private File findNewestTextLikeFile(@Nullable File directory) {
        if (directory == null || !directory.isDirectory()) return null;
        File[] files = directory.listFiles(file -> isShareableLogFile(file));
        if (files == null || files.length == 0) return null;

        File newest = null;
        for (File file : files) {
            if (newest == null || file.lastModified() > newest.lastModified()) {
                newest = file;
            }
        }
        return newest;
    }

    private boolean isShareableLogFile(@Nullable File file) {
        if (file == null || !file.isFile() || file.length() <= 0L) return false;
        String name = file.getName().toLowerCase(Locale.US);
        return name.endsWith(".txt") || name.endsWith(".log");
    }

    private void shareLatestInstanceCrashLog() {
        File logFile = findLatestInstanceCrashOrLogFile();
        if (logFile == null) {
            Toast.makeText(this, "No crash reports were found in this instance game/crash-reports folder.", Toast.LENGTH_LONG).show();
            return;
        }

        File shareDir = new File(getCacheDir(), "shared_crash_logs");
        if (!shareDir.exists() && !shareDir.mkdirs()) {
            Toast.makeText(this, "Unable to prepare crash log for sharing.", Toast.LENGTH_LONG).show();
            return;
        }

        String safeInstance = sanitizeImportedFileName(instanceName, null);
        if (isBlank(safeInstance)) safeInstance = "instance";
        File shareFile = new File(shareDir, safeInstance + "-" + logFile.getName());

        try {
            copyFile(logFile, shareFile);
            shareFile.setReadable(true, false);

            Uri uri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    shareFile
            );

            Intent sendIntent = new Intent(Intent.ACTION_SEND);
            sendIntent.setType("text/plain");
            sendIntent.putExtra(Intent.EXTRA_STREAM, uri);
            sendIntent.putExtra(Intent.EXTRA_SUBJECT, "Crash log - " + instanceName);
            sendIntent.putExtra(Intent.EXTRA_TEXT, "Crash log for " + instanceName + "\n" + logFile.getName());
            sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            sendIntent.setClipData(ClipData.newUri(getContentResolver(), "Crash Log", uri));

            startActivity(Intent.createChooser(sendIntent, "Share Crash Log"));
        } catch (ActivityNotFoundException throwable) {
            Toast.makeText(this, "No app is available to share this crash log.", Toast.LENGTH_LONG).show();
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to share instance crash log " + logFile.getAbsolutePath(), throwable);
            Toast.makeText(this, "Unable to share crash log: " + readableError(throwable), Toast.LENGTH_LONG).show();
        }
    }

    private void copyFile(@NonNull File source, @NonNull File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create folder: " + parent.getAbsolutePath());
        }
        try (InputStream input = new java.io.FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target, false)) {
            copyStream(input, output);
        }
    }

    private void showFolderLocation() {
        File folder = gameDirectory != null ? gameDirectory : rootDirectory;
        if (folder == null) {
            Logging.i(TAG, "[OpenFolder] clicked but folder is null. rootDirectory="
                    + filePathOrNull(rootDirectory) + ", gameDirectory=" + filePathOrNull(gameDirectory));
            return;
        }

        Logging.i(TAG, "[OpenFolder] clicked instanceId=" + instanceId
                + ", instanceName=" + instanceName
                + ", isolated=" + isolated
                + ", baseVersionId=" + baseVersionId
                + ", minecraftVersionId=" + minecraftVersionId);
        Logging.i(TAG, "[OpenFolder] raw rootDirectory=" + filePathOrNull(rootDirectory));
        Logging.i(TAG, "[OpenFolder] raw gameDirectory=" + filePathOrNull(gameDirectory));
        Logging.i(TAG, "[OpenFolder] chosen folder=" + folder.getAbsolutePath()
                + ", exists=" + folder.exists()
                + ", isDirectory=" + folder.isDirectory()
                + ", canRead=" + folder.canRead()
                + ", canWrite=" + folder.canWrite());

        if (!folder.exists()) {
            boolean created = false;
            try {
                created = folder.mkdirs();
            } catch (Throwable throwable) {
                Logging.i(TAG, "[OpenFolder] mkdirs threw for " + folder.getAbsolutePath()
                        + ": " + readableError(throwable));
            }
            Logging.i(TAG, "[OpenFolder] mkdirs result=" + created
                    + ", existsAfter=" + folder.exists()
                    + ", path=" + folder.getAbsolutePath());
        }

        if (openInstanceFolderInFilesApp(folder)) {
            return;
        }

        Logging.i(TAG, "[OpenFolder] all open attempts failed, showing copy-path fallback for "
                + folder.getAbsolutePath());
        showFolderPathFallback(folder);
    }
    private boolean openInstanceFolderInFilesApp(@NonNull File folder) {
        File target;
        try {
            target = folder.getCanonicalFile();
        } catch (IOException ignored) {
            target = folder.getAbsoluteFile();
        }

        logOpenFolderEnvironment(target);

        Uri initialUri = buildSelectedStorageInitialUri(target);
        if (initialUri != null) {
            Logging.i(TAG, "[OpenFolder] selected-storage route produced URI=" + initialUri);
        } else {
            Logging.i(TAG, "[OpenFolder] selected-storage route produced no URI; trying launcher provider");
            initialUri = buildLauncherDocumentsProviderInitialUri(target);
            if (initialUri != null) {
                Logging.i(TAG, "[OpenFolder] launcher-provider route produced URI=" + initialUri);
            }
        }

        if (initialUri == null) {
            Logging.i(TAG, "[OpenFolder] no document-tree initial URI for instance folder: "
                    + target.getAbsolutePath());
            return false;
        }

        return openFolderViewAt(initialUri, target);
    }
    @Nullable
    private Uri buildSelectedStorageInitialUri(@NonNull File target) {
        StorageLocation selected = StorageLocationStore.getSelectedLocation(this);
        Logging.i(TAG, "[OpenFolder] selected location id=" + selected.getId()
                + ", name=" + selected.getDisplayName()
                + ", default=" + selected.isDefaultLocation()
                + ", usableForFileLaunch=" + selected.isUsableForFileLaunch());
        Logging.i(TAG, "[OpenFolder] selected location uri=" + selected.getUriString());
        Logging.i(TAG, "[OpenFolder] selected location launcherHomePath=" + selected.getLauncherHomePath());
        Logging.i(TAG, "[OpenFolder] selected location minecraftHomePath=" + selected.getMinecraftHomePath());

        Uri mapped = buildInitialUriForStorageLocation(selected, target, "selected");
        if (mapped != null) return mapped;

        for (StorageLocation location : StorageLocationStore.getLocations(this)) {
            if (location.isDefaultLocation()) continue;
            if (location.getId().equals(selected.getId())) continue;

            mapped = buildInitialUriForStorageLocation(location, target, "saved");
            if (mapped != null) return mapped;
        }

        Logging.i(TAG, "[OpenFolder] no custom storage location contained target " + target.getAbsolutePath());
        return null;
    }

    @Nullable
    private Uri buildInitialUriForStorageLocation(
            @NonNull StorageLocation location,
            @NonNull File target,
            @NonNull String source
    ) {
        if (location.isDefaultLocation()) {
            Logging.i(TAG, "[OpenFolder] " + source + " location is default; using launcher provider route");
            return null;
        }

        String uriString = location.getUriString();
        if (isBlank(uriString)) {
            Logging.i(TAG, "[OpenFolder] " + source + " custom location has no tree uri: " + location.getId());
            return null;
        }

        try {
            Uri treeUri = Uri.parse(uriString.trim());
            Logging.i(TAG, "[OpenFolder] trying " + source + " launcherHomePath mapping for " + location.getId());
            Uri mapped = buildInitialUriForLocationRoot(treeUri, location.getLauncherHomePath(), target);
            if (mapped != null) {
                Logging.i(TAG, "[OpenFolder] " + source + " launcherHomePath mapping succeeded: " + mapped);
                return mapped;
            }

            Logging.i(TAG, "[OpenFolder] trying " + source + " minecraftHomePath mapping for " + location.getId());
            mapped = buildInitialUriForLocationRoot(treeUri, location.getMinecraftHomePath(), target);
            if (mapped != null) {
                Logging.i(TAG, "[OpenFolder] " + source + " minecraftHomePath mapping succeeded: " + mapped);
                return mapped;
            }
        } catch (Throwable throwable) {
            Logging.i(TAG, "[OpenFolder] unable to map " + source + " storage folder "
                    + location.getId() + ": " + readableError(throwable));
        }

        return null;
    }

    @Nullable
    private Uri buildInitialUriForLocationRoot(
            @NonNull Uri treeUri,
            @Nullable String localRootPath,
            @NonNull File target
    ) throws IOException {
        if (isBlank(localRootPath)) {
            Logging.i(TAG, "[OpenFolder] local root path is blank for treeUri=" + treeUri);
            return null;
        }

        File localRoot = new File(localRootPath.trim()).getCanonicalFile();
        boolean containsTarget = isSameOrChild(localRoot, target);
        Logging.i(TAG, "[OpenFolder] checking localRoot=" + localRoot.getAbsolutePath()
                + " containsTarget=" + containsTarget
                + " target=" + target.getAbsolutePath()
                + " treeUri=" + treeUri);
        if (!containsTarget) return null;

        String relativePath = getRelativePathBetweenFiles(localRoot, target);
        Logging.i(TAG, "[OpenFolder] relative path for selected tree=" + relativePath);

        Uri resolved = SafMinecraftMirror.findRelativePathInTree(this, treeUri, relativePath);
        if (resolved != null) {
            Logging.i(TAG, "[OpenFolder] SAF tree walk resolved URI=" + resolved);
            return resolved;
        }

        // ExternalStorageProvider document IDs are path-like, so keep this fallback
        // for devices/providers that allow direct ID construction but fail traversal.
        Uri appended = buildTreeDocumentUriByAppendingPath(treeUri, relativePath);
        Logging.i(TAG, "[OpenFolder] SAF path append fallback URI=" + appended);
        return appended;
    }

    @Nullable
    private Uri buildLauncherDocumentsProviderInitialUri(@NonNull File target) {
        try {
            File root = DroidBridgeDocumentsProvider.getRootDirectoryForContext(this).getCanonicalFile();
            boolean providerContainsTarget = isSameOrChild(root, target);

            Logging.i(TAG, "[OpenFolder] provider mapping check package=" + getPackageName());
            Logging.i(TAG, "[OpenFolder] provider mapping authority="
                    + DroidBridgeDocumentsProvider.getAuthority(this));
            Logging.i(TAG, "[OpenFolder] provider mapping defaultHome="
                    + PathManager.getDefaultLauncherHome(this).getAbsolutePath());
            Logging.i(TAG, "[OpenFolder] provider mapping root=" + root.getAbsolutePath());
            Logging.i(TAG, "[OpenFolder] provider mapping target=" + target.getAbsolutePath()
                    + ", containsTarget=" + providerContainsTarget);

            if (!providerContainsTarget) return null;

            Uri treeUri = DroidBridgeDocumentsProvider.buildTreeUriForFile(this, target);
            if (treeUri != null) {
                Logging.i(TAG, "[OpenFolder] provider tree URI="
                        + treeUri
                        + ", documentId="
                        + DroidBridgeDocumentsProvider.getDocumentIdForFile(this, target));
                return treeUri;
            }

            Uri documentUri = DroidBridgeDocumentsProvider.buildDocumentUriForFile(this, target);
            Logging.i(TAG, "[OpenFolder] provider tree URI was null, fallback document URI="
                    + documentUri);
            return documentUri;
        } catch (Throwable throwable) {
            Logging.i(TAG, "[OpenFolder] Unable to build launcher DocumentsProvider initial URI: "
                    + readableError(throwable));
            return null;
        }
    }

    @Nullable
    private Uri buildTreeDocumentUriByAppendingPath(@NonNull Uri treeUri, @Nullable String relativePath) {
        try {
            String treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
            if (isBlank(treeDocumentId)) {
                Logging.i(TAG, "[OpenFolder] tree document id is blank for treeUri=" + treeUri);
                return null;
            }

            String documentId = appendDocumentIdPath(treeDocumentId, relativePath);
            Uri built = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
            Logging.i(TAG, "[OpenFolder] built tree URI by appending path. treeDocumentId="
                    + treeDocumentId + ", relativePath=" + relativePath
                    + ", documentId=" + documentId + ", uri=" + built);
            return built;
        } catch (Throwable throwable) {
            Logging.i(TAG, "[OpenFolder] unable to build tree document URI by path: " + readableError(throwable));
            return null;
        }
    }

    private boolean openFolderViewAt(@NonNull Uri folderUri, @NonNull File folder) {
        ArrayList<Intent> attempts = new ArrayList<>();

        addDocumentsUiBrowseAttempt(attempts, folderUri, null, null);

        addDocumentsUiBrowseAttempt(
                attempts,
                folderUri,
                "com.google.android.documentsui",
                "com.android.documentsui.files.FilesActivity"
        );
        addDocumentsUiBrowseAttempt(
                attempts,
                folderUri,
                "com.google.android.documentsui",
                "com.google.android.documentsui.files.FilesActivity"
        );
        addDocumentsUiBrowseAttempt(
                attempts,
                folderUri,
                "com.google.android.documentsui",
                "com.android.documentsui.FilesActivity"
        );
        addDocumentsUiBrowseAttempt(
                attempts,
                folderUri,
                "com.google.android.documentsui",
                "com.google.android.documentsui.FilesActivity"
        );
        addDocumentsUiBrowseAttempt(
                attempts,
                folderUri,
                "com.android.documentsui",
                "com.android.documentsui.files.FilesActivity"
        );
        addDocumentsUiBrowseAttempt(
                attempts,
                folderUri,
                "com.android.documentsui",
                "com.android.documentsui.FilesActivity"
        );

        Throwable lastError = null;
        for (Intent intent : attempts) {
            try {
                Logging.i(TAG, "[OpenFolder] launching folder view: "
                        + folder.getAbsolutePath()
                        + " -> "
                        + folderUri
                        + ", component="
                        + intent.getComponent()
                        + ", package="
                        + intent.getPackage()
                        + ", type="
                        + intent.getType()
                        + ", data="
                        + intent.getData());

                startActivity(intent);

                Logging.i(TAG, "[OpenFolder] folder view startActivity succeeded.");
                return true;
            } catch (Throwable throwable) {
                lastError = throwable;
                Logging.i(TAG, "[OpenFolder] folder view attempt failed: "
                        + readableError(throwable)
                        + ", component="
                        + intent.getComponent()
                        + ", package="
                        + intent.getPackage());
            }
        }

        if (openFolderTreePickerFallback(folderUri, folder)) {
            return true;
        }

        if (lastError != null) {
            Logging.i(TAG, "[OpenFolder] unable to open instance folder browse view: "
                    + readableError(lastError));
        }
        return false;
    }
    private boolean openFolderTreePickerFallback(@NonNull Uri folderUri, @NonNull File folder) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, folderUri);

        try {
            intent.setClipData(ClipData.newUri(getContentResolver(), "Instance Folder", folderUri));
        } catch (Throwable ignored) {
        }

        try {
            Logging.i(TAG, "[OpenFolder] launching ACTION_OPEN_DOCUMENT_TREE fallback: "
                    + folder.getAbsolutePath()
                    + " -> "
                    + folderUri);
            startActivity(intent);
            return true;
        } catch (Throwable throwable) {
            Logging.i(TAG, "[OpenFolder] ACTION_OPEN_DOCUMENT_TREE fallback failed: "
                    + readableError(throwable));
            return false;
        }
    }

    private void addDocumentsUiBrowseAttempt(
            @NonNull ArrayList<Intent> attempts,
            @NonNull Uri folderUri,
            @Nullable String packageName,
            @Nullable String className
    ) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, folderUri);

        try {
            intent.setClipData(ClipData.newUri(getContentResolver(), "Instance Folder", folderUri));
        } catch (Throwable ignored) {
        }

        if (!isBlank(packageName) && !isBlank(className)) {
            intent.setComponent(new android.content.ComponentName(packageName, className));
        } else if (!isBlank(packageName)) {
            intent.setPackage(packageName);
        }

        attempts.add(intent);
    }

    private void logOpenFolderEnvironment(@NonNull File target) {
        Logging.i(TAG, "[OpenFolder] package=" + getPackageName()
                + ", applicationId=" + BuildConfig.APPLICATION_ID
                + ", debugBuild=" + BuildConfig.DEBUG);
        Logging.i(TAG, "[OpenFolder] target canonical=" + safeCanonicalPath(target)
                + ", exists=" + target.exists()
                + ", isDirectory=" + target.isDirectory()
                + ", canRead=" + target.canRead()
                + ", canWrite=" + target.canWrite());

        try {
            StorageLocation selected = StorageLocationStore.getSelectedLocation(this);
            Logging.i(TAG, "[OpenFolder] selectedLocationId=" + selected.getId()
                    + ", selectedLocationName=" + selected.getDisplayName()
                    + ", selectedLocationDefault=" + selected.isDefaultLocation()
                    + ", selectedLocationUsableForFileLaunch=" + selected.isUsableForFileLaunch());
            Logging.i(TAG, "[OpenFolder] selectedLocationUri=" + selected.getUriString());
            Logging.i(TAG, "[OpenFolder] selectedLocationLauncherHomePath=" + selected.getLauncherHomePath());
            Logging.i(TAG, "[OpenFolder] selectedLocationMinecraftHomePath=" + selected.getMinecraftHomePath());
        } catch (Throwable throwable) {
            Logging.i(TAG, "[OpenFolder] unable to read selected storage location: "
                    + readableError(throwable));
        }

        try {
            File defaultHome = PathManager.getDefaultLauncherHome(this);
            Logging.i(TAG, "[OpenFolder] defaultHome=" + defaultHome.getAbsolutePath()
                    + ", exists=" + defaultHome.exists()
                    + ", canRead=" + defaultHome.canRead()
                    + ", canWrite=" + defaultHome.canWrite());
        } catch (Throwable throwable) {
            Logging.i(TAG, "[OpenFolder] unable to resolve defaultHome: " + readableError(throwable));
        }

        try {
            File providerRoot = DroidBridgeDocumentsProvider.getRootDirectoryForContext(this);
            Logging.i(TAG, "[OpenFolder] providerAuthority="
                    + DroidBridgeDocumentsProvider.getAuthority(this));
            Logging.i(TAG, "[OpenFolder] providerRoot=" + providerRoot.getAbsolutePath()
                    + ", exists=" + providerRoot.exists()
                    + ", canRead=" + providerRoot.canRead()
                    + ", canWrite=" + providerRoot.canWrite());
        } catch (Throwable throwable) {
            Logging.i(TAG, "[OpenFolder] unable to resolve provider root: " + readableError(throwable));
        }
    }

    @NonNull
    private String filePathOrNull(@Nullable File file) {
        return file == null ? "null" : file.getAbsolutePath();
    }

    @NonNull
    private String appendDocumentIdPath(@NonNull String documentId, @Nullable String relativePath) {
        String cleanRelative = relativePath == null ? "" : relativePath.replace('\\', '/').trim();
        while (cleanRelative.startsWith("/")) cleanRelative = cleanRelative.substring(1);
        while (cleanRelative.endsWith("/")) cleanRelative = cleanRelative.substring(0, cleanRelative.length() - 1);

        if (cleanRelative.isEmpty()) return documentId;
        if (documentId.endsWith(":") || documentId.endsWith("/")) {
            return documentId + cleanRelative;
        }
        return documentId + "/" + cleanRelative;
    }

    private boolean isSameOrChild(@NonNull File root, @NonNull File target) throws IOException {
        String rootPath = root.getCanonicalPath();
        String targetPath = target.getCanonicalPath();
        return targetPath.equals(rootPath) || targetPath.startsWith(rootPath + File.separator);
    }

    @NonNull
    private String getRelativePathBetweenFiles(@NonNull File root, @NonNull File target) throws IOException {
        String rootPath = root.getCanonicalPath();
        String targetPath = target.getCanonicalPath();

        if (targetPath.equals(rootPath)) return "";

        if (targetPath.startsWith(rootPath + File.separator)) {
            return targetPath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
        }

        return "";
    }

    @NonNull
    private String safeCanonicalPath(@NonNull File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException ignored) {
            return file.getAbsolutePath();
        }
    }

    private void showFolderPathFallback(@NonNull File folder) {
        String path = folder.getAbsolutePath();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.instance_folder_open_failed_title)
                .setMessage(getString(R.string.instance_folder_open_failed_message, path))
                .setNegativeButton(android.R.string.ok, null)
                .setPositiveButton(R.string.instance_folder_copy_path, (dialog, which) -> copyTextToClipboard(path))
                .show();
    }

    private void copyTextToClipboard(@NonNull String text) {
        ClipboardManager clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager != null) {
            clipboardManager.setPrimaryClip(ClipData.newPlainText(getString(R.string.instance_settings_view_folder), text));
            Toast.makeText(this, R.string.instance_folder_path_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void showEditInstanceNameDialog() {
        if (!isolated) {
            Toast.makeText(this, R.string.instance_rename_shared_not_supported, Toast.LENGTH_LONG).show();
            return;
        }

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setText(instanceName);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);

        FrameLayout container = new FrameLayout(this);
        int horizontalPadding = dp(20);
        int topPadding = dp(8);
        container.setPadding(horizontalPadding, topPadding, horizontalPadding, 0);
        container.addView(input, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.instance_settings_edit_name)
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.instance_settings_save_name, null)
                .create();

        dialog.setOnShowListener(value -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String newName = sanitizeInstanceName(input.getText() == null ? "" : input.getText().toString());
            if (isBlank(newName)) {
                input.setError(getString(R.string.instance_rename_empty));
                return;
            }
            if (newName.equals(instanceName)) {
                dialog.dismiss();
                return;
            }
            renameInstance(newName, dialog);
        }));

        dialog.show();
    }

    private void renameInstance(@NonNull String newName, @NonNull AlertDialog dialog) {
        File currentRoot = rootDirectory;
        if (currentRoot == null) {
            Toast.makeText(this, R.string.instance_rename_failed_missing_root, Toast.LENGTH_LONG).show();
            return;
        }

        setInstanceEditInProgress(true);
        Thread thread = new Thread(() -> {
            String oldName = instanceName;
            try {
                LauncherInstance updated = LauncherInstanceManager.renameInstance(this, currentRoot, newName);
                runOnUiThread(() -> {
                    applyUpdatedInstance(updated);
                    setInstanceEditInProgress(false);
                    setResult(RESULT_OK);
                    dialog.dismiss();
                    Toast.makeText(this, getString(R.string.instance_rename_success, oldName, updated.getName()), Toast.LENGTH_SHORT).show();
                });
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to rename instance " + oldName + " to " + newName, throwable);
                runOnUiThread(() -> {
                    setInstanceEditInProgress(false);
                    String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
                    Toast.makeText(this, getString(R.string.instance_rename_failed, message), Toast.LENGTH_LONG).show();
                });
            }
        }, "Rename Instance");
        thread.start();
    }

    @NonNull
    private File remapPathAfterDirectoryRename(@NonNull File oldRoot, @NonNull File newRoot, @NonNull File oldPath) {
        String oldRootPath = safeCanonicalPath(oldRoot);
        String oldTargetPath = safeCanonicalPath(oldPath);
        if (oldTargetPath.equals(oldRootPath)) {
            return newRoot;
        }
        if (oldTargetPath.startsWith(oldRootPath + File.separator)) {
            String relativePath = oldTargetPath.substring(oldRootPath.length() + 1);
            return new File(newRoot, relativePath);
        }
        return oldPath;
    }

    private void applyUpdatedInstance(@NonNull LauncherInstance updated) {
        instanceId = updated.getId();
        instanceName = updated.getName();
        loader = updated.getLoader();
        baseVersionId = updated.getBaseVersionId();
        minecraftVersionId = updated.getMinecraftVersionId();
        versionType = updated.getVersionType();
        rootDirectory = updated.getRootDirectory();
        gameDirectory = updated.getGameDirectory();
        iconFile = updated.getIconFile();
        isolated = updated.isIsolated();
        resetContentDirectories();
        updateIntentExtras();
        bindHeader();
        refreshContentList();
    }

    private void resetContentDirectories() {
        modsDirectory = new File(gameDirectory, "mods");
        shaderpacksDirectory = new File(gameDirectory, "shaderpacks");
        resourcepacksDirectory = new File(gameDirectory, ModManagerContentType.getResourcePackFolderName(getGameVersionIdForContent()));
        worldsDirectory = new File(gameDirectory, "saves");
        screenshotsDirectory = new File(gameDirectory, "screenshots");
    }

    private void updateIntentExtras() {
        Intent intent = getIntent();
        intent.putExtra(EXTRA_INSTANCE_ID, instanceId);
        intent.putExtra(EXTRA_INSTANCE_NAME, instanceName);
        intent.putExtra(EXTRA_INSTANCE_LOADER, loader);
        intent.putExtra(EXTRA_BASE_VERSION_ID, baseVersionId);
        intent.putExtra(EXTRA_MINECRAFT_VERSION_ID, minecraftVersionId);
        intent.putExtra(EXTRA_VERSION_TYPE, versionType);
        intent.putExtra(EXTRA_ROOT_DIRECTORY, rootDirectory != null ? rootDirectory.getAbsolutePath() : "");
        intent.putExtra(EXTRA_GAME_DIRECTORY, gameDirectory != null ? gameDirectory.getAbsolutePath() : "");
        intent.putExtra(EXTRA_ICON_FILE, iconFile != null ? iconFile.getAbsolutePath() : "");
        intent.putExtra(EXTRA_ISOLATED, isolated);
    }

    private void pickInstanceIcon() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        try {
            startActivityForResult(Intent.createChooser(intent, getString(R.string.instance_icon_picker_title)), REQUEST_PICK_INSTANCE_ICON);
        } catch (ActivityNotFoundException throwable) {
            Toast.makeText(this, R.string.instance_icon_picker_missing, Toast.LENGTH_LONG).show();
        }
    }

    private void savePickedInstanceIcon(@NonNull Uri uri) {
        File currentRoot = rootDirectory;
        if (currentRoot == null) {
            Toast.makeText(this, R.string.instance_rename_failed_missing_root, Toast.LENGTH_LONG).show();
            return;
        }

        setInstanceEditInProgress(true);
        Thread thread = new Thread(() -> {
            try {
                LauncherInstance updated = LauncherInstanceManager.updateInstanceIcon(this, currentRoot, uri);
                runOnUiThread(() -> {
                    applyUpdatedInstance(updated);
                    bindInstanceIcon();
                    setInstanceEditInProgress(false);
                    setResult(RESULT_OK);
                    Toast.makeText(this, R.string.instance_icon_update_success, Toast.LENGTH_SHORT).show();
                });
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to update instance icon", throwable);
                runOnUiThread(() -> {
                    setInstanceEditInProgress(false);
                    String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
                    Toast.makeText(this, getString(R.string.instance_icon_update_failed, message), Toast.LENGTH_LONG).show();
                });
            }
        }, "Update Instance Icon");
        thread.start();
    }

    @NonNull
    private File resolveIconTargetFile(@NonNull Uri uri) throws Exception {
        if (iconFile != null && iconFile.getParentFile() != null && iconFile.getParentFile().canWrite()) {
            return iconFile;
        }

        File targetDirectory = rootDirectory != null ? rootDirectory : gameDirectory;
        if (targetDirectory == null) {
            throw new IllegalStateException("Missing instance folder.");
        }
        if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
            throw new IllegalStateException("Unable to create icon folder: " + targetDirectory.getAbsolutePath());
        }

        String extension = resolveImageExtension(uri);
        return new File(targetDirectory, "icon" + extension);
    }

    @NonNull
    private String resolveImageExtension(@NonNull Uri uri) {
        String name = resolveDisplayName(uri).toLowerCase(Locale.US);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return ".jpg";
        if (name.endsWith(".webp")) return ".webp";
        if (name.endsWith(".gif")) return ".gif";
        return ".png";
    }

    private void setInstanceEditInProgress(boolean editing) {
        binding.buttonPlay.setEnabled(!editing);
        binding.buttonPlayServer.setEnabled(!editing);
        binding.buttonInstanceSettings.setEnabled(!editing);
        binding.buttonBrowseContent.setEnabled(!editing && canBrowseSelectedCategory());
        binding.buttonAddMods.setEnabled(!editing && canUploadSelectedCategory());
        binding.buttonCheckContentUpdates.setEnabled(!editing && canCheckUpdatesForSelectedCategory());
        binding.buttonUpdateAllContent.setEnabled(!editing && hasAvailableUpdatesForSelectedCategory());
        updateContentFilterButtonUi();
    }

    private void showDeleteInstanceDialog() {
        if (!isolated) {
            ArrayList<String> dependents = LauncherInstanceManager.findSharedVersionDependents(
                    this,
                    baseVersionId
            );
            if (!dependents.isEmpty()) {
                LauncherDialogStyle.showStyledMessageDialog(
                        this,
                        getString(R.string.delete_shared_instance_blocked_title, instanceName),
                        getString(
                                R.string.delete_shared_instance_blocked_message,
                                instanceName,
                                LauncherInstanceManager.formatDependentVersionList(dependents)
                        ),
                        getString(android.R.string.ok),
                        (dialog, which) -> { },
                        getString(android.R.string.cancel)
                );
                return;
            }
        }

        String targetPath;
        try {
            targetPath = LauncherInstanceManager.getDeleteTargetDirectory(baseVersionId, rootDirectory, isolated).getAbsolutePath();
        } catch (Throwable throwable) {
            targetPath = rootDirectory.getAbsolutePath();
        }

        int messageId = isolated
                ? R.string.delete_instance_message
                : R.string.delete_shared_instance_message;

        LauncherDialogStyle.showStyledMessageDialog(
                this,
                getString(R.string.delete_instance_title, instanceName),
                getString(messageId, instanceName, targetPath),
                getString(R.string.button_delete_forever),
                (dialog, which) -> deleteInstance(),
                getString(android.R.string.cancel)
        );
    }

    private void deleteInstance() {
        setDeleteInProgress(true);

        Thread thread = new Thread(() -> {
            LauncherInstanceDeleteManager.DeleteJob deleteJob;
            try {
                deleteJob = LauncherInstanceDeleteManager.hideForDeletion(
                        this,
                        instanceId,
                        baseVersionId,
                        rootDirectory,
                        isolated
                );
                LauncherPreferences.clearInstancePlayed(this, instanceId);
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to prepare delete for " + instanceName, throwable);
                runOnUiThread(() -> {
                    setDeleteInProgress(false);
                    String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
                    Toast.makeText(this, getString(R.string.delete_instance_failed, instanceName, message), Toast.LENGTH_LONG).show();
                });
                return;
            }

            if (deleteJob.wasMovedOutOfVisibleList() || !deleteJob.getOriginalTarget().exists()) {
                runOnUiThread(this::finishDeleteAndClose);
                finishDeleteInBackground(deleteJob);
                return;
            }

            try {
                LauncherInstanceDeleteManager.finishDeletion(this, deleteJob);
                runOnUiThread(this::finishDeleteAndClose);
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to delete instance " + instanceName, throwable);
                runOnUiThread(() -> {
                    setDeleteInProgress(false);
                    String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
                    Toast.makeText(this, getString(R.string.delete_instance_failed, instanceName, message), Toast.LENGTH_LONG).show();
                });
            }
        }, "Delete Instance From Details");
        thread.start();
    }

    private void finishDeleteAndClose() {
        Toast.makeText(this, getString(R.string.delete_instance_deleted, instanceName), Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private void finishDeleteInBackground(@NonNull LauncherInstanceDeleteManager.DeleteJob deleteJob) {
        try {
            LauncherInstanceDeleteManager.finishDeletion(this, deleteJob);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Instance was hidden but background cleanup failed for " + instanceName, throwable);
        }
    }

    private void setDeleteInProgress(boolean deleting) {
        binding.buttonPlay.setEnabled(!deleting);
        binding.buttonPlayServer.setEnabled(!deleting);
        binding.buttonInstanceSettings.setEnabled(!deleting);
        binding.buttonBrowseContent.setEnabled(!deleting && canBrowseSelectedCategory());
        binding.buttonAddMods.setEnabled(!deleting && canUploadSelectedCategory());
        binding.buttonCheckContentUpdates.setEnabled(!deleting && canCheckUpdatesForSelectedCategory());
        binding.buttonUpdateAllContent.setEnabled(!deleting && hasAvailableUpdatesForSelectedCategory());
        updateContentFilterButtonUi();
    }

    private void refreshContentList() {
        if (contentAdapter == null || screenshotAdapter == null || binding == null || gameDirectory == null) return;

        final ResourceCategory refreshCategory = selectedCategory;
        configureContentRecyclerForCategory(refreshCategory);
        final File directory = getDirectoryForCategory(refreshCategory);
        final ModManagerContentType managerType = toModManagerContentType(refreshCategory);
        final int generation = ++contentRefreshGeneration;
        final int visibleBeforeRefresh = contentItems.size();
        contentPageGeneration++;
        clearInstalledContentLookupCaches();

        contentRefreshRunning = true;
        updateContentFilterButtonUi();
        // Show immediately. If the list is large, waiting for a delayed overlay means
        // RecyclerView can start binding rows before the user sees any progress UI.
        setContentLoadingOverlayVisible(
                true,
                getCategoryPluralLabel(refreshCategory),
                visibleBeforeRefresh == 0
                        ? "Loading " + getCategoryPluralLabel(refreshCategory).toLowerCase(Locale.US) + "..."
                        : "Refreshing " + getCategoryPluralLabel(refreshCategory).toLowerCase(Locale.US) + "..."
        );

        // Keep the first screen draw responsive while large folders are scanned in the background.
        binding.buttonBrowseContent.setVisibility(refreshCategory == ResourceCategory.SCREENSHOTS ? View.GONE : View.VISIBLE);
        binding.buttonBrowseContent.setText(R.string.button_browse_content);
        binding.buttonBrowseContent.setEnabled(canBrowseSelectedCategory() && !contentOperationRunning);
        bindImportButtonForCategory(refreshCategory);
        binding.buttonAddMods.setEnabled(canUploadSelectedCategory() && !contentOperationRunning);
        binding.textModsHint.setText(visibleBeforeRefresh == 0
                ? "Loading " + getCategoryPluralLabel(refreshCategory).toLowerCase(Locale.US) + "..."
                : "Refreshing " + getCategoryPluralLabel(refreshCategory).toLowerCase(Locale.US) + "...");
        updateContentUpdateButtons();

        contentRefreshExecutor.execute(() -> {
            ArrayList<InstanceContentItem> loadedItems = new ArrayList<>();

            // Pruning can touch installed-content manifests and the filesystem.
            // Keep it off the UI thread so big packs do not freeze the activity transition.
            if (managerType != null) {
                try {
                    ModManagerManifest.pruneMissingFiles(gameDirectory, managerType);
                } catch (Throwable throwable) {
                    Logging.i(TAG, "Unable to prune missing content metadata: " + readableError(throwable));
                }
            }

            try {
                if (directory.exists() || directory.mkdirs()) {
                    File[] files = directory.listFiles(file -> shouldShowFileForCategory(refreshCategory, file));
                    if (files != null) {
                        ArrayList<File> sortedFiles = new ArrayList<>();
                        Collections.addAll(sortedFiles, files);
                        // Only index filesystem entries here. Do not open jars/zips or read pack
                        // metadata during the folder scan; the visible page loads rich metadata later.
                        if (refreshCategory == ResourceCategory.SCREENSHOTS) {
                            sortedFiles.sort((left, right) -> {
                                int modifiedCompare = Long.compare(right.lastModified(), left.lastModified());
                                return modifiedCompare != 0
                                        ? modifiedCompare
                                        : left.getName().compareToIgnoreCase(right.getName());
                            });
                        } else {
                            sortedFiles.sort(Comparator
                                    .comparing((File file) -> !file.isDirectory())
                                    .thenComparing(file -> stripDisabledSuffix(stripExtension(file.getName())).toLowerCase(Locale.US)));
                        }

                        for (File file : sortedFiles) {
                            loadedItems.add(new InstanceContentItem(
                                    file,
                                    refreshCategory,
                                    resolveDisplayTitle(file, refreshCategory)
                            ));
                        }
                    }
                }
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to scan instance content folder: " + directory.getAbsolutePath(), throwable);
            }

            mainHandler.post(() -> {
                if (binding == null || isFinishing() || isDestroyed()) return;
                if (generation != contentRefreshGeneration || selectedCategory != refreshCategory) return;

                allContentItems.clear();
                allContentItems.addAll(loadedItems);
                sortContentItemsForCurrentUpdateState();
                applyContentSearchFilter(false);
                pruneContentSelectionToLoadedItems();

                binding.buttonBrowseContent.setVisibility(refreshCategory == ResourceCategory.SCREENSHOTS ? View.GONE : View.VISIBLE);
                binding.buttonBrowseContent.setText(R.string.button_browse_content);
                binding.buttonBrowseContent.setEnabled(canBrowseSelectedCategory() && !contentOperationRunning);
                bindImportButtonForCategory(refreshCategory);
                binding.buttonAddMods.setEnabled(canUploadSelectedCategory() && !contentOperationRunning);
                updateContentFilterButtonUi();
                updateContentHint(directory);
                updateContentUpdateButtons();

                // Let the loading overlay draw first, then bind the list. This avoids a blank/frozen
                // screen when a huge pack causes RecyclerView to bind many rows.
                binding.recyclerResourceItems.post(() -> {
                    if (binding == null || isFinishing() || isDestroyed()) return;
                    if (generation != contentRefreshGeneration || selectedCategory != refreshCategory) return;

                    notifyActiveContentAdapter();

                    binding.recyclerResourceItems.post(() -> {
                        contentRefreshRunning = false;
                        updateContentFilterButtonUi();
                        hideContentLoadingOverlayIfIdle();
                    });
                });
            });
        });
    }

    private void showContentLoadingSoon(@NonNull String title, @NonNull String message) {
        cancelPendingContentLoadingOverlay();
        pendingContentLoadingRunnable = () -> {
            pendingContentLoadingRunnable = null;
            if (binding == null || isFinishing() || isDestroyed()) return;
            if (contentRefreshRunning || contentOperationRunning) {
                setContentLoadingOverlayVisible(true, title, message);
            }
        };
        mainHandler.postDelayed(pendingContentLoadingRunnable, 60L);
    }

    private void showContentOperationOverlay(@NonNull String title, @NonNull String message) {
        cancelPendingContentLoadingOverlay();
        setContentLoadingOverlayVisible(true, title, message);
    }

    private void hideContentLoadingOverlayIfIdle() {
        if (contentRefreshRunning || contentOperationRunning) return;
        cancelPendingContentLoadingOverlay();
        setContentLoadingOverlayVisible(false, "", "");
    }

    private void cancelPendingContentLoadingOverlay() {
        if (pendingContentLoadingRunnable != null) {
            mainHandler.removeCallbacks(pendingContentLoadingRunnable);
            pendingContentLoadingRunnable = null;
        }
    }

    private void setContentLoadingOverlayVisible(boolean visible, @NonNull String title, @NonNull String message) {
        if (visible) {
            ensureContentLoadingOverlay();
            if (contentLoadingTitle != null) contentLoadingTitle.setText(title);
            if (contentLoadingMessage != null) contentLoadingMessage.setText(message);
        }

        if (contentLoadingOverlay != null) {
            contentLoadingOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void ensureContentLoadingOverlay() {
        if (contentLoadingOverlay != null) return;

        FrameLayout overlay = new FrameLayout(this);
        overlay.setVisibility(View.GONE);
        overlay.setClickable(true);
        overlay.setFocusable(true);
        overlay.setPadding(dp(24), dp(24), dp(24), dp(24));
        overlay.setBackgroundColor(0x99000000);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(26), dp(22), dp(26), dp(22));

        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setColor(resolveThemeColor(android.R.attr.colorBackground, 0xFF20242B));
        cardBackground.setCornerRadius(dp(24));
        card.setBackground(cardBackground);

        ProgressBar progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        card.addView(progressBar, new LinearLayout.LayoutParams(dp(44), dp(44)));

        contentLoadingTitle = new TextView(this);
        contentLoadingTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        contentLoadingTitle.setTypeface(contentLoadingTitle.getTypeface(), android.graphics.Typeface.BOLD);
        contentLoadingTitle.setTextColor(resolveThemeColor(android.R.attr.textColorPrimary, Color.WHITE));
        contentLoadingTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        titleParams.topMargin = dp(12);
        card.addView(contentLoadingTitle, titleParams);

        contentLoadingMessage = new TextView(this);
        contentLoadingMessage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        contentLoadingMessage.setTextColor(resolveThemeColor(android.R.attr.textColorSecondary, Color.LTGRAY));
        contentLoadingMessage.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        messageParams.topMargin = dp(6);
        card.addView(contentLoadingMessage, messageParams);

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                Math.min(dp(360), Math.max(dp(260), getResources().getDisplayMetrics().widthPixels - dp(72))),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        overlay.addView(card, cardParams);

        getWindow().addContentView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        contentLoadingOverlay = overlay;
    }

    private void setContentOperationInProgress(boolean busy, @NonNull String title, @NonNull String message) {
        contentOperationRunning = busy;
        if (busy) {
            showContentOperationOverlay(title, message);
        } else {
            hideContentLoadingOverlayIfIdle();
        }

        if (binding == null) return;
        binding.buttonBrowseContent.setEnabled(!busy && canBrowseSelectedCategory());
        binding.buttonAddMods.setEnabled(!busy && canUploadSelectedCategory());
        updateCategoryActionVisibility(selectedCategory);
        binding.buttonCheckContentUpdates.setEnabled(!busy && canCheckUpdatesForSelectedCategory());
        binding.buttonUpdateAllContent.setEnabled(!busy && hasAvailableUpdatesForSelectedCategory());
        updateContentFilterButtonUi();
        binding.recyclerResourceItems.setAlpha(busy ? 0.55f : 1.0f);
        updateContentPagerUi();
        updateContentSelectionUi();
    }

    private void updateContentHint(@NonNull File directory) {
        int totalCount = countContentItemsMatchingVisibilityFilter();
        int filteredCount = filteredContentItems.size();
        String pluralLabel = getCategoryPluralLabel(selectedCategory);

        String countText;
        if (filteredCount == 0) {
            if (contentVisibilityFilter == ContentVisibilityFilter.ENABLED_ONLY) {
                countText = getString(R.string.instance_content_empty_enabled_value, pluralLabel);
            } else if (contentVisibilityFilter == ContentVisibilityFilter.DISABLED_ONLY) {
                countText = getString(R.string.instance_content_empty_disabled_value, pluralLabel);
            } else {
                countText = getString(R.string.instance_content_empty_value, pluralLabel);
            }
        } else {
            int start = Math.min(filteredCount, contentCurrentPage * CONTENT_PAGE_SIZE + 1);
            int end = Math.min(filteredCount, start + contentItems.size() - 1);
            if (!isBlank(contentSearchQuery)) {
                countText = getString(R.string.instance_content_page_search_value, start, end, filteredCount, totalCount, pluralLabel.toLowerCase(Locale.US));
            } else if (filteredCount > CONTENT_PAGE_SIZE && selectedCategory != ResourceCategory.SCREENSHOTS) {
                countText = getString(R.string.instance_content_page_count_value, start, end, filteredCount, pluralLabel.toLowerCase(Locale.US));
            } else {
                countText = getString(R.string.instance_content_count_value, filteredCount, pluralLabel);
            }
        }

        if (selectedCategory == ResourceCategory.MODS && !supportsMods()) {
            binding.textModsHint.setText(getString(R.string.mods_vanilla_hint));
        } else {
            binding.textModsHint.setText(countText);
        }
    }

    private void requestContentSearchFilter(boolean notifyAdapter) {
        if (!notifyAdapter) {
            applyContentSearchFilter(false);
            return;
        }

        if (contentSearchFilterApplyQueued) {
            return;
        }

        contentSearchFilterApplyQueued = true;
        mainHandler.post(this::runQueuedContentSearchFilter);
    }

    private void runQueuedContentSearchFilter() {
        contentSearchFilterApplyQueued = false;
        if (binding == null || isFinishing() || isDestroyed()) return;

        RecyclerView recyclerView = binding.recyclerResourceItems;
        if (recyclerView != null
                && (recyclerView.isComputingLayout()
                || recyclerView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE)) {
            contentSearchFilterApplyQueued = true;
            recyclerView.postDelayed(this::runQueuedContentSearchFilter, 64L);
            return;
        }

        applyContentSearchFilter(true);
    }

    private void applyContentSearchFilter(boolean notifyAdapter) {
        if (binding == null) return;

        if (notifyAdapter) {
            RecyclerView recyclerView = binding.recyclerResourceItems;
            if (recyclerView != null
                    && (recyclerView.isComputingLayout()
                    || recyclerView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE)) {
                requestContentSearchFilter(true);
                return;
            }
        }

        String query = contentSearchQuery == null ? "" : contentSearchQuery.trim();
        filteredContentItems.clear();

        for (InstanceContentItem item : allContentItems) {
            if (!matchesContentVisibilityFilter(item)) continue;
            if (query.isEmpty() || matchesContentSearch(item, query)) {
                filteredContentItems.add(item);
            }
        }

        clampContentCurrentPage();
        applyContentPage();
        updateContentHint(getDirectoryForCategory(selectedCategory));
        updateContentUpdateButtons();
        updateContentPagerUi();

        if (notifyAdapter) {
            notifyActiveContentAdapter();
            scrollContentListToTop();
        }
    }

    private void setContentPage(int page) {
        int oldPage = contentCurrentPage;
        contentCurrentPage = page;
        clampContentCurrentPage();
        if (oldPage == contentCurrentPage && !contentItems.isEmpty()) return;
        applyContentPage();
        updateContentHint(getDirectoryForCategory(selectedCategory));
        updateContentPagerUi();
        updateContentSelectionUi();
        notifyActiveContentAdapter();
        scrollContentListToTop();
    }

    private void scrollContentListToTop() {
        if (binding == null || binding.recyclerResourceItems == null) return;
        RecyclerView recyclerView = binding.recyclerResourceItems;
        recyclerView.stopScroll();
        RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
        if (layoutManager instanceof LinearLayoutManager) {
            ((LinearLayoutManager) layoutManager).scrollToPositionWithOffset(0, 0);
        } else {
            recyclerView.scrollToPosition(0);
        }
        recyclerView.post(() -> {
            RecyclerView.LayoutManager postedLayoutManager = recyclerView.getLayoutManager();
            if (postedLayoutManager instanceof LinearLayoutManager) {
                ((LinearLayoutManager) postedLayoutManager).scrollToPositionWithOffset(0, 0);
            } else {
                recyclerView.scrollToPosition(0);
            }
        });
    }

    private void clampContentCurrentPage() {
        int pageCount = getContentPageCount();
        if (contentCurrentPage < 0) contentCurrentPage = 0;
        if (pageCount <= 0) {
            contentCurrentPage = 0;
        } else if (contentCurrentPage >= pageCount) {
            contentCurrentPage = pageCount - 1;
        }
    }

    private int getContentPageCount() {
        if (filteredContentItems.isEmpty()) return 0;
        if (selectedCategory == ResourceCategory.SCREENSHOTS) return 1;
        return Math.max(1, (filteredContentItems.size() + CONTENT_PAGE_SIZE - 1) / CONTENT_PAGE_SIZE);
    }

    private void applyContentPage() {
        contentPageGeneration++;
        contentItems.clear();
        if (filteredContentItems.isEmpty()) return;
        if (selectedCategory == ResourceCategory.SCREENSHOTS) {
            contentItems.addAll(filteredContentItems);
            return;
        }
        int start = Math.max(0, contentCurrentPage * CONTENT_PAGE_SIZE);
        int end = Math.min(filteredContentItems.size(), start + CONTENT_PAGE_SIZE);
        for (int i = start; i < end; i++) {
            contentItems.add(filteredContentItems.get(i));
        }
    }

    private void updateContentPagerUi() {
        if (binding == null || binding.layoutContentPager == null) return;
        int pageCount = getContentPageCount();
        boolean showPager = pageCount > 1;
        binding.layoutContentPager.setVisibility(showPager ? View.VISIBLE : View.GONE);
        if (!showPager) return;

        int filteredCount = filteredContentItems.size();
        int start = Math.min(filteredCount, contentCurrentPage * CONTENT_PAGE_SIZE + 1);
        int end = Math.min(filteredCount, start + contentItems.size() - 1);
        binding.textContentPageStatus.setText(getString(R.string.instance_content_page_status, contentCurrentPage + 1, pageCount, start, end, filteredCount));
        binding.buttonContentPagePrevious.setEnabled(contentCurrentPage > 0 && !contentOperationRunning);
        binding.buttonContentPageNext.setEnabled(contentCurrentPage < pageCount - 1 && !contentOperationRunning);
    }

    private void sortContentItemsForCurrentUpdateState() {
        if (selectedCategory == ResourceCategory.SCREENSHOTS) {
            allContentItems.sort((left, right) -> {
                int modifiedCompare = Long.compare(right.file.lastModified(), left.file.lastModified());
                return modifiedCompare != 0
                        ? modifiedCompare
                        : left.title.compareToIgnoreCase(right.title);
            });
            return;
        }
        allContentItems.sort((left, right) -> {
            int leftUpdate = getUpdateSortRank(left);
            int rightUpdate = getUpdateSortRank(right);
            if (leftUpdate != rightUpdate) return Integer.compare(leftUpdate, rightUpdate);
            int directoryCompare = Boolean.compare(!left.file.isDirectory(), !right.file.isDirectory());
            if (directoryCompare != 0) return directoryCompare;
            return left.title.compareToIgnoreCase(right.title);
        });
    }

    private int getUpdateSortRank(@NonNull InstanceContentItem item) {
        UpdateState state = getUpdateStateForItem(item);
        if (state == UpdateState.UPDATE_AVAILABLE) return 0;
        if (state == UpdateState.UPDATING || state == UpdateState.CHECKING) return 1;
        if (state == UpdateState.ERROR) return 2;
        return 3;
    }

    private boolean matchesContentSearch(@NonNull InstanceContentItem item, @NonNull String rawQuery) {
        String query = normalizeSearchText(rawQuery);
        if (query.isEmpty()) return true;

        String metadata = contentSearchMetadata.get(safeCanonicalPath(item.file));
        String combined = normalizeSearchText(
                item.title + " "
                        + item.file.getName() + " "
                        + stripExtension(item.file.getName()) + " "
                        + item.category.name() + " "
                        + (metadata == null ? "" : metadata)
        );

        for (String token : query.split(" ")) {
            if (token.trim().isEmpty()) continue;
            if (!combined.contains(token.trim())) return false;
        }
        return true;
    }

    @NonNull
    private String normalizeSearchText(@Nullable String value) {
        if (value == null) return "";
        String normalized = value.toLowerCase(Locale.US)
                .replace('_', ' ')
                .replace('-', ' ')
                .replace('.', ' ')
                .replace('+', ' ')
                .trim();
        while (normalized.contains("  ")) normalized = normalized.replace("  ", " ");
        return normalized;
    }

    private void rememberContentSearchMetadata(@NonNull InstanceContentItem item, @Nullable String displayName) {
        if (isBlank(displayName)) return;

        contentSearchMetadata.put(safeCanonicalPath(item.file), displayName.trim());

        // Async jar metadata can make a previously hidden result match by friendly mod name.
        // Do not immediately rebuild the visible RecyclerView when the row is already visible,
        // because that cancels taps on update/delete/enable controls while the user is searching.
        if (!isBlank(contentSearchQuery) && !isContentItemCurrentlyVisible(item)) {
            requestMetadataSearchFilter();
        }
    }

    private boolean isContentItemCurrentlyVisible(@NonNull InstanceContentItem item) {
        return findContentItemIndexByCanonicalPath(contentItems, safeCanonicalPath(item.file)) >= 0;
    }

    private int countContentItemsMatchingVisibilityFilter() {
        int count = 0;
        for (InstanceContentItem item : allContentItems) {
            if (matchesContentVisibilityFilter(item)) count++;
        }
        return count;
    }

    private boolean matchesContentVisibilityFilter(@NonNull InstanceContentItem item) {
        if (contentVisibilityFilter == ContentVisibilityFilter.ALL) return true;
        if (!item.category.supportsDisableToggle) return false;

        boolean enabled = isContentItemEnabled(item.file);
        return contentVisibilityFilter == ContentVisibilityFilter.ENABLED_ONLY
                ? enabled
                : !enabled;
    }

    private void requestMetadataSearchFilter() {
        cancelPendingMetadataSearchFilter();
        pendingMetadataSearchFilterRunnable = () -> {
            pendingMetadataSearchFilterRunnable = null;
            requestContentSearchFilter(true);
        };
        mainHandler.postDelayed(pendingMetadataSearchFilterRunnable, 250L);
    }

    private void cancelPendingMetadataSearchFilter() {
        if (pendingMetadataSearchFilterRunnable == null) return;
        mainHandler.removeCallbacks(pendingMetadataSearchFilterRunnable);
        pendingMetadataSearchFilterRunnable = null;
    }

    private boolean shouldShowFileForCategory(@NonNull ResourceCategory category, @NonNull File file) {
        if (file.isHidden()) return false;
        String name = file.getName().toLowerCase(Locale.US);
        switch (category) {
            case MODS:
                return file.isFile() && (name.endsWith(".jar") || name.endsWith(".jar.disabled"));
            case SHADERPACKS:
                return file.isDirectory() || (file.isFile() && (name.endsWith(".zip") || name.endsWith(".zip.disabled")));
            case RESOURCEPACKS:
                return file.isDirectory() || (file.isFile() && (name.endsWith(".zip") || name.endsWith(".zip.disabled")));
            case WORLDS:
                return file.isDirectory() && new File(file, "level.dat").isFile();
            case SCREENSHOTS:
                return file.isFile() && (name.endsWith(".png")
                        || name.endsWith(".jpg")
                        || name.endsWith(".jpeg")
                        || name.endsWith(".webp"));
            default:
                return false;
        }
    }

    @NonNull
    private File getDirectoryForCategory(@NonNull ResourceCategory category) {
        switch (category) {
            case MODS:
                return modsDirectory;
            case SHADERPACKS:
                return shaderpacksDirectory;
            case RESOURCEPACKS:
                return resourcepacksDirectory;
            case WORLDS:
                return worldsDirectory;
            case SCREENSHOTS:
                return screenshotsDirectory;
            default:
                return gameDirectory;
        }
    }

    private boolean canUploadSelectedCategory() {
        if (selectedCategory == ResourceCategory.SCREENSHOTS) return false;
        return selectedCategory != ResourceCategory.MODS || supportsMods();
    }

    private boolean canBrowseSelectedCategory() {
        if (selectedCategory == ResourceCategory.SCREENSHOTS) return false;
        // Vanilla instances cannot install or browse mods. Resource packs, shader packs,
        // and worlds are still allowed because they do not require a mod loader.
        return selectedCategory != ResourceCategory.MODS || supportsMods();
    }

    private boolean supportsMods() {
        return !"vanilla".equalsIgnoreCase(loader);
    }

    private void browseSelectedContent() {
        if (!canBrowseSelectedCategory()) {
            if (selectedCategory == ResourceCategory.MODS && !supportsMods()) {
                Toast.makeText(this, R.string.mods_vanilla_hint, Toast.LENGTH_LONG).show();
            }
            return;
        }

        Intent intent = new Intent(this, ContentBrowserActivity.class);
        intent.putExtra(EXTRA_INSTANCE_ID, instanceId);
        intent.putExtra(EXTRA_INSTANCE_NAME, instanceName);
        intent.putExtra(EXTRA_INSTANCE_LOADER, loader);
        intent.putExtra(EXTRA_BASE_VERSION_ID, baseVersionId);
        intent.putExtra(EXTRA_MINECRAFT_VERSION_ID, minecraftVersionId);
        intent.putExtra(EXTRA_VERSION_TYPE, versionType);
        intent.putExtra(EXTRA_ROOT_DIRECTORY, rootDirectory != null ? rootDirectory.getAbsolutePath() : "");
        intent.putExtra(EXTRA_GAME_DIRECTORY, gameDirectory != null ? gameDirectory.getAbsolutePath() : "");
        intent.putExtra(EXTRA_ICON_FILE, iconFile != null ? iconFile.getAbsolutePath() : "");
        intent.putExtra(EXTRA_CONTENT_CATEGORY, selectedCategory.name().toLowerCase(Locale.US));

        try {
            startActivity(intent);
        } catch (ActivityNotFoundException throwable) {
            Toast.makeText(
                    this,
                    getString(R.string.instance_content_browser_not_ready, getString(selectedCategory.tabTitleRes)),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void showInstalledContentInfoDialog(@NonNull InstanceContentItem item) {
        if (item.category != ResourceCategory.MODS) return;

        final InstanceContentItem actionItem = resolveContentItemForAction(item);
        if (openInstalledContentProjectDetails(actionItem)) return;

        showInstalledContentLocalInfoDialog(actionItem);
    }

    private boolean openInstalledContentProjectDetails(@NonNull InstanceContentItem item) {
        JSONObject entry = getInstalledEntryForItem(item);
        if (entry == null) return false;

        ModManagerContentType managerType = toModManagerContentType(item.category);
        if (managerType == null) return false;

        ModManagerSource source = ModManagerManifest.getSource(entry);
        if (source == ModManagerSource.UNKNOWN) source = getInstalledSourceForItem(item);
        if (source != ModManagerSource.MODRINTH && source != ModManagerSource.CURSEFORGE) return false;

        String projectId = getProjectIdFromEntry(entry);
        if (isBlank(projectId)) return false;

        Intent intent = new Intent(this, ContentProjectDetailsActivity.class);
        intent.putExtra(EXTRA_INSTANCE_ID, instanceId);
        intent.putExtra(EXTRA_INSTANCE_NAME, instanceName);
        intent.putExtra(EXTRA_INSTANCE_LOADER, loader);
        intent.putExtra(EXTRA_BASE_VERSION_ID, baseVersionId);
        intent.putExtra(EXTRA_MINECRAFT_VERSION_ID, getGameVersionIdForContent());
        intent.putExtra(EXTRA_VERSION_TYPE, versionType);
        intent.putExtra(EXTRA_ROOT_DIRECTORY, rootDirectory != null ? rootDirectory.getAbsolutePath() : "");
        intent.putExtra(EXTRA_GAME_DIRECTORY, gameDirectory != null ? gameDirectory.getAbsolutePath() : "");
        intent.putExtra(EXTRA_ICON_FILE, iconFile != null ? iconFile.getAbsolutePath() : "");
        intent.putExtra(EXTRA_ISOLATED, isolated);
        intent.putExtra(EXTRA_CONTENT_CATEGORY, managerType.getIntentValue());
        intent.putExtra(ContentBrowserActivity.EXTRA_PROJECT_ID, projectId);
        intent.putExtra(ContentBrowserActivity.EXTRA_PROJECT_SLUG, resolveProjectSlugFromInstalledEntry(entry));
        intent.putExtra(ContentBrowserActivity.EXTRA_PROJECT_TITLE, resolveProjectTitleFromInstalledEntry(entry, item));
        intent.putExtra(ContentBrowserActivity.EXTRA_PROJECT_TYPE, managerType.getIntentValue());
        intent.putExtra(ContentBrowserActivity.EXTRA_PROJECT_ICON_URL, resolveIconUrlFromEntry(entry));
        intent.putExtra(ContentBrowserActivity.EXTRA_PROJECT_SOURCE, source.getId());

        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException throwable) {
            Toast.makeText(
                    this,
                    getString(R.string.instance_content_browser_not_ready, getString(item.category.tabTitleRes)),
                    Toast.LENGTH_LONG
            ).show();
            return true;
        }
    }

    @NonNull
    private String resolveProjectSlugFromInstalledEntry(@NonNull JSONObject entry) {
        return firstNonBlank(
                entry.optString("slug", ""),
                entry.optString("projectSlug", ""),
                entry.optString("platformProjectSlug", ""),
                entry.optString("modrinthProjectSlug", ""),
                entry.optString("modrinth_project_slug", ""),
                entry.optString("curseForgeProjectSlug", ""),
                entry.optString("curseforgeProjectSlug", ""),
                firstNestedProjectValue(entry, "slug"),
                firstNestedProjectValue(entry, "projectSlug")
        );
    }

    @NonNull
    private String resolveProjectTitleFromInstalledEntry(
            @NonNull JSONObject entry,
            @NonNull InstanceContentItem item
    ) {
        return firstNonBlank(
                entry.optString("title", ""),
                entry.optString("name", ""),
                entry.optString("projectTitle", ""),
                entry.optString("displayName", ""),
                firstNestedProjectValue(entry, "title"),
                firstNestedProjectValue(entry, "name"),
                item.title,
                stripDisabledSuffix(item.file.getName())
        );
    }

    @NonNull
    private String firstNestedProjectValue(@NonNull JSONObject entry, @NonNull String key) {
        String value = firstNestedString(entry.optJSONObject("project"), key);
        if (!isBlank(value)) return value;
        value = firstNestedString(entry.optJSONObject("modrinthProject"), key);
        if (!isBlank(value)) return value;
        value = firstNestedString(entry.optJSONObject("curseForgeProject"), key);
        if (!isBlank(value)) return value;
        value = firstNestedString(entry.optJSONObject("curseforgeProject"), key);
        if (!isBlank(value)) return value;
        return firstNestedString(entry.optJSONObject("data"), key);
    }

    @NonNull
    private String firstNestedString(@Nullable JSONObject object, @NonNull String key) {
        return object == null ? "" : object.optString(key, "").trim();
    }

    private void showInstalledContentLocalInfoDialog(@NonNull InstanceContentItem actionItem) {
        Toast.makeText(this, "Loading mod info...", Toast.LENGTH_SHORT).show();

        iconExecutor.execute(() -> {
            final String title = resolveInstalledContentInfoTitle(actionItem);
            final String message = buildInstalledContentInfoMessage(actionItem, title);
            mainHandler.post(() -> {
                if (binding == null || isFinishing() || isDestroyed()) return;
                androidx.appcompat.app.AlertDialog dialog = LauncherDialogStyle.showStyledMessageDialog(
                        this,
                        title,
                        message,
                        "Close",
                        (unusedDialog, which) -> { },
                        "Open Folder"
                );
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> {
                    dialog.dismiss();
                    File folder = getDirectoryForCategory(actionItem.category);
                    if (!folder.exists()) folder.mkdirs();
                    if (!openInstanceFolderInFilesApp(folder)) showFolderPathFallback(folder);
                });
            });
        });
    }

    @NonNull
    private String resolveInstalledContentInfoTitle(@NonNull InstanceContentItem item) {
        if (item.category == ResourceCategory.MODS && item.file.isFile()) {
            String nestedOrEmbeddedName = ModJarMetadataExtractor.readDisplayName(item.file);
            if (!isBlank(nestedOrEmbeddedName)) return nestedOrEmbeddedName.trim();
        }
        String displayTitle = resolveDisplayTitle(item.file, item.category);
        return isBlank(displayTitle) ? item.title : displayTitle;
    }

    @NonNull
    private String buildInstalledContentInfoMessage(@NonNull InstanceContentItem item, @NonNull String displayTitle) {
        StringBuilder message = new StringBuilder();
        appendInfoLine(message, "Name", displayTitle);
        appendInfoLine(message, "File", item.file.getName());
        appendInfoLine(message, "Size", item.file.isFile() ? formatFileSize(item.file.length()) : "Folder");
        appendInfoLine(message, "Status", item.category.supportsDisableToggle && !isContentItemEnabled(item.file) ? "Disabled" : "Enabled");
        appendInfoLine(message, "Source", getInstalledSourceForItem(item).getDisplayName());

        JSONObject entry = getInstalledEntryForItem(item);
        if (entry != null) {
            appendInfoLine(message, "Installed version", firstNonBlank(
                    entry.optString("versionNumber", ""),
                    entry.optString("version_number", ""),
                    entry.optString("version", ""),
                    entry.optString("fileName", "")
            ));
            appendInfoLine(message, "Project ID", getProjectIdFromEntry(entry));
        }

        String modDetails = readModDetailsForInfoDialog(item.file);
        if (!isBlank(modDetails)) {
            message.append("\nMod metadata\n").append(modDetails.trim()).append('\n');
        }

        appendInfoLine(message, "Path", item.file.getAbsolutePath());
        return message.toString().trim();
    }

    private void appendInfoLine(@NonNull StringBuilder builder, @NonNull String label, @Nullable String value) {
        if (isBlank(value)) return;
        builder.append(label).append(": ").append(value.trim()).append('\n');
    }

    @NonNull
    private String readModDetailsForInfoDialog(@NonNull File file) {
        if (!file.isFile()) return "";
        try (ZipFile zip = new ZipFile(file)) {
            String fabricJson = readZipEntryText(zip, "fabric.mod.json");
            if (!isBlank(fabricJson)) return readFabricDetailsForInfoDialog(fabricJson);

            String quiltJson = readZipEntryText(zip, "quilt.mod.json");
            if (!isBlank(quiltJson)) return readQuiltDetailsForInfoDialog(quiltJson);

            String forgeToml = readZipEntryText(zip, "META-INF/mods.toml");
            if (isBlank(forgeToml)) forgeToml = readZipEntryText(zip, "META-INF/neoforge.mods.toml");
            if (!isBlank(forgeToml)) return readForgeTomlDetailsForInfoDialog(forgeToml);

            String mcmodInfo = readZipEntryText(zip, "mcmod.info");
            if (!isBlank(mcmodInfo)) return readMcmodInfoDetailsForInfoDialog(mcmodInfo);
        } catch (Throwable throwable) {
            return "Unable to read embedded metadata: " + readableError(throwable);
        }
        return "No embedded mod metadata was found in this jar.";
    }

    @NonNull
    private String readFabricDetailsForInfoDialog(@NonNull String jsonText) {
        StringBuilder builder = new StringBuilder();
        try {
            JSONObject json = new JSONObject(jsonText);
            appendInfoLine(builder, "Loader", "Fabric");
            appendInfoLine(builder, "Mod ID", json.optString("id", ""));
            appendInfoLine(builder, "Version", json.optString("version", ""));
            appendInfoLine(builder, "Description", json.optString("description", ""));
            appendInfoLine(builder, "Authors", jsonValueToReadableString(json.opt("authors")));
            appendInfoLine(builder, "Contact", jsonValueToReadableString(json.opt("contact")));
        } catch (Throwable ignored) {
            appendInfoLine(builder, "Loader", "Fabric");
            appendInfoLine(builder, "Mod ID", extractJsonString(jsonText, "id"));
            appendInfoLine(builder, "Version", extractJsonString(jsonText, "version"));
            appendInfoLine(builder, "Description", extractJsonString(jsonText, "description"));
        }
        return builder.toString();
    }

    @NonNull
    private String readQuiltDetailsForInfoDialog(@NonNull String jsonText) {
        StringBuilder builder = new StringBuilder();
        try {
            JSONObject root = new JSONObject(jsonText);
            JSONObject quilt = root.optJSONObject("quilt_loader");
            JSONObject metadata = quilt == null ? null : quilt.optJSONObject("metadata");
            appendInfoLine(builder, "Loader", "Quilt");
            if (quilt != null) {
                appendInfoLine(builder, "Mod ID", quilt.optString("id", ""));
                appendInfoLine(builder, "Version", quilt.optString("version", ""));
            }
            if (metadata != null) {
                appendInfoLine(builder, "Name", metadata.optString("name", ""));
                appendInfoLine(builder, "Description", metadata.optString("description", ""));
                appendInfoLine(builder, "Contributors", jsonValueToReadableString(metadata.opt("contributors")));
            }
        } catch (Throwable ignored) {
            appendInfoLine(builder, "Loader", "Quilt");
            appendInfoLine(builder, "Mod ID", extractJsonString(jsonText, "id"));
            appendInfoLine(builder, "Version", extractJsonString(jsonText, "version"));
        }
        return builder.toString();
    }

    @NonNull
    private String readForgeTomlDetailsForInfoDialog(@NonNull String tomlText) {
        StringBuilder builder = new StringBuilder();
        appendInfoLine(builder, "Loader", "Forge / NeoForge");
        appendInfoLine(builder, "Mod ID", extractTomlString(tomlText, "modId"));
        appendInfoLine(builder, "Version", extractTomlString(tomlText, "version"));
        appendInfoLine(builder, "Display name", extractTomlString(tomlText, "displayName"));
        appendInfoLine(builder, "Description", extractTomlString(tomlText, "description"));
        appendInfoLine(builder, "Authors", extractTomlString(tomlText, "authors"));
        appendInfoLine(builder, "License", extractTomlString(tomlText, "license"));
        return builder.toString();
    }

    @NonNull
    private String readMcmodInfoDetailsForInfoDialog(@NonNull String jsonText) {
        StringBuilder builder = new StringBuilder();
        try {
            Object parsed = jsonText.trim().startsWith("[") ? new JSONArray(jsonText) : new JSONObject(jsonText);
            JSONObject json = parsed instanceof JSONArray && ((JSONArray) parsed).length() > 0
                    ? ((JSONArray) parsed).optJSONObject(0)
                    : (parsed instanceof JSONObject ? (JSONObject) parsed : null);
            appendInfoLine(builder, "Loader", "Legacy Forge");
            if (json != null) {
                appendInfoLine(builder, "Mod ID", json.optString("modid", ""));
                appendInfoLine(builder, "Version", json.optString("version", ""));
                appendInfoLine(builder, "Description", json.optString("description", ""));
                appendInfoLine(builder, "Authors", jsonValueToReadableString(json.opt("authorList")));
            }
        } catch (Throwable ignored) {
            appendInfoLine(builder, "Loader", "Legacy Forge");
            appendInfoLine(builder, "Mod ID", extractJsonString(jsonText, "modid"));
            appendInfoLine(builder, "Version", extractJsonString(jsonText, "version"));
            appendInfoLine(builder, "Description", extractJsonString(jsonText, "description"));
        }
        return builder.toString();
    }

    @NonNull
    private String jsonValueToReadableString(@Nullable Object value) {
        if (value == null || JSONObject.NULL.equals(value)) return "";
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            ArrayList<String> values = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                String readable = jsonValueToReadableString(array.opt(i));
                if (!isBlank(readable)) values.add(readable);
            }
            return joinStrings(values, ", ");
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            ArrayList<String> values = new ArrayList<>();
            JSONArray names = object.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String key = names.optString(i, "");
                    if (isBlank(key)) continue;
                    Object nested = object.opt(key);
                    String readable = jsonValueToReadableString(nested);
                    values.add(isBlank(readable) ? key : key + "=" + readable);
                }
            }
            return joinStrings(values, ", ");
        }
        return String.valueOf(value).trim();
    }

    @NonNull
    private String joinStrings(@NonNull ArrayList<String> values, @NonNull String delimiter) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (isBlank(value)) continue;
            if (builder.length() > 0) builder.append(delimiter);
            builder.append(value.trim());
        }
        return builder.toString();
    }

    private void showDeleteContentItemDialog(@NonNull InstanceContentItem item) {
        InstanceContentItem actionItem = resolveContentItemForAction(item);
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.instance_content_delete_title, actionItem.title))
                .setMessage(getString(R.string.instance_content_delete_message, actionItem.file.getAbsolutePath()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.button_delete_forever, (dialog, which) -> deleteContentItem(actionItem))
                .show();
    }

    private void deleteContentItem(@NonNull InstanceContentItem item) {
        final InstanceContentItem actionItem = resolveContentItemForAction(item);
        Thread thread = new Thread(() -> {
            try {
                deleteFileOrDirectory(actionItem.file);
                ModManagerContentType managerType = toModManagerContentType(actionItem.category);
                if (gameDirectory != null && managerType != null) {
                    ModManagerManifest.removeEntryForFile(gameDirectory, managerType, actionItem.file);
                }
                runOnUiThread(() -> {
                    refreshContentList();
                    Toast.makeText(
                            this,
                            getString(R.string.instance_content_delete_success, actionItem.title),
                            Toast.LENGTH_SHORT
                    ).show();
                });
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to delete content item " + actionItem.file.getAbsolutePath(), throwable);
                runOnUiThread(() -> {
                    String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
                    Toast.makeText(this, getString(R.string.instance_content_delete_failed, actionItem.title, message), Toast.LENGTH_LONG).show();
                });
            }
        }, "Delete Instance Content");
        thread.start();
    }

    private void deleteFileOrDirectory(@NonNull File file) throws IOException {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteFileOrDirectory(child);
                }
            }
        }
        if (!file.delete() && file.exists()) {
            throw new IOException("Unable to delete: " + file.getAbsolutePath());
        }
    }

    private void setContentItemEnabled(@NonNull InstanceContentItem item, boolean enabled) {
        item = resolveContentItemForAction(item);
        if (!item.category.supportsDisableToggle || item.file.isDirectory()) {
            return;
        }
        if (contentOperationRunning) {
            return;
        }

        boolean currentlyEnabled = isContentItemEnabled(item.file);
        if (currentlyEnabled == enabled) {
            return;
        }

        File parent = item.file.getParentFile();
        if (parent == null) {
            Toast.makeText(this, getString(R.string.instance_content_toggle_failed, item.file.getName()), Toast.LENGTH_LONG).show();
            notifyContentItemChangedByPath(item.file);
            return;
        }

        File oldFile = item.file;
        File target = enabled
                ? new File(parent, removeDisabledSuffix(oldFile.getName()))
                : new File(parent, oldFile.getName() + ".disabled");
        ModManagerContentType managerType = toModManagerContentType(item.category);
        ResourceCategory itemCategory = item.category;
        String oldPath = safeCanonicalPath(oldFile);
        String displayName = stripDisabledSuffix(target.getName());

        setContentOperationInProgress(
                true,
                enabled ? "Enabling content" : "Disabling content",
                displayName
        );

        contentOperationExecutor.execute(() -> {
            Throwable error = null;
            boolean changed = false;

            try {
                if (!oldFile.exists()) {
                    throw new IOException("File no longer exists: " + oldFile.getName());
                }
                if (target.exists()) {
                    throw new IOException("Target already exists: " + target.getName());
                }
                if (!oldFile.renameTo(target)) {
                    throw new IOException("Unable to rename " + oldFile.getName());
                }
                changed = true;

                if (gameDirectory != null && managerType != null) {
                    ModManagerManifest.updateEntryFileTarget(gameDirectory, managerType, oldFile, target);
                }
            } catch (Throwable throwable) {
                error = throwable;
                Logging.e(TAG, "Unable to toggle content item " + oldFile.getAbsolutePath(), throwable);
            }

            Throwable finalError = error;
            boolean finalChanged = changed;
            mainHandler.post(() -> {
                if (binding == null || isFinishing() || isDestroyed()) return;

                if (finalChanged && finalError == null) {
                    replaceContentItemAfterToggle(oldPath, target, itemCategory);
                    Toast.makeText(
                            this,
                            getString(enabled ? R.string.instance_content_enabled_success : R.string.instance_content_disabled_success, displayName),
                            Toast.LENGTH_SHORT
                    ).show();
                } else {
                    notifyContentItemChangedByPath(oldFile);
                    Toast.makeText(
                            this,
                            getString(R.string.instance_content_toggle_failed, displayName) + ": " + (finalError == null ? "Unknown error" : readableError(finalError)),
                            Toast.LENGTH_LONG
                    ).show();
                }

                setContentOperationInProgress(false, "", "");
            });
        });
    }

    private void replaceContentItemAfterToggle(
            @NonNull String oldCanonicalPath,
            @NonNull File newFile,
            @NonNull ResourceCategory category
    ) {
        InstanceContentItem replacement = new InstanceContentItem(newFile, category, resolveDisplayTitle(newFile, category));
        int allIndex = findContentItemIndexByCanonicalPath(allContentItems, oldCanonicalPath);
        if (allIndex >= 0) {
            allContentItems.set(allIndex, replacement);
        }

        int index = findContentItemIndexByCanonicalPath(contentItems, oldCanonicalPath);
        if (index < 0 && allIndex < 0) {
            refreshContentList();
            return;
        }

        contentSearchMetadata.remove(oldCanonicalPath);
        if (contentAdapter != null) {
            contentAdapter.clearTransientCachesForFile(oldCanonicalPath);
        }

        sortContentItemsForCurrentUpdateState();
        applyContentSearchFilter(true);
        updateContentHint(getDirectoryForCategory(category));
        updateContentUpdateButtons();
    }

    private void notifyContentItemChangedByPath(@NonNull File file) {
        int index = findContentItemIndexByCanonicalPath(safeCanonicalPath(file));
        if (index >= 0 && contentAdapter != null) {
            contentAdapter.notifyItemChanged(index);
        }
    }

    private int findContentItemIndexByCanonicalPath(@NonNull String canonicalPath) {
        return findContentItemIndexByCanonicalPath(contentItems, canonicalPath);
    }

    private int findContentItemIndexByCanonicalPath(
            @NonNull ArrayList<InstanceContentItem> items,
            @NonNull String canonicalPath
    ) {
        for (int i = 0; i < items.size(); i++) {
            if (canonicalPath.equals(safeCanonicalPath(items.get(i).file))) {
                return i;
            }
        }
        return -1;
    }

    @NonNull
    private InstanceContentItem resolveContentItemForAction(@NonNull InstanceContentItem item) {
        String canonicalPath = safeCanonicalPath(item.file);

        int visibleIndex = findContentItemIndexByCanonicalPath(contentItems, canonicalPath);
        if (visibleIndex >= 0) return contentItems.get(visibleIndex);

        int allIndex = findContentItemIndexByCanonicalPath(allContentItems, canonicalPath);
        if (allIndex >= 0) return allContentItems.get(allIndex);

        return item;
    }

    private void prepareContentRowAction() {
        cancelPendingMetadataSearchFilter();
        if (binding != null && binding.editTextContentSearch != null && binding.editTextContentSearch.hasFocus()) {
            finishContentSearchInput(binding.editTextContentSearch);
        } else {
            enableFullscreen();
        }
    }

    private void showDeleteContentItemDialogFromRow(@NonNull InstanceContentItem item) {
        prepareContentRowAction();
        showDeleteContentItemDialog(resolveContentItemForAction(item));
    }

    private void setContentItemEnabledFromRow(@NonNull InstanceContentItem item, boolean enabled) {
        prepareContentRowAction();
        setContentItemEnabled(resolveContentItemForAction(item), enabled);
    }

    private void checkSingleContentUpdateFromRow(@NonNull InstanceContentItem item) {
        prepareContentRowAction();
        checkSingleContentUpdate(resolveContentItemForAction(item));
    }

    private void updateSingleContentItemFromRow(@NonNull InstanceContentItem item) {
        prepareContentRowAction();
        updateSingleContentItem(resolveContentItemForAction(item));
    }

    private void clearInstalledContentLookupCaches() {
        installedEntryCache.clear();
        missingInstalledEntryCache.clear();
        modpackInstalledEntriesCache.clear();
        contentSearchMetadata.clear();
    }

    private boolean canCheckUpdatesForSelectedCategory() {
        return toModManagerContentType(selectedCategory) != null && gameDirectory != null;
    }

    private void updateContentUpdateButtons() {
        boolean canCheck = canCheckUpdatesForSelectedCategory();
        binding.buttonCheckContentUpdates.setVisibility(canCheck ? View.VISIBLE : View.GONE);
        binding.buttonCheckContentUpdates.setEnabled(canCheck && !contentOperationRunning);
        boolean hasUpdates = hasAvailableUpdatesForSelectedCategory();
        binding.buttonUpdateAllContent.setVisibility(hasUpdates ? View.VISIBLE : View.GONE);
        binding.buttonUpdateAllContent.setEnabled(hasUpdates && !contentOperationRunning);
        binding.buttonUpdateAllContent.setText(hasUpdates
                ? getString(R.string.instance_content_update_all_count, countAvailableUpdatesForSelectedCategory())
                : getString(R.string.instance_content_update_all));
        binding.buttonCheckContentUpdates.setIconResource(hasUpdates ? R.drawable.ic_update_24 : R.drawable.ic_sync_24);
        updateContentFilterButtonUi();
        updateContentSelectionUi();
    }

    private void updateContentSelectionUi() {
        if (binding == null || binding.layoutContentSelection == null) return;
        if (selectedCategory == ResourceCategory.SCREENSHOTS) {
            binding.layoutContentSelection.setVisibility(View.GONE);
            return;
        }
        int selectedCount = selectedContentKeys.size();
        boolean visible = contentSelectionMode || selectedCount > 0;
        binding.layoutContentSelection.setVisibility(visible ? View.VISIBLE : View.GONE);
        binding.textContentSelectionCount.setText(getString(R.string.instance_content_selected_count, selectedCount));
        boolean hasVisibleItems = !contentItems.isEmpty();
        boolean allVisibleSelected = hasVisibleItems && areAllVisibleContentItemsSelected();
        binding.buttonSelectAllContent.setText(allVisibleSelected
                ? R.string.instance_content_deselect_page
                : R.string.instance_content_select_all);
        binding.buttonSelectAllContent.setContentDescription(getString(allVisibleSelected
                ? R.string.instance_content_deselect_page_description
                : R.string.instance_content_select_all_description));
        binding.buttonSelectAllContent.setEnabled(hasVisibleItems && !contentOperationRunning);
        binding.buttonDeleteSelectedContent.setEnabled(selectedCount > 0 && !contentOperationRunning);
        binding.buttonUpdateSelectedContent.setEnabled(countSelectedUpdateCandidates() > 0 && !contentOperationRunning);
        int enableCandidates = countSelectedToggleCandidates(true);
        int disableCandidates = countSelectedToggleCandidates(false);
        binding.buttonEnableSelectedContent.setVisibility(enableCandidates > 0 ? View.VISIBLE : View.GONE);
        binding.buttonDisableSelectedContent.setVisibility(disableCandidates > 0 ? View.VISIBLE : View.GONE);
        binding.buttonEnableSelectedContent.setEnabled(enableCandidates > 0 && !contentOperationRunning);
        binding.buttonDisableSelectedContent.setEnabled(disableCandidates > 0 && !contentOperationRunning);
    }

    private void clearContentSelection() {
        selectedContentKeys.clear();
        contentSelectionMode = false;
        updateContentSelectionUi();
        if (contentAdapter != null) contentAdapter.notifyDataSetChanged();
    }

    private void toggleSelectAllVisibleContent() {
        if (contentOperationRunning || contentItems.isEmpty()) return;

        boolean deselectVisiblePage = areAllVisibleContentItemsSelected();
        for (InstanceContentItem item : new ArrayList<>(contentItems)) {
            String key = getContentSelectionKey(item);
            if (deselectVisiblePage) {
                selectedContentKeys.remove(key);
            } else {
                selectedContentKeys.add(key);
            }
        }

        contentSelectionMode = !selectedContentKeys.isEmpty();
        updateContentSelectionUi();
        if (contentAdapter != null) contentAdapter.notifyDataSetChanged();
    }

    private boolean areAllVisibleContentItemsSelected() {
        if (contentItems.isEmpty()) return false;
        for (InstanceContentItem item : contentItems) {
            if (!selectedContentKeys.contains(getContentSelectionKey(item))) return false;
        }
        return true;
    }

    private void pruneContentSelectionToLoadedItems() {
        if (selectedContentKeys.isEmpty()) {
            contentSelectionMode = false;
            updateContentSelectionUi();
            return;
        }
        HashSet<String> loaded = new HashSet<>();
        for (InstanceContentItem item : allContentItems) loaded.add(getContentSelectionKey(item));
        selectedContentKeys.retainAll(loaded);
        contentSelectionMode = !selectedContentKeys.isEmpty();
        updateContentSelectionUi();
    }

    private void toggleContentItemSelected(@NonNull InstanceContentItem item) {
        String key = getContentSelectionKey(item);
        contentSelectionMode = true;
        if (selectedContentKeys.contains(key)) {
            selectedContentKeys.remove(key);
        } else {
            selectedContentKeys.add(key);
        }
        if (selectedContentKeys.isEmpty()) contentSelectionMode = false;
        updateContentSelectionUi();
        if (contentAdapter != null) contentAdapter.notifyDataSetChanged();
    }

    private boolean isContentItemSelected(@NonNull InstanceContentItem item) {
        return selectedContentKeys.contains(getContentSelectionKey(item));
    }

    @NonNull
    private String getContentSelectionKey(@NonNull InstanceContentItem item) {
        return item.category.name() + ":" + safeCanonicalPath(item.file);
    }

    @NonNull
    private ArrayList<InstanceContentItem> getSelectedContentItems() {
        ArrayList<InstanceContentItem> selected = new ArrayList<>();
        for (InstanceContentItem item : allContentItems) {
            if (selectedContentKeys.contains(getContentSelectionKey(item))) selected.add(item);
        }
        return selected;
    }

    private int countSelectedUpdateCandidates() {
        int count = 0;
        for (InstanceContentItem item : getSelectedContentItems()) {
            ModManagerContentType managerType = toModManagerContentType(item.category);
            JSONObject entry = getInstalledEntryForItem(item);
            if (managerType == null || entry == null) continue;
            if (updateCandidates.containsKey(buildUpdateKey(managerType, entry))) count++;
        }
        return count;
    }

    private int countSelectedToggleCandidates(boolean enable) {
        int count = 0;
        for (InstanceContentItem item : getSelectedContentItems()) {
            if (!item.category.supportsDisableToggle || item.file.isDirectory()) continue;
            boolean currentlyEnabled = isContentItemEnabled(item.file);
            if (enable ? !currentlyEnabled : currentlyEnabled) count++;
        }
        return count;
    }

    private void updateSelectedContentItemsEnabled(boolean enabled) {
        ArrayList<InstanceContentItem> selected = getSelectedContentItems();
        ArrayList<InstanceContentItem> targets = new ArrayList<>();
        for (InstanceContentItem item : selected) {
            InstanceContentItem actionItem = resolveContentItemForAction(item);
            if (!actionItem.category.supportsDisableToggle || actionItem.file.isDirectory()) continue;
            boolean currentlyEnabled = isContentItemEnabled(actionItem.file);
            if (enabled ? !currentlyEnabled : currentlyEnabled) targets.add(actionItem);
        }

        if (targets.isEmpty()) {
            Toast.makeText(this, enabled ? R.string.instance_content_no_selected_enable_targets : R.string.instance_content_no_selected_disable_targets, Toast.LENGTH_SHORT).show();
            return;
        }

        setContentOperationInProgress(
                true,
                getString(enabled ? R.string.instance_content_enable_selected_title : R.string.instance_content_disable_selected_title),
                getString(R.string.instance_content_selected_count, targets.size())
        );

        contentOperationExecutor.execute(() -> {
            int changed = 0;
            Throwable error = null;
            for (InstanceContentItem item : targets) {
                try {
                    File oldFile = item.file;
                    File parent = oldFile.getParentFile();
                    if (parent == null) throw new IOException("No parent folder for " + oldFile.getName());
                    File target = enabled
                            ? new File(parent, removeDisabledSuffix(oldFile.getName()))
                            : new File(parent, oldFile.getName() + ".disabled");
                    if (!oldFile.exists()) throw new IOException("File no longer exists: " + oldFile.getName());
                    if (target.exists()) throw new IOException("Target already exists: " + target.getName());
                    if (!oldFile.renameTo(target)) throw new IOException("Unable to rename " + oldFile.getName());

                    ModManagerContentType managerType = toModManagerContentType(item.category);
                    if (gameDirectory != null && managerType != null) {
                        ModManagerManifest.updateEntryFileTarget(gameDirectory, managerType, oldFile, target);
                    }
                    changed++;
                } catch (Throwable throwable) {
                    if (error == null) error = throwable;
                    Logging.e(TAG, "Unable to batch-toggle selected content item " + item.file.getAbsolutePath(), throwable);
                }
            }

            int finalChanged = changed;
            Throwable finalError = error;
            mainHandler.post(() -> {
                if (binding == null || isFinishing() || isDestroyed()) return;
                selectedContentKeys.clear();
                contentSelectionMode = false;
                setContentOperationInProgress(false, "", "");
                refreshContentList();
                if (finalError == null) {
                    Toast.makeText(this, getString(enabled ? R.string.instance_content_enable_selected_success : R.string.instance_content_disable_selected_success, finalChanged), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, getString(R.string.instance_content_toggle_selected_failed, finalChanged, readableError(finalError)), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void updateSelectedContentItems() {
        ArrayList<ModManagerUpdateManager.UpdateCandidate> updates = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (InstanceContentItem item : getSelectedContentItems()) {
            ModManagerContentType managerType = toModManagerContentType(item.category);
            JSONObject entry = getInstalledEntryForItem(item);
            if (managerType == null || entry == null) continue;
            String key = buildUpdateKey(managerType, entry);
            ModManagerUpdateManager.UpdateCandidate candidate = updateCandidates.get(key);
            if (candidate != null && seen.add(key)) updates.add(candidate);
        }
        if (updates.isEmpty()) {
            Toast.makeText(this, R.string.instance_content_no_selected_updates, Toast.LENGTH_SHORT).show();
            return;
        }
        updateAllCandidates(updates, false);
    }

    private void showDeleteSelectedContentDialog() {
        ArrayList<InstanceContentItem> selected = getSelectedContentItems();
        if (selected.isEmpty()) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.instance_content_delete_selected_title)
                .setMessage(getString(R.string.instance_content_delete_selected_message, selected.size()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.button_delete_forever, (dialog, which) -> deleteSelectedContentItems(selected))
                .show();
    }

    private void deleteSelectedContentItems(@NonNull ArrayList<InstanceContentItem> selected) {
        if (selected.isEmpty() || contentOperationRunning) return;
        setContentOperationInProgress(true, getString(R.string.instance_content_delete_selected), getString(R.string.instance_content_selected_count, selected.size()));
        contentOperationExecutor.execute(() -> {
            int deleted = 0;
            Throwable error = null;
            for (InstanceContentItem item : selected) {
                try {
                    InstanceContentItem actionItem = resolveContentItemForAction(item);
                    deleteFileOrDirectory(actionItem.file);
                    ModManagerContentType managerType = toModManagerContentType(actionItem.category);
                    if (gameDirectory != null && managerType != null) {
                        ModManagerManifest.removeEntryForFile(gameDirectory, managerType, actionItem.file);
                    }
                    deleted++;
                } catch (Throwable throwable) {
                    if (error == null) error = throwable;
                    Logging.e(TAG, "Unable to delete selected content item " + item.file.getAbsolutePath(), throwable);
                }
            }
            int finalDeleted = deleted;
            Throwable finalError = error;
            mainHandler.post(() -> {
                if (binding == null || isFinishing() || isDestroyed()) return;
                selectedContentKeys.clear();
                contentSelectionMode = false;
                setContentOperationInProgress(false, "", "");
                refreshContentList();
                if (finalError == null) {
                    Toast.makeText(this, getString(R.string.instance_content_delete_selected_success, finalDeleted), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, getString(R.string.instance_content_delete_failed, getString(R.string.instance_content_selected_count, finalDeleted), readableError(finalError)), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private boolean hasAvailableUpdatesForSelectedCategory() {
        return countAvailableUpdatesForSelectedCategory() > 0;
    }

    private int countAvailableUpdatesForSelectedCategory() {
        ModManagerContentType managerType = toModManagerContentType(selectedCategory);
        if (managerType == null) return 0;
        int count = 0;
        String prefix = managerType.name() + ":";
        for (String key : updateCandidates.keySet()) if (key.startsWith(prefix)) count++;
        return count;
    }

    private void checkUpdatesForSelectedCategory() {
        ModManagerContentType managerType = toModManagerContentType(selectedCategory);
        if (managerType == null || gameDirectory == null) {
            Toast.makeText(this, R.string.instance_content_updates_not_supported, Toast.LENGTH_SHORT).show();
            return;
        }
        showUpdateProgressDialog(getString(R.string.instance_content_checking_updates_title), getString(R.string.instance_content_checking_updates_message), true, 1, true);
        Thread thread = new Thread(() -> {
            try {
                markTrackedEntriesUpToDate(managerType);
                ArrayList<ModManagerUpdateManager.UpdateCandidate> updates = ModManagerUpdateManager.checkUpdates(this, gameDirectory, managerType, getGameVersionIdForContent(), loader, new ModManagerUpdateManager.Listener() {
                    @Override public void onStatus(@NonNull String message) { runOnUiThread(() -> setUpdateProgressMessage(message)); }
                    @Override public void onProgress(int current, int total) { runOnUiThread(() -> setUpdateProgress(current, total)); }
                });

                ArrayList<JSONObject> modpackEntries = getModpackInstalledEntriesForType(managerType);
                if (!modpackEntries.isEmpty()) {
                    runOnUiThread(() -> setUpdateProgressMessage("Checking modpack-installed files..."));
                    int totalFallback = Math.max(1, modpackEntries.size());
                    for (int i = 0; i < modpackEntries.size(); i++) {
                        JSONObject modpackEntry = modpackEntries.get(i);
                        File installedFile = resolveFileForInstalledEntry(modpackEntry);
                        if (installedFile != null
                                && ModManagerManifest.getInstalledEntryForFile(gameDirectory, managerType, installedFile) != null) {
                            final int progress = i + 1;
                            runOnUiThread(() -> setUpdateProgress(progress, totalFallback));
                            continue;
                        }

                        ModManagerSource source = ModManagerManifest.getSource(modpackEntry);
                        if (source != ModManagerSource.MODRINTH && source != ModManagerSource.CURSEFORGE) {
                            final int progress = i + 1;
                            runOnUiThread(() -> setUpdateProgress(progress, totalFallback));
                            continue;
                        }

                        try {
                            ModManagerUpdateManager.UpdateCandidate candidate = ModManagerUpdateManager.checkUpdateForEntry(
                                    this,
                                    gameDirectory,
                                    managerType,
                                    modpackEntry,
                                    getGameVersionIdForContent(),
                                    loader
                            );
                            if (candidate != null && !containsUpdateCandidate(updates, candidate)) {
                                updates.add(candidate);
                            }
                        } catch (Throwable throwable) {
                            Logging.i(TAG, "Unable to check modpack-installed file update: " + readableError(throwable));
                        }
                        final int progress = i + 1;
                        runOnUiThread(() -> setUpdateProgress(progress, totalFallback));
                    }
                }

                removeUpdateCandidatesForType(managerType);
                for (ModManagerUpdateManager.UpdateCandidate candidate : updates) {
                    String key = buildUpdateKey(candidate);
                    updateCandidates.put(key, candidate);
                    updateStates.put(key, UpdateState.UPDATE_AVAILABLE);
                    updateMessages.put(key, getString(R.string.instance_content_update_available_value, candidate.latestVersion.versionNumber));
                }
                runOnUiThread(() -> {
                    dismissUpdateProgressDialog();
                    sortContentItemsForCurrentUpdateState();
                    contentCurrentPage = 0;
                    applyContentSearchFilter(false);
                    updateContentUpdateButtons();
                    if (contentAdapter != null) contentAdapter.notifyDataSetChanged();
                    scrollContentListToTop();
                    Toast.makeText(this, updates.isEmpty() ? getString(R.string.instance_content_no_updates_found) : getString(R.string.instance_content_updates_found, updates.size()), Toast.LENGTH_LONG).show();
                });
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to check content updates", throwable);
                runOnUiThread(() -> {
                    dismissUpdateProgressDialog();
                    Toast.makeText(this, getString(R.string.instance_content_update_check_failed, throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName()), Toast.LENGTH_LONG).show();
                });
            }
        }, "Check Instance Content Updates");
        thread.start();
    }

    private void checkSingleContentUpdate(@NonNull InstanceContentItem item) {
        item = resolveContentItemForAction(item);
        ModManagerContentType managerType = toModManagerContentType(item.category);
        if (managerType == null || gameDirectory == null) return;
        JSONObject entry = getInstalledEntryForItem(item);
        if (entry == null) {
            Toast.makeText(this, R.string.instance_content_update_missing_metadata, Toast.LENGTH_LONG).show();
            return;
        }
        ModManagerSource source = ModManagerManifest.getSource(entry);
        if (source != ModManagerSource.MODRINTH && source != ModManagerSource.CURSEFORGE) {
            Toast.makeText(this, R.string.instance_content_update_manual_not_supported, Toast.LENGTH_LONG).show();
            return;
        }
        String key = buildUpdateKey(managerType, entry);
        updateStates.put(key, UpdateState.CHECKING);
        updateMessages.put(key, getString(R.string.instance_content_checking_updates_short));
        if (contentAdapter != null) contentAdapter.notifyDataSetChanged();
        Thread thread = new Thread(() -> {
            try {
                ModManagerUpdateManager.UpdateCandidate candidate = ModManagerUpdateManager.checkUpdateForEntry(this, gameDirectory, managerType, entry, getGameVersionIdForContent(), loader);
                runOnUiThread(() -> {
                    if (candidate == null) {
                        updateCandidates.remove(key);
                        updateStates.put(key, UpdateState.UP_TO_DATE);
                        updateMessages.put(key, getString(R.string.instance_content_up_to_date));
                        Toast.makeText(this, R.string.instance_content_up_to_date, Toast.LENGTH_SHORT).show();
                    } else {
                        String candidateKey = buildUpdateKey(candidate);
                        updateCandidates.put(candidateKey, candidate);
                        updateStates.put(candidateKey, UpdateState.UPDATE_AVAILABLE);
                        updateMessages.put(candidateKey, getString(R.string.instance_content_update_available_value, candidate.latestVersion.versionNumber));
                        contentCurrentPage = 0;
                        Toast.makeText(this, getString(R.string.instance_content_update_available_for, candidate.getDisplayName()), Toast.LENGTH_LONG).show();
                    }
                    sortContentItemsForCurrentUpdateState();
                    applyContentSearchFilter(false);
                    updateContentUpdateButtons();
                    if (contentAdapter != null) contentAdapter.notifyDataSetChanged();
                });
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to check single content update", throwable);
                runOnUiThread(() -> {
                    updateStates.put(key, UpdateState.ERROR);
                    updateMessages.put(key, throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName());
                    Toast.makeText(this, getString(R.string.instance_content_update_check_failed, updateMessages.get(key)), Toast.LENGTH_LONG).show();
                    if (contentAdapter != null) contentAdapter.notifyDataSetChanged();
                });
            }
        }, "Check Content Item Update");
        thread.start();
    }

    private void updateSingleContentItem(@NonNull InstanceContentItem item) {
        item = resolveContentItemForAction(item);
        ModManagerContentType managerType = toModManagerContentType(item.category);
        if (managerType == null || gameDirectory == null) return;
        JSONObject entry = getInstalledEntryForItem(item);
        if (entry == null) {
            Toast.makeText(this, R.string.instance_content_update_missing_metadata, Toast.LENGTH_LONG).show();
            return;
        }
        String key = buildUpdateKey(managerType, entry);
        ModManagerUpdateManager.UpdateCandidate candidate = updateCandidates.get(key);
        if (candidate == null) {
            checkSingleContentUpdate(item);
            return;
        }
        updateCandidate(candidate);
    }

    private void updateAllAvailableForSelectedCategory() {
        ModManagerContentType managerType = toModManagerContentType(selectedCategory);
        if (managerType == null || gameDirectory == null) return;
        ArrayList<ModManagerUpdateManager.UpdateCandidate> updates = new ArrayList<>();
        String prefix = managerType.name() + ":";
        for (Map.Entry<String, ModManagerUpdateManager.UpdateCandidate> entry : updateCandidates.entrySet()) {
            if (entry.getKey().startsWith(prefix)) updates.add(entry.getValue());
        }
        if (updates.isEmpty()) {
            Toast.makeText(this, R.string.instance_content_no_updates_found, Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.instance_content_update_all_title)
                .setMessage(getString(R.string.instance_content_update_all_message, updates.size()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.instance_content_update_all, (dialog, which) -> updateAllCandidates(updates, true))
                .show();
    }

    private void updateCandidate(@NonNull ModManagerUpdateManager.UpdateCandidate candidate) {
        String key = buildUpdateKey(candidate);
        UpdateCleanupPlan cleanupPlan = createUpdateCleanupPlan(candidate);
        updateStates.put(key, UpdateState.UPDATING);
        updateMessages.put(key, getString(R.string.instance_content_updating_value, candidate.latestVersion.versionNumber));
        if (contentAdapter != null) contentAdapter.notifyDataSetChanged();
        showUpdateProgressDialog(getString(R.string.instance_content_updating_title), getString(R.string.instance_content_updating_one, candidate.getDisplayName()), true, 1, false);
        Thread thread = new Thread(() -> {
            try {
                final Throwable[] error = new Throwable[1];
                ModManagerUpdateManager.updateCandidate(this, gameDirectory, getGameVersionIdForContent(), loader, candidate, new ModrinthInstallManager.Listener() {
                    @Override public void onStatus(@NonNull String message) { runOnUiThread(() -> setUpdateProgressMessage(message)); }
                    @Override public void onComplete(@NonNull String message) { runOnUiThread(() -> setUpdateProgressMessage(message)); }
                    @Override public void onError(@NonNull Throwable throwable) { error[0] = throwable; }
                });
                if (error[0] != null) throw error[0];
                final int removedOldFiles = finalizeContentUpdateCleanup(Collections.singletonList(cleanupPlan));
                runOnUiThread(() -> {
                    dismissUpdateProgressDialog();
                    updateCandidates.remove(key); updateStates.remove(key); updateMessages.remove(key);
                    refreshContentList();
                    setResult(RESULT_OK);
                    Toast.makeText(this, getString(R.string.instance_content_update_success, candidate.getDisplayName()) + formatUpdateCleanupSuffix(removedOldFiles), Toast.LENGTH_LONG).show();
                });
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to update content item", throwable);
                runOnUiThread(() -> {
                    dismissUpdateProgressDialog();
                    updateStates.put(key, UpdateState.ERROR);
                    updateMessages.put(key, throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName());
                    if (contentAdapter != null) contentAdapter.notifyDataSetChanged();
                    Toast.makeText(this, getString(R.string.instance_content_update_failed, candidate.getDisplayName(), updateMessages.get(key)), Toast.LENGTH_LONG).show();
                });
            }
        }, "Update Instance Content Item");
        thread.start();
    }

    private void updateAllCandidates(@NonNull ArrayList<ModManagerUpdateManager.UpdateCandidate> updates) {
        updateAllCandidates(updates, false);
    }

    private void updateAllCandidates(@NonNull ArrayList<ModManagerUpdateManager.UpdateCandidate> updates, boolean clearCheckedStateForUpdatedTypes) {
        if (updates.isEmpty() || gameDirectory == null) return;
        ArrayList<UpdateCleanupPlan> cleanupPlans = new ArrayList<>();
        for (ModManagerUpdateManager.UpdateCandidate candidate : updates) {
            cleanupPlans.add(createUpdateCleanupPlan(candidate));
        }

        showUpdateProgressDialog(getString(R.string.instance_content_update_all_title), getString(R.string.instance_content_update_all_wait), false, updates.size(), false);
        for (ModManagerUpdateManager.UpdateCandidate candidate : updates) {
            String key = buildUpdateKey(candidate);
            updateStates.put(key, UpdateState.UPDATING);
            updateMessages.put(key, getString(R.string.instance_content_updating_value, candidate.latestVersion.versionNumber));
        }
        if (contentAdapter != null) contentAdapter.notifyDataSetChanged();
        Thread thread = new Thread(() -> {
            try {
                ModManagerUpdateManager.updateAll(this, gameDirectory, getGameVersionIdForContent(), loader, updates, new ModManagerUpdateManager.Listener() {
                    @Override public void onStatus(@NonNull String message) { runOnUiThread(() -> setUpdateProgressMessage(message)); }
                    @Override public void onProgress(int current, int total) { runOnUiThread(() -> setUpdateProgress(current, total)); }
                });
                final int removedOldFiles = finalizeContentUpdateCleanup(cleanupPlans);
                runOnUiThread(() -> {
                    dismissUpdateProgressDialog();
                    HashSet<ModManagerContentType> updatedTypes = new HashSet<>();
                    for (ModManagerUpdateManager.UpdateCandidate candidate : updates) {
                        String key = buildUpdateKey(candidate);
                        updateCandidates.remove(key); updateStates.remove(key); updateMessages.remove(key);
                        updatedTypes.add(candidate.contentType);
                    }
                    if (clearCheckedStateForUpdatedTypes) {
                        for (ModManagerContentType contentType : updatedTypes) removeUpdateCandidatesForType(contentType);
                    }
                    updateContentUpdateButtons();
                    refreshContentList();
                    setResult(RESULT_OK);
                    Toast.makeText(this, getString(R.string.instance_content_update_all_success, updates.size()) + formatUpdateCleanupSuffix(removedOldFiles), Toast.LENGTH_LONG).show();
                });
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to update all content", throwable);
                runOnUiThread(() -> {
                    dismissUpdateProgressDialog();
                    refreshContentList();
                    Toast.makeText(this, getString(R.string.instance_content_update_all_failed, throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName()), Toast.LENGTH_LONG).show();
                });
            }
        }, "Update All Instance Content");
        thread.start();
    }

    @NonNull
    private UpdateCleanupPlan createUpdateCleanupPlan(@NonNull ModManagerUpdateManager.UpdateCandidate candidate) {
        UpdateCleanupPlan plan = new UpdateCleanupPlan(
                candidate,
                candidate.contentType,
                candidate.source,
                sanitizeProjectId(candidate.getProjectId()),
                buildUpdateKey(candidate),
                System.currentTimeMillis()
        );

        collectOldFilesForUpdate(plan, getInstalledManifestEntriesForProject(plan.contentType, plan.source, plan.projectId));
        ArrayList<JSONObject> modpackEntries = getModpackInstalledEntriesForProject(plan.contentType, plan.source, plan.projectId);
        plan.hadModpackMetadata = !modpackEntries.isEmpty();
        collectOldFilesForUpdate(plan, modpackEntries);
        return plan;
    }

    private void collectOldFilesForUpdate(
            @NonNull UpdateCleanupPlan plan,
            @NonNull ArrayList<JSONObject> entries
    ) {
        for (JSONObject entry : entries) {
            File file = resolveFileForInstalledEntry(entry, plan.contentType);
            if (file == null) continue;
            String canonical = safeCanonicalPath(file);
            if (!plan.oldCanonicalPaths.add(canonical)) continue;
            plan.oldFiles.add(file);
        }
    }

    private int finalizeContentUpdateCleanup(@NonNull java.util.List<UpdateCleanupPlan> cleanupPlans) {
        if (gameDirectory == null || cleanupPlans.isEmpty()) return 0;

        int removedOldFiles = 0;
        for (UpdateCleanupPlan plan : cleanupPlans) {
            try {
                File replacementFile = findReplacementFileForUpdate(plan);
                JSONObject replacementEntry = findReplacementEntryForUpdate(plan, replacementFile);
                removedOldFiles += removeOldFilesForUpdate(plan, replacementFile);
                removedOldFiles += removeDuplicateTrackedFilesForProject(plan, replacementFile);
                syncModpackFilesManifestAfterUpdate(plan, replacementFile, replacementEntry);
                ModManagerManifest.pruneMissingFiles(gameDirectory, plan.contentType);
            } catch (Throwable throwable) {
                Logging.i(TAG, "Unable to finish update cleanup for " + plan.key + ": " + readableError(throwable));
            }
        }
        return removedOldFiles;
    }

    private int removeOldFilesForUpdate(
            @NonNull UpdateCleanupPlan plan,
            @Nullable File replacementFile
    ) {
        if (gameDirectory == null || replacementFile == null || !replacementFile.isFile()) return 0;

        String replacementPath = safeCanonicalPath(replacementFile);
        int removed = 0;
        for (File oldFile : plan.oldFiles) {
            String oldPath = safeCanonicalPath(oldFile);
            if (oldPath.equals(replacementPath)) continue;

            try {
                ModManagerManifest.removeEntryForFile(gameDirectory, plan.contentType, oldFile);
            } catch (Throwable ignored) {
            }

            if (!oldFile.exists()) continue;
            try {
                deleteFileOrDirectory(oldFile);
                removed++;
            } catch (Throwable throwable) {
                Logging.i(TAG, "Unable to delete old updated file " + oldFile.getAbsolutePath() + ": " + readableError(throwable));
            }
        }
        return removed;
    }

    private int removeDuplicateTrackedFilesForProject(
            @NonNull UpdateCleanupPlan plan,
            @Nullable File replacementFile
    ) {
        if (gameDirectory == null) return 0;

        File keeper = replacementFile != null && replacementFile.isFile()
                ? replacementFile
                : findNewestTrackedProjectFile(plan);
        if (keeper == null || !keeper.isFile()) return 0;

        String keeperPath = safeCanonicalPath(keeper);
        int removed = 0;
        HashSet<String> seenPaths = new HashSet<>();
        ArrayList<File> trackedFiles = new ArrayList<>();

        collectTrackedProjectFilesForCleanup(trackedFiles, seenPaths, getInstalledManifestEntriesForProject(plan.contentType, plan.source, plan.projectId), plan.contentType);
        collectTrackedProjectFilesForCleanup(trackedFiles, seenPaths, getModpackInstalledEntriesForProject(plan.contentType, plan.source, plan.projectId), plan.contentType);

        for (File file : trackedFiles) {
            if (file == null || !file.isFile()) continue;
            String path = safeCanonicalPath(file);
            if (path.equals(keeperPath)) continue;

            try {
                ModManagerManifest.removeEntryForFile(gameDirectory, plan.contentType, file);
            } catch (Throwable ignored) {
            }

            try {
                deleteFileOrDirectory(file);
                removed++;
            } catch (Throwable throwable) {
                Logging.i(TAG, "Unable to delete duplicate updated file " + file.getAbsolutePath() + ": " + readableError(throwable));
            }
        }
        return removed;
    }

    private void collectTrackedProjectFilesForCleanup(
            @NonNull ArrayList<File> output,
            @NonNull HashSet<String> seenPaths,
            @NonNull ArrayList<JSONObject> entries,
            @NonNull ModManagerContentType type
    ) {
        for (JSONObject entry : entries) {
            File file = resolveFileForInstalledEntry(entry, type);
            if (file == null || !file.isFile()) continue;
            String path = safeCanonicalPath(file);
            if (seenPaths.add(path)) output.add(file);
        }
    }

    @Nullable
    private File findNewestTrackedProjectFile(@NonNull UpdateCleanupPlan plan) {
        ArrayList<File> files = new ArrayList<>();
        HashSet<String> seenPaths = new HashSet<>();
        collectTrackedProjectFilesForCleanup(files, seenPaths, getInstalledManifestEntriesForProject(plan.contentType, plan.source, plan.projectId), plan.contentType);
        collectTrackedProjectFilesForCleanup(files, seenPaths, getModpackInstalledEntriesForProject(plan.contentType, plan.source, plan.projectId), plan.contentType);

        File newest = null;
        long newestTime = Long.MIN_VALUE;
        String latestFileName = resolveLatestFileName(plan.candidate);
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            if (!isBlank(latestFileName) && file.getName().equalsIgnoreCase(latestFileName)) {
                return file;
            }
            long modified = file.lastModified();
            if (newest == null || modified > newestTime) {
                newest = file;
                newestTime = modified;
            }
        }
        return newest;
    }

    @Nullable
    private File findReplacementFileForUpdate(@NonNull UpdateCleanupPlan plan) {
        ArrayList<JSONObject> normalEntries = getInstalledManifestEntriesForProject(plan.contentType, plan.source, plan.projectId);
        File file = findBestReplacementFileFromEntries(plan, normalEntries);
        if (file != null) return file;

        ArrayList<JSONObject> modpackEntries = getModpackInstalledEntriesForProject(plan.contentType, plan.source, plan.projectId);
        file = findBestReplacementFileFromEntries(plan, modpackEntries);
        if (file != null) return file;

        String latestFileName = resolveLatestFileName(plan.candidate);
        if (!isBlank(latestFileName) && gameDirectory != null) {
            File targetDirectory = plan.contentType.getTargetDirectory(gameDirectory, getGameVersionIdForContent());
            File candidate = new File(targetDirectory, latestFileName);
            if (candidate.isFile()) return candidate;
            File disabledCandidate = new File(targetDirectory, latestFileName + ".disabled");
            if (disabledCandidate.isFile()) return disabledCandidate;
        }

        File newest = findNewestNewFileInTargetDirectory(plan);
        return newest != null && newest.isFile() ? newest : null;
    }

    @Nullable
    private File findBestReplacementFileFromEntries(
            @NonNull UpdateCleanupPlan plan,
            @NonNull ArrayList<JSONObject> entries
    ) {
        for (JSONObject entry : entries) {
            File file = resolveFileForInstalledEntry(entry, plan.contentType);
            if (file == null || !file.isFile()) continue;
            if (!plan.oldCanonicalPaths.contains(safeCanonicalPath(file))) {
                return file;
            }
        }

        // Do not return an old tracked file as the replacement. If the update manager
        // installed a new jar/zip but did not update metadata yet, returning the old file
        // here prevents the cleanup pass from deleting it and leaves duplicates behind.
        return null;
    }

    @Nullable
    private File findNewestNewFileInTargetDirectory(@NonNull UpdateCleanupPlan plan) {
        if (gameDirectory == null) return null;
        File directory = plan.contentType.getTargetDirectory(gameDirectory, getGameVersionIdForContent());
        File[] files = directory.listFiles(file -> file.isFile() && !file.isHidden());
        if (files == null) return null;

        long newestTime = plan.createdAt - 2000L;
        File newest = null;
        for (File file : files) {
            String canonical = safeCanonicalPath(file);
            if (plan.oldCanonicalPaths.contains(canonical)) continue;
            long modified = file.lastModified();
            if (modified >= newestTime) {
                newestTime = modified;
                newest = file;
            }
        }
        return newest;
    }

    @Nullable
    private JSONObject findReplacementEntryForUpdate(
            @NonNull UpdateCleanupPlan plan,
            @Nullable File replacementFile
    ) {
        ArrayList<JSONObject> entries = getInstalledManifestEntriesForProject(plan.contentType, plan.source, plan.projectId);
        JSONObject entry = findMatchingEntryForFile(entries, plan.contentType, replacementFile);
        if (entry != null) return entry;

        entries = getModpackInstalledEntriesForProject(plan.contentType, plan.source, plan.projectId);
        entry = findMatchingEntryForFile(entries, plan.contentType, replacementFile);
        if (entry != null) return entry;

        if (replacementFile != null && replacementFile.isFile()) {
            return buildFallbackUpdatedEntry(plan, replacementFile);
        }
        return null;
    }

    @Nullable
    private JSONObject findMatchingEntryForFile(
            @NonNull ArrayList<JSONObject> entries,
            @NonNull ModManagerContentType type,
            @Nullable File file
    ) {
        if (entries.isEmpty()) return null;
        if (file != null) {
            String targetPath = safeCanonicalPath(file);
            for (JSONObject entry : entries) {
                File entryFile = resolveFileForInstalledEntry(entry, type);
                if (entryFile != null && targetPath.equals(safeCanonicalPath(entryFile))) return entry;
            }
        }
        return entries.get(0);
    }

    private void syncModpackFilesManifestAfterUpdate(
            @NonNull UpdateCleanupPlan plan,
            @Nullable File replacementFile,
            @Nullable JSONObject replacementEntry
    ) throws Exception {
        if (gameDirectory == null) return;

        File manifest = getModpackFilesManifestFile();
        boolean manifestExists = manifest.isFile();
        if (!manifestExists && !plan.hadModpackMetadata) return;

        JSONObject root = manifestExists ? new JSONObject(readTextFile(manifest)) : new JSONObject();
        JSONArray files = root.optJSONArray("files");
        if (files == null) files = new JSONArray();

        JSONArray kept = new JSONArray();
        for (int i = 0; i < files.length(); i++) {
            JSONObject entry = files.optJSONObject(i);
            if (entry == null) continue;

            ModManagerContentType entryType = resolveContentTypeFromEntry(entry);
            File entryFile = entryType == null ? null : resolveFileForInstalledEntry(entry, entryType);
            boolean staleFile = entryFile != null && !entryFile.isFile();
            boolean sameProject = entryMatchesProject(entry, plan.contentType, plan.source, plan.projectId);

            if (sameProject || staleFile) continue;
            kept.put(entry);
        }

        if (replacementFile != null && replacementFile.isFile()) {
            JSONObject updatedEntry = replacementEntry == null
                    ? buildFallbackUpdatedEntry(plan, replacementFile)
                    : new JSONObject(replacementEntry.toString());
            normalizeUpdatedManifestEntry(plan, updatedEntry, replacementFile);
            kept.put(updatedEntry);
        }

        root.put("files", kept);
        writeTextFile(manifest, root.toString(2));
    }

    @NonNull
    private JSONObject buildFallbackUpdatedEntry(
            @NonNull UpdateCleanupPlan plan,
            @NonNull File replacementFile
    ) {
        JSONObject entry = new JSONObject();
        try {
            entry.put("source", plan.source.getId());
            entry.put("platform", plan.source.getId());
            entry.put("modpackPlatform", plan.source.getId());
            entry.put("contentType", plan.contentType.getIntentValue());
            entry.put("type", plan.contentType.getIntentValue());
            entry.put("platformProjectId", plan.projectId);
            entry.put("projectId", plan.projectId);
            entry.put("versionNumber", plan.candidate.latestVersion.versionNumber);
            String latestFileName = resolveLatestFileName(plan.candidate);
            if (!isBlank(latestFileName)) entry.put("remoteFileName", latestFileName);
            String versionId = readLatestVersionString(plan.candidate, "versionId", "id");
            if (!isBlank(versionId)) entry.put("versionId", versionId);
            String fileId = readLatestVersionString(plan.candidate, "fileId", "fileID", "curseForgeFileId", "curseforgeFileId");
            if (!isBlank(fileId)) entry.put("fileId", fileId);
            String downloadUrl = readLatestVersionString(plan.candidate, "downloadUrl", "url");
            if (!isBlank(downloadUrl)) entry.put("downloadUrl", downloadUrl);
        } catch (Throwable ignored) {
        }
        normalizeUpdatedManifestEntry(plan, entry, replacementFile);
        return entry;
    }

    private void normalizeUpdatedManifestEntry(
            @NonNull UpdateCleanupPlan plan,
            @NonNull JSONObject entry,
            @NonNull File replacementFile
    ) {
        try {
            entry.put("source", plan.source.getId());
            entry.put("platform", plan.source.getId());
            if (!entry.has("modpackPlatform")) entry.put("modpackPlatform", plan.source.getId());
            entry.put("contentType", plan.contentType.getIntentValue());
            entry.put("type", plan.contentType.getIntentValue());
            entry.put("platformProjectId", plan.projectId);
            entry.put("projectId", plan.projectId);
            entry.put("fileName", replacementFile.getName());
            entry.put("relativePath", getRelativePathFromGameDirectory(replacementFile));
            entry.put("filePath", getRelativePathFromGameDirectory(replacementFile));
            entry.put("absolutePath", replacementFile.getAbsolutePath());
            entry.put("canonicalPath", safeCanonicalPath(replacementFile));
            entry.put("installedAt", System.currentTimeMillis());
            entry.put("updatedBy", "DroidBridge");
            if (!entry.has("versionNumber")) entry.put("versionNumber", plan.candidate.latestVersion.versionNumber);
        } catch (Throwable ignored) {
        }
    }

    @NonNull
    private ArrayList<JSONObject> getInstalledManifestEntriesForProject(
            @NonNull ModManagerContentType type,
            @NonNull ModManagerSource source,
            @NonNull String projectId
    ) {
        ArrayList<JSONObject> result = new ArrayList<>();
        if (gameDirectory == null) return result;

        for (JSONObject entry : ModManagerManifest.getInstalledEntries(gameDirectory, type)) {
            if (entryMatchesProject(entry, type, source, projectId)) {
                result.add(entry);
            }
        }
        return result;
    }

    @NonNull
    private ArrayList<JSONObject> getModpackInstalledEntriesForProject(
            @NonNull ModManagerContentType type,
            @NonNull ModManagerSource source,
            @NonNull String projectId
    ) {
        ArrayList<JSONObject> result = new ArrayList<>();
        for (JSONObject entry : getModpackInstalledEntriesForType(type)) {
            if (entryMatchesProject(entry, type, source, projectId)) {
                result.add(entry);
            }
        }
        return result;
    }

    private boolean entryMatchesProject(
            @NonNull JSONObject entry,
            @NonNull ModManagerContentType type,
            @NonNull ModManagerSource source,
            @NonNull String projectId
    ) {
        String contentType = entry.optString("contentType", entry.optString("type", ""));
        if (!isBlank(contentType) && !type.getIntentValue().equalsIgnoreCase(contentType)) return false;

        ModManagerSource entrySource = ModManagerManifest.getSource(entry);
        if (entrySource != ModManagerSource.UNKNOWN && entrySource != source) return false;

        String entryProjectId = getProjectIdFromEntry(entry);
        return !isBlank(projectId) && projectId.equalsIgnoreCase(entryProjectId);
    }

    @NonNull
    private String getProjectIdFromEntry(@NonNull JSONObject entry) {
        String projectId = entry.optString("platformProjectId", "").trim();
        if (projectId.isEmpty()) projectId = entry.optString("projectId", "").trim();
        if (projectId.isEmpty()) projectId = entry.optString("modrinthProjectId", "").trim();
        if (projectId.isEmpty()) projectId = entry.optString("modrinth_project_id", "").trim();
        if (projectId.isEmpty()) projectId = entry.optString("curseForgeProjectId", "").trim();
        if (projectId.isEmpty()) projectId = entry.optString("curseforgeProjectId", "").trim();
        if (projectId.isEmpty()) projectId = entry.optString("curseForgeProjectID", "").trim();
        if (projectId.isEmpty()) projectId = entry.optString("projectID", "").trim();
        return projectId;
    }

    @NonNull
    private String sanitizeProjectId(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    @Nullable
    private ModManagerContentType resolveContentTypeFromEntry(@NonNull JSONObject entry) {
        String value = entry.optString("contentType", entry.optString("type", ""));
        if (value.equalsIgnoreCase(ModManagerContentType.MODS.getIntentValue())) return ModManagerContentType.MODS;
        if (value.equalsIgnoreCase(ModManagerContentType.RESOURCEPACKS.getIntentValue())) return ModManagerContentType.RESOURCEPACKS;
        if (value.equalsIgnoreCase(ModManagerContentType.SHADERPACKS.getIntentValue())) return ModManagerContentType.SHADERPACKS;
        return null;
    }

    @Nullable
    private File resolveFileForInstalledEntry(
            @NonNull JSONObject entry,
            @NonNull ModManagerContentType type
    ) {
        File file = resolveFileForInstalledEntry(entry);
        if (file != null && file.isFile()) return file;
        if (gameDirectory == null) return file;

        String fileName = entry.optString("fileName", "");
        if (!isBlank(fileName)) {
            File targetDirectory = type.getTargetDirectory(gameDirectory, getGameVersionIdForContent());
            File candidate = new File(targetDirectory, fileName);
            if (candidate.isFile()) return candidate;
            File disabledCandidate = new File(targetDirectory, fileName + ".disabled");
            if (disabledCandidate.isFile()) return disabledCandidate;
            File enabledCandidate = new File(targetDirectory, stripDisabledSuffix(fileName));
            if (enabledCandidate.isFile()) return enabledCandidate;
        }
        return file;
    }

    @NonNull
    private File getModpackFilesManifestFile() {
        return DroidBridgeMetadataPaths.fileForRead(gameDirectory, DroidBridgeMetadataPaths.MODPACK_FILES_MANIFEST);
    }

    @NonNull
    private String formatUpdateCleanupSuffix(int removedOldFiles) {
        if (removedOldFiles <= 0) return "";
        return " · removed " + removedOldFiles + " old " + (removedOldFiles == 1 ? "file" : "files");
    }

    @NonNull
    private String resolveLatestFileName(@NonNull ModManagerUpdateManager.UpdateCandidate candidate) {
        String fileName = readLatestVersionString(candidate, "fileName", "filename", "primaryFileName", "name");
        if (!isBlank(fileName)) return fileName;

        String downloadUrl = readLatestVersionString(candidate, "downloadUrl", "url");
        if (isBlank(downloadUrl)) return "";
        int slash = downloadUrl.lastIndexOf('/');
        String tail = slash >= 0 && slash + 1 < downloadUrl.length() ? downloadUrl.substring(slash + 1) : downloadUrl;
        int query = tail.indexOf('?');
        if (query >= 0) tail = tail.substring(0, query);
        return tail.trim();
    }

    @NonNull
    private String readLatestVersionString(
            @NonNull ModManagerUpdateManager.UpdateCandidate candidate,
            @NonNull String... fieldNames
    ) {
        Object latest = candidate.latestVersion;
        if (latest == null) return "";
        return readObjectStringField(latest, fieldNames);
    }

    @NonNull
    private String readObjectStringField(
            @NonNull Object object,
            @NonNull String... fieldNames
    ) {
        for (String fieldName : fieldNames) {
            try {
                java.lang.reflect.Field field = object.getClass().getField(fieldName);
                Object value = field.get(object);
                if (value != null) {
                    String text = String.valueOf(value).trim();
                    if (!text.isEmpty() && !"null".equalsIgnoreCase(text)) return text;
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private void writeTextFile(@NonNull File file, @NonNull String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create folder: " + parent.getAbsolutePath());
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes("UTF-8"));
        }
    }

    private static final class UpdateCleanupPlan {
        @NonNull final ModManagerUpdateManager.UpdateCandidate candidate;
        @NonNull final ModManagerContentType contentType;
        @NonNull final ModManagerSource source;
        @NonNull final String projectId;
        @NonNull final String key;
        final long createdAt;
        boolean hadModpackMetadata;
        @NonNull final ArrayList<File> oldFiles = new ArrayList<>();
        @NonNull final HashSet<String> oldCanonicalPaths = new HashSet<>();

        UpdateCleanupPlan(
                @NonNull ModManagerUpdateManager.UpdateCandidate candidate,
                @NonNull ModManagerContentType contentType,
                @NonNull ModManagerSource source,
                @NonNull String projectId,
                @NonNull String key,
                long createdAt
        ) {
            this.candidate = candidate;
            this.contentType = contentType;
            this.source = source;
            this.projectId = projectId;
            this.key = key;
            this.createdAt = createdAt;
        }
    }

    private void markTrackedEntriesUpToDate(@NonNull ModManagerContentType managerType) {
        if (gameDirectory == null) return;
        removeUpdateCandidatesForType(managerType);
        for (JSONObject entry : ModManagerManifest.getInstalledEntries(gameDirectory, managerType)) {
            ModManagerSource source = ModManagerManifest.getSource(entry);
            if (source != ModManagerSource.MODRINTH && source != ModManagerSource.CURSEFORGE) continue;
            String key = buildUpdateKey(managerType, entry);
            updateStates.put(key, UpdateState.UP_TO_DATE);
            updateMessages.put(key, getString(R.string.instance_content_up_to_date));
        }
        for (JSONObject entry : getModpackInstalledEntriesForType(managerType)) {
            ModManagerSource source = ModManagerManifest.getSource(entry);
            if (source != ModManagerSource.MODRINTH && source != ModManagerSource.CURSEFORGE) continue;
            String key = buildUpdateKey(managerType, entry);
            updateStates.put(key, UpdateState.UP_TO_DATE);
            updateMessages.put(key, getString(R.string.instance_content_up_to_date));
        }
    }

    private void removeUpdateCandidatesForType(@NonNull ModManagerContentType managerType) {
        String prefix = managerType.name() + ":";
        for (String key : new ArrayList<>(updateCandidates.keySet())) if (key.startsWith(prefix)) updateCandidates.remove(key);
        for (String key : new ArrayList<>(updateStates.keySet())) if (key.startsWith(prefix)) updateStates.remove(key);
        for (String key : new ArrayList<>(updateMessages.keySet())) if (key.startsWith(prefix)) updateMessages.remove(key);
    }

    @NonNull private String getGameVersionIdForContent() { return isBlank(minecraftVersionId) ? ModManagerVersionResolver.resolveGameVersionForContent(baseVersionId) : minecraftVersionId; }
    @NonNull private String buildUpdateKey(@NonNull ModManagerUpdateManager.UpdateCandidate candidate) { return candidate.contentType.name() + ":" + candidate.source.getId() + ":" + candidate.getProjectId(); }
    @NonNull private String buildUpdateKey(@NonNull ModManagerContentType managerType, @NonNull JSONObject entry) {
        ModManagerSource source = ModManagerManifest.getSource(entry);
        String projectId = getProjectIdFromEntry(entry);
        if (projectId.isEmpty()) projectId = entry.optString("fileName", "unknown").trim();
        return managerType.name() + ":" + source.getId() + ":" + projectId;
    }
    @NonNull private String buildUpdateKey(@NonNull InstanceContentItem item) {
        ModManagerContentType managerType = toModManagerContentType(item.category);
        if (managerType != null && gameDirectory != null) {
            JSONObject entry = getInstalledEntryForItem(item);
            if (entry != null) return buildUpdateKey(managerType, entry);
            return managerType.name() + ":file:" + safeCanonicalPath(item.file);
        }
        return item.category.name() + ":file:" + safeCanonicalPath(item.file);
    }
    @NonNull private UpdateState getUpdateStateForItem(@NonNull InstanceContentItem item) {
        // Fast first draw: do not parse installed-content manifests just to paint an
        // UNKNOWN update state. Once an update check has populated state/candidate maps,
        // rows may resolve their source/project keys normally.
        if (updateStates.isEmpty() && updateCandidates.isEmpty() && updateMessages.isEmpty()) {
            return UpdateState.UNKNOWN;
        }
        UpdateState state = updateStates.get(buildUpdateKey(item));
        return state == null ? UpdateState.UNKNOWN : state;
    }
    @Nullable private String getUpdateMessageForItem(@NonNull InstanceContentItem item) {
        if (updateMessages.isEmpty() && updateStates.isEmpty() && updateCandidates.isEmpty()) return null;
        return updateMessages.get(buildUpdateKey(item));
    }

    @Nullable
    private JSONObject getInstalledEntryForItem(@NonNull InstanceContentItem item) {
        ModManagerContentType managerType = toModManagerContentType(item.category);
        if (managerType == null || gameDirectory == null) return null;

        String cacheKey = buildInstalledEntryCacheKey(managerType, item.file);
        JSONObject cached = installedEntryCache.get(cacheKey);
        if (cached != null) return cached;
        if (missingInstalledEntryCache.contains(cacheKey)) return null;

        JSONObject entry = ModManagerManifest.getInstalledEntryForFile(gameDirectory, managerType, item.file);
        if (entry == null) entry = getModpackInstalledEntryForFile(managerType, item.file);

        if (entry != null) {
            installedEntryCache.put(cacheKey, entry);
        } else {
            missingInstalledEntryCache.add(cacheKey);
        }
        return entry;
    }

    @NonNull
    private String buildInstalledEntryCacheKey(@NonNull ModManagerContentType managerType, @NonNull File file) {
        return managerType.name()
                + ":" + safeCanonicalPath(file)
                + ":" + file.length()
                + ":" + file.lastModified();
    }

    @Nullable
    private JSONObject getModpackInstalledEntryForFile(
            @NonNull ModManagerContentType managerType,
            @NonNull File file
    ) {
        for (JSONObject entry : getModpackInstalledEntriesForType(managerType)) {
            if (matchesInstalledContentEntry(entry, file)) return entry;
        }
        return null;
    }

    @NonNull
    private ArrayList<JSONObject> getModpackInstalledEntriesForType(@NonNull ModManagerContentType managerType) {
        ArrayList<JSONObject> cached = modpackInstalledEntriesCache.get(managerType);
        if (cached != null) return cached;

        ArrayList<JSONObject> entries = new ArrayList<>();
        if (gameDirectory == null) {
            modpackInstalledEntriesCache.put(managerType, entries);
            return entries;
        }

        File manifest = DroidBridgeMetadataPaths.fileForRead(gameDirectory, DroidBridgeMetadataPaths.MODPACK_FILES_MANIFEST);
        if (!manifest.isFile()) {
            modpackInstalledEntriesCache.put(managerType, entries);
            return entries;
        }

        try {
            JSONObject root = new JSONObject(readTextFile(manifest));
            JSONArray files = root.optJSONArray("files");
            if (files != null) {
                String expectedType = managerType.getIntentValue();
                for (int i = 0; i < files.length(); i++) {
                    JSONObject entry = files.optJSONObject(i);
                    if (entry == null) continue;
                    String contentType = entry.optString("contentType", entry.optString("type", ""));
                    if (!expectedType.equalsIgnoreCase(contentType)) continue;
                    entries.add(entry);
                }
            }
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to read modpack installed-content metadata: " + readableError(throwable));
        }
        modpackInstalledEntriesCache.put(managerType, entries);
        return entries;
    }

    private boolean matchesInstalledContentEntry(@NonNull JSONObject entry, @NonNull File file) {
        String fileCanonical = safeCanonicalPath(file);
        if (matchesPathValue(fileCanonical, entry.optString("canonicalPath", ""))) return true;
        if (matchesPathValue(fileCanonical, entry.optString("absolutePath", ""))) return true;

        String relativePath = getRelativePathFromGameDirectory(file);
        String enabledRelativePath = stripDisabledSuffix(relativePath);
        if (matchesRelativePath(relativePath, entry.optString("relativePath", ""))) return true;
        if (matchesRelativePath(relativePath, entry.optString("filePath", ""))) return true;
        if (matchesRelativePath(relativePath, entry.optString("path", ""))) return true;
        if (matchesRelativePath(enabledRelativePath, entry.optString("relativePath", ""))) return true;
        if (matchesRelativePath(enabledRelativePath, entry.optString("filePath", ""))) return true;
        if (matchesRelativePath(enabledRelativePath, entry.optString("path", ""))) return true;

        String entryFileName = entry.optString("fileName", "");
        return !isBlank(entryFileName)
                && stripDisabledSuffix(file.getName()).equalsIgnoreCase(stripDisabledSuffix(entryFileName));
    }

    private boolean matchesPathValue(@NonNull String left, @Nullable String right) {
        if (isBlank(right)) return false;
        return left.equals(safeCanonicalPath(new File(right)));
    }

    private boolean matchesRelativePath(@NonNull String left, @Nullable String right) {
        if (isBlank(right)) return false;
        return normalizeContentPath(left).equals(normalizeContentPath(right));
    }

    @NonNull
    private String normalizeContentPath(@NonNull String path) {
        String normalized = path.replace('\\', '/').trim();
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        return normalized.toLowerCase(Locale.US);
    }

    @NonNull
    private String getRelativePathFromGameDirectory(@NonNull File file) {
        if (gameDirectory == null) return file.getName();
        String gamePath = safeCanonicalPath(gameDirectory);
        String filePath = safeCanonicalPath(file);
        if (filePath.equals(gamePath)) return "";
        if (filePath.startsWith(gamePath + File.separator)) {
            return filePath.substring(gamePath.length() + 1).replace(File.separatorChar, '/');
        }
        return file.getName();
    }

    @Nullable
    private File resolveFileForInstalledEntry(@NonNull JSONObject entry) {
        if (gameDirectory == null) return null;
        String relativePath = entry.optString("relativePath", entry.optString("filePath", entry.optString("path", "")));
        if (!isBlank(relativePath)) {
            File file = new File(gameDirectory, relativePath.replace('/', File.separatorChar));
            if (file.isFile()) return file;
            File disabledFile = new File(gameDirectory, relativePath.replace('/', File.separatorChar) + ".disabled");
            if (disabledFile.isFile()) return disabledFile;
        }
        String absolutePath = entry.optString("absolutePath", "");
        if (!isBlank(absolutePath)) {
            File file = new File(absolutePath);
            if (file.isFile()) return file;
        }
        return null;
    }

    private boolean containsUpdateCandidate(
            @NonNull ArrayList<ModManagerUpdateManager.UpdateCandidate> updates,
            @NonNull ModManagerUpdateManager.UpdateCandidate candidate
    ) {
        String candidateKey = buildUpdateKey(candidate);
        for (ModManagerUpdateManager.UpdateCandidate existing : updates) {
            if (candidateKey.equals(buildUpdateKey(existing))) return true;
        }
        return false;
    }

    @NonNull
    private String readTextFile(@NonNull File file) throws IOException {
        try (InputStream input = new java.io.FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copyStream(input, output);
            return output.toString("UTF-8");
        }
    }

    private void showUpdateProgressDialog(@NonNull String title, @NonNull String message, boolean indeterminate, int max, boolean cancelable) {
        dismissUpdateProgressDialog();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(22);
        layout.setPadding(padding, dp(6), padding, 0);
        updateProgressMessage = new TextView(this);
        updateProgressMessage.setText(message);
        layout.addView(updateProgressMessage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        updateProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        updateProgressBar.setIndeterminate(indeterminate);
        updateProgressBar.setMax(Math.max(1, max));
        updateProgressBar.setProgress(0);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progressParams.topMargin = dp(12);
        layout.addView(updateProgressBar, progressParams);
        updateProgressDialog = new MaterialAlertDialogBuilder(this).setTitle(title).setView(layout).setCancelable(cancelable).create();
        updateProgressDialog.setOnDismissListener(dialog -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        updateProgressDialog.show();
    }
    private void setUpdateProgressMessage(@NonNull String message) { if (updateProgressMessage != null) updateProgressMessage.setText(message); }
    private void setUpdateProgress(int current, int total) { if (updateProgressBar == null) return; updateProgressBar.setIndeterminate(false); updateProgressBar.setMax(Math.max(1, total)); updateProgressBar.setProgress(Math.max(0, Math.min(current, Math.max(1, total)))); }
    private void dismissUpdateProgressDialog() { if (updateProgressDialog != null) { updateProgressDialog.dismiss(); updateProgressDialog = null; } updateProgressMessage = null; updateProgressBar = null; getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }

    private void pickSelectedContent() {
        if (selectedCategory == ResourceCategory.MODS && !supportsMods()) {
            Toast.makeText(this, R.string.mods_vanilla_hint, Toast.LENGTH_LONG).show();
            return;
        }

        pendingImportCategory = selectedCategory;

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(pendingImportCategory.mimeType);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        if (pendingImportCategory.mimeTypes.length > 0) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, pendingImportCategory.mimeTypes);
        }

        try {
            startActivityForResult(
                    Intent.createChooser(intent, getString(
                            pendingImportCategory == ResourceCategory.RESOURCEPACKS && usesLegacyTexturePacks()
                                    ? R.string.texturepacks_picker_title
                                    : pendingImportCategory.pickerTitleRes
                    )),
                    REQUEST_PICK_CONTENT
            );
        } catch (ActivityNotFoundException throwable) {
            Toast.makeText(this, R.string.mods_picker_missing, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_UPDATE_MODPACK) {
            if (resultCode == RESULT_OK) {
                if (data != null) {
                    String updatedLoader = data.getStringExtra(EXTRA_INSTANCE_LOADER);
                    String updatedBaseVersion = data.getStringExtra(EXTRA_BASE_VERSION_ID);
                    String updatedMinecraftVersion = data.getStringExtra(EXTRA_MINECRAFT_VERSION_ID);
                    String updatedVersionType = data.getStringExtra(EXTRA_VERSION_TYPE);
                    if (!isBlank(updatedLoader)) loader = updatedLoader;
                    if (!isBlank(updatedBaseVersion)) baseVersionId = updatedBaseVersion;
                    if (!isBlank(updatedMinecraftVersion)) minecraftVersionId = updatedMinecraftVersion;
                    if (!isBlank(updatedVersionType)) versionType = updatedVersionType;
                    updateIntentExtras();
                    bindHeader();
                }
                setResult(RESULT_OK);
                refreshContentList();
            }
            return;
        }

        if (requestCode == REQUEST_EXPORT_WORLD) {
            File worldDirectory = pendingWorldExportDirectory;
            pendingWorldExportDirectory = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && worldDirectory != null) {
                exportWorldToUri(data.getData(), worldDirectory);
            }
            return;
        }

        if (requestCode == REQUEST_EXPORT_MODPACK) {
            ModpackExportManager.Platform platform = pendingExportPlatform;
            ModpackExportManager.ExportOptions exportOptions = pendingExportOptions;
            pendingExportPlatform = null;
            pendingExportOptions = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && platform != null) {
                exportModpackToUri(
                        data.getData(),
                        platform,
                        exportOptions == null ? ModpackExportManager.ExportOptions.defaultOptions() : exportOptions
                );
            }
            return;
        }

        if (requestCode == REQUEST_IMPORT_MODPACK) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                importModpackFromUri(data.getData());
            }
            return;
        }

        if (requestCode == REQUEST_PICK_INSTANCE_ICON) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                savePickedInstanceIcon(data.getData());
            }
            return;
        }

        if (requestCode != REQUEST_PICK_CONTENT || resultCode != RESULT_OK || data == null) {
            return;
        }

        ArrayList<Uri> uris = collectSelectedUris(data);
        if (uris.isEmpty()) return;

        ResourceCategory importCategory = pendingImportCategory;
        Thread thread = new Thread(() -> importSelectedContent(uris, importCategory), "Import Instance Content");
        thread.start();
    }

    private void importSelectedContent(@NonNull ArrayList<Uri> uris, @NonNull ResourceCategory category) {
        File targetDirectory = getDirectoryForCategory(category);
        int added = 0;
        try {
            if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
                throw new IllegalStateException("Unable to create folder: " + targetDirectory.getAbsolutePath());
            }

            for (Uri uri : uris) {
                if (category == ResourceCategory.WORLDS) {
                    importWorldArchive(uri, targetDirectory);
                } else {
                    String fileName = sanitizeImportedFileName(resolveDisplayName(uri), category.defaultExtension);
                    copyUriToFile(uri, uniqueTargetFile(targetDirectory, fileName));
                }
                added++;
            }

            int finalAdded = added;
            runOnUiThread(() -> {
                refreshContentList();
                Toast.makeText(
                        this,
                        getString(R.string.instance_content_imported_value, finalAdded, getCategoryPluralLabel(category)),
                        Toast.LENGTH_SHORT
                ).show();
            });
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to import " + category.name(), throwable);
            runOnUiThread(() -> Toast.makeText(
                    this,
                    getString(R.string.instance_content_import_failed, getCategoryPluralLabel(category), throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName()),
                    Toast.LENGTH_LONG
            ).show());
        }
    }

    @NonNull
    private ArrayList<Uri> collectSelectedUris(@NonNull Intent data) {
        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                Uri uri = clipData.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        return uris;
    }

    private void importWorldArchive(@NonNull Uri uri, @NonNull File savesDirectory) throws Exception {
        File tempZip = File.createTempFile("world-import-", ".zip", getCacheDir());
        try {
            copyUriToFile(uri, tempZip);
            String worldRootPrefix = findWorldRootPrefix(tempZip);
            if (worldRootPrefix == null) {
                throw new IllegalStateException("Selected zip does not look like a Minecraft world. It must contain level.dat.");
            }

            String zipName = stripExtension(sanitizeImportedFileName(resolveDisplayName(uri), ".zip"));
            String rootName = sanitizeImportedFileName(lastFolderName(worldRootPrefix), null);
            String worldFolderName = isBlank(rootName) ? zipName : rootName;
            if (isBlank(worldFolderName)) worldFolderName = "Imported World";

            File targetWorldDirectory = uniqueTargetDirectory(savesDirectory, worldFolderName);
            extractWorldZip(tempZip, targetWorldDirectory, worldRootPrefix);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tempZip.delete();
        }
    }

    @Nullable
    private String findWorldRootPrefix(@NonNull File zipFile) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = normalizeZipPath(entry.getName());
                if (name.endsWith("level.dat")) {
                    int end = name.length() - "level.dat".length();
                    return name.substring(0, end);
                }
            }
        }
        return null;
    }

    private void extractWorldZip(@NonNull File zipFile, @NonNull File targetWorldDirectory, @Nullable String rootPrefix) throws Exception {
        String targetCanonical = targetWorldDirectory.getCanonicalPath();
        if (!targetWorldDirectory.exists() && !targetWorldDirectory.mkdirs()) {
            throw new IllegalStateException("Unable to create world folder: " + targetWorldDirectory.getAbsolutePath());
        }

        String prefix = rootPrefix == null ? "" : normalizeZipPath(rootPrefix);
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;

                String name = normalizeZipPath(entry.getName());
                if (!prefix.isEmpty()) {
                    if (!name.startsWith(prefix)) continue;
                    name = name.substring(prefix.length());
                }
                while (name.startsWith("/")) name = name.substring(1);
                if (isBlank(name)) continue;

                File output = new File(targetWorldDirectory, name);
                String outputCanonical = output.getCanonicalPath();
                if (!outputCanonical.equals(targetCanonical) && !outputCanonical.startsWith(targetCanonical + File.separator)) {
                    throw new SecurityException("Blocked unsafe zip entry: " + entry.getName());
                }

                File parent = output.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IllegalStateException("Unable to create folder: " + parent.getAbsolutePath());
                }

                try (InputStream input = zip.getInputStream(entry);
                     FileOutputStream outputStream = new FileOutputStream(output)) {
                    copyStream(input, outputStream);
                }
            }
        }
    }

    private void startWorldExportFromRow(@NonNull InstanceContentItem item) {
        prepareContentRowAction();
        InstanceContentItem actionItem = resolveContentItemForAction(item);
        startWorldExport(actionItem.file);
    }

    private void startWorldExport(@NonNull File worldDirectory) {
        if (!worldDirectory.isDirectory() || !new File(worldDirectory, "level.dat").isFile()) {
            Toast.makeText(this, "This folder does not look like a Minecraft world.", Toast.LENGTH_LONG).show();
            return;
        }

        pendingWorldExportDirectory = worldDirectory;

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, sanitizeImportedFileName(worldDirectory.getName(), ".zip"));

        try {
            startActivityForResult(Intent.createChooser(intent, "Export World"), REQUEST_EXPORT_WORLD);
        } catch (ActivityNotFoundException throwable) {
            pendingWorldExportDirectory = null;
            Toast.makeText(this, "No file picker is available for exporting.", Toast.LENGTH_LONG).show();
        }
    }

    private void exportWorldToUri(@NonNull Uri uri, @NonNull File worldDirectory) {
        showUpdateProgressDialog("Export World", "Preparing world export...", false, 100, false);

        Thread thread = new Thread(() -> {
            try {
                if (!worldDirectory.isDirectory() || !new File(worldDirectory, "level.dat").isFile()) {
                    throw new IllegalStateException("This folder does not look like a Minecraft world.");
                }

                ArrayList<File> files = new ArrayList<>();
                collectFilesForWorldExport(worldDirectory, files);
                if (files.isEmpty()) {
                    throw new IllegalStateException("World folder is empty.");
                }

                OutputStream rawOutput = getContentResolver().openOutputStream(uri);
                if (rawOutput == null) {
                    throw new IOException("Unable to open export target.");
                }

                try (OutputStream output = rawOutput; ZipOutputStream zip = new ZipOutputStream(output)) {
                    String rootName = sanitizeImportedFileName(worldDirectory.getName(), null);
                    if (isBlank(rootName)) rootName = "World";

                    for (int i = 0; i < files.size(); i++) {
                        File file = files.get(i);
                        String relativePath = getRelativePathFromDirectory(worldDirectory, file);
                        if (isBlank(relativePath)) continue;

                        ZipEntry entry = new ZipEntry(rootName + "/" + relativePath);
                        entry.setTime(file.lastModified());
                        zip.putNextEntry(entry);

                        try (InputStream input = new java.io.FileInputStream(file)) {
                            copyStream(input, zip);
                        }

                        zip.closeEntry();

                        final int progress = i + 1;
                        final int total = files.size();
                        if (progress == total || progress % 8 == 0) {
                            runOnUiThread(() -> {
                                setUpdateProgressMessage("Exporting " + progress + " of " + total + " files...");
                                setUpdateProgress(progress, total);
                            });
                        }
                    }
                }

                runOnUiThread(() -> {
                    dismissUpdateProgressDialog();
                    Toast.makeText(this, "World exported: " + worldDirectory.getName(), Toast.LENGTH_LONG).show();
                });
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to export world " + worldDirectory.getAbsolutePath(), throwable);
                runOnUiThread(() -> {
                    dismissUpdateProgressDialog();
                    Toast.makeText(this, "World export failed: " + readableError(throwable), Toast.LENGTH_LONG).show();
                });
            }
        }, "Export World");
        thread.start();
    }

    private void collectFilesForWorldExport(@NonNull File file, @NonNull ArrayList<File> output) {
        if (file.isHidden()) return;
        if (file.isFile()) {
            output.add(file);
            return;
        }

        File[] children = file.listFiles();
        if (children == null) return;

        ArrayList<File> sortedChildren = new ArrayList<>();
        Collections.addAll(sortedChildren, children);
        sortedChildren.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File child : sortedChildren) {
            collectFilesForWorldExport(child, output);
        }
    }

    @NonNull
    private String getRelativePathFromDirectory(@NonNull File root, @NonNull File file) throws IOException {
        String rootPath = root.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        if (!filePath.equals(rootPath) && !filePath.startsWith(rootPath + File.separator)) {
            throw new SecurityException("Blocked unsafe export path: " + file.getAbsolutePath());
        }
        if (filePath.equals(rootPath)) return "";
        return filePath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
    }

    @NonNull
    private String normalizeZipPath(@NonNull String path) {
        return path.replace('\\', '/');
    }

    @NonNull
    private String lastFolderName(@NonNull String prefix) {
        String value = normalizeZipPath(prefix);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    @NonNull
    private File uniqueTargetDirectory(@NonNull File parent, @NonNull String rawName) {
        String name = sanitizeImportedFileName(rawName, null);
        if (isBlank(name)) name = "Imported World";
        File target = new File(parent, name);
        if (!target.exists()) return target;
        for (int i = 2; i < 1000; i++) {
            File candidate = new File(parent, name + "-" + i);
            if (!candidate.exists()) return candidate;
        }
        return new File(parent, name + "-" + System.currentTimeMillis());
    }

    @NonNull
    private String resolveDisplayName(@NonNull Uri uri) {
        String fallback = uri.getLastPathSegment();
        if (fallback == null || fallback.trim().isEmpty()) fallback = "file";

        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.trim().isEmpty()) return value;
                }
            }
        } catch (Throwable ignored) {
        }

        return fallback;
    }

    @NonNull
    private String sanitizeInstanceName(@NonNull String rawName) {
        String name = rawName.trim().replace('\n', ' ').replace('\r', ' ');
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        while (name.contains("  ")) name = name.replace("  ", " ");
        if (".".equals(name) || "..".equals(name)) return "";
        return name;
    }

    @NonNull
    private String sanitizeImportedFileName(@NonNull String rawName, @Nullable String defaultExtension) {
        String name = rawName.trim().replace('\n', ' ').replace('\r', ' ');
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        while (name.contains("  ")) name = name.replace("  ", " ");
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) name = "file";
        if (!isBlank(defaultExtension) && !name.toLowerCase(Locale.US).endsWith(defaultExtension.toLowerCase(Locale.US))) {
            name = name + defaultExtension;
        }
        return name;
    }

    @NonNull
    private File uniqueTargetFile(@NonNull File directory, @NonNull String fileName) {
        File target = new File(directory, fileName);
        if (!target.exists()) return target;

        String base = fileName;
        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }

        for (int i = 2; i < 1000; i++) {
            File candidate = new File(directory, base + "-" + i + extension);
            if (!candidate.exists()) return candidate;
        }
        return new File(directory, base + "-" + System.currentTimeMillis() + extension);
    }

    private void copyUriToFile(@NonNull Uri uri, @NonNull File target) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) throw new IllegalStateException("Unable to open selected file.");
            copyStream(input, output);
        }
    }

    private void copyStream(@NonNull InputStream input, @NonNull OutputStream output) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    @NonNull
    private String resolveDisplayTitle(@NonNull File file, @NonNull ResourceCategory category) {
        if (category == ResourceCategory.SCREENSHOTS) {
            return file.getName();
        }
        if (category == ResourceCategory.WORLDS) {
            return file.getName();
        }

        if (category == ResourceCategory.MODS && file.isFile()) {
            // Keep the first screen draw fast. Deep jar metadata/name lookup is
            // done asynchronously in the RecyclerView row after the list is visible.
            return stripExtension(file.getName());
        }

        return stripExtension(file.getName());
    }

    @Nullable
    private String readModName(@NonNull File file) {
        String nestedOrEmbeddedName = ModJarMetadataExtractor.readDisplayName(file);
        if (!isBlank(nestedOrEmbeddedName)) return nestedOrEmbeddedName;

        try (ZipFile zip = new ZipFile(file)) {
            String fabricJson = readZipEntryText(zip, "fabric.mod.json");
            String name = extractJsonString(fabricJson, "name");
            if (!isBlank(name)) return name;

            String quiltJson = readZipEntryText(zip, "quilt.mod.json");
            name = extractJsonString(quiltJson, "name");
            if (!isBlank(name)) return name;

            String forgeToml = readZipEntryText(zip, "META-INF/mods.toml");
            name = extractTomlString(forgeToml, "displayName");
            if (!isBlank(name)) return name;

            String mcmodInfo = readZipEntryText(zip, "mcmod.info");
            name = extractJsonString(mcmodInfo, "name");
            if (!isBlank(name)) return name;
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Nullable
    private String readZipEntryText(@NonNull ZipFile zip, @NonNull String entryName) throws IOException {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null || entry.isDirectory()) return null;
        if (entry.getSize() > 1024 * 1024) return null;

        try (InputStream input = zip.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copyStream(input, output);
            return output.toString("UTF-8");
        }
    }

    @Nullable
    private String extractJsonString(@Nullable String text, @NonNull String key) {
        if (text == null) return null;
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    @Nullable
    private String extractTomlString(@Nullable String text, @NonNull String key) {
        if (text == null) return null;
        Matcher matcher = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)')").matcher(text);
        if (!matcher.find()) return null;
        String doubleQuoted = matcher.group(1);
        String singleQuoted = matcher.group(2);
        return !isBlank(doubleQuoted) ? doubleQuoted : singleQuoted;
    }

    @Nullable
    private Bitmap loadIconForItem(@NonNull InstanceContentItem item) {
        File file = item.file;
        ResourceCategory category = item.category;
        if (category == ResourceCategory.WORLDS) {
            File worldIcon = new File(file, "icon.png");
            if (worldIcon.isFile()) return BitmapFactory.decodeFile(worldIcon.getAbsolutePath());
            return null;
        }

        if (file.isDirectory()) {
            File packIcon = new File(file, "pack.png");
            if (packIcon.isFile()) return BitmapFactory.decodeFile(packIcon.getAbsolutePath());
            File icon = new File(file, "icon.png");
            if (icon.isFile()) return BitmapFactory.decodeFile(icon.getAbsolutePath());
            return loadManifestIconForItem(item);
        }

        if (category == ResourceCategory.MODS) {
            Bitmap embeddedModIcon = ModJarMetadataExtractor.readIcon(file);
            if (embeddedModIcon != null) return embeddedModIcon;
        }

        try (ZipFile zip = new ZipFile(file)) {
            if (category == ResourceCategory.MODS) {
                Bitmap modIcon = loadModIcon(zip);
                if (modIcon != null) return modIcon;
            }

            Bitmap packIcon = decodeZipBitmap(zip, "pack.png");
            if (packIcon != null) return packIcon;

            Bitmap likelyIcon = decodeFirstLikelyIcon(zip);
            if (likelyIcon != null) return likelyIcon;

            return loadManifestIconForItem(item);
        } catch (Throwable ignored) {
            return loadManifestIconForItem(item);
        }
    }

    @Nullable
    private Bitmap loadManifestIconForItem(@NonNull InstanceContentItem item) {
        ModManagerContentType managerType = toModManagerContentType(item.category);
        if (managerType == null || gameDirectory == null) return null;

        File cachedIcon = ModManagerManifest.getInstalledIconFileForFile(gameDirectory, managerType, item.file);
        if (cachedIcon != null && cachedIcon.isFile()) {
            Bitmap bitmap = BitmapFactory.decodeFile(cachedIcon.getAbsolutePath());
            if (bitmap != null) return bitmap;
        }

        JSONObject entry = getInstalledEntryForItem(item);
        if (entry == null) return null;

        Bitmap metadataIcon = loadIconFromInstalledMetadata(item, managerType, entry);
        if (metadataIcon != null) return metadataIcon;

        return loadPlatformProjectIconForEntry(item, managerType, entry);
    }

    @Nullable
    private Bitmap loadIconFromInstalledMetadata(
            @NonNull InstanceContentItem item,
            @NonNull ModManagerContentType managerType,
            @NonNull JSONObject entry
    ) {
        File localIcon = resolveLocalIconFileFromEntry(entry);
        if (localIcon != null && localIcon.isFile()) {
            Bitmap bitmap = BitmapFactory.decodeFile(localIcon.getAbsolutePath());
            if (bitmap != null) return bitmap;
        }

        String iconUrl = resolveIconUrlFromEntry(entry);
        if (isBlank(iconUrl)) return null;

        File cacheFile = getInstalledContentIconCacheFile(item, managerType, entry);
        Bitmap cached = cacheFile.isFile() ? BitmapFactory.decodeFile(cacheFile.getAbsolutePath()) : null;
        if (cached != null) return cached;

        return downloadAndCacheBitmap(iconUrl, cacheFile, null);
    }

    @Nullable
    private Bitmap loadPlatformProjectIconForEntry(
            @NonNull InstanceContentItem item,
            @NonNull ModManagerContentType managerType,
            @NonNull JSONObject entry
    ) {
        ModManagerSource source = ModManagerManifest.getSource(entry);
        String projectId = getProjectIdFromEntry(entry);
        if (isBlank(projectId)) return null;

        File cacheFile = getInstalledContentIconCacheFile(item, managerType, entry);
        Bitmap cached = cacheFile.isFile() ? BitmapFactory.decodeFile(cacheFile.getAbsolutePath()) : null;
        if (cached != null) return cached;

        String iconUrl = "";
        if (source == ModManagerSource.MODRINTH) {
            iconUrl = fetchModrinthProjectIconUrl(projectId);
        } else if (source == ModManagerSource.CURSEFORGE) {
            iconUrl = fetchCurseForgeProjectIconUrl(projectId);
        }

        if (isBlank(iconUrl)) return null;
        return downloadAndCacheBitmap(iconUrl, cacheFile, null);
    }

    @Nullable
    private File resolveLocalIconFileFromEntry(@NonNull JSONObject entry) {
        String path = firstNonBlank(
                entry.optString("cachedIconPath", ""),
                entry.optString("iconCachePath", ""),
                entry.optString("installedIconPath", ""),
                entry.optString("localIconPath", ""),
                entry.optString("iconPath", ""),
                entry.optString("iconFile", "")
        );

        if (isBlank(path)) return null;
        if (path.startsWith("http://") || path.startsWith("https://") || path.startsWith("//")) return null;

        File direct = new File(path);
        if (direct.isFile()) return direct;

        if (gameDirectory != null) {
            File relative = new File(gameDirectory, path.replace('/', File.separatorChar));
            if (relative.isFile()) return relative;
        }

        return null;
    }

    @NonNull
    private String resolveIconUrlFromEntry(@NonNull JSONObject entry) {
        String direct = firstNonBlank(
                entry.optString("iconUrl", ""),
                entry.optString("iconURL", ""),
                entry.optString("icon_url", ""),
                entry.optString("projectIconUrl", ""),
                entry.optString("projectIconURL", ""),
                entry.optString("project_icon_url", ""),
                entry.optString("thumbnailUrl", ""),
                entry.optString("thumbnailURL", ""),
                entry.optString("thumbnail_url", ""),
                entry.optString("logoUrl", ""),
                entry.optString("logoURL", ""),
                entry.optString("logo_url", ""),
                entry.optString("imageUrl", ""),
                entry.optString("imageURL", ""),
                entry.optString("image_url", "")
        );
        if (!isBlank(direct)) return normalizeIconUrl(direct);

        String nested = firstNonBlank(
                resolveIconUrlFromObject(entry.optJSONObject("project")),
                resolveIconUrlFromObject(entry.optJSONObject("modrinthProject")),
                resolveIconUrlFromObject(entry.optJSONObject("curseForgeProject")),
                resolveIconUrlFromObject(entry.optJSONObject("curseforgeProject")),
                resolveIconUrlFromObject(entry.optJSONObject("data"))
        );
        return normalizeIconUrl(nested);
    }

    @NonNull
    private String resolveIconUrlFromObject(@Nullable JSONObject object) {
        if (object == null) return "";

        String direct = firstNonBlank(
                object.optString("iconUrl", ""),
                object.optString("iconURL", ""),
                object.optString("icon_url", ""),
                object.optString("thumbnailUrl", ""),
                object.optString("thumbnailURL", ""),
                object.optString("thumbnail_url", ""),
                object.optString("logoUrl", ""),
                object.optString("logoURL", ""),
                object.optString("logo_url", ""),
                object.optString("imageUrl", ""),
                object.optString("imageURL", ""),
                object.optString("image_url", "")
        );
        if (!isBlank(direct)) return direct;

        JSONObject logo = object.optJSONObject("logo");
        if (logo != null) {
            direct = firstNonBlank(
                    logo.optString("thumbnailUrl", ""),
                    logo.optString("thumbnailURL", ""),
                    logo.optString("url", "")
            );
            if (!isBlank(direct)) return direct;
        }

        JSONObject icon = object.optJSONObject("icon");
        if (icon != null) {
            direct = firstNonBlank(
                    icon.optString("thumbnailUrl", ""),
                    icon.optString("thumbnailURL", ""),
                    icon.optString("url", "")
            );
        }
        return direct;
    }

    @NonNull
    private String fetchModrinthProjectIconUrl(@NonNull String projectId) {
        try {
            String body = readNetworkText(
                    "https://api.modrinth.com/v2/project/" + Uri.encode(projectId),
                    null
            );
            JSONObject project = new JSONObject(body);
            return normalizeIconUrl(project.optString("icon_url", ""));
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to resolve Modrinth project icon for " + projectId + ": " + readableError(throwable));
            return "";
        }
    }

    @NonNull
    private String fetchCurseForgeProjectIconUrl(@NonNull String projectId) {
        try {
            String apiKey = CurseForgeApiKeyProvider.resolve();
            if (isBlank(apiKey)) return "";

            String body = readNetworkText(
                    "https://api.curseforge.com/v1/mods/" + Uri.encode(projectId),
                    apiKey
            );
            JSONObject root = new JSONObject(body);
            JSONObject data = root.optJSONObject("data");
            if (data == null) return "";
            JSONObject logo = data.optJSONObject("logo");
            if (logo == null) return "";

            return normalizeIconUrl(firstNonBlank(
                    logo.optString("thumbnailUrl", ""),
                    logo.optString("url", "")
            ));
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to resolve CurseForge project icon for " + projectId + ": " + readableError(throwable));
            return "";
        }
    }

    @NonNull
    private String readNetworkText(@NonNull String urlString, @Nullable String curseForgeApiKey) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("User-Agent", "DroidBridge");
        if (!isBlank(curseForgeApiKey)) {
            connection.setRequestProperty("x-api-key", curseForgeApiKey.trim());
        }

        int responseCode = connection.getResponseCode();
        InputStream input = responseCode >= 200 && responseCode < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        if (input == null) {
            connection.disconnect();
            throw new IOException("HTTP " + responseCode);
        }

        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copyStream(stream, output);
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("HTTP " + responseCode + ": " + output.toString("UTF-8"));
            }
            return output.toString("UTF-8");
        } finally {
            connection.disconnect();
        }
    }

    @Nullable
    private Bitmap downloadAndCacheBitmap(
            @NonNull String rawUrl,
            @NonNull File cacheFile,
            @Nullable String curseForgeApiKey
    ) {
        String url = normalizeIconUrl(rawUrl);
        if (isBlank(url)) return null;

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("User-Agent", "DroidBridge");
            if (!isBlank(curseForgeApiKey)) {
                connection.setRequestProperty("x-api-key", curseForgeApiKey.trim());
            }

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("HTTP " + responseCode);
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                int total = 0;
                int maxBytes = 4 * 1024 * 1024;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > maxBytes) throw new IOException("Icon is too large");
                    output.write(buffer, 0, read);
                }
            }

            byte[] bytes = output.toByteArray();
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) return null;

            File parent = cacheFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream fileOutput = new FileOutputStream(cacheFile)) {
                fileOutput.write(bytes);
            } catch (Throwable throwable) {
                Logging.i(TAG, "Unable to cache installed content icon: " + throwable.getMessage());
            }
            return bitmap;
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to download installed content icon: " + readableError(throwable));
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @NonNull
    private File getInstalledContentIconCacheFile(
            @NonNull InstanceContentItem item,
            @NonNull ModManagerContentType managerType,
            @NonNull JSONObject entry
    ) {
        File iconDirectory = DroidBridgeMetadataPaths.childDirectoryForWrite(gameDirectory, DroidBridgeMetadataPaths.CONTENT_ICONS_DIRECTORY);
        ModManagerSource source = ModManagerManifest.getSource(entry);
        String projectId = getProjectIdFromEntry(entry);
        String key = firstNonBlank(source.getId(), "unknown") + "-" + firstNonBlank(projectId, stripDisabledSuffix(item.file.getName()));
        return new File(iconDirectory, sanitizeCacheFileName(managerType.getIntentValue() + "-" + key) + ".png");
    }

    @NonNull
    private String normalizeIconUrl(@Nullable String value) {
        if (value == null) return "";
        String clean = value.trim();
        if (clean.isEmpty() || "null".equalsIgnoreCase(clean)) return "";
        if (clean.startsWith("//")) clean = "https:" + clean;
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) return "";
        return clean;
    }

    @NonNull
    private String firstNonBlank(@Nullable String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty() && !"null".equalsIgnoreCase(value.trim())) {
                return value.trim();
            }
        }
        return "";
    }

    @NonNull
    private String sanitizeCacheFileName(@NonNull String value) {
        String clean = value.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        if (clean.isEmpty()) clean = "icon";
        if (clean.length() > 120) clean = clean.substring(0, 120);
        return clean;
    }

    @Nullable
    private Bitmap loadModIcon(@NonNull ZipFile zip) throws IOException {
        String fabricJson = readZipEntryText(zip, "fabric.mod.json");
        Bitmap icon = decodeZipBitmap(zip, extractJsonIconString(fabricJson));
        if (icon != null) return icon;

        String quiltJson = readZipEntryText(zip, "quilt.mod.json");
        icon = decodeZipBitmap(zip, extractJsonIconString(quiltJson));
        if (icon != null) return icon;

        String forgeToml = readZipEntryText(zip, "META-INF/mods.toml");
        icon = decodeZipBitmap(zip, extractTomlString(forgeToml, "logoFile"));
        if (icon != null) return icon;

        String neoForgeToml = readZipEntryText(zip, "META-INF/neoforge.mods.toml");
        icon = decodeZipBitmap(zip, extractTomlString(neoForgeToml, "logoFile"));
        if (icon != null) return icon;

        return null;
    }

    @Nullable
    private String extractJsonIconString(@Nullable String text) {
        String directIcon = extractJsonString(text, "icon");
        if (!isBlank(directIcon)) return directIcon;
        if (text == null) return null;

        Matcher objectMatcher = Pattern.compile("\\\"icon\\\"\\s*:\\s*\\{([^}]+)\\}", Pattern.DOTALL).matcher(text);
        if (!objectMatcher.find()) return null;

        Matcher imageMatcher = Pattern.compile("\\\"[^\\\"]+\\\"\\s*:\\s*\\\"([^\\\"]+\\.(?:png|jpg|jpeg|webp))\\\"").matcher(objectMatcher.group(1));
        return imageMatcher.find() ? imageMatcher.group(1) : null;
    }

    @Nullable
    private Bitmap decodeFirstLikelyIcon(@NonNull ZipFile zip) throws IOException {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            String name = normalizeZipPath(entry.getName()).toLowerCase(Locale.US);
            String shortName = name;
            int slash = shortName.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < shortName.length()) shortName = shortName.substring(slash + 1);

            if (isLikelyIconPath(name) || isLikelyIconFileName(shortName)) {
                try (InputStream input = zip.getInputStream(entry)) {
                    Bitmap bitmap = BitmapFactory.decodeStream(input);
                    if (bitmap != null) return bitmap;
                }
            }
        }
        return null;
    }

    private boolean isLikelyIconPath(@NonNull String path) {
        return path.endsWith("/icon.png")
                || path.endsWith("/logo.png")
                || path.endsWith("/icon.jpg")
                || path.endsWith("/icon.jpeg")
                || path.endsWith("/icon.webp")
                || path.endsWith("/logo.jpg")
                || path.endsWith("/logo.jpeg")
                || path.endsWith("/logo.webp")
                || path.endsWith("/mod_icon.png")
                || path.endsWith("/modicon.png");
    }

    private boolean isLikelyIconFileName(@NonNull String name) {
        return "icon.png".equals(name)
                || "logo.png".equals(name)
                || "pack.png".equals(name)
                || "icon.jpg".equals(name)
                || "icon.jpeg".equals(name)
                || "icon.webp".equals(name)
                || "logo.jpg".equals(name)
                || "logo.jpeg".equals(name)
                || "logo.webp".equals(name)
                || "mod_icon.png".equals(name)
                || "modicon.png".equals(name);
    }

    @Nullable
    private Bitmap decodeZipBitmap(@NonNull ZipFile zip, @Nullable String entryName) throws IOException {
        if (isBlank(entryName)) return null;

        String normalized = normalizeZipPath(entryName.trim());
        while (normalized.startsWith("/")) normalized = normalized.substring(1);

        ZipEntry entry = zip.getEntry(normalized);
        if (entry == null && normalized.startsWith("./")) {
            entry = zip.getEntry(normalized.substring(2));
        }
        if (entry == null) {
            entry = findZipEntryBySuffix(zip, normalized);
        }
        if (entry == null || entry.isDirectory()) return null;

        try (InputStream input = zip.getInputStream(entry)) {
            return BitmapFactory.decodeStream(input);
        }
    }

    @Nullable
    private ZipEntry findZipEntryBySuffix(@NonNull ZipFile zip, @NonNull String normalizedName) {
        String lowerName = normalizedName.toLowerCase(Locale.US);
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            String entryName = normalizeZipPath(entry.getName()).toLowerCase(Locale.US);
            if (entryName.equals(lowerName) || entryName.endsWith("/" + lowerName)) return entry;
        }
        return null;
    }

    @NonNull
    private String stripExtension(@NonNull String name) {
        String cleanName = stripDisabledSuffix(name);
        int dot = cleanName.lastIndexOf('.');
        return dot > 0 ? cleanName.substring(0, dot) : cleanName;
    }

    private boolean isContentItemEnabled(@NonNull File file) {
        return !file.getName().toLowerCase(Locale.US).endsWith(".disabled");
    }

    @NonNull
    private String stripDisabledSuffix(@NonNull String name) {
        return name.toLowerCase(Locale.US).endsWith(".disabled")
                ? name.substring(0, name.length() - ".disabled".length())
                : name;
    }

    @NonNull
    private String removeDisabledSuffix(@NonNull String name) {
        return stripDisabledSuffix(name);
    }

    @NonNull
    private String formatSubtitle(@NonNull InstanceContentItem item) {
        String subtitle;
        if (item.file.isDirectory()) {
            subtitle = getString(R.string.instance_content_folder_subtitle, item.file.getName());
        } else {
            subtitle = getString(R.string.instance_content_file_subtitle, item.file.getName(), formatFileSize(item.file.length()));
        }

        if (item.category.supportsDisableToggle && !isContentItemEnabled(item.file)) {
            subtitle = subtitle + " · " + getString(R.string.instance_content_disabled_label);
        }
        return subtitle;
    }

    @NonNull
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes / 1024.0;
        String[] units = {"KB", "MB", "GB"};
        int unitIndex = 0;
        while (value >= 1024 && unitIndex < units.length - 1) {
            value /= 1024.0;
            unitIndex++;
        }
        return String.format(Locale.US, "%.1f %s", value, units[unitIndex]);
    }

    @Nullable
    private ModManagerContentType toModManagerContentType(@NonNull ResourceCategory category) {
        switch (category) {
            case MODS:
                return ModManagerContentType.MODS;
            case SHADERPACKS:
                return ModManagerContentType.SHADERPACKS;
            case RESOURCEPACKS:
                return ModManagerContentType.RESOURCEPACKS;
            case WORLDS:
            case SCREENSHOTS:
            default:
                return null;
        }
    }

    @NonNull
    private ModManagerSource getInstalledSourceForItem(@NonNull InstanceContentItem item) {
        ModManagerContentType managerType = toModManagerContentType(item.category);
        if (managerType == null || gameDirectory == null) return ModManagerSource.UNKNOWN;
        ModManagerSource source = ModManagerManifest.getInstalledSourceForFile(gameDirectory, managerType, item.file);
        if (source != ModManagerSource.UNKNOWN) return source;
        JSONObject entry = getModpackInstalledEntryForFile(managerType, item.file);
        return entry == null ? ModManagerSource.UNKNOWN : ModManagerManifest.getSource(entry);
    }


    @NonNull
    private String displayLoader(@Nullable String value) {
        if (value == null || value.trim().isEmpty()) return "Vanilla";
        return value.substring(0, 1).toUpperCase(Locale.US) + value.substring(1);
    }

    @NonNull
    private String displayVersionType(@Nullable String value) {
        if (value == null || value.trim().isEmpty()) return "Release";
        switch (value) {
            case "release":
                return "Release";
            case "snapshot":
                return "Snapshot";
            case "old_beta":
                return "Beta";
            case "old_alpha":
                return "Alpha";
            default:
                return value.substring(0, 1).toUpperCase(Locale.US) + value.substring(1).replace('_', ' ');
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    @NonNull
    private LoadedContentMetadata loadContentMetadataForItem(@NonNull InstanceContentItem item) {
        String displayName = null;
        Bitmap icon = null;

        if (item.category == ResourceCategory.MODS && item.file.isFile()) {
            ModJarMetadataExtractor.Result result = ModJarMetadataExtractor.read(item.file);
            if (result != null) {
                displayName = result.getDisplayName();
                icon = result.getIcon();
            }

            if (icon == null) {
                try (ZipFile zip = new ZipFile(item.file)) {
                    icon = loadModIcon(zip);
                    if (icon == null) icon = decodeZipBitmap(zip, "pack.png");
                    if (icon == null) icon = decodeFirstLikelyIcon(zip);
                } catch (Throwable ignored) {
                }
            }

            if (icon == null) icon = loadManifestIconForItem(item);
            return new LoadedContentMetadata(displayName, icon);
        }

        icon = loadIconForItem(item);
        return new LoadedContentMetadata(null, icon);
    }

    private static final class LoadedContentMetadata {
        @Nullable
        final String displayName;
        @Nullable
        final Bitmap icon;

        LoadedContentMetadata(@Nullable String displayName, @Nullable Bitmap icon) {
            this.displayName = displayName;
            this.icon = icon;
        }

        boolean hasAny() {
            return displayName != null && !displayName.trim().isEmpty() || icon != null;
        }
    }

    private enum ContentVisibilityFilter {
        ALL,
        ENABLED_ONLY,
        DISABLED_ONLY
    }

    private enum ResourceCategory {
        MODS(
                R.string.instance_tab_mods,
                R.string.button_upload_mods,
                R.string.button_browse_mods,
                R.string.mods_picker_title,
                R.string.instance_content_mods_plural,
                R.drawable.ic_instance_mod_24,
                "*/*",
                new String[]{"application/java-archive", "application/x-java-archive", "application/zip", "application/octet-stream"},
                ".jar",
                true,
                true,
                true
        ),
        SHADERPACKS(
                R.string.instance_tab_shaderpacks,
                R.string.button_upload_shaderpacks,
                R.string.button_browse_shaderpacks,
                R.string.shaderpacks_picker_title,
                R.string.instance_content_shaderpacks_plural,
                R.drawable.ic_instance_shaderpack_24,
                "*/*",
                new String[]{"application/zip", "application/octet-stream"},
                ".zip",
                true,
                true,
                true
        ),
        RESOURCEPACKS(
                R.string.instance_tab_resourcepacks,
                R.string.button_upload_resourcepacks,
                R.string.button_browse_resourcepacks,
                R.string.resourcepacks_picker_title,
                R.string.instance_content_resourcepacks_plural,
                R.drawable.ic_instance_resourcepack_24,
                "*/*",
                new String[]{"application/zip", "application/octet-stream"},
                ".zip",
                true,
                true,
                true
        ),
        WORLDS(
                R.string.instance_tab_worlds,
                R.string.button_upload_worlds,
                0,
                R.string.worlds_picker_title,
                R.string.instance_content_worlds_plural,
                R.drawable.ic_instance_world_24,
                "*/*",
                new String[]{"application/zip", "application/octet-stream"},
                ".zip",
                false,
                false,
                false
        ),
        SCREENSHOTS(
                R.string.instance_tab_screenshots,
                0,
                0,
                0,
                R.string.instance_content_screenshots_plural,
                R.drawable.ic_screenshot_24,
                "image/*",
                new String[]{"image/png", "image/jpeg", "image/webp"},
                null,
                false,
                false,
                false
        );

        @StringRes
        final int tabTitleRes;
        @StringRes
        final int uploadButtonTextRes;
        @StringRes
        final int browseButtonTextRes;
        @StringRes
        final int pickerTitleRes;
        @StringRes
        final int pluralLabelRes;
        @DrawableRes
        final int defaultIconRes;
        @NonNull
        final String mimeType;
        @NonNull
        final String[] mimeTypes;
        @Nullable
        final String defaultExtension;
        final boolean supportsUpdatePlaceholder;
        final boolean supportsBrowse;
        final boolean supportsDisableToggle;

        ResourceCategory(
                @StringRes int tabTitleRes,
                @StringRes int uploadButtonTextRes,
                @StringRes int browseButtonTextRes,
                @StringRes int pickerTitleRes,
                @StringRes int pluralLabelRes,
                @DrawableRes int defaultIconRes,
                @NonNull String mimeType,
                @NonNull String[] mimeTypes,
                @Nullable String defaultExtension,
                boolean supportsUpdatePlaceholder,
                boolean supportsBrowse,
                boolean supportsDisableToggle
        ) {
            this.tabTitleRes = tabTitleRes;
            this.uploadButtonTextRes = uploadButtonTextRes;
            this.browseButtonTextRes = browseButtonTextRes;
            this.pickerTitleRes = pickerTitleRes;
            this.pluralLabelRes = pluralLabelRes;
            this.defaultIconRes = defaultIconRes;
            this.mimeType = mimeType;
            this.mimeTypes = mimeTypes;
            this.defaultExtension = defaultExtension;
            this.supportsUpdatePlaceholder = supportsUpdatePlaceholder;
            this.supportsBrowse = supportsBrowse;
            this.supportsDisableToggle = supportsDisableToggle;
        }
    }

    private enum UpdateState {
        UNKNOWN,
        CHECKING,
        UPDATE_AVAILABLE,
        UPDATING,
        UP_TO_DATE,
        ERROR
    }

    private static final class InstanceContentItem {
        @NonNull
        final File file;
        @NonNull
        final ResourceCategory category;
        @NonNull
        final String title;

        InstanceContentItem(@NonNull File file, @NonNull ResourceCategory category, @NonNull String title) {
            this.file = file;
            this.category = category;
            this.title = title;
        }
    }

    private void showScreenshotViewer(@NonNull File screenshotFile) {
        if (!screenshotFile.isFile()) {
            Toast.makeText(this, R.string.instance_screenshot_missing, Toast.LENGTH_LONG).show();
            refreshContentList();
            return;
        }

        Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(R.layout.dialog_instance_screenshot_viewer);
        dialog.setCancelable(true);

        ImageView imageView = dialog.findViewById(R.id.imageScreenshotFullscreen);
        ProgressBar progressBar = dialog.findViewById(R.id.progressScreenshotFullscreen);
        MaterialButton backButton = dialog.findViewById(R.id.buttonScreenshotBack);
        MaterialButton shareButton = dialog.findViewById(R.id.buttonScreenshotShare);
        MaterialButton downloadButton = dialog.findViewById(R.id.buttonScreenshotDownload);
        MaterialButton deleteButton = dialog.findViewById(R.id.buttonScreenshotDelete);

        final Bitmap[] displayedBitmap = new Bitmap[1];
        backButton.setOnClickListener(view -> dialog.dismiss());
        shareButton.setOnClickListener(view -> shareScreenshot(screenshotFile));
        downloadButton.setOnClickListener(view -> downloadScreenshotToPictures(screenshotFile));
        deleteButton.setOnClickListener(view -> confirmDeleteScreenshot(screenshotFile, dialog));

        dialog.setOnDismissListener(unused -> {
            if (imageView != null) imageView.setImageDrawable(null);
            Bitmap bitmap = displayedBitmap[0];
            displayedBitmap[0] = null;
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            mainHandler.postDelayed(this::enableFullscreen, 80L);
        });

        dialog.show();
        Window dialogWindow = dialog.getWindow();
        if (dialogWindow != null) {
            dialogWindow.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            dialogWindow.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            applyFullscreenToWindow(dialogWindow);
        }

        int targetWidth = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        int targetHeight = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        iconExecutor.execute(() -> {
            Bitmap bitmap = decodeSampledBitmap(screenshotFile, targetWidth, targetHeight, false);
            mainHandler.post(() -> {
                if (!dialog.isShowing()) {
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                    return;
                }
                progressBar.setVisibility(View.GONE);
                if (bitmap == null) {
                    Toast.makeText(this, R.string.instance_screenshot_open_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                displayedBitmap[0] = bitmap;
                imageView.setImageBitmap(bitmap);
            });
        });
    }

    private void shareScreenshot(@NonNull File screenshotFile) {
        Toast.makeText(this, R.string.instance_screenshot_preparing_share, Toast.LENGTH_SHORT).show();
        contentOperationExecutor.execute(() -> {
            try {
                File shareDirectory = new File(getCacheDir(), "shared_screenshots");
                if (!shareDirectory.exists() && !shareDirectory.mkdirs()) {
                    throw new IOException("Unable to create screenshot share folder.");
                }
                String safeName = sanitizeImportedFileName(screenshotFile.getName(), null);
                if (isBlank(safeName)) safeName = "minecraft-screenshot.png";
                File shareFile = new File(shareDirectory, safeName);
                copyFile(screenshotFile, shareFile);
                shareFile.setReadable(true, false);

                Uri uri = FileProvider.getUriForFile(
                        this,
                        getPackageName() + ".fileprovider",
                        shareFile
                );
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType(resolveScreenshotMimeType(screenshotFile));
                shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                shareIntent.setClipData(ClipData.newUri(getContentResolver(), screenshotFile.getName(), uri));

                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    try {
                        startActivity(Intent.createChooser(
                                shareIntent,
                                getString(R.string.instance_screenshot_share_chooser)
                        ));
                    } catch (ActivityNotFoundException throwable) {
                        Toast.makeText(this, R.string.instance_screenshot_no_share_app, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to share screenshot " + screenshotFile.getAbsolutePath(), throwable);
                mainHandler.post(() -> Toast.makeText(
                        this,
                        getString(R.string.instance_screenshot_share_failed, readableError(throwable)),
                        Toast.LENGTH_LONG
                ).show());
            }
        });
    }

    @NonNull
    private String resolveScreenshotMimeType(@NonNull File screenshotFile) {
        String name = screenshotFile.getName().toLowerCase(Locale.US);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        return "image/png";
    }

    private void downloadScreenshotToPictures(@NonNull File screenshotFile) {
        if (!screenshotFile.isFile()) {
            Toast.makeText(this, R.string.instance_screenshot_missing, Toast.LENGTH_LONG).show();
            refreshContentList();
            return;
        }

        Toast.makeText(this, R.string.instance_screenshot_preparing_download, Toast.LENGTH_SHORT).show();
        contentOperationExecutor.execute(() -> {
            Uri pendingMediaUri = null;
            try {
                String safeName = sanitizeImportedFileName(screenshotFile.getName(), null);
                if (isBlank(safeName)) safeName = "minecraft-screenshot.png";
                String mimeType = resolveScreenshotMimeType(screenshotFile);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, safeName);
                    values.put(MediaStore.Images.Media.MIME_TYPE, mimeType);
                    values.put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/DroidBridge/Screenshots"
                    );
                    values.put(MediaStore.Images.Media.IS_PENDING, 1);

                    pendingMediaUri = getContentResolver().insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            values
                    );
                    if (pendingMediaUri == null) {
                        throw new IOException("Unable to create the Pictures media entry.");
                    }

                    try (InputStream input = new java.io.FileInputStream(screenshotFile);
                         OutputStream output = getContentResolver().openOutputStream(pendingMediaUri, "w")) {
                        if (output == null) {
                            throw new IOException("Unable to open the Pictures media entry.");
                        }
                        copyStream(input, output);
                    }

                    ContentValues completedValues = new ContentValues();
                    completedValues.put(MediaStore.Images.Media.IS_PENDING, 0);
                    if (getContentResolver().update(
                            pendingMediaUri,
                            completedValues,
                            null,
                            null
                    ) <= 0) {
                        throw new IOException("Unable to publish the downloaded screenshot.");
                    }
                    pendingMediaUri = null;
                } else {
                    File picturesDirectory = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_PICTURES
                    );
                    File downloadDirectory = new File(
                            picturesDirectory,
                            "DroidBridge/Screenshots"
                    );
                    if (!downloadDirectory.exists() && !downloadDirectory.mkdirs()) {
                        throw new IOException(
                                "Unable to create folder: " + downloadDirectory.getAbsolutePath()
                        );
                    }

                    File downloadedFile = uniqueTargetFile(downloadDirectory, safeName);
                    copyFile(screenshotFile, downloadedFile);
                    MediaScannerConnection.scanFile(
                            this,
                            new String[]{downloadedFile.getAbsolutePath()},
                            new String[]{mimeType},
                            null
                    );
                }

                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(
                            this,
                            R.string.instance_screenshot_download_success,
                            Toast.LENGTH_LONG
                    ).show();
                });
            } catch (Throwable throwable) {
                if (pendingMediaUri != null) {
                    try {
                        getContentResolver().delete(pendingMediaUri, null, null);
                    } catch (Throwable cleanupError) {
                        Logging.e(TAG, "Unable to remove incomplete screenshot download", cleanupError);
                    }
                }
                Logging.e(TAG, "Unable to download screenshot " + screenshotFile.getAbsolutePath(), throwable);
                mainHandler.post(() -> Toast.makeText(
                        this,
                        getString(
                                R.string.instance_screenshot_download_failed_detail,
                                readableError(throwable)
                        ),
                        Toast.LENGTH_LONG
                ).show());
            }
        });
    }

    private void confirmDeleteScreenshot(@NonNull File screenshotFile, @NonNull Dialog viewerDialog) {
        AlertDialog confirmation = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.instance_screenshot_delete_title)
                .setMessage(getString(R.string.instance_screenshot_delete_message, screenshotFile.getName()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.button_delete_forever, (unused, which) ->
                        deleteScreenshot(screenshotFile, viewerDialog))
                .create();
        showFullscreenSafeDialog(confirmation);
    }

    private void deleteScreenshot(@NonNull File screenshotFile, @NonNull Dialog viewerDialog) {
        contentOperationExecutor.execute(() -> {
            boolean deleted = false;
            String failureMessage = null;
            try {
                // A mirror-backed SAF location must be deleted at the picked tree too,
                // otherwise the old screenshot can be restored into the local mirror later.
                StorageLocationStore.deleteFromScopedStorageIfNeeded(this, screenshotFile);
                deleted = !screenshotFile.exists() || screenshotFile.delete();
                if (!deleted) failureMessage = getString(R.string.instance_screenshot_delete_failed);
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to delete screenshot " + screenshotFile.getAbsolutePath(), throwable);
                failureMessage = getString(R.string.instance_screenshot_delete_failed_detail, readableError(throwable));
            }

            boolean finalDeleted = deleted;
            String finalFailureMessage = failureMessage;
            mainHandler.post(() -> {
                if (finalDeleted) {
                    if (screenshotAdapter != null) screenshotAdapter.removeFromThumbnailCache(screenshotFile);
                    if (viewerDialog.isShowing()) viewerDialog.dismiss();
                    refreshContentList();
                    Toast.makeText(this, R.string.instance_screenshot_delete_success, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(
                            this,
                            isBlank(finalFailureMessage)
                                    ? getString(R.string.instance_screenshot_delete_failed)
                                    : finalFailureMessage,
                            Toast.LENGTH_LONG
                    ).show();
                }
            });
        });
    }

    @Nullable
    private Bitmap decodeSampledBitmap(
            @NonNull File imageFile,
            int requestedWidth,
            int requestedHeight,
            boolean thumbnail
    ) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(imageFile.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = calculateBitmapSampleSize(
                    bounds.outWidth,
                    bounds.outHeight,
                    Math.max(1, requestedWidth),
                    Math.max(1, requestedHeight)
            );
            options.inPreferredConfig = thumbnail ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
            options.inDither = thumbnail;
            return BitmapFactory.decodeFile(imageFile.getAbsolutePath(), options);
        } catch (OutOfMemoryError error) {
            Logging.e(TAG, "Out of memory decoding screenshot " + imageFile.getAbsolutePath(), error);
            return null;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to decode screenshot " + imageFile.getAbsolutePath(), throwable);
            return null;
        }
    }

    private int calculateBitmapSampleSize(int width, int height, int requestedWidth, int requestedHeight) {
        double widthRatio = width / (double) Math.max(1, requestedWidth);
        double heightRatio = height / (double) Math.max(1, requestedHeight);
        double largestRatio = Math.max(widthRatio, heightRatio);
        int sampleSize = 1;
        while ((sampleSize * 2) <= largestRatio) {
            sampleSize *= 2;
        }
        return Math.max(1, sampleSize);
    }

    private final class ScreenshotAdapter extends RecyclerView.Adapter<ScreenshotAdapter.ScreenshotViewHolder> {
        private final Set<String> loadingThumbnails = Collections.newSetFromMap(new ConcurrentHashMap<>());
        private final LruCache<String, Bitmap> thumbnailCache;

        ScreenshotAdapter() {
            setHasStableIds(true);
            int maxMemoryKb = (int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / 1024L);
            int cacheSizeKb = Math.max(4 * 1024, Math.min(24 * 1024, maxMemoryKb / 16));
            thumbnailCache = new LruCache<String, Bitmap>(cacheSizeKb) {
                @Override
                protected int sizeOf(@NonNull String key, @NonNull Bitmap bitmap) {
                    return Math.max(1, bitmap.getByteCount() / 1024);
                }
            };
        }

        @NonNull
        @Override
        public ScreenshotViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_instance_screenshot, parent, false);
            return new ScreenshotViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ScreenshotViewHolder holder, int position) {
            InstanceContentItem item = contentItems.get(position);
            String cacheKey = screenshotCacheKey(item.file);
            holder.itemView.setTag(cacheKey);
            holder.name.setText(item.file.getName());
            holder.thumbnail.setContentDescription(getString(
                    R.string.instance_screenshot_thumbnail_description,
                    item.file.getName()
            ));
            holder.itemView.setOnClickListener(view -> showScreenshotViewer(item.file));

            Bitmap cached = thumbnailCache.get(cacheKey);
            if (cached != null && !cached.isRecycled()) {
                holder.progress.setVisibility(View.GONE);
                holder.thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
                holder.thumbnail.setImageBitmap(cached);
                return;
            }

            holder.thumbnail.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            holder.thumbnail.setImageResource(R.drawable.ic_screenshot_24);
            holder.progress.setVisibility(View.VISIBLE);
            loadThumbnail(holder, item.file, cacheKey);
        }

        private void loadThumbnail(
                @NonNull ScreenshotViewHolder holder,
                @NonNull File screenshotFile,
                @NonNull String cacheKey
        ) {
            if (!loadingThumbnails.add(cacheKey)) return;
            int requestedSize = Math.min(
                    dp(360),
                    Math.max(dp(180), getResources().getDisplayMetrics().widthPixels / 2)
            );
            iconExecutor.execute(() -> {
                Bitmap bitmap = decodeSampledBitmap(screenshotFile, requestedSize, requestedSize, true);
                mainHandler.post(() -> {
                    loadingThumbnails.remove(cacheKey);
                    if (binding == null || isFinishing() || isDestroyed()) {
                        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                        return;
                    }
                    if (bitmap != null) thumbnailCache.put(cacheKey, bitmap);
                    Object tag = holder.itemView.getTag();
                    if (!(tag instanceof String) || !cacheKey.equals(tag)) return;
                    holder.progress.setVisibility(View.GONE);
                    if (bitmap != null) {
                        holder.thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        holder.thumbnail.setImageBitmap(bitmap);
                    }
                });
            });
        }

        @Override
        public long getItemId(int position) {
            if (position < 0 || position >= contentItems.size()) return RecyclerView.NO_ID;
            return screenshotCacheKey(contentItems.get(position).file).hashCode();
        }

        @Override
        public int getItemCount() {
            return contentItems.size();
        }

        @NonNull
        private String screenshotCacheKey(@NonNull File file) {
            return safeCanonicalPath(file) + ":" + file.length() + ":" + file.lastModified();
        }

        void removeFromThumbnailCache(@NonNull File file) {
            String canonicalPrefix = safeCanonicalPath(file) + ":";
            for (String key : new ArrayList<>(thumbnailCache.snapshot().keySet())) {
                if (key.startsWith(canonicalPrefix)) thumbnailCache.remove(key);
            }
        }

        void clearThumbnailCache() {
            thumbnailCache.evictAll();
            loadingThumbnails.clear();
        }

        final class ScreenshotViewHolder extends RecyclerView.ViewHolder {
            final ImageView thumbnail;
            final TextView name;
            final ProgressBar progress;

            ScreenshotViewHolder(@NonNull View itemView) {
                super(itemView);
                thumbnail = itemView.findViewById(R.id.imageScreenshotThumbnail);
                name = itemView.findViewById(R.id.textScreenshotName);
                progress = itemView.findViewById(R.id.progressScreenshotThumbnail);
            }
        }
    }

    private final class InstanceContentAdapter extends RecyclerView.Adapter<InstanceContentAdapter.ContentViewHolder> {
        private final Map<String, LoadedContentMetadata> metadataCache = new ConcurrentHashMap<>();
        private final Set<String> missingMetadataCache = Collections.newSetFromMap(new ConcurrentHashMap<>());
        private final Set<String> loadingMetadata = Collections.newSetFromMap(new ConcurrentHashMap<>());
        private final Map<String, ModManagerSource> sourceCache = new ConcurrentHashMap<>();
        private final Set<String> loadingSource = Collections.newSetFromMap(new ConcurrentHashMap<>());

        InstanceContentAdapter() {
            setHasStableIds(true);
        }

        void clearTransientCachesForFile(@NonNull String canonicalPath) {
            for (String key : new ArrayList<>(metadataCache.keySet())) {
                if (key.startsWith(canonicalPath + ":")) metadataCache.remove(key);
            }
            for (String key : new ArrayList<>(sourceCache.keySet())) {
                if (key.startsWith(canonicalPath + ":")) sourceCache.remove(key);
            }
            for (String key : new ArrayList<>(missingMetadataCache)) {
                if (key.startsWith(canonicalPath + ":")) missingMetadataCache.remove(key);
            }
        }

        @NonNull
        @Override
        public ContentViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_instance_resource, parent, false);
            return new ContentViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ContentViewHolder holder, int position) {
            InstanceContentItem item = contentItems.get(position);
            String metadataKey = buildMetadataKey(item);

            holder.itemView.setTag(metadataKey);
            holder.title.setText(item.title);
            holder.subtitle.setText(formatSubtitle(item));
            boolean selected = isContentItemSelected(item);
            holder.itemView.setSelected(selected);
            holder.selectionCheckBox.setVisibility(contentSelectionMode ? View.VISIBLE : View.GONE);
            holder.selectionCheckBox.setOnCheckedChangeListener(null);
            holder.selectionCheckBox.setChecked(selected);
            holder.selectionCheckBox.setOnClickListener(view -> toggleContentItemSelected(resolveContentItemForAction(item)));
            applyFallbackIcon(holder, item);

            LoadedContentMetadata cached = metadataCache.get(metadataKey);
            if (cached != null) {
                applyLoadedMetadata(holder, item, metadataKey, cached);
            } else if (!missingMetadataCache.contains(metadataKey)) {
                loadMetadataAsync(holder, item, metadataKey);
            }

            ModManagerSource installedSource = sourceCache.get(metadataKey);
            if (installedSource == null) {
                holder.sourceIcon.setVisibility(View.GONE);
                loadSourceAsync(holder, item, metadataKey);
            } else {
                holder.sourceIcon.setVisibility(installedSource.hasIcon() ? View.VISIBLE : View.GONE);
                if (installedSource.hasIcon()) {
                    holder.sourceIcon.setImageResource(installedSource.getIconRes());
                    holder.sourceIcon.setContentDescription(getString(R.string.modmanager_installed_from, installedSource.getDisplayName()));
                }
            }

            boolean canPlayWorld = item.category == ResourceCategory.WORLDS && item.file.isDirectory();
            holder.playWorldButton.setVisibility(canPlayWorld ? View.VISIBLE : View.GONE);
            holder.playWorldButton.setOnClickListener(canPlayWorld ? view -> {
                prepareContentRowAction();
                launchInstance(resolveContentItemForAction(item).file.getName());
            } : null);

            if (item.category == ResourceCategory.WORLDS && item.file.isDirectory()) {
                bindWorldExportButton(holder, item);
            } else if (item.category.supportsUpdatePlaceholder) {
                bindUpdateButton(holder, item);
            } else {
                holder.updateButton.setVisibility(View.GONE);
                holder.updateButton.setOnClickListener(null);
            }

            boolean canToggle = item.category.supportsDisableToggle && item.file.isFile();
            holder.enabledSwitch.setOnCheckedChangeListener(null);
            holder.enabledSwitch.setVisibility(canToggle ? View.VISIBLE : View.GONE);
            holder.enabledSwitch.setChecked(isContentItemEnabled(item.file));
            if (canToggle) {
                holder.enabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> setContentItemEnabledFromRow(item, isChecked));
            }

            holder.deleteButton.setVisibility(View.VISIBLE);
            holder.deleteButton.setOnClickListener(view -> showDeleteContentItemDialogFromRow(item));

            boolean canShowInfo = item.category == ResourceCategory.MODS;
            holder.itemView.setClickable(true);
            holder.itemView.setFocusable(true);
            holder.itemView.setOnClickListener(view -> {
                InstanceContentItem actionItem = resolveContentItemForAction(item);
                if (contentSelectionMode) {
                    toggleContentItemSelected(actionItem);
                } else if (canShowInfo) {
                    showInstalledContentInfoDialog(actionItem);
                }
            });
            holder.itemView.setOnLongClickListener(view -> {
                toggleContentItemSelected(resolveContentItemForAction(item));
                return true;
            });
            applyContentRowState(holder, item, selected);
        }

        private void applyContentRowState(@NonNull ContentViewHolder holder, @NonNull InstanceContentItem item, boolean selected) {
            if (!(holder.itemView instanceof MaterialCardView)) return;
            MaterialCardView card = (MaterialCardView) holder.itemView;
            UpdateState state = getUpdateStateForItem(item);
            int surface = resolveThemeColor(com.google.android.material.R.attr.colorSurface, 0xFF141414);
            int surfaceVariant = resolveThemeColor(com.google.android.material.R.attr.colorSurfaceVariant, 0xFF242424);
            int primaryContainer = resolveThemeColor(com.google.android.material.R.attr.colorPrimaryContainer, 0xFF2D4D3A);
            int primary = resolveThemeColor(com.google.android.material.R.attr.colorPrimary, 0xFF20C975);
            int errorContainer = resolveThemeColor(com.google.android.material.R.attr.colorErrorContainer, 0xFF5A1A1A);
            int error = resolveThemeColor(com.google.android.material.R.attr.colorError, 0xFFFF6B6B);
            int outline = resolveThemeColor(com.google.android.material.R.attr.colorOutlineVariant, 0xFF6B5E58);

            if (selected) {
                card.setCardBackgroundColor(primaryContainer);
                card.setStrokeColor(primary);
                card.setStrokeWidth(dp(2));
            } else if (state == UpdateState.UPDATE_AVAILABLE) {
                card.setCardBackgroundColor(errorContainer);
                card.setStrokeColor(error);
                card.setStrokeWidth(dp(2));
            } else if (state == UpdateState.UPDATING || state == UpdateState.CHECKING) {
                card.setCardBackgroundColor(surfaceVariant);
                card.setStrokeColor(primary);
                card.setStrokeWidth(dp(1));
            } else {
                card.setCardBackgroundColor(surface);
                card.setStrokeColor(outline);
                card.setStrokeWidth(dp(1));
            }
        }

        private void applyFallbackIcon(
                @NonNull ContentViewHolder holder,
                @NonNull InstanceContentItem item
        ) {
            holder.icon.setBackgroundResource(R.drawable.bg_instance_icon);
            holder.icon.setPadding(dp(8), dp(8), dp(8), dp(8));
            holder.icon.setScaleType(ImageView.ScaleType.FIT_XY);
            holder.icon.setImageResource(item.category.defaultIconRes);
        }

        private void applyLoadedIcon(
                @NonNull ContentViewHolder holder,
                @NonNull Bitmap icon
        ) {
            holder.icon.setBackgroundResource(R.drawable.bg_instance_icon);
            holder.icon.setPadding(dp(4), dp(4), dp(4), dp(4));
            holder.icon.setScaleType(ImageView.ScaleType.FIT_XY);
            holder.icon.setImageBitmap(icon);
        }


        private void loadSourceAsync(
                @NonNull ContentViewHolder holder,
                @NonNull InstanceContentItem item,
                @NonNull String metadataKey
        ) {
            if (!loadingSource.add(metadataKey)) return;
            final int expectedPageGeneration = contentPageGeneration;

            iconExecutor.execute(() -> {
                if (expectedPageGeneration != contentPageGeneration) {
                    loadingSource.remove(metadataKey);
                    return;
                }

                ModManagerSource source;
                try {
                    source = getInstalledSourceForItem(item);
                } catch (Throwable throwable) {
                    Logging.i(TAG, "Unable to resolve installed source for " + item.file.getName() + ": " + readableError(throwable));
                    source = ModManagerSource.UNKNOWN;
                }

                ModManagerSource finalSource = source == null ? ModManagerSource.UNKNOWN : source;
                mainHandler.post(() -> {
                    loadingSource.remove(metadataKey);
                    if (expectedPageGeneration != contentPageGeneration) return;
                    sourceCache.put(metadataKey, finalSource);

                    if (binding == null || isFinishing() || isDestroyed()) return;
                    Object tag = holder.itemView.getTag();
                    if (!(tag instanceof String) || !metadataKey.equals(tag)) return;

                    holder.sourceIcon.setVisibility(finalSource.hasIcon() ? View.VISIBLE : View.GONE);
                    if (finalSource.hasIcon()) {
                        holder.sourceIcon.setImageResource(finalSource.getIconRes());
                        holder.sourceIcon.setContentDescription(getString(R.string.modmanager_installed_from, finalSource.getDisplayName()));
                    }
                });
            });
        }


        private void bindWorldExportButton(@NonNull ContentViewHolder holder, @NonNull InstanceContentItem item) {
            holder.updateButton.setVisibility(View.VISIBLE);
            holder.updateButton.setText("");
            holder.updateButton.setEnabled(true);
            holder.updateButton.setIconResource(R.drawable.ic_arrow_upward_24);
            holder.updateButton.setContentDescription("Export World");
            holder.updateButton.setOnClickListener(view -> startWorldExportFromRow(item));
        }

        private void bindUpdateButton(@NonNull ContentViewHolder holder, @NonNull InstanceContentItem item) {
            holder.updateButton.setVisibility(View.VISIBLE);
            holder.updateButton.setText("");
            holder.updateButton.setEnabled(true);
            holder.updateButton.setBackgroundTintList(ColorStateList.valueOf(resolveThemeColor(com.google.android.material.R.attr.colorSurface, 0xFF141414)));
            holder.updateButton.setIconTint(ColorStateList.valueOf(resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.WHITE)));
            holder.updateButton.setStrokeColor(ColorStateList.valueOf(resolveThemeColor(com.google.android.material.R.attr.colorOutline, 0xFF8D7970)));
            UpdateState state = getUpdateStateForItem(item);
            String message = getUpdateMessageForItem(item);
            switch (state) {
                case CHECKING:
                    holder.updateButton.setEnabled(false);
                    holder.updateButton.setIconResource(R.drawable.ic_sync_24);
                    holder.updateButton.setContentDescription(getString(R.string.instance_content_checking_updates_short));
                    holder.updateButton.setOnClickListener(null);
                    return;
                case UPDATE_AVAILABLE:
                    holder.updateButton.setIconResource(R.drawable.ic_update_24);
                    holder.updateButton.setBackgroundTintList(ColorStateList.valueOf(resolveThemeColor(com.google.android.material.R.attr.colorError, 0xFFFF6B6B)));
                    holder.updateButton.setIconTint(ColorStateList.valueOf(resolveThemeColor(com.google.android.material.R.attr.colorOnError, Color.WHITE)));
                    holder.updateButton.setStrokeColor(ColorStateList.valueOf(resolveThemeColor(com.google.android.material.R.attr.colorError, 0xFFFF6B6B)));
                    holder.updateButton.setContentDescription(message == null ? getString(R.string.instance_content_update_available) : message);
                    holder.updateButton.setOnClickListener(view -> updateSingleContentItemFromRow(item));
                    return;
                case UPDATING:
                    holder.updateButton.setEnabled(false);
                    holder.updateButton.setIconResource(R.drawable.ic_update_24);
                    holder.updateButton.setContentDescription(message == null ? getString(R.string.instance_content_updating_title) : message);
                    holder.updateButton.setOnClickListener(null);
                    return;
                case UP_TO_DATE:
                    holder.updateButton.setIconResource(R.drawable.ic_check_24);
                    holder.updateButton.setContentDescription(getString(R.string.instance_content_up_to_date));
                    holder.updateButton.setOnClickListener(view -> checkSingleContentUpdateFromRow(item));
                    return;
                case ERROR:
                    holder.updateButton.setIconResource(R.drawable.ic_sync_24);
                    holder.updateButton.setContentDescription(message == null ? getString(R.string.instance_content_update_check_failed_short) : message);
                    holder.updateButton.setOnClickListener(view -> checkSingleContentUpdateFromRow(item));
                    return;
                case UNKNOWN:
                default:
                    holder.updateButton.setIconResource(R.drawable.ic_sync_24);
                    holder.updateButton.setContentDescription(getString(R.string.instance_content_check_update_button));
                    holder.updateButton.setOnClickListener(view -> checkSingleContentUpdateFromRow(item));
            }
        }

        @Override
        public long getItemId(int position) {
            if (position < 0 || position >= contentItems.size()) return RecyclerView.NO_ID;
            InstanceContentItem item = contentItems.get(position);
            return safeCanonicalPath(item.file).hashCode() * 31L + item.category.ordinal();
        }

        @Override
        public int getItemCount() {
            return contentItems.size();
        }

        @NonNull
        private String buildMetadataKey(@NonNull InstanceContentItem item) {
            return item.file.getAbsolutePath()
                    + ":" + item.file.length()
                    + ":" + item.file.lastModified()
                    + ":" + item.category.name();
        }

        private void loadMetadataAsync(
                @NonNull ContentViewHolder holder,
                @NonNull InstanceContentItem item,
                @NonNull String metadataKey
        ) {
            if (!loadingMetadata.add(metadataKey)) return;
            final int expectedPageGeneration = contentPageGeneration;

            iconExecutor.execute(() -> {
                if (expectedPageGeneration != contentPageGeneration) {
                    loadingMetadata.remove(metadataKey);
                    return;
                }

                LoadedContentMetadata metadata;
                try {
                    metadata = loadContentMetadataForItem(item);
                } catch (Throwable throwable) {
                    Logging.e(TAG, "Unable to resolve installed content metadata for " + item.file.getName(), throwable);
                    metadata = new LoadedContentMetadata(null, null);
                }

                LoadedContentMetadata finalMetadata = metadata;
                mainHandler.post(() -> {
                    loadingMetadata.remove(metadataKey);
                    if (expectedPageGeneration != contentPageGeneration) return;

                    if (finalMetadata.hasAny()) {
                        metadataCache.put(metadataKey, finalMetadata);
                    } else {
                        missingMetadataCache.add(metadataKey);
                    }

                    if (binding == null || isFinishing() || isDestroyed()) return;
                    applyLoadedMetadata(holder, item, metadataKey, finalMetadata);
                });
            });
        }

        private void applyLoadedMetadata(
                @NonNull ContentViewHolder holder,
                @NonNull InstanceContentItem item,
                @NonNull String metadataKey,
                @NonNull LoadedContentMetadata metadata
        ) {
            rememberContentSearchMetadata(item, metadata.displayName);

            Object tag = holder.itemView.getTag();
            if (!(tag instanceof String) || !metadataKey.equals(tag)) return;

            if (item.category == ResourceCategory.MODS
                    && metadata.displayName != null
                    && !metadata.displayName.trim().isEmpty()) {
                holder.title.setText(metadata.displayName.trim());
            }

            if (metadata.icon != null) {
                applyLoadedIcon(holder, metadata.icon);
            }
        }

        final class ContentViewHolder extends RecyclerView.ViewHolder {
            final CheckBox selectionCheckBox;
            final ImageView icon;
            final TextView title;
            final TextView subtitle;
            final ImageView sourceIcon;
            final MaterialButton playWorldButton;
            final MaterialButton updateButton;
            final SwitchMaterial enabledSwitch;
            final MaterialButton deleteButton;

            ContentViewHolder(@NonNull View itemView) {
                super(itemView);
                selectionCheckBox = itemView.findViewById(R.id.checkResourceSelected);
                icon = itemView.findViewById(R.id.imageResourceIcon);
                title = itemView.findViewById(R.id.textResourceName);
                subtitle = itemView.findViewById(R.id.textResourceSubtitle);
                sourceIcon = itemView.findViewById(R.id.imageResourceInstalledSource);
                playWorldButton = itemView.findViewById(R.id.buttonPlayWorld);
                updateButton = itemView.findViewById(R.id.buttonUpdateResource);
                enabledSwitch = itemView.findViewById(R.id.switchResourceEnabled);
                deleteButton = itemView.findViewById(R.id.buttonDeleteResource);
            }
        }
    }
    private void showPerInstanceSettingsDialog() {
        enableFullscreen();

        String settingsKey = getPerInstanceSettingsKey();
        new PerInstanceSettingsDialog(
                this,
                settingsKey,
                collectPerInstanceSettingsAliasKeys(settingsKey),
                buildShortcutDataForCurrentInstance(),
                this::enableFullscreen
        ).show();
    }

    @NonNull
    private InstanceShortcutHelper.ShortcutData buildShortcutDataForCurrentInstance() {
        String safeId = isBlank(instanceId) ? instanceName : instanceId;
        String safeName = isBlank(instanceName) ? safeId : instanceName;
        String safeLoader = isBlank(loader) ? "Vanilla" : loader;
        String safeBaseVersion = isBlank(baseVersionId) ? safeName : baseVersionId;
        String safeMinecraftVersion = isBlank(minecraftVersionId) ? safeBaseVersion : minecraftVersionId;
        return new InstanceShortcutHelper.ShortcutData(
                safeId,
                safeName,
                safeLoader,
                safeBaseVersion,
                safeMinecraftVersion,
                iconFile
        );
    }

    private void savePerInstanceSettingsAliases(
            @NonNull String settingsKey,
            @NonNull InstanceLaunchSettings.Settings settings
    ) {
        for (String key : collectPerInstanceSettingsAliasKeys(settingsKey)) {
            InstanceLaunchSettings.save(this, key, settings);
        }
    }

    private void clearPerInstanceSettingsAliases(@NonNull String settingsKey) {
        for (String key : collectPerInstanceSettingsAliasKeys(settingsKey)) {
            InstanceLaunchSettings.clear(this, key);
        }
    }

    @NonNull
    private ArrayList<String> collectPerInstanceSettingsAliasKeys(@NonNull String settingsKey) {
        ArrayList<String> keys = new ArrayList<>();
        addPerInstanceSettingsAlias(keys, settingsKey);
        addPerInstanceSettingsAlias(keys, InstanceLaunchSettings.resolveInstanceKey(instanceId, instanceName));
        addPerInstanceSettingsAlias(keys, instanceId);
        return keys;
    }

    private void addPerInstanceSettingsAlias(
            @NonNull ArrayList<String> keys,
            @Nullable String rawKey
    ) {
        if (isBlank(rawKey)) return;
        String key = InstanceLaunchSettings.resolveInstanceKey(rawKey, rawKey);
        if (!keys.contains(key)) keys.add(key);
    }

    @NonNull
    private String getPerInstanceSettingsKey() {
        return InstanceLaunchSettings.resolveInstanceKey(instanceId, instanceName);
    }

    @NonNull
    private TextView buildPerInstanceDialogLabel(@NonNull String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        view.setTextColor(resolveThemeColor(android.R.attr.textColorPrimary, Color.WHITE));
        return view;
    }

    @NonNull
    private LinearLayout.LayoutParams topMarginParams(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = topMargin;
        return params;
    }

    private int resolveRendererSelectionIndex(
            @NonNull ArrayList<RendererInterface> renderers,
            @Nullable String selectedRendererId
    ) {
        if (isBlank(selectedRendererId)) return 0;
        for (int i = 0; i < renderers.size(); i++) {
            if (selectedRendererId.equals(renderers.get(i).getUniqueIdentifier())) {
                return i + 1;
            }
        }
        return 0;
    }

    private void updatePerInstanceRamSliderState(@NonNull SeekBar ramSlider, boolean enabled) {
        ramSlider.setEnabled(enabled);
        ramSlider.setAlpha(enabled ? 1f : 0.45f);
    }

    private int calculatePerInstanceRamStepCount(int minMemoryMb, int maxMemoryMb, int stepMb) {
        if (maxMemoryMb <= minMemoryMb) return 0;
        return Math.max(1, (int) Math.ceil((maxMemoryMb - minMemoryMb) / (float) stepMb));
    }

    private int perInstanceRamMemoryFromProgress(int progress, int minMemoryMb, int stepMb, int maxMemoryMb) {
        long requestedMb = (long) minMemoryMb + ((long) Math.max(0, progress) * (long) stepMb);
        if (requestedMb > maxMemoryMb) requestedMb = maxMemoryMb;
        return MemoryAllocationUtils.clampToAllowedRam(this, (int) requestedMb);
    }

    private int perInstanceRamProgressFromMemory(int memoryMb, int minMemoryMb, int stepMb, int stepCount) {
        int safeMemoryMb = MemoryAllocationUtils.clampToAllowedRam(this, memoryMb);
        int progress = Math.round((safeMemoryMb - minMemoryMb) / (float) Math.max(1, stepMb));
        return Math.max(0, Math.min(progress, stepCount));
    }

    private void updatePerInstanceRamText(
            @NonNull TextView ramValue,
            boolean customRam,
            int memoryMb,
            int maxMemoryMb
    ) {
        if (!customRam) {
            int globalMb = MemoryAllocationUtils.resolveAllocatedMemoryMb(this);
            ramValue.setText("Using launcher default: " + globalMb + " MB (" + formatGb(globalMb) + " GB)");
            return;
        }

        ramValue.setText("Custom RAM: " + memoryMb + " MB (" + formatGb(memoryMb) + " GB)"
                + " · Max recommended: " + maxMemoryMb + " MB");
    }

    private void showPerInstanceRamInputDialog(
            @NonNull TextView ramValue,
            @NonNull SeekBar ramSlider,
            @NonNull SwitchMaterial customRamSwitch,
            @NonNull boolean[] useCustomRam,
            @NonNull int[] selectedRamMb,
            int minMemoryMb,
            int stepMb,
            int stepCount,
            int maxMemoryMb
    ) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setText(String.valueOf(useCustomRam[0]
                ? selectedRamMb[0]
                : MemoryAllocationUtils.resolveAllocatedMemoryMb(this)));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Custom RAM")
                .setMessage("Enter RAM in MB. Allowed range: " + minMemoryMb + " - " + maxMemoryMb + " MB.")
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();

        dialog.setOnShowListener(value -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            int parsed;
            try {
                parsed = Integer.parseInt(input.getText() == null ? "" : input.getText().toString().trim());
            } catch (Throwable ignored) {
                input.setError("Enter a number in MB.");
                return;
            }

            selectedRamMb[0] = MemoryAllocationUtils.clampToAllowedRam(this, parsed);
            useCustomRam[0] = true;
            customRamSwitch.setChecked(true);
            updatePerInstanceRamSliderState(ramSlider, true);
            ramSlider.setProgress(perInstanceRamProgressFromMemory(selectedRamMb[0], minMemoryMb, stepMb, stepCount));
            updatePerInstanceRamText(ramValue, true, selectedRamMb[0], maxMemoryMb);
            dialog.dismiss();
        }));

        showFullscreenSafeDialog(dialog);
    }

    @NonNull
    private String formatGb(int memoryMb) {
        return String.format(java.util.Locale.US, "%.1f", memoryMb / 1024f);
    }
}

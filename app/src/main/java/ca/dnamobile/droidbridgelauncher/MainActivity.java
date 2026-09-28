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
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import ca.dnamobile.droidbridgelauncher.fancymenu.MicrosoftAuthConfigPersonal;
import ca.dnamobile.droidbridgelauncher.fancymenu.MicrosoftAuthManagerPersonal;
import ca.dnamobile.droidbridgelauncher.modmanager.ModManagerTools;
import ca.dnamobile.droidbridgelauncher.controls.ControlsMain;
import ca.dnamobile.droidbridgelauncher.data.AccountStore;
import ca.dnamobile.droidbridgelauncher.data.model.MinecraftVersion;
import ca.dnamobile.droidbridgelauncher.databinding.ActivityMainBinding;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstance;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstanceManager;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstanceDeleteManager;
import ca.dnamobile.droidbridgelauncher.installation.InstallationForegroundService;
import ca.dnamobile.droidbridgelauncher.installation.InstallSessionState;
import ca.dnamobile.droidbridgelauncher.legal.LegalConsentStore;
import ca.dnamobile.droidbridgelauncher.legal.LegalLinks;
import ca.dnamobile.droidbridgelauncher.launcher.InstanceLaunchSettings;
import ca.dnamobile.droidbridgelauncher.launcher.QuickPlayHelper;
import ca.dnamobile.droidbridgelauncher.logs.LauncherLogManager;
import ca.dnamobile.droidbridgelauncher.modcompat.SimpleVoiceChatCompat;
import ca.dnamobile.droidbridgelauncher.modmanager.ModpackInstallManager;
import ca.dnamobile.droidbridgelauncher.notifications.LauncherNotificationPermissionHelper;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.shortcuts.InstanceShortcutHelper;
import ca.dnamobile.droidbridgelauncher.storage.StorageLocation;
import ca.dnamobile.droidbridgelauncher.storage.StorageLocationDialog;
import ca.dnamobile.droidbridgelauncher.storage.StorageLocationStore;
import ca.dnamobile.droidbridgelauncher.ui.LauncherDialogStyle;
import ca.dnamobile.droidbridgelauncher.ui.instance.CreateInstanceDialog;
import ca.dnamobile.droidbridgelauncher.ui.instance.LauncherInstanceAdapter;
import ca.dnamobile.droidbridgelauncher.ui.version.CleanroomInstaller;
import ca.dnamobile.droidbridgelauncher.ui.version.CleanroomMigrationDialog;
import ca.dnamobile.droidbridgelauncher.ui.version.FabricInstaller;
import ca.dnamobile.droidbridgelauncher.ui.version.ForgeInstaller;
import ca.dnamobile.droidbridgelauncher.ui.version.MinecraftVersionInstaller;
import ca.dnamobile.droidbridgelauncher.ui.version.MinecraftVersionManifestClient;
import ca.dnamobile.droidbridgelauncher.ui.version.NeoForgeInstaller;
import ca.dnamobile.droidbridgelauncher.update.LauncherUpdateDialogs;
import ca.dnamobile.droidbridgelauncher.utils.AppOrientationHelper;
import ca.dnamobile.droidbridgelauncher.utils.FullscreenUtils;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

public class MainActivity extends AppCompatActivity {
    private static final String TYPE_INSTALLED = "installed";
    private static final String FILTER_ALL = "all";
    private static final String FILTER_RECENT = "recent";
    private static final String FILTER_FAVORITES = "favorites";
    private static final String FILTER_VANILLA = "vanilla";
    private static final String FILTER_MODIFIED = "modified";
    private static final String FILTER_SNAPSHOT = "snapshot";
    private static final String FILTER_SHARED = "shared";
    private static final int REQUEST_PICK_INSTANCE_ICON = 8021;
    private static final int REQUEST_ADD_STORAGE_LOCATION = 8032;
    private static final int REQUEST_IMPORT_MODPACK = 8033;

    private static final long INSTALL_UI_UPDATE_INTERVAL_MS = 350L;
    private static final long INSTALL_UI_MESSAGE_UPDATE_INTERVAL_MS = 850L;
    private static final long INSTALL_NOTIFICATION_UPDATE_INTERVAL_MS = 1000L;
    private static final int INSTALL_NOTIFICATION_PROGRESS_STEP = 2;

    private static final int COLOR_DIALOG_BG = Color.rgb(30, 34, 42);
    private static final int COLOR_CARD_BG = Color.rgb(38, 43, 53);
    private static final int COLOR_CARD_STROKE = Color.rgb(54, 61, 74);
    private static final int COLOR_TEXT_PRIMARY = Color.rgb(238, 241, 248);
    private static final int COLOR_TEXT_SECONDARY = Color.rgb(198, 204, 216);
    private static final int COLOR_TEXT_MUTED = Color.rgb(150, 159, 176);
    private static final int COLOR_ACCENT = Color.rgb(37, 211, 128);

    private ActivityMainBinding binding;
    private AccountStore accountStore;
    private MicrosoftAuthManagerPersonal authManager;
    private LauncherInstanceAdapter instanceAdapter;
    @Nullable
    private LauncherInstance selectedInstance;
    @Nullable
    private String pendingShortcutInstanceId;
    private String selectedFilter = FILTER_ALL;
    @NonNull
    private String appliedLauncherTheme = LauncherPreferences.LAUNCHER_THEME_ORANGE;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // Orientation is owned by Android's configuration system. MainActivity intentionally
    // does not consume orientation/screenSize changes in the manifest, so rotating the
    // device recreates the Activity and Android selects res/layout-port/activity_main.xml
    // or res/layout-land/activity_main.xml before ActivityMainBinding is inflated.
    private int mainOrientationTarget;

    private AlertDialog installDialog;
    private ProgressBar installDialogProgress;
    private TextView installDialogMessage;
    @Nullable
    private AlertDialog launchPrepareDialog;
    @Nullable
    private TextView launchPrepareMessage;
    @Nullable
    private TextView launchPreparePercent;
    @Nullable
    private ProgressBar launchPrepareProgress;
    private CreateInstanceDialog createInstanceDialog;
    private CheckBox installDialogForegroundCheck;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private boolean installSessionActive;
    private long installSessionGeneration = -1L;
    private boolean uiLifecycleDestroyed;
    private boolean installPermissionPromptShownThisSession;
    private String activeInstallTitle = "Installing Minecraft";
    private String activeInstallMessage = "Preparing installation...";
    private int activeInstallProgress;
    private long lastInstallUiDispatchMs;
    private int lastInstallUiDispatchProgress = -1;
    @NonNull
    private String lastInstallUiDispatchMessage = "";
    private long lastInstallNotificationUpdateMs;
    private int lastInstallNotificationProgress = -1;
    private boolean appIntegrityBlocked;
    private boolean instanceTabListenerAdded;

    private final Runnable hideStatusRunnable = () -> {
        if (binding != null && binding.textStatus != null) {
            binding.textStatus.setVisibility(View.GONE);
        }
    };

    @Nullable
    private Runnable pendingAfterMicrosoftSignIn;

    private final ArrayList<MinecraftVersion> allVersions = new ArrayList<>();
    private final ArrayList<LauncherInstance> installedInstances = new ArrayList<>();
    private final Set<String> installedBaseVersionIds = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        appliedLauncherTheme = LauncherPreferences.getLauncherTheme(this);
        LauncherTheme.apply(this);
        mainOrientationTarget = AppOrientationHelper.resolveLauncherRequestedOrientation(this);
        setRequestedOrientation(mainOrientationTarget);
        super.onCreate(savedInstanceState);
        uiLifecycleDestroyed = false;
        Logging.init(this);

        appIntegrityBlocked = ControlsMain.blockIfInvalidSignature(this);
        if (appIntegrityBlocked) {
            return;
        }

        PathManager.initContextConstants(this);
        LauncherInstanceDeleteManager.cleanupPendingDeletesAsync(this);
        selectedFilter = sanitizeSavedFilter(LauncherPreferences.getSelectedInstanceFilter(this, FILTER_ALL));

        // Do not choose a layout manually from WindowMetrics here. During an orientation
        // transition WindowMetrics can still describe the previous window before the new
        // decor view is attached. Let Android's resource qualifiers select the correct XML.
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        LauncherTheme.applyRainbowBackgroundIfNeeded(this);
        FullscreenUtils.enableImmersive(this);

        registerNotificationPermissionLauncher();
        setupAccountUi();
        setupInstanceUi();
        refreshInstancesAndRebind(false);
        loadVersions(false);
        captureShortcutLaunchIntent(getIntent());
        maybeShowRequiredLegalAcceptanceDialog();
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        if (appIntegrityBlocked || binding == null) {
            return;
        }

        PathManager.initContextConstants(this);
        captureShortcutLaunchIntent(intent);
        refreshInstancesAndRebind(true);
        maybeShowRequiredLegalAcceptanceDialog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applySelectedMainOrientation();

        if (appIntegrityBlocked || binding == null) {
            return;
        }

        String currentTheme = LauncherPreferences.getLauncherTheme(this);
        if (!currentTheme.equals(appliedLauncherTheme)) {
            recreate();
            return;
        }

        PathManager.initContextConstants(this);
        FullscreenUtils.enableImmersive(this);
        refreshAccountUiFromStore();
        refreshInstanceTabsForSettings();
        reattachInstallSessionIfNeeded();
        if (!installSessionActive) {
            refreshInstancesAndRebind(true);
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Orientation/screenSize are deliberately NOT listed in MainActivity's
        // android:configChanges, so normal rotations recreate this Activity instead of
        // reaching this method. Keep this only for configuration changes we do consume
        // (for example uiMode/keyboard) and refresh the grid from the current resources.
        if (binding != null && binding.recyclerVersions.getLayoutManager() instanceof GridLayoutManager) {
            ((GridLayoutManager) binding.recyclerVersions.getLayoutManager())
                    .setSpanCount(getInstanceGridSpanCount());
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (appIntegrityBlocked || binding == null) return;
        if (hasFocus) FullscreenUtils.enableImmersive(this);
    }

    private void applySelectedMainOrientation() {
        int target = AppOrientationHelper.resolveLauncherRequestedOrientation(this);
        mainOrientationTarget = target;
        if (getRequestedOrientation() != target) {
            setRequestedOrientation(target);
        }
    }

    @Override
    protected void onDestroy() {
        uiLifecycleDestroyed = true;
        mainHandler.removeCallbacks(installSessionLifecycleRunnable);
        dismissInstallDialog();
        if (installSessionActive) {
            clearInstallKeepScreenOnFlagSafely();
        }
        dismissLaunchPrepareDialog();
        if (authManager != null && !isChangingConfigurations()) {
            authManager.dispose();
        }
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void maybeShowRequiredLegalAcceptanceDialog() {
        if (LegalConsentStore.hasAcceptedCurrentTerms(this)) {
            if (tryConsumePendingShortcutLaunch()) {
                return;
            }
            maybeShowNotificationPermissionLaunchPrompt();
            LauncherUpdateDialogs.checkOnStartup(this);
            return;
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.eula_acceptance_title)
                .setMessage(R.string.eula_acceptance_message)
                .setCancelable(false)
                .setNeutralButton(R.string.button_open_eula, null)
                .setPositiveButton(R.string.button_accept_eula, null)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            // Opening the EULA is informational; keep this required acceptance
            // dialog visible when the user returns to DroidBridge.
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view ->
                    LegalLinks.open(this, LegalLinks.MINECRAFT_EULA_URL));

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                LegalConsentStore.markCurrentTermsAccepted(this);
                dialog.dismiss();
                if (tryConsumePendingShortcutLaunch()) {
                    return;
                }
                maybeShowNotificationPermissionLaunchPrompt();
                LauncherUpdateDialogs.checkOnStartup(this);
            });
        });

        dialog.show();
    }

    private void captureShortcutLaunchIntent(@Nullable Intent intent) {
        String instanceId = InstanceShortcutHelper.readInstanceId(intent);
        if (!isBlank(instanceId)) {
            pendingShortcutInstanceId = instanceId.trim();
        }
    }

    private boolean tryConsumePendingShortcutLaunch() {
        if (isBlank(pendingShortcutInstanceId)) {
            return false;
        }
        if (!LegalConsentStore.hasAcceptedCurrentTerms(this)) {
            return false;
        }

        String shortcutInstanceId = pendingShortcutInstanceId.trim();
        pendingShortcutInstanceId = null;

        LauncherInstance instance = findShortcutInstance(shortcutInstanceId);
        if (instance == null) {
            String message = "This instance shortcut points to an instance that no longer exists.";
            setStatus(message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return true;
        }

        selectedFilter = FILTER_ALL;
        selectTabByFilter(FILTER_ALL);
        selectedInstance = instance;
        if (instanceAdapter != null) {
            instanceAdapter.setSelectedInstance(instance);
            applyInstanceFilter();
        }
        updateSelectedInstanceCard();
        quickLaunchInstance(instance, false);
        return true;
    }

    @Nullable
    private LauncherInstance findShortcutInstance(@NonNull String instanceId) {
        for (LauncherInstance instance : installedInstances) {
            if (instance.getId().equals(instanceId) || instance.getName().equals(instanceId)) {
                return instance;
            }
        }

        LauncherInstance instance = LauncherInstanceManager.findByNameOrId(this, instanceId);
        if (instance != null) {
            return instance;
        }

        refreshInstancesAndRebind(true);
        for (LauncherInstance refreshed : installedInstances) {
            if (refreshed.getId().equals(instanceId) || refreshed.getName().equals(instanceId)) {
                return refreshed;
            }
        }
        return null;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    private void setupAccountUi() {
        try {
            accountStore = new AccountStore(this);
            authManager = new MicrosoftAuthManagerPersonal(this, accountStore);
            authManager.setListener(new MicrosoftAuthManagerPersonal.Listener() {
                @Override
                public void onSignedIn(@NonNull AccountStore.Account account) {
                    updateAccountStatus(account);
                    setStatus(getString(R.string.msg_sign_in_success));

                    Runnable pending = pendingAfterMicrosoftSignIn;
                    pendingAfterMicrosoftSignIn = null;
                    if (pending != null) {
                        pending.run();
                    }
                }

                @Override
                public void onError(@NonNull String message) {
                    pendingAfterMicrosoftSignIn = null;
                    setStatus(message);
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                }
            });

            updateAccountStatus(accountStore.load());
        } catch (Throwable throwable) {
            Logging.e("MainActivity", "Microsoft account UI initialization failed", throwable);
            binding.textAccountStatus.setText(R.string.status_signed_out);
            binding.buttonSignIn.setEnabled(false);
            binding.buttonSignOut.setEnabled(false);
        }

        binding.buttonSignIn.setOnClickListener(view -> signInWithMicrosoftThen(null));
        binding.buttonSignOut.setOnClickListener(view -> showSignOutConfirmationDialog());
    }

    private void refreshAccountUiFromStore() {
        if (accountStore == null || binding == null) return;

        try {
            updateAccountStatus(accountStore.load());
        } catch (Throwable throwable) {
            Logging.e("MainActivity", "Unable to refresh account UI", throwable);
        }
    }

    private void showSignOutConfirmationDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sign_out_confirm_title)
                .setMessage(R.string.sign_out_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.button_sign_out, (dialog, which) -> performMicrosoftSignOut())
                .show();
    }

    private void performMicrosoftSignOut() {
        if (authManager == null || accountStore == null) return;

        pendingAfterMicrosoftSignIn = null;
        authManager.signOut();
        updateAccountStatus(accountStore.load());
        setStatus(getString(R.string.msg_sign_out_success));
        Toast.makeText(this, R.string.msg_sign_out_success, Toast.LENGTH_SHORT).show();
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

    private boolean hasActiveMicrosoftAccount() {
        return ModManagerTools.hasActiveMicrosoftAccount(accountStore);
    }

    private boolean hasCompletedMicrosoftLoginOnce() {
        return ModManagerTools.hasCompletedMicrosoftLoginOnce(accountStore);
    }

    private boolean requireActiveMicrosoftAccountBeforeCreateInstance(@NonNull Runnable afterSignIn) {
        return ModManagerTools.requireActiveMicrosoftAccountBeforeInstall(
                this,
                accountStore,
                () -> signInWithMicrosoftThen(afterSignIn)
        );
    }

    private boolean requireMicrosoftLoginHistoryBeforeLaunch(@NonNull Runnable afterSignIn) {
        return ModManagerTools.requireMicrosoftLoginHistoryBeforeLaunch(
                this,
                accountStore,
                () -> signInWithMicrosoftThen(afterSignIn)
        );
    }

    private void setupInstanceUi() {
        binding.textFolder.setText(getString(R.string.launcher_folder_value, PathManager.DIR_MINECRAFT_HOME));
        setupRenderSurfaceUi();
        setupSharedInstallsUi();

        instanceAdapter = new LauncherInstanceAdapter(this, new LauncherInstanceAdapter.Listener() {
            @Override
            public void onInstanceSelected(@NonNull LauncherInstance instance) {
                if (requireMicrosoftLoginHistoryBeforeLaunch(() -> selectAndOpenInstance(instance))) {
                    selectedInstance = null;
                    instanceAdapter.clearSelectedInstance();
                    updateSelectedInstanceCard();
                    return;
                }

                selectAndOpenInstance(instance);
            }

            @Override
            public void onInstanceQuickPlayRequested(@NonNull LauncherInstance instance) {
                quickLaunchInstance(instance);
            }

            @Override
            public void onInstanceDeleteRequested(@NonNull LauncherInstance instance) {
                showDeleteInstanceDialog(instance);
            }
        });

        instanceAdapter.setSelectionChangedListener(() -> {
            if (instanceAdapter != null && instanceAdapter.isSelectionMode()) {
                selectedInstance = null;
                instanceAdapter.clearSelectedInstance();
                updateSelectedInstanceCard();
            }
            updateMultiSelectUi();
        });
        instanceAdapter.setFavoriteChangedListener(() -> {
            if (FILTER_FAVORITES.equals(selectedFilter)) {
                applyInstanceFilter();
            }
        });

        binding.recyclerVersions.setLayoutManager(new GridLayoutManager(this, getInstanceGridSpanCount()));
        binding.recyclerVersions.setAdapter(instanceAdapter);

        addInstanceTabs();
        setupMultiSelectButton();

        binding.buttonRefreshVersions.setOnClickListener(view -> {
            refreshInstancesAndRebind(true);
            loadVersions(true);
        });

        setupStorageLocationsButton();
        setupMainContentButtons();

        binding.fabCreateInstance.setOnClickListener(view -> {
            if (requireActiveMicrosoftAccountBeforeCreateInstance(this::showCreateInstanceDialog)) {
                return;
            }
            showCreateInstanceDialog();
        });
        binding.buttonLaunchVersion.setOnClickListener(view -> launchSelectedInstance());
        binding.buttonOpenFolder.setOnClickListener(view -> showSelectedInstanceFolder());
        if (binding.buttonOpenSettings != null) {
            binding.buttonOpenSettings.setOnClickListener(view -> startActivity(new Intent(this, LauncherSettingsActivity.class)));
        }

        binding.checkKeepLogs.setChecked(LauncherLogManager.isKeepLogHistoryEnabled(this));
        binding.checkKeepLogs.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherLogManager.setKeepLogHistoryEnabled(this, isChecked);
            setStatus(getString(isChecked ? R.string.log_history_enabled : R.string.log_history_disabled));
        });
        binding.buttonShareLatestLog.setOnClickListener(view -> LauncherLogManager.shareLogs(this));

        binding.buttonLaunchVersion.setEnabled(false);
        binding.buttonOpenFolder.setEnabled(false);
        updateSelectedInstanceCard();
    }

    private void setupMultiSelectButton() {
        binding.buttonMultiSelectInstances.setOnClickListener(view -> handleMultiSelectButtonPressed());
        updateMultiSelectUi();
    }

    private void handleMultiSelectButtonPressed() {
        if (instanceAdapter == null) return;

        if (!instanceAdapter.isSelectionMode()) {
            selectedInstance = null;
            instanceAdapter.clearSelectedInstance();
            instanceAdapter.setSelectionMode(true);
            updateSelectedInstanceCard();
            setStatus(getString(R.string.instance_multiselect_hint));
            return;
        }

        if (instanceAdapter.getSelectedCount() <= 0) {
            instanceAdapter.setSelectionMode(false);
            updateMultiSelectUi();
            return;
        }

        showDeleteSelectedInstancesDialog();
    }

    private void updateMultiSelectUi() {
        if (binding == null || binding.buttonMultiSelectInstances == null || binding.textMainInstancesTitle == null) {
            return;
        }

        boolean selecting = instanceAdapter != null && instanceAdapter.isSelectionMode();
        int count = selecting && instanceAdapter != null ? instanceAdapter.getSelectedCount() : 0;

        if (selecting && count > 0) {
            binding.textMainInstancesTitle.setText(getString(R.string.instance_multiselect_title, count));
            binding.buttonMultiSelectInstances.setVisibility(View.VISIBLE);
            binding.buttonMultiSelectInstances.setEnabled(true);
            binding.buttonMultiSelectInstances.setAlpha(1.0f);
            binding.buttonMultiSelectInstances.setIconResource(R.drawable.ic_delete_24);
            binding.buttonMultiSelectInstances.setContentDescription(getString(R.string.button_delete_selected_instances));
            ensureDeleteButtonBeforeFolderButton();
        } else {
            binding.textMainInstancesTitle.setText("Instances");
            binding.buttonMultiSelectInstances.setVisibility(View.GONE);
            binding.buttonMultiSelectInstances.setEnabled(false);
            binding.buttonMultiSelectInstances.setAlpha(1.0f);
            binding.buttonMultiSelectInstances.setIconResource(R.drawable.ic_delete_24);
            binding.buttonMultiSelectInstances.setContentDescription(getString(R.string.button_delete_selected_instances));
        }
    }

    private void ensureDeleteButtonBeforeFolderButton() {
        if (binding == null || binding.buttonMultiSelectInstances == null || binding.buttonStorageLocations == null) {
            return;
        }
        if (!(binding.buttonMultiSelectInstances.getParent() instanceof ViewGroup)) {
            return;
        }
        ViewGroup parent = (ViewGroup) binding.buttonMultiSelectInstances.getParent();
        if (parent != binding.buttonStorageLocations.getParent()) {
            return;
        }

        int deleteIndex = parent.indexOfChild(binding.buttonMultiSelectInstances);
        int folderIndex = parent.indexOfChild(binding.buttonStorageLocations);
        if (deleteIndex < 0 || folderIndex < 0 || deleteIndex == folderIndex - 1) {
            return;
        }

        parent.removeView(binding.buttonMultiSelectInstances);
        if (deleteIndex < folderIndex) {
            folderIndex--;
        }
        parent.addView(binding.buttonMultiSelectInstances, Math.max(0, folderIndex));
    }

    private void showDeleteSelectedInstancesDialog() {
        if (instanceAdapter == null) return;

        ArrayList<LauncherInstance> selected = instanceAdapter.getSelectedInstances();
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.delete_multiple_instances_none, Toast.LENGTH_SHORT).show();
            return;
        }

        ArrayList<LauncherInstance> deletable = new ArrayList<>();
        ArrayList<String> blockedNames = new ArrayList<>();
        for (LauncherInstance instance : selected) {
            if (!instance.isIsolated()) {
                ArrayList<String> dependents = LauncherInstanceManager.findSharedVersionDependents(this, instance.getBaseVersionId());
                if (!dependents.isEmpty()) {
                    blockedNames.add(instance.getName() + " — needed by "
                            + LauncherInstanceManager.formatDependentVersionList(dependents));
                    continue;
                }
            }
            deletable.add(instance);
        }

        if (deletable.isEmpty()) {
            LauncherDialogStyle.showStyledMessageDialog(
                    this,
                    getString(R.string.delete_multiple_instances_title, selected.size()),
                    buildBulletList(blockedNames),
                    getString(android.R.string.ok),
                    (dialog, which) -> { },
                    getString(android.R.string.cancel)
            );
            return;
        }

        String message = blockedNames.isEmpty()
                ? getString(R.string.delete_multiple_instances_message, buildInstanceBulletList(deletable))
                : getString(
                        R.string.delete_multiple_instances_message_with_blocked,
                        buildInstanceBulletList(deletable),
                        buildBulletList(blockedNames)
                );

        LauncherDialogStyle.showStyledMessageDialog(
                this,
                getString(R.string.delete_multiple_instances_title, deletable.size()),
                message,
                getString(R.string.button_delete_forever),
                (dialog, which) -> deleteSelectedInstances(deletable),
                getString(android.R.string.cancel)
        );
    }

    @NonNull
    private String buildInstanceBulletList(@NonNull List<LauncherInstance> instances) {
        ArrayList<String> names = new ArrayList<>();
        for (LauncherInstance instance : instances) names.add(instance.getName());
        return buildBulletList(names);
    }

    @NonNull
    private String buildBulletList(@NonNull List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append('\n');
            builder.append("• ").append(value);
        }
        return builder.toString();
    }

    private void deleteSelectedInstances(@NonNull List<LauncherInstance> instancesToDelete) {
        if (instancesToDelete.isEmpty()) return;

        setLoading(true);
        setStatus(getString(R.string.delete_instance_deleting, instancesToDelete.size() + " instances"));

        Thread thread = new Thread(() -> {
            int deleted = 0;
            int failed = 0;

            for (LauncherInstance instance : instancesToDelete) {
                try {
                    LauncherInstanceDeleteManager.DeleteJob deleteJob = LauncherInstanceDeleteManager.hideForDeletion(MainActivity.this, instance);
                    LauncherPreferences.clearInstancePlayed(MainActivity.this, instance.getId());
                    LauncherPreferences.clearInstanceFavorite(MainActivity.this, instance.getId());

                    if (deleteJob.wasMovedOutOfVisibleList() || !deleteJob.getOriginalTarget().exists()) {
                        deleted++;
                        finishDeleteInBackground(instance, deleteJob);
                        continue;
                    }

                    LauncherInstanceDeleteManager.finishDeletion(MainActivity.this, deleteJob);
                    deleted++;
                } catch (Throwable throwable) {
                    failed++;
                    Logging.e("DeleteInstance", "Unable to delete selected instance " + instance.getName(), throwable);
                }
            }

            int finalDeleted = deleted;
            int finalFailed = failed;
            runOnUiThread(() -> finishMultiDeleteUi(finalDeleted, finalFailed));
        }, "Delete Selected Launcher Instances");
        thread.start();
    }

    private void finishMultiDeleteUi(int deleted, int failed) {
        if (instanceAdapter != null) instanceAdapter.setSelectionMode(false);
        selectedInstance = null;
        refreshInstancesAndRebind(false);
        setLoading(false);

        String message = failed == 0
                ? getString(R.string.delete_multiple_instances_finished, deleted)
                : getString(R.string.delete_multiple_instances_failed, deleted, failed);
        setStatus(message);
        Toast.makeText(this, message, failed == 0 ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
        updateMultiSelectUi();
    }

    private void setupRenderSurfaceUi() {
        boolean useNativeSurfaceView = LauncherPreferences.isUseNativeSurfaceView(this);
        binding.switchUseNativeSurface.setChecked(useNativeSurfaceView);
        updateRenderSurfaceSwitchText(useNativeSurfaceView);

        binding.switchUseNativeSurface.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherPreferences.setUseNativeSurfaceView(this, isChecked);
            updateRenderSurfaceSwitchText(isChecked);
            setStatus(getString(
                    R.string.render_surface_mode_status,
                    getString(isChecked ? R.string.render_surface_surface_view : R.string.render_surface_texture_view)
            ));
        });
    }

    private void updateRenderSurfaceSwitchText(boolean useNativeSurfaceView) {
        binding.switchUseNativeSurface.setText(
                useNativeSurfaceView
                        ? R.string.render_surface_surface_view
                        : R.string.render_surface_texture_view
        );
    }

    private void setupSharedInstallsUi() {
        boolean showSharedInstalls = LauncherPreferences.isShowSharedInstalls(this);
        binding.switchShowSharedInstalls.setChecked(showSharedInstalls);
        updateSharedInstallsSwitchText(showSharedInstalls);

        binding.switchShowSharedInstalls.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherPreferences.setShowSharedInstalls(this, isChecked);
            updateSharedInstallsSwitchText(isChecked);
            selectedFilter = sanitizeSavedFilter(selectedFilter);
            addInstanceTabs();
            refreshInstancesAndRebind(true);
            setStatus(getString(isChecked ? R.string.shared_installs_shown : R.string.shared_installs_hidden));
        });
    }

    private void updateSharedInstallsSwitchText(boolean showSharedInstalls) {
        binding.switchShowSharedInstalls.setText(
                showSharedInstalls
                        ? R.string.shared_installs_on
                        : R.string.shared_installs_show
        );
    }

    private void addInstanceTabs() {
        if (binding == null || binding.tabVersionTypes == null) return;

        selectedFilter = sanitizeSavedFilter(selectedFilter);
        binding.tabVersionTypes.removeAllTabs();

        addInstanceTab(getString(R.string.instance_tab_all), FILTER_ALL, FILTER_ALL.equals(selectedFilter));
        addInstanceIconTab(R.drawable.ic_favorite_24, getString(R.string.instance_tab_favorites), FILTER_FAVORITES, FILTER_FAVORITES.equals(selectedFilter));
        addInstanceTab(getString(R.string.instance_tab_recent), FILTER_RECENT, FILTER_RECENT.equals(selectedFilter));
        addInstanceTab(getString(R.string.instance_tab_vanilla), FILTER_VANILLA, FILTER_VANILLA.equals(selectedFilter));
        addInstanceTab(getString(R.string.instance_tab_modified), FILTER_MODIFIED, FILTER_MODIFIED.equals(selectedFilter));
        addInstanceTab(getString(R.string.instance_tab_snapshot), FILTER_SNAPSHOT, FILTER_SNAPSHOT.equals(selectedFilter));
        if (LauncherPreferences.isShowSharedInstalls(this)) {
            addInstanceTab(getString(R.string.instance_tab_shared), FILTER_SHARED, FILTER_SHARED.equals(selectedFilter));
        }

        if (instanceTabListenerAdded) {
            selectTabByFilter(selectedFilter);
            return;
        }
        instanceTabListenerAdded = true;
        binding.tabVersionTypes.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                Object tag = tab.getTag();
                selectedFilter = sanitizeSavedFilter(tag instanceof String ? (String) tag : FILTER_ALL);
                LauncherPreferences.setSelectedInstanceFilter(MainActivity.this, selectedFilter);
                if (instanceAdapter != null && instanceAdapter.isSelectionMode()) {
                    instanceAdapter.setSelectionMode(false);
                }
                selectedInstance = null;
                if (instanceAdapter != null) {
                    instanceAdapter.clearSelectedInstance();
                }
                applyInstanceFilter();
                updateSelectedInstanceCard();
                updateMultiSelectUi();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
    }

    private void refreshInstanceTabsForSettings() {
        if (binding == null || binding.tabVersionTypes == null) return;
        String before = selectedFilter;
        selectedFilter = sanitizeSavedFilter(selectedFilter);
        boolean sharedTabVisible = false;
        for (int i = 0; i < binding.tabVersionTypes.getTabCount(); i++) {
            TabLayout.Tab tab = binding.tabVersionTypes.getTabAt(i);
            if (tab != null && FILTER_SHARED.equals(tab.getTag())) {
                sharedTabVisible = true;
                break;
            }
        }
        if (LauncherPreferences.isShowSharedInstalls(this) != sharedTabVisible || !before.equals(selectedFilter)) {
            addInstanceTabs();
            selectTabByFilter(selectedFilter);
        }
    }

    private void addInstanceTab(@NonNull String title, @NonNull String filter, boolean selected) {
        TabLayout.Tab tab = binding.tabVersionTypes.newTab();
        tab.setText(title);
        tab.setTag(filter);
        binding.tabVersionTypes.addTab(tab, selected);
    }

    private void addInstanceIconTab(int iconResId, @NonNull String contentDescription, @NonNull String filter, boolean selected) {
        TabLayout.Tab tab = binding.tabVersionTypes.newTab();
        tab.setIcon(iconResId);
        tab.setContentDescription(contentDescription);
        tab.setTag(filter);
        binding.tabVersionTypes.addTab(tab, selected);
    }

    private int getInstanceGridSpanCount() {
        // Keep the list in lockstep with the same Configuration Android used to choose
        // layout-port/layout-land. This avoids raw-pixel/density heuristics producing a
        // landscape grid while the portrait XML is active (or vice versa).
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                ? 2
                : 1;
    }

    private void loadVersions(boolean showLoading) {
        if (showLoading) {
            setLoading(true);
            setStatus(getString(R.string.msg_fetching_versions));
        }

        Thread thread = new Thread(() -> {
            try {
                List<MinecraftVersion> versions = MinecraftVersionManifestClient.loadVersions(MainActivity.this);
                runOnUiThread(() -> {
                    allVersions.clear();
                    allVersions.addAll(versions);
                    if (showLoading) {
                        setLoading(false);
                        setStatus(getString(R.string.msg_versions_loaded, versions.size()));
                    }
                });
            } catch (Throwable throwable) {
                Logging.e("VersionManifest", "Unable to load Minecraft version manifest", throwable);
                runOnUiThread(() -> {
                    if (showLoading) setLoading(false);
                    setStatus(getString(R.string.msg_versions_failed, throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName()));
                });
            }
        }, "Minecraft Version Manifest");
        thread.start();
    }

    private void refreshInstancesAndRebind(boolean keepSelection) {
        String selectedKey = keepSelection && selectedInstance != null
                ? LauncherInstanceAdapter.getSelectionKey(selectedInstance)
                : null;

        refreshInstalledBaseVersions();
        installedInstances.clear();

        ArrayList<LauncherInstance> isolatedInstances = LauncherInstanceManager.findInstances(this);
        installedInstances.addAll(isolatedInstances);
        addSharedInstalledVersions(isolatedInstances);

        if (selectedKey != null) {
            selectedInstance = findInstanceBySelectionKey(selectedKey);
        } else {
            selectedInstance = null;
        }

        if (instanceAdapter != null) {
            instanceAdapter.setSelectedInstance(selectedInstance);
            applyInstanceFilter();
        }

        updateSelectedInstanceCard();
    }

    private void refreshInstalledBaseVersions() {
        installedBaseVersionIds.clear();
        for (MinecraftVersion version : MinecraftVersionInstaller.findInstalledVersions()) {
            installedBaseVersionIds.add(version.getId());
        }
    }

    private void addSharedInstalledVersions(@NonNull List<LauncherInstance> isolatedInstances) {
        if (!LauncherPreferences.isShowSharedInstalls(this)) {
            return;
        }

        HashSet<String> versionsOwnedByIsolatedInstances = collectVersionsRequiredByIsolatedInstances(isolatedInstances);
        HashSet<String> addedSharedIds = new HashSet<>();

        for (java.io.File minecraftHome : StorageLocationStore.getVisibleMinecraftHomes(this)) {
            for (MinecraftVersion version : MinecraftVersionInstaller.findInstalledVersions(minecraftHome)) {
                if (versionsOwnedByIsolatedInstances.contains(version.getId())) {
                    continue;
                }

                String sharedId = LauncherInstance.sharedInstanceId(version.getId(), minecraftHome);
                if (!addedSharedIds.add(sharedId)) continue;

                installedInstances.add(LauncherInstance.sharedInstalledVersion(
                        version.getId(),
                        normalizeInstalledVersionType(version.getType()),
                        minecraftHome,
                        version.getReleaseTime(),
                        inferLoaderNameFromVersionId(version.getId())
                ));
            }
        }
    }

    @Nullable
    private LauncherInstance findInstanceBySelectionKey(@NonNull String selectionKey) {
        for (LauncherInstance instance : installedInstances) {
            if (selectionKey.equals(LauncherInstanceAdapter.getSelectionKey(instance))) {
                return instance;
            }
        }
        return null;
    }

    @NonNull
    private HashSet<String> collectVersionsRequiredByIsolatedInstances(@NonNull List<LauncherInstance> isolatedInstances) {
        HashSet<String> required = new HashSet<>();

        for (LauncherInstance instance : isolatedInstances) {
            String versionId = instance.getBaseVersionId();
            if (versionId != null && !versionId.trim().isEmpty()) {
                required.add(versionId);
                collectInheritedVersionIds(versionId, required, new HashSet<>());
            }

            String minecraftVersionId = instance.getMinecraftVersionId();
            if (minecraftVersionId != null && !minecraftVersionId.trim().isEmpty()) {
                required.add(minecraftVersionId);
                collectInheritedVersionIds(minecraftVersionId, required, new HashSet<>());
            }
        }

        return required;
    }

    private void collectInheritedVersionIds(
            @NonNull String versionId,
            @NonNull HashSet<String> required,
            @NonNull HashSet<String> visited
    ) {
        if (!visited.add(versionId)) return;

        java.io.File jsonFile = new java.io.File(
                MinecraftVersionInstaller.getVersionDirectory(versionId),
                versionId + ".json"
        );
        if (!jsonFile.isFile()) return;

        try {
            org.json.JSONObject json = new org.json.JSONObject(readFile(jsonFile));
            String inheritsFrom = json.optString("inheritsFrom", "");
            if (inheritsFrom == null || inheritsFrom.trim().isEmpty()) return;

            required.add(inheritsFrom);
            collectInheritedVersionIds(inheritsFrom, required, visited);
        } catch (Throwable throwable) {
            Logging.i("MainActivity", "Unable to inspect inherited version for "
                    + versionId + ": " + throwable.getMessage());
        }
    }

    @NonNull
    private static String readFile(@NonNull java.io.File file) throws Exception {
        try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
            return ca.dnamobile.droidbridgelauncher.runtime.Tools.read(input);
        }
    }

    @NonNull
    private String inferLoaderNameFromVersionId(@NonNull String versionId) {
        String neoForge = NeoForgeInstaller.inferLoaderNameFromVersionId(versionId);
        if (!"Vanilla".equalsIgnoreCase(neoForge)) return neoForge;

        String forge = ForgeInstaller.inferLoaderNameFromVersionId(versionId);
        if (!"Vanilla".equalsIgnoreCase(forge)) return forge;

        return FabricInstaller.inferLoaderNameFromVersionId(versionId);
    }

    @NonNull
    private String normalizeInstalledVersionType(@Nullable String type) {
        if (type == null || type.isBlank() || TYPE_INSTALLED.equals(type)) {
            return "release";
        }
        return type;
    }

    @Nullable
    private LauncherInstance findInstanceById(@NonNull String id) {
        for (LauncherInstance instance : installedInstances) {
            if (id.equals(instance.getId())) return instance;
        }
        return null;
    }

    private void applyInstanceFilter() {
        ArrayList<LauncherInstance> filtered = new ArrayList<>();

        for (LauncherInstance instance : installedInstances) {
            if (matchesFilter(instance)) {
                filtered.add(instance);
            }
        }

        if (FILTER_RECENT.equals(selectedFilter)) {
            filtered.sort((left, right) -> Long.compare(
                    LauncherPreferences.getInstanceLastPlayed(this, right.getId()),
                    LauncherPreferences.getInstanceLastPlayed(this, left.getId())
            ));
        }

        instanceAdapter.submitList(filtered);
        binding.textVersionCount.setText(getString(R.string.instance_count_value, filtered.size()));

        if (!filtered.isEmpty()) {
            binding.recyclerVersions.scrollToPosition(0);
        }
    }

    private boolean matchesFilter(@NonNull LauncherInstance instance) {
        switch (selectedFilter) {
            case FILTER_RECENT:
                return LauncherPreferences.getInstanceLastPlayed(this, instance.getId()) > 0L;

            case FILTER_FAVORITES:
                return LauncherPreferences.isInstanceFavorite(this, instance.getId());

            case FILTER_VANILLA:
                return "vanilla".equalsIgnoreCase(instance.getLoader());

            case FILTER_MODIFIED:
                return !"vanilla".equalsIgnoreCase(instance.getLoader());

            case FILTER_SNAPSHOT:
                return "snapshot".equalsIgnoreCase(instance.getVersionType());

            case FILTER_SHARED:
                return LauncherPreferences.isShowSharedInstalls(this) && !instance.isIsolated();

            case FILTER_ALL:
            default:
                return true;
        }
    }

    private static final String TAG_BROWSE_CONTENT_MAIN_BUTTON = "browse_content_main_button_dynamic";
    private static final String TAG_IMPORT_MODPACK_MAIN_BUTTON = "import_modpack_main_button_dynamic";

    private void setupMainContentButtons() {
        View.OnClickListener browseListener = view -> {
            if (!hasActiveMicrosoftAccount()) {
                signInWithMicrosoftThen(this::openGlobalContentBrowser);
                return;
            }
            openGlobalContentBrowser();
        };

        View.OnClickListener importListener = view -> {
            if (!hasActiveMicrosoftAccount()) {
                signInWithMicrosoftThen(this::openModpackImportPicker);
                return;
            }
            openModpackImportPicker();
        };

        View browseButton = findOrCreateMainActionButton(
                "buttonBrowseContentMain",
                TAG_BROWSE_CONTENT_MAIN_BUTTON,
                "Browse Modpacks",
                browseListener,
                true
        );
        if (browseButton != null) {
            browseButton.setOnClickListener(browseListener);
            styleMainActionButton(browseButton, "Browse Modpacks", R.drawable.ic_browse_modpacks_24);
        }

        View importButton = findOrCreateMainActionButton(
                "buttonImportModpackMain",
                TAG_IMPORT_MODPACK_MAIN_BUTTON,
                "Import Modpack",
                importListener,
                true
        );
        if (importButton != null) {
            importButton.setOnClickListener(importListener);
            styleMainActionButton(importButton, "Import Modpack", R.drawable.ic_import_modpack_24);
        }

        updateMainContentButtonVisibility(hasActiveMicrosoftAccount());
    }

    private void styleMainActionButton(@NonNull View view, @NonNull String text, int iconResId) {
        if (view instanceof MaterialButton) {
            MaterialButton button = (MaterialButton) view;
            button.setText(text);
            button.setSingleLine(true);
            button.setAllCaps(false);
            button.setIconResource(iconResId);
            button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
            button.setIconPadding(dp(8));
            button.setMinHeight(dp(44));
            return;
        }

        if (view instanceof TextView) {
            ((TextView) view).setText(text);
        }
    }

    @Nullable
    private View findOrCreateMainActionButton(
            @NonNull String optionalXmlIdName,
            @NonNull String dynamicTag,
            @NonNull String text,
            @NonNull View.OnClickListener listener,
            boolean insertBeforeRefresh
    ) {
        View existing = findMainActionButton(optionalXmlIdName, dynamicTag);
        if (existing != null) {
            existing.setOnClickListener(listener);
            return existing;
        }

        if (!(binding.buttonRefreshVersions.getParent() instanceof ViewGroup)) {
            Logging.i("MainActivity", "Unable to add main action button because refresh parent is unavailable: " + text);
            return null;
        }

        ViewGroup parent = (ViewGroup) binding.buttonRefreshVersions.getParent();
        MaterialButton button = new MaterialButton(this);
        button.setTag(dynamicTag);
        button.setText(text);
        if (TAG_BROWSE_CONTENT_MAIN_BUTTON.equals(dynamicTag)) {
            button.setIconResource(R.drawable.ic_browse_modpacks_24);
        } else if (TAG_IMPORT_MODPACK_MAIN_BUTTON.equals(dynamicTag)) {
            button.setIconResource(R.drawable.ic_import_modpack_24);
        }
        button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        button.setIconPadding(dp(8));
        button.setSingleLine(true);
        button.setAllCaps(false);
        button.setMinHeight(dp(40));
        button.setMinWidth(dp(96));
        button.setOnClickListener(listener);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.leftMargin = dp(8);

        int refreshIndex = parent.indexOfChild(binding.buttonRefreshVersions);
        int insertIndex;
        if (insertBeforeRefresh && refreshIndex >= 0) {
            insertIndex = refreshIndex;
        } else {
            insertIndex = refreshIndex >= 0 ? refreshIndex + 1 : parent.getChildCount();
        }
        parent.addView(button, Math.max(0, Math.min(insertIndex, parent.getChildCount())), params);
        return button;
    }

    @Nullable
    private View findMainActionButton(@NonNull String optionalXmlIdName, @NonNull String dynamicTag) {
        if (binding == null || binding.getRoot() == null) return null;

        int id = getResources().getIdentifier(optionalXmlIdName, "id", getPackageName());
        if (id != 0) {
            View byId = binding.getRoot().findViewById(id);
            if (byId != null) return byId;
        }

        if (binding.buttonRefreshVersions.getParent() instanceof ViewGroup) {
            return ((ViewGroup) binding.buttonRefreshVersions.getParent()).findViewWithTag(dynamicTag);
        }
        return binding.getRoot().findViewWithTag(dynamicTag);
    }

    private void updateMainContentButtonVisibility(boolean signedIn) {
        if (binding == null) return;

        View browseButton = findMainActionButton("buttonBrowseContentMain", TAG_BROWSE_CONTENT_MAIN_BUTTON);
        if (browseButton != null) {
            browseButton.setVisibility(signedIn ? View.VISIBLE : View.GONE);
            browseButton.setEnabled(signedIn);
        }

        View importButton = findMainActionButton("buttonImportModpackMain", TAG_IMPORT_MODPACK_MAIN_BUTTON);
        if (importButton != null) {
            importButton.setVisibility(signedIn ? View.VISIBLE : View.GONE);
            importButton.setEnabled(signedIn);
        }
    }

    private void openGlobalContentBrowser() {
        Intent intent = new Intent(this, ContentBrowserActivity.class);
        intent.putExtra(InstanceDetailsActivity.EXTRA_CONTENT_CATEGORY, "modpacks");
        startActivity(intent);
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
        if (ControlsMain.toastAndBlockIfInvalidSignature(this)) {
            return;
        }

        setLoading(true);
        showInstallDialog("Modpack");
        beginInstallSession("Modpack");

        ModpackInstallManager.Listener listener = new ModpackInstallManager.Listener() {
            @Override
            public void onStatus(@NonNull String message) {
                dispatchInstallProgress(activeInstallProgress, message);
            }

            @Override
            public void onProgress(int current, int total) {
                int progress;
                if (total > 0) progress = Math.max(0, Math.min(100, (int) ((current * 100L) / total)));
                else {
                    progress = 0;
                }
                dispatchInstallProgress(progress, activeInstallMessage);
            }

            @Override
            public void onComplete(@NonNull String message) {
                onComplete(message, null);
            }

            @Override
            public void onComplete(@NonNull String message, @Nullable LauncherInstance instance) {
                runInstallCompletionOnUi(() -> {
                    setLoading(false);
                    finishInstallSession();
                    dismissInstallDialog();
                    refreshInstancesAndRebind(false);
                    selectedFilter = FILTER_ALL;
                    selectTabByFilter(FILTER_ALL);
                    if (instance == null) {
                        setStatus(message);
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                        return;
                    }
                    CleanroomMigrationDialog.offerAfterModpackInstall(
                            MainActivity.this,
                            message,
                            instance,
                            (finalInstance, finalMessage) -> {
                                refreshInstancesAndRebind(false);
                                setStatus(finalMessage);
                                Toast.makeText(MainActivity.this, finalMessage, Toast.LENGTH_LONG).show();
                                startActivity(InstanceDetailsActivity.createIntent(MainActivity.this, finalInstance));
                            }
                    );
                });
            }

            @Override
            public void onError(@NonNull Throwable throwable) {
                runInstallCompletionOnUi(() -> {
                    setLoading(false);
                    finishInstallSession();
                    dismissInstallDialog();
                    String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
                    setStatus("Modpack import failed: " + message);
                    Toast.makeText(MainActivity.this, "Modpack import failed: " + message, Toast.LENGTH_LONG).show();
                });
            }
        };

        Thread thread = new Thread(() -> ModpackInstallManager.importFromUri(this, uri, listener), "Import Modpack");
        thread.start();
    }

    private void setupStorageLocationsButton() {
        View.OnClickListener listener = view -> showStorageLocationsDialog();

        int storageButtonId = getResources().getIdentifier(
                "buttonStorageLocations",
                "id",
                getPackageName()
        );
        if (storageButtonId != 0) {
            View storageButton = binding.getRoot().findViewById(storageButtonId);
            if (storageButton != null) {
                storageButton.setOnClickListener(listener);
                return;
            }
        }

        if (!(binding.buttonRefreshVersions.getParent() instanceof ViewGroup)) {
            Logging.i("MainActivity", "Storage location button missing and refresh parent is unavailable");
            return;
        }

        ViewGroup parent = (ViewGroup) binding.buttonRefreshVersions.getParent();
        String tag = "storage_locations_button_dynamic";
        View existing = parent.findViewWithTag(tag);
        if (existing != null) {
            existing.setOnClickListener(listener);
            return;
        }

        MaterialButton folderButton = new MaterialButton(this);
        folderButton.setTag(tag);
        folderButton.setText("");
        folderButton.setIconResource(R.drawable.ic_folder_24);
        folderButton.setIconPadding(0);
        folderButton.setContentDescription(getString(R.string.storage_locations_title));
        folderButton.setMinWidth(dp(48));
        folderButton.setMinHeight(dp(40));
        folderButton.setOnClickListener(listener);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.rightMargin = dp(8);

        parent.addView(folderButton, 0, params);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void showStorageLocationsDialog() {
        StorageLocationDialog.show(this, new StorageLocationDialog.Listener() {
            @Override
            public void onLocationSelected(@NonNull StorageLocation location) {
                StorageLocationStore.setSelectedLocationId(MainActivity.this, location.getId());
                refreshAfterStorageLocationSelection(
                        getString(R.string.storage_location_selected, location.getDisplayName()),
                        false
                );
            }

            @Override
            public void onAddLocationRequested() {
                openStorageLocationPicker();
            }

            @Override
            public void onDeleteLocationRequested(@NonNull StorageLocation location) {
                showDeleteStorageLocationDialog(location);
            }
        });
    }

    @SuppressLint("StringFormatInvalid")
    private void showDeleteStorageLocationDialog(@NonNull StorageLocation location) {
        if (location.isDefaultLocation()) {
            Toast.makeText(this, R.string.storage_location_default_cannot_delete, Toast.LENGTH_SHORT).show();
            showStorageLocationsDialog();
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.storage_location_delete_title, location.getDisplayName()))
                .setMessage(getString(R.string.storage_location_delete_message, location.getDisplayName()))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> showStorageLocationsDialog())
                .setPositiveButton(R.string.storage_location_delete_confirm, (dialog, which) -> {
                    boolean removed = StorageLocationStore.removeLocation(this, location.getId());
                    if (!removed) {
                        Toast.makeText(this, R.string.storage_location_delete_failed, Toast.LENGTH_LONG).show();
                        showStorageLocationsDialog();
                        return;
                    }

                    refreshAfterStorageLocationChange();
                    setStatus(getString(R.string.storage_location_deleted, location.getDisplayName()));
                    Toast.makeText(this, getString(R.string.storage_location_deleted, location.getDisplayName()), Toast.LENGTH_SHORT).show();
                    showStorageLocationsDialog();
                })
                .show();
    }

    private void refreshAfterStorageLocationChange() {
        refreshAfterStorageLocationChange(null, false);
    }

    /**
     * Selecting a storage location must be cheap. This path only restores enough
     * metadata to populate the instance adapter. Full assets/libraries/saves/mods
     * are restored later, only when the user opens details or launches an instance.
     */
    private void refreshAfterStorageLocationSelection(@Nullable String finalStatus, boolean reopenStorageDialog) {
        refreshStorageLocationForAdapter(finalStatus, reopenStorageDialog);
    }

    /**
     * Adding/removing a storage location uses the same metadata-only path. The old
     * implementation did a full SAF tree copy here, which made picking a folder
     * look frozen even when only one or two instances existed.
     */
    private void refreshAfterStorageLocationChange(@Nullable String finalStatus, boolean reopenStorageDialog) {
        refreshStorageLocationForAdapter(finalStatus, reopenStorageDialog);
    }

    private void refreshStorageLocationForAdapter(@Nullable String finalStatus, boolean reopenStorageDialog) {
        selectedInstance = null;
        if (instanceAdapter != null) {
            instanceAdapter.clearSelectedInstance();
        }

        if (!StorageLocationStore.isSelectedScopedStorage(this)) {
            PathManager.initContextConstants(this);
            binding.textFolder.setText(getString(R.string.launcher_folder_value, PathManager.DIR_MINECRAFT_HOME));
            refreshInstancesAndRebind(false);
            if (finalStatus != null) setStatus(finalStatus);
            if (reopenStorageDialog) showStorageLocationsDialog();
            return;
        }

        if (!StorageLocationStore.needsSelectedTreeMetadataRestoreForAdapter(this)) {
            PathManager.initContextConstants(this);
            binding.textFolder.setText(getString(R.string.launcher_folder_value, PathManager.DIR_MINECRAFT_HOME));
            refreshInstancesAndRebind(false);
            if (finalStatus != null) setStatus(finalStatus);
            if (reopenStorageDialog) showStorageLocationsDialog();
            return;
        }

        setLoading(true);
        setStatus("Reading launcher metadata...");

        new Thread(() -> {
            Throwable syncFailure = null;
            try {
                StorageLocationStore.syncSelectedTreeMetadataToMirror(MainActivity.this, null);
            } catch (Throwable throwable) {
                syncFailure = throwable;
                Logging.i("ScopedStorage", "Unable to read scoped-storage metadata: " + throwable.getMessage());
            }

            Throwable finalSyncFailure = syncFailure;
            runOnUiThread(() -> {
                PathManager.initContextConstants(MainActivity.this);
                binding.textFolder.setText(getString(R.string.launcher_folder_value, PathManager.DIR_MINECRAFT_HOME));

                refreshInstancesAndRebind(false);
                setLoading(false);

                if (finalSyncFailure != null) {
                    String message = finalSyncFailure.getMessage() != null
                            ? finalSyncFailure.getMessage()
                            : finalSyncFailure.getClass().getSimpleName();
                    setStatus("Storage selected, but metadata read failed: " + message);
                } else if (finalStatus != null) {
                    setStatus(finalStatus);
                }

                if (reopenStorageDialog) showStorageLocationsDialog();
            });
        }, "ScopedStorageMetadataRefresh").start();
    }

    private void openStorageLocationPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);

        try {
            startActivityForResult(intent, REQUEST_ADD_STORAGE_LOCATION);
        } catch (ActivityNotFoundException throwable) {
            setStatus(getString(R.string.storage_picker_unavailable));
        }
    }

    private void showCreateInstanceDialog() {
        if (requireActiveMicrosoftAccountBeforeCreateInstance(this::showCreateInstanceDialog)) {
            return;
        }

        if (allVersions.isEmpty()) {
            Toast.makeText(this, R.string.msg_fetching_versions, Toast.LENGTH_SHORT).show();
            loadVersions(true);
            return;
        }

        createInstanceDialog = new CreateInstanceDialog(this, allVersions, new CreateInstanceDialog.Listener() {
            @Override
            public void onPickIcon(@NonNull CreateInstanceDialog dialog) {
                pickInstanceIcon();
            }

            @Override
            public void onCreateInstance(@NonNull CreateInstanceDialog.Request request) {
                createInstanceFromRequest(request);
            }
        });

        createInstanceDialog.setExistingInstanceNames(collectExistingInstanceNamesForDialog());
        createInstanceDialog.show();
    }

    @NonNull
    private ArrayList<String> collectExistingInstanceNamesForDialog() {
        ArrayList<String> names = new ArrayList<>();
        HashSet<String> addedKeys = new HashSet<>();

        for (LauncherInstance instance : installedInstances) {
            addInstanceNameForDuplicateCheck(names, addedKeys, instance.getName());
        }

        return names;
    }

    private void addInstanceNameForDuplicateCheck(
            @NonNull ArrayList<String> names,
            @NonNull HashSet<String> addedKeys,
            @Nullable String name
    ) {
        String key = normalizeInstanceNameKey(name);
        if (key.isEmpty() || !addedKeys.add(key)) return;

        names.add(name.trim());
    }

    @NonNull
    private static String normalizeInstanceNameKey(@Nullable String name) {
        if (name == null) return "";
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isolatedInstanceNameExists(@NonNull String name) {
        String targetKey = normalizeInstanceNameKey(name);
        if (targetKey.isEmpty()) return false;

        for (LauncherInstance instance : installedInstances) {
            if (instance.isIsolated()
                    && targetKey.equals(normalizeInstanceNameKey(instance.getName()))) {
                return true;
            }
        }

        return false;
    }

    private void showDuplicateInstanceNameMessage(@NonNull String name) {
        String message = getString(R.string.create_instance_name_already_exists, name);
        setStatus(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void pickInstanceIcon() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        try {
            startActivityForResult(intent, REQUEST_PICK_INSTANCE_ICON);
        } catch (ActivityNotFoundException throwable) {
            Toast.makeText(this, R.string.create_instance_icon_picker_missing, Toast.LENGTH_SHORT).show();
        }
    }

    private void createInstanceFromRequest(@NonNull CreateInstanceDialog.Request request) {
        if (ControlsMain.toastAndBlockIfInvalidSignature(this)) {
            return;
        }

        if (requireActiveMicrosoftAccountBeforeCreateInstance(() -> createInstanceFromRequest(request))) {
            return;
        }

        if (request.isolatedInstance && isolatedInstanceNameExists(request.name)) {
            showDuplicateInstanceNameMessage(request.name);
            refreshInstancesAndRebind(true);
            return;
        }

        MinecraftVersion baseVersion = findManifestVersionById(request.minecraftVersionId);

        if (baseVersion == null) {
            Toast.makeText(this, R.string.create_instance_version_missing, Toast.LENGTH_LONG).show();
            return;
        }

        final MinecraftVersion versionToInstall = baseVersion;
        setLoading(true);
        binding.buttonLaunchVersion.setEnabled(false);
        String installDisplayName = request.isolatedInstance ? request.name : request.minecraftVersionId;
        showInstallDialog(installDisplayName);
        beginInstallSession(installDisplayName);
        setStatus(getString(
                request.isolatedInstance ? R.string.create_instance_installing : R.string.create_instance_installing_shared,
                request.isolatedInstance ? request.name : request.minecraftVersionId
        ));

        MinecraftVersionInstaller.InstallProgressListener progressListener = this::dispatchInstallProgress;

        Thread thread = new Thread(() -> {
            try {
                PathManager.initContextConstants(MainActivity.this);

                if (StorageLocationStore.isSelectedScopedStorage(MainActivity.this)) {
                    /*
                     * Compatibility fallback only. If the selected folder is writable through
                     * java.io.File, StorageLocationStore uses that path directly and this block
                     * is skipped. Do not restore/crawl the selected SAF folder before install.
                     */
                    progressListener.onProgress(1, "Using scoped-storage compatibility mirror...");
                }

                MinecraftVersionInstaller.installVanillaVersion(
                        MainActivity.this,
                        versionToInstall,
                        progressListener
                );

                String launchVersionId = versionToInstall.getId();
                String loaderName = request.loader;

                if ("Fabric".equalsIgnoreCase(loaderName)) {
                    FabricInstaller.InstallResult fabricResult = FabricInstaller.installFabricVersion(
                            MainActivity.this,
                            versionToInstall,
                            request.loaderVersion,
                            progressListener
                    );
                    launchVersionId = fabricResult.getFabricVersionId();
                } else if ("Forge".equalsIgnoreCase(loaderName)) {
                    ForgeInstaller.InstallResult forgeResult = ForgeInstaller.installForgeVersion(
                            MainActivity.this,
                            versionToInstall,
                            request.name,
                            request.loaderVersion,
                            progressListener
                    );
                    launchVersionId = forgeResult.getForgeVersionId();
                } else if ("NeoForge".equalsIgnoreCase(loaderName)) {
                    NeoForgeInstaller.InstallResult neoForgeResult = NeoForgeInstaller.installNeoForgeVersion(
                            MainActivity.this,
                            versionToInstall,
                            request.name,
                            request.loaderVersion,
                            progressListener
                    );
                    launchVersionId = neoForgeResult.getNeoForgeVersionId();
                } else if ("Cleanroom".equalsIgnoreCase(loaderName)) {
                    CleanroomInstaller.InstallResult cleanroomResult = CleanroomInstaller.installCleanroomVersion(
                            MainActivity.this,
                            versionToInstall,
                            request.loaderVersion,
                            progressListener
                    );
                    launchVersionId = cleanroomResult.getCleanroomVersionId();
                }

                if (!request.isolatedInstance) {
                    ensureModsDirectoryForLoader(request.loader, null);
                    syncSelectedStorageMirrorToTree(progressListener);

                    final String sharedLaunchVersionId = launchVersionId;
                    runInstallCompletionOnUi(() -> {
                        refreshInstancesAndRebind(false);
                        if (LauncherPreferences.isShowSharedInstalls(MainActivity.this)) {
                            selectedFilter = FILTER_SHARED;
                            selectTabByFilter(FILTER_SHARED);
                            selectedInstance = findInstanceById(LauncherInstance.sharedInstanceId(
                                    sharedLaunchVersionId,
                                    new java.io.File(PathManager.DIR_MINECRAFT_HOME)
                            ));
                            instanceAdapter.setSelectedInstance(selectedInstance);
                            applyInstanceFilter();
                        } else {
                            selectedFilter = FILTER_ALL;
                            selectTabByFilter(FILTER_ALL);
                            selectedInstance = null;
                            instanceAdapter.clearSelectedInstance();
                            applyInstanceFilter();
                        }
                        updateSelectedInstanceCard();

                        setLoading(false);
                        finishInstallSession();
                        dismissInstallDialog();
                        setStatus(getString(R.string.create_instance_shared_complete, sharedLaunchVersionId));
                        Toast.makeText(this, getString(R.string.create_instance_shared_complete, sharedLaunchVersionId), Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                LauncherInstance instance = LauncherInstanceManager.createInstance(
                        MainActivity.this,
                        request.name,
                        request.loader,
                        launchVersionId,
                        request.minecraftVersionId,
                        request.versionType,
                        request.iconUri,
                        true
                );

                ensureModsDirectoryForLoader(request.loader, instance);
                syncSelectedStorageMirrorToTree(progressListener);

                runInstallCompletionOnUi(() -> {
                    refreshInstancesAndRebind(false);
                    selectedFilter = FILTER_ALL;
                    selectTabByFilter(FILTER_ALL);
                    selectedInstance = instance;
                    instanceAdapter.setSelectedInstance(instance);
                    applyInstanceFilter();
                    updateSelectedInstanceCard();

                    setLoading(false);
                    finishInstallSession();
                    dismissInstallDialog();
                    setStatus(getString(R.string.create_instance_complete, instance.getName()));
                    Toast.makeText(this, getString(R.string.create_instance_complete, instance.getName()), Toast.LENGTH_SHORT).show();
                });
            } catch (Throwable throwable) {
                Logging.e("CreateInstance", "Unable to create launcher instance", throwable);
                runInstallCompletionOnUi(() -> {
                    setLoading(false);
                    finishInstallSession();
                    dismissInstallDialog();
                    updateSelectedInstanceCard();
                    setStatus(getString(R.string.msg_version_install_failed, throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName()));
                });
            }
        }, "Create Launcher Instance");
        thread.start();
    }

    private void syncSelectedStorageMirrorToTree(@NonNull MinecraftVersionInstaller.InstallProgressListener progressListener) {
        if (!StorageLocationStore.isSelectedScopedStorage(this)) return;

        /*
         * An arbitrary SAF tree is a compatibility backup, not a directly mountable
         * java.io.File filesystem. The runtime mirror remains authoritative, but saving
         * it must never block the install-complete path. StorageLocationStore coalesces
         * repeated requests and skips files already proven unchanged.
         */
        progressListener.onProgress(99, "Finishing install...");
        StorageLocationStore.requestSelectedMirrorSyncToTree(this);
    }

    private void ensureModsDirectoryForLoader(@NonNull String loader, @Nullable LauncherInstance instance) {
        if (!"Fabric".equalsIgnoreCase(loader)
                && !"Forge".equalsIgnoreCase(loader)
                && !"NeoForge".equalsIgnoreCase(loader)
                && !"Cleanroom".equalsIgnoreCase(loader)) {
            return;
        }

        java.io.File modsDir = instance != null
                ? new java.io.File(instance.getGameDirectory(), "mods")
                : new java.io.File(PathManager.DIR_MINECRAFT_HOME, "mods");

        if (!modsDir.exists() && !modsDir.mkdirs()) {
            Logging.i("CreateInstance", "Unable to create mods folder: " + modsDir.getAbsolutePath());
        }
    }

    private void selectTabByFilter(@NonNull String filter) {
        String safeFilter = sanitizeSavedFilter(filter);
        selectedFilter = safeFilter;
        LauncherPreferences.setSelectedInstanceFilter(this, safeFilter);

        for (int i = 0; i < binding.tabVersionTypes.getTabCount(); i++) {
            TabLayout.Tab tab = binding.tabVersionTypes.getTabAt(i);
            if (tab != null && safeFilter.equals(tab.getTag())) {
                tab.select();
                return;
            }
        }
    }

    @NonNull
    private String sanitizeSavedFilter(@Nullable String filter) {
        if (filter == null) return FILTER_ALL;
        switch (filter) {
            case FILTER_ALL:
            case FILTER_RECENT:
            case FILTER_FAVORITES:
            case FILTER_VANILLA:
            case FILTER_MODIFIED:
            case FILTER_SNAPSHOT:
                return filter;
            case FILTER_SHARED:
                return LauncherPreferences.isShowSharedInstalls(this) ? FILTER_SHARED : FILTER_ALL;
            default:
                return FILTER_ALL;
        }
    }

    @Nullable
    private MinecraftVersion findManifestVersionById(@NonNull String versionId) {
        for (MinecraftVersion version : allVersions) {
            if (versionId.equals(version.getId())) return version;
        }
        return null;
    }

    private void registerNotificationPermissionLauncher() {
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        LauncherNotificationPermissionHelper.setBackgroundInstallNotificationsEnabled(this, true);
                        if (installSessionActive
                                && installDialogForegroundCheck != null
                                && installDialogForegroundCheck.isChecked()) {
                            startOrUpdateInstallForegroundService(activeInstallProgress <= 0);
                        }
                        Toast.makeText(this, R.string.notification_permission_enabled_toast, Toast.LENGTH_SHORT).show();
                    } else {
                        LauncherNotificationPermissionHelper.setBackgroundInstallNotificationsEnabled(this, false);
                        if (installSessionActive && installDialogForegroundCheck != null) {
                            installDialogForegroundCheck.setChecked(false);
                        }
                        Toast.makeText(this, R.string.notification_permission_denied_toast, Toast.LENGTH_LONG).show();
                    }
                }
        );
    }

    private void maybeShowNotificationPermissionLaunchPrompt() {
        if (!LauncherNotificationPermissionHelper.shouldShowLaunchPrompt(this)) return;

        LauncherNotificationPermissionHelper.markLaunchPromptShown(this);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.notification_permission_launch_title)
                .setMessage(R.string.notification_permission_launch_message)
                .setNegativeButton(R.string.notification_permission_not_now, null)
                .setPositiveButton(R.string.notification_permission_allow, (dialog, which) ->
                        requestNotificationPermissionIfPossible())
                .show();
    }

    private void requestNotificationPermissionIfPossible() {
        if (notificationPermissionLauncher == null) return;
        LauncherNotificationPermissionHelper.requestPostNotificationsPermission(notificationPermissionLauncher);
    }

    private void beginInstallSession(@NonNull String instanceName) {
        installSessionActive = true;
        installPermissionPromptShownThisSession = false;
        activeInstallTitle = "Installing " + instanceName;
        activeInstallMessage = "Preparing installation...";
        activeInstallProgress = 0;
        installSessionGeneration = InstallSessionState.begin(
                instanceName,
                activeInstallTitle,
                activeInstallMessage
        );
        resetInstallProgressThrottles();
        if (!uiLifecycleDestroyed && !isFinishing() && !isDestroyed()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        startOrUpdateInstallForegroundService(true);
    }

    private void resetInstallProgressThrottles() {
        lastInstallUiDispatchMs = 0L;
        lastInstallUiDispatchProgress = -1;
        lastInstallUiDispatchMessage = "";
        lastInstallNotificationUpdateMs = 0L;
        lastInstallNotificationProgress = -1;
    }

    private void dispatchInstallProgress(int progress, @NonNull String message) {
        int safeProgress = Math.max(0, Math.min(100, progress));
        String safeMessage = message.trim().isEmpty() ? activeInstallMessage : message;
        long now = SystemClock.uptimeMillis();

        boolean messageChanged = !safeMessage.equals(lastInstallUiDispatchMessage);
        boolean shouldDispatch = safeProgress <= 0
                || safeProgress >= 100
                || Math.abs(safeProgress - lastInstallUiDispatchProgress) >= 1
                || now - lastInstallUiDispatchMs >= INSTALL_UI_UPDATE_INTERVAL_MS
                || (messageChanged && now - lastInstallUiDispatchMs >= INSTALL_UI_MESSAGE_UPDATE_INTERVAL_MS);

        activeInstallProgress = safeProgress;
        activeInstallMessage = safeMessage;
        InstallSessionState.update(installSessionGeneration, safeProgress, safeMessage);

        if (!shouldDispatch) return;

        lastInstallUiDispatchMs = now;
        lastInstallUiDispatchProgress = safeProgress;
        lastInstallUiDispatchMessage = safeMessage;
        if (!uiLifecycleDestroyed) {
            mainHandler.post(() -> updateInstallProgress(safeProgress, safeMessage));
        }
    }

    private void updateInstallProgress(int progress, @NonNull String message) {
        if (binding == null) return;

        int safeProgress = Math.max(0, Math.min(100, progress));
        activeInstallProgress = safeProgress;
        activeInstallMessage = message;

        binding.progressVersions.setIndeterminate(false);
        binding.progressVersions.setMax(100);
        binding.progressVersions.setProgress(safeProgress);
        updateInstallDialog(safeProgress, message);
        setStatus(message);

        if (installSessionActive) {
            startOrUpdateInstallForegroundServiceThrottled(false);
        }
    }

    private void startOrUpdateInstallForegroundServiceThrottled(boolean indeterminate) {
        long now = SystemClock.uptimeMillis();
        boolean force = indeterminate
                || activeInstallProgress <= 0
                || activeInstallProgress >= 100
                || Math.abs(activeInstallProgress - lastInstallNotificationProgress) >= INSTALL_NOTIFICATION_PROGRESS_STEP
                || now - lastInstallNotificationUpdateMs >= INSTALL_NOTIFICATION_UPDATE_INTERVAL_MS;

        if (!force) return;

        lastInstallNotificationUpdateMs = now;
        lastInstallNotificationProgress = activeInstallProgress;
        startOrUpdateInstallForegroundService(indeterminate);
    }

    private void startOrUpdateInstallForegroundService(boolean indeterminate) {
        if (!installSessionActive) return;
        if (installDialogForegroundCheck != null && !installDialogForegroundCheck.isChecked()) return;
        if (!LauncherNotificationPermissionHelper.isBackgroundInstallNotificationsEnabled(this)) return;

        if (!LauncherNotificationPermissionHelper.hasPostNotificationsPermission(this)) {
            if (!installPermissionPromptShownThisSession) {
                installPermissionPromptShownThisSession = true;
                requestNotificationPermissionIfPossible();
            }
            return;
        }

        InstallationForegroundService.update(
                this,
                activeInstallTitle,
                activeInstallMessage,
                activeInstallProgress,
                indeterminate
        );
    }

    private void finishInstallSession() {
        boolean finishedCurrentSession = InstallSessionState.finish(installSessionGeneration);
        installSessionActive = false;
        installPermissionPromptShownThisSession = false;
        clearInstallKeepScreenOnFlagSafely();
        if (finishedCurrentSession) {
            InstallationForegroundService.stop(getApplicationContext());
        }
        resetInstallProgressThrottles();
    }

    private final Runnable installSessionLifecycleRunnable = this::pollInstallSessionLifecycle;

    private void reattachInstallSessionIfNeeded() {
        InstallSessionState.Snapshot snapshot = InstallSessionState.snapshot();
        if (!snapshot.active || uiLifecycleDestroyed || binding == null) return;

        installSessionActive = true;
        installSessionGeneration = snapshot.generation;
        activeInstallTitle = snapshot.title;
        activeInstallMessage = snapshot.message;
        activeInstallProgress = snapshot.progress;

        setLoading(true);
        if (binding.buttonLaunchVersion != null) {
            binding.buttonLaunchVersion.setEnabled(false);
        }
        if (installDialog == null || !installDialog.isShowing()) {
            showInstallDialog(snapshot.displayName);
        }
        updateInstallDialog(snapshot.progress, snapshot.message);
        setStatus(snapshot.message);
        if (!isFinishing() && !isDestroyed()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        mainHandler.removeCallbacks(installSessionLifecycleRunnable);
        mainHandler.postDelayed(installSessionLifecycleRunnable, 250L);
    }

    private void pollInstallSessionLifecycle() {
        if (uiLifecycleDestroyed || binding == null || isFinishing() || isDestroyed()) return;

        InstallSessionState.Snapshot snapshot = InstallSessionState.snapshot();
        if (snapshot.active) {
            if (snapshot.generation != installSessionGeneration) {
                installSessionGeneration = snapshot.generation;
            }
            installSessionActive = true;
            activeInstallTitle = snapshot.title;
            activeInstallMessage = snapshot.message;
            activeInstallProgress = snapshot.progress;

            if (installDialog == null || !installDialog.isShowing()) {
                showInstallDialog(snapshot.displayName);
            }
            updateInstallDialog(snapshot.progress, snapshot.message);
            setStatus(snapshot.message);
            mainHandler.postDelayed(installSessionLifecycleRunnable, 250L);
            return;
        }

        if (installSessionActive) {
            installSessionActive = false;
            clearInstallKeepScreenOnFlagSafely();
            dismissInstallDialog();
            setLoading(false);
            refreshInstancesAndRebind(true);
            updateSelectedInstanceCard();
        }
    }

    private void clearInstallKeepScreenOnFlagSafely() {
        if (uiLifecycleDestroyed || isDestroyed()) return;
        try {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (RuntimeException throwable) {
            Logging.i("InstallLifecycle", "Ignored stale window while clearing install keep-screen-on: " + throwable);
        }
    }

    private boolean canApplyInstallCompletionToUi() {
        return !uiLifecycleDestroyed && binding != null && !isFinishing() && !isDestroyed();
    }

    private void runInstallCompletionOnUi(@NonNull Runnable action) {
        runOnUiThread(() -> {
            if (!canApplyInstallCompletionToUi()) {
                // The installer outlived an Activity recreation (rotation/display hotplug).
                // Finish the process-level session and let the newly created Activity
                // observe it via InstallSessionState instead of touching this stale UI.
                finishInstallSession();
                dismissInstallDialog();
                return;
            }
            action.run();
        });
    }

    private void showInstallDialog(@NonNull String instanceName) {
        if (uiLifecycleDestroyed || isFinishing() || isDestroyed()) return;
        dismissInstallDialog();

        LinearLayout layout = new LinearLayout(this);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding / 2, padding, 0);

        installDialogMessage = new TextView(this);
        installDialogMessage.setText(getString(R.string.create_instance_installing, instanceName));

        installDialogProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        installDialogProgress.setMax(100);
        installDialogProgress.setProgress(0);
        installDialogProgress.setIndeterminate(true);

        installDialogForegroundCheck = new CheckBox(this);
        installDialogForegroundCheck.setText(R.string.install_dialog_background_notifications);
        installDialogForegroundCheck.setChecked(
                LauncherNotificationPermissionHelper.isBackgroundInstallNotificationsEnabled(this)
        );
        installDialogForegroundCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherNotificationPermissionHelper.setBackgroundInstallNotificationsEnabled(this, isChecked);
            if (!installSessionActive) return;
            if (isChecked) {
                startOrUpdateInstallForegroundService(activeInstallProgress <= 0);
            } else {
                InstallationForegroundService.stop(this);
            }
        });

        layout.addView(installDialogMessage);
        layout.addView(installDialogProgress);
        layout.addView(installDialogForegroundCheck);

        installDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.create_instance_install_dialog_title)
                .setView(layout)
                .setCancelable(false)
                .create();
        try {
            installDialog.show();
        } catch (WindowManager.BadTokenException | IllegalStateException throwable) {
            Logging.i("InstallLifecycle", "Install dialog skipped because Activity window changed: " + throwable);
            installDialog = null;
            installDialogProgress = null;
            installDialogMessage = null;
            installDialogForegroundCheck = null;
        }
    }

    private void updateInstallDialog(int progress, @NonNull String message) {
        if (installDialogMessage != null) {
            installDialogMessage.setText(message);
        }
        if (installDialogProgress != null) {
            installDialogProgress.setIndeterminate(false);
            installDialogProgress.setProgress(Math.max(0, Math.min(100, progress)));
        }
    }

    private void dismissInstallDialog() {
        AlertDialog dialog = installDialog;
        installDialog = null;
        installDialogProgress = null;
        installDialogMessage = null;
        installDialogForegroundCheck = null;

        if (dialog == null) return;
        try {
            Window window = dialog.getWindow();
            View decor = window != null ? window.getDecorView() : null;
            if (dialog.isShowing() && (decor == null || decor.isAttachedToWindow())) {
                dialog.dismiss();
            }
        } catch (IllegalArgumentException | WindowManager.BadTokenException throwable) {
            // The display/orientation may have detached this dialog between the
            // isAttachedToWindow() check and dismiss(). The installer itself is
            // process-scoped and must keep running; dropping this stale window is safe.
            Logging.i("InstallLifecycle", "Ignored stale install dialog during display change: " + throwable);
        }
    }

    private void showDeleteInstanceDialog(@NonNull LauncherInstance instance) {
        if (!instance.isIsolated()) {
            ArrayList<String> dependents = LauncherInstanceManager.findSharedVersionDependents(
                    this,
                    instance.getBaseVersionId()
            );
            if (!dependents.isEmpty()) {
                LauncherDialogStyle.showStyledMessageDialog(
                        this,
                        getString(R.string.delete_shared_instance_blocked_title, instance.getName()),
                        getString(
                                R.string.delete_shared_instance_blocked_message,
                                instance.getName(),
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
            targetPath = LauncherInstanceManager.getDeleteTargetDirectory(instance).getAbsolutePath();
        } catch (Throwable throwable) {
            targetPath = instance.getRootDirectory().getAbsolutePath();
        }

        int messageId = instance.isIsolated()
                ? R.string.delete_instance_message
                : R.string.delete_shared_instance_message;

        LauncherDialogStyle.showStyledMessageDialog(
                this,
                getString(R.string.delete_instance_title, instance.getName()),
                getString(messageId, instance.getName(), targetPath),
                getString(R.string.button_delete_forever),
                (dialog, which) -> deleteInstance(instance),
                getString(android.R.string.cancel)
        );
    }

    private void deleteInstance(@NonNull LauncherInstance instance) {
        setLoading(true);
        setStatus(getString(R.string.delete_instance_deleting, instance.getName()));

        Thread thread = new Thread(() -> {
            LauncherInstanceDeleteManager.DeleteJob deleteJob;
            try {
                deleteJob = LauncherInstanceDeleteManager.hideForDeletion(MainActivity.this, instance);
                LauncherPreferences.clearInstancePlayed(MainActivity.this, instance.getId());
                LauncherPreferences.clearInstanceFavorite(MainActivity.this, instance.getId());
            } catch (Throwable throwable) {
                Logging.e("DeleteInstance", "Unable to prepare delete for " + instance.getName(), throwable);
                runOnUiThread(() -> {
                    setLoading(false);
                    String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
                    setStatus(getString(R.string.delete_instance_failed, instance.getName(), message));
                    Toast.makeText(this, getString(R.string.delete_instance_failed, instance.getName(), message), Toast.LENGTH_LONG).show();
                });
                return;
            }

            if (deleteJob.wasMovedOutOfVisibleList() || !deleteJob.getOriginalTarget().exists()) {
                runOnUiThread(() -> finishDeleteUi(instance));
                finishDeleteInBackground(instance, deleteJob);
                return;
            }

            try {
                LauncherInstanceDeleteManager.finishDeletion(MainActivity.this, deleteJob);
                runOnUiThread(() -> finishDeleteUi(instance));
            } catch (Throwable throwable) {
                Logging.e("DeleteInstance", "Unable to delete instance " + instance.getName(), throwable);
                runOnUiThread(() -> {
                    setLoading(false);
                    String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
                    setStatus(getString(R.string.delete_instance_failed, instance.getName(), message));
                    Toast.makeText(this, getString(R.string.delete_instance_failed, instance.getName(), message), Toast.LENGTH_LONG).show();
                });
            }
        }, "Delete Launcher Instance");
        thread.start();
    }

    private void finishDeleteUi(@NonNull LauncherInstance instance) {
        if (selectedInstance != null && selectedInstance.getId().equals(instance.getId())) {
            selectedInstance = null;
        }

        refreshInstancesAndRebind(false);
        setLoading(false);
        setStatus(getString(R.string.delete_instance_deleted, instance.getName()));
        Toast.makeText(this, getString(R.string.delete_instance_deleted, instance.getName()), Toast.LENGTH_SHORT).show();
    }

    private void finishDeleteInBackground(
            @NonNull LauncherInstance instance,
            @NonNull LauncherInstanceDeleteManager.DeleteJob deleteJob
    ) {
        try {
            LauncherInstanceDeleteManager.finishDeletion(MainActivity.this, deleteJob);
        } catch (Throwable throwable) {
            Logging.e("DeleteInstance", "Instance was hidden but background cleanup failed for "
                    + instance.getName(), throwable);
        }
    }

    private void selectAndOpenInstance(@NonNull LauncherInstance instance) {
        selectedInstance = instance;
        if (instanceAdapter != null) {
            instanceAdapter.setSelectedInstance(instance);
        }
        updateSelectedInstanceCard();
        restoreScopedStorageForInstanceDetails(instance, () -> openInstanceDetails(instance));
    }

    private void openInstanceDetails(@NonNull LauncherInstance instance) {
        startActivity(InstanceDetailsActivity.createIntent(this, instance));
    }

    private void quickLaunchInstance(@NonNull LauncherInstance instance) {
        quickLaunchInstance(instance, true);
    }

    private void quickLaunchInstance(@NonNull LauncherInstance instance, boolean allowGridLastWorldQuickPlay) {
        if (ControlsMain.toastAndBlockIfInvalidSignature(this)) {
            return;
        }

        if (requireMicrosoftLoginHistoryBeforeLaunch(() -> quickLaunchInstance(instance, allowGridLastWorldQuickPlay))) {
            return;
        }

        selectedInstance = instance;
        if (instanceAdapter != null) {
            instanceAdapter.setSelectedInstance(instance);
        }
        updateSelectedInstanceCard();
        if (!SimpleVoiceChatCompat.ensureMicrophoneReadyBeforeLaunch(this, instance.getGameDirectory())) {
            return;
        }

        String gridPlayMode = allowGridLastWorldQuickPlay
                ? InstanceLaunchSettings.resolveEffectiveGridPlayIconMode(this, instance)
                : LauncherPreferences.GRID_PLAY_ICON_MODE_REGULAR;

        if (LauncherPreferences.GRID_PLAY_ICON_MODE_SERVER.equals(gridPlayMode)) {
            restoreScopedStorageForLaunchIfNeeded(instance, () -> showGridServerQuickPlayDialog(instance));
            return;
        }

        if (LauncherPreferences.GRID_PLAY_ICON_MODE_LAST_WORLD.equals(gridPlayMode)) {
            restoreScopedStorageForLaunchIfNeeded(instance, () -> showGridWorldQuickPlayDialog(instance));
            return;
        }

        restoreScopedStorageForLaunchIfNeeded(instance, () -> startGameActivity(instance, null, null));
    }

    private void launchSelectedInstance() {
        if (ControlsMain.toastAndBlockIfInvalidSignature(this)) {
            return;
        }

        if (selectedInstance == null) {
            Toast.makeText(this, R.string.hint_select_instance, Toast.LENGTH_SHORT).show();
            return;
        }

        if (requireMicrosoftLoginHistoryBeforeLaunch(this::launchSelectedInstance)) {
            return;
        }
        if (!SimpleVoiceChatCompat.ensureMicrophoneReadyBeforeLaunch(this, selectedInstance.getGameDirectory())) {
            return;
        }
        LauncherInstance instanceToLaunch = selectedInstance;
        restoreScopedStorageForLaunchIfNeeded(instanceToLaunch, () -> startGameActivity(instanceToLaunch));
    }

    private void restoreScopedStorageForInstanceDetails(
            @NonNull LauncherInstance instance,
            @NonNull Runnable afterRestore
    ) {
        afterRestore.run();

        if (!StorageLocationStore.needsSelectedLocalPathRestoreForDetails(this, instance.getRootDirectory())) {
            return;
        }

        new Thread(() -> {
            try {
                StorageLocationStore.syncSelectedLocalPathFromTree(MainActivity.this, instance.getRootDirectory(), null);
            } catch (Throwable throwable) {
                Logging.i("ScopedStorage", "Background instance detail restore skipped/failed: "
                        + throwable.getMessage());
            }
        }, "ScopedStorageInstanceDetailsBackground").start();
    }

    private void restoreScopedStorageForLaunchIfNeeded(
            @NonNull LauncherInstance instance,
            @NonNull Runnable afterRestore
    ) {
        if (!StorageLocationStore.needsSelectedTreeRestoreForLaunch(this, instance.getRootDirectory(), instance.getBaseVersionId())) {
            afterRestore.run();
            return;
        }

        setLoading(true);
        binding.buttonLaunchVersion.setEnabled(false);
        setStatus("Preparing game files...");
        showLaunchPrepareDialog(instance);

        new Thread(() -> {
            Throwable restoreFailure = null;
            try {
                StorageLocationStore.syncSelectedTreeToMirror(
                        MainActivity.this,
                        (progress, message) -> mainHandler.post(() -> {
                            String safeMessage = message == null || message.trim().isEmpty()
                                    ? "Preparing game files..."
                                    : message.trim();
                            updateLaunchPrepareProgress(progress, safeMessage);
                            setStatus("Launching game: " + safeMessage);
                        })
                );
            } catch (Throwable throwable) {
                restoreFailure = throwable;
                Logging.i("ScopedStorage", "Unable to restore scoped storage before launch: " + throwable.getMessage());
            }

            Throwable finalRestoreFailure = restoreFailure;
            runOnUiThread(() -> {
                PathManager.initContextConstants(MainActivity.this);
                binding.textFolder.setText(getString(R.string.launcher_folder_value, PathManager.DIR_MINECRAFT_HOME));
                refreshInstancesAndRebind(true);
                setLoading(false);

                if (finalRestoreFailure != null) {
                    dismissLaunchPrepareDialog();
                    String message = finalRestoreFailure.getMessage() != null
                            ? finalRestoreFailure.getMessage()
                            : finalRestoreFailure.getClass().getSimpleName();
                    setStatus("Unable to prepare scoped storage files: " + message);
                    Toast.makeText(MainActivity.this, "Unable to prepare scoped storage files: " + message, Toast.LENGTH_LONG).show();
                    return;
                }

                updateLaunchPrepareProgress(100, "Starting Minecraft...");
                dismissLaunchPrepareDialog();
                afterRestore.run();
            });
        }, "ScopedStorageLaunchRestore").start();
    }

    private void showLaunchPrepareDialog(@NonNull LauncherInstance instance) {
        dismissLaunchPrepareDialog();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_DIALOG_BG);
        int rootPadding = dp(18);
        root.setPadding(rootPadding, rootPadding, rootPadding, dp(10));

        TextView title = new TextView(this);
        title.setText("Launching game");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(COLOR_TEXT_PRIMARY);
        title.setPadding(dp(2), 0, dp(2), dp(6));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView info = new TextView(this);
        info.setText("Preparing local game files for " + instance.getName()
                + ". This usually only happens after reinstalling the app or choosing a scoped storage folder.");
        info.setTextSize(14);
        info.setTextColor(COLOR_TEXT_SECONDARY);
        info.setPadding(dp(2), 0, dp(2), dp(12));
        root.addView(info, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int cardPadding = dp(14);
        card.setPadding(cardPadding, cardPadding, cardPadding, cardPadding);
        card.setBackground(roundedDrawable(COLOR_CARD_BG, COLOR_CARD_STROKE, 18));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, 0, 0, dp(12));
        root.addView(card, cardParams);

        TextView cardTitle = new TextView(this);
        cardTitle.setText("Restoring scoped storage");
        cardTitle.setTextSize(18);
        cardTitle.setTypeface(Typeface.DEFAULT_BOLD);
        cardTitle.setTextColor(COLOR_TEXT_PRIMARY);
        cardTitle.setPadding(0, 0, 0, dp(8));
        card.addView(cardTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        launchPrepareMessage = new TextView(this);
        launchPrepareMessage.setText("Checking game files...");
        launchPrepareMessage.setTextSize(13);
        launchPrepareMessage.setTextColor(COLOR_TEXT_SECONDARY);
        launchPrepareMessage.setPadding(0, 0, 0, dp(10));
        card.addView(launchPrepareMessage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout progressRow = new LinearLayout(this);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        progressRow.setPadding(0, 0, 0, dp(4));

        TextView progressLabel = new TextView(this);
        progressLabel.setText("Progress");
        progressLabel.setTextSize(13);
        progressLabel.setTextColor(COLOR_TEXT_MUTED);
        progressRow.addView(progressLabel, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        launchPreparePercent = new TextView(this);
        launchPreparePercent.setText("Loading...");
        launchPreparePercent.setTextSize(13);

        launchPreparePercent.setTypeface(Typeface.DEFAULT_BOLD);
        launchPreparePercent.setTextColor(COLOR_ACCENT);
        launchPreparePercent.setGravity(Gravity.END);
        progressRow.addView(launchPreparePercent, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        card.addView(progressRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        launchPrepareProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        launchPrepareProgress.setIndeterminate(true);
        launchPrepareProgress.setMax(100);
        launchPrepareProgress.setProgress(0);
        tintProgressBar(launchPrepareProgress);
        card.addView(launchPrepareProgress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView hint = new TextView(this);
        hint.setText("Please keep this screen open and do not press Play again. The next launch should be much faster.");
        hint.setTextSize(12);
        hint.setTextColor(COLOR_TEXT_MUTED);
        hint.setPadding(0, dp(10), 0, 0);
        card.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        launchPrepareDialog = new MaterialAlertDialogBuilder(this)
                .setView(root)
                .setCancelable(false)
                .create();
        launchPrepareDialog.show();
        styleLaunchPrepareDialogChrome(launchPrepareDialog);
        updateLaunchPrepareProgress(1, "Checking game files...");
    }

    private void updateLaunchPrepareProgress(int progress, @NonNull String message) {
        String safeMessage = message.trim().isEmpty() ? "Preparing game files..." : message.trim();

        if (launchPrepareMessage != null) {
            launchPrepareMessage.setText(safeMessage);
        }

        if (launchPreparePercent != null) {
            if (progress >= 100) {
                launchPreparePercent.setText("Done");
            } else {
                launchPreparePercent.setText("Loading...");
            }
        }

        if (launchPrepareProgress != null) {
            if (progress >= 100) {
                launchPrepareProgress.setIndeterminate(false);
                launchPrepareProgress.setProgress(100);
            } else {
                launchPrepareProgress.setIndeterminate(true);
            }
        }
    }

    private void dismissLaunchPrepareDialog() {
        if (launchPrepareDialog != null) {
            launchPrepareDialog.dismiss();
            launchPrepareDialog = null;
        }
        launchPrepareMessage = null;
        launchPreparePercent = null;
        launchPrepareProgress = null;
    }

    private void styleLaunchPrepareDialogChrome(@NonNull AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(roundedDrawable(COLOR_DIALOG_BG, COLOR_DIALOG_BG, 22));
        window.setDimAmount(0.58f);
    }

    @NonNull
    private GradientDrawable roundedDrawable(int fillColor, int strokeColor, int cornerDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fillColor);
        bg.setCornerRadius(dp(cornerDp));
        bg.setStroke(dp(1), strokeColor);
        return bg;
    }

    private void tintProgressBar(@NonNull ProgressBar progressBar) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        progressBar.setProgressTintList(ColorStateList.valueOf(COLOR_ACCENT));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(COLOR_CARD_STROKE));
        progressBar.setIndeterminateTintList(ColorStateList.valueOf(COLOR_ACCENT));
    }

    private void startGameActivity(@NonNull LauncherInstance instance) {
        startGameActivity(instance, null, null);
    }

    private void startGameActivity(
            @NonNull LauncherInstance instance,
            @Nullable String quickPlayWorld,
            @Nullable String quickPlayServer
    ) {
        LauncherPreferences.recordInstancePlayed(this, instance.getId());

        Intent intent = new Intent(this, GameActivity.class);
        intent.putExtra(
                GameActivity.EXTRA_VERSION_ID,
                instance.isIsolated() ? instance.getName() : instance.getBaseVersionId()
        );
        intent.putExtra(GameActivity.EXTRA_INSTANCE_SETTINGS_KEY, instance.getId());

        if (quickPlayWorld != null && !quickPlayWorld.trim().isEmpty()) {
            intent.putExtra(GameActivity.EXTRA_QUICK_PLAY_WORLD, quickPlayWorld.trim());
            setStatus("Opening world: " + quickPlayWorld.trim());
        }
        if (quickPlayServer != null && !quickPlayServer.trim().isEmpty()) {
            intent.putExtra(GameActivity.EXTRA_QUICK_PLAY_SERVER, quickPlayServer.trim());
            setStatus("Opening server: " + quickPlayServer.trim());
        }

        startActivity(intent);
    }

    @Nullable
    private String resolveGridQuickPlayWorld(@NonNull LauncherInstance instance) {
        if (!QuickPlayHelper.supportsQuickPlay(instance.getMinecraftVersionId(), instance.getBaseVersionId())) {
            return null;
        }
        return QuickPlayHelper.findLastPlayedWorldFolderName(instance.getGameDirectory());
    }

    private void showGridWorldQuickPlayDialog(@NonNull LauncherInstance instance) {
        if (!QuickPlayHelper.supportsQuickPlay(instance.getMinecraftVersionId(), instance.getBaseVersionId())) {
            Toast.makeText(this, R.string.quick_play_world_unsupported, Toast.LENGTH_LONG).show();
            startGameActivity(instance, null, null);
            return;
        }

        ArrayList<QuickPlayHelper.WorldEntry> worlds;
        try {
            worlds = QuickPlayHelper.readWorldList(instance.getGameDirectory());
        } catch (Throwable throwable) {
            Logging.e("GridWorldQuickPlay", "Unable to read world list for " + instance.getName(), throwable);
            String message = throwable.getMessage() != null
                    ? throwable.getMessage()
                    : throwable.getClass().getSimpleName();
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return;
        }

        if (worlds.isEmpty()) {
            Toast.makeText(this, R.string.world_quick_play_no_worlds, Toast.LENGTH_LONG).show();
            return;
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setClipToPadding(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setHorizontalScrollBarEnabled(false);
        scrollView.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);

        LinearLayout root = LauncherDialogStyle.createDialogRoot(
                this,
                getString(R.string.world_quick_play_title),
                getString(R.string.grid_play_icon_world_dialog_summary)
        );
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout worldCard = new LinearLayout(this);
        worldCard.setOrientation(LinearLayout.VERTICAL);
        worldCard.setPadding(dp(12), dp(12), dp(12), dp(6));
        worldCard.setBackground(LauncherDialogStyle.roundedDrawable(
                this,
                LauncherDialogStyle.COLOR_CARD_BG,
                LauncherDialogStyle.COLOR_CARD_STROKE,
                18
        ));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, dp(2), 0, dp(10));
        root.addView(worldCard, cardParams);

        final AlertDialog[] dialogRef = new AlertDialog[1];
        for (QuickPlayHelper.WorldEntry world : worlds) {
            addGridWorldQuickPlayRow(worldCard, instance, world, dialogRef);
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialogRef[0] = dialog;
        dialog.setOnShowListener(unused -> LauncherDialogStyle.styleDialogChrome(this, dialog));
        dialog.show();
        LauncherDialogStyle.styleDialogChrome(this, dialog);
    }

    private void addGridWorldQuickPlayRow(
            @NonNull LinearLayout parent,
            @NonNull LauncherInstance instance,
            @NonNull QuickPlayHelper.WorldEntry world,
            @NonNull AlertDialog[] dialogRef
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(78));
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(LauncherDialogStyle.roundedDrawable(
                this,
                LauncherDialogStyle.COLOR_CARD_BG_PRESSED,
                LauncherDialogStyle.COLOR_CARD_STROKE,
                14
        ));

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setBackground(LauncherDialogStyle.roundedDrawable(
                this,
                LauncherDialogStyle.COLOR_CARD_BG,
                LauncherDialogStyle.COLOR_CARD_STROKE,
                12
        ));
        icon.setPadding(dp(4), dp(4), dp(4), dp(4));
        bindWorldIcon(icon, instance, world);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(56), dp(56));
        iconParams.setMargins(0, 0, dp(12), 0);
        row.addView(icon, iconParams);

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(world.name);
        name.setTextSize(16f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textColumn.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        if (!world.name.equals(world.folderName)) {
            TextView folder = new TextView(this);
            folder.setText(world.folderName);
            folder.setTextSize(13f);
            folder.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
            folder.setSingleLine(true);
            folder.setEllipsize(android.text.TextUtils.TruncateAt.END);
            folder.setPadding(0, dp(3), 0, 0);
            textColumn.addView(folder, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }

        row.addView(textColumn, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        row.setOnClickListener(view -> {
            AlertDialog dialog = dialogRef[0];
            if (dialog != null) dialog.dismiss();
            startGameActivity(instance, world.folderName, null);
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(8));
        parent.addView(row, params);
    }

    private void bindWorldIcon(
            @NonNull ImageView icon,
            @NonNull LauncherInstance instance,
            @NonNull QuickPlayHelper.WorldEntry world
    ) {
        icon.setImageResource(R.drawable.ic_instance_world_24);
        File iconFile = new File(new File(new File(instance.getGameDirectory(), "saves"), world.folderName), "icon.png");
        if (!iconFile.isFile()) return;

        try {
            Bitmap bitmap = BitmapFactory.decodeFile(iconFile.getAbsolutePath());
            if (bitmap != null) icon.setImageBitmap(bitmap);
        } catch (Throwable throwable) {
            Logging.i("GridWorldQuickPlay", "Unable to load world icon for " + world.folderName + ": " + throwable.getMessage());
        }
    }

    private void showGridServerQuickPlayDialog(@NonNull LauncherInstance instance) {
        ArrayList<QuickPlayHelper.ServerEntry> servers;
        try {
            servers = QuickPlayHelper.readServerList(instance.getGameDirectory());
        } catch (Throwable throwable) {
            Logging.e("GridServerQuickPlay", "Unable to read server list for " + instance.getName(), throwable);
            String message = throwable.getMessage() != null
                    ? throwable.getMessage()
                    : throwable.getClass().getSimpleName();
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return;
        }

        if (servers.isEmpty()) {
            Toast.makeText(this, R.string.server_quick_play_no_servers, Toast.LENGTH_LONG).show();
            return;
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setClipToPadding(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setHorizontalScrollBarEnabled(false);
        scrollView.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);

        LinearLayout root = LauncherDialogStyle.createDialogRoot(
                this,
                getString(R.string.server_quick_play_title),
                getString(R.string.grid_play_icon_server_dialog_summary)
        );
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout serverCard = new LinearLayout(this);
        serverCard.setOrientation(LinearLayout.VERTICAL);
        serverCard.setPadding(dp(12), dp(12), dp(12), dp(6));
        serverCard.setBackground(LauncherDialogStyle.roundedDrawable(
                this,
                LauncherDialogStyle.COLOR_CARD_BG,
                LauncherDialogStyle.COLOR_CARD_STROKE,
                18
        ));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, dp(2), 0, dp(10));
        root.addView(serverCard, cardParams);

        final AlertDialog[] dialogRef = new AlertDialog[1];
        for (QuickPlayHelper.ServerEntry server : servers) {
            addGridServerQuickPlayRow(serverCard, instance, server, dialogRef);
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialogRef[0] = dialog;
        dialog.setOnShowListener(unused -> LauncherDialogStyle.styleDialogChrome(this, dialog));
        dialog.show();
        LauncherDialogStyle.styleDialogChrome(this, dialog);
    }

    private void addGridServerQuickPlayRow(
            @NonNull LinearLayout parent,
            @NonNull LauncherInstance instance,
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
        row.setBackground(LauncherDialogStyle.roundedDrawable(
                this,
                LauncherDialogStyle.COLOR_CARD_BG_PRESSED,
                LauncherDialogStyle.COLOR_CARD_STROKE,
                14
        ));

        TextView name = new TextView(this);
        name.setText(server.name);
        name.setTextSize(16f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
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
            address.setTextSize(13f);
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
            startGameActivity(instance, null, server.address);
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(8));
        parent.addView(row, params);
    }

    private void showSelectedInstanceFolder() {
        if (selectedInstance == null) {
            Toast.makeText(this, R.string.hint_select_instance, Toast.LENGTH_SHORT).show();
            return;
        }

        String path = selectedInstance.getGameDirectory().getAbsolutePath();
        setStatus(getString(R.string.msg_instance_folder_location, path));
        Toast.makeText(this, path, Toast.LENGTH_LONG).show();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (appIntegrityBlocked || binding == null) {
            return;
        }

        if (requestCode == REQUEST_IMPORT_MODPACK) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                importModpackFromUri(data.getData());
            }
            return;
        }

        if (requestCode == REQUEST_ADD_STORAGE_LOCATION) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                return;
            }

            Uri treeUri = data.getData();
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                getContentResolver().takePersistableUriPermission(treeUri, flags);
            } catch (Throwable ignored) {
            }

            StorageLocation location = StorageLocationStore.addTreeUri(this, treeUri);
            StorageLocationStore.setSelectedLocationId(this, location.getId());
            refreshAfterStorageLocationChange(
                    getString(R.string.storage_location_added, location.getDisplayName()),
                    false
            );
            return;
        }

        if (requestCode != REQUEST_PICK_INSTANCE_ICON || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri iconUri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(
                    iconUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (Throwable ignored) {
        }

        if (createInstanceDialog != null && createInstanceDialog.isShowing()) {
            createInstanceDialog.setIconUri(iconUri);
        }
    }

    private void updateSelectedInstanceCard() {
        if (selectedInstance == null) {
            binding.textSelectedVersion.setText(R.string.selected_instance_empty);
            binding.buttonLaunchVersion.setEnabled(false);
            binding.buttonOpenFolder.setEnabled(false);
            return;
        }

        binding.textSelectedVersion.setText(getString(
                R.string.selected_instance_value,
                selectedInstance.getName(),
                selectedInstance.getLoader(),
                selectedInstance.getMinecraftVersionId()
        ));
        binding.buttonLaunchVersion.setEnabled(true);
        binding.buttonOpenFolder.setEnabled(true);
    }

    private void updateAccountStatus(@Nullable AccountStore.Account account) {
        boolean signedIn = account != null;
        updateMainContentButtonVisibility(signedIn);

        binding.buttonSignIn.setVisibility(signedIn ? View.GONE : View.VISIBLE);
        binding.buttonSignIn.setEnabled(!signedIn);
        binding.buttonSignOut.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        binding.buttonSignOut.setEnabled(signedIn);

        if (account == null) {
            if (hasCompletedMicrosoftLoginOnce()) {
                binding.textAccountStatus.setText(R.string.status_signed_out_offline_unlocked);
            } else {
                binding.textAccountStatus.setText(R.string.status_signed_out);
            }
            return;
        }

        String name = account.displayName;
        if (isNullOrBlank(name)) name = account.minecraftName;
        if (isNullOrBlank(name)) name = account.email;
        if (isNullOrBlank(name)) name = "Microsoft Player";
        binding.textAccountStatus.setText(getString(R.string.status_signed_in, name));
    }

    private static boolean isNullOrBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    private void setLoading(boolean loading) {
        binding.progressVersions.setIndeterminate(loading);
        binding.progressVersions.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.buttonRefreshVersions.setEnabled(!loading);
        binding.fabCreateInstance.setEnabled(!loading);
        if (!loading) updateSelectedInstanceCard();
    }

    private void setStatus(@NonNull String status) {
        if (binding == null || binding.textStatus == null) return;

        mainHandler.removeCallbacks(hideStatusRunnable);
        binding.textStatus.setVisibility(View.VISIBLE);

        CharSequence current = binding.textStatus.getText();
        if (current == null || !status.contentEquals(current)) {
            binding.textStatus.setText(status);
        }

        mainHandler.postDelayed(hideStatusRunnable, installSessionActive ? 5000L : 2600L);
    }
}

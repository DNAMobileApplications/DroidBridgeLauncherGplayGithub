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

import android.Manifest;
import android.annotation.SuppressLint;
import androidx.appcompat.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.tabs.TabLayout;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ca.dnamobile.droidbridgelauncher.fancymenu.MicrosoftAuthConfigPersonal;
import ca.dnamobile.droidbridgelauncher.fancymenu.MicrosoftAuthManagerPersonal;
import ca.dnamobile.droidbridgelauncher.fancymenu.MicrosoftAccountRegistry;
import ca.dnamobile.droidbridgelauncher.controls.ControlsActivity;
import ca.dnamobile.droidbridgelauncher.controls.ControlsPreferences;
import ca.dnamobile.droidbridgelauncher.cleanup.GameAssetCleanupManager;
import ca.dnamobile.droidbridgelauncher.storage.DroidBridgeBackupManager;
import ca.dnamobile.droidbridgelauncher.data.AccountStore;
import ca.dnamobile.droidbridgelauncher.data.model.MinecraftVersion;
import ca.dnamobile.droidbridgelauncher.databinding.ActivityLauncherSettingsBinding;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.feature.unpack.ComponentInstallationManager;
import ca.dnamobile.droidbridgelauncher.input.GamepadMappingDialog;
import ca.dnamobile.droidbridgelauncher.input.GamepadMappingStore;
import ca.dnamobile.droidbridgelauncher.input.GyroInputController;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstance;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstanceManager;
import ca.dnamobile.droidbridgelauncher.legal.LegalLinks;
import ca.dnamobile.droidbridgelauncher.launcher.RuntimeCompat;
import ca.dnamobile.droidbridgelauncher.logs.LauncherLogManager;
import ca.dnamobile.droidbridgelauncher.logs.LauncherDiagnosticLog;
import ca.dnamobile.droidbridgelauncher.modcompat.AndroidMicrophonePermission;
import ca.dnamobile.droidbridgelauncher.notifications.LauncherNotificationPermissionHelper;
import ca.dnamobile.droidbridgelauncher.renderer.Driver;
import ca.dnamobile.droidbridgelauncher.renderer.DriverPluginManager;
import ca.dnamobile.droidbridgelauncher.renderer.MobileGluesConfigHelper;
import ca.dnamobile.droidbridgelauncher.renderer.BtaRendererPolicy;
import ca.dnamobile.droidbridgelauncher.renderer.RendererInterface;
import ca.dnamobile.droidbridgelauncher.renderer.RendererPluginManager;
import ca.dnamobile.droidbridgelauncher.renderer.Renderers;
import ca.dnamobile.droidbridgelauncher.renderer.RendererVersionRules;
import ca.dnamobile.droidbridgelauncher.settings.GameOverlayPreferences;
import ca.dnamobile.droidbridgelauncher.settings.GameResolutionSettings;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.settings.MemoryAllocationUtils;
import ca.dnamobile.droidbridgelauncher.recording.RecordingPreferences;
import ca.dnamobile.droidbridgelauncher.skin.CustomSkinStore;
import ca.dnamobile.droidbridgelauncher.skin.MicrosoftCapeService;
import ca.dnamobile.droidbridgelauncher.skin.MicrosoftSkinUploader;
import ca.dnamobile.droidbridgelauncher.skin.PlayerHeadLoader;
import ca.dnamobile.droidbridgelauncher.skin.PlayerModelPreviewView;
import ca.dnamobile.droidbridgelauncher.skin.SkinModelType;
import ca.dnamobile.droidbridgelauncher.update.LauncherUpdateDialogs;
import ca.dnamobile.droidbridgelauncher.update.LauncherUpdatePreferences;
import ca.dnamobile.droidbridgelauncher.ui.version.MinecraftVersionInstaller;
import ca.dnamobile.droidbridgelauncher.utils.AppOrientationHelper;
import ca.dnamobile.droidbridgelauncher.utils.FullscreenUtils;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;
import ca.dnamobile.droidbridgelauncher.utils.path.LibPath;
import ca.dnamobile.droidbridgelauncher.runtime.multirt.MultiRTUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class LauncherSettingsActivity extends AppCompatActivity {
    // Dialog colors are resolved from the active DroidBridge theme after super.onCreate().
    // These fallback values are only used during the tiny window before theme resolution.
    // Keeping custom dialogs theme-aware avoids dark-only cards with light-theme Spinner text.
    private int COLOR_DIALOG_BG = Color.rgb(30, 34, 42);
    private int COLOR_CARD_BG = Color.rgb(38, 43, 53);
    private int COLOR_CARD_BG_PRESSED = Color.rgb(43, 49, 60);
    private int COLOR_CARD_STROKE = Color.rgb(54, 61, 74);
    private int COLOR_TEXT_PRIMARY = Color.rgb(238, 241, 248);
    private int COLOR_TEXT_SECONDARY = Color.rgb(198, 204, 216);
    private int COLOR_TEXT_MUTED = Color.rgb(150, 159, 176);
    private int COLOR_ACCENT = Color.rgb(217, 87, 0);
    private int COLOR_ACCENT_MUTED = Color.rgb(141, 115, 100);
    private static final float DIALOG_DIM_NORMAL = 0.58f;

    private static final String JAR_EXECUTION_PREFS = "jar_execution";
    private static final String JAR_EXECUTION_LAST_SUMMARY_KEY = "last_summary";
    private static final String JAR_EXECUTION_PENDING_OF_PROFILE_KEY = "pending_optifine_profile";
    private static final String JAR_EXECUTION_PENDING_OF_MC_KEY = "pending_optifine_minecraft";
    private static final String JAR_EXECUTION_PENDING_OF_DISPLAY_KEY = "pending_optifine_display";
    private static final String JAR_EXECUTION_PENDING_OF_STARTED_KEY = "pending_optifine_started";

    private static final String BTA_BASE_VERSION_ID = "b1.7.3";
    private static final String BTA_RELEASE_MANIFEST_URL = "https://downloads.betterthanadventure.net/bta-client/release/versions.json";
    private static final String BTA_NIGHTLY_MANIFEST_URL = "https://downloads.betterthanadventure.net/bta-client/nightly/versions.json";
    private static final String BTA_RELEASE_CLIENT_URL_FORMAT = "https://downloads.betterthanadventure.net/bta-client/release/%s/client.jar";
    private static final String BTA_NIGHTLY_CLIENT_URL_FORMAT = "https://downloads.betterthanadventure.net/bta-client/nightly/%s/client.jar";
    private static final String BTA_RELEASE_ICON_URL_FORMAT = "https://downloads.betterthanadventure.net/bta-client/release/%s/auto/%s.png";
    private static final String BTA_NIGHTLY_ICON_URL_FORMAT = "https://downloads.betterthanadventure.net/bta-client/nightly/%s/auto/%s.png";
    private static final String[] BTA_TESTED_VERSION_NAMES = new String[]{
            "v7.3",
            "v7.2_01",
            "v7.2",
            "v7.1_01",
            "v7.1"
    };

    private static final String FABRIC_GAME_VERSIONS_URL = "https://meta.fabricmc.net/v2/versions/game";
    private static final String FABRIC_LOADER_VERSIONS_URL = "https://meta.fabricmc.net/v2/versions/loader";
    private static final String LEGACY_FABRIC_GAME_VERSIONS_URL = "https://meta.legacyfabric.net/v2/versions/game";
    private static final String LEGACY_FABRIC_LOADER_VERSIONS_URL = "https://meta.legacyfabric.net/v2/versions/loader";
    private static final String LEGACY_FABRIC_META_URL = "https://meta.legacyfabric.net/";
    private static final String LEGACY_FABRIC_MAVEN_URL = "https://maven.legacyfabric.net/";
    private static final int FABRIC_MAX_SNAPSHOT_CHOICES = 80;
    private static final String[] LEGACY_FABRIC_SUPPORTED_MINECRAFT_VERSIONS = new String[]{
            "1.13.2",
            "1.12.2",
            "1.11.2",
            "1.10.2",
            "1.9.4",
            "1.8.9",
            "1.7.10",
            "1.6.4",
            "1.5.2",
            "1.4.7",
            "1.3.2"
    };

    private static final String SETTINGS_DEFAULTS_PREFS = "launcher_settings_defaults";
    private static final String SETTINGS_DEFAULTS_APPLIED_KEY = "settings_defaults_applied_2026_04_instances";

    // Change this URL to your official DroidBridge renderer download page.
    private static final String RENDERER_DOWNLOAD_URL = "https://github.com/MobileGL-Dev/MobileGlues-release/releases";

    private ActivityLauncherSettingsBinding binding;
    private AccountStore accountStore;
    private MicrosoftAuthManagerPersonal authManager;
    private CustomSkinStore customSkinStore;
    private ActivityResultLauncher<Intent> customSkinPickerLauncher;
    private ActivityResultLauncher<Intent> microsoftSkinPickerLauncher;
    private ActivityResultLauncher<Intent> offlineSkinPickerLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private ActivityResultLauncher<String> microphonePermissionLauncher;
    private ActivityResultLauncher<Intent> mobileGluesFolderPickerLauncher;
    private boolean updatingBmclApiSwitch;
    private ActivityResultLauncher<Intent> droidBridgeBackupFolderPickerLauncher;
    private ActivityResultLauncher<Intent> droidBridgeRestoreZipPickerLauncher;
    private ActivityResultLauncher<Intent> mouseCursorIconPickerLauncher;
    private ActivityResultLauncher<Intent> dualScreenBackgroundPickerLauncher;
    private ActivityResultLauncher<Intent> dualScreenMapFramePickerLauncher;
    private ActivityResultLauncher<Intent> jarExecutionPickerLauncher;
    private ActivityResultLauncher<Intent> customRuntimeArchivePickerLauncher;
    @Nullable
    private String pendingCustomRuntimeName;
    private Uri pendingOfflineSkinUri;
    private ImageView pendingOfflineSkinPreview;
    private TextView pendingOfflineSkinLabel;
    private AlertDialog offlineAccountsDialog;
    private AlertDialog microsoftAccountsDialog;
    private final List<RendererInterface> availableRenderers = new ArrayList<>();
    private final List<Driver> availableDrivers = new ArrayList<>();
    private boolean rendererSpinnerReady;
    private boolean driverSpinnerReady;
    @Nullable private MaterialButton buttonRendererVersionRules;
    @Nullable private TextView textRendererVersionRulesSummary;
    @Nullable private MaterialButton buttonDualScreenHudLayout;
    @Nullable private TextView textDualScreenHudLayoutSummary;
    @Nullable private MaterialButton buttonDualScreenTouchScale;
    @Nullable private TextView textDualScreenTouchScaleSummary;
    @Nullable private MaterialButton buttonDualScreenBackground;
    @Nullable private TextView textDualScreenBackgroundSummary;
    @Nullable private MaterialButton buttonDualScreenMapFrame;
    @Nullable private TextView textDualScreenMapFrameSummary;
    @Nullable private MaterialCardView cardDualScreenSettings;
    @Nullable private MaterialCardView cardRecordingSettings;
    @Nullable private com.google.android.material.switchmaterial.SwitchMaterial switchRecordingBothScreens;
    @Nullable private TextView textRecordingBothScreensSummary;
    private volatile boolean runtimeComponentsReinstalling;
    private TextView textControllerCameraSensitivity;
    private SeekBar sliderControllerCameraSensitivity;
    private TextView textHardwareMouseDpiScale;
    private SeekBar sliderHardwareMouseDpiScale;
    @Nullable private MaterialButton buttonPhysicalMouseMode;
    @Nullable private TextView textPhysicalMouseModeSummary;
    private com.google.android.material.switchmaterial.SwitchMaterial switchVirtualMouseAtGameStart;
    private com.google.android.material.switchmaterial.SwitchMaterial switchGyroscopeCamera;
    private TextView textGyroscopeCameraSummary;
    private TextView textVirtualMouseSpeed;
    private SeekBar sliderVirtualMouseSpeed;
    private int playerModelCapeLoadGeneration;
    private static final long MICROSOFT_CAPE_PROFILE_CACHE_MS = 300_000L;
    private static final long MICROSOFT_CAPE_SELECTION_OVERRIDE_MS = 300_000L;
    @Nullable
    private volatile MicrosoftCapeService.Profile cachedMicrosoftCapeProfile;
    @Nullable
    private volatile String cachedMicrosoftCapeToken;
    private volatile long cachedMicrosoftCapeProfileAt;
    private volatile boolean microsoftCapeSelectionOverrideSet;
    @Nullable
    private volatile String microsoftCapeSelectionOverrideId;
    private volatile long microsoftCapeSelectionOverrideAt;
    private boolean pendingMicrosoftCapeOpenAfterAuthRefresh;
    private volatile boolean microsoftCapeProfileAuthenticationStale;
    @Nullable
    private AccountStore.Account microsoftCapeActiveAccountBeforeRefresh;
    @Nullable
    private AlertDialog jarExecutionProgressDialog;
    @Nullable
    private TextView jarExecutionProgressTitleView;
    @Nullable
    private TextView jarExecutionProgressStatusView;
    @Nullable
    private TextView jarExecutionProgressLogView;
    private final ArrayList<String> jarExecutionProgressLines = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherTheme.apply(this);
        super.onCreate(savedInstanceState);
        resolveDialogPaletteFromActiveTheme();
        Logging.init(this);
        AppOrientationHelper.applyToActivity(this);
        PathManager.initContextConstants(this);
        binding = ActivityLauncherSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        LauncherTheme.applyRainbowBackgroundIfNeeded(this);
        FullscreenUtils.enableImmersive(this);

        binding.buttonSettingsBack.setOnClickListener(view -> finish());
        applySettingsDefaultsOnce();
        setupSettingsSectionTabs();
        registerSkinPickerLauncher();
        registerMicrosoftSkinPickerLauncher();
        registerOfflineSkinPickerLauncher();
        registerNotificationPermissionLauncher();
        registerMicrophonePermissionLauncher();
        registerMobileGluesFolderPickerLauncher();
        registerDroidBridgeBackupFolderPickerLauncher();
        registerDroidBridgeRestoreZipPickerLauncher();
        registerMouseCursorIconPickerLauncher();
        registerDualScreenBackgroundPickerLauncher();
        registerDualScreenMapFramePickerLauncher();
        registerJarExecutionPickerLauncher();
        registerCustomRuntimeArchivePickerLauncher();
        setupAccountUi();
        setupInstanceSettings();
        setupRendererSettings();
        setupRenderSurfaceSettings();
        setupControllerSettings();
        setupLauncherSettings();
        setupDualScreenSettingsSection();
        setupRecordingSettingsSection();
        setupPrivacyPolicySettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppOrientationHelper.applyToActivity(this);
        FullscreenUtils.enableImmersive(this);
        if (binding != null) {
            RendererInterface selectedRenderer = getSelectedRendererFromSpinner();
            updateMobileGluesConfigSummary(selectedRenderer);
            if (DriverPluginManager.isVulkanZinkRenderer(selectedRenderer)) {
                DriverPluginManager.reload(this);
                updateVulkanDriverSettings(selectedRenderer);
            }
            refreshControllerSettingsValues();
            refreshGameDisplaySettingsValues();
            refreshDualScreenSettingsUi();
            refreshRecordingDualScreenOption();
            updateLauncherThemeSettingsUi();
            updateInstallNotificationSettingsUi();
            updateGridPlayIconModeButtonText();
            updateSimpleVoiceChatPermissionUi();
            refreshAccountUiFromStore();
        }
        checkPendingOptiFineStandaloneInstall();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            FullscreenUtils.enableImmersive(this);
        }
    }

    @Override
    protected void onDestroy() {
        if (runtimeComponentsReinstalling) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (authManager != null && !isChangingConfigurations()) {
            authManager.dispose();
        }
        super.onDestroy();
    }

    private void refreshAccountUiFromStore() {
        if (accountStore == null || binding == null) return;

        try {
            AccountStore.Account account = accountStore.load();
            updateAccountStatus(account);
            updateSkinUi(account);
            updateChangeMicrosoftSkinButtonState(account);
        } catch (Throwable throwable) {
            Logging.e("LauncherSettings", "Unable to refresh account UI", throwable);
        }
    }

    /**
     * Resolves every custom settings-dialog color from the active launcher theme.
     *
     * Light and Dark force their matching resource set. Accent themes (Orange, Red,
     * Yellow, Green, Blue, Indigo, Violet, Pink, Rainbow and Monochrome) follow the
     * current day/night appearance while retaining their own colorAccent.
     */
    private void resolveDialogPaletteFromActiveTheme() {
        // Resolve the surface palette explicitly instead of depending on an OEM Spinner/
        // dialog context to propagate Material day/night colors correctly.  This is
        // especially important on the AYN Thor where the Orange theme can otherwise
        // show a light Settings activity with a stale dark custom-dialog palette.
        String launcherTheme = LauncherPreferences.getLauncherTheme(this);
        boolean useDarkPalette;
        if (LauncherPreferences.LAUNCHER_THEME_DARK.equals(launcherTheme)) {
            useDarkPalette = true;
        } else if (LauncherPreferences.LAUNCHER_THEME_LIGHT.equals(launcherTheme)) {
            useDarkPalette = false;
        } else {
            int nightMask = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            useDarkPalette = nightMask == Configuration.UI_MODE_NIGHT_YES;
        }

        if (useDarkPalette) {
            COLOR_DIALOG_BG = Color.rgb(16, 15, 13);          // #100F0D
            COLOR_CARD_BG = Color.rgb(31, 23, 17);            // #1F1711
            COLOR_CARD_BG_PRESSED = Color.rgb(85, 65, 52);    // #554134
            COLOR_CARD_STROKE = Color.rgb(85, 65, 52);        // #554134
            COLOR_TEXT_PRIMARY = Color.rgb(245, 222, 211);    // #F5DED3
            COLOR_TEXT_SECONDARY = Color.rgb(220, 194, 178);  // #DCC2B2
            COLOR_TEXT_MUTED = Color.rgb(166, 139, 122);      // #A68B7A
        } else {
            COLOR_DIALOG_BG = Color.rgb(255, 248, 243);       // #FFF8F3
            COLOR_CARD_BG = Color.rgb(255, 241, 230);         // #FFF1E6
            COLOR_CARD_BG_PRESSED = Color.rgb(248, 223, 208); // #F8DFD0
            COLOR_CARD_STROKE = Color.rgb(219, 194, 179);     // #DBC2B3
            COLOR_TEXT_PRIMARY = Color.rgb(36, 25, 20);       // #241914
            COLOR_TEXT_SECONDARY = Color.rgb(88, 67, 55);     // #584337
            COLOR_TEXT_MUTED = Color.rgb(141, 115, 100);      // #8D7364
        }

        // Keep the selected theme's accent (Orange/Red/Blue/etc.) independent from
        // the light/dark surface palette.
        android.util.TypedValue accentValue = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.colorAccent, accentValue, true)) {
            if (accentValue.resourceId != 0) {
                COLOR_ACCENT = themedColor(accentValue.resourceId, COLOR_ACCENT);
            } else {
                COLOR_ACCENT = accentValue.data;
            }
        } else {
            COLOR_ACCENT = useDarkPalette ? Color.rgb(255, 157, 46) : Color.rgb(217, 87, 0);
        }
        COLOR_ACCENT_MUTED = COLOR_TEXT_MUTED;

        Logging.i("LauncherSettings", "Dialog palette theme=" + launcherTheme
                + " dark=" + useDarkPalette
                + " bg=#" + String.format(java.util.Locale.ROOT, "%08X", COLOR_DIALOG_BG)
                + " text=#" + String.format(java.util.Locale.ROOT, "%08X", COLOR_TEXT_PRIMARY));
    }

    private int themedColor(int colorRes, int fallback) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return getResources().getColor(colorRes, getTheme());
            }
            //noinspection deprecation
            return getResources().getColor(colorRes);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private void applySettingsDefaultsOnce() {
        android.content.SharedPreferences preferences = getSharedPreferences(SETTINGS_DEFAULTS_PREFS, MODE_PRIVATE);
        if (preferences.getBoolean(SETTINGS_DEFAULTS_APPLIED_KEY, false)) return;

        // Defaults requested for the settings screen:
        // - Shared installs hidden/off by default.
        // - Keep inherited/base Minecraft versions on by default.
        LauncherPreferences.setShowSharedInstalls(this, false);
        LauncherPreferences.setRemoveInheritedVanillaAfterLoaderInstall(this, false);
        preferences.edit().putBoolean(SETTINGS_DEFAULTS_APPLIED_KEY, true).apply();
    }

    private void setupSettingsSectionTabs() {
        binding.settingsSectionTabs.removeAllTabs();
        addSettingsSectionTab(R.string.settings_account_title);
        addSettingsSectionTab(R.string.renderer_settings_title);
        addSettingsSectionTab(R.string.controller_settings_title);
        addSettingsSectionTab(R.string.settings_launcher_title);
        addSettingsSectionTab("Recording Settings");
        addSettingsSectionTab(R.string.settings_instance_title);
        addSettingsSectionTab("Dual-screen Settings");
        addSettingsSectionTab("Privacy Policy");




        binding.settingsSectionTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                scrollToSettingsSection(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                scrollToSettingsSection(tab.getPosition());
            }
        });
    }

    private void addSettingsSectionTab(int titleResId) {
        TabLayout.Tab tab = binding.settingsSectionTabs.newTab();
        tab.setText(titleResId);
        binding.settingsSectionTabs.addTab(tab);
    }

    private void addSettingsSectionTab(@NonNull String title) {
        TabLayout.Tab tab = binding.settingsSectionTabs.newTab();
        tab.setText(title);
        binding.settingsSectionTabs.addTab(tab);
    }

    private void scrollToSettingsSection(int position) {
        View target;

        switch (position) {
            case 0:
                target = binding.cardAccountSettings;
                break;
            case 1:
                target = binding.cardRendererSettings;

                break;
            case 2:
                target = binding.cardControllerSettings;

                break;
            case 3:
                target = binding.cardLauncherSettings;
                break;
            case 4:
                if (cardRecordingSettings == null) return;
                target = cardRecordingSettings;
                break;
            case 5:
                target = binding.cardInstanceSettings;
                break;
            case 6:
                if (cardDualScreenSettings == null) return;
                target = cardDualScreenSettings;
                break;
            case 7:
                target = binding.cardPrivacyPolicySettings;
                break;
            default:
                return;
        }

        binding.settingsScrollView.post(() ->
                binding.settingsScrollView.smoothScrollTo(0, Math.max(0, target.getTop() - dp(8)))
        );
    }

    private void setupAccountUi() {
        try {
            accountStore = new AccountStore(this);
            customSkinStore = new CustomSkinStore(this);
            authManager = new MicrosoftAuthManagerPersonal(this, accountStore);
            authManager.setListener(new MicrosoftAuthManagerPersonal.Listener() {
                @Override
                public void onSignedIn(@NonNull AccountStore.Account account) {
                    MicrosoftAccountRegistry.save(LauncherSettingsActivity.this, account);

                    boolean reopenCapePicker = pendingMicrosoftCapeOpenAfterAuthRefresh;
                    AccountStore.Account uiAccount = restoreActiveAccountAfterCapeAuthRefresh(account);

                    updateAccountStatus(uiAccount);
                    updateSkinUi(uiAccount);
                    updateChangeMicrosoftSkinButtonState(uiAccount);
                    updateChangeMicrosoftCapeButtonState(uiAccount);
                    binding.buttonRefreshMicrosoftSkin.setEnabled(true);
                    binding.buttonAddMicrosoftAccount.setEnabled(true);
                    binding.buttonManageMicrosoftAccounts.setEnabled(true);

                    if (reopenCapePicker) {
                        // updateSkinUi() starts exactly one profile request using the newly issued
                        // Minecraft token. refreshActiveCapeIntoPlayerModel() will reuse that response
                        // to reopen the cape picker, avoiding a second immediate /minecraft/profile GET.
                        binding.buttonChangeMicrosoftCape.setEnabled(false);
                        binding.buttonChangeMicrosoftCape.setText(R.string.microsoft_cape_loading_button);
                    }
                }

                @Override
                public void onError(@NonNull String message) {
                    boolean capeRefreshFailed = pendingMicrosoftCapeOpenAfterAuthRefresh;
                    AccountStore.Account uiAccount = restoreActiveAccountAfterCapeAuthRefresh(null);
                    if (capeRefreshFailed) {
                        pendingMicrosoftCapeOpenAfterAuthRefresh = false;
                        binding.buttonChangeMicrosoftCape.setText(R.string.microsoft_cape_change_button);
                        updateChangeMicrosoftCapeButtonState(uiAccount);
                    }

                    binding.textAccountStatus.setText(message);
                    binding.buttonRefreshMicrosoftSkin.setEnabled(true);
                    binding.buttonAddMicrosoftAccount.setEnabled(true);
                    binding.buttonManageMicrosoftAccounts.setEnabled(true);
                    Toast.makeText(LauncherSettingsActivity.this, message, Toast.LENGTH_LONG).show();
                }
            });

            AccountStore.Account account = accountStore.load();
            updateAccountStatus(account);
            updateSkinUi(account);
        } catch (Throwable throwable) {
            Logging.e("LauncherSettings", "Microsoft account UI initialization failed", throwable);
            binding.textAccountStatus.setText(R.string.status_signed_out);
            binding.buttonSignIn.setEnabled(false);
            binding.buttonAddMicrosoftAccount.setEnabled(false);
            binding.buttonManageMicrosoftAccounts.setEnabled(false);
            binding.buttonSignOut.setEnabled(false);
            binding.buttonManageOfflineAccounts.setEnabled(false);
            binding.buttonUseMicrosoftAccount.setEnabled(false);
            binding.buttonRefreshMicrosoftSkin.setEnabled(false);
            binding.buttonChangeMicrosoftCape.setEnabled(false);
        }

        setupChangeMicrosoftSkinButton();
        setupChangeMicrosoftCapeButton();

        binding.buttonSignIn.setOnClickListener(view -> startMicrosoftSignInFlow(false));
        binding.buttonAddMicrosoftAccount.setOnClickListener(view -> startMicrosoftSignInFlow(true));
        binding.buttonManageMicrosoftAccounts.setOnClickListener(view -> showMicrosoftAccountsDialog());

        binding.buttonSignOut.setOnClickListener(view -> showSignOutConfirmationDialog());

        binding.buttonUseMicrosoftAccount.setOnClickListener(view -> useRememberedMicrosoftAccount());
        binding.buttonManageOfflineAccounts.setOnClickListener(view -> showOfflineAccountsDialog());
        binding.buttonRefreshMicrosoftSkin.setOnClickListener(view -> refreshMicrosoftAccountAndSkin(true));
        updateChangeMicrosoftSkinButtonState(accountStore != null ? accountStore.load() : null);
        updateChangeMicrosoftCapeButtonState(accountStore != null ? accountStore.load() : null);
    }


    private void startMicrosoftSignInFlow(boolean addingAnotherAccount) {
        if (authManager == null) return;
        if (!MicrosoftAuthConfigPersonal.isConfigured()) {
            binding.textAccountStatus.setText(R.string.msg_configure_client_id);
            return;
        }

        binding.buttonSignIn.setEnabled(false);
        binding.buttonAddMicrosoftAccount.setEnabled(false);
        binding.buttonManageMicrosoftAccounts.setEnabled(false);
        binding.textAccountStatus.setText(addingAnotherAccount
                ? "Adding another Microsoft account..."
                : getString(R.string.status_signed_out));
        authManager.signIn();
    }

    private void setupChangeMicrosoftSkinButton() {
        if (binding == null) return;

        binding.buttonChangeMicrosoftSkin.setOnClickListener(view -> showChangeMicrosoftSkinDialog());
    }

    private void setupChangeMicrosoftCapeButton() {
        if (binding == null) return;

        binding.buttonChangeMicrosoftCape.setOnClickListener(view -> showChangeMicrosoftCapeDialog());
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

        authManager.signOut();

        AccountStore.Account account = accountStore.load();
        updateAccountStatus(account);
        updateSkinUi(account);

        if (binding.buttonRefreshMicrosoftSkin != null) {
            binding.buttonRefreshMicrosoftSkin.setEnabled(false);
        }
        if (binding.buttonAddMicrosoftAccount != null) {
            binding.buttonAddMicrosoftAccount.setEnabled(true);
        }
        if (binding.buttonManageMicrosoftAccounts != null) {
            binding.buttonManageMicrosoftAccounts.setEnabled(MicrosoftAccountRegistry.hasAny(this));
        }
        updateChangeMicrosoftSkinButtonState(account);
        updateChangeMicrosoftCapeButtonState(account);

        Toast.makeText(this, R.string.msg_sign_out_success, Toast.LENGTH_SHORT).show();
    }

    private void registerSkinPickerLauncher() {
        customSkinPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> { }
        );
    }

    private void registerMicrosoftSkinPickerLauncher() {
        microsoftSkinPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                    Uri uri = result.getData().getData();
                    if (uri == null) return;
                    prepareMicrosoftSkinUpload(uri);
                }
        );
    }

    private void registerOfflineSkinPickerLauncher() {
        offlineSkinPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                    Uri uri = result.getData().getData();
                    if (uri == null) return;
                    pendingOfflineSkinUri = uri;
                    if (pendingOfflineSkinPreview != null) {
                        updatePendingOfflineSkinPreview(uri);
                    }
                    if (pendingOfflineSkinLabel != null) {
                        pendingOfflineSkinLabel.setText(R.string.offline_account_skin_selected);
                    }
                }
        );
    }

    private void openCustomSkinPicker() {
        // Kept for old callers. Custom skins are now managed per offline profile.
        showOfflineAccountsDialog();
    }

    private void handleCustomSkinResult(@NonNull Uri uri) {
        // Kept for old callers. Custom skins are now managed per offline profile.
        pendingOfflineSkinUri = uri;
    }

    private void updateSkinUi(@Nullable AccountStore.Account account) {
        boolean offlineUnlocked = accountStore != null && accountStore.canUseOfflineMode();
        boolean activeOfflineSkin = account != null && account.isOfflineAccount() && account.hasOfflineSkin();
        boolean microsoftSkin = account != null && account.isMicrosoftAccount() && !isNullOrBlank(account.skinUrl);
        boolean rememberedMicrosoft = accountStore != null && (accountStore.hasStoredMicrosoftAccount() || MicrosoftAccountRegistry.hasAny(this));

        if (activeOfflineSkin) {
            binding.textSkinStatus.setText(getString(R.string.offline_account_skin_active, account.getBestDisplayName()));
        } else if (microsoftSkin) {
            binding.textSkinStatus.setText(R.string.custom_skin_status_microsoft);
        } else if (rememberedMicrosoft) {
            binding.textSkinStatus.setText(R.string.microsoft_skin_needs_refresh);
        } else if (!offlineUnlocked) {
            binding.textSkinStatus.setText(R.string.custom_skin_status_locked);
        } else {
            binding.textSkinStatus.setText(R.string.custom_skin_status_none);
        }

        updatePlayerModelPreview(account);
        updateChangeMicrosoftSkinButtonState(account);
        updateChangeMicrosoftCapeButtonState(account);
    }

    private void setupInstanceSettings() {
        binding.textFolder.setText(getString(R.string.launcher_folder_value, PathManager.DIR_MINECRAFT_HOME));
        updateDroidBridgeBackupSummary();
        binding.buttonBackupDroidBridgeData.setOnClickListener(view -> showDroidBridgeBackupDialog());
        binding.buttonRestoreDroidBridgeData.setOnClickListener(view -> showDroidBridgeRestoreDialog());

        boolean showSharedInstalls = LauncherPreferences.isShowSharedInstalls(this);
        binding.switchShowSharedInstalls.setChecked(showSharedInstalls);
        updateSharedInstallsSwitchText(showSharedInstalls);
        binding.switchShowSharedInstalls.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherPreferences.setShowSharedInstalls(this, isChecked);
            updateSharedInstallsSwitchText(isChecked);
        });

        // Checked = remove the inherited/base Minecraft version after the loader profile is flattened.
        // Unchecked = keep/install the inherited/base version.
        boolean removeInheritedVanilla = LauncherPreferences.isRemoveInheritedVanillaAfterLoaderInstall(this);
        binding.switchRemoveInheritedVanilla.setChecked(removeInheritedVanilla);
        updateRemoveInheritedVanillaSwitchText(removeInheritedVanilla);
        binding.switchRemoveInheritedVanilla.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherPreferences.setRemoveInheritedVanillaAfterLoaderInstall(this, isChecked);
            updateRemoveInheritedVanillaSwitchText(isChecked);
        });

        updateGridPlayIconModeButtonText();
        binding.buttonGridPlayIconMode.setOnClickListener(view -> showGridPlayIconModeDialog());
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupRendererSettings() {
        binding.spinnerRenderer.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                if (!rendererSpinnerReady) return;
                applyRendererSelection(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        binding.spinnerRenderer.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                showRendererPickerDialog();
            }
            return true;
        });

        binding.buttonDownloadRenderers.setOnClickListener(view -> showDownloadRenderersDialog());
        setupRendererVersionRulesEntry();

        binding.spinnerVulkanDriver.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                if (!driverSpinnerReady) return;
                applyVulkanDriverSelection(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        binding.spinnerVulkanDriver.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                showVulkanDriverPickerDialog();
            }
            return true;
        });

        binding.buttonImportRendererPlugin.setOnClickListener(view -> openSelectedRendererPluginSettings());
        binding.buttonGrantRendererStorageAccess.setOnClickListener(view -> openDroidBridgeStorageAccessSettings());
        binding.buttonClearRendererPluginCache.setOnClickListener(view -> clearRendererPluginCache());
        binding.buttonRefreshRenderers.setOnClickListener(view -> {
            Renderers.reload(this);
            DriverPluginManager.reload(this);
            refreshRendererList();
        });

        boolean useSystemVulkanDriver = LauncherPreferences.isUseSystemVulkanDriver(this);
        binding.switchUseSystemVulkanDriver.setChecked(useSystemVulkanDriver);
        updateSystemVulkanDriverSwitchText(useSystemVulkanDriver);
        binding.switchUseSystemVulkanDriver.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherPreferences.setSystemVulkanMode(this, isChecked);
            if (isChecked && binding.switchUseOpenGlFor26Plus.isChecked()) {
                binding.switchUseOpenGlFor26Plus.setChecked(false);
            }
            updateSystemVulkanDriverSwitchText(isChecked);
            updateVulkanDriverSettings(getSelectedRendererFromSpinner());
        });

        boolean useOpenGl26Plus = LauncherPreferences.isUseOpenGlForMinecraft26Plus(this);
        if (useSystemVulkanDriver && useOpenGl26Plus) {
            LauncherPreferences.setSystemVulkanMode(this, true);
            useOpenGl26Plus = false;
            Logging.i("LauncherSettings",
                    "Repaired conflicting Graphics API toggles: kept System Vulkan");
        }
        binding.switchUseOpenGlFor26Plus.setChecked(useOpenGl26Plus);
        updateOpenGl26PlusSwitchText(useOpenGl26Plus);
        binding.switchUseOpenGlFor26Plus.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherPreferences.setUseOpenGlForMinecraft26Plus(this, isChecked);
            if (isChecked && binding.switchUseSystemVulkanDriver.isChecked()) {
                binding.switchUseSystemVulkanDriver.setChecked(false);
            }
            updateOpenGl26PlusSwitchText(isChecked);
            updateVulkanDriverSettings(getSelectedRendererFromSpinner());
        });

        installVulkanVsyncToggle();

        Renderers.reload(this);
        refreshRendererList();
    }


    private void setupRendererVersionRulesEntry() {
        if (buttonRendererVersionRules != null) {
            updateRendererVersionRulesSummary();
            return;
        }

        View parentView = (View) binding.layoutRendererPickerRow.getParent();
        if (!(parentView instanceof LinearLayout)) return;
        LinearLayout parent = (LinearLayout) parentView;
        int insertIndex = parent.indexOfChild(binding.layoutRendererPickerRow) + 1;

        MaterialButton button = new MaterialButton(this);
        button.setText("Version-specific renderer defaults");
        button.setAllCaps(false);
        button.setOnClickListener(view -> showRendererVersionRulesDialog());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        buttonParams.setMargins(0, dp(10), 0, 0);
        parent.addView(button, insertIndex++, buttonParams);
        buttonRendererVersionRules = button;

        TextView summary = new TextView(this);
        summary.setTextSize(12.5f);
        summary.setTextColor(COLOR_TEXT_SECONDARY);
        summary.setPadding(dp(2), dp(4), dp(2), 0);
        parent.addView(summary, insertIndex, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        textRendererVersionRulesSummary = summary;
        updateRendererVersionRulesSummary();
    }

    private void updateRendererVersionRulesSummary() {
        TextView summary = textRendererVersionRulesSummary;
        if (summary == null) return;
        int active = RendererVersionRules.countEnabledRules(this);
        if (active <= 0) {
            summary.setText("Off. Minecraft uses the normal global renderer unless the instance has its own renderer override.");
        } else {
            summary.setText(active + (active == 1 ? " version rule active. " : " version rules active. ")
                    + "Per-instance renderer settings still take priority.");
        }
    }

    private static final class RendererRuleEditor {
        final CheckBox enabled;
        final android.widget.Spinner minimum;
        final android.widget.Spinner maximum;
        final android.widget.Spinner renderer;
        final ArrayList<String> rendererIds;

        RendererRuleEditor(
                @NonNull CheckBox enabled,
                @NonNull android.widget.Spinner minimum,
                @NonNull android.widget.Spinner maximum,
                @NonNull android.widget.Spinner renderer,
                @NonNull ArrayList<String> rendererIds
        ) {
            this.enabled = enabled;
            this.minimum = minimum;
            this.maximum = maximum;
            this.renderer = renderer;
            this.rendererIds = rendererIds;
        }
    }

    private void showRendererVersionRulesDialog() {
        // Re-resolve on every open so a recently changed launcher theme cannot leave
        // this dialog using stale colors.
        resolveDialogPaletteFromActiveTheme();
        Renderers.reload(this);
        List<RendererInterface> rendererChoices = Renderers.getCompatibleRenderers(this);
        List<RendererVersionRules.Rule> savedRules = RendererVersionRules.loadRules(this);
        List<String> minimumChoices = RendererVersionRules.getStableReleaseVersions();
        List<String> maximumChoices = RendererVersionRules.getMaximumVersionChoices();

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(COLOR_DIALOG_BG);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setScrollbarFadingEnabled(false);

        LinearLayout root = createStyledDialogRoot(
                scrollView,
                "Version-specific renderer defaults",
                "Configure up to three stable Minecraft release ranges. Rules are checked from top to bottom. "
                        + "Per-instance renderer settings are never replaced by these defaults."
        );

        ArrayList<RendererRuleEditor> editors = new ArrayList<>();
        for (int i = 0; i < RendererVersionRules.getMaxRules(); i++) {
            RendererVersionRules.Rule rule = i < savedRules.size()
                    ? savedRules.get(i).copy()
                    : new RendererVersionRules.Rule();
            LinearLayout card = addStyledDialogCard(root);
            addStyledDialogCardTitle(card, "Rule " + (i + 1));

            CheckBox enabled = new CheckBox(this);
            enabled.setText("Enable this version range");
            enabled.setTextColor(COLOR_TEXT_PRIMARY);
            enabled.setChecked(rule.enabled);
            card.addView(enabled, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));

            addRendererRuleFieldLabel(card, "Minimum Minecraft version");
            android.widget.Spinner minimum = createRendererRuleSpinner(minimumChoices, false);
            minimum.setSelection(indexOfRuleChoice(minimumChoices, rule.minimumVersion, 0), false);
            card.addView(minimum, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));

            addRendererRuleFieldLabel(card, "Maximum Minecraft version");
            android.widget.Spinner maximum = createRendererRuleSpinner(maximumChoices, true);
            maximum.setSelection(indexOfRuleChoice(
                    maximumChoices,
                    rule.maximumVersion,
                    maximumChoices.size() - 1
            ), false);
            card.addView(maximum, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));

            addRendererRuleFieldLabel(card, "Renderer");
            ArrayList<String> rendererIds = new ArrayList<>();
            ArrayList<String> rendererNames = new ArrayList<>();
            for (RendererInterface renderer : rendererChoices) {
                rendererIds.add(renderer.getUniqueIdentifier());
                rendererNames.add(renderer.getRendererName()
                        + (renderer.isExternalPlugin() ? "  •  Plugin" : ""));
            }
            int rendererIndex = rendererIds.indexOf(rule.rendererIdentifier);
            if (!rule.rendererIdentifier.trim().isEmpty() && rendererIndex < 0) {
                rendererIds.add(0, rule.rendererIdentifier);
                rendererNames.add(0, "Unavailable saved renderer");
                rendererIndex = 0;
            }
            if (rendererNames.isEmpty()) {
                rendererIds.add("");
                rendererNames.add("No compatible renderers available");
                rendererIndex = 0;
            }
            android.widget.Spinner rendererSpinner = createTextSpinner(rendererNames);
            rendererSpinner.setSelection(Math.max(0, rendererIndex), false);
            card.addView(rendererSpinner, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));

            RendererRuleEditor editor = new RendererRuleEditor(
                    enabled,
                    minimum,
                    maximum,
                    rendererSpinner,
                    rendererIds
            );
            editors.add(editor);
            enabled.setOnCheckedChangeListener((buttonView, isChecked) -> setRendererRuleEditorEnabled(editor, isChecked));
            setRendererRuleEditorEnabled(editor, rule.enabled);
        }

        LinearLayout noteCard = addStyledDialogCard(root);
        addStyledDialogCardTitle(noteCard, "How selection works");
        addStyledDialogInfoText(noteCard,
                "The first enabled range that matches the Minecraft release and has an available renderer is used. "
                        + "If no rule matches, DroidBridge uses the normal global renderer. "
                        + "'Latest release' has no fixed upper limit, so it also covers future stable releases. "
                        + "Snapshots, pre-releases, release candidates, alpha and beta versions are ignored by this feature.");

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Save", null)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            styleLauncherDialogChrome(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                ArrayList<RendererVersionRules.Rule> rules = new ArrayList<>();
                for (int i = 0; i < editors.size(); i++) {
                    RendererRuleEditor editor = editors.get(i);
                    String minimum = minimumChoices.get(editor.minimum.getSelectedItemPosition());
                    String maximum = maximumChoices.get(editor.maximum.getSelectedItemPosition());
                    int rendererPosition = editor.renderer.getSelectedItemPosition();
                    String rendererId = rendererPosition >= 0 && rendererPosition < editor.rendererIds.size()
                            ? editor.rendererIds.get(rendererPosition)
                            : "";

                    if (editor.enabled.isChecked()) {
                        if (!RendererVersionRules.isValidRange(minimum, maximum)) {
                            Toast.makeText(this,
                                    "Rule " + (i + 1) + ": minimum version must not be newer than maximum version.",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (rendererId.trim().isEmpty()) {
                            Toast.makeText(this,
                                    "Rule " + (i + 1) + ": choose an available renderer.",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                    }

                    rules.add(new RendererVersionRules.Rule(
                            editor.enabled.isChecked(),
                            minimum,
                            maximum,
                            rendererId
                    ));
                }

                RendererVersionRules.saveRules(this, rules);
                updateRendererVersionRulesSummary();
                Toast.makeText(this, "Version-specific renderer defaults saved.", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void addRendererRuleFieldLabel(@NonNull LinearLayout parent, @NonNull String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(COLOR_TEXT_SECONDARY);
        label.setTextSize(13f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setPadding(0, dp(10), 0, dp(3));
        parent.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
    }

    @NonNull
    private android.widget.Spinner createRendererRuleSpinner(
            @NonNull List<String> values,
            boolean formatLatest
    ) {
        ArrayList<String> labels = new ArrayList<>();
        for (String value : values) {
            labels.add(formatLatest ? RendererVersionRules.getVersionChoiceLabel(value) : value);
        }
        return createTextSpinner(labels);
    }

    @NonNull
    private android.widget.Spinner createTextSpinner(@NonNull List<String> labels) {
        android.widget.Spinner spinner = new android.widget.Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, R.layout.item_droidbridge_spinner, labels) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                applyRendererRuleSpinnerColors(view, false);
                return view;
            }

            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                applyRendererRuleSpinnerColors(view, true);
                return view;
            }
        };
        adapter.setDropDownViewResource(R.layout.item_droidbridge_spinner_dropdown);
        spinner.setAdapter(adapter);
        return spinner;
    }

    private void applyRendererRuleSpinnerColors(@NonNull View view, boolean dropdown) {
        TextView text = view instanceof TextView
                ? (TextView) view
                : view.findViewById(android.R.id.text1);
        if (text != null) {
            // Programmatic color is intentional: a few OEM Spinner implementations
            // ignore Material colorOnSurface when a custom AlertDialog is used.
            text.setTextColor(COLOR_TEXT_PRIMARY);
            text.setHintTextColor(COLOR_TEXT_MUTED);
        }
        if (dropdown) {
            view.setBackground(roundedDrawable(COLOR_CARD_BG, COLOR_CARD_STROKE, 8));
        } else {
            view.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    private void setRendererRuleEditorEnabled(@NonNull RendererRuleEditor editor, boolean enabled) {
        editor.minimum.setEnabled(enabled);
        editor.maximum.setEnabled(enabled);
        editor.renderer.setEnabled(enabled);
        editor.minimum.setAlpha(enabled ? 1f : 0.55f);
        editor.maximum.setAlpha(enabled ? 1f : 0.55f);
        editor.renderer.setAlpha(enabled ? 1f : 0.55f);
    }

    private static int indexOfRuleChoice(
            @NonNull List<String> choices,
            @Nullable String value,
            int fallback
    ) {
        int index = value == null ? -1 : choices.indexOf(value);
        if (index >= 0) return index;
        if (choices.isEmpty()) return 0;
        return Math.max(0, Math.min(fallback, choices.size() - 1));
    }

    private void showDownloadRenderersDialog() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(COLOR_DIALOG_BG);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setScrollbarFadingEnabled(false);

        LinearLayout root = createStyledDialogRoot(
                scrollView,
                "Download renderers",
                "DroidBridge Launcher can use extra renderer plugins from compatible renderer packages."
        );

        LinearLayout card = addStyledDialogCard(root);
        addStyledDialogCardTitle(card, "Open renderer download site");
        addStyledDialogInfoText(card,
                "You will be brought to a download renderer site to get other renderers for DroidBridge Launcher. "
                        + "Only install renderer APKs from sources you trust.");

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Open site", null)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            styleLauncherDialogChrome(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                openRendererDownloadSite();
                dialog.dismiss();
            });
        });

        dialog.show();
    }

    private void openRendererDownloadSite() {
        if (isNullOrBlank(RENDERER_DOWNLOAD_URL)) {
            Toast.makeText(this, "Renderer download site is not configured.", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(RENDERER_DOWNLOAD_URL));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(intent);
        } catch (Throwable throwable) {
            Toast.makeText(this, "Unable to open renderer download site.", Toast.LENGTH_LONG).show();
        }
    }

    private void showRendererPickerDialog() {
        if (availableRenderers.isEmpty()) {
            Toast.makeText(this, R.string.renderer_none_found, Toast.LENGTH_SHORT).show();
            return;
        }

        ArrayList<String> names = new ArrayList<>();
        for (RendererInterface renderer : availableRenderers) {
            names.add(renderer.getRendererName() + (renderer.isExternalPlugin() ? "  •  Plugin" : ""));
        }

        showStyledSingleChoiceDialog(
                "Renderer",
                "Choose the renderer Minecraft will use. Plugin renderers are marked in the list.",
                names,
                binding.spinnerRenderer.getSelectedItemPosition(),
                position -> {
                    binding.spinnerRenderer.setSelection(position, false);
                    applyRendererSelection(position);
                }
        );
    }

    private void showVulkanDriverPickerDialog() {
        if (availableDrivers.isEmpty()) {
            Toast.makeText(this, "No Vulkan drivers found.", Toast.LENGTH_SHORT).show();
            return;
        }

        ArrayList<String> names = new ArrayList<>();
        for (Driver driver : availableDrivers) {
            names.add(driver.getName());
        }

        showStyledSingleChoiceDialog(
                "Vulkan driver",
                "Choose the driver used when Vulkan/Zink is selected and the system Vulkan driver switch is off.",
                names,
                binding.spinnerVulkanDriver.getSelectedItemPosition(),
                position -> {
                    binding.spinnerVulkanDriver.setSelection(position, false);
                    applyVulkanDriverSelection(position);
                }
        );
    }

    private void applyRendererSelection(int position) {
        if (position < 0 || position >= availableRenderers.size()) return;
        RendererInterface renderer = availableRenderers.get(position);
        LauncherPreferences.setSelectedRendererIdentifier(this, renderer.getUniqueIdentifier());
        Renderers.setCurrentRenderer(this, renderer.getUniqueIdentifier(), true);
        updateRendererDescription(renderer);
        updateRendererPluginButtons(renderer);
        updateVulkanDriverSettings(renderer);
    }

    private void applyVulkanDriverSelection(int position) {
        if (position < 0 || position >= availableDrivers.size()) return;
        Driver driver = availableDrivers.get(position);
        LauncherPreferences.setSelectedVulkanDriverName(this, driver.getName());
        updateVulkanDriverDescription(driver);
    }

    private void refreshRendererList() {
        rendererSpinnerReady = false;
        availableRenderers.clear();
        availableRenderers.addAll(Renderers.getCompatibleRenderers(this));

        ArrayList<String> names = new ArrayList<>();
        for (RendererInterface renderer : availableRenderers) {
            names.add(renderer.getRendererName() + (renderer.isExternalPlugin() ? "  •  Plugin" : ""));
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.item_droidbridge_spinner, names);
        adapter.setDropDownViewResource(R.layout.item_droidbridge_spinner_dropdown);
        binding.spinnerRenderer.setAdapter(adapter);

        if (availableRenderers.isEmpty()) {
            //binding.textRendererDescription.setText(R.string.renderer_none_found);
            updateRendererPluginButtons(null);
            updateMobileGluesConfigSummary(null);
            updateVulkanDriverSettings(null);
            return;
        }

        int selectedIndex = Renderers.indexOfRenderer(availableRenderers, LauncherPreferences.getSelectedRendererIdentifier(this));
        binding.spinnerRenderer.setSelection(selectedIndex, false);
        updateRendererDescription(availableRenderers.get(selectedIndex));
        updateRendererPluginButtons(availableRenderers.get(selectedIndex));
        updateVulkanDriverSettings(availableRenderers.get(selectedIndex));
        rendererSpinnerReady = true;
    }

    private void updateVulkanDriverSettings(@Nullable RendererInterface renderer) {
        boolean show = DriverPluginManager.isVulkanZinkRenderer(renderer)
                && !LauncherPreferences.isUseSystemVulkanDriver(this);
        binding.layoutVulkanDriverSettings.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            driverSpinnerReady = false;
            availableDrivers.clear();
            binding.spinnerVulkanDriver.setAdapter(null);
            binding.textVulkanDriverDescription.setText("");
            return;
        }

        refreshVulkanDriverList();
    }

    private void refreshVulkanDriverList() {
        driverSpinnerReady = false;
        availableDrivers.clear();
        availableDrivers.addAll(DriverPluginManager.getDrivers(this));

        ArrayList<String> names = new ArrayList<>();
        for (Driver driver : availableDrivers) {
            names.add(driver.getName());
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.item_droidbridge_spinner, names);
        adapter.setDropDownViewResource(R.layout.item_droidbridge_spinner_dropdown);
        binding.spinnerVulkanDriver.setAdapter(adapter);

        if (availableDrivers.isEmpty()) {
            binding.textVulkanDriverDescription.setText("");
            return;
        }

        int selectedIndex = DriverPluginManager.indexOfDriver(this, LauncherPreferences.getSelectedVulkanDriverName(this));
        binding.spinnerVulkanDriver.setSelection(selectedIndex, false);
        updateVulkanDriverDescription(availableDrivers.get(selectedIndex));
        driverSpinnerReady = true;
    }

    private void updateVulkanDriverDescription(@NonNull Driver driver) {
        String description = driver.getDescription();
        if (description == null || description.trim().isEmpty()) {
            description = "Uses the selected Vulkan driver for Vulkan/Zink rendering.";
        }

        binding.textVulkanDriverDescription.setText(getString(
                R.string.vulkan_driver_description_value,
                driver.getName(),
                description
        ));
    }

    private void updateRendererDescription(@NonNull RendererInterface renderer) {
        //binding.textRendererDescription.setText(buildFriendlyRendererDescription(renderer));
        updateMobileGluesConfigSummary(renderer);
    }

    @NonNull
    private String buildFriendlyRendererDescription(@NonNull RendererInterface renderer) {
        String name = renderer.getRendererName();
        String lookup = (
                renderer.getRendererName() + " "
                        + renderer.getRendererId() + " "
                        + renderer.getUniqueIdentifier() + " "
                        + renderer.getRendererLibrary()
        ).toLowerCase();

        if (lookup.contains("mobileglues") || lookup.contains("mobile glues")) {
            return name + "\nRecommended for most Android devices. Good balance of compatibility and performance for modern Minecraft versions.";
        }

        if (lookup.contains("vulkan") || lookup.contains("zink")) {
            return name + "\nUses Vulkan/Zink rendering. Best for devices with strong Vulkan support, and useful for newer Minecraft versions or Vulkan-focused testing.";
        }

        if (lookup.contains("gl4es") || lookup.contains("opengles")) {
            return name + "\nClassic OpenGL ES compatibility renderer. Useful for older Minecraft versions or devices that do not work well with Vulkan.";
        }

        if (lookup.contains("virgl")) {
            return name + "\nCompatibility renderer for specific devices and setups. Try this if the recommended renderer does not work correctly.";
        }

        String description = renderer.getRendererDescription();
        if (description != null && !description.trim().isEmpty()) {
            return name + "\n" + description.trim();
        }

        return name + "\nRuns Minecraft using this renderer.";
    }

    private void updateMobileGluesConfigSummary(@Nullable RendererInterface renderer) {
        boolean mobileGlues = MobileGluesConfigHelper.isMobileGluesRenderer(renderer);

        if (!mobileGlues) {
            binding.textRendererPluginConfig.setText("");
            binding.textRendererPluginConfig.setVisibility(View.GONE);
            binding.buttonGrantRendererStorageAccess.setVisibility(View.GONE);
            return;
        }

        binding.textRendererPluginConfig.setText(MobileGluesConfigHelper.buildSettingsSummary(this, renderer));
        binding.textRendererPluginConfig.setVisibility(View.VISIBLE);

        boolean hasAccess = MobileGluesConfigHelper.hasStorageAccess(this);
        binding.buttonGrantRendererStorageAccess.setVisibility(View.VISIBLE);
        binding.buttonGrantRendererStorageAccess.setEnabled(true);
        binding.buttonGrantRendererStorageAccess.setText(hasAccess
                ? "Choose MobileGlues folder again"
                : "Choose MobileGlues folder");
    }

    private void updateRendererPluginButtons(@Nullable RendererInterface renderer) {
        boolean externalPlugin = renderer != null && renderer.isExternalPlugin();
        binding.buttonImportRendererPlugin.setEnabled(externalPlugin);
        binding.buttonClearRendererPluginCache.setEnabled(RendererPluginManager.hasImportedOrCachedRendererPlugins(this));
    }

    private void openSelectedRendererPluginSettings() {
        RendererInterface renderer = getSelectedRendererFromSpinner();
        if (renderer == null || !renderer.isExternalPlugin()) {
            return;
        }

        RendererPluginManager.openPluginApp(this, renderer);
    }

    private void openDroidBridgeStorageAccessSettings() {
        if (mobileGluesFolderPickerLauncher == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        MobileGluesConfigHelper.addMgPickerHints(intent);
        mobileGluesFolderPickerLauncher.launch(intent);
    }

    @Nullable
    private RendererInterface getSelectedRendererFromSpinner() {
        int position = binding.spinnerRenderer.getSelectedItemPosition();
        if (position < 0 || position >= availableRenderers.size()) return null;
        return availableRenderers.get(position);
    }

    private void clearRendererPluginCache() {
        RendererPluginManager.clearImportedAndCachedRendererPlugins(this);
        Renderers.reload(this);
        refreshRendererList();
    }

    private void setupRenderSurfaceSettings() {
        boolean useNativeSurfaceView = LauncherPreferences.isUseNativeSurfaceView(this);
        binding.switchUseNativeSurface.setChecked(useNativeSurfaceView);
        updateRenderSurfaceSwitchText(useNativeSurfaceView);
        binding.switchUseNativeSurface.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherPreferences.setUseNativeSurfaceView(this, isChecked);
            updateRenderSurfaceSwitchText(isChecked);
        });

        setupSustainedPerformanceSettings();
        setupGameDisplaySettings();
    }

    private void setupSustainedPerformanceSettings() {
        boolean supported = isSustainedPerformanceModeSupported();
        boolean enabled = supported && LauncherPreferences.isSustainedPerformanceEnabled(this);

        binding.switchSustainedPerformance.setOnCheckedChangeListener(null);
        binding.switchSustainedPerformance.setEnabled(supported);
        binding.switchSustainedPerformance.setChecked(enabled);
        binding.textSustainedPerformanceSummary.setText(supported
                ? R.string.settings_sustained_performance_summary
                : R.string.settings_sustained_performance_unsupported);

        binding.switchSustainedPerformance.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!supported) {
                buttonView.setChecked(false);
                return;
            }
            LauncherPreferences.setSustainedPerformanceEnabled(this, isChecked);
        });
    }

    private boolean isSustainedPerformanceModeSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            return powerManager != null && powerManager.isSustainedPerformanceModeSupported();
        } catch (Throwable throwable) {
            Logging.e("LauncherSettings", "Unable to query sustained performance support", throwable);
            return false;
        }
    }

    private void setupGameDisplaySettings() {
        int currentScale = LauncherPreferences.getGameResolutionScalePercent(this);
        binding.sliderGameResolutionScale.setMax(
                LauncherPreferences.MAX_GAME_RESOLUTION_SCALE_PERCENT
                        - LauncherPreferences.MIN_GAME_RESOLUTION_SCALE_PERCENT
        );
        updateResolutionScaleUi(currentScale);

        binding.sliderGameResolutionScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int percent = LauncherPreferences.MIN_GAME_RESOLUTION_SCALE_PERCENT + progress;
                updateResolutionScaleText(percent);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int percent = LauncherPreferences.MIN_GAME_RESOLUTION_SCALE_PERCENT + seekBar.getProgress();
                percent = LauncherPreferences.clampGameResolutionScalePercent(percent);
                LauncherPreferences.setGameResolutionScalePercent(LauncherSettingsActivity.this, percent);
                updateResolutionScaleUi(percent);
            }
        });

        binding.textGameResolutionScale.setOnClickListener(view -> openResolutionScaleInputDialog());

        setupGameResolutionSelector();
        bindForceFullscreenSwitch();
        bindIgnoreDisplayCutoutSwitch();
        bindAvoidRoundedCornersSwitch();
    }

    private void setupGameResolutionSelector() {
        List<String> labels = gameResolutionLabels();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                R.layout.item_droidbridge_spinner,
                labels
        );
        adapter.setDropDownViewResource(R.layout.item_droidbridge_spinner_dropdown);
        binding.spinnerGameResolution.setAdapter(adapter);
        binding.spinnerGameResolution.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                showGameResolutionPickerDialog();
            }
            return true;
        });
        updateGameResolutionUi();
    }

    @NonNull
    private List<String> gameResolutionLabels() {
        return Arrays.asList(
                getString(R.string.settings_renderer_game_resolution_native),
                getString(R.string.settings_renderer_game_resolution_1080p),
                getString(R.string.settings_renderer_game_resolution_4_3),
                getString(R.string.settings_renderer_game_resolution_mcsx),
                getString(R.string.settings_renderer_game_resolution_custom)
        );
    }

    private void showGameResolutionPickerDialog() {
        GameResolutionSettings.Profile profile = GameResolutionSettings.getProfile(this);
        showStyledSingleChoiceDialog(
                getString(R.string.settings_renderer_game_resolution_title),
                getString(R.string.settings_renderer_game_resolution_picker_summary),
                gameResolutionLabels(),
                gameResolutionModeIndex(profile.mode),
                this::handleGameResolutionChoice
        );
    }

    private void handleGameResolutionChoice(int position) {
        if (position == 0) {
            GameResolutionSettings.setMode(this, GameResolutionSettings.MODE_NATIVE);
            updateGameResolutionUi();
            return;
        }

        if (position == 4) {
            showCustomGameResolutionDialog();
            return;
        }

        String mode;
        if (position == 1) {
            mode = GameResolutionSettings.MODE_1920_1080;
        } else if (position == 2) {
            mode = GameResolutionSettings.MODE_BEST_4_3;
        } else {
            mode = GameResolutionSettings.MODE_MCSX;
        }
        showExperimentalResolutionConfirmation(() -> {
            GameResolutionSettings.setMode(this, mode);
            updateGameResolutionUi();
        });
    }

    private void showExperimentalResolutionConfirmation(@NonNull Runnable onConfirmed) {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(COLOR_DIALOG_BG);

        LinearLayout root = createStyledDialogRoot(
                scrollView,
                getString(R.string.settings_renderer_game_resolution_confirm_title),
                getString(R.string.settings_renderer_game_resolution_confirm_message)
        );
        LinearLayout card = addStyledDialogCard(root);
        addStyledDialogCardTitle(card, getString(R.string.settings_renderer_game_resolution_title));
        addStyledDialogInfoText(card, getString(R.string.settings_renderer_game_resolution_warning));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_renderer_game_resolution_confirm_button, null)
                .create();
        dialog.setOnShowListener(dialogInterface -> {
            styleLauncherDialogChrome(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                onConfirmed.run();
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void showCustomGameResolutionDialog() {
        GameResolutionSettings.Profile profile = GameResolutionSettings.getProfile(this);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(COLOR_DIALOG_BG);

        LinearLayout root = createStyledDialogRoot(
                scrollView,
                getString(R.string.settings_renderer_game_resolution_custom_title),
                getString(
                        R.string.settings_renderer_game_resolution_custom_message,
                        GameResolutionSettings.MIN_CUSTOM_DIMENSION,
                        GameResolutionSettings.MAX_CUSTOM_DIMENSION
                )
        );
        LinearLayout card = addStyledDialogCard(root);
        addStyledDialogCardTitle(card, getString(R.string.settings_renderer_game_resolution_title));
        addStyledDialogInfoText(card, getString(R.string.settings_renderer_game_resolution_warning));

        EditText widthInput = createResolutionDimensionInput(
                getString(R.string.settings_renderer_game_resolution_custom_width),
                profile.customWidth,
                android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
        );
        EditText heightInput = createResolutionDimensionInput(
                getString(R.string.settings_renderer_game_resolution_custom_height),
                profile.customHeight,
                android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        );

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        inputParams.setMargins(0, 0, 0, dp(10));
        card.addView(widthInput, inputParams);
        card.addView(heightInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnShowListener(dialogInterface -> {
            styleLauncherDialogChrome(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                int width = parseResolutionDimension(widthInput);
                int height = parseResolutionDimension(heightInput);
                if (!isValidCustomResolutionDimension(width)
                        || !isValidCustomResolutionDimension(height)) {
                    String error = getString(
                            R.string.settings_renderer_game_resolution_custom_invalid,
                            GameResolutionSettings.MIN_CUSTOM_DIMENSION,
                            GameResolutionSettings.MAX_CUSTOM_DIMENSION
                    );
                    if (!isValidCustomResolutionDimension(width)) widthInput.setError(error);
                    if (!isValidCustomResolutionDimension(height)) heightInput.setError(error);
                    return;
                }

                GameResolutionSettings.setCustomResolution(this, width, height);
                updateGameResolutionUi();
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    @NonNull
    private EditText createResolutionDimensionInput(
            @NonNull String hint,
            int value,
            int imeAction
    ) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(String.valueOf(value));
        input.setSelectAllOnFocus(true);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setImeOptions(imeAction);
        input.setTextColor(COLOR_TEXT_PRIMARY);
        input.setHintTextColor(COLOR_TEXT_MUTED);
        input.setBackground(roundedDrawable(COLOR_DIALOG_BG, COLOR_CARD_STROKE, 12));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        return input;
    }

    private int parseResolutionDimension(@NonNull EditText input) {
        try {
            return Integer.parseInt(input.getText() == null ? "" : input.getText().toString().trim());
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private boolean isValidCustomResolutionDimension(int value) {
        return value >= GameResolutionSettings.MIN_CUSTOM_DIMENSION
                && value <= GameResolutionSettings.MAX_CUSTOM_DIMENSION;
    }

    private int gameResolutionModeIndex(@Nullable String mode) {
        String safeMode = GameResolutionSettings.normalizeMode(mode);
        if (GameResolutionSettings.MODE_1920_1080.equals(safeMode)) return 1;
        if (GameResolutionSettings.MODE_BEST_4_3.equals(safeMode)) return 2;
        if (GameResolutionSettings.MODE_MCSX.equals(safeMode)) return 3;
        if (GameResolutionSettings.MODE_CUSTOM.equals(safeMode)) return 4;
        return 0;
    }

    private void updateGameResolutionUi() {
        if (binding == null
                || binding.spinnerGameResolution == null
                || binding.textGameResolutionValue == null) {
            return;
        }

        GameResolutionSettings.Profile profile = GameResolutionSettings.getProfile(this);
        binding.spinnerGameResolution.setSelection(gameResolutionModeIndex(profile.mode), false);

        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        GameResolutionSettings.ResolvedResolution resolved =
                GameResolutionSettings.resolveBaseResolution(
                        profile,
                        Math.max(1, metrics.widthPixels),
                        Math.max(1, metrics.heightPixels)
                );

        if (GameResolutionSettings.MODE_1920_1080.equals(profile.mode)) {
            binding.textGameResolutionValue.setText(getString(
                    R.string.settings_renderer_game_resolution_value_preset,
                    getString(R.string.settings_renderer_game_resolution_1080p),
                    resolved.width,
                    resolved.height
            ));
        } else if (GameResolutionSettings.MODE_BEST_4_3.equals(profile.mode)) {
            binding.textGameResolutionValue.setText(getString(
                    R.string.settings_renderer_game_resolution_value_4_3,
                    resolved.width,
                    resolved.height
            ));
        } else if (GameResolutionSettings.MODE_MCSX.equals(profile.mode)) {
            binding.textGameResolutionValue.setText(getString(
                    R.string.settings_renderer_game_resolution_value_mcsx,
                    resolved.width,
                    resolved.height
            ));
        } else if (GameResolutionSettings.MODE_CUSTOM.equals(profile.mode)) {
            binding.textGameResolutionValue.setText(getString(
                    R.string.settings_renderer_game_resolution_value_custom,
                    profile.customWidth,
                    profile.customHeight
            ));
        } else {
            binding.textGameResolutionValue.setText(getString(
                    R.string.settings_renderer_game_resolution_value_native,
                    resolved.width,
                    resolved.height
            ));
        }
    }

    private void bindForceFullscreenSwitch() {
        boolean forceFullscreen = LauncherPreferences.isForceFullscreenMode(this);

        binding.switchForceFullscreenMode.setOnCheckedChangeListener(null);
        binding.switchForceFullscreenMode.setChecked(forceFullscreen);
        updateForceFullscreenSwitchText(forceFullscreen);

        binding.switchForceFullscreenMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherPreferences.setForceFullscreenMode(this, isChecked);
            updateForceFullscreenSwitchText(isChecked);

            // Fullscreen visibility and notch avoidance are independent. Keeping the
            // notch preference untouched here prevents Force Fullscreen from silently
            // reversing the user's safe-area choice on phones such as the Galaxy S22.
            FullscreenUtils.enableImmersive(this);
        });
    }

    private void bindIgnoreDisplayCutoutSwitch() {
        boolean ignoreDisplayCutout = LauncherPreferences.isIgnoreDisplayCutout(this);

        binding.switchIgnoreDisplayCutout.setOnCheckedChangeListener(null);
        binding.switchIgnoreDisplayCutout.setChecked(ignoreDisplayCutout);
        updateIgnoreDisplayCutoutSwitchText(ignoreDisplayCutout);

        binding.switchIgnoreDisplayCutout.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherPreferences.setIgnoreDisplayCutout(this, isChecked);
            updateIgnoreDisplayCutoutSwitchText(isChecked);

            // ON means avoid/ignore the physical notch as usable game space. Do not
            // force fullscreen or alter the rounded-corner preference; both options
            // are deliberately independent now.
            FullscreenUtils.enableImmersive(this);
        });
    }

    private void bindAvoidRoundedCornersSwitch() {
        boolean avoidRoundedCorners = LauncherPreferences.isAvoidRoundedDisplayCorners(this);

        binding.switchAvoidRoundedCorners.setOnCheckedChangeListener(null);
        binding.switchAvoidRoundedCorners.setChecked(avoidRoundedCorners);
        updateAvoidRoundedCornersSwitchText(avoidRoundedCorners);

        binding.switchAvoidRoundedCorners.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherPreferences.setAvoidRoundedDisplayCorners(this, isChecked);
            updateAvoidRoundedCornersSwitchText(isChecked);

            // Rounded-corner padding and notch avoidance can coexist. The final safe
            // inset is the larger of the physical cutout inset and this small margin.
        });
    }

    private void setForceFullscreenQuietly(boolean enabled) {
        LauncherPreferences.setForceFullscreenMode(this, enabled);
        binding.switchForceFullscreenMode.setOnCheckedChangeListener(null);
        binding.switchForceFullscreenMode.setChecked(enabled);
        updateForceFullscreenSwitchText(enabled);
        bindForceFullscreenSwitch();
    }

    private void setIgnoreDisplayCutoutQuietly(boolean enabled) {
        LauncherPreferences.setIgnoreDisplayCutout(this, enabled);
        binding.switchIgnoreDisplayCutout.setOnCheckedChangeListener(null);
        binding.switchIgnoreDisplayCutout.setChecked(enabled);
        updateIgnoreDisplayCutoutSwitchText(enabled);
        bindIgnoreDisplayCutoutSwitch();
    }

    private void setAvoidRoundedCornersQuietly(boolean enabled) {
        LauncherPreferences.setAvoidRoundedDisplayCorners(this, enabled);
        binding.switchAvoidRoundedCorners.setOnCheckedChangeListener(null);
        binding.switchAvoidRoundedCorners.setChecked(enabled);
        updateAvoidRoundedCornersSwitchText(enabled);
        bindAvoidRoundedCornersSwitch();
    }

    private void openResolutionScaleInputDialog() {
        int currentScale = LauncherPreferences.getGameResolutionScalePercent(this);

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setText(String.valueOf(currentScale));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_renderer_resolution_scale_title)
                .setMessage(getString(
                        R.string.settings_renderer_resolution_scale_dialog_message,
                        LauncherPreferences.MIN_GAME_RESOLUTION_SCALE_PERCENT,
                        LauncherPreferences.MAX_GAME_RESOLUTION_SCALE_PERCENT
                ))
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();

        dialog.setOnShowListener(dialogInterface -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            int percent = parseResolutionScaleInput(input.getText() == null ? "" : input.getText().toString());
            percent = LauncherPreferences.clampGameResolutionScalePercent(percent);
            LauncherPreferences.setGameResolutionScalePercent(this, percent);
            updateResolutionScaleUi(percent);
            dialog.dismiss();
        }));

        dialog.show();
    }

    private int parseResolutionScaleInput(@NonNull String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Throwable ignored) {
            return LauncherPreferences.getGameResolutionScalePercent(this);
        }
    }

    private void updateResolutionScaleUi(int percent) {
        int safePercent = LauncherPreferences.clampGameResolutionScalePercent(percent);
        binding.sliderGameResolutionScale.setProgress(
                safePercent - LauncherPreferences.MIN_GAME_RESOLUTION_SCALE_PERCENT
        );
        updateResolutionScaleText(safePercent);
    }

    private void updateResolutionScaleText(int percent) {
        binding.textGameResolutionScale.setText(getString(
                R.string.settings_renderer_resolution_scale_value,
                LauncherPreferences.clampGameResolutionScalePercent(percent)
        ));
    }

    private void refreshGameDisplaySettingsValues() {
        if (binding == null
                || binding.sliderGameResolutionScale == null
                || binding.textGameResolutionScale == null) {
            return;
        }

        updateResolutionScaleUi(LauncherPreferences.getGameResolutionScalePercent(this));
        updateGameResolutionUi();
        updateOrientationModeUi();
    }

    private void setupOrientationSettings() {
        updateOrientationModeUi();
        binding.buttonLauncherOrientationMode.setOnClickListener(view -> showOrientationModeDialog(false));
        binding.buttonGameOrientationMode.setOnClickListener(view -> showOrientationModeDialog(true));
    }

    private void setupImeViewportPushSetting() {
        if (binding == null || binding.switchImeViewportPush == null) return;

        boolean enabled = LauncherPreferences.isImeViewportPushEnabled(this);
        binding.switchImeViewportPush.setOnCheckedChangeListener(null);
        binding.switchImeViewportPush.setChecked(enabled);
        updateImeViewportPushSwitchText(enabled);
        binding.switchImeViewportPush.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherPreferences.setImeViewportPushEnabled(this, isChecked);
            updateImeViewportPushSwitchText(isChecked);
        });
    }

    private void showOrientationModeDialog(boolean gameOrientation) {
        List<String> labels = new ArrayList<>(Arrays.asList(
                getString(R.string.app_orientation_auto_rotate),
                getString(R.string.app_orientation_landscape),
                getString(R.string.app_orientation_reverse_landscape),
                getString(R.string.app_orientation_portrait),
                getString(R.string.app_orientation_reverse_portrait)
        ));
        List<String> values = new ArrayList<>(Arrays.asList(
                LauncherPreferences.APP_ORIENTATION_AUTO,
                LauncherPreferences.APP_ORIENTATION_LANDSCAPE,
                LauncherPreferences.APP_ORIENTATION_REVERSE_LANDSCAPE,
                LauncherPreferences.APP_ORIENTATION_PORTRAIT,
                LauncherPreferences.APP_ORIENTATION_REVERSE_PORTRAIT
        ));

        // The launcher itself has no Minecraft SurfaceView to center, so expose this
        // additional mode only for Game Orientation. The Activity stays portrait while
        // GameActivity places the game surface in a smaller centered landscape rectangle.
        if (gameOrientation) {
            labels.add(4, getString(R.string.app_orientation_portrait_centered_game));
            values.add(4, LauncherPreferences.APP_ORIENTATION_PORTRAIT_CENTERED_GAME);
        }

        String current = gameOrientation
                ? LauncherPreferences.getGameOrientationMode(this)
                : LauncherPreferences.getLauncherOrientationMode(this);
        int selectedIndex = Math.max(0, values.indexOf(current));

        showStyledSingleChoiceDialog(
                getString(gameOrientation
                        ? R.string.game_orientation_title
                        : R.string.launcher_orientation_title),
                getString(gameOrientation
                        ? R.string.game_orientation_dialog_summary
                        : R.string.launcher_orientation_dialog_summary),
                labels,
                selectedIndex,
                position -> {
                    if (position < 0 || position >= values.size()) return;
                    String selectedMode = values.get(position);
                    if (gameOrientation) {
                        LauncherPreferences.setGameOrientationMode(this, selectedMode);
                    } else {
                        LauncherPreferences.setLauncherOrientationMode(this, selectedMode);
                    }
                    updateOrientationModeUi();
                    if (!gameOrientation) {
                        AppOrientationHelper.applyToActivity(this);
                    }
                }
        );
    }

    private void updateOrientationModeUi() {
        if (binding == null
                || binding.buttonLauncherOrientationMode == null
                || binding.textLauncherOrientationModeSummary == null
                || binding.buttonGameOrientationMode == null
                || binding.textGameOrientationModeSummary == null) {
            return;
        }

        String launcherLabel = getAppOrientationModeLabel(
                LauncherPreferences.getLauncherOrientationMode(this)
        );
        binding.buttonLauncherOrientationMode.setText(launcherLabel);
        binding.textLauncherOrientationModeSummary.setText(
                getString(R.string.launcher_orientation_summary, launcherLabel)
        );

        String gameLabel = getAppOrientationModeLabel(
                LauncherPreferences.getGameOrientationMode(this)
        );
        binding.buttonGameOrientationMode.setText(gameLabel);
        binding.textGameOrientationModeSummary.setText(
                getString(R.string.game_orientation_summary, gameLabel)
        );
    }

    @NonNull
    private String getAppOrientationModeLabel(@NonNull String mode) {
        switch (mode) {
            case LauncherPreferences.APP_ORIENTATION_LANDSCAPE:
                return getString(R.string.app_orientation_landscape);
            case LauncherPreferences.APP_ORIENTATION_REVERSE_LANDSCAPE:
                return getString(R.string.app_orientation_reverse_landscape);
            case LauncherPreferences.APP_ORIENTATION_PORTRAIT:
                return getString(R.string.app_orientation_portrait);
            case LauncherPreferences.APP_ORIENTATION_REVERSE_PORTRAIT:
                return getString(R.string.app_orientation_reverse_portrait);
            case LauncherPreferences.APP_ORIENTATION_PORTRAIT_CENTERED_GAME:
                return getString(R.string.app_orientation_portrait_centered_game);
            case LauncherPreferences.APP_ORIENTATION_AUTO:
            default:
                return getString(R.string.app_orientation_auto_rotate);
        }
    }

    private void setupControllerSettings() {
        binding.buttonEditBuiltInController.setOnClickListener(view ->
                GamepadMappingDialog.show(this, () -> runOnUiThread(() -> {
                    refreshControllerSettingsValues();
                    FullscreenUtils.enableImmersive(this);
                }))
        );

        binding.buttonManageTouchControls.setOnClickListener(view ->
                startActivity(new Intent(this, ControlsActivity.class))
        );

        binding.buttonMouseCursorIconSettings.setOnClickListener(view -> showMouseCursorIconDialog());
        updateMouseCursorIconSettingsSummary();

        setupControllerCameraSensitivitySettings();
        setupGyroscopeCameraSettings();
        setupPhysicalMouseModeSettings();
        setupHardwareMouseDpiScaleSettings();
        setupVirtualMouseAtGameStartSettings();
        setupVirtualMouseSpeedSettings();
        refreshControllerSettingsValues();
    }

    private void setupControllerCameraSensitivitySettings() {
        if (binding == null || binding.layoutControllerSettings == null) return;

        LinearLayout host = binding.layoutHardwareMouseDpiScaleHost != null
                ? binding.layoutHardwareMouseDpiScaleHost
                : binding.layoutControllerSettings;
        if (host.findViewWithTag("controller_camera_sensitivity") != null) return;

        LinearLayout container = new LinearLayout(this);
        container.setTag("controller_camera_sensitivity");
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, dp(12), 0, 0);

        TextView title = new TextView(this);
        title.setText("In-game camera sensitivity");
        title.setTextSize(16f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        container.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView summary = new TextView(this);
        summary.setText("Controls in-game camera movement from touchscreen look, controller right stick, and gyroscope camera look. This is the same value used by Edit Gamepad Settings and updates immediately.");
        summary.setTextSize(13f);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        summaryParams.topMargin = dp(2);
        container.addView(summary, summaryParams);

        textControllerCameraSensitivity = new TextView(this);
        textControllerCameraSensitivity.setTextSize(14f);
        textControllerCameraSensitivity.setTypeface(
                textControllerCameraSensitivity.getTypeface(),
                android.graphics.Typeface.BOLD
        );
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        valueParams.topMargin = dp(8);
        container.addView(textControllerCameraSensitivity, valueParams);

        sliderControllerCameraSensitivity = new SeekBar(this);
        sliderControllerCameraSensitivity.setMax(
                GamepadMappingStore.MAX_SENSITIVITY - GamepadMappingStore.MIN_SENSITIVITY
        );
        sliderControllerCameraSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int percent = GamepadMappingStore.MIN_SENSITIVITY + progress;
                updateControllerCameraSensitivityText(percent);
                if (fromUser) {
                    GamepadMappingStore.get(LauncherSettingsActivity.this)
                            .setGameCameraSensitivity(percent);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int percent = GamepadMappingStore.MIN_SENSITIVITY + seekBar.getProgress();
                GamepadMappingStore.get(LauncherSettingsActivity.this)
                        .setGameCameraSensitivity(percent);
                updateControllerCameraSensitivityUi(percent);
            }
        });
        container.addView(sliderControllerCameraSensitivity, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        host.addView(container, 0);
    }

    private void setupGyroscopeCameraSettings() {
        if (binding == null || binding.layoutControllerSettings == null) return;

        LinearLayout host = binding.layoutHardwareMouseDpiScaleHost != null
                ? binding.layoutHardwareMouseDpiScaleHost
                : binding.layoutControllerSettings;
        if (host.findViewWithTag("gyroscope_camera") != null) return;

        LinearLayout container = new LinearLayout(this);
        container.setTag("gyroscope_camera");
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, dp(12), 0, 0);

        TextView title = new TextView(this);
        title.setText("Gyroscope camera");
        title.setTextSize(16f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        container.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        textGyroscopeCameraSummary = new TextView(this);
        textGyroscopeCameraSummary.setTextSize(13f);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        summaryParams.topMargin = dp(2);
        container.addView(textGyroscopeCameraSummary, summaryParams);

        switchGyroscopeCamera =
                new com.google.android.material.switchmaterial.SwitchMaterial(this);
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        switchParams.topMargin = dp(6);
        container.addView(switchGyroscopeCamera, switchParams);

        host.addView(container, Math.min(1, host.getChildCount()));
    }

    private void setupPhysicalMouseModeSettings() {
        if (binding == null || binding.layoutControllerSettings == null) return;

        LinearLayout host = binding.layoutHardwareMouseDpiScaleHost != null
                ? binding.layoutHardwareMouseDpiScaleHost
                : binding.layoutControllerSettings;
        if (host.findViewWithTag("physical_mouse_mode") != null) return;

        LinearLayout container = new LinearLayout(this);
        container.setTag("physical_mouse_mode");
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, dp(12), 0, 0);

        TextView title = new TextView(this);
        title.setText("Physical Mouse Mode");
        title.setTextSize(16f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        container.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        buttonPhysicalMouseMode = new MaterialButton(this);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        buttonParams.topMargin = dp(8);
        container.addView(buttonPhysicalMouseMode, buttonParams);

        textPhysicalMouseModeSummary = new TextView(this);
        textPhysicalMouseModeSummary.setTextSize(13f);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        summaryParams.topMargin = dp(4);
        container.addView(textPhysicalMouseModeSummary, summaryParams);

        buttonPhysicalMouseMode.setOnClickListener(view -> showPhysicalMouseModeDialog());
        host.addView(container);
        updatePhysicalMouseModeUi();
    }

    private void showPhysicalMouseModeDialog() {
        String current = LauncherPreferences.getPhysicalMouseMode(this);
        String[] labels = new String[]{"Native Mouse", "Android Virtual Mouse"};
        int checked = LauncherPreferences.PHYSICAL_MOUSE_MODE_ANDROID_VIRTUAL.equals(current) ? 1 : 0;

        new AlertDialog.Builder(this)
                .setTitle("Physical Mouse Mode")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    LauncherPreferences.setPhysicalMouseMode(
                            this,
                            which == 1
                                    ? LauncherPreferences.PHYSICAL_MOUSE_MODE_ANDROID_VIRTUAL
                                    : LauncherPreferences.PHYSICAL_MOUSE_MODE_NATIVE
                    );
                    updatePhysicalMouseModeUi();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updatePhysicalMouseModeUi() {
        if (buttonPhysicalMouseMode == null || textPhysicalMouseModeSummary == null) return;
        boolean androidVirtual = LauncherPreferences.isAndroidVirtualPhysicalMouse(this);
        if (androidVirtual) {
            buttonPhysicalMouseMode.setText("Android Virtual Mouse");
            textPhysicalMouseModeSummary.setText(
                    "Less accurate. Uses Android's native OS mouse so the pointer can click "
                            + "DroidBridge on-screen buttons and also interact with Minecraft.");
        } else {
            buttonPhysicalMouseMode.setText("Native Mouse");
            textPhysicalMouseModeSummary.setText(
                    "More accurate. Sends physical mouse input directly to Java Minecraft using "
                            + "DroidBridge's native/raw mouse path.");
        }
    }

    private void setupHardwareMouseDpiScaleSettings() {
        if (binding == null || binding.layoutControllerSettings == null) return;

        LinearLayout host = binding.layoutHardwareMouseDpiScaleHost != null
                ? binding.layoutHardwareMouseDpiScaleHost
                : binding.layoutControllerSettings;
        if (host.findViewWithTag("hardware_mouse_dpi_scale") != null) return;

        LinearLayout container = new LinearLayout(this);
        container.setTag("hardware_mouse_dpi_scale");
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, dp(12), 0, 0);

        TextView title = new TextView(this);
        title.setText("Hardware mouse DPI scale");
        title.setTextSize(16f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleParams.topMargin = 0;
        container.addView(title, titleParams);

        TextView summary = new TextView(this);
        summary.setText("Adjusts real mouse / captured-pointer speed using a relative pointer multiplier. Touch camera movement, hotbar taps, and absolute menu taps are not scaled.");
        summary.setTextSize(13f);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        summaryParams.topMargin = dp(2);
        container.addView(summary, summaryParams);

        textHardwareMouseDpiScale = new TextView(this);
        textHardwareMouseDpiScale.setTextSize(14f);
        textHardwareMouseDpiScale.setTypeface(textHardwareMouseDpiScale.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        valueParams.topMargin = dp(8);
        container.addView(textHardwareMouseDpiScale, valueParams);

        sliderHardwareMouseDpiScale = new SeekBar(this);
        sliderHardwareMouseDpiScale.setMax(GamepadMappingStore.MAX_MOUSE_DPI_SCALE - GamepadMappingStore.MIN_MOUSE_DPI_SCALE);
        sliderHardwareMouseDpiScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int percent = GamepadMappingStore.MIN_MOUSE_DPI_SCALE + progress;
                updateHardwareMouseDpiScaleText(percent);
                if (fromUser) {
                    GamepadMappingStore.get(LauncherSettingsActivity.this).setHardwareMouseDpiScale(percent);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int percent = GamepadMappingStore.MIN_MOUSE_DPI_SCALE + seekBar.getProgress();
                GamepadMappingStore.get(LauncherSettingsActivity.this).setHardwareMouseDpiScale(percent);
                updateHardwareMouseDpiScaleUi(percent);
            }
        });
        container.addView(sliderHardwareMouseDpiScale, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        host.addView(container);
    }

    private void setupVirtualMouseAtGameStartSettings() {
        if (binding == null || binding.layoutControllerSettings == null) return;

        LinearLayout host = binding.layoutHardwareMouseDpiScaleHost != null
                ? binding.layoutHardwareMouseDpiScaleHost
                : binding.layoutControllerSettings;
        if (host.findViewWithTag("virtual_mouse_at_game_start") != null) return;

        LinearLayout container = new LinearLayout(this);
        container.setTag("virtual_mouse_at_game_start");
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, dp(12), 0, 0);

        TextView title = new TextView(this);
        title.setText("Touch Virtual Mouse");
        title.setTextSize(16f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        container.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        switchVirtualMouseAtGameStart =
                new com.google.android.material.switchmaterial.SwitchMaterial(this);
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        switchParams.topMargin = dp(8);
        container.addView(switchVirtualMouseAtGameStart, switchParams);

        host.addView(container);
    }

    private void setupVirtualMouseSpeedSettings() {
        if (binding == null || binding.layoutControllerSettings == null) return;

        LinearLayout host = binding.layoutHardwareMouseDpiScaleHost != null
                ? binding.layoutHardwareMouseDpiScaleHost
                : binding.layoutControllerSettings;
        if (host.findViewWithTag("virtual_mouse_speed") != null) return;

        LinearLayout container = new LinearLayout(this);
        container.setTag("virtual_mouse_speed");
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, dp(12), 0, 0);

        TextView title = new TextView(this);
        title.setText("Virtual mouse speed");
        title.setTextSize(16f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        container.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView summary = new TextView(this);
        summary.setText("Adjusts actual finger-driven virtual cursor movement from 25% to 300%. This does not add a cursor offset and does not affect controller or hardware mouse movement.");
        summary.setTextSize(13f);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        summaryParams.topMargin = dp(2);
        container.addView(summary, summaryParams);

        textVirtualMouseSpeed = new TextView(this);
        textVirtualMouseSpeed.setTextSize(14f);
        textVirtualMouseSpeed.setTypeface(
                textVirtualMouseSpeed.getTypeface(),
                android.graphics.Typeface.BOLD
        );
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        valueParams.topMargin = dp(8);
        container.addView(textVirtualMouseSpeed, valueParams);

        sliderVirtualMouseSpeed = new SeekBar(this);
        sliderVirtualMouseSpeed.setMax(
                ControlsPreferences.MAX_VIRTUAL_MOUSE_SPEED_PERCENT
                        - ControlsPreferences.MIN_VIRTUAL_MOUSE_SPEED_PERCENT
        );
        sliderVirtualMouseSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int percent = ControlsPreferences.MIN_VIRTUAL_MOUSE_SPEED_PERCENT + progress;
                updateVirtualMouseSpeedText(percent);
                if (fromUser) {
                    ControlsPreferences.setVirtualMouseSpeedPercent(
                            LauncherSettingsActivity.this,
                            percent
                    );
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int percent = ControlsPreferences.MIN_VIRTUAL_MOUSE_SPEED_PERCENT
                        + seekBar.getProgress();
                ControlsPreferences.setVirtualMouseSpeedPercent(
                        LauncherSettingsActivity.this,
                        percent
                );
                updateVirtualMouseSpeedUi(percent);
            }
        });
        container.addView(sliderVirtualMouseSpeed, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        host.addView(container);
    }

    private void refreshControllerSettingsValues() {
        if (binding == null) return;

        boolean touchControlsEnabled = ControlsPreferences.isTouchControlsEnabled(this);
        binding.switchTouchControlsEnabled.setOnCheckedChangeListener(null);
        binding.switchTouchControlsEnabled.setChecked(touchControlsEnabled);
        updateTouchControlsSwitchText(touchControlsEnabled);
        binding.switchTouchControlsEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ControlsPreferences.setTouchControlsEnabled(this, isChecked);
            updateTouchControlsSwitchText(isChecked);
        });

        updateMinecraftTouchGestureSettingsUi();
        updateMouseCursorIconSettingsSummary();
        GamepadMappingStore mappingStore = GamepadMappingStore.get(this);
        updateControllerCameraSensitivityUi(mappingStore.getGameCameraSensitivity());
        updateGyroscopeCameraUi();
        updatePhysicalMouseModeUi();
        updateHardwareMouseDpiScaleUi(mappingStore.getHardwareMouseDpiScale());
        updateVirtualMouseAtGameStartUi();
        updateVirtualMouseSpeedUi(ControlsPreferences.getVirtualMouseSpeedPercent(this));

        boolean forceSdl = LauncherPreferences.isForceSdlControllerBridge(this);
        binding.switchForceSdlControllerBridge.setOnCheckedChangeListener(null);
        binding.switchForceSdlControllerBridge.setChecked(forceSdl);
        updateForceSdlControllerBridgeSwitchText(forceSdl);
        binding.switchForceSdlControllerBridge.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherPreferences.setForceSdlControllerBridge(this, isChecked);
            updateForceSdlControllerBridgeSwitchText(isChecked);
        });

        boolean autoHideTouchControls =
                ControlsPreferences.isAutoHideTouchControlsWithControllerEnabled(this);
        binding.switchAutoHideTouchControlsWithController.setOnCheckedChangeListener(null);
        binding.switchAutoHideTouchControlsWithController.setChecked(autoHideTouchControls);
        updateAutoHideTouchControlsWithControllerSwitchText(autoHideTouchControls);
        binding.switchAutoHideTouchControlsWithController.setOnCheckedChangeListener(
                (buttonView, isChecked) -> {
                    ControlsPreferences.setAutoHideTouchControlsWithControllerEnabled(
                            this,
                            isChecked
                    );
                    updateAutoHideTouchControlsWithControllerSwitchText(isChecked);
                }
        );
    }


    private void updateMinecraftTouchGestureSettingsUi() {
        boolean gesturesEnabled = ControlsPreferences.isMinecraftTouchGesturesEnabled(this);
        binding.switchMinecraftTouchGestures.setOnCheckedChangeListener(null);
        binding.switchMinecraftTouchGestures.setChecked(gesturesEnabled);
        updateMinecraftTouchGesturesSwitchText(gesturesEnabled);
        binding.switchMinecraftTouchGestures.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ControlsPreferences.setMinecraftTouchGesturesEnabled(this, isChecked);
            updateMinecraftTouchGesturesSwitchText(isChecked);
            updateDoubleTapToDropEnabledState(isChecked);
        });

        boolean doubleTapToDrop = ControlsPreferences.isDoubleTapToDropEnabled(this);
        binding.switchDoubleTapToDrop.setOnCheckedChangeListener(null);
        binding.switchDoubleTapToDrop.setChecked(doubleTapToDrop);
        updateDoubleTapToDropSwitchText(doubleTapToDrop);
        updateDoubleTapToDropEnabledState(gesturesEnabled);
        binding.switchDoubleTapToDrop.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ControlsPreferences.setDoubleTapToDropEnabled(this, isChecked);
            updateDoubleTapToDropSwitchText(isChecked);
        });

        boolean doubleTapHotbarToOffhand =
                ControlsPreferences.isDoubleTapHotbarToOffhandEnabled(this);
        binding.switchDoubleTapHotbarToOffhand.setOnCheckedChangeListener(null);
        binding.switchDoubleTapHotbarToOffhand.setChecked(doubleTapHotbarToOffhand);
        updateDoubleTapHotbarToOffhandSwitchText(doubleTapHotbarToOffhand);
        binding.switchDoubleTapHotbarToOffhand.setOnCheckedChangeListener(
                (buttonView, isChecked) -> {
                    ControlsPreferences.setDoubleTapHotbarToOffhandEnabled(this, isChecked);
                    updateDoubleTapHotbarToOffhandSwitchText(isChecked);
                }
        );
    }

    private void updateMinecraftTouchGesturesSwitchText(boolean enabled) {
        binding.switchMinecraftTouchGestures.setText(enabled
                ? R.string.controller_minecraft_touch_gestures_on
                : R.string.controller_minecraft_touch_gestures_off);
    }

    private void updateDoubleTapToDropSwitchText(boolean enabled) {
        binding.switchDoubleTapToDrop.setText(enabled
                ? R.string.controller_double_tap_to_drop_on
                : R.string.controller_double_tap_to_drop_off);
    }

    private void updateDoubleTapHotbarToOffhandSwitchText(boolean enabled) {
        binding.switchDoubleTapHotbarToOffhand.setText(enabled
                ? R.string.controller_double_tap_hotbar_to_offhand_on
                : R.string.controller_double_tap_hotbar_to_offhand_off);
    }

    private void updateDoubleTapToDropEnabledState(boolean gesturesEnabled) {
        binding.switchDoubleTapToDrop.setEnabled(gesturesEnabled);
        binding.textDoubleTapToDropSummary.setAlpha(gesturesEnabled ? 1.0f : 0.55f);
    }

    private void updateControllerCameraSensitivityUi(int percent) {
        int safePercent = Math.max(
                GamepadMappingStore.MIN_SENSITIVITY,
                Math.min(GamepadMappingStore.MAX_SENSITIVITY, percent)
        );
        if (sliderControllerCameraSensitivity != null) {
            sliderControllerCameraSensitivity.setProgress(
                    safePercent - GamepadMappingStore.MIN_SENSITIVITY
            );
        }
        updateControllerCameraSensitivityText(safePercent);
    }

    private void updateControllerCameraSensitivityText(int percent) {
        if (textControllerCameraSensitivity != null) {
            textControllerCameraSensitivity.setText(
                    "In-game camera sensitivity: " + percent + "%"
            );
        }
    }

    private void updateGyroscopeCameraUi() {
        if (switchGyroscopeCamera == null) return;

        boolean available = GyroInputController.isGyroscopeAvailable(this);
        boolean enabled = available && ControlsPreferences.isGyroscopeCameraEnabled(this);

        switchGyroscopeCamera.setOnCheckedChangeListener(null);
        switchGyroscopeCamera.setEnabled(available);
        switchGyroscopeCamera.setChecked(enabled);
        switchGyroscopeCamera.setText(available
                ? (enabled ? "Gyroscope camera: On" : "Gyroscope camera: Off")
                : "Gyroscope camera: Not available");

        if (textGyroscopeCameraSummary != null) {
            textGyroscopeCameraSummary.setText(available
                    ? "Move the phone or handheld to look around in-game. Gyro only moves the camera while Minecraft has captured the mouse, and it uses the In-game camera sensitivity setting above. Off by default."
                    : "This device does not report an Android gyroscope sensor, so gyro camera movement is unavailable.");
            textGyroscopeCameraSummary.setAlpha(available ? 1.0f : 0.55f);
        }

        switchGyroscopeCamera.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ControlsPreferences.setGyroscopeCameraEnabled(
                    LauncherSettingsActivity.this,
                    isChecked
            );
            updateGyroscopeCameraUi();
        });
    }

    private void updateHardwareMouseDpiScaleUi(int percent) {
        int safePercent = Math.max(
                GamepadMappingStore.MIN_MOUSE_DPI_SCALE,
                Math.min(GamepadMappingStore.MAX_MOUSE_DPI_SCALE, percent)
        );
        if (sliderHardwareMouseDpiScale != null) {
            sliderHardwareMouseDpiScale.setProgress(safePercent - GamepadMappingStore.MIN_MOUSE_DPI_SCALE);
        }
        updateHardwareMouseDpiScaleText(safePercent);
    }

    private void updateHardwareMouseDpiScaleText(int percent) {
        if (textHardwareMouseDpiScale != null) {
            textHardwareMouseDpiScale.setText("Mouse DPI scale: " + percent + "%");
        }
    }

    private void updateVirtualMouseAtGameStartUi() {
        if (switchVirtualMouseAtGameStart == null) return;

        boolean enabled = ControlsPreferences.isVirtualMouseStartEnabled(this);
        switchVirtualMouseAtGameStart.setOnCheckedChangeListener(null);
        switchVirtualMouseAtGameStart.setChecked(enabled);
        updateVirtualMouseAtGameStartText(enabled);
        switchVirtualMouseAtGameStart.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ControlsPreferences.setVirtualMouseStartEnabled(this, isChecked);
            updateVirtualMouseAtGameStartText(isChecked);
        });
    }

    private void updateVirtualMouseAtGameStartText(boolean enabled) {
        if (switchVirtualMouseAtGameStart != null) {
            switchVirtualMouseAtGameStart.setText(
                    enabled
                            ? "Touch Virtual Mouse: On (offset virtual mouse)"
                            : "Touch Virtual Mouse: Off (normal touch at finger location)"
            );
        }
    }

    private void updateVirtualMouseSpeedUi(int percent) {
        int safePercent = Math.max(
                ControlsPreferences.MIN_VIRTUAL_MOUSE_SPEED_PERCENT,
                Math.min(ControlsPreferences.MAX_VIRTUAL_MOUSE_SPEED_PERCENT, percent)
        );
        if (sliderVirtualMouseSpeed != null) {
            sliderVirtualMouseSpeed.setProgress(
                    safePercent - ControlsPreferences.MIN_VIRTUAL_MOUSE_SPEED_PERCENT
            );
        }
        updateVirtualMouseSpeedText(safePercent);
    }

    private void updateVirtualMouseSpeedText(int percent) {
        if (textVirtualMouseSpeed != null) {
            textVirtualMouseSpeed.setText("Virtual mouse speed: " + percent + "%");
        }
    }

    private void updateTouchControlsSwitchText(boolean enabled) {
        binding.switchTouchControlsEnabled.setText(enabled
                ? R.string.controller_touch_controls_enabled_on
                : R.string.controller_touch_controls_enabled_off);
    }

    private void updateForceSdlControllerBridgeSwitchText(boolean enabled) {
        binding.switchForceSdlControllerBridge.setText(enabled
                ? R.string.controller_force_sdl_on
                : R.string.controller_force_sdl_off);
    }

    private void updateAutoHideTouchControlsWithControllerSwitchText(boolean enabled) {
        binding.switchAutoHideTouchControlsWithController.setText(enabled
                ? R.string.controller_auto_hide_touch_controls_on
                : R.string.controller_auto_hide_touch_controls_off);
    }

    private void registerMouseCursorIconPickerLauncher() {
        mouseCursorIconPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                    Uri uri = result.getData().getData();
                    if (uri == null) return;
                    importCustomMouseCursorIcon(uri);
                }
        );
    }

    private void showMouseCursorIconDialog() {
        String[] styles = new String[]{
                ControlsPreferences.MOUSE_CURSOR_STYLE_CROSSHAIR,
                ControlsPreferences.MOUSE_CURSOR_STYLE_ARROW,
                ControlsPreferences.MOUSE_CURSOR_STYLE_DOT,
                ControlsPreferences.MOUSE_CURSOR_STYLE_CUSTOM
        };
        String currentStyle = ControlsPreferences.getMouseCursorStyle(this);
        int currentSize = ControlsPreferences.getMouseCursorSizePercent(this);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(COLOR_DIALOG_BG);

        LinearLayout root = createStyledDialogRoot(
                scrollView,
                "Mouse cursor icon",
                "Choose the cursor used by the virtual mouse button and controller cursor mode in Minecraft menus. This cursor is not shown during normal grabbed gameplay."
        );

        LinearLayout previewCard = addStyledDialogCard(root);
        addStyledDialogCardTitle(previewCard, "Preview");

        ImageView preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(dp(88), dp(88));
        previewParams.gravity = Gravity.CENTER_HORIZONTAL;
        previewParams.topMargin = dp(6);
        previewCard.addView(preview, previewParams);

        final String[] selectedStyle = new String[]{currentStyle};
        final int[] selectedSize = new int[]{currentSize};

        LinearLayout styleCard = addStyledDialogCard(root);
        addStyledDialogCardTitle(styleCard, "Cursor style");
        addStyledDialogInfoText(styleCard, "Use one of the built-in icons, or upload a custom image for the menu cursor.");

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        LinearLayout.LayoutParams groupParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        groupParams.topMargin = dp(4);
        styleCard.addView(group, groupParams);

        int[] radioIds = new int[styles.length];
        for (int i = 0; i < styles.length; i++) {
            String style = styles[i];
            RadioButton option = new RadioButton(this);
            option.setId(View.generateViewId());
            radioIds[i] = option.getId();
            option.setText(ControlsPreferences.getMouseCursorStyleLabel(style)
                    + (ControlsPreferences.MOUSE_CURSOR_STYLE_CUSTOM.equals(style) && !ControlsPreferences.hasCustomMouseCursorIcon(this)
                    ? " (upload required)"
                    : ""));
            option.setChecked(style.equals(currentStyle));
            styleDialogRadioButton(option);
            group.addView(option, new RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT,
                    RadioGroup.LayoutParams.WRAP_CONTENT
            ));
        }

        group.setOnCheckedChangeListener((radioGroup, checkedId) -> {
            for (int i = 0; i < radioIds.length; i++) {
                if (radioIds[i] == checkedId) {
                    selectedStyle[0] = styles[i];
                    updateMouseCursorIconPreview(preview, selectedStyle[0], selectedSize[0]);
                    return;
                }
            }
        });

        MaterialButton uploadCustom = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        uploadCustom.setText("Upload custom icon");
        uploadCustom.setAllCaps(false);
        styleDialogOutlinedButton(uploadCustom);
        LinearLayout.LayoutParams uploadParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        uploadParams.topMargin = dp(8);
        styleCard.addView(uploadCustom, uploadParams);

        LinearLayout sizeCard = addStyledDialogCard(root);
        addStyledDialogCardTitle(sizeCard, "Cursor size");

        TextView sizeValue = new TextView(this);
        sizeValue.setTextColor(COLOR_TEXT_SECONDARY);
        sizeValue.setTextSize(14f);
        sizeValue.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams sizeValueParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        sizeValueParams.topMargin = dp(2);
        sizeCard.addView(sizeValue, sizeValueParams);

        SeekBar sizeSlider = new SeekBar(this);
        styleDialogSeekBar(sizeSlider);
        sizeSlider.setMax(ControlsPreferences.MAX_MOUSE_CURSOR_SIZE_PERCENT
                - ControlsPreferences.MIN_MOUSE_CURSOR_SIZE_PERCENT);
        sizeSlider.setProgress(currentSize - ControlsPreferences.MIN_MOUSE_CURSOR_SIZE_PERCENT);
        sizeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                selectedSize[0] = ControlsPreferences.MIN_MOUSE_CURSOR_SIZE_PERCENT + progress;
                sizeValue.setText("Cursor size: " + selectedSize[0] + "%");
                updateMouseCursorIconPreview(preview, selectedStyle[0], selectedSize[0]);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        sizeCard.addView(sizeSlider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        updateMouseCursorIconPreview(preview, selectedStyle[0], selectedSize[0]);
        sizeValue.setText("Cursor size: " + selectedSize[0] + "%");

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();

        uploadCustom.setOnClickListener(view -> {
            openMouseCursorIconPicker();
            dialog.dismiss();
        });

        dialog.setOnShowListener(dialogInterface -> {
            styleLauncherDialogChrome(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                if (ControlsPreferences.MOUSE_CURSOR_STYLE_CUSTOM.equals(selectedStyle[0])
                        && !ControlsPreferences.hasCustomMouseCursorIcon(this)) {
                    Toast.makeText(this, "Choose a custom cursor image first.", Toast.LENGTH_SHORT).show();
                    openMouseCursorIconPicker();
                    dialog.dismiss();
                    return;
                }

                ControlsPreferences.setMouseCursorStyle(this, selectedStyle[0]);
                ControlsPreferences.setMouseCursorSizePercent(this, selectedSize[0]);
                updateMouseCursorIconSettingsSummary();
                Toast.makeText(this, "Mouse cursor settings saved.", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
        });

        dialog.show();
    }

    private void openMouseCursorIconPicker() {
        if (mouseCursorIconPickerLauncher == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        mouseCursorIconPickerLauncher.launch(intent);
    }

    private void importCustomMouseCursorIcon(@NonNull Uri uri) {
        File target = ControlsPreferences.getCustomMouseCursorFile(this);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();

        try {
            copyUriToFile(uri, target);
            if (BitmapFactory.decodeFile(target.getAbsolutePath()) == null) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                Toast.makeText(this, "That image could not be used as a cursor icon.", Toast.LENGTH_LONG).show();
                return;
            }

            ControlsPreferences.setCustomMouseCursorPath(this, target.getAbsolutePath());
            ControlsPreferences.setMouseCursorStyle(this, ControlsPreferences.MOUSE_CURSOR_STYLE_CUSTOM);
            updateMouseCursorIconSettingsSummary();
            Toast.makeText(this, "Custom mouse cursor saved.", Toast.LENGTH_SHORT).show();
        } catch (Throwable throwable) {
            Toast.makeText(this, throwable.getMessage() != null ? throwable.getMessage() : throwable.toString(), Toast.LENGTH_LONG).show();
        }
    }

    private void updateMouseCursorIconSettingsSummary() {
        if (binding == null || binding.textMouseCursorIconSummary == null) return;

        String style = ControlsPreferences.getMouseCursorStyle(this);
        String label = ControlsPreferences.getMouseCursorStyleLabel(style);
        if (ControlsPreferences.MOUSE_CURSOR_STYLE_CUSTOM.equals(style)
                && !ControlsPreferences.hasCustomMouseCursorIcon(this)) {
            label = "Custom image not selected";
        }
        binding.textMouseCursorIconSummary.setText(
                "Selected: " + label + " • Size: " + ControlsPreferences.getMouseCursorSizePercent(this) + "%"
        );
    }

    private void updateMouseCursorIconPreview(
            @NonNull ImageView preview,
            @NonNull String style,
            int sizePercent
    ) {
        int safePercent = Math.max(
                ControlsPreferences.MIN_MOUSE_CURSOR_SIZE_PERCENT,
                Math.min(ControlsPreferences.MAX_MOUSE_CURSOR_SIZE_PERCENT, sizePercent)
        );
        int previewSize = dp(88);
        int iconSize = Math.max(dp(28), Math.min(previewSize, Math.round(previewSize * safePercent / 130f)));
        ViewGroup.LayoutParams params = preview.getLayoutParams();
        if (params != null) {
            params.width = previewSize;
            params.height = previewSize;
            preview.setLayoutParams(params);
        }
        preview.setPadding(
                Math.max(0, (previewSize - iconSize) / 2),
                Math.max(0, (previewSize - iconSize) / 2),
                Math.max(0, (previewSize - iconSize) / 2),
                Math.max(0, (previewSize - iconSize) / 2)
        );

        if (ControlsPreferences.MOUSE_CURSOR_STYLE_CUSTOM.equals(style) && ControlsPreferences.hasCustomMouseCursorIcon(this)) {
            String path = ControlsPreferences.getCustomMouseCursorPath(this);
            if (path != null) {
                preview.setImageURI(Uri.fromFile(new File(path)));
                return;
            }
        }

        int iconId = getResources().getIdentifier(
                ControlsPreferences.getMouseCursorResourceName(style),
                "drawable",
                getPackageName()
        );
        if (iconId == 0) {
            iconId = getResources().getIdentifier("ic_cursor_arrow", "drawable", getPackageName());
        }
        if (iconId != 0) {
            preview.setImageResource(iconId);
        }
    }

    private void registerNotificationPermissionLauncher() {
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    LauncherNotificationPermissionHelper.setBackgroundInstallNotificationsEnabled(this, granted);
                    updateInstallNotificationSettingsUi();
                    Toast.makeText(
                            this,
                            granted
                                    ? R.string.notification_permission_enabled_toast
                                    : R.string.notification_permission_denied_toast,
                            granted ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG
                    ).show();

                    if (!granted && LauncherNotificationPermissionHelper.requiresRuntimePermission()) {
                        showNotificationDeniedSettingsDialog();
                    }
                }
        );
    }

    private void setupInstallNotificationSettings() {
        updateInstallNotificationSettingsUi();
    }

    private void updateInstallNotificationSettingsUi() {
        if (binding == null || binding.switchInstallNotifications == null) return;

        boolean permissionGranted = LauncherNotificationPermissionHelper.hasPostNotificationsPermission(this);
        boolean enabled = LauncherNotificationPermissionHelper.isBackgroundInstallNotificationsEnabled(this) && permissionGranted;

        binding.switchInstallNotifications.setOnCheckedChangeListener(null);
        binding.switchInstallNotifications.setChecked(enabled);
        binding.switchInstallNotifications.setText(enabled
                ? R.string.install_notifications_on
                : R.string.install_notifications_off);

        if (!LauncherNotificationPermissionHelper.requiresRuntimePermission()) {
            binding.textInstallNotificationsSummary.setText(R.string.install_notifications_summary_old_android);
        } else if (permissionGranted) {
            binding.textInstallNotificationsSummary.setText(R.string.install_notifications_summary_enabled);
        } else {
            binding.textInstallNotificationsSummary.setText(R.string.install_notifications_summary_permission_needed);
        }

        binding.switchInstallNotifications.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isChecked) {
                LauncherNotificationPermissionHelper.setBackgroundInstallNotificationsEnabled(this, false);
                updateInstallNotificationSettingsUi();
                return;
            }

            if (LauncherNotificationPermissionHelper.hasPostNotificationsPermission(this)) {
                LauncherNotificationPermissionHelper.setBackgroundInstallNotificationsEnabled(this, true);
                updateInstallNotificationSettingsUi();
                return;
            }

            LauncherNotificationPermissionHelper.setBackgroundInstallNotificationsEnabled(this, true);
            if (notificationPermissionLauncher != null) {
                LauncherNotificationPermissionHelper.requestPostNotificationsPermission(notificationPermissionLauncher);
            }
        });
    }

    private void showNotificationDeniedSettingsDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.notification_permission_denied_title)
                .setMessage(R.string.notification_permission_denied_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.notification_permission_open_settings, (dialog, which) ->
                        LauncherNotificationPermissionHelper.openAppNotificationSettings(this))
                .show();
    }

    private void registerMicrophonePermissionLauncher() {
        microphonePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    updateSimpleVoiceChatPermissionUi();
                    Toast.makeText(
                            this,
                            granted
                                    ? R.string.simple_voice_chat_permission_granted_toast
                                    : R.string.simple_voice_chat_permission_denied_toast,
                            granted ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG
                    ).show();

                    if (!granted) {
                        showSimpleVoiceChatPermissionDeniedDialog();
                    }
                }
        );
    }

    private void registerMobileGluesFolderPickerLauncher() {
        mobileGluesFolderPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                    Uri treeUri = result.getData().getData();
                    if (treeUri == null) return;
                    try {
                        final int flags = result.getData().getFlags()
                                & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        getContentResolver().takePersistableUriPermission(treeUri, flags);
                    } catch (Throwable ignored) {
                    }

                    MobileGluesConfigHelper.SelectionResult selection = MobileGluesConfigHelper.setSelectedConfigTreeUri(this, treeUri);
                    RendererInterface renderer = getSelectedRendererFromSpinner();
                    updateMobileGluesConfigSummary(renderer);

                    Toast.makeText(
                            this,
                            selection.message,
                            selection.success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG
                    ).show();
                }
        );
    }

    private void registerDroidBridgeBackupFolderPickerLauncher() {
        droidBridgeBackupFolderPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                    Uri treeUri = result.getData().getData();
                    if (treeUri == null) return;

                    try {
                        final int flags = result.getData().getFlags()
                                & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        getContentResolver().takePersistableUriPermission(treeUri, flags);
                    } catch (Throwable ignored) {
                    }

                    startDroidBridgeBackup(treeUri);
                }
        );
    }

    private void registerDroidBridgeRestoreZipPickerLauncher() {
        droidBridgeRestoreZipPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                    Uri zipUri = result.getData().getData();
                    if (zipUri == null) return;

                    try {
                        final int flags = result.getData().getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
                        getContentResolver().takePersistableUriPermission(zipUri, flags);
                    } catch (Throwable ignored) {
                    }

                    confirmDroidBridgeRestore(zipUri);
                }
        );
    }

    private void showDroidBridgeBackupDialog() {
        File launcherHome = resolveCurrentLauncherHome();
        new MaterialAlertDialogBuilder(this)
                .setTitle("Back up DroidBridge data")
                .setMessage("Choose a folder where DroidBridge will create a portable .zip backup of the current data folder. This can include instances, saves, versions, libraries, assets, mods, configs, logs, and account/session files, so choose a private location.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Choose folder", (dialog, which) -> openDroidBridgeBackupFolderPicker())
                .setNeutralButton("View current folder", (dialog, which) -> Toast.makeText(
                        this,
                        launcherHome.getAbsolutePath(),
                        Toast.LENGTH_LONG
                ).show())
                .show();
    }

    private void openDroidBridgeBackupFolderPicker() {
        if (droidBridgeBackupFolderPickerLauncher == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        droidBridgeBackupFolderPickerLauncher.launch(intent);
    }

    private void showDroidBridgeRestoreDialog() {
        File launcherHome = resolveCurrentLauncherHome();
        new MaterialAlertDialogBuilder(this)
                .setTitle("Restore DroidBridge data")
                .setMessage("Choose a DroidBridge backup .zip to restore into the current data folder. This replaces the current DroidBridge data folder and moves the previous data aside as a .before_restore folder.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Choose backup", (dialog, which) -> openDroidBridgeRestoreZipPicker())
                .setNeutralButton("View current folder", (dialog, which) -> Toast.makeText(
                        this,
                        launcherHome.getAbsolutePath(),
                        Toast.LENGTH_LONG
                ).show())
                .show();
    }

    private void openDroidBridgeRestoreZipPicker() {
        if (droidBridgeRestoreZipPickerLauncher == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip",
                "application/octet-stream",
                "application/x-zip-compressed"
        });
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        droidBridgeRestoreZipPickerLauncher.launch(intent);
    }

    private void confirmDroidBridgeRestore(@NonNull Uri backupZipUri) {
        File launcherHome = resolveCurrentLauncherHome();
        new MaterialAlertDialogBuilder(this)
                .setTitle("Restore this backup?")
                .setMessage("DroidBridge will restore this backup into:\n\n"
                        + launcherHome.getAbsolutePath()
                        + "\n\nThe current data folder will be moved aside first, so you can recover it manually if needed. Do not launch Minecraft while restore is running.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Restore", (dialog, which) -> startDroidBridgeRestore(backupZipUri))
                .show();
    }

    private void startDroidBridgeRestore(@NonNull Uri backupZipUri) {
        File launcherHome = resolveCurrentLauncherHome();
        binding.buttonBackupDroidBridgeData.setEnabled(false);
        binding.buttonRestoreDroidBridgeData.setEnabled(false);
        updateDroidBridgeBackupSummary("Restoring backup... Keep DroidBridge open until this finishes.");

        Thread thread = new Thread(() -> {
            try {
                DroidBridgeBackupManager.RestoreResult result = DroidBridgeBackupManager.restoreBackup(
                        getApplicationContext(),
                        backupZipUri,
                        launcherHome,
                        message -> runOnUiThread(() -> updateDroidBridgeBackupSummary(message))
                );

                runOnUiThread(() -> {
                    PathManager.initContextConstants(this);
                    binding.textFolder.setText(getString(R.string.launcher_folder_value, PathManager.DIR_MINECRAFT_HOME));
                    binding.buttonBackupDroidBridgeData.setEnabled(true);
                    binding.buttonRestoreDroidBridgeData.setEnabled(true);
                    String summary = "Restore complete: " + result.fileCount + " files"
                            + " • " + DroidBridgeBackupManager.formatBytes(result.byteCount);
                    if (result.previousDataPath != null && !result.previousDataPath.trim().isEmpty()) {
                        summary += "\nPrevious data moved to: " + result.previousDataPath;
                    }
                    updateDroidBridgeBackupSummary(summary);
                    Toast.makeText(this, "DroidBridge backup restored.", Toast.LENGTH_LONG).show();
                });
            } catch (Throwable throwable) {
                runOnUiThread(() -> {
                    binding.buttonBackupDroidBridgeData.setEnabled(true);
                    binding.buttonRestoreDroidBridgeData.setEnabled(true);
                    String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.toString();
                    updateDroidBridgeBackupSummary("Restore failed: " + message);
                    Toast.makeText(this, "Restore failed: " + message, Toast.LENGTH_LONG).show();
                });
            }
        }, "DroidBridgeRestore");
        thread.start();
    }

    private void startDroidBridgeBackup(@NonNull Uri treeUri) {
        File launcherHome = resolveCurrentLauncherHome();
        if (!launcherHome.isDirectory()) {
            Toast.makeText(this, "DroidBridge data folder was not found.", Toast.LENGTH_LONG).show();
            updateDroidBridgeBackupSummary("Backup unavailable. Data folder was not found.");
            return;
        }

        binding.buttonBackupDroidBridgeData.setEnabled(false);
        binding.buttonRestoreDroidBridgeData.setEnabled(false);
        updateDroidBridgeBackupSummary("Creating backup... Keep DroidBridge open until this finishes.");

        Thread thread = new Thread(() -> {
            try {
                DroidBridgeBackupManager.Result result = DroidBridgeBackupManager.createBackup(
                        getApplicationContext(),
                        launcherHome,
                        treeUri,
                        message -> runOnUiThread(() -> updateDroidBridgeBackupSummary(message))
                );

                runOnUiThread(() -> {
                    binding.buttonBackupDroidBridgeData.setEnabled(true);
                    binding.buttonRestoreDroidBridgeData.setEnabled(true);
                    String summary = "Backup complete: " + result.fileName
                            + " • " + result.fileCount + " files"
                            + " • " + DroidBridgeBackupManager.formatBytes(result.byteCount);
                    updateDroidBridgeBackupSummary(summary);
                    Toast.makeText(this, "DroidBridge backup created.", Toast.LENGTH_LONG).show();
                });
            } catch (Throwable throwable) {
                runOnUiThread(() -> {
                    binding.buttonBackupDroidBridgeData.setEnabled(true);
                    binding.buttonRestoreDroidBridgeData.setEnabled(true);
                    String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.toString();
                    updateDroidBridgeBackupSummary("Backup failed: " + message);
                    Toast.makeText(this, "Backup failed: " + message, Toast.LENGTH_LONG).show();
                });
            }
        }, "DroidBridgeBackup");
        thread.start();
    }

    @NonNull
    private File resolveCurrentLauncherHome() {
        File minecraftHome = new File(PathManager.DIR_MINECRAFT_HOME);
        File parent = minecraftHome.getParentFile();
        if (parent != null && parent.isDirectory()) return parent;
        return minecraftHome;
    }

    private void updateDroidBridgeBackupSummary() {
        File launcherHome = resolveCurrentLauncherHome();
        updateDroidBridgeBackupSummary("Current data folder: " + launcherHome.getAbsolutePath());
    }

    private void updateDroidBridgeBackupSummary(@NonNull String text) {
        if (binding != null && binding.textDroidBridgeBackupSummary != null) {
            binding.textDroidBridgeBackupSummary.setText(text);
        }
    }

    private void setupSimpleVoiceChatSettings() {
        updateSimpleVoiceChatPermissionUi();
        binding.buttonSimpleVoiceChatMicrophonePermission.setOnClickListener(view -> {
            if (AndroidMicrophonePermission.isGranted(this)) {
                showSimpleVoiceChatPermissionGrantedDialog();
                return;
            }

            if (microphonePermissionLauncher != null) {
                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            } else {
                AndroidMicrophonePermission.showRequestDialog(this);
            }
        });
    }

    private void updateSimpleVoiceChatPermissionUi() {
        if (binding == null
                || binding.buttonSimpleVoiceChatMicrophonePermission == null
                || binding.textSimpleVoiceChatMicrophoneStatus == null) {
            return;
        }

        boolean granted = AndroidMicrophonePermission.isGranted(this);
        binding.textSimpleVoiceChatMicrophoneStatus.setText(granted
                ? R.string.simple_voice_chat_microphone_status_granted
                : R.string.simple_voice_chat_microphone_status_missing);
        binding.buttonSimpleVoiceChatMicrophonePermission.setText(granted
                ? R.string.simple_voice_chat_microphone_button_enabled
                : R.string.simple_voice_chat_microphone_button_enable);
    }

    private void showSimpleVoiceChatPermissionGrantedDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.simple_voice_chat_microphone_title)
                .setMessage(R.string.simple_voice_chat_microphone_already_granted)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showSimpleVoiceChatPermissionDeniedDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.simple_voice_chat_microphone_title)
                .setMessage(R.string.simple_voice_chat_microphone_denied_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.simple_voice_chat_open_app_settings, (dialog, which) ->
                        AndroidMicrophonePermission.openAppSettings(this))
                .show();
    }

    private void setupLauncherSettings() {
        setupMemorySettings();
        setupInstallNotificationSettings();
        setupSimpleVoiceChatSettings();
        setupRuntimeComponentsReinstallSettings();
        setupGameAssetCleanupSettings();
        setupBmclApiDownloadSourceSettings();
        setupJarExecutionSettings();
        setupLauncherThemeSettings();

        binding.checkKeepLogs.setChecked(LauncherLogManager.isKeepLogHistoryEnabled(this));
        binding.checkKeepLogs.setOnCheckedChangeListener((buttonView, isChecked) ->
                LauncherLogManager.setKeepLogHistoryEnabled(this, isChecked));

        boolean launcherLogsEnabled = LauncherDiagnosticLog.isEnabled(this);
        binding.switchLauncherDiagnosticLogs.setChecked(launcherLogsEnabled);
        updateLauncherDiagnosticLogsSwitchText(launcherLogsEnabled);
        binding.switchLauncherDiagnosticLogs.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherDiagnosticLog.setEnabled(this, isChecked);
            updateLauncherDiagnosticLogsSwitchText(isChecked);
        });

        boolean shareLogChooserEnabled = LauncherPreferences.isShareLogChooserEnabled(this);
        binding.switchShareLogChooser.setChecked(shareLogChooserEnabled);
        updateShareLogChooserSwitchText(shareLogChooserEnabled);
        binding.switchShareLogChooser.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherPreferences.setShareLogChooserEnabled(this, isChecked);
            updateShareLogChooserSwitchText(isChecked);
        });

        boolean showInGameSettingsButton = LauncherPreferences.isShowInGameSettingsButton(this);
        binding.switchShowInGameSettingsButton.setChecked(showInGameSettingsButton);
        updateInGameSettingsButtonSwitchText(showInGameSettingsButton);
        binding.switchShowInGameSettingsButton.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherPreferences.setShowInGameSettingsButton(this, isChecked);
            updateInGameSettingsButtonSwitchText(isChecked);
        });

        boolean dualScreenSupport = LauncherPreferences.isDualScreenSupportEnabled(this);
        binding.switchDualScreenSupport.setChecked(dualScreenSupport);
        updateDualScreenSupportSwitchText(dualScreenSupport);

        boolean dualScreenSwapped = LauncherPreferences.isDualScreenLastSwapRequest(this);
        binding.switchDualScreenSwap.setChecked(dualScreenSwapped);
        binding.switchDualScreenSwap.setEnabled(dualScreenSupport);
        updateDualScreenSwapSwitchText(dualScreenSwapped);

        boolean dualScreenFourThree = LauncherPreferences.isDualScreenFourThreeLayout(this);
        binding.switchDualScreenAspectRatio.setChecked(dualScreenFourThree);
        binding.switchDualScreenAspectRatio.setEnabled(dualScreenSupport);
        updateDualScreenAspectRatioSwitchText();

        boolean dualScreenFps = LauncherPreferences.isDualScreenFpsEnabled(this);
        binding.switchDualScreenFps.setChecked(dualScreenFps);
        binding.switchDualScreenFps.setEnabled(dualScreenSupport);
        updateDualScreenFpsSwitchText();

        binding.switchDualScreenSupport.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherPreferences.setDualScreenSupportEnabled(this, isChecked);
            updateDualScreenSupportSwitchText(isChecked);
            binding.switchDualScreenSwap.setEnabled(isChecked);
            binding.switchDualScreenAspectRatio.setEnabled(isChecked);
            binding.switchDualScreenFps.setEnabled(isChecked);
            if (buttonDualScreenHudLayout != null) buttonDualScreenHudLayout.setEnabled(isChecked);
            if (buttonDualScreenTouchScale != null) buttonDualScreenTouchScale.setEnabled(isChecked);
            if (buttonDualScreenBackground != null) buttonDualScreenBackground.setEnabled(isChecked);
            if (buttonDualScreenMapFrame != null) buttonDualScreenMapFrame.setEnabled(isChecked);
            refreshRecordingDualScreenOption();
        });
        binding.switchDualScreenSwap.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // This is an absolute desired layout, not a one-shot toggle command.
            // ON and OFF are both persisted together with the request to use the
            // external/second display so GameActivity can reapply it on resume.
            LauncherPreferences.setDualScreenSwapState(this, isChecked);
            updateDualScreenSwapSwitchText(isChecked);
        });
        binding.switchDualScreenAspectRatio.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherPreferences.setDualScreenAspectRatio(
                    this,
                    isChecked
                            ? LauncherPreferences.DUAL_SCREEN_ASPECT_RATIO_4_3
                            : LauncherPreferences.DUAL_SCREEN_ASPECT_RATIO_16_9
            );
            updateDualScreenAspectRatioSwitchText();
        });
        binding.switchDualScreenFps.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherPreferences.setDualScreenFpsEnabled(this, isChecked);
            updateDualScreenFpsSwitchText();
        });
        installDualScreenHudLayoutSetting();
        installDualScreenTouchScaleSetting();
        installDualScreenBackgroundSetting();
        installDualScreenMapFrameSetting();

        updateAndroidBackButtonActionButtonText();
        binding.buttonAndroidBackAction.setOnClickListener(view -> showAndroidBackButtonActionDialog());
        updateInGameMenuKeyboardShortcutButtonText();
        binding.buttonInGameMenuKeyboardShortcut.setOnClickListener(
                view -> showInGameMenuKeyboardShortcutDialog());

        setupOrientationSettings();
        setupImeViewportPushSetting();

        boolean showGameLogOverlay = LauncherPreferences.isShowGameLogOverlay(this);
        binding.switchShowGameLogOverlay.setChecked(showGameLogOverlay);
        updateGameLogOverlaySwitchText(showGameLogOverlay);
        binding.switchShowGameLogOverlay.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherPreferences.setShowGameLogOverlay(this, isChecked);
            updateGameLogOverlaySwitchText(isChecked);
        });

        setupFloatingGameOverlaySettings();

        binding.buttonShareLatestLog.setOnClickListener(view -> LauncherLogManager.shareLatestLog(this));
        binding.buttonShareLauncherLogs.setOnClickListener(view -> LauncherDiagnosticLog.share(this));
        setupUpdateCheckerSettings();
    }



    private void setupBmclApiDownloadSourceSettings() {
        if (binding == null || binding.switchUseBmclApi == null) return;

        updateBmclApiSwitch(LauncherPreferences.isUseBmclApi(this));
        binding.switchUseBmclApi.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingBmclApiSwitch) return;

            if (!isChecked) {
                LauncherPreferences.setUseBmclApi(this, false);
                updateBmclApiSwitch(false);
                return;
            }

            // Keep this opt-in behind a real Microsoft Minecraft session. The
            // mirror changes only download routing; it never bypasses login or
            // ownership checks.
            updateBmclApiSwitch(false);
            AccountStore.Account microsoftAccount = accountStore != null
                    ? accountStore.loadLastMicrosoftAccount()
                    : new AccountStore(this).loadLastMicrosoftAccount();
            if (microsoftAccount == null || !microsoftAccount.hasMinecraftSession()) {
                showStyledBmclApiMessageDialog(
                        R.string.bmclapi_requires_microsoft_title,
                        R.string.bmclapi_requires_microsoft_message,
                        android.R.string.ok,
                        false,
                        null
                );
                return;
            }

            showStyledBmclApiMessageDialog(
                    R.string.bmclapi_enable_title,
                    R.string.bmclapi_enable_message,
                    R.string.bmclapi_enable_confirm,
                    true,
                    () -> {
                        LauncherPreferences.setUseBmclApi(this, true);
                        updateBmclApiSwitch(true);
                    }
            );
        });
    }

    private void updateBmclApiSwitch(boolean enabled) {
        if (binding == null || binding.switchUseBmclApi == null) return;
        updatingBmclApiSwitch = true;
        try {
            binding.switchUseBmclApi.setChecked(enabled);
            binding.switchUseBmclApi.setText(enabled
                    ? R.string.bmclapi_download_source_on
                    : R.string.bmclapi_download_source_off);
        } finally {
            updatingBmclApiSwitch = false;
        }
    }

    private void showStyledBmclApiMessageDialog(
            int titleResId,
            int messageResId,
            int positiveButtonResId,
            boolean showCancelButton,
            @Nullable Runnable positiveAction
    ) {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(COLOR_DIALOG_BG);
        scrollView.setVerticalScrollBarEnabled(true);

        createStyledDialogRoot(
                scrollView,
                getString(titleResId),
                getString(messageResId)
        );

        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .setPositiveButton(positiveButtonResId, null);
        if (showCancelButton) {
            builder.setNegativeButton(android.R.string.cancel, null);
        }

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(dialogInterface -> {
            styleLauncherDialogChrome(dialog);
            TextView positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positiveButton != null) {
                positiveButton.setOnClickListener(view -> {
                    if (positiveAction != null) {
                        positiveAction.run();
                    }
                    dialog.dismiss();
                });
            }
        });
        dialog.show();
    }

    private void setupLauncherThemeSettings() {
        if (binding == null || binding.buttonLauncherTheme == null) return;
        updateLauncherThemeSettingsUi();
        binding.buttonLauncherTheme.setOnClickListener(view -> showLauncherThemeDialog());
    }

    private void updateLauncherThemeSettingsUi() {
        if (binding == null || binding.buttonLauncherTheme == null || binding.textLauncherThemeSummary == null) return;
        String current = LauncherPreferences.getLauncherTheme(this);
        String label = LauncherTheme.getDisplayName(current);
        binding.buttonLauncherTheme.setText(label);
        binding.textLauncherThemeSummary.setText(
                "Selected: " + label + " • Changes launcher light/dark appearance, buttons, tabs, icons, highlights, and the main launcher screen after returning from settings."
        );
    }

    private void showLauncherThemeDialog() {
        String[] values = LauncherTheme.values();
        ArrayList<String> labels = new ArrayList<>();
        String current = LauncherPreferences.getLauncherTheme(this);
        int selectedIndex = 0;
        for (int i = 0; i < values.length; i++) {
            labels.add(LauncherTheme.getDisplayName(values[i]));
            if (values[i].equals(current)) selectedIndex = i;
        }

        showStyledSingleChoiceDialog(
                "Launcher theme",
                "Choose the normal Light/Dark appearance or an accent theme DroidBridge uses across the launcher. MainActivity refreshes itself when you return from settings. Rainbow also adds a rainbow launcher background.",
                labels,
                selectedIndex,
                position -> {
                    if (position < 0 || position >= values.length) return;
                    LauncherPreferences.setLauncherTheme(this, values[position]);
                    setResult(RESULT_OK);
                    updateLauncherThemeSettingsUi();
                    Toast.makeText(this, "Launcher theme changed to " + LauncherTheme.getDisplayName(values[position]), Toast.LENGTH_SHORT).show();
                    recreate();
                }
        );
    }

    private void setupGameAssetCleanupSettings() {
        if (binding == null || binding.buttonCleanupGameFiles == null) return;
        binding.buttonCleanupGameFiles.setOnClickListener(view -> showGameAssetCleanupDialog());
    }

    private void showGameAssetCleanupDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.cleanup_game_files_title)
                .setMessage(R.string.cleanup_game_files_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.cleanup_game_files_confirm, (dialog, which) -> runGameAssetCleanup())
                .show();
    }

    private void runGameAssetCleanup() {
        setGameAssetCleanupButtonRunning(true);
        Thread thread = new Thread(() -> {
            try {
                GameAssetCleanupManager.CleanupResult result = GameAssetCleanupManager.cleanUnusedAssets(this);
                runOnUiThread(() -> {
                    setGameAssetCleanupButtonRunning(false);
                    showGameAssetCleanupResultDialog(result);
                });
            } catch (Throwable throwable) {
                runOnUiThread(() -> {
                    setGameAssetCleanupButtonRunning(false);
                    String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.toString();
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.cleanup_game_files_failed_title)
                            .setMessage(getString(R.string.cleanup_game_files_failed_message, message))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
            }
        }, "DroidBridgeAssetCleanup");
        thread.start();
    }

    private void setGameAssetCleanupButtonRunning(boolean running) {
        if (binding == null || binding.buttonCleanupGameFiles == null) return;
        binding.buttonCleanupGameFiles.setEnabled(!running);
        binding.buttonCleanupGameFiles.setText(running
                ? R.string.cleanup_game_files_running
                : R.string.button_cleanup_game_files);
    }

    private void showGameAssetCleanupResultDialog(@NonNull GameAssetCleanupManager.CleanupResult result) {
        StringBuilder message = new StringBuilder();
        message.append(result.getMessage());
        message.append("\n\nRemoved: ")
                .append(result.getDeletedFileCount())
                .append(" files (")
                .append(GameAssetCleanupManager.formatBytes(result.getDeletedByteCount()))
                .append(")");
        message.append("\nScanned asset files: ").append(result.getScannedFileCount());
        message.append("\nKept required files: ").append(result.getKeptFileCount());
        message.append("\nRequired asset targets: ").append(result.getRequiredFileCount());
        message.append("\nReadable asset indexes: ").append(result.getAssetIndexCount());

        if (result.getSkippedVersionCount() > 0) {
            message.append("\nSkipped version JSONs: ").append(result.getSkippedVersionCount());
        }
        if (result.getMissingAssetIndexCount() > 0) {
            message.append("\nMissing asset indexes: ").append(result.getMissingAssetIndexCount());
        }
        if (result.getFailedDeleteCount() > 0) {
            message.append("\nFailed deletions: ").append(result.getFailedDeleteCount());
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.cleanup_game_files_complete_title)
                .setMessage(message.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }


    private void registerJarExecutionPickerLauncher() {
        jarExecutionPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                    Uri uri = result.getData().getData();
                    if (uri == null) return;
                    confirmExecuteJarFile(uri);
                }
        );
    }


    private void registerCustomRuntimeArchivePickerLauncher() {
        customRuntimeArchivePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                    Uri uri = result.getData().getData();
                    if (uri == null) return;
                    String targetRuntime = pendingCustomRuntimeName;
                    pendingCustomRuntimeName = null;
                    if (isNullOrBlank(targetRuntime)) {
                        Toast.makeText(this, "Choose a target runtime first.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    startCustomRuntimeInstall(uri, targetRuntime);
                }
        );
    }

    private void showCustomRuntimeTargetDialog() {
        if (runtimeComponentsReinstalling) {
            Toast.makeText(this, "Runtime/component install is already running.", Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> runtimeNames = Arrays.asList("Internal-8", "Internal-17", "Internal-21", "Internal-25");
        showStyledSingleChoiceDialog(
                "Install custom runtime",
                "Choose which internal runtime slot the selected runtime archive should replace. Use this only with trusted Android Java runtime archives.",
                runtimeNames,
                Math.max(0, runtimeNames.indexOf("Internal-25")),
                position -> {
                    if (position < 0 || position >= runtimeNames.size()) return;
                    pendingCustomRuntimeName = runtimeNames.get(position);
                    openCustomRuntimeArchivePicker();
                }
        );
    }

    private void openCustomRuntimeArchivePicker() {
        if (customRuntimeArchivePickerLauncher == null) return;

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip",
                "application/x-zip-compressed",
                "application/x-xz",
                "application/octet-stream"
        });
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        customRuntimeArchivePickerLauncher.launch(intent);
    }

    private void startCustomRuntimeInstall(@NonNull Uri uri, @NonNull String targetRuntime) {
        if (runtimeComponentsReinstalling) return;

        final String displayName = getDocumentDisplayName(uri, "custom-runtime.zip");
        runtimeComponentsReinstalling = true;
        setRuntimeRepairButtonsEnabled(false);
        updateRuntimeComponentsReinstallSummary("Installing custom runtime into " + targetRuntime + " from " + displayName + "...");

        Thread thread = new Thread(() -> {
            try {
                PathManager.initContextConstants(getApplicationContext());
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) {
                        throw new IOException("Unable to open selected runtime archive.");
                    }
                    String version = "Custom runtime from " + displayName + "\nInstalled by DroidBridge at " + System.currentTimeMillis();
                    MultiRTUtils.installRuntimeFromArchive(input, targetRuntime, version, displayName);
                }

                File runtimeDir = MultiRTUtils.getRuntimeDir(targetRuntime);
                int javaMajor = RuntimeCompat.javaMajorForRuntimeName(targetRuntime);
                if (!RuntimeCompat.isRuntimeInstalledForJava(targetRuntime, runtimeDir, javaMajor)) {
                    throw new IOException("Runtime installed but did not validate: "
                            + RuntimeCompat.describeRuntimeState(targetRuntime, runtimeDir));
                }

                runOnUiThread(() -> {
                    runtimeComponentsReinstalling = false;
                    setRuntimeRepairButtonsEnabled(true);
                    updateRuntimeComponentsReinstallSummary("Custom runtime installed into " + targetRuntime + ". Restart DroidBridge before launching Minecraft.");
                    Toast.makeText(this, "Custom runtime installed into " + targetRuntime, Toast.LENGTH_LONG).show();
                });
            } catch (Throwable throwable) {
                Logging.e("LauncherSettings", "Custom runtime install failed", throwable);
                runOnUiThread(() -> {
                    runtimeComponentsReinstalling = false;
                    setRuntimeRepairButtonsEnabled(true);
                    String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.toString();
                    updateRuntimeComponentsReinstallSummary("Custom runtime install failed: " + message);
                    Toast.makeText(this, "Custom runtime install failed: " + message, Toast.LENGTH_LONG).show();
                });
            }
        }, "DroidBridgeCustomRuntimeInstall");
        thread.start();
    }

    private void setRuntimeRepairButtonsEnabled(boolean enabled) {
        if (binding == null) return;
        if (binding.buttonReinstallRuntimeComponents != null) {
            binding.buttonReinstallRuntimeComponents.setEnabled(enabled);
            binding.buttonReinstallRuntimeComponents.setText(enabled ? "Reinstall components and JREs" : "Working...");
        }
        if (binding.buttonInstallCustomRuntime != null) {
            binding.buttonInstallCustomRuntime.setEnabled(enabled);
            binding.buttonInstallCustomRuntime.setText(enabled ? "Install custom runtime" : "Installing...");
        }
    }

    @NonNull
    private String getDocumentDisplayName(@NonNull Uri uri, @NonNull String fallbackName) {
        String name = null;
        android.database.Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) name = cursor.getString(index);
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }

        if (isNullOrBlank(name)) {
            String path = uri.getLastPathSegment();
            if (!isNullOrBlank(path)) {
                int slash = path.lastIndexOf('/');
                name = slash >= 0 ? path.substring(slash + 1) : path;
            }
        }

        return isNullOrBlank(name) ? fallbackName : name;
    }

    private void setupJarExecutionSettings() {
        if (binding == null || binding.buttonExecuteJarFile == null) return;

        String lastSummary = getSharedPreferences(JAR_EXECUTION_PREFS, MODE_PRIVATE)
                .getString(JAR_EXECUTION_LAST_SUMMARY_KEY, null);
        updateJarExecutionSummary(isNullOrBlank(lastSummary)
                ? "Choose a .jar file to run with DroidBridge's bundled Java runtime. Useful for loader installer jars and other trusted Java tools."
                : lastSummary);
        binding.buttonExecuteJarFile.setOnClickListener(view -> openJarExecutionPicker());
    }

    private void openJarExecutionPicker() {
        if (jarExecutionPickerLauncher == null) return;

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/java-archive",
                "application/x-java-archive",
                "application/octet-stream",
                "application/zip"
        });
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        jarExecutionPickerLauncher.launch(intent);
    }

    private void confirmExecuteJarFile(@NonNull Uri jarUri) {
        String displayName = getJarDisplayName(jarUri);
        if (!displayName.toLowerCase(java.util.Locale.US).endsWith(".jar")) {
            Toast.makeText(this, "Choose a .jar file.", Toast.LENGTH_LONG).show();
            return;
        }

        if (isOptiFineInstallerName(displayName.toLowerCase(Locale.US))) {
            showOptiFineNativeActionDialog(jarUri, displayName);
            return;
        }

        if (isBtaInstallerName(displayName.toLowerCase(Locale.US))) {
            showBtaNativeInstallerDialog(displayName);
            return;
        }

        if (isFabricInstallerName(displayName.toLowerCase(Locale.US))) {
            boolean legacyFabricInstaller = isLegacyFabricInstallerJar(jarUri, displayName);
            showFabricNativeInstallerDialog(jarUri, displayName, legacyFabricInstaller);
            return;
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(COLOR_DIALOG_BG);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setScrollbarFadingEnabled(false);

        LinearLayout root = createStyledDialogRoot(
                scrollView,
                "Execute Java .jar",
                "Run a trusted Java .jar with DroidBridge's internal Java runtime."
        );

        String defaultArguments = buildDefaultJarArguments(displayName);
        String argumentsHint = buildJarArgumentsHint(displayName);

        LinearLayout card = addStyledDialogCard(root);
        addStyledDialogCardTitle(card, displayName);
        addStyledDialogInfoText(card,
                "This runs the jar as a loader installer command, not through the broken Cacio/JLI GUI bridge.\n\n"
                        + "Zalith does not treat loader installs as a blind desktop GUI jar launch. It builds loader-specific arguments first, then runs the installer with the target game directory already known. "
                        + "DroidBridge now follows that same direction here: Fabric/Quilt get their CLI installer syntax and Forge/NeoForge use --installClient. "
                        + "OptiFine is handled as its own standalone loader profile, matching Zalith's flow. BTA installer jars are also handled natively now: DroidBridge downloads BTA's manifest, shows tested/untested/nightly versions, writes the BTA version JSON, and creates a DroidBridge instance without launching the Swing installer.");

        TextView argsTitle = new TextView(this);
        argsTitle.setText("Optional arguments");
        argsTitle.setTextColor(COLOR_TEXT_PRIMARY);
        argsTitle.setTextSize(14f);
        argsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams argsTitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        argsTitleParams.topMargin = dp(12);
        card.addView(argsTitle, argsTitleParams);

        EditText argumentsInput = new EditText(this);
        argumentsInput.setSingleLine(false);
        argumentsInput.setMinLines(1);
        argumentsInput.setMaxLines(4);
        argumentsInput.setHint(argumentsHint);
        argumentsInput.setText(defaultArguments);
        argumentsInput.setSelectAllOnFocus(true);
        argumentsInput.setTextColor(COLOR_TEXT_PRIMARY);
        argumentsInput.setHintTextColor(COLOR_TEXT_MUTED);
        argumentsInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        LinearLayout.LayoutParams argsInputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        argsInputParams.topMargin = dp(4);
        card.addView(argumentsInput, argsInputParams);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Run jar", null)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            styleLauncherDialogChrome(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String argsText = argumentsInput.getText() == null ? "" : argumentsInput.getText().toString();
                dialog.dismiss();
                startJarExecution(jarUri, displayName, argsText);
            });
        });

        dialog.show();
    }


    private boolean isLegacyFabricInstallerJar(@NonNull Uri jarUri, @NonNull String displayName) {
        String lowerName = displayName.toLowerCase(Locale.US);
        if (lowerName.contains("legacyfabric") || lowerName.contains("legacy-fabric") || lowerName.contains("legacy_fabric")) {
            return true;
        }

        try (InputStream rawInput = getContentResolver().openInputStream(jarUri)) {
            if (rawInput == null) return false;
            java.util.jar.JarInputStream jarInput = new java.util.jar.JarInputStream(rawInput);
            try {
                java.util.jar.Manifest manifest = jarInput.getManifest();
                if (manifest != null) {
                    String title = manifest.getMainAttributes().getValue("Implementation-Title");
                    String version = manifest.getMainAttributes().getValue("Implementation-Version");
                    String combined = (title == null ? "" : title) + " " + (version == null ? "" : version);
                    if (combined.toLowerCase(Locale.US).contains("legacyfabric")) return true;
                }

                java.util.jar.JarEntry entry;
                while ((entry = jarInput.getNextJarEntry()) != null) {
                    String name = entry.getName();
                    if (name == null) continue;
                    String lowerEntry = name.toLowerCase(Locale.US);
                    if (lowerEntry.equals("net/fabricmc/installer/util/reference.class")
                            || lowerEntry.equals("net/fabricmc/installer/main.class")) {
                        byte[] bytes = readSmallJarEntryBytes(jarInput, 96 * 1024);
                        String text = new String(bytes, StandardCharsets.ISO_8859_1).toLowerCase(Locale.US);
                        if (text.contains("meta.legacyfabric.net")
                                || text.contains("maven.legacyfabric.net")
                                || text.contains("legacy fabric installer")) {
                            return true;
                        }
                    }
                }
            } finally {
                try {
                    jarInput.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable throwable) {
            Logging.i("LauncherSettings", "Unable to inspect Fabric installer jar; using normal Fabric mode: " + throwable.getMessage());
        }
        return false;
    }

    @NonNull
    private byte[] readSmallJarEntryBytes(@NonNull InputStream input, int maxBytes) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            int allowed = Math.min(read, Math.max(0, maxBytes - total));
            if (allowed > 0) {
                output.write(buffer, 0, allowed);
                total += allowed;
            }
            if (total >= maxBytes) break;
        }
        return output.toByteArray();
    }


    private void showFabricNativeInstallerDialog(@NonNull Uri jarUri, @NonNull String displayName, boolean legacyFabricInstaller) {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(COLOR_DIALOG_BG);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setScrollbarFadingEnabled(false);

        LinearLayout root = createStyledDialogRoot(
                scrollView,
                legacyFabricInstaller ? "Install Legacy Fabric" : "Install Fabric",
                legacyFabricInstaller
                        ? "DroidBridge will use Legacy Fabric's supported Minecraft list instead of the normal Fabric version list."
                        : "DroidBridge will use a native version picker instead of making you type Fabric installer arguments manually."
        );

        LinearLayout card = addStyledDialogCard(root);
        addStyledDialogCardTitle(card, displayName);
        addStyledDialogInfoText(card,
                legacyFabricInstaller
                        ? "This jar is the Legacy Fabric installer. Legacy Fabric does not support every Minecraft version, so DroidBridge will only show the official supported versions from the Legacy Fabric downloads/instance list, then it will download Legacy Fabric loader versions and run the installer with the correct Legacy Fabric metadata and Maven URLs."
                        : "DroidBridge will download Fabric's Minecraft and loader version lists, let you choose the target Minecraft version and Fabric Loader version, then run this installer with the correct command-line arguments. "
                        + "After the installer finishes, DroidBridge will create a normal Fabric instance from the installed profile.");

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Choose version", null)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            styleLauncherDialogChrome(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                dialog.dismiss();
                loadFabricVersionListAndShowPicker(jarUri, displayName, legacyFabricInstaller);
            });
        });
        dialog.show();
    }

    private void loadFabricVersionListAndShowPicker(@NonNull Uri jarUri, @NonNull String displayName, boolean legacyFabricInstaller) {
        showJarExecutionProgressDialog(legacyFabricInstaller ? "Legacy Fabric" : "Fabric");
        appendJarExecutionProgressLine(legacyFabricInstaller ? "Downloading Legacy Fabric version lists..." : "Downloading Fabric version lists...");
        updateJarExecutionSummary(legacyFabricInstaller ? "Downloading Legacy Fabric version lists..." : "Downloading Fabric version lists...");

        Thread thread = new Thread(() -> {
            try {
                FabricVersionList versionList = downloadFabricVersionList(legacyFabricInstaller);
                if (versionList.isEmpty()) {
                    throw new IllegalStateException(legacyFabricInstaller
                            ? "No Legacy Fabric versions were returned."
                            : "No Fabric versions were returned by the Fabric meta API.");
                }
                runOnUiThread(() -> {
                    if (jarExecutionProgressDialog != null && jarExecutionProgressDialog.isShowing()) {
                        jarExecutionProgressDialog.dismiss();
                    }
                    jarExecutionProgressDialog = null;
                    jarExecutionProgressTitleView = null;
                    jarExecutionProgressStatusView = null;
                    jarExecutionProgressLogView = null;
                    showFabricMinecraftVersionPicker(jarUri, displayName, versionList, legacyFabricInstaller);
                });
            } catch (Throwable throwable) {
                Logging.e("LauncherSettings", legacyFabricInstaller ? "Unable to download Legacy Fabric version list" : "Unable to download Fabric version list", throwable);
                String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.toString();
                runOnUiThread(() -> {
                    String summary = (legacyFabricInstaller ? "Unable to download Legacy Fabric versions: " : "Unable to download Fabric versions: ") + message;
                    updateJarExecutionSummary(summary);
                    finishJarExecutionProgressDialog(summary, false);
                    Toast.makeText(this, summary, Toast.LENGTH_LONG).show();
                });
            }
        }, legacyFabricInstaller ? "DroidBridgeLegacyFabricVersionList" : "DroidBridgeFabricVersionList");
        thread.start();
    }

    @NonNull
    private FabricVersionList downloadFabricVersionList(boolean legacyFabricInstaller) throws Exception {
        ArrayList<FabricMinecraftVersion> stableMinecraftVersions = new ArrayList<>();
        ArrayList<FabricMinecraftVersion> snapshotMinecraftVersions = new ArrayList<>();
        ArrayList<FabricLoaderVersion> stableLoaders = new ArrayList<>();
        ArrayList<FabricLoaderVersion> unstableLoaders = new ArrayList<>();

        if (legacyFabricInstaller) {
            appendJarExecutionProgressLine("Preparing Legacy Fabric supported Minecraft list...");
            addLegacyFabricSupportedMinecraftVersions(stableMinecraftVersions);

            // The Legacy Fabric website/downloads expose only a small supported set.
            // Do not use the normal Fabric game list here, otherwise users can select
            // unsupported versions and end up with broken installs.
            try {
                appendJarExecutionProgressLine("Checking Legacy Fabric game metadata...");
                JSONArray legacyGameVersions = new JSONArray(httpGetTextForJarInstaller(LEGACY_FABRIC_GAME_VERSIONS_URL));
                HashSet<String> metaVersions = new HashSet<>();
                for (int i = 0; i < legacyGameVersions.length(); i++) {
                    JSONObject item = legacyGameVersions.optJSONObject(i);
                    if (item == null) continue;
                    String version = item.optString("version", item.optString("id", "")).trim();
                    if (!version.isEmpty()) metaVersions.add(version);
                }
                if (!metaVersions.isEmpty()) {
                    for (FabricMinecraftVersion version : stableMinecraftVersions) {
                        if (!metaVersions.contains(version.version)) {
                            appendJarExecutionProgressLine("Keeping supported Legacy Fabric version " + version.version + " even though it was not returned by the metadata endpoint.");
                        }
                    }
                }
            } catch (Throwable throwable) {
                Logging.i("LauncherSettings", "Legacy Fabric game metadata unavailable; using bundled supported list: " + throwable.getMessage());
                appendJarExecutionProgressLine("Legacy Fabric game metadata unavailable; using bundled supported list.");
            }

            appendJarExecutionProgressLine("Downloading Legacy Fabric Loader list...");
            JSONArray loaderVersions = new JSONArray(httpGetTextForJarInstaller(LEGACY_FABRIC_LOADER_VERSIONS_URL));
            for (int i = 0; i < loaderVersions.length(); i++) {
                JSONObject item = loaderVersions.optJSONObject(i);
                if (item == null) continue;
                String version = item.optString("version", "").trim();
                if (version.isEmpty()) continue;
                FabricLoaderVersion loader = new FabricLoaderVersion(version, item.optBoolean("stable", false));
                if (loader.stable) {
                    stableLoaders.add(loader);
                } else {
                    unstableLoaders.add(loader);
                }
            }

            return new FabricVersionList(stableMinecraftVersions, snapshotMinecraftVersions, stableLoaders, unstableLoaders);
        }

        appendJarExecutionProgressLine("Downloading Minecraft version list from Fabric...");
        JSONArray gameVersions = new JSONArray(httpGetTextForJarInstaller(FABRIC_GAME_VERSIONS_URL));
        for (int i = 0; i < gameVersions.length(); i++) {
            JSONObject item = gameVersions.optJSONObject(i);
            if (item == null) continue;
            String version = item.optString("version", "").trim();
            if (version.isEmpty()) continue;
            FabricMinecraftVersion mc = new FabricMinecraftVersion(version, item.optBoolean("stable", false));
            if (mc.stable) {
                stableMinecraftVersions.add(mc);
            } else if (snapshotMinecraftVersions.size() < FABRIC_MAX_SNAPSHOT_CHOICES) {
                snapshotMinecraftVersions.add(mc);
            }
        }

        appendJarExecutionProgressLine("Downloading Fabric Loader list...");
        JSONArray loaderVersions = new JSONArray(httpGetTextForJarInstaller(FABRIC_LOADER_VERSIONS_URL));
        for (int i = 0; i < loaderVersions.length(); i++) {
            JSONObject item = loaderVersions.optJSONObject(i);
            if (item == null) continue;
            String version = item.optString("version", "").trim();
            if (version.isEmpty()) continue;
            FabricLoaderVersion loader = new FabricLoaderVersion(version, item.optBoolean("stable", false));
            if (loader.stable) {
                stableLoaders.add(loader);
            } else {
                unstableLoaders.add(loader);
            }
        }

        return new FabricVersionList(stableMinecraftVersions, snapshotMinecraftVersions, stableLoaders, unstableLoaders);
    }

    private void addLegacyFabricSupportedMinecraftVersions(@NonNull ArrayList<FabricMinecraftVersion> output) {
        for (String version : LEGACY_FABRIC_SUPPORTED_MINECRAFT_VERSIONS) {
            output.add(new FabricMinecraftVersion(version, true));
        }
    }

    private void showFabricMinecraftVersionPicker(
            @NonNull Uri jarUri,
            @NonNull String displayName,
            @NonNull FabricVersionList versionList,
            boolean legacyFabricInstaller
    ) {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(COLOR_DIALOG_BG);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setScrollbarFadingEnabled(false);

        LinearLayout root = createStyledDialogRoot(
                scrollView,
                legacyFabricInstaller ? "Legacy Fabric Minecraft version" : "Fabric Minecraft version",
                legacyFabricInstaller
                        ? "Choose one of the official Legacy Fabric supported Minecraft versions."
                        : "Choose the Minecraft version Fabric should install. Stable releases are shown first."
        );

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        group.setPadding(0, dp(8), 0, dp(4));
        root.addView(group, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        ArrayList<FabricMinecraftVersion> allVersions = new ArrayList<>();
        addFabricMinecraftGroup(group, allVersions, legacyFabricInstaller ? "Supported versions" : "Stable releases", versionList.stableMinecraftVersions);
        if (!legacyFabricInstaller) {
            addFabricMinecraftGroup(group, allVersions, "Snapshots / previews", versionList.snapshotMinecraftVersions);
        }

        if (!allVersions.isEmpty()) {
            group.check(20000);
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Next", null)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            styleLauncherDialogChrome(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                int checkedId = group.getCheckedRadioButtonId();
                int index = checkedId - 20000;
                if (index < 0 || index >= allVersions.size()) {
                    Toast.makeText(this, "Choose a Minecraft version first.", Toast.LENGTH_SHORT).show();
                    return;
                }
                FabricMinecraftVersion selected = allVersions.get(index);
                dialog.dismiss();
                showFabricLoaderVersionPicker(jarUri, displayName, versionList, selected, legacyFabricInstaller);
            });
        });
        dialog.show();
    }

    private void addFabricMinecraftGroup(
            @NonNull RadioGroup group,
            @NonNull ArrayList<FabricMinecraftVersion> allVersions,
            @NonNull String title,
            @NonNull List<FabricMinecraftVersion> versions
    ) {
        if (versions.isEmpty()) return;

        TextView header = new TextView(this);
        header.setText(title);
        header.setTextColor(COLOR_TEXT_PRIMARY);
        header.setTextSize(15f);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(dp(2), dp(12), dp(2), dp(4));
        group.addView(header, new RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.MATCH_PARENT,
                RadioGroup.LayoutParams.WRAP_CONTENT
        ));

        for (FabricMinecraftVersion version : versions) {
            RadioButton option = new RadioButton(this);
            option.setId(20000 + allVersions.size());
            option.setText(version.version + (version.stable ? "  •  Stable" : "  •  Snapshot"));
            option.setTextColor(COLOR_TEXT_SECONDARY);
            option.setTextSize(14f);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                option.setButtonTintList(ColorStateList.valueOf(COLOR_ACCENT));
            }
            group.addView(option, new RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT,
                    RadioGroup.LayoutParams.WRAP_CONTENT
            ));
            allVersions.add(version);
        }
    }

    private void showFabricLoaderVersionPicker(
            @NonNull Uri jarUri,
            @NonNull String displayName,
            @NonNull FabricVersionList versionList,
            @NonNull FabricMinecraftVersion minecraftVersion,
            boolean legacyFabricInstaller
    ) {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(COLOR_DIALOG_BG);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setScrollbarFadingEnabled(false);

        LinearLayout root = createStyledDialogRoot(
                scrollView,
                legacyFabricInstaller ? "Legacy Fabric Loader version" : "Fabric Loader version",
                "Choose the " + (legacyFabricInstaller ? "Legacy Fabric" : "Fabric") + " Loader version for Minecraft " + minecraftVersion.version + ". Latest stable is selected by default."
        );

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        group.setPadding(0, dp(8), 0, dp(4));
        root.addView(group, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        ArrayList<FabricLoaderVersion> allLoaders = new ArrayList<>();
        addFabricLoaderGroup(group, allLoaders, "Stable loaders", versionList.stableLoaders);
        addFabricLoaderGroup(group, allLoaders, "Other loaders", versionList.unstableLoaders);

        if (!allLoaders.isEmpty()) {
            group.check(30000);
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Install", null)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            styleLauncherDialogChrome(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                int checkedId = group.getCheckedRadioButtonId();
                int index = checkedId - 30000;
                if (index < 0 || index >= allLoaders.size()) {
                    Toast.makeText(this, "Choose a Fabric Loader version first.", Toast.LENGTH_SHORT).show();
                    return;
                }
                FabricLoaderVersion loader = allLoaders.get(index);
                dialog.dismiss();
                installFabricVersion(jarUri, displayName, minecraftVersion, loader, legacyFabricInstaller);
            });
        });
        dialog.show();
    }

    private void addFabricLoaderGroup(
            @NonNull RadioGroup group,
            @NonNull ArrayList<FabricLoaderVersion> allLoaders,
            @NonNull String title,
            @NonNull List<FabricLoaderVersion> loaders
    ) {
        if (loaders.isEmpty()) return;

        TextView header = new TextView(this);
        header.setText(title);
        header.setTextColor(COLOR_TEXT_PRIMARY);
        header.setTextSize(15f);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(dp(2), dp(12), dp(2), dp(4));
        group.addView(header, new RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.MATCH_PARENT,
                RadioGroup.LayoutParams.WRAP_CONTENT
        ));

        for (FabricLoaderVersion loader : loaders) {
            RadioButton option = new RadioButton(this);
            option.setId(30000 + allLoaders.size());
            option.setText(loader.version + (loader.stable ? "  •  Stable" : ""));
            option.setTextColor(COLOR_TEXT_SECONDARY);
            option.setTextSize(14f);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                option.setButtonTintList(ColorStateList.valueOf(COLOR_ACCENT));
            }
            group.addView(option, new RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT,
                    RadioGroup.LayoutParams.WRAP_CONTENT
            ));
            allLoaders.add(loader);
        }
    }

    private void installFabricVersion(
            @NonNull Uri jarUri,
            @NonNull String displayName,
            @NonNull FabricMinecraftVersion minecraftVersion,
            @NonNull FabricLoaderVersion loaderVersion,
            boolean legacyFabricInstaller
    ) {
        String installerName = legacyFabricInstaller ? "Legacy Fabric" : "Fabric";
        showJarExecutionProgressDialog(installerName + " " + minecraftVersion.version);
        appendJarExecutionProgressLine("Preparing " + installerName + " " + minecraftVersion.version + " with Loader " + loaderVersion.version + "...");
        updateJarExecutionSummary("Preparing " + installerName + " " + minecraftVersion.version + "...");

        Thread thread = new Thread(() -> {
            try {
                File javaExecutable = findJavaExecutableForJarExecution();
                if (javaExecutable == null || !javaExecutable.isFile()) {
                    throw new IllegalStateException("No bundled Java executable was found. Reinstall components and JREs, then try again.");
                }

                File jarDir = new File(getCacheDir(), "jar_execution");
                if (!jarDir.exists() && !jarDir.mkdirs()) {
                    throw new IllegalStateException("Could not create temporary jar execution folder.");
                }
                File copiedJar = new File(jarDir, sanitizeJarFileName(displayName));
                copyUriToFile(jarUri, copiedJar);

                File workingDirectory = resolveJarExecutionWorkingDirectory();
                LoaderInstallSnapshot installSnapshot = captureLoaderInstallSnapshot(workingDirectory);

                ArrayList<String> command = new ArrayList<>();
                command.add(javaExecutable.getAbsolutePath());
                command.add("-Xint");
                command.add("-Djava.awt.headless=true");
                command.add("-Djava.io.tmpdir=" + getCacheDir().getAbsolutePath());
                command.add("-Duser.home=" + resolveJarExecutionUserHome(workingDirectory).getAbsolutePath());
                command.add("-Duser.dir=" + workingDirectory.getAbsolutePath());
                command.add("-jar");
                command.add(copiedJar.getAbsolutePath());
                command.add("client");
                if (legacyFabricInstaller) {
                    command.add("-metaurl");
                    command.add(LEGACY_FABRIC_META_URL);
                    command.add("-mavenurl");
                    command.add(LEGACY_FABRIC_MAVEN_URL);
                }
                command.add("-dir");
                command.add(workingDirectory.getAbsolutePath());
                command.add("-mcversion");
                command.add(minecraftVersion.version);
                command.add("-loader");
                command.add(loaderVersion.version);
                command.add("-noprofile");

                String commandPreview = joinCommandForDisplay(command);
                Logging.i("LauncherSettings", "Starting native " + installerName + " install: " + commandPreview);
                updateJarExecutionSummary("Installing " + installerName + "...\n" + commandPreview);
                appendJarExecutionProgressLine("Starting " + installerName + " installer...");

                Process process = startJarJavaProcessWithLinkerFallback(
                        command,
                        workingDirectory,
                        javaExecutable,
                        null,
                        installerName + " installer"
                );

                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (output.length() < 16000) output.append(line).append('\n');
                        Logging.i("LauncherSettings", installerName + " output: " + line);
                        appendJarExecutionProgressLine(line);
                        updateJarExecutionSummary(line);
                    }
                }

                int exitCode = process.waitFor();
                Logging.i("LauncherSettings", installerName + " installer finished with exit code " + exitCode);
                if (exitCode != 0) {
                    String finalOutput = output.toString().trim();
                    if (finalOutput.length() > 3500) finalOutput = finalOutput.substring(finalOutput.length() - 3500);
                    throw new IllegalStateException(installerName + " installer exited with code " + exitCode
                            + (finalOutput.isEmpty() ? "" : ":\n" + finalOutput));
                }

                InstalledLoaderInstance installed = createInstanceFromCompletedLoaderInstall(displayName, workingDirectory, installSnapshot);
                if (installed == null) {
                    throw new IllegalStateException(installerName + " installer finished, but DroidBridge could not find the installed Fabric version profile.");
                }

                String summary = "Created DroidBridge instance: " + installed.instanceName
                        + "\nLaunch version: " + installed.versionId
                        + "\nMinecraft version: " + installed.minecraftVersionId
                        + "\n" + installerName + " Loader: " + loaderVersion.version
                        + "\n\nTip: open Per Instance Settings to choose Internal-17/21/25 for older Fabric instances when a modpack requires it.";

                runOnUiThread(() -> {
                    resetJarExecutionButton();
                    updateJarExecutionSummary(summary);
                    finishJarExecutionProgressDialog(summary, true);
                    Toast.makeText(this, "Created " + installed.instanceName, Toast.LENGTH_LONG).show();
                });
            } catch (Throwable throwable) {
                Logging.e("LauncherSettings", installerName + " native install failed", throwable);
                String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.toString();
                runOnUiThread(() -> {
                    resetJarExecutionButton();
                    String summary = installerName + " install failed: " + message;
                    updateJarExecutionSummary(summary);
                    finishJarExecutionProgressDialog(summary, false);
                    Toast.makeText(this, summary, Toast.LENGTH_LONG).show();
                });
            }
        }, legacyFabricInstaller ? "DroidBridgeLegacyFabricInstall" : "DroidBridgeFabricInstall");
        thread.start();
    }


    private void showBtaNativeInstallerDialog(@NonNull String displayName) {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(COLOR_DIALOG_BG);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setScrollbarFadingEnabled(false);

        LinearLayout root = createStyledDialogRoot(
                scrollView,
                "Install Better than Adventure!",
                "DroidBridge will not run the desktop BTA installer window. It will use the Amethyst-style native installer path instead."
        );

        LinearLayout card = addStyledDialogCard(root);
        addStyledDialogCardTitle(card, displayName);
        addStyledDialogInfoText(card,
                "The BTA installer jar opens a Swing window after downloading its version list, which fails in DroidBridge's headless runner. "
                        + "Instead, DroidBridge will download BTA's release/nightly manifests, show the versions Amethyst marks as tested separately, then create a normal DroidBridge instance that inherits "
                        + BTA_BASE_VERSION_ID + ".");

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Choose version", null)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            styleLauncherDialogChrome(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                dialog.dismiss();
                loadBtaVersionListAndShowPicker();
            });
        });
        dialog.show();
    }

    private void loadBtaVersionListAndShowPicker() {
        showJarExecutionProgressDialog("Better than Adventure!");
        appendJarExecutionProgressLine("Downloading BTA version lists...");
        updateJarExecutionSummary("Downloading BTA version lists...");

        Thread thread = new Thread(() -> {
            try {
                BtaVersionList versionList = downloadBtaVersionList();
                if (versionList.isEmpty()) {
                    throw new IllegalStateException("No BTA versions were found in the release/nightly manifests.");
                }
                runOnUiThread(() -> {
                    if (jarExecutionProgressDialog != null && jarExecutionProgressDialog.isShowing()) {
                        jarExecutionProgressDialog.dismiss();
                    }
                    jarExecutionProgressDialog = null;
                    jarExecutionProgressTitleView = null;
                    jarExecutionProgressStatusView = null;
                    jarExecutionProgressLogView = null;
                    showBtaVersionPicker(versionList);
                });
            } catch (Throwable throwable) {
                Logging.e("LauncherSettings", "Unable to download BTA version list", throwable);
                String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.toString();
                runOnUiThread(() -> {
                    String summary = "Unable to download BTA versions: " + message;
                    updateJarExecutionSummary(summary);
                    finishJarExecutionProgressDialog(summary, false);
                    Toast.makeText(this, summary, Toast.LENGTH_LONG).show();
                });
            }
        }, "DroidBridgeBtaVersionList");
        thread.start();
    }

    private void showBtaVersionPicker(@NonNull BtaVersionList versionList) {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(COLOR_DIALOG_BG);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setScrollbarFadingEnabled(false);

        LinearLayout root = createStyledDialogRoot(
                scrollView,
                "Better than Adventure!",
                "Choose the BTA version to install. Versions marked Tested match Amethyst's confirmed list."
        );

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        group.setPadding(0, dp(8), 0, dp(4));
        root.addView(group, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        ArrayList<BtaVersion> allVersions = new ArrayList<>();
        addBtaVersionGroup(group, allVersions, "Tested versions", versionList.testedVersions);
        addBtaVersionGroup(group, allVersions, "Untested release versions", versionList.untestedVersions);
        addBtaVersionGroup(group, allVersions, "Nightly versions", versionList.nightlyVersions);

        if (!allVersions.isEmpty()) {
            group.check(10000);
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Install", null)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            styleLauncherDialogChrome(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                int checkedId = group.getCheckedRadioButtonId();
                int index = checkedId - 10000;
                if (index < 0 || index >= allVersions.size()) {
                    Toast.makeText(this, "Choose a BTA version first.", Toast.LENGTH_SHORT).show();
                    return;
                }
                BtaVersion selected = allVersions.get(index);
                dialog.dismiss();
                installBtaVersion(selected);
            });
        });
        dialog.show();
    }

    private void addBtaVersionGroup(
            @NonNull RadioGroup group,
            @NonNull ArrayList<BtaVersion> allVersions,
            @NonNull String title,
            @NonNull List<BtaVersion> versions
    ) {
        if (versions.isEmpty()) return;

        TextView header = new TextView(this);
        header.setText(title);
        header.setTextColor(COLOR_TEXT_PRIMARY);
        header.setTextSize(15f);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(dp(2), dp(12), dp(2), dp(4));
        group.addView(header, new RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.MATCH_PARENT,
                RadioGroup.LayoutParams.WRAP_CONTENT
        ));

        for (BtaVersion version : versions) {
            RadioButton option = new RadioButton(this);
            option.setId(10000 + allVersions.size());
            option.setText(version.versionName + "  •  " + version.label);
            option.setTextColor(COLOR_TEXT_SECONDARY);
            option.setTextSize(14f);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                option.setButtonTintList(ColorStateList.valueOf(COLOR_ACCENT));
            }
            group.addView(option, new RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT,
                    RadioGroup.LayoutParams.WRAP_CONTENT
            ));
            allVersions.add(version);
        }
    }

    @NonNull
    private BtaVersionList downloadBtaVersionList() throws Exception {
        ArrayList<BtaVersion> tested = new ArrayList<>();
        ArrayList<BtaVersion> untested = new ArrayList<>();
        ArrayList<BtaVersion> nightlies = new ArrayList<>();

        appendJarExecutionProgressLine("Downloading release manifest...");
        JSONArray releaseVersions = readBtaVersionsArray(httpGetTextForJarInstaller(BTA_RELEASE_MANIFEST_URL));
        for (int i = releaseVersions.length() - 1; i >= 0; i--) {
            String version = releaseVersions.optString(i, "").trim();
            if (version.isEmpty()) continue;
            BtaVersion item = buildBtaVersion(version, "release", isBtaTestedVersion(version));
            if (item.tested) tested.add(item); else untested.add(item);
        }

        try {
            appendJarExecutionProgressLine("Downloading nightly manifest...");
            JSONArray nightlyVersions = readBtaVersionsArray(httpGetTextForJarInstaller(BTA_NIGHTLY_MANIFEST_URL));
            for (int i = nightlyVersions.length() - 1; i >= 0; i--) {
                String version = nightlyVersions.optString(i, "").trim();
                if (version.isEmpty()) continue;
                nightlies.add(buildBtaVersion(version, "nightly", false));
            }
        } catch (Throwable throwable) {
            Logging.i("LauncherSettings", "Unable to download BTA nightly manifest: " + throwable.getMessage());
            appendJarExecutionProgressLine("Nightly manifest unavailable; showing release versions only.");
        }

        return new BtaVersionList(tested, untested, nightlies);
    }

    @NonNull
    private JSONArray readBtaVersionsArray(@NonNull String jsonText) throws Exception {
        JSONObject json = new JSONObject(jsonText);
        JSONArray versions = json.optJSONArray("versions");
        if (versions == null) throw new IllegalStateException("BTA manifest did not contain a versions array.");
        return versions;
    }

    @NonNull
    private BtaVersion buildBtaVersion(@NonNull String version, @NonNull String buildType, boolean tested) {
        boolean nightly = "nightly".equalsIgnoreCase(buildType);
        String clientUrl = String.format(Locale.US,
                nightly ? BTA_NIGHTLY_CLIENT_URL_FORMAT : BTA_RELEASE_CLIENT_URL_FORMAT,
                version
        );
        String iconName = version.replace('.', '_');
        if (nightly) iconName = "v" + iconName;
        String iconUrl = String.format(Locale.US,
                nightly ? BTA_NIGHTLY_ICON_URL_FORMAT : BTA_RELEASE_ICON_URL_FORMAT,
                version,
                iconName
        );
        String label = nightly ? "Nightly" : (tested ? "Tested" : "Untested");
        return new BtaVersion(version, buildType, clientUrl, iconUrl, label, tested);
    }

    private boolean isBtaTestedVersion(@NonNull String version) {
        for (String tested : BTA_TESTED_VERSION_NAMES) {
            if (tested.equals(version)) return true;
        }
        return false;
    }

    private void installBtaVersion(@NonNull BtaVersion version) {
        showJarExecutionProgressDialog("BTA " + version.versionName);
        appendJarExecutionProgressLine("Preparing BTA " + version.versionName + "...");
        updateJarExecutionSummary("Preparing BTA " + version.versionName + "...");

        Thread thread = new Thread(() -> {
            try {
                File minecraftHome = resolveJarExecutionWorkingDirectory();
                appendJarExecutionProgressLine("Checking inherited base " + BTA_BASE_VERSION_ID + "...");
                ensureInheritedMinecraftVersionInstalled(minecraftHome, BTA_BASE_VERSION_ID);

                appendJarExecutionProgressLine("Writing BTA version JSON...");
                String versionId = "bta-" + version.versionName;
                File versionDir = new File(new File(minecraftHome, "versions"), versionId);
                File jsonFile = new File(versionDir, versionId + ".json");
                if (!versionDir.exists() && !versionDir.mkdirs()) {
                    throw new IOException("Unable to create BTA version folder: " + versionDir.getAbsolutePath());
                }
                writeTextFile(jsonFile, buildBtaVersionJson(version, versionId));

                appendJarExecutionProgressLine("Downloading BTA client jar...");
                removeOldBtaClientJar(minecraftHome, version.versionName);
                File libraryJar = getBtaLibraryJar(minecraftHome, version.versionName);
                downloadFileForJarInstaller(version.downloadUrl, libraryJar);

                appendJarExecutionProgressLine("Creating DroidBridge instance...");
                String instanceName = buildUniqueLoaderInstanceName("BTA", version.versionName);
                LauncherInstance instance = LauncherInstanceManager.createInstance(
                        this,
                        instanceName,
                        "BTA",
                        versionId,
                        BTA_BASE_VERSION_ID,
                        "old_beta",
                        null,
                        true
                );
                ensureLoaderInstanceFolders(instance);
                String summary = "Installed Better than Adventure! " + version.versionName
                        + "\nInstance: " + instance.getName()
                        + "\nLaunch version: " + versionId
                        + "\nInherited Minecraft version: " + BTA_BASE_VERSION_ID;
                Logging.i("LauncherSettings", summary);
                runOnUiThread(() -> {
                    resetJarExecutionButton();
                    updateJarExecutionSummary(summary);
                    finishJarExecutionProgressDialog(summary, true);
                    Toast.makeText(this, "BTA instance created: " + instance.getName(), Toast.LENGTH_LONG).show();
                });
            } catch (Throwable throwable) {
                Logging.e("LauncherSettings", "BTA native install failed", throwable);
                String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.toString();
                runOnUiThread(() -> {
                    resetJarExecutionButton();
                    String summary = "BTA install failed: " + message;
                    updateJarExecutionSummary(summary);
                    finishJarExecutionProgressDialog(summary, false);
                    Toast.makeText(this, summary, Toast.LENGTH_LONG).show();
                });
            }
        }, "DroidBridgeBtaInstall");
        thread.start();
    }

    @NonNull
    private String buildBtaVersionJson(@NonNull BtaVersion version, @NonNull String versionId) throws Exception {
        JSONObject artifact = new JSONObject();
        artifact.put("path", "bta-client/bta-client-" + version.versionName + ".jar");
        artifact.put("url", version.downloadUrl);

        JSONObject downloads = new JSONObject();
        downloads.put("artifact", artifact);

        JSONObject library = new JSONObject();
        library.put("name", "bta-client:bta-client:" + version.versionName);
        library.put("downloads", downloads);

        JSONArray libraries = new JSONArray();
        libraries.put(library);

        JSONObject json = new JSONObject();
        json.put("id", versionId);
        json.put("type", "old_beta");
        json.put("inheritsFrom", BTA_BASE_VERSION_ID);
        json.put("mainClass", "net.minecraft.client.Minecraft");
        if (BtaRendererPolicy.isBta8OrNewer(versionId, null)) {
            JSONObject javaVersion = new JSONObject();
            javaVersion.put("majorVersion", 17);
            json.put("javaVersion", javaVersion);
        }

        /*
         * Important for DroidBridge:
         * legacy profile can leave this out because its old-version launcher path does
         * not re-inject the inherited beta arguments the same way DroidBridge's
         * JavaLaunchBuilder does.  DroidBridge resolves b1.7.3 inheritance first,
         * so without this override the parent b1.7.3 minecraftArguments leak back
         * in and BTA launches with modern-ish extras such as --gameDir/--assetsDir.
         * BTA should launch like beta: username + session only.
         */
        json.put("minecraftArguments", "${auth_player_name} ${auth_session}");
        json.put("libraries", libraries);
        return json.toString(2);
    }

    @NonNull
    private File getBtaLibraryJar(@NonNull File minecraftHome, @NonNull String versionName) {
        return new File(new File(minecraftHome, "libraries"), "bta-client/bta-client-" + versionName + ".jar");
    }

    private void removeOldBtaClientJar(@NonNull File minecraftHome, @NonNull String versionName) throws IOException {
        File old = getBtaLibraryJar(minecraftHome, versionName);
        if (old.isFile() && !old.delete()) {
            throw new IOException("Unable to replace old BTA client jar: " + old.getAbsolutePath());
        }
    }

    private void showOptiFineNativeActionDialog(@NonNull Uri jarUri, @NonNull String displayName) {
        String minecraftVersion = inferOptiFineMinecraftVersion(displayName);
        String profileName = buildOptiFineProfileName(displayName);
        boolean agentsReady = hasZalithStyleOptiFineAgents();

        StringBuilder message = new StringBuilder();
        message.append("Install OptiFine as its own DroidBridge instance.\n\n");
        if (!minecraftVersion.isEmpty()) {
            message.append("Minecraft version: ").append(minecraftVersion).append("\n");
        } else {
            message.append("Minecraft version: unknown from file name\n");
        }
        message.append("OptiFine profile: ").append(profileName).append("\n\n");
        message.append("DroidBridge will first make sure the matching vanilla Minecraft version exists in .minecraft/versions, then it will open the OptiFine installer using the same Forge/OptiFine javaagent flow Zalith uses.\n\n");
        message.append("Do not close DroidBridge until the install progress finishes and the new instance appears.");

        if (minecraftVersion.isEmpty()) {
            updateJarExecutionSummary("Unable to infer the Minecraft version from " + displayName + ". Expected a name like OptiFine_1.20.1_HD_U_I6.jar.");
            new MaterialAlertDialogBuilder(this)
                    .setTitle("OptiFine install")
                    .setMessage("Unable to infer the Minecraft version from this file name. Expected a name like OptiFine_1.20.1_HD_U_I6.jar.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        if (!agentsReady) {
            updateJarExecutionSummary("OptiFine standalone install requires forge_installer.jar and OptiFineRenamer.jar in DroidBridge components.");
            new MaterialAlertDialogBuilder(this)
                    .setTitle("OptiFine install")
                    .setMessage("OptiFine standalone install requires these component files:\n\n• forge_installer.jar\n• OptiFineRenamer.jar\n\nReinstall DroidBridge components, then try again.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Install OptiFine")
                .setMessage(message.toString())
                .setPositiveButton("Install as instance", null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(dialogInterface -> {
            styleLauncherDialogChrome(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                dialog.dismiss();
                startOptiFineStandaloneInstall(jarUri, displayName, minecraftVersion, profileName);
            });
        });
        dialog.show();
    }

    private void startOptiFineStandaloneInstall(
            @NonNull Uri jarUri,
            @NonNull String displayName,
            @NonNull String minecraftVersion,
            @NonNull String profileName
    ) {
        showJarExecutionProgressDialog(displayName);
        appendJarExecutionProgressLine("Preparing standalone OptiFine install...");

        Thread thread = new Thread(() -> {
            try {
                File workingDirectory = resolveJarExecutionWorkingDirectory();
                updateJarExecutionSummary("Preparing vanilla base " + minecraftVersion + " for OptiFine...");
                appendJarExecutionProgressLine("Checking vanilla base " + minecraftVersion + "...");
                ensureInheritedMinecraftVersionInstalled(workingDirectory, minecraftVersion);

                File jarDir = new File(getCacheDir(), "jar_execution");
                if (!jarDir.exists() && !jarDir.mkdirs()) {
                    throw new IllegalStateException("Could not create temporary jar execution folder.");
                }
                File copiedJar = new File(jarDir, sanitizeJarFileName(displayName));
                copyUriToFile(jarUri, copiedJar);

                /*
                 * Do not open DroidBridgeStandaloneJarActivity for OptiFine anymore.
                 * Logcat proved the in-process Android/Cacio/JLI Activity path
                 * aborts immediately after Calling JLI_Launch with:
                 *   FORTIFY: pthread_mutex_lock called on a destroyed mutex
                 *
                 * the prior implementation uses the Forge/OptiFine javaagents for standalone
                 * OptiFine.  Run that same javaagent install in an external
                 * Java process instead, then convert the produced version JSON
                 * into a DroidBridge instance.  This keeps OptiFine as its own
                 * loader/profile and does not copy it into a Forge mods folder.
                 */
                storePendingOptiFineStandaloneInstall(profileName, minecraftVersion, displayName);
                LoaderInstallSnapshot snapshot = captureLoaderInstallSnapshot(workingDirectory);
                int exitCode = runOptiFineStandaloneExternalInstaller(copiedJar, displayName, profileName, workingDirectory);

                if (exitCode != 0) {
                    throw new IllegalStateException("OptiFine installer exited with code " + exitCode
                            + ". Check the installer log above for the exact error.");
                }

                InstalledLoaderInstance installed = createOptiFineInstanceFromInstalledProfile(
                        profileName,
                        minecraftVersion,
                        displayName,
                        workingDirectory,
                        snapshot
                );
                clearPendingOptiFineStandaloneInstall();

                String summary = "Created DroidBridge instance: " + installed.instanceName
                        + "\nLaunch version: " + installed.versionId
                        + "\nMinecraft version: " + installed.minecraftVersionId;
                runOnUiThread(() -> {
                    resetJarExecutionButton();
                    updateJarExecutionSummary(summary);
                    finishJarExecutionProgressDialog(summary, true);
                    Toast.makeText(this, "Created " + installed.instanceName, Toast.LENGTH_LONG).show();
                });
            } catch (Throwable throwable) {
                Logging.e("LauncherSettings", "OptiFine standalone install failed", throwable);
                String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.toString();
                runOnUiThread(() -> {
                    resetJarExecutionButton();
                    String summary = "OptiFine standalone install failed: " + message;
                    updateJarExecutionSummary(summary);
                    finishJarExecutionProgressDialog(summary, false);
                    Toast.makeText(this, summary, Toast.LENGTH_LONG).show();
                });
            }
        }, "DroidBridgeOptiFineStandaloneInstall");
        thread.start();
    }

    private int runOptiFineStandaloneExternalInstaller(
            @NonNull File copiedJar,
            @NonNull String displayName,
            @NonNull String profileName,
            @NonNull File workingDirectory
    ) throws Exception {
        int requiredInstallerJava = readJarMainClassJavaVersionForInstaller(copiedJar);
        JarGuiCompatibility compatibility = findJarGuiCompatibility(requiredInstallerJava);
        if (compatibility == null) {
            throw new IllegalStateException(buildOptiFineInstallerRuntimeError(requiredInstallerJava));
        }
        File javaExecutable = compatibility.javaExecutable;
        if (!hasZalithStyleOptiFineAgents()) {
            throw new IllegalStateException("OptiFine standalone install requires forge_installer.jar and OptiFineRenamer.jar in components.");
        }

        ArrayList<String> command = new ArrayList<>();
        command.add(javaExecutable.getAbsolutePath());

        /*
         * Important: do NOT use -Djava.awt.headless=true here.
         *
         * The external-process OptiFine attempt reached optifine.InstallerFrame
         * and failed with java.awt.HeadlessException because DroidBridge started
         * Internal-17 as a headless command-line process.  OptiFine's installer
         * still constructs a Swing JFrame even when the OFNPS agent is going to
         * click Install automatically.
         *
         * Keep this out of DroidBridgeStandaloneJarActivity/VMLauncher, but launch an
         * external process with DroidBridge's Cacio17 stack and a runtime new
         * enough for the selected OptiFine installer. This avoids the
         * Internal-8 AArch64 VM crash while also avoiding Java 17 loading
         * failures for Java 21+ OptiFine installers.
         */
        addGuiJarCompatibilityArguments(command, compatibility);

        // OptiFine and forge_installer.jar resolve the Minecraft directory from
        // user.home/.minecraft. Point user.home at the real launcher root instead
        // of Android's external-files directory, otherwise the installer writes to
        // a second, unused .minecraft folder and DroidBridge cannot find the profile.
        File optiFineUserHome = resolveOptiFineUserHome(workingDirectory);
        ensureOptiFineLauncherProfiles(workingDirectory);
        command.add("-Duser.home=" + optiFineUserHome.getAbsolutePath());
        command.add("-Duser.dir=" + workingDirectory.getAbsolutePath());
        command.add("-javaagent:" + LibPath.FORGE_INSTALLER.getAbsolutePath() + "=OFNPS");
        command.add("-javaagent:" + LibPath.OPTIFINE_RENAMER.getAbsolutePath() + "=" + profileName);
        command.add("-jar");
        command.add(copiedJar.getAbsolutePath());

        String commandPreview = joinCommandForDisplay(command);
        Logging.i("LauncherSettings", "Starting OptiFine standalone external Cacio install using Java " + compatibility.javaMajor + ": " + commandPreview);
        updateJarExecutionSummary("Installing OptiFine standalone profile...\n" + commandPreview);
        appendJarExecutionProgressLine("Starting external OptiFine installer with Cacio using Java " + compatibility.javaMajor + "...");

        Process process = startJarJavaProcessWithLinkerFallback(
                command,
                workingDirectory,
                javaExecutable,
                compatibility,
                "OptiFine standalone installer"
        );
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < 16000) output.append(line).append('\n');
                Logging.i("LauncherSettings", "OptiFine output: " + line);
                appendJarExecutionProgressLine(line);
                updateJarExecutionSummary(line);
            }
        }

        int exitCode = process.waitFor();
        Logging.i("LauncherSettings", "OptiFine standalone installer finished with exit code " + exitCode);
        if (exitCode != 0) {
            String finalOutput = output.toString().trim();
            if (finalOutput.length() > 2500) finalOutput = finalOutput.substring(finalOutput.length() - 2500);
            if (!finalOutput.isEmpty()) {
                appendJarExecutionProgressLine(finalOutput);
            }
        }
        return exitCode;
    }

    @NonNull
    private File resolveOptiFineUserHome(@NonNull File minecraftHome) {
        File parent = minecraftHome.getParentFile();
        if (parent != null && ".minecraft".equals(minecraftHome.getName())) {
            return parent;
        }
        return minecraftHome;
    }

    private void ensureOptiFineLauncherProfiles(@NonNull File minecraftHome) throws Exception {
        if (!minecraftHome.exists() && !minecraftHome.mkdirs()) {
            throw new IOException("Unable to create Minecraft folder: " + minecraftHome.getAbsolutePath());
        }

        File profilesFile = new File(minecraftHome, "launcher_profiles.json");
        JSONObject root;
        boolean changed = false;
        if (profilesFile.isFile()) {
            String text = readTextFile(profilesFile).trim();
            root = text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } else {
            root = new JSONObject();
            changed = true;
        }

        if (root.optJSONObject("profiles") == null) {
            root.put("profiles", new JSONObject());
            changed = true;
        }

        if (changed) {
            writeTextFile(profilesFile, root.toString(2));
            Logging.i("LauncherSettings", "Prepared OptiFine launcher profile store: "
                    + profilesFile.getAbsolutePath());
        }
    }

    @NonNull
    private InstalledLoaderInstance createOptiFineInstanceFromInstalledProfile(
            @NonNull String profileName,
            @NonNull String minecraftVersion,
            @NonNull String displayName,
            @NonNull File minecraftHome,
            @NonNull LoaderInstallSnapshot snapshot
    ) throws Exception {
        File versionDir = new File(new File(minecraftHome, "versions"), profileName);
        File versionJson = new File(versionDir, profileName + ".json");

        if (!versionJson.isFile()) {
            InstalledVersionProfile detected = findInstalledLoaderVersionProfile(
                    minecraftHome,
                    displayName.toLowerCase(Locale.US),
                    snapshot
            );
            if (detected != null && "OptiFine".equals(detected.loader)) {
                profileName = detected.versionId;
                minecraftVersion = detected.minecraftVersionId;
                versionDir = new File(new File(minecraftHome, "versions"), profileName);
                versionJson = new File(versionDir, profileName + ".json");
            }
        }

        if (!versionJson.isFile()) {
            throw new IllegalStateException("OptiFine installer finished but did not create version JSON: "
                    + new File(versionDir, profileName + ".json").getAbsolutePath());
        }

        JSONObject json = new JSONObject(readTextFile(versionJson));
        String inherited = json.optString("inheritsFrom", minecraftVersion).trim();
        if (inherited.isEmpty()) inherited = minecraftVersion;
        String type = json.optString("type", "release").trim();
        if (type.isEmpty()) type = "release";

        if (!inherited.isEmpty()) {
            ensureInheritedMinecraftVersionInstalled(minecraftHome, inherited);
        }

        for (LauncherInstance existing : LauncherInstanceManager.findInstances(this)) {
            if (profileName.equals(existing.getBaseVersionId())) {
                return new InstalledLoaderInstance(existing.getName(), profileName, inherited.isEmpty() ? profileName : inherited);
            }
        }

        String instanceName = buildUniqueLoaderInstanceName("OptiFine", inherited.isEmpty() ? profileName : inherited);
        LauncherInstance instance = LauncherInstanceManager.createInstance(
                this,
                instanceName,
                "OptiFine",
                profileName,
                inherited.isEmpty() ? profileName : inherited,
                type,
                null,
                true
        );
        ensureLoaderInstanceFolders(instance);
        return new InstalledLoaderInstance(instance.getName(), profileName, inherited.isEmpty() ? profileName : inherited);
    }

    private void openJavaGuiLauncherActivityForOptiFine(
            @NonNull File copiedJar,
            @NonNull String displayName,
            @NonNull String profileName
    ) {
        String[] candidateClassNames = new String[]{
                "ca.dnamobile.droidbridgelauncher.DroidBridgeStandaloneJarActivity",
        };

        for (String className : candidateClassNames) {
            try {
                Class<?> activityClass = Class.forName(className);
                Intent intent = new Intent(this, activityClass);
                intent.putExtra("jre_name", "Internal-8");
                intent.putExtra("forceShowLog", true);
                intent.putExtra("openLogOutput", true);
                intent.putExtra("subscribe_jvm_exit_event", true);
                intent.putExtra("javaArgs", buildOptiFineJavaGuiArgs(copiedJar, displayName, true));
                updateJarExecutionSummary("Opening OptiFine installer using " + className + "...");
                startActivity(intent);
                return;
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable throwable) {
                Logging.e("LauncherSettings", "Unable to open OptiFine Java GUI launcher Activity", throwable);
                String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.toString();
                updateJarExecutionSummary("Unable to open OptiFine installer Activity: " + message);
                finishJarExecutionProgressDialog("Unable to open OptiFine installer Activity: " + message, false);
                return;
            }
        }

        String message = "DroidBridgeStandaloneJarActivity was not found. OptiFine standalone installer cannot open.";
        updateJarExecutionSummary(message);
        finishJarExecutionProgressDialog(message, false);
    }

    private void installOptiFineJarIntoInstanceMods(
            @NonNull Uri jarUri,
            @NonNull String displayName,
            @NonNull LauncherInstance instance
    ) {
        Thread thread = new Thread(() -> {
            try {
                File modsDir = new File(instance.getGameDirectory(), "mods");
                if (!modsDir.exists() && !modsDir.mkdirs()) {
                    throw new IOException("Unable to create mods folder: " + modsDir.getAbsolutePath());
                }
                File target = uniqueFileInDirectory(modsDir, sanitizeJarFileName(displayName));
                copyUriToFile(jarUri, target);

                String message = "Installed OptiFine into: " + instance.getName()
                        + "\n\nFile copied to:\n" + target.getAbsolutePath()
                        + "\n\nLaunch that Forge instance to use OptiFine. Standalone OptiFine profile creation still needs a native extractor.";
                Logging.i("LauncherSettings", message);
                runOnUiThread(() -> {
                    updateJarExecutionSummary(message);
                    Toast.makeText(this, "OptiFine added to " + instance.getName(), Toast.LENGTH_LONG).show();
                });
            } catch (Throwable throwable) {
                Logging.e("LauncherSettings", "OptiFine native mod install failed", throwable);
                String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.toString();
                runOnUiThread(() -> {
                    updateJarExecutionSummary("OptiFine install failed: " + message);
                    Toast.makeText(this, "OptiFine install failed: " + message, Toast.LENGTH_LONG).show();
                });
            }
        }, "DroidBridgeOptiFineModInstall");
        thread.start();
    }

    @NonNull
    private File uniqueFileInDirectory(@NonNull File directory, @NonNull String requestedName) {
        File first = new File(directory, requestedName);
        if (!first.exists()) return first;

        String name = requestedName;
        String extension = "";
        int dot = requestedName.lastIndexOf('.');
        if (dot > 0) {
            name = requestedName.substring(0, dot);
            extension = requestedName.substring(dot);
        }

        for (int i = 1; i < 1000; i++) {
            File candidate = new File(directory, name + " (" + i + ")" + extension);
            if (!candidate.exists()) return candidate;
        }
        return new File(directory, name + "-" + System.currentTimeMillis() + extension);
    }

    private boolean isForgeLikeInstance(@NonNull LauncherInstance instance) {
        String loader = instance.getLoader().toLowerCase(Locale.US);
        String baseVersion = instance.getBaseVersionId().toLowerCase(Locale.US);
        return loader.contains("forge") || baseVersion.contains("forge") || baseVersion.contains("neoforge");
    }

    @NonNull
    private String inferOptiFineMinecraftVersion(@NonNull String displayName) {
        Matcher matcher = Pattern.compile("(?i)OptiFine_([0-9]+(?:\\.[0-9]+){1,2})_").matcher(displayName);
        if (matcher.find()) return matcher.group(1);
        matcher = Pattern.compile("([0-9]+(?:\\.[0-9]+){1,2})").matcher(displayName);
        return matcher.find() ? matcher.group(1) : "";
    }

    @NonNull
    private String buildJarArgumentsHint(@NonNull String displayName) {
        String lower = displayName.toLowerCase(Locale.US);
        File gameHome = resolveJarExecutionWorkingDirectory();

        if (isFabricInstallerName(lower)) {
            return "client -dir \"" + gameHome.getAbsolutePath()
                    + "\" -mcversion <minecraft version> -loader <loader version>";
        }
        if (isQuiltInstallerName(lower)) {
            return "install client <minecraft version> <loader version> --install-dir=\""
                    + gameHome.getAbsolutePath() + "\"";
        }
        if (isNeoForgeInstallerName(lower)) {
            return "--installClient \"" + gameHome.getAbsolutePath() + "\"";
        }
        if (isForgeInstallerName(lower)) {
            return "--installClient";
        }
        if (isOptiFineInstallerName(lower)) {
            return hasZalithStyleOptiFineAgents()
                    ? "Leave empty. DroidBridge will use forge_installer.jar + OptiFineRenamer.jar automatically."
                    : "--installClient";
        }
        if (isBtaInstallerName(lower)) {
            return "Leave empty. DroidBridge installs BTA natively from the BTA manifest.";
        }
        return "Optional command-line arguments for this jar";
    }

    @NonNull
    private String buildDefaultJarArguments(@NonNull String displayName) {
        String lower = displayName.toLowerCase(Locale.US);
        File gameHome = resolveJarExecutionWorkingDirectory();

        if (isFabricInstallerName(lower)) {
            // the prior implementation supplies -mcversion and -loader from its loader picker.
            // The generic jar picker cannot know those safely, so prefill the
            // stable part and let the user add the two version values when needed.
            return "client -dir \"" + gameHome.getAbsolutePath() + "\"";
        }
        if (isQuiltInstallerName(lower)) {
            // Quilt needs mc/loader versions before --install-dir. Leave blank
            // rather than generating a command that looks complete but is not.
            return "";
        }
        if (isNeoForgeInstallerName(lower)) {
            return "--installClient \"" + gameHome.getAbsolutePath() + "\"";
        }
        if (isForgeInstallerName(lower)) {
            return "--installClient";
        }
        if (isOptiFineInstallerName(lower)) {
            return hasZalithStyleOptiFineAgents() ? "" : "--installClient";
        }
        if (isBtaInstallerName(lower)) {
            return "";
        }
        return "";
    }

    @NonNull
    private String buildJarExecutionFinalSummary(
            int exitCode,
            @NonNull String displayName,
            @NonNull String argumentsText,
            @NonNull String finalOutput
    ) {
        String lowerName = displayName.toLowerCase(Locale.US);
        String lowerOutput = finalOutput.toLowerCase(Locale.US);
        StringBuilder summary = new StringBuilder();
        summary.append("Jar finished with exit code ").append(exitCode).append('.');

        if (lowerOutput.contains("headlessexception") || lowerOutput.contains("java.awt.window") || lowerOutput.contains("javax.swing")) {
            summary.append("\n\nThis jar tried to open a desktop Swing/AWT window. Android cannot display that installer window from DroidBridge. ")
                    .append("Use a command-line mode if the jar provides one, or add a launcher-native installer/extractor path for this specific jar type.");
            if (lowerName.contains("optifine")) {
                summary.append("\n\nOptiFine should not be launched through Swing/Cacio here. DroidBridge tries Zalith-style Forge/OptiFine javaagents first; reinstall components if forge_installer.jar or OptiFineRenamer.jar are missing.");
            }
        } else if (lowerName.contains("fabric-installer") && argumentsText.trim().startsWith("--installClient")) {
            summary.append("\n\nFabric installer does not use --installClient here. Use this syntax instead:\nclient -dir \"")
                    .append(resolveJarExecutionWorkingDirectory().getAbsolutePath())
                    .append("\" -mcversion <version>");
        } else if (exitCode == 134) {
            summary.append("\n\nThe Java VM aborted with exit code 134. Check the logged command: it must not contain Cacio, CTCToolkit, java.awt.headless=false, or Internal-8 for normal loader installs. Reinstall Internal-17/components if it falls back incorrectly.");
        }

        if (!finalOutput.isEmpty()) {
            summary.append("\n\n").append(finalOutput);
        } else if (summary.indexOf("\n\n") < 0) {
            summary.append("\n\nNo text output was produced. If this was a loader installer jar, it may require command-line arguments or it may be trying to open a desktop GUI that Android cannot show.");
        }
        return summary.toString();
    }

    private void startJarExecution(@NonNull Uri jarUri, @NonNull String displayName, @NonNull String argumentsText) {
        if (binding == null || binding.buttonExecuteJarFile == null) return;

        binding.buttonExecuteJarFile.setEnabled(false);
        binding.buttonExecuteJarFile.setText("Running jar...");
        showJarExecutionProgressDialog(displayName);
        updateJarExecutionSummary("Preparing " + displayName + "...");
        appendJarExecutionProgressLine("Preparing " + displayName + "...");

        if (shouldUseJavaGuiLauncherActivity(displayName)) {
            if (openJavaGuiLauncherActivityIfAvailable(jarUri, displayName, argumentsText)) {
                resetJarExecutionButton();
                return;
            }

            resetJarExecutionButton();
            String message = "This jar is a desktop Swing/AWT installer and needs launcher-native support. "
                    + "OptiFine is no longer routed here because Android aborts the in-process Cacio/JLI Activity path on some devices. "
                    + "Fabric-style command-line jars still run from this button.";
            updateJarExecutionSummary(message);
            Toast.makeText(this, "This GUI jar needs launcher-native support.", Toast.LENGTH_LONG).show();
            return;
        }

        Thread thread = new Thread(() -> {
            File copiedJar = null;
            try {
                File javaExecutable = findJavaExecutableForJarExecution();
                if (javaExecutable == null || !javaExecutable.isFile()) {
                    throw new IllegalStateException("No bundled Java executable was found. Reinstall components and JREs, then try again.");
                }

                File jarDir = new File(getCacheDir(), "jar_execution");
                if (!jarDir.exists() && !jarDir.mkdirs()) {
                    throw new IllegalStateException("Could not create temporary jar execution folder.");
                }

                copiedJar = new File(jarDir, sanitizeJarFileName(displayName));
                copyUriToFile(jarUri, copiedJar);

                File workingDirectory = resolveJarExecutionWorkingDirectory();
                LoaderInstallSnapshot installSnapshot = captureLoaderInstallSnapshot(workingDirectory);
                StringBuilder output = new StringBuilder();
                ArrayList<String> command = new ArrayList<>();
                command.add(javaExecutable.getAbsolutePath());
                command.add("-Xint");
                command.add("-Djava.awt.headless=true");
                command.add("-Djava.io.tmpdir=" + getCacheDir().getAbsolutePath());
                command.add("-Duser.home=" + resolveJarExecutionUserHome(workingDirectory).getAbsolutePath());
                command.add("-Duser.dir=" + workingDirectory.getAbsolutePath());

                boolean optiFineAgentMode = addZalithStyleLoaderVmArguments(command, displayName);

                command.add("-jar");
                command.add(copiedJar.getAbsolutePath());
                command.addAll(splitCommandLineArguments(normalizeLoaderJarArguments(displayName, argumentsText, optiFineAgentMode)));

                String commandPreview = joinCommandForDisplay(command);
                Logging.i("LauncherSettings", "Starting jar execution: " + commandPreview);
                updateJarExecutionSummary("Running jar...\n" + commandPreview);
                appendJarExecutionProgressLine("Starting Java installer...");

                Process process = startJarJavaProcessWithLinkerFallback(
                        command,
                        workingDirectory,
                        javaExecutable,
                        null,
                        "Jar execution"
                );
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (output.length() < 12000) {
                            output.append(line).append('\n');
                        }
                        Logging.i("LauncherSettings", "Jar output: " + line);
                        final String latestLine = line;
                        updateJarExecutionSummary(latestLine);
                        appendJarExecutionProgressLine(latestLine);
                    }
                }

                int exitCode = process.waitFor();
                String finalOutput = output.toString().trim();
                if (finalOutput.length() > 3500) {
                    finalOutput = finalOutput.substring(finalOutput.length() - 3500);
                }
                Logging.i("LauncherSettings", "Jar execution finished with exit code " + exitCode);
                InstalledLoaderInstance installedLoaderInstance = null;
                if (exitCode == 0) {
                    installedLoaderInstance = createInstanceFromCompletedLoaderInstall(displayName, workingDirectory, installSnapshot);
                }
                String summary = buildJarExecutionFinalSummary(exitCode, displayName, argumentsText, finalOutput);
                if (installedLoaderInstance != null) {
                    summary += "\n\nCreated DroidBridge instance: " + installedLoaderInstance.instanceName
                            + "\nLaunch version: " + installedLoaderInstance.versionId
                            + "\nMinecraft version: " + installedLoaderInstance.minecraftVersionId;
                }
                String finalSummary = summary;
                runOnUiThread(() -> {
                    resetJarExecutionButton();
                    updateJarExecutionSummary(finalSummary);
                    finishJarExecutionProgressDialog(finalSummary, exitCode == 0);
                    Toast.makeText(this, exitCode == 0 ? "Jar execution complete." : "Jar finished with exit code " + exitCode + ".", Toast.LENGTH_LONG).show();
                });
            } catch (Throwable throwable) {
                Logging.e("LauncherSettings", "Jar execution failed", throwable);
                String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.toString();
                runOnUiThread(() -> {
                    resetJarExecutionButton();
                    String errorSummary = "Jar execution failed: " + message;
                    updateJarExecutionSummary(errorSummary);
                    finishJarExecutionProgressDialog(errorSummary, false);
                    Toast.makeText(this, "Jar execution failed: " + message, Toast.LENGTH_LONG).show();
                });
            }
        }, "DroidBridgeJarExecution");
        thread.start();
    }


    @NonNull
    private LoaderInstallSnapshot captureLoaderInstallSnapshot(@NonNull File minecraftHome) {
        HashMap<String, Long> knownVersions = new HashMap<>();
        File versionsRoot = new File(minecraftHome, "versions");
        File[] children = versionsRoot.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!child.isDirectory()) continue;
                File jsonFile = new File(child, child.getName() + ".json");
                if (jsonFile.isFile()) knownVersions.put(child.getName(), jsonFile.lastModified());
            }
        }
        return new LoaderInstallSnapshot(knownVersions);
    }

    @Nullable
    private InstalledLoaderInstance createInstanceFromCompletedLoaderInstall(
            @NonNull String displayName,
            @NonNull File minecraftHome,
            @NonNull LoaderInstallSnapshot snapshot
    ) throws Exception {
        String lower = displayName.toLowerCase(Locale.US);
        if (!isNormalLoaderInstallerName(lower)) return null;

        InstalledVersionProfile profile = findInstalledLoaderVersionProfile(minecraftHome, lower, snapshot);
        if (profile == null) {
            Logging.i("LauncherSettings", "Loader installer finished, but no new/updated loader version JSON was found for " + displayName);
            return null;
        }

        if (!profile.minecraftVersionId.trim().isEmpty()) {
            ensureInheritedMinecraftVersionInstalled(minecraftHome, profile.minecraftVersionId);
        }

        String instanceName = buildUniqueLoaderInstanceName(profile.loader, profile.minecraftVersionId);
        LauncherInstance instance = LauncherInstanceManager.createInstance(
                this,
                instanceName,
                profile.loader,
                profile.versionId,
                profile.minecraftVersionId,
                profile.versionType,
                null,
                true
        );
        ensureLoaderInstanceFolders(instance);
        Logging.i("LauncherSettings", "Created instance " + instance.getName()
                + " for loader version " + profile.versionId
                + " inheriting " + profile.minecraftVersionId);
        return new InstalledLoaderInstance(instance.getName(), profile.versionId, profile.minecraftVersionId);
    }

    private boolean isNormalLoaderInstallerName(@NonNull String lowerName) {
        return isFabricInstallerName(lowerName)
                || isQuiltInstallerName(lowerName)
                || isForgeInstallerName(lowerName)
                || isNeoForgeInstallerName(lowerName)
                || isOptiFineInstallerName(lowerName);
    }

    @Nullable
    private InstalledVersionProfile findInstalledLoaderVersionProfile(
            @NonNull File minecraftHome,
            @NonNull String lowerInstallerName,
            @NonNull LoaderInstallSnapshot snapshot
    ) {
        File versionsRoot = new File(minecraftHome, "versions");
        File[] children = versionsRoot.listFiles();
        if (children == null) return null;

        InstalledVersionProfile best = null;
        long bestModified = Long.MIN_VALUE;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            String versionId = child.getName();
            File jsonFile = new File(child, versionId + ".json");
            if (!jsonFile.isFile()) continue;

            long modified = jsonFile.lastModified();
            Long beforeModified = snapshot.knownVersions.get(versionId);
            boolean newOrUpdated = beforeModified == null || modified > beforeModified;

            try {
                JSONObject json = new JSONObject(readTextFile(jsonFile));
                InstalledVersionProfile profile = parseInstalledLoaderProfile(versionId, json, lowerInstallerName);
                if (profile == null) continue;

                // Prefer versions that were created/updated by this run. If the
                // installer only revalidated an existing profile, still fall back
                // to the newest matching profile so repeat installs work.
                long score = modified + (newOrUpdated ? 1000000000000L : 0L);
                if (score > bestModified) {
                    bestModified = score;
                    best = profile;
                }
            } catch (Throwable throwable) {
                Logging.i("LauncherSettings", "Unable to inspect installed version "
                        + versionId + ": " + throwable.getMessage());
            }
        }
        return best;
    }

    @Nullable
    private InstalledVersionProfile parseInstalledLoaderProfile(
            @NonNull String versionId,
            @NonNull JSONObject json,
            @NonNull String lowerInstallerName
    ) {
        String id = json.optString("id", versionId).trim();
        if (id.isEmpty()) id = versionId;
        String lowerId = id.toLowerCase(Locale.US);
        String lowerVersionId = versionId.toLowerCase(Locale.US);
        String inheritsFrom = json.optString("inheritsFrom", "").trim();
        String versionType = json.optString("type", "release").trim();
        if (versionType.isEmpty()) versionType = "release";

        String loader = "";
        if (isForgeInstallerName(lowerInstallerName)
                && (lowerId.contains("forge") || lowerVersionId.contains("forge"))) {
            loader = "Forge";
        } else if (isNeoForgeInstallerName(lowerInstallerName)
                && (lowerId.contains("neoforge") || lowerVersionId.contains("neoforge"))) {
            loader = "NeoForge";
        } else if (isFabricInstallerName(lowerInstallerName)
                && (lowerId.contains("fabric") || lowerVersionId.contains("fabric"))) {
            loader = "Fabric";
        } else if (isQuiltInstallerName(lowerInstallerName)
                && (lowerId.contains("quilt") || lowerVersionId.contains("quilt"))) {
            loader = "Quilt";
        } else if (isOptiFineInstallerName(lowerInstallerName)
                && (lowerId.contains("optifine") || lowerVersionId.contains("optifine"))) {
            loader = "OptiFine";
        }
        if (loader.isEmpty()) return null;

        String minecraftVersion = inheritsFrom;
        if (minecraftVersion.trim().isEmpty()) {
            minecraftVersion = guessMinecraftVersionFromLoaderVersionId(id, loader);
        }
        if (minecraftVersion.trim().isEmpty()) minecraftVersion = id;

        return new InstalledVersionProfile(loader, id, minecraftVersion, versionType);
    }

    @NonNull
    private String guessMinecraftVersionFromLoaderVersionId(@NonNull String versionId, @NonNull String loader) {
        String lowerLoader = loader.toLowerCase(Locale.US);
        String value = versionId;
        if ("Fabric".equals(loader) || "Quilt".equals(loader)) {
            int lastDash = value.lastIndexOf('-');
            return lastDash >= 0 && lastDash + 1 < value.length() ? value.substring(lastDash + 1) : "";
        }
        if ("OptiFine".equals(loader)) {
            Matcher matcher = Pattern.compile("(?i)([0-9]+(?:\\.[0-9]+){1,2})[-_]?OptiFine").matcher(value);
            if (matcher.find()) return matcher.group(1);
            matcher = Pattern.compile("(?i)OptiFine[_-]([0-9]+(?:\\.[0-9]+){1,2})").matcher(value);
            if (matcher.find()) return matcher.group(1);
        }
        if (value.toLowerCase(Locale.US).contains(lowerLoader)) {
            int marker = value.toLowerCase(Locale.US).indexOf(lowerLoader);
            String prefix = value.substring(0, marker).replaceAll("[-_]+$", "");
            return prefix.trim();
        }
        return "";
    }

    private void ensureInheritedMinecraftVersionInstalled(
            @NonNull File minecraftHome,
            @NonNull String minecraftVersion
    ) throws Exception {
        /*
         * A loader profile is not launch-ready merely because its inherited
         * version JSON and client jar exist. OptiFine also needs every vanilla
         * library, the asset index, asset objects, and the Android native AAR
         * extraction produced by MinecraftVersionInstaller.
         *
         * The old shortcut downloaded only <version>.json and <version>.jar.
         * That allowed OptiFine to finish and the instance to be created, but
         * the first launch then reported missing files until Repair Instance
         * ran the complete vanilla installer. Always run the complete installer
         * here; it hash-checks existing files and downloads only missing or
         * damaged entries, so already-complete versions are retained efficiently.
         */
        PathManager.initContextConstants(this);
        File activeMinecraftHome = new File(PathManager.DIR_MINECRAFT_HOME);
        if (!activeMinecraftHome.getCanonicalFile().equals(minecraftHome.getCanonicalFile())) {
            throw new IllegalStateException(
                    "OptiFine base install path does not match the active launcher storage location: "
                            + minecraftHome.getAbsolutePath()
            );
        }

        MinecraftVersionMetadata metadata = findMinecraftVersionMetadata(minecraftVersion);
        if (metadata == null || metadata.url.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Loader installed, but inherited Minecraft version metadata was not found: "
                            + minecraftVersion
            );
        }

        Logging.i("LauncherSettings", "Verifying complete inherited Minecraft version " + minecraftVersion);
        updateJarExecutionSummary("Verifying complete Minecraft base " + minecraftVersion + "...");
        appendJarExecutionProgressLine("Checking vanilla files, libraries, assets, and natives for "
                + minecraftVersion + "...");

        MinecraftVersion manifestVersion = new MinecraftVersion(
                metadata.id,
                metadata.type,
                metadata.releaseTime,
                metadata.url
        );
        MinecraftVersionInstaller.installVanillaVersion(
                this,
                manifestVersion,
                (progress, message) -> {
                    Logging.i("LauncherSettings", "OptiFine base " + progress + "%: " + message);
                    appendJarExecutionProgressLine(message);
                    updateJarExecutionSummary(message);
                }
        );
    }

    @Nullable
    private MinecraftVersionMetadata findMinecraftVersionMetadata(@NonNull String minecraftVersion) throws Exception {
        JSONObject manifest = new JSONObject(httpGetTextForJarInstaller("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"));
        org.json.JSONArray versions = manifest.optJSONArray("versions");
        if (versions == null) return null;
        for (int i = 0; i < versions.length(); i++) {
            JSONObject item = versions.optJSONObject(i);
            if (item == null) continue;
            if (minecraftVersion.equals(item.optString("id", ""))) {
                return new MinecraftVersionMetadata(
                        item.optString("id", ""),
                        item.optString("type", "release"),
                        item.optString("releaseTime", ""),
                        item.optString("url", "")
                );
            }
        }
        return null;
    }

    @NonNull
    private String httpGetTextForJarInstaller(@NonNull String urlText) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "DroidBridge Launcher");
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        if (stream == null) throw new IllegalStateException("HTTP " + code + " for " + urlText);
        try (InputStream input = stream; java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            String text = output.toString(StandardCharsets.UTF_8.name());
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + ": " + text);
            return text;
        } finally {
            connection.disconnect();
        }
    }

    private void downloadFileForJarInstaller(@NonNull String urlText, @NonNull File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Unable to create folder: " + parent.getAbsolutePath());
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent", "DroidBridge Launcher");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + " while downloading " + urlText);
        }
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        } finally {
            connection.disconnect();
        }
        if (target.isFile() && !target.delete()) {
            throw new IllegalStateException("Unable to replace existing file: " + target.getAbsolutePath());
        }
        if (!tmp.renameTo(target)) {
            throw new IllegalStateException("Unable to move downloaded file to: " + target.getAbsolutePath());
        }
    }

    @NonNull
    private String buildUniqueLoaderInstanceName(@NonNull String loader, @NonNull String minecraftVersion) {
        String base = loader + " (" + minecraftVersion + ")";
        HashSet<String> existingNames = new HashSet<>();
        try {
            for (LauncherInstance instance : LauncherInstanceManager.findInstances(this)) {
                existingNames.add(instance.getName().toLowerCase(Locale.US));
            }
        } catch (Throwable throwable) {
            Logging.i("LauncherSettings", "Unable to collect existing instance names: " + throwable.getMessage());
        }

        if (!existingNames.contains(base.toLowerCase(Locale.US))) return base;
        for (int i = 1; i < 1000; i++) {
            String candidate = base + " (" + i + ")";
            if (!existingNames.contains(candidate.toLowerCase(Locale.US))) return candidate;
        }
        return base + " (" + System.currentTimeMillis() + ")";
    }

    private void ensureLoaderInstanceFolders(@NonNull LauncherInstance instance) {
        File gameDir = instance.getGameDirectory();
        String[] folders = new String[]{"mods", "config", "resourcepacks", "shaderpacks", "saves", "logs"};
        for (String folder : folders) {
            File dir = new File(gameDir, folder);
            if (!dir.exists() && !dir.mkdirs()) {
                Logging.i("LauncherSettings", "Unable to create instance folder: " + dir.getAbsolutePath());
            }
        }
    }

    @NonNull
    private String readTextFile(@NonNull File file) throws Exception {
        try (InputStream input = new java.io.FileInputStream(file); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void writeTextFile(@NonNull File file, @NonNull String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Unable to create folder: " + parent.getAbsolutePath());
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }


    private static final class FabricMinecraftVersion {
        @NonNull final String version;
        final boolean stable;

        FabricMinecraftVersion(@NonNull String version, boolean stable) {
            this.version = version;
            this.stable = stable;
        }
    }

    private static final class FabricLoaderVersion {
        @NonNull final String version;
        final boolean stable;

        FabricLoaderVersion(@NonNull String version, boolean stable) {
            this.version = version;
            this.stable = stable;
        }
    }

    private static final class FabricVersionList {
        @NonNull final ArrayList<FabricMinecraftVersion> stableMinecraftVersions;
        @NonNull final ArrayList<FabricMinecraftVersion> snapshotMinecraftVersions;
        @NonNull final ArrayList<FabricLoaderVersion> stableLoaders;
        @NonNull final ArrayList<FabricLoaderVersion> unstableLoaders;

        FabricVersionList(
                @NonNull ArrayList<FabricMinecraftVersion> stableMinecraftVersions,
                @NonNull ArrayList<FabricMinecraftVersion> snapshotMinecraftVersions,
                @NonNull ArrayList<FabricLoaderVersion> stableLoaders,
                @NonNull ArrayList<FabricLoaderVersion> unstableLoaders
        ) {
            this.stableMinecraftVersions = stableMinecraftVersions;
            this.snapshotMinecraftVersions = snapshotMinecraftVersions;
            this.stableLoaders = stableLoaders;
            this.unstableLoaders = unstableLoaders;
        }

        boolean isEmpty() {
            return stableMinecraftVersions.isEmpty()
                    && snapshotMinecraftVersions.isEmpty()
                    || (stableLoaders.isEmpty() && unstableLoaders.isEmpty());
        }
    }


    private static final class BtaVersion {
        @NonNull final String versionName;
        @NonNull final String buildType;
        @NonNull final String downloadUrl;
        @NonNull final String iconUrl;
        @NonNull final String label;
        final boolean tested;

        BtaVersion(
                @NonNull String versionName,
                @NonNull String buildType,
                @NonNull String downloadUrl,
                @NonNull String iconUrl,
                @NonNull String label,
                boolean tested
        ) {
            this.versionName = versionName;
            this.buildType = buildType;
            this.downloadUrl = downloadUrl;
            this.iconUrl = iconUrl;
            this.label = label;
            this.tested = tested;
        }
    }

    private static final class BtaVersionList {
        @NonNull final ArrayList<BtaVersion> testedVersions;
        @NonNull final ArrayList<BtaVersion> untestedVersions;
        @NonNull final ArrayList<BtaVersion> nightlyVersions;

        BtaVersionList(
                @NonNull ArrayList<BtaVersion> testedVersions,
                @NonNull ArrayList<BtaVersion> untestedVersions,
                @NonNull ArrayList<BtaVersion> nightlyVersions
        ) {
            this.testedVersions = testedVersions;
            this.untestedVersions = untestedVersions;
            this.nightlyVersions = nightlyVersions;
        }

        boolean isEmpty() {
            return testedVersions.isEmpty() && untestedVersions.isEmpty() && nightlyVersions.isEmpty();
        }
    }

    private static final class LoaderInstallSnapshot {
        @NonNull final HashMap<String, Long> knownVersions;

        LoaderInstallSnapshot(@NonNull HashMap<String, Long> knownVersions) {
            this.knownVersions = knownVersions;
        }
    }

    private static final class InstalledVersionProfile {
        @NonNull final String loader;
        @NonNull final String versionId;
        @NonNull final String minecraftVersionId;
        @NonNull final String versionType;

        InstalledVersionProfile(
                @NonNull String loader,
                @NonNull String versionId,
                @NonNull String minecraftVersionId,
                @NonNull String versionType
        ) {
            this.loader = loader;
            this.versionId = versionId;
            this.minecraftVersionId = minecraftVersionId;
            this.versionType = versionType;
        }
    }

    private static final class InstalledLoaderInstance {
        @NonNull final String instanceName;
        @NonNull final String versionId;
        @NonNull final String minecraftVersionId;

        InstalledLoaderInstance(
                @NonNull String instanceName,
                @NonNull String versionId,
                @NonNull String minecraftVersionId
        ) {
            this.instanceName = instanceName;
            this.versionId = versionId;
            this.minecraftVersionId = minecraftVersionId;
        }
    }

    private static final class MinecraftVersionMetadata {
        @NonNull final String id;
        @NonNull final String type;
        @NonNull final String releaseTime;
        @NonNull final String url;

        MinecraftVersionMetadata(
                @NonNull String id,
                @NonNull String type,
                @NonNull String releaseTime,
                @NonNull String url
        ) {
            this.id = id;
            this.type = type;
            this.releaseTime = releaseTime;
            this.url = url;
        }
    }

    private void showJarExecutionProgressDialog(@NonNull String displayName) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;

            // The user can change launcher themes while Settings stays alive. Resolve the
            // active palette again when this progress dialog is created so the installer
            // console never keeps stale colors from a previous theme.
            resolveDialogPaletteFromActiveTheme();

            if (jarExecutionProgressDialog != null && jarExecutionProgressDialog.isShowing()) {
                jarExecutionProgressDialog.dismiss();
            }
            jarExecutionProgressLines.clear();

            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(18), dp(16), dp(18), dp(12));
            root.setBackgroundColor(COLOR_DIALOG_BG);

            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);

            ProgressBar progressBar = new ProgressBar(this);
            progressBar.setIndeterminate(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                progressBar.setIndeterminateTintList(ColorStateList.valueOf(COLOR_ACCENT));
            }
            LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(36), dp(36));
            progressParams.rightMargin = dp(12);
            header.addView(progressBar, progressParams);

            LinearLayout titleColumn = new LinearLayout(this);
            titleColumn.setOrientation(LinearLayout.VERTICAL);

            jarExecutionProgressTitleView = new TextView(this);
            jarExecutionProgressTitleView.setText("Installing " + displayName);
            jarExecutionProgressTitleView.setTextColor(COLOR_TEXT_PRIMARY);
            jarExecutionProgressTitleView.setTextSize(17f);
            jarExecutionProgressTitleView.setTypeface(Typeface.DEFAULT_BOLD);
            titleColumn.addView(jarExecutionProgressTitleView);

            jarExecutionProgressStatusView = new TextView(this);
            jarExecutionProgressStatusView.setText("Preparing installer...");
            jarExecutionProgressStatusView.setTextColor(COLOR_TEXT_SECONDARY);
            jarExecutionProgressStatusView.setTextSize(13f);
            titleColumn.addView(jarExecutionProgressStatusView);

            header.addView(titleColumn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            root.addView(header);

            TextView warning = new TextView(this);
            warning.setText("Do not close DroidBridge while this is running. Forge and Fabric can look paused while they download, verify, or patch files.");
            warning.setTextColor(COLOR_TEXT_MUTED);
            warning.setTextSize(12f);
            LinearLayout.LayoutParams warningParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            warningParams.topMargin = dp(10);
            root.addView(warning, warningParams);

            jarExecutionProgressLogView = new TextView(this);
            // Use the same high-contrast palette as the rest of the active theme.  The
            // previous implementation put themed text on a permanently near-black
            // console, which became unreadable in Light/Orange/Indigo and other themes.
            jarExecutionProgressLogView.setTextColor(COLOR_TEXT_PRIMARY);
            jarExecutionProgressLogView.setTextSize(11f);
            jarExecutionProgressLogView.setTypeface(Typeface.MONOSPACE);
            jarExecutionProgressLogView.setText("Waiting for output...\n");
            jarExecutionProgressLogView.setPadding(dp(8), dp(8), dp(8), dp(8));

            ScrollView logScroll = new ScrollView(this);
            logScroll.setFillViewport(false);
            GradientDrawable logBackground = new GradientDrawable();
            // Theme the live installer console instead of forcing a black panel.
            // Light/accent themes get a light card; Dark gets the normal dark card.
            logBackground.setColor(COLOR_CARD_BG);
            logBackground.setStroke(dp(1), COLOR_CARD_STROKE);
            logBackground.setCornerRadius(dp(10));
            logScroll.setBackground(logBackground);
            logScroll.addView(jarExecutionProgressLogView);
            LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(230)
            );
            logParams.topMargin = dp(12);
            root.addView(logScroll, logParams);

            AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                    .setView(root)
                    .setPositiveButton("Run in background", null)
                    .create();
            jarExecutionProgressDialog = dialog;
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnShowListener(dialogInterface -> {
                styleLauncherDialogChrome(dialog);
                if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                        dialog.dismiss();
                        if (jarExecutionProgressDialog == dialog) {
                            jarExecutionProgressDialog = null;
                            jarExecutionProgressTitleView = null;
                            jarExecutionProgressStatusView = null;
                            jarExecutionProgressLogView = null;
                        }
                    });
                }
            });
            dialog.show();
        });
    }

    private void appendJarExecutionProgressLine(@NonNull String line) {
        runOnUiThread(() -> {
            if (jarExecutionProgressStatusView != null) {
                jarExecutionProgressStatusView.setText(line);
            }
            if (jarExecutionProgressLogView != null) {
                jarExecutionProgressLines.add(line);
                while (jarExecutionProgressLines.size() > 90) {
                    jarExecutionProgressLines.remove(0);
                }
                StringBuilder builder = new StringBuilder();
                for (String item : jarExecutionProgressLines) {
                    builder.append(item).append('\n');
                }
                jarExecutionProgressLogView.setText(builder.toString());
            }
        });
    }

    private void finishJarExecutionProgressDialog(@NonNull String finalSummary, boolean success) {
        runOnUiThread(() -> {
            if (jarExecutionProgressTitleView != null) {
                jarExecutionProgressTitleView.setText(success ? "Installer complete" : "Installer finished with errors");
            }
            if (jarExecutionProgressStatusView != null) {
                jarExecutionProgressStatusView.setText(success ? "Done" : "Check the log below");
            }
            if (jarExecutionProgressLogView != null) {
                jarExecutionProgressLogView.setText(finalSummary);
            }

            if (success) {
                final AlertDialog completedDialog = jarExecutionProgressDialog;
                if (completedDialog != null && completedDialog.isShowing()) {
                    View decorView = completedDialog.getWindow() != null
                            ? completedDialog.getWindow().getDecorView()
                            : null;
                    Runnable closeCompletedDialog = () -> {
                        if (jarExecutionProgressDialog == completedDialog && completedDialog.isShowing()) {
                            completedDialog.dismiss();
                            jarExecutionProgressDialog = null;
                            jarExecutionProgressTitleView = null;
                            jarExecutionProgressStatusView = null;
                            jarExecutionProgressLogView = null;
                        }
                    };
                    if (decorView != null) {
                        decorView.postDelayed(closeCompletedDialog, 650L);
                    } else {
                        closeCompletedDialog.run();
                    }
                } else {
                    jarExecutionProgressDialog = null;
                    jarExecutionProgressTitleView = null;
                    jarExecutionProgressStatusView = null;
                    jarExecutionProgressLogView = null;
                }
                return;
            }

            if (jarExecutionProgressDialog != null && jarExecutionProgressDialog.isShowing()
                    && jarExecutionProgressDialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
                jarExecutionProgressDialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("Close");
                jarExecutionProgressDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    if (jarExecutionProgressDialog != null) jarExecutionProgressDialog.dismiss();
                    jarExecutionProgressDialog = null;
                    jarExecutionProgressTitleView = null;
                    jarExecutionProgressStatusView = null;
                    jarExecutionProgressLogView = null;
                });
            }
        });
    }

    private void resetJarExecutionButton() {
        if (binding != null && binding.buttonExecuteJarFile != null) {
            binding.buttonExecuteJarFile.setEnabled(true);
            binding.buttonExecuteJarFile.setText("Execute .jar file");
        }
    }

    private void updateJarExecutionSummary(@NonNull String text) {
        runOnUiThread(() -> {
            getSharedPreferences(JAR_EXECUTION_PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(JAR_EXECUTION_LAST_SUMMARY_KEY, text)
                    .apply();
            if (binding != null && binding.textJarExecutionSummary != null) {
                binding.textJarExecutionSummary.setText(text);
            }
        });
    }

    @NonNull
    private ArrayList<String> splitCommandLineArguments(@NonNull String rawArguments) {
        ArrayList<String> result = new ArrayList<>();
        String value = rawArguments.trim();
        if (value.isEmpty()) return result;

        StringBuilder current = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        boolean escaping = false;

        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);

            if (escaping) {
                current.append(ch);
                escaping = false;
                continue;
            }

            if (ch == '\\') {
                escaping = true;
                continue;
            }

            if (ch == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
                continue;
            }

            if (ch == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
                continue;
            }

            if (Character.isWhitespace(ch) && !inSingleQuotes && !inDoubleQuotes) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }

            current.append(ch);
        }

        if (escaping) {
            current.append('\\');
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    @NonNull
    private String joinCommandForDisplay(@NonNull List<String> command) {
        StringBuilder builder = new StringBuilder();
        for (String part : command) {
            if (builder.length() > 0) builder.append(' ');
            builder.append(quoteCommandPartForDisplay(part));
        }
        return builder.toString();
    }

    @NonNull
    private String quoteCommandPartForDisplay(@NonNull String value) {
        if (value.indexOf(' ') < 0 && value.indexOf('\t') < 0 && value.indexOf('"') < 0) {
            return value;
        }
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    @NonNull
    private File resolveJarExecutionWorkingDirectory() {
        PathManager.initContextConstants(this);
        File minecraftHome = new File(PathManager.DIR_MINECRAFT_HOME);
        if (!minecraftHome.exists()) {
            //noinspection ResultOfMethodCallIgnored
            minecraftHome.mkdirs();
        }
        return minecraftHome.isDirectory() ? minecraftHome : getFilesDir();
    }


    private boolean isFabricInstallerName(@NonNull String lowerName) {
        return lowerName.contains("fabric-installer")
                || lowerName.contains("fabric_installer");
    }

    private boolean isQuiltInstallerName(@NonNull String lowerName) {
        return lowerName.contains("quilt-installer")
                || lowerName.contains("quilt_installer");
    }

    private boolean isNeoForgeInstallerName(@NonNull String lowerName) {
        return lowerName.contains("neoforge")
                || lowerName.contains("neo-forge")
                || lowerName.contains("neo_forge");
    }

    private boolean isForgeInstallerName(@NonNull String lowerName) {
        return !isNeoForgeInstallerName(lowerName)
                && (lowerName.contains("forge") || lowerName.contains("minecraftforge"));
    }

    private boolean isOptiFineInstallerName(@NonNull String lowerName) {
        return lowerName.contains("optifine");
    }

    private boolean isBtaInstallerName(@NonNull String lowerName) {
        return lowerName.contains("bta-installer")
                || lowerName.contains("bta_installer")
                || lowerName.contains("betterthanadventure")
                || lowerName.contains("better-than-adventure")
                || lowerName.contains("better_than_adventure")
                || lowerName.contains("bta");
    }

    private boolean hasZalithStyleOptiFineAgents() {
        try {
            LibPath.refresh();
            return LibPath.FORGE_INSTALLER != null && LibPath.FORGE_INSTALLER.isFile()
                    && LibPath.OPTIFINE_RENAMER != null && LibPath.OPTIFINE_RENAMER.isFile();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean addZalithStyleLoaderVmArguments(
            @NonNull ArrayList<String> command,
            @NonNull String displayName
    ) {
        String lower = displayName.toLowerCase(Locale.US);
        if (!isOptiFineInstallerName(lower) || !hasZalithStyleOptiFineAgents()) {
            return false;
        }

        /*
         * the prior implementation's InstallArgsUtils does not launch OptiFine as a normal Swing
         * installer. It injects forge_installer.jar with OFNPS and
         * OptiFineRenamer.jar with the target profile name, then launches the
         * OptiFine jar. Reuse that launcher-side strategy here and keep Cacio
         * out of this ProcessBuilder path completely.
         */
        String customName = buildOptiFineProfileName(displayName);
        command.add("-javaagent:" + LibPath.FORGE_INSTALLER.getAbsolutePath() + "=OFNPS");
        command.add("-javaagent:" + LibPath.OPTIFINE_RENAMER.getAbsolutePath() + "=" + customName);
        return true;
    }

    @NonNull
    private String normalizeLoaderJarArguments(
            @NonNull String displayName,
            @NonNull String argumentsText,
            boolean optiFineAgentMode
    ) {
        String lower = displayName.toLowerCase(Locale.US);
        String trimmed = argumentsText.trim();
        if (isOptiFineInstallerName(lower)) {
            if (optiFineAgentMode) {
                // Old fallback suggestions used --installClient. In agent mode that
                // is no longer needed and can make OptiFine's GUI-first main path run.
                if (trimmed.isEmpty()
                        || "--installClient".equalsIgnoreCase(trimmed)
                        || trimmed.toLowerCase(Locale.US).startsWith("--installclient ")) {
                    return "";
                }
            } else if (trimmed.isEmpty()) {
                return "--installClient";
            }
        }
        return argumentsText;
    }

    @NonNull
    private String buildOptiFineProfileName(@NonNull String displayName) {
        String name = displayName;
        if (name.toLowerCase(Locale.US).endsWith(".jar")) {
            name = name.substring(0, name.length() - 4);
        }

        Matcher matcher = Pattern.compile("(?i)OptiFine_([0-9]+(?:\\.[0-9]+){1,2})_(.+)").matcher(name);
        if (matcher.matches()) {
            String mc = matcher.group(1);
            String of = matcher.group(2).replaceAll("[^A-Za-z0-9._+-]", "_");
            return mc + "-OptiFine_" + of;
        }

        name = name.replaceAll("[^A-Za-z0-9._+-]", "_");
        if (name.trim().isEmpty()) return "OptiFine";
        return name;
    }

    private void storePendingOptiFineStandaloneInstall(
            @NonNull String profileName,
            @NonNull String minecraftVersion,
            @NonNull String displayName
    ) {
        getSharedPreferences(JAR_EXECUTION_PREFS, MODE_PRIVATE)
                .edit()
                .putString(JAR_EXECUTION_PENDING_OF_PROFILE_KEY, profileName)
                .putString(JAR_EXECUTION_PENDING_OF_MC_KEY, minecraftVersion)
                .putString(JAR_EXECUTION_PENDING_OF_DISPLAY_KEY, displayName)
                .putLong(JAR_EXECUTION_PENDING_OF_STARTED_KEY, System.currentTimeMillis())
                .apply();
    }

    private void clearPendingOptiFineStandaloneInstall() {
        getSharedPreferences(JAR_EXECUTION_PREFS, MODE_PRIVATE)
                .edit()
                .remove(JAR_EXECUTION_PENDING_OF_PROFILE_KEY)
                .remove(JAR_EXECUTION_PENDING_OF_MC_KEY)
                .remove(JAR_EXECUTION_PENDING_OF_DISPLAY_KEY)
                .remove(JAR_EXECUTION_PENDING_OF_STARTED_KEY)
                .apply();
    }

    private void checkPendingOptiFineStandaloneInstall() {
        SharedPreferences prefs = getSharedPreferences(JAR_EXECUTION_PREFS, MODE_PRIVATE);
        String profileName = prefs.getString(JAR_EXECUTION_PENDING_OF_PROFILE_KEY, "");
        if (profileName == null || profileName.trim().isEmpty()) return;

        String minecraftVersion = prefs.getString(JAR_EXECUTION_PENDING_OF_MC_KEY, "");
        String displayName = prefs.getString(JAR_EXECUTION_PENDING_OF_DISPLAY_KEY, "OptiFine");
        long started = prefs.getLong(JAR_EXECUTION_PENDING_OF_STARTED_KEY, 0L);

        Thread thread = new Thread(() -> {
            try {
                File minecraftHome = resolveJarExecutionWorkingDirectory();
                File versionDir = new File(new File(minecraftHome, "versions"), profileName);
                File versionJson = new File(versionDir, profileName + ".json");
                if (!versionJson.isFile()) {
                    long age = started > 0L ? System.currentTimeMillis() - started : 0L;
                    if (age > 90_000L) {
                        updateJarExecutionSummary("Waiting for OptiFine installer to finish. If the installer was closed or crashed, run " + displayName + " again.");
                    }
                    return;
                }

                JSONObject json = new JSONObject(readTextFile(versionJson));
                String inherited = json.optString("inheritsFrom", minecraftVersion == null ? "" : minecraftVersion).trim();
                if (inherited.isEmpty()) inherited = minecraftVersion == null ? "" : minecraftVersion;
                String type = json.optString("type", "release").trim();
                if (type.isEmpty()) type = "release";

                if (!inherited.isEmpty()) {
                    ensureInheritedMinecraftVersionInstalled(minecraftHome, inherited);
                }

                for (LauncherInstance existing : LauncherInstanceManager.findInstances(this)) {
                    if (profileName.equals(existing.getBaseVersionId())) {
                        clearPendingOptiFineStandaloneInstall();
                        String summary = "OptiFine profile installed and instance already exists: " + existing.getName();
                        runOnUiThread(() -> {
                            updateJarExecutionSummary(summary);
                            finishJarExecutionProgressDialog(summary, true);
                        });
                        return;
                    }
                }

                String instanceName = buildUniqueLoaderInstanceName("OptiFine", inherited.isEmpty() ? profileName : inherited);
                LauncherInstance instance = LauncherInstanceManager.createInstance(
                        this,
                        instanceName,
                        "OptiFine",
                        profileName,
                        inherited.isEmpty() ? profileName : inherited,
                        type,
                        null,
                        true
                );
                ensureLoaderInstanceFolders(instance);
                clearPendingOptiFineStandaloneInstall();
                String summary = "Created DroidBridge instance: " + instance.getName()
                        + "\nLaunch version: " + profileName
                        + "\nMinecraft version: " + (inherited.isEmpty() ? profileName : inherited);
                runOnUiThread(() -> {
                    updateJarExecutionSummary(summary);
                    finishJarExecutionProgressDialog(summary, true);
                    Toast.makeText(this, "Created " + instance.getName(), Toast.LENGTH_LONG).show();
                });
            } catch (Throwable throwable) {
                Logging.e("LauncherSettings", "Unable to finalize pending OptiFine install", throwable);
                String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.toString();
                updateJarExecutionSummary("OptiFine installed, but DroidBridge could not create its instance yet: " + message);
            }
        }, "DroidBridgePendingOptiFineFinalize");
        thread.start();
    }



    private boolean shouldUseJavaGuiLauncherActivity(@NonNull String displayName) {
        /*
         * Disable the Activity/Cacio/JLI bridge for Execute Jar.
         * Logcat confirmed it aborts on this Android build with:
         *   FORTIFY: pthread_mutex_lock called on a destroyed mutex
         * after JLI_Launch. Normal loader installers must use the ProcessBuilder
         * command-line path and then be converted into DroidBridge instances.
         * BTA/updater jars still need launcher-native GUI support later.
         */
        return false;
    }

    private boolean openJavaGuiLauncherActivityIfAvailable(
            @NonNull Uri jarUri,
            @NonNull String displayName,
            @NonNull String argumentsText
    ) {
        String[] candidateClassNames = new String[]{
                "ca.dnamobile.droidbridgelauncher.DroidBridgeStandaloneJarActivity",
        };

        for (String className : candidateClassNames) {
            try {
                Class<?> activityClass = Class.forName(className);
                Intent intent = new Intent(this, activityClass);
                intent.putExtra("modUri", jarUri);
                intent.putExtra("forceShowLog", true);
                intent.putExtra("openLogOutput", true);
                intent.putExtra("subscribe_jvm_exit_event", true);

                boolean optiFine = isOptiFineInstallerName(displayName.toLowerCase(Locale.US));
                if (optiFine) {
                    // Match the prior implementation's OptiFine installer path: use Internal-8 with
                    // forge_installer.jar=OFNPS and OptiFineRenamer.jar.
                    intent.putExtra("jre_name", "Internal-8");
                    File copiedJar = copyJarUriForGuiLauncher(jarUri, displayName);
                    boolean autoInstall = "__DROIDBRIDGE_OPTIFINE_INSTALL__".equals(argumentsText);
                    intent.putExtra("javaArgs", buildOptiFineJavaGuiArgs(copiedJar, displayName, autoInstall));
                } else {
                    intent.putExtra("jre_name", "Internal-17");
                    if (!isNullOrBlank(argumentsText)) {
                        intent.putExtra("javaArgs", "-jar " + quoteCommandPartForDisplay(copyJarUriForGuiLauncher(jarUri, displayName).getAbsolutePath()) + " " + argumentsText.trim());
                    }
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    getContentResolver().takePersistableUriPermission(jarUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) {
                }
                updateJarExecutionSummary("Opening Java GUI launcher for " + displayName + " using " + className + "...");
                startActivity(intent);
                return true;
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable throwable) {
                Logging.e("LauncherSettings", "Unable to open Java GUI launcher Activity", throwable);
                updateJarExecutionSummary("Java GUI launcher Activity was found but could not be opened: "
                        + (throwable.getMessage() != null ? throwable.getMessage() : throwable.toString()));
                return true;
            }
        }
        return false;
    }

    @NonNull
    private String buildOptiFineJavaGuiArgs(
            @NonNull File copiedJar,
            @NonNull String displayName,
            boolean autoInstall
    ) {
        StringBuilder args = new StringBuilder();
        if (autoInstall && hasZalithStyleOptiFineAgents()) {
            String customName = buildOptiFineProfileName(displayName);
            args.append("-javaagent:")
                    .append(quoteCommandPartForDisplay(LibPath.FORGE_INSTALLER.getAbsolutePath()))
                    .append("=OFNPS ");
            args.append("-javaagent:")
                    .append(quoteCommandPartForDisplay(LibPath.OPTIFINE_RENAMER.getAbsolutePath()))
                    .append("=")
                    .append(quoteCommandPartForDisplay(customName))
                    .append(' ');
        }
        args.append("-jar ").append(quoteCommandPartForDisplay(copiedJar.getAbsolutePath()));
        return args.toString();
    }

    @NonNull
    private File copyJarUriForGuiLauncher(@NonNull Uri jarUri, @NonNull String displayName) throws Exception {
        File jarDir = new File(getCacheDir(), "jar_execution");
        if (!jarDir.exists() && !jarDir.mkdirs()) {
            throw new IllegalStateException("Could not create temporary jar execution folder.");
        }
        File copiedJar = new File(jarDir, sanitizeJarFileName(displayName));
        copyUriToFile(jarUri, copiedJar);
        return copiedJar;
    }

    private boolean shouldAttemptGuiJarCompatibility(@NonNull String displayName) {
        /*
         * Do not enable the old direct ProcessBuilder + Cacio compatibility path
         * for OptiFine anymore. Logcat showed it still starts with:
         *   -Djava.awt.headless=false
         *   -Dawt.toolkit=net.java.openjdk.cacio.ctc.CTCToolkit
         *   -Xbootclasspath/p:<cacio jars>
         * and then Internal-8 aborts in HotSpot AArch64 before the installer runs.
         *
         * OptiFine is handled above before generic jar execution. It follows
         * the prior implementation's standalone profile flow instead of being copied into a Forge
         * mods folder.
         */
        return false;
    }

    @Nullable
    private JarGuiCompatibility findJarGuiCompatibility(int requiredInstallerJava) {
        /*
         * Do not use Internal-8 for OptiFine anymore.
         * Logcat showed Internal-8 aborting before OptiFine could run:
         *   Internal Error (assembler_aarch64.hpp:237)
         *   guarantee(val < (1U << nbits)) failed: Field too big for insn
         *
         * Also do not force Internal-17 for every OptiFine jar. Newer OptiFine
         * installers can have Java 21+ main classes, and Java 17 dies before
         * Cacio/Swing opens with UnsupportedClassVersionError. Keep the same
         * external Cacio process route, but pick a runtime that can actually
         * load the installer class first.
         */
        LibPath.refresh();

        File cacio17Dir = LibPath.CACIO_17;
        if (cacio17Dir == null || !cacio17Dir.isDirectory()) return null;

        String cacio17Classpath = buildJarClassPathForInstaller(cacio17Dir);
        if (isNullOrBlank(cacio17Classpath)) return null;

        File cacioAgent = LibPath.CACIO_17_AGENT != null && LibPath.CACIO_17_AGENT.isFile()
                ? LibPath.CACIO_17_AGENT
                : null;

        for (String runtimeName : getOptiFineInstallerRuntimeCandidates(requiredInstallerJava)) {
            File javaExecutable = findSpecificJavaExecutableForJarExecution(runtimeName);
            if (javaExecutable == null || !javaExecutable.isFile()) continue;

            return new JarGuiCompatibility(
                    javaExecutable,
                    javaMajorForInstallerRuntimeName(runtimeName),
                    cacio17Dir,
                    cacio17Classpath,
                    cacioAgent,
                    null,
                    null
            );
        }
        return null;
    }

    @NonNull
    private String[] getOptiFineInstallerRuntimeCandidates(int requiredInstallerJava) {
        if (requiredInstallerJava >= 25) {
            /*
             * Do not silently route Java-25 GUI installers through the bundled
             * caciocavallo17 stack. Logcat from 2026-06-22 showed Java 25 can
             * load the OptiFine class, but Cacio fails before Swing opens with:
             *   ClassNotFoundException: sun/java2d/SurfaceManagerFactory
             *   java.awt.HeadlessException
             * That is a Cacio/Java-25 compatibility failure, not an OptiFine
             * class-version failure. A future Cacio25 stack can be added here,
             * but using caciocavallo17 with Internal-25 just gives a fake GUI
             * path that crashes every time.
             */
            return new String[0];
        }
        if (requiredInstallerJava >= 21) {
            /*
             * OptiFine 26.1.2 preview is Java 21 bytecode. Prefer Internal-21
             * because the bundled Cacio17 jars still work with the Java-21 AWT
             * internals, while Java 25 removed/changed the Java2D internals that
             * Cacio's agent probes and then AWT falls back to headless mode.
             */
            return new String[]{"Internal-21"};
        }
        if (requiredInstallerJava >= 17) {
            return new String[]{"Internal-17", "Internal-21"};
        }
        return new String[]{"Internal-17", "Internal-21"};
    }

    private int javaMajorForInstallerRuntimeName(@NonNull String runtimeName) {
        if (runtimeName.contains("25")) return 25;
        if (runtimeName.contains("21")) return 21;
        if (runtimeName.contains("17")) return 17;
        if (runtimeName.contains("8")) return 8;
        return 17;
    }

    @NonNull
    private String buildOptiFineInstallerRuntimeError(int requiredInstallerJava) {
        String runtimeHint;
        if (requiredInstallerJava >= 25) {
            return "This OptiFine installer requires Java " + requiredInstallerJava
                    + ", but DroidBridge's current caciocavallo17 GUI bridge is not compatible with Internal-25. "
                    + "Add a Java-25-compatible Cacio bridge before running this installer.";
        } else if (requiredInstallerJava >= 21) {
            runtimeHint = "Internal-21";
        } else {
            runtimeHint = "Internal-17/21";
        }

        String versionHint = requiredInstallerJava > 0
                ? " This installer requires Java " + requiredInstallerJava + "."
                : "";
        return "OptiFine standalone install needs " + runtimeHint
                + " and the caciocavallo17 GUI files."
                + versionHint
                + " Internal-25 is intentionally skipped here because Cacio falls back to headless mode on Java 25. "
                + "Reinstall DroidBridge components/runtimes, then try again.";
    }

    private int readJarMainClassJavaVersionForInstaller(@NonNull File jarFile) {
        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(jarFile)) {
            java.util.zip.ZipEntry manifestEntry = zipFile.getEntry("META-INF/MANIFEST.MF");
            if (manifestEntry == null) return -1;

            java.util.jar.Manifest manifest;
            try (InputStream manifestInput = zipFile.getInputStream(manifestEntry)) {
                manifest = new java.util.jar.Manifest(manifestInput);
            }
            java.util.jar.Attributes attributes = manifest.getMainAttributes();
            String mainClass = attributes.getValue("Main-Class");
            if (mainClass == null || mainClass.trim().isEmpty()) return -1;

            String classEntryName = mainClass.trim().replace('.', '/') + ".class";
            java.util.zip.ZipEntry classEntry = zipFile.getEntry(classEntryName);
            if (classEntry == null) return -1;

            try (InputStream input = zipFile.getInputStream(classEntry)) {
                byte[] header = new byte[8];
                if (input.read(header) != header.length) return -1;
                if ((header[0] & 0xFF) != 0xCA || (header[1] & 0xFF) != 0xFE
                        || (header[2] & 0xFF) != 0xBA || (header[3] & 0xFF) != 0xBE) {
                    return -1;
                }
                int classMajor = ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
                return classMajor >= 45 ? classMajor - 44 : -1;
            }
        } catch (Throwable throwable) {
            Logging.i("LauncherSettings", "Unable to infer OptiFine installer Java version: " + throwable);
            return -1;
        }
    }

    @Nullable
    private File findJava8ExecutableForJarExecution() {
        ArrayList<File> candidates = new ArrayList<>();
        addJava8RuntimeCandidates(candidates, getDataDir());
        addJava8RuntimeCandidates(candidates, new File(getApplicationInfo().dataDir));
        addJava8RuntimeCandidates(candidates, getFilesDir());

        File minecraftHome = new File(PathManager.DIR_MINECRAFT_HOME);
        addJava8RuntimeCandidates(candidates, minecraftHome);
        addJava8RuntimeCandidates(candidates, minecraftHome.getParentFile());

        for (File candidate : candidates) {
            if (candidate == null || !candidate.isFile()) continue;
            //noinspection ResultOfMethodCallIgnored
            candidate.setExecutable(true, true);
            if (candidate.canExecute() || isJavaExecutableName(candidate.getName())) return candidate;
        }
        return null;
    }

    private void addJava8RuntimeCandidates(@NonNull ArrayList<File> candidates, @Nullable File root) {
        if (root == null) return;
        candidates.add(new File(root, "runtimes/Internal-8/bin/java"));
        candidates.add(new File(root, "runtimes/Internal-8/bin/java.real"));
        candidates.add(new File(root, "runtime/Internal-8/bin/java"));
        candidates.add(new File(root, "runtime/Internal-8/bin/java.real"));
        candidates.add(new File(root, "jre-8/bin/java"));
        candidates.add(new File(root, "Internal-8/bin/java"));
    }

    @Nullable
    private File findSpecificJavaExecutableForJarExecution(@NonNull String runtimeName) {
        ArrayList<File> candidates = new ArrayList<>();
        addSpecificJavaRuntimeCandidates(candidates, getDataDir(), runtimeName);
        addSpecificJavaRuntimeCandidates(candidates, new File(getApplicationInfo().dataDir), runtimeName);
        addSpecificJavaRuntimeCandidates(candidates, getFilesDir(), runtimeName);

        File minecraftHome = new File(PathManager.DIR_MINECRAFT_HOME);
        addSpecificJavaRuntimeCandidates(candidates, minecraftHome, runtimeName);
        addSpecificJavaRuntimeCandidates(candidates, minecraftHome.getParentFile(), runtimeName);

        for (File candidate : candidates) {
            if (candidate == null || !candidate.isFile()) continue;
            //noinspection ResultOfMethodCallIgnored
            candidate.setExecutable(true, true);
            if (candidate.canExecute() || isJavaExecutableName(candidate.getName())) return candidate;
        }
        return null;
    }

    private void addSpecificJavaRuntimeCandidates(
            @NonNull ArrayList<File> candidates,
            @Nullable File root,
            @NonNull String runtimeName
    ) {
        if (root == null) return;
        candidates.add(new File(root, "runtimes/" + runtimeName + "/bin/java"));
        candidates.add(new File(root, "runtimes/" + runtimeName + "/bin/java.real"));
        candidates.add(new File(root, "runtime/" + runtimeName + "/bin/java"));
        candidates.add(new File(root, "runtime/" + runtimeName + "/bin/java.real"));
        candidates.add(new File(root, runtimeName + "/bin/java"));
        candidates.add(new File(root, runtimeName + "/bin/java.real"));
    }

    @NonNull
    private String buildJarClassPathForInstaller(@Nullable File directory) {
        if (directory == null || !directory.isDirectory()) return "";
        File[] jars = directory.listFiles((dir, name) -> name != null && name.toLowerCase(Locale.US).endsWith(".jar"));
        if (jars == null || jars.length == 0) return "";
        java.util.Arrays.sort(jars, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        StringBuilder builder = new StringBuilder();
        for (File jar : jars) {
            if (builder.length() > 0) builder.append(File.pathSeparator);
            builder.append(jar.getAbsolutePath());
        }
        return builder.toString();
    }

    @Nullable
    private File findCacioCavalloDirectory() {
        ArrayList<File> candidates = new ArrayList<>();
        File externalFiles = getExternalFilesDir(null);
        if (externalFiles != null) candidates.add(new File(externalFiles, "caciocavallo"));
        candidates.add(new File(getApplicationInfo().dataDir, "caciocavallo"));
        candidates.add(new File(getFilesDir(), "caciocavallo"));
        candidates.add(new File(getCacheDir(), "caciocavallo"));

        File minecraftHome = new File(PathManager.DIR_MINECRAFT_HOME);
        File launcherRoot = minecraftHome.getParentFile();
        if (launcherRoot != null) candidates.add(new File(launcherRoot, "caciocavallo"));

        for (File candidate : candidates) {
            if (candidate == null || !candidate.isDirectory()) continue;
            if (new File(candidate, "ResConfHack.jar").isFile()
                    && new File(candidate, "cacio-androidnw-1.10-SNAPSHOT.jar").isFile()
                    && new File(candidate, "cacio-shared-1.10-SNAPSHOT.jar").isFile()) {
                return candidate;
            }
        }
        return null;
    }

    @Nullable
    private File findComponentsDirectory() {
        ArrayList<File> candidates = new ArrayList<>();
        candidates.add(new File(getApplicationInfo().dataDir, "components"));
        candidates.add(new File(getFilesDir(), "components"));
        File externalFiles = getExternalFilesDir(null);
        if (externalFiles != null) candidates.add(new File(externalFiles, "components"));
        File minecraftHome = new File(PathManager.DIR_MINECRAFT_HOME);
        File launcherRoot = minecraftHome.getParentFile();
        if (launcherRoot != null) candidates.add(new File(launcherRoot, "components"));

        for (File candidate : candidates) {
            if (candidate != null && candidate.isDirectory()) return candidate;
        }
        return null;
    }

    private void addGuiJarCompatibilityArguments(
            @NonNull ArrayList<String> command,
            @NonNull JarGuiCompatibility compatibility
    ) {
        command.add("-Xint");
        command.add("-Djava.io.tmpdir=" + getCacheDir().getAbsolutePath());
        command.add("-Djava.awt.headless=false");
        command.add("-Dcacio.managed.screensize=" + getJarGuiCompatibilityScreenSize());
        command.add("-Dcacio.font.fontmanager=sun.awt.X11FontManager");
        command.add("-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler");
        command.add("-Dswing.defaultlaf=javax.swing.plaf.nimbus.NimbusLookAndFeel");

        if (compatibility.javaMajor >= 17) {
            command.add("-Dawt.toolkit=com.github.caciocavallosilano.cacio.ctc.CTCToolkit");
            command.add("-Djava.awt.graphicsenv=com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment");
            if (compatibility.javaMajor >= 21) {
                command.add("--enable-native-access=ALL-UNNAMED");
            }
            if (compatibility.cacioAgent != null && compatibility.cacioAgent.isFile()) {
                command.add("-javaagent:" + compatibility.cacioAgent.getAbsolutePath());
            }
            addJava17CacioModuleOpenArgs(command);
            command.add("-Xbootclasspath/a:" + compatibility.cacioClassPath);
        } else {
            command.add("-Dawt.toolkit=net.java.openjdk.cacio.ctc.CTCToolkit");
            command.add("-Djava.awt.graphicsenv=net.java.openjdk.cacio.ctc.CTCGraphicsEnvironment");
            if (compatibility.sandboxPolicy != null) {
                command.add("-Djava.security.policy=" + compatibility.sandboxPolicy.getAbsolutePath());
                command.add("-Djava.security.manager=net.sourceforge.prograde.sm.ProGradeJSM");
            }
            if (compatibility.proGradeJar != null) {
                command.add("-Xbootclasspath/a:" + compatibility.proGradeJar.getAbsolutePath());
            }
            command.add("-Xbootclasspath/p:" + compatibility.cacioClassPath);
        }
    }

    private void addJava17CacioModuleOpenArgs(@NonNull ArrayList<String> command) {
        command.add("--add-exports=java.desktop/java.awt=ALL-UNNAMED");
        command.add("--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED");
        command.add("--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED");
        command.add("--add-exports=java.desktop/sun.java2d=ALL-UNNAMED");
        command.add("--add-exports=java.desktop/java.awt.dnd.peer=ALL-UNNAMED");
        command.add("--add-exports=java.desktop/sun.awt=ALL-UNNAMED");
        command.add("--add-exports=java.desktop/sun.awt.event=ALL-UNNAMED");
        command.add("--add-exports=java.desktop/sun.awt.datatransfer=ALL-UNNAMED");
        command.add("--add-exports=java.desktop/sun.font=ALL-UNNAMED");
        command.add("--add-exports=java.base/sun.security.action=ALL-UNNAMED");
        command.add("--add-opens=java.base/java.util=ALL-UNNAMED");
        command.add("--add-opens=java.desktop/java.awt=ALL-UNNAMED");
        command.add("--add-opens=java.desktop/sun.font=ALL-UNNAMED");
        command.add("--add-opens=java.desktop/sun.java2d=ALL-UNNAMED");
        command.add("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED");
        command.add("--add-opens=java.base/java.net=ALL-UNNAMED");
    }

    private void configureJarGuiCompatibilityEnvironment(
            @NonNull ProcessBuilder builder,
            @NonNull JarGuiCompatibility compatibility,
            @NonNull File workingDirectory
    ) {
        java.util.Map<String, String> environment = builder.environment();
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        environment.put("TMPDIR", getCacheDir().getAbsolutePath());
        environment.put("HOME", resolveJarExecutionUserHome(workingDirectory).getAbsolutePath());
        environment.put("AWTSTUB_WIDTH", String.valueOf(Math.max(1, metrics.widthPixels)));
        environment.put("AWTSTUB_HEIGHT", String.valueOf(Math.max(1, metrics.heightPixels)));

        File nativeDir = new File(getApplicationInfo().nativeLibraryDir);
        if (nativeDir.isDirectory()) {
            environment.put("DROIDBRIDGE_NATIVEDIR", nativeDir.getAbsolutePath());
            environment.put("DRIVER_PATH", nativeDir.getAbsolutePath());
        }
        environment.put("FORCE_VSYNC", "false");
    }

    @NonNull
    private String getJarGuiCompatibilityScreenSize() {
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        return Math.max(1, metrics.widthPixels) + "x" + Math.max(1, metrics.heightPixels);
    }

    private final class JarGuiCompatibility {
        final File javaExecutable;
        final int javaMajor;
        final File cacioDir;
        final String cacioClassPath;
        @Nullable final File cacioAgent;
        @Nullable final File proGradeJar;
        @Nullable final File sandboxPolicy;

        JarGuiCompatibility(
                @NonNull File javaExecutable,
                int javaMajor,
                @NonNull File cacioDir,
                @NonNull String cacioClassPath,
                @Nullable File cacioAgent,
                @Nullable File proGradeJar,
                @Nullable File sandboxPolicy
        ) {
            this.javaExecutable = javaExecutable;
            this.javaMajor = javaMajor;
            this.cacioDir = cacioDir;
            this.cacioClassPath = cacioClassPath;
            this.cacioAgent = cacioAgent;
            this.proGradeJar = proGradeJar;
            this.sandboxPolicy = sandboxPolicy;
        }
    }

    @Nullable
    private File findJavaExecutableForJarExecution() {
        PathManager.initContextConstants(this);

        ArrayList<File> candidates = new ArrayList<>();

        /*
         * DroidBridge unpacks its bundled Java runtimes under:
         *   /data/user/0/<package>/runtimes/Internal-17/bin/java
         *   /data/user/0/<package>/runtimes/Internal-21/bin/java
         *   /data/user/0/<package>/runtimes/Internal-25/bin/java
         *
         * The previous patch looked for "runtime/..." instead of "runtimes/...",
         * so it missed the actual installed JREs even though UnpackJreTask reported
         * them as up to date in logcat.
         */
        addJavaRuntimeCandidates(candidates, getDataDir());
        addJavaRuntimeCandidates(candidates, new File(getApplicationInfo().dataDir));
        addJavaRuntimeCandidates(candidates, getFilesDir());

        File minecraftHome = new File(PathManager.DIR_MINECRAFT_HOME);
        addJavaRuntimeCandidates(candidates, minecraftHome);
        File launcherRoot = minecraftHome.getParentFile();
        addJavaRuntimeCandidates(candidates, launcherRoot);

        for (String value : collectPathManagerStringFields()) {
            if (isNullOrBlank(value)) continue;
            File file = new File(value);
            if (file.isFile() && isJavaExecutableName(file.getName())) candidates.add(file);
            if (file.isDirectory()) {
                candidates.add(new File(file, "bin/java"));
                candidates.add(new File(file, "bin/java.real"));
                addJavaRuntimeCandidates(candidates, file);
            }
        }

        for (File candidate : candidates) {
            if (candidate == null || !candidate.isFile()) continue;
            //noinspection ResultOfMethodCallIgnored
            candidate.setExecutable(true, true);
            if (candidate.canExecute() || "java".equals(candidate.getName()) || "java.real".equals(candidate.getName())) {
                return candidate;
            }
        }
        return null;
    }

    private void addJavaRuntimeCandidates(@NonNull ArrayList<File> candidates, @Nullable File root) {
        if (root == null) return;

        String[] relativeCandidates = new String[]{
                "runtimes/Internal-17/bin/java",
                "runtimes/Internal-21/bin/java",
                "runtimes/Internal-25/bin/java",
                "runtimes/Internal-8/bin/java",
                "runtimes/Internal-17/bin/java.real",
                "runtimes/Internal-21/bin/java.real",
                "runtimes/Internal-25/bin/java.real",
                "runtimes/Internal-8/bin/java.real",
                "runtime/Internal-17/bin/java",
                "runtime/Internal-21/bin/java",
                "runtime/Internal-25/bin/java",
                "runtime/Internal-8/bin/java",
                "runtime/java/bin/java",
                "runtime/jre/bin/java",
                "runtime/jre-17/bin/java",
                "runtime/jre-21/bin/java",
                "runtime/jre-8/bin/java",
                "runtime/jre-25/bin/java",
                "jre/bin/java",
                "jre-17/bin/java",
                "jre-21/bin/java",
                "jre-8/bin/java",
                "jre-25/bin/java",
                "Internal-17/bin/java",
                "Internal-21/bin/java",
                "Internal-8/bin/java",
                "Internal-25/bin/java",
                "components/jre/bin/java"
        };

        for (String relative : relativeCandidates) {
            candidates.add(new File(root, relative));
        }

        File runtimesDir = new File(root, "runtimes");
        addJavaExecutablesFromRuntimeDirectory(candidates, runtimesDir);
        File runtimeDir = new File(root, "runtime");
        addJavaExecutablesFromRuntimeDirectory(candidates, runtimeDir);
    }

    private void addJavaExecutablesFromRuntimeDirectory(@NonNull ArrayList<File> candidates, @Nullable File runtimeDir) {
        if (runtimeDir == null || !runtimeDir.isDirectory()) return;
        File[] children = runtimeDir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child == null || !child.isDirectory()) continue;
            candidates.add(new File(child, "bin/java"));
            candidates.add(new File(child, "bin/java.real"));
        }
    }

    private Process startJarJavaProcessWithLinkerFallback(
            @NonNull List<String> command,
            @NonNull File workingDirectory,
            @NonNull File javaExecutable,
            @Nullable JarGuiCompatibility guiCompatibility,
            @NonNull String taskName
    ) throws IOException {
        if (command.isEmpty()) {
            throw new IOException(taskName + " command is empty.");
        }

        prepareJavaExecutableForExternalStart(javaExecutable);

        try {
            ProcessBuilder builder = buildJarJavaProcessBuilder(command, workingDirectory, javaExecutable, guiCompatibility);
            Logging.i("LauncherSettings", taskName + " launchMode=direct-exec command=" + joinCommandForDisplay(command));
            appendJarExecutionProgressLine("Starting Java through direct exec...");
            return builder.start();
        } catch (IOException directFailure) {
            if (!isAndroidExecPermissionDenied(directFailure)) {
                throw directFailure;
            }

            Logging.e("LauncherSettings", taskName + " direct Java exec was blocked; retrying with Android linker", directFailure);
            appendJarExecutionProgressLine("Direct Java exec was blocked by Android. Retrying through system linker...");

            File linker = resolveAndroidSystemLinker();
            if (linker == null || !linker.isFile()) {
                IOException wrapped = new IOException("Direct Java start was blocked and no Android system linker was found.");
                wrapped.initCause(directFailure);
                throw wrapped;
            }

            File linkerJavaExecutable = resolveJavaExecutableForLinker(javaExecutable);
            prepareJavaExecutableForExternalStart(linkerJavaExecutable);

            ArrayList<String> linkerCommand = new ArrayList<>();
            linkerCommand.add(linker.getAbsolutePath());
            linkerCommand.add(linkerJavaExecutable.getAbsolutePath());
            for (int i = 1; i < command.size(); i++) {
                linkerCommand.add(command.get(i));
            }

            ProcessBuilder retryBuilder = buildJarJavaProcessBuilder(linkerCommand, workingDirectory, linkerJavaExecutable, guiCompatibility);
            Logging.i("LauncherSettings", taskName + " launchMode=system-linker command=" + joinCommandForDisplay(linkerCommand));
            appendJarExecutionProgressLine("Starting Java through Android system linker...");
            return retryBuilder.start();
        }
    }

    @NonNull
    private ProcessBuilder buildJarJavaProcessBuilder(
            @NonNull List<String> command,
            @NonNull File workingDirectory,
            @NonNull File javaExecutable,
            @Nullable JarGuiCompatibility guiCompatibility
    ) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory);
        builder.redirectErrorStream(true);
        configureJarExecutionEnvironment(builder, javaExecutable, workingDirectory);
        if (guiCompatibility != null) {
            configureJarGuiCompatibilityEnvironment(builder, guiCompatibility, workingDirectory);
        }
        return builder;
    }

    private void prepareJavaExecutableForExternalStart(@NonNull File javaExecutable) {
        try {
            File javaHome = resolveJavaHome(javaExecutable);
            if (javaHome != null && javaHome.isDirectory()) {
                makeExecutableIfPresent(javaHome);
                File bin = new File(javaHome, "bin");
                makeExecutableIfPresent(bin);
                makeExecutableIfPresent(new File(bin, "java"));
                makeExecutableIfPresent(new File(bin, "java.real"));
            }
            makeExecutableIfPresent(javaExecutable);
        } catch (Throwable throwable) {
            Logging.e("LauncherSettings", "Unable to prepare Java executable permissions", throwable);
        }
    }

    @NonNull
    private File resolveJavaExecutableForLinker(@NonNull File javaExecutable) {
        File parent = javaExecutable.getParentFile();
        if (parent != null && "java".equals(javaExecutable.getName())) {
            File javaReal = new File(parent, "java.real");
            if (javaReal.isFile()) {
                return javaReal;
            }
        }
        return javaExecutable;
    }

    private void makeExecutableIfPresent(@Nullable File file) {
        if (file == null || !file.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        file.setReadable(true, true);
        //noinspection ResultOfMethodCallIgnored
        file.setExecutable(true, true);
        try {
            android.system.Os.chmod(file.getAbsolutePath(), file.isDirectory() ? 0755 : 0755);
        } catch (Throwable ignored) {
        }
    }

    private boolean isAndroidExecPermissionDenied(@NonNull IOException failure) {
        String message = failure.toString().toLowerCase(Locale.US);
        return message.contains("error=13")
                || message.contains("permission denied")
                || message.contains("eacces");
    }

    @Nullable
    private File resolveAndroidSystemLinker() {
        String[] candidates = new String[]{
                "/system/bin/linker64",
                "/apex/com.android.runtime/bin/linker64",
                "/system/bin/linker",
                "/apex/com.android.runtime/bin/linker"
        };
        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.isFile()) return file;
        }
        return null;
    }

    private void configureJarExecutionEnvironment(
            @NonNull ProcessBuilder builder,
            @NonNull File javaExecutable,
            @NonNull File workingDirectory
    ) {
        File javaHome = resolveJavaHome(javaExecutable);
        if (javaHome == null) return;

        java.util.Map<String, String> environment = builder.environment();
        environment.put("JAVA_HOME", javaHome.getAbsolutePath());
        environment.put("HOME", resolveJarExecutionUserHome(workingDirectory).getAbsolutePath());
        environment.put("TMPDIR", getCacheDir().getAbsolutePath());

        String existingPath = environment.get("PATH");
        String javaBin = new File(javaHome, "bin").getAbsolutePath();
        environment.put("PATH", isNullOrBlank(existingPath) ? javaBin : javaBin + ":" + existingPath);

        ArrayList<String> ldPaths = new ArrayList<>();
        addExistingDirectory(ldPaths, new File(javaHome, "lib/server"));
        addExistingDirectory(ldPaths, new File(javaHome, "lib"));

        /*
         * Internal-8 has the older Android/OpenJDK layout used by legacy Android launcher:
         *   Internal-8/lib/aarch64/jli/libjli.so
         *   Internal-8/lib/aarch64/server/libjvm.so
         *   Internal-8/lib/aarch64/*.so
         *
         * Running Internal-8/bin/java without these entries causes Android's
         * linker to abort before Java starts: "library libjli.so not found".
         */
        addExistingDirectory(ldPaths, new File(javaHome, "lib/aarch64/jli"));
        addExistingDirectory(ldPaths, new File(javaHome, "lib/aarch64/server"));
        addExistingDirectory(ldPaths, new File(javaHome, "lib/aarch64"));
        addExistingDirectory(ldPaths, new File(javaHome, "lib/arm64/jli"));
        addExistingDirectory(ldPaths, new File(javaHome, "lib/arm64/server"));
        addExistingDirectory(ldPaths, new File(javaHome, "lib/arm64"));

        File runtimeMod = new File(getApplicationInfo().dataDir, "app_runtime_mod");
        addExistingDirectory(ldPaths, runtimeMod);

        File nativeDir = new File(getApplicationInfo().nativeLibraryDir);
        addExistingDirectory(ldPaths, nativeDir);

        String existingLdPath = environment.get("LD_LIBRARY_PATH");
        String ldPath = joinPathList(ldPaths);
        if (!isNullOrBlank(ldPath)) {
            environment.put("LD_LIBRARY_PATH", isNullOrBlank(existingLdPath) ? ldPath : ldPath + ":" + existingLdPath);
        }

        if (runtimeMod.isDirectory()) {
            environment.put("MOD_ANDROID_RUNTIME", runtimeMod.getAbsolutePath());
        }
        if (nativeDir.isDirectory()) {
            environment.put("DROIDBRIDGE_NATIVEDIR", nativeDir.getAbsolutePath());
            environment.put("DRIVER_PATH", nativeDir.getAbsolutePath());
        }
        environment.put("FORCE_VSYNC", "false");
    }

    @NonNull
    private File resolveJarExecutionUserHome(@NonNull File fallback) {
        File externalFiles = getExternalFilesDir(null);
        if (externalFiles != null && externalFiles.isDirectory()) return externalFiles;
        return fallback;
    }

    private void addExistingDirectory(@NonNull ArrayList<String> paths, @Nullable File directory) {
        if (directory == null || !directory.isDirectory()) return;
        String path = directory.getAbsolutePath();
        if (!paths.contains(path)) paths.add(path);
    }

    @NonNull
    private String joinPathList(@NonNull ArrayList<String> paths) {
        StringBuilder builder = new StringBuilder();
        for (String path : paths) {
            if (isNullOrBlank(path)) continue;
            if (builder.length() > 0) builder.append(':');
            builder.append(path);
        }
        return builder.toString();
    }

    @Nullable
    private File resolveJavaHome(@NonNull File javaExecutable) {
        File binDir = javaExecutable.getParentFile();
        if (binDir == null) return null;
        File javaHome = binDir.getParentFile();
        return javaHome != null && javaHome.isDirectory() ? javaHome : null;
    }

    @NonNull
    private ArrayList<String> collectPathManagerStringFields() {
        ArrayList<String> values = new ArrayList<>();
        try {
            Field[] fields = PathManager.class.getDeclaredFields();
            for (Field field : fields) {
                if (field.getType() != String.class) continue;
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof String) {
                    values.add((String) value);
                }
            }
        } catch (Throwable throwable) {
            Logging.e("LauncherSettings", "Unable to inspect PathManager Java paths", throwable);
        }
        return values;
    }

    private boolean isJavaExecutableName(@Nullable String name) {
        if (name == null) return false;
        return "java".equals(name) || "java.real".equals(name);
    }

    @NonNull
    private String sanitizeJarFileName(@NonNull String rawName) {
        String cleaned = rawName.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.length() == 0) cleaned = "selected.jar";
        if (!cleaned.toLowerCase(java.util.Locale.US).endsWith(".jar")) cleaned += ".jar";
        return cleaned;
    }

    @NonNull
    private String getJarDisplayName(@NonNull Uri uri) {
        String name = null;
        android.database.Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) name = cursor.getString(index);
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }

        if (isNullOrBlank(name)) {
            String path = uri.getLastPathSegment();
            if (!isNullOrBlank(path)) {
                int slash = path.lastIndexOf('/');
                name = slash >= 0 ? path.substring(slash + 1) : path;
            }
        }

        if (isNullOrBlank(name)) name = "selected.jar";
        return sanitizeJarFileName(name);
    }

    private void setupRuntimeComponentsReinstallSettings() {
        if (binding == null || binding.buttonReinstallRuntimeComponents == null) return;

        updateRuntimeComponentsReinstallSummary(
                "Use this if the first launch setup was interrupted or a runtime/component is missing. "
                        + "DroidBridge attempts each bundled item once, skips failures, and keeps going."
        );

        binding.buttonReinstallRuntimeComponents.setOnClickListener(view -> showRuntimeComponentsReinstallDialog());
        if (binding.buttonInstallCustomRuntime != null) {
            binding.buttonInstallCustomRuntime.setOnClickListener(view -> showCustomRuntimeTargetDialog());
        }
    }

    private void showRuntimeComponentsReinstallDialog() {
        if (runtimeComponentsReinstalling) {
            Toast.makeText(this, "Runtime/component reinstall is already running.", Toast.LENGTH_SHORT).show();
            return;
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(COLOR_DIALOG_BG);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setScrollbarFadingEnabled(false);

        LinearLayout root = createStyledDialogRoot(
                scrollView,
                "Repair runtime components",
                "Reinstall DroidBridge's bundled Java runtimes, LWJGL libraries, support jars, and launcher components."
        );

        LinearLayout card = addStyledDialogCard(root);
        addStyledDialogCardTitle(card, "Reinstall bundled files");
        addStyledDialogInfoText(card,
                "No Minecraft game files, accounts, worlds, mods, resource packs, or shader packs are installed or removed. "
                        + "Each bundled item is attempted once. If one item fails, DroidBridge skips it and continues instead of repeatedly retrying.\n\n"
                        + "Keep DroidBridge open during the repair. Leave at least 1 GB of free device storage for Java runtimes, LWJGL files, and temporary extraction data."
        );

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Reinstall", null)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            styleLauncherDialogChrome(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                dialog.dismiss();
                startRuntimeComponentsReinstall();
            });
        });

        dialog.show();
    }

    private void startRuntimeComponentsReinstall() {
        startRuntimeComponentsReinstall(true);
    }

    private void startRuntimeComponentsReinstall(boolean reinstallEverything) {
        if (runtimeComponentsReinstalling) return;

        runtimeComponentsReinstalling = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRuntimeRepairButtonsEnabled(false);
        updateRuntimeComponentsReinstallSummary(
                "Starting component repair... Keep DroidBridge open. Failed items will be skipped."
        );

        Thread thread = new Thread(() -> {
            try {
                PathManager.initContextConstants(getApplicationContext());
                ComponentInstallationManager.InstallResult result = reinstallEverything
                        ? ComponentInstallationManager.reinstallAll(
                        getApplicationContext(),
                        (current, total, itemName, detail) ->
                                updateRuntimeComponentsReinstallSummary(
                                        detail + " (" + current + "/" + total + ")"
                                )
                )
                        : ComponentInstallationManager.installMissing(
                        getApplicationContext(),
                        (current, total, itemName, detail) ->
                                updateRuntimeComponentsReinstallSummary(
                                        detail + " (" + current + "/" + total + ")"
                                )
                );

                runOnUiThread(() -> finishRuntimeComponentsReinstall(result));
            } catch (Throwable throwable) {
                Logging.e("LauncherSettings", "Runtime/component repair flow failed", throwable);
                runOnUiThread(() -> {
                    runtimeComponentsReinstalling = false;
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    setRuntimeRepairButtonsEnabled(true);
                    String message = throwable.getMessage() != null
                            ? throwable.getMessage()
                            : throwable.toString();
                    updateRuntimeComponentsReinstallSummary(
                            "Repair stopped unexpectedly: " + message
                                    + ". Clear DroidBridge cache, restart the phone, confirm at least 1 GB is free, and try again."
                    );
                    Toast.makeText(this, "Component repair stopped: " + message, Toast.LENGTH_LONG).show();
                });
            }
        }, "DroidBridgeRuntimeRepair");
        thread.start();
    }

    private void finishRuntimeComponentsReinstall(
            @NonNull ComponentInstallationManager.InstallResult result
    ) {
        runtimeComponentsReinstalling = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        PathManager.initContextConstants(this);
        setRuntimeRepairButtonsEnabled(true);

        if (binding != null && binding.textFolder != null) {
            binding.textFolder.setText(getString(R.string.launcher_folder_value, PathManager.DIR_MINECRAFT_HOME));
        }

        if (result.isComplete()) {
            updateRuntimeComponentsReinstallSummary(
                    "Repair complete. All bundled components and internal JREs are ready."
            );
            Toast.makeText(this, "Components and JREs are ready.", Toast.LENGTH_LONG).show();
            return;
        }

        List<String> missing = result.getAllMissingNames();
        StringBuilder summary = new StringBuilder(
                "Repair finished, but some items were skipped or remain incomplete:"
        );
        for (String name : missing) {
            summary.append("\n• ").append(name);
        }
        summary.append("\n\nTry Reinstall again. If it still fails, clear DroidBridge cache, restart the phone, and make sure at least 1 GB of device storage is free.");
        updateRuntimeComponentsReinstallSummary(summary.toString());
        showRuntimeComponentsIncompleteDialog(missing);
    }

    private void showRuntimeComponentsIncompleteDialog(@NonNull List<String> missing) {
        StringBuilder message = new StringBuilder(
                "DroidBridge skipped the failed items instead of getting stuck or repeatedly retrying. "
                        + "Minecraft versions that require these files may not launch:\n\n"
        );
        for (String name : missing) {
            message.append("• ").append(name).append('\n');
        }
        message.append("\nTry reinstalling again. If it still fails, clear DroidBridge cache, restart the phone, and confirm that at least 1 GB of device storage is free.");

        new MaterialAlertDialogBuilder(this)
                .setTitle("Components still missing")
                .setMessage(message.toString())
                .setNegativeButton("Close", null)
                .setPositiveButton("Reinstall Missing", (dialog, which) -> startRuntimeComponentsReinstall(false))
                .show();
    }

    private void updateRuntimeComponentsReinstallSummary(@NonNull String text) {
        runOnUiThread(() -> {
            if (binding != null && binding.textRuntimeComponentsSummary != null) {
                binding.textRuntimeComponentsSummary.setText(text);
            }
        });
    }


    private void setupFloatingGameOverlaySettings() {
        if (binding == null || binding.switchShowInGameSettingsButton == null) return;
        if (!(binding.switchShowInGameSettingsButton.getParent() instanceof ViewGroup)) return;

        ViewGroup parent = (ViewGroup) binding.switchShowInGameSettingsButton.getParent();
        if (parent.findViewWithTag("floating_game_overlay_settings") != null) return;

        LinearLayout container = new LinearLayout(this);
        container.setTag("floating_game_overlay_settings");
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, dp(12), 0, 0);
        parent.addView(container, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        View divider = new View(this);
        divider.setBackgroundColor(0x33000000);
        container.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(1))
        ));

        TextView title = new TextView(this);
        title.setText("In-game overlay");
        title.setTextSize(16f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleParams.topMargin = 0;
        container.addView(title, titleParams);

        com.google.android.material.switchmaterial.SwitchMaterial showFps =
                new com.google.android.material.switchmaterial.SwitchMaterial(this);
        showFps.setChecked(GameOverlayPreferences.isShowGameFpsCounter(this));
        updateFloatingFpsSwitchText(showFps, showFps.isChecked());
        showFps.setOnCheckedChangeListener((buttonView, isChecked) -> {
            GameOverlayPreferences.setShowGameFpsCounter(this, isChecked);
            updateFloatingFpsSwitchText(showFps, isChecked);
        });
        container.addView(showFps, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView fpsSummary = new TextView(this);
        fpsSummary.setText("Shows real-time FPS while Minecraft is running. This stays separate from the settings COG, so the FPS counter can remain visible even when the COG is hidden.");
        fpsSummary.setTextSize(13f);
        LinearLayout.LayoutParams fpsSummaryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        fpsSummaryParams.topMargin = dp(2);
        container.addView(fpsSummary, fpsSummaryParams);

        TextView fpsSizeTitle = new TextView(this);
        fpsSizeTitle.setText("FPS counter size");
        fpsSizeTitle.setTextSize(15f);
        fpsSizeTitle.setTypeface(fpsSizeTitle.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams fpsSizeTitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        fpsSizeTitleParams.topMargin = dp(12);
        container.addView(fpsSizeTitle, fpsSizeTitleParams);

        android.widget.Spinner fpsSizeSpinner = new android.widget.Spinner(this);
        ArrayAdapter<String> fpsSizeAdapter = new ArrayAdapter<>(
                this,
                R.layout.item_droidbridge_spinner,
                GameOverlayPreferences.getFpsSizeLabels()
        );
        fpsSizeAdapter.setDropDownViewResource(R.layout.item_droidbridge_spinner_dropdown);
        fpsSizeSpinner.setAdapter(fpsSizeAdapter);
        fpsSizeSpinner.setSelection(GameOverlayPreferences.indexOfFpsSize(
                GameOverlayPreferences.getGameFpsCounterSize(this)
        ), false);
        fpsSizeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean ready;

            @Override
            public void onItemSelected(AdapterView<?> parentView, View view, int position, long id) {
                if (!ready) {
                    ready = true;
                    return;
                }
                GameOverlayPreferences.setGameFpsCounterSize(
                        LauncherSettingsActivity.this,
                        GameOverlayPreferences.fpsSizeValueForIndex(position)
                );
                updateFloatingFpsSwitchText(showFps, showFps.isChecked());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });
        LinearLayout.LayoutParams fpsSpinnerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        fpsSpinnerParams.topMargin = dp(6);
        container.addView(fpsSizeSpinner, fpsSpinnerParams);

        TextView fpsSizeSummary = new TextView(this);
        fpsSizeSummary.setText("Small keeps the old compact badge. Medium and Large make the counter easier to read on high-DPI phones.");
        fpsSizeSummary.setTextSize(13f);
        LinearLayout.LayoutParams fpsSizeSummaryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        fpsSizeSummaryParams.topMargin = dp(2);
        container.addView(fpsSizeSummary, fpsSizeSummaryParams);

        TextView placementTitle = new TextView(this);
        placementTitle.setText("Floating settings button position");
        placementTitle.setTextSize(15f);
        placementTitle.setTypeface(placementTitle.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams placementTitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        placementTitleParams.topMargin = dp(12);
        container.addView(placementTitle, placementTitleParams);

        android.widget.Spinner placementSpinner = new android.widget.Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                R.layout.item_droidbridge_spinner,
                GameOverlayPreferences.getPlacementLabels()
        );
        adapter.setDropDownViewResource(R.layout.item_droidbridge_spinner_dropdown);
        placementSpinner.setAdapter(adapter);
        placementSpinner.setSelection(GameOverlayPreferences.indexOfPlacement(
                GameOverlayPreferences.getGameSettingsButtonPlacement(this)
        ), false);
        placementSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean ready;

            @Override
            public void onItemSelected(AdapterView<?> parentView, View view, int position, long id) {
                if (!ready) {
                    ready = true;
                    return;
                }
                String placement = GameOverlayPreferences.placementValueForIndex(position);
                GameOverlayPreferences.setGameSettingsButtonPlacement(LauncherSettingsActivity.this, placement);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        spinnerParams.topMargin = dp(6);
        container.addView(placementSpinner, spinnerParams);

        TextView placementSummary = new TextView(this);
        placementSummary.setText("This controls the default corner. Dragging the button in game saves a custom position until you reset it or pick a new corner.");
        placementSummary.setTextSize(13f);
        LinearLayout.LayoutParams placementSummaryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        placementSummaryParams.topMargin = dp(2);
        container.addView(placementSummary, placementSummaryParams);

        MaterialButton resetPosition = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        resetPosition.setText("Reset floating button position");
        resetPosition.setAllCaps(false);
        resetPosition.setOnClickListener(view -> GameOverlayPreferences.resetGameSettingsButtonCustomPosition(this));
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        resetParams.topMargin = dp(8);
        container.addView(resetPosition, resetParams);
    }

    private void updateFloatingFpsSwitchText(@NonNull android.widget.CompoundButton switchView, boolean enabled) {
        String size = GameOverlayPreferences.getFpsSizeLabel(this);
        switchView.setText(enabled ? "Show FPS counter: On (" + size + ")" : "Show FPS counter: Off");
    }

    private void setupUpdateCheckerSettings() {
        if (binding == null || binding.buttonShareLatestLog == null) return;
        if (!(binding.buttonShareLatestLog.getParent() instanceof ViewGroup)) return;

        ViewGroup parent = (ViewGroup) binding.buttonShareLatestLog.getParent();
        if (parent.findViewWithTag("update_checker_settings") != null) return;

        LinearLayout container = new LinearLayout(this);
        container.setTag("update_checker_settings");
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, dp(12), 0, 0);
        parent.addView(container, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("Launcher updates");
        title.setTextSize(16f);
        title.setGravity(Gravity.START);
        container.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView summary = new TextView(this);
        summary.setText("Checks GitHub releases for newer DroidBridge builds.");
        summary.setTextSize(13f);
        summary.setPadding(0, dp(2), 0, dp(6));
        container.addView(summary, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        CheckBox autoCheck = new CheckBox(this);
        autoCheck.setText("Check for updates on startup");
        autoCheck.setChecked(LauncherUpdatePreferences.isAutoCheckEnabled(this));
        autoCheck.setOnCheckedChangeListener((buttonView, isChecked) ->
                LauncherUpdatePreferences.setAutoCheckEnabled(this, isChecked));
        container.addView(autoCheck, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        MaterialButton checkNow = new MaterialButton(this);
        checkNow.setText("Check for updates");
        checkNow.setAllCaps(false);
        checkNow.setOnClickListener(view -> LauncherUpdateDialogs.checkManually(this));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        buttonParams.topMargin = dp(6);
        container.addView(checkNow, buttonParams);
    }

    private void setupPrivacyPolicySettings() {
        setupLegalLinkButton(
                binding.buttonOpenMinecraftEula,
                LegalLinks.MINECRAFT_EULA_URL,
                "Minecraft EULA link is not configured."
        );
        setupLegalLinkButton(
                binding.buttonOpenPrivacyPolicy,
                LegalLinks.DROIDBRIDGE_PRIVACY_POLICY_URL,
                "Privacy Policy link is not available yet."
        );
        setupLegalLinkButton(
                binding.buttonOpenDroidBridgeTerms,
                LegalLinks.DROIDBRIDGE_TERMS_URL,
                "DroidBridge Terms of Service link is not available yet."
        );
        setupLegalLinkButton(
                binding.buttonOpenDroidBridgeLicense,
                LegalLinks.DROIDBRIDGE_LICENSING,
                "DroidBridge Terms of Service link is not available yet."
        );
    }

    private void setupLegalLinkButton(
            @NonNull MaterialButton button,
            @Nullable String url,
            @NonNull String unavailableMessage
    ) {
        boolean available = !isNullOrBlank(url);
        button.setEnabled(available);
        button.setOnClickListener(view -> {
            if (!available || !LegalLinks.open(this, url)) {
                Toast.makeText(this, unavailableMessage, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void setupMemorySettings() {
        int maxMemoryMb = MemoryAllocationUtils.getMaxAllocatableMemoryMb(this);
        int currentMemoryMb = MemoryAllocationUtils.resolveAllocatedMemoryMb(this);

        updateMemorySeekBarBounds(currentMemoryMb);
        updateMemoryText(currentMemoryMb);
        updateAvailableMemorySummary(maxMemoryMb);
        updateRamUnlockButton();

        binding.buttonUnlockRam.setOnClickListener(view -> showRamUnlockDialog());

        binding.sliderAllocatedRam.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;

                int memoryMb = memoryFromSeekBarProgress(progress);
                LauncherPreferences.setAllocatedMemoryMb(LauncherSettingsActivity.this, memoryMb);
                updateMemoryText(memoryMb);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int memoryMb = memoryFromSeekBarProgress(seekBar.getProgress());
                int safeMemoryMb = MemoryAllocationUtils.clampToAllowedRam(LauncherSettingsActivity.this, memoryMb);
                LauncherPreferences.setAllocatedMemoryMb(LauncherSettingsActivity.this, safeMemoryMb);
                updateMemorySlider(safeMemoryMb);
            }
        });

        binding.textAllocatedRam.setOnClickListener(view -> openMemoryInputDialog());
    }

    private void updateRamUnlockButton() {
        boolean unlocked = MemoryAllocationUtils.isRamUnlocked(this);
        binding.buttonUnlockRam.setText(unlocked
                ? R.string.memory_unlock_button_unlocked
                : R.string.memory_unlock_button_locked);
        binding.buttonUnlockRam.setEnabled(true);
    }

    private void showRamUnlockDialog() {
        boolean unlocked = MemoryAllocationUtils.isRamUnlocked(this);
        int titleRes = unlocked
                ? R.string.memory_relock_dialog_title
                : R.string.memory_unlock_dialog_title;
        int messageRes = unlocked
                ? R.string.memory_relock_dialog_message
                : R.string.memory_unlock_dialog_message;
        int positiveRes = unlocked
                ? R.string.memory_relock_dialog_positive
                : R.string.memory_unlock_dialog_positive;

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(COLOR_DIALOG_BG);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setScrollbarFadingEnabled(false);

        LinearLayout root = createStyledDialogRoot(
                scrollView,
                getString(titleRes),
                getString(messageRes)
        );

        LinearLayout card = addStyledDialogCard(root);
        addStyledDialogCardTitle(card, unlocked ? "Available RAM limit" : "Maximum RAM access");
        addStyledDialogInfoText(card, unlocked
                ? "This will return the global and per-instance RAM sliders to Android's currently available RAM limit. The saved default is not reset."
                : "This lets the global and per-instance RAM sliders use the device-reported installed RAM instead of only the currently available RAM. Use this only when you understand the risk of starving Android or the GPU driver.");

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(8), 0, 0);

        MaterialButton cancel = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        cancel.setText(android.R.string.cancel);
        cancel.setAllCaps(false);
        styleDialogOutlinedButton(cancel);
        actions.addView(cancel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        MaterialButton confirm = new MaterialButton(this);
        confirm.setText(positiveRes);
        confirm.setAllCaps(false);
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        confirmParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(confirm, confirmParams);

        card.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .create();

        cancel.setOnClickListener(view -> dialog.dismiss());
        confirm.setOnClickListener(view -> {
            MemoryAllocationUtils.setRamUnlocked(this, !unlocked);
            int currentMemoryMb = MemoryAllocationUtils.resolveAllocatedMemoryMb(this);
            updateMemorySlider(currentMemoryMb);
            updateRamUnlockButton();
            dialog.dismiss();
        });

        dialog.setOnShowListener(dialogInterface -> styleLauncherDialogChrome(dialog));
        dialog.show();
    }

    private void openMemoryInputDialog() {
        int maxMemoryMb = MemoryAllocationUtils.getMaxAllocatableMemoryMb(this);
        int currentMemoryMb = MemoryAllocationUtils.resolveAllocatedMemoryMb(this);

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setText(String.valueOf(currentMemoryMb));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.memory_dialog_title)
                .setMessage(getString(R.string.memory_dialog_message, maxMemoryMb))
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();

        dialog.setOnShowListener(dialogInterface -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            int requestedMb = parseMemoryInput(input.getText() == null ? "" : input.getText().toString());
            int memoryMb = MemoryAllocationUtils.clampToAllowedRam(this, requestedMb);
            LauncherPreferences.setAllocatedMemoryMb(this, memoryMb);
            updateMemorySlider(memoryMb);
            dialog.dismiss();
        }));

        dialog.show();
    }

    private int parseMemoryInput(@NonNull String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Throwable ignored) {
            return MemoryAllocationUtils.resolveAllocatedMemoryMb(this);
        }
    }

    private void updateMemorySlider(int memoryMb) {
        int maxMemoryMb = MemoryAllocationUtils.getMaxAllocatableMemoryMb(this);
        int safeMemoryMb = MemoryAllocationUtils.clampToAllowedRam(this, memoryMb);

        updateMemorySeekBarBounds(safeMemoryMb);
        updateMemoryText(safeMemoryMb);
        updateAvailableMemorySummary(maxMemoryMb);
    }

    private void updateMemorySeekBarBounds(int memoryMb) {
        int maxMemoryMb = MemoryAllocationUtils.getMaxAllocatableMemoryMb(this);
        int minMemoryMb = MemoryAllocationUtils.getMinimumMemoryMb(maxMemoryMb);
        int safeMemoryMb = MemoryAllocationUtils.clampToAllowedRam(this, memoryMb);
        int steps = Math.max(1, (maxMemoryMb - minMemoryMb) / MemoryAllocationUtils.RAM_STEP_MB);

        binding.sliderAllocatedRam.setMax(steps);
        binding.sliderAllocatedRam.setProgress(progressFromMemory(safeMemoryMb));
    }

    private int progressFromMemory(int memoryMb) {
        int maxMemoryMb = MemoryAllocationUtils.getMaxAllocatableMemoryMb(this);
        int minMemoryMb = MemoryAllocationUtils.getMinimumMemoryMb(maxMemoryMb);
        int safeMemoryMb = MemoryAllocationUtils.clampToAllowedRam(this, memoryMb);
        return Math.max(0, (safeMemoryMb - minMemoryMb) / MemoryAllocationUtils.RAM_STEP_MB);
    }

    private int memoryFromSeekBarProgress(int progress) {
        int maxMemoryMb = MemoryAllocationUtils.getMaxAllocatableMemoryMb(this);
        int minMemoryMb = MemoryAllocationUtils.getMinimumMemoryMb(maxMemoryMb);
        int requestedMb = minMemoryMb + (Math.max(0, progress) * MemoryAllocationUtils.RAM_STEP_MB);
        return MemoryAllocationUtils.clampToAllowedRam(this, requestedMb);
    }

    private void updateMemoryText(int memoryMb) {
        binding.textAllocatedRam.setText(getString(R.string.memory_allocated_value, memoryMb, memoryMb / 1024f));
    }

    private void updateAvailableMemorySummary(int maxMemoryMb) {
        int availableMemoryMb = MemoryAllocationUtils.getAvailableMemoryMb(this);
        int totalMemoryMb = MemoryAllocationUtils.getTotalMemoryMb(this);
        binding.textAvailableRamSummary.setText(getString(
                R.string.memory_available_summary,
                availableMemoryMb,
                availableMemoryMb / 1024f,
                totalMemoryMb,
                totalMemoryMb / 1024f,
                maxMemoryMb,
                maxMemoryMb / 1024f
        ));
    }

    private void updateSharedInstallsSwitchText(boolean showSharedInstalls) {
        binding.switchShowSharedInstalls.setText(
                showSharedInstalls
                        ? R.string.shared_installs_on
                        : R.string.shared_installs_show
        );
    }

    private void updateRemoveInheritedVanillaSwitchText(boolean removeInheritedVanilla) {
        binding.switchRemoveInheritedVanilla.setText(
                removeInheritedVanilla
                        ? R.string.inherited_vanilla_remove_on
                        : R.string.inherited_vanilla_remove_off
        );
    }

    private void showAndroidBackButtonActionDialog() {
        List<String> labels = Arrays.asList(
                getString(R.string.android_back_action_pause_game),
                getString(R.string.android_back_action_launcher_menu),
                getString(R.string.android_back_action_disabled)
        );
        List<String> values = Arrays.asList(
                LauncherPreferences.ANDROID_BACK_ACTION_PAUSE_GAME,
                LauncherPreferences.ANDROID_BACK_ACTION_LAUNCHER_MENU,
                LauncherPreferences.ANDROID_BACK_ACTION_DISABLED
        );

        String current = LauncherPreferences.getAndroidBackButtonAction(this);
        int selectedIndex = Math.max(0, values.indexOf(current));
        showStyledSingleChoiceDialog(
                getString(R.string.android_back_button_action_title),
                getString(R.string.android_back_button_action_dialog_summary),
                labels,
                selectedIndex,
                position -> {
                    if (position < 0 || position >= values.size()) return;
                    LauncherPreferences.setAndroidBackButtonAction(this, values.get(position));
                    updateAndroidBackButtonActionButtonText();
                }
        );
    }

    private void updateAndroidBackButtonActionButtonText() {
        if (binding == null || binding.buttonAndroidBackAction == null) return;
        String action = LauncherPreferences.getAndroidBackButtonAction(this);
        int label = R.string.android_back_action_current_pause_game;
        if (LauncherPreferences.ANDROID_BACK_ACTION_LAUNCHER_MENU.equals(action)) {
            label = R.string.android_back_action_current_launcher_menu;
        } else if (LauncherPreferences.ANDROID_BACK_ACTION_DISABLED.equals(action)) {
            label = R.string.android_back_action_current_disabled;
        }
        binding.buttonAndroidBackAction.setText(label);
    }

    private void showInGameMenuKeyboardShortcutDialog() {
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.in_game_menu_keyboard_shortcut_capture_title)
                .setMessage(R.string.in_game_menu_keyboard_shortcut_capture_summary)
                .setNeutralButton("Clear", (dialogInterface, which) -> {
                    LauncherPreferences.setInGameMenuKeyboardShortcut(this, 0, 0);
                    updateInGameMenuKeyboardShortcutButtonText();
                })
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnKeyListener((dialogInterface, keyCode, event) -> {
            if (event == null || event.getAction() != KeyEvent.ACTION_DOWN
                    || event.getRepeatCount() != 0) {
                return true;
            }

            // Only accept actual keyboard events. Controller buttons must keep their existing
            // mapping behavior and must never become launcher-menu shortcuts accidentally.
            int source = event.getSource();
            boolean keyboardSource = source == 0
                    || (source & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD;
            int normalizedKeyCode = normalizeKeyboardShortcutCaptureKeyCode(keyCode, event);
            // Do not reject an entire HID device just because Android also reports GAMEPAD or
            // JOYSTICK sources. DeX docks and many USB/Bluetooth keyboard+mouse receivers are
            // composite devices. Reject actual controller key codes instead.
            if (!keyboardSource || isControllerShortcutPrimaryKey(normalizedKeyCode)) {
                Toast.makeText(this,
                        "Press a shortcut on a physical keyboard.",
                        Toast.LENGTH_SHORT).show();
                return true;
            }

            // Modifier DOWN arrives before the primary key. Keep the dialog open and wait for
            // the non-modifier key so Ctrl+Esc / Alt+F8 / Ctrl+Shift+M are captured as one
            // shortcut instead of accidentally binding just Ctrl, Alt, Shift, or Meta.
            if (KeyEvent.isModifierKey(normalizedKeyCode)) {
                return true;
            }

            // A genuine Android navigation Back still cancels the dialog. Physical keyboard
            // Escape reported as Back/scan-code 1 was normalized to KEYCODE_ESCAPE above.
            if (normalizedKeyCode == KeyEvent.KEYCODE_BACK) {
                dialog.dismiss();
                return true;
            }

            if (isSystemReservedShortcutPrimaryKey(normalizedKeyCode)) {
                Toast.makeText(this,
                        "Android reserves that key. Choose another keyboard shortcut.",
                        Toast.LENGTH_SHORT).show();
                return true;
            }

            int modifiers = normalizedKeyboardShortcutModifiers(event.getMetaState());
            LauncherPreferences.setInGameMenuKeyboardShortcut(
                    this, normalizedKeyCode, modifiers);
            updateInGameMenuKeyboardShortcutButtonText();
            Toast.makeText(this,
                    "In-game launcher menu shortcut: "
                            + keyboardShortcutLabel(normalizedKeyCode, modifiers),
                    Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            return true;
        });
        dialog.setOnShowListener(dialogInterface -> styleLauncherDialogChrome(dialog));
        dialog.show();
    }

    private void updateInGameMenuKeyboardShortcutButtonText() {
        if (binding == null || binding.buttonInGameMenuKeyboardShortcut == null) return;
        int keyCode = LauncherPreferences.getInGameMenuKeyboardShortcutKeyCode(this);
        int modifiers = LauncherPreferences.getInGameMenuKeyboardShortcutModifiers(this);
        binding.buttonInGameMenuKeyboardShortcut.setText(
                keyCode == 0
                        ? getString(R.string.in_game_menu_keyboard_shortcut_disabled)
                        : "Keyboard shortcut: " + keyboardShortcutLabel(keyCode, modifiers));
    }

    private static int normalizeKeyboardShortcutCaptureKeyCode(int keyCode, @NonNull KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK
                && (event.getSource() & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
                && event.getScanCode() == 1) {
            return KeyEvent.KEYCODE_ESCAPE;
        }
        return keyCode;
    }

    private static int normalizedKeyboardShortcutModifiers(int metaState) {
        int normalized = KeyEvent.normalizeMetaState(metaState);
        return normalized & (KeyEvent.META_CTRL_ON
                | KeyEvent.META_ALT_ON
                | KeyEvent.META_SHIFT_ON
                | KeyEvent.META_META_ON);
    }

    private static boolean isControllerShortcutPrimaryKey(int keyCode) {
        if (KeyEvent.isGamepadButton(keyCode)) return true;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                return true;
            default:
                return false;
        }
    }

    private static boolean isSystemReservedShortcutPrimaryKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_HOME
                || keyCode == KeyEvent.KEYCODE_POWER
                || keyCode == KeyEvent.KEYCODE_APP_SWITCH;
    }

    @NonNull
    private static String keyboardShortcutLabel(int keyCode, int modifiers) {
        StringBuilder label = new StringBuilder();
        if ((modifiers & KeyEvent.META_CTRL_ON) != 0) label.append("Ctrl + ");
        if ((modifiers & KeyEvent.META_ALT_ON) != 0) label.append("Alt + ");
        if ((modifiers & KeyEvent.META_SHIFT_ON) != 0) label.append("Shift + ");
        if ((modifiers & KeyEvent.META_META_ON) != 0) label.append("Meta + ");
        label.append(keyboardShortcutPrimaryKeyLabel(keyCode));
        return label.toString();
    }

    @NonNull
    private static String keyboardShortcutPrimaryKeyLabel(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) return "Esc";
        if (keyCode == KeyEvent.KEYCODE_ENTER) return "Enter";
        if (keyCode == KeyEvent.KEYCODE_SPACE) return "Space";

        String value = KeyEvent.keyCodeToString(keyCode);
        if (value == null || value.trim().isEmpty()) return "Key " + keyCode;
        if (value.startsWith("KEYCODE_")) value = value.substring("KEYCODE_".length());
        value = value.replace('_', ' ').trim();
        if (value.isEmpty()) return "Key " + keyCode;
        return value;
    }

    private void showGridPlayIconModeDialog() {
        List<String> labels = Arrays.asList(
                getString(R.string.grid_play_icon_mode_regular),
                getString(R.string.grid_play_icon_mode_last_world),
                getString(R.string.grid_play_icon_mode_server)
        );
        List<String> values = Arrays.asList(
                LauncherPreferences.GRID_PLAY_ICON_MODE_REGULAR,
                LauncherPreferences.GRID_PLAY_ICON_MODE_LAST_WORLD,
                LauncherPreferences.GRID_PLAY_ICON_MODE_SERVER
        );

        String current = LauncherPreferences.getGridPlayIconMode(this);
        int selectedIndex = Math.max(0, values.indexOf(current));

        showStyledSingleChoiceDialog(
                getString(R.string.grid_play_icon_mode_title),
                getString(R.string.grid_play_icon_mode_dialog_summary),
                labels,
                selectedIndex,
                position -> {
                    if (position < 0 || position >= values.size()) return;
                    String mode = values.get(position);
                    LauncherPreferences.setGridPlayIconMode(this, mode);
                    updateGridPlayIconModeButtonText();
                    Toast.makeText(this, getGridPlayIconModeToast(mode), Toast.LENGTH_SHORT).show();
                }
        );
    }

    private void updateGridPlayIconModeButtonText() {
        if (binding == null || binding.buttonGridPlayIconMode == null) return;
        binding.buttonGridPlayIconMode.setText(getGridPlayIconModeLabel(LauncherPreferences.getGridPlayIconMode(this)));
    }

    private int getGridPlayIconModeLabel(@NonNull String mode) {
        if (LauncherPreferences.GRID_PLAY_ICON_MODE_LAST_WORLD.equals(mode)) {
            return R.string.grid_play_icon_mode_current_last_world;
        }
        if (LauncherPreferences.GRID_PLAY_ICON_MODE_SERVER.equals(mode)) {
            return R.string.grid_play_icon_mode_current_server;
        }
        return R.string.grid_play_icon_mode_current_regular;
    }

    private int getGridPlayIconModeToast(@NonNull String mode) {
        if (LauncherPreferences.GRID_PLAY_ICON_MODE_LAST_WORLD.equals(mode)) {
            return R.string.grid_play_icon_mode_saved_last_world;
        }
        if (LauncherPreferences.GRID_PLAY_ICON_MODE_SERVER.equals(mode)) {
            return R.string.grid_play_icon_mode_saved_server;
        }
        return R.string.grid_play_icon_mode_saved_regular;
    }

    private void updateQuickPlayLastWorldGridSwitchText(boolean quickPlayLastWorld) {
        // Kept for any older source branches that still call the former toggle helper.
        updateGridPlayIconModeButtonText();
    }

    private void updateRenderSurfaceSwitchText(boolean useNativeSurfaceView) {
        binding.switchUseNativeSurface.setText(
                useNativeSurfaceView
                        ? R.string.render_surface_surface_view
                        : R.string.render_surface_texture_view
        );
    }

    private void updateForceFullscreenSwitchText(boolean forceFullscreen) {
        binding.switchForceFullscreenMode.setText(
                forceFullscreen
                        ? R.string.settings_renderer_full_screen_on
                        : R.string.settings_renderer_full_screen_off
        );
    }

    private void updateAvoidRoundedCornersSwitchText(boolean avoidRoundedCorners) {
        binding.switchAvoidRoundedCorners.setText(
                avoidRoundedCorners
                        ? R.string.settings_renderer_avoid_rounded_corners_on
                        : R.string.settings_renderer_avoid_rounded_corners_off
        );
    }

    private void updateIgnoreDisplayCutoutSwitchText(boolean ignoreDisplayCutout) {
        binding.switchIgnoreDisplayCutout.setText(
                ignoreDisplayCutout
                        ? R.string.settings_renderer_ignore_notch_on
                        : R.string.settings_renderer_ignore_notch_off
        );
    }

    private void updateImeViewportPushSwitchText(boolean enabled) {
        if (binding == null || binding.switchImeViewportPush == null) return;
        binding.switchImeViewportPush.setText(
                enabled
                        ? R.string.settings_game_ime_viewport_push_on
                        : R.string.settings_game_ime_viewport_push_off
        );
    }

    private void updateSystemVulkanDriverSwitchText(boolean useSystemVulkanDriver) {
        binding.switchUseSystemVulkanDriver.setText(
                useSystemVulkanDriver
                        ? R.string.use_system_vulkan_driver_on
                        : R.string.use_system_vulkan_driver_off
        );
    }

    private void updateOpenGl26PlusSwitchText(boolean useOpenGl26Plus) {
        binding.switchUseOpenGlFor26Plus.setText(
                useOpenGl26Plus
                        ? R.string.use_opengl_26_plus_on
                        : R.string.use_opengl_26_plus_off
        );
    }

    private void installVulkanVsyncToggle() {
        if (binding == null || binding.switchUseOpenGlFor26Plus == null) return;
        android.view.ViewParent rawParent = binding.switchUseOpenGlFor26Plus.getParent();
        if (!(rawParent instanceof ViewGroup)) return;

        ViewGroup parent = (ViewGroup) rawParent;
        final String tag = "enable_vulkan_vsync_toggle";
        if (parent.findViewWithTag(tag) != null) return;

        CheckBox toggle = new CheckBox(this);
        toggle.setTag(tag);
        boolean enabled = LauncherPreferences.isVulkanVsyncEnabled(this);
        toggle.setChecked(enabled);
        updateVulkanVsyncToggleText(toggle, enabled);
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            LauncherPreferences.setVulkanVsyncEnabled(this, isChecked);
            updateVulkanVsyncToggleText(toggle, isChecked);
            Toast.makeText(
                    this,
                    isChecked
                            ? "Vulkan VSync will use FIFO present mode on next launch."
                            : "Vulkan VSync will use mailbox/unlimited present mode on next launch.",
                    Toast.LENGTH_SHORT
            ).show();
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(8);
        int index = parent.indexOfChild(binding.switchUseOpenGlFor26Plus) + 1;
        parent.addView(toggle, Math.min(Math.max(index, 0), parent.getChildCount()), params);
    }

    private void updateVulkanVsyncToggleText(@NonNull CheckBox toggle, boolean enabled) {
        toggle.setText(enabled
                ? "Enable VSYNC with Vulkan: On (FIFO present mode)"
                : "Enable VSYNC with Vulkan: Off (mailbox/unlimited)");
    }

    private void updateInGameSettingsButtonSwitchText(boolean showInGameSettingsButton) {
        binding.switchShowInGameSettingsButton.setText(
                showInGameSettingsButton
                        ? R.string.game_settings_button_on
                        : R.string.game_settings_button_off
        );
    }

    // DROIDBRIDGE_DUAL_SCREEN_SETTINGS_SECTION_BEGIN
    /**
     * Moves the existing Dual-screen controls out of Launcher Settings and into a
     * normal MaterialCardView section on this same settings ScrollView. The existing
     * switches/buttons themselves are re-parented, so every listener and preference
     * binding remains the same object and no setting is duplicated.
     */
    private void setupDualScreenSettingsSection() {
        if (binding == null || cardDualScreenSettings != null) return;

        android.view.ViewParent rawSourceParent = binding.switchDualScreenSupport.getParent();
        android.view.ViewParent rawPageParent = binding.cardInstanceSettings.getParent();
        if (!(rawSourceParent instanceof LinearLayout) || !(rawPageParent instanceof LinearLayout)) {
            Logging.e("LauncherSettings", "Dual-screen Settings section: expected LinearLayout parents; no views moved");
            return;
        }

        LinearLayout source = (LinearLayout) rawSourceParent;
        LinearLayout page = (LinearLayout) rawPageParent;

        // setupLauncherSettings() creates these four dynamic rows before this method runs.
        if (buttonDualScreenHudLayout == null || textDualScreenHudLayoutSummary == null
                || buttonDualScreenBackground == null || textDualScreenBackgroundSummary == null
                || buttonDualScreenMapFrame == null || textDualScreenMapFrameSummary == null
                || buttonDualScreenTouchScale == null || textDualScreenTouchScaleSummary == null) {
            Logging.e("LauncherSettings", "Dual-screen Settings section: one or more existing Dual-screen rows are missing; no views moved");
            return;
        }

        int first = source.indexOfChild(binding.switchDualScreenSupport);
        int last = first;
        View[] anchors = new View[] {
                binding.switchDualScreenSupport,
                binding.switchDualScreenSwap,
                binding.switchDualScreenAspectRatio,
                binding.switchDualScreenFps,
                buttonDualScreenHudLayout,
                textDualScreenHudLayoutSummary,
                buttonDualScreenBackground,
                textDualScreenBackgroundSummary,
                buttonDualScreenMapFrame,
                textDualScreenMapFrameSummary,
                buttonDualScreenTouchScale,
                textDualScreenTouchScaleSummary
        };
        for (View anchor : anchors) {
            int index = source.indexOfChild(anchor);
            if (index < 0) {
                Logging.e("LauncherSettings", "Dual-screen Settings section: an expected existing view is not in Launcher Settings; no views moved");
                return;
            }
            last = Math.max(last, index);
        }
        if (first < 0 || last < first) return;

        // Guard the two known neighboring Launcher settings. If either ever moves into
        // the Dual-screen range in a future layout revision, fail closed instead of
        // accidentally stealing an unrelated setting.
        int inGameButtonIndex = source.indexOfChild(binding.switchShowInGameSettingsButton);
        int androidBackIndex = source.indexOfChild(binding.buttonAndroidBackAction);
        if ((inGameButtonIndex >= first && inGameButtonIndex <= last)
                || (androidBackIndex >= first && androidBackIndex <= last)) {
            Logging.e("LauncherSettings", "Dual-screen Settings section: range overlaps a non-Dual-screen setting; no views moved");
            return;
        }

        ArrayList<View> dualViews = new ArrayList<>();
        for (int i = first; i <= last; i++) {
            dualViews.add(source.getChildAt(i));
        }

        MaterialCardView card = new MaterialCardView(this);
        card.setTag("dual_screen_settings_card");
        // Match the existing Instances card instead of inventing a second settings style.
        card.setRadius(binding.cardInstanceSettings.getRadius());
        card.setCardElevation(binding.cardInstanceSettings.getCardElevation());
        card.setMaxCardElevation(binding.cardInstanceSettings.getMaxCardElevation());
        card.setUseCompatPadding(binding.cardInstanceSettings.getUseCompatPadding());
        card.setPreventCornerOverlap(binding.cardInstanceSettings.getPreventCornerOverlap());
        card.setCardBackgroundColor(binding.cardInstanceSettings.getCardBackgroundColor());
        card.setStrokeWidth(binding.cardInstanceSettings.getStrokeWidth());
        card.setStrokeColor(binding.cardInstanceSettings.getStrokeColor());

        LinearLayout.LayoutParams cardParams;
        ViewGroup.LayoutParams existingCardParams = binding.cardInstanceSettings.getLayoutParams();
        if (existingCardParams instanceof LinearLayout.LayoutParams) {
            cardParams = new LinearLayout.LayoutParams((LinearLayout.LayoutParams) existingCardParams);
        } else {
            cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            cardParams.topMargin = dp(12);
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(source.getPaddingLeft(), source.getPaddingTop(),
                source.getPaddingRight(), source.getPaddingBottom());
        card.addView(content, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView sourceTitle = findSettingsSectionTitle(
                binding.cardInstanceSettings,
                getString(R.string.settings_instance_title)
        );
        TextView title = cloneSettingsSectionTitle(sourceTitle, "Dual-screen Settings");
        content.addView(title);

        // Re-parent the complete contiguous Dual-screen block. This also carries the
        // existing XML summary TextViews for the four switches, while the four dynamic
        // button/summary pairs remain in their already-established order:
        // HUD layout -> background -> exploration map -> touch scale.
        for (View view : dualViews) {
            source.removeView(view);
            content.addView(view);
        }

        int instanceIndex = page.indexOfChild(binding.cardInstanceSettings);
        int insertIndex = instanceIndex >= 0
                ? Math.min(page.getChildCount(), instanceIndex + 1)
                : page.getChildCount();
        page.addView(card, insertIndex, cardParams);
        cardDualScreenSettings = card;

        Logging.i("LauncherSettings", "Dual-screen Settings section installed on main settings page; existing controls moved=" + dualViews.size());
    }

    // DROIDBRIDGE_RECORDING_SETTINGS_SECTION_BEGIN
    /**
     * Installs a dedicated Recording section and tab without duplicating any existing
     * launcher settings. This is intentionally a small first version so more recording
     * controls (quality, frame rate, dual-display layout, audio choices) can be added later.
     */
    private void setupRecordingSettingsSection() {
        if (binding == null || cardRecordingSettings != null) return;
        android.view.ViewParent rawPageParent = binding.cardInstanceSettings.getParent();
        if (!(rawPageParent instanceof LinearLayout)) {
            Logging.e("LauncherSettings", "Recording Settings section: expected LinearLayout parent");
            return;
        }

        LinearLayout page = (LinearLayout) rawPageParent;
        MaterialCardView card = new MaterialCardView(this);
        card.setTag("recording_settings_card");
        card.setRadius(binding.cardInstanceSettings.getRadius());
        card.setCardElevation(binding.cardInstanceSettings.getCardElevation());
        card.setMaxCardElevation(binding.cardInstanceSettings.getMaxCardElevation());
        card.setUseCompatPadding(binding.cardInstanceSettings.getUseCompatPadding());
        card.setPreventCornerOverlap(binding.cardInstanceSettings.getPreventCornerOverlap());
        card.setCardBackgroundColor(binding.cardInstanceSettings.getCardBackgroundColor());
        card.setStrokeWidth(binding.cardInstanceSettings.getStrokeWidth());
        card.setStrokeColor(binding.cardInstanceSettings.getStrokeColor());

        LinearLayout.LayoutParams cardParams;
        ViewGroup.LayoutParams existingCardParams = binding.cardInstanceSettings.getLayoutParams();
        if (existingCardParams instanceof LinearLayout.LayoutParams) {
            cardParams = new LinearLayout.LayoutParams((LinearLayout.LayoutParams) existingCardParams);
        } else {
            cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            cardParams.topMargin = dp(12);
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.addView(content, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView title = cloneSettingsSectionTitle(null, "Recording Settings");
        content.addView(title);

        TextView summary = new TextView(this);
        summary.setText("Recording saves MP4 videos to Movies/DroidBridge/Recordings. Choose game audio, microphone voice, or both. When DroidBridge Dual-screen Mode is enabled, this tab can also record the HUD/touch display at the same time.");
        summary.setTextSize(13f);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        summaryParams.bottomMargin = dp(10);
        content.addView(summary, summaryParams);

        MaterialButton audioModeButton = new MaterialButton(this);
        audioModeButton.setTag("recording_audio_mode");
        updateRecordingAudioModeText(audioModeButton);
        audioModeButton.setOnClickListener(view -> showRecordingAudioModeDialog(audioModeButton));
        LinearLayout.LayoutParams audioButtonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        audioButtonParams.bottomMargin = dp(4);
        content.addView(audioModeButton, audioButtonParams);

        TextView audioModeSummary = new TextView(this);
        audioModeSummary.setText("Select what goes into the recording's audio track. Voice modes use the device microphone; Voice & Game Audio mixes microphone voice with Minecraft audio.");
        audioModeSummary.setTextSize(13f);
        LinearLayout.LayoutParams audioSummaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        audioSummaryParams.bottomMargin = dp(10);
        content.addView(audioModeSummary, audioSummaryParams);

        com.google.android.material.switchmaterial.SwitchMaterial bothScreens =
                new com.google.android.material.switchmaterial.SwitchMaterial(this);
        bothScreens.setTag("recording_both_screens");
        bothScreens.setChecked(RecordingPreferences.isRecordBothScreensEnabled(this));
        updateRecordingBothScreensText(bothScreens, bothScreens.isChecked());
        bothScreens.setOnCheckedChangeListener((buttonView, isChecked) -> {
            RecordingPreferences.setRecordBothScreensEnabled(this, isChecked);
            updateRecordingBothScreensText(bothScreens, isChecked);
        });
        content.addView(bothScreens, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        switchRecordingBothScreens = bothScreens;

        TextView bothScreensSummary = new TextView(this);
        bothScreensSummary.setText("Records the Minecraft/game display plus DroidBridge's HUD/touch display as a matched second MP4. The selected recording audio mode is stored in the game-screen MP4. This option is shown only while Dual-screen Mode is enabled.");
        bothScreensSummary.setTextSize(13f);
        LinearLayout.LayoutParams bothSummaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        bothSummaryParams.topMargin = dp(2);
        bothSummaryParams.bottomMargin = dp(10);
        content.addView(bothScreensSummary, bothSummaryParams);
        textRecordingBothScreensSummary = bothScreensSummary;

        com.google.android.material.switchmaterial.SwitchMaterial hideTouch =
                new com.google.android.material.switchmaterial.SwitchMaterial(this);
        hideTouch.setTag("recording_hide_touch_controls");
        hideTouch.setChecked(RecordingPreferences.isHideTouchControlsEnabled(this));
        updateRecordingHideTouchText(hideTouch, hideTouch.isChecked());
        hideTouch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            RecordingPreferences.setHideTouchControlsEnabled(this, isChecked);
            updateRecordingHideTouchText(hideTouch, isChecked);
        });
        content.addView(hideTouch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView hideSummary = new TextView(this);
        hideSummary.setText("When enabled, touch controls stay visible and usable on your device, but DroidBridge excludes their artwork from the recording. The controls are no longer hidden from the player while capture is active.");
        hideSummary.setTextSize(13f);
        LinearLayout.LayoutParams hideSummaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        hideSummaryParams.topMargin = dp(2);
        content.addView(hideSummary, hideSummaryParams);

        // Keep the physical settings-card order identical to the tab order:
        // Launcher Settings -> Recording Settings -> Instance Settings -> Dual-screen Settings.
        int launcherIndex = page.indexOfChild(binding.cardLauncherSettings);
        int insertIndex = launcherIndex >= 0
                ? Math.min(page.getChildCount(), launcherIndex + 1)
                : page.getChildCount();
        page.addView(card, insertIndex, cardParams);
        cardRecordingSettings = card;
        refreshRecordingDualScreenOption();
        Logging.i("LauncherSettings", "Recording Settings section installed");
    }

    private void refreshRecordingDualScreenOption() {
        com.google.android.material.switchmaterial.SwitchMaterial toggle = switchRecordingBothScreens;
        TextView summary = textRecordingBothScreensSummary;
        if (toggle == null || summary == null) return;

        boolean dualScreenEnabled = LauncherPreferences.isDualScreenSupportEnabled(this);
        int visibility = dualScreenEnabled ? View.VISIBLE : View.GONE;
        toggle.setVisibility(visibility);
        summary.setVisibility(visibility);
        if (!dualScreenEnabled) return;

        boolean enabled = RecordingPreferences.isRecordBothScreensEnabled(this);
        if (toggle.isChecked() != enabled) toggle.setChecked(enabled);
        updateRecordingBothScreensText(toggle, enabled);
    }

    private void showRecordingAudioModeDialog(@NonNull MaterialButton button) {
        final String[] labels = new String[]{
                "Game Only Audio",
                "Voice Only Audio",
                "Voice & Game Audio"
        };
        final String[] modes = new String[]{
                RecordingPreferences.AUDIO_MODE_GAME_ONLY,
                RecordingPreferences.AUDIO_MODE_VOICE_ONLY,
                RecordingPreferences.AUDIO_MODE_VOICE_AND_GAME
        };

        String current = RecordingPreferences.getAudioMode(this);
        int selected = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i].equals(current)) {
                selected = i;
                break;
            }
        }

        final androidx.appcompat.app.AlertDialog[] dialogRef = new androidx.appcompat.app.AlertDialog[1];
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Recording audio")
                .setSingleChoiceItems(labels, selected, (whichDialog, which) -> {
                    int safeIndex = Math.max(0, Math.min(which, modes.length - 1));
                    RecordingPreferences.setAudioMode(this, modes[safeIndex]);
                    updateRecordingAudioModeText(button);
                    if (dialogRef[0] != null) dialogRef[0].dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialogRef[0] = dialog;
        dialog.show();
    }

    private void updateRecordingAudioModeText(@NonNull MaterialButton button) {
        button.setText("Recording audio: " + RecordingPreferences.getAudioModeLabel(this));
    }

    private void updateRecordingBothScreensText(
            @NonNull com.google.android.material.switchmaterial.SwitchMaterial toggle,
            boolean enabled
    ) {
        toggle.setText(enabled
                ? "Record both screens: On"
                : "Record both screens: Off");
    }

    private void updateRecordingHideTouchText(
            @NonNull com.google.android.material.switchmaterial.SwitchMaterial toggle,
            boolean enabled
    ) {
        toggle.setText(enabled
                ? "Hide touch controls in recordings: On"
                : "Hide touch controls in recordings: Off");
    }
    // DROIDBRIDGE_RECORDING_SETTINGS_SECTION_END

    @Nullable
    private TextView findSettingsSectionTitle(@NonNull View root, @NonNull CharSequence wanted) {
        if (root instanceof TextView) {
            TextView textView = (TextView) root;
            CharSequence value = textView.getText();
            if (value != null && wanted.toString().contentEquals(value)) return textView;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findSettingsSectionTitle(group.getChildAt(i), wanted);
                if (found != null) return found;
            }
        }
        return null;
    }

    @NonNull
    private TextView cloneSettingsSectionTitle(@Nullable TextView source, @NonNull String titleText) {
        TextView title = new TextView(this);
        title.setText(titleText);
        if (source != null) {
            title.setTextColor(source.getTextColors());
            title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, source.getTextSize());
            title.setTypeface(source.getTypeface());
            title.setGravity(source.getGravity());
            title.setIncludeFontPadding(source.getIncludeFontPadding());
            title.setPadding(source.getPaddingLeft(), source.getPaddingTop(),
                    source.getPaddingRight(), source.getPaddingBottom());
            ViewGroup.LayoutParams params = source.getLayoutParams();
            if (params instanceof LinearLayout.LayoutParams) {
                title.setLayoutParams(new LinearLayout.LayoutParams((LinearLayout.LayoutParams) params));
            }
        } else {
            title.setTextColor(COLOR_TEXT_PRIMARY);
            title.setTextSize(20f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.bottomMargin = dp(10);
            title.setLayoutParams(params);
        }
        return title;
    }
    // DROIDBRIDGE_DUAL_SCREEN_SETTINGS_SECTION_END

    private void refreshDualScreenSettingsUi() {
        if (binding == null
                || binding.switchDualScreenSupport == null
                || binding.switchDualScreenSwap == null) {
            return;
        }

        boolean enabled = LauncherPreferences.isDualScreenSupportEnabled(this);
        boolean swapped = LauncherPreferences.isDualScreenLastSwapRequest(this);
        binding.switchDualScreenSupport.setChecked(enabled);
        binding.switchDualScreenSwap.setEnabled(enabled);
        binding.switchDualScreenSwap.setChecked(swapped);
        binding.switchDualScreenAspectRatio.setEnabled(enabled);
        binding.switchDualScreenAspectRatio.setChecked(LauncherPreferences.isDualScreenFourThreeLayout(this));
        binding.switchDualScreenFps.setEnabled(enabled);
        binding.switchDualScreenFps.setChecked(LauncherPreferences.isDualScreenFpsEnabled(this));
        updateDualScreenSupportSwitchText(enabled);
        updateDualScreenSwapSwitchText(swapped);
        updateDualScreenAspectRatioSwitchText();
        updateDualScreenFpsSwitchText();
        updateDualScreenHudLayoutSettingUi();
        updateDualScreenTouchScaleSettingUi();
        updateDualScreenBackgroundSettingUi();
        updateDualScreenMapFrameSettingUi();
    }

    private void installDualScreenHudLayoutSetting() {
        if (binding == null || binding.switchDualScreenSwap == null) return;
        android.view.ViewParent rawParent = binding.switchDualScreenSwap.getParent();
        if (!(rawParent instanceof ViewGroup)) return;

        ViewGroup parent = (ViewGroup) rawParent;
        final String buttonTag = "dual_screen_hud_layout_button";
        final String summaryTag = "dual_screen_hud_layout_summary";

        View existingButton = parent.findViewWithTag(buttonTag);
        if (existingButton instanceof MaterialButton) {
            buttonDualScreenHudLayout = (MaterialButton) existingButton;
        } else {
            MaterialButton button = new MaterialButton(
                    this,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
            );
            button.setTag(buttonTag);
            button.setAllCaps(false);
            button.setOnClickListener(view -> showDualScreenHudLayoutDialog());

            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            buttonParams.topMargin = dp(10);
            int anchorIndex = parent.indexOfChild(binding.textDualScreenFpsSummary);
            if (anchorIndex < 0) anchorIndex = parent.indexOfChild(binding.switchDualScreenSwap);
            parent.addView(button, Math.min(parent.getChildCount(), Math.max(0, anchorIndex + 1)), buttonParams);
            buttonDualScreenHudLayout = button;
        }

        View existingSummary = parent.findViewWithTag(summaryTag);
        if (existingSummary instanceof TextView) {
            textDualScreenHudLayoutSummary = (TextView) existingSummary;
        } else {
            TextView summary = new TextView(this);
            summary.setTag(summaryTag);
            summary.setText("Modern keeps the current HUD style with larger status/XP elements. Legacy uses the old-console-inspired HUD arrangement. 3DS matches the compact map-and-button lower screen and intentionally hides hearts, armor, hunger, air, and XP from the bottom display.");

            LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            summaryParams.topMargin = dp(2);
            int buttonIndex = buttonDualScreenHudLayout == null
                    ? parent.getChildCount()
                    : parent.indexOfChild(buttonDualScreenHudLayout);
            parent.addView(summary, Math.min(parent.getChildCount(), buttonIndex + 1), summaryParams);
            textDualScreenHudLayoutSummary = summary;
        }
        updateDualScreenHudLayoutSettingUi();
    }

    private void updateDualScreenHudLayoutSettingUi() {
        if (buttonDualScreenHudLayout == null) return;
        buttonDualScreenHudLayout.setEnabled(LauncherPreferences.isDualScreenSupportEnabled(this));
        String layout = LauncherPreferences.getDualScreenHudLayout(this);
        String label;
        if (LauncherPreferences.DUAL_SCREEN_HUD_LAYOUT_LEGACY.equals(layout)) {
            label = "Legacy";
        } else if (LauncherPreferences.DUAL_SCREEN_HUD_LAYOUT_3DS.equals(layout)) {
            label = "3DS";
        } else {
            label = "Modern";
        }
        buttonDualScreenHudLayout.setText("Bottom-screen HUD layout: " + label);
    }

    private void showDualScreenHudLayoutDialog() {
        String current = LauncherPreferences.getDualScreenHudLayout(this);
        int checked;
        if (LauncherPreferences.DUAL_SCREEN_HUD_LAYOUT_LEGACY.equals(current)) {
            checked = 1;
        } else if (LauncherPreferences.DUAL_SCREEN_HUD_LAYOUT_3DS.equals(current)) {
            checked = 2;
        } else {
            checked = 0;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Bottom-screen HUD layout")
                .setSingleChoiceItems(
                        new String[]{"Modern", "Legacy", "3DS"},
                        checked,
                        (dialog, which) -> {
                            String selected;
                            if (which == 1) {
                                selected = LauncherPreferences.DUAL_SCREEN_HUD_LAYOUT_LEGACY;
                            } else if (which == 2) {
                                selected = LauncherPreferences.DUAL_SCREEN_HUD_LAYOUT_3DS;
                            } else {
                                selected = LauncherPreferences.DUAL_SCREEN_HUD_LAYOUT_MODERN;
                            }
                            LauncherPreferences.setDualScreenHudLayout(this, selected);
                            updateDualScreenHudLayoutSettingUi();
                            dialog.dismiss();
                        }
                )
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void installDualScreenTouchScaleSetting() {
        if (binding == null || binding.switchDualScreenSwap == null) return;
        android.view.ViewParent rawParent = binding.switchDualScreenSwap.getParent();
        if (!(rawParent instanceof ViewGroup)) return;

        ViewGroup parent = (ViewGroup) rawParent;
        final String buttonTag = "dual_screen_touch_scale_button";
        final String summaryTag = "dual_screen_touch_scale_summary";

        View existingButton = parent.findViewWithTag(buttonTag);
        if (existingButton instanceof MaterialButton) {
            buttonDualScreenTouchScale = (MaterialButton) existingButton;
        } else {
            MaterialButton button = new MaterialButton(
                    this,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
            );
            button.setTag(buttonTag);
            button.setAllCaps(false);
            button.setOnClickListener(view -> showDualScreenTouchScaleDialog());

            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            buttonParams.topMargin = dp(10);
            int anchorIndex = textDualScreenHudLayoutSummary == null
                    ? parent.indexOfChild(buttonDualScreenHudLayout)
                    : parent.indexOfChild(textDualScreenHudLayoutSummary);
            if (anchorIndex < 0) anchorIndex = parent.indexOfChild(binding.textDualScreenFpsSummary);
            parent.addView(button, Math.min(parent.getChildCount(), Math.max(0, anchorIndex + 1)), buttonParams);
            buttonDualScreenTouchScale = button;
        }

        View existingSummary = parent.findViewWithTag(summaryTag);
        if (existingSummary instanceof TextView) {
            textDualScreenTouchScaleSummary = (TextView) existingSummary;
        } else {
            TextView summary = new TextView(this);
            summary.setTag(summaryTag);
            summary.setText("Scales only the touch-control overlay on the bottom screen. Useful when the phone is attached to a TV or monitor; the normal single-screen touch layout is unchanged.");

            LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            summaryParams.topMargin = dp(2);
            int buttonIndex = buttonDualScreenTouchScale == null
                    ? parent.getChildCount()
                    : parent.indexOfChild(buttonDualScreenTouchScale);
            parent.addView(summary, Math.min(parent.getChildCount(), buttonIndex + 1), summaryParams);
            textDualScreenTouchScaleSummary = summary;
        }
        updateDualScreenTouchScaleSettingUi();
    }

    private void updateDualScreenTouchScaleSettingUi() {
        if (buttonDualScreenTouchScale == null) return;
        buttonDualScreenTouchScale.setEnabled(LauncherPreferences.isDualScreenSupportEnabled(this));
        int percent = ControlsPreferences.getDualScreenButtonScalePercent(this);
        buttonDualScreenTouchScale.setText("Bottom-screen touch control scale: " + percent + "%");
    }

    private void showDualScreenTouchScaleDialog() {
        final int[] values = new int[]{75, 100, 125, 150, 175, 200, 225, 250, 275, 300};
        String[] labels = new String[values.length];
        int current = ControlsPreferences.getDualScreenButtonScalePercent(this);
        int checked = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            labels[i] = values[i] + "%";
            int distance = Math.abs(values[i] - current);
            if (distance < bestDistance) {
                bestDistance = distance;
                checked = i;
            }
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("Bottom-screen touch control scale")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    if (which >= 0 && which < values.length) {
                        ControlsPreferences.setDualScreenButtonScalePercent(this, values[which]);
                        updateDualScreenTouchScaleSettingUi();
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void registerDualScreenBackgroundPickerLauncher() {
        dualScreenBackgroundPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                    Uri uri = result.getData().getData();
                    if (uri == null) return;
                    importDualScreenBackgroundImage(uri);
                }
        );
    }

    private void registerDualScreenMapFramePickerLauncher() {
        dualScreenMapFramePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                    Uri uri = result.getData().getData();
                    if (uri == null) return;
                    importDualScreenMapFrameImage(uri);
                }
        );
    }

    private void installDualScreenBackgroundSetting() {
        if (binding == null || binding.switchDualScreenSwap == null) return;
        android.view.ViewParent rawParent = binding.switchDualScreenSwap.getParent();
        if (!(rawParent instanceof ViewGroup)) return;

        ViewGroup parent = (ViewGroup) rawParent;
        final String buttonTag = "dual_screen_background_button";
        final String summaryTag = "dual_screen_background_summary";

        View existingButton = parent.findViewWithTag(buttonTag);
        if (existingButton instanceof MaterialButton) {
            buttonDualScreenBackground = (MaterialButton) existingButton;
        } else {
            MaterialButton button = new MaterialButton(
                    this,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
            );
            button.setTag(buttonTag);
            button.setAllCaps(false);
            button.setOnClickListener(view -> showDualScreenBackgroundDialog());

            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            buttonParams.topMargin = dp(10);

            int swapIndex = parent.indexOfChild(binding.switchDualScreenSwap);
            int insertIndex = Math.min(parent.getChildCount(), Math.max(0, swapIndex + 1));
            if (textDualScreenHudLayoutSummary != null) {
                int summaryIndex = parent.indexOfChild(textDualScreenHudLayoutSummary);
                if (summaryIndex >= 0) insertIndex = Math.min(parent.getChildCount(), summaryIndex + 1);
            } else if (buttonDualScreenHudLayout != null) {
                int layoutIndex = parent.indexOfChild(buttonDualScreenHudLayout);
                if (layoutIndex >= 0) insertIndex = Math.min(parent.getChildCount(), layoutIndex + 1);
            }
            parent.addView(button, insertIndex, buttonParams);
            buttonDualScreenBackground = button;
        }

        View existingSummary = parent.findViewWithTag(summaryTag);
        if (existingSummary instanceof TextView) {
            textDualScreenBackgroundSummary = (TextView) existingSummary;
        } else {
            TextView summary = new TextView(this);
            summary.setTag(summaryTag);
            summary.setText("Choose an image shown behind the touch controls and HUD. Without a custom image, DroidBridge uses its built-in dark block texture for both Modern and Legacy layouts.");

            LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            summaryParams.topMargin = dp(2);

            int buttonIndex = buttonDualScreenBackground == null
                    ? parent.getChildCount()
                    : parent.indexOfChild(buttonDualScreenBackground);
            parent.addView(summary, Math.min(parent.getChildCount(), buttonIndex + 1), summaryParams);
            textDualScreenBackgroundSummary = summary;
        }

        updateDualScreenBackgroundSettingUi();
    }

    private void updateDualScreenBackgroundSettingUi() {
        if (buttonDualScreenBackground == null) return;
        buttonDualScreenBackground.setEnabled(LauncherPreferences.isDualScreenSupportEnabled(this));
        boolean custom = LauncherPreferences.hasDualScreenBackgroundImage(this);
        buttonDualScreenBackground.setText(
                custom
                        ? "Dual-screen background: Custom image"
                        : "Dual-screen background: Default block texture"
        );
    }

    private void showDualScreenBackgroundDialog() {
        boolean custom = LauncherPreferences.hasDualScreenBackgroundImage(this);
        if (!custom) {
            openDualScreenBackgroundPicker();
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("Dual-screen background")
                .setItems(new String[]{"Choose a different image", "Use default background"}, (dialog, which) -> {
                    if (which == 0) {
                        openDualScreenBackgroundPicker();
                    } else if (which == 1) {
                        LauncherPreferences.clearDualScreenBackgroundImage(this);
                        updateDualScreenBackgroundSettingUi();
                        Toast.makeText(this, "Dual-screen background reset.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openDualScreenBackgroundPicker() {
        if (dualScreenBackgroundPickerLauncher == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        dualScreenBackgroundPickerLauncher.launch(intent);
    }

    private void importDualScreenBackgroundImage(@NonNull Uri uri) {
        File target = LauncherPreferences.getDualScreenBackgroundImageFile(this);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }

        try {
            copyUriToFile(uri, target);

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(target.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                Toast.makeText(this, "That file could not be used as a background image.", Toast.LENGTH_LONG).show();
                updateDualScreenBackgroundSettingUi();
                return;
            }

            updateDualScreenBackgroundSettingUi();
            Toast.makeText(
                    this,
                    "Dual-screen background saved. It will appear behind the controls/HUD.",
                    Toast.LENGTH_SHORT
            ).show();
        } catch (Throwable throwable) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            updateDualScreenBackgroundSettingUi();
            Toast.makeText(
                    this,
                    throwable.getMessage() != null ? throwable.getMessage() : throwable.toString(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void installDualScreenMapFrameSetting() {
        if (binding == null || binding.switchDualScreenSwap == null) return;
        android.view.ViewParent rawParent = binding.switchDualScreenSwap.getParent();
        if (!(rawParent instanceof ViewGroup)) return;

        ViewGroup parent = (ViewGroup) rawParent;
        final String buttonTag = "dual_screen_map_frame_button";
        final String summaryTag = "dual_screen_map_frame_summary";

        View existingButton = parent.findViewWithTag(buttonTag);
        if (existingButton instanceof MaterialButton) {
            buttonDualScreenMapFrame = (MaterialButton) existingButton;
        } else {
            MaterialButton button = new MaterialButton(
                    this,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
            );
            button.setTag(buttonTag);
            button.setAllCaps(false);
            button.setOnClickListener(view -> showDualScreenMapFrameDialog());

            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            buttonParams.topMargin = dp(10);

            int insertIndex = parent.getChildCount();
            if (textDualScreenBackgroundSummary != null) {
                int summaryIndex = parent.indexOfChild(textDualScreenBackgroundSummary);
                if (summaryIndex >= 0) insertIndex = Math.min(parent.getChildCount(), summaryIndex + 1);
            } else if (buttonDualScreenBackground != null) {
                int buttonIndex = parent.indexOfChild(buttonDualScreenBackground);
                if (buttonIndex >= 0) insertIndex = Math.min(parent.getChildCount(), buttonIndex + 1);
            }
            parent.addView(button, insertIndex, buttonParams);
            buttonDualScreenMapFrame = button;
        }

        View existingSummary = parent.findViewWithTag(summaryTag);
        if (existingSummary instanceof TextView) {
            textDualScreenMapFrameSummary = (TextView) existingSummary;
        } else {
            TextView summary = new TextView(this);
            summary.setTag(summaryTag);
            summary.setText("Choose the texture/frame used around the dual-screen exploration map. Square PNG/JPG images work best.");

            LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            summaryParams.topMargin = dp(2);

            int buttonIndex = buttonDualScreenMapFrame == null
                    ? parent.getChildCount()
                    : parent.indexOfChild(buttonDualScreenMapFrame);
            parent.addView(summary, Math.min(parent.getChildCount(), buttonIndex + 1), summaryParams);
            textDualScreenMapFrameSummary = summary;
        }

        updateDualScreenMapFrameSettingUi();
    }

    private void updateDualScreenMapFrameSettingUi() {
        if (buttonDualScreenMapFrame == null) return;
        buttonDualScreenMapFrame.setEnabled(LauncherPreferences.isDualScreenSupportEnabled(this));
        boolean custom = LauncherPreferences.hasDualScreenMapFrameImage(this);
        buttonDualScreenMapFrame.setText(
                custom
                        ? "Exploration map texture: Custom image"
                        : "Exploration map texture: Default parchment"
        );
    }

    private void showDualScreenMapFrameDialog() {
        boolean custom = LauncherPreferences.hasDualScreenMapFrameImage(this);
        if (!custom) {
            openDualScreenMapFramePicker();
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("Exploration map texture")
                .setItems(new String[]{"Choose a different image", "Use default parchment"}, (dialog, which) -> {
                    if (which == 0) {
                        openDualScreenMapFramePicker();
                    } else if (which == 1) {
                        LauncherPreferences.clearDualScreenMapFrameImage(this);
                        updateDualScreenMapFrameSettingUi();
                        Toast.makeText(this, "Exploration map texture reset.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openDualScreenMapFramePicker() {
        if (dualScreenMapFramePickerLauncher == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        dualScreenMapFramePickerLauncher.launch(intent);
    }

    private void importDualScreenMapFrameImage(@NonNull Uri uri) {
        File target = LauncherPreferences.getDualScreenMapFrameImageFile(this);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }

        try {
            copyUriToFile(uri, target);

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(target.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                Toast.makeText(this, "That file could not be used as a map texture.", Toast.LENGTH_LONG).show();
                updateDualScreenMapFrameSettingUi();
                return;
            }

            updateDualScreenMapFrameSettingUi();
            Toast.makeText(
                    this,
                    "Exploration map texture saved.",
                    Toast.LENGTH_SHORT
            ).show();
        } catch (Throwable throwable) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            updateDualScreenMapFrameSettingUi();
            Toast.makeText(
                    this,
                    throwable.getMessage() != null ? throwable.getMessage() : throwable.toString(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void updateDualScreenSupportSwitchText(boolean enabled) {
        binding.switchDualScreenSupport.setText(
                enabled
                        ? R.string.dual_screen_support_on
                        : R.string.dual_screen_support_off
        );
    }

    private void updateDualScreenSwapSwitchText(boolean swapped) {
        binding.switchDualScreenSwap.setText(
                swapped
                        ? R.string.dual_screen_swap_on
                        : R.string.dual_screen_swap_off
        );
    }

    private void updateDualScreenAspectRatioSwitchText() {
        binding.switchDualScreenAspectRatio.setText(
                LauncherPreferences.isDualScreenFourThreeLayout(this)
                        ? "Bottom-screen layout: 4:3"
                        : "Bottom-screen layout: 16:9"
        );
    }

    private void updateDualScreenFpsSwitchText() {
        binding.switchDualScreenFps.setText(
                LauncherPreferences.isDualScreenFpsEnabled(this)
                        ? "Bottom-screen FPS: On"
                        : "Bottom-screen FPS: Off"
        );
    }

    private void updateLauncherDiagnosticLogsSwitchText(boolean enabled) {
        binding.switchLauncherDiagnosticLogs.setText(
                enabled
                        ? R.string.launcher_logs_on
                        : R.string.launcher_logs_off
        );
    }

    private void updateShareLogChooserSwitchText(boolean enabled) {
        binding.switchShareLogChooser.setText(
                enabled
                        ? R.string.share_logs_dialog_setting_on
                        : R.string.share_logs_dialog_setting_off
        );
    }

    private void updateGameLogOverlaySwitchText(boolean showGameLogOverlay) {
        binding.switchShowGameLogOverlay.setText(
                showGameLogOverlay
                        ? R.string.game_log_overlay_on
                        : R.string.game_log_overlay_off
        );
    }

    private void updateAccountStatus(@Nullable AccountStore.Account account) {
        if (MicrosoftAccountRegistry.isUsableMicrosoftAccount(account)) {
            MicrosoftAccountRegistry.save(this, account);
        }

        boolean accountStoreHasMicrosoft = accountStore != null && accountStore.hasStoredMicrosoftAccount();
        boolean registryHasMicrosoft = MicrosoftAccountRegistry.hasAny(this);
        boolean hasRememberedMicrosoft = accountStoreHasMicrosoft || registryHasMicrosoft;
        boolean offlineUnlocked = accountStore != null && accountStore.canUseOfflineMode();
        boolean activeOffline = account != null && account.isOfflineAccount();
        boolean activeMicrosoft = account != null && account.isMicrosoftAccount();
        int microsoftAccountCount = MicrosoftAccountRegistry.count(this)
                + (accountStoreHasMicrosoft && !registryHasMicrosoft ? 1 : 0);

        binding.buttonSignIn.setVisibility(hasRememberedMicrosoft ? View.GONE : View.VISIBLE);
        binding.buttonAddMicrosoftAccount.setVisibility(hasRememberedMicrosoft ? View.VISIBLE : View.GONE);
        binding.buttonAddMicrosoftAccount.setEnabled(true);
        binding.buttonManageMicrosoftAccounts.setVisibility(hasRememberedMicrosoft ? View.VISIBLE : View.GONE);
        binding.buttonManageMicrosoftAccounts.setEnabled(hasRememberedMicrosoft);
        binding.buttonSignOut.setVisibility((hasRememberedMicrosoft || account != null) ? View.VISIBLE : View.GONE);
        binding.buttonManageOfflineAccounts.setVisibility(offlineUnlocked ? View.VISIBLE : View.GONE);
        binding.buttonManageOfflineAccounts.setEnabled(offlineUnlocked);
        binding.buttonUseMicrosoftAccount.setVisibility(hasRememberedMicrosoft && activeOffline ? View.VISIBLE : View.GONE);
        binding.buttonUseMicrosoftAccount.setText(microsoftAccountCount > 1 ? "Choose Microsoft account" : getString(R.string.button_use_microsoft_account));
        binding.buttonRefreshMicrosoftSkin.setVisibility(hasRememberedMicrosoft ? View.VISIBLE : View.GONE);
        binding.buttonRefreshMicrosoftSkin.setEnabled(hasRememberedMicrosoft);

        if (activeOffline) {
            String microsoftName = "Microsoft account";
            AccountStore.Account remembered = accountStore != null ? accountStore.loadLastMicrosoftAccount() : null;
            if (remembered != null) microsoftName = remembered.getBestDisplayName();
            binding.textAccountStatus.setText(getString(R.string.status_offline_account_with_microsoft, account.getBestDisplayName(), microsoftName));
            return;
        }

        if (activeMicrosoft) {
            binding.textAccountStatus.setText(getString(R.string.status_signed_in, account.getBestDisplayName()));
            return;
        }

        if (hasRememberedMicrosoft) {
            AccountStore.Account remembered = getPreferredRememberedMicrosoftAccount();
            String name = remembered != null ? remembered.getBestDisplayName() : "Microsoft Player";
            if (microsoftAccountCount > 1) {
                binding.textAccountStatus.setText("Microsoft accounts saved: " + microsoftAccountCount + " • Last used: " + name);
            } else {
                binding.textAccountStatus.setText(getString(R.string.status_microsoft_remembered, name));
            }
            return;
        }

        if (offlineUnlocked) {
            binding.textAccountStatus.setText(R.string.status_signed_out_offline_unlocked);
        } else {
            binding.textAccountStatus.setText(R.string.status_signed_out);
        }
    }

    private void useRememberedMicrosoftAccount() {
        if (accountStore == null) return;
        if (MicrosoftAccountRegistry.count(this) > 1) {
            showMicrosoftAccountsDialog();
            return;
        }
        try {
            AccountStore.Account registryAccount = getPreferredRememberedMicrosoftAccount();
            if (registryAccount != null && MicrosoftAccountRegistry.isUsableMicrosoftAccount(registryAccount)) {
                accountStore.saveMicrosoftAccount(registryAccount);
            } else {
                accountStore.useLastMicrosoftAccount();
            }
            AccountStore.Account account = accountStore.load();
            updateAccountStatus(account);
            updateSkinUi(account);
            Toast.makeText(this, R.string.microsoft_account_restored, Toast.LENGTH_SHORT).show();
        } catch (Throwable throwable) {
            Toast.makeText(this, throwable.getMessage() != null ? throwable.getMessage() : throwable.toString(), Toast.LENGTH_LONG).show();
        }
    }

    @Nullable
    private AccountStore.Account getPreferredRememberedMicrosoftAccount() {
        if (accountStore != null) {
            try {
                AccountStore.Account remembered = accountStore.loadLastMicrosoftAccount();
                if (remembered != null && remembered.isMicrosoftAccount()) {
                    MicrosoftAccountRegistry.save(this, remembered);
                    return remembered;
                }
            } catch (Throwable throwable) {
                Logging.e("LauncherSettings", "Unable to load remembered Microsoft account", throwable);
            }
        }

        ArrayList<AccountStore.Account> accounts = MicrosoftAccountRegistry.list(this);
        return accounts.isEmpty() ? null : accounts.get(0);
    }

    @NonNull
    private ArrayList<AccountStore.Account> getMicrosoftAccountsForDialog() {
        ArrayList<AccountStore.Account> accounts = MicrosoftAccountRegistry.list(this);
        if (accountStore != null) {
            try {
                AccountStore.Account active = accountStore.load();
                if (MicrosoftAccountRegistry.isUsableMicrosoftAccount(active)
                        && !MicrosoftAccountRegistry.containsSame(accounts, active)) {
                    accounts.add(0, active);
                    MicrosoftAccountRegistry.save(this, active);
                }
            } catch (Throwable ignored) {
            }

            try {
                AccountStore.Account remembered = accountStore.loadLastMicrosoftAccount();
                if (MicrosoftAccountRegistry.isUsableMicrosoftAccount(remembered)
                        && !MicrosoftAccountRegistry.containsSame(accounts, remembered)) {
                    accounts.add(remembered);
                    MicrosoftAccountRegistry.save(this, remembered);
                }
            } catch (Throwable ignored) {
            }
        }
        return accounts;
    }

    private void showMicrosoftAccountsDialog() {
        if (accountStore == null) return;

        if (microsoftAccountsDialog != null && microsoftAccountsDialog.isShowing()) {
            microsoftAccountsDialog.dismiss();
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(COLOR_DIALOG_BG);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setScrollbarFadingEnabled(false);

        LinearLayout root = createStyledDialogRoot(
                scrollView,
                "Microsoft accounts",
                "Choose the account DroidBridge should use for launch, refresh a saved account, remove one, or add another Microsoft account."
        );

        ArrayList<AccountStore.Account> accounts = getMicrosoftAccountsForDialog();
        AccountStore.Account active = null;
        try {
            active = accountStore.load();
        } catch (Throwable ignored) {
        }

        if (accounts.isEmpty()) {
            LinearLayout card = addStyledDialogCard(root);
            addStyledDialogCardTitle(card, "No Microsoft accounts saved");
            addStyledDialogInfoText(card, "Use Add another account to sign in and save a Microsoft account.");
        } else {
            for (AccountStore.Account account : accounts) {
                root.addView(buildMicrosoftAccountRow(account, active));
            }
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Add another", null)
                .create();

        microsoftAccountsDialog = dialog;
        dialog.setOnShowListener(dialogInterface -> {
            styleLauncherDialogChrome(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                dialog.dismiss();
                startMicrosoftSignInFlow(true);
            });
        });
        dialog.setOnDismissListener(dialogInterface -> {
            if (microsoftAccountsDialog == dialog) {
                microsoftAccountsDialog = null;
            }
        });
        dialog.show();
    }

    @NonNull
    private View buildMicrosoftAccountRow(
            @NonNull AccountStore.Account account,
            @Nullable AccountStore.Account active
    ) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(roundedDrawable(COLOR_CARD_BG, COLOR_CARD_STROKE, 18));

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(cardParams);

        boolean isActive = MicrosoftAccountRegistry.isSameAccount(account, active);
        TextView title = new TextView(this);
        title.setText(account.getBestDisplayName() + (isActive ? "  •  Active" : ""));
        title.setTextColor(COLOR_TEXT_PRIMARY);
        title.setTextSize(17f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView summary = new TextView(this);
        String uuid = !isNullOrBlank(account.minecraftUuid) ? account.minecraftUuid : "No UUID saved";
        summary.setText("Minecraft: " + account.minecraftName + "\nUUID: " + uuid);
        summary.setTextColor(COLOR_TEXT_SECONDARY);
        summary.setTextSize(12.5f);
        summary.setPadding(0, dp(4), 0, dp(8));
        card.addView(summary, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        card.addView(buttons, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        MaterialButton use = buildSmallAccountButton(isActive ? "Using" : "Use");
        use.setEnabled(!isActive);
        use.setOnClickListener(view -> useMicrosoftAccount(account));
        buttons.addView(use);

        MaterialButton refresh = buildSmallAccountButton("Refresh");
        refresh.setOnClickListener(view -> refreshSpecificMicrosoftAccount(account));
        buttons.addView(refresh);

        MaterialButton remove = buildSmallAccountButton("Remove");
        remove.setOnClickListener(view -> confirmRemoveMicrosoftAccount(account));
        buttons.addView(remove);

        return card;
    }

    @NonNull
    private MaterialButton buildSmallAccountButton(@NonNull String text) {
        MaterialButton button = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(10), dp(6), dp(10), dp(6));
        styleDialogOutlinedButton(button);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(dp(6), 0, 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private void useMicrosoftAccount(@NonNull AccountStore.Account account) {
        if (accountStore == null) return;
        try {
            accountStore.saveMicrosoftAccount(account);
            MicrosoftAccountRegistry.save(this, account);
            AccountStore.Account active = accountStore.load();
            updateAccountStatus(active);
            updateSkinUi(active);
            Toast.makeText(this, "Using Microsoft account: " + account.getBestDisplayName(), Toast.LENGTH_SHORT).show();
            if (microsoftAccountsDialog != null && microsoftAccountsDialog.isShowing()) {
                microsoftAccountsDialog.dismiss();
            }
        } catch (Throwable throwable) {
            Toast.makeText(this, throwable.getMessage() != null ? throwable.getMessage() : throwable.toString(), Toast.LENGTH_LONG).show();
        }
    }

    private void refreshSpecificMicrosoftAccount(@NonNull AccountStore.Account account) {
        if (accountStore == null || authManager == null) return;
        try {
            accountStore.saveMicrosoftAccount(account);
            MicrosoftAccountRegistry.save(this, account);
            binding.textSkinStatus.setText(R.string.microsoft_skin_refreshing);
            binding.buttonRefreshMicrosoftSkin.setEnabled(false);
            Toast.makeText(this, "Refreshing " + account.getBestDisplayName() + "...", Toast.LENGTH_SHORT).show();
            authManager.refreshMicrosoftAccount();
        } catch (Throwable throwable) {
            Toast.makeText(this, throwable.getMessage() != null ? throwable.getMessage() : throwable.toString(), Toast.LENGTH_LONG).show();
        }
    }

    private void confirmRemoveMicrosoftAccount(@NonNull AccountStore.Account account) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Remove Microsoft account?")
                .setMessage("Remove " + account.getBestDisplayName() + " from DroidBridge's saved account list?")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Remove", (dialog, which) -> removeMicrosoftAccount(account))
                .show();
    }

    private void removeMicrosoftAccount(@NonNull AccountStore.Account account) {
        MicrosoftAccountRegistry.remove(this, account);
        try {
            AccountStore.Account active = accountStore != null ? accountStore.load() : null;
            if (MicrosoftAccountRegistry.isSameAccount(account, active)) {
                ArrayList<AccountStore.Account> remaining = MicrosoftAccountRegistry.list(this);
                if (!remaining.isEmpty()) {
                    accountStore.saveMicrosoftAccount(remaining.get(0));
                } else if (authManager != null) {
                    authManager.signOut();
                }
            }
        } catch (Throwable throwable) {
            Logging.e("LauncherSettings", "Unable to update active Microsoft account after removal", throwable);
        }

        AccountStore.Account current = accountStore != null ? accountStore.load() : null;
        updateAccountStatus(current);
        updateSkinUi(current);
        Toast.makeText(this, "Microsoft account removed.", Toast.LENGTH_SHORT).show();
        showMicrosoftAccountsDialog();
    }

    private void showOfflineAccountsDialog() {
        if (accountStore == null || !accountStore.canUseOfflineMode()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.offline_locked_title)
                    .setMessage(R.string.offline_locked_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(24);
        root.setPadding(padding, dp(18), padding, dp(4));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        root.addView(buildDialogHeader(
                R.drawable.ic_player_head_placeholder,
                R.string.offline_accounts_title,
                R.string.offline_accounts_dialog_summary
        ));

        TextView sectionTitle = new TextView(this);
        sectionTitle.setText(R.string.offline_accounts_section_title);
        sectionTitle.setTextAppearance(android.R.style.TextAppearance_Material_Medium);
        sectionTitle.setTypeface(sectionTitle.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        sectionParams.topMargin = dp(18);
        root.addView(sectionTitle, sectionParams);

        ArrayList<AccountStore.Account> accounts = accountStore.listOfflineAccounts();
        if (accounts.isEmpty()) {
            root.addView(buildEmptyOfflineAccountCard());
        } else {
            AccountStore.Account active = accountStore.load();
            for (AccountStore.Account offline : accounts) {
                root.addView(buildOfflineAccountRow(offline, active));
            }
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.offline_account_add, null)
                .create();

        dialog.setOnShowListener(dialogInterface -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            dialog.dismiss();
            showEditOfflineAccountDialog(null);
        }));
        offlineAccountsDialog = dialog;
        dialog.setOnDismissListener(d -> { if (offlineAccountsDialog == dialog) offlineAccountsDialog = null; });
        dialog.show();
    }

    @NonNull
    private View buildOfflineAccountRow(@NonNull AccountStore.Account offline, @Nullable AccountStore.Account active) {
        boolean isActive = active != null && active.isOfflineAccount() && offline.accountId.equals(active.accountId);

        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(18));
        card.setCardElevation(dp(1));
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(isActive ? 0xFF68C995 : 0x22000000);
        card.setCardBackgroundColor(isActive ? 0xFFE9F8EF : 0xFFFFFFFF);
        card.setUseCompatPadding(true);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.addView(row, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ));

        ImageView avatar = new ImageView(this);
        avatar.setAdjustViewBounds(true);
        avatar.setScaleType(ImageView.ScaleType.FIT_CENTER);
        avatar.setBackgroundResource(R.drawable.bg_player_head_preview);
        avatar.setPadding(dp(4), dp(4), dp(4), dp(4));
        avatar.setImageResource(R.drawable.ic_player_head_placeholder);
        PlayerHeadLoader.loadInto(this, avatar, offline, null);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(58), dp(58));
        avatarParams.rightMargin = dp(14);
        row.addView(avatar, avatarParams);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText(offline.getBestDisplayName());
        title.setTextAppearance(android.R.style.TextAppearance_Material_Medium);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        labels.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView subtitle = new TextView(this);
        subtitle.setText(offline.hasOfflineSkin()
                ? getString(R.string.offline_account_row_skin, offline.offlineSkinModel)
                : getString(R.string.offline_account_row_no_skin));
        subtitle.setTextAppearance(android.R.style.TextAppearance_Material_Small);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        subtitleParams.topMargin = dp(2);
        labels.addView(subtitle, subtitleParams);

        if (isActive) {
            TextView activeBadge = new TextView(this);
            activeBadge.setText(R.string.offline_account_active_badge);
            activeBadge.setTextAppearance(android.R.style.TextAppearance_Material_Small);
            activeBadge.setTextColor(0xFF168A49);
            activeBadge.setTypeface(activeBadge.getTypeface(), android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            badgeParams.topMargin = dp(4);
            labels.addView(activeBadge, badgeParams);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        actionsParams.leftMargin = dp(12);
        row.addView(actions, actionsParams);

        MaterialButton use = buildCompactDialogButton(isActive ? R.string.offline_account_active_button : R.string.offline_account_use);
        use.setEnabled(!isActive);
        use.setOnClickListener(v -> {
            if (offlineAccountsDialog != null) offlineAccountsDialog.dismiss();
            accountStore.activateOfflineAccount(offline.accountId);
            AccountStore.Account account = accountStore.load();
            updateAccountStatus(account);
            updateSkinUi(account);
            Toast.makeText(this, getString(R.string.offline_account_enabled, offline.getBestDisplayName()), Toast.LENGTH_SHORT).show();
        });
        addButtonWithTopMargin(actions, use, 0);

        MaterialButton edit = buildCompactDialogButton(R.string.offline_account_edit_button);
        edit.setOnClickListener(v -> {
            if (offlineAccountsDialog != null) offlineAccountsDialog.dismiss();
            showEditOfflineAccountDialog(offline);
        });
        addButtonWithTopMargin(actions, edit, dp(6));

        MaterialButton delete = buildCompactDialogButton(R.string.offline_account_delete_button);
        delete.setOnClickListener(v -> {
            if (offlineAccountsDialog != null) offlineAccountsDialog.dismiss();
            confirmDeleteOfflineAccount(offline);
        });
        addButtonWithTopMargin(actions, delete, dp(6));

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.topMargin = dp(10);
        card.setLayoutParams(cardParams);
        return card;
    }

    private void showEditOfflineAccountDialog(@Nullable AccountStore.Account existing) {
        if (accountStore == null) return;

        pendingOfflineSkinUri = null;
        pendingOfflineSkinPreview = null;
        pendingOfflineSkinLabel = null;

        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(18), dp(24), dp(4));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        root.addView(buildDialogHeader(
                R.drawable.ic_player_head_placeholder,
                existing == null ? R.string.offline_account_create_title : R.string.offline_account_edit_title,
                R.string.offline_account_edit_summary
        ));

        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(18));
        card.setCardElevation(dp(1));
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(0x22000000);
        card.setCardBackgroundColor(0xFFFFFFFF);
        card.setUseCompatPadding(true);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.topMargin = dp(16);
        root.addView(card, cardParams);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.addView(row, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ));

        ImageView preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackgroundResource(R.drawable.bg_player_head_preview);
        preview.setPadding(dp(6), dp(6), dp(6), dp(6));
        preview.setImageResource(R.drawable.ic_player_head_placeholder);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(dp(88), dp(88));
        previewParams.rightMargin = dp(14);
        row.addView(preview, previewParams);
        pendingOfflineSkinPreview = preview;

        if (existing != null && existing.hasOfflineSkin()) {
            PlayerHeadLoader.loadInto(this, preview, existing, null);
        }

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        row.addView(form, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setHint(R.string.offline_account_name_hint);
        input.setText(existing != null ? existing.getBestDisplayName() : "Player");
        form.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView skinLabel = new TextView(this);
        skinLabel.setText(existing != null && existing.hasOfflineSkin()
                ? getString(R.string.offline_account_skin_current, existing.offlineSkinModel)
                : getString(R.string.offline_account_skin_none));
        skinLabel.setTextAppearance(android.R.style.TextAppearance_Material_Small);
        LinearLayout.LayoutParams skinLabelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        skinLabelParams.topMargin = dp(6);
        form.addView(skinLabel, skinLabelParams);
        pendingOfflineSkinLabel = skinLabel;

        LinearLayout skinActions = new LinearLayout(this);
        skinActions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams skinActionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        skinActionsParams.topMargin = dp(8);
        form.addView(skinActions, skinActionsParams);

        MaterialButton chooseSkin = buildCompactDialogButton(R.string.offline_account_choose_skin);
        chooseSkin.setOnClickListener(v -> openOfflineSkinPicker());
        skinActions.addView(chooseSkin, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final boolean[] clearSkin = new boolean[]{false};
        MaterialButton clearSkinButton = buildCompactDialogButton(R.string.offline_account_clear_skin);
        clearSkinButton.setOnClickListener(v -> {
            pendingOfflineSkinUri = null;
            clearSkin[0] = true;
            preview.setImageResource(R.drawable.ic_player_head_placeholder);
            skinLabel.setText(R.string.offline_account_skin_none);
        });
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        clearParams.leftMargin = dp(8);
        skinActions.addView(clearSkinButton, clearParams);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(existing == null ? R.string.offline_account_create : R.string.offline_account_save, null)
                .create();

        dialog.setOnShowListener(dialogInterface -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String name = sanitizeOfflineName(input.getText() == null ? "" : input.getText().toString());
            if (!isValidOfflineName(name)) {
                input.setError(getString(R.string.offline_account_invalid));
                return;
            }

            try {
                AccountStore.Account account = accountStore.saveOrUpdateOfflineAccount(
                        existing != null ? existing.accountId : null,
                        name,
                        pendingOfflineSkinUri,
                        clearSkin[0]
                );
                updateAccountStatus(account);
                updateSkinUi(account);
                Toast.makeText(this, getString(R.string.offline_account_enabled, account.getBestDisplayName()), Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            } catch (Throwable throwable) {
                input.setError(throwable.getMessage() != null ? throwable.getMessage() : throwable.toString());
            }
        }));

        dialog.setOnDismissListener(dialogInterface -> {
            pendingOfflineSkinUri = null;
            pendingOfflineSkinPreview = null;
            pendingOfflineSkinLabel = null;
        });
        dialog.show();
    }

    @NonNull
    private View buildDialogHeader(int iconResId, int titleResId, int summaryResId) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconResId);
        icon.setBackgroundResource(R.drawable.bg_player_head_preview);
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(72), dp(72));
        iconParams.rightMargin = dp(16);
        header.addView(icon, iconParams);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        header.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText(titleResId);
        title.setTextAppearance(android.R.style.TextAppearance_Material_Large);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        text.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView summary = new TextView(this);
        summary.setText(summaryResId);
        summary.setTextAppearance(android.R.style.TextAppearance_Material_Small);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        summaryParams.topMargin = dp(4);
        text.addView(summary, summaryParams);

        return header;
    }

    @NonNull
    private View buildEmptyOfflineAccountCard() {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(18));
        card.setCardElevation(dp(1));
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(0x22000000);
        card.setCardBackgroundColor(0xFFFFFFFF);
        card.setUseCompatPadding(true);

        TextView empty = new TextView(this);
        empty.setText(R.string.offline_accounts_empty);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(18), dp(22), dp(18), dp(22));
        empty.setTextAppearance(android.R.style.TextAppearance_Material_Small);
        card.addView(empty, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(10);
        card.setLayoutParams(params);
        return card;
    }

    @NonNull
    private MaterialButton buildCompactDialogButton(int textResId) {
        MaterialButton button = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(textResId);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setMinimumHeight(0);
        button.setMinimumWidth(0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        return button;
    }

    private void addButtonWithTopMargin(@NonNull LinearLayout parent, @NonNull View child, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = topMargin;
        parent.addView(child, params);
    }

    private void updatePendingOfflineSkinPreview(@NonNull Uri uri) {
        if (pendingOfflineSkinPreview == null) return;
        File previewFile = new File(getCacheDir(), "pending_offline_skin_preview.png");
        try (InputStream input = getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(previewFile)) {
            if (input == null) throw new IllegalStateException("Unable to open selected skin.");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            android.graphics.Bitmap head = PlayerHeadLoader.loadHeadFromSkinFile(previewFile);
            if (head != null) pendingOfflineSkinPreview.setImageBitmap(head);
            else pendingOfflineSkinPreview.setImageResource(R.drawable.ic_player_head_placeholder);
        } catch (Throwable ignored) {
            pendingOfflineSkinPreview.setImageResource(R.drawable.ic_player_head_placeholder);
        }
    }

    private void openOfflineSkinPicker() {
        if (offlineSkinPickerLauncher == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/png");
        offlineSkinPickerLauncher.launch(intent);
    }

    private void confirmDeleteOfflineAccount(@NonNull AccountStore.Account account) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.offline_account_delete_title, account.getBestDisplayName()))
                .setMessage(R.string.offline_account_delete_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.offline_account_delete_button, (dialog, which) -> {
                    accountStore.deleteOfflineAccount(account.accountId);
                    AccountStore.Account active = accountStore.load();
                    updateAccountStatus(active);
                    updateSkinUi(active);
                    showOfflineAccountsDialog();
                })
                .show();
    }

    private void updateChangeMicrosoftSkinButtonState(@Nullable AccountStore.Account activeAccount) {
        if (binding == null) return;

        AccountStore.Account microsoft = getMicrosoftSkinTargetAccount(activeAccount);
        boolean canChange = microsoft != null && microsoft.isMicrosoftAccount() && microsoft.hasMinecraftSession();
        binding.buttonChangeMicrosoftSkin.setVisibility(canChange ? View.VISIBLE : View.GONE);
        binding.buttonChangeMicrosoftSkin.setEnabled(canChange);
    }

    private void updateChangeMicrosoftCapeButtonState(@Nullable AccountStore.Account activeAccount) {
        if (binding == null) return;

        AccountStore.Account microsoft = getMicrosoftSkinTargetAccount(activeAccount);
        boolean canChange = microsoft != null && microsoft.isMicrosoftAccount() && microsoft.hasMinecraftSession();
        binding.buttonChangeMicrosoftCape.setVisibility(canChange ? View.VISIBLE : View.GONE);
        binding.buttonChangeMicrosoftCape.setEnabled(canChange);
    }

    private void updatePlayerModelPreview(@Nullable AccountStore.Account account) {
        if (binding == null || binding.modelPlayerPreview == null) return;

        AccountStore.Account microsoft = getMicrosoftSkinTargetAccount(account);
        if (microsoft != null && microsoft.isMicrosoftAccount() && !isNullOrBlank(microsoft.skinUrl)) {
            binding.modelPlayerPreview.setSkinUrl(microsoft.skinUrl);
        } else {
            binding.modelPlayerPreview.setSkinUrl(null);
        }

        refreshActiveCapeIntoPlayerModel(microsoft);
    }

    private void refreshActiveCapeIntoPlayerModel(@Nullable AccountStore.Account account) {
        if (binding == null || binding.modelPlayerPreview == null) return;

        final int generation = ++playerModelCapeLoadGeneration;
        binding.modelPlayerPreview.setCapeUrl(null);

        if (account == null || !account.isMicrosoftAccount() || !account.hasMinecraftSession()) {
            return;
        }

        final String token = account.minecraftAccessToken;
        if (isNullOrBlank(token)) return;

        MicrosoftCapeService.Profile cachedProfile = getCachedMicrosoftCapeProfile(token);
        if (cachedProfile != null) {
            MicrosoftCapeService.CapeEntry activeCape = getEffectiveActiveCape(cachedProfile);
            binding.modelPlayerPreview.setCapeUrl(activeCape != null ? activeCape.url : null);
            return;
        }

        Thread thread = new Thread(() -> {
            String activeCapeUrl = null;
            MicrosoftCapeService.Profile loadedProfile = null;
            Throwable loadFailure = null;
            try {
                loadedProfile = MicrosoftCapeService.fetchProfile(token);
                microsoftCapeProfileAuthenticationStale = false;
                cacheMicrosoftCapeProfile(token, loadedProfile);
                MicrosoftCapeService.CapeEntry activeCape = getEffectiveActiveCape(loadedProfile);
                if (activeCape != null && !isNullOrBlank(activeCape.url)) {
                    activeCapeUrl = activeCape.url;
                }
            } catch (Throwable throwable) {
                loadFailure = throwable;
                if (isMicrosoftCapeAuthenticationFailure(throwable)) {
                    microsoftCapeProfileAuthenticationStale = true;
                }
                Logging.i("LauncherSettings", "Unable to load active cape preview: " + throwable.getMessage());
            }

            final String finalCapeUrl = activeCapeUrl;
            final MicrosoftCapeService.Profile finalLoadedProfile = loadedProfile;
            final Throwable finalLoadFailure = loadFailure;
            final AccountStore.Account finalAccount = account;
            runOnUiThread(() -> {
                if (generation != playerModelCapeLoadGeneration || binding == null || binding.modelPlayerPreview == null) {
                    return;
                }
                binding.modelPlayerPreview.setCapeUrl(finalCapeUrl);

                if (pendingMicrosoftCapeOpenAfterAuthRefresh) {
                    pendingMicrosoftCapeOpenAfterAuthRefresh = false;
                    binding.buttonChangeMicrosoftCape.setText(R.string.microsoft_cape_change_button);
                    updateChangeMicrosoftCapeButtonState(accountStore != null ? accountStore.load() : null);

                    if (finalLoadedProfile != null && finalAccount != null) {
                        showMicrosoftCapePickerDialog(finalAccount, finalLoadedProfile);
                    } else {
                        String message = finalLoadFailure != null && finalLoadFailure.getMessage() != null
                                ? finalLoadFailure.getMessage()
                                : "Unable to refresh Microsoft cape information.";
                        Toast.makeText(this, getString(R.string.microsoft_cape_load_failed, message), Toast.LENGTH_LONG).show();
                    }
                }
            });
        }, "DroidBridgeActiveCapePreview");
        thread.start();
    }

    @Nullable
    private MicrosoftCapeService.Profile getCachedMicrosoftCapeProfile(@NonNull String token) {
        MicrosoftCapeService.Profile profile = cachedMicrosoftCapeProfile;
        String cachedToken = cachedMicrosoftCapeToken;
        long age = System.currentTimeMillis() - cachedMicrosoftCapeProfileAt;
        if (profile == null || cachedToken == null || !cachedToken.equals(token) || age < 0L || age > MICROSOFT_CAPE_PROFILE_CACHE_MS) {
            return null;
        }
        return profile;
    }

    private void cacheMicrosoftCapeProfile(
            @NonNull String token,
            @NonNull MicrosoftCapeService.Profile profile
    ) {
        cachedMicrosoftCapeToken = token;
        cachedMicrosoftCapeProfile = profile;
        cachedMicrosoftCapeProfileAt = System.currentTimeMillis();

        if (microsoftCapeSelectionOverrideSet && capeSelectionMatches(profile, microsoftCapeSelectionOverrideId)) {
            microsoftCapeSelectionOverrideSet = false;
            microsoftCapeSelectionOverrideId = null;
            microsoftCapeSelectionOverrideAt = 0L;
        }
    }

    private void rememberMicrosoftCapeSelection(@Nullable String capeId) {
        microsoftCapeSelectionOverrideSet = true;
        microsoftCapeSelectionOverrideId = isNullOrBlank(capeId) ? null : capeId;
        microsoftCapeSelectionOverrideAt = System.currentTimeMillis();

        // The cape list itself is still valid after a selection change. Keep it fresh so reopening
        // the picker does not immediately hit /minecraft/profile again. The active state is
        // represented by the short-lived override until Minecraft Services naturally catches up.
        cachedMicrosoftCapeProfileAt = System.currentTimeMillis();
    }

    @Nullable
    private String getEffectiveActiveCapeId(@NonNull MicrosoftCapeService.Profile profile) {
        if (microsoftCapeSelectionOverrideSet) {
            long age = System.currentTimeMillis() - microsoftCapeSelectionOverrideAt;
            if (age >= 0L && age <= MICROSOFT_CAPE_SELECTION_OVERRIDE_MS) {
                return microsoftCapeSelectionOverrideId;
            }
            microsoftCapeSelectionOverrideSet = false;
            microsoftCapeSelectionOverrideId = null;
            microsoftCapeSelectionOverrideAt = 0L;
        }

        MicrosoftCapeService.CapeEntry activeCape = profile.getActiveCape();
        return activeCape != null ? activeCape.id : null;
    }

    @Nullable
    private MicrosoftCapeService.CapeEntry getEffectiveActiveCape(@NonNull MicrosoftCapeService.Profile profile) {
        String activeCapeId = getEffectiveActiveCapeId(profile);
        if (isNullOrBlank(activeCapeId)) return null;
        for (MicrosoftCapeService.CapeEntry cape : profile.capes) {
            if (activeCapeId.equals(cape.id)) return cape;
        }
        return null;
    }

    @Nullable
    private AccountStore.Account getMicrosoftSkinTargetAccount(@Nullable AccountStore.Account activeAccount) {
        if (activeAccount != null && activeAccount.isMicrosoftAccount() && activeAccount.hasMinecraftSession()) {
            return activeAccount;
        }
        if (accountStore == null) return null;
        AccountStore.Account remembered = accountStore.loadLastMicrosoftAccount();
        if (remembered != null && remembered.isMicrosoftAccount() && remembered.hasMinecraftSession()) {
            return remembered;
        }
        return null;
    }

    private void showChangeMicrosoftCapeDialog() {
        AccountStore.Account account = getMicrosoftSkinTargetAccount(accountStore != null ? accountStore.load() : null);
        if (account == null) {
            Toast.makeText(this, R.string.microsoft_cape_requires_account, Toast.LENGTH_LONG).show();
            return;
        }

        if (microsoftCapeProfileAuthenticationStale) {
            refreshStaleMicrosoftAccountForCape(account);
            return;
        }

        MicrosoftCapeService.Profile cachedProfile = getCachedMicrosoftCapeProfile(account.minecraftAccessToken);
        if (cachedProfile != null) {
            showMicrosoftCapePickerDialog(account, cachedProfile);
            return;
        }

        binding.buttonChangeMicrosoftCape.setEnabled(false);
        binding.buttonChangeMicrosoftCape.setText(R.string.microsoft_cape_loading_button);
        Toast.makeText(this, R.string.microsoft_cape_loading, Toast.LENGTH_SHORT).show();

        Thread thread = new Thread(() -> {
            try {
                MicrosoftCapeService.Profile profile = MicrosoftCapeService.fetchProfile(account.minecraftAccessToken);
                microsoftCapeProfileAuthenticationStale = false;
                cacheMicrosoftCapeProfile(account.minecraftAccessToken, profile);
                runOnUiThread(() -> {
                    binding.buttonChangeMicrosoftCape.setText(R.string.microsoft_cape_change_button);
                    updateChangeMicrosoftCapeButtonState(account);
                    showMicrosoftCapePickerDialog(account, profile);
                });
            } catch (Throwable throwable) {
                if (isMicrosoftCapeAuthenticationFailure(throwable)) {
                    microsoftCapeProfileAuthenticationStale = true;
                    runOnUiThread(() -> refreshStaleMicrosoftAccountForCape(account));
                    return;
                }

                runOnUiThread(() -> {
                    binding.buttonChangeMicrosoftCape.setText(R.string.microsoft_cape_change_button);
                    updateChangeMicrosoftCapeButtonState(account);
                    String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.toString();
                    Toast.makeText(this, getString(R.string.microsoft_cape_load_failed, message), Toast.LENGTH_LONG).show();
                });
            }
        }, "Microsoft Cape List");
        thread.start();
    }

    private void refreshStaleMicrosoftAccountForCape(@NonNull AccountStore.Account account) {
        if (authManager == null || accountStore == null || pendingMicrosoftCapeOpenAfterAuthRefresh) {
            binding.buttonChangeMicrosoftCape.setText(R.string.microsoft_cape_change_button);
            updateChangeMicrosoftCapeButtonState(account);
            return;
        }

        pendingMicrosoftCapeOpenAfterAuthRefresh = true;
        microsoftCapeActiveAccountBeforeRefresh = accountStore.load();

        // MicrosoftAuthManagerPersonal refreshes the account currently stored in AccountStore.
        // If the launcher is currently using an offline profile, temporarily make the remembered
        // Microsoft account the refresh target. The listener restores the offline profile afterward.
        AccountStore.Account active = microsoftCapeActiveAccountBeforeRefresh;
        if (active == null || !active.isMicrosoftAccount() || !MicrosoftAccountRegistry.isSameAccount(active, account)) {
            accountStore.saveMicrosoftAccount(account);
        }

        microsoftCapeProfileAuthenticationStale = false;
        cachedMicrosoftCapeProfile = null;
        cachedMicrosoftCapeToken = null;
        cachedMicrosoftCapeProfileAt = 0L;
        binding.buttonChangeMicrosoftCape.setEnabled(false);
        binding.buttonChangeMicrosoftCape.setText(R.string.microsoft_cape_loading_button);
        LauncherDiagnosticLog.i("LauncherSettings", "Minecraft cape session is stale; refreshing Microsoft account before reopening cape picker");

        try {
            authManager.refreshMicrosoftAccount();
        } catch (Throwable throwable) {
            pendingMicrosoftCapeOpenAfterAuthRefresh = false;
            AccountStore.Account uiAccount = restoreActiveAccountAfterCapeAuthRefresh(null);
            binding.buttonChangeMicrosoftCape.setText(R.string.microsoft_cape_change_button);
            updateChangeMicrosoftCapeButtonState(uiAccount);
            String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.toString();
            Toast.makeText(this, getString(R.string.microsoft_cape_load_failed, message), Toast.LENGTH_LONG).show();
        }
    }

    @Nullable
    private AccountStore.Account restoreActiveAccountAfterCapeAuthRefresh(@Nullable AccountStore.Account refreshedAccount) {
        AccountStore.Account restore = microsoftCapeActiveAccountBeforeRefresh;
        microsoftCapeActiveAccountBeforeRefresh = null;

        if (accountStore != null && restore != null && restore.isOfflineAccount()) {
            try {
                accountStore.saveOfflineAccount(restore);
                return restore;
            } catch (Throwable throwable) {
                Logging.e("LauncherSettings", "Unable to restore offline account after Microsoft cape refresh", throwable);
            }
        }

        if (refreshedAccount != null) return refreshedAccount;
        return accountStore != null ? accountStore.load() : null;
    }

    private static boolean isMicrosoftCapeAuthenticationFailure(@Nullable Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("HTTP 401") || message.contains("HTTP 403"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void showMicrosoftCapePickerDialog(
            @NonNull AccountStore.Account account,
            @NonNull MicrosoftCapeService.Profile profile
    ) {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(COLOR_DIALOG_BG);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setScrollbarFadingEnabled(false);

        LinearLayout root = createStyledDialogRoot(
                scrollView,
                getString(R.string.microsoft_cape_dialog_title),
                getString(R.string.microsoft_cape_dialog_summary, account.getBestDisplayName())
        );

        LinearLayout card = addStyledDialogCard(root);
        addStyledDialogCardTitle(card, getString(R.string.microsoft_cape_owned_title));
        if (profile.capes.isEmpty()) {
            addStyledDialogInfoText(card, getString(R.string.microsoft_cape_empty));
        }

        String effectiveActiveCapeId = getEffectiveActiveCapeId(profile);
        MicrosoftCapeService.CapeEntry effectiveActiveCape = getEffectiveActiveCape(profile);
        final String[] selectedCapeId = new String[]{effectiveActiveCapeId};
        final String[] selectedCapeUrl = new String[]{effectiveActiveCape != null ? effectiveActiveCape.url : null};
        final ArrayList<RadioButton> radios = new ArrayList<>();
        final ArrayList<LinearLayout> rows = new ArrayList<>();

        addCapeOptionRow(
                card,
                radios,
                rows,
                getString(R.string.microsoft_cape_none_title),
                getString(R.string.microsoft_cape_none_summary),
                null,
                selectedCapeId[0] == null,
                () -> {
                    selectedCapeId[0] = null;
                    selectedCapeUrl[0] = null;
                }
        );

        for (MicrosoftCapeService.CapeEntry cape : profile.capes) {
            boolean isEffectiveActive = cape.id.equals(effectiveActiveCapeId);
            String subtitle = isEffectiveActive
                    ? getString(R.string.microsoft_cape_active_badge)
                    : getString(R.string.microsoft_cape_inactive_badge);
            addCapeOptionRow(
                    card,
                    radios,
                    rows,
                    cape.getDisplayName(),
                    subtitle,
                    cape.url,
                    isEffectiveActive,
                    () -> {
                        selectedCapeId[0] = cape.id;
                        selectedCapeUrl[0] = cape.url;
                    }
            );
        }

        final AlertDialog[] dialogRef = new AlertDialog[1];
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.microsoft_cape_apply, null)
                .create();
        dialogRef[0] = dialog;

        final String initialCapeId = effectiveActiveCapeId;
        dialog.setOnShowListener(dialogInterface -> {
            styleLauncherDialogChrome(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(R.string.microsoft_cape_saving_button);
                applyMicrosoftCapeSelection(account, selectedCapeId[0], selectedCapeUrl[0], initialCapeId, () -> {
                    AlertDialog activeDialog = dialogRef[0];
                    if (activeDialog != null) activeDialog.dismiss();
                }, () -> {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(R.string.microsoft_cape_apply);
                });
            });
        });

        dialog.show();
    }

    private void addCapeOptionRow(
            @NonNull LinearLayout parent,
            @NonNull ArrayList<RadioButton> radios,
            @NonNull ArrayList<LinearLayout> rows,
            @NonNull String titleText,
            @NonNull String subtitleText,
            @Nullable String capeUrl,
            boolean checked,
            @NonNull Runnable onSelected
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(66));
        row.setPadding(dp(12), dp(8), dp(10), dp(8));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(roundedDrawable(
                checked ? COLOR_CARD_BG_PRESSED : COLOR_CARD_BG,
                checked ? COLOR_ACCENT_MUTED : COLOR_CARD_STROKE,
                14
        ));

        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setBackground(roundedDrawable(COLOR_DIALOG_BG, COLOR_CARD_STROKE, 10));
        preview.setPadding(dp(4), dp(4), dp(4), dp(4));
        if (isNullOrBlank(capeUrl)) {
            preview.setImageResource(R.drawable.ic_no_cape_24);
        } else {
            loadCapePreviewInto(preview, capeUrl);
        }
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(dp(42), dp(54));
        previewParams.rightMargin = dp(12);
        row.addView(preview, previewParams);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(COLOR_TEXT_PRIMARY);
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        labels.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView subtitle = new TextView(this);
        subtitle.setText(subtitleText);
        subtitle.setTextColor(COLOR_TEXT_SECONDARY);
        subtitle.setTextSize(12f);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        subtitleParams.topMargin = dp(2);
        labels.addView(subtitle, subtitleParams);

        RadioButton radio = new RadioButton(this);
        radio.setChecked(checked);
        radio.setClickable(false);
        radio.setFocusable(false);
        styleDialogRadioButton(radio);
        row.addView(radio, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        radios.add(radio);
        rows.add(row);
        row.setOnClickListener(view -> {
            onSelected.run();
            for (RadioButton item : radios) item.setChecked(false);
            for (LinearLayout itemRow : rows) {
                itemRow.setBackground(roundedDrawable(COLOR_CARD_BG, COLOR_CARD_STROKE, 14));
            }
            radio.setChecked(true);
            row.setBackground(roundedDrawable(COLOR_CARD_BG_PRESSED, COLOR_ACCENT_MUTED, 14));
        });

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowParams.bottomMargin = dp(8);
        parent.addView(row, rowParams);
    }

    private void loadCapePreviewInto(@NonNull ImageView target, @NonNull String url) {
        target.setImageResource(R.drawable.ic_cape_placeholder_24);
        target.setScaleType(ImageView.ScaleType.FIT_CENTER);
        final String safeUrl = normalizeMinecraftTextureUrl(url);
        if (isNullOrBlank(safeUrl)) return;

        Thread thread = new Thread(() -> {
            Bitmap bitmap = null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(safeUrl).openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(15000);
                connection.setUseCaches(true);
                if (connection.getResponseCode() >= 200 && connection.getResponseCode() < 300) {
                    try (InputStream input = connection.getInputStream()) {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                        bitmap = BitmapFactory.decodeStream(input, null, options);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }

            final Bitmap fullBitmap = bitmap;
            final Bitmap capePanel = createCapePanelBitmap(fullBitmap);
            runOnUiThread(() -> {
                if (capePanel != null && !capePanel.isRecycled()) {
                    target.setImageBitmap(capePanel);
                } else if (fullBitmap != null && !fullBitmap.isRecycled()) {
                    target.setImageBitmap(fullBitmap);
                }
            });
        }, "Microsoft Cape Preview");
        thread.start();
    }

    @Nullable
    private static String normalizeMinecraftTextureUrl(@Nullable String rawUrl) {
        if (rawUrl == null) return null;
        String value = rawUrl.trim();
        if (value.isEmpty()) return null;
        if (value.startsWith("//")) return "https:" + value;
        if (value.startsWith("http://textures.minecraft.net/")) {
            return "https://textures.minecraft.net/" + value.substring("http://textures.minecraft.net/".length());
        }
        return value;
    }

    @Nullable
    private static Bitmap createCapePanelBitmap(@Nullable Bitmap source) {
        if (source == null || source.isRecycled()) return null;
        int width = source.getWidth();
        int height = source.getHeight();
        if (width < 22 || height < 17) return null;

        // Minecraft cape textures are normally 64x32.  The visible back panel is
        // the 10x16 section starting at 1,1 in the classic cape layout.  Crop it
        // for the list so users see the actual cape instead of a tiny full texture.
        int sx = Math.max(0, Math.min(width - 1, Math.round(width * (1f / 64f))));
        int sy = Math.max(0, Math.min(height - 1, Math.round(height * (1f / 32f))));
        int sw = Math.max(1, Math.min(width - sx, Math.round(width * (10f / 64f))));
        int sh = Math.max(1, Math.min(height - sy, Math.round(height * (16f / 32f))));

        try {
            return Bitmap.createBitmap(source, sx, sy, sw, sh);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void applyMicrosoftCapeSelection(
            @NonNull AccountStore.Account account,
            @Nullable String capeId,
            @Nullable String capeUrl,
            @Nullable String initialCapeId,
            @NonNull Runnable onSuccess,
            @NonNull Runnable onFailure
    ) {
        binding.buttonChangeMicrosoftCape.setEnabled(false);
        binding.buttonChangeMicrosoftCape.setText(R.string.microsoft_cape_saving_button);

        Thread thread = new Thread(() -> {
            try {
                // The PUT/DELETE response is authoritative for the selection request. Do not
                // immediately poll /minecraft/profile afterward: Minecraft Services rate-limits
                // that endpoint (HTTP 429), and the profile can lag behind a successful mutation.
                if (!sameCapeSelection(capeId, initialCapeId)) {
                    if (isNullOrBlank(capeId)) {
                        MicrosoftCapeService.hideActiveCape(account.minecraftAccessToken);
                    } else {
                        MicrosoftCapeService.activateCape(account.minecraftAccessToken, capeId);
                    }
                }

                rememberMicrosoftCapeSelection(capeId);
                LauncherDiagnosticLog.i("LauncherSettings", "Microsoft cape update succeeded target="
                        + (isNullOrBlank(capeId) ? "NONE" : capeId));

                runOnUiThread(() -> {
                    if (binding == null) return;
                    binding.buttonChangeMicrosoftCape.setText(R.string.microsoft_cape_change_button);
                    updateChangeMicrosoftCapeButtonState(account);

                    // Invalidate any preview GET that started before this mutation, then update the
                    // preview from the cape data already loaded in the picker. This makes the UI
                    // reflect the successful change immediately without another network request.
                    playerModelCapeLoadGeneration++;
                    if (binding.modelPlayerPreview != null) {
                        binding.modelPlayerPreview.setCapeUrl(isNullOrBlank(capeId) ? null : capeUrl);
                    }

                    Toast.makeText(this, R.string.microsoft_cape_save_success, Toast.LENGTH_LONG).show();
                    onSuccess.run();
                });
            } catch (Throwable throwable) {
                String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.toString();
                LauncherDiagnosticLog.e("LauncherSettings", "Microsoft cape update failed target="
                        + (isNullOrBlank(capeId) ? "NONE" : capeId)
                        + " error=" + message, throwable);
                runOnUiThread(() -> {
                    if (binding == null) return;
                    binding.buttonChangeMicrosoftCape.setText(R.string.microsoft_cape_change_button);
                    updateChangeMicrosoftCapeButtonState(account);
                    Toast.makeText(this, getString(R.string.microsoft_cape_save_failed, message), Toast.LENGTH_LONG).show();
                    onFailure.run();
                });
            }
        }, "Microsoft Cape Save");
        thread.start();
    }

    private static boolean capeSelectionMatches(
            @Nullable MicrosoftCapeService.Profile profile,
            @Nullable String capeId
    ) {
        if (profile == null) return false;
        MicrosoftCapeService.CapeEntry activeCape = profile.getActiveCape();
        if (isNullOrBlank(capeId)) return activeCape == null;
        return activeCape != null && capeId.equals(activeCape.id);
    }

    private static boolean sameCapeSelection(@Nullable String firstCapeId, @Nullable String secondCapeId) {
        String first = isNullOrBlank(firstCapeId) ? null : firstCapeId;
        String second = isNullOrBlank(secondCapeId) ? null : secondCapeId;
        return first == null ? second == null : first.equals(second);
    }

    private void showChangeMicrosoftSkinDialog() {
        AccountStore.Account account = getMicrosoftSkinTargetAccount(accountStore != null ? accountStore.load() : null);
        if (account == null) {
            Toast.makeText(this, R.string.microsoft_skin_requires_account, Toast.LENGTH_LONG).show();
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.microsoft_skin_change_title)
                .setMessage(getString(R.string.microsoft_skin_change_message, account.getBestDisplayName()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.microsoft_skin_pick, (dialog, which) -> openMicrosoftSkinPicker())
                .show();
    }

    private void openMicrosoftSkinPicker() {
        if (microsoftSkinPickerLauncher == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/png");
        microsoftSkinPickerLauncher.launch(intent);
    }

    private void prepareMicrosoftSkinUpload(@NonNull Uri uri) {
        File tempFile = new File(getCacheDir(), "pending_microsoft_account_skin.png");
        try {
            copyUriToFile(uri, tempFile);
            if (!CustomSkinStore.isSkinValid(tempFile)) {
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
                Toast.makeText(this, R.string.microsoft_skin_invalid, Toast.LENGTH_LONG).show();
                return;
            }

            SkinModelType detectedModel = CustomSkinStore.getSkinModel(tempFile);
            showConfirmMicrosoftSkinUploadDialog(tempFile, detectedModel);
        } catch (Throwable throwable) {
            Toast.makeText(this, throwable.getMessage() != null ? throwable.getMessage() : throwable.toString(), Toast.LENGTH_LONG).show();
        }
    }

    private void showConfirmMicrosoftSkinUploadDialog(@NonNull File skinFile, @NonNull SkinModelType detectedModel) {
        final SkinModelType[] selectedModel = new SkinModelType[]{
                detectedModel == SkinModelType.SLIM ? SkinModelType.SLIM : SkinModelType.CLASSIC
        };
        String[] choices = new String[]{
                getString(R.string.microsoft_skin_variant_classic),
                getString(R.string.microsoft_skin_variant_slim)
        };
        int checked = selectedModel[0] == SkinModelType.SLIM ? 1 : 0;

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.microsoft_skin_upload_title)
                .setMessage(R.string.microsoft_skin_upload_message)
                .setSingleChoiceItems(choices, checked, (dialog, which) ->
                        selectedModel[0] = which == 1 ? SkinModelType.SLIM : SkinModelType.CLASSIC)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.microsoft_skin_upload, (dialog, which) ->
                        uploadMicrosoftAccountSkin(skinFile, selectedModel[0]))
                .show();
    }

    private void uploadMicrosoftAccountSkin(@NonNull File skinFile, @NonNull SkinModelType model) {
        AccountStore.Account account = getMicrosoftSkinTargetAccount(accountStore != null ? accountStore.load() : null);
        if (account == null) {
            Toast.makeText(this, R.string.microsoft_skin_requires_account, Toast.LENGTH_LONG).show();
            return;
        }

        binding.buttonChangeMicrosoftSkin.setEnabled(false);
        binding.buttonRefreshMicrosoftSkin.setEnabled(false);
        binding.textSkinStatus.setText(R.string.microsoft_skin_uploading);

        Thread thread = new Thread(() -> {
            try {
                MicrosoftSkinUploader.uploadSkin(account.minecraftAccessToken, skinFile, model);
                runOnUiThread(() -> {
                    Toast.makeText(this, R.string.microsoft_skin_upload_success, Toast.LENGTH_LONG).show();
                    refreshMicrosoftAccountAndSkin(false);
                });
            } catch (Throwable throwable) {
                runOnUiThread(() -> {
                    binding.buttonChangeMicrosoftSkin.setEnabled(true);
                    binding.buttonRefreshMicrosoftSkin.setEnabled(true);
                    String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.toString();
                    binding.textSkinStatus.setText(getString(R.string.microsoft_skin_upload_failed, message));
                    Toast.makeText(this, getString(R.string.microsoft_skin_upload_failed, message), Toast.LENGTH_LONG).show();
                });
            }
        }, "Microsoft Skin Upload");
        thread.start();
    }

    private void copyUriToFile(@NonNull Uri sourceUri, @NonNull File destination) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(sourceUri);
             FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null) throw new IllegalStateException("Could not open selected file.");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private void refreshMicrosoftAccountAndSkin(boolean showToast) {
        if (authManager == null) return;
        binding.buttonRefreshMicrosoftSkin.setEnabled(false);
        binding.textSkinStatus.setText(R.string.microsoft_skin_refreshing);
        if (showToast) {
            Toast.makeText(this, R.string.microsoft_skin_refreshing, Toast.LENGTH_SHORT).show();
        }
        authManager.refreshMicrosoftAccount();
    }

    private static String sanitizeOfflineName(@Nullable String raw) {
        if (raw == null) return "Player";
        String cleaned = raw.trim().replaceAll("[^A-Za-z0-9_]", "");
        if (cleaned.length() > 16) cleaned = cleaned.substring(0, 16);
        return cleaned.length() == 0 ? "Player" : cleaned;
    }

    private static boolean isValidOfflineName(@Nullable String value) {
        return value != null && value.matches("[A-Za-z0-9_]{3,16}");
    }


    private interface StyledDialogItemCallback {
        void onItemSelected(int position);
    }

    private void showStyledSingleChoiceDialog(
            @NonNull String title,
            @NonNull String summary,
            @NonNull List<String> items,
            int selectedIndex,
            @NonNull StyledDialogItemCallback callback
    ) {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(COLOR_DIALOG_BG);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setScrollbarFadingEnabled(false);

        LinearLayout root = createStyledDialogRoot(scrollView, title, summary);
        LinearLayout card = addStyledDialogCard(root);

        int safeIndex = items.isEmpty()
                ? -1
                : Math.max(0, Math.min(selectedIndex, items.size() - 1));

        final AlertDialog[] dialogRef = new AlertDialog[1];

        for (int i = 0; i < items.size(); i++) {
            final int position = i;
            addStyledSingleChoiceRow(
                    card,
                    items.get(i),
                    position == safeIndex,
                    () -> {
                        callback.onItemSelected(position);
                        AlertDialog dialog = dialogRef[0];
                        if (dialog != null) dialog.dismiss();
                    }
            );
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        dialogRef[0] = dialog;
        dialog.setOnShowListener(dialogInterface -> styleLauncherDialogChrome(dialog));
        dialog.show();
    }

    private void addStyledSingleChoiceRow(
            @NonNull LinearLayout parent,
            @NonNull String rawLabel,
            boolean checked,
            @NonNull Runnable onClick
    ) {
        boolean plugin = rawLabel.contains("•") && rawLabel.toLowerCase().contains("plugin");
        String label = rawLabel
                .replace("  •  Plugin", "")
                .replace(" • Plugin", "")
                .trim();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(58));
        row.setPadding(dp(14), dp(8), dp(10), dp(8));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(roundedDrawable(
                checked ? COLOR_CARD_BG_PRESSED : COLOR_CARD_BG,
                checked ? COLOR_ACCENT_MUTED : COLOR_CARD_BG,
                14
        ));

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(COLOR_TEXT_PRIMARY);
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(false);
        textColumn.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        if (plugin) {
            TextView badge = new TextView(this);
            badge.setText("Plugin renderer");
            badge.setTextColor(COLOR_ACCENT);
            badge.setTextSize(12f);
            badge.setPadding(0, dp(2), 0, 0);
            textColumn.addView(badge, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
        }

        row.addView(textColumn, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));

        RadioButton radio = new RadioButton(this);
        radio.setChecked(checked);
        radio.setClickable(false);
        radio.setFocusable(false);
        radio.setFocusableInTouchMode(false);
        styleDialogRadioButton(radio);

        row.addView(radio, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        row.setOnClickListener(view -> onClick.run());

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(6));
        parent.addView(row, params);
    }

    @NonNull
    private LinearLayout createStyledDialogRoot(
            @NonNull ScrollView scrollView,
            @NonNull String titleText,
            @NonNull String summaryText
    ) {
        LinearLayout root = createStyledDialogRoot(titleText, summaryText);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));
        return root;
    }

    @NonNull
    private LinearLayout createStyledDialogRoot(
            @NonNull String titleText,
            @NonNull String summaryText
    ) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_DIALOG_BG);
        int padding = dp(18);
        root.setPadding(padding, padding, padding, dp(8));

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(COLOR_TEXT_PRIMARY);
        title.setPadding(dp(2), 0, dp(2), dp(6));
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView summary = new TextView(this);
        summary.setText(summaryText);
        summary.setTextSize(14f);
        summary.setTextColor(COLOR_TEXT_SECONDARY);
        summary.setPadding(dp(2), 0, dp(2), dp(12));
        root.addView(summary, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        return root;
    }

    @NonNull
    private LinearLayout addStyledDialogCard(@NonNull LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(14);
        card.setPadding(padding, padding, padding, padding);
        card.setBackground(roundedDrawable(COLOR_CARD_BG, COLOR_CARD_STROKE, 18));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(12));
        root.addView(card, params);
        return card;
    }

    private void addStyledDialogCardTitle(@NonNull LinearLayout root, @NonNull String title) {
        TextView header = new TextView(this);
        header.setText(title);
        header.setTextSize(18f);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(COLOR_TEXT_PRIMARY);
        header.setPadding(0, 0, 0, dp(8));
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
    }

    private void addStyledDialogInfoText(@NonNull LinearLayout root, @NonNull String text) {
        TextView info = new TextView(this);
        info.setText(text);
        info.setTextSize(13f);
        info.setTextColor(COLOR_TEXT_SECONDARY);
        info.setPadding(0, 0, 0, dp(8));
        root.addView(info, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
    }





    private void styleDialogRadioButton(@NonNull RadioButton option) {
        option.setTextColor(COLOR_TEXT_SECONDARY);
        option.setTextSize(15f);
        option.setPadding(0, dp(2), 0, dp(2));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int[][] states = new int[][]{
                    new int[]{android.R.attr.state_checked},
                    new int[]{-android.R.attr.state_checked}
            };
            int[] colors = new int[]{COLOR_ACCENT, COLOR_TEXT_MUTED};
            option.setButtonTintList(new ColorStateList(states, colors));
        }
    }

    private void styleDialogSeekBar(@NonNull SeekBar seekBar) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        seekBar.setThumbTintList(ColorStateList.valueOf(COLOR_ACCENT));
        seekBar.setProgressTintList(ColorStateList.valueOf(COLOR_ACCENT));
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(COLOR_CARD_STROKE));
    }

    private void styleDialogOutlinedButton(@NonNull MaterialButton button) {
        button.setTextColor(COLOR_ACCENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.setBackgroundTintList(ColorStateList.valueOf(COLOR_CARD_BG));
        }
        button.setStrokeColor(ColorStateList.valueOf(COLOR_ACCENT_MUTED));
        button.setRippleColor(ColorStateList.valueOf(COLOR_CARD_BG_PRESSED));
    }

    private void styleLauncherDialogChrome(@NonNull AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(roundedDrawable(COLOR_DIALOG_BG, COLOR_DIALOG_BG, 22));
            window.setDimAmount(DIALOG_DIM_NORMAL);
        }

        tintLauncherDialogButton(dialog, AlertDialog.BUTTON_POSITIVE);
        tintLauncherDialogButton(dialog, AlertDialog.BUTTON_NEGATIVE);
        tintLauncherDialogButton(dialog, AlertDialog.BUTTON_NEUTRAL);
    }

    private void tintLauncherDialogButton(@NonNull AlertDialog dialog, int whichButton) {
        TextView button = dialog.getButton(whichButton);
        if (button != null) {
            button.setTextColor(COLOR_ACCENT);
        }
    }

    @NonNull
    private GradientDrawable roundedDrawable(int fillColor, int strokeColor, int cornerDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fillColor);
        bg.setCornerRadius(dp(cornerDp));
        bg.setStroke(Math.max(1, dp(1)), strokeColor);
        return bg;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static boolean isNullOrBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }
}

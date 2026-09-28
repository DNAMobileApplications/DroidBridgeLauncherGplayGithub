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

package ca.dnamobile.droidbridgelauncher.ui.instance;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.R;
import ca.dnamobile.droidbridgelauncher.ui.LauncherDialogStyle;
import ca.dnamobile.droidbridgelauncher.launcher.DistantHorizonsGcMitigation;
import ca.dnamobile.droidbridgelauncher.launcher.InstanceLaunchSettings;
import ca.dnamobile.droidbridgelauncher.renderer.RendererInterface;
import ca.dnamobile.droidbridgelauncher.renderer.Renderers;
import ca.dnamobile.droidbridgelauncher.settings.GameResolutionSettings;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.settings.MemoryAllocationUtils;
import ca.dnamobile.droidbridgelauncher.shortcuts.InstanceShortcutHelper;

/**
 * CreateInstanceDialog-style editor for per-instance launch overrides.
 *
 * The action buttons live inside the custom Material card rather than using the
 * default AlertDialog button bar. That keeps Save/Cancel/Reset visible in
 * landscape and prevents showFullscreenSafeDialog-style OnShowListener
 * replacement from breaking the button callbacks.
 */
public final class PerInstanceSettingsDialog {

    private final Activity activity;
    private final String settingsKey;
    private final ArrayList<String> aliasKeys;
    @Nullable
    private final InstanceShortcutHelper.ShortcutData shortcutData;
    @Nullable
    private final Runnable onDismiss;

    private AlertDialog dialog;

    private final ArrayList<RendererInterface> renderers = new ArrayList<>();
    private final ArrayList<String> rendererLabels = new ArrayList<>();
    private int selectedRendererIndex;
    private int selectedRuntimeIndex;
    private int selectedGraphicsApiModeIndex;
    private int selectedVulkanCompatibilityModeIndex;
    private int selectedResolutionModeIndex;
    private int selectedLaunchTargetModeIndex;

    private MaterialAutoCompleteTextView rendererDropdown;
    private MaterialAutoCompleteTextView runtimeDropdown;
    private MaterialAutoCompleteTextView graphicsApiDropdown;
    private MaterialAutoCompleteTextView vulkanCompatibilityDropdown;
    private MaterialAutoCompleteTextView resolutionDropdown;
    private MaterialAutoCompleteTextView launchTargetDropdown;
    private TextInputEditText resolutionWidthInput;
    private TextInputEditText resolutionHeightInput;
    private TextInputLayout resolutionWidthLayout;
    private TextInputLayout resolutionHeightLayout;
    private TextInputEditText jvmArgsInput;
    private SwitchMaterial distantHorizonsZgcSwitch;
    private SwitchMaterial customRamSwitch;
    private TextInputEditText ramInput;
    private TextInputLayout ramInputLayout;
    private MaterialButton ramUnlockButton;
    private Slider ramSlider;
    private TextView ramSummary;
    private TextView ramRangeText;

    private int minRamMb;
    private int maxRamMb;
    private int sliderMaxRamMb;
    private int ramStepMb;
    private int selectedRamMb;
    private boolean updatingRamText;

    public PerInstanceSettingsDialog(
            @NonNull Activity activity,
            @NonNull String settingsKey,
            @Nullable List<String> aliasKeys,
            @Nullable Runnable onDismiss
    ) {
        this(activity, settingsKey, aliasKeys, null, onDismiss);
    }

    public PerInstanceSettingsDialog(
            @NonNull Activity activity,
            @NonNull String settingsKey,
            @Nullable List<String> aliasKeys,
            @Nullable InstanceShortcutHelper.ShortcutData shortcutData,
            @Nullable Runnable onDismiss
    ) {
        this.activity = activity;
        this.settingsKey = settingsKey;
        this.aliasKeys = new ArrayList<>();
        this.shortcutData = shortcutData;
        addAlias(settingsKey);
        if (aliasKeys != null) {
            for (String key : aliasKeys) addAlias(key);
        }
        this.onDismiss = onDismiss;
    }

    public void show() {
        LauncherDialogStyle.syncTheme(activity);
        InstanceLaunchSettings.Settings settings = InstanceLaunchSettings.load(activity, settingsKey);
        prepareRendererChoices(settings);
        prepareRamBounds(settings);

        FrameLayout outer = new FrameLayout(activity);
        outer.setPadding(dp(4), dp(4), dp(4), dp(4));

        MaterialCardView card = new MaterialCardView(activity);
        card.setRadius(dp(26));
        card.setCardBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);
        card.setStrokeColor(LauncherDialogStyle.COLOR_CARD_STROKE);
        card.setStrokeWidth(dp(1));
        card.setCardElevation(0f);
        card.setUseCompatPadding(false);
        card.setPreventCornerOverlap(true);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(18), dp(22), dp(14));
        card.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        content.addView(createHeader(), matchWrap());

        ScrollView formScroll = new ScrollView(activity);
        formScroll.setFillViewport(false);
        formScroll.setClipToPadding(false);
        formScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        formScroll.setPadding(0, 0, 0, dp(8));

        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(0, 0, 0, dp(8));
        formScroll.addView(form, matchWrap());

        form.addView(createLaunchTargetSection(settings), matchWrap());
        form.addView(createRendererSection(settings), matchWrap());
        form.addView(createGraphicsApiSection(settings), matchWrap());
        form.addView(createVulkanCompatibilitySection(settings), matchWrap());
        form.addView(createResolutionSection(settings), matchWrap());
        form.addView(createRuntimeSection(settings), matchWrap());
        form.addView(createDistantHorizonsGcSection(settings), matchWrap());
        form.addView(createJvmArgsSection(settings), matchWrap());
        form.addView(createRamSection(settings), matchWrap());
        if (shortcutData != null) {
            form.addView(createShortcutSection(), matchWrap());
        }

        // Critical: the form takes remaining space only. The action row stays outside
        // the ScrollView, so Save/Cancel/Reset can never be pushed below the screen.
        content.addView(formScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        content.addView(createActionsSection(), matchWrap());

        outer.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        dialog = new MaterialAlertDialogBuilder(activity)
                .setView(outer)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
                int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
                int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
                int targetWidth = Math.min(screenWidth - dp(24), dp(760));
                int availableHeight = screenHeight - dp(24);
                if (availableHeight < dp(260)) {
                    availableHeight = screenHeight - dp(8);
                }
                availableHeight = Math.max(1, availableHeight);
                int targetHeight = Math.min(availableHeight, dp(640));
                window.setLayout(targetWidth, targetHeight);
            }
        });
        dialog.setOnDismissListener(dialogInterface -> {
            if (onDismiss != null) onDismiss.run();
        });
        dialog.show();
    }

    private View createHeader() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(16));

        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.mipmap.ic_launcher);
        icon.setPadding(dp(12), dp(12), dp(12), dp(12));

        GradientDrawable iconBackground = new GradientDrawable();
        iconBackground.setCornerRadius(dp(18));
        iconBackground.setColor(LauncherDialogStyle.COLOR_CARD_BG);
        icon.setBackground(iconBackground);
        row.addView(icon, new LinearLayout.LayoutParams(dp(72), dp(72)));

        LinearLayout textColumn = new LinearLayout(activity);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setPadding(dp(16), 0, 0, 0);

        TextView title = new TextView(activity);
        title.setText("Per Instance Settings");
        title.setTextSize(22);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        textColumn.addView(title, matchWrap());

        TextView subtitle = new TextView(activity);
        subtitle.setText("Grid play action, renderer, Graphics API, Vulkan compatibility, Java runtime, garbage collection, JVM arguments, and RAM for this instance only.");
        subtitle.setTextSize(13);
        subtitle.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        subtitle.setPadding(0, dp(4), 0, 0);
        textColumn.addView(subtitle, matchWrap());

        row.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private View createLaunchTargetSection(@NonNull InstanceLaunchSettings.Settings settings) {
        LinearLayout section = createSection();
        addSectionTitle(
                section,
                "Grid play action",
                "Choose what this instance's grid play icon opens. Use global keeps the launcher-wide Regular, World Save, or Server choice."
        );

        String[] labels = InstanceLaunchSettings.getLaunchTargetModeLabels();
        launchTargetDropdown = new MaterialAutoCompleteTextView(activity);
        launchTargetDropdown.setInputType(InputType.TYPE_NULL);
        launchTargetDropdown.setSingleLine(true);
        launchTargetDropdown.setOnClickListener(view -> launchTargetDropdown.showDropDown());
        launchTargetDropdown.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, labels));
        selectedLaunchTargetModeIndex = InstanceLaunchSettings.launchTargetModeIndex(settings.launchTargetMode);
        if (selectedLaunchTargetModeIndex < 0 || selectedLaunchTargetModeIndex >= labels.length) {
            selectedLaunchTargetModeIndex = 0;
        }
        launchTargetDropdown.setText(labels[selectedLaunchTargetModeIndex], false);
        launchTargetDropdown.setOnItemClickListener((parent, view, position, id) -> {
            selectedLaunchTargetModeIndex = Math.max(0, Math.min(position, labels.length - 1));
            launchTargetDropdown.setText(labels[selectedLaunchTargetModeIndex], false);
        });

        TextInputLayout layout = createDropdownLayout("Grid play action");
        layout.addView(launchTargetDropdown, matchWrap());
        section.addView(layout, matchWrap());
        return section;
    }

    private View createRendererSection(@NonNull InstanceLaunchSettings.Settings settings) {
        LinearLayout section = createSection();
        addSectionTitle(section, "Renderer", "Use a specific renderer for this instance, or keep the launcher default.");

        rendererDropdown = new MaterialAutoCompleteTextView(activity);
        rendererDropdown.setInputType(InputType.TYPE_NULL);
        rendererDropdown.setSingleLine(true);
        rendererDropdown.setOnClickListener(view -> rendererDropdown.showDropDown());
        rendererDropdown.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, rendererLabels));
        selectedRendererIndex = resolveRendererSelectionIndex(settings.rendererIdentifier);
        rendererDropdown.setText(rendererLabels.get(selectedRendererIndex), false);
        rendererDropdown.setOnItemClickListener((parent, view, position, id) -> {
            // AutoCompleteTextView positions belong to the currently filtered adapter.
            // Resolve the actual clicked label back to the stable full renderer list so
            // selecting MobileGlues/Krypton/Default cannot silently save another item.
            Object clickedItem = parent.getItemAtPosition(position);
            String clickedLabel = clickedItem != null ? clickedItem.toString() : "";
            int resolvedIndex = rendererLabels.indexOf(clickedLabel);
            selectedRendererIndex = resolvedIndex >= 0 ? resolvedIndex : 0;
            rendererDropdown.setText(rendererLabels.get(selectedRendererIndex), false);
        });

        TextInputLayout layout = createDropdownLayout("Renderer");
        layout.addView(rendererDropdown, matchWrap());
        section.addView(layout, matchWrap());
        return section;
    }

    private View createGraphicsApiSection(@NonNull InstanceLaunchSettings.Settings settings) {
        LinearLayout section = createSection();
        addSectionTitle(
                section,
                "Minecraft 26.2+ Graphics API",
                "Default uses OpenGL. Use System Vulkan Driver forces Minecraft Vulkan through Android's system Vulkan driver. Use OpenGL forces OpenGL. VulkanMod 26.2 keeps its own compatibility path."
        );

        String[] graphicsApiLabels = InstanceLaunchSettings.getGraphicsApiModeLabels();
        graphicsApiDropdown = new MaterialAutoCompleteTextView(activity);
        graphicsApiDropdown.setInputType(InputType.TYPE_NULL);
        graphicsApiDropdown.setSingleLine(true);
        graphicsApiDropdown.setOnClickListener(view -> graphicsApiDropdown.showDropDown());
        graphicsApiDropdown.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, graphicsApiLabels));
        selectedGraphicsApiModeIndex = InstanceLaunchSettings.graphicsApiModeIndex(settings.graphicsApiMode);
        if (selectedGraphicsApiModeIndex < 0 || selectedGraphicsApiModeIndex >= graphicsApiLabels.length) {
            selectedGraphicsApiModeIndex = 0;
        }
        graphicsApiDropdown.setText(graphicsApiLabels[selectedGraphicsApiModeIndex], false);
        graphicsApiDropdown.setOnItemClickListener((parent, view, position, id) -> {
            // Do not use the filtered adapter position as the Graphics API index.
            // After a previous System Vulkan selection that can turn a click on
            // "Default" into index 0 ("Use launcher default"), resurrecting stale
            // global Vulkan state. Resolve the clicked label to its stable mode first.
            Object clickedItem = parent.getItemAtPosition(position);
            String clickedLabel = clickedItem != null ? clickedItem.toString() : "";
            String clickedMode = InstanceLaunchSettings.graphicsApiModeForLabel(clickedLabel);
            selectedGraphicsApiModeIndex = InstanceLaunchSettings.graphicsApiModeIndex(clickedMode);
            if (selectedGraphicsApiModeIndex < 0
                    || selectedGraphicsApiModeIndex >= graphicsApiLabels.length) {
                selectedGraphicsApiModeIndex = 0;
            }
            graphicsApiDropdown.setText(graphicsApiLabels[selectedGraphicsApiModeIndex], false);
        });

        TextInputLayout layout = createDropdownLayout("Graphics API");
        layout.addView(graphicsApiDropdown, matchWrap());
        section.addView(layout, matchWrap());
        return section;
    }

    private View createVulkanCompatibilitySection(
            @NonNull InstanceLaunchSettings.Settings settings
    ) {
        LinearLayout section = createSection();
        addSectionTitle(section, "System Vulkan compatibility", "");

        String[] labels = InstanceLaunchSettings.getVulkanCompatibilityModeLabels();
        vulkanCompatibilityDropdown = new MaterialAutoCompleteTextView(activity);
        vulkanCompatibilityDropdown.setInputType(InputType.TYPE_NULL);
        vulkanCompatibilityDropdown.setSingleLine(true);
        vulkanCompatibilityDropdown.setOnClickListener(
                view -> vulkanCompatibilityDropdown.showDropDown());
        vulkanCompatibilityDropdown.setAdapter(new ArrayAdapter<>(
                activity, android.R.layout.simple_list_item_1, labels));
        selectedVulkanCompatibilityModeIndex =
                InstanceLaunchSettings.vulkanCompatibilityModeIndex(
                        settings.vulkanCompatibilityMode);
        if (selectedVulkanCompatibilityModeIndex < 0
                || selectedVulkanCompatibilityModeIndex >= labels.length) {
            selectedVulkanCompatibilityModeIndex = 0;
        }
        vulkanCompatibilityDropdown.setText(
                labels[selectedVulkanCompatibilityModeIndex], false);
        vulkanCompatibilityDropdown.setOnItemClickListener((parent, view, position, id) -> {
            // AutoCompleteTextView positions are positions in the *filtered* adapter.
            // Resolve the clicked label back to the stable full mode list instead of
            // treating the filtered position as a compatibility-mode index.
            Object clickedItem = parent.getItemAtPosition(position);
            String clickedLabel = clickedItem != null ? clickedItem.toString() : "";
            String clickedMode = InstanceLaunchSettings.vulkanCompatibilityModeForLabel(clickedLabel);
            selectedVulkanCompatibilityModeIndex =
                    InstanceLaunchSettings.vulkanCompatibilityModeIndex(clickedMode);
            if (selectedVulkanCompatibilityModeIndex < 0
                    || selectedVulkanCompatibilityModeIndex >= labels.length) {
                selectedVulkanCompatibilityModeIndex = 0;
            }
            vulkanCompatibilityDropdown.setText(
                    labels[selectedVulkanCompatibilityModeIndex], false);
        });

        TextInputLayout layout = createDropdownLayout("Vulkan compatibility");
        layout.addView(vulkanCompatibilityDropdown, matchWrap());
        section.addView(layout, matchWrap());
        return section;
    }

    private View createResolutionSection(@NonNull InstanceLaunchSettings.Settings settings) {
        LinearLayout section = createSection();
        addSectionTitle(
                section,
                "Experimental game resolution",
                "Overrides the launcher-wide framebuffer setting only for this instance. "
                        + "MCSX works best at 1280 × 960. Unsupported values can cause black screens, "
                        + "stretched UI, renderer crashes, or poor performance. Use at your own risk."
        );

        String[] labels = InstanceLaunchSettings.getResolutionModeLabels();
        resolutionDropdown = new MaterialAutoCompleteTextView(activity);
        resolutionDropdown.setInputType(InputType.TYPE_NULL);
        resolutionDropdown.setSingleLine(true);
        resolutionDropdown.setOnClickListener(view -> resolutionDropdown.showDropDown());
        resolutionDropdown.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, labels));
        selectedResolutionModeIndex = InstanceLaunchSettings.resolutionModeIndex(settings.resolutionMode);
        if (selectedResolutionModeIndex < 0 || selectedResolutionModeIndex >= labels.length) {
            selectedResolutionModeIndex = 0;
        }
        resolutionDropdown.setText(labels[selectedResolutionModeIndex], false);
        resolutionDropdown.setOnItemClickListener((parent, view, position, id) -> {
            selectedResolutionModeIndex = Math.max(0, Math.min(position, labels.length - 1));
            resolutionDropdown.setText(labels[selectedResolutionModeIndex], false);
            updateResolutionCustomInputVisibility();
        });

        TextInputLayout dropdownLayout = createDropdownLayout("Resolution");
        dropdownLayout.addView(resolutionDropdown, matchWrap());
        section.addView(dropdownLayout, matchWrap());

        GameResolutionSettings.Profile globalProfile = GameResolutionSettings.getProfile(activity);
        int initialWidth = settings.hasResolutionOverride()
                ? settings.resolutionCustomWidth
                : globalProfile.customWidth;
        int initialHeight = settings.hasResolutionOverride()
                ? settings.resolutionCustomHeight
                : globalProfile.customHeight;

        resolutionWidthInput = new TextInputEditText(activity);
        resolutionWidthInput.setSingleLine(true);
        resolutionWidthInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        resolutionWidthInput.setSelectAllOnFocus(true);
        resolutionWidthInput.setText(String.valueOf(initialWidth));
        resolutionWidthLayout = createOutlinedLayout("Custom width");
        resolutionWidthLayout.addView(resolutionWidthInput, matchWrap());
        LinearLayout.LayoutParams widthParams = matchWrap();
        widthParams.setMargins(0, dp(8), 0, 0);
        section.addView(resolutionWidthLayout, widthParams);

        resolutionHeightInput = new TextInputEditText(activity);
        resolutionHeightInput.setSingleLine(true);
        resolutionHeightInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        resolutionHeightInput.setSelectAllOnFocus(true);
        resolutionHeightInput.setText(String.valueOf(initialHeight));
        resolutionHeightLayout = createOutlinedLayout("Custom height");
        resolutionHeightLayout.addView(resolutionHeightInput, matchWrap());
        LinearLayout.LayoutParams heightParams = matchWrap();
        heightParams.setMargins(0, dp(8), 0, 0);
        section.addView(resolutionHeightLayout, heightParams);

        TextView restartNotice = new TextView(activity);
        restartNotice.setText("Applied the next time this instance starts. Resolution Scale is applied afterwards, except MCSX which stays fixed at 1280 × 960.");
        restartNotice.setTextSize(12);
        restartNotice.setPadding(0, dp(6), 0, 0);
        section.addView(restartNotice, matchWrap());

        updateResolutionCustomInputVisibility();
        return section;
    }

    private View createRuntimeSection(@NonNull InstanceLaunchSettings.Settings settings) {
        LinearLayout section = createSection();
        addSectionTitle(section, "Java runtime", "Default automatically picks the runtime for the Minecraft version.");

        String[] runtimeLabels = InstanceLaunchSettings.getRuntimeDisplayLabels();
        runtimeDropdown = new MaterialAutoCompleteTextView(activity);
        runtimeDropdown.setInputType(InputType.TYPE_NULL);
        runtimeDropdown.setSingleLine(true);
        runtimeDropdown.setOnClickListener(view -> runtimeDropdown.showDropDown());
        runtimeDropdown.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, runtimeLabels));
        selectedRuntimeIndex = InstanceLaunchSettings.runtimeIndexForName(settings.runtimeName);
        if (selectedRuntimeIndex < 0 || selectedRuntimeIndex >= runtimeLabels.length) selectedRuntimeIndex = 0;
        runtimeDropdown.setText(runtimeLabels[selectedRuntimeIndex], false);
        runtimeDropdown.setOnItemClickListener((parent, view, position, id) -> {
            selectedRuntimeIndex = Math.max(0, Math.min(position, runtimeLabels.length - 1));
            runtimeDropdown.setText(runtimeLabels[selectedRuntimeIndex], false);
        });

        TextInputLayout layout = createDropdownLayout("Java runtime");
        layout.addView(runtimeDropdown, matchWrap());
        section.addView(layout, matchWrap());
        return section;
    }

    private View createDistantHorizonsGcSection(@NonNull InstanceLaunchSettings.Settings settings) {
        LinearLayout section = createSection();
        DistantHorizonsGcMitigation.DeviceRecommendation recommendation =
                DistantHorizonsGcMitigation.getDeviceRecommendation(activity);

        addSectionTitle(
                section,
                "Distant Horizons garbage collection",
                "Choose the garbage collector used when Distant Horizons is detected. "
                        + "ZGC can reduce long pauses on capable devices; G1GC is safer on lower-end devices."
        );

        String savedMode = InstanceLaunchSettings.sanitizeGcMode(settings.gcMode);
        String initialMode = settings.hasGcOverride()
                ? savedMode
                : recommendation.recommendedGcMode;

        distantHorizonsZgcSwitch = new SwitchMaterial(activity);
        distantHorizonsZgcSwitch.setText("Use ZGC (off uses G1GC)");
        distantHorizonsZgcSwitch.setChecked(InstanceLaunchSettings.GC_MODE_ZGC.equals(initialMode));
        section.addView(distantHorizonsZgcSwitch, matchWrap());

        TextView recommendationText = new TextView(activity);
        recommendationText.setText(
                "Automatic recommendation for this device: "
                        + DistantHorizonsGcMitigation.displayGcMode(recommendation.recommendedGcMode)
                        + ". " + recommendation.summary
                        + (settings.hasGcOverride()
                        ? " This instance currently uses its saved override."
                        : " This recommendation is being used until you save this dialog.")
        );
        recommendationText.setTextSize(12);
        recommendationText.setPadding(0, dp(4), 0, 0);
        section.addView(recommendationText, matchWrap());
        return section;
    }

    private View createJvmArgsSection(@NonNull InstanceLaunchSettings.Settings settings) {
        LinearLayout section = createSection();
        addSectionTitle(section, "Custom JVM arguments", "Optional extra JVM flags. Memory and classpath flags are ignored by the launcher.");

        jvmArgsInput = new TextInputEditText(activity);
        jvmArgsInput.setSingleLine(false);
        jvmArgsInput.setMinLines(2);
        jvmArgsInput.setMaxLines(4);
        jvmArgsInput.setGravity(Gravity.TOP | Gravity.START);
        jvmArgsInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        jvmArgsInput.setHint("-Dexample=true");
        jvmArgsInput.setText(settings.customJvmArgs == null ? "" : settings.customJvmArgs);

        TextInputLayout layout = createOutlinedLayout("Custom JVM arguments");
        layout.addView(jvmArgsInput, matchWrap());
        section.addView(layout, matchWrap());
        return section;
    }

    private View createRamSection(@NonNull InstanceLaunchSettings.Settings settings) {
        LinearLayout section = createSection();
        addSectionTitle(section, "RAM", "Leave disabled to use the launcher-wide RAM value.");

        customRamSwitch = new SwitchMaterial(activity);
        customRamSwitch.setText("Use custom RAM for this instance");
        customRamSwitch.setChecked(settings.hasRamOverride());
        section.addView(customRamSwitch, matchWrap());

        ramUnlockButton = new MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        ramUnlockButton.setAllCaps(false);
        ramUnlockButton.setIconResource(R.drawable.ic_lock_24);
        ramUnlockButton.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        ramUnlockButton.setIconPadding(dp(8));
        ramUnlockButton.setOnClickListener(view -> showRamUnlockDialog());
        LinearLayout.LayoutParams unlockParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        unlockParams.setMargins(0, dp(8), 0, 0);
        section.addView(ramUnlockButton, unlockParams);

        ramInput = new TextInputEditText(activity);
        ramInput.setSingleLine(true);
        ramInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        ramInput.setSelectAllOnFocus(true);
        ramInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) applyTypedRamValue();
        });
        ramInput.setOnEditorActionListener((view, actionId, event) -> {
            applyTypedRamValue();
            return false;
        });

        ramInputLayout = createOutlinedLayout("RAM in MB");
        ramInputLayout.addView(ramInput, matchWrap());
        LinearLayout.LayoutParams inputParams = matchWrap();
        inputParams.setMargins(0, dp(8), 0, 0);
        section.addView(ramInputLayout, inputParams);

        ramSlider = new Slider(activity);
        ramSlider.setValueFrom(minRamMb);
        ramSlider.setValueTo(sliderMaxRamMb);
        ramSlider.setStepSize(ramStepMb);
        ramSlider.setValue(clampRamToSliderRange(selectedRamMb));
        ramSlider.setLabelFormatter(value -> Math.round(value) + " MB");
        ramSlider.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                view.getParent().requestDisallowInterceptTouchEvent(true);
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                view.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });
        ramSlider.addOnChangeListener((slider, value, fromUser) -> {
            selectedRamMb = Math.round(value);
            updateRamViews();
        });
        LinearLayout.LayoutParams sliderParams = matchWrap();
        sliderParams.setMargins(0, dp(8), 0, 0);
        section.addView(ramSlider, sliderParams);

        ramSummary = new TextView(activity);
        ramSummary.setTextSize(13);
        ramSummary.setPadding(0, dp(4), 0, 0);
        section.addView(ramSummary, matchWrap());

        ramRangeText = new TextView(activity);
        ramRangeText.setTextSize(12);
        ramRangeText.setPadding(0, dp(4), 0, 0);
        section.addView(ramRangeText, matchWrap());

        customRamSwitch.setOnCheckedChangeListener((buttonView, checked) -> updateRamViews());
        updateRamUnlockButton();
        updateRamViews();
        return section;
    }

    private View createShortcutSection() {
        LinearLayout section = createSection();
        addSectionTitle(
                section,
                "Home screen shortcut",
                "Add a launcher shortcut for this instance. It stores only the instance id, so it always uses the current per-instance settings and the current instance icon."
        );

        MaterialButton shortcutButton = new MaterialButton(activity);
        shortcutButton.setText("Add Home Screen Shortcut");
        shortcutButton.setAllCaps(false);
        shortcutButton.setIconResource(R.drawable.ic_open_in_new_24);
        shortcutButton.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        shortcutButton.setIconPadding(dp(8));
        shortcutButton.setOnClickListener(view -> {
            if (shortcutData == null) {
                Toast.makeText(activity, "Instance shortcut data is unavailable.", Toast.LENGTH_LONG).show();
                return;
            }
            InstanceShortcutHelper.requestPinShortcut(activity, shortcutData);
        });

        LinearLayout.LayoutParams buttonParams = matchWrap();
        buttonParams.setMargins(0, dp(4), 0, 0);
        section.addView(shortcutButton, buttonParams);
        return section;
    }

    private View createActionsSection() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        row.setPadding(0, dp(12), 0, 0);

        MaterialButton reset = new MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        reset.setText("Reset");
        reset.setAllCaps(false);
        reset.setOnClickListener(view -> {
            clearAllAliases();
            Toast.makeText(activity, "Per-instance settings reset.", Toast.LENGTH_SHORT).show();
            dismiss();
        });
        row.addView(reset, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        MaterialButton cancel = new MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        cancel.setText(android.R.string.cancel);
        cancel.setAllCaps(false);
        cancel.setOnClickListener(view -> dismiss());
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cancelParams.setMargins(dp(8), 0, 0, 0);
        row.addView(cancel, cancelParams);

        MaterialButton save = new MaterialButton(activity);
        save.setText("Save");
        save.setAllCaps(false);
        save.setOnClickListener(view -> saveAndDismiss());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        saveParams.setMargins(dp(8), 0, 0, 0);
        row.addView(save, saveParams);

        return row;
    }

    private void updateRamUnlockButton() {
        if (ramUnlockButton == null) return;
        ramUnlockButton.setText(MemoryAllocationUtils.isRamUnlocked(activity)
                ? activity.getString(R.string.memory_unlock_button_unlocked)
                : activity.getString(R.string.memory_unlock_button_locked));
    }

    private void showRamUnlockDialog() {
        LauncherDialogStyle.syncTheme(activity);
        boolean unlocked = MemoryAllocationUtils.isRamUnlocked(activity);
        int titleRes = unlocked
                ? R.string.memory_relock_dialog_title
                : R.string.memory_unlock_dialog_title;
        int messageRes = unlocked
                ? R.string.memory_relock_dialog_message
                : R.string.memory_unlock_dialog_message;
        int positiveRes = unlocked
                ? R.string.memory_relock_dialog_positive
                : R.string.memory_unlock_dialog_positive;

        ScrollView scrollView = new ScrollView(activity);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setScrollbarFadingEnabled(false);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(LauncherDialogStyle.COLOR_DIALOG_BG);
        int padding = dp(18);
        root.setPadding(padding, padding, padding, dp(8));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(activity);
        title.setText(activity.getString(titleRes));
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        title.setPadding(dp(2), 0, dp(2), dp(6));
        root.addView(title, matchWrap());

        TextView summary = new TextView(activity);
        summary.setText(activity.getString(messageRes));
        summary.setTextSize(14f);
        summary.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        summary.setPadding(dp(2), 0, dp(2), dp(12));
        root.addView(summary, matchWrap());

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(roundedDialogDrawable(LauncherDialogStyle.COLOR_CARD_BG, LauncherDialogStyle.COLOR_CARD_STROKE, 18));
        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.setMargins(0, 0, 0, dp(12));
        root.addView(card, cardParams);

        TextView cardTitle = new TextView(activity);
        cardTitle.setText(unlocked ? "Available RAM limit" : "Maximum RAM access");
        cardTitle.setTextSize(18f);
        cardTitle.setTypeface(Typeface.DEFAULT_BOLD);
        cardTitle.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        cardTitle.setPadding(0, 0, 0, dp(8));
        card.addView(cardTitle, matchWrap());

        TextView cardInfo = new TextView(activity);
        cardInfo.setText(unlocked
                ? "This will return the global and per-instance RAM sliders to Android's currently available RAM limit. The saved default is not reset."
                : "This lets the global and per-instance RAM sliders use the device-reported installed RAM instead of only the currently available RAM. Use this only when you understand the risk of starving Android or the GPU driver.");
        cardInfo.setTextSize(13f);
        cardInfo.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        cardInfo.setPadding(0, 0, 0, dp(8));
        card.addView(cardInfo, matchWrap());

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(8), 0, 0);

        MaterialButton cancel = new MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        cancel.setText(android.R.string.cancel);
        cancel.setAllCaps(false);
        cancel.setTextColor(LauncherDialogStyle.COLOR_ACCENT);
        cancel.setBackground(roundedDialogDrawable(LauncherDialogStyle.COLOR_CARD_BG, LauncherDialogStyle.COLOR_ACCENT_MUTED, 14));
        actions.addView(cancel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        MaterialButton confirm = new MaterialButton(activity);
        confirm.setText(positiveRes);
        confirm.setAllCaps(false);
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        confirmParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(confirm, confirmParams);
        card.addView(actions, matchWrap());

        AlertDialog ramDialog = new MaterialAlertDialogBuilder(activity)
                .setView(scrollView)
                .create();

        cancel.setOnClickListener(view -> ramDialog.dismiss());
        confirm.setOnClickListener(view -> {
            MemoryAllocationUtils.setRamUnlocked(activity, !unlocked);
            MemoryAllocationUtils.resolveAllocatedMemoryMb(activity);
            refreshRamBoundsFromUnlockState();
            updateRamUnlockButton();
            updateRamViews();
            ramDialog.dismiss();
        });

        ramDialog.setOnShowListener(dialogInterface -> {
            Window window = ramDialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(roundedDialogDrawable(LauncherDialogStyle.COLOR_DIALOG_BG, LauncherDialogStyle.COLOR_DIALOG_BG, 22));
                window.setDimAmount(LauncherDialogStyle.DIALOG_DIM_NORMAL);
            }
        });
        ramDialog.show();
    }

    private void refreshRamBoundsFromUnlockState() {
        maxRamMb = MemoryAllocationUtils.getMaxAllocatableMemoryMb(activity);
        minRamMb = MemoryAllocationUtils.getMinimumMemoryMb(maxRamMb);
        ramStepMb = Math.max(1, MemoryAllocationUtils.RAM_STEP_MB);
        int stepCount = Math.max(1, (maxRamMb - minRamMb) / ramStepMb);
        sliderMaxRamMb = minRamMb + (stepCount * ramStepMb);
        selectedRamMb = clampRamToSliderRange(selectedRamMb);

        if (ramSlider != null) {
            ramSlider.setValueFrom(minRamMb);
            ramSlider.setValueTo(sliderMaxRamMb);
            ramSlider.setStepSize(ramStepMb);
            ramSlider.setValue(clampRamToSliderRange(selectedRamMb));
        }
    }

    private void prepareRendererChoices(@NonNull InstanceLaunchSettings.Settings settings) {
        Renderers.reload(activity);
        renderers.clear();
        renderers.addAll(Renderers.getCompatibleRenderers(activity));
        rendererLabels.clear();
        rendererLabels.add("Default launcher renderer");
        for (RendererInterface renderer : renderers) {
            rendererLabels.add(renderer.getRendererName() + (renderer.isExternalPlugin() ? "  •  Plugin" : ""));
        }
        if (rendererLabels.isEmpty()) rendererLabels.add("Default launcher renderer");
    }

    private void prepareRamBounds(@NonNull InstanceLaunchSettings.Settings settings) {
        maxRamMb = MemoryAllocationUtils.getMaxAllocatableMemoryMb(activity);
        minRamMb = MemoryAllocationUtils.getMinimumMemoryMb(maxRamMb);
        ramStepMb = Math.max(1, MemoryAllocationUtils.RAM_STEP_MB);
        int stepCount = Math.max(1, (maxRamMb - minRamMb) / ramStepMb);
        sliderMaxRamMb = minRamMb + (stepCount * ramStepMb);
        selectedRamMb = settings.hasRamOverride()
                ? settings.ramMb
                : MemoryAllocationUtils.resolveAllocatedMemoryMb(activity);
        selectedRamMb = clampRamToSliderRange(selectedRamMb);
    }

    private void saveAndDismiss() {
        InstanceLaunchSettings.Settings settings = InstanceLaunchSettings.load(activity, settingsKey);

        if (selectedRendererIndex > 0 && selectedRendererIndex - 1 < renderers.size()) {
            settings.rendererIdentifier = renderers.get(selectedRendererIndex - 1).getUniqueIdentifier();
        } else {
            settings.rendererIdentifier = InstanceLaunchSettings.RENDERER_DEFAULT;
        }

        settings.runtimeName = InstanceLaunchSettings.runtimeNameForIndex(selectedRuntimeIndex);
        settings.graphicsApiMode = InstanceLaunchSettings.graphicsApiModeForIndex(selectedGraphicsApiModeIndex);
        settings.vulkanCompatibilityMode =
                InstanceLaunchSettings.vulkanCompatibilityModeForIndex(
                        selectedVulkanCompatibilityModeIndex);
        settings.resolutionMode = InstanceLaunchSettings.resolutionModeForIndex(selectedResolutionModeIndex);
        settings.launchTargetMode = InstanceLaunchSettings.launchTargetModeForIndex(selectedLaunchTargetModeIndex);
        if (GameResolutionSettings.MODE_CUSTOM.equals(settings.resolutionMode)) {
            Integer width = readResolutionDimension(resolutionWidthInput);
            Integer height = readResolutionDimension(resolutionHeightInput);
            if (width == null || height == null) {
                Toast.makeText(
                        activity,
                        "Custom resolution values must be between "
                                + GameResolutionSettings.MIN_CUSTOM_DIMENSION + " and "
                                + GameResolutionSettings.MAX_CUSTOM_DIMENSION + ".",
                        Toast.LENGTH_LONG
                ).show();
                return;
            }
            settings.resolutionCustomWidth = width;
            settings.resolutionCustomHeight = height;
        } else {
            Integer width = readResolutionDimension(resolutionWidthInput);
            Integer height = readResolutionDimension(resolutionHeightInput);
            if (width != null) settings.resolutionCustomWidth = width;
            if (height != null) settings.resolutionCustomHeight = height;
        }
        settings.gcMode = distantHorizonsZgcSwitch != null && distantHorizonsZgcSwitch.isChecked()
                ? InstanceLaunchSettings.GC_MODE_ZGC
                : InstanceLaunchSettings.GC_MODE_G1GC;
        settings.customJvmArgs = jvmArgsInput != null && jvmArgsInput.getText() != null
                ? jvmArgsInput.getText().toString().trim()
                : "";

        boolean customRam = customRamSwitch != null && customRamSwitch.isChecked();
        if (customRam) {
            applyTypedRamValue();
            settings.ramMb = clampRamToSliderRange(selectedRamMb);
        } else {
            settings.ramMb = InstanceLaunchSettings.RAM_DEFAULT;
        }

        saveAllAliases(settings);
        synchronizeExplicitGraphicsApiSelection(settings.graphicsApiMode);
        Toast.makeText(activity, "Per-instance settings saved.", Toast.LENGTH_SHORT).show();
        dismiss();
    }

    /**
     * Keep the launcher-wide graphics mode in sync with an explicit per-instance
     * OpenGL/Vulkan choice. This prevents older global state from disagreeing with
     * the dropdown and also makes subsystems that initialize before LaunchGame see
     * the same API selection. "Use launcher default" and "Default" do not change
     * the global switches.
     */
    private void synchronizeExplicitGraphicsApiSelection(@Nullable String graphicsApiMode) {
        String mode = graphicsApiMode == null
                ? InstanceLaunchSettings.GRAPHICS_API_INHERIT
                : graphicsApiMode.trim().toLowerCase(Locale.ROOT);
        if (InstanceLaunchSettings.GRAPHICS_API_VULKAN.equals(mode)) {
            LauncherPreferences.setSystemVulkanMode(activity, true);
        } else if (InstanceLaunchSettings.GRAPHICS_API_OPENGL.equals(mode)) {
            LauncherPreferences.setUseOpenGlForMinecraft26Plus(activity, true);
        }
    }

    private void saveAllAliases(@NonNull InstanceLaunchSettings.Settings settings) {
        for (String key : aliasKeys) {
            InstanceLaunchSettings.save(activity, key, settings);
        }
    }

    private void clearAllAliases() {
        for (String key : aliasKeys) {
            InstanceLaunchSettings.clear(activity, key);
        }
    }

    private void dismiss() {
        if (dialog != null) dialog.dismiss();
    }

    private void updateResolutionCustomInputVisibility() {
        boolean custom = selectedResolutionModeIndex == 5;
        int visibility = custom ? View.VISIBLE : View.GONE;
        if (resolutionWidthLayout != null) resolutionWidthLayout.setVisibility(visibility);
        if (resolutionHeightLayout != null) resolutionHeightLayout.setVisibility(visibility);
    }

    @Nullable
    private Integer readResolutionDimension(@Nullable TextInputEditText input) {
        if (input == null || input.getText() == null) return null;
        try {
            int value = Integer.parseInt(input.getText().toString().trim());
            if (value < GameResolutionSettings.MIN_CUSTOM_DIMENSION
                    || value > GameResolutionSettings.MAX_CUSTOM_DIMENSION) {
                return null;
            }
            return value;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void applyTypedRamValue() {
        if (ramInput == null || ramInput.getText() == null) return;
        String raw = ramInput.getText().toString().trim();
        if (raw.isEmpty()) {
            selectedRamMb = MemoryAllocationUtils.resolveAllocatedMemoryMb(activity);
        } else {
            try {
                selectedRamMb = Integer.parseInt(raw);
            } catch (Throwable ignored) {
                selectedRamMb = MemoryAllocationUtils.resolveAllocatedMemoryMb(activity);
            }
        }
        selectedRamMb = clampRamToSliderRange(selectedRamMb);
        if (ramSlider != null && Math.round(ramSlider.getValue()) != selectedRamMb) {
            ramSlider.setValue(selectedRamMb);
        }
        updateRamViews();
    }

    private void updateRamViews() {
        boolean custom = customRamSwitch != null && customRamSwitch.isChecked();
        int globalMb = MemoryAllocationUtils.resolveAllocatedMemoryMb(activity);

        if (ramInput != null) {
            ramInput.setEnabled(custom);
            ramInput.setAlpha(custom ? 1f : 0.55f);
        }
        if (ramInputLayout != null) {
            ramInputLayout.setEnabled(custom);
        }
        if (ramSlider != null) {
            ramSlider.setEnabled(custom);
            ramSlider.setAlpha(custom ? 1f : 0.45f);
        }

        int displayMb = custom ? selectedRamMb : globalMb;
        setRamInputText(displayMb);

        if (ramSummary != null) {
            if (custom) {
                ramSummary.setText("Custom RAM: " + selectedRamMb + " MB (" + formatGb(selectedRamMb) + " GB)");
            } else {
                ramSummary.setText("Using launcher default: " + globalMb + " MB (" + formatGb(globalMb) + " GB)");
            }
        }
        if (ramRangeText != null) {
            String mode = MemoryAllocationUtils.isRamUnlocked(activity) ? "Unlocked" : "Locked";
            ramRangeText.setText(mode + " range: " + minRamMb + " MB - " + sliderMaxRamMb + " MB · Step: " + ramStepMb + " MB");
        }
    }

    private void setRamInputText(int memoryMb) {
        if (ramInput == null || updatingRamText) return;
        String value = String.valueOf(memoryMb);
        String current = ramInput.getText() == null ? "" : ramInput.getText().toString();
        if (value.equals(current)) return;
        updatingRamText = true;
        ramInput.setText(value);
        ramInput.setSelection(ramInput.length());
        updatingRamText = false;
    }

    private int clampRamToSliderRange(int memoryMb) {
        int clamped = MemoryAllocationUtils.clampToAllowedRam(activity, memoryMb);
        clamped = Math.max(minRamMb, Math.min(sliderMaxRamMb, clamped));
        int offset = clamped - minRamMb;
        int roundedSteps = Math.round(offset / (float) ramStepMb);
        int rounded = minRamMb + roundedSteps * ramStepMb;
        return Math.max(minRamMb, Math.min(sliderMaxRamMb, rounded));
    }

    private int resolveRendererSelectionIndex(@Nullable String selectedRendererId) {
        if (selectedRendererId == null || selectedRendererId.trim().isEmpty()) return 0;
        for (int i = 0; i < renderers.size(); i++) {
            if (selectedRendererId.equals(renderers.get(i).getUniqueIdentifier())) {
                return i + 1;
            }
        }
        return 0;
    }

    private LinearLayout createSection() {
        LinearLayout section = new LinearLayout(activity);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, dp(8), 0, dp(10));
        return section;
    }

    private void addSectionTitle(@NonNull LinearLayout root, @NonNull String titleText, @NonNull String summaryText) {
        TextView title = new TextView(activity);
        title.setText(titleText);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setTextSize(15);
        title.setTextColor(LauncherDialogStyle.COLOR_TEXT_PRIMARY);
        root.addView(title, matchWrap());

        TextView summary = new TextView(activity);
        summary.setText(summaryText);
        summary.setTextSize(12);
        summary.setTextColor(LauncherDialogStyle.COLOR_TEXT_SECONDARY);
        summary.setPadding(0, dp(2), 0, dp(8));
        root.addView(summary, matchWrap());
    }

    private TextInputLayout createOutlinedLayout(@NonNull String hint) {
        TextInputLayout layout = new TextInputLayout(activity);
        layout.setHint(hint);
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        layout.setBoxCornerRadii(dp(14), dp(14), dp(14), dp(14));
        return layout;
    }

    private TextInputLayout createDropdownLayout(@NonNull String hint) {
        TextInputLayout layout = createOutlinedLayout(hint);
        layout.setEndIconMode(TextInputLayout.END_ICON_DROPDOWN_MENU);
        return layout;
    }

    private int calculateMaxFormHeight() {
        int height = activity.getResources().getDisplayMetrics().heightPixels;
        return Math.min(dp(440), Math.max(dp(250), height - dp(310)));
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    @NonNull
    private GradientDrawable roundedDialogDrawable(int fillColor, int strokeColor, int cornerDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fillColor);
        bg.setCornerRadius(dp(cornerDp));
        bg.setStroke(Math.max(1, dp(1)), strokeColor);
        return bg;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    @NonNull
    private String formatGb(int memoryMb) {
        return String.format(Locale.US, "%.1f", memoryMb / 1024f);
    }

    private void addAlias(@Nullable String rawKey) {
        if (rawKey == null) return;
        String key = InstanceLaunchSettings.resolveInstanceKey(rawKey, rawKey);
        if (key.trim().isEmpty()) return;
        if (!aliasKeys.contains(key)) aliasKeys.add(key);
    }
}

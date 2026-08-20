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
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
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
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import ca.dnamobile.droidbridgelauncher.R;
import ca.dnamobile.droidbridgelauncher.data.model.MinecraftVersion;
import ca.dnamobile.droidbridgelauncher.ui.version.CleanroomSupport;
import ca.dnamobile.droidbridgelauncher.ui.version.LoaderVersionResolver;

/**
 * Modrinth-style create instance dialog.
 *
 * - Loader selection uses chips.
 * - Minecraft version uses an exposed dropdown.
 * - Loader version is resolved dynamically from the selected loader + Minecraft version.
 * - Vanilla, Fabric, Forge, and NeoForge are visible/selectable in the UI.
 *
 * NeoForge installation is wired through NeoForgeInstaller and follows the same
 * protected shared-cache behavior as Forge/Fabric.
 */
public final class CreateInstanceDialog {
    public interface Listener {
        void onPickIcon(@NonNull CreateInstanceDialog dialog);

        void onCreateInstance(@NonNull Request request);
    }

    public static final class Request {
        public final String name;
        public final String loader;
        @Nullable
        public final String loaderVersion;
        public final String minecraftVersionId;
        public final String versionType;
        @Nullable
        public final Uri iconUri;
        public final boolean isolatedInstance;
        Request(
                @NonNull String name,
                @NonNull String loader,
                @Nullable String loaderVersion,
                @NonNull String minecraftVersionId,
                @NonNull String versionType,
                @Nullable Uri iconUri,
                boolean isolatedInstance
        ) {
            this.name = name;
            this.loader = loader;
            this.loaderVersion = loaderVersion;
            this.minecraftVersionId = minecraftVersionId;
            this.versionType = versionType;
            this.iconUri = iconUri;
            this.isolatedInstance = isolatedInstance;
        }
    }

    private static final String LOADER_VANILLA = "Vanilla";
    private static final String LOADER_FABRIC = "Fabric";
    private static final String LOADER_FORGE = "Forge";
    private static final String LOADER_NEOFORGE = "NeoForge";
    private static final String LOADER_CLEANROOM = "Cleanroom";
    private static final String OPTIFINE_DOWNLOADS_URL = "https://optifine.net/downloads";

    private static final String TYPE_RELEASE = "release";
    private static final String TYPE_SNAPSHOT = "snapshot";
    private static final String TYPE_BETA = "old_beta";
    private static final String TYPE_ALPHA = "old_alpha";

    private final Activity activity;
    private final ArrayList<MinecraftVersion> allVersions;
    private final Listener listener;

    private AlertDialog dialog;
    private ImageView iconPreview;
    private TextInputEditText nameInput;
    private MaterialAutoCompleteTextView minecraftVersionDropdown;
    private MaterialAutoCompleteTextView loaderVersionDropdown;
    private CheckBox isolatedInstanceCheck;
    private TextView versionHelp;
    private TextView loaderVersionStatus;
    private MaterialButton createButton;
    private Chip releaseTypeChip;

    private String selectedType = TYPE_RELEASE;
    private String selectedLoader = LOADER_VANILLA;
    @Nullable
    private Uri iconUri;
    private boolean userEditedName;
    private boolean programmaticNameChange;

    private int selectedMinecraftVersionIndex;
    private int selectedLoaderVersionIndex;
    private int loaderVersionRequestSerial;

    private final ArrayList<MinecraftVersion> filteredVersions = new ArrayList<>();
    private final ArrayList<LoaderVersionResolver.LoaderVersionOption> loaderVersionOptions = new ArrayList<>();
    private TextInputLayout nameInputLayout;
    private final Set<String> existingInstanceNameKeys = new HashSet<>();
    public CreateInstanceDialog(
            @NonNull Activity activity,
            @NonNull List<MinecraftVersion> allVersions,
            @NonNull Listener listener
    ) {
        this.activity = activity;
        this.allVersions = new ArrayList<>(allVersions);
        this.listener = listener;
    }

    @NonNull
    private String getSelectedLoaderName() {
        return selectedLoader;
    }

    @Nullable
    private String getSelectedLoaderVersion() {
        if (loaderVersionOptions.isEmpty()) return null;
        if (selectedLoaderVersionIndex < 0 || selectedLoaderVersionIndex >= loaderVersionOptions.size()) return null;

        String value = loaderVersionOptions.get(selectedLoaderVersionIndex).loaderVersion;
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }

    @NonNull
    private String buildDefaultInstanceName() {
        MinecraftVersion selected = getSelectedMinecraftVersion();
        String versionId = selected != null ? selected.getId() : "";
        if (versionId.trim().isEmpty()) {
            return getSelectedLoaderName();
        }
        return versionId + " (" + getSelectedLoaderName() + ")";
    }

    @Nullable
    private MinecraftVersion getSelectedMinecraftVersion() {
        if (filteredVersions.isEmpty()) return null;

        int position = selectedMinecraftVersionIndex;
        if (position < 0 || position >= filteredVersions.size()) position = 0;
        return filteredVersions.get(position);
    }

    private void updateDefaultNameIfAllowed() {
        if (nameInput == null || userEditedName) return;

        programmaticNameChange = true;
        nameInput.setText(buildDefaultInstanceName());
        nameInput.setSelection(nameInput.length());
        programmaticNameChange = false;
    }

    public void show() {
        ScrollView scrollView = new ScrollView(activity);
        scrollView.setFillViewport(false);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(4), dp(4), dp(4), dp(4));
        scrollView.addView(root);

        MaterialCardView card = new MaterialCardView(activity);
        card.setRadius(dp(26));
        card.setCardElevation(dp(8));
        card.setUseCompatPadding(true);
        card.setPreventCornerOverlap(true);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(20), dp(22), dp(18));
        card.addView(content, matchWrap());

        content.addView(createHeader());
        content.addView(createNameSection());
        content.addView(createLoaderSection());
        content.addView(createVersionSection());
        content.addView(createLoaderVersionSection());
        content.addView(createOptionsSection());
        content.addView(createActionsSection());

        root.addView(card, matchWrap());

        updateMinecraftVersionDropdown();

        dialog = new MaterialAlertDialogBuilder(activity)
                .setView(scrollView)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
            }
        });

        dialog.show();
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    public void setIconUri(@Nullable Uri uri) {
        iconUri = uri;
        if (iconPreview == null) return;

        if (uri == null) {
            iconPreview.setImageResource(R.mipmap.ic_launcher);
        } else {
            iconPreview.setImageURI(uri);
        }
    }

    private View createHeader() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(18));

        iconPreview = new ImageView(activity);
        iconPreview.setImageResource(R.mipmap.ic_launcher);
        iconPreview.setPadding(dp(12), dp(12), dp(12), dp(12));

        GradientDrawable iconBackground = new GradientDrawable();
        iconBackground.setCornerRadius(dp(18));
        iconBackground.setColor(0xFF20242B);
        iconPreview.setBackground(iconBackground);

        row.addView(iconPreview, new LinearLayout.LayoutParams(dp(78), dp(78)));

        LinearLayout textColumn = new LinearLayout(activity);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setPadding(dp(16), 0, 0, 0);

        TextView title = new TextView(activity);
        title.setText(R.string.create_instance_title);
        title.setTextSize(22);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        textColumn.addView(title, matchWrap());

        TextView subtitle = new TextView(activity);
        subtitle.setText(R.string.create_instance_dialog_subtitle);
        subtitle.setTextSize(13);
        subtitle.setPadding(0, dp(4), 0, 0);
        textColumn.addView(subtitle, matchWrap());

        LinearLayout iconButtons = new LinearLayout(activity);
        iconButtons.setOrientation(LinearLayout.HORIZONTAL);
        iconButtons.setPadding(0, dp(10), 0, 0);

        MaterialButton select = new MaterialButton(activity);
        select.setText(R.string.create_instance_select_icon);
        select.setOnClickListener(view -> listener.onPickIcon(this));
        iconButtons.addView(select, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        MaterialButton remove = new MaterialButton(activity);
        remove.setText(R.string.create_instance_remove_icon);
        remove.setEnabled(false);
        remove.setOnClickListener(view -> {
            setIconUri(null);
            remove.setEnabled(false);
        });
        select.setOnClickListener(view -> {
            listener.onPickIcon(this);
            remove.setEnabled(true);
        });

        LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        removeParams.setMargins(dp(8), 0, 0, 0);
        iconButtons.addView(remove, removeParams);

        textColumn.addView(iconButtons, matchWrap());

        row.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private View createNameSection() {
        LinearLayout section = createSection();

        nameInput = new TextInputEditText(activity);
        nameInput.setSingleLine(true);
        nameInput.setHint(activity.getString(R.string.create_instance_name_hint));
        nameInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!programmaticNameChange) {
                    userEditedName = true;
                }

                if (nameInputLayout != null) {
                    nameInputLayout.setError(null);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        nameInputLayout = createOutlinedLayout(activity.getString(R.string.create_instance_name_label));
        nameInputLayout.addView(nameInput, matchWrap());
        section.addView(nameInputLayout, matchWrap());

        return section;
    }

    private View createLoaderSection() {
        LinearLayout section = createSection();
        addSectionTitle(section, activity.getString(R.string.create_instance_loader_label), activity.getString(R.string.create_instance_loader_section_summary));
        section.addView(createLoaderChips(), matchWrap());
        return section;
    }

    private View createVersionSection() {
        LinearLayout section = createSection();

        addSectionTitle(section, activity.getString(R.string.create_instance_game_version_label), activity.getString(R.string.create_instance_game_version_summary));

        minecraftVersionDropdown = new MaterialAutoCompleteTextView(activity);
        minecraftVersionDropdown.setInputType(InputType.TYPE_NULL);
        minecraftVersionDropdown.setSingleLine(true);
        minecraftVersionDropdown.setOnClickListener(view -> minecraftVersionDropdown.showDropDown());
        minecraftVersionDropdown.setOnItemClickListener((parent, view, position, id) -> {
            selectedMinecraftVersionIndex = position;
            updateDefaultNameIfAllowed();
            updateLoaderVersionDropdown();
        });

        TextInputLayout versionLayout = createDropdownLayout(activity.getString(R.string.create_instance_game_version_label));
        versionLayout.addView(minecraftVersionDropdown, matchWrap());
        section.addView(versionLayout, matchWrap());

        TextView typeLabel = new TextView(activity);
        typeLabel.setText(R.string.create_instance_version_type_label);
        typeLabel.setTypeface(typeLabel.getTypeface(), Typeface.BOLD);
        typeLabel.setPadding(0, dp(12), 0, dp(4));
        section.addView(typeLabel, matchWrap());
        section.addView(createVersionTypeChips(), matchWrap());

        return section;
    }

    private View createLoaderVersionSection() {
        LinearLayout section = createSection();

        addSectionTitle(section, activity.getString(R.string.create_instance_loader_version_label), activity.getString(R.string.create_instance_loader_version_summary));

        loaderVersionDropdown = new MaterialAutoCompleteTextView(activity);
        loaderVersionDropdown.setInputType(InputType.TYPE_NULL);
        loaderVersionDropdown.setSingleLine(true);
        loaderVersionDropdown.setOnClickListener(view -> loaderVersionDropdown.showDropDown());
        loaderVersionDropdown.setOnItemClickListener((parent, view, position, id) -> {
            selectedLoaderVersionIndex = position;
            updateLoaderVersionStatus();
        });

        TextInputLayout loaderLayout = createDropdownLayout(activity.getString(R.string.create_instance_loader_version_label));
        loaderLayout.addView(loaderVersionDropdown, matchWrap());
        section.addView(loaderLayout, matchWrap());

        loaderVersionStatus = new TextView(activity);
        loaderVersionStatus.setTextSize(12);
        loaderVersionStatus.setPadding(0, dp(8), 0, 0);
        section.addView(loaderVersionStatus, matchWrap());

        return section;
    }

    private View createOptionsSection() {
        LinearLayout section = createSection();

        isolatedInstanceCheck = new CheckBox(activity);
        isolatedInstanceCheck.setText(R.string.create_instance_isolated_checkbox);
        // Checked = create an isolated instance under .minecraft/instances/<name>.
        // Unchecked = install/use the shared .minecraft path only.
        isolatedInstanceCheck.setChecked(true);
        section.addView(isolatedInstanceCheck, matchWrap());

        TextView isolatedHelp = new TextView(activity);
        isolatedHelp.setTextSize(12);
        isolatedHelp.setText(R.string.create_instance_isolated_summary);
        isolatedHelp.setPadding(0, dp(4), 0, 0);
        section.addView(isolatedHelp, matchWrap());

        versionHelp = new TextView(activity);
        versionHelp.setTextSize(12);
        versionHelp.setText(R.string.create_instance_per_instance_summary);
        versionHelp.setPadding(0, dp(12), 0, 0);
        section.addView(versionHelp, matchWrap());

        return section;
    }

    private View createActionsSection() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        row.setPadding(0, dp(10), 0, 0);

        MaterialButton cancel = new MaterialButton(activity);
        cancel.setText(android.R.string.cancel);
        cancel.setOnClickListener(view -> {
            if (dialog != null) dialog.dismiss();
        });
        row.addView(cancel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        createButton = new MaterialButton(activity);
        createButton.setText(R.string.create_instance_button);
        createButton.setOnClickListener(view -> submit());



        LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        createParams.setMargins(dp(10), 0, 0, 0);
        row.addView(createButton, createParams);

        return row;
    }

    private ChipGroup createLoaderChips() {
        ChipGroup group = new ChipGroup(activity);
        group.setSingleSelection(true);
        group.setSelectionRequired(true);

        group.addView(createLoaderChip(LOADER_VANILLA, true));
        group.addView(createLoaderChip(LOADER_FABRIC, false));
        group.addView(createLoaderChip(LOADER_FORGE, false));
        group.addView(createLoaderChip(LOADER_NEOFORGE, false));
        group.addView(createLoaderChip(LOADER_CLEANROOM, false));
        group.addView(createOptiFineWebsiteChip());

        return group;
    }

    private Chip createOptiFineWebsiteChip() {
        Chip chip = createChip("OptiFine ↗", true, false);
        chip.setCheckable(false);
        chip.setOnClickListener(view -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(OPTIFINE_DOWNLOADS_URL));
            try {
                activity.startActivity(intent);
            } catch (ActivityNotFoundException throwable) {
                Toast.makeText(activity, "No web browser is available to open OptiFine downloads.", Toast.LENGTH_LONG).show();
            }
        });
        return chip;
    }

    private Chip createLoaderChip(@NonNull String title, boolean checked) {
        Chip chip = createChip(title, true, checked);
        chip.setOnClickListener(view -> {
            boolean wasCleanroom = LOADER_CLEANROOM.equalsIgnoreCase(selectedLoader);
            selectedLoader = title;
            boolean isCleanroom = LOADER_CLEANROOM.equalsIgnoreCase(selectedLoader);

            if (isCleanroom) {
                selectedType = TYPE_RELEASE;
                if (releaseTypeChip != null) releaseTypeChip.setChecked(true);
            }

            updateDefaultNameIfAllowed();
            updateLoaderHelpText();
            if (wasCleanroom || isCleanroom) updateMinecraftVersionDropdown();
            else updateLoaderVersionDropdown();
        });
        return chip;
    }

    private ChipGroup createVersionTypeChips() {
        ChipGroup group = new ChipGroup(activity);
        group.setSingleSelection(true);
        group.setSelectionRequired(true);

        addTypeChip(group, activity.getString(R.string.version_tab_release), TYPE_RELEASE, true);
        addTypeChip(group, activity.getString(R.string.version_tab_snapshot), TYPE_SNAPSHOT, false);
        addTypeChip(group, activity.getString(R.string.version_tab_beta), TYPE_BETA, false);
        addTypeChip(group, activity.getString(R.string.version_tab_alpha), TYPE_ALPHA, false);

        return group;
    }

    private void addTypeChip(@NonNull ChipGroup group, @NonNull String title, @NonNull String type, boolean checked) {
        Chip chip = createChip(title, true, checked);
        if (TYPE_RELEASE.equals(type)) releaseTypeChip = chip;
        chip.setOnClickListener(view -> {
            if (LOADER_CLEANROOM.equalsIgnoreCase(selectedLoader) && !TYPE_RELEASE.equals(type)) {
                selectedType = TYPE_RELEASE;
                if (releaseTypeChip != null) releaseTypeChip.setChecked(true);
                return;
            }
            selectedType = type;
            updateMinecraftVersionDropdown();
        });
        group.addView(chip);
    }

    private Chip createChip(@NonNull String title, boolean enabled, boolean checked) {
        Chip chip = new Chip(activity);
        chip.setText(title);
        chip.setCheckable(true);
        chip.setEnabled(enabled);
        chip.setChecked(checked);
        return chip;
    }

    private void updateMinecraftVersionDropdown() {
        if (minecraftVersionDropdown == null) return;

        filteredVersions.clear();
        ArrayList<String> labels = new ArrayList<>();

        for (MinecraftVersion version : allVersions) {
            if (!selectedType.equals(version.getType())) continue;
            if (LOADER_CLEANROOM.equalsIgnoreCase(selectedLoader)
                    && !CleanroomSupport.MINECRAFT_VERSION.equals(version.getId())) {
                continue;
            }
            filteredVersions.add(version);
            labels.add(version.getId());
        }

        selectedMinecraftVersionIndex = 0;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, labels);
        minecraftVersionDropdown.setAdapter(adapter);

        if (labels.isEmpty()) {
            minecraftVersionDropdown.setText("", false);
            if (versionHelp != null) versionHelp.setText(R.string.create_instance_no_versions);
        } else {
            minecraftVersionDropdown.setText(labels.get(0), false);
        }

        updateDefaultNameIfAllowed();
        updateLoaderVersionDropdown();
    }

    private void updateLoaderVersionDropdown() {
        if (loaderVersionDropdown == null) return;

        MinecraftVersion minecraftVersion = getSelectedMinecraftVersion();
        loaderVersionOptions.clear();
        selectedLoaderVersionIndex = 0;
        loaderVersionRequestSerial++;
        int requestSerial = loaderVersionRequestSerial;

        if (minecraftVersion == null) {
            setLoaderVersionOptions(new ArrayList<>(), activity.getString(R.string.create_instance_no_versions), false);
            return;
        }

        if (LOADER_VANILLA.equalsIgnoreCase(selectedLoader)) {
            ArrayList<LoaderVersionResolver.LoaderVersionOption> vanilla = new ArrayList<>();
            vanilla.add(new LoaderVersionResolver.LoaderVersionOption(
                    activity.getString(R.string.create_instance_loader_version_vanilla),
                    ""
            ));
            setLoaderVersionOptions(vanilla, activity.getString(R.string.create_instance_loader_version_auto), true);
            return;
        }

        setLoaderVersionOptions(new ArrayList<>(), activity.getString(R.string.create_instance_loader_version_loading), false);

        String loader = selectedLoader;
        String mcVersion = minecraftVersion.getId();

        new Thread(() -> {
            ArrayList<LoaderVersionResolver.LoaderVersionOption> resolved = new ArrayList<>();
            String error = null;

            try {
                resolved.addAll(LoaderVersionResolver.resolveVersions(loader, mcVersion));
            } catch (Throwable throwable) {
                error = throwable.getMessage() == null ? throwable.toString() : throwable.getMessage();
            }

            String finalError = error;
            activity.runOnUiThread(() -> {
                if (requestSerial != loaderVersionRequestSerial) return;

                if (finalError != null) {
                    setLoaderVersionOptions(
                            new ArrayList<>(),
                            activity.getString(R.string.create_instance_loader_version_error, finalError),
                            false
                    );
                    return;
                }

                if (resolved.isEmpty()) {
                    setLoaderVersionOptions(
                            new ArrayList<>(),
                            activity.getString(R.string.create_instance_loader_version_none, loader, mcVersion),
                            false
                    );
                    return;
                }

                setLoaderVersionOptions(resolved, activity.getString(R.string.create_instance_loader_version_loaded, resolved.size()), true);
            });
        }, "LoaderVersionResolver-" + loader + "-" + mcVersion).start();
    }

    private void setLoaderVersionOptions(
            @NonNull ArrayList<LoaderVersionResolver.LoaderVersionOption> options,
            @NonNull String status,
            boolean enabled
    ) {
        loaderVersionOptions.clear();
        loaderVersionOptions.addAll(options);
        selectedLoaderVersionIndex = 0;

        ArrayList<String> labels = new ArrayList<>();
        for (LoaderVersionResolver.LoaderVersionOption option : loaderVersionOptions) {
            labels.add(option.displayName);
        }

        loaderVersionDropdown.setEnabled(enabled && !labels.isEmpty());
        loaderVersionDropdown.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, labels));
        loaderVersionDropdown.setText(labels.isEmpty() ? "" : labels.get(0), false);

        if (loaderVersionStatus != null) loaderVersionStatus.setText(status);
        if (createButton != null) createButton.setEnabled(LOADER_VANILLA.equalsIgnoreCase(selectedLoader) || !loaderVersionOptions.isEmpty());
        updateLoaderVersionStatus();
    }

    private void updateLoaderVersionStatus() {
        if (loaderVersionStatus == null || loaderVersionOptions.isEmpty()) return;

        LoaderVersionResolver.LoaderVersionOption option = loaderVersionOptions.get(Math.max(0, Math.min(selectedLoaderVersionIndex, loaderVersionOptions.size() - 1)));
        if (LOADER_VANILLA.equalsIgnoreCase(selectedLoader)) {
            loaderVersionStatus.setText(R.string.create_instance_loader_version_auto);
        } else {
            loaderVersionStatus.setText(activity.getString(R.string.create_instance_loader_version_selected, option.displayName));
        }
    }

    private void updateLoaderHelpText() {
        if (versionHelp == null) return;

        if (LOADER_FABRIC.equalsIgnoreCase(selectedLoader)) {
            versionHelp.setText(R.string.create_instance_fabric_summary);
        } else if (LOADER_FORGE.equalsIgnoreCase(selectedLoader)) {
            versionHelp.setText(R.string.create_instance_forge_summary);
        } else if (LOADER_NEOFORGE.equalsIgnoreCase(selectedLoader)) {
            versionHelp.setText(R.string.create_instance_neoforge_summary);
        } else if (LOADER_CLEANROOM.equalsIgnoreCase(selectedLoader)) {
            versionHelp.setText("Cleanroom is available only for Minecraft 1.12.2. DroidBridge will preserve its generated loader profile and automatically use Java 21 or Java 25 for the selected Cleanroom build.");
        } else {
            versionHelp.setText(R.string.create_instance_per_instance_summary);
        }
    }

    private void submit() {
        if (filteredVersions.isEmpty()) {
            versionHelp.setText(R.string.create_instance_no_versions);
            return;
        }

        MinecraftVersion version = getSelectedMinecraftVersion();
        if (version == null) {
            versionHelp.setText(R.string.create_instance_no_versions);
            return;
        }


        if (!LOADER_VANILLA.equalsIgnoreCase(selectedLoader) && loaderVersionOptions.isEmpty()) {
            versionHelp.setText(R.string.create_instance_loader_version_required);
            return;
        }

        String defaultName = buildDefaultInstanceName();
        String rawName = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
        String name = rawName.isEmpty() ? defaultName : rawName;

        // Checked = isolated instance. Unchecked = shared install only.
        boolean isolatedInstance = isolatedInstanceCheck == null || isolatedInstanceCheck.isChecked();

        if (isolatedInstance && !validateInstanceName(name)) {
            return;
        }

        listener.onCreateInstance(new Request(
                name,
                getSelectedLoaderName(),
                getSelectedLoaderVersion(),
                version.getId(),
                selectedType,
                iconUri,
                isolatedInstance
        ));

        if (dialog != null) dialog.dismiss();
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
        root.addView(title, matchWrap());

        TextView summary = new TextView(activity);
        summary.setText(summaryText);
        summary.setTextSize(12);
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

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
    public void setExistingInstanceNames(@Nullable List<String> names) {
        existingInstanceNameKeys.clear();

        if (names == null) return;

        for (String name : names) {
            String key = normalizeInstanceName(name);
            if (!key.isEmpty()) {
                existingInstanceNameKeys.add(key);
            }
        }
    }
    @NonNull
    private String normalizeInstanceName(@Nullable String name) {
        if (name == null) return "";
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isDuplicateInstanceName(@NonNull String name) {
        return existingInstanceNameKeys.contains(normalizeInstanceName(name));
    }

    private boolean validateInstanceName(@NonNull String name) {
        if (!isDuplicateInstanceName(name)) {
            if (nameInputLayout != null) {
                nameInputLayout.setError(null);
            }
            return true;
        }

        if (nameInputLayout != null) {
            nameInputLayout.setError(activity.getString(R.string.create_instance_name_already_exists, name));
        }

        if (nameInput != null) {
            nameInput.requestFocus();
        }

        return false;
    }
}

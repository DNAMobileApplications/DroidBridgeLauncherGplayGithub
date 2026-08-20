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

import android.content.Context;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.R;
import ca.dnamobile.droidbridgelauncher.databinding.ItemLauncherInstanceBinding;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstance;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;

public final class LauncherInstanceAdapter extends RecyclerView.Adapter<LauncherInstanceAdapter.InstanceViewHolder> {
    public interface Listener {
        void onInstanceSelected(@NonNull LauncherInstance instance);
        void onInstanceQuickPlayRequested(@NonNull LauncherInstance instance);

        void onInstanceDeleteRequested(@NonNull LauncherInstance instance);
    }

    private final Context context;
    private final LayoutInflater inflater;
    private final Listener listener;
    private final ArrayList<LauncherInstance> instances = new ArrayList<>();
    private final HashSet<String> multiSelectedKeys = new HashSet<>();
    @Nullable
    private String selectedInstanceKey;
    @Nullable
    private Runnable selectionChangedListener;
    @Nullable
    private Runnable favoriteChangedListener;
    private boolean selectionMode;

    public LauncherInstanceAdapter(@NonNull Context context, @NonNull Listener listener) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.listener = listener;
    }

    public void submitList(@NonNull List<LauncherInstance> newInstances) {
        instances.clear();
        instances.addAll(newInstances);
        pruneSelectionsToCurrentItems();
        notifyDataSetChanged();
        dispatchSelectionChanged();
    }

    public void setSelectedInstance(@Nullable LauncherInstance instance) {
        this.selectedInstanceKey = instance == null ? null : getSelectionKey(instance);
        notifyDataSetChanged();
    }

    public void clearSelectedInstance() {
        this.selectedInstanceKey = null;
        notifyDataSetChanged();
    }

    public void setSelectedInstanceId(@Nullable String selectedInstanceId) {
        if (selectedInstanceId == null) {
            clearSelectedInstance();
            return;
        }

        for (LauncherInstance instance : instances) {
            if (selectedInstanceId.equals(instance.getId())) {
                setSelectedInstance(instance);
                return;
            }
        }

        clearSelectedInstance();
    }

    public void setSelectionChangedListener(@Nullable Runnable listener) {
        this.selectionChangedListener = listener;
    }

    public void setFavoriteChangedListener(@Nullable Runnable listener) {
        this.favoriteChangedListener = listener;
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public void setSelectionMode(boolean enabled) {
        if (selectionMode == enabled) return;
        selectionMode = enabled;
        if (!enabled) multiSelectedKeys.clear();
        notifyDataSetChanged();
        dispatchSelectionChanged();
    }

    public void clearMultiSelection() {
        if (multiSelectedKeys.isEmpty()) return;
        multiSelectedKeys.clear();
        notifyDataSetChanged();
        dispatchSelectionChanged();
    }

    public int getSelectedCount() {
        return multiSelectedKeys.size();
    }

    @NonNull
    public ArrayList<LauncherInstance> getSelectedInstances() {
        ArrayList<LauncherInstance> selected = new ArrayList<>();
        for (LauncherInstance instance : instances) {
            if (multiSelectedKeys.contains(getSelectionKey(instance))) {
                selected.add(instance);
            }
        }
        return selected;
    }

    @NonNull
    @Override
    public InstanceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemLauncherInstanceBinding binding = ItemLauncherInstanceBinding.inflate(inflater, parent, false);
        return new InstanceViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull InstanceViewHolder holder, int position) {
        LauncherInstance instance = instances.get(position);
        String instanceKey = getSelectionKey(instance);
        boolean selected = selectedInstanceKey != null && selectedInstanceKey.equals(instanceKey);
        boolean multiSelected = multiSelectedKeys.contains(instanceKey);
        boolean favorite = LauncherPreferences.isInstanceFavorite(context, instance.getId());

        holder.binding.textInstanceName.setText(instance.getName());
        holder.binding.textInstanceMeta.setText(context.getString(
                instance.isIsolated() ? R.string.instance_meta_value : R.string.instance_meta_shared_value,
                displayLoader(instance.getLoader()),
                instance.getBaseVersionId(),
                displayVersionType(instance.getVersionType()),
                cleanDate(instance.getCreatedAt())
        ));
        holder.binding.textInstanceState.setText(instance.isIsolated()
                ? R.string.version_state_installed
                : R.string.instance_state_shared);

        bindInstanceIcon(holder, instance);

        MaterialCardView card = holder.binding.instanceCard;
        int selectedColor = MaterialColors.getColor(card, com.google.android.material.R.attr.colorPrimary);
        int outlineColor = MaterialColors.getColor(card, com.google.android.material.R.attr.colorOutline);
        card.setStrokeColor((selectionMode ? multiSelected : selected) ? selectedColor : outlineColor);
        card.setStrokeWidth((selectionMode ? multiSelected : selected) ? dp(2) : dp(1));

        holder.binding.getRoot().setOnClickListener(view -> {
            if (selectionMode) {
                toggleMultiSelection(instance);
                return;
            }
            setSelectedInstance(instance);
            listener.onInstanceSelected(instance);
        });
        holder.binding.getRoot().setOnLongClickListener(view -> {
            if (!selectionMode) {
                selectionMode = true;
                multiSelectedKeys.clear();
            }
            toggleMultiSelection(instance);
            return true;
        });

        holder.binding.buttonFavoriteInstance.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        holder.binding.buttonFavoriteInstance.setImageResource(R.drawable.ic_favorite_24);
        holder.binding.buttonFavoriteInstance.setAlpha(favorite ? 1.0f : 0.42f);
        holder.binding.buttonFavoriteInstance.setContentDescription(
                favorite
                        ? context.getString(R.string.instance_favorite_remove_description)
                        : context.getString(R.string.instance_favorite_add_description)
        );
        holder.binding.buttonFavoriteInstance.setOnClickListener(view -> {
            boolean nowFavorite = !LauncherPreferences.isInstanceFavorite(context, instance.getId());
            LauncherPreferences.setInstanceFavorite(context, instance.getId(), nowFavorite);
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition >= 0) notifyItemChanged(adapterPosition);
            else notifyDataSetChanged();
            if (favoriteChangedListener != null) favoriteChangedListener.run();
        });

        holder.binding.buttonDeleteInstance.setImageResource(selectionMode ? R.drawable.ic_check_24 : R.drawable.ic_play_arrow_24);
        holder.binding.buttonDeleteInstance.setContentDescription(selectionMode
                ? context.getString(multiSelected ? R.string.instance_multiselect_selected_description : R.string.instance_multiselect_unselected_description)
                : context.getString(R.string.instance_play_content_description));
        holder.binding.buttonDeleteInstance.setAlpha(selectionMode && !multiSelected ? 0.45f : 1.0f);
        holder.binding.buttonDeleteInstance.setOnClickListener(view -> {
            if (selectionMode) {
                toggleMultiSelection(instance);
                return;
            }
            listener.onInstanceQuickPlayRequested(instance);
        });
    }

    private void bindInstanceIcon(@NonNull InstanceViewHolder holder, @NonNull LauncherInstance instance) {
        holder.binding.imageInstanceIcon.setImageDrawable(null);

        File iconFile = instance.getIconFile();
        if (iconFile != null && iconFile.isFile()) {
            try {
                holder.binding.imageInstanceIcon.setImageURI(Uri.fromFile(iconFile));
                if (holder.binding.imageInstanceIcon.getDrawable() != null) {
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        holder.binding.imageInstanceIcon.setImageResource(InstanceIconResolver.getDefaultIcon(instance));
    }

    private void toggleMultiSelection(@NonNull LauncherInstance instance) {
        String key = getSelectionKey(instance);
        if (!multiSelectedKeys.add(key)) {
            multiSelectedKeys.remove(key);
        }
        if (selectionMode && multiSelectedKeys.isEmpty()) {
            selectionMode = false;
        }
        notifyDataSetChanged();
        dispatchSelectionChanged();
    }

    private void pruneSelectionsToCurrentItems() {
        if (multiSelectedKeys.isEmpty()) return;

        HashSet<String> current = new HashSet<>();
        for (LauncherInstance instance : instances) {
            current.add(getSelectionKey(instance));
        }
        multiSelectedKeys.retainAll(current);
    }

    private void dispatchSelectionChanged() {
        if (selectionChangedListener != null) selectionChangedListener.run();
    }

    @Override
    public int getItemCount() {
        return instances.size();
    }

    @NonNull
    public List<LauncherInstance> getCurrentItems() {
        return Collections.unmodifiableList(instances);
    }

    @NonNull
    private String displayLoader(@Nullable String loader) {
        if (loader == null || loader.isBlank()) return "Vanilla";
        return loader.substring(0, 1).toUpperCase(Locale.US) + loader.substring(1);
    }

    @NonNull
    private static String displayVersionType(@Nullable String type) {
        if (type == null) return "Unknown";
        switch (type) {
            case "release":
                return "Release";
            case "snapshot":
                return "Snapshot";
            case "old_beta":
                return "Beta";
            case "old_alpha":
                return "Alpha";
            default:
                return type.substring(0, 1).toUpperCase(Locale.US) + type.substring(1).replace('_', ' ');
        }
    }

    @NonNull
    private static String cleanDate(@Nullable String releaseTime) {
        if (releaseTime == null || releaseTime.isBlank()) return "Unknown date";
        int index = releaseTime.indexOf('T');
        return index > 0 ? releaseTime.substring(0, index) : releaseTime;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static final class InstanceViewHolder extends RecyclerView.ViewHolder {
        final ItemLauncherInstanceBinding binding;

        InstanceViewHolder(@NonNull ItemLauncherInstanceBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    @NonNull
    public static String getSelectionKey(@NonNull LauncherInstance instance) {
        if (instance.isIsolated()) {
            return "isolated:"
                    + safePath(instance.getRootDirectory())
                    + ":"
                    + nullToEmpty(instance.getName());
        }

        return "shared:"
                + safePath(instance.getGameDirectory())
                + ":"
                + nullToEmpty(instance.getBaseVersionId());
    }

    @NonNull
    private static String safePath(@Nullable File file) {
        if (file == null) return "";
        return file.getAbsoluteFile().getAbsolutePath();
    }

    @NonNull
    private static String nullToEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}

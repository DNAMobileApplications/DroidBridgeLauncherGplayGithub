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

package ca.dnamobile.droidbridgelauncher.storage;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;

import ca.dnamobile.droidbridgelauncher.R;

public final class StorageLocationAdapter extends RecyclerView.Adapter<StorageLocationAdapter.ViewHolder> {
    public interface Listener {
        void onLocationClicked(@NonNull StorageLocation location);

        void onLocationDeleteClicked(@NonNull StorageLocation location);
    }

    private final Listener listener;
    private final ArrayList<StorageLocation> locations = new ArrayList<>();
    private String selectedId = StorageLocationStore.DEFAULT_LOCATION_ID;

    public StorageLocationAdapter(@NonNull Listener listener) {
        this.listener = listener;
    }

    public void submit(@NonNull List<StorageLocation> newLocations, @NonNull String selectedId) {
        locations.clear();
        locations.addAll(newLocations);
        this.selectedId = selectedId;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int horizontal = dp(parent, 10);
        int vertical = dp(parent, 6);
        int rowPadding = dp(parent, 12);
        int spacing = dp(parent, 12);

        MaterialCardView card = new MaterialCardView(parent.getContext());
        card.setRadius(dp(parent, 18));
        card.setCardElevation(dp(parent, 0));
        card.setUseCompatPadding(true);
        card.setClickable(true);
        card.setFocusable(true);
        card.setStrokeWidth(dp(parent, 1));

        RecyclerView.LayoutParams cardParams = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, vertical, 0, vertical);
        card.setLayoutParams(cardParams);

        LinearLayout row = new LinearLayout(parent.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(horizontal, rowPadding, horizontal, rowPadding);
        card.addView(row, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        ImageView icon = new ImageView(parent.getContext());
        icon.setImageResource(R.drawable.ic_folder_24);
        icon.setPadding(dp(parent, 9), dp(parent, 9), dp(parent, 9), dp(parent, 9));
        row.addView(icon, new LinearLayout.LayoutParams(dp(parent, 46), dp(parent, 46)));

        LinearLayout textColumn = new LinearLayout(parent.getContext());
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setPadding(spacing, 0, spacing, 0);
        row.addView(textColumn, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        TextView title = new TextView(parent.getContext());
        title.setTextSize(16);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setMaxLines(1);
        textColumn.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView summary = new TextView(parent.getContext());
        summary.setTextSize(12);
        summary.setMaxLines(3);
        summary.setPadding(0, dp(parent, 3), 0, 0);
        textColumn.addView(summary, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        RadioButton radio = new RadioButton(parent.getContext());
        radio.setClickable(true);
        radio.setFocusable(false);
        row.addView(radio, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        ImageView delete = new ImageView(parent.getContext());
        delete.setImageResource(R.drawable.ic_delete_24);
        delete.setContentDescription(parent.getContext().getString(R.string.storage_location_delete_action));
        delete.setPadding(dp(parent, 10), dp(parent, 10), dp(parent, 10), dp(parent, 10));
        delete.setClickable(true);
        delete.setFocusable(true);
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(parent, 44), dp(parent, 44));
        deleteParams.setMargins(dp(parent, 4), 0, 0, 0);
        row.addView(delete, deleteParams);

        return new ViewHolder(card, radio, title, summary, delete);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        StorageLocation location = locations.get(position);
        boolean selected = location.getId().equals(selectedId);
        boolean canDelete = !location.isDefaultLocation();

        holder.radio.setChecked(selected);
        holder.title.setText(location.getDisplayName());
        holder.summary.setText(location.getSummary());
        holder.itemView.setSelected(selected);

        installRecursiveClickListener(holder.itemView, location, holder.deleteButton);

        holder.deleteButton.setVisibility(canDelete ? View.VISIBLE : View.GONE);
        holder.deleteButton.setOnClickListener(canDelete
                ? view -> listener.onLocationDeleteClicked(location)
                : null);
    }

    private void select(@NonNull StorageLocation location) {
        selectedId = location.getId();
        notifyDataSetChanged();
        listener.onLocationClicked(location);
    }

    /**
     * Make the entire row tappable, including the path text and radio dot, but do not
     * steal clicks from the delete icon.
     */
    private void installRecursiveClickListener(
            @NonNull View view,
            @NonNull StorageLocation location,
            @Nullable View excludedView
    ) {
        if (view == excludedView) {
            return;
        }

        view.setClickable(true);
        view.setOnClickListener(clicked -> select(location));

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                installRecursiveClickListener(group.getChildAt(i), location, excludedView);
            }
        }
    }

    @Override
    public int getItemCount() {
        return locations.size();
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        final RadioButton radio;
        final TextView title;
        final TextView summary;
        final ImageView deleteButton;

        ViewHolder(
                @NonNull View itemView,
                @NonNull RadioButton radio,
                @NonNull TextView title,
                @NonNull TextView summary,
                @NonNull ImageView deleteButton
        ) {
            super(itemView);
            this.radio = radio;
            this.title = title;
            this.summary = summary;
            this.deleteButton = deleteButton;
        }
    }

    private static int dp(@NonNull View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
}

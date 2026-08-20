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

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ca.dnamobile.droidbridgelauncher.R;

/**
 * Modrinth-style storage location dialog.
 *
 * This intentionally mirrors the Create Instance dialog structure:
 * rounded Material card, large icon/header, concise summary, content list, and
 * bottom action buttons. The dialog selects/saves a location and lets the user
 * forget custom storage locations without deleting files from storage.
 */
public final class StorageLocationDialog {
    public interface Listener {
        void onLocationSelected(@NonNull StorageLocation location);

        void onAddLocationRequested();

        void onDeleteLocationRequested(@NonNull StorageLocation location);
    }

    private StorageLocationDialog() {
    }

    public static void show(@NonNull Activity activity, @NonNull Listener listener) {
        ScrollView scrollView = new ScrollView(activity);
        scrollView.setFillViewport(false);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 4), dp(activity, 4), dp(activity, 4), dp(activity, 4));
        scrollView.addView(root);

        MaterialCardView card = new MaterialCardView(activity);
        card.setRadius(dp(activity, 26));
        card.setCardElevation(dp(activity, 8));
        card.setUseCompatPadding(true);
        card.setPreventCornerOverlap(true);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(activity, 22), dp(activity, 20), dp(activity, 22), dp(activity, 18));
        card.addView(content, matchWrap());

        final AlertDialog[] dialogHolder = new AlertDialog[1];

        content.addView(createHeader(activity));
        content.addView(createLocationSection(activity, listener, dialogHolder), matchWrap());

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setView(scrollView)
                .create();
        dialogHolder[0] = dialog;

        content.addView(createActions(activity, dialog, listener), matchWrap());
        root.addView(card, matchWrap());

        dialog.setOnShowListener(dialogInterface -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
            }
        });

        dialog.show();
    }

    @NonNull
    private static View createHeader(@NonNull Activity activity) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(activity, 18));

        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.drawable.ic_folder_24);
        icon.setPadding(dp(activity, 13), dp(activity, 13), dp(activity, 13), dp(activity, 13));

        GradientDrawable iconBackground = new GradientDrawable();
        iconBackground.setCornerRadius(dp(activity, 18));
        iconBackground.setColor(0xFF20242B);
        icon.setBackground(iconBackground);

        row.addView(icon, new LinearLayout.LayoutParams(dp(activity, 78), dp(activity, 78)));

        LinearLayout textColumn = new LinearLayout(activity);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setPadding(dp(activity, 16), 0, 0, 0);

        TextView title = new TextView(activity);
        title.setText(R.string.storage_locations_title);
        title.setTextSize(22);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        textColumn.addView(title, matchWrap());

        TextView subtitle = new TextView(activity);
        subtitle.setText(R.string.storage_locations_summary);
        subtitle.setTextSize(13);
        subtitle.setPadding(0, dp(activity, 4), 0, 0);
        textColumn.addView(subtitle, matchWrap());

        row.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    @NonNull
    private static View createLocationSection(
            @NonNull Activity activity,
            @NonNull Listener listener,
            @NonNull AlertDialog[] dialogHolder
    ) {
        LinearLayout section = new LinearLayout(activity);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, dp(activity, 4), 0, dp(activity, 10));

        TextView title = new TextView(activity);
        title.setText(R.string.storage_locations_choose_title);
        title.setTextSize(15);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        section.addView(title, matchWrap());

        TextView help = new TextView(activity);
        help.setText(R.string.storage_locations_choose_summary);
        help.setTextSize(12);
        help.setPadding(0, dp(activity, 2), 0, dp(activity, 8));
        section.addView(help, matchWrap());

        RecyclerView recyclerView = new RecyclerView(activity);
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        recyclerView.setNestedScrollingEnabled(false);

        StorageLocationAdapter adapter = new StorageLocationAdapter(new StorageLocationAdapter.Listener() {
            @Override
            public void onLocationClicked(@NonNull StorageLocation location) {
                StorageLocationStore.setSelectedLocationId(activity, location.getId());
                if (dialogHolder[0] != null) {
                    dialogHolder[0].dismiss();
                }
                listener.onLocationSelected(location);
            }

            @Override
            public void onLocationDeleteClicked(@NonNull StorageLocation location) {
                if (dialogHolder[0] != null) {
                    dialogHolder[0].dismiss();
                }
                listener.onDeleteLocationRequested(location);
            }
        });
        adapter.submit(StorageLocationStore.getLocations(activity), StorageLocationStore.getSelectedLocationId(activity));
        recyclerView.setAdapter(adapter);

        LinearLayout.LayoutParams recyclerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 270)
        );
        recyclerParams.topMargin = dp(activity, 4);
        section.addView(recyclerView, recyclerParams);

        return section;
    }

    @NonNull
    private static View createActions(
            @NonNull Activity activity,
            @NonNull AlertDialog dialog,
            @NonNull Listener listener
    ) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        row.setPadding(0, dp(activity, 10), 0, 0);

        MaterialButton close = new MaterialButton(activity);
        close.setText(android.R.string.cancel);
        close.setOnClickListener(view -> dialog.dismiss());
        row.addView(close, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        MaterialButton add = new MaterialButton(activity);
        add.setText(R.string.button_add_storage_location);
        add.setIconResource(R.drawable.ic_folder_24);
        add.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        add.setOnClickListener(view -> {
            dialog.dismiss();
            listener.onAddLocationRequested();
        });

        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        addParams.setMargins(dp(activity, 10), 0, 0, 0);
        row.addView(add, addParams);

        return row;
    }

    @NonNull
    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private static int dp(@NonNull Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}

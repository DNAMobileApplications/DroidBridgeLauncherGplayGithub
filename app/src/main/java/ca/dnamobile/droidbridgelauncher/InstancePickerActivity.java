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
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;

import ca.dnamobile.droidbridgelauncher.instance.LauncherInstance;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstanceManager;
import ca.dnamobile.droidbridgelauncher.shortcuts.InstanceShortcutHelper;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Exported picker used by trusted front ends such as Kid Emu.
 *
 * Kid Emu should launch this activity with ACTION_PICK_INSTANCE. DroidBridge owns
 * the instance database, so this avoids making parents type or discover raw
 * instance IDs manually.
 */
public final class InstancePickerActivity extends AppCompatActivity {
    private final ArrayList<LauncherInstance> instances = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherTheme.apply(this);
        super.onCreate(savedInstanceState);

        PathManager.initContextConstants(this);

        Intent intent = getIntent();
        if (intent == null || !InstanceShortcutHelper.ACTION_PICK_INSTANCE.equals(intent.getAction())) {
            Toast.makeText(this, "Invalid instance picker request.", Toast.LENGTH_SHORT).show();
            setResult(Activity.RESULT_CANCELED);
            finish();
            return;
        }

        loadInstances();
        if (instances.isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("No DroidBridge instances")
                    .setMessage("Create a Minecraft instance in DroidBridge first, then import it into Kid Emu.")
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> finishCanceled())
                    .setOnCancelListener(dialog -> finishCanceled())
                    .show();
            return;
        }

        showPickerDialog();
    }

    private void loadInstances() {
        instances.clear();
        try {
            instances.addAll(LauncherInstanceManager.findInstances(this));
        } catch (Throwable throwable) {
            Toast.makeText(this, "Unable to load instances.", Toast.LENGTH_LONG).show();
        }
    }

    private void showPickerDialog() {
        String[] names = new String[instances.size()];
        for (int i = 0; i < instances.size(); i++) {
            LauncherInstance instance = instances.get(i);
            String detail = instance.getLoader();
            if (detail == null || detail.trim().isEmpty()) detail = "Minecraft";
            names[i] = instance.getName() + "\n" + detail + " · " + instance.getMinecraftVersionId();
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("Choose DroidBridge instance")
                .setItems(names, (dialog, which) -> {
                    if (which < 0 || which >= instances.size()) {
                        finishCanceled();
                        return;
                    }
                    returnPickedInstance(instances.get(which));
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> finishCanceled())
                .setOnCancelListener(dialog -> finishCanceled())
                .show();
    }

    private void returnPickedInstance(@NonNull LauncherInstance instance) {
        Intent result = new Intent();
        result.putExtra(InstanceShortcutHelper.EXTRA_PICKED_INSTANCE_ID, instance.getId());
        result.putExtra(InstanceShortcutHelper.EXTRA_PICKED_INSTANCE_ID_LEGACY, instance.getId());
        result.putExtra(InstanceShortcutHelper.EXTRA_PICKED_INSTANCE_NAME, instance.getName());
        setResult(Activity.RESULT_OK, result);
        finish();
    }

    private void finishCanceled() {
        setResult(Activity.RESULT_CANCELED);
        finish();
    }
}

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

import androidx.annotation.Nullable;

import ca.dnamobile.droidbridgelauncher.shortcuts.InstanceShortcutHelper;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

/**
 * Small exported bridge used by pinned Android launcher shortcuts.
 *
 * The shortcut itself stores only the stable instance id. This activity hands the
 * id back into MainActivity so DroidBridge uses the same launch checks and
 * per-instance settings flow as the normal Play/quick-launch buttons.
 */
public final class InstanceShortcutActivity extends Activity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LauncherTheme.apply(this);
        super.onCreate(savedInstanceState);

        PathManager.initContextConstants(this);

        Intent source = getIntent();
        String instanceId = InstanceShortcutHelper.readInstanceId(source);

        if (instanceId == null || instanceId.trim().isEmpty()) {
            Toast.makeText(this, "This instance shortcut is invalid.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.setAction(InstanceShortcutHelper.ACTION_LAUNCH_INSTANCE);
        launchIntent.putExtra(InstanceShortcutHelper.EXTRA_INSTANCE_ID, instanceId);
        launchIntent.putExtra(InstanceShortcutHelper.EXTRA_INSTANCE_ID_LEGACY, instanceId);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launchIntent);
        finish();
    }
}

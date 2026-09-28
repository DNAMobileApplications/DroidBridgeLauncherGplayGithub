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
import android.content.Intent;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.util.LinkedHashSet;
import java.util.List;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.feature.unpack.ComponentInstallationManager;
import ca.dnamobile.droidbridgelauncher.feature.unpack.RuntimeComponentMigration;
import ca.dnamobile.droidbridgelauncher.launcher.DroidBridgeLaunchActivity;
import ca.dnamobile.droidbridgelauncher.runtime.Tools;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

@SuppressLint("CustomSplashScreen")
public class SplashActivity extends AppCompatActivity {
    private TextView titleText;
    private TextView headingText;
    private TextView descriptionText;
    private TextView storageText;
    private TextView statusText;
    private TextView missingText;
    private ProgressBar progressBar;
    private MaterialButton installButton;
    private MaterialButton continueButton;

    private volatile boolean finished;
    private volatile boolean installing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        LauncherTheme.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        LauncherTheme.applyRainbowBackgroundIfNeeded(this);

        titleText = findViewById(R.id.textTitle);
        headingText = findViewById(R.id.textInstallerHeading);
        descriptionText = findViewById(R.id.textInstallerDescription);
        storageText = findViewById(R.id.textInstallerStorage);
        statusText = findViewById(R.id.textStatus);
        missingText = findViewById(R.id.textMissingComponents);
        progressBar = findViewById(R.id.progressComponents);
        installButton = findViewById(R.id.buttonInstallComponents);
        continueButton = findViewById(R.id.buttonContinueLauncher);

        titleText.setText(R.string.app_name);
        installButton.setOnClickListener(view -> startMissingComponentInstall());
        continueButton.setOnClickListener(view -> openMainActivity());

        PathManager.initContextConstants(this);
        RuntimeComponentMigration.runIfNeeded(this);
        PathManager.initContextConstants(this);

        checkComponentStateAsync();
    }

    @Override
    protected void onDestroy() {
        finished = true;
        installing = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onDestroy();
    }

    private void checkComponentStateAsync() {
        showCheckingState();
        Thread thread = new Thread(() -> {
            if (!Tools.checkStorageRoot()) {
                runOnUiThread(this::showStorageUnavailableState);
                return;
            }

            try {
                ComponentInstallationManager.ScanResult scan = ComponentInstallationManager.scan(this);
                if (scan.isComplete()) {
                    openMainActivity();
                } else {
                    runOnUiThread(() -> showInstallPrompt(scan));
                }
            } catch (Throwable throwable) {
                Logging.e("SplashActivity", "Unable to check launcher components", throwable);
                runOnUiThread(() -> showCheckFailure(throwable));
            }
        }, "DroidBridge Component Check");
        thread.start();
    }

    private void showCheckingState() {
        headingText.setText(R.string.component_installer_checking_title);
        descriptionText.setText(R.string.component_installer_checking_message);
        storageText.setVisibility(View.GONE);
        missingText.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        statusText.setText(R.string.splash_screen_checking);
        installButton.setVisibility(View.GONE);
        continueButton.setVisibility(View.GONE);
    }

    private void showInstallPrompt(@NonNull ComponentInstallationManager.ScanResult scan) {
        if (finished || installing) return;

        boolean firstInstall = scan.looksLikeFirstInstall()
                && !ComponentInstallationManager.hasAttemptedInstall(this);
        headingText.setText(firstInstall
                ? R.string.component_installer_first_launch_title
                : R.string.component_installer_repair_title);
        descriptionText.setText(firstInstall
                ? R.string.component_installer_first_launch_message
                : R.string.component_installer_repair_message);

        updateStorageText();
        setMissingText(scan.getMissingNames(), R.string.component_installer_pending_prefix);

        progressBar.setVisibility(View.GONE);
        statusText.setText(getString(
                R.string.component_installer_pending_count,
                scan.getMissingCount()
        ));
        installButton.setText(firstInstall
                ? R.string.component_installer_install_button
                : R.string.component_installer_reinstall_button);
        installButton.setEnabled(true);
        installButton.setVisibility(View.VISIBLE);

        continueButton.setVisibility(firstInstall ? View.GONE : View.VISIBLE);
        continueButton.setEnabled(true);
    }

    private void startMissingComponentInstall() {
        if (installing || finished) return;
        if (!Tools.checkStorageRoot()) {
            showStorageUnavailableState();
            return;
        }

        installing = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        headingText.setText(R.string.component_installer_installing_title);
        descriptionText.setText(R.string.component_installer_installing_message);
        missingText.setVisibility(View.GONE);
        updateStorageText();
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(false);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        statusText.setText(R.string.component_installer_starting);
        installButton.setEnabled(false);
        installButton.setText(R.string.component_installer_working_button);
        continueButton.setVisibility(View.GONE);

        Thread thread = new Thread(() -> {
            ComponentInstallationManager.InstallResult result;
            try {
                result = ComponentInstallationManager.installMissing(
                        this,
                        (current, total, itemName, detail) -> runOnUiThread(() -> {
                            if (finished || !installing) return;
                            int progress = total > 0 ? Math.round((current * 100f) / total) : 0;
                            progressBar.setProgress(Math.max(0, Math.min(100, progress)));
                            statusText.setText(detail);
                        })
                );
            } catch (Throwable throwable) {
                Logging.e("SplashActivity", "Component installation flow failed", throwable);
                runOnUiThread(() -> finishInstallationWithUnexpectedFailure(throwable));
                return;
            }

            runOnUiThread(() -> finishInstallation(result));
        }, "DroidBridge Component Install");
        thread.start();
    }

    private void finishInstallation(@NonNull ComponentInstallationManager.InstallResult result) {
        installing = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        progressBar.setProgress(100);

        if (result.isComplete()) {
            statusText.setText(R.string.component_installer_complete);
            openMainActivity();
            return;
        }

        List<String> missingNames = result.getAllMissingNames();
        headingText.setText(R.string.component_installer_incomplete_title);
        descriptionText.setText(R.string.component_installer_incomplete_message);
        updateStorageText();
        setMissingText(missingNames, R.string.component_installer_missing_prefix);
        statusText.setText(getString(
                R.string.component_installer_skipped_count,
                missingNames.size()
        ));
        progressBar.setVisibility(View.GONE);

        installButton.setText(R.string.component_installer_reinstall_button);
        installButton.setEnabled(true);
        installButton.setVisibility(View.VISIBLE);
        continueButton.setEnabled(true);
        continueButton.setVisibility(View.VISIBLE);

        showMissingComponentsDialog(missingNames);
    }

    private void finishInstallationWithUnexpectedFailure(@NonNull Throwable throwable) {
        installing = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        progressBar.setVisibility(View.GONE);
        headingText.setText(R.string.component_installer_incomplete_title);
        descriptionText.setText(R.string.component_installer_incomplete_message);
        updateStorageText();
        statusText.setText(getString(
                R.string.splash_screen_failed,
                readableMessage(throwable)
        ));
        installButton.setText(R.string.component_installer_reinstall_button);
        installButton.setEnabled(true);
        installButton.setVisibility(View.VISIBLE);
        continueButton.setEnabled(true);
        continueButton.setVisibility(View.VISIBLE);
    }

    private void showMissingComponentsDialog(@NonNull List<String> missingNames) {
        if (finished || isFinishing()) return;

        StringBuilder message = new StringBuilder();
        message.append(getString(R.string.component_installer_missing_dialog_message));
        if (!missingNames.isEmpty()) {
            message.append("\n\n");
            for (String name : new LinkedHashSet<>(missingNames)) {
                message.append("• ").append(name).append('\n');
            }
        }
        message.append("\n").append(getString(R.string.component_installer_troubleshooting));

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.component_installer_missing_dialog_title)
                .setMessage(message.toString().trim())
                .setNegativeButton(R.string.component_installer_continue_button, (dialog, which) -> openMainActivity())
                .setPositiveButton(R.string.component_installer_reinstall_button, (dialog, which) -> startMissingComponentInstall())
                .show();
    }

    private void showStorageUnavailableState() {
        if (finished) return;
        installing = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        headingText.setText(R.string.component_installer_storage_title);
        descriptionText.setText(R.string.splash_screen_storage_unavailable);
        storageText.setText(R.string.component_installer_storage_advice);
        storageText.setVisibility(View.VISIBLE);
        missingText.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        statusText.setText(R.string.component_installer_storage_retry_message);
        installButton.setText(R.string.component_installer_check_again_button);
        installButton.setEnabled(true);
        installButton.setVisibility(View.VISIBLE);
        installButton.setOnClickListener(view -> {
            installButton.setOnClickListener(button -> startMissingComponentInstall());
            checkComponentStateAsync();
        });
        continueButton.setVisibility(View.GONE);
    }

    private void showCheckFailure(@NonNull Throwable throwable) {
        if (finished) return;
        headingText.setText(R.string.component_installer_repair_title);
        descriptionText.setText(R.string.component_installer_repair_message);
        updateStorageText();
        progressBar.setVisibility(View.GONE);
        statusText.setText(getString(R.string.splash_screen_failed, readableMessage(throwable)));
        installButton.setText(R.string.component_installer_reinstall_button);
        installButton.setEnabled(true);
        installButton.setVisibility(View.VISIBLE);
        continueButton.setVisibility(View.VISIBLE);
    }

    private void updateStorageText() {
        long available = ComponentInstallationManager.getAvailableBytes(this);
        String availableText = available >= 0
                ? Formatter.formatFileSize(this, available)
                : getString(R.string.component_installer_storage_unknown);
        storageText.setText(getString(
                R.string.component_installer_storage_status,
                availableText
        ));
        storageText.setVisibility(View.VISIBLE);
    }

    private void setMissingText(@NonNull List<String> names, int prefixRes) {
        LinkedHashSet<String> unique = new LinkedHashSet<>(names);
        if (unique.isEmpty()) {
            missingText.setVisibility(View.GONE);
            return;
        }

        StringBuilder text = new StringBuilder(getString(prefixRes));
        for (String name : unique) {
            text.append("\n• ").append(name);
        }
        missingText.setText(text.toString());
        missingText.setVisibility(View.VISIBLE);
    }

    private void openMainActivity() {
        if (finished) return;
        runOnUiThread(() -> {
            if (finished) return;
            finished = true;
            installing = false;
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            startActivity(new Intent(this, DroidBridgeLaunchActivity.class));
            finish();
        });
    }

    @NonNull
    private static String readableMessage(@NonNull Throwable throwable) {
        String message = throwable.getMessage();
        return message != null && !message.trim().isEmpty()
                ? message.trim()
                : throwable.getClass().getSimpleName();
    }
}

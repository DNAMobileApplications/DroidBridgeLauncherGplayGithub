/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */
package ca.dnamobile.droidbridgelauncher.ui.version;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstance;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Shared UI for post-modpack-install, manual migration, and compatibility repair. */
public final class CleanroomMigrationDialog {
    private static final String TAG = "CleanroomMigrationUi";

    // Keep these values in sync with the launcher's other custom dialogs.
    private static final int COLOR_DIALOG_BG = Color.rgb(30, 34, 42);
    private static final int COLOR_CARD_BG = Color.rgb(38, 43, 53);
    private static final int COLOR_CARD_STROKE = Color.rgb(54, 61, 74);
    private static final int COLOR_TEXT_PRIMARY = Color.rgb(238, 241, 248);
    private static final int COLOR_TEXT_SECONDARY = Color.rgb(198, 204, 216);
    private static final int COLOR_ACCENT = Color.rgb(37, 211, 128);
    private static final float DIALOG_DIM = 0.58f;

    private CleanroomMigrationDialog() {
    }

    public interface Completion {
        void onFinished(@NonNull LauncherInstance instance, @NonNull String message);
    }

    public static void offerAfterModpackInstall(
            @NonNull Activity activity,
            @NonNull String installMessage,
            @Nullable LauncherInstance instance,
            @NonNull Completion completion
    ) {
        if (instance == null) return;
        if (!CleanroomMigrationManager.isEligible(instance)) {
            completion.onFinished(instance, installMessage);
            return;
        }

        boolean rlcraft = CleanroomCompatibilityManager.isRLCraft(instance);
        String compatibilityText = rlcraft
                ? "RLCraft was detected. DroidBridge will install Fugue, Scalar Legacy and the Cleanroom-compatible RLCraft replacements. Conflicting original JARs are moved into a dated backup folder so they can be restored."
                : "DroidBridge will install the required Cleanroom compatibility libraries and replace known incompatible legacy libraries when detected. Original conflicting JARs are backed up, not deleted.";

        showActionDialog(
                activity,
                "Use Cleanroom for this modpack?",
                "This is a Forge 1.12.2 modpack. DroidBridge can convert this same instance to Cleanroom after installation.\n\n"
                        + compatibilityText + "\n\n"
                        + "Configs, scripts, saves, resource packs, options, and the rest of the modpack remain in the same instance folder. You can also run this later from Instance Settings.",
                "Switch to Cleanroom",
                () -> startOperation(activity, instance, installMessage, false, completion),
                "Later",
                () -> completion.onFinished(instance, installMessage),
                "Keep Forge",
                () -> completion.onFinished(instance, installMessage),
                false
        );
    }

    public static void showForInstance(
            @NonNull Activity activity,
            @Nullable LauncherInstance instance,
            @NonNull Completion completion
    ) {
        if (!CleanroomMigrationManager.isEligible(instance)) {
            Toast.makeText(activity, "Only isolated Forge 1.12.2 instances can be migrated to Cleanroom.", Toast.LENGTH_LONG).show();
            return;
        }

        final LauncherInstance target = instance;
        boolean rlcraft = CleanroomCompatibilityManager.isRLCraft(target);
        String preset = rlcraft
                ? "RLCraft was detected. Required compatibility mods will be installed and incompatible original JARs will be backed up before the loader is switched."
                : "Required Cleanroom compatibility libraries will be installed. Any replaced legacy libraries are backed up before the loader is switched.";

        showActionDialog(
                activity,
                "Migrate to Cleanroom",
                "Convert this Forge 1.12.2 instance to Cleanroom in place?\n\n"
                        + preset + "\n\n"
                        + "The migration does not move the instance or alter its saves, configs, scripts, options, or resource packs.",
                "Migrate",
                () -> startOperation(activity, target, "", false, completion),
                null,
                null,
                activity.getString(android.R.string.cancel),
                null,
                true
        );
    }

    public static void showRepairForInstance(
            @NonNull Activity activity,
            @Nullable LauncherInstance instance,
            @NonNull Completion completion
    ) {
        if (!CleanroomCompatibilityManager.isRepairEligible(instance)) {
            Toast.makeText(activity, "Only isolated Cleanroom 1.12.2 instances can use this repair.", Toast.LENGTH_LONG).show();
            return;
        }

        final LauncherInstance target = instance;
        boolean rlcraft = CleanroomCompatibilityManager.isRLCraft(target);
        showActionDialog(
                activity,
                "Repair Cleanroom Compatibility",
                (rlcraft
                        ? "Apply the RLCraft Cleanroom compatibility preset to this instance?"
                        : "Scan this instance and install missing Cleanroom compatibility libraries?")
                        + "\n\nConflicting JARs are moved to mods/cleanroom-disabled in a dated backup folder. They are never deleted. Saves, configs, scripts and resource packs are not changed.",
                "Repair",
                () -> startOperation(activity, target, "", true, completion),
                null,
                null,
                activity.getString(android.R.string.cancel),
                null,
                true
        );
    }

    private static void startOperation(
            @NonNull Activity activity,
            @NonNull LauncherInstance instance,
            @NonNull String previousMessage,
            boolean repair,
            @NonNull Completion completion
    ) {
        LinearLayout root = createDialogRoot(activity);

        TextView title = createTitle(activity,
                repair ? "Repairing Cleanroom Compatibility" : "Migrating to Cleanroom");
        root.addView(title, matchWrap());

        LinearLayout card = addCard(activity, root);

        TextView status = new TextView(activity);
        status.setText(repair ? "Scanning the modpack..." : "Preparing Cleanroom migration...");
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        status.setTextColor(COLOR_TEXT_SECONDARY);
        status.setPadding(0, 0, 0, dp(activity, 14));
        card.addView(status, matchWrap());

        ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(false);
        progress.setMax(100);
        progress.setProgress(1);
        tintProgressBar(progress);
        card.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 12)
        ));

        AlertDialog progressDialog = new MaterialAlertDialogBuilder(activity)
                .setView(root)
                .setCancelable(false)
                .create();
        progressDialog.show();
        styleDialogChrome(activity, progressDialog);

        Thread thread = new Thread(() -> {
            try {
                if (repair) {
                    CleanroomCompatibilityManager.Result result = CleanroomMigrationManager.repairCompatibility(
                            activity,
                            instance,
                            (value, message) -> activity.runOnUiThread(() -> updateProgress(activity, progress, status, value, message))
                    );
                    activity.runOnUiThread(() -> {
                        if (progressDialog.isShowing()) progressDialog.dismiss();
                        completion.onFinished(instance, result.getSummary());
                    });
                } else {
                    LauncherInstance updated = CleanroomMigrationManager.migrateToLatest(
                            activity,
                            instance,
                            (value, message) -> activity.runOnUiThread(() -> updateProgress(activity, progress, status, value, message))
                    );
                    activity.runOnUiThread(() -> {
                        if (progressDialog.isShowing()) progressDialog.dismiss();
                        String message = previousMessage.trim().isEmpty()
                                ? "Migrated " + updated.getName() + " to Cleanroom and prepared its mod compatibility."
                                : previousMessage + " Switched the instance to Cleanroom and prepared its mod compatibility.";
                        completion.onFinished(updated, message);
                    });
                }
            } catch (Throwable throwable) {
                Logging.e(TAG, repair ? "Cleanroom compatibility repair failed" : "Cleanroom migration failed", throwable);
                activity.runOnUiThread(() -> {
                    if (progressDialog.isShowing()) progressDialog.dismiss();
                    String reason = throwable.getMessage() == null
                            ? throwable.getClass().getSimpleName()
                            : throwable.getMessage();
                    showActionDialog(
                            activity,
                            repair ? "Cleanroom repair failed" : "Cleanroom migration failed",
                            reason + (repair
                                    ? "\n\nAny partially applied file changes were rolled back."
                                    : "\n\nThe instance loader was not switched. Any partially applied compatibility changes were rolled back."),
                            activity.getString(android.R.string.ok),
                            () -> completion.onFinished(instance, previousMessage.trim().isEmpty()
                                    ? (repair
                                    ? "Cleanroom compatibility repair failed."
                                    : "Cleanroom migration failed; the instance is still using Forge.")
                                    : previousMessage),
                            null,
                            null,
                            null,
                            null,
                            false
                    );
                });
            }
        }, repair ? "Cleanroom Compatibility Repair" : "Cleanroom Migration");
        thread.start();
    }

    private static void showActionDialog(
            @NonNull Activity activity,
            @NonNull String title,
            @NonNull String message,
            @NonNull String positiveLabel,
            @NonNull Runnable positiveAction,
            @Nullable String neutralLabel,
            @Nullable Runnable neutralAction,
            @Nullable String negativeLabel,
            @Nullable Runnable negativeAction,
            boolean cancelable
    ) {
        LinearLayout root = createDialogRoot(activity);
        root.addView(createTitle(activity, title), matchWrap());

        LinearLayout card = addCard(activity, root);
        TextView body = new TextView(activity);
        body.setText(message);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setTextColor(COLOR_TEXT_SECONDARY);
        body.setLineSpacing(0f, 1.08f);
        card.addView(body, matchWrap());

        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(activity)
                .setView(root)
                .setCancelable(cancelable)
                .setPositiveButton(positiveLabel, (dialog, which) -> positiveAction.run());

        if (neutralLabel != null) {
            builder.setNeutralButton(neutralLabel, (dialog, which) -> {
                if (neutralAction != null) neutralAction.run();
            });
        }
        if (negativeLabel != null) {
            builder.setNegativeButton(negativeLabel, (dialog, which) -> {
                if (negativeAction != null) negativeAction.run();
            });
        }

        AlertDialog dialog = builder.create();
        dialog.show();
        styleDialogChrome(activity, dialog);
    }

    @NonNull
    private static LinearLayout createDialogRoot(@NonNull Activity activity) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_DIALOG_BG);
        int padding = dp(activity, 18);
        root.setPadding(padding, padding, padding, dp(activity, 8));
        return root;
    }

    @NonNull
    private static TextView createTitle(@NonNull Activity activity, @NonNull String text) {
        TextView title = new TextView(activity);
        title.setText(text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(COLOR_TEXT_PRIMARY);
        title.setPadding(dp(activity, 2), 0, dp(activity, 2), dp(activity, 12));
        return title;
    }

    @NonNull
    private static LinearLayout addCard(@NonNull Activity activity, @NonNull LinearLayout root) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(activity, 14);
        card.setPadding(padding, padding, padding, padding);
        card.setBackground(roundedDrawable(activity, COLOR_CARD_BG, COLOR_CARD_STROKE, 18));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(activity, 12));
        root.addView(card, params);
        return card;
    }

    private static void updateProgress(
            @NonNull Activity activity,
            @NonNull ProgressBar progress,
            @NonNull TextView status,
            int value,
            @NonNull String message
    ) {
        if (activity.isFinishing()) return;
        progress.setProgress(value);
        status.setText(message);
    }

    private static void styleDialogChrome(@NonNull Activity activity, @NonNull AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(roundedDrawable(activity, COLOR_DIALOG_BG, COLOR_DIALOG_BG, 22));
            window.setDimAmount(DIALOG_DIM);
            View decor = window.getDecorView();
            if (decor != null) {
                decor.setBackgroundColor(COLOR_DIALOG_BG);
                forceKnownAlertPanelsDark(decor);
            }
        }

        tintDialogButton(dialog, AlertDialog.BUTTON_POSITIVE);
        tintDialogButton(dialog, AlertDialog.BUTTON_NEGATIVE);
        tintDialogButton(dialog, AlertDialog.BUTTON_NEUTRAL);
    }

    private static void tintDialogButton(@NonNull AlertDialog dialog, int whichButton) {
        Button button = dialog.getButton(whichButton);
        if (button != null) {
            button.setTextColor(COLOR_ACCENT);
            button.setBackgroundColor(Color.TRANSPARENT);
            button.setAllCaps(false);
        }
    }

    private static void forceKnownAlertPanelsDark(@NonNull View decor) {
        setViewBackgroundIfFound(decor, androidx.appcompat.R.id.parentPanel, COLOR_DIALOG_BG);
        setViewBackgroundIfFound(decor, androidx.appcompat.R.id.topPanel, COLOR_DIALOG_BG);
        setViewBackgroundIfFound(decor, androidx.appcompat.R.id.contentPanel, COLOR_DIALOG_BG);
        setViewBackgroundIfFound(decor, androidx.appcompat.R.id.customPanel, COLOR_DIALOG_BG);
        setViewBackgroundIfFound(decor, androidx.appcompat.R.id.buttonPanel, COLOR_DIALOG_BG);
    }

    private static void setViewBackgroundIfFound(@NonNull View decor, int id, int color) {
        View target = decor.findViewById(id);
        if (target != null) target.setBackgroundColor(color);
    }

    private static void tintProgressBar(@NonNull ProgressBar progressBar) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        progressBar.setProgressTintList(ColorStateList.valueOf(COLOR_ACCENT));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(COLOR_CARD_STROKE));
        progressBar.setIndeterminateTintList(ColorStateList.valueOf(COLOR_ACCENT));
    }

    @NonNull
    private static GradientDrawable roundedDrawable(
            @NonNull Activity activity,
            int fillColor,
            int strokeColor,
            int cornerDp
    ) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(activity, cornerDp));
        drawable.setStroke(dp(activity, 1), strokeColor);
        return drawable;
    }

    @NonNull
    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private static int dp(@NonNull Activity activity, int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                activity.getResources().getDisplayMetrics()
        ));
    }
}

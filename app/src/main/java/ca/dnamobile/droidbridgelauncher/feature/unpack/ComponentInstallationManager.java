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

package ca.dnamobile.droidbridgelauncher.feature.unpack;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.StatFs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

/**
 * Shared, non-blocking component/JRE installation flow.
 *
 * A failed item is attempted once, recorded, and skipped so one broken runtime
 * cannot trap the launcher on its preparation screen. Failed-task state is only
 * a repair hint: if the normal integrity/version check later proves the installed
 * item is healthy, the stale failure marker is cleared automatically.
 */
public final class ComponentInstallationManager {
    private static final String TAG = "ComponentInstaller";
    private static final String PREFS_NAME = "droidbridge_component_installer";
    private static final String KEY_FAILED_TASK_IDS = "failed_task_ids";
    private static final String KEY_INSTALL_ATTEMPTED = "install_attempted";

    /**
     * Current bundles expand to roughly 600 MB. One GB leaves room for the
     * largest temporary JRE extraction, filesystem overhead, and activation.
     */
    public static final long RECOMMENDED_FREE_BYTES = 1024L * 1024L * 1024L;

    private ComponentInstallationManager() {
    }

    public interface Listener {
        void onProgress(int current, int total, @NonNull String itemName, @NonNull String detail);
    }

    public static final class ScanResult {
        private final int totalTaskCount;
        @NonNull private final List<String> missingTaskIds;
        @NonNull private final List<String> missingNames;

        private ScanResult(
                int totalTaskCount,
                @NonNull List<String> missingTaskIds,
                @NonNull List<String> missingNames
        ) {
            this.totalTaskCount = totalTaskCount;
            this.missingTaskIds = Collections.unmodifiableList(new ArrayList<>(missingTaskIds));
            this.missingNames = Collections.unmodifiableList(new ArrayList<>(missingNames));
        }

        public int getTotalTaskCount() {
            return totalTaskCount;
        }

        public int getMissingCount() {
            return missingNames.size();
        }

        public boolean isComplete() {
            return missingNames.isEmpty();
        }

        public boolean looksLikeFirstInstall() {
            return totalTaskCount > 0 && missingNames.size() == totalTaskCount;
        }

        @NonNull
        public List<String> getMissingNames() {
            return missingNames;
        }

        @NonNull
        private Set<String> missingIdSet() {
            return new HashSet<>(missingTaskIds);
        }
    }

    public static final class InstallResult {
        private final int attemptedCount;
        private final int installedCount;
        @NonNull private final List<String> failedNames;
        @NonNull private final ScanResult finalScan;

        private InstallResult(
                int attemptedCount,
                int installedCount,
                @NonNull List<String> failedNames,
                @NonNull ScanResult finalScan
        ) {
            this.attemptedCount = attemptedCount;
            this.installedCount = installedCount;
            this.failedNames = Collections.unmodifiableList(new ArrayList<>(failedNames));
            this.finalScan = finalScan;
        }

        public int getAttemptedCount() {
            return attemptedCount;
        }

        public int getInstalledCount() {
            return installedCount;
        }

        public boolean isComplete() {
            return failedNames.isEmpty() && finalScan.isComplete();
        }

        @NonNull
        public List<String> getFailedNames() {
            return failedNames;
        }

        @NonNull
        public ScanResult getFinalScan() {
            return finalScan;
        }

        @NonNull
        public List<String> getAllMissingNames() {
            LinkedHashSet<String> names = new LinkedHashSet<>(failedNames);
            names.addAll(finalScan.getMissingNames());
            return new ArrayList<>(names);
        }
    }

    @NonNull
    public static synchronized ScanResult scan(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        PathManager.initContextConstants(appContext);

        List<TaskEntry> tasks = buildTasks(appContext);
        Set<String> rememberedFailures = readFailedTaskIds(appContext);
        ArrayList<String> missingIds = new ArrayList<>();
        ArrayList<String> missingNames = new ArrayList<>();

        boolean failureStateChanged = false;
        for (TaskEntry entry : tasks) {
            boolean missing;
            try {
                missing = entry.task.isNeedUnpack();
            } catch (Throwable throwable) {
                missing = true;
                Logging.e(TAG, "Unable to verify " + entry.name + "; treating it as missing", throwable);
            }

            if (missing) {
                missingIds.add(entry.id);
                missingNames.add(entry.name);
            } else if (rememberedFailures.remove(entry.id)) {
                // A previous install/reinstall may have failed after leaving the old
                // known-good copy active. Do not let that stale preference override
                // the component/JRE's real health check forever.
                failureStateChanged = true;
                Logging.i(TAG, "Cleared stale failed-task marker for verified item: " + entry.name);
            }
        }

        if (failureStateChanged) {
            writeFailedTaskIds(appContext, rememberedFailures);
        }

        return new ScanResult(tasks.size(), missingIds, missingNames);
    }

    @NonNull
    public static InstallResult installMissing(
            @NonNull Context context,
            @Nullable Listener listener
    ) {
        return install(context, false, listener);
    }

    @NonNull
    public static InstallResult reinstallAll(
            @NonNull Context context,
            @Nullable Listener listener
    ) {
        return install(context, true, listener);
    }

    public static boolean hasAttemptedInstall(@NonNull Context context) {
        return preferences(context).getBoolean(KEY_INSTALL_ATTEMPTED, false);
    }

    public static long getAvailableBytes(@NonNull Context context) {
        try {
            File filesDir = context.getApplicationContext().getFilesDir();
            StatFs statFs = new StatFs(filesDir.getAbsolutePath());
            return statFs.getAvailableBytes();
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to read available storage", throwable);
            return -1L;
        }
    }

    @NonNull
    private static synchronized InstallResult install(
            @NonNull Context context,
            boolean forceAll,
            @Nullable Listener listener
    ) {
        Context appContext = context.getApplicationContext();
        PathManager.initContextConstants(appContext);

        ScanResult initialScan = scan(appContext);
        Set<String> selectedIds = forceAll
                ? null
                : initialScan.missingIdSet();
        List<TaskEntry> allTasks = buildTasks(appContext);
        ArrayList<TaskEntry> selectedTasks = new ArrayList<>();
        for (TaskEntry entry : allTasks) {
            if (forceAll || selectedIds.contains(entry.id)) selectedTasks.add(entry);
        }

        preferences(appContext).edit().putBoolean(KEY_INSTALL_ATTEMPTED, true).apply();

        // Mark every selected item before starting it. If Android kills the app in
        // the middle of extraction, the next launch will still offer a repair.
        Set<String> failures = readFailedTaskIds(appContext);
        for (TaskEntry entry : selectedTasks) failures.add(entry.id);
        writeFailedTaskIds(appContext, failures);

        ArrayList<String> failedNames = new ArrayList<>();
        int installedCount = 0;
        int total = selectedTasks.size() + 1; // final single-file preparation
        int current = 0;

        for (TaskEntry entry : selectedTasks) {
            current++;
            final int progressIndex = current;
            if (listener != null) {
                listener.onProgress(progressIndex, total, entry.name, "Installing " + entry.name + "...");
            }

            entry.task.setListener(new AbstractUnpackTask.Listener() {
                @Override
                public void onTaskProgress(String message) {
                    if (listener != null) {
                        listener.onProgress(progressIndex, total, entry.name, message);
                    }
                }
            });

            try {
                entry.task.run();
                installedCount++;
                failures.remove(entry.id);
                writeFailedTaskIds(appContext, failures);
                Logging.i(TAG, "Installed " + entry.name + " successfully");
            } catch (Throwable throwable) {
                failedNames.add(entry.name);
                failures.add(entry.id);
                writeFailedTaskIds(appContext, failures);
                Logging.e(TAG, "Skipping failed item after one attempt: " + entry.name, throwable);
            }
        }

        current++;
        if (listener != null) {
            listener.onProgress(current, total, "Launcher files", "Finalizing launcher files...");
        }
        try {
            new UnpackSingleFilesTask(appContext).run();
        } catch (Throwable throwable) {
            failedNames.add("Launcher support files");
            Logging.e(TAG, "Single launcher file preparation failed", throwable);
        }

        ScanResult finalScan = scan(appContext);
        return new InstallResult(selectedTasks.size(), installedCount, failedNames, finalScan);
    }

    @NonNull
    private static List<TaskEntry> buildTasks(@NonNull Context context) {
        ArrayList<TaskEntry> tasks = new ArrayList<>();

        for (Components component : Components.values()) {
            try {
                tasks.add(new TaskEntry(
                        "component:" + component.name(),
                        component.displayName,
                        new UnpackComponentsTask(context, component)
                ));
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to create component task for " + component.displayName, throwable);
            }
        }

        for (Jre jre : Jre.values()) {
            try {
                tasks.add(new TaskEntry(
                        "jre:" + jre.name(),
                        jre.jreName,
                        new UnpackJreTask(context, jre)
                ));
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to create runtime task for " + jre.jreName, throwable);
            }
        }

        return tasks;
    }

    @NonNull
    private static SharedPreferences preferences(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @NonNull
    private static Set<String> readFailedTaskIds(@NonNull Context context) {
        Set<String> stored = preferences(context).getStringSet(KEY_FAILED_TASK_IDS, Collections.emptySet());
        return stored != null ? new HashSet<>(stored) : new HashSet<>();
    }

    private static void writeFailedTaskIds(@NonNull Context context, @NonNull Set<String> ids) {
        if (!preferences(context).edit().putStringSet(KEY_FAILED_TASK_IDS, new HashSet<>(ids)).commit()) {
            Logging.e(TAG, "Unable to persist failed component list", null);
        }
    }

    private static final class TaskEntry {
        @NonNull final String id;
        @NonNull final String name;
        @NonNull final AbstractUnpackTask task;

        TaskEntry(
                @NonNull String id,
                @NonNull String name,
                @NonNull AbstractUnpackTask task
        ) {
            this.id = id;
            this.name = name;
            this.task = task;
        }
    }
}

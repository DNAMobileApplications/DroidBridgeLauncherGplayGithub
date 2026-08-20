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

package ca.dnamobile.droidbridgelauncher.instance;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.storage.StorageLocationStore;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

/**
 * Fast instance deletion helper.
 *
 * The old delete flow recursively deleted the target first, then updated the UI.
 * Large modpacks/saves can contain thousands of files, so the launcher looked frozen.
 * This helper first moves the instance/version folder into .minecraft/.pending_delete,
 * which removes it from the visible instance list almost instantly, then the slow
 * recursive delete and optional SAF cleanup happen in the background.
 */
public final class LauncherInstanceDeleteManager {
    private static final String TAG = "InstanceDelete";
    private static final String PENDING_DELETE_DIR = ".pending_delete";

    private LauncherInstanceDeleteManager() {
    }

    public static final class DeleteJob {
        private final String instanceId;
        private final File originalTarget;
        private final File deleteTarget;
        private final boolean movedOutOfVisibleList;

        private DeleteJob(
                @NonNull String instanceId,
                @NonNull File originalTarget,
                @NonNull File deleteTarget,
                boolean movedOutOfVisibleList
        ) {
            this.instanceId = instanceId;
            this.originalTarget = originalTarget;
            this.deleteTarget = deleteTarget;
            this.movedOutOfVisibleList = movedOutOfVisibleList;
        }

        @NonNull
        public File getOriginalTarget() {
            return originalTarget;
        }

        @NonNull
        public File getDeleteTarget() {
            return deleteTarget;
        }

        public boolean wasMovedOutOfVisibleList() {
            return movedOutOfVisibleList;
        }
    }

    @NonNull
    public static DeleteJob hideForDeletion(
            @NonNull Context context,
            @NonNull LauncherInstance instance
    ) throws Exception {
        return hideForDeletion(
                context,
                instance.getId(),
                instance.getBaseVersionId(),
                instance.getRootDirectory(),
                instance.isIsolated()
        );
    }

    @NonNull
    public static DeleteJob hideForDeletion(
            @NonNull Context context,
            @NonNull String instanceId,
            @NonNull String baseVersionId,
            @NonNull File rootDirectory,
            boolean isolated
    ) throws Exception {
        PathManager.initContextConstants(context);

        File target = LauncherInstanceManager
                .getDeleteTargetDirectory(baseVersionId, rootDirectory, isolated)
                .getCanonicalFile();

        validateDeleteTarget(context, target, baseVersionId, rootDirectory, isolated);

        if (!target.exists()) {
            return new DeleteJob(instanceId, target, target, true);
        }

        File trashTarget = buildTrashTarget(target, rootDirectory, isolated);
        File trashParent = trashTarget.getParentFile();
        if (trashParent == null) {
            throw new IllegalStateException("Missing delete staging parent for: " + trashTarget.getAbsolutePath());
        }
        if (!trashParent.exists() && !trashParent.mkdirs()) {
            throw new IllegalStateException("Unable to create delete staging folder: " + trashParent.getAbsolutePath());
        }

        if (target.renameTo(trashTarget)) {
            Logging.i(TAG, "Moved instance out of visible list: "
                    + target.getAbsolutePath() + " -> " + trashTarget.getAbsolutePath());
            return new DeleteJob(instanceId, target, trashTarget.getCanonicalFile(), true);
        }

        Logging.i(TAG, "Unable to move instance before delete, falling back to direct delete: "
                + target.getAbsolutePath());
        return new DeleteJob(instanceId, target, target, false);
    }

    public static void finishDeletion(
            @NonNull Context context,
            @NonNull DeleteJob job
    ) throws Exception {
        deleteRecursively(job.getDeleteTarget());

        try {
            boolean deletedScopedCopy = StorageLocationStore.deleteFromScopedStorageIfNeeded(
                    context,
                    job.getOriginalTarget()
            );
            if (deletedScopedCopy) {
                Logging.i(TAG, "Deleted scoped-storage copy for instance "
                        + job.instanceId + " at " + job.getOriginalTarget().getAbsolutePath());
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to delete scoped-storage copy for instance " + job.instanceId, throwable);
            throw new IllegalStateException(
                    "Local instance was removed, but the scoped-storage copy could not be deleted: "
                            + readableError(throwable),
                    throwable
            );
        }

        Logging.i(TAG, "Finished deleting instance " + job.instanceId
                + " at " + job.getOriginalTarget().getAbsolutePath());
    }

    public static void cleanupPendingDeletesAsync(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                for (File minecraftHome : StorageLocationStore.getAllMinecraftHomes(appContext)) {
                    cleanupPendingDeletes(minecraftHome);
                }
            } catch (Throwable throwable) {
                Logging.i(TAG, "Pending delete cleanup failed: " + readableError(throwable));
            }
        }, "Cleanup Pending Instance Deletes").start();
    }

    private static void cleanupPendingDeletes(@Nullable File minecraftHome) {
        if (minecraftHome == null) return;
        File pendingRoot = new File(minecraftHome, PENDING_DELETE_DIR);
        if (!pendingRoot.isDirectory()) return;

        File[] children = pendingRoot.listFiles();
        if (children == null) return;

        for (File child : children) {
            try {
                deleteRecursively(child);
            } catch (Throwable throwable) {
                Logging.i(TAG, "Unable to cleanup pending delete "
                        + child.getAbsolutePath() + ": " + readableError(throwable));
            }
        }

        //noinspection ResultOfMethodCallIgnored
        pendingRoot.delete();
    }

    private static void validateDeleteTarget(
            @NonNull Context context,
            @NonNull File target,
            @NonNull String baseVersionId,
            @NonNull File rootDirectory,
            boolean isolated
    ) throws Exception {
        if (isolated) {
            File instancesRoot = rootDirectory.getParentFile() != null
                    ? rootDirectory.getParentFile().getCanonicalFile()
                    : LauncherInstanceManager.getInstancesRoot().getCanonicalFile();
            if (target.equals(instancesRoot) || !isChildOf(instancesRoot, target)) {
                throw new IllegalStateException("Refusing to delete unsafe instance path: "
                        + target.getAbsolutePath());
            }
            return;
        }

        File versionsRoot = new File(rootDirectory, "versions").getCanonicalFile();
        File parent = target.getParentFile() != null ? target.getParentFile().getCanonicalFile() : null;
        if (parent == null || !versionsRoot.equals(parent)) {
            throw new IllegalStateException("Refusing to delete unsafe shared version path: "
                    + target.getAbsolutePath());
        }

        ensureSharedVersionIsNotRequired(context, baseVersionId);
    }

    private static void ensureSharedVersionIsNotRequired(
            @NonNull Context context,
            @NonNull String versionId
    ) {
        ArrayList<String> dependents = LauncherInstanceManager.findSharedVersionDependents(context, versionId);
        dependents.addAll(LauncherInstanceManager.findIsolatedInstanceDependents(context, versionId));

        if (!dependents.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot delete shared version " + versionId
                            + " because it is required by:\n"
                            + LauncherInstanceManager.formatDependentVersionList(dependents)
                            + "\nDelete those instances/loader versions first, or keep this shared version installed."
            );
        }
    }

    @NonNull
    private static File buildTrashTarget(
            @NonNull File target,
            @NonNull File rootDirectory,
            boolean isolated
    ) throws Exception {
        File minecraftHome;
        if (isolated) {
            File instancesRoot = target.getParentFile();
            minecraftHome = instancesRoot != null ? instancesRoot.getParentFile() : null;
        } else {
            minecraftHome = rootDirectory;
        }

        if (minecraftHome == null) {
            throw new IllegalStateException("Unable to resolve Minecraft home for delete staging.");
        }

        File trashRoot = new File(minecraftHome.getCanonicalFile(), PENDING_DELETE_DIR);
        String prefix = isolated ? "instance-" : "version-";
        String safeName = target.getName().replaceAll("[^A-Za-z0-9._-]", "_");
        File candidate = new File(trashRoot, prefix + safeName + "-" + System.currentTimeMillis());

        int suffix = 2;
        while (candidate.exists()) {
            candidate = new File(trashRoot, prefix + safeName + "-" + System.currentTimeMillis() + "-" + suffix++);
        }
        return candidate;
    }

    private static boolean isChildOf(@NonNull File parent, @NonNull File child) throws Exception {
        String parentPath = parent.getCanonicalPath();
        String childPath = child.getCanonicalPath();
        return childPath.startsWith(parentPath + File.separator);
    }

    private static void deleteRecursively(@NonNull File file) throws Exception {
        if (!file.exists()) return;

        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }

        if (!file.delete() && file.exists()) {
            throw new IllegalStateException("Unable to delete: " + file.getAbsolutePath());
        }
    }

    @NonNull
    private static String readableError(@NonNull Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName()
                : message;
    }
}

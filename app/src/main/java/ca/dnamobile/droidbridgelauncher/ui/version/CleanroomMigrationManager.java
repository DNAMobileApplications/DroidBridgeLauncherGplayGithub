/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */
package ca.dnamobile.droidbridgelauncher.ui.version;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.data.model.MinecraftVersion;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstance;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstanceManager;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

/** Converts an existing isolated Forge 1.12.2 instance to Cleanroom in place. */
public final class CleanroomMigrationManager {
    private static final String TAG = "CleanroomMigration";

    private CleanroomMigrationManager() {
    }

    public interface ProgressListener {
        void onProgress(int progress, @NonNull String message);
    }

    public static boolean isEligible(@Nullable LauncherInstance instance) {
        if (instance == null || !instance.isIsolated()) return false;
        if (!CleanroomSupport.supportsMinecraftVersion(instance.getMinecraftVersionId())) return false;
        if (CleanroomSupport.LOADER_NAME.equalsIgnoreCase(instance.getLoader())) return false;

        String loader = instance.getLoader() == null ? "" : instance.getLoader().trim().toLowerCase(Locale.US);
        return loader.contains("forge") && !loader.contains("neoforge") && !loader.contains("neo forge");
    }

    @NonNull
    public static LauncherInstance migrateToLatest(
            @NonNull Context context,
            @NonNull LauncherInstance instance,
            @Nullable ProgressListener listener
    ) throws Exception {
        return migrate(context, instance, null, listener);
    }

    @NonNull
    public static LauncherInstance migrate(
            @NonNull Context context,
            @NonNull LauncherInstance instance,
            @Nullable String requestedCleanroomVersion,
            @Nullable ProgressListener listener
    ) throws Exception {
        PathManager.initContextConstants(context);
        if (!isEligible(instance)) {
            throw new IllegalArgumentException("Only isolated Forge 1.12.2 instances can be migrated to Cleanroom.");
        }

        notify(listener, 3, "Finding a compatible Cleanroom version...");
        String cleanroomVersion = requestedCleanroomVersion == null ? "" : requestedCleanroomVersion.trim();
        if (cleanroomVersion.isEmpty()) {
            ArrayList<LoaderVersionResolver.LoaderVersionOption> versions =
                    LoaderVersionResolver.resolveVersions(CleanroomSupport.LOADER_NAME, CleanroomSupport.MINECRAFT_VERSION);
            for (LoaderVersionResolver.LoaderVersionOption option : versions) {
                if (option != null && option.loaderVersion != null && !option.loaderVersion.trim().isEmpty()) {
                    cleanroomVersion = option.loaderVersion.trim();
                    break;
                }
            }
        }
        if (cleanroomVersion.isEmpty()) {
            throw new IllegalStateException("No Cleanroom versions were returned by the metadata service.");
        }

        MinecraftVersion vanilla = findMinecraftVersion(context, CleanroomSupport.MINECRAFT_VERSION);
        if (vanilla == null) {
            // CleanroomInstaller only needs the installed vanilla version id here.
            vanilla = new MinecraftVersion(CleanroomSupport.MINECRAFT_VERSION, "release", "", "");
        }

        final String selectedVersion = cleanroomVersion;
        notify(listener, 8, "Installing Cleanroom " + selectedVersion + "...");
        CleanroomInstaller.InstallResult result = CleanroomInstaller.installCleanroomVersion(
                context,
                vanilla,
                selectedVersion,
                (progress, message) -> {
                    int mapped = 8 + ((Math.max(0, Math.min(100, progress)) * 47) / 100);
                    notify(listener, mapped, message);
                }
        );

        notify(listener, 56, "Preparing this modpack for Cleanroom...");
        CleanroomCompatibilityManager.Result compatibility = CleanroomCompatibilityManager.prepare(
                context,
                instance,
                (progress, message) -> {
                    int mapped = 56 + ((Math.max(0, Math.min(100, progress)) * 38) / 100);
                    notify(listener, mapped, message);
                }
        );

        notify(listener, 95, "Switching this instance to Cleanroom...");
        LauncherInstance updated;
        try {
            updated = LauncherInstanceManager.updateInstanceLoaderProfile(
                    context,
                    instance.getRootDirectory(),
                    CleanroomSupport.LOADER_NAME,
                    result.getCleanroomVersionId(),
                    CleanroomSupport.MINECRAFT_VERSION
            );
        } catch (Throwable throwable) {
            CleanroomCompatibilityManager.rollback(instance, compatibility);
            throw throwable;
        }
        writeMigrationRecord(instance, updated, result, compatibility);
        notify(listener, 100, "Cleanroom " + result.getLoaderVersion() + " is ready with Java "
                + result.getRequiredJava() + ". " + compatibility.getSummary());
        return updated;
    }

    @NonNull
    public static CleanroomCompatibilityManager.Result repairCompatibility(
            @NonNull Context context,
            @NonNull LauncherInstance instance,
            @Nullable ProgressListener listener
    ) throws Exception {
        PathManager.initContextConstants(context);
        if (!CleanroomCompatibilityManager.isRepairEligible(instance)) {
            throw new IllegalArgumentException("Only isolated Cleanroom 1.12.2 instances can be repaired.");
        }
        notify(listener, 1, "Scanning this Cleanroom instance...");
        CleanroomCompatibilityManager.Result result = CleanroomCompatibilityManager.prepare(
                context,
                instance,
                (progress, message) -> notify(listener, Math.max(1, Math.min(99, progress)), message)
        );
        notify(listener, 100, result.getSummary());
        return result;
    }

    @Nullable
    private static MinecraftVersion findMinecraftVersion(@NonNull Context context, @NonNull String id) {
        try {
            for (MinecraftVersion version : MinecraftVersionManifestClient.loadVersions(context)) {
                if (id.equals(version.getId())) return version;
            }
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to read Minecraft version manifest: " + throwable.getMessage());
        }
        return null;
    }

    private static void writeMigrationRecord(
            @NonNull LauncherInstance before,
            @NonNull LauncherInstance after,
            @NonNull CleanroomInstaller.InstallResult result,
            @NonNull CleanroomCompatibilityManager.Result compatibility
    ) {
        try {
            File metadataDirectory = new File(after.getRootDirectory(), "metadata");
            if (!metadataDirectory.exists() && !metadataDirectory.mkdirs() && !metadataDirectory.isDirectory()) return;

            JSONObject json = new JSONObject();
            json.put("schema", 2);
            json.put("migratedAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date()));
            json.put("previousLoader", before.getLoader());
            json.put("previousBaseVersionId", before.getBaseVersionId());
            json.put("minecraftVersionId", CleanroomSupport.MINECRAFT_VERSION);
            json.put("cleanroomVersion", result.getLoaderVersion());
            json.put("cleanroomProfileId", result.getCleanroomVersionId());
            json.put("requiredJava", result.getRequiredJava());
            json.put("gameDirectoryPreserved", after.getGameDirectory().getAbsolutePath());
            json.put("compatibilityProfile", compatibility.getProfileName());
            json.put("compatibilityBackupDirectory", compatibility.backupDirectory == null
                    ? JSONObject.NULL
                    : compatibility.backupDirectory.getAbsolutePath());
            json.put("compatibilityDisabledFiles", compatibility.disabledFiles.size());
            json.put("compatibilityInstalledFiles", compatibility.installedFiles.size());

            File record = new File(metadataDirectory, "cleanroom_migration.json");
            try (FileOutputStream output = new FileOutputStream(record)) {
                output.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to write Cleanroom migration record: " + throwable.getMessage());
        }
    }

    private static void notify(
            @Nullable ProgressListener listener,
            int progress,
            @NonNull String message
    ) {
        Logging.i(TAG, message);
        if (listener != null) listener.onProgress(Math.max(0, Math.min(100, progress)), message);
    }
}

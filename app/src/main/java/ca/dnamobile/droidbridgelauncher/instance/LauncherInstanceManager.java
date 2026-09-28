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
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.modmanager.ModManagerContentType;
import ca.dnamobile.droidbridgelauncher.modmanager.ModManagerVersionResolver;
import ca.dnamobile.droidbridgelauncher.storage.StorageLocationStore;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

public final class LauncherInstanceManager {
    private static final String TAG = "InstanceManager";
    private static final String METADATA_FILE = "instance.json";

    private LauncherInstanceManager() {
    }

    @NonNull
    public static File getInstancesRoot() {
        return getInstancesRoot(new File(PathManager.DIR_MINECRAFT_HOME));
    }

    @NonNull
    public static File getInstancesRoot(@NonNull File minecraftHome) {
        return new File(minecraftHome, "instances");
    }

    @NonNull
    public static LauncherInstance createInstance(
            @NonNull Context context,
            @NonNull String requestedName,
            @NonNull String loader,
            @NonNull String baseVersionId,
            @NonNull String versionType,
            @Nullable Uri iconUri
    ) throws Exception {
        // Safe default: do not seed launcher-owned options.txt here.
        // Modpack installs and copied instances must be allowed to provide their own options.txt.
        return createInstance(context, requestedName, loader, baseVersionId, baseVersionId, versionType, iconUri, false);
    }

    /**
     * Creates an isolated instance.
     *
     * seedDefaultOptions should only be true for a clean launcher-created vanilla/loader
     * instance where you intentionally want DroidBridge to add its Android-friendly defaults.
     * Keep this false for modpack imports, instance copies, restores, and any flow where files
     * are copied into the game directory after the instance folder is created.
     */
    @NonNull
    public static LauncherInstance createInstance(
            @NonNull Context context,
            @NonNull String requestedName,
            @NonNull String loader,
            @NonNull String baseVersionId,
            @NonNull String versionType,
            @Nullable Uri iconUri,
            boolean seedDefaultOptions
    ) throws Exception {
        return createInstance(context, requestedName, loader, baseVersionId, baseVersionId, versionType, iconUri, seedDefaultOptions);
    }

    @NonNull
    public static LauncherInstance createInstance(
            @NonNull Context context,
            @NonNull String requestedName,
            @NonNull String loader,
            @NonNull String baseVersionId,
            @NonNull String minecraftVersionId,
            @NonNull String versionType,
            @Nullable Uri iconUri
    ) throws Exception {
        // Safe default: do not seed launcher-owned options.txt here.
        return createInstance(context, requestedName, loader, baseVersionId, minecraftVersionId, versionType, iconUri, false);
    }

    /**
     * Creates an isolated instance. Use seedDefaultOptions=true only for a brand-new
     * vanilla/loader instance. Modpack/copy/import flows must pass false.
     */
    @NonNull
    public static LauncherInstance createInstance(
            @NonNull Context context,
            @NonNull String requestedName,
            @NonNull String loader,
            @NonNull String baseVersionId,
            @NonNull String minecraftVersionId,
            @NonNull String versionType,
            @Nullable Uri iconUri,
            boolean seedDefaultOptions
    ) throws Exception {
        PathManager.initContextConstants(context);

        String cleanMinecraftVersionId = minecraftVersionId.trim().isEmpty()
                ? ModManagerVersionResolver.resolveGameVersionForContent(baseVersionId)
                : minecraftVersionId.trim();
        if (cleanMinecraftVersionId.isEmpty()) cleanMinecraftVersionId = baseVersionId;

        String name = cleanDisplayName(requestedName, loader, baseVersionId);
        String baseId = uniqueIdForName(name);
        File root = createUniqueInstanceRoot(baseId);
        // The root name is guaranteed unique inside the instances directory. Persist that
        // exact unique value as the instance id; using baseId here caused two same-named
        // instances (for example two Fabric 1.21.11 installs) to share per-instance prefs.
        String id = root.getName();

        File gameDir = new File(root, "game");
        ensureDirectory(gameDir);
        ensureDirectory(new File(gameDir, "saves"));
        ensureDirectory(new File(gameDir, ModManagerContentType.getResourcePackFolderName(cleanMinecraftVersionId)));
        ensureDirectory(new File(gameDir, "shaderpacks"));
        ensureDirectory(new File(gameDir, "mods"));
        ensureDirectory(new File(gameDir, "config"));
        ensureDirectory(new File(gameDir, "logs"));
        ensureDirectory(new File(root, "metadata"));

        File iconFile = null;
        if (iconUri != null) {
            iconFile = new File(root, "icon");
            copyUri(context, iconUri, iconFile);
        }

        String createdAt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date());

        JSONObject json = new JSONObject();
        json.put("schema", 1);
        json.put("id", id);
        json.put("name", name);
        json.put("loader", loader);
        json.put("baseVersionId", baseVersionId);
        json.put("minecraftVersionId", cleanMinecraftVersionId);
        json.put("versionType", versionType);
        json.put("rootDirectory", root.getAbsolutePath());
        json.put("gameDirectory", gameDir.getAbsolutePath());
        json.put("iconFile", iconFile != null ? iconFile.getAbsolutePath() : "");
        json.put("createdAt", createdAt);
        json.put("storageMode", "storage_location");
        json.put("launcherHome", PathManager.DIR_GAME_HOME);
        json.put("minecraftHome", PathManager.DIR_MINECRAFT_HOME);
        json.put("note", "Shared game files live under this storage location's .minecraft/versions/libraries/assets. This directory isolates saves/options/mods/" + ModManagerContentType.getResourcePackFolderName(cleanMinecraftVersionId) + " for this launcher instance.");

        writeString(new File(root, METADATA_FILE), json.toString(2));

        if (seedDefaultOptions) {
            DefaultMinecraftOptionsInstaller.installIfMissingForNewInstance(
                    context,
                    gameDir,
                    cleanMinecraftVersionId
            );
        }

        Logging.i(TAG, "Created instance " + name
                + " id=" + id
                + " loader=" + loader
                + " baseVersion=" + baseVersionId
                + " minecraftVersion=" + cleanMinecraftVersionId
                + " type=" + versionType
                + " root=" + root.getAbsolutePath()
                + (iconFile == null ? "" : " customIcon=" + iconFile.getAbsolutePath()));

        return new LauncherInstance(
                id,
                name,
                loader,
                baseVersionId,
                cleanMinecraftVersionId,
                versionType,
                root,
                gameDir,
                iconFile,
                createdAt
        );
    }

    @NonNull
    public static ArrayList<LauncherInstance> findInstances(@NonNull Context context) {
        ArrayList<LauncherInstance> result = new ArrayList<>();
        HashSet<String> seenIds = new HashSet<>();

        for (File minecraftHome : StorageLocationStore.getVisibleMinecraftHomes(context)) {
            File root = getInstancesRoot(minecraftHome);
            File[] children = root.listFiles();
            if (children == null) continue;

            for (File child : children) {
                if (!child.isDirectory()) continue;
                File jsonFile = new File(child, METADATA_FILE);
                if (!jsonFile.isFile()) continue;

                try {
                    LauncherInstance instance = readInstance(jsonFile);
                    String uniqueKey = instance.getId() + "@" + instance.getRootDirectory().getAbsolutePath();
                    if (seenIds.add(uniqueKey)) result.add(instance);
                } catch (Throwable throwable) {
                    Logging.i(TAG, "Skipping broken instance " + child.getAbsolutePath() + ": " + throwable.getMessage());
                }
            }
        }

        result.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return result;
    }

    @Nullable
    public static LauncherInstance findByNameOrId(@NonNull Context context, @NonNull String nameOrId) {
        for (LauncherInstance instance : findInstances(context)) {
            if (instance.getId().equals(nameOrId) || instance.getName().equals(nameOrId)) {
                return instance;
            }
        }
        return null;
    }
    @NonNull
    public static LauncherInstance renameInstance(
            @NonNull Context context,
            @NonNull File rootDirectory,
            @NonNull String requestedName
    ) throws Exception {
        PathManager.initContextConstants(context);

        File jsonFile = resolveMetadataFile(rootDirectory);
        JSONObject json = new JSONObject(readString(jsonFile));
        File actualRoot = requireParent(jsonFile);

        String id = json.optString("id", actualRoot.getName());
        String loader = json.optString("loader", "Vanilla");
        String baseVersionId = json.optString("baseVersionId", "");
        String minecraftVersionId = resolveStoredMinecraftVersionId(json, baseVersionId, actualRoot);
        String name = cleanDisplayName(requestedName, loader, baseVersionId);
        if (name.trim().isEmpty()) {
            throw new IllegalArgumentException("Instance name is empty.");
        }

        for (LauncherInstance existing : findInstances(context)) {
            boolean sameInstance = existing.getId().equals(id)
                    || safeCanonicalPath(existing.getRootDirectory()).equals(safeCanonicalPath(actualRoot));
            if (!sameInstance && existing.getName().equalsIgnoreCase(name)) {
                throw new IllegalStateException("An instance named " + name + " already exists.");
            }
        }

        File gameDir = resolveStoredGameDirectory(json, actualRoot);
        File iconFile = resolveStoredIconFile(json, actualRoot);

        json.put("id", id);
        json.put("name", name);
        json.put("minecraftVersionId", minecraftVersionId);
        json.put("rootDirectory", actualRoot.getAbsolutePath());
        json.put("gameDirectory", gameDir.getAbsolutePath());
        json.put("iconFile", iconFile != null ? iconFile.getAbsolutePath() : "");

        writeString(jsonFile, json.toString(2));
        return readInstance(jsonFile);
    }

    /** Updates only the launch-loader metadata for an existing isolated instance.
     * The game directory is preserved exactly as-is so modpack files never need to be copied.
     */
    @NonNull
    public static LauncherInstance updateInstanceLoaderProfile(
            @NonNull Context context,
            @NonNull File rootDirectory,
            @NonNull String loader,
            @NonNull String baseVersionId,
            @NonNull String minecraftVersionId
    ) throws Exception {
        PathManager.initContextConstants(context);

        File jsonFile = resolveMetadataFile(rootDirectory);
        JSONObject json = new JSONObject(readString(jsonFile));
        File actualRoot = requireParent(jsonFile);

        String previousLoader = json.optString("loader", "Vanilla");
        String previousBaseVersionId = json.optString("baseVersionId", "");
        String previousMinecraftVersionId = resolveStoredMinecraftVersionId(json, previousBaseVersionId, actualRoot);

        if ("Cleanroom".equalsIgnoreCase(loader) && !json.has("cleanroomMigration")) {
            JSONObject migration = new JSONObject();
            migration.put("previousLoader", previousLoader);
            migration.put("previousBaseVersionId", previousBaseVersionId);
            migration.put("previousMinecraftVersionId", previousMinecraftVersionId);
            migration.put("migratedAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date()));
            json.put("cleanroomMigration", migration);
        }

        json.put("loader", loader);
        json.put("baseVersionId", baseVersionId);
        json.put("minecraftVersionId", minecraftVersionId);
        json.put("rootDirectory", actualRoot.getAbsolutePath());
        json.put("gameDirectory", resolveStoredGameDirectory(json, actualRoot).getAbsolutePath());
        json.put("updatedAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date()));

        writeString(jsonFile, json.toString(2));
        Logging.i(TAG, "Updated instance loader profile root=" + actualRoot.getAbsolutePath()
                + " loader=" + previousLoader + " -> " + loader
                + " baseVersion=" + previousBaseVersionId + " -> " + baseVersionId
                + " minecraftVersion=" + minecraftVersionId);
        return readInstance(jsonFile);
    }

    /**
     * Copies a picked icon into the instance folder and persists the icon path.
     */
    @NonNull
    public static LauncherInstance updateInstanceIcon(
            @NonNull Context context,
            @NonNull File rootDirectory,
            @NonNull Uri iconUri
    ) throws Exception {
        PathManager.initContextConstants(context);

        File jsonFile = resolveMetadataFile(rootDirectory);
        JSONObject json = new JSONObject(readString(jsonFile));
        File actualRoot = requireParent(jsonFile);
        File gameDir = resolveStoredGameDirectory(json, actualRoot);

        File oldIcon = resolveStoredIconFile(json, actualRoot);
        File target = new File(actualRoot, "icon" + resolveImageExtension(context, iconUri));
        copyUri(context, iconUri, target);

        if (oldIcon != null
                && !safeCanonicalPath(oldIcon).equals(safeCanonicalPath(target))
                && isChildOf(actualRoot, oldIcon)
                && oldIcon.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            oldIcon.delete();
        }

        json.put("rootDirectory", actualRoot.getAbsolutePath());
        json.put("gameDirectory", gameDir.getAbsolutePath());
        json.put("iconFile", target.getAbsolutePath());

        writeString(jsonFile, json.toString(2));
        return readInstance(jsonFile);
    }

    /**
     * Returns shared versions that directly inherit from the supplied base version.
     *
     * Loader versions such as Forge/Fabric usually keep their own JSON under
     * .minecraft/versions/<loaderVersion>, but that JSON points back to the vanilla
     * version through "inheritsFrom". Deleting the vanilla parent breaks those
     * loader launches, so shared vanilla folders must be protected while dependents
     * exist.
     */
    @NonNull
    public static ArrayList<String> findSharedVersionDependents(
            @NonNull Context context,
            @NonNull String versionId
    ) {
        PathManager.initContextConstants(context);

        ArrayList<String> dependents = new ArrayList<>();
        if (versionId.trim().isEmpty()) return dependents;

        for (File minecraftHome : StorageLocationStore.getVisibleMinecraftHomes(context)) {
            File versionsRoot = new File(minecraftHome, "versions");
            File[] children = versionsRoot.listFiles();
            if (children == null) continue;

            for (File child : children) {
                if (!child.isDirectory()) continue;

                String childVersionId = child.getName();
                if (versionId.equals(childVersionId)) continue;

                File jsonFile = new File(child, childVersionId + ".json");
                if (!jsonFile.isFile()) continue;

                try {
                    JSONObject json = new JSONObject(readString(jsonFile));
                    String inheritsFrom = json.optString("inheritsFrom", "");
                    if (versionId.equals(inheritsFrom)) {
                        String displayId = json.optString("id", childVersionId);
                        dependents.add((displayId.isEmpty() ? childVersionId : displayId)
                                + " (" + minecraftHome.getAbsolutePath() + ")");
                    }
                } catch (Throwable throwable) {
                    Logging.i(TAG, "Unable to inspect shared version dependency for "
                            + childVersionId + ": " + throwable.getMessage());
                }
            }
        }

        dependents.sort(String::compareToIgnoreCase);
        return dependents;
    }

    /**
     * Returns isolated launcher instances that directly launch the supplied shared version
     * or inherit from it through their installed loader profile.
     *
     * This protects the hidden/shared version cache used by isolated instances. Without
     * this check, deleting a visible shared Forge/Fabric/Vanilla folder can break an
     * isolated instance that still points at that version id.
     */
    @NonNull
    public static ArrayList<String> findIsolatedInstanceDependents(
            @NonNull Context context,
            @NonNull String versionId
    ) {
        PathManager.initContextConstants(context);

        ArrayList<String> dependents = new ArrayList<>();
        if (versionId.trim().isEmpty()) return dependents;

        for (LauncherInstance instance : findInstances(context)) {
            String instanceVersionId = instance.getBaseVersionId();
            File minecraftHome = getMinecraftHomeForInstance(instance);
            if (versionId.equals(instanceVersionId)
                    || versionInheritsFrom(minecraftHome, instanceVersionId, versionId, new HashSet<>())) {
                dependents.add(instance.getName() + " (instance)");
            }
        }

        dependents.sort(String::compareToIgnoreCase);
        return dependents;
    }

    public static boolean isSharedVersionRequiredByIsolatedInstances(
            @NonNull Context context,
            @NonNull String versionId
    ) {
        return !findIsolatedInstanceDependents(context, versionId).isEmpty();
    }

    private static boolean versionInheritsFrom(
            @NonNull File minecraftHome,
            @NonNull String childVersionId,
            @NonNull String targetVersionId,
            @NonNull HashSet<String> visited
    ) {
        if (childVersionId.trim().isEmpty() || !visited.add(childVersionId)) return false;

        File versionsRoot = new File(minecraftHome, "versions");
        File jsonFile = new File(new File(versionsRoot, childVersionId), childVersionId + ".json");
        if (!jsonFile.isFile()) return false;

        try {
            JSONObject json = new JSONObject(readString(jsonFile));
            String inheritsFrom = json.optString("inheritsFrom", "");
            if (inheritsFrom.trim().isEmpty()) return false;
            if (targetVersionId.equals(inheritsFrom)) return true;
            return versionInheritsFrom(minecraftHome, inheritsFrom, targetVersionId, visited);
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to inspect inherited version chain for "
                    + childVersionId + ": " + throwable.getMessage());
            return false;
        }
    }

    @NonNull
    public static String formatDependentVersionList(@NonNull List<String> dependents) {
        StringBuilder builder = new StringBuilder();
        for (String dependent : dependents) {
            if (dependent == null || dependent.trim().isEmpty()) continue;
            if (builder.length() > 0) builder.append('\n');
            builder.append("• ").append(dependent);
        }
        return builder.length() > 0 ? builder.toString() : "• Unknown loader version";
    }

    private static void ensureSharedVersionIsNotRequired(
            @NonNull Context context,
            @NonNull String versionId
    ) {
        ArrayList<String> dependents = findSharedVersionDependents(context, versionId);
        dependents.addAll(findIsolatedInstanceDependents(context, versionId));

        if (!dependents.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot delete shared version " + versionId
                            + " because it is required by:\n"
                            + formatDependentVersionList(dependents)
                            + "\nDelete those instances/loader versions first, or keep this shared version installed."
            );
        }
    }


    /**
     * Permanently deletes an instance from disk.
     *
     * Isolated instances remove only their folder under .minecraft/instances.
     * Shared installs remove only .minecraft/versions/<versionId>; shared libraries/assets are kept
     * because other versions can still depend on them.
     */
    public static void deleteInstance(@NonNull Context context, @NonNull LauncherInstance instance) throws Exception {
        deleteInstance(
                context,
                instance.getId(),
                instance.getBaseVersionId(),
                instance.getRootDirectory(),
                instance.isIsolated()
        );
    }

    /**
     * Variant used by InstanceDetailsActivity, where the activity already has primitive extras.
     */
    public static void deleteInstance(
            @NonNull Context context,
            @NonNull String instanceId,
            @NonNull String baseVersionId,
            @NonNull File rootDirectory,
            boolean isolated
    ) throws Exception {
        PathManager.initContextConstants(context);

        File target = getDeleteTargetDirectory(baseVersionId, rootDirectory, isolated).getCanonicalFile();

        if (isolated) {
            File instancesRoot = rootDirectory.getParentFile() != null
                    ? rootDirectory.getParentFile().getCanonicalFile()
                    : getInstancesRoot().getCanonicalFile();
            if (target.equals(instancesRoot) || !isChildOf(instancesRoot, target)) {
                throw new IllegalStateException("Refusing to delete unsafe instance path: " + target.getAbsolutePath());
            }
        } else {
            File versionsRoot = new File(rootDirectory, "versions").getCanonicalFile();
            File parent = target.getParentFile() != null ? target.getParentFile().getCanonicalFile() : null;
            if (parent == null || !versionsRoot.equals(parent)) {
                throw new IllegalStateException("Refusing to delete unsafe shared version path: " + target.getAbsolutePath());
            }

            ensureSharedVersionIsNotRequired(context, baseVersionId);
        }

        try {
            boolean deletedScopedCopy = StorageLocationStore.deleteFromScopedStorageIfNeeded(context, target);
            if (deletedScopedCopy) {
                Logging.i(TAG, "Deleted scoped-storage copy for instance " + instanceId + " at " + target.getAbsolutePath());
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to delete scoped-storage copy for instance " + instanceId, throwable);
            throw new IllegalStateException("Scoped-storage copy could not be deleted. Local mirror was left untouched: "
                    + (throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName()),
                    throwable);
        }

        deleteRecursively(target);

        Logging.i(TAG, "Deleted instance " + instanceId + " at " + target.getAbsolutePath());
    }

    @NonNull
    public static File getDeleteTargetDirectory(@NonNull LauncherInstance instance) {
        return getDeleteTargetDirectory(
                instance.getBaseVersionId(),
                instance.getRootDirectory(),
                instance.isIsolated()
        );
    }

    @NonNull
    public static File getDeleteTargetDirectory(
            @NonNull String baseVersionId,
            @NonNull File rootDirectory,
            boolean isolated
    ) {
        if (isolated) {
            return rootDirectory;
        }

        if (baseVersionId.trim().isEmpty()) {
            throw new IllegalArgumentException("Shared version id is empty.");
        }

        return new File(new File(rootDirectory, "versions"), baseVersionId);
    }

    @NonNull
    private static File getMinecraftHomeForInstance(@NonNull LauncherInstance instance) {
        if (!instance.isIsolated()) return instance.getRootDirectory();

        File root = instance.getRootDirectory();
        File instancesRoot = root.getParentFile();
        File minecraftHome = instancesRoot != null ? instancesRoot.getParentFile() : null;
        return minecraftHome != null ? minecraftHome : new File(PathManager.DIR_MINECRAFT_HOME);
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
    private static LauncherInstance readInstance(@NonNull File jsonFile) throws Exception {
        JSONObject json = new JSONObject(readString(jsonFile));
        File actualRoot = requireParent(jsonFile);

        String storedId = json.optString("id", actualRoot.getName());
        String id = actualRoot.getName();
        if (!id.equals(storedId)) {
            // Older builds could create foo-2 while still storing id=foo. Repair that
            // collision in place so settings/favorites/shortcuts remain instance-specific.
            json.put("id", id);
            writeString(jsonFile, json.toString(2));
            Logging.i(TAG, "Repaired duplicate instance id " + storedId + " -> " + id
                    + " root=" + actualRoot.getAbsolutePath());
        }
        String name = json.optString("name", id);
        String loader = json.optString("loader", "Vanilla");
        String baseVersionId = json.optString("baseVersionId", "");
        String minecraftVersionId = resolveStoredMinecraftVersionId(json, baseVersionId, actualRoot);
        if (json.optString("minecraftVersionId", "").trim().isEmpty() && !minecraftVersionId.trim().isEmpty()) {
            json.put("minecraftVersionId", minecraftVersionId);
            writeString(jsonFile, json.toString(2));
        }
        String versionType = json.optString("versionType", "release");
        File root = resolveStoredRoot(json, actualRoot);
        File gameDir = resolveStoredGameDirectory(json, root);
        File icon = resolveStoredIconFile(json, root);
        String createdAt = json.optString("createdAt", "");

        return new LauncherInstance(id, name, loader, baseVersionId, minecraftVersionId, versionType, root, gameDir, icon, createdAt);
    }

    @NonNull
    private static String resolveStoredMinecraftVersionId(
            @NonNull JSONObject json,
            @NonNull String baseVersionId,
            @NonNull File root
    ) {
        String minecraftVersionId = json.optString("minecraftVersionId", "").trim();
        if (!minecraftVersionId.isEmpty()) return minecraftVersionId;

        minecraftVersionId = readMinecraftVersionFromLaunchProfile(baseVersionId, root);
        if (!minecraftVersionId.isEmpty()) return minecraftVersionId;

        minecraftVersionId = ModManagerVersionResolver.resolveGameVersionForContent(baseVersionId);
        return minecraftVersionId.trim().isEmpty() ? baseVersionId : minecraftVersionId;
    }

    @NonNull
    private static String readMinecraftVersionFromLaunchProfile(@NonNull String baseVersionId, @NonNull File root) {
        if (baseVersionId.trim().isEmpty()) return "";

        File minecraftHome = inferMinecraftHome(root);
        File jsonFile = new File(new File(new File(minecraftHome, "versions"), baseVersionId), baseVersionId + ".json");
        if (!jsonFile.isFile()) return "";

        try {
            JSONObject versionJson = new JSONObject(readString(jsonFile));
            String value = versionJson.optString("minecraftVersionId", "").trim();
            if (!value.isEmpty()) return value;

            value = versionJson.optString("inheritsFrom", "").trim();
            if (!value.isEmpty()) return value;

            value = versionJson.optString("jar", "").trim();
            if (!value.isEmpty() && !value.equals(baseVersionId)) {
                return ModManagerVersionResolver.resolveGameVersionForContent(value);
            }
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to resolve Minecraft version for " + baseVersionId + ": " + throwable.getMessage());
        }

        return "";
    }

    @NonNull
    private static File inferMinecraftHome(@NonNull File root) {
        File instancesRoot = root.getParentFile();
        File minecraftHome = instancesRoot != null && "instances".equalsIgnoreCase(instancesRoot.getName())
                ? instancesRoot.getParentFile()
                : null;
        return minecraftHome != null ? minecraftHome : new File(PathManager.DIR_MINECRAFT_HOME);
    }


    @NonNull
    private static File resolveMetadataFile(@NonNull File rootDirectory) throws Exception {
        File direct = new File(rootDirectory, METADATA_FILE);
        if (direct.isFile()) return direct;

        File parent = rootDirectory.getParentFile();
        if (parent != null) {
            File parentMetadata = new File(parent, METADATA_FILE);
            if (parentMetadata.isFile()) return parentMetadata;
        }

        throw new IllegalStateException("Instance metadata was not found: " + rootDirectory.getAbsolutePath());
    }

    @NonNull
    private static File requireParent(@NonNull File file) {
        File parent = file.getParentFile();
        if (parent == null) {
            throw new IllegalStateException("Missing parent folder for: " + file.getAbsolutePath());
        }
        return parent;
    }

    @NonNull
    private static File resolveStoredRoot(@NonNull JSONObject json, @NonNull File actualRoot) {
        String storedPath = json.optString("rootDirectory", "");
        if (storedPath.trim().isEmpty()) return actualRoot;

        File storedRoot = new File(storedPath);
        File storedMetadata = new File(storedRoot, METADATA_FILE);
        if (!storedMetadata.isFile()) return actualRoot;

        try {
            if (!storedMetadata.getCanonicalPath().equals(new File(actualRoot, METADATA_FILE).getCanonicalPath())) {
                return actualRoot;
            }
        } catch (Throwable ignored) {
            return actualRoot;
        }

        return storedRoot;
    }

    @NonNull
    private static File resolveStoredGameDirectory(@NonNull JSONObject json, @NonNull File root) {
        String gamePath = json.optString("gameDirectory", "");
        if (gamePath.trim().isEmpty()) return new File(root, "game");

        File gameDir = new File(gamePath);
        String rootPath = safeCanonicalPath(root);
        String gameCanonical = safeCanonicalPath(gameDir);
        if (gameCanonical.equals(rootPath) || gameCanonical.startsWith(rootPath + File.separator)) {
            return gameDir;
        }

        return new File(root, "game");
    }

    @Nullable
    private static File resolveStoredIconFile(@NonNull JSONObject json, @NonNull File root) {
        String iconPath = json.optString("iconFile", "");
        if (iconPath.trim().isEmpty()) return null;

        File icon = new File(iconPath);
        String rootPath = safeCanonicalPath(root);
        String iconCanonical = safeCanonicalPath(icon);
        if (iconCanonical.equals(rootPath) || iconCanonical.startsWith(rootPath + File.separator)) {
            return icon;
        }

        File nameFallback = new File(root, icon.getName());
        return nameFallback.isFile() ? nameFallback : icon;
    }

    @NonNull
    private static String resolveImageExtension(@NonNull Context context, @NonNull Uri uri) {
        String mimeType = null;
        try {
            mimeType = context.getContentResolver().getType(uri);
        } catch (Throwable ignored) {
        }

        if ("image/jpeg".equalsIgnoreCase(mimeType) || "image/jpg".equalsIgnoreCase(mimeType)) return ".jpg";
        if ("image/webp".equalsIgnoreCase(mimeType)) return ".webp";
        if ("image/gif".equalsIgnoreCase(mimeType)) return ".gif";

        String value = uri.toString().toLowerCase(Locale.US);
        if (value.endsWith(".jpg") || value.endsWith(".jpeg")) return ".jpg";
        if (value.endsWith(".webp")) return ".webp";
        if (value.endsWith(".gif")) return ".gif";
        return ".png";
    }

    @NonNull
    private static String safeCanonicalPath(@NonNull File file) {
        try {
            return file.getCanonicalPath();
        } catch (Throwable ignored) {
            return file.getAbsolutePath();
        }
    }


    @NonNull
    private static File createUniqueInstanceRoot(@NonNull String baseId) {
        File instancesRoot = getInstancesRoot();
        ensureDirectory(instancesRoot);

        File root = new File(instancesRoot, baseId);
        if (!root.exists()) return root;

        for (int i = 2; i < 1000; i++) {
            File candidate = new File(instancesRoot, baseId + "-" + i);
            if (!candidate.exists()) return candidate;
        }

        return new File(instancesRoot, baseId + "-" + UUID.randomUUID());
    }

    @NonNull
    private static String cleanDisplayName(@NonNull String raw, @NonNull String loader, @NonNull String baseVersionId) {
        String value = raw.trim();
        if (value.isEmpty()) value = baseVersionId + " (" + loader + ")";
        value = value.replace('\n', ' ').replace('\r', ' ').trim();
        return value.isEmpty() ? baseVersionId + " (" + loader + ")" : value;
    }

    @NonNull
    private static String uniqueIdForName(@NonNull String name) {
        String safe = name.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9._ -]", "")
                .replace(' ', '-')
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");

        if (safe.isEmpty()) safe = "instance";
        return safe;
    }

    private static void copyUri(@NonNull Context context, @NonNull Uri uri, @NonNull File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null) ensureDirectory(parent);

        try (InputStream input = context.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) throw new IllegalStateException("Unable to open selected icon.");

            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static void ensureDirectory(@NonNull File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Unable to create directory: " + dir.getAbsolutePath());
        }
    }

    @NonNull
    private static String readString(@NonNull File file) throws Exception {
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void writeString(@NonNull File file, @NonNull String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null) ensureDirectory(parent);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}

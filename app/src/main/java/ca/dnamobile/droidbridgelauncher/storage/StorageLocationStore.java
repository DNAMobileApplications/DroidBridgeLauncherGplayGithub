package ca.dnamobile.droidbridgelauncher.storage;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ca.dnamobile.droidbridgelauncher.R;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.ui.version.CleanroomSupport;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

/**
 * Stores launcher storage roots selected from the Storage Locations dialog.
 *
 * Play-compliant scoped-storage rule:
 * - Default storage uses Android's app-specific external files directory.
 * - User-picked storage saves the SAF tree URI and shows the user's picked path.
 * - Minecraft/Forge/NeoForge require normal java.io.File paths. When the picked
 *   folder is writable as a normal File path, use it directly like DroidBridge/Glow Worm
 *   style storage.
 * - Only when Android blocks direct File access do we fall back to an app-private
 *   mirror plus SAF sync. That fallback is slower and should not run for writable
 *   real paths.
 * - Never rewrite a custom picked folder to Android/data as if it were the user's
 *   chosen folder.
 */
public final class StorageLocationStore {
    public static final String DEFAULT_LOCATION_ID = "default";

    private static final String TAG = "StorageLocationStore";
    private static final String PREFS_NAME = "storage_locations";
    private static final String KEY_LOCATIONS = "locations_json";
    private static final String KEY_SELECTED_LOCATION_ID = "selected_location_id";

    private static final String JSON_ID = "id";
    private static final String JSON_NAME = "name";
    private static final String JSON_URI = "uri";
    private static final String METADATA_RESTORE_MARKER = ".scoped_storage_metadata_loaded";

    private static final Object BACKGROUND_MIRROR_SYNC_LOCK = new Object();
    private static boolean backgroundMirrorSyncRunning;
    private static boolean backgroundMirrorSyncRequested;

    private StorageLocationStore() {
    }

    @NonNull
    public static List<StorageLocation> getLocations(@NonNull Context context) {
        Context appContext = context.getApplicationContext();

        ArrayList<StorageLocation> locations = new ArrayList<>();
        File defaultHome = PathManager.getDefaultLauncherHome(appContext);
        File defaultMinecraftHome = new File(defaultHome, ".minecraft");
        locations.add(new StorageLocation(
                DEFAULT_LOCATION_ID,
                appContext.getString(R.string.storage_default_name),
                defaultMinecraftHome.getAbsolutePath(),
                null,
                defaultHome.getAbsolutePath(),
                defaultMinecraftHome.getAbsolutePath(),
                true,
                true
        ));

        JSONArray array = readAndCleanLocationArray(appContext);
        for (int i = 0; i < array.length(); i++) {
            try {
                JSONObject object = array.getJSONObject(i);
                String id = object.optString(JSON_ID, "");
                String name = object.optString(JSON_NAME, "");
                String uri = object.optString(JSON_URI, "");
                if (id.trim().isEmpty() || uri.trim().isEmpty()) continue;

                Uri treeUri = Uri.parse(uri);
                File displayLauncherHome = resolveTreeUriToLauncherHome(appContext, treeUri);
                File displayMinecraftHome = displayLauncherHome != null ? new File(displayLauncherHome, ".minecraft") : null;

                File launcherHome;
                File minecraftHome;
                boolean directFileAccess = displayLauncherHome != null
                        && canUseLauncherHomeDirectly(displayLauncherHome);

                if (directFileAccess) {
                    // DroidBridge/Glow Worm style: use the selected folder itself as the
                    // launcher home. No SAF mirror and no post-install file sync.
                    launcherHome = displayLauncherHome;
                    minecraftHome = new File(launcherHome, ".minecraft");
                } else {
                    // Compatibility fallback for providers/Android versions that do not
                    // allow java.io.File writes to the selected tree path.
                    launcherHome = getScopedMirrorLauncherHome(appContext, id);
                    minecraftHome = new File(launcherHome, ".minecraft");
                }

                String summary;
                if (displayMinecraftHome != null) {
                    summary = displayMinecraftHome.getAbsolutePath();
                    if (!directFileAccess) {
                        summary += "\nCompatibility mode: files are staged locally, then copied by Android scoped storage.";
                    }
                } else {
                    summary = uri;
                }

                locations.add(new StorageLocation(
                        id,
                        name.trim().isEmpty() ? buildDisplayName(appContext, treeUri, displayLauncherHome) : name,
                        summary,
                        uri,
                        launcherHome.getAbsolutePath(),
                        minecraftHome.getAbsolutePath(),
                        false,
                        true
                ));
            } catch (Throwable throwable) {
                Logging.i(TAG, "Skipping broken storage location: " + throwable.getMessage());
            }
        }

        return locations;
    }

    @NonNull
    public static StorageLocation addTreeUri(@NonNull Context context, @NonNull Uri treeUri) {
        Context appContext = context.getApplicationContext();
        String uriString = treeUri.toString();
        String id = buildLocationId(uriString);
        File displayHome = resolveTreeUriToLauncherHome(appContext, treeUri);
        String displayName = buildDisplayName(appContext, treeUri, displayHome);

        JSONArray array = readLocationArray(appContext);
        JSONArray rewritten = new JSONArray();
        boolean replaced = false;

        for (int i = 0; i < array.length(); i++) {
            try {
                JSONObject object = array.getJSONObject(i);
                if (id.equals(object.optString(JSON_ID, ""))) {
                    rewritten.put(toJson(id, displayName, uriString));
                    replaced = true;
                } else {
                    rewritten.put(object);
                }
            } catch (Throwable ignored) {
            }
        }

        if (!replaced) rewritten.put(toJson(id, displayName, uriString));
        getPrefs(appContext).edit().putString(KEY_LOCATIONS, rewritten.toString()).apply();

        for (StorageLocation location : getLocations(appContext)) {
            if (id.equals(location.getId())) return location;
        }

        File launcherHome = displayHome != null && canUseLauncherHomeDirectly(displayHome)
                ? displayHome
                : getScopedMirrorLauncherHome(appContext, id);
        return new StorageLocation(
                id,
                displayName,
                displayHome != null ? new File(displayHome, ".minecraft").getAbsolutePath() : uriString,
                uriString,
                launcherHome.getAbsolutePath(),
                new File(launcherHome, ".minecraft").getAbsolutePath(),
                false,
                true
        );
    }

    public static boolean removeLocation(@NonNull Context context, @NonNull String id) {
        Context appContext = context.getApplicationContext();
        if (DEFAULT_LOCATION_ID.equals(id)) return false;

        JSONArray array = readLocationArray(appContext);
        JSONArray rewritten = new JSONArray();
        boolean removed = false;
        String removedUriString = "";

        for (int i = 0; i < array.length(); i++) {
            try {
                JSONObject object = array.getJSONObject(i);
                if (id.equals(object.optString(JSON_ID, ""))) {
                    removed = true;
                    removedUriString = object.optString(JSON_URI, "");
                } else {
                    rewritten.put(object);
                }
            } catch (Throwable ignored) {
            }
        }

        if (!removed) return false;

        SharedPreferences.Editor editor = getPrefs(appContext).edit().putString(KEY_LOCATIONS, rewritten.toString());
        if (id.equals(getSelectedLocationId(appContext))) {
            editor.putString(KEY_SELECTED_LOCATION_ID, DEFAULT_LOCATION_ID);
        }
        editor.apply();

        releasePersistablePermissionIfPossible(appContext, removedUriString);
        return true;
    }

    public static void setSelectedLocationId(@NonNull Context context, @NonNull String id) {
        getPrefs(context).edit().putString(KEY_SELECTED_LOCATION_ID, id).apply();
    }

    @NonNull
    public static String getSelectedLocationId(@NonNull Context context) {
        String id = getPrefs(context).getString(KEY_SELECTED_LOCATION_ID, DEFAULT_LOCATION_ID);
        return id == null || id.trim().isEmpty() ? DEFAULT_LOCATION_ID : id;
    }

    @NonNull
    public static StorageLocation getSelectedLocation(@NonNull Context context) {
        String selectedId = getSelectedLocationId(context);
        List<StorageLocation> locations = getLocations(context);
        for (StorageLocation location : locations) {
            if (selectedId.equals(location.getId())) return location;
        }
        setSelectedLocationId(context, DEFAULT_LOCATION_ID);
        return locations.get(0);
    }

    @Nullable
    public static String getSelectedTreeUriString(@NonNull Context context) {
        StorageLocation location = getSelectedLocation(context);
        return location.isDefaultLocation() ? null : location.getUriString();
    }

    @Nullable
    public static Uri getSelectedTreeUri(@NonNull Context context) {
        String value = getSelectedTreeUriString(context);
        return value == null || value.trim().isEmpty() ? null : Uri.parse(value.trim());
    }

    public static boolean isSelectedScopedStorage(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        StorageLocation selected = getSelectedLocation(appContext);
        return !selected.isDefaultLocation()
                && getSelectedTreeUri(appContext) != null
                && isMirrorBackedStorageLocation(appContext, selected);
    }

    /**
     * MainActivity should not crawl a SAF tree on every resume/selection. A metadata
     * restore is only needed when the selected mirror has no launcher metadata yet.
     */
    public static boolean needsSelectedTreeMetadataRestoreForAdapter(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        if (!isSelectedScopedStorage(appContext)) return false;

        File launcherHome = getSelectedLauncherHome(appContext);
        File minecraftHome = new File(launcherHome, ".minecraft");
        if (new File(launcherHome, METADATA_RESTORE_MARKER).isFile()
                || new File(minecraftHome, METADATA_RESTORE_MARKER).isFile()) {
            return false;
        }

        return !hasAnyInstanceMetadata(new File(minecraftHome, "instances"))
                && !hasAnyVersionMetadata(new File(minecraftHome, "versions"));
    }

    /**
     * Opening details should be instant. If this returns true, callers can hydrate
     * the one instance folder in the background, not before navigation.
     */
    public static boolean needsSelectedLocalPathRestoreForDetails(
            @NonNull Context context,
            @Nullable File localPath
    ) {
        Context appContext = context.getApplicationContext();
        if (localPath == null || !isSelectedScopedStorage(appContext)) return false;

        File target;
        try {
            target = localPath.getCanonicalFile();
        } catch (Throwable ignored) {
            target = localPath.getAbsoluteFile();
        }

        if (!target.isDirectory()) return true;
        if (new File(target, "instance.json").isFile()
                && new File(new File(target, "game"), "options.txt").isFile()) {
            return false;
        }
        return false;
    }

    /**
     * Returns the actual local File root used by installers and the JVM. For SAF
     * locations this is an app-private mirror, not Android/data pretending to be
     * the user's selected folder.
     */
    @NonNull
    public static File getSelectedLauncherHome(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        StorageLocation selected = getSelectedLocation(appContext);
        String path = selected.getLauncherHomePath();
        if (path != null && !path.trim().isEmpty()) return new File(path.trim());
        return PathManager.getDefaultLauncherHome(appContext);
    }

    @NonNull
    public static File getSelectedMinecraftHome(@NonNull Context context) {
        return new File(getSelectedLauncherHome(context), ".minecraft");
    }

    @NonNull
    public static List<File> getVisibleLauncherHomes(@NonNull Context context) {
        ArrayList<File> homes = new ArrayList<>();
        homes.add(getSelectedLauncherHome(context));
        return homes;
    }

    @NonNull
    public static List<File> getVisibleMinecraftHomes(@NonNull Context context) {
        ArrayList<File> homes = new ArrayList<>();
        for (File launcherHome : getVisibleLauncherHomes(context)) homes.add(new File(launcherHome, ".minecraft"));
        return homes;
    }

    @NonNull
    public static List<File> getAllLauncherHomes(@NonNull Context context) {
        ArrayList<File> homes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (StorageLocation location : getLocations(context)) {
            String path = location.getLauncherHomePath();
            if (path != null && !path.trim().isEmpty()) addHomeIfMissing(homes, seen, new File(path.trim()));
        }
        addHomeIfMissing(homes, seen, getSelectedLauncherHome(context));
        addHomeIfMissing(homes, seen, PathManager.getDefaultLauncherHome(context));
        return homes;
    }

    @NonNull
    public static List<File> getAllMinecraftHomes(@NonNull Context context) {
        ArrayList<File> homes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (File launcherHome : getAllLauncherHomes(context)) {
            File minecraftHome = new File(launcherHome, ".minecraft");
            if (seen.add(minecraftHome.getAbsolutePath())) homes.add(minecraftHome);
        }
        return homes;
    }

    @NonNull
    public static File getScopedMirrorLauncherHome(@NonNull Context context, @NonNull String id) {
        String safeId = id.replaceAll("[^A-Za-z0-9._-]", "_");
        return new File(context.getApplicationContext().getFilesDir(), "scoped_storage_mirrors/" + safeId);
    }

    public static void syncSelectedMirrorToTree(
            @NonNull Context context,
            @Nullable SafMinecraftMirror.Progress progress
    ) throws Exception {
        Context appContext = context.getApplicationContext();
        StorageLocation selected = getSelectedLocation(appContext);
        if (selected.isDefaultLocation()) return;
        if (!isMirrorBackedStorageLocation(appContext, selected)) return;
        Uri treeUri = getSelectedTreeUri(appContext);
        if (treeUri == null) return;

        File localRoot = getSelectedLocalRootForTree(appContext, treeUri);
        SafMinecraftMirror.copyLocalLauncherHomeToTree(appContext, localRoot, treeUri, progress);
    }

    /**
     * Queues a non-blocking compatibility mirror save. Multiple requests are coalesced
     * into one active pass plus, at most, one follow-up pass if files changed while the
     * first pass was running. Install completion must never wait for a full SAF tree walk.
     */
    public static void requestSelectedMirrorSyncToTree(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        if (!isSelectedScopedStorage(appContext)) return;

        synchronized (BACKGROUND_MIRROR_SYNC_LOCK) {
            backgroundMirrorSyncRequested = true;
            if (backgroundMirrorSyncRunning) return;
            backgroundMirrorSyncRunning = true;
        }

        new Thread(() -> {
            while (true) {
                synchronized (BACKGROUND_MIRROR_SYNC_LOCK) {
                    if (!backgroundMirrorSyncRequested) {
                        backgroundMirrorSyncRunning = false;
                        return;
                    }
                    backgroundMirrorSyncRequested = false;
                }

                try {
                    syncSelectedMirrorToTree(appContext, null);
                } catch (Throwable throwable) {
                    Logging.i(TAG, "Background scoped-storage save failed: " + throwable.getMessage());
                }
            }
        }, "ScopedStorageBackgroundSave").start();
    }

    /**
     * Full restore used only when runtime files are actually needed, such as before
     * launch. Do not call this from the storage picker just to populate the UI.
     */
    public static void syncSelectedTreeToMirror(
            @NonNull Context context,
            @Nullable SafMinecraftMirror.Progress progress
    ) throws Exception {
        Context appContext = context.getApplicationContext();
        StorageLocation selected = getSelectedLocation(appContext);
        if (selected.isDefaultLocation()) return;
        if (!isMirrorBackedStorageLocation(appContext, selected)) return;
        Uri treeUri = getSelectedTreeUri(appContext);
        if (treeUri == null) return;

        File localRoot = getSelectedLocalRootForTree(appContext, treeUri);
        SafMinecraftMirror.copyTreeToLocalLauncherHome(appContext, treeUri, localRoot, progress);
    }

    /**
     * Tiny restore used by the storage picker/main adapter. It copies only instance
     * metadata, icons, options.txt, and version JSON files. It intentionally skips
     * assets, libraries, saves, mods, resource packs, shader packs, logs, screenshots,
     * jars, and other large runtime data.
     */
    public static void syncSelectedTreeMetadataToMirror(
            @NonNull Context context,
            @Nullable SafMinecraftMirror.Progress progress
    ) throws Exception {
        Context appContext = context.getApplicationContext();
        StorageLocation selected = getSelectedLocation(appContext);
        if (selected.isDefaultLocation()) return;
        if (!isMirrorBackedStorageLocation(appContext, selected)) return;
        Uri treeUri = getSelectedTreeUri(appContext);
        if (treeUri == null) return;

        File localRoot = getSelectedLocalRootForTree(appContext, treeUri);
        SafMinecraftMirror.copyTreeMetadataToLocalLauncherHome(appContext, treeUri, localRoot, progress);
        markMetadataRestoreChecked(localRoot);
    }

    /**
     * Restores one already-known local mirror path from the picked SAF tree.
     * This is useful when the user opens one instance details screen: the adapter
     * stays fast, but that one instance folder can be hydrated on demand.
     */
    public static void syncSelectedLocalPathFromTree(
            @NonNull Context context,
            @NonNull File localPath,
            @Nullable SafMinecraftMirror.Progress progress
    ) throws Exception {
        Context appContext = context.getApplicationContext();
        StorageLocation selected = getSelectedLocation(appContext);
        if (selected.isDefaultLocation()) return;
        if (!isMirrorBackedStorageLocation(appContext, selected)) return;
        Uri treeUri = getSelectedTreeUri(appContext);
        if (treeUri == null) return;

        File localRoot = getSelectedLocalRootForTree(appContext, treeUri).getCanonicalFile();
        File target = localPath.getCanonicalFile();
        if (!target.equals(localRoot) && !isChildOf(localRoot, target)) {
            Logging.i(TAG, "Skipping scoped relative restore outside selected mirror: " + target.getAbsolutePath());
            return;
        }

        String relative = relativePath(localRoot, target);
        SafMinecraftMirror.copyRelativePathToLocalLauncherHome(appContext, treeUri, localRoot, relative, progress);
    }

    public static boolean needsSelectedTreeRestoreForLaunch(
            @NonNull Context context,
            @Nullable File instanceRoot,
            @Nullable String versionId
    ) {
        Context appContext = context.getApplicationContext();
        if (!isSelectedScopedStorage(appContext)) return false;

        try {
            if (instanceRoot != null && !new File(instanceRoot, "instance.json").isFile()) {
                return true;
            }

            File minecraftHome = getSelectedMinecraftHome(appContext);
            String cleanVersionId = versionId == null ? "" : versionId.trim();
            if (!cleanVersionId.isEmpty()
                    && !isVersionInheritanceChainReadyForLaunch(minecraftHome, cleanVersionId, new HashSet<>())) {
                return true;
            }

            // Assets and libraries are now under .minecraft. If these are absent,
            // the app-private mirror is probably metadata-only or was lost after
            // uninstall/reinstall, so restore before starting Minecraft.
            if (!new File(minecraftHome, "assets").isDirectory()) return true;
            if (!new File(minecraftHome, "libraries").isDirectory()) return true;
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to check scoped mirror launch readiness: " + throwable.getMessage());
            return true;
        }

        return false;
    }

    /**
     * Metadata-only restores can make a Fabric/Forge/NeoForge child profile visible
     * in the adapter while the inherited vanilla parent profile/JAR is still absent.
     * The launcher must restore the full scoped tree before launch if any inherited
     * parent in the chain is missing.
     */
    private static boolean isVersionInheritanceChainReadyForLaunch(
            @NonNull File minecraftHome,
            @NonNull String versionId,
            @NonNull Set<String> visited
    ) throws Exception {
        String cleanVersionId = versionId.trim();
        if (cleanVersionId.isEmpty()) return true;
        if (!visited.add(cleanVersionId)) {
            Logging.i(TAG, "Circular version inheritance detected while checking scoped mirror: " + cleanVersionId);
            return false;
        }

        File versionDirectory = new File(new File(minecraftHome, "versions"), cleanVersionId);
        File versionJsonFile = new File(versionDirectory, cleanVersionId + ".json");
        if (!versionJsonFile.isFile()) {
            Logging.i(TAG, "Scoped mirror is missing version JSON before launch: " + versionJsonFile.getAbsolutePath());
            return false;
        }

        JSONObject versionJson = new JSONObject(readString(versionJsonFile));

        String referencedJarId = versionJson.optString("jar", "").trim();
        if (!referencedJarId.isEmpty() && !referencedJarId.equals(cleanVersionId)) {
            File referencedJarFile = new File(
                    new File(new File(minecraftHome, "versions"), referencedJarId),
                    referencedJarId + ".jar"
            );
            if (!referencedJarFile.isFile()) {
                Logging.i(TAG, "Scoped mirror is missing referenced client jar before launch: "
                        + referencedJarFile.getAbsolutePath());
                return false;
            }
        }

        String parentVersionId = versionJson.optString("inheritsFrom", "").trim();
        if (!parentVersionId.isEmpty()) {
            return isVersionInheritanceChainReadyForLaunch(minecraftHome, parentVersionId, visited);
        }

        if (!referencedJarId.isEmpty() && !referencedJarId.equals(cleanVersionId)) {
            return true;
        }

        if (CleanroomSupport.isCleanroomProfile(cleanVersionId, versionJson)) {
            File cleanroomBaseJar = new File(
                    new File(new File(minecraftHome, "versions"), CleanroomSupport.MINECRAFT_VERSION),
                    CleanroomSupport.MINECRAFT_VERSION + ".jar"
            );
            if (!cleanroomBaseJar.isFile()) {
                Logging.i(TAG, "Scoped mirror is missing Cleanroom base client jar before launch: "
                        + cleanroomBaseJar.getAbsolutePath());
                return false;
            }
            return true;
        }

        File clientJarFile = new File(versionDirectory, cleanVersionId + ".jar");
        if (!clientJarFile.isFile()) {
            Logging.i(TAG, "Scoped mirror is missing standalone client jar before launch: " + clientJarFile.getAbsolutePath());
            return false;
        }

        return true;
    }

    @NonNull
    private static String readString(@NonNull File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    @NonNull
    private static File getSelectedLocalRootForTree(@NonNull Context context, @NonNull Uri treeUri) {
        File launcherHome = getSelectedLauncherHome(context);
        return isMinecraftTreeUri(context, treeUri) ? new File(launcherHome, ".minecraft") : launcherHome;
    }

    private static boolean isMinecraftTreeUri(@NonNull Context context, @NonNull Uri treeUri) {
        File selectedDirectory = resolveTreeUriToFile(context, treeUri);
        if (selectedDirectory != null && ".minecraft".equals(selectedDirectory.getName())) {
            return true;
        }

        try {
            String documentId = DocumentsContract.getTreeDocumentId(treeUri);
            if (documentId == null) return false;
            documentId = documentId.replace('\\', '/');
            return documentId.equals(".minecraft") || documentId.endsWith("/.minecraft") || documentId.endsWith(":.minecraft");
        } catch (Throwable ignored) {
            return false;
        }
    }


    /**
     * If a local mirror path belongs to a custom SAF storage location, delete the
     * matching path from the picked scoped-storage tree too. This is required
     * because deleting the local mirror alone does not remove the already-synced
     * files from the user's selected folder.
     */
    public static boolean deleteFromScopedStorageIfNeeded(
            @NonNull Context context,
            @NonNull File localPath
    ) throws Exception {
        Context appContext = context.getApplicationContext();
        File target = localPath.getCanonicalFile();

        for (StorageLocation location : getLocations(appContext)) {
            if (location.isDefaultLocation()) continue;
            if (!isMirrorBackedStorageLocation(appContext, location)) continue;

            String uriString = location.getUriString();
            String launcherHomePath = location.getLauncherHomePath();
            if (uriString == null || uriString.trim().isEmpty()) continue;
            if (launcherHomePath == null || launcherHomePath.trim().isEmpty()) continue;

            Uri treeUri = Uri.parse(uriString.trim());
            File launcherHome = new File(launcherHomePath.trim()).getCanonicalFile();
            File localRootForTree = isMinecraftTreeUri(appContext, treeUri)
                    ? new File(launcherHome, ".minecraft").getCanonicalFile()
                    : launcherHome;

            if (!target.equals(localRootForTree) && !isChildOf(localRootForTree, target)) {
                continue;
            }

            String relative = relativePath(localRootForTree, target);
            if (relative.isEmpty()) {
                Logging.i(TAG, "Refusing to delete scoped storage root for local path: " + target.getAbsolutePath());
                return false;
            }

            return SafMinecraftMirror.deleteRelativePathFromTree(
                    appContext,
                    treeUri,
                    relative
            );
        }

        return false;
    }

    @Nullable
    public static File resolveTreeUriToLauncherHome(@NonNull Context context, @NonNull Uri uri) {
        File selectedDirectory = resolveTreeUriToFile(context, uri);
        if (selectedDirectory == null) return null;
        return normalizeSelectedDirectoryToLauncherHome(selectedDirectory);
    }

    @Nullable
    public static File resolveTreeUriToFile(@NonNull Context context, @NonNull Uri uri) {
        try {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                String path = uri.getPath();
                return path == null || path.trim().isEmpty() ? null : new File(path);
            }

            String documentId = DocumentsContract.getTreeDocumentId(uri);
            if (documentId == null || documentId.trim().isEmpty()) return null;

            String volume = documentId;
            String relative = "";
            int split = documentId.indexOf(':');
            if (split >= 0) {
                volume = documentId.substring(0, split);
                relative = split + 1 < documentId.length() ? documentId.substring(split + 1) : "";
            }

            File base;
            if ("primary".equalsIgnoreCase(volume)) base = Environment.getExternalStorageDirectory();
            else if ("home".equalsIgnoreCase(volume)) base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            else base = new File("/storage", volume);

            return relative.trim().isEmpty() ? base : new File(base, relative);
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to resolve tree URI " + uri + ": " + throwable.getMessage());
            return null;
        }
    }

    @NonNull
    private static File normalizeSelectedDirectoryToLauncherHome(@NonNull File selectedDirectory) {
        if (".minecraft".equals(selectedDirectory.getName())) {
            File parent = selectedDirectory.getParentFile();
            if (parent != null) return parent;
        }
        return selectedDirectory;
    }

    private static boolean canUseLauncherHomeDirectly(@NonNull File launcherHome) {
        try {
            if (!launcherHome.isDirectory()) return false;

            File minecraftHome = new File(launcherHome, ".minecraft");
            if (!minecraftHome.exists() && !minecraftHome.mkdirs() && !minecraftHome.isDirectory()) {
                return false;
            }

            File testFile = new File(minecraftHome, ".java_launcher_write_test");
            try (FileOutputStream output = new FileOutputStream(testFile, false)) {
                output.write('J');
            }

            if (testFile.exists() && !testFile.delete()) {
                Logging.i(TAG, "Unable to delete direct storage write test: " + testFile.getAbsolutePath());
            }
            return true;
        } catch (Throwable throwable) {
            Logging.i(TAG, "Selected folder is not writable through File API, using SAF mirror fallback: "
                    + launcherHome.getAbsolutePath() + " because " + throwable.getMessage());
            return false;
        }
    }

    private static boolean isMirrorBackedStorageLocation(
            @NonNull Context context,
            @NonNull StorageLocation location
    ) {
        String launcherHomePath = location.getLauncherHomePath();
        if (launcherHomePath == null || launcherHomePath.trim().isEmpty()) return false;

        try {
            File mirrorRoot = new File(context.getApplicationContext().getFilesDir(), "scoped_storage_mirrors").getCanonicalFile();
            File launcherHome = new File(launcherHomePath.trim()).getCanonicalFile();
            return launcherHome.equals(mirrorRoot) || isChildOf(mirrorRoot, launcherHome);
        } catch (Throwable throwable) {
            return false;
        }
    }

    private static boolean hasAnyInstanceMetadata(@NonNull File instancesRoot) {
        File[] children = instancesRoot.listFiles();
        if (children == null) return false;
        for (File child : children) {
            if (child == null || !child.isDirectory()) continue;
            if (child.getName().startsWith(".pending_delete")) continue;
            if (new File(child, "instance.json").isFile()) return true;
        }
        return false;
    }

    private static boolean hasAnyVersionMetadata(@NonNull File versionsRoot) {
        File[] children = versionsRoot.listFiles();
        if (children == null) return false;
        for (File child : children) {
            if (child == null || !child.isDirectory()) continue;
            String id = child.getName();
            if (id.startsWith(".pending_delete")) continue;
            if (new File(child, id + ".json").isFile()) return true;
        }
        return false;
    }

    private static void markMetadataRestoreChecked(@NonNull File localRoot) {
        try {
            if (!localRoot.exists() && !localRoot.mkdirs()) return;
            File marker = new File(localRoot, METADATA_RESTORE_MARKER);
            if (!marker.exists()) {
                try (FileOutputStream output = new FileOutputStream(marker, false)) {
                    output.write('1');
                }
            }
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to write scoped metadata marker: " + throwable.getMessage());
        }
    }

    @NonNull
    private static JSONArray readAndCleanLocationArray(@NonNull Context context) {
        JSONArray raw = readLocationArray(context);
        JSONArray cleaned = new JSONArray();
        Set<String> seenIds = new HashSet<>();
        boolean changed = false;
        String selectedId = getSelectedLocationId(context);
        boolean selectedRemoved = false;

        for (int i = 0; i < raw.length(); i++) {
            try {
                JSONObject object = raw.getJSONObject(i);
                String id = object.optString(JSON_ID, "");
                String name = object.optString(JSON_NAME, "");
                String uri = object.optString(JSON_URI, "");
                if (id.trim().isEmpty() || uri.trim().isEmpty() || !seenIds.add(id)) {
                    changed = true;
                    if (id.equals(selectedId)) selectedRemoved = true;
                    continue;
                }
                cleaned.put(toJson(id, name, uri));
            } catch (Throwable throwable) {
                changed = true;
            }
        }

        if (changed) {
            SharedPreferences.Editor editor = getPrefs(context).edit().putString(KEY_LOCATIONS, cleaned.toString());
            if (selectedRemoved) editor.putString(KEY_SELECTED_LOCATION_ID, DEFAULT_LOCATION_ID);
            editor.apply();
        }

        return cleaned;
    }

    private static void releasePersistablePermissionIfPossible(@NonNull Context context, @Nullable String uriString) {
        if (uriString == null || uriString.trim().isEmpty()) return;
        try {
            Uri uri = Uri.parse(uriString);
            context.getApplicationContext().getContentResolver().releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to release storage URI permission: " + throwable.getMessage());
        }
    }

    @NonNull
    private static SharedPreferences getPrefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @NonNull
    private static JSONArray readLocationArray(@NonNull Context context) {
        String raw = getPrefs(context).getString(KEY_LOCATIONS, "[]");
        try {
            return new JSONArray(raw == null || raw.trim().isEmpty() ? "[]" : raw);
        } catch (Throwable ignored) {
            return new JSONArray();
        }
    }

    @NonNull
    private static JSONObject toJson(@NonNull String id, @NonNull String name, @NonNull String uri) {
        JSONObject object = new JSONObject();
        try {
            object.put(JSON_ID, id);
            object.put(JSON_NAME, name);
            object.put(JSON_URI, uri);
        } catch (Throwable ignored) {
        }
        return object;
    }

    @NonNull
    private static String buildLocationId(@NonNull String uriString) {
        return "tree:" + Integer.toHexString(uriString.hashCode());
    }

    @NonNull
    private static String buildDisplayName(@NonNull Context context, @NonNull Uri uri, @Nullable File resolvedHome) {
        if (resolvedHome != null) {
            String name = resolvedHome.getName();
            if (name != null && !name.trim().isEmpty()) return name;
        }

        String segment = uri.getLastPathSegment();
        if (segment == null || segment.trim().isEmpty()) return context.getString(R.string.storage_scoped_name);

        int split = segment.indexOf(':');
        if (split >= 0 && split + 1 < segment.length()) {
            String path = segment.substring(split + 1);
            if (!path.trim().isEmpty()) {
                int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
                return slash >= 0 && slash + 1 < path.length() ? path.substring(slash + 1) : path;
            }
        }
        return segment.replace(':', '/');
    }


    private static boolean isChildOf(@NonNull File parent, @NonNull File child) throws Exception {
        String parentPath = parent.getCanonicalPath();
        String childPath = child.getCanonicalPath();
        return childPath.startsWith(parentPath + File.separator);
    }

    @NonNull
    private static String relativePath(@NonNull File root, @NonNull File file) throws Exception {
        String rootPath = root.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        if (!filePath.startsWith(rootPath)) return "";
        String out = filePath.substring(rootPath.length());
        while (out.startsWith(File.separator)) out = out.substring(1);
        return out.replace(File.separatorChar, '/');
    }

    private static void addHomeIfMissing(@NonNull List<File> homes, @NonNull Set<String> seen, @Nullable File home) {
        if (home == null) return;
        String path = home.getAbsolutePath();
        if (seen.add(path)) homes.add(home);
    }
}

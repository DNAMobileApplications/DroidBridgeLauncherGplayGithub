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

package ca.dnamobile.droidbridgelauncher.modmanager;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import ca.dnamobile.droidbridgelauncher.data.model.MinecraftVersion;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstance;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstanceManager;
import ca.dnamobile.droidbridgelauncher.ui.version.FabricInstaller;
import ca.dnamobile.droidbridgelauncher.ui.version.ForgeInstaller;
import ca.dnamobile.droidbridgelauncher.ui.version.MinecraftVersionInstaller;
import ca.dnamobile.droidbridgelauncher.ui.version.MinecraftVersionManifestClient;
import ca.dnamobile.droidbridgelauncher.ui.version.NeoForgeInstaller;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

/**
 * First-pass Modrinth/CurseForge/desktop-launcher modpack installer for DroidBridge.
 *
 * Supported imports:
 * - Modrinth .mrpack files with modrinth.index.json
 * - CurseForge exported .zip files with manifest.json + overrides/
 * - portable desktop-launcher exported .zip files with mmc-pack.json + instance.cfg + .minecraft/
 *
 * Supported browsing installs:
 * - Modrinth modpack projects by project id/slug
 * - CurseForge modpack projects by numeric project id
 */
public final class ModpackInstallManager {
    private static final String TAG = "ModpackInstall";
    private static final String DROIDBRIDGE_METADATA_DIRECTORY = DroidBridgeMetadataPaths.PRIMARY_DIRECTORY;
    private static final String MODPACK_MANIFEST_FILE = "modpack_manifest.json";
    private static final String MODPACK_FILES_MANIFEST_FILE = "modpack_files_manifest.json";
    private static final String MODPACK_INSTALL_WARNINGS_FILE = "modpack_install_warnings.txt";
    private static final String JAVALAUNCHER_PACK_ICON_ENTRY = "droidbridge-pack-icon.png";
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int CURSEFORGE_MINECRAFT_GAME_ID = 432;
    private static final int CURSEFORGE_WORLDS_CLASS_ID = 17;


    private ModpackInstallManager() {
    }

    public interface Listener {
        void onStatus(@NonNull String message);
        void onProgress(int current, int total);
        void onComplete(@NonNull String message);

        default void onComplete(@NonNull String message, @Nullable LauncherInstance instance) {
            onComplete(message);
        }
        void onError(@NonNull Throwable throwable);
    }

    public static void installFromProject(
            @NonNull Context context,
            @NonNull ModManagerSource source,
            @NonNull String projectId,
            @Nullable String slug,
            @Nullable String title,
            @Nullable String iconUrl,
            @Nullable String preferredMinecraftVersion,
            @Nullable String preferredLoader,
            @NonNull Listener listener
    ) {
        try {
            PathManager.initContextConstants(context);
            listener.onStatus("Finding latest compatible modpack version...");
            File download;
            if (source == ModManagerSource.CURSEFORGE) {
                download = downloadCurseForgeModpack(context, projectId, title, preferredMinecraftVersion, preferredLoader, listener);
            } else {
                download = downloadModrinthModpack(context, projectId, slug, title, preferredMinecraftVersion, preferredLoader, listener);
            }
            installFromLocalFile(context, download, iconUrl, listener);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to install modpack project " + projectId, throwable);
            listener.onError(throwable);
        }
    }

    @NonNull
    public static ArrayList<ModpackVersionChoice> listProjectVersions(
            @NonNull Context context,
            @NonNull ModManagerSource source,
            @NonNull String projectId,
            @Nullable String slug
    ) throws Exception {
        PathManager.initContextConstants(context);
        if (source == ModManagerSource.CURSEFORGE) {
            return listCurseForgeModpackVersions(projectId);
        }
        return listModrinthModpackVersions(projectId, slug);
    }

    public static void installFromProjectVersion(
            @NonNull Context context,
            @NonNull ModManagerSource source,
            @NonNull String projectId,
            @Nullable String slug,
            @Nullable String title,
            @Nullable String iconUrl,
            @NonNull ModpackVersionChoice version,
            @NonNull Listener listener
    ) {
        try {
            PathManager.initContextConstants(context);
            listener.onStatus("Preparing " + version.getDisplayTitle() + "...");
            File download;
            if (source == ModManagerSource.CURSEFORGE) {
                download = downloadCurseForgeModpackVersion(context, projectId, title, version, listener);
            } else {
                download = downloadModrinthModpackVersion(context, projectId, slug, title, version, listener);
            }
            installFromLocalFile(context, download, iconUrl, listener);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to install selected modpack version for " + projectId, throwable);
            listener.onError(throwable);
        }
    }

    public static void importFromUri(
            @NonNull Context context,
            @NonNull Uri uri,
            @NonNull Listener listener
    ) {
        File temp = null;
        try {
            PathManager.initContextConstants(context);
            listener.onStatus("Preparing selected modpack...");
            temp = File.createTempFile("javalauncher-modpack-import-", ".zip", context.getCacheDir());
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(temp)) {
                if (input == null) throw new IOException("Unable to open selected modpack file.");
                copyStream(input, output);
            }
            installFromLocalFile(context, temp, listener);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to import modpack", throwable);
            listener.onError(throwable);
        } finally {
            if (temp != null) //noinspection ResultOfMethodCallIgnored
                temp.delete();
        }
    }

    public static void installFromLocalFile(
            @NonNull Context context,
            @NonNull File archive,
            @NonNull Listener listener
    ) throws Exception {
        installFromLocalFile(context, archive, null, listener);
    }

    private static void installFromLocalFile(
            @NonNull Context context,
            @NonNull File archive,
            @Nullable String iconUrl,
            @NonNull Listener listener
    ) throws Exception {
        if (!archive.isFile()) throw new IOException("Modpack file not found: " + archive.getAbsolutePath());

        try (ZipFile zip = new ZipFile(archive)) {
            if (zip.getEntry("modrinth.index.json") != null) {
                installMrpack(context, archive, iconUrl, listener);
                return;
            }
            if (zip.getEntry("manifest.json") != null) {
                installCurseForgePack(context, archive, iconUrl, listener);
                return;
            }
            String multiMcRootPrefix = findMultiMcRootPrefix(zip);
            if (multiMcRootPrefix != null) {
                installMultiMcPack(context, archive, multiMcRootPrefix, listener);
                return;
            }
        }

        throw new IllegalArgumentException("Unsupported modpack. Select a Modrinth .mrpack, CurseForge exported .zip, or MultiMC/Prism exported .zip.");
    }


    @NonNull
    private static ArrayList<ModpackVersionChoice> listModrinthModpackVersions(
            @NonNull String projectId,
            @Nullable String slug
    ) throws Exception {
        String lookup = !isBlank(projectId) ? projectId.trim() : safe(slug);
        if (isBlank(lookup)) throw new IllegalArgumentException("Missing Modrinth project id.");

        JSONArray versions = new JSONArray(httpGetString("https://api.modrinth.com/v2/project/" + urlEncode(lookup) + "/version", null));
        ArrayList<ModpackVersionChoice> choices = new ArrayList<>();
        for (int i = 0; i < versions.length(); i++) {
            JSONObject version = versions.optJSONObject(i);
            if (version == null) continue;

            JSONObject primary = findModrinthMrpackFile(version);
            if (primary == null) continue;

            ArrayList<String> gameVersions = jsonArrayToStringList(version.optJSONArray("game_versions"));
            ArrayList<String> loaders = jsonArrayToStringList(version.optJSONArray("loaders"));
            String versionId = version.optString("id", "").trim();
            String versionName = firstNonBlank(version.optString("name", ""), version.optString("version_number", ""));
            String versionNumber = version.optString("version_number", "").trim();
            String datePublished = version.optString("date_published", "").trim();
            String fileName = sanitizeFileName(primary.optString("filename", ""), "modpack.mrpack");
            String downloadUrl = cleanDownloadUrl(primary.optString("url", ""));

            if (isBlank(versionId) || !isHttpUrl(downloadUrl)) continue;

            choices.add(new ModpackVersionChoice(
                    ModManagerSource.MODRINTH,
                    versionId,
                    0,
                    versionName,
                    versionNumber,
                    fileName,
                    downloadUrl,
                    gameVersions,
                    loaders,
                    datePublished
            ));
        }
        return choices;
    }

    @Nullable
    private static JSONObject findModrinthMrpackFile(@NonNull JSONObject version) {
        JSONArray files = version.optJSONArray("files");
        JSONObject fallback = null;
        if (files == null) return null;
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) continue;
            String fileName = file.optString("filename", "");
            if (!fileName.toLowerCase(Locale.US).endsWith(".mrpack")) continue;
            if (fallback == null) fallback = file;
            if (file.optBoolean("primary", false)) return file;
        }
        return fallback;
    }

    @NonNull
    private static File downloadModrinthModpackVersion(
            @NonNull Context context,
            @NonNull String projectId,
            @Nullable String slug,
            @Nullable String title,
            @NonNull ModpackVersionChoice version,
            @NonNull Listener listener
    ) throws Exception {
        String downloadUrl = cleanDownloadUrl(version.downloadUrl);
        if (!isHttpUrl(downloadUrl)) {
            // The user may have kept the version selector open while the object was stale.
            // Re-fetch the version list and match by id before failing.
            for (ModpackVersionChoice fresh : listModrinthModpackVersions(projectId, slug)) {
                if (fresh.versionId.equals(version.versionId)) {
                    downloadUrl = cleanDownloadUrl(fresh.downloadUrl);
                    if (isHttpUrl(downloadUrl)) {
                        version = fresh;
                        break;
                    }
                }
            }
        }
        if (!isHttpUrl(downloadUrl)) {
            throw new IOException("The selected Modrinth modpack version does not have a downloadable .mrpack file.");
        }

        String fallbackName = sanitizeFileName(safe(title) + "-" + version.getDisplayTitle() + ".mrpack", "modpack.mrpack");
        String fileName = sanitizeFileName(version.fileName, fallbackName);
        File out = new File(context.getCacheDir(), fileName);
        listener.onStatus("Downloading " + fileName + "...");
        downloadFile(downloadUrl, out, listener);
        return out;
    }

    @NonNull
    private static ArrayList<ModpackVersionChoice> listCurseForgeModpackVersions(
            @NonNull String projectId
    ) throws Exception {
        int modId = parsePositiveInt(projectId, "CurseForge project id");
        String apiKey = CurseForgeApiKeyProvider.resolve();
        if (isBlank(apiKey)) throw new IOException("Missing CurseForge API key.");

        ArrayList<ModpackVersionChoice> choices = new ArrayList<>();
        int pageSize = 50;
        int index = 0;
        int totalCount = Integer.MAX_VALUE;

        while (index < totalCount) {
            String url = "https://api.curseforge.com/v1/mods/"
                    + modId
                    + "/files?pageSize="
                    + pageSize
                    + "&index="
                    + index
                    + "&gameId="
                    + CURSEFORGE_MINECRAFT_GAME_ID;

            JSONObject response = new JSONObject(httpGetString(url, apiKey));
            JSONObject pagination = response.optJSONObject("pagination");
            if (pagination != null) {
                totalCount = pagination.optInt("totalCount", totalCount);
            }

            JSONArray data = response.optJSONArray("data");
            if (data == null || data.length() == 0) break;

            for (int i = 0; i < data.length(); i++) {
                JSONObject file = data.optJSONObject(i);
                if (file == null) continue;

                String fileName = sanitizeFileName(optJsonString(file, "fileName"), "");
                if (isBlank(fileName) || !fileName.toLowerCase(Locale.US).endsWith(".zip")) continue;

                int fileId = file.optInt("id", 0);
                if (fileId <= 0) continue;

                ArrayList<String> gameVersions = jsonArrayToStringList(file.optJSONArray("gameVersions"));
                ArrayList<String> loaders = extractCurseForgeLoaders(gameVersions);
                String versionName = firstNonBlank(
                        optJsonString(file, "displayName"),
                        stripArchiveExtension(fileName)
                );
                String datePublished = firstNonBlank(
                        optJsonString(file, "fileDate"),
                        optJsonString(file, "dateCreated")
                );

                choices.add(new ModpackVersionChoice(
                        ModManagerSource.CURSEFORGE,
                        String.valueOf(fileId),
                        fileId,
                        versionName,
                        versionName,
                        fileName,
                        cleanDownloadUrl(optJsonString(file, "downloadUrl")),
                        gameVersions,
                        loaders,
                        datePublished
                ));
            }

            index += data.length();
            if (pagination == null || data.length() < pageSize) break;
        }

        return choices;
    }

    @NonNull
    private static File downloadCurseForgeModpackVersion(
            @NonNull Context context,
            @NonNull String projectId,
            @Nullable String title,
            @NonNull ModpackVersionChoice version,
            @NonNull Listener listener
    ) throws Exception {
        int modId = parsePositiveInt(projectId, "CurseForge project id");
        int fileId = version.fileId > 0 ? version.fileId : parsePositiveInt(version.versionId, "CurseForge file id");
        String apiKey = CurseForgeApiKeyProvider.resolve();
        if (isBlank(apiKey)) throw new IOException("Missing CurseForge API key.");

        JSONObject selected = new JSONObject(httpGetString(
                "https://api.curseforge.com/v1/mods/" + modId + "/files/" + fileId,
                apiKey
        )).optJSONObject("data");
        if (selected == null) throw new IOException("The selected CurseForge modpack file could not be loaded.");

        String fallbackName = sanitizeFileName(safe(title) + "-" + fileId + ".zip", "modpack.zip");
        String fileName = sanitizeFileName(optJsonString(selected, "fileName"), sanitizeFileName(version.fileName, fallbackName));
        String downloadUrl = resolveCurseForgeDownloadUrl(apiKey, modId, fileId, fileName, selected);
        if (isBlank(downloadUrl)) throw new IOException("CurseForge did not provide a downloadable URL for this modpack file: " + fileName);

        File out = new File(context.getCacheDir(), fileName);
        listener.onStatus("Downloading " + fileName + "...");
        downloadFile(downloadUrl, out, listener);
        return out;
    }

    private static File downloadModrinthModpack(
            @NonNull Context context,
            @NonNull String projectId,
            @Nullable String slug,
            @Nullable String title,
            @Nullable String preferredMinecraftVersion,
            @Nullable String preferredLoader,
            @NonNull Listener listener
    ) throws Exception {
        String lookup = !isBlank(projectId) ? projectId.trim() : safe(slug);
        if (isBlank(lookup)) throw new IllegalArgumentException("Missing Modrinth project id.");

        JSONArray versions = new JSONArray(httpGetString("https://api.modrinth.com/v2/project/" + urlEncode(lookup) + "/version", null));
        JSONObject selected = selectModrinthVersion(versions, preferredMinecraftVersion, preferredLoader);
        if (selected == null) throw new IOException("No compatible Modrinth modpack version was found.");

        JSONArray files = selected.optJSONArray("files");
        JSONObject primary = null;
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.optJSONObject(i);
                if (file == null) continue;
                String fileName = file.optString("filename", "");
                if (file.optBoolean("primary", false) || fileName.toLowerCase(Locale.US).endsWith(".mrpack")) {
                    primary = file;
                    if (file.optBoolean("primary", false)) break;
                }
            }
        }
        if (primary == null) throw new IOException("The selected Modrinth version does not contain an .mrpack file.");

        String url = primary.optString("url", "");
        if (isBlank(url)) throw new IOException("The Modrinth .mrpack download URL is missing.");

        String fileName = sanitizeFileName(primary.optString("filename", safe(title) + ".mrpack"), "modpack.mrpack");
        File out = new File(context.getCacheDir(), fileName);
        listener.onStatus("Downloading " + fileName + "...");
        downloadFile(url, out, listener);
        return out;
    }

    @Nullable
    private static JSONObject selectModrinthVersion(
            @NonNull JSONArray versions,
            @Nullable String preferredMinecraftVersion,
            @Nullable String preferredLoader
    ) {
        String mc = safe(preferredMinecraftVersion).trim();
        String loader = normalizeLoader(preferredLoader);

        JSONObject fallback = null;
        for (int i = 0; i < versions.length(); i++) {
            JSONObject version = versions.optJSONObject(i);
            if (version == null) continue;
            if (fallback == null) fallback = version;

            if (!isBlank(mc) && !jsonArrayContains(version.optJSONArray("game_versions"), mc)) continue;
            if (!isBlank(loader) && !jsonArrayContains(version.optJSONArray("loaders"), loader)) continue;
            return version;
        }
        return fallback;
    }

    private static File downloadCurseForgeModpack(
            @NonNull Context context,
            @NonNull String projectId,
            @Nullable String title,
            @Nullable String preferredMinecraftVersion,
            @Nullable String preferredLoader,
            @NonNull Listener listener
    ) throws Exception {
        int modId = parsePositiveInt(projectId, "CurseForge project id");
        String apiKey = CurseForgeApiKeyProvider.resolve();
        if (isBlank(apiKey)) throw new IOException("Missing CurseForge API key.");

        StringBuilder url = new StringBuilder("https://api.curseforge.com/v1/mods/")
                .append(modId)
                .append("/files?pageSize=50&index=0&gameId=")
                .append(CURSEFORGE_MINECRAFT_GAME_ID);
        if (!isBlank(preferredMinecraftVersion)) {
            url.append("&gameVersion=").append(urlEncode(preferredMinecraftVersion.trim()));
        }
        int loaderType = curseForgeLoaderType(preferredLoader);
        if (loaderType > 0) {
            url.append("&modLoaderType=").append(loaderType);
        }

        JSONObject response = new JSONObject(httpGetString(url.toString(), apiKey));
        JSONArray data = response.optJSONArray("data");
        JSONObject selected = null;
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject file = data.optJSONObject(i);
                if (file == null) continue;
                String name = file.optString("fileName", "").toLowerCase(Locale.US);
                if (name.endsWith(".zip")) {
                    selected = file;
                    break;
                }
            }
        }
        if (selected == null) throw new IOException("No compatible CurseForge modpack file was found.");

        int fileId = selected.optInt("id", 0);
        String fileName = sanitizeFileName(optJsonString(selected, "fileName"), sanitizeFileName(safe(title) + ".zip", "modpack.zip"));
        String downloadUrl = resolveCurseForgeDownloadUrl(apiKey, modId, fileId, fileName, selected);
        if (isBlank(downloadUrl)) throw new IOException("CurseForge did not provide a downloadable URL for this modpack file: " + fileName);

        File out = new File(context.getCacheDir(), fileName);
        listener.onStatus("Downloading " + fileName + "...");
        downloadFile(downloadUrl, out, listener);
        return out;
    }

    private static void installMrpack(
            @NonNull Context context,
            @NonNull File archive,
            @Nullable String iconUrl,
            @NonNull Listener listener
    ) throws Exception {
        listener.onStatus("Reading Modrinth modpack...");
        JSONObject index;
        try (ZipFile zip = new ZipFile(archive)) {
            index = new JSONObject(readZipEntryText(zip, "modrinth.index.json"));
        }

        JSONObject dependencies = index.optJSONObject("dependencies");
        if (dependencies == null) throw new IOException("modrinth.index.json is missing dependencies.");

        String minecraftVersion = dependencies.optString("minecraft", "").trim();
        if (isBlank(minecraftVersion)) throw new IOException("Modpack is missing the Minecraft dependency.");

        LoaderSpec loaderSpec = LoaderSpec.fromModrinthDependencies(dependencies);
        String instanceName = uniqueInstanceName(context, index.optString("name", "Modrinth Modpack"));

        File extractedIcon = extractPackIconToTempFile(context, archive, "");
        LauncherInstance instance;
        try {
            instance = createBaseInstance(context, instanceName, minecraftVersion, loaderSpec, iconUrl, extractedIcon, listener);
        } finally {
            deleteTempFile(extractedIcon);
        }
        File gameDirectory = instance.getGameDirectory();
        JSONArray installedContent = new JSONArray();

        listener.onStatus("Installing Modrinth files...");
        JSONArray files = index.optJSONArray("files");
        int totalFiles = files == null ? 0 : files.length();
        ArrayList<PendingModrinthInstalledFile> pendingModrinthFiles = new ArrayList<>();
        for (int i = 0; files != null && i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) continue;
            String relativePath = file.optString("path", "");
            if (!isSafeRelativePath(relativePath)) {
                throw new SecurityException("Blocked unsafe modpack path: " + relativePath);
            }
            File target = new File(gameDirectory, relativePath.replace('/', File.separatorChar));
            ensureParent(target);

            JSONObject installFileMetadata = new JSONObject(file.toString());
            listener.onStatus("Downloading " + relativePath + "...");
            String downloadUrl = downloadAndVerifyModrinthPackFile(
                    installFileMetadata,
                    target,
                    relativePath
            );
            pendingModrinthFiles.add(new PendingModrinthInstalledFile(target, relativePath, installFileMetadata, downloadUrl));
            listener.onProgress(i + 1, Math.max(1, totalFiles));
        }

        listener.onStatus("Resolving Modrinth update metadata...");
        Map<String, JSONObject> modrinthVersionsBySha1 = resolveModrinthVersionMetadataBySha1(pendingModrinthFiles);
        for (PendingModrinthInstalledFile pendingFile : pendingModrinthFiles) {
            String sha1 = resolveModrinthSha1(pendingFile.fileMetadata, pendingFile.target);
            JSONObject installedEntry = buildModrinthInstalledContentEntry(
                    gameDirectory,
                    pendingFile.target,
                    pendingFile.relativePath,
                    pendingFile.fileMetadata,
                    pendingFile.downloadUrl,
                    index.optString("name", instanceName),
                    minecraftVersion,
                    loaderSpec,
                    sha1,
                    modrinthVersionsBySha1.get(sha1)
            );
            if (installedEntry != null) installedContent.put(installedEntry);
        }

        listener.onStatus("Copying Modrinth overrides...");
        try (ZipFile zip = new ZipFile(archive)) {
            Map<String, String> saveWorldRemaps = new LinkedHashMap<>();
            copyZipPrefixToDirectory(zip, "overrides/", gameDirectory, saveWorldRemaps);
            copyZipPrefixToDirectory(zip, "client-overrides/", gameDirectory, saveWorldRemaps);
        }

        listener.onStatus("Resolving override metadata...");
        appendMissingModrinthMetadataForLocalContent(
                gameDirectory,
                installedContent,
                index.optString("name", instanceName),
                minecraftVersion,
                loaderSpec
        );

        writeInstalledContentMetadata(gameDirectory, "modrinth", index.optString("name", instanceName), minecraftVersion, loaderSpec, installedContent);
        registerInstalledContentWithModManagerManifest(gameDirectory, installedContent);
        writeInstalledPackManifest(gameDirectory, "modrinth", index.optString("name", instanceName), minecraftVersion, loaderSpec, null, null);
        listener.onComplete("Installed modpack: " + instanceName, instance);
    }

    private static void installCurseForgePack(
            @NonNull Context context,
            @NonNull File archive,
            @Nullable String iconUrl,
            @NonNull Listener listener
    ) throws Exception {
        listener.onStatus("Reading CurseForge modpack...");
        JSONObject manifest;
        try (ZipFile zip = new ZipFile(archive)) {
            manifest = new JSONObject(readZipEntryText(zip, "manifest.json"));
        }

        JSONObject minecraft = manifest.optJSONObject("minecraft");
        if (minecraft == null) throw new IOException("manifest.json is missing minecraft metadata.");
        String minecraftVersion = minecraft.optString("version", "").trim();
        if (isBlank(minecraftVersion)) throw new IOException("CurseForge pack is missing the Minecraft version.");

        LoaderSpec loaderSpec = LoaderSpec.fromCurseForgeManifest(minecraft.optJSONArray("modLoaders"));
        String instanceName = uniqueInstanceName(context, manifest.optString("name", "CurseForge Modpack"));
        File extractedIcon = extractPackIconToTempFile(context, archive, "");
        LauncherInstance instance;
        try {
            instance = createBaseInstance(context, instanceName, minecraftVersion, loaderSpec, iconUrl, extractedIcon, listener);
        } finally {
            deleteTempFile(extractedIcon);
        }
        File gameDirectory = instance.getGameDirectory();

        String apiKey = CurseForgeApiKeyProvider.resolve();
        if (isBlank(apiKey)) throw new IOException("Missing CurseForge API key.");

        listener.onStatus("Installing CurseForge files...");
        JSONArray installedContent = new JSONArray();
        JSONArray files = manifest.optJSONArray("files");
        int totalFiles = files == null ? 0 : files.length();
        int installedFiles = 0;
        ArrayList<String> skippedFiles = new ArrayList<>();

        if (files != null && files.length() > 0) {
            int workerCount = Math.max(1, Math.min(4, files.length()));
            listener.onStatus("Installing CurseForge files (" + workerCount + " downloads at once)...");
            ExecutorService executor = Executors.newFixedThreadPool(workerCount);
            CompletionService<CurseForgeInstallResult> completionService = new ExecutorCompletionService<>(executor);
            int submitted = 0;

            for (int i = 0; i < files.length(); i++) {
                final int index = i;
                final JSONObject entry = files.optJSONObject(i);
                completionService.submit(() -> installCurseForgeManifestEntry(
                        gameDirectory,
                        entry,
                        index,
                        apiKey,
                        manifest.optString("name", instanceName),
                        minecraftVersion,
                        loaderSpec,
                        listener
                ));
                submitted++;
            }

            executor.shutdown();
            for (int completed = 0; completed < submitted; completed++) {
                CurseForgeInstallResult result;
                try {
                    Future<CurseForgeInstallResult> future = completionService.take();
                    result = future.get();
                } catch (Throwable throwable) {
                    String reason = throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
                    result = CurseForgeInstallResult.skipped("unknown CurseForge entry skipped: " + reason);
                }

                if (result.installedFileCount > 0) {
                    installedFiles += result.installedFileCount;
                }
                if (result.installedEntry != null) {
                    installedContent.put(result.installedEntry);
                }
                if (!isBlank(result.warning)) {
                    skippedFiles.add(result.warning);
                }
                listener.onProgress(completed + 1, Math.max(1, totalFiles));
            }

            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }

        if (totalFiles > 0 && installedFiles == 0) {
            writeInstallWarnings(gameDirectory, skippedFiles);
            throw new IOException("CurseForge did not download any mod files. Check the API key, file availability, or CDN access.");
        }

        String overridesFolder = firstNonBlank(manifest.optString("overrides", ""), "overrides");
        listener.onStatus("Copying CurseForge overrides...");
        try (ZipFile zip = new ZipFile(archive)) {
            Map<String, String> saveWorldRemaps = new LinkedHashMap<>();
            String normalizedOverridesFolder = normalizeZipPrefix(overridesFolder);
            copyZipPrefixToDirectory(zip, normalizedOverridesFolder, gameDirectory, saveWorldRemaps);
            if (!"client-overrides/".equalsIgnoreCase(normalizedOverridesFolder)) {
                copyZipPrefixToDirectory(zip, "client-overrides/", gameDirectory, saveWorldRemaps);
            }
        }

        writeInstalledContentMetadata(gameDirectory, "curseforge", manifest.optString("name", instanceName), minecraftVersion, loaderSpec, installedContent);
        registerInstalledContentWithModManagerManifest(gameDirectory, installedContent);
        writeInstalledPackManifest(gameDirectory, "curseforge", manifest.optString("name", instanceName), minecraftVersion, loaderSpec, manifest, null);
        if (!skippedFiles.isEmpty()) {
            writeInstallWarnings(gameDirectory, skippedFiles);
            listener.onComplete("Installed modpack: " + instanceName + " (" + skippedFiles.size() + " unavailable file" + (skippedFiles.size() == 1 ? "" : "s") + " skipped)", instance);
        } else {
            listener.onComplete("Installed modpack: " + instanceName, instance);
        }
    }


    @NonNull
    private static CurseForgeInstallResult installCurseForgeManifestEntry(
            @NonNull File gameDirectory,
            @Nullable JSONObject entry,
            int index,
            @NonNull String apiKey,
            @NonNull String packName,
            @NonNull String minecraftVersion,
            @NonNull LoaderSpec loaderSpec,
            @NonNull Listener listener
    ) {
        if (entry == null) {
            return CurseForgeInstallResult.skipped("Invalid CurseForge file entry at index " + index);
        }

        int projectId = entry.optInt("projectID", entry.optInt("projectId", 0));
        int fileId = entry.optInt("fileID", entry.optInt("fileId", 0));
        String fallbackName = "project-" + projectId + "-" + fileId + ".jar";
        File target = null;

        if (projectId <= 0 || fileId <= 0) {
            return CurseForgeInstallResult.skipped("Invalid CurseForge file entry at index " + index);
        }

        try {
            JSONObject fileResponse = new JSONObject(httpGetString(
                    "https://api.curseforge.com/v1/mods/" + projectId + "/files/" + fileId,
                    apiKey
            )).optJSONObject("data");
            if (fileResponse == null) throw new IOException("Missing CurseForge file metadata");

            String fileName = sanitizeFileName(optJsonString(fileResponse, "fileName"), fallbackName);
            String downloadUrl = resolveCurseForgeDownloadUrl(apiKey, projectId, fileId, fileName, fileResponse);
            if (isBlank(downloadUrl)) throw new IOException("CurseForge did not provide a downloadable URL");

            CurseForgeFilePlacement placement = resolveCurseForgeFilePlacement(projectId, fileName, apiKey);
            target = new File(new File(gameDirectory, placement.targetFolderName), fileName);
            ensureParent(target);
            listener.onStatus("Downloading " + placement.targetFolderName + "/" + fileName + "...");
            downloadFile(downloadUrl, target, null);

            if (placement.worldArchive || downloadedZipLooksLikeWorld(target)) {
                verifyCurseForgeDownloadedFile(target, fileResponse, ModManagerContentType.RESOURCEPACKS, downloadUrl);
                int installedWorlds = extractWorldArchiveToSaves(gameDirectory, target, fileName, listener);
                if (!target.delete()) {
                    Logging.i(TAG, "Unable to delete temporary CurseForge world archive: " + target.getAbsolutePath());
                }
                return CurseForgeInstallResult.installedWorld(installedWorlds);
            }

            ModManagerContentType contentType = placement.contentType;
            verifyCurseForgeDownloadedFile(target, fileResponse, contentType, downloadUrl);
            JSONObject installedEntry = buildCurseForgeInstalledContentEntry(
                    gameDirectory,
                    target,
                    contentType,
                    fileResponse,
                    projectId,
                    fileId,
                    downloadUrl,
                    packName,
                    minecraftVersion,
                    loaderSpec
            );
            return CurseForgeInstallResult.installed(installedEntry);
        } catch (Throwable throwable) {
            if (target != null && target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
            String reason = throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
            String warning = fallbackName + " skipped: " + reason;
            Logging.i(TAG, "CurseForge modpack file skipped: " + projectId + ":" + fileId + " - " + reason);
            listener.onStatus("Skipping unavailable CurseForge file " + projectId + ":" + fileId);
            return CurseForgeInstallResult.skipped(warning);
        }
    }

    private static final class CurseForgeInstallResult {
        @Nullable
        final JSONObject installedEntry;
        @NonNull
        final String warning;
        final int installedFileCount;

        private CurseForgeInstallResult(
                @Nullable JSONObject installedEntry,
                @NonNull String warning,
                int installedFileCount
        ) {
            this.installedEntry = installedEntry;
            this.warning = warning;
            this.installedFileCount = installedFileCount;
        }

        @NonNull
        static CurseForgeInstallResult installed(@NonNull JSONObject installedEntry) {
            return new CurseForgeInstallResult(installedEntry, "", 1);
        }

        @NonNull
        static CurseForgeInstallResult installedWorld(int installedWorlds) {
            return new CurseForgeInstallResult(null, "", Math.max(1, installedWorlds));
        }

        @NonNull
        static CurseForgeInstallResult skipped(@NonNull String warning) {
            return new CurseForgeInstallResult(null, warning, 0);
        }
    }

    private static void installMultiMcPack(
            @NonNull Context context,
            @NonNull File archive,
            @NonNull String rootPrefix,
            @NonNull Listener listener
    ) throws Exception {
        listener.onStatus("Reading MultiMC/Prism modpack...");
        JSONObject pack;
        String instanceCfg = "";
        try (ZipFile zip = new ZipFile(archive)) {
            pack = new JSONObject(readZipEntryText(zip, rootPrefix + "mmc-pack.json"));
            ZipEntry cfgEntry = zip.getEntry(rootPrefix + "instance.cfg");
            if (cfgEntry != null && !cfgEntry.isDirectory()) {
                try (InputStream input = zip.getInputStream(cfgEntry)) {
                    instanceCfg = readToString(input);
                }
            }
        }

        String minecraftVersion = findMultiMcMinecraftVersion(pack);
        if (isBlank(minecraftVersion)) throw new IOException("mmc-pack.json is missing the Minecraft component.");

        LoaderSpec loaderSpec = LoaderSpec.fromMultiMcPackJson(pack);
        String cfgName = readMultiMcCfgValue(instanceCfg, "name");
        String instanceName = uniqueInstanceName(context, isBlank(cfgName) ? "MultiMC Modpack" : cfgName);

        File extractedIcon = extractPackIconToTempFile(context, archive, rootPrefix);
        LauncherInstance instance;
        try {
            instance = createBaseInstance(context, instanceName, minecraftVersion, loaderSpec, null, extractedIcon, listener);
        } finally {
            deleteTempFile(extractedIcon);
        }
        File gameDirectory = instance.getGameDirectory();

        listener.onStatus("Copying MultiMC/Prism .minecraft files...");
        try (ZipFile zip = new ZipFile(archive)) {
            String minecraftPrefix = findFirstExistingPrefix(zip,
                    rootPrefix + ".minecraft/",
                    rootPrefix + "minecraft/"
            );
            if (minecraftPrefix == null) {
                throw new IOException("MultiMC/Prism pack is missing the .minecraft folder.");
            }
            copyZipPrefixToDirectory(zip, minecraftPrefix, gameDirectory, new LinkedHashMap<>());
        }

        JSONObject extra = new JSONObject();
        extra.put("rootPrefix", rootPrefix);
        extra.put("mmcPack", pack);
        writeInstalledPackManifest(gameDirectory, "multimc", instanceName, minecraftVersion, loaderSpec, pack, extra);
        listener.onComplete("Installed modpack: " + instanceName, instance);
    }

    @Nullable
    private static String findMultiMcRootPrefix(@NonNull ZipFile zip) {
        if (zip.getEntry("mmc-pack.json") != null) return "";
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            String name = normalizeZipPath(entry.getName());
            if (name.endsWith("/mmc-pack.json")) {
                return name.substring(0, name.length() - "mmc-pack.json".length());
            }
        }
        return null;
    }

    @Nullable
    private static String findFirstExistingPrefix(@NonNull ZipFile zip, @NonNull String... prefixes) {
        for (String prefix : prefixes) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = normalizeZipPath(entry.getName());
                if (name.startsWith(prefix)) return prefix;
            }
        }
        return null;
    }

    @NonNull
    private static String findMultiMcMinecraftVersion(@NonNull JSONObject pack) {
        JSONArray components = pack.optJSONArray("components");
        if (components == null) return "";
        for (int i = 0; i < components.length(); i++) {
            JSONObject component = components.optJSONObject(i);
            if (component == null) continue;
            if ("net.minecraft".equalsIgnoreCase(component.optString("uid", ""))) {
                return component.optString("version", "").trim();
            }
        }
        return "";
    }

    @NonNull
    private static String readMultiMcCfgValue(@Nullable String cfg, @NonNull String key) {
        if (cfg == null) return "";
        String prefix = key + "=";
        String[] lines = cfg.replace("\r", "").split("\n");
        for (String line : lines) {
            if (line.startsWith(prefix)) return line.substring(prefix.length()).trim();
        }
        return "";
    }

    @NonNull
    private static LauncherInstance createBaseInstance(
            @NonNull Context context,
            @NonNull String instanceName,
            @NonNull String minecraftVersionId,
            @NonNull LoaderSpec loaderSpec,
            @Nullable String iconUrl,
            @Nullable File localIconFile,
            @NonNull Listener listener
    ) throws Exception {
        MinecraftVersion minecraftVersion = findMinecraftVersion(context, minecraftVersionId);
        if (minecraftVersion == null) throw new IOException("Minecraft " + minecraftVersionId + " was not found in the version manifest.");

        if (!isVanillaInstalled(minecraftVersionId)) {
            listener.onStatus("Installing Minecraft " + minecraftVersionId + "...");
            MinecraftVersionInstaller.installVanillaVersion(context, minecraftVersion, (progress, message) -> {
                listener.onStatus(message);
                listener.onProgress(progress, 100);
            });
        }

        String launchVersionId = minecraftVersionId;
        String loaderName = loaderSpec.loaderName;
        String loaderVersion = normalizeLoaderVersionForInstaller(loaderName, loaderSpec.loaderVersion, minecraftVersionId);
        if ("Fabric".equalsIgnoreCase(loaderName)) {
            listener.onStatus("Installing Fabric " + loaderVersion + "...");
            FabricInstaller.InstallResult result = FabricInstaller.installFabricVersion(
                    context,
                    minecraftVersion,
                    loaderVersion,
                    (progress, message) -> listener.onStatus(message)
            );
            launchVersionId = result.getFabricVersionId();
        } else if ("Forge".equalsIgnoreCase(loaderName)) {
            listener.onStatus("Installing Forge " + loaderVersion + "...");
            ForgeInstaller.InstallResult result = ForgeInstaller.installForgeVersion(
                    context,
                    minecraftVersion,
                    instanceName,
                    loaderVersion,
                    (progress, message) -> listener.onStatus(message)
            );
            launchVersionId = result.getForgeVersionId();
        } else if ("NeoForge".equalsIgnoreCase(loaderName)) {
            listener.onStatus("Installing NeoForge " + loaderVersion + "...");
            NeoForgeInstaller.InstallResult result = NeoForgeInstaller.installNeoForgeVersion(
                    context,
                    minecraftVersion,
                    instanceName,
                    loaderVersion,
                    (progress, message) -> listener.onStatus(message)
            );
            launchVersionId = result.getNeoForgeVersionId();
        } else if ("Quilt".equalsIgnoreCase(loaderName)) {
            throw new UnsupportedOperationException("Quilt modpacks are detected, but this DroidBridge build does not have a Quilt installer wired yet.");
        }


        listener.onStatus("Creating instance " + instanceName + "...");
        File temporaryIcon = localIconFile != null && localIconFile.isFile() ? null : downloadInstanceIcon(context, iconUrl);
        Uri iconUri = localIconFile != null && localIconFile.isFile()
                ? Uri.fromFile(localIconFile)
                : (temporaryIcon == null ? null : Uri.fromFile(temporaryIcon));
        LauncherInstance instance;
        try {
            instance = LauncherInstanceManager.createInstance(
                    context,
                    instanceName,
                    loaderName,
                    launchVersionId,
                    minecraftVersionId,
                    "release",
                    iconUri
            );
        } finally {
            if (temporaryIcon != null && temporaryIcon.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temporaryIcon.delete();
            }
        }

        File mods = new File(instance.getGameDirectory(), "mods");
        if (!mods.exists()) //noinspection ResultOfMethodCallIgnored
            mods.mkdirs();
        return instance;
    }

    @Nullable
    private static File extractPackIconToTempFile(@NonNull Context context, @NonNull File archive, @Nullable String rootPrefix) {
        if (!archive.isFile()) return null;
        String prefix = rootPrefix == null ? "" : normalizeZipPath(rootPrefix);
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry entry = findPackIconEntry(zip, prefix);
            if (entry == null || entry.isDirectory()) return null;
            if (entry.getSize() > 5L * 1024L * 1024L) {
                Logging.i(TAG, "Skipping oversized modpack icon: " + entry.getName());
                return null;
            }

            String extension = resolveIconExtension(entry.getName());
            File out = File.createTempFile("javalauncher-imported-modpack-icon-", extension, context.getCacheDir());
            try (InputStream input = zip.getInputStream(entry);
                 FileOutputStream output = new FileOutputStream(out)) {
                copyStream(input, output);
            }
            return out;
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to extract modpack icon: " + throwable.getMessage());
            return null;
        }
    }

    @Nullable
    private static ZipEntry findPackIconEntry(@NonNull ZipFile zip, @NonNull String rootPrefix) {
        ArrayList<String> exactNames = new ArrayList<>();
        exactNames.add(JAVALAUNCHER_PACK_ICON_ENTRY);
        exactNames.add("icon.png");
        exactNames.add("pack.png");
        exactNames.add("modpack-icon.png");
        exactNames.add("logo.png");
        exactNames.add("overrides/pack.png");
        exactNames.add("overrides/icon.png");
        exactNames.add("overrides/modpack-icon.png");
        exactNames.add("overrides/logo.png");

        if (!isBlank(rootPrefix)) {
            exactNames.add(rootPrefix + JAVALAUNCHER_PACK_ICON_ENTRY);
            exactNames.add(rootPrefix + "icon.png");
            exactNames.add(rootPrefix + "pack.png");
            exactNames.add(rootPrefix + "modpack-icon.png");
            exactNames.add(rootPrefix + "logo.png");
            exactNames.add(rootPrefix + ".minecraft/pack.png");
            exactNames.add(rootPrefix + ".minecraft/icon.png");
        }

        for (String name : exactNames) {
            ZipEntry entry = zip.getEntry(name);
            if (entry != null && !entry.isDirectory()) return entry;
        }

        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            String name = normalizeZipPath(entry.getName());
            String lower = name.toLowerCase(Locale.US);
            if (lower.equals(JAVALAUNCHER_PACK_ICON_ENTRY)
                    || lower.endsWith("/" + JAVALAUNCHER_PACK_ICON_ENTRY)
                    || lower.endsWith("/instance-icon.png")) {
                return entry;
            }
        }
        return null;
    }

    @NonNull
    private static String resolveIconExtension(@NonNull String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return ".jpg";
        if (lower.endsWith(".webp")) return ".webp";
        return ".png";
    }

    private static void deleteTempFile(@Nullable File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Nullable
    private static File downloadInstanceIcon(@NonNull Context context, @Nullable String iconUrl) {
        String cleanUrl = cleanDownloadUrl(iconUrl);
        if (!isHttpUrl(cleanUrl)) return null;

        File temporaryIcon = null;
        try {
            temporaryIcon = File.createTempFile("javalauncher-modpack-icon-", ".png", context.getCacheDir());
            downloadFile(cleanUrl, temporaryIcon, null);
            return temporaryIcon;
        } catch (Throwable throwable) {
            if (temporaryIcon != null && temporaryIcon.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temporaryIcon.delete();
            }
            Logging.i(TAG, "Unable to download modpack icon: " + throwable.getMessage());
            return null;
        }
    }

    private static void writeInstallWarnings(@NonNull File gameDirectory, @NonNull ArrayList<String> warnings) {
        if (warnings.isEmpty()) return;
        try {
            File metadataDirectory = getDroidBridgeMetadataDirectory(gameDirectory);
            File out = new File(metadataDirectory, MODPACK_INSTALL_WARNINGS_FILE);
            StringBuilder builder = new StringBuilder();
            builder.append("Some CurseForge files could not be downloaded.\n");
            builder.append("The pack may still launch if those files were optional or unavailable on CurseForge.\n\n");
            for (String warning : warnings) builder.append("- ").append(warning).append('\n');
            try (FileOutputStream output = new FileOutputStream(out)) {
                output.write(builder.toString().getBytes("UTF-8"));
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to write modpack install warnings", throwable);
        }
    }

    @NonNull
    private static File getDroidBridgeMetadataDirectory(@NonNull File gameDirectory) {
        File metadataDirectory = new File(gameDirectory, DROIDBRIDGE_METADATA_DIRECTORY);
        if (!metadataDirectory.exists()) {
            //noinspection ResultOfMethodCallIgnored
            metadataDirectory.mkdirs();
        }
        return metadataDirectory;
    }

    @Nullable
    private static MinecraftVersion findMinecraftVersion(@NonNull Context context, @NonNull String id) throws Exception {
        for (MinecraftVersion version : MinecraftVersionManifestClient.loadVersions(context)) {
            if (id.equals(version.getId())) return version;
        }
        return null;
    }

    private static boolean isVanillaInstalled(@NonNull String versionId) {
        for (MinecraftVersion version : MinecraftVersionInstaller.findInstalledVersions()) {
            if (versionId.equals(version.getId())) return true;
        }
        return false;
    }

    @NonNull
    private static String uniqueInstanceName(@NonNull Context context, @Nullable String rawName) {
        String base = sanitizeInstanceName(isBlank(rawName) ? "Imported Modpack" : rawName.trim());
        if (base.isEmpty()) base = "Imported Modpack";

        Set<String> existing = new HashSet<>();
        for (LauncherInstance instance : LauncherInstanceManager.findInstances(context)) {
            existing.add(instance.getName().trim().toLowerCase(Locale.US));
        }

        if (!existing.contains(base.toLowerCase(Locale.US))) return base;
        for (int i = 2; i < 1000; i++) {
            String candidate = base + " " + i;
            if (!existing.contains(candidate.toLowerCase(Locale.US))) return candidate;
        }
        return base + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    @NonNull
    private static String sanitizeInstanceName(@NonNull String rawName) {
        String name = rawName.trim().replace('\n', ' ').replace('\r', ' ');
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        while (name.contains("  ")) name = name.replace("  ", " ");
        if (".".equals(name) || "..".equals(name)) return "";
        return name;
    }

    private static boolean downloadedZipLooksLikeWorld(@NonNull File archive) {
        String lowerName = archive.getName().toLowerCase(Locale.US);
        if (!lowerName.endsWith(".zip") && !lowerName.endsWith(".zip.disabled")) return false;
        if (!looksLikeZipFile(archive)) return false;
        try (ZipFile worldZip = new ZipFile(archive)) {
            return !findWorldRoots(worldZip).isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int extractWorldArchiveToSaves(
            @NonNull File gameDirectory,
            @NonNull File archive,
            @NonNull String archiveFileName,
            @NonNull Listener listener
    ) throws Exception {
        if (!looksLikeZipFile(archive)) {
            throw new IOException("CurseForge world file is not a valid zip: " + archiveFileName);
        }

        try (ZipFile worldZip = new ZipFile(archive)) {
            ArrayList<String> worldRoots = findWorldRoots(worldZip);
            if (worldRoots.isEmpty()) {
                throw new IOException("CurseForge world archive does not contain level.dat: " + archiveFileName);
            }

            File savesDirectory = resolveSafeChild(gameDirectory, "saves");
            if (!savesDirectory.exists() && !savesDirectory.mkdirs()) {
                throw new IOException("Unable to create folder: " + savesDirectory.getAbsolutePath());
            }

            int installed = 0;
            for (String root : worldRoots) {
                String requestedWorldName = worldNameFromArchiveRoot(root, archiveFileName);
                File targetWorldDirectory = uniqueDirectory(savesDirectory, requestedWorldName);
                listener.onStatus("Installing world " + targetWorldDirectory.getName() + "...");
                copyWorldRootToDirectory(worldZip, root, targetWorldDirectory);
                installed++;
            }
            return installed;
        }
    }

    @NonNull
    private static ArrayList<String> findWorldRoots(@NonNull ZipFile zip) {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            String name = normalizeZipPath(entry.getName()).trim();
            while (name.startsWith("/")) name = name.substring(1);
            String lower = name.toLowerCase(Locale.US);
            if (lower.equals("level.dat")) {
                roots.add("");
            } else if (lower.endsWith("/level.dat")) {
                roots.add(name.substring(0, name.length() - "/level.dat".length()));
            }
        }
        return new ArrayList<>(roots);
    }

    @NonNull
    private static String worldNameFromArchiveRoot(@NonNull String root, @NonNull String archiveFileName) {
        String normalizedRoot = normalizeZipPath(root).trim();
        while (normalizedRoot.endsWith("/")) {
            normalizedRoot = normalizedRoot.substring(0, normalizedRoot.length() - 1);
        }
        if (isBlank(normalizedRoot)) {
            return sanitizeFileName(stripArchiveExtension(archiveFileName));
        }

        String[] parts = normalizedRoot.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("saves".equalsIgnoreCase(parts[i]) && !isBlank(parts[i + 1])) {
                return sanitizeFileName(parts[i + 1]);
            }
        }
        return sanitizeFileName(parts[parts.length - 1]);
    }

    private static void copyWorldRootToDirectory(
            @NonNull ZipFile zip,
            @NonNull String root,
            @NonNull File targetWorldDirectory
    ) throws Exception {
        String normalizedRoot = normalizeZipPath(root).trim();
        while (normalizedRoot.startsWith("/")) normalizedRoot = normalizedRoot.substring(1);
        while (normalizedRoot.endsWith("/")) normalizedRoot = normalizedRoot.substring(0, normalizedRoot.length() - 1);
        String rootPrefix = isBlank(normalizedRoot) ? "" : normalizedRoot + "/";

        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = normalizeZipPath(entry.getName()).trim();
            while (name.startsWith("/")) name = name.substring(1);
            if (!rootPrefix.isEmpty() && !name.startsWith(rootPrefix)) continue;

            String relative = rootPrefix.isEmpty() ? name : name.substring(rootPrefix.length());
            if (isBlank(relative)) continue;
            if (!isSafeRelativePath(relative)) continue;

            File output = resolveSafeChild(targetWorldDirectory, relative);
            if (entry.isDirectory()) {
                if (!output.exists() && !output.mkdirs()) {
                    throw new IOException("Unable to create folder: " + output.getAbsolutePath());
                }
                continue;
            }

            ensureParent(output);
            try (InputStream input = zip.getInputStream(entry);
                 FileOutputStream outputStream = new FileOutputStream(output)) {
                copyStream(input, outputStream);
            }
        }
    }

    private static void copyZipPrefixToDirectory(
            @NonNull ZipFile zip,
            @NonNull String prefix,
            @NonNull File targetDirectory,
            @NonNull Map<String, String> saveWorldRemaps
    ) throws Exception {
        String normalizedPrefix = normalizeZipPrefix(prefix);
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = normalizeZipPath(entry.getName());
            if (!name.startsWith(normalizedPrefix)) continue;

            String relative = normalizeOverrideRelativePath(name.substring(normalizedPrefix.length()));
            if (isBlank(relative) || !isSafeRelativePath(relative)) continue;

            File output = resolveOverrideTarget(targetDirectory, relative, saveWorldRemaps);
            if (entry.isDirectory()) {
                if (!output.exists() && !output.mkdirs()) {
                    throw new IOException("Unable to create folder: " + output.getAbsolutePath());
                }
                continue;
            }

            ensureParent(output);
            try (InputStream input = zip.getInputStream(entry);
                 FileOutputStream outputStream = new FileOutputStream(output)) {
                copyStream(input, outputStream);
            }
        }
    }

    @NonNull
    private static String normalizeOverrideRelativePath(@NonNull String relativePath) {
        String normalized = normalizeZipPath(relativePath).trim();
        while (normalized.startsWith("/")) normalized = normalized.substring(1);

        // Some desktop-launcher/private exports accidentally keep the game folder inside
        // overrides/client-overrides. Strip it so bundled worlds land in saves/ instead of
        // an unusable .minecraft/saves/ folder.
        String lower = normalized.toLowerCase(Locale.US);
        if (lower.startsWith(".minecraft/")) {
            normalized = normalized.substring(".minecraft/".length());
        } else if (lower.startsWith("minecraft/")) {
            normalized = normalized.substring("minecraft/".length());
        }
        return normalized;
    }

    @NonNull
    private static File resolveOverrideTarget(
            @NonNull File gameDirectory,
            @NonNull String relativePath,
            @NonNull Map<String, String> saveWorldRemaps
    ) throws IOException {
        String normalized = normalizeZipPath(relativePath);
        if (!normalized.toLowerCase(Locale.US).startsWith("saves/")) {
            return resolveSafeChild(gameDirectory, normalized);
        }

        String[] parts = normalized.split("/", 3);
        if (parts.length < 2 || isBlank(parts[1])) {
            return resolveSafeChild(gameDirectory, normalized);
        }

        File savesDirectory = resolveSafeChild(gameDirectory, "saves");
        if (!savesDirectory.exists() && !savesDirectory.mkdirs()) {
            throw new IOException("Unable to create folder: " + savesDirectory.getAbsolutePath());
        }

        String originalWorldName = sanitizeFileName(parts[1]);
        String targetWorldName = saveWorldRemaps.get(originalWorldName);
        if (isBlank(targetWorldName)) {
            File requestedWorldDirectory = new File(savesDirectory, originalWorldName);
            if (requestedWorldDirectory.exists()) {
                targetWorldName = uniqueDirectory(savesDirectory, requestedWorldDirectory.getName() + " Pack World").getName();
            } else {
                targetWorldName = originalWorldName;
            }
            saveWorldRemaps.put(originalWorldName, targetWorldName);
        }

        File targetWorldDirectory = new File(savesDirectory, targetWorldName);
        if (parts.length == 2) return targetWorldDirectory;
        return resolveSafeChild(targetWorldDirectory, parts[2]);
    }

    @NonNull
    private static File uniqueDirectory(@NonNull File parent, @NonNull String baseName) {
        String cleanBase = sanitizeFileName(baseName);
        File candidate = new File(parent, cleanBase);
        if (!candidate.exists()) return candidate;
        for (int i = 2; i < 1000; i++) {
            candidate = new File(parent, cleanBase + " " + i);
            if (!candidate.exists()) return candidate;
        }
        return new File(parent, cleanBase + " " + System.currentTimeMillis());
    }

    @NonNull
    private static File resolveSafeChild(@NonNull File parent, @NonNull String relativePath) throws IOException {
        if (!isSafeRelativePath(relativePath)) {
            throw new SecurityException("Blocked unsafe override path: " + relativePath);
        }

        File child = new File(parent, normalizeZipPath(relativePath).replace('/', File.separatorChar));
        String parentCanonical = parent.getCanonicalPath();
        String childCanonical = child.getCanonicalPath();
        if (!childCanonical.equals(parentCanonical) && !childCanonical.startsWith(parentCanonical + File.separator)) {
            throw new SecurityException("Blocked unsafe override path: " + relativePath);
        }
        return child;
    }


    /**
     * Modrinth .mrpack metadata only exists for entries listed in modrinth.index.json/files.
     * DroidBridge-created private exports, older exports, and some hand-made packs can place
     * mods in overrides/ instead. Those files still need update metadata, so resolve any missing
     * mods/resourcepacks/shaderpacks by SHA-1 through Modrinth's version-file lookup and append
     * normal DroidBridge metadata entries for files that are actually hosted by Modrinth.
     */
    private static void appendMissingModrinthMetadataForLocalContent(
            @NonNull File gameDirectory,
            @NonNull JSONArray installedContent,
            @NonNull String packName,
            @NonNull String minecraftVersion,
            @NonNull LoaderSpec loaderSpec
    ) {
        try {
            HashSet<String> existingRelativePaths = new HashSet<>();
            for (int i = 0; i < installedContent.length(); i++) {
                JSONObject entry = installedContent.optJSONObject(i);
                if (entry == null) continue;
                String relativePath = normalizeZipPath(firstNonBlank(
                        entry.optString("relativePath", ""),
                        firstNonBlank(entry.optString("filePath", ""), entry.optString("path", ""))
                )).toLowerCase(Locale.US);
                if (!isBlank(relativePath)) existingRelativePaths.add(relativePath);
            }

            ArrayList<PendingModrinthInstalledFile> pendingFiles = new ArrayList<>();
            collectMissingLocalModrinthCandidates(gameDirectory, "mods", existingRelativePaths, pendingFiles);
            collectMissingLocalModrinthCandidates(gameDirectory, "resourcepacks", existingRelativePaths, pendingFiles);
            collectMissingLocalModrinthCandidates(gameDirectory, "shaderpacks", existingRelativePaths, pendingFiles);
            if (pendingFiles.isEmpty()) return;

            Map<String, JSONObject> modrinthVersionsBySha1 = resolveModrinthVersionMetadataBySha1(pendingFiles);
            for (PendingModrinthInstalledFile pendingFile : pendingFiles) {
                String sha1 = resolveModrinthSha1(pendingFile.fileMetadata, pendingFile.target);
                JSONObject versionMetadata = modrinthVersionsBySha1.get(sha1);
                if (versionMetadata == null) {
                    continue;
                }

                String downloadUrl = selectModrinthDownloadUrl(versionMetadata, sha1);
                if (!isBlank(downloadUrl)) {
                    JSONArray downloads = new JSONArray();
                    downloads.put(downloadUrl);
                    pendingFile.fileMetadata.put("downloads", downloads);
                }

                JSONObject installedEntry = buildModrinthInstalledContentEntry(
                        gameDirectory,
                        pendingFile.target,
                        pendingFile.relativePath,
                        pendingFile.fileMetadata,
                        downloadUrl,
                        packName,
                        minecraftVersion,
                        loaderSpec,
                        sha1,
                        versionMetadata
                );
                if (installedEntry != null) installedContent.put(installedEntry);
            }
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to resolve Modrinth metadata for override files: " + throwable.getMessage());
        }
    }

    private static void collectMissingLocalModrinthCandidates(
            @NonNull File gameDirectory,
            @NonNull String folder,
            @NonNull HashSet<String> existingRelativePaths,
            @NonNull ArrayList<PendingModrinthInstalledFile> out
    ) {
        File directory = new File(gameDirectory, folder);
        File[] files = directory.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isHidden() || !file.isFile()) continue;
            String lowerName = file.getName().toLowerCase(Locale.US);
            if (!(lowerName.endsWith(".jar") || lowerName.endsWith(".zip"))) continue;

            String relativePath = folder + "/" + file.getName();
            String key = normalizeZipPath(relativePath).toLowerCase(Locale.US);
            if (existingRelativePaths.contains(key)) continue;

            try {
                JSONObject fileMetadata = buildLocalModrinthFileMetadata(file, relativePath);
                out.add(new PendingModrinthInstalledFile(file, relativePath, fileMetadata, ""));
            } catch (Throwable throwable) {
                Logging.i(TAG, "Unable to prepare local Modrinth metadata candidate for " + file.getName() + ": " + throwable.getMessage());
            }
        }
    }

    @NonNull
    private static JSONObject buildLocalModrinthFileMetadata(
            @NonNull File file,
            @NonNull String relativePath
    ) throws Exception {
        JSONObject fileMetadata = new JSONObject();
        fileMetadata.put("path", relativePath);
        fileMetadata.put("fileSize", file.length());
        JSONObject hashes = new JSONObject();
        hashes.put("sha1", hashFile(file, "SHA-1"));
        hashes.put("sha512", hashFile(file, "SHA-512"));
        fileMetadata.put("hashes", hashes);
        fileMetadata.put("downloads", new JSONArray());
        JSONObject env = new JSONObject();
        env.put("client", "required");
        env.put("server", "optional");
        fileMetadata.put("env", env);
        return fileMetadata;
    }

    @NonNull
    private static String selectModrinthDownloadUrl(@Nullable JSONObject versionMetadata, @Nullable String wantedSha1) {
        if (versionMetadata == null) return "";
        JSONArray files = versionMetadata.optJSONArray("files");
        if (files == null || files.length() == 0) return "";

        String primaryUrl = "";
        String firstUrl = "";
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) continue;
            String url = file.optString("url", "").trim();
            if (isBlank(url)) continue;
            if (isBlank(firstUrl)) firstUrl = url;
            if (file.optBoolean("primary", false)) primaryUrl = url;

            JSONObject hashes = file.optJSONObject("hashes");
            String sha1 = hashes == null ? "" : hashes.optString("sha1", "").trim();
            if (!isBlank(wantedSha1) && wantedSha1.equalsIgnoreCase(sha1)) {
                return url;
            }
        }
        return firstNonBlank(primaryUrl, firstUrl);
    }

    @NonNull
    private static Map<String, JSONObject> resolveModrinthVersionMetadataBySha1(
            @NonNull ArrayList<PendingModrinthInstalledFile> files
    ) {
        HashMap<String, JSONObject> out = new HashMap<>();
        LinkedHashSet<String> hashes = new LinkedHashSet<>();
        for (PendingModrinthInstalledFile file : files) {
            String sha1 = resolveModrinthSha1(file.fileMetadata, file.target);
            if (!isBlank(sha1)) hashes.add(sha1);
        }
        if (hashes.isEmpty()) return out;

        try {
            JSONObject request = new JSONObject();
            JSONArray hashArray = new JSONArray();
            for (String hash : hashes) hashArray.put(hash);
            request.put("hashes", hashArray);
            request.put("algorithm", "sha1");

            JSONObject response = new JSONObject(httpPostString(
                    "https://api.modrinth.com/v2/version_files",
                    request,
                    null
            ));

            for (String hash : hashes) {
                JSONObject version = response.optJSONObject(hash);
                if (version != null) out.put(hash, version);
            }
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to resolve Modrinth update metadata from hashes: " + throwable.getMessage());
        }
        return out;
    }

    @NonNull
    private static String resolveModrinthSha1(@NonNull JSONObject mrpackFile, @NonNull File target) {
        JSONObject hashes = mrpackFile.optJSONObject("hashes");
        String declaredSha1 = hashes == null ? "" : hashes.optString("sha1", "").trim();
        if (!isBlank(declaredSha1)) return declaredSha1;
        try {
            return hashFile(target, "SHA-1");
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to hash Modrinth file " + target.getName() + ": " + throwable.getMessage());
            return "";
        }
    }

    @Nullable
    private static JSONObject buildModrinthInstalledContentEntry(
            @NonNull File gameDirectory,
            @NonNull File target,
            @NonNull String relativePath,
            @NonNull JSONObject mrpackFile,
            @NonNull String downloadUrl,
            @NonNull String packName,
            @NonNull String minecraftVersion,
            @NonNull LoaderSpec loaderSpec,
            @NonNull String resolvedSha1,
            @Nullable JSONObject versionMetadata
    ) throws Exception {
        ModManagerContentType contentType = resolveContentTypeForRelativePath(relativePath);
        if (contentType == null) return null;

        ParsedModrinthDownloadIds parsedIds = parseModrinthDownloadIds(downloadUrl);
        String projectId = firstNonBlank(
                versionMetadata == null ? "" : versionMetadata.optString("project_id", ""),
                parsedIds.projectId
        );
        String versionId = firstNonBlank(
                versionMetadata == null ? "" : versionMetadata.optString("id", ""),
                parsedIds.versionId
        );
        String versionNumber = firstNonBlank(
                versionMetadata == null ? "" : versionMetadata.optString("version_number", ""),
                versionId
        );

        JSONObject entry = buildInstalledContentBaseEntry(
                gameDirectory,
                target,
                relativePath,
                contentType,
                ModManagerSource.MODRINTH,
                packName,
                minecraftVersion,
                loaderSpec
        );

        JSONObject hashes = mrpackFile.optJSONObject("hashes");
        entry.put("platform", "modrinth");
        entry.put("modpackPlatform", "modrinth");
        entry.put("installedFromPlatformModpack", true);
        entry.put("updateReady", !isBlank(projectId));
        entry.put("platformProjectId", projectId);
        entry.put("projectId", projectId);
        entry.put("modrinthProjectId", projectId);
        entry.put("platformVersionId", versionId);
        entry.put("versionId", versionId);
        entry.put("modrinthVersionId", versionId);
        entry.put("versionNumber", versionNumber);
        entry.put("downloadUrl", downloadUrl);
        entry.put("downloads", mrpackFile.optJSONArray("downloads") == null ? new JSONArray().put(downloadUrl) : mrpackFile.optJSONArray("downloads"));
        entry.put("fileSize", target.length());
        entry.put("declaredFileSize", mrpackFile.optLong("fileSize", target.length()));
        entry.put("hashes", hashes == null ? new JSONObject() : hashes);
        entry.put("sha1", firstNonBlank(resolvedSha1, hashes == null ? "" : hashes.optString("sha1", "")));
        entry.put("sha512", hashes == null ? "" : hashes.optString("sha512", ""));
        entry.put("modrinthPackFile", mrpackFile);
        if (versionMetadata != null) {
            entry.put("modrinthVersion", versionMetadata);
            entry.put("gameVersions", versionMetadata.optJSONArray("game_versions") == null ? new JSONArray() : versionMetadata.optJSONArray("game_versions"));
            entry.put("loaders", versionMetadata.optJSONArray("loaders") == null ? new JSONArray() : versionMetadata.optJSONArray("loaders"));
        }
        return entry;
    }

    @NonNull
    private static JSONObject buildCurseForgeInstalledContentEntry(
            @NonNull File gameDirectory,
            @NonNull File target,
            @NonNull ModManagerContentType contentType,
            @NonNull JSONObject fileMetadata,
            int projectId,
            int fileId,
            @NonNull String downloadUrl,
            @NonNull String packName,
            @NonNull String minecraftVersion,
            @NonNull LoaderSpec loaderSpec
    ) throws Exception {
        String relativePath = getTargetFolderName(contentType) + "/" + target.getName();
        JSONObject entry = buildInstalledContentBaseEntry(
                gameDirectory,
                target,
                relativePath,
                contentType,
                ModManagerSource.CURSEFORGE,
                packName,
                minecraftVersion,
                loaderSpec
        );

        entry.put("platform", "curseforge");
        entry.put("modpackPlatform", "curseforge");
        entry.put("installedFromPlatformModpack", true);
        entry.put("updateReady", true);
        entry.put("platformProjectId", String.valueOf(projectId));
        entry.put("projectId", String.valueOf(projectId));
        entry.put("curseForgeProjectId", projectId);
        entry.put("platformFileId", String.valueOf(fileId));
        entry.put("fileId", String.valueOf(fileId));
        entry.put("curseForgeFileId", fileId);
        entry.put("versionNumber", optJsonString(fileMetadata, "displayName"));
        entry.put("downloadUrl", downloadUrl);
        entry.put("fileDate", optJsonString(fileMetadata, "fileDate"));
        entry.put("releaseType", fileMetadata.optInt("releaseType", 0));
        entry.put("gameVersions", fileMetadata.optJSONArray("gameVersions") == null ? new JSONArray() : fileMetadata.optJSONArray("gameVersions"));
        entry.put("hashes", fileMetadata.optJSONArray("hashes") == null ? new JSONArray() : fileMetadata.optJSONArray("hashes"));
        entry.put("sha1", resolveCurseForgeSha1(fileMetadata.optJSONArray("hashes")));
        entry.put("fileSize", target.length());
        entry.put("declaredFileSize", fileMetadata.optLong("fileLength", target.length()));
        entry.put("curseForgeFile", fileMetadata);
        return entry;
    }

    @NonNull
    private static JSONObject buildInstalledContentBaseEntry(
            @NonNull File gameDirectory,
            @NonNull File target,
            @NonNull String relativePath,
            @NonNull ModManagerContentType contentType,
            @NonNull ModManagerSource source,
            @NonNull String packName,
            @NonNull String minecraftVersion,
            @NonNull LoaderSpec loaderSpec
    ) throws Exception {
        JSONObject entry = new JSONObject();
        entry.put("source", source.getId());
        entry.put("platform", source.getId());
        entry.put("contentType", contentType.getIntentValue());
        entry.put("type", contentType.getIntentValue());
        entry.put("fileName", target.getName());
        entry.put("name", stripExtension(target.getName()));
        entry.put("displayName", stripExtension(target.getName()));
        entry.put("filePath", relativePath);
        entry.put("relativePath", relativePath);
        entry.put("path", relativePath);
        entry.put("absolutePath", target.getAbsolutePath());
        entry.put("canonicalPath", safeCanonicalPath(target));
        entry.put("enabled", true);
        entry.put("installedAt", System.currentTimeMillis());
        entry.put("installedBy", "modpack");
        entry.put("modpackName", packName);
        entry.put("minecraftVersion", minecraftVersion);
        entry.put("loader", loaderSpec.loaderName);
        entry.put("loaderVersion", safe(loaderSpec.loaderVersion));
        entry.put("gameDirectory", gameDirectory.getAbsolutePath());
        return entry;
    }

    private static int countUpdateReadyFiles(@NonNull JSONArray files) {
        int count = 0;
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file != null && file.optBoolean("updateReady", false)) count++;
        }
        return count;
    }

    private static void writeInstalledContentMetadata(
            @NonNull File gameDirectory,
            @NonNull String platform,
            @NonNull String packName,
            @NonNull String minecraftVersion,
            @NonNull LoaderSpec loaderSpec,
            @NonNull JSONArray files
    ) {
        try {
            File metadataDirectory = getDroidBridgeMetadataDirectory(gameDirectory);
            JSONObject out = new JSONObject();
            out.put("schemaVersion", 1);
            out.put("type", "modpack-installed-content");
            out.put("platform", platform);
            out.put("packName", packName);
            out.put("minecraftVersion", minecraftVersion);
            out.put("loader", loaderSpec.loaderName);
            out.put("loaderVersion", safe(loaderSpec.loaderVersion));
            out.put("installedAt", System.currentTimeMillis());
            out.put("fileCount", files.length());
            out.put("updateReadyFileCount", countUpdateReadyFiles(files));
            out.put("files", files);
            try (FileOutputStream output = new FileOutputStream(new File(metadataDirectory, MODPACK_FILES_MANIFEST_FILE))) {
                output.write(out.toString(2).getBytes("UTF-8"));
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to write modpack installed-content metadata", throwable);
        }
    }

    /**
     * Best-effort bridge into the existing installed-content update database.
     *
     * DroidBridge already updates individual content from ModManagerManifest entries.
     * This keeps the modpack importer independent by storing .javalauncher/modpack_files_manifest.json,
     * then attempts to call an existing ModManagerManifest registration method if this project has one.
     */
    private static void registerInstalledContentWithModManagerManifest(
            @NonNull File gameDirectory,
            @NonNull JSONArray installedContent
    ) {
        for (int i = 0; i < installedContent.length(); i++) {
            JSONObject entry = installedContent.optJSONObject(i);
            if (entry == null) continue;
            ModManagerContentType type = contentTypeFromIntentValue(entry.optString("contentType", ""));
            if (type == null) continue;
            tryRegisterInstalledEntry(gameDirectory, type, entry);
        }
    }

    private static void tryRegisterInstalledEntry(
            @NonNull File gameDirectory,
            @NonNull ModManagerContentType type,
            @NonNull JSONObject entry
    ) {
        try {
            Method[] methods = ModManagerManifest.class.getDeclaredMethods();
            for (Method method : methods) {
                if (!Modifier.isStatic(method.getModifiers())) continue;
                Class<?>[] parameterTypes = method.getParameterTypes();
                String name = method.getName().toLowerCase(Locale.US);
                boolean likelyRegisterMethod = name.contains("add")
                        || name.contains("put")
                        || name.contains("save")
                        || name.contains("record")
                        || name.contains("register")
                        || name.contains("entry");
                if (!likelyRegisterMethod) continue;

                if (parameterTypes.length == 3
                        && File.class.equals(parameterTypes[0])
                        && ModManagerContentType.class.equals(parameterTypes[1])
                        && JSONObject.class.equals(parameterTypes[2])) {
                    method.setAccessible(true);
                    method.invoke(null, gameDirectory, type, entry);
                    return;
                }

                if (parameterTypes.length == 4
                        && File.class.equals(parameterTypes[0])
                        && ModManagerContentType.class.equals(parameterTypes[1])
                        && File.class.equals(parameterTypes[2])
                        && JSONObject.class.equals(parameterTypes[3])) {
                    method.setAccessible(true);
                    File installedFile = resolveInstalledContentFile(gameDirectory, entry);
                    if (installedFile != null) method.invoke(null, gameDirectory, type, installedFile, entry);
                    return;
                }
            }
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to mirror modpack file into ModManagerManifest: " + throwable.getMessage());
        }
    }

    @Nullable
    private static File resolveInstalledContentFile(@NonNull File gameDirectory, @NonNull JSONObject entry) {
        String relativePath = entry.optString("relativePath", entry.optString("filePath", ""));
        if (!isSafeRelativePath(relativePath)) return null;
        return new File(gameDirectory, relativePath.replace('/', File.separatorChar));
    }

    @Nullable
    private static ModManagerContentType resolveContentTypeForRelativePath(@NonNull String relativePath) {
        String path = normalizeZipPath(relativePath).toLowerCase(Locale.US);
        if (path.startsWith("mods/") && (path.endsWith(".jar") || path.endsWith(".jar.disabled"))) {
            return ModManagerContentType.MODS;
        }
        if (path.startsWith("resourcepacks/") && (path.endsWith(".zip") || path.endsWith(".zip.disabled"))) {
            return ModManagerContentType.RESOURCEPACKS;
        }
        if (path.startsWith("shaderpacks/") && (path.endsWith(".zip") || path.endsWith(".zip.disabled"))) {
            return ModManagerContentType.SHADERPACKS;
        }
        return null;
    }

    @Nullable
    private static ModManagerContentType contentTypeFromIntentValue(@Nullable String value) {
        if (value == null) return null;
        if ("mods".equalsIgnoreCase(value)) return ModManagerContentType.MODS;
        if ("resourcepacks".equalsIgnoreCase(value)) return ModManagerContentType.RESOURCEPACKS;
        if ("shaderpacks".equalsIgnoreCase(value) || "shaders".equalsIgnoreCase(value)) return ModManagerContentType.SHADERPACKS;
        return null;
    }

    private static final class CurseForgeFilePlacement {
        @NonNull
        final ModManagerContentType contentType;
        @NonNull
        final String targetFolderName;
        final boolean worldArchive;

        private CurseForgeFilePlacement(
                @NonNull ModManagerContentType contentType,
                @NonNull String targetFolderName,
                boolean worldArchive
        ) {
            this.contentType = contentType;
            this.targetFolderName = targetFolderName;
            this.worldArchive = worldArchive;
        }

        @NonNull
        static CurseForgeFilePlacement normal(@NonNull ModManagerContentType contentType) {
            return new CurseForgeFilePlacement(contentType, getTargetFolderName(contentType), false);
        }

        @NonNull
        static CurseForgeFilePlacement worldDownload() {
            return new CurseForgeFilePlacement(ModManagerContentType.RESOURCEPACKS,
                    DROIDBRIDGE_METADATA_DIRECTORY + "/world-downloads",
                    true);
        }
    }

    @NonNull
    private static CurseForgeFilePlacement resolveCurseForgeFilePlacement(
            int projectId,
            @NonNull String fileName,
            @NonNull String apiKey
    ) {
        try {
            String body = httpGetString(
                    "https://api.curseforge.com/v1/mods/" + projectId,
                    apiKey
            );
            JSONObject root = new JSONObject(body);
            JSONObject data = root.optJSONObject("data");
            if (isCurseForgeWorldProject(data)) {
                return CurseForgeFilePlacement.worldDownload();
            }

            int classId = data == null ? 0 : data.optInt("classId", 0);
            if (classId == ModManagerContentType.SHADERPACKS.getCurseForgeClassId()) {
                return CurseForgeFilePlacement.normal(ModManagerContentType.SHADERPACKS);
            }
            if (classId == ModManagerContentType.RESOURCEPACKS.getCurseForgeClassId()) {
                return CurseForgeFilePlacement.normal(ModManagerContentType.RESOURCEPACKS);
            }
            if (classId == ModManagerContentType.MODS.getCurseForgeClassId()) {
                return CurseForgeFilePlacement.normal(ModManagerContentType.MODS);
            }

            ModManagerContentType categoryType = resolveCurseForgeContentTypeFromCategories(data);
            if (categoryType != null) return CurseForgeFilePlacement.normal(categoryType);
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to resolve CurseForge content placement for "
                    + projectId
                    + ": "
                    + (throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage()));
        }

        return CurseForgeFilePlacement.normal(resolveCurseForgeContentTypeFromFileName(fileName));
    }

    private static boolean isCurseForgeWorldProject(@Nullable JSONObject projectMetadata) {
        if (projectMetadata == null) return false;
        if (projectMetadata.optInt("classId", 0) == CURSEFORGE_WORLDS_CLASS_ID) return true;

        String classSlug = (projectMetadata.optString("classSlug", "")
                + " "
                + projectMetadata.optString("slug", "")
                + " "
                + projectMetadata.optString("links", "")).toLowerCase(Locale.US);
        if (classSlug.contains("/worlds") || classSlug.contains("minecraft/worlds") || classSlug.equals("worlds")) {
            return true;
        }

        JSONArray categories = projectMetadata.optJSONArray("categories");
        if (categories == null) return false;
        for (int i = 0; i < categories.length(); i++) {
            JSONObject category = categories.optJSONObject(i);
            if (category == null) continue;
            String value = (category.optString("name", "")
                    + " "
                    + category.optString("slug", "")
                    + " "
                    + category.optString("url", "")).toLowerCase(Locale.US);
            if (value.contains("/worlds")
                    || value.contains("minecraft/worlds")
                    || "worlds".equals(value.trim())
                    || value.contains(" worlds ")) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    private static ModManagerContentType resolveCurseForgeContentType(
            int projectId,
            @NonNull String fileName,
            @NonNull String apiKey
    ) {
        try {
            String body = httpGetString(
                    "https://api.curseforge.com/v1/mods/" + projectId,
                    apiKey
            );
            JSONObject root = new JSONObject(body);
            JSONObject data = root.optJSONObject("data");
            int classId = data == null ? 0 : data.optInt("classId", 0);
            if (classId == ModManagerContentType.SHADERPACKS.getCurseForgeClassId()) {
                return ModManagerContentType.SHADERPACKS;
            }
            if (classId == ModManagerContentType.RESOURCEPACKS.getCurseForgeClassId()) {
                return ModManagerContentType.RESOURCEPACKS;
            }
            if (classId == ModManagerContentType.MODS.getCurseForgeClassId()) {
                return ModManagerContentType.MODS;
            }

            ModManagerContentType categoryType = resolveCurseForgeContentTypeFromCategories(data);
            if (categoryType != null) return categoryType;
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to resolve CurseForge content type for "
                    + projectId
                    + ": "
                    + (throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage()));
        }

        return resolveCurseForgeContentTypeFromFileName(fileName);
    }

    @NonNull
    private static ModManagerContentType resolveCurseForgeContentTypeFromFileName(@NonNull String fileName) {
        String lowerName = fileName.toLowerCase(Locale.US);
        if (lowerName.endsWith(".jar") || lowerName.endsWith(".jar.disabled")) return ModManagerContentType.MODS;
        if (looksLikeShaderPackName(lowerName)) return ModManagerContentType.SHADERPACKS;
        if (lowerName.endsWith(".zip") || lowerName.endsWith(".zip.disabled")) return ModManagerContentType.RESOURCEPACKS;
        return ModManagerContentType.MODS;
    }

    @Nullable
    private static ModManagerContentType resolveCurseForgeContentTypeFromCategories(@Nullable JSONObject projectMetadata) {
        if (projectMetadata == null) return null;
        JSONArray categories = projectMetadata.optJSONArray("categories");
        if (categories == null) return null;
        for (int i = 0; i < categories.length(); i++) {
            JSONObject category = categories.optJSONObject(i);
            if (category == null) continue;
            String value = (category.optString("name", "")
                    + " "
                    + category.optString("slug", "")
                    + " "
                    + category.optString("url", "")).toLowerCase(Locale.US);
            if (value.contains("shader")) return ModManagerContentType.SHADERPACKS;
            if (value.contains("resource") || value.contains("texture")) return ModManagerContentType.RESOURCEPACKS;
        }
        return null;
    }

    private static boolean looksLikeShaderPackName(@NonNull String lowerName) {
        return lowerName.contains("shader")
                || lowerName.contains("shaders")
                || lowerName.contains("complementary")
                || lowerName.contains("sildur")
                || lowerName.contains("bsl")
                || lowerName.contains("makeup")
                || lowerName.contains("potato")
                || lowerName.contains("insanity")
                || lowerName.contains("photon")
                || lowerName.contains("mellow")
                || lowerName.contains("bliss")
                || lowerName.contains("miniature");
    }

    @NonNull
    private static String getTargetFolderName(@NonNull ModManagerContentType type) {
        if (type == ModManagerContentType.SHADERPACKS) return "shaderpacks";
        if (type == ModManagerContentType.RESOURCEPACKS) return "resourcepacks";
        return "mods";
    }

    private static void verifyCurseForgeDownloadedFile(
            @NonNull File target,
            @NonNull JSONObject fileMetadata,
            @NonNull ModManagerContentType contentType,
            @NonNull String downloadUrl
    ) throws Exception {
        String expectedSha1 = resolveCurseForgeSha1(fileMetadata.optJSONArray("hashes"));
        if (!isBlank(expectedSha1)) {
            String actualSha1 = hashFile(target, "SHA-1");
            if (!expectedSha1.equalsIgnoreCase(actualSha1)) {
                throw new IOException("Downloaded file hash mismatch for "
                        + target.getName()
                        + " from "
                        + downloadUrl);
            }
        }

        String lowerName = target.getName().toLowerCase(Locale.US);
        if ((contentType == ModManagerContentType.SHADERPACKS || contentType == ModManagerContentType.RESOURCEPACKS)
                && (lowerName.endsWith(".zip") || lowerName.endsWith(".zip.disabled"))
                && !looksLikeZipFile(target)) {
            throw new IOException("Downloaded shader/resource pack is not a valid zip: " + target.getName());
        }
    }

    private static boolean looksLikeZipFile(@NonNull File file) {
        if (!file.isFile() || file.length() < 4) return false;
        try (InputStream input = new FileInputStream(file)) {
            byte[] header = new byte[4];
            int read = input.read(header);
            if (read < 4) return false;
            return header[0] == 'P' && header[1] == 'K';
        } catch (Throwable ignored) {
            return false;
        }
    }

    @NonNull
    private static String resolveCurseForgeSha1(@Nullable JSONArray hashes) {
        if (hashes == null) return "";
        for (int i = 0; i < hashes.length(); i++) {
            JSONObject hash = hashes.optJSONObject(i);
            if (hash == null) continue;
            String value = hash.optString("value", "").trim();
            int algo = hash.optInt("algo", 0);
            if (algo == 1 && !isBlank(value)) return value;
        }
        return "";
    }

    @NonNull
    private static ParsedModrinthDownloadIds parseModrinthDownloadIds(@Nullable String downloadUrl) {
        String url = cleanDownloadUrl(downloadUrl);
        int dataIndex = url.indexOf("/data/");
        if (dataIndex < 0) return new ParsedModrinthDownloadIds("", "");
        String rest = url.substring(dataIndex + "/data/".length());
        String[] parts = rest.split("/");
        String projectId = parts.length > 0 ? parts[0].trim() : "";
        String versionId = parts.length > 2 && "versions".equals(parts[1]) ? parts[2].trim() : "";
        return new ParsedModrinthDownloadIds(projectId, versionId);
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
    private static String stripExtension(@NonNull String name) {
        String clean = name;
        if (clean.toLowerCase(Locale.US).endsWith(".disabled")) {
            clean = clean.substring(0, clean.length() - ".disabled".length());
        }
        int dot = clean.lastIndexOf('.');
        return dot > 0 ? clean.substring(0, dot) : clean;
    }

    private static void writeInstalledPackManifest(
            @NonNull File gameDirectory,
            @NonNull String platform,
            @NonNull String name,
            @NonNull String minecraftVersion,
            @NonNull LoaderSpec loaderSpec,
            @Nullable JSONObject sourceManifest,
            @Nullable JSONObject extra
    ) {
        try {
            File metadataDirectory = getDroidBridgeMetadataDirectory(gameDirectory);
            JSONObject out = new JSONObject();
            out.put("type", "modpack");
            out.put("platform", platform);
            out.put("name", name);
            out.put("minecraftVersion", minecraftVersion);
            out.put("loader", loaderSpec.loaderName);
            out.put("loaderVersion", loaderSpec.loaderVersion);
            out.put("installedAt", System.currentTimeMillis());
            out.put("contentManifest", MODPACK_FILES_MANIFEST_FILE);
            if (sourceManifest != null) out.put("sourceManifest", sourceManifest);
            if (extra != null) out.put("extra", extra);
            try (FileOutputStream output = new FileOutputStream(new File(metadataDirectory, MODPACK_MANIFEST_FILE))) {
                output.write(out.toString(2).getBytes("UTF-8"));
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to write modpack manifest", throwable);
        }
    }

    @NonNull
    private static String resolveCurseForgeDownloadUrl(
            @NonNull String apiKey,
            int projectId,
            int fileId,
            @NonNull String fileName,
            @Nullable JSONObject fileMetadata
    ) {
        String direct = fileMetadata == null ? "" : cleanDownloadUrl(optJsonString(fileMetadata, "downloadUrl"));
        if (isHttpUrl(direct)) return direct;

        // The download-url endpoint frequently returns HTTP 403 for modpack dependencies even
        // though the public ForgeCDN file path is valid. In large CurseForge packs this added one
        // extra failing network request per protected file. Prefer the deterministic CDN path first
        // and let the hash/zip validation below reject anything that is not the real file.
        if (fileId > 0 && !isBlank(fileName)) {
            return buildCurseForgeCdnUrl(fileId, fileName);
        }

        if (projectId > 0 && fileId > 0) {
            try {
                String response = httpGetString(
                        "https://api.curseforge.com/v1/mods/" + projectId + "/files/" + fileId + "/download-url",
                        apiKey
                );
                String endpointUrl = parseCurseForgeDownloadUrlResponse(response);
                if (isHttpUrl(endpointUrl)) return endpointUrl;
            } catch (Throwable throwable) {
                Logging.i(TAG, "CurseForge download-url endpoint failed for "
                        + projectId + ":" + fileId + " - " + throwable.getMessage());
            }
        }

        return "";
    }

    @NonNull
    private static String parseCurseForgeDownloadUrlResponse(@Nullable String responseText) {
        String text = cleanDownloadUrl(responseText);
        if (isBlank(text)) return "";

        if (text.startsWith("{") && text.endsWith("}")) {
            try {
                JSONObject json = new JSONObject(text);
                String data = cleanDownloadUrl(optJsonString(json, "data"));
                if (isHttpUrl(data)) return data;
                String downloadUrl = cleanDownloadUrl(optJsonString(json, "downloadUrl"));
                if (isHttpUrl(downloadUrl)) return downloadUrl;
            } catch (Throwable ignored) {
            }
        }

        return text;
    }

    @NonNull
    private static String buildCurseForgeCdnUrl(int fileId, @NonNull String fileName) {
        int firstFolder = fileId / 1000;
        int secondFolder = fileId % 1000;
        return "https://edge.forgecdn.net/files/"
                + firstFolder
                + "/"
                + String.format(Locale.US, "%03d", secondFolder)
                + "/"
                + urlEncodePathSegment(fileName);
    }

    @NonNull
    private static String cleanDownloadUrl(@Nullable String rawValue) {
        if (rawValue == null) return "";
        String value = rawValue.trim();
        if (value.isEmpty()) return "";
        if ("null".equalsIgnoreCase(value) || "<null>".equalsIgnoreCase(value)) return "";
        if ("\"null\"".equalsIgnoreCase(value) || "'null'".equalsIgnoreCase(value)) return "";
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        if ("null".equalsIgnoreCase(value)) return "";
        return value;
    }

    private static boolean isHttpUrl(@Nullable String value) {
        String url = cleanDownloadUrl(value);
        return url.startsWith("http://") || url.startsWith("https://");
    }

    @NonNull
    private static String optJsonString(@Nullable JSONObject object, @NonNull String key) {
        if (object == null || !object.has(key) || object.isNull(key)) return "";
        Object value = object.opt(key);
        return value == null || value == JSONObject.NULL ? "" : String.valueOf(value).trim();
    }

    @NonNull
    private static String urlEncodePathSegment(@NonNull String value) {
        try {
            return URLEncoder.encode(value, "UTF-8")
                    .replace("+", "%20")
                    .replace("%2F", "/");
        } catch (Throwable ignored) {
            return value.replace(" ", "%20");
        }
    }

    @NonNull
    private static String downloadAndVerifyModrinthPackFile(
            @NonNull JSONObject fileMetadata,
            @NonNull File target,
            @NonNull String relativePath
    ) throws Exception {
        JSONArray downloads = fileMetadata.optJSONArray("downloads");
        if (downloads == null || downloads.length() == 0) {
            throw new IOException("Missing download URL for " + relativePath);
        }

        IOException lastFailure = null;
        for (int i = 0; i < downloads.length(); i++) {
            String downloadUrl = cleanDownloadUrl(downloads.optString(i, ""));
            if (isBlank(downloadUrl)) continue;

            try {
                downloadFile(downloadUrl, target, null);
                verifyModrinthPackFileOrRepairTrustedMismatch(target, fileMetadata, relativePath, downloadUrl);
                return downloadUrl;
            } catch (IOException throwable) {
                lastFailure = throwable;
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                Logging.i(TAG, "Modrinth file download attempt failed for "
                        + relativePath
                        + " url="
                        + downloadUrl
                        + " error="
                        + throwable.getMessage());
            }
        }

        if (lastFailure != null) throw lastFailure;
        throw new IOException("Missing or invalid download URL for " + relativePath);
    }

    private static void verifyModrinthPackFileOrRepairTrustedMismatch(
            @NonNull File file,
            @NonNull JSONObject fileMetadata,
            @NonNull String relativePath,
            @NonNull String downloadUrl
    ) throws Exception {
        JSONObject hashes = fileMetadata.optJSONObject("hashes");
        if (hashes == null) return;

        String expectedSha1 = hashes.optString("sha1", "").trim();
        if (isBlank(expectedSha1)) return;

        String actualSha1 = hashFile(file, "SHA-1");
        if (expectedSha1.equalsIgnoreCase(actualSha1)) return;

        if (isTrustedModrinthDownloadHash(downloadUrl, actualSha1)) {
            String actualSha512 = hashFile(file, "SHA-512");
            JSONObject repairedHashes = new JSONObject(hashes.toString());
            repairedHashes.put("sha1", actualSha1);
            repairedHashes.put("sha512", actualSha512);
            fileMetadata.put("hashes", repairedHashes);
            fileMetadata.put("fileSize", file.length());
            Logging.i(TAG, "Accepted trusted Modrinth CDN file despite stale exported hash for "
                    + relativePath
                    + " expected="
                    + expectedSha1
                    + " actual="
                    + actualSha1
                    + ". This repairs older DroidBridge exports that referenced a remote Modrinth URL after the local jar had been patched.");
            return;
        }

        throw new IOException("SHA-1 mismatch for "
                + file.getName()
                + " expected="
                + expectedSha1
                + " actual="
                + actualSha1);
    }

    private static void verifyHashesIfPresent(@NonNull File file, @Nullable JSONObject hashes) throws Exception {
        if (hashes == null) return;
        String expectedSha1 = hashes.optString("sha1", "").trim();
        if (!isBlank(expectedSha1)) {
            String actual = hashFile(file, "SHA-1");
            if (!expectedSha1.equalsIgnoreCase(actual)) {
                throw new IOException("SHA-1 mismatch for " + file.getName());
            }
        }
    }

    private static boolean isTrustedModrinthDownloadHash(
            @NonNull String downloadUrl,
            @NonNull String actualSha1
    ) {
        if (!isModrinthDownloadHost(downloadUrl) || isBlank(actualSha1)) return false;

        ParsedModrinthDownloadIds ids = parseModrinthDownloadIds(downloadUrl);
        if (isBlank(ids.versionId)) return false;

        try {
            JSONObject version = new JSONObject(httpGetString(
                    "https://api.modrinth.com/v2/version/" + urlEncodePathSegment(ids.versionId),
                    null
            ));
            JSONArray files = version.optJSONArray("files");
            if (files == null) return false;
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.optJSONObject(i);
                if (file == null) continue;
                JSONObject hashes = file.optJSONObject("hashes");
                String sha1 = hashes == null ? "" : hashes.optString("sha1", "").trim();
                if (actualSha1.equalsIgnoreCase(sha1)) return true;
            }
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to verify trusted Modrinth mismatch through version metadata: " + throwable.getMessage());
        }
        return false;
    }

    private static boolean isModrinthDownloadHost(@Nullable String rawUrl) {
        String url = cleanDownloadUrl(rawUrl).toLowerCase(Locale.US);
        return url.startsWith("https://cdn.modrinth.com/")
                || url.startsWith("http://cdn.modrinth.com/")
                || url.startsWith("https://launcher-files.modrinth.com/")
                || url.startsWith("http://launcher-files.modrinth.com/");
    }

    @NonNull
    private static String hashFile(@NonNull File file, @NonNull String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        byte[] bytes = digest.digest();
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) builder.append(String.format(Locale.US, "%02x", b & 0xff));
        return builder.toString();
    }

    private static void downloadFile(@NonNull String url, @NonNull File target, @Nullable Listener listener) throws Exception {
        String cleanUrl = cleanDownloadUrl(url);
        if (!isHttpUrl(cleanUrl)) {
            throw new IOException("Missing or invalid download URL for " + target.getName());
        }

        ensureParent(target);
        HttpURLConnection connection = openConnection(cleanUrl, null);
        int response = connection.getResponseCode();
        if (response / 100 != 2) throw new IOException("HTTP " + response + " while downloading " + cleanUrl);
        int contentLength = connection.getContentLength();
        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            int downloaded = 0;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                downloaded += read;
                if (listener != null && contentLength > 0) listener.onProgress(downloaded, contentLength);
            }
        } finally {
            connection.disconnect();
        }
    }

    @NonNull
    private static String httpGetString(@NonNull String url, @Nullable String curseForgeApiKey) throws Exception {
        HttpURLConnection connection = openConnection(url, curseForgeApiKey);
        int response = connection.getResponseCode();
        InputStream stream = response / 100 == 2 ? connection.getInputStream() : connection.getErrorStream();
        String text = stream == null ? "" : readToString(stream);
        connection.disconnect();
        if (response / 100 != 2) throw new IOException("HTTP " + response + ": " + text);
        return text;
    }

    @NonNull
    private static String httpPostString(
            @NonNull String url,
            @NonNull JSONObject body,
            @Nullable String curseForgeApiKey
    ) throws Exception {
        HttpURLConnection connection = openConnection(url, curseForgeApiKey);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        byte[] payload = body.toString().getBytes("UTF-8");
        connection.setFixedLengthStreamingMode(payload.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(payload);
        }
        int response = connection.getResponseCode();
        InputStream stream = response / 100 == 2 ? connection.getInputStream() : connection.getErrorStream();
        String text = stream == null ? "" : readToString(stream);
        connection.disconnect();
        if (response / 100 != 2) throw new IOException("HTTP " + response + ": " + text);
        return text;
    }

    @NonNull
    private static HttpURLConnection openConnection(@NonNull String url, @Nullable String curseForgeApiKey) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", "DroidBridge/Modpacks");
        if (!isBlank(curseForgeApiKey)) connection.setRequestProperty("x-api-key", curseForgeApiKey.trim());
        return connection;
    }

    @NonNull
    private static String readZipEntryText(@NonNull ZipFile zip, @NonNull String entryName) throws Exception {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null || entry.isDirectory()) throw new IOException("Missing " + entryName);
        try (InputStream input = zip.getInputStream(entry)) {
            return readToString(input);
        }
    }

    @NonNull
    private static String readToString(@NonNull InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copyStream(input, output);
        return output.toString("UTF-8");
    }

    private static void copyStream(@NonNull InputStream input, @NonNull OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
    }

    private static void ensureParent(@NonNull File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create folder: " + parent.getAbsolutePath());
        }
    }

    private static boolean isSafeRelativePath(@Nullable String rawPath) {
        if (rawPath == null) return false;
        String path = normalizeZipPath(rawPath).trim();
        if (path.isEmpty()) return false;
        if (path.startsWith("/") || path.startsWith("\\")) return false;
        if (path.matches("^[A-Za-z]:[/\\\\].*")) return false;
        String[] parts = path.split("/");
        for (String part : parts) {
            if (part.equals("..") || part.equals(".")) return false;
        }
        return true;
    }

    @NonNull
    private static String normalizeZipPath(@NonNull String path) {
        return path.replace('\\', '/');
    }

    @NonNull
    private static String normalizeZipPrefix(@NonNull String prefix) {
        String normalized = normalizeZipPath(prefix.trim());
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (!normalized.endsWith("/")) normalized += "/";
        return normalized;
    }


    @NonNull
    private static ArrayList<String> jsonArrayToStringList(@Nullable JSONArray array) {
        ArrayList<String> list = new ArrayList<>();
        if (array == null) return list;
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim();
            if (!isBlank(value)) list.add(value);
        }
        return list;
    }

    @NonNull
    private static ArrayList<String> extractCurseForgeLoaders(@NonNull ArrayList<String> gameVersions) {
        ArrayList<String> loaders = new ArrayList<>();
        for (String value : gameVersions) {
            String lower = value == null ? "" : value.trim().toLowerCase(Locale.US);
            String loader = "";
            if ("forge".equals(lower) || lower.startsWith("forge ")) loader = "forge";
            else if ("fabric".equals(lower) || lower.startsWith("fabric ")) loader = "fabric";
            else if ("neoforge".equals(lower) || lower.startsWith("neoforge ")) loader = "neoforge";
            else if ("quilt".equals(lower) || lower.startsWith("quilt ")) loader = "quilt";

            if (!isBlank(loader) && !containsIgnoreCase(loaders, loader)) loaders.add(loader);
        }
        return loaders;
    }

    private static boolean containsIgnoreCase(@NonNull ArrayList<String> values, @NonNull String target) {
        for (String value : values) {
            if (target.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    @NonNull
    private static String stripArchiveExtension(@NonNull String fileName) {
        String value = fileName.trim();
        String lower = value.toLowerCase(Locale.US);
        if (lower.endsWith(".mrpack")) return value.substring(0, value.length() - ".mrpack".length());
        if (lower.endsWith(".zip")) return value.substring(0, value.length() - ".zip".length());
        return value;
    }

    private static boolean jsonArrayContains(@Nullable JSONArray array, @NonNull String value) {
        if (array == null) return false;
        for (int i = 0; i < array.length(); i++) {
            if (value.equalsIgnoreCase(array.optString(i, ""))) return true;
        }
        return false;
    }

    @NonNull
    private static String normalizeLoader(@Nullable String loader) {
        String value = safe(loader).trim().toLowerCase(Locale.US);
        if (value.contains("cleanroom")) return "forge";
        if (value.equals("vanilla") || value.equals("minecraft")) return "";
        if (value.equals("fabric-loader")) return "fabric";
        if (value.equals("neoforge")) return "neoforge";
        return value;
    }

    private static int curseForgeLoaderType(@Nullable String loader) {
        String value = normalizeLoader(loader);
        if ("forge".equals(value)) return 1;
        if ("fabric".equals(value)) return 4;
        if ("quilt".equals(value)) return 5;
        if ("neoforge".equals(value)) return 6;
        return 0;
    }

    private static int parsePositiveInt(@NonNull String value, @NonNull String label) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed > 0) return parsed;
        } catch (Throwable ignored) {
        }
        throw new IllegalArgumentException("Invalid " + label + ": " + value);
    }

    @NonNull
    private static String sanitizeFileName(@NonNull String rawName) {
        String name = rawName.trim().replace('\n', ' ').replace('\r', ' ');
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        while (name.contains("  ")) name = name.replace("  ", " ");
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) name = "file";
        return name;
    }

    @NonNull
    private static String sanitizeFileName(@Nullable String rawName, @NonNull String fallback) {
        String name = isBlank(rawName) ? fallback : rawName.trim();
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) return fallback;
        return name;
    }

    @NonNull
    private static String urlEncode(@NonNull String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    @NonNull
    private static String firstNonBlank(@Nullable String first, @Nullable String fallback) {
        return isBlank(first) ? safe(fallback).trim() : first.trim();
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(@Nullable String value) {
        if (value == null) return true;
        String trimmed = value.trim();
        return trimmed.isEmpty()
                || "null".equalsIgnoreCase(trimmed)
                || "<null>".equalsIgnoreCase(trimmed)
                || "\"null\"".equalsIgnoreCase(trimmed)
                || "'null'".equalsIgnoreCase(trimmed);
    }


    public static final class ModpackVersionChoice {
        @NonNull
        public final ModManagerSource source;
        @NonNull
        public final String versionId;
        public final int fileId;
        @NonNull
        public final String versionName;
        @NonNull
        public final String versionNumber;
        @NonNull
        public final String fileName;
        @NonNull
        public final String downloadUrl;
        @NonNull
        public final ArrayList<String> gameVersions;
        @NonNull
        public final ArrayList<String> loaders;
        @NonNull
        public final String datePublished;

        ModpackVersionChoice(
                @NonNull ModManagerSource source,
                @NonNull String versionId,
                int fileId,
                @Nullable String versionName,
                @Nullable String versionNumber,
                @Nullable String fileName,
                @Nullable String downloadUrl,
                @NonNull ArrayList<String> gameVersions,
                @NonNull ArrayList<String> loaders,
                @Nullable String datePublished
        ) {
            this.source = source;
            this.versionId = safe(versionId).trim();
            this.fileId = fileId;
            this.versionName = safe(versionName).trim();
            this.versionNumber = safe(versionNumber).trim();
            this.fileName = safe(fileName).trim();
            this.downloadUrl = cleanDownloadUrl(downloadUrl);
            this.gameVersions = gameVersions;
            this.loaders = loaders;
            this.datePublished = safe(datePublished).trim();
        }

        @NonNull
        public String getDisplayTitle() {
            if (!isBlank(versionName)) return versionName;
            if (!isBlank(versionNumber)) return versionNumber;
            if (!isBlank(fileName)) return stripArchiveExtension(fileName);
            return source == ModManagerSource.CURSEFORGE ? "CurseForge file " + fileId : versionId;
        }

        @NonNull
        public String getDisplaySubtitle() {
            StringBuilder builder = new StringBuilder();
            if (!gameVersions.isEmpty()) {
                builder.append("Minecraft ").append(joinShortList(gameVersions, 4));
            }
            if (!loaders.isEmpty()) {
                if (builder.length() > 0) builder.append(" · ");
                builder.append("Loader ").append(joinShortList(loaders, 3));
            }
            if (!isBlank(datePublished)) {
                if (builder.length() > 0) builder.append(" · ");
                builder.append(datePublished.substring(0, Math.min(10, datePublished.length())));
            }
            if (!isBlank(fileName)) {
                if (builder.length() > 0) builder.append("\n");
                builder.append(fileName);
            }
            return builder.length() == 0 ? "No version metadata available" : builder.toString();
        }

        public boolean isCompatibleWith(@Nullable String minecraftVersion, @Nullable String loader) {
            String mc = safe(minecraftVersion).trim();
            String normalizedLoader = normalizeLoader(loader);
            boolean mcMatches = isBlank(mc) || gameVersions.isEmpty() || containsIgnoreCase(gameVersions, mc);
            boolean loaderMatches = isBlank(normalizedLoader) || loaders.isEmpty() || containsIgnoreCase(loaders, normalizedLoader);
            return mcMatches && loaderMatches;
        }

        @NonNull
        private static String joinShortList(@NonNull ArrayList<String> values, int limit) {
            StringBuilder builder = new StringBuilder();
            int count = Math.min(values.size(), Math.max(1, limit));
            for (int i = 0; i < count; i++) {
                if (builder.length() > 0) builder.append(", ");
                builder.append(values.get(i));
            }
            if (values.size() > count) builder.append(" +").append(values.size() - count);
            return builder.toString();
        }
    }

    @NonNull
    private static String normalizeLoaderVersionForInstaller(
            @NonNull String loaderName,
            @Nullable String loaderVersion,
            @NonNull String minecraftVersionId
    ) {
        String version = safe(loaderVersion).trim();
        if (isBlank(version)) return version;

        if ("Forge".equalsIgnoreCase(loaderName)) {
            return normalizeForgeLoaderVersion(version, minecraftVersionId);
        }

        if ("NeoForge".equalsIgnoreCase(loaderName)) {
            version = stripNeoForgePrefix(version);

            String minecraftPrefix = minecraftVersionId.trim() + "-";
            if (!isBlank(minecraftVersionId) && version.startsWith(minecraftPrefix)) {
                version = version.substring(minecraftPrefix.length()).trim();
            }

            java.util.regex.Matcher minecraftPrefixed = java.util.regex.Pattern
                    .compile("^1\\.\\d+(?:\\.\\d+)?-(.+)$")
                    .matcher(version);
            if (minecraftPrefixed.matches()) {
                version = minecraftPrefixed.group(1).trim();
            }
        }

        return version;
    }

    @NonNull
    private static String normalizeForgeLoaderVersion(
            @NonNull String rawVersion,
            @NonNull String minecraftVersionId
    ) {
        String version = rawVersion.trim();

        int lastColon = version.lastIndexOf(':');
        if (lastColon >= 0 && lastColon + 1 < version.length()) {
            version = version.substring(lastColon + 1).trim();
        }

        String lower = version.toLowerCase(Locale.US);
        if (lower.startsWith("forge-")) {
            version = version.substring("forge-".length()).trim();
        }

        String minecraftPrefix = minecraftVersionId.trim() + "-";
        if (!isBlank(minecraftVersionId) && version.startsWith(minecraftPrefix)) {
            version = version.substring(minecraftPrefix.length()).trim();
        }

        java.util.regex.Matcher minecraftPrefixed = java.util.regex.Pattern
                .compile("^1\\.\\d+(?:\\.\\d+)?-(.+)$")
                .matcher(version);
        if (minecraftPrefixed.matches()) {
            version = minecraftPrefixed.group(1).trim();
        }

        return version;
    }

    @NonNull
    private static String stripNeoForgePrefix(@NonNull String value) {
        String clean = value.trim();
        String lower = clean.toLowerCase(Locale.US);
        if (lower.startsWith("neoforge-")) {
            clean = clean.substring("neoforge-".length()).trim();
        }
        return clean;
    }

    private static final class PendingModrinthInstalledFile {
        @NonNull
        final File target;
        @NonNull
        final String relativePath;
        @NonNull
        final JSONObject fileMetadata;
        @NonNull
        final String downloadUrl;

        PendingModrinthInstalledFile(
                @NonNull File target,
                @NonNull String relativePath,
                @NonNull JSONObject fileMetadata,
                @NonNull String downloadUrl
        ) {
            this.target = target;
            this.relativePath = relativePath;
            this.fileMetadata = fileMetadata;
            this.downloadUrl = downloadUrl;
        }
    }

    private static final class ParsedModrinthDownloadIds {
        @NonNull
        final String projectId;
        @NonNull
        final String versionId;

        ParsedModrinthDownloadIds(@NonNull String projectId, @NonNull String versionId) {
            this.projectId = projectId;
            this.versionId = versionId;
        }
    }

    private static final class LoaderSpec {
        @NonNull
        final String loaderName;
        @Nullable
        final String loaderVersion;

        LoaderSpec(@NonNull String loaderName, @Nullable String loaderVersion) {
            this.loaderName = loaderName;
            this.loaderVersion = loaderVersion;
        }

        @NonNull
        static LoaderSpec fromModrinthDependencies(@NonNull JSONObject dependencies) {
            if (!isBlank(dependencies.optString("fabric-loader", ""))) {
                return new LoaderSpec("Fabric", dependencies.optString("fabric-loader", ""));
            }
            if (!isBlank(dependencies.optString("forge", ""))) {
                return new LoaderSpec("Forge", dependencies.optString("forge", ""));
            }
            if (!isBlank(dependencies.optString("neoforge", ""))) {
                return new LoaderSpec("NeoForge", stripNeoForgePrefix(dependencies.optString("neoforge", "")));
            }
            if (!isBlank(dependencies.optString("quilt-loader", ""))) {
                return new LoaderSpec("Quilt", dependencies.optString("quilt-loader", ""));
            }
            return new LoaderSpec("Vanilla", null);
        }


        @NonNull
        static LoaderSpec fromMultiMcPackJson(@NonNull JSONObject pack) {
            JSONArray components = pack.optJSONArray("components");
            if (components == null) return new LoaderSpec("Vanilla", null);

            for (int i = 0; i < components.length(); i++) {
                JSONObject component = components.optJSONObject(i);
                if (component == null) continue;
                String uid = component.optString("uid", "").trim().toLowerCase(Locale.US);
                String version = component.optString("version", "").trim();
                if ("net.fabricmc.fabric-loader".equals(uid)) return new LoaderSpec("Fabric", version);
                if ("net.minecraftforge".equals(uid)) return new LoaderSpec("Forge", version);
                if ("net.neoforged".equals(uid) || "net.neoforged.neoforge".equals(uid)) return new LoaderSpec("NeoForge", stripNeoForgePrefix(version));
                if ("org.quiltmc.quilt-loader".equals(uid)) return new LoaderSpec("Quilt", version);
            }

            return new LoaderSpec("Vanilla", null);
        }

        @NonNull
        static LoaderSpec fromCurseForgeManifest(@Nullable JSONArray modLoaders) {
            if (modLoaders == null) return new LoaderSpec("Vanilla", null);
            JSONObject first = null;
            for (int i = 0; i < modLoaders.length(); i++) {
                JSONObject loader = modLoaders.optJSONObject(i);
                if (loader == null) continue;
                if (loader.optBoolean("primary", false)) {
                    first = loader;
                    break;
                }
                if (first == null) first = loader;
            }
            if (first == null) return new LoaderSpec("Vanilla", null);
            String id = first.optString("id", "").trim();
            if (id.toLowerCase(Locale.US).startsWith("fabric-")) return new LoaderSpec("Fabric", id.substring("fabric-".length()));
            if (id.toLowerCase(Locale.US).startsWith("forge-")) return new LoaderSpec("Forge", id.substring("forge-".length()));
            if (id.toLowerCase(Locale.US).startsWith("neoforge-")) return new LoaderSpec("NeoForge", stripNeoForgePrefix(id));
            if (id.toLowerCase(Locale.US).startsWith("quilt-")) return new LoaderSpec("Quilt", id.substring("quilt-".length()));
            return new LoaderSpec("Vanilla", null);
        }
    }
}

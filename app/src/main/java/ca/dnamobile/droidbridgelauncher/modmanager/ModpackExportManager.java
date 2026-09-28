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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

/**
 * Exports an existing DroidBridge instance into Modrinth, CurseForge, or portable desktop-launcher style packs.
 *
 * Important: this exporter creates a valid importable archive for private sharing.
 * Publishing or sharing still requires all referenced mods/resources to
 * be allowed by that platform. Unknown/manual files are placed in overrides and are
 * listed in EXPORT_WARNINGS.txt so pack authors can fix them before upload/share.
 */
public final class ModpackExportManager {
    private static final String TAG = "ModpackExport";
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String DROIDBRIDGE_METADATA_DIRECTORY = DroidBridgeMetadataPaths.PRIMARY_DIRECTORY;
    private static final String MODPACK_MANIFEST_FILE = "modpack_manifest.json";
    private static final String MODPACK_FILES_MANIFEST_FILE = "modpack_files_manifest.json";
    private static final String JAVALAUNCHER_PACK_ICON_ENTRY = "droidbridge-pack-icon.png";
    private static final String VULKAN_SHIELD_FALLBACK_PACK_FOLDER = "DroidBridge-Vulkan-Shield-Fallback";
    private static final String VULKAN_SHIELD_FALLBACK_PACK_ID = "file/" + VULKAN_SHIELD_FALLBACK_PACK_FOLDER;
    private static final String[] ROOT_SHADER_CONFIGURATION_FILES = {"shaderpack.txt", "optionsshaders.txt"};
    /**
     * Modpack behaviour/configuration folders are part of the instance, not optional
     * cosmetic content. Always keep them with an export so edited mod settings are not
     * silently lost. Worlds remain opt-in separately below.
     */
    private static final String[] REQUIRED_CONFIGURATION_FOLDERS = {
            "config", "defaultconfigs", "kubejs", "scripts"
    };
    /** Additional well-known folders used by mods/loaders to ship pack configuration. */
    private static final String[] SUPPLEMENTAL_CONFIGURATION_FOLDERS = {
            "openloader", "global_packs", "paxi", "patchouli_books", "resources"
    };
    private static final String[] ROOT_CONFIGURATION_EXTENSIONS = {
            ".cfg", ".conf", ".config", ".json", ".json5", ".toml", ".properties", ".yaml", ".yml"
    };

    public enum Platform {
        MODRINTH,
        CURSEFORGE,
        MULTIMC
    }

    public interface Listener {
        void onStatus(@NonNull String message);
        void onProgress(int current, int total);
        void onComplete(@NonNull String message);
        void onError(@NonNull Throwable throwable);
    }

    public static final class ExportOptions {
        public final boolean includeInstanceIcon;
        public final boolean includeMods;
        public final boolean includeResourcePacks;
        public final boolean includeShaderPacks;
        public final boolean includeConfig;
        public final boolean includeDefaultConfigs;
        public final boolean includeKubeJs;
        public final boolean includeScripts;
        public final boolean includeOptionsTxt;
        public final boolean includeSaves;

        public ExportOptions(
                boolean includeInstanceIcon,
                boolean includeMods,
                boolean includeResourcePacks,
                boolean includeShaderPacks,
                boolean includeConfig,
                boolean includeDefaultConfigs,
                boolean includeKubeJs,
                boolean includeScripts,
                boolean includeOptionsTxt
        ) {
            this(
                    includeInstanceIcon,
                    includeMods,
                    includeResourcePacks,
                    includeShaderPacks,
                    includeConfig,
                    includeDefaultConfigs,
                    includeKubeJs,
                    includeScripts,
                    includeOptionsTxt,
                    false
            );
        }

        public ExportOptions(
                boolean includeInstanceIcon,
                boolean includeMods,
                boolean includeResourcePacks,
                boolean includeShaderPacks,
                boolean includeConfig,
                boolean includeDefaultConfigs,
                boolean includeKubeJs,
                boolean includeScripts,
                boolean includeOptionsTxt,
                boolean includeSaves
        ) {
            this.includeInstanceIcon = includeInstanceIcon;
            this.includeMods = includeMods;
            this.includeResourcePacks = includeResourcePacks;
            this.includeShaderPacks = includeShaderPacks;
            this.includeConfig = includeConfig;
            this.includeDefaultConfigs = includeDefaultConfigs;
            this.includeKubeJs = includeKubeJs;
            this.includeScripts = includeScripts;
            this.includeOptionsTxt = includeOptionsTxt;
            this.includeSaves = includeSaves;
        }

        @NonNull
        public static ExportOptions defaultOptions() {
            return new ExportOptions(true, true, true, true, true, true, true, true, true, false);
        }

        @NonNull
        static ExportOptions clean(@Nullable ExportOptions options) {
            return options == null ? defaultOptions() : options;
        }
    }

    private ModpackExportManager() {
    }

    @NonNull
    private static String normalizeLoaderForExport(@Nullable String loader) {
        if (loader == null) return "Vanilla";
        String clean = loader.trim();
        return clean.toLowerCase(Locale.US).contains("cleanroom") ? "Forge" : clean;
    }

    public static void exportToUri(
            @NonNull Context context,
            @NonNull File gameDirectory,
            @NonNull String instanceName,
            @NonNull String minecraftVersion,
            @NonNull String loader,
            @Nullable String baseVersionId,
            @NonNull Platform platform,
            @NonNull Uri outputUri,
            @NonNull Listener listener
    ) {
        exportToUri(context, gameDirectory, instanceName, minecraftVersion, loader, baseVersionId, null, platform, outputUri, listener);
    }

    public static void exportToUri(
            @NonNull Context context,
            @NonNull File gameDirectory,
            @NonNull String instanceName,
            @NonNull String minecraftVersion,
            @NonNull String loader,
            @Nullable String baseVersionId,
            @Nullable File iconFile,
            @NonNull Platform platform,
            @NonNull Uri outputUri,
            @NonNull Listener listener
    ) {
        exportToUri(context, gameDirectory, instanceName, minecraftVersion, loader, baseVersionId, iconFile, platform, outputUri, ExportOptions.defaultOptions(), listener);
    }

    public static void exportToUri(
            @NonNull Context context,
            @NonNull File gameDirectory,
            @NonNull String instanceName,
            @NonNull String minecraftVersion,
            @NonNull String loader,
            @Nullable String baseVersionId,
            @Nullable File iconFile,
            @NonNull Platform platform,
            @NonNull Uri outputUri,
            @Nullable ExportOptions exportOptions,
            @NonNull Listener listener
    ) {
        ExportOptions options = ExportOptions.clean(exportOptions);
        // Cleanroom is Forge-compatible, but public modpack formats do not have a
        // Cleanroom loader key. Export migrated instances with their original Forge
        // dependency metadata so the pack remains installable elsewhere.
        String exportLoader = normalizeLoaderForExport(loader);
        File temp = null;
        try {
            if (!gameDirectory.isDirectory()) throw new IOException("Missing instance game directory: " + gameDirectory.getAbsolutePath());
            listener.onStatus("Building modpack export...");
            temp = File.createTempFile("javalauncher-modpack-export-", platform == Platform.MODRINTH ? ".mrpack" : ".zip", context.getCacheDir());
            if (platform == Platform.MODRINTH) {
                exportModrinth(temp, gameDirectory, instanceName, minecraftVersion, exportLoader, baseVersionId, iconFile, options, listener);
            } else if (platform == Platform.CURSEFORGE) {
                exportCurseForge(temp, gameDirectory, instanceName, minecraftVersion, exportLoader, baseVersionId, iconFile, options, listener);
            } else {
                exportMultiMc(temp, gameDirectory, instanceName, minecraftVersion, exportLoader, baseVersionId, iconFile, options, listener);
            }

            listener.onStatus("Saving export...");
            try (InputStream input = new FileInputStream(temp);
                 OutputStream output = context.getContentResolver().openOutputStream(outputUri)) {
                if (output == null) throw new IOException("Unable to open export destination.");
                copyStream(input, output);
            }
            listener.onComplete("Exported modpack: " + instanceName);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to export modpack", throwable);
            listener.onError(throwable);
        } finally {
            if (temp != null) //noinspection ResultOfMethodCallIgnored
                temp.delete();
        }
    }

    private static void exportModrinth(
            @NonNull File outputFile,
            @NonNull File gameDirectory,
            @NonNull String instanceName,
            @NonNull String minecraftVersion,
            @NonNull String loader,
            @Nullable String baseVersionId,
            @Nullable File iconFile,
            @NonNull ExportOptions options,
            @NonNull Listener listener
    ) throws Exception {
        ArrayList<FileRecord> records = collectContentRecords(gameDirectory, minecraftVersion, options);
        ArrayList<String> warnings = new ArrayList<>();

        JSONObject index = new JSONObject();
        index.put("formatVersion", 1);
        index.put("game", "minecraft");
        index.put("versionId", "1.0.0");
        index.put("name", instanceName);
        index.put("summary", "Exported from DroidBridge");

        JSONObject dependencies = new JSONObject();
        dependencies.put("minecraft", minecraftVersion);
        addModrinthLoaderDependency(dependencies, gameDirectory, minecraftVersion, loader, baseVersionId);
        index.put("dependencies", dependencies);

        JSONArray files = new JSONArray();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(outputFile))) {
            if (options.includeInstanceIcon) addInstanceIconToZip(zip, iconFile);
            int current = 0;
            for (FileRecord record : records) {
                current++;
                listener.onStatus("Adding " + record.relativePath + "...");
                listener.onProgress(current, Math.max(1, records.size()));

                String currentSha1 = sha1(record.file);
                String currentSha512 = sha512(record.file);
                if (record.canUseModrinthDownload(currentSha1)) {
                    JSONObject entry = new JSONObject();
                    entry.put("path", record.relativePath);
                    JSONObject hashes = new JSONObject();
                    hashes.put("sha1", currentSha1);
                    hashes.put("sha512", currentSha512);
                    entry.put("hashes", hashes);
                    JSONArray downloads = new JSONArray();
                    downloads.put(record.downloadUrl);
                    entry.put("downloads", downloads);
                    entry.put("fileSize", record.file.length());
                    JSONObject env = new JSONObject();
                    env.put("client", "required");
                    env.put("server", "optional");
                    entry.put("env", env);
                    files.put(entry);
                } else {
                    addFileToZip(zip, record.file, "overrides/" + record.relativePath);
                    if (record.source == ModManagerSource.MODRINTH && !isBlank(record.downloadUrl)) {
                        warnings.add(record.relativePath + " was exported as an override because the local file no longer matches the tracked Modrinth download hash. This prevents SHA-1 mismatch failures when the pack is imported.");
                    } else {
                        warnings.add(record.relativePath + " was exported as an override because it is not tracked as a Modrinth file with a download URL.");
                    }
                }
            }

            addCommonOverrides(zip, gameDirectory, warnings, options);
            index.put("files", files);
            addTextEntry(zip, "modrinth.index.json", index.toString(2));
            addWarnings(zip, warnings, Platform.MODRINTH);
        }
    }

    private static void exportCurseForge(
            @NonNull File outputFile,
            @NonNull File gameDirectory,
            @NonNull String instanceName,
            @NonNull String minecraftVersion,
            @NonNull String loader,
            @Nullable String baseVersionId,
            @Nullable File iconFile,
            @NonNull ExportOptions options,
            @NonNull Listener listener
    ) throws Exception {
        ArrayList<FileRecord> records = collectContentRecords(gameDirectory, minecraftVersion, options);
        ArrayList<String> warnings = new ArrayList<>();
        Set<String> addedProjectIds = new HashSet<>();

        JSONObject manifest = new JSONObject();
        manifest.put("minecraft", buildCurseForgeMinecraftBlock(gameDirectory, minecraftVersion, loader, baseVersionId));
        manifest.put("manifestType", "minecraftModpack");
        manifest.put("manifestVersion", 1);
        manifest.put("name", instanceName);
        manifest.put("version", "1.0.0");
        manifest.put("author", "DroidBridge");
        manifest.put("overrides", "overrides");

        JSONArray files = new JSONArray();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(outputFile))) {
            if (options.includeInstanceIcon) addInstanceIconToZip(zip, iconFile);
            int current = 0;
            for (FileRecord record : records) {
                current++;
                listener.onStatus("Adding " + record.relativePath + "...");
                listener.onProgress(current, Math.max(1, records.size()));

                if (record.source == ModManagerSource.CURSEFORGE && record.projectId > 0 && record.fileId > 0) {
                    if (addedProjectIds.add(String.valueOf(record.projectId))) {
                        JSONObject entry = new JSONObject();
                        entry.put("projectID", record.projectId);
                        entry.put("fileID", record.fileId);
                        entry.put("required", true);
                        files.put(entry);
                    } else {
                        warnings.add(record.relativePath + " was skipped from CurseForge manifest because another file from the same project was already listed.");
                    }
                } else {
                    addFileToZip(zip, record.file, "overrides/" + record.relativePath);
                    warnings.add(record.relativePath + " was exported as an override because it is not tracked as a CurseForge project/file id.");
                }
            }

            addCommonOverrides(zip, gameDirectory, warnings, options);
            manifest.put("files", files);
            addTextEntry(zip, "manifest.json", manifest.toString(2));
            addTextEntry(zip, "modlist.html", buildModListHtml(records));
            addWarnings(zip, warnings, Platform.CURSEFORGE);
        }
    }


    private static void exportMultiMc(
            @NonNull File outputFile,
            @NonNull File gameDirectory,
            @NonNull String instanceName,
            @NonNull String minecraftVersion,
            @NonNull String loader,
            @Nullable String baseVersionId,
            @Nullable File iconFile,
            @NonNull ExportOptions options,
            @NonNull Listener listener
    ) throws Exception {
        ArrayList<String> warnings = new ArrayList<>();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(outputFile))) {
            if (options.includeInstanceIcon) addInstanceIconToZip(zip, iconFile);
            listener.onStatus("Writing MultiMC metadata...");
            addTextEntry(zip, "instance.cfg", buildMultiMcInstanceCfg(instanceName));
            addTextEntry(zip, "mmc-pack.json", buildMultiMcPackJson(gameDirectory, minecraftVersion, loader, baseVersionId).toString(2));

            ArrayList<File> exportRoots = collectMultiMcExportRoots(gameDirectory, options);
            int current = 0;
            for (File source : exportRoots) {
                current++;
                listener.onStatus("Adding .minecraft/" + source.getName() + "...");
                listener.onProgress(current, Math.max(1, exportRoots.size()));
                addFileOrDirectoryToZip(zip, source, ".minecraft/" + source.getName());
            }

            addRootConfigurationFiles(zip, gameDirectory, ".minecraft/");
            addRootShaderConfigurationFiles(zip, gameDirectory, ".minecraft/", options.includeShaderPacks);

            File optionsFile = new File(gameDirectory, "options.txt");
            if (options.includeOptionsTxt && optionsFile.isFile()) {
                addOptionsFileToZip(zip, optionsFile, ".minecraft/options.txt");
                warnings.add("options.txt was included for private sharing. Remove it before sharing if it contains personal settings you do not want to ship.");
            }
            File optifineOptions = new File(gameDirectory, "optionsof.txt");
            if (options.includeOptionsTxt && optifineOptions.isFile()) {
                addFileToZip(zip, optifineOptions, ".minecraft/optionsof.txt");
            }
            if (options.includeSaves && new File(gameDirectory, "saves").isDirectory()) {
                warnings.add("saves folder was included for private sharing. Remove it before sharing if you do not want to ship worlds.");
            }

            warnings.add("MultiMC/Prism exports bundle files directly. Before sharing, make sure every bundled mod/resourcepack/shader/config is allowed to be redistributed.");
            addTextEntry(zip, "README_JAVALAUNCHER_MULTIMC.txt", buildMultiMcReadme(instanceName));
            addWarnings(zip, warnings, Platform.MULTIMC);
        }
    }

    private static void addInstanceIconToZip(@NonNull ZipOutputStream zip, @Nullable File iconFile) throws Exception {
        if (iconFile == null || !iconFile.isFile() || iconFile.length() <= 0) return;
        addFileToZip(zip, iconFile, JAVALAUNCHER_PACK_ICON_ENTRY);
    }

    @NonNull
    private static ArrayList<FileRecord> collectContentRecords(
            @NonNull File gameDirectory,
            @Nullable String minecraftVersion,
            @NonNull ExportOptions options
    ) {
        ArrayList<FileRecord> out = new ArrayList<>();
        if (options.includeMods) collectFolderRecords(gameDirectory, ModManagerContentType.MODS, "mods", out, false);
        if (options.includeResourcePacks) {
            // 1.5.2 and older use texturepacks. Keep the real on-disk folder name in
            // the export; also preserve a second pack folder if a migrated instance
            // happens to contain both.
            String preferred = ModManagerContentType.getResourcePackFolderName(minecraftVersion);
            collectFolderRecords(gameDirectory, ModManagerContentType.RESOURCEPACKS, preferred, out,
                    "texturepacks".equals(preferred));
            String alternate = "texturepacks".equals(preferred) ? "resourcepacks" : "texturepacks";
            if (new File(gameDirectory, alternate).isDirectory()) {
                collectFolderRecords(gameDirectory, ModManagerContentType.RESOURCEPACKS, alternate, out,
                        "texturepacks".equals(alternate));
            }
        }
        if (options.includeShaderPacks) collectFolderRecords(gameDirectory, ModManagerContentType.SHADERPACKS, "shaderpacks", out, false);
        return out;
    }

    private static void collectFolderRecords(
            @NonNull File gameDirectory,
            @NonNull ModManagerContentType type,
            @NonNull String folder,
            @NonNull ArrayList<FileRecord> out,
            boolean forceOverride
    ) {
        File directory = new File(gameDirectory, folder);
        File[] files = directory.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isHidden() || !file.isFile()) continue;
            String name = file.getName().toLowerCase(Locale.US);
            boolean packagedContent = name.endsWith(".jar") || name.endsWith(".zip");
            boolean shaderTextCompanion = type == ModManagerContentType.SHADERPACKS && name.endsWith(".txt");
            if (!packagedContent && !shaderTextCompanion) continue;

            String relativePath = folder + "/" + file.getName();
            JSONObject entry = null;
            if (!forceOverride) {
                try {
                    entry = ModManagerManifest.getInstalledEntryForFile(gameDirectory, type, file);
                } catch (Throwable ignored) {
                }
                if (entry == null) {
                    entry = findDroidBridgeInstalledContentEntry(gameDirectory, file, relativePath);
                }
            }
            out.add(FileRecord.fromEntry(file, relativePath, entry));
        }
    }


    @Nullable
    private static JSONObject findDroidBridgeInstalledContentEntry(
            @NonNull File gameDirectory,
            @NonNull File file,
            @NonNull String relativePath
    ) {
        File metadataFile = new File(new File(gameDirectory, DROIDBRIDGE_METADATA_DIRECTORY), MODPACK_FILES_MANIFEST_FILE);
        if (!metadataFile.isFile()) return null;

        String wantedCanonical = safeCanonicalPath(file);
        String wantedAbsolute = file.getAbsolutePath();
        String wantedRelative = relativePath.replace('\\', '/');
        String wantedFileName = file.getName();

        try {
            JSONObject manifest = new JSONObject(readTextFile(metadataFile));
            JSONArray files = manifest.optJSONArray("files");
            if (files == null) return null;

            JSONObject fileNameMatch = null;
            for (int i = 0; i < files.length(); i++) {
                JSONObject entry = files.optJSONObject(i);
                if (entry == null) continue;

                String entryCanonical = entry.optString("canonicalPath", "").trim();
                String entryAbsolute = entry.optString("absolutePath", "").trim();
                String entryRelative = optStringAny(entry, "relativePath", "filePath", "path").replace('\\', '/');
                String entryFileName = entry.optString("fileName", "").trim();

                if (!isBlank(entryCanonical) && wantedCanonical.equals(entryCanonical)) return entry;
                if (!isBlank(entryAbsolute) && wantedAbsolute.equals(entryAbsolute)) return entry;
                if (!isBlank(entryRelative) && wantedRelative.equalsIgnoreCase(entryRelative)) return entry;

                if (!isBlank(entryFileName) && wantedFileName.equalsIgnoreCase(entryFileName)) {
                    if (fileNameMatch == null) {
                        fileNameMatch = entry;
                    } else {
                        // Filename is ambiguous; avoid attaching the wrong platform metadata.
                        fileNameMatch = null;
                    }
                }
            }
            return fileNameMatch;
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to read DroidBridge modpack file metadata for export: " + throwable.getMessage());
            return null;
        }
    }

    @NonNull
    private static String safeCanonicalPath(@NonNull File file) {
        try {
            return file.getCanonicalPath();
        } catch (Throwable ignored) {
            return file.getAbsolutePath();
        }
    }

    private static void addCommonOverrides(
            @NonNull ZipOutputStream zip,
            @NonNull File gameDirectory,
            @NonNull ArrayList<String> warnings,
            @NonNull ExportOptions options
    ) throws Exception {
        // Mod configuration is part of the pack. Do not let a config checkbox or an
        // older caller accidentally strip edited settings from an exported instance.
        addRequiredConfigurationFolders(zip, gameDirectory, "overrides/");
        addRootConfigurationFiles(zip, gameDirectory, "overrides/");

        addOverrideFolderIfSelected(zip, gameDirectory, "saves", options.includeSaves);
        if (options.includeSaves && new File(gameDirectory, "saves").isDirectory()) {
            warnings.add("saves folder was included for private sharing. Remove it before publishing unless you intentionally want to ship worlds.");
        }

        addRootShaderConfigurationFiles(zip, gameDirectory, "overrides/", options.includeShaderPacks);

        File optionsFile = new File(gameDirectory, "options.txt");
        if (options.includeOptionsTxt && optionsFile.isFile()) {
            addOptionsFileToZip(zip, optionsFile, "overrides/options.txt");
            warnings.add("options.txt was included for private sharing. Remove it before publishing if it contains personal settings you do not want to ship.");
        }
        File optifineOptions = new File(gameDirectory, "optionsof.txt");
        if (options.includeOptionsTxt && optifineOptions.isFile()) {
            addFileToZip(zip, optifineOptions, "overrides/optionsof.txt");
        }
    }

    private static void addRequiredConfigurationFolders(
            @NonNull ZipOutputStream zip,
            @NonNull File gameDirectory,
            @NonNull String zipPrefix
    ) throws Exception {
        for (String folder : REQUIRED_CONFIGURATION_FOLDERS) {
            File source = new File(gameDirectory, folder);
            if (source.exists()) addFileOrDirectoryToZip(zip, source, zipPrefix + folder);
        }
        for (String folder : SUPPLEMENTAL_CONFIGURATION_FOLDERS) {
            File source = new File(gameDirectory, folder);
            if (source.exists()) addFileOrDirectoryToZip(zip, source, zipPrefix + folder);
        }
    }

    /**
     * Some older mods/loaders put configuration directly in the game root instead
     * of config/. Preserve those files, but intentionally avoid broad .txt/.dat files
     * that can contain player/server history or unrelated launcher state.
     */
    private static void addRootConfigurationFiles(
            @NonNull ZipOutputStream zip,
            @NonNull File gameDirectory,
            @NonNull String zipPrefix
    ) throws Exception {
        File[] files = gameDirectory.listFiles();
        if (files == null) return;
        for (File source : files) {
            if (!source.isFile() || source.isHidden()) continue;
            String lower = source.getName().toLowerCase(Locale.US);
            if (!hasAnySuffix(lower, ROOT_CONFIGURATION_EXTENSIONS)) continue;
            if ("launcher_profiles.json".equals(lower)
                    || "launcher_accounts.json".equals(lower)
                    || "usercache.json".equals(lower)) {
                continue;
            }
            addFileToZip(zip, source, zipPrefix + source.getName());
        }
    }

    private static boolean hasAnySuffix(@NonNull String value, @NonNull String[] suffixes) {
        for (String suffix : suffixes) {
            if (value.endsWith(suffix)) return true;
        }
        return false;
    }

    private static void addRootShaderConfigurationFiles(
            @NonNull ZipOutputStream zip,
            @NonNull File gameDirectory,
            @NonNull String zipPrefix,
            boolean includeShaderPacks
    ) throws Exception {
        if (!includeShaderPacks) return;
        for (String fileName : ROOT_SHADER_CONFIGURATION_FILES) {
            File source = new File(gameDirectory, fileName);
            if (source.isFile()) addFileToZip(zip, source, zipPrefix + source.getName());
        }
    }

    private static void addOverrideFolderIfSelected(
            @NonNull ZipOutputStream zip,
            @NonNull File gameDirectory,
            @NonNull String folder,
            boolean selected
    ) throws Exception {
        if (!selected) return;
        File source = new File(gameDirectory, folder);
        if (source.exists()) addFileOrDirectoryToZip(zip, source, "overrides/" + folder);
    }

    private static void addModrinthLoaderDependency(
            @NonNull JSONObject dependencies,
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @NonNull String loader,
            @Nullable String baseVersionId
    ) throws Exception {
        String loaderVersion = resolveLoaderVersion(gameDirectory, minecraftVersion, loader, baseVersionId);
        if (isBlank(loaderVersion)) {
            if (!"Vanilla".equalsIgnoreCase(loader)) {
                throw new IllegalStateException("Unable to resolve " + loader + " loader version for export. Open this instance once after installing the modpack, or reinstall the pack so DroidBridge can write .javalauncher/modpack_manifest.json.");
            }
            return;
        }
        if ("Fabric".equalsIgnoreCase(loader)) dependencies.put("fabric-loader", loaderVersion);
        else if ("Forge".equalsIgnoreCase(loader)) dependencies.put("forge", loaderVersion);
        else if ("NeoForge".equalsIgnoreCase(loader)) dependencies.put("neoforge", loaderVersion);
        else if ("Quilt".equalsIgnoreCase(loader)) dependencies.put("quilt-loader", loaderVersion);
    }

    @NonNull
    private static String buildMultiMcInstanceCfg(@NonNull String instanceName) {
        return "InstanceType=OneSix\n"
                + "name=" + sanitizeMultiMcCfgValue(instanceName) + "\n"
                + "iconKey=default\n"
                + "notes=Exported from DroidBridge.\n";
    }

    @NonNull
    private static JSONObject buildMultiMcPackJson(
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @NonNull String loader,
            @Nullable String baseVersionId
    ) throws Exception {
        JSONObject pack = new JSONObject();
        pack.put("formatVersion", 1);
        JSONArray components = new JSONArray();

        JSONObject minecraft = new JSONObject();
        minecraft.put("cachedName", "Minecraft");
        minecraft.put("uid", "net.minecraft");
        minecraft.put("version", minecraftVersion);
        minecraft.put("important", true);
        components.put(minecraft);

        String loaderVersion = resolveLoaderVersion(gameDirectory, minecraftVersion, loader, baseVersionId);
        String loaderUid = buildMultiMcLoaderUid(loader);
        if (!isBlank(loaderUid) && isBlank(loaderVersion)) {
            throw new IllegalStateException("Unable to resolve " + loader + " loader version for MultiMC/Prism export. Reinstall the pack or repair its DroidBridge metadata first.");
        }
        if (!isBlank(loaderUid) && !isBlank(loaderVersion)) {
            JSONObject loaderComponent = new JSONObject();
            loaderComponent.put("uid", loaderUid);
            loaderComponent.put("version", loaderVersion);
            loaderComponent.put("important", true);
            components.put(loaderComponent);
        }

        pack.put("components", components);
        return pack;
    }

    @NonNull
    private static String buildMultiMcLoaderUid(@Nullable String loader) {
        if (isBlank(loader)) return "";
        if ("Fabric".equalsIgnoreCase(loader)) return "net.fabricmc.fabric-loader";
        if ("Forge".equalsIgnoreCase(loader)) return "net.minecraftforge";
        if ("NeoForge".equalsIgnoreCase(loader)) return "net.neoforged";
        if ("Quilt".equalsIgnoreCase(loader)) return "org.quiltmc.quilt-loader";
        return "";
    }

    @NonNull
    private static String sanitizeMultiMcCfgValue(@NonNull String value) {
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    @NonNull
    private static ArrayList<File> collectMultiMcExportRoots(@NonNull File gameDirectory, @NonNull ExportOptions options) {
        ArrayList<File> out = new ArrayList<>();
        addExportRootIfSelected(out, gameDirectory, "mods", options.includeMods);
        addExportRootIfSelected(out, gameDirectory, "resourcepacks", options.includeResourcePacks);
        addExportRootIfSelected(out, gameDirectory, "texturepacks", options.includeResourcePacks);
        addExportRootIfSelected(out, gameDirectory, "shaderpacks", options.includeShaderPacks);
        for (String folder : REQUIRED_CONFIGURATION_FOLDERS) addExportRootIfSelected(out, gameDirectory, folder, true);
        for (String folder : SUPPLEMENTAL_CONFIGURATION_FOLDERS) addExportRootIfSelected(out, gameDirectory, folder, true);
        addExportRootIfSelected(out, gameDirectory, "saves", options.includeSaves);
        return out;
    }

    private static void addExportRootIfSelected(
            @NonNull ArrayList<File> out,
            @NonNull File gameDirectory,
            @NonNull String folder,
            boolean selected
    ) {
        if (!selected) return;
        File source = new File(gameDirectory, folder);
        if (source.exists()) out.add(source);
    }

    @NonNull
    private static String buildMultiMcReadme(@NonNull String instanceName) {
        return "DroidBridge MultiMC/Prism export\n\n"
                + "Instance: " + instanceName + "\n\n"
                + "This ZIP contains instance.cfg, mmc-pack.json, and .minecraft content. "
                + "It is intended for MultiMC/Prism-style import from zip and private sharing. "
                + "Before public sharing, verify redistribution permissions for every included mod, resource pack, shader pack, and config file.\n";
    }

    @NonNull
    private static JSONObject buildCurseForgeMinecraftBlock(
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @NonNull String loader,
            @Nullable String baseVersionId
    ) throws Exception {
        JSONObject minecraft = new JSONObject();
        minecraft.put("version", minecraftVersion);
        JSONArray modLoaders = new JSONArray();
        String loaderVersion = resolveLoaderVersion(gameDirectory, minecraftVersion, loader, baseVersionId);
        if (!"Vanilla".equalsIgnoreCase(loader) && isBlank(loaderVersion)) {
            throw new IllegalStateException("Unable to resolve " + loader + " loader version for CurseForge export. Reinstall the pack or repair its DroidBridge metadata first.");
        }
        String loaderId = buildCurseForgeLoaderId(loader, loaderVersion);
        if (!isBlank(loaderId)) {
            JSONObject modLoader = new JSONObject();
            modLoader.put("id", loaderId);
            modLoader.put("primary", true);
            modLoaders.put(modLoader);
        }
        minecraft.put("modLoaders", modLoaders);
        return minecraft;
    }

    @NonNull
    private static String buildCurseForgeLoaderId(@NonNull String loader, @Nullable String loaderVersion) {
        if (isBlank(loaderVersion)) return "";
        if ("Fabric".equalsIgnoreCase(loader)) return "fabric-" + loaderVersion;
        if ("Forge".equalsIgnoreCase(loader)) return "forge-" + loaderVersion;
        if ("NeoForge".equalsIgnoreCase(loader)) return "neoforge-" + loaderVersion;
        if ("Quilt".equalsIgnoreCase(loader)) return "quilt-" + loaderVersion;
        return "";
    }

    @NonNull
    private static String resolveLoaderVersion(
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @Nullable String baseVersionId
    ) {
        if (isBlank(loader) || "Vanilla".equalsIgnoreCase(loader)) return "";

        String stored = normalizeResolvedLoaderVersion(loader, readStoredLoaderVersion(gameDirectory, loader));
        if (looksLikeLoaderVersion(loader, stored)) return stored;

        String fromBaseVersionId = normalizeResolvedLoaderVersion(loader,
                resolveLoaderVersionFromVersionId(loader, baseVersionId, minecraftVersion));
        if (looksLikeLoaderVersion(loader, fromBaseVersionId)) return fromBaseVersionId;

        String fromInstalledVersionJson = normalizeResolvedLoaderVersion(loader,
                resolveLoaderVersionFromInstalledVersionJson(gameDirectory, minecraftVersion, loader, baseVersionId));
        if (looksLikeLoaderVersion(loader, fromInstalledVersionJson)) return fromInstalledVersionJson;

        String fromLibraries = normalizeResolvedLoaderVersion(loader,
                resolveLoaderVersionFromLibraries(gameDirectory, minecraftVersion, loader, baseVersionId));
        if (looksLikeLoaderVersion(loader, fromLibraries)) return fromLibraries;

        return "";
    }

    @NonNull
    private static String resolveLoaderVersionFromVersionId(
            @Nullable String loader,
            @Nullable String versionId,
            @Nullable String minecraftVersion
    ) {
        if (isBlank(loader) || isBlank(versionId)) return "";
        String id = versionId.trim();
        String lower = id.toLowerCase(Locale.US);
        String resolved = "";

        if ("Fabric".equalsIgnoreCase(loader)) {
            resolved = extractAfterMarker(id, lower, "fabric-loader-");
        } else if ("Quilt".equalsIgnoreCase(loader)) {
            resolved = extractAfterMarker(id, lower, "quilt-loader-");
        } else if ("Forge".equalsIgnoreCase(loader)) {
            if (lower.startsWith("forge-")) {
                resolved = id.substring("forge-".length()).trim();
            } else {
                resolved = extractAfterMarker(id, lower, "forge-");
            }
            resolved = stripMinecraftVersionPrefix(resolved, minecraftVersion);
        } else if ("NeoForge".equalsIgnoreCase(loader)) {
            resolved = resolveNeoForgeLoaderVersionFromVersionId(id);
            resolved = stripMinecraftVersionPrefix(resolved, minecraftVersion);
        }

        resolved = normalizeResolvedLoaderVersion(loader, resolved);
        return looksLikeLoaderVersion(loader, resolved) ? resolved : "";
    }

    @NonNull
    private static String extractAfterMarker(
            @NonNull String value,
            @NonNull String lowerValue,
            @NonNull String marker
    ) {
        int index = lowerValue.indexOf(marker);
        if (index < 0) return "";
        String rest = value.substring(index + marker.length()).trim();
        int dash = rest.indexOf('-');
        return dash >= 0 ? rest.substring(0, dash).trim() : rest;
    }

    @NonNull
    private static String stripMinecraftVersionPrefix(@Nullable String version, @Nullable String minecraftVersion) {
        if (isBlank(version)) return "";
        String clean = version.trim();

        if (!isBlank(minecraftVersion)) {
            String mc = minecraftVersion.trim();
            if (clean.startsWith(mc + "-")) {
                return clean.substring(mc.length() + 1).trim();
            }
        }

        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^1\\.\\d+(?:\\.\\d+)?-(.+)$")
                .matcher(clean);
        if (matcher.matches()) {
            return matcher.group(1).trim();
        }
        return clean;
    }

    @NonNull
    private static String resolveLoaderVersionFromInstalledVersionJson(
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @Nullable String baseVersionId
    ) {
        File versionsDirectory = findMinecraftChildDirectory(gameDirectory, "versions");
        if (versionsDirectory == null || !versionsDirectory.isDirectory()) return "";

        ArrayList<File> exactCandidates = new ArrayList<>();
        addVersionJsonCandidate(exactCandidates, versionsDirectory, baseVersionId);

        File[] children = versionsDirectory.listFiles();
        if (children == null) return "";

        for (File child : children) {
            if (!child.isDirectory()) continue;
            String name = child.getName();
            if (!isBlank(baseVersionId) && name.equalsIgnoreCase(baseVersionId.trim())) continue;
            if (versionDirectoryNameSuggestsLoader(name, loader)) {
                addVersionJsonCandidate(exactCandidates, versionsDirectory, name);
            }
        }

        for (File file : exactCandidates) {
            String resolved = resolveLoaderVersionFromVersionJson(file, minecraftVersion, loader, true);
            if (looksLikeLoaderVersion(loader, resolved)) return resolved;
        }

        ArrayList<File> fuzzyCandidates = new ArrayList<>();
        for (File child : children) {
            if (child.isDirectory()) {
                addVersionJsonCandidate(fuzzyCandidates, versionsDirectory, child.getName());
            } else if (child.isFile() && child.getName().toLowerCase(Locale.US).endsWith(".json")) {
                addUniqueFile(fuzzyCandidates, child);
            }
        }

        for (File file : fuzzyCandidates) {
            String resolved = resolveLoaderVersionFromVersionJson(file, minecraftVersion, loader, false);
            if (looksLikeLoaderVersion(loader, resolved)) return resolved;
        }
        return "";
    }

    private static boolean versionDirectoryNameSuggestsLoader(@Nullable String versionId, @Nullable String loader) {
        if (isBlank(versionId) || isBlank(loader)) return false;
        String lower = versionId.trim().toLowerCase(Locale.US);
        if ("Fabric".equalsIgnoreCase(loader)) return lower.contains("fabric");
        if ("Quilt".equalsIgnoreCase(loader)) return lower.contains("quilt");
        if ("NeoForge".equalsIgnoreCase(loader)) return lower.contains("neoforge") || lower.contains("neo-forge");
        if ("Forge".equalsIgnoreCase(loader)) return lower.contains("forge") && !lower.contains("neoforge") && !lower.contains("neo-forge");
        return false;
    }

    private static void addVersionJsonCandidate(
            @NonNull ArrayList<File> out,
            @Nullable File versionsDirectory,
            @Nullable String versionId
    ) {
        if (versionsDirectory == null || isBlank(versionId)) return;
        String clean = versionId.trim();
        File nested = new File(new File(versionsDirectory, clean), clean + ".json");
        if (nested.isFile()) addUniqueFile(out, nested);
        File flat = new File(versionsDirectory, clean + ".json");
        if (flat.isFile()) addUniqueFile(out, flat);
    }

    private static void addUniqueFile(@NonNull ArrayList<File> out, @Nullable File file) {
        if (file == null || !file.isFile()) return;
        String wanted = safeCanonicalPath(file);
        for (File existing : out) {
            if (wanted.equals(safeCanonicalPath(existing))) return;
        }
        out.add(file);
    }

    @NonNull
    private static String resolveLoaderVersionFromVersionJson(
            @NonNull File file,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            boolean exactCandidate
    ) {
        try {
            JSONObject json = new JSONObject(readTextFile(file));
            String fromLoaderVersion = normalizeResolvedLoaderVersion(loader, json.optString("loaderVersion", ""));
            if (looksLikeLoaderVersion(loader, fromLoaderVersion)) return fromLoaderVersion;

            JSONArray libraries = json.optJSONArray("libraries");
            String fromLibraries = resolveLoaderVersionFromLibrariesArray(libraries, loader, minecraftVersion);
            if (looksLikeLoaderVersion(loader, fromLibraries) && (exactCandidate || versionJsonMatchesMinecraft(json, minecraftVersion, fromLibraries))) {
                return fromLibraries;
            }

            String fromId = resolveLoaderVersionFromVersionId(loader, json.optString("id", file.getName()), minecraftVersion);
            if (looksLikeLoaderVersion(loader, fromId) && (exactCandidate || versionJsonMatchesMinecraft(json, minecraftVersion, fromId))) {
                return fromId;
            }

            String fromInherits = resolveLoaderVersionFromVersionId(loader, json.optString("inheritsFrom", ""), minecraftVersion);
            if (looksLikeLoaderVersion(loader, fromInherits) && (exactCandidate || versionJsonMatchesMinecraft(json, minecraftVersion, fromInherits))) {
                return fromInherits;
            }
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to read installed version metadata for export from " + file.getAbsolutePath() + ": " + throwable.getMessage());
        }
        return "";
    }

    private static boolean versionJsonMatchesMinecraft(
            @NonNull JSONObject json,
            @NonNull String minecraftVersion,
            @Nullable String resolvedLoaderVersion
    ) {
        if (isBlank(minecraftVersion)) return true;
        String mc = minecraftVersion.trim();
        String id = json.optString("id", "");
        String inheritsFrom = json.optString("inheritsFrom", "");
        if (id.contains(mc) || inheritsFrom.equalsIgnoreCase(mc) || inheritsFrom.contains(mc)) return true;
        if (!isBlank(resolvedLoaderVersion) && resolvedLoaderVersion.startsWith(mc + "-")) return true;

        JSONArray libraries = json.optJSONArray("libraries");
        if (libraries == null) return false;
        for (int i = 0; i < libraries.length(); i++) {
            JSONObject library = libraries.optJSONObject(i);
            if (library == null) continue;
            String name = library.optString("name", "");
            if (name.contains(":" + mc + "-") || name.endsWith(":" + mc)) return true;
        }
        return false;
    }

    @NonNull
    private static String resolveLoaderVersionFromLibrariesArray(
            @Nullable JSONArray libraries,
            @Nullable String loader,
            @Nullable String minecraftVersion
    ) {
        if (libraries == null) return "";
        for (int i = 0; i < libraries.length(); i++) {
            JSONObject library = libraries.optJSONObject(i);
            if (library == null) continue;
            String name = library.optString("name", "").trim();
            String resolved = resolveLoaderVersionFromMavenName(loader, name, minecraftVersion);
            if (looksLikeLoaderVersion(loader, resolved)) return resolved;
        }
        return "";
    }

    @NonNull
    private static String resolveLoaderVersionFromMavenName(
            @Nullable String loader,
            @Nullable String name,
            @Nullable String minecraftVersion
    ) {
        if (isBlank(loader) || isBlank(name)) return "";
        String clean = name.trim();
        String lower = clean.toLowerCase(Locale.US);
        String rawVersion = mavenVersion(clean);
        if (isBlank(rawVersion)) return "";

        String resolved = "";
        if ("Fabric".equalsIgnoreCase(loader) && lower.startsWith("net.fabricmc:fabric-loader:")) {
            resolved = rawVersion;
        } else if ("Quilt".equalsIgnoreCase(loader) && lower.startsWith("org.quiltmc:quilt-loader:")) {
            resolved = rawVersion;
        } else if ("Forge".equalsIgnoreCase(loader)
                && (lower.startsWith("net.minecraftforge:forge:") || lower.startsWith("net.minecraftforge:fmlloader:"))) {
            resolved = stripMinecraftVersionPrefix(rawVersion, minecraftVersion);
        } else if ("NeoForge".equalsIgnoreCase(loader)
                && (lower.startsWith("net.neoforged:neoforge:") || lower.startsWith("net.neoforged:forge:"))) {
            resolved = stripMinecraftVersionPrefix(rawVersion, minecraftVersion);
        }

        resolved = normalizeResolvedLoaderVersion(loader, resolved);
        return looksLikeLoaderVersion(loader, resolved) ? resolved : "";
    }

    @NonNull
    private static String mavenVersion(@NonNull String name) {
        String[] parts = name.split(":");
        return parts.length >= 3 ? parts[2].trim() : "";
    }

    @NonNull
    private static String resolveLoaderVersionFromLibraries(
            @NonNull File gameDirectory,
            @NonNull String minecraftVersion,
            @Nullable String loader,
            @Nullable String baseVersionId
    ) {
        File librariesDirectory = findMinecraftChildDirectory(gameDirectory, "libraries");
        if (librariesDirectory == null || !librariesDirectory.isDirectory()) return "";

        ArrayList<String> candidates = new ArrayList<>();
        if ("Fabric".equalsIgnoreCase(loader)) {
            collectLoaderVersionsFromMavenDirectory(candidates, new File(librariesDirectory, "net/fabricmc/fabric-loader"), loader, minecraftVersion, baseVersionId);
        } else if ("Quilt".equalsIgnoreCase(loader)) {
            collectLoaderVersionsFromMavenDirectory(candidates, new File(librariesDirectory, "org/quiltmc/quilt-loader"), loader, minecraftVersion, baseVersionId);
        } else if ("Forge".equalsIgnoreCase(loader)) {
            collectLoaderVersionsFromMavenDirectory(candidates, new File(librariesDirectory, "net/minecraftforge/forge"), loader, minecraftVersion, baseVersionId);
            collectLoaderVersionsFromMavenDirectory(candidates, new File(librariesDirectory, "net/minecraftforge/fmlloader"), loader, minecraftVersion, baseVersionId);
        } else if ("NeoForge".equalsIgnoreCase(loader)) {
            collectLoaderVersionsFromMavenDirectory(candidates, new File(librariesDirectory, "net/neoforged/neoforge"), loader, minecraftVersion, baseVersionId);
            collectLoaderVersionsFromMavenDirectory(candidates, new File(librariesDirectory, "net/neoforged/forge"), loader, minecraftVersion, baseVersionId);
        }

        if (candidates.size() == 1) return candidates.get(0);

        String fromBase = firstCandidateContainedBy(candidates, baseVersionId);
        if (looksLikeLoaderVersion(loader, fromBase)) return fromBase;

        String fromMinecraft = firstCandidateMatchingMinecraft(candidates, minecraftVersion, loader);
        return looksLikeLoaderVersion(loader, fromMinecraft) ? fromMinecraft : "";
    }

    private static void collectLoaderVersionsFromMavenDirectory(
            @NonNull ArrayList<String> out,
            @NonNull File directory,
            @Nullable String loader,
            @Nullable String minecraftVersion,
            @Nullable String baseVersionId
    ) {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            String raw = child.getName().trim();
            String resolved = normalizeResolvedLoaderVersion(loader, stripMinecraftVersionPrefix(raw, minecraftVersion));
            if (!looksLikeLoaderVersion(loader, resolved)) continue;

            if (!isBlank(baseVersionId)) {
                String base = baseVersionId.trim().toLowerCase(Locale.US);
                if (base.contains(raw.toLowerCase(Locale.US)) || base.contains(resolved.toLowerCase(Locale.US))) {
                    addUniqueString(out, resolved);
                    continue;
                }
            }

            if (!isBlank(minecraftVersion) && raw.startsWith(minecraftVersion.trim() + "-")) {
                addUniqueString(out, resolved);
                continue;
            }

            if ("NeoForge".equalsIgnoreCase(loader) && neoforgeVersionMatchesMinecraft(resolved, minecraftVersion)) {
                addUniqueString(out, resolved);
                continue;
            }

            if ("Fabric".equalsIgnoreCase(loader) || "Quilt".equalsIgnoreCase(loader)) {
                addUniqueString(out, resolved);
            }
        }
    }

    private static void addUniqueString(@NonNull ArrayList<String> out, @Nullable String value) {
        if (isBlank(value)) return;
        for (String existing : out) {
            if (existing.equalsIgnoreCase(value.trim())) return;
        }
        out.add(value.trim());
    }

    @NonNull
    private static String firstCandidateContainedBy(@NonNull ArrayList<String> candidates, @Nullable String value) {
        if (isBlank(value)) return "";
        String lower = value.trim().toLowerCase(Locale.US);
        for (String candidate : candidates) {
            if (lower.contains(candidate.toLowerCase(Locale.US))) return candidate;
        }
        return "";
    }

    @NonNull
    private static String firstCandidateMatchingMinecraft(
            @NonNull ArrayList<String> candidates,
            @Nullable String minecraftVersion,
            @Nullable String loader
    ) {
        if (isBlank(minecraftVersion)) return "";
        for (String candidate : candidates) {
            if ("NeoForge".equalsIgnoreCase(loader) && neoforgeVersionMatchesMinecraft(candidate, minecraftVersion)) {
                return candidate;
            }
        }
        return "";
    }

    private static boolean neoforgeVersionMatchesMinecraft(@Nullable String neoforgeVersion, @Nullable String minecraftVersion) {
        if (isBlank(neoforgeVersion) || isBlank(minecraftVersion)) return false;
        String mc = minecraftVersion.trim();
        if (mc.startsWith("1.")) mc = mc.substring(2);
        return neoforgeVersion.trim().startsWith(mc + ".") || neoforgeVersion.trim().equals(mc);
    }

    @Nullable
    private static File findMinecraftChildDirectory(@NonNull File gameDirectory, @NonNull String childName) {
        File current = gameDirectory;
        for (int i = 0; i < 10 && current != null; i++) {
            File child = new File(current, childName);
            if (child.isDirectory()) return child;
            current = current.getParentFile();
        }
        return null;
    }

    @NonNull
    private static String normalizeResolvedLoaderVersion(@Nullable String loader, @Nullable String version) {
        if (isBlank(version)) return "";
        String clean = version.trim();

        int lastColon = clean.lastIndexOf(':');
        if (lastColon >= 0 && lastColon + 1 < clean.length()) {
            clean = clean.substring(lastColon + 1).trim();
        }

        String lower = clean.toLowerCase(Locale.US);
        if ("Forge".equalsIgnoreCase(loader) && lower.startsWith("forge-")) {
            clean = clean.substring("forge-".length()).trim();
        } else if ("NeoForge".equalsIgnoreCase(loader)) {
            clean = stripNeoForgePrefix(clean);
        }

        java.util.regex.Matcher minecraftPrefixed = java.util.regex.Pattern
                .compile("^1\\.\\d+(?:\\.\\d+)?-(.+)$")
                .matcher(clean);
        if (minecraftPrefixed.matches()) {
            clean = minecraftPrefixed.group(1).trim();
        }

        return clean.trim();
    }

    /**
     * Do not export instance names, pack slugs, or corrupted one-part values as loader versions.
     * Examples of bad values from older metadata: 2, forge-2, neoforge-the-pixelmon-modpack-5.
     */
    private static boolean looksLikeLoaderVersion(@Nullable String loader, @Nullable String version) {
        if (isBlank(version)) return false;
        String clean = normalizeResolvedLoaderVersion(loader, version);
        if (clean.equalsIgnoreCase("recommended") || clean.equalsIgnoreCase("latest")) return true;

        if ("Forge".equalsIgnoreCase(loader)
                || "NeoForge".equalsIgnoreCase(loader)
                || "Fabric".equalsIgnoreCase(loader)
                || "Quilt".equalsIgnoreCase(loader)) {
            return clean.matches("^\\d+(?:\\.\\d+)+(?:[-+._][A-Za-z0-9][A-Za-z0-9._-]*)?$");
        }

        return clean.matches("^\\d+(?:\\.\\d+)+(?:[-+._][A-Za-z0-9][A-Za-z0-9._-]*)?$");
    }

    @NonNull
    private static String readStoredLoaderVersion(@NonNull File gameDirectory, @Nullable String loader) {
        String fromPackManifest = readStoredLoaderVersionFromFile(new File(
                new File(gameDirectory, DROIDBRIDGE_METADATA_DIRECTORY),
                MODPACK_MANIFEST_FILE
        ), loader);
        if (!isBlank(fromPackManifest)) return fromPackManifest;

        return readStoredLoaderVersionFromFile(new File(
                new File(gameDirectory, DROIDBRIDGE_METADATA_DIRECTORY),
                MODPACK_FILES_MANIFEST_FILE
        ), loader);
    }

    @NonNull
    private static String readStoredLoaderVersionFromFile(@NonNull File file, @Nullable String loader) {
        if (!file.isFile()) return "";
        try {
            JSONObject json = new JSONObject(readTextFile(file));
            String storedLoader = json.optString("loader", "").trim();
            if (!isBlank(storedLoader) && !isBlank(loader) && !storedLoader.equalsIgnoreCase(loader)) {
                String normalizedStored = storedLoader.toLowerCase(Locale.US).replace("-loader", "");
                String normalizedWanted = loader.toLowerCase(Locale.US).replace("-loader", "");
                if (!normalizedStored.equals(normalizedWanted)) return "";
            }
            return json.optString("loaderVersion", "").trim();
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to read stored modpack loader metadata from " + file.getAbsolutePath() + ": " + throwable.getMessage());
            return "";
        }
    }

    @NonNull
    private static String readTextFile(@NonNull File file) throws Exception {
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copyStream(input, output);
            return output.toString("UTF-8");
        }
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

    /**
     * NeoForge version ids are not consistent across launcher forks. Examples:
     * - neoforge-21.1.203
     * - neoforge-21.1.203-beta
     * - neoforge-1.21.1-21.1.203
     * - 1.21.1-neoforge-21.1.203
     *
     * The old exporter split on the first dash after "neoforge-", which turned
     * versions such as "21.1.203-beta" into just "beta". The imported pack then
     * failed with "NeoForge not found". Keep the full NeoForge version and only
     * strip a Minecraft-version prefix when one is definitely present.
     */
    @NonNull
    private static String resolveNeoForgeLoaderVersionFromVersionId(@NonNull String versionId) {
        String value = versionId.trim();
        String lower = value.toLowerCase(Locale.US);

        int marker = lower.indexOf("neoforge-");
        if (marker >= 0) {
            value = value.substring(marker + "neoforge-".length());
        }

        value = value.trim();
        while (value.startsWith("-")) value = value.substring(1).trim();

        java.util.regex.Matcher minecraftPrefixed = java.util.regex.Pattern
                .compile("^1\\.\\d+(?:\\.\\d+)?-(.+)$")
                .matcher(value);
        if (minecraftPrefixed.matches()) {
            value = minecraftPrefixed.group(1).trim();
        }

        return value;
    }

    private static void addWarnings(
            @NonNull ZipOutputStream zip,
            @NonNull ArrayList<String> warnings,
            @NonNull Platform platform
    ) throws Exception {
        if (warnings.isEmpty()) return;
        StringBuilder builder = new StringBuilder();
        builder.append("DroidBridge modpack export warnings\n\n");
        if (platform == Platform.MODRINTH) {
            builder.append("Modrinth publishing note: .mrpack uploads should reference allowed remote downloads where possible. Files in overrides may be rejected if they are redistributed mods/resource packs without permission.\n\n");
        } else if (platform == Platform.CURSEFORGE) {
            builder.append("CurseForge publishing note: CurseForge project uploads normally expect CurseForge-hosted files in manifest.json. Files in overrides/mods may be rejected unless they are approved third-party resources.\n\n");
        } else {
            builder.append("MultiMC/Prism sharing note: this export bundles .minecraft files directly. Make sure every included resource is allowed to be redistributed before sharing.\n\n");
        }
        for (String warning : warnings) builder.append("- ").append(warning).append('\n');
        addTextEntry(zip, "EXPORT_WARNINGS.txt", builder.toString());
    }

    @NonNull
    private static String buildModListHtml(@NonNull ArrayList<FileRecord> records) {
        StringBuilder builder = new StringBuilder();
        builder.append("<ul>\n");
        for (FileRecord record : records) {
            builder.append("  <li>").append(escapeHtml(record.file.getName())).append("</li>\n");
        }
        builder.append("</ul>\n");
        return builder.toString();
    }

    @NonNull
    private static String escapeHtml(@Nullable String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void addFileOrDirectoryToZip(
            @NonNull ZipOutputStream zip,
            @NonNull File source,
            @NonNull String zipPath
    ) throws Exception {
        File parent = source.getParentFile();
        if (VULKAN_SHIELD_FALLBACK_PACK_FOLDER.equals(source.getName())
                && parent != null
                && ("resourcepacks".equalsIgnoreCase(parent.getName())
                || "texturepacks".equalsIgnoreCase(parent.getName()))) {
            return;
        }
        if (source.isDirectory()) {
            File[] children = source.listFiles();
            if (children == null) return;
            for (File child : children) addFileOrDirectoryToZip(zip, child, zipPath + "/" + child.getName());
        } else if (source.isFile()) {
            addFileToZip(zip, source, zipPath);
        }
    }

    private static void addOptionsFileToZip(
            @NonNull ZipOutputStream zip,
            @NonNull File source,
            @NonNull String zipPath
    ) throws Exception {
        String original = readTextFile(source);
        String newline = original.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = original.split("\\r?\\n", -1);
        StringBuilder sanitized = new StringBuilder(original.length());

        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            if (lineIndex > 0) sanitized.append(newline);
            String line = lines[lineIndex];
            int separator = line.indexOf(':');
            if (separator <= 0
                    || !"resourcePacks".equals(line.substring(0, separator).trim())) {
                sanitized.append(line);
                continue;
            }

            String value = line.substring(separator + 1).trim();
            try {
                JSONArray current = new JSONArray(value);
                JSONArray updated = new JSONArray();
                for (int i = 0; i < current.length(); i++) {
                    Object entry = current.opt(i);
                    if (!VULKAN_SHIELD_FALLBACK_PACK_ID.equals(entry)) updated.put(entry);
                }
                sanitized.append("resourcePacks:").append(updated.toString());
            } catch (Throwable ignored) {
                // Never damage a user-authored options file during export.
                sanitized.append(line);
            }
        }

        addTextEntry(zip, zipPath, sanitized.toString());
    }

    private static void addFileToZip(
            @NonNull ZipOutputStream zip,
            @NonNull File source,
            @NonNull String zipPath
    ) throws Exception {
        String safePath = zipPath.replace('\\', '/');
        zip.putNextEntry(new ZipEntry(safePath));
        try (InputStream input = new FileInputStream(source)) {
            copyStream(input, zip);
        }
        zip.closeEntry();
    }

    private static void addTextEntry(
            @NonNull ZipOutputStream zip,
            @NonNull String zipPath,
            @NonNull String text
    ) throws Exception {
        zip.putNextEntry(new ZipEntry(zipPath));
        zip.write(text.getBytes("UTF-8"));
        zip.closeEntry();
    }

    @NonNull
    private static String sha1(@NonNull File file) throws Exception {
        return hashFile(file, "SHA-1");
    }

    @NonNull
    private static String sha512(@NonNull File file) throws Exception {
        return hashFile(file, "SHA-512");
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

    private static void copyStream(@NonNull InputStream input, @NonNull OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
    }

    @NonNull
    private static String optStringAny(@Nullable JSONObject object, @NonNull String... keys) {
        if (object == null) return "";
        for (String key : keys) {
            String value = object.optString(key, "").trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static int optIntAny(@Nullable JSONObject object, @NonNull String... keys) {
        if (object == null) return 0;
        for (String key : keys) {
            int value = object.optInt(key, 0);
            if (value > 0) return value;
            String raw = object.optString(key, "").trim();
            if (!raw.isEmpty()) {
                try {
                    value = Integer.parseInt(raw);
                    if (value > 0) return value;
                } catch (Throwable ignored) {
                }
            }
        }
        return 0;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class FileRecord {
        @NonNull
        final File file;
        @NonNull
        final String relativePath;
        @NonNull
        final ModManagerSource source;
        @Nullable
        final String downloadUrl;
        @NonNull
        final String trackedSha1;
        @NonNull
        final String trackedSha512;
        final int projectId;
        final int fileId;

        FileRecord(
                @NonNull File file,
                @NonNull String relativePath,
                @NonNull ModManagerSource source,
                @Nullable String downloadUrl,
                @Nullable String trackedSha1,
                @Nullable String trackedSha512,
                int projectId,
                int fileId
        ) {
            this.file = file;
            this.relativePath = relativePath.replace('\\', '/');
            this.source = source;
            this.downloadUrl = downloadUrl;
            this.trackedSha1 = trackedSha1 == null ? "" : trackedSha1.trim();
            this.trackedSha512 = trackedSha512 == null ? "" : trackedSha512.trim();
            this.projectId = projectId;
            this.fileId = fileId;
        }

        @NonNull
        static FileRecord fromEntry(@NonNull File file, @NonNull String relativePath, @Nullable JSONObject entry) {
            ModManagerSource source = ModManagerSource.UNKNOWN;
            try {
                if (entry != null) source = ModManagerManifest.getSource(entry);
            } catch (Throwable ignored) {
            }
            if (source == ModManagerSource.UNKNOWN && entry != null) {
                String sourceId = optStringAny(entry, "source", "platform", "modpackPlatform").toLowerCase(Locale.US);
                if ("modrinth".equals(sourceId)) {
                    source = ModManagerSource.MODRINTH;
                } else if ("curseforge".equals(sourceId) || "curse_forge".equals(sourceId)) {
                    source = ModManagerSource.CURSEFORGE;
                }
            }

            String downloadUrl = optStringAny(entry, "downloadUrl", "url", "fileUrl", "primaryDownloadUrl");
            if (isBlank(downloadUrl) && entry != null) {
                JSONArray downloads = entry.optJSONArray("downloads");
                if (downloads != null && downloads.length() > 0) {
                    downloadUrl = downloads.optString(0, "").trim();
                }
            }

            String trackedSha1 = optStringAny(entry, "sha1", "fileSha1");
            String trackedSha512 = optStringAny(entry, "sha512", "fileSha512");
            if (entry != null) {
                JSONObject hashes = entry.optJSONObject("hashes");
                if (isBlank(trackedSha1) && hashes != null) trackedSha1 = hashes.optString("sha1", "").trim();
                if (isBlank(trackedSha512) && hashes != null) trackedSha512 = hashes.optString("sha512", "").trim();

                JSONObject modrinthPackFile = entry.optJSONObject("modrinthPackFile");
                JSONObject modrinthHashes = modrinthPackFile == null ? null : modrinthPackFile.optJSONObject("hashes");
                if (isBlank(trackedSha1) && modrinthHashes != null) trackedSha1 = modrinthHashes.optString("sha1", "").trim();
                if (isBlank(trackedSha512) && modrinthHashes != null) trackedSha512 = modrinthHashes.optString("sha512", "").trim();
            }

            int projectId = optIntAny(entry, "curseForgeProjectId", "platformProjectId", "projectId", "modId");
            int fileId = optIntAny(entry, "curseForgeFileId", "platformFileId", "fileId");
            return new FileRecord(file, relativePath, source, downloadUrl, trackedSha1, trackedSha512, projectId, fileId);
        }

        boolean canUseModrinthDownload(@NonNull String currentSha1) {
            if (source != ModManagerSource.MODRINTH || isBlank(downloadUrl)) return false;

            // If the original Modrinth hash is known, only reference the remote
            // download when the local file still matches it. DroidBridge can patch
            // jars during compatibility mitigation; exporting those patched jars
            // with the old Modrinth URL creates an invalid .mrpack because the
            // importer downloads the original jar but verifies against the patched
            // local hash. In that case the file must be bundled under overrides.
            if (!isBlank(trackedSha1)) {
                return trackedSha1.equalsIgnoreCase(currentSha1);
            }

            // Older metadata did not always store the Modrinth hash. Keep the old
            // behavior for those entries rather than dropping valid remote files.
            return true;
        }
    }
}

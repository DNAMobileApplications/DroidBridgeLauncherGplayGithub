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

package ca.dnamobile.droidbridgelauncher.launcher;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ca.dnamobile.droidbridgelauncher.data.AccountStore;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.network.MinecraftDownloadSource;
import ca.dnamobile.droidbridgelauncher.modcompat.BtaGlfwBufferCompat;
import ca.dnamobile.droidbridgelauncher.modcompat.ControllerModCompat;
import ca.dnamobile.droidbridgelauncher.modcompat.ControlifySDL;
import ca.dnamobile.droidbridgelauncher.modcompat.FFmpegPluginCompat;
import ca.dnamobile.droidbridgelauncher.modcompat.E4MCCompat;
import ca.dnamobile.droidbridgelauncher.modcompat.MCSRRankedCompat;
import ca.dnamobile.droidbridgelauncher.modcompat.VerityNativeCompat;
import ca.dnamobile.droidbridgelauncher.renderer.DriverPluginManager;
import ca.dnamobile.droidbridgelauncher.renderer.BtaRendererPolicy;
import ca.dnamobile.droidbridgelauncher.renderer.RendererInterface;
import ca.dnamobile.droidbridgelauncher.security.LauncherSecurity;
import ca.dnamobile.droidbridgelauncher.settings.LauncherPreferences;
import ca.dnamobile.droidbridgelauncher.settings.MemoryAllocationUtils;
import ca.dnamobile.droidbridgelauncher.skin.AccountSkinCache;
import ca.dnamobile.droidbridgelauncher.skin.CustomSkinStore;
import ca.dnamobile.droidbridgelauncher.skin.OfflineSkinProfile;
import ca.dnamobile.droidbridgelauncher.skin.OfflineYggdrasilServer;
import ca.dnamobile.droidbridgelauncher.skin.LegacySkinProxyServer;
import ca.dnamobile.droidbridgelauncher.skin.SkinModelType;
import ca.dnamobile.droidbridgelauncher.ui.version.CleanroomSupport;
import ca.dnamobile.droidbridgelauncher.ui.version.MinecraftVersionInstaller;
import ca.dnamobile.droidbridgelauncher.ui.version.InheritedVersionFlattener;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;
import ca.dnamobile.droidbridgelauncher.utils.path.LibPath;
import ca.dnamobile.droidbridgelauncher.runtime.Architecture;
import ca.dnamobile.droidbridgelauncher.runtime.multirt.MultiRTUtils;

public final class JavaLaunchBuilder {
    private static final String TAG = "JavaLaunchBuilder";
    private static final String DEFAULT_MAIN_CLASS = "net.minecraft.client.main.Main";
    private static final int DEFAULT_MEMORY_MB = 2048;
    private static final String DROIDBRIDGE_LIB_PATCHER_ASSET =
            "components/components/DroidBridgeLibPatcher.jar";
    private static final String DROIDBRIDGE_COMPONENT_VERSION_ASSET =
            "components/components/version";
    private static OfflineYggdrasilServer activeOfflineSkinServer;
    private static LegacySkinProxyServer activeLegacySkinProxyServer;

    private final Context context;
    private final String versionId;
    private final AccountStore.Account account;
    private final int width;
    private final int height;
    private File runtimeDirectoryOverride;
    private File gameDirectoryOverride;
    private RendererInterface rendererOverride;
    private boolean useSystemVulkanDriverOverride;
    @NonNull private String vulkanCompatibilityModeOverride = InstanceLaunchSettings.VULKAN_COMPAT_AUTO;

    public JavaLaunchBuilder(
            @NonNull Context context,
            @NonNull String versionId,
            @Nullable AccountStore.Account account,
            int width,
            int height
    ) {
        this.context = context.getApplicationContext();
        this.versionId = versionId;
        this.account = resolveLaunchAccount(this.context, account);
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.useSystemVulkanDriverOverride = LauncherPreferences.isUseSystemVulkanDriver(this.context);
    }

    @Nullable
    private static AccountStore.Account resolveLaunchAccount(
            @NonNull Context context,
            @Nullable AccountStore.Account requestedAccount
    ) {
        AccountStore.Account storedAccount = loadActiveAccountFallback(context);
        if (storedAccount == null) {
            return requestedAccount;
        }
        if (requestedAccount == null) {
            return storedAccount;
        }

        if (isSameLaunchAccount(requestedAccount, storedAccount)
                && storedAccount.isMicrosoftAccount()
                && !isNullOrBlank(storedAccount.minecraftAccessToken)) {
            if (!safeEquals(requestedAccount.minecraftAccessToken, storedAccount.minecraftAccessToken)) {
                Logging.i(TAG, "Using refreshed Microsoft account from AccountStore for launch arguments.");
            }
            return storedAccount;
        }

        return requestedAccount;
    }

    private static boolean isSameLaunchAccount(
            @NonNull AccountStore.Account first,
            @NonNull AccountStore.Account second
    ) {
        if (sameNonBlank(first.accountId, second.accountId)) return true;
        if (sameNonBlank(first.minecraftUuid, second.minecraftUuid)) return true;
        if (sameNonBlank(first.minecraftName, second.minecraftName)) return true;
        if (sameNonBlank(safeBestDisplayName(first), safeBestDisplayName(second))) return true;
        return false;
    }

    private static boolean sameNonBlank(@Nullable String first, @Nullable String second) {
        return !isNullOrBlank(first)
                && !isNullOrBlank(second)
                && first.trim().equalsIgnoreCase(second.trim());
    }

    @NonNull
    private static String safeBestDisplayName(@Nullable AccountStore.Account account) {
        if (account == null) return "";
        try {
            String name = account.getBestDisplayName();
            return name != null ? name : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean safeEquals(@Nullable String first, @Nullable String second) {
        if (first == null) return second == null;
        return first.equals(second);
    }

    @Nullable
    private static AccountStore.Account loadActiveAccountFallback(@NonNull Context context) {
        try {
            return new AccountStore(context).load();
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to load active account fallback", throwable);
            return null;
        }
    }

    @NonNull
    public JavaLaunchBuilder setRuntimeDirectory(@Nullable File runtimeDirectory) {
        this.runtimeDirectoryOverride = runtimeDirectory;
        return this;
    }

    @NonNull
    public JavaLaunchBuilder setGameDirectory(@Nullable File gameDirectory) {
        this.gameDirectoryOverride = gameDirectory;
        return this;
    }
    @NonNull
    public JavaLaunchBuilder setRenderer(@Nullable RendererInterface renderer) {
        this.rendererOverride = renderer;
        return this;
    }

    @NonNull
    public JavaLaunchBuilder setUseSystemVulkanDriver(boolean useSystemVulkanDriver) {
        this.useSystemVulkanDriverOverride = useSystemVulkanDriver;
        return this;
    }

    @NonNull
    public JavaLaunchBuilder setVulkanCompatibilityMode(@Nullable String mode) {
        this.vulkanCompatibilityModeOverride =
                InstanceLaunchSettings.sanitizeVulkanCompatibilityMode(mode);
        return this;
    }


    @NonNull
    public LaunchPlan build() throws Exception {
        if (gameDirectoryOverride != null) {
            PathManager.initContextConstants(context, PathManager.inferLauncherHomeFromGameDirectory(gameDirectoryOverride));
        } else {
            PathManager.initContextConstants(context);
        }

        File versionDir = MinecraftVersionInstaller.getVersionDirectory(versionId);
        File versionJsonFile = new File(versionDir, versionId + ".json");
        File gameDirectory = gameDirectoryOverride != null ? gameDirectoryOverride : new File(PathManager.DIR_MINECRAFT_HOME);
        ensureDirectory(gameDirectory);

        if (!versionJsonFile.isFile()) {
            throw new IllegalStateException("Missing version JSON: " + versionJsonFile.getAbsolutePath());
        }

        JSONObject rawVersionJson = new JSONObject(readFile(versionJsonFile));
        JSONObject versionJson = resolveInheritedVersionJson(rawVersionJson);
        String effectiveMinecraftVersionId = resolveEffectiveMinecraftVersionId(rawVersionJson, versionJson);
        versionJson = ensureCriticalMinecraftLibraries(versionJson, effectiveMinecraftVersionId);
        File clientJarFile = resolveClientJarFile(versionId, rawVersionJson);
        Logging.i(TAG, "Client jar resolution: launchVersion=" + versionId
                + " effectiveMinecraftVersion=" + effectiveMinecraftVersionId
                + " inheritsFrom=" + rawVersionJson.optString("inheritsFrom", "")
                + " jar=" + rawVersionJson.optString("jar", "")
                + " clientJar=" + clientJarFile.getAbsolutePath());
        String mainClass = versionJson.optString("mainClass", DEFAULT_MAIN_CLASS);
        String assetIndexName = resolveAssetIndexName(versionJson);
        File gameAssetsDir = resolveGameAssetsDirectory(versionJson, assetIndexName);
        int javaMajor = resolveJavaMajor(versionJson);
        String runtimeName = runtimeDirectoryOverride != null
                ? runtimeDirectoryOverride.getName()
                : runtimeNameForJava(javaMajor);
        File runtimeDir = runtimeDirectoryOverride != null
                ? runtimeDirectoryOverride
                : resolveRuntime(javaMajor);

        if (!RuntimeCompat.isRuntimeInstalledForJava(runtimeName, runtimeDir, javaMajor)) {
            throw new IllegalStateException("Selected runtime is not usable for Java "
                    + javaMajor
                    + ": "
                    + RuntimeCompat.describeRuntimeState(runtimeName, runtimeDir));
        }

        File javaBinary = RuntimeCompat.findJavaBinary(runtimeDir);
        if (javaBinary == null) {
            // Game launches use VMLauncher/JLI and do not execute bin/java.
            // Keep a stable placeholder in LaunchPlan for Java 8 runtimes that
            // are VM-launchable but do not ship a standalone java binary.
            javaBinary = new File(runtimeDir, "bin/java");
        }

        File lwjglComponentDir = resolveLwjglComponent(effectiveMinecraftVersionId, versionJson);
        if (BtaRendererPolicy.isBta8OrNewer(this.versionId, null)) {
            // BTA 8 reuses one-element GLFW output buffers. Repair the isolated
            // DroidBridge BTA GLFW shim before its jars are placed on the JVM
            // classpath so native-compatible output calls preserve buffer position.
            BtaGlfwBufferCompat.prepare(lwjglComponentDir);
        }
        File lwjglNativesDir = resolveLwjglNativeDir(lwjglComponentDir);
        MinecraftVersionInstaller.ensureJnaNativesForLaunch(versionId, versionJson);
        boolean forgeLaunch = isForgeOrBootstrapVersion(versionJson);
        boolean optiFinePresent = forgeLaunch && hasEnabledOptiFineModJar(gameDirectory);
        ArrayList<String> optiFineSecureJarIgnoreNames = new ArrayList<>();
        if (forgeLaunch) {
            logOptiFineForgeCompatibility(versionJson, effectiveMinecraftVersionId, gameDirectory, optiFinePresent);
        }
        String classPath = buildClassPath(
                versionJson,
                clientJarFile,
                lwjglComponentDir,
                forgeLaunch,
                optiFinePresent,
                optiFineSecureJarIgnoreNames,
                effectiveMinecraftVersionId,
                gameDirectory
        );

        Map<String, String> replacements = buildReplacements(
                versionJson,
                assetIndexName,
                gameAssetsDir,
                classPath,
                lwjglNativesDir,
                gameDirectory
        );

        ArrayList<String> jvmArgs = buildJvmArgs(
                runtimeDir,
                lwjglNativesDir,
                classPath,
                replacements,
                versionJson,
                gameDirectory,
                rendererOverride,
                optiFinePresent,
                optiFineSecureJarIgnoreNames
        );
        ArrayList<String> gameArgs = buildGameArgs(versionJson, replacements);

        writeLegacyForgeSplashConfigIfNeeded(runtimeDir, versionJson, gameDirectory);

        File debugFile = new File(versionDir, "java_launcher_last_launch_args.txt");
        writeDebugLaunchFile(debugFile, runtimeDir, mainClass, classPath, jvmArgs, gameArgs);

        return new LaunchPlan(
                versionId,
                mainClass,
                gameDirectory,
                runtimeDir,
                javaBinary,
                lwjglNativesDir,
                classPath,
                jvmArgs,
                gameArgs,
                useSystemVulkanDriverOverride,
                vulkanCompatibilityModeOverride,
                effectiveMinecraftVersionId
        );
    }
    private static void ensureDirectory(@NonNull File directory) {
        if (directory.exists()) {
            if (!directory.isDirectory()) {
                throw new IllegalStateException("Path exists but is not a directory: " + directory.getAbsolutePath());
            }
            return;
        }

        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("Unable to create directory: " + directory.getAbsolutePath());
        }
    }

    @NonNull
    private JSONObject resolveInheritedVersionJson(@NonNull JSONObject childJson) throws Exception {
        String inheritsFrom = childJson.optString("inheritsFrom", "");
        if (inheritsFrom.isEmpty()) {
            return childJson;
        }

        JSONObject parentJson = resolveInheritedVersionJson(readVersionJson(inheritsFrom));
        JSONObject merged = new JSONObject(parentJson.toString());

        JSONArray names = childJson.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String key = names.getString(i);
                if ("libraries".equals(key) || "arguments".equals(key)) continue;
                merged.put(key, childJson.get(key));
            }
        }

        merged.put("libraries", mergeLibraries(parentJson.optJSONArray("libraries"), childJson.optJSONArray("libraries")));
        merged.put("arguments", mergeArguments(parentJson.optJSONObject("arguments"), childJson.optJSONObject("arguments")));

        if (!childJson.has("minecraftArguments") && parentJson.has("minecraftArguments")) {
            merged.put("minecraftArguments", parentJson.optString("minecraftArguments", ""));
        }

        return merged;
    }

    @NonNull
    private JSONObject readVersionJson(@NonNull String id) throws Exception {
        File versionDir = MinecraftVersionInstaller.getVersionDirectory(id);
        File json = new File(versionDir, id + ".json");
        if (!json.isFile()) {
            throw new IllegalStateException("Missing inherited version JSON: " + json.getAbsolutePath());
        }
        return new JSONObject(readFile(json));
    }

    @NonNull
    private File resolveClientJarFile(@NonNull String id, @NonNull JSONObject rawVersionJson) {
        File ownJar = new File(MinecraftVersionInstaller.getVersionDirectory(id), id + ".jar");
        String referencedJarId = rawVersionJson.optString("jar", "").trim();
        String inheritsFrom = rawVersionJson.optString("inheritsFrom", "").trim();

        /*
         * OptiFine installer profiles are inheritance profiles: the OptiFine code
         * lives in libraries/optifine while the Minecraft client classes come from
         * the declared vanilla jar/parent. A stale or installer-created profile-local
         * jar must not silently replace that declared base. This also makes the
         * selected client jar match the version metadata shown in DroidBridge.
         */
        if (isOptiFineProfile(id, rawVersionJson)) {
            if (!referencedJarId.isEmpty() && !referencedJarId.equals(id)) {
                File referencedJar = new File(
                        MinecraftVersionInstaller.getVersionDirectory(referencedJarId),
                        referencedJarId + ".jar"
                );
                if (referencedJar.isFile()) return referencedJar;
                throw new IllegalStateException("Missing OptiFine referenced client jar: "
                        + referencedJar.getAbsolutePath());
            }

            if (!inheritsFrom.isEmpty() && !inheritsFrom.equals(id)) {
                File parentJar = new File(
                        MinecraftVersionInstaller.getVersionDirectory(inheritsFrom),
                        inheritsFrom + ".jar"
                );
                if (parentJar.isFile()) return parentJar;
                throw new IllegalStateException("Missing OptiFine inherited client jar: "
                        + parentJar.getAbsolutePath());
            }
        }

        if (ownJar.isFile()) {
            return ownJar;
        }

        // Cleanroom and some official-launcher-compatible profiles use the
        // standard "jar" field to reuse another version's client jar without
        // inheriting that version's complete library list.
        if (!referencedJarId.isEmpty() && !referencedJarId.equals(id)) {
            File referencedJar = new File(
                    MinecraftVersionInstaller.getVersionDirectory(referencedJarId),
                    referencedJarId + ".jar"
            );
            if (referencedJar.isFile()) return referencedJar;
            throw new IllegalStateException("Missing referenced client jar: " + referencedJar.getAbsolutePath());
        }

        if (!inheritsFrom.isEmpty()) {
            File parentJar = new File(MinecraftVersionInstaller.getVersionDirectory(inheritsFrom), inheritsFrom + ".jar");
            if (parentJar.isFile()) return parentJar;
            throw new IllegalStateException("Missing inherited client jar: " + parentJar.getAbsolutePath());
        }

        // Current Cleanroom installer profiles can be standalone JSON profiles
        // with neither "jar" nor "inheritsFrom". Cleanroom is 1.12.2-only,
        // so its client classes must come from the installed vanilla 1.12.2 jar.
        if (CleanroomSupport.isCleanroomProfile(id, rawVersionJson)) {
            File cleanroomBaseJar = new File(
                    MinecraftVersionInstaller.getVersionDirectory(CleanroomSupport.MINECRAFT_VERSION),
                    CleanroomSupport.MINECRAFT_VERSION + ".jar"
            );
            if (cleanroomBaseJar.isFile()) return cleanroomBaseJar;
            throw new IllegalStateException("Missing Cleanroom base client jar: "
                    + cleanroomBaseJar.getAbsolutePath());
        }

        throw new IllegalStateException("Missing client jar: " + ownJar.getAbsolutePath());
    }

    private static boolean isOptiFineProfile(@NonNull String id, @NonNull JSONObject rawVersionJson) {
        if (id.toLowerCase(Locale.ROOT).contains("optifine")) return true;
        JSONArray libraries = rawVersionJson.optJSONArray("libraries");
        if (libraries == null) return false;
        for (int i = 0; i < libraries.length(); i++) {
            JSONObject library = libraries.optJSONObject(i);
            if (library == null) continue;
            String name = library.optString("name", "").toLowerCase(Locale.ROOT);
            if (name.contains("optifine")) return true;
        }
        return false;
    }

    @NonNull
    private String resolveEffectiveMinecraftVersionId(
            @NonNull JSONObject rawVersionJson,
            @NonNull JSONObject mergedVersionJson
    ) {
        String inheritsFrom = rawVersionJson.optString(
                "inheritsFrom",
                mergedVersionJson.optString("inheritsFrom", "")
        ).trim();
        if (!inheritsFrom.isEmpty()) return inheritsFrom;

        String referencedJarId = rawVersionJson.optString(
                "jar",
                mergedVersionJson.optString("jar", "")
        ).trim();
        if (!referencedJarId.isEmpty() && !referencedJarId.equals(versionId)) {
            return referencedJarId;
        }

        if (CleanroomSupport.isCleanroomProfile(versionId, rawVersionJson)) {
            return CleanroomSupport.MINECRAFT_VERSION;
        }

        String flattenedParent = mergedVersionJson.optString("javaLauncherFlattenedParent", "").trim();
        if (flattenedParent.isEmpty()) {
            String markerParent = InheritedVersionFlattener.readFlattenedParentId(context, versionId);
            if (markerParent != null) flattenedParent = markerParent.trim();
        }
        if (!flattenedParent.isEmpty()) return flattenedParent;

        String id = mergedVersionJson.optString("id", versionId);
        if (id.startsWith("fabric-loader-")) {
            int lastDash = id.lastIndexOf('-');
            if (lastDash > 0 && lastDash + 1 < id.length()) {
                return id.substring(lastDash + 1);
            }
        }

        return id;
    }


    @NonNull
    private JSONObject ensureCriticalMinecraftLibraries(
            @NonNull JSONObject versionJson,
            @NonNull String minecraftVersionId
    ) throws Exception {
        JSONArray libraries = versionJson.optJSONArray("libraries");
        if (libraries == null) {
            libraries = new JSONArray();
            versionJson.put("libraries", libraries);
        }

        /*
         * Minecraft 1.21.3+ references com.mojang.jtracy.TracyClient during
         * RenderSystem bootstrap. Some Fabric inherited/flattened JSONs created
         * by older installer code can miss this Mojang library, which produces:
         *
         *   NoClassDefFoundError: com/mojang/jtracy/TracyClient
         *
         * Add it only when absent. If the official JSON already contains it,
         * this does nothing and preserves the official metadata/version.
         */
        if (requiresJtracyLibrary(minecraftVersionId) && !hasMavenLibrary(libraries, "com.mojang", "jtracy")) {
            JSONObject jtracy = null;

            /*
             * Prefer the exact jtracy declaration from the installed vanilla
             * version metadata.  Snapshot APIs can change between jtracy
             * releases (26.3-snapshot-9 requires createSectionCategory(String)),
             * so injecting an old hard-coded jar can produce NoSuchMethodError
             * even though the correct dependency is already installed.
             *
             * This path is mainly for flattened Fabric/Quilt profiles whose
             * generated JSON accidentally lost a vanilla Mojang library.
             */
            try {
                JSONObject vanillaJson = readVersionJson(minecraftVersionId);
                JSONObject declared = findMavenLibrary(
                        vanillaJson.optJSONArray("libraries"),
                        "com.mojang",
                        "jtracy"
                );
                if (declared != null) {
                    jtracy = new JSONObject(declared.toString());
                    Logging.i(TAG, "Restored critical Mojang library from vanilla metadata: "
                            + jtracy.optString("name")
                            + " for Minecraft "
                            + minecraftVersionId);
                }
            } catch (Throwable throwable) {
                Logging.i(TAG, "Unable to read vanilla jtracy metadata for "
                        + minecraftVersionId
                        + "; legacy fallback will be considered: "
                        + throwable.getClass().getSimpleName());
            }

            if (jtracy == null) {
                jtracy = createMavenLibrary("com.mojang:jtracy:1.0.29");
                Logging.i(TAG, "Added legacy missing critical Mojang library fallback: "
                        + jtracy.optString("name")
                        + " for Minecraft "
                        + minecraftVersionId);
            }

            libraries.put(jtracy);
        }

        return versionJson;
    }

    private boolean requiresJtracyLibrary(@NonNull String minecraftVersionId) {
        String id = minecraftVersionId.trim().toLowerCase(Locale.ROOT);

        // Modern year/snapshot style versions also use the modern RenderSystem path.
        if (id.matches("^\\d{2}w\\d{2}[a-z].*$")) return true;
        if (id.matches("^\\d{2}\\..*$")) return true;

        int[] parsed = parseReleaseVersion(id);
        if (parsed == null) return false;

        int major = parsed[0];
        int minor = parsed[1];
        int patch = parsed[2];

        if (major > 1) return true;
        if (major < 1) return false;
        if (minor > 21) return true;
        if (minor < 21) return false;
        return patch >= 3;
    }

    @Nullable
    private int[] parseReleaseVersion(@NonNull String id) {
        String[] parts = id.split("[^0-9]+");
        ArrayList<Integer> numbers = new ArrayList<>();
        for (String part : parts) {
            if (part == null || part.isEmpty()) continue;
            try {
                numbers.add(Integer.parseInt(part));
            } catch (NumberFormatException ignored) {
            }
            if (numbers.size() >= 3) break;
        }

        if (numbers.size() < 2) return null;
        int major = numbers.get(0);
        int minor = numbers.get(1);
        int patch = numbers.size() >= 3 ? numbers.get(2) : 0;
        return new int[]{major, minor, patch};
    }

    private boolean hasMavenLibrary(
            @NonNull JSONArray libraries,
            @NonNull String expectedGroup,
            @NonNull String expectedArtifact
    ) {
        return findMavenLibrary(libraries, expectedGroup, expectedArtifact) != null;
    }

    @Nullable
    private JSONObject findMavenLibrary(
            @Nullable JSONArray libraries,
            @NonNull String expectedGroup,
            @NonNull String expectedArtifact
    ) {
        if (libraries == null) return null;

        for (int i = 0; i < libraries.length(); i++) {
            JSONObject library = libraries.optJSONObject(i);
            if (library == null) continue;

            String[] parts = library.optString("name", "").split(":");
            if (parts.length < 2) continue;

            // Only the base Java library counts as present here.
            // A classifier/native jar such as com.mojang:jtracy:1.0.29:natives-linux
            // does not contain com.mojang.jtracy.TracyClient and must not block
            // adding the base com.mojang:jtracy jar.
            if (parts.length == 3 && expectedGroup.equals(parts[0]) && expectedArtifact.equals(parts[1])) {
                return library;
            }
        }
        return null;
    }

    @NonNull
    private JSONObject createMavenLibrary(@NonNull String name) throws Exception {
        String path = artifactToPath(name);
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("Invalid Maven library name: " + name);
        }

        JSONObject artifact = new JSONObject();
        artifact.put("path", path);
        artifact.put("url", "https://libraries.minecraft.net/" + path);

        JSONObject downloads = new JSONObject();
        downloads.put("artifact", artifact);

        JSONObject library = new JSONObject();
        library.put("name", name);
        library.put("downloads", downloads);
        return library;
    }

    @NonNull
    private JSONArray mergeArrays(@Nullable JSONArray parent, @Nullable JSONArray child) throws Exception {
        JSONArray merged = new JSONArray();
        if (parent != null) {
            for (int i = 0; i < parent.length(); i++) merged.put(parent.get(i));
        }
        if (child != null) {
            for (int i = 0; i < child.length(); i++) merged.put(child.get(i));
        }
        return merged;
    }

    /**
     * Version JSON inheritance appends child libraries after parent libraries.
     * Fabric/Quilt loaders can intentionally override parent Mojang libraries
     * such as org.ow2.asm:asm. If both versions reach the final classpath,
     * Fabric Loader aborts with duplicate ASM classes.
     */
    @NonNull
    private JSONArray mergeLibraries(@Nullable JSONArray parent, @Nullable JSONArray child) throws Exception {
        LinkedHashMap<String, JSONObject> merged = new LinkedHashMap<>();

        if (parent != null) {
            for (int i = 0; i < parent.length(); i++) {
                JSONObject library = parent.optJSONObject(i);
                if (library == null) continue;

                String key = getLibraryMergeKey(library);
                if (key.isEmpty()) {
                    key = "parent:" + i + ":" + library.toString();
                }

                merged.put(key, library);
            }
        }

        if (child != null) {
            for (int i = 0; i < child.length(); i++) {
                JSONObject library = child.optJSONObject(i);
                if (library == null) continue;

                String key = getLibraryMergeKey(library);
                if (key.isEmpty()) {
                    key = "child:" + i + ":" + library.toString();
                }

                JSONObject replaced = merged.put(key, library);
                if (replaced != null && !replaced.toString().equals(library.toString())) {
                    Logging.i(TAG, "Replacing inherited Maven library: "
                            + key
                            + " old="
                            + replaced.optString("name", "<unknown>")
                            + " new="
                            + library.optString("name", "<unknown>"));
                }
            }
        }

        JSONArray out = new JSONArray();
        for (JSONObject library : merged.values()) {
            out.put(library);
        }
        return out;
    }

    @NonNull
    private String getLibraryMergeKey(@NonNull JSONObject library) {
        String name = library.optString("name", "").trim();
        if (name.isEmpty()) return "";

        String[] parts = name.split(":");
        if (parts.length < 2) return name;

        String group = parts[0].trim();
        String artifact = parts[1].trim();
        if (group.isEmpty() || artifact.isEmpty()) return name;

        /*
         * De-dupe normal Maven jars by group:artifact so Fabric/Quilt child
         * libraries can replace older inherited vanilla libraries, for example
         * org.ow2.asm:asm:9.6 -> org.ow2.asm:asm:9.9.
         *
         * Do NOT collapse classifier jars into that same key. Mojang libraries
         * such as com.mojang:jtracy have both:
         *   com.mojang:jtracy:1.0.29
         *   com.mojang:jtracy:1.0.29:natives-linux
         *
         * The base jar contains com.mojang.jtracy.TracyClient. The native jar
         * does not. If both use the same merge key, the native jar can replace
         * the base jar and Minecraft crashes with NoClassDefFoundError.
         */
        if (parts.length >= 4 && !parts[3].trim().isEmpty()) {
            return group + ":" + artifact + ":" + parts[3].trim();
        }

        return group + ":" + artifact;
    }

    @NonNull
    private JSONObject mergeArguments(@Nullable JSONObject parent, @Nullable JSONObject child) throws Exception {
        if (parent == null && child == null) return new JSONObject();
        if (parent == null) return new JSONObject(child.toString());
        if (child == null) return new JSONObject(parent.toString());

        JSONObject merged = new JSONObject(parent.toString());
        JSONArray keys = child.names();
        if (keys != null) {
            for (int i = 0; i < keys.length(); i++) {
                String key = keys.getString(i);
                if ("game".equals(key) || "jvm".equals(key)) {
                    merged.put(key, mergeArrays(parent.optJSONArray(key), child.optJSONArray(key)));
                } else {
                    merged.put(key, child.get(key));
                }
            }
        }
        return merged;
    }

    private int resolveJavaMajor(@NonNull JSONObject versionJson) {
        if (BtaRendererPolicy.isBta8OrNewer(versionId, null)) {
            Logging.i(TAG, "BTA 8+ profile detected; forcing Java 17 for " + versionId);
            return 17;
        }

        int cleanroomJava = CleanroomSupport.resolveRequiredJava(versionId, versionJson);
        if (cleanroomJava > 0) {
            Logging.i(TAG, "Cleanroom profile detected; forcing Java " + cleanroomJava + " for " + versionId);
            return cleanroomJava;
        }

        JSONObject javaVersion = versionJson.optJSONObject("javaVersion");
        if (javaVersion == null) return 8;
        int major = javaVersion.optInt("majorVersion", 0);
        if (major <= 0) major = javaVersion.optInt("version", 8);
        return Math.max(8, major);
    }

    @NonNull
    private File resolveRuntime(int javaMajor) {
        return RuntimeCompat.resolveRuntimeForJava(javaMajor);
    }

    @NonNull
    private static String runtimeNameForJava(int javaMajor) {
        if (javaMajor >= 25) return "Internal-25";
        if (javaMajor >= 21) return "Internal-21";
        if (javaMajor >= 17) return "Internal-17";
        return "Internal-8";
    }

    @NonNull
    private String resolveAssetIndexName(@NonNull JSONObject versionJson) {
        JSONObject assetIndex = versionJson.optJSONObject("assetIndex");
        if (assetIndex != null) {
            String id = assetIndex.optString("id", "");
            if (!id.isEmpty()) return id;
        }
        return versionJson.optString("assets", "legacy");
    }

    @NonNull
    private File resolveGameAssetsDirectory(
            @NonNull JSONObject versionJson,
            @NonNull String assetIndexName
    ) throws Exception {
        File assetsRoot = MinecraftVersionInstaller.getAssetsDirectory();
        String legacyArguments = versionJson.optString("minecraftArguments", "");

        // Very old Mojang JSONs use ${game_assets}, not ${assets_root}.
        // The official launcher points that token at assets/virtual/<index>.
        if (legacyArguments.contains("${game_assets}")) {
            String virtualName = assetIndexName.isEmpty() ? "legacy" : assetIndexName;
            File virtualAssets = new File(assetsRoot, "virtual/" + virtualName);
            ensureVirtualAssetsIfNeeded(assetIndexName, virtualAssets);
            return virtualAssets;
        }

        return assetsRoot;
    }

    private void ensureVirtualAssetsIfNeeded(
            @NonNull String assetIndexName,
            @NonNull File virtualAssets
    ) throws Exception {
        if (!virtualAssets.exists() && !virtualAssets.mkdirs()) {
            throw new IllegalStateException("Unable to create virtual assets directory: " + virtualAssets.getAbsolutePath());
        }

        File marker = new File(virtualAssets, ".java_launcher_complete");
        if (marker.isFile()) return;

        File assetsRoot = MinecraftVersionInstaller.getAssetsDirectory();
        File indexFile = new File(assetsRoot, "indexes/" + assetIndexName + ".json");
        if (!indexFile.isFile()) {
            // Keep launching. The old client can still boot, but resources/sounds may be missing.
            Logging.i(TAG, "Legacy virtual asset index missing: " + indexFile.getAbsolutePath());
            return;
        }

        JSONObject indexJson = new JSONObject(readFile(indexFile));
        JSONObject objects = indexJson.optJSONObject("objects");
        if (objects == null) return;

        Iterator<String> keys = objects.keys();
        int copied = 0;
        while (keys.hasNext()) {
            String assetPath = keys.next();
            JSONObject object = objects.optJSONObject(assetPath);
            if (object == null) continue;

            String hash = object.optString("hash", "");
            if (hash.length() < 2) continue;

            File source = new File(assetsRoot, "objects/" + hash.substring(0, 2) + "/" + hash);
            File target = new File(virtualAssets, assetPath);
            if (!source.isFile() || target.isFile()) continue;

            copyFile(source, target);
            copied++;
        }

        try (FileOutputStream output = new FileOutputStream(marker)) {
            output.write(("copied=" + copied + "\n").getBytes(StandardCharsets.UTF_8));
        }
        Logging.i(TAG, "Prepared legacy virtual assets: " + virtualAssets.getAbsolutePath() + " copied=" + copied);
    }

    @NonNull
    private File resolveLwjglComponent(
            @NonNull String versionId,
            @NonNull JSONObject versionJson
    ) {
        if (isBetterThanAdventureVersionId(versionId) || isBetterThanAdventureVersionId(this.versionId)) {
            File btaComponentDir = new File(PathManager.DIR_FILE, "lwjgl3.3.3-bta");
            if (btaComponentDir.isDirectory()) {
                Logging.i(TAG, "Using isolated BTA LWJGL component: " + btaComponentDir.getAbsolutePath());
                return btaComponentDir;
            }
            Logging.i(TAG, "BTA LWJGL component missing; falling back to shared lwjgl3.3.3");
        }

        boolean cleanroom = CleanroomSupport.isCleanroomProfile(this.versionId, versionJson);
        String componentName = (cleanroom || shouldUseModernLwjgl(versionId)) ? "lwjgl3.4.1" : "lwjgl3.3.3";
        if (cleanroom) {
            Logging.i(TAG, "Using modern Android LWJGL component for Cleanroom: " + componentName);
        }
        File componentDir = new File(PathManager.DIR_FILE, componentName);
        if (componentDir.isDirectory()) return componentDir;

        File fallback333 = new File(PathManager.DIR_FILE, "lwjgl3.3.3");
        if (fallback333.isDirectory()) return fallback333;

        File fallbackBta = new File(PathManager.DIR_FILE, "lwjgl3.3.3-bta");
        if (fallbackBta.isDirectory()) return fallbackBta;

        File fallback341 = new File(PathManager.DIR_FILE, "lwjgl3.4.1");
        if (fallback341.isDirectory()) return fallback341;

        throw new IllegalStateException("No LWJGL component found in " + PathManager.DIR_FILE.getAbsolutePath());
    }

    private static boolean isBetterThanAdventureVersionId(@Nullable String rawVersionId) {
        if (rawVersionId == null) return false;
        String value = rawVersionId.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("bta-")
                || value.contains("betterthanadventure")
                || value.contains("better-than-adventure");
    }

    private boolean shouldUseModernLwjgl(@NonNull String rawVersion) {
        String value = rawVersion.trim().toLowerCase(Locale.ROOT);
        if (value.matches("^\\d{2}w\\d{2}[a-z].*$")) {
            return Integer.parseInt(value.substring(0, 2)) >= 26;
        }
        if (value.matches("^\\d{2}\\..*$")) {
            try {
                int major = Integer.parseInt(value.substring(0, 2));
                return major >= 26;
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
    }

    @NonNull
    private File resolveLwjglNativeDir(@NonNull File componentDir) {
        String archName = Architecture.androidAbiAsString(Architecture.getDeviceArchitecture());
        File nativeDir = new File(componentDir, "natives/" + archName);
        if (nativeDir.isDirectory()) return nativeDir;

        // Some packs only ship arm64 in the current stripped bring-up package.
        File arm64 = new File(componentDir, "natives/arm64-v8a");
        if (arm64.isDirectory()) return arm64;

        throw new IllegalStateException("Missing LWJGL natives for " + archName + " in " + componentDir.getAbsolutePath());
    }

    @NonNull
    private String buildClassPath(
            @NonNull JSONObject versionJson,
            @NonNull File clientJarFile,
            @NonNull File lwjglComponentDir,
            boolean forgeLaunch,
            boolean optiFinePresent,
            @NonNull ArrayList<String> optiFineSecureJarIgnoreNames,
            @NonNull String effectiveMinecraftVersionId,
            @NonNull File gameDirectory
    ) throws Exception {
        LinkedHashMap<String, File> jars = new LinkedHashMap<>();

        boolean enableWebRtcBridge = shouldEnableWebRtcBridgeForLaunch(versionJson, gameDirectory);
        if (enableWebRtcBridge) {
            addDroidBridgeWebRtcBridgeClasspathFirst(jars);
        } else {
            Logging.i(TAG, "DroidBridge Android WebRTC bridge not added: no WebRTC/P2P mod or library detected for this launch.");
        }

        File[] lwjglJars = lwjglComponentDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (lwjglJars != null) {
            java.util.Arrays.sort(lwjglJars, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File jar : lwjglJars) jars.put(jar.getAbsolutePath(), jar);
        }

        // De-dupe Maven libraries by group:artifact, not by absolute path.
        // Path-only de-dupe leaves both asm-9.6.jar and asm-9.9.jar on the classpath.
        LinkedHashMap<String, String> mavenJarPaths = new LinkedHashMap<>();

        JSONArray libraries = versionJson.optJSONArray("libraries");
        if (libraries != null) {
            for (int i = 0; i < libraries.length(); i++) {
                JSONObject library = libraries.optJSONObject(i);
                if (library == null || !isLibraryAllowed(library)) continue;

                String name = library.optString("name", "");
                if (isSkippedDesktopNativeLibrary(name)) continue;
                if (enableWebRtcBridge && isSkippedDesktopWebRtcNativeLibrary(name)) {
                    Logging.i(TAG, "Skipping desktop WebRTC native library on Android classpath because Android WebRTC bridge is active: " + name);
                    continue;
                }

                String artifactPath = resolveLibraryArtifactPath(library);
                if (artifactPath == null || artifactPath.isEmpty()) continue;
                if (artifactPath.endsWith(".aar")) {
                    Logging.i(TAG, "Skipping native AAR on classpath: " + artifactPath);
                    continue;
                }

                File jar = new File(MinecraftVersionInstaller.getLibrariesDirectory(), artifactPath);

                if (forgeLaunch && isForgeMinecraftClientArtifact(name, artifactPath)) {
                    if (optiFinePresent) {
                        addJarNameIfMissing(optiFineSecureJarIgnoreNames, jar);
                        Logging.i(TAG, "Keeping Minecraft client artifact on classpath for OptiFine base-resource patching and adding it to SecureJar ignoreList: " + artifactPath);
                    } else {
                        Logging.i(TAG, "Skipping Forge Minecraft client artifact on classpath: " + artifactPath);
                        continue;
                    }
                }

                if (ensureLibraryJarForLaunch(library, artifactPath, jar)) {
                    String mavenKey = getLibraryMergeKey(library);
                    if (!mavenKey.isEmpty()) {
                        String oldPath = mavenJarPaths.put(mavenKey, jar.getAbsolutePath());
                        if (oldPath != null && !oldPath.equals(jar.getAbsolutePath())) {
                            jars.remove(oldPath);
                            Logging.i(TAG, "Replacing duplicate Maven library on classpath: "
                                    + mavenKey
                                    + " old="
                                    + oldPath
                                    + " new="
                                    + jar.getAbsolutePath());
                        }
                    }

                    jars.put(jar.getAbsolutePath(), jar);
                } else {
                    Logging.i(TAG, "Skipping missing library on classpath: " + jar.getAbsolutePath());
                }
            }
        }

        addCriticalClasspathLibrariesIfNeeded(
                jars,
                mavenJarPaths,
                versionJson,
                effectiveMinecraftVersionId
        );

        if (forgeLaunch && shouldSkipInheritedMinecraftClientJarForForge(versionJson, effectiveMinecraftVersionId) && !optiFinePresent) {
            Logging.i(TAG, "Skipping vanilla/inherited Minecraft client jar on modern Forge/NeoForge classpath: "
                    + clientJarFile.getAbsolutePath());
        } else {
            if (forgeLaunch && optiFinePresent) {
                addJarNameIfMissing(optiFineSecureJarIgnoreNames, clientJarFile);
            }
            jars.put(clientJarFile.getAbsolutePath(), clientJarFile);
            if (forgeLaunch) {
                if (optiFinePresent) {
                    Logging.i(TAG, "Keeping Minecraft base-resource jar on Forge classpath for OptiFine and adding it to SecureJar ignoreList to prevent duplicate module resolution: "
                            + clientJarFile.getAbsolutePath());
                    logOptiFineBaseResourceProbe(jars, clientJarFile);
                } else {
                    Logging.i(TAG, "Keeping vanilla/inherited Minecraft client jar on legacy Forge classpath: "
                            + clientJarFile.getAbsolutePath());
                }
            }
        }

        StringBuilder builder = new StringBuilder();
        for (File jar : jars.values()) {
            if (builder.length() > 0) builder.append(':');
            builder.append(jar.getAbsolutePath());
        }
        return builder.toString();
    }


    private void addDroidBridgeWebRtcBridgeClasspathFirst(@NonNull LinkedHashMap<String, File> jars) {
        File bridgeDir = resolveDroidBridgeWebRtcBridgeDirectory();
        if (bridgeDir == null || !bridgeDir.isDirectory()) {
            Logging.i(TAG, "DroidBridge Android WebRTC bridge directory not found; desktop dev.onvoid WebRTC will remain first if present. Checked: "
                    + (bridgeDir != null ? bridgeDir.getAbsolutePath() : "<null>"));
            return;
        }

        ArrayList<File> bridgeJars = new ArrayList<>();
        File primaryBridgeJar = new File(bridgeDir, "droidbridge-devonvoid-android-webrtc-bridge.jar");
        File androidClassesJar = new File(bridgeDir, "webrtc-android-classes.jar");

        if (primaryBridgeJar.isFile()) {
            bridgeJars.add(primaryBridgeJar);
        }

        if (androidClassesJar.isFile()) {
            bridgeJars.add(androidClassesJar);
        }

        File[] extraJars = bridgeDir.listFiles((dir, name) -> name != null && name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (extraJars != null) {
            java.util.Arrays.sort(extraJars, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File jar : extraJars) {
                if (jar == null || !jar.isFile()) continue;
                if (bridgeJars.contains(jar)) continue;
                bridgeJars.add(jar);
            }
        }

        if (bridgeJars.isEmpty()) {
            Logging.i(TAG, "DroidBridge Android WebRTC bridge directory has no jars: " + bridgeDir.getAbsolutePath());
            return;
        }

        for (File jar : bridgeJars) {
            jars.put(jar.getAbsolutePath(), jar);
            Logging.i(TAG, "Prepended DroidBridge Android WebRTC classpath jar: " + jar.getAbsolutePath());
        }
    }

    @Nullable
    private File resolveDroidBridgeWebRtcBridgeDirectory() {
        File[] candidates = new File[]{
                new File(PathManager.DIR_DATA, "webrtc_bridge"),
                new File(PathManager.DIR_FILE, "webrtc_bridge"),
                new File(PathManager.DIR_DATA, "components/webrtc_bridge"),
                new File(PathManager.DIR_FILE, "components/webrtc_bridge")
        };

        for (File candidate : candidates) {
            if (candidate != null && candidate.isDirectory()) {
                return candidate;
            }
        }

        return candidates[0];
    }

    private boolean shouldEnableWebRtcBridgeForLaunch(
            @NonNull JSONObject versionJson,
            @NonNull File gameDirectory
    ) {
        if (versionJsonContainsWebRtcLibrary(versionJson)) {
            Logging.i(TAG, "DroidBridge Android WebRTC bridge enabled: version JSON contains WebRTC library.");
            return true;
        }

        if (modsDirectoryContainsWebRtcFeature(new File(gameDirectory, "mods"))) {
            Logging.i(TAG, "DroidBridge Android WebRTC bridge enabled: WebRTC/P2P mod detected in game mods directory.");
            return true;
        }

        File instanceDir = gameDirectory.getParentFile();
        if (instanceDir != null && modsDirectoryContainsWebRtcFeature(new File(instanceDir, "mods"))) {
            Logging.i(TAG, "DroidBridge Android WebRTC bridge enabled: WebRTC/P2P mod detected in instance mods directory.");
            return true;
        }

        File cursor = gameDirectory;
        for (int depth = 0; depth < 5 && cursor != null; depth++) {
            if (".minecraft".equals(cursor.getName())
                    && modsDirectoryContainsWebRtcFeature(new File(cursor, "mods"))) {
                Logging.i(TAG, "DroidBridge Android WebRTC bridge enabled: WebRTC/P2P mod detected in shared .minecraft mods directory.");
                return true;
            }
            cursor = cursor.getParentFile();
        }

        return false;
    }

    private boolean versionJsonContainsWebRtcLibrary(@NonNull JSONObject versionJson) {
        JSONArray libraries = versionJson.optJSONArray("libraries");
        if (libraries == null) return false;

        for (int i = 0; i < libraries.length(); i++) {
            JSONObject library = libraries.optJSONObject(i);
            if (library == null) continue;

            String name = library.optString("name", "").toLowerCase(Locale.ROOT);
            String artifactPath = resolveLibraryArtifactPath(library);
            String combined = name + " " + (artifactPath != null ? artifactPath.toLowerCase(Locale.ROOT) : "");

            if (combined.contains("dev.onvoid.webrtc")
                    || combined.contains("webrtc-java")
                    || combined.contains("jingle_peerconnection")
                    || combined.contains("peerconnection")) {
                return true;
            }
        }

        return false;
    }

    private boolean modsDirectoryContainsWebRtcFeature(@Nullable File modsDir) {
        if (modsDir == null || !modsDir.isDirectory()) return false;

        File[] files = modsDir.listFiles();
        if (files == null) return false;

        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".jar")) continue;

            if (name.contains("webrtc")
                    || name.contains("p2p")
                    || name.startsWith("voicechat-")
                    || name.contains("simple-voice-chat")
                    || name.contains("simplevoicechat")) {
                return true;
            }
        }

        return false;
    }

    private boolean isSkippedDesktopWebRtcNativeLibrary(@NonNull String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("dev.onvoid.webrtc:webrtc-java:")) {
            return false;
        }

        String[] parts = lower.split(":");
        if (parts.length < 4) {
            return false;
        }

        String classifier = parts[3];
        return classifier.contains("linux")
                || classifier.contains("windows")
                || classifier.contains("macos")
                || classifier.contains("osx");
    }

    private boolean shouldSkipInheritedMinecraftClientJarForForge(
            @NonNull JSONObject versionJson,
            @NonNull String effectiveMinecraftVersionId
    ) {
        return !isLegacyForgeClasspathProfile(versionJson, effectiveMinecraftVersionId);
    }

    private void addJarNameIfMissing(@NonNull ArrayList<String> jarNames, @Nullable File jar) {
        if (jar == null) return;
        String name = jar.getName();
        if (name == null || name.trim().isEmpty()) return;
        if (!jarNames.contains(name)) jarNames.add(name);
    }

    private void logOptiFineForgeCompatibility(
            @NonNull JSONObject versionJson,
            @NonNull String effectiveMinecraftVersionId,
            @NonNull File gameDirectory,
            boolean optiFinePresent
    ) {
        if (!optiFinePresent) {
            Logging.i(TAG, "OptiFine compatibility check: no enabled OptiFine jar detected for this Forge launch.");
            return;
        }

        String forgeVersion = inferForgeVersion(versionJson);
        Logging.i(TAG, "OptiFine compatibility check:"
                + " mc=" + effectiveMinecraftVersionId
                + " forge=" + (forgeVersion.isEmpty() ? "<unknown>" : forgeVersion)
                + " gameDir=" + gameDirectory.getAbsolutePath()
                + " result=detected; keeping Minecraft client base resources visible and ignored by SecureJar module scanning");
    }

    private void logOptiFineBaseResourceProbe(
            @NonNull LinkedHashMap<String, File> classpathJars,
            @NonNull File clientJarFile
    ) {
        boolean foundInClientJar = jarContainsEntry(clientJarFile, "eud.class");
        boolean foundInClasspath = foundInClientJar || classpathContainsEntry(classpathJars, "eud.class");
        Logging.i(TAG, "OptiFine base resource probe: eud.class found="
                + foundInClasspath
                + " clientJar=" + clientJarFile.getAbsolutePath()
                + " clientJarContains=" + foundInClientJar);
    }

    private boolean classpathContainsEntry(
            @NonNull LinkedHashMap<String, File> classpathJars,
            @NonNull String entryName
    ) {
        for (File jar : classpathJars.values()) {
            if (jarContainsEntry(jar, entryName)) return true;
        }
        return false;
    }

    private boolean jarContainsEntry(@Nullable File jarFile, @NonNull String entryName) {
        if (jarFile == null || !jarFile.isFile()) return false;
        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(jarFile)) {
            return zipFile.getEntry(entryName) != null;
        } catch (Throwable throwable) {
            Logging.i(TAG, "OptiFine base resource probe failed for " + jarFile.getAbsolutePath() + ": " + throwable);
            return false;
        }
    }

    private boolean hasEnabledOptiFineModJar(@NonNull File gameDirectory) {
        for (File modsDir : getModSearchDirectories(gameDirectory)) {
            File optiFine = findEnabledOptiFineJar(modsDir);
            if (optiFine != null) {
                Logging.i(TAG, "Detected OptiFine jar for Forge launch: " + optiFine.getAbsolutePath());
                return true;
            }
        }
        return false;
    }

    @NonNull
    private ArrayList<File> getModSearchDirectories(@NonNull File gameDirectory) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        ArrayList<File> dirs = new ArrayList<>();

        addModSearchDirectory(dirs, seen, new File(gameDirectory, "mods"));
        File parent = gameDirectory.getParentFile();
        if (parent != null) addModSearchDirectory(dirs, seen, new File(parent, "mods"));
        addModSearchDirectory(dirs, seen, new File(PathManager.DIR_MINECRAFT_HOME, "mods"));

        return dirs;
    }

    private void addModSearchDirectory(
            @NonNull ArrayList<File> dirs,
            @NonNull LinkedHashSet<String> seen,
            @Nullable File dir
    ) {
        if (dir == null) return;
        String path = dir.getAbsolutePath();
        if (seen.add(path)) dirs.add(dir);
    }

    @Nullable
    private File findEnabledOptiFineJar(@Nullable File modsDir) {
        if (modsDir == null || !modsDir.isDirectory()) return null;
        File[] files = modsDir.listFiles();
        if (files == null) return null;
        java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            String lower = file.getName().toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".jar")) continue;
            if (!lower.contains("optifine")) continue;
            if (lower.endsWith(".disabled") || lower.contains(".jar.disabled")) continue;
            return file;
        }
        return null;
    }

    @NonNull
    private String inferForgeVersion(@NonNull JSONObject versionJson) {
        JSONArray libraries = versionJson.optJSONArray("libraries");
        if (libraries == null) return "";

        for (int i = 0; i < libraries.length(); i++) {
            JSONObject library = libraries.optJSONObject(i);
            if (library == null) continue;
            String name = library.optString("name", "");
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("net.minecraftforge:forge:")) continue;

            String[] parts = name.split(":");
            if (parts.length >= 3) {
                String version = parts[2];
                int dash = version.lastIndexOf('-');
                return dash >= 0 && dash + 1 < version.length() ? version.substring(dash + 1) : version;
            }
        }

        return "";
    }


    private boolean isLegacyForgeClasspathProfile(
            @NonNull JSONObject versionJson,
            @NonNull String effectiveMinecraftVersionId
    ) {
        String combined = (versionId + " "
                + versionJson.optString("id", "") + " "
                + versionJson.optString("mainClass", "") + " "
                + versionJson.optString("inheritsFrom", "") + " "
                + effectiveMinecraftVersionId).toLowerCase(Locale.ROOT);

        if (combined.contains("neoforge") || combined.contains("net.neoforged")) {
            return false;
        }

        if (combined.contains("launchwrapper") || combined.contains("fmltweaker")) {
            return true;
        }

        int[] parsed = parseReleaseVersion(effectiveMinecraftVersionId);
        if (parsed == null) return false;

        int major = parsed[0];
        int minor = parsed[1];
        return major == 1 && minor <= 16;
    }

    private boolean isForgeMinecraftClientArtifact(@NonNull String name, @NonNull String artifactPath) {
        String lowerName = name.toLowerCase(Locale.ROOT);
        String lowerPath = artifactPath.replace('\\', '/').toLowerCase(Locale.ROOT);

        return lowerName.startsWith("net.minecraft:client:")
                || lowerPath.contains("/net/minecraft/client/");
    }

    private boolean isSkippedDesktopNativeLibrary(@NonNull String name) {
        return name.contains("org.lwjgl")
                || name.contains("jinput-platform")
                || name.contains("lwjgl-platform")
                || name.contains("twitch-platform");
    }

    private boolean isLibraryAllowed(@NonNull JSONObject library) {
        JSONArray rules = library.optJSONArray("rules");
        if (rules == null || rules.length() == 0) return true;

        boolean allowed = false;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule == null) continue;

            String action = rule.optString("action", "allow");
            JSONObject os = rule.optJSONObject("os");
            boolean appliesToLinux = os == null || "linux".equals(os.optString("name", "linux"));

            if (!appliesToLinux) continue;
            allowed = "allow".equals(action);
        }
        return allowed;
    }

    @Nullable
    private String resolveLibraryArtifactPath(@NonNull JSONObject library) {
        JSONObject downloads = library.optJSONObject("downloads");
        if (downloads != null) {
            JSONObject artifact = downloads.optJSONObject("artifact");
            if (artifact != null) {
                String path = artifact.optString("path", "");
                if (!path.isEmpty()) return path;
            }
        }
        return artifactToPath(library.optString("name", ""));
    }

    private void addCriticalClasspathLibrariesIfNeeded(
            @NonNull LinkedHashMap<String, File> jars,
            @NonNull LinkedHashMap<String, String> mavenJarPaths,
            @NonNull JSONObject versionJson,
            @NonNull String effectiveMinecraftVersionId
    ) throws Exception {
        if (!requiresJtracyLibrary(effectiveMinecraftVersionId)) return;

        final String jtracyKey = "com.mojang:jtracy";
        String resolvedPath = mavenJarPaths.get(jtracyKey);
        if (resolvedPath != null && !resolvedPath.isEmpty()) {
            Logging.i(TAG, "Preserving Minecraft-declared jtracy on classpath: " + resolvedPath);
            return;
        }

        /*
         * Never replace a Minecraft-declared jtracy with the historical 1.0.29
         * fallback. Snapshot 9 calls newer TracyClient APIs, so downgrading the
         * jar here causes a startup NoSuchMethodError before LWJGL/SDL starts.
         * If the first classpath pass could not add it (for example, a transient
         * download failure), retry the exact declaration from version metadata.
         */
        JSONObject declaredJtracy = findMavenLibrary(
                versionJson.optJSONArray("libraries"),
                "com.mojang",
                "jtracy"
        );
        if (declaredJtracy != null) {
            addMavenLibraryToClasspath(
                    jars,
                    mavenJarPaths,
                    declaredJtracy,
                    "critical Minecraft library retry"
            );
            return;
        }

        // Last-resort compatibility for older malformed/flattened profiles.
        addMavenLibraryToClasspath(
                jars,
                mavenJarPaths,
                createMavenLibrary("com.mojang:jtracy:1.0.29"),
                "legacy critical Minecraft library fallback"
        );
    }

    private void addMavenLibraryToClasspath(
            @NonNull LinkedHashMap<String, File> jars,
            @NonNull LinkedHashMap<String, String> mavenJarPaths,
            @NonNull JSONObject library,
            @NonNull String reason
    ) {
        String artifactPath = resolveLibraryArtifactPath(library);
        if (artifactPath == null || artifactPath.isEmpty()) return;

        File jar = new File(MinecraftVersionInstaller.getLibrariesDirectory(), artifactPath);
        if (!ensureLibraryJarForLaunch(library, artifactPath, jar)) {
            Logging.i(TAG, "Unable to add " + reason + ": " + library.optString("name")
                    + " path=" + jar.getAbsolutePath());
            return;
        }

        String mavenKey = getLibraryMergeKey(library);
        if (!mavenKey.isEmpty()) {
            String oldPath = mavenJarPaths.put(mavenKey, jar.getAbsolutePath());
            if (oldPath != null && !oldPath.equals(jar.getAbsolutePath())) {
                jars.remove(oldPath);
                Logging.i(TAG, "Replacing duplicate Maven library on classpath: "
                        + mavenKey
                        + " old="
                        + oldPath
                        + " new="
                        + jar.getAbsolutePath());
            }
        }

        jars.put(jar.getAbsolutePath(), jar);
        Logging.i(TAG, "Added " + reason + " to classpath: " + library.optString("name")
                + " -> " + jar.getAbsolutePath());
    }

    private boolean ensureLibraryJarForLaunch(
            @NonNull JSONObject library,
            @NonNull String artifactPath,
            @NonNull File jar
    ) {
        if (jar.isFile()) return true;

        String name = library.optString("name", "");
        String url = "";
        String expectedSha1 = "";

        JSONObject downloads = library.optJSONObject("downloads");
        if (downloads != null) {
            JSONObject artifact = downloads.optJSONObject("artifact");
            if (artifact != null) {
                url = artifact.optString("url", "").trim();
                expectedSha1 = artifact.optString("sha1", "").trim().toLowerCase(Locale.ROOT);
            }
        }

        if (url.isEmpty()) {
            url = "https://libraries.minecraft.net/" + artifactPath;
        }

        Logging.i(TAG, "Library missing before launch, attempting download: "
                + name
                + " path="
                + jar.getAbsolutePath());

        try {
            downloadLibraryJar(url, jar, expectedSha1);

            if (!expectedSha1.isEmpty()) {
                String actualSha1 = sha1(jar);
                if (!expectedSha1.equals(actualSha1)) {
                    // Keep the launcher from booting with a corrupt/partial dependency.
                    // The next install/launch can retry the download cleanly.
                    //noinspection ResultOfMethodCallIgnored
                    jar.delete();
                    Logging.i(TAG, "Downloaded library SHA-1 mismatch for "
                            + name
                            + " expected="
                            + expectedSha1
                            + " actual="
                            + actualSha1);
                    return false;
                }
            }

            Logging.i(TAG, "Downloaded missing launch library: " + jar.getAbsolutePath());
            return jar.isFile();
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to download missing launch library: " + name + " from " + url, throwable);
            return false;
        }
    }

    private void downloadLibraryJar(
            @NonNull String urlString,
            @NonNull File target,
            @NonNull String expectedSha1
    ) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Unable to create library directory: " + parent.getAbsolutePath());
        }

        File temp = new File(target.getAbsolutePath() + ".download");
        Exception lastError = null;
        for (String candidateUrl : MinecraftDownloadSource.getCandidateUrls(context, urlString)) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(candidateUrl).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "JavaLauncher/1.0");

                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("HTTP " + code + " while downloading " + candidateUrl);
                }

                try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream output = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        output.write(buffer, 0, read);
                    }
                }

                if (!expectedSha1.isEmpty()) {
                    String actualSha1 = sha1(temp);
                    if (!expectedSha1.equals(actualSha1)) {
                        throw new IllegalStateException("SHA-1 mismatch from " + candidateUrl);
                    }
                }

                if (target.exists() && !target.delete()) {
                    throw new IllegalStateException("Unable to replace library: " + target.getAbsolutePath());
                }
                if (!temp.renameTo(target)) {
                    copyFile(temp, target);
                    //noinspection ResultOfMethodCallIgnored
                    temp.delete();
                }
                return;
            } catch (Exception error) {
                lastError = error;
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                Logging.i(TAG, "Library download source failed for " + candidateUrl
                        + ": " + error.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
        }

        if (lastError != null) throw lastError;
        throw new IllegalStateException("No download source available for " + urlString);
    }

    @NonNull
    private String sha1(@NonNull File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }

        byte[] hash = digest.digest();
        StringBuilder out = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return out.toString();
    }

    @Nullable
    private String artifactToPath(@NonNull String name) {
        String[] parts = name.split(":");
        if (parts.length < 3) return null;
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length > 3 ? "-" + parts[3] : "";
        return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + ".jar";
    }

    @NonNull
    private Map<String, String> buildReplacements(
            @NonNull JSONObject versionJson,
            @NonNull String assetIndexName,
            @NonNull File gameAssetsDir,
            @NonNull String classPath,
            @NonNull File lwjglNativesDir,
            @NonNull File gameDirectory
    ) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        int launchWidth = resolveLaunchWidth();
        int launchHeight = resolveLaunchHeight();

        String playerName = resolvePlayerName();
        boolean hasMinecraftSession = hasValidMinecraftSession();
        if (!hasMinecraftSession && !LauncherSecurity.allowsOfflineProfileAuth()) {
            throw new SecurityException("A valid Microsoft/Minecraft account is required to launch Minecraft.");
        }

        boolean customSkinEnabled = !hasMinecraftSession && isCustomSkinEnabledForLaunch(playerName);

        OfflineSkinProfile customSkinProfile = customSkinEnabled
                ? resolveOfflineSkinProfile(playerName)
                : null;

        String authUuid;
        if (customSkinProfile != null && customSkinProfile.enabled) {
            authUuid = stripUuidDashes(customSkinProfile.uniqueUuid);
        } else if (hasMinecraftSession) {
            authUuid = stripUuidDashes(account.minecraftUuid);
        } else {
            authUuid = createOfflineUuid(playerName);
        }

        String accessToken = hasMinecraftSession ? account.minecraftAccessToken : "0";
        String xuid = hasMinecraftSession && !isNullOrBlank(account.xuid) ? account.xuid : "";
        String userType = hasMinecraftSession ? "msa" : "legacy";

        values.put("${auth_player_name}", playerName);
        values.put("${version_name}", versionId);
        values.put("${game_directory}", gameDirectory.getAbsolutePath());
        values.put("${assets_root}", MinecraftVersionInstaller.getAssetsDirectory().getAbsolutePath());
        values.put("${game_assets}", gameAssetsDir.getAbsolutePath());
        values.put("${assets_index_name}", assetIndexName);
        values.put("${auth_uuid}", authUuid);
        values.put("${auth_access_token}", accessToken);
        values.put("${auth_session}", accessToken);
        values.put("${clientid}", "0");
        values.put("${client_id}", "0");
        values.put("${auth_xuid}", xuid);
        values.put("${user_type}", userType);
        values.put("${user_properties}", "{}");
        values.put("${user_property_map}", "{}");
        values.put("${profile_properties}", "{}");
        values.put("${quickPlayPath}", "");
        values.put("${quickPlaySingleplayer}", "");
        values.put("${quickPlayMultiplayer}", "");
        values.put("${quickPlayRealms}", "");
        values.put("${version_type}", versionJson.optString("type", "release"));
        values.put("${resolution_width}", String.valueOf(launchWidth));
        values.put("${resolution_height}", String.valueOf(launchHeight));
        values.put("${natives_directory}", lwjglNativesDir.getAbsolutePath());
        values.put("${launcher_name}", "JavaLauncher");
        values.put("${launcher_version}", "1.0");
        values.put("${library_directory}", MinecraftVersionInstaller.getLibrariesDirectory().getAbsolutePath());
        values.put("${classpath_separator}", ":");
        values.put("${classpath}", classPath);

        Logging.i(TAG, "Launch account mode=" + (customSkinEnabled ? "custom_skin" : (hasMinecraftSession ? "microsoft" : "offline"))
                + " player=" + playerName
                + " uuid=" + authUuid
                + " userType=" + userType
                + " accountLoaded=" + (account != null));

        return values;
    }

    @NonNull
    private String resolvePlayerName() {
        if (account != null) {
            if (!isNullOrBlank(account.minecraftName)) {
                return sanitizePlayerName(account.minecraftName);
            }
            if (!isNullOrBlank(account.displayName)) {
                return sanitizePlayerName(account.displayName);
            }
            if (!isNullOrBlank(account.email)) {
                String local = account.email.split("@")[0];
                return sanitizePlayerName(local);
            }
        }
        return "Player";
    }

    private boolean hasValidMinecraftSession() {
        return account != null
                && account.isMicrosoftAccount()
                && !isNullOrBlank(account.minecraftAccessToken)
                && !isNullOrBlank(account.minecraftName)
                && !isNullOrBlank(account.minecraftUuid);
    }

    private static boolean isNullOrBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    @NonNull
    private static String stripUuidDashes(@NonNull String uuid) {
        return uuid.replace("-", "").trim();
    }

    @NonNull
    private String sanitizePlayerName(@NonNull String raw) {
        String cleaned = raw.replaceAll("[^A-Za-z0-9_]", "");
        if (cleaned.isEmpty()) return "Player";
        if (cleaned.length() > 16) return cleaned.substring(0, 16);
        return cleaned;
    }

    @NonNull
    private String createOfflineUuid(@NonNull String playerName) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
    }

    public static void stopActiveOfflineSkinServer() {
        OfflineYggdrasilServer server = activeOfflineSkinServer;
        activeOfflineSkinServer = null;
        if (server != null) {
            try {
                server.stop();
            } catch (Throwable ignored) {
            }
        }
    }


    public static void stopActiveLegacySkinProxyServer() {
        LegacySkinProxyServer server = activeLegacySkinProxyServer;
        activeLegacySkinProxyServer = null;
        if (server != null) {
            try {
                server.stop();
            } catch (Throwable ignored) {
            }
        }
    }

    private static int getActiveLegacySkinProxyPort() {
        LegacySkinProxyServer server = activeLegacySkinProxyServer;
        if (server == null) return -1;
        try {
            return server.getPort();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private void addLegacySkinProxyIfNeeded(
            @NonNull ArrayList<String> args,
            @NonNull JSONObject versionJson
    ) {
        stopActiveLegacySkinProxyServer();

        // BTA 8.x still resolves modern texture hashes through a plain-HTTP
        // textures.minecraft.net URL. Android/JDK networking can leave that request
        // unusable even though the same HTTPS endpoint works. Reuse the existing
        // localhost HTTP proxy as a transport bridge for BTA: the proxy upgrades only
        // textures.minecraft.net requests to HTTPS and forwards everything else
        // normally. BTA's positional username/session arguments remain untouched.
        boolean btaSkinHttpBridge = isBetterThanAdventureProfile(versionJson);
        boolean legacySkinProxy = shouldUseLegacySkinProxy(versionJson);
        if (!btaSkinHttpBridge && !legacySkinProxy) {
            return;
        }

        String playerName = resolvePlayerName();
        File skinFile = resolveLegacySkinFileForLaunch(playerName);
        if (!btaSkinHttpBridge && (skinFile == null || !skinFile.isFile())) {
            Logging.i(TAG, "Legacy skin proxy skipped: no cached/selected skin available for " + playerName);
            return;
        }

        try {
            LegacySkinProxyServer server = new LegacySkinProxyServer();
            // Keep the old pre-Yggdrasil behavior exactly as before. For BTA this is
            // only an optional local fallback entry; its modern /texture/<hash> request
            // is forwarded after the HTTP -> HTTPS upgrade.
            if (skinFile != null && skinFile.isFile()) {
                server.addSkin(playerName, skinFile);
                if (btaSkinHttpBridge) {
                    // BTA may replace the authenticated account name with Player###
                    // and request that transient profile's texture hash. Serve the
                    // authenticated launch account skin for any BTA texture hash so
                    // the in-game model always matches the selected Microsoft account.
                    server.setModernTextureOverride(skinFile);
                }
            }
            server.start();

            int port = server.getPort();
            if (port <= 0) {
                server.stop();
                Logging.i(TAG, "Legacy/BTA skin proxy did not expose a valid port.");
                return;
            }

            activeLegacySkinProxyServer = server;
            addJvmArgIfMissing(args, "-Djava.net.useSystemProxies=false");
            addJvmArgIfMissing(args, "-Dhttp.proxyHost=127.0.0.1");
            addJvmArgIfMissing(args, "-Dhttp.proxyPort=" + port);
            addJvmArgIfMissing(args, "-Dhttp.nonProxyHosts=127.*|localhost|::1");

            if (btaSkinHttpBridge) {
                Logging.i(TAG, "BTA skin HTTP bridge active on port " + port
                        + " for launch account " + playerName
                        + "; textures.minecraft.net HTTP requests will be upgraded to HTTPS");
            } else {
                Logging.i(TAG, "Legacy skin proxy active on port " + port
                        + " for " + playerName
                        + " skin=" + skinFile.getAbsolutePath());
            }
        } catch (Throwable throwable) {
            stopActiveLegacySkinProxyServer();
            Logging.e(TAG, "Failed to start legacy/BTA skin proxy", throwable);
        }
    }

    @Nullable
    private File resolveLegacySkinFileForLaunch(@NonNull String playerName) {
        OfflineSkinProfile offlineProfile = resolveOfflineSkinProfile(playerName);
        if (offlineProfile != null
                && offlineProfile.enabled
                && offlineProfile.skinFile != null
                && offlineProfile.skinFile.isFile()) {
            return offlineProfile.skinFile;
        }

        if (!hasValidMinecraftSession()) {
            return null;
        }

        File cached = AccountSkinCache.getCachedSkinFileIfPresent(context, account);
        if (cached != null && cached.isFile()) {
            return cached;
        }

        try {
            if (AccountSkinCache.cacheMicrosoftSkin(context, account)) {
                cached = AccountSkinCache.getCachedSkinFileIfPresent(context, account);
                if (cached != null && cached.isFile()) {
                    return cached;
                }
            }
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to refresh Microsoft skin cache for legacy skin proxy: " + throwable.getMessage());
        }

        return null;
    }

    private boolean shouldUseLegacySkinProxy(@NonNull JSONObject versionJson) {
        String combined = (versionId + " "
                + versionJson.optString("id", "") + " "
                + versionJson.optString("inheritsFrom", "") + " "
                + versionJson.optString("mainClass", "")).toLowerCase(Locale.ROOT);
        if (combined.contains("bta") || combined.contains("betterthanadventure") || combined.contains("better than adventure")) {
            return false;
        }

        String minecraftVersion = resolveMinecraftVersionIdForLegacySkin(versionJson);
        if (minecraftVersion.isEmpty()) return false;

        int[] parsed = parseReleaseVersion(minecraftVersion);
        if (parsed == null) return false;

        int major = parsed[0];
        int minor = parsed[1];
        int patch = parsed[2];

        // Legacy skin endpoint era. These versions request:
        //   http://skins.minecraft.net/MinecraftSkins/<player>.png
        // That host no longer resolves reliably, so DroidBridge serves the cached
        // Microsoft/offline skin through a local HTTP proxy instead.
        if (major < 1) return true;
        if (major > 1) return false;
        if (minor < 7) return true;
        return minor == 7 && patch <= 5;
    }

    @NonNull
    private String resolveMinecraftVersionIdForLegacySkin(@NonNull JSONObject versionJson) {
        String[] candidates = new String[]{
                versionJson.optString("javaLauncherFlattenedParent", ""),
                versionJson.optString("inheritsFrom", ""),
                versionId,
                versionJson.optString("id", "")
        };

        for (String candidate : candidates) {
            String version = extractMinecraftVersionFromProfileId(candidate);
            if (!version.isEmpty()) return version;
        }

        return "";
    }

    @NonNull
    private String extractMinecraftVersionFromProfileId(@Nullable String value) {
        if (value == null) return "";
        String id = value.trim();
        if (id.isEmpty()) return "";

        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.startsWith("fabric-loader-")
                || lower.startsWith("legacyfabric-loader-")
                || lower.contains("-loader-")) {
            int lastDash = id.lastIndexOf('-');
            if (lastDash >= 0 && lastDash + 1 < id.length()) {
                String tail = id.substring(lastDash + 1).trim();
                if (parseReleaseVersion(tail) != null) return tail;
            }
        }

        Matcher matcher = Pattern.compile("(?i)(?:^|[^0-9])([0-9]+\\.[0-9]+(?:\\.[0-9]+)?)(?:$|[^0-9])").matcher(id);
        if (matcher.find()) return matcher.group(1);
        return "";
    }

    @Nullable
    private OfflineSkinProfile resolveOfflineSkinProfile(@NonNull String playerName) {
        if (account != null && account.isOfflineAccount() && !isNullOrBlank(account.offlineSkinPath)) {
            File skinFile = new File(account.offlineSkinPath);
            if (skinFile.isFile()) {
                SkinModelType model = SkinModelType.fromId(account.offlineSkinModel);
                String uuid = !isNullOrBlank(account.minecraftUuid)
                        ? account.minecraftUuid
                        : CustomSkinStore.getOfflineUuidWithSkinModel(playerName, model);
                return new OfflineSkinProfile(uuid, skinFile, model, true);
            }
        }

        // Migration compatibility for the previous single global custom-skin setting.
        if (account != null && account.isOfflineAccount()) {
            try {
                OfflineSkinProfile profile = new CustomSkinStore(context).buildOfflineProfile(playerName);
                if (profile.enabled && profile.skinFile != null && profile.skinFile.isFile()) return profile;
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to check legacy custom skin profile", throwable);
            }
        }

        return null;
    }

    private boolean isCustomSkinEnabledForLaunch(@NonNull String playerName) {
        OfflineSkinProfile profile = resolveOfflineSkinProfile(playerName);
        return profile != null && profile.enabled && profile.skinFile != null && profile.skinFile.isFile();
    }

    private void addCustomSkinAuthlibInjectorIfNeeded(@NonNull ArrayList<String> args) {
        String playerName = resolvePlayerName();
        OfflineSkinProfile profile = resolveOfflineSkinProfile(playerName);
        if (profile == null || !profile.enabled || profile.skinFile == null || !profile.skinFile.isFile()) {
            stopActiveOfflineSkinServer();
            return;
        }

        File authlibInjector = resolveAuthlibInjectorJar();
        if (authlibInjector == null || !authlibInjector.isFile()) {
            Logging.i(TAG, "Offline skin selected, but authlib-injector.jar was not found. Skin will only show in launcher UI.");
            safeWriteSkinLaunchNote("Offline skin selected, but authlib-injector.jar was not found.\n");
            stopActiveOfflineSkinServer();
            return;
        }

        try {
            stopActiveOfflineSkinServer();
            OfflineYggdrasilServer server = new OfflineYggdrasilServer("JavaLauncher_Offline", "JavaLauncher", "1.0");
            server.start();
            int port = server.getPort();
            if (port <= 0) {
                server.stop();
                Logging.i(TAG, "Offline skin server did not expose a valid port.");
                return;
            }

            server.addCharacter(stripUuidDashes(profile.uniqueUuid), playerName, profile.skinFile, profile.model);
            activeOfflineSkinServer = server;

            args.add("-javaagent:" + authlibInjector.getAbsolutePath() + "=http://127.0.0.1:" + port + "/");
            args.add("-Dauthlibinjector.side=client");
            Logging.i(TAG, "Using OfflineYggdrasilServer on port " + port + " for offline skin user " + playerName);
        } catch (Throwable throwable) {
            stopActiveOfflineSkinServer();
            Logging.e(TAG, "Failed to prepare custom skin authlib injector", throwable);
        }
    }

    private File resolveAuthlibInjectorJar() {
        File fromLibPath = readLibPathFileField("AUTHLIB_INJECTOR");
        if (fromLibPath != null && fromLibPath.isFile()) return fromLibPath;

        File[] candidates = new File[]{
                new File(PathManager.DIR_FILE, "authlib-injector.jar"),
                new File(PathManager.DIR_FILE, "authlib-injector/authlib-injector.jar"),
                new File(PathManager.DIR_FILE, "components/authlib-injector.jar"),
                new File(PathManager.DIR_FILE, "components/authlib-injector/authlib-injector.jar"),
                new File(PathManager.DIR_FILE, "authlib_injector.jar"),
                new File(PathManager.DIR_DATA, "authlib-injector.jar")
        };

        for (File candidate : candidates) {
            if (candidate.isFile()) return candidate;
        }
        return null;
    }

    @Nullable
    private File readLibPathFileField(@NonNull String fieldName) {
        try {
            Field field = LibPath.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof File) return (File) value;
            if (value instanceof String && !((String) value).trim().isEmpty()) {
                return new File((String) value);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void safeWriteSkinLaunchNote(@NonNull String text) {
        try {
            File logFile = new File(PathManager.DIR_LAUNCHER_LOG, "latestlog.txt");
            File parent = logFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream output = new FileOutputStream(logFile, true)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean shouldEnableLegacyAwt(@NonNull JSONObject versionJson) {
        String id = versionJson.optString("id", versionId);
        if (id == null || id.trim().isEmpty()) id = versionId;
        id = id.trim().toLowerCase(Locale.ROOT);

        String combined = (versionId + " "
                + versionJson.optString("id", "") + " "
                + versionJson.optString("mainClass", "") + " "
                + versionJson.optString("inheritsFrom", "") + " "
                + versionJson.optString("minecraftArguments", "")).toLowerCase(Locale.ROOT);

        if (combined.contains("neoforge") || combined.contains("net.neoforged")) {
            return false;
        }

        if (combined.contains("net.minecraft.launchwrapper.launch")
                || combined.contains("launchwrapper")
                || combined.contains("fmltweaker")) {
            return true;
        }

        if (id.startsWith("rd-")
                || id.startsWith("classic")
                || id.startsWith("infdev")
                || id.startsWith("indev")
                || id.startsWith("a")
                || id.startsWith("b")) {
            return true;
        }

        int[] parsed = parseReleaseVersion(id);
        if (parsed == null) return false;

        int major = parsed[0];
        int minor = parsed[1];
        return major == 1 && minor >= 0 && minor <= 12;
    }

    private boolean shouldEnableControlifySharedSdl263Compat(@NonNull JSONObject versionJson) {
        // Controlify 26.3 needs a shared-SDL compatibility gate. Loader instance IDs
        // such as fabric-loader-0.19.5-26.3 are not parsed correctly by the generic
        // release parser because it sees the loader version (0.19.5) first. Resolve
        // the Minecraft tail explicitly without changing behavior for any non-Controlify
        // launch or older Minecraft version.
        if (shouldEnableSdl3SingleWindowJavaBindingReuse(versionJson)) {
            return true;
        }

        String[] candidates = new String[]{
                versionJson.optString("javaLauncherFlattenedParent", ""),
                versionJson.optString("inheritsFrom", ""),
                versionJson.optString("id", ""),
                versionId
        };
        for (String candidate : candidates) {
            if (candidate == null) continue;
            String value = candidate.trim();
            if (value.isEmpty()) continue;

            String minecraftId = extractMinecraftIdFromLoaderVersion(value);
            int[] parsed = parseReleaseVersion(minecraftId.toLowerCase(Locale.ROOT));
            if (parsed == null) continue;
            if (parsed[0] > 26 || (parsed[0] == 26 && parsed[1] >= 3)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldEnableSdl3SingleWindowJavaBindingReuse(@NonNull JSONObject versionJson) {
        String combined = (versionId + " "
                + versionJson.optString("id", "") + " "
                + versionJson.optString("inheritsFrom", "") + " "
                + versionJson.optString("javaLauncherFlattenedParent", "")).toLowerCase(Locale.ROOT)
                .replace('_', '-');
        while (combined.contains("--")) combined = combined.replace("--", "-");

        Matcher snapshot = Pattern.compile("(?i)([0-9]+)\\.([0-9]+)-snapshot-([0-9]+)").matcher(combined);
        while (snapshot.find()) {
            try {
                int major = Integer.parseInt(snapshot.group(1));
                int minor = Integer.parseInt(snapshot.group(2));
                int number = Integer.parseInt(snapshot.group(3));
                if (major > 26) return true;
                if (major == 26 && minor > 3) return true;
                if (major == 26 && minor == 3 && number >= 9) return true;
            } catch (NumberFormatException ignored) {
            }
        }

        String[] releaseCandidates = new String[]{
                versionJson.optString("javaLauncherFlattenedParent", ""),
                versionJson.optString("inheritsFrom", ""),
                versionJson.optString("id", ""),
                versionId
        };
        for (String candidate : releaseCandidates) {
            if (candidate == null) continue;
            String value = candidate.trim().toLowerCase(Locale.ROOT);
            if (value.isEmpty() || value.contains("snapshot")) continue;
            int[] parsed = parseReleaseVersion(value);
            if (parsed == null) continue;
            if (parsed[0] > 26 || (parsed[0] == 26 && parsed[1] >= 3)) return true;
        }
        return false;
    }

    @NonNull
    private ArrayList<String> buildJvmArgs(
            @NonNull File runtimeDir,
            @NonNull File lwjglNativesDir,
            @NonNull String classPath,
            @NonNull Map<String, String> replacements,
            @NonNull JSONObject versionJson,
            @NonNull File gameDirectory,
            @Nullable RendererInterface renderer,
            boolean optiFinePresent,
            @NonNull ArrayList<String> optiFineSecureJarIgnoreNames
    ) throws Exception {
        ArrayList<String> args = new ArrayList<>();

        addLegacySkinProxyIfNeeded(args, versionJson);
        addCustomSkinAuthlibInjectorIfNeeded(args);

        int launchWidth = resolveLaunchWidth();
        int launchHeight = resolveLaunchHeight();
        boolean needsLegacyAwt = shouldEnableLegacyAwt(versionJson);
        int glfwWindowWidth = needsLegacyAwt ? resolveBridgeWindowWidth(launchWidth) : launchWidth;
        int glfwWindowHeight = needsLegacyAwt ? resolveBridgeWindowHeight(launchHeight) : launchHeight;
        if (needsLegacyAwt && glfwWindowWidth < glfwWindowHeight) {
            int tmp = glfwWindowWidth;
            glfwWindowWidth = glfwWindowHeight;
            glfwWindowHeight = tmp;
        }

        if (needsLegacyAwt) {
            args.addAll(buildCacioJvmArgs(runtimeDir, glfwWindowWidth, glfwWindowHeight));
            Logging.i(TAG, "Enabled legacy LWJGL2/AWT sizing: requested="
                    + width + "x" + height
                    + " scale=" + getResolutionScalePercentSafe() + "%"
                    + " cacio=" + glfwWindowWidth + "x" + glfwWindowHeight
                    + " glfwstub=" + glfwWindowWidth + "x" + glfwWindowHeight);
        }

        addMojangJvmArguments(args, versionJson, replacements);
        addLegacyBetaJvmArgs(args, versionJson);
        addBetterThanAdventureJvmArgsIfNeeded(args, versionJson);

        removeClasspathArgs(args);

        purgeManagedArgs(args);
        addForgeModuleWorkaroundsIfNeeded(args, runtimeDir, versionJson);
        addOptiFineSecureJarIgnoreListIfNeeded(args, optiFinePresent, optiFineSecureJarIgnoreNames);
        FFmpegPluginCompat.Result replayFfmpeg = FFmpegPluginCompat.discoverForReplayMod(context, gameDirectory);
        boolean legacyForgeRuntime = isLegacyForgeRuntimeProfile(runtimeDir, versionJson);

        // Verity 6+ bundles its offline Sherpa/ONNX stack differently from 5.x.
        // Extract any Android ARM64 JNI libraries before java.library.path is finalized.
        VerityNativeCompat.prepare(context, gameDirectory);

        String jnaNativePath = buildJnaNativePath();
        String jnaLibraryPath = ControllerModCompat.buildJnaLibraryPath(context, gameDirectory, jnaNativePath);
        boolean controllablePresent = ControllerModCompat.hasControllable(gameDirectory);
        boolean controlifyPresent = ControlifySDL.hasControlify(gameDirectory);
        String nativeLibraryPath = buildNativeLibraryPath(runtimeDir, lwjglNativesDir, jnaLibraryPath, renderer, replayFfmpeg);
        String bootLibraryPath = buildBootLibraryPath(runtimeDir);
        String resolvFile = prepareAndroidResolvConfForRuntime();

        args.add("-Djava.home=" + runtimeDir.getAbsolutePath());
        args.add("-Djava.io.tmpdir=" + PathManager.DIR_CACHE.getAbsolutePath());
        args.add("-Duser.home=" + (legacyForgeRuntime
                ? resolveLegacyLauncherHome(gameDirectory)
                : gameDirectory.getAbsolutePath()));
        args.add("-Duser.language=" + Locale.getDefault().getLanguage());
        args.add("-Duser.timezone=" + java.util.TimeZone.getDefault().getID());
        args.add("-Dos.name=Linux");
        args.add("-Dos.version=Android-" + Build.VERSION.RELEASE);
        args.add("-Ddroidbridge.path.minecraft=" + (legacyForgeRuntime
                ? new File(PathManager.DIR_MINECRAFT_HOME).getAbsolutePath()
                : gameDirectory.getAbsolutePath()));
        args.add("-Ddroidbridge.dualscreen.state="
                + new File(gameDirectory, "droidbridge_dual_screen_state.json").getAbsolutePath());
        args.add("-Ddroidbridge.dualscreen.control="
                + new File(gameDirectory, "droidbridge_dual_screen_control.json").getAbsolutePath());
        // Event-driven networkless exact-icon mailbox.
        //
        // Keep the proven v9 transport for every supported Minecraft version, including 26.2.
        // The 26.2 renderer itself is still selected separately below through the dedicated
        // javaagent property, so restoring this mailbox does NOT roll back the 26.2 transformer.
        //
        // This intentionally matches the launcher-side transport used by the known-good
        // 0.9.17-0.9.21 builds. The later v11/per-instance mailbox split caused 26.2 to stop
        // receiving finished item captures reliably even though 26.1.2 continued to render.
        final boolean droidBridgeMinecraft262 = isMinecraft262ReleaseForDebugUtilsFallback(versionJson);
        final File droidBridgeIconMailbox =
                new File(PathManager.DIR_CACHE, "droidbridge_dual_screen_live_icons_v9");
        if (droidBridgeMinecraft262) {
            // Keep the current hybrid LibPatcher routing: 26.2 gets the stable historical
            // GuiRenderer/GuiItemAtlas transformer, while 26.3+ keeps the newer transformer.
            args.add("-Ddroidbridge.dualscreen.shield_native_capture_26_2=true");
        }
        File droidBridgeIconRequests = new File(droidBridgeIconMailbox, "requests");
        File droidBridgeIconResponses = new File(droidBridgeIconMailbox, "responses");
        if (!droidBridgeIconMailbox.isDirectory()) droidBridgeIconMailbox.mkdirs();
        if (!droidBridgeIconRequests.isDirectory()) droidBridgeIconRequests.mkdirs();
        if (!droidBridgeIconResponses.isDirectory()) droidBridgeIconResponses.mkdirs();
        try { new File(droidBridgeIconMailbox, "ready.current").delete(); } catch (Throwable ignored) { }
        File[] staleIconRequests = droidBridgeIconRequests.listFiles();
        if (staleIconRequests != null) {
            for (File stale : staleIconRequests) {
                if (stale != null && stale.isFile()) {
                    String name = stale.getName().toLowerCase(java.util.Locale.ROOT);
                    if (name.endsWith(".req") || name.endsWith(".tmp")) {
                        try { stale.delete(); } catch (Throwable ignored) { }
                    }
                }
            }
        }
        args.add("-Ddroidbridge.dualscreen.iconDir=" + droidBridgeIconMailbox.getAbsolutePath());
        if (droidBridgeMinecraft262) {
            Logging.i(TAG, "DroidBridge 26.2 dual-screen exact-icon transport restored to v9; "
                    + "stable 26.2 GuiItemAtlas transformer remains enabled");
        }
        args.add("-Ddroidbridge.dualscreen.hideHud=false");
        args.add("-Ddroidbridge.path.private.account=" + PathManager.DIR_ACCOUNT_NEW);
        args.add("-Djava.library.path=" + nativeLibraryPath);
        // MCSR Ranked 5.x bundles both desktop-Linux and Android Snappy JNI libraries.
        // OpenJDK on Android reports itself as Linux/aarch64, so Xerial Snappy otherwise
        // extracts the glibc build (libm.so.6/libc.so.6) and crashes when replay tracking
        // starts in a ranked world. Point Snappy at the Android native already shipped
        // inside the unmodified MCSR Ranked jar.
        MCSRRankedCompat.applySnappyCompatibility(context, gameDirectory, args);
        // e4mc 6.x can use a launcher-supplied Android Netty-Quiche native via
        // link.e4mc.native_path. Keep the mod jar/network protocol untouched and
        // arm the override only when a real Android-compatible native is present.
        E4MCCompat.applyNativeCompatibility(context, gameDirectory, args);
        if (needsLegacyAwt) {
            args.add("-Dsun.boot.library.path=" + bootLibraryPath);
        }
        args.add("-Dorg.lwjgl.librarypath=" + lwjglNativesDir.getAbsolutePath());
        // Snapshot 4 uses LWJGL's SDL3 bindings. Point them at the exact SDL3
        // packaged by DroidBridge so the function provider cannot open another
        // copy before the OpenJDK ndlsym hook is installed.
        args.add("-Dorg.lwjgl.sdl.libname="
                + new File(PathManager.DIR_NATIVE_LIB, "libSDL3.so").getAbsolutePath());
        if (shouldEnableSdl3SingleWindowJavaBindingReuse(versionJson)) {
            args.add("-Ddroidbridge.sdl3.singleWindowReuse=true");
            Logging.i(TAG, "Snapshot 9+ SDL3 single-window Java binding compatibility enabled for "
                    + versionJson.optString("id", versionId));
        }
        if (controlifyPresent) {
            File controlifySdl3 = new File(PathManager.DIR_NATIVE_LIB, "libSDL3.so");
            args.add("-Ddroidbridge.controlify.sdl3_compat=true");
            args.add("-Ddroidbridge.controlify.sdl3.path=" + controlifySdl3.getAbsolutePath());

            if (shouldEnableControlifySharedSdl263Compat(versionJson)) {
                args.add("-Ddroidbridge.controlify.knot_safe_26_3=true");
                Logging.i(TAG, "Controlify Minecraft 26.3 shared-SDL keyboard/event compatibility enabled");
            }

            Logging.i(TAG, "Controlify Android SDL3 FFM path active: "
                    + controlifySdl3.getAbsolutePath());
        }
        args.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=" + PathManager.DIR_CACHE.getAbsolutePath());
        args.add("-Djna.tmpdir=" + PathManager.DIR_CACHE.getAbsolutePath());
        addJnaAndroidWorkarounds(args, jnaNativePath, jnaLibraryPath, controllablePresent);
        args.add("-Dio.netty.native.workdir=" + PathManager.DIR_CACHE.getAbsolutePath());
        String rendererLibrary = resolveRendererOpenGlLibrary(renderer);
        if (!rendererLibrary.isEmpty()) {
            args.add("-Dorg.lwjgl.opengl.libname=" + rendererLibrary);
        }

        /*
         * Android 10's linker/OpenJDK combination on devices such as the LG G7
         * can execute LTW when DroidBridge preloads it, but LWJGL 3.4's Linux
         * GL.create() bootstrap cannot reopen LTW as the context-management
         * library. Minecraft 26.3 uses SDL3/EGL anyway, so select EGL explicitly
         * for that narrow path and let DroidBridge's app-owned SDL3 EGL provider
         * supply eglGetProcAddress. The provider continues routing desktop GL
         * procedure lookup to LTW via DROIDBRIDGE_SDL3_OPENGL_LIBRARY.
         *
         * Keep older LTW/GLFW releases and Android 11+ on the established path.
         */
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q
                && isLtwRenderer(renderer)
                && shouldEnableSdl3SingleWindowJavaBindingReuse(versionJson)) {
            File sdl3EglProvider = new File(
                    PathManager.DIR_NATIVE_LIB,
                    "libdroidbridge_sdl3_egl.so"
            );
            if (sdl3EglProvider.isFile()) {
                args.add("-Dorg.lwjgl.opengl.contextAPI=EGL");
                args.add("-Dorg.lwjgl.egl.libname=" + sdl3EglProvider.getAbsolutePath());
                Logging.i(TAG, "Android 10 LTW LWJGL context bootstrap using SDL3 EGL provider="
                        + sdl3EglProvider.getAbsolutePath());
            } else {
                Logging.e(TAG, "Android 10 LTW LWJGL context bootstrap provider missing="
                        + sdl3EglProvider.getAbsolutePath());
            }
        }

        args.add("-Dorg.lwjgl.freetype.libname=" + new File(PathManager.DIR_NATIVE_LIB, "libfreetype.so").getAbsolutePath());
        args.add("-Dglfwstub.windowWidth=" + glfwWindowWidth);
        args.add("-Dglfwstub.windowHeight=" + glfwWindowHeight);
        args.add("-Dglfwstub.initEgl=" + (!needsLegacyAwt && shouldGlfwStubInitEgl(renderer)));
        args.add("-Dext.net.resolvPath=" + resolvFile);
        addMinecraftNetworkJvmArgs(args, runtimeDir);
        enforceBtaSkinProxyJvmArgs(args, versionJson);
        args.add("-Dlog4j2.formatMsgNoLookups=true");
        if (legacyForgeRuntime) {
            addLegacyForgeLog4jPatchArg(args);
        }
        args.add("-Dnet.minecraft.clientmodname=" + getClientModNameForRenderer(renderer));
        args.add("-Dfml.earlyprogresswindow=false");
        args.add("-Dloader.disable_forked_guis=true");
        if (resolveRuntimeMajor(runtimeDir) >= 9) {
            addJvmArgIfMissing(args, "-Djdk.lang.Process.launchMechanism=FORK");
            Logging.i(TAG, "Applied jdk.lang.Process.launchMechanism=FORK for Android ProcessBuilder compatibility.");
        }
        args.add("-Dsodium.checks.issue2561=false");
        addReplayModFFmpegJvmArgs(args, replayFfmpeg);
        addDroidBridgeLibPatcherIfAvailable(args, runtimeDir, renderer, versionJson);

        int requestedMemoryMb = MemoryAllocationUtils.resolveAllocatedMemoryMb(context);
        int allocatedMemoryMb = legacyForgeRuntime
                ? resolveLegacyForgeMaxHeapMb(requestedMemoryMb)
                : requestedMemoryMb;
        int startMemoryMb = legacyForgeRuntime ? allocatedMemoryMb : resolveStartHeapMb(allocatedMemoryMb);
        Logging.i(TAG, "Using allocated memory: Xms=" + startMemoryMb
                + " MB, Xmx=" + allocatedMemoryMb + " MB"
                + (legacyForgeRuntime ? " (legacy Forge Reborn parity profile)" : ""));

        args.add("-XX:ActiveProcessorCount=" + java.lang.Runtime.getRuntime().availableProcessors());
        args.add("-Xms" + startMemoryMb + "M");
        args.add("-Xmx" + allocatedMemoryMb + "M");
        args.add("-cp");
        args.add(classPath);
        return args;
    }

    private void addBetterThanAdventureJvmArgsIfNeeded(
            @NonNull ArrayList<String> args,
            @NonNull JSONObject versionJson
    ) {
        if (!isBetterThanAdventureProfile(versionJson)) return;

        // Keep BTA's controller workaround isolated to BTA. Do not globally disable
        // GLFW/SDL controller paths because Controlify, Controllable, and Legacy4J
        // depend on those routes. The isolated lwjgl3.3.3-bta component reads these
        // flags and hides only BTA's incomplete native controller setup path.
        addJvmArgIfMissing(args, "-Ddroidbridge.bta=true");
        addJvmArgIfMissing(args, "-Ddroidbridge.bta.disableNativeControllers=true");
        addJvmArgIfMissing(args, "-Ddroidbridge.bta.launcherControllerFallback=true");
        if (BtaRendererPolicy.isBta8OrNewer(this.versionId, null)) {
            // The BTA GLFW shim uses one adaptive presentation pacer. It leaves
            // real eglSwapInterval enabled and only sleeps when the EGL swap
            // returns before the next display deadline, avoiding double pacing.
            addJvmArgIfMissing(args, "-Ddroidbridge.bta.vsyncPacer=true");
        }
        Logging.i(TAG, "Applied isolated BTA input/controller/VSync JVM flags");
    }

    private void writeLegacyForgeSplashConfigIfNeeded(
            @NonNull File runtimeDir,
            @NonNull JSONObject versionJson,
            @NonNull File gameDirectory
    ) {
        if (!isLegacyForgeRuntimeProfile(runtimeDir, versionJson)) return;

        File configDir = new File(gameDirectory, "config");
        File splashFile = new File(configDir, "splash.properties");

        try {
            if (!configDir.exists() && !configDir.mkdirs()) {
                Logging.i(TAG, "Unable to create legacy Forge config directory for splash disable: "
                        + configDir.getAbsolutePath());
                return;
            }

            LinkedHashMap<String, String> values = readSimplePropertiesFile(splashFile);
            boolean changed = false;
            changed |= putIfChanged(values, "enabled", "false");
            changed |= putIfChanged(values, "rotate", "false");
            changed |= putIfChanged(values, "showMemory", "false");

            if (changed || !splashFile.isFile()) {
                writeSimplePropertiesFile(splashFile, values);
                Logging.i(TAG, "Hard-disabled legacy Forge splash from launch builder: "
                        + splashFile.getAbsolutePath());
            } else {
                Logging.i(TAG, "Legacy Forge splash already disabled from launch builder: "
                        + splashFile.getAbsolutePath());
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to disable legacy Forge splash from launch builder", throwable);
        }
    }

    @NonNull
    private LinkedHashMap<String, String> readSimplePropertiesFile(@NonNull File file) throws Exception {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        if (!file.isFile()) return values;

        String text = readFile(file);
        for (String line : text.split("\\r?\\n")) {
            if (line == null) continue;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            int equals = trimmed.indexOf('=');
            int colon = trimmed.indexOf(':');
            int index;
            if (equals >= 0 && colon >= 0) {
                index = Math.min(equals, colon);
            } else {
                index = Math.max(equals, colon);
            }
            if (index <= 0) continue;

            String key = trimmed.substring(0, index).trim();
            String value = trimmed.substring(index + 1).trim();
            if (!key.isEmpty()) values.put(key, value);
        }
        return values;
    }

    private boolean putIfChanged(
            @NonNull LinkedHashMap<String, String> values,
            @NonNull String key,
            @NonNull String value
    ) {
        String current = values.get(key);
        if (current != null && value.equalsIgnoreCase(current.trim())) return false;
        values.put(key, value);
        return true;
    }

    private void writeSimplePropertiesFile(
            @NonNull File file,
            @NonNull LinkedHashMap<String, String> values
    ) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Unable to create directory: " + parent.getAbsolutePath());
        }

        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            out.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }

        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(out.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static int resolveStartHeapMb(int maxHeapMb) {
        if (maxHeapMb <= 0) return 512;

        // Android needs native/GPU headroom. Do not pre-commit the full heap.
        // Xmx remains the user-selected RAM cap; Xms is only the starting heap.
        int quarter = maxHeapMb / 4;
        return Math.min(768, Math.max(512, quarter));
    }

    private int resolveLegacyForgeMaxHeapMb(int requestedMb) {
        // Match the working Reborn/the prior implementation Java 8 Forge profile. RLCraft is much
        // more stable on this Android HotSpot build with a fixed 2 GB heap than
        // with a large 4-5 GB heap that leaves less native/JIT/GL headroom.
        return 2048;
    }

    private boolean isLegacyForgeRuntimeProfile(
            @NonNull File runtimeDir,
            @NonNull JSONObject versionJson
    ) {
        if (resolveRuntimeMajor(runtimeDir) >= 9) return false;

        String combined = (versionId + " "
                + versionJson.optString("id", "") + " "
                + versionJson.optString("mainClass", "") + " "
                + versionJson.optString("inheritsFrom", "") + " "
                + versionJson.optString("minecraftArguments", "")).toLowerCase(Locale.ROOT);

        if (combined.contains("neoforge") || combined.contains("net.neoforged")) return false;
        if (combined.contains("launchwrapper") || combined.contains("fmltweaker")) return true;
        if (!isForgeOrBootstrapVersion(versionJson)) return false;

        int[] parsed = parseReleaseVersion(versionJson.optString("id", versionId));
        if (parsed == null) return true;
        return parsed[0] == 1 && parsed[1] <= 16;
    }

    private void addLegacyForgeJvmStabilityArgs(
            @NonNull ArrayList<String> args,
            @NonNull JSONObject versionJson
    ) {
        // Intentionally empty. The working Reborn/the prior implementation RLCraft launch does not
        // force -Xss or TieredStopAtLevel, so DroidBridge should not add those
        // unless a future crash log proves they are required for a specific pack.
    }


    @NonNull
    private String resolveLegacyLauncherHome(@NonNull File gameDirectory) {
        try {
            File minecraftHome = new File(PathManager.DIR_MINECRAFT_HOME);
            File parent = minecraftHome.getParentFile();
            if (parent != null && parent.isDirectory()) return parent.getAbsolutePath();
        } catch (Throwable ignored) {
        }

        File current = gameDirectory;
        while (current != null) {
            if (".minecraft".equals(current.getName())) {
                File parent = current.getParentFile();
                if (parent != null) return parent.getAbsolutePath();
            }
            current = current.getParentFile();
        }
        return gameDirectory.getAbsolutePath();
    }

    private void addLegacyForgeLog4jPatchArg(@NonNull ArrayList<String> args) {
        File patch = resolveLegacyForgeLog4jPatchFile();
        if (patch == null || !patch.isFile()) {
            Logging.i(TAG, "Legacy Forge log4j patch config not found; continuing with log4j2.formatMsgNoLookups.");
            return;
        }
        addJvmArgIfMissing(args, "-Dlog4j.configurationFile=" + patch.getAbsolutePath());
        Logging.i(TAG, "Applied legacy Forge log4j config: " + patch.getAbsolutePath());
    }

    @Nullable
    private File resolveLegacyForgeLog4jPatchFile() {
        File[] candidates = new File[]{
                new File(PathManager.DIR_FILE, "components/log4j-rce-patch-1.12.xml"),
                new File(PathManager.DIR_FILE, "log4j-rce-patch-1.12.xml"),
                new File(PathManager.DIR_DATA, "components/log4j-rce-patch-1.12.xml"),
                new File(PathManager.DIR_DATA, "log4j-rce-patch-1.12.xml")
        };
        for (File candidate : candidates) {
            if (candidate != null && candidate.isFile()) return candidate;
        }
        return null;
    }

    private int resolveLaunchWidth() {
        return Math.max(1, width);
    }

    private int resolveLaunchHeight() {
        return Math.max(1, height);
    }

    private int getResolutionScalePercentSafe() {
        try {
            int percent = LauncherPreferences.getGameResolutionScalePercent(context);
            if (percent <= 0) return 100;
            return Math.max(1, Math.min(100, percent));
        } catch (Throwable ignored) {
            return 100;
        }
    }

    private int resolveBridgeWindowWidth(int fallback) {
        try {
            int value = org.lwjgl.glfw.CallbackBridge.windowWidth;
            if (value > 0) return value;
        } catch (Throwable ignored) {
        }
        return Math.max(1, fallback);
    }

    private int resolveBridgeWindowHeight(int fallback) {
        try {
            int value = org.lwjgl.glfw.CallbackBridge.windowHeight;
            if (value > 0) return value;
        } catch (Throwable ignored) {
        }
        return Math.max(1, fallback);
    }


    private void enforceBtaSkinProxyJvmArgs(
            @NonNull ArrayList<String> args,
            @NonNull JSONObject versionJson
    ) {
        if (!isBetterThanAdventureProfile(versionJson)) return;

        int port = getActiveLegacySkinProxyPort();
        if (port <= 0) return;

        // Inherited/version JVM arguments are appended after the proxy is created.
        // Make the BTA transport bridge authoritative at the end of JVM assembly so
        // a stale proxyHost/proxyPort cannot bypass the local skin override.
        purgeArg(args, "-Dhttp.proxyHost=");
        purgeArg(args, "-Dhttp.proxyPort=");
        purgeArg(args, "-Dhttp.nonProxyHosts=");
        args.add("-Dhttp.proxyHost=127.0.0.1");
        args.add("-Dhttp.proxyPort=" + port);
        args.add("-Dhttp.nonProxyHosts=127.*|localhost|::1");

        Logging.i(TAG, "BTA skin proxy JVM route finalized on port " + port);
    }

    private void addMinecraftNetworkJvmArgs(
            @NonNull ArrayList<String> args,
            @NonNull File runtimeDir
    ) {
        /*
         * Normal Minecraft multiplayer must stay on the stock Java networking path.
         * These properties explicitly disable any proxy leakage from earlier in-process
         * JVM launches or P2P experiments while keeping SRV DNS available for hosted
         * names such as *.modrinth.gg.
         */
        addJvmArgIfMissing(args, "-Djava.net.useSystemProxies=false");
        addJvmArgIfMissing(args, "-Djava.net.preferIPv6Addresses=false");

        /*
         * Minecraft Java resolves server redirects through JNDI SRV lookups
         * (_minecraft._tcp.<host>). On Android runtimes, java.naming and
         * jdk.naming.dns are not always resolved unless requested explicitly.
         * The earlier check only looked at <runtime>/lib/modules and missed
         * runtimes laid out as <runtime>/jre/lib/modules, so DroidBridge could
         * silently fall back to A-record + 25565 while PC/legacy profile followed SRV.
         */
        if (resolveRuntimeMajor(runtimeDir) >= 9 && runtimeLooksModuleCapable(runtimeDir)) {
            addJavaModuleIfMissing(args, "java.naming");
            addJavaModuleIfMissing(args, "jdk.naming.dns");
            Logging.i(TAG, "Enabled Java DNS/JNDI modules for Minecraft SRV server address resolution.");
        } else {
            Logging.i(TAG, "Skipping Java DNS/JNDI module args for runtime "
                    + runtimeDir.getName()
                    + " state="
                    + RuntimeCompat.describeRuntimeState(runtimeDir.getName(), runtimeDir));
        }
    }

    @NonNull
    private String prepareAndroidResolvConfForRuntime() {
        File resolvFile = new File(PathManager.DIR_DATA, "resolv.conf");
        LinkedHashSet<String> dnsServers = new LinkedHashSet<>();

        try {
            Object service = context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (service instanceof ConnectivityManager) {
                ConnectivityManager connectivity = (ConnectivityManager) service;
                Network activeNetwork = connectivity.getActiveNetwork();
                LinkProperties properties = activeNetwork != null
                        ? connectivity.getLinkProperties(activeNetwork)
                        : null;
                if (properties != null) {
                    for (InetAddress address : properties.getDnsServers()) {
                        if (address == null) continue;
                        String host = address.getHostAddress();
                        if (host == null) continue;
                        int scope = host.indexOf('%');
                        if (scope >= 0) host = host.substring(0, scope);
                        host = host.trim();
                        if (!host.isEmpty()) dnsServers.add(host);
                    }
                }
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to read Android DNS servers for resolv.conf", throwable);
        }

        if (dnsServers.isEmpty()) {
            // Last-resort fallback so libextnet/JNDI still has a usable resolver file.
            dnsServers.add("1.1.1.1");
            dnsServers.add("8.8.8.8");
        }

        StringBuilder out = new StringBuilder();
        out.append("# Auto-generated by DroidBridge for Java DNS/JNDI SRV lookups\n");
        for (String server : dnsServers) {
            out.append("nameserver ").append(server).append('\n');
        }
        out.append("options timeout:2 attempts:2\n");

        try {
            File parent = resolvFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Logging.i(TAG, "Unable to create resolv.conf parent: " + parent.getAbsolutePath());
            }
            try (FileOutputStream output = new FileOutputStream(resolvFile, false)) {
                output.write(out.toString().getBytes(StandardCharsets.UTF_8));
            }
            Logging.i(TAG, "Wrote Java DNS resolver config for Minecraft SRV lookup: "
                    + resolvFile.getAbsolutePath()
                    + " servers="
                    + dnsServers);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to write Java DNS resolver config", throwable);
        }

        return resolvFile.getAbsolutePath();
    }

    private boolean runtimeLooksModuleCapable(@NonNull File runtimeDir) {
        File javaHome = RuntimeCompat.findModernJavaHome(runtimeDir);
        if (javaHome == null) javaHome = runtimeDir;

        if (new File(javaHome, "lib/modules").isFile()) return true;
        if (new File(runtimeDir, "lib/modules").isFile()) return true;
        if (new File(runtimeDir, "jre/lib/modules").isFile()) return true;
        if (new File(javaHome, "jmods/java.naming.jmod").isFile()) return true;
        if (new File(javaHome, "jmods/jdk.naming.dns.jmod").isFile()) return true;
        if (new File(runtimeDir, "jmods/java.naming.jmod").isFile()) return true;
        if (new File(runtimeDir, "jmods/jdk.naming.dns.jmod").isFile()) return true;
        return RuntimeCompat.findFileNamed(runtimeDir, "modules", 10) != null;
    }

    private void addJavaModuleIfMissing(
            @NonNull ArrayList<String> args,
            @NonNull String moduleName
    ) {
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg == null) continue;

            if (arg.startsWith("--add-modules=")) {
                if (addModuleToInlineAddModules(args, i, moduleName)) return;
            } else if ("--add-modules".equals(arg) && i + 1 < args.size()) {
                if (addModuleToSeparateAddModules(args, i + 1, moduleName)) return;
            }
        }

        args.add("--add-modules=" + moduleName);
    }

    private boolean addModuleToInlineAddModules(
            @NonNull ArrayList<String> args,
            int index,
            @NonNull String moduleName
    ) {
        String arg = args.get(index);
        String prefix = "--add-modules=";
        String modules = arg.substring(prefix.length());
        if (moduleListContains(modules, moduleName)) return true;
        args.set(index, prefix + appendModule(modules, moduleName));
        return true;
    }

    private boolean addModuleToSeparateAddModules(
            @NonNull ArrayList<String> args,
            int valueIndex,
            @NonNull String moduleName
    ) {
        String modules = args.get(valueIndex);
        if (modules == null) modules = "";
        if (moduleListContains(modules, moduleName)) return true;
        args.set(valueIndex, appendModule(modules, moduleName));
        return true;
    }

    private boolean moduleListContains(@NonNull String modules, @NonNull String moduleName) {
        for (String part : modules.split(",")) {
            if (moduleName.equals(part.trim())) return true;
        }
        return false;
    }

    @NonNull
    private String appendModule(@NonNull String modules, @NonNull String moduleName) {
        String cleaned = modules.trim();
        if (cleaned.isEmpty()) return moduleName;
        return cleaned + "," + moduleName;
    }


    private void addLegacyBetaJvmArgs(@NonNull ArrayList<String> args, @NonNull JSONObject versionJson) {
        String id = versionJson.optString("id", versionId);
        if (id == null || id.trim().isEmpty()) id = versionId;
        String lower = id.trim().toLowerCase(Locale.ROOT);

        if (!isBetacraftProxyVersion(lower)) {
            return;
        }

        addJvmArgIfMissing(args, "-Dhttp.proxyHost=betacraft.uk");
        addJvmArgIfMissing(args, "-Dhttp.proxyPort=11705");
        addJvmArgIfMissing(args, "-Djava.util.Arrays.useLegacyMergeSort=true");
        addJvmArgIfMissing(args, "-Djava.net.preferIPv4Stack=true");
        Logging.i(TAG, "Applied Betacraft proxy JVM args for legacy version " + id);
    }

    private boolean isBetacraftProxyVersion(@NonNull String id) {
        if (id.startsWith("rd-") || id.startsWith("classic") || id.startsWith("infdev") || id.startsWith("indev")) {
            return true;
        }
        if (id.matches("^a\\d.*") || id.matches("^b\\d.*")) {
            return true;
        }
        if (id.startsWith("1.")) {
            String[] parts = id.split("[^0-9]+");
            int found = 0;
            int major = -1;
            int minor = -1;
            for (String part : parts) {
                if (part == null || part.isEmpty()) continue;
                try {
                    if (found == 0) {
                        major = Integer.parseInt(part);
                    } else if (found == 1) {
                        minor = Integer.parseInt(part);
                        break;
                    }
                    found++;
                } catch (NumberFormatException ignored) {
                }
            }
            return major == 1 && minor >= 0 && minor < 6;
        }
        return false;
    }


    @NonNull
    private String resolveRendererOpenGlLibrary(@Nullable RendererInterface renderer) {
        if (renderer == null) {
            return "libng_gl4es.so";
        }


        String library = renderer.getRendererLibrary();
        if (library == null || library.trim().isEmpty()) {
            Logging.i(TAG, "Selected renderer returned an empty OpenGL library, falling back to libng_gl4es.so");
            return "libng_gl4es.so";
        }

        if (isLtwRenderer(renderer)) {
            File direct = new File(library.trim());
            if (direct.isAbsolute() && direct.isFile()) return direct.getAbsolutePath();
            for (File path : renderer.getLibrarySearchPaths()) {
                File candidate = new File(path, "libltw.so");
                if (candidate.isFile()) return candidate.getAbsolutePath();
            }
            return "libltw.so";
        }

        // LWJGL's org.lwjgl.opengl.libname normally receives a library name, not a copied
        // APK/source path. The actual plugin native directory is already added to
        // java.library.path and LD_LIBRARY_PATH by buildNativeLibraryPath()/RuntimeBootstrap.
        return new File(library.trim()).getName();
    }


    @NonNull
    private String getClientModNameForRenderer(@Nullable RendererInterface renderer) {
        if (isMobileGluesRenderer(renderer)) {
            // MobileGlues only loads its external config for launcher names it recognizes.
            // Keep this as a renderer compatibility name only; the app still identifies itself
            // as JavaLauncher in the launcher UI/log header.
            return "ZalithLauncher";
        }
        return "JavaLauncher";
    }

    private boolean isMobileGluesRenderer(@Nullable RendererInterface renderer) {
        if (renderer == null) return false;
        String combined = (renderer.getUniqueIdentifier() + " "
                + renderer.getRendererName() + " "
                + renderer.getRendererId() + " "
                + renderer.getRendererLibrary()).toLowerCase(Locale.ROOT);
        return combined.contains("mobileglues") || combined.contains("mobile glues");
    }

    private boolean shouldGlfwStubInitEgl(@Nullable RendererInterface renderer) {
        /*
         * GL4ES is an OpenGL wrapper layered over Android EGL/GLES. Let the
         * GLFW stub initialise its EGL path instead of forcing the native bridge
         * down the no-init path. This avoids the late gl_init SIGSEGV seen after
         * "EGLBridge: Binding to OpenGL ES" while keeping MobileGlues/Krypton
         * on the already working bridge path.
         */
        return isGl4esRenderer(renderer);
    }

    private boolean isGl4esRenderer(@Nullable RendererInterface renderer) {
        if (renderer == null) return false;
        String combined = (renderer.getUniqueIdentifier() + " "
                + renderer.getRendererName() + " "
                + renderer.getRendererId() + " "
                + renderer.getRendererLibrary()).toLowerCase(Locale.ROOT);
        return combined.contains("gl4es") || combined.contains("opengles2");
    }

    private void addJnaAndroidWorkarounds(
            @NonNull ArrayList<String> args,
            @NonNull String jnaNativePath,
            @NonNull String jnaLibraryPath,
            boolean controllablePresent
    ) {
        File jnaDispatch = findFirstLibraryInPath(jnaNativePath, "libjnidispatch.so");
        File controllableSdl2 = findFirstLibraryInPath(jnaLibraryPath, "libSDL2.so");

        if (!jnaNativePath.trim().isEmpty()) {
            args.add("-Djna.boot.library.path=" + jnaNativePath);
        }
        if (!jnaLibraryPath.trim().isEmpty()) {
            args.add("-Djna.library.path=" + jnaLibraryPath);
        }

        /*
         * Do NOT force jna.nounpack=true for Controllable. ControllableSDL calls
         * its embedded-resource loader directly; with nounpack=true JNA 5.12 can
         * hit Native.isUnpacked(file=null) and crash before fallback.
         *
         * ControllerModCompat patches Controllable's embedded linux-aarch64 SDL2
         * resource to the Android libSDL2.so, so normal JNA unpacking should stay
         * enabled and will unpack an Android-safe library.
         */
        args.add("-Djna.nounpack=false");
        if (controllablePresent && controllableSdl2 != null && controllableSdl2.isFile()) {
            Logging.i(TAG, "Controllable SDL2 native prepared: " + controllableSdl2.getAbsolutePath());
        }

        args.add("-Djna.nosys=false");
        if (jnaDispatch != null && jnaDispatch.isFile()) {
            Logging.i(TAG, "JNA Android dispatch found: " + jnaDispatch.getAbsolutePath());
        } else {
            Logging.i(TAG, "JNA Android dispatch missing. Checked path: " + jnaNativePath);
        }

        args.add("-Djna.debug_load=false");
        args.add("-Dcom.sun.jna.useProtected=true");
        Logging.i(TAG, "Applied Android JNA boot path: " + jnaNativePath);
        Logging.i(TAG, "Applied Android JNA library path: " + jnaLibraryPath);
    }

    @NonNull
    private String buildJnaNativePath() {
        StringBuilder builder = new StringBuilder();
        File nativesRoot = new File(PathManager.DIR_CACHE, "natives");

        // Preferred exact folder used by this launcher/version.
        addPathIfContains(builder, new File(nativesRoot, versionId), "libjnidispatch.so");

        // Loader versions often launch as fabric-loader-<loader>-<mc>, while natives may be extracted to the MC id.
        String mcVersionId = extractMinecraftIdFromLoaderVersion(versionId);
        if (!mcVersionId.equals(versionId)) {
            addPathIfContains(builder, new File(nativesRoot, mcVersionId), "libjnidispatch.so");
        }

        // Do not append every extracted natives folder here. Reborn/the prior implementation only
        // exposes the current version natives directory to JNA. Appending stale
        // natives from other Forge/NeoForge instances can make heavy 1.12 packs
        // load the wrong native library and crash inside libjvm/libffi paths.
        return builder.toString();
    }

    @NonNull
    private String extractMinecraftIdFromLoaderVersion(@NonNull String id) {
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.startsWith("fabric-loader-") || lower.startsWith("quilt-loader-")) {
            int lastDash = id.lastIndexOf('-');
            if (lastDash > 0 && lastDash + 1 < id.length()) {
                return id.substring(lastDash + 1);
            }
        }
        return id;
    }

    private void addPathIfContains(
            @NonNull StringBuilder builder,
            @NonNull File dir,
            @NonNull String fileName
    ) {
        if (!dir.isDirectory()) return;
        if (!new File(dir, fileName).isFile()) return;
        addPath(builder, dir);
    }

    @Nullable
    private File findFirstLibraryInPath(@NonNull String pathList, @NonNull String fileName) {
        if (pathList.trim().isEmpty()) return null;
        String[] parts = pathList.split(":");
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) continue;
            File candidate = new File(part.trim(), fileName);
            if (candidate.isFile()) return candidate;
        }
        return null;
    }

    private void addPathList(@NonNull StringBuilder builder, @NonNull String pathList) {
        if (pathList.trim().isEmpty()) return;
        String[] parts = pathList.split(":");
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) continue;
            addPath(builder, new File(part.trim()));
        }
    }



    private void addOptiFineSecureJarIgnoreListIfNeeded(
            @NonNull ArrayList<String> args,
            boolean optiFinePresent,
            @NonNull ArrayList<String> optiFineSecureJarIgnoreNames
    ) {
        if (!optiFinePresent) return;
        if (optiFineSecureJarIgnoreNames.isEmpty()) {
            Logging.i(TAG, "OptiFine SecureJar ignoreList not changed: no Minecraft base-resource jar names were collected.");
            return;
        }

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg == null || !arg.startsWith("-DignoreList=")) continue;

            String existing = arg.substring("-DignoreList=".length());
            String merged = mergeCommaSeparatedValues(existing, optiFineSecureJarIgnoreNames);
            args.set(i, "-DignoreList=" + merged);
            Logging.i(TAG, "OptiFine SecureJar ignoreList merged: " + merged);
            addJvmArgIfMissing(args, "-DlibraryDirectory=" + MinecraftVersionInstaller.getLibrariesDirectory().getAbsolutePath());
            return;
        }

        String created = mergeCommaSeparatedValues("", optiFineSecureJarIgnoreNames);
        args.add("-DignoreList=" + created);
        addJvmArgIfMissing(args, "-DlibraryDirectory=" + MinecraftVersionInstaller.getLibrariesDirectory().getAbsolutePath());
        Logging.i(TAG, "OptiFine SecureJar ignoreList created: " + created);
    }

    @NonNull
    private String mergeCommaSeparatedValues(
            @Nullable String existing,
            @NonNull ArrayList<String> additions
    ) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (existing != null && !existing.trim().isEmpty()) {
            for (String part : existing.split(",")) {
                if (part == null) continue;
                String value = part.trim();
                if (!value.isEmpty()) values.add(value);
            }
        }
        for (String addition : additions) {
            if (addition == null) continue;
            String value = addition.trim();
            if (!value.isEmpty()) values.add(value);
        }

        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append(',');
            out.append(value);
        }
        return out.toString();
    }

    private void addDroidBridgeLibPatcherIfAvailable(
            @NonNull ArrayList<String> args,
            @NonNull File runtimeDir,
            @Nullable RendererInterface renderer,
            @NonNull JSONObject versionJson
    ) {
        // Do not skip the LWJGL/GLFW library patcher for Krypton or 26.1.x.
        // The last known-good launch path kept this agent enabled for every renderer.
        // Skipping it delays the Android GLFW/linker hook until Minecraft is already
        // starting LWJGL, which caused the 26.1/26.1.1/26.1.2 regressions.

        LibPath.refresh();

        File patcher = LibPath.DROIDBRIDGE_LIB_PATCHER;
        ensureBundledDroidBridgeLibPatcherCurrent(patcher);
        if (patcher == null || !patcher.isFile()) {
            Logging.i(TAG, "DroidBridgeLibPatcher.jar was not found at "
                    + (patcher != null ? patcher.getAbsolutePath() : "<null>"));
            return;
        }

        String agentArg = "-javaagent:" + patcher.getAbsolutePath();
        if (!args.contains(agentArg)) {
            args.add(agentArg);
            Logging.i(TAG, "Applied DroidBridgeLibPatcher for all renderers: " + patcher.getAbsolutePath());
        } else {
            Logging.i(TAG, "DroidBridgeLibPatcher already present: " + patcher.getAbsolutePath());
        }

        /*
         * The optional debug-utils workaround targets Minecraft 26.2's
         * VulkanDebug.Enabled implementation. Never arm it for 26.3 snapshots
         * or later because those builds use a different Vulkan bootstrap.
         */
        if (useSystemVulkanDriverOverride
                && isMinecraft262ReleaseForDebugUtilsFallback(versionJson)) {
            addJvmArgIfMissing(args, "-Ddroidbridge.vulkan.debug_utils_fallback=true");
            Logging.i(TAG, "Enabled optional VK_EXT_debug_utils fallback for Minecraft 26.2 System Vulkan.");
        } else if (useSystemVulkanDriverOverride) {
            Logging.i(TAG, "Skipped 26.2-only VK_EXT_debug_utils fallback for "
                    + versionJson.optString("id", versionId));
        }

        if (shouldEnableNativeAccessForRuntime(runtimeDir)) {
            addJvmArgIfMissing(args, "--enable-native-access=ALL-UNNAMED");
            Logging.i(TAG, "Enabled native access for DroidBridgeLibPatcher on runtime " + runtimeDir.getName());
        }
    }

    /**
     * The javaagent is launch-critical and may outlive an APK/component update in app data.
     * Verify the extracted copy against the APK asset immediately before building the JVM
     * command. If the bytes differ, refresh it from the APK so Minecraft cannot accidentally
     * start with an older transformer revision.
     */
    private void ensureBundledDroidBridgeLibPatcherCurrent(@Nullable File patcher) {
        if (patcher == null) {
            Logging.i(TAG, "DroidBridgeLibPatcher self-check skipped: resolved path is null");
            return;
        }

        String bundledVersion = readBundledComponentVersion();
        try {
            if (fileMatchesAsset(patcher, DROIDBRIDGE_LIB_PATCHER_ASSET)) {
                Logging.i(TAG, "DroidBridgeLibPatcher self-check OK: component="
                        + bundledVersion + " sha256=" + sha256(patcher)
                        + " path=" + patcher.getAbsolutePath());
                return;
            }

            File parent = patcher.getParentFile();
            if (parent == null) {
                Logging.i(TAG, "DroidBridgeLibPatcher self-heal failed: path has no parent: "
                        + patcher.getAbsolutePath());
                return;
            }
            if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                Logging.i(TAG, "DroidBridgeLibPatcher self-heal failed: unable to create "
                        + parent.getAbsolutePath());
                return;
            }

            File staged = new File(parent, patcher.getName() + ".asset-refresh.tmp");
            File backup = new File(parent, patcher.getName() + ".asset-refresh.bak");
            //noinspection ResultOfMethodCallIgnored
            staged.delete();
            //noinspection ResultOfMethodCallIgnored
            backup.delete();

            copyAssetToFile(DROIDBRIDGE_LIB_PATCHER_ASSET, staged);
            if (!fileMatchesAsset(staged, DROIDBRIDGE_LIB_PATCHER_ASSET)) {
                throw new IllegalStateException("staged javaagent does not match APK asset");
            }

            boolean hadOld = patcher.isFile();
            if (hadOld && !patcher.renameTo(backup)) {
                // renameTo can fail on some Android filesystems. A direct delete is safe here:
                // the javaagent has not been attached to the Minecraft JVM yet.
                if (!patcher.delete()) {
                    throw new IllegalStateException("unable to replace existing javaagent");
                }
            }

            if (!staged.renameTo(patcher)) {
                // Fall back to a byte copy if an atomic rename is unavailable.
                copyFile(staged, patcher);
                //noinspection ResultOfMethodCallIgnored
                staged.delete();
            }

            if (!fileMatchesAsset(patcher, DROIDBRIDGE_LIB_PATCHER_ASSET)) {
                // Restore the old copy when possible rather than leaving a bad agent in place.
                //noinspection ResultOfMethodCallIgnored
                patcher.delete();
                if (backup.isFile()) {
                    //noinspection ResultOfMethodCallIgnored
                    backup.renameTo(patcher);
                }
                throw new IllegalStateException("installed javaagent failed APK byte verification");
            }

            //noinspection ResultOfMethodCallIgnored
            backup.delete();
            Logging.i(TAG, "DroidBridgeLibPatcher self-healed from APK asset: component="
                    + bundledVersion + " sha256=" + sha256(patcher)
                    + " path=" + patcher.getAbsolutePath());
        } catch (Throwable throwable) {
            Logging.e(TAG, "DroidBridgeLibPatcher self-heal failed; retaining available installed agent", throwable);
        }
    }

    private boolean fileMatchesAsset(@NonNull File file, @NonNull String assetPath) {
        if (!file.isFile() || file.length() <= 0) return false;
        try (InputStream asset = new BufferedInputStream(context.getAssets().open(assetPath));
             InputStream installed = new BufferedInputStream(new FileInputStream(file))) {
            byte[] left = new byte[32 * 1024];
            byte[] right = new byte[32 * 1024];
            while (true) {
                int leftRead = readChunk(asset, left);
                int rightRead = readChunk(installed, right);
                if (leftRead != rightRead) return false;
                if (leftRead < 0) return true;
                for (int i = 0; i < leftRead; i++) {
                    if (left[i] != right[i]) return false;
                }
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to compare javaagent with APK asset " + assetPath, throwable);
            return false;
        }
    }

    private static int readChunk(@NonNull InputStream input, @NonNull byte[] buffer) throws Exception {
        int offset = 0;
        while (offset < buffer.length) {
            int read = input.read(buffer, offset, buffer.length - offset);
            if (read < 0) return offset == 0 ? -1 : offset;
            if (read == 0) break;
            offset += read;
        }
        return offset;
    }

    private void copyAssetToFile(@NonNull String assetPath, @NonNull File target) throws Exception {
        try (InputStream input = new BufferedInputStream(context.getAssets().open(assetPath));
             FileOutputStream output = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
        }
        if (!target.isFile() || target.length() <= 0) {
            throw new IllegalStateException("copied asset is missing or empty: " + assetPath);
        }
    }

    @NonNull
    private String readBundledComponentVersion() {
        try (InputStream input = context.getAssets().open(DROIDBRIDGE_COMPONENT_VERSION_ASSET);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[256];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            String value = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? "unknown" : value;
        } catch (Throwable throwable) {
            return "unknown";
        }
    }

    @NonNull
    private static String sha256(@NonNull File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder out = new StringBuilder(64);
        for (byte value : digest.digest()) {
            out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return out.toString();
    }

    @NonNull
    private static String sha256Text(@NonNull String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(64);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    private boolean shouldEnableNativeAccessForRuntime(@NonNull File runtimeDir) {
        return resolveRuntimeMajor(runtimeDir) >= 22;
    }

    private boolean shouldSkipLibPatcherForKrypton261(
            @Nullable RendererInterface renderer,
            @NonNull JSONObject versionJson
    ) {
        return isKryptonRenderer(renderer) && isMinecraft261Family(buildMinecraftCompatIdentity(versionJson));
    }

    private boolean isKryptonRenderer(@Nullable RendererInterface renderer) {
        if (renderer == null) return false;
        String combined = (renderer.getUniqueIdentifier() + " "
                + renderer.getRendererName() + " "
                + renderer.getRendererId() + " "
                + renderer.getRendererLibrary()).toLowerCase(Locale.ROOT);
        return combined.contains("krypton")
                || combined.contains("ng_gl4es")
                || combined.contains("e7b90ed6-e518-4d4e-93dc-5c7133cd5b31");
    }

    @NonNull
    private String buildMinecraftCompatIdentity(@NonNull JSONObject versionJson) {
        return (versionId + " "
                + versionJson.optString("id", "") + " "
                + versionJson.optString("inheritsFrom", "") + " "
                + versionJson.optString("javaLauncherFlattenedParent", "") + " "
                + versionJson.optString("mainClass", "")).toLowerCase(Locale.ROOT);
    }

    private boolean isMinecraft262ReleaseForDebugUtilsFallback(
            @NonNull JSONObject versionJson
    ) {
        String minecraftVersion = versionJson.optString("inheritsFrom", "").trim();
        if (minecraftVersion.isEmpty()) {
            minecraftVersion = versionJson.optString("javaLauncherFlattenedParent", "").trim();
        }
        if (minecraftVersion.isEmpty()) {
            minecraftVersion = versionJson.optString("id", versionId).trim();
        }
        /*
         * Match only the resolved final 26.2 Minecraft release. Exclude 26.2.x,
         * pre/rc builds and every 26.3+ class layout.
         */
        return Pattern.compile("(?<![0-9])26\\.2(?![0-9.\\-])")
                .matcher(minecraftVersion.toLowerCase(Locale.ROOT))
                .find();
    }

    private boolean isMinecraft261Family(@NonNull String value) {
        Matcher matcher = Pattern.compile("(^|[^0-9])26\\.1(?:\\.([0-9]+))?($|[^0-9])").matcher(value);
        while (matcher.find()) {
            String patchText = matcher.group(2);
            if (patchText == null || patchText.isEmpty()) {
                return true;
            }
            try {
                int patch = Integer.parseInt(patchText);
                return patch >= 0 && patch <= 2;
            } catch (NumberFormatException ignored) {
                return true;
            }
        }
        return false;
    }

    private int resolveRuntimeMajor(@NonNull File runtimeDir) {
        String name = runtimeDir.getName().toLowerCase(Locale.ROOT);
        int best = 0;
        String[] parts = name.split("[^0-9]+");
        for (String part : parts) {
            if (part == null || part.isEmpty()) continue;
            try {
                best = Math.max(best, Integer.parseInt(part));
            } catch (NumberFormatException ignored) {
            }
        }
        return best;
    }

    private boolean isVulkanZinkRenderer(@Nullable RendererInterface renderer) {
        if (renderer == null) return false;
        String combined = rendererIdentity(renderer);
        return combined.contains("vulkan_zink") || combined.contains("vulkan zink")
                || combined.contains("zink") || combined.contains("osmesa");
    }

    private boolean shouldEnableModernCacioForRenderer(@Nullable RendererInterface renderer) {
        // Modern LWJGL3 launches should not be forced through Cacio/CTC here.
        // Only legacy AWT/LWJGL2 profiles use Cacio above. Forcing modern Mesa/Zink
        // through Cacio caused the Adreno 740 fallback path to lose LWJGL's OpenGL
        // context management API.
        return false;
    }

    private boolean isLtwRenderer(@Nullable RendererInterface renderer) {
        if (renderer == null) return false;
        String combined = rendererIdentity(renderer);
        return combined.contains("ltw") || combined.contains("libltw.so");
    }

    @NonNull
    private String rendererIdentity(@NonNull RendererInterface renderer) {
        return (renderer.getUniqueIdentifier() + " "
                + renderer.getRendererName() + " "
                + renderer.getRendererId() + " "
                + renderer.getRendererLibrary()).toLowerCase(Locale.ROOT);
    }

    private void addForgeModuleWorkaroundsIfNeeded(
            @NonNull ArrayList<String> args,
            @NonNull File runtimeDir,
            @NonNull JSONObject versionJson
    ) {
        if (!isForgeOrBootstrapVersion(versionJson)) return;

        int runtimeMajor = resolveRuntimeMajor(runtimeDir);
        if (runtimeMajor > 0 && runtimeMajor < 9) {
            purgeJava9ModuleArgs(args);
            Logging.i(TAG, "Skipping Forge Java 9+ module args for Java 8 runtime "
                    + runtimeDir.getName()
                    + " on "
                    + versionJson.optString("id", versionId));
            return;
        }

        addForgeJava17ModuleOpens(args, versionJson);
        addForgeNashornAsmModulePath(args, versionJson);
    }

    private void purgeJava9ModuleArgs(@NonNull ArrayList<String> args) {
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg == null) continue;

            if (isJava9ModuleArgWithSeparateValue(arg)) {
                args.remove(i);
                if (i < args.size()) args.remove(i);
                i--;
                continue;
            }

            if (isJava9ModuleArg(arg)) {
                args.remove(i);
                i--;
            }
        }
    }

    private boolean isJava9ModuleArgWithSeparateValue(@NonNull String arg) {
        return "--module-path".equals(arg)
                || "-p".equals(arg)
                || "--upgrade-module-path".equals(arg)
                || "--add-modules".equals(arg)
                || "--limit-modules".equals(arg)
                || "--add-opens".equals(arg)
                || "--add-exports".equals(arg)
                || "--enable-native-access".equals(arg);
    }

    private boolean isJava9ModuleArg(@NonNull String arg) {
        return arg.startsWith("--module-path=")
                || arg.startsWith("--upgrade-module-path=")
                || arg.startsWith("--add-modules=")
                || arg.startsWith("--limit-modules=")
                || arg.startsWith("--add-opens=")
                || arg.startsWith("--add-exports=")
                || arg.startsWith("--enable-native-access=")
                || "--illegal-access=permit".equals(arg)
                || "--illegal-access=warn".equals(arg)
                || "--illegal-access=debug".equals(arg)
                || "--illegal-access=deny".equals(arg);
    }

    private void addForgeJava17ModuleOpens(
            @NonNull ArrayList<String> args,
            @NonNull JSONObject versionJson
    ) {
        if (!isForgeOrBootstrapVersion(versionJson)) return;

        // Forge 1.20.x / SecureJarHandler accesses MethodHandles.Lookup.IMPL_LOOKUP.
        // On Java 17 this needs java.base/java.lang.invoke opened to the unnamed module.
        // Without it Forge crashes before the Minecraft client starts with:
        // InaccessibleObjectException: module java.base does not "opens java.lang.invoke"
        addJvmArgIfMissing(args, "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED");

        // These are harmless if already present and help other Forge bootstrap reflection paths.
        addJvmArgIfMissing(args, "--add-opens=java.base/java.lang=ALL-UNNAMED");
        addJvmArgIfMissing(args, "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED");
        addJvmArgIfMissing(args, "--add-opens=java.base/java.util=ALL-UNNAMED");
        addJvmArgIfMissing(args, "--add-opens=java.base/java.util.jar=ALL-UNNAMED");
        addJvmArgIfMissing(args, "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED");

        Logging.i(TAG, "Applied Forge Java 17 module opens for " + versionJson.optString("id", versionId));
    }

    private boolean isForgeOrBootstrapVersion(@NonNull JSONObject versionJson) {
        // Cleanroom uses Forge's launch/bootstrap compatibility paths, but its
        // generated profile may expose Bouncepad rather than a Forge-named main class.
        if (CleanroomSupport.isCleanroomProfile(this.versionId, versionJson)) return true;

        String id = versionJson.optString("id", versionId).toLowerCase(Locale.ROOT);
        String mainClass = versionJson.optString("mainClass", "").toLowerCase(Locale.ROOT);

        if (id.contains("forge")) return true;
        if (mainClass.contains("cpw.mods.bootstraplauncher")) return true;
        if (mainClass.contains("net.minecraftforge")) return true;

        JSONArray libraries = versionJson.optJSONArray("libraries");
        if (libraries == null) return false;

        for (int i = 0; i < libraries.length(); i++) {
            JSONObject library = libraries.optJSONObject(i);
            if (library == null) continue;

            String name = library.optString("name", "").toLowerCase(Locale.ROOT);
            if (name.contains("net.minecraftforge:forge")
                    || name.contains("cpw.mods:securejarhandler")
                    || name.contains("cpw.mods:bootstraplauncher")
                    || name.contains("cpw.mods:modlauncher")) {
                return true;
            }
        }

        return false;
    }

    private void addJvmArgIfMissing(@NonNull ArrayList<String> args, @NonNull String value) {
        if (!args.contains(value)) {
            args.add(value);
        }
    }


    private void addForgeNashornAsmModulePath(
            @NonNull ArrayList<String> args,
            @NonNull JSONObject versionJson
    ) {
        if (!isForgeOrBootstrapVersion(versionJson)) return;

        ArrayList<String> moduleJars = collectForgeAsmModuleJars(versionJson);
        if (moduleJars.isEmpty()) {
            Logging.i(TAG, "Forge ASM module-path fix skipped; no ASM module jars found.");
            return;
        }

        String extraModulePath = joinPathList(moduleJars);
        mergeModulePath(args, extraModulePath);

        Logging.i(TAG, "Applied Forge ASM module path: " + extraModulePath);
    }

    @NonNull
    private ArrayList<String> collectForgeAsmModuleJars(@NonNull JSONObject versionJson) {
        ArrayList<String> jars = new ArrayList<>();
        JSONArray libraries = versionJson.optJSONArray("libraries");
        if (libraries == null) return jars;

        for (int i = 0; i < libraries.length(); i++) {
            JSONObject library = libraries.optJSONObject(i);
            if (library == null || !isLibraryAllowed(library)) continue;

            String name = library.optString("name", "");
            String lowerName = name.toLowerCase(Locale.ROOT);

            /*
             * Do NOT add nashorn-core here.
             *
             * Forge's own version JSON already places Nashorn on the module path
             * through its ${library_directory}/${classpath_separator} arguments.
             * Adding nashorn-core a second time causes:
             *
             *   Module com.ibm.icu reads more than one module named org.openjdk.nashorn
             *
             * The missing dependency from the previous crash was ASM, required by
             * the already-present Nashorn module, so only add ASM modules.
             */
            boolean needed = lowerName.startsWith("org.ow2.asm:asm:")
                    || lowerName.startsWith("org.ow2.asm:asm-analysis:")
                    || lowerName.startsWith("org.ow2.asm:asm-commons:")
                    || lowerName.startsWith("org.ow2.asm:asm-tree:")
                    || lowerName.startsWith("org.ow2.asm:asm-util:");

            if (!needed) continue;

            String artifactPath = resolveLibraryArtifactPath(library);
            if (artifactPath == null || artifactPath.isEmpty()) continue;

            File jar = new File(MinecraftVersionInstaller.getLibrariesDirectory(), artifactPath);
            if (jar.isFile()) {
                addPathIfMissing(jars, jar.getAbsolutePath());
            } else {
                Logging.i(TAG, "Forge ASM module-path dependency is missing: " + jar.getAbsolutePath());
            }
        }

        return jars;
    }

    private void mergeModulePath(@NonNull ArrayList<String> args, @NonNull String extraModulePath) {
        if (extraModulePath.isEmpty()) return;

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);

            if ("--module-path".equals(arg) || "-p".equals(arg)) {
                if (i + 1 < args.size()) {
                    args.set(i + 1, appendUniquePathEntries(args.get(i + 1), extraModulePath));
                } else {
                    args.add(extraModulePath);
                }
                return;
            }

            if (arg.startsWith("--module-path=")) {
                args.set(i, "--module-path=" + appendUniquePathEntries(arg.substring("--module-path=".length()), extraModulePath));
                return;
            }
        }

        args.add("--module-path");
        args.add(extraModulePath);
    }

    @NonNull
    private String appendUniquePathEntries(@Nullable String existing, @NonNull String extra) {
        ArrayList<String> entries = new ArrayList<>();

        if (existing != null && !existing.trim().isEmpty()) {
            String[] parts = existing.split(":");
            for (String part : parts) {
                if (part != null && !part.trim().isEmpty()) {
                    addPathIfMissing(entries, part.trim());
                }
            }
        }

        String[] extraParts = extra.split(":");
        for (String part : extraParts) {
            if (part != null && !part.trim().isEmpty()) {
                addPathIfMissing(entries, part.trim());
            }
        }

        return joinPathList(entries);
    }

    private void addPathIfMissing(@NonNull ArrayList<String> entries, @NonNull String path) {
        if (!entries.contains(path)) {
            entries.add(path);
        }
    }

    @NonNull
    private String joinPathList(@NonNull ArrayList<String> entries) {
        StringBuilder builder = new StringBuilder();
        for (String entry : entries) {
            if (builder.length() > 0) builder.append(':');
            builder.append(entry);
        }
        return builder.toString();
    }

    private void addMojangJvmArguments(
            @NonNull ArrayList<String> args,
            @NonNull JSONObject versionJson,
            @NonNull Map<String, String> replacements
    ) throws Exception {
        JSONObject arguments = versionJson.optJSONObject("arguments");
        if (arguments == null) return;
        JSONArray jvm = arguments.optJSONArray("jvm");
        if (jvm == null) return;
        addArgumentArray(args, jvm, replacements);
    }

    @NonNull
    private List<String> buildCacioJvmArgs(@NonNull File runtimeDir, int screenWidth, int screenHeight) {
        ArrayList<String> args = new ArrayList<>();
        boolean java8 = runtimeDir.getName().contains("8");

        File cacioDir = java8 ? LibPath.CACIO_8 : LibPath.CACIO_17;
        String cacioClassPath = buildJarClassPath(cacioDir);
        if (cacioClassPath.isEmpty()) {
            Logging.i(TAG, "Cacio AWT jars are missing, skipping AWT backend: " + cacioDir.getAbsolutePath());
            return args;
        }

        args.add("-Djava.awt.headless=false");
        args.add("-Dcacio.managed.screensize="
                + Math.max(1, screenWidth)
                + "x"
                + Math.max(1, screenHeight));
        args.add("-Dcacio.font.fontmanager=sun.awt.X11FontManager");
        args.add("-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler");
        args.add("-Dswing.defaultlaf=javax.swing.plaf.nimbus.NimbusLookAndFeel");

        if (java8) {
            args.add("-Dawt.toolkit=net.java.openjdk.cacio.ctc.CTCToolkit");
            args.add("-Djava.awt.graphicsenv=net.java.openjdk.cacio.ctc.CTCGraphicsEnvironment");
            args.add("-Xbootclasspath/p:" + cacioClassPath);
        } else {
            args.add("-Dawt.toolkit=com.github.caciocavallosilano.cacio.ctc.CTCToolkit");
            args.add("-Djava.awt.graphicsenv=com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment");
            if (LibPath.CACIO_17_AGENT != null && LibPath.CACIO_17_AGENT.isFile()) {
                args.add("-javaagent:" + LibPath.CACIO_17_AGENT.getAbsolutePath());
            }
            args.add("--add-exports=java.desktop/java.awt=ALL-UNNAMED");
            args.add("--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED");
            args.add("--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED");
            args.add("--add-exports=java.desktop/sun.java2d=ALL-UNNAMED");
            args.add("--add-exports=java.desktop/java.awt.dnd.peer=ALL-UNNAMED");
            args.add("--add-exports=java.desktop/sun.awt=ALL-UNNAMED");
            args.add("--add-exports=java.desktop/sun.awt.event=ALL-UNNAMED");
            args.add("--add-exports=java.desktop/sun.awt.datatransfer=ALL-UNNAMED");
            args.add("--add-exports=java.desktop/sun.font=ALL-UNNAMED");
            args.add("--add-exports=java.base/sun.security.action=ALL-UNNAMED");
            args.add("--add-opens=java.base/java.util=ALL-UNNAMED");
            args.add("--add-opens=java.desktop/java.awt=ALL-UNNAMED");
            args.add("--add-opens=java.desktop/sun.font=ALL-UNNAMED");
            args.add("--add-opens=java.desktop/sun.java2d=ALL-UNNAMED");
            args.add("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED");
            args.add("--add-opens=java.base/java.net=ALL-UNNAMED");
            args.add("-Xbootclasspath/a:" + cacioClassPath);
        }

        Logging.i(TAG, "Enabled Cacio AWT backend from " + cacioDir.getAbsolutePath());
        return args;
    }

    @NonNull
    private String buildJarClassPath(@Nullable File directory) {
        if (directory == null || !directory.isDirectory()) return "";
        File[] jars = directory.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null || jars.length == 0) return "";

        ArrayList<File> ordered = new ArrayList<>();
        addNamedJarFirst(ordered, jars, "ResConfHack.jar");
        addNamedJarFirst(ordered, jars, "cacio-androidnw-1.10-SNAPSHOT.jar");
        addNamedJarFirst(ordered, jars, "cacio-shared-1.10-SNAPSHOT.jar");

        java.util.Arrays.sort(jars, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File jar : jars) {
            if (!ordered.contains(jar)) ordered.add(jar);
        }

        StringBuilder out = new StringBuilder();
        for (File jar : ordered) {
            if (out.length() > 0) out.append(':');
            out.append(jar.getAbsolutePath());
        }
        return out.toString();
    }

    private void addNamedJarFirst(
            @NonNull ArrayList<File> out,
            @NonNull File[] jars,
            @NonNull String fileName
    ) {
        for (File jar : jars) {
            if (jar != null && fileName.equals(jar.getName()) && !out.contains(jar)) {
                out.add(jar);
                return;
            }
        }
    }

    @NonNull
    private String buildNativeLibraryPath(
            @NonNull File runtimeDir,
            @NonNull File lwjglNativesDir,
            @NonNull String jnaNativePath,
            @Nullable RendererInterface renderer,
            @NonNull FFmpegPluginCompat.Result ffmpeg
    ) {
        File runtimeLibDir = resolveRuntimeLibDir(runtimeDir);
        File serverDir = new File(runtimeLibDir, "server");
        File clientDir = new File(runtimeLibDir, "client");
        File jvmDir = new File(serverDir, "libjvm.so").isFile() ? serverDir : clientDir;
        String systemLib = Build.SUPPORTED_64_BIT_ABIS != null && Build.SUPPORTED_64_BIT_ABIS.length > 0 ? "lib64" : "lib";

        StringBuilder builder = new StringBuilder();
        addPath(builder, jvmDir);
        addPath(builder, new File(runtimeLibDir, "jli"));
        addPath(builder, runtimeLibDir);
        addPath(builder, lwjglNativesDir);
        if (ffmpeg.available && ffmpeg.libraryPath != null && !ffmpeg.libraryPath.trim().isEmpty()) {
            addPath(builder, new File(ffmpeg.libraryPath));
        }
        addPathList(builder, jnaNativePath);
        if (renderer != null) {
            for (File rendererPath : renderer.getLibrarySearchPaths()) {
                addPath(builder, rendererPath);
            }
            for (File driverPath : DriverPluginManager.getSelectedDriverLibrarySearchPaths(context, renderer, useSystemVulkanDriverOverride)) {
                addPath(builder, driverPath);
            }
        }
        addPath(builder, new File("/system/" + systemLib));
        addPath(builder, new File("/vendor/" + systemLib));
        addPath(builder, new File("/vendor/" + systemLib + "/hw"));
        if (PathManager.DIR_RUNTIME_MOD != null) addPath(builder, PathManager.DIR_RUNTIME_MOD);
        addPath(builder, new File(PathManager.DIR_NATIVE_LIB));
        return builder.toString();
    }


    @NonNull
    private String buildBootLibraryPath(@NonNull File runtimeDir) {
        File runtimeLibDir = resolveRuntimeLibDir(runtimeDir);
        StringBuilder builder = new StringBuilder();

        addPath(builder, runtimeLibDir);
        addPath(builder, new File(runtimeLibDir, "server"));
        addPath(builder, new File(runtimeLibDir, "client"));
        addPath(builder, new File(runtimeLibDir, "jli"));
        addPath(builder, new File(PathManager.DIR_NATIVE_LIB));

        return builder.toString();
    }

    @NonNull
    private File resolveRuntimeLibDir(@NonNull File runtimeDir) {
        for (String part : getRuntimeArchCandidates()) {
            File candidate = new File(runtimeDir, "lib/" + part);
            if (candidate.isDirectory()) return candidate;
        }
        return new File(runtimeDir, "lib");
    }

    @NonNull
    private List<String> getRuntimeArchCandidates() {
        ArrayList<String> candidates = new ArrayList<>();
        String arch = Architecture.archAsString(Architecture.getDeviceArchitecture());

        addUnique(candidates, arch);

        if (Architecture.getDeviceArchitecture() == Architecture.ARCH_ARM64 || arch.contains("arm64") || arch.contains("aarch64")) {
            addUnique(candidates, "aarch64");
            addUnique(candidates, "arm64");
            addUnique(candidates, "arm64-v8a");
        } else if (Architecture.getDeviceArchitecture() == Architecture.ARCH_ARM || arch.contains("arm")) {
            addUnique(candidates, "arm");
            addUnique(candidates, "armeabi-v7a");
        } else if (Architecture.getDeviceArchitecture() == Architecture.ARCH_X86) {
            addUnique(candidates, "i386");
            addUnique(candidates, "i486");
            addUnique(candidates, "i586");
            addUnique(candidates, "x86");
        } else if (arch.contains("x86_64") || arch.contains("amd64")) {
            addUnique(candidates, "amd64");
            addUnique(candidates, "x86_64");
        }

        return candidates;
    }

    private void addUnique(@NonNull List<String> list, @Nullable String value) {
        if (value == null || value.trim().isEmpty()) return;
        for (String part : value.split("/")) {
            if (!part.trim().isEmpty() && !list.contains(part)) list.add(part);
        }
    }

    private void addPath(@NonNull StringBuilder builder, @NonNull File dir) {
        if (!dir.isDirectory()) return;
        String path = dir.getAbsolutePath();
        if (containsPath(builder.toString(), path)) return;
        if (builder.length() > 0) builder.append(':');
        builder.append(path);
    }

    private boolean containsPath(@NonNull String pathList, @NonNull String path) {
        if (pathList.isEmpty()) return false;
        String[] parts = pathList.split(":");
        for (String part : parts) {
            if (path.equals(part)) return true;
        }
        return false;
    }

    private void addLegacySkinProxyGameArgsIfNeeded(
            @NonNull ArrayList<String> args,
            @NonNull JSONObject versionJson
    ) {
        if (!shouldUseLegacySkinProxy(versionJson)) return;

        int port = getActiveLegacySkinProxyPort();
        if (port <= 0) {
            Logging.i(TAG, "Legacy skin proxy game args skipped: proxy server is not active.");
            return;
        }

        /*
         * Minecraft 1.6.x and nearby legacy clients do not always honor the JVM
         * http.proxyHost/http.proxyPort properties for skin downloads. They build
         * the skin URL with Minecraft.getMinecraft().getProxy(), which is controlled
         * by the launcher's game arguments instead. Without these args, the client
         * still tries to resolve skins.minecraft.net directly and falls back to the
         * default Steve skin.
         */
        putGameOptionValue(args, "--proxyHost", "127.0.0.1");
        putGameOptionValue(args, "--proxyPort", String.valueOf(port));

        Logging.i(TAG, "Legacy skin proxy game args active: --proxyHost 127.0.0.1 --proxyPort " + port);
    }

    private void putGameOptionValue(
            @NonNull ArrayList<String> args,
            @NonNull String option,
            @NonNull String value
    ) {
        for (int i = 0; i < args.size(); i++) {
            if (!option.equals(args.get(i))) continue;

            if (i + 1 < args.size() && !args.get(i + 1).startsWith("--")) {
                args.set(i + 1, value);
            } else {
                args.add(i + 1, value);
            }
            return;
        }

        args.add(option);
        args.add(value);
    }

    @NonNull
    private ArrayList<String> buildGameArgs(
            @NonNull JSONObject versionJson,
            @NonNull Map<String, String> replacements
    ) throws Exception {
        if (isBetterThanAdventureProfile(versionJson)) {
            return buildBetterThanAdventureArgs(replacements);
        }

        ArrayList<String> args = new ArrayList<>();
        JSONObject arguments = versionJson.optJSONObject("arguments");
        if (arguments != null) {
            JSONArray game = arguments.optJSONArray("game");
            if (game != null) {
                addArgumentArray(args, game, replacements);
                sanitizeAndRepairGameArgs(args, replacements);
                addLegacySkinProxyGameArgsIfNeeded(args, versionJson);
                return args;
            }
        }

        String legacy = versionJson.optString("minecraftArguments", "");
        if (!legacy.isEmpty()) {
            for (String part : splitLegacyArguments(legacy)) {
                if (!part.trim().isEmpty()) args.add(replaceTokens(part.trim(), replacements));
            }
        }

        sanitizeAndRepairGameArgs(args, replacements);
        addLegacySkinProxyGameArgsIfNeeded(args, versionJson);
        return args;
    }

    @NonNull
    private List<String> splitLegacyArguments(@NonNull String raw) {
        ArrayList<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
                continue;
            }
            if (Character.isWhitespace(c) && !inQuote) {
                if (current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) out.add(current.toString());
        return out;
    }

    @NonNull
    private ArrayList<String> buildBetterThanAdventureArgs(@NonNull Map<String, String> replacements) {
        ArrayList<String> args = new ArrayList<>();
        String playerName = replacements.get("${auth_player_name}");
        String session = replacements.get("${auth_session}");

        if (playerName != null && !playerName.trim().isEmpty()) {
            args.add(playerName);
        }
        if (session != null && !session.trim().isEmpty()) {
            args.add(session);
        }

        Logging.i(TAG, "Using strict BTA launch arguments: username/session only");
        return args;
    }

    private boolean isBetterThanAdventureProfile(@NonNull JSONObject versionJson) {
        String id = versionJson.optString("id", "").toLowerCase(Locale.ROOT);
        String mainClass = versionJson.optString("mainClass", "").toLowerCase(Locale.ROOT);
        if (id.startsWith("bta-") || id.contains("betterthanadventure") || id.contains("better-than-adventure")) {
            return true;
        }

        JSONArray libraries = versionJson.optJSONArray("libraries");
        if (libraries != null) {
            for (int i = 0; i < libraries.length(); i++) {
                JSONObject library = libraries.optJSONObject(i);
                if (library == null) continue;
                String name = library.optString("name", "").toLowerCase(Locale.ROOT);
                if (name.startsWith("bta-client:bta-client:") || name.contains("betterthanadventure")) {
                    return true;
                }
            }
        }

        return mainClass.equals("net.minecraft.client.minecraft") && id.contains("bta");
    }

    private void sanitizeAndRepairGameArgs(
            @NonNull ArrayList<String> args,
            @NonNull Map<String, String> replacements
    ) {
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg == null || arg.trim().isEmpty()) {
                args.remove(i);
                i--;
                continue;
            }

            if (arg.contains("${")) {
                Logging.i(TAG, "Removing unresolved launch argument: " + arg);
                args.remove(i);
                if (i > 0 && args.get(i - 1).startsWith("--")) {
                    Logging.i(TAG, "Removing option for unresolved value: " + args.get(i - 1));
                    args.remove(i - 1);
                    i -= 2;
                } else {
                    i--;
                }
            }
        }

        ensureOptionHasValue(args, "--gameDir", replacements.get("${game_directory}"));
        ensureOptionHasValue(args, "--assetsDir", replacements.get("${game_assets}"));
        ensureOptionHasValue(args, "--assetIndex", replacements.get("${assets_index_name}"));
    }

    private void ensureOptionHasValue(
            @NonNull ArrayList<String> args,
            @NonNull String option,
            @Nullable String fallbackValue
    ) {
        if (fallbackValue == null || fallbackValue.trim().isEmpty()) return;

        for (int i = 0; i < args.size(); i++) {
            if (!option.equals(args.get(i))) continue;

            if (i + 1 >= args.size() || args.get(i + 1).startsWith("--")) {
                Logging.i(TAG, "Repairing missing launch argument value for " + option + " -> " + fallbackValue);
                args.add(i + 1, fallbackValue);
            }
            return;
        }
    }

    private void addArgumentArray(
            @NonNull ArrayList<String> out,
            @NonNull JSONArray array,
            @NonNull Map<String, String> replacements
    ) throws Exception {
        for (int i = 0; i < array.length(); i++) {
            Object value = array.get(i);
            if (value instanceof String) {
                out.add(replaceTokens((String) value, replacements));
            } else if (value instanceof JSONObject) {
                JSONObject object = (JSONObject) value;
                if (!isRulesAllowed(object.optJSONArray("rules"))) continue;
                Object v = object.opt("value");
                if (v instanceof String) {
                    out.add(replaceTokens((String) v, replacements));
                } else if (v instanceof JSONArray) {
                    JSONArray valueArray = (JSONArray) v;
                    for (int j = 0; j < valueArray.length(); j++) {
                        out.add(replaceTokens(valueArray.getString(j), replacements));
                    }
                }
            }
        }
    }

    private boolean isRulesAllowed(@Nullable JSONArray rules) {
        if (rules == null || rules.length() == 0) return true;

        boolean allowed = false;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule == null || !doesRuleApply(rule)) continue;

            String action = rule.optString("action", "allow");
            allowed = "allow".equals(action);
        }
        return allowed;
    }

    private boolean doesRuleApply(@NonNull JSONObject rule) {
        JSONObject os = rule.optJSONObject("os");
        if (os != null && !"linux".equals(os.optString("name", "linux"))) {
            return false;
        }

        JSONObject features = rule.optJSONObject("features");
        if (features != null) {
            Iterator<String> keys = features.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                boolean required = features.optBoolean(key, false);
                if (getFeatureFlag(key) != required) return false;
            }
        }

        return true;
    }

    private boolean getFeatureFlag(@NonNull String key) {
        // Match the launcher features we actually support.
        if ("has_custom_resolution".equals(key)) return true;

        // These must stay false unless JavaLauncher has explicit UI/state for them.
        if ("is_demo_user".equals(key)) return false;
        if ("is_quick_play_singleplayer".equals(key)) return false;
        if ("is_quick_play_multiplayer".equals(key)) return false;
        if ("is_quick_play_realms".equals(key)) return false;

        // Unknown feature-gated args should not be included.
        return false;
    }

    @NonNull
    private String replaceTokens(@NonNull String value, @NonNull Map<String, String> replacements) {
        String out = value;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            out = out.replace(entry.getKey(), entry.getValue());
        }
        return out;
    }

    private void removeClasspathArgs(@NonNull ArrayList<String> args) {
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if ("-cp".equals(arg) || "-classpath".equals(arg) || "--class-path".equals(arg)) {
                args.remove(i);
                if (i < args.size()) args.remove(i);
                i--;
            }
        }
    }

    private void purgeManagedArgs(@NonNull ArrayList<String> args) {
        purgeArg(args, "-Xms");
        purgeArg(args, "-Xmx");
        purgeArg(args, "-d32");
        purgeArg(args, "-d64");
        purgeArg(args, "-Xint");
        purgeArg(args, "-XX:+UseTransparentHugePages");
        purgeArg(args, "-XX:+UseLargePagesInMetaspace");
        purgeArg(args, "-XX:+UseLargePages");
        purgeArg(args, "-Djava.library.path=");
        purgeArg(args, "-Djna.boot.library.path=");
        purgeArg(args, "-Djna.library.path=");
        purgeArg(args, "-Djna.nounpack=");
        purgeArg(args, "-Djna.nosys=");
        purgeArg(args, "-Djna.debug_load=");
        purgeArg(args, "-Dcom.sun.jna.useProtected=");
        purgeArg(args, "-Djna.tmpdir=");
        purgeArg(args, "-Dorg.lwjgl.librarypath=");
        purgeArg(args, "-Dorg.lwjgl.sdl.libname=");
        purgeArg(args, "-Dorg.lwjgl.opengl.libname=");
        purgeArg(args, "-Dorg.lwjgl.opengl.contextAPI=");
        purgeArg(args, "-Dorg.lwjgl.egl.libname=");
        purgeArg(args, "-Dorg.lwjgl.freetype.libname=");
        purgeArg(args, "-Dorg.lwjgl.system.SharedLibraryExtractPath=");
        purgeArg(args, "-Dio.netty.native.workdir=");
        purgeArg(args, "-XX:ActiveProcessorCount=");
    }

    private void purgeArg(@NonNull ArrayList<String> args, @NonNull String prefix) {
        args.removeIf(arg -> arg.startsWith(prefix));
    }

    private void copyFile(@NonNull File source, @NonNull File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Unable to create directory: " + parent.getAbsolutePath());
        }

        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        }
    }

    @NonNull
    private String readFile(@NonNull File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            int offset = 0;
            while (offset < buffer.length) {
                int read = input.read(buffer, offset, buffer.length - offset);
                if (read < 0) break;
                offset += read;
            }
            return new String(buffer, 0, offset, StandardCharsets.UTF_8);
        }
    }

    private void writeDebugLaunchFile(
            @NonNull File file,
            @NonNull File runtimeDir,
            @NonNull String mainClass,
            @NonNull String classPath,
            @NonNull List<String> jvmArgs,
            @NonNull List<String> gameArgs
    ) {
        try {
            StringBuilder out = new StringBuilder();
            out.append("version=").append(versionId).append('\n');
            out.append("accountLoaded=").append(account != null).append('\n');
            out.append("accountType=").append(account != null ? account.accountType : "none").append('\n');
            out.append("hasMinecraftSession=").append(hasValidMinecraftSession()).append('\n');
            out.append("customSkinEnabled=").append(isCustomSkinEnabledForLaunch(resolvePlayerName())).append('\n');
            out.append("runtime=").append(runtimeDir.getAbsolutePath()).append('\n');
            out.append("mainClass=").append(mainClass).append('\n');
            out.append("classpath=").append(classPath).append('\n');
            out.append("\nJVM ARGS\n");
            for (String arg : jvmArgs) out.append(arg).append('\n');
            out.append("\nGAME ARGS\n");
            for (String arg : gameArgs) out.append(arg).append('\n');
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(out.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Failed to write debug launch plan", throwable);
        }
    }
    private void addReplayModFFmpegJvmArgs(
            @NonNull ArrayList<String> args,
            @NonNull FFmpegPluginCompat.Result ffmpeg
    ) {
        if (!ffmpeg.replayModPresent) {
            return;
        }

        if (!ffmpeg.available || ffmpeg.executablePath == null || ffmpeg.executablePath.trim().isEmpty()) {
            Logging.i(TAG, "Replay Mod is installed, but JavaLauncher FFmpeg plugin was not found. "
                    + (ffmpeg.errorMessage != null ? ffmpeg.errorMessage : ""));
            return;
        }

        addJvmArgIfMissing(args, "-Djavalauncher.ffmpeg.path=" + ffmpeg.executablePath);
        addJvmArgIfMissing(args, "-Dreplaymod.ffmpeg.path=" + ffmpeg.executablePath);
        addJvmArgIfMissing(args, "-Dffmpeg.location=" + ffmpeg.executablePath);

        // Compatibility alias for Android Replay Mod/DroidBridge-derived patches that still read this key.
        addJvmArgIfMissing(args, "-Ddroidbridge.ffmpeg.path=" + ffmpeg.executablePath);

        Logging.i(TAG, "Applied Replay Mod FFmpeg JVM path from " + ffmpeg.packageName
                + ": " + ffmpeg.executablePath);
    }
}

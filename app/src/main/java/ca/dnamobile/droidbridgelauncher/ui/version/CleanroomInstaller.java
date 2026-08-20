/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, or any third-party project.
 */

package ca.dnamobile.droidbridgelauncher.ui.version;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

import ca.dnamobile.droidbridgelauncher.data.model.MinecraftVersion;
import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.launcher.RuntimeCompat;
import ca.dnamobile.droidbridgelauncher.runtime.multirt.MultiRTUtils;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;

/** Installs Cleanroom as a first-class 1.12.2 loader profile. */
public final class CleanroomInstaller {
    private static final String TAG = "CleanroomInstaller";
    private static final String INSTALLER_URL =
            "https://hmcl-dev.github.io/metadata/cleanroom/files/cleanroom-%1$s-installer.jar";
    private static final int BUFFER_SIZE = 64 * 1024;

    private CleanroomInstaller() {
    }

    public static final class InstallResult {
        private final String minecraftVersionId;
        private final String loaderVersion;
        private final String cleanroomVersionId;
        private final int requiredJava;

        private InstallResult(
                @NonNull String minecraftVersionId,
                @NonNull String loaderVersion,
                @NonNull String cleanroomVersionId,
                int requiredJava
        ) {
            this.minecraftVersionId = minecraftVersionId;
            this.loaderVersion = loaderVersion;
            this.cleanroomVersionId = cleanroomVersionId;
            this.requiredJava = requiredJava;
        }

        @NonNull
        public String getMinecraftVersionId() {
            return minecraftVersionId;
        }

        @NonNull
        public String getLoaderVersion() {
            return loaderVersion;
        }

        @NonNull
        public String getCleanroomVersionId() {
            return cleanroomVersionId;
        }

        public int getRequiredJava() {
            return requiredJava;
        }
    }

    @NonNull
    public static InstallResult installCleanroomVersion(
            @NonNull Context context,
            @NonNull MinecraftVersion vanillaVersion,
            @Nullable String requestedLoaderVersion,
            @Nullable MinecraftVersionInstaller.InstallProgressListener listener
    ) throws Exception {
        PathManager.initContextConstants(context);

        String minecraftVersion = vanillaVersion.getId().trim();
        if (!CleanroomSupport.supportsMinecraftVersion(minecraftVersion)) {
            throw new IllegalArgumentException("Cleanroom only supports Minecraft "
                    + CleanroomSupport.MINECRAFT_VERSION + ".");
        }

        String loaderVersion = requestedLoaderVersion == null ? "" : requestedLoaderVersion.trim();
        if (loaderVersion.isEmpty()) {
            throw new IllegalArgumentException("A Cleanroom loader version is required.");
        }

        ensureVanillaBase(minecraftVersion);
        int requiredJava = CleanroomSupport.requiredJavaForVersion(loaderVersion);

        String reusableVersionId = findInstalledCleanroomProfile(loaderVersion);
        if (reusableVersionId != null) {
            notify(listener, 76, "Reusing Cleanroom " + loaderVersion + " profile...");
            patchInstalledProfile(reusableVersionId, loaderVersion);
            notify(listener, 100, "Cleanroom " + loaderVersion + " is ready with Java " + requiredJava + ".");
            return new InstallResult(minecraftVersion, loaderVersion, reusableVersionId, requiredJava);
        }

        notify(listener, 72, "Downloading Cleanroom " + loaderVersion + " installer...");
        File installer = new File(
                PathManager.DIR_CACHE,
                "cleanroom-" + safeFilePart(loaderVersion) + "-installer.jar"
        );
        downloadFileIfNeeded(installer, String.format(Locale.ROOT, INSTALLER_URL, loaderVersion));
        ensureLauncherProfiles();

        File runtime = resolveExactInstallerRuntime(requiredJava);
        ArrayList<String> commonArgs = new ArrayList<>();
        commonArgs.add("-Djava.awt.headless=true");
        commonArgs.add("-Duser.home=" + PathManager.DIR_MINECRAFT_HOME);
        commonArgs.add("-Djava.io.tmpdir=" + PathManager.DIR_CACHE.getAbsolutePath());
        commonArgs.add("-jar");
        commonArgs.add(installer.getAbsolutePath());
        commonArgs.add("--installClient");

        notify(listener, 80, "Installing Cleanroom " + loaderVersion + " with Java " + requiredJava + "...");
        ArrayList<String> argsWithPath = new ArrayList<>(commonArgs);
        argsWithPath.add(PathManager.DIR_MINECRAFT_HOME);

        int exitCode = InstallerProcessRunner.launch(
                context,
                "cleanroom-installer-" + loaderVersion,
                runtime,
                new File(PathManager.DIR_MINECRAFT_HOME),
                argsWithPath,
                81,
                91,
                (progress, status) -> notify(listener, progress, status)
        );

        if (exitCode != 0) {
            // Some Forge-derived installer builds accept --installClient but
            // infer the working directory instead of accepting a path argument.
            notify(listener, 88, "Retrying Cleanroom installer in compatibility mode...");
            exitCode = InstallerProcessRunner.launch(
                    context,
                    "cleanroom-installer-compat-" + loaderVersion,
                    runtime,
                    new File(PathManager.DIR_MINECRAFT_HOME),
                    commonArgs,
                    88,
                    94,
                    (progress, status) -> notify(listener, progress, status)
            );
        }

        if (exitCode != 0) {
            throw new IllegalStateException("Cleanroom installer exited with code " + exitCode
                    + ". Check latestlog.txt for installer output.");
        }

        String installedVersionId = findInstalledCleanroomProfile(loaderVersion);
        if (installedVersionId == null) {
            throw new IllegalStateException("Cleanroom installer finished, but no matching Cleanroom "
                    + loaderVersion + " version JSON was found.");
        }

        // Do not flatten this profile. Cleanroom relies on its generated child
        // libraries/arguments replacing selected inherited 1.12.2 libraries.
        patchInstalledProfile(installedVersionId, loaderVersion);

        notify(listener, 100, "Cleanroom " + loaderVersion + " installed with Java " + requiredJava + ".");
        return new InstallResult(minecraftVersion, loaderVersion, installedVersionId, requiredJava);
    }

    private static void ensureVanillaBase(@NonNull String minecraftVersion) {
        File versionDir = MinecraftVersionInstaller.getVersionDirectory(minecraftVersion);
        File json = new File(versionDir, minecraftVersion + ".json");
        File jar = new File(versionDir, minecraftVersion + ".jar");
        if (!json.isFile() || !jar.isFile()) {
            throw new IllegalStateException("Minecraft " + minecraftVersion
                    + " must be installed before Cleanroom. Missing "
                    + (!json.isFile() ? json.getAbsolutePath() : jar.getAbsolutePath()));
        }
    }

    @Nullable
    private static String findInstalledCleanroomProfile(@NonNull String requestedVersion) {
        File versionsDir = MinecraftVersionInstaller.getVersionsDirectory();
        File[] directories = versionsDir.listFiles(File::isDirectory);
        if (directories == null) return null;

        String bestId = null;
        long bestModified = Long.MIN_VALUE;

        for (File directory : directories) {
            String id = directory.getName();
            File jsonFile = new File(directory, id + ".json");
            if (!jsonFile.isFile()) continue;

            try {
                JSONObject json = new JSONObject(readString(jsonFile));
                if (!CleanroomSupport.isCleanroomProfile(id, json)) continue;

                String detected = CleanroomSupport.detectVersion(id, json);
                boolean versionMatches = requestedVersion.equalsIgnoreCase(detected)
                        || id.toLowerCase(Locale.ROOT).contains(requestedVersion.toLowerCase(Locale.ROOT))
                        || json.toString().toLowerCase(Locale.ROOT).contains(requestedVersion.toLowerCase(Locale.ROOT));
                if (!versionMatches) continue;

                long modified = Math.max(directory.lastModified(), jsonFile.lastModified());
                if (bestId == null || modified > bestModified) {
                    bestId = id;
                    bestModified = modified;
                }
            } catch (Throwable throwable) {
                Logging.i(TAG, "Ignoring unreadable version profile " + id + ": " + throwable.getMessage());
            }
        }

        return bestId;
    }

    private static void patchInstalledProfile(
            @NonNull String versionId,
            @NonNull String cleanroomVersion
    ) throws Exception {
        File jsonFile = new File(MinecraftVersionInstaller.getVersionDirectory(versionId), versionId + ".json");
        JSONObject json = new JSONObject(readString(jsonFile));
        CleanroomSupport.markInstalledProfile(json, cleanroomVersion);

        // Cleanroom uses the vanilla 1.12.2 client classes but some installer
        // profiles omit both normal launcher linkage fields. Persist the
        // official-launcher-compatible jar mapping so future launches do not
        // depend on profile-shape heuristics.
        if (json.optString("jar", "").trim().isEmpty()
                && json.optString("inheritsFrom", "").trim().isEmpty()) {
            json.put("jar", CleanroomSupport.MINECRAFT_VERSION);
        }

        writeString(jsonFile, json.toString(2));
        Logging.i(TAG, "Marked Cleanroom profile " + versionId + " for Java "
                + CleanroomSupport.requiredJavaForVersion(cleanroomVersion));
    }

    @NonNull
    private static File resolveExactInstallerRuntime(int javaMajor) {
        String runtimeName = javaMajor >= 25 ? "Internal-25" : "Internal-21";
        File runtime = MultiRTUtils.getRuntimeDir(runtimeName);
        File normalized = RuntimeCompat.normalizeRuntimeHome(runtimeName, runtime, javaMajor);
        File java = RuntimeCompat.findJavaBinary(normalized);

        if (!normalized.isDirectory() || java == null || !java.isFile()) {
            throw new IllegalStateException(runtimeName + " is required for Cleanroom but is not installed: "
                    + RuntimeCompat.describeRuntimeState(runtimeName, runtime));
        }
        return normalized;
    }

    private static void ensureLauncherProfiles() throws Exception {
        File profileFile = new File(PathManager.DIR_MINECRAFT_HOME, "launcher_profiles.json");
        if (profileFile.isFile()) return;

        JSONObject defaultProfile = new JSONObject();
        defaultProfile.put("lastVersionId", CleanroomSupport.MINECRAFT_VERSION);

        JSONObject profiles = new JSONObject();
        profiles.put("default", defaultProfile);

        JSONObject root = new JSONObject();
        root.put("profiles", profiles);
        root.put("selectedProfile", "default");
        writeString(profileFile, root.toString(2));
    }

    private static void downloadFileIfNeeded(@NonNull File target, @NonNull String url) throws Exception {
        if (target.isFile() && target.length() > 64 * 1024L) return;

        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("Unable to create cache directory: " + parent.getAbsolutePath());
        }

        File temporary = new File(target.getAbsolutePath() + ".part");
        if (temporary.exists() && !temporary.delete()) {
            throw new IllegalStateException("Unable to replace partial download: " + temporary.getAbsolutePath());
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(120000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "DroidBridge/1.0");
        connection.setRequestMethod("GET");

        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            String error = readStream(connection.getErrorStream());
            connection.disconnect();
            throw new IllegalStateException("HTTP " + code + " while downloading Cleanroom installer: " + error);
        }

        try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(temporary)) {
            copy(input, output);
        } finally {
            connection.disconnect();
        }

        if (temporary.length() <= 64 * 1024L) {
            throw new IllegalStateException("Downloaded Cleanroom installer is unexpectedly small ("
                    + temporary.length() + " bytes).");
        }

        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("Unable to replace cached installer: " + target.getAbsolutePath());
        }
        if (!temporary.renameTo(target)) {
            try (InputStream input = new FileInputStream(temporary); FileOutputStream output = new FileOutputStream(target)) {
                copy(input, output);
            }
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
        }
    }

    @NonNull
    private static String readStream(@Nullable InputStream stream) throws Exception {
        if (stream == null) return "";
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copy(input, output);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    @NonNull
    private static String readString(@NonNull File file) throws Exception {
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copy(input, output);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void writeString(@NonNull File file, @NonNull String value) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("Unable to create directory: " + parent.getAbsolutePath());
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void copy(@NonNull InputStream input, @NonNull java.io.OutputStream output) throws Exception {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    @NonNull
    private static String safeFilePart(@NonNull String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void notify(
            @Nullable MinecraftVersionInstaller.InstallProgressListener listener,
            int progress,
            @NonNull String status
    ) {
        if (listener != null) listener.onProgress(Math.max(0, Math.min(100, progress)), status);
        Logging.i(TAG, status);
    }
}

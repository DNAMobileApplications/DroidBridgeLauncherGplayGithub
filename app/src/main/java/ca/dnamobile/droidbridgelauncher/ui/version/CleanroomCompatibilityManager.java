/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 */
package ca.dnamobile.droidbridgelauncher.ui.version;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.instance.LauncherInstance;
import ca.dnamobile.droidbridgelauncher.modmanager.CurseForgeApiClient;
import ca.dnamobile.droidbridgelauncher.modmanager.ModManagerContentType;
import ca.dnamobile.droidbridgelauncher.modmanager.ModManagerManifest;
import ca.dnamobile.droidbridgelauncher.modmanager.ModManagerSource;
import ca.dnamobile.droidbridgelauncher.modmanager.ModpackUpdateManager;
import ca.dnamobile.droidbridgelauncher.modmanager.ModrinthFile;
import ca.dnamobile.droidbridgelauncher.modmanager.ModrinthProject;
import ca.dnamobile.droidbridgelauncher.modmanager.ModrinthVersion;

/**
 * Prepares legacy Forge 1.12.2 modpacks for Cleanroom without deleting user files.
 *
 * Downloads are staged first. Existing incompatible JARs are then moved into a
 * dated backup folder and the staged replacements are committed. Any failure
 * during the commit restores the original files before returning an error.
 */
public final class CleanroomCompatibilityManager {
    private static final String TAG = "CleanroomCompat";
    private static final String MINECRAFT_VERSION = "1.12.2";
    private static final String FORGE_LOADER = "forge";

    // Required for normal legacy Forge -> Cleanroom migrations.
    private static final ProjectSpec FUGUE = new ProjectSpec(
            "1005815", "Fugue", new String[]{"fugue"}
    );
    private static final ProjectSpec SCALAR_LEGACY = new ProjectSpec(
            "1235372", "Scalar Legacy", new String[]{"scalar legacy", "scalar-legacy", "scalar_legacy"}
    );
    private static final ProjectSpec FORGELIN_CONTINUOUS = new ProjectSpec(
            "456403", "Forgelin-Continuous", new String[]{"forgelin-continuous", "forgelin_continuous"}
    );
    private static final ProjectSpec LIBRARIANLIB_CONTINUOUS = new ProjectSpec(
            "1058274", "LibrarianLib-Continuous", new String[]{"librarianlib-continuous", "librarianlib_continuous"}
    );

    // RLCraft compatibility preset.
    private static final ProjectSpec HAD_ENOUGH_ITEMS = new ProjectSpec(
            "557549", "Had Enough Items", new String[]{"hadenoughitems", "had-enough-items"}
    );
    private static final ProjectSpec QUARK_ROTN = new ProjectSpec(
            "417392", "Quark: RotN Edition", new String[]{"quarkrotn", "quark-rotn", "quark_rotn"}
    );
    private static final ProjectSpec RENDERLIB = new ProjectSpec(
            "624967", "RenderLib", new String[]{"renderlib"}, true
    );
    private static final ProjectSpec RECURRENT_COMPLEX = new ProjectSpec(
            "223150", "Recurrent Complex", new String[]{"recurrentcomplex"}, true,
            "1.4.8.5"
    );
    private static final ProjectSpec RLTWEAKER = new ProjectSpec(
            "734347", "RLTweaker2", new String[]{"rltweaker"}, true
    );

    private CleanroomCompatibilityManager() {
    }

    public interface ProgressListener {
        void onProgress(int progress, @NonNull String message);
    }

    public static final class Result {
        public final boolean rlcraftPreset;
        @Nullable
        public final File backupDirectory;
        @NonNull
        public final ArrayList<String> disabledFiles;
        @NonNull
        public final ArrayList<String> installedFiles;
        @NonNull
        public final ArrayList<String> installedProjects;

        private Result(
                boolean rlcraftPreset,
                @Nullable File backupDirectory,
                @NonNull ArrayList<String> disabledFiles,
                @NonNull ArrayList<String> installedFiles,
                @NonNull ArrayList<String> installedProjects
        ) {
            this.rlcraftPreset = rlcraftPreset;
            this.backupDirectory = backupDirectory;
            this.disabledFiles = disabledFiles;
            this.installedFiles = installedFiles;
            this.installedProjects = installedProjects;
        }

        @NonNull
        public String getProfileName() {
            return rlcraftPreset ? "RLCraft" : "Forge 1.12.2";
        }

        @NonNull
        public String getSummary() {
            StringBuilder summary = new StringBuilder();
            summary.append(getProfileName()).append(" compatibility prepared");
            if (!installedFiles.isEmpty()) {
                summary.append("; installed ").append(installedFiles.size()).append(" compatibility mod");
                if (installedFiles.size() != 1) summary.append('s');
            }
            if (!disabledFiles.isEmpty()) {
                summary.append("; backed up ").append(disabledFiles.size()).append(" conflicting mod");
                if (disabledFiles.size() != 1) summary.append('s');
            }
            return summary.append('.').toString();
        }
    }

    public static boolean isRepairEligible(@Nullable LauncherInstance instance) {
        if (instance == null || !instance.isIsolated()) return false;
        if (!CleanroomSupport.supportsMinecraftVersion(instance.getMinecraftVersionId())) return false;
        String loader = normalize(instance.getLoader());
        String base = normalize(instance.getBaseVersionId());
        return loader.contains("cleanroom") || base.contains("cleanroom");
    }

    public static boolean isRLCraft(@NonNull LauncherInstance instance) {
        String instanceName = normalize(instance.getName());
        if (instanceName.contains("rlcraft")) return true;

        try {
            ModpackUpdateManager.InstalledModpackInfo info = ModpackUpdateManager.readInstalledModpackInfo(
                    instance.getRootDirectory(), instance.getGameDirectory());
            if (info != null && normalize(info.displayTitle).contains("rlcraft")) return true;
        } catch (Throwable ignored) {
        }

        File modsDirectory = new File(instance.getGameDirectory(), "mods");
        File[] files = modsDirectory.listFiles();
        if (files == null) return false;
        boolean hasRlMixins = false;
        boolean hasRlTweaker = false;
        boolean hasLycanites = false;
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            String name = normalize(file.getName());
            if (name.contains("rlmixins")) hasRlMixins = true;
            if (name.contains("rltweaker")) hasRlTweaker = true;
            if (name.contains("lycanitesmobs")) hasLycanites = true;
        }
        return hasRlMixins && hasRlTweaker && hasLycanites;
    }

    @NonNull
    public static Result prepare(
            @NonNull Context context,
            @NonNull LauncherInstance instance,
            @Nullable ProgressListener listener
    ) throws Exception {
        File gameDirectory = instance.getGameDirectory();
        File modsDirectory = new File(gameDirectory, "mods");
        if (!modsDirectory.exists() && !modsDirectory.mkdirs() && !modsDirectory.isDirectory()) {
            throw new IllegalStateException("Unable to create the instance mods folder: " + modsDirectory.getAbsolutePath());
        }

        boolean rlcraft = isRLCraft(instance);
        notify(listener, 1, rlcraft
                ? "Detected RLCraft; preparing the Cleanroom compatibility preset..."
                : "Scanning the modpack for Cleanroom compatibility requirements...");

        ArrayList<File> originalFiles = listJarFiles(modsDirectory);
        ArrayList<ProjectSpec> projects = buildProjectPlan(gameDirectory, originalFiles, rlcraft);
        ArrayList<FileRule> removalRules = buildRemovalRules(originalFiles, projects, rlcraft);

        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File metadataDirectory = new File(instance.getRootDirectory(), "metadata");
        if (!metadataDirectory.exists() && !metadataDirectory.mkdirs() && !metadataDirectory.isDirectory()) {
            throw new IllegalStateException("Unable to create instance metadata directory.");
        }
        File stagingDirectory = new File(metadataDirectory, "cleanroom-staging-" + stamp);
        if (!stagingDirectory.mkdirs() && !stagingDirectory.isDirectory()) {
            throw new IllegalStateException("Unable to create Cleanroom compatibility staging directory.");
        }

        ArrayList<StagedProject> stagedProjects = new ArrayList<>();
        try {
            CurseForgeApiClient api = new CurseForgeApiClient(context);
            int count = Math.max(1, projects.size());
            for (int i = 0; i < projects.size(); i++) {
                ProjectSpec spec = projects.get(i);
                int progress = 5 + ((i * 55) / count);
                notify(listener, progress, "Downloading " + spec.displayName + "...");
                stagedProjects.add(stageProject(api, stagingDirectory, spec));
            }

            notify(listener, 63, "Backing up conflicting legacy mods...");
            Result result = commit(
                    gameDirectory,
                    modsDirectory,
                    stamp,
                    rlcraft,
                    originalFiles,
                    removalRules,
                    stagedProjects,
                    listener
            );
            writeCompatibilityRecord(instance, result);
            deleteRecursively(stagingDirectory);
            notify(listener, 100, result.getSummary());
            return result;
        } catch (Throwable throwable) {
            deleteRecursively(stagingDirectory);
            throw throwable;
        }
    }

    public static void rollback(
            @NonNull LauncherInstance instance,
            @NonNull Result result
    ) {
        File modsDirectory = new File(instance.getGameDirectory(), "mods");
        for (String installedName : result.installedFiles) {
            File installed = new File(modsDirectory, installedName);
            try {
                ModManagerManifest.removeEntryForFile(instance.getGameDirectory(), ModManagerContentType.MODS, installed);
            } catch (Throwable ignored) {
            }
            if (installed.isFile() && !installed.delete()) {
                Logging.i(TAG, "Unable to remove compatibility file during rollback: " + installed);
            }
        }

        File backupDirectory = result.backupDirectory;
        if (backupDirectory != null && backupDirectory.isDirectory()) {
            File[] backups = backupDirectory.listFiles();
            if (backups != null) {
                for (File backup : backups) {
                    if (backup == null || !backup.isFile()) continue;
                    try {
                        moveFile(backup, uniqueTarget(modsDirectory, backup.getName()));
                    } catch (Throwable throwable) {
                        Logging.e(TAG, "Unable to restore " + backup.getName() + " during rollback", throwable);
                    }
                }
            }
            deleteRecursively(backupDirectory);
        }
        File record = new File(new File(instance.getRootDirectory(), "metadata"), "cleanroom_compatibility.json");
        if (record.isFile() && !record.delete()) {
            Logging.i(TAG, "Unable to remove rolled-back Cleanroom compatibility record: " + record);
        }
    }

    @NonNull
    private static ArrayList<ProjectSpec> buildProjectPlan(
            @NonNull File gameDirectory,
            @NonNull ArrayList<File> originalFiles,
            boolean rlcraft
    ) {
        ArrayList<ProjectSpec> plan = new ArrayList<>();
        addIfMissing(plan, gameDirectory, originalFiles, FUGUE);
        addIfMissing(plan, gameDirectory, originalFiles, SCALAR_LEGACY);

        boolean oldForgelin = containsOldForgelin(originalFiles);
        boolean oldLibrarian = containsOldLibrarianLib(originalFiles);
        if (oldForgelin || rlcraft) addIfMissing(plan, gameDirectory, originalFiles, FORGELIN_CONTINUOUS);
        if (oldLibrarian || rlcraft) addIfMissing(plan, gameDirectory, originalFiles, LIBRARIANLIB_CONTINUOUS);

        if (rlcraft) {
            addIfMissing(plan, gameDirectory, originalFiles, HAD_ENOUGH_ITEMS);
            addIfMissing(plan, gameDirectory, originalFiles, QUARK_ROTN);
            addIfMissing(plan, gameDirectory, originalFiles, RENDERLIB);
            addIfMissing(plan, gameDirectory, originalFiles, RECURRENT_COMPLEX);
            addIfMissing(plan, gameDirectory, originalFiles, RLTWEAKER);
        }
        return plan;
    }

    private static void addIfMissing(
            @NonNull ArrayList<ProjectSpec> plan,
            @NonNull File gameDirectory,
            @NonNull ArrayList<File> files,
            @NonNull ProjectSpec spec
    ) {
        if (!spec.forceRefresh && ModManagerManifest.isProjectInstalled(
                gameDirectory,
                ModManagerContentType.MODS,
                ModManagerSource.CURSEFORGE.getId(),
                spec.projectId
        )) return;
        if (!spec.forceRefresh && containsReplacement(files, spec)) return;
        plan.add(spec);
    }

    @NonNull
    private static ArrayList<FileRule> buildRemovalRules(
            @NonNull ArrayList<File> originalFiles,
            @NonNull ArrayList<ProjectSpec> plannedProjects,
            boolean rlcraft
    ) {
        ArrayList<FileRule> rules = new ArrayList<>();
        if (containsOldForgelin(originalFiles)) {
            rules.add(new FileRule("Shadowfacts' Forgelin", name ->
                    name.contains("forgelin") && !name.contains("continuous")));
        }
        if (containsOldLibrarianLib(originalFiles)) {
            rules.add(new FileRule("LibrarianLib", name ->
                    name.contains("librarianlib") && !name.contains("continuous")));
        }
        if (!rlcraft) return rules;

        rules.add(new FileRule("AttributeFix", name -> name.contains("attributefix")));
        rules.add(new FileRule("Born in a Barn", name -> name.contains("born in a barn") || name.contains("borninabarn")));
        rules.add(new FileRule("BedBreakBegone", name -> name.contains("bedbreakbegone") || name.contains("breakbedbegone")));
        rules.add(new FileRule("Block Overlay Fix", name -> name.contains("blockoverlayfix")));
        rules.add(new FileRule("Entity Culling", name -> name.contains("entityculling")));
        rules.add(new FileRule("FoamFix", name -> name.contains("foamfix")));
        rules.add(new FileRule("Frame Void Patch", name -> name.contains("framevoidpatch")));
        rules.add(new FileRule("HelpFixer", name -> name.contains("helpfixer")));
        rules.add(new FileRule("Just Enough Items", CleanroomCompatibilityManager::isOldJeiFile));
        rules.add(new FileRule("MixinBootstrap", name -> name.contains("mixinbootstrap")));
        rules.add(new FileRule("Mixin Compatibility", name -> name.contains("mixincompat")));
        rules.add(new FileRule("Quark", name -> name.startsWith("quark-") && !name.contains("rotn")));
        rules.add(new FileRule("Phosphor", name -> name.contains("phosphor")));
        rules.add(new FileRule("PortalDupeBegone", name -> name.contains("portaldupebegone")));
        rules.add(new FileRule("Spark", name -> name.equals("spark-forge.jar") || name.startsWith("spark-forge-")));
        rules.add(new FileRule("Surge", name -> name.startsWith("surge-") || name.contains("surge-1.12")));
        rules.add(new FileRule("Toast Control", name -> name.contains("toastcontrol") || name.contains("toast control")));

        // Only move the existing copies when a refreshed replacement was staged.
        if (containsProject(plannedProjects, RENDERLIB.projectId)) {
            rules.add(new FileRule("RenderLib update", name -> name.contains("renderlib")));
        }
        if (containsProject(plannedProjects, RECURRENT_COMPLEX.projectId)) {
            rules.add(new FileRule("Recurrent Complex update", name -> name.contains("recurrentcomplex")));
        }
        if (containsProject(plannedProjects, RLTWEAKER.projectId)) {
            rules.add(new FileRule("RLTweaker update", name -> name.contains("rltweaker")));
        }
        return rules;
    }

    private static boolean isOldJeiFile(@NonNull String name) {
        if (name.contains("hadenoughitems")) return false;
        return name.startsWith("jei_")
                || name.startsWith("jei-")
                || name.matches("jei[0-9._+\\-].*");
    }

    @NonNull
    private static StagedProject stageProject(
            @NonNull CurseForgeApiClient api,
            @NonNull File stagingDirectory,
            @NonNull ProjectSpec spec
    ) throws Exception {
        ModrinthProject project = api.getProject(spec.projectId);
        ArrayList<ModrinthVersion> versions = api.getProjectVersions(
                spec.projectId,
                ModManagerContentType.MODS,
                MINECRAFT_VERSION,
                FORGE_LOADER
        );
        if (versions.isEmpty()) {
            throw new IllegalStateException("No compatible 1.12.2 Forge file was found for " + spec.displayName + ".");
        }

        ModrinthVersion version = selectNewestVersion(versions, spec);
        ModrinthFile file = version.getPrimaryFile();
        if (file == null || file.url.trim().isEmpty()) {
            throw new IllegalStateException("CurseForge did not provide a download for " + spec.displayName + ".");
        }
        String safeName = sanitizeFileName(file.filename);
        File stagedFile = uniqueTarget(stagingDirectory, safeName);
        api.downloadToFile(file.url, stagedFile);
        if (!stagedFile.isFile() || stagedFile.length() <= 0L) {
            throw new IllegalStateException("Downloaded file is empty for " + spec.displayName + ".");
        }
        verifySha1(stagedFile, file.sha1, spec.displayName);
        return new StagedProject(spec, project, version, file, stagedFile);
    }


    @NonNull
    private static ModrinthVersion selectNewestVersion(
            @NonNull List<ModrinthVersion> versions,
            @NonNull ProjectSpec spec
    ) {
        ModrinthVersion selected = null;
        for (ModrinthVersion candidate : versions) {
            if (candidate == null) continue;
            ModrinthFile candidateFile = candidate.getPrimaryFile();
            if (candidateFile == null || candidateFile.url.trim().isEmpty()) continue;
            if (spec.minimumVersion != null
                    && compareLooseVersions(extractVersion(candidate, candidateFile), spec.minimumVersion) < 0) {
                continue;
            }
            if (selected == null || isNewer(candidate, selected)) selected = candidate;
        }
        if (selected == null) {
            String minimum = spec.minimumVersion == null ? "" : " at least " + spec.minimumVersion;
            throw new IllegalStateException("No compatible 1.12.2 Forge file" + minimum
                    + " was found for " + spec.displayName + ".");
        }
        Logging.i(TAG, "Selected " + spec.displayName + " version " + selected.versionNumber
                + " (file " + selected.id + ", published " + selected.datePublished + ")");
        return selected;
    }

    private static boolean isNewer(
            @NonNull ModrinthVersion candidate,
            @NonNull ModrinthVersion current
    ) {
        String candidateDate = candidate.datePublished == null ? "" : candidate.datePublished.trim();
        String currentDate = current.datePublished == null ? "" : current.datePublished.trim();
        int dateCompare = candidateDate.compareTo(currentDate);
        if (dateCompare != 0) return dateCompare > 0;

        int versionCompare = compareLooseVersions(candidate.versionNumber, current.versionNumber);
        if (versionCompare != 0) return versionCompare > 0;
        return compareNumericIds(candidate.id, current.id) > 0;
    }

    @NonNull
    private static String extractVersion(
            @NonNull ModrinthVersion version,
            @NonNull ModrinthFile file
    ) {
        String combined = version.versionNumber + " " + version.name + " " + file.filename;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?<![0-9])([0-9]+(?:\\.[0-9]+){1,5})(?![0-9])")
                .matcher(combined);
        String best = "0";
        while (matcher.find()) {
            String found = matcher.group(1);
            if (compareLooseVersions(found, best) > 0) best = found;
        }
        return best;
    }

    private static int compareLooseVersions(@Nullable String left, @Nullable String right) {
        String[] a = normalizeVersion(left).split("\\.");
        String[] b = normalizeVersion(right).split("\\.");
        int count = Math.max(a.length, b.length);
        for (int i = 0; i < count; i++) {
            long av = i < a.length ? parseVersionPart(a[i]) : 0L;
            long bv = i < b.length ? parseVersionPart(b[i]) : 0L;
            if (av != bv) return av < bv ? -1 : 1;
        }
        return 0;
    }

    @NonNull
    private static String normalizeVersion(@Nullable String value) {
        if (value == null) return "0";
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("([0-9]+(?:\\.[0-9]+)*)")
                .matcher(value);
        return matcher.find() ? matcher.group(1) : "0";
    }

    private static long parseVersionPart(@NonNull String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static int compareNumericIds(@Nullable String left, @Nullable String right) {
        try {
            return Long.compare(Long.parseLong(left == null ? "0" : left),
                    Long.parseLong(right == null ? "0" : right));
        } catch (NumberFormatException ignored) {
            String a = left == null ? "" : left;
            String b = right == null ? "" : right;
            return a.compareTo(b);
        }
    }

    @NonNull
    private static Result commit(
            @NonNull File gameDirectory,
            @NonNull File modsDirectory,
            @NonNull String stamp,
            boolean rlcraft,
            @NonNull ArrayList<File> originalFiles,
            @NonNull ArrayList<FileRule> rules,
            @NonNull ArrayList<StagedProject> stagedProjects,
            @Nullable ProgressListener listener
    ) throws Exception {
        File backupDirectory = new File(modsDirectory, "cleanroom-disabled" + File.separator + stamp);
        ArrayList<MovedFile> backedUp = new ArrayList<>();
        ArrayList<MovedFile> installed = new ArrayList<>();
        ArrayList<String> disabledNames = new ArrayList<>();
        ArrayList<String> installedNames = new ArrayList<>();
        ArrayList<String> installedProjects = new ArrayList<>();
        Set<String> alreadyMoved = new HashSet<>();

        try {
            for (File original : originalFiles) {
                if (!original.isFile()) continue;
                String name = normalize(original.getName());
                if (!matchesAnyRule(name, rules)) continue;
                String path = canonicalPath(original);
                if (!alreadyMoved.add(path)) continue;

                if (!backupDirectory.exists() && !backupDirectory.mkdirs() && !backupDirectory.isDirectory()) {
                    throw new IllegalStateException("Unable to create Cleanroom disabled-mod backup folder.");
                }
                File backup = uniqueTarget(backupDirectory, original.getName());
                moveFile(original, backup);
                backedUp.add(new MovedFile(original, backup));
                disabledNames.add(original.getName());
            }

            int count = Math.max(1, stagedProjects.size());
            for (int i = 0; i < stagedProjects.size(); i++) {
                StagedProject staged = stagedProjects.get(i);
                int progress = 70 + ((i * 25) / count);
                notify(listener, progress, "Installing " + staged.spec.displayName + "...");
                File target = uniqueTarget(modsDirectory, staged.file.filename);
                moveFile(staged.stagedFile, target);
                installed.add(new MovedFile(staged.stagedFile, target));
                installedNames.add(target.getName());
                installedProjects.add(staged.spec.displayName);
                ModManagerManifest.recordInstalled(
                        gameDirectory,
                        ModManagerContentType.MODS,
                        ModManagerSource.CURSEFORGE,
                        staged.project,
                        staged.version,
                        staged.file,
                        target,
                        false,
                        MINECRAFT_VERSION,
                        FORGE_LOADER
                );
            }

            File resultBackup = disabledNames.isEmpty() ? null : backupDirectory;
            if (disabledNames.isEmpty()) deleteRecursively(backupDirectory);
            return new Result(rlcraft, resultBackup, disabledNames, installedNames, installedProjects);
        } catch (Throwable throwable) {
            // Remove newly installed files first, then restore every original JAR.
            for (int i = installed.size() - 1; i >= 0; i--) {
                MovedFile move = installed.get(i);
                if (move.destination.isFile() && !move.destination.delete()) {
                    Logging.i(TAG, "Unable to remove rolled-back compatibility file: " + move.destination);
                }
            }
            for (int i = backedUp.size() - 1; i >= 0; i--) {
                MovedFile move = backedUp.get(i);
                try {
                    if (move.destination.isFile()) moveFile(move.destination, move.source);
                } catch (Throwable restoreError) {
                    Logging.e(TAG, "Unable to restore " + move.source.getName(), restoreError);
                }
            }
            deleteRecursively(backupDirectory);
            throw throwable;
        }
    }

    private static boolean matchesAnyRule(@NonNull String name, @NonNull ArrayList<FileRule> rules) {
        for (FileRule rule : rules) {
            if (rule.matcher.matches(name)) return true;
        }
        return false;
    }

    private static boolean containsProject(@NonNull List<ProjectSpec> projects, @NonNull String projectId) {
        for (ProjectSpec project : projects) {
            if (projectId.equals(project.projectId)) return true;
        }
        return false;
    }

    private static boolean containsOldForgelin(@NonNull List<File> files) {
        for (File file : files) {
            String name = normalize(file.getName());
            if (name.contains("forgelin") && !name.contains("continuous")) return true;
        }
        return false;
    }

    private static boolean containsOldLibrarianLib(@NonNull List<File> files) {
        for (File file : files) {
            String name = normalize(file.getName());
            if (name.contains("librarianlib") && !name.contains("continuous")) return true;
        }
        return false;
    }

    private static boolean containsReplacement(@NonNull List<File> files, @NonNull ProjectSpec spec) {
        for (File file : files) {
            String name = normalize(file.getName());
            for (String token : spec.fileTokens) {
                if (name.contains(normalize(token))) return true;
            }
        }
        return false;
    }

    @NonNull
    private static ArrayList<File> listJarFiles(@NonNull File directory) {
        ArrayList<File> result = new ArrayList<>();
        File[] files = directory.listFiles();
        if (files == null) return result;
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            String name = normalize(file.getName());
            if (name.endsWith(".jar") || name.endsWith(".jar.disabled")) result.add(file);
        }
        return result;
    }

    private static void verifySha1(
            @NonNull File file,
            @Nullable String expected,
            @NonNull String label
    ) throws Exception {
        if (expected == null || expected.trim().isEmpty()) return;
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder actual = new StringBuilder();
        for (byte value : digest.digest()) actual.append(String.format(Locale.US, "%02x", value & 0xff));
        if (!expected.trim().equalsIgnoreCase(actual.toString())) {
            throw new IllegalStateException("SHA-1 verification failed for " + label + ".");
        }
    }

    private static void moveFile(@NonNull File source, @NonNull File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Unable to create " + parent.getAbsolutePath());
        }
        if (source.renameTo(destination)) return;

        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            output.getFD().sync();
        }
        if (!source.delete()) {
            if (!destination.delete()) Logging.i(TAG, "Unable to delete incomplete move destination: " + destination);
            throw new IOException("Unable to remove source after copying " + source.getName());
        }
    }

    @NonNull
    private static File uniqueTarget(@NonNull File directory, @NonNull String fileName) {
        File target = new File(directory, fileName);
        if (!target.exists()) return target;
        String base = fileName;
        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }
        for (int i = 2; i < 1000; i++) {
            File candidate = new File(directory, base + "-" + i + extension);
            if (!candidate.exists()) return candidate;
        }
        return new File(directory, base + "-" + System.currentTimeMillis() + extension);
    }

    @NonNull
    private static String sanitizeFileName(@NonNull String rawName) {
        String name = rawName.trim().replace('\n', ' ').replace('\r', ' ');
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) name = "cleanroom-compat.jar";
        return name;
    }

    private static void writeCompatibilityRecord(
            @NonNull LauncherInstance instance,
            @NonNull Result result
    ) {
        try {
            File metadataDirectory = new File(instance.getRootDirectory(), "metadata");
            if (!metadataDirectory.exists() && !metadataDirectory.mkdirs() && !metadataDirectory.isDirectory()) return;

            JSONObject json = new JSONObject();
            json.put("schema", 1);
            json.put("preparedAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date()));
            json.put("profile", result.getProfileName());
            json.put("rlcraftPreset", result.rlcraftPreset);
            json.put("gameDirectory", instance.getGameDirectory().getAbsolutePath());
            json.put("backupDirectory", result.backupDirectory == null ? JSONObject.NULL : result.backupDirectory.getAbsolutePath());
            json.put("disabledFiles", new JSONArray(result.disabledFiles));
            json.put("installedFiles", new JSONArray(result.installedFiles));
            json.put("installedProjects", new JSONArray(result.installedProjects));

            File record = new File(metadataDirectory, "cleanroom_compatibility.json");
            try (FileOutputStream output = new FileOutputStream(record)) {
                output.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to write Cleanroom compatibility record: " + throwable.getMessage());
        }
    }

    private static void deleteRecursively(@Nullable File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        if (!file.delete() && file.exists()) Logging.i(TAG, "Unable to delete temporary file: " + file);
    }

    @NonNull
    private static String canonicalPath(@NonNull File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException ignored) {
            return file.getAbsolutePath();
        }
    }

    @NonNull
    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }

    private static void notify(
            @Nullable ProgressListener listener,
            int progress,
            @NonNull String message
    ) {
        Logging.i(TAG, message);
        if (listener != null) listener.onProgress(Math.max(0, Math.min(100, progress)), message);
    }

    private interface NameMatcher {
        boolean matches(@NonNull String normalizedName);
    }

    private static final class FileRule {
        @NonNull
        final String label;
        @NonNull
        final NameMatcher matcher;

        FileRule(@NonNull String label, @NonNull NameMatcher matcher) {
            this.label = label;
            this.matcher = matcher;
        }
    }

    private static final class ProjectSpec {
        @NonNull
        final String projectId;
        @NonNull
        final String displayName;
        @NonNull
        final String[] fileTokens;
        final boolean forceRefresh;
        @Nullable
        final String minimumVersion;

        ProjectSpec(@NonNull String projectId, @NonNull String displayName, @NonNull String[] fileTokens) {
            this(projectId, displayName, fileTokens, false, null);
        }

        ProjectSpec(
                @NonNull String projectId,
                @NonNull String displayName,
                @NonNull String[] fileTokens,
                boolean forceRefresh
        ) {
            this(projectId, displayName, fileTokens, forceRefresh, null);
        }

        ProjectSpec(
                @NonNull String projectId,
                @NonNull String displayName,
                @NonNull String[] fileTokens,
                boolean forceRefresh,
                @Nullable String minimumVersion
        ) {
            this.projectId = projectId;
            this.displayName = displayName;
            this.fileTokens = fileTokens;
            this.forceRefresh = forceRefresh;
            this.minimumVersion = minimumVersion;
        }
    }

    private static final class StagedProject {
        @NonNull
        final ProjectSpec spec;
        @NonNull
        final ModrinthProject project;
        @NonNull
        final ModrinthVersion version;
        @NonNull
        final ModrinthFile file;
        @NonNull
        final File stagedFile;

        StagedProject(
                @NonNull ProjectSpec spec,
                @NonNull ModrinthProject project,
                @NonNull ModrinthVersion version,
                @NonNull ModrinthFile file,
                @NonNull File stagedFile
        ) {
            this.spec = spec;
            this.project = project;
            this.version = version;
            this.file = file;
            this.stagedFile = stagedFile;
        }
    }

    private static final class MovedFile {
        @NonNull
        final File source;
        @NonNull
        final File destination;

        MovedFile(@NonNull File source, @NonNull File destination) {
            this.source = source;
            this.destination = destination;
        }
    }
}

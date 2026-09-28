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

package ca.dnamobile.droidbridgelauncher.utils.path;

import android.content.Context;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import ca.dnamobile.droidbridgelauncher.storage.StorageLocationStore;

public final class PathManager {
    public static String DIR_NATIVE_LIB;
    public static File DIR_FILE;
    public static String DIR_DATA;
    public static File DIR_CACHE;
    public static String DIR_MULTIRT_HOME;
    public static String DIR_GAME_HOME = "";
    public static String DIR_MINECRAFT_HOME = "";
    public static String DIR_LAUNCHER_LOG;
    public static String DIR_CTRLMAP_PATH;
    public static String DIR_ACCOUNT_NEW;
    public static String DIR_CACHE_STRING;
    public static String DIR_ADDONS_INFO_CACHE;
    @Nullable
    public static File DIR_RUNTIME_MOD;
    public static String DIR_CUSTOM_MOUSE;
    public static File DIR_BACKGROUND;
    public static File DIR_APP_CACHE;
    public static File DIR_USER_SKIN;
    public static File DIR_INSTALLED_RENDERER_PLUGIN;
    public static File FILE_SETTINGS;
    public static File FILE_PROFILE_PATH;
    public static String FILE_CTRLDEF_FILE;
    public static String FILE_VERSION_LIST;
    public static File FILE_NEWBIE_GUIDE;

    private PathManager() {
    }

    public static void initContextConstants(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        initContextConstants(appContext, StorageLocationStore.getSelectedLauncherHome(appContext));
    }

    public static void initContextConstants(@NonNull Context context, @NonNull File launcherHome) {
        Context appContext = context.getApplicationContext();

        DIR_NATIVE_LIB = appContext.getApplicationInfo().nativeLibraryDir;
        DIR_FILE = appContext.getFilesDir();
        File parent = DIR_FILE.getParentFile();
        DIR_DATA = parent != null ? parent.getAbsolutePath() : DIR_FILE.getAbsolutePath();
        DIR_CACHE = appContext.getCacheDir();
        DIR_MULTIRT_HOME = new File(DIR_DATA, "runtimes").getAbsolutePath();

        DIR_GAME_HOME = launcherHome.getAbsolutePath();
        DIR_MINECRAFT_HOME = new File(DIR_GAME_HOME, ".minecraft").getAbsolutePath();

        DIR_LAUNCHER_LOG = new File(DIR_GAME_HOME, "launcher_log").getAbsolutePath();
        DIR_CTRLMAP_PATH = new File(DIR_GAME_HOME, "controlmap").getAbsolutePath();
        DIR_ACCOUNT_NEW = new File(DIR_FILE, "accounts").getAbsolutePath();
        DIR_CACHE_STRING = new File(DIR_CACHE, "string_cache").getAbsolutePath();
        DIR_ADDONS_INFO_CACHE = new File(DIR_CACHE, "addons_info_cache").getAbsolutePath();
        DIR_CUSTOM_MOUSE = new File(DIR_GAME_HOME, "mouse").getAbsolutePath();
        DIR_BACKGROUND = new File(DIR_GAME_HOME, "background");
        DIR_APP_CACHE = appContext.getExternalCacheDir() != null ? appContext.getExternalCacheDir() : DIR_CACHE;
        DIR_USER_SKIN = new File(DIR_FILE, "user_skin");
        DIR_INSTALLED_RENDERER_PLUGIN = new File(DIR_FILE, "renderer_plugins");
        DIR_RUNTIME_MOD = appContext.getDir("runtime_mod", Context.MODE_PRIVATE);

        FILE_PROFILE_PATH = new File(DIR_DATA, "profile_path.json");
        FILE_CTRLDEF_FILE = new File(DIR_CTRLMAP_PATH, "default.json").getAbsolutePath();
        FILE_VERSION_LIST = new File(DIR_DATA, "version_list.json").getAbsolutePath();
        FILE_NEWBIE_GUIDE = new File(DIR_DATA, "newbie_guide.json");
        FILE_SETTINGS = new File(DIR_FILE, "launcher_settings.json");

        createRequiredDirectories();
        deleteQuietly(new File(DIR_DATA, "accounts"));
        deleteQuietly(new File(DIR_DATA, "user_skin"));
        LibPath.refresh();
    }
    @NonNull
    public static File getAccessibleLauncherRoot(@NonNull Context context) {
        return getLauncherHome(context);
    }
    @NonNull
    public static File getDefaultLauncherHome(@NonNull Context context) {
        File externalFiles = context.getExternalFilesDir(null);
        return externalFiles != null ? externalFiles : new File(context.getFilesDir(), "DroidBridge");
    }
    @NonNull
    public static File getLauncherHome(@NonNull Context context) {
        return StorageLocationStore.getSelectedLauncherHome(context.getApplicationContext());
    }
    @NonNull
    public static File getMinecraftHome(@NonNull Context context) {
        return new File(getLauncherHome(context), ".minecraft");
    }
    @NonNull
    public static File inferLauncherHomeFromGameDirectory(@NonNull File gameDirectory) {
        File candidate = gameDirectory;

        // Isolated instances use: <launcherHome>/.minecraft/instances/<id>/game
        File parent = candidate.getParentFile();
        File grandParent = parent != null ? parent.getParentFile() : null;
        if ("game".equals(candidate.getName())
                && grandParent != null
                && "instances".equals(grandParent.getName())) {
            File minecraftHome = grandParent.getParentFile();
            File launcherHome = minecraftHome != null ? minecraftHome.getParentFile() : null;
            return launcherHome != null ? launcherHome : candidate;
        }

        // Shared installs use the .minecraft folder itself as the game directory.
        if (".minecraft".equals(candidate.getName())) {
            File launcherHome = candidate.getParentFile();
            return launcherHome != null ? launcherHome : candidate;
        }

        // Last fallback: if the path is somewhere inside .minecraft, walk up to it.
        File walk = candidate;
        while (walk != null) {
            if (".minecraft".equals(walk.getName())) {
                File launcherHome = walk.getParentFile();
                return launcherHome != null ? launcherHome : candidate;
            }
            walk = walk.getParentFile();
        }

        return getDefaultLauncherHomeFromAnyPath(candidate);
    }

    @NonNull
    private static File getDefaultLauncherHomeFromAnyPath(@NonNull File path) {
        File parent = path.getParentFile();
        return parent != null ? parent : path;
    }

    private static void createRequiredDirectories() {
        mkdirs(new File(DIR_GAME_HOME));
        mkdirs(new File(DIR_MINECRAFT_HOME));
        mkdirs(new File(DIR_MULTIRT_HOME));
        mkdirs(new File(DIR_LAUNCHER_LOG));
        mkdirs(new File(DIR_CTRLMAP_PATH));
        mkdirs(new File(DIR_CUSTOM_MOUSE));
        mkdirs(DIR_BACKGROUND);
        mkdirs(DIR_INSTALLED_RENDERER_PLUGIN);
        mkdirs(DIR_USER_SKIN);
        if (DIR_RUNTIME_MOD != null) mkdirs(DIR_RUNTIME_MOD);

        // Java/LWJGL launcher-side assets live in the internal app files directory,
        // not in .minecraft. The game asset index downloader can fill this later.
        mkdirs(new File(DIR_FILE, "assets"));

        // Keep .minecraft visible and ready, but do not place launcher assets/components inside it.
        mkdirs(new File(DIR_MINECRAFT_HOME, "versions"));
        mkdirs(new File(DIR_MINECRAFT_HOME, "mods"));
        mkdirs(new File(DIR_MINECRAFT_HOME, "resourcepacks"));
        mkdirs(new File(DIR_MINECRAFT_HOME, "shaderpacks"));
        mkdirs(new File(DIR_MINECRAFT_HOME, "saves"));
        mkdirs(new File(DIR_MINECRAFT_HOME, "logs"));
        mkdirs(new File(DIR_MINECRAFT_HOME, "crash-reports"));
    }

    private static void mkdirs(@NonNull File file) {
        if (!file.exists()) file.mkdirs();
    }

    public static void deleteRecursivelyChecked(@Nullable File file) throws IOException {
        if (file == null || !file.exists()) return;

        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                throw new IOException("Unable to list directory for deletion: " + file.getAbsolutePath());
            }
            for (File child : children) {
                deleteRecursivelyChecked(child);
            }
        }

        if (file.exists() && !file.delete()) {
            throw new IOException("Unable to delete path: " + file.getAbsolutePath());
        }
    }

    public static void deleteQuietly(@Nullable File file) {
        if (file == null || !file.exists()) return;
        try {
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) {
                    for (File child : children) deleteQuietly(child);
                }
            }
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        } catch (Throwable ignored) {
        }
    }

    @NonNull
    public static List<File> getStorageRoots(@NonNull Context context) {
        ArrayList<File> roots = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        File[] appExternalDirs = context.getExternalFilesDirs(null);
        if (appExternalDirs != null) {
            for (File dir : appExternalDirs) {
                File root = getStorageRootFromAppExternalDir(dir);
                addRootIfUsable(roots, seen, root);
            }
        }

        for (File root : getDirectMountedStorageRoots()) {
            addRootIfUsable(roots, seen, root);
        }

        if (roots.isEmpty()) {
            addRootIfUsable(roots, seen, Environment.getExternalStorageDirectory());
        }

        return roots;
    }

    @NonNull
    public static File getPrimaryStorageRoot(@NonNull Context context) {
        String emulatedPath = Environment.getExternalStorageDirectory().getAbsolutePath();
        for (File root : getStorageRoots(context)) {
            if (emulatedPath.equals(root.getAbsolutePath())) return root;
        }
        List<File> roots = getStorageRoots(context);
        return roots.isEmpty() ? Environment.getExternalStorageDirectory() : roots.get(0);
    }

    @Nullable
    public static File getRemovableStorageRoot(@NonNull Context context) {
        File[] appExternalDirs = context.getExternalFilesDirs(null);
        if (appExternalDirs != null) {
            for (File dir : appExternalDirs) {
                if (dir == null) continue;
                if (!Environment.isExternalStorageRemovable(dir)) continue;
                File root = getStorageRootFromAppExternalDir(dir);
                if (root != null && root.exists() && root.canRead()) return root;
            }
        }

        List<File> directRoots = getDirectMountedStorageRoots();
        return directRoots.isEmpty() ? null : directRoots.get(0);
    }

    @NonNull
    public static List<File> getUsbOrExternalRoots(@NonNull Context context) {
        String primaryPath = getPrimaryStorageRoot(context).getAbsolutePath();
        File removable = getRemovableStorageRoot(context);
        String removablePath = removable != null ? removable.getAbsolutePath() : null;

        ArrayList<File> result = new ArrayList<>();
        for (File root : getStorageRoots(context)) {
            String path = root.getAbsolutePath();
            if (!path.equals(primaryPath) && (removablePath == null || !path.equals(removablePath))) {
                result.add(root);
            }
        }
        return result;
    }

    @Nullable
    public static File findContainingStorageRoot(@NonNull Context context, @Nullable String path) {
        if (path == null || path.trim().isEmpty()) return null;
        try {
            File target = new File(path).getCanonicalFile();
            for (File root : getStorageRoots(context)) {
                if (isPathInsideRoot(target, root)) return root;
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    public static boolean isPathInsideRoot(@NonNull File target, @NonNull File root) {
        try {
            String targetPath = target.getCanonicalFile().getPath();
            String rootPath = root.getCanonicalFile().getPath();
            return targetPath.equals(rootPath) || targetPath.startsWith(rootPath + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    @Nullable
    private static File getStorageRootFromAppExternalDir(@Nullable File dir) {
        if (dir == null) return null;
        String path = dir.getAbsolutePath();
        String marker = "/Android/data/";
        int index = path.indexOf(marker);
        if (index <= 0) return null;
        return new File(path.substring(0, index));
    }

    @NonNull
    private static List<File> getDirectMountedStorageRoots() {
        ArrayList<File> results = new ArrayList<>();
        File storageDir = new File("/storage");
        File[] children = storageDir.listFiles();
        if (children == null) return results;

        for (File child : children) {
            if (!child.isDirectory()) continue;
            String name = child.getName();
            if ("emulated".equals(name) || "self".equals(name)) continue;
            if (!child.canRead()) continue;

            String lowerName = name.toLowerCase(Locale.ROOT);
            boolean looksLikeRemovable = name.contains("-")
                    || lowerName.startsWith("usb")
                    || lowerName.startsWith("usbotg")
                    || lowerName.startsWith("sd")
                    || lowerName.startsWith("ext")
                    || lowerName.startsWith("disk");

            if (looksLikeRemovable) results.add(child);
        }
        return results;
    }

    private static void addRootIfUsable(@NonNull List<File> roots, @NonNull Set<String> seen, @Nullable File root) {
        if (root == null || !root.exists() || !root.canRead()) return;
        String path = root.getAbsolutePath();
        if (seen.add(path)) roots.add(root);
    }
}

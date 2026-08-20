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

package ca.dnamobile.droidbridgelauncher.modcompat;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.launcher.LaunchPlan;
import ca.dnamobile.droidbridgelauncher.utils.path.PathManager;
import ca.dnamobile.droidbridgelauncher.runtime.utils.JREUtils;

/**
 * Android launcher-side support for Sable's Rapier native.
 *
 * Sable normally extracts sable_rapier_aarch64_linux.so into <gameDir>/.sable/natives
 * and then calls System.load(...) on that external/app-scoped storage path. Android blocks
 * that path from the JVM classloader namespace, which makes Rapier fail to link.
 *
 * DroidBridgeLibPatcher rewrites Sable's Rapier3D.loadLibrary() so it reads the
 * sable_rapier_path JVM property instead. This class makes sure the javaagent exists,
 * points sable_rapier_path at the APK native library directory, and adds both args before
 * ModLauncher/Sable can load.
 */
public final class SableRapierSupport {
    private static final String TAG = "SableRapier";

    private static final String JAVA_AGENT_PREFIX = "-javaagent:";
    private static final String SABLE_PROPERTY_PREFIX = "-Dsable_rapier_path=";

    private static final String DROIDBRIDGE_PATCHER_JAR = "DroidBridgeLibPatcher.jar";
    /** Bundled legacy Android native. This ABI matches Sable 1.x only. */
    private static final String SABLE_NATIVE_V1 = "libsable_rapier.so";

    /** Optional Sable 2.x Android native. Package this exact file when integrating a 2.x native. */
    private static final String SABLE_NATIVE_V2 = "libsable_rapier_v2.so";

    private static final Pattern SEMVER_PATTERN = Pattern.compile("(?<!\\d)(\\d+)\\.(\\d+)\\.(\\d+)(?!\\d)");
    private static final String CXX_SHARED = "libc++_shared.so";

    private SableRapierSupport() {
    }

    public static void addJvmArgsIfNeeded(
            @NonNull Context context,
            @NonNull LaunchPlan plan,
            @NonNull List<String> jvmArgs
    ) {
        // VMLauncher may reuse the Android process between game launches. Never let a path
        // selected for an earlier Sable version leak into a later launch.
        clearSableRapierProperty();

        SableInstall install = findSableInstall(plan.getGameDirectory());
        if (install.coreJar == null) {
            return;
        }

        if (install.androidPatchJar != null) {
            // The standalone patch owns both the bytecode rewrite and the matching native.
            // Never preload DroidBridge's bundled legacy native alongside it.
            removeArgsStartingWith(jvmArgs, SABLE_PROPERTY_PREFIX);
            log("Sable " + install.versionLabel() + " detected with Android patch "
                    + install.androidPatchJar.getName() + "; launcher native override disabled to avoid ABI conflicts.");
            return;
        }

        int majorVersion = install.majorVersion;
        if (majorVersion < 1) {
            removeArgsStartingWith(jvmArgs, SABLE_PROPERTY_PREFIX);
            log("Sable version could not be identified from " + install.coreJar.getName()
                    + ". Refusing to load the bundled legacy native because an unknown Sable ABI is unsafe. "
                    + "Install the Android patch matching the installed Sable version.");
            return;
        }

        String nativeName = majorVersion >= 2 ? SABLE_NATIVE_V2 : SABLE_NATIVE_V1;
        File nativeFile = prepareSableRapierNative(context, nativeName);

        if (majorVersion >= 2 && (nativeFile == null || !nativeFile.isFile() || nativeFile.length() <= 0)) {
            // Fail closed. The old Sable 1.x native uses a different JNI ABI and loading it with
            // Sable 2.x corrupts scene handles, ending in a non-unwinding Rust panic in changeBlock.
            removeArgsStartingWith(jvmArgs, SABLE_PROPERTY_PREFIX);
            log("Sable " + install.versionLabel() + " requires a matching Sable 2.x Android native. "
                    + "Refusing to load the bundled Sable 1.x native. Install sable-android-patch-2.0.3.jar "
                    + "or package " + SABLE_NATIVE_V2 + " in app/src/main/jniLibs/arm64-v8a/.");
            return;
        }

        File patcherJar = prepareDroidBridgeLibPatcher(context);
        if (patcherJar == null || !patcherJar.isFile() || patcherJar.length() <= 0) {
            log("Sable detected, but " + DROIDBRIDGE_PATCHER_JAR + " was not found. Rapier override cannot be applied.");
            return;
        }

        if (nativeFile == null || !nativeFile.isFile() || nativeFile.length() <= 0) {
            log("Sable detected, but " + nativeName + " was not found. Rapier override cannot be applied.");
            return;
        }

        preloadCxxIfPresent(nativeFile.getParentFile());
        preloadSableNative(nativeFile);

        String patcherPath = patcherJar.getAbsolutePath();
        String nativePath = nativeFile.getAbsolutePath();

        try {
            System.setProperty("sable_rapier_path", nativePath);
        } catch (Throwable ignored) {
        }

        upsertOrderedSableJvmArgs(jvmArgs, nativePath, patcherPath);

        log("Sable " + install.versionLabel() + " Rapier javaagent enabled: " + patcherPath);
        log("Sable " + install.versionLabel() + " native override enabled: " + nativePath);
    }

    private static void upsertOrderedSableJvmArgs(
            @NonNull List<String> args,
            @NonNull String nativePath,
            @NonNull String patcherPath
    ) {
        removeArgsStartingWith(args, SABLE_PROPERTY_PREFIX);
        removeDroidBridgePatcherAgent(args);

        int insertAt = !args.isEmpty() && "java".equals(args.get(0)) ? 1 : 0;
        args.add(insertAt, SABLE_PROPERTY_PREFIX + nativePath);
        args.add(insertAt + 1, JAVA_AGENT_PREFIX + patcherPath);
    }

    private static void clearSableRapierProperty() {
        try {
            System.clearProperty("sable_rapier_path");
        } catch (Throwable ignored) {
        }
    }

    private static void removeArgsStartingWith(@NonNull List<String> args, @NonNull String prefix) {
        for (int i = args.size() - 1; i >= 0; i--) {
            String arg = args.get(i);
            if (arg != null && arg.startsWith(prefix)) {
                args.remove(i);
            }
        }
    }

    private static void removeDroidBridgePatcherAgent(@NonNull List<String> args) {
        for (int i = args.size() - 1; i >= 0; i--) {
            String arg = args.get(i);
            if (arg == null || !arg.startsWith(JAVA_AGENT_PREFIX)) continue;
            String lower = arg.toLowerCase(Locale.ROOT);
            if (lower.endsWith("/" + DROIDBRIDGE_PATCHER_JAR.toLowerCase(Locale.ROOT))
                    || lower.endsWith("\\" + DROIDBRIDGE_PATCHER_JAR.toLowerCase(Locale.ROOT))
                    || lower.equals(JAVA_AGENT_PREFIX + DROIDBRIDGE_PATCHER_JAR.toLowerCase(Locale.ROOT))) {
                args.remove(i);
            }
        }
    }

    @Nullable
    private static File prepareDroidBridgeLibPatcher(@NonNull Context context) {
        File componentsDir = resolveComponentsDirectory(context);
        File target = new File(componentsDir, DROIDBRIDGE_PATCHER_JAR);

        // Support either asset layout:
        // app/src/main/assets/DroidBridgeLibPatcher.jar
        // app/src/main/assets/components/DroidBridgeLibPatcher.jar
        if (copyFirstExistingAsset(context, target, DROIDBRIDGE_PATCHER_JAR, "components/" + DROIDBRIDGE_PATCHER_JAR)) {
            return target;
        }

        File[] candidates = new File[]{
                target,
                new File(context.getFilesDir(), DROIDBRIDGE_PATCHER_JAR),
                new File(new File(context.getFilesDir(), "components"), DROIDBRIDGE_PATCHER_JAR),
                new File(PathManager.DIR_FILE, DROIDBRIDGE_PATCHER_JAR),
                new File(new File(PathManager.DIR_FILE, "components"), DROIDBRIDGE_PATCHER_JAR),
                new File(new File(PathManager.DIR_FILE, "droidbridge"), DROIDBRIDGE_PATCHER_JAR),
                new File(new File(PathManager.DIR_FILE, "patcher"), DROIDBRIDGE_PATCHER_JAR),
                new File(new File(PathManager.DIR_FILE, "runtime_mod"), DROIDBRIDGE_PATCHER_JAR)
        };

        for (File candidate : candidates) {
            if (candidate.isFile() && candidate.length() > 0) {
                return candidate;
            }
        }
        return null;
    }

    @NonNull
    private static File resolveComponentsDirectory(@NonNull Context context) {
        File filesDir = context.getFilesDir();
        File dataDir = filesDir != null ? filesDir.getParentFile() : null;
        File componentsDir = dataDir != null
                ? new File(dataDir, "components")
                : new File(context.getFilesDir(), "components");

        if (!componentsDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            componentsDir.mkdirs();
        }
        return componentsDir;
    }

    @Nullable
    private static File prepareSableRapierNative(@NonNull Context context, @NonNull String nativeName) {
        // Prefer the APK nativeLibraryDir. This is the Android linker namespace-safe path and
        // matches the working the prior implementation log: /data/app/.../lib/arm64/libsable_rapier.so
        String appNativeDir = context.getApplicationInfo() != null
                ? context.getApplicationInfo().nativeLibraryDir
                : null;
        if (appNativeDir != null && !appNativeDir.trim().isEmpty()) {
            File appNative = new File(appNativeDir, nativeName);
            if (appNative.isFile() && appNative.length() > 0) return appNative;
        }

        String nativeLibDir = PathManager.DIR_NATIVE_LIB;
        if (nativeLibDir != null && !nativeLibDir.trim().isEmpty()) {
            File nativeDirFile = new File(nativeLibDir, nativeName);
            if (nativeDirFile.isFile() && nativeDirFile.length() > 0) return nativeDirFile;
        }

        File runtimeModNative = findInRuntimeModDirectory(nativeName);
        if (runtimeModNative != null && runtimeModNative.isFile() && runtimeModNative.length() > 0) {
            return runtimeModNative;
        }

        // Last resort: app-private copy from assets. Prefer not to use this when nativeLibraryDir is available,
        // because some Android linker namespace configs only allow the APK native lib directory.
        File privateDir = context.getDir("sable_rapier", Context.MODE_PRIVATE);
        File privateNative = new File(privateDir, nativeName);
        if (copyFirstExistingAsset(context, privateNative, nativeName, "native/" + nativeName)) {
            makeLoadable(privateNative);
            log("Using fallback app-private Sable native path. If Android blocks this, package "
                    + nativeName + " under app/src/main/jniLibs/arm64-v8a/.");
            return privateNative;
        }

        if (privateNative.isFile() && privateNative.length() > 0) {
            makeLoadable(privateNative);
            log("Using existing fallback app-private Sable native path. If Android blocks this, package "
                    + nativeName + " under app/src/main/jniLibs/arm64-v8a/.");
            return privateNative;
        }

        File[] fallbackCandidates = new File[]{
                new File(PathManager.DIR_FILE, nativeName),
                new File(new File(PathManager.DIR_FILE, "runtime_mod"), nativeName),
                new File(new File(PathManager.DIR_FILE, "sable"), nativeName)
        };

        for (File candidate : fallbackCandidates) {
            if (candidate.isFile() && candidate.length() > 0) return candidate;
        }

        return null;
    }

    private static boolean copyFirstExistingAsset(
            @NonNull Context context,
            @NonNull File target,
            @NonNull String... assetNames
    ) {
        for (String assetName : assetNames) {
            if (copyAsset(context, assetName, target)) {
                return true;
            }
        }
        return target.isFile() && target.length() > 0;
    }

    private static boolean copyAsset(@NonNull Context context, @NonNull String assetName, @NonNull File target) {
        try (InputStream input = context.getAssets().open(assetName)) {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }

            try (FileOutputStream output = new FileOutputStream(target, false)) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }

            makeLoadable(target);
            log("Copied asset " + assetName + " to " + target.getAbsolutePath());
            return target.isFile() && target.length() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void makeLoadable(@NonNull File file) {
        try {
            //noinspection ResultOfMethodCallIgnored
            file.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            file.setExecutable(true, false);
        } catch (Throwable ignored) {
        }
    }

    @NonNull
    private static SableInstall findSableInstall(@NonNull File gameDirectory) {
        SableInstall install = new SableInstall();
        scanModsDirectory(new File(gameDirectory, "mods"), install);

        String minecraftHome = PathManager.DIR_MINECRAFT_HOME;
        if (minecraftHome != null && !minecraftHome.trim().isEmpty()) {
            File globalMods = new File(minecraftHome, "mods");
            if (!sameFile(globalMods, new File(gameDirectory, "mods"))) {
                scanModsDirectory(globalMods, install);
            }
        }
        return install;
    }

    private static void scanModsDirectory(@NonNull File modsDir, @NonNull SableInstall install) {
        File[] files = modsDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (!file.isFile()) continue;
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".disabled") || !name.endsWith(".jar")) continue;

            if (isSableAndroidPatch(name)) {
                if (install.androidPatchJar == null) install.androidPatchJar = file;
                continue;
            }

            if (isSableCore(name) && install.coreJar == null) {
                install.coreJar = file;
                int[] version = findLastSemanticVersion(name);
                if (version != null) {
                    install.majorVersion = version[0];
                    install.minorVersion = version[1];
                    install.patchVersion = version[2];
                }
            }
        }
    }

    private static boolean isSableAndroidPatch(@NonNull String lowerName) {
        return lowerName.contains("sable-android-patch")
                || lowerName.contains("sable_android_patch")
                || lowerName.contains("sableandroidpatch");
    }

    private static boolean isSableCore(@NonNull String lowerName) {
        return lowerName.startsWith("sable-neoforge-")
                || lowerName.startsWith("sable-fabric-")
                || lowerName.matches("sable-[0-9].*\\.jar");
    }

    @Nullable
    private static int[] findLastSemanticVersion(@NonNull String text) {
        Matcher matcher = SEMVER_PATTERN.matcher(text);
        int[] last = null;
        while (matcher.find()) {
            try {
                last = new int[]{
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3))
                };
            } catch (NumberFormatException ignored) {
            }
        }
        return last;
    }

    private static final class SableInstall {
        @Nullable File coreJar;
        @Nullable File androidPatchJar;
        int majorVersion = -1;
        int minorVersion = -1;
        int patchVersion = -1;

        @NonNull
        String versionLabel() {
            if (majorVersion < 0) {
                return "unknown";
            }
            if (minorVersion < 0 || patchVersion < 0) {
                return majorVersion + ".x";
            }
            return majorVersion + "." + minorVersion + "." + patchVersion;
        }
    }

    @Nullable
    private static File findInRuntimeModDirectory(@NonNull String nativeName) {
        try {
            Field field = PathManager.class.getField("DIR_RUNTIME_MOD");
            Object value = field.get(null);
            if (value instanceof File) {
                return new File((File) value, nativeName);
            }
        } catch (Throwable ignored) {
            // Older DroidBridge builds may not have DIR_RUNTIME_MOD yet.
        }
        return null;
    }

    private static void preloadCxxIfPresent(@Nullable File parent) {
        if (parent == null) return;
        File cxxShared = new File(parent, CXX_SHARED);
        if (!cxxShared.isFile()) return;

        try {
            if (JREUtils.dlopen(cxxShared.getAbsolutePath())) {
                log("Preloaded " + CXX_SHARED + " for Sable Rapier.");
            }
        } catch (Throwable throwable) {
            log("Unable to preload " + CXX_SHARED + ": " + throwable.getMessage());
        }
    }

    private static void preloadSableNative(@NonNull File nativeFile) {
        try {
            if (JREUtils.dlopen(nativeFile.getAbsolutePath())) {
                log("Preloaded " + nativeFile.getName() + ".");
            }
        } catch (Throwable throwable) {
            log("Unable to preload " + nativeFile.getName() + ": " + throwable.getMessage());
        }
    }

    private static boolean sameFile(@NonNull File left, @NonNull File right) {
        try {
            return left.getCanonicalFile().equals(right.getCanonicalFile());
        } catch (Throwable ignored) {
            return left.getAbsolutePath().equals(right.getAbsolutePath());
        }
    }

    private static void log(@NonNull String message) {
        Logging.i(TAG, message);
        try {
            ca.dnamobile.droidbridgelauncher.runtime.Logger.appendToLog(TAG + ": " + message);
        } catch (Throwable ignored) {
        }
    }
}

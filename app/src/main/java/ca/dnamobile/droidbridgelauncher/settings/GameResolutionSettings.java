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

package ca.dnamobile.droidbridgelauncher.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Stores and resolves DroidBridge's experimental game framebuffer/aspect-ratio setting.
 *
 * The selected profile supplies the base Minecraft framebuffer size. The existing
 * resolution-scale percentage is applied afterwards, so 100% means the exact selected
 * profile and lower percentages reduce its render cost without changing its aspect ratio.
 */
public final class GameResolutionSettings {
    public static final String MODE_NATIVE = "native";
    public static final String MODE_1920_1080 = "1920x1080";
    public static final String MODE_BEST_4_3 = "best_4_3";
    public static final String MODE_MCSX = "mcsx";
    public static final String MODE_CUSTOM = "custom";

    public static final int MCSX_WIDTH = 1280;
    public static final int MCSX_HEIGHT = 960;
    public static final int DEFAULT_CUSTOM_WIDTH = 1920;
    public static final int DEFAULT_CUSTOM_HEIGHT = 1080;
    public static final int MIN_CUSTOM_DIMENSION = 320;
    public static final int MAX_CUSTOM_DIMENSION = 7680;

    private static final String PREFS_NAME = "launcher_resolution_preferences";
    private static final String KEY_MODE = "game_resolution_mode";
    private static final String KEY_CUSTOM_WIDTH = "game_resolution_custom_width";
    private static final String KEY_CUSTOM_HEIGHT = "game_resolution_custom_height";
    private static final String PROFILE_FILE_NAME = "game_resolution_profile.txt";

    @Nullable
    private static volatile Profile runtimeProfileOverride;

    private GameResolutionSettings() {
    }

    @NonNull
    public static Profile getProfile(@NonNull Context context) {
        Profile fromFile = readProfileFile(context);
        if (fromFile != null) return fromFile;

        SharedPreferences preferences = preferences(context);
        Profile profile = new Profile(
                normalizeMode(preferences.getString(KEY_MODE, MODE_NATIVE)),
                clampCustomDimension(preferences.getInt(KEY_CUSTOM_WIDTH, DEFAULT_CUSTOM_WIDTH)),
                clampCustomDimension(preferences.getInt(KEY_CUSTOM_HEIGHT, DEFAULT_CUSTOM_HEIGHT))
        );
        writeProfileFile(context, profile);
        return profile;
    }

    public static void setMode(@NonNull Context context, @Nullable String mode) {
        Profile current = getProfile(context);
        setProfile(context, new Profile(normalizeMode(mode), current.customWidth, current.customHeight));
    }

    public static void setCustomResolution(@NonNull Context context, int width, int height) {
        setProfile(context, new Profile(
                MODE_CUSTOM,
                clampCustomDimension(width),
                clampCustomDimension(height)
        ));
    }

    public static void setRuntimeProfileOverride(@Nullable Profile profile) {
        runtimeProfileOverride = profile == null
                ? null
                : new Profile(profile.mode, profile.customWidth, profile.customHeight);
    }

    public static void clearRuntimeProfileOverride() {
        runtimeProfileOverride = null;
    }

    @Nullable
    public static Profile getRuntimeProfileOverride() {
        Profile profile = runtimeProfileOverride;
        return profile == null
                ? null
                : new Profile(profile.mode, profile.customWidth, profile.customHeight);
    }

    public static void setProfile(@NonNull Context context, @NonNull Profile profile) {
        Profile safe = new Profile(
                normalizeMode(profile.mode),
                clampCustomDimension(profile.customWidth),
                clampCustomDimension(profile.customHeight)
        );

        SharedPreferences.Editor editor = preferences(context).edit()
                .putString(KEY_MODE, safe.mode)
                .putInt(KEY_CUSTOM_WIDTH, safe.customWidth)
                .putInt(KEY_CUSTOM_HEIGHT, safe.customHeight);
        if (!editor.commit()) editor.apply();
        writeProfileFile(context, safe);
    }

    @NonNull
    public static ResolvedResolution resolveBaseResolution(
            @NonNull Context context,
            int availableWidth,
            int availableHeight
    ) {
        Profile profile = runtimeProfileOverride;
        if (profile == null) profile = getProfile(context);
        return resolveBaseResolution(profile, availableWidth, availableHeight);
    }

    @NonNull
    public static ResolvedResolution resolveBaseResolution(
            @NonNull Profile profile,
            int availableWidth,
            int availableHeight
    ) {
        int safeWidth = Math.max(1, availableWidth);
        int safeHeight = Math.max(1, availableHeight);

        if (MODE_1920_1080.equals(profile.mode)) {
            boolean portrait = safeHeight > safeWidth;
            return new ResolvedResolution(
                    portrait ? 1080 : 1920,
                    portrait ? 1920 : 1080,
                    profile.mode
            );
        }

        if (MODE_BEST_4_3.equals(profile.mode)) {
            boolean portrait = safeHeight > safeWidth;
            return fitAspectInside(
                    safeWidth,
                    safeHeight,
                    portrait ? 3 : 4,
                    portrait ? 4 : 3,
                    profile.mode
            );
        }

        if (MODE_MCSX.equals(profile.mode)) {
            // MCSX is designed around a fixed 4:3 framebuffer. Keep the exact size
            // instead of adapting it to the phone so its menus and gameplay scale
            // consistently across devices.
            return new ResolvedResolution(MCSX_WIDTH, MCSX_HEIGHT, profile.mode);
        }

        if (MODE_CUSTOM.equals(profile.mode)) {
            return new ResolvedResolution(profile.customWidth, profile.customHeight, profile.mode);
        }

        return new ResolvedResolution(safeWidth, safeHeight, MODE_NATIVE);
    }

    @NonNull
    public static ResolvedResolution resolveRenderResolution(
            @NonNull Context context,
            int availableWidth,
            int availableHeight,
            int resolutionScalePercent
    ) {
        ResolvedResolution base = resolveBaseResolution(context, availableWidth, availableHeight);
        if (MODE_MCSX.equals(base.mode)) {
            // Keep the recommended MCSX framebuffer exact. Applying the launcher-wide
            // percentage here would turn 1280x960 into another size and defeat this preset.
            return base;
        }
        int percent = Math.max(1, Math.min(100, resolutionScalePercent));
        return new ResolvedResolution(
                Math.max(1, Math.round(base.width * (percent / 100f))),
                Math.max(1, Math.round(base.height * (percent / 100f))),
                base.mode
        );
    }

    @NonNull
    public static DisplayBounds resolveDisplayBounds(
            @NonNull Context context,
            int availableWidth,
            int availableHeight
    ) {
        ResolvedResolution base = resolveBaseResolution(context, availableWidth, availableHeight);
        return resolveDisplayBounds(availableWidth, availableHeight, base);
    }

    @NonNull
    public static DisplayBounds resolveDisplayBounds(
            int availableWidth,
            int availableHeight,
            @NonNull ResolvedResolution base
    ) {
        int safeWidth = Math.max(1, availableWidth);
        int safeHeight = Math.max(1, availableHeight);
        if (MODE_NATIVE.equals(base.mode)) {
            return new DisplayBounds(0, 0, safeWidth, safeHeight);
        }

        double targetAspect = base.width / (double) Math.max(1, base.height);
        double availableAspect = safeWidth / (double) Math.max(1, safeHeight);

        int contentWidth;
        int contentHeight;
        if (availableAspect > targetAspect) {
            contentHeight = safeHeight;
            contentWidth = Math.max(1, (int) Math.round(contentHeight * targetAspect));
        } else {
            contentWidth = safeWidth;
            contentHeight = Math.max(1, (int) Math.round(contentWidth / targetAspect));
        }

        contentWidth = Math.min(safeWidth, contentWidth);
        contentHeight = Math.min(safeHeight, contentHeight);
        return new DisplayBounds(
                Math.max(0, (safeWidth - contentWidth) / 2),
                Math.max(0, (safeHeight - contentHeight) / 2),
                contentWidth,
                contentHeight
        );
    }

    @NonNull
    public static String normalizeMode(@Nullable String mode) {
        if (mode == null) return MODE_NATIVE;
        String value = mode.trim().toLowerCase(Locale.ROOT);
        if (MODE_1920_1080.equals(value)
                || MODE_BEST_4_3.equals(value)
                || MODE_MCSX.equals(value)
                || MODE_CUSTOM.equals(value)) {
            return value;
        }
        return MODE_NATIVE;
    }

    public static int clampCustomDimension(int value) {
        if (value < MIN_CUSTOM_DIMENSION) return MIN_CUSTOM_DIMENSION;
        if (value > MAX_CUSTOM_DIMENSION) return MAX_CUSTOM_DIMENSION;
        return value;
    }

    @NonNull
    private static ResolvedResolution fitAspectInside(
            int availableWidth,
            int availableHeight,
            int aspectWidth,
            int aspectHeight,
            @NonNull String mode
    ) {
        int safeWidth = Math.max(1, availableWidth);
        int safeHeight = Math.max(1, availableHeight);

        int widthFromHeight = (safeHeight / aspectHeight) * aspectWidth;
        int heightFromWidth = (safeWidth / aspectWidth) * aspectHeight;

        int width;
        int height;
        if (widthFromHeight > 0 && widthFromHeight <= safeWidth) {
            width = widthFromHeight;
            height = (width / aspectWidth) * aspectHeight;
        } else {
            height = Math.max(aspectHeight, heightFromWidth);
            width = (height / aspectHeight) * aspectWidth;
        }

        width = Math.max(aspectWidth, Math.min(safeWidth, width));
        height = Math.max(aspectHeight, Math.min(safeHeight, height));

        // Re-align after clamping so the result remains an exact 4:3 or 3:4 size.
        int factor = Math.max(1, Math.min(width / aspectWidth, height / aspectHeight));
        width = aspectWidth * factor;
        height = aspectHeight * factor;
        return new ResolvedResolution(width, height, mode);
    }

    @SuppressWarnings("deprecation")
    @NonNull
    private static SharedPreferences preferences(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS
        );
    }

    @Nullable
    private static Profile readProfileFile(@NonNull Context context) {
        File file = profileFile(context);
        if (!file.isFile()) return null;

        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(file),
                StandardCharsets.UTF_8
        )) {
            StringBuilder builder = new StringBuilder();
            char[] buffer = new char[128];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, read);
                if (builder.length() > 256) return null;
            }

            String[] values = builder.toString().trim().split(",");
            if (values.length != 3) return null;
            return new Profile(
                    normalizeMode(values[0]),
                    clampCustomDimension(Integer.parseInt(values[1].trim())),
                    clampCustomDimension(Integer.parseInt(values[2].trim()))
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void writeProfileFile(@NonNull Context context, @NonNull Profile profile) {
        File target = profileFile(context);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }

        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        String value = profile.mode + "," + profile.customWidth + "," + profile.customHeight;
        try (FileOutputStream stream = new FileOutputStream(temp);
             OutputStreamWriter writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8)) {
            writer.write(value);
            writer.flush();
            try {
                stream.getFD().sync();
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
            return;
        }

        if (!temp.renameTo(target)) {
            try (FileOutputStream stream = new FileOutputStream(target);
                 OutputStreamWriter writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8)) {
                writer.write(value);
                writer.flush();
                try {
                    stream.getFD().sync();
                } catch (Throwable ignored) {
                }
            } catch (Throwable ignored) {
            }
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    @NonNull
    private static File profileFile(@NonNull Context context) {
        return new File(context.getApplicationContext().getFilesDir(), PROFILE_FILE_NAME);
    }

    public static final class Profile {
        @NonNull public final String mode;
        public final int customWidth;
        public final int customHeight;

        public Profile(@NonNull String mode, int customWidth, int customHeight) {
            this.mode = normalizeMode(mode);
            this.customWidth = clampCustomDimension(customWidth);
            this.customHeight = clampCustomDimension(customHeight);
        }
    }

    public static final class ResolvedResolution {
        public final int width;
        public final int height;
        @NonNull public final String mode;

        public ResolvedResolution(int width, int height, @NonNull String mode) {
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
            this.mode = normalizeMode(mode);
        }
    }

    public static final class DisplayBounds {
        public final int left;
        public final int top;
        public final int width;
        public final int height;

        public DisplayBounds(int left, int top, int width, int height) {
            this.left = Math.max(0, left);
            this.top = Math.max(0, top);
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
        }
    }
}

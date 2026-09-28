/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * Clean-main rewrite pass: DroidBridge-owned implementation surface.
 */

package ca.dnamobile.droidbridgelauncher.dualscreen;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

/**
 * Small disk/memory cache for finished hotbar item icons.
 *
 * This is intentionally separate from DualScreenControlsView so the UI view can stay
 * dumb: it draws a PNG when one exists and falls back to the existing renderer while a
 * future Minecraft-side/offline renderer fills the cache. The path is compatible with
 * the renderer-service layout:
 *
 *   <game>/droidbridge_dual_screen_assets/rendered_icons/*.png
 */
final class DualScreenRenderedIconCache {
    private static final String TAG = "DualScreenRenderedIconCache";
    private static final int MAX_MEMORY_ICONS = 160;

    @NonNull private final File gameDir;
    @NonNull private final File renderedIconsDir;
    @NonNull private final Map<String, Bitmap> memory = new HashMap<>();

    DualScreenRenderedIconCache(@NonNull File hudStateFile) {
        File gameDir = hudStateFile.getParentFile();
        if (gameDir != null && "game".equalsIgnoreCase(gameDir.getName()) == false) {
            File maybeGame = new File(gameDir, "game");
            if (maybeGame.isDirectory()) gameDir = maybeGame;
        }
        if (gameDir == null) gameDir = new File(".");
        this.gameDir = gameDir;
        renderedIconsDir = new File(new File(gameDir, "droidbridge_dual_screen_assets"), "rendered_icons");
    }

    /**
     * Extracts a vetted special-item PNG from the installed companion mod once, then serves the
     * copied file from memory/disk. This is the stable path for banners, pots, heads, scaffolding
     * and other vanilla items whose inventory icon is produced by a special Minecraft renderer.
     */
    @Nullable
    Bitmap getEmbeddedReference(@NonNull String rawName) {
        String clean = rawName.trim().toLowerCase(Locale.ROOT);
        if (clean.endsWith(".png")) clean = clean.substring(0, clean.length() - 4);
        clean = clean.replaceAll("[^a-z0-9_]+", "_");
        if (clean.isEmpty()) return null;

        String memoryKey = "embedded/reference-v1/" + clean;
        Bitmap cached = memory.get(memoryKey);
        if (cached != null && !cached.isRecycled()) return cached;

        if (!renderedIconsDir.isDirectory() && !renderedIconsDir.mkdirs()) return null;
        File destination = new File(renderedIconsDir, "embedded_" + clean + ".png");
        Bitmap existing = loadFile(destination, memoryKey);
        if (existing != null && !existing.isRecycled()) return existing;

        String[] entries = new String[] {
                "assets/droidbridge_dualscreen/reference_icons/" + clean + ".png",
                "assets/droidbridge_dualscreen/rendered_icons/" + clean + ".png"
        };
        for (File jar : findBundledIconJarCandidates()) {
            if (jar == null || !jar.isFile() || jar.length() <= 0L) continue;
            try (ZipFile zip = new ZipFile(jar)) {
                for (String entryName : entries) {
                    ZipEntry entry = zip.getEntry(entryName);
                    if (entry == null || entry.isDirectory() || entry.getSize() == 0L) continue;
                    File temporary = new File(renderedIconsDir,
                            "embedded_" + clean + ".png.tmp");
                    try (InputStream input = zip.getInputStream(entry);
                         FileOutputStream output = new FileOutputStream(temporary)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                        output.flush();
                    }
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inScaled = false;
                    Bitmap decoded = BitmapFactory.decodeFile(temporary.getAbsolutePath(), options);
                    if (decoded == null || decoded.isRecycled()
                            || decoded.getWidth() <= 1 || decoded.getHeight() <= 1) {
                        if (decoded != null && !decoded.isRecycled()) decoded.recycle();
                        temporary.delete();
                        continue;
                    }
                    if (destination.isFile()) destination.delete();
                    if (!temporary.renameTo(destination)) {
                        decoded.recycle();
                        temporary.delete();
                        continue;
                    }
                    if (memory.size() > MAX_MEMORY_ICONS) clearMemory();
                    memory.put(memoryKey, decoded);
                    return decoded;
                }
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to extract embedded reference icon " + clean, throwable);
            }
        }
        return null;
    }

    @Nullable
    Bitmap getIfReady(@NonNull String itemId, @NonNull String itemKey, @Nullable String iconPath, int size) {
        String[] names = deterministicNames(itemId, itemKey, iconPath);

        // Asset-translator rework: never load pregenerated final PNG icons bundled inside
        // any DroidBridge dual-screen Fabric jar. The bottom HUD should render installed
        // Minecraft item definitions/assets on Android, then use only Android-generated
        // disk cache PNGs. This prevents old bundled chest/bed/pot/shulker images from
        // overriding the real asset translator path.

        File direct = directRenderedIconFile(iconPath);
        if (direct != null) {
            Bitmap b = loadFile(direct);
            if (b != null) return b;
        }

        for (String name : names) {
            File file = new File(renderedIconsDir, name + ".png");
            Bitmap b = loadFile(file, "det/" + name);
            if (b != null && !b.isRecycled()) return b;
        }

        String key = cacheKey(itemId, itemKey, iconPath, size);
        Bitmap cached = memory.get(key);
        if (cached != null && !cached.isRecycled()) return cached;
        File file = new File(renderedIconsDir, key + ".png");
        return loadFile(file, key);
    }


    private boolean isSpecialOrComplexItem(@Nullable String raw) {
        if (raw == null) return false;
        String value = raw.toLowerCase(Locale.ROOT);
        return value.contains("chest")
                || value.contains("bed")
                || value.contains("shulker")
                || value.contains("banner")
                || value.contains("decorated_pot")
                || value.contains("flower_pot")
                || value.contains("pottery_sherd")
                || value.contains("stonecutter")
                || value.contains("scaffolding")
                || value.contains("grindstone")
                || value.contains("anvil")
                || value.contains("trapdoor")
                || value.contains("stairs")
                || value.contains("_wall")
                || value.contains(":wall")
                || value.contains("_fence")
                || value.contains(":fence")
                || value.contains("_button")
                || value.contains(":button");
    }

    @NonNull
    private String[] deterministicNames(@Nullable String itemId, @Nullable String itemKey, @Nullable String iconPath) {
        ArrayList<String> names = new ArrayList<>();
        addDeterministicName(names, itemId);
        addDeterministicName(names, itemKey);
        addDeterministicName(names, iconPathName(iconPath));
        String k = itemKey == null ? "" : itemKey.trim().toLowerCase(Locale.ROOT);
        if (!k.isEmpty() && k.indexOf(':') < 0) addDeterministicName(names, "minecraft:" + k);
        addAliasNames(names);
        return names.toArray(new String[0]);
    }

    private void addDeterministicName(@NonNull ArrayList<String> names, @Nullable String raw) {
        if (raw == null) return;
        String value = raw.trim();
        if (value.isEmpty()) return;

        value = value.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < value.length()) value = value.substring(slash + 1);
        int dot = value.lastIndexOf('.');
        if (dot > 0) value = value.substring(0, dot);

        value = value.toLowerCase(Locale.ROOT);
        value = value.replace(':', '_');
        value = value.replaceAll("[^a-z0-9._-]+", "_");
        while (value.contains("__")) value = value.replace("__", "_");
        if (value.startsWith("_")) value = value.substring(1);
        if (value.endsWith("_")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty()) return;

        if (!names.contains(value)) names.add(value);
    }

    @NonNull
    private String iconPathName(@Nullable String rawPath) {
        if (rawPath == null) return "";
        String value = rawPath.trim().replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < value.length()) value = value.substring(slash + 1);
        int dot = value.lastIndexOf('.');
        if (dot > 0) value = value.substring(0, dot);
        return value;
    }

    private void addAliasNames(@NonNull ArrayList<String> names) {
        // Avoid duplicates while adding generic fallbacks. These help when the HUD state
        // only has a display label like "Bed"/"Banner" before the exact item id arrives.
        ArrayList<String> source = new ArrayList<>(names);
        for (String raw : source) {
            String original = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
            String value = original;
            if (value.startsWith("minecraft_")) value = value.substring("minecraft_".length());

            // 1.0.116: patterned banners can arrive as a base white_banner id plus an
            // Ominous Banner label, or as an icon-path name. Prefer the new pregenerated
            // vertical banner icon instead of the raw pattern/face tile.
            if (original.contains("ominous") && original.contains("banner")) {
                addDeterministicName(names, "minecraft:ominous_banner");
                addDeterministicName(names, "ominous_banner");
                addDeterministicName(names, "minecraft:white_banner");
                addDeterministicName(names, "white_banner");
            }

            if (value.equals("bed")) { addDeterministicName(names, "minecraft:white_bed"); addDeterministicName(names, "white_bed"); }
            if (value.equals("banner") || value.endsWith("_banner") || value.contains("banner")) {
                addDeterministicName(names, "minecraft:white_banner");
                addDeterministicName(names, "white_banner");
            }
            if (value.equals("stonecutter") || value.contains("stonecutter")) {
                addDeterministicName(names, "minecraft:stonecutter");
                addDeterministicName(names, "stonecutter");
            }
            if (value.equals("chest_boat") || value.equals("boat_with_chest") || value.contains("chest_boat")) { addDeterministicName(names, "minecraft:oak_chest_boat"); addDeterministicName(names, "oak_chest_boat"); }
            if (value.equals("shulker") || value.equals("shulker_box") || value.contains("shulker_box")) { addDeterministicName(names, "minecraft:shulker_box"); addDeterministicName(names, "shulker_box"); }
            if (value.equals("pot") || value.equals("decorative_pot") || value.equals("decorated_pot") || value.contains("decorated_pot")) { addDeterministicName(names, "minecraft:decorated_pot"); addDeterministicName(names, "decorated_pot"); }
        }
    }

    @Nullable
    private Bitmap loadBundledPreGenerated(@NonNull String[] names) {
        if (names.length == 0) return null;
        for (File jar : findBundledIconJarCandidates()) {
            if (jar == null || !jar.isFile() || jar.length() <= 0L) continue;
            Bitmap bitmap = tryLoadBundledFromJar(jar, names);
            if (bitmap != null && !bitmap.isRecycled()) return bitmap;
        }
        return null;
    }

    @NonNull
    private ArrayList<File> findBundledIconJarCandidates() {
        ArrayList<File> jars = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        addJarCandidates(jars, seen, new File(gameDir, "mods"));
        File parent = gameDir.getParentFile();
        if (parent != null) {
            addJarCandidates(jars, seen, new File(parent, "mods"));
            File grand = parent.getParentFile();
            if (grand != null) addJarCandidates(jars, seen, new File(grand, "mods"));
        }
        Collections.sort(jars, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                int version = Integer.compare(priorityForJarName(b.getName()), priorityForJarName(a.getName()));
                if (version != 0) return version;
                long diff = b.lastModified() - a.lastModified();
                if (diff > 0) return 1;
                if (diff < 0) return -1;
                return b.getName().compareToIgnoreCase(a.getName());
            }
        });
        return jars;
    }

    private int priorityForJarName(@NonNull String name) {
        String value = name.toLowerCase(Locale.ROOT);
        int score = 0;
        if (value.contains("1.0.144")) score += 30000;
        if (value.contains("1.0.142")) score += 20000;
        if (value.contains("1.0.141")) score += 10000;
        if (value.contains("combined")) score += 1000;
        if (value.contains("duoscreen")) score += 100;
        if (value.contains("dualscreen")) score += 50;
        return score;
    }

    private void addJarCandidates(@NonNull ArrayList<File> out, @NonNull Set<String> seen, @Nullable File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".jar")) continue;
            if (!name.contains("droidbridge") || !name.contains("dualscreen")) continue;
            String path = file.getAbsolutePath();
            if (seen.add(path)) out.add(file);
        }
    }

    @Nullable
    private Bitmap tryLoadBundledFromJar(@NonNull File jar, @NonNull String[] names) {
        try (ZipFile zip = new ZipFile(jar)) {
            for (String name : names) {
                if (name == null || name.length() == 0) continue;
                String entryName = "assets/droidbridge_dualscreen/rendered_icons/" + name + ".png";
                String cacheKey = "jar/" + jar.lastModified() + "/" + entryName;
                Bitmap cached = memory.get(cacheKey);
                if (cached != null && !cached.isRecycled()) return cached;
                ZipEntry entry = zip.getEntry(entryName);
                if (entry == null || entry.getSize() == 0L) continue;
                try (InputStream input = zip.getInputStream(entry)) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inScaled = false;
                    Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
                    if (bitmap != null && !bitmap.isRecycled()) {
                        if (memory.size() > MAX_MEMORY_ICONS) clearMemory();
                        memory.put(cacheKey, bitmap);
                        return bitmap;
                    }
                }
            }
        } catch (Throwable t) {
            // Not every DroidBridge jar has bundled icons. Keep this quiet unless decode fails badly.
        }
        return null;
    }

    void store(@NonNull String rawKey, @NonNull Bitmap bitmap) {
        if (bitmap.isRecycled() || bitmap.getWidth() <= 1 || bitmap.getHeight() <= 1) return;
        if (!renderedIconsDir.isDirectory() && !renderedIconsDir.mkdirs()) return;
        String key = sanitizeKey(rawKey);
        File file = new File(renderedIconsDir, key + ".png");
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Throwable t) {
            Logging.e(TAG, "Unable to store rendered icon " + file.getAbsolutePath(), t);
        }
    }

    void clearMemory() {
        for (Bitmap bitmap : memory.values()) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
        memory.clear();
    }

    @Nullable
    private File directRenderedIconFile(@Nullable String iconPath) {
        if (iconPath == null || iconPath.trim().isEmpty()) return null;
        String value = iconPath.trim();
        if (!value.toLowerCase(Locale.ROOT).contains("rendered_icons")) return null;
        File file = new File(value);
        return file.isFile() && file.length() > 0L ? file : null;
    }

    @Nullable
    private Bitmap loadFile(@NonNull File file) {
        return loadFile(file, file.getAbsolutePath());
    }

    @Nullable
    private Bitmap loadFile(@NonNull File file, @NonNull String key) {
        Bitmap cached = memory.get(key);
        if (cached != null && !cached.isRecycled()) return cached;
        if (!file.isFile() || file.length() <= 0L) return null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap != null) {
                if (memory.size() > MAX_MEMORY_ICONS) clearMemory();
                memory.put(key, bitmap);
                return bitmap;
            }
        } catch (Throwable t) {
            Logging.e(TAG, "Unable to load rendered icon " + file.getAbsolutePath(), t);
        }
        return null;
    }

    @NonNull
    private String cacheKey(@NonNull String itemId, @NonNull String itemKey, @Nullable String iconPath, int size) {
        String raw = (itemId == null ? "" : itemId) + "|" + itemKey + "|" + (iconPath == null ? "" : iconPath) + "|" + size;
        return sanitizeKey(raw);
    }

    @NonNull
    private String sanitizeKey(@NonNull String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes("UTF-8"));
            StringBuilder out = new StringBuilder(34);
            out.append("i_");
            for (int i = 0; i < 16 && i < hash.length; i++) {
                int b = hash[i] & 0xff;
                if (b < 16) out.append('0');
                out.append(Integer.toHexString(b));
            }
            return out.toString();
        } catch (Throwable ignored) {
            String value = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
            return value.length() > 80 ? value.substring(0, 80) : value;
        }
    }
}

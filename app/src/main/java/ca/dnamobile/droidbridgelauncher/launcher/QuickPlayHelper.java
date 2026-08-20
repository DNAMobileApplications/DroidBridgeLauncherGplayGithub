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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;
import ca.dnamobile.droidbridgelauncher.runtime.Tools;

/**
 * Small launcher-side helpers for Minecraft Java Edition Quick Play.
 *
 * Vanilla Quick Play is available in modern 1.20+ clients. The launch builder
 * still validates support before appending args; these helpers are used by the
 * UI so old instances simply launch normally instead of showing dead actions.
 */
public final class QuickPlayHelper {
    private static final String TAG = "QuickPlayHelper";

    private static final byte TAG_END = 0;
    private static final byte TAG_BYTE = 1;
    private static final byte TAG_SHORT = 2;
    private static final byte TAG_INT = 3;
    private static final byte TAG_LONG = 4;
    private static final byte TAG_FLOAT = 5;
    private static final byte TAG_DOUBLE = 6;
    private static final byte TAG_BYTE_ARRAY = 7;
    private static final byte TAG_STRING = 8;
    private static final byte TAG_LIST = 9;
    private static final byte TAG_COMPOUND = 10;
    private static final byte TAG_INT_ARRAY = 11;
    private static final byte TAG_LONG_ARRAY = 12;

    private QuickPlayHelper() {
    }

    public static boolean supportsQuickPlay(@Nullable String... versionIds) {
        if (versionIds == null) return false;
        for (String versionId : versionIds) {
            if (supportsQuickPlayVersionId(versionId)) return true;
        }
        return false;
    }

    public static boolean supportsQuickPlayVersionId(@Nullable String versionId) {
        if (versionId == null) return false;

        String value = versionId.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return false;

        // DroidBridge uses 26.x ids for modern snapshot/release tracks.
        int[] numbers = parseVersionNumbers(value);
        if (numbers.length > 0 && numbers[0] >= 26) return true;

        // Snapshot support began around the 23w14a Quick Play rollout.
        if (value.matches("^\\d{2}w\\d{2}[a-z].*$")) {
            try {
                int year = Integer.parseInt(value.substring(0, 2));
                int week = Integer.parseInt(value.substring(3, 5));
                return year > 23 || (year == 23 && week >= 14);
            } catch (Throwable ignored) {
            }
        }

        // Release/loader ids can be "1.20.1", "fabric-loader-0.16.14-1.21.5", etc.
        for (int i = 0; i + 1 < numbers.length; i++) {
            if (numbers[i] == 1 && numbers[i + 1] >= 20) return true;
        }

        return false;
    }

    @Nullable
    public static String findLastPlayedWorldFolderName(@Nullable File gameDirectory) {
        if (gameDirectory == null) return null;

        String logged = findWorldFromQuickPlayLog(gameDirectory);
        if (isExistingWorld(gameDirectory, logged)) return logged;

        File savesDir = new File(gameDirectory, "saves");
        File[] worlds = savesDir.listFiles(File::isDirectory);
        if (worlds == null || worlds.length == 0) return null;

        ArrayList<File> candidates = new ArrayList<>();
        for (File world : worlds) {
            if (new File(world, "level.dat").isFile()) {
                candidates.add(world);
            }
        }
        if (candidates.isEmpty()) return null;

        candidates.sort(Comparator.comparingLong(QuickPlayHelper::latestWorldTimestamp).reversed());
        return candidates.get(0).getName();
    }


    @NonNull
    public static ArrayList<WorldEntry> readWorldList(@Nullable File gameDirectory) {
        ArrayList<WorldEntry> worlds = new ArrayList<>();
        if (gameDirectory == null) return worlds;

        File savesDir = new File(gameDirectory, "saves");
        File[] saveDirectories = savesDir.listFiles(File::isDirectory);
        if (saveDirectories == null || saveDirectories.length == 0) return worlds;

        ArrayList<File> candidates = new ArrayList<>();
        for (File worldDirectory : saveDirectories) {
            if (new File(worldDirectory, "level.dat").isFile()) {
                candidates.add(worldDirectory);
            }
        }

        candidates.sort(Comparator.comparingLong(QuickPlayHelper::latestWorldTimestamp).reversed());
        for (File worldDirectory : candidates) {
            String folderName = worldDirectory.getName();
            String displayName = readWorldDisplayName(worldDirectory);
            if (displayName == null || displayName.trim().isEmpty()) {
                displayName = folderName;
            }
            worlds.add(new WorldEntry(displayName.trim(), folderName, latestWorldTimestamp(worldDirectory)));
        }
        return worlds;
    }

    @NonNull
    public static ArrayList<ServerEntry> readServerList(@Nullable File gameDirectory) {
        ArrayList<ServerEntry> servers = new ArrayList<>();
        if (gameDirectory == null) return servers;

        File serversDat = new File(gameDirectory, "servers.dat");
        if (!serversDat.isFile()) return servers;

        try (InputStream input = openPossiblyGzipped(serversDat);
             DataInputStream data = new DataInputStream(input)) {
            byte rootType = data.readByte();
            if (rootType != TAG_COMPOUND) return servers;
            readNbtString(data); // root name
            readRootCompoundForServers(data, servers);
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to read servers.dat: " + throwable.getMessage());
        }

        return servers;
    }

    @Nullable
    private static String findWorldFromQuickPlayLog(@NonNull File gameDirectory) {
        File log = new File(new File(gameDirectory, "quickPlay"), "log.json");
        if (!log.isFile()) return null;

        try (FileInputStream input = new FileInputStream(log)) {
            String jsonText = Tools.read(input);
            Object json;
            String trimmed = jsonText == null ? "" : jsonText.trim();
            if (trimmed.startsWith("[")) json = new JSONArray(trimmed);
            else json = new JSONObject(trimmed);
            return findLikelyWorldName(json, new HashSet<>());
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to read Quick Play log: " + throwable.getMessage());
            return null;
        }
    }

    @Nullable
    private static String findLikelyWorldName(@Nullable Object value, @NonNull Set<Object> visited) {
        if (value == null || value == JSONObject.NULL) return null;
        if (!visited.add(value)) return null;

        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String[] keys = new String[]{
                    "quickPlaySingleplayer",
                    "singleplayer",
                    "worldFolder",
                    "worldName",
                    "levelName",
                    "folderName"
            };
            for (String key : keys) {
                String direct = object.optString(key, "").trim();
                if (!direct.isEmpty()) return direct;
            }

            JSONArray names = object.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String found = findLikelyWorldName(object.opt(names.optString(i)), visited);
                    if (found != null && !found.trim().isEmpty()) return found.trim();
                }
            }
            return null;
        }

        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                String found = findLikelyWorldName(array.opt(i), visited);
                if (found != null && !found.trim().isEmpty()) return found.trim();
            }
        }

        return null;
    }

    private static boolean isExistingWorld(@NonNull File gameDirectory, @Nullable String worldName) {
        if (worldName == null || worldName.trim().isEmpty()) return false;
        return new File(new File(gameDirectory, "saves"), worldName.trim()).isDirectory();
    }

    private static long latestWorldTimestamp(@NonNull File worldDirectory) {
        long latest = worldDirectory.lastModified();
        File level = new File(worldDirectory, "level.dat");
        if (level.isFile()) latest = Math.max(latest, level.lastModified());
        File session = new File(worldDirectory, "session.lock");
        if (session.isFile()) latest = Math.max(latest, session.lastModified());
        return latest;
    }


    @Nullable
    private static String readWorldDisplayName(@NonNull File worldDirectory) {
        File levelDat = new File(worldDirectory, "level.dat");
        if (!levelDat.isFile()) return null;

        try (InputStream input = openPossiblyGzipped(levelDat);
             DataInputStream data = new DataInputStream(input)) {
            byte rootType = data.readByte();
            if (rootType != TAG_COMPOUND) return null;
            readNbtString(data); // root name
            return readNamedStringFromCompound(data, "LevelName");
        } catch (Throwable throwable) {
            Logging.i(TAG, "Unable to read world name from "
                    + worldDirectory.getName()
                    + ": "
                    + throwable.getMessage());
            return null;
        }
    }

    @Nullable
    private static String readNamedStringFromCompound(
            @NonNull DataInputStream data,
            @NonNull String wantedName
    ) throws IOException {
        while (true) {
            byte type = data.readByte();
            if (type == TAG_END) return null;

            String name = readNbtString(data);
            if (type == TAG_STRING && wantedName.equals(name)) {
                String value = readNbtString(data);
                return value == null || value.trim().isEmpty() ? null : value.trim();
            }

            if (type == TAG_COMPOUND) {
                String nested = readNamedStringFromCompound(data, wantedName);
                if (nested != null && !nested.trim().isEmpty()) return nested.trim();
                continue;
            }

            skipPayload(data, type);
        }
    }

    @NonNull
    private static InputStream openPossiblyGzipped(@NonNull File file) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(new FileInputStream(file));
        buffered.mark(2);
        int first = buffered.read();
        int second = buffered.read();
        buffered.reset();
        if (first == 0x1f && second == 0x8b) {
            return new GZIPInputStream(buffered);
        }
        return buffered;
    }

    private static void readRootCompoundForServers(
            @NonNull DataInputStream data,
            @NonNull ArrayList<ServerEntry> out
    ) throws IOException {
        while (true) {
            byte type = data.readByte();
            if (type == TAG_END) return;

            String name = readNbtString(data);
            if (type == TAG_LIST && "servers".equals(name)) {
                readServersListPayload(data, out);
            } else {
                skipPayload(data, type);
            }
        }
    }

    private static void readServersListPayload(
            @NonNull DataInputStream data,
            @NonNull ArrayList<ServerEntry> out
    ) throws IOException {
        byte childType = data.readByte();
        int length = data.readInt();
        if (length < 0) throw new IOException("Negative NBT list length");

        if (childType != TAG_COMPOUND) {
            for (int i = 0; i < length; i++) skipPayload(data, childType);
            return;
        }

        for (int i = 0; i < length; i++) {
            ServerEntry entry = readServerCompound(data);
            if (entry != null) out.add(entry);
        }
    }

    @Nullable
    private static ServerEntry readServerCompound(@NonNull DataInputStream data) throws IOException {
        String name = "";
        String address = "";

        while (true) {
            byte type = data.readByte();
            if (type == TAG_END) break;

            String tagName = readNbtString(data);
            if (type == TAG_STRING && "name".equals(tagName)) {
                name = readNbtString(data);
            } else if (type == TAG_STRING && "ip".equals(tagName)) {
                address = readNbtString(data);
            } else {
                skipPayload(data, type);
            }
        }

        address = address == null ? "" : address.trim();
        if (address.isEmpty()) return null;
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) name = address;
        return new ServerEntry(name, address);
    }

    @NonNull
    private static String readNbtString(@NonNull DataInputStream data) throws IOException {
        int length = data.readUnsignedShort();
        if (length == 0) return "";
        byte[] bytes = new byte[length];
        data.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void skipPayload(@NonNull DataInputStream data, byte type) throws IOException {
        switch (type) {
            case TAG_BYTE:
                data.skipBytes(1);
                return;
            case TAG_SHORT:
                data.skipBytes(2);
                return;
            case TAG_INT:
            case TAG_FLOAT:
                data.skipBytes(4);
                return;
            case TAG_LONG:
            case TAG_DOUBLE:
                data.skipBytes(8);
                return;
            case TAG_BYTE_ARRAY: {
                int length = data.readInt();
                skipFully(data, length);
                return;
            }
            case TAG_STRING:
                readNbtString(data);
                return;
            case TAG_LIST: {
                byte childType = data.readByte();
                int length = data.readInt();
                if (length < 0) throw new IOException("Negative NBT list length");
                for (int i = 0; i < length; i++) skipPayload(data, childType);
                return;
            }
            case TAG_COMPOUND:
                while (true) {
                    byte childType = data.readByte();
                    if (childType == TAG_END) return;
                    readNbtString(data);
                    skipPayload(data, childType);
                }
            case TAG_INT_ARRAY: {
                int length = data.readInt();
                skipFully(data, length * 4L);
                return;
            }
            case TAG_LONG_ARRAY: {
                int length = data.readInt();
                skipFully(data, length * 8L);
                return;
            }
            case TAG_END:
                return;
            default:
                throw new EOFException("Unsupported NBT tag: " + type);
        }
    }

    private static void skipFully(@NonNull DataInputStream data, long bytes) throws IOException {
        if (bytes < 0) throw new IOException("Negative NBT payload length");
        long remaining = bytes;
        while (remaining > 0) {
            int skipped = data.skipBytes((int) Math.min(Integer.MAX_VALUE, remaining));
            if (skipped <= 0) {
                if (data.read() == -1) throw new EOFException("Unexpected EOF while skipping NBT payload");
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    @NonNull
    private static int[] parseVersionNumbers(@NonNull String versionId) {
        String normalized = versionId.replaceAll("[^0-9]+", ".");
        String[] parts = normalized.split("\\.+");
        ArrayList<Integer> numbers = new ArrayList<>();

        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) continue;
            try {
                numbers.add(Integer.parseInt(part.trim()));
            } catch (Throwable ignored) {
            }
        }

        int[] values = new int[numbers.size()];
        for (int i = 0; i < numbers.size(); i++) {
            values[i] = numbers.get(i);
        }
        return values;
    }

    @Nullable
    public static ParsedServerAddress parseServerAddress(@Nullable String rawAddress) {
        String value = rawAddress == null ? "" : rawAddress.trim();
        if (value.isEmpty()) return null;

        String host = value;
        int port = 25565;

        // Bracketed IPv6, for example [2001:db8::1]:25566
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close > 1) {
                host = value.substring(1, close).trim();
                if (close + 1 < value.length() && value.charAt(close + 1) == ':') {
                    port = parsePortOrDefault(value.substring(close + 2), port);
                }
            }
        } else {
            int firstColon = value.indexOf(':');
            int lastColon = value.lastIndexOf(':');

            // Treat a single colon as host:port. Multiple colons are likely raw IPv6.
            if (firstColon > 0 && firstColon == lastColon) {
                host = value.substring(0, firstColon).trim();
                port = parsePortOrDefault(value.substring(firstColon + 1), port);
            }
        }

        if (host == null) return null;
        host = host.trim();
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1).trim();
        }
        if (host.isEmpty()) return null;
        return new ParsedServerAddress(host, port);
    }

    private static int parsePortOrDefault(@Nullable String portText, int defaultPort) {
        String value = portText == null ? "" : portText.trim();
        if (value.isEmpty()) return defaultPort;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 0 && parsed <= 65535) return parsed;
        } catch (Throwable ignored) {
        }
        return defaultPort;
    }

    public static final class ParsedServerAddress {
        @NonNull
        public final String host;
        public final int port;

        public ParsedServerAddress(@NonNull String host, int port) {
            this.host = host;
            this.port = port;
        }
    }


    public static final class WorldEntry {
        @NonNull
        public final String name;
        @NonNull
        public final String folderName;
        public final long lastModifiedMs;

        public WorldEntry(@NonNull String name, @NonNull String folderName, long lastModifiedMs) {
            this.name = name;
            this.folderName = folderName;
            this.lastModifiedMs = lastModifiedMs;
        }

        @NonNull
        public String displayText() {
            if (name.equals(folderName)) return folderName;
            return name + "\n" + folderName;
        }
    }

    public static final class ServerEntry {
        @NonNull
        public final String name;
        @NonNull
        public final String address;

        public ServerEntry(@NonNull String name, @NonNull String address) {
            this.name = name;
            this.address = address;
        }

        @NonNull
        public String displayText() {
            if (name.equals(address)) return address;
            return name + "\n" + address;
        }
    }
}

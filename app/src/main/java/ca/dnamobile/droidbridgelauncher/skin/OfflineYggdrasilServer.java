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

package ca.dnamobile.droidbridgelauncher.skin;

import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

/**
 * Tiny launcher-side authlib-injector compatible Yggdrasil server for custom offline skins.
 *
 * This intentionally avoids Ktor/NanoHTTPD so DroidBridge does not need another dependency.
 * It only implements the authlib-injector routes Minecraft needs for local skin resolution.
 */
public final class OfflineYggdrasilServer {
    private static final String TAG = "OfflineYggdrasilServer";

    private final int requestedPort;
    private final String serverName;
    private final String implementationName;
    private final String implementationVersion;
    private final Map<String, CharacterProfile> charactersByUuid = new ConcurrentHashMap<>();
    private final Map<String, CharacterProfile> charactersByName = new ConcurrentHashMap<>();
    private final KeyPair keyPair;

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread serverThread;

    public OfflineYggdrasilServer() throws Exception {
        this("DroidBridge_Offline", "DroidBridge", "1.0");
    }

    public OfflineYggdrasilServer(@NonNull String serverName,
                                  @NonNull String implementationName,
                                  @NonNull String implementationVersion) throws Exception {
        this(0, serverName, implementationName, implementationVersion);
    }

    public OfflineYggdrasilServer(int requestedPort,
                                  @NonNull String serverName,
                                  @NonNull String implementationName,
                                  @NonNull String implementationVersion) throws Exception {
        this.requestedPort = Math.max(0, requestedPort);
        this.serverName = serverName;
        this.implementationName = implementationName;
        this.implementationVersion = implementationVersion;
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        this.keyPair = generator.generateKeyPair();
    }

    public synchronized void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket(requestedPort, 50, InetAddress.getByName("127.0.0.1"));
        running = true;
        serverThread = new Thread(this::acceptLoop, "DroidBridgeOfflineYggdrasilServer");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Throwable ignored) {
        }
        serverSocket = null;
    }

    public int getPort() {
        ServerSocket socket = serverSocket;
        if (!running || socket == null || socket.isClosed()) return -1;
        return socket.getLocalPort();
    }

    public void addCharacter(@NonNull String uuid,
                             @NonNull String name,
                             @Nullable File skinFile,
                             @NonNull SkinModelType model) throws Exception {
        byte[] skinBytes = readSkinBytes(skinFile);
        String skinHash = skinBytes != null ? sha256(skinBytes) : null;
        String normalizedUuid = uuid.replace("-", "").toLowerCase(Locale.ROOT);
        CharacterProfile profile = new CharacterProfile(normalizedUuid, name, skinHash, skinBytes, model);
        charactersByUuid.put(profile.uuid.toLowerCase(Locale.ROOT), profile);
        charactersByName.put(profile.name.toLowerCase(Locale.ROOT), profile);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                Thread handler = new Thread(() -> handleSocket(socket), "DroidBridgeOfflineYggdrasilRequest");
                handler.setDaemon(true);
                handler.start();
            } catch (Throwable throwable) {
                if (running) Logging.e(TAG, "Accept failed", throwable);
            }
        }
    }

    private void handleSocket(@NonNull Socket socket) {
        try (Socket ignored = socket;
             InputStream input = socket.getInputStream();
             OutputStream output = socket.getOutputStream()) {
            HttpRequest request = readRequest(input);
            if (request == null) {
                writeResponse(output, 400, "text/plain; charset=utf-8", "Bad Request".getBytes(StandardCharsets.UTF_8));
                return;
            }
            route(request, output);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Request failed", throwable);
        }
    }

    private void route(@NonNull HttpRequest request, @NonNull OutputStream output) throws Exception {
        String path = request.path;
        if ("/".equals(path)) {
            writeJson(output, root());
            return;
        }
        if ("/status".equals(path)) {
            writeJson(output, status());
            return;
        }
        if ("POST".equals(request.method) && "/api/profiles/minecraft".equals(path)) {
            writeJson(output, profiles(request.body));
            return;
        }
        if ("/sessionserver/session/minecraft/hasJoined".equals(path)) {
            writeJson(output, hasJoined(request.query.get("username")));
            return;
        }
        if ("POST".equals(request.method) && "/sessionserver/session/minecraft/join".equals(path)) {
            writeResponse(output, 204, "text/plain; charset=utf-8", new byte[0]);
            return;
        }
        if (path.startsWith("/sessionserver/session/minecraft/profile/")) {
            String uuid = path.substring("/sessionserver/session/minecraft/profile/".length());
            writeJson(output, profile(uuid));
            return;
        }
        if (path.startsWith("/textures/")) {
            String hash = path.substring("/textures/".length());
            writeTexture(output, hash);
            return;
        }
        writeResponse(output, 404, "application/json; charset=utf-8", "{}".getBytes(StandardCharsets.UTF_8));
    }

    @NonNull
    private String root() throws Exception {
        JSONObject meta = new JSONObject();
        meta.put("serverName", serverName);
        meta.put("implementationName", implementationName);
        meta.put("implementationVersion", implementationVersion);
        meta.put("feature.non_email_login", true);

        JSONObject root = new JSONObject();
        root.put("skinDomains", new JSONArray().put("127.0.0.1").put("localhost"));
        root.put("meta", meta);
        root.put("signaturePublickey", toPemPublicKey(keyPair.getPublic()));
        return root.toString();
    }

    @NonNull
    private String status() throws Exception {
        JSONObject json = new JSONObject();
        json.put("user.count", charactersByUuid.size());
        json.put("token.count", 0);
        return json.toString();
    }

    @NonNull
    private String profiles(@NonNull byte[] body) throws Exception {
        JSONArray names = new JSONArray(new String(body, StandardCharsets.UTF_8));
        JSONArray out = new JSONArray();
        for (int i = 0; i < names.length(); i++) {
            String name = names.optString(i, "");
            CharacterProfile profile = charactersByName.get(name.toLowerCase(Locale.ROOT));
            if (profile == null) continue;
            JSONObject item = new JSONObject();
            item.put("id", profile.uuid);
            item.put("name", profile.name);
            out.put(item);
        }
        return out.toString();
    }

    @NonNull
    private String hasJoined(@Nullable String username) throws Exception {
        if (username == null || username.trim().isEmpty()) return new JSONObject().toString();
        CharacterProfile profile = charactersByName.get(username.toLowerCase(Locale.ROOT));
        return profile != null ? profile.toCompleteResponse(rootUrl(), this::sign) : new JSONObject().toString();
    }

    @NonNull
    private String profile(@NonNull String uuid) throws Exception {
        CharacterProfile profile = charactersByUuid.get(uuid.replace("-", "").toLowerCase(Locale.ROOT));
        return profile != null ? profile.toCompleteResponse(rootUrl(), this::sign) : new JSONObject().toString();
    }

    private void writeTexture(@NonNull OutputStream output, @NonNull String hash) throws Exception {
        for (CharacterProfile profile : charactersByUuid.values()) {
            if (profile.skinHash != null && profile.skinHash.equalsIgnoreCase(hash) && profile.skinBytes != null) {
                writeResponse(output, 200, "image/png", profile.skinBytes,
                        "Cache-Control: max-age=2592000, public\r\nEtag: \"" + hash + "\"\r\n");
                return;
            }
        }
        writeResponse(output, 404, "text/plain; charset=utf-8", "Not Found".getBytes(StandardCharsets.UTF_8));
    }

    @NonNull
    private String rootUrl() {
        return "http://127.0.0.1:" + getPort();
    }

    private void writeJson(@NonNull OutputStream output, @NonNull String json) throws IOException {
        writeResponse(output, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private void writeResponse(@NonNull OutputStream output,
                               int status,
                               @NonNull String contentType,
                               @NonNull byte[] body) throws IOException {
        writeResponse(output, status, contentType, body, "");
    }

    private void writeResponse(@NonNull OutputStream output,
                               int status,
                               @NonNull String contentType,
                               @NonNull byte[] body,
                               @NonNull String extraHeaders) throws IOException {
        String statusText = status == 200 ? "OK"
                : status == 204 ? "No Content"
                : status == 400 ? "Bad Request"
                : status == 404 ? "Not Found"
                : "OK";
        String headers = "HTTP/1.1 " + status + " " + statusText + "\r\n"
                + "Connection: close\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + extraHeaders
                + "Content-Length: " + body.length + "\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.UTF_8));
        output.write(body);
        output.flush();
    }

    @Nullable
    private HttpRequest readRequest(@NonNull InputStream input) throws IOException {
        String requestLine = readLine(input);
        if (requestLine == null || requestLine.trim().isEmpty()) return null;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return null;

        String method = parts[0].trim().toUpperCase(Locale.ROOT);
        String target = parts[1].trim();
        String path = target;
        String queryString = "";
        int queryIndex = target.indexOf('?');
        if (queryIndex >= 0) {
            path = target.substring(0, queryIndex);
            queryString = target.substring(queryIndex + 1);
        }

        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readLine(input)) != null) {
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }

        int contentLength = 0;
        try {
            contentLength = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        } catch (Throwable ignored) {
        }
        byte[] body = new byte[Math.max(0, contentLength)];
        int offset = 0;
        while (offset < body.length) {
            int read = input.read(body, offset, body.length - offset);
            if (read < 0) break;
            offset += read;
        }

        return new HttpRequest(method, path, parseQuery(queryString), body);
    }

    @Nullable
    private String readLine(@NonNull InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int current;
        boolean seenAny = false;
        while ((current = input.read()) != -1) {
            seenAny = true;
            if (current == '\n') break;
            if (current != '\r') out.write(current);
        }
        if (!seenAny && out.size() == 0) return null;
        return out.toString("UTF-8");
    }

    @NonNull
    private Map<String, String> parseQuery(@NonNull String queryString) throws IOException {
        Map<String, String> query = new HashMap<>();
        if (queryString.trim().isEmpty()) return query;
        String[] pairs = queryString.split("&");
        for (String pair : pairs) {
            int equals = pair.indexOf('=');
            String key = equals >= 0 ? pair.substring(0, equals) : pair;
            String value = equals >= 0 ? pair.substring(equals + 1) : "";
            query.put(urlDecode(key), urlDecode(value));
        }
        return query;
    }

    @NonNull
    private String urlDecode(@NonNull String value) throws IOException {
        return URLDecoder.decode(value, "UTF-8");
    }

    @Nullable
    private byte[] readSkinBytes(@Nullable File skinFile) throws IOException {
        if (skinFile == null || !skinFile.isFile()) return null;
        try (FileInputStream input = new FileInputStream(skinFile);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }

    @NonNull
    private String toPemPublicKey(@NonNull PublicKey publicKey) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.encodeToString(publicKey.getEncoded(), Base64.NO_WRAP)
                + "\n-----END PUBLIC KEY-----";
    }

    @NonNull
    private String sign(@NonNull String value) throws Exception {
        Signature signature = Signature.getInstance("SHA1withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(value.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(signature.sign(), Base64.NO_WRAP);
    }

    @NonNull
    private String sha256(@NonNull byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] out = digest.digest(bytes);
        StringBuilder builder = new StringBuilder();
        for (byte b : out) builder.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
        return builder.toString();
    }

    private interface Signer {
        @NonNull String sign(@NonNull String value) throws Exception;
    }

    private static final class HttpRequest {
        final String method;
        final String path;
        final Map<String, String> query;
        final byte[] body;

        HttpRequest(@NonNull String method,
                    @NonNull String path,
                    @NonNull Map<String, String> query,
                    @NonNull byte[] body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.body = body;
        }
    }

    private static final class CharacterProfile {
        final String uuid;
        final String name;
        final String skinHash;
        final byte[] skinBytes;
        final SkinModelType model;

        CharacterProfile(@NonNull String uuid,
                         @NonNull String name,
                         @Nullable String skinHash,
                         @Nullable byte[] skinBytes,
                         @NonNull SkinModelType model) {
            this.uuid = uuid;
            this.name = name;
            this.skinHash = skinHash;
            this.skinBytes = skinBytes;
            this.model = model;
        }

        @NonNull
        String toCompleteResponse(@NonNull String rootUrl, @NonNull Signer signer) throws Exception {
            JSONObject textures = new JSONObject();
            if (skinHash != null && skinHash.length() > 0) {
                JSONObject skin = new JSONObject();
                skin.put("url", rootUrl + "/textures/" + skinHash);
                if (model == SkinModelType.SLIM) {
                    skin.put("metadata", new JSONObject().put("model", "slim"));
                }
                textures.put("SKIN", skin);
            }

            JSONObject texturesObject = new JSONObject();
            texturesObject.put("timestamp", System.currentTimeMillis());
            texturesObject.put("profileId", uuid);
            texturesObject.put("profileName", name);
            texturesObject.put("textures", textures);

            String encoded = Base64.encodeToString(texturesObject.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            JSONObject property = new JSONObject();
            property.put("name", "textures");
            property.put("value", encoded);
            property.put("signature", signer.sign(encoded));

            JSONObject response = new JSONObject();
            response.put("id", uuid);
            response.put("name", name);
            response.put("properties", new JSONArray().put(property));
            return response.toString();
        }
    }
}

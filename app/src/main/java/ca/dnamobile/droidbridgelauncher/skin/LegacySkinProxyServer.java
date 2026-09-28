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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

/**
 * Launcher-side proxy for legacy Minecraft skin downloads.
 *
 * Minecraft versions before the Yggdrasil skin rewrite ask for:
 *   http://skins.minecraft.net/MinecraftSkins/<username>.png
 *
 * That old host is dead/unreliable. Legacy Minecraft 1.6.x also passes the
 * launcher --proxyHost/--proxyPort as a SOCKS proxy, not as a normal HTTP
 * proxy. DroidBridge therefore accepts both protocols on one localhost port:
 *
 *   - HTTP proxy requests from JVM http.proxyHost/http.proxyPort
 *   - SOCKS4/SOCKS5 requests from Minecraft's --proxyHost/--proxyPort
 *
 * Skin requests are served from the launcher cache. Non-skin SOCKS targets are
 * tunneled through normally so the proxy does not break other legacy network
 * calls that happen to reuse Minecraft.getProxy().
 */
public final class LegacySkinProxyServer {
    private static final String TAG = "LegacySkinProxy";
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int BUFFER_SIZE = 16 * 1024;

    private final Map<String, File> skinsByLowerName = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    // BTA 8 can replace the authenticated launcher username with a transient
    // Player### session name and then request that profile's texture hash. For a
    // Microsoft launch, DroidBridge already has the authenticated account skin
    // cached locally, so allow the BTA-only proxy path to serve that exact PNG for
    // any textures.minecraft.net/texture/<hash> request. This avoids both the
    // Player### mismatch and BTA's plain-HTTP texture transport.
    @Nullable
    private volatile File modernTextureOverride;

    @Nullable
    private ServerSocket serverSocket;
    @Nullable
    private Thread acceptThread;

    public void addSkin(@NonNull String playerName, @NonNull File skinFile) {
        String key = playerName.trim().toLowerCase(Locale.ROOT);
        if (!key.isEmpty() && skinFile.isFile()) {
            skinsByLowerName.put(key, skinFile);
        }
    }

    public void setModernTextureOverride(@Nullable File skinFile) {
        modernTextureOverride = skinFile != null && skinFile.isFile() ? skinFile : null;
    }

    public void start() throws IOException {
        if (running.get()) return;
        ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        serverSocket = socket;
        running.set(true);

        Thread thread = new Thread(this::acceptLoop, "DroidBridgeLegacySkinProxy");
        thread.setDaemon(true);
        acceptThread = thread;
        thread.start();
    }

    public int getPort() {
        ServerSocket socket = serverSocket;
        return socket == null ? -1 : socket.getLocalPort();
    }

    public void stop() {
        running.set(false);
        ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
        acceptThread = null;
    }

    private void acceptLoop() {
        while (running.get()) {
            ServerSocket socket = serverSocket;
            if (socket == null) return;
            try {
                Socket client = socket.accept();
                Thread handler = new Thread(() -> handleClient(client), "DroidBridgeLegacySkinProxyClient");
                handler.setDaemon(true);
                handler.start();
            } catch (Throwable throwable) {
                if (running.get()) {
                    Logging.i(TAG, "Accept failed: " + readableError(throwable));
                }
            }
        }
    }

    private void handleClient(@NonNull Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(15000);
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            OutputStream output = socket.getOutputStream();

            input.mark(1);
            int first = input.read();
            if (first < 0) return;
            input.reset();

            if (first == 0x05) {
                handleSocks5(socket, input, output);
                return;
            }

            if (first == 0x04) {
                handleSocks4(socket, input, output);
                return;
            }

            handleHttpProxy(input, output);
        } catch (Throwable throwable) {
            Logging.i(TAG, "Request failed: " + readableError(throwable));
        }
    }

    private void handleHttpProxy(
            @NonNull BufferedInputStream input,
            @NonNull OutputStream output
    ) throws IOException {
        HttpRequest request = readRequest(input, null);
        if (request == null) {
            writeText(output, 400, "Bad Request");
            return;
        }

        if (isLegacySkinRequest(request)) {
            serveLegacySkinRequest(request, output);
            return;
        }

        forwardHttpRequest(request, output);
    }

    private void handleSocks5(
            @NonNull Socket client,
            @NonNull BufferedInputStream input,
            @NonNull OutputStream output
    ) throws IOException {
        int version = readByte(input);
        if (version != 0x05) {
            throw new IOException("Unsupported SOCKS version: " + version);
        }

        int methodCount = readByte(input);
        for (int i = 0; i < methodCount; i++) {
            readByte(input);
        }

        // No authentication.
        output.write(new byte[]{0x05, 0x00});
        output.flush();

        int requestVersion = readByte(input);
        int command = readByte(input);
        readByte(input); // reserved
        int addressType = readByte(input);

        if (requestVersion != 0x05) {
            writeSocks5Reply(output, 0x01, 0);
            return;
        }

        String targetHost = readSocks5Host(input, addressType);
        int targetPort = readUnsignedShort(input);

        if (command != 0x01) {
            writeSocks5Reply(output, 0x07, 0);
            Logging.i(TAG, "SOCKS5 command not supported: " + command + " target=" + targetHost + ":" + targetPort);
            return;
        }

        if (isLegacySkinTarget(targetHost, targetPort)) {
            writeSocks5Reply(output, 0x00, getPort());
            HttpRequest request = readRequest(input, targetHost);
            if (request == null) {
                writeText(output, 400, "Bad Request");
                return;
            }
            if (isLegacySkinRequest(request)) {
                serveLegacySkinRequest(request, output);
            } else {
                Logging.i(TAG, "SOCKS5 legacy skin host requested non-skin path: " + request.path);
                writeText(output, 404, "Not Found");
            }
            return;
        }

        Socket remote = null;
        try {
            remote = new Socket(targetHost, targetPort);
            writeSocks5Reply(output, 0x00, getPort());
            Logging.i(TAG, "SOCKS5 tunnel opened to " + targetHost + ":" + targetPort);
            tunnel(client, input, output, remote);
        } catch (Throwable throwable) {
            if (remote != null) {
                try {
                    remote.close();
                } catch (Throwable ignored) {
                }
            }
            writeSocks5Reply(output, 0x01, 0);
            Logging.i(TAG, "SOCKS5 tunnel failed to " + targetHost + ":" + targetPort + ": " + readableError(throwable));
        }
    }

    private void handleSocks4(
            @NonNull Socket client,
            @NonNull BufferedInputStream input,
            @NonNull OutputStream output
    ) throws IOException {
        int version = readByte(input);
        if (version != 0x04) {
            throw new IOException("Unsupported SOCKS version: " + version);
        }

        int command = readByte(input);
        int targetPort = readUnsignedShort(input);
        byte[] ip = new byte[4];
        readFully(input, ip);
        skipNullTerminated(input); // user id

        String targetHost;
        if (ip[0] == 0 && ip[1] == 0 && ip[2] == 0 && ip[3] != 0) {
            targetHost = readNullTerminatedString(input);
        } else {
            targetHost = (ip[0] & 0xff) + "." + (ip[1] & 0xff) + "." + (ip[2] & 0xff) + "." + (ip[3] & 0xff);
        }

        if (command != 0x01) {
            writeSocks4Reply(output, 91, 0);
            Logging.i(TAG, "SOCKS4 command not supported: " + command + " target=" + targetHost + ":" + targetPort);
            return;
        }

        if (isLegacySkinTarget(targetHost, targetPort)) {
            writeSocks4Reply(output, 90, getPort());
            HttpRequest request = readRequest(input, targetHost);
            if (request == null) {
                writeText(output, 400, "Bad Request");
                return;
            }
            if (isLegacySkinRequest(request)) {
                serveLegacySkinRequest(request, output);
            } else {
                Logging.i(TAG, "SOCKS4 legacy skin host requested non-skin path: " + request.path);
                writeText(output, 404, "Not Found");
            }
            return;
        }

        Socket remote = null;
        try {
            remote = new Socket(targetHost, targetPort);
            writeSocks4Reply(output, 90, getPort());
            Logging.i(TAG, "SOCKS4 tunnel opened to " + targetHost + ":" + targetPort);
            tunnel(client, input, output, remote);
        } catch (Throwable throwable) {
            if (remote != null) {
                try {
                    remote.close();
                } catch (Throwable ignored) {
                }
            }
            writeSocks4Reply(output, 91, 0);
            Logging.i(TAG, "SOCKS4 tunnel failed to " + targetHost + ":" + targetPort + ": " + readableError(throwable));
        }
    }

    @NonNull
    private String readSocks5Host(@NonNull InputStream input, int addressType) throws IOException {
        if (addressType == 0x01) {
            byte[] address = new byte[4];
            readFully(input, address);
            return (address[0] & 0xff) + "." + (address[1] & 0xff) + "." + (address[2] & 0xff) + "." + (address[3] & 0xff);
        }

        if (addressType == 0x03) {
            int length = readByte(input);
            byte[] address = new byte[length];
            readFully(input, address);
            return new String(address, StandardCharsets.ISO_8859_1);
        }

        if (addressType == 0x04) {
            byte[] address = new byte[16];
            readFully(input, address);
            return InetAddress.getByAddress(address).getHostAddress();
        }

        throw new IOException("Unsupported SOCKS5 address type: " + addressType);
    }

    private boolean isLegacySkinTarget(@Nullable String host, int port) {
        if (host == null) return false;
        String lower = host.trim().toLowerCase(Locale.ROOT);
        return (port == 80 || port == 0)
                && ("skins.minecraft.net".equals(lower) || lower.endsWith(".skins.minecraft.net"));
    }

    private void serveLegacySkinRequest(
            @NonNull HttpRequest request,
            @NonNull OutputStream output
    ) throws IOException {
        File skin = findSkinForRequest(request);
        if (skin != null && skin.isFile()) {
            writeFile(output, 200, "image/png", skin);
            Logging.i(TAG, "Served legacy skin request for " + request.path + " from " + skin.getAbsolutePath());
            return;
        }
        Logging.i(TAG, "No skin file matched legacy skin request: " + request.path);
        writeText(output, 404, "Not Found");
    }

    @Nullable
    private HttpRequest readRequest(
            @NonNull BufferedInputStream input,
            @Nullable String fallbackHost
    ) throws IOException {
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        int previous3 = -1;
        int previous2 = -1;
        int previous1 = -1;
        int current;

        while ((current = input.read()) != -1) {
            headerBytes.write(current);
            if (headerBytes.size() > MAX_HEADER_BYTES) return null;
            if (previous3 == '\r' && previous2 == '\n' && previous1 == '\r' && current == '\n') {
                break;
            }
            previous3 = previous2;
            previous2 = previous1;
            previous1 = current;
        }

        if (headerBytes.size() == 0) return null;

        BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.ByteArrayInputStream(headerBytes.toByteArray()),
                StandardCharsets.ISO_8859_1
        ));

        String requestLine = reader.readLine();
        if (requestLine == null || requestLine.trim().isEmpty()) return null;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return null;

        String method = parts[0].trim();
        String target = parts[1].trim();
        String host = "";
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if ("host".equalsIgnoreCase(name)) host = value;
        }

        String urlText = target;
        if (!urlText.startsWith("http://") && !urlText.startsWith("https://")) {
            String effectiveHost = host.isEmpty() && fallbackHost != null ? fallbackHost.trim() : host;
            if (effectiveHost.isEmpty()) return null;
            urlText = "http://" + effectiveHost + target;
        }

        try {
            URL url = new URL(urlText);
            return new HttpRequest(method, url, host, url.getPath());
        } catch (Throwable throwable) {
            return null;
        }
    }

    private boolean isLegacySkinRequest(@NonNull HttpRequest request) {
        String host = request.url.getHost() == null ? "" : request.url.getHost().toLowerCase(Locale.ROOT);
        String path = request.path == null ? "" : request.path;
        return "GET".equalsIgnoreCase(request.method)
                && ("skins.minecraft.net".equals(host) || host.endsWith(".skins.minecraft.net"))
                && path.toLowerCase(Locale.ROOT).contains("/minecraftskins/")
                && path.toLowerCase(Locale.ROOT).endsWith(".png");
    }

    @Nullable
    private File findSkinForRequest(@NonNull HttpRequest request) {
        String path = request.path == null ? "" : request.path;
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        if (name.toLowerCase(Locale.ROOT).endsWith(".png")) {
            name = name.substring(0, name.length() - 4);
        }
        try {
            name = URLDecoder.decode(name, "UTF-8");
        } catch (Throwable ignored) {
        }
        return skinsByLowerName.get(name.trim().toLowerCase(Locale.ROOT));
    }

    private void forwardHttpRequest(@NonNull HttpRequest request, @NonNull OutputStream output) throws IOException {
        if (!"GET".equalsIgnoreCase(request.method)) {
            writeText(output, 405, "Method Not Allowed");
            return;
        }

        File override = modernTextureOverride;
        if (override != null && override.isFile() && isModernMinecraftTextureRequest(request.url)) {
            writeFile(output, 200, "image/png", override);
            Logging.i(TAG, "Served authenticated BTA skin override for "
                    + request.url.getPath() + " from " + override.getAbsolutePath());
            return;
        }

        HttpURLConnection connection = null;
        try {
            URL forwardUrl = upgradeMinecraftTextureUrl(request.url);
            connection = (HttpURLConnection) forwardUrl.openConnection(java.net.Proxy.NO_PROXY);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setUseCaches(true);
            connection.setRequestProperty("User-Agent", "DroidBridge LegacySkinProxy");

            int code = connection.getResponseCode();
            String type = connection.getContentType();
            if (type == null || type.trim().isEmpty()) type = "application/octet-stream";
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) {
                writeText(output, code, "");
                return;
            }

            byte[] body = readAll(stream);
            writeBytes(output, code, type, body, "Cache-Control: no-cache\r\n");
        } catch (Throwable throwable) {
            writeText(output, 502, "Proxy error: " + readableError(throwable));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private boolean isModernMinecraftTextureRequest(@NonNull URL url) {
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        String path = url.getPath() == null ? "" : url.getPath().toLowerCase(Locale.ROOT);
        return ("textures.minecraft.net".equals(host) || host.endsWith(".textures.minecraft.net"))
                && path.startsWith("/texture/");
    }

    @NonNull
    private URL upgradeMinecraftTextureUrl(@NonNull URL original) throws IOException {
        String protocol = original.getProtocol() == null ? "" : original.getProtocol();
        String host = original.getHost() == null ? "" : original.getHost().toLowerCase(Locale.ROOT);
        if ("http".equalsIgnoreCase(protocol)
                && ("textures.minecraft.net".equals(host) || host.endsWith(".textures.minecraft.net"))) {
            URL upgraded = new URL("https", original.getHost(), -1, original.getFile());
            Logging.i(TAG, "Upgraded Minecraft texture request to HTTPS: " + upgraded);
            return upgraded;
        }
        return original;
    }

    private void tunnel(
            @NonNull Socket client,
            @NonNull BufferedInputStream clientInput,
            @NonNull OutputStream clientOutput,
            @NonNull Socket remote
    ) throws IOException {
        try (Socket remoteSocket = remote) {
            remoteSocket.setSoTimeout(30000);
            InputStream remoteInput = remoteSocket.getInputStream();
            OutputStream remoteOutput = remoteSocket.getOutputStream();

            Thread remoteToClient = new Thread(() -> copyQuietly(remoteInput, clientOutput, client), "DroidBridgeLegacySkinProxyTunnelOut");
            remoteToClient.setDaemon(true);
            remoteToClient.start();

            copyStream(clientInput, remoteOutput);
        } finally {
            try {
                client.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void copyQuietly(
            @NonNull InputStream input,
            @NonNull OutputStream output,
            @NonNull Socket socketToClose
    ) {
        try {
            copyStream(input, output);
        } catch (Throwable ignored) {
        } finally {
            try {
                socketToClose.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void copyStream(@NonNull InputStream input, @NonNull OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            output.flush();
        }
    }

    private int readByte(@NonNull InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) throw new IOException("Unexpected EOF");
        return value & 0xff;
    }

    private int readUnsignedShort(@NonNull InputStream input) throws IOException {
        int high = readByte(input);
        int low = readByte(input);
        return ((high & 0xff) << 8) | (low & 0xff);
    }

    private void readFully(@NonNull InputStream input, @NonNull byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = input.read(buffer, offset, buffer.length - offset);
            if (read < 0) throw new IOException("Unexpected EOF");
            offset += read;
        }
    }

    private void skipNullTerminated(@NonNull InputStream input) throws IOException {
        while (true) {
            int value = readByte(input);
            if (value == 0) return;
        }
    }

    @NonNull
    private String readNullTerminatedString(@NonNull InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            int value = readByte(input);
            if (value == 0) break;
            output.write(value);
            if (output.size() > 4096) throw new IOException("SOCKS string too long");
        }
        return new String(output.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private void writeSocks5Reply(@NonNull OutputStream output, int replyCode, int port) throws IOException {
        output.write(new byte[]{
                0x05,
                (byte) (replyCode & 0xff),
                0x00,
                0x01,
                127,
                0,
                0,
                1,
                (byte) ((port >> 8) & 0xff),
                (byte) (port & 0xff)
        });
        output.flush();
    }

    private void writeSocks4Reply(@NonNull OutputStream output, int code, int port) throws IOException {
        output.write(new byte[]{
                0x00,
                (byte) (code & 0xff),
                (byte) ((port >> 8) & 0xff),
                (byte) (port & 0xff),
                127,
                0,
                0,
                1
        });
        output.flush();
    }

    private void writeFile(@NonNull OutputStream output, int code, @NonNull String type, @NonNull File file) throws IOException {
        byte[] bytes;
        try (InputStream input = new FileInputStream(file)) {
            bytes = readAll(input);
        }
        writeBytes(output, code, type, bytes, "Cache-Control: max-age=2592000, public\r\n");
    }

    private void writeText(@NonNull OutputStream output, int code, @NonNull String text) throws IOException {
        writeBytes(output, code, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8), "Cache-Control: no-cache\r\n");
    }

    private void writeBytes(
            @NonNull OutputStream output,
            int code,
            @NonNull String type,
            @NonNull byte[] body,
            @NonNull String extraHeaders
    ) throws IOException {
        String status = code + " " + statusText(code);
        String headers = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + extraHeaders
                + "Connection: close\r\n"
                + "\r\n";
        output.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        output.write(body);
        output.flush();
    }

    @NonNull
    private byte[] readAll(@NonNull InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    @NonNull
    private String statusText(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 502: return "Bad Gateway";
            default: return "Status";
        }
    }

    @NonNull
    private static String readableError(@NonNull Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    private static final class HttpRequest {
        @NonNull final String method;
        @NonNull final URL url;
        @NonNull final String hostHeader;
        @NonNull final String path;

        HttpRequest(@NonNull String method, @NonNull URL url, @NonNull String hostHeader, @NonNull String path) {
            this.method = method;
            this.url = url;
            this.hostHeader = hostHeader;
            this.path = path;
        }
    }
}

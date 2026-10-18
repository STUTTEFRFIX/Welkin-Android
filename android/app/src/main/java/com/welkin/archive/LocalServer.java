package com.welkin.archive;

import android.content.res.AssetManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Tiny loopback-only HTTP server that serves the bundled static site out of
 * {@code assets/www}. The Welkin theme loads ES modules, a JSON import map and
 * WebGL resources, which a file:// origin cannot provide, so the app serves the
 * exact same bytes the site has when hosted.
 */
public final class LocalServer {

    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final Map<String, String> MIME = new HashMap<>();

    static {
        MIME.put("html", "text/html; charset=utf-8");
        MIME.put("htm", "text/html; charset=utf-8");
        MIME.put("css", "text/css; charset=utf-8");
        MIME.put("js", "text/javascript; charset=utf-8");
        MIME.put("mjs", "text/javascript; charset=utf-8");
        MIME.put("json", "application/json; charset=utf-8");
        MIME.put("webp", "image/webp");
        MIME.put("png", "image/png");
        MIME.put("jpg", "image/jpeg");
        MIME.put("jpeg", "image/jpeg");
        MIME.put("gif", "image/gif");
        MIME.put("svg", "image/svg+xml");
        MIME.put("ico", "image/x-icon");
        MIME.put("txt", "text/plain; charset=utf-8");
        MIME.put("xml", "application/xml; charset=utf-8");
        MIME.put("woff2", "font/woff2");
        MIME.put("woff", "font/woff");
        MIME.put("mp3", "audio/mpeg");
        MIME.put("mp4", "video/mp4");
        MIME.put("webmanifest", "application/manifest+json");
    }

    private final AssetManager assets;
    private final ServerSocket socket;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    public LocalServer(AssetManager assets) throws IOException {
        this.assets = assets;
        // Bind IPv4 loopback explicitly: some devices resolve the loopback name to
        // ::1 while WebView is told to load 127.0.0.1. Prefer the port the site was
        // generated with so canonical URLs match, fall back to any free port.
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        ServerSocket bound;
        try {
            bound = new ServerSocket(8866, 64, loopback);
        } catch (IOException busy) {
            bound = new ServerSocket(0, 64, loopback);
        }
        this.socket = bound;
    }

    public int port() {
        return socket.getLocalPort();
    }

    public void start() {
        Thread acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!socket.isClosed()) {
                    try {
                        final Socket client = socket.accept();
                        pool.execute(new Runnable() {
                            @Override
                            public void run() {
                                handle(client);
                            }
                        });
                    } catch (IOException closed) {
                        return;
                    } catch (RejectedExecutionException ignored) {
                        return;
                    }
                }
            }
        }, "welkin-assets");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public void close() {
        pool.shutdownNow();
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing useful to do while shutting down.
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(15000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            String head = readHead(in);
            if (head == null) {
                return;
            }
            String requestLine = head;
            int lineEnd = head.indexOf("\r\n");
            if (lineEnd >= 0) {
                requestLine = head.substring(0, lineEnd);
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                write(out, 400, "text/plain; charset=utf-8", "Bad request".getBytes("UTF-8"));
                return;
            }
            if (!"GET".equals(parts[0]) && !"HEAD".equals(parts[0])) {
                write(out, 405, "text/plain; charset=utf-8", "Method not allowed".getBytes("UTF-8"));
                return;
            }

            String target = parts[1];
            int query = target.indexOf('?');
            if (query >= 0) {
                target = target.substring(0, query);
            }
            int fragment = target.indexOf('#');
            if (fragment >= 0) {
                target = target.substring(0, fragment);
            }
            String path;
            try {
                path = URLDecoder.decode(target, "UTF-8");
            } catch (UnsupportedEncodingException | IllegalArgumentException badPath) {
                write(out, 400, "text/plain; charset=utf-8", "Bad path".getBytes("UTF-8"));
                return;
            }
            if (path.isEmpty() || "/".equals(path)) {
                path = "/index.html";
            } else if (path.endsWith("/")) {
                path = path + "index.html";
            }
            if (path.contains("..") || path.contains("\\") || path.indexOf('\0') >= 0) {
                write(out, 403, "text/plain; charset=utf-8", "Forbidden".getBytes("UTF-8"));
                return;
            }

            String asset = "www" + path;
            byte[] body;
            try {
                body = readAll(assets.open(asset));
            } catch (IOException missing) {
                body = ("Not found: " + path).getBytes("UTF-8");
                write(out, 404, "text/plain; charset=utf-8", body);
                return;
            }

            Map<String, String> extra = new HashMap<>();
            extra.put("Cache-Control", path.startsWith("/assets/") ? "public, max-age=86400" : "no-cache");
            write(out, 200, mimeOf(asset), body, extra, "HEAD".equals(parts[0]));
        } catch (IOException ignored) {
            // Client went away mid-request; nothing to recover.
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
        int matched = 0;
        int value;
        while ((value = in.read()) != -1) {
            buffer.write(value);
            if (buffer.size() > MAX_HEADER_BYTES) {
                return null;
            }
            if ((value == '\n' && matched == 2) || (value == '\n' && matched == 3)) {
                return buffer.toString("UTF-8");
            }
            if (value == '\r') {
                matched = matched == 0 || matched == 2 ? matched + 1 : 2;
            } else if (value == '\n') {
                matched = matched == 1 ? 2 : 0;
            } else {
                matched = 0;
            }
        }
        return buffer.size() == 0 ? null : buffer.toString("UTF-8");
    }

    private static String mimeOf(String asset) {
        int dot = asset.lastIndexOf('.');
        String extension = dot < 0 ? "" : asset.substring(dot + 1).toLowerCase(Locale.US);
        String type = MIME.get(extension);
        return type != null ? type : "application/octet-stream";
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] chunk = new byte[8192];
            int count;
            while ((count = in.read(chunk)) != -1) {
                out.write(chunk, 0, count);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void write(OutputStream out, int status, String type, byte[] body) throws IOException {
        write(out, status, type, body, null, false);
    }

    private static void write(OutputStream out, int status, String type, byte[] body,
                              Map<String, String> extra, boolean headOnly) throws IOException {
        StringBuilder headers = new StringBuilder(256);
        headers.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        headers.append("Content-Type: ").append(type).append("\r\n");
        headers.append("Content-Length: ").append(body.length).append("\r\n");
        headers.append("Connection: close\r\n");
        headers.append("X-Content-Type-Options: nosniff\r\n");
        if (extra != null) {
            for (Map.Entry<String, String> item : extra.entrySet()) {
                headers.append(item.getKey()).append(": ").append(item.getValue()).append("\r\n");
            }
        }
        headers.append("\r\n");
        out.write(headers.toString().getBytes("UTF-8"));
        if (!headOnly) {
            out.write(body);
        }
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            default: return "Error";
        }
    }
}

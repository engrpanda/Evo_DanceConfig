package com.evo.danceconfig;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny embedded HTTP server so the robot can be managed from any browser on the network
 * (or from localhost on the robot itself): upload/download sounds, edit config and settings,
 * and test playback.
 */
public class RobotServer {

    private static final long MAX_UPLOAD = 200L * 1024 * 1024;

    private final Context context;
    private final Settings settings;
    private final AppLog log;
    private final RobotStorage storage;
    private final SoundPlayer player;

    private ServerSocket serverSocket;
    private ExecutorService pool;
    private volatile boolean running;
    private volatile int port;

    public interface StateListener { void onServerStateChanged(); }
    private volatile StateListener stateListener;

    public RobotServer(Context context, Settings settings, AppLog log, RobotStorage storage, SoundPlayer player) {
        this.context = context.getApplicationContext();
        this.settings = settings;
        this.log = log;
        this.storage = storage;
        this.player = player;
    }

    public void setStateListener(StateListener l) { stateListener = l; }

    public boolean isRunning() { return running; }
    public int port() { return port; }

    public synchronized boolean start() {
        if (running) return true;
        int p = settings.port();
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new java.net.InetSocketAddress(p));
            serverSocket = ss;
            port = p;
            pool = Executors.newCachedThreadPool();
            running = true;
            Thread t = new Thread(this::acceptLoop, "robot-server");
            t.setDaemon(true);
            t.start();
            log.ok("Web server started on port " + p + ".");
            fireState();
            return true;
        } catch (IOException e) {
            log.error("Cannot start web server on port " + p + ": " + e.getMessage());
            fireState();
            return false;
        }
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        try { serverSocket.close(); } catch (IOException ignored) { }
        pool.shutdownNow();
        log.info("Web server stopped.");
        fireState();
    }

    public synchronized boolean restart() {
        stop();
        return start();
    }

    private void fireState() {
        StateListener l = stateListener;
        if (l != null) l.onServerStateChanged();
    }

    /** Addresses a browser can use, e.g. http://192.168.1.20:8080 */
    public List<String> urls() {
        List<String> out = new ArrayList<>();
        out.add("http://localhost:" + settings.port());
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) out.add("http://" + a.getHostAddress() + ":" + settings.port());
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = serverSocket.accept();
                pool.execute(() -> handle(s));
            } catch (IOException e) {
                if (running) log.error("Web server error: " + e.getMessage());
                return;
            } catch (RuntimeException e) {
                return; // pool shut down
            }
        }
    }

    // ------------------------------------------------------------------ request handling

    private static class Request {
        String method, path, rawQuery;
        Map<String, String> headers = new HashMap<>();
        Map<String, String> query = new HashMap<>();
        InputStream body;
        long contentLength;
    }

    private static class HttpError extends Exception {
        final int status;
        HttpError(int status, String message) { super(message); this.status = status; }
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(sock.getInputStream(), 16 * 1024);
            OutputStream out = sock.getOutputStream();
            try {
                Request req = readRequest(in);
                if (req == null) return;
                if ("100-continue".equalsIgnoreCase(req.headers.get("expect"))) {
                    out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                }
                route(req, out);
            } catch (HttpError e) {
                sendJson(out, e.status, errorJson(e.getMessage()));
            } catch (Exception e) {
                log.error("Request failed: " + e.getMessage());
                try { sendJson(out, 500, errorJson("Internal error: " + e.getMessage())); } catch (IOException ignored) { }
            }
        } catch (IOException ignored) {
            // client went away
        }
    }

    private Request readRequest(InputStream in) throws IOException, HttpError {
        String line = readLine(in);
        if (line == null || line.isEmpty()) return null;
        String[] parts = line.split(" ");
        if (parts.length < 2) throw new HttpError(400, "Bad request");
        Request r = new Request();
        r.method = parts[0].toUpperCase(Locale.ROOT);
        String target = parts[1];
        int q = target.indexOf('?');
        r.path = q < 0 ? target : target.substring(0, q);
        r.rawQuery = q < 0 ? "" : target.substring(q + 1);
        for (String pair : r.rawQuery.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = decode(eq < 0 ? pair : pair.substring(0, eq));
            String v = eq < 0 ? "" : decode(pair.substring(eq + 1));
            r.query.put(k, v);
        }
        int total = 0;
        while (true) {
            String h = readLine(in);
            if (h == null) throw new HttpError(400, "Bad request");
            if (h.isEmpty()) break;
            total += h.length();
            if (total > 32 * 1024) throw new HttpError(431, "Headers too large");
            int c = h.indexOf(':');
            if (c > 0) r.headers.put(h.substring(0, c).trim().toLowerCase(Locale.ROOT), h.substring(c + 1).trim());
        }
        String cl = r.headers.get("content-length");
        try { r.contentLength = cl == null ? 0 : Long.parseLong(cl); } catch (NumberFormatException e) { throw new HttpError(400, "Bad Content-Length"); }
        r.body = in;
        return r;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') bos.write(b);
            if (bos.size() > 8192) throw new IOException("Line too long");
        }
        if (b == -1 && bos.size() == 0) return null;
        return bos.toString("ISO-8859-1");
    }

    private static String decode(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private void route(Request req, OutputStream out) throws Exception {
        String path = req.path;
        boolean mutating = !req.method.equals("GET") && !req.method.equals("HEAD");

        if (req.method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
            sendAsset(out, "web/index.html", "text/html; charset=utf-8");
            return;
        }
        if (req.method.equals("GET") && path.equals("/lame.min.js")) {
            sendAsset(out, "web/lame.min.js", "application/javascript; charset=utf-8");
            return;
        }
        if (!path.startsWith("/api/") && !path.startsWith("/files/")) throw new HttpError(404, "Not found");

        // Optional PIN protection
        String pin = settings.pin();
        if (!pin.isEmpty()) {
            String given = req.headers.containsKey("x-pin") ? req.headers.get("x-pin") : req.query.get("pin");
            if (given == null || !MessageDigest.isEqual(pin.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
                throw new HttpError(401, "PIN required");
            }
        }
        // CSRF guard: a custom header forces a CORS preflight, which this server never approves.
        if (mutating && !"evo".equals(req.headers.get("x-requested-with"))) throw new HttpError(403, "Missing X-Requested-With header");

        if (path.startsWith("/files/")) {
            serveFile(req, out, decode(path.substring(7)));
            return;
        }

        switch (path) {
            case "/api/status": {
                requireMethod(req, "GET");
                sendJson(out, 200, statusJson());
                return;
            }
            case "/api/files": {
                if (req.method.equals("GET")) {
                    sendJson(out, 200, storage.listJson().toString());
                } else if (req.method.equals("POST")) {
                    upload(req, out);
                } else if (req.method.equals("DELETE")) {
                    String name = req.query.get("name");
                    if (!storage.delete(name)) throw new HttpError(404, "File not found: " + name);
                    if (name.equals(player.playingName())) player.stop();
                    log.warn("Deleted " + name + " (web)");
                    sendJson(out, 200, okJson());
                } else throw new HttpError(405, "Method not allowed");
                return;
            }
            case "/api/config": {
                if (req.method.equals("GET")) {
                    JSONObject o = new JSONObject();
                    o.put("entries", storage.readConfig());
                    o.put("file", storage.configFile().getName());
                    o.put("exists", storage.configFile().isFile());
                    sendJson(out, 200, o.toString());
                } else if (req.method.equals("PUT")) {
                    String text = readBodyText(req, 1024 * 1024);
                    try {
                        storage.writeConfig(text);
                    } catch (JSONException e) {
                        throw new HttpError(400, "Invalid config JSON: " + e.getMessage());
                    }
                    log.ok("Dance config saved (web).");
                    sendJson(out, 200, okJson());
                } else throw new HttpError(405, "Method not allowed");
                return;
            }
            case "/api/config/only": {
                requireMethod(req, "POST");
                String name = RobotStorage.safeName(req.query.get("name"));
                if (name == null || !RobotStorage.isAudio(name)) throw new HttpError(400, "Pick an audio file.");
                if (!storage.file(name).isFile()) throw new HttpError(404, "Sound not found: " + name);
                storage.setOnly(name);
                log.ok("Dance list now has only " + name + " (web). Previous list kept as a backup.");
                sendJson(out, 200, okJson());
                return;
            }
            case "/api/config/add":
            case "/api/config/remove": {
                requireMethod(req, "POST");
                String name = RobotStorage.safeName(req.query.get("name"));
                if (name == null || !RobotStorage.isAudio(name)) throw new HttpError(400, "Pick an audio file.");
                boolean add = path.endsWith("/add");
                boolean changed = add ? storage.addToConfig(name) : storage.removeFromConfig(name);
                if (changed) log.ok((add ? "Added " : "Removed ") + name + (add ? " to" : " from") + " the dance list (web).");
                sendJson(out, 200, new JSONObject().put("ok", true).put("changed", changed).toString());
                return;
            }
            case "/api/settings": {
                if (req.method.equals("GET")) {
                    sendJson(out, 200, settings.toJson().toString());
                } else if (req.method.equals("PUT")) {
                    JSONObject o;
                    try { o = new JSONObject(readBodyText(req, 64 * 1024)); } catch (JSONException e) { throw new HttpError(400, "Invalid JSON"); }
                    int oldPort = settings.port();
                    String err = settings.apply(o);
                    if (err != null) throw new HttpError(400, err);
                    log.ok("Settings saved (web).");
                    boolean portChanged = oldPort != settings.port();
                    JSONObject res = new JSONObject().put("ok", true).put("portChanged", portChanged).put("newPort", settings.port());
                    sendJson(out, 200, res.toString());
                    if (portChanged) restartLater();
                    fireState();
                } else throw new HttpError(405, "Method not allowed");
                return;
            }
            case "/api/pull": {
                requireMethod(req, "POST");
                String type = req.query.containsKey("type") ? req.query.get("type") : "all";
                JSONArray pulled = new JSONArray();
                if (type.equals("music") || type.equals("all")) pulled.put(pull("Music", settings.musicUrl(), settings.musicName()));
                if (type.equals("json") || type.equals("all")) pulled.put(pull("JSON", settings.jsonUrl(), settings.jsonName()));
                sendJson(out, 200, new JSONObject().put("ok", true).put("pulled", pulled).toString());
                return;
            }
            case "/api/player": {
                requireMethod(req, "GET");
                sendJson(out, 200, player.toJson().toString());
                return;
            }
            case "/api/player/play": {
                requireMethod(req, "POST");
                String name = req.query.get("name");
                File f = storage.file(name);
                if (f == null || !f.isFile() || !RobotStorage.isAudio(f.getName())) throw new HttpError(404, "Sound not found: " + name);
                if (!player.play(f)) throw new HttpError(500, "Robot could not play " + name);
                sendJson(out, 200, okJson());
                return;
            }
            case "/api/player/stop": {
                requireMethod(req, "POST");
                player.stop();
                sendJson(out, 200, okJson());
                return;
            }
            case "/api/log": {
                if (req.method.equals("GET")) {
                    JSONArray arr = new JSONArray();
                    for (AppLog.Entry e : log.snapshot()) {
                        arr.put(new JSONObject().put("t", e.time).put("level", AppLog.levelName(e.level)).put("msg", e.message));
                    }
                    sendJson(out, 200, arr.toString());
                } else if (req.method.equals("DELETE")) {
                    log.clear();
                    log.info("Log cleared (web).");
                    sendJson(out, 200, okJson());
                } else throw new HttpError(405, "Method not allowed");
                return;
            }
            default:
                throw new HttpError(404, "Not found");
        }
    }

    private static void requireMethod(Request r, String m) throws HttpError {
        if (!r.method.equals(m)) throw new HttpError(405, "Method not allowed");
    }

    private String pull(String type, String url, String name) throws HttpError {
        log.info("Pulling " + type + " from " + url);
        try {
            File f = storage.pull(url, name);
            log.ok(type + " saved to robot folder: " + f.getName() + " (" + RobotStorage.humanSize(f.length()) + ")");
            return f.getName();
        } catch (IOException e) {
            log.error("Pull " + type + " failed: " + e.getMessage());
            throw new HttpError(502, "Pull " + type + " failed: " + e.getMessage());
        }
    }

    private void upload(Request req, OutputStream out) throws Exception {
        String name = req.query.get("name");
        if (name == null || name.contains("/") || name.contains("\\") || RobotStorage.safeName(name) == null) {
            throw new HttpError(400, "Invalid file name. Allowed: " + RobotStorage.ALLOWED_EXT + ", letters/digits/space/._-()");
        }
        if (req.contentLength <= 0) throw new HttpError(411, "Empty upload");
        if (req.contentLength > MAX_UPLOAD) throw new HttpError(413, "File too large (max 200 MB)");
        LimitedInputStream limited = new LimitedInputStream(req.body, req.contentLength);
        try {
            File f = storage.save(name, limited, MAX_UPLOAD);
            if (limited.left > 0) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                throw new IOException("Connection closed before the whole file arrived");
            }
            log.ok("Uploaded " + f.getName() + " (" + RobotStorage.humanSize(f.length()) + ") via web");
            sendJson(out, 200, new JSONObject().put("ok", true).put("name", f.getName()).put("size", f.length()).toString());
        } catch (IOException e) {
            log.error("Upload failed: " + e.getMessage());
            throw new HttpError(500, "Upload failed: " + e.getMessage());
        }
    }

    private void restartLater() {
        new Thread(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) { }
            restart();
        }).start();
    }

    private JSONObject statusJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("app", "EVO Dance Manager");
        o.put("storage", storage.status());
        o.put("player", player.toJson());
        o.put("port", port);
        o.put("pinEnabled", !settings.pin().isEmpty());
        o.put("urls", new JSONArray(urls()));
        o.put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " (Android " + android.os.Build.VERSION.RELEASE + ")");
        return o;
    }

    private String readBodyText(Request req, int max) throws IOException, HttpError {
        if (req.contentLength > max) throw new HttpError(413, "Body too large");
        byte[] data = new byte[(int) req.contentLength];
        int off = 0;
        while (off < data.length) {
            int r = req.body.read(data, off, data.length - off);
            if (r < 0) throw new IOException("Unexpected end of body");
            off += r;
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ responses

    private static String errorJson(String msg) {
        try { return new JSONObject().put("ok", false).put("error", msg).toString(); } catch (JSONException e) { return "{\"ok\":false}"; }
    }

    private static String okJson() { return "{\"ok\":true}"; }

    private static String statusText(int code) {
        switch (code) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 416: return "Range Not Satisfiable";
            case 431: return "Request Header Fields Too Large";
            case 502: return "Bad Gateway";
            default: return code >= 500 ? "Server Error" : "Error";
        }
    }

    private void writeHead(OutputStream out, int code, String type, long length, String extra) throws IOException {
        String head = "HTTP/1.1 " + code + " " + statusText(code) + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: close\r\n"
                + (extra == null ? "" : extra)
                + "\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
    }

    private void sendJson(OutputStream out, int code, Object json) throws IOException {
        byte[] b = json.toString().getBytes(StandardCharsets.UTF_8);
        writeHead(out, code, "application/json; charset=utf-8", b.length, null);
        out.write(b);
        out.flush();
    }

    private void sendAsset(OutputStream out, String asset, String type) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = context.getAssets().open(asset)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
        }
        writeHead(out, 200, type, bos.size(), null);
        out.write(bos.toByteArray());
        out.flush();
    }

    private void serveFile(Request req, OutputStream out, String name) throws Exception {
        if (!req.method.equals("GET") && !req.method.equals("HEAD")) throw new HttpError(405, "Method not allowed");
        File f = storage.file(name);
        if (f == null || !f.isFile()) throw new HttpError(404, "File not found");
        long size = f.length();
        long start = 0, end = size - 1;
        int code = 200;
        String range = req.headers.get("range");
        if (range != null && range.startsWith("bytes=") && size > 0) {
            try {
                String[] p = range.substring(6).split(",")[0].trim().split("-", -1);
                if (p[0].isEmpty()) {
                    start = Math.max(0, size - Long.parseLong(p[1]));
                } else {
                    start = Long.parseLong(p[0]);
                    if (!p[1].isEmpty()) end = Math.min(end, Long.parseLong(p[1]));
                }
                if (start > end || start >= size) {
                    writeHead(out, 416, "text/plain", 0, "Content-Range: bytes */" + size + "\r\n");
                    out.flush();
                    return;
                }
                code = 206;
            } catch (NumberFormatException e) {
                start = 0; end = size - 1; code = 200;
            }
        }
        long length = size == 0 ? 0 : end - start + 1;
        StringBuilder extra = new StringBuilder("Accept-Ranges: bytes\r\n");
        if (code == 206) extra.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(size).append("\r\n");
        if ("1".equals(req.query.get("download"))) {
            extra.append("Content-Disposition: attachment; filename=\"").append(f.getName().replace("\"", "")).append("\"\r\n");
        }
        writeHead(out, code, mime(f.getName()), length, extra.toString());
        if (req.method.equals("HEAD") || length == 0) { out.flush(); return; }
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.seek(start);
            byte[] buf = new byte[32 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int r = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (r < 0) break;
                out.write(buf, 0, r);
                remaining -= r;
            }
        }
        out.flush();
    }

    private static String mime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".ogg")) return "audio/ogg";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".aac")) return "audio/aac";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    /** Reads at most {@code limit} bytes from the underlying stream. */
    private static class LimitedInputStream extends InputStream {
        private final InputStream in;
        long left;

        LimitedInputStream(InputStream in, long limit) { this.in = in; this.left = limit; }

        @Override public int read() throws IOException {
            if (left <= 0) return -1;
            int b = in.read();
            if (b >= 0) left--;
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int r = in.read(b, off, (int) Math.min(len, left));
            if (r > 0) left -= r;
            return r;
        }
    }
}

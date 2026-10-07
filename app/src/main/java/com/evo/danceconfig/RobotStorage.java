package com.evo.danceconfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** All file access to the robot's "extra" folder: list, save, delete, pull from URL, config JSON. */
public class RobotStorage {

    public static final Set<String> ALLOWED_EXT = new HashSet<>(Arrays.asList(
            "mp3", "wav", "ogg", "m4a", "aac", "flac", "json"));
    private static final Set<String> AUDIO_EXT = new HashSet<>(Arrays.asList(
            "mp3", "wav", "ogg", "m4a", "aac", "flac"));
    private static final Pattern NAME_OK = Pattern.compile("[A-Za-z0-9._()\\- ]{1,100}");

    private final Settings settings;
    private final AppLog log;

    public RobotStorage(Settings settings, AppLog log) {
        this.settings = settings;
        this.log = log;
    }

    public File dir() { return new File(settings.robotPath()); }

    /** Returns a safe file name (no path segments, allowed extension) or null. */
    public static String safeName(String raw) {
        if (raw == null) return null;
        String n = raw.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).trim();
        if (n.isEmpty() || n.startsWith(".") || n.contains("..") || !NAME_OK.matcher(n).matches()) return null;
        int dot = n.lastIndexOf('.');
        if (dot < 0) return null;
        return ALLOWED_EXT.contains(n.substring(dot + 1).toLowerCase(Locale.ROOT)) ? n : null;
    }

    public static boolean isAudio(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && AUDIO_EXT.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    public boolean ensureDir() {
        File d = dir();
        return d.isDirectory() || d.mkdirs();
    }

    public boolean isWritable() {
        File d = dir();
        return d.isDirectory() && d.canWrite();
    }

    public File file(String name) {
        String n = safeName(name);
        return n == null ? null : new File(dir(), n);
    }

    public List<File> list() {
        File[] files = dir().listFiles(f -> f.isFile() && safeName(f.getName()) != null);
        List<File> out = new ArrayList<>();
        if (files != null) out.addAll(Arrays.asList(files));
        Collections.sort(out, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return a.getName().toLowerCase(Locale.ROOT).compareTo(b.getName().toLowerCase(Locale.ROOT));
            }
        });
        return out;
    }

    public JSONArray listJson() throws JSONException {
        Set<String> inList = configFiles();
        JSONArray arr = new JSONArray();
        for (File f : list()) {
            JSONObject o = new JSONObject();
            o.put("name", f.getName());
            o.put("size", f.length());
            o.put("modified", f.lastModified());
            o.put("audio", isAudio(f.getName()));
            o.put("inConfig", inList.contains(f.getName()));
            arr.put(o);
        }
        return arr;
    }

    /** Streams {@code in} into the robot folder atomically (temp file, then rename). */
    public File save(String name, InputStream in, long maxBytes) throws IOException {
        String n = safeName(name);
        if (n == null) throw new IOException("Invalid file name: " + name);
        if (!ensureDir()) throw new IOException("Cannot create robot folder " + dir());
        File tmp = new File(dir(), n + ".part");
        long total = 0;
        try (OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[32 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) {
                total += r;
                if (total > maxBytes) throw new IOException("File too large");
                out.write(buf, 0, r);
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
        File dst = new File(dir(), n);
        if (dst.exists() && !dst.delete()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Cannot replace existing " + n);
        }
        if (!tmp.renameTo(dst)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Cannot write " + n);
        }
        return dst;
    }

    public boolean delete(String name) {
        File f = file(name);
        return f != null && f.isFile() && f.delete();
    }

    /** Downloads {@code url} into the robot folder as {@code name}. Blocking: call off the main thread. */
    public File pull(String url, String name) throws IOException {
        if (url == null || !url.startsWith("http")) throw new IOException("Invalid URL: " + url);
        if (name != null && name.equals(settings.jsonName())) backupConfig();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IOException("Server returned HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                return save(name, in, 200L * 1024 * 1024);
            }
        } finally {
            c.disconnect();
        }
    }

    // ---- Dance config (danceconfig.json) ----

    public File configFile() { return new File(dir(), settings.jsonName()); }

    public String readConfigText() throws IOException {
        File f = configFile();
        if (!f.isFile()) return "[]";
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
            return bos.toString("UTF-8");
        }
    }

    public JSONArray readConfig() {
        try {
            return new JSONArray(readConfigText());
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** Copies the current config to "<name>.backup.json" so an overwrite can always be undone. */
    public void backupConfig() {
        File cf = configFile();
        if (!cf.isFile()) return;
        String base = settings.jsonName().replaceAll("\\.json$", "");
        // Quick successive edits (e.g. toggling songs) must not overwrite the backup with a half-edited list.
        File existing = new File(dir(), base + ".backup.json");
        if (existing.isFile() && System.currentTimeMillis() - existing.lastModified() < 10 * 60 * 1000L) return;
        try (InputStream in = new FileInputStream(cf)) {
            save(base + ".backup.json", in, 1024 * 1024);
        } catch (IOException e) {
            log.warn("Could not back up the config: " + e.getMessage());
        }
    }

    /** Validates and writes the config (after backing up the old one). Must be a JSON array of objects. */
    public void writeConfig(String text) throws IOException, JSONException {
        JSONArray arr = new JSONArray(text);
        for (int i = 0; i < arr.length(); i++) {
            if (!(arr.get(i) instanceof JSONObject)) throw new JSONException("Entry " + (i + 1) + " is not an object");
        }
        // org.json escapes "/" as "\/"; keep plain slashes like the original file so the robot parses it the same way.
        String pretty = arr.toString(2).replaceAll("(?<!\\\\)((?:\\\\\\\\)*)\\\\/", "$1/");
        backupConfig();
        save(settings.jsonName(), new java.io.ByteArrayInputStream(pretty.getBytes("UTF-8")), 1024 * 1024);
    }

    /** Sound file names referenced by the config (leading "/" removed). */
    public Set<String> configFiles() {
        Set<String> out = new HashSet<>();
        JSONArray arr = readConfig();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            String n = safeName(o == null ? null : o.optString("fileName"));
            if (n != null) out.add(n);
        }
        return out;
    }

    /** Appends a dance entry for {@code name} unless the list already has it. Returns true if added. */
    public boolean addToConfig(String name) throws IOException, JSONException {
        if (configFiles().contains(name)) return false;
        JSONArray arr = readConfig();
        JSONObject o = new JSONObject();
        o.put("fileName", "/" + name);
        o.put("danceEmoji", "0");
        o.put("headAction", "0");
        o.put("footAction", "0");
        arr.put(o);
        writeConfig(arr.toString());
        return true;
    }

    /**
     * Replaces the whole dance list with one entry for {@code name}. Keeps that file's existing
     * emoji/head/foot values if it was already listed, otherwise copies them from the first entry.
     * The old list is backed up first. Sound files are not touched.
     */
    public void setOnly(String name) throws IOException, JSONException {
        JSONArray arr = readConfig();
        JSONObject src = null;
        for (int i = 0; i < arr.length() && src == null; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && name.equals(safeName(o.optString("fileName")))) src = o;
        }
        if (src == null && arr.length() > 0) src = arr.optJSONObject(0);
        JSONObject o = new JSONObject();
        o.put("fileName", "/" + name);
        o.put("danceEmoji", src == null ? "0" : src.optString("danceEmoji", "0"));
        o.put("headAction", src == null ? "0" : src.optString("headAction", "0"));
        o.put("footAction", src == null ? "0" : src.optString("footAction", "0"));
        writeConfig(new JSONArray().put(o).toString());
    }

    /** Removes every entry that points at {@code name}. Returns true if anything was removed. */
    public boolean removeFromConfig(String name) throws IOException, JSONException {
        JSONArray arr = readConfig(), keep = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && name.equals(safeName(o.optString("fileName")))) continue;
            keep.put(arr.get(i));
        }
        if (keep.length() == arr.length()) return false;
        writeConfig(keep.toString());
        return true;
    }

    public JSONObject status() throws JSONException {
        JSONObject o = new JSONObject();
        File d = dir();
        o.put("path", d.getAbsolutePath());
        o.put("exists", d.isDirectory());
        o.put("writable", d.isDirectory() && d.canWrite());
        o.put("count", list().size());
        JSONArray arr = readConfig();
        int missing = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            String n = safeName(e == null ? null : e.optString("fileName"));
            if (n == null || !new File(d, n).isFile()) missing++;
        }
        o.put("configExists", configFile().isFile());
        o.put("songs", arr.length());
        o.put("missing", missing);
        return o;
    }

    public static String humanSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        return String.format(Locale.US, "%.1f MB", b / 1048576.0);
    }
}

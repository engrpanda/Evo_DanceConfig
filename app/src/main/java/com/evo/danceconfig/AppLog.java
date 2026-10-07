package com.evo.danceconfig;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** Thread-safe in-memory log (last 500 lines) mirrored to a file. */
public class AppLog {

    public static final int INFO = 0, OK = 1, WARN = 2, ERROR = 3;
    private static final int MAX_LINES = 500;

    public static class Entry {
        public final long time;
        public final int level;
        public final String message;

        Entry(long time, int level, String message) {
            this.time = time;
            this.level = level;
            this.message = message;
        }

        public String timeText() {
            return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(time));
        }
    }

    public interface Listener { void onLog(Entry entry); }

    private final List<Entry> entries = new ArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File file;

    public AppLog(File dir) {
        file = new File(dir, "danceconfig_log.txt");
    }

    public void info(String m) { add(INFO, m); }
    public void ok(String m) { add(OK, m); }
    public void warn(String m) { add(WARN, m); }
    public void error(String m) { add(ERROR, m); }

    public synchronized void add(int level, String message) {
        final Entry e = new Entry(System.currentTimeMillis(), level, message);
        entries.add(e);
        while (entries.size() > MAX_LINES) entries.remove(0);
        try (FileOutputStream fos = new FileOutputStream(file, true)) {
            fos.write(("[" + e.timeText() + "] " + message + "\n").getBytes("UTF-8"));
        } catch (Exception ignored) { }
        main.post(() -> { for (Listener l : listeners) l.onLog(e); });
    }

    public synchronized List<Entry> snapshot() {
        return new ArrayList<>(entries);
    }

    public synchronized void clear() {
        entries.clear();
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    public static String levelName(int level) {
        switch (level) {
            case OK: return "ok";
            case WARN: return "warn";
            case ERROR: return "error";
            default: return "info";
        }
    }
}

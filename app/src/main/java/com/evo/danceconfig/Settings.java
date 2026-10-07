package com.evo.danceconfig;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

/** All user-configurable robot settings, persisted in SharedPreferences. */
public class Settings {

    public static final String DEF_ROBOT_PATH = "/sdcard/robot/rndata/system_150f8e7a0eb74964260dcd48b4cc9f99/extra/";
    public static final String DEF_MUSIC_URL = "https://github.com/engrpanda/Evo_DanceConfig/releases/download/v0.1/minibotmusic.mp3";
    public static final String DEF_JSON_URL = "https://raw.githubusercontent.com/engrpanda/Evo_DanceConfig/refs/heads/master/danceconfig.json";
    public static final String DEF_MUSIC_NAME = "minibotmusic.mp3";
    public static final String DEF_JSON_NAME = "danceconfig.json";
    public static final int DEF_PORT = 8080;
    public static final int DEF_VOLUME = 100;
    public static final int DEF_UI_DPI = 320;

    private final SharedPreferences prefs;

    public Settings(Context context) {
        prefs = context.getSharedPreferences("danceconfig_settings", Context.MODE_PRIVATE);
    }

    public String robotPath() {
        String p = prefs.getString("robotPath", DEF_ROBOT_PATH).trim();
        if (p.isEmpty()) p = DEF_ROBOT_PATH;
        return p.endsWith("/") ? p : p + "/";
    }

    public String musicUrl() { return prefs.getString("musicUrl", DEF_MUSIC_URL).trim(); }
    public String jsonUrl() { return prefs.getString("jsonUrl", DEF_JSON_URL).trim(); }
    public String musicName() { return prefs.getString("musicName", DEF_MUSIC_NAME).trim(); }
    public String jsonName() { return prefs.getString("jsonName", DEF_JSON_NAME).trim(); }
    public int port() { return prefs.getInt("port", DEF_PORT); }
    public boolean autoStartServer() { return prefs.getBoolean("autoStart", true); }
    public String pin() { return prefs.getString("pin", ""); }
    public int volume() { return prefs.getInt("volume", DEF_VOLUME); }
    /** Target UI density in dpi; 0 keeps the system value. Only ever lowers the density (bigger layout area). */
    public int uiDpi() { return prefs.getInt("uiDpi", DEF_UI_DPI); }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("robotPath", robotPath());
        o.put("musicUrl", musicUrl());
        o.put("jsonUrl", jsonUrl());
        o.put("musicName", musicName());
        o.put("jsonName", jsonName());
        o.put("port", port());
        o.put("autoStart", autoStartServer());
        o.put("pinEnabled", !pin().isEmpty());
        o.put("volume", volume());
        return o;
    }

    /** Applies the keys present in {@code o}. Returns an error message, or null when OK. */
    public String apply(JSONObject o) {
        SharedPreferences.Editor e = prefs.edit();
        if (o.has("robotPath")) {
            String p = o.optString("robotPath").trim();
            if (!p.startsWith("/")) return "Robot folder must be an absolute path (start with /).";
            e.putString("robotPath", p);
        }
        if (o.has("musicUrl")) e.putString("musicUrl", o.optString("musicUrl").trim());
        if (o.has("jsonUrl")) e.putString("jsonUrl", o.optString("jsonUrl").trim());
        if (o.has("musicName")) {
            String n = RobotStorage.safeName(o.optString("musicName"));
            if (n == null) return "Music file name is not valid.";
            e.putString("musicName", n);
        }
        if (o.has("jsonName")) {
            String n = RobotStorage.safeName(o.optString("jsonName"));
            if (n == null) return "JSON file name is not valid.";
            e.putString("jsonName", n);
        }
        if (o.has("port")) {
            int port = o.optInt("port", -1);
            if (port < 1024 || port > 65535) return "Port must be between 1024 and 65535.";
            e.putInt("port", port);
        }
        if (o.has("autoStart")) e.putBoolean("autoStart", o.optBoolean("autoStart"));
        if (o.has("pin")) e.putString("pin", o.optString("pin").trim());
        if (o.has("uiDpi")) {
            int d = o.optInt("uiDpi", -1);
            if (d != 0 && (d < 160 || d > 640)) return "UI density must be 0 (system) or between 160 and 640.";
            e.putInt("uiDpi", d);
        }
        if (o.has("volume")) e.putInt("volume", Math.max(0, Math.min(100, o.optInt("volume", DEF_VOLUME))));
        e.apply();
        return null;
    }

    public void resetDefaults() {
        prefs.edit().clear().apply();
    }
}

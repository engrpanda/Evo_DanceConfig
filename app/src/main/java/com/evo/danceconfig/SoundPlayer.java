package com.evo.danceconfig;

import android.media.AudioAttributes;
import android.media.MediaPlayer;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/** Plays a robot-folder sound through the device speaker so it can be tested on the robot. */
public class SoundPlayer {

    public interface Listener { void onPlayerChanged(); }

    private final Settings settings;
    private final AppLog log;
    private MediaPlayer player;
    private String playingName;
    private Listener listener;

    public SoundPlayer(Settings settings, AppLog log) {
        this.settings = settings;
        this.log = log;
    }

    public synchronized void setListener(Listener l) { listener = l; }

    private void notifyChanged() {
        Listener l = listener;
        if (l != null) l.onPlayerChanged();
    }

    public synchronized boolean play(File f) {
        stopInternal();
        if (f == null || !f.isFile()) {
            log.error("Cannot play: file not found.");
            return false;
        }
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setDataSource(f.getAbsolutePath());
            float v = settings.volume() / 100f;
            mp.setVolume(v, v);
            mp.setOnCompletionListener(m -> {
                synchronized (SoundPlayer.this) {
                    if (player == m) {
                        log.info("Finished playing " + playingName);
                        stopInternal();
                    }
                }
                notifyChanged();
            });
            mp.setOnErrorListener((m, what, extra) -> {
                synchronized (SoundPlayer.this) {
                    log.error("Playback error (" + what + "/" + extra + ") on " + playingName);
                    stopInternal();
                }
                notifyChanged();
                return true;
            });
            mp.prepare();
            mp.start();
            player = mp;
            playingName = f.getName();
            log.info("Playing on robot: " + playingName);
            notifyChanged();
            return true;
        } catch (Exception e) {
            log.error("Cannot play " + f.getName() + ": " + e.getMessage());
            stopInternal();
            notifyChanged();
            return false;
        }
    }

    public synchronized void stop() {
        if (player != null) log.info("Stopped playback.");
        stopInternal();
        notifyChanged();
    }

    private void stopInternal() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
        }
        player = null;
        playingName = null;
    }

    public synchronized boolean isPlaying() {
        try { return player != null && player.isPlaying(); } catch (Exception e) { return false; }
    }

    public synchronized String playingName() { return playingName; }

    public synchronized JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        boolean playing = isPlaying();
        o.put("playing", playing);
        o.put("name", playing ? playingName : JSONObject.NULL);
        o.put("position", playing ? player.getCurrentPosition() : 0);
        o.put("duration", playing ? player.getDuration() : 0);
        return o;
    }

    public synchronized void release() { stopInternal(); }
}

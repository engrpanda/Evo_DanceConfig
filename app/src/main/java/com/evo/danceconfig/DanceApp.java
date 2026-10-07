package com.evo.danceconfig;

import android.app.Application;

/** Holds the long-lived singletons so the web server and the UI share the same state. */
public class DanceApp extends Application {

    public Settings settings;
    public AppLog log;
    public RobotStorage storage;
    public SoundPlayer player;
    public RobotServer server;

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new Settings(this);
        log = new AppLog(getFilesDir());
        storage = new RobotStorage(settings, log);
        player = new SoundPlayer(settings, log);
        server = new RobotServer(this, settings, log, storage, player);
    }
}

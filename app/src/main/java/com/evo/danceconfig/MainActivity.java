package com.evo.danceconfig;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.navigationrail.NavigationRailView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.slider.Slider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_STORAGE_PERMISSION = 100;

    private DanceApp app;
    private com.evo.danceconfig.Settings settings;
    private AppLog log;
    private RobotStorage storage;
    private SoundPlayer player;
    private RobotServer server;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private View secDashboard, secFiles, secConfig, secSettings, secLog;

    // dashboard
    private View dotServer, dotFolder;
    private TextView tvServerState, tvServerUrls, tvFolderState, tvFolderPath, tvCurrentName, tvCurrentMeta;
    private MaterialButton btnServerToggle, btnGrantAccess, btnTestCurrent, btnPullAll;
    private LinearProgressIndicator progressPull;
    private NavigationRailView rail;
    private TextView tvEntryCount;

    // files / config / settings / log
    private LinearLayout filesContainer, entriesContainer, logContainer;
    private TextView tvFilesEmpty, tvVolume;
    private ScrollView scrollLog;
    private EditText etUiDpi, etRobotPath, etMusicUrl, etJsonUrl, etMusicName, etJsonName, etPort, etPin;
    private MaterialSwitch switchAutoStart;
    private Slider sliderVolume;

    private int currentSection = R.id.nav_dashboard;

    private final AppLog.Listener logListener = this::appendLog;

    private final ActivityResultLauncher<String[]> importPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::importUri);

    /** The robot panel is 1920x1080 at 560 dpi (about 549x308 dp), far too small for this layout, so scale down. */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        int target = new com.evo.danceconfig.Settings(base).uiDpi();
        android.content.res.Configuration cfg = new android.content.res.Configuration(base.getResources().getConfiguration());
        if (target > 0 && cfg.densityDpi > target) {
            cfg.densityDpi = target;
            base = base.createConfigurationContext(cfg);
        }
        super.attachBaseContext(base);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        app = (DanceApp) getApplication();
        settings = app.settings;
        log = app.log;
        storage = app.storage;
        player = app.player;
        server = app.server;

        bindViews();
        wireActions();

        for (AppLog.Entry e : log.snapshot()) appendLog(e);

        rail = findViewById(R.id.navRail);
        rail.setOnItemSelectedListener(item -> {
            showSection(item.getItemId());
            return true;
        });
        rail.setSelectedItemId(R.id.nav_dashboard);

        loadSettingsIntoForm();
        log.info("EVO Dance Manager ready.");
    }

    @Override
    protected void onStart() {
        super.onStart();
        log.addListener(logListener);
        server.setStateListener(() -> runOnUiThread(this::refreshDashboard));
        player.setListener(() -> runOnUiThread(this::onPlayerChanged));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!hasStorageAccess()) {
            requestStorageAccess(false);
        } else {
            storage.ensureDir();
        }
        if (settings.autoStartServer() && !server.isRunning()) server.start();
        refreshAll();
    }

    @Override
    protected void onStop() {
        super.onStop();
        log.removeListener(logListener);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) io.shutdownNow();
    }

    // ------------------------------------------------------------------ view setup

    private void bindViews() {
        secDashboard = findViewById(R.id.secDashboard);
        secFiles = findViewById(R.id.secFiles);
        secConfig = findViewById(R.id.secConfig);
        secSettings = findViewById(R.id.secSettings);
        secLog = findViewById(R.id.secLog);

        dotServer = findViewById(R.id.dotServer);
        dotFolder = findViewById(R.id.dotFolder);
        tvServerState = findViewById(R.id.tvServerState);
        tvServerUrls = findViewById(R.id.tvServerUrls);
        tvFolderState = findViewById(R.id.tvFolderState);
        tvFolderPath = findViewById(R.id.tvFolderPath);
        tvCurrentName = findViewById(R.id.tvCurrentName);
        tvCurrentMeta = findViewById(R.id.tvCurrentMeta);
        btnServerToggle = findViewById(R.id.btnServerToggle);
        btnGrantAccess = findViewById(R.id.btnGrantAccess);
        btnTestCurrent = findViewById(R.id.btnTestCurrent);
        btnPullAll = findViewById(R.id.btnPullAll);
        tvEntryCount = findViewById(R.id.tvEntryCount);
        progressPull = findViewById(R.id.progressPull);

        filesContainer = findViewById(R.id.filesContainer);
        tvFilesEmpty = findViewById(R.id.tvFilesEmpty);
        entriesContainer = findViewById(R.id.entriesContainer);
        logContainer = findViewById(R.id.logContainer);
        scrollLog = findViewById(R.id.scrollLog);

        etRobotPath = findViewById(R.id.etRobotPath);
        etMusicUrl = findViewById(R.id.etMusicUrl);
        etJsonUrl = findViewById(R.id.etJsonUrl);
        etMusicName = findViewById(R.id.etMusicName);
        etJsonName = findViewById(R.id.etJsonName);
        etPort = findViewById(R.id.etPort);
        etPin = findViewById(R.id.etPin);
        etUiDpi = findViewById(R.id.etUiDpi);
        switchAutoStart = findViewById(R.id.switchAutoStart);
        sliderVolume = findViewById(R.id.sliderVolume);
        tvVolume = findViewById(R.id.tvVolume);
    }

    private void wireActions() {
        btnServerToggle.setOnClickListener(v -> {
            if (server.isRunning()) {
                server.stop();
            } else {
                io.execute(server::start);
            }
        });
        btnGrantAccess.setOnClickListener(v -> requestStorageAccess(true));
        btnTestCurrent.setOnClickListener(v -> rail.setSelectedItemId(R.id.nav_config));
        btnPullAll.setOnClickListener(v -> pull("all"));

        findViewById(R.id.btnImport).setOnClickListener(v -> importPicker.launch(new String[]{"*/*"}));
        findViewById(R.id.btnPullMusic).setOnClickListener(v -> pull("music"));
        findViewById(R.id.btnPullJson).setOnClickListener(v -> pull("json"));
        findViewById(R.id.btnRefreshFiles).setOnClickListener(v -> refreshFiles());
        findViewById(R.id.btnAllOn).setOnClickListener(v -> {
            if (!requireAccess()) return;
            try {
                int added = 0;
                for (File f : storage.list()) {
                    if (RobotStorage.isAudio(f.getName()) && storage.addToConfig(f.getName())) added++;
                }
                log.ok("Activated all songs (" + added + " added to the dance list).");
            } catch (Exception e) {
                log.error("Cannot update config: " + e.getMessage());
            }
            refreshAll();
        });
        findViewById(R.id.btnAllOff).setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle("Turn every song off?")
                .setMessage("The dance list will be empty. The previous list is kept as a backup. Sound files stay on the robot.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Turn off", (d, w) -> {
                    if (!requireAccess()) return;
                    try {
                        storage.writeConfig("[]");
                        log.warn("All songs deactivated. The dance list is empty.");
                    } catch (Exception e) {
                        log.error("Cannot update config: " + e.getMessage());
                    }
                    refreshAll();
                }).show());

        findViewById(R.id.btnAddEntry).setOnClickListener(v -> {
            addEntryView(new JSONObject());
            updateEntryCount();
        });
        findViewById(R.id.btnSaveConfig).setOnClickListener(v -> saveConfig());
        findViewById(R.id.btnReloadConfig).setOnClickListener(v -> loadConfigEditor());

        findViewById(R.id.btnSaveSettings).setOnClickListener(v -> saveSettings());
        findViewById(R.id.btnResetSettings).setOnClickListener(v ->
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Reset settings?")
                        .setMessage("Robot folder, URLs, file names, port, PIN and volume go back to their defaults.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Reset", (d, w) -> {
                            settings.resetDefaults();
                            loadSettingsIntoForm();
                            log.warn("Settings reset to defaults.");
                            refreshAll();
                        }).show());
        findViewById(R.id.btnExit).setOnClickListener(v -> {
            log.info("Exiting app.");
            player.release();
            server.stop();
            finishAffinity();
        });

        sliderVolume.addOnChangeListener((s, value, fromUser) -> tvVolume.setText(((int) value) + "%"));
        findViewById(R.id.btnClearLog).setOnClickListener(v -> {
            log.clear();
            logContainer.removeAllViews();
            log.info("Log cleared.");
        });
    }

    private void showSection(int id) {
        currentSection = id;
        secDashboard.setVisibility(id == R.id.nav_dashboard ? View.VISIBLE : View.GONE);
        secFiles.setVisibility(id == R.id.nav_files ? View.VISIBLE : View.GONE);
        secConfig.setVisibility(id == R.id.nav_config ? View.VISIBLE : View.GONE);
        secSettings.setVisibility(id == R.id.nav_settings ? View.VISIBLE : View.GONE);
        secLog.setVisibility(id == R.id.nav_log ? View.VISIBLE : View.GONE);
        if (id == R.id.nav_files) refreshFiles();
        if (id == R.id.nav_config) loadConfigEditor();
        if (id == R.id.nav_dashboard) refreshDashboard();
        if (id == R.id.nav_log) scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
    }

    private void refreshAll() {
        refreshDashboard();
        if (currentSection == R.id.nav_files) refreshFiles();
    }

    // ------------------------------------------------------------------ storage access

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestStorageAccess(boolean explicit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!explicit) return; // do not yank the user into system settings on every resume
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_STORAGE_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (hasStorageAccess()) {
                log.ok("Storage permission granted.");
                storage.ensureDir();
            } else {
                log.error("Storage permission denied. The robot folder cannot be written.");
            }
            refreshAll();
        }
    }

    /** Logs and returns false when the robot folder cannot be written. */
    private boolean requireAccess() {
        if (!hasStorageAccess()) {
            log.error("Storage access is needed first. Tap \"Grant storage access\" on the Home screen.");
            requestStorageAccess(true);
            return false;
        }
        if (!storage.ensureDir()) {
            log.error("Cannot create robot folder: " + storage.dir());
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ dashboard

    private void refreshDashboard() {
        boolean running = server.isRunning();
        setDot(dotServer, running ? R.color.status_ok : R.color.status_error);
        tvServerState.setText(running ? "Running" : "Stopped");
        btnServerToggle.setText(running ? "Stop server" : "Start server");
        if (running) {
            StringBuilder sb = new StringBuilder();
            for (String u : server.urls()) sb.append(u).append('\n');
            tvServerUrls.setText(sb.toString().trim());
        } else {
            tvServerUrls.setText("Server is off. Start it to manage the robot from a browser.");
        }

        boolean access = hasStorageAccess();
        boolean exists = storage.dir().isDirectory();
        boolean writable = storage.isWritable();
        tvFolderPath.setText(storage.dir().getAbsolutePath());
        if (!access) {
            setDot(dotFolder, R.color.status_warn);
            tvFolderState.setText("Access needed");
        } else if (!exists) {
            setDot(dotFolder, R.color.status_warn);
            tvFolderState.setText("Not created yet");
        } else if (!writable) {
            setDot(dotFolder, R.color.status_error);
            tvFolderState.setText("Read-only");
        } else {
            setDot(dotFolder, R.color.status_ok);
            tvFolderState.setText("Ready");
        }
        btnGrantAccess.setVisibility(access ? View.GONE : View.VISIBLE);

        try {
            JSONObject st = storage.status();
            int songs = st.optInt("songs"), missing = st.optInt("missing");
            tvCurrentName.setText(songs + (songs == 1 ? " song" : " songs"));
            tvCurrentMeta.setText(!st.optBoolean("configExists") ? "No danceconfig.json on the robot yet."
                    : missing == 0 ? "All sound files are on the robot  ·  " + st.optInt("count") + " files in folder"
                    : missing + (missing == 1 ? " song has" : " songs have") + " no sound file on the robot.");
            tvCurrentMeta.setTextColor(missing == 0 ? MaterialColors.getColor(tvCurrentMeta, com.google.android.material.R.attr.colorOnSurfaceVariant)
                    : ContextCompat.getColor(this, R.color.status_warn));
        } catch (Exception ignored) { }
    }

    private void setDot(View dot, int colorRes) {
        Drawable d = dot.getBackground().mutate();
        d.setTint(ContextCompat.getColor(this, colorRes));
        dot.setBackground(d);
    }

    // ------------------------------------------------------------------ playback

    private void toggleSound(String name) {
        if (name != null && name.equals(player.playingName()) && player.isPlaying()) {
            player.stop();
            return;
        }
        File f = storage.file(name);
        if (f == null || !f.isFile()) {
            log.error("Sound not found on the robot: " + name);
            return;
        }
        player.play(f);
    }

    private void onPlayerChanged() {
        if (currentSection == R.id.nav_files) refreshFiles();
        if (currentSection == R.id.nav_config) refreshEntryPlayIcons();
    }

    private void refreshEntryPlayIcons() {
        for (int i = 0; i < entriesContainer.getChildCount(); i++) {
            View v = entriesContainer.getChildAt(i);
            String n = RobotStorage.safeName(text(v, R.id.etEntryFile));
            boolean playing = n != null && player.isPlaying() && n.equals(player.playingName());
            ((MaterialButton) v.findViewById(R.id.btnEntryPlay)).setIcon(
                    ContextCompat.getDrawable(this, playing ? R.drawable.ic_stop : R.drawable.ic_play));
        }
    }

    // ------------------------------------------------------------------ pull / import

    private void pull(String type) {
        if (!requireAccess()) return;
        setBusy(true);
        io.execute(() -> {
            try {
                if (type.equals("music") || type.equals("all")) {
                    log.info("Pulling music from " + settings.musicUrl());
                    File f = storage.pull(settings.musicUrl(), settings.musicName());
                    log.ok("Music saved: " + f.getName() + " (" + RobotStorage.humanSize(f.length()) + ")");
                }
                if (type.equals("json") || type.equals("all")) {
                    log.info("Pulling config from " + settings.jsonUrl());
                    File f = storage.pull(settings.jsonUrl(), settings.jsonName());
                    log.ok("Config saved: " + f.getName());
                }
            } catch (Exception e) {
                log.error("Pull failed: " + e.getMessage());
            }
            runOnUiThread(() -> {
                setBusy(false);
                refreshAll();
                if (currentSection == R.id.nav_config) loadConfigEditor();
            });
        });
    }

    private void setBusy(boolean busy) {
        progressPull.setVisibility(busy ? View.VISIBLE : View.INVISIBLE);
        btnPullAll.setEnabled(!busy);
        findViewById(R.id.btnPullMusic).setEnabled(!busy);
        findViewById(R.id.btnPullJson).setEnabled(!busy);
    }

    private void importUri(Uri uri) {
        if (uri == null) return;
        if (!requireAccess()) return;
        String name = null;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = c.getString(i);
            }
        }
        final String safe = RobotStorage.safeName(name);
        if (safe == null) {
            log.error("Cannot import \"" + name + "\". Use " + RobotStorage.ALLOWED_EXT + " with a simple name (letters, digits, space . _ - ( )).");
            return;
        }
        io.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("Cannot open the selected file");
                File f = storage.save(safe, in, 200L * 1024 * 1024);
                log.ok("Imported " + f.getName() + " (" + RobotStorage.humanSize(f.length()) + ")");
            } catch (Exception e) {
                log.error("Import failed: " + e.getMessage());
            }
            runOnUiThread(this::refreshAll);
        });
    }

    // ------------------------------------------------------------------ files

    private void refreshFiles() {
        filesContainer.removeAllViews();
        List<File> files = storage.list();
        tvFilesEmpty.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
        java.util.Set<String> inList = storage.configFiles();
        int audioCount = 0, activeCount = 0;
        for (File f : files) {
            if (RobotStorage.isAudio(f.getName())) {
                audioCount++;
                if (inList.contains(f.getName())) activeCount++;
            }
        }
        ((TextView) findViewById(R.id.tvActiveSummary)).setText(activeCount + " of " + audioCount + " songs active");
        findViewById(R.id.activeBar).setVisibility(audioCount == 0 ? View.GONE : View.VISIBLE);
        LayoutInflater inf = LayoutInflater.from(this);
        for (File f : files) {
            View row = inf.inflate(R.layout.item_file, filesContainer, false);
            boolean audio = RobotStorage.isAudio(f.getName());
            boolean isCurrent = inList.contains(f.getName());
            boolean isPlaying = player.isPlaying() && f.getName().equals(player.playingName());

            ((ImageView) row.findViewById(R.id.imgKind)).setImageResource(audio ? R.drawable.ic_music : R.drawable.ic_tune);
            ((TextView) row.findViewById(R.id.tvFileName)).setText(f.getName());
            ((TextView) row.findViewById(R.id.tvFileMeta)).setText(
                    RobotStorage.humanSize(f.length()) + "  ·  " + formatDate(f.lastModified()));
            row.findViewById(R.id.tvCurrentBadge).setVisibility(isCurrent ? View.VISIBLE : View.GONE);

            MaterialButton play = row.findViewById(R.id.btnFilePlay);
            com.google.android.material.materialswitch.MaterialSwitch sw = row.findViewById(R.id.swActive);
            MaterialButton del = row.findViewById(R.id.btnFileDelete);
            play.setVisibility(audio ? View.VISIBLE : View.GONE);
            sw.setVisibility(audio ? View.VISIBLE : View.GONE);
            play.setIcon(ContextCompat.getDrawable(this, isPlaying ? R.drawable.ic_stop : R.drawable.ic_play));
            play.setOnClickListener(v -> toggleSound(f.getName()));
            sw.setChecked(isCurrent); // set before attaching the listener so it does not fire
            sw.setOnCheckedChangeListener((b, on) -> {
                if (!requireAccess()) { b.setChecked(!on); return; }
                try {
                    if (on) {
                        storage.addToConfig(f.getName());
                        log.ok("Activated " + f.getName() + " (added to the dance list).");
                    } else {
                        storage.removeFromConfig(f.getName());
                        log.warn("Deactivated " + f.getName() + " (removed from the dance list).");
                    }
                } catch (Exception e) {
                    log.error("Cannot update config: " + e.getMessage());
                }
                refreshAll();
            });
            del.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                    .setTitle("Delete " + f.getName() + "?")
                    .setMessage("This removes it from the robot folder.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete", (d, w) -> {
                        if (f.getName().equals(player.playingName())) player.stop();
                        if (storage.delete(f.getName())) log.warn("Deleted " + f.getName());
                        else log.error("Could not delete " + f.getName());
                        refreshAll();
                    }).show());
            filesContainer.addView(row);
        }
    }

    // ------------------------------------------------------------------ dance config

    private void loadConfigEditor() {
        entriesContainer.removeAllViews();
        JSONArray arr = storage.readConfig();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) addEntryView(o);
        }
        updateEntryCount();
    }

    /** Dropdown values: 0 and 1, plus whatever the file already holds so unknown values are never lost. */
    private android.widget.Spinner setupChoice(View row, int id, String value) {
        android.widget.Spinner sp = row.findViewById(id);
        java.util.List<String> opts = new java.util.ArrayList<>(java.util.Arrays.asList("0", "1"));
        if (!value.isEmpty() && !opts.contains(value)) opts.add(value);
        android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<>(this, R.layout.spinner_item, opts);
        ad.setDropDownViewResource(R.layout.spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setSelection(Math.max(0, opts.indexOf(value)));
        return sp;
    }

    private void addEntryView(JSONObject o) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_dance_entry, entriesContainer, false);
        v.setTag(o); // keeps unknown keys so they survive a save
        EditText file = v.findViewById(R.id.etEntryFile);
        file.setText(o.optString("fileName"));
        setupChoice(v, R.id.spEntryEmoji, o.optString("danceEmoji", "0"));
        setupChoice(v, R.id.spEntryHead, o.optString("headAction", "0"));
        setupChoice(v, R.id.spEntryFoot, o.optString("footAction", "0"));
        Runnable paint = () -> {
            String n = RobotStorage.safeName(file.getText().toString());
            boolean ok = n != null && new File(storage.dir(), n).isFile();
            file.setTextColor(ok ? MaterialColors.getColor(file, com.google.android.material.R.attr.colorOnSurface)
                    : ContextCompat.getColor(this, R.color.status_error));
            v.findViewById(R.id.btnEntryPlay).setEnabled(ok);
        };
        file.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) { paint.run(); }
        });
        paint.run();
        v.findViewById(R.id.btnEntryPlay).setOnClickListener(b -> toggleSound(RobotStorage.safeName(file.getText().toString())));
        v.findViewById(R.id.btnEntryDelete).setOnClickListener(b -> {
            entriesContainer.removeView(v);
            updateEntryCount();
        });
        entriesContainer.addView(v);
    }

    private void updateEntryCount() {
        int n = entriesContainer.getChildCount();
        tvEntryCount.setText(n + (n == 1 ? " song" : " songs") + "  ·  red = no sound file on the robot");
    }

    private void saveConfig() {
        if (!requireAccess()) return;
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < entriesContainer.getChildCount(); i++) {
                View v = entriesContainer.getChildAt(i);
                JSONObject o = (JSONObject) v.getTag();
                String file = text(v, R.id.etEntryFile);
                if (file.isEmpty()) {
                    log.error("Entry " + (i + 1) + " needs a sound file name.");
                    return;
                }
                o.put("fileName", file);
                o.put("danceEmoji", String.valueOf(((android.widget.Spinner) v.findViewById(R.id.spEntryEmoji)).getSelectedItem()));
                o.put("headAction", String.valueOf(((android.widget.Spinner) v.findViewById(R.id.spEntryHead)).getSelectedItem()));
                o.put("footAction", String.valueOf(((android.widget.Spinner) v.findViewById(R.id.spEntryFoot)).getSelectedItem()));
                arr.put(o);
            }
            storage.writeConfig(arr.toString());
            log.ok("Dance list saved (" + arr.length() + (arr.length() == 1 ? " song" : " songs") + "). Previous list kept as a backup.");
            refreshAll();
        } catch (Exception e) {
            log.error("Cannot save config: " + e.getMessage());
        }
    }

    private static String text(View root, int id) {
        return ((EditText) root.findViewById(id)).getText().toString().trim();
    }

    // ------------------------------------------------------------------ settings

    private void loadSettingsIntoForm() {
        etRobotPath.setText(settings.robotPath());
        etMusicUrl.setText(settings.musicUrl());
        etJsonUrl.setText(settings.jsonUrl());
        etMusicName.setText(settings.musicName());
        etJsonName.setText(settings.jsonName());
        etPort.setText(String.valueOf(settings.port()));
        etPin.setText(settings.pin());
        etUiDpi.setText(String.valueOf(settings.uiDpi()));
        switchAutoStart.setChecked(settings.autoStartServer());
        sliderVolume.setValue(settings.volume());
        tvVolume.setText(settings.volume() + "%");
    }

    private void saveSettings() {
        try {
            JSONObject o = new JSONObject();
            o.put("robotPath", etRobotPath.getText().toString());
            o.put("musicUrl", etMusicUrl.getText().toString());
            o.put("jsonUrl", etJsonUrl.getText().toString());
            o.put("musicName", etMusicName.getText().toString());
            o.put("jsonName", etJsonName.getText().toString());
            o.put("port", parseInt(etPort.getText().toString()));
            o.put("pin", etPin.getText().toString());
            o.put("uiDpi", parseInt(etUiDpi.getText().toString()));
            o.put("autoStart", switchAutoStart.isChecked());
            o.put("volume", (int) sliderVolume.getValue());
            int oldPort = settings.port();
            int oldDpi = settings.uiDpi();
            String err = settings.apply(o);
            if (err != null) {
                log.error(err);
                return;
            }
            log.ok("Settings saved.");
            if (oldDpi != settings.uiDpi()) {
                log.info("UI size changed, reloading screen.");
                recreate();
            }
            if (server.isRunning() && oldPort != settings.port()) {
                io.execute(() -> {
                    server.restart();
                    runOnUiThread(this::refreshDashboard);
                });
            }
            storage.ensureDir();
            refreshAll();
        } catch (Exception e) {
            log.error("Cannot save settings: " + e.getMessage());
        }
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return -1; }
    }

    // ------------------------------------------------------------------ log view

    private void appendLog(AppLog.Entry e) {
        TextView tv = new TextView(this);
        androidx.core.widget.TextViewCompat.setTextAppearance(tv, R.style.Text_Mono);
        tv.setPadding(0, 3, 0, 3);
        tv.setTextIsSelectable(true);
        int attr = com.google.android.material.R.attr.colorOnSurface;
        int color;
        switch (e.level) {
            case AppLog.OK: color = ContextCompat.getColor(this, R.color.status_ok); break;
            case AppLog.WARN: color = ContextCompat.getColor(this, R.color.status_warn); break;
            case AppLog.ERROR: color = ContextCompat.getColor(this, R.color.status_error); break;
            default: color = MaterialColors.getColor(logContainer, attr);
        }
        tv.setTextColor(color);
        tv.setText(e.timeText() + "  " + e.message);
        logContainer.addView(tv);
        while (logContainer.getChildCount() > 500) logContainer.removeViewAt(0);
        scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
    }

    private static String formatDate(long millis) {
        return new SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(new Date(millis));
    }
}

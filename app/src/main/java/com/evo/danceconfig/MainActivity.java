package com.evo.danceconfig;

import android.Manifest;
import android.app.DownloadManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_STORAGE_PERMISSION = 100;

    private LinearLayout logContainer;
    private ScrollView scrollLog;

    private final String musicUrl = "https://github.com/engrpanda/Evo_DanceConfig/releases/download/v0.1/minibotmusic.mp3";
    private final String jsonUrl = "https://raw.githubusercontent.com/engrpanda/Evo_DanceConfig/refs/heads/master/danceconfig.json";
    private final String robotPath = "/sdcard/robot/rndata/system_ccfda69b10253ec17722dfd329d3bf01/extra/";

    private File musicFile;
    private File jsonFile;
    private File logFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        logContainer = findViewById(R.id.logContainer);
        scrollLog = findViewById(R.id.scrollLog);

        logFile = new File(getFilesDir(), "danceconfig_log.txt");
        loadLogs();

        MaterialButton btnDownloadMusic = findViewById(R.id.btnDownloadMusic);
        MaterialButton btnDownloadJson = findViewById(R.id.btnDownloadJson);
        MaterialButton btnPushToRobot = findViewById(R.id.btnPushToRobot);
        MaterialButton btnOpenFolder = findViewById(R.id.btnOpenFolder);
        MaterialButton btnClearLog = findViewById(R.id.btnClearLog);

        MaterialButton btnExit = findViewById(R.id.btnExit);
        btnExit.setOnClickListener(v -> {
            log("Exiting app...");
            finishAffinity(); // Close app completely
        });


        btnDownloadMusic.setOnClickListener(v -> checkPermissionAndDownload("Music", musicUrl, "minibotmusic.mp3"));
        btnDownloadJson.setOnClickListener(v -> checkPermissionAndDownload("JSON", jsonUrl, "danceconfig.json"));
        btnPushToRobot.setOnClickListener(v -> checkPermissionAndPush());
        btnOpenFolder.setOnClickListener(v -> log("Opening folder: " + robotPath));
        btnClearLog.setOnClickListener(v -> {
            logContainer.removeAllViews();
            if (logFile.exists()) logFile.delete();
            log("Log cleared.");
        });
    }

    private void checkPermissionAndDownload(String type, String url, String fileName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE_PERMISSION);
            return;
        }
        downloadFile(type, url, fileName);
    }

    private void checkPermissionAndPush() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE_PERMISSION);
            return;
        }
        pushFiles();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                log("Storage permission granted. Please retry the action.");
            } else {
                log("Storage permission denied. Cannot perform action.");
            }
        }
    }

    private void downloadFile(String type, String url, String fileName) {
        log("Downloading " + type + " from: " + url);

        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!downloadDir.exists()) downloadDir.mkdirs();

        File targetFile = new File(downloadDir, fileName);

        // Always overwrite old file
        if (targetFile.exists() && !targetFile.delete()) {
            log("Warning: Cannot delete old " + type + " file. Will try to overwrite.");
        }

        if (type.equals("Music")) musicFile = targetFile;
        else jsonFile = targetFile;

        Uri fileUri = Uri.fromFile(targetFile);

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
                .setTitle("Downloading " + fileName)
                .setDescription("Please wait...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(fileUri);

        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        long downloadId = dm.enqueue(request);

        new Thread(() -> {
            boolean downloading = true;
            while (downloading) {
                DownloadManager.Query q = new DownloadManager.Query().setFilterById(downloadId);
                Cursor cursor = dm.query(q);
                if (cursor != null && cursor.moveToFirst()) {
                    int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        runOnUiThread(() -> log(type + " downloaded: " + targetFile.getName() + " (" + formatDate(targetFile.lastModified()) + ")"));
                        downloading = false;
                    }
                    cursor.close();
                }
                try { Thread.sleep(500); } catch (Exception ignored) {}
            }
        }).start();
    }

    private void pushFiles() {
        if (musicFile == null || !musicFile.exists() || jsonFile == null || !jsonFile.exists()) {
            log("Error: Download Music and JSON first before pushing.");
            return;
        }

        log("Pushing files to robot folder: " + robotPath);
        File robotDir = new File(robotPath);
        if (!robotDir.exists() && !robotDir.mkdirs()) {
            log("Error: Cannot create robot folder.");
            return;
        }

        copyFile(musicFile, new File(robotDir, musicFile.getName()));
        copyFile(jsonFile, new File(robotDir, jsonFile.getName()));

        log("Files pushed successfully to robot folder.");
    }

    private void copyFile(File src, File dst) {
        try (FileChannel inChannel = new FileInputStream(src).getChannel();
             FileChannel outChannel = new FileOutputStream(dst).getChannel()) {
            outChannel.transferFrom(inChannel, 0, inChannel.size());
            log("Copied: " + dst.getName() + " (" + formatDate(dst.lastModified()) + ")");
        } catch (Exception e) {
            log("Error copying file: " + e.getMessage());
        }
    }

    private void loadLogs() {
        if (logFile.exists()) {
            try (FileInputStream fis = new FileInputStream(logFile)) {
                byte[] bytes = new byte[(int) logFile.length()];
                fis.read(bytes);
                String content = new String(bytes);
                for (String line : content.split("\n")) {
                    addLogToView(line);
                }
            } catch (Exception e) {
                addLogToView("Error loading logs: " + e.getMessage());
            }
        }
    }

    private void log(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String fullMessage = "[" + timestamp + "] " + message;

        addLogToView(fullMessage);
        appendLogToFile(fullMessage);
    }

    private void addLogToView(String message) {
        TextView tv = new TextView(this);
        tv.setText(message);
        tv.setTextSize(14f);
        tv.setTextColor(0xFF333333);
        logContainer.addView(tv);
        scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
    }

    private void appendLogToFile(String message) {
        try (FileOutputStream fos = new FileOutputStream(logFile, true)) { // append = true
            fos.write((message + "\n").getBytes());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String formatDate(long millis) {
        return new SimpleDateFormat("dd-MMM-yyyy HH:mm", Locale.getDefault()).format(new Date(millis));
    }
}

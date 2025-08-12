package com.evo.danceconfig;

import android.Manifest;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PICK_MUSIC = 1001;
    private static final int REQ_PICK_JSON = 1002;

    private Uri musicUri = null;
    private Uri jsonUri = null;

    private String copyDestPath = "/sdcard/robot/rndata/system_ccfda69b10253ec17722dfd329d3bf01/extra/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // uses your XML layout

        requestPermissions();

        WebView webView = findViewById(R.id.webView);
        Button btnDownload = findViewById(R.id.btnDownload);
        Button btnSelectMusic = findViewById(R.id.btnSelectMusic);
        Button btnSelectJson = findViewById(R.id.btnSelectJson);
        Button btnCopyFiles = findViewById(R.id.btnCopyFiles);

        // Setup WebView
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setDomStorageEnabled(true);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());

        // Handle downloads from WebView
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);
                String fileName = contentDisposition.replace("inline; filename=", "").replace("\"", "");
                request.addRequestHeader("User-Agent", userAgent);
                request.setDescription("Downloading file...");
                request.setTitle(fileName);
                request.allowScanningByMediaScanner();
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                dm.enqueue(request);
                Toast.makeText(MainActivity.this, "Downloading " + fileName, Toast.LENGTH_SHORT).show();
            }
        });

        // Button: open GitHub release in WebView
        btnDownload.setOnClickListener(v -> webView.loadUrl("https://github.com/engrpanda/Evo_AppStore/releases/tag/1.0.1"));

        // Button: select music file
        btnSelectMusic.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("audio/*");
            startActivityForResult(Intent.createChooser(intent, "Select Music"), REQ_PICK_MUSIC);
        });

        // Button: select JSON file
        btnSelectJson.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("application/json");
            startActivityForResult(Intent.createChooser(intent, "Select JSON"), REQ_PICK_JSON);
        });

        // Button: copy selected files
        btnCopyFiles.setOnClickListener(v -> {
            if (musicUri == null && jsonUri == null) {
                Toast.makeText(this, "No files selected", Toast.LENGTH_SHORT).show();
                return;
            }

            File destDir = new File(copyDestPath);
            if (!destDir.exists()) {
                if (!destDir.mkdirs()) {
                    Toast.makeText(this, "Failed to create destination folder", Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            if (musicUri != null) {
                copyFile(musicUri, new File(destDir, "music.mp3")); // fixed name or extract from Uri
            }
            if (jsonUri != null) {
                copyFile(jsonUri, new File(destDir, "data.json")); // fixed name or extract from Uri
            }

            Toast.makeText(this, "Files copied!", Toast.LENGTH_SHORT).show();
        });
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String[] perms = {
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            };
            ActivityCompat.requestPermissions(this, perms, 123);
        }
    }

    private void copyFile(Uri srcUri, File destFile) {
        try {
            InputStream in = getContentResolver().openInputStream(srcUri);
            OutputStream out = new FileOutputStream(destFile);

            byte[] buf = new byte[1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            in.close();
            out.close();
        } catch (Exception e) {
            Toast.makeText(this, "Copy failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (requestCode == REQ_PICK_MUSIC) {
                musicUri = uri;
                Toast.makeText(this, "Music selected", Toast.LENGTH_SHORT).show();
            } else if (requestCode == REQ_PICK_JSON) {
                jsonUri = uri;
                Toast.makeText(this, "JSON selected", Toast.LENGTH_SHORT).show();
            }
        }
    }
}

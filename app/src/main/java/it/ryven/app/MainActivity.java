package it.ryven.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.splashscreen.SplashScreen;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import android.webkit.JsResult;
import android.webkit.JsPromptResult;
import android.widget.EditText;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String APP_URL = "https://app.ryven.it";

    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private LinearLayout errorView;
    private ProgressBar progressBar;

    private ValueCallback<Uri[]> fileUploadCallback;
    private String cameraPhotoPath;
    private boolean isPageLoaded = false;

    private final ActivityResultLauncher<Intent> fileChooserLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (fileUploadCallback == null) return;

                Uri[] results = null;
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    String dataString = result.getData().getDataString();
                    if (dataString != null) {
                        results = new Uri[]{Uri.parse(dataString)};
                    }
                } else if (result.getResultCode() == Activity.RESULT_OK && cameraPhotoPath != null) {
                    results = new Uri[]{Uri.parse(cameraPhotoPath)};
                }

                fileUploadCallback.onReceiveValue(results);
                fileUploadCallback = null;
            });

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), permissions -> {
                // Permissions handled - WebView will re-request if needed
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Edge-to-edge status bar
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(ContextCompat.getColor(this, android.R.color.transparent));

        webView = findViewById(R.id.webView);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        errorView = findViewById(R.id.errorView);
        progressBar = findViewById(R.id.progressBar);
        MaterialButton retryButton = findViewById(R.id.retryButton);

        swipeRefresh.setColorSchemeColors(
                ContextCompat.getColor(this, android.R.color.holo_green_dark)
        );
        swipeRefresh.setOnRefreshListener(() -> {
            if (isNetworkAvailable()) {
                webView.reload();
            } else {
                swipeRefresh.setRefreshing(false);
                showError();
            }
        });

        retryButton.setOnClickListener(v -> {
            if (isNetworkAvailable()) {
                hideError();
                loadApp();
            } else {
                Toast.makeText(this, getString(R.string.no_internet), Toast.LENGTH_SHORT).show();
            }
        });

        setupWebView();
        loadApp();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();

        // Core settings
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        // Cache - prefer network to ensure fresh data, fall back to cache offline
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(true);

        // Ensure database/localStorage path is set
        settings.setDatabasePath(getApplicationContext().getDir("databases", MODE_PRIVATE).getPath());

        // Viewport & rendering
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);

        // Media & content
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowContentAccess(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);

        // Allow all HTTPS content (images from CDNs, APIs from different origins)
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // Geolocation
        settings.setGeolocationEnabled(true);

        // User agent - use standard mobile Chrome UA to avoid detection/blocking
        // Append RyvenApp identifier for the web app to detect native context
        String ua = settings.getUserAgentString();
        settings.setUserAgentString(ua + " RyvenApp/1.0");

        // Enable cookies - critical for authentication with base44.app
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);
        cookieManager.flush();

        // Enable WebView debugging in debug builds
        WebView.setWebContentsDebuggingEnabled(true);

        // JavaScript bridge for native feedback
        webView.addJavascriptInterface(new WebAppInterface(), "RyvenNative");

        // WebView client - handle navigation and errors
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (!isPageLoaded) {
                    progressBar.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                isPageLoaded = true;
                progressBar.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                hideError();
                injectTouchFixes();
                injectFeedbackSystem();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    showError();
                    progressBar.setVisibility(View.GONE);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // Keep app domains in WebView
                if (url.contains("ryven.it") || url.contains("ryven.app") ||
                    url.contains("base44.com") || url.contains("base44.app") ||
                    url.contains("onesignal.com") || url.contains("openstreetmap.org") ||
                    url.contains("open-meteo.com") || url.contains("unpkg.com") ||
                    url.contains("githubusercontent.com") || url.contains("leaflet")) {
                    return false;
                }
                // Open external links (WhatsApp, etc.) in browser
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                } catch (Exception e) {
                    // Ignore
                }
                return true;
            }
        });

        // Allow popups/new windows
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);

        // Chrome client - handle file uploads, permissions, geolocation, JS dialogs
        webView.setWebChromeClient(new WebChromeClient() {

            // Handle JavaScript alert() dialogs
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Ryven")
                        .setMessage(message)
                        .setPositiveButton("OK", (dialog, which) -> result.confirm())
                        .setCancelable(false)
                        .show();
                return true;
            }

            // Handle JavaScript confirm() dialogs
            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Ryven")
                        .setMessage(message)
                        .setPositiveButton("OK", (dialog, which) -> result.confirm())
                        .setNegativeButton("Annulla", (dialog, which) -> result.cancel())
                        .setCancelable(false)
                        .show();
                return true;
            }

            // Handle JavaScript prompt() dialogs
            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                EditText input = new EditText(MainActivity.this);
                input.setText(defaultValue);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Ryven")
                        .setMessage(message)
                        .setView(input)
                        .setPositiveButton("OK", (dialog, which) -> result.confirm(input.getText().toString()))
                        .setNegativeButton("Annulla", (dialog, which) -> result.cancel())
                        .setCancelable(false)
                        .show();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = callback;
                openFileChooser(fileChooserParams);
                return true;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                                                           GeolocationPermissions.Callback callback) {
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, false);
                } else {
                    permissionLauncher.launch(new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    });
                    callback.invoke(origin, true, false);
                }
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });

        // Scrolling behavior for swipe refresh
        webView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            swipeRefresh.setEnabled(scrollY == 0);
        });
    }

    private void injectTouchFixes() {
        // Minimal touch fixes - avoid overriding the app's own CSS
        // Only add touch-action: manipulation to eliminate 300ms tap delay
        String js = "(function() {" +
                "if (window.__ryvenTouchFixed) return;" +
                "window.__ryvenTouchFixed = true;" +
                "var style = document.createElement('style');" +
                "style.textContent = '" +
                "  html { touch-action: manipulation; }" +
                "';" +
                "document.head.appendChild(style);" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    private void injectFeedbackSystem() {
        // Lightweight: native toast bridge only
        String js = "(function() {" +
                "if (window.__ryvenFeedbackInjected) return;" +
                "window.__ryvenFeedbackInjected = true;" +
                "window.showRyvenToast = function(message) {" +
                "  if (window.RyvenNative) { window.RyvenNative.showToast(message); }" +
                "};" +
                "window.__RYVEN_NATIVE__ = true;" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    private void openFileChooser(WebChromeClient.FileChooserParams params) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        String[] acceptTypes = params.getAcceptTypes();
        if (acceptTypes != null && acceptTypes.length > 0 && acceptTypes[0] != null && !acceptTypes[0].isEmpty()) {
            intent.setType(acceptTypes[0]);
            if (acceptTypes[0].startsWith("image")) {
                // Offer camera option for image uploads
                Intent chooserIntent = createImageChooserIntent(intent);
                fileChooserLauncher.launch(chooserIntent);
                return;
            }
        } else {
            intent.setType("*/*");
        }

        fileChooserLauncher.launch(Intent.createChooser(intent, "Scegli file"));
    }

    private Intent createImageChooserIntent(Intent galleryIntent) {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        try {
            File photoFile = createImageFile();
            if (photoFile != null) {
                cameraPhotoPath = "file:" + photoFile.getAbsolutePath();
                Uri photoUri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", photoFile);
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
            }
        } catch (IOException e) {
            cameraPhotoPath = null;
        }

        Intent chooserIntent = Intent.createChooser(galleryIntent, "Scegli immagine");
        if (cameraPhotoPath != null) {
            chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{takePictureIntent});
        }
        return chooserIntent;
    }

    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String imageFileName = "RYVEN_" + timeStamp + "_";
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        return File.createTempFile(imageFileName, ".jpg", storageDir);
    }

    private void loadApp() {
        if (isNetworkAvailable()) {
            progressBar.setVisibility(View.VISIBLE);
            webView.loadUrl(APP_URL);
        } else {
            showError();
        }
    }

    private void showError() {
        errorView.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
    }

    private void hideError() {
        errorView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        }
        return false;
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        webView.destroy();
        super.onDestroy();
    }

    // JavaScript bridge for native features
    public class WebAppInterface {
        @JavascriptInterface
        public void showToast(String message) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void showSnackbar(String message) {
            runOnUiThread(() -> Snackbar.make(webView, message, Snackbar.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void vibrate() {
            // Short vibration feedback
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(android.os.VibrationEffect.createOneShot(50,
                            android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                }
            }
        }
    }
}

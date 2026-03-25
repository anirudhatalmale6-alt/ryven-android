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
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.splashscreen.SplashScreen;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

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

        // Cache for better performance
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(true);

        // Viewport & rendering
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);

        // Media
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowContentAccess(true);

        // Mixed content (allow HTTPS images from different origins)
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        // Geolocation
        settings.setGeolocationEnabled(true);

        // User agent - append app identifier
        String ua = settings.getUserAgentString();
        settings.setUserAgentString(ua + " RyvenApp/1.0");

        // Enable cookies
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

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
                // Keep app.ryven.it and base44 URLs in WebView
                if (url.contains("ryven.it") || url.contains("base44.com")) {
                    return false;
                }
                // Open external links in browser
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                } catch (Exception e) {
                    // Ignore
                }
                return true;
            }
        });

        // Chrome client - handle file uploads, permissions, geolocation
        webView.setWebChromeClient(new WebChromeClient() {
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
        String js = "(function() {" +
                // Remove 300ms tap delay
                "var meta = document.querySelector('meta[name=viewport]');" +
                "if (meta) {" +
                "  if (!meta.content.includes('user-scalable')) {" +
                "    meta.content += ', user-scalable=no';" +
                "  }" +
                "}" +
                // Fix touch events on buttons and interactive elements
                "document.addEventListener('touchstart', function(e) {" +
                "  var target = e.target.closest('button, a, [role=button], [onclick], input[type=submit], [tabindex]');" +
                "  if (target) {" +
                "    target.style.opacity = '0.7';" +
                "    setTimeout(function() { target.style.opacity = '1'; }, 150);" +
                "  }" +
                "}, {passive: true});" +
                // Fix z-index issues with overlays/popups
                "var style = document.createElement('style');" +
                "style.textContent = '" +
                "  * { -webkit-tap-highlight-color: rgba(46,125,50,0.2); }" +
                "  [role=dialog], [role=alertdialog], .modal, .popup, .overlay, .alert {" +
                "    z-index: 99999 !important;" +
                "    pointer-events: auto !important;" +
                "  }" +
                "  [role=dialog] button, [role=alertdialog] button, .modal button, .popup button {" +
                "    pointer-events: auto !important;" +
                "    position: relative;" +
                "    z-index: 100000 !important;" +
                "  }" +
                "  button, a, [role=button], input[type=submit] {" +
                "    touch-action: manipulation;" +
                "    cursor: pointer;" +
                "  }" +
                "';" +
                "document.head.appendChild(style);" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    private void injectFeedbackSystem() {
        String js = "(function() {" +
                "if (window.__ryvenFeedbackInjected) return;" +
                "window.__ryvenFeedbackInjected = true;" +
                // Create toast/snackbar container
                "var container = document.createElement('div');" +
                "container.id = 'ryven-toast-container';" +
                "container.style.cssText = 'position:fixed;bottom:80px;left:50%;transform:translateX(-50%);z-index:999999;pointer-events:none;';" +
                "document.body.appendChild(container);" +
                // Toast function
                "window.showRyvenToast = function(message, type) {" +
                "  type = type || 'success';" +
                "  var toast = document.createElement('div');" +
                "  var colors = {success:'#2E7D32',error:'#D32F2F',info:'#1976D2',warning:'#F57C00'};" +
                "  var icons = {success:'\u2713',error:'\u2717',info:'\u2139',warning:'\u26A0'};" +
                "  toast.style.cssText = 'background:' + (colors[type]||colors.success) + ';color:white;padding:12px 24px;border-radius:8px;margin-top:8px;font-size:14px;font-family:sans-serif;box-shadow:0 4px 12px rgba(0,0,0,0.3);display:flex;align-items:center;gap:8px;opacity:0;transition:opacity 0.3s;pointer-events:auto;min-width:200px;max-width:80vw;';" +
                "  toast.innerHTML = '<span style=\"font-size:18px\">' + (icons[type]||icons.success) + '</span><span>' + message + '</span>';" +
                "  container.appendChild(toast);" +
                "  requestAnimationFrame(function(){toast.style.opacity='1';});" +
                "  setTimeout(function(){toast.style.opacity='0';setTimeout(function(){toast.remove();},300);},3000);" +
                "};" +
                // Intercept fetch/XHR to detect successful actions and show feedback
                "var origFetch = window.fetch;" +
                "window.fetch = function() {" +
                "  var url = arguments[0];" +
                "  var opts = arguments[1] || {};" +
                "  return origFetch.apply(this, arguments).then(function(response) {" +
                "    if (response.ok && opts.method && opts.method.toUpperCase() !== 'GET') {" +
                "      var urlStr = typeof url === 'string' ? url : url.url;" +
                "      if (urlStr && !urlStr.includes('log-user-in-app') && !urlStr.includes('/analytics')) {" +
                "        window.showRyvenToast('Operazione completata!', 'success');" +
                "      }" +
                "    } else if (!response.ok && opts.method && opts.method.toUpperCase() !== 'GET') {" +
                "      window.showRyvenToast('Errore. Riprova.', 'error');" +
                "    }" +
                "    return response;" +
                "  }).catch(function(err) {" +
                "    if (opts.method && opts.method.toUpperCase() !== 'GET') {" +
                "      window.showRyvenToast('Errore di rete.', 'error');" +
                "    }" +
                "    throw err;" +
                "  });" +
                "};" +
                // Loading indicator for image uploads
                "var origXHR = XMLHttpRequest.prototype.open;" +
                "XMLHttpRequest.prototype.open = function(method) {" +
                "  if (method.toUpperCase() === 'POST' || method.toUpperCase() === 'PUT') {" +
                "    this.addEventListener('loadstart', function() {" +
                "      window.showRyvenToast('Caricamento in corso...', 'info');" +
                "    });" +
                "    this.addEventListener('load', function() {" +
                "      if (this.status >= 200 && this.status < 300) {" +
                "        window.showRyvenToast('Caricamento completato!', 'success');" +
                "      }" +
                "    });" +
                "    this.addEventListener('error', function() {" +
                "      window.showRyvenToast('Errore nel caricamento.', 'error');" +
                "    });" +
                "  }" +
                "  return origXHR.apply(this, arguments);" +
                "};" +
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

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
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.ConsoleMessage;
import android.webkit.WebViewClient;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.splashscreen.SplashScreen;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String APP_URL = "https://app.ryven.it";

    private WebView webView;
    private LinearLayout errorView;
    private ProgressBar progressBar;

    private ValueCallback<Uri[]> fileUploadCallback;
    private String cameraPhotoPath;
    private boolean isPageLoaded = false;
    private String cachedUserAgent = null;

    // Polyfill script injected into <head> BEFORE any other scripts.
    // Stubs OneSignal completely to prevent SDK from loading and crashing.
    // The OneSignal SDK causes unhandled errors in WebView that corrupt
    // React's synthetic event delegation, breaking all button clicks.
    private static final String EARLY_POLYFILL_SCRIPT =
            "<script>" +
            // Stub OneSignal BEFORE the app checks `if (window.OneSignal)`.
            // The app's cie() function checks this and skips SDK loading if present.
            "window.OneSignal={" +
            "init:function(){return Promise.resolve();}," +
            "Notifications:{" +
            "requestPermission:function(){return Promise.resolve('denied');}," +
            "permission:false," +
            "addEventListener:function(){}" +
            "}," +
            "login:function(){}," +
            "logout:function(){}," +
            "setExternalUserId:function(){return Promise.resolve();}," +
            "getExternalUserId:function(){return Promise.resolve(null);}," +
            "User:{addTag:function(){},addTags:function(){},removeTag:function(){},removeTags:function(){}}" +
            "};" +
            // Notification API polyfill as backup
            "if(!window.Notification){" +
            "window.Notification=function(t,o){};Notification.permission='denied';" +
            "Notification.requestPermission=function(c){var p=Promise.resolve('denied');if(c)c('denied');return p};" +
            "Notification.maxActions=0;" +
            "}" +
            // Ensure navigator.permissions doesn't crash
            "if(window.navigator&&!navigator.permissions){" +
            "navigator.permissions={query:function(d){return Promise.resolve({state:'denied',onchange:null})}};" +
            "}" +
            "console.log('[Ryven] WebView polyfills injected - OneSignal stubbed');" +
            "</script>";

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
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(ContextCompat.getColor(this, android.R.color.transparent));

        webView = findViewById(R.id.webView);
        errorView = findViewById(R.id.errorView);
        progressBar = findViewById(R.id.progressBar);
        MaterialButton retryButton = findViewById(R.id.retryButton);

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

        // Core
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setDatabasePath(getApplicationContext().getDir("databases", MODE_PRIVATE).getPath());

        // Cache
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        // Viewport - let the web page control its own viewport
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);

        // Media & images
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // Geolocation
        settings.setGeolocationEnabled(true);

        // Popups
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);

        // Keep default WebView user agent - don't modify it
        // Base44 may need to detect WebView for proper functionality
        cachedUserAgent = settings.getUserAgentString();

        // Cookies
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);
        cookieManager.flush();

        // Debug
        WebView.setWebContentsDebuggingEnabled(true);

        // JS bridge
        webView.addJavascriptInterface(new WebAppInterface(), "RyvenNative");

        // WebView client
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (!isPageLoaded) {
                    progressBar.setVisibility(View.VISIBLE);
                }
                // Also inject polyfill here as a safety net (in case shouldInterceptRequest didn't run)
                injectNotificationPolyfill(view);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();

                // Block OneSignal SDK from loading entirely - it causes unhandled
                // errors in WebView that corrupt React's event system
                if (url.contains("cdn.onesignal.com") || url.contains("OneSignalSDK")) {
                    Log.d("RyvenWebView", "Blocked OneSignal: " + url);
                    // Return empty JS to prevent network error
                    String emptyJs = "/* OneSignal blocked in WebView */";
                    return new WebResourceResponse("application/javascript", "UTF-8",
                            new ByteArrayInputStream(emptyJs.getBytes(StandardCharsets.UTF_8)));
                }

                // Intercept the main page HTML to inject polyfills before ANY scripts execute
                if (request.isForMainFrame() && url.startsWith(APP_URL)) {
                    try {
                        return fetchAndInjectPolyfill(url, request);
                    } catch (Exception e) {
                        Log.e("RyvenWebView", "Intercept failed: " + e.getMessage());
                    }
                }

                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                isPageLoaded = true;
                progressBar.setVisibility(View.GONE);
                hideError();
                injectErrorLogger();
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

                // Keep ALL http/https URLs inside the WebView by default
                // Only open special schemes (tel:, mailto:, intent:, whatsapp:) externally
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    // Only open truly external links in browser
                    if (url.contains("wa.me") || url.contains("whatsapp.com")) {
                        openExternal(url);
                        return true;
                    }
                    return false; // Load ALL web URLs in WebView
                }

                // Non-http schemes (tel:, mailto:, intent:, etc.)
                openExternal(url);
                return true;
            }
        });

        // Chrome client - JS dialogs, file uploads, permissions
        webView.setWebChromeClient(new WebChromeClient() {

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

            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                String msg = consoleMessage.message();
                String src = consoleMessage.sourceId();
                int line = consoleMessage.lineNumber();
                Log.d("RyvenWebView", consoleMessage.messageLevel() + ": " + msg + " [" + src + ":" + line + "]");
                return super.onConsoleMessage(consoleMessage);
            }
        });
    }

    private void injectNotificationPolyfill(WebView view) {
        // Safety net: stub OneSignal + Notification API via evaluateJavascript
        // (in case shouldInterceptRequest HTML injection didn't run)
        String js = "(function() {" +
                "if (!window.OneSignal || !window.OneSignal.init) {" +
                "  window.OneSignal = {" +
                "    init: function(){return Promise.resolve();}," +
                "    Notifications: {requestPermission:function(){return Promise.resolve('denied');},permission:false,addEventListener:function(){}}," +
                "    login:function(){},logout:function(){}," +
                "    setExternalUserId:function(){return Promise.resolve();}," +
                "    getExternalUserId:function(){return Promise.resolve(null);}," +
                "    User:{addTag:function(){},addTags:function(){},removeTag:function(){},removeTags:function(){}}" +
                "  };" +
                "}" +
                "if (!window.Notification) {" +
                "  window.Notification = function(t,o){};" +
                "  Notification.permission = 'denied';" +
                "  Notification.requestPermission = function(c){var p=Promise.resolve('denied');if(c)c('denied');return p;};" +
                "}" +
                "})();";
        view.evaluateJavascript(js, null);
    }

    private WebResourceResponse fetchAndInjectPolyfill(String url, WebResourceRequest request) throws Exception {
        URL pageUrl = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) pageUrl.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);

        // Forward original request headers
        if (cachedUserAgent != null) {
            conn.setRequestProperty("User-Agent", cachedUserAgent);
        }
        Map<String, String> headers = request.getRequestHeaders();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (!entry.getKey().equalsIgnoreCase("User-Agent") || cachedUserAgent == null) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            conn.disconnect();
            return null; // Let WebView handle non-200 responses normally
        }

        // Read HTML
        InputStream is = conn.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        reader.close();
        conn.disconnect();

        String html = sb.toString();

        // Inject polyfill script right after <head> (before any other scripts)
        int headIdx = html.indexOf("<head>");
        if (headIdx >= 0) {
            html = html.substring(0, headIdx + 6) + EARLY_POLYFILL_SCRIPT + html.substring(headIdx + 6);
        } else {
            // No <head> tag found, try injecting at the start of <html>
            int htmlIdx = html.indexOf("<html");
            if (htmlIdx >= 0) {
                int closeTag = html.indexOf(">", htmlIdx);
                if (closeTag >= 0) {
                    html = html.substring(0, closeTag + 1) + "<head>" + EARLY_POLYFILL_SCRIPT + "</head>" + html.substring(closeTag + 1);
                }
            }
        }

        byte[] data = html.getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream bis = new ByteArrayInputStream(data);

        Map<String, String> responseHeaders = new HashMap<>();
        responseHeaders.put("Access-Control-Allow-Origin", "*");
        responseHeaders.put("Cache-Control", "no-cache");

        return new WebResourceResponse("text/html", "UTF-8", 200, "OK", responseHeaders, bis);
    }

    private void injectErrorLogger() {
        // Inject a small floating error log panel for debugging
        // Client can screenshot this to show us exact JS errors
        String js = "(function() {" +
                "if (window.__ryvenDebug) return;" +
                "window.__ryvenDebug = true;" +
                // Create debug panel (hidden by default, triple-tap to show)
                "var panel = document.createElement('div');" +
                "panel.id = 'ryven-debug';" +
                "panel.style.cssText = 'position:fixed;top:0;left:0;right:0;max-height:40vh;overflow:auto;background:rgba(0,0,0,0.85);color:#0f0;font:11px monospace;padding:8px;z-index:999999;display:none;';" +
                "document.body.appendChild(panel);" +
                "var logs = [];" +
                "function addLog(type, msg) {" +
                "  logs.push('[' + type + '] ' + msg);" +
                "  if (logs.length > 80) logs.shift();" +
                "  panel.innerHTML = '<b>Debug Log (tap to close)</b><br>' + logs.join('<br>');" +
                "}" +
                // Intercept fetch to log API errors with full URL
                "var origFetch = window.fetch;" +
                "window.fetch = function(url, opts) {" +
                "  var method = (opts && opts.method) || 'GET';" +
                "  var reqUrl = typeof url === 'string' ? url : (url.url || '');" +
                "  return origFetch.apply(this, arguments).then(function(resp) {" +
                "    if (resp.status >= 400) {" +
                "      resp.clone().text().then(function(body) {" +
                "        addLog('FETCH ' + resp.status, method + ' ' + reqUrl.substring(0, 150) + ' -> ' + body.substring(0, 200));" +
                "      }).catch(function() {});" +
                "    }" +
                "    return resp;" +
                "  }).catch(function(err) {" +
                "    addLog('FETCH ERR', method + ' ' + reqUrl.substring(0, 150) + ' -> ' + err.message);" +
                "    throw err;" +
                "  });" +
                "};" +
                // Intercept XMLHttpRequest to log API errors with full URL
                "var origOpen = XMLHttpRequest.prototype.open;" +
                "var origSend = XMLHttpRequest.prototype.send;" +
                "XMLHttpRequest.prototype.open = function(method, url) {" +
                "  this._ryMethod = method;" +
                "  this._ryUrl = url;" +
                "  return origOpen.apply(this, arguments);" +
                "};" +
                "XMLHttpRequest.prototype.send = function() {" +
                "  var self = this;" +
                "  this.addEventListener('load', function() {" +
                "    if (self.status >= 400) {" +
                "      addLog('XHR ' + self.status, self._ryMethod + ' ' + (self._ryUrl||'').substring(0, 150) + ' -> ' + (self.responseText||'').substring(0, 200));" +
                "    }" +
                "  });" +
                "  this.addEventListener('error', function() {" +
                "    addLog('XHR ERR', self._ryMethod + ' ' + (self._ryUrl||'').substring(0, 150));" +
                "  });" +
                "  return origSend.apply(this, arguments);" +
                "};" +
                // Capture errors
                "window.addEventListener('error', function(e) {" +
                "  addLog('ERR', e.message + ' at ' + (e.filename||'') + ':' + (e.lineno||''));" +
                "});" +
                "window.addEventListener('unhandledrejection', function(e) {" +
                "  addLog('PROMISE', e.reason ? (e.reason.message || String(e.reason)) : 'unknown');" +
                "});" +
                // Capture console.error
                "var origErr = console.error;" +
                "console.error = function() {" +
                "  addLog('console.error', Array.from(arguments).join(' '));" +
                "  origErr.apply(console, arguments);" +
                "};" +
                // Log user agent for diagnostics
                "addLog('UA', navigator.userAgent);" +
                // Log WebView detection + Notification polyfill status
                "addLog('INFO', 'wv=' + (navigator.userAgent.includes('wv')) + " +
                "  ' sw=' + ('serviceWorker' in navigator) + " +
                "  ' notif=' + ('Notification' in window) + " +
                "  ' notifPerm=' + (window.Notification ? Notification.permission : 'N/A') + " +
                "  ' idb=' + (typeof indexedDB !== 'undefined'));" +
                // Triple-tap top-left corner to toggle panel
                "var tapCount = 0, tapTimer;" +
                "document.addEventListener('click', function(e) {" +
                "  if (e.clientX < 60 && e.clientY < 60) {" +
                "    tapCount++;" +
                "    clearTimeout(tapTimer);" +
                "    tapTimer = setTimeout(function() { tapCount = 0; }, 500);" +
                "    if (tapCount >= 3) {" +
                "      panel.style.display = panel.style.display === 'none' ? 'block' : 'none';" +
                "      tapCount = 0;" +
                "    }" +
                "  }" +
                "});" +
                "panel.addEventListener('click', function() { panel.style.display = 'none'; });" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    private void openExternal(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            // Ignore
        }
    }

    private void openFileChooser(WebChromeClient.FileChooserParams params) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        String[] acceptTypes = params.getAcceptTypes();
        if (acceptTypes != null && acceptTypes.length > 0 && acceptTypes[0] != null && !acceptTypes[0].isEmpty()) {
            intent.setType(acceptTypes[0]);
            if (acceptTypes[0].startsWith("image")) {
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

    public class WebAppInterface {
        @JavascriptInterface
        public void showToast(String message) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        }
    }
}

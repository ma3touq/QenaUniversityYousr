package eg.qu.edu.yousr;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final String HOME_URL = "https://mispg.qu.edu.eg/yousr/";
    private static final String HOME_HOST = "mispg.qu.edu.eg";
    private static final int FILE_CHOOSER_REQUEST = 1001;

    // أقل إصدار WebView نعتبره مناسب لمواقع الويب الحديثة
    private static final int MIN_WEBVIEW_MAJOR = 80;
    // بعد كام وقت نفحص هل الصفحة فاضية (بيضا)
    private static final long BLANK_CHECK_DELAY_MS = 7000;

    private FrameLayout root;
    private WebView webView;
    private ProgressBar progressBar;
    private LinearLayout fallbackView;
    private TextView fallbackText;
    private Button btnUpdate;
    private Button btnContinue;

    private ValueCallback<Uri[]> filePathCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int jsSyntaxErrors = 0;
    private boolean renderGone = false;
    private String webViewPackage = "com.google.android.webview";
    private int webViewMajor = -1;

    // ---------------------------------------------------------------
    // onCreate
    // ---------------------------------------------------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#145A43"));
        getWindow().setNavigationBarColor(Color.BLACK);

        // تفعيل الفحص عبر chrome://inspect في نسخة الـ debug فقط
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        readWebViewInfo();

        root = new FrameLayout(this);
        try {
            webView = new WebView(this);
        } catch (Exception e) {
            // WebView غير متاح/معطل على الجهاز
            setContentView(root);
            buildFallbackView();
            showFallback("مكوّن عرض الصفحات (Android System WebView) غير متاح أو معطّل على جهازك.", true, false);
            return;
        }

        // ملحوظة: تم حذف setLayerType(SOFTWARE) لأنه بيبطّئ العرض ومش بيحل المشكلة

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgressTintList(
                android.content.res.ColorStateList.valueOf(Color.parseColor("#145A43")));

        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(progressBar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(3)));
        buildFallbackView();
        setContentView(root);

        setupWebSettings();
        setupClients();
        setupDownloads();

        // لو إصدار WebView قديم نحذّر المستخدم (ونكمل التحميل برضه)
        if (webViewMajor > 0 && webViewMajor < MIN_WEBVIEW_MAJOR) {
            showFallback("إصدار Android System WebView على جهازك قديم (" + webViewMajor
                    + ").\nده غالباً سبب ظهور شاشة بيضاء.\nحدّثه من متجر Play ثم أعد فتح التطبيق، أو افتح الموقع في Chrome.",
                    true, true);
        }

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(HOME_URL);
        }

        // فحص احتياطي: لو بعد 15 ثانية الصفحة لسه فاضية
        handler.postDelayed(blankCheck, 15000);
    }

    // ---------------------------------------------------------------
    // إعدادات الـ WebView
    // ---------------------------------------------------------------
    private void setupWebSettings() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        // كان NEVER_ALLOW وده بيحجب أي ملف http داخل الصفحة؛ COMPATIBILITY أأمن للتوافق
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);
    }

    private void setupClients() {
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl().toString());
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                hideFallback();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
                handler.removeCallbacks(blankCheck);
                handler.postDelayed(blankCheck, BLANK_CHECK_DELAY_MS);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    showFallback("تعذّر تحميل الصفحة.\n(" + error.getDescription()
                            + " - كود " + error.getErrorCode() + ")\nتأكد من الإنترنت وأعد المحاولة.",
                            false, false);
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                if (request.isForMainFrame() && errorResponse.getStatusCode() >= 500) {
                    showFallback("الخادم رجّع خطأ (" + errorResponse.getStatusCode()
                            + "). حاول مرة أخرى بعد قليل.", false, false);
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // لا نتجاوز أخطاء الشهادة أبداً (أمان بيانات الطلاب)
                handler.cancel();
                showFallback("تعذّر التحقق من شهادة الأمان للموقع (SSL: " + error.getPrimaryError()
                        + ").\nتأكد أن تاريخ ووقت الجهاز صحيحين، أو افتح الموقع في Chrome.",
                        false, false);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // الـ WebView انهار (غالباً نقص ذاكرة) - نمنع انهيار التطبيق كله
                renderGone = true;
                try {
                    root.removeView(webView);
                    webView.destroy();
                } catch (Exception ignored) { }
                webView = null;
                showFallback("توقف عرض الصفحة بسبب نقص الذاكرة أو مشكلة في WebView.", true, false);
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    String msg = m.message() == null ? "" : m.message();
                    if (msg.contains("SyntaxError") || msg.contains("Unexpected token")
                            || msg.contains("Unexpected identifier")) {
                        jsSyntaxErrors++;
                    }
                    android.util.Log.e("WEB", msg + " @" + m.sourceId() + ":" + m.lineNumber());
                }
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST);
                    return true;
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "تعذر فتح مدير الملفات", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });
    }

    private void setupDownloads() {
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);
                String cookiesHeader = CookieManager.getInstance().getCookie(url);
                if (cookiesHeader != null) request.addRequestHeader("cookie", cookiesHeader);
                request.addRequestHeader("User-Agent", userAgent);
                String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                request.setTitle(fileName);
                request.setDescription("جاري تنزيل الملف");
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(request);
                Toast.makeText(this, "بدأ تنزيل الملف", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "تعذر تنزيل الملف", Toast.LENGTH_LONG).show();
            }
        });
    }

    // ---------------------------------------------------------------
    // فحص الصفحة الفاضية
    // ---------------------------------------------------------------
    private final Runnable blankCheck = new Runnable() {
        @Override
        public void run() {
            if (webView == null || fallbackView.getVisibility() == View.VISIBLE) return;
            String url = webView.getUrl();
            if (url == null || !url.startsWith("http")) return;

            String js = "(function(){var b=document.body;if(!b)return '0|0';"
                    + "return (b.innerText||'').trim().length+'|'"
                    + "+b.querySelectorAll('img,input,button,a,canvas,svg,iframe,table').length;})()";
            webView.evaluateJavascript(js, value -> {
                if (value == null || webView == null) return;
                String[] p = value.replace("\"", "").split("\\|");
                if (p.length == 2 && p[0].equals("0") && p[1].equals("0")) {
                    boolean oldWv = webViewMajor > 0 && webViewMajor < MIN_WEBVIEW_MAJOR;
                    String hint = (jsSyntaxErrors > 0 || oldWv)
                            ? "السبب المرجّح: إصدار WebView قديم لا يدعم كود الموقع."
                            : "ممكن يكون الإنترنت بطيء أو الصفحة لم تُحمّل.";
                    showFallback("الصفحة ظهرت فارغة.\n" + hint, true, true);
                }
            });
        }
    };

    // ---------------------------------------------------------------
    // شاشة البدائل (بدل الشاشة البيضاء)
    // ---------------------------------------------------------------
    private void buildFallbackView() {
        fallbackView = new LinearLayout(this);
        fallbackView.setOrientation(LinearLayout.VERTICAL);
        fallbackView.setGravity(Gravity.CENTER);
        fallbackView.setBackgroundColor(Color.WHITE);
        fallbackView.setPadding(dp(24), dp(24), dp(24), dp(24));
        fallbackView.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        fallbackView.setClickable(true);
        fallbackView.setVisibility(View.GONE);

        fallbackText = new TextView(this);
        fallbackText.setTextSize(16);
        fallbackText.setTextColor(Color.parseColor("#222222"));
        fallbackText.setGravity(Gravity.CENTER);
        fallbackText.setPadding(0, 0, 0, dp(20));
        fallbackView.addView(fallbackText);

        fallbackView.addView(makeButton("إعادة المحاولة", v -> retry()));
        fallbackView.addView(makeButton("فتح الموقع في Chrome", v -> openInChrome()));
        btnUpdate = makeButton("تحديث WebView من متجر Play", v -> openWebViewInPlayStore());
        fallbackView.addView(btnUpdate);
        btnContinue = makeButton("متابعة الانتظار", v -> hideFallback());
        fallbackView.addView(btnContinue);

        root.addView(fallbackView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private Button makeButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    private void showFallback(String message, boolean offerUpdate, boolean offerContinue) {
        if (fallbackView == null) return;
        fallbackText.setText(message);
        btnUpdate.setVisibility(offerUpdate ? View.VISIBLE : View.GONE);
        btnContinue.setVisibility(offerContinue ? View.VISIBLE : View.GONE);
        fallbackView.setVisibility(View.VISIBLE);
        if (progressBar != null) progressBar.setVisibility(View.GONE);
    }

    private void hideFallback() {
        if (fallbackView != null) fallbackView.setVisibility(View.GONE);
    }

    private void retry() {
        if (renderGone || webView == null) {
            recreate();
            return;
        }
        hideFallback();
        webView.loadUrl(HOME_URL);
    }

    // ---------------------------------------------------------------
    // أدوات مساعدة
    // ---------------------------------------------------------------
    private void readWebViewInfo() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                PackageInfo pi = WebView.getCurrentWebViewPackage();
                if (pi != null) {
                    if (pi.packageName != null) webViewPackage = pi.packageName;
                    if (pi.versionName != null) {
                        webViewMajor = Integer.parseInt(pi.versionName.split("\\.")[0]);
                    }
                }
            }
        } catch (Exception ignored) { }
    }

    private void openInChrome() {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(HOME_URL));
        i.setPackage("com.android.chrome");
        try {
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(HOME_URL)));
            } catch (Exception e2) {
                Toast.makeText(this, "لا يوجد متصفح لفتح الرابط", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openWebViewInPlayStore() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + webViewPackage)));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + webViewPackage)));
            } catch (Exception e2) {
                Toast.makeText(this, "تعذر فتح متجر Play", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private boolean handleUrl(String url) {
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme();
        if (scheme.equals("http") || scheme.equals("https")) {
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            if (host.equals(HOME_HOST) || host.endsWith(".qu.edu.eg")) return false;
            openExternal(uri);
            return true;
        }
        if (scheme.equals("tel") || scheme.equals("mailto")) {
            openExternal(uri);
            return true;
        }
        return true;
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            Toast.makeText(this, "لا يوجد تطبيق مناسب لفتح الرابط", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && filePathCallback != null) {
            Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (fallbackView != null && fallbackView.getVisibility() == View.VISIBLE
                && webView != null && webView.canGoBack()) {
            hideFallback();
            webView.goBack();
        } else if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            new AlertDialog.Builder(this)
                    .setMessage("هل تريد الخروج من التطبيق؟")
                    .setNegativeButton("إلغاء", (d, w) -> d.dismiss())
                    .setPositiveButton("خروج", (d, w) -> finish())
                    .show();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null && !renderGone) webView.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            try {
                root.removeView(webView);
                webView.destroy();
            } catch (Exception ignored) { }
            webView = null;
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

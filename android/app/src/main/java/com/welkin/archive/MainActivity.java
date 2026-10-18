package com.welkin.archive;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
/**
 * Single-activity WebView shell for the Welkin theme.
 *
 * <p>The static Hexo output lives in {@code assets/www} and is served over a
 * loopback HTTP origin so that ES modules, import maps and WebGL all behave
 * exactly like the hosted site. Native touches: a light vibration on every tap,
 * hardware/touch back navigation inside the site, and external links handed to
 * the system browser.</p>
 */
public class MainActivity extends Activity {

    private static final int BACKGROUND = 0xFFEAE5E1;
    private static final int INK = 0xFF2B2724;
    private static final int MUTED = 0xFF6B625A;
    private static final String INJECTION =
            "(function(){" +
            "  if (window.__welkinNative) return;" +
            "  window.__welkinNative = true;" +
            "  document.documentElement.classList.add('in-app');" +
            "  var style = document.createElement('style');" +
            "  style.textContent = '.in-app{-webkit-tap-highlight-color:transparent}' +" +
            "    '.in-app body{overscroll-behavior-y:none}' +" +
            "    '.in-app ::selection{background:rgba(198,124,74,.22)}';" +
            "  document.head.appendChild(style);" +
            "  var last = 0;" +
            "  document.addEventListener('pointerdown', function(event){" +
            "    var now = Date.now();" +
            "    if (now - last < 45) return;" +
            "    last = now;" +
            "    try { WelkinNative.tap(); } catch (error) {}" +
            "  }, {capture:true, passive:true});" +
            "})();";

    private FrameLayout root;
    private WebView webView;
    private LocalServer server;
    private Vibrator vibrator;
    private View failurePanel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(BACKGROUND);
        window.setNavigationBarColor(BACKGROUND);
        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null && !vibrator.hasVibrator()) {
            vibrator = null;
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(BACKGROUND);

        webView = new WebView(this);
        webView.setBackgroundColor(BACKGROUND);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        configure(webView.getSettings());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return intercept(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(INJECTION, null);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    showFailure("页面加载失败：" + describe(error));
                }
            }
        });
        webView.addJavascriptInterface(new NativeBridge(), "WelkinNative");
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        startSite();
    }

    /** Boot the loopback server and point the WebView at it. Safe to call again. */
    private void startSite() {
        if (failurePanel != null) {
            root.removeView(failurePanel);
            failurePanel = null;
        }
        if (server != null) {
            server.close();
            server = null;
        }
        try {
            server = new LocalServer(getAssets());
            server.start();
            webView.loadUrl("http://127.0.0.1:" + server.port() + "/");
        } catch (Exception failure) {
            showFailure("本地站点启动失败：" + failure.getClass().getSimpleName()
                    + ": " + failure.getMessage());
        }
    }

    private void showFailure(String message) {
        if (failurePanel != null || root == null) {
            return;
        }
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = dp(28);
        panel.setPadding(side, dp(110), side, side);
        panel.setBackgroundColor(BACKGROUND);

        TextView title = new TextView(this);
        title.setText("无法打开离线站点");
        title.setTextSize(20f);
        title.setTextColor(INK);

        TextView detail = new TextView(this);
        detail.setText(message + "\n\n站点资源内置于安装包，通常重新尝试即可恢复；"
                + "若仍失败，请卸载本应用后安装最新的安装包。");
        detail.setTextSize(13f);
        detail.setLineSpacing(dp(4), 1f);
        detail.setTextColor(MUTED);
        detail.setPadding(0, dp(16), 0, dp(24));

        Button retry = new Button(this);
        retry.setText("重新尝试");
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startSite();
            }
        });

        panel.addView(title);
        panel.addView(detail);
        panel.addView(retry);
        failurePanel = panel;
        root.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private static String describe(WebResourceError error) {
        if (error == null) {
            return "未知错误";
        }
        CharSequence description = error.getDescription();
        return (description == null ? "" : description.toString())
                + " (" + error.getErrorCode() + ")";
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void configure(WebSettings settings) {
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // Keep the theme's designed type scale instead of the system font size.
        settings.setTextZoom(100);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            settings.setForceDark(WebSettings.FORCE_DARK_OFF);
        }
    }

    /** Keep the loopback origin inside the app; send anything else to the browser. */
    private boolean intercept(String url) {
        if (url == null) {
            return true;
        }
        if (url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
            // No browser available; stay where we are.
        }
        return true;
    }

    @Override
    public void onBackPressed() {
        if (failurePanel != null) {
            super.onBackPressed();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            // Detach first: destroying an attached WebView logs a warning and can
            // leave the renderer holding the window surface.
            if (webView.getParent() instanceof ViewGroup) {
                ((ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.loadUrl("about:blank");
            webView.removeJavascriptInterface("WelkinNative");
            webView.destroy();
            webView = null;
        }
        if (server != null) {
            server.close();
            server = null;
        }
        super.onDestroy();
    }

    private final class NativeBridge {

        @JavascriptInterface
        public void tap() {
            final Vibrator device = vibrator;
            if (device == null || !device.hasVibrator()) {
                return;
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    device.vibrate(VibrationEffect.createOneShot(8, 70));
                } else {
                    device.vibrate(8);
                }
            } catch (Exception ignored) {
                // Some devices rate-limit vibration; the tap still works.
            }
        }

        @JavascriptInterface
        public String platform() {
            return "android";
        }

        @JavascriptInterface
        public boolean canVibrate() {
            return vibrator != null && vibrator.hasVibrator();
        }
    }
}

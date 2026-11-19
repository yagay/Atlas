package uk.wort.atlas.overlay;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * Browser content is drawn directly in TYPE_APPLICATION_OVERLAY's existing
 * draggable panel. A source requiring JS, sign-in or client-side rendering can
 * therefore be browsed without switching to Atlas or another full-screen app.
 *
 * No Javascript bridge, no inspection of passwords or login forms, no request
 * rewriting or anti-bot bypass. Cookies belong to Android WebView's cookie jar.
 */
final class OverlayWebPanel {
    interface Actions {
        void returnToResults();
        void reparseAfterLogin();
    }

    private final AtlasOverlayService service;
    private final LinearLayout container;
    private final WebView webView;
    private final TextView progress;
    private final String initialUrl;
    private final String sourceId;
    private final String originHost;
    private final Actions actions;

    OverlayWebPanel(AtlasOverlayService service, String sourceId, String sourceName,
                    String initialUrl, int heightPx, Actions actions) {
        this.service = service;
        this.sourceId = sourceId == null ? "" : sourceId;
        this.initialUrl = initialUrl;
        this.originHost = normalHost(Uri.parse(initialUrl).getHost());
        this.actions = actions;

        container = new LinearLayout(service);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(Color.rgb(31, 33, 39));

        LinearLayout controls = new LinearLayout(service);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(dp(3), dp(3), dp(3), dp(3));
        Button back = button("‹ 结果");
        back.setOnClickListener(v -> actions.returnToResults());
        controls.addView(back, params(59, 34, 0));
        Button previous = button("◀");
        controls.addView(previous, params(32, 34, 4));
        Button next = button("▶");
        controls.addView(next, params(32, 34, 3));
        Button refresh = button("↻");
        controls.addView(refresh, params(32, 34, 3));
        Button parse = button("抓取");
        controls.addView(parse, params(49, 34, 3));
        Button external = button("外部 ↗");
        controls.addView(external, params(61, 34, 4));
        container.addView(controls, new LinearLayout.LayoutParams(-1, dp(40)));

        progress = new TextView(service);
        progress.setSingleLine(true);
        progress.setTextSize(11);
        progress.setGravity(Gravity.CENTER_VERTICAL);
        progress.setTextColor(Color.rgb(188, 193, 204));
        progress.setPadding(dp(7), 0, dp(7), 0);
        progress.setText(sourceName + " · 站内网页");
        container.addView(progress, new LinearLayout.LayoutParams(-1, dp(24)));

        webView = new WebView(service);
        webView.setBackgroundColor(Color.WHITE);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        if (!this.sourceId.isEmpty()) {
            service.getSharedPreferences("overlay_source_sessions", Context.MODE_PRIVATE)
                    .edit().putString("ua_" + this.sourceId, settings.getUserAgentString()).apply();
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                String destination = request.getUrl().toString();
                if (isSiteUrl(destination)) return false;
                Uri uri = request.getUrl();
                if ("magnet".equalsIgnoreCase(uri.getScheme())) {
                    openExternal(destination);
                    return true;
                }
                Toast.makeText(service,
                        "当前网页跳转到其他域名或应用，请使用「外部」打开",
                        Toast.LENGTH_LONG).show();
                return true;
            }

            @Override public void onPageFinished(WebView view, String url) {
                progress.setText(sourceName + " · " + safeDisplayHost(url));
                CookieManager.getInstance().flush();
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                                  android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) {
                    progress.setText("网页加载失败 · 可刷新或外部打开");
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int percent) {
                if (percent < 100) progress.setText(sourceName + " · 加载中 " + percent + "%");
            }
        });
        container.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));
        container.setLayoutParams(new LinearLayout.LayoutParams(-1, heightPx));

        previous.setOnClickListener(v -> { if (webView.canGoBack()) webView.goBack(); });
        next.setOnClickListener(v -> { if (webView.canGoForward()) webView.goForward(); });
        refresh.setOnClickListener(v -> webView.reload());
        parse.setOnClickListener(v -> {
            CookieManager.getInstance().flush();
            actions.reparseAfterLogin();
        });
        external.setOnClickListener(v -> openExternal(currentUrl()));

        // The URL comes from Atlas's authenticated source list, but still validate
        // before loading any potentially user-configured source.
        if (isSiteUrl(initialUrl)) webView.loadUrl(initialUrl);
        else progress.setText("来源地址不是受信任的 HTTPS 网页");
    }

    private int dp(float value) {
        return Math.round(service.getResources().getDisplayMetrics().density * value);
    }

    private LinearLayout.LayoutParams params(int width, int height, int left) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(width), dp(height));
        p.leftMargin = dp(left);
        return p;
    }

    private Button button(String text) {
        Button button = new Button(service);
        button.setText(text);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(2), 0, dp(2), 0);
        button.setTextColor(Color.rgb(240, 218, 185));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(52, 55, 63));
        bg.setCornerRadius(dp(8));
        button.setBackground(bg);
        return button;
    }

    private static String normalHost(String host) {
        if (host == null) return "";
        host = host.toLowerCase(Locale.ROOT);
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    private String safeDisplayHost(String url) {
        String host = Uri.parse(url).getHost();
        return host == null ? "" : host;
    }

    private boolean isSiteUrl(String url) {
        if (url == null) return false;
        Uri uri;
        try { uri = Uri.parse(url); }
        catch (Exception invalid) { return false; }
        String host = normalHost(uri.getHost());
        return "https".equalsIgnoreCase(uri.getScheme()) &&
                !originHost.isEmpty() &&
                (host.equals(originHost) || host.endsWith("." + originHost)) &&
                uri.getUserInfo() == null &&
                (uri.getPort() == -1 || uri.getPort() == 443) &&
                !host.equals("localhost") && !host.endsWith(".local") &&
                !host.matches("[0-9.]+") && !host.contains(":");
    }

    private void openExternal(String value) {
        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"magnet".equalsIgnoreCase(scheme)) {
                Toast.makeText(service, "不支持此链接类型", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent view = new Intent(Intent.ACTION_VIEW, uri);
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            service.startActivity(view);
        } catch (ActivityNotFoundException missing) {
            Toast.makeText(service, "没有能打开此链接的应用", Toast.LENGTH_SHORT).show();
        } catch (Exception failure) {
            Toast.makeText(service, "无法打开链接", Toast.LENGTH_SHORT).show();
        }
    }

    View getView() { return container; }

    String currentUrl() {
        String url = webView.getUrl();
        return url == null || url.isEmpty() ? initialUrl : url;
    }

    void destroy() {
        CookieManager.getInstance().flush();
        webView.stopLoading();
        webView.setWebChromeClient(null);
        webView.setWebViewClient(null);
        webView.loadUrl("about:blank");
        container.removeView(webView);
        webView.destroy();
    }
}

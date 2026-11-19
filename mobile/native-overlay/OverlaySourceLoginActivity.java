package uk.wort.atlas.overlay;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
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

/**
 * A temporary, user-initiated sign-in page for one known JAV source.
 *
 * Logins happen on the source's actual website; Atlas never asks for, stores or
 * submits credentials. Cookies remain in Android WebView's per-app cookie jar.
 * No JavaScript bridge, no proxy, no cookie export to Atlas API.
 */
public class OverlaySourceLoginActivity extends Activity {
    public static final String EXTRA_SOURCE_ID = "sourceId";
    public static final String EXTRA_SOURCE_NAME = "sourceName";
    public static final String EXTRA_SOURCE_URL = "sourceUrl";

    private String sourceId, sourceName, sourceUrl;
    private WebView browser;
    private TextView progressText;
    private boolean submittedReturn;

    private int dp(float count) {
        return Math.round(getResources().getDisplayMetrics().density * count);
    }

    private TextView heading(String value, int size) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(Color.WHITE);
        label.setGravity(Gravity.CENTER_VERTICAL);
        return label;
    }

    private Button control(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setTextColor(Color.rgb(245, 217, 179));
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Color.rgb(51, 55, 63)));
        return button;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent launch = getIntent();
        sourceId = launch.getStringExtra(EXTRA_SOURCE_ID);
        sourceName = launch.getStringExtra(EXTRA_SOURCE_NAME);
        sourceUrl = launch.getStringExtra(EXTRA_SOURCE_URL);
        if (sourceName == null || sourceName.isEmpty()) sourceName = "搜索源";
        if (sourceUrl == null ||
                !OverlaySourceClient.isApprovedWebUrl(sourceId, sourceUrl)) {
            Toast.makeText(this, "此站点不支持在 App 内登录", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        getWindow().setStatusBarColor(Color.rgb(28, 30, 36));
        getWindow().setNavigationBarColor(Color.rgb(28, 30, 36));
        getWindow().getDecorView().setSystemUiVisibility(0);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(28, 30, 36));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(7), dp(5), dp(7), dp(5));
        Button back = control("返回");
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(62), dp(43)));
        TextView title = heading(sourceName + " · 站点登录", 15);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, dp(43), 1);
        titleParams.leftMargin = dp(6);
        bar.addView(title, titleParams);
        Button done = control("完成登录");
        done.setOnClickListener(v -> finish());
        bar.addView(done, new LinearLayout.LayoutParams(dp(91), dp(43)));
        root.addView(bar);

        progressText = heading("请在本站正常登录，完成后点右上角「完成登录」", 12);
        progressText.setTextColor(Color.rgb(191, 194, 203));
        progressText.setPadding(dp(14), 0, dp(10), 0);
        root.addView(progressText, new LinearLayout.LayoutParams(-1, dp(36)));

        browser = new WebView(this);
        browser.setBackgroundColor(Color.WHITE);
        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings settings = browser.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        // Store only a non-secret user agent. Site credentials stay with WebView.
        getSharedPreferences("overlay_source_sessions", MODE_PRIVATE).edit()
                .putString("ua_" + sourceId, settings.getUserAgentString()).apply();
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false);

        browser.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                String destination = request.getUrl().toString();
                if (OverlaySourceClient.isApprovedWebUrl(sourceId, destination)) {
                    return false;
                }
                Toast.makeText(OverlaySourceLoginActivity.this,
                        "此站点跳转到外部登录域名，无法在内嵌页面共享登录状态；请使用浏览器登录",
                        Toast.LENGTH_LONG).show();
                return true;
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (progressText != null) {
                    progressText.setText("请登录 " + sourceName + "，完成后点「完成登录」");
                }
            }
        });
        browser.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int progress) {
                if (progressText != null && progress < 100) {
                    progressText.setText(sourceName + " 网页加载 " + progress + "%");
                }
            }
        });
        root.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        AtlasSafeArea.install(this);
        browser.loadUrl(sourceUrl);
    }

    @Override public void onBackPressed() {
        if (browser != null && browser.canGoBack()) {
            browser.goBack();
        } else {
            finish();
        }
    }

    private void returnToOverlay() {
        if (submittedReturn || sourceId == null) return;
        submittedReturn = true;
        CookieManager.getInstance().flush();
        Intent resume = new Intent(this, AtlasOverlayService.class);
        resume.setAction(AtlasOverlayService.ACTION_SITE_LOGIN_FINISHED);
        resume.putExtra(EXTRA_SOURCE_ID, sourceId);
        resume.putExtra(EXTRA_SOURCE_NAME, sourceName);
        resume.putExtra(EXTRA_SOURCE_URL, sourceUrl);
        try { startService(resume); } catch (Exception ignored) {}
    }

    @Override protected void onDestroy() {
        if (isFinishing()) returnToOverlay();
        if (browser != null) {
            browser.stopLoading();
            browser.loadUrl("about:blank");
            browser.setWebChromeClient(null);
            browser.setWebViewClient(null);
            browser.destroy();
            browser = null;
        }
        progressText = null;
        super.onDestroy();
    }
}

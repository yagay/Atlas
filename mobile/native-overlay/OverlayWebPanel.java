package uk.wort.atlas.overlay;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The ONE JAV source viewer for the Atlas main Activity and floating search.
 * Same WebView, cookies, navigation, HTML parser, results, magnet actions.
 * Only the host container differs: main app Activity vs movable overlay window.
 *
 * This is a user-initiated source browser, not an anti-bot bypass. There is no
 * JavaScript bridge, credential capture, or injection into the site.
 */
final class OverlayWebPanel {
    interface Actions {
        void returnToResults();
    }

    private final Context context;
    private final String sourceId;
    private final String sourceName;
    private final String initialUrl;
    private final String originHost;
    private final Actions actions;
    private final LinearLayout container;
    private final LinearLayout parsedItems;
    private final ScrollView parsedScroll;
    private final WebView webView;
    private final TextView status;
    private final ExecutorService jobs = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean disposed = false;
    private boolean webStarted = false;
    private boolean websiteMode = false;
    private int generation = 0;

    OverlayWebPanel(Context context, String sourceId, String sourceName,
                    String url, int heightPx, boolean preferParsed, Actions actions) {
        this.context = context;
        this.sourceId = sourceId == null ? "" : sourceId;
        this.sourceName = sourceName == null ? "搜索源" : sourceName;
        this.initialUrl = url;
        this.originHost = normalizeHost(Uri.parse(url).getHost());
        this.actions = actions;
        if (!isSafeInitialUrl(url)) throw new IllegalArgumentException("Invalid source URL");

        container = column();
        container.setBackgroundColor(Color.rgb(29, 31, 37));

        LinearLayout toolbar = row();
        toolbar.setPadding(dp(2), dp(4), dp(2), dp(4));
        Button returnButton = button("‹ 返回");
        returnButton.setOnClickListener(v -> actions.returnToResults());
        toolbar.addView(returnButton, toolbarButton(51, 0));

        Button back = button("◀");
        toolbar.addView(back, toolbarButton(28, 3));
        Button forward = button("▶");
        toolbar.addView(forward, toolbarButton(28, 3));
        Button refresh = button("↻");
        toolbar.addView(refresh, toolbarButton(28, 3));
        Button parse = button("解析");
        toolbar.addView(parse, toolbarButton(43, 3));
        Button website = button("网页");
        toolbar.addView(website, toolbarButton(43, 3));
        Button external = button("外部 ↗");
        toolbar.addView(external, toolbarButton(59, 3));
        container.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(42)));

        status = label(this.sourceName + " · 统一来源浏览器", 11, 0xffbdc1cc, false);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setPadding(dp(6), 0, dp(6), 0);
        container.addView(status, new LinearLayout.LayoutParams(-1, dp(24)));

        FrameLayout content = new FrameLayout(context);
        webView = new WebView(context);
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
            context.getSharedPreferences("overlay_source_sessions", Context.MODE_PRIVATE)
                    .edit().putString("ua_" + this.sourceId, settings.getUserAgentString()).apply();
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                String target = request.getUrl().toString();
                if (isSiteUrl(target)) return false;
                if ("magnet".equalsIgnoreCase(request.getUrl().getScheme())) {
                    openExternal(target);
                    return true;
                }
                Toast.makeText(context, "此网页跳到了其他域名，需使用外部浏览器打开",
                        Toast.LENGTH_LONG).show();
                return true;
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (disposed) return;
                status.setText(sourceName + " · " + Uri.parse(url).getHost());
                CookieManager.getInstance().flush();
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                                  android.webkit.WebResourceError error) {
                if (!disposed && request.isForMainFrame()) {
                    status.setText("网页无法加载，可重试或外部打开");
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                if (!disposed && websiteMode && value < 100) {
                    status.setText(sourceName + " · 网页加载 " + value + "%");
                }
            }
        });
        content.addView(webView, new FrameLayout.LayoutParams(-1, -1));

        parsedScroll = new ScrollView(context);
        parsedScroll.setFillViewport(false);
        parsedScroll.setVerticalScrollBarEnabled(true);
        parsedScroll.setBackgroundColor(0xff1d1f25);
        parsedItems = column();
        parsedItems.setPadding(dp(7), dp(5), dp(7), dp(14));
        parsedScroll.addView(parsedItems, new ScrollView.LayoutParams(-1, -2));
        content.addView(parsedScroll, new FrameLayout.LayoutParams(-1, -1));
        container.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        container.setLayoutParams(new LinearLayout.LayoutParams(-1, heightPx));

        back.setOnClickListener(v -> {
            if (websiteMode && webView.canGoBack()) webView.goBack();
            else showParsed(initialUrl);
        });
        forward.setOnClickListener(v -> {
            if (websiteMode && webView.canGoForward()) webView.goForward();
        });
        refresh.setOnClickListener(v -> {
            if (websiteMode) webView.reload();
            else showParsed(initialUrl);
        });
        parse.setOnClickListener(v -> {
            CookieManager.getInstance().flush();
            showParsed(currentUrl());
        });
        website.setOnClickListener(v -> showWebsite(currentUrl()));
        external.setOnClickListener(v -> openExternal(currentUrl()));

        if (preferParsed && OverlaySourceClient.supported(this.sourceId) &&
                OverlaySourceClient.isApprovedWebUrl(this.sourceId, url)) {
            showParsed(url);
        } else {
            showWebsite(url);
        }
    }

    static boolean isSafeInitialUrl(String value) {
        try {
            URL url = new URL(value);
            String host = normalizeHost(url.getHost());
            if (!"https".equalsIgnoreCase(url.getProtocol()) ||
                    host.isEmpty() || host.equals("localhost") || host.endsWith(".localhost") ||
                    host.endsWith(".local") || host.endsWith(".internal") ||
                    host.matches("[0-9.]+") || host.contains(":") ||
                    host.indexOf('.') < 0 ||
                    (url.getPort() != -1 && url.getPort() != 443) ||
                    url.getUserInfo() != null) return false;
            return true;
        } catch (Exception e) { return false; }
    }

    private boolean isSiteUrl(String value) {
        if (!isSafeInitialUrl(value)) return false;
        String host = normalizeHost(Uri.parse(value).getHost());
        return host.equals(originHost) || host.endsWith("." + originHost);
    }

    private static String normalizeHost(String host) {
        if (host == null) return "";
        host = host.toLowerCase(Locale.ROOT);
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    private int dp(float value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout.LayoutParams toolbarButton(int width, int marginStart) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(width), dp(34));
        p.leftMargin = dp(marginStart);
        return p;
    }

    private Button button(String value) {
        Button button = new Button(context);
        button.setText(value);
        button.setTextSize(10);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(2), 0, dp(2), 0);
        button.setTextColor(0xfff1d8ae);
        button.setBackground(background(0xff333841));
        return button;
    }

    private GradientDrawable background(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(7));
        return drawable;
    }

    private TextView label(String text, int fontSize, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(fontSize);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private void addNote(String text) {
        TextView note = label(text, 12, 0xffb8bec9, false);
        note.setPadding(dp(4), dp(6), dp(4), dp(6));
        parsedItems.addView(note, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addHeading(String text) {
        TextView heading = label(text, 14, 0xffecc38b, true);
        heading.setPadding(dp(2), dp(9), dp(2), dp(6));
        parsedItems.addView(heading, new LinearLayout.LayoutParams(-1, -2));
    }

    private void viewMode(boolean website) {
        websiteMode = website;
        webView.setVisibility(website ? View.VISIBLE : View.GONE);
        parsedScroll.setVisibility(website ? View.GONE : View.VISIBLE);
    }

    private void showWebsite(String requestedUrl) {
        if (disposed) return;
        String target = isSiteUrl(requestedUrl) ? requestedUrl : initialUrl;
        viewMode(true);
        status.setText(sourceName + " · 网页");
        if (!webStarted || !target.equals(webView.getUrl())) {
            webStarted = true;
            webView.loadUrl(target);
        }
    }

    private void showParsed(String requestedUrl) {
        if (disposed) return;
        String target = isSiteUrl(requestedUrl) ? requestedUrl : initialUrl;
        if (!OverlaySourceClient.supported(sourceId) ||
                !OverlaySourceClient.isApprovedWebUrl(sourceId, target)) {
            showWebsite(target);
            Toast.makeText(context, "此来源暂无原生解析器，已切到网页模式",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final int request = ++generation;
        viewMode(false);
        parsedItems.removeAllViews();
        parsedScroll.scrollTo(0, 0);
        status.setText(sourceName + " · 正在解析");
        addNote("正在读取 " + sourceName + "…");
        jobs.execute(() -> {
            try {
                JSONObject parsed = OverlaySourceClient.preview(context, sourceId, sourceName, target);
                main.post(() -> {
                    if (disposed || request != generation) return;
                    renderParsed(parsed, request);
                });
            } catch (Exception problem) {
                main.post(() -> {
                    if (disposed || request != generation) return;
                    // Invalid, expired or JS-only content is never treated as a real result.
                    Toast.makeText(context, "无法解析，已切换网站原始页面",
                            Toast.LENGTH_SHORT).show();
                    showWebsite(target);
                });
            }
        });
    }

    private void addCover(String url, ImageView image, int request) {
        if (url.isEmpty()) return;
        jobs.execute(() -> {
            Bitmap bitmap = OverlaySourceClient.cover(context, sourceId, url);
            if (bitmap == null) bitmap = OverlaySearchClient.cover(url);
            if (bitmap == null) return;
            final Bitmap picture = bitmap;
            main.post(() -> {
                if (!disposed && request == generation && image.getParent() != null) {
                    image.setImageBitmap(picture);
                }
            });
        });
    }

    private void renderParsed(JSONObject parsed, int request) {
        if (disposed) return;
        parsedItems.removeAllViews();
        status.setText(sourceName + " · 解析结果");
        String title = parsed.optString("title", "");
        if (!title.isEmpty()) addHeading(title.length() > 160 ? title.substring(0, 160) : title);
        JSONArray cards = parsed.optJSONArray("items");
        JSONArray magnets = parsed.optJSONArray("magnets");
        boolean hasItems = cards != null && cards.length() > 0;
        boolean hasMagnets = magnets != null && magnets.length() > 0;

        if (hasItems) {
            addHeading("相关作品 · " + cards.length());
            for (int i = 0; i < Math.min(cards.length(), 20); i++) {
                JSONObject item = cards.optJSONObject(i);
                if (item == null) continue;
                String page = item.optString("url", "");
                if (!isSiteUrl(page)) continue;
                String code = item.optString("code", "");
                String itemTitle = item.optString("title", "");
                LinearLayout entry = row();
                entry.setPadding(dp(6), dp(6), dp(6), dp(6));
                entry.setBackground(background(0xff2b3039));
                String coverUrl = item.optString("cover", "");
                if (!coverUrl.isEmpty() && i < 9) {
                    ImageView cover = new ImageView(context);
                    cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    entry.addView(cover, new LinearLayout.LayoutParams(dp(52), dp(72)));
                    addCover(coverUrl, cover, request);
                }
                LinearLayout descriptions = column();
                descriptions.setPadding(dp(7), 0, dp(5), 0);
                if (!code.isEmpty()) descriptions.addView(label(code, 12, 0xffffc886, true));
                TextView name = label(itemTitle, 12, Color.WHITE, false);
                name.setMaxLines(3);
                name.setEllipsize(TextUtils.TruncateAt.END);
                descriptions.addView(name);
                entry.addView(descriptions, new LinearLayout.LayoutParams(0, -2, 1));
                Button detail = button("详情 ›");
                detail.setOnClickListener(v -> showParsed(page));
                entry.addView(detail, new LinearLayout.LayoutParams(dp(58), dp(35)));
                LinearLayout.LayoutParams rowP = new LinearLayout.LayoutParams(-1, -2);
                rowP.topMargin = dp(5);
                parsedItems.addView(entry, rowP);
            }
        }

        if (hasMagnets) {
            addHeading("磁力资源 · " + magnets.length());
            for (int i = 0; i < Math.min(18, magnets.length()); i++) {
                JSONObject item = magnets.optJSONObject(i);
                if (item == null) continue;
                String magnet = item.optString("magnet", "");
                if (!magnet.startsWith("magnet:?")) continue;
                LinearLayout row = row();
                row.setPadding(dp(4), dp(5), dp(4), dp(5));
                TextView name = label(item.optString("name", "磁力链接"), 11, Color.WHITE, false);
                name.setMaxLines(2);
                row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                Button open = button("打开");
                open.setOnClickListener(v -> openExternal(magnet));
                row.addView(open, new LinearLayout.LayoutParams(dp(48), dp(34)));
                Button copy = button("复制");
                copy.setOnClickListener(v -> {
                    ClipboardManager clipboard =
                            (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("JAV magnet", magnet));
                        Toast.makeText(context, "磁力已复制", Toast.LENGTH_SHORT).show();
                    }
                });
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(48), dp(34));
                cp.leftMargin = dp(5);
                row.addView(copy, cp);
                parsedItems.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
        }

        String nextUrl = parsed.optString("nextUrl", "");
        if (!nextUrl.isEmpty() && isSiteUrl(nextUrl)) {
            Button more = button("加载下一页");
            more.setOnClickListener(v -> showParsed(nextUrl));
            LinearLayout.LayoutParams moreP = new LinearLayout.LayoutParams(-1, dp(40));
            moreP.topMargin = dp(9);
            parsedItems.addView(more, moreP);
        }

        if (!hasItems && !hasMagnets) {
            addNote("已读取页面，但未发现可解析的作品或磁力。可能需要登录，或由网页脚本动态加载。");
            Button website = button("打开本站原始网页 / 登录");
            website.setOnClickListener(v -> showWebsite(parsed.optString("url", initialUrl)));
            parsedItems.addView(website, new LinearLayout.LayoutParams(-1, dp(42)));
        }
    }

    private void openExternal(String value) {
        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"magnet".equalsIgnoreCase(scheme)) {
                Toast.makeText(context, "不支持此链接", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (ActivityNotFoundException missing) {
            Toast.makeText(context, "未安装能打开该链接的应用", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(context, "无法打开该链接", Toast.LENGTH_SHORT).show();
        }
    }

    View getView() { return container; }

    String currentUrl() {
        String url = webStarted ? webView.getUrl() : null;
        return url == null || url.isEmpty() ? initialUrl : url;
    }

    /** Used by the Android back button in the main-app Activity. */
    boolean goBackInSite() {
        if (websiteMode && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        if (websiteMode && OverlaySourceClient.supported(sourceId)) {
            showParsed(initialUrl);
            return true;
        }
        return false;
    }

    void destroy() {
        disposed = true;
        generation++;
        jobs.shutdownNow();
        CookieManager.getInstance().flush();
        webView.stopLoading();
        webView.setWebChromeClient(null);
        webView.setWebViewClient(null);
        webView.loadUrl("about:blank");
        android.view.ViewGroup parent = (android.view.ViewGroup) webView.getParent();
        if (parent != null) parent.removeView(webView);
        webView.destroy();
    }
}

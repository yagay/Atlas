package uk.wort.atlas.overlay;

import android.graphics.Bitmap;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.text.Html;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native HTML -> structured source preview. No script execution / embedded browser.
 * The source comes from the authenticated Atlas /api/search response.
 *
 * Only known public JAV source hosts are eligible; never fetch an arbitrary
 * address supplied by the user or a third party. Private-network resolution,
 * redirects to unapproved hosts, large responses and HTTP are blocked.
 */
final class OverlaySourceClient {
    private static final int MAX_HTML_BYTES = 1_500_000;
    private static final int MAX_IMAGE_BYTES = 2_500_000;
    private static final int MAX_ITEMS = 20;
    private static final int MAX_MAGNETS = 30;
    private static final Map<String,String> SOURCES = new HashMap<>();

    static {
        SOURCES.put("javbus", "javbus.com");
        SOURCES.put("javdb", "javdb.com");
        SOURCES.put("javlibrary", "javlibrary.com");
        SOURCES.put("jable", "jable.tv");
        SOURCES.put("javmost", "javmost.com");
        SOURCES.put("javguru", "jav.guru");
        SOURCES.put("av01", "av01.tv");
        SOURCES.put("missav", "missav.ws");
        SOURCES.put("supjav", "supjav.com");
        SOURCES.put("bestjavporn", "bestjavporn.com");
        SOURCES.put("javsb", "jav.sb");
        SOURCES.put("javleak", "javleak.com");
        SOURCES.put("javseen", "javseen.tv");
        SOURCES.put("javtsunami", "javtsunami.com");
        SOURCES.put("javsubtitled", "javsubtitled.com");
        SOURCES.put("javout", "javout.co");
        SOURCES.put("javgg", "javgg.net");
        SOURCES.put("javcl", "javcl.com");
        SOURCES.put("javdoe", "javdoe.to");
        SOURCES.put("javdesu", "javdesu.tv");
        SOURCES.put("javynow", "javynow.com");
        SOURCES.put("javhdporn2", "javhdporn.net");
        SOURCES.put("javasslove", "javass.love");
        SOURCES.put("javsubtitle", "javsubtitle.xyz");
    }

    private static final Pattern ANCHOR = Pattern.compile(
            "<a\\b([^>]*?)>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern IMAGE = Pattern.compile(
            "<img\\b([^>]*?)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern MAGNET = Pattern.compile(
            "magnet:\\?xt=urn:btih:[0-9a-zA-Z]{32,40}(?:[^\\s\"'<>]*)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE = Pattern.compile(
            "(?:FC2[-_ ]?(?:PPV[-_ ]?)?\\d{4,9}|[A-Z]{2,12}[-_]\\d{2,7})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NEXT_LINK = Pattern.compile(
            "<a\\b([^>]*(?:rel=[\"']next[\"']|class=[\"'][^\"']*next[^\"']*[\"'])[^>]*)>",
            Pattern.CASE_INSENSITIVE);

    private OverlaySourceClient() {}

    static boolean supported(String sourceId) {
        return sourceId != null && SOURCES.containsKey(sourceId.toLowerCase(Locale.ROOT));
    }

    private static String approvedHost(String sourceId) throws Exception {
        if (sourceId == null) throw new Exception("来源未指定");
        String host = SOURCES.get(sourceId.toLowerCase(Locale.ROOT));
        if (host == null) throw new Exception("该来源暂不支持内容抓取，请选择网页打开");
        return host;
    }

    private static boolean hostMatches(String candidate, String trusted) {
        if (candidate == null || trusted == null) return false;
        String host = candidate.toLowerCase(Locale.ROOT);
        return host.equals(trusted) || host.endsWith("." + trusted);
    }

    /**
     * Shared allowlist for external-source fetching and the one-time embedded
     * sign-in Activity. Login pages cannot navigate to an unrelated host.
     */
    static boolean isApprovedWebUrl(String sourceId, String rawUrl) {
        try {
            URL target = new URL(rawUrl);
            return "https".equalsIgnoreCase(target.getProtocol())
                    && hostMatches(target.getHost(), approvedHost(sourceId))
                    && (target.getPort() == -1 || target.getPort() == 443)
                    && target.getUserInfo() == null;
        } catch (Exception invalid) {
            return false;
        }
    }

    /**
     * WebView owns each site's login session; native HttpURLConnection does not.
     * Read cookies only for the vetted destination host and never send them to
     * the Atlas backend or a different search source. HttpOnly values stay with
     * Android's WebView CookieManager rather than app preferences.
     */
    private static void attachSiteCookies(HttpURLConnection connection, URL url) {
        try {
            String cookie = CookieManager.getInstance().getCookie(url.toString());
            if (cookie != null && !cookie.isEmpty() && cookie.length() <= 8192) {
                connection.setRequestProperty("Cookie", cookie);
            }
        } catch (Exception ignored) {
            // No stored session is normal for public search.
        }
    }

    private static URL validated(String sourceId, String raw) throws Exception {
        String allowed = approvedHost(sourceId);
        URL url = new URL(raw);
        if (!"https".equalsIgnoreCase(url.getProtocol()) ||
                !hostMatches(url.getHost(), allowed) ||
                (url.getPort() != -1 && url.getPort() != 443) ||
                url.getUserInfo() != null) {
            throw new Exception("为防止访问非受信地址，此来源链接无法在悬浮窗中抓取");
        }
        // Prevent DNS rebinding to local/private networks on Android.
        for (InetAddress address : InetAddress.getAllByName(url.getHost())) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() ||
                    address.isLinkLocalAddress() || address.isSiteLocalAddress() ||
                    address.isMulticastAddress()) {
                throw new Exception("此来源解析到非公共网络地址，已拒绝请求");
            }
        }
        return url;
    }

    // WebView login cookies may be bound to a User-Agent. Reuse the login page
    // user agent when fetching the same site through HttpURLConnection.
    private static String sourceUserAgent(Context ctx, String sourceId) {
        String value = ctx.getSharedPreferences("overlay_source_sessions", Context.MODE_PRIVATE)
                .getString("ua_" + sourceId, "");
        return value == null || value.isEmpty()
                ? "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
                : value;
    }

    private static final class Page {
        String html, url;
        Page(String body, String address) { html = body; url = address; }
    }

    private static Page loadPage(Context ctx, String sourceId, String address) throws Exception {
        URL target = validated(sourceId, address);
        for (int redirect = 0; redirect < 4; redirect++) {
            HttpURLConnection conn = (HttpURLConnection) target.openConnection();
            try {
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(14000);
                conn.setRequestProperty("User-Agent", sourceUserAgent(ctx, sourceId));
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5");
                conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7");
                attachSiteCookies(conn, target);
                int status = conn.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = conn.getHeaderField("Location");
                    if (location == null) throw new Exception("来源跳转缺少地址");
                    target = validated(sourceId, new URL(target, location).toString());
                    continue;
                }
                if (status == 401 || status == 403 || status == 429 ||
                    status == 503) throw new Exception("来源要求登录、访问验证或暂时限制抓取 (HTTP " + status + ")");
                if (status < 200 || status >= 300) throw new Exception("来源暂时不可用 (HTTP " + status + ")");
                String mime = conn.getContentType();
                if (mime != null && !mime.toLowerCase(Locale.ROOT).contains("html")) {
                    throw new Exception("来源没有返回可解析的 HTML 内容");
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (InputStream input = conn.getInputStream()) {
                    byte[] chunk = new byte[8192];
                    int size;
                    while ((size = input.read(chunk)) != -1) {
                        output.write(chunk, 0, size);
                        if (output.size() > MAX_HTML_BYTES) throw new Exception("来源页面过大，已停止抓取");
                    }
                }
                String charset = "UTF-8";
                if (mime != null && mime.toLowerCase(Locale.ROOT).contains("charset=")) {
                    String specified = mime.replaceFirst("(?is).*charset=([\\w-]+).*", "$1");
                    if (specified.matches("[A-Za-z0-9_-]{2,32}")) charset = specified;
                }
                String html;
                try { html = output.toString(charset); }
                catch (Exception ignored) { html = output.toString("UTF-8"); }
                if (html.length() < 100) throw new Exception("来源返回内容为空或不完整");
                if (Pattern.compile("(?:cf-browser-verification|cf-chl-|challenge-platform|just a moment|captcha)", Pattern.CASE_INSENSITIVE)
                        .matcher(html).find()) {
                    throw new Exception("来源要求人机验证，无法直接抓取；可使用网页打开");
                }
                return new Page(html, target.toString());
            } finally {
                conn.disconnect();
            }
        }
        throw new Exception("来源跳转次数过多，已停止抓取");
    }

    private static String attr(String attrs, String key) {
        if (attrs == null) return "";
        Pattern p = Pattern.compile("\\b" + Pattern.quote(key) +
                "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", Pattern.CASE_INSENSITIVE);
        Matcher match = p.matcher(attrs);
        if (!match.find()) return "";
        for (int i = 1; i <= 3; i++) if (match.group(i) != null) return decode(match.group(i));
        return "";
    }

    private static String decode(String raw) {
        if (raw == null) return "";
        return Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
                .replace('\u00a0', ' ').trim();
    }

    private static String cleanText(String raw) {
        return decode(raw.replaceAll("(?is)<script[^>]*>.*?</script>", "")
                .replaceAll("(?is)<style[^>]*>.*?</style>", "")
                .replaceAll("(?is)<[^>]+>", " ")
                .replaceAll("\\s+", " ")).replaceAll("\\s+", " ").trim();
    }

    private static String absolute(String base, String relative) {
        if (relative == null || relative.trim().isEmpty() || relative.startsWith("javascript:") ||
                relative.startsWith("data:") || relative.startsWith("#")) return "";
        try {
            URL url = new URL(new URL(base), relative);
            return "https".equalsIgnoreCase(url.getProtocol()) ? url.toString() : "";
        } catch (Exception ignored) { return ""; }
    }

    private static String pickImage(String body, String base) {
        Matcher image = IMAGE.matcher(body);
        if (!image.find()) return "";
        String attrs = image.group(1);
        for (String key : new String[] {"data-src", "data-original", "data-lazy-src", "data-thumb", "src"}) {
            String url = absolute(base, attr(attrs, key));
            if (!url.isEmpty()) return url;
        }
        return "";
    }

    private static String extractCode(String text) {
        Matcher m = CODE.matcher(text == null ? "" : text);
        return m.find() ? m.group().toUpperCase(Locale.ROOT).replace('_', '-') : "";
    }

    private static String meta(String html, String key) {
        Matcher m = Pattern.compile("<meta\\b([^>]*?)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        while (m.find()) {
            String attrs = m.group(1);
            if (key.equalsIgnoreCase(attr(attrs, "property")) ||
                    key.equalsIgnoreCase(attr(attrs, "name"))) {
                return attr(attrs, "content");
            }
        }
        return "";
    }

    private static JSONArray findMagnets(String html) {
        JSONArray magnets = new JSONArray();
        Set<String> unique = new HashSet<>();
        Matcher m = MAGNET.matcher(html.replace("&amp;", "&").replace("&#38;", "&"));
        while (m.find() && magnets.length() < MAX_MAGNETS) {
            String link = m.group().replace("&quot;", "");
            if (unique.add(link)) {
                JSONObject item = new JSONObject();
                try {
                    item.put("magnet", link);
                    item.put("name", "来源页面磁力 " + (magnets.length() + 1));
                    magnets.put(item);
                } catch (Exception ignored) {}
            }
        }
        return magnets;
    }

    static JSONObject preview(Context ctx, String sourceId, String sourceName, String rawUrl) throws Exception {
        Page page = loadPage(ctx, sourceId, rawUrl);
        String html = page.html;
        String finalUrl = page.url;
        JSONArray items = new JSONArray();
        Set<String> seen = new HashSet<>();
        Matcher links = ANCHOR.matcher(html);
        String sourceHost = approvedHost(sourceId);
        boolean javBus = "javbus".equals(sourceId);
        boolean javDb = "javdb".equals(sourceId);

        while (links.find() && items.length() < MAX_ITEMS) {
            String a = links.group(1);
            String body = links.group(2);
            String href = absolute(finalUrl, attr(a, "href"));
            if (href.isEmpty() || !hostMatches(new URL(href).getHost(), sourceHost) ||
                    href.equals(finalUrl) || !seen.add(href)) continue;
            String css = attr(a, "class").toLowerCase(Locale.ROOT);
            boolean javBusCard = javBus && css.contains("movie-box");
            boolean javDbCard = javDb && css.matches(".*\\bbox\\b.*");
            boolean knownCard = javBusCard || javDbCard ||
                    css.contains("video") || css.contains("post") ||
                    css.contains("thumb") || css.contains("movie") || css.contains("tile");
            String cover = pickImage(body, finalUrl);
            String text = cleanText(body);
            String code = extractCode(text);
            String title = attr(a, "title");
            if (title.isEmpty()) {
                Matcher titleNode = Pattern.compile(
                        "(?is)<(?:div|span|h[1-6])[^>]*class=[\"'][^\"']*(?:video-title|title|photo-info)[^\"']*[\"'][^>]*>(.*?)</(?:div|span|h[1-6])>")
                        .matcher(body);
                if (titleNode.find()) title = cleanText(titleNode.group(1));
            }
            if (title.isEmpty()) title = text;
            if (title.isEmpty()) {
                Matcher image = IMAGE.matcher(body);
                if (image.find()) title = attr(image.group(1), "alt");
            }
            if (title.length() > 160) title = title.substring(0, 160);
            if (code.isEmpty()) code = extractCode(title);
            // Avoid rendering menus, ads, login buttons, generic navigational links.
            boolean matches = (knownCard && (!code.isEmpty() || (!cover.isEmpty() && title.length() > 6)))
                    || (!code.isEmpty() && !cover.isEmpty());
            if (!matches || title.isEmpty()) continue;

            JSONObject result = new JSONObject();
            result.put("title", title);
            result.put("code", code);
            result.put("cover", cover);
            result.put("url", href);
            items.put(result);
        }

        String pageTitle = meta(html, "og:title");
        if (pageTitle.isEmpty()) {
            Matcher m = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
            if (m.find()) pageTitle = cleanText(m.group(1));
        }
        if (pageTitle.length() > 160) pageTitle = pageTitle.substring(0, 160);

        JSONArray magnets = findMagnets(html);

        JSONObject result = new JSONObject();
        result.put("source", sourceName == null ? sourceId : sourceName);
        result.put("url", finalUrl);
        result.put("title", pageTitle);
        result.put("cover", absolute(finalUrl, meta(html, "og:image")));
        result.put("description", meta(html, "og:description"));
        result.put("items", items);
        result.put("magnets", magnets);
        Matcher next = NEXT_LINK.matcher(html);
        String nextUrl = "";
        if (next.find()) {
            String candidate = absolute(finalUrl, attr(next.group(1), "href"));
            if (!candidate.isEmpty() && hostMatches(new URL(candidate).getHost(), sourceHost)
                    && !candidate.equals(finalUrl)) nextUrl = candidate;
        }
        result.put("nextUrl", nextUrl);
        return result;
    }

    /** Covers from a supported source or its subdomains only. Never fetch arbitrary URLs. */
    static Bitmap cover(Context ctx, String sourceId, String address) {
        HttpURLConnection conn = null;
        try {
            URL target = validated(sourceId, address);
            conn = (HttpURLConnection) target.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(9000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", sourceUserAgent(ctx, sourceId));
            attachSiteCookies(conn, target);
            if (conn.getResponseCode() != 200) return null;
            String type = conn.getContentType();
            if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("image/")) return null;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = conn.getInputStream()) {
                byte[] chunk = new byte[8192];
                int size;
                while ((size = input.read(chunk)) != -1) {
                    output.write(chunk, 0, size);
                    if (output.size() > MAX_IMAGE_BYTES) return null;
                }
            }
            byte[] bytes = output.toByteArray();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}

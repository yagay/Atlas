package uk.wort.atlas.overlay;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * All external requests are made from Android's native network stack.
 * Never fetch results inside the overlay WebView or move the user to Atlas.
 */
final class OverlaySearchClient {
    private static final String BASE = "https://atlas.wort.uk/api";
    private OverlaySearchClient() {}

    static JSONObject search(String keyword, String mode, String token) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(BASE + "/search");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(13000);
            connection.setReadTimeout(22000);
            connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + token);

            JSONObject request = new JSONObject();
            request.put("keyword", keyword);
            request.put("page", 1);
            request.put("pageSize", 20);
            request.put("majorCategoryId", "jav_sources");
            request.put("javSubMode", mode);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(request.toString().getBytes(StandardCharsets.UTF_8));
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) throw new Exception("服务器没有返回内容 (HTTP " + status + ")");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream input = stream) {
                byte[] bytes = new byte[4096];
                int n;
                while ((n = input.read(bytes)) != -1) {
                    buffer.write(bytes, 0, n);
                    if (buffer.size() > 2 * 1024 * 1024) throw new Exception("搜索响应超过上限");
                }
            }
            JSONObject response = new JSONObject(buffer.toString("UTF-8"));
            if (status == 401) throw new Exception("登录已失效，请回 Atlas 重新登录");
            if (status < 200 || status >= 300 || !response.optBoolean("success", false)) {
                JSONObject error = response.optJSONObject("error");
                String message = error != null ? error.optString("message", "") : "";
                if (message.isEmpty()) message = response.optString("message", "搜索失败 (HTTP " + status + ")");
                throw new Exception(message);
            }
            JSONObject data = response.optJSONObject("data");
            if (data == null) throw new Exception("搜索结果格式不正确");
            return data;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * Account search history is already saved by POST /api/search on the
     * backend. Read and manage that same account data from the native overlay:
     * no second on-device history database, no duplicated search POST.
     */
    private static JSONObject historyRequest(String method, String path, String token) throws Exception {
        if (token == null || token.trim().isEmpty()) throw new Exception("请先登录 Atlas");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(BASE + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(12000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            int status = connection.getResponseCode();
            if (status == 401) throw new Exception("登录状态已失效，请回到 Atlas 重新登录");
            InputStream raw = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (raw == null) throw new Exception("历史接口没有响应 (HTTP " + status + ")");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream input = raw) {
                byte[] part = new byte[4096];
                int n;
                while ((n = input.read(part)) != -1) {
                    buffer.write(part, 0, n);
                    if (buffer.size() > 512 * 1024) throw new Exception("历史记录响应过大");
                }
            }
            JSONObject response = new JSONObject(buffer.toString("UTF-8"));
            if (status < 200 || status >= 300 || !response.optBoolean("success", false)) {
                JSONObject error = response.optJSONObject("error");
                String message = error == null ? "" : error.optString("message", "");
                if (message.isEmpty()) message = response.optString("message", "历史接口失败 (HTTP " + status + ")");
                throw new Exception(message);
            }
            return response.optJSONObject("data") == null
                    ? new JSONObject() : response.optJSONObject("data");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static JSONArray history(String token, int limit) throws Exception {
        JSONObject data = historyRequest("GET",
                "/user/search-history?limit=" + Math.max(1, Math.min(100, limit)), token);
        JSONArray history = data.optJSONArray("history");
        return history == null ? new JSONArray() : history;
    }

    static void deleteHistory(String token, String id) throws Exception {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new Exception("无效的历史记录 ID");
        }
        historyRequest("DELETE", "/user/search-history/" + id, token);
    }

    static void clearHistory(String token) throws Exception {
        historyRequest("DELETE", "/user/search-history", token);
    }

    static Bitmap cover(String rawUrl) {
        if (rawUrl == null || rawUrl.isEmpty()) return null;
        HttpURLConnection connection = null;
        try {
            URL raw = new URL(rawUrl.startsWith("//") ? "https:" + rawUrl
                    : rawUrl.startsWith("/") ? "https://www.javbus.com" + rawUrl : rawUrl);
            if (!raw.getProtocol().equals("https")) return null;
            URL proxy = new URL(BASE + "/jav/proxy-image?url=" +
                    java.net.URLEncoder.encode(raw.toString(), "UTF-8"));
            connection = (HttpURLConnection) proxy.openConnection();
            connection.setConnectTimeout(9000);
            connection.setReadTimeout(12000);
            connection.setInstanceFollowRedirects(true);
            if (connection.getResponseCode() != 200) return null;
            try (InputStream input = connection.getInputStream()) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 2;
                return BitmapFactory.decodeStream(input, null, opts);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}

package uk.wort.atlas.overlay;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;

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

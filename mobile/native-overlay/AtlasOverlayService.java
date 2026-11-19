package uk.wort.atlas.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Compact native search UI rendered as TYPE_APPLICATION_OVERLAY above other apps.
 * Search data is rendered in the overlay itself; no Atlas Activity is launched.
 */
public class AtlasOverlayService extends Service {
    public static final String ACTION_START = "uk.wort.atlas.overlay.START";
    public static final String ACTION_STOP = "uk.wort.atlas.overlay.STOP";
    public static final String EXTRA_TOKEN = "token";
    private static final String CHANNEL = "atlas_jav_overlay";
    private static final int NOTIFICATION_ID = 7731;

    private static volatile boolean running = false;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private WindowManager windows;
    private WindowManager.LayoutParams bubbleParams;
    private View bubble;
    private View panel;
    private EditText searchInput;
    private LinearLayout results;
    private String currentMode = "code";
    private String token;
    private int searchGeneration = 0;
    private boolean closing = false;

    public static boolean isRunning() { return running; }

    @Override public void onCreate() {
        super.onCreate();
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String nextToken = intent.getStringExtra(EXTRA_TOKEN);
        if (!Settings.canDrawOverlays(this) || nextToken == null || nextToken.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        token = nextToken; // credential stays in the live process, never in SharedPreferences.
        ensureForeground();
        running = true;
        if (bubble == null) {
            try {
                createBubble();
            } catch (Exception ex) {
                stopSelf();
            }
        }
        return START_NOT_STICKY; // never restart without a fresh authenticated token
    }

    private void ensureForeground() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Atlas 悬浮搜索", NotificationManager.IMPORTANCE_LOW));
        Intent stop = new Intent(this, AtlasOverlayService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 2, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent openApp = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent openIntent = openApp == null ? null : PendingIntent.getActivity(this, 3, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle("Atlas JAV 悬浮搜索")
                .setContentText("点悬浮图标搜索 · 可在通知中关闭")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "关闭悬浮搜索", stopIntent).build());
        if (openIntent != null) builder.setContentIntent(openIntent);
        startForeground(NOTIFICATION_ID, builder.build());
    }

    private int dp(float n) { return Math.round(getResources().getDisplayMetrics().density * n); }

    private GradientDrawable background(int color, float radiusDp, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        if (stroke != 0) shape.setStroke(dp(1), stroke);
        return shape;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout.LayoutParams itemParams(int width, int height, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.topMargin = dp(top);
        return params;
    }

    private void createBubble() {
        TextView icon = label("⌕", 31, Color.rgb(28, 23, 17), true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(background(Color.rgb(236, 179, 100), 50, Color.rgb(250, 202, 136)));
        icon.setElevation(dp(9));
        int size = dp(52);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        bubbleParams = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = getSharedPreferences("overlay_position", MODE_PRIVATE)
                .getInt("x", dm.widthPixels - size - dp(14));
        bubbleParams.y = getSharedPreferences("overlay_position", MODE_PRIVATE)
                .getInt("y", dp(190));
        bubbleParams.x = Math.max(0, Math.min(dm.widthPixels - size, bubbleParams.x));
        bubbleParams.y = Math.max(dp(25), Math.min(dm.heightPixels - size - dp(35), bubbleParams.y));
        icon.setOnTouchListener(new View.OnTouchListener() {
            private float initialX, initialY;
            private int startX, startY;
            private boolean moved;
            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = event.getRawX(); initialY = event.getRawY();
                        startX = bubbleParams.x; startY = bubbleParams.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialX;
                        float dy = event.getRawY() - initialY;
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                        if (moved) {
                            bubbleParams.x = Math.max(0, Math.min(dm.widthPixels - size, startX + Math.round(dx)));
                            bubbleParams.y = Math.max(dp(25), Math.min(dm.heightPixels - size - dp(35), startY + Math.round(dy)));
                            if (bubble != null) windows.updateViewLayout(bubble, bubbleParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) {
                            getSharedPreferences("overlay_position", MODE_PRIVATE).edit()
                                    .putInt("x", bubbleParams.x).putInt("y", bubbleParams.y).apply();
                        } else {
                            openPanel();
                        }
                        return true;
                    default: return true;
                }
            }
        });
        windows.addView(icon, bubbleParams);
        bubble = icon;
    }

    private Button action(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setTextColor(Color.rgb(36, 28, 18));
        button.setBackground(background(Color.rgb(238, 185, 112), 9, 0));
        button.setPadding(dp(12), 0, dp(12), 0);
        return button;
    }

    private Button modeButton(String mode, String title) {
        Button button = action(title);
        boolean selected = mode.equals(currentMode);
        button.setTextColor(selected ? Color.rgb(35, 24, 13) : Color.rgb(202, 206, 212));
        button.setBackground(background(
                selected ? Color.rgb(236, 179, 100) : Color.rgb(48, 50, 57), 9, 0));
        button.setOnClickListener(v -> {
            currentMode = mode;
            if (panel != null) {
                String old = searchInput.getText().toString();
                closePanel();
                openPanel();
                searchInput.setText(old);
                searchInput.setSelection(searchInput.length());
            }
        });
        return button;
    }

    private void openPanel() {
        if (panel != null || closing) return;
        if (bubble != null) bubble.setVisibility(View.GONE);
        LinearLayout root = vertical();
        root.setPadding(dp(14), dp(13), dp(14), dp(13));
        root.setBackground(background(Color.rgb(27, 29, 35), 16, Color.rgb(69, 69, 76)));
        root.setElevation(dp(14));
        LinearLayout title = horizontal();
        title.addView(label("Atlas · JAV 悬浮搜索", 16, Color.WHITE, true),
                new LinearLayout.LayoutParams(0, dp(34), 1));
        Button close = action("×");
        close.setTextSize(22);
        close.setTextColor(Color.WHITE);
        close.setBackground(background(Color.rgb(50, 53, 61), 8, 0));
        close.setOnClickListener(v -> closePanel());
        title.addView(close, new LinearLayout.LayoutParams(dp(38), dp(34)));
        root.addView(title);

        LinearLayout modes = horizontal();
        for (String[] choice : new String[][] {
                {"code", "番号"}, {"actress", "演员"}, {"title", "标题"}
        }) {
            Button item = modeButton(choice[0], choice[1]);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(35), 1);
            params.rightMargin = dp(7);
            modes.addView(item, params);
        }
        root.addView(modes, itemParams(-1, dp(35), 7));

        LinearLayout searchRow = horizontal();
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.rgb(142, 146, 155));
        input.setHint(currentMode.equals("code") ? "输入番号，例如 ABC-123" :
                currentMode.equals("actress") ? "输入演员姓名" : "输入作品标题");
        input.setPadding(dp(10), 0, dp(10), 0);
        input.setBackground(background(Color.rgb(48, 50, 56), 8, Color.rgb(82, 82, 87)));
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput = input;
        searchRow.addView(input, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button search = action("搜索");
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(dp(67), dp(44));
        btnParams.leftMargin = dp(8);
        searchRow.addView(search, btnParams);
        search.setOnClickListener(v -> performSearch());
        input.setOnEditorActionListener((v, id, event) -> {
            if (id == EditorInfo.IME_ACTION_SEARCH) { performSearch(); return true; }
            return false;
        });
        root.addView(searchRow, itemParams(-1, dp(44), 12));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        results = vertical();
        results.setPadding(0, dp(12), 0, dp(10));
        scroll.addView(results);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int scrollHeight = Math.min(dp(390), Math.round(dm.heightPixels * 0.47f));
        root.addView(scroll, itemParams(-1, scrollHeight, 0));
        addInfo("输入番号或关键词后，封面、详情及磁力链接会显示在这里。");

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                dm.widthPixels - dp(24), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = dp(12);
        p.y = Math.max(dp(36), Math.min(bubbleParams.y, Math.round(dm.heightPixels * 0.19f)));
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        try {
            windows.addView(root, p);
            panel = root;
            input.requestFocus();
            ui.postDelayed(() -> {
                if (panel == root) {
                    InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
                }
            }, 160);
        } catch (Exception ex) {
            panel = null;
            if (bubble != null) bubble.setVisibility(View.VISIBLE);
            Toast.makeText(this, "无法打开悬浮搜索框", Toast.LENGTH_SHORT).show();
        }
    }

    private void closePanel() {
        searchGeneration++;
        if (searchInput != null) {
            InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            keyboard.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        }
        if (panel != null) {
            try { windows.removeView(panel); } catch (Exception ignored) {}
            panel = null;
        }
        results = null;
        searchInput = null;
        if (bubble != null) bubble.setVisibility(View.VISIBLE);
    }

    private void addInfo(String message) {
        if (results == null) return;
        TextView text = label(message, 13, Color.rgb(167, 172, 182), false);
        text.setPadding(dp(4), dp(5), dp(4), dp(6));
        results.addView(text, itemParams(-1, -2, 3));
    }

    private void addHeading(String message) {
        if (results == null) return;
        TextView text = label(message, 14, Color.rgb(239, 187, 122), true);
        text.setPadding(dp(2), dp(10), dp(2), dp(4));
        results.addView(text, itemParams(-1, -2, 1));
    }

    private void addSmall(String message) {
        if (message == null || message.isEmpty() || results == null) return;
        TextView text = label(message, 12, Color.rgb(193, 197, 204), false);
        text.setPadding(dp(2), dp(3), dp(2), dp(3));
        results.addView(text);
    }

    private void performSearch() {
        if (searchInput == null || results == null) return;
        String query = searchInput.getText().toString().trim();
        if (query.isEmpty()) {
            Toast.makeText(this, "请输入番号或关键词", Toast.LENGTH_SHORT).show();
            return;
        }
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        keyboard.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        final int request = ++searchGeneration;
        final String mode = currentMode;
        final String credential = token;
        results.removeAllViews();
        addInfo("正在搜索：" + query);
        io.execute(() -> {
            try {
                JSONObject data = OverlaySearchClient.search(query, mode, credential);
                ui.post(() -> { if (request == searchGeneration && panel != null) showSearchResult(data, request); });
            } catch (Exception ex) {
                String message = ex.getMessage();
                ui.post(() -> {
                    if (request != searchGeneration || results == null) return;
                    results.removeAllViews();
                    addInfo(message == null ? "搜索失败，请重试" : message);
                });
            }
        });
    }

    private void copyMagnet(String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Atlas JAV magnet", value));
        Toast.makeText(this, "磁力链接已复制", Toast.LENGTH_SHORT).show();
    }

    private void showSearchResult(JSONObject data, int request) {
        if (results == null) return;
        results.removeAllViews();
        boolean populated = false;
        JSONObject detail = data.optJSONObject("detail");
        if (detail != null) {
            populated = true;
            String code = detail.optString("code");
            addHeading(code.isEmpty() ? "作品详情" : code + " · 作品详情");
            LinearLayout header = horizontal();
            String coverUrl = detail.optString("cover");
            if (!coverUrl.isEmpty()) {
                ImageView cover = new ImageView(this);
                cover.setBackground(background(Color.rgb(44, 46, 53), 8, 0));
                cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                header.addView(cover, new LinearLayout.LayoutParams(dp(96), dp(123)));
                io.execute(() -> {
                    Bitmap bitmap = OverlaySearchClient.cover(coverUrl);
                    if (bitmap != null) ui.post(() -> {
                        if (request == searchGeneration && panel != null) cover.setImageBitmap(bitmap);
                    });
                });
            }
            LinearLayout meta = vertical();
            meta.setPadding(dp(10), 0, 0, 0);
            String title = detail.optString("title", code);
            meta.addView(label(title, 14, Color.WHITE, true));
            for (String[] row : new String[][] {
                    {"releaseDate", "发行"}, {"duration", "时长"}, {"maker", "制作"},
                    {"publisher", "发行商"}, {"series", "系列"}
            }) {
                String value = detail.optString(row[0]);
                if (!value.isEmpty()) {
                    TextView text = label(row[1] + "：" + value, 11, Color.rgb(178, 181, 190), false);
                    meta.addView(text, itemParams(-1, -2, 5));
                }
            }
            header.addView(meta, new LinearLayout.LayoutParams(0, -2, 1));
            results.addView(header, itemParams(-1, -2, 8));
            JSONArray actresses = detail.optJSONArray("actresses");
            if (actresses != null && actresses.length() > 0) {
                StringBuilder names = new StringBuilder();
                for (int i=0; i < Math.min(actresses.length(), 5); i++) {
                    if (i > 0) names.append("、");
                    names.append(actresses.optString(i));
                }
                addSmall("演员：" + names);
            }
            JSONArray magnets = detail.optJSONArray("magnets");
            if (magnets != null && magnets.length() > 0) {
                addHeading("磁力资源 · " + magnets.length() + " 条（点复制）");
                for (int i = 0; i < Math.min(18, magnets.length()); i++) {
                    JSONObject m = magnets.optJSONObject(i);
                    if (m == null) continue;
                    String link = m.optString("magnet");
                    if (link.isEmpty()) continue;
                    LinearLayout row = horizontal();
                    row.setBackground(background(Color.rgb(43, 46, 54), 8, 0));
                    row.setPadding(dp(8), dp(6), dp(5), dp(6));
                    LinearLayout info = vertical();
                    info.addView(label(m.optString("name", "磁力链接"), 12, Color.WHITE, true));
                    info.addView(label(m.optString("size", "大小未知") +
                            (m.optBoolean("isHD") ? " · HD" : ""), 11, Color.rgb(174, 177, 186), false));
                    row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
                    Button copy = action("复制");
                    copy.setOnClickListener(v -> copyMagnet(link));
                    row.addView(copy, new LinearLayout.LayoutParams(dp(60), dp(36)));
                    results.addView(row, itemParams(-1, -2, 6));
                }
                if (magnets.length() > 18) addSmall("其余 " + (magnets.length() - 18) + " 条资源可在主 App 查看");
            } else {
                addSmall("暂无磁力结果。可查看下方其他搜索源。");
            }
        }

        JSONArray actresses = data.optJSONArray("actresses");
        if (actresses != null && actresses.length() > 0) {
            populated = true;
            addHeading("演员资料 · " + actresses.length());
            for (int i = 0; i < Math.min(8, actresses.length()); i++) {
                JSONObject a = actresses.optJSONObject(i);
                if (a != null) addSmall("• " + a.optString("name"));
            }
        }

        JSONArray works = data.optJSONArray("actressWorks");
        if (works != null && works.length() > 0) {
            populated = true;
            addHeading("相关作品 · " + works.length());
            for (int i = 0; i < Math.min(12, works.length()); i++) {
                JSONObject item = works.optJSONObject(i);
                if (item == null) continue;
                String code = item.optString("code");
                Button entry = action(code + "  " + item.optString("title"));
                entry.setTextColor(Color.rgb(231, 232, 235));
                entry.setBackground(background(Color.rgb(49, 51, 60), 8, 0));
                entry.setOnClickListener(v -> {
                    currentMode = "code";
                    closePanel();
                    openPanel();
                    if (searchInput != null) {
                        searchInput.setText(code);
                        performSearch();
                    }
                });
                results.addView(entry, itemParams(-1, dp(43), 6));
            }
        }

        JSONArray sources = data.optJSONArray("results");
        if (sources != null && sources.length() > 0) {
            populated = true;
            addHeading("其他搜索源 · " + sources.length());
            for (int i = 0; i < Math.min(15, sources.length()); i++) {
                JSONObject source = sources.optJSONObject(i);
                if (source == null) continue;
                String url = source.optString("url");
                if (!url.startsWith("https://")) continue;
                Button button = action(source.optString("name", "搜索源") + "  ↗");
                button.setTextColor(Color.rgb(228, 229, 233));
                button.setBackground(background(Color.rgb(48, 50, 58), 8, 0));
                button.setOnClickListener(v -> {
                    try {
                        Intent view = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                        view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(view);
                    } catch (Exception ex) {
                        Toast.makeText(this, "无法打开搜索源", Toast.LENGTH_SHORT).show();
                    }
                });
                results.addView(button, itemParams(-1, dp(38), 5));
            }
        }
        if (!populated) {
            String info = "没有找到结果";
            JSONObject errors = data.optJSONObject("errors");
            if (errors != null && !errors.optString("search").isEmpty()) info = errors.optString("search");
            addInfo(info);
        }
    }

    @Override public void onDestroy() {
        running = false;
        closing = true;
        searchGeneration++;
        closePanel();
        if (bubble != null) {
            try { windows.removeView(bubble); } catch (Exception ignored) {}
            bubble = null;
        }
        token = null;
        io.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

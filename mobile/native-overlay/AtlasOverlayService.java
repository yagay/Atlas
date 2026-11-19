package uk.wort.atlas.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
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
import android.view.WindowInsets;
import android.view.WindowMetrics;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.text.Editable;
import android.text.InputType;
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
    public static final String ACTION_OPEN_PANEL = "uk.wort.atlas.overlay.OPEN_PANEL_SERVICE";
    public static final String ACTION_SET_OPACITY = "uk.wort.atlas.overlay.OPACITY";
    public static final String ACTION_SITE_LOGIN_FINISHED = "uk.wort.atlas.overlay.LOGIN_FINISHED";
    public static final String EXTRA_OPACITY = "opacity";
    private static final String WINDOW_PREFS = "overlay_window";
    public static final String EXTRA_TOKEN = "token";
    private static final String CHANNEL = "atlas_jav_overlay";
    private static final int NOTIFICATION_ID = 7731;

    private static volatile boolean running = false;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private WindowManager windows;
    private WindowManager.LayoutParams bubbleParams;
    private WindowManager.LayoutParams panelParams;
    private View bubble;
    private View panel;
    private EditText searchInput;
    private LinearLayout results;
    private ScrollView resultScroll;
    private LinearLayout panelRoot;
    private LinearLayout modeControls;
    private LinearLayout inputControls;
    private OverlayWebPanel inlineBrowser;
    private TextView opacityLabel;
    private String currentMode = "code";
    private String token;
    private int searchGeneration = 0;
    private boolean closing = false;
    private int opacityPercent = 100;
    private JSONObject lastSearchData;
    private JSONObject currentSourceList;
    private String selectedSourceId;
    private String selectedSourceName;
    private String selectedSourceUrl;
    private String activeSourceUrl;
    private boolean activeSourceDetail;
    private boolean sourceLoginOpen;

    public static boolean isRunning() { return running; }

    @Override public void onCreate() {
        super.onCreate();
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        opacityPercent = getSavedOpacity();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_OPEN_PANEL.equals(intent.getAction())) {
            if (running && token != null) openPanel();
            return START_NOT_STICKY;
        }
        if (ACTION_SET_OPACITY.equals(intent.getAction())) {
            setPanelOpacity(intent.getIntExtra(EXTRA_OPACITY, getSavedOpacity()));
            return START_NOT_STICKY;
        }
        if (ACTION_SITE_LOGIN_FINISHED.equals(intent.getAction())) {
            sourceLoginOpen = false;
            if (panel != null) panel.setVisibility(View.VISIBLE);
            if (bubble != null && panel == null) bubble.setVisibility(View.VISIBLE);
            String loggedSource = intent.getStringExtra(OverlaySourceLoginActivity.EXTRA_SOURCE_ID);
            if (running && token != null && panel != null &&
                    loggedSource != null && loggedSource.equals(selectedSourceId) &&
                    activeSourceUrl != null) {
                loadSourcePreview(selectedSourceId, selectedSourceName,
                        activeSourceUrl, activeSourceDetail);
            }
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

    private int getSavedOpacity() {
        return clamp(getSharedPreferences(WINDOW_PREFS, MODE_PRIVATE).getInt("opacity", 100), 50, 100);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(Math.max(min, max), value));
    }

    private void setPanelOpacity(int value) {
        opacityPercent = clamp(value, 50, 100);
        getSharedPreferences(WINDOW_PREFS, MODE_PRIVATE).edit().putInt("opacity", opacityPercent).apply();
        if (opacityLabel != null) opacityLabel.setText(opacityPercent + "%");
        if (panel != null && panelParams != null) {
            panelParams.alpha = opacityPercent / 100f;
            try { windows.updateViewLayout(panel, panelParams); } catch (Exception ignored) {}
        }
    }

    /**
     * Keep a floating window inside the display's status/navigation/cutout insets.
     * The panel is a small movable window, never an edge-to-edge page.
     */
    private Rect safeBounds() {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        int left = dp(8), top = dp(35), right = width - dp(8), bottom = height - dp(42);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowMetrics metrics = windows.getMaximumWindowMetrics();
            Rect bounds = metrics.getBounds();
            width = bounds.width();
            height = bounds.height();
            Insets bars = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.statusBars() |
                    WindowInsets.Type.navigationBars() |
                    WindowInsets.Type.displayCutout());
            left = Math.max(dp(8), bars.left + dp(8));
            right = width - Math.max(dp(8), bars.right + dp(8));
            top = Math.max(dp(35), bars.top + dp(10));
            bottom = height - Math.max(dp(42), bars.bottom + dp(10));
        }
        // Never enlarge a safe rectangle beyond the physical display. The old
        // 220/230dp minimums could put small overlay content under system bars.
        return new Rect(left, top, Math.max(left + 1, right),
                Math.max(top + 1, bottom));
    }

    private void keepPanelVisible() {
        if (panel == null || panelParams == null) return;
        Rect safe = safeBounds();
        int w = panel.getWidth() > 0 ? panel.getWidth() : panelParams.width;
        int h = panel.getHeight() > 0 ? panel.getHeight() : dp(200);
        int x = clamp(panelParams.x, safe.left, Math.max(safe.left, safe.right - w));
        int y = clamp(panelParams.y, safe.top, Math.max(safe.top, safe.bottom - h));
        if (x != panelParams.x || y != panelParams.y) {
            panelParams.x = x;
            panelParams.y = y;
            try { windows.updateViewLayout(panel, panelParams); } catch (Exception ignored) {}
        }
    }

    private void enablePanelDrag(View handle) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float touchX, touchY;
            private int startingX, startingY;
            @Override public boolean onTouch(View v, MotionEvent event) {
                if (panelParams == null || panel == null) return false;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        startingX = panelParams.x;
                        startingY = panelParams.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        Rect area = safeBounds();
                        int width = panel.getWidth() > 0 ? panel.getWidth() : panelParams.width;
                        int height = panel.getHeight() > 0 ? panel.getHeight() : dp(220);
                        panelParams.x = clamp(startingX + Math.round(event.getRawX() - touchX),
                                area.left, Math.max(area.left, area.right - width));
                        panelParams.y = clamp(startingY + Math.round(event.getRawY() - touchY),
                                area.top, Math.max(area.top, area.bottom - height));
                        try { windows.updateViewLayout(panel, panelParams); } catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        getSharedPreferences(WINDOW_PREFS, MODE_PRIVATE).edit()
                                .putInt("panel_x", panelParams.x)
                                .putInt("panel_y", panelParams.y).apply();
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

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
        Rect bubbleSafe = safeBounds();
        bubbleParams.x = clamp(bubbleParams.x, bubbleSafe.left,
                Math.max(bubbleSafe.left, bubbleSafe.right - size));
        bubbleParams.y = clamp(bubbleParams.y, bubbleSafe.top,
                Math.max(bubbleSafe.top, bubbleSafe.bottom - size));
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
                            Rect bubbleArea = safeBounds();
                            bubbleParams.x = clamp(startX + Math.round(dx),
                                    bubbleArea.left, Math.max(bubbleArea.left, bubbleArea.right - size));
                            bubbleParams.y = clamp(startY + Math.round(dy),
                                    bubbleArea.top, Math.max(bubbleArea.top, bubbleArea.bottom - size));
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

    /**
     * Android floating windows may not show the stock EditText paste popup on
     * certain ROMs. Paste explicitly into the focused EditText using the native
     * clipboard, preserving selection and normal IME editing.
     */
    private void pasteFromClipboard() {
        EditText target = searchInput;
        if (target == null || panel == null) return;
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) {
                Toast.makeText(this, "剪贴板为空，请先复制内容", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                Toast.makeText(this, "剪贴板暂无可粘贴内容", Toast.LENGTH_SHORT).show();
                return;
            }
            CharSequence source = clip.getItemAt(0).coerceToText(this);
            if (source == null || source.length() == 0) {
                Toast.makeText(this, "剪贴板没有文本内容", Toast.LENGTH_SHORT).show();
                return;
            }
            // Search is single-line; avoid pasting huge content or line breaks.
            String paste = source.toString().replaceAll("[\\r\\n\\t]+", " ").trim();
            if (paste.isEmpty()) {
                Toast.makeText(this, "剪贴板没有文本内容", Toast.LENGTH_SHORT).show();
                return;
            }
            if (paste.length() > 500) paste = paste.substring(0, 500);
            Editable existing = target.getText();
            int length = existing.length();
            int cursorStart = target.getSelectionStart();
            int cursorEnd = target.getSelectionEnd();
            if (cursorStart < 0 || cursorEnd < 0) {
                cursorStart = length;
                cursorEnd = length;
            }
            int from = Math.max(0, Math.min(Math.min(cursorStart, cursorEnd), length));
            int to = Math.max(from, Math.min(Math.max(cursorStart, cursorEnd), length));
            existing.replace(from, to, paste);
            target.setSelection(Math.min(existing.length(), from + paste.length()));
            target.requestFocus();
        } catch (SecurityException denied) {
            Toast.makeText(this, "系统限制悬浮窗读取剪贴板，请在键盘中粘贴", Toast.LENGTH_LONG).show();
        } catch (Exception problem) {
            Toast.makeText(this, "粘贴失败，可尝试长按输入框或键盘的粘贴按钮", Toast.LENGTH_LONG).show();
        }
    }

    private void openPanel() {
        if (panel != null || closing) return;
        if (bubble != null) bubble.setVisibility(View.GONE);
        LinearLayout root = vertical();
        panelRoot = root;
        root.setPadding(dp(10), dp(9), dp(10), dp(9));
        root.setBackground(background(Color.rgb(27, 29, 35), 16, Color.rgb(69, 69, 76)));
        root.setElevation(dp(14));
        LinearLayout title = horizontal();
        TextView dragTitle = label("☰  JAV 搜索 · 拖动", 14, Color.WHITE, true);
        title.addView(dragTitle, new LinearLayout.LayoutParams(0, dp(36), 1));
        enablePanelDrag(dragTitle);

        Button opacityButton = action(opacityPercent + "%");
        opacityButton.setTextSize(11);
        opacityButton.setTextColor(Color.rgb(237, 198, 145));
        opacityButton.setBackground(background(Color.rgb(48, 50, 57), 8, 0));
        opacityLabel = opacityButton;
        opacityButton.setOnClickListener(v -> {
            int next = opacityPercent <= 55 ? 100 : (opacityPercent <= 70 ? 55 : opacityPercent <= 85 ? 70 : 85);
            setPanelOpacity(next);
        });
        LinearLayout.LayoutParams opacityParams = new LinearLayout.LayoutParams(dp(51), dp(34));
        opacityParams.rightMargin = dp(6);
        title.addView(opacityButton, opacityParams);
        Button close = action("×");
        close.setTextSize(22);
        close.setTextColor(Color.WHITE);
        close.setBackground(background(Color.rgb(50, 53, 61), 8, 0));
        close.setOnClickListener(v -> closePanel());
        title.addView(close, new LinearLayout.LayoutParams(dp(38), dp(34)));
        root.addView(title);

        LinearLayout modes = horizontal();
        modeControls = modes;
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
        inputControls = searchRow;
        EditText input = new EditText(this);
        // Keep standard selection/cut/copy/paste action mode and IME clipboard support.
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setLongClickable(true);
        input.setCursorVisible(true);
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
        Button paste = action("粘贴");
        paste.setContentDescription("粘贴剪贴板文本到搜索框");
        paste.setTextColor(Color.rgb(235, 205, 167));
        paste.setBackground(background(Color.rgb(53, 55, 64), 8, 0));
        LinearLayout.LayoutParams pasteParams = new LinearLayout.LayoutParams(dp(49), dp(44));
        pasteParams.leftMargin = dp(6);
        searchRow.addView(paste, pasteParams);
        paste.setOnClickListener(v -> pasteFromClipboard());
        Button search = action("搜索");
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(dp(67), dp(44));
        btnParams.leftMargin = dp(6);
        searchRow.addView(search, btnParams);
        search.setOnClickListener(v -> performSearch());
        input.setOnEditorActionListener((v, id, event) -> {
            if (id == EditorInfo.IME_ACTION_SEARCH) { performSearch(); return true; }
            return false;
        });
        root.addView(searchRow, itemParams(-1, dp(44), 12));

        results = vertical();
        results.setPadding(0, dp(3), 0, 0);
        // The content is WRAP_CONTENT until it reaches a safe, scrollable height.
        // No fixed 390dp blank area above/below a small result.
        Rect safe = safeBounds();
        int reservedForToolbar = dp(196);
        final int maxResultsHeight = Math.min(dp(360),
                Math.max(dp(36), safe.height() - reservedForToolbar));
        ScrollView compactResults = new ScrollView(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(
                        maxResultsHeight, View.MeasureSpec.AT_MOST));
            }
        };
        compactResults.setFillViewport(false);
        compactResults.setVerticalScrollBarEnabled(true);
        compactResults.addView(results);
        resultScroll = compactResults;
        resultScroll.setVisibility(View.GONE);
        root.addView(resultScroll, itemParams(-1, -2, 4));

        int panelWidth = Math.min(dp(350), safe.width());
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                panelWidth, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = clamp(getSharedPreferences(WINDOW_PREFS, MODE_PRIVATE).getInt("panel_x",
                bubbleParams.x - panelWidth / 2), safe.left,
                Math.max(safe.left, safe.right - panelWidth));
        p.y = clamp(getSharedPreferences(WINDOW_PREFS, MODE_PRIVATE).getInt("panel_y",
                bubbleParams.y), safe.top, Math.max(safe.top, safe.bottom - dp(210)));
        p.alpha = opacityPercent / 100f;
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        try {
            windows.addView(root, p);
            panel = root;
            panelParams = p;
            root.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (panel == root) keepPanelVisible();
            });
            input.requestFocus();
            ui.postDelayed(() -> {
                if (panel == root) {
                    InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
                }
            }, 160);
        } catch (Exception ex) {
            panel = null;
            panelParams = null;
            panelRoot = null;
            modeControls = null;
            inputControls = null;
            opacityLabel = null;
            resultScroll = null;
            if (bubble != null) bubble.setVisibility(View.VISIBLE);
            Toast.makeText(this, "无法打开悬浮搜索框", Toast.LENGTH_SHORT).show();
        }
    }

    private void closePanel() {
        searchGeneration++;
        closeEmbeddedWeb();
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
        panelRoot = null;
        modeControls = null;
        inputControls = null;
        lastSearchData = null;
        currentSourceList = null;
        selectedSourceId = null;
        selectedSourceName = null;
        selectedSourceUrl = null;
        activeSourceUrl = null;
        activeSourceDetail = false;
        sourceLoginOpen = false;
        resultScroll = null;
        opacityLabel = null;
        panelParams = null;
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
        closeEmbeddedWeb();
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
        lastSearchData = null;
        currentSourceList = null;
        selectedSourceId = null;
        results.removeAllViews();
        if (resultScroll != null) resultScroll.setVisibility(View.VISIBLE);
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

    /**
     * Hand a magnet URI to the Android resolver. A floating service has no
     * Activity context, so NEW_TASK is required. Never navigate to Atlas or
     * reinterpret the magnet as an http(s) URL.
     */
    private void openMagnet(String value) {
        if (value == null || value.trim().isEmpty()) {
            Toast.makeText(this, "磁力链接为空", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            android.net.Uri magnet = android.net.Uri.parse(value.trim());
            // Magnet URIs are opaque on some Android builds, so do not call
            // Uri.getQueryParameter(), which throws for non-hierarchical URIs.
            if (!"magnet".equalsIgnoreCase(magnet.getScheme())
                    || !magnet.getSchemeSpecificPart().contains("xt=")) {
                Toast.makeText(this, "磁力链接格式无效", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent open = new Intent(Intent.ACTION_VIEW, magnet);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(open);
        } catch (ActivityNotFoundException noHandler) {
            Toast.makeText(this, "没有可打开磁力链接的应用，请安装支持磁力链接的下载工具或点击复制",
                    Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "无法打开磁力链接，可点击复制后使用其他应用",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showSearchResult(JSONObject data, int request) {
        if (results == null) return;
        if (resultScroll != null) {
            resultScroll.setVisibility(View.VISIBLE);
            resultScroll.scrollTo(0, 0);
        }
        results.removeAllViews();
        lastSearchData = data;
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
                addHeading("磁力资源 · " + magnets.length() + " 条");
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
                    Button open = action("打开");
                    open.setContentDescription("使用其他应用打开磁力链接");
                    open.setOnClickListener(v -> openMagnet(link));
                    LinearLayout.LayoutParams openParams =
                            new LinearLayout.LayoutParams(dp(53), dp(36));
                    openParams.leftMargin = dp(5);
                    row.addView(open, openParams);

                    Button copy = action("复制");
                    copy.setContentDescription("复制磁力链接");
                    copy.setOnClickListener(v -> copyMagnet(link));
                    copy.setTextColor(Color.rgb(236, 218, 192));
                    copy.setBackground(background(Color.rgb(65, 68, 76), 8, 0));
                    LinearLayout.LayoutParams copyParams =
                            new LinearLayout.LayoutParams(dp(53), dp(36));
                    copyParams.leftMargin = dp(5);
                    row.addView(copy, copyParams);
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
                String sourceId = source.optString("id", "");
                String sourceName = source.optString("name", "搜索源");
                Button button = action(sourceName + (OverlaySourceClient.supported(sourceId) ? " · 查看内容" : " · 内嵌网页"));
                button.setTextColor(Color.rgb(228, 229, 233));
                button.setBackground(background(Color.rgb(48, 50, 58), 8, 0));
                // Both Atlas main app and floating search now use OverlayWebPanel
                // for site navigation, parsing, sign-in and magnet actions.
                button.setOnClickListener(v -> openEmbeddedSource(sourceId, sourceName, url));
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

    /** Source login happens inside the same draggable floating window. */
    private void openSourceLogin(String sourceId, String name, String pageUrl) {
        openEmbeddedSource(sourceId, name, pageUrl);
    }

    private void closeEmbeddedWeb() {
        OverlayWebPanel current = inlineBrowser;
        if (current == null) return;
        inlineBrowser = null;
        if (panelRoot != null) {
            panelRoot.removeView(current.getView());
        }
        current.destroy();
        if (modeControls != null) modeControls.setVisibility(View.VISIBLE);
        if (inputControls != null) inputControls.setVisibility(View.VISIBLE);
        if (resultScroll != null) resultScroll.setVisibility(View.VISIBLE);
        if (panel != null) panel.post(this::keepPanelVisible);
    }

    /**
     * Native browsing is the fallback for sources that require JavaScript,
     * ordinary website sign-in, or have no available HTML parser. The view stays
     * inside TYPE_APPLICATION_OVERLAY, not a separate Activity or full browser.
     */
    private void openEmbeddedSource(String sourceId, String name, String url) {
        if (panelRoot == null || panel == null || url == null ||
                !url.startsWith("https://")) {
            Toast.makeText(this, "无法在悬浮窗内打开该网页", Toast.LENGTH_SHORT).show();
            return;
        }
        closeEmbeddedWeb();
        searchGeneration++;
        if (selectedSourceId == null || !selectedSourceId.equals(sourceId)) {
            selectedSourceId = sourceId;
            selectedSourceName = name;
            selectedSourceUrl = url;
        }
        try {
            // The header, panel padding and margins consume ~64dp. Size the
            // embedded site using the actual safe height, not screen height.
            // In particular, never force a 220dp web panel under 3-button nav.
            int availableHeight = Math.max(dp(65), safeBounds().height() - dp(76));
            int webHeight = Math.min(dp(530), availableHeight);
            OverlayWebPanel embedded = new OverlayWebPanel(
                    this, sourceId, name, url, webHeight, true, new OverlayWebPanel.Actions() {
                @Override public void returnToResults() {
                    closeEmbeddedWeb();
                    int request = ++searchGeneration;
                    if (currentSourceList != null) {
                        showSourcePreview(currentSourceList, selectedSourceId,
                                selectedSourceName, selectedSourceUrl, false, request);
                    } else if (lastSearchData != null) {
                        showSearchResult(lastSearchData, request);
                    } else if (results != null) {
                        results.removeAllViews();
                        addInfo("请重新搜索");
                    }
                }

            });
            inlineBrowser = embedded;
            if (modeControls != null) modeControls.setVisibility(View.GONE);
            if (inputControls != null) inputControls.setVisibility(View.GONE);
            if (resultScroll != null) resultScroll.setVisibility(View.GONE);
            panelRoot.addView(embedded.getView(),
                    itemParams(LinearLayout.LayoutParams.MATCH_PARENT, webHeight, 4));
            panel.post(this::keepPanelVisible);
        } catch (Exception error) {
            closeEmbeddedWeb();
            Toast.makeText(this, "悬浮网页初始化失败，可使用外部浏览器",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void addSourceToolbar(String sourceName, String pageUrl, boolean detail) {
        if (results == null) return;
        LinearLayout bar = horizontal();
        Button back = action(detail ? "‹ 列表" : "‹ 搜索");
        back.setTextSize(12);
        back.setOnClickListener(v -> {
            int request = ++searchGeneration; // invalidate outstanding image/HTML callbacks
            if (detail && currentSourceList != null) {
                showSourcePreview(currentSourceList, selectedSourceId,
                        selectedSourceName, selectedSourceUrl, false, request);
            } else if (lastSearchData != null) {
                showSearchResult(lastSearchData, request);
            } else {
                results.removeAllViews();
                addInfo("请重新搜索");
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(dp(76), dp(36)));
        TextView sourceLabel = label(sourceName, 12, Color.WHITE, true);
        sourceLabel.setSingleLine(true);
        sourceLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, dp(36), 1);
        titleParams.leftMargin = dp(8);
        bar.addView(sourceLabel, titleParams);
        if (OverlaySourceClient.supported(selectedSourceId)) {
            Button signIn = action("登录");
            signIn.setTextColor(Color.rgb(236, 194, 133));
            signIn.setBackground(background(Color.rgb(49, 53, 61), 8, 0));
            signIn.setOnClickListener(v -> openSourceLogin(selectedSourceId, sourceName, pageUrl));
            LinearLayout.LayoutParams signInParams =
                    new LinearLayout.LayoutParams(dp(54), dp(36));
            signInParams.rightMargin = dp(5);
            bar.addView(signIn, signInParams);
        }
        Button browser = action("网页");
        browser.setTextColor(Color.rgb(231, 234, 239));
        browser.setBackground(background(Color.rgb(55, 59, 69), 8, 0));
        browser.setOnClickListener(v -> openEmbeddedSource(selectedSourceId, sourceName, pageUrl));
        LinearLayout.LayoutParams browseParams = new LinearLayout.LayoutParams(dp(69), dp(36));
        browseParams.leftMargin = dp(6);
        bar.addView(browser, browseParams);
        results.addView(bar, itemParams(-1, dp(36), 0));
    }

    private void openSourceWebsite(String rawUrl) {
        try {
            android.net.Uri uri = android.net.Uri.parse(rawUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                Toast.makeText(this, "来源网址无效", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent view = new Intent(Intent.ACTION_VIEW, uri);
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(view);
        } catch (Exception error) {
            Toast.makeText(this, "没有可用浏览器或无法打开此页面", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Fetch known public source pages off the UI thread, then render structured
     * results inside this same overlay. No Activity/browser switch by default.
     */
    private void loadSourcePreview(String sourceId, String name, String url, boolean detail) {
        if (results == null || panel == null) return;
        if (!detail) {
            selectedSourceId = sourceId;
            selectedSourceName = name;
            selectedSourceUrl = url;
            currentSourceList = null;
        }
        activeSourceUrl = url;
        activeSourceDetail = detail;
        final int request = ++searchGeneration;
        results.removeAllViews();
        if (resultScroll != null) {
            resultScroll.setVisibility(View.VISIBLE);
            resultScroll.scrollTo(0, 0);
        }
        addSourceToolbar(name, url, detail);
        if (!OverlaySourceClient.supported(sourceId)) {
            openEmbeddedSource(sourceId, name, url);
            return;
        }
        addInfo("正在抓取 " + name + " 的网页内容…");
        io.execute(() -> {
            try {
                JSONObject preview = OverlaySourceClient.preview(getApplicationContext(), sourceId, name, url);
                ui.post(() -> {
                    if (request != searchGeneration || results == null || panel == null) return;
                    if (!detail) currentSourceList = preview;
                    showSourcePreview(preview, sourceId, name, url, detail, request);
                });
            } catch (Exception err) {
                String reason = err.getMessage();
                ui.post(() -> {
                    if (request != searchGeneration || results == null || panel == null) return;
                    Toast.makeText(AtlasOverlayService.this,
                            "来源暂无法直接抓取，已在小窗内打开网页",
                            Toast.LENGTH_SHORT).show();
                    openEmbeddedSource(sourceId, name, url);
                });
            }
        });
    }

    private void sourcePreviewCover(String sourceId, String url, ImageView image, int request) {
        if (url == null || url.isEmpty()) return;
        io.execute(() -> {
            Bitmap bitmap = OverlaySourceClient.cover(getApplicationContext(), sourceId, url);
            if (bitmap == null) bitmap = OverlaySearchClient.cover(url);
            if (bitmap == null) return;
            final Bitmap display = bitmap;
            ui.post(() -> {
                if (request == searchGeneration && panel != null && results != null) {
                    image.setImageBitmap(display);
                }
            });
        });
    }

    private void appendSourceMagnets(JSONArray magnets) {
        if (magnets == null || magnets.length() == 0 || results == null) return;
        addHeading("本页磁力 · " + magnets.length() + " 条");
        for (int i = 0; i < Math.min(15, magnets.length()); i++) {
            JSONObject magnet = magnets.optJSONObject(i);
            if (magnet == null) continue;
            String link = magnet.optString("magnet", "");
            if (!link.startsWith("magnet:?")) continue;
            LinearLayout entry = horizontal();
            entry.setPadding(dp(7), dp(5), dp(6), dp(5));
            entry.setBackground(background(Color.rgb(44, 47, 54), 7, 0));
            TextView name = label(magnet.optString("name", "磁力资源 " + (i + 1)),
                    11, Color.rgb(216, 220, 227), false);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            entry.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            Button open = action("打开");
            open.setOnClickListener(v -> openMagnet(link));
            entry.addView(open, new LinearLayout.LayoutParams(dp(54), dp(34)));
            Button copy = action("复制");
            copy.setOnClickListener(v -> copyMagnet(link));
            copy.setTextColor(Color.rgb(235, 220, 199));
            copy.setBackground(background(Color.rgb(58, 62, 70), 8, 0));
            LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(dp(52), dp(34));
            copyParams.leftMargin = dp(5);
            entry.addView(copy, copyParams);
            results.addView(entry, itemParams(-1, -2, 6));
        }
    }

    private void showSourcePreview(JSONObject preview, String sourceId, String name,
                                   String fallbackUrl, boolean detail, int request) {
        if (results == null) return;
        results.removeAllViews();
        if (resultScroll != null) {
            resultScroll.setVisibility(View.VISIBLE);
            resultScroll.scrollTo(0, 0);
        }
        String pageUrl = preview.optString("url", fallbackUrl);
        addSourceToolbar(name, pageUrl, detail);
        String heading = preview.optString("title", "");
        if (!heading.isEmpty()) {
            TextView title = label(heading, 13, Color.rgb(237, 188, 127), true);
            title.setMaxLines(3);
            title.setPadding(dp(3), dp(8), dp(3), dp(3));
            results.addView(title);
        }
        if (detail) {
            String coverUrl = preview.optString("cover", "");
            if (!coverUrl.isEmpty()) {
                ImageView cover = new ImageView(this);
                cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cover.setBackground(background(Color.rgb(47, 49, 56), 7, 0));
                results.addView(cover, itemParams(dp(124), dp(160), 7));
                sourcePreviewCover(sourceId, coverUrl, cover, request);
            }
            String description = preview.optString("description", "");
            if (!description.isEmpty()) addInfo(description.length() > 350
                    ? description.substring(0, 350) + "…" : description);
        }
        JSONArray items = preview.optJSONArray("items");
        if (items != null && items.length() > 0) {
            addHeading((detail ? "相关作品" : "来源搜索结果") + " · " + items.length() + " 条");
            for (int i = 0; i < Math.min(20, items.length()); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String itemUrl = item.optString("url", "");
                String code = item.optString("code", "");
                String title = item.optString("title", "");
                if (itemUrl.isEmpty() || title.isEmpty()) continue;
                LinearLayout card = horizontal();
                card.setPadding(dp(5), dp(5), dp(5), dp(5));
                card.setBackground(background(Color.rgb(46, 49, 56), 8, 0));
                String coverUrl = item.optString("cover", "");
                if (!coverUrl.isEmpty() && i < 8) {
                    ImageView cover = new ImageView(this);
                    cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    cover.setBackground(background(Color.rgb(61, 63, 71), 7, 0));
                    card.addView(cover, new LinearLayout.LayoutParams(dp(54), dp(74)));
                    sourcePreviewCover(sourceId, coverUrl, cover, request);
                }
                LinearLayout info = vertical();
                info.setPadding(dp(8), 0, dp(3), 0);
                if (!code.isEmpty()) {
                    info.addView(label(code, 12, Color.rgb(239, 183, 110), true));
                }
                TextView description = label(title, 11, Color.WHITE, false);
                description.setMaxLines(3);
                description.setEllipsize(android.text.TextUtils.TruncateAt.END);
                info.addView(description, itemParams(-1, -2, 4));
                card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
                TextView go = label("›", 24, Color.rgb(236, 184, 118), true);
                card.addView(go, new LinearLayout.LayoutParams(dp(20), -2));
                card.setOnClickListener(v -> loadSourcePreview(sourceId, name, itemUrl, true));
                results.addView(card, itemParams(-1, -2, 6));
            }
        }

        appendSourceMagnets(preview.optJSONArray("magnets"));

        if ((items == null || items.length() == 0) &&
                (preview.optJSONArray("magnets") == null ||
                        preview.optJSONArray("magnets").length() == 0)) {
            addInfo("网页已读取，但未提取到可用作品或磁力。可能需要登录或运行 JavaScript。");
            Button embedded = action("在小窗中查看完整网页");
            embedded.setOnClickListener(v -> openEmbeddedSource(sourceId, name, pageUrl));
            results.addView(embedded, itemParams(-1, dp(39), 8));
        }

        String nextUrl = preview.optString("nextUrl", "");
        if (!detail && !nextUrl.isEmpty()) {
            Button next = action("加载来源下一页");
            next.setOnClickListener(v -> loadSourcePreview(sourceId, name, nextUrl, false));
            results.addView(next, itemParams(-1, dp(40), 9));
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

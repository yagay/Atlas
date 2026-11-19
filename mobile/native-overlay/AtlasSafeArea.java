package uk.wort.atlas.overlay;

import android.app.Activity;
import android.view.View;
import android.view.WindowManager;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Single native window-insets policy for every full-screen Atlas Activity:
 * the Capacitor main UI, the in-app source browser, and the legacy login page.
 *
 * Android 15/16 enforce edge-to-edge, including on 3-button navigation devices.
 * Reserve the actual visible status/navigation/cutout area at the native content
 * root so *all* children, web and native, remain inside the interactive region.
 * IME insets keep input fields above the keyboard without accumulating padding.
 *
 * Capacitor SystemBars.insetsHandling must be "disable" for the main Activity
 * so that this handler is the only owner of its native safe-area padding.
 */
public final class AtlasSafeArea {
    private AtlasSafeArea() {}

    public static void install(Activity activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        activity.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        final View content = activity.findViewById(android.R.id.content);
        if (content == null) return;

        final int baseLeft = content.getPaddingLeft();
        final int baseTop = content.getPaddingTop();
        final int baseRight = content.getPaddingRight();
        final int baseBottom = content.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(content, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());

            // For a floating/side keyboard, preserve horizontal IME insets too.
            int left = baseLeft + Math.max(bars.left, ime.left);
            int top = baseTop + bars.top;
            int right = baseRight + Math.max(bars.right, ime.right);
            int bottom = baseBottom + Math.max(bars.bottom, ime.bottom);
            if (view.getPaddingLeft() != left || view.getPaddingTop() != top
                    || view.getPaddingRight() != right || view.getPaddingBottom() != bottom) {
                view.setPadding(left, top, right, bottom);
            }

            // Keep dispatching insets to existing WebView and keyboard plugins.
            // Our own baseline is fixed: updates never add padding repeatedly.
            return windowInsets;
        });
        content.post(() -> ViewCompat.requestApplyInsets(content));
    }
}

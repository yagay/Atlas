package uk.wort.atlas.overlay;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Toast;

/**
 * In-app host for the exact same native source viewer as the floating panel.
 * The main search Activity remains underneath; its search state is preserved.
 */
public class AtlasSourceBrowserActivity extends Activity {
    private OverlayWebPanel viewer;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String sourceId = getIntent().getStringExtra("sourceId");
        String sourceName = getIntent().getStringExtra("sourceName");
        String url = getIntent().getStringExtra("url");

        if (!OverlayWebPanel.isSafeInitialUrl(url) ||
                (OverlaySourceClient.supported(sourceId) &&
                 !OverlaySourceClient.isApprovedWebUrl(sourceId, url))) {
            Toast.makeText(this, "搜索源地址不受支持", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        getWindow().setStatusBarColor(Color.rgb(29, 31, 37));
        getWindow().setNavigationBarColor(Color.rgb(29, 31, 37));
        getWindow().getDecorView().setSystemUiVisibility(0);
        getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        try {
            viewer = new OverlayWebPanel(this, sourceId, sourceName, url,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, true,
                    this::finish);
            setContentView(viewer.getView());
        } catch (Exception error) {
            Toast.makeText(this, "无法加载来源页面", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override public void onBackPressed() {
        if (viewer != null && viewer.goBackInSite()) return;
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (viewer != null) {
            viewer.destroy();
            viewer = null;
        }
        super.onDestroy();
    }
}

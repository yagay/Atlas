package uk.wort.atlas.overlay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Public, credential-free gesture entry: only opens an already authorized Atlas overlay. */
public final class AtlasExternalActionReceiver extends BroadcastReceiver {
    public static final String ACTION_OPEN = "uk.wort.atlas.overlay.OPEN_PANEL";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_OPEN.equals(intent.getAction())) return;
        if (AtlasOverlayService.isRunning()) {
            Intent show = new Intent(context, AtlasOverlayService.class)
                    .setAction(AtlasOverlayService.ACTION_OPEN_PANEL);
            try {
                context.startService(show);
                return;
            } catch (Exception ignored) { }
        }
        // Do not import, persist, or request an Atlas authentication token externally.
        Toast.makeText(context, "请先在 Atlas 中开启悬浮搜索", Toast.LENGTH_SHORT).show();
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { context.startActivity(launch); } catch (Exception ignored) { }
        }
    }
}

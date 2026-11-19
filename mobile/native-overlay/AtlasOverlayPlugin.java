package uk.wort.atlas.overlay;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Bridge between original Atlas login state and its native overlay service. */
@CapacitorPlugin(name = "AtlasOverlay")
public class AtlasOverlayPlugin extends Plugin {
    private JSObject state() {
        JSObject data = new JSObject();
        data.put("supported", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O);
        data.put("permissionGranted", Settings.canDrawOverlays(getContext()));
        data.put("running", AtlasOverlayService.isRunning());
        return data;
    }

    @PluginMethod
    public void status(PluginCall call) {
        call.resolve(state());
    }

    @PluginMethod
    public void requestPermission(PluginCall call) {
        if (Settings.canDrawOverlays(getContext())) {
            call.resolve(state());
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getContext().getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            call.resolve(state());
        } catch (Exception error) {
            call.reject("无法打开悬浮窗权限设置", error);
        }
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (!Settings.canDrawOverlays(getContext())) {
            call.reject("请先允许 Atlas 显示在其他应用上层", "OVERLAY_PERMISSION_REQUIRED");
            return;
        }
        String token = call.getString("token");
        if (token == null || token.trim().length() < 16) {
            call.reject("请先登录 Atlas 再开启悬浮搜索");
            return;
        }
        try {
            Intent service = new Intent(getContext(), AtlasOverlayService.class);
            service.setAction(AtlasOverlayService.ACTION_START);
            service.putExtra(AtlasOverlayService.EXTRA_TOKEN, token);
            ContextCompat.startForegroundService(getContext(), service);
            call.resolve(state());
        } catch (Exception error) {
            call.reject("无法启动悬浮搜索服务", error);
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Intent service = new Intent(getContext(), AtlasOverlayService.class);
        service.setAction(AtlasOverlayService.ACTION_STOP);
        try {
            getContext().startService(service);
            call.resolve(state());
        } catch (Exception error) {
            call.reject("无法停止悬浮窗", error);
        }
    }
}

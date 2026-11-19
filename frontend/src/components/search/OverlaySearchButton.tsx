import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Search, Loader2, ShieldCheck, ShieldAlert } from 'lucide-react';
import { App as NativeApp } from '@capacitor/app';
import { useAuthStore } from '@/stores/authStore';
import { atlasOverlay } from '@/services/atlasOverlay';

/** Enables the original Atlas Android app's native floating JAV search window. */
export const OverlaySearchButton: React.FC<{ variant?: 'compact' | 'settings' }> = ({ variant = 'compact' }) => {
  const token = useAuthStore(state => state.token);
  const [enabled, setEnabled] = useState(false);
  const [permissionGranted, setPermissionGranted] = useState(false);
  const [opacity, setOpacity] = useState(100);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const pendingPermission = useRef(false);
  const tokenRef = useRef(token);
  tokenRef.current = token;

  const enable = useCallback(async () => {
    const currentToken = tokenRef.current;
    if (!currentToken) { setMessage('请先登录 Atlas'); return; }
    try {
      await atlasOverlay.start(currentToken);
      setEnabled(true);
      setMessage('悬浮搜索已开启，切换其他应用后点击图标即可搜索');
    } catch (err) {
      setMessage(err instanceof Error ? err.message : '悬浮搜索启动失败');
      setEnabled(false);
    }
  }, []);

  useEffect(() => {
    if (!atlasOverlay.available()) return;
    let disposed = false;
    let unsubscribe: (() => void) | undefined;
    void atlasOverlay.status().then(result => {
      if (!disposed) {
        setEnabled(result.running);
        setPermissionGranted(result.permissionGranted);
        setOpacity(result.opacity ?? 100);
      }
    }).catch(() => undefined);

    void NativeApp.addListener('resume', () => {
      void atlasOverlay.status().then(result => {
        if (disposed) return;
        setEnabled(result.running);
        setPermissionGranted(result.permissionGranted);
        setOpacity(result.opacity ?? 100);
        if (pendingPermission.current) {
          pendingPermission.current = false;
          if (result.permissionGranted) void enable();
          else setMessage('未获得悬浮窗权限，无法开启');
        }
      }).catch(() => undefined);
    }).then(listener => {
      if (disposed) void listener.remove();
      else unsubscribe = () => void listener.remove();
    }).catch(() => undefined);

    return () => { disposed = true; unsubscribe?.(); };
  }, [enable]);

  // On logout, stop the service instead of leaking an authenticated search bubble.
  useEffect(() => {
    if (atlasOverlay.available() && !token) {
      void atlasOverlay.stop().catch(() => undefined);
      setEnabled(false);
    }
  }, [token]);

  if (!atlasOverlay.available()) {
    return variant === 'settings' ? (
      <p className="text-sm text-surface-500">悬浮搜索仅支持安装后的 Android Atlas App。</p>
    ) : null;
  }

  const toggle = async () => {
    if (busy) return;
    setBusy(true);
    setMessage('');
    try {
      const status = await atlasOverlay.status();
      setPermissionGranted(status.permissionGranted);
      if (status.running) {
        await atlasOverlay.stop();
        setEnabled(false);
        setMessage('已关闭悬浮搜索');
      } else if (!status.permissionGranted) {
        pendingPermission.current = true;
        setMessage('请在系统设置中允许 Atlas 显示在其他应用上层');
        await atlasOverlay.requestPermission();
      } else {
        await enable();
      }
    } catch (err) {
      pendingPermission.current = false;
      setMessage(err instanceof Error ? err.message : '操作失败');
    } finally {
      setBusy(false);
    }
  };

  const saveOpacity = async () => {
    try {
      const result = await atlasOverlay.setOpacity(opacity);
      setOpacity(result.opacity ?? opacity);
    } catch (err) {
      setMessage(err instanceof Error ? err.message : '透明度保存失败');
    }
  };

  if (variant === 'settings') {
    return (
      <section className="rounded-2xl border border-surface-200 bg-white p-4 shadow-sm dark:border-surface-800 dark:bg-surface-900/70 sm:p-5">
        <div className="flex items-start justify-between gap-3">
          <div className="flex min-w-0 items-start gap-3">
            <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-amber-500/15 text-amber-600 dark:text-amber-400">
              <Search className="h-5 w-5" />
            </span>
            <div>
              <h2 className="text-base font-semibold text-heading">悬浮 JAV 搜索</h2>
              <p className="mt-1 text-sm leading-6 text-surface-500">
                开启后可在其他应用上方显示可拖动搜索图标。点击图标展开搜索框，直接查看封面、详情和磁力结果。
              </p>
            </div>
          </div>
          <button
            type="button"
            role="switch"
            aria-label="开启或关闭悬浮搜索"
            aria-checked={enabled}
            onClick={() => void toggle()}
            disabled={busy || !token}
            className={'relative mt-1 h-8 w-14 shrink-0 rounded-full transition-colors ' +
              (enabled ? 'bg-amber-500' : 'bg-surface-300 dark:bg-surface-700')}
          >
            <span className={'absolute left-1 top-1 h-6 w-6 rounded-full bg-white shadow-sm transition-transform ' +
              (enabled ? 'translate-x-6' : 'translate-x-0')} />
            {busy ? <Loader2 className="absolute left-[19px] top-2 h-4 w-4 animate-spin text-surface-700" /> : null}
          </button>
        </div>
        <div className="mt-4 flex flex-wrap items-center gap-3 border-t border-surface-100 pt-4 text-xs dark:border-surface-800">
          <span className={enabled ? 'font-medium text-emerald-600 dark:text-emerald-400' : 'text-surface-500'}>
            状态：{enabled ? '已开启' : '未开启'}
          </span>
          <span className={'inline-flex items-center gap-1 ' + (permissionGranted ?
            'text-emerald-600 dark:text-emerald-400' : 'text-amber-600 dark:text-amber-400')}>
            {permissionGranted ? <ShieldCheck className="h-4 w-4" /> : <ShieldAlert className="h-4 w-4" />}
            悬浮窗权限：{permissionGranted ? '已授权' : '未授权'}
          </span>
        </div>
        {!permissionGranted && (
          <button type="button" disabled={busy} onClick={() => void toggle()}
            className="mt-4 rounded-lg bg-amber-500 px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-50">
            前往系统设置并允许悬浮窗
          </button>
        )}
        <div className="mt-4 border-t border-surface-100 pt-4 dark:border-surface-800">
          <div className="flex items-center justify-between gap-3">
            <label htmlFor="overlay-window-opacity" className="text-sm font-medium text-surface-700 dark:text-surface-200">
              悬浮窗口不透明度
            </label>
            <span className="text-sm font-semibold tabular-nums text-amber-600 dark:text-amber-400">{opacity}%</span>
          </div>
          <input
            id="overlay-window-opacity"
            aria-label="悬浮窗口不透明度"
            type="range" min={50} max={100} step={5}
            value={opacity}
            onChange={event => setOpacity(Number(event.target.value))}
            onPointerUp={() => void saveOpacity()}
            onKeyUp={() => void saveOpacity()}
            onBlur={() => void saveOpacity()}
            className="mt-3 w-full accent-amber-500"
          />
          <div className="mt-1 flex justify-between text-xs text-surface-500">
            <span>50% · 半透明</span>
            <span>100% · 不透明</span>
          </div>
          <p className="mt-2 text-xs leading-5 text-surface-500">
            只调节展开后的悬浮窗口。也可在悬浮窗标题栏点百分比按钮快速切换，数值自动保存。
          </p>
        </div>
        <p className="mt-3 text-xs leading-5 text-surface-500">
          首次启用需要在 Android 系统设置中允许「显示在其他应用上层」。关闭开关或退出登录后会停止悬浮服务。
        </p>
        {message && <p role="status" className="mt-3 rounded-lg bg-surface-100 p-3 text-xs leading-5 text-surface-700 dark:bg-surface-800 dark:text-surface-200">{message}</p>}
      </section>
    );
  }

  return (
    <div className="relative">
      <button type="button" onClick={() => void toggle()}
        aria-label={enabled ? '关闭悬浮 JAV 搜索' : '开启悬浮 JAV 搜索'}
        title={enabled ? '关闭悬浮搜索' : '开启悬浮搜索'}
        className={'flex items-center gap-1.5 rounded-lg px-2 py-2 text-xs font-semibold transition-colors ' +
          (enabled ? 'bg-amber-500/15 text-amber-600 dark:text-amber-400' :
            'text-surface-500 hover:bg-surface-100 dark:hover:bg-surface-800')}>
        {busy ? <Loader2 className="h-[18px] w-[18px] animate-spin"/> : <Search className="h-[18px] w-[18px]"/>}
        <span className="hidden min-[390px]:inline">悬浮</span>
        <span aria-hidden="true" className={'h-1.5 w-1.5 rounded-full ' + (enabled ? 'bg-green-500' : 'bg-surface-400')}/>
      </button>
      {message ? (
        <div role="status" className="absolute right-0 top-full z-50 mt-2 w-[min(80vw,280px)] rounded-xl border border-surface-200 bg-white p-3 text-xs leading-relaxed text-surface-700 shadow-xl dark:border-surface-700 dark:bg-surface-900 dark:text-surface-200">
          {message}
          <button type="button" aria-label="关闭提示" className="ml-2 text-primary-500" onClick={() => setMessage('')}>×</button>
        </div>
      ) : null}
    </div>
  );
};

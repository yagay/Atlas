import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Search, Loader2 } from 'lucide-react';
import { App as NativeApp } from '@capacitor/app';
import { useAuthStore } from '@/stores/authStore';
import { atlasOverlay } from '@/services/atlasOverlay';

/** Enables the original Atlas Android app's native floating JAV search window. */
export const OverlaySearchButton: React.FC = () => {
  const token = useAuthStore(state => state.token);
  const [enabled, setEnabled] = useState(false);
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
      if (!disposed) setEnabled(result.running);
    }).catch(() => undefined);

    void NativeApp.addListener('resume', () => {
      void atlasOverlay.status().then(result => {
        if (disposed) return;
        setEnabled(result.running);
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

  if (!atlasOverlay.available()) return null;

  const toggle = async () => {
    if (busy) return;
    setBusy(true);
    setMessage('');
    try {
      const status = await atlasOverlay.status();
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

/**
 * Compact settings for the original Atlas JAV-only Android app.
 * Restores a settings route without reviving community/admin/media features.
 */
import React from 'react';
import { ArrowLeft, Moon, Sun, Settings2 } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { useThemeStore, useAuthStore } from '@/stores';
import { OverlaySearchButton } from '@/components/search/OverlaySearchButton';
import { LanguageSwitcher } from '@/components/ui';

export const JavSettingsPage: React.FC = () => {
  const navigate = useNavigate();
  const { resolvedTheme, toggleTheme } = useThemeStore();
  const username = useAuthStore(state => state.user?.username);

  return (
    <div className="page-bg min-h-screen">
      <header className="sticky top-0 z-40 border-b border-surface-200/80 bg-white/95 px-4 py-3 backdrop-blur dark:border-surface-800/80 dark:bg-surface-950/95">
        <div className="mx-auto flex w-full max-w-[760px] items-center gap-3">
          <button type="button" onClick={() => navigate('/main?tab=jav')} aria-label="返回 JAV 搜索"
            className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl text-surface-600 hover:bg-surface-100 dark:text-surface-300 dark:hover:bg-surface-800">
            <ArrowLeft className="h-5 w-5"/>
          </button>
          <span className="flex min-w-0 items-center gap-2 font-semibold text-heading">
            <Settings2 className="h-[18px] w-[18px] text-amber-500" />
            Atlas · 设置
          </span>
        </div>
      </header>
      <main className="mx-auto w-full max-w-[760px] space-y-5 px-4 py-6 sm:px-6">
        <div>
          <h1 className="text-xl font-bold text-heading">应用设置</h1>
          <p className="mt-1 text-sm text-surface-500">
            {username ? `账号：${username} · ` : ''}仅保留 JAV 搜索相关选项
          </p>
        </div>
        <OverlaySearchButton variant="settings"/>
        <section className="rounded-2xl border border-surface-200 bg-white p-4 shadow-sm dark:border-surface-800 dark:bg-surface-900/70 sm:p-5">
          <h2 className="text-base font-semibold text-heading">界面</h2>
          <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
            <span className="text-sm text-surface-600 dark:text-surface-300">深色模式</span>
            <button type="button" onClick={toggleTheme}
              className="inline-flex min-h-10 items-center gap-2 rounded-xl border border-surface-200 px-3 text-sm text-surface-700 dark:border-surface-700 dark:text-surface-200">
              {resolvedTheme === 'dark' ? <Moon className="h-4 w-4" /> : <Sun className="h-4 w-4" />}
              {resolvedTheme === 'dark' ? '深色' : '浅色'}
            </button>
          </div>
          <div className="mt-3 flex flex-wrap items-center justify-between gap-3 border-t border-surface-100 pt-3 dark:border-surface-800">
            <span className="text-sm text-surface-600 dark:text-surface-300">语言</span>
            <LanguageSwitcher />
          </div>
        </section>
      </main>
    </div>
  );
};

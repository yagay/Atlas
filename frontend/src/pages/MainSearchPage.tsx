/**
 * Original Atlas JAV search workspace with all non-JAV navigation removed.
 * Intentionally keeps useSearchFlow/useJavSearchFlow and existing result panels.
 */
import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Search, LogOut, Moon, Sun, Settings2 } from 'lucide-react';
import { useAuthStore, useSourceStore, useProxyStore, useThemeStore } from '@/stores';
import { useNavigate, useSearchParams } from 'react-router-dom';
import type { JavSubMode } from '@/types/source';
import type { JavEnrichedData } from '@/types/search';
import { JAV_SUB_MODES } from '@/types/source';
import { SearchDock, SearchWorkspace, SearchHistoryPanel } from '@/components/search';
import { OverlaySearchButton } from '@/components/search/OverlaySearchButton';
import { Panel } from '@/components/workbench';
import { LanguageSwitcher } from '@/components/ui';
import { useSearchFlow } from '@/hooks/useSearchFlow';
import { useCacheSync } from '@/hooks/useCacheSync';
import { useFavoritesManager } from '@/hooks/useFavoritesManager';
import { useJavSearchFlow } from '@/hooks/useJavSearchFlow';
import { useSourceManager } from '@/hooks/useSourceManager';
import { useSearchHistoryManager } from '@/hooks/useSearchHistoryManager';
import type { SearchHistoryItem } from '@/types';
import { isJavCodeLike } from '@codeseek/shared/utils';
import { useRouteEnterAnimation } from '@/hooks/useRouteEnterAnimation';
import { usePullToRefresh } from '@/components/layout/PullToRefresh';

export const MainSearchPage: React.FC = () => {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const { user, isAuthenticated, logout } = useAuthStore();
  const { activeTab, setActiveTab } = useSourceStore();
  const { isEnabled: isProxyEnabled, initializeProxy } = useProxyStore();
  const { resolvedTheme, toggleTheme } = useThemeStore();
  const pageRef = useRouteEnterAnimation<HTMLDivElement>();
  const javFlow = useJavSearchFlow();
  const sourceManager = useSourceManager();
  const historyManager = useSearchHistoryManager();
  const [historyExpanded, setHistoryExpanded] = useState(true);
  const [javSubMode, setJavSubMode] = useState<JavSubMode>('code');
  const programmaticUrlRef = useRef(false);

  const handleSearchUrlSync = useCallback((keyword: string, subMode?: JavSubMode) => {
    programmaticUrlRef.current = true;
    setSearchParams({ tab: 'jav', q: keyword, sub: subMode ?? javSubMode }, { replace: true });
  }, [setSearchParams, javSubMode]);

  const searchFlow = useSearchFlow({
    activeTab,
    fetchJavDetail: javFlow.fetchJavDetail,
    resetJavDetail: javFlow.resetJavDetail,
    javEnrichedDetail: javFlow.javEnrichedDetail,
    setJavEnrichedDetail: javFlow.setJavEnrichedDetail,
    javSubMode,
    onSearch: handleSearchUrlSync,
  });
  const favoritesManager = useFavoritesManager({ keyword: searchFlow.keyword });

  const handleHistorySelect = (item: SearchHistoryItem) => {
    const query = item.query?.trim();
    if (!query) return;
    // Older records do not carry JAV sub-mode. Prefer a known code, otherwise
    // reuse the user's selected mode rather than inventing an actress/title type.
    const mode: JavSubMode = item.code || isJavCodeLike(query) ? 'code' : javSubMode;
    setJavSubMode(mode);
    searchFlow.setKeyword(query);
    setHistoryExpanded(false);
    void searchFlow.handleSearch(query, 1, mode);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  };

  usePullToRefresh(async () => {
    if (searchFlow.hasSearched && searchFlow.keyword.trim()) {
      await searchFlow.handleSearch(searchFlow.keyword, searchFlow.enrichedPage || 1);
    }
  });

  const javEnrichedData = searchFlow.enrichedData?.resultType === 'jav'
    ? searchFlow.enrichedData as JavEnrichedData
    : null;
  const javRelatedSeed = javEnrichedData
    ? javEnrichedData.detail
      ? {
          code: javEnrichedData.detail.code,
          actors: javEnrichedData.detail.actresses,
          tags: javEnrichedData.detail.tags,
          publisher: javEnrichedData.detail.publisher || javEnrichedData.detail.maker,
        }
      : javSubMode === 'actress' && javEnrichedData.normalizedKeyword
        ? { actress: javEnrichedData.normalizedKeyword }
        : undefined
    : undefined;
  const javExcludeCodes = javEnrichedData
    ? [
        ...(javEnrichedData.detail ? [javEnrichedData.detail.code] : []),
        ...(javEnrichedData.actressWorks || []).map(work => work.code),
      ]
    : [];

  const [quotaRefreshKey, setQuotaRefreshKey] = useState(0);
  const wasSearchingRef = useRef(false);
  useEffect(() => {
    if (wasSearchingRef.current && !searchFlow.isSearching) {
      setQuotaRefreshKey(key => key + 1);
    }
    wasSearchingRef.current = searchFlow.isSearching;
  }, [searchFlow.isSearching]);

  useEffect(() => {
    const query = searchParams.get('q');
    const tab = searchParams.get('tab');
    const sub = searchParams.get('sub');
    if (tab && tab !== 'jav') {
      const params = new URLSearchParams(searchParams);
      params.set('tab', 'jav');
      setActiveTab('jav');
      setSearchParams(params, { replace: true });
      return;
    }
    if (activeTab !== 'jav') {
      setActiveTab('jav');
      return;
    }
    let override: JavSubMode | undefined;
    if (sub && JAV_SUB_MODES.includes(sub as JavSubMode)) {
      override = sub as JavSubMode;
      if (override !== javSubMode) setJavSubMode(override);
    }
    const programmatic = programmaticUrlRef.current;
    if (programmatic) programmaticUrlRef.current = false;
    if (!query || programmatic) return;
    searchFlow.setKeyword(query);
    searchFlow.handleSearch(query, 1, override);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams, activeTab]);

  useEffect(() => { initializeProxy(); }, [initializeProxy]);
  useCacheSync();

  const handleActressWorkClick = (code: string) => {
    setJavSubMode('code');
    searchFlow.setKeyword(code);
    searchFlow.handleSearch(code, 1, 'code');
    window.scrollTo({ top: 0, behavior: 'smooth' });
  };

  const handleActressClick = (name: string) => {
    setJavSubMode('actress');
    searchFlow.setKeyword(name);
    searchFlow.handleSearch(name, 1, 'actress');
    window.scrollTo({ top: 0, behavior: 'smooth' });
  };

  const handleSubModeChange = (mode: JavSubMode) => {
    setJavSubMode(mode);
    programmaticUrlRef.current = true;
    const params: Record<string, string> = { tab: 'jav', sub: mode };
    if (searchFlow.keyword.trim()) params.q = searchFlow.keyword.trim();
    setSearchParams(params, { replace: true });
  };

  const handleExitResults = useCallback(() => {
    searchFlow.resetResults();
    window.scrollTo({ top: 0, behavior: 'smooth' });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchFlow.resetResults]);

  return (
    <div ref={pageRef} className="page-bg min-h-screen">
      <header className="sticky top-0 z-40 border-b border-surface-200/80 bg-white/95 px-4 py-3 backdrop-blur dark:border-surface-800/80 dark:bg-surface-950/95">
        <div className="mx-auto flex w-full max-w-[1000px] items-center justify-between gap-3">
          <button type="button" onClick={() => { searchFlow.resetResults(); navigate('/main?tab=jav'); }}
            className="flex items-center gap-2 text-base font-bold text-heading" aria-label="JAV 搜索首页">
            <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-amber-500 text-white">
              <Search className="h-5 w-5" />
            </span>
            <span className="hidden min-[390px]:inline">Atlas · </span><span>JAV 搜索</span>
          </button>
          <div className="flex shrink-0 items-center gap-0.5 sm:gap-1">
            <OverlaySearchButton />
            <button type="button" onClick={() => navigate('/settings')} aria-label="打开设置" title="设置"
              className="rounded-lg p-2 text-surface-500 hover:bg-surface-100 dark:hover:bg-surface-800">
              <Settings2 className="h-[18px] w-[18px]" />
            </button>
            <LanguageSwitcher />
            <button type="button" onClick={toggleTheme} aria-label="切换主题" title="切换主题"
              className="rounded-lg p-2 text-surface-500 hover:bg-surface-100 dark:hover:bg-surface-800">
              {resolvedTheme === 'dark' ? <Sun className="h-[18px] w-[18px]"/> : <Moon className="h-[18px] w-[18px]"/>}
            </button>
            <button type="button" onClick={() => logout()} aria-label="退出登录" title="退出登录"
              className="rounded-lg p-2 text-surface-500 hover:bg-surface-100 dark:hover:bg-surface-800">
              <LogOut className="h-[18px] w-[18px]"/>
            </button>
          </div>
        </div>
      </header>
      <main className="mx-auto w-full max-w-[1000px] px-3 py-5 sm:px-6">
        <div className="mb-4">
          <h1 className="type-page-title text-heading">JAV 番号搜索</h1>
          <p className="type-meta mt-1">按番号、演员或标题查询作品、封面、详情与磁力链接</p>
        </div>
        <Panel>
          <SearchDock
            activeTab="jav"
            javSubMode={javSubMode}
            onSubModeChange={handleSubModeChange}
            keyword={searchFlow.keyword}
            setKeyword={searchFlow.setKeyword}
            isSearching={searchFlow.isSearching}
            onSubmit={() => { setHistoryExpanded(false); void searchFlow.handleSearch(); }}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                setHistoryExpanded(false);
              }
              searchFlow.handleKeyDown(event);
            }}
            suggestions={searchFlow.suggestions}
            isLoadingSuggestions={searchFlow.isLoadingSuggestions}
            showSuggestions={searchFlow.showSuggestions}
            onSuggestionSelect={searchFlow.handleSuggestionSelect}
            onCloseSuggestions={() => searchFlow.setShowSuggestions(false)}
            quotaRefreshKey={quotaRefreshKey}
            javFormatHintVisible={searchFlow.javFormatHintVisible}
          />
          <SearchHistoryPanel
            history={historyManager.searchHistory}
            total={historyManager.historyTotal}
            page={historyManager.historyPageNo}
            totalPages={historyManager.historyTotalPages}
            onPageChange={historyManager.setHistoryPageNo}
            isLoading={historyManager.isLoadingHistory}
            show={historyExpanded}
            onToggle={() => {
              const next = !historyExpanded;
              setHistoryExpanded(next);
              if (next) void historyManager.refreshHistory();
            }}
            onItemSelect={handleHistorySelect}
            onClear={historyManager.handleClearHistory}
            onDeleteSelected={historyManager.handleDeleteSelected}
          />
          {searchFlow.hasSearched ? (
            <SearchWorkspace
              activeTab="jav"
              javSubMode={javSubMode}
              searchFlow={searchFlow}
              javFlow={javFlow}
              favoritesManager={favoritesManager}
              sourceManager={sourceManager}
              isAuthenticated={isAuthenticated}
              isProxyEnabled={isProxyEnabled}
              navigate={navigate}
              onExit={handleExitResults}
              onActressWorkClick={handleActressWorkClick}
              onActressClick={handleActressClick}
              javRelatedSeed={javRelatedSeed}
              javExcludeCodes={javExcludeCodes}
            />
          ) : (
            <div className="flex flex-col items-center justify-center gap-2 border-t border-surface-100 px-4 py-10 text-center dark:border-surface-800/70">
              <Search className="h-9 w-9 text-surface-400" />
              <p className="text-sm font-semibold text-heading">输入番号开始搜索</p>
              <p className="type-meta">保留 Atlas 原有 JAV 数据源、作品详情、演员搜索和磁力结果</p>
              {user?.username ? <p className="type-micro text-surface-500">已登录：{user.username}</p> : null}
            </div>
          )}
        </Panel>
      </main>
    </div>
  );
};

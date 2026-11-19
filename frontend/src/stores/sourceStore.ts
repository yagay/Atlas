/**
 * 搜索源工作台状态：只持久化当前选中的类别页签。
 */

import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import type { SearchTabType } from '@/types';

interface SourceState {
  activeTab: SearchTabType;
  setActiveTab: (tab: SearchTabType) => void;
}

/** 源列表页签状态；持久化后刷新页面仍停留在上次类别。 */
export const useSourceStore = create<SourceState>()(
  persist(
    (set) => ({
      activeTab: 'jav',
      // JAV-only: 旧版本持久化的其他搜索分类统一回到 JAV。
      setActiveTab: () => set({ activeTab: 'jav' }),
    }),
    {
      name: 'source-storage',
      storage: createJSONStorage(() => localStorage),
      partialize: () => ({ activeTab: 'jav' as const }),
      merge: (persisted, current) => ({ ...current, ...(persisted as Partial<SourceState>), activeTab: 'jav' }),
    }
  )
);

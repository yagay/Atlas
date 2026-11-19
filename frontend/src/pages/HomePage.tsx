import React, { useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Search, Images, Magnet, Users } from 'lucide-react';
import { useAuthStore, useProxyStore } from '@/stores';
import { UnifiedNavBar } from '@/components/layout';
import { useFeatureFlags } from '@/contexts';
import { FeedbackButton } from '@/components/feedback';
import { SiteFooter } from '@/components/home/SiteFooter';

/** JAV 专用首页：取消动漫、影视、漫画、小说等与番号检索无关的推广模块。 */
export const HomePage: React.FC = () => {
  const navigate = useNavigate();
  const { isAuthenticated, user } = useAuthStore();
  const { initializeProxy } = useProxyStore();
  const { enableRegistration } = useFeatureFlags();
  const isAdmin = isAuthenticated && user != null && (user.role === 'admin' || user.role === 'super_admin');
  const searchPath = isAuthenticated ? '/main?tab=jav&sub=code' : '/login';

  useEffect(() => { initializeProxy(); }, [initializeProxy]);

  return (
    <div className="min-h-screen overflow-x-clip bg-stone-50 text-stone-900 dark:bg-[#0a0a0b] dark:text-stone-100">
      <UnifiedNavBar
        activeTab="jav"
        onTabChange={() => navigate('/main?tab=jav')}
        isAuthenticated={isAuthenticated}
        user={user}
        isAdmin={isAdmin}
      />
      <main className="mx-auto max-w-5xl px-4 py-16 sm:px-6 sm:py-24">
        <section className="mx-auto max-w-2xl text-center">
          <p className="mb-3 text-sm font-semibold tracking-widest text-amber-600 dark:text-amber-400">ATLAS · JAV SEARCH</p>
          <h1 className="text-4xl font-bold tracking-tight sm:text-5xl">专注 JAV 搜索</h1>
          <p className="mt-5 text-base leading-7 text-stone-600 dark:text-stone-400">
            按番号、演员或标题查找作品。集中展示封面、预览图片、作品资料和多来源磁力搜索结果。
          </p>
          <Link
            to={searchPath}
            className="mt-8 inline-flex items-center justify-center gap-2 rounded-xl bg-amber-500 px-6 py-3 font-semibold text-stone-950 transition-colors hover:bg-amber-400"
          >
            <Search className="h-5 w-5" />
            进入 JAV 搜索
          </Link>
        </section>
        <section className="mt-16 grid gap-4 sm:grid-cols-3" aria-label="主要功能">
          {[
            { icon: Search, title: '番号与标题', body: '精准查询番号，或使用标题关键词查找作品。', sub: 'code' },
            { icon: Users, title: '演员搜索', body: '检索演员资料并浏览其关联作品。', sub: 'actress' },
            { icon: Images, title: '封面与作品资料', body: '查看作品封面、可用预览图及发行资料。', sub: 'code' },
          ].map(({ icon: Icon, title, body, sub }) => (
            <Link
              key={title}
              to={isAuthenticated ? `/main?tab=jav&sub=${sub}` : '/login'}
              className="rounded-2xl border border-stone-200 bg-white p-5 shadow-sm transition-colors hover:border-amber-400 dark:border-stone-800 dark:bg-stone-900 dark:hover:border-amber-600"
            >
              <Icon className="mb-3 h-6 w-6 text-amber-600 dark:text-amber-400" />
              <h2 className="font-semibold">{title}</h2>
              <p className="mt-2 text-sm leading-6 text-stone-600 dark:text-stone-400">{body}</p>
            </Link>
          ))}
        </section>
        <p className="mt-8 flex items-center justify-center gap-2 text-center text-sm text-stone-500 dark:text-stone-400">
          <Magnet className="h-4 w-4" /> 支持 JAV 多源磁力检索；实际资源以搜索源返回结果为准。
        </p>
      </main>
      <SiteFooter enableRegistration={enableRegistration} />
      <FeedbackButton />
    </div>
  );
};

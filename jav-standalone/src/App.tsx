import { FormEvent, useCallback, useEffect, useMemo, useState } from 'react';
import {
  Search, Magnet, Copy, ExternalLink, RefreshCw,
  LogOut, Settings2, X, Film, ImageOff, CalendarDays, Clock3,
  Check, Eye, EyeOff, ChevronLeft, ChevronRight, Link2, ShieldCheck, Github
} from 'lucide-react';
import { Browser } from '@capacitor/browser';
import { App as NativeApp } from '@capacitor/app';
import { Capacitor, CapacitorHttp } from '@capacitor/core';

type SearchMode = 'code' | 'actress' | 'title';

type MagnetItem = {
  name: string;
  size: string;
  date: string;
  magnet: string;
  isHD: boolean;
};

type Work = { code: string; title: string; cover?: string; date?: string; actress?: string };
type Actress = { id: string; name: string; cover?: string; birthday?: string; detailUrl?: string };
type SourceCard = { id: string; name: string; subtitle?: string | null; description?: string | null; url: string };

type JavDetail = {
  code: string;
  title: string;
  cover?: string;
  releaseDate?: string;
  duration?: string;
  director?: string;
  maker?: string;
  publisher?: string;
  series?: string;
  tags?: string[];
  actresses?: string[];
  magnets?: MagnetItem[];
  detailUrl?: string;
  fromLibrary?: boolean;
  sampleImages?: string[];
  screenshots?: string[];
  samples?: string[];
  previewImages?: string[];
};

type SearchResult = {
  detail?: JavDetail;
  results?: SourceCard[];
  actresses?: Actress[];
  actressWorks?: Work[];
  fallback?: string;
  searchErrorKind?: string;
  errors?: Record<string, string | null>;
  magnetFetchFailed?: boolean;
};
type ApiEnvelope<T> = { success: boolean; data?: T; message?: string; error?: { message?: string; code?: string } };

const DEFAULT_API = 'https://atlas.wort.uk/api';
const API_KEY = 'jav-standalone:api:v1';
const TOKEN_KEY = 'jav-standalone:auth:v1';
const HISTORY_KEY = 'jav-standalone:recent:v1';
const OAUTH_PENDING_KEY = 'jav-standalone:oauth-pending:v1';
const OAUTH_CALLBACKS = new Set(['javsearch:', 'atlas:']);

function stored(key: string, fallback = ''): string {
  try { return localStorage.getItem(key) || fallback; } catch { return fallback; }
}
function normalizeApi(value: string): string {
  const cleaned = value.trim().replace(/\/+$/, '');
  const url = new URL(cleaned);
  if (url.protocol !== 'https:' && !(url.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(url.hostname))) {
    throw new Error('API 地址必须使用 HTTPS');
  }
  if (url.username || url.password) throw new Error('API 地址不能包含用户名或密码');
  return url.origin + (url.pathname.endsWith('/api') ? url.pathname : url.pathname.replace(/\/+$/, '') + '/api');
}
async function send<T>(api: string, path: string, token: string | null, body?: unknown): Promise<T> {
  const method = body === undefined ? 'GET' : 'POST';
  const headers: Record<string, string> = {
    'Accept': 'application/json',
    ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    ...(token ? { 'Authorization': 'Bearer ' + token } : {})
  };
  try {
    let status: number;
    let result: ApiEnvelope<T>;
    if (Capacitor.isNativePlatform()) {
      // Android 内置 HTTP Bridge：独立本地 WebView 的 https://localhost 源不能通过
      // 旧 Atlas Worker 的 CORS 白名单，原生请求避开浏览器 CORS 但仍验证 HTTPS。
      const response = await CapacitorHttp.request({
        url: api + path,
        method,
        headers,
        ...(body === undefined ? {} : { data: body }),
        connectTimeout: 27000,
        readTimeout: 27000,
        responseType: 'json'
      });
      status = response.status;
      result = (typeof response.data === 'string' ? JSON.parse(response.data) : response.data) as ApiEnvelope<T>;
    } else {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 27000);
      try {
        const response = await fetch(api + path, {
          method, headers,
          ...(body === undefined ? {} : { body: JSON.stringify(body) }),
          signal: controller.signal
        });
        status = response.status;
        result = await response.json() as ApiEnvelope<T>;
      } finally { clearTimeout(timeout); }
    }
    if (status < 200 || status >= 300 || !result.success || result.data == null) {
      if (status === 401) throw new Error('登录已失效，请退出后重新登录');
      throw new Error(result.error?.message || result.message || '请求失败');
    }
    return result.data;
  } catch (err) {
    if (err instanceof Error && (err.name === 'AbortError' || err.message.includes('timeout'))) {
      throw new Error('连接超时，请检查网络或搜索源状态');
    }
    throw err;
  }
}

function safeUrl(input?: string, base?: string): string {
  if (!input) return '';
  try {
    const parsed = new URL(input, base || 'https://www.javbus.com');
    return parsed.protocol === 'https:' ? parsed.href : '';
  } catch { return ''; }
}
function getImage(api: string, input?: string, base?: string): string {
  const normalized = safeUrl(input, base);
  return normalized ? api + '/jav/proxy-image?url=' + encodeURIComponent(normalized) : '';
}

function Poster({ api, src, base, alt, className, onClick }: {
  api: string; src?: string; base?: string; alt: string; className?: string; onClick?: () => void
}) {
  const [failed, setFailed] = useState(false);
  const uri = getImage(api, src, base);
  useEffect(() => setFailed(false), [uri]);
  if (!uri || failed) return <div className={'missing-poster ' + (className || '')}><ImageOff size={24}/></div>;
  return <img className={className} loading="lazy" src={uri} alt={alt} onError={() => setFailed(true)} onClick={onClick} />;
}

async function openWeb(url: string) {
  const target = safeUrl(url);
  if (!target) return;
  try { await Browser.open({ url: target }); }
  catch { window.open(target, '_blank', 'noopener,noreferrer'); }
}

function previewUrls(detail: JavDetail): string[] {
  const candidates = [
    ...(detail.sampleImages || []), ...(detail.screenshots || []),
    ...(detail.samples || []), ...(detail.previewImages || [])
  ];
  return [...new Set(candidates.map(item => safeUrl(item, detail.detailUrl)).filter(Boolean))];
}

export function App() {
  const [api, setApi] = useState(() => stored(API_KEY, DEFAULT_API));
  const [apiDraft, setApiDraft] = useState(api);
  const [token, setToken] = useState<string | null>(() => stored(TOKEN_KEY) || null);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [loginBusy, setLoginBusy] = useState(false);
  const [oauthBusy, setOauthBusy] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [mode, setMode] = useState<SearchMode>('code');
  const [query, setQuery] = useState('');
  const [searchBusy, setSearchBusy] = useState(false);
  const [searched, setSearched] = useState(false);
  const [data, setData] = useState<SearchResult | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [showAllMagnets, setShowAllMagnets] = useState(false);
  const [viewer, setViewer] = useState<string[] | null>(null);
  const [viewerIndex, setViewerIndex] = useState(0);
  const [recent, setRecent] = useState<string[]>(() => {
    try { return JSON.parse(stored(HISTORY_KEY, '[]')) as string[]; } catch { return []; }
  });

  // GitHub 在系统浏览器完成授权；App 通过专用 deep link 收到一次性 code，
  // 再通过原生 HTTP 向已有 Atlas API 换取 token。只处理本站主动发起的授权。
  useEffect(() => {
    if (!Capacitor.isNativePlatform()) return;
    let disposed = false;
    let removeAppListener: (() => void) | undefined;
    let removeBrowserListener: (() => void) | undefined;
    let handledLink: string | null = null;

    const handleLink = (raw: string) => {
      let url: URL;
      try { url = new URL(raw); } catch { return; }
      if (!OAUTH_CALLBACKS.has(url.protocol) || url.hostname !== 'auth' || url.pathname !== '/callback') return;
      if (handledLink === raw) return;
      const pendingString = stored(OAUTH_PENDING_KEY);
      if (!pendingString) return;
      let pending: { api: string; at: number };
      try { pending = JSON.parse(pendingString) as { api: string; at: number }; }
      catch { return; }
      if (pending.api !== api || !Number.isFinite(pending.at) || Date.now() - pending.at > 10 * 60 * 1000) {
        try { localStorage.removeItem(OAUTH_PENDING_KEY); } catch { /* ignore */ }
        return;
      }
      handledLink = raw;
      try { localStorage.removeItem(OAUTH_PENDING_KEY); } catch { /* ignore */ }
      void Browser.close().catch(() => undefined);
      const oauthError = url.searchParams.get('error');
      const code = url.searchParams.get('code');
      if (oauthError || !code) {
        setError(oauthError ? 'GitHub 授权失败：' + oauthError : '未收到 GitHub 登录凭证');
        setOauthBusy(false);
        return;
      }
      setError('');
      setOauthBusy(true);
      void (async () => {
        try {
          const result = await send<{token: string; user: {username?: string}}>(
            api, '/auth/oauth/exchange', null, { code }
          );
          if (!result.token) throw new Error('兑换登录凭证失败：服务器没有返回令牌');
          if (disposed) return;
          setToken(result.token);
          try { localStorage.setItem(TOKEN_KEY, result.token); } catch { /* ignore */ }
          setPassword('');
          setNotice('GitHub 登录成功，可以开始搜索');
        } catch (err) {
          if (!disposed) setError(err instanceof Error ? err.message : 'GitHub 登录失败');
        } finally {
          if (!disposed) setOauthBusy(false);
        }
      })();
    };

    void (async () => {
      const listener = await NativeApp.addListener('appUrlOpen', ({url}) => {
        if (!disposed) handleLink(url);
      });
      if (disposed) { void listener.remove(); return; }
      removeAppListener = () => void listener.remove();
      const launchUrl = await NativeApp.getLaunchUrl().catch(() => null);
      if (!disposed && launchUrl?.url) handleLink(launchUrl.url);
    })().catch(err => {
      if (!disposed) setError(err instanceof Error ? err.message : '无法监听登录回调');
    });
    void Browser.addListener('browserFinished', () => {
      if (!disposed) setOauthBusy(false);
    }).then(listener => {
      if (disposed) { void listener.remove(); return; }
      removeBrowserListener = () => void listener.remove();
    }).catch(() => undefined);

    return () => {
      disposed = true;
      removeAppListener?.();
      removeBrowserListener?.();
    };
  }, [api]);

  async function loginWithGitHub() {
    if (loginBusy || oauthBusy) return;
    if (!Capacitor.isNativePlatform()) {
      setError('GitHub 原生回跳登录仅支持安装后的 Android 客户端');
      return;
    }
    setError('');
    setOauthBusy(true);
    try {
      // 旧生产后端只支持 atlas://，新后端支持 javsearch://；
      // 能力检测失败时兼容旧端，不会因为尚未部署后端补丁而禁用登录。
      let client = 'app';
      try {
        const supported = await send<{javsearch?: boolean}>(api, '/auth/github/capabilities', null);
        if (supported.javsearch) client = 'javsearch';
      } catch { /* 旧 Atlas 服务器：回退 atlas:// 回调 */ }
      try { localStorage.setItem(OAUTH_PENDING_KEY, JSON.stringify({ api, at: Date.now() })); }
      catch { throw new Error('无法保存登录状态，请检查应用存储权限'); }
      await Browser.open({ url: api + '/auth/github?client=' + client });
      if (client === 'app') {
        setNotice('使用现有 Atlas 登录服务。若出现“选择应用”，请选择 JAV Search 返回。');
      }
    } catch (err) {
      setOauthBusy(false);
      try { localStorage.removeItem(OAUTH_PENDING_KEY); } catch { /* ignore */ }
      setError(err instanceof Error ? err.message : '无法打开 GitHub 授权');
    }
  }

  const doSearch = useCallback(async (term: string, selection: SearchMode) => {
    const value = term.trim();
    if (!value || searchBusy || !token) return;
    setQuery(value);
    setMode(selection);
    setData(null);
    setError('');
    setNotice('');
    setSearched(true);
    setShowAllMagnets(false);
    setSearchBusy(true);
    try {
      const result = await send<SearchResult>(api, '/search', token, {
        keyword: value, page: 1, pageSize: 20,
        majorCategoryId: 'jav_sources', javSubMode: selection
      });
      setData(result);
      const next = [value, ...recent.filter(item => item !== value)].slice(0, 12);
      setRecent(next);
      try { localStorage.setItem(HISTORY_KEY, JSON.stringify(next)); } catch { /* ignore */ }
      if (!result.detail && !result.actresses?.length && !result.actressWorks?.length && !result.results?.length) {
        setNotice(result.errors?.search || '未找到对应作品或搜索源');
      } else if (result.searchErrorKind === 'SOURCE_UNAVAILABLE') {
        setNotice('部分外部数据源暂时不可用，可以尝试打开下方的源站搜索');
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : '搜索发生未知错误');
    } finally {
      setSearchBusy(false);
    }
  }, [api, token, searchBusy, recent]);

  async function doLogin(event: FormEvent) {
    event.preventDefault();
    if (loginBusy || !username.trim() || !password) return;
    setError('');
    setLoginBusy(true);
    try {
      const result = await send<{ token: string; user: { username?: string } }>(
        api, '/auth/login', null, { identifier: username.trim(), password }
      );
      if (!result.token) throw new Error('服务器没有返回登录令牌');
      setToken(result.token);
      try { localStorage.setItem(TOKEN_KEY, result.token); } catch { /* ignore */ }
      setPassword('');
      setNotice('已登录，可以搜索 JAV 番号');
    } catch (err) {
      setError(err instanceof Error ? err.message : '登录失败');
    } finally {
      setLoginBusy(false);
    }
  }

  function logout() {
    setToken(null);
    setData(null);
    setSearched(false);
    setError('');
    setPassword('');
    try { localStorage.removeItem(TOKEN_KEY); } catch { /* ignore */ }
  }

  function saveApi() {
    try {
      const value = normalizeApi(apiDraft);
      setApi(value);
      try { localStorage.setItem(API_KEY, value); } catch { /* ignore */ }
      setSettingsOpen(false);
      if (value !== api) logout();
    } catch (err) { setError(err instanceof Error ? err.message : '无效的 API 地址'); }
  }

  async function copyMagnet(magnet: string) {
    try { await navigator.clipboard.writeText(magnet); setNotice('磁力链接已复制'); }
    catch { setNotice('复制失败，请长按磁力链接手动复制'); }
  }

  const detail = data?.detail;
  const screenshots = useMemo(() => detail ? previewUrls(detail) : [], [detail]);
  const magnets = detail?.magnets || [];
  const visibleMagnets = showAllMagnets ? magnets : magnets.slice(0, 7);

  return <div className="app">
    <header className="topbar">
      <div className="topbar-inner">
        <button className="brand" onClick={() => { setData(null); setSearched(false); setError(''); }} aria-label="返回搜索主页">
          <span className="brand-logo"><Film size={19}/></span>
          <span>JAV <strong>Search</strong></span>
        </button>
        <div className="top-actions">
          <button className="icon-button" title="API 设置" aria-label="API 设置" onClick={() => { setApiDraft(api); setSettingsOpen(true); }}><Settings2 size={20}/></button>
          {token && <button className="icon-button" title="退出登录" aria-label="退出登录" onClick={logout}><LogOut size={20}/></button>}
        </div>
      </div>
    </header>
    <main className="main">
      {!token ? (
        <section className="auth">
          <div className="hero-mark"><Search size={30}/></div>
          <p className="eyebrow">独立 JAV 搜索应用</p>
          <h1>只搜索你需要的作品</h1>
          <p className="muted centered">独立 Android 客户端，不包含原 Atlas 的其他分类、社区与管理入口。</p>
          <form className="login-card" onSubmit={doLogin}>
            <h2>登录搜索服务</h2>
            <p className="muted">使用已有 Atlas 搜索服务账号。服务端搜索需要登录令牌。</p>
            <label htmlFor="identifier">用户名或邮箱</label>
            <input id="identifier" autoComplete="username" value={username} onChange={e => setUsername(e.target.value)} placeholder="用户名 / 邮箱" required/>
            <label htmlFor="password">密码</label>
            <div className="password-control">
              <input id="password" type={showPassword ? 'text' : 'password'} autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} placeholder="输入密码" required/>
              <button type="button" aria-label={showPassword ? '隐藏密码' : '显示密码'} onClick={() => setShowPassword(value => !value)}>{showPassword ? <EyeOff size={18}/> : <Eye size={18}/>}</button>
            </div>
            <button type="submit" className="primary full" disabled={loginBusy || oauthBusy}>{loginBusy ? '登录中…' : '登录并开始搜索'}</button>
            <div className="login-divider"><span>或使用</span></div>
            <button type="button" className="github-login full" disabled={loginBusy || oauthBusy} onClick={() => void loginWithGitHub()}>
              <Github size={19}/> {oauthBusy ? '正在等待 GitHub 授权…' : '使用 GitHub 登录'}
            </button>
            <div className="service-info"><ShieldCheck size={15}/> API: {api}</div>
          </form>
        </section>
      ) : (
        <>
          <section className="search-block">
            <div className="section-header">
              <div><p className="eyebrow">JAV · SEARCH</p><h1>影片搜索</h1></div>
              <div className="small-status"><span className="status-dot"/> 搜索服务</div>
            </div>
            <div className="mode-switch" role="tablist" aria-label="搜索类型">
              {([['code','番号'],['actress','演员'],['title','标题']] as const).map(([value,label]) => (
                <button key={value} role="tab" aria-selected={mode === value} className={mode === value ? 'selected' : ''} onClick={() => {setMode(value); setSearched(false); setData(null); setError('');}}>{label}</button>
              ))}
            </div>
            <form className="search-form" onSubmit={e => {e.preventDefault(); void doSearch(query, mode);}}>
              <Search size={20} className="search-icon"/>
              <input aria-label="搜索关键词" value={query} onChange={e => setQuery(e.target.value)} placeholder={mode === 'code' ? '输入番号，例如 ABC-123' : mode === 'actress' ? '输入演员姓名' : '输入作品标题关键词'}/>
              {query && <button className="clear-btn" type="button" onClick={() => setQuery('')} aria-label="清除"><X size={16}/></button>}
              <button type="submit" className="primary search-submit" disabled={searchBusy}>{searchBusy ? '搜索中' : '搜索'}</button>
            </form>
            {!searched && recent.length > 0 && <div className="recents"><span>最近搜索</span><div className="recent-list">{recent.slice(0,8).map(x => <button key={x} onClick={() => void doSearch(x,mode)}>{x}</button>)}</div></div>}
          </section>

          {searchBusy && <div className="loading-card"><RefreshCw className="spin" size={23}/><div>正在查询 JAV 资料与搜索源…</div></div>}
          {error && <div className="error-banner">{error}<button onClick={() => setError('')}><X size={16}/></button></div>}
          {notice && <div className="notice-banner">{notice}<button onClick={() => setNotice('')}><X size={16}/></button></div>}

          {!searchBusy && detail && <section className="panel">
            <div className="panel-title"><h2>作品详情</h2><span className="chip">{detail.code}</span></div>
            <div className="detail-head">
              <Poster api={api} src={detail.cover} base={detail.detailUrl} alt={detail.title} className="detail-cover" onClick={() => {const uri=safeUrl(detail.cover,detail.detailUrl); if(uri){setViewer([uri]);setViewerIndex(0);}}}/>
              <div className="detail-meta">
                <h3>{detail.title || detail.code}</h3>
                <div className="metadata">
                  {detail.releaseDate && <span><CalendarDays size={14}/> {detail.releaseDate}</span>}
                  {detail.duration && <span><Clock3 size={14}/> {detail.duration}</span>}
                  {detail.maker && <span>制作商：{detail.maker}</span>}
                  {detail.publisher && <span>发行商：{detail.publisher}</span>}
                  {detail.series && <span>系列：{detail.series}</span>}
                  {detail.director && <span>导演：{detail.director}</span>}
                </div>
              </div>
            </div>
            {(detail.actresses?.length || 0) > 0 && <div className="detail-section"><h4>演员</h4><div className="tag-list">{detail.actresses!.map(name=><button className="tag" key={name} onClick={()=>void doSearch(name,'actress')}>{name}</button>)}</div></div>}
            {(detail.tags?.length || 0) > 0 && <div className="detail-section"><h4>标签</h4><div className="tag-list">{detail.tags!.map(tag=><span className="tag subtle" key={tag}>{tag}</span>)}</div></div>}
            <div className="detail-section">
              <div className="inline-title"><h4>作品预览图片</h4><span>{screenshots.length ? screenshots.length + ' 张' : '暂无数据'}</span></div>
              {screenshots.length ? <div className="gallery">{screenshots.map((img,i) => <button key={img} onClick={() => {setViewer(screenshots);setViewerIndex(i);}}><Poster api={api} src={img} alt={'预览图 '+(i+1)} className="gallery-image"/></button>)}</div> : <p className="muted">当前搜索接口未提供该作品的预览截图，避免错误显示封面或无关图片。</p>}
            </div>
            <div className="detail-section">
              <div className="inline-title"><h4><Magnet size={17}/> 磁力资源</h4><span>{magnets.length} 条</span></div>
              {data?.magnetFetchFailed && <p className="muted">磁力数据源可能暂时不可用，并不代表该作品没有资源。</p>}
              {!magnets.length && <p className="muted">暂未找到磁力链接。可以在下方使用其他源站搜索。</p>}
              <div className="magnet-list">
                {visibleMagnets.map((m,i) => <div className="magnet-item" key={m.magnet+i}>
                  <div className="magnet-info"><div className="magnet-name">{m.name || '磁力资源'} {m.isHD && <span className="hd">HD</span>}</div><div className="magnet-sub">{m.size || '大小未知'}{m.date ? ' · '+m.date : ''}</div></div>
                  <div className="magnet-actions"><button title="复制磁力" onClick={()=>void copyMagnet(m.magnet)}><Copy size={17}/></button><a title="尝试在外部应用打开" href={m.magnet}><ExternalLink size={17}/></a></div>
                </div>)}
              </div>
              {magnets.length>7 && <button className="sub-action" onClick={()=>setShowAllMagnets(v=>!v)}>{showAllMagnets?'收起磁力列表':'显示全部 '+magnets.length+' 条磁力资源'}</button>}
            </div>
            {detail.detailUrl && <button className="sub-action" onClick={()=>void openWeb(detail.detailUrl!)}>查看来源详情 <ExternalLink size={15}/></button>}
          </section>}

          {!searchBusy && (data?.actresses?.length || 0)>0 && <section className="panel">
            <div className="panel-title"><h2>演员资料</h2><span className="chip">{data!.actresses!.length} 条</span></div>
            <div className="works-grid">{data!.actresses!.map(person=><article className="work-card" key={person.id}>
              <Poster api={api} src={person.cover} alt={person.name} className="work-cover"/>
              <div className="work-body"><strong>{person.name}</strong>{person.birthday && <small>{person.birthday}</small>}{person.detailUrl && <button onClick={()=>void openWeb(person.detailUrl!)} className="tiny-link">来源资料 <ExternalLink size={12}/></button>}</div>
            </article>)}</div>
          </section>}

          {!searchBusy && (data?.actressWorks?.length || 0)>0 && <section className="panel">
            <div className="panel-title"><h2>相关作品</h2><span className="chip">{data!.actressWorks!.length} 部</span></div>
            <div className="works-grid">{data!.actressWorks!.map(work=><button key={work.code} className="work-card" onClick={()=>void doSearch(work.code,'code')}>
              <Poster api={api} src={work.cover} alt={work.title} className="work-cover"/>
              <span className="work-body"><strong>{work.code}</strong><small>{work.title}</small></span>
            </button>)}</div>
          </section>}

          {!searchBusy && (data?.results?.length || 0)>0 && <section className="panel">
            <div className="panel-title"><h2>其他搜索源</h2><span className="chip">{data!.results!.length} 个</span></div>
            <p className="muted panel-desc">这里是源站搜索入口，并不表示已经取得对应作品的资源。</p>
            <div className="source-list">{data!.results!.map(source=><button key={source.id} className="source-item" onClick={()=>void openWeb(source.url)}>
              <span className="source-mark"><Link2 size={18}/></span>
              <span className="source-name"><strong>{source.name}</strong>{source.subtitle && <small>{source.subtitle}</small>}</span>
              <ExternalLink size={17}/></button>)}</div>
          </section>}
          {searched && !searchBusy && !data && !error && <div className="empty-state"><ImageOff size={30}/><p>没有找到结果</p></div>}
        </>
      )}
      <footer className="footer">JAV Search · 独立客户端 · 数据由已连接的搜索服务提供</footer>
    </main>

    {settingsOpen && <div className="dialog-backdrop" onClick={()=>setSettingsOpen(false)}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label="API 设置" onClick={e=>e.stopPropagation()}>
        <div className="dialog-title"><h2>搜索服务设置</h2><button onClick={()=>setSettingsOpen(false)}><X size={20}/></button></div>
        <p className="muted">可以切换至自己的 Atlas 兼容后端。更改服务地址后需要重新登录。</p>
        <label htmlFor="api-endpoint">后端 API 地址</label>
        <input id="api-endpoint" value={apiDraft} onChange={e=>setApiDraft(e.target.value)} placeholder="https://example.com/api" autoCapitalize="none"/>
        <button className="primary full" onClick={saveApi}><Check size={16}/> 保存</button>
        <p className="muted tiny">Android 页面完全打包在本地；此设置只决定搜索请求发送到哪里。</p>
      </div>
    </div>}

    {viewer && <div className="image-viewer" role="dialog" aria-modal="true" aria-label="图片预览">
      <div className="viewer-bar"><span>{viewerIndex+1} / {viewer.length}</span><button onClick={()=>setViewer(null)} aria-label="关闭预览"><X size={22}/></button></div>
      <Poster api={api} src={viewer[viewerIndex]} alt="作品图片" className="viewer-image"/>
      {viewer.length>1 && <div className="viewer-controls"><button aria-label="上一张" onClick={()=>setViewerIndex(i=>(i+viewer.length-1)%viewer.length)}><ChevronLeft size={28}/></button><button aria-label="下一张" onClick={()=>setViewerIndex(i=>(i+1)%viewer.length)}><ChevronRight size={28}/></button></div>}
    </div>}
  </div>;
}
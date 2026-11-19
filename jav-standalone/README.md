# JAV Search Standalone

独立 Android APK，不引用 Atlas 原前端，也不加载线上 Atlas 网站。

- Android 包名 `uk.wort.javsearch`；可与 `uk.wort.atlas` 同时安装。
- 仅包括登录、番号/演员/标题搜索、作品详情、封面、可用预览图片、磁力链接与多源站点跳转。
- 前端静态页面打包进 APK。**capacitor.config.json 不得添加 server.url**，否则会重新加载旧线上站点。
- 数据服务默认使用 `https://atlas.wort.uk/api`。可以在 App 登录页修改 API 地址；必须使用已部署 Atlas 兼容 API 的服务器。
- Atlas 后端的 `POST /api/search` 需要登录令牌。本应用通过 `POST /api/auth/login` 获取，不使用旧 Atlas 网站页面。
- 目前原 Atlas 后端主要返回封面，并不保证返回多张作品预览截图。前端仅展示服务器确实提供的 `sampleImages` / `screenshots` / `samples` / `previewImages`。
- 标题模式当前后端只返回多源站点卡片；不是独立的作品列表索引。

## GitHub 登录

- 登录页提供 GitHub 授权按钮；使用系统浏览器完成登录，App 监听 `appUrlOpen` 和冷启动回调。
- 收到的只是一次性 `code`，调用 `POST /api/auth/oauth/exchange` 换取登录态，不把长期令牌放在深链里。
- 旧 Atlas 服务器回调为 `atlas://auth/callback`，新 APK 也可处理；与原 Atlas 同时安装时，Android 可能弹出应用选择，需要选择 **JAV Search**。
- 当前分支的后端新增 `GET /api/auth/github/capabilities` 与 `client=javsearch`，部署该后端后独立 App 自动使用 `javsearch://auth/callback`，避免旧 Atlas 抢回调。**仅构建 APK 不会自动部署后端。**
- 后端授权仍使用现有 GitHub OAuth App 的 HTTPS 回调路径，不需更改 GitHub OAuth App 配置。
- CI 使用 `scripts/configure-oauth.mjs` 向动态生成的 AndroidManifest 注入两个回调 scheme。

## 本地开发
```bash
cd jav-standalone
npm install
npm run build
npx cap add android
npx cap sync android
cd android
./gradlew assembleDebug
```

## CI
GitHub Actions：`jav-standalone-debug.yml`。只编译 Debug，绝不推送正式 Release 或部署 Worker。

## 声明
资源是否可用取决于外部服务；只使用有权访问和分享的资料与下载链接。

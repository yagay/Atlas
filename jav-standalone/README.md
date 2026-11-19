# JAV Search Standalone

独立 Android APK，不引用 Atlas 原前端，也不加载线上 Atlas 网站。

- Android 包名 `uk.wort.javsearch`；可与 `uk.wort.atlas` 同时安装。
- 仅包括登录、番号/演员/标题搜索、作品详情、封面、可用预览图片、磁力链接与多源站点跳转。
- 前端静态页面打包进 APK。**capacitor.config.json 不得添加 server.url**，否则会重新加载旧线上站点。
- 数据服务默认使用 `https://atlas.wort.uk/api`。可以在 App 登录页修改 API 地址；必须使用已部署 Atlas 兼容 API 的服务器。
- Atlas 后端的 `POST /api/search` 需要登录令牌。本应用通过 `POST /api/auth/login` 获取，不使用旧 Atlas 网站页面。
- 目前原 Atlas 后端主要返回封面，并不保证返回多张作品预览截图。前端仅展示服务器确实提供的 `sampleImages` / `screenshots` / `samples` / `previewImages`。
- 标题模式当前后端只返回多源站点卡片；不是独立的作品列表索引。

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

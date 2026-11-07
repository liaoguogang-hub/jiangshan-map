# 江山时序 · Android 封装

把 [江山时序](https://deanchen.me/jiangshan-map/) 这幅中国历史交互舆图打成可安装的 Android App，
**完全离线**运行。

- 上游站点是零构建的纯静态资源（`index.html` + `css/` + `js/` + `data/` + `geojson/`），
  所以不需要 npm build，直接把仓库根目录的资源复制进 `assets/www` 即可。
- 通过 `androidx.webkit` 的 `WebViewAssetLoader` 以
  `https://appassets.androidplatform.net/` 提供资源，拿到正规 https 来源，
  `fetch` / `localStorage` 行为与线上一致，不需要放宽 `file://` 限制。
- **不声明 `android.permission.INTERNET`**，App 从根上无法联网；
  站内的统计脚本与 CDN 字体会被 WebViewClient 直接拦截，不会卡在超时上。

## 构建

```bash
# 1. 准备站点资源（从仓库根目录执行）
bash android/prepare_assets.sh

# 2. 构建
cd android
./gradlew assembleDebug

# 3. 安装
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`（约 37 MB）。

## 深链

App 注册了 `jiangshan://atlas` scheme，可直接跳到任意年份与地点：

```bash
adb shell am start -a android.intent.action.VIEW \
  -d "jiangshan://atlas?year=741&place=%E9%95%BF%E5%AE%89" com.jiangshan.atlas
```

对应站点自身的 `?year=&place=&person=` 协议。`launchMode` 为 `singleTask`，
`onNewIntent` 里会重新载入页面，因此 App 已在前台时深链同样生效。

## 工具链

| 项 | 版本 |
|---|---|
| Gradle | 9.8.1 |
| AGP | 9.4.1 |
| Kotlin | AGP 9 内置（无需单独声明 `org.jetbrains.kotlin.android`） |
| compileSdk / targetSdk | 37 |
| minSdk | 24 |

> `gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 指向腾讯云镜像，
> `settings.gradle.kts` 里的仓库也按「阿里云优先」排序——国内直连
> `services.gradle.org` 和 `repo.maven.apache.org` 会超时或挂死。

## 注意

- `android/app/src/main/assets/www/` 是**生成目录**，已加入 `android/.gitignore`，
  由 `prepare_assets.sh` 从仓库根目录复制而来，不要手工提交。
- 站点里 `assets/hero_demo_*.webp` 与 `assets/preview_*.webp` 只用于 README 演示
  （约 17 MB），打包时已跳过。

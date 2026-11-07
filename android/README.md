# 江山时序 · Android 封装

把 [江山时序](https://deanchen.me/jiangshan-map/) 这幅中国历史交互舆图打成可安装的 Android App，
网页内容**完全离线**运行，并额外内置一个 **MCP 考据助手**：用自然语言提问，AI 把问题考据成
「年份 + 地点/人物」，再把结果画到地图上。

- 上游站点是零构建的纯静态资源（`index.html` + `css/` + `js/` + `data/` + `geojson/`），
  所以不需要 npm build，直接把仓库根目录的资源复制进 `assets/www` 即可。
- 网页通过 `androidx.webkit` 的 `WebViewAssetLoader` 以
  `https://appassets.androidplatform.net/` 提供，拿到正规 https 来源，
  `fetch` / `localStorage` 行为与线上一致，不需要放宽 `file://` 限制。
- `WebViewClient` 会把所有非 `appassets.androidplatform.net` 的请求直接拦截，
  站内统计脚本与 CDN 字体不会卡在超时上。

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

---

## MCP 考据助手

### 它做什么

主界面右下角有一个「考据」按钮，点开是一个对话框，可以输入自然语言问题，例如：

- 雪拥蓝关马不前是哪一年，韩愈当时被贬在哪里
- 李白和杜甫在哪里相遇
- 赤壁在今天的哪里

App 把问题发给 PC 上的 MCP 服务，服务端用 LLM 考据出年份与地点，
再调用舆图自带的 `inspect_place` / `inspect_person` / `search_atlas` 取结构化数据，
返回：

```
answer   ← AI 的考据解答
year     ← 819
place    ← 蓝关,潮州
atlas.era            ← 唐宪宗·元和十四年
atlas.polity         ← 唐
atlas.regions        ← [关内道]
atlas.modernProvince ← 陕西省
atlas.coord          ← [109.48, 34.03]
atlas.lore           ← 两千年沿革文字
atlas.url            ← 深链
```

点「在地图中打开」后，App 取出 `url` 的 query 部分，在 WebView 里加载，
地图会跳到对应年份并画出人物/路线行迹。

### 架构

```
手机 App（MCP 客户端）                  PC（MCP 服务端）
─────────────────────                  ────────────────────────────────
WebSocketClient  ── ws://host:port/mcp ──►  scripts/mcp_ws_server.js
  · 手写 RFC 6455 帧                        · 复用 scripts/mcp_server.js 的
  · 独立 IO 线程，不阻塞主线程                沙箱与控制器
  · 客户端帧带掩码                          · MCP JSON-RPC 2.0
                                          · ask_question 工具调 LLM 做考据
McpClient                                · 需要桌面版 MCP 时仍可用
  · initialize / tools/list / tools/call    scripts/mcp_server.js（stdio）
```

### 启动服务端

```bash
# 只测舆图工具（不需要 LLM）
node scripts/mcp_ws_server.js --port 8798 --host 0.0.0.0

# 带自然语言考据（任何 OpenAI 兼容或 Anthropic 兼容端点）
LLM_BASE_URL=https://api.deepseek.com \
LLM_API_KEY=sk-xxx \
LLM_MODEL=deepseek-chat \
node scripts/mcp_ws_server.js --port 8798 --host 0.0.0.0
```

健康检查：`curl http://127.0.0.1:8798/health`

环境变量说明：

| 变量 | 用途 |
|---|---|
| `LLM_BASE_URL` | 端点根地址，如 `https://api.deepseek.com` |
| `LLM_API_KEY` | API key（也接受 `ANTHROPIC_AUTH_TOKEN` / `ANTHROPIC_API_KEY`） |
| `LLM_MODEL` | 模型名，留空用端点默认 |
| `LLM_TRANSPORT` | 强制 `openai` 或 `anthropic`，默认按 URL 自动判断 |

> Anthropic 兼容端点（如 `https://api.minimaxi.com/anthropic`）走 `/v1/messages`；
> 其余走 `/v1/chat/completions`。

### 手机连接

有两种方式：

**A. USB 隧道（推荐，不暴露端口）**

```bash
adb reverse tcp:8798 tcp:8798
# 然后在 App 里填 127.0.0.1 / 8798
```

**B. 局域网**

```bash
node scripts/mcp_ws_server.js --port 8798 --host 0.0.0.0
# 在 App 里填 PC 的局域网 IP，例如 192.168.1.10 / 8798
```

### 权限说明

`AndroidManifest.xml` 里声明了 `android.permission.INTERNET`，**仅用于连接你自己 PC 上的
MCP 服务**（Android 连回环地址也需要这个权限，否则报 `EPERM`）。
网页内容本身仍然完全离线：`WebViewClient` 会拦截一切非 `appassets` 的请求。

---

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
>
> AGP 9 有两处 API 变更：不再需要 `org.jetbrains.kotlin.android` 插件；
> `kotlinOptions { jvmTarget }` 改用 `kotlin { compilerOptions { } }`。

## 注意

- `android/app/src/main/assets/www/` 是**生成目录**，已加入 `android/.gitignore`，
  由 `prepare_assets.sh` 从仓库根目录复制而来，不要手工提交。
- 站点里 `assets/hero_demo_*.webp` 与 `assets/preview_*.webp` 只用于 README 演示
  （约 17 MB），打包时已跳过。
- APK 用的是 debug 签名，要分发得换成自己的 keystore。

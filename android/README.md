# 江山时序 · Android

把 [江山时序](https://deanchen.me/jiangshan-map/) 这幅中国历史交互舆图封装成 Android App，
网页内容**完全离线**，并内置一个**考据 Agent**：用自然语言提问，模型把问题拆解成年份与地点，
舆图再给出真实的年号、政区、坐标与两千年沿革。

> 上游站点：[DeanChensj/jiangshan-map](https://github.com/DeanChensj/jiangshan-map)（GPL-3.0）
> 本目录是 Android 封装层的全部源码。

## 版本

| 版本 | 内容 |
|---|---|
| [1.2.0](CHANGELOG.md) | 手机内置 Agent，不依赖电脑；支持 DeepSeek / MiniMax / Kimi / 通义 / OpenAI |
| 1.1.0 | 电脑端 MCP 考据助手（已从 App 移除，脚本保留） |
| 1.0.0 | 离线封装 + 深链 |

安装包：见 [Releases](https://github.com/liaoguogang-hub/jiangshan-map/releases)。

## 架构

```
MainActivity
├── WebView（assets/www，完全离线）
│     └── window.atlasAPI ← 站点自带：inspect / search / openPerson / resolvePlace
└── AgentPanel（浮层，右下角「考据」）
      │
      ├── LlmConfig     厂商预设 + 分厂商持久化
      ├── LlmClient     OpenAI 兼容 chat completions（零第三方依赖）
      ├── AtlasBridge   evaluateJavascript 调 window.atlasAPI
      └── AgentEngine   编排：模型解析 → 工具分派 → 结构化结果
```

**分工是关键**：模型只负责「听懂问题」，输出

```json
{"answer": "...", "tool": "inspect_place", "year": 744, "place": "洛阳,梁宋,东鲁"}
```

年号、政权、政区、坐标、沿革文字**全部由舆图提供** —— 模型碰不到这些字段，想编也编不出来。
这也是为什么答案里既有模型的叙述，又有精确到小数点后两位的坐标。

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

产物约 37 MB。

## 使用考据 Agent

1. 点右下角「考据」
2. 选厂商（地址与模型名自动带出），填 API Key，点「保存」
3. 输入问题，或点示例按钮

支持的厂商：

| 厂商 | 接口 | 默认模型 |
|---|---|---|
| DeepSeek | `https://api.deepseek.com` | `deepseek-chat` |
| MiniMax | `https://api.minimaxi.com` | `MiniMax-M3.1-Flash` |
| Kimi (Moonshot) | `https://api.moonshot.cn/v1` | `moonshot-v1-8k` |
| 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
| 自定义 | 任意 OpenAI 兼容网关 | — |

配置按厂商独立保存，切换不丢已填的 Key。模型报错（额度用尽 / Key 无效 / 模型名不对）
会把厂商原文显示在界面上。

## 深链

```bash
adb shell am start -a android.intent.action.VIEW \
  -d "jiangshan://atlas?year=741&place=%E9%95%BF%E5%AE%89" com.jiangshan.atlas
```

对应站点自身的 `?year=&place=&person=` 协议。`launchMode` 为 `singleTask`，
`onNewIntent` 里会重新载入，所以 App 已在前台时深链同样生效。

## 电脑端 MCP（可选）

App 已经不需要电脑了，但 `scripts/mcp_server.js`（stdio）与 `scripts/mcp_ws_server.js`
（WebSocket）仍然保留，可以给 Claude Desktop / Cursor 等桌面 MCP 客户端用：

```bash
# 桌面客户端（Claude Desktop / Cursor）指向这个 stdio 服务
node scripts/mcp_server.js

# 或者起 WebSocket 版，多一个 ask_question 工具
LLM_BASE_URL=https://api.deepseek.com LLM_API_KEY=sk-xxx LLM_MODEL=deepseek-chat \
  node scripts/mcp_ws_server.js --port 8798 --host 0.0.0.0
```

## 工具链

| 项 | 版本 |
|---|---|
| Gradle | 9.8.1 |
| AGP | 9.4.1 |
| Kotlin | AGP 9 内置（无需单独声明 `org.jetbrains.kotlin.android`） |
| compileSdk / targetSdk | 37 |
| minSdk | 24 |

> `gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 指向腾讯云镜像，
> `settings.gradle.kts` 的仓库按「阿里云优先」排序——国内直连 `services.gradle.org`
> 和 `repo.maven.apache.org` 会超时或挂死。
>
> AGP 9 两处 API 变更：不再需要 `org.jetbrains.kotlin.android` 插件；
> `kotlinOptions { jvmTarget }` 改用 `kotlin { compilerOptions { } }`。

## 权限

`INTERNET` 只用于调用你配置的模型服务。网页内容仍完全离线：
`WebViewClient` 会拦截所有非 `appassets.androidplatform.net` 的请求，
站点自带的统计脚本与 CDN 字体不会卡在超时上。

## 注意

- `app/src/main/assets/www/` 是**生成目录**，已加入 `android/.gitignore`，
  由 `prepare_assets.sh` 从仓库根目录复制而来，不要手工提交。
- 站点里 `assets/hero_demo_*.webp` 与 `assets/preview_*.webp` 只用于 README 演示
  （约 17 MB），打包时已跳过。
- APK 使用 debug 签名，自行分发请换成自己的 keystore。

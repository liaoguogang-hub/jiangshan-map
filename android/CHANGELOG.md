# 变更记录

本工程把开源站点 [江山时序](https://deanchen.me/jiangshan-map/) 封装成 Android App。
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)；`versionCode` 每次发版 +1。

## [1.3.0] — 2026-11-08

**入口移到顶部工具栏**

- 站点顶部工具栏（`.control-strip`）里注入两个按钮，位于「图层」左侧：
  「设置」「考据」，沿用站点自己的 `action-btn subtle` 样式，明暗主题一致
- 面板拆成两种模式：
  - **设置** — 仅模型服务配置（厂商、地址、Key、模型名），紧凑卡片贴顶
  - **考据** — 仅提问与结果，全高
- 去掉右下角浮动「考据」按钮
- 未配置 Key 时点「考据」会自动引导到设置页

## [1.2.0] — 2026-11-08

**手机内置 Agent：不再依赖电脑**

- 新增 `agent/` 包，把考据能力整体搬进 APK：
  - `LlmConfig` — 厂商预设 + 分厂商持久化配置
  - `LlmClient` — OpenAI 兼容的 chat completions 客户端（零第三方依赖）
  - `AtlasBridge` — 通过 `evaluateJavascript` 调 WebView 里的 `window.atlasAPI`
  - `AgentEngine` — 编排「模型听懂问题 → 舆图查准史料」
  - `AgentPanel` — MainActivity 内的浮层 UI
- 支持主流模型，切换厂商只需点一下：
  | 厂商 | 接口 | 默认模型 |
  |---|---|---|
  | DeepSeek | `https://api.deepseek.com` | `deepseek-chat` |
  | MiniMax | `https://api.minimaxi.com` | `MiniMax-M3.1-Flash` |
  | Kimi (Moonshot) | `https://api.moonshot.cn/v1` | `moonshot-v1-8k` |
  | 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` |
  | OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
  | 自定义 | 任意 OpenAI 兼容网关 | — |
- 模型输出若报错（额度、Key、模型名），把厂商原文透传到界面上
- 考据助手改为 MainActivity 内浮层，直接复用同一份 WebView 与 `atlasAPI`

**移除**

- 手机端的 MCP 客户端（`WebSocketClient` / `McpClient` / `ResearchActivity`）。
  电脑端脚本 `scripts/mcp_server.js`、`scripts/mcp_ws_server.js` 保留，
  仍可供 Claude Desktop / Cursor 等桌面 MCP 客户端使用。

## [1.1.0] — 2026-11-07

**电脑端 MCP 考据助手**

- 新增 `scripts/mcp_ws_server.js`：把 stdio 版 MCP 协议挂到 WebSocket 上，
  零依赖手写 RFC 6455；新增 `ask_question` 工具，用 LLM 把提问拆解成
  `inspect_place` / `inspect_person` / `search_atlas` 的参数
- 同时支持 OpenAI 兼容（`/v1/chat/completions`）与 Anthropic 兼容（`/v1/messages`）
- 手机端通过 `adb reverse` 隧道连接；提供考据面板与三个示例提问

## [1.0.0] — 2026-11-07

**首个可用版本：离线封装**

- 站点资源零构建打包进 `assets/www`，用 `WebViewAssetLoader` 以
  `https://appassets.androidplatform.net/` 提供，`fetch` / `localStorage` 行为与线上一致
- `WebViewClient` 拦截一切站外请求，网页内容完全离线
- 注册 `jiangshan://atlas` 深链；`singleTask` + `onNewIntent` 保证前台也能跳转
- 跳过仅用于 README 演示的 `hero_demo_*` / `preview_*` 图片（约 17 MB）
- 工具链：Gradle 9.8.1 / AGP 9.4.1 / compileSdk 37 / minSdk 24，
  仓库源与 Gradle 分发均按国内镜像优先

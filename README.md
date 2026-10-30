# Acid-broswer

**Acid-broswer** 是一款为 Android 打造的超轻量、原生支持 Agent Loop 的浏览器内核：纯原生 `WebView` + 注入式 `agent_bridge.js` 运行时 + `AcidIcons` 纯矢量图标引擎，**零第三方依赖**（不使用 AndroidX，不使用任何外部库）。

- **双主题**：默认纯净白色界面，一键切换真 OLED 纯黑夜间模式。所有颜色、字号、圆角令牌集中在 `AcidTheme`，单一真相源。
- **底栏 5 键**：从左到右依次为 `后退`、`前进`、`主页 (居中)`、`标签页 (带实时数字角标)`、`功能菜单`。
- **数据驱动功能菜单（13 项，4×2 自动分页）**：`夜间模式`、`无痕模式`、`书签收藏`、`历史浏览`、`电脑模式`、`下载管理`、`Acid Agent`、`设置`、`模型配置`、`广告拦截`、`脚本管理`、`MCP 服务`、`关于`。
- **本地控制网关**：内置 `127.0.0.1:3090` MCP 服务，支持大模型或本地 Agent 闭环遥控。

## 权限说明

应用申请以下 5 项权限，均为功能必需：

| 权限 | 用途 |
|---|---|
| `INTERNET` | 浏览网页、Agent 模型调用、检查更新 |
| `ACCESS_NETWORK_STATE` | 判断网络可用性 |
| `READ_EXTERNAL_STORAGE` | 读取 `Download` 目录中已下载的文件 |
| `WRITE_EXTERNAL_STORAGE` | 将下载文件保存到 `Download` 目录 |
| `REQUEST_INSTALL_PACKAGES` | 从下载列表直接安装 APK |

> **无需 Root、无需 Shizuku、无需无障碍服务、无需悬浮窗权限。**

---

## 一、 目录结构

```text
Acid-broswer/
├── AndroidManifest.xml                  # 权限、版本单点真相源与 Activity 声明
├── build.sh                             # 一键编译 + JVM 回归测试门禁（自动生成 BuildInfo）
├── test.sh                              # 零依赖纯 JVM 回归测试集运行脚本
├── assets/
│   ├── acid_logo.png                    # 启动页品牌图标
│   ├── adblock/
│   │   └── builtin_rules.txt            # 内置离线 ABP 广告拦截规则库
│   ├── agent_bridge.js                  # 注入式页面感知与动作执行引擎（DOM 蒸馏 / SoM / React-safe Fill）
│   ├── home.html                        # 内置启动页暨 Agent 动作靶场
│   ├── skills/
│   │   └── userscript_dev.md            # Agent 油猴脚本按需加载技能文档
│   └── userscripts/
│       └── anti_app_hijack.user.js      # 默认内置插件：拦截"打开App看全文"遮罩
├── docs/
│   ├── agent-tool-issues-report-20261028.md
│   ├── dynamic-tools-design.md
│   ├── ui-template-and-architecture.md  # 分级弹窗模板、设计令牌与声明式扩展规范
│   └── skills/
│       └── browser-feature-authoring.md
├── res/
│   ├── drawable/ic_launcher.png
│   └── values/strings.xml
├── src/com/acid/browser/                # 浏览器核心与声明式 UI 组件源码
└── test/                                # 纯 JVM 单元测试与轻量 Android/JSON 测试桩
```

### 源码分层

| 层 | 文件 |
|---|---|
| **入口与编排** | `BrowserActivity` — 生命周期、顶栏/底栏、overlay 协调 |
| **覆盖层与导航栈** | `AcidOverlay`（统一 show/dismiss/applyTheme/handleBackPressed）、`AcidBottomSheetView`（含顶栏/固定子顶栏/自适应滚动区/固定底栏与 `SheetPage` 二级栈）、`OverlayStackManager`（严格区分 `[←]` 返回上一级与 `[✕]` 关闭整个弹窗流）、`AcidDialog` / `AcidDialogs`（Tier-3 对话框）、`AcidContextMenu`（Tier-4 浮层菜单） |
| **设计系统与微工具** | `AcidTheme`（语义字号、5 级圆角、双主题调色板、`onColor` 对比度计算）、`AcidWidgets`（`groupCard` 自动分割线、`contentCard`、`heroCard`、`ListRow`、`LoadingButton`、响应式 `switchRow`）、`AcidIcons`（SVG 矢量图标引擎）、`AcidFeedback`、`AcidAsync`、`AcidHttp`、`AcidMarkdownView` |
| **功能弹窗与视图** | `BrowserMenuSheet`（`TileSpec` 数据驱动网格）、`BrowserSettingsSheet`、`ModelProviderConfigSheet`、`AgentModelSelectorSheet`、`AdBlockSheet`、`UserscriptSheet`（含 L2 脚本详情页）、`BookmarksHistorySheet`、`DownloadsSheet`、`DownloadConfirmSheet`、`AgentSessionsSheet`、`AboutSheet`、`TabsOverviewView`、`AgentFloatingView`、`AgentOverlayView`、`ElementContextMenu` |
| **浏览器与辅助服务** | `BrowserController`、`JsDialogHelper`、`AppUpdateChecker`、`AdBlockManager`、`UserscriptManager`、`UserscriptMeta`、`AcidFileProvider` |
| **Agent 与 MCP 链路** | `AcidAgentLoop`、`AcidSandboxTools`（声明式 `AgentTool` 注册表）、`WebAutomationDriver`、`JsEvalShaper`、`HttpControlServer` |
| **声明式设置与持久化** | `AcidSettings`（单点设置注册表，自动同步 `SharedPreferences`、`settings.json` 与 Agent 提示词 Schema）、`StateSource`（统一响应式状态）、`BrowserDataStore`、`AcidSessionStore`、`AcidModelStore` |

---

## 二、 本地 MCP 网关 (`127.0.0.1:3090`)

纯 Model Context Protocol 实现，提供 `browser`（13 个 action）、`read`、`write`、`edit`、`bash` 5 大核心工具（与内置 Agent 共享同一套 `AcidSandboxTools` 注册表）。

```bash
# 列出可用工具
curl -s -X POST http://127.0.0.1:3090/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# 跳转并等待页面稳定
curl -s -X POST http://127.0.0.1:3090/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"browser","arguments":{"action":"navigate","url":"https://news.ycombinator.com/"}}}'

# 观测当前页面语义树与 [e1..eN] 句柄
curl -s -X POST http://127.0.0.1:3090/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"browser","arguments":{"action":"observe","som":true}}}'

# 免软键盘填充（React/Vue 安全）
curl -s -X POST http://127.0.0.1:3090/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"browser","arguments":{"action":"fill","ref":"e7","text":"Acid","submit":true}}}'
```

服务可通过 功能菜单 → `MCP 服务` 开关。SSE 端点：`GET /sse`、`POST /message?sessionId=X`。

---

## 三、 构建与回归测试

```bash
./build.sh          # 编译、运行 JVM 回归测试集并打包签名 build/Acid-broswer.apk
./test.sh           # 单独运行纯 JVM 回归测试集（< 0.3 秒）
```

依赖：`aapt`、`javac` (JDK 8+)、`dx`、`zipalign`、`apksigner`、`android.jar`。支持通过环境变量 `ANDROID_SDK_ROOT`、`ANDROID_JAR`、`DX`、`KEYSTORE_FILE` 覆盖默认路径。

---

## 四、 项目文档

- **[UI 分级弹窗模板与声明式架构规范](docs/ui-template-and-architecture.md)** — 4 级覆盖层体系、语义化字号/圆角令牌、页面模板及“新增开关/菜单格/工具”标准范式。
- **[Agent 工具链问题与优化建议报告 (2026-10-28)](docs/agent-tool-issues-report-20261028.md)** — 基于多站点真实业务流实测的 13 项工具问题与优先级矩阵。

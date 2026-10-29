# Acid-broswer

**Acid-broswer** 是一款为 Android 打造的超轻量、原生支持 Agent Loop 的浏览器内核：纯原生 `WebView` + 注入式 `agent_bridge.js` 运行时 + `AcidIcons` 纯矢量图标引擎，**零第三方依赖**（不使用 AndroidX，不使用任何外部库）。

- **双主题**：默认纯净白色界面，一键切换真 OLED 纯黑夜间模式。所有颜色集中在 `AcidTheme`，单一真相源。
- **底栏 5 键**：从左到右依次为 `后退`、`前进`、`主页 (居中)`、`标签页 (带实时数字角标)`、`功能菜单`。
- **功能菜单（10 项，两页横滑）**：`夜间模式`、`无痕模式`、`书签收藏`、`历史浏览`、`电脑模式`、`下载管理`、`Acid Agent`、`模型配置`、`关于`、`MCP 服务`。
- **超轻量体积**：全包 **249 KB**（254,713 字节），dex 仅 1,797 个方法。
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
├── AndroidManifest.xml                  # 权限与 Activity 声明
├── build.sh                             # 一键编译：aapt → javac → dx → zipalign → apksigner（约 4 秒）
├── assets/
│   ├── acid_logo.png                    # 启动页品牌图标
│   ├── agent_bridge.js                  # 注入式页面感知与动作执行引擎（DOM 蒸馏 / SoM / React-safe Fill）
│   ├── home.html                        # 内置启动页暨 Agent 动作靶场
│   └── userscripts/
│       └── anti_app_hijack.user.js      # 默认内置插件：拦截"打开App看全文"遮罩
├── docs/
│   └── agent-tool-issues-report-20261028.md
├── res/
│   ├── drawable/ic_launcher.png
│   └── values/strings.xml
└── src/com/acid/browser/                # 26 个 Java 文件，约 14,900 行
```

### 源码分层

| 层 | 文件 |
|---|---|
| **入口与编排** | `BrowserActivity` — 生命周期、顶栏/底栏、overlay 注册表 |
| **覆盖层基类** | `AcidOverlay`（统一 show/dismiss/applyTheme/handleBackPressed）、`AcidBottomSheetView`（Sheet 脚手架） |
| **控制器** | `BrowserController` — Tab 会话、WebView 配置、UA 伪装、深链拦截、下载管理 |
| **UI 组件** | `BrowserMenuSheet`、`BrowserListsSheet`、`TabsOverviewView`、`AgentFloatingView`、`AgentSessionsSheet`、`AgentModelSelectorSheet`、`ModelProviderConfigSheet`、`ElementContextMenu` |
| **主题与图形** | `AcidTheme`（42 色常量 + 语义访问器）、`AcidIcons`（47 款 SVG 矢量图标）、`AcidMarkdownView`、`AgentOverlayView` |
| **Agent 链路** | `AcidAgentLoop`、`AcidSandboxTools`、`WebAutomationDriver`、`JsEvalShaper`、`HttpControlServer` |
| **持久化** | `BrowserDataStore`（书签/历史/下载）、`AcidSessionStore`（会话）、`AcidModelStore`（模型配置）、`UserscriptManager` |
| **其它** | `AcidFileProvider` |

---

## 二、 本地 MCP 网关 (`127.0.0.1:3090`)

纯 Model Context Protocol 实现，提供 `browser` 工具（13 个 action）与 `workspace` 工具。

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

## 三、 构建

```bash
./build.sh          # 输出 build/Acid-broswer.apk
```

依赖：`aapt`、`javac` (JDK 8+)、`dx`、`zipalign`、`apksigner`、`android.jar`（API 23）。

---

## 四、 项目文档

- **[Agent 工具链问题与优化建议报告 (2026-10-28)](docs/agent-tool-issues-report-20261028.md)** — 基于多站点真实业务流实测的 13 项工具问题与优先级矩阵。

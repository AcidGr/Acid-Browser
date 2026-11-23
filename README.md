# Acid Browser

**Acid Browser** 是一款基于 Android 系统原生 `WebView` 构建的浏览器，无 AndroidX 或第三方大型 SDK 依赖，内置自主 AI Agent 对话循环、本地沙盒工作区、用户脚本引擎、ABP 广告拦截与本地 MCP 服务网关。

[English](README_EN.md) | [中文说明](README.md)

---

## 目录

- [核心设计思路](#核心设计思路)
- [浏览器功能列表](#浏览器功能列表)
- [Agent 工具与动作](#agent-工具与动作)
- [快速上手与模型配置](#快速上手与模型配置)
- [沙盒工作区目录结构](#沙盒工作区目录结构)
- [权限说明](#权限说明)
- [构建与测试](#构建与测试)
- [致谢](#致谢)

---

## 核心设计思路

### 1. 点击与填表不依赖 `screenshot` 视觉模型

与依赖“截图 + 多模态视觉模型猜坐标”的 GUI Agent 不同，Acid Browser 的网页交互主路径建立在页内注入的 `agent_bridge.js` DOM 语义蒸馏之上：

1. **语义句柄（`[e1..eN]`）而非像素识别**：`observe` 会遍历页面真实可交互节点，过滤不可见元素并做遮挡检测，生成精简的 `[e1..eN]` 句柄表与正文摘录，纯文本大模型即可直接理解页面结构与可点控件。
2. **原生触摸与 DOM 闭环**：调用 `click` 时由客户端解析目标元素的几何坐标并派发 Android 原生 `MotionEvent`（后台标签页走 DOM 点击），`fill` 直接操作输入节点并兼容 React/Vue 受控组件且带回读校验。
3. **每步自动回传最新观测**：所有状态变更动作（`navigate`、`click`、`fill`、`select`、`scroll`、`wait`、`upload`、`set_mode`、`back`、`reload`）执行后都会自动附带更新后的页面状态与 `[e1..eN]` 列表，无需额外轮次。

### 2. `screenshot` 是后期按需补上的视觉补充

`screenshot` 动作并不是主链路，而是在版本演进后期遇到纯 DOM 语义树无法覆盖的场景时才补充的兜底工具，主要用于：

- Canvas 画布、数据图表、图片内容或图形验证码的视觉核对；
- 纯图标按钮缺失文本/属性、或需要确认页面实际排版与视觉状态时。

调用 `screenshot` 时，坐标空间会自动与当前手机/电脑模式视口对齐；支持开启 `som=true` 仅在返回的截图图像本身绘制 `[e1..eN]` 标注框（不污染网页 DOM），也支持通过 `save_path` 将原分辨率截图保存到沙盒工作区供 `bash` 处理或 `upload` 上传。

### 3. 前台锁定与人工接管

- **防误触锁定**：当 Agent 正在驱动当前前台标签页时，系统会锁定该页的网页触控与地址栏操作，避免人工误触打断执行；此时仍可通过底部「标签页」切换到其他标签页自由浏览，互不干扰。
- **人工接管（Takeover）**：遇到登录密码、短信验证码、图形验证或支付确认时，Agent 会调用 `request_takeover`（或由用户点击顶部「点此接管」）暂停循环并归还控制权；处理完成后可在对话窗输入补充说明发送恢复，或直接点击「交还AI」继续任务。

---

## 浏览器功能列表

### 日常浏览与标签管理
- **地址栏与搜索**：支持输入网址或关键词搜索（可在设置中切换内置搜索引擎或自定义搜索模板），支持 `acid://search?q=...` 快捷查询。
- **多标签页概览**：双列缩略图网格概览，支持新建、切换、单页关闭与一键关闭其他空闲标签页；Agent 正在运行的标签页受保护不会被误关。
- **电脑 / 手机模式切换**：支持一键切换 `1280px` 桌面视口与桌面 User-Agent，电脑模式状态按标签页独立隔离。
- **书签与历史记录**：支持书签收藏、编辑、搜索与排序；全量浏览历史按「今天 / 昨天 / 最近 7 天 / 更早以前」分组并支持实时检索。
- **内置流光主页**：支持在 `homepage/index.html` 中使用自定义 HTML/CSS/JS 替换主页。

### 界面、排版与隐私
- **主题与全屏**：支持日间模式与 OLED 纯黑夜间模式；全屏模式适配异形屏挖孔区域并保留右下角退出按钮。
- **网页全局字号缩放**：支持按百分比调节网页字号并实时预览。
- **无痕模式**：不记录浏览历史、禁用表单保存与磁盘缓存，退出时自动清理临时会话与 Cookie。
- **浏览防护**：支持 `Do Not Track (DNT)` 请求头、无图省流模式、长按网页元素上下文菜单，以及三级第三方 App 唤端拦截策略（完全拦截 / 弹窗询问 / 允许；Agent 接管期间强制完全拦截）。

### 广告拦截、脚本与下载
- **ABP 广告拦截引擎**：支持内置规则库、在线规则订阅更新以及自定义规则编辑（`||domain^`、`@@||whitelist^`、`domain##selector`），保存后热重载生效。
- **用户脚本（Userscripts）**：原生支持 `==UserScript==` 元数据（`@name`、`@match`、`@exclude`、`@run-at`、`@grant`、`@require` 等），支持导入代码/URL、点击 `.user.js` 链接安装、独立开关与脚本菜单命令；内置防跳 App 与解除复制限制脚本。
- **下载管理**：内置多线程分块断点续传下载器（也可切换为系统 `DownloadManager`），支持网盘直链请求头/Cookie 适配、按类型筛选（安装包 / 压缩包 / 文档 / 图片 / 视频 / 音频）、系统分享、APK 安装与在线检查更新。

### AI 对话窗、技能与 MCP 网关
- **原生 Agent 悬浮对话窗**：支持多会话管理与持久化恢复、按会话切换模型及思考深度（Reasoning Effort：默认 / 关闭 / 低 / 中 / 高 / 极高）、运行中随时发消息重引导、接管暂停期间带文字指令恢复。
- **多模型接入**：内置主流模型厂商目录并支持自定义 OpenAI 兼容接口（含本地无鉴权服务如 Ollama / vLLM），支持接口 `/models` 列表拉取与连通性测试。
- **对话附件上传**：单次支持选取最多 5 个本地文件（图片支持多模态识图输入，文本/代码/其他文件暂存至工作区 `uploads/` 供工具读取或上传至网页）。
- **技能库（Skills）**：支持 Markdown 编写的按需加载技能包，内置主页定制（`homepage_dev.md`）、用户脚本编写（`userscript_dev.md`）与网盘下载（`cloud_drive_download.md`）三份技能，也支持导入自定义技能。
- **本地 MCP 控制网关**：遵循 Model Context Protocol（JSON-RPC 2.0，提供 `GET /sse` 与 `POST /mcp`），默认绑定 `127.0.0.1:3090` 并强制 Bearer Token 鉴权（可手动开启局域网访问与重置令牌），仓库附带 `scripts/acid-mcp-stdio-bridge.py` 用于桥接 stdio 客户端。

---

## Agent 工具与动作

Agent 与本地 MCP 网关共用同一套工具声明（`AcidSandboxTools`），共注册 5 个工具：

| 工具名 | 说明 |
| :--- | :--- |
| `browser` | 网页感知与自动化控制入口，通过 `action` 参数调度具体动作 |
| `read` | 按行读取沙盒工作区内的 UTF-8 文本文件（`file_path`、`offset`、`limit`） |
| `write` | 创建或覆写工作区文本文件；写入 `userscripts/<name>.user.js` 时会自动校验 `==UserScript==` 头并安装 |
| `edit` | 对工作区已有文本文件做精确字符串替换（支持 `replace_all`） |
| `bash` | 在沙盒工作区目录通过 `/system/bin/sh` 执行命令（`command`、`timeout_ms`；出于安全限制仅应用内 Agent 可用，MCP 网关禁止调用） |

### `browser` 工具支持的 `action` 列表

| `action` | 功能说明 | 关键参数 |
| :--- | :--- | :--- |
| `observe` | 蒸馏当前页面 `[e1..eN]` 交互元素与正文摘录 | `selector`、`offset`、`limit`、`text_offset`、`text_limit`、`viewport_only` |
| `navigate` | 在目标标签页加载指定网页或工作区文件 | `url` |
| `click` | 点击页面元素（前景派发原生触摸，后台走 DOM 点击，含重定位自愈） | `ref` / `text`（可配 `exact`） / `selector` / `x`、`y`、`coord_space` |
| `fill` | 向输入框或富文本区域填值并回读校验（兼容 React/Vue） | `ref` / `selector`、`text`、`clear`、`submit` |
| `select` | 在 `<select>` 下拉框中选择指定选项 | `ref`、`text` |
| `scroll` | 滚动视口或指定容器 | `direction`（`down`/`up`/`left`/`right`/`top`/`bottom`）、`amount`、`ref` |
| `wait` | 等待页面稳定、元素出现/消失、文本出现、网络空闲或固定时长 | `condition`（`stable`/`selector`/`text`/`gone`/`networkidle`/`ms`）、`selector`、`text`、`quiet_ms`、`ms`、`timeout_ms` |
| `eval` | 在页面执行 JavaScript 并自动等待 Promise 返回结果 | `code` |
| `upload` | 将工作区文件传入网页 `<input type="file">`（拒绝受保护的系统配置文件） | 点击目标（`ref`/`text`/`selector`/`x`/`y`）+ `file_path` 或 `file_paths` |
| `download` | 拉起内置多线程下载确认页（支持常见网盘 UA/Cookie 自动补全） | `url`、`file_name`、`user_agent` |
| `set_mode` | 切换桌面/移动模式或开关 SoM 标注 | `mode`（`desktop`/`mobile`）、`som` |
| `tabs` | 列出、新建、切换或关闭标签页 | `tab_action`（`list`/`new`/`switch`/`close`）、`tab_id`、`url` |
| `back` | 当前标签页后退 | — |
| `reload` | 刷新当前标签页 | — |
| `screenshot` | 截取当前可见视口图像（仅在需要纯视觉判断时使用） | `som`（仅在图像上绘制标号）、`save_path` |
| `request_takeover` | 暂停自动流程请求人工接管（仅应用内 Agent 可用，MCP 网关禁用） | `reason` |

---

## 快速上手与模型配置

1. 安装构建输出的 `build/Acid-browser.apk`。
2. 在底部菜单「☰」第 2 页进入 **「模型配置」**：
   - 可从内置厂商目录选择（填入 API Key 后即可装载模型），或添加自定义 OpenAI 兼容服务（接口地址填到 `/v1` 为止，本地免鉴权接口如 `http://127.0.0.1:11434/v1` 可留空密钥）；
   - 支持「同步接口模型 (`/models`)」、手动添加模型 ID 及测试连通性。
3. 点击顶部右上角 Agent 图标打开悬浮对话窗，点击模型按钮即可切换当前模型与思考深度，输入任务即可开始执行。

---

## 沙盒工作区目录结构

配置、书签、历史、主页、技能、用户脚本与广告规则均存放在应用私有工作区中，核心 JSON 配置与规则文件支持热重载：

```text
/data/user/0/com.acid.browser/files/acid_workspace/
├── settings.json               # 全局浏览器设置
├── bookmarks.json              # 书签收藏列表
├── history.json                # 浏览历史记录（含时间戳）
├── uploads/                    # 对话上传的附件暂存目录
├── homepage/
│   └── index.html              # 本地自定义主页入口
├── skills/
│   ├── homepage_dev.md         # 主页定制技能
│   ├── userscript_dev.md       # 用户脚本编写技能
│   └── cloud_drive_download.md # 网盘直链下载技能
├── userscripts/
│   └── anti_app_hijack.user.js # 内置防跳 App 与解除复制限制脚本
└── adblock/
    ├── custom_rules.txt        # 自定义广告拦截规则
    └── subscriptions.txt       # 在线订阅缓存规则
```

---

## 权限说明

仅申请以下 5 项基础权限，不需要 Root、无障碍服务或系统悬浮窗权限：

| 权限名称 | 用途说明 |
| :--- | :--- |
| `INTERNET` | 网页浏览、模型 API 请求、规则订阅与更新检查 |
| `ACCESS_NETWORK_STATE` | 检测网络连接状态与局域网 IP |
| `READ_EXTERNAL_STORAGE` | 读取本地已下载文件 |
| `WRITE_EXTERNAL_STORAGE` | 保存下载文件到系统存储 |
| `REQUEST_INSTALL_PACKAGES` | 在下载管理或更新弹窗中安装 APK |

---

## 构建与测试

### 环境要求
- Linux / macOS
- JDK 8+（`javac`、`java`）
- Node.js（用于运行 JS Bridge 回归测试）
- Android SDK Build Tools（`aapt`、`dx`、`zipalign`、`apksigner`）及 `android.jar`（`minSdkVersion` 为 24）

### 常用命令
```bash
# 运行全部回归测试并编译打包签名 APK（输出 build/Acid-browser.apk）
./build.sh

# 仅运行 JVM 回归测试与 JS Bridge 回归测试
./test.sh
```

## 致谢

本项目已链接认可 [LINUX DO](https://linux.do) 社区，感谢佬友们的支持与反馈。

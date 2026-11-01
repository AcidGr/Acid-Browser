# Acid Browser

**Acid Browser** 是一款专为 Android 打造的超轻量、零第三方依赖的智能浏览器。

它基于系统原生 `WebView` 构建，不依赖 AndroidX 或任何外部重量级 SDK，安装包体积仅约 **345 KB**。在提供干净、流畅的日常网页浏览体验的同时，原生内嵌了自主 AI Agent 循环、本地沙盒工作区、油猴用户脚本引擎、ABP 广告拦截器以及标准 MCP 控制网关。

<p align="center">
  <img src="docs/screenshots/01-home.jpg" width="250" alt="Acid Browser 主页" />
  <img src="docs/screenshots/02-menu-page1.jpg" width="250" alt="功能菜单 第 1 页" />
  <img src="docs/screenshots/07-agent-chat.jpg" width="250" alt="Acid Agent 对话窗" />
</p>

---

## 目录

- [界面速览](#界面速览)
- [快速上手](#快速上手)
- [模型配置图文教程](#模型配置图文教程)
- [功能详解](#功能详解)
- [沙盒工作区目录结构](#沙盒工作区目录结构)
- [权限说明](#权限说明)
- [构建与测试](#构建与测试)

---

## 界面速览

底部为五行单手操作栏：**后退 · 前进 · 主页 · 标签页 · 功能菜单**。地址栏左侧的盾牌图标即广告拦截状态指示。

点击最右侧「☰」打开**功能面板**，共两页，24 项能力全部在此集中调度：

<table>
  <tr>
    <td align="center"><b>第 1 页：日常浏览</b></td>
    <td align="center"><b>第 2 页：AI 与扩展</b></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/02-menu-page1.jpg" width="300" alt="功能菜单第一页" /></td>
    <td><img src="docs/screenshots/03-menu-page2.jpg" width="300" alt="功能菜单第二页" /></td>
  </tr>
</table>

| 第 1 页 | 作用 |
| :--- | :--- |
| 夜间模式 | 一键切换 OLED 纯黑深色主题 |
| 无痕模式 | 不落盘历史、不写缓存、退出即清会话 Cookie |
| 书签收藏 | 收藏、编辑、搜索、排序当前站点 |
| 历史浏览 | 全量历史，按时间分桶并支持实时检索 |
| 电脑 / 手机模式 | 一键切换 1280px 桌面视口 + 桌面 UA |
| 下载管理 | 分类筛选、进度查看、系统分享与 APK 直装 |
| Acid Agent | 打开原生 AI Agent 悬浮对话窗 |
| 设置 | 通用 / AI / 防护 / 隐私四级设置目录 |

| 第 2 页 | 作用 |
| :--- | :--- |
| 模型配置 | 管理大模型供应商与模型列表（详见下文） |
| 广告拦截 | 内置规则、在线订阅与自定义 ABP 规则 |
| 脚本管理 | 用户脚本安装、开关、查看与删除 |
| 技能管理 | Agent 按需加载的 Markdown 技能库 |
| MCP | 本地 MCP 控制网关与令牌认证 |
| 关于 | 版本信息与在线检查更新 |

---

## 快速上手

1. 安装 `build/Acid-browser.apk`，首次打开即为内置流光主页。
2. 在搜索框直接输入关键词或网址；也可提交 `acid://search?q=关键词` 调用默认搜索引擎。
3. 想用 AI 时，只需两步：**先配一个模型**（见下节），再点「Acid Agent」开始对话。

---

## 模型配置图文教程

Acid Browser 不预置任何密钥，模型完全由你自己接入。**支持 DeepSeek、Kimi、阿里云百炼（Qwen）、小米 MiMo、MiniMax、硅基流动等主流厂商，也支持任意 OpenAI 兼容接口**（中转网关、Ollama、vLLM 等）。

### 第一步：打开模型配置

`☰ 功能菜单` → 第 2 页 → **「模型配置」**

<img src="docs/screenshots/04-model-providers.jpg" width="330" alt="模型配置供应商列表" />

列表中每个供应商卡片会显示 **密钥状态**（`● 密钥已就绪` / `● 待填密钥`）、接口地址与已装载模型，并提供：

- **测试连通性** —— 实时向 `/models` 探测，验证 Key 与网络是否可用；
- **配置与模型** —— 编辑密钥、接口与模型列表；
- **删除** —— 仅自定义供应商可删除。

### 第二步：添加供应商

点击右上角 **「+ 添加供应商」**，有两种接入方式：

<img src="docs/screenshots/05-model-catalog.jpg" width="330" alt="添加模型供应商" />

- **默认供应商目录**：内置 6 家主流厂商，选好后填入 API Key 即可一键装载官方推荐模型，无需手动抄写模型 ID；
- **自定义供应商 API**：适用于第三方聚合网关、本地 Ollama / vLLM，或任何兼容 OpenAI Chat Completions 协议的服务。

### 第三步：填写密钥与模型

以 Kimi 为例，进入配置页后：

<img src="docs/screenshots/06-model-editor.jpg" width="330" alt="供应商配置页" />

1. **API 密钥**：输入框默认掩码显示，点击右上角「显示密钥」可临时查看，保存后不会在列表中明文回显；
2. **API 接口地址**：内置厂商已自动填好，自定义服务填到 `/v1` 为止；
3. **装载的模型列表**：可三种方式调整
   - **同步接口模型 (`/models`)** —— 自动拉取服务端真实可用的模型 ID；
   - **手动添加** —— 填入自定义模型 ID 与显示名；
   - **恢复默认模型** —— 还原该厂商的推荐清单；
4. 点击底部 **「保存供应商配置」** 完成装载。

> 本地免鉴权服务（如 `http://127.0.0.1:11434/v1`）可以留空 API Key，Agent 会自动跳过鉴权要求。

### 第四步：在对话里选择模型与思考深度

打开 **「Acid Agent」**，点击卡片右上角的模型按钮即可进入**选择模型**面板：

<img src="docs/screenshots/08-agent-model-select.jpg" width="330" alt="选择模型与思考深度" />

- 顶部搜索框可按供应商或模型名**实时过滤**；
- 点任意模型行的「选择」即可立即切换，无需重启；
- **思考深度 (Reasoning Effort)** 提供 默认 / 关闭 / 低 / 中 / 高 / 极高 六档：简单任务用「关闭」或「低」响应更快，复杂调研用「高」以上；
- 右上角「模型配置」可直达上一步的供应商管理页。

---

## 功能详解

### 1. 原生 AI Agent

点「Acid Agent」打开悬浮对话窗，新会话自带示例指令：

<img src="docs/screenshots/07-agent-chat.jpg" width="330" alt="Acid Agent 对话窗" />

Agent 内建 5 个本地沙盒工具，无需任何插件即可完成闭环任务：

| 工具 | 能力 |
| :--- | :--- |
| `browser` | 网页感知与操控：`observe` / `navigate` / `click` / `fill` / `select` / `scroll` / `wait` / `eval` / `set_mode` / `tabs` / `back` / `reload` / `request_takeover` |
| `read` | 读取工作区文件（带行号） |
| `write` | 新建或覆写文件，写入 `userscripts/*.user.js` 会自动校验并安装脚本 |
| `edit` | 精确字符串替换 |
| `bash` | 在工作区目录执行 shell 命令 |

关键机制：**闭环保留** —— 每次点击/填表/滚动后都会自动回传最新页面快照与 `[e1..eN]` 语义句柄，Agent 不必盲目重试；遇到登录、验证码、支付确认时会主动 `request_takeover` 暂停，交还给你手动处理。

### 2. 广告拦截与唤端防护

<img src="docs/screenshots/09-adblock.jpg" width="330" alt="广告拦截面板" />

- 顶部总开关，开启后自动拦截广告请求、弹窗贴片与恶意唤端跳转；
- 内置规则库（示例设备已加载 3132 条）与**在线 ABP / EasyList 订阅**一键拉取更新；
- **自定义规则**支持标准语法：
  - `||ad-domain.com^` —— 按域名拦截
  - `@@||safe.com^` —— 白名单放行
  - `example.com##.banner` —— 隐藏指定元素
- 修改规则后无需重启，保存即热重载生效。

### 3. 用户脚本（Userscripts）

<img src="docs/screenshots/10-userscripts.jpg" width="330" alt="脚本管理" />

原生支持 `==UserScript==` 元数据（`@name` / `@match` / `@exclude` / `@run-at` / `@version`），每个脚本独立开关。三种安装方式：

1. 点「导入脚本」粘贴代码或 URL；
2. 网页中点击 `.user.js` 链接，自动呼出安装器；
3. 直接让 Agent 用 `write` 写进 `userscripts/`。

### 4. 技能（Skills）

<img src="docs/screenshots/11-skills.jpg" width="330" alt="技能管理" />

技能是给 Agent 的**专业作业手册**，用 Markdown 编写、按需加载，平时零 Token 消耗。内置两份官方技能：

- **Custom Homepage Design & Authoring** —— 主页定制向导与免 Key 接口库；
- **Browser Feature & Userscript Authoring** —— 用户脚本编写规范与运行时边界。

你也可以点「添加技能」导入自己的 Markdown 流程，Agent 会自动编目并在此后的任务中优先遵循。

### 5. 本地 MCP 控制网关

<img src="docs/screenshots/12-mcp.jpg" width="330" alt="MCP 服务面板" />

开启后，电脑端的 Cursor、Claude Desktop 等工具可远程操控这台手机上的浏览器：

- 遵循 Model Context Protocol（JSON-RPC 2.0），支持 `GET /sse` 流式传输与 `POST /mcp` 直接调用；
- 默认**仅监听 `127.0.0.1`** 并强制 Bearer Token 鉴权，令牌支持查看、复制与重新生成（重置后旧令牌立即失效）；
- 仅在手动打开「允许局域网设备连接」后才绑定 `0.0.0.0`，建议只在可信 Wi-Fi 下使用；
- 仓库附带 `scripts/acid-mcp-stdio-bridge.py`，用于桥接只支持 stdio 的客户端。

### 6. 其他日常能力

- **极简流光主页**：渐变时钟、日期胶囊、书签磁贴；可完全用 `homepage/index.html` 自定义；
- **多标签管理**：网格缩略图概览，支持单卡滑动关闭与一键全关；
- **完整历史**：不限条数，按今天 / 昨天 / 过去 7 天 / 更早分组，支持实时搜索；
- **分类下载**：按安装包 / 压缩包 / 文档 / 图片 / 视频 / 音频筛选，支持系统分享与 APK 一键安装；
- **阅读与隐私**：OLED 夜间模式、无痕浏览、无图省流、DNT 请求头、长按网页上下文菜单。

---

## 沙盒工作区目录结构

浏览器的配置、书签、历史、主页、脚本与规则均以直观的文本或 JSON 文件存放在应用私有工作区中，支持通过内置 UI 或外部文件编辑实时热重载：

```text
/data/user/0/com.acid.browser/files/acid_workspace/
├── settings.json               # 全局浏览器设置
├── bookmarks.json              # 书签收藏列表
├── history.json                # 浏览历史记录（含时间戳）
├── homepage/
│   └── index.html              # 本地自定义主页入口（同目录可放 CSS/JS/图片）
├── skills/
│   ├── homepage_dev.md         # 内置主页设计与定制技能
│   └── userscript_dev.md       # 内置用户脚本编写技能
├── userscripts/
│   └── anti_app_hijack.user.js # 内置防跳 App 与解除复制限制脚本
└── adblock/
    ├── custom_rules.txt        # 自定义广告拦截规则
    └── subscriptions.txt       # 在线订阅缓存规则
```

---

## 权限说明

遵循最小必要权限原则，仅申请以下 5 项基础权限，无需 Root、无需无障碍服务、无需悬浮窗权限：

| 权限名称 | 用途说明 |
| :--- | :--- |
| `INTERNET` | 网页浏览、在线模型 API 请求、应用更新检查 |
| `ACCESS_NETWORK_STATE` | 检测当前网络连接状态与局域网地址 |
| `READ_EXTERNAL_STORAGE` | 在下载管理中读取系统 `Download` 目录已下载文件 |
| `WRITE_EXTERNAL_STORAGE` | 将网页下载的文件保存至本地存储 |
| `REQUEST_INSTALL_PACKAGES` | 在下载管理或更新弹窗中拉起安装 APK 文件 |

---

## 构建与测试

### 环境要求
- Linux / macOS
- JDK 8+（`javac`、`java`）
- Android SDK Build Tools（`aapt`、`dx`、`zipalign`、`apksigner`）及 `android.jar`（API 23+，`minSdkVersion` 为 24）

### 常用命令
```bash
# 运行纯 JVM 回归测试并打包签名 APK
./build.sh

# 仅运行纯 JVM 回归测试套件（12 项核心模块测试）
./test.sh
```

构建完成后，签名安装包输出在 `build/Acid-browser.apk`。

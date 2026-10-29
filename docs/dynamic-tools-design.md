# Userscript 引擎增强 + Agent 动态工具 设计方案

> 状态：**设计稿 v2，待评审**
> 变更：v1 设想「独立 tools.json + 独立注入层」，v2 改为**复用并增强现有 userscript 系统**。
> 依据：用户确认「复用 userscript」「兼容油猴元数据」「支持 URL 一键安装」。

---

## 一、为什么换架构

v1 的假设是「动态工具需要自己一套存储和注入」。查代码后发现这个假设是错的：

**Acid 已经有一套 userscript 系统，而且形态和油猴高度相似。**

```javascript
// assets/userscripts/anti_app_hijack.user.js —— 已经是标准油猴格式
// ==UserScript==
// @name         Acid Anti-Hijack & Full-Article Unlocker
// @namespace    https://acid.browser/userscripts
// @version      1.0.0
// @match        *://*/*
// @run-at       document-idle
// ==/UserScript==
```

注入走 `evaluateJavascript`，**免疫页面 CSP**，比很多油猴实现更稳。

所以正确的做法不是另起炉灶，而是**把这套引擎补完整**，动态工具建在它上面。

**工作量因此从 ~590 行降到 ~420 行。**

---

## 二、能力边界（准确版）

这是回答「Agent 到底能做到什么地步」的核心。

```
┌──────────────────────────────────────────────────────────┐
│ L4  修改 App 自身（加 Java / 改 UI 逻辑）                    │
│     ✗ 完全不可能                                           │
│     实测：源码 Permission denied、无 javac/dx/aapt、        │
│           无 pm install 权限                               │
├──────────────────────────────────────────────────────────┤
│ L3  新增菜单入口（动态工具）                                │
│     ⚠️ 需改造，本方案的目标                                 │
│     本质 = 带 UI 元数据的 userscript                       │
├──────────────────────────────────────────────────────────┤
│ L2  写 userscript（跨页面长期生效）                         │
│     ◐ 已具备但残缺：无 @match 解析，全站污染                │
│     ← 第一阶段要补的就是这里                                │
├──────────────────────────────────────────────────────────┤
│ L1  操作当前页面（eval / click / fill / observe）           │
│     ✓ 完全具备，走 agent_bridge.js                         │
└──────────────────────────────────────────────────────────┘
```

### 明确做不到的事

| 需求 | 结论 | 原因 |
|---|---|---|
| 装 Tampermonkey / Violentmonkey | ❌ | Android WebView 无扩展机制 |
| 用 `.crx` 扩展 | ❌ | 同上 |
| `chrome.runtime` / `chrome.webRequest` | ❌ | WebView 不提供 |
| `GM_xmlhttpRequest` 跨域 | ❌ | 无 `@grant` 宿主（可后续用原生桥模拟） |
| 改浏览器 UI / 逻辑 | ❌ | 需编译 |
| 读其他 App 数据 | ❌ | SELinux `untrusted_app_27` |

### 能做到的事

| 需求 | 现状 | 本方案后 |
|---|---|---|
| 改当前页样式/内容 | ✅ | ✅ |
| 写长期生效的脚本 | ✅（但全站生效） | ✅（可限域名） |
| 用户说「加个看书模式」→ 菜单出现按钮 | ❌ | ✅ |
| 装网上的现成油猴脚本 | ❌ | ✅（不含 GM_* 的） |
| 脚本开关/删除 | ❌ | ✅ |

---

## 三、现有实现的缺陷（已核实）

### 缺陷 1：元数据完全不解析

`UserscriptManager` 里搜不到任何 `@match` / `@grant` / `@run-at` 的处理。

| 元数据 | 脚本里写了 | 实际效果 |
|---|---|---|
| `@match *://*/*` | ✅ | **被忽略**，所有站点注入 |
| `@run-at document-idle` | ✅ | **被忽略**，固定 `onPageFinished` |
| `@name` | ✅ | **被忽略**，UI 显示文件名 |
| `@description` | ✅ | **被忽略** |

`listUserscriptsJson()` 只返回 `{name, sizeBytes}`，且**没有任何调用方**。

### 缺陷 2：`write` 工具的承诺是假的

工具描述声称：

```
Writing to 'userscripts/<name>.user.js' automatically installs a browser userscript plugin.
```

实际代码：

```java
private String executeWriteTool(JSONObject args) {
    writeFileUtf8(f, content);      // 就是普通写文件
    return "Successfully wrote ...";
}
```

没有调用 `saveUserscript()`，没有路径特殊处理，没有扩展名校验。
之所以能生效，是因为两者最终写到同一目录、注入逻辑遍历目录——**是碰巧**。

### 缺陷 3：`document-start` 无处执行

`onPageStarted` 里没有任何注入代码：

```java
public void onPageStarted(WebView view, String url, Bitmap favicon) {
    tab.isLoading = true;
    tab.url = url;
    // 只有 UI 回调，无注入
}
```

只在 `onPageFinished` 和 `onProgressChanged(>40)` 注入。即使解析了 `@run-at document-start` 也没有落点。

### 缺陷 4：注入点重复触发

`onPageFinished` 和 `onProgressChanged(>40)` 都会调 `injectRuntimeAndUserscripts`。虽然有 `window.__acidInjectedUserscripts` 去重，但每次都要重新读文件、拼字符串、过一遍 `evaluateJavascript`，属于浪费。

---

## 四、第一阶段：补完 userscript 引擎

### 4.1 元数据解析器

新增 `UserscriptMeta`：

```java
public class UserscriptMeta {
    public String name;          // @name，缺省用文件名
    public String namespace;     // @namespace
    public String version;       // @version
    public String description;   // @description
    public List<String> matches;     // @match
    public List<String> includes;    // @include
    public List<String> excludes;    // @exclude
    public String runAt;         // @run-at: document-start / document-end / document-idle
    public boolean enabled = true;

    // 本项目扩展
    public String iconPath;      // @acid-icon，24x24 SVG path
    public boolean showInMenu;   // @acid-menu
    public int menuOrder;        // @acid-order
}
```

解析规则：

- 匹配 `// ==UserScript==` … `// ==/UserScript==` 块
- 每行格式 `// @key value`
- 同一 key 可出现多次（`@match` 允许多行）
- 解析失败不影响脚本本体，用缺省值

**必须容错**：元数据坏了，脚本仍要能跑（用 `@match *://*/*` 兜底），不能因为解析异常就丢弃脚本。

### 4.2 域名匹配

```java
public static boolean matchesUrl(UserscriptMeta meta, String url) {
    if (meta.matches.isEmpty() && meta.includes.isEmpty()) return true;  // 无限制
    for (String p : meta.excludes) if (globMatch(p, url)) return false;
    for (String p : meta.matches)  if (globMatch(p, url)) return true;
    for (String p : meta.includes) if (globMatch(p, url)) return true;
    return false;
}
```

支持油猴通配符：

| 模式 | 含义 |
|---|---|
| `*` | 任意字符（不含 `/`） |
| `*://*/*` | 所有 http/https |
| `https://example.com/*` | 指定域名 |
| `*://*.github.com/*` | 含子域 |

实现方式：把通配符转成正则（`*` → `[^/]*`），预编译缓存。

### 4.3 注入点重构

```java
// 新增：按 run-at 分组
public void injectByRunAt(WebView wv, String url, String runAt) {
    for (UserscriptMeta meta : mLoadedScripts) {
        if (!meta.enabled) continue;
        if (!runAt.equals(meta.runAt)) continue;
        if (!matchesUrl(meta, url)) continue;
        injectOne(wv, meta);
    }
}
```

三个注入点：

| 时机 | 位置 | 适合的 run-at |
|---|---|---|
| 页面开始加载 | `onPageStarted` | `document-start` |
| DOM 就绪 | `onPageFinished` | `document-end` |
| 加载完成 | `onProgressChanged(100)` | `document-idle` |

**注意**：`document-start` 时 DOM 尚未构建，只能做 `window` 层面的操作（拦截 `fetch`、改 `navigator`、注入 CSS）。这是 WebView 的固有限制，要在文档里说明。

### 4.4 修 `write` 工具

```java
private String executeWriteTool(JSONObject args) {
    File f = resolveSandboxFile(pathStr);

    // 写入 userscripts/ 时走安装路径，做文件名清洗 + 元数据校验
    if (isInsideUserscriptsDir(f)) {
        UserscriptMeta meta = UserscriptMeta.parse(content);
        if (meta.matches.isEmpty()) {
            return "Error: 脚本缺少 @match，无法确定生效范围";
        }
        if (!mController.saveUserscript(f.getName(), content)) {
            return "Error: 安装脚本失败";
        }
        return "已安装脚本「" + meta.name + "」，生效范围：" + meta.matches;
    }

    // 其余路径走普通写入
    writeFileUtf8(f, content);
    return "Successfully wrote ...";
}
```

### 4.5 脚本管理界面

新增 `UserscriptSheet extends AcidBottomSheetView`（受益于 ② 的 overlay 注册表，只需 2 个触点）：

```
┌─────────────────────────────────────┐
│ 脚本管理                        [+] │
├─────────────────────────────────────┤
│ 📖 看书模式                  [开关] │
│    *://*/*  ·  v1.0.0               │
│    隐藏图片/侧栏，正文纯文字化        │
├─────────────────────────────────────┤
│ 🛡 Acid Anti-Hijack         [开关] │
│    *://*/*  ·  v1.0.0               │
│    拦截"打开App看全文"遮罩           │
└─────────────────────────────────────┘
```

功能：开关、删除、查看脚本内容、导入文件。

### 4.6 URL 一键安装

挂在 `onDownloadStart`（已存在）：

```java
if (url.endsWith(".user.js")) {
    // 不走下载管理器，改为"安装脚本"流程
    showInstallConfirmDialog(url);
    return;
}
```

安装确认框必须展示：

- 脚本名、版本、描述
- **生效域名列表**（这是安全关键）
- 脚本前 20 行预览
- `[取消] [安装]`

---

## 五、第二阶段：动态工具（建在 userscript 之上）

### 5.1 核心简化

```
自定义工具 ≡ 一个带 @acid-* 元数据的 userscript
```

不再需要 `tools.json`、`CustomTool` 模型、独立注入层。

### 5.2 扩展元数据

```javascript
// ==UserScript==
// @name         看书模式
// @namespace    acid.agent
// @version      1.0.0
// @description  隐藏图片/侧栏/广告，正文改为纯文字阅读视图
// @match        *://*/*
// @run-at       document-idle
// @acid-icon    M 4 5 H 20 V 19 H 4 Z M 7 9 H 17 M 7 12 H 17 M 7 15 H 14
// @acid-menu    true
// @acid-order   1
// ==/UserScript==

(() => {
  document.querySelectorAll('img, aside, .ad, [class*=banner]')
    .forEach(el => el.remove());
  document.body.style.cssText = 'font-size:18px;line-height:1.8;max-width:720px;margin:0 auto;';
})();
```

### 5.3 图标由 Agent 绘制

**解析器约束（已核实）**：

| 支持 | `M L H V C Q` + 小写 + `Z` |
|---|---|
| **不支持** | `A` 弧线、`S` 平滑三次、`T` 平滑二次 |

现有 47 个图标全部避开不支持的命令（圆形用手工贝塞尔拆分）。**Agent 必须遵守同一子集**，否则会画出残缺图形——解析器遇到未知命令是**静默跳过**的：

```java
default:
    i++;        // 悄悄吞掉，不报错
    break;
```

保存前必须校验：

```java
if (iconPath.matches(".*[AaSsTt].*")) {
    return "Error: icon_path 含不支持的命令 (A/S/T)，请只用 M L H V C Q Z";
}
```

### 5.4 前置改造：`SvgIconView` 支持运行时路径

现状只有一个构造函数，只吃编译期常量：

```java
public SvgIconView(Context context, int iconId, int color, int sizeDp) {
    mBasePath = buildIconPath24(iconId);   // 只能走 switch
}
```

新增（约 20 行）：

```java
public SvgIconView(Context context, String svgPath, int color, int sizeDp) {
    super(context);
    mBasePath = parseSvgPath(svgPath);
    // 其余初始化同现有
}
```

`buildIconPath24()` 的 `default` 分支目前画占位圆，动态路径不走这里；但要保证解析失败时**有可见反馈**，而不是静默空白。

### 5.5 `create_script` 工具

在 `AcidSandboxTools` 注册第 6 个工具：

```json
{
  "name": "create_script",
  "description": "为用户创建一个可长期使用的浏览器功能。生成的是标准 userscript，需用户确认后才会出现在菜单。图标由你绘制，24x24 线稿风格，只用 M/L/H/V/C/Q/Z 命令。",
  "parameters": {
    "type": "object",
    "properties": {
      "name":        { "type": "string", "description": "菜单显示名，≤6 个中文字" },
      "description": { "type": "string", "description": "一句话说明用途" },
      "match":       { "type": "string", "description": "生效域名，如 *://*/* 或 *://*.zhihu.com/*" },
      "run_at":      { "type": "string", "enum": ["document-start", "document-end", "document-idle"] },
      "code":        { "type": "string", "description": "脚本主体，IIFE 包裹，不要包含元数据头（由系统生成）" },
      "icon_path":   { "type": "string", "description": "SVG path，24x24 viewBox，仅 M/L/H/V/C/Q/Z" }
    },
    "required": ["name", "match", "code", "icon_path"]
  }
}
```

系统负责拼装完整的元数据头 + 落盘 + 标记为待确认。

### 5.6 菜单动态区

现状：第 2 页 10 项 + 占位格，分页器高度**写死 142dp**：

```java
int pagerHeight = AcidTheme.dp(context, 142);
```

如果只在第 2 页底部追加，会撑高页面导致翻页跳变或裁剪。

**解法（一期）**：第 2 页内嵌 `ScrollView`，动态工具在其中滚动，页面总高不变。

**解法（二期，工具变多时）**：独立第 3 页。

### 5.7 待确认机制

`create_script` 生成后 `enabled=false`，菜单显示黄色角标。

用户点击 → 确认框：

```
┌────────────────────────────────────┐
│  启用「看书模式」？                  │
│                                     │
│  ┌──────┐  生效范围：所有网站        │
│  │ 📖  │  图标由 Agent 绘制         │
│  └──────┘                          │
│  脚本预览：                          │
│  ┌───────────────────────────────┐ │
│  │ (() => {                       │ │
│  │   document.querySelectorAll(   │ │
│  │     'img, aside').forEach(     │ │
│  │     e => e.remove());          │ │
│  └───────────────────────────────┘ │
│                                     │
│  [取消]              [启用]         │
└────────────────────────────────────┘
```

---

## 六、安全模型

### 现状风险（已实测）

- MCP 网关 `Access-Control-Allow-Origin: *`，**无任何鉴权**
- 任意网页可 `fetch('http://127.0.0.1:3090/mcp')`
- 工具执行无来源区分

若直接开放 `create_script`，攻击链：

```
恶意网页 → fetch /mcp → create_script { code: "偷cookie发到evil.com", match: "*://*/*" }
        → 之后每个页面都被注入 → 账号被盗
```

### 三道防线

**防线 1：来源隔离（必须）**

`create_script` 只允许 App 内 Agent 对话调用，MCP 网关一律拒绝。

```java
public ToolExecResult executeTool(String name, JSONObject args, ToolSource source) {
    if ("create_script".equals(name) && source != ToolSource.AGENT_UI) {
        return new ToolExecResult("Error: create_script 只能由用户在 App 内发起。", false, null);
    }
    ...
}
```

改动：`executeTool` 加 `source` 参数，两个调用方各传各的。

**防线 2：默认不启用 + 用户确认（必须）**

- `create_script` 只写 `enabled=false`
- 菜单显示「待确认」角标
- 用户看到完整脚本 + 生效域名后确认
- 未确认的**永不注入**

**防线 3：域名作用域（建议）**

- Agent 生成时默认给具体域名，而非 `*://*/*`
- 用户可改，但改成全站要额外警告

### 补充

- 单脚本上限 64 KB
- 提供「全部禁用」开关
- URL 安装必须显示 `@match` 列表（这是最容易骗人的地方）

---

## 七、工作量

### 第一阶段：引擎（约 320 行）

| 模块 | 文件 | 行数 |
|---|---|---|
| `UserscriptMeta` 解析器 | 新增 | ~120 |
| 域名匹配（glob→regex + 缓存） | 新增 | ~60 |
| 注入点重构（按 run-at 分组） | `UserscriptManager` | ~70 |
| 修 `write` 工具 | `AcidSandboxTools` | ~30 |
| `UserscriptSheet` 管理界面 | 新增 | ~200 |
| URL 一键安装 | `BrowserController` + 确认框 | ~80 |
| **合计** | **3 新增 + 3 修改** | **~560** |

### 第二阶段：动态工具（约 200 行）

| 模块 | 文件 | 行数 |
|---|---|---|
| `create_script` 工具 | `AcidSandboxTools` | ~70 |
| `SvgIconView` path 构造函数 | `AcidIcons` | ~20 |
| 菜单动态区（ScrollView 方案） | `BrowserMenuSheet` | ~120 |
| 确认对话框 | `BrowserMenuSheet` | ~60 |
| `BrowserActivity` 接线 | 注册表 | ~20 |
| **合计** | **1 新增 + 3 修改** | **~290** |

**总计约 850 行，2 个阶段。**

---

## 八、实施顺序

```
阶段 1（引擎）
  ├─ 1.1 UserscriptMeta 解析器
  ├─ 1.2 域名匹配
  ├─ 1.3 注入点重构
  ├─ 1.4 修 write 工具
  ├─ 1.5 UserscriptSheet 管理界面
  └─ 1.6 URL 一键安装
       ↓ 验收：内置脚本能按 @match 只在指定域名生效
       ↓
阶段 2（动态工具）
  ├─ 2.1 SvgIconView path 构造函数
  ├─ 2.2 create_script 工具
  ├─ 2.3 菜单动态区
  ├─ 2.4 确认对话框
  └─ 2.5 接线
       ↓ 验收：用户说"加个看书模式"，菜单出现按钮，确认后可用
```

**阶段 1 独立可用**，即使不做阶段 2 也有价值（修掉全站污染问题）。

---

## 九、未决问题

| # | 问题 | 建议 |
|---|---|---|
| 1 | 内置脚本的 `@match *://*/*` 要不要收紧？ | 保持，它靠脚本内部判断 |
| 2 | `GM_*` API 要不要支持？ | 二期再考虑，用原生桥模拟 `GM_xmlhttpRequest` |
| 3 | 脚本数量上限？ | 30 个 |
| 4 | 是否支持脚本订阅（自动更新）？ | 暂不做 |
| 5 | 先修 MCP 鉴权还是先做功能？ | **建议先修**，否则防线 1 压力太大 |

---

## 十、风险

| 风险 | 等级 | 缓解 |
|---|---|---|
| 恶意网页通过 MCP 装后门 | **高** | 来源隔离 + 默认不启用 |
| `document-start` 时 DOM 未建好，脚本报错 | 中 | 文档说明限制；脚本内自行判断 `document.readyState` |
| 元数据解析异常导致脚本不跑 | 中 | 解析失败降级为 `*://*/*` 兜底 |
| 工具过多撑爆菜单 | 低 | 数量上限 + 内部滚动 |
| 用户装了脚本忘了 | 中 | 管理界面显示来源与时间 |

---

*设计稿 v2 完，待评审。*

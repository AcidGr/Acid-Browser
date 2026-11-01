# 用户脚本编写规范

本文件规定 Acid Android 浏览器用户脚本（userscript）的编写方式。按本文操作即可产出可运行的脚本。

---

## 1. 何时写用户脚本

**写用户脚本**——需求是持久、可复用的能力，跨页面刷新和导航都要生效：

- 阅读模式、去广告、页面净化
- 强制深色、字体放大、排版调整
- 悬浮按钮、表格导出、批量操作
- 自动展开、自动翻页、去弹窗遮罩

**不写用户脚本**——需求是一次性的：

- 单次点击、单次填表、读取当前页面某个值 → 用 `browser(action='click'|'fill'|'eval')`
- 只看工作区文件 → 用 `read` / `bash`

---

## 2. 文件位置与基本结构

调用 `write` 写入 `userscripts/<名称>.user.js`。

- `file_path` 必须是 `userscripts/` 下的 `.user.js` 文件
- `content` 是完整脚本：元数据块 + 实现代码
- 单文件上限 20 MB

### 标准模板

```javascript
// ==UserScript==
// @name         看书模式
// @namespace    acid.agent
// @version      1.0.0
// @description  优化正文排版并隐藏侧边栏
// @match        *://*.zhihu.com/*
// @run-at       document-idle
// ==/UserScript==

(function () {
    'use strict';
    if (window.__acidReaderModeLoaded) return;
    window.__acidReaderModeLoaded = true;

    GM_addStyle(`
        header, aside, .advertisement { display: none !important; }
        body, article { max-width: 760px !important; margin: 0 auto !important; }
    `);
})();
```

---

## 3. 元数据指令

| 指令 | 格式 | 必填 | 说明 |
| :--- | :--- | :---: | :--- |
| `@name` | 字符串 | 是 | 脚本显示名称 |
| `@namespace` | 字符串 | 否 | 作者命名空间，与 `@name` 共同标识脚本 |
| `@version` | 语义版本 | 否 | 如 `1.0.0`，用于检查更新 |
| `@description` | 字符串 | 否 | 一句话说明 |
| `@author` | 字符串 | 否 | 作者 |
| `@match` | 通配符 | 是 | 生效网址，可写多行 |
| `@include` | 通配符 | 否 | 旧式匹配，与 `@match` 等价 |
| `@exclude` | 通配符 | 否 | 排除网址 |
| `@exclude-match` | 通配符 | 否 | 排除网址，与 `@exclude` 等价 |
| `@run-at` | 枚举 | 否 | `document-start` / `document-end` / `document-idle`，默认 `document-idle` |
| `@grant` | API 名 | 否 | 声明使用的 GM API，可写多行 |
| `@require` | URL | 否 | 外部依赖库，安装时自动下载并缓存 |
| `@resource` | `名称 URL` | 否 | 静态资源，用 `GM_getResourceText(名称)` 读取 |
| `@unwrap` | 无值 | 否 | 让脚本在页面作用域运行，不套闭包 |
| `@homepageURL` | URL | 否 | 项目主页 |
| `@supportURL` | URL | 否 | 反馈地址 |
| `@updateURL` | URL | 否 | 检查更新地址，未写则回退到 `@downloadURL` |
| `@downloadURL` | URL | 否 | 脚本下载地址 |

指令名大小写不敏感，`@homepageURL` 与 `@homepageurl` 等价。

### `@match` 写法

```javascript
// @match  *://*.zhihu.com/*
// @match  *://*.juejin.cn/*
// @match  *://*/*            // 全部网站，谨慎使用
// @exclude-match  *://*.zhihu.com/private/*
```

`*://*.example.com/*` 同时匹配 `example.com` 与 `www.example.com`。

---

## 4. `@grant` 规则

- **完全不写 `@grant`**：脚本可使用全部 GM API。
- **写了任意 `@grant`**：只有声明过的 API 存在，其余为 `undefined`。`@grant none` 表示不提供任何 GM API。
- 脚本用到的每个 API 都要声明，包括 `GM_xmlhttpRequest`。

```javascript
// @grant GM_getValue
// @grant GM_setValue
// @grant GM_xmlhttpRequest
```

---

## 5. 可用 API

### 键值存储（每个脚本独立，持久化）

```javascript
GM_setValue(key, value);
GM_getValue(key, defaultValue);
GM_deleteValue(key);
GM_listValues();                    // 返回 key 数组
GM_setValues({ a: 1, b: 2 });       // 批量写
GM_getValues(['a', 'b']);           // 批量读
GM_deleteValues(['a', 'b']);        // 批量删
```

### 存储变更监听

任意标签页写入该 key 时触发，`remote` 为 `true` 表示来自其他标签页。

```javascript
const id = GM_addValueChangeListener('theme', function (name, oldValue, newValue, remote) {
    applyTheme(newValue);
});
GM_removeValueChangeListener(id);
```

### 跨域请求

不受 WebView CORS 限制，自动携带站点 Cookie。

```javascript
GM_xmlhttpRequest({
    method: 'GET',
    url: 'https://api.example.com/data',
    headers: { 'X-Token': 'abc' },
    timeout: 15000,
    responseType: 'json',           // text | json | blob | arraybuffer | document
    onload: function (res) {
        console.log(res.status, res.response);
    },
    onerror: function (err) {},
    ontimeout: function () {},
    onabort: function () {}
});

// 中断请求
const handle = GM_xmlhttpRequest({ url: '...' });
handle.abort();
```

`data` 可传 `FormData`、`URLSearchParams`、数组或对象，会自动序列化。

可用回调：`onload`、`onerror`、`ontimeout`、`onabort`、`onloadstart`、`onreadystatechange`。

### 资源文件

```javascript
// @resource  myCss  https://cdn.example.com/style.css
// @resource  icon    https://cdn.example.com/icon.png

const css  = GM_getResourceText('myCss');   // 文本内容
const icon = GM_getResourceURL('icon');     // data: URL
```

资源在安装时下载并按原始二进制缓存，图片、字体不会损坏。

### 下载与标签页

```javascript
GM_download(url, '文件名.zip');        // 交给浏览器下载器
GM_download({ url: '...', name: '...' });

GM_openInTab(url);                     // 前台新标签页
GM_openInTab(url, { active: false });  // 后台新标签页
GM_openInTab(url, true);               // 后台（旧式参数）
```

### 菜单命令

命令出现在「功能 → 脚本管理」页面顶部的「当前页面的脚本命令」区域，点击即执行。

```javascript
GM_registerMenuCommand('导出表格', function () {
    exportTables();
});
GM_unregisterMenuCommand('导出表格');
```

### 样式与元素

```javascript
GM_addStyle('body { background: #fff !important; }');

GM_addElement('div', { id: 'my-panel', textContent: 'Hello' });
GM_addElement(document.body, 'div', { class: 'panel' });
```

### 系统

```javascript
GM_setClipboard('要复制的文本');
GM_log('调试信息');
GM_notification({ title: '标题', text: '内容' });
GM_info.script.name;      // 脚本名称
GM_info.script.version;
GM_info.scriptHandler;    // "Acid"
```

### Promise 版本

所有 `GM_*` 均有对应的 `GM.*`，返回 Promise。

```javascript
await GM.setValue('k', 'v');
const v = await GM.getValue('k');
const res = await GM.xmlHttpRequest({ url: '...' });
```

---

## 6. 执行时机

脚本按页面真实状态注入：

| `@run-at` | 触发条件 | 适用场景 |
| :--- | :--- | :--- |
| `document-start` | `document.head` 出现后 | 改写 `window`、`navigator`，拦截 `fetch`，注入早期样式 |
| `document-end` | `DOMContentLoaded` | 大部分 DOM 操作 |
| `document-idle` | `load`（默认） | 元素删除、文本替换、事件绑定 |

`document-start` 时 `document.head` 已存在，可以 `appendChild` 样式；但 `document.body` 可能尚未创建。

---

## 7. 防重复执行

单页应用（SPA）路由切换会重复触发注入，脚本必须自带守卫：

```javascript
if (window.__acidMyFeatureLoaded) return;
window.__acidMyFeatureLoaded = true;
```

---

## 8. `@unwrap` 用法

默认脚本包裹在闭包内，顶层变量不会污染页面。需要向页面暴露全局变量时加 `@unwrap`：

```javascript
// ==UserScript==
// @name     页面钩子
// @match    *://*/*
// @unwrap
// ==/UserScript==

var myGlobalApi = { run: function () {} };   // 页面可直接访问
```

`@unwrap` 下 GM API 挂到 `window` 上，脚本主体在页面作用域执行。不需要暴露全局时不要使用。

---

## 9. `@require` 依赖

```javascript
// @require https://cdn.jsdelivr.net/npm/jquery@3.7.1/dist/jquery.min.js
```

安装时自动下载并缓存到 `userscripts/deps/`，脚本执行前先加载。缓存后离线可用。依赖 URL 建议带固定版本号。

---

## 10. 编写示例

### 阅读模式

```javascript
// ==UserScript==
// @name         看书模式
// @namespace    acid.agent
// @version      1.0.0
// @description  优化正文排版，隐藏广告与侧边栏
// @match        *://*.zhihu.com/*
// @match        *://*.juejin.cn/*
// @run-at       document-idle
// ==/UserScript==

(function () {
    'use strict';
    if (window.__acidReaderModeLoaded) return;
    window.__acidReaderModeLoaded = true;

    GM_addStyle(`
        header, aside, .advertisement, [class*="Ad"], [class*="banner"] { display: none !important; }
        body, article, .content {
            max-width: 760px !important;
            margin: 0 auto !important;
            font-size: 18px !important;
            line-height: 1.8 !important;
        }
    `);
})();
```

### 清理唤端弹窗与遮罩

```javascript
// ==UserScript==
// @name         去弹窗拦截
// @namespace    acid.agent
// @version      1.0.0
// @description  自动清理唤端拦截弹窗与遮罩
// @match        *://*/*
// @run-at       document-idle
// ==/UserScript==

(function () {
    'use strict';

    function purge() {
        document.querySelectorAll('.open-app-banner, [class*="download-app"], [id*="openInApp"]')
            .forEach(function (el) { el.remove(); });
        if (document.body) document.body.style.overflow = 'auto';
    }

    purge();
    new MutationObserver(purge).observe(document.documentElement, { childList: true, subtree: true });
})();
```

### 表格导出按钮

```javascript
// ==UserScript==
// @name         网页表格导出器
// @namespace    acid.agent
// @version      1.0.0
// @description  在含表格的页面生成悬浮 CSV 导出按钮
// @match        *://*/*
// @run-at       document-idle
// ==/UserScript==

(function () {
    'use strict';
    if (window.__acidTableExporterLoaded) return;
    window.__acidTableExporterLoaded = true;

    var tables = document.querySelectorAll('table');
    if (!tables.length) return;

    var btn = document.createElement('button');
    btn.textContent = '导出表格 (' + tables.length + ')';
    btn.style.cssText = 'position:fixed;bottom:80px;right:20px;z-index:999999;'
        + 'background:#00BA76;color:#fff;border:none;padding:10px 14px;'
        + 'border-radius:20px;font-weight:bold;font-size:13px;';

    btn.onclick = function () {
        var csv = '';
        tables[0].querySelectorAll('tr').forEach(function (tr) {
            var row = Array.from(tr.querySelectorAll('th, td'))
                .map(function (td) { return '"' + td.innerText.replace(/"/g, '""') + '"'; })
                .join(',');
            csv += row + '\n';
        });
        var blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
        var a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = 'table_export_' + Date.now() + '.csv';
        a.click();
    };

    document.body.appendChild(btn);
})();
```

---

## 11. 安装后的用户操作

脚本写入后自动安装并生效。用户可在「功能 → 脚本管理」中：

- 查看全部脚本及其生效范围
- 用开关启用 / 停用
- 查看详情、编辑源码、检查更新
- 在当前页运行一次
- 删除脚本

告知用户脚本已安装并在匹配网站上自动执行，可在「功能 → 脚本管理」管理。

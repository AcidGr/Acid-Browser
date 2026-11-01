# Skill: Browser Feature & Userscript Authoring

Authoring persistent in-browser extensions and user scripts inside Acid Android Browser via standard `write`.

---

## 1. Overview & Trigger Criteria

Acid Android Browser embeds a native Userscript Engine that executes standard userscripts on matching webpages. All installed scripts live under the `userscripts/` directory and are managed cleanly inside the browser's dedicated **「脚本管理」 (Userscript Management Sheet)** panel, where users can view script details, toggle them on/off, or delete scripts.

> **Note**: Userscripts run automatically in the background on matching websites. They do **not** appear as tool buttons in the browser menu.

### When to Use This Skill
- **Write a userscript (`write(file_path='userscripts/<name>.user.js', ...)`) when**:
  - The user requests a **persistent, reusable capability** (e.g., "Add a reader mode", "block banners on this domain", "add a button to export tables", "auto-expand collapsed text", "force dark mode").
  - The feature should remain available across page reloads and tab navigations.
- **Do NOT write a userscript when**:
  - The task is a one-off inspection or transient interaction (e.g., "click the next button", "what is the price of this item?") -> Use `browser(action='click'|'fill'|'eval')` instead.
  - The user only asks to inspect workspace files -> Use `read`/`bash`.

---

## 2. Authoring Workflow via `write` Tool

To create a new browser script, call `write` targeting a `.user.js` file inside `userscripts/`:
- **`file_path`**: Must be formatted as `userscripts/<script_name>.user.js` (e.g. `userscripts/reader_mode.user.js`).
- **`content`**: Full JavaScript file containing the standard `// ==UserScript==` metadata block followed by the implementation wrapped in an IIFE.

### Standard Template
```javascript
// ==UserScript==
// @name         看书模式
// @namespace    acid.agent
// @version      1.0.0
// @description  优化知乎正文阅读排版与去除侧边栏
// @match        *://*.zhihu.com/*
// @run-at       document-idle
// ==/UserScript==

(function () {
    'use strict';
    // Feature implementation here
})();
```

### Metadata Directives Reference

| Directive | Format | Required | Description |
| :--- | :--- | :---: | :--- |
| `@name` | string | **Yes** | Display name of the script shown in the script management sheet. Keep it clear, e.g., `"知乎阅读模式"`, `"表格一键导出"`. |
| `@description`| string | No | One sentence explaining what the script accomplishes. Shown in the script detail sheet. |
| `@match` | glob pattern | **Yes** | Target site glob (e.g. `*://*.zhihu.com/*`). Can specify multiple `@match` lines for multiple domains! Use `*://*/*` only for universal scripts. |
| `@run-at` | string | No | Execution timing: `"document-idle"` (default, recommended), `"document-end"`, or `"document-start"`. |
| `@version` | string | No | Semantic version string, e.g., `"1.0.0"`. |
| `@namespace` | string | No | Script namespace / author identifier, e.g., `"acid.agent"`. |

---

## 3. Runtime Features & Tampermonkey Compatibility

Acid Browser embeds a full-featured GreaseMonkey / Tampermonkey compatibility runtime:

1. **Native `GM_*` and Promise-based `GM.*` APIs Supported**:
   - `GM_setValue`, `GM_getValue`, `GM_deleteValue`, `GM_listValues`: Sandboxed persistent key-value storage per script.
   - `GM_xmlhttpRequest` / `GM.xmlHttpRequest`: Cross-origin HTTP requests bypassing WebView CORS restrictions (supports text, JSON, and binary Blob/ArrayBuffer responses).
   - `GM_addStyle`: Dynamic CSS style injection.
   - `GM_addElement`: Safe DOM element creation and attachment.
   - `GM_setClipboard`: System clipboard access.
   - `GM_registerMenuCommand` / `GM_unregisterMenuCommand`: Page-level custom action commands.
   - `GM_info`: Script metadata object (`name`, `version`, `description`, etc.).
   - `unsafeWindow` / `window.wrappedJSObject`: Wrapped page window object.
2. **`@require` and `@resource` Support**:
   - External library CDNs (e.g. jQuery, Lodash, Vue) declared with `@require` are automatically downloaded and cached locally upon installation, then bundled seamlessly before execution.
3. **Script Size Limit**:
   - Maximum script size is **20 MB** (`MAX_SCRIPT_BYTES`). Easily accommodates heavy frontend bundles and UI component libraries.
4. **Lifecycle (`@run-at`) Differences**:
   - `document-idle` (Default): Runs after DOM and subresources finish loading. Safest for element removal, text modifications, and event listeners.
   - `document-start`: Runs at `onPageStarted` when DOM is not yet constructed. **Cannot access `document.body`**. Only use to patch `window`/`navigator` globals, intercept `window.fetch`, or append styles to `document.documentElement`.
5. **Idempotency & Re-entrancy**:
   - Always guard script execution with a unique property on `window` to prevent duplicate initialization during SPA client-side navigations:
     ```javascript
     if (window.__acidFeatureNameLoaded) return;
     window.__acidFeatureNameLoaded = true;
     ```

---

## 4. Script Management & User Control

When a userscript is written to `userscripts/*.user.js` via `write`:
1. The Userscript Engine automatically validates metadata, registers it, and installs it into `userscripts/`.
2. Once installed, it is active and will execute automatically whenever the browser navigates to a URL matching `@match`.
3. **User Control**: The user can open **「功能 -> 脚本管理」** at any time to:
   - View all installed scripts and their target URL scopes;
   - Enable or disable any script via an ON/OFF toggle switch;
   - View script code or delete scripts.
4. **Agent Action**: Inform the user that the script has been installed and is active on matching websites, and they can manage or toggle it anytime in 「功能 -> 脚本管理」.

---

## 5. Complete Authoring Examples

### Pattern A: Clean Reading Mode (DOM Cleaner + Style Injection)
```javascript
// Call write(file_path="userscripts/reader_mode.user.js", content=...)
// ==UserScript==
// @name         看书模式
// @namespace    acid.agent
// @version      1.0.0
// @description  优化正文排版，隐藏广告与无关侧边栏
// @match        *://*.zhihu.com/*
// @match        *://*.juejin.cn/*
// @run-at       document-idle
// ==/UserScript==

(function () {
    'use strict';
    if (window.__acidReaderModeLoaded) return;
    window.__acidReaderModeLoaded = true;

    const style = document.createElement('style');
    style.id = 'acid-reader-mode-style';
    style.textContent = `
        header, aside, .advertisement, [class*="Ad"], [class*="banner"] { display: none !important; }
        body, article, .content { max-width: 760px !important; margin: 0 auto !important; font-size: 18px !important; line-height: 1.8 !important; }
    `;
    if (!document.getElementById('acid-reader-mode-style')) {
        (document.head || document.documentElement).appendChild(style);
    }
})();
```

### Pattern B: Continuous Floating Banner Remover
```javascript
// Call write(file_path="userscripts/anti_app_hijack.user.js", content=...)
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
    function purgeAnnoyances() {
        const targets = document.querySelectorAll('.open-app-banner, [class*="download-app"], [id*="openInApp"]');
        targets.forEach(el => el.remove());
        if (document.body) document.body.style.overflow = 'auto';
    }

    purgeAnnoyances();
    const observer = new MutationObserver(purgeAnnoyances);
    observer.observe(document.documentElement, { childList: true, subtree: true });
})();
```

### Pattern C: Floating Table Exporter Button
```javascript
// Call write(file_path="userscripts/table_exporter.user.js", content=...)
// ==UserScript==
// @name         网页表格导出器
// @namespace    acid.agent
// @version      1.0.0
// @description  在包含表格的页面生成悬浮下载 CSV 按钮
// @match        *://*/*
// @run-at       document-idle
// ==/UserScript==

(function () {
    'use strict';
    if (window.__acidTableExporterLoaded) return;
    window.__acidTableExporterLoaded = true;

    window.addEventListener('load', () => {
        const tables = document.querySelectorAll('table');
        if (!tables.length) return;

        const btn = document.createElement('button');
        btn.textContent = `📊 导出表格 (${tables.length})`;
        btn.style.cssText = `
            position: fixed; bottom: 80px; right: 20px; z-index: 999999;
            background: #00BA76; color: #fff; border: none; padding: 10px 14px;
            border-radius: 20px; font-weight: bold; box-shadow: 0 4px 12px rgba(0,0,0,0.25);
            font-size: 13px; cursor: pointer;
        `;
        btn.onclick = () => {
            let csv = '';
            tables[0].querySelectorAll('tr').forEach(tr => {
                const row = Array.from(tr.querySelectorAll('th, td')).map(td => `"${td.innerText.replace(/"/g, '""')}"`).join(',');
                csv += row + '\n';
            });
            const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
            const a = document.createElement('a');
            a.href = URL.createObjectURL(blob);
            a.download = `table_export_${Date.now()}.csv`;
            a.click();
        };
        document.body.appendChild(btn);
    });
})();
```

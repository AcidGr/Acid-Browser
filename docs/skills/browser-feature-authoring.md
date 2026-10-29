# Skill: Browser Feature & Userscript Authoring

Authoring persistent in-browser extensions, user scripts, and dynamic menu-card tools inside Acid Android Browser.

---

## 1. Overview & Trigger Criteria

Acid Android Browser embeds a native Userscript Engine that turns standard user scripts into **first-class browser features**. When marked with Acid metadata, scripts are dynamically rendered as native interactive cards in the browser's bottom-right functional menu (`BrowserMenuSheet`).

### When to Use This Skill
- **Use `create_script` when**:
  - The user requests a **persistent, reusable capability** (e.g., "Add a reader mode", "block banners on this domain", "add a button to export tables", "auto-expand collapsed text", "force dark mode").
  - The feature should remain available across page reloads and tab navigations.
  - The user wants a convenient button/toggle in the browser menu.
- **Do NOT use `create_script` when**:
  - The task is a one-off inspection or transient interaction (e.g., "click the next button", "what is the price of this item?") -> Use `browser(action='click'|'fill'|'eval')` instead.
  - The user only asks to inspect workspace files -> Use `read`/`bash`.

---

## 2. Tool Reference: `create_script`

Call `create_script` with the following arguments:

| Parameter | Type | Required | Description |
| :--- | :--- | :---: | :--- |
| `name` | string | Yes | Menu label shown to the user. **Keep it concise** (<= 6 Chinese characters or <= 15 English characters), e.g., `"看书模式"`, `"AdBlock"`, `"Export Table"`. |
| `description`| string | No | One clear sentence describing what the tool accomplishes. |
| `match` | string | Yes | Target site glob. Follow the **Principle of Least Privilege**: prefer domain-specific scopes (e.g., `*://*.zhihu.com/*`) over broad wildcards (`*://*/*`) unless the tool is universally applicable. |
| `run_at` | string | No | Lifecycle timing: `"document-idle"` (default, recommended), `"document-end"`, or `"document-start"`. |
| `code` | string | Yes | Raw JavaScript body without the `==UserScript==` header. It will be wrapped into an IIFE automatically. |
| `icon_path` | string | Yes | SVG path command string within a `24x24` viewport. **Must strictly adhere to Acid SVG subset rules**. |

---

## 3. Acid SVG Path Grammar & Constraints

The in-app vector renderer (`AcidIcons.java`) uses a lightweight, high-performance canvas path builder. **It is strict and will reject invalid paths.**

### Whitelist & Blacklist
- **ALLOWED Commands**: `M`, `L`, `H`, `V`, `C`, `Q`, `Z` (case-insensitive, coordinates separated by spaces or commas).
- **FORBIDDEN Commands**: `A` (elliptic arcs), `S` (smooth cubic curves), `T` (smooth quadratic curves).
  > **CRITICAL**: Never emit `A`, `S`, or `T`. Any path containing these characters triggers `Error: invalid icon_path` and fails installation.
- **Viewport**: Strictly `24 x 24` (coordinates from `0` to `24`).

### Curated Ready-to-Use Icon Library
Copy and adapt from these verified SVG paths:

| Icon | Purpose | SVG Path (`icon_path`) |
| :--- | :--- | :--- |
| 📖 **Book / Reader** | Reading mode, focus mode | `M 5 4 H 19 V 20 H 5 Z M 8 8 H 16 M 8 12 H 16 M 8 16 H 13` |
| 🛡 **Shield / Guard** | Ad blocker, anti-hijack | `M 12 3 L 20 6 V 12 C 20 16 16 20 12 21 C 8 20 4 16 4 12 V 6 Z` |
| 🧹 **Broom / Cleaner** | Clean clutter, strip banners | `M 15 3 L 21 9 L 14 16 L 8 10 Z M 8 10 L 4 14 V 20 H 10 L 14 16` |
| 🔤 **Text / Font** | Typography, font size | `M 4 5 H 20 M 12 5 V 19 M 8 19 H 16` |
| ☀️ **Sun / Contrast** | Theme switch, contrast | `M 12 7 C 9.24 7 7 9.24 7 12 C 7 14.76 9.24 17 12 17 C 14.76 17 17 14.76 17 12 C 17 9.24 14.76 7 12 7 Z M 12 2 V 4 M 12 20 V 22 M 2 12 H 4 M 20 12 H 22` |
| 📥 **Download / Export**| Extract data, save table | `M 12 3 V 15 M 7 10 L 12 15 L 17 10 M 4 20 H 20` |
| ⚡ **Lightning / Action** | Speedup, one-click action | `M 13 2 L 4 14 H 11 L 9 22 L 20 10 H 13 Z` |
| 👁 **Eye / Unhide** | Reveal hidden content | `M 2 12 C 4 7 8 4 12 4 C 16 4 20 7 22 12 C 20 17 16 20 12 20 C 8 20 4 17 2 12 Z M 12 9 C 10.34 9 9 10.34 9 12 C 9 13.66 10.34 15 12 15 C 13.66 15 15 13.66 15 12 C 15 10.34 13.66 9 12 9 Z` |

---

## 4. Runtime Boundaries & Android WebView Constraints

Acid Browser runs scripts directly inside the Android WebView page context (`evaluateJavascript`). Understand the following runtime limitations:

1. **NO `GM_*` APIs**:
   - There is NO Tampermonkey extension sandbox (`GM_xmlhttpRequest`, `GM_setValue`, `GM_getValue`, `GM_addStyle`, `GM_setClipboard`, etc. are **UNDEFINED**).
   - Use standard Web APIs: `window.localStorage`, `window.sessionStorage`, `window.fetch`, and `navigator.clipboard`.
2. **NO `@require` or `@resource`**:
   - External script CDNs (such as external jQuery bundles) are not pre-fetched. Write vanilla JavaScript.
3. **Script Size Limit**:
   - Maximum script size is **20 MB** (`MAX_SCRIPT_BYTES`). Easily accommodates heavy frontend bundles, UI component libraries, and embedded offline resources.
4. **Lifecycle (`run_at`) Differences**:
   - `document-idle` (Default): Runs after DOM and subresources finish loading. Safest for element removal, text modifications, and event listeners.
   - `document-start`: Runs at `onPageStarted` when DOM is not yet constructed. **Cannot access `document.body`**. Only use to patch `window`/`navigator` globals, intercept `window.fetch`, or append styles to `document.documentElement`.
5. **Idempotency & Re-entrancy**:
   - Always guard script execution with a unique property on `window` to prevent duplicate initialization during SPA client-side navigations:
     ```javascript
     if (window.__acidFeatureNameLoaded) return;
     window.__acidFeatureNameLoaded = true;
     ```

---

## 5. Security & Human-in-the-Loop Activation

When `create_script` is executed:
1. The script is created with `@acid-menu true` and `@acid-enabled false` by default.
2. It appears immediately in the browser's menu card under **快捷工具 (Custom Tools)** marked as `(待启用)` with an amber indicator.
3. **It will NOT run until the human user opens the menu and taps "Enable"** after reviewing the code.
4. **Agent Action**: Always tell the user that the tool has been authored and guide them to open the menu (`功能 -> 快捷工具`) to review and enable it.

---

## 6. Authoring Patterns & Templates

### Pattern A: Clean Reading Mode (DOM Cleaner + Style Injection)
```javascript
// Tool arguments:
// name: "看书模式"
// match: "*://*.zhihu.com/*"
// icon_path: "M 5 4 H 19 V 20 H 5 Z M 8 8 H 16 M 8 12 H 16 M 8 16 H 13"
// code:
const style = document.createElement('style');
style.id = 'acid-reader-mode-style';
style.textContent = `
    header, aside, .advertisement, [class*="Ad"], [class*="banner"] { display: none !important; }
    body, article, .content { max-width: 760px !important; margin: 0 auto !important; font-size: 18px !important; line-height: 1.8 !important; }
`;
if (!document.getElementById('acid-reader-mode-style')) {
    (document.head || document.documentElement).appendChild(style);
}
```

### Pattern B: Continuous Floating Banner & App-Hijack Remover
```javascript
// Tool arguments:
// name: "去弹窗拦截"
// match: "*://*/*"
// icon_path: "M 12 3 L 20 6 V 12 C 20 16 16 20 12 21 C 8 20 4 16 4 12 V 6 Z"
// code:
function purgeAnnoyances() {
    const targets = document.querySelectorAll('.open-app-banner, [class*="download-app"], [id*="openInApp"]');
    targets.forEach(el => el.remove());
    document.body && (document.body.style.overflow = 'auto');
    document.documentElement && (document.documentElement.style.overflow = 'auto');
}
purgeAnnoyances();
const observer = new MutationObserver(purgeAnnoyances);
observer.observe(document.documentElement, { childList: true, subtree: true });
```

### Pattern C: Table Exporter to CSV / Clipboard
```javascript
// Tool arguments:
// name: "导出表格"
// match: "*://*/*"
// icon_path: "M 12 3 V 15 M 7 10 L 12 15 L 17 10 M 4 20 H 20"
// code:
const tables = document.querySelectorAll('table');
if (!tables.length) {
    alert('当前页面未检测到数据表格');
    return;
}
let csv = [];
const rows = tables[0].querySelectorAll('tr');
rows.forEach(row => {
    const cols = row.querySelectorAll('th, td');
    const rowData = Array.from(cols).map(c => '"' + (c.innerText || '').replace(/"/g, '""').trim() + '"');
    csv.push(rowData.join(','));
});
const blob = new Blob([csv.join('\n')], { type: 'text/csv;charset=utf-8;' });
const url = URL.createObjectURL(blob);
const a = document.createElement('a');
a.href = url;
a.download = (document.title || 'table_export') + '.csv';
a.click();
URL.revokeObjectURL(url);
```

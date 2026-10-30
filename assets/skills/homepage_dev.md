# Skill: Custom Homepage Design & Authoring

Autonomous, bespoke browser homepage creation and styling inside Acid Android Browser via standard `write` and `edit`.

---

## 1. Overview & Trigger Criteria

Acid Android Browser empowers the user to completely replace and customize their default new-tab homepage. The homepage is backed by a dedicated directory inside the workspace sandbox (`homepage/index.html`) that enjoys full local file access, offline instant loading, and seamless integration with browser search and user bookmarks.

### When to Use This Skill
- **Use this skill when**:
  - The user requests to create, design, or customize their browser homepage (e.g. "帮我做一个主页", "重新设计我的主页", "把主页换成赛博朋克风/极简风", "主页加一个时钟和语录").
  - The user asks to tweak existing homepage elements (e.g. change wallpaper, add widgets, adjust bookmark grid).
- **Do NOT use this skill when**:
  - The user asks to write a userscript for an external website (use `skills/userscript_dev.md` instead).
  - The user only wants to navigate to an external URL or manage settings.

---

## 2. Interactive Discovery Workflow (Mandatory First Turn)

> **CRITICAL RULE**: When asked to create or redesign a homepage, DO NOT write code immediately unless the user has already specified all visual, functional, and layout details in their opening prompt.
>
> In your very first reply, act as a professional UX/UI designer and ask clarifying questions so the resulting homepage perfectly matches their personal taste.

### Discovery Question Template
Present clear, structured options in your response:

```markdown
没问题！我是你的专属主页设计师。为了做出完全符合你心意的主页，想先了解一下你的偏好：

1. **视觉风格与背景**：
   - A. 极简冷淡（纯黑 OLED / 纯白 / 磨砂深灰）
   - B. 动态与流光（经典三色平滑渐变、极光微动效）
   - C. 高清每日壁纸（必应每日美图、二次元动漫风景、随机高质风景摄影）
   - D. 毛玻璃质感（深色磨砂卡片拟态 Glassmorphism）

2. **核心头部组件**（可多选）：
   - [ ] 炫彩大数字时钟 + 实时日期星期
   - [ ] 一言语录（点击可换一句，动漫/文学/游戏金句）
   - [ ] 每日精选古诗词
   - [ ] 极简天气微件（基于 IP 自动获取温度与天气）

3. **快捷方式（书签）**：
   - 是否需要我直接读取你当前的浏览器书签（`bookmarks.json`），自动生成类似 Via 风格的应用卡片磁贴？

4. **搜索栏偏好**：
   - 居中圆角搜索框（回车直接调用默认搜索引擎），是否需要添加常用引擎快捷切换？
```

Once the user replies with their preferences, proceed to build the homepage according to their choices.

---

## 3. Recommended Stable & Zero-Key APIs

The Acid WebView sandbox permits cross-origin network requests (`fetch`) from `file:///` origins. When designing dynamic widgets, prefer these reliable, fast, zero-authentication endpoints:

### 1. Dynamic Quotes & Poetry
- **Hitokoto (一言)**:
  - Anime / Comic / Games: `https://v1.hitokoto.cn/?c=a&c=b&c=c&max_length=30`
  - Literature / Philosophy / Original: `https://v1.hitokoto.cn/?c=d&c=e&c=k&max_length=30`
  - JSON format: `{"hitokoto": "句子内容", "from": "出处", "from_who": "作者"}`
- **Jinrishici (今日诗词)**:
  - `https://v2.jinrishici.com/one.json`
  - Automatically matches seasonal and temporal Chinese poetry based on client IP: `{"data": {"content": "...", "origin": {"title": "...", "author": "..."}}}`

### 2. High-Definition Wallpapers & Backgrounds
- **Bing Daily Wallpaper (1920x1080 Direct Image)**:
  - `https://bing.biturl.top/?resolution=1920&format=image&index=0&mkt=zh-CN`
- **Lorem Picsum High-Res Photography (Ideal for Blurred Backgrounds)**:
  - Blurred portrait photo: `https://picsum.photos/1080/1920?blur=2`
  - Crisp photography: `https://picsum.photos/1080/1920`
- **Anime Landscape Wallpaper**:
  - `https://api.paugram.com/wallpaper/` (Fast HTTPS direct image)

### 3. Lightweight Weather
- **wttr.in Weather API**:
  - `https://wttr.in/?format=j1` (Returns JSON with current condition, temperature in °C, wind, and forecast)

---

## 4. Architecture & Engineering Standards

### File Structure
- Primary entry point: **`homepage/index.html`** (relative to sandbox workspace).
- Supporting files (optional): `homepage/style.css`, `homepage/app.js`, `homepage/bg.jpg`.
- The `homepage/` directory is permanently protected in Acid Browser and will **NEVER** be deleted during workspace temp cleanup.

### Viewport & Theme Declarations
Always declare modern mobile viewport and color-scheme in `<head>`:
```html
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover">
<meta name="color-scheme" content="light dark">
```

### Native Search Integration (`acid://search`)
Acid Browser natively intercepts `acid://search?q=...` navigation and routes it to the user's currently configured search engine (Google, Bing, Baidu, DuckDuckGo, etc.).
Always submit searches via:
```javascript
function doSearch(kw) {
    if (!kw || !kw.trim()) return;
    window.location.href = 'acid://search?q=' + encodeURIComponent(kw.trim());
}
```
Or via standard form:
```html
<form action="acid://search" method="get">
    <input type="text" name="q" placeholder="搜索或输入网址..." autocomplete="off">
</form>
```

### Bookmark Data Integration
Before writing `homepage/index.html`, call `read(file_path='bookmarks.json')` to inspect existing bookmarks.
Render bookmarks into a responsive grid:
- Colorful circular icon badge (using the first character of the title or domain name with a deterministic pastel background).
- Site title.
- Link anchor (`<a href="...">`) to the target URL.

### Dark Mode Adaptation
Always use CSS variables and `@media (prefers-color-scheme: dark)`:
```css
:root {
    --bg: #F8FAFC;
    --card: rgba(255, 255, 255, 0.85);
    --text: #0F172A;
    --muted: #64748B;
}
@media (prefers-color-scheme: dark) {
    :root {
        --bg: #0A0E14;
        --card: rgba(18, 24, 34, 0.85);
        --text: #F1F5F9;
        --muted: #94A3B8;
    }
}
```

---

## 5. Delivery & Verification Closed Loop

After creating or updating `homepage/index.html`:
1. **Navigate to the homepage**:
   `browser(action='navigate', url='acid://home')`
2. **Observe rendered page**:
   `browser(action='observe')`
3. **Verify visual integrity**:
   - Check that clock, search bar, and bookmark tiles are cleanly centered and visible.
   - Confirm there are no broken images, missing elements, or horizontal overflow bars.
4. **Present the result to the user**:
   Summarize the implemented theme, widgets, and APIs in clean Markdown, and inform the user that clicking the Home icon or opening a new tab will show their new homepage immediately.

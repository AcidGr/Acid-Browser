# 自定义主页编写规范

本文件规定 Acid Android 浏览器自定义主页的编写方式。主页是用户打开浏览器和新标签页时看到的页面。

---

## 1. 何时使用本规范

用户要求设计、修改、美化浏览器主页时使用。

涉及以下需求时不要动主页：修改浏览器设置 → 用设置面板；修改书签数据 → 编辑 `bookmarks.json`。

---

## 2. 先问清偏好，再动手

主页是高度个人化的界面。首次接到主页需求时，先向用户确认以下问题，不要直接开始写代码：

1. **风格倾向**：极简纯色 / 毛玻璃 / 深色科技 / 自然风景 / 二次元
2. **主色或壁纸**：指定颜色、指定图片，还是交给浏览器每日随机
3. **需要哪些组件**：时钟、日期、搜索框、书签、便签、天气、一言、倒计时
4. **布局**：居中单列 / 上下分区 / 卡片网格
5. **是否保留官方默认**：完全重做，还是在默认模板上调整

用户已明确说明的项直接采用，不必重复询问。

---

## 3. 文件结构

| 路径 | 说明 |
| :--- | :--- |
| `homepage/index.html` | 入口文件，必需 |
| `homepage/style.css` | 可选，样式 |
| `homepage/app.js` | 可选，脚本 |
| `homepage/icons/<域名>.png` | 可选，自定义图标 |

`homepage/` 目录在浏览器清理工作区临时文件时不会被删除。

### 默认模板标记

官方默认主页第一行含 `<!-- ACID_DEFAULT_HOMEPAGE -->`。浏览器检测到该标记时会用官方模板覆盖它。

**一旦为用户定制主页，必须删除这一行。** 删除后浏览器不再覆盖该文件，用户的定制内容在应用重启和版本升级后都会保留。

---

## 4. 必需的头部声明

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover">
    <meta name="color-scheme" content="light dark">
</head>
</html>
```

---

## 5. 原生搜索协议

浏览器拦截 `acid://search?q=...` 并交给用户当前配置的搜索引擎（Google、Bing、百度、DuckDuckGo 等）。

```javascript
function doSearch(keyword) {
    if (!keyword || !keyword.trim()) return;
    window.location.href = 'acid://search?q=' + encodeURIComponent(keyword.trim());
}
```

用表单提交：

```html
<form action="acid://search" method="get">
    <input type="text" name="q" placeholder="搜索或输入网址" autocomplete="off">
</form>
```

---

## 6. 书签集成

书签数据存放在工作区根目录的 `../bookmarks.json`。

浏览器在书签变化时调用 `window.__updateAcidBookmarks(bookmarksArray)` 推送最新数据。

### 必须实现

```javascript
window.__updateAcidBookmarks = function (list) {
    renderBookmarks(list);
};

// 首次加载时读取本地数据
function loadBookmarks() {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', '../bookmarks.json', true);
    xhr.onload = function () {
        try {
            renderBookmarks(JSON.parse(xhr.responseText));
        } catch (e) {}
    };
    xhr.send();
}

loadBookmarks();
window.addEventListener('focus', loadBookmarks);
```

`bookmarks.json` 结构：

```json
[
  { "title": "知乎", "url": "https://www.zhihu.com" },
  { "title": "GitHub", "url": "https://github.com" }
]
```

### 可滚动书签区

头部和搜索框固定，书签区独立滚动：

```css
body { height: 100vh; margin: 0; display: flex; flex-direction: column; overflow: hidden; }
.header { flex-shrink: 0; }
.bookmarks {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    -webkit-overflow-scrolling: touch;
}
```

### 图标

按域名缓存已验证可用的图标地址到 `localStorage` 的 `acid_favicon_verified_v1`，避免每次加载重复探测 404。

```javascript
function faviconFor(host) {
    var cache = JSON.parse(localStorage.getItem('acid_favicon_verified_v1') || '{}');
    if (cache[host]) return cache[host];
    return 'https://' + host + '/favicon.ico';
}
```

图标不可用时用首字母色块替代。

---

## 7. 深色模式

用 CSS 变量配合 `prefers-color-scheme`：

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

## 8. 可用接口

主页从 `file:///` 源发起跨域 `fetch` 不受限制，可直接使用以下免密钥接口。

### 一言（随机句子）

```
https://v1.hitokoto.cn/?c=a&c=b&c=c&max_length=30     动漫游戏
https://v1.hitokoto.cn/?c=d&c=e&c=k&max_length=30     文学哲理
```

返回：`{"hitokoto": "句子", "from": "出处", "from_who": "作者"}`

### 今日诗词

```
https://v2.jinrishici.com/one.json
```

返回：`{"data": {"content": "...", "origin": {"title": "...", "author": "..."}}}`

### 壁纸

```
https://bing.biturl.top/?resolution=1920&format=image&index=0&mkt=zh-CN     必应每日
https://picsum.photos/1080/1920?blur=2                                      高斯模糊照片
https://picsum.photos/1080/1920                                             清晰照片
https://api.paugram.com/wallpaper/                                          二次元风景
```

### 天气

```
https://wttr.in/?format=j1
```

返回 JSON，含当前温度（°C）、天气状况、风速和预报。

---

## 9. 交付流程

1. 写入 `homepage/index.html`（删除 `<!-- ACID_DEFAULT_HOMEPAGE -->`）
2. 跳转验证：`browser(action='navigate', url='acid://home')`
3. 观察渲染：`browser(action='observe')`
4. 检查项：
   - 时钟、搜索框、书签正常显示
   - 无图片加载失败
   - 无横向滚动条
   - 深色模式下文字可读
5. 向用户说明已实现的组件与配色，告知点击主页按钮或新建标签页即可看到效果。

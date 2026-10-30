# Acid Browser · AI 内核智能浏览器

**Acid Browser** 是一款专为 Android 打造的超轻量、高性能、原生融合 AI Agent 循环的现代浏览器。

基于纯原生 Android `WebView` 与自主感知识别引擎构建，集成 Set-of-Mark (SoM) 视觉标记、免键盘 React-safe 填充、本地沙盒工作区、工业级安全 MCP 控制网关与开放式技能系统，**全应用零外部第三方依赖**（不依赖 AndroidX、不依赖外部重量级 SDK），安装包仅约 370 KB，冷启动瞬时秒开。

---

## 核心特性与架构亮点

### 1. 现代化极简流光自定义主页 (`acid://home`)
- **开箱即用高颜值**：内置三色平滑渐变大时钟（蓝 ➔ 桃红 ➔ 暖黄）、流光微边框日期星期胶囊、一言（Hitokoto）灵动语录（点击即换一句）；
- **原生搜索引擎联动**：居中磨砂圆角搜索栏原生支持 `acid://search?q=...` 协议，无缝联动用户配置的默认搜索引擎（Google / 百度 / 必应 / DuckDuckGo / 自定义引擎）；
- **书签应用磁贴网格**：自动联动本地书签列表，智能提炼首字彩色圆形徽标与磨砂玻璃卡片；
- **双层自愈与容灾兜底**：工作区主页位于 `homepage/index.html`，缺失时自动从内置模板重新释放；极端情况下无缝 Fallback 回退至官方内置靶场页，绝不白屏。

### 2. 原生自主 AI Agent (Acid Agent)
- **自主决策闭环 (ReAct)**：搭载 5 大核心底层工具（`browser`, `read`, `write`, `edit`, `bash`），支持页面智能浏览、局部滚动、多标签页调度与人机接管（HITL Takeover）；
- **智能免键盘填充**：针对 React / Vue / SPA 现代网页，通过合成原生输入事件序列完成可靠表单填充，不弹软键盘、不污染宿主焦点；
- **视觉感知与定位**：原生 Set-of-Mark (SoM) 动态编号注入，实现像素级高精度的语义句柄定位与点击；
- **双端视口自由切换**：支持一键切换 PC 桌面 1280px 宽视口与桌面 UA，解锁隐藏桌面端元素与完整 DOM。

### 3. 开放式技能系统 (Agent Skills)
- **按需渐进式披露 (Progressive Disclosure)**：与业界顶尖 Agent 范式对齐，技能采用标准 Markdown 编写，按需 JIT 加载，平时 0 Token 消耗；
- **《主页设计与开发向导技能》(`homepage_dev.md`)**：强制首轮向导提问 SOP，收集用户视觉与布局偏好，推荐免 Key 高速稳定接口库（一言、今日诗词、必应壁纸、极简天气等），完成闭环自检交付；
- **《用户脚本开发技能》(`userscript_dev.md`)**：指导 Agent 编写跨页面长期生效的持久化网页脚本；
- **独立技能管理面板 (`SkillSheet`)**：二级抽屉管理界面，支持查看官方核心技能详情、一键重置默认、从剪贴板或手动输入添加第三方自定义技能（`*.md`），Agent 自动动态编目即装即用。

### 4. 工业级本地安全 MCP 网关 (`127.0.0.1:3090`)
- **全协议兼容**：遵循 Model Context Protocol 标准规范，支持 JSON-RPC 2.0 与 SSE 传输流，支持 Cursor、Claude Desktop、DSH 等外部开发环境直连遥控；
- **高等级安全认证**：集成 Bearer Token 令牌鉴权与 CORS 保护；支持局域网安全开关，默认仅限本地回环访问；
- **专属状态面板 (`McpSheet`)**：一键查看本地与局域网端点、复制认证令牌、重置密钥与安全防范警示。

### 5. 完备的现代浏览器基础基建
- **多标签页智能调度**：网格缩略图卡片概览（`TabsOverviewView`），支持单卡滑动关闭、全部关闭与防误关兜底；
- **全量浏览历史**：解除传统条数限制，智能时间分桶（今天 / 昨天 / 过去 7 天 / 更早），毫秒级实时搜索过滤；
- **书签收藏管理**：支持一键收藏当前页、实时模糊搜索、排序与编辑；
- **原生文件下载接管**：后台断点续传，下载进度实时通知，内置 `DownloadsSheet` 统一管理与系统分享；
- **广告拦截引擎 (AdBlock)**：支持标准 ABP 语法规则（`||domain^`, `@@||domain^`, `##selector`），支持内置规则与自定义规则热重载；
- **恶意唤端拦截盾牌**：内置防跳 App 核心插件，杜绝主流网站未经许可强制拉起 Native App；
- **真 OLED 纯黑夜间模式**：高对比度深浅色一键切换，完美消除夜间强光刺眼；
- **无痕隐私模式 (Incognito)**：无痕状态下彻底阻断历史记录落盘与网页缓存。

---

## 单一事实源 (SSOT) 与工作区结构

所有关键数据与设置均以人机共读的多行格式化 JSON 沉淀在沙盒工作区中，并由 `FileObserver` 与运行时工具实施双向热重载，具备严格的白名单免疫保护：

```text
/data/user/0/com.acid.browser/files/acid_workspace/
├── settings.json              # 全局浏览器配置唯一事实源（单键修改即刻生效）
├── bookmarks.json             # 用户书签收藏（支持双向同步与外部热重载）
├── history.json               # 全量浏览历史（带时间戳，支持无损检索）
├── homepage/
│   └── index.html             # 自定义主页主入口（支持同目录扩展 CSS/JS/图片）
├── skills/
│   ├── homepage_dev.md        # 官方主页设计与向导技能
│   └── userscript_dev.md      # 官方用户脚本开发技能
├── userscripts/
│   └── anti_app_hijack.user.js # 默认防跳 App 核心用户脚本
└── adblock/
    └── custom_rules.txt       # 自定义广告拦截规则库
```

> **自愈保障**：若工作区中的任何核心事实源文件被外部误删，浏览器在下次启动时均会自动从底层数据或 assets 安全资产库重新自愈生成，绝无崩溃或白屏风险。

---

## 权限说明

Acid Browser 严格遵循“最小必要权限”原则，仅申请以下 5 项基础权限：

| 权限名称 | 对应功能场景 |
| :--- | :--- |
| `INTERNET` | 网页加载、在线模型接口调用、OTA 版本检查 |
| `ACCESS_NETWORK_STATE` | 探测当前网络连通状态与异常重试 |
| `READ_EXTERNAL_STORAGE` | 读取系统 `Download` 目录中已下载的文件 |
| `WRITE_EXTERNAL_STORAGE` | 将网页附件与安装包保存至本地存储 |
| `REQUEST_INSTALL_PACKAGES` | 从下载管理面板一键拉起安装 APK 文件 |

> **安全承诺**：无需 Root、无需 Shizuku、无需系统无障碍服务、无需申请悬浮窗系统权限。

---

## 构建与测试指南

### 环境依赖
- Linux / macOS 环境；
- Android Build Tools：`aapt`、`dx`、`zipalign`、`apksigner`；
- JDK 8+ (`javac`)；
- `android.jar` (API Level 24+)。

### 一键构建与测试
```bash
# 运行 JVM 回归测试门禁，自动生成 BuildInfo 并构建签名 APK
./build.sh

# 单独执行纯 JVM 回归测试套件（12 项核心引擎测试，耗时 < 0.3 秒）
./test.sh
```

构建生成的发布包位于：`build/Acid-broswer.apk`。

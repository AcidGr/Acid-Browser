# Acid-broswer (Phase 2 Clean White Edition)

**Acid-broswer** 是一款专为 Android 打造的超轻量、零敏感权限、原生支持 Agent Loop 的现代 AI 浏览器内核（纯白极简 UI，经典底栏 5 键导航，全矢量 SVG 图标设计，零 Emoji）。

- **现代白底主题**：默认纯净白色界面，质感通透；支持一键无缝夜间模式切换。
- **经典底栏 5 键**：从左到右依次为 `后退`、`前进`、`菜单 (居中)`、`标签页 (带实时数字方框)`、`主页`，与主流正常浏览器使用习惯完全一致。
- **功能菜单面板**：底部弹窗式抽屉，集成 `夜间模式`、`书签收藏`、`历史浏览`、`下载管理`、`电脑/手机模式切换`、`Agent 调试台`。
- **超轻量体积**：全包仅 `~130 KB`（零第三方臃肿依赖，纯原生 Android `WebView` + 注入式 `agent_bridge.js` 运行时 + `AcidIcons` 纯矢量抗锯齿引擎）。
- **零敏感权限**：仅需普通 `INTERNET` 权限（**无需 Root、无需 Shizuku、无需系统无障碍服务、无需悬浮窗权限**，任何安卓机安装即用）。
- **控制网关**：内置 `127.0.0.1:3090` 本地 HTTP 控制网关，支持大模型或本地 Agent 闭环遥控。

---

## 一、 目录结构

```text
Acid-broswer/
├── AndroidManifest.xml                  # 仅需 INTERNET 权限，支持 http/https 链接打开
├── build.sh                             # 一键极速编译、4字节对齐与签名脚本
├── docs/
│   └── agent-tool-issues-report-20261028.md  # Agent 工具链实测问题报告与优化建议 (13项问题诊断)
├── assets/
│   ├── acid_logo.svg                    # 品牌定制 SVG 矢量图标源文件
│   ├── agent_bridge.js                  # 注入式页面感知与动作执行引擎 (DOM 蒸馏 / SoM / React-safe Fill)
│   ├── home.html                        # 内置纯 SVG 矢量风格启动页暨 Agent 动作靶场 (Testbench)
│   └── userscripts/
│       └── anti_app_hijack.user.js      # 默认内置插件：拦截“打开App看全文”遮罩与解除复制限制
├── res/
│   ├── drawable/ic_launcher.png         # 由 SVG 矢量路径生成的酸绿/青色赛博六边形图标
│   └── values/strings.xml               # 应用名称定义
└── src/com/acid/browser/
    ├── AcidIcons.java                   # 纯 SVG Path (24x24 viewBox) 解析与抗锯齿矢量图标引擎 (15款定制图标)
    ├── BrowserActivity.java             # 主界面：全 SVG 矢量顶栏、双标识切换、SoM 开关、多标签、人机接管栏与调试台
    ├── BrowserController.java           # 核心控制器：多 Tab 会话、UA/Viewport 伪装、DeepLink 拦截、MotionEvent 合成
    ├── AgentOverlayView.java            # 顶层 HUD 画布：AI 驾驶呼吸灯边框、防误触锁、硬件点击水波纹脉冲动画
    └── HttpControlServer.java           # 127.0.0.1:3090 本地 REST 控制网关
```

---

## 二、 本地 HTTP 网关接口速查 (`127.0.0.1:3090`)

```bash
# 1. 查看浏览器状态
curl -s http://127.0.0.1:3090/api/status

# 2. 观测当前页面语义树与 [e1..eN] 句柄 (开启 SoM 框)
curl -s "http://127.0.0.1:3090/api/observe?som=true"

# 3. 跳转网址并等待页面稳定
curl -s -X POST http://127.0.0.1:3090/api/navigate \
  -d '{"url":"https://news.ycombinator.com/"}'

# 4. 免软键盘填充输入框 (React/Vue 安全)
curl -s -X POST http://127.0.0.1:3090/api/fill \
  -d '{"ref":"e7","text":"Acid Browser Agent","clear":true,"submit":false}'

# 5. 按句柄触发硬件级真实点击 (带脉冲水波纹动画)
curl -s -X POST http://127.0.0.1:3090/api/click \
  -d '{"ref":"e10","native":true}'

# 6. 切换电脑端/移动端标识 或 请求人类接管
curl -s -X POST http://127.0.0.1:3090/api/mode \
  -d '{"browserMode":"desktop","controlMode":"takeover","som":true,"reason":"请完成滑块验证"}'
```

---

## 三、 项目文档与诊断评估

- **[Acid Browser Agent 工具链问题与优化建议报告 (2026-10-28)](docs/agent-tool-issues-report-20261028.md)**：
  基于多站点真实复杂业务流（抖音登录与私信 / 抖音作品查询 / 百度贴吧通知 / 深圳天气 / Gmail 发信）的深度实测，全面梳理的 13 项工具问题、P0~P2 优先级矩阵、分工哲学反思与 API 演进草案。


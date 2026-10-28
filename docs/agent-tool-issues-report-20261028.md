# Acid Browser Agent 工具链问题与优化建议报告

> **报告日期**：2026-10-28  
> **实测环境**：Acid Browser 内置 Agent Loop (WebAutomationDriver + agent_bridge.js)  
> **实测场景**：抖音登录与发私信 → 抖音作品查询 → 百度贴吧通知检索 → 深圳天气查询 → Gmail 邮件发送全流程  
> **问题统计**：共 13 项问题（🔴 P0 × 3 · 🟡 P1 × 4 · 🟢 P2 × 6）

---

## 0. 执行摘要

本次多站点连续业务流任务最终全部达成（抖音登录成功、作品数据查到、贴吧通知梳理完成、邮件确认送达），但执行过程效率极低，暴露出工具链多项底层隐患。

### 核心指标看板

| 指标项 | 统计值 | 说明 |
| :--- | :--- | :--- |
| **裸异常 `End of input`** | **18+ 次** | `evaluateJavascript` 异常未过滤直接透传给 Agent |
| **无效/重试调用占比** | **~60%** | 主要集中在 Gmail 发信与 SPA 状态探测阶段 |
| **P0 级致命缺陷** | **3 项** | 缺少 wait 原语、eval 不支持 async、快照裸异常 |
| **可用等待原语** | **0 个** | 工具链中无任何 `wait` 语义支持 |

### 核心结论与设计反思

> **一句话总结**：当前工具把「页面状态的确定性收敛」这件本该由底层工具完成的机械任务，连同原生裸异常一起抛给了 Agent；Agent 在没有 `wait` 原语的情况下被迫使用 `eval` 编写原生 JS 解决，既丧失了工具链的安全校验与可观测性（`verified:true` 回执），又陷入了“刷新页面 → 盲猜状态 → 再次重试”的低效死循环。
>
> **分工准则**：**工具负责「页面状态的确定性收敛」，Agent 负责「意图理解与业务决策」。**

---

## 1. 优先级与问题总览表

| 编号 | 等级 | 问题名称 | 实测复现 | 缺陷类型 | 影响范围 |
| :--- | :---: | :--- | :---: | :--- | :--- |
| **A-1** | 🔴 P0 | 快照管线抛裸异常 `End of input at character 0 of` | 18+ 次 | 异常处理 | 所有 `navigate` / `observe` / `click` / `reload` |
| **A-2** | 🔴 P0 | `eval` 不支持 async，Promise 返回值被吞成 `{}` | 4 次 | 功能缺陷 | 异步执行、延迟等待与动态探测 |
| **A-3** | 🔴 P0 | 缺少 `wait` / 显式等待原语 | 全程受阻 | 能力缺口 | SPA 路由、动态渲染、异步提交确认 |
| **B-1** | 🟡 P1 | `STALE_ELEMENT_REF` 失效率过高，无自动重解析机制 | 4 次 | 稳定性 | 抖音等高频局部刷新的 SPA 站点 |
| **B-2** | 🟡 P1 | 缺少语义定位（文本/选择器），只能靠坐标或 JS 绕过 | 多次 | 能力缺口 | 动态 DOM 交互与无 ref 覆盖区域 |
| **B-3** | 🟡 P1 | 坐标点击坐标系含糊，实测偏移约 200px | 1 次 | 正确性 | `click(x, y)` 物理坐标交互 |
| **B-4** | 🟡 P1 | 仅 hash 变化的 `navigate` 静默不生效 | 2 次 | 语义错误 | SPA Hash 路由切换（如 Gmail 模块切换） |
| **C-1** | 🟢 P2 | `tabs new` 创建新标签后不自动切换上下文 | 1 次 | 行为缺陷 | 多标签页并行操作流程 |
| **C-2** | 🟢 P2 | 快照冗余严重：8 个 ref 指向同一卡片嵌套结构 | 持续 | 性能/Token | 列表型、流式卡片型页面（如抖音瀑布流） |
| **C-3** | 🟢 P2 | `fill` 不支持 `contenteditable`（静默无效且不报错） | 1 次 | 能力缺口 | 富文本编辑器（Gmail 正文、Notion、文档等） |
| **C-4** | 🟢 P2 | `request_takeover`（人机接管）缺乏状态前置交接 | 2 次 | 协作体验 | 验证码通过、登录态检查等接管恢复场景 |
| **C-5** | 🟢 P2 | 站点 TrustedHTML 策略拦截 `innerHTML` 赋值 | 1 次 | 三方约束 | Gmail 等启用了 Trusted Types 的站点 |
| **C-6** | 🟢 P2 | Gmail 移动版 Service Worker 误报“无法离线发送” | 3 次 | 三方约束 | 移动端 Web 应用特定离线策略 |

---

## 2. 问题深度剖析与修复建议

### 2.1 P0 级核心痛点（立即修复）

#### A-1 · 快照管线抛裸异常，语义完全丢失
- **实际现象**：任意动作在页面尚未加载完毕或网络响应中执行时，工具返回裸字符串错误：
  ```json
  {"ok": false, "error": "End of input at character 0 of "}
  ```
- **根本原因**：底层 `evaluateJavascript` 返回空字符串或 `null`，Java 侧未做判空直接调用 `new JSONObject(raw)` 触发 JSON 语法解析崩溃，把原本只是“加载中”的状态伪装成致命异常，并将已就绪的状态（如 title 已经存在）一并吞没。
- **改进建议**：
  1. 对 `evaluateJavascript` 回调结果做防守型校验，空值返回语义化错误码：
     ```java
     if (raw == null || raw.trim().isEmpty() || "null".equals(raw)) {
         return SnapshotResult.error("EMPTY_SNAPSHOT", "页面尚未就绪或脚本未执行", true);
     }
     ```
  2. 即使快照构建失败，也应尽可能回传已知的最小元数据，辅助 Agent 决策：
     ```json
     {
       "ok": false,
       "error_code": "SNAPSHOT_TIMEOUT",
       "retryable": true,
       "hint": "page may still be loading, retry after 500ms",
       "partial": {
         "url": "https://mail.google.com/mail/u/0/#inbox",
         "title": "收件箱 (1) - Gmail",
         "readyState": "interactive"
       }
     }
     ```

#### A-2 · `eval` 不支持 async，Promise 被吞成 `{}`
- **实际现象**：
  ```python
  browser(action='eval', code='new Promise(r => setTimeout(() => r("done"), 2500))')
  # 实际返回：{"ok": true, "result": {}} （假装成功但无有效结果）
  ```
- **根本原因**：`WebView.evaluateJavascript` 执行返回 Promise 时，未经 await 解析，直接将 Promise 实例序列化为 `{}`。
- **与 A-3 的连锁死锁**：由于工具没有 `wait`，Agent 试图用 `eval('new Promise(setTimeout)')` 自行等待，结果 Promise 返回值被吞，导致等待逻辑彻底失效，只能靠高频盲目刷新硬扛。
- **改进建议**：底层自动包裹 async IIFE，并确保结果 await 之后再序列化：
  ```javascript
  (async () => {
    try {
      const v = await (async () => { /* user code */ })();
      return JSON.stringify({ ok: true, value: v === undefined ? null : v });
    } catch (e) {
      return JSON.stringify({ ok: false, error: String(e && e.message || e) });
    }
  })()
  ```

#### A-3 · 缺少 `wait` / 显式等待原语
- **实际现象**：缺少等待 DOM 稳定、等待特定选择器、等待网络静默或固定延时的能力，导致 SPA 动态交互无据可依。
- **建议新增 API**：
  ```python
  browser(action='wait', condition='networkidle', timeout_ms=8000)
  browser(action='wait', condition='selector', selector='div[role="textbox"]', timeout_ms=5000)
  browser(action='wait', condition='stable', quiet_ms=300, timeout_ms=3000)
  browser(action='wait', condition='text', text='邮件已发送', timeout_ms=5000)
  browser(action='wait', condition='ms', ms=500)
  ```

---

### 2.2 P1 级稳定性与交互缺陷（本迭代推进）

#### B-1 · `STALE_ELEMENT_REF` 失效率过高
- **实际现象**：在抖音等局部定时重渲染站点，获取到的 ref 句柄在 2~3 秒内就会失效，连续点击 `e49`, `e29`, `e23` 全报元素已脱离 DOM。
- **改进建议**：
  1. 为 ElementRef 增加结构化指纹：包含 Accessible Name、Role、DOM 路径特征等。
  2. Revision 变化导致找不到元素时，利用指纹在新快照中自动尝试重解析（`re_resolved: true`），命中后继续执行。

#### B-2 · 缺少语义定位机制
- **改进建议**：为 `click` 增加语义定位能力，当 ref 失效时提供标准降级通道：
  ```python
  browser(action='click', text='消息')
  browser(action='click', selector='a[href*="/user/self"]')
  browser(action='click', role='button', name='发送')
  ```

#### B-3 · 坐标点击坐标系含糊
- **实际现象**：根据快照坐标点击 `(36, 397)`，实际由于 DPR（3.5）、视口偏移等换算未对齐，触发了偏移达 200px 的其他元素（跳转到了 `aisearch`）。
- **改进建议**：
  1. 显式化坐标单位参数：`space='css'`（默认）与 `space='device'`。
  2. 点击回执必须回传实际命中元素（`hit: {tag, text, ref, rect}`），一旦发生偏移 Agent 可立即自纠。

#### B-4 · 仅 hash 变化的 `navigate` 静默失效
- **实际现象**：在同一个单页内改变 hash（如 `#inbox` 改为 `#drafts`），浏览器不会触发硬导航，工具回执显示成功但页面并未更新。
- **改进建议**：检测同源同路径且仅 hash 差异时，底层派发 `hashchange` 事件，若页面未响应则执行软刷新。

---

### 2.3 P2 级体验与规范缺陷（后续迭代）

* **C-1 (`tabs new`)**：新建标签页后应自动切换激活，或在返回中清晰提示 `{created: 2, active: 1}`。
* **C-2 (快照冗余)**：过滤仅作为样式包裹的纯容器节点，同区域同前缀文本合并折叠，提供 `compact=true` 选项，预计可降低 70%+ 的 Token 消耗。
* **C-3 (`fill` 支持 contenteditable)**：针对 Gmail 等富文本编辑器，底层自动分派至 `document.execCommand('insertText')`，并在不支持时抛出明确异常。
* **C-4 (`request_takeover` 状态交接)**：允许声明 `expect=[{type:'cookie', name:'sessionid'}]`，恢复执行时由工具自动对比并回传哪些条件已达成。
* **C-5 (`TrustedHTML` 防御)**：在 `agent_bridge.js` 中预注入 `__acid.setText(el, text)` 安全工具函数，规避站点级 CSP 拦截。
* **C-6 (站点移动端离线误判)**：建立站点知识库，针对 Gmail 等移动版有已知 Service Worker 误报的站点，建议优先切换桌面端（`desktop`）视口。

---

## 3. 建议的演进版 API 草案

```python
# ── 等待原语（核心新增）───────────────────────────────
wait(condition='networkidle'|'selector'|'stable'|'text'|'gone'|'ms',
     selector?, text?, quiet_ms?, ms?, timeout_ms?) -> {satisfied, elapsed_ms, snapshot}

# ── 页面感知（增强健壮性与压缩）─────────────────────────
observe(compact?: bool)          # compact=true 只返回关键交互元素
                                 # 失败时降级返回 partial: {url, title, readyState}

# ── 动作交互（多定位方式与真实命中回执）────────────────
click(ref? | text? | selector? | role+name?, within?, x?+y?+space?, tab_id?)
     -> {ok, hit:{tag, text, ref, rect}, strategy, verified, re_resolved?}

fill(ref? | selector?, text, clear?, submit?)
     -> 支持 input / textarea / contenteditable；失败显式抛错

# ── 导航管理─────────────────────────────────────────────
navigate(url, force_reload?: bool, tab_id?)
     -> {ok, navigated: bool, reason?, actual_url}

tabs(tab_action='new', url) 
     -> 自动切换上下文，或显式返回 {created, active}

# ── 脚本执行─────────────────────────────────────────────
eval(code) 
     -> 原生支持 async/await，Promise 自动 resolve
     -> 预注入 window.__acid 工具函数

# ── 人工接管─────────────────────────────────────────────
request_takeover(reason, expect?: [{type:'cookie'|'selector'|'url', ...}])
     -> 恢复后返回 {resumed: true, checks:[{satisfied}], snapshot}
```

---

## 4. 附录：Gmail 实测流程对比与时间线

在本次实测的 Gmail 发信环节，共经历了 **25 个执行步骤**，其中有多达 **11 步为重试或无效探索**（占比 44%）：

```text
1. 访问 Gmail 移动端 -> 2. 点击写信 -> 3. 填写收件人与主题 
-> 4. 点击发送 (❌ 误报离线) -> 5. 返回重发 (❌ 再次误报)
-> 6. 探测网络 (确认在线) -> 7. 切桌面端 -> 8. 多次导航尝试 (❌ 持续 End of input 异常)
-> 9. 多次 observe 探测 (❌ 持续抛错) -> 10. 新建 tab (未切换) -> 11. 百度重置网络
-> 12. 桌面版加载成功 -> 13/14. 检查已发与草稿 -> 15. 打开桌面写信窗口
-> 16. 直接设置收件人 .value (❌ 未生成标签) -> 17. 换用预填 URL view=cm 成功
-> 18. 等待正文编辑器 (❌ eval Promise 吞成 {} 无法等待)
-> 19. 轮询「正在加载」消失 -> 20. innerHTML 写入 (❌ TrustedHTML 拦截)
-> 21. innerText 写入 (❌ 长度仍为 0) -> 22. execCommand 成功写入正文
-> 23. 最终字段校验 -> 24. 点击发送 -> 25. 已发邮件核对送达成功
```

**效益评估**：若落实 **A-1（错误语义化）**、**A-2（eval 支持 async）** 与 **A-3（新增 wait 原语）**，该链路可直接从 25 步压缩至 **8~10 步以内**，Token 与往返耗时可节省 **60% 以上**。

# Acid Browser UI 分级弹窗模板与声明式扩展规范

本文档定义了 Acid Browser 的 4 级覆盖层交互规范、语义化设计令牌（Typography / Radius / Color）、标准页面脚手架模板，以及新增设置开关、菜单格子、弹窗页面与 Agent 工具的标准写法。

---

## 一、 四级覆盖层体系（4-Tier Overlay Hierarchy）

| 层级 | 组件基类 | 视觉与导航语义 |
|---|---|---|
| **Tier 0：视口浮层** | `TabsOverviewView`、`AgentFloatingView`（实现 `AcidOverlay`） | 嵌入在主视口内的抽屉或网格，统一通过 `show()` / `dismiss()` 控制显隐。 |
| **Tier 1：一级底部弹窗** | `AcidBottomSheetView`（通过 `OverlayStackManager.openRootModal` 打开） | 顶栏显示 `[标题 (16sp Bold)] ... [右上角动作?] [✕]`，**不显示** `←`；点击 `[✕]`、背景遮罩或系统返回键均关闭弹窗。 |
| **Tier 2：二级子页面** | `SheetPage`（通过 `pushPage` 或 `OverlayStackManager.pushModal` 压栈） | 顶栏自动显示 **`[← 标题 (16sp Bold)] ... [右上角动作?] [✕]`**；点击 **`[←]` 或系统返回键**始终回退上一级并恢复滚动位置与草稿；点击 **`[✕]` 或背景遮罩**始终一次性关闭整个弹窗流回到网页。 |
| **Tier 3：模态对话框** | `AcidDialog` / `AcidDialogs` | 居中 `16dp` 圆角卡片，标题采用 `SP_DIALOG_TITLE (15sp Bold)`，仅用于确认、单/多选选择器、轻量输入框。复杂详情（如脚本详情）一律使用 Tier-2 子页而非对话框。 |
| **Tier 4：上下文菜单** | `AcidContextMenu` | 跟随长按坐标或锚点行弹出的 `12dp` 圆角菜单，自动防越界裁剪。 |

---

## 二、 语义化设计令牌（`AcidTheme`）

所有字号、圆角、颜色与对比度计算必须从 `AcidTheme` 获取，禁止在业务组件中硬编码数字字号、数字圆角或 `Color.parseColor`。

### 2.1 字号规范（按职责命名）

| 令牌 | 大小 | 字重与样式 | 用途 |
|---|---|---|---|
| `SP_SHEET_TITLE` | `16sp` | Bold, `text(dark)` | Tier-1 / Tier-2 底部弹窗顶栏主标题 |
| `SP_DIALOG_TITLE` | `15sp` | Bold, `text(dark)` | Tier-3 模态对话框标题 |
| `SP_HERO` | `15sp` | Bold, `text(dark)` | 状态首卡 / 品牌首卡主标题（与列表项保持 ≥ 1sp 视觉级差） |
| `SP_ITEM` | `14sp` | Bold, `text(dark)` | 标准设置行、列表卡片主标题 |
| `SP_BODY` | `13sp` | Regular/Bold | 正文段落、对话框说明、输入框文字、主次按钮文字 |
| `SP_FIELD_LABEL` | `12sp` | Bold, `icon(dark)` | 表单输入框上方标签、分段 Tab 标签 |
| `SP_OVERLINE` | `11.5sp` | Bold, `muted(dark)`, `letterSpacing=0.03` | 卡片组外上方的分区小标题（通过字距与颜色同 `SP_FIELD_LABEL` 区分） |
| `SP_CAPTION` | `11.5sp` | Regular, `muted(dark)` | 列表副标题、URL、辅助说明文字 |
| `SP_BADGE` | `10.5sp` | Bold / Monospace | 状态胶囊标签（`statusTag`）、代码块预览 |
| `SP_MICRO` | `9.5sp` | Bold | 角标数字、工具状态微标签 |

### 2.2 圆角与间距规范

- `RADIUS_SHEET = 16dp`：底部弹窗顶角、对话框卡片、悬浮 Agent 抽屉
- `RADIUS_CARD = 12dp`：分组卡片（`groupCard`/`contentCard`/`heroCard`）、长按菜单、标签页缩略卡
- `RADIUS_CONTROL = 8dp`：独立列表项、输入框、按钮、功能菜单磁贴
- `RADIUS_BADGE = 6dp`：状态标签、内联小按钮、代码块背景
- `RADIUS_PILL = 999dp`：全胶囊形态（地址栏、筛选胶囊栏、聊天输入框、分页指示点）
- `ROW_DIVIDER_INDENT_DP = 58dp`：由 `ROW_PAD_H_DP (14) + ROW_ICON_BOX_DP (32) + ROW_ICON_GAP_DP (12)` 自动推导，由 `AcidWidgets.groupCard` 自动插入，禁止手写 `addDivider(..., 58)`。

### 2.3 颜色语义规则

- **主操作 / 激活选中态**：统一使用 `AcidTheme.accent(dark)`
- **危险 / 删除操作**：统一使用 `AcidTheme.COLOR_RED`
- **信息 / 辅助状态标签**：使用 `AcidTheme.COLOR_BLUE`
- **警告 / 接管提示**：使用 `AcidTheme.COLOR_AMBER`
- **实心背景上的文字颜色**：统一调用 `AcidTheme.onColor(fillColor)` 自动按相对亮度返回黑色或白色。

---

## 三、 标准页面模板结构（`SheetPage` + `AcidWidgets`）

每个弹窗页面由 `SheetPage` 承载，包含 4 个标准槽位：

```java
new SheetPage("页面标题", new FullPageRenderer() {
    @Override
    public void render(LinearLayout pinnedHeader, LinearLayout container, LinearLayout pinnedFooter) {
        // 1. 可选：固定子顶栏（搜索框 / 分段 Tab / 筛选胶囊栏，滚动时不丢失输入焦点）
        // 2. 滚动内容区：由 Section Overline + groupCard / contentCard / ListRow 组成
        // 3. 可选：固定底栏（主次提交按钮，不随长列表滚走）
    }
}).withAction(AcidIcons.ICON_PLUS, "右上角动作", onClick);
```

---

## 四、 常见扩展场景速查

### 1. 新增一个设置开关（仅需 2 处）
1. 在 `AcidSettings.java` 注册一行 `BoolKey`（自动完成 `SharedPreferences` 读写、`settings.json` 导出与热重载导入、以及 Agent 系统提示词 Schema 生成）：
   ```java
   public static final BoolKey MY_FEATURE = register(new BoolKey(
       "my_feature", "my_feature", false, "我的新特性", true, false
   ));
   ```
2. 在 `BrowserSettingsSheet.java` 的对应 `groupCard` 中加入一行声明式 `switchRow`：
   ```java
   AcidWidgets.switchRow(ctx, mIsDarkMode, AcidIcons.ICON_SHIELD, accent, AcidSettings.MY_FEATURE, mDataStore, "已开启", "已关闭")
   ```

### 2. 新增一个功能菜单磁贴（仅需 1 处）
在 `BrowserMenuSheet.buildDefaultTiles(...)` 的列表中添加一项 `TileSpec.route(...)` 或 `TileSpec.toggle(...)`，分页（每页 4×2 共 8 项）与页码指示点均自动计算生成。

### 3. 新增一个 Agent / MCP 工具（仅需 1 处）
在 `AcidSandboxTools.java` 中调用 `registerTool(new AgentTool() { ... })`，内置 Agent Loop 与本地 3090 MCP Server 自动同步生效。

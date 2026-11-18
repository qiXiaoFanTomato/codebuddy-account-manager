# CodeBuddy Account Manager · UI 设计系统（单一事实来源）

> **这份文档是本项目界面的唯一权威规范。** 任何 AI 或开发者在新增页面、新增功能、修改任何界面元素之前，必须先读完第 1、2、3、5 节。
> 视觉语言代号：**档案柜 / The Cabinet** —— 与 `design/index.html`、`design/plugin-ui.html` 及 `AccountUiColors.kt` 完全一致。
> 真实界面用 **Kotlin + Swing（IntelliJ Platform）** 绘制；`design/*.html` 是同一套 token 的 Web 复刻，二者颜色值一一对应。

---

## 0. 一页速览（TL;DR）

| 你要做的事 | 必须遵守 |
| --- | --- |
| 取颜色 | 只用 `AccountUiColors` 里的常量，禁止写裸 `Color(0x...)` |
| 取字号 | 只用 `AccountUiType` 的五档阶梯，禁止写裸字号数字 |
| 取间距 / 圆角 | 只用 `AccountUiSpacing` / `AccountUiRadius` |
| 画通用元素 | 先查 `AccountUiKit` 有没有现成工厂方法，有就绝不自绘 |
| 画卡片 / 按钮 / 进度条 | 复用 `AccountGridCard` / `JellyButton` / `JellyProgressBar` 等，不新写绘制逻辑 |
| 加新页面 | 套用第 4 节的页面骨架 + 第 7 节的清单，不发明新布局范式 |
| 表达状态 | 颜色 + 文字/圆点双通道，不能只靠颜色 |
| 数字 | 一律等宽（`AccountUiType.mono` / `forText`），中文一律界面字体 |

**一句话原则**：*像在整理一份实体档案 —— 冷蓝灰档案纸、墨铅字、朱红戳记、黄铜读数，靠 1px 铅线和留白分层，不做体积、不堆高光、不抢眼的绿色红色只有语义用途。*

---

## 1. 设计哲学与铁律

### 1.1 视觉隐喻：档案柜 / The Cabinet

界面是一个整理整齐的档案柜。每个账号是一张**档案卡**，工具区是**抽屉**，导航与状态是盖在纸上的**朱印**与**黄铜读数**。这套隐喻决定了所有视觉决策。

### 1.2 六条铁律（违反即视为破坏一致性）

1. **纸面三级分层**：`panelBg`（画布）→ `surfaceBg`（工具栏/抽屉）→ `cardBg`（档案卡），层级只用 1px 铅线 `rule` 和极轻阴影区分。**不用渐变、不做体积高光、不用彩色大底填充**。
2. **朱印是唯一高饱和**：`seal`（朱红 #B23A2C）全界面只用于——「当前激活」标记、异常/危险、选中下划线/焦点语义之外的强调。它是文档上的印章，不是主题色。
3. **黄铜承载读数**：所有关键数字、进度条充盈用 `brass`（#9B7826）；数字必须等宽，位数变化不挤动布局。
4. **墨蓝构成骨架**：标题栏、状态栏、主按钮、卡片 tab 用同一条 `ink`（#1B2A3A），配朱红圆点，形成上下两条呼应的深墨带。
5. **形状有职责**：直角为主，圆角只有 2~3px（收纸角）；胶囊只给小徽标/进度条这类「小而确定」的元素。圆角不用来表达质感。
6. **固定浅色、双通道状态**：界面固定用稿子里的浅色值，不跟随 IDE 深色主题压暗；状态必须颜色 + 文案/圆点双通道表达。

### 1.3 界面解剖（自上而下）

```
┌─────────────────────────────────────────────┐
│ 深墨带标题栏  ink 底 / onInk 字 / 朱红 brand 点 │  ← 全局唯一，含视图分段 tabs
├─────────────────────────────────────────────┤
│ 抽屉工具栏  surfaceBg 底 / 1px 铅线分隔        │  ← 主操作按钮 + 筛选/排序行
├─────────────────────────────────────────────┤
│                                             │
│ 内容画布  panelBg 底                          │  ← 视图标题 + 档案卡网格/看板/明细
│   ┌──────────┐  ┌──────────┐                 │
│   │ 档案卡    │  │ 档案卡    │   cardBg 底     │
│   │ [tab]    │  │          │   铅线描边       │
│   └──────────┘  └──────────┘                 │
├─────────────────────────────────────────────┤
│ 深墨带状态栏  ink 底 / 黄铜读数 / 朱红状态点     │  ← 与标题栏呼应，显示当前账号+额度
└─────────────────────────────────────────────┘
```

---

## 2. Design Tokens（唯一权威数值表）

> **代码位置**：`src/main/kotlin/dev/codebuddy/account/ui/AccountUiColors.kt`（颜色）、文件末尾的 `AccountUiRadius` / `AccountUiSpacing` / `AccountUiType`（尺寸）。
> **CSS 对应**：`design/index.html` / `design/plugin-ui.html` 的 `:root`。所有颜色用 `fixed()` 包装成 `JBColor(浅,浅)`，**刻意不跟随 IDE 深色主题**。

### 2.1 颜色

#### 表面阶梯（Surface）

| Kotlin 常量 | CSS 变量 | HEX | 用途 |
| --- | --- | --- | --- |
| `panelBg` | `--paper` | `#E8ECEF` | 内容区画布、面板底色 |
| `surfaceBg` / `surfaceHi` | `--drawer` | `#DDE4E8` | 工具栏、表头、次级分段、轨道 |
| `cardBg` | `--card` | `#F7F9F9` | 档案卡、看板卡、表格底 |
| `cardHoverBg` | — | `#FFFFFF` | 卡片 hover（只提亮一档，不做位移变形） |
| `itemBg` | — | `#EEF2F4` | 卡片内嵌块、细项、分组底 |
| `cardActiveBg` | — | `#F1F4F5` | 当前激活账号卡底（默认主题靠墨蓝描边区分） |

#### 品牌骨架（Brand）

| Kotlin 常量 | CSS 变量 | HEX | 用途 | 禁止 |
| --- | --- | --- | --- | --- |
| `ink` | `--ink` | `#1B2A3A` | 标题栏/状态栏/主按钮填充/卡片 tab | 不要拿它当正文大面积背景 |
| `onInk` | — | `#F7F9F9` | 墨蓝带上的主文字 | — |
| `onInkDim` | — | `#A9BAC6` | 墨蓝带上次级文字（≈55% 不透明度） | — |
| `seal` / `accent` / `danger` | `--seal` | `#B23A2C` | **唯一高饱和**：当前标记、异常、危险、选中下划线、焦点语义 | 不做整块填充、不做普通链接色 |
| `sealSoft` / `accentSoft` | — | `#F8EAE7` | 朱印淡底（选中行、强调块） | — |
| `brass` / `barFill` / `focusRing` | `--brass` | `#9B7826` | 黄铜：关键读数、进度条充盈、焦点环、刻度 | 不做正文文字色（用 `brassInk`） |
| `brassInk` / `warning` | `--brass-ink` | `#745617` | 黄铜压深：数字描边、强调文字、警告态 | — |
| `brassSoft` | — | `#F3EBD6` | 黄铜淡底：选中行/高亮块 | — |
| `rule` / `border` / `tableGrid` | `--rule` | `#C6D0D7` | 所有 1px 描边、表格网格 | — |
| `track` / `barTrack` | `--track` | `#D3DBE0` | 进度条轨道底 | — |
| `divider` | — | `#D9E1E6` | 卡片内部弱分隔线（比 rule 更轻） | — |

#### 文本（Text）

| Kotlin 常量 | HEX | 用途 | 对比度（在 cardBg 上） |
| --- | --- | --- | --- |
| `textPrimary` | `#1B2A3A` | 主文本、标题 | 13.81:1 ✅ AA |
| `textSecondary` | `#4F5E6D` | 正文、Swing 次级文本 | 6.30:1 ✅ AA |
| `textMuted` | `#5A6978` | 次级说明、表头、档案号 | 5.33:1 ✅ AA |
| `textFaint` | `#666F7D` | 最弱文本（占位、未刷新，**非关键信息**） | 4.81:1 ✅ AA |

> ⚠️ `textFaint` 的 `#666F7D` 是修正后的值（原 `#6E7C8A` 只有 4.04:1 不达标）。**不要改浅它。**

#### 语义色（Semantic）

| 状态 | 文案 | 色值常量 | HEX |
| --- | --- | --- | --- |
| 正常 / 有效 | 正常、Token 有效 | `success` | `#15803D` |
| 偏低 / 临期 | 偏低 | `warning`（黄铜压深） | `#745617` |
| 紧张 / 已用尽 / 查询失败 | 紧张、已用尽、查询失败 | `danger`（= `seal`） | `#B23A2C` |
| 无数据 / 未刷新 | 无套餐、未刷新 | `textMuted` / `textFaint` | `#5A6978` / `#666F7D` |

额度状态判定逻辑统一在 `AccountUiKit.quotaStatus()` / `QuotaStatus.of()`，不要自己写阈值：
`null→未刷新` · `error→查询失败` · `空套餐→无套餐` · `usable≤0→已用尽` · `≤10%→紧张` · `≤30%→偏低` · `else→正常`。

#### 标签 / 徽标（Tag / Chip）

| 常量 | 背景 HEX | 文字 HEX | 用途 |
| --- | --- | --- | --- |
| `tagPersonalBg/Fg` | `#E6F4F8` | `#0B6C82` | 个人账号 |
| `tagEnterpriseBg/Fg` | `#FDF3E3` | `#96590B` | 企业账号 |
| `tagDomainBg/Fg` | `#E8ECEF` | `#5A6978` | 认证域标签 |
| `tagSuccessBg` | `#E8F4EC` | 配 `success` | Token 有效 |
| `glyphGiftBg/Fg` | `#FFF3E2` | `#B45309` | 套餐字标：赠送 |
| `glyphBaseBg/Fg` | `#E8F1FF` | `#1D4ED8` | 套餐字标：基础 |
| `glyphPlainBg/Fg` | `#E8ECEF` | `#4F5E6D` | 套餐字标：普通 |
| `tagDomainAltBg/Fg` | `#EFECFB` | `#5B4BC4` | workbuddy 第二域名（随主题可变） |

头像色池 `avatarPalette`（按 UID 稳定取色，顺序固定）：`#0E7490` `#5B4BC4` `#1E7A5E` `#A85B3E` `#2E6FA8` `#A8466B` `#7A4B8E` `#B03A3A`。

#### 复刻页专用色（用量统计 / 成长计划，取自 WorkDaddy 页面口径）

`chartGreen #5FAF73` · `planGreen #3F9E5F` · `planGreenSoft #EAF6EE` · `chartBorder #E5E8EA` · `plotBg #F7F8F8` · `chipBg #EEF0F1`。
这些是**复刻第三方页面时才用**的色，插件原生界面不要用。

#### 深墨带内部色（Hero / 标题栏 / 状态栏）

`heroStart #1B2A3A` → `heroEnd #2C4257`（弱渐变，几乎不可见）· `heroText #F7F9F9` · `heroTextDim #A9BAC6` · `heroDivider rgba(247,249,249,.40)` · `heroCell rgba(255,255,255,.24)` · `heroSheet/heroSheen #D6B15C`（数字脉冲）。

### 2.2 字体族

| 用途 | CSS 变量 | 字体栈 | Kotlin 入口 |
| --- | --- | --- | --- |
| 标题/展示 | `--display` | `IBM Plex Sans Condensed` → 苹方/微软雅黑/Noto Sans CJK | 界面字体加粗 + `AccountUiType.scaled` |
| 正文 | `--body` | `IBM Plex Sans` → 苹方/微软雅黑/Noto Sans CJK | `AccountUiType.ui()` |
| 数字/等宽 | `--mono` | `IBM Plex Mono` → 编辑器字体 → Consolas | `AccountUiType.mono()` / `forText()` |

> **中文绝不走等宽字体**：编辑器等宽字体没有中文字形，中文会渲染成方框 `□`。含 CJK 的文本用 `AccountUiType.forText()` 自动回退到界面字体。数字、时间、百分比走 `mono()`。

### 2.3 字号阶梯（`AccountUiType`，单位已含 UI 缩放）

| 常量 | px | 用途 |
| --- | --- | --- |
| `heroNum` | 40 | 看板 Hero 主指标（`dash-hero-num`） |
| `display` | 32 | 关键 KPI 数字 |
| `number` | 22 | 次级数字（KPI 值、读数） |
| `title` | 14 | 区块/卡片标题 |
| `body` | 13 | 正文 |
| `tab` | 12 | 卡片 tab、行文本 |
| `label` | 12 | 标签、字段值 |
| `caption` | 11 | 辅助说明、档案号 |
| `micro` | 10 | 徽标、圆点 |

阶梯外的超大数字用 `AccountUiType.scaled(n)` 换算，**不要写裸数字**。

### 2.4 间距阶梯（`AccountUiSpacing`，px，用前经 `JBUI.scale`）

基准 4：`xxs 2` · `xs 4` · `sm 8` · `md 12` · `lg 16` · `xl 20`
语义别名：`rowGap 6` · `blockGap 8` · `cardPadV 16` / `cardPadH 18` · `cardGap 18` / `gridGap 18` · `sectionGap 22` · `gridMinColumnWidth 330` · `heroPadV 14` / `heroPadH 16`。

约定：块内用 `xs/sm`，块之间用 `blockGap`，卡片之间用 `cardGap`，越外层越松。

### 2.5 圆角（`AccountUiRadius`）

`card 3` · `inner 2` · `chip 2` · `pill 100`。
矩形容器（卡片、内嵌块、按钮、输入框）用 card/inner/chip 的 2~3px；胶囊只给状态标签与进度条这类小元素；进度条一律直角。

### 2.6 描边、阴影、动效

- 描边：1px `rule`；卡片激活态用 1px `ink` 或 `accent` 描边（不用彩色底）。
- 阴影：`cardShadow = rgba(27,42,58,.026)`，只做「贴纸上浮」的 1px 单层，绝不营造体积。
- 动效：补间统一走 `Jelly` 的 60fps 时钟，缓动用 ease-out 系；`Jelly.animationsEnabled=false` 时必须直接跳终值仍完整可用；支持 `prefers-reduced-motion`。

---

## 3. 组件库（先复用，再自绘）

> **铁律**：画任何通用元素前，先在 `AccountUiKit` 和 `ui/` 包里找现成实现。找到就直接用，**绝不写第二份**。

### 3.1 统一入口：`AccountUiKit`

| 工厂方法 | 产出 | 何时用 |
| --- | --- | --- |
| `sectionHeader(title, subtitle?, actions?)` | 分区标题（标题 + 右侧延伸细线 + 操作区） | 所有分区头 |
| `fieldLine(label, value, labelWidth?)` | 字段行（标签左、值右，全屏对齐） | 卡片里所有「标签 → 值」 |
| `valueText(text, tone?, mono?)` | 右对齐数值文本（等宽、刷新不跳动） | 数值展示 |
| `statusTag(text, tone)` | 状态标签（6px 圆点 + 文案，双通道） | 行内状态 |
| `hint(text)` | 辅助说明文字 | 统一弱提示 |
| `kpi(label, value, hint?, tone?)` | KPI 指标块（数值默认黄铜） | 看板指标 |
| `hero(title, value, unit?, metrics?)` | Hero 指标条（深墨带） | 主指标 |
| `button(text, variant?, icon?, tip?, onClick)` | 统一按钮（`JellyButton`） | 所有按钮 |
| `action(text, onClick)` | 分区标题右侧小动作 | 替代 ActionLink |
| `fieldPill(text, tone)` | 字段徽标（语义色弱底 + 同色字，圆角 4） | 计数/数值短字段 |
| `fieldRow(vararg comps)` | 行内水平排布 + 垂直居中 | 一行多个小组件 |
| `styleTable(table, widths)` | 账本式表格样式 | 所有表格 |
| `styleCombo(combo, label?)` | 浅色下拉框（含渲染器） | 所有下拉框 |
| `styleScroll(scroll, bg?)` | 浅色滚动区 | 所有滚动区（防深色主题变黑底） |
| `segmented(items, selectedIndex, onSelect)` | 主导航分段 | 视图切换 |
| `quotaStatus(summary)` | 额度状态（文案 + 色） | 额度状态展示 |
| `formatNumber(v)` | 千分位 + 最多两位小数 | 所有数字格式化 |
| `card(fill?, stroke?, arc?, elevated?, pill?, material?)` | 档案卡外壳（`RoundedCard`） | 卡片 / 嵌套块容器 |
| `column()` / `row(layout?)` | 撑满宽度的纵向 / 横向容器 | 卡片内堆叠 |
| `divider(color?)` | 1px 分隔线（`HairLine`） | 区块分隔 |
| `dialogTopBar(title, subtitle?, actions?)` | 弹窗顶栏 | 所有 `DialogWrapper` |
| `cardHeadline(value, unit?, trailing?, tone?)` | 卡片主指标行 | 看板卡片主读数 |
| `cardRow(name, value?, tag?, valueTone?, tooltip?, valueWidthDp?, clickable?)` | 明细行 | 看板列表行 |
| `cardList(rows, capacity?, emptyText?)` | 明细列表（含「另有 N 项」折叠） | 看板列表 |
| `cardActionBar()` | 卡片底部动作条（按钮换行） | 看板卡片操作区 |
| `notice(text, tone?)` | 弱化提示行 | 空态 / 加载中 / 溢出 |
| `metric(label, value, tone?)` | 指标单元（标签上、数值下） | 次级读数 |
| `progressBar(percent)` | 额度进度条（`QuotaBar`） | 所有进度条 |
| `chip(text, fill?, fg?, arc?)` | 小徽标（`Chip`） | 标签 / 计数 |
| `glyph(glyph, fill, fg, size?)` | 圆角方形字标（`GlyphBadge`） | 头像位 / 套餐字标 |

### 3.2 基础绘制组件（`AccountElements.kt` / 相关）

- `HairLine(color?)` — 1px 横向分隔线。
- `StatusTag(text, tone)` — 圆点 + 文案，色弱用户靠位置/文案也能区分。
- `MetricCell(label, value, tone)` — 标签在上、数值在下，左对齐。
- `JellyButton` / `JellyButtonVariant` — 按钮（SECONDARY/PRIMARY 等变体）。
- `AccountGridCard` — 账号档案卡（左上角 `tab`、头像、标签、字段、黄铜读数、进度条、底部虚线操作区）。
- `JellyProgressBar` / `Meter` — 现名为 `QuotaBar`（经 `AccountUiKit.progressBar`）：黄铜充盈 + 铅线轨道 + 9px 刻度；低额度切朱印。
- `GlyphBadge` — 首字母/图标徽标（头像位）。
- `SegmentedTabs` — 分段导航。
- `Chip` — 胶囊徽标。

### 3.3 弹窗 / 独立窗口

`HistoryDialog` · `HistoryReportDialog` · `QuotaDetailDialog` · `UsageDetailDialog` · `UsageStatsDialog` · `GrowthCenterDialog` · `ModelQuotaRulesEditor` · `QuickPhraseDialog`。
新增弹窗沿用 DialogWrapper，套用相同的标题栏深墨带 + 内容画布 + 底部按钮区结构。

### 3.4 Toast / 系统提示

- 轻量回执用 `showToast()`（深墨底 + 黄铜左边框，warn 态换朱红左边框）。
- 重要操作结果由桌宠气泡承载（等级色竖条 + 操作标签），桌宠关闭时才退回 IDE 通知。新增系统提示时遵循这条路由规则，不要两套都弹。

---

## 4. 页面骨架模板

新增页面必须落在以下四种范式之一，不要发明第五种布局语言。

### 4.1 卡片网格页（账号库范式）
`内容画布 → view-head（标题 + meta） → account-grid（repeat(auto-fill,minmax(330px,1fr))） → N 张档案卡`。
每张卡：tab（可选）→ 头像 + 名称 + 标签 + 印章 → 字段行 → 黄铜读数 → 进度条 → 虚线操作区。

### 4.2 看板页（Dashboard 范式）
`view-head → dash-grid（2 列，wide 卡跨整行）`。
卡片结构固定：`.dash-head`（标题 + `⠿` 抓手 + 右侧操作）+ `.dash-body`。 Hero 主指标卡用深墨带，KPI 用 `kpi()` 工厂，列表行用 `dash-row`（名称 + 等宽数值，异常标朱红）。

### 4.3 明细 / 表格页
`view-head → 筛选/时间范围工具条 → 账本式表格（styleTable）→ 分页`。
表头抽屉底 + 墨蓝下线，表体档案卡底，左列名称用展示字体、数字列右对齐等宽。分组用 `<details>` 折叠（额度明细范式），展开箭头用 `▸` 旋转 90°。

### 4.4 表单 / 设置页
`分区（sectionHeader 分隔）→ 字段行/开关/输入 → 底部操作`。
每个设置项：左标签 + 说明，右控件；分区之间用 `sectionGap 22`。下拉一律 `styleCombo`，滚动一律 `styleScroll`。

---

## 5. 强制规则（MUST / MUST NOT）

### ✅ MUST（必须）
1. 颜色、字号、间距、圆角**全部走 token 常量**。
2. 通用元素**先查 `AccountUiKit` / 现有组件**再决定自绘。
3. 状态**颜色 + 文案/圆点双通道**。
4. 数字**等宽**，中文**界面字体**（`forText` 判断 CJK）。
5. 新增组件支持键盘焦点，`:focus-visible` / focus 环用黄铜 `focusRing`。
6. 尊重 `prefers-reduced-motion` / `Jelly.animationsEnabled`。
7. 所有下拉/滚动区调用 `styleCombo` / `styleScroll` 保持浅色。
8. 新页面复用第 4 节骨架与 `sectionHeader`。

### ❌ MUST NOT（禁止）
1. 禁止裸写 `Color(0x......)`、`font = deriveFont(15f)`、`padding = 13` 等魔法数。
2. 禁止引入新的高饱和色、渐变背景、玻璃拟态、发光阴影。
3. 禁止用朱红 `seal` 做普通按钮填充或大面积色块（它是印章/危险信号）。
4. 禁止正文使用黄铜 `brass`（可读性不足），要用 `brassInk`。
5. 禁止中文走等宽字体（会变方框）。
6. 禁止只靠颜色表达状态。
7. 禁止卡片 hover 位移、弹跳、缩放（只提亮一档底色）。
8. 禁止把界面压成 IDE 深色主题配色（本产品固定浅色档案纸）。
9. 禁止为单个页面复制一份按钮/标签/卡片绘制代码。
10. 禁止改浅 `textFaint` 等文本色到 AA 对比度以下。

---

## 6. 无障碍（WCAG 2.1 AA）

- 正文对比度 ≥ **4.5:1**，≥24px 或 ≥18.66px 粗体大字 ≥ **3:1**。
- 当前 token 已全部达标（见 2.1 文本表），不要调浅。
- 焦点：3px 黄铜 `focusRing`，`outline-offset 2~3px`；键盘可达、逻辑 tab 顺序。
- 数字用 `tabular-nums` / 等宽，刷新不跳动；`role=img` 的进度条给 `aria-label="剩余 98%"`。
- 动效可关闭；减少动效偏好下所有补间跳终值。

---

## 7. 新增页面标准流程（AI Checklist）

按顺序执行，每项打勾后再进入下一步：

- [ ] **1. 定位范式**：判断新页面属于 4.1~4.4 哪种；在 `ui/` 下按 `Account*Panel.kt` / `*Dialog.kt` 命名新建文件。
- [ ] **2. 声明依赖**：文件顶部 `import` 需要的 `AccountUiColors` / `AccountUiType` / `AccountUiSpacing` / `AccountUiKit`。
- [ ] **3. 搭骨架**：容器背景 `panelBg`；需要标题栏/状态栏时用 `ink` 深墨带；分区用 `sectionHeader`。
- [ ] **4. 取 token**：逐个色值/字号/间距对照第 2 节表格取常量，禁止裸值。
- [ ] **5. 复用组件**：按钮、标签、字段、KPI、表格、滚动区全部调 `AccountUiKit`。
- [ ] **6. 状态与数字**：状态走 `QuotaStatus`/`statusTag` 双通道；数字走 `mono`/`forText` + `formatNumber`。
- [ ] **7. 交互**：按钮 `onClick`、键盘焦点、焦点环、hover 只提亮。
- [ ] **8. 无障碍**：核对对比度、ARIA、键盘可达、减少动效。
- [ ] **9. 对照复刻页**：若该页面也在 `design/plugin-ui.html` 里出现，颜色/间距/结构要一致。
- [ ] **10. 自测**：`./gradlew.bat buildPlugin` 通过 + `runIde` 里目视核对浅色、对齐、等宽数字。

---

## 8. 色调主题系统（可切换，默认不变）

`AccentTheme` 提供 7 套方案：`CABINET`（档案柜，默认）· `INK_TEAL`（墨青）· `GRAPHITE`（石墨）· `INDIGO`（靛蓝）· `SKY_BLUE`（晴空）· `MINT`（薄荷）· `CORAL`（珊瑚）。

- **默认且设计基准是 CABINET**。所有新页面必须在 CABINET 下做到 100% 还原。
- 主题通过 `AccountUiColors.applyTheme()` 整体替换主色相关字段；中性色与语义色不变。
- 新组件若要响应主题切换，主色用 `accent` / `accentFill` 等可变字段，中性色用固定字段，并监听 `UiThemeEvents.TOPIC` 在切换后重建（卡片底色在构造期固定，只重绘不换色）。
- 不要新增主题方案，除非产品明确要求。

---

## 附录 A：文件对照表

| 层 | 文件 | 职责 |
| --- | --- | --- |
| 规范 | `design/DESIGN-SYSTEM.md` | 本文档，单一事实来源 |
| 视觉稿 | `design/index.html` | 产品落地页（视觉语言源头） |
| 视觉稿 | `design/plugin-ui.html` | 插件界面复刻（组件最全） |
| 视觉稿 | `design/ui-audit.html` | UI 体检 / 对比度校验记录 |
| 视觉稿 | `design/cabinet-swing-preview.html` | Swing 绘制预览 |
| Token | `ui/tokens/AccountUiColors.kt` | 颜色 + 圆角/间距/字号阶梯 + 主题 |
| 基建 | `ui/foundation/Jelly.kt` | 表面绘制、动画基础设施 |
| 基建 | `ui/foundation/WrapGridLayout.kt` / `FlowWrapLayout.kt` | 网格 / 流式换行布局 |
| 基建 | `ui/foundation/AccountTableStyle.kt` | 账本式表格样式 |
| 组件 | `ui/components/AccountUiKit.kt` | 统一组件工厂入口（唯一入口） |
| 组件 | `ui/components/AccountElements.kt` | HairLine / FieldLine / StatusTag / MetricCell / SectionHeader |
| 组件 | `ui/components/AccountButtons.kt` | JellyButton / ActionPill / DialogTopBar / SegmentedTabs |
| 组件 | `ui/components/QuotaCards.kt` | RoundedCard / HoverRow / QuotaBar / Chip / 额度卡 |
| 组件 | `ui/components/CardContent.kt` | CardHeadline / CardRow / CardList / CardActionBar |
| 组件 | `ui/components/AccountHero.kt` | KpiTile / HeroPanel / HeroCard |
| 组件 | `ui/components/AccountGridCard.kt` | 账号档案卡 / GlyphBadge / IconTile |
| 页面 | `ui/views/AccountPanel.kt` / `DashboardPanel.kt` / `DashboardGrid.kt` / `QuotaView.kt` / `AccountGridView.kt` | 主工具窗三视图 |
| 页面 | `ui/views/*Dialog.kt` / `ModelQuotaRulesEditor.kt` / `AccountSettingsConfigurable.kt` | 弹窗与设置页 |
| 状态栏 | `ui/statusbar/CodeBuddyStatusBarWidget.kt` / `StatusBarSupport.kt` | 状态栏 widget |
| 入口 | `ui/AccountToolWindowFactory.kt` / `OpenPanelAction.kt` / `QuickPhraseAction.kt` / `AccountSort.kt` | IDE 扩展点入口与排序策略 |

> 包分层是强制的，依赖方向只允许向下：`ui → views/statusbar → components → foundation → tokens`。
> `components` 不许依赖 `views`/`statusbar`；`foundation` 不许依赖 `components` 及以上。搬类时同步改 `META-INF/plugin.xml` 里的 FQN。

## 附录 B：变更规则

修改 token 值（如对比度修正）时：① 同步改 `AccountUiColors.kt` 与两份 HTML 的 `:root`；② 在 `design/ui-audit.html` 记录原因；③ 更新本表。三者不一致即视为 bug。

# AGENTS.md — 项目协作约定（供 AI / 开发者阅读）

本文件是任何 AI 助手在本仓库修改代码前的**入口约定**。项目概览与构建方式见 `README.md`；**界面相关的所有改动，必须先遵守下列约定。**

---

## 🎨 UI 改动：先读规范，再动手

本项目已沉淀一套完整的界面设计语言「档案柜 / The Cabinet」。**任何新增页面、新增功能、调整样式之前，必须先阅读：**

1. **`design/DESIGN-SYSTEM.md`** —— UI 设计的唯一事实来源（token 表、组件 API、页面骨架、强制规则、验收清单）。
2. **`design/design-system.html`** —— 可视化设计图（在浏览器打开，直观看颜色/字号/组件/Do & Don't）。

真实界面用 **Kotlin + Swing（IntelliJ Platform）** 绘制，位于 `src/main/kotlin/dev/codebuddy/account/ui/`；`design/*.html` 是同一套 token 的 Web 复刻。二者颜色值一一对应，改 token 必须两边同步。

### 不可违反的 UI 硬规则（违反即视为破坏一致性）

1. **颜色 / 字号 / 间距 / 圆角一律走 token**：只用 `AccountUiColors`、`AccountUiType`、`AccountUiSpacing`、`AccountUiRadius` 的常量，禁止裸写 `Color(0x...)`、`deriveFont(15f)`、裸 padding 数字。
2. **先复用，再自绘**：画按钮、标签、状态、字段、KPI、表格、滚动区、下拉框前，先查 `AccountUiKit` 和 `ui/` 包里有没有现成实现，有就绝不再写一份。
3. **朱红 `seal` 是唯一高饱和**，只用于「当前 / 异常 / 危险 / 强调」，不做普通按钮填充或大面积色块。
4. **黄铜 `brass` 只做数字与进度条充盈**；正文文字用 `brassInk`，不用 brass。
5. **数字等宽、中文界面字体**：数字走 `AccountUiType.mono/forText`，中文绝不走等宽字体（会渲染成方框 □）。
6. **状态双通道**：颜色 + 文案/圆点，不能只靠颜色；额度状态统一走 `AccountUiKit.quotaStatus()`。
7. **固定浅色档案纸**，不跟随 IDE 深色主题压暗；下拉/滚动区必须调 `styleCombo`/`styleScroll` 保持浅色。
8. **卡片 hover 只提亮一档底色**，不位移、不缩放、不弹跳；不做渐变、玻璃拟态、发光阴影。
9. **新页面套用四种骨架之一**（卡片网格 / 看板 / 表格明细 / 表单设置），不发明第五种布局语言。
10. **无障碍达 WCAG AA**：正文对比度 ≥4.5:1（当前文本 token 已达标，勿改浅），焦点环用黄铜 `focusRing`，尊重减少动效偏好。
11. **新增页面后执行 `design/DESIGN-SYSTEM.md` 第 7 节的 10 项验收清单**，并 `./gradlew.bat buildPlugin` 通过。

默认且设计基准色调是 `AccentTheme.CABINET`，所有新界面必须在它之下 100% 还原。

### 包分层（强制）

`ui/` 已按层拆开，**新代码只能落在对应层，依赖方向只能向下**：

| 层 | 包 | 放什么 | 允许依赖 |
| --- | --- | --- | --- |
| 1 | `ui.tokens` | `AccountUiColors` / `AccentTheme` / `AccountUiType` / `AccountUiSpacing` / `AccountUiRadius` | 无 |
| 2 | `ui.foundation` | 绘制与布局基建：`Jelly` / `JellyValue` / `Easing` / `Tween` / `WrapGridLayout` / `FlowWrapLayout` / `AccountTableStyle` | tokens |
| 3 | `ui.components` | 可复用组件：`AccountUiKit`（门面）/ `AccountElements` / `AccountButtons` / `QuotaCards` / `CardContent` / `AccountHero` / `AccountGridCard` | tokens、foundation |
| 4 | `ui.views` | 页面与弹窗：`AccountPanel` / `DashboardPanel` / `DashboardGrid` / `AccountGridView` / `QuotaView` / 各 `*Dialog` / `AccountSettingsConfigurable` | tokens、foundation、components |
| 4 | `ui.statusbar` | 状态栏 widget 与装配 | tokens、foundation、components、views |
| — | `ui`（根） | 只留 IDE 入口与纯策略：`AccountToolWindowFactory` / `OpenPanelAction` / `QuickPhraseAction` / `AccountSort` | 全部 |

- **禁止反向依赖**：components 不许 import views / statusbar；foundation 不许 import components 及以上。需要「组件依赖页面外壳」时，把那个外壳下沉到 components，或把公共部分抽到 foundation（`FlowWrapLayout` 就是这样从 `DashboardGrid` 下沉的）。
- **新增可复用控件先进 `ui.components`**，并在 `AccountUiKit` 加一个工厂方法作为唯一入口；页面里只调门面，不直接 new 组件。
- 页面只写排版与业务取数，不写绘制细节（`paintComponent` 只出现在 foundation / components / 图表类小组件里）。
- `plugin.xml` 里的四个 FQN 随包变动：`ui.AccountToolWindowFactory`、`ui.OpenPanelAction`、`ui.QuickPhraseAction`、`ui.views.AccountSettingsConfigurable`、`ui.statusbar.CodeBuddyStatusBarWidgetFactory`。搬类时必须同步改 `META-INF/plugin.xml`，否则插件启动就不挂载。

---

## 其他约定

- **版本与发布**：版本号在 `build.gradle.kts`；发布流程见 `docs/release.md`；变更说明见 `CHANGELOG.md`。改动界面后若涉及发布物料，注意同步 `docs/*.html` 的版本号（历史上有过版本不一致问题，见 `design/ui-audit.html`）。
- **第三方素材署名**：桌宠形象来自 dsh-pet（CC BY-NC-SA 4.0），见 `THIRD-PARTY-NOTICES.md`；修改或新增素材必须遵守署名与非商业条款。
- **凭据安全**：不得在日志、UI、导出物以外泄露 token；任何 AI 不得把凭据写入仓库文件。

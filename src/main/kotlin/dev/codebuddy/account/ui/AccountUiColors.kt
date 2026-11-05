package dev.codebuddy.account.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.util.messages.Topic
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Font
import javax.swing.UIManager

/**
 * 界面配色集中定义。
 *
 * ## 视觉方向：档案柜 / The Cabinet（复刻自 design/index.html、design/plugin-ui.html）
 *
 * 冷蓝灰档案纸 + 墨蓝字 + 朱印唯一高饱和 + 黄铜读数 + 铅线：
 *
 * - **纸面三级**：paper（画布）→ drawer（抽屉 / 工具栏）→ card（档案卡），
 *   层级靠 1px 铅线描边分开，不用渐变、不做体积高光。
 * - **唯一高饱和**：朱印红只给「当前 / 异常 / 选中下划线」；其余状态走墨蓝与语义色。
 * - **黄铜读数**：所有关键数字、进度条充盈用黄铜，数字一律等宽，位数变化不挤动布局。
 * - **深墨带**：窗口标题栏与底部状态栏是同一条墨蓝，配朱红圆点。
 * - **形状**：直角为主（2~3px），档案卡左上角挂 tab，卡片底部用虚线分隔。
 *
 * 全部使用 [JBColor]（浅色 / 深色成对），跟随 IDE 主题；
 * 主色相关字段是运行时可变的，由 [applyTheme] 在切换色调时整体替换。
 */
object AccountUiColors {

    // ---------------------------------------------------------------
    // 表面阶梯
    // ---------------------------------------------------------------

    /**
     * 固定浅色：浅 / 深两个槽位取同一个值。
     *
     * 设计稿是一套冷蓝灰档案纸，IDE 切到深色主题时不应把它压暗成深灰，
     * 所以所有 token 都固定用稿子里的浅色值，不跟随主题。
     */
    private fun fixed(rgb: Int): JBColor = JBColor(Color(rgb), Color(rgb))

    /** 档案纸：面板 / 内容区画布 */
    val panelBg = fixed(0xE8ECEF)

    /** 抽屉面：工具栏 / 次级分段 / 表头 */
    val surfaceBg = fixed(0xDDE4E8)

    /** 档案卡纸面 */
    val cardBg = fixed(0xF7F9F9)

    /** 卡片 hover 底色（只提亮一档，不做位移变形） */
    val cardHoverBg = fixed(0xFFFFFF)

    /** 卡片内嵌块底色（细项、分组） */
    val itemBg = fixed(0xEEF2F4)

    /** 次级表面（表头、输入区、分段轨道） */
    val surfaceHi = fixed(0xDDE4E8)

    /** 当前激活账号卡片底色：档案纸略深一档，靠墨蓝描边区分 */
    var cardActiveBg: JBColor = fixed(0xF1F4F5)
        private set

    /** 选中态卡片底色 */
    var cardSelectedBg: JBColor = fixed(0xF1F4F5)
        private set

    // ---------------------------------------------------------------
    // 档案柜专属色（与 HTML 设计稿 token 一一对应）
    // ---------------------------------------------------------------

    /** 墨蓝 ink：标题栏、状态栏、主按钮、档案卡 tab */
    val ink = fixed(0x1B2A3A)

    /** 墨蓝带上的文字 */
    val onInk = fixed(0xF7F9F9)

    /** 墨蓝带上的次级文字（约 55% 不透明度） */
    val onInkDim = fixed(0xA9BAC6)

    /** 朱印 seal：全界面唯一高饱和，用于「当前」、异常与选中下划线 */
    val seal = fixed(0xB23A2C)

    /** 朱印淡底 */
    val sealSoft = fixed(0xF8EAE7)

    /** 黄铜 brass：读数数字、进度条充盈、刻度 */
    val brass = fixed(0x9B7826)

    /** 黄铜压深：数字描边、强调文字 */
    val brassInk = fixed(0x745617)

    /** 黄铜淡底：选中行 / 高亮块 */
    val brassSoft = fixed(0xF3EBD6)

    /** 铅线 rule：所有 1px 描边 */
    val rule = fixed(0xC6D0D7)

    /** 轨道底色 */
    val track = fixed(0xD3DBE0)

    /** 刻度线颜色（进度条上的 9px 栅格） */
    val tick = JBColor(Color(27, 42, 58, 56), Color(27, 42, 58, 56))

    // ---------------------------------------------------------------
    // 描边与投影
    // ---------------------------------------------------------------

    /** 常规边框 */
    val border = rule

    /** 弱分隔线（卡片内部分行） */
    val divider = fixed(0xD9E1E6)

    /** 卡片轻阴影：单层 1px 偏移，只做「贴纸上浮」不营造体积 */
    val cardShadow = JBColor(Color(27, 42, 58, 26), Color(27, 42, 58, 26))

    /** 激活账号边框（随主色变化） */
    var borderActive: JBColor = fixed(0x1B2A3A)
        private set

    // ---------------------------------------------------------------
    // 强调色（随色调变化）
    // ---------------------------------------------------------------

    /** 结构主色：朱印。选中、徽标、焦点、underline tab */
    var accent: JBColor = fixed(0xB23A2C)
        private set

    /** 主色压深：按下态 */
    var accentDeep: JBColor = fixed(0x8E2D22)
        private set

    /** 主色柔和底（选中行、强调块） */
    var accentSoft: JBColor = fixed(0xF8EAE7)
        private set

    /** 主色之上的文字色 */
    var onAccent: JBColor = fixed(0xFFFFFF)
        private set

    /**
     * 大面积填充色（主按钮、档案卡 tab）：墨蓝实心。
     *
     * 与 HTML 的 `.btn` / `.card-tab` 一致：ink 底 + card 色文字，
     * 朱印只留给出戳记与下划线，不做整块填充。
     */
    var accentFill: JBColor = fixed(0x1B2A3A)
        private set

    /** 填充色之上的文字色 */
    var onAccentFill: JBColor = fixed(0xF7F9F9)
        private set

    /** 焦点环：黄铜，与读数同色 */
    var focusRing: JBColor = fixed(0x9B7826)
        private set

    /** 卡片顶部 1px 内描边的高光 */
    val edgeHighlight = JBColor(Color(255, 255, 255, 140), Color(255, 255, 255, 140))

    // ---------------------------------------------------------------
    // 深墨带（标题栏 / 状态栏 / 弹窗顶栏）
    // ---------------------------------------------------------------

    /** 深墨带起点 */
    var heroStart: JBColor = fixed(0x1B2A3A)
        private set

    /** 深墨带终点（弱渐变，几乎不可见） */
    var heroEnd: JBColor = fixed(0x2C4257)
        private set

    /** 深墨带上的主文本 */
    val heroText = fixed(0xF7F9F9)

    /** 深墨带上的次级文本 */
    val heroTextDim = fixed(0xA9BAC6)

    /** 深墨带内分隔线 */
    val heroDivider = JBColor(Color(247, 249, 249, 40), Color(247, 249, 249, 40))

    /** 深墨带内次级指标块底色（半透明白） */
    val heroCell = JBColor(Color(255, 255, 255, 24), Color(255, 255, 255, 24))

    /** Hero 仪表网格点阵（档案柜不绘制，保留字段供旧绘制件引用） */
    val heroGrid = JBColor(Color(255, 255, 255, 12), Color(255, 255, 255, 12))

    /** 深墨带上的浅色强调（数值脉冲）：黄铜 */
    var heroSheen: JBColor = fixed(0xD6B15C)
        private set

    // ---------------------------------------------------------------
    // 文本
    // ---------------------------------------------------------------

    /** 主文本：墨蓝 */
    val textPrimary = fixed(0x1B2A3A)

    /** 次文本 */
    val textSecondary = fixed(0x4F5E6D)

    /** 弱文本（表头、辅助说明） */
    val textMuted = fixed(0x5A6978)

    /**
     * 最弱文本（占位、未刷新，仅用于非关键信息）。
     * 用 #666F7D 而非更浅的灰：在档案卡纸面上达 4.81:1，满足 WCAG AA 正文 4.5:1。
     */
    val textFaint = fixed(0x666F7D)

    /** 正常 / 有效 */
    val success = fixed(0x15803D)

    /** 警告 / 临期：黄铜压深色 */
    val warning = fixed(0x745617)

    /** 危险 / 过期、额度极低：朱印 */
    val danger = seal

    // ---------------------------------------------------------------
    // 进度条：黄铜充盈 + 铅线轨道 + 9px 刻度
    // ---------------------------------------------------------------

    /** 进度条轨道 */
    val barTrack = track

    /** 进度条充盈（正常）：黄铜 */
    val barFill = brass

    /** 进度条充盈顶部提亮 */
    val barFillTop = fixed(0xB08A2E)

    /** 进度条充盈（低额度）：朱印 */
    val barFillLow = seal

    // ---------------------------------------------------------------
    // 标签
    // ---------------------------------------------------------------

    /** 个人账号标签 */
    val tagPersonalBg = fixed(0xE6F4F8)
    val tagPersonalFg = fixed(0x0B6C82)

    /** 企业账号标签 */
    val tagEnterpriseBg = fixed(0xFDF3E3)
    val tagEnterpriseFg = fixed(0x96590B)

    /** 认证域标签：档案纸底 + 墨蓝灰字，等宽小字 */
    val tagDomainBg = fixed(0xE8ECEF)
    val tagDomainFg = fixed(0x5A6978)

    /** 认证域标签（workbuddy）：与主色区分的第二域名色 */
    var tagDomainAltBg: JBColor = fixed(0xEFECFB)
        private set
    var tagDomainAltFg: JBColor = fixed(0x5B4BC4)
        private set

    /** 头像配色池：与 plugin-ui.html 的 av-1~av-4 一致 */
    val avatarPalette = arrayOf(
        Color(0x0E7490), Color(0x5B4BC4), Color(0x1E7A5E), Color(0xA85B3E),
        Color(0x2E6FA8), Color(0xA8466B), Color(0x7A4B8E), Color(0xB03A3A),
    )

    // ---------------------------------------------------------------
    // 账本式表格：墨色表头线 + 铅线行
    // ---------------------------------------------------------------

    /** 表头底色：抽屉面 */
    val tableHeaderBg = surfaceBg

    /** 表体底色：档案纸 */
    val tableBodyBg = cardBg

    /** 表格斑马纹底色（档案柜不用斑马纹，保留字段供旧代码引用） */
    val tableStripeBg = cardBg

    /** 表格网格线 */
    val tableGrid = rule

    // ---------------------------------------------------------------
    // 色调切换
    // ---------------------------------------------------------------

    /** 当前生效的色调 */
    @Volatile
    var theme: AccentTheme = AccentTheme.CABINET
        private set

    /**
     * 切换色调：整体替换主色相关字段。
     *
     * 中性色与语义色不变（它们与配色方案无关）；
     * 所有颜色只取调色板的浅色值（界面固定浅色档案纸，不跟随 IDE 深色主题）。
     */
    fun applyTheme(next: AccentTheme) {
        theme = next
        val p = next.palette
        accent = JBColor(p.accentLight, p.accentLight)
        accentDeep = JBColor(p.deepLight, p.deepLight)
        accentSoft = JBColor(p.softLight, p.softLight)
        onAccent = JBColor(p.onLight, p.onLight)
        accentFill = JBColor(p.fillLight, p.fillLight)
        onAccentFill = JBColor(p.onFillLight, p.onFillLight)
        focusRing = JBColor(p.ringLight, p.ringLight)
        borderActive = JBColor(p.borderLight, p.borderLight)
        heroStart = JBColor(p.heroStartLight, p.heroStartLight)
        heroEnd = JBColor(p.heroEndLight, p.heroEndLight)
        heroSheen = JBColor(p.sheenLight, p.sheenLight)
        cardActiveBg = if (next == AccentTheme.CABINET) {
            // 档案柜：激活态靠墨蓝描边，底色仍是档案纸，不铺彩色
            fixed(0xF1F4F5)
        } else {
            JBColor(p.softLight, p.softLight)
        }
        cardSelectedBg = cardActiveBg
        tagDomainAltBg = JBColor(p.altSoftLight, p.altSoftLight)
        tagDomainAltFg = JBColor(p.altFgLight, p.altFgLight)
    }

    /** 读取上次选择的色调（无记录或旧方案已下线时用「档案柜」） */
    fun loadPersistedTheme(): AccentTheme {
        val id = try {
            PropertiesComponent.getInstance().getValue(THEME_KEY)
        } catch (_: Throwable) {
            null
        }
        val theme = AccentTheme.byId(id) ?: AccentTheme.CABINET
        applyTheme(theme)
        return theme
    }

    /** 记住色调选择 */
    fun persistTheme(theme: AccentTheme) {
        try {
            PropertiesComponent.getInstance().setValue(THEME_KEY, theme.id)
        } catch (_: Throwable) {
            // 设置写不进去也不影响本次会话内的切换
        }
    }

    private const val THEME_KEY = "codebuddy.account.ui.theme"
}

/**
 * 界面色调方案。
 *
 * [CABINET] 是默认方案，来自 design 目录下两份 HTML 的「档案柜」；
 * 其余为旧版「素墨仪表」留下的可选色调，中性纸面与语义色始终共用。
 */
enum class AccentTheme(
    val id: String,
    val displayName: String,
    val palette: AccentPalette,
) {
    /** 档案柜：默认。墨蓝主按钮 + 朱印强调 + 黄铜读数 */
    CABINET("cabinet", "档案柜", AccentPalette(
        accentLight = Color(0xB23A2C), accentDark = Color(0xE58A76),
        deepLight = Color(0x8E2D22), deepDark = Color(0xC96A57),
        softLight = Color(0xF8EAE7), softDark = Color(0x3A2422),
        onLight = Color(0xFFFFFF), onDark = Color(0x1B2A3A),
        fillLight = Color(0x1B2A3A), fillDark = Color(0x3B5368),
        onFillLight = Color(0xF7F9F9), onFillDark = Color(0xF1F5F7),
        ringLight = Color(0x9B7826), ringDark = Color(0xD6B15C),
        borderLight = Color(0x1B2A3A), borderDark = Color(0x93A8B8),
        heroStartLight = Color(0x1B2A3A), heroStartDark = Color(0x101922),
        heroEndLight = Color(0x2C4257), heroEndDark = Color(0x1B2A3A),
        sheenLight = Color(0xD6B15C), sheenDark = Color(0xE2BE6A),
        altSoftLight = Color(0xEFECFB), altSoftDark = Color(0x241E3D),
        altFgLight = Color(0x5B4BC4), altFgDark = Color(0xB8AAF8),
    )),

    /** 墨青：旧版默认色，保留以兼容已保存的色调选择 */
    INK_TEAL("teal", "墨青", AccentPalette(
        accentLight = Color(0x0E7490), accentDark = Color(0x4FC3D9),
        deepLight = Color(0x0B5C73), deepDark = Color(0x7FD8E8),
        softLight = Color(0xE6F4F8), softDark = Color(0x102A31),
        onLight = Color(0xFFFFFF), onDark = Color(0x062026),
        fillLight = Color(0x0E7490), fillDark = Color(0x4FC3D9),
        onFillLight = Color(0xFFFFFF), onFillDark = Color(0x062026),
        ringLight = Color(0x1C93AE), ringDark = Color(0x3AA6BC),
        borderLight = Color(0xBFDCE4), borderDark = Color(0x255C68),
        heroStartLight = Color(0x0D2B33), heroStartDark = Color(0x10262D),
        heroEndLight = Color(0x12414E), heroEndDark = Color(0x17323B),
        sheenLight = Color(0xCFE6EC), sheenDark = Color(0x9FCEDA),
        altSoftLight = Color(0xEFECFB), altSoftDark = Color(0x241E3D),
        altFgLight = Color(0x5B4BC4), altFgDark = Color(0xB8AAF8),
    )),

    /** 石墨：纯中性强调，界面完全不抢内容的注意力 */
    GRAPHITE("graphite", "石墨", AccentPalette(
        accentLight = Color(0x3F4A56), accentDark = Color(0xB8C2CE),
        deepLight = Color(0x2C353E), deepDark = Color(0xD2DAE3),
        softLight = Color(0xEDF1F5), softDark = Color(0x242A31),
        onLight = Color(0xFFFFFF), onDark = Color(0x0E1317),
        fillLight = Color(0x3F4A56), fillDark = Color(0xB8C2CE),
        onFillLight = Color(0xFFFFFF), onFillDark = Color(0x0E1317),
        ringLight = Color(0x6B7885), ringDark = Color(0x8E99A6),
        borderLight = Color(0xCBD4DE), borderDark = Color(0x4A5561),
        heroStartLight = Color(0x1B2229), heroStartDark = Color(0x232B33),
        heroEndLight = Color(0x2E3841), heroEndDark = Color(0x2E3841),
        sheenLight = Color(0xC9D3DC), sheenDark = Color(0xAEBAC5),
        altSoftLight = Color(0xE6F4F8), altSoftDark = Color(0x0F2C33),
        altFgLight = Color(0x0B6C82), altFgDark = Color(0x7FD3E4),
    )),

    /** 靛蓝：旧版默认色，保留以兼容已保存的色调选择 */
    INDIGO("indigo", "靛蓝", AccentPalette(
        accentLight = Color(0x3B4AB8), accentDark = Color(0x9AA9FF),
        deepLight = Color(0x2C3999), deepDark = Color(0xB9C4FF),
        softLight = Color(0xE9EBFA), softDark = Color(0x1A1F3D),
        onLight = Color(0xFFFFFF), onDark = Color(0x0A0E2A),
        fillLight = Color(0x3B4AB8), fillDark = Color(0x9AA9FF),
        onFillLight = Color(0xFFFFFF), onFillDark = Color(0x0A0E2A),
        ringLight = Color(0x5E6FD6), ringDark = Color(0x7C8BE0),
        borderLight = Color(0xBFC6E8), borderDark = Color(0x3A4585),
        heroStartLight = Color(0x141936), heroStartDark = Color(0x171C3A),
        heroEndLight = Color(0x232C58), heroEndDark = Color(0x27305C),
        sheenLight = Color(0xD5D9F2), sheenDark = Color(0xB9C2E8),
        altSoftLight = Color(0xE6F4F8), altSoftDark = Color(0x0F2C33),
        altFgLight = Color(0x0B6C82), altFgDark = Color(0x7FD3E4),
    )),

    /**
     * 晴空：亮蓝。填充 #0EA5E9 配深色文字（7:1），
     * 文字 / 描边退回 #0369A1（5.9:1），亮而不糊。
     */
    SKY_BLUE("sky", "晴空", AccentPalette(
        accentLight = Color(0x0369A1), accentDark = Color(0x7DD3FC),
        deepLight = Color(0x075985), deepDark = Color(0xBAE6FD),
        softLight = Color(0xE0F2FE), softDark = Color(0x0B2B3D),
        onLight = Color(0xFFFFFF), onDark = Color(0x06283D),
        fillLight = Color(0x38BDF8), fillDark = Color(0x7DD3FC),
        onFillLight = Color(0x04283D), onFillDark = Color(0x04283D),
        ringLight = Color(0x38BDF8), ringDark = Color(0x38BDF8),
        borderLight = Color(0xBAE6FD), borderDark = Color(0x0C4A6E),
        heroStartLight = Color(0x075985), heroStartDark = Color(0x0A2C3F),
        heroEndLight = Color(0x0EA5E9), heroEndDark = Color(0x134E6B),
        sheenLight = Color(0xBAE6FD), sheenDark = Color(0x7DD3FC),
        altSoftLight = Color(0xEFECFB), altSoftDark = Color(0x241E3D),
        altFgLight = Color(0x5B4BC4), altFgDark = Color(0xB8AAF8),
    )),

    /** 薄荷：明亮绿。填充 #34D399 配深绿文字，比墨青轻快一档 */
    MINT("mint", "薄荷", AccentPalette(
        accentLight = Color(0x047857), accentDark = Color(0x6EE7B7),
        deepLight = Color(0x065F46), deepDark = Color(0xA7F3D0),
        softLight = Color(0xD1FAE5), softDark = Color(0x08281F),
        onLight = Color(0xFFFFFF), onDark = Color(0x04211A),
        fillLight = Color(0x34D399), fillDark = Color(0x34D399),
        onFillLight = Color(0x052E22), onFillDark = Color(0x052E22),
        ringLight = Color(0x34D399), ringDark = Color(0x34D399),
        borderLight = Color(0xA7F3D0), borderDark = Color(0x065F46),
        heroStartLight = Color(0x064E3B), heroStartDark = Color(0x08261F),
        heroEndLight = Color(0x059669), heroEndDark = Color(0x0B3B2E),
        sheenLight = Color(0xA7F3D0), sheenDark = Color(0x6EE7B7),
        altSoftLight = Color(0xEFECFB), altSoftDark = Color(0x241E3D),
        altFgLight = Color(0x5B4BC4), altFgDark = Color(0xB8AAF8),
    )),

    /** 珊瑚：明亮粉红。填充 #FB7185 配深玫红文字，最跳的一套 */
    CORAL("coral", "珊瑚", AccentPalette(
        accentLight = Color(0xBE123C), accentDark = Color(0xFDA4AF),
        deepLight = Color(0x9F1239), deepDark = Color(0xFECDD3),
        softLight = Color(0xFFE4E6), softDark = Color(0x33121A),
        onLight = Color(0xFFFFFF), onDark = Color(0x2B0710),
        fillLight = Color(0xFB7185), fillDark = Color(0xFB7185),
        onFillLight = Color(0x4C0519), onFillDark = Color(0x4C0519),
        ringLight = Color(0xFB7185), ringDark = Color(0xFB7185),
        borderLight = Color(0xFECDD3), borderDark = Color(0x881337),
        heroStartLight = Color(0x881337), heroStartDark = Color(0x2A0A12),
        heroEndLight = Color(0xE11D48), heroEndDark = Color(0x4C0519),
        sheenLight = Color(0xFECDD3), sheenDark = Color(0xFDA4AF),
        altSoftLight = Color(0xEFECFB), altSoftDark = Color(0x241E3D),
        altFgLight = Color(0x5B4BC4), altFgDark = Color(0xB8AAF8),
    ));

    companion object {
        fun byId(id: String?): AccentTheme? = entries.firstOrNull { it.id == id }
    }
}

/** 一个色调方案的主色系（浅色 / 深色成对） */data class AccentPalette(
    val accentLight: Color,
    val accentDark: Color,
    val deepLight: Color,
    val deepDark: Color,
    val softLight: Color,
    val softDark: Color,
    val onLight: Color,
    val onDark: Color,
    val fillLight: Color,
    val fillDark: Color,
    val onFillLight: Color,
    val onFillDark: Color,
    val ringLight: Color,
    val ringDark: Color,
    val borderLight: Color,
    val borderDark: Color,
    val heroStartLight: Color,
    val heroStartDark: Color,
    val heroEndLight: Color,
    val heroEndDark: Color,
    val sheenLight: Color,
    val sheenDark: Color,
    val altSoftLight: Color,
    val altSoftDark: Color,
    val altFgLight: Color,
    val altFgDark: Color,
)

/**
 * 色调变更通知。
 *
 * 卡片底色、描边色在构造时就固定了，只重绘不会换色，
 * 因此切换色调后需要让已打开的面板重建一次。
 */
interface UiThemeListener {
    fun themeChanged(theme: AccentTheme)
}

/** 色调变更广播（application 级） */
object UiThemeEvents {

    val TOPIC: Topic<UiThemeListener> =
        Topic.create("CodeBuddy UI theme changed", UiThemeListener::class.java)

    fun fire(theme: AccentTheme) {
        try {
            ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).themeChanged(theme)
        } catch (_: Throwable) {
            // 广播失败不影响本次切换
        }
    }
}

/**
 * 圆角尺寸阶梯（档案柜：直角为主，只在边缘收 2~3px 的纸角）。
 *
 * 形状有职责，不靠圆角表达「质感」：
 * - [card]：外层档案卡 / 索引卡
 * - [inner]：内层块、输入框
 * - [chip]：小徽标 / 标签
 * - [pill]：胶囊（只给仍需要胶囊的旧组件，进度条一律直角）
 */
object AccountUiRadius {
    const val card = 3
    const val inner = 2
    const val chip = 2
    const val pill = 100
}

/**
 * 间距阶梯（未落地 UI 缩放，使用处需经 JBUI.scale）。
 *
 * 4 为基准：2 / 4 / 8 / 12 / 16 / 20。
 * 约定：块内用 [xs]/[sm]，块之间用 [blockGap]，卡片之间用 [cardGap]，
 * 卡片内边距用 [cardPadV]/[cardPadH]，越外层越松。
 */
object AccountUiSpacing {
    const val xxs = 2
    const val xs = 4
    const val sm = 8
    const val md = 12
    const val lg = 16
    const val xl = 20

    /** 卡片之间的纵向间距（档案柜网格 gap 18px） */
    const val cardGap = 18

    /** 卡片内边距（外框卡片） */
    const val cardPadV = 16
    const val cardPadH = 18

    /** 卡片内区块间距 */
    const val blockGap = 8

    /** 列表行之间的间距 */
    const val rowGap = 6

    /** 分区（section）之间的间距 */
    const val sectionGap = 22

    /** 网格卡片之间的间距 */
    const val gridGap = 18

    /** 网格卡片最小列宽：与 plugin-ui.html 的 minmax(330px, 1fr) 一致 */
    const val gridMinColumnWidth = 330

    /** Hero 指标条内边距 */
    const val heroPadV = 14
    const val heroPadH = 16
}

/**
 * 字号阶梯（已含 UI 缩放，直接传给 `deriveFont`）。
 *
 * 只有五档，禁止在界面里再写裸数字：
 * [display] 关键指标数字 → [number] 次级数字 → [title] 区块标题 →
 * [body] 正文 → [label] 行文本 → [caption] 辅助说明 → [micro] 徽标。
 *
 * 数字一律走 [mono]：等宽数字在刷新时不会左右跳动，也更像仪表读数。
 */
object AccountUiType {
    /** 阶梯外的特殊字号（超大数字等）经此换算，保持 UI 缩放一致 */
    fun scaled(size: Int): Float = JBUI.scale(size).toFloat()

    /** 关键指标数字（KPI 主数值） */
    val display: Float get() = scaled(32)

    /** 看板 Hero 数字：档案柜的 dash-hero-num 是 38px */
    val heroNum: Float get() = scaled(40)

    /** 档案卡 tab / 索引卡标签 */
    val tab: Float get() = scaled(12)

    /** 次级数字（同等重要的副指标） */
    val number: Float get() = scaled(22)

    /** 区块标题 / 卡片标题 */
    val title: Float get() = scaled(14)

    /** 正文 */
    val body: Float get() = scaled(13)

    /** 行文本 / 标签 */
    val label: Float get() = scaled(12)

    /** 辅助说明 */
    val caption: Float get() = scaled(11)

    /** 徽标文字 */
    val micro: Float get() = scaled(10)

    /** 等宽字族（取编辑器字体，取不到时退回标签字体） */
    private val monoFamily: String by lazy {
        val editor = try {
            EditorColorsManager.getInstance().globalScheme.editorFontName
        } catch (_: Throwable) {
            null
        }
        editor?.takeIf { it.isNotBlank() } ?: baseLabelFont().family
    }

    /**
     * 仪表数字字体：等宽字族 + 指定字号。
     *
     * 只在数字 / 时间 / 百分比上使用，正文保持界面字体，避免整屏像代码编辑器。
     * **中文一律不要走这里**：编辑器等宽字体通常没有中文字形，会渲染成方框。
     */
    fun mono(size: Float, style: Int = Font.BOLD): Font = Font(monoFamily, style, 1).deriveFont(size)

    /** 界面字体的指定字号：中文标签、说明文字用这个 */
    fun ui(size: Float, style: Int = Font.PLAIN): Font = baseLabelFont().deriveFont(style, size)

    /**
     * 文本感知的字体：纯 ASCII / 数字走等宽，含中日韩字符回退到界面字体。
     *
     * 这是本次「方框乱码」的修复点：编辑器字体（Consolas / Cascadia Mono 等）
     * 不带中文字形，把中文交给 [mono] 会整片变成 □。
     */
    fun forText(text: String, size: Float, style: Int = Font.PLAIN): Font =
        if (text.any { it.code >= 0x2E80 }) ui(size, style) else mono(size, style)
}

/**
 * 自绘 [javax.swing.JComponent] 的基础字体。
 *
 * JComponent 自身没有默认字体，构造期还未挂到父容器上，`getFont()` 返回 null，
 * 直接 `font.deriveFont(...)` 会 NPE，因此统一用 LAF 的 Label 字体兜底。
 */
internal fun baseLabelFont(): Font =
    UIManager.getFont("Label.font") ?: Font(Font.SANS_SERIF, Font.PLAIN, JBUI.scale(12))

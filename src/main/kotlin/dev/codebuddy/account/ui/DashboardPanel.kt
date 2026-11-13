package dev.codebuddy.account.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.codebuddy.account.checkin.CheckinTracker
import dev.codebuddy.account.growth.GrowthService
import dev.codebuddy.account.growth.GrowthTracker
import dev.codebuddy.account.history.ActivityFeed
import dev.codebuddy.account.history.HistoryActivity
import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.pet.CbEventCenter
import dev.codebuddy.account.pet.CbWebObserver
import dev.codebuddy.account.pet.PetService
import dev.codebuddy.account.pet.PetSettings
import dev.codebuddy.account.quota.QuotaPackage
import dev.codebuddy.account.quota.QuotaSummary
import dev.codebuddy.account.quota.QuotaTime
import dev.codebuddy.account.service.AccountEvents
import dev.codebuddy.account.service.AccountLibrary
import dev.codebuddy.account.service.AccountSettings
import dev.codebuddy.account.service.AutoRefreshService
import dev.codebuddy.account.service.QuotaAlertSettings
import dev.codebuddy.account.service.QuotaScheduler
import dev.codebuddy.account.usage.ModelQuotaRuleState
import dev.codebuddy.account.usage.ModelQuotaTracker
import dev.codebuddy.account.usage.UsageRecord
import dev.codebuddy.account.usage.UsageTodayTracker
import dev.codebuddy.account.usage.formatCredit
import java.awt.*
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*

/**
 * 看板：账号 / 额度 / 消耗 / 运行态的总览视图。
 *
 * 全部功能都以卡片形式铺在网格里（[DashboardCardGridLayout]）。点「管理卡片」进入编辑模式后，
 * 卡片可拖拽排序、可移除，也能从「＋ 添加卡片」里恢复；增删与重排都带过渡动画。
 * 工具条上的「尺寸」统一切换全屏卡片的高度档位
 * （2×1 / 2×2 / 2×4）——所有卡片始终同宽同高，内容按档位容量裁剪，不因数据条数高矮不齐。
 *
 * 卡片的可见性、顺序与统一尺寸记在 [AccountSettings]，重开工具窗仍是用户排过的样子。
 *
 * 除账号 / 额度数据外，这里也承载 CodeBuddy 运行态（限流、成长计划、观察代点、事件记录、
 * 对话活跃）：原先的「CodeBuddy 增强助手」窗口已删除，同一功能点只在这套卡片里出现一次。
 */
class DashboardPanel(
    private val onOpenList: () -> Unit,
    private val onRefreshAll: () -> Unit,
    private val onOpenQuotaDetail: () -> Unit,
    /** 「7 天内到期」卡片入口：只列临期额度，按账号分类 */
    private val onOpenExpiringQuota: () -> Unit,
    private val onOpenUsageDetail: () -> Unit,
    private val onOpenHistory: () -> Unit,
    private val onOpenReport: () -> Unit,
    private val onCheckin: () -> Unit,
    private val onGrowth: () -> Unit,
    private val onGrowthTravel: () -> Unit,
    private val onGrowthActivity: () -> Unit,
    private val onGrowthKeepalive: () -> Unit,
    private val onOpenGrowthCenter: () -> Unit,
    /** 「数据状态」卡入口：打开用量统计（聚合视图，与请求级消耗明细区分） */
    private val onOpenUsageStats: () -> Unit,
    private val onOpenSettings: () -> Unit,
    /** 「快捷短语」入口：管理常用短语并发送到 CodeBuddy 输入框 */
    private val onOpenPhrases: () -> Unit,
) : JPanel(BorderLayout()) {

    private val library = AccountLibrary.getInstance()
    private val settings = AccountSettings.getInstance()
    private val content = JPanel()
    private val timeFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
    private val hmFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    /**
     * 卡片网格：按可用宽度自动分列，卡片可跨列（整宽 / 半宽）。
     *
     * 卡片顺序与可见性由 [DashboardCardId] 与设置里的隐藏列表共同决定，
     * 每次刷新只重建卡片内容，网格本身常驻，避免滚动位置被重置。
     */
    private val grid = JPanel(
        DashboardCardGridLayout(
            JBUI.scale(AccountUiSpacing.gridGap),
            JBUI.scale(AccountUiSpacing.gridGap),
            JBUI.scale(DashboardCardGridLayout.MIN_CELL_WIDTH),
        )
    ).apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
    }

    /** 拖拽落点提示：同一时刻只高亮一张卡片 */
    private val dropTracker = CardDropTracker()

    /** 编辑模式：进入后才显示抓手与移除按钮，并允许拖动排序 / 删除 / 添加 */
    private var editMode = false
    private var editButton: JellyButton? = null
    private var addButton: JellyButton? = null

    /** 正在播放的布局位移动画；全量刷新前要先停掉，避免写到已销毁的卡片实例上 */
    private var layoutTween: Tween? = null

    // 上一次刷新用的数据快照：换尺寸时只重建那一张卡的内容，不触发全量刷新
    private var lastSaved: List<CredentialSnapshot> = emptyList()
    private var lastActive: CredentialSnapshot? = null
    private var lastQuotas: Map<String, QuotaSummary> = emptyMap()
    private var lastThreshold: Int = 0

    // 对话活跃卡片的本地缓存：ActivityFeed 读盘较重，只在首次展示 / 手动刷新时后台读一次
    private var activityItems: List<ActivityFeed.Item> = emptyList()
    private var activityLoaded = false
    private var activityLoading = false

    /**
     * 今日消耗 Hero：常驻实例。
     *
     * 积分动画要跨刷新延续（100 → 120 的滚动过渡），
     * 所以这里复用同一个组件，由 [heroStrip] 原地更新数值与指标；
     * 每次刷新重建组件会让动画从头再滚一遍。
     */
    private val usageHero = HeroPanel(
        // 标题交给卡片外壳的标题栏，这里不再重复一行标题
        title = "",
        value = "—",
        unit = "credits",
    ).apply {
        // 已经套在 DashboardCard 里，不再自绘一层卡片底，避免卡中卡
        paintSurface = false
    }

    init {
        background = AccountUiColors.panelBg

        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.background = AccountUiColors.panelBg
        content.border = JBUI.Borders.empty(AccountUiSpacing.cardGap, AccountUiSpacing.cardPadH)

        val scroll = JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 16
            background = AccountUiColors.panelBg
            viewport.background = AccountUiColors.panelBg
        }
        add(scroll, BorderLayout.CENTER)

        // 整块可点击 → 打开消耗明细（只装一次：Hero 是常驻实例，重复叠加会多次触发）
        installCardClick(usageHero, "点击查看消耗明细", onOpenUsageDetail, recursive = false)

        // 已经有今日数据时直接落位，避免每次打开工具窗都从 0 滚一遍
        val cachedUsage = UsageTodayTracker.state()
        if (cachedUsage.loaded) {
            usageHero.setValue(formatCredit(cachedUsage.totalCredit), animate = false)
        }
    }

    /** 数据变化后重建看板（EDT 调用） */
    fun update() {
        // 全量重建会换掉所有卡片实例，先停掉可能仍在跑的位移动画
        layoutTween?.stop()
        layoutTween = null
        val saved = library.listSaved()
        val active = library.currentActive()
        val quotas = library.quotaCache.loadAll()
        val threshold = QuotaAlertSettings.getInstance().getState().thresholdPercent

        content.removeAll()
        if (saved.isEmpty()) {
            content.add(emptyHint())
            content.add(Box.createVerticalGlue())
            content.revalidate()
            content.repaint()
            return
        }

        // 记下这一轮的数据，换尺寸 / 重排时直接复用，不再触发一次数据刷新
        lastSaved = saved
        lastActive = active
        lastQuotas = quotas
        lastThreshold = threshold

        // 卡片按「顺序 + 可见性」重建；网格本身常驻，避免刷新时滚动位置被重置
        rebuildGrid()
        content.add(detach(grid))
        content.add(Box.createVerticalGlue())

        content.revalidate()
        content.repaint()

        // 今日消耗 / 今日对话 / 今日签到：只在缓存过期时后台刷新，回来后再重建一次
        UsageTodayTracker.ensureFresh { update() }
        HistoryActivity.ensureFresh { update() }
        if (settings.modelQuotaEnabled) {
            ModelQuotaTracker.ensureFresh { update() }
        }
        if (settings.checkinEnabled) {
            CheckinTracker.ensureFresh { update() }
        }
        if (settings.growthEnabled) {
            GrowthTracker.ensureFresh { update() }
            // 成长计划详情（活跃 / 连登 / 旅行）独立缓存，不跟任务一起查
            GrowthTracker.ensureFreshDetails { update() }
        }
        loadActivityIfNeeded()
    }

    /** 对话活跃：读盘重，后台线程读一次缓存，回来再重建卡片 */
    private fun loadActivityIfNeeded(force: Boolean = false) {
        if (activityLoading) return
        if (!force && activityLoaded) return
        activityLoading = true
        ApplicationManager.getApplication().executeOnPooledThread {
            val items = ActivityFeed.recent(limit = ACTIVITY_ROWS, force = force)
            ApplicationManager.getApplication().invokeLater {
                activityLoading = false
                activityLoaded = true
                activityItems = items
                update()
            }
        }
    }

    // ---------------------------------------------------------------
    // 0. 卡片框架：工具栏 / 分发 / 尺寸与增删
    // ---------------------------------------------------------------

    /** 按标识构建卡片（不可见或数据不足时返回 null） */
    private fun buildCard(
        id: DashboardCardId,
        saved: List<CredentialSnapshot>,
        active: CredentialSnapshot?,
        quotas: Map<String, QuotaSummary>,
        threshold: Int,
    ): JComponent? = when (id) {
        // 今日消耗与关键指标左右并排：两张卡都是半行宽，HERO 在左、KPI 在右。
        // 今日消耗主体是常驻 HeroPanel（数值动画跨刷新延续），下方追加按模型分组的今日消耗。
        DashboardCardId.HERO -> newCard(id) { card ->
            card.body.add(detach(heroStrip(saved, quotas)))
            card.body.add(Box.createVerticalStrut(JBUI.scale(CardMetrics.blockGap)))
            card.body.add(todayModelUsageSection())
        }
        DashboardCardId.KPI -> newCard(id) { card -> card.body.add(kpiRow(saved, active, quotas, threshold)) }
        DashboardCardId.USAGE -> newCard(id) { card -> fillUsageCard(card, saved) }
        DashboardCardId.MODEL_QUOTA -> newCard(
            id,
            title = "模型限额 · ${ModelQuotaTracker.state().periodLabel}",
        ) { card -> fillModelQuotaCard(card) }
        DashboardCardId.RATE_LIMIT -> newCard(id) { card -> fillRateLimitCard(card) }
        DashboardCardId.RANKING -> newCard(id) { card -> fillRankingCard(card, saved, active, quotas) }
        DashboardCardId.EXPIRING -> newCard(id) { card -> fillExpiringCard(card, saved, quotas) }
        DashboardCardId.CHECKIN -> newCard(id) { card -> fillCheckinCard(card) }
        DashboardCardId.GROWTH_PLAN -> newCard(id) { card -> fillGrowthPlanCard(card) }
        DashboardCardId.GROWTH -> newCard(id) { card -> fillGrowthCard(card) }
        DashboardCardId.ASSISTANT -> newCard(id) { card -> fillAssistantCard(card) }
        DashboardCardId.EVENTS -> newCard(id) { card -> fillEventsCard(card) }
        DashboardCardId.ACTIVITY -> newCard(id) { card -> fillActivityCard(card) }
        DashboardCardId.SCHEDULER -> newCard(id) { card -> fillSchedulerCard(card) }
        DashboardCardId.STATUS -> newCard(id) { card -> fillStatusCard(card, saved, active, quotas, threshold) }
        DashboardCardId.GUIDE -> newCard(id) { card -> fillGuideCard(card) }
    }

    /**
     * 建一张可增删 / 可拖拽排序的卡片。
     *
     * 尺寸档位由看板统一指定（工具栏切换），所有卡片同宽同高；
     * 内容按档位容量裁剪，卡片之间不会因为数据条数不同而高矮不齐。
     */
    private fun newCard(
        id: DashboardCardId,
        title: String = id.title,
        subtitle: String? = null,
        span: Int = currentSize().columns,
        fill: (DashboardCard) -> Unit,
    ): DashboardCard {
        val card = DashboardCard(
            id = id,
            title = title,
            subtitle = subtitle,
            size = currentSize(),
            span = span,
            onRemove = { card -> removeCardWithAnimation(card, id) },
        )
        installCardEditing(card)
        fill(card)
        return card
    }

    /** 当前统一的卡片尺寸档位 */
    private fun currentSize(): DashboardCardSize =
        DashboardCardSize.of(settings.dashboardCardSize) ?: DashboardCardSize.DEFAULT

    /** 按当前顺序 / 可见性 / 统一尺寸重建网格（不触发数据刷新） */
    private fun rebuildGrid() {
        if (lastSaved.isEmpty()) return
        val hidden = settings.hiddenDashboardCards
        grid.removeAll()
        for (name in effectiveCardOrder()) {
            val id = DashboardCardId.of(name) ?: continue
            if (id.name in hidden) continue
            buildCard(id, lastSaved, lastActive, lastQuotas, lastThreshold)?.let { grid.add(it) }
        }
        // 新建的卡片要继承当前编辑态，否则编辑模式下刷新一次抓手就消失了
        grid.components.filterIsInstance<DashboardCard>().forEach {
            it.setEditing(editMode, animate = false)
        }
        grid.revalidate()
        grid.repaint()
    }

    /** 装上拖拽排序：卡片自身当落点，标题栏抓手当拖起源 */
    private fun installCardEditing(card: DashboardCard) {
        val handler = CardTransferHandler(
            card = card,
            tracker = dropTracker,
            onMove = { from, to, after -> moveCard(from, to, after) },
        )
        card.transferHandler = handler
        card.dragHandle.transferHandler = handler
    }

    /** 当前生效的卡片顺序：设置里为准，新增卡片按枚举顺序补到末尾 */
    private fun effectiveCardOrder(): List<String> {
        val saved = settings.dashboardCardOrder.filter { DashboardCardId.of(it) != null }
        val known = saved.toSet()
        return saved + DashboardCardId.entries.map { it.name }.filter { it !in known }
    }

    /**
     * 拖拽落地：把 [from] 插到 [to] 之前 / 之后，写回设置并就地重排。
     *
     * 只挪组件、不重建卡片内容，避免一次拖拽触发全量刷新（滚动位置与动画都会被打断）。
     */
    private fun moveCard(from: String, to: String, after: Boolean) {
        if (from == to) return
        val order = effectiveCardOrder().toMutableList()
        if (!order.remove(from)) return
        val index = order.indexOf(to)
        if (index < 0) return
        order.add(if (after) index + 1 else index, from)
        settings.setDashboardCardOrder(order)
        AccountEvents.fireDashboardLayoutChanged(this@DashboardPanel)
        reorderGrid(order)
    }

    /** 按新顺序重排网格里的卡片：只挪组件，旧位置 → 新位置走 FLIP 位移动画 */
    private fun reorderGrid(order: List<String>) {
        val cards = grid.components
            .filterIsInstance<DashboardCard>()
            .associateBy { it.id.name }
        if (cards.isEmpty()) return
        val before = captureBounds()
        grid.removeAll()
        for (name in order) cards[name]?.let { grid.add(it) }
        grid.doLayout()
        animateLayout(before, captureBounds(), entering = null)
    }

    // ---------------------------------------------------------------
    // 0.1 编辑模式：切换 / 添加 / 删除 / 过渡动画
    // ---------------------------------------------------------------

    private fun toggleEditMode() {
        editMode = !editMode
        editButton?.text = if (editMode) "完成编辑" else "管理卡片"
        editButton?.toolTipText = if (editMode) {
            "退出编辑模式，卡片顺序与增删已保存"
        } else {
            "进入编辑模式：拖动排序 / 删除 / 添加卡片"
        }
        addButton?.isVisible = editMode
        editButton?.parent?.let {
            it.revalidate()
            it.repaint()
        }
        grid.components.filterIsInstance<DashboardCard>().forEach { it.setEditing(editMode) }
        grid.revalidate()
        grid.repaint()
    }

    /** 添加 = 把隐藏的内置卡片放回看板；新卡片走入场动画，其余卡片 FLIP 让位 */
    private fun addCardWithAnimation(id: DashboardCardId) {
        if (id.name !in settings.hiddenDashboardCards) return
        settings.setDashboardCardVisible(id.name, true)
        AccountEvents.fireDashboardLayoutChanged(this@DashboardPanel)
        rebuildWithTransition(entering = id.name)
    }

    /** 删除 = 先播退场动画，动画结束后再写设置并让其余卡片平滑补位 */
    private fun removeCardWithAnimation(card: DashboardCard, id: DashboardCardId) {
        if (!editMode) return
        layoutTween?.stop()
        val tween = Tween(200, { t -> Easing.inOutCubic(t) }, { p ->
            card.reveal = 1.0 - p
            grid.repaint()
        })
        tween.onEnd = {
            settings.setDashboardCardVisible(id.name, false)
            AccountEvents.fireDashboardLayoutChanged(this@DashboardPanel)
            rebuildWithTransition()
        }
        layoutTween = tween
        tween.start()
    }

    /** 重建网格并给「所有位置变化」补间；[entering] 指定的卡片额外播入场动画 */
    private fun rebuildWithTransition(entering: String? = null) {
        if (lastSaved.isEmpty()) return
        val before = captureBounds()
        rebuildGrid()
        grid.doLayout()
        animateLayout(before, captureBounds(), entering)
    }

    private fun captureBounds(): Map<String, Rectangle> =
        grid.components.filterIsInstance<DashboardCard>().associate { it.id.name to it.bounds }

    /**
     * FLIP：先记旧位置、布局到新位置，再把每帧的差值补到 setBounds 上。
     *
     * 位移放在组件 bounds 层而不是 paint 层，子组件会跟着一起移动；
     * 动画结束交还布局权威，网格高度与滚动范围按新卡片数重算。
     */
    private fun animateLayout(
        before: Map<String, Rectangle>,
        after: Map<String, Rectangle>,
        entering: String?,
    ) {
        layoutTween?.stop()
        val cards = grid.components.filterIsInstance<DashboardCard>()
        val shifts = cards.mapNotNull { card ->
            val from = before[card.id.name] ?: return@mapNotNull null
            val to = after[card.id.name] ?: return@mapNotNull null
            card to (from to to)
        }
        val enteringCard = entering?.let { name -> cards.firstOrNull { it.id.name == name } }
        enteringCard?.reveal = 0.0

        val tween = Tween(260, { t -> Easing.outQuint(t) }, { p ->
            for ((card, pair) in shifts) {
                val (from, to) = pair
                val dx = ((from.x - to.x) * (1.0 - p)).toInt()
                val dy = ((from.y - to.y) * (1.0 - p)).toInt()
                card.setBounds(to.x + dx, to.y + dy, to.width, to.height)
            }
            enteringCard?.reveal = p
            grid.repaint()
        })
        tween.onEnd = {
            enteringCard?.reveal = 1.0
            grid.revalidate()
            content.revalidate()
            content.repaint()
        }
        layoutTween = tween
        tween.start()
    }

    /**
     * 看板标题行右侧：统一尺寸 +「＋ 添加卡片」（仅编辑态）+「管理卡片 / 完成编辑」。
     *
     * 由外部（主面板的 `.view-head`）挂载，看板自身不再单独占一行工具条，
     * 与 design/plugin-ui.html 的 `.view-head` 一行标题 + 右侧操作保持一致。
     */
    fun headerControls(): JComponent {
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply { isOpaque = false }
        lateinit var sizeButton: JellyButton
        sizeButton = JellyButton(
            "尺寸 ${currentSize().label}",
            JellyButtonVariant.SECONDARY,
            null,
            "统一切换全部卡片的尺寸",
        ) { showSizeMenu(sizeButton) }
        actions.add(sizeButton)

        lateinit var add: JellyButton
        add = JellyButton(
            "＋ 添加卡片",
            JellyButtonVariant.SECONDARY,
            null,
            "把隐藏的卡片加回看板",
        ) { showAddCardMenu(add) }
        add.isVisible = editMode
        addButton = add
        actions.add(add)

        val manage = JellyButton(
            if (editMode) "完成编辑" else "管理卡片",
            JellyButtonVariant.SECONDARY,
            null,
            "进入编辑模式：拖动排序 / 删除 / 添加卡片",
        ) { toggleEditMode() }
        editButton = manage
        actions.add(manage)
        return actions
    }

    /** 尺寸菜单：一处切换，全部卡片跟着换尺寸与高度 */
    private fun showSizeMenu(anchor: JComponent) {
        val menu = JPopupMenu()
        val group = ButtonGroup()
        val current = currentSize()
        for (size in DashboardCardSize.entries) {
            val item = JRadioButtonMenuItem(
                "${size.label}　${size.columns} 格宽 · ${size.heightDp} dp 高",
                size == current,
            )
            item.addActionListener {
                settings.setDashboardCardSize(size.name)
                (anchor as? JellyButton)?.text = "尺寸 ${size.label}"
                AccountEvents.fireDashboardLayoutChanged(this@DashboardPanel)
                rebuildWithTransition()
            }
            group.add(item)
            menu.add(item)
        }
        menu.show(anchor, 0, anchor.height)
    }

    /** 编辑模式下的「添加卡片」：列出所有已隐藏卡片，末项可一键恢复默认布局 */
    private fun showAddCardMenu(anchor: JComponent) {
        val menu = JPopupMenu()
        val hidden = settings.hiddenDashboardCards
        val candidates = DashboardCardId.entries.filter { it.name in hidden }
        if (candidates.isEmpty()) {
            menu.add(JMenuItem("全部卡片已在看板中").apply { isEnabled = false })
        } else {
            menu.add(JMenuItem("添加到看板").apply { isEnabled = false })
            for (id in candidates) {
                menu.add(JMenuItem(id.title).apply {
                    addActionListener { addCardWithAnimation(id) }
                })
            }
        }
        menu.addSeparator()
        menu.add(JMenuItem("恢复默认布局").apply {
            addActionListener {
                settings.resetDashboardLayout()
                AccountEvents.fireDashboardLayoutChanged(this@DashboardPanel)
                rebuildWithTransition()
            }
        })
        menu.show(anchor, 0, anchor.height)
    }

    // ---------------------------------------------------------------
    // 1. 关键指标
    // ---------------------------------------------------------------

    private fun kpiRow(
        saved: List<CredentialSnapshot>,
        active: CredentialSnapshot?,
        quotas: Map<String, QuotaSummary>,
        threshold: Int,
    ): JComponent {
        val rows = saved.mapNotNull { s -> quotas[s.accountKey]?.takeIf { it.error == null }?.let { s to it } }
        val size = rows.sumOf { it.second.usableSize }
        val remain = rows.sumOf { it.second.usableRemain }
        val percent = if (size <= 0) 0.0 else remain / size * 100.0
        val unit = rows.firstOrNull()?.second?.unit ?: "credits"

        val expiredTokens = saved.count { it.isRefreshTokenExpired }
        val usableAccounts = saved.count { !it.isRefreshTokenExpired }

        val soon = rows.asSequence()
            .flatMap { it.second.usablePackages.asSequence() }
            .filter { it.remain > 0 && it.expireMillis != Long.MAX_VALUE }
            .filter { it.expireMillis - System.currentTimeMillis() in 0..SEVEN_DAYS_MS }
            .toList()

        val lowAccounts = rows.count { it.second.usableRemainPercent < threshold }

        // 响应式：按可用宽度自动分列，窄工具窗下 4 张卡折成 2×2 而不是被压扁。
        // 最大高度必须跟着当前首选高度走：写死一次会让换行后的卡片被父容器裁掉。
        val row = object : JPanel(
            WrapGridLayout(JBUI.scale(8), JBUI.scale(8), JBUI.scale(KPI_MIN_WIDTH))
        ) {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply { isOpaque = false }
        row.add(
            kpi(
                title = "账号总数",
                value = saved.size.toString(),
                sub = "可用 $usableAccounts · Token 过期 $expiredTokens",
                color = AccountUiColors.accent,
                tip = "点击查看账号卡片视图",
                onClick = onOpenList,
            )
        )
        row.add(
            kpi(
                title = "额度总剩余",
                value = if (rows.isEmpty()) "—" else "${"%.0f".format(percent)}%",
                sub = if (rows.isEmpty()) "尚未查询" else "剩余 ${num(remain)} / ${num(size)} $unit",
                color = if (rows.isEmpty()) AccountUiColors.textFaint else QuotaCardUi.percentColor(percent),
                tip = "点击查看额度明细",
                onClick = onOpenQuotaDetail,
            )
        )
        row.add(
            kpi(
                title = "7 天内到期",
                value = if (rows.isEmpty()) "—" else "${soon.size} 条",
                sub = if (soon.isEmpty()) "近期无到期额度" else "最近 ${QuotaTime.remainingText(soon.minOf { it.expireMillis })}",
                color = if (soon.isEmpty()) AccountUiColors.textMuted else AccountUiColors.warning,
                tip = "点击只查看 7 天内到期的额度（按账号分类）",
                onClick = onOpenExpiringQuota,
            )
        )
        row.add(
            kpi(
                title = "低额度账号",
                value = "${lowAccounts} 个",
                sub = "告警阈值 $threshold%（设置可改）",
                color = if (lowAccounts > 0) AccountUiColors.danger else AccountUiColors.success,
                tip = "点击查看各账号额度",
                onClick = onOpenQuotaDetail,
            )
        )
        row.alignmentX = Component.LEFT_ALIGNMENT
        return row
    }

    private fun kpi(
        title: String,
        value: String,
        sub: String,
        color: Color,
        tip: String,
        onClick: () -> Unit,
    ): JComponent {
        val card = KpiTile(title, value, sub, color)
        // 高度交给 KpiTile 按「标签 / 数值 / 说明」三行内容计算，固定 92dp 会裁掉说明文字
        card.alignmentX = Component.LEFT_ALIGNMENT
        installCardClick(card, tip, onClick)
        return card
    }

    // ---------------------------------------------------------------
    // 2. 今日指标
    // ---------------------------------------------------------------

    /**
     * 顶部 Hero 指标条：今日消耗积分为主视觉，下方并列剩余积分 / 今日对话 / 账号总数。
     *
     * 全界面只保留这一处渐变，用来承担「第一眼看什么」的职责；
     * 其余区块一律用中性卡片，避免到处都是视觉焦点。
     *
     * 组件是常驻实例，这里只喂数据：积分变化由 Hero 内部做滚动过渡
     * （100 → 120），并在右上角闪一次增量徽标。
     */
    private fun heroStrip(saved: List<CredentialSnapshot>, quotas: Map<String, QuotaSummary>): JComponent {
        val usage = UsageTodayTracker.state()
        val history = HistoryActivity.state()
        val today = LocalDate.now()
        val todayConversations = history.conversations.filter { sameDay(it.lastMessageAt, today) }
        val conversationAccounts = todayConversations.map { it.sourceUid }.distinct().size

        val remain = quotas.values
            .filter { it.error == null }
            .sumOf { it.usableRemain }
        val quotaAccounts = quotas.values.count { it.error == null }

        // Hero 是常驻实例：先从上一次所在的容器里摘出来，避免重复挂载
        (usageHero.parent as? Container)?.remove(usageHero)

        usageHero.setValue(
            when {
                usage.loaded -> formatCredit(usage.totalCredit)
                usage.loading -> "…"
                else -> "—"
            }
        )
        usageHero.setMetrics(
            listOf(
                "剩余积分" to if (quotaAccounts > 0) num(remain) else "—",
                "今日对话" to when {
                    history.loaded -> "${todayConversations.size} 个 · $conversationAccounts 账号"
                    history.loading -> "扫描中…"
                    else -> "—"
                },
                "账号总数" to "${saved.size} 个",
            )
        )
        usageHero.setUpdatedAt(
            if (usage.fetchedAt > 0L) "更新于 ${hmFmt.format(Instant.ofEpochMilli(usage.fetchedAt))}" else null
        )

        usageHero.alignmentX = Component.LEFT_ALIGNMENT
        return usageHero
    }

    /**
     * 今日各模型消耗：按模型分组统计今天各自的积分消耗，按积分降序排列。
     *
     * 数据取自 UsageTodayTracker.State.byModel()（仅基于已加载的今日记录），
     * 行数按固定容量裁剪，超出部分折成提示，避免把整屏卡片一起撑高。
     */
    private fun todayModelUsageSection(): JComponent {
        val st = UsageTodayTracker.state()
        val models = st.byModel()
        val section = FullWidthColumn()
        section.add(
            SectionHeader(
                "今日各模型消耗",
                subtitle = if (st.loaded && models.isNotEmpty()) "共 ${models.size} 个模型 · 按积分降序" else null,
            )
        )
        section.add(Box.createVerticalStrut(JBUI.scale(CardMetrics.rowGap)))
        section.add(
            CardList(
                rows = models.map { m ->
                    val name = m.model.ifBlank { "未知模型" }
                    CardRow(
                        name = name,
                        value = formatCredit(m.credit),
                        tag = AccountUiKit.statusTag("${m.count} 次", AccountUiColors.textMuted),
                        valueTone = AccountUiColors.textSecondary,
                        valueWidthDp = 72,
                        tooltip = "$name · 今日消耗 ${formatCredit(m.credit)} credits · ${m.count} 次请求",
                    )
                },
                capacity = MODEL_USAGE_ROWS,
                emptyText = when {
                    st.loading -> "正在读取今日消耗…"
                    st.loaded -> "今天还没有消耗记录"
                    else -> "点上方数值查看消耗明细"
                },
                overflowText = { "另有 $it 个模型未展示" },
            )
        )
        return section
    }

    // ---------------------------------------------------------------
    // 2.5 今日签到
    // ---------------------------------------------------------------

    /** 今日签到：小卡形态，只留关键数字与最近几个账号 */
    private fun fillCheckinCard(card: DashboardCard) {
        card.actions.addAction("一键签到", "对全部账号执行今日签到", primary = true, onClick = onCheckin)
        card.actions.addAction("刷新", "重新读取本地今日签到记录", ghost = true) {
            CheckinTracker.ensureFresh(force = true) { update() }
        }

        if (!settings.checkinEnabled) {
            card.body.add(CardNotice("签到已在设置中关闭（实验特性：接口非官方，可在设置页调整路径或关闭）。"))
            return
        }

        val st = CheckinTracker.state()
        if (st.running) card.body.add(CardNotice("正在执行签到…"))
        if (!st.loaded) {
            card.body.add(CardNotice("暂无今日签到记录，点「一键签到」执行。"))
            return
        }
        if (st.items.isEmpty()) {
            card.body.add(CardNotice("账号库为空，请先导入账号。"))
            return
        }

        val allDone = st.checkedCount == st.items.size
        card.body.add(
            CardHeadline(
                value = "${st.checkedCount}/${st.items.size}",
                unit = "个账号已签到",
                tone = if (allDone) AccountUiColors.success else AccountUiColors.textPrimary,
                trailing = null,
            )
        )
        card.body.add(Box.createVerticalStrut(JBUI.scale(CardMetrics.blockGap)))
        card.body.add(
            CardList(
                rows = st.items.map { checkinRow(it) },
                capacity = card.listRows,
                overflowText = { "另有 $it 个账号未展示" },
            )
        )
        if (st.failedCount > 0) {
            card.body.add(Box.createVerticalStrut(JBUI.scale(CardMetrics.rowGap)))
            // 把首个具体原因露出来：token 失效 / HTTP 错误 / 接口业务码可直接区分
            val first = st.items.firstNotNullOfOrNull { it.failed }
            card.body.add(
                CardNotice(
                    "${st.failedCount} 个账号签到失败${first?.let { "：$it" } ?: ""}",
                    AccountUiColors.danger,
                )
            )
        }
    }

    private fun checkinRow(item: CheckinTracker.AccountCheckin): JComponent {
        val (statusText, statusColor) = when {
            item.failed != null -> "签到失败" to AccountUiColors.danger
            item.result?.already == true -> "今日已签到" to AccountUiColors.success
            item.result?.success == true -> "签到成功" to AccountUiColors.success
            item.checked -> "今日已签到" to AccountUiColors.success
            else -> "未签到" to AccountUiColors.warning
        }

        val failed = item.failed
        return CardRow(
            name = item.displayName,
            value = if (failed != null) "—" else "",
            tag = AccountUiKit.statusTag(statusText, statusColor),
            valueTone = if (failed != null) AccountUiColors.danger else AccountUiColors.textMuted,
            tooltip = buildString {
                append(item.displayName).append(" · ").append(statusText)
                if (failed != null) {
                    append(" · ").append(failed)
                } else {
                    item.result?.message?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                }
            },
        )
    }

    // ---------------------------------------------------------------
    // 2.5 成长计划与活跃状态
    // ---------------------------------------------------------------

    /**
     * 成长计划与活跃状态：今日活跃、连登天数、Buddy 旅行状态。
     *
     * 数据来自 [GrowthTracker.details] 的独立缓存（10 分钟），与「成长中心」任务卡分开查，
     * 避免三个接口的耗时被算进任务卡的刷新里。
     */
    private fun fillGrowthPlanCard(card: DashboardCard) {
        card.actions.addAction("刷新", "重新查询今日活跃 / 连登 / 旅行状态", primary = true) {
            GrowthTracker.ensureFreshDetails(force = true) { update() }
        }

        if (!settings.growthEnabled) {
            card.body.add(CardNotice("成长中心任务已在设置中关闭（实验特性：接口非官方，可在设置页调整路径或关闭）。"))
            return
        }

        val st = GrowthTracker.details()
        if (!st.loaded) {
            card.body.add(
                CardNotice(
                    if (st.loading) "正在查询今日活跃与连登状态…" else "尚未查询，点「刷新」读取成长计划与活跃状态。"
                )
            )
            return
        }
        if (st.items.isEmpty()) {
            card.body.add(CardNotice("账号库为空，请先导入账号。"))
            return
        }

        card.body.add(
            CardHeadline(
                value = "${st.activeCount}/${st.total}",
                unit = "个账号今日已活跃",
                tone = if (st.activeCount == st.total) AccountUiColors.success else AccountUiColors.textPrimary,
                trailing = if (st.maxStreakDays > 0) {
                    AccountUiKit.fieldPill("最高连登 ${st.maxStreakDays} 天", AccountUiColors.brass)
                } else {
                    null
                },
            )
        )
        card.body.add(Box.createVerticalStrut(JBUI.scale(CardMetrics.blockGap)))
        card.body.add(
            CardList(
                rows = st.items.map { growthPlanRow(it) },
                capacity = card.listRows,
                overflowText = { "另有 $it 个账号未展示" },
            )
        )
        if (st.failedCount > 0) {
            card.body.add(Box.createVerticalStrut(JBUI.scale(CardMetrics.rowGap)))
            card.body.add(
                CardNotice("${st.failedCount} 个账号查询失败：多为 token 失效或接口不可用", AccountUiColors.danger)
            )
        }
    }

    /** 一个账号一行：状态看今日活跃，数值优先给旅行的回来时间，没有旅行再给连登天数 */
    private fun growthPlanRow(item: GrowthTracker.AccountDetail): JComponent {
        val active = item.active
        val (statusText, statusColor) = when {
            item.error != null -> "查询失败" to AccountUiColors.danger
            active?.ok != true -> "活跃未知" to AccountUiColors.textMuted
            active.active -> "今日已活跃" to AccountUiColors.success
            else -> "今日未活跃" to AccountUiColors.warning
        }
        val streak = item.streak?.takeIf { it.ok }
        val travel = item.travel?.takeIf { it.ok }
        val value = when {
            travel != null -> travel.etaText() ?: travel.stateLabel
            streak != null -> "连登 ${streak.days} 天"
            else -> "—"
        }
        return CardRow(
            name = item.displayName,
            value = value,
            tag = AccountUiKit.statusTag(statusText, statusColor),
            valueTone = when {
                item.error != null -> AccountUiColors.danger
                travel?.state == "arrived" -> AccountUiColors.warning
                else -> AccountUiColors.textMuted
            },
            valueWidthDp = 132,
            tooltip = buildString {
                append(item.displayName).append(" · ").append(statusText)
                item.error?.let { append(" · ").append(it) }
                streak?.let { s ->
                    append(" · 连登 ").append(s.days).append(" 天，补登卡 ").append(s.makeupCards).append(" 张")
                    if (s.nextTierRemaining > 0) append("，下一档还差 ").append(s.nextTierRemaining).append(" 天")
                }
                travel?.let { t ->
                    append(" · Buddy ").append(t.stateLabel)
                    t.etaText()?.let { append("（").append(it).append("）") }
                    if (t.rewardCredit > 0) append("，奖励 ").append(num(t.rewardCredit)).append(" credits")
                }
            },
        )
    }

    // ---------------------------------------------------------------
    // 2.55 成长中心任务
    // ---------------------------------------------------------------

    private fun fillGrowthCard(card: DashboardCard) {
        card.actions.addAction("一键完成", "接取未接取任务 + 领取奖励", primary = true, onClick = onGrowth)
        card.actions.addAction("旅行巡检", "执行旅行巡检任务", onClick = onGrowthTravel)
        card.actions.addAction("活跃上报", "上报客户端活跃事件", onClick = onGrowthActivity)
        card.actions.addAction("全部保活", "对所有账号做保活", onClick = onGrowthKeepalive)
        card.actions.addAction("打开窗口", "打开成长计划窗口", ghost = true, onClick = onOpenGrowthCenter)
        card.actions.addAction("刷新", "重新查询成长任务", ghost = true) {
            GrowthTracker.ensureFresh(force = true) { update() }
        }

        if (!settings.growthEnabled) {
            card.body.add(CardNotice("成长中心任务已在设置中关闭（实验特性：接口非官方，可在设置页调整路径或关闭）。"))
            return
        }

        val st = GrowthTracker.state()
        if (st.running) card.body.add(CardNotice("正在执行成长任务…"))
        if (!st.loaded) {
            card.body.add(CardNotice("尚未查询成长任务，点「刷新」获取。"))
            return
        }
        if (st.items.isEmpty()) {
            card.body.add(CardNotice("账号库为空，请先导入账号。"))
            return
        }

        card.body.add(
            CardHeadline(
                value = "${st.claimedTasks}/${st.totalTasks}",
                unit = "个任务已完成",
                trailing = if (st.claimableTasks > 0) {
                    AccountUiKit.fieldPill("待领取 ${st.claimableTasks} 个", AccountUiColors.warning)
                } else {
                    null
                },
            )
        )
        card.body.add(Box.createVerticalStrut(JBUI.scale(CardMetrics.blockGap)))
        card.body.add(
            CardList(
                rows = st.items.map { growthRow(it) },
                capacity = card.listRows,
                overflowText = { "另有 $it 个账号未展示" },
            )
        )
        if (st.failedCount > 0) {
            card.body.add(Box.createVerticalStrut(JBUI.scale(CardMetrics.rowGap)))
            card.body.add(
                CardNotice("${st.failedCount} 个账号失败：多为 token 失效或接口不可用", AccountUiColors.danger)
            )
        }
    }

    private fun growthRow(item: GrowthTracker.AccountGrowth): JComponent {
        val (statusText, statusColor) = when {
            item.error != null -> "查询失败" to AccountUiColors.danger
            item.claimable > 0 -> "可领奖 ${item.claimable} 个" to AccountUiColors.warning
            item.doing > 0 -> "进行中 ${item.doing} 个" to AccountUiColors.textSecondary
            item.total > 0 && item.claimed == item.total -> "全部完成" to AccountUiColors.success
            else -> "待接取 ${item.pending} 个" to AccountUiColors.textMuted
        }

        val doing = item.tasks.firstOrNull {
            it.state == GrowthService.TaskState.ACCEPTED || it.state == GrowthService.TaskState.IN_PROGRESS
        }
        return CardRow(
            name = item.displayName,
            value = when {
                item.error != null -> "查询失败"
                item.claimable > 0 -> "待领 ${num(item.claimableCredit)}"
                doing != null -> doing.progressText.ifBlank { doing.title }.take(12)
                else -> ""
            },
            tag = AccountUiKit.statusTag(statusText, statusColor),
            valueTone = when {
                item.error != null -> AccountUiColors.danger
                item.claimable > 0 -> AccountUiColors.warning
                else -> AccountUiColors.textMuted
            },
            tooltip = buildString {
                append(item.displayName).append(" · ").append(statusText)
                item.error?.let { append(" · ").append(it) }
                doing?.let { append(" · ").append(it.title) }
            },
        )
    }

    // ---------------------------------------------------------------
    // 2.57 增强助手：观察通道 / 代点统计 / 联动开关
    // ---------------------------------------------------------------

    /**
     * 增强助手：页面观察通道、代点 / 续跑 / 迁移统计、提醒与代点开关。
     *
     * 原先独立的「CodeBuddy 增强助手」窗口已删除，功能和开关都收在这一张卡里；
     * 开关直接写 [PetSettings] 并调 [PetService.applySettings] 立即生效，不再在桌宠设置页重复出现。
     */
    private fun fillAssistantCard(card: DashboardCard) {
        card.actions.addAction("刷新", "重新读取观察通道与代点统计", primary = true) { update() }
        card.actions.addAction("快捷短语", "管理常用短语并发送到 CodeBuddy 输入框", ghost = true) {
            onOpenPhrases()
        }

        val recent = CbEventCenter.recentEvents()
        val approvals = recent.count { it.type == "approval" }
        val autoApprovals = recent.count { it.type == "approval" && it.auto }
        val interrupts = recent.count { it.type == "interrupted" }
        val autoContinues = recent.count { it.type == "interrupted" && it.auto }
        val migration = recent.firstOrNull { it.type == "history" }
        val connected = CbWebObserver.isConnected()

        card.body.add(
            CardHeadline(
                value = if (connected) "已连接" else "未连接",
                unit = "页面观察通道",
                tone = if (connected) AccountUiColors.success else AccountUiColors.warning,
                trailing = AccountUiKit.statusTag("代点 $autoApprovals 次", AccountUiColors.textMuted),
            )
        )
        card.body.add(Box.createVerticalStrut(JBUI.scale(CardMetrics.rowGap)))
        card.body.add(
            CardNotice(
                "权限弹窗 $approvals 次（代点 $autoApprovals）· 中断 $interrupts 次（续跑 $autoContinues）" +
                    (migration?.let { " · 最近迁移 ${it.timeText}" } ?: "")
            )
        )
        card.body.add(Box.createVerticalStrut(JBUI.scale(CardMetrics.blockGap)))
        card.body.add(
            CardList(
                rows = petSwitchRows(),
                capacity = card.listRows,
                overflowText = { "另有 $it 项开关未展示（切到 2×4 档可看全）" },
            )
        )
    }

    /** 联动开关：每行一项，点击即写设置并立即生效 */
    private fun petSwitchRows(): List<JComponent> {
        val pet = PetSettings.getInstance()
        return listOf(
            switchRow("权限提醒", pet.cbApproveNoticeEnabled) { pet.cbApproveNoticeEnabled = it },
            switchRow("进度提醒", pet.cbProgressNoticeEnabled) { pet.cbProgressNoticeEnabled = it },
            switchRow("超时代点（${pet.cbAutoApproveMinutes} 分钟）", pet.cbAutoApproveEnabled) {
                pet.cbAutoApproveEnabled = it
            },
            switchRow("弹窗免打扰", pet.cbNoDisturbEnabled) { pet.cbNoDisturbEnabled = it },
            switchRow("危险命令代点", pet.cbNoDisturbDangerEnabled) { pet.cbNoDisturbDangerEnabled = it },
            switchRow("中断自动继续", pet.cbAutoContinueEnabled) { pet.cbAutoContinueEnabled = it },
            switchRow("限流提醒", pet.cbLimitNoticeEnabled) { pet.cbLimitNoticeEnabled = it },
            switchRow("成长播报", pet.cbGrowthNoticeEnabled) { pet.cbGrowthNoticeEnabled = it },
            switchRow("迁移反馈", pet.cbHistoryNoticeEnabled) { pet.cbHistoryNoticeEnabled = it },
        )
    }

    private fun switchRow(label: String, checked: Boolean, toggle: (Boolean) -> Unit): JComponent {
        val row = JPanel(BorderLayout(JBUI.scale(6), 0))
        row.isOpaque = false
        row.alignmentX = Component.LEFT_ALIGNMENT
        row.preferredSize = Dimension(0, JBUI.scale(CardMetrics.rowHeight))
        row.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(CardMetrics.rowHeight))
        row.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        row.toolTipText = "$label：点击切换（立即生效）"
        row.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                toggle(!checked)
                PetService.getInstance().applySettings()
                update()
            }
        })
        row.add(
            JLabel(label).apply {
                font = baseLabelFont().deriveFont(Font.PLAIN, AccountUiType.label)
                foreground = AccountUiColors.textPrimary
            },
            BorderLayout.CENTER,
        )
        row.add(
            if (checked) {
                AccountUiKit.fieldPill("已开", AccountUiColors.success)
            } else {
                AccountUiKit.fieldPill("已关", AccountUiColors.textMuted)
            },
            BorderLayout.EAST,
        )
        return row
    }

    // ---------------------------------------------------------------
    // 2.58 事件记录
    // ---------------------------------------------------------------

    /** 事件记录：观察通道与日志汇合后的最近事件，含「是否由桌宠代劳」 */
    private fun fillEventsCard(card: DashboardCard) {
        card.actions.addAction("刷新", "重新读取最近事件", primary = true) { update() }
        card.actions.addAction("清空记录", "清空事件记录", ghost = true) {
            CbEventCenter.clearEvents()
            update()
        }

        val events = CbEventCenter.recentEvents()
        card.body.add(
            CardNotice(
                if (events.isEmpty()) {
                    "还没有观察到权限弹窗 / 限流 / 中断事件。"
                } else {
                    "最近 ${events.size} 条 · 时间 / 类型 / 标题"
                }
            )
        )
        card.body.add(Box.createVerticalStrut(JBUI.scale(CardMetrics.rowGap)))
        card.body.add(
            CardList(
                rows = events.map { eventRow(it) },
                capacity = card.listRows,
                overflowText = { "另有 $it 条事件未展示" },
            )
        )
    }

    private fun eventRow(e: CbEventCenter.EventRecord): JComponent = CardRow(
        name = "${e.timeText} ${e.title}",
        value = "",
        valueWidthDp = 0,
        tag = AccountUiKit.statusTag(
            if (e.auto) "${e.typeLabel} · 代劳" else e.typeLabel,
            if (e.auto) AccountUiColors.brass else AccountUiColors.textMuted,
        ),
        tooltip = "${e.timeText} ${e.typeLabel} ${e.title}：${e.detail}",
    )

    // ---------------------------------------------------------------
    // 2.59 对话活跃
    // ---------------------------------------------------------------

    /** 对话活跃：最近完成的对话（项目 + 标题 + 摘要），数据来自 [ActivityFeed] 的本地缓存 */
    private fun fillActivityCard(card: DashboardCard) {
        card.actions.addAction("刷新", "重新扫描最近的对话", primary = true) {
            loadActivityIfNeeded(force = true)
        }
        card.actions.addAction("打开历史", "打开历史对话窗口", ghost = true) { onOpenHistory() }

        if (activityItems.isEmpty()) {
            card.body.add(
                CardNotice(
                    if (activityLoading) "正在扫描最近的对话…" else "暂无对话记录：完成一轮对话后会自动出现在这里。"
                )
            )
            return
        }
        card.body.add(CardNotice("最近 ${activityItems.size} 个对话 · 摘要取「主人」之后的正文"))
        card.body.add(Box.createVerticalStrut(JBUI.scale(CardMetrics.rowGap)))
        card.body.add(
            CardList(
                rows = activityItems.map { activityRow(it) },
                capacity = card.listRows,
                overflowText = { "另有 $it 个对话未展示" },
            )
        )
    }

    private fun activityRow(item: ActivityFeed.Item): JComponent = CardRow(
        name = item.excerpt.ifBlank { item.title },
        value = "",
        valueWidthDp = 0,
        tag = AccountUiKit.fieldPill("${item.time.take(5)} ${item.project}", AccountUiColors.textMuted),
        tooltip = "${item.time} ${item.project} · ${item.title}：${item.excerpt}",
    )

    // ---------------------------------------------------------------
    // 2.6 智能调度
    // ---------------------------------------------------------------

    /** 智能调度：状态 + 阈值 / 冷却 / 上次切换三行，说明放最后一行弱化字 */
    private fun fillSchedulerCard(card: DashboardCard) {
        card.actions.addAction("去设置", "打开设置页调整自动调度") { onOpenSettings() }

        val st = AutoRefreshService.getInstance().autoSwitchState()
        val headline = when {
            !st.enabled -> "未开启"
            !st.hotSwitchAvailable -> "已开启 · 热切换不可用"
            else -> "已开启"
        }
        val color = when {
            !st.enabled -> AccountUiColors.textMuted
            !st.hotSwitchAvailable -> AccountUiColors.warning
            else -> AccountUiColors.success
        }
        card.body.add(
            CardHeadline(
                value = headline,
                tone = color,
                valueFont = baseLabelFont().deriveFont(Font.BOLD, AccountUiType.title),
                trailing = AccountUiKit.statusTag(if (st.enabled) "自动调度" else "已关闭", color),
            )
        )
        card.body.add(Box.createVerticalStrut(JBUI.scale(CardMetrics.blockGap)))

        val rows = mutableListOf<JComponent>()
        rows += AccountUiKit.fieldLine(
            "触发阈值",
            AccountUiKit.valueText("剩余 ${st.thresholdPercent}%", AccountUiColors.textSecondary, mono = false),
        )
        if (st.enabled) {
            rows += AccountUiKit.fieldLine(
                "热切换",
                AccountUiKit.valueText(
                    if (st.hotSwitchAvailable) "可用" else "不可用",
                    if (st.hotSwitchAvailable) AccountUiColors.success else AccountUiColors.warning,
                    mono = false,
                ),
            )
            // 共享额度池里离到期最近的一份：说明下一次请求该吃哪个账号的哪份额度
            val next = QuotaScheduler.mergeShares(
                QuotaScheduler.pool(library.listSaved(), library.quotaCache.loadAll())
            ).firstOrNull()
            if (next != null) {
                rows += AccountUiKit.fieldLine(
                    "下一份额度",
                    AccountUiKit.valueText(
                        "${next.snapshot.displayName} · ${QuotaTime.remainingText(next.pkg.expireMillis)}",
                        AccountUiColors.warning,
                        mono = false,
                    ),
                )
            }
        }
        if (st.coolingDown) {
            rows += AccountUiKit.fieldLine(
                "冷却中",
                AccountUiKit.valueText(
                    "约 ${(st.remainingCooldownMs / 60_000L).toInt() + 1} 分钟",
                    AccountUiColors.textSecondary,
                    mono = false,
                ),
            )
        }
        if (st.lastSwitchAt > 0) {
            rows += AccountUiKit.fieldLine(
                "上次切换",
                AccountUiKit.valueText(timeFmt.format(Instant.ofEpochMilli(st.lastSwitchAt)), AccountUiColors.textMuted),
            )
        }
        card.body.add(CardList(rows, card.listRows, overflowText = { "另有 $it 项" }))

        card.body.add(Box.createVerticalStrut(JBUI.scale(CardMetrics.rowGap)))
        card.body.add(
            CardNotice(
                when {
                    !st.enabled -> "额度低于 ${st.thresholdPercent}% 时不会自动切号，去设置里打开「自动调度」。"
                    !st.hotSwitchAvailable -> "热切换不可用（需 CodeBuddy 插件支持），触发时只会提醒手动切换。"
                    else -> "由后台「自动刷新」任务触发（默认 15 分钟）。"
                }
            )
        )
    }

    // ---------------------------------------------------------------
    // 3. 消耗明细卡片
    // ---------------------------------------------------------------

    private fun fillUsageCard(card: DashboardCard, saved: List<CredentialSnapshot>) {
        card.actions.addAction("刷新", "重新读取今日消耗", ghost = true) {
            UsageTodayTracker.ensureFresh(force = true) { update() }
        }
        card.actions.addAction("查看全部", "打开消耗明细窗口") { onOpenUsageDetail() }

        val st = UsageTodayTracker.state()
        val labels = saved.associate { it.accountKey to it.displayName }

        card.body.add(
            CardHeadline(
                value = if (st.loaded) formatCredit(st.totalCredit) else "—",
                unit = "credits",
                tone = if (st.loaded) AccountUiColors.textPrimary else AccountUiColors.textMuted,
                trailing = if (st.loaded) {
                    AccountUiKit.statusTag("${st.totalRequests} 次请求", AccountUiColors.textMuted)
                } else {
                    null
                },
            )
        )

        val models = st.byModel().take(4)
        if (models.isNotEmpty()) {
            card.body.add(Box.createVerticalStrut(JBUI.scale(CardMetrics.rowGap)))
            card.body.add(
                CardNotice(
                    "模型 " + models.joinToString(" · ") { m ->
                        "${m.model.ifBlank { "-" }} ${formatCredit(m.credit)}"
                    }
                )
            )
        }
        if (st.loaded && st.error != null) {
            card.body.add(CardNotice("部分账号读取失败：${st.error}", AccountUiColors.danger))
        }

        card.body.add(Box.createVerticalStrut(JBUI.scale(CardMetrics.blockGap)))
        card.body.add(
            CardList(
                rows = st.latestWithAccount(24).map { (accountKey, record) ->
                    usageRow(labels[accountKey] ?: accountKey.take(8), record)
                },
                capacity = card.listRows,
                emptyText = when {
                    st.loading -> "正在读取…"
                    st.loaded -> "今天还没有消耗记录"
                    else -> "点「刷新」读取今天各账号的消耗明细"
                },
                overflowText = { "另有 $it 条记录" },
            )
        )
        if (st.truncated) {
            card.body.add(CardNotice("单个账号超过 ${UsageTodayTracker.MAX_ROWS_SHOWN} 条，仅统计最近一页"))
        }
        installCardClick(card, "点击查看完整消耗明细", onOpenUsageDetail)
    }

    // ---------------------------------------------------------------
    // 3.5 模型限额卡片
    // ---------------------------------------------------------------

    private fun fillModelQuotaCard(card: DashboardCard) {
        card.actions.addAction("刷新", "统计本周期目标模型消耗", ghost = true) {
            ModelQuotaTracker.ensureFresh(force = true) { update() }
        }
        card.actions.addAction("消耗明细") { onOpenUsageDetail() }

        val st = ModelQuotaTracker.state()
        if (!st.enabled) {
            card.body.add(CardNotice("模型限额监控未启用，可在设置页开启（可同时监控多个模型）。"))
            return
        }
        if (st.rules.isEmpty()) {
            card.body.add(CardNotice("未配置限额规则，可在设置页添加「模型关键字 + 限额积分」。"))
            return
        }

        // 一条规则约占 3 行：名称行 + 进度条 + 间距，按档位容量裁剪规则条数
        card.body.add(
            CardList(
                rows = st.rules.map { modelQuotaBlock(it, st.periodLabel) },
                capacity = (card.listRows / 3).coerceAtLeast(1),
                overflowText = { "另有 $it 条限额规则未展示" },
            )
        )

        if (st.error != null) {
            card.body.add(Box.createVerticalStrut(JBUI.scale(CardMetrics.rowGap)))
            card.body.add(CardNotice("部分账号读取失败：${st.error}", AccountUiColors.danger))
        }
        if (st.truncated) card.body.add(CardNotice("部分账号记录超过单次拉取上限，统计可能偏低。"))
        if (st.loading) {
            card.body.add(CardNotice("正在统计本周期消耗…"))
        } else if (!st.loaded) {
            card.body.add(CardNotice("点「刷新」统计本周期内目标模型的积分消耗。"))
        }

        installCardClick(card, "点击查看完整消耗明细", onOpenUsageDetail)
    }

    /** 一条限额规则：名称 + 已用 / 限额 + 状态，下面压一条 6px 进度条 */
    private fun modelQuotaBlock(rule: ModelQuotaRuleState, periodLabel: String): JComponent {
        val modelName = rule.keyword.ifBlank { "目标模型" }
        val (statusText, statusColor) = when {
            rule.limit <= 0 -> "未设置限额" to AccountUiColors.textFaint
            rule.exceed -> "已达限额" to AccountUiColors.danger
            rule.remainPercent <= 20 -> "接近限额" to AccountUiColors.warning
            else -> "正常" to AccountUiColors.success
        }

        val block = FullWidthColumn()
        block.add(
            CardRow(
                name = modelName,
                value = "${formatCredit(rule.used)} / ${formatCredit(rule.limit)}",
                tag = AccountUiKit.statusTag(statusText, statusColor),
                valueTone = when {
                    rule.exceed -> AccountUiColors.danger
                    rule.remainPercent <= 20 -> AccountUiColors.warning
                    else -> AccountUiColors.textSecondary
                },
                tooltip = buildString {
                    append(modelName).append(" ").append(periodLabel)
                    append("已用 ").append(formatCredit(rule.used))
                    append(" / ").append(formatCredit(rule.limit)).append(" credits")
                    append(" · 剩余 ").append(formatCredit(rule.remain))
                    append("（").append("%.0f".format(rule.remainPercent)).append("%）")
                    append(" · 请求 ").append(rule.requests).append(" 次")
                    if (rule.matchedModels.isNotEmpty()) {
                        append(" · 命中 ").append(rule.matchedModels.joinToString("、"))
                    }
                },
            )
        )
        block.add(Box.createVerticalStrut(JBUI.scale(AccountUiSpacing.xs)))
        block.add(QuotaBar(rule.remainPercent).apply { alignmentX = Component.LEFT_ALIGNMENT })

        val exceeded = rule.exceededAccounts
        if (exceeded.isNotEmpty()) {
            block.add(Box.createVerticalStrut(JBUI.scale(AccountUiSpacing.xxs)))
            block.add(
                CardNotice(
                    "${exceeded.size} 个账号已超限：${exceeded.take(3).joinToString("、") { it.accountName }}",
                    AccountUiColors.danger,
                )
            )
        }
        return block
    }

    // ---------------------------------------------------------------
    // 3.6 模型频率限制与预计解封时间
    // ---------------------------------------------------------------

    /**
     * 模型频率限制：只读 [CbEventCenter] 的本机限流记录，不发请求。
     *
     * 剩余时间是动态的，卡片挂一个 30 秒的 ticker 只改数值列，不重建卡片，
     * 避免打断看板其它卡的动画与滚动位置；卡片离开层级时停表。
     */
    private fun fillRateLimitCard(card: DashboardCard) {
        card.actions.addAction("刷新记录", "重新读取本机限流记录", primary = true) { update() }

        // 最近一步的吞吐（日志通道按步写入）：与限流信息同卡展示，回答「当前模型有多快」
        CbEventCenter.recentUsage()?.let { usage ->
            if (System.currentTimeMillis() - usage.at <= USAGE_SAMPLE_TTL_MS) {
                card.body.add(
                    CardNotice(
                        "最近一步输出：${usage.detail()}",
                        AccountUiColors.textMuted,
                    )
                )
                card.body.add(Box.createVerticalStrut(JBUI.scale(CardMetrics.blockGap)))
            }
        }

        // 页面会话状态没接上时，限流只能靠日志推断：模型名与解封时间可能缺失，明确告知
        if (PetSettings.getInstance().cbLimitNoticeEnabled && !CbWebObserver.storeConnected()) {
            card.body.add(
                CardNotice(
                    "未接上 CodeBuddy 会话状态：限流信息只能从日志推断，模型名与解封时间可能缺失",
                    AccountUiColors.warning,
                )
            )
            card.body.add(Box.createVerticalStrut(JBUI.scale(CardMetrics.blockGap)))
        }

        val now = System.currentTimeMillis()
        val limits = CbEventCenter.activeLimits()
            .sortedBy { if (it.resetAt <= 0L) Long.MAX_VALUE else it.resetAt }
        if (limits.isEmpty()) {
            card.body.add(
                CardNotice("暂无模型频率限制记录：命中限流时会在这里显示模型、限制类型与预计解封时间。")
            )
            return
        }

        val blocked = limits.count { it.resetAt <= 0L || it.resetAt > now }
        val nearest = limits.map { it.resetAt }.filter { it > 0L }.minOrNull()
        card.body.add(
            CardHeadline(
                value = blocked.toString(),
                unit = if (blocked > 0) "个模型受限" else "个模型受限（已到解封时间）",
                tone = if (blocked > 0) AccountUiColors.danger else AccountUiColors.success,
                trailing = nearest?.let {
                    AccountUiKit.fieldPill(
                        "最近解封 ${hmFmt.format(Instant.ofEpochMilli(it))}",
                        if (blocked > 0) AccountUiColors.warning else AccountUiColors.textMuted,
                    )
                },
            )
        )
        card.body.add(Box.createVerticalStrut(JBUI.scale(CardMetrics.blockGap)))

        val rows = limits.map { limit -> limit to rateLimitRow(limit) }
        card.body.add(
            CardList(
                rows = rows.map { it.second },
                capacity = card.listRows,
                overflowText = { "另有 $it 条限制记录未展示" },
            )
        )

        val ticker = Timer(30_000) tick@{
            // 卡片已被刷新替换时停表，避免旧卡片上的定时器继续空转
            if (!card.isShowing) {
                (it.source as? Timer)?.stop()
                return@tick
            }
            for ((limit, row) in rows) row.setValue(limit.remaining(), rateLimitTone(limit))
        }.apply { isRepeats = true }
        card.addHierarchyListener { e ->
            if ((e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong()) == 0L) return@addHierarchyListener
            if (card.isShowing) ticker.start() else ticker.stop()
        }
    }

    private fun rateLimitRow(limit: CbEventCenter.ModelLimit): CardRow = CardRow(
        name = limit.model,
        value = limit.remaining(),
        tag = AccountUiKit.statusTag(limit.kind, AccountUiColors.danger),
        valueTone = rateLimitTone(limit),
        tooltip = limit.detail(),
        valueWidthDp = 132,
    )

    /** 已过解封时间的记录转灰，仍在限制中的留朱红 */
    private fun rateLimitTone(limit: CbEventCenter.ModelLimit): Color =
        if (limit.resetAt in 1..System.currentTimeMillis()) AccountUiColors.textMuted else AccountUiColors.danger

    private fun usageRow(accountLabel: String, record: UsageRecord): JComponent {
        val time = clock(record.requestTime)
        val model = record.model.ifBlank { "-" }
        return CardRow(
            name = "$time · $accountLabel",
            value = formatCredit(record.credit),
            tag = AccountUiKit.fieldPill(model, AccountUiColors.textSecondary),
            valueTone = AccountUiColors.textSecondary,
            tooltip = "$time · $accountLabel · $model · ${formatCredit(record.credit)} credits",
            valueWidthDp = 72,
        )
    }

    // ---------------------------------------------------------------
    // 4. 账号额度排行
    // ---------------------------------------------------------------

    private fun fillRankingCard(
        card: DashboardCard,
        saved: List<CredentialSnapshot>,
        active: CredentialSnapshot?,
        quotas: Map<String, QuotaSummary>,
    ) {
        card.actions.addAction("额度明细", "打开额度明细窗口") { onOpenQuotaDetail() }
        card.actions.addAction("账号卡片", "切换到账号卡片视图", ghost = true) { onOpenList() }

        card.body.add(CardNotice("按未过期额度的剩余比例排序"))
        card.body.add(Box.createVerticalStrut(JBUI.scale(CardMetrics.rowGap)))

        val ordered = saved
            .map { s -> s to quotas[s.accountKey] }
            .sortedWith(
                compareByDescending<Pair<CredentialSnapshot, QuotaSummary?>> {
                    it.second?.takeIf { q -> q.error == null }?.usableRemainPercent ?: -1.0
                }.thenBy { it.first.displayName }
            )

        card.body.add(
            CardList(
                rows = ordered.map { (s, q) -> rankRow(s, q, s.accountKey == active?.accountKey) },
                capacity = card.listRows,
                overflowText = { "另有 $it 个账号未展示" },
            )
        )
    }

    private fun rankRow(s: CredentialSnapshot, quota: QuotaSummary?, active: Boolean): JComponent {
        val ok = quota?.takeIf { it.error == null }
        val (value, tone) = when {
            quota == null -> "未刷新" to AccountUiColors.textFaint
            quota.error != null -> "查询失败" to AccountUiColors.danger
            ok == null || ok.packages.isEmpty() -> "无套餐" to AccountUiColors.textMuted
            else -> "${"%.0f".format(ok.usableRemainPercent)}%" to QuotaCardUi.percentColor(ok.usableRemainPercent)
        }
        val row = CardRow(
            name = s.displayName,
            value = value,
            tag = if (active) {
                Chip("当前", AccountUiColors.accentFill, AccountUiColors.onAccentFill)
            } else {
                null
            },
            valueTone = tone,
            tooltip = buildString {
                append(s.displayName)
                if (s.domainHost.isNotBlank()) append(" · ").append(s.domainHost)
                if (ok != null && ok.packages.isNotEmpty()) {
                    append(" · 剩余 ").append(num(ok.usableRemain))
                    append(" / ").append(num(ok.usableSize)).append(" ").append(ok.unit)
                } else if (quota?.error != null) {
                    append(" · ").append(quota.error)
                }
            },
            valueWidthDp = 64,
            clickable = true,
        )
        installCardClick(row, "在账号卡片视图中查看 ${s.displayName}", onOpenList)
        return row
    }

    // ---------------------------------------------------------------
    // 5. 到期看板（同类型合并）
    // ---------------------------------------------------------------

    /** 同一套餐类型在多个账号 / 多个细项上的聚合结果 */
    private class ExpiringGroup(val label: String) {
        val accounts = LinkedHashSet<String>()
        var count = 0
        var remain = 0.0
        var size = 0.0
        var unit = ""
        var nearest = Long.MAX_VALUE
    }

    private fun fillExpiringCard(
        card: DashboardCard,
        saved: List<CredentialSnapshot>,
        quotas: Map<String, QuotaSummary>,
    ) {
        card.actions.addAction("额度明细", "打开额度明细窗口") { onOpenQuotaDetail() }

        val now = System.currentTimeMillis()
        val groups = LinkedHashMap<String, ExpiringGroup>()

        for (s in saved) {
            val packages = quotas[s.accountKey]?.takeIf { it.error == null }?.usablePackages ?: continue
            for (pkg in packages) {
                if (pkg.remain <= 0 || pkg.expireMillis == Long.MAX_VALUE) continue
                val left = pkg.expireMillis - now
                if (left < 0 || left > THIRTY_DAYS_MS) continue

                val key = pkg.displayGroup.ifBlank { pkg.name.ifBlank { "其他" } }
                val group = groups.getOrPut(key) { ExpiringGroup(QuotaCardUi.shortName(key)) }
                group.accounts.add(s.accountKey)
                group.count++
                group.remain += pkg.remain
                group.size += pkg.size
                if (group.unit.isBlank()) group.unit = pkg.unit
                if (pkg.expireMillis < group.nearest) group.nearest = pkg.expireMillis
            }
        }

        if (groups.isEmpty()) {
            card.body.add(CardNotice("30 天内没有到期额度。"))
            return
        }

        card.body.add(
            CardList(
                rows = groups.values.sortedBy { it.nearest }.map { expiringRow(it, now) },
                capacity = card.listRows,
                overflowText = { "另有 $it 类未展示" },
            )
        )
    }

    private fun expiringRow(group: ExpiringGroup, now: Long): JComponent {
        val days = ((group.nearest - now) / 86_400_000L).toInt()
        val chipColor = when {
            days <= 1 -> AccountUiColors.danger
            days <= 3 -> AccountUiColors.warning
            else -> AccountUiColors.textMuted
        }
        val row = CardRow(
            name = group.label,
            value = "${num(group.remain)} / ${num(group.size)} ${group.unit}",
            tag = AccountUiKit.fieldPill(if (days <= 0) "<1 天" else "$days 天", chipColor),
            valueTone = AccountUiColors.textSecondary,
            tooltip = "${group.label} · ${group.accounts.size} 个账号 · ${group.count} 条 · " +
                "剩余 ${num(group.remain)} / ${num(group.size)} ${group.unit}",
            valueWidthDp = 112,
            clickable = true,
        )
        installCardClick(row, "在额度明细中查看 ${group.label}", onOpenQuotaDetail)
        return row
    }

    // ---------------------------------------------------------------
    // 6. 数据状态
    // ---------------------------------------------------------------

    /** 数据状态：关键状态逐行排列，按档位容量裁剪，动作固定在底边 */
    private fun fillStatusCard(
        card: DashboardCard,
        saved: List<CredentialSnapshot>,
        active: CredentialSnapshot?,
        quotas: Map<String, QuotaSummary>,
        threshold: Int,
    ) {
        card.actions.addAction("刷新全部额度", "重新查询所有账号额度", primary = true, onClick = onRefreshAll)
        card.actions.addAction("额度明细", "打开额度明细窗口") { onOpenQuotaDetail() }
        card.actions.addAction("消耗明细", "打开消耗明细窗口", ghost = true) { onOpenUsageDetail() }
        card.actions.addAction("用量统计", "打开用量统计（积分 / 调用趋势）", ghost = true) { onOpenUsageStats() }
        card.actions.addAction("历史对话", "打开历史对话窗口", ghost = true) { onOpenHistory() }
        card.actions.addAction("对话日报", "生成对话日报", ghost = true) { onOpenReport() }

        val cached = saved.count { quotas[it.accountKey]?.error == null && quotas[it.accountKey]?.packages?.isNotEmpty() == true }
        val latest = quotas.values.map { it.fetchedAt }.maxOrNull() ?: 0L
        val domains = saved.groupingBy { it.domainHost }.eachCount()
        val domainText = domains.entries.sortedByDescending { it.value }
            .joinToString(" · ") { "${it.key} ${it.value}" }

        // 字段行：标签左、值右，所有行左右边缘对齐
        val rows = mutableListOf<JComponent>()
        fun kv(
            label: String,
            value: String,
            valueColor: Color = AccountUiColors.textSecondary,
            tip: String? = null,
        ) {
            val line = AccountUiKit.valueText(value, valueColor, mono = false)
            if (tip != null) line.toolTipText = tip
            rows += AccountUiKit.fieldLine(label, line)
        }

        kv("当前账号", active?.let { "${it.displayName}（${it.domainHost}）" } ?: "未检测到")
        kv("最近刷新", if (latest <= 0) "尚未刷新" else timeFmt.format(Instant.ofEpochMilli(latest)))
        kv("额度缓存", "$cached / ${saved.size} 个")
        kv("今日消耗", usageStatusText(), AccountUiColors.accent)
        kv("告警阈值", "$threshold%", tip = "低额度提醒阈值，可在设置中调整")
        kv("认证域", domainText, tip = domainText)

        card.body.add(CardList(rows, card.listRows, overflowText = { "另有 $it 项" }))
    }

    private fun usageStatusText(): String {
        val st = UsageTodayTracker.state()
        return when {
            st.loaded && st.error != null ->
                "合计 ${formatCredit(st.totalCredit)} credits（${st.accountCount} 个账号，部分失败）"
            st.loaded -> "合计 ${formatCredit(st.totalCredit)} credits · ${st.totalRequests} 次请求"
            st.loading -> "正在读取…"
            else -> "尚未读取"
        }
    }

    // ---------------------------------------------------------------
    // 通用
    // ---------------------------------------------------------------

    /** 常驻组件重建前先从旧父容器摘掉，避免仍挂在上一轮的入场包装里 */
    private fun <T : Component> detach(component: T): T {
        (component.parent as? Container)?.remove(component)
        return component
    }

    /**
     * 整块卡片可点击：把点击 / 悬停装到卡片及其所有子组件上，
     * 否则点在 JLabel 上不会冒泡到卡片，看起来像「点不动」。
     */
    private fun installCardClick(
        card: JComponent,
        tip: String,
        action: () -> Unit,
        recursive: Boolean = true,
    ) {
        card.toolTipText = card.toolTipText ?: tip
        val hand = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val rounded = card as? RoundedCard
        val hero = card as? HeroCard
        rounded?.let {
            if (it.hoverFill == null) it.hoverFill = AccountUiColors.cardHoverBg
            // 整块可点击才给按压回弹，纯展示卡片不响应
            it.pressFeedback = true
        }

        fun hover(value: Boolean) {
            rounded?.setHovering(value)
            hero?.setHovering(value)
        }

        fun walk(component: Component) {
            // 按钮 / 链接 / 拖拽抓手 / 尺寸按钮自己有动作，不再叠卡片点击，
            // 否则点「刷新」会连带打开明细窗口，点尺寸按钮会连带跳转
            if (component is AbstractButton || component is JellyButton ||
                component is ActionPill || component is CardIconButton ||
                (component as? JComponent)?.getClientProperty(DASHBOARD_CARD_ACTION_KEY) == true
            ) {
                return
            }
            component.cursor = hand
            component.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    // 编辑模式只做排序 / 增删，点卡片不该跳到明细
                    if (editMode) return
                    if (SwingUtilities.isLeftMouseButton(e)) action()
                }

                override fun mouseEntered(e: MouseEvent) = hover(true)

                override fun mouseExited(e: MouseEvent) {
                    // 在子组件之间移动也会触发 exited，用卡片坐标判断是否真的离开
                    val point = SwingUtilities.convertPoint(component, e.point, card)
                    if (!card.contains(point.x, point.y)) hover(false)
                }
            })
            if (recursive && component is Container) {
                for (child in component.components) walk(child)
            }
        }
        walk(card)
    }

    /**
     * 使用说明：说明本插件真实的读写范围与网络范围。
     *
     * 放在看板末尾而非顶部工具栏，避免占用首屏空间又不丢失透明度说明。
     */
    private fun fillGuideCard(card: DashboardCard) {
        card.actions.addAction("设置", "打开插件设置", ghost = true) { onOpenSettings() }
        card.body.add(guideLine("切换账号：读取本机 auth 目录的 .info 凭据写入 IDE 的 secret-storage.xml，写入前自动备份。"))
        card.body.add(guideLine("权限范围：只读写本机 CodeBuddy 凭据与账号库目录，不访问系统钥匙串。"))
        card.body.add(guideLine("网络范围：额度与消耗明细请求 codebuddy.cn / workbuddy.cn 接口；凭据不上传、不代理。"))
    }

    /** 窄卡里的长句：按字数硬换行，避免 JLabel 把后半句直接裁掉 */
    private fun guideLine(text: String): JLabel = JLabel("<html>${text.chunked(20).joinToString("<br/>")}</html>").apply {
        alignmentX = Component.LEFT_ALIGNMENT
        font = font.deriveFont(Font.PLAIN, AccountUiType.caption)
        foreground = AccountUiColors.textMuted
        toolTipText = text
        border = JBUI.Borders.empty(1, 0)
    }

    private fun emptyHint(): JComponent {
        val box = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(48, AccountUiSpacing.cardPadH)
        }
        box.add(JLabel("看板暂无数据").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            font = font.deriveFont(Font.BOLD, AccountUiType.title)
            foreground = AccountUiColors.textSecondary
        })
        box.add(Box.createVerticalStrut(JBUI.scale(AccountUiSpacing.sm)))
        box.add(JLabel("先从 auth 目录导入账号，再刷新额度。").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            font = font.deriveFont(Font.PLAIN, AccountUiType.label)
            foreground = AccountUiColors.textFaint
        })
        return box
    }

    /** 会话时间是否为今天（ISO-8601，解析失败视为不是） */
    private fun sameDay(iso: String, day: LocalDate): Boolean {
        if (iso.isBlank()) return false
        return try {
            Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate() == day
        } catch (_: Exception) {
            false
        }
    }

    /** "2026-11-02 14:40:00" -> "14:40" */
    private fun clock(requestTime: String): String {
        val index = requestTime.indexOf(' ')
        if (index < 0) return requestTime.take(5)
        return requestTime.substring(index + 1).take(5)
    }

    private fun num(v: Double): String = AccountUiKit.formatNumber(v)

    private companion object {
        const val SEVEN_DAYS_MS = 7L * 86_400_000L
        const val THIRTY_DAYS_MS = 30L * 86_400_000L

        /** 吞吐样本有效期：超过半小时不再展示，避免看到过期的旧速度 */
        const val USAGE_SAMPLE_TTL_MS = 30L * 60_000L

        /** KPI 卡片最小列宽，低于此宽度不再分列（响应式折行依据） */
        const val KPI_MIN_WIDTH = 150

        /** 今日各模型消耗最多展示的模型数，超出部分折成提示 */
        const val MODEL_USAGE_ROWS = 4

        /** 对话活跃一次读取的条数（渲染时再按卡片容量裁剪） */
        const val ACTIVITY_ROWS = 16
    }
}

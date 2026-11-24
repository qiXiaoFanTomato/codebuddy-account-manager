package dev.codebuddy.account.pet

/**
 * 兼容层：历史上调用方按「敏感 / 破坏性 / 宿主安全」三个问题来查。
 *
 * 判定逻辑已收敛到 [RiskClassifier]（八类风险 + 最高风险 + 宿主安全标记），
 * 这里只保留同名方法，避免外部调用点一次性全改。
 *
 * **新代码请直接调用 `RiskClassifier.classify()`**：只有它能拿到具体类别，
 * 而分类策略是按类别配置的，用这三个布尔值无法表达。
 */
internal object HookApprovalRules {

    /** 红线类（凭证 / 扣费）或摘要被截断：不允许任何分类策略替主人放行 */
    fun isSensitive(request: HookApprovalRequest): Boolean =
        RiskClassifier.classify(request).let { it.redline || it.truncated }

    /** 命中任何风险类别（含 UNKNOWN）都算「危险」，供旧调用点判断卡片配色 */
    fun isDestructive(request: HookApprovalRequest): Boolean =
        !RiskClassifier.classify(request).normal

    /** 宿主 `[SafetyRule]` 会二次拦截：hook 的 allow 豁免不了，只能让位 */
    fun isHostSafety(request: HookApprovalRequest): Boolean =
        RiskClassifier.classify(request).hostSafety

    fun requiresManualConfirm(request: HookApprovalRequest): Boolean = isSensitive(request)
}

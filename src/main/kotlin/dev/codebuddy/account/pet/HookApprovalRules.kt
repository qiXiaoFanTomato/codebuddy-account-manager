package dev.codebuddy.account.pet

/**
 * hook 通道的安全阀：判定哪些调用**只能由主人手动确认**。
 *
 * 命中后仍然弹桌宠气泡（「仍要执行 / 取消」两个按钮），区别在于：
 *  - 免打扰不会替主人放行；
 *  - 超时只会交回 CodeBuddy 原生确认，不会自动执行。
 *
 * 也就是「不代点」，而不是「不问」—— 决定权始终在主人手上。
 */
internal object HookApprovalRules {

    /** 破坏性 / 提权 / 对外分发：一律不代放行 */
    private val DANGEROUS = listOf(
        "rm -rf", "rm -fr", "rmdir /s", "rd /s", "del /f", "del /s", "del /q",
        "format ", "mkfs", "dd if=", "diskpart", "fdisk",
        "shutdown", "restart-computer", "reg delete", "reg add", "bcdedit",
        "takeown", "icacls", "sudo ", "runas ", "start-process -verb runas",
        "git push --force", "git push -f", "git reset --hard", "git clean -fd",
        "npm publish", "twine upload", "scp ", "rsync ",
    )

    /** 凭证 / 扣费相关：同样交给主人自己看 */
    private val SENSITIVE = listOf(
        "password", "passwd", "secret", "token", "api key", "apikey", "credential",
        ".ssh", "id_rsa", "cookie", "积分", "扣费", "支付", "付款", "订阅",
    )

    /**
     * 是否只能人工确认。
     *
     * 摘要为空时**不**判为受保护：这类调用（例如没有命令的写文件工具）
     * 恰恰需要主人看一眼，交给气泡展示更合适。
     */
    fun requiresManualConfirm(request: HookApprovalRequest): Boolean {
        // 摘要被截断时看不全，宁可当成危险处理，也不让免打扰替主人放行
        if (request.truncated) return true
        val detail = request.detail.lowercase()
        if (detail.isBlank()) return false
        return DANGEROUS.any { detail.contains(it) } || SENSITIVE.any { detail.contains(it) }
    }
}

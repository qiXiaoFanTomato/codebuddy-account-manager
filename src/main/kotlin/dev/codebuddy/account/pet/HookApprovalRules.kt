package dev.codebuddy.account.pet

/**
 * hook 通道的安全阀：把「不能替主人放行」的调用分成三类。
 *
 *  - [isSensitive]：凭证、扣费、对外分发、摘要被截断 —— **永远人工**，不受任何策略影响；
 *  - [isHostSafety]：宿主 `[SafetyRule]` 会在 hook 之后再拦一次的命令（日志实证
 *    `Safety rule triggered despite hook presence`），hook 的 allow 无法豁免它 ——
 *    让位给宿主的原生态确认框，桌宠不代点也不弹气泡，避免主人点两遍；
 *  - [isDestructive]：破坏性 / 提权命令 —— 交给 `cbApprovalDanger` 策略决定，
 *    选「自动放行」直接代办、选「问，超时没人管就放行」等超时代办、选「一直等人工」走气泡。
 *
 * 拆分理由：原来两类混在 `requiresManualConfirm` 里，导致「危险命令永远人工」这条
 * 硬规则无法放开，主人连选项都没有。
 */
internal object HookApprovalRules {

    /** 破坏性 / 提权：可由 [CbDangerPolicy] 放开 */
    private val DESTRUCTIVE = listOf(
        "rm -rf", "rm -fr", "rmdir /s", "rd /s", "del /f", "del /s", "del /q",
        // PowerShell 侧此前完全没覆盖，`Remove-Item -Force` 这类会被当成普通命令
        "remove-item", "clear-content", "stop-process",
        "format ", "mkfs", "dd if=", "diskpart", "fdisk",
        "shutdown", "restart-computer", "reg delete", "reg add", "bcdedit",
        "takeown", "icacls", "sudo ", "runas ", "start-process -verb runas",
        "set-executionpolicy", "invoke-expression", "iex ",
        "git push --force", "git push -f", "git reset --hard", "git clean -fd",
    )

    /**
     * 凭证 / 扣费 / 对外分发：**不受危险策略影响**。
     *
     * 这几类失败一次不可回滚（密钥泄露、钱被扣、代码发到外部），
     * 所以即使主人把危险命令设成「允许自动放行」，仍然只能人工点。
     */
    private val SENSITIVE = listOf(
        "password", "passwd", "secret", "token", "api key", "apikey", "credential",
        ".ssh", "id_rsa", "cookie", "积分", "扣费", "支付", "付款", "订阅",
        "npm publish", "twine upload", "scp ", "rsync ",
    )

    /** 只有命令类工具才可能被宿主 SafetyRule 拦；Write / Edit 的参数是文件内容，不参与匹配 */
    private val COMMAND_TOOLS = setOf("bash", "execute_command", "powershell", "terminal")

    /**
     * 宿主 `[SafetyRule]` 实测会二次拦截的命令特征。
     *
     * **只收本机日志验证过的三类**（`[PermissionEngine] Safety rule triggered despite hook presence: <reason>`）：
     *  - `remove-item` → `security.powershellRecursiveDelete`
     *  - `powershell` / `pwsh` 起脚本 → `security.powershellScriptExecution`
     *  - `node -e` / `node --eval` → `security.commandInjection`
     *
     * 新增特征必须先在同一台机器的日志里看到对应 reason，否则误判会让本该由桌宠确认的命令
     * 直接落到宿主默认策略上，等于静默放行。
     */
    private val HOST_SAFETY = listOf(
        "remove-item",
        "powershell -", "powershell.exe", "pwsh -", "pwsh.exe", "-executionpolicy bypass",
        "node -e", "node --eval",
    )

    /** 摘要被截断时看不全，宁可当成敏感处理，也不让任何策略替主人放行 */
    fun isSensitive(request: HookApprovalRequest): Boolean {
        if (request.truncated) return true
        val detail = request.detail.lowercase()
        if (detail.isBlank()) return false
        return SENSITIVE.any { detail.contains(it) }
    }

    /** 破坏性 / 提权命令：可由危险策略放开 */
    fun isDestructive(request: HookApprovalRequest): Boolean {
        val detail = request.detail.lowercase()
        if (detail.isBlank()) return false
        return DESTRUCTIVE.any { detail.contains(it) }
    }

    /** 宿主 SafetyRule 一定会二次拦截：hook 让位，既不代点也不弹桌宠气泡 */
    fun isHostSafety(request: HookApprovalRequest): Boolean {
        if (request.toolName.lowercase() !in COMMAND_TOOLS) return false
        val detail = request.detail.lowercase()
        if (detail.isBlank()) return false
        return HOST_SAFETY.any { detail.contains(it) }
    }

    /** 是否仍需要主人亲手确认（敏感类）；危险类由调用方按策略判断 */
    fun requiresManualConfirm(request: HookApprovalRequest): Boolean = isSensitive(request)
}

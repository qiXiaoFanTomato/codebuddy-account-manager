package dev.codebuddy.account.pet

/**
 * 一次工具调用的风险类型。
 *
 * [severity] 越小越严重，用于「一条命令命中多类」时取最高风险，保证同一个输入
 * 无论规则遍历顺序如何都得到同一个结论（否则策略会抖动）。
 *
 * 排序依据是**不可回滚性**，不是主观危险感：
 * 扣费 / 凭证泄露 / 代码外发一旦发生无法撤销；误删有备份与 reflog，所以排在后面。
 *
 * [configurable] 为 false 的两类是红线：设置页只读，不允许配成自动放行，
 * 只有静默运行（并且显式勾选放开红线）才能越过。
 *
 * 可见性是 public：它出现在 [PetSettings] 的公开方法签名上（策略查询与配置写入）。
 */
enum class CbRiskKind(
    val label: String,
    val severity: Int,
    val hint: String,
    val configurable: Boolean = true,
) {
    BILLING("扣费", 0, "积分 / 支付 / 订阅 / 余额类调用", configurable = false),
    CREDENTIAL("凭证", 1, "读写 .ssh、密钥、token、密码等凭据", configurable = false),
    EGRESS("对外分发", 2, "把代码或文件发布 / 上传到外部"),
    SCRIPT("脚本执行", 3, "shell / PowerShell / node -e 等内联脚本"),
    PRIVILEGE("提权", 4, "sudo / runas / 改执行策略"),
    SYSTEM("系统与磁盘", 5, "格式化、分区、关机、注册表"),
    DELETE("删除", 6, "递归删除、强制删除"),
    GIT_DESTRUCTIVE("Git 破坏", 7, "force push、reset --hard、clean -fd"),
    UNKNOWN("无法识别", 8, "摘要为空或被截断，看不全"),
    NORMAL("普通", 9, "未命中任何风险规则"),
    ;

    /** 红线：不受分类策略影响，只有静默（且放开红线）能越过 */
    val redline: Boolean get() = !configurable
}

/**
 * 一次调用的完整风险画像。
 *
 * [hostSafety] 表示宿主 `[SafetyRule]` 会在 hook 之外再拦一次（实测日志
 * `Safety rule triggered despite hook presence`）：返回 allow 也豁免不了，
 * 只能让位，静默模式同样无效。
 */
internal class RiskProfile(
    val kinds: Set<CbRiskKind>,
    val top: CbRiskKind,
    val hostSafety: Boolean,
    /** 摘要被截断：看不全，任何策略都不允许「直接放行」 */
    val truncated: Boolean = false,
) {
    val redline: Boolean get() = top.redline
    val normal: Boolean get() = top == CbRiskKind.NORMAL
}

/**
 * 工具调用风险分类器（纯函数，不依赖平台 API，可直接单测）。
 *
 * ## 为什么不用裸子串匹配
 *
 * 旧实现 `detail.contains("format ")` 这类写法有三类可复现的错判：
 *  - `npm run format `（带尾空格）被判成磁盘格式化；
 *  - `SENSITIVE` 里的 `"scp "` 会把 `scp-notes.md` 这样的**文件路径**当成外发；
 *  - `"sudo "` / `"iex "` 带尾空格，遇到 `sudo` 结尾、`sudo\t`、`iex(...)` 就漏报。
 *
 * 因此这里改为：**先判断工具类型**（只有命令类工具才做命令分析，路径不参与）→
 * 按 `&& || ; |` 换行切成独立命令 → 每段认「命令位」（跳过 sudo / env / xargs
 * 这类前缀）→ 命令名 + 参数位判断 → 所有段取最高风险。
 *
 * 宁可保守：拿不准就归 [CbRiskKind.UNKNOWN]，走人工确认，而不是当普通命令放行。
 */
internal object RiskClassifier {

    /**
     * 会执行命令的工具名（小写）。
     *
     * 只有这些工具的 `detail` 才是命令原文；Write / Edit 的 `detail` 是文件路径，
     * 拿去做命令分析会把 `C:\work\format` 当成格式化磁盘。
     */
    private val COMMAND_TOOLS = setOf(
        "bash", "shell", "sh", "zsh",
        "execute_command", "run_command", "command", "cmd",
        "powershell", "pwsh", "terminal",
    )

    /** 复合命令分隔符：`&&`、`||`、`;`、管道、换行 */
    private val SEPARATOR = Regex("""\s*(?:&&|\|\||;|\||\r?\n)\s*""")

    /** 前缀命令：跳过它们才能认到真正的命令位；同时 sudo / doas 本身算提权 */
    private val WRAPPERS = setOf(
        "sudo", "doas", "env", "time", "nohup", "command", "exec",
        "nice", "ionice", "timeout", "watch", "xargs", "builtin",
    )

    /** 只做输出、不执行后续参数的命令：`echo "rm -rf /"` 不该被判成删除 */
    private val ECHO_ONLY = setOf("echo", "printf", "write-output", "write-host", "print")

    private class Rule(
        val names: Set<String>,
        val kind: CbRiskKind,
        /** 需要紧跟其后的子命令之一（npm publish / git push） */
        val subcommands: Set<String> = emptySet(),
        /** 需要任一 flag（`-f` 会匹配到合并写法 `-rf`） */
        val requiresFlag: Set<String> = emptySet(),
        /** 需要任一「前缀参数」，如 dd 的 `of=` */
        val requiresArgPrefix: Set<String> = emptySet(),
    )

    private val RULES: List<Rule> = listOf(
        // ---- 删除 ----
        Rule(
            names = setOf("rm", "del", "erase", "rd", "rmdir", "unlink", "remove-item", "ri", "sdelete"),
            kind = CbRiskKind.DELETE,
        ),
        Rule(names = setOf("clear-content", "clc", "truncate"), kind = CbRiskKind.DELETE),

        // ---- 提权 ----
        Rule(
            names = setOf(
                "sudo", "doas", "su", "runas", "takeown", "icacls",
                "set-executionpolicy", "set-mppreference",
            ),
            kind = CbRiskKind.PRIVILEGE,
        ),
        Rule(names = setOf("start-process"), kind = CbRiskKind.PRIVILEGE, requiresFlag = setOf("-verb")),

        // ---- 系统与磁盘 ----
        Rule(
            names = setOf(
                "format", "mkfs", "diskpart", "fdisk", "parted",
                "shutdown", "reboot", "halt", "poweroff", "bcdedit",
                "chkdsk", "sfc", "mklabel",
            ),
            kind = CbRiskKind.SYSTEM,
        ),
        Rule(names = setOf("dd"), kind = CbRiskKind.SYSTEM, requiresArgPrefix = setOf("of=")),
        Rule(names = setOf("reg"), kind = CbRiskKind.SYSTEM, subcommands = setOf("add", "delete", "import")),
        Rule(names = setOf("net"), kind = CbRiskKind.SYSTEM, subcommands = setOf("user", "localgroup")),
        Rule(names = setOf("sc"), kind = CbRiskKind.SYSTEM, subcommands = setOf("delete", "stop", "config")),

        // ---- 脚本执行（宿主 SafetyRule 也会拦，见 hostSafety）----
        Rule(names = setOf("iex", "invoke-expression", "eval"), kind = CbRiskKind.SCRIPT),
        Rule(
            names = setOf("powershell", "pwsh"),
            kind = CbRiskKind.SCRIPT,
            requiresFlag = setOf("-c", "-command", "-e", "-encodedcommand", "-file", "-f"),
        ),
        Rule(names = setOf("node", "deno", "bun"), kind = CbRiskKind.SCRIPT, requiresFlag = setOf("-e", "--eval", "-p", "--print")),
        Rule(names = setOf("python", "python3", "py", "perl", "ruby", "php"), kind = CbRiskKind.SCRIPT, requiresFlag = setOf("-c")),
        Rule(names = setOf("cmd"), kind = CbRiskKind.SCRIPT, requiresFlag = setOf("/c", "/k")),
        Rule(names = setOf("bash", "sh", "zsh", "fish"), kind = CbRiskKind.SCRIPT, requiresFlag = setOf("-c")),

        // ---- 对外分发 ----
        Rule(names = setOf("scp", "sftp", "rsync", "ssh"), kind = CbRiskKind.EGRESS),
        Rule(names = setOf("nc", "netcat", "telnet"), kind = CbRiskKind.EGRESS),
        Rule(names = setOf("npm", "yarn", "pnpm"), kind = CbRiskKind.EGRESS, subcommands = setOf("publish")),
        Rule(names = setOf("twine"), kind = CbRiskKind.EGRESS, subcommands = setOf("upload")),
        Rule(names = setOf("docker", "podman"), kind = CbRiskKind.EGRESS, subcommands = setOf("push")),
        Rule(names = setOf("curl"), kind = CbRiskKind.EGRESS, requiresFlag = setOf("-d", "--data", "-F", "--form", "-T", "--upload-file")),
        Rule(names = setOf("wget"), kind = CbRiskKind.EGRESS, requiresFlag = setOf("--post-data", "--post-file")),
    )

    /** Git 破坏性操作：需要同时看子命令与参数，单独判 */
    private val GIT_FORCE_FLAGS = setOf("--force", "-f", "--force-with-lease", "--mirror", "--delete")

    /** 目录名 / 环境变量里出现即视为凭据（带路径语义，不会误伤 tokens.json） */
    private val CREDENTIAL_PATHS = listOf(
        ".ssh/", ".ssh\\", "id_rsa", "id_ed25519", "id_ecdsa",
        ".aws/credentials", ".aws\\credentials", ".kube/config", ".netrc",
        ".npmrc", ".pypirc", ".gnupg", "credentials.json", "secrets.json",
        "secrets.yaml", "secrets.yml",
        // .env 需要路径语义：`cat .env` / `~/.env` 算，`.environment` 不算
        " .env", "/.env", "\\.env", "\t.env",
    )

    /** 凭据关键词：只有出现在「读取类命令」里才算，避免 git add tokens.json 误判 */
    private val CREDENTIAL_WORDS = Regex(
        """\b(password|passwd|secret|token|apikey|api[_-]?key|credential|private[_-]?key|access[_-]?key)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val READ_COMMANDS = setOf(
        "cat", "type", "get-content", "gc", "more", "less", "head", "tail",
        "cp", "copy", "scp", "curl", "wget", "printenv", "env", "set",
    )

    private val BILLING_WORDS = Regex(
        """(积分|扣费|支付|付款|订阅|充值|余额|消耗|计费)|\b(billing|payment|invoice|subscribe|subscription|credits?)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 宿主 `[SafetyRule]` 实测会二次拦截的组合（只收本机日志验证过的三类）。
     *
     * 新增条件必须先在日志里看到对应 reason，否则误判会让本该由桌宠确认的命令
     * 直接落到宿主默认策略上，等于静默放行。
     */
    private fun isHostSafety(head: String, tokens: List<String>): Boolean = when (head) {
        "remove-item", "ri" -> hasFlag(tokens, "-recurse") || hasFlag(tokens, "-r")
        "powershell", "pwsh" -> true
        "node" -> hasFlag(tokens, "-e") || hasFlag(tokens, "--eval")
        else -> false
    }

    /** 入口：从 hook 请求分类 */
    fun classify(request: HookApprovalRequest): RiskProfile =
        classify(request.toolName, request.detail, request.truncated)

    /**
     * 入口：从工具名与摘要分类。
     *
     * @param toolName 宿主上报的工具名，大小写不定
     * @param detail 命令原文 / 文件路径 / URL；可能为空
     * @param truncated 摘要是否被截断
     */
    fun classify(toolName: String, detail: String, truncated: Boolean = false): RiskProfile {
        val tool = toolName.trim().lowercase()
        val text = detail.trim()
        val kinds = linkedSetOf<CbRiskKind>()

        // 内容类规则对命令与路径都成立（写 .ssh 也是凭据风险）
        kinds += contentKinds(text)

        if (text.isBlank()) {
            // 摘要为空：只有命令类才算「看不全」（UNKNOWN，不许自动放行）。
            // 写 / 编辑 / MCP 这类非命令工具本来就可能不传路径或只传 url，
            // 一律判 UNKNOWN 会让每一次写文件都当成「看不懂的命令」弹窗（实测 Write 的
            // tool_input 只有 filePath+content，旧投影取不到 → detail 恒空）
            if (tool in COMMAND_TOOLS) kinds += CbRiskKind.UNKNOWN
            return profile(kinds, truncated = truncated)
        }

        var hostSafety = false
        if (tool in COMMAND_TOOLS) {
            for (tokens in segments(text)) {
                val result = classifySegment(tokens)
                kinds += result.kinds
                hostSafety = hostSafety || result.hostSafety
            }
        } else if (kinds.isEmpty()) {
            // 非命令工具（Write / Edit / MCP）且未命中内容规则：按普通处理，
            // 但要保证不是「看不懂的命令」，所以只对命令类工具才用 UNKNOWN 兜底
            kinds += CbRiskKind.NORMAL
        }

        return profile(kinds, truncated = truncated, hostSafety = hostSafety)
    }

    private fun profile(
        kinds: Set<CbRiskKind>,
        truncated: Boolean,
        hostSafety: Boolean = false,
    ): RiskProfile {
        val cleaned = kinds.ifEmpty { setOf(CbRiskKind.NORMAL) }
        // 截断时无条件归 UNKNOWN：看不全就不允许任何策略「直接放行」，
        // 只允许人工点、超时放行或静默；否则一条被截断的危险命令会当普通命令白放
        val effective = if (truncated) cleaned + CbRiskKind.UNKNOWN else cleaned
        val top = effective.minByOrNull { it.severity } ?: CbRiskKind.NORMAL
        return RiskProfile(effective, top, hostSafety, truncated)
    }

    private class SegmentResult(val kinds: Set<CbRiskKind>, val hostSafety: Boolean)

    /** 分析单段命令：先剥前缀，再按命令位匹配 */
    private fun classifySegment(tokens: List<String>): SegmentResult {
        if (tokens.isEmpty()) return SegmentResult(emptySet(), false)
        val head0 = headOf(tokens[0])
        // echo / printf 只输出不执行：`echo "rm -rf /"` 不算删除
        if (head0 in ECHO_ONLY) return SegmentResult(emptySet(), false)

        val kinds = linkedSetOf<CbRiskKind>()
        var index = 0
        // 跳过 sudo / env / xargs 这类前缀，同时把 sudo 本身记成提权
        while (index < tokens.size - 1) {
            val wrapper = headOf(tokens[index])
            if (wrapper !in WRAPPERS) break
            if (wrapper == "sudo" || wrapper == "doas") kinds += CbRiskKind.PRIVILEGE
            index++
            // 跳掉前缀自己的参数：`sudo -u root`、`timeout 30s`、`env FOO=1`
            while (index < tokens.size - 1) {
                val t = tokens[index]
                val isOption = t.startsWith("-") && t.length > 1
                val isValue = t.matches(Regex("""^\d+[smhd]?$""")) ||
                    t.matches(Regex("""^[A-Za-z_][A-Za-z0-9_]*=.*$"""))
                if (!isOption && !isValue) break
                // 短选项带独立取值（-u root）跳两个；其余只跳自己
                index += if (isOption && t.length == 2 && index + 2 < tokens.size) 2 else 1
            }
        }
        if (index >= tokens.size) return SegmentResult(kinds, false)

        val head = headOf(tokens[index])
        val args = tokens.subList(index, tokens.size)
        kinds += matchRules(head, args)
        if (head == "git") kinds += gitKinds(args)
        val hostSafety = isHostSafety(head, args)
        return SegmentResult(kinds, hostSafety)
    }

    private fun matchRules(head: String, tokens: List<String>): Set<CbRiskKind> {
        val out = linkedSetOf<CbRiskKind>()
        // `mkfs.ext4` / `python3.11` 这类带点号后缀的命令，按主干名再匹配一次
        val candidates = linkedSetOf(head)
        val dot = head.indexOf('.')
        if (dot > 0 && head.take(dot).any { it.isLetter() }) candidates += head.substring(0, dot)
        for (rule in RULES) {
            if (candidates.none { it in rule.names }) continue
            if (rule.subcommands.isNotEmpty() && tokens.getOrNull(1)?.lowercase() !in rule.subcommands) continue
            if (rule.requiresFlag.isNotEmpty() && !hasFlag(tokens, *rule.requiresFlag.toTypedArray())) continue
            if (rule.requiresArgPrefix.isNotEmpty() &&
                tokens.none { t -> rule.requiresArgPrefix.any { t.startsWith(it, ignoreCase = true) } }
            ) continue
            out += rule.kind
        }
        return out
    }

    /** Git 破坏性操作：必须同时看子命令与参数，否则 `git pull --force` 会被误判 */
    private fun gitKinds(tokens: List<String>): Set<CbRiskKind> {
        val sub = tokens.getOrNull(1)?.lowercase() ?: return emptySet()
        val hit = when (sub) {
            "push" -> hasFlag(tokens, *GIT_FORCE_FLAGS.toTypedArray())
            "reset" -> hasFlag(tokens, "--hard")
            "clean" -> hasFlag(tokens, "-f", "--force")
            "checkout" -> hasFlag(tokens, "-f", "--force")
            else -> false
        }
        return if (hit) setOf(CbRiskKind.GIT_DESTRUCTIVE) else emptySet()
    }

    /** 内容类风险：凭据与扣费，命令与路径都适用 */
    private fun contentKinds(text: String): Set<CbRiskKind> {
        if (text.isBlank()) return emptySet()
        val lower = text.lowercase()
        val out = linkedSetOf<CbRiskKind>()

        if (CREDENTIAL_PATHS.any { lower.contains(it) }) {
            out += CbRiskKind.CREDENTIAL
        } else {
            // 关键词必须配「读取类命令」：cat .env 算，git add tokens.json 不算
            val firstToken = segments(lower).firstOrNull()?.firstOrNull()?.let(::headOf)
            if (firstToken != null && firstToken in READ_COMMANDS && CREDENTIAL_WORDS.containsMatchIn(lower)) {
                out += CbRiskKind.CREDENTIAL
            }
        }
        if (BILLING_WORDS.containsMatchIn(text)) out += CbRiskKind.BILLING
        return out
    }

    /** 按分隔符切成独立命令，每条再分词 */
    private fun segments(detail: String): List<List<String>> =
        detail.split(SEPARATOR)
            .map { tokenize(it) }
            .filter { it.isNotEmpty() }

    private fun tokenize(segment: String): List<String> =
        segment.split(Regex("""\s+"""))
            .map { it.trim('"', '\'', '`', '(', ')', '{', '}', '[', ']', ',') }
            .filter { it.isNotEmpty() }

    /**
     * 取命令位名字：去掉路径前缀与 Windows 扩展名。
     *
     * `C:\Windows\System32\format.com` → `format`，
     * `/usr/bin/rm` → `rm`，`./gradlew` → `gradlew`。
     */
    private fun headOf(token: String): String {
        var s = token.replace('\\', '/')
        val slash = s.lastIndexOf('/')
        if (slash >= 0) s = s.substring(slash + 1)
        for (ext in listOf(".exe", ".com", ".bat", ".cmd", ".ps1")) {
            if (s.endsWith(ext, ignoreCase = true)) {
                s = s.dropLast(ext.length)
                break
            }
        }
        return s.lowercase()
    }

    /**
     * 是否带某个 flag。
     *
     * 单横线短选项要支持合并写法：`-rf` 同时命中 `-r` 与 `-f`；
     * 双横线长选项必须完整相等，避免 `--force-with-lease` 被 `--force` 误命中。
     */
    private fun hasFlag(tokens: List<String>, vararg flags: String): Boolean = tokens.any { token ->
        val t = token.lowercase()
        flags.any { flag ->
            val f = flag.lowercase()
            when {
                t == f -> true
                f.startsWith("--") -> false
                f.length == 2 && f[0] == '-' && t.length > 2 && t[0] == '-' && t[1] != '-' ->
                    t.substring(1).contains(f[1])
                else -> false
            }
        }
    }
}

package dev.codebuddy.account.model

/**
 * 一份完整的 CodeBuddy 凭据快照，对应 auth 目录下的一个 .info 文件。
 *
 * 关键事实（经实测验证）：
 * - token 只存在顶层 auth 节点，accounts / allAccounts 里不含 token
 * - 因此多账号切换必须整文件替换，不能只改 accounts 数组
 */
data class CredentialSnapshot(
    /** 账号唯一标识 */
    val uid: String,
    /** 昵称，如 "启小凡" */
    val nickname: String,
    /** uin，如 "330107094664" */
    val uin: String,
    /** 账号类型 personal / enterprise */
    val type: String,
    /** 手机号（可能已脱敏） */
    val phoneNumber: String,
    /** 认证域，如 www.codebuddy.cn / www.workbuddy.cn */
    val domain: String,
    /** access token 过期时间戳（毫秒），0 表示未知 */
    val expiresAt: Long,
    /** refresh token 过期时间戳（毫秒），0 表示未知 */
    val refreshExpiresAt: Long,
    /** 原始文件完整内容，切换时原样写回 */
    val rawContent: String,
    /** 来源文件路径（仅用于展示） */
    val sourcePath: String,
    /** 导入时间戳 */
    val importedAt: Long = System.currentTimeMillis(),
    /** 用户自定义别名，用于区分同名/同脱敏手机号的账号 */
    val alias: String = "",
) {
    /** 界面展示名：优先别名，其次昵称 */
    val displayName: String
        get() = alias.ifBlank { nickname.ifBlank { uin.ifBlank { uid.take(8) } } }

    /**
     * 账号唯一键：uid + 认证域。
     *
     * 同一个 uid 可能在不同域下有独立账号（实测：启小凡同时有
     * www.codebuddy.cn 和 www.workbuddy.cn 两份凭据），
     * 仅用 uid 作为主键会互相覆盖。
     */
    val accountKey: String
        get() = uid + "@" + domain

    /** 主域，如 codebuddy.cn / workbuddy.cn */
    val domainHost: String
        get() = domain.removePrefix("www.")
    val isAccessTokenExpired: Boolean
        get() = expiresAt > 0 && System.currentTimeMillis() > expiresAt

    val isRefreshTokenExpired: Boolean
        get() = refreshExpiresAt > 0 && System.currentTimeMillis() > refreshExpiresAt
}

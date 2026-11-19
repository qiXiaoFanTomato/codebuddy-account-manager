package dev.codebuddy.account.model

/**
 * 账号键工具。
 *
 * accountKey 形如：ab6f110b-...-c077944f33d0@www.codebuddy.cn
 * Windows 文件名不允许 < > : " / \ | ? *，这里做一次兜底替换。
 */
object AccountKeys {

    /** 文件名非法字符：导入 / 迁移时逐个账号调用，正则只编译一次 */
    private val ILLEGAL_CHARS = Regex("[<>:\"/\\\\|?*]")

    /** 转成安全文件名 */
    fun toFileName(accountKey: String): String =
        accountKey.replace(ILLEGAL_CHARS, "_") + ".info"

    fun fromFileName(fileName: String): String =
        fileName.removeSuffix(".info")
}

package dev.codebuddy.account.model

/**
 * 账号键工具。
 *
 * accountKey 形如：ab6f110b-...-c077944f33d0@www.codebuddy.cn
 * Windows 文件名不允许 < > : " / \ | ? *，这里做一次兜底替换。
 */
object AccountKeys {

    /** 转成安全文件名 */
    fun toFileName(accountKey: String): String =
        accountKey.replace(Regex("[<>:\"/\\\\|?*]"), "_") + ".info"

    fun fromFileName(fileName: String): String =
        fileName.removeSuffix(".info")
}

package dev.codebuddy.account.ui

import dev.codebuddy.account.model.CredentialSnapshot

/**
 * 账号排序与分组。
 *
 * 分组顺序：当前激活 > 域名 > 昵称
 */
enum class AccountSortMode(val label: String) {
    DEFAULT("默认"),
    BY_DOMAIN("按域名分组"),
    BY_LAST_USED("按过期时间"),
    BY_QUOTA("按剩余额度"),
    BY_NAME("按昵称"),
}

object AccountSorter {

    fun sort(
        list: List<CredentialSnapshot>,
        mode: AccountSortMode,
        activeKey: String?,
        quotaRemain: (String) -> Double?,
    ): List<CredentialSnapshot> {
        // 当前激活账号永远排最前
        val active = list.filter { it.accountKey == activeKey }
        val rest = list.filter { it.accountKey != activeKey }

        val sortedRest = when (mode) {
            AccountSortMode.DEFAULT -> rest.sortedByDescending { it.importedAt }
            AccountSortMode.BY_DOMAIN -> rest.sortedWith(
                compareBy({ it.domainHost }, { it.displayName })
            )
            AccountSortMode.BY_LAST_USED -> rest.sortedByDescending { it.expiresAt }
            AccountSortMode.BY_QUOTA -> rest.sortedByDescending { quotaRemain(it.accountKey) ?: -1.0 }
            AccountSortMode.BY_NAME -> rest.sortedBy { it.displayName }
        }
        return active + sortedRest
    }

    /** 按域名分组（用于分组标题展示），保持传入顺序 */
    fun groupByDomain(list: List<CredentialSnapshot>): List<Pair<String, List<CredentialSnapshot>>> {
        val order = LinkedHashMap<String, MutableList<CredentialSnapshot>>()
        for (s in list) {
            val g = s.domainHost.ifBlank { "未知域" }
            order.getOrPut(g) { mutableListOf() }.add(s)
        }
        return order.map { (k, v) -> k to v.toList() }
    }
}

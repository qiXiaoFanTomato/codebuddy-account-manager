package dev.codebuddy.account.ui

import dev.codebuddy.account.model.CredentialSnapshot
import dev.codebuddy.account.quota.QuotaSummary
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.table.AbstractTableModel

/**
 * 账号列表表格模型。
 */
class AccountTableModel : AbstractTableModel() {

    private val columns = arrayOf("当前", "昵称", "UIN", "类型", "手机号", "认证域", "Token 状态", "剩余额度", "导入时间")
    private var rows: List<Row> = emptyList()
    private var quotas: Map<String, QuotaSummary> = emptyMap()

    data class Row(val snapshot: CredentialSnapshot, val isActive: Boolean)

    fun setData(list: List<CredentialSnapshot>, activeUid: String?, quotaMap: Map<String, QuotaSummary>) {
        rows = list.map { Row(it, it.accountKey == activeUid) }
        quotas = quotaMap
        fireTableDataChanged()
    }

    fun rowAt(index: Int): Row? = rows.getOrNull(index)

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val r = rows[rowIndex]
        val s = r.snapshot
        return when (columnIndex) {
            0 -> if (r.isActive) "●" else ""
            1 -> s.nickname
            2 -> s.uin
            3 -> s.type
            4 -> s.phoneNumber
            5 -> s.domain
            6 -> when {
                s.isRefreshTokenExpired -> "已过期（需重新登录）"
                s.isAccessTokenExpired -> "access 过期，可刷新"
                else -> "有效"
            }
            7 -> quotas[s.uid]?.summaryText() ?: "—"
            8 -> SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(s.importedAt))
            else -> ""
        }
    }
}

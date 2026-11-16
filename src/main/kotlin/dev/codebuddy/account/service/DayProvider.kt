package dev.codebuddy.account.service

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 「业务日」的唯一出口。
 *
 * 签到、今日用量、按日去重（claimDaily）都以本地日期作 key。散落各处各写
 * `LocalDate.now()` 的问题是：想统一调整口径（跨时区、跟随服务端时区）时
 * 要改十几处，漏一处就会「签到记了但服务端不算今天」。
 *
 * 默认仍用系统时区（保持原有行为）。需要对齐服务端时区时，不改代码即可通过
 * `-Dcodebuddy.account.dayZone=Asia/Shanghai` 或 IDE 的 custom VM options 覆盖。
 */
object DayProvider {

    /** 显式指定的业务日时区；未设置或非法时返回 null（回退系统时区） */
    private val overrideZone: ZoneId? = System.getProperty("codebuddy.account.dayZone")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() }

    private fun zone(): ZoneId = overrideZone ?: ZoneId.systemDefault()

    /** 当前业务日 */
    fun today(): LocalDate = LocalDate.now(zone())

    /** 当前业务日（yyyy-MM-dd） */
    fun todayText(): String = today().toString()

    /** 当前业务时间 */
    fun now(): LocalDateTime = LocalDateTime.now(zone())
}

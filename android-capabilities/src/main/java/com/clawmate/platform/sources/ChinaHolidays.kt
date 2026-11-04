package com.clawmate.platform.sources

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 常用节假日：按国务院办公厅公布的放假安排整理的本地表。
 *
 * 规则：
 *  - 只收录官方通知中明确写出的放假日与调休上班日；未覆盖的年份只按常规周末显示，
 *    并明确提示“该年度放假安排尚未发布”。
 *  - 调休上班日（周末补班）单独成条并标注“调休上班”，不当作休息日。
 *  - 每个条目都带来源标记（[KIND]）和依据（通知名称），不推断日程是否已完成。
 */
object ChinaHolidays {
    /** 来源编号：系统日历日历 id 为非负整数，节假日使用 -1 与系统来源区分。 */
    const val SOURCE_ID = -1L
    const val SOURCE_NAME = "常用节假日"
    const val KIND = "假日"

    /** 已收录官方通知的最大年份。 */
    val latestNoticeYear: Int get() = table.filterValues { it.isNotEmpty() }.keys.max()

    data class Day(val name: String, val workday: Boolean, val notice: String)

    /** 2025年放假安排：国办发明电〔2024〕7号。 */
    private val n2025 = "2025年放假安排"
    /** 2026年放假安排：国办发明电〔2025〕6号。 */
    private val n2026 = "2026年放假安排"

    private val table: Map<Int, Map<LocalDate, Day>> = buildMap<Int, MutableMap<LocalDate, Day>> {
        // 放假区间按“起始月日到结束月日”登记，跨月的区间（如春节 2月15日—2月23日）
        // 由 from/to 共同确定，避免把月当成区间终点。
        fun rest(year: Int, month: Int, day: Int, toMonth: Int, toDay: Int, name: String, notice: String) {
            val map = getOrPut(year) { LinkedHashMap() }
            var d = LocalDate.of(year, month, day); val end = LocalDate.of(year, toMonth, toDay)
            check(!end.isBefore(d)) { "放假区间顺序错误: $year-$month-$day..$toMonth-$toDay" }
            while (!d.isAfter(end)) { map[d] = Day(name, false, notice); d = d.plusDays(1) }
        }
        fun work(year: Int, month: Int, day: Int, name: String, notice: String) {
            getOrPut(year) { LinkedHashMap() }[LocalDate.of(year, month, day)] = Day(name, true, notice)
        }
        // 2025年
        rest(2025, 1, 1, 1, 1, "元旦", n2025)
        rest(2025, 1, 28, 2, 4, "春节", n2025); work(2025, 1, 26, "春节调休上班", n2025); work(2025, 2, 8, "春节调休上班", n2025)
        rest(2025, 4, 4, 4, 6, "清明节", n2025)
        rest(2025, 5, 1, 5, 5, "劳动节", n2025); work(2025, 4, 27, "劳动节调休上班", n2025)
        rest(2025, 5, 31, 6, 2, "端午节", n2025)
        rest(2025, 10, 1, 10, 8, "国庆节、中秋节", n2025); work(2025, 9, 28, "国庆节调休上班", n2025); work(2025, 10, 11, "国庆节调休上班", n2025)
        // 2026年
        rest(2026, 1, 1, 1, 3, "元旦", n2026); work(2026, 1, 4, "元旦调休上班", n2026)
        rest(2026, 2, 15, 2, 23, "春节", n2026); work(2026, 2, 14, "春节调休上班", n2026); work(2026, 2, 28, "春节调休上班", n2026)
        rest(2026, 4, 4, 4, 6, "清明节", n2026)
        rest(2026, 5, 1, 5, 5, "劳动节", n2026); work(2026, 5, 9, "劳动节调休上班", n2026)
        rest(2026, 6, 19, 6, 21, "端午节", n2026)
        rest(2026, 9, 25, 9, 27, "中秋节", n2026)
        rest(2026, 10, 1, 10, 7, "国庆节", n2026); work(2026, 9, 20, "国庆节调休上班", n2026); work(2026, 10, 10, "国庆节调休上班", n2026)
    }

    /** 指定日期的官方安排；未收录年份返回 null。 */
    fun day(date: LocalDate): Day? = table[date.year]?.get(date)

    /** 一个日期是否已按官方通知标注（放假或调休上班）。 */
    fun covered(date: LocalDate): Boolean = table.containsKey(date.year)

    fun isWeekend(date: LocalDate): Boolean = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

    /** 快速浏览窗口内的条目：放假日逐日成条，常规周末合并成条，调休上班单独成条。 */
    fun entries(from: LocalDate, to: LocalDate): List<HolidayEntry> {
        if (to < from) return emptyList()
        val out = ArrayList<HolidayEntry>()
        var date = from
        while (date <= to) {
            val covered = covered(date)
            val day = day(date)
            when {
                day != null && !day.workday -> {
                    out.add(HolidayEntry(
                        id = "$KIND:${date}:${day.name}",
                        title = "${day.name}放假",
                        description = "${date} 放假。依据${day.notice}；" + (spanTitle(date) ?: "") + "。",
                        start = date.toString(),
                        end = date.toString(),
                        allDay = true,
                        kind = KIND,
                        notice = day.notice,
                    ))
                    date = date.plusDays(1)
                }
                day != null && day.workday -> {
                    out.add(HolidayEntry(
                        id = "$KIND:${date}:work",
                        title = "调休上班",
                        description = "${date} 按${day.notice}是调休上班日，不是休息日。",
                        start = date.toString(),
                        end = date.toString(),
                        allDay = true,
                        kind = KIND,
                        notice = day.notice,
                    ))
                    date = date.plusDays(1)
                }
                isWeekend(date) && (!covered || day == null) -> {
                    var end = date
                    while (end < to) {
                        val next = end.plusDays(1)
                        if (!isWeekend(next) || day(next) != null) break
                        end = next
                    }
                    out.add(HolidayEntry(
                        id = "$KIND:$date:$end",
                        title = if (date == end) "周六休息" else "周末休息",
                        description = if (covered) "$date${if (date == end) "" else " 至 $end"} 为常规周末。"
                        else "$date${if (date == end) "" else " 至 $end"} 为常规周末；该年度放假安排尚未发布，具体以官方通知为准。",
                        start = date.toString(),
                        end = end.toString(),
                        allDay = true,
                        kind = KIND,
                        notice = null,
                    ))
                    date = end.plusDays(1)
                }
                else -> date = date.plusDays(1)
            }
        }
        return out
    }

    /** 将 [from] 所在的连续放假日合并成一条，用于“今日/近期”摘要。 */
    fun spanTitle(date: LocalDate): String? {
        val day = day(date) ?: return null
        if (day.workday) return null
        var start = date; var end = date
        while (day(start.minusDays(1))?.let { !it.workday && it.name == day.name } == true) start = start.minusDays(1)
        while (day(end.plusDays(1))?.let { !it.workday && it.name == day.name } == true) end = end.plusDays(1)
        val rest = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1
        return "${day.name}共 $rest 天（${start} 至 ${end}）"
    }
}

/** 一个节假日/周末条目（全天）。 */
data class HolidayEntry(
    val id: String,
    val title: String,
    val description: String,
    val start: String,
    val end: String,
    val allDay: Boolean,
    val kind: String,
    val notice: String?,
)

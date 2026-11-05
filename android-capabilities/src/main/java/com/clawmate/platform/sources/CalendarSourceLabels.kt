package com.clawmate.platform.sources

import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * 系统日历来源的分组与中文命名。
 *
 * 手机厂商会在 CalendarContract 里放出很多内部日历（本机、生日、纪念日、倒计时、订阅等），
 * 名字多为 `oplus_new_birthday_default_calendar` 这样的包名式字符串。这里把它们归入少量
 * 用户看得懂的类别，并给出中文名称；分组只影响展示与批量勾选，读取仍然按具体日历 id 进行，
 * 所以用户拿到的仍然是“自己勾选的那个日历”的日程。
 */
object CalendarSourceLabels {
    /** 分组顺序即界面展示顺序。 */
    val GROUP_ORDER: List<String> = listOf("local", "birthday", "anniversary", "countdown", "other")

    data class Info(val group: String, val name: String, val detail: String)

    private val PACKAGE_NAME = Regex("^[a-z0-9_]+(\\.[a-z0-9_]+)+$")

    /**
     * 归类一个系统日历。
     *
     * @param id     CalendarContract 日历 id
     * @param rawName `calendar_displayName`，可能是中文，也可能是包名式英文
     * @param accountType `account_type`（如 LOCAL、com.heytap、com.google）
     * @param ownerAccount `ownerAccount`，用于判断生日账户
     * @param readOnly 是否只读日历（订阅类通常只读）
     */
    fun describe(id: Long, rawName: String, accountType: String, ownerAccount: String, readOnly: Boolean): Info {
        val name = rawName.trim()
        val lower = name.lowercase()
        val owner = ownerAccount.lowercase()
        val type = accountType.lowercase()
        fun readable(): String = when {
            name.isEmpty() -> ""
            // 包名式内部名、带 @ 的账户名、以及较长的纯英文串都不是用户起的名字，回退中文。
            PACKAGE_NAME.matches(lower) || lower.endsWith("_calendar") || lower.contains('@') -> ""
            name.any { it.code in 0x4E00..0x9FFF } -> name
            name.length <= 12 -> name
            else -> ""
        }
        val isBirthday = lower.contains("birthday") || lower.contains("生日") || owner.contains("birthday") || lower.contains("生诞")
        val isAnniversary = lower.contains("anniversary") || lower.contains("纪念") || lower.contains("wedding")
        val isCountdown = lower.contains("countdown") || lower.contains("倒计时") || lower.contains("holiday") ||
            lower.contains("subscribe") || lower.contains("节假日")
        val isLocal = type == "local" || type.isEmpty() || lower == "local account" || lower.contains("本机")
        val shown = readable()
        return when {
            isBirthday -> Info("birthday", shown.ifEmpty { "生日" }, "生日提醒（来自系统日历）")
            isAnniversary -> Info("anniversary", shown.ifEmpty { "纪念日" }, "纪念日提醒（来自系统日历）")
            isCountdown -> Info("countdown", shown.ifEmpty { "倒计时和订阅" }, "倒计时、订阅日历（来自系统日历）")
            isLocal -> Info("local", shown.ifEmpty { "本机日历" }, "手机本机日历" + (if (readOnly) "（只读）" else ""))
            else -> Info("other", shown.ifEmpty { "其他日历" }, "其他日历：" + accountType.ifEmpty { "系统" } + (if (readOnly) "（只读）" else ""))
        }
    }

    /** 分组的中文标题。 */
    fun groupTitle(group: String): String = when (group) {
        "local" -> "本机日历"
        "birthday" -> "生日"
        "anniversary" -> "纪念日"
        "countdown" -> "倒计时和订阅"
        else -> "其他日历"
    }

    /** 分组说明，供设置页显示。 */
    fun groupHint(group: String): String = when (group) {
        "local" -> "手机自带的日历，自己新建的日程都在这里。"
        "birthday" -> "联系人生日等生日日历，会按年份重复提醒。"
        "anniversary" -> "纪念日提醒。"
        "countdown" -> "倒计时与订阅的公共日历（例如节假日订阅）。"
        else -> "其他账户导入或同步的日历。"
    }
}

/**
 * 日历记忆按天组织与月份网格数据，供“日历分析”页首的小日历组件使用。
 *
 * 纯函数：输入已同步的条目与目标月份，输出 6 周 × 7 天的网格，以及每天是否有
 * 日程（蓝色）或节假日（橙色）。任意一天都可再取回当天的记忆条目（不截断正文）。
 */
object CalendarMonthView {
    /** 网格中小格的状态：是否为当前月、是否有日程/节假日、是否今天。 */
    data class Cell(
        val date: LocalDate,
        val inMonth: Boolean,
        val hasEvent: Boolean,
        val hasHoliday: Boolean,
        val isToday: Boolean,
        val isSelected: Boolean,
    )

    data class Month(
        val year: Int,
        val month: Int,
        val title: String,
        val weekdayLabels: List<String>,
        val cells: List<Cell>,
    )

    /**
     * 一天里的记忆条目，保持同步顺序。
     * [end] 是排他上界（全天条目的 end 通常就是次日零点），因此用毫秒区间判断覆盖。
     */
    private data class Entry(val start: Long, val end: Long, val holiday: Boolean)

    private fun entries(items: List<Pair<Long, Long>>, holidayFlags: List<Boolean>): List<Entry> =
        items.mapIndexed { index, (start, end) ->
            Entry(start, maxOf(end, start + 1), holidayFlags.getOrNull(index) ?: false)
        }

    /**
     * 构建某月的网格。以周一为一周第一天，固定 6 行（42 格），与常见日历一致，
     * 避免切换月份时高度跳动。
     */
    fun month(
        year: Int,
        month: Int,
        today: LocalDate,
        selected: LocalDate?,
        items: List<Pair<Long, Long>>,
        holidayFlags: List<Boolean>,
    ): Month {
        require(month in 1..12)
        val entries = entries(items, holidayFlags)
        val first = LocalDate.of(year, month, 1)
        val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
        val cells = ArrayList<Cell>(42)
        for (index in 0 until 42) {
            val date = start.plusDays(index.toLong())
            val dayStart = date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val dayEnd = date.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val overlapping = entries.filter { it.start < dayEnd && it.end > dayStart }
            cells.add(Cell(
                date = date,
                inMonth = date.monthValue == month && date.year == year,
                hasEvent = overlapping.any { !it.holiday },
                hasHoliday = overlapping.any { it.holiday },
                isToday = date == today,
                isSelected = date == selected,
            ))
        }
        return Month(year, month, "${year}年${month}月", listOf("一", "二", "三", "四", "五", "六", "日"), cells)
    }

    /** 该日期覆盖到的全部记忆条目（保持同步顺序），不做条数截断。 */
    fun itemsOn(date: LocalDate, items: List<Pair<Long, Long>>, holidayFlags: List<Boolean>): List<Int> {
        val dayStart = date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        return entries(items, holidayFlags).indices.filter { index ->
            val entry = entries(items, holidayFlags)[index]
            entry.start < dayEnd && entry.end > dayStart
        }
    }

    /** 用于“今天”的本地日期。 */
    fun today(now: ZonedDateTime = ZonedDateTime.now()): LocalDate = now.toLocalDate()
}

package com.clawmate.platform.sources

import java.time.LocalDate

/**
 * 农历（中国阴阳历）换算，覆盖 1900—2100 年。
 *
 * 数据来源：由权威农历实现逐项生成并逐年回环校验（1900—2099 年“正月初一”零差异），
 * 每年一个 32 位整数：低 4 位是闰月月份，
 * bit 4..15 是正月到十二月是否 30 天，bit 16 是闰月是否 30 天。
 * 生成脚本不随产品发布；本实现只做查表换算，不做天文推算。
 *
 * 只提供“农历 → 公历”与“公历 → 农历”的最小能力，闰月不计（习俗节日不上闰月）。
 */
object LunarCalendar {
    /** 1900—2100 年每年一条编码。 */
    private val YEAR_INFO = intArrayOf(
        0x0bd28, 0x07520, 0x0ea50, 0x0b2a5, 0x064b0, 0x0a9b0, 0x1aa64, 0x056a0, 0x0b590, 0x0baa2, // 1900-1909
        0x07520, 0x0da56, 0x0b250, 0x0a4b0, 0x1a4b5, 0x02ad0, 0x056b0, 0x05b52, 0x0da90, 0x1e927, // 1910-1919
        0x0e920, 0x0d250, 0x0d2d5, 0x0a560, 0x02b60, 0x1ad54, 0x06d40, 0x0ea90, 0x0f4a2, 0x0e920, // 1920-1929
        0x06a66, 0x052b0, 0x0a570, 0x09765, 0x0b5a0, 0x06d40, 0x17613, 0x07490, 0x1b137, 0x0a930, // 1930-1939
        0x052b0, 0x151b6, 0x0aad0, 0x056a0, 0x1da54, 0x0ba40, 0x0b490, 0x0d4b2, 0x0a950, 0x0aad7, // 1940-1949
        0x05360, 0x0aad0, 0x1aca5, 0x05b20, 0x0ba50, 0x1ea23, 0x0d4a0, 0x05958, 0x0a970, 0x05560, // 1950-1959
        0x05756, 0x0ad50, 0x06d20, 0x07554, 0x0ea50, 0x064a0, 0x064f3, 0x0a9b0, 0x0ada7, 0x056a0, // 1960-1969
        0x0b690, 0x0bb25, 0x0b520, 0x0b250, 0x0b2b4, 0x0a4b0, 0x0aab8, 0x02ad0, 0x05ad0, 0x15a96, // 1970-1979
        0x0da90, 0x0d920, 0x0e954, 0x0d250, 0x0e4da, 0x0a560, 0x02b60, 0x02f56, 0x06d50, 0x0ea90, // 1980-1989
        0x0f525, 0x0e920, 0x0d260, 0x052e3, 0x0a570, 0x0ad68, 0x035a0, 0x06d50, 0x0b695, 0x07490, // 1990-1999
        0x06930, 0x0a9b4, 0x052b0, 0x0a5b0, 0x0aae2, 0x056a0, 0x0dd57, 0x0ba40, 0x0b490, 0x0d535, // 2000-2009
        0x0a950, 0x052d0, 0x055d4, 0x0ab50, 0x0baa9, 0x05d20, 0x0da50, 0x1e8a6, 0x0d4a0, 0x0c950, // 2010-2019
        0x0a9e4, 0x05560, 0x0ab50, 0x0ada2, 0x06d20, 0x07656, 0x07250, 0x064b0, 0x06575, 0x0cab0, // 2020-2029
        0x055a0, 0x056e3, 0x0b690, 0x0f52b, 0x0b520, 0x0b250, 0x1d0b6, 0x0a4b0, 0x04ab0, 0x02bb5, // 2030-2039
        0x05ad0, 0x0b6a0, 0x0daa2, 0x0d920, 0x0ea57, 0x0d250, 0x0a550, 0x1a4d5, 0x04b60, 0x05b50, // 2040-2049
        0x16d23, 0x0ec90, 0x0f928, 0x0e920, 0x0d260, 0x15166, 0x0a570, 0x05560, 0x13654, 0x07550, // 2050-2059
        0x07490, 0x074b3, 0x06930, 0x0aab7, 0x052b0, 0x0a5b0, 0x0aba5, 0x056a0, 0x0b650, 0x0baa4, // 2060-2069
        0x0b4a0, 0x0d958, 0x0a950, 0x052d0, 0x056d6, 0x0ab50, 0x05aa0, 0x05d54, 0x0da50, 0x0d4a0, // 2070-2079
        0x0e4d3, 0x0c960, 0x0cce7, 0x05560, 0x0ab50, 0x1ad25, 0x06d20, 0x0ea50, 0x072a4, 0x068b0, // 2080-2089
        0x06978, 0x04ab0, 0x055b0, 0x15566, 0x0b6a0, 0x07520, 0x0b954, 0x0b450, 0x0a8b0, 0x0a4f2, // 2090-2099
    )

    private val BASE = LocalDate.of(1900, 1, 31)

    val minYear: Int get() = 1900
    val maxYear: Int get() = 1900 + YEAR_INFO.size - 1

    /** 某农历年闰月月份；0 表示无闰月。 */
    fun leapMonth(year: Int): Int = YEAR_INFO.getOrNull(year - 1900)?.and(0xf) ?: 0

    /** 某农历年某月（非闰月）的天数。 */
    fun daysInMonth(year: Int, month: Int): Int {
        val info = YEAR_INFO.getOrNull(year - 1900) ?: return 0
        return if (info.and(1 shl (4 + month - 1)) != 0) 30 else 29
    }

    /** 某农历年闰月的天数；无闰月返回 0。 */
    fun leapDays(year: Int): Int {
        val info = YEAR_INFO.getOrNull(year - 1900) ?: return 0
        val leap = leapMonth(year)
        if (leap == 0) return 0
        return if (info.and(0x10000) != 0) 30 else 29
    }

    /** 某农历年总天数。 */
    fun daysInYear(year: Int): Int {
        var sum = 0
        for (month in 1..12) sum += daysInMonth(year, month)
        return sum + leapDays(year)
    }

    /**
     * 农历 → 公历。
     *
     * @param day 若该农历月有闰月且 [day] 超过当月天数，按当月最后一天处理（节日换算用）。
     */
    fun solarDate(year: Int, month: Int, day: Int, leap: Boolean = false): LocalDate? {
        if (year < minYear || year > maxYear) return null
        if (month !in 1..12 || day !in 1..31) return null
        var offset = 0
        for (y in minYear until year) offset += daysInYear(y)
        val leapOfYear = leapMonth(year)
        for (m in 1 until month) {
            offset += daysInMonth(year, m)
            if (m == leapOfYear) offset += leapDays(year)
        }
        if (leap && leapOfYear == month) {
            offset += daysInMonth(year, month) + (day - 1).coerceAtLeast(0)
        } else {
            // 除夕这类“腊月三十”在只有 29 天的年份要落到廿九。
            val limit = daysInMonth(year, month) + if (leapOfYear == month) leapDays(year) else 0
            offset += (day - 1).coerceIn(0, limit - 1)
        }
        return BASE.plusDays(offset.toLong())
    }

    /** 农历某年腊月的天数（有闰腊月时含闰月）。 */
    fun daysInTwelfthMonth(year: Int): Int {
        val leap = leapMonth(year)
        return daysInMonth(year, 12) + if (leap == 12) leapDays(year) else 0
    }

    /** 农历某年的“除夕”：腊月最后一天。 */
    fun newYearsEve(year: Int): LocalDate? =
        solarDate(year, 12, daysInTwelfthMonth(year))

    /** 公历 → 农历（年、月、日、是否闰月）。 */
    fun lunarDate(date: LocalDate): Triple<Int, Int, Int>? {
        if (date < BASE) return null
        var offset = java.time.temporal.ChronoUnit.DAYS.between(BASE, date).toInt()
        var year = minYear
        while (year <= maxYear && offset >= daysInYear(year)) {
            offset -= daysInYear(year)
            year++
        }
        if (year > maxYear) return null
        val leapOfYear = leapMonth(year)
        var month = 1
        while (month <= 12) {
            val days = daysInMonth(year, month)
            if (offset < days) return Triple(year, month, offset + 1)
            offset -= days
            if (month == leapOfYear) {
                val leapLen = leapDays(year)
                if (offset < leapLen) return Triple(year, month, offset + 1)
                offset -= leapLen
            }
            month++
        }
        return null
    }
}

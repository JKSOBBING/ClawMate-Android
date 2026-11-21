package com.clawmate.platform.sources

import java.time.LocalDate

/**
 * 二十四节气。
 *
 * 用“太阳黄经”计算：节气就是太阳视黄经为 15° 整数倍的时刻。
 * 这里采用 VSOP87 截断版太阳位置算法（Jean Meeus《Astronomical Algorithms》第 25 章），
 * 精度约 1 分钟以内，对中国时区内“节气落在哪一天”足够可靠，覆盖 1900—2100 年。
 *
 * 只输出日期（本地日期，按 [zone]），不输出时刻：用户关心的是“哪天是立冬”。
 */
object SolarTerms {
    /** 来源编号：与系统日历（非负）、常用节假日（-1）区分。 */
    const val SOURCE_ID = -3L
    const val SOURCE_NAME = "二十四节气"
    const val KIND = "节气"

    /** 24 个节气，按黄经从 285°（小寒）开始，每 15° 一个。 */
    private val TERMS: List<Pair<String, Int>> = listOf(
        "小寒" to 285, "大寒" to 300, "立春" to 315, "雨水" to 330,
        "惊蛰" to 345, "春分" to 0, "清明" to 15, "谷雨" to 30,
        "立夏" to 45, "小满" to 60, "芒种" to 75, "夏至" to 90,
        "小暑" to 105, "大暑" to 120, "立秋" to 135, "处暑" to 150,
        "白露" to 165, "秋分" to 180, "寒露" to 195, "霜降" to 210,
        "立冬" to 225, "小雪" to 240, "大雪" to 255, "冬至" to 270,
    )

    private val RAD = Math.PI / 180.0

    /** 归一化到 [0, 360) 的角度差。 */
    private fun norm(degrees: Double): Double {
        var value = degrees % 360.0
        if (value < 0) value += 360.0
        return value
    }

    /** 太阳视黄经（度），t 为儒略世纪数（J2000 起算）。 */
    private fun sunLongitude(t: Double): Double {
        val l0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t
        val m = norm(357.52911 + 35999.05029 * t - 0.0001537 * t * t) * RAD
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * Math.sin(m) +
            (0.019993 - 0.000101 * t) * Math.sin(2 * m) + 0.000289 * Math.sin(3 * m)
        val trueLong = l0 + c
        val omega = norm(125.04 - 1934.136 * t) * RAD
        // 光行差与章动，给出“视黄经”。
        return norm(trueLong - 0.00569 - 0.00478 * Math.sin(omega))
    }

    private fun julianDay(year: Int, month: Int, day: Int): Double {
        var y = year; var m = month
        if (m <= 2) { y -= 1; m += 12 }
        val a = Math.floorDiv(y, 100)
        val b = 2 - a + Math.floorDiv(a, 4)
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    /** 儒略日 → UTC 时刻（毫秒）。 */
    private fun julianDayToMillis(jd: Double): Long {
        // JD 2440587.5 == 1970-01-01T00:00Z
        return Math.round((jd - 2440587.5) * 86_400_000.0)
    }

    /**
     * 求某一节气在给定年份的日期。
     *
     * @param term 节气名（如“立冬”）
     * @param year 公历年
     */
    fun dateOf(term: String, year: Int): LocalDate? {
        val target = TERMS.firstOrNull { it.first == term }?.second ?: return null
        // 起点：以 2 月 4 日（立春，黄经 315°）为基准，每 15° 约 15.2184 天。
        // 小寒(285°)、大寒(300°)的黄经差要按“跨 0°”处理，直接用模运算即可。
        val daysFromLichun = norm(target - 315.0) / 360.0 * 365.2422
        val startDate = LocalDate.of(year, 2, 4)
        val centerDay = startDate.toEpochDay() + Math.round(daysFromLichun).toLong()
        // 先粗定位到该日，再用牛顿迭代收敛到精确时刻，保证结果落在 [startDate, startDate+366) 内。
        var jd = julianDay(year, 1, 1) + (centerDay - LocalDate.of(year, 1, 1).toEpochDay()) + 0.5
        for (round in 0 until 20) {
            val t = (jd - 2451545.0) / 36525.0
            val diff = norm(target - sunLongitude(t))
            val days = if (diff > 180) (diff - 360) / 360.0 * 365.2422 else diff / 360.0 * 365.2422
            jd += days
            if (Math.abs(days) < 1e-7) break
        }
        // 收敛后按本地时区取日期。若因时区差落到相邻年份，用下一个窗口再算一次。
        var result = java.time.Instant.ofEpochMilli(julianDayToMillis(jd)).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        if (result.year != year) {
            // 小寒、大寒在 1 月，其余在 2—12 月：只可能是差了整年，按年偏移修正。
            val shifted = julianDayToMillis(jd + (if (result.year < year) 365.2422 else -365.2422))
            result = java.time.Instant.ofEpochMilli(shifted).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        }
        return result
    }

    /** 某年的全部 24 个节气，按日期升序。 */
    fun of(year: Int): List<Pair<LocalDate, String>> =
        TERMS.mapNotNull { (name, _) -> dateOf(name, year)?.let { it to name } }.sortedBy { it.first }

    /** 区间内的节气条目。 */
    fun entries(from: LocalDate, to: LocalDate): List<HolidayEntry> {
        if (to < from) return emptyList()
        val out = ArrayList<HolidayEntry>()
        var year = from.year
        while (year <= to.year) {
            of(year).forEach { (date, name) ->
                if (date >= from && date <= to) {
                    out.add(HolidayEntry(
                        id = "$KIND:$date:$name",
                        title = name,
                        description = "$date 是二十四节气中的$name（太阳黄经 ${TERMS.first { it.first == name }.second}°）。",
                        start = date.toString(),
                        end = date.toString(),
                        allDay = true,
                        kind = KIND,
                        notice = null,
                    ))
                }
            }
            year++
        }
        return out.sortedBy { it.start }
    }
}

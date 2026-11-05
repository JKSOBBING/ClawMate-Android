package com.clawmate.platform.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDate

class LunarCalendarTest {
    /** 已知对照：春节（农历正月初一）的公历日期。 */
    @Test fun springFestivalDatesMatchPublishedCalendar() {
        val expected = mapOf(
            2024 to LocalDate.of(2024, 2, 10),
            2025 to LocalDate.of(2025, 1, 29),
            2026 to LocalDate.of(2026, 2, 17),
            2027 to LocalDate.of(2027, 2, 6),
        )
        for ((year, date) in expected) {
            assertEquals("$year 春节", date, LunarCalendar.solarDate(year, 1, 1))
        }
    }

    /** 已知对照：中秋（八月十五）。 */
    @Test fun midAutumnDates() {
        assertEquals(LocalDate.of(2025, 10, 6), LunarCalendar.solarDate(2025, 8, 15))
        assertEquals(LocalDate.of(2026, 9, 25), LunarCalendar.solarDate(2026, 8, 15))
    }

    @Test fun roundTripForEveryDayOfSomeYears() {
        for (year in 2024..2027) {
            for (month in 1..12) {
                val days = LunarCalendar.daysInMonth(year, month)
                for (day in 1..days) {
                    val solar = LunarCalendar.solarDate(year, month, day) ?: continue
                    val back = LunarCalendar.lunarDate(solar)
                    assertNotNull("$year-$month-$day 无法回转", back)
                    assertEquals("$year-$month-$day 回转不一致：$back", month, back!!.second)
                    // 有闰月时，闰月中的日期回转后会标为同月，日一致即可。
                    assertEquals("$year-$month-$day 回转动日不一致", day, back.third)
                }
            }
        }
    }

    @Test fun leapMonthHandled() {
        // 2025 年有闰六月（农历）。
        assertEquals(6, LunarCalendar.leapMonth(2025))
        assertEquals(0, LunarCalendar.leapMonth(2026))
    }

    @Test fun yearLengthsAreReasonable() {
        for (year in 2000..2050) {
            val days = LunarCalendar.daysInYear(year)
            assertEquals("$year 农历年长度异常", true, days in 353..385)
        }
    }
}

package com.clawmate.platform.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SolarTermsTest {
    /** 已知权威日期（中国标准时间，来源：中国科学院紫金山天文台历算数据/常见日历）。 */
    @Test fun knownDatesMatchPublishedCalendar() {
        val cases = listOf(
            Triple("立春", 2026, LocalDate.of(2026, 2, 4)),
            Triple("春分", 2026, LocalDate.of(2026, 3, 20)),
            Triple("清明", 2026, LocalDate.of(2026, 4, 5)),
            Triple("夏至", 2026, LocalDate.of(2026, 6, 21)),
            Triple("立秋", 2026, LocalDate.of(2026, 8, 7)),
            Triple("秋分", 2026, LocalDate.of(2026, 9, 23)),
            Triple("立冬", 2026, LocalDate.of(2026, 11, 7)),
            // 2026 冬至发生在北京时间 12 月 22 日 04:49（UTC 12-21 20:49），
            // 不能按“冬至总在 21 日”想当然。
            Triple("冬至", 2026, LocalDate.of(2026, 12, 22)),
        )
        for ((term, year, expected) in cases) {
            assertEquals("$year $term", expected, SolarTerms.dateOf(term, year))
        }
    }

    @Test fun knownDatesForPastYears() {
        assertEquals(LocalDate.of(2024, 2, 4), SolarTerms.dateOf("立春", 2024))
        assertEquals(LocalDate.of(2024, 12, 21), SolarTerms.dateOf("冬至", 2024))
        assertEquals(LocalDate.of(2025, 11, 7), SolarTerms.dateOf("立冬", 2025))
        assertEquals(LocalDate.of(2025, 6, 21), SolarTerms.dateOf("夏至", 2025))
        // 2025 冬至在北京时间 23:01，仍在 21 日。
        assertEquals(LocalDate.of(2025, 12, 21), SolarTerms.dateOf("冬至", 2025))
    }

    @Test fun everyYearHas24TermsInOrder() {
        for (year in 2024..2030) {
            val terms = SolarTerms.of(year)
            assertEquals("$year 应有 24 个节气", 24, terms.size)
            assertEquals("$year 应有 24 个不重复名称", 24, terms.map { it.second }.toSet().size)
            // 按日期升序，且相邻节气间隔在 14—17 天之间。
            for (index in 1 until terms.size) {
                val gap = java.time.temporal.ChronoUnit.DAYS.between(terms[index - 1].first, terms[index].first)
                assertTrue("$year 第 $index 个节气间隔异常：$gap 天", gap in 14..17)
            }
        }
    }

    @Test fun entriesOnlyCoverRequestedRange() {
        val from = LocalDate.of(2026, 11, 1)
        val to = LocalDate.of(2026, 11, 30)
        val entries = SolarTerms.entries(from, to)
        // 2026 年 11 月：立冬(11-07)、小雪(11-22)
        assertEquals(listOf("立冬", "小雪"), entries.map { it.title })
        assertTrue(entries.all { LocalDate.parse(it.start) in from..to })
    }

    @Test fun unknownTermReturnsNull() {
        assertEquals(null, SolarTerms.dateOf("不存在", 2026))
    }
}

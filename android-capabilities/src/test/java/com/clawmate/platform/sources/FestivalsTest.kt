package com.clawmate.platform.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class FestivalsTest {
    private fun dateOf(year: Int, name: String): LocalDate? =
        Festivals.of(year).firstOrNull { it.second == name }?.first

    @Test fun chineseFestivalsFollowLunarCalendar() {
        assertEquals(LocalDate.of(2026, 2, 17), dateOf(2026, "春节"))
        assertEquals(LocalDate.of(2026, 3, 3), dateOf(2026, "元宵节"))
        assertEquals(LocalDate.of(2026, 9, 25), dateOf(2026, "中秋节"))
        assertEquals(LocalDate.of(2026, 10, 18), dateOf(2026, "重阳节"))
    }

    @Test fun newYearsEveFallsOnLastDayOfLunarYear() {
        // 2025 农历腊月只有 29 天：除夕应为 2026-02-16，而不是“腊月三十”缺席。
        assertEquals(LocalDate.of(2026, 2, 16), dateOf(2026, "除夕"))
    }

    @Test fun westernFestivalsUseCorrectRules() {
        assertEquals(LocalDate.of(2026, 2, 14), dateOf(2026, "情人节"))
        // 母亲节：2026 年 5 月第二个星期日 = 5 月 10 日
        val mothers = dateOf(2026, "母亲节")!!
        assertEquals(DayOfWeek.SUNDAY, mothers.dayOfWeek)
        assertEquals(LocalDate.of(2026, 5, 10), mothers)
        // 父亲节：6 月第三个星期日 = 6 月 21 日
        assertEquals(LocalDate.of(2026, 6, 21), dateOf(2026, "父亲节"))
        // 感恩节：11 月第四个星期四 = 11 月 26 日
        val thanks = dateOf(2026, "感恩节")!!
        assertEquals(DayOfWeek.THURSDAY, thanks.dayOfWeek)
        assertEquals(LocalDate.of(2026, 11, 26), thanks)
        assertEquals(LocalDate.of(2026, 12, 25), dateOf(2026, "圣诞节"))
    }

    @Test fun entriesRespectCategoryFilter() {
        val from = LocalDate.of(2026, 1, 1)
        val to = LocalDate.of(2026, 12, 31)
        val chinese = Festivals.entries(from, to, setOf(Festivals.Category.CHINESE))
        assertTrue(chinese.any { it.title == "春节" })
        assertFalse(chinese.any { it.title == "圣诞节" })
        val western = Festivals.entries(from, to, setOf(Festivals.Category.WESTERN))
        assertTrue(western.any { it.title == "圣诞节" })
        assertFalse(western.any { it.title == "春节" })
    }

    @Test fun entriesStayInsideRange() {
        val from = LocalDate.of(2026, 11, 1)
        val to = LocalDate.of(2026, 11, 30)
        val entries = Festivals.entries(from, to)
        assertTrue(entries.isNotEmpty())
        assertTrue(entries.all { LocalDate.parse(it.start) in from..to })
        // 11 月应有感恩节（11 月第四个星期四）；万圣节前夜在 10-31，不在 11 月。
        assertTrue(entries.any { it.title == "感恩节" })
        assertFalse(entries.any { it.title == "万圣节前夜" })
        assertTrue(Festivals.entries(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
            .any { it.title == "万圣节前夜" })
    }

    @Test fun catalogCoversAllCategories() {
        val catalog = Festivals.catalog()
        assertTrue(catalog.size >= 30)
        for (category in Festivals.Category.values()) {
            assertTrue("缺少类别 ${category.label}", catalog.any { it.second == category })
        }
        assertEquals(catalog.size, catalog.map { it.first }.distinct().size)
    }

    @Test fun categoryKeysRoundTrip() {
        for (category in Festivals.Category.values()) {
            assertEquals(category, Festivals.categoryOf(category.key))
        }
        assertEquals(null, Festivals.categoryOf("unknown"))
    }
}

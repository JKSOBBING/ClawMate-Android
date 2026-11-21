package com.clawmate.platform.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ChinaHolidaysTest {
    private fun day(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day)

    @Test fun labeledDaysComeFromOfficialNotices() {
        assertEquals("春节", ChinaHolidays.day(day(2026, 2, 17))?.name)
        assertFalse(ChinaHolidays.day(day(2026, 2, 17))!!.workday)
        assertEquals("春节调休上班", ChinaHolidays.day(day(2026, 2, 28))?.name)
        assertTrue(ChinaHolidays.day(day(2026, 2, 28))!!.workday)
        assertEquals("国庆节", ChinaHolidays.day(day(2026, 10, 3))?.name)
        // 2026年国庆 10月10日补班。
        assertTrue(ChinaHolidays.day(day(2026, 10, 10))!!.workday)
        assertNull(ChinaHolidays.day(day(2027, 1, 1)))
    }

    @Test fun entriesSeparateHolidaysWeekendsAndMakeUpWorkdays() {
        val entries = ChinaHolidays.entries(day(2026, 10, 1), day(2026, 10, 11))
        val titles = entries.map { it.title }
        assertTrue(titles.contains("国庆节放假"))
        assertTrue(titles.contains("调休上班"))
        // 10月8日—9日不是假期，也不补班：不产生条目。
        assertFalse(entries.any { it.start == "2026-10-08" })
        // 2026年10月10日周六是补班日，必须标记为上班而不是休息。
        val saturday = entries.firstOrNull { it.start == "2026-10-10" }
        assertNotNull(saturday)
        assertEquals("调休上班", saturday!!.title)
        // 普通周末不再生成条目（用户明确要求不要周末信息）。
        assertFalse(titles.any { it.contains("周末") || it.contains("周六休息") })
    }

    @Test fun weekendsAreNotRecordedAsHolidayEntries() {
        // 2027 年通知尚未发布：既没有节日条目，也不能退化成“周末条目”。
        assertTrue(ChinaHolidays.entries(day(2027, 1, 1), day(2027, 1, 10)).isEmpty())
        // 2026 年 11 月只有周末、没有法定节日：同样不产生条目。
        assertTrue(ChinaHolidays.entries(day(2026, 11, 1), day(2026, 11, 30)).isEmpty())
    }

    @Test fun springFestivalSpanIsContiguousAcrossDays() {
        for (d in 15..23) {
            val entry = ChinaHolidays.day(day(2026, 2, d))
            assertNotNull("2026-02-$d 应为春节假期", entry)
            assertEquals("春节", entry!!.name)
        }
        assertEquals("2026年放假安排", ChinaHolidays.day(day(2026, 2, 19))?.notice)
    }

    @Test fun holidayCountsMatchNotices() {
        val spring = (1..28).count { ChinaHolidays.day(day(2026, 2, it))?.name == "春节" && !ChinaHolidays.day(day(2026, 2, it))!!.workday }
        assertEquals(9, spring)
        val national = (1..31).count { ChinaHolidays.day(day(2026, 10, it))?.name == "国庆节" && !ChinaHolidays.day(day(2026, 10, it))!!.workday }
        assertEquals(7, national)
    }
}

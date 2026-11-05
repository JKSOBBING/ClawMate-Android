package com.clawmate.platform.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CalendarSourceLabelsTest {
    private fun info(name: String, type: String = "com.heytap", owner: String = "", readOnly: Boolean = false) =
        CalendarSourceLabels.describe(1, name, type, owner, readOnly)

    @Test fun localAccountBecomesReadableChineseName() {
        val local = CalendarSourceLabels.describe(1, "local account", "LOCAL", "local account", false)
        assertEquals("local", local.group)
        assertEquals("本机日历", local.name)
    }

    @Test fun birthdayCalendarsMergeIntoOneGroup() {
        val birthdayAccount = info("birthday@localhost", owner = "birthday@localhost")
        assertEquals("birthday", birthdayAccount.group)
        assertEquals("生日", birthdayAccount.name)
        val newBirthday = info("oplus_new_birthday_default_calendar")
        assertEquals("birthday", newBirthday.group)
        assertEquals("生日", newBirthday.name)
    }

    @Test fun anniversaryAndCountdownGroups() {
        assertEquals("anniversary", info("oplus_anniversary_default_calendar").group)
        assertEquals("纪念日", info("oplus_anniversary_default_calendar").name)
        assertEquals("countdown", info("oplus_countdown_default_calendar").group)
        assertEquals("倒计时和订阅", info("oplus_countdown_default_calendar").name)
    }

    @Test fun readableNamesAreKept() {
        assertEquals("家庭", info("家庭").name)
        assertEquals("工作日", info("工作日", type = "com.google").name)
    }

    @Test fun unknownCalendarsFallIntoOtherGroup() {
        val other = info("My_Work_Calendar", type = "com.example")
        assertEquals("other", other.group)
        assertTrue(other.detail.contains("其他日历"))
        assertFalse(other.name.contains("_"))
    }
}

class CalendarMonthViewTest {
    private fun at(year: Int, month: Int, day: Int, hour: Int = 0): Long =
        LocalDate.of(year, month, day).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() +
            hour * 3600_000L

    @Test fun gridAlwaysHasSixWeeksAndStartsOnMonday() {
        val month = CalendarMonthView.month(2026, 11, LocalDate.of(2026, 11, 5), null, emptyList(), emptyList())
        assertEquals(42, month.cells.size)
        assertEquals(1, month.cells[0].date.dayOfWeek.value) // 周一
        assertEquals("2026年11月", month.title)
        assertEquals(7, month.weekdayLabels.size)
    }

    @Test fun onlyCurrentMonthCellsAreMarkedInMonth() {
        val month = CalendarMonthView.month(2026, 11, LocalDate.of(2026, 11, 5), null, emptyList(), emptyList())
        val inMonth = month.cells.filter { it.inMonth }
        assertEquals(30, inMonth.size)
        assertTrue(inMonth.all { it.date.monthValue == 11 })
    }

    @Test fun holidayAndEventDotsAreSeparate() {
        val items = listOf(at(2026, 11, 7) to at(2026, 11, 8), at(2026, 11, 6, 9) to at(2026, 11, 6, 10))
        val flags = listOf(true, false)
        val month = CalendarMonthView.month(2026, 11, LocalDate.of(2026, 11, 5), null, items, flags)
        val holiday = month.cells.first { it.date == LocalDate.of(2026, 11, 7) }
        assertTrue("11月7日应为节假日", holiday.hasHoliday)
        assertFalse(holiday.hasEvent)
        val event = month.cells.first { it.date == LocalDate.of(2026, 11, 6) }
        assertTrue(event.hasEvent)
        assertFalse(event.hasHoliday)
    }

    @Test fun todayAndSelectionAreMarked() {
        val month = CalendarMonthView.month(2026, 11, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 9), emptyList(), emptyList())
        assertTrue(month.cells.first { it.date == LocalDate.of(2026, 11, 5) }.isToday)
        assertTrue(month.cells.first { it.date == LocalDate.of(2026, 11, 9) }.isSelected)
    }

    @Test fun multiDayItemsAppearOnEveryCoveredDay() {
        val items = listOf(at(2026, 11, 6) to at(2026, 11, 8))
        val flags = listOf(false)
        assertTrue(CalendarMonthView.itemsOn(LocalDate.of(2026, 11, 6), items, flags).isNotEmpty())
        assertTrue(CalendarMonthView.itemsOn(LocalDate.of(2026, 11, 7), items, flags).isNotEmpty())
        assertTrue(CalendarMonthView.itemsOn(LocalDate.of(2026, 11, 9), items, flags).isEmpty())
    }

    @Test fun itemsOnReturnsIndexesInSyncOrder() {
        val items = listOf(at(2026, 11, 6, 9) to at(2026, 11, 6, 10), at(2026, 11, 6, 8) to at(2026, 11, 6, 9))
        assertEquals(listOf(0, 1), CalendarMonthView.itemsOn(LocalDate.of(2026, 11, 6), items, listOf(false, false)))
    }
}

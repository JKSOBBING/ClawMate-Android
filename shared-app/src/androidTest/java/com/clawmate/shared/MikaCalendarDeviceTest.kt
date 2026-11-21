package com.clawmate.shared

import android.content.*
import android.content.pm.PackageManager
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.platform.app.InstrumentationRegistry
import com.clawmate.platform.sources.PersonalSourceEngine
import com.clawmate.platform.sources.SolarTerms
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class MikaCalendarDeviceTest {
    @Test fun unifiedCalendarIncludesHiddenSubscriptionsAndPrefersProviderTerms() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "unified-calendar-${System.nanoTime()}").apply { mkdirs() }
        val today = LocalDate.now(); val zone = ZoneId.systemDefault()
        val term = SolarTerms.entries(today, today.plusDays(30)).first()
        val begin = LocalDate.parse(term.start).atStartOfDay(zone).toInstant().toEpochMilli()
        val writable = listOf(
            7L to "local account",
            9L to "oplus_new_birthday_default_calendar",
            10L to "oplus_anniversary_default_calendar",
            11L to "oplus_countdown_default_calendar",
        )
        var granted = true
        var saved: ContentValues? = null
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri {
                assertEquals(CalendarContract.Events.CONTENT_URI, uri)
                assertNull(saved)
                saved = ContentValues(requireNotNull(values))
                return ContentUris.withAppendedId(uri, 73)
            }
            override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = error("read only")
            override fun delete(uri: Uri, s: String?, a: Array<out String>?) = error("read only")
            override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): android.database.Cursor {
                val c = MatrixCursor(columns!!)
                if (uri == CalendarContract.Calendars.CONTENT_URI) {
                    if (selection == null) {
                        for ((id, name) in writable) c.addRow(arrayOf(id, name, "LOCAL", "", 700, 1))
                        c.addRow(arrayOf(8L, "节气订阅", "LOCAL", "", 200, 0))
                    } else {
                        assertTrue(selection.contains(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL))
                        for ((id, name) in writable) c.addRow(arrayOf(id, name))
                    }
                } else {
                    assertEquals(setOf("7", "8", "9", "10", "11"), args!!.toSet())
                    c.addRow(columns.map { when (it) {
                        "event_id" -> 9L; "calendar_id" -> 8L; "title" -> term.title
                        "begin" -> begin; "end" -> begin + 86400000L; "allDay" -> 1
                        "eventTimezone" -> zone.id; else -> ""
                    } }.toTypedArray())
                    saved?.let { row -> c.addRow(columns.map { when (it) {
                        "event_id" -> 73L
                        "begin" -> row.getAsLong("dtstart")
                        "end" -> row.getAsLong("dtend")
                        else -> row[it]
                    } }.toTypedArray()) }
                }
                return c
            }
        }
        val resolver = ContentResolver.wrap(provider)
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = dir
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(dir.name + name, mode)
            override fun getContentResolver() = resolver
            override fun checkSelfPermission(permission: String) = if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
        try {
            PersonalSourceEngine(isolated).use { engine ->
                engine.saveOptions("calendar", """{"calendarEnabled":true,"historyDays":0,"selected":[7]}""")
                val targets = JSONArray(engine.execute("calendarTargets", "{}"))
                val idAndName = (0 until targets.length()).map {
                    val target = targets.getJSONObject(it)
                    target.getString("id") to target.getString("name")
                }
                assertEquals(listOf("7" to "本机日历", "9" to "生日", "10" to "纪念日", "11" to "倒计时"), idAndName)
                val event = JSONObject().put("title", "日历读写测试").put("start", begin).put("end", begin + 3600000L)
                    .put("calendarId", "8")
                assertThrows(IllegalStateException::class.java) { engine.execute("calendarInsert", event.toString()) }
                event.put("calendarId", idAndName.single { it.second == "纪念日" }.first)
                granted = false
                assertThrows(IllegalStateException::class.java) { engine.execute("calendarInsert", event.toString()) }
                assertNull(saved)
                granted = true
                val receipt = JSONObject(engine.execute("calendarInsert", event.toString()))
                assertEquals(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, 73).toString(), receipt.getString("uri"))
                val raw = engine.execute("calendar", "{}")
                val items = JSONObject(raw).getJSONArray("items")
                val written = (0 until items.length()).map { items.getJSONObject(it) }.single { it.getString("title") == "日历读写测试" }
                assertEquals(10L, written.getLong("calendarId")); assertEquals(begin, written.getLong("start"))
                assertEquals(begin + 3600000L, written.getLong("end"))
                val same = (0 until items.length()).map { items.getJSONObject(it) }.filter { it.getString("title") == term.title }
                assertEquals(1, same.size); assertEquals("system", same.single().getString("source"))
                engine.write("calendar", raw); granted = false
                assertEquals("{}", engine.read("calendar"))
                assertThrows(IllegalStateException::class.java) { engine.execute("calendar", "{}") }
                granted = true
                engine.saveOptions("calendar", """{"calendarEnabled":false,"historyDays":0,"selected":[]}""")
                assertEquals("{}", engine.read("calendar"))
                assertEquals(0, JSONObject(engine.execute("calendar", """{"quick":true}""")).getJSONArray("items").length())
            }
        } finally {
            base.deleteSharedPreferences(dir.name + "personal_sources")
            base.deleteSharedPreferences(dir.name + "personal_source_options")
            check(dir.canonicalPath.startsWith(base.cacheDir.canonicalPath + File.separator))
            dir.deleteRecursively()
        }
    }
}

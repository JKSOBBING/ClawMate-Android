package com.clawmate.shared

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.clawmate.platform.sources.PersonalSourceEngine
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PersonalSourcesDeviceTest {
    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    fun calendarQueriesUseSelectedSourcesAndStoredWindowWithoutWritingProvider() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "calendar-fixture-${System.nanoTime()}").apply { mkdirs() }
        var eventReads = 0
        var granted = true
        var queriedStart = 0L
        val provider = object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: android.net.Uri): String? = null
            override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = error("read only")
            override fun delete(uri: android.net.Uri, selection: String?, args: Array<out String>?) = error("read only")
            override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, args: Array<out String>?) = error("read only")
            override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): android.database.Cursor {
                val cursor = android.database.MatrixCursor(projection!!)
                if (uri == android.provider.CalendarContract.Calendars.CONTENT_URI) cursor.addRow(arrayOf(7L, "Fixture calendar"))
                else {
                    eventReads++
                    assertEquals("calendar_id IN (?)", selection); assertArrayEquals(arrayOf("7"), args)
                    queriedStart = uri.pathSegments[uri.pathSegments.size - 2].toLong()
                    val now = System.currentTimeMillis()
                    cursor.addRow(arrayOf(1L, 7L, "Fixture event", "Synthetic body", "", now, now + 3600000, 0, "Asia/Shanghai"))
                }
                return cursor
            }
        }
        val resolver = android.content.ContentResolver.wrap(provider)
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(directory.name + name, mode)
            override fun getContentResolver() = resolver
            override fun checkSelfPermission(permission: String) = if (granted) android.content.pm.PackageManager.PERMISSION_GRANTED else android.content.pm.PackageManager.PERMISSION_DENIED
        }
        PersonalSourceEngine(isolated).use { engine ->
            try {
                assertEquals(1, JSONObject(engine.execute("calendar", "{\"listOnly\":true}")).getJSONArray("sources").length())
                assertEquals(0, eventReads)
                for (days in listOf(0, 30, 90, 365)) {
                    engine.saveOptions("calendar", "{\"calendarEnabled\":true,\"historyDays\":$days,\"selected\":[7]}")
                    val raw = engine.execute("calendar", "{}")
                    val result = JSONObject(raw)
                    assertEquals(1, result.getJSONArray("items").length())
                    val expected = java.time.LocalDate.now().minusDays(days.toLong()).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    assertEquals(expected, queriedStart); assertEquals(expected, result.getLong("windowStart"))
                    engine.write("calendar", raw)
                    assertEquals(1, JSONObject(engine.read("calendar")).getJSONArray("items").length())
                }
                granted = false
                assertEquals("{}", engine.read("calendar"))
                assertThrows(IllegalStateException::class.java) { engine.execute("calendar", "{}") }
                assertEquals(4, eventReads)
            } finally {
                base.deleteSharedPreferences(directory.name + "personal_sources")
                base.deleteSharedPreferences(directory.name + "personal_source_options")
                directory.walkBottomUp().forEach { it.delete() }
            }
        }
    }

    @Test fun encryptedCredentialsAndAtomicSnapshotsAreIsolatedAndClearable() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "sources-fixture-${System.nanoTime()}").apply { mkdirs() }
        val prefsName = directory.name
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefsName + "-" + name, mode)
        }
        val engine = PersonalSourceEngine(isolated)
        try {
            assertFalse(JSONObject(engine.options()).getBoolean("calendarEnabled"))
            assertEquals(90, JSONObject(engine.options()).getInt("historyDays"))
            engine.saveOptions("calendar", "{\"calendarEnabled\":true,\"historyDays\":30,\"selected\":[7]}")
            engine.saveOptions("mail", "{\"maxMessages\":20}")
            assertThrows(IllegalArgumentException::class.java) { engine.saveOptions("mail", "{\"maxMessages\":999}") }
            assertThrows(IllegalArgumentException::class.java) { engine.saveOptions("calendar", "{\"calendarEnabled\":true,\"historyDays\":999,\"selected\":[]}") }
            engine.saveCredentials("imap.example.invalid", 993, "fixture@example.invalid", "fixture-secret")
            val preferences = base.getSharedPreferences(prefsName + "-personal_sources", Context.MODE_PRIVATE)
            assertFalse(preferences.getString("secret", "")!!.contains("fixture-secret"))
            assertFalse(engine.settings().contains("fixture-secret"))
            engine.saveCredentials("imap.example.invalid", 993, "fixture@example.invalid", "") // decrypt retained secret
            engine.write("mail", JSONObject().put("syncedAt", 123).put("items", org.json.JSONArray()).toString())
            assertEquals(123, JSONObject(engine.read("mail")).getInt("syncedAt"))
            engine.configureOAuth("google", "fixture@example.invalid")
            assertFalse(preferences.contains("secret")); assertEquals("{}", engine.read("mail"))
            assertEquals("imap.gmail.com", JSONObject(engine.settings()).getString("host"))
            assertEquals("google", JSONObject(engine.settings()).getString("mode"))
            assertThrows(IllegalStateException::class.java) { engine.oauthToken("google", "fixture@example.invalid") }
            engine.configureOAuth("microsoft", "fixture@example.invalid", "public-fixture-id", "common")
            assertEquals("outlook.office365.com", JSONObject(engine.settings()).getString("host"))
            assertEquals("public-fixture-id", JSONObject(engine.settings()).getString("client"))
            engine.forgetMail()
            val restored = JSONObject(PersonalSourceEngine(isolated).use { it.options() })
            assertEquals(30, restored.getInt("historyDays")); assertEquals(20, restored.getInt("maxMessages"))
            assertTrue(restored.getBoolean("calendarEnabled")); assertEquals(7, restored.getJSONArray("selected").getInt(0))
            // Controlled snapshot: disabling calendar clears only the app-owned copy and retains choices.
            File(directory, "personal-sources/calendar.json").writeText("{\"items\":[],\"syncedAt\":123}")
            engine.saveOptions("calendar", "{\"calendarEnabled\":false,\"historyDays\":30,\"selected\":[7]}")
            assertFalse(File(directory, "personal-sources/calendar.json").exists())
            assertEquals("{}", engine.read("calendar"))
            assertEquals("{}", engine.read("mail"))
            assertEquals("", JSONObject(engine.settings()).getString("username"))
            if (!engine.calendarGranted()) {
                assertEquals("{}", engine.read("calendar"))
                assertThrows(IllegalStateException::class.java) { engine.execute("calendar", "{\"selected\":[]}") }
            }
        } finally {
            engine.close(); base.deleteSharedPreferences(prefsName + "-personal_sources"); base.deleteSharedPreferences(prefsName + "-personal_source_options")
            directory.walkBottomUp().forEach { it.delete() }
        }
    }
}

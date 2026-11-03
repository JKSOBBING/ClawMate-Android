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
    @Test fun encryptedCredentialsAndAtomicSnapshotsAreIsolatedAndClearable() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "sources-fixture-${System.nanoTime()}").apply { mkdirs() }
        val prefsName = directory.name
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefsName, mode)
        }
        val engine = PersonalSourceEngine(isolated)
        try {
            engine.saveCredentials("imap.example.invalid", 993, "fixture@example.invalid", "fixture-secret")
            val preferences = base.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            assertFalse(preferences.getString("secret", "")!!.contains("fixture-secret"))
            assertFalse(engine.settings().contains("fixture-secret"))
            engine.saveCredentials("imap.example.invalid", 993, "fixture@example.invalid", "") // decrypt retained secret
            engine.write("mail", JSONObject().put("syncedAt", 123).put("items", org.json.JSONArray()).toString())
            assertEquals(123, JSONObject(engine.read("mail")).getInt("syncedAt"))
            engine.forgetMail(); assertEquals("{}", engine.read("mail"))
            assertEquals("", JSONObject(engine.settings()).getString("username"))
            if (!engine.calendarGranted()) {
                assertEquals("{}", engine.read("calendar"))
                assertThrows(IllegalStateException::class.java) { engine.execute("calendar", "{\"selected\":[]}") }
            }
        } finally {
            engine.close(); base.deleteSharedPreferences(prefsName)
            directory.walkBottomUp().forEach { it.delete() }
        }
    }
}

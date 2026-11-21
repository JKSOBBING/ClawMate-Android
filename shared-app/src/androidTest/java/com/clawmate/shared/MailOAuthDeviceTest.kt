package com.clawmate.shared

import android.app.Activity
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.clawmate.platform.sources.MailOAuth
import com.clawmate.platform.sources.PersonalSourceEngine
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Production state machine, synthetic endpoint responses. Does not contact a provider. */
class MailOAuthDeviceTest {
    private fun activity(): Activity {
        lateinit var result: Activity
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = Activity() }
        return result
    }
    private fun fixture(beforeCreate: (ContextWrapper) -> Unit = {}, test: (PersonalSourceEngine) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "oauth-fixture-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(directory.name + "-" + name, mode)
        }
        beforeCreate(isolated)
        val engine = PersonalSourceEngine(isolated)
        try { test(engine) } finally {
            engine.close(); base.deleteSharedPreferences(directory.name + "-personal_sources"); base.deleteSharedPreferences(directory.name + "-personal_source_options"); directory.walkBottomUp().forEach { it.delete() }
        }
    }
    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) { check(System.nanoTime() < until) { "Timed out" }; Thread.sleep(10) }
    }
    private fun device() = JSONObject().put("device_code", "private-device-code").put("user_code", "DISPLAY-CODE")
        .put("verification_uri", "https://microsoft.com/devicelogin").put("expires_in", 900).put("interval", 5)
    private val client = "11111111-1111-1111-1111-111111111111"
    private val user = "fixture@example.invalid"

    @Test fun sjtuRequiresJAccountAndStopsAcceptingAnExpiredToken() = fixture(beforeCreate = { context ->
        context.getSharedPreferences("personal_sources", 0).edit()
            .putString("host", "MAIL.SJTU.EDU.CN").putString("user", "fixture").putString("mode", "password")
            .putString("secret", "legacy-test-value").commit()
        File(context.filesDir, "personal-sources").mkdirs()
        File(context.filesDir, "personal-sources/mail.json").writeText("""{"syncedAt":123,"items":[]}""")
    }) { engine ->
        val time = AtomicLong(100000)
        assertEquals("jaccount", JSONObject(engine.settings()).getString("mode"))
        assertEquals(123L, JSONObject(engine.read("mail")).getLong("syncedAt"))
        assertThrows(IllegalArgumentException::class.java) {
            engine.saveCredentials("MAIL.SJTU.EDU.CN", 993, "fixture@sjtu.edu.cn", "unused")
        }
        MailOAuth(activity(), engine, { provider, _, form ->
            assertEquals("jaccount", provider)
            assertEquals("fixture-code", form["code"])
            JSONObject().put("access_token", "fixture-token").put("expires_in", 120)
        }, time::get).use { auth ->
            auth.startJAccount("fixture", client, "clawmate://jaccount", "fixture-secret")
            val state = android.net.Uri.parse(JSONObject(auth.status()).getString("uri")).getQueryParameter("state")
            auth.completeJAccount("code=fixture-code&state=$state")
            await { JSONObject(auth.status()).getString("state") == "done" }
            assertEquals(123L, JSONObject(engine.read("mail")).getLong("syncedAt"))
            assertEquals("jaccount", JSONObject(engine.settings()).getString("mode"))
            assertEquals("fixture-token", engine.oauthToken("jaccount", "fixture@sjtu.edu.cn"))
            time.addAndGet(120000)
            assertThrows(IllegalStateException::class.java) { engine.execute("mailOpen", "{}") }
            auth.startJAccount("second", client, "clawmate://jaccount", "fixture-secret")
            assertEquals(123L, JSONObject(engine.read("mail")).getLong("syncedAt"))
            val nextState = android.net.Uri.parse(JSONObject(auth.status()).getString("uri")).getQueryParameter("state")
            auth.completeJAccount("code=fixture-code&state=$nextState")
            await { JSONObject(auth.status()).getString("state") == "done" }
            assertEquals("{}", engine.read("mail"))
            assertEquals("second@sjtu.edu.cn", JSONObject(engine.settings()).getString("username"))
        }
    }

    @Test fun devicePollingHonorsIntervalsAndBindsTokenToAccountThenExpires() = fixture { engine ->
        val time = AtomicLong(100000); val requests = AtomicInteger(0)
        engine.configureOAuth("microsoft", user, client)
        engine.write("mail", """{"syncedAt":456,"items":[]}""")
        MailOAuth(activity(), engine, { directory, path, form ->
            assertEquals("common", directory); assertEquals(client, form["client_id"])
            if (path == "devicecode") { assertEquals(MailOAuth.SCOPE, form["scope"]); device() }
            else {
                assertEquals("private-device-code", form["device_code"])
                when (requests.incrementAndGet()) {
                    1 -> JSONObject().put("error", "authorization_pending")
                    2 -> JSONObject().put("error", "slow_down")
                    else -> JSONObject().put("access_token", "synthetic-token").put("expires_in", 3600)
                }
            }
        }, time::get).use { auth ->
            auth.start("microsoft", user, client, "common")
            await { JSONObject(auth.status()).optString("code") == "DISPLAY-CODE" }
            assertFalse(auth.status().contains("private-device-code")); assertEquals(0, requests.get())
            time.addAndGet(5000); auth.status(); await { requests.get() == 1 }; Thread.sleep(50)
            auth.status(); assertEquals(1, requests.get())
            time.addAndGet(5000); auth.status(); await { requests.get() == 2 }; Thread.sleep(50)
            time.addAndGet(5000); auth.status(); assertEquals(2, requests.get())
            time.addAndGet(5000); auth.status(); await { JSONObject(auth.status()).optString("state") == "done" }
            assertEquals("synthetic-token", engine.oauthToken("microsoft", user))
            assertEquals(456L, JSONObject(engine.read("mail")).getLong("syncedAt"))
            assertFalse(auth.status().contains("synthetic-token")); assertFalse(engine.settings().contains("synthetic-token"))
            assertThrows(IllegalStateException::class.java) { engine.oauthToken("microsoft", "other@example.invalid") }
            time.addAndGet(3600000); assertEquals("error", JSONObject(auth.status()).getString("state"))
            assertThrows(IllegalStateException::class.java) { engine.oauthToken("microsoft", user) }
        }
    }
    @Test fun cancelDiscardsLateDeviceResponseAndExpiryNeverPolls() = fixture { engine ->
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        MailOAuth(activity(), engine, { _, _, _ -> started.countDown(); release.await(5, TimeUnit.SECONDS); device() }).use { auth ->
            auth.start("microsoft", user, client, "common"); assertTrue(started.await(5, TimeUnit.SECONDS))
            auth.cancel(); release.countDown(); Thread.sleep(100)
            assertEquals("idle", JSONObject(auth.status()).getString("state")); assertFalse(auth.status().contains("DISPLAY-CODE"))
            assertThrows(IllegalStateException::class.java) { engine.oauthToken("microsoft", user) }
        }
        val time = AtomicLong(100000); val polls = AtomicInteger(0)
        MailOAuth(activity(), engine, { _, path, _ -> if (path == "token") polls.incrementAndGet(); device() }, time::get).use { auth ->
            auth.start("microsoft", user, client, "common")
            await { JSONObject(auth.status()).optString("code") == "DISPLAY-CODE" }
            time.addAndGet(901000)
            assertEquals("error", JSONObject(auth.status()).getString("state")); assertEquals(0, polls.get())
        }
    }
    @Test fun denialAndInvalidRegistrationCannotProduceToken() = fixture { engine ->
        val time = AtomicLong(100000)
        MailOAuth(activity(), engine, { _, path, _ ->
            if (path == "devicecode") device() else JSONObject().put("error", "authorization_declined")
        }, time::get).use { auth ->
            assertThrows(IllegalArgumentException::class.java) { auth.start("microsoft", user, "not-client-id", "common") }
            assertEquals("", JSONObject(engine.settings()).getString("username"))
            auth.start("microsoft", user, client, "common")
            await { JSONObject(auth.status()).optString("code") == "DISPLAY-CODE" }
            time.addAndGet(5000); auth.status()
            await { JSONObject(auth.status()).optString("state") == "error" }
            assertFalse(auth.status().contains("DISPLAY-CODE"))
            assertThrows(IllegalStateException::class.java) { engine.oauthToken("microsoft", user) }
        }
    }
}

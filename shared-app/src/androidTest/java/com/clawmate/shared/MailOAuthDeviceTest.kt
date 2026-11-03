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
    private fun fixture(test: (PersonalSourceEngine) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "oauth-fixture-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(directory.name, mode)
        }
        val engine = PersonalSourceEngine(isolated)
        try { test(engine) } finally {
            engine.close(); base.deleteSharedPreferences(directory.name); directory.walkBottomUp().forEach { it.delete() }
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

    @Test fun devicePollingHonorsIntervalsAndBindsTokenToAccountThenExpires() = fixture { engine ->
        val time = AtomicLong(100000); val requests = AtomicInteger(0)
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

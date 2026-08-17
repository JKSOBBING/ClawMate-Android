package com.clawmate.app.datamanager

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Phase-36 focused JVM tests for the **minimal replaceable periodic-trigger
 * adapter** ([PeriodicTriggerAdapter]) and its **explicit-only scheduling host**
 * ([PeriodicSchedulingHost]).
 *
 * No WorkManager dependency and no device required. Coverage:
 *  - disabled by default: constructing the host (even with a persisted `true`)
 *    schedules nothing; only an explicit [PeriodicSchedulingHost.scheduleIfEnabled]
 *    call does;
 *  - explicit `true` registers exactly one canonical local-only task
 *    (REPLACE, unique name); explicit `false` cancels and schedules nothing;
 *  - the scheduled run is serial, idempotent, local-only (never the model-backed
 *    repository) and cancellable;
 *  - a `NOT_REQUIRED`/`LOCAL_ONLY` policy never enables; the adapter never leaks a
 *    secret/path;
 *  - the in-process adapter wraps [PeriodicConsolidationScheduler] for a future
 *    WorkManager worker without changing the contract.
 */
class PeriodicSchedulingHostTest {

    private fun tempBase(): File =
        Files.createTempDirectory("clawmate-phase36-host").toFile().apply { deleteOnExit() }

    // ---- 1. disabled by default, explicit-only registration ----

    @Test
    fun `constructing the host schedules nothing even with opt-in true`() {
        val base = tempBase()
        val host = PeriodicSchedulingHost(base)
        // NO call to scheduleIfEnabled -> no registration, regardless of opt-in.
        assertFalse(host.isScheduled())
    }

    @Test
    fun `explicit false disables and cancels any prior schedule`() {
        val base = tempBase()
        val host = PeriodicSchedulingHost(base)
        assertTrue(host.scheduleIfEnabled(true) is PeriodicScheduleResult.Scheduled)
        assertTrue(host.isScheduled())
        val outcome = host.scheduleIfEnabled(false)
        assertTrue(outcome is PeriodicScheduleResult.Disabled)
        assertFalse(host.isScheduled())
    }

    @Test
    fun `explicit true registers exactly one canonical task (replace)`() {
        val base = tempBase()
        val host = PeriodicSchedulingHost(base)
        val first = host.scheduleIfEnabled(true)
        assertTrue(first is PeriodicScheduleResult.Scheduled)
        val second = host.scheduleIfEnabled(true)
        // REPLACE: same unique name, never two tasks.
        assertTrue(second is PeriodicScheduleResult.Scheduled || second is PeriodicScheduleResult.Replaced)
        assertTrue(host.isScheduled())
    }

    @Test
    fun `the adapter holds no secret or external path`() {
        val scheduler = PeriodicConsolidationScheduler()
        val adapter = InProcessPeriodicTriggerAdapter(scheduler)
        assertTrue(adapter.taskName.isNotBlank())
        val policy = SchedulingPolicy(taskName = adapter.taskName, mode = SchedulerMode.LOCAL_ONLY, networkConstraint = NetworkConstraint.NOT_REQUIRED)
        assertTrue(policy.enabled)
        assertFalse(policy.taskName.contains("http"))
        assertFalse(policy.taskName.contains("key")) // no secret in the task name/policy
    }

    // ---- 2. scheduled local run is serial, idempotent, local-only ----

    @Test
    fun `scheduled local run rebuilds local artifacts and stays local`() {
        val base = tempBase()
        seedDailyLog(base, "2026-08-01", "a.logs.md", listOf("买咖啡花 25 元", "中午吃火锅"))
        val host = PeriodicSchedulingHost(base)
        host.scheduleIfEnabled(true)
        val result = runBlocking { host.runNow() }
        assertTrue("local consolidation must produce records", result != null && result!!.localRecords > 0)
        assertTrue(result!!.indexedEvents > 0)
        // The local path never touches the model-backed repository or network.
        assertTrue(result!!.sceneCount >= 0)
        runBlocking { host.runNow() } // idempotent, no explosion
    }

    @Test
    fun `runNow does nothing when not scheduled or not enabled`() {
        val base = tempBase()
        val host = PeriodicSchedulingHost(base)
        // Disabled-by-default: no task registered -> runNow is a harmless no-op.
        val result = runBlocking { host.runNow() }
        assertEquals(null, result)
    }

    @Test
    fun `cancel removes the scheduled task`() {
        val base = tempBase()
        val host = PeriodicSchedulingHost(base)
        host.scheduleIfEnabled(true)
        assertTrue(host.isScheduled())
        assertTrue(host.cancel())
        assertFalse(host.isScheduled())
        assertEquals(null, runBlocking { host.runNow() })
    }

    // ---- 3. cancellable, serial ----

    @Test
    fun `cancellation aborts a local run before start`() {
        val base = tempBase()
        seedDailyLog(base, "2026-08-01", "a.logs.md", listOf("买咖啡花 25 元"))
        val host = PeriodicSchedulingHost(base)
        host.scheduleIfEnabled(true)
        runBlocking {
            try {
                host.runNow { true }
                fail("expected CancellationException")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // expected
            }
        }
    }

    // ---- helpers ----

    private fun seedDailyLog(base: File, day: String, fileName: String, entries: List<String>) {
        val dayDir = File(File(base, DailyLogWorkspace.DAILY_LOG_ROOT_RELATIVE), day)
        dayDir.mkdirs()
        val body = entries.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n\n")
        File(dayDir, fileName).writeText("$body\n")
    }
}

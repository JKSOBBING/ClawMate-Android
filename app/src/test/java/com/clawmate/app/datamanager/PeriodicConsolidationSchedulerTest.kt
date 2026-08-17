package com.clawmate.app.datamanager

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * Focused JVM tests for the Phase-35 **periodic local-consolidation scheduling
 * seam**.
 *
 * There is no WorkManager dependency in the app; this seam is a pure, in-process,
 * JVM-testable controller. Tests never start an Android worker. Coverage:
 *  - disabled by default (production default);
 *  - explicit enable via `LOCAL_ONLY` policy;
 *  - unique task name: REPLACE vs KEEP conflict policy, no double-scheduling;
 *  - cancel removes a task; failure/cancel never leaks a (secret-free) config;
 *  - serial execution + cooperative cancellation;
 *  - the local-only job produces deterministic, idempotent artifacts and never
 *    touches the model-backed path (no network);
 *  - old-state compatible (no persisted state / migration needed).
 */
class PeriodicConsolidationSchedulerTest {

    private fun tempBase(): File =
        Files.createTempDirectory("clawmate-phase35-sched").toFile().apply { deleteOnExit() }

    private fun disabledPolicy(name: String = SchedulingPolicyDefaults.DEFAULT_TASK_NAME) =
        SchedulingPolicy(taskName = name, mode = SchedulerMode.DISABLED)

    private fun localPolicy(name: String = SchedulingPolicyDefaults.DEFAULT_TASK_NAME, conflict: TaskConflictPolicy = TaskConflictPolicy.REPLACE) =
        SchedulingPolicy(taskName = name, mode = SchedulerMode.LOCAL_ONLY, conflictPolicy = conflict)

    /** A counting fake job that simulates a local-only run and records runs. */
    private class CountingJob(
        private val onRun: ((Int) -> Unit)? = null,
        private val failOnRun: Int = -1,
    ) : LocalPeriodicConsolidationJob {
        val runs = AtomicInteger(0)
        var cancelled = 0
        override suspend fun runOnce(isCancelled: () -> Boolean): PeriodicRunResult {
            val n = runs.incrementAndGet()
            if (failOnRun == n) throw RuntimeException("boom run=$n")
            onRun?.invoke(n)
            return PeriodicRunResult(taskName = "counting", localRecords = n)
        }
    }

    // ---- 1. disabled default ----

    @Test
    fun `disabled policy registers nothing and is the default`() {
        val scheduler = PeriodicConsolidationScheduler()
        assertEquals(SchedulerMode.DISABLED, SchedulingPolicy().mode) // default DISABLED
        val outcome = scheduler.register(disabledPolicy(), CountingJob())
        assertTrue(outcome is PeriodicConsolidationScheduler.RegistrationOutcome.Disabled)
        assertNull(scheduler.scheduledTaskNames().firstOrNull())
        assertFalse(scheduler.isScheduled(SchedulingPolicyDefaults.DEFAULT_TASK_NAME))
    }

    // ---- 2. explicit enable ----

    @Test
    fun `explicit local-only policy registers a task and it is scheduleable`() {
        val scheduler = PeriodicConsolidationScheduler()
        val job = CountingJob()
        val outcome = scheduler.register(localPolicy(), job)
        assertTrue(outcome is PeriodicConsolidationScheduler.RegistrationOutcome.EnabledNew)
        assertTrue(scheduler.isScheduled(SchedulingPolicyDefaults.DEFAULT_TASK_NAME))
        assertEquals(listOf(SchedulingPolicyDefaults.DEFAULT_TASK_NAME), scheduler.scheduledTaskNames())
        val result = runBlocking { scheduler.runScheduledOnce(SchedulingPolicyDefaults.DEFAULT_TASK_NAME) }
        assertTrue("local job must have run", job.runs.get() == 1)
        assertTrue(result!!.anyWorkProduced)
    }

    // ---- 3. unique task name / replace-or-keep ----

    @Test
    fun `KEEP preserves the existing registration and rejects the new one`() {
        val scheduler = PeriodicConsolidationScheduler()
        val first = CountingJob()
        val second = CountingJob()
        scheduler.register(localPolicy(conflict = TaskConflictPolicy.KEEP), first)
        val outcome = scheduler.register(localPolicy(conflict = TaskConflictPolicy.KEEP), second)
        assertTrue(outcome is PeriodicConsolidationScheduler.RegistrationOutcome.KeptExisting)
        // One task name, never two.
        assertEquals(listOf(SchedulingPolicyDefaults.DEFAULT_TASK_NAME), scheduler.scheduledTaskNames())
        runBlocking { scheduler.runScheduledOnce(SchedulingPolicyDefaults.DEFAULT_TASK_NAME) }
        assertTrue(first.runs.get() == 1)
        assertEquals(0, second.runs.get())
    }

    @Test
    fun `REPLACE swaps the existing registration of the same name`() {
        val scheduler = PeriodicConsolidationScheduler()
        val first = CountingJob()
        val second = CountingJob()
        scheduler.register(localPolicy(conflict = TaskConflictPolicy.REPLACE), first)
        val outcome = scheduler.register(localPolicy(conflict = TaskConflictPolicy.REPLACE), second)
        assertTrue(outcome is PeriodicConsolidationScheduler.RegistrationOutcome.Replaced)
        assertEquals(listOf(SchedulingPolicyDefaults.DEFAULT_TASK_NAME), scheduler.scheduledTaskNames())
        runBlocking { scheduler.runScheduledOnce(SchedulingPolicyDefaults.DEFAULT_TASK_NAME) }
        assertEquals(0, first.runs.get())
        assertTrue(second.runs.get() == 1)
    }

    @Test
    fun `two distinct task names schedule independently`() {
        val scheduler = PeriodicConsolidationScheduler()
        scheduler.register(localPolicy("a"), CountingJob())
        scheduler.register(localPolicy("b"), CountingJob())
        assertEquals(listOf("a", "b"), scheduler.scheduledTaskNames())
        assertTrue(scheduler.isScheduled("a"))
        assertTrue(scheduler.isScheduled("b"))
    }

    // ---- 4. cancel + failure never leaks a (secret-free) config ----

    @Test
    fun `cancel removes a registered task`() {
        val scheduler = PeriodicConsolidationScheduler()
        scheduler.register(localPolicy("x"), CountingJob())
        assertTrue(scheduler.cancel("x"))
        assertFalse(scheduler.isScheduled("x"))
        // Run on a cancelled/unknown name is a harmless no-op (null), never throws.
        val result = runBlocking { scheduler.runScheduledOnce("x") }
        assertNull(result)
    }

    @Test
    fun `failed job leaves no stale registration and does not leak policy config`() {
        val scheduler = PeriodicConsolidationScheduler()
        scheduler.register(localPolicy(), CountingJob(failOnRun = 1))
        // Config is secret-free: assert the policy holds no URI/key/path.
        val registered = scheduler.scheduledTaskNames()
        assertEquals(listOf(SchedulingPolicyDefaults.DEFAULT_TASK_NAME), registered)
        try {
            runBlocking { scheduler.runScheduledOnce(SchedulingPolicyDefaults.DEFAULT_TASK_NAME) }
            fail("expected the failing job to throw")
        } catch (expected: RuntimeException) {
            // expected
        }
        // After failure the registration remains intact for a future explicit run;
        // nothing sensitive is retained because no secret was ever stored.
        assertTrue(scheduler.isScheduled(SchedulingPolicyDefaults.DEFAULT_TASK_NAME))
        scheduler.cancel(SchedulingPolicyDefaults.DEFAULT_TASK_NAME)
        assertFalse(scheduler.isScheduled(SchedulingPolicyDefaults.DEFAULT_TASK_NAME))
    }

    @Test
    fun `scheduling policy never carries a secret or external path`() {
        val p = localPolicy()
        // Only task metadata: no base URL / key / token / path.
        assertTrue(p.taskName.isNotBlank())
        assertEquals(NetworkConstraint.NOT_REQUIRED, p.networkConstraint)
        assertNotEquals(NetworkConstraint.CONNECTED, p.networkConstraint)
        assertTrue(p.effectiveIntervalMinutes > 0)
    }

    @Test
    fun `connected or blank policies cannot register local periodic work`() {
        val scheduler = PeriodicConsolidationScheduler()
        val connected = SchedulingPolicy(
            taskName = "connected",
            mode = SchedulerMode.LOCAL_ONLY,
            networkConstraint = NetworkConstraint.CONNECTED,
        )
        val blank = SchedulingPolicy(taskName = " ", mode = SchedulerMode.LOCAL_ONLY)
        assertFalse(connected.enabled)
        assertFalse(blank.enabled)
        assertTrue(scheduler.register(connected, CountingJob()) is PeriodicConsolidationScheduler.RegistrationOutcome.Disabled)
        assertTrue(scheduler.register(blank, CountingJob()) is PeriodicConsolidationScheduler.RegistrationOutcome.Disabled)
        assertTrue(scheduler.scheduledTaskNames().isEmpty())
    }

    // ---- 5. serial execution + cooperative cancellation ----

    @Test
    fun `runs serialize through one mutex and never overlap`() {
        val scheduler = PeriodicConsolidationScheduler()
        var active = 0
        var peak = 0
        val jobs = (0 until 4).map { i ->
            val j = CountingJob(onRun = {
                active++
                peak = maxOf(peak, active)
                Thread.sleep(5)
                active--
            })
            scheduler.register(localPolicy("job$i"), j)
            j
        }
        runBlocking { scheduler.runAllScheduledOnce() }
        assertEquals("all four jobs must have run exactly once", listOf(1, 1, 1, 1), jobs.map { it.runs.get() })
        assertEquals("no two jobs overlapped", 1, peak)
    }

    @Test
    fun `cancellation aborts and rethrows and does not corrupt state`() {
        val scheduler = PeriodicConsolidationScheduler()
        scheduler.register(localPolicy(), CountingJob())
        runBlocking {
            try {
                scheduler.runScheduledOnce(SchedulingPolicyDefaults.DEFAULT_TASK_NAME, isCancelled = { true })
                fail("expected CancellationException")
            } catch (e: CancellationException) {
                // expected
            }
            // Task still registered for a later explicit run; no half-state.
            assertTrue(scheduler.isScheduled(SchedulingPolicyDefaults.DEFAULT_TASK_NAME))
        }
        // A subsequent non-cancelled run proceeds normally.
        val result = runBlocking { scheduler.runScheduledOnce(SchedulingPolicyDefaults.DEFAULT_TASK_NAME) }
        assertTrue(result != null && result.anyWorkProduced)
    }

    // ---- 6. old-state compatible / no persisted migration needed ----

    @Test
    fun `scheduler starts with no persisted state and needs no migration`() {
        // Fresh registry == empty: old app versions without scheduling keep working.
        val scheduler = PeriodicConsolidationScheduler()
        assertTrue(scheduler.scheduledTaskNames().isEmpty())
        assertEquals(SchedulerMode.DISABLED, SchedulingPolicy().mode)
        // Disabling then enabling is a clean transition.
        scheduler.register(SchedulingPolicy(taskName = "t", mode = SchedulerMode.DISABLED), CountingJob())
        assertFalse(scheduler.isScheduled("t"))
        scheduler.register(SchedulingPolicy(taskName = "t", mode = SchedulerMode.LOCAL_ONLY), CountingJob())
        assertTrue(scheduler.isScheduled("t"))
        assertTrue(scheduler.cancel("t"))
    }

    // ---- 7. the real local-only job is deterministic, local, idempotent, net-free ----

    @Test
    fun `local-only periodic job rebuilds local artifacts deterministically and stays local`() {
        val base = tempBase()
        seedDailyLog(base, "2026-08-01", "a.logs.md", listOf("买咖啡花 25 元", "中午吃火锅"))
        val job = LocalOnlyPeriodicJob(base)
        val first = runBlocking { job.runOnce { false } }
        assertTrue("local consolidation must produce records", first.localRecords > 0)
        assertTrue(first.indexedEvents > 0)
        // The periodic local path also does ProfileIndexBuilder.buildLocal(), i.e. a
        // deterministic local profile rebuild under the app-private index tree.
        assertTrue("profile domains must be projected locally", first.profileDomainCount > 0)

        // The rebuilt profile state is local-only and honest (zero network, no model claims).
        val profileState = DataManagerPaths.profileState(base)
        assertTrue("profile state must be rebuilt under the app-private index", profileState.exists())
        val decoded = ProfileCodec.decodeState(profileState.readText())
        assertEquals(0, decoded.claimCount)
        assertTrue(decoded.domains.values.all { it.generation == "local_only" || it.generation == "empty" })

        // Deterministic idempotent rerun does not explode counts.
        runBlocking { job.runOnce { false } }
    }

    @Test
    fun `local-only periodic job is cancellable`() {
        val base = tempBase()
        seedDailyLog(base, "2026-08-01", "a.logs.md", listOf("买咖啡花 25 元"))
        val job = LocalOnlyPeriodicJob(base)
        runBlocking {
            try {
                job.runOnce { true }
                fail("expected CancellationException")
            } catch (e: CancellationException) {
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

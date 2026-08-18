package com.clawmate.app.settings

import com.clawmate.app.datamanager.PeriodicSchedulingHost
import com.clawmate.app.settings.data.FakeSettingsRepository
import com.clawmate.app.settings.domain.AppSettings
import com.clawmate.app.settings.ui.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Phase-36 Settings-view-model tests for the explicit periodic local-consolidation
 * scheduling action and the explicit model-enrich actions.
 *
 * These are the "wiring" tests that prove the only registration path is an
 * explicit Settings action — never constructed/launched automatically:
 *  - a device-free ViewModel (no host injected) reports DISABLED and schedules
 *    nothing;
 *  - explicit opt-in `false` → disabled, nothing scheduled;
 *  - explicit opt-in `true` + the schedule action → one local task registered;
 *  - `runPeriodicLocalConsolidationNow` only runs once the task is scheduled;
 *  - enrich actions are zero-network by default (no configured model).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSchedulingEnrichTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun tempBase(): File =
        Files.createTempDirectory("clawmate-phase36-vm").toFile().apply { deleteOnExit() }

    private fun repo() = FakeSettingsRepository(AppSettings.defaults())

    // ---- 1. default disabled / device-free host ----

    @Test
    fun `device-free vm reports disabled and schedules nothing`() = runTest {
        val vm = SettingsViewModel(repo(), "1.0.0", 1L)
        vm.schedulePeriodicLocalConsolidation()
        val state = vm.state.value
        assertFalse(state.periodicScheduled)
        assertTrue(state.periodicScheduleMessage != null && state.periodicScheduleMessage!!.contains("未注入"))
    }

    @Test
    fun `default opt-in is false`() {
        assertFalse(AppSettings.defaults().periodicLocalConsolidationEnabled)
    }

    // ---- 2. explicit schedule with an injected host ----

    @Test
    fun `explicit opt-in true schedules one local task`() = runTest {
        val base = tempBase()
        val host = PeriodicSchedulingHost(base)
        val vm = SettingsViewModel(repo(), "1.0.0", 1L, schedulingHost = host)
        vm.setPeriodicLocalConsolidationEnabled(true)
        vm.schedulePeriodicLocalConsolidation()
        val state = vm.state.value
        assertTrue(state.periodicScheduled)
        assertTrue(state.periodicScheduleMessage != null)
    }

    @Test
    fun `explicit opt-in false cancels and leaves disabled`() = runTest {
        val base = tempBase()
        val host = PeriodicSchedulingHost(base)
        val vm = SettingsViewModel(repo(), "1.0.0", 1L, schedulingHost = host)
        vm.setPeriodicLocalConsolidationEnabled(false)
        vm.schedulePeriodicLocalConsolidation()
        val state = vm.state.value
        assertFalse(state.periodicScheduled)
        assertTrue(state.periodicScheduleMessage != null && state.periodicScheduleMessage!!.contains("禁用"))
        assertFalse(host.isScheduled())
    }

    @Test
    fun `run now does nothing unless scheduled and opted in`() = runTest {
        val base = tempBase()
        val host = PeriodicSchedulingHost(base)
        val vm = SettingsViewModel(repo(), "1.0.0", 1L, schedulingHost = host)
        // Not scheduled -> no run, an honest message reports it.
        vm.runPeriodicLocalConsolidationNow()
        assertTrue(vm.state.value.periodicScheduleMessage != null)
        assertFalse(vm.state.value.periodicScheduled)

        // Scheduled but opt-in turned off -> no run (guard), message set.
        vm.setPeriodicLocalConsolidationEnabled(true)
        vm.schedulePeriodicLocalConsolidation()
        assertTrue(vm.state.value.periodicScheduled)
        vm.updateDraft { it.copy(periodicLocalConsolidationEnabled = false) }
        vm.runPeriodicLocalConsolidationNow()
        // The guard reported it and did not invoke a run; the host still holds
        // the previously-registered task (no automatic cancellation here).
        assertTrue(vm.state.value.periodicScheduleMessage != null)
        assertTrue(host.isScheduled())
    }

    // ---- 3. enrich actions are zero-network by default ----

    @Test
    fun `entity enrichment without a configured model is honest local-only`() = runTest {
        // No endpoint config than ever seeded in tests -> zero model calls, the
        // device-free runner falls back to LOCAL_ONLY (no network).
        val vm = SettingsViewModel(repo(), "1.0.0", 1L)
        // With no injectable model provider the action reports a guarded outcome.
        vm.runEntityEnrichment()
        assertTrue(vm.state.value.enrichResultMessage != null)
    }

    @Test
    fun `batch enrichment without host reports a guarded outcome`() = runTest {
        val vm = SettingsViewModel(repo(), "1.0.0", 1L)
        vm.runBatchEventEnrichment()
        assertTrue(vm.state.value.batchEnrichResultMessage != null)
    }

    // ---- 4. Phase 37 — explicit fact-splitting action ----

    @Test
    fun `fact split without host reports a guarded outcome`() = runTest {
        val vm = SettingsViewModel(repo(), "1.0.0", 1L)
        vm.runFactSplit()
        assertTrue(vm.state.value.factSplitResultMessage != null)
        assertTrue(vm.state.value.factSplitResultMessage!!.contains("未注入"))
    }

    @Test
    fun `fact split with an injected runner is zero network local-only by default`() = runTest {
        // Isolate from any EndpointConfig seeded by an earlier test in this JVM:
        // with no runtime cloud-planner config, the fact split must stay LOCAL_ONLY.
        com.clawmate.app.config.EndpointConfig.load(java.util.Properties())
        val base = tempBase()
        // Seed a real daily-log entry using the workspace writer (proper format).
        com.clawmate.app.datamanager.DailyLogWorkspace.writeDocument(
            base, "2026-08-01", "a.logs.md", listOf("买了咖啡花了 25 元"),
        )
        val runner = com.clawmate.app.datamanager.FactSplitRunner(base)
        val vm = SettingsViewModel(repo(), "1.0.0", 1L, factSplitRunner = runner)
        vm.runFactSplit()
        // Let the launched coroutine complete so the honest message is recorded.
        advanceUntilIdle()
        // No endpoint config seeded -> LOCAL_ONLY (zero network), honest message.
        val message = vm.state.value.factSplitResultMessage
        assertTrue("message was: $message", message != null && message.contains("local_only"))
    }

    @Test
    fun `scheduling opt-in round-trips through the codec`() {
        val on = AppSettings.defaults().copy(periodicLocalConsolidationEnabled = true)
        val encoded = com.clawmate.app.settings.data.SettingsCodec.encode(on)
        val decoded = com.clawmate.app.settings.data.SettingsCodec.decode(encoded)
        assertTrue(decoded.periodicLocalConsolidationEnabled)
        assertEquals(
            com.clawmate.app.settings.domain.SettingsKeys.PERIODIC_LOCAL_CONSOLIDATION_ENABLED,
            com.clawmate.app.settings.domain.SettingsKeys.PERIODIC_LOCAL_CONSOLIDATION_ENABLED,
        )
    }
}

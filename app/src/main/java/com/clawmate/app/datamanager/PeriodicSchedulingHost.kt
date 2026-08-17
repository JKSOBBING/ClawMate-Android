package com.clawmate.app.datamanager

import java.io.File

/**
 * Phase-36 **periodic local-consolidation scheduling host** — the controller that
 * binds the persisted `periodicLocalConsolidationEnabled` user opt-in to the
 * minimal replaceable [PeriodicTriggerAdapter].
 *
 * ## Explicit-only, disabled-by-default
 * The host never schedules anything by itself. [scheduleIfEnabled] is the **only**
 * registration path and must be invoked from an explicit user action (a Settings
 * click), passing the *current* persisted opt-in in as [scheduleIfEnabled]'s
 * [enabled]. It:
 *  - when [enabled] is **false** (the production default) cancels any prior
 *    registration and schedules nothing ([PeriodicScheduleResult.Disabled]);
 *  - when [enabled] is **true** registers one `LOCAL_ONLY + NOT_REQUIRED`
 *    [SchedulingPolicy] with the canonical local job via the [adapter].
 *
 * Nothing here runs on `Application.onCreate`, Home load, recomposition, or a
 * plain constructor. Constructing the host (even with a persisted `true`) does
 * **not** schedule — only an explicit [scheduleIfEnabled] / [runNow] call does.
 *
 * ## Local-only, serial, idempotent, cancellable
 * The job is [LocalOnlyPeriodicJob] (deterministic local consolidation / index /
 * profile rebuild). It never references [CloudConsolidationRepository] and never
 * opens the network. [runNow] triggers one local run serially (the adapter's
 * single task name keeps it replaceable with a real WorkManager worker later).
 */
class PeriodicSchedulingHost(
    private val baseDir: File,
    private val adapter: PeriodicTriggerAdapter = InProcessPeriodicTriggerAdapter(
        PeriodicConsolidationScheduler(),
    ),
) {

    /**
     * The single, replaceable registration entry — explicit caller only. The
     * caller passes the currently-persisted opt-in: `true` schedules the one
     * local-only task, `false` cancels it and schedules nothing.
     */
    fun scheduleIfEnabled(enabled: Boolean): PeriodicScheduleResult {
        if (!enabled) {
            adapter.cancel()
            return PeriodicScheduleResult.Disabled(adapter.taskName)
        }
        return adapter.schedule(
            policy = SchedulingPolicy(
                taskName = adapter.taskName,
                mode = SchedulerMode.LOCAL_ONLY,
                intervalMinutes = SchedulingPolicyDefaults.DEFAULT_INTERVAL_MINUTES,
                networkConstraint = NetworkConstraint.NOT_REQUIRED,
                conflictPolicy = TaskConflictPolicy.REPLACE,
            ),
            job = LocalOnlyPeriodicJob(baseDir),
        )
    }

    /** Explicitly cancel the scheduled local task. */
    fun cancel(): Boolean = adapter.cancel()

    /** Whether the canonical local task is currently scheduled. */
    fun isScheduled(): Boolean = adapter.isScheduled()

    /**
     * Explicitly trigger one local consolidation run now. Runs only when the task
     * is scheduled and still enabled; cancellable; never touches the model.
     */
    suspend fun runNow(isCancelled: () -> Boolean = { false }): PeriodicRunResult? =
        adapter.runScheduledOnce(isCancelled)
}

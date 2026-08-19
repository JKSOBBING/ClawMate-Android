package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.platform.JobSchedulerGateway
import com.clawmate.app.datamanager.platform.ScheduleResult

/**
 * Phase-46 **real Android JobScheduler-backed periodic trigger adapter**.
 *
 * This is a [PeriodicTriggerAdapter] implementation that keeps all deterministic
 * decisions in the existing [PeriodicConsolidationScheduler] and, when a task is
 * actually enabled, *also* registers one persisted framework job with the real
 * [JobSchedulerGateway] (JobScheduler on a device, a fake in JVM tests).
 *
 * ## Reuses the existing engine — never a second copy
 * `schedule()` stores the caller's [LocalPeriodicConsolidationJob] (the canonical
 * [LocalOnlyPeriodicJob]) in the in-memory scheduler under the unique canonical
 * task name *and* submits a persisted periodic `JobInfo` via the
 * [JobSchedulerGateway] pointing at [PeriodicLocalConsolidationJobService]. The
 * service re-derives the same local-only job after app/device restart, so a run
 * uses the exact same [LocalConsolidationIndexRunner]-backed work.
 *
 * ## Registration contract
 *  - [schedule] with a `DISABLED` / non-`LOCAL_ONLY` policy registers nothing and
 *    cancels any prior job (same as Phase-35/36 in-memory contract);
 *  - only an *enabled* [SchedulingPolicy] submits a persisted periodic job;
 *  - the same canonical [taskName] can never be scheduled twice — a replace
 *    reuses the single `JOB_ID`.
 *
 * ## Cancel / replace / failure honesty
 *  - [cancel] removes the in-memory registration and cancels the OS job;
 *  - if the framework rejects the schedule ([ScheduleResult.Failure]) the adapter
 *    does **not** claim success: it cancels the in-memory registration and
 *    returns [PeriodicScheduleResult.Disabled] with the failure recorded, so the
 *    UI reports a truthful not-scheduled state rather than faking availability.
 *
 * ## Serial / bounded execution
 * [runScheduledOnce] stays serial through the scheduler's single `Mutex`;
 * JobScheduler itself also enforces one running instance of a job id, so the
 * same job never overlaps with itself.
 */
class JobSchedulerTriggerAdapter(
    private val scheduler: PeriodicConsolidationScheduler,
    private val gateway: JobSchedulerGateway,
    override val taskName: String = SchedulingPolicyDefaults.DEFAULT_TASK_NAME,
    @Volatile var lastFailure: String? = null,
    private val jobId: Int = PlatformPeriodicScheduling.JOB_ID,
) : PeriodicTriggerAdapter {

    override fun schedule(
        policy: SchedulingPolicy,
        job: LocalPeriodicConsolidationJob,
    ): PeriodicScheduleResult {
        // Disabled / non-local-only -> nothing registered; cancels any prior OS job.
        if (!policy.enabled) {
            gateway.cancel(jobId)
            scheduler.cancel(taskName)
            lastFailure = null
            return PeriodicScheduleResult.Disabled(taskName)
        }

        // Submit the persisted periodic OS job (the real gateway builds the
        // framework JobInfo pointing at PeriodicLocalConsolidationJobService).
        val intervalMs = policy.effectiveIntervalMinutes * 60_000L
        val scheduleResult = gateway.schedule(jobId, intervalMs)
        if (scheduleResult !is ScheduleResult.Success) {
            // The framework refused: never claim success. Keep the in-memory
            // registry empty so UI reports not-scheduled (honest).
            scheduler.cancel(taskName)
            lastFailure = (scheduleResult as? ScheduleResult.Failure)?.reason ?: "schedule failed"
            return PeriodicScheduleResult.Disabled(taskName)
        }

        // Framework accepted -> register the deterministic local-only job too so
        // runScheduledOnce / isScheduled reflect the same single task.
        lastFailure = null
        val outcome = scheduler.register(
            policy.copy(taskName = taskName, conflictPolicy = TaskConflictPolicy.REPLACE),
            job,
        )
        return when (outcome) {
            is PeriodicConsolidationScheduler.RegistrationOutcome.Disabled ->
                PeriodicScheduleResult.Disabled(outcome.taskName)
            is PeriodicConsolidationScheduler.RegistrationOutcome.EnabledNew ->
                PeriodicScheduleResult.Scheduled(outcome.taskName)
            is PeriodicConsolidationScheduler.RegistrationOutcome.Replaced ->
                PeriodicScheduleResult.Replaced(outcome.taskName)
            is PeriodicConsolidationScheduler.RegistrationOutcome.KeptExisting ->
                PeriodicScheduleResult.KeptExisting(outcome.taskName)
        }
    }

    override fun cancel(): Boolean {
        gateway.cancel(jobId)
        val removed = scheduler.cancel(taskName)
        lastFailure = null
        return removed
    }

    override fun isScheduled(): Boolean =
        scheduler.isScheduled(taskName) && gateway.isPending(jobId)

    /**
     * Whether the *OS* scheduler currently holds a pending job for [jobId];
     * independent of the in-memory registry (so restart recovery can observe it).
     */
    fun isOsJobPending(): Boolean = gateway.isPending(jobId)

    override suspend fun runScheduledOnce(
        isCancelled: () -> Boolean,
    ): PeriodicRunResult? = scheduler.runScheduledOnce(taskName, isCancelled)
}

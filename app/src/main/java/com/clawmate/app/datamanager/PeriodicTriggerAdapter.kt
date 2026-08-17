package com.clawmate.app.datamanager

/**
 * Phase-36 **minimal replaceable Android periodic-trigger adapter**.
 *
 * The app has **no WorkManager dependency** in its offline Gradle cache (verified
 * at phase start; downloading it would violate the offline/no-network rule).
 * Rather than pulling in a network artifact, this seam defines the *replaceable*
 * contract that a future WorkManager `PeriodicWorkRequest` / `Worker` line can
 * implement independently, and ships a deterministic **in-process** adapter
 * ([InProcessPeriodicTriggerAdapter]) that drives the Phase-35
 * [PeriodicConsolidationScheduler] serially from an explicit trigger.
 *
 * The adapter is deliberately minimal and **disabled by default**: it never
 * schedules, never runs, and never opens the network unless an explicit caller
 * builds a `LOCAL_ONLY` + `NOT_REQUIRED` [SchedulingPolicy] and calls
 * [PeriodicTriggerAdapter.schedule]. It is never invoked from
 * `Application.onCreate`, Home load, recomposition, or a plain repository/
 * scheduling-host constructor.
 *
 * The trigger source of `LocaOnlyPeriodicJob` only runs deterministic local
 * consolidation / index / profile rebuild (never [CloudConsolidationRepository]),
 * so a scheduled run is idempotent and cancellable and carries no secret.
 */
interface PeriodicTriggerAdapter {

    /** The unique canonical task name this adapter manages. */
    val taskName: String

    /**
     * Schedule one periodic local-consolidation task. Returns a typed
     * [PeriodicScheduleResult]. A `DISABLED` / non-`LOCAL_ONLY` policy schedules
     * nothing (same contract as [PeriodicConsolidationScheduler.register]).
     */
    fun schedule(policy: SchedulingPolicy, job: LocalPeriodicConsolidationJob): PeriodicScheduleResult

    /** Cancel / remove the task by [taskName]; true when it was scheduled. */
    fun cancel(): Boolean

    /** Whether the canonical task is currently scheduled. */
    fun isScheduled(): Boolean

    /**
     * Run the scheduled local-consolidation job once (serially vs every other
     * scheduled task). Runs nothing when not scheduled / not enabled. Cancellation
     * propagates via [kotlinx.coroutines.CancellationException].
     */
    suspend fun runScheduledOnce(isCancelled: () -> Boolean = { false }): PeriodicRunResult?
}

/** Honest, countable outcome of a scheduling call (mirrors registration states). */
sealed interface PeriodicScheduleResult {
    /** The policy was disabled → nothing scheduled. */
    data class Disabled(val taskName: String) : PeriodicScheduleResult
    /** A new local-only task was registered (single task name, idempotent replace). */
    data class Scheduled(val taskName: String) : PeriodicScheduleResult
    /** An existing task of the same name was kept (KEEP conflict policy). */
    data class KeptExisting(val taskName: String) : PeriodicScheduleResult
    /** An existing task was replaced (REPLACE policy). */
    data class Replaced(val taskName: String) : PeriodicScheduleResult
}

/**
 * The deterministic **in-process** implementation: wraps the Phase-35
 * [PeriodicConsolidationScheduler] with a [LocalOnlyPeriodicJob] under the unique
 * canonical task name. All runs are serial through the scheduler's single `Mutex`
 * and idempotent; they never touch the model-backed repository.
 */
class InProcessPeriodicTriggerAdapter(
    private val scheduler: PeriodicConsolidationScheduler,
    override val taskName: String = SchedulingPolicyDefaults.DEFAULT_TASK_NAME,
    private val conflictPolicy: TaskConflictPolicy = TaskConflictPolicy.REPLACE,
) : PeriodicTriggerAdapter {

    override fun schedule(
        policy: SchedulingPolicy,
        job: LocalPeriodicConsolidationJob,
    ): PeriodicScheduleResult {
        val effective = policy.copy(taskName = taskName, conflictPolicy = conflictPolicy)
        return when (val outcome = scheduler.register(effective, job)) {
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

    override fun cancel(): Boolean = scheduler.cancel(taskName)

    override fun isScheduled(): Boolean = scheduler.isScheduled(taskName)

    override suspend fun runScheduledOnce(
        isCancelled: () -> Boolean,
    ): PeriodicRunResult? = scheduler.runScheduledOnce(taskName, isCancelled)
}

package com.clawmate.app.datamanager

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single, explicit registration seam for the Phase-35 periodic **local-only**
 * consolidation work.
 *
 * There is **no WorkManager dependency** in the app, so this seam does not start
 * an Android worker or open the network. It is a small, JVM-testable controller
 * that a future UI / host may wire to a real periodic trigger (for example a
 * WorkManager `PeriodicWorkRequest`, once the dependency is introduced). All
 * decisions are made here in-process and deterministically.
 *
 * Registration contract:
 *  - [register] is the **only** entry point and is always explicit. It schedules
 *    a task only when the injected [SchedulingPolicy] has
 *    `mode = LOCAL_ONLY`; a `DISABLED` policy (the production default) schedules
 *    nothing and returns [RegistrationOutcome.DISABLED].
 *  - The task name is the unique key. Per [SchedulingPolicy.conflictPolicy], an
 *    existing registration of the same name is replaced or kept; one task name
 *    can never be scheduled twice.
 *  - Nothing here runs on Application.onCreate / Home load / recomposition / a
 *    plain repository constructor. The seam only acts when an explicit caller
 *    invokes [register] and later [runScheduledOnce] / [runAllScheduledOnce].
 *
 * Execution contract:
 *  - All registered local-only tasks run **serially** through a single [Mutex]
 *    (never in parallel), matching the low-RAM host constraint.
 *  - Each run is **idempotent** (deterministic local rebuilds of the same input
 *    produce the same artifacts) and **cancellable**: the caller supplies
 *    `isCancelled` and the job checks it between stages; a cancelled run throws
 *    [CancellationException].
 *  - The background path **never** touches the model-backed
 *    [CloudConsolidationRepository]. Only [LocalPeriodicConsolidationJob]-style
 *    deterministic local work runs.
 *  - Cancellation / failure does not leak configuration: the registry holds only
 *    the (secret-free) [SchedulingPolicy] + job; a failed/cancelled run is
 *    cleared from the running state and leaves no stale half-registration.
 */
class PeriodicConsolidationScheduler(
    private val jobRegistry: PeriodicJobRegistry = InMemoryJobRegistry(),
) {

    private val runMutex = Mutex()

    /**
     * Register (or replace) one periodic local-consolidation task. This is the
     * lone explicit registration path. It never auto-enables: a [SchedulingPolicy]
     * with [SchedulerMode.DISABLED] registers nothing.
     */
    @Synchronized
    fun register(policy: SchedulingPolicy, job: LocalPeriodicConsolidationJob): RegistrationOutcome {
        if (!policy.enabled) {
            return RegistrationOutcome.Disabled(taskName = policy.taskName)
        }
        val existing = jobRegistry.get(policy.taskName)
        if (existing != null) {
            when (policy.conflictPolicy) {
                TaskConflictPolicy.KEEP -> return RegistrationOutcome.KeptExisting(policy.taskName)
                TaskConflictPolicy.REPLACE -> {
                    jobRegistry.put(policy.taskName, RegisteredTask(policy, job))
                    return RegistrationOutcome.Replaced(policy.taskName)
                }
            }
        }
        jobRegistry.put(policy.taskName, RegisteredTask(policy, job))
        return RegistrationOutcome.EnabledNew(policy.taskName)
    }

    /** Cancel/remove a scheduled task by its unique name. Returns true if it was present. */
    @Synchronized
    fun cancel(taskName: String): Boolean = jobRegistry.remove(taskName) != null

    /** Whether a task with [taskName] is currently registered (scheduled). */
    fun isScheduled(taskName: String): Boolean = jobRegistry.get(taskName) != null

    /** The unique names of all currently registered tasks (stable, sorted). */
    fun scheduledTaskNames(): List<String> = jobRegistry.names().sorted()

    /**
     * Run exactly one registered task by name, **serially** with respect to every
     * other task, and only if its policy is still enabled/local-only. Returns null
     * when the task is not registered or was disabled in the meantime. A cancelled
     * run throws [CancellationException] and never persists partial registration
     * state.
     */
    suspend fun runScheduledOnce(
        taskName: String,
        isCancelled: () -> Boolean = { false },
    ): PeriodicRunResult? = runMutex.withLock {
        val task = jobRegistry.get(taskName) ?: return@withLock null
        if (!task.policy.enabled) return@withLock null
        if (isCancelled()) throw CancellationException("periodic run cancelled before start: $taskName")
        val result = task.job.runOnce(isCancelled)
        // A cancelled job leaves the registration intact for a later explicit run,
        // but never a half-created artifact: the local job is all-or-nothing.
        result
    }

    /**
     * Run every currently registered task **serially** (one at a time). Each task
     * is idempotent, so this is safe to call repeatedly. Cancellation aborts at the
     * next cooperative check and rethrows [CancellationException].
     */
    suspend fun runAllScheduledOnce(
        isCancelled: () -> Boolean = { false },
    ): Map<String, PeriodicRunResult> = runMutex.withLock {
        val names = jobRegistry.names().sorted()
        val results = LinkedHashMap<String, PeriodicRunResult>()
        for (name in names) {
            if (isCancelled()) throw CancellationException("periodic run cancelled at: $name")
            val task = jobRegistry.get(name) ?: continue
            if (!task.policy.enabled) continue
            results[name] = task.job.runOnce(isCancelled)
        }
        results
    }

    /** Typed outcome of one [register] call; the registry is only mutated for an enabled policy. */
    sealed interface RegistrationOutcome {
        val taskName: String
        /** The policy was [SchedulerMode.DISABLED] → nothing was scheduled. */
        data class Disabled(override val taskName: String) : RegistrationOutcome
        /** A new task was registered. */
        data class EnabledNew(override val taskName: String) : RegistrationOutcome
        /** An existing task of the same name was replaced (REPLACE policy). */
        data class Replaced(override val taskName: String) : RegistrationOutcome
        /** An existing task of the same name was kept (KEEP policy); the new one was rejected. */
        data class KeptExisting(override val taskName: String) : RegistrationOutcome
    }

    /** A single registered scheduling policy + its local-only job. */
    data class RegisteredTask(
        val policy: SchedulingPolicy,
        val job: LocalPeriodicConsolidationJob,
    )
}

/**
 * Contract for the *local-only* work a periodic task runs. Implementations must
 * be deterministic, idempotent, fully cancellable, and must never call the
 * model-backed [CloudConsolidationRepository] or open the network. A cancelled
 * run throws [CancellationException].
 */
interface LocalPeriodicConsolidationJob {
    suspend fun runOnce(isCancelled: () -> Boolean): PeriodicRunResult
}

/** Honest, countable outcome of one local periodic run. */
data class PeriodicRunResult(
    val taskName: String,
    val localRecords: Int = 0,
    val indexedEvents: Int = 0,
    val sceneCount: Int = 0,
    val entityCount: Int = 0,
    val profileDomainCount: Int = 0,
    val finishedAt: String = "",
) {
    val anyWorkProduced: Boolean
        get() = localRecords > 0 || indexedEvents > 0 || sceneCount > 0 ||
            entityCount > 0 || profileDomainCount > 0
}

/**
 * The production **local-only** periodic job. It runs, in order:
 *  1. Phase-30 local consolidation + event/index rebuild ([LocalConsolidationIndexRunner]);
 *  2. Phase-33 workflow-scene + entity index rebuild ([EntitySceneIndexRunner], local-only);
 *  3. Phase-32 local profile projection ([ProfileIndexBuilder.buildLocal]).
 *
 * It never references [CloudConsolidationRepository] and never touches the
 * network. Each stage is deterministic + idempotent; a cancelled run stops at the
 * next stage boundary and throws [CancellationException]. The persisted profile
 * state / manual Home data surfaces are left untouched (only `buildLocal`'s
 * deterministic projection is recomputed and read back — nothing writes there).
 */
class LocalOnlyPeriodicJob(
    private val baseDir: File,
) : LocalPeriodicConsolidationJob {

    override suspend fun runOnce(isCancelled: () -> Boolean): PeriodicRunResult {
        val taskName = SchedulingPolicyDefaults.DEFAULT_TASK_NAME

        // Stage 1 — local consolidation + event index.
        val local = LocalConsolidationIndexRunner(baseDir).run()
        if (isCancelled()) throw CancellationException("periodic job cancelled after consolidation: $taskName")

        // Stage 2 — scene + entity index (local-only).
        val sceneEntity = EntitySceneIndexRunner(baseDir).run(EntitySceneIndexRunner.BuildMode.LocalOnly)
        if (isCancelled()) throw CancellationException("periodic job cancelled after scene/entity: $taskName")

        // Stage 3 — local profile projection (no network, no model claims).
        val profile = ProfileIndexBuilder(baseDir).buildLocal()
        if (isCancelled()) throw CancellationException("periodic job cancelled after profile: $taskName")

        return PeriodicRunResult(
            taskName = taskName,
            localRecords = local.consolidation.totalRecords,
            indexedEvents = local.indexedEvents,
            sceneCount = sceneEntity.sceneCount,
            entityCount = sceneEntity.entityCount,
            profileDomainCount = profile.domainCounts.size,
            finishedAt = nowIsoLocal(),
        )
    }

    private fun nowIsoLocal(): String =
        java.time.LocalDateTime.now(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
}

/**
 * Opaque task store behind the scheduler so tests can observe and reset state.
 * It holds only secret-free [PeriodicConsolidationScheduler.RegisteredTask] entries.
 */
interface PeriodicJobRegistry {
    fun get(name: String): PeriodicConsolidationScheduler.RegisteredTask?
    fun put(name: String, task: PeriodicConsolidationScheduler.RegisteredTask)
    fun remove(name: String): PeriodicConsolidationScheduler.RegisteredTask?
    fun names(): Set<String>
}

/** Simple in-memory registry (no persistence, so old state needs no migration). */
class InMemoryJobRegistry : PeriodicJobRegistry {
    private val tasks = LinkedHashMap<String, PeriodicConsolidationScheduler.RegisteredTask>()
    override fun get(name: String) = tasks[name]
    override fun put(name: String, task: PeriodicConsolidationScheduler.RegisteredTask) { tasks[name] = task }
    override fun remove(name: String): PeriodicConsolidationScheduler.RegisteredTask? = tasks.remove(name)
    override fun names(): Set<String> = tasks.keys.toSet()
}

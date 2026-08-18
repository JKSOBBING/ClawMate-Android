package com.clawmate.app.datamanager

import java.util.concurrent.atomic.AtomicReference

/**
 * Phase-46 **in-process, device-free contract** for reading the user's persisted
 * periodic-local-consolidation opt-in from a background job and for recording the
 * outcome of an OS-triggered run.
 *
 * ## Why a thin seam
 * [PeriodicLocalConsolidationJobService] runs on the main thread and must decide
 * *fast* whether the job is still wanted. It should not block on DataStore I/O, so
 * a **process-level mirror** is kept updated by the Settings path (and by restore /
 * boot reconciliation) exactly like [com.clawmate.app.gallery.background.GallerySyncOptinRegistry].
 * The "truth" still lives in the persisted [AppSettings]; this registry is only a
 * replicla flag the OS job can read synchronously.
 *
 * It never *runs* anything — it only answers "am I still opted in?" and "what was
 * the last honest run outcome?". Writing an outcome never fabricates success.
 */
object PeriodicSchedulingRuntimeRegistry {

    private val optedInFlag = AtomicReference<Boolean?>(null)
    private val lastOutcome = AtomicReference<JobRunOutcome?>(null)
    private val lastError = AtomicReference<String?>(null)

    /**
     * Mirror the persisted opt-in. `null` = unknown (none set yet): a background
     * job treats `null` as "not confirmed" and cancels itself rather than running
     * off an assumption.
     */
    fun setOptedIn(value: Boolean) {
        optedInFlag.set(value)
        if (!value) clearRunResult()
    }

    /** Whether the runtime currently regards the task as opted in (default false). */
    fun isOptedIn(): Boolean = optedInFlag.get() ?: false

    /** Clear the flag (e.g. fresh process where Settings hasn't loaded). */
    fun clearOptedIn() {
        optedInFlag.set(null)
        clearRunResult()
    }

    /** Record an honest OS-triggered run outcome. */
    fun recordOutcome(outcome: JobRunOutcome) {
        lastOutcome.set(outcome)
        lastError.set(null)
    }

    /** Record an honest run failure (message only; never fabricated success). */
    fun recordError(message: String) {
        lastError.set(message)
    }

    fun lastRunOutcome(): JobRunOutcome? = lastOutcome.get()
    fun lastRunError(): String? = lastError.get()

    /** Reset transient state (tests / explicit cancel). */
    fun clearRunResult() {
        lastOutcome.set(null)
        lastError.set(null)
    }
}

/** Typed, honest outcome of one periodic job run, reusing the Phase-35 counts. */
data class JobRunOutcome(
    val taskName: String,
    val localRecords: Int = 0,
    val indexedEvents: Int = 0,
    val sceneCount: Int = 0,
    val entityCount: Int = 0,
    val profileDomainCount: Int = 0,
    val finishedAt: String,
) {
    val anyWorkProduced: Boolean
        get() = localRecords > 0 || indexedEvents > 0 || sceneCount > 0 ||
            entityCount > 0 || profileDomainCount > 0
}

/** Derive a [JobRunOutcome] from a Phase-35 [PeriodicRunResult] (idempotent mapping). */
fun PeriodicRunResult.toJobRunOutcome(): JobRunOutcome =
    JobRunOutcome(
        taskName = taskName,
        localRecords = localRecords,
        indexedEvents = indexedEvents,
        sceneCount = sceneCount,
        entityCount = entityCount,
        profileDomainCount = profileDomainCount,
        finishedAt = finishedAt,
    )


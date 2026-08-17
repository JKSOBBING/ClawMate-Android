package com.clawmate.app.datamanager

/**
 * Pure, testable scheduling policy for the Phase-35 periodic **local-only**
 * consolidation seam (Harmony's periodic/WorkManager equivalent on Android).
 *
 * The app has **no WorkManager dependency** (verified at phase start). Rather
 * than pulling in a network + heavy artifact from a new dependency, this file
 * defines a small, deterministic, JVM-testable policy surface that a future
 * UI / host can wire to a real periodic trigger (e.g. an Android WorkManager
 * worker) independently. Nothing here starts an Android worker or opens the
 * network.
 *
 * Safety invariants enforced at the policy layer:
 *  - **Disabled by default.** [mode] is [SchedulerMode.DISABLED], the only
 *    production default. A task is only ever registered when a caller
 *    explicitly builds a policy with `mode = LOCAL_ONLY`.
 *  - **Local-only only.** A periodic task is only allowed to trigger
 *    deterministic local consolidation / index / profile rebuild underneath
 *    ([PeriodicConsolidationScheduler]). It never references the model-backed
 *    [CloudConsolidationRepository]; the model path remains reserved for an
 *    explicit user action plus a complete config.
 *  - **No network requirement.** The deterministic local rebuild does not need
 *    connectivity, so [networkConstraint] is fixed to [NetworkConstraint.NOT_REQUIRED].
 *  - **Config has no secrets.** [SchedulingPolicy] carries only task metadata;
 *    never a base URL, API key, token, or path. A cancelled or failed run cannot
 *    leak a secret that was never held here.
 */
object SchedulingPolicyDefaults {
    /** Unique task name for the canonical periodic local-consolidation job. */
    const val DEFAULT_TASK_NAME: String = "clawmate.periodic.local.consolidation"

    /** Default repeat interval in minutes (no model path, so read/write only). */
    const val DEFAULT_INTERVAL_MINUTES: Long = 12 * 60L

    /** Max interval accepted by [SchedulingPolicy]; longer inputs are clamped. */
    const val MAX_INTERVAL_MINUTES: Long = 24 * 60L
}

/** Network/connectivity constraint for a periodic job. The local path needs none. */
enum class NetworkConstraint(val token: String) {
    /** The periodic local-consolidation job performs no network I/O. */
    NOT_REQUIRED("not_required"),
    /** Reserved for a future networked job; never used by the local seam. */
    CONNECTED("connected"),
}

/** Whether periodic scheduling is enabled. [DISABLED] is the only production default. */
enum class SchedulerMode(val token: String) {
    /** Default: no periodic task is ever scheduled or run. */
    DISABLED("disabled"),
    /** An explicit caller opted in; runs deterministic local consolidation only. */
    LOCAL_ONLY("local_only"),
}

/** What to do when registering a task whose unique [SchedulingPolicy.taskName] already exists. */
enum class TaskConflictPolicy(val token: String) {
    /** Replace the previously registered task of the same name. */
    REPLACE("replace"),
    /** Keep the existing task and reject the new registration. */
    KEEP("keep"),
}

/**
 * A single, explicit scheduling request for one periodic local-consolidation
 * task. Constructed only by an explicit UI/host caller; never by launch,
 * Home-load, recomposition, or a repository/Application constructor.
 */
data class SchedulingPolicy(
    /** Unique task name (the seam's single replace/key). Must be non-blank. */
    val taskName: String = SchedulingPolicyDefaults.DEFAULT_TASK_NAME,
    val mode: SchedulerMode = SchedulerMode.DISABLED,
    /** Repeat interval in minutes; only meaningful when [enabled]. */
    val intervalMinutes: Long = SchedulingPolicyDefaults.DEFAULT_INTERVAL_MINUTES,
    val networkConstraint: NetworkConstraint = NetworkConstraint.NOT_REQUIRED,
    val conflictPolicy: TaskConflictPolicy = TaskConflictPolicy.REPLACE,
) {
    /** Whether this policy is safe to register for the local-only scheduler. */
    val enabled: Boolean
        get() = mode == SchedulerMode.LOCAL_ONLY &&
            networkConstraint == NetworkConstraint.NOT_REQUIRED &&
            taskName.isNotBlank()

    /** Clamped, bounded repeat interval in minutes (1..MAX). */
    val effectiveIntervalMinutes: Long
        get() = when {
            intervalMinutes <= 0 -> 1L
            intervalMinutes > SchedulingPolicyDefaults.MAX_INTERVAL_MINUTES ->
                SchedulingPolicyDefaults.MAX_INTERVAL_MINUTES
            else -> intervalMinutes
        }
}

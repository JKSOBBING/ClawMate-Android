package com.clawmate.app.datamanager.platform

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

/**
 * Phase-46 **thin seam over the framework `JobScheduler`** so pure JVM tests can
 * drive scheduling decisions without a device and without touching the framework
 * `JobInfo` POJO (whose mockable-jar methods throw "Stub!" on the JVM). The
 * interface therefore exposes **primitive** job metadata ([jobId],
 * [intervalMillis]) and the real implementation builds the framework `JobInfo`
 * internally. Registration/un-registration decisions stay in JVM-testable Kotlin
 * (see [com.clawmate.app.datamanager.JobSchedulerTriggerAdapter]).
 */
interface JobSchedulerGateway {

    /** Android `JobInfo` builder needs a ComponentName; expose it for the real gateway. */
    fun jobServiceComponent(): ComponentName

    /**
     * Schedule one persisted periodic job with [jobId] at [intervalMillis] period.
     * Returns a typed outcome so callers can retry/honestly report a framework
     * rejection (never pretend success).
     */
    fun schedule(jobId: Int, intervalMillis: Long): ScheduleResult

    /** Cancel the job with [jobId]. */
    fun cancel(jobId: Int): CancelResult

    /** The ids currently registered with the OS scheduler (pending jobs). */
    fun pendingJobIds(): Set<Int>

    /** True when [jobId] is one of the OS scheduler's pending jobs. */
    fun isPending(jobId: Int): Boolean = pendingJobIds().contains(jobId)
}

/** Typed outcome of one framework schedule attempt (mirrors JobScheduler consts). */
sealed interface ScheduleResult {
    /** JobScheduler.schedule returned JobScheduler.RESULT_SUCCESS. */
    data class Success(val jobId: Int) : ScheduleResult
    /**
     * JobScheduler.RESULT_FAILURE (e.g. unsupported SDK, policy, or job cannot be
     * scheduled). Callers retry/honestly report, never pretend success.
     */
    data class Failure(val jobId: Int, val reason: String) : ScheduleResult
}

/** Typed outcome of one cancel attempt. */
sealed interface CancelResult {
    data object Cancelled : CancelResult
    data object NotCancelled : CancelResult
}

/**
 * Production adapter wrapping the real framework `JobScheduler`. Obtained *only*
 * from a `Context`; a unit-testable fake implements [JobSchedulerGateway] directly.
 */
class AndroidJobSchedulerGateway(
    private val scheduler: JobScheduler,
    private val service: ComponentName,
) : JobSchedulerGateway {

    override fun jobServiceComponent(): ComponentName = service

    override fun schedule(jobId: Int, intervalMillis: Long): ScheduleResult {
        val binding = PlatformJobInfoBuilder(service)
        val jobInfo = binding.buildPeriodic(jobId, intervalMillis)
        val code = scheduler.schedule(jobInfo)
        return if (code == JobScheduler.RESULT_SUCCESS) {
            ScheduleResult.Success(jobId)
        } else {
            ScheduleResult.Failure(jobId, "JobScheduler.schedule returned $code")
        }
    }

    override fun cancel(jobId: Int): CancelResult {
        // framework JobScheduler.cancel(int) returns void; presence is observed
        // via pending jobs, so a cancel is treated as attempted/completed.
        scheduler.cancel(jobId)
        return CancelResult.Cancelled
    }

    override fun pendingJobIds(): Set<Int> =
        runCatching { scheduler.getAllPendingJobs().orEmpty().map { it.id }.toSet() }
            .getOrDefault(emptySet())
}

/**
 * Builds the framework `JobInfo` for the canonical persisted periodic local job.
 * Kept central so the real adapter, the restore coordinator and the JobService use
 * one definition. Only used on-device (or behind the gateway); it is never invoked
 * by a pure JVM test.
 */
class PlatformJobInfoBuilder(
    private val service: ComponentName,
) {
    fun buildPeriodic(jobId: Int, intervalMillis: Long): JobInfo =
        JobInfo.Builder(jobId, service)
            .setPersisted(true)
            .setPeriodic(intervalMillis)
            // Local-only job needs no network, no charging, no unmetered constraint.
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
            .setRequiresBatteryNotLow(false)
            .setRequiresCharging(false)
            .build()
}


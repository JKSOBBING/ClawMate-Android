package com.clawmate.app.datamanager

import android.app.job.JobParameters
import android.app.job.JobService
import com.clawmate.app.log.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Phase-46 **[JobService] that actually runs one OS-scheduled periodic local
 * consolidation.**
 *
 * ## How the OS job reaches real work (the "reachable task" proof)
 * 1. [JobSchedulerTriggerAdapter] registers a persisted periodic
 *    `JobInfo` whose service is this class (see AndroidManifest).
 * 2. When JobScheduler fires it, [onStartJob] runs on the main thread and:
 *    - **gates on the user's explicit opt-in**: the process-level
 *      [PeriodicSchedulingRuntimeRegistry] (fed from the persisted
 *      [AppSettings.periodicLocalConsolidationEnabled]) must be `true`. If it is
 *      `false`/unknown the job is treated as stale: it cancels itself
 *      (`jobFinished(needsReschedule = false)`), so a disabled user never gets
 *      background work.
 *    - runs the **existing canonical local-only job** ([LocalOnlyPeriodicJob], the
 *      exact engine the Settings "立即运行" path uses) on a background dispatcher.
 *    - records an honest outcome ([JobRunOutcome]) into
 *      [PeriodicSchedulingRuntimeRegistry] and the in-app [AppLogger].
 * 3. [onStopJob] cancels the in-flight run cooperatively and returns
 *    `false` (the OS will reschedule the next period on its own).
 *
 * ## Boundary guarantees
 * - **Single source**: this service reuses `LocalOnlyPeriodicJob` — there is no
 *   second consolidation engine.
 * - **Serial / no self-overlap**: JobScheduler never starts a second instance of
 *   the same `jobId` while one is running, and the underlying
 *   `PeriodicConsolidationScheduler` also serializes writes.
 * - **No network / model** at all: the job never touches
 *   [CloudConsolidationRepository], never auto-calls a cloud model, chat, GUI,
 *   media-scan, or TTS. If a job is somehow scheduled that cannot express that
 *   boundary, it is left `UNSUPPORTED` rather than appearing to run.
 * - **Cancellable / retriable**: a cancelled run rethrows [CancellationException]
 *   and the registration stays intact for the next OS-fired period.
 */
class PeriodicLocalConsolidationJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeJob: Job? = null

    /** Cooperative cancellation flag set by [onStopJob]; read inside the run lambda. */
    private val cancellationRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        // Bind the process-level opt-in mirror to the persisted AppSettings so
        // the service can gate synchronously. Reading once here never *runs* work.
        runCatching {
            val repo = com.clawmate.app.settings.data.DataStoreSettingsRepository(applicationContext)
            val enabled = kotlinx.coroutines.runBlocking {
                runCatching { repo.snapshot().periodicLocalConsolidationEnabled }
                    .getOrDefault(false)
            }
            PeriodicSchedulingRuntimeRegistry.setOptedIn(enabled)
        }.onFailure {
            AppLogger.w("PeriodicJob", "未能从持久化设置确认 opt-in，按未启用处理")
            PeriodicSchedulingRuntimeRegistry.setOptedIn(false)
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        // Gate on the user's explicit opt-in. Stale job -> cancel, do not reschedule.
        if (!PeriodicSchedulingRuntimeRegistry.isOptedIn()) {
            AppLogger.w("PeriodicJob", "周期任务已关闭/未确认 opt-in：取消本次 OS 任务")
            jobFinished(params, false)
            return false
        }

        val baseDir: File = PlatformPeriodicScheduling.filesBaseDir(applicationContext)
        val job = LocalOnlyPeriodicJob(baseDir)
        cancellationRequested.set(false)

        activeJob = scope.launch {
            try {
                val result = job.runOnce { cancellationRequested.get() }
                PeriodicSchedulingRuntimeRegistry.recordOutcome(result.toJobRunOutcome())
                AppLogger.i(
                    "PeriodicJob",
                    "本地周期重构完成：记录=${result.localRecords} 事件=${result.indexedEvents} " +
                        "场景=${result.sceneCount} 实体=${result.entityCount} 画像领域=${result.profileDomainCount}",
                )
                jobFinished(params, false) // completed; the periodic reschedule is the OS's business
            } catch (e: CancellationException) {
                AppLogger.w("PeriodicJob", "本地周期重构被取消")
                jobFinished(params, false)
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                PeriodicSchedulingRuntimeRegistry.recordError(msg)
                AppLogger.e("PeriodicJob", "本地周期重构失败：$msg")
                // reschedule=true lets JobScheduler retry this period; the persisted
                // job remains for future periods regardless.
                jobFinished(params, true)
            }
        }
        return true // we are doing work asynchronously; the OS waits for jobFinished
    }

    override fun onStopJob(params: JobParameters): Boolean {
        // Cooperative cancellation of the in-flight run; JobScheduler will reschedule.
        cancellationRequested.set(true)
        activeJob?.cancel()
        activeJob = null
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Mirror state for tests (never thread-conflicting). */
    internal fun currentOptedIn(): Boolean = PeriodicSchedulingRuntimeRegistry.isOptedIn()
}

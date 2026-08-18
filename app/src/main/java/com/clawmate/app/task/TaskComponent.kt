package com.clawmate.app.task

import android.content.Context
import com.clawmate.app.task.data.AndroidBundledConfigReader
import com.clawmate.app.task.data.BundledConfigReader
import com.clawmate.app.task.data.DefaultWorkflowRepository
import com.clawmate.app.task.data.WorkflowRepository
import com.clawmate.app.task.hdc.DisabledWorkflowDeviceAdapter
import com.clawmate.app.task.hdc.WorkflowDeviceAdapter
import com.clawmate.app.task.runner.WorkflowRunner
import com.clawmate.app.task.runner.WorkflowRunnerImpl

/**
 * Lightweight manual DI for the Task/Workflow feature (Phase 4). Mirrors the
 * Phase 2/3 pattern (ChatComponent): the repository is backed by app-private
 * filesDir and reads bundled configs from `res/raw`.
 *
 * Phase 8: device-control adapters are DISABLED by default. A caller may
 * explicitly register a health-verified [WorkflowDeviceAdapter] via
 * [setDeviceAdapter]; until then the runner uses [DisabledWorkflowDeviceAdapter]
 * and every GUI/HDC step stays BLOCKED. No network call happens on app launch.
 */
object TaskComponent {

    @Volatile
    private var repository: WorkflowRepository? = null

    @Volatile
    private var bundledReader: BundledConfigReader? = null

    @Volatile
    private var runner: WorkflowRunner? = null

    @Volatile
    private var deviceAdapter: WorkflowDeviceAdapter? = null

    fun bundledReader(context: Context): BundledConfigReader =
        bundledReader ?: AndroidBundledConfigReader(context.applicationContext).also { bundledReader = it }

    fun repository(context: Context): WorkflowRepository =
        repository ?: DefaultWorkflowRepository(
            baseDir = context.applicationContext.filesDir,
            bundled = bundledReader(context),
        ).also { repository = it }

    /**
     * Register an explicit device-control adapter (e.g. a health-verified
     * [com.clawmate.app.task.hdc.BridgeBackedWorkflowDeviceAdapter]) supplied by
     * a caller, or pass null to revert to the disabled default. Rebuilds the
     * runner so it observes the new adapter on the next run.
     */
    fun setDeviceAdapter(adapter: WorkflowDeviceAdapter?) {
        synchronized(this) {
            deviceAdapter = adapter
            runner = null
        }
    }

    fun currentAdapter(): WorkflowDeviceAdapter =
        synchronized(this) { deviceAdapter ?: DisabledWorkflowDeviceAdapter }

    /* 200 step / 100 loop iterations / 60s cap — bounded runner. */
    fun runner(context: Context): WorkflowRunner {
        synchronized(this) {
            runner?.let { return it }
            val adapter = deviceAdapter ?: DisabledWorkflowDeviceAdapter
            return WorkflowRunnerImpl(
                maxTotalSteps = 200,
                maxLoopIterations = 100,
                maxMillis = 60_000L,
                deviceAdapter = adapter,
                // Phase 41: the runner writes real daily-log entries under the
                // app-private filesDir/workflows/daily-log/YYYY-MM-DD/ so Home's
                //「生成推荐」reads genuine workflow output (same single writer the
                // DailyLogEventSource reads).
                baseDir = context.applicationContext.filesDir,
            ).also { runner = it }
        }
    }
}

package com.clawmate.app.task

import android.content.Context
import com.clawmate.app.task.data.AndroidBundledConfigReader
import com.clawmate.app.task.data.BundledConfigReader
import com.clawmate.app.task.data.DefaultWorkflowRepository
import com.clawmate.app.task.data.WorkflowRepository
import com.clawmate.app.task.runner.WorkflowRunner
import com.clawmate.app.task.runner.WorkflowRunnerImpl

/**
 * Lightweight manual DI for the Task/Workflow feature (Phase 4). Mirrors the
 * Phase 2/3 pattern (ChatComponent): the repository is backed by app-private
 * filesDir and reads bundled configs from `res/raw`.
 */
object TaskComponent {

    @Volatile
    private var repository: WorkflowRepository? = null

    @Volatile
    private var bundledReader: BundledConfigReader? = null

    @Volatile
    private var runner: WorkflowRunner? = null

    fun bundledReader(context: Context): BundledConfigReader =
        bundledReader ?: AndroidBundledConfigReader(context.applicationContext).also { bundledReader = it }

    fun repository(context: Context): WorkflowRepository =
        repository ?: DefaultWorkflowRepository(
            baseDir = context.applicationContext.filesDir,
            bundled = bundledReader(context),
        ).also { repository = it }

    /* 200 step / 100 loop iterations / 60s cap — bounded runner. */
    fun runner(): WorkflowRunner =
        runner ?: WorkflowRunnerImpl(maxTotalSteps = 200, maxLoopIterations = 100, maxMillis = 60_000L)
            .also { runner = it }
}

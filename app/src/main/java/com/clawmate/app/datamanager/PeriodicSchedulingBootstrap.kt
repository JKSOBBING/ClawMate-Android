package com.clawmate.app.datamanager

import android.app.job.JobScheduler
import android.content.Context
import com.clawmate.app.datamanager.platform.AndroidJobSchedulerGateway
import com.clawmate.app.datamanager.platform.JobSchedulerGateway

/**
 * Holds/creates the single real [JobSchedulerGateway] for this app process so the
 * Settings factory, the restore coordinator and tests share one definition of
 * "the OS scheduler". Never constructed on a plain JVM (no Context).
 */
object PeriodicSchedulingGatewayHolder {

    @Volatile
    private var cached: JobSchedulerGateway? = null

    /** Build (and cache) the real framework gateway from [context]. */
    fun obtain(context: Context): JobSchedulerGateway {
        cached?.let { return it }
        val jobScheduler = context.applicationContext
            .getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
            ?: error("JobScheduler service not available")
        val service = PlatformPeriodicScheduling.jobServiceComponent(context)
        val gateway = AndroidJobSchedulerGateway(jobScheduler, service)
        cached = gateway
        return gateway
    }

    /** Clear the cached gateway (mostly tests). */
    fun reset() {
        cached = null
    }
}

/**
 * Convenience factory to build the **real** platform-backed
 * [JobSchedulerTriggerAdapter] for a [Context]. Used by
 * [PeriodicPlatformSchedulerFactory] and the restore coordinator.
 */
object PeriodicPlatformSchedulerFactory {
    fun real(context: Context): JobSchedulerTriggerAdapter =
        JobSchedulerTriggerAdapter(
            scheduler = PeriodicConsolidationScheduler(),
            gateway = PeriodicSchedulingGatewayHolder.obtain(context),
        )
}

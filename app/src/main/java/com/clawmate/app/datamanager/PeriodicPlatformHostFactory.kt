package com.clawmate.app.datamanager

import android.content.Context

/**
 * Phase-46 factory that builds a [PeriodicSchedulingHost] whose adapter is the
 * **real JobScheduler adapter** for a [Context] (or keeps the in-process seam when
 * no Context is available / tests).
 *
 * This is the single place the existing Settings VM wires the explicit
 * opt-in/schedule action to real OS scheduling. [PeriodicSchedulingHost.scheduleIfEnabled]
 * remains the one caller; the adapter swap is transparent to it.
 */
object PeriodicPlatformHostFactory {

    /**
     * Build a host backed by the real platform adapter. Requires a real
     * [Context]; `null` on a device-free construction.
     */
    fun platformHost(context: Context, baseDir: java.io.File): PeriodicSchedulingHost? =
        runCatching {
            val scheduler = PeriodicConsolidationScheduler()
            val adapter = JobSchedulerTriggerAdapter(
                scheduler = scheduler,
                gateway = PeriodicSchedulingGatewayHolder.obtain(context),
            )
            PeriodicSchedulingHost(baseDir, adapter)
        }.getOrNull()

    /**
     * The in-process seam (Phase-35/36 default) — kept intact for JVM tests and
     * device-free construction. Never removed (task #5).
     */
    fun inProcessHost(baseDir: java.io.File): PeriodicSchedulingHost =
        PeriodicSchedulingHost(baseDir)
}


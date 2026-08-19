package com.clawmate.app.datamanager

import android.content.ComponentName
import android.content.Context
import android.os.Build
import java.io.File

/**
 * Phase-46 **real Android platform scheduling for periodic local consolidation.**
 *
 * ## Why JobScheduler (and not AlarmManager)
 * The app targets `minSdk = 26`, `compileSdk = 34` and has **no androidx.work** in
 * its offline Gradle cache. JobScheduler is part of the Android framework
 * (`android.app.job.*`, API 21+) so it adds **no new dependency**. Compared to
 * AlarmManager + BroadcastReceiver it is the better fit here:
 *  - **reboot persistence for free**: `JobInfo.setPersisted(true)` re-registers the
 *    job after device reboot without needing `RECEIVE_BOOT_COMPLETED` or a manual
 *    boot receiver;
 *  - **battery-friendly periodic**: JobScheduler batches execution and enforces a
 *    15-minute minimum period, which is fine for a long-running local rebuild
 *    (the canonical job uses a 12h default interval via
 *    [SchedulingPolicyDefaults.DEFAULT_INTERVAL_MINUTES]);
 *  - **no extra permission**: AlarmManager exact alarms need `SCHEDULE_EXACT_ALARM`
 *    on newer Android and wake-launch timing; JobScheduler needs none.
 *
 * ## Single source of truth
 * Every OS-scheduled run still funnels through the existing
 * [LocalOnlyPeriodicJob] (which itself reuses [LocalConsolidationIndexRunner] +
 * Phase-33 scene/entity + Phase-32 local profile). There is **no second
 * consolidation engine**. The [JobSchedulerTriggerAdapter] is a new
 * `PeriodicTriggerAdapter` implementation that (a) keeps the deterministic
 * in-process decisions in [PeriodicConsolidationScheduler] and (b) additionally
 * mirrors a registered/`LOCAL_ONLY` + `NOT_REQUIRED` task onto a persisted
 * framework `JobScheduler` job.
 *
 * ## Explicit-only + disabled by default
 * The adapter only ever schedules when [PeriodicTriggerAdapter.schedule] is called
 * with an enabled `LOCAL_ONLY` policy — the same explicit Settings action already
 * wired in Phase 36. `cancel()` removes both the in-memory registration and the OS
 * job. Application launch never runs a job; restore only re-binds a persisted
 * schedule without executing.
 *
 * ## Network / data boundary
 * [LocalOnlyPeriodicJob] is deterministic local-only work (daily-log consolidate /
 * index / profile). The OS job never calls [CloudConsolidationRepository], never
 * auto-calls a cloud model, never opens chat/GUI/media-scan/TTS. The job is gated
 * inside [PeriodicLocalConsolidationJobService] by the persisted opt-in, so a
 * stale OS job that fires after the user disabled scheduling is a no-op that
 * cancels itself.
 */
object PlatformPeriodicScheduling {
    /** Unique JobScheduler job id for the canonical periodic local task. */
    const val JOB_ID: Int = 4601

    /** Application-private files base dir used by the local-only job. */
    fun filesBaseDir(context: Context): File = context.filesDir

    /** The [JobService] ComponentName (must match AndroidManifest). */
    fun jobServiceComponent(context: Context): ComponentName =
        ComponentName(context, PeriodicLocalConsolidationJobService::class.java)

    /** True when the host supports persisted JobScheduler jobs (API 21+/fine for 26). */
    fun frameworkSupportsPersistedJobs(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= 21
}

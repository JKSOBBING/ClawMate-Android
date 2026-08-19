package com.clawmate.app.datamanager

import android.content.Context
import com.clawmate.app.log.AppLogger

/**
 * Phase-46 **restart / boot reconciliation coordinator**.
 *
 * ## What it does (and what it deliberately does NOT)
 * On app startup ([android.app.Application.onCreate]) this is invoked once. It
 * only **reconciles the *registration*** so the in-memory view and the persisted
 * user intent agree after a process or device restart — it never *runs* a job,
 * never touches [CloudConsolidationRepository], never opens the network, chat,
 * GUI, media-scan or TTS.
 *
 * Exactly one of these happens:
 *  - persisted opt-in **true**  ∧  an OS job is pending  → no-op (already correct).
 *  - persisted opt-in **true**  ∧  no OS job is pending   → re-register the
 *    persisted local-only schedule (the user explicitly opted in; the reboot
 *    wiped only the transient binding, not their intent). The registration is a
 *    scheduling decision only — nothing executes at launch.
 *  - persisted opt-in **false** (or unknown/error) → cancel any stale OS job and
 *    leave the in-memory scheduler empty. A disabled user is never re-enabled.
 *
 * This is deliberately *not* called on Home load / recomposition / repository
 * construction — only from the single Application.onCreate path.
 *
 * ## Why this satisfies "恢复注册，不能偷偷执行"
 * `JobInfo.setPersisted(true)` already survives device reboot; when the OS
 * launches the app to serve a fired job it goes through
 * [PeriodicLocalConsolidationJobService] which gates on the *persisted* opt-in
 * and only then runs the local-only job. This coordinator only re-attaches the
 * binding and mirrors the opt-in into [PeriodicSchedulingRuntimeRegistry]; it
 * performs zero consolidation/index/profile rebuild itself.
 */
object PeriodicSchedulingRestoreCoordinator {

    /**
     * Reconcile registration with the persisted opt-in. Returns a short, honest
     * status string useful for logging/tests (never fabricated success).
     */
    fun reconcile(
        context: Context?,
        platform: () -> PeriodicSchedulingPlatformAccess = {
            PeriodicSchedulingPlatformAccess.fromContext(
                checkNotNull(context) { "reconcile requires a Context when no platform access is supplied" },
            )
        },
    ): String {
        val access = runCatching { platform() }.getOrElse { e ->
            AppLogger.w("PeriodicJob", "启动恢复：无法访问平台调度，按未配置处理（${e.message}）")
            PeriodicSchedulingRuntimeRegistry.clearOptedIn()
            return "not_configured"
        }

        val enabled = access.persistedOptIn ?: false
        val osPending = access.isOsJobPending()

        return when {
            enabled && osPending -> {
                // User still opted-in and the OS already holds the job: just mirror
                // the opt-in so a fired job runs; never double-schedule.
                PeriodicSchedulingRuntimeRegistry.setOptedIn(true)
                "already_registered"
            }
            enabled && !osPending -> {
                // Device reboot dropped the transient binding; restore registration.
                // (setPersisted would normally survive reboot; this re-binds in case
                // the OS pruned it and is safe/idempotent.)
                val result = access.restoreSchedule()
                if (result) {
                    PeriodicSchedulingRuntimeRegistry.setOptedIn(true)
                    AppLogger.i("PeriodicJob", "启动恢复：按持久化 opt-in 重新注册本地周期任务")
                    "restored"
                } else {
                    PeriodicSchedulingRuntimeRegistry.setOptedIn(true)
                    AppLogger.w("PeriodicJob", "启动恢复：平台拒绝重新注册（用户已启用，将等待显式重试）")
                    "schedule_refused"
                }
            }
            else -> {
                // Off (or unknown): cancel any stale job and keep in-memory empty.
                access.cancelSchedule()
                PeriodicSchedulingRuntimeRegistry.setOptedIn(false)
                "cancelled"
            }
        }
    }
}

/**
 * Thin, injectable access to the platform pieces a [PeriodicSchedulingRestoreCoordinator]
 * needs, so JVM tests can pass a fake (no device, no framework call).
 */
data class PeriodicSchedulingPlatformAccess(
    val persistedOptIn: Boolean?,
    val isOsJobPending: () -> Boolean,
    val restoreSchedule: () -> Boolean,
    val cancelSchedule: () -> Unit,
) {
    companion object {
        /** Build the real access from Android Context/JobScheduler. */
        fun fromContext(context: Context): PeriodicSchedulingPlatformAccess =
            PlatformPeriodicSchedulingAccess.real(context)
    }
}

/** Real framework-backed implementation of [PeriodicSchedulingPlatformAccess]. */
object PlatformPeriodicSchedulingAccess {
    fun real(context: Context): PeriodicSchedulingPlatformAccess {
        val scheduler = PeriodicSchedulingGatewayHolder.obtain(context)
        val repo = com.clawmate.app.settings.data.DataStoreSettingsRepository(context.applicationContext)
        return PeriodicSchedulingPlatformAccess(
            persistedOptIn = runCatching {
                kotlinx.coroutines.runBlocking { repo.snapshot().periodicLocalConsolidationEnabled }
            }.getOrNull(),
            isOsJobPending = { scheduler.isPending(PlatformPeriodicScheduling.JOB_ID) },
            restoreSchedule = {
                scheduler.schedule(
                    PlatformPeriodicScheduling.JOB_ID,
                    SchedulingPolicyDefaults.DEFAULT_INTERVAL_MINUTES * 60_000L,
                ) is com.clawmate.app.datamanager.platform.ScheduleResult.Success
            },
            cancelSchedule = { scheduler.cancel(PlatformPeriodicScheduling.JOB_ID) },
        )
    }
}

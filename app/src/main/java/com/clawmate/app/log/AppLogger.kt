package com.clawmate.app.log

import com.clawmate.app.log.data.InAppLogRepository
import com.clawmate.app.log.data.RingBufferInAppLogRepository
import com.clawmate.app.log.domain.LogLevel
import com.clawmate.app.log.domain.LogSource

/**
 * Phase 14 — lightweight in-app AppLogger singleton (Android).
 *
 * Mirror of the HarmonyOS `entry/src/main/ets/utils/AppLogger.ets` (same
 * `[prefix][module] message` stable prefix) that appends each call into the
 * [InAppLogRepository] instead of only a system log. Modules that run in the
 * app call [e] / [w] / [i] so the Log viewer reflects real events. Because the
 * ring buffer redacts and bounds on insert, this logger can never store a raw
 * secret.
 *
 * The logger is intentionally a static singleton (like the Harmony `AppLogger`)
 * backed by [component], but every one of its functions is also available as a
 * pure unit-testable utility taking an explicit [InAppLogRepository].
 */
object AppLogger {

    private const val DOMAIN_TAG = "ClawMate"

    /** The live repository backing the in-app viewer (swap in tests). */
    @Volatile
    var repository: InAppLogRepository = RingBufferInAppLogRepository()

    /**
     * Emit one record with a module-prefixed message, matching Harmony's
     * `AppModuleLogger` prefix convention. Never throws.
     */
    fun log(
        level: LogLevel,
        source: LogSource,
        module: String,
        message: String,
    ) {
        val repo = repository
        runCatching {
            repo.append(
                level = level,
                source = source,
                tag = module.ifBlank { "App" },
                message = "[${DOMAIN_TAG}][${if (module.isBlank()) "App" else module}] $message",
            )
        }
        // No rethrow: logging must never crash a screen.
    }

    // Convenience per-level helpers.
    fun d(module: String, message: String) = log(LogLevel.DEBUG, LogSource.APPLICATION, module, message)
    fun i(module: String, message: String) = log(LogLevel.INFO, LogSource.APPLICATION, module, message)
    fun w(module: String, message: String) = log(LogLevel.WARN, LogSource.APPLICATION, module, message)
    fun e(module: String, message: String) = log(LogLevel.ERROR, LogSource.APPLICATION, module, message)

    fun cloud(level: LogLevel, module: String, message: String) =
        log(level, LogSource.CLOUD_AGENT, module, message)

    fun gallery(level: LogLevel, module: String, message: String) =
        log(level, LogSource.GALLERY, module, message)

    fun workflow(level: LogLevel, module: String, message: String) =
        log(level, LogSource.WORKFLOW, module, message)
}

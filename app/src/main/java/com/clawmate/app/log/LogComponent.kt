package com.clawmate.app.log

import com.clawmate.app.log.data.InAppLogRepository
import com.clawmate.app.log.data.RingBufferInAppLogRepository

/**
 * Phase 14 — manual dependency container for the in-app log slice.
 *
 * Follows the ChatComponent / GalleryComponent / TaskComponent / SettingsComponent
 * pattern: one long-lived repository instance is created lazily and shared by
 * the [AppLogger] emitter and the Log viewer's observer for the process
 * lifetime. Nothing is written to disk; retention is an in-memory bounded ring
 * buffer sized by [RingBufferInAppLogRepository.DEFAULT_CAPACITY].
 */
object LogComponent {

    @Volatile
    private var repository: InAppLogRepository? = null

    /** The process-wide repository; creates a bounded ring buffer on first use. */
    fun repository(): InAppLogRepository =
        repository ?: RingBufferInAppLogRepository().also {
            synchronized(this) {
                if (repository == null) {
                    repository = it
                    AppLogger.repository = it
                }
            }
        }

    /** Reset the cached instance and detach the logger (used in tests). */
    fun reset() {
        synchronized(this) {
            repository?.clear()
            repository = null
        }
    }
}

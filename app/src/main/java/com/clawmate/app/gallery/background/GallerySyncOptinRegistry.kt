package com.clawmate.app.gallery.background

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-level mirror of the user's explicit opt-in to the background gallery
 * scan/sync boundary (Phase 42 explicit entry).
 *
 * This is the same "mirror the persisted opt-in onto a runtime flag" pattern the
 * overlay slice uses ([com.clawmate.app.overlay.OverlayCapabilityFlags]): the
 * *persisted* [com.clawmate.app.settings.domain.AppSettings.galleryBackgroundSyncEnabled]
 * value is copied here only when the settings are saved, and every gate
 * evaluation reads this flag. Nothing here ever starts a scan by itself — it
 * only records one observable fact ([optedIn]) that the honest
 * [GallerySyncGate] combines with the live media-read permission grant.
 *
 * The default is `false` so until the user explicitly opts in the boundary stays
 * NOT_CONFIGURED on every launch, matching the persisted default.
 */
object GallerySyncOptinRegistry {
    private val optedInFlag = AtomicBoolean(false)

    /** The current explicit opt-in (default false). */
    val optedIn: Boolean get() = optedInFlag.get()

    /** Mirror a persisted opt-in value onto the runtime gate (settings save). */
    fun setOptIn(value: Boolean) {
        optedInFlag.set(value)
    }

    /** Force the runtime flag back to the persistent default (mostly for tests). */
    fun reset() {
        optedInFlag.set(false)
    }
}

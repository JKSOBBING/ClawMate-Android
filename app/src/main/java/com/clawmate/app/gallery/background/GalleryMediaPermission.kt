package com.clawmate.app.gallery.background

/**
 * Runtime photo/media access state for the Gallery background scan/sync gate.
 *
 * The Photo Picker least-privilege path (each picked URI is read with an
 * explicit grant and no broad permission is requested) is **not** involved
 * here: a background, non-user-driven scan of the whole media library is a
 * different, broader capability that requires the user to explicitly grant
 * media read access (READ_MEDIA_IMAGES on API 33+, READ_EXTERNAL_STORAGE
 * below) and to explicitly opt in to background sync. Until both are true the
 * gate reports NOT_CONFIGURED/BLOCKED and no media is ever scanned.
 */
interface GalleryMediaPermission {
    /** Whether the app currently holds a usable photo/library read grant. */
    fun isPhotoAccessGranted(): Boolean

    /** A static, neutral note about which partial-grant state is observed. */
    fun stateNote(): String
}

/**
 * Deterministic gate for the Gallery background scanner.
 *
 * Mirrors the pattern of the Phase 19 [com.clawmate.app.accessibility.AccessibilityGate]
 * and Phase 20 overlay gate: it derives one honest status from a few injected,
 * observable facts and never executes anything.
 */
class GallerySyncGate(
    /** The user's explicit settings opt-in to background gallery sync. */
    private val optedIn: Boolean,
    /** Whether a runtime media/photo access grant is currently held. */
    private val photoAccessGranted: Boolean,
) {

    val status: GallerySyncGateStatus
        get() = when {
            !optedIn -> GallerySyncGateStatus.NOT_CONFIGURED
            !photoAccessGranted -> GallerySyncGateStatus.BLOCKED
            else -> GallerySyncGateStatus.READY
        }

    /** Human, honest reason for [status] used by UI/labels. */
    fun statusLabel(): String = when (status) {
        GallerySyncGateStatus.NOT_CONFIGURED -> "未启用后台图库同步 — NOT_CONFIGURED"
        GallerySyncGateStatus.BLOCKED -> "未授予媒体读取权限 — BLOCKED"
        GallerySyncGateStatus.READY -> "后台图库同步已就绪 — READY"
    }
}

/** Availability of the background scan/sync boundary. */
enum class GallerySyncGateStatus {
    /** User has not explicitly opted in — the boundary is dormant. */
    NOT_CONFIGURED,

    /** User opted in but a runtime media/photo permission is missing. */
    BLOCKED,

    /** Opted in AND photo-access granted — a serial run may start. */
    READY,
}

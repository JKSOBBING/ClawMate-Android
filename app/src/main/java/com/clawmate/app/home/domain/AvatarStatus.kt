package com.clawmate.app.home.domain

/**
 * Digital-avatar lifecycle status, ported from the HarmonyOS source
 * (`datamanager/avatar/AvatarTypes.ets`): `'empty' | 'current' | 'stale' |
 * 'generating' | 'failed'`.
 *
 * On Android no avatar generation/import engine exists yet, so the home UI
 * always reports [DISABLED]: an honest `empty` status with a NOT_CONFIGURED
 * action label. Nothing here ever fabricates a generated avatar or claims a
 * success it did not produce.
 */
enum class AvatarStatus {
    /** No avatar has ever been generated (mirrors source `'empty'`). */
    EMPTY,

    /** Avatar is up to date with the current profile (source `'current'`). */
    CURRENT,

    /** Avatar is stale relative to the current profile (source `'stale'`). */
    STALE,

    /** An avatar generation is in flight (source `'generating'`). */
    GENERATING,

    /** The last generation attempt failed (source `'failed'`). */
    FAILED,

    /** Avatar feature is not available on this platform/runtime. */
    DISABLED;

    companion object {
        /** Parse an arbitrary token (e.g. a persisted value) to a status. */
        fun fromToken(token: String): AvatarStatus = when (token.lowercase()) {
            "current" -> CURRENT
            "stale" -> STALE
            "generating" -> GENERATING
            "failed" -> FAILED
            else -> EMPTY
        }

        /** Stable machine token for persistence / logs. */
        fun of(status: AvatarStatus): String = when (status) {
            EMPTY -> "empty"
            CURRENT -> "current"
            STALE -> "stale"
            GENERATING -> "generating"
            FAILED -> "failed"
            DISABLED -> "disabled"
        }
    }
}

/**
 * Honest, human-facing copy for the avatar section header.
 *
 * On Android the avatar engine is NOT configured, so the status is [AvatarStatus
 * .DISABLED] with a NOT_CONFIGURED label — never a fabricated success.
 */
data class AvatarSection(
    val status: AvatarStatus,
    val statusLabel: String,
    val actionLabel: String,
    val updatedLabel: String,
    val hasAvatarImage: Boolean = false,
    val imageUri: String = "",
) {
    companion object {
        /** The safe default for a platform with no avatar engine wired up. */
        val notConfigured = AvatarSection(
            status = AvatarStatus.DISABLED,
            statusLabel = "未配置数字分身",
            actionLabel = "未启用",
            updatedLabel = "暂无",
            hasAvatarImage = false,
            imageUri = "",
        )
    }
}

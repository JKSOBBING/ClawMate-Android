package com.clawmate.app.profile.avatar.data.asset

import com.clawmate.app.profile.avatar.domain.AvatarGenerationMode
import com.clawmate.app.profile.avatar.domain.AvatarStatusToken

/**
 * Pure, persisted wire models for the local digital-avatar **asset repository**
 * (the portable part of migration row **A2**).
 *
 * Ported from the HarmonyOS source of truth (read-only):
 *   entry/src/main/ets/datamanager/avatar/AvatarStorage.ets
 *   entry/src/main/ets/datamanager/avatar/AvatarTypes.ets
 *   entry/src/main/ets/datamanager/DataManagerPaths.ets
 */
object AvatarAssetLimits {

    /** Default cap for a single imported avatar asset (256 KB). */
    const val DEFAULT_MAX_IMPORT_BYTES: Int = 256 * 1024

    /** Minimum decodable canvas dimension for a local image import. */
    const val MIN_DECODE_DIMENSION: Int = 8

    /** Defensive upper bound on an accepted decode dimension. */
    const val MAX_IMPORT_DIMENSION: Int = 8192
}

/**
 * One persisted version record in the avatar asset repository. Mirrors the
 * storage-level fields of `AvatarVersionState` in AvatarTypes.ets that the
 * local subset needs; image/thumbnail live under app-private `avatar/assets/`.
 */
data class AvatarVersionRecord(
    val versionId: String = "",
    val createdAt: String = "",
    val imageRelativePath: String = "",
    val thumbnailRelativePath: String = "",
    val mode: AvatarGenerationMode = AvatarGenerationMode.TEXT_TO_IMAGE,
    val provider: String = "",
    val model: String = "",
    val promptSnapshot: String = "",
    val profileSnapshotHash: String = "",
) {
    companion object {
        fun empty() = AvatarVersionRecord()
    }
}

/** The `AvatarState`-equivalent persisted via DataManager-style `state.json`. */
data class AvatarAssetStateModel(
    val currentVersionId: String = "",
    val profileSnapshotHash: String = "",
    val status: AvatarStatusToken = AvatarStatusToken.EMPTY,
    val lastErrorCode: String = "",
    val lastErrorMessage: String = "",
    val updatedAt: String = "",
    val referencePhotoPath: String = "",
)

/** Typed outcome of a local-image import, so the UI never guesses success. */
data class AvatarImportResult(
    val state: AvatarAssetStateModel,
    val version: AvatarVersionRecord? = null,
    val errorMessage: String? = null,
) {
    val success: Boolean get() = errorMessage == null && version != null
}

/**
 * Typed result of clearing / deleting avatar assets, so the UI can report an
 * honest "cleared" vs "nothing to clear".
 */
data class AvatarClearResult(
    val state: AvatarAssetStateModel = AvatarAssetStateModel(),
    val clearedVersionCount: Int = 0,
)

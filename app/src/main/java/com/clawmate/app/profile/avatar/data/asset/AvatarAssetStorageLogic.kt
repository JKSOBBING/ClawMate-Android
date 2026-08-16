package com.clawmate.app.profile.avatar.data.asset

import com.clawmate.app.profile.avatar.domain.AvatarGenerationMode
import com.clawmate.app.profile.avatar.domain.AvatarStatusToken
import java.util.Locale

/**
 * Pure, deterministic port of the *local* half of `AvatarStorage.ets` —
 * ordering / index / extension / reference-photo naming / state normalisation.
 *
 * Every function here is side-effect free apart from the injected [FileIo] seam
 * (so unit tests run fully device-free on the JVM). It mirrors, where relevant:
 *   - `listVersions()` newest-first `created_at.localeCompare` ordering;
 *   - the `version-index.json` dedup append (`loadVersionIds`/`saveVersionId`);
 *   - `nextReferencePhotoPath()`/`removeStaleReferencePhotoFiles()` for the
 *     single-current-reference-photo cache policy;
 *   - `referencePhotoExtension()`/`avatarAssetExtension()` extension resolution;
 *   - `normalizeState()`/`safeStatus()` state sanitisation.
 *
 * Nothing here generates or fabricates an image. Imported bytes are treated as
 * untrusted input and validated to actually decode before being persisted.
 */
object AvatarAssetStorageLogic {

    /** Newest-first ordering by persisted `createdAt` (mirrors .ets sort). */
    fun versionsNewestFirst(records: List<AvatarVersionRecord>): List<AvatarVersionRecord> =
        records.sortedWith { a, b -> b.createdAt.compareTo(a.createdAt) }

    /**
     * Append [newId] to a version-index list if absent. Deduplicates blank ids
     * (mirrors `saveVersionId`: blank ids are ignored). Order is preserved.
     */
    fun appendVersionId(indexIds: List<String>, newId: String): List<String> {
        if (newId.isBlank()) return indexIds
        val ids = ArrayList<String>(indexIds.size + 1)
        for (id in indexIds) {
            val clean = id.trim()
            if (clean.isNotBlank() && clean !in ids) ids.add(clean)
        }
        if (newId.trim() !in ids) ids.add(newId.trim())
        return ids
    }

    /**
     * Map a version record to the index id ordering it would be given when
     * listed — a convenience for tests to assert ordering across persisted JSON.
     */
    fun sortedVersionIds(records: List<AvatarVersionRecord>): List<String> =
        versionsNewestFirst(records.map { it }).map { it.versionId }

    /**
     * Resolve a persisted asset-relative path from a version id and output
     * format. Port of `relativeAvatarAssetPath`/`avatarAssetExtension`.
     */
    fun relativeAssetPath(versionId: String, outputFormat: String?): String =
        "assets/$versionId${assetExtension(outputFormat)}"

    /**
     * Asset file extension from an output format (default JPEG; PNG/WEBP honoured).
     * Accepts both a bare format token (`"png"`) and a dot-prefixed extension
     * (`".png"`, as returned by [referencePhotoExtension]) so the two stay
     * consistent when building asset-relative paths.
     */
    fun assetExtension(outputFormat: String?): String {
        val format = (outputFormat ?: "jpeg").trim().lowercase(Locale.US).removePrefix(".")
        return when (format) {
            "png" -> ".png"
            "webp" -> ".webp"
            else -> ".jpg"
        }
    }

    /**
     * Select the stored extension for a picked/source reference image. Port of
     * `AvatarStorage.referencePhotoExtension()`: only genuinely image-y sources
     * map to a known extension, everything else (untrusted) falls back to `.jpg`
     * so the import path never interprets arbitrary data as a specific codec.
     * Both the standard content MIME forms (`image/png`) and `data:image/png`
     * URIs / `.png`-suffixed names are recognised as image-y.
     */
    fun referencePhotoExtension(source: String): String {
        val normalized = source.trim().lowercase(Locale.US)
        return when {
            normalized.startsWith("image/png") || normalized.startsWith("data:image/png") ||
                normalized.endsWith(".png") -> ".png"
            normalized.startsWith("image/webp") || normalized.startsWith("data:image/webp") ||
                normalized.endsWith(".webp") -> ".webp"
            normalized.startsWith("image/heic") || normalized.startsWith("data:image/heic") ||
                normalized.endsWith(".heic") -> ".heic"
            normalized.startsWith("image/heif") || normalized.startsWith("data:image/heif") ||
                normalized.endsWith(".heif") -> ".heif"
            else -> ".jpg"
        }
    }

    /**
     * Reference-photo files keep only the **most recently saved** one: port of
     * `nextReferencePhotoPath` + `removeStaleReferencePhotoFiles`. [FileIo.list]
     * returns names under the assets root that start with the reference prefix;
     * the newest (kept) name is excluded from the returned "stale" removal set.
     */
    fun staleReferencePhotoNames(keepName: String, assetNames: List<String>): List<String> {
        val keep = keepName.trim()
        return assetNames
            .filter { it.startsWith(REFERENCE_PHOTO_PREFIX) && it != keep }
            .sorted() // deterministic; the newest reference already won as keep
    }

    /** Filename prefix used for imported reference photos (mirrors the .ets name). */
    const val REFERENCE_PHOTO_PREFIX: String = "reference-photo"

    /** Deterministic candidate name for a reference photo given a timestamp + sequence. */
    fun referencePhotoName(timestampMillis: Long, sequence: Long, extension: String): String =
        "$REFERENCE_PHOTO_PREFIX-$timestampMillis-$sequence${extension.lowercase(Locale.US)}"

    /**
     * Normalise persisted avatar state defensively (port of `normalizeState` /
     * `safeStatus`): unknown status tokens fall back to [AvatarStatusToken.EMPTY]
     * and null/blank scalar fields become `""`. The UI can never be blocked or
     * lied to by a malformed state file.
     */
    fun normalizeState(state: AvatarAssetStateModel): AvatarAssetStateModel = AvatarAssetStateModel(
        currentVersionId = safe(state.currentVersionId),
        profileSnapshotHash = safe(state.profileSnapshotHash),
        status = safeStatus(state.status),
        lastErrorCode = safe(state.lastErrorCode),
        lastErrorMessage = safe(state.lastErrorMessage),
        updatedAt = safe(state.updatedAt),
        referencePhotoPath = safe(state.referencePhotoPath),
    )

    private fun safe(value: String): String = value.trim()
    private fun safeStatus(value: AvatarStatusToken): AvatarStatusToken = when (value) {
        AvatarStatusToken.EMPTY, AvatarStatusToken.CURRENT, AvatarStatusToken.STALE,
        AvatarStatusToken.GENERATING, AvatarStatusToken.FAILED -> value
    }

    /**
     * Default empty state, mirroring `createEmptyAvatarState()` in AvatarTypes.ets.
     */
    fun emptyState(): AvatarAssetStateModel = AvatarAssetStateModel()

    /**
     * Build the honest state after a successful local import: the newest real
     * local version becomes the current one. This is a *real asset*, so the
     * status is [AvatarStatusToken.CURRENT]; generation nonetheless stays gated
     * separately (see AvatarGenerationGate) and is never implied here.
     */
    fun stateAfterImport(
        previous: AvatarAssetStateModel,
        version: AvatarVersionRecord,
        referencePhotoPath: String = previous.referencePhotoPath,
    ): AvatarAssetStateModel = AvatarAssetStateModel(
        currentVersionId = version.versionId,
        profileSnapshotHash = version.profileSnapshotHash,
        status = AvatarStatusToken.CURRENT,
        lastErrorCode = "",
        lastErrorMessage = "",
        updatedAt = version.createdAt,
        referencePhotoPath = referencePhotoPath,
    )

    /**
     * Build the [AvatarVersionRecord] that persists a real provider-generated
     * avatar (migration row A2 local asset result path). The version id
     * (`seedream-<seq>`) distinguishes a generated image from a `local-import`,
     * and `provider` is `"seedream"` exactly as the Harmony `AvatarService` does.
     * The record stays defensive-oversized-checked by the repository before write.
     */
    fun generatedVersionRecord(
        versionId: String,
        createdAt: String,
        relativeImagePath: String,
        mode: AvatarGenerationMode,
        model: String,
        prompt: String,
        profileHash: String,
    ): AvatarVersionRecord = AvatarVersionRecord(
        versionId = versionId,
        createdAt = createdAt,
        imageRelativePath = relativeImagePath,
        mode = mode,
        provider = "seedream",
        model = model.takeIf { it.isNotBlank() } ?: "seedream",
        promptSnapshot = prompt.take(4096),
        profileSnapshotHash = profileHash,
    )


    /**
     * Simple structural size guard for a single asset record payload before
     * persistence writes it (defence in depth on top of the import byte cap).
     */
    fun isRecordOversized(record: AvatarVersionRecord, maxBytes: Int = 4096): Boolean =
        record.promptSnapshot.length + record.profileSnapshotHash.length +
            record.imageRelativePath.length + record.versionId.length > maxBytes

    /**
     * Minimal data-access seam used by the pure storage logic and the
     * repository so ordering/clear/validation are device-free and testable.
     * Production uses `java.io.File`; tests inject an in-memory probe.
     */
    interface FileIo {
        fun list(dirRelative: String): List<String>
        fun exists(relativePath: String): Boolean
        fun delete(relativePath: String): Boolean
        fun sizeOf(relativePath: String): Long
    }
}

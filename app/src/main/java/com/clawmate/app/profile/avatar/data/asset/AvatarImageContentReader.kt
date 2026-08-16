package com.clawmate.app.profile.avatar.data.asset

import android.content.Context
import android.net.Uri

/**
 * Lifecycle-safe, bounded reader for a user-picked avatar image.
 *
 * Streams the granted content URI through the [ContentResolver] into memory but
 * aborts as soon as the byte budget is exceeded, so an oversized/arbitrary file
 * is rejected *before* it is fully buffered (the same bounded-sink behaviour the
 * Phase-17 profile-share importer uses). Returns `null` when the URI is not
 * readable, empty, or over the [maxBytes] budget — the caller surfaces an honest
 * error; nothing is invented.
 *
 * Note [maxBytes] is a soft read budget; the file-backed repository enforces the
 * same cap authoritatively via [AvatarAssetLimits.DEFAULT_MAX_IMPORT_BYTES] and
 * also validates that the bytes actually decode as a real image.
 */
object AvatarImageContentReader {

    /**
     * Read exactly the picker-granted [uri] as a byte array bounded by
     * [maxBytes]. A `null` result means unreadable / empty / over budget.
     */
    fun readBounded(context: Context, uri: Uri, maxBytes: Int = AvatarAssetLimits.DEFAULT_MAX_IMPORT_BYTES): ByteArray? {
        if (uri == Uri.EMPTY) return null
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return@runCatching null // over budget → reject
                    buffer.write(chunk, 0, n)
                }
                if (total == 0) null else buffer.toByteArray()
            }
        }.getOrNull()
    }
}

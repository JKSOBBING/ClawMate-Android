package com.clawmate.app.gallery.background

import android.Manifest
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/**
 * Production Android runtime photo-access detector.
 *
 * Mirrors the minimal pattern of the Phase 20 overlay detector but for media:
 * it reports whether the app currently holds a usable photo/library read grant
 * (`READ_MEDIA_IMAGES` on API 33+, `READ_EXTERNAL_STORAGE` on API <= 32). It
 * is safe on every API level and, crucially, it is only ever *read* — this
 * boundary never requests the permission itself and never broadens the Photo
 * Picker least-privilege path.
 */
class AndroidGalleryMediaPermission(private val context: Context) : GalleryMediaPermission {

    override fun isPhotoAccessGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, permissionName()) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun stateNote(): String = when (isPhotoAccessGranted()) {
        true -> "已授予图库读取权限"
        false -> "未授予图库读取权限（后台扫描 BLOCKED）"
    }

    private fun permissionName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
}

/** Deterministic fake for device-free tests. */
class FakeGalleryMediaPermission(
    private val granted: Boolean,
) : GalleryMediaPermission {
    override fun isPhotoAccessGranted(): Boolean = granted
    override fun stateNote(): String = if (granted) "granted" else "not-granted"
}

/**
 * Production media-library source backed by [MediaStore].
 *
 * Like `GalleryAutoScanner.scanNewest`, it returns the newest image inputs
 * (DATE_ADDED descending) without reading pixels. It is only ever invoked by
 * the coordinator after the [GallerySyncGate] is READY, so the caller must also
 * hold the runtime grant; the source refuses to query otherwise (defense in
 * depth — never scan without a grant).
 *
 * URIs, ids and dates are treated as untrusted input and sanitized/made stable
 * deterministically; malformed rows are skipped, never fabricated.
 */
class MediaStoreGalleryMediaSource(
    private val context: Context,
    private val permission: GalleryMediaPermission = AndroidGalleryMediaPermission(context),
) : GalleryMediaSource {

    override suspend fun scanNewest(limit: Int): List<GalleryPhotoInput> {
        if (limit <= 0) return emptyList()
        if (!permission.isPhotoAccessGranted()) return emptyList()
        // Do NOT project MediaStore.Images.Media.DATA: modern SDKs' scoped
        // storage may omit the filesystem path for a row, and the content URI is
        // always derivable from the row _ID alone. Each column is read
        // defensively (optional/absent columns fall back to a safe default).
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val baseUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString()
        val out = ArrayList<GalleryPhotoInput>()
        val resolver = context.contentResolver
        runCatching {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                while (out.size < limit && cursor.moveToNext()) {
                    val id = if (!cursor.isNull(idCol)) cursor.getString(idCol) else null
                        ?: continue
                    val uri = MediaStoreUriBuilder.contentUriFor(baseUri, id)
                    if (uri.isEmpty()) continue
                    val dateAdded = if (dateCol >= 0 && !cursor.isNull(dateCol)) cursor.getLong(dateCol) else 0L
                    val date = GalleryAndroidDateFormatter.formatDate(dateAdded)
                    out.add(
                        GalleryPhotoInput(
                            uri = uri,
                            id = com.clawmate.app.gallery.model.GalleryIds.stableIdForUri(uri),
                            date = date,
                            latitude = null,
                            longitude = null,
                        ),
                    )
                }
            }
        }
        return out
    }
}

/**
 * Pure, device-free content-URI builder so the source stays host-testable.
 *
 * Modern scoped storage may omit the filesystem `DATA` path entirely, but every
 * MediaStore image row still has a stable `_ID`, so a valid
 * `content://.../media/<id>` URI is always derivable from the id alone. The
 * builder never reads, writes, or fabricates filesystem paths.
 */
internal object MediaStoreUriBuilder {
    /**
     * Builds a `content://<base>/<id>` URI string from a base media URI and the
     * row `_ID`, or returns `""` when the base is blank or the id is not a
     * plain numeral (defensive: refuse malformed/traversal ids rather than
     * fabricate a URI).
     */
    fun contentUriFor(baseUri: String, id: String): String {
        val cleanBase = baseUri.trim().removeSuffix("/")
        val cleanId = id.trim()
        if (cleanBase.isEmpty() || cleanId.isEmpty() || !cleanId.all { it.isDigit() }) return ""
        return "$cleanBase/$cleanId"
    }
}

/** Tiny pure date formatter so the source stays device-free-testable. */
internal object GalleryAndroidDateFormatter {
    private val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    fun formatDate(epochSecondsOrMs: Long): String {
        if (epochSecondsOrMs <= 0) return ""
        val ms = if (epochSecondsOrMs < 100_000_000_000L) epochSecondsOrMs * 1000L else epochSecondsOrMs
        return fmt.format(java.util.Date(ms))
    }
}

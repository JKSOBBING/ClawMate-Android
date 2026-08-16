package com.clawmate.app.gallery.photo

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.exifinterface.media.ExifInterface
import com.clawmate.app.gallery.model.GalleryExifMetadata
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Result of a photo-pick launch. Typed states let the UI react without holding
 * a broad storage permission.
 */
sealed class PickResult {
    /** User picked one or more photos; [uris] are content URIs with grant. */
    data class Picked(val uris: List<Uri>) : PickResult()

    /** User cancelled the system picker. */
    data object Cancelled : PickResult()

    /** The system picker did not return / is not available in this context. */
    data class Unavailable(val reason: String) : PickResult()
}

/**
 * Compose-launchable adapter for the Android Photo Picker (getContent-style).
 *
 * The Photo Picker grants read access to exactly the chosen URIs, so no broad
 * `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE` request is needed here. The
 * manifest lists those permissions only as the unrelated surface for a
 * not-yet-wired auto-scan path and they are never requested at runtime by this
 * slice. A denied/cancelled/back-press pick maps to [PickResult.Cancelled].
 *
 * HarmonyOS source of truth (read-only):
 *   entry/src/main/ets/utils/gallery/GalleryPhotoPicker.ets
 *     (PhotoViewPicker with IMAGE_TYPE, maxSelectNumber, isOriginalSupported)
 */
@Composable
fun rememberPhotoPicker(
    maxSelectNumber: Int = 9,
    onResult: (PickResult) -> Unit,
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxSelectNumber),
    ) { uris ->
        onResult(
            when {
                // Picker cancelled/denied/back-press returns an empty result.
                uris.isEmpty() -> PickResult.Cancelled
                else -> PickResult.Picked(uris)
            },
        )
    }
    return {
        launcher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }
}

/**
 * Resolves a picked [Uri] into real metadata via [Context.contentResolver] and
 * [ExifInterface]. Nothing is fabricated: unobtainable fields are null and the
 * caller decides how to surface a missing value.
 *
 * Mirrors the HarmonyOS "picker returns only a URI; EXIF and archive date are
 * resolved when decoding the image" behaviour.
 */
object MediaMetadataReader {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /**
     * Archive date for a content URI. Prefers MediaStore `DATE_TAKEN`, then
     * EXIF `DateTimeOriginal`, then MediaStore `DATE_ADDED`, then the epoch of
     * import. The last fallback keeps items storable when the provider hides
     * metadata; it is an import-time value, never a fake "photo date".
     */
    fun resolveDate(context: Context, uri: Uri): String {
        val epoch = queryImageLong(context, uri, MediaStore.Images.Media.DATE_TAKEN)
            ?: readExifDate(context, uri)
            ?: queryImageLong(context, uri, MediaStore.Images.Media.DATE_ADDED)
            ?: System.currentTimeMillis()
        return dateFormat.format(Date(epoch))
    }

    /**
     * EXIF-ish metadata the platform exposes: device model, image dimensions,
     * and (when the picker grants location) GPS from EXIF bytes. Returns null
     * when nothing could be resolved — missing metadata is reported, never
     * fabricated.
     */
    fun resolveExif(context: Context, uri: Uri): GalleryExifMetadata? {
        val model = queryImageString(context, uri, "model")
        val width = queryImageInt(context, uri, MediaStore.Images.Media.WIDTH)
        val height = queryImageInt(context, uri, MediaStore.Images.Media.HEIGHT)
        val fromBytes = readExifStream(context, uri)
        if (model == null && width == null && height == null && fromBytes == null) {
            return null
        }
        return GalleryExifMetadata(
            deviceModel = model,
            imageWidth = width ?: fromBytes?.imageWidth,
            imageHeight = height ?: fromBytes?.imageHeight,
            latitude = fromBytes?.latitude,
            longitude = fromBytes?.longitude,
        )
    }

    /** Whether the URI is actually readable through the content resolver. */
    fun isAccessible(context: Context, uri: Uri): Boolean =
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)

    private fun queryImageLong(context: Context, uri: Uri, column: String): Long? =
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(column),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst() && c.isNull(0).not()) c.getLong(0) else null
            }
        }.getOrDefault(null)

    private fun queryImageInt(context: Context, uri: Uri, column: String): Int? =
        queryImageLong(context, uri, column)?.toInt()

    private fun queryImageString(context: Context, uri: Uri, column: String): String? =
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(column),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst() && c.isNull(0).not()) c.getString(0) else null
            }
        }.getOrDefault(null)

    private fun readExifDate(context: Context, uri: Uri): Long? =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            }
        }.getOrNull()?.let { dat ->
            runCatching {
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(dat.trim())?.time
            }.getOrNull()
        }

    private fun readExifStream(context: Context, uri: Uri): GalleryExifMetadata? =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).takeIf { it > 0 }
                val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).takeIf { it > 0 }
                val ll = exif.latLong
                GalleryExifMetadata(
                    imageWidth = width,
                    imageHeight = height,
                    latitude = ll?.getOrNull(0),
                    longitude = ll?.getOrNull(1),
                )
            }
        }.getOrNull()
}

package com.clawmate.app.home.data

import android.graphics.BitmapFactory
import com.clawmate.app.home.data.HomeAvatarSectionLoader.ReadableImageCheck
import com.clawmate.app.profile.avatar.data.asset.AvatarAssetLimits
import java.io.File

/**
 * Production [ReadableImageCheck]: a resolved absolute app-private asset path is
 * displayed only when the underlying file is a *real, readable* image.
 *
 * It re-applies the same safety gate the [com.clawmate.app.profile.avatar.data.
 * DiskAvatarAssetRepository] enforces at write time, but on the **read/display**
 * path:
 *  - the file exists, is a plain readable file, and is non-empty;
 *  - its on-disk size is within [AvatarAssetLimits.DEFAULT_MAX_IMPORT_BYTES]
 *    (defensive: a bloated/corrupt file is not shown);
 *  - it actually decodes to valid image bounds within
 *    [AvatarAssetLimits.MIN_DECODE_DIMENSION]..[AvatarAssetLimits.MAX_IMPORT_DIMENSION]
 *    (so a corrupt or non-image asset is never shown as a success).
 *
 * Nothing is logged, and no absolute path leaves this object's scope other than
 * the display URI it returns to the Home card.
 */
class FileHomeAvatarImageCheck(
    private val maxBytes: Int = AvatarAssetLimits.DEFAULT_MAX_IMPORT_BYTES,
) : ReadableImageCheck {

    override fun readableDisplayUri(absolutePath: String?): String? {
        val path = absolutePath?.trim().orEmpty()
        if (path.isEmpty()) return null
        val file = File(path)
        if (!file.isFile) return null
        if (!file.canRead()) return null
        val length = file.length()
        // Empty file → not a real image; oversized → over the existing budget.
        if (length <= 0L || length > maxBytes) return null
        fun validBounds(): Boolean {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            val w = opts.outWidth
            val h = opts.outHeight
            if (w <= 0 || h <= 0) return false
            if (w < AvatarAssetLimits.MIN_DECODE_DIMENSION || h < AvatarAssetLimits.MIN_DECODE_DIMENSION) return false
            if (w > AvatarAssetLimits.MAX_IMPORT_DIMENSION || h > AvatarAssetLimits.MAX_IMPORT_DIMENSION) return false
            return true
        }
        if (!runCatching { validBounds() }.getOrDefault(false)) return null
        return "file://$path"
    }
}

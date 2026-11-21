// Adapted from wangzan101/Mika at 9fd0be4 (Apache-2.0). See third_party/mika/LICENSE.
package com.clawmate.platform.display

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

/**
 * 系统把逻辑画面顺时针旋转到固定的物理缓冲;反向转回来,让预览、PNG 和 OCR 都用应用的逻辑坐标。
 * 直接画到目标尺寸,不为每张预览另建一张全尺寸的中间图;源矩形不含 ImageReader 的行填充。
 */
internal fun uprightDisplayFrame(
    buffer: Bitmap,
    geometry: DisplayFrameGeometry,
    maxWidth: Int = geometry.width,
    paint: Paint = Paint(Paint.FILTER_BITMAP_FLAG),
): Bitmap {
    val (width, height) = geometry.previewSize(maxWidth)
    val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(result).apply {
        scale(width.toFloat() / geometry.width, height.toFloat() / geometry.height)
        when (geometry.rotation) {
            1 -> { translate(0f, geometry.bufferWidth.toFloat()); rotate(-90f) }
            2 -> { translate(geometry.bufferWidth.toFloat(), geometry.bufferHeight.toFloat()); rotate(180f) }
            3 -> { translate(geometry.bufferHeight.toFloat(), 0f); rotate(90f) }
        }
        val content = Rect(0, 0, geometry.bufferWidth, geometry.bufferHeight)
        drawBitmap(buffer, content, content, paint)
    }
    return result
}

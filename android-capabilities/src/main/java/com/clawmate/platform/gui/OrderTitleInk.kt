package com.clawmate.platform.gui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Recheck doubtful Han characters in a printed product title against local font outlines. */
internal object OrderTitleInk {
    private const val SIDE = 20
    private const val SIZE = SIDE * SIDE
    private fun font() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 48f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textLocale = Locale.SIMPLIFIED_CHINESE
        color = Color.BLACK
    }

    private class Mask(val pixels: IntArray, val w: Int, val h: Int) {
        fun ink(x: Int, y: Int): Boolean {
            val p = pixels[y * w + x]
            return maxOf((p shr 16) and 255, (p shr 8) and 255, p and 255) < 145
        }

        fun shape(from: Int, until: Int): ByteArray? {
            var l = until
            var r = -1
            var t = h
            var b = -1
            for (y in 0 until h) for (x in from until until) if (ink(x,y)) {
                l = minOf(l,x); r = maxOf(r,x); t = minOf(t,y); b = maxOf(b,y)
            }
            if (r - l < 5 || b - t < 7) return null
            val bits = ByteArray(SIZE)
            for (v in 0 until SIDE) for (u in 0 until SIDE) {
                val x1 = l + u * (r-l+1) / SIDE
                val x2 = maxOf(x1+1, l + (u+1) * (r-l+1) / SIDE)
                val y1 = t + v * (b-t+1) / SIDE
                val y2 = maxOf(y1+1, t + (v+1) * (b-t+1) / SIDE)
                var dark = 0
                for (y in y1 until y2) for (x in x1 until x2) if (ink(x,y)) dark++
                bits[v * SIDE + u] = (dark * 120 / ((x2-x1)*(y2-y1))).toByte()
            }
            return bits
        }
    }

    private val glyphs: Map<Char, ByteArray> by lazy {
        val glyphMap = linkedMapOf<Char, ByteArray>()
        val bitmap = Bitmap.createBitmap(62, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = font()
        val mask = Mask(IntArray(62*64),62,64)
        val charset = charset("GB2312")
        try {
            // GB 2312 level one contains the commonly used simplified Chinese characters.
            for (row in 0xb0..0xd7) for (column in 0xa1..0xfe) {
                val c = String(byteArrayOf(row.toByte(), column.toByte()), charset).singleOrNull() ?: continue
                if (c !in '\u4e00'..'\u9fff') continue
                bitmap.eraseColor(Color.WHITE)
                canvas.drawText(c.toString(),4f,52f,paint)
                bitmap.getPixels(mask.pixels,0,mask.w,0,0,mask.w,mask.h)
                mask.shape(0,mask.w)?.let { glyphMap[c] = it }
            }
        } finally { bitmap.recycle() }
        glyphMap
    }

    private fun distance(a: ByteArray, b: ByteArray, bound: Int): Int {
        var sum = 0
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += d*d
            if (sum > bound) break
        }
        return sum
    }

    fun correct(bitmap: Bitmap, guess: String): String {
        if (guess.isBlank() || guess.length > 72 || guess.none { it in '\u4e00'..'\u9fff' }) return guess
        val w = bitmap.width
        val h = bitmap.height
        val mask = Mask(IntArray(w*h),w,h)
        bitmap.getPixels(mask.pixels,0,w,0,0,w,h)
        val projection = IntArray(w) { x -> (0 until h).count { mask.ink(x,it) } }
        val first = projection.indexOfFirst { it > 0 }
        val last = projection.indexOfLast { it > 0 }
        if (first < 0 || last-first < 12) return guess
        val paint = font()
        val advances = FloatArray(guess.length)
        paint.getTextWidths(guess, advances)
        val total = advances.sum()
        if (total <= 0f) return guess
        val edges = IntArray(guess.length+1)
        edges[0] = first
        edges[guess.length] = last+1
        var measured = 0f
        for (i in 1 until guess.length) {
            measured += advances[i-1]
            val center = first + (measured/total*(last+1-first)).roundToInt()
            val range = ((center-h/8).coerceAtLeast(edges[i-1]+1))..((center+h/8).coerceAtMost(last))
            edges[i] = range.minByOrNull { projection[it]*32+abs(it-center) } ?: return guess
        }
        val result = guess.toCharArray()
        // Costs are mean squared ink-density differences on a common 20 x 20 grid.
        val unit = SIZE*120*120
        for (i in result.indices) {
            val ch = guess[i]
            if (ch !in '\u4e00'..'\u9fff' || edges[i+1]-edges[i] < 10) continue
            val shape = mask.shape(edges[i],edges[i+1]) ?: continue
            val originalShape = glyphs[ch] ?: run {
                val reference = Bitmap.createBitmap(62, 64, Bitmap.Config.ARGB_8888)
                try {
                    reference.eraseColor(Color.WHITE)
                    Canvas(reference).drawText(ch.toString(), 4f, 52f, paint)
                    val referenceMask = Mask(IntArray(62 * 64), 62, 64)
                    reference.getPixels(referenceMask.pixels, 0, 62, 0, 0, 62, 64)
                    referenceMask.shape(0, 62)
                } finally { reference.recycle() }
            } ?: continue
            val original = distance(shape, originalShape, Int.MAX_VALUE)
            if (original < unit * .15) continue
            var best = unit
            var second = unit
            var winner = ch
            for ((candidate, sample) in glyphs) {
                val value = distance(shape,sample,second)
                if (value < best) { second = best; best = value; winner = candidate }
                else if (value < second) second = value
            }
            if (best < unit*.14 && original-best > unit*.07 && second-best > unit*.019) result[i] = winner
        }
        return String(result)
    }
}

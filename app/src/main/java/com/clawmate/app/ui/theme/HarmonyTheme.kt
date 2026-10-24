package com.clawmate.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import com.clawmate.app.R

/** The source's AppTheme color adaptation is part of its visual contract. */
object HarmonyTheme {
    val page @Composable get() = colorResource(R.color.ui_page_background)
    val pageWhite @Composable get() = colorResource(R.color.ui_page_white)
    val surface @Composable get() = colorResource(R.color.ui_surface)
    val soft @Composable get() = colorResource(R.color.ui_surface_soft)
    val primaryText @Composable get() = colorResource(R.color.ui_text_primary)
    val secondaryText @Composable get() = colorResource(R.color.ui_text_secondary)
    val mutedText @Composable get() = colorResource(R.color.ui_text_muted)
    val border @Composable get() = colorResource(R.color.ui_border)
    val glass @Composable get() = colorResource(R.color.ui_glass_surface)
    val glassTitle @Composable get() = colorResource(R.color.ui_glass_title)
    val glassBorder @Composable get() = colorResource(R.color.ui_glass_border)
    val mask @Composable get() = colorResource(R.color.ui_glass_mask)

    @Composable fun text(hex: String): Color = adapted(hex, ToneUse.Text)
    @Composable fun background(hex: String): Color = adapted(hex, ToneUse.Background)
    @Composable fun fill(hex: String): Color = adapted(hex, ToneUse.Fill)
    @Composable fun border(hex: String): Color = adapted(hex, ToneUse.Border)

    @Composable private fun adapted(hex: String, use: ToneUse): Color {
        val role = harmonyColorRole(hex, use)
        return if (role != null) colorResource(role) else {
            val raw = hex.removePrefix("#").toLongOrNull(16) ?: 0L
            Color(if (hex.length == 7) raw or 0xFF000000L else raw)
        }
    }
}

internal enum class ToneUse { Text, Background, Fill, Border }

/** Pure mapping: persisted scene colors remain unchanged, only presentation adapts. */
internal fun harmonyColorRole(hex: String, use: ToneUse): Int? {
    if (!Regex("^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$").matches(hex)) return null
    val raw = hex.drop(1).toLong(16)
    val alpha = if (hex.length == 9) ((raw shr 24) and 255) / 255.0 else 1.0
    if (alpha == 0.0) return null
    val r = ((raw shr 16) and 255) / 255.0
    val g = ((raw shr 8) and 255) / 255.0
    val b = (raw and 255) / 255.0
    val min = minOf(r, g, b)
    val max = maxOf(r, g, b)
    val delta = max - min
    val saturation = if (max == 0.0) 0.0 else delta / max
    val hue = if (delta == 0.0) 0.0 else (360 + 60 * when (max) {
        r -> (g - b) / delta
        g -> (b - r) / delta + 2
        else -> (r - g) / delta + 4
    }) % 360
    val neutral = saturation < .2 || (max < .6 && saturation < .45)
    val family = when {
        hue < 20 || hue >= 350 -> 0
        hue < 50 -> 1
        hue < 80 -> 2
        hue < 165 -> 3
        hue < 205 -> 4
        hue < 255 -> 5
        hue < 290 -> 6
        else -> 7
    }
    return when (use) {
        ToneUse.Text -> when {
            min >= .85 -> R.color.ui_text_on_fill
            alpha < .7 -> R.color.ui_text_muted
            max < .25 -> R.color.ui_text_primary
            neutral -> if (max < .55) R.color.ui_text_secondary else R.color.ui_text_muted
            else -> accentRoles[family]
        }
        ToneUse.Background -> when {
            alpha < 1 && min > .85 -> when {
                alpha <= .2 -> R.color.ui_glass_title
                alpha <= .5 -> R.color.ui_glass_mask
                else -> R.color.ui_glass_surface
            }
            alpha < 1 && max < .12 -> R.color.ui_scrim
            min > .985 -> R.color.ui_surface
            neutral -> when {
                max > .9 -> R.color.ui_surface_soft
                max > .65 -> R.color.ui_surface_muted
                else -> R.color.ui_surface_emphasized
            }
            max < .3 -> R.color.ui_surface_emphasized
            min > .62 || alpha < .55 -> softRoles[family]
            else -> fillRoles[family]
        }
        ToneUse.Fill -> when {
            neutral || max < .3 -> if (max > .6) R.color.ui_fill_neutral else R.color.ui_surface_emphasized
            else -> fillRoles[family]
        }
        ToneUse.Border -> when {
            min > .9 && alpha < 1 -> R.color.ui_glass_border
            neutral || min > .7 || max < .35 -> R.color.ui_border
            else -> accentRoles[family]
        }
    }
}

private val accentRoles = intArrayOf(R.color.ui_accent_red, R.color.ui_accent_orange, R.color.ui_accent_gold,
    R.color.ui_accent_green, R.color.ui_accent_cyan, R.color.ui_accent_blue, R.color.ui_accent_purple, R.color.ui_accent_pink)
private val softRoles = intArrayOf(R.color.ui_soft_red, R.color.ui_soft_orange, R.color.ui_soft_gold,
    R.color.ui_soft_green, R.color.ui_soft_cyan, R.color.ui_soft_blue, R.color.ui_soft_purple, R.color.ui_soft_pink)
private val fillRoles = intArrayOf(R.color.ui_fill_red, R.color.ui_fill_orange, R.color.ui_fill_gold,
    R.color.ui_fill_green, R.color.ui_fill_cyan, R.color.ui_fill_blue, R.color.ui_fill_purple, R.color.ui_fill_pink)

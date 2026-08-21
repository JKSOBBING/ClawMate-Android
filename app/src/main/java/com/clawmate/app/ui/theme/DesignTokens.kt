package com.clawmate.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Shared visual contract for Android pages aligned with the HarmonyOS app. */
object ClawMateDesign {
    object Colors {
        val PageBackground = Color(0xFFF4F8FF)
        val Surface = Color(0xFFFFFFFF)
        val SurfaceSubtle = Color(0xFFF8FAFE)
        val Input = Color(0xFFF2F5FC)
        val InputDisabled = Color(0xFFE6EAF2)
        val Border = Color(0xFFE3E8F1)
        val Primary = Color(0xFF765BFF)
        val PrimaryStrong = Color(0xFF8066FF)
        val PrimaryContainer = Color(0xFFF0ECFF)
        val Text = Color(0xFF11182F)
        val TextSecondary = Color(0xFF717684)
        val TextMuted = Color(0xFF8A93A3)
        val Success = Color(0xFF148F73)
        val Warning = Color(0xFFFF9800)
        val Danger = Color(0xFFD9467D)
        val Disabled = Color(0xFFA9B0BE)
    }

    object Shape {
        val Card = 16.dp
        val Control = 12.dp
        val Small = 8.dp
        val Pill = 24.dp
    }

    object Size {
        val InputHeight = 48.dp
        val ButtonHeight = 44.dp
        val IconButton = 48.dp
        val PageHorizontalPadding = 16.dp
    }

    object Space {
        val Xs = 4.dp
        val Sm = 8.dp
        val Md = 12.dp
        val Lg = 16.dp
        val Xl = 24.dp
    }
}

package com.clawmate.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Reference palette derived from the HarmonyOS app's light theme.
private val LightColors = lightColorScheme(
    primary = BlueViolet700,
    onPrimary = White,
    secondary = BlueViolet200,
    onSecondary = Ink,
    background = NearWhite,
    onBackground = Ink,
    surface = White,
    onSurface = Ink,
    surfaceVariant = ClawMateDesign.Colors.SurfaceSubtle,
    onSurfaceVariant = ClawMateDesign.Colors.TextSecondary,
)

private val DarkColors = darkColorScheme(
    primary = BlueViolet300,
    onPrimary = Ink,
    secondary = BlueViolet500,
    onSecondary = White,
    background = DeepInk,
    onBackground = NearWhite,
    surface = DeepInk,
    onSurface = NearWhite,
)

/** Accent tokens sourced from the HarmonyOS resource colors. */
object ClawMateColors {
    val Light = LightColors
    val Dark = DarkColors
}

@Composable
fun ClawMateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = ClawMateTypography,
        content = content,
    )
}

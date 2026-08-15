package com.clawmate.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Reference palette derived from the HarmonyOS app's light theme:
// blue-violet accent (#5B6BF0 family) with a near-white surface.
private val LightColors = lightColorScheme(
    primary = BlueViolet700,
    onPrimary = White,
    secondary = BlueViolet200,
    onSecondary = Ink,
    background = NearWhite,
    onBackground = Ink,
    surface = White,
    onSurface = Ink,
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

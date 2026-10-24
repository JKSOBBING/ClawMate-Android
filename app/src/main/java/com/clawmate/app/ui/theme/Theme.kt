package com.clawmate.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.TextUnit
import com.clawmate.app.R

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
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        var host = view.context
        while (host is android.content.ContextWrapper && host !is android.app.Activity) host = host.baseContext
        (host as? android.app.Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = (if (darkTheme) DarkColors else LightColors).copy(
            primary = colorResource(R.color.ui_fill_blue),
            background = colorResource(R.color.ui_page_background),
            surface = colorResource(R.color.ui_surface),
            onSurface = colorResource(R.color.ui_text_primary),
            onBackground = colorResource(R.color.ui_text_primary),
            surfaceVariant = colorResource(R.color.ui_surface_soft),
            onSurfaceVariant = colorResource(R.color.ui_text_secondary),
        ),
        typography = ClawMateTypography,
    ) {
        // ArkUI sizes text from its requested font. Material's inherited 24sp
        // line height otherwise makes even 10sp badges consume a full body row.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground,
            LocalTextStyle provides MaterialTheme.typography.bodyLarge.copy(
            lineHeight = TextUnit.Unspecified,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ), content = content)
    }
}

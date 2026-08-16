package com.clawmate.app.settings.ui

import androidx.compose.ui.graphics.Color

/**
 * Settings-center palette, mirrored from HarmonyOS
 * `entry/src/main/ets/pages/settings/SettingsCenterTheme.ets`.
 *
 * Colors are literal (not dynamic) so the section list and detail cards match
 * the source's rested blue-tinted background.
 */
object SettingsThemeColors {
    val PageBackground = Color(0xFFF4F8FF)
    val CardBackground = Color(0xFFFFFFFF)
    val InputBackground = Color(0xFFF2F5FC)
    val InputDisabledBackground = Color(0xFFE6EAF2)
    val PanelBackground = Color(0xFFEEF4FF)
    val TitleColor = Color(0xFF11182F)
    val SecondaryText = Color(0xFF7B8290)
    val MutedText = Color(0xFF6F7684)
    val BorderColor = Color(0xFFE6EAF2)
    val PanelBorderColor = Color(0xFFD9E5FF)

    val Primary = Color(0xFF4F68FF)
    val Secondary = Color(0xFF607D8B)
    val Success = Color(0xFF38BE8B)
    val Warning = Color(0xFFFF9800)
    val Danger = Color(0xFFF44336)
    val Special = Color(0xFF765BFF)

    val Connected = Color(0xFF2D9B73)
    val Disconnected = Color(0xFFD26B31)
    val Disabled = Color(0xFFA9B0BE)
}

/** Visual category of an action button (mirrors `SettingsActionKind`). */
enum class SettingsActionKind {
    Primary, Secondary, Success, Warning, Danger, Special;
}

fun settingsActionColor(kind: SettingsActionKind): Color = when (kind) {
    SettingsActionKind.Primary -> SettingsThemeColors.Primary
    SettingsActionKind.Secondary -> SettingsThemeColors.Secondary
    SettingsActionKind.Success -> SettingsThemeColors.Success
    SettingsActionKind.Warning -> SettingsThemeColors.Warning
    SettingsActionKind.Danger -> SettingsThemeColors.Danger
    SettingsActionKind.Special -> SettingsThemeColors.Special
}

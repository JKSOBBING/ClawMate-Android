package com.clawmate.app.settings.ui

import androidx.compose.ui.graphics.Color
import com.clawmate.app.ui.theme.ClawMateDesign

/**
 * Settings-center palette, mirrored from HarmonyOS
 * `entry/src/main/ets/pages/settings/SettingsCenterTheme.ets`.
 *
 * Colors are literal (not dynamic) so the section list and detail cards match
 * the source's rested blue-tinted background.
 */
object SettingsThemeColors {
    val PageBackground = ClawMateDesign.Colors.PageBackground
    val CardBackground = ClawMateDesign.Colors.Surface
    val InputBackground = ClawMateDesign.Colors.Input
    val InputDisabledBackground = ClawMateDesign.Colors.InputDisabled
    val PanelBackground = Color(0xFFEEF4FF)
    val TitleColor = ClawMateDesign.Colors.Text
    val SecondaryText = Color(0xFF7B8290)
    val MutedText = Color(0xFF6F7684)
    val BorderColor = ClawMateDesign.Colors.Border
    val PanelBorderColor = Color(0xFFD9E5FF)

    val Primary = ClawMateDesign.Colors.Primary
    val Secondary = Color(0xFF607D8B)
    val Success = ClawMateDesign.Colors.Success
    val Warning = ClawMateDesign.Colors.Warning
    val Danger = ClawMateDesign.Colors.Danger
    val Special = ClawMateDesign.Colors.Accent

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

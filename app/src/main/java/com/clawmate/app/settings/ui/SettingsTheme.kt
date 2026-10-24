package com.clawmate.app.settings.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import com.clawmate.app.ui.theme.HarmonyTheme
import com.clawmate.app.ui.theme.ClawMateDesign

/**
 * Settings-center palette, mirrored from HarmonyOS
 * `entry/src/main/ets/pages/settings/SettingsCenterTheme.ets`.
 *
 * Raw source tones are adapted by AppTheme for both system appearances.
 */
object SettingsThemeColors {
    val PageBackground @Composable get() = HarmonyTheme.page
    val CardBackground @Composable get() = HarmonyTheme.surface
    val InputBackground @Composable get() = HarmonyTheme.background("#F2F5FC")
    val InputDisabledBackground @Composable get() = HarmonyTheme.background("#E6EAF2")
    val PanelBackground @Composable get() = HarmonyTheme.background("#EEF4FF")
    val TitleColor @Composable get() = HarmonyTheme.text("#11182F")
    val SecondaryText @Composable get() = HarmonyTheme.text("#7B8290")
    val MutedText @Composable get() = HarmonyTheme.text("#6F7684")
    val BorderColor @Composable get() = HarmonyTheme.border("#E6EAF2")
    val PanelBorderColor @Composable get() = HarmonyTheme.border("#D9E5FF")
    val Primary @Composable get() = HarmonyTheme.fill("#4F68FF")
    val Secondary @Composable get() = HarmonyTheme.text("#607D8B")
    val Success @Composable get() = HarmonyTheme.text("#38BE8B")
    val Warning @Composable get() = HarmonyTheme.text("#FF9800")
    val Danger @Composable get() = HarmonyTheme.text("#F44336")
    val Special @Composable get() = HarmonyTheme.text("#765BFF")
    val Connected @Composable get() = HarmonyTheme.text("#2D9B73")
    val Disconnected @Composable get() = HarmonyTheme.text("#D26B31")
    val Disabled @Composable get() = HarmonyTheme.text("#A9B0BE")
}

/** Visual category of an action button (mirrors `SettingsActionKind`). */
enum class SettingsActionKind {
    Primary, Secondary, Success, Warning, Danger, Special;
}

@Composable
fun settingsActionColor(kind: SettingsActionKind): Color = HarmonyTheme.fill(when (kind) {
    SettingsActionKind.Primary -> "#4F68FF"
    SettingsActionKind.Secondary -> "#607D8B"
    SettingsActionKind.Success -> "#38BE8B"
    SettingsActionKind.Warning -> "#FF9800"
    SettingsActionKind.Danger -> "#F44336"
    SettingsActionKind.Special -> "#765BFF"
})

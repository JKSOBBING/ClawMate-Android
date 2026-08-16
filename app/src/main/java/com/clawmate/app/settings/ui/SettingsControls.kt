package com.clawmate.app.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CardRadius = 18.dp
private val ControlRadius = 12.dp
private val CardPadding = 16.dp
private val FieldHeight = 42.dp

/**
 * Grouped settings card — title + optional subtitle + injected content.
 * Mirrors HarmonyOS `SettingsGroupCard` in SettingsCenterControls.ets.
 */
@Composable
fun SettingsGroupCard(
    title: String,
    subtitle: String = "",
    onHeaderClick: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .background(SettingsThemeColors.CardBackground, RoundedCornerShape(CardRadius))
            .border(1.dp, Color(0xFFF0F2F7), RoundedCornerShape(CardRadius))
            .padding(CardPadding),
    ) {
        Text(
            title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = SettingsThemeColors.TitleColor,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onHeaderClick() },
        )
        if (subtitle.isNotEmpty()) {
            Text(
                subtitle,
                fontSize = 12.sp,
                color = SettingsThemeColors.SecondaryText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clickable { onHeaderClick() },
            )
        }
        Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            content()
        }
    }
}

@Composable
fun SettingsSectionTitle(title: String, subtitle: String, onClick: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .clickable { onClick() },
    ) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = SettingsThemeColors.TitleColor)
        Text(
            subtitle,
            fontSize = 12.sp,
            color = SettingsThemeColors.SecondaryText,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Label above a field (mirrors `FieldLabel`). */
@Composable
fun SettingsFieldLabel(label: String) {
    Text(
        label,
        fontSize = 13.sp,
        color = SettingsThemeColors.MutedText,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 6.dp),
    )
}

/** Plain single/multi-line input used by most settings fields. */
@Composable
fun SettingInput(
    label: String,
    value: String,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    Row {
        if (label.isNotEmpty()) {
            Column(modifier = Modifier.padding(end = 8.dp).width(124.dp)) {
                Text(
                    label,
                    fontSize = 13.sp,
                    color = SettingsThemeColors.MutedText,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text(placeholder, color = SettingsThemeColors.Disabled, fontSize = 13.sp) },
                enabled = enabled,
                singleLine = singleLine,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = if (enabled) SettingsThemeColors.TitleColor else SettingsThemeColors.SecondaryText,
                    fontSize = 13.sp,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (enabled) SettingsThemeColors.InputBackground else SettingsThemeColors.InputDisabledBackground,
                        RoundedCornerShape(ControlRadius),
                    ),
            )
        }
    }
    Spacer(Modifier.height(6.dp))
}

/**
 * Toggle row with label + description.
 * Mirrors HarmonyOS `SettingToggle` in SettingsCenterControls.ets.
 */
@Composable
fun SettingToggle(
    label: String,
    desc: String,
    selected: Boolean,
    enabled: Boolean = true,
    onValueChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp)
            .clickable(enabled = enabled) { onValueChange(!selected) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = selected,
            onCheckedChange = if (enabled) { v -> onValueChange(v) } else { null },
            enabled = enabled,
        )
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Normal, color = SettingsThemeColors.TitleColor)
            if (desc.isNotEmpty()) {
                Text(
                    desc,
                    fontSize = 12.sp,
                    color = SettingsThemeColors.SecondaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * Sensitive input with hidden/revealed preview. Mirrors HarmonyOS
 * `SensitiveSettingInput` behavior: masked preview by default; reveal to edit;
 * the mask marker is never written back.
 */
@Composable
fun SensitiveSettingInput(
    key: String,
    revealedKeys: Set<String>,
    label: String,
    value: String,
    placeholder: String,
    onReveal: () -> Unit,
    onHide: () -> Unit,
    onValueChange: (String) -> Unit,
    currentValue: String,
) {
    val revealed = revealedKeys.contains(key)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, color = SettingsThemeColors.MutedText, modifier = Modifier.padding(bottom = 6.dp))
            if (revealed) {
                OutlinedTextField(
                    value = currentValue,
                    onValueChange = { v -> if (!SensitiveValueUtilsMarker.isMasked(v)) onValueChange(v) },
                    placeholder = { Text(placeholder, color = SettingsThemeColors.Disabled, fontSize = 13.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = SettingsThemeColors.TitleColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SettingsThemeColors.InputBackground, RoundedCornerShape(ControlRadius)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(FieldHeight)
                        .background(SettingsThemeColors.InputDisabledBackground, RoundedCornerShape(ControlRadius))
                        .padding(horizontal = 12.dp)
                        .clickable { onReveal() },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        maskForDisplay(value, placeholder),
                        fontSize = 13.sp,
                        color = SettingsThemeColors.SecondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        TextButton(
            onClick = { if (revealed) onHide() else onReveal() },
            modifier = Modifier.padding(start = 8.dp).width(72.dp),
        ) {
            Text(if (revealed) "隐藏" else "显示", fontSize = 12.sp)
        }
    }
}

private object SensitiveValueUtilsMarker {
    const val MARK: Char = '•'

    fun isMasked(value: String): Boolean {
        val t = value.trim()
        return t.isNotEmpty() && t.indexOf(MARK) >= 0
    }
}

private fun maskForDisplay(value: String, placeholder: String): String {
    if (value.isBlank()) return if (placeholder.isBlank()) "未配置" else placeholder
    if (SensitiveValueUtilsMarker.isMasked(value)) return value
    return com.clawmate.app.settings.domain.SensitiveValueUtils.maskSensitiveValue(value)
}

/** Horizontal row of action buttons (mirrors `SettingsActionRow` / `SettingsActionButton`). */
@Composable
fun SettingsActionRow(
    actions: List<SettingsAction>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        actions.forEach { action ->
            TextButton(
                onClick = action.onClick,
                enabled = action.enabled,
                modifier = Modifier.weight(1f).height(40.dp),
            ) {
                Text(
                    action.label,
                    fontSize = 12.sp,
                    color = if (action.enabled) settingsActionColor(action.kind) else SettingsThemeColors.Disabled,
                )
            }
        }
    }
}

data class SettingsAction(
    val label: String,
    val kind: SettingsActionKind = SettingsActionKind.Primary,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** Read-only output panel (mirrors `SettingsOutputPanel`). */
@Composable
fun SettingsOutputPanel(title: String, content: String, placeholder: String = "暂无输出") {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(title, fontSize = 13.sp, color = SettingsThemeColors.MutedText)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
                .background(SettingsThemeColors.PanelBackground, RoundedCornerShape(ControlRadius))
                .border(1.dp, SettingsThemeColors.PanelBorderColor, RoundedCornerShape(ControlRadius))
                .padding(12.dp),
        ) {
            Text(
                content.ifBlank { placeholder },
                fontSize = 12.sp,
                color = androidx.compose.ui.graphics.Color(0xFF333333),
            )
        }
    }
}

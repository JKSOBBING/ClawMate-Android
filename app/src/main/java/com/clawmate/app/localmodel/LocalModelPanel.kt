package com.clawmate.app.localmodel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clawmate.app.settings.ui.SettingsAction
import com.clawmate.app.settings.ui.SettingsActionKind
import com.clawmate.app.settings.ui.SettingsActionRow
import com.clawmate.app.settings.ui.SettingsGroupCard
import com.clawmate.app.settings.ui.SettingsThemeColors

/**
 * The "本地模型" panel rendered inside the 模型与智能体 Settings section.
 *
 * Shows the honest capability state ([LocalModelState]), the discovered model
 * inventory, and an explicit "加载/测试" action per loadable model. Load/test
 * runs through [LocalModelCapability]; READY is reported only on a real native
 * load success. When MNN/LLM for Android is missing the panel reports
 * UNSUPPORTED and never fabricates a loaded result.
 */
@Composable
fun LocalModelPanel() {
    val context = LocalContext.current
    val filesDir = remember(context) { LocalModelViewModel.filesDir(context) }
    val vm: LocalModelViewModel = viewModel(
        factory = LocalModelViewModel.Factory(filesDir),
    )
    val ui by vm.ui.collectAsState()

    // Scan once on first composition (app launch performs no network/load).
    LaunchedEffect(Unit) { vm.refresh() }

    val s = ui.state

    SettingsGroupCard(
        title = "本地模型",
        subtitle = "端侧 MNN/HiAI 模型边界（仅真实加载成功后显示已就绪）",
    ) {
        val statusColor = when (s.status) {
            LocalModelStatus.READY -> SettingsThemeColors.Success
            LocalModelStatus.ERROR, LocalModelStatus.UNSUPPORTED -> SettingsThemeColors.Danger
            LocalModelStatus.LOADING -> SettingsThemeColors.Warning
            LocalModelStatus.NOT_CONFIGURED -> SettingsThemeColors.Secondary
        }
        Text(
            "状态：${s.status.displayLabel()}",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = statusColor,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (s.message.isNotBlank()) {
            Text(
                s.message,
                fontSize = 12.sp,
                color = SettingsThemeColors.SecondaryText,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        s.errorKind?.let { kind ->
            Text(
                "原因：${kind.displayLabel()}",
                fontSize = 12.sp,
                color = SettingsThemeColors.Danger,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        if (ui.refreshing) {
            Text("正在扫描…", fontSize = 12.sp, color = SettingsThemeColors.Warning)
        }

        if (s.models.isEmpty()) {
            Text(
                "未在应用私有目录发现本地模型。请将模型放入 dataDir/model 或 dataDir/modelscope（含 config.json 与权重文件）。",
                fontSize = 12.sp,
                color = SettingsThemeColors.SecondaryText,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            s.models.forEach { model ->
                ModelRow(
                    label = model.label,
                    size = formatBytes(model.sizeBytes),
                    statusText = model.statusText,
                )
            }
        }

        // Explicit load/test actions - one per ready model, enabled when not busy.
        SettingsActionRow(
            actions = s.models.filter { it.ready }.map { model ->
                SettingsAction(
                    label = if (ui.loadTestBusy && s.selectedModelId == model.id) "正在加载 ${model.label}…" else "加载/测试 ${model.label}",
                    kind = SettingsActionKind.Primary,
                    enabled = !ui.loadTestBusy,
                    onClick = { vm.loadTest(model.id) },
                )
            },
        )

        // Honest footer about the native runtime.
        Text(
            "说明：Android 端当前未内置 MNN/LLM 推理库，因此本地模型加载会如实报告“不支持/未配置”；连上真实推理库后才会进入“已就绪”。",
            fontSize = 11.sp,
            color = SettingsThemeColors.SecondaryText,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun ModelRow(label: String, size: String, statusText: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = SettingsThemeColors.TitleColor)
            Text(statusText, fontSize = 11.sp, color = SettingsThemeColors.SecondaryText)
        }
        Text(size, fontSize = 12.sp, color = SettingsThemeColors.SecondaryText)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

package com.clawmate.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Settings tab placeholder (Phase 1).
 * HarmonyOS settings are organized into five sections:
 *   个人与隐私 | 数据采集 | 模型与智能体 | 任务与自动化 | 存储与关于
 *
 * HarmonyOS source reference:
 *   entry/src/main/ets/pages/settings/SettingsCenterPage.ets
 *   entry/src/main/ets/pages/settings/SettingsCenterTypes.ets
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Settings — phase 2 (sections: persona/privacy, data collection, model & agents, task automation, storage & about)",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

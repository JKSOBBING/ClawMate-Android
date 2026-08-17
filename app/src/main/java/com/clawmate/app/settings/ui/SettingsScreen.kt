package com.clawmate.app.settings.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clawmate.app.settings.SettingsComponent
import com.clawmate.app.settings.domain.SETTINGS_SECTIONS
import com.clawmate.app.settings.domain.SettingsSectionId
import com.clawmate.app.log.ui.LogScreen

/**
 * Compose Settings screen — the Android vertical slice of the HarmonyOS 设置 tab
 * (`entry/src/main/ets/pages/settings/SettingsCenterPage.ets`).
 *
 * Shows the five-section list (个人与隐私, 数据采集, 模型与智能体, 任务与自动化,
 * 存储与关于). Tapping a section opens its detail with grouped cards, text
 * fields, switches, sensitive reveal/edit, save/reset actions and loading /
 * error / saved states. Missing / unconfigured endpoints are surfaced as
 * NOT_CONFIGURED / DISABLED — never claimed connected.
 */
@Composable
fun SettingsScreen(context: Context, modifier: Modifier = Modifier) {
    val version = rememberAppVersion(context)
    val vm: SettingsViewModel = viewModel(
        factory = SettingsViewModelFactory.forContext(
            repository = SettingsComponent.repository(context),
            versionName = version.first,
            versionCode = version.second,
            context = context,
        ),
    )
    val state by vm.state.collectAsState()

    Box(modifier = modifier.fillMaxSize().padding(top = 0.dp)) {
        if (state.loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            return@Box
        }

        if (SettingsSectionId.isSelected(state.selectedSectionId)) {
            val active = SettingsSectionId.entries.firstOrNull { it.ordinalIndex == state.selectedSectionId }
            if (active != null) {
                if (active == SettingsSectionId.RuntimeLogs) {
                    LogScreen(onBack = vm::closeSection)
                    return@Box
                }
                SectionDetail(
                    section = SETTINGS_SECTIONS.first { it.id == active },
                    state = state,
                    vm = vm,
                    context = context,
                )
            }
        } else {
            SectionList(state = state, vm = vm)
        }
    }
}

@Composable
private fun SectionList(state: SettingsUiState, vm: SettingsViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SettingsThemeColors.PageBackground)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 90.dp),
    ) {
        Text(
            "设置中心",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = SettingsThemeColors.TitleColor,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 10.dp),
        )
        SETTINGS_SECTIONS.forEach { item ->
            SectionListItem(
                item = item,
                onClick = { vm.selectSection(item.id) },
            )
        }
    }
}

@Composable
private fun SectionListItem(
    item: com.clawmate.app.settings.domain.SettingsSectionItem,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(Color.White, RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(SettingsThemeColors.Primary, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                item.badge,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp, end = 10.dp)) {
            Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = SettingsThemeColors.TitleColor)
            Text(
                item.subtitle,
                fontSize = 12.sp,
                color = SettingsThemeColors.SecondaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text("›", fontSize = 28.sp, color = Color(0xFF8A93A3))
    }
}

@Composable
private fun SectionDetail(
    section: com.clawmate.app.settings.domain.SettingsSectionItem,
    state: SettingsUiState,
    vm: SettingsViewModel,
    context: Context,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SettingsThemeColors.PageBackground)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 110.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.closeSection() }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回设置中心")
            }
            Column(modifier = Modifier.padding(start = 4.dp)) {
                Text("设置中心", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SettingsThemeColors.TitleColor)
                Text(
                    settingsSectionSubtitleDisplay(section.id),
                    fontSize = 12.sp,
                    color = SettingsThemeColors.SecondaryText,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        // Error banner
        state.error?.let { err ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(SettingsThemeColors.Danger.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                Text(err, fontSize = 13.sp, color = SettingsThemeColors.Danger)
            }
        }

        // Saved confirmation
        if (state.saved) {
            LaunchedEffect(state.saved) {
                kotlinx.coroutines.delay(2000)
                vm.dismissSaved()
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = SettingsThemeColors.Success)
                Text(
                    "设置已保存",
                    fontSize = 13.sp,
                    color = SettingsThemeColors.Success,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }

        when (section.id) {
            SettingsSectionId.PersonalPrivacy -> PrivacySection(state, vm)
            SettingsSectionId.DataCollection -> DataCollectionSection(state, vm)
            SettingsSectionId.ModelAgents -> ModelAgentsSection(state, vm, context)
            SettingsSectionId.TaskAutomation -> TaskAutomationSection(state, vm, context)
            SettingsSectionId.StorageAbout -> StorageAboutSection(state, vm, context)
            SettingsSectionId.WechatAutoSummary -> WechatSettingsSection(state, vm)
            // Unreachable here: RuntimeLogs is handled earlier in SettingsScreen
            // as a full-page Log viewer (no settings form).
            SettingsSectionId.RuntimeLogs -> Unit
        }

        // Save / reset actions
        SettingsSaveResetBar(
            saveBusy = state.saveBusy,
            dirty = state.dirty,
            onSave = { vm.save() },
            onReset = { vm.resetToDefaults() },
        )
    }
}

@Composable
private fun SettingsSaveResetBar(saveBusy: Boolean, dirty: Boolean, onSave: () -> Unit, onReset: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = onSave,
            enabled = dirty && !saveBusy,
            colors = ButtonDefaults.buttonColors(containerColor = SettingsThemeColors.Primary),
            modifier = Modifier.weight(1f).height(44.dp),
        ) {
            if (saveBusy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White)
            } else {
                Text("保存设置", fontSize = 14.sp)
            }
        }
        TextButton(
            onClick = onReset,
            modifier = Modifier.height(44.dp),
        ) {
            Text("恢复默认", fontSize = 14.sp, color = SettingsThemeColors.Secondary)
        }
    }
}

private fun settingsSectionSubtitleDisplay(id: SettingsSectionId): String =
    com.clawmate.app.settings.domain.settingsSectionSubtitle(id)

/** Read app version name/code deterministically, falling back to safe values. */
@Composable
private fun rememberAppVersion(context: Context): Pair<String, Long> {
    return androidx.compose.runtime.remember(context) {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
            (info.versionName ?: "1.0.0") to code
        }.getOrElse { "1.0.0" to 1L }
    }
}

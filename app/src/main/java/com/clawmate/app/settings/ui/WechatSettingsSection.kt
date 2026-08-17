package com.clawmate.app.settings.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clawmate.app.wechat.domain.WechatAutoSummaryModes
import com.clawmate.app.wechat.domain.WechatCollectionGate
import com.clawmate.app.wechat.domain.WechatCollectionStatus
import com.clawmate.app.wechat.domain.validateWechatAutoSummaryRequest

/**
 * Phase 13 — WeChat auto-summary settings section.
 *
 * Exposes the portable WeChat summary parameters (days / max contacts / swipe
 * speed / ratio / stable swipes / max history swipes / wait / mode / target
 * contact) through the existing [SettingInput] / [SettingToggle] controls,
 * backed by DataStore via the shared [SettingsViewModel]. The collection
 * boundary ([WechatCollectionGate]) is shown as a typed, honest status row: it
 * stays BLOCKED / NOT_CONFIGURED unless a health-verified PC HDC bridge is
 * registered, and becomes AVAILABLE only when a verified adapter is present and
 * auto-summary is enabled. The explicit [WechatCollectionGate.collect] invokes
 * only the safe `wechat_collect` adapter action; it never drives WeChat, sends
 * arbitrary commands, or fakes a summary. Numbers are normalized on change through the ported pure normalizers.
 */
@Composable
fun WechatSettingsSection(state: SettingsUiState, vm: SettingsViewModel) {
    SettingsSectionTitle(
        title = "微信自动汇总",
        subtitle = "微信摘要参数、验证与 PC/HDC 采集边界",
        onClick = {},
    )

    SettingsGroupCard(
        title = "自动汇总开关",
        subtitle = "开启后按以下参数生成微信消息摘要（采集始终受下方边界约束）",
    ) {
        SettingToggle(
            label = "启用微信自动汇总",
            desc = "关闭采集将保持未启用状态",
            selected = state.draft.wechatAutoSummaryEnabled,
            onValueChange = vm::setWechatAutoSummaryEnabled,
        )
    }

    SettingsGroupCard(
        title = "汇总范围",
        subtitle = "汇总窗口与联系人数量（归一化后落在有界区间）",
    ) {
        SettingInput("汇总天数", state.draft.wechatAutoSummaryDays.toString(),
            placeholder = "默认 7，范围 1-90", singleLine = true) { vm.setWechatAutoSummaryDays(it) }
        SettingInput("最大联系人", state.draft.wechatAutoSummaryMaxContacts.toString(),
            placeholder = "默认 10，范围 1-50", singleLine = true) { vm.setWechatAutoSummaryMaxContacts(it) }
        SettingInput("目标联系人(指定模式)", state.draft.wechatCollectionTargetContact,
            placeholder = "指定联系人模式下的联系人名称", singleLine = true) { vm.setWechatCollectionTargetContact(it) }
    }

    SettingsGroupCard(
        title = "采集参数（有界）",
        subtitle = "复现 PC 侧采集器参数，数值在保存前归一化",
    ) {
        SettingInput("滑动速度", state.draft.wechatAutoSummarySwipeSpeed.toString(),
            placeholder = "默认 2000，范围 0-20000", singleLine = true) { vm.setWechatAutoSummarySwipeSpeed(it) }
        SettingInput("历史滑动比例", state.draft.wechatAutoSummaryHistorySwipeRatio.toString(),
            placeholder = "默认 0.4，范围 0.1-0.95", singleLine = true) { vm.setWechatAutoSummaryHistorySwipeRatio(it) }
        SettingInput("稳定判定次数", state.draft.wechatAutoSummaryStableSwipes.toString(),
            placeholder = "默认 3，范围 1-10", singleLine = true) { vm.setWechatAutoSummaryStableSwipes(it) }
        SettingInput("历史最大滑动", state.draft.wechatAutoSummaryMaxHistorySwipes.toString(),
            placeholder = "默认 80，范围 1-300", singleLine = true) { vm.setWechatAutoSummaryMaxHistorySwipes(it) }
        SettingInput("页面等待时间", state.draft.wechatAutoSummaryWait.toString(),
            placeholder = "默认 1.0，范围 0-10", singleLine = true) { vm.setWechatAutoSummaryWait(it) }
    }

    SettingsGroupCard(
        title = "采集模式",
        subtitle = "recent_contacts 与 target_contact 二选一",
    ) {
        SettingToggle(
            label = "指定联系人模式",
            desc = "开启使用 target_contact，否则为 recent_contacts",
            selected = state.draft.wechatCollectionMode == WechatAutoSummaryModes.TARGET,
            onValueChange = { vm.setWechatCollectionMode(if (it) WechatAutoSummaryModes.TARGET else WechatAutoSummaryModes.RECENT) },
        )
    }

    WechatCollectionBoundaryCard(state, vm)
}

/** Typed, honest collection boundary rendered from the live gate. */
@Composable
private fun WechatCollectionBoundaryCard(
    state: SettingsUiState,
    vm: SettingsViewModel,
) {
    val request = vm.wechatSummaryRequestOrNull()
    val validation = request?.let { validateWechatAutoSummaryRequest(it) }.orEmpty()
    val gate: WechatCollectionGate = vm.wechatCollectionGate()
    val status = gate.status

    SettingsGroupCard(
        title = "PC/HDC 采集边界",
        subtitle = "采集为 PC 侧 Python/HDC 服务；此界面对齐已验证 bridge 状态，绝不自动化微信或伪造汇总",
    ) {
        val (statusLabel, statusKind, enabled) = when (status) {
            WechatCollectionStatus.BLOCKED -> Triple(
                "采集 BLOCKED — 需已验证的 PC/HDC bridge",
                SettingsActionKind.Danger,
                false,
            )
            WechatCollectionStatus.NOT_CONFIGURED -> Triple(
                "采集未配置（自动汇总关闭）",
                SettingsActionKind.Warning,
                false,
            )
            WechatCollectionStatus.AVAILABLE -> Triple(
                "采集可用 — 已配置并健康校验的 bridge，可发起显式采集",
                SettingsActionKind.Success,
                true,
            )
            WechatCollectionStatus.UNAVAILABLE -> Triple(
                "采集不可用",
                SettingsActionKind.Secondary,
                false,
            )
        }
        SettingsActionRow(
            actions = listOf(
                SettingsAction(statusLabel, statusKind, enabled = enabled, onClick = {}),
            ),
        )
        if (validation.isNotEmpty()) {
            Text(
                "请求校验：$validation",
                fontSize = 12.sp,
                color = SettingsThemeColors.Warning,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        Text(
            "Android 不驱动微信、不发送任意命令。仅当已配置并健康校验的 bridge 且自动汇总开启时，显式采集才会调用安全的 wechat_collect 动作；未通过校验/未启用/未配置均不发起任何采集，亦不伪造摘要。",
            fontSize = 11.sp,
            color = SettingsThemeColors.SecondaryText,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

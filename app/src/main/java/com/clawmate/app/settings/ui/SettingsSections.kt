package com.clawmate.app.settings.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clawmate.app.localmodel.LocalModelPanel
import com.clawmate.app.settings.domain.AutoConfirmMode
import com.clawmate.app.settings.domain.EmbeddingMode
import com.clawmate.app.settings.domain.SettingsKeys

/**
 * The five Settings sections, mirroring the HarmonyOS `SettingsCenterPage.ets`
 * section builders. Only a truthful subset is rendered: endpoints that are not
 * configured show DISABLED / NOT_CONFIGURED, and destructive cleanups are
 * gated by explicit, labelled buttons (never broad directory deletes).
 */

@Composable
fun PrivacySection(state: SettingsUiState, vm: SettingsViewModel) {
    SettingsSectionTitle(
        title = "个人与隐私",
        subtitle = "隐私守护、确认策略与数字分身",
        onClick = { vm.hide(SettingKeys.AutoConfirm) },
    )

    SettingsGroupCard(
        title = "Agent 确认策略",
        subtitle = "控制本地与云端 Agent 动作是否需要人工确认",
    ) {
        AutoConfirmToggleRow(
            label = "本地 Agent 自动同意",
            desc = "关闭后每次端侧模型动作都会弹出确认",
            selected = state.draft.agentLocalConfirm == AutoConfirmMode.AutoApprove,
            onChecked = { vm.setLocalAutoConfirm(if (it) AutoConfirmMode.AutoApprove else AutoConfirmMode.Ask) },
        )
        AutoConfirmToggleRow(
            label = "云端 Agent 自动同意",
            desc = "关闭后云端模型动作需要手动确认后发送",
            selected = state.draft.agentCloudConfirm == AutoConfirmMode.AutoApprove,
            onChecked = { vm.setCloudAutoConfirm(if (it) AutoConfirmMode.AutoApprove else AutoConfirmMode.Ask) },
        )
    }

    SettingsGroupCard(
        title = "数字分身图片服务",
        subtitle = "配置头像生成服务地址、密钥、模型与图片尺寸",
    ) {
        SettingInput("图片服务 URL", state.draft.avatarImageBaseUrl,
            placeholder = "输入图片服务 URL") { vm.setAvatarBaseUrl(it) }
        SensitiveSettingInput(
            key = SettingsKeys.AVATAR_IMAGE_API_KEY,
            revealedKeys = state.revealedSensitiveKeys,
            label = "图片 API Key",
            value = maskedOrText(state.draft.avatarImageApiKey),
            placeholder = "图片服务密钥",
            onReveal = { vm.reveal(SettingsKeys.AVATAR_IMAGE_API_KEY) },
            onHide = { vm.hide(SettingsKeys.AVATAR_IMAGE_API_KEY) },
            onValueChange = { vm.setAvatarApiKey(it) },
            currentValue = state.draft.avatarImageApiKey,
        )
        SettingInput("文生图模型", state.draft.avatarTextToImageModel,
            placeholder = "文生图模型") { vm.setAvatarModel(it) }
        SettingInput("图片尺寸", state.draft.avatarImageSize,
            placeholder = "图片尺寸，如 2K") { vm.setAvatarSize(it) }
    }
}

@Composable
fun DataCollectionSection(state: SettingsUiState, vm: SettingsViewModel) {
    SettingsSectionTitle(
        title = "数据采集",
        subtitle = "图库、OCR、Embedding 与地图服务",
        onClick = { vm.hide(SettingsKeys.GALLERY_API_KEY) },
    )

    SettingsGroupCard(
        title = "图库分析",
        subtitle = "图库大模型服务与字段级请求策略",
    ) {
        SettingInput("API Base URL", state.draft.galleryApiBaseUrl,
            placeholder = "输入图库 API Base URL") { vm.setGalleryBaseUrl(it) }
        SensitiveSettingInput(
            key = SettingsKeys.GALLERY_API_KEY,
            revealedKeys = state.revealedSensitiveKeys,
            label = "API Key",
            value = maskedOrText(state.draft.galleryApiKey),
            placeholder = "图库分析服务密钥",
            onReveal = { vm.reveal(SettingsKeys.GALLERY_API_KEY) },
            onHide = { vm.hide(SettingsKeys.GALLERY_API_KEY) },
            onValueChange = { vm.setGalleryApiKey(it) },
            currentValue = state.draft.galleryApiKey,
        )
        SettingInput("图库大模型", state.draft.galleryModel,
            placeholder = "图库大模型") { vm.setGalleryModel(it) }
        SettingToggle("字段级并发请求", "同一张图并发请求各分析字段",
            state.draft.galleryParallelFieldRequests, onValueChange = vm::setGalleryParallel)
        SettingInput("大模型并发请求数", state.draft.galleryModelConcurrency.toString(),
            placeholder = "默认 2", singleLine = true) { vm.setGalleryModelConcurrency(it) }
    }

    SettingsGroupCard(
        title = "OCR",
        subtitle = "本地 OCR 回退、关键词预筛和并发控制",
    ) {
        SettingToggle("OCR 关键词预筛", "自动分析时先用 OCR 关键词过滤图片",
            state.draft.galleryUseOcrKeywordFilter, onValueChange = vm::setGalleryOcrFilter)
        SettingInput("OCR 并发请求数", state.draft.galleryOcrConcurrency.toString(),
            placeholder = "默认 3", singleLine = true) { vm.setGalleryOcrConcurrency(it) }
    }

    SettingsGroupCard(
        title = "Embedding",
        subtitle = "图片检索向量服务和端云模式",
    ) {
        SettingInput("并发请求数", state.draft.galleryEmbeddingConcurrency.toString(),
            placeholder = "默认 2", singleLine = true) { vm.setGalleryEmbeddingConcurrency(it) }
        SettingToggle("使用云端 Embedding API", "关闭时使用端侧 Embedding",
            state.draft.galleryEmbeddingMode == EmbeddingMode.Remote,
            onValueChange = { vm.setGalleryEmbeddingMode(if (it) EmbeddingMode.Remote else EmbeddingMode.HuaweiLocal) })
        SettingInput("Embedding Base URL", state.draft.galleryEmbeddingBaseUrl,
            placeholder = "输入 Embedding Base URL") { vm.setGalleryEmbeddingBaseUrl(it) }
        SensitiveSettingInput(
            key = SettingsKeys.GALLERY_EMBEDDING_API_KEY,
            revealedKeys = state.revealedSensitiveKeys,
            label = "Embedding API Key",
            value = maskedOrText(state.draft.galleryEmbeddingApiKey),
            placeholder = "Embedding 服务密钥",
            onReveal = { vm.reveal(SettingsKeys.GALLERY_EMBEDDING_API_KEY) },
            onHide = { vm.hide(SettingsKeys.GALLERY_EMBEDDING_API_KEY) },
            onValueChange = { vm.setGalleryEmbeddingApiKey(it) },
            currentValue = state.draft.galleryEmbeddingApiKey,
        )
        SettingInput("Embedding 模型", state.draft.galleryEmbeddingModel,
            placeholder = "Embedding 模型") { vm.setGalleryEmbeddingModel(it) }
    }

    SettingsGroupCard(
        title = "月度 AI 总结",
        subtitle = "可选的云端月度总结（复用 Planner 端点；关闭则不发起任何云端请求）",
    ) {
        SettingToggle(
            "启用云端月度 AI 总结",
            "需同时配置 Planner 端点/模型/密钥",
            state.draft.galleryAiSummaryEnabled,
            onValueChange = vm::setGalleryAiSummaryEnabled,
        )
        Text(
            state.draft.galleryAiSummaryEnabled
                .let { if (it) "已启用：仅在有明确触发时联网生成月度总结；本地洞察不受影响。" else "未启用：不会向云端发送任何图库数据。" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SettingsGroupCard(
        title = "后台图库同步",
        subtitle = "显式开启的可选后台扫描/同步（Phase 23）：需勾选开启，且 Android 系统授予图库读取权限；不会静默扫描或上传",
    ) {
        SettingToggle(
            "启用后台图库扫描/同步",
            "显式开启受控扫描（默认关闭；开启还需授予图库读取权限，且只从显式动作触发）",
            state.draft.galleryBackgroundSyncEnabled,
            onValueChange = vm::setGalleryBackgroundSyncEnabled,
        )

        val context = androidx.compose.ui.platform.LocalContext.current
        val permissionGranted = runCatching {
            val permission = if (android.os.Build.VERSION.SDK_INT >= 33)
                android.Manifest.permission.READ_MEDIA_IMAGES
            else
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val gate = com.clawmate.app.gallery.background.GallerySyncGate(
            optedIn = state.draft.galleryBackgroundSyncEnabled,
            photoAccessGranted = permissionGranted,
        )
        val status = when (gate.status) {
            com.clawmate.app.gallery.background.GallerySyncGateStatus.NOT_CONFIGURED ->
                SettingsAction("未启用 — NOT_CONFIGURED", SettingsActionKind.Secondary, enabled = false, onClick = {})
            com.clawmate.app.gallery.background.GallerySyncGateStatus.BLOCKED ->
                SettingsAction("需授予图库读取权限 — BLOCKED", SettingsActionKind.Warning, enabled = false, onClick = {})
            com.clawmate.app.gallery.background.GallerySyncGateStatus.READY ->
                SettingsAction("已就绪 — READY（仅显式触发）", SettingsActionKind.Success, enabled = false, onClick = {})
        }
        SettingsActionRow(actions = listOf(status))
        Text(
            "本设置只是边界开关：开启后仍需在系统「应用权限」中授予图库读取权限，且后台协调器只会从显式用户动作串行运行，单次受限批量/时长，可取消且可去重；未经配置的云端适配器不会收任何图库数据，图库 AI 总结/向量仍走既有显式触发路径。Photo Picker 无感最低权限导入不受影响。",
            fontSize = 11.sp,
            color = SettingsThemeColors.SecondaryText,
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    SettingsGroupCard(
        title = "地图服务",
        subtitle = "图片位置解析和分析缩放参数",
    ) {
        SettingInput("分析缩放比例", state.draft.galleryImageScale.toString(),
            placeholder = "默认 0.9", singleLine = true) { vm.setGalleryImageScale(it) }
        SensitiveSettingInput(
            key = SettingsKeys.GALLERY_AMAP_WEB_KEY,
            revealedKeys = state.revealedSensitiveKeys,
            label = "高德 Web 服务 Key",
            value = maskedOrText(state.draft.galleryAmapWebKey),
            placeholder = "用于拍摄位置逆地理编码",
            onReveal = { vm.reveal(SettingsKeys.GALLERY_AMAP_WEB_KEY) },
            onHide = { vm.hide(SettingsKeys.GALLERY_AMAP_WEB_KEY) },
            onValueChange = { vm.setGalleryAmapWebKey(it) },
            currentValue = state.draft.galleryAmapWebKey,
        )
    }
}

@Composable
fun ModelAgentsSection(state: SettingsUiState, vm: SettingsViewModel, context: Context) {
    SettingsSectionTitle(
        title = "模型与智能体",
        subtitle = "云端 Planner/Decider、本地模型与 HDC",
        onClick = { vm.hide(SettingsKeys.CLOUD_PLANNER_API_KEY) },
    )

    SettingsGroupCard(
        title = "云端 Planner/Decider",
        subtitle = "云端智能体服务、模型和连通性测试",
    ) {
        SettingInput("Planner 服务", state.draft.cloudPlannerBaseUrl,
            placeholder = "输入 Planner 服务 URL") { vm.setCloudPlannerBaseUrl(it) }
        SettingInput("Planner Model", state.draft.cloudPlannerModel,
            placeholder = "留空则不传 model") { vm.setCloudPlannerModel(it) }
        SensitiveSettingInput(
            key = SettingsKeys.CLOUD_PLANNER_API_KEY,
            revealedKeys = state.revealedSensitiveKeys,
            label = "Planner API Key",
            value = maskedOrText(state.draft.cloudPlannerApiKey),
            placeholder = "Planner 服务密钥",
            onReveal = { vm.reveal(SettingsKeys.CLOUD_PLANNER_API_KEY) },
            onHide = { vm.hide(SettingsKeys.CLOUD_PLANNER_API_KEY) },
            onValueChange = { vm.setCloudPlannerApiKey(it) },
            currentValue = state.draft.cloudPlannerApiKey,
        )
        SettingInput("Decider 服务", state.draft.cloudDeciderBaseUrl,
            placeholder = "输入 Decider 服务 URL") { vm.setCloudDeciderBaseUrl(it) }
        SensitiveSettingInput(
            key = SettingsKeys.CLOUD_DECIDER_API_KEY,
            revealedKeys = state.revealedSensitiveKeys,
            label = "Decider API Key",
            value = maskedOrText(state.draft.cloudDeciderApiKey),
            placeholder = "Decider 服务密钥",
            onReveal = { vm.reveal(SettingsKeys.CLOUD_DECIDER_API_KEY) },
            onHide = { vm.hide(SettingsKeys.CLOUD_DECIDER_API_KEY) },
            onValueChange = { vm.setCloudDeciderApiKey(it) },
            currentValue = state.draft.cloudDeciderApiKey,
        )
        SettingInput("Decider Model", state.draft.cloudDeciderModel,
            placeholder = "留空则不传 model") { vm.setCloudDeciderModel(it) }
        SettingsActionRow(
            actions = listOf(
                SettingsAction(
                    label = if (state.draft.cloudPlannerConfigured) "Planner 已配置" else "Planner 未配置",
                    kind = SettingsActionKind.Success,
                    enabled = state.draft.cloudPlannerConfigured,
                    onClick = {},
                ),
            ),
        )
    }

    SettingsGroupCard(
        title = "HDC Bridge",
        subtitle = "设备自动化连接状态（未配置时显示禁用）",
    ) {
        SettingInput("HDC Server URL", state.draft.hdcServerUrl,
            placeholder = "输入 HDC 服务 URL",
            enabled = state.draft.hdcBridgeConfigured) { vm.setHdcServerUrl(it) }
        SettingInput("无线 HDC 目标", state.draft.wirelessHdcTarget,
            placeholder = "ip:port",
            enabled = state.draft.hdcBridgeConfigured) { vm.setWirelessHdcTarget(it) }

        // Live health test — ONLY on explicit click. Loading / error / disabled
        // states are driven by hdcTesting / hdcTestMessage / hdcBridgeConfigured.
        val testEnabled = state.draft.hdcBridgeConfigured && !state.hdcTesting
        SettingsActionRow(
            actions = listOf(
                SettingsAction(
                    label = if (state.hdcTesting) "正在检查…" else "测试 HDC 健康",
                    kind = SettingsActionKind.Primary,
                    enabled = testEnabled,
                    onClick = { vm.testHdcHealth() },
                ),
            ),
        )
        val status = when {
            state.hdcTesting -> SettingsAction("正在检查连接…", SettingsActionKind.Warning, enabled = false, onClick = {})
            state.hdcTestConnected -> SettingsAction("HDC 已连接", SettingsActionKind.Success, onClick = {})
            state.hdcTestMessage != null ->
                SettingsAction("连接失败", SettingsActionKind.Danger, enabled = false, onClick = {})
            state.draft.hdcConnected -> SettingsAction("已连接", SettingsActionKind.Success, onClick = {})
            state.draft.hdcBridgeConfigured ->
                SettingsAction("未连接（需测试）", SettingsActionKind.Warning, onClick = {})
            else -> SettingsAction("未配置 — DISABLED", SettingsActionKind.Secondary, enabled = false, onClick = {})
        }
        SettingsActionRow(actions = listOf(status))
        state.hdcTestMessage?.let { message ->
            Text(
                message,
                fontSize = 12.sp,
                color = if (state.hdcTestConnected) SettingsThemeColors.Success else SettingsThemeColors.Danger,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Text(
            "测试仅在显式点击时进行，应用启动不会发起网络请求。",
            fontSize = 11.sp,
            color = SettingsThemeColors.SecondaryText,
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    // Phase 10 — local model capability boundary. Shows the typed status and an
    // explicit load/test action; READY only ever follows a real native load.
    LocalModelPanel()

    SettingsGroupCard(
        title = "上下文压缩",
        subtitle = "聊天上下文压缩阈值和保留轮数",
    ) {
        SettingInput("压缩阈值(%)", state.draft.chatCompressionThresholdPercent.toString(),
            placeholder = "默认 85，范围 50-95", singleLine = true) { vm.setCompressionThreshold(it) }
        SettingInput("压缩保留轮数", state.draft.chatRecentRounds.toString(),
            placeholder = "默认 8，范围 2-20", singleLine = true) { vm.setRecentRounds(it) }
    }
}

@Composable
fun TaskAutomationSection(state: SettingsUiState, vm: SettingsViewModel, context: Context) {
    SettingsSectionTitle(
        title = "任务与自动化",
        subtitle = "Agent 执行、Workflow 与运行日志",
        onClick = {},
    )

    SettingsGroupCard(
        title = "Workflow 与自动化",
        subtitle = "Agent 执行到端与硬件步骤边界",
    ) {
        SettingToggle("Agent 自动化", "普通 Agent 执行开关",
            state.draft.workflowAutomationEnabled, onValueChange = vm::setWorkflowAutomationEnabled)
        SettingToggle("硬件/HDC 步骤", "开启后允许 GUI/HDC 自动化步骤（当前未接入，均报告 UNSUPPORTED/BLOCKED）",
            state.draft.workflowHardwareStepsEnabled, onValueChange = vm::setWorkflowHardwareSteps)

        val status = if (state.draft.workflowHardwareStepsEnabled) {
            SettingsAction("硬件步骤仅报告 UNSUPPORTED/BLOCKED", SettingsActionKind.Warning, enabled = false, onClick = {})
        } else {
            SettingsAction("硬件步骤已禁用", SettingsActionKind.Secondary, enabled = false, onClick = {})
        }
        SettingsActionRow(actions = listOf(status))
    }

    SettingsGroupCard(
        title = "AutoAgent 辅助功能边界",
        subtitle = "受控可用性：未勾选时不发起任何操作；勾选后仍需系统无障碍授权与安全执行器",
    ) {
        SettingToggle(
            label = "启用 AutoAgent 辅助功能",
            desc = "显式开启受控可用性检测（默认关闭；开启不等于获得执行能力）",
            selected = state.draft.accessibilityOptIn,
            onValueChange = vm::setAccessibilityOptIn,
        )

        val gate = vm.accessibilityGate()
        val actionRow = when (gate.status) {
            com.clawmate.app.accessibility.AccessibilityGateStatus.NOT_CONFIGURED ->
                SettingsAction("未启用 — NOT_CONFIGURED", SettingsActionKind.Secondary, enabled = false, onClick = {})
            com.clawmate.app.accessibility.AccessibilityGateStatus.BLOCKED ->
                SettingsAction("未观察到系统无障碍授权 — BLOCKED", SettingsActionKind.Warning, enabled = false, onClick = {})
            com.clawmate.app.accessibility.AccessibilityGateStatus.READY ->
                SettingsAction("边界就绪（无安全执行器，动作仍受控）", SettingsActionKind.Warning, enabled = false, onClick = {})
        }
        SettingsActionRow(actions = listOf(actionRow))
        Text(
            "Phase 19 坐标/手势类动作（TAP / TYPE_TEXT / SWIPE / OPEN_APP / CAPTURE_SCREEN）仍为受控禁用：AccessibilityGate 保持 NOT_CONFIGURED 或 BLOCKED，不会伪造成功。Phase 22 的安全语义动作见下方独立边界。",
            fontSize = 12.sp,
            color = SettingsThemeColors.SecondaryText,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    // ------------------------------------------------------------------
    // Phase 22 — safe *semantic* AccessibilityService action executor
    // (typed allowlist + package allowlist; honest demo/test boundary).
    // ------------------------------------------------------------------
    val safeBoundary = vm.safeAccessibilityExecutorBoundary()
    var demoResult by androidx.compose.runtime.mutableStateOf<String?>(null)
    SettingsGroupCard(
        title = "安全语义动作执行器 · 测试/演示边界",
        subtitle = "最小显式白名单（Phase 22）：仅这些语义动作，且每次动作前校验前台包名；无坐标注入/无 Shell/无截图/无隐藏输入",
    ) {
        Text(
            "允许的动作：" + safeBoundary.allowlist.joinToString(" / "),
            fontSize = 12.sp,
            color = SettingsThemeColors.SecondaryText,
        )
        Text(
            "允许的前台包：" + safeBoundary.packageAllowlist.sorted().joinToString(" / "),
            fontSize = 12.sp,
            color = SettingsThemeColors.SecondaryText,
            modifier = Modifier.padding(top = 4.dp),
        )
        // Honest live readiness (never a fake claim).
        val statusAction = when (safeBoundary.readinessLabel) {
            "READY" -> SettingsAction("执行器就绪 READY（演示面板不自动触发真实动作）", SettingsActionKind.Warning, enabled = false, onClick = {})
            "NOT_CONFIGURED" -> SettingsAction("未显式开启 — NOT_CONFIGURED", SettingsActionKind.Secondary, enabled = false, onClick = {})
            "BLOCKED_SERVICE" -> SettingsAction("系统无障碍服务未连接 — BLOCKED_SERVICE", SettingsActionKind.Warning, enabled = false, onClick = {})
            else -> SettingsAction("前台包不在白名单 — BLOCKED_PACKAGE", SettingsActionKind.Warning, enabled = false, onClick = {})
        }
        SettingsActionRow(actions = listOf(statusAction))

        // Demo dispatch: run the *typed preflight path* with a no-perform seam,
        // so the panel never triggers a real system/navigation action and never
        // fabricates SUCCESS — the result is an honest preflight/rejection.
        val demoName = "back"
        SettingsActionRow(
            actions = listOf(
                SettingsAction(
                    label = "触发预检（back）",
                    kind = SettingsActionKind.Primary,
                    enabled = true,
                    onClick = {
                        demoResult = demoDispatchMessage(
                            vm.safeAccessibilityDemoDispatch(demoName, emptyMap()),
                        )
                    },
                ),
            ),
        )
        demoResult?.let { message ->
            Text(
                message,
                fontSize = 12.sp,
                color = SettingsThemeColors.SecondaryText,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Text(
            "本面板仅做预检/诊断：真实系统动作只会由实时辅助功能会话通过类型化执行器执行，并始终在每次动作前校验前台包名。",
            fontSize = 11.sp,
            color = SettingsThemeColors.SecondaryText,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    SettingsGroupCard(
        title = "悬浮窗 Overlay 边界",
        subtitle = "受控可用性：未勾选时不渲染任何浮层；勾选后仍需系统悬浮窗授权与安全状态渲染器",
    ) {
        SettingToggle(
            label = "启用悬浮窗 Overlay",
            desc = "显式开启受控可用性检测（默认关闭；开启等于获得渲染能力还需系统授权）",
            selected = state.draft.overlayOptIn,
            onValueChange = { enabled ->
                vm.setOverlayOptIn(enabled)
                // Turning opt-in off must stop the overlay service (no silent
                // rendering outside an explicit opt-in).
                if (!enabled) vm.stopOverlayOnOptOut()
            },
        )

        val gate = vm.overlayGate()
        val overlayAction = when (gate.status) {
            com.clawmate.app.overlay.OverlayGateStatus.NOT_CONFIGURED ->
                SettingsAction("未启用 — NOT_CONFIGURED", SettingsActionKind.Secondary, enabled = false, onClick = {})
            com.clawmate.app.overlay.OverlayGateStatus.BLOCKED ->
                SettingsAction("未观察到系统悬浮窗授权 — BLOCKED", SettingsActionKind.Warning, enabled = false, onClick = {})
            com.clawmate.app.overlay.OverlayGateStatus.READY ->
                SettingsAction("边界就绪 — READY（状态浮层，仅渲染受限状态文字）", SettingsActionKind.Warning, enabled = false, onClick = {})
        }
        SettingsActionRow(actions = listOf(overlayAction))

        // The single "system-settings permission request action": when the user
        // opted in but the system grant is still missing, present a button that
        // opens Android's `ACTION_MANAGE_OVERLAY_PERMISSION` settings screen.
        // Launcher is created unconditionally (remember count must stay stable
        // across recompositions); only its button is shown conditionally.
        val overlayRequest = vm.overlayPermissionRequest()
        val overlayPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
        ) { /* user returns from the system overlay-settings screen; we re-observe on rebuild */ }
        if (overlayRequest.canRequest) {
            SettingsActionRow(
                actions = listOf(
                    SettingsAction(
                        label = "打开系统设置授予悬浮窗权限",
                        kind = SettingsActionKind.Primary,
                        enabled = true,
                        onClick = {
                            overlayPermLauncher.launch(
                                android.content.Intent(
                                    overlayRequest.action,
                                    android.net.Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        },
                    ),
                ),
            )
        }
        Text(
            "当边界 READY（已勾选 + 已授予系统悬浮窗权限）时，可通过下方按钮显式启动/停止本地状态浮层。该浮层只显示受限的智能体状态文字（≤160 字符），不提供截图、不拦截输入、不执行任何命令，也不支持边缘流光特效或智能体 GUI 控制。",
            fontSize = 12.sp,
            color = SettingsThemeColors.SecondaryText,
            modifier = Modifier.padding(top = 8.dp),
        )

        // Explicit start/stop of the local status overlay service — only enabled
        // while the gate is READY, and only ever dispatched inside a click
        // handler (never on app launch or recomposition).
        val startStep = vm.overlayServiceStartStep()
        val present = com.clawmate.app.overlay.OverlayStateRegistry.isOverlayPresent()
        SettingsActionRow(
            actions = listOf(
                SettingsAction(
                    label = when {
                        present -> "状态浮层运行中 — 停止"
                        startStep == com.clawmate.app.overlay.OverlayServiceStartStep.START -> "启动本地状态浮层"
                        else -> "启动本地状态浮层（需先 READY）"
                    },
                    kind = if (present) SettingsActionKind.Danger else SettingsActionKind.Primary,
                    enabled = if (present) true else startStep == com.clawmate.app.overlay.OverlayServiceStartStep.START,
                    onClick = {
                        if (present) vm.stopOverlayService() else vm.startOverlayService()
                    },
                ),
            ),
        )
        Text(
            "本地状态浮层仅在显式点击时启动/停止；应用启动或界面重组合不会自动启动。关闭「启用悬浮窗 Overlay」会自动停止浮层。边缘流光特效与智能体 GUI 控制在 Android 上仍不支持。",
            fontSize = 11.sp,
            color = SettingsThemeColors.SecondaryText,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    SettingsGroupCard(
        title = "PC 控制后端",
        subtitle = "启动云端 PC 控制后端供 Agent 自动化使用",
    ) {
        SettingInput("PC Server URL", state.draft.pcServerUrl,
            placeholder = "输入 PC Server URL") { vm.setPcServerUrl(it) }
        val pcStatus = if (state.draft.pcConnected) {
            SettingsAction("已连接", SettingsActionKind.Success, enabled = false, onClick = {})
        } else {
            SettingsAction("后端未连接 — 需显式启动", SettingsActionKind.Secondary, enabled = false, onClick = {})
        }
        SettingsActionRow(actions = listOf(pcStatus))
    }

    // ------------------------------------------------------------------
    // Phase 36 — periodic local-only consolidation (explicit opt-in +
    // explicit schedule + explicit run; never auto-scheduled).
    // ------------------------------------------------------------------
    SettingsGroupCard(
        title = "本地周期整合调度",
        subtitle = "显式开启的本地周期重构（Phase 36）：默认关闭；开启后仍需点「应用调度」才注册一个本地任务，且任何时刻都不会从启动/Home/重组合自动注册",
    ) {
        SettingToggle(
            label = "启用本地周期整合",
            desc = "显式开启受控本地重构开关（默认关闭；开启本身不会自动注册任务）",
            selected = state.draft.periodicLocalConsolidationEnabled,
            onValueChange = vm::setPeriodicLocalConsolidationEnabled,
        )
        SettingsActionRow(
            actions = listOf(
                SettingsAction(
                    label = if (state.draft.periodicLocalConsolidationEnabled) "应用调度（注册本地任务）" else "关闭/取消调度",
                    kind = SettingsActionKind.Primary,
                    enabled = !state.periodicSchedulingBusy,
                    onClick = { vm.schedulePeriodicLocalConsolidation() },
                ),
            ),
        )
        SettingsActionRow(
            actions = listOf(
                SettingsAction(
                    label = "立即运行一次本地重构",
                    kind = SettingsActionKind.Secondary,
                    enabled = !state.periodicSchedulingBusy && state.draft.periodicLocalConsolidationEnabled &&
                        state.periodicScheduled,
                    onClick = { vm.runPeriodicLocalConsolidationNow() },
                ),
            ),
        )
        val scheduleStatus = if (state.periodicSchedulingBusy) {
            SettingsAction("处理中…", SettingsActionKind.Warning, enabled = false, onClick = {})
        } else if (state.periodicScheduled) {
            SettingsAction("本地任务已注册 — 仅本地重构", SettingsActionKind.Success, enabled = false, onClick = {})
        } else {
            SettingsAction("未注册 — 本地周期任务禁用", SettingsActionKind.Secondary, enabled = false, onClick = {})
        }
        SettingsActionRow(actions = listOf(scheduleStatus))
        state.periodicScheduleMessage?.let { m ->
            Text(m, fontSize = 12.sp, color = SettingsThemeColors.SecondaryText, modifier = Modifier.padding(top = 6.dp))
        }
        Text(
            "本调度只运行确定性本地整合/索引/画像重建（Phase 30/33/32），串行、幂等、可取消，绝不调用云端整合存储，也不从启动、Home 加载或普通 repository 构造自动注册。",
            fontSize = 11.sp,
            color = SettingsThemeColors.SecondaryText,
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    // ------------------------------------------------------------------
    // Phase 36 — explicit model entity enrichment (Stream 2). Only persisted on
    // an explicit MODEL success; never auto-run / auto-written.
    // ------------------------------------------------------------------
    SettingsGroupCard(
        title = "实体增强（模型）",
        subtitle = "对本地实体索引做显式模型澄清（Phase 36）：零网络除非显式触发 + 完整配置 + 真实候选；未配置/未启用时保持本地文档不变",
    ) {
        SettingsActionRow(
            actions = listOf(
                SettingsAction(
                    label = "运行实体增强",
                    kind = SettingsActionKind.Primary,
                    enabled = !state.enrichBusy,
                    onClick = { vm.runEntityEnrichment() },
                ),
            ),
        )
        val enrichStatus = if (state.enrichBusy) {
            SettingsAction("增强中…", SettingsActionKind.Warning, enabled = false, onClick = {})
        } else if (state.enrichResultMessage != null) {
            SettingsAction("最近一次增强已完成", SettingsActionKind.Secondary, enabled = false, onClick = {})
        } else {
            SettingsAction("未执行 — DISABLED（默认）", SettingsActionKind.Secondary, enabled = false, onClick = {})
        }
        SettingsActionRow(actions = listOf(enrichStatus))
        state.enrichResultMessage?.let { m ->
            Text(m, fontSize = 12.sp, color = SettingsThemeColors.SecondaryText, modifier = Modifier.padding(top = 6.dp))
        }
        Text(
            "仅在显式点击时运行：模型输出经严格 JSON + 真实 record/source 白名单校验后，只有明确成功的 MODEL 结果才会由调用方写入增强索引；LOCAL_ONLY/FALLBACK/EMPTY 都不会写入，旧索引保持不变。启动/Home/重组合不会自动执行。",
            fontSize = 11.sp,
            color = SettingsThemeColors.SecondaryText,
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    // ------------------------------------------------------------------
    // Phase 36 — explicit generic batch event enrichment (Stream 3). Returned as
    // a typed surface keyed by real event id; never auto-persisted.
    // ------------------------------------------------------------------
    SettingsGroupCard(
        title = "事件批量增强（模型）",
        subtitle = "对真实已索引事件批量抽取关键词/领域/实体/待办（Phase 36）：零网络除非显式触发 + 完整配置 + 真实事件",
    ) {
        SettingsActionRow(
            actions = listOf(
                SettingsAction(
                    label = "运行批量事件增强",
                    kind = SettingsActionKind.Primary,
                    enabled = !state.enrichBusy,
                    onClick = { vm.runBatchEventEnrichment() },
                ),
            ),
        )
        state.batchEnrichResultMessage?.let { m ->
            Text(m, fontSize = 12.sp, color = SettingsThemeColors.SecondaryText, modifier = Modifier.padding(top = 6.dp))
        }
        Text(
            "仅在显式点击时运行：模型对真实事件 id 做白名单匹配，把增强结果持久化到版本化索引（关键词/领域/实体/待办/方法），事件查询与画像边界可读取；失败/畸形/未知 id 回退到确定性本地规则增强且不写入（旧索引保持不变），不携带 URI/密钥。",
            fontSize = 11.sp,
            color = SettingsThemeColors.SecondaryText,
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    // ------------------------------------------------------------------
    // Phase 37 — explicit fact splitting (Harmony `splitEntriesIntoFacts` /
    // `splitEntryIntoFacts`; result returned, caller decides persistence).
    // ------------------------------------------------------------------
    SettingsGroupCard(
        title = "日志事实拆分（模型）",
        subtitle = "把真实 daily-log 条目批量为候选事实（Phase 37）：零网络除非显式触发 + 完整配置 + 真实条目；结果仅返回，不自动写入",
    ) {
        SettingsActionRow(
            actions = listOf(
                SettingsAction(
                    label = "运行事实拆分",
                    kind = SettingsActionKind.Primary,
                    enabled = !state.enrichBusy,
                    onClick = { vm.runFactSplit() },
                ),
            ),
        )
        state.factSplitResultMessage?.let { m ->
            Text(m, fontSize = 12.sp, color = SettingsThemeColors.SecondaryText, modifier = Modifier.padding(top = 6.dp))
        }
        Text(
            "仅在显式点击时运行：模型对每个真实 source_ref 做白名单匹配，按 batch/chars/count 限制返回候选事实（summary / normalized_fact / event_date / tags / model_notes / dedup_text）；失败/畸形/越界回退到确定性本地拆分，绝不伪造事实。结果按真实 source_ref 键控返回，调用方自行决定是否持久化。",
            fontSize = 11.sp,
            color = SettingsThemeColors.SecondaryText,
            modifier = Modifier.padding(top = 6.dp),
        )
        // Phase 38 — the downstream persisted consolidation of the fact split.
        SettingsActionRow(
            actions = listOf(
                SettingsAction(
                    label = "事实拆分→模型整合并持久化",
                    kind = SettingsActionKind.Primary,
                    enabled = !state.enrichBusy,
                    onClick = { vm.runFactSplitAndConsolidate() },
                ),
            ),
        )
        state.factSplitConsolidationMessage?.let { m ->
            Text(m, fontSize = 12.sp, color = SettingsThemeColors.SecondaryText, modifier = Modifier.padding(top = 6.dp))
        }
        Text(
            "显式点击后，事实拆分结果会送入已有的模型感知整合引擎（CloudConsolidationRepository + ModelConsolidationApplier），把真实 daily-log 拆成的事实去重并持久化到 outputs/索引/画像；未配置或零真实条目时不写入，旧状态保持不变。不在启动/Home/重组合自动运行。",
            fontSize = 11.sp,
            color = SettingsThemeColors.SecondaryText,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
fun StorageAboutSection(state: SettingsUiState, vm: SettingsViewModel, context: Context) {
    SettingsSectionTitle(
        title = "存储与关于",
        subtitle = "模型文件、调试产物、版本与说明",
        onClick = {},
    )

    val storage = rememberStorageSummary(context)

    SettingsGroupCard(
        title = "存储概览",
        subtitle = "应用私有 storage 用量摘要",
    ) {
        InfoRow("缓存占用", human(storage.cacheBytes))
        InfoRow("文件占用", human(storage.filesBytes))
        InfoRow("内置 Workflows", "${storage.workflowCount}")
        InfoRow("已下发本地模型", "${storage.modelCount}")
    }

    SettingsGroupCard(
        title = "清理操作",
        subtitle = "删除类操作会先弹窗确认，且绝不默认删除目录",
    ) {
        SettingsActionRow(
            actions = listOf(
                SettingsAction("清理截图", SettingsActionKind.Danger, enabled = false, onClick = {}),
            ),
        )
        Text(
            "当前环境未接入截图/调试产物目录，破坏性清理不可用。为避免误删数据，这里不执行任何文件删除。",
            fontSize = 12.sp,
            color = SettingsThemeColors.SecondaryText,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    SettingsGroupCard(
        title = "关于",
        subtitle = "当前应用与设置中心说明",
    ) {
        InfoRow("应用", "ClawMate Android")
        InfoRow("版本", "${state.versionName} (${state.versionCode})")
        InfoRow("数据源", "mobiinfra（HarmonyOS 参考，仅作对照）")
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = SettingsThemeColors.SecondaryText, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = SettingsThemeColors.TitleColor)
    }
}

@Composable
private fun AutoConfirmToggleRow(
    label: String,
    desc: String,
    selected: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    SettingToggle(label, desc, selected, onValueChange = onChecked)
}

/** Display masking only — the real secret stays in [current]. */
private fun maskedOrText(current: String): String = current

/** Deterministic, app-private storage summary (never touches user media). */
@Composable
private fun rememberStorageSummary(context: Context): StorageSummary {
    return androidx.compose.runtime.remember(context) {
        val app = context.applicationContext
        var cache = 0L
        var files = 0L
        runCatching { app.cacheDir?.listFiles()?.forEach { cache += it.length() } }
        runCatching { app.filesDir?.listFiles()?.forEach { files += it.length() } }
        runCatching {
            listOf(
                "default_meituan_order.json", "default_ele_order.json", "default_weibo_hot_search.json",
                "default_weixin_chat.json", "default_weixin_today_chat.json", "default_xiaohongshu_history.json",
                "default_dianping_order.json", "default_alipay_bill.json", "default_ctrip_order.json",
            )
        }
        StorageSummary(
            cacheBytes = cache,
            filesBytes = files,
            modelCount = 0,
            workflowCount = listOf(
                "default_meituan_order.json", "default_ele_order.json", "default_weibo_hot_search.json",
                "default_weixin_chat.json", "default_weixin_today_chat.json", "default_xiaohongshu_history.json",
                "default_dianping_order.json", "default_alipay_bill.json", "default_ctrip_order.json",
                "default_bilibili_history.json", "default_tencent_video_history.json",
            ).size,
        )
    }
}

private fun human(bytes: Long): String {
    if (bytes <= 0) return "—"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.1f GB".format(mb / 1024.0)
}

/** Placeholder object to avoid accidental string misuse in toggle rows. */
internal object SettingKeys {
    const val AutoConfirm = "autoconfirm"
}

/**
 * Phase 22 — renders the honest result of a safe-executor demo dispatch as a
 * one-line label. Never fabricates SUCCESS: non-success states surface their
 * truthful category, and a READY-but-not-performed notice is explicitly NOT
 * success (the demo seam never performs a real action).
 */
private fun demoDispatchMessage(result: com.clawmate.app.accessibility.AccessibilityExecutionOutcome): String =
    when (result) {
        is com.clawmate.app.accessibility.AccessibilityExecutionOutcome.NotConfigured ->
            "预检结果：NOT_CONFIGURED — " + result.message
        is com.clawmate.app.accessibility.AccessibilityExecutionOutcome.BlockedService ->
            "预检结果：BLOCKED_SERVICE — " + result.reason
        is com.clawmate.app.accessibility.AccessibilityExecutionOutcome.BlockedPackage ->
            "预检结果：BLOCKED_PACKAGE — " + result.reason
        is com.clawmate.app.accessibility.AccessibilityExecutionOutcome.Unsupported ->
            "预检结果：UNSUPPORTED（未执行）— " + result.reason
        is com.clawmate.app.accessibility.AccessibilityExecutionOutcome.Error ->
            "预检结果：ERROR（未执行）— " + result.reason
        is com.clawmate.app.accessibility.AccessibilityExecutionOutcome.Success ->
            // Unreachable from the demo seam (it never performs); kept honest if
            // it were ever wired to a real seam — SUCCESS only means performed.
            "边界就绪 READY（演示面板不触发真实动作，故此处不会报告 SUCCESS）。"
    }

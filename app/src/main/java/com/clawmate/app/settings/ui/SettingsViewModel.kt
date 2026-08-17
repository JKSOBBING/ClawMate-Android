package com.clawmate.app.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clawmate.app.config.EndpointConfig
import com.clawmate.app.settings.config.EndpointBridge
import com.clawmate.app.settings.data.SettingsRepository
import com.clawmate.app.settings.domain.AppSettings
import com.clawmate.app.settings.domain.AutoConfirmMode
import com.clawmate.app.settings.domain.EmbeddingMode
import com.clawmate.app.settings.domain.SensitiveValueUtils
import com.clawmate.app.settings.domain.SettingsSectionId
import com.clawmate.app.settings.domain.SettingsNormalizers
import com.clawmate.app.wechat.domain.WechatAutoSummaryModes
import com.clawmate.app.wechat.domain.normalizeWechatAutoSummaryDays
import com.clawmate.app.wechat.domain.normalizeWechatAutoSummaryHistorySwipeRatio
import com.clawmate.app.wechat.domain.normalizeWechatAutoSummaryMaxContacts
import com.clawmate.app.wechat.domain.normalizeWechatAutoSummaryMaxHistorySwipes
import com.clawmate.app.wechat.domain.normalizeWechatAutoSummaryMode
import com.clawmate.app.wechat.domain.normalizeWechatAutoSummaryStableSwipes
import com.clawmate.app.wechat.domain.normalizeWechatAutoSummarySwipeSpeed
import com.clawmate.app.wechat.domain.normalizeWechatAutoSummaryWait
import com.clawmate.app.task.TaskComponent
import com.clawmate.app.task.hdc.BridgeBackedWorkflowDeviceAdapter
import com.clawmate.app.task.hdc.DisabledWorkflowDeviceAdapter
import com.clawmate.app.task.hdc.HdcBridgeClient
import com.clawmate.app.task.hdc.HdcBridgeResult
import com.clawmate.app.task.hdc.HdcUrlNormalizer
import com.clawmate.app.task.hdc.OkHttpHdcBridgeClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the Settings center.
 *
 * Owns the persistent store ([SettingsRepository]), a working [SettingsUiState
 * .draft] that the section UI edits, section navigation, sensitive-value
 * reveal/edit state, and save/reset actions. Connectivity (HDC, planner,
 * gallery) is reported as NOT_CONFIGURED / DISABLED until a real endpoint is
 * configured — never assumed connected.
 */
class SettingsViewModel(
    private val repository: SettingsRepository,
    private val versionName: String = "1.0.0",
    private val versionCode: Long = 1L,
    /**
     * Injected HDC bridge client factory so unit tests can stub the client
     * without a network. Defaults to the production OkHttp implementation.
     */
    private val hdcClientFactory: (String) -> HdcBridgeClient = { url -> OkHttpHdcBridgeClient(url) },
    /**
     * Phase 19 — injectable AutoAgent accessibility detector so unit tests can
     * drive the gate without Android system APIs. Defaults to the live
     * registry-backed detector that mirrors what the scaffold actually reports.
     */
    private val accessibilityDetector: com.clawmate.app.accessibility.AccessibilityServiceDetector =
        com.clawmate.app.accessibility.RegistryAccessibilityServiceDetector(),
    /**
     * Phase 20 — injectable float/edge overlay detector so unit tests can drive
     * the overlay gate without Android system APIs. Defaults to the live
     * registry-backed detector that mirrors what the overlay scaffold actually
     * observes.
     */
    private val overlayDetector: com.clawmate.app.overlay.OverlayPermissionDetector =
        com.clawmate.app.overlay.RegistryOverlayPermissionDetector(),
    /**
     * Phase 26 — injectable overlay *service* controller so the explicit
     * start/stop action is unit-testable device-free. Defaults to a no-op-safe
     * controller when constructed without a Context (the real Android
     * [com.clawmate.app.overlay.AndroidOverlayServiceController] is wired by
     * the Settings UI/factory that holds a Context). The pure
     * [OverlayServiceStartPolicy] decision is always computed regardless of
     * which controller is injected.
     */
    private val overlayServiceController: com.clawmate.app.overlay.OverlayServiceController =
        object : com.clawmate.app.overlay.OverlayServiceController {
            override fun startService() {
                // No Context in the plain unit-test construction; the UI layer
                // injects the Android controller when a Context is available.
            }

            override fun stopService() {
                // No Context in the plain unit-test construction.
            }
        },
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState(versionName = versionName, versionCode = versionCode))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val persisted = runCatching { repository.snapshot() }.getOrDefault(AppSettings.defaults())
            // Seed from runtime EndpointConfig (git-ignored local_config) if no
            // user override has been saved yet.
            val seed = EndpointBridge.seeded(persisted)
            val draft = seed.copy(
                // Display masked secrets from persisted key; never leak plaintext
                // of a non-seeded secret unless the user reveals it.
                cloudPlannerApiKey = safeSecret(persisted.cloudPlannerApiKey, seed.cloudPlannerApiKey),
            )
            _state.update {
                it.copy(
                    loading = false,
                    draft = draft,
                    appSettings = draft,
                    dirty = false,
                )
            }
        }
    }

    private fun safeSecret(persisted: String, seeded: String): String =
        if (SensitiveValueUtils.isMaskedSensitiveValue(persisted)) {
            // persisted value is a stale mask; start clean
            if (seeded.isNotBlank() && !seeded.equals(persisted, ignoreCase = true)) seeded else ""
        } else {
            // prefer the user's persisted plaintext; else seeded runtime value
            if (persisted.isNotBlank()) persisted else seeded
        }

    // ---------------------------------------------------------------- sections

    fun selectSection(section: SettingsSectionId) {
        closeRevealedSensitive()
        _state.update { it.copy(selectedSectionId = SettingsSectionId.normalize(section.ordinalIndex)) }
    }

    fun closeSection() {
        closeRevealedSensitive()
        _state.update { it.copy(selectedSectionId = SettingsSectionId.NONE) }
    }

    // --------------------------------------------------------------- sensitive

    /** Reveal one sensitive field so its real value can be edited. */
    fun reveal(key: String) {
        _state.update { it.copy(revealedSensitiveKeys = setOf(key)) }
    }

    fun hide(key: String) {
        _state.update { it.copy(revealedSensitiveKeys = emptySet()) }
    }

    private fun closeRevealedSensitive() {
        _state.update { it.copy(revealedSensitiveKeys = emptySet()) }
    }

    // ------------------------------------------------------------------ fields

    fun updateDraft(transform: (AppSettings) -> AppSettings) {
        _state.update { it.copy(draft = transform(it.draft), dirty = true) }
    }

    fun setCloudPlannerBaseUrl(value: String) = updateDraft { it.copy(cloudPlannerBaseUrl = value) }
    fun setCloudPlannerModel(value: String) = updateDraft { it.copy(cloudPlannerModel = value) }
    fun setCloudPlannerApiKey(value: String) = updateDraft { it.copy(cloudPlannerApiKey = value) }

    fun setCloudDeciderBaseUrl(value: String) = updateDraft { it.copy(cloudDeciderBaseUrl = value) }
    fun setCloudDeciderApiKey(value: String) = updateDraft { it.copy(cloudDeciderApiKey = value) }
    fun setCloudDeciderModel(value: String) = updateDraft { it.copy(cloudDeciderModel = value) }

    fun setAvatarBaseUrl(value: String) = updateDraft { it.copy(avatarImageBaseUrl = value) }
    fun setAvatarApiKey(value: String) = updateDraft { it.copy(avatarImageApiKey = value) }
    fun setAvatarModel(value: String) = updateDraft { it.copy(avatarTextToImageModel = value) }
    fun setAvatarSize(value: String) = updateDraft { it.copy(avatarImageSize = value) }

    fun setGalleryBaseUrl(value: String) = updateDraft { it.copy(galleryApiBaseUrl = value) }
    fun setGalleryApiKey(value: String) = updateDraft { it.copy(galleryApiKey = value) }
    fun setGalleryModel(value: String) = updateDraft { it.copy(galleryModel = value) }
    fun setGallerySkipOcr(value: Boolean) = updateDraft { it.copy(gallerySkipOcr = value) }
    fun setGalleryOcrFilter(value: Boolean) = updateDraft { it.copy(galleryUseOcrKeywordFilter = value) }
    fun setGalleryParallel(value: Boolean) = updateDraft { it.copy(galleryParallelFieldRequests = value) }
    fun setGalleryOcrConcurrency(value: String) {
        val n = SettingsNormalizers.concurrency(value, _state.value.draft.galleryOcrConcurrency)
        updateDraft { it.copy(galleryOcrConcurrency = n) }
    }
    fun setGalleryModelConcurrency(value: String) {
        val n = SettingsNormalizers.concurrency(value, _state.value.draft.galleryModelConcurrency)
        updateDraft { it.copy(galleryModelConcurrency = n) }
    }
    fun setGalleryEmbeddingConcurrency(value: String) {
        val n = SettingsNormalizers.concurrency(value, _state.value.draft.galleryEmbeddingConcurrency)
        updateDraft { it.copy(galleryEmbeddingConcurrency = n) }
    }
    fun setGalleryEmbeddingMode(mode: EmbeddingMode) = updateDraft { it.copy(galleryEmbeddingMode = mode) }
    fun setGalleryEmbeddingBaseUrl(value: String) = updateDraft { it.copy(galleryEmbeddingBaseUrl = value) }
    fun setGalleryEmbeddingApiKey(value: String) = updateDraft { it.copy(galleryEmbeddingApiKey = value) }
    fun setGalleryEmbeddingModel(value: String) = updateDraft { it.copy(galleryEmbeddingModel = value) }
    fun setGalleryAiSummaryEnabled(value: Boolean) = updateDraft { it.copy(galleryAiSummaryEnabled = value) }
    fun setGalleryBackgroundSyncEnabled(value: Boolean) = updateDraft { it.copy(galleryBackgroundSyncEnabled = value) }
    fun setGalleryImageScale(value: String) {
        val n = SettingsNormalizers.normalizedDouble(value, _state.value.draft.galleryImageScale, 0.1, 1.0)
        updateDraft { it.copy(galleryImageScale = n) }
    }
    fun setGalleryAmapWebKey(value: String) = updateDraft { it.copy(galleryAmapWebKey = value) }

    fun setHdcServerUrl(value: String) = updateDraft { it.copy(hdcServerUrl = value) }
    fun setWirelessHdcTarget(value: String) = updateDraft { it.copy(wirelessHdcTarget = value) }
    fun setCompressionThreshold(value: String) {
        val n = SettingsNormalizers.compressionThresholdPercent(value)
        updateDraft { it.copy(chatCompressionThresholdPercent = n) }
    }
    fun setRecentRounds(value: String) {
        val n = SettingsNormalizers.recentRounds(value)
        updateDraft { it.copy(chatRecentRounds = n) }
    }

    fun setLocalAutoConfirm(mode: AutoConfirmMode) = updateDraft { it.copy(agentLocalConfirm = mode) }
    fun setCloudAutoConfirm(mode: AutoConfirmMode) = updateDraft { it.copy(agentCloudConfirm = mode) }

    fun setWorkflowAutomationEnabled(value: Boolean) = updateDraft { it.copy(workflowAutomationEnabled = value) }
    fun setWorkflowHardwareSteps(value: Boolean) = updateDraft { it.copy(workflowHardwareStepsEnabled = value) }
    fun setAccessibilityOptIn(value: Boolean) = updateDraft { it.copy(accessibilityOptIn = value) }
    fun setOverlayOptIn(value: Boolean) = updateDraft { it.copy(overlayOptIn = value) }
    fun setPcServerUrl(value: String) = updateDraft { it.copy(pcServerUrl = value) }

    // ----- WeChat auto-summary (Phase 13) — fields are normalized on change.
    fun setWechatAutoSummaryEnabled(value: Boolean) = updateDraft { it.copy(wechatAutoSummaryEnabled = value) }
    fun setWechatAutoSummaryDays(value: String) {
        val n = normalizeWechatAutoSummaryDays(value)
        updateDraft { it.copy(wechatAutoSummaryDays = n) }
    }
    fun setWechatAutoSummaryMaxContacts(value: String) {
        val n = normalizeWechatAutoSummaryMaxContacts(value)
        updateDraft { it.copy(wechatAutoSummaryMaxContacts = n) }
    }
    fun setWechatAutoSummarySwipeSpeed(value: String) {
        val n = normalizeWechatAutoSummarySwipeSpeed(value)
        updateDraft { it.copy(wechatAutoSummarySwipeSpeed = n) }
    }
    fun setWechatAutoSummaryHistorySwipeRatio(value: String) {
        val n = normalizeWechatAutoSummaryHistorySwipeRatio(value)
        updateDraft { it.copy(wechatAutoSummaryHistorySwipeRatio = n) }
    }
    fun setWechatAutoSummaryStableSwipes(value: String) {
        val n = normalizeWechatAutoSummaryStableSwipes(value)
        updateDraft { it.copy(wechatAutoSummaryStableSwipes = n) }
    }
    fun setWechatAutoSummaryMaxHistorySwipes(value: String) {
        val n = normalizeWechatAutoSummaryMaxHistorySwipes(value)
        updateDraft { it.copy(wechatAutoSummaryMaxHistorySwipes = n) }
    }
    fun setWechatAutoSummaryWait(value: String) {
        val n = normalizeWechatAutoSummaryWait(value)
        updateDraft { it.copy(wechatAutoSummaryWait = n) }
    }
    fun setWechatCollectionMode(value: String) {
        val normalized = if (value == WechatAutoSummaryModes.TARGET) WechatAutoSummaryModes.TARGET else WechatAutoSummaryModes.RECENT
        updateDraft { it.copy(wechatCollectionMode = normalized) }
    }
    fun setWechatCollectionTargetContact(value: String) = updateDraft { it.copy(wechatCollectionTargetContact = value) }

    // ------------------------------------------------------------------ actions

    fun save() {
        val draft = _state.value.draft
        viewModelScope.launch {
            _state.update { it.copy(saveBusy = true, saved = false, error = null) }
            val clean = runCatching { repository.save(draft) }
                .getOrElse { e ->
                    _state.update { it.copy(saveBusy = false, error = "保存失败：${e.message}") }
                    return@launch
                }
            // Push normalized cloud endpoint into runtime config for the chat/gallery.
            runCatching { EndpointBridge.applyToRuntime(clean) }

            com.clawmate.app.log.AppLogger.i("Settings", "设置已保存")
            _state.update {
                it.copy(
                    saveBusy = false,
                    saved = true,
                    appSettings = clean,
                    draft = clean,
                    dirty = false,
                    revealedSensitiveKeys = emptySet(), // lock secrets again after save
                )
            }
        }
    }

    /**
     * Runs a live HDC bridge health check — **only by explicit user click**, and
     * never on app launch. Requires a configured `hdcServerUrl`. On a successful
     * `ok` + control-ready reply it marks the bridge connected and registers the
     * health-verified adapter with [TaskComponent] so the workflow runner can
     * surface honest bridge steps. Anything else keeps the bridge NOT_CONFIGURED
     * and shows a truthful error/loading/disabled state. No secrets are logged.
     */
    fun testHdcHealth() {
        if (_state.value.hdcTesting) return
        val url = _state.value.draft.hdcServerUrl
        if (HdcUrlNormalizer.normalizeBaseUrl(url).isEmpty()) {
            _state.update { it.copy(hdcTesting = false, hdcTestConnected = false, hdcTestMessage = "未配置 HDC Server URL") }
            return
        }
        _state.update { it.copy(hdcTesting = true, hdcTestConnected = false, hdcTestMessage = null) }
        viewModelScope.launch {
            val adapter = BridgeBackedWorkflowDeviceAdapter(hdcClientFactory(url))
            val result = adapter.refreshHealth()
            when (result) {
                is com.clawmate.app.task.hdc.HdcActionResult.Success -> {
                    TaskComponent.setDeviceAdapter(adapter)
                    com.clawmate.app.log.AppLogger.workflow(
                        com.clawmate.app.log.domain.LogLevel.INFO,
                        "HDC",
                        "HDC bridge 健康检查通过",
                    )
                    _state.update {
                        it.copy(
                            hdcTesting = false,
                            hdcTestConnected = true,
                            hdcTestMessage = result.response.message?.takeIf { msg -> msg.isNotBlank() }
                                ?: "HDC bridge 健康检查通过",
                        )
                    }
                }
                is com.clawmate.app.task.hdc.HdcActionResult.BridgeError -> {
                    TaskComponent.setDeviceAdapter(null)
                    _state.update {
                        it.copy(
                            hdcTesting = false,
                            hdcTestConnected = false,
                            hdcTestMessage = result.response.message?.takeIf { msg -> msg.isNotBlank() }
                                ?: "HDC bridge 报告未连接",
                        )
                    }
                }
                is com.clawmate.app.task.hdc.HdcActionResult.TransportError -> {
                    TaskComponent.setDeviceAdapter(null)
                    com.clawmate.app.log.AppLogger.workflow(
                        com.clawmate.app.log.domain.LogLevel.ERROR,
                        "HDC",
                        "HDC 健康检查失败: ${result.message}",
                    )
                    _state.update {
                        it.copy(
                            hdcTesting = false,
                            hdcTestConnected = false,
                            hdcTestMessage = "HDC 健康检查失败: ${result.message}",
                        )
                    }
                }
                is com.clawmate.app.task.hdc.HdcActionResult.Unsupported -> {
                    TaskComponent.setDeviceAdapter(null)
                    _state.update {
                        it.copy(hdcTesting = false, hdcTestConnected = false, hdcTestMessage = result.reason)
                    }
                }
            }
        }
    }

    /** Clears the HDC bridge test result and disconnects any registered adapter. */
    fun clearHdcTestResult() {
        TaskComponent.setDeviceAdapter(null)
        _state.update { it.copy(hdcTestConnected = false, hdcTestMessage = null) }
    }

    fun resetToDefaults() {
        _state.update { it.copy(dirty = true, draft = AppSettings.defaults()) }
    }

    fun dismissSaved() {
        _state.update { it.copy(saved = false) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /** No-op safety gate — destructive cleanup actions are surfaced but never run. */
    fun onDestructiveAction(key: String) {
        // Deliberately a no-op: code paths that would delete real directories or
        // user data are never wired here without a distinct, explicit UI action.
    }

    /** Restore a masked value to its known default (only when mask == default mask). */
    fun normalizeMaskedSecretIfDefault(persistedKey: String, defaultValue: String): String =
        SensitiveValueUtils.restoreMaskedSensitiveSettingDefault(persistedKey, defaultValue)

    /**
     * Build the Phase-25B WeChat collection boundary from live state: collection
     * is available only when a health-verified adapter is registered via
     * [TaskComponent]. The explicit [WechatCollectionGate.collect] invokes the
     * safe `wechatCollect` adapter action; failing any gate (validation /
     * disabled auto-summary / not-configured adapter) returns an honest
     * BLOCKED / NOT_CONFIGURED without any bridge call.
     */
    fun wechatCollectionGate(): com.clawmate.app.wechat.domain.WechatCollectionGate {
        val adapter = runCatching { TaskComponent.currentAdapter() }
            .getOrDefault(DisabledWorkflowDeviceAdapter)
        return com.clawmate.app.wechat.domain.WechatCollectionGate(
            adapter = adapter,
            summaryEnabled = _state.value.draft.wechatAutoSummaryEnabled,
        )
    }

    /**
     * Phase 19 — build the AutoAgent controlled-accessibility boundary from
     * live state. The gate reflects the user's explicit [accessibilityOptIn]
     * draft, the *actually-observed* system accessibility grant (via the
     * injected [accessibilityDetector], which reads the live service registry
     * by default) and the (always-absent today) safe provider. It never
     * executes an action and never fabricates availability.
     */
    fun accessibilityGate(): com.clawmate.app.accessibility.AccessibilityGate {
        val d = _state.value.draft
        return com.clawmate.app.accessibility.AccessibilityGate(
            optedIn = d.accessibilityOptIn,
            systemServiceEnabled = accessibilityDetector.isServiceEnabledBySystem(),
            providerConfigured = accessibilityDetector.isProviderConfigured(),
        )
    }

    /**
     * Phase 22 — the honest *demo/test boundary* model for the safe semantic
     * executor ([com.clawmate.app.accessibility.SafeAccessibilityActionExecutor]).
     * Derived from live observed facts (opt-in draft, observed system service
     * grant via the injected detector, observed foreground package via the
     * registry) and the closed safe allowlists. It is purely read-only and never
     * claims any action ran.
     */
    fun safeAccessibilityExecutorBoundary():
        com.clawmate.app.accessibility.SafeAccessibilityExecutorBoundary {
        val d = _state.value.draft
        val serviceEnabled = accessibilityDetector.isServiceEnabledBySystem()
        val foreground =
            com.clawmate.app.accessibility.AccessibilityStateRegistry.currentForegroundPackage()
        val gate = com.clawmate.app.accessibility.SafeAccessibilityDefaults.gate(
            optedIn = d.accessibilityOptIn,
            serviceEnabled = serviceEnabled,
            currentForegroundPackage = foreground,
        )
        val label = when (gate.readiness) {
            com.clawmate.app.accessibility.SafeAccessibilityReadiness.NotConfigured -> "NOT_CONFIGURED"
            com.clawmate.app.accessibility.SafeAccessibilityReadiness.BlockedService -> "BLOCKED_SERVICE"
            com.clawmate.app.accessibility.SafeAccessibilityReadiness.BlockedPackage -> "BLOCKED_PACKAGE"
            com.clawmate.app.accessibility.SafeAccessibilityReadiness.Ready -> "READY"
        }
        return com.clawmate.app.accessibility.SafeAccessibilityExecutorBoundary(
            readinessLabel = label,
            statusMessage = gate.statusMessage(),
            allowlist = com.clawmate.app.accessibility.SafeAccessibilityExecutorBoundary.SAFE_ACTION_ALLOWLIST,
            packageAllowlist = com.clawmate.app.accessibility.SafeAccessibilityDefaults.PACKAGE_ALLOWLIST,
        )
    }

    /**
     * Phase 22 — the demo boundary's *preflight dispatch*. Runs the same typed
     * [com.clawmate.app.accessibility.ControlledSafeAccessibilityDispatcher]
     * validation + gate as real execution, but with a **no-perform** seam so the
     * panel can never trigger a real system/navigation action and can never
     * report a fabricated SUCCESS. The result is always an honest
     * preflight/rejection outcome (NOT_CONFIGURED / BLOCKED_* / UNSUPPORTED, or a
     * clear READY-but-not-performed notice).
     */
    fun safeAccessibilityDemoDispatch(
        name: String,
        params: Map<String, Any?> = emptyMap(),
    ): com.clawmate.app.accessibility.AccessibilityExecutionOutcome {
        val d = _state.value.draft
        val serviceEnabled = accessibilityDetector.isServiceEnabledBySystem()
        val foreground =
            com.clawmate.app.accessibility.AccessibilityStateRegistry.currentForegroundPackage()
        val gate = com.clawmate.app.accessibility.SafeAccessibilityDefaults.gate(
            optedIn = d.accessibilityOptIn,
            serviceEnabled = serviceEnabled,
            currentForegroundPackage = foreground,
        )
        val noPerformExecutor =
            com.clawmate.app.accessibility.SafeAccessibilityActionExecutor { _ ->
                // The demo panel never performs a real action; this seam is only
                // reached when the gate is READY, and it truthfully declines.
                com.clawmate.app.accessibility.SafeActionPerformResult.Unsupported(
                    "演示边界只做预检，不会从设置面板触发真实系统动作；真实动作仅由实时辅助功能会话通过类型化执行器执行。",
                )
            }
        return com.clawmate.app.accessibility.ControlledSafeAccessibilityDispatcher(
            gate = gate,
            executor = noPerformExecutor,
        ).dispatch(name, params)
    }

    /**
     * Phase 20 — build the float/edge overlay boundary from live state. The
     * gate reflects the user's explicit [overlayOptIn] draft, the
     * *actually-observed* system `SYSTEM_ALERT_WINDOW` grant (via the injected
     * [overlayDetector], which reads the live overlay registry by default) and
     * the (always-absent today) safe renderer. It never renders an overlay and
     * never fabricates a surface.
     */
    fun overlayGate(): com.clawmate.app.overlay.OverlayGate {
        val d = _state.value.draft
        return com.clawmate.app.overlay.OverlayGate(
            optedIn = d.overlayOptIn,
            overlayPermissionGranted = overlayDetector.isOverlayPermissionGranted(),
            rendererConfigured = overlayDetector.isRendererConfigured(),
        )
    }

    /**
     * Phase 20 — the single descriptive "system-settings permission request
     * action" for the overlay boundary. It is pure metadata (the Android
     * `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` action + package + whether it
     * is currently actionable): it never launches anything by itself. The UI
     * uses [OverlayPermissionRequest.canRequest] to decide whether showing the
     * "open system settings to grant overlay" button is valid — i.e. only when
     * the user opted in but the system grant is still missing.
     */
    fun overlayPermissionRequest(): com.clawmate.app.overlay.OverlayPermissionRequest {
        val d = _state.value.draft
        return com.clawmate.app.overlay.OverlayPermissionRequest.forGate(
            baselineAction = com.clawmate.app.overlay.OverlayPermissionContract.ACTION_MANAGE_OVERLAY_PERMISSION,
            packageName = com.clawmate.app.overlay.OverlayPermissionContract.REQUEST_PACKAGE_NAME_PLACEHOLDER,
            overlayOptIn = d.overlayOptIn,
            overlayPermissionGranted = overlayDetector.isOverlayPermissionGranted(),
        )
    }

    /**
     * Phase 26 — the pure decision for the explicit "start overlay status
     * service" action. [OverlayServiceStartStep.START] only when the live gate
     * is [OverlayGateStatus.READY]; otherwise [OverlayServiceStartStep.KEEP_STOPPED]
     * with an honest reason. The UI must only dispatch a start from an explicit
     * click handler — never on launch or recomposition.
     */
    fun overlayServiceStartStep(): com.clawmate.app.overlay.OverlayServiceStartStep =
        com.clawmate.app.overlay.OverlayServiceStartPolicy.startStep(overlayGate())

    /**
     * Phase 26 — whether the overlay service must be stopped because the gate
     * is no longer READY while the service is still present (typically the user
     * turned opt-in off). Pure and testable.
     */
    fun overlayServiceStopRequired(): Boolean =
        com.clawmate.app.overlay.OverlayServiceStartPolicy.stopRequired(
            overlayGate(),
            com.clawmate.app.overlay.OverlayStateRegistry.isOverlayPresent(),
        )

    /**
     * Phase 26 — explicitly start the overlay status service. This only ever
     * runs when the UI has already confirmed the pure [overlayServiceStartStep]
     * is [OverlayServiceStartStep.START] (i.e. the gate is READY); if the gate
     * is not READY the controller is not invoked (the service itself would also
     * re-derive the gate and self-terminate). It mirrors the user's explicit
     * opt-in onto [OverlayCapabilityFlags] so the service's own re-derived gate
     * reflects the same opt-in, then dispatches the start.
     */
    fun startOverlayService() {
        if (overlayServiceStartStep() == com.clawmate.app.overlay.OverlayServiceStartStep.START) {
            com.clawmate.app.overlay.OverlayCapabilityFlags.overlayOptedIn = true
            overlayServiceController.startService()
        }
    }

    /**
     * Phase 26 — explicitly stop the overlay status service and clear the
     * mirrored opt-in flag (nothing renders without an explicit opt-in).
     */
    fun stopOverlayService() {
        com.clawmate.app.overlay.OverlayCapabilityFlags.overlayOptedIn = false
        overlayServiceController.stopService()
    }

    /**
     * Phase 26 — stops the overlay status service when opt-in is turned off so
     * it cannot keep rendering while not opted in (never silent; the overlay may
     * only live while READY).
     */
    fun stopOverlayOnOptOut() {
        if (com.clawmate.app.overlay.OverlayStateRegistry.isOverlayPresent()) {
            stopOverlayService()
        }
    }

    /** Validate the current WeChat summary draft into a buildable request (null when invalid). */
    fun wechatSummaryRequestOrNull(): com.clawmate.app.wechat.domain.WechatAutoSummaryRequest? {
        val d = _state.value.draft
        val request = com.clawmate.app.wechat.domain.WechatAutoSummaryRequest(
            mode = normalizeWechatAutoSummaryMode(d.wechatCollectionMode),
            days = d.wechatAutoSummaryDays,
            maxContacts = d.wechatAutoSummaryMaxContacts,
            targetContact = d.wechatCollectionTargetContact,
            maxHistorySwipes = d.wechatAutoSummaryMaxHistorySwipes,
            swipeSpeed = d.wechatAutoSummarySwipeSpeed,
            historySwipeRatio = d.wechatAutoSummaryHistorySwipeRatio,
            stableSwipes = d.wechatAutoSummaryStableSwipes,
            wait = d.wechatAutoSummaryWait,
        )
        return if (com.clawmate.app.wechat.domain.validateWechatAutoSummaryRequest(request).isEmpty()) request else null
    }
}

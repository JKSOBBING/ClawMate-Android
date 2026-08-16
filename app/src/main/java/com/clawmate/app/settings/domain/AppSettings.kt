package com.clawmate.app.settings.domain

/**
 * A typed, immutable snapshot of every setting editable from the Settings
 * center. This is the in-memory working copy the UI edits and the ViewModel
 * persists through the repository (which stores each field under a
 * [SettingsKeys] key, normalized).
 *
 * Defaults here are empty/safe and deterministic — matching the requirement
 * not to copy real HarmonyOS default values. Real values come only from the
 * git-ignored `local_config.properties` seed (see `config/EndpointConfig.kt`).
 */
data class AppSettings(
    // Privacy — 个人与隐私
    val agentLocalConfirm: AutoConfirmMode = AutoConfirmMode.Ask,
    val agentCloudConfirm: AutoConfirmMode = AutoConfirmMode.Ask,
    val avatarImageBaseUrl: String = "",
    val avatarImageApiKey: String = "",
    val avatarTextToImageModel: String = "",
    val avatarImageSize: String = "",
    // Data collection — 数据采集 (gallery/OCR/embedding/map)
    val galleryApiBaseUrl: String = "",
    val galleryApiKey: String = "",
    val galleryModel: String = "",
    val gallerySkipOcr: Boolean = false,
    val galleryUseOcrKeywordFilter: Boolean = true,
    val galleryParallelFieldRequests: Boolean = true,
    val galleryOcrConcurrency: Int = 3,
    val galleryModelConcurrency: Int = 2,
    val galleryEmbeddingConcurrency: Int = 2,
    val galleryEmbeddingMode: EmbeddingMode = EmbeddingMode.HuaweiLocal,
    val galleryEmbeddingBaseUrl: String = "",
    val galleryEmbeddingApiKey: String = "",
    val galleryEmbeddingModel: String = "",
    val galleryImageScale: Double = 0.9,
    val galleryAmapWebKey: String = "",
    // Model & agents — 模型与智能体
    val cloudPlannerBaseUrl: String = "",
    val cloudPlannerModel: String = "",
    val cloudPlannerApiKey: String = "",
    val cloudDeciderBaseUrl: String = "",
    val cloudDeciderApiKey: String = "",
    val cloudDeciderModel: String = "",
    val hdcServerUrl: String = "",
    val wirelessHdcTarget: String = "",
    val hdcConnected: Boolean = false,
    val chatCompressionThresholdPercent: Int = 85,
    val chatRecentRounds: Int = 8,
    // Task & automation — 任务与自动化
    val workflowAutomationEnabled: Boolean = true,
    val workflowHardwareStepsEnabled: Boolean = false,
    val pcServerUrl: String = "",
    val pcConnected: Boolean = false,
    // Storage / about — 存储与关于
    val cleanupAckSafe: Boolean = false,
) {

    /** A quick, independent connectivity summary used by the Model/Agents UI. */
    val cloudPlannerConfigured: Boolean
        get() = SettingsNormalizers.normalizeUrl(cloudPlannerBaseUrl).isNotEmpty() &&
            cloudPlannerApiKey.isNotBlank()

    val cloudDeciderConfigured: Boolean
        get() = SettingsNormalizers.normalizeUrl(cloudDeciderBaseUrl).isNotEmpty() &&
            cloudDeciderApiKey.isNotBlank()

    val hdcBridgeConfigured: Boolean
        get() = SettingsNormalizers.normalizeUrl(hdcServerUrl).isNotEmpty()

    val galleryVisionConfigured: Boolean
        get() = SettingsNormalizers.normalizeUrl(galleryApiBaseUrl).isNotEmpty() &&
            galleryApiKey.isNotBlank()

    companion object {
        /** The safe, empty default snapshot. */
        fun defaults(): AppSettings = AppSettings()
    }
}

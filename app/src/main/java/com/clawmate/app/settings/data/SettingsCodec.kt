package com.clawmate.app.settings.data

import com.clawmate.app.settings.domain.AppSettings
import com.clawmate.app.settings.domain.AutoConfirmMode
import com.clawmate.app.settings.domain.EmbeddingMode
import com.clawmate.app.settings.domain.SensitiveValueUtils
import com.clawmate.app.settings.domain.SettingsKeys
import com.clawmate.app.settings.domain.SettingsNormalizers
import com.clawmate.app.wechat.domain.normalizeWechatAutoSummaryHistorySwipeRatio
import com.clawmate.app.wechat.domain.normalizeWechatAutoSummaryMaxContacts
import com.clawmate.app.wechat.domain.normalizeWechatAutoSummaryMaxHistorySwipes
import com.clawmate.app.wechat.domain.normalizeWechatAutoSummaryStableSwipes
import com.clawmate.app.wechat.domain.normalizeWechatAutoSummarySwipeSpeed
import com.clawmate.app.wechat.domain.normalizeWechatAutoSummaryWait
import com.clawmate.app.wechat.domain.normalizeWechatAutoSummaryDays
import com.clawmate.app.wechat.domain.normalizeWechatAutoSummaryMode

/**
 * Translates a typed [AppSettings] snapshot to/from a flat `Map<String,String>`
 * of [SettingsKeys] and back. The flat form is what the DataStore Preferences
 * store persists (each value a `stringPreferencesKey`).
 *
 * Round-trip guarantees:
 *  - decoding always succeeds (unknown / corrupt / masked values degrade via
 *    the normalizers to a safe value — total function);
 *  - encoding a value that is still a masked display string is *dropped* (its
 *    key defaults to the safe empty default) so a mask marker is never
 *    persisted as a literal secret; see [sanitizeForWrite].
 *
 * Connectivity flags (HDC/PC connected, cleanup ack) are intentionally NOT in
 * the encoded map — they are derived at runtime and never assumed configured.
 */
object SettingsCodec {

    // ----------------------------------------------------------------- encode

    /** Encode a snapshot to a flat key->string map (values already normalized). */
    fun encode(s: AppSettings): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out[SettingsKeys.AGENT_LOCAL_CONFIRM] = s.agentLocalConfirm.storageKey
        out[SettingsKeys.AGENT_CLOUD_CONFIRM] = s.agentCloudConfirm.storageKey
        out[SettingsKeys.AVATAR_IMAGE_BASE_URL] = SettingsNormalizers.normalizeUrl(s.avatarImageBaseUrl)
        out[SettingsKeys.AVATAR_IMAGE_API_KEY] = s.avatarImageApiKey.trim()
        out[SettingsKeys.AVATAR_TEXT_TO_IMAGE_MODEL] = SettingsNormalizers.normalizeModel(s.avatarTextToImageModel)
        out[SettingsKeys.AVATAR_IMAGE_SIZE] = SettingsNormalizers.normalizeText(s.avatarImageSize)

        out[SettingsKeys.GALLERY_API_BASE_URL] = SettingsNormalizers.normalizeUrl(s.galleryApiBaseUrl)
        out[SettingsKeys.GALLERY_API_KEY] = s.galleryApiKey.trim()
        out[SettingsKeys.GALLERY_MODEL] = SettingsNormalizers.normalizeModel(s.galleryModel)
        out[SettingsKeys.GALLERY_SKIP_OCR] = s.gallerySkipOcr.toString()
        out[SettingsKeys.GALLERY_USE_OCR_KEYWORD_FILTER] = s.galleryUseOcrKeywordFilter.toString()
        out[SettingsKeys.GALLERY_PARALLEL_FIELD_REQUESTS] = s.galleryParallelFieldRequests.toString()
        out[SettingsKeys.GALLERY_OCR_CONCURRENCY] = s.galleryOcrConcurrency.toString()
        out[SettingsKeys.GALLERY_MODEL_CONCURRENCY] = s.galleryModelConcurrency.toString()
        out[SettingsKeys.GALLERY_EMBEDDING_CONCURRENCY] = s.galleryEmbeddingConcurrency.toString()
        out[SettingsKeys.GALLERY_EMBEDDING_MODE] = s.galleryEmbeddingMode.storageKey
        out[SettingsKeys.GALLERY_EMBEDDING_BASE_URL] = SettingsNormalizers.normalizeUrl(s.galleryEmbeddingBaseUrl)
        out[SettingsKeys.GALLERY_EMBEDDING_API_KEY] = s.galleryEmbeddingApiKey.trim()
        out[SettingsKeys.GALLERY_EMBEDDING_MODEL] = SettingsNormalizers.normalizeModel(s.galleryEmbeddingModel)
        out[SettingsKeys.GALLERY_IMAGE_SCALE] = s.galleryImageScale.toString()
        out[SettingsKeys.GALLERY_AMAP_WEB_KEY] = s.galleryAmapWebKey.trim()

        out[SettingsKeys.CLOUD_PLANNER_BASE_URL] = SettingsNormalizers.normalizeUrl(s.cloudPlannerBaseUrl)
        out[SettingsKeys.CLOUD_PLANNER_MODEL] = SettingsNormalizers.normalizeModel(s.cloudPlannerModel)
        out[SettingsKeys.CLOUD_PLANNER_API_KEY] = s.cloudPlannerApiKey.trim()
        out[SettingsKeys.CLOUD_DECIDER_BASE_URL] = SettingsNormalizers.normalizeUrl(s.cloudDeciderBaseUrl)
        out[SettingsKeys.CLOUD_DECIDER_API_KEY] = s.cloudDeciderApiKey.trim()
        out[SettingsKeys.CLOUD_DECIDER_MODEL] = SettingsNormalizers.normalizeModel(s.cloudDeciderModel)
        out[SettingsKeys.HDC_SERVER_URL] = SettingsNormalizers.normalizeUrl(s.hdcServerUrl)
        out[SettingsKeys.WIRELESS_HDC_TARGET] = SettingsNormalizers.normalizeText(s.wirelessHdcTarget)

        out[SettingsKeys.CHAT_COMPRESSION_THRESHOLD_PERCENT] = s.chatCompressionThresholdPercent.toString()
        out[SettingsKeys.CHAT_RECENT_ROUNDS] = s.chatRecentRounds.toString()

        out[SettingsKeys.WORKFLOW_AUTOMATION_ENABLED] = s.workflowAutomationEnabled.toString()
        out[SettingsKeys.WORKFLOW_HARDWARE_STEPS_ENABLED] = s.workflowHardwareStepsEnabled.toString()
        out[SettingsKeys.PC_SERVER_URL] = SettingsNormalizers.normalizeUrl(s.pcServerUrl)

        // WeChat auto-summary (Phase 13) — normalized before persistence.
        out[SettingsKeys.WECHAT_AUTO_SUMMARY_ENABLED] = s.wechatAutoSummaryEnabled.toString()
        out[SettingsKeys.WECHAT_AUTO_SUMMARY_DAYS] = s.wechatAutoSummaryDays.toString()
        out[SettingsKeys.WECHAT_AUTO_SUMMARY_MAX_CONTACTS] = s.wechatAutoSummaryMaxContacts.toString()
        out[SettingsKeys.WECHAT_AUTO_SUMMARY_SWIPE_SPEED] = s.wechatAutoSummarySwipeSpeed.toString()
        out[SettingsKeys.WECHAT_AUTO_SUMMARY_HISTORY_SWIPE_RATIO] = s.wechatAutoSummaryHistorySwipeRatio.toString()
        out[SettingsKeys.WECHAT_AUTO_SUMMARY_STABLE_SWIPES] = s.wechatAutoSummaryStableSwipes.toString()
        out[SettingsKeys.WECHAT_AUTO_SUMMARY_MAX_HISTORY_SWIPES] = s.wechatAutoSummaryMaxHistorySwipes.toString()
        out[SettingsKeys.WECHAT_AUTO_SUMMARY_WAIT] = s.wechatAutoSummaryWait.toString()
        out[SettingsKeys.WECHAT_COLLECTION_MODE] = normalizeWechatAutoSummaryMode(s.wechatCollectionMode)
        out[SettingsKeys.WECHAT_COLLECTION_TARGET_CONTACT] = s.wechatCollectionTargetContact.trim()
        return out
    }

    /**
     * Strip any persisted value that still carries a mask marker, replacing it
     * with the safe empty default for that key. This is the write-guard that
     * guarantees a masked display string is never persisted as a literal secret.
     */
    fun sanitizeForWrite(flat: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap(flat)
        SettingsKeys.SENSITIVE_KEYS.forEach { key ->
            val v = out[key]?.trim().orEmpty()
            if (SensitiveValueUtils.isMaskedSensitiveValue(v)) {
                out.remove(key)
            }
        }
        return out
    }

    // ----------------------------------------------------------------- decode

    /** Decode a flat key->string map into a typed snapshot (total function). */
    fun decode(flat: Map<String, String>): AppSettings {
        fun s(key: String): String = flat[key].orEmpty()
        return AppSettings(
            agentLocalConfirm = modeFromKey(s(SettingsKeys.AGENT_LOCAL_CONFIRM), AutoConfirmMode.Ask),
            agentCloudConfirm = modeFromKey(s(SettingsKeys.AGENT_CLOUD_CONFIRM), AutoConfirmMode.Ask),
            avatarImageBaseUrl = SettingsNormalizers.normalizeUrl(s(SettingsKeys.AVATAR_IMAGE_BASE_URL)),
            avatarImageApiKey = s(SettingsKeys.AVATAR_IMAGE_API_KEY).trim(),
            avatarTextToImageModel = SettingsNormalizers.normalizeModel(s(SettingsKeys.AVATAR_TEXT_TO_IMAGE_MODEL)),
            avatarImageSize = SettingsNormalizers.normalizeText(s(SettingsKeys.AVATAR_IMAGE_SIZE)),
            galleryApiBaseUrl = SettingsNormalizers.normalizeUrl(s(SettingsKeys.GALLERY_API_BASE_URL)),
            galleryApiKey = s(SettingsKeys.GALLERY_API_KEY).trim(),
            galleryModel = SettingsNormalizers.normalizeModel(s(SettingsKeys.GALLERY_MODEL)),
            gallerySkipOcr = SettingsNormalizers.boolean(s(SettingsKeys.GALLERY_SKIP_OCR), false),
            galleryUseOcrKeywordFilter =
                SettingsNormalizers.boolean(s(SettingsKeys.GALLERY_USE_OCR_KEYWORD_FILTER), true),
            galleryParallelFieldRequests =
                SettingsNormalizers.boolean(s(SettingsKeys.GALLERY_PARALLEL_FIELD_REQUESTS), true),
            galleryOcrConcurrency = SettingsNormalizers.concurrency(s(SettingsKeys.GALLERY_OCR_CONCURRENCY), 3),
            galleryModelConcurrency = SettingsNormalizers.concurrency(s(SettingsKeys.GALLERY_MODEL_CONCURRENCY), 2),
            galleryEmbeddingConcurrency =
                SettingsNormalizers.concurrency(s(SettingsKeys.GALLERY_EMBEDDING_CONCURRENCY), 2),
            galleryEmbeddingMode = SettingsNormalizers.embeddingMode(s(SettingsKeys.GALLERY_EMBEDDING_MODE)),
            galleryEmbeddingBaseUrl = SettingsNormalizers.normalizeUrl(s(SettingsKeys.GALLERY_EMBEDDING_BASE_URL)),
            galleryEmbeddingApiKey = s(SettingsKeys.GALLERY_EMBEDDING_API_KEY).trim(),
            galleryEmbeddingModel = SettingsNormalizers.normalizeModel(s(SettingsKeys.GALLERY_EMBEDDING_MODEL)),
            galleryImageScale = SettingsNormalizers.normalizedDouble(s(SettingsKeys.GALLERY_IMAGE_SCALE), 0.9, 0.1, 1.0),
            galleryAmapWebKey = s(SettingsKeys.GALLERY_AMAP_WEB_KEY).trim(),
            cloudPlannerBaseUrl = SettingsNormalizers.normalizeUrl(s(SettingsKeys.CLOUD_PLANNER_BASE_URL)),
            cloudPlannerModel = SettingsNormalizers.normalizeModel(s(SettingsKeys.CLOUD_PLANNER_MODEL)),
            cloudPlannerApiKey = s(SettingsKeys.CLOUD_PLANNER_API_KEY).trim(),
            cloudDeciderBaseUrl = SettingsNormalizers.normalizeUrl(s(SettingsKeys.CLOUD_DECIDER_BASE_URL)),
            cloudDeciderApiKey = s(SettingsKeys.CLOUD_DECIDER_API_KEY).trim(),
            cloudDeciderModel = SettingsNormalizers.normalizeModel(s(SettingsKeys.CLOUD_DECIDER_MODEL)),
            hdcServerUrl = SettingsNormalizers.normalizeUrl(s(SettingsKeys.HDC_SERVER_URL)),
            wirelessHdcTarget = SettingsNormalizers.normalizeText(s(SettingsKeys.WIRELESS_HDC_TARGET)),
            chatCompressionThresholdPercent = SettingsNormalizers.compressionThresholdPercent(
                s(SettingsKeys.CHAT_COMPRESSION_THRESHOLD_PERCENT)),
            chatRecentRounds = SettingsNormalizers.recentRounds(s(SettingsKeys.CHAT_RECENT_ROUNDS)),
            workflowAutomationEnabled = SettingsNormalizers.boolean(s(SettingsKeys.WORKFLOW_AUTOMATION_ENABLED), true),
            workflowHardwareStepsEnabled =
                SettingsNormalizers.boolean(s(SettingsKeys.WORKFLOW_HARDWARE_STEPS_ENABLED), false),
            pcServerUrl = SettingsNormalizers.normalizeUrl(s(SettingsKeys.PC_SERVER_URL)),
            wechatAutoSummaryEnabled =
                SettingsNormalizers.boolean(s(SettingsKeys.WECHAT_AUTO_SUMMARY_ENABLED), false),
            wechatAutoSummaryDays = normalizeWechatAutoSummaryDays(s(SettingsKeys.WECHAT_AUTO_SUMMARY_DAYS)),
            wechatAutoSummaryMaxContacts =
                normalizeWechatAutoSummaryMaxContacts(s(SettingsKeys.WECHAT_AUTO_SUMMARY_MAX_CONTACTS)),
            wechatAutoSummarySwipeSpeed =
                normalizeWechatAutoSummarySwipeSpeed(s(SettingsKeys.WECHAT_AUTO_SUMMARY_SWIPE_SPEED)),
            wechatAutoSummaryHistorySwipeRatio =
                normalizeWechatAutoSummaryHistorySwipeRatio(s(SettingsKeys.WECHAT_AUTO_SUMMARY_HISTORY_SWIPE_RATIO)),
            wechatAutoSummaryStableSwipes =
                normalizeWechatAutoSummaryStableSwipes(s(SettingsKeys.WECHAT_AUTO_SUMMARY_STABLE_SWIPES)),
            wechatAutoSummaryMaxHistorySwipes =
                normalizeWechatAutoSummaryMaxHistorySwipes(s(SettingsKeys.WECHAT_AUTO_SUMMARY_MAX_HISTORY_SWIPES)),
            wechatAutoSummaryWait = normalizeWechatAutoSummaryWait(s(SettingsKeys.WECHAT_AUTO_SUMMARY_WAIT)),
            wechatCollectionMode = normalizeWechatAutoSummaryMode(s(SettingsKeys.WECHAT_COLLECTION_MODE)),
            wechatCollectionTargetContact =
                SettingsNormalizers.normalizeText(s(SettingsKeys.WECHAT_COLLECTION_TARGET_CONTACT)),
        )
    }

    private fun modeFromKey(key: String, fallback: AutoConfirmMode): AutoConfirmMode =
        AutoConfirmMode.entries.firstOrNull { it.storageKey == key.trim() } ?: fallback
}

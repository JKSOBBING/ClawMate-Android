package com.clawmate.app.settings.domain

/**
 * Typed settings keys backed by a deterministic DataStore Preferences store.
 *
 * Keys and their normalization mirror the HarmonyOS source inventory in
 * `pages/Index.ets:357+` and `SettingsCenterPage.ets` `@StorageLink` fields,
 * but **no real default values are copied** — all committed defaults here are
 * empty / safe placeholders. Real endpoint values, when present, come only
 * from the git-ignored `raw/local_config.properties` (see
 * `config/EndpointConfig.kt`).
 *
 * Values are grouped so sensitive fields are flagged for masking behaviour and
 * so tests can assert the full key set deterministically.
 */
object SettingsKeys {

    // ------------------------------------------------------------- privacy
    /** Local agent action confirmation policy (Harmony: `autoConfirm`). */
    const val AGENT_LOCAL_CONFIRM = "agent_local_confirm"

    /** Cloud agent action confirmation policy (Harmony: `cloudAutoConfirm`). */
    const val AGENT_CLOUD_CONFIRM = "agent_cloud_confirm"

    /** Digital-avatar image base URL (Harmony: `avatarImageBaseUrl`). */
    const val AVATAR_IMAGE_BASE_URL = "avatar_image_base_url"

    /** Digital-avatar image API key (Harmony: `avatarImageApiKey`). SENSITIVE. */
    const val AVATAR_IMAGE_API_KEY = "avatar_image_api_key"

    /** Text-to-image / image-to-image model (Harmony: `avatarTextToImageModel`). */
    const val AVATAR_TEXT_TO_IMAGE_MODEL = "avatar_text_to_image_model"

    /** Avatar image size (Harmony: `avatarImageSize`). */
    const val AVATAR_IMAGE_SIZE = "avatar_image_size"

    // ------------------------------------------------------------ collection
    /** Gallery cloud-vision API base URL (default-value migration; left empty). */
    const val GALLERY_API_BASE_URL = "gallery_api_base_url"

    /** Gallery cloud-vision API key (Harmony: `galleryApiKey`). SENSITIVE. */
    const val GALLERY_API_KEY = "gallery_api_key"

    /** Gallery vision model (empty -> "not configured"). */
    const val GALLERY_MODEL = "gallery_model"

    /** Whether to skip on-device OCR. */
    const val GALLERY_SKIP_OCR = "gallery_skip_ocr"

    /** Whether to use OCR keyword pre-filtering. */
    const val GALLERY_USE_OCR_KEYWORD_FILTER = "gallery_use_ocr_keyword_filter"

    /** Parallel field-request per image flag. */
    const val GALLERY_PARALLEL_FIELD_REQUESTS = "gallery_parallel_field_requests"

    /** OCR / model / embedding concurrency values. */
    const val GALLERY_OCR_CONCURRENCY = "gallery_ocr_concurrency"
    const val GALLERY_MODEL_CONCURRENCY = "gallery_model_concurrency"
    const val GALLERY_EMBEDDING_CONCURRENCY = "gallery_embedding_concurrency"

    /** Embedding mode (`remote` | `huawei_local`). */
    const val GALLERY_EMBEDDING_MODE = "gallery_embedding_mode"

    /** Embedding service base URL (default-value migration; left empty). */
    const val GALLERY_EMBEDDING_BASE_URL = "gallery_embedding_base_url"

    /** Embedding API key. SENSITIVE. */
    const val GALLERY_EMBEDDING_API_KEY = "gallery_embedding_api_key"

    /** Embedding model name. */
    const val GALLERY_EMBEDDING_MODEL = "gallery_embedding_model"

    /** Phase 21 — explicit opt-in to the optional cloud AI month summary.
     * Off by default; on alone is not enough (the OpenAI-compatible Planner
     * endpoint/model/key must also be configured). */
    const val GALLERY_AI_SUMMARY_ENABLED = "gallery_ai_summary_enabled"

    /** Image analysis scale (double 0.0..1.0). */
    const val GALLERY_IMAGE_SCALE = "gallery_image_scale"

    /** AMap web-service key (reverse geocoding). SENSITIVE. */
    const val GALLERY_AMAP_WEB_KEY = "gallery_amap_web_key"

    // ------------------------------------------------------ model / agents
    /** Cloud planner base URL. */
    const val CLOUD_PLANNER_BASE_URL = "cloud_planner_base_url"

    /** Cloud planner model (empty -> "don't send model"). */
    const val CLOUD_PLANNER_MODEL = "cloud_planner_model"

    /** Cloud planner API key. SENSITIVE. */
    const val CLOUD_PLANNER_API_KEY = "cloud_planner_api_key"

    /** Cloud decider base URL. */
    const val CLOUD_DECIDER_BASE_URL = "cloud_decider_base_url"

    /** Cloud decider API key. SENSITIVE. */
    const val CLOUD_DECIDER_API_KEY = "cloud_decider_api_key"

    /** Cloud decider model (empty -> "don't send model"). */
    const val CLOUD_DECIDER_MODEL = "cloud_decider_model"

    /** HDC bridge server URL (Harmony: `hdcServerUrl`). */
    const val HDC_SERVER_URL = "hdc_server_url"

    /** Wireless HDC target `ip:port` (Harmony: `wirelessHdcTarget`). */
    const val WIRELESS_HDC_TARGET = "wireless_hdc_target"

    /** HDC bridge connection state (keep in sync with the HDC capability). */
    const val HDC_CONNECTED = "hdc_connected"

    /** Chat context compression threshold percent. */
    const val CHAT_COMPRESSION_THRESHOLD_PERCENT = "chat_compression_threshold_percent"

    /** Chat context recent rounds kept. */
    const val CHAT_RECENT_ROUNDS = "chat_recent_rounds"

    // ------------------------------------------------------------- workflow
    /** Whether agent workflow automation is enabled. */
    const val WORKFLOW_AUTOMATION_ENABLED = "workflow_automation_enabled"

    /** Whether to surface HDC/device-control workflow steps. */
    const val WORKFLOW_HARDWARE_STEPS_ENABLED = "workflow_hardware_steps_enabled"

    /** Phase 19 — user opt-in to the AutoAgent controlled-accessibility boundary. */
    const val ACCESSIBILITY_OPT_IN = "accessibility_autoagent_opt_in"

    /** Phase 20 — user opt-in to the float/edge overlay boundary. */
    const val OVERLAY_OPT_IN = "overlay_float_opt_in"

    /** PC control backend URL (Harmony: `pcServerUrl`). */
    const val PC_SERVER_URL = "pc_server_url"

    /** PC backend connection state (display only; never assumed true). */
    const val PC_CONNECTED = "pc_connected"

    // ------------------------------------------------------- wechat summary
    /** Whether WeChat auto-summary is enabled (Harmony: `wechatAutoSummaryEnabled`). */
    const val WECHAT_AUTO_SUMMARY_ENABLED = "wechat_auto_summary_enabled"

    /** Summary days window 1..90 (Harmony: `wechatAutoSummaryDefaultDays`). */
    const val WECHAT_AUTO_SUMMARY_DAYS = "wechat_auto_summary_days"

    /** Max contacts per summary run 1..50 (Harmony: `wechatAutoSummaryDefaultMaxContacts`). */
    const val WECHAT_AUTO_SUMMARY_MAX_CONTACTS = "wechat_auto_summary_max_contacts"

    /** Swipe speed 0..20000 (Harmony: `wechatAutoSummarySwipeSpeed`). */
    const val WECHAT_AUTO_SUMMARY_SWIPE_SPEED = "wechat_auto_summary_swipe_speed"

    /** History swipe ratio 0.1..0.95 (Harmony: `wechatAutoSummaryHistorySwipeRatio`). */
    const val WECHAT_AUTO_SUMMARY_HISTORY_SWIPE_RATIO = "wechat_auto_summary_history_swipe_ratio"

    /** Stable swipes 1..10 (Harmony: `wechatAutoSummaryStableSwipes`). */
    const val WECHAT_AUTO_SUMMARY_STABLE_SWIPES = "wechat_auto_summary_stable_swipes"

    /** Max history swipes 1..300 (Harmony: `wechatAutoSummaryMaxHistorySwipes`). */
    const val WECHAT_AUTO_SUMMARY_MAX_HISTORY_SWIPES = "wechat_auto_summary_max_history_swipes"

    /** Wait seconds 0..10 (Harmony: `wechatAutoSummaryWait`). */
    const val WECHAT_AUTO_SUMMARY_WAIT = "wechat_auto_summary_wait"

    /** Collection mode: `recent_contacts` | `target_contact`. */
    const val WECHAT_COLLECTION_MODE = "wechat_collection_mode"

    /** Target-contact name for target mode (Harmony: `..._TargetContactInput`). */
    const val WECHAT_COLLECTION_TARGET_CONTACT = "wechat_collection_target_contact"

    // ------------------------------------------------------------- storage
    /** Whether "destructive debug cleanup" has ever been acknowledged. */
    const val CLEANUP_ACK_SAFE = "cleanup_ack_safe"

    // ------------------------------------------------------ versioned schema
    /** Current default-values snapshot (so default migrations can be additive). */
    const val DEFAULTS_SCHEMA_VERSION = "defaults_schema_version"

    /** The set of all keys, for exhaustive iteration in tests / export. */
    val ALL_KEYS: List<String> = listOf(
        AGENT_LOCAL_CONFIRM, AGENT_CLOUD_CONFIRM,
        AVATAR_IMAGE_BASE_URL, AVATAR_IMAGE_API_KEY, AVATAR_TEXT_TO_IMAGE_MODEL, AVATAR_IMAGE_SIZE,
        GALLERY_API_BASE_URL, GALLERY_API_KEY, GALLERY_MODEL,
        GALLERY_SKIP_OCR, GALLERY_USE_OCR_KEYWORD_FILTER, GALLERY_PARALLEL_FIELD_REQUESTS,
        GALLERY_OCR_CONCURRENCY, GALLERY_MODEL_CONCURRENCY, GALLERY_EMBEDDING_CONCURRENCY,
        GALLERY_EMBEDDING_MODE, GALLERY_EMBEDDING_BASE_URL, GALLERY_EMBEDDING_API_KEY, GALLERY_EMBEDDING_MODEL,
        GALLERY_AI_SUMMARY_ENABLED, GALLERY_IMAGE_SCALE, GALLERY_AMAP_WEB_KEY,
        CLOUD_PLANNER_BASE_URL, CLOUD_PLANNER_MODEL, CLOUD_PLANNER_API_KEY,
        CLOUD_DECIDER_BASE_URL, CLOUD_DECIDER_API_KEY, CLOUD_DECIDER_MODEL,
        HDC_SERVER_URL, WIRELESS_HDC_TARGET, HDC_CONNECTED,
        CHAT_COMPRESSION_THRESHOLD_PERCENT, CHAT_RECENT_ROUNDS,
        WORKFLOW_AUTOMATION_ENABLED, WORKFLOW_HARDWARE_STEPS_ENABLED, ACCESSIBILITY_OPT_IN,
        OVERLAY_OPT_IN, PC_SERVER_URL, PC_CONNECTED,
        WECHAT_AUTO_SUMMARY_ENABLED, WECHAT_AUTO_SUMMARY_DAYS, WECHAT_AUTO_SUMMARY_MAX_CONTACTS,
        WECHAT_AUTO_SUMMARY_SWIPE_SPEED, WECHAT_AUTO_SUMMARY_HISTORY_SWIPE_RATIO,
        WECHAT_AUTO_SUMMARY_STABLE_SWIPES, WECHAT_AUTO_SUMMARY_MAX_HISTORY_SWIPES, WECHAT_AUTO_SUMMARY_WAIT,
        WECHAT_COLLECTION_MODE, WECHAT_COLLECTION_TARGET_CONTACT,
        CLEANUP_ACK_SAFE, DEFAULTS_SCHEMA_VERSION,
    )

    /** The subset of keys that hold secrets and must be masked in UI / export. */
    val SENSITIVE_KEYS: Set<String> = setOf(
        AVATAR_IMAGE_API_KEY,
        GALLERY_API_KEY,
        GALLERY_EMBEDDING_API_KEY,
        GALLERY_AMAP_WEB_KEY,
        CLOUD_PLANNER_API_KEY,
        CLOUD_DECIDER_API_KEY,
    )
}

package com.clawmate.shared

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.net.URI

/** Read the Compose stores in place. Only preferences with an equivalent shared-host key cross the bridge. */
internal object LegacySettingsImport {
    private val names = mapOf(
        "chat_compression_threshold_percent" to "chatContextCompressionThresholdPercent",
        "chat_recent_rounds" to "chatContextRecentRounds",
        "wechat_auto_summary_days" to "wechatAutoSummaryDefaultDays",
        "wechat_auto_summary_max_contacts" to "wechatAutoSummaryDefaultMaxContacts"
    )
    private val matchingKeys = setOf(
        "avatar_image_base_url", "avatar_image_api_key", "avatar_text_to_image_model", "avatar_image_size",
        "cloud_planner_base_url", "cloud_planner_api_key", "cloud_planner_model",
        "cloud_decider_base_url", "cloud_decider_api_key", "cloud_decider_model",
        "gallery_api_base_url", "gallery_api_key", "gallery_model", "gallery_skip_ocr", "gallery_use_ocr_keyword_filter",
        "gallery_parallel_field_requests", "gallery_ocr_concurrency", "gallery_model_concurrency", "gallery_embedding_concurrency",
        "gallery_embedding_mode", "gallery_embedding_base_url", "gallery_embedding_api_key", "gallery_embedding_model",
        "gallery_image_scale", "gallery_amap_web_key", "wechat_auto_summary_enabled", "wechat_auto_summary_swipe_speed",
        "wechat_auto_summary_history_swipe_ratio", "wechat_auto_summary_stable_swipes", "wechat_auto_summary_max_history_swipes",
        "wechat_auto_summary_wait"
    )
    private val serviceOrigins = mapOf(
        "avatar_image_api_key" to "avatar_image_base_url", "cloud_planner_api_key" to "cloud_planner_base_url",
        "cloud_decider_api_key" to "cloud_decider_base_url", "gallery_api_key" to "gallery_api_base_url",
        "gallery_embedding_api_key" to "gallery_embedding_base_url"
    )

    @Synchronized fun read(files: File): JSONObject = runBlocking(Dispatchers.IO) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            suspend fun values(name: String): Map<String, String> {
                val file = File(files, "datastore/$name.preferences_pb")
                if (!file.isFile) return emptyMap()
                val store = PreferenceDataStoreFactory.create(scope = scope) { file }
                return store.data.first().asMap().entries.mapNotNull { (key, value) ->
                    (value as? String)?.let { key.name to it }
                }.toMap()
            }
            val prefs = values("clawmate_settings")
            val out = JSONObject()
            for ((key, value) in prefs) {
                if (key in serviceOrigins) {
                    // A key without its original HTTPS destination must never attach to a new service default.
                    val uri = runCatching { URI(prefs[serviceOrigins.getValue(key)].orEmpty()) }.getOrNull()
                    if (uri?.scheme != "https" || uri.host.isNullOrEmpty() || uri.userInfo != null) continue
                }
                val name = when (key) {
                    in names -> names.getValue(key)
                    in matchingKeys -> key.split('_').let { words ->
                        words.first() + words.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
                    }
                    else -> continue
                }
                out.put(name, value)
            }
            for ((old, new) in mapOf("agent_local_confirm" to "autoConfirm", "agent_cloud_confirm" to "cloudAutoConfirm")) {
                prefs[old]?.let { out.put(new, (it == "auto_approve").toString()) }
            }
            values("clawmate_home")["firstLaunchOnboardingVersion"]?.let { out.put("firstLaunchOnboardingVersion", it) }
            out
        } finally {
            scope.cancel()
        }
    }
}

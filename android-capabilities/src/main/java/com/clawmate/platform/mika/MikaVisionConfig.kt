package com.clawmate.platform.mika

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.OpenAiCompatModel
import com.wangzan101.mika.core.vision.VisionService
import android.content.Context
import android.content.pm.ApplicationInfo
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * 看图模型配置（用户在「云模型」里填的 Decider 视觉模型）。
 *
 * 手机任务有它时，`phone_describe` / `phone_find_visually` 才可用；没有配置就保持隐藏，
 * 由界面提示先配置，而不是让主模型去猜屏上的东西。
 */
data class MikaVisionConfig(
    val baseUrl: String,
    val modelName: String,
    val key: String,
) {
    companion object {
        /** A private debug deployment may nominate exactly one cleartext Decider origin. */
        fun debugOrigin(context: Context): String {
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return ""
            val id = context.resources.getIdentifier("workflow_decider_http_origin", "string", context.packageName)
            return if (id == 0) "" else context.getString(id)
        }

        fun permitsDebugHttp(url: String, origin: String): Boolean {
            val endpoint = url.trim().toHttpUrlOrNull() ?: return false
            val allowed = origin.toHttpUrlOrNull() ?: return false
            return endpoint.scheme == "http" && allowed.scheme == "http" && endpoint.host == allowed.host &&
                endpoint.port == allowed.port && endpoint.username.isEmpty() && endpoint.password.isEmpty() &&
                endpoint.query == null && endpoint.fragment == null &&
                endpoint.encodedPath.trimEnd('/') in setOf("", "/v1", "/v1/chat/completions")
        }

        /** 地址必须能按 Mika 的规则规范化；模型名留空使用服务默认，最长 180 字；Key 可为空但不得超长。 */
        fun parse(url: String, model: String, key: String, debugHttpOrigin: String = ""): MikaVisionConfig? {
            val name = model.trim()
            if (name.length > 180 || key.length > 4096) return null
            val base = MikaEndpoints.parse(url)?.second ?: if (permitsDebugHttp(url, debugHttpOrigin)) {
                url.trim().toHttpUrlOrNull()!!.newBuilder().encodedPath("/v1").build().toString()
            } else return null
            return MikaVisionConfig(base, name, key.trim())
        }
    }
}

/** 用看图模型配置造一个 Mika 的 [VisionService]；图片处理走本机的 [AndroidImageOps]。 */
internal fun mikaVisionService(config: MikaVisionConfig, http: OkHttpClient) =
    VisionService(mikaVisionModel(config, http), AndroidImageOps)

internal fun mikaVisionModel(config: MikaVisionConfig, http: OkHttpClient, guiAction: Boolean = false) = OpenAiCompatModel(
        config.baseUrl,
        config.key.ifBlank { null },
        config.modelName,
        Capabilities(toolCalls = false, vision = true, contextTokens = 16_000, reasoning = false),
        http,
        maxOutputTokens = if (guiAction) 256 else null,
        temperature = if (guiAction) 0.0 else null,
    )

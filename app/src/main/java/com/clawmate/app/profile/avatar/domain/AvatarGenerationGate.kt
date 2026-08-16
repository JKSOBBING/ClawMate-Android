package com.clawmate.app.profile.avatar.domain

/**
 * Truthful capability boundary for digital-avatar image generation.
 *
 * The HarmonyOS avatar generation is a cloud call to a Seedream provider
 * (`datamanager/avatar/SeedreamAvatarProvider.ets`) that requires a configured
 * image base URL + API key + model, and returns real bytes. On Android we do
 * **not** vendor that cloud path and we never fabricate a generated avatar.
 * This gate derives an honest, actionable state purely from the locally
 * configured settings (`AvatarProviderSettings`), and records that a real
 * generation requires a configured, network-reachable provider.
 */
enum class AvatarGenerationState {
    /** No provider config present — the "生成数字分身" action must stay disabled. */
    NOT_CONFIGURED,

    /** Provider credentials/model are present, but no verified provider adapter is wired. */
    CONFIGURED_BUT_NO_PROVIDER,
}

/**
 * The minimal provider inputs needed before an avatar could be generated.
 * Values are read from the existing app settings (never stored here).
 */
data class AvatarProviderSettings(
    val imageBaseUrl: String = "",
    val apiKey: String = "",
    val textToImageModel: String = "",
    val imageToImageModel: String = "", // not currently collected in settings; kept for parity
    val imageSize: String = "",
    val watermark: Boolean = true,
) {
    val hasBaseUrl: Boolean get() = imageBaseUrl.isNotBlank()
    val hasApiKey: Boolean get() = apiKey.isNotBlank()
}

object AvatarGenerationGate {
    /**
     * Deterministically derive the honest generation state from configured
     * settings. Returns [AvatarGenerationState.NOT_CONFIGURED] whenever the
     * provider cannot be reached with real credentials; otherwise
     * [AvatarGenerationState.CONFIGURED_BUT_NO_PROVIDER].
     *
     * This is a pure, total function: it never performs network I/O, never
     * generates an image, and never claims a result it did not produce.
     */
    fun state(settings: AvatarProviderSettings): AvatarGenerationState =
        if (settings.hasBaseUrl && settings.hasApiKey) {
            AvatarGenerationState.CONFIGURED_BUT_NO_PROVIDER
        } else {
            AvatarGenerationState.NOT_CONFIGURED
        }

    /** Short, honest human copy for a given state. */
    fun label(state: AvatarGenerationState): String = when (state) {
        AvatarGenerationState.NOT_CONFIGURED -> "未配置数字分身生成服务"
        AvatarGenerationState.CONFIGURED_BUT_NO_PROVIDER -> "已配置，但尚未接入生成服务"
    }

    /**
     * Actionable guidance shown under the honest state — never implies a fake
     * success and never leaks the configured API key.
     */
    fun guidance(state: AvatarGenerationState, hasCurrentProfile: Boolean): String = when (state) {
        AvatarGenerationState.NOT_CONFIGURED ->
            "需要先配置图片服务 URL 与密钥（设置中心 → 模型与智能体 → 数字分身）。当前不生成任何图片。"
        AvatarGenerationState.CONFIGURED_BUT_NO_PROVIDER ->
            if (hasCurrentProfile) {
                "已具备本地画像，但 Android 尚未接入云端生成服务，且此设备不会凭空产出分身图片。"
            } else {
                "已配置服务参数，但本地尚无完整画像，且 Android 尚未接入云端生成服务。"
            }
    }
}

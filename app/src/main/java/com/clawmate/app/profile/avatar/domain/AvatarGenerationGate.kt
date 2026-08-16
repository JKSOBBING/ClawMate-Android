package com.clawmate.app.profile.avatar.domain

import com.clawmate.app.profile.avatar.data.generation.AvatarProviderLimits
import com.clawmate.app.profile.avatar.data.generation.AvatarUrlValidator

/**
 * Truthful capability boundary for digital-avatar image generation.
 *
 * Rooted in the HarmonyOS `datamanager/avatar/SeedreamAvatarProvider.ets` +
 * `datamanager/avatar/AvatarService.ets` flow, but on Android the generation is
 * **actually wired** through a configured Seedream-compatible provider (migration
 * row A2). The gate derives an honest readiness state purely from the locally
 * configured settings:
 *  - [NOT_CONFIGURED] — no base URL / no api key: the 生成 action stays disabled
 *    and the provider is never called;
 *  - [PARTIALLY_CONFIGURED] — base URL + key present but model or image size is
 *    missing (or the base URL is not a well-formed http/https endpoint): the
 *    action is disabled because the provider would lack required inputs;
 *  - [READY] — base URL (http/https + host), api key, model and size all
 *    present: a real `AvatarProvider` generation may proceed on explicit user
 *    action.
 *
 * This gate is a pure total function: it never performs network I/O, never
 * generates an image, and its readiness result is what a caller checks *before*
 * it is allowed to build/send any request.
 */
enum class AvatarGenerationState {
    /** No provider config present — the "生成数字分身" action must stay disabled. */
    NOT_CONFIGURED,

    /** Base URL + api key present, but model/size missing or the URL invalid. */
    PARTIALLY_CONFIGURED,

    /** A fully configured, reachable-in-principle provider is ready for a user action. */
    READY,
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
    val hasModel: Boolean get() = textToImageModel.isNotBlank()
    val hasImageSize: Boolean get() = imageSize.isNotBlank()
}

object AvatarGenerationGate {
    /**
     * Deterministically derive the honest readiness from configured settings.
     * Returns [AvatarGenerationState.READY] only when every required provider
     * input is present and the base URL is a valid absolute http/https endpoint.
     */
    fun state(settings: AvatarProviderSettings): AvatarGenerationState {
        if (!settings.hasBaseUrl || !settings.hasApiKey) return AvatarGenerationState.NOT_CONFIGURED
        val baseValid = AvatarUrlValidator.validateBaseUrl(settings.imageBaseUrl).valid
        if (!baseValid || !settings.hasModel || !settings.hasImageSize) {
            return AvatarGenerationState.PARTIALLY_CONFIGURED
        }
        return AvatarGenerationState.READY
    }

    /** Short, honest human copy for a given state. */
    fun label(state: AvatarGenerationState): String = when (state) {
        AvatarGenerationState.NOT_CONFIGURED -> "未配置数字分身生成服务"
        AvatarGenerationState.PARTIALLY_CONFIGURED -> "数字分身服务配置不完整"
        AvatarGenerationState.READY -> "数字分身服务已就绪"
    }

    /** Actionable guidance — never implies a fake success and never leaks the key. */
    fun guidance(state: AvatarGenerationState, hasCurrentProfile: Boolean): String = when (state) {
        AvatarGenerationState.NOT_CONFIGURED ->
            "需要先配置图片服务 URL 与密钥（设置中心 → 数字分身图片服务）。当前不生成任何图片。"
        AvatarGenerationState.PARTIALLY_CONFIGURED ->
            "图片服务 URL 与密钥已配置，但还缺少文生图模型或图片尺寸（或 URL 格式不正确）。补齐后可点击“生成数字分身”。"
        AvatarGenerationState.READY ->
            if (hasCurrentProfile) {
                "服务已就绪。点击“生成数字分身”将按你的本地画像生成一张图片并保存在本机（不会凭空编造）。"
            } else {
                "服务已就绪，但本地仓库还没有一个已保存的画像完整度。请先完善本地画像再生成。"
            }
    }

    /**
     * Whether a generation action may proceed given the gate state. The caller
     * backs out (and reports an honest message) *before* any network call when
     * this is false.
     */
    fun canGenerate(state: AvatarGenerationState): Boolean = state == AvatarGenerationState.READY

    /** Minimum profile completeness required for a meaningful generation. */
    val minProfileCompleteness: Int get() = AvatarProviderLimits.MIN_PROFILE_COMPLETENESS
}

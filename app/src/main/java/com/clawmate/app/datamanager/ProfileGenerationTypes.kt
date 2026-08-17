package com.clawmate.app.datamanager

import com.clawmate.app.datamanager.ProfileTypes.ProfileBuildResult
import com.clawmate.app.datamanager.ProfileTypes.ProfileBuildStatus

/**
 * Typed, honest outcome of an explicit "生成画像" run, surfaced so the UI can
 * represent 未配置 / 本地画像 / 运行中 / 生成成功 / 部分生成 / 失败 without ever
 * conflating a local-only projection with model claims.
 */
sealed interface ProfileGenerationState {

    /** No explicit generate action has completed yet. */
    data object Idle : ProfileGenerationState

    /** Model backend is not configured, but a local-only projection is available. */
    data object NotConfigured : ProfileGenerationState

    /** No real indexed events exist — nothing to generate (zero network). */
    data object NoData : ProfileGenerationState

    /** A profile build is in progress (may be local-only or model-backed). */
    data object Running : ProfileGenerationState

    /**
     * A profile build completed. [isModel] is true only when at least one domain
     * carries strictly-validated model claims; otherwise this is a real
     * local-only projection ([claims] stays bounded/local).
     */
    data class Success(
        val eventCount: Int,
        val domainCounts: Map<String, Int> = emptyMap(),
        val claimCount: Int = 0,
        val isModel: Boolean = false,
    ) : ProfileGenerationState

    /** The build partially produced claims (some domains local/empty). */
    data class Partial(
        val eventCount: Int,
        val domainCounts: Map<String, Int> = emptyMap(),
        val claimCount: Int = 0,
    ) : ProfileGenerationState

    /** The model path failed; the deterministic local result was preserved. */
    data class Error(
        val message: String,
        val userMessage: String,
    ) : ProfileGenerationState
}

/**
 * Typed factory translating a [ProfileBuildResult] into a [ProfileGenerationState]
 * for the Home UI. The mapping is honest: MODEL / LOCAL_ONLY / EMPTY map to
 * explicit, distinct states and nothing is invented.
 */
object ProfileGenerationStateMapper {

    fun fromResult(result: ProfileBuildResult, wasConfigured: Boolean): ProfileGenerationState {
        if (result.eventCount == 0) return ProfileGenerationState.NoData
        return when (result.status) {
            ProfileBuildStatus.MODEL ->
                ProfileGenerationState.Success(
                    eventCount = result.eventCount,
                    domainCounts = result.domainCounts,
                    claimCount = result.claimCount,
                    isModel = true,
                )
            ProfileBuildStatus.LOCAL_ONLY ->
                ProfileGenerationState.Success(
                    eventCount = result.eventCount,
                    domainCounts = result.domainCounts,
                    claimCount = result.claimCount,
                    isModel = false,
                )
            ProfileBuildStatus.PARTIAL ->
                ProfileGenerationState.Partial(
                    eventCount = result.eventCount,
                    domainCounts = result.domainCounts,
                    claimCount = result.claimCount,
                )
            ProfileBuildStatus.ERROR ->
                ProfileGenerationState.Error(
                    message = "画像模型生成失败，已保留本地画像",
                    userMessage = "画像生成失败，已保留本地画像；请检查模型配置后重试。",
                )
            else ->
                if (wasConfigured) {
                    ProfileGenerationState.Error(
                        message = "画像生成未完成",
                        userMessage = "画像生成未完成，请重试。",
                    )
                } else {
                    ProfileGenerationState.NotConfigured
                }
        }
    }
}

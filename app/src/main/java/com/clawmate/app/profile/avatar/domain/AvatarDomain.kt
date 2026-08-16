package com.clawmate.app.profile.avatar.domain

/**
 * Typed digital-avatar / user-profile domain, ported from the HarmonyOS source
 * of truth (read-only reference):
 *   entry/src/main/ets/datamanager/avatar/AvatarTypes.ets
 *   entry/src/main/ets/datamanager/avatar/AvatarPromptBuilder.ets
 *
 * Only the *local, on-device* parts are ported here. The actual image
 * generation (Harmony `SeedreamAvatarProvider`, a cloud provider that needs an
 * image base URL + API key) is intentionally **not** implemented on Android:
 * it stays behind the honest [AvatarGenerationState] boundary in
 * [AvatarGenerationGate]. Nothing in this package ever fabricates a generated
 * avatar image or a profile a real provider did not produce.
 */

/** Generation mode the avatar provider supports (source `AvatarGenerationMode`). */
enum class AvatarGenerationMode {
    TEXT_TO_IMAGE,
    IMAGE_TO_IMAGE;

    companion object {
        fun fromToken(token: String): AvatarGenerationMode = when (token.lowercase()) {
            "image_to_image" -> IMAGE_TO_IMAGE
            else -> TEXT_TO_IMAGE
        }

        fun of(mode: AvatarGenerationMode): String = when (mode) {
            TEXT_TO_IMAGE -> "text_to_image"
            IMAGE_TO_IMAGE -> "image_to_image"
        }
    }
}

/**
 * One persona-domain snapshot in the user profile.
 * Port of `AvatarDomainSnapshot` in AvatarTypes.ets.
 */
data class AvatarDomainSnapshot(
    val domain: String,
    val displayName: String,
    val shortSummary: String,
    val summary: String,
    val claims: List<String> = emptyList(),
    val eventCount: Int = 0,
)

/**
 * The profile snapshot a generated avatar is built from.
 * Port of `AvatarProfileSnapshot` in AvatarTypes.ets.
 */
data class AvatarProfileSnapshot(
    val generatedAt: String = "",
    val eventCount: Int = 0,
    val completeness: Int = 0,
    val gender: String = "",
    val age: String = "",
    val domains: List<AvatarDomainSnapshot> = emptyList(),
    val promptProfileText: String = "",
)

/** Consent recorded before an avatar photo is used. Port of `AvatarConsentSnapshot`. */
data class AvatarConsentSnapshot(
    val mode: AvatarGenerationMode = AvatarGenerationMode.TEXT_TO_IMAGE,
    val photoUsed: Boolean = false,
    val consentedAt: String = "",
    val photoSource: String = "",
)

/**
 * Deterministic lifecycle state of the avatar feature (mirrors `AvatarState`),
 * carried so the UI can honestly represent "never generated / up to date /
 * stale / failed" using only real persisted inputs.
 */
data class AvatarState(
    val currentVersionId: String = "",
    val profileSnapshotHash: String = "",
    val status: AvatarStatusToken = AvatarStatusToken.EMPTY,
    val lastErrorCode: String = "",
    val lastErrorMessage: String = "",
    val updatedAt: String = "",
    val referencePhotoPath: String = "",
)

/** Stable tokens mirroring the source `AvatarStatus` union. */
enum class AvatarStatusToken(val token: String) {
    EMPTY("empty"),
    CURRENT("current"),
    STALE("stale"),
    GENERATING("generating"),
    FAILED("failed");

    companion object {
        fun fromToken(value: String): AvatarStatusToken = entries.firstOrNull { it.token == value } ?: EMPTY
    }
}

/** A generated avatar version record (view model shape). Port of `AvatarVersionViewModel`. */
data class AvatarVersionViewModel(
    val versionId: String,
    val createdAt: String,
    val imageUri: String,
    val imagePath: String,
    val mode: AvatarGenerationMode,
    val provider: String,
    val model: String,
) {
    companion object {
        fun empty() = AvatarVersionViewModel(
            versionId = "",
            createdAt = "",
            imageUri = "",
            imagePath = "",
            mode = AvatarGenerationMode.TEXT_TO_IMAGE,
            provider = "",
            model = "",
        )
    }
}

/**
 * Locally editable, device-persisted user profile.
 *
 * This is the honest, real "Profile / Avatar local UI + state" surface: the
 * user can maintain their display name, short summary, gender/age and a short
 * summary + claims for each persona domain (吃 / 穿 / 住 / 行) entirely on
 * device with no cloud and no secrets. It is the input that a future avatar
 * provider would consume; no avatar image is ever generated here.
 */
data class LocalAvatarProfile(
    val displayName: String = "",
    val shortSummary: String = "",
    val gender: String = "",
    val age: String = "",
    val domains: List<AvatarDomainSnapshot> = defaultProfileDomains(),
    val updatedAt: String = "",
) {
    companion object {
        val empty = LocalAvatarProfile()
    }
}

/** The four persona domains used by the profile, with empty honest defaults. */
fun defaultProfileDomains(): List<AvatarDomainSnapshot> = listOf(
    AvatarDomainSnapshot(domain = "eat", displayName = "吃", shortSummary = "", summary = ""),
    AvatarDomainSnapshot(domain = "wear", displayName = "穿", shortSummary = "", summary = ""),
    AvatarDomainSnapshot(domain = "live", displayName = "住", shortSummary = "", summary = ""),
    AvatarDomainSnapshot(domain = "travel", displayName = "行", shortSummary = "", summary = ""),
)

/**
 * Pure completeness reducer (0..100) for a local profile. A domain is "filled"
 * when its short summary is a non-blank, non-`暂无数据` value; the display name
 * counts as a shared fifth signal. Deterministic and unit-testable.
 */
fun localProfileCompleteness(profile: LocalAvatarProfile): Int {
    val signals = mutableListOf<Boolean>()
    signals += profile.displayName.isNotBlank()
    profile.domains.take(4).forEach { signals += it.shortSummary.isNotBlank() }
    val done = signals.count { it }
    return (done * 100 + signals.size / 2) / signals.size
}

/**
 * Human copy for the real empty profile (mirrors the source sentinel). Kept
 * stable so tests and UI agree.
 */
const val PROFILE_DOMAIN_NO_DATA: String = "暂无数据"

/**
 * Normalise an arbitrary error value to a single-line message with a fallback.
 * Port of `avatarErrorMessage` in AvatarTypes.ets.
 */
fun avatarErrorMessage(value: Any?, fallback: String): String {
    if (value == null) return fallback
    val text = value.toString().replace(Regex("\\s+"), " ").trim()
    return text.takeIf { it.isNotEmpty() } ?: fallback
}

// --- Phase 24: honest run lifecycle + bounded prompt/consent (migration row A2)

/**
 * Lifecycle of a single, user-initiated avatar generation run, surfaced so the
 * screen honestly shows READY → RUNNING → SUCCESS / ERROR. [IDLE] means no run
 * has happened this session; [ERROR] always carries a redacted message.
 */
enum class AvatarRunState(val label: String) {
    IDLE(""),
    RUNNING("生成中…"),
    SUCCESS("已生成"),
    ERROR("生成失败");

    companion object {
        fun fromToken(token: String): AvatarRunState = entries.firstOrNull { it.name == token } ?: IDLE
    }
}

/** Bounded prompt text ready to send to the provider (deterministic). */
data class AvatarGenerationPrompt(
    val text: String,
    val byteLength: Int = text.length,
    val mode: AvatarGenerationMode = AvatarGenerationMode.TEXT_TO_IMAGE,
    val consented: Boolean = true,
) {
    companion object {
        /** Absolute cap on the built prompt (must match the provider cap). */
        const val MAX_PROMPT_LENGTH: Int = com.clawmate.app.profile.avatar.data.generation
            .AvatarProviderLimits.MAX_PROMPT_LENGTH
    }
}

/**
 * Build a bounded, deterministic prompt from the locally curated profile for a
 * text-to-image avatar generation. Mirrors the shape of the Harmony
 * `AvatarPromptBuilder.buildPrompt` (task goal → layout → profile fusion →
 * details → constraints → style → exclusions) but only ever uses the local
 * profile fields and never leaks a secret. The produced text is capped by
 * [AvatarGenerationPrompt.MAX_PROMPT_LENGTH]; the caller must not send an
 * unbounded/raw prompt to the provider.
 *
 * This is the honest text-to-image consent surface: generating a digital avatar
 * from the text profile alone uses no photo, and the explicit user action
 * (tapping 生成数字分身) is the recorded consent.
 */
fun buildAvatarGenerationPrompt(profile: LocalAvatarProfile): AvatarGenerationPrompt {
    val lines = mutableListOf<String>()
    lines += "任务目标: 生成一张 4:3 横向首页场景图（home hero banner），画面主体是一个生动、温暖且极具表现力、亲和力的 3D 卡通风格个人数字形象，用于移动生活助手 App 的主页上方展示。"
    lines += "构图与姿态: 人物位于画面中心安全区，脸部和上半身清晰，姿态自然亲近；画面适配移动端首页横幅裁切，人物不贴边，面部和关键上半身不被裁切。"
    lines += "个人 profile 内容:"
    if (profile.displayName.isNotBlank()) {
        lines += "展示名称: ${profile.displayName.trim().take(60)}"
    }
    if (profile.gender.isNotBlank()) {
        val g = profile.gender.trim()
        lines += "性别: ${if (g == "男") "male" else if (g == "女") "female" else g.take(20)}"
    }
    if (profile.age.isNotBlank()) {
        lines += "年龄: ${profile.age.trim().take(20)}"
    }
    profile.domains.take(4).forEach { domain ->
        val parts = mutableListOf<String>()
        if (domain.shortSummary.isNotBlank()) parts += domain.shortSummary.trim()
        if (domain.summary.isNotBlank()) parts += domain.summary.trim()
        if (domain.claims.isNotEmpty()) parts += domain.claims.take(3).joinToString("；")
        lines += "${domain.displayName}: ${parts.take(3).joinToString("；").take(300).ifBlank { "暂无稳定画像" }}"
    }
    lines += "约束: 严格仅反映上述文本内容，profile 元素数量克制，避免堆砌；高品质现代 3D 卡通风格，精致亲切；无文字、无水印、无 Logo。"
    val joined = lines.joinToString("\n")
    // Bound defensively.
    val bounded = if (joined.length <= AvatarGenerationPrompt.MAX_PROMPT_LENGTH) {
        joined
    } else {
        joined.take(AvatarGenerationPrompt.MAX_PROMPT_LENGTH)
    }
    return AvatarGenerationPrompt(text = bounded, mode = AvatarGenerationMode.TEXT_TO_IMAGE, consented = true)
}

/** Human copy for an avatar run state (used only for display). */
fun avatarRunStateLabel(state: AvatarRunState): String = when (state) {
    AvatarRunState.RUNNING -> "正在生成数字分身，请稍候…"
    AvatarRunState.SUCCESS -> "数字分身已生成并保存在本机"
    AvatarRunState.ERROR -> "数字分身生成失败，请稍后重试或检查服务配置"
    AvatarRunState.IDLE -> ""
}

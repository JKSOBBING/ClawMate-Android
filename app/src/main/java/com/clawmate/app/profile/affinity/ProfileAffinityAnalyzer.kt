package com.clawmate.app.profile.affinity

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsRequest
import com.clawmate.app.chat.cloud.ChatException
import com.clawmate.app.chat.cloud.ChatMessageWire

/**
 * Remote "同频报告" analyzer. Reuses the existing OpenAI-compatible chat client
 * ([com.clawmate.app.chat.cloud.ChatCompletionsClient] via the [ChatClient]
 * contract) and faithfully ports the HarmonyOS prompt from
 * `buildProfileAffinityMessages` (Index.ets), then returns the report as a
 * typed [ProfileAffinityResult].
 *
 * @property chat the shared OpenAI-compatible client (never re-implemented/re-copied).
 * @property config a validated [ProfileAffinityConfig]; only constructed when the
 *   Planner backend is configured.
 */
class ProfileAffinityAnalyzer(
    internal val chat: ChatClient,
    internal val config: ProfileAffinityConfig,
) {

    /**
     * Generate a "同频报告" from two profile summaries. Sends only the bounded
     * profiles (each capped by [ProfileAffinityBounds.MAX_PROFILE_INPUT_CHARACTERS])
     * as the two-message system+user prompt with `temperature = 0.2` and
     * `maxTokens = 2048`, the exact parameters ported from
     * `runProfileShareAffinityAnalysis`. The reply is bounded and malformed /
     * over-bounds / empty / error replies raise a typed [ProfileAffinityException].
     */
    suspend fun generate(localProfileText: String, remoteProfileText: String): ProfileAffinityResult {
        if (localProfileText.isBlank() || remoteProfileText.isBlank()) {
            throw ProfileAffinityException(
                reason = ProfileAffinityErrorReason.EMPTY_INPUT,
                message = "双方画像内容不足，无法生成同频报告",
            )
        }
        val content = try {
            chat.completion(
                ChatCompletionsRequest(
                    model = config.modelName,
                    messages = buildMessages(localProfileText, remoteProfileText),
                    stream = false,
                    temperature = 0.2,
                    maxTokens = 2048,
                ),
            ).content
        } catch (e: ChatException) {
            throw ProfileAffinityException.fromChat(e)
        }
        val report = content.trim()
        if (report.length > ProfileAffinityBounds.MAX_REPORT_CHARACTERS) {
            throw ProfileAffinityException.of(
                ProfileAffinityErrorReason.OVER_BOUNDS,
                "AI 同频报告超过字符上限(${ProfileAffinityBounds.MAX_REPORT_CHARACTERS})",
            )
        }
        if (report.isEmpty()) {
            throw ProfileAffinityException.of(
                ProfileAffinityErrorReason.EMPTY,
                "AI 未返回同频报告内容",
            )
        }
        return ProfileAffinityResult(report = report, rawText = content)
    }

    /**
     * The exact two-message prompt, ported from Harmony `buildProfileAffinityMessages`.
     * Only the two bounded profile texts are included — never a source URI, token
     * or unrelated local data.
     */
    internal fun buildMessages(
        localProfileText: String,
        remoteProfileText: String,
    ): List<ChatMessageWire> {
        val systemPrompt =
            "你是一个谨慎的用户画像同频分析助手。你只能根据输入的两份画像摘要分析相似度，" +
                "不要编造未出现的事实，不要输出隐私原文，不要给出医疗、金融、法律等高风险判断。" +
                "请用中文输出 Markdown，包含：同频分(0-100)、核心同频点、关键差异、分数原因、适合一起做的事。"
        val userPrompt =
            "本机用户画像摘要：\n" +
                limitProfileText(localProfileText) +
                "\n\n对方用户画像摘要：\n" +
                limitProfileText(remoteProfileText) +
                "\n\n请生成一份面向双方用户都能阅读的同频报告。"
        return listOf(
            ChatMessageWire(role = "system", content = systemPrompt),
            ChatMessageWire(role = "user", content = userPrompt),
        )
    }

    /**
     * Port of the Harmony `limitProfileShareText`: trim and cap at
     * [ProfileAffinityBounds.MAX_PROFILE_INPUT_CHARACTERS], appending an
     * ellipsis when truncated.
     */
    internal fun limitProfileText(
        text: String,
        maxLength: Int = ProfileAffinityBounds.MAX_PROFILE_INPUT_CHARACTERS,
    ): String {
        val value = text.trim()
        if (value.length <= maxLength) return value
        return value.substring(0, maxOf(0, maxLength - 3)) + "..."
    }
}

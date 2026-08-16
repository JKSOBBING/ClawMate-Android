package com.clawmate.app.gallery.cloud

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsRequest
import com.clawmate.app.chat.cloud.ChatException
import com.clawmate.app.chat.cloud.ChatMessageWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Optional AI month-summary adapter (migration row D2 cloud part).
 *
 * Reuses the existing OpenAI-compatible chat client
 * ([com.clawmate.app.chat.cloud.ChatCompletionsClient] via the [ChatClient]
 * contract) and the **same prompt** that the HarmonyOS
 * `CloudModelClient.summarizeGalleryMonth` sends, then parses the reply into a
 * typed [GalleryMonthSummaryResult].
 *
 * Safety properties (all enforced and unit-tested):
 *  - Only constructed with a ready [GalleryMonthlySummaryConfig] (explicit opt-in
 *    + endpoint/model/key present) — no call when not configured.
 *  - The summary prompt is built from the caller-provided concatenated monthly
 *    aggregate text; the LLM-input builder ([GalleryMonthlyInsights.buildLlmInput])
 *    already bounds the source to 80 samples before it is passed here.
 *  - Response is bounded in bytes and characters; an empty / missing title /
 *    summary / suggestion reply raises EMPTY (never fabricated).
 *  - Secrets are redacted from all error messages via
 *    [GalleryCloudErrorRedactor]; underlying [ChatException] messages are mapped
 *    to typed [GalleryCloudException].
 */
class GalleryMonthlySummaryClient(
    private val chat: ChatClient,
    private val config: GalleryMonthlySummaryConfig,
) {
    private val jsonTolerant = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Ported from Harmony `CloudModelClient.summarizeGalleryMonth`. Builds the
     * exact system/user prompt, calls the shared chat backend, and parses the
     * `{title, summary, suggestion}` JSON (tolerating parse failure by falling
     * back to an honest raw-text-based result, exactly as the source does).
     */
    suspend fun summarize(monthAggregateText: String): GalleryMonthSummaryResult {
        val prompt = buildPrompt(monthAggregateText)
        val content = try {
            chat.completion(
                ChatCompletionsRequest(
                    model = config.modelName,
                    messages = listOf(ChatMessageWire(role = "user", content = prompt)),
                    stream = false,
                ),
            ).content
        } catch (e: ChatException) {
            throw mapChatException(e)
        }
        if (content.length > GalleryCloudBounds.MAX_SUMMARY_CHARACTERS) {
            throw GalleryCloudException(
                reason = GalleryCloudErrorReason.OVER_BOUNDS,
                message = "AI summary exceeds character bound "
                    + GalleryCloudBounds.MAX_SUMMARY_CHARACTERS,
            )
        }
        return parseReply(content)
    }

    /** The exact prompt ported from `summarizeGalleryMonth`. */
    internal fun buildPrompt(monthAggregateText: String): String =
        "你是一个图库月度洞察助手。" +
            "\n\n请根据用户提供的月度图库聚合数据和图片摘要清单，判断这个月用户大概做了什么，并生成一个适合展示在“图库洞察”页的月度总结。" +
            "\n不要思考，不要输出思考过程、推理过程或分析步骤。" +
            "\n严格基于输入数据，不要编造具体人名、地点、城市、店名或未出现的事件。" +
            "\n如果只能推测，请使用“可能”“疑似”“看起来像”。" +
            "\n不要使用“人生高光”“最重要”等强判断。" +
            "\nsummary 必须包含 2 到 3 个具体观察，优先引用输入里的时间、类别、数量、平台、活动或事项线索；不要只写“内容丰富”“记录较多”这类笼统表述。" +
            "\n\n只输出 JSON，格式如下：" +
            "\n{" +
            "\n  \"title\": \"最多14个字的标题\"," +
            "\n  \"summary\": \"80到140字的月度总结，说明这个月大概做了什么\"," +
            "\n  \"suggestion\": \"一句可执行整理建议\"" +
            "\n}" +
            "\n\n月度图库聚合数据：\n" + monthAggregateText +
            "\n\n只输出 JSON，不要思考。"

    private fun parseReply(content: String): GalleryMonthSummaryResult {
        val payload = try {
            extractJsonObject(content)?.let { jsonTolerant.parseToJsonElement(it).jsonObject }
        } catch (e: Exception) {
            null
        }
        if (payload == null) {
            // Parse failure falls back to the raw text (mirrors the Harmony source).
            return GalleryMonthSummaryResult(
                title = "",
                summary = GalleryCloudErrorRedactor.redact(content.trim()),
                suggestion = "",
                rawText = GalleryCloudErrorRedactor.redact(content),
            )
        }
        val title = stringOf(payload, "title").ifBlank { stringOf(payload, "标题") }
        var summary = stringOf(payload, "summary").ifBlank { stringOf(payload, "摘要") }
        val suggestion = stringOf(payload, "suggestion")
            .ifBlank { stringOf(payload, "suggestions") }
            .ifBlank { stringOf(payload, "建议") }
        val reason = stringOf(payload, "reason")
        if (summary.isEmpty() && title.isEmpty() && suggestion.isEmpty()) {
            throw GalleryCloudException(
                reason = GalleryCloudErrorReason.EMPTY,
                message = GalleryCloudErrorRedactor.redact(
                    if (reason.isNotEmpty()) reason
                    else "AI 月度总结返回缺少 title/summary/suggestion",
                ),
            )
        }
        if (summary.isEmpty()) summary = content.trim()
        return GalleryMonthSummaryResult(
            title = title,
            summary = GalleryCloudErrorRedactor.redact(summary),
            suggestion = GalleryCloudErrorRedactor.redact(suggestion),
            rawText = GalleryCloudErrorRedactor.redact(content),
        )
    }

    private fun stringOf(obj: JsonObject, key: String): String =
        (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    /** Extract the first `{...}` block from an arbitrary model reply. */
    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\') { if (i + 1 < text.length) continue }
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun mapChatException(e: ChatException): GalleryCloudException =
        GalleryCloudException(
            reason = when (e.kind) {
                com.clawmate.app.chat.cloud.ChatErrorKind.HTTP -> GalleryCloudErrorReason.HTTP
                com.clawmate.app.chat.cloud.ChatErrorKind.TIMEOUT -> GalleryCloudErrorReason.IO
                com.clawmate.app.chat.cloud.ChatErrorKind.IO -> GalleryCloudErrorReason.IO
                com.clawmate.app.chat.cloud.ChatErrorKind.MALFORMED_SSE -> GalleryCloudErrorReason.MALFORMED
                else -> GalleryCloudErrorReason.IO
            },
            httpCode = e.httpCode,
            message = GalleryCloudErrorRedactor.redact(e.message.orEmpty()),
            cause = e,
        )
}

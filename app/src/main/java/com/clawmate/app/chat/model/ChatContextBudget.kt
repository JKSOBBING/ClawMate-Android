package com.clawmate.app.chat.model

import com.clawmate.app.chat.cloud.ChatMessageWire
import com.clawmate.app.settings.domain.AppSettings

/**
 * Pure, deterministic context-budget seam (Requirement A).
 *
 * This is the Android port of HarmonyOS `pages/chat/ChatContextBudget.ets`.
 * It decides whether a model request's wire context must be compressed and,
 * when it must, produces a **local-only extractive summary** of the older
 * messages plus the retained recent window.
 *
 * Key properties (all mirrored from the Harmony source):
 *  - token approximation is a character-based proxy: `estimateTextTokens` counts
 *    UTF-16 code units (`String.length`, identical to the Harmony `text.length`),
 *    so ASCII and BMP CJK characters each count as 1 and non-BMP codepoints
 *    (emoji) count as 2. UTF-8 byte length is NOT used; the proxy stays bytecount
 *    independent and matches the Harmony algorithm.
 *  - threshold = `maxContextTokens * thresholdPercent / 100`; usable input =
 *    `threshold - reservedOutputTokens`; compress when `estimated >= usable`.
 *  - `thresholdPercent` and `keepRecentTurns` come from [AppSettings] via
 *    [fromSettings] and are clamped to the same ranges as the Settings
 *    normalizers (50..95 and 2..20), so the seam can never contradict what the
 *    user configured.
 *  - extractive summary is pure and never calls the model or network. On any
 *    pressure it retains the most recent N user turns (never silently dropping
 *    the newest messages), keeps chronological order, and skips `task_trace` /
 *    `context_notice` messages (UI-only, must not pollute the summary).
 *
 * This class performs no I/O, no tokenizer dependency, and no network access —
 * it is fully unit-testable.
 */
class ChatContextBudget(
    val thresholdPercent: Int,
    val keepRecentTurns: Int,
    val maxContextTokens: Int = DEFAULT_MAX_CONTEXT_TOKENS,
    val reservedOutputTokens: Int = DEFAULT_RESERVED_OUTPUT_TOKENS,
) {

    init {
        require(thresholdPercent in MIN_THRESHOLD_PERCENT..MAX_THRESHOLD_PERCENT) {
            "thresholdPercent must be in $MIN_THRESHOLD_PERCENT..$MAX_THRESHOLD_PERCENT"
        }
        require(keepRecentTurns in MIN_KEEP_RECENT_TURNS..MAX_KEEP_RECENT_TURNS) {
            "keepRecentTurns must be in $MIN_KEEP_RECENT_TURNS..$MAX_KEEP_RECENT_TURNS"
        }
    }

    // ------------------------------------------------------------------
    // Token approximation
    // ------------------------------------------------------------------

    /**
     * Approximate model tokens for [text] as its UTF-16 code-unit length.
     * Matches HarmonyOS `estimateTextTokens`. BMP CJK and ASCII both count 1;
     * non-BMP characters (surrogate-pair emoji) count 2. This is a rough proxy
     * only — no end-side tokenizer is pulled in.
     */
    fun estimateTextTokens(text: String): Int = text.length

    /**
     * Approximate tokens for one wire message: 16 structural overhead + role +
     * textual content (the only content shape this project emits).
     */
    fun estimateWireMessageTokens(message: ChatMessageWire): Int =
        CLOUD_MESSAGE_STRUCTURE_TOKEN_OVERHEAD +
            estimateTextTokens(message.role) +
            estimateTextTokens(message.content ?: "")

    /** Approximate tokens for a whole list of wire messages. */
    fun estimateMessagesTokens(messages: List<ChatMessageWire>): Int =
        messages.sumOf { estimateWireMessageTokens(it) }

    // ------------------------------------------------------------------
    // Decision
    // ------------------------------------------------------------------

    /** The compression decision for a fully-built wire message list. */
    fun decide(messages: List<ChatMessageWire>): ChatContextBudgetDecision {
        val estimated = estimateMessagesTokens(messages)
        val threshold = maxContextTokens * thresholdPercent / 100
        val usable = (threshold - reservedOutputTokens).coerceAtLeast(0)
        return ChatContextBudgetDecision(
            estimatedInputTokens = estimated,
            thresholdTokens = threshold,
            usableInputTokens = usable,
            shouldCompress = estimated >= usable,
        )
    }

    // ------------------------------------------------------------------
    // Splitting
    // ------------------------------------------------------------------

    /**
     * Splits a session's messages by the most recent [keepRecentTurns] user
     * turns. Messages that are UI-only (`task_trace`, `context_notice`) are
     * excluded from both buckets (counted in [ChatHistorySplit.skippedMessageCount]).
     * Order is preserved. Never drops more than the older prefix.
     */
    fun splitHistoryByRecentTurns(messages: List<ChatMessage>): ChatHistorySplit {
        val usable = mutableListOf<ChatMessage>()
        var skipped = 0
        for (m in messages) {
            if (isSkippedContextMessage(m)) skipped++ else usable.add(m)
        }
        if (usable.isEmpty()) {
            return ChatHistorySplit(emptyList(), emptyList(), 0, skipped)
        }

        val userTurnCount = usable.count { it.role == ChatMessageRole.USER }
        if (userTurnCount == 0) {
            return ChatHistorySplit(emptyList(), usable, 0, skipped)
        }
        if (userTurnCount < keepRecentTurns) {
            return ChatHistorySplit(emptyList(), usable, userTurnCount, skipped)
        }

        // boundary = index of the (keepRecentTurns)-th user turn from the end.
        var count = 0
        var boundary = 0
        for (i in usable.indices.reversed()) {
            if (usable[i].role == ChatMessageRole.USER) {
                count++
                if (count == keepRecentTurns) {
                    boundary = i
                    break
                }
            }
        }
        val older = usable.subList(0, boundary)
        val retained = usable.subList(boundary, usable.size)
        return ChatHistorySplit(
            olderMessages = older,
            retainedMessages = retained,
            keptUserTurnCount = keepRecentTurns,
            skippedMessageCount = skipped,
        )
    }

    // ------------------------------------------------------------------
    // Extractive summary (local only)
    // ------------------------------------------------------------------

    /** Enum label for a message in the summary, mirroring the Harmony helper. */
    fun summaryLabel(message: ChatMessage): String = when {
        message.role == ChatMessageRole.USER -> "用户"
        message.role == ChatMessageRole.ASSISTANT -> "助手"
        message.role == ChatMessageRole.SYSTEM -> "系统"
        message.kind == ChatMessageKind.TASK_RESULT -> "工具结果"
        else -> "消息"
    }

    /**
     * Deterministic, local-only extractive summary of [messages]. Produces a
     * leading header line then `- <label>: <compact text>` lines in
     * chronological order, bounded to [maxChars]. Returns an honest "nothing to
     * extract" note when there is no content rather than fabricating text.
     */
    fun buildExtractiveSummary(
        messages: List<ChatMessage>,
        maxChars: Int = DEFAULT_EXTRACTIVE_SUMMARY_MAX_CHARS,
    ): String {
        val cap = if (maxChars <= 0) DEFAULT_EXTRACTIVE_SUMMARY_MAX_CHARS else maxChars
        var summary = EXTRACTIVE_SUMMARY_HEADER
        for (m in messages) {
            if (isSkippedContextMessage(m)) continue
            val content = compactText(m.content)
            if (content.isEmpty()) continue
            val line = "- ${summaryLabel(m)}: $content"
            val appended = appendSummaryLine(summary, line, cap)
            if (appended == summary) break
            summary = appended
            if (summary.length >= cap) break
        }
        return if (summary == EXTRACTIVE_SUMMARY_HEADER) {
            "$EXTRACTIVE_SUMMARY_HEADER\n- 无可抽取的早期对话内容。"
        } else summary
    }

    // ------------------------------------------------------------------
    // Wire-context building
    // ------------------------------------------------------------------

    /**
     * Builds the final on-wire message list for a model request, applying
     * context compression when the current history exceeds the budget.
     *
     * @param systemPrompt the fixed leading system prompt.
     * @param history the stored session messages in chronological order.
     * @param pendingUserText optional new user turn appended at the tail (the
     *   plain `sendMessage` path). When null (the agent path) the caller appends
     *   the user turn itself and the decision covers only the prior history.
     *
     * Never silently drops the newest messages and never fakes tool calls or
     * credentials into the summary. When compression is triggered the result
     * carries a deterministic extractive summary of the older messages and the
     * retained recent window.
     */
    fun buildWireContext(
        systemPrompt: String,
        history: List<ChatMessage>,
        pendingUserText: String? = null,
    ): ChatContextBuildResult {
        val systemWire = ChatMessageWire(ChatMessageRole.SYSTEM.wire, systemPrompt)
        val baseWires = toContextWires(history)
        val withPending = if (pendingUserText.isNullOrBlank()) baseWires else baseWires +
            ChatMessageWire(ChatMessageRole.USER.wire, pendingUserText)

        if (!decide(listOf(systemWire) + withPending).shouldCompress) {
            return ChatContextBuildResult(
                messages = listOf(systemWire) + withPending,
                compressed = false,
                summary = "",
            )
        }

        // Compress: keep recent window, fold older messages into an extractive summary.
        val split = splitHistoryByRecentTurns(history)
        val summaryText = if (split.olderMessages.isNotEmpty()) {
            buildExtractiveSummary(split.olderMessages)
        } else {
            EXTRACTIVE_SUMMARY_HEADER + "\n- 无可抽取的早期对话内容。"
        }

        // Merge the summary into the single leading system message (some
        // OpenAI-compatible servers only accept system at index 0), matching the
        // Harmony `buildCompressedCloudContextMessages` merging rule.
        val mergedSystem = mergeSummaryIntoSystem(systemPrompt, summaryText)
        val retainedWires = toContextWires(split.retainedMessages)
        val messages = listOf(mergedSystem) + retainedWires + if (pendingUserText.isNullOrBlank()) {
            emptyList()
        } else {
            listOf(ChatMessageWire(ChatMessageRole.USER.wire, pendingUserText))
        }
        return ChatContextBuildResult(
            messages = messages,
            compressed = true,
            summary = summaryText,
        )
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun toContextWires(messages: List<ChatMessage>): List<ChatMessageWire> = buildList {
        for (m in messages) {
            val content = m.content.trim()
            if (content.isEmpty() || isSkippedContextMessage(m)) continue
            when {
                m.role == ChatMessageRole.USER ||
                    m.role == ChatMessageRole.ASSISTANT ||
                    m.role == ChatMessageRole.SYSTEM -> add(ChatMessageWire(m.role.wire, content))
                m.kind == ChatMessageKind.TASK_RESULT -> add(ChatMessageWire(ChatMessageRole.ASSISTANT.wire, content))
                else -> Unit
            }
        }
    }

    private fun mergeSummaryIntoSystem(systemPrompt: String, summary: String): ChatMessageWire {
        val base = systemPrompt.trim()
        var content = if (base.isEmpty()) "" else base
        val trimmed = summary.trim()
        if (trimmed.isNotEmpty()) {
            val summaryContent = "$SUMMARY_PREFIX$trimmed"
            content = if (content.isEmpty()) summaryContent else "$content\n\n$summaryContent"
        }
        return ChatMessageWire(ChatMessageRole.SYSTEM.wire, content)
    }

    private fun compactText(text: String): String = text.replace(WHITESPACE_REGEX, " ").trim()

    private fun appendSummaryLine(summary: String, line: String, maxChars: Int): String {
        val candidate = if (summary.isEmpty()) line else "$summary\n$line"
        if (candidate.length <= maxChars) return candidate
        val separatorLength = if (summary.isEmpty()) 0 else 1
        val remaining = maxChars - summary.length - separatorLength
        if (remaining <= 3) return summary
        val clipped = line.substring(0, remaining - 3) + "..."
        return if (summary.isEmpty()) clipped else "$summary\n$clipped"
    }

    companion object {
        const val DEFAULT_MAX_CONTEXT_TOKENS: Int = 256000
        const val DEFAULT_THRESHOLD_PERCENT: Int = 85
        const val MIN_THRESHOLD_PERCENT: Int = 50
        const val MAX_THRESHOLD_PERCENT: Int = 95
        const val DEFAULT_KEEP_RECENT_TURNS: Int = 8
        const val MIN_KEEP_RECENT_TURNS: Int = 2
        const val MAX_KEEP_RECENT_TURNS: Int = 20
        const val DEFAULT_RESERVED_OUTPUT_TOKENS: Int = 32768
        const val DEFAULT_EXTRACTIVE_SUMMARY_MAX_CHARS: Int = 4000
        const val EXTRACTIVE_SUMMARY_HEADER: String = "较早对话摘要（本地抽取）:"
        const val SUMMARY_PREFIX: String =
            "以下是较早对话的压缩摘要，用于延续上下文；请将其视为历史背景，不要逐字重复：\n"
        const val CHAT_CONTEXT_COMPRESSION_NOTICE: String = "历史过长，已自动压缩上下文"

        private const val CLOUD_MESSAGE_STRUCTURE_TOKEN_OVERHEAD: Int = 16
        private val WHITESPACE_REGEX = Regex("\\s+")

        /** Default budget using the Settings-safe normalized defaults. */
        fun defaults(): ChatContextBudget = ChatContextBudget(
            thresholdPercent = DEFAULT_THRESHOLD_PERCENT,
            keepRecentTurns = DEFAULT_KEEP_RECENT_TURNS,
        )

        /**
         * Builds a budget from [AppSettings] while keeping the same bounds the
         * Settings normalizers enforce, so the compression can never disagree
         * with the user's configured threshold / recent-rounds.
         */
        fun fromSettings(settings: AppSettings): ChatContextBudget {
            val threshold = settings.chatCompressionThresholdPercent
                .coerceIn(MIN_THRESHOLD_PERCENT, MAX_THRESHOLD_PERCENT)
            val rounds = settings.chatRecentRounds
                .coerceIn(MIN_KEEP_RECENT_TURNS, MAX_KEEP_RECENT_TURNS)
            return ChatContextBudget(
                thresholdPercent = threshold,
                keepRecentTurns = rounds,
            )
        }
    }
}

/** The compression decision for a wire message list. */
data class ChatContextBudgetDecision(
    val estimatedInputTokens: Int,
    val thresholdTokens: Int,
    val usableInputTokens: Int,
    val shouldCompress: Boolean,
)

/** The split of a session history into older + retained recent messages. */
data class ChatHistorySplit(
    val olderMessages: List<ChatMessage>,
    val retainedMessages: List<ChatMessage>,
    val keptUserTurnCount: Int,
    val skippedMessageCount: Int,
)

/** Result of building a (possibly compressed) on-wire model context. */
data class ChatContextBuildResult(
    val messages: List<ChatMessageWire>,
    val compressed: Boolean,
    val summary: String,
)

/** A `task_trace` / `context_notice` is UI-only and never enters the model context. */
fun isSkippedContextMessage(message: ChatMessage): Boolean =
    message.kind == ChatMessageKind.TASK_TRACE || message.kind == ChatMessageKind.CONTEXT_NOTICE

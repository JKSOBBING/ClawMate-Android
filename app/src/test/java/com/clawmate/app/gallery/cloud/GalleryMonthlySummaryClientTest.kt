package com.clawmate.app.gallery.cloud

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsRequest
import com.clawmate.app.chat.cloud.ChatCompletionResult
import com.clawmate.app.chat.cloud.ChatException
import com.clawmate.app.chat.cloud.ChatErrorKind
import com.clawmate.app.chat.cloud.CloudStreamChunk
import com.clawmate.app.gallery.insights.GalleryMonthInsight
import com.clawmate.app.gallery.insights.GalleryMonthlyInsights
import com.clawmate.app.gallery.model.GalleryPhotoItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Device-free [GalleryMonthlySummaryClient] tests using a fake [ChatClient]:
 * the exact Harmony prompt shape, JSON reply parsing, secret redaction and the
 * bounded-input contract via [GalleryMonthlyInsights.buildLlmInput].
 */
class GalleryMonthlySummaryClientTest {

    private val config = GalleryMonthlySummaryConfig(
        enabled = true,
        baseUrl = "https://planner.test",
        apiKey = "sk-planner-secret",
        modelName = "gpt-4o",
    )

    private fun client(chat: ChatClient): GalleryMonthlySummaryClient =
        GalleryMonthlySummaryClient(chat, config)

    private fun insight(): GalleryMonthInsight {
        val items: List<GalleryPhotoItem> = (1..100).map { i ->
            GalleryPhotoItem(
                id = "p$i",
                uri = "content://x/$i",
                date = "2026-06-01",
                title = "item $i",
                summary = "summary text $i",
                category = "餐饮",
                createdAt = 0L,
            )
        }
        return GalleryMonthlyInsights.build(items).first()
    }

    @Test
    fun `buildLlmInput aggregates are capped to 80 samples`() {
        val insight = insight()
        val text = GalleryMonthlyInsights.buildLlmInput(insight)
        assertTrue(text.contains("共 100 张，本次提供 80"))
        assertTrue(text.length < 20_000)
    }

    @Test
    fun `prompt embeds the bounded aggregate and asks only for JSON`() {
        val client = client(chatReturning("raw"))
        val prompt = client.buildPrompt("AGG")
        assertTrue(prompt.contains("月度图库聚合数据"))
        assertTrue(prompt.contains("AGG"))
        assertTrue(prompt.contains("只输出 JSON"))
        assertTrue(prompt.contains("月度总结"))
    }

    @Test
    fun `summarize parses a JSON reply into typed result`() = runBlocking {
        val client = client(
            chatReturning("""{"title":"六月","summary":"观察一","suggestion":"整理建议"}"""),
        )
        val result = client.summarize("AGG")
        assertEquals("六月", result.title)
        assertEquals("观察一", result.summary)
        assertEquals("整理建议", result.suggestion)
    }

    @Test
    fun `summarize maps a provider error and redacts secrets`() = runBlocking {
        val chat = object : ChatClient {
            override suspend fun completion(r: ChatCompletionsRequest): ChatCompletionResult {
                throw ChatException(ChatErrorKind.HTTP, "invalid sk-planner-secret", httpCode = 401)
            }

            override fun completionStream(r: ChatCompletionsRequest): Flow<CloudStreamChunk> = emptyFlow()
        }
        val e = try {
            client(chat).summarize("AGG")
            fail("expected GalleryCloudException")
            null
        } catch (ex: GalleryCloudException) {
            ex
        }
        assertTrue(e != null)
        assertFalse("secret must not leak", e!!.safeMessage.contains("sk-planner-secret"))
        assertFalse("secret must not leak", e.safeMessage.contains("sk-"))
    }

    @Test
    fun `summarize with empty payload raises EMPTY (never fabricated)`() = runBlocking {
        val client = client(chatReturning("""{"foo":true}"""))
        val e = try {
            client.summarize("AGG")
            fail("expected EMPTY")
            null
        } catch (ex: GalleryCloudException) {
            ex
        }
        assertTrue(e != null)
        assertEquals(GalleryCloudErrorReason.EMPTY, e!!.reason)
    }

    @Test
    fun `summarize falls back to raw text when reply is not JSON`() = runBlocking {
        val client = client(chatReturning("这段月度总结平铺直叙没有 JSON"))
        val result = client.summarize("AGG")
        // Parse-failure fallback (mirrors Harmony) is honest, never a fabricated field.
        assertTrue(result.summary.isNotBlank())
    }

    // ----------------------------------------------------------------- helper

    private fun chatReturning(content: String): ChatClient = object : ChatClient {
        override suspend fun completion(r: ChatCompletionsRequest): ChatCompletionResult =
            ChatCompletionResult(content = content, finishReason = "stop", raw = content)

        override fun completionStream(r: ChatCompletionsRequest): Flow<CloudStreamChunk> = emptyFlow()
    }
}

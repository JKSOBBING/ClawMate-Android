package com.clawmate.app.gallery.cloud

import com.clawmate.app.gallery.insights.GalleryMonthlyInsightCloudState
import com.clawmate.app.settings.domain.AppSettings
import com.clawmate.app.settings.domain.EmbeddingMode
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Device-free config-gating tests for [GalleryCloudAdapter].
 *
 * Verifies the honest NOT_CONFIGURED / UNSUPPORTED / READY gates and — crucially —
 * that the executor methods short-circuit **without touching any client** when a
 * feature is not configured (no network, no fabricated text/vector).
 */
class GalleryCloudAdapterTest {

    private val httpClient = OkHttpClient()
    private val fakeChat = object : com.clawmate.app.chat.cloud.ChatClient {
        override suspend fun completion(
            request: com.clawmate.app.chat.cloud.ChatCompletionsRequest,
        ): com.clawmate.app.chat.cloud.ChatCompletionResult {
            fail("chat client must not be invoked when not configured")
            throw UnsupportedOperationException()
        }

        override fun completionStream(
            request: com.clawmate.app.chat.cloud.ChatCompletionsRequest,
        ): kotlinx.coroutines.flow.Flow<com.clawmate.app.chat.cloud.CloudStreamChunk> {
            fail("chat client must not be invoked when not configured")
            throw UnsupportedOperationException()
        }
    }

    private fun adapter(
        settings: AppSettings,
        plannerBase: String = "",
        plannerKey: String = "",
        plannerModel: String = "",
    ): GalleryCloudAdapter = GalleryCloudAdapter.fromSettings(
        settings = settings,
        plannerBaseUrl = plannerBase,
        plannerApiKey = plannerKey,
        plannerModel = plannerModel,
        httpClient = httpClient,
        chatClient = fakeChat,
    )

    // ------------------------------------------------------------ summary gate

    @Test
    fun `summary is NOT_CONFIGURED when opted out`() {
        val a = adapter(AppSettings.defaults().copy(galleryAiSummaryEnabled = false))
        assertEquals(GalleryMonthlyInsightCloudState.NotConfigured, a.summaryState)
    }

    @Test
    fun `summary is NOT_CONFIGURED when opt-in but planner endpoint missing`() {
        val a = adapter(
            AppSettings.defaults().copy(galleryAiSummaryEnabled = true),
            plannerBase = "https://planner.test",
            plannerKey = "",
            plannerModel = "",
        )
        assertEquals(GalleryMonthlyInsightCloudState.NotConfigured, a.summaryState)
    }

    @Test
    fun `summary is READY when opted-in and fully configured`() {
        val a = adapter(
            AppSettings.defaults().copy(galleryAiSummaryEnabled = true),
            plannerBase = "https://planner.test",
            plannerKey = "sk-secret",
            plannerModel = "gpt-4o",
        )
        assertEquals(GalleryMonthlyInsightCloudState.Ready::class.java, a.summaryState::class.java)
    }

    // ------------------------------------------------------------ embedding gate

    @Test
    fun `embedding is UNSUPPORTED when mode is huawei_local`() {
        val a = adapter(AppSettings.defaults())
        assertEquals(GalleryMonthlyInsightCloudState.Unsupported, a.embeddingState)
    }

    @Test
    fun `embedding is NOT_CONFIGURED when remote mode but no config`() {
        val a = adapter(AppSettings.defaults().copy(galleryEmbeddingMode = EmbeddingMode.Remote))
        assertEquals(GalleryMonthlyInsightCloudState.NotConfigured, a.embeddingState)
    }

    @Test
    fun `embedding is READY when remote mode fully configured`() {
        val a = adapter(
            AppSettings.defaults().copy(
                galleryEmbeddingMode = EmbeddingMode.Remote,
                galleryEmbeddingBaseUrl = "https://embed.test",
                galleryEmbeddingApiKey = "sk-embed",
                galleryEmbeddingModel = "text-embedding-3",
            ),
        )
        assertEquals(GalleryMonthlyInsightCloudState.Ready::class.java, a.embeddingState::class.java)
    }

    // ------------------------------------------------------------- no-call gate

    @Test
    fun `summary executor never calls the network when not configured`() = runBlocking {
        val a = adapter(AppSettings.defaults().copy(galleryAiSummaryEnabled = false))
        try {
            a.generateMonthlySummary("aggregate text here")
            fail("expected GalleryCloudException when not configured")
        } catch (e: GalleryCloudException) {
            assertEquals(GalleryCloudErrorReason.EMPTY_INPUT, e.reason)
        }
    }

    @Test
    fun `embedding executor never calls the network when not configured`() = runBlocking {
        val a = adapter(AppSettings.defaults())
        try {
            a.embedText("some text")
            fail("expected GalleryCloudException when not configured")
        } catch (e: GalleryCloudException) {
            assertEquals(GalleryCloudErrorReason.EMPTY_INPUT, e.reason)
        }
    }
}

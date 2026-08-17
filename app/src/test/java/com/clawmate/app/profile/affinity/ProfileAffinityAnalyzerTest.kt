package com.clawmate.app.profile.affinity

import com.clawmate.app.chat.cloud.ChatErrorKind
import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsRequest
import com.clawmate.app.chat.cloud.ChatCompletionResult
import com.clawmate.app.chat.cloud.ChatException
import com.clawmate.app.chat.cloud.CloudStreamChunk
import com.clawmate.app.config.EndpointConfig
import com.clawmate.app.profile.avatar.domain.LocalAvatarProfile
import com.clawmate.app.profile.avatar.domain.AvatarDomainSnapshot
import com.clawmate.app.profile.avatar.domain.defaultProfileDomains
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Device-free tests for the profile-affinity "同频报告" feature.
 *
 * Covers the no-call config gate, the faithfully-ported two-message prompt, the
 * input/output bounds, empty/exception returns and the HTTP/timeout error
 * mapping — all without a device or network. The Android Activity wiring
 * (button + coroutine states) is verified to compile by `:app:assembleDebug`.
 */
class ProfileAffinityAnalyzerTest {

    private val config = ProfileAffinityConfig(
        baseUrl = "https://planner.test",
        apiKey = "sk-planner-secret",
        modelName = "gpt-4o",
    )

    private fun analyzer(chat: ChatClient): ProfileAffinityAnalyzer =
        ProfileAffinityAnalyzer(chat, config)

    @After
    fun tearDown() {
        // Restore an empty EndpointConfig so the test never leaks state elsewhere.
        EndpointConfig.load(java.util.Properties())
    }

    // ------------------------------------------------------------------ gate

    @Test
    fun `config gate returns null when planner backend is blank (no call)`() {
        EndpointConfig.load(java.util.Properties()) // all fields blank
        assertNull(
            "Without a configured Planner backend the feature is NOT_CONFIGURED",
            ProfileAffinityConfig.fromEndpointConfig(),
        )
    }

    @Test
    fun `config gate returns a config when planner endpoint model and key present`() {
        EndpointConfig.load(
            java.util.Properties().apply {
                setProperty("cloudPlannerBaseUrl", "https://planner.test")
                setProperty("cloudPlannerApiKey", "sk-planner-secret")
                setProperty("cloudPlannerModel", "gpt-4o")
            },
        )
        val cfg = ProfileAffinityConfig.fromEndpointConfig()
        assertNotNull(cfg)
        assertEquals("https://planner.test", cfg!!.baseUrl.trimEnd('/'))
        assertEquals("sk-planner-secret", cfg.apiKey)
        assertEquals("gpt-4o", cfg.modelName)
    }

    @Test
    fun `config gate stays null when api key missing even with a url set`() {
        EndpointConfig.load(
            java.util.Properties().apply {
                setProperty("cloudPlannerBaseUrl", "https://planner.test")
                setProperty("cloudPlannerModel", "gpt-4o")
                // no api key
            },
        )
        assertNull(ProfileAffinityConfig.fromEndpointConfig())
    }

    // ------------------------------------------------------------------ prompt

    @Test
    fun `prompt includes both profile texts and never a URI or api key`() {
        val client = analyzer(fakeChatReturning("报告"))
        val messages = client.buildMessages("本地画像内容", "对方画像内容")

        assertEquals("system", messages[0].role)
        assertEquals("user", messages[1].role)
        val systemText = messages[0].content.orEmpty()
        val userText = messages[1].content.orEmpty()

        // Faithful Harmony prompt requirements: cautious analysis of two profiles only.
        assertTrue(systemText.contains("同频分(0-100)"))
        assertTrue(systemText.contains("核心同频点"))
        assertTrue(systemText.contains("关键差异"))
        assertTrue(systemText.contains("分数原因"))
        assertTrue(systemText.contains("适合一起做的事"))
        assertTrue(systemText.contains("谨慎"))
        assertTrue(systemText.contains("不要编造"))
        assertTrue(systemText.contains("不要输出隐私原文"))

        assertTrue(userText.contains("本地画像内容"))
        assertTrue(userText.contains("对方画像内容"))
        assertTrue(userText.contains("本机用户画像摘要"))
        assertTrue(userText.contains("对方用户画像摘要"))

        // Nothing sensitive / unrelated is ever transmitted.
        assertFalse(userText.contains("content://"))
        assertFalse(userText.contains("sk-planner-secret"))
        assertFalse(userText.contains("sk-"))
        assertFalse(userText.contains("Authorization"))
        assertFalse(userText.contains("Bearer"))
    }

    @Test
    fun `generated request uses temperature 0p2 and maxTokens 2048`() = runBlocking {
        val captured = mutableListOf<ChatCompletionsRequest>()
        val chat = object : ChatClient {
            override suspend fun completion(r: ChatCompletionsRequest): ChatCompletionResult {
                captured += r
                return ChatCompletionResult("报告", "stop", null)
            }

            override fun completionStream(r: ChatCompletionsRequest): Flow<CloudStreamChunk> = emptyFlow()
        }
        analyzer(chat).generate("本地", "对方")
        assertEquals(0.2, captured.single().temperature)
        assertEquals(2048, captured.single().maxTokens)
        assertEquals("gpt-4o", captured.single().model)
        assertEquals(false, captured.single().stream)
    }

    // ------------------------------------------------------------------ bounds

    @Test
    fun `limitProfileText truncates long profiles with an ellipsis`() {
        val long = "x".repeat(15000)
        val bounded = analyzer(fakeChatReturning("x")).limitProfileText(long)
        assertTrue(bounded.length <= ProfileAffinityBounds.MAX_PROFILE_INPUT_CHARACTERS)
        assertTrue(bounded.endsWith("..."))
    }

    @Test
    fun `buildLocalAffinityProfileText is capped to the input bound and never raw`() {
        // A real local profile only ever populates `shortSummary` (the editor and
        // share importer write that field); `summary` stays blank in practice.
        // Mirroring that real state means the production mapping exercises the
        // `shortSummary` fallback that actually carries profile content.
        val profile = LocalAvatarProfile(
            displayName = "用户A",
            domains = defaultProfileDomains().mapIndexed { i, d ->
                d.copy(shortSummary = "summary$i", summary = "", claims = listOf("claim$i"))
            },
        )
        val text = buildLocalAffinityProfileText(profile)
        assertTrue(text.length <= ProfileAffinityBounds.MAX_PROFILE_INPUT_CHARACTERS)
        assertTrue(text.contains("用户A"))
        assertTrue("real shortSummary content appears in the report text", text.contains("summary0"))
        assertTrue("the display title for each persona domain is included", text.contains("吃"))
    }

    @Test
    fun `generate raises OVER_BOUNDS when the reply is too long`() = runBlocking {
        val huge = "r".repeat(ProfileAffinityBounds.MAX_REPORT_CHARACTERS + 1)
        val e = try {
            analyzer(fakeChatReturning(huge)).generate("本地", "对方")
            fail("expected OVER_BOUNDS")
            null
        } catch (ex: ProfileAffinityException) {
            ex
        }
        assertNotNull(e)
        assertEquals(ProfileAffinityErrorReason.OVER_BOUNDS, e!!.reason)
    }

    // ------------------------------------------------------------------ empty/errors

    @Test
    fun `generate raises EMPTY_input when a profile is blank`() = runBlocking {
        val e = try {
            analyzer(fakeChatReturning("x")).generate("  ", "对方")
            fail("expected EMPTY_INPUT")
            null
        } catch (ex: ProfileAffinityException) {
            ex
        }
        assertEquals(ProfileAffinityErrorReason.EMPTY_INPUT, e!!.reason)
    }

    @Test
    fun `generate raises EMPTY when the model returns blank content`() = runBlocking {
        val e = try {
            analyzer(fakeChatReturning("   ")).generate("本地", "对方")
            fail("expected EMPTY")
            null
        } catch (ex: ProfileAffinityException) {
            ex
        }
        assertEquals(ProfileAffinityErrorReason.EMPTY, e!!.reason)
    }

    @Test
    fun `generate returns the report markdown on success`() = runBlocking {
        val report = "## 同频报告\n同频分 85"
        val result = analyzer(fakeChatReturning(report)).generate("本地", "对方")
        assertEquals(report, result.report)
        assertTrue(result.rawText == report)
    }

    @Test
    fun `HTTP error is mapped to a typed reason and secrets redacted`() = runBlocking {
        val chat = object : ChatClient {
            override suspend fun completion(r: ChatCompletionsRequest): ChatCompletionResult {
                throw ChatException(ChatErrorKind.HTTP, "invalid sk-planner-secret", httpCode = 401)
            }

            override fun completionStream(r: ChatCompletionsRequest): Flow<CloudStreamChunk> = emptyFlow()
        }
        val e = try {
            analyzer(chat).generate("本地", "对方")
            fail("expected ProfileAffinityException")
            null
        } catch (ex: ProfileAffinityException) {
            ex
        }
        assertEquals(ProfileAffinityErrorReason.HTTP, e!!.reason)
        assertEquals(401, e.httpCode)
        assertFalse(e.safeMessage.contains("sk-planner-secret"))
        assertFalse(e.safeMessage.contains("sk-"))
        assertTrue(e.userMessage.contains("HTTP"))
    }

    @Test
    fun `timeout is mapped to TIMEOUT with an honest user message`() = runBlocking {
        val chat = object : ChatClient {
            override suspend fun completion(r: ChatCompletionsRequest): ChatCompletionResult {
                throw ChatException(ChatErrorKind.TIMEOUT, "timed out")
            }

            override fun completionStream(r: ChatCompletionsRequest): Flow<CloudStreamChunk> = emptyFlow()
        }
        val e = try {
            analyzer(chat).generate("本地", "对方")
            fail("expected ProfileAffinityException")
            null
        } catch (ex: ProfileAffinityException) {
            ex
        }
        assertEquals(ProfileAffinityErrorReason.TIMEOUT, e!!.reason)
        assertTrue(e.userMessage.contains("超时"))
    }

    // ------------------------------------------------------------------ factory

    @Test
    fun `factory builds an analyzer wired to the request path`() = runBlocking {
        val result = ProfileAffinityResult("报告", "报告")
        var called = false
        val chat = object : ChatClient {
            override suspend fun completion(r: ChatCompletionsRequest): ChatCompletionResult {
                called = true
                return ChatCompletionResult(result.report, "stop", null)
            }

            override fun completionStream(r: ChatCompletionsRequest): Flow<CloudStreamChunk> = emptyFlow()
        }
        val analyzer = ProfileAffinityFactory.create(config, chat)
        val out = analyzer.generate("本地", "对方")
        assertTrue(called)
        assertEquals(result.report, out.report)
    }

    // ------------------------------------------------------------------ helpers

    private fun fakeChatReturning(content: String): ChatClient = object : ChatClient {
        override suspend fun completion(r: ChatCompletionsRequest): ChatCompletionResult =
            ChatCompletionResult(content = content, finishReason = "stop", raw = content)

        override fun completionStream(r: ChatCompletionsRequest): Flow<CloudStreamChunk> = emptyFlow()
    }
}

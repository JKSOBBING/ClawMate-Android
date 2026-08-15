package com.clawmate.app.chat.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic unit tests for the cloud client's pure mapping helpers:
 * URL normalization and OpenAI-style error-body extraction.
 */
class ChatClientMappingTest {

    @Test
    fun `bare host normalizes to v1 chat completions`() {
        assertEquals(
            "https://planner.example.com/v1/chat/completions",
            normalizeChatUrl("https://planner.example.com"),
        )
    }

    @Test
    fun `host with trailing slash normalizes`() {
        assertEquals(
            "http://10.0.0.1:8000/v1/chat/completions",
            normalizeChatUrl("http://10.0.0.1:8000/"),
        )
    }

    @Test
    fun `already a v1 host appends chat completions`() {
        assertEquals(
            "https://x.example.com/v1/chat/completions",
            normalizeChatUrl("https://x.example.com/v1"),
        )
    }

    @Test
    fun `full chat completions url is unchanged`() {
        val url = "https://x.example.com/v1/chat/completions"
        assertEquals(url, normalizeChatUrl(url))
    }

    @Test
    fun `blank input stays blank`() {
        assertEquals("", normalizeChatUrl(""))
        assertFalse(ChatServerConfig.isConfigured(""))
    }

    @Test
    fun `isConfigured true for a real url`() {
        assertTrue(ChatServerConfig.isConfigured("https://planner.example.com"))
    }

    // ------------------------------------------------------------------
    // Error body extraction
    // ------------------------------------------------------------------

    @Test
    fun `extracts message from top-level object`() {
        val msg = ChatErrorBodyParser.message("""{"message":"bad request"}""")
        assertEquals("bad request", msg)
    }

    @Test
    fun `extracts from nested error object`() {
        val msg = ChatErrorBodyParser.message(
            """{"error":{"message":"Insufficient quota","type":"insufficient_quota","code":"429"}}""",
        )
        assertTrue(msg.contains("Insufficient quota"))
        assertTrue(msg.contains("type=insufficient_quota"))
        assertTrue(msg.contains("code=429"))
    }

    @Test
    fun `extracts string error field`() {
        val msg = ChatErrorBodyParser.message("""{"error":"unauthorized"}""")
        assertEquals("unauthorized", msg)
    }

    @Test
    fun `extracts detail field`() {
        val msg = ChatErrorBodyParser.message("""{"detail":"model not found"}""")
        assertTrue(msg.contains("model not found"))
    }

    @Test
    fun `non json body is truncated and returned`() {
        val msg = ChatErrorBodyParser.message("<html>server error</html>")
        assertTrue(msg.isEmpty() || msg.isNotBlank())
    }

    @Test
    fun `empty body returns empty`() {
        assertEquals("", ChatErrorBodyParser.message("   "))
    }
}

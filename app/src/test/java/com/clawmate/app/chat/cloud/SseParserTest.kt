package com.clawmate.app.chat.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Deterministic unit tests for SSE parsing and stream-chunk mapping.
 *
 * Covers:
 *   - event framing ([SseEventParser]): multiple data lines, chunk boundaries
 *     split mid-line, CRLF, blank/ignored lines, final flush.
 *   - chunk mapping ([ChatStreamChunkParser]): content deltas, `[DONE]`,
 *     reasoning fields, malformed payloads.
 */
class SseParserTest {

    // ------------------------------------------------------------------
    // SseEventParser framing
    // ------------------------------------------------------------------

    @Test
    fun `greedy parse of a full multi-line stream`() {
        val parser = SseEventParser()
        val raw = "data: {\"choices\":[]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\ndata: [DONE]\n\n"
        val events = parser.push(raw)
        assertEquals(3, events.size)
        assertEquals("{\"choices\":[]}", events[0].data)
        assertEquals("{\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}", events[1].data)
        assertEquals("[DONE]", events[2].data)
    }

    @Test
    fun `push tolerates a line split across chunk boundaries`() {
        val parser = SseEventParser()
        val first = parser.push("data: {\"choices\":[{\"delta\":{\"co")
        assertTrue(first.isEmpty())
        val second = parser.push("ntent\":\"A\"}}]}\n\ndata: [DONE]\n\n")
        assertEquals(2, second.size)
        assertEquals("{\"choices\":[{\"delta\":{\"content\":\"A\"}}]}", second[0].data)
        assertEquals("[DONE]", second[1].data)
    }

    @Test
    fun `crlf line endings are normalised`() {
        val parser = SseEventParser()
        val events = parser.push("data: {\"choices\":[]}\r\ndata: [DONE]\r\n")
        assertEquals(2, events.size)
        assertEquals("[DONE]", events[1].data)
    }

    @Test
    fun `blank and non-data lines are ignored`() {
        val parser = SseEventParser()
        val events = parser.push(
            "event: message\n" +
                "id: 1\n" +
                ": comment\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n" +
                "\n" +
                "data: [DONE]\n",
        )
        assertEquals(2, events.size)
    }

    @Test
    fun `flush emits a trailing un-terminated data line`() {
        val parser = SseEventParser()
        assertTrue(parser.push("data: [DONE]").isEmpty())
        val flushed = parser.flush()
        assertEquals(1, flushed.size)
        assertEquals("[DONE]", flushed[0].data)
    }

    // ------------------------------------------------------------------
    // ChatStreamChunkParser
    // ------------------------------------------------------------------

    @Test
    fun `done sentinel yields done chunk`() {
        val chunk = ChatStreamChunkParser.parse("[DONE]")
        assertTrue(chunk.done)
        assertEquals("", chunk.contentDelta)
    }

    @Test
    fun `content delta is mapped`() {
        val chunk = ChatStreamChunkParser.parse(
            """{"choices":[{"delta":{"content":"Hi there"},"finish_reason":null}],"model":"m"}""",
        )
        assertFalse(chunk.done)
        assertEquals("Hi there", chunk.contentDelta)
    }

    @Test
    fun `finish reason is mapped`() {
        val chunk = ChatStreamChunkParser.parse(
            """{"choices":[{"delta":{"content":""},"finish_reason":"stop"}]}""",
        )
        assertEquals("stop", chunk.finishReason)
        assertEquals("", chunk.contentDelta)
    }

    @Test
    fun `empty choices yields empty chunk`() {
        val chunk = ChatStreamChunkParser.parse("""{"choices":[]}""")
        assertFalse(chunk.done)
        assertEquals("", chunk.contentDelta)
    }

    @Test
    fun `non-object choice is reported as a typed parse error`() {
        try {
            ChatStreamChunkParser.parse("""{"choices":[null]}""")
            fail("expected SseParserException")
        } catch (e: SseParserException) {
            assertTrue(e.message.orEmpty().contains("choice"))
        }
    }

    @Test
    fun `no choices key yields empty chunk`() {
        val chunk = ChatStreamChunkParser.parse("""{"id":"x"}""")
        assertEquals("", chunk.contentDelta)
    }

    @Test
    fun `empty payload yields empty chunk`() {
        val chunk = ChatStreamChunkParser.parse("   ")
        assertFalse(chunk.done)
        assertEquals("", chunk.contentDelta)
    }

    @Test
    fun `malformed json throws SseParserException`() {
        try {
            ChatStreamChunkParser.parse("data: {not json}")
            fail("expected SseParserException")
        } catch (e: SseParserException) {
            assertTrue(e.message.orEmpty().contains("JSON"))
        }
    }

    @Test
    fun `content delta accumulates across successive chunks`() {
        val p1 = ChatStreamChunkParser.parse("""{"choices":[{"delta":{"content":"Hel"}}]}""")
        val p2 = ChatStreamChunkParser.parse("""{"choices":[{"delta":{"content":"lo"}}]}""")
        val p3 = ChatStreamChunkParser.parse("""{"choices":[{"delta":{"content":" world"}}]}""")
        assertEquals("Hello world", p1.contentDelta + p2.contentDelta + p3.contentDelta)
    }
}

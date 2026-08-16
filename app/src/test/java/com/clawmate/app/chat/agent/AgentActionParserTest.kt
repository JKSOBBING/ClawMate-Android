package com.clawmate.app.chat.agent

import com.clawmate.app.chat.agent.AgentActionParser.AgentParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Deterministic tests for the pure action parser (port of
 * `AgentActionParser.ets`): JSON extraction from model text, terminal
 * detection, and parameter reading. No network / platform.
 */
class AgentActionParserTest {

    @Test
    fun `parse extracts action and parameters`() {
        val parsed = AgentActionParser.parse(
            """{"action":"open_app","parameters":{"app_name":"Calendar"}}""",
        )
        assertEquals("open_app", parsed.action)
        assertEquals("Calendar", AgentActionParser.getMapString(parsed.parameters, "app_name"))
    }

    @Test
    fun `parse unwraps nested wrapper payload`() {
        val parsed = AgentActionParser.parse(
            """{"response":{"content":{"action":"swipe","parameters":{"direction":"UP"}}}}""",
        )
        assertEquals("swipe", parsed.action)
        assertEquals("UP", AgentActionParser.getMapString(parsed.parameters, "direction"))
    }

    @Test
    fun `parse tolerates prose surrounding the json object`() {
        val parsed = AgentActionParser.parse(
            "Let me help.\n{\"action\":\"click\",\"parameters\":{\"coords\":[500,800]}}",
        )
        assertEquals("click", parsed.action)
        val coords = AgentActionParser.getNumberArray(parsed.parameters, "coords")
        assertEquals(listOf(500.0, 800.0), coords)
    }

    @Test
    fun `parse lowercases the action`() {
        val parsed = AgentActionParser.parse("""{"action":"DONE"}""")
        assertEquals("done", parsed.action)
        assertTrue(AgentActionParser.isTerminal(parsed.action))
    }

    @Test
    fun `terminal action names are detected`() {
        assertTrue(AgentActionParser.isTerminal("done"))
        assertTrue(AgentActionParser.isTerminal("stop"))
        assertTrue(AgentActionParser.isTerminal("terminate"))
        assertTrue(AgentActionParser.isTerminal("abort"))
        assertTrue(AgentActionParser.isTerminal(" STOP "))
        assertFalse(AgentActionParser.isTerminal("click"))
        assertFalse(AgentActionParser.isTerminal("swipe"))
    }

    @Test
    fun `parse throws when action is missing`() {
        try {
            AgentActionParser.parse("""{"foo":"bar"}""")
            fail("expected AgentParseException")
        } catch (e: AgentParseException) {
            assertTrue(e.message.orEmpty().contains("action"))
        }
    }

    @Test
    fun `parse throws on unparseable non-json`() {
        try {
            AgentActionParser.parse("this is not json at all")
            fail("expected AgentParseException")
        } catch (e: AgentParseException) {
            assertTrue(e.message.orEmpty().contains("JSON"))
        }
    }

    @Test
    fun `parameters falls back to top-level fields when empty`() {
        val parsed = AgentActionParser.parse(
            """{"action":"input","text":"hello","coordinates":{"x":1,"y":2}}""",
        )
        // `coordinates` is used as parameters when present.
        assertEquals(
            "1",
            AgentActionParser.getMapString(parsed.parameters, "x"),
        )
    }

    @Test
    fun `getNumberArray accepts nested single-element arrays`() {
        val parsed = AgentActionParser.parse(
            """{"action":"swipe","parameters":{"start_coords":[[10,20]],"end_coords":[30,40]}}""",
        )
        val start = AgentActionParser.getNumberArray(parsed.parameters, "start_coords")
        assertEquals(listOf(10.0, 20.0), start)
        val end = AgentActionParser.getNumberArray(parsed.parameters, "end_coords")
        assertEquals(listOf(30.0, 40.0), end)
    }
}

package local.meetingnotes.sdk

import org.junit.Assert.*
import org.junit.Test

class MeetingConfigurationTest {
    @Test fun roundTripPreservesHostPortsAndPrivateDirectories() {
        val config = MeetingConfiguration(18090, 18091, 2, "meeting", "weights", 30, 10)
        assertEquals(config, MeetingConfiguration.fromJson(config.toJson()))
        assertEquals("http://127.0.0.1:18090", config.baseUrl)
    }
    @Test fun rejectsPortCollisionThreadAbuseAndEscapingPrivateStorage() {
        for (body in listOf("""{"api_port":18081}""", """{"threads":99}""", """{"storage_directory":"../other"}""",
            """{"model_directory":"/sdcard/models"}""", """{"model_idle_seconds":0}""", """{"v":2}""")) {
            try { MeetingConfiguration.fromJson(body); fail(body) } catch (_: IllegalArgumentException) {}
        }
    }
}

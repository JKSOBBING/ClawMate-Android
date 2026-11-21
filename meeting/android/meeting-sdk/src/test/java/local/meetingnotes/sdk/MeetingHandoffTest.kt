package local.meetingnotes.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class MeetingHandoffTest {
    private fun envelope(text: String, revision: Int = 1): String {
        val source = JSONObject().put("namespace", "on-device-meeting-notes").put("instance_id", "a".repeat(32))
            .put("meeting_id", "b".repeat(32)).put("audio_id", JSONObject.NULL).put("revision", revision).put("source_hash", "c".repeat(64))
        val payload = JSONObject().put("v", 1).put("source", source).put("text", text).put("title", "会议")
            .put("created", "2026-10-26T08:00:00+00:00").put("selection", "both").toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return JSONObject().put("v", 1).put("export_id", hash).put("payload", payload).toString()
    }
    @Test fun immutablePayloadIsStableAndCarriesRevision() {
        val raw = envelope("人工稿 </script>\n后续行动")
        val handoff = MeetingHandoff.parse(raw)
        assertEquals(raw, handoff.toJson()); assertEquals(1, handoff.revision)
        assertNotEquals(handoff.exportId, MeetingHandoff.parse(envelope("人工稿", 2)).exportId)
    }
    @Test fun tamperingAndUnsupportedVersionsAreRejected() {
        val raw = JSONObject(envelope("草稿"))
        raw.put("payload", raw.getString("payload").replace("草稿", "篡改"))
        try { MeetingHandoff.parse(raw.toString()); fail() } catch (_: IllegalArgumentException) {}
        try { MeetingHandoff.parse(envelope(" ")); fail() } catch (_: IllegalArgumentException) {}
    }
}

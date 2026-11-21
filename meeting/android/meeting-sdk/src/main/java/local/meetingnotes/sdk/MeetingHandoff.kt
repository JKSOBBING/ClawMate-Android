package local.meetingnotes.sdk

import org.json.JSONObject
import java.security.MessageDigest

/** Verified immutable copy. The host owns its import receipt and destination storage. */
class MeetingHandoff private constructor(val exportId: String, val payload: String, private val value: JSONObject) {
    val title: String get() = value.getString("title")
    val text: String get() = value.getString("text")
    val sourceJson: String get() = value.getJSONObject("source").toString()
    val revision: Int get() = value.getJSONObject("source").getInt("revision")
    fun toJson(): String = JSONObject().put("v", 1).put("export_id", exportId).put("payload", payload).toString()

    companion object {
        fun parse(raw: String): MeetingHandoff {
            require(raw.length <= 2 * 1024 * 1024) { "导入内容过大" }
            val envelope = JSONObject(raw)
            require(envelope.getInt("v") == 1)
            val id = envelope.getString("export_id")
            require(Regex("[0-9a-f]{64}").matches(id))
            val payload = envelope.getString("payload")
            val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            require(digest == id) { "导入内容校验失败" }
            val value = JSONObject(payload)
            require(value.getInt("v") == 1)
            val source = value.getJSONObject("source")
            require(source.getString("namespace") == "on-device-meeting-notes")
            MeetingProtocol.id(source.getString("instance_id")); MeetingProtocol.id(source.getString("meeting_id"))
            if (!source.isNull("audio_id")) MeetingProtocol.id(source.getString("audio_id"))
            require(source.getInt("revision") >= 0 && Regex("[0-9a-f]{64}").matches(source.getString("source_hash")))
            require(value.getString("selection") in setOf("transcript", "summary", "both"))
            require(value.getString("title").length <= 300)
            require(Regex("^\\d{4}-\\d{2}-\\d{2}T").containsMatchIn(value.getString("created")))
            require(value.getString("text").isNotBlank() && value.getString("text").length <= 200000) { "导入内容为空或超过 20 万字符" }
            return MeetingHandoff(id, payload, value)
        }
    }
}

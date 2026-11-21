package local.meetingnotes.sdk

import org.json.JSONArray
import org.json.JSONObject

/** Latest segment revisions for pages which reconnect during a recording. */
internal class TranscriptCache {
    private val finals = JSONArray()
    private val qwen = JSONArray()
    private var audioId: String? = null
    private var progressive = false

    @Synchronized fun reset() {
        while (finals.length() > 0) finals.remove(0)
        while (qwen.length() > 0) qwen.remove(0)
        audioId = null
        progressive = false
    }

    @Synchronized fun accept(value: JSONObject) {
        when (value.optString("type")) {
            "ready" -> {
                audioId = value.optString("audio_id", "")
                progressive = value.optBoolean("progressive_refinement", false)
            }
            "final" -> finals.put(JSONObject(value.toString()))
            "qwen_final" -> qwen.put(JSONObject(value.toString()))
            "refined", "refinement_failed" -> {
                val index = value.optInt("segment_id", -1)
                if (index in 0 until finals.length() && value.optString("audio_id") == audioId) {
                    val row = finals.getJSONObject(index)
                    if (value.optInt("refinement_revision", 0) > row.optInt("refinement_revision", 0)) {
                        for (key in arrayOf("text", "firered_text", "refinement_status", "refinement_revision", "refinement_seconds")) {
                            if (value.has(key)) row.put(key, value.get(key))
                        }
                    }
                }
            }
            "speaker_update" -> {
                val index = value.optInt("segment_id", -1)
                if (index in 0 until finals.length()) {
                    val row = finals.getJSONObject(index)
                    for (key in value.keys()) if (key != "type") row.put(key, value.get(key))
                }
            }
        }
    }

    @Synchronized fun appendTo(snapshot: JSONObject): JSONObject = snapshot
        .put("audio_id", audioId)
        .put("progressive_refinement", progressive)
        .put("finals", JSONArray(finals.toString()))
        .put("qwen", JSONArray(qwen.toString()))
}

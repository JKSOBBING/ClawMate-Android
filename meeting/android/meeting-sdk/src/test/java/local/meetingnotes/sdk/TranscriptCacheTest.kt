package local.meetingnotes.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TranscriptCacheTest {
    private fun ready(cache: TranscriptCache) {
        cache.accept(JSONObject("""{"type":"ready","audio_id":"a","progressive_refinement":true}"""))
        cache.accept(JSONObject("""{"type":"final","segment_id":0,"text":"draft","start":1,"end":2,"speaker":"unknown","refinement_revision":0}"""))
    }
    private fun row(cache: TranscriptCache) = cache.appendTo(JSONObject()).getJSONArray("finals").getJSONObject(0)

    @Test fun reconnectGetsLatestRefinementAndSpeakerWithoutDuplicateSegments() {
        val cache = TranscriptCache()
        ready(cache)
        cache.accept(JSONObject("""{"type":"refined","audio_id":"a","segment_id":0,"text":"refined","refinement_revision":2,"speaker":"invalid","start":99}"""))
        cache.accept(JSONObject("""{"type":"speaker_update","segment_id":0,"speaker":"speaker_00"}"""))
        cache.accept(JSONObject("""{"type":"refined","audio_id":"a","segment_id":0,"text":"stale","refinement_revision":1}"""))
        cache.accept(JSONObject("""{"type":"refined","audio_id":"other","segment_id":0,"text":"wrong audio","refinement_revision":3}"""))
        val snapshot = cache.appendTo(JSONObject())
        assertEquals(1, snapshot.getJSONArray("finals").length())
        assertEquals("refined", row(cache).getString("text"))
        assertEquals("speaker_00", row(cache).getString("speaker"))
        assertEquals(1, row(cache).getInt("start"))
        assertEquals(2, row(cache).getInt("end"))
        assertEquals("final", row(cache).getString("type"))
    }

    @Test fun failedRefinementKeepsDraftAndSnapshotsCannotMutateCache() {
        val cache = TranscriptCache()
        ready(cache)
        cache.accept(JSONObject("""{"type":"refinement_failed","audio_id":"a","segment_id":0,"refinement_status":"failed","refinement_revision":1}"""))
        assertEquals("draft", row(cache).getString("text"))
        assertEquals("failed", row(cache).getString("refinement_status"))
        row(cache).put("text", "changed by page")
        assertEquals("draft", row(cache).getString("text"))
    }

    @Test fun newRecordingClearsPreviousAudioAndModelState() {
        val cache = TranscriptCache()
        ready(cache)
        cache.accept(JSONObject("""{"type":"qwen_final","text":"previous"}"""))
        cache.reset()
        val snapshot = cache.appendTo(JSONObject())
        assertTrue(snapshot.isNull("audio_id"))
        assertFalse(snapshot.getBoolean("progressive_refinement"))
        assertEquals(0, snapshot.getJSONArray("finals").length())
        assertEquals(0, snapshot.getJSONArray("qwen").length())
    }
}

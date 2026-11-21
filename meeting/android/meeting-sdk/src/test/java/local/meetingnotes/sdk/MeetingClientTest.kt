package local.meetingnotes.sdk

import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class MeetingClientTest {
    private val server = MockWebServer().apply { start() }
    private val client = MeetingClient("http://127.0.0.1:${server.port}")
    private val id = "a".repeat(32)
    @After fun close() { client.close(); server.shutdown() }

    @Test fun encodesQueryAndPreservesConflictWithoutRetry() {
        server.enqueue(MockResponse().setBody("{\"items\":[]}"))
        val args = JSONObject().put("query", JSONObject().put("q", "研发 & 周会"))
        assertEquals(200, client.request(MeetingOperation.HISTORY, args).result.get(5, TimeUnit.SECONDS).status)
        assertEquals("研发 & 周会", server.takeRequest().requestUrl!!.queryParameter("q"))
        server.enqueue(MockResponse().setResponseCode(409).setBody("{\"detail\":\"stale revision\"}"))
        try {
            client.request(MeetingOperation.SAVE_DOCUMENT, JSONObject().put("id", id).put("body", JSONObject().put("base_revision", 2)))
                .result.get(5, TimeUnit.SECONDS)
            fail("Expected conflict")
        } catch (error: ExecutionException) { assertEquals(409, (error.cause as MeetingApiException).status) }
        assertEquals("PUT", server.takeRequest().method)
        assertEquals(2, server.requestCount)
    }

    @Test fun rejectsRemoteEndpointsTraversalAndUnadvertisedQueries() {
        for (endpoint in listOf("https://example.com", "http://user:pass@127.0.0.1", "http://127.0.0.1/api")) {
            try { MeetingClient(endpoint); fail(endpoint) } catch (_: IllegalArgumentException) {}
        }
        try { client.request(MeetingOperation.MEETING, JSONObject().put("id", "../other")); fail() }
        catch (_: IllegalArgumentException) {}
        try { client.request(MeetingOperation.HEALTH, JSONObject().put("query", JSONObject().put("url", "remote"))); fail() }
        catch (_: IllegalArgumentException) {}
        assertEquals(0, server.requestCount)
    }

    @Test fun cancellingInFlightRequestDropsResultAndCloseRejectsNewWork() {
        server.enqueue(MockResponse().setBody("{}").setBodyDelay(2, TimeUnit.SECONDS))
        val request = client.request(MeetingOperation.HEALTH)
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        request.cancel()
        assertTrue(request.result.isCancelled)
        client.close()
        try { client.request(MeetingOperation.HEALTH); fail() } catch (_: IllegalStateException) {}
    }

    @Test fun uploadUsesOneStreamAndExportWritesToCallerOwnedDestination() {
        server.enqueue(MockResponse().setResponseCode(202).setBody("{\"status\":\"processing\"}"))
        var closed = false
        client.uploadAudio(id, "public.wav", {
            object : ByteArrayInputStream("public PCM fixture".toByteArray()) { override fun close() { closed = true; super.close() } }
        }).result.get(5, TimeUnit.SECONDS)
        val request = server.takeRequest()
        assertTrue(request.body.readUtf8().contains("public PCM fixture"))
        assertTrue(closed)
        server.enqueue(MockResponse().setBody("# meeting"))
        val destination = ByteArrayOutputStream()
        assertEquals(9L, client.export(id, "export.md", destination).result.get(5, TimeUnit.SECONDS))
        assertEquals("# meeting", destination.toString("UTF-8"))
    }

    @Test fun streamPreservesPcmAndLatestRefinementBeforeDone() {
        var received: okio.ByteString? = null
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                when(JSONObject(text).getString("type")) {
                    "start" -> webSocket.send("""{"type":"ready","audio_id":"a","progressive_refinement":true}""")
                    "stop" -> {
                        webSocket.send("""{"type":"final","segment_id":0,"text":"draft","start":0,"end":1,"speaker":"unknown"}""")
                        webSocket.send("""{"type":"refined","audio_id":"a","segment_id":0,"refinement_revision":1,"text":"refined"}""")
                        webSocket.send("""{"type":"speaker_update","segment_id":0,"speaker":"speaker_00"}""")
                        webSocket.send("""{"type":"done"}""")
                    }
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) { received = bytes }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        val stream = client.stream(id) {}
        stream.ready.get(5, TimeUnit.SECONDS)
        val pcm = ByteArray(3200) { (it % 127).toByte() }
        stream.sendPcm(pcm); stream.stop()
        stream.finished.get(5, TimeUnit.SECONDS)
        assertArrayEquals(pcm, received!!.toByteArray())
        val row = JSONObject(stream.snapshot()).getJSONArray("finals").getJSONObject(0)
        assertEquals("refined", row.getString("text")); assertEquals("speaker_00", row.getString("speaker"))
    }

    @Test fun uploadPreservesTheSelectedOfflineRefinementProfile() {
        server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
        client.uploadAudio(id, "public.wav", { ByteArrayInputStream(byteArrayOf(1, 2)) },
            refinementProfileId = "local-firered").result.get(5, TimeUnit.SECONDS)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, Regex("name=\"refinement_profile_id\"\\r\\n(?:[^\\r\\n]+\\r\\n)*\\r\\nlocal-firered\\r\\n").containsMatchIn(body))
        assertFalse(body.contains("local-sensevoice"))
    }
}

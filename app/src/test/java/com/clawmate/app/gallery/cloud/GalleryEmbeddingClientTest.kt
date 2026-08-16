package com.clawmate.app.gallery.cloud

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Device-free [GalleryEmbeddingClient] tests against a MockWebServer: request
 * URL/body, input bounding, response parsing, HTTP-error secret redaction,
 * empty-vector handling and response size / dimension bounds.
 */
class GalleryEmbeddingClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GalleryEmbeddingClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = GalleryEmbeddingClient(
            httpClient = OkHttpClient(),
            config = GalleryEmbeddingConfig(
                baseUrl = server.url("/").toString(),
                apiKey = "sk-real-secret",
                modelName = "text-embedding-3",
            ),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `embed hits v1_embeddings with auth and bounded input`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data":[{"embedding":[0.1,0.2,0.3],"index":0}]}"""),
        )

        val longText = "a".repeat(GalleryCloudBounds.MAX_EMBEDDING_TEXT_LENGTH + 1000)
        val result = client.embed(longText)

        assertEquals(3, result.vector.size)
        assertEquals("remote", result.mode)
        assertEquals("text-embedding-3", result.model)

        val recorded = server.takeRequest()
        // Host served base is server.url("/") which ends in "/" -> normalized with /v1/embeddings.
        assertTrue(recorded.path?.endsWith("/v1/embeddings") == true)
        assertEquals("Bearer sk-real-secret", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        // Input was clipped to the bound before serialization.
        assertTrue(body.contains("\"input\""))
    }

    @Test
    fun `input is clipped to bound before sending`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data":[{"embedding":[1.0],"index":0}]}"""),
        )
        client.embed("x".repeat(10_000))
        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        // The serialized JSON input is bounded: 512 chars of x + JSON overhead.
        val inputLen = com.clawmate.app.gallery.cloud.GalleryCloudBounds.MAX_EMBEDDING_TEXT_LENGTH
        assertTrue(body.length < 2_000)
        assertTrue(body.contains("\"input\":\"" + "x".repeat(inputLen)))
    }

    @Test
    fun `http error redacts secrets from message`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"invalid sk-real-secret bearer"}}"""),
        )
        val e = try {
            client.embed("hello")
            fail("expected GalleryCloudException")
            null
        } catch (ex: GalleryCloudException) {
            ex
        }
        assertTrue(e != null)
        assertEquals(GalleryCloudErrorReason.HTTP, e!!.reason)
        assertFalse("secret must not leak", e.safeMessage.contains("sk-real-secret"))
        assertFalse("secret must not leak", e.safeMessage.contains("sk-"))
    }

    @Test
    fun `empty data vector raises EMPTY and never fabricates`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data":[]}"""),
        )
        val e = try {
            client.embed("hello")
            fail("expected EMPTY")
            null
        } catch (ex: GalleryCloudException) {
            ex
        }
        assertTrue(e != null)
        assertEquals(GalleryCloudErrorReason.EMPTY, e!!.reason)
    }

    @Test
    fun `oversized embedding response is rejected`() = runBlocking {
        val huge = "0".repeat(GalleryCloudBounds.MAX_EMBEDDING_RESPONSE_BYTES) // well over body bound
        server.enqueue(
            MockResponse()
                .setBody("""{"data":[{"embedding":[${huge}]}]}"""),
        )
        val e = try {
            client.embed("hello")
            fail("expected OVER_BOUNDS")
            null
        } catch (ex: GalleryCloudException) {
            ex
        }
        assertTrue(e != null)
        assertEquals(GalleryCloudErrorReason.OVER_BOUNDS, e!!.reason)
    }

    @Test
    fun `over-long vector dimension is rejected`() = runBlocking {
        val big = (1..(GalleryCloudBounds.MAX_EMBEDDING_DIMENSIONS + 1)).joinToString(",") { "0.5" }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data":[{"embedding":[$big]}]}"""),
        )
        val e = try {
            client.embed("hello")
            fail("expected OVER_BOUNDS")
            null
        } catch (ex: GalleryCloudException) {
            ex
        }
        assertTrue(e != null)
        assertEquals(GalleryCloudErrorReason.OVER_BOUNDS, e!!.reason)
    }
}

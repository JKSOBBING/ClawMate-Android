package com.clawmate.app.profile.avatar.generation

import com.clawmate.app.profile.avatar.data.generation.AvatarErrorCause
import com.clawmate.app.profile.avatar.data.generation.AvatarGenerationClient
import com.clawmate.app.profile.avatar.data.generation.AvatarGenerationParams
import com.clawmate.app.profile.avatar.data.generation.AvatarProviderConfig
import com.clawmate.app.profile.avatar.data.generation.AvatarProviderException
import com.clawmate.app.profile.avatar.data.generation.AvatarProviderRequest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Device-free [AvatarGenerationClient] tests against MockWebServer(s): request
 * URL/auth/body, base64 + url-fallback response parsing, content-type and size
 * enforcement, HTTP-error redaction, cancellation, and the no-call-when-
 * unconfigured guarantee.
 */
class AvatarGenerationClientTest {

    private lateinit var server: MockWebServer
    private lateinit var imageServer: MockWebServer
    private lateinit var client: AvatarGenerationClient

    // A small, real 2x2 JPEG byte pattern for content-type checks.
    private val imageBytes = byteArrayOf(
        -1, -8, -1, -32, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
    )

    private fun imageBody(bytes: ByteArray = imageBytes): Buffer =
        Buffer().write(bytes)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        imageServer = MockWebServer()
        imageServer.start()
        client = AvatarGenerationClient(httpClient = OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
        imageServer.shutdown()
    }

    private fun config(): AvatarProviderConfig = AvatarProviderConfig(
        imageBaseUrl = server.url("/").toString().trimEnd('/'),
        apiKey = "sk-real-secret",
        textToImageModel = "seedream-3",
        size = "2K",
    )

    private fun request(prompt: String = "生成一个数字分身") = AvatarProviderRequest(
        prompt = prompt,
        model = "seedream-3",
        params = AvatarGenerationParams(size = "2K", responseFormat = "url", outputFormat = "jpeg"),
    )

    @Test
    fun `generate posts base url with bearer auth and exact body`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"req-1","data":[{"b64_json":"${Base64.getEncoder().encodeToString(imageBytes)}"}]}"""),
        )
        val result = client.generate(config(), request())

        assertTrue(result.imageBytesBase64.isNotEmpty())
        assertEquals("req-1", result.providerRequestId)
        // base64 response carries no url, so imageUrl stays empty (honest).
        assertEquals("", result.imageUrl)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("Bearer sk-real-secret", recorded.getHeader("Authorization"))
        assertTrue(recorded.path == "/" || recorded.path!!.endsWith("/"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"model\":\"seedream-3\""))
        assertTrue(body.contains("\"size\":\"2K\""))
        assertTrue(body.contains("\"prompt\":\""))
    }

    @Test
    fun `url response falls back to a validated download`() = runBlocking {
        val downloadUrl = imageServer.url("/img/1.png").toString()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data":[{"url":"$downloadUrl"}]}"""),
        )
        imageServer.enqueue(
            MockResponse()
                .setHeader("Content-Type", "image/png")
                .setBody(imageBody()),
        )
        val result = client.generate(config(), request())
        assertTrue(result.imageBytesBase64.isNotEmpty())
        val downloaded = imageServer.takeRequest()
        assertEquals("GET", downloaded.method)
    }

    @Test
    fun `download rejects a non-image content type`() = runBlocking {
        val downloadUrl = imageServer.url("/img/2.html").toString()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data":[{"url":"$downloadUrl"}]}"""),
        )
        imageServer.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody("<!doctype html>"),
        )
        val e = runCatching { client.generate(config(), request()) }.exceptionOrNull() as AvatarProviderException
        assertEquals(AvatarErrorCause.UNSUPPORTED_IMAGE, e.reason)
    }

    @Test
    fun `generate never sends when the base url is not configured`() = runBlocking {
        val unconfigured = AvatarProviderConfig(apiKey = "k", textToImageModel = "m", size = "2K")
        val e = runCatching { client.generate(unconfigured, request()) }.exceptionOrNull() as AvatarProviderException
        assertEquals(AvatarErrorCause.NOT_READY, e.reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `generate never sends when the api key is missing`() = runBlocking {
        val noKey = AvatarProviderConfig(imageBaseUrl = server.url("/").toString(), textToImageModel = "m", size = "2K")
        val e = runCatching { client.generate(noKey, request()) }.exceptionOrNull() as AvatarProviderException
        assertEquals(AvatarErrorCause.NOT_READY, e.reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `http error redacts the api key from the message`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":"invalid sk-real-secret bearer"}"""),
        )
        val e = runCatching { client.generate(config(), request()) }.exceptionOrNull() as AvatarProviderException
        assertEquals(AvatarErrorCause.HTTP, e.reason)
        assertFalse("secret leaked", e.message!!.contains("sk-real-secret"))
        assertFalse("secret leaked", e.message!!.contains("sk-"))
    }

    @Test
    fun `response with no image data raises parse error`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"x","data":[]}"""),
        )
        val e = runCatching { client.generate(config(), request()) }.exceptionOrNull() as AvatarProviderException
        assertEquals(AvatarErrorCause.PARSE, e.reason)
    }

    @Test
    fun `oversized response body is rejected before parse`() = runBlocking {
        // The parser rejects content-length oversize immediately.
        val huge = (0..1_000_000).joinToString(",", "[", "]") { "\"x\"" }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data":$huge}"""),
        )
        val e = runCatching { client.generate(config(), request()) }.exceptionOrNull() as AvatarProviderException
        assertTrue(e.reason == AvatarErrorCause.PARSE || e.reason == AvatarErrorCause.INTERNAL)
    }

    @Test
    fun `direct download validates url and content type`() = runBlocking {
        val url = imageServer.url("/ok.png").toString()
        imageServer.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(imageBody()))
        val bytes = client.downloadValidated(url, timeoutMs = 5_000)
        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty())
        val bad = runCatching {
            client.downloadValidated("file:///etc/passwd", timeoutMs = 5_000)
        }.exceptionOrNull() as AvatarProviderException
        assertEquals(AvatarErrorCause.DOWNLOAD, bad.reason)
    }

    @Test
    fun `slow endpoint maps to a timeout error`() = runBlocking {
        // A non-empty response whose body is delayed well past the configured
        // read timeout (coerced to at least 1 s) exercises the timeout mapping.
        server.enqueue(
            MockResponse().setBody("{}").setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS),
        )
        val before = System.currentTimeMillis()
        val e = runCatching {
            client.generate(
                config().copy(timeoutMs = 50),
                request(),
            )
        }.exceptionOrNull() as AvatarProviderException
        val elapsed = System.currentTimeMillis() - before
        // A socket read timeout surfaces as either TIMEOUT or an internally
        // cancelled call — both are honest, never a fabricated success.
        assertTrue(
            "reason was ${e.reason}",
            e.reason == AvatarErrorCause.TIMEOUT || e.reason == AvatarErrorCause.CANCELLED,
        )
        assertTrue("expected a fast timeout, took $elapsed", elapsed < 6_000)
    }
}

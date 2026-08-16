package com.clawmate.app.gallery.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource

/**
 * OpenAI-compatible `/v1/embeddings` client used for **remote** gallery text
 * embedding (migration row D1), ported from the HarmonyOS source:
 *   entry/src/main/ets/utils/CloudModelClient.ets createEmbedding / normalizeEmbeddingUrl
 *   entry/src/main/ets/utils/gallery/GalleryEmbeddingService.ets embedTextRemote
 *
 * It mirrors the wire contract of a compatible embeddings server
 * (`{"model": …, "input": …}` -> `{"data":[{"embedding":[…]}]}`), reusing the
 * same OkHttp + kotlinx.serialization stack as the existing chat client.
 *
 * Safety properties (all enforced and unit-tested):
 *  - Endpoint/model/key presence is guaranteed by [GalleryEmbeddingConfig]
 *    before this client is ever constructed — no call when not configured.
 *  - Request input is capped at [GalleryCloudBounds.MAX_EMBEDDING_TEXT_LENGTH]
 *    characters (port of Harmony's 512-char clamp).
 *  - Response body is capped at [GalleryCloudBounds.MAX_EMBEDDING_RESPONSE_BYTES]
 *    and the returned vector at [GalleryCloudBounds.MAX_EMBEDDING_DIMENSIONS].
 *  - Non-2xx HTTP responses and malformed replies raise [GalleryCloudException]
 *    whose message is scrubbed of secrets by [GalleryCloudErrorRedactor].
 *  - No response is ever fabricated: an empty `data` list raises EMPTY.
 */
class GalleryEmbeddingClient(
    private val httpClient: OkHttpClient,
    private val config: GalleryEmbeddingConfig,
) {
    private val json = embeddingJson
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Embeds [text] and returns a bounded [GalleryEmbeddingResult], or throws
     * [GalleryCloudException] with a redacted message.
     */
    suspend fun embed(text: String): GalleryEmbeddingResult = withContext(Dispatchers.IO) {
        val clipped = clip(text, GalleryCloudBounds.MAX_EMBEDDING_TEXT_LENGTH)
        if (clipped.isBlank()) {
            throw GalleryCloudException(
                reason = GalleryCloudErrorReason.EMPTY_INPUT,
                message = "Embedding input is blank",
            )
        }
        val body = json.encodeToString(EmbeddingRequest.serializer(), EmbeddingRequest(config.modelName, clipped))
            .toRequestBody(jsonMediaType)
        val httpRequest = Request.Builder()
            .url(config.embeddingsUrl)
            .post(body)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + config.apiKey)
            .build()

        try {
            val call = httpClient.newCall(httpRequest)
            call.execute().use { response ->
                val source = response.body?.source()
                if (source == null) {
                    throw GalleryCloudException(
                        reason = GalleryCloudErrorReason.EMPTY,
                        message = "Embedding response has no body",
                    )
                }
                val bytes = readBodyBounded(source, GalleryCloudBounds.MAX_EMBEDDING_RESPONSE_BYTES.toLong())
                if (!response.isSuccessful) {
                    throw GalleryCloudException(
                        reason = GalleryCloudErrorReason.HTTP,
                        httpCode = response.code,
                        message = GalleryCloudErrorRedactor.redact(
                            "Embedding HTTP ${response.code}: $bytes",
                        ),
                    )
                }
                mapResponse(response.code, bytes)
            }
        } catch (e: GalleryCloudException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw GalleryCloudException(GalleryCloudErrorReason.IO, "Embedding request timed out", cause = e)
        } catch (e: java.io.IOException) {
            throw GalleryCloudException(GalleryCloudErrorReason.IO, "Embedding network error", cause = e)
        }
    }

    private fun mapResponse(code: Int, text: String): GalleryEmbeddingResult {
        val parsed = try {
            json.decodeFromString(EmbeddingResponse.serializer(), text)
        } catch (e: Exception) {
            throw GalleryCloudException(
                reason = GalleryCloudErrorReason.MALFORMED,
                httpCode = if (code in 200..299) null else code,
                message = GalleryCloudErrorRedactor.redact("Embedding parse failed: ${e.message}"),
                cause = e,
            )
        }
        if (parsed.data.isEmpty()) {
            throw GalleryCloudException(
                reason = GalleryCloudErrorReason.EMPTY,
                message = "Embedding response has no vectors",
            )
        }
        val vector = parsed.data[0].embedding
        if (vector.size > GalleryCloudBounds.MAX_EMBEDDING_DIMENSIONS) {
            throw GalleryCloudException(
                reason = GalleryCloudErrorReason.OVER_BOUNDS,
                message = "Embedding dimension ${vector.size} exceeds bound "
                    + GalleryCloudBounds.MAX_EMBEDDING_DIMENSIONS,
            )
        }
        return GalleryEmbeddingResult.remote(vector.toList(), config.modelName)
    }

    /** Reads at most [maxBytes] bytes of [source]; rejects anything larger. */
    private fun readBodyBounded(source: BufferedSource, maxBytes: Long): String {
        val sink = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = source.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) {
                throw GalleryCloudException(
                    reason = GalleryCloudErrorReason.OVER_BOUNDS,
                    message = "Embedding response exceeds size bound $maxBytes",
                )
            }
            sink.write(buffer, 0, read)
        }
        return sink.toByteArray().decodeToString()
    }

    private fun clip(text: String, max: Int): String {
        if (text.length <= max) return text
        return text.substring(0, max)
    }
}

@Serializable
internal data class EmbeddingRequest(
    val model: String,
    val input: String,
)

@Serializable
internal data class EmbeddingResponse(
    val data: List<EmbeddingData> = emptyList(),
)

@Serializable
internal data class EmbeddingData(
    val embedding: List<Double> = emptyList(),
    @SerialName("index") val index: Int = 0,
)

private val embeddingJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

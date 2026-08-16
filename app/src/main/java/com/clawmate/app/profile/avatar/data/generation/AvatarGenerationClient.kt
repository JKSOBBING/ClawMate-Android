package com.clawmate.app.profile.avatar.data.generation

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Production OkHttp client for the configured Seedream avatar generation,
 * ported from `SeedreamAvatarProvider.ets` and hardened for Android.
 *
 * Threading/cancellation: every call runs on [Dispatchers.IO]; the enclosing
 * coroutine's cancellation cancels the live [Call] so an interrupted
 * generate/download never leaks a connection. Timeouts come from the injected
 * [OkHttpClient]; the production provider
 * ([SeedreamAvatarProvider.withDefaultClient]) configures a 10 s connect /
 * 60 s read / 30 s write timeout matching the source defaults
 * (`connectTimeout: 10000`, `readTimeout: timeoutMs`).
 *
 * Byte bounds: the JSON response body is read with [AvatarProviderLimits
 * .MAX_RESPONSE_BODY_BYTES], a base64 `b64_json` payload is decoded only through
 * [SeedreamResponseParser.decodeBoundedBase64] and [AvatarProviderLimits
 * .MAX_IMAGE_BYTES], and a downloaded provider image is both content-type and
 * size gated before any bytes are handed back.
 *
 * Security: a configured api key travels only in the `Authorization: Bearer`
 * header and never appears in any message/error. No secrets are logged.
 *
 * This client is deliberately **stateless and configuration-injected**: it
 * holds no credentials and performs a call only when called with a validated
 * [AvatarProviderConfig].
 */
class AvatarGenerationClient(
    private val httpClient: OkHttpClient,
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * Build a [Call] from an OkHttpClient that carries a read timeout matching
     * [timeoutMs] (mirrors the source `readTimeout: config.timeoutMs`). Uses
     * [httpClient]'s pools/settings and only overrides the read timeout so a
     * misconfigured-yet-required timeout is honoured without mutating the shared
     * client.
     */
    private fun newCall(request: Request, timeoutMs: Long): Call {
        val effective = timeoutMs.coerceAtLeast(1_000L)
        val callClient = httpClient.newBuilder()
            .readTimeout(effective, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        return callClient.newCall(request)
    }
    /**
     * Full generate orchestration mirroring Harmony `generate()`:
     * POST to base URL → parse response → prefer bounded `b64_json`, fall back to
     * a validated URL download, else error "no image data".
     * Rejects an unconfigured base URL / api key / model / prompt **before** any
     * request is built (no network call when unconfigured).
     */
    suspend fun generate(
        config: AvatarProviderConfig,
        request: AvatarProviderRequest,
    ): AvatarProviderResult = withContext(Dispatchers.IO) {
        val baseCheck = AvatarUrlValidator.validateBaseUrl(config.imageBaseUrl)
        if (!baseCheck.valid) {
            throw AvatarProviderException(AvatarErrorCause.NOT_READY, "Seedream 图片服务 URL 未配置")
        }
        val apiKey = config.apiKey.trim()
        if (apiKey.isEmpty()) {
            throw AvatarProviderException(AvatarErrorCause.NOT_READY, "Seedream API Key 未配置")
        }

        val bodyJson = SeedreamRequestBodyBuilder.build(config, request)
            ?: throw AvatarProviderException(
                AvatarErrorCause.INVALID_ARGUMENT,
                "Seedream 生成请求不可用（提示词或模型不完整，或提示词超出上限）",
            )

        val body = bodyJson.toRequestBody(jsonMedia)
        val httpRequest = Request.Builder()
            .url(baseCheck.normalized)
            .post(body)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .build()
        val call = newCall(httpRequest, config.timeoutMs)
        val raw = executeJson(call)

        val parsed = SeedreamResponseParser.parse(raw)
        var imageBase64 = parsed.imageBase64
        if (imageBase64.isEmpty() && parsed.imageUrl.isNotEmpty()) {
            val bytes = downloadValidated(parsed.imageUrl, config.timeoutMs)
            imageBase64 = java.util.Base64.getEncoder().encodeToString(bytes)
        }

        // Re-decode the final chosen payload through the bounded decoder so the
        // caller receives validated bytes (never fabricated, never oversized).
        val imageBytes = if (imageBase64.isNotEmpty()) {
            SeedreamResponseParser.decodeBoundedBase64(imageBase64)
                ?: throw AvatarProviderException(
                    AvatarErrorCause.UNSUPPORTED_IMAGE,
                    "Seedream 响应中的图片数据无效或超出大小上限",
                )
        } else {
            throw AvatarProviderException(
                AvatarErrorCause.PARSE,
                "Seedream 响应没有图片数据",
            )
        }
        val metadata = LinkedHashMap<String, String>()
        metadata["raw"] = raw.take(AvatarProviderLimits.MAX_RESPONSE_BODY_BYTES)
        if (parsed.providerRequestId.isNotEmpty()) metadata["id"] = parsed.providerRequestId
        if (parsed.imageUrl.isNotEmpty()) metadata["image_url"] = parsed.imageUrl
        AvatarProviderResult(
            imageBytesBase64 = java.util.Base64.getEncoder().encodeToString(imageBytes),
            imageUrl = parsed.imageUrl,
            providerRequestId = parsed.providerRequestId,
            rawMetadata = metadata,
        )
    }

    /** Bind a validated, content-type + size gated image download to bytes. */
    suspend fun downloadValidated(url: String, timeoutMs: Long): ByteArray {
        val urlCheck = AvatarUrlValidator.validateDownloadUrl(url)
        if (!urlCheck.valid) {
            throw AvatarProviderException(AvatarErrorCause.DOWNLOAD, "Seedream 图片下载 URL 无效")
        }
        val httpRequest = Request.Builder().url(urlCheck.normalized).get().build()
        val call = newCall(httpRequest, timeoutMs)
        return withContext(Dispatchers.IO) {
            try {
                val response = call.execute()
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        throw AvatarProviderException(
                            AvatarErrorCause.DOWNLOAD,
                            "Seedream 图片下载 HTTP ${resp.code}",
                        )
                    }
                    val contentType = resp.header("Content-Type")
                    if (!AvatarUrlValidator.acceptedContentType(contentType)) {
                        throw AvatarProviderException(
                            AvatarErrorCause.UNSUPPORTED_IMAGE,
                            AvatarUrlValidator.contentTypeReason(contentType),
                        )
                    }
                    val body = resp.body ?: throw AvatarProviderException(
                        AvatarErrorCause.DOWNLOAD, "Seedream 图片下载响应为空",
                    )
                    val total = body.contentLength()
                    if (total > AvatarProviderLimits.MAX_IMAGE_BYTES) {
                        throw AvatarProviderException(
                            AvatarErrorCause.UNSUPPORTED_IMAGE,
                            "Seedream 图片下载超出大小上限",
                        )
                    }
                    val bytes = body.bytes()
                    if (bytes.size <= 0 || bytes.size > AvatarProviderLimits.MAX_IMAGE_BYTES) {
                        throw AvatarProviderException(
                            AvatarErrorCause.UNSUPPORTED_IMAGE,
                            "Seedream 图片下载超出大小上限",
                        )
                    }
                    bytes
                }
            } catch (e: CancellationException) {
                call.cancel()
                throw e
            } catch (e: IOException) {
                call.cancel()
                throw mapIOException(e, call)
            }
        }
    }

    private suspend fun executeJson(call: Call): String = withContext(Dispatchers.IO) {
        try {
            val response = call.execute()
            response.use { resp ->
                val body = resp.body
                if (!resp.isSuccessful) {
                    // Read + discard the (bounded) error body; never log or leak it.
                    body?.byteStream()?.use { input ->
                        input.readNBytes(AvatarProviderLimits.MAX_RESPONSE_BODY_BYTES)
                    }
                    throw AvatarProviderException(
                        AvatarErrorCause.HTTP,
                        "Seedream HTTP ${resp.code}",
                    )
                }
                val contentLength = body?.contentLength() ?: -1L
                if (contentLength > AvatarProviderLimits.MAX_RESPONSE_BODY_BYTES) {
                    throw AvatarProviderException(
                        AvatarErrorCause.PARSE,
                        "Seedream 响应体超出大小上限",
                    )
                }
                val source = body?.byteStream() ?: throw AvatarProviderException(
                    AvatarErrorCause.PARSE, "Seedream 响应体为空",
                )
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    ensureActive()
                    val n = source.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > AvatarProviderLimits.MAX_RESPONSE_BODY_BYTES) {
                        throw AvatarProviderException(
                            AvatarErrorCause.PARSE, "Seedream 响应体超出大小上限",
                        )
                    }
                    out.write(buf, 0, n)
                }
                out.toString(Charsets.UTF_8)
            }
        } catch (e: AvatarProviderException) {
            throw e
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        } catch (e: IOException) {
            call.cancel()
            throw mapIOException(e, call)
        }
    }

    private fun mapIOException(e: IOException, call: Call): AvatarProviderException {
        if (call.isCanceled()) {
            return AvatarProviderException(AvatarErrorCause.CANCELLED, "生成已取消", e)
        }
        val cause = if (e is java.net.SocketTimeoutException) {
            AvatarErrorCause.TIMEOUT
        } else {
            AvatarErrorCause.INTERNAL
        }
        return AvatarProviderException(
            cause,
            if (cause == AvatarErrorCause.TIMEOUT) "Seedream 请求超时" else "Seedream 网络错误",
            e,
        )
    }
}

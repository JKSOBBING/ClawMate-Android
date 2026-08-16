package com.clawmate.app.chat.cloud

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI-compatible `/v1/chat/completions` client backed by OkHttp.
 *
 * Ports the HarmonyOS `utils/CloudModelClient.ets` (chatCompletion and the SSE
 * chatCompletionStream path) to Android. Supports:
 *   - non-streaming completion via [completion]
 *   - **SSE streaming** surfaced as a cold [Flow] in [completionStream], so the
 *     ViewModel can launch it in a coroutine and cancel it (coroutine
 *     cancellation is checked between lines, closing the underlying HTTP
 *     connection).
 *   - connect/read timeouts
 *   - HTTP status errors and OpenAI-style error-body extraction
 *   - malformed SSE events surfaced as [ChatException] with
 *     [ChatErrorKind.MALFORMED_SSE].
 *
 * Endpoint + credentials live in [ChatServerConfig]; no secrets are hard-coded
 * here. The chunk/error parsers are pure, separately-tested units.
 */
class ChatCompletionsClient(
    private val httpClient: OkHttpClient,
    private val config: ChatServerConfig,
) : ChatClient {
    private val json = WireJson.json
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    val baseUrl: String get() = config.completionsUrl

    // ---------------------------------------------------------------------
    // Non-streaming completion
    // ---------------------------------------------------------------------

    /** Executes a non-streaming completion and maps the response. */
    override suspend fun completion(request: ChatCompletionsRequest): ChatCompletionResult =
        withContext(Dispatchers.IO) {
            val body = buildBody(request, stream = false)
            val httpRequest = buildHttpRequest(body)
            val call = httpClient.newCall(httpRequest)
            try {
                val response = call.execute()
                response.use { resp ->
                    val text = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        throw ChatException(
                            kind = ChatErrorKind.HTTP,
                            httpCode = resp.code,
                            message = buildHttpErrorMessage(resp.code, text),
                        )
                    }
                    mapCompletionResponse(resp.code, text)
                }
            } catch (e: ChatException) {
                throw e
            } catch (e: CancellationException) {
                call.cancel()
                throw e
            } catch (e: IOException) {
                call.cancel()
                throw if (call.isCanceled()) e else mapIOException(e)
            }
        }

    // ---------------------------------------------------------------------
    // SSE streaming
    // ---------------------------------------------------------------------

    /**
     * Streams a completion over SSE as a cold [Flow] emitting [CloudStreamChunk]
     * deltas. The flow is cancelled by the collector (e.g. the ViewModel),
     * which closes the underlying connection via [Call.cancel]. Reading is
     * cancellable between SSE lines. A terminal `[DONE]` (done chunk) completes
     * the flow normally.
     */
    override fun completionStream(request: ChatCompletionsRequest): Flow<CloudStreamChunk> = flow {
        val body = buildBody(request, stream = true)
        val httpRequest = buildHttpRequest(body)
        val call = httpClient.newCall(httpRequest)

        val parser = SseEventParser()
        var finished = false

        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                val errorText = response.body?.string() ?: ""
                throw ChatException(
                    kind = ChatErrorKind.HTTP,
                    httpCode = response.code,
                    message = buildHttpErrorMessage(response.code, errorText),
                )
            }
            response.use {
                val body0 = it.body ?: throw ChatException(
                    ChatErrorKind.IO, "Empty SSE response body",
                )
                val source = body0.source()
                while (!finished) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    parser.push(line + "\n").forEach { event ->
                        val chunk = try {
                            ChatStreamChunkParser.parse(event.data)
                        } catch (e: SseParserException) {
                            throw ChatException(
                                kind = ChatErrorKind.MALFORMED_SSE,
                                message = e.message ?: "Malformed SSE event",
                                cause = e,
                            )
                        }
                        emit(chunk)
                        if (chunk.done) finished = true
                    }
                }
            }
        } catch (e: ChatException) {
            throw e
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        } catch (e: IOException) {
            call.cancel()
            throw if (call.isCanceled()) CancellationException("stream cancelled") else mapIOException(e)
        } finally {
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private fun buildBody(request: ChatCompletionsRequest, stream: Boolean): RequestBody {
        val wire = request.copy(stream = stream)
        return json.encodeToString(ChatCompletionsRequest.serializer(), wire)
            .toRequestBody(jsonMediaType)
    }

    private fun buildHttpRequest(body: RequestBody): Request {
        val builder = Request.Builder()
            .url(config.completionsUrl)
            .post(body)
            .header("Content-Type", "application/json")
        val apiKey = config.apiKey
        builder.header("Authorization", "Bearer " + if (apiKey.isBlank()) "EMPTY" else apiKey)
        return builder.build()
    }

    private fun mapCompletionResponse(code: Int, text: String): ChatCompletionResult {
        val parsed = try {
            json.decodeFromString(ChatCompletionsResponse.serializer(), text)
        } catch (e: Exception) {
            throw ChatException(
                kind = ChatErrorKind.MALFORMED_SSE,
                httpCode = if (code in 200..299) code else null,
                message = "Response JSON parse failed: ${e.message}",
                cause = e,
            )
        }
        val choice = parsed.choices.firstOrNull()
            ?: throw ChatException(
                ChatErrorKind.MALFORMED_SSE,
                "Response has no choices",
            )
        return ChatCompletionResult(
            content = choice.message?.content ?: "",
            finishReason = choice.finishReason ?: "",
            raw = text,
            toolCalls = choice.message?.toolCalls ?: emptyList(),
        )
    }

    private fun mapIOException(e: IOException): ChatException =
        when (e) {
            is java.net.SocketTimeoutException -> ChatException(
                ChatErrorKind.TIMEOUT, "Request timed out", cause = e,
            )
            else -> ChatException(ChatErrorKind.IO, "Network error", cause = e)
        }

    private fun buildHttpErrorMessage(code: Int, bodyText: String): String {
        val extracted = ChatErrorBodyParser.message(bodyText)
        return if (extracted.isNotEmpty()) "HTTP $code: $extracted" else "HTTP $code: request failed"
    }
}

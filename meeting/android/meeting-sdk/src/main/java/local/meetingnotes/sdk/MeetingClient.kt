package local.meetingnotes.sdk

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class MeetingResponse(val status: Int, val payload: String)
class MeetingApiException(val status: Int, val detail: String) : IOException("HTTP $status: $detail")

class MeetingRequest<T> internal constructor(val result: CompletableFuture<T>, private val call: Call) : Closeable {
    fun cancel() { result.cancel(false); call.cancel() }
    override fun close() = cancel()
}

/** All HTTP work is asynchronous. Completion callbacks execute on a worker thread. */
class MeetingClient(baseUrl: String = MeetingSdk.baseUrl) : Closeable {
    private val base = MeetingProtocol.endpoint(baseUrl)
    private val transport = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        // The embedded server closes idle HTTP connections after five seconds.
        // Expire our pool earlier; write requests still must never be replayed.
        .connectionPool(ConnectionPool(5, 2, TimeUnit.SECONDS))
        .followRedirects(false).followSslRedirects(false).build()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val streams = ConcurrentHashMap.newKeySet<MeetingPcmStream>()
    private val closed = AtomicBoolean(false)

    fun request(operation: MeetingOperation, args: JSONObject = JSONObject()): MeetingRequest<MeetingResponse> {
        val builder = Request.Builder().url(MeetingProtocol.url(base, operation, args))
        val body = args.optJSONObject("body")?.toString() ?: "{}"
        if (operation.method != "GET") builder.method(operation.method, body.toRequestBody(JSON))
        return enqueue(builder.build()) { response ->
            require((response.body?.contentLength() ?: 0) <= MAX_JSON) { "会议响应过大，请分页查询" }
            val source = response.body?.source() ?: throw IOException("会议接口未返回正文")
            source.request(MAX_JSON + 1)
            require(source.buffer.size <= MAX_JSON) { "会议响应过大，请分页查询" }
            val bytes = source.readByteArray()
            MeetingResponse(response.code, bytes.toString(Charsets.UTF_8))
        }
    }

    fun request(operation: String, args: JSONObject = JSONObject()): MeetingRequest<MeetingResponse> =
        request(MeetingOperation.valueOf(operation.uppercase()), args)

    /** Source is opened on a worker and closed after one bounded upload; never buffered in memory. */
    fun uploadAudio(meetingId: String, filename: String, open: () -> InputStream,
                    model: String = "sensevoice",
                    refinementProfileId: String = if (model == "qwen3_asr") "local-qwen" else "local-sensevoice"): MeetingRequest<MeetingResponse> {
        require(model in setOf("sensevoice", "qwen3_asr"))
        require(Regex("[A-Za-z0-9_-]{1,100}").matches(refinementProfileId))
        require(filename.length in 1..160 && '\r' !in filename && '\n' !in filename)
        val audio = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                open().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= 128L * 1024 * 1024) { "音频超过 128 MiB" }
                        sink.write(buffer, 0, n)
                    }
                }
            }
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, audio).addFormDataPart("offline_model", model)
            .addFormDataPart("compare", "false").addFormDataPart("refinement_profile_id", refinementProfileId).build()
        val url = base.newBuilder().addPathSegments("api/v1/meetings/${MeetingProtocol.id(meetingId)}/audio").build()
        return enqueue(Request.Builder().url(url).post(body).build()) { response ->
            MeetingResponse(response.code, response.body?.string() ?: "{}")
        }
    }

    /** Caller owns the output stream (e.g. a Storage Access Framework destination). */
    fun export(meetingId: String, format: String, output: OutputStream, audioId: String? = null): MeetingRequest<Long> {
        require(format in setOf("export", "export.md", "audio"))
        val url = base.newBuilder().addPathSegments("api/v1/meetings/${MeetingProtocol.id(meetingId)}/$format")
        if (audioId != null) url.addQueryParameter("audio_id", MeetingProtocol.id(audioId))
        return enqueue(Request.Builder().url(url.build()).build()) { response ->
            response.body!!.byteStream().use { it.copyTo(output) }
        }
    }

    fun stream(meetingId: String, listener: (String) -> Unit): MeetingPcmStream {
        check(!closed.get()) { "会议客户端已关闭" }
        val stream = MeetingPcmStream(transport, MeetingProtocol.streamUrl(base, meetingId), listener)
        streams.add(stream)
        stream.terminated.whenComplete { _, _ -> streams.remove(stream) }
        if (closed.get()) stream.close() else stream.connect()
        return stream
    }

    private fun <T> enqueue(request: Request, read: (Response) -> T): MeetingRequest<T> {
        check(!closed.get()) { "会议客户端已关闭" }
        val call = transport.newCall(request)
        val future = CompletableFuture<T>()
        calls.add(call)
        future.whenComplete { _, _ -> if (future.isCancelled) call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { calls.remove(call); future.completeExceptionally(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) {
                            val source = it.body?.source()
                            source?.request(8192)
                            val detail = source?.readByteArray(minOf(source.buffer.size, 8192))?.toString(Charsets.UTF_8) ?: ""
                            throw MeetingApiException(it.code, detail)
                        }
                        future.complete(read(it))
                    }
                } catch (error: Exception) { future.completeExceptionally(error) }
                finally { calls.remove(call) }
            }
        })
        if (closed.get()) { call.cancel(); future.cancel(false) }
        return MeetingRequest(future, call)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        calls.forEach { it.cancel() }; streams.toList().forEach { it.close() }
        transport.dispatcher.executorService.shutdown()
        transport.connectionPool.evictAll()
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_JSON = 4L * 1024 * 1024
    }
}

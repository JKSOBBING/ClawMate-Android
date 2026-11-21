package local.meetingnotes.sdk

import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** PCM remains in Kotlin; only structured events cross the UI bridge. */
class MeetingPcmStream internal constructor(
    private val client: OkHttpClient, private val url: String, private val listener: (String) -> Unit,
) : Closeable {
    val ready = CompletableFuture<Unit>()
    val finished = CompletableFuture<Unit>()
    internal val terminated = CompletableFuture<Unit>()
    private val closed = AtomicBoolean(false)
    private val transcript = TranscriptCache()
    @Volatile private var socket: WebSocket? = null

    internal fun connect() {
        if (closed.get()) return
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed.get()) { webSocket.cancel(); return }
                webSocket.send("""{"type":"start","sample_rate":16000,"channels":1,"format":"pcm_s16le"}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (closed.get()) return
                try {
                    val event = MeetingProtocol.event(text)
                    transcript.accept(event)
                    if (event.optString("type") == "ready") ready.complete(Unit)
                    listener(text)
                    when (event.optString("type")) {
                        "done" -> { finished.complete(Unit); closed.set(true); webSocket.close(1000, "completed") }
                        "error" -> fail(IOException(event.optString("message")))
                    }
                } catch (error: Exception) { fail(error) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = fail(t)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!finished.isDone) fail(IOException("录音连接已关闭：$code $reason"))
                terminated.complete(Unit)
            }
        })
        if (closed.get()) socket?.cancel()
    }

    fun sendPcm(bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size % 2 == 0 && bytes.size <= 32000) { "需要 16 kHz 单声道 PCM16 帧" }
        check(ready.isDone && !ready.isCompletedExceptionally && !closed.get()) { "录音连接尚未就绪或已关闭" }
        val ws = socket ?: error("录音连接不存在")
        check(ws.queueSize() <= 640000) { "实时识别积压" }
        check(ws.send(bytes.toByteString())) { "录音连接已关闭" }
    }

    fun pause() = control("pause")
    fun resume() = control("resume")
    fun ping() = control("ping")
    fun stop() = control("stop")
    fun snapshot(): String = transcript.appendTo(JSONObject().put("v", 1)).toString()
    private fun control(type: String) {
        check(ready.isDone && !closed.get()) { "录音连接尚未就绪或已关闭" }
        check(socket?.send(JSONObject().put("type", type).toString()) == true)
    }
    private fun fail(error: Throwable) {
        closed.set(true); ready.completeExceptionally(error); finished.completeExceptionally(error); socket?.cancel(); terminated.complete(Unit)
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            val error = IOException("客户端已断开，已保存音频可通过恢复接口处理")
            ready.completeExceptionally(error); finished.completeExceptionally(error)
        }
        socket?.cancel(); terminated.complete(Unit)
    }
}

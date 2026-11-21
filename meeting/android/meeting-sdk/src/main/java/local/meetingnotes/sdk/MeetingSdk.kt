package local.meetingnotes.sdk

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.Closeable
import java.io.File

enum class MeetingEnginePhase { STOPPED, STARTING, READY, FAILED }

data class MeetingEngineState(val phase: MeetingEnginePhase, val error: String? = null)

fun interface MeetingEventListener {
    /** Version 1 JSON events, delivered on Android's main thread. */
    fun onEvent(event: String)
}

/** In-process API. The host owns permissions and UI; the service owns capture. */
object MeetingSdk {
    const val BASE_URL = "http://127.0.0.1:18080"
    const val PAGE_URL = "$BASE_URL/"
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val events = EventSubscriptions(
        dispatch = { callback -> main.post { callback() } },
        onFailure = { error -> Log.e("MeetingSdk", "Event listener failed", error) },
    )
    @Volatile private var engine = MeetingEngineState(MeetingEnginePhase.STOPPED)
    @Volatile private var configuration = MeetingConfiguration()
    private var processConfiguration: MeetingConfiguration? = null
    val baseUrl: String get() = configuration.baseUrl
    val pageUrl: String get() = "$baseUrl/"
    fun getConfiguration(): MeetingConfiguration = configuration

    /** Call from a visible host screen. Repeated calls reuse the same service. */
    @Synchronized fun initialize(context: Context, config: MeetingConfiguration? = null) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("meeting-sdk", Context.MODE_PRIVATE)
        val previous = prefs.getString("config", null)
        val selected = config ?: processConfiguration ?: previous?.let { MeetingConfiguration.fromJson(it) } ?: MeetingConfiguration()
        check(processConfiguration == null || selected == processConfiguration) { "运行中不能改变端口或模型目录，请重启应用进程" }
        configuration = selected; processConfiguration = selected
        check(prefs.edit().putString("config", selected.toJson()).commit()) { "无法保存会议运行配置" }
        val intent = Intent(app, BackendService::class.java)
        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
    }

    fun getEngineState(): MeetingEngineState = engine

    @Synchronized internal fun restoreConfiguration(context: Context): MeetingConfiguration {
        val raw = context.getSharedPreferences("meeting-sdk", Context.MODE_PRIVATE).getString("config", null)
        configuration = if (raw == null) MeetingConfiguration() else MeetingConfiguration.fromJson(raw)
        processConfiguration = configuration
        return configuration
    }

    /** Original PhoneAudio state fields plus a protocol version. Safe without a page. */
    fun getRecordingState(): String = BackendService.recordingState()

    /** Subscribe before reading snapshots. Close on page stop; capture continues. */
    fun subscribe(listener: MeetingEventListener): Closeable = events.subscribe(listener::onEvent)

    fun startRecording(context: Context, meetingId: String, compare: Boolean = false) {
        require(Regex("[0-9a-f]{32}").matches(meetingId)) { "无效的会议 ID" }
        check(engine.phase == MeetingEnginePhase.READY) { "本地引擎尚未就绪" }
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "未授予麦克风权限"
        }
        command(context, "record") {
            putExtra("mid", meetingId)
            putExtra("compare", compare)
        }
    }

    fun stopRecording(context: Context) = captureCommand(context, "stop_recording")
    fun pauseRecording(context: Context) = captureCommand(context, "pause_recording")
    fun resumeRecording(context: Context) = captureCommand(context, "resume_recording")

    private fun captureCommand(context: Context, action: String) {
        // A detached/stopped engine must not be restarted by a stale page command.
        if (engine.phase == MeetingEnginePhase.READY) command(context, action)
    }

    private fun command(context: Context, action: String, extras: Intent.() -> Unit = {}) {
        val app = context.applicationContext
        app.startService(Intent(app, BackendService::class.java).setAction(action).apply(extras))
    }

    internal fun publish(event: String) = events.publish(event)

    internal fun engineChanged(phase: MeetingEnginePhase, error: String? = null) {
        engine = MeetingEngineState(phase, error)
        publish(JSONObject().put("v", 1).put("type", "engine_state")
            .put("phase", phase.name.lowercase()).put("message", error).toString())
    }
}

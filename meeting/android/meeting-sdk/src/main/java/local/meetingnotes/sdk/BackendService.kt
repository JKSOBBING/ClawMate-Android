package local.meetingnotes.sdk

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.*
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class BackendService : Service() {
    companion object {
        private val backendLifecycle = Any()
        private val backendEpoch = AtomicLong()
        @Volatile private var mid: String? = null
        @Volatile private var capturing = false
        @Volatile private var streaming = false
        @Volatile private var paused = false
        @Volatile private var sentSamples = 0L
        @Volatile private var phase = "idle"
        @Volatile private var captureError: String? = null
        private val transcript = TranscriptCache()
        fun recordingState(): String = synchronized(BackendService::class.java) {
            transcript.appendTo(JSONObject().put("v",1).put("type","capture_state").put("mid",mid).put("recording",capturing).put("streaming",streaming)
                .put("paused",paused).put("audio_seconds",sentSamples/16000.0)
                .put("phase",phase).put("message",captureError)).toString()
        }
    }
    private val instanceEpoch = backendEpoch.incrementAndGet()
    private var socket: WebSocket?=null
    private var audio: AudioRecord?=null
    private val client=OkHttpClient.Builder().readTimeout(0,TimeUnit.SECONDS).build()
    private var booted=false
    @Volatile private var recordRequested=false
    @Volatile private var pauseRequested=false
    @Volatile private var generation=0
    @Volatile private var interruptionReason: String?=null
    @Volatile private var destroyed=false
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        val config = MeetingSdk.restoreConfiguration(this)
        MeetingSdk.engineChanged(MeetingEnginePhase.STARTING)
        if (Build.VERSION.SDK_INT>=26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("meeting-local","本地会议运行",NotificationManager.IMPORTANCE_LOW))
        foreground(false)
        Thread {
            try {
                val root=File(filesDir,config.storageDirectory).apply { mkdirs() }
                for (name in arrayOf("native-libraries.json","model-sources.json"))
                    assets.open(name).use { input -> File(root,name).outputStream().use { input.copyTo(it) } }
                synchronized(backendLifecycle) {
                    if(destroyed) return@Thread
                    if (!Python.isStarted()) Python.start(AndroidPlatform(this))
                    Python.getInstance().getModule("android_backend").callAttr("start",filesDir.absolutePath,
                        applicationInfo.nativeLibraryDir,"${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}",config.toJson())
                    if(!destroyed) {
                        booted=true
                        MeetingSdk.engineChanged(MeetingEnginePhase.READY)
                    }
                }
            } catch (error: Throwable) {
                if(destroyed) return@Thread
                MeetingSdk.engineChanged(MeetingEnginePhase.FAILED,error.message ?: "本地引擎启动失败")
                File(filesDir,"startup-error.txt").writeText(error.stackTraceToString())
                event(JSONObject().put("type","capture_error").put("message","引擎启动失败：${error.message}").toString())
            }
        }.start()
    }
    private fun foreground(recording: Boolean) {
        val launch=packageManager.getLaunchIntentForPackage(packageName)
        val stop=PendingIntent.getService(this,1,Intent(this,BackendService::class.java).setAction("stop_recording"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder=if (Build.VERSION.SDK_INT>=26) Notification.Builder(this,"meeting-local") else Notification.Builder(this)
        builder.setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("端侧会议纪要")
            .setContentText(if (recording && paused) "录音已暂停，麦克风已停止采集" else if (recording) "正在录音，语音在手机本地处理" else "本地引擎运行中")
            .setOngoing(true)
        if(launch!=null) builder.setContentIntent(PendingIntent.getActivity(this,0,launch,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        if (recording) {
            val toggle=PendingIntent.getService(this,2,Intent(this,BackendService::class.java)
                .setAction(if (paused) "resume_recording" else "pause_recording"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null,if (paused) "继续录音" else "暂停录音",toggle).build())
            builder.addAction(Notification.Action.Builder(null,"结束录音",stop).build())
        }
        if (Build.VERSION.SDK_INT>=34) startForeground(7,builder.build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            (if (recording) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0))
        else if (Build.VERSION.SDK_INT>=29) startForeground(7,builder.build(),if (recording) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        else startForeground(7,builder.build())
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        when(intent?.action) {
            "record" -> begin(intent.getStringExtra("mid") ?: return START_NOT_STICKY,intent.getBooleanExtra("compare",true))
            "stop_recording" -> stopRecording()
            "pause_recording" -> if (capturing && recordRequested) pauseRequested=true
            "resume_recording" -> if (capturing && recordRequested) pauseRequested=false
        }
        return START_NOT_STICKY
    }
    private fun event(message: String) { if(!destroyed) MeetingSdk.publish(message) }
    private fun transition(next: String,reason: String?=null) {
        synchronized(BackendService::class.java) {
            phase=next;captureError=reason
            streaming=next in setOf("preparing","recording","paused","finalizing")
            capturing=next in setOf("recording","paused");paused=next=="paused"
        }
        event(JSONObject(recordingState()).put("type","capture_phase").toString())
    }
    private fun begin(id: String,compare: Boolean) {
        if (streaming) { event(JSONObject().put("type","capture_error").put("message","请先结束当前录音").toString());return }
        if (!booted || checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            event(JSONObject().put("type","capture_error").put("message","引擎尚未就绪或未授予麦克风权限").toString());return
        }
        val session=++generation
        synchronized(BackendService::class.java) { mid=id;sentSamples=0;transcript.reset() }
        recordRequested=true
        pauseRequested=false
        interruptionReason=null
        transition("preparing")
        foreground(true)
        socket=client.newWebSocket(Request.Builder().url(MeetingProtocol.streamUrl(MeetingProtocol.endpoint(MeetingSdk.baseUrl),id,compare)).build(),object: WebSocketListener() {
            override fun onOpen(ws: WebSocket,response: Response) { ws.send("{\"type\":\"start\",\"sample_rate\":16000,\"channels\":1,\"format\":\"pcm_s16le\"}") }
            override fun onMessage(ws: WebSocket,text: String) {
                if(session!=generation) return
                val value=MeetingProtocol.event(text)
                synchronized(BackendService::class.java) { transcript.accept(value) }
                event(text)
                if (value.optString("type")=="ready") startAudio(ws,session)
                if (value.optString("type")=="error") fail(IllegalStateException(value.optString("message")))
                if (value.optString("type")=="done") { recordRequested=false;transition("finalizing");ws.close(1000,"completed") }
            }
            override fun onFailure(ws: WebSocket,error: Throwable,response: Response?) { if(session==generation) fail(error) }
            override fun onClosing(ws: WebSocket,code: Int,reason: String) { ws.close(code,reason) }
            override fun onClosed(ws: WebSocket,code: Int,reason: String) {
                if(session!=generation) return
                recordRequested=false
                if(phase=="finalizing") transition("idle") else transition("interrupted",captureError ?: "录音连接中断，请检查已保存音频")
                event("{\"type\":\"capture_closed\"}");Handler(mainLooper).post { if(session==generation) foreground(false) }
            }
        })
    }
    private fun startAudio(ws: WebSocket,session: Int) {
        Thread {
            var recorder: AudioRecord?=null
            var recordingCallback: AudioManager.AudioRecordingCallback?=null
            val manager=getSystemService(AudioManager::class.java)
            try {
                if(!recordRequested) { ws.send("{\"type\":\"stop\"}");return@Thread }
                val minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
                require(minimum>0) { "手机不支持 16 kHz 单声道采集" }
                val input=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(minimum,12800))
                recorder=input
                require(input.state==AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
                if(Build.VERSION.SDK_INT>=29) {
                    recordingCallback=object: AudioManager.AudioRecordingCallback() {
                        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                            if(session==generation && recordRequested && configs.orEmpty().any { it.clientAudioSessionId==input.audioSessionId && it.isClientSilenced }) {
                                interruptionReason="麦克风被系统或其他应用暂停，请检查后继续录音"
                                pauseRequested=true
                            }
                        }
                    }
                    manager.registerAudioRecordingCallback(recordingCallback,Handler(mainLooper))
                }
                audio=input;input.startRecording();if(recordRequested) transition("recording")
                event("{\"type\":\"capture_started\"}")
                val buffer=ByteArray(3200)
                var lastHeartbeat=SystemClock.elapsedRealtime()
                while(recordRequested && streaming && session==generation) {
                    if(pauseRequested) {
                        if(!paused) {
                            input.stop();transition("paused",interruptionReason)
                            check(ws.send("{\"type\":\"pause\"}")) { "录音连接已关闭" }
                            event("{\"type\":\"capture_paused\"}")
                            Handler(mainLooper).post { foreground(true) }
                        }
                        if(SystemClock.elapsedRealtime()-lastHeartbeat>=15000) {
                            check(ws.send("{\"type\":\"ping\"}")) { "录音连接已关闭" }
                            lastHeartbeat=SystemClock.elapsedRealtime()
                        }
                        Thread.sleep(50);continue
                    }
                    if(paused) {
                        input.startRecording();interruptionReason=null;transition("recording")
                        check(ws.send("{\"type\":\"resume\"}")) { "录音连接已关闭" }
                        event("{\"type\":\"capture_resumed\"}")
                        Handler(mainLooper).post { foreground(true) }
                    }
                    val count=input.read(buffer,0,buffer.size)
                    if(count<0) throw IllegalStateException("麦克风读取失败：$count")
                    if(count>0) {
                        if(ws.queueSize()>640000) throw IllegalStateException("实时识别积压，请停止录音并使用离线精修")
                        if(!ws.send(buffer.toByteString(0,count))) throw IllegalStateException("录音连接已关闭")
                        sentSamples+=count/2
                    }
                }
                if(session==generation && streaming) ws.send("{\"type\":\"stop\"}")
            } catch (error: Throwable) { if(session==generation) fail(error) }
            finally {
                recordingCallback?.let { manager.unregisterAudioRecordingCallback(it) }
                try { if(recorder?.recordingState==AudioRecord.RECORDSTATE_RECORDING) recorder?.stop() } catch (_: Exception) {}
                recorder?.release()
                if(session==generation) { audio=null;capturing=false }
            }
        }.start()
    }
    private fun stopRecording() { if(!streaming)return;recordRequested=false;transition("finalizing");event("{\"type\":\"capture_stopping\"}") }
    private fun fail(error: Throwable) {
        if(!streaming) return
        recordRequested=false;transition("interrupted",error.message ?: "录音中断")
        socket?.cancel()
        event(JSONObject().put("type","capture_error").put("message",error.message ?: "录音失败").toString())
        Handler(mainLooper).post { foreground(false) }
    }
    override fun onDestroy() {
        destroyed=true;booted=false;recordRequested=false;generation++;socket?.cancel()
        transition(if(streaming) "interrupted" else "idle",if(streaming) "本地服务已停止，请检查已保存音频" else null)
        MeetingSdk.engineChanged(MeetingEnginePhase.STOPPED)
        // Startup and shutdown are serialized off the Android UI thread.
        Thread {
            synchronized(backendLifecycle) {
                if(instanceEpoch==backendEpoch.get() && Python.isStarted())
                    Python.getInstance().getModule("android_backend").callAttr("stop")
            }
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }.start()
        super.onDestroy()
    }
}

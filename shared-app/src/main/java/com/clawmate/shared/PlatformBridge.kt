package com.clawmate.shared

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import ohos.ace.adapter.capability.bridge.BridgePlugin
import ohos.stage.ability.adapter.StageActivity
import android.content.res.Configuration
import com.clawmate.app.chat.voice.tts.*
import com.clawmate.app.gallery.analysis.OcrResult
import com.clawmate.app.gallery.ocr.LocalOcrRecognizer
import com.clawmate.platform.LocalImageAccess
import com.clawmate.platform.NativeOperations
import org.json.JSONObject
import java.io.Closeable

/** Android APIs only; no account, service endpoint or vendor-specific dependency. */
class PlatformBridge(private val activity: StageActivity) :
    BridgePlugin(activity, "ClawMatePlatform", activity.bridgeManager), Closeable {
    private val ocrJobs = NativeOperations<OcrResult>()
    private var tts: AndroidTextToSpeechTtsAdapter? = null
    private data class Voice(val status: String = "idle", val messageId: String = "", val error: String = "")
    @Volatile private var voice = Voice()

    fun ocrStart(value: String): String {
        val uri = LocalImageAccess.validate(value, listOf(activity.filesDir, activity.cacheDir))
        return ocrJobs.start { LocalOcrRecognizer(activity.applicationContext).recognizeOnWorkerThread(uri) }
    }

    fun ocrPoll(id: String): String {
        val job = ocrJobs.poll(id)
        val result = JSONObject().put("v", 1).put("status", job.state).put("text", "").put("error", "")
        when (val outcome = job.value) {
            is OcrResult.Text -> result.put("text", outcome.value)
            OcrResult.Empty -> Unit
            OcrResult.Cancelled -> result.put("status", "cancelled").put("error", "本地识别已取消")
            is OcrResult.ModelDownload -> result.put("status", "failed").put("error", outcome.message)
            is OcrResult.Failed -> result.put("status", "failed").put("error", outcome.reason)
            is OcrResult.Unavailable -> result.put("status", "failed").put("error", outcome.reason)
            null -> if (job.state != "running") result.put("error", "本地识别已取消、过期或失败，请重试")
        }
        return result.toString()
    }

    fun ocrCancel(id: String): Boolean { ocrJobs.cancel(id); return true }

    fun ttsInitialize(): Boolean = onUi {
        if (tts == null || tts?.state == TtsInitState.ENGINE_TTS_ERROR || tts?.state == TtsInitState.UNAVAILABLE) {
            tts?.release()
            tts = AndroidTextToSpeechTtsAdapter(activity.applicationContext)
            val result = tts!!.initialize(object : TtsSpeakListener {
                override fun onStarted(utteranceId: String) { voice = voice.copy(status = "speaking", error = "") }
                override fun onDone(utteranceId: String) { voice = voice.copy(status = "idle") }
                override fun onError(utteranceId: String, message: String) { voice = voice.copy(status = "error", error = message) }
            })
            if (result is TtsInitResult.Failed) voice = Voice("error", error = result.message)
        }
        true // Initialization requested. Read the actual state from runtimeSnapshot.
    }

    fun ttsSpeak(messageId: String, text: String): Boolean = onUi {
        require(messageId.isNotBlank() && text.isNotBlank() && text.length <= 20000) { "朗读文本为空或过长" }
        val engine = tts ?: error("请先初始化本机语音引擎")
        check(engine.ready) { "本机语音引擎尚不可用：${engine.state}" }
        voice = Voice("synthesizing", messageId)
        try {
            when (val result = engine.speak(text)) {
                TtsSpeakResult.Accepted -> true
                is TtsSpeakResult.NotReady -> error("本机语音引擎尚未就绪：${result.reason}")
                is TtsSpeakResult.RichTypeError -> error(result.message)
            }
        } catch (failure: Exception) {
            voice = voice.copy(status = "error", error = failure.message ?: "系统朗读失败")
            throw failure
        }
    }

    fun ttsStop(): Boolean = onUi {
        tts?.stop()
        if (voice.status == "speaking" || voice.status == "synthesizing") voice = voice.copy(status = "paused")
        true
    }

    fun runtimeSnapshot(): String = onUi {
        (activity as? SharedActivity)?.updateSystemBars()
        val engineState = tts?.state?.name ?: "UNINITIALIZED"
        val failure = engineState == "ENGINE_TTS_ERROR" || (tts != null && engineState == "UNAVAILABLE")
        JSONObject().put("v", 1)
            .put("dark", activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
            .put("keyboardHeight", keyboardHeight())
            .put("ttsEngineState", engineState)
            .put("voiceStatus", if (failure) "error" else voice.status)
            .put("voiceMessageId", voice.messageId)
            .put("voiceError", if (failure) "本机未提供可用的中文朗读引擎，请在系统设置中安装或启用" else voice.error)
            .toString()
    }

    override fun close() {
        ocrJobs.close()
        onUi { tts?.release(); tts = null; voice = Voice() }
    }
    fun platformInfo(): String = "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}"

    fun clearFocus(): Boolean = onUi {
        activity.currentFocus?.let { it.clearFocus(); true } ?: false
    }

    fun hideKeyboard(): Boolean = onUi {
        val focused = activity.currentFocus ?: return@onUi false
        val manager = activity.getSystemService(InputMethodManager::class.java)
        manager.hideSoftInputFromWindow(focused.windowToken, 0)
    }

    fun keyboardHeight(): Int = onUi {
        val root = activity.window.decorView
        val frame = Rect()
        root.getWindowVisibleDisplayFrame(frame)
        (root.rootView.height - frame.bottom).takeIf { it > root.rootView.height / 5 } ?: 0
    }

    fun setClipboard(text: String): Boolean = onUi {
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("ClawMate", text))
        true
    }

    fun openUrl(value: String): Boolean = onUi {
        val uri = Uri.parse(value)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) { "Invalid HTTPS URL" }
        activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        true
    }

    private fun <T> onUi(action: () -> T): T {
        val work = FutureTask(action)
        if (Looper.myLooper() == Looper.getMainLooper()) work.run() else activity.runOnUiThread(work)
        return try { work.get(2, TimeUnit.SECONDS) } catch (timeout: TimeoutException) {
            // A timed-out, still-queued action must not start speaking later.
            work.cancel(false)
            throw IllegalStateException("安卓界面响应超时，请重试", timeout)
        }
    }
}

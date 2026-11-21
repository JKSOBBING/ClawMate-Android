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
import com.clawmate.app.chat.voice.asr.*
import com.clawmate.app.image.PreparedChatImage
import com.clawmate.app.task.runner.WorkflowRunResult
import com.clawmate.app.gallery.analysis.OcrResult
import com.clawmate.app.gallery.ocr.LocalOcrRecognizer
import com.clawmate.platform.LocalImageAccess
import com.clawmate.inference.MobiInferTextEngine
import com.clawmate.platform.localmodel.LocalInferenceJobs
import com.clawmate.platform.NativeOperations
import com.clawmate.platform.SharedNativeWork
import com.clawmate.platform.gui.MikaPhoneEntry
import com.clawmate.platform.gui.MikaPhoneMode
import com.clawmate.platform.gui.MikaPhoneResult
import com.clawmate.platform.avatar.AndroidAvatarForeground
import com.clawmate.platform.avatar.AvatarForegroundRgba
import com.clawmate.platform.voice.SpeechHttp
import com.clawmate.platform.voice.PcmSpeechPlayer
import com.clawmate.platform.voice.SpeechFilePlayer
import com.clawmate.platform.reminder.CollectionReminderEngine
import com.clawmate.app.task.runner.WorkflowModelMailbox
import org.json.JSONObject
import org.json.JSONArray
import java.io.Closeable
import kotlinx.coroutines.runBlocking

/** Android APIs only; no account, service endpoint or vendor-specific dependency. */
class PlatformBridge(private val activity: StageActivity) :
    BridgePlugin(activity, "ClawMatePlatform", activity.bridgeManager), Closeable {
    fun legacyImportStatus(): String = LegacyDataImport.status(activity.applicationContext)
    fun legacySettings(): String = if (activity.packageName == "com.clawmate.app") {
        LegacySettingsImport.read(activity.filesDir).toString()
    } else "{}"
    private val sources = com.clawmate.platform.sources.PersonalSourceEngine(activity)
    private val sourceAuth = com.clawmate.platform.sources.MailOAuth(activity, sources)
    fun sourcesAuthStart(provider: String, user: String, client: String, tenant: String): String = try {
        sourceAuth.start(provider, user, client, tenant); JSONObject().put("ok", true).toString()
    } catch (_: IllegalArgumentException) {
        JSONObject().put("ok", false).put("error", "请填写完整邮箱地址；Microsoft还需有效的应用客户端ID，以及common或租户ID").toString()
    } catch (_: Exception) {
        JSONObject().put("ok", false).put("error", "无法开始授权，请关闭上一次授权窗口并检查本机配置后重试").toString()
    }
    fun sourcesAuthStatus(): String = sourceAuth.status()
    fun sourcesAuthOpen(): Boolean { sourceAuth.openPage(); return true }
    fun sourcesAuthCancel(): Boolean { sourceAuth.cancel(); return true }
    /** jAccount 授权码流：客户端密钥由用户在本机填写，只进入后台令牌兑换，不经过前端返回值。 */
    fun sourcesJAccountStart(user: String, client: String, redirect: String, secret: String): String = try {
        sourceAuth.startJAccount(user, client, redirect, secret); JSONObject().put("ok", true)
            .put("url", sourceAuth.status().let { JSONObject(it).optString("uri") }).toString()
    } catch (e: IllegalArgumentException) {
        JSONObject().put("ok", false).put("error", e.message ?: "jAccount授权参数不正确").toString()
    } catch (_: Exception) {
        JSONObject().put("ok", false).put("error", "无法开始jAccount授权，请稍后重试").toString()
    }
    /**
     * 宿主收到的 jAccount 回调（Deep Link 或已登记网址）：交给 MailOAuth 校验 state 并兑换令牌。
     * 只接受本应用的 Deep Link 或带授权码查询串的回调；其它链接明确返回 false。
     */
    fun sourcesJAccountLink(url: String): Boolean = acceptJAccountLink(url)
    /** Deep Link（clawmate://jaccount?code=...&state=...）到达时由宿主 Activity 调用。 */
    fun acceptJAccountLink(url: String): Boolean {
        if (!com.clawmate.platform.sources.JAccountCallback.isOwnLink(url)) return false
        return runCatching { sourceAuth.completeJAccountFromRedirect(url) }.isSuccess
    }
    fun acceptJAccountLinkFromActivity(url: String): Boolean = acceptJAccountLink(url)
    fun sourceAuthActivityResult(code: Int, result: Int, data: Intent?): Boolean = sourceAuth.activityResult(code, result, data)
    private val sourceJobs = NativeOperations<String>(1)
    private val wechat = com.clawmate.platform.gui.WechatCollectionDevice(activity)
    private val wechatSteps = NativeOperations<String>(1)

    fun wechatStepStart(token: String, action: String): String {
        if (JSONObject(action).getString("action") == "open") onUi { wechat.reserve(token) }
        return wechatSteps.start {
            try {
                JSONObject().put("text", runBlocking { wechat.step(token, action) }).toString()
            } catch (error: Exception) {
                JSONObject().put("error", error.message ?: "微信窗口操作失败").toString()
            }
        }
    }

    fun wechatStepPoll(id: String): String {
        val step = wechatSteps.poll(id)
        return JSONObject().put("state", step.state).put("result", step.value.orEmpty()).toString()
    }

    fun wechatStop(token: String) = onUi { wechat.stop(token); true }
    private var awaitingCalendarPermission = false
    private fun calendarPermissions(write: Boolean) = if (write)
        arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR)
    else arrayOf(android.Manifest.permission.READ_CALENDAR)

    fun sourcesPermissionStart(write: Boolean): String = onUi {
        val missing = calendarPermissions(write).filter {
            activity.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        when {
            missing.isEmpty() -> "granted"
            awaitingCalendarPermission -> "busy"
            else -> {
                awaitingCalendarPermission = true
                try {
                    activity.requestPermissions(missing.toTypedArray(), 7310)
                } catch (failure: RuntimeException) {
                    awaitingCalendarPermission = false
                    throw failure
                }
                "pending"
            }
        }
    }
    internal fun calendarPermissionReturned() { awaitingCalendarPermission = false }
    fun sourcesPermissionStatus(write: Boolean): String = onUi {
        when {
            awaitingCalendarPermission -> "pending"
            calendarPermissions(write).all { activity.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED } -> "granted"
            else -> "denied"
        }
    }
    fun sourcesGranted(): Boolean = sources.calendarGranted()
    fun sourcesOptions(): String = sources.options()
    fun sourcesSaveOptions(kind: String, value: String): String = try {
        sources.saveOptions(kind, value); JSONObject().put("ok", true).toString()
    } catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message ?: "设置保存失败").toString() }
    fun sourcesSettings(): String = sources.settings()
    fun sourcesCampusCredentials(user: String, password: String): Boolean {
        sources.saveCampusCredentials(user, password); sourceAuth.cancel(); return true
    }
    fun sourcesCredentials(host: String, port: String, user: String, secret: String): Boolean {
        sources.saveCredentials(host, port.toInt(), user, secret); sourceAuth.cancel(); return true
    }
    fun sourcesStart(operation: String, argument: String): String = sourceJobs.start {
        try { JSONObject().put("ok", true).put("value", sources.execute(operation, argument)).toString() }
        catch (error: Exception) {
            if (Thread.currentThread().isInterrupted) JSONObject().put("ok", false).put("error", "读取已取消").toString()
            else {
                // 把真实原因带回页面：IMAP 服务器拒绝、TLS/证书、超时等都要能看到。
                val reason = generateSequence(error as Throwable) { it.cause }.last().let { it.message ?: it.javaClass.simpleName }
                JSONObject().put("ok", false).put("detail", "$reason").put("error",
                    if (operation.startsWith("calendar")) reason else
                        "读取失败：$reason。（邮箱请核对账号、密码与IMAP开关）").toString()
            }
        }
    }
    fun sourcesPoll(id: String): String {
        val job = sourceJobs.poll(id)
        return JSONObject().put("state", job.state).put("value", job.value ?: "").toString()
    }
    fun sourcesCancel(): Boolean { sources.close(); return true }
    fun sourcesCancelJob(id: String): Boolean { sourceJobs.cancel(id); sources.close(); return true }
    fun sourcesRead(kind: String): String = sources.read(kind)
    fun sourcesWrite(kind: String, value: String): Boolean { sources.write(kind, value); return true }
    fun sourcesClear(kind: String): Boolean { sources.clear(kind); return true }
    fun sourcesForgetMail(): Boolean { sourceAuth.cancel(); sources.forgetMail(); return true }
    /** 常用节假日：只读说明数据范围与依据，不联网、不读取系统日历。 */
    fun sourcesHolidayInfo(): String = JSONObject().put("name", com.clawmate.platform.sources.ChinaHolidays.SOURCE_NAME)
        .put("sections", JSONArray().put("2025年放假安排（国办发明电〔2024〕7号）").put("2026年放假安排（国办发明电〔2025〕6号）"))
        .put("note", "只收录官方通知中的放假日与调休上班日；未覆盖年份只显示常规周末并提示以官方通知为准。")
        .toString()
    // 本地模型下载到应用私有目录：进程内单任务，支持断点续传、SHA256 校验、暂停/取消与删除。
    private val modelDownloader by lazy { com.clawmate.platform.localmodel.download.ModelDownloadRegistry.of(activity.filesDir) }
    private val modelManager by lazy { com.clawmate.platform.localmodel.LocalModelManager(activity.filesDir) }
    private val ggufPicker by lazy { GgufPicker(activity, modelManager) }
    private val localInference = LocalInferenceJobs({ modelManager.resolveGguf(it) }) { model, messages, threads, cancelled, onText ->
        MobiInferTextEngine.generate(model, messages, 512, threads, cancelled, onText)
    }

    fun localModelImport(): String = onUi { ggufPicker.start() }
    fun localModelImportPoll(id: String): String = ggufPicker.poll(id)
    fun localModelImportCancel(id: String): Boolean = ggufPicker.cancel(id)
    internal fun ggufActivityResult(code: Int, result: Int, data: Intent?): Boolean =
        ggufPicker.onActivityResult(code, result, data)

    @Synchronized fun localInferenceStart(requestId: String, modelId: String, messagesJson: String, threads: Int): String {
        check(modelId == modelManager.selectedModelId()) { "选中的本地模型已变化，请重新发送" }
        return localInference.start(requestId, modelId, messagesJson, threads)
    }
    fun localInferencePoll(id: String): String {
        val result = localInference.poll(id)
        return JSONObject().put("v", 1).put("state", result.state).put("text", result.text).put("error", result.error).toString()
    }
    fun localInferenceCancel(id: String): Boolean = localInference.cancel(id)
    internal fun stopLocalInference() = localInference.cancelCurrent()
    fun localModelDownloadStart(repo: String, includeAuxiliary: Boolean): String = runCatching {
        if (modelDownloader.start(repo, includeAuxiliary)) "ok" else "已有模型下载任务正在进行，请等待完成或暂停后再试"
    }.getOrElse { "无法开始下载：" + (it.message ?: "未知错误") }
    fun localModelDownloadPause(): Boolean = modelDownloader.pause()
    /** 只读的下载计划：来源、目标目录、文件选择规则与校验步骤；不联网、不写盘。 */
    fun localModelPlan(repo: String, includeAuxiliary: Boolean): String =
        JSONObject(com.clawmate.platform.localmodel.LocalModelPlan.build(repo, includeAuxiliary)).toString()
    /** 下载数据的网址来源（官方 ModelScope，HTTPS）。 */
    fun localModelDownloadSource(): String = com.clawmate.platform.localmodel.LocalModelPlan.source()
    /** 应用私有的模型存储路径（不含凭据），用于真机核对下载目录。 */
    fun localModelRoot(): String = java.io.File(activity.filesDir, "modelscope").absolutePath
    fun localModelDownloadCancel(): Boolean = modelDownloader.cancel()
    fun localModelDownloadProgress(): String = modelDownloader.progress().toJson()
    fun localModelList(): String {
        val array = JSONArray()
        val selected = modelManager.selectedModelId()
        modelManager.list().forEach { model ->
            array.put(JSONObject().put("id", model.id).put("label", model.label).put("path", model.path)
                .put("repo", model.id.removePrefix("modelscope:").removePrefix("legacy:"))
                .put("sizeText", com.clawmate.platform.localmodel.download.ModelScopeSpec.formatBytes(model.sizeBytes))
                .put("statusText", model.statusText).put("ready", model.ready)
                .put("selected", model.id == selected).put("runnable", model.runnable))
        }
        return array.toString()
    }
    fun localModelSelect(modelId: String): String = runCatching { modelManager.select(modelId).id }
        .getOrElse { throw IllegalStateException(it.message ?: "无法选择模型", it) }
    @Synchronized fun localModelDelete(modelId: String): String = runCatching {
        check(!localInference.uses(modelId)) { "请先停止使用此模型的推理" }
        modelManager.delete(modelId, modelDownloader.isRunning()); "ok"
    }.getOrElse { throw IllegalStateException(it.message ?: "无法删除模型", it) }
    private val ocrJobs = NativeOperations<OcrResult>()
    private val imageJobs = NativeOperations<List<PreparedChatImage>>(1)
    private val avatarJobs = NativeOperations<AvatarForegroundRgba>(1)
    private val screenshotJobs = NativeOperations<Pair<Int,com.clawmate.platform.gui.WindowScreenshotResult>>(1)
    @Volatile private var screenshotEpoch = 0
    private var speechHttp = SpeechHttp()
    private var speechHttpDetached = false
    private val pcmSpeech = PcmSpeechPlayer(activity.applicationContext)
    private val fileSpeech = SpeechFilePlayer(activity.applicationContext, java.io.File(activity.filesDir,"files/chat-sessions/assets"))
    private var speechMode = ""
    private val workflowJobs = NativeOperations<WorkflowRunResult>(1, expiryMillis = 670_000)
    private val mikaJobs = NativeOperations<MikaPhoneResult>(1)
    private val workflowModels = java.util.concurrent.ConcurrentHashMap<String, WorkflowModelMailbox>()
    private var asr: AndroidSpeechRecognizerAsrAdapter? = null
    private val meeting = MeetingBridge(activity as SharedActivity)
    private val codexBridge = lazy { CodexPlatformBridge(activity) }
    private val codex by codexBridge
    private val reminders = CollectionReminderEngine(activity)

    fun reminderInitialize(hour: String, minute: String): String = onUi {
        reminders.initialize(SharedActivity::class.java.name, hour.toInt(), minute.toInt())
    }
    fun reminderRefresh(): String = onUi { reminders.refresh() }    fun reminderState(): String = onUi { reminders.snapshot() }
    fun codexRequestStart(raw: String): String = codex.codexRequest(raw)
    fun codexRequestPoll(ticket: String): String = codex.codexRequestPoll(ticket)
    fun codexRequestCancel(ticket: String): Boolean = codex.codexRequestCancel(ticket)
    fun codexCancel(id: String): Boolean = codex.codexCancel(id)
    fun codexConnectionList(): String = codex.codexConnectionList()
    fun codexConnectionLoad(id: String): String = codex.codexConnectionLoad(id)
    fun codexConnectionSave(raw: String): String = codex.codexConnectionSave(raw)
    fun codexConnectionForget(id: String): Boolean = codex.codexConnectionForget(id)

    // Pickers must run on the UI thread: they register activity-result launchers and start
    // intents, both of which the platform rejects off the main thread.
    private val codexPickers = lazy { CodexPickers(activity as SharedActivity) }
    private val pickers by codexPickers
    fun codexScanStart(): Boolean = onUi { pickers.scanStart() }
    fun codexScanResult(): String = onUi { pickers.scanResult() }
    fun codexTakePhotoStart(cacheDir: String): Boolean = onUi { pickers.takePhotoStart(cacheDir) }
    fun codexTakePhotoResult(): String = pickers.takePhotoResult()
    fun codexDocumentStart(kind: String): Boolean = onUi { pickers.documentStart(kind) }
    fun codexDocumentResult(): String = onUi { pickers.documentResult() }
    fun codexExportStart(raw: String): Boolean {
        val saved = JSONObject(raw)
        return onUi { pickers.exportStart(saved.optString("localPath"), saved.optString("name")) }
    }
    fun codexExportResult(): String = pickers.exportResult()

    fun codexOpenArtifact(raw: String): Boolean {
        val saved = JSONObject(raw)
        return onUi { pickers.openArtifact(saved.optString("localPath"), saved.optString("mimeType")) }
    }

    fun codexRunTaskStart(raw: String): String = codex.codexRunTaskStart(raw)
    fun codexRunTaskPoll(id: String): String = codex.codexRunTaskPoll(id)
    fun codexRunTaskCancel(id: String): Boolean = codex.codexRunTaskCancel(id)

    /** Routes a picker result back to the waiting Codex call; true when consumed. */
    internal fun codexActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean =
        pickers.onActivityResult(requestCode, resultCode, data)

    /** Completes a "save as" request with the location the user chose. */
    internal fun codexExportPicked(uri: Uri?) {
        pickers.onExportPicked(uri)
    }
    fun reminderSetEnabled(enabled: Boolean): String = onUi {
        if (enabled && Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            runCatching { reminders.setEnabled(true) } // Persist a controlled disabled/error state before the prompt.
            activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7303)
            return@onUi reminders.snapshot()
        }
        val result = reminders.setEnabled(enabled)
        (activity as SharedActivity).clearReminderRequest()
        result
    }
    fun reminderSetTime(hour: String, minute: String): String = onUi {
        val result = reminders.setTime(hour.toInt(), minute.toInt())
        (activity as SharedActivity).clearReminderRequest()
        result
    }
    fun reminderRecordCollection(): String = onUi { reminders.recordCollection() }
    fun reminderClaim(request: String): Boolean = onUi {
        val host = activity as SharedActivity
        if (!host.reminderWindowReady() || host.reminderRequest() != request || request.isEmpty()) return@onUi false
        val claimed = reminders.claim(request)
        if (claimed) host.clearReminderRequest()
        claimed
    }
    fun reminderClear(request: String): Boolean = onUi {
        val host = activity as SharedActivity
        if (host.reminderRequest() == request) host.clearReminderRequest()
        true
    }
    fun reminderRequest(): String = onUi { (activity as SharedActivity).reminderRequest() }

    fun meetingInitialize(): Boolean = onUi { meeting.initialize() }
    fun meetingAttach(): Boolean = onUi { meeting.attach() }
    fun meetingDetach(): Boolean = onUi { meeting.detach() }
    fun meetingPoll(since: String): String = meeting.snapshot(since)
    fun meetingRequest(operation: String, args: String): String = meeting.request(operation, args)
    fun meetingJob(id: String): String = meeting.poll(id)
    fun meetingCancel(id: String): Boolean = meeting.cancel(id)
    fun meetingRecord(id: String): Boolean = onUi {
        if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 7302)
            error("请允许麦克风权限后再次点击开始录音")
        }
        asrCancel(); ttsStop(); meeting.stopPlayback()
        meeting.record(id)
    }
    fun meetingCapture(action: String): Boolean = onUi {
        when(action) {
            "stop" -> local.meetingnotes.sdk.MeetingSdk.stopRecording(activity)
            "pause" -> local.meetingnotes.sdk.MeetingSdk.pauseRecording(activity)
            "resume" -> local.meetingnotes.sdk.MeetingSdk.resumeRecording(activity)
            else -> error("不支持的录音操作")
        }; true
    }
    fun meetingChooseAudio(id: String, profile: String): String = onUi { meeting.chooseAudio(id, profile) }
    fun meetingChooseHandoff(): String = onUi { meeting.chooseHandoff() }
    fun meetingExport(id: String, format: String, revision: String): String = onUi { meeting.export(id, format, revision) }
    fun meetingPlay(id: String, audioId: String, start: String, end: String): Boolean = onUi { meeting.play(id, audioId, start, end) }
    fun meetingStopPlayback(): Boolean = onUi { meeting.stopPlayback() }
    fun meetingValidateHandoff(raw: String): String = meeting.validateHandoff(raw)
    fun meetingActivityResult(code: Int, result: Int, data: Intent?): Boolean = meeting.activityResult(code, result, data)
    private var asrState = "idle"
    private var asrText = ""
    private var asrError = ""
    private var asrVersion = 0
    private var tts: AndroidTextToSpeechTtsAdapter? = null
    private data class Voice(val status: String = "idle", val messageId: String = "", val error: String = "")
    @Volatile private var voice = Voice()

    fun imagePrepareStart(raw: String): String {
        require(raw.length <= 16000)
        val input = JSONArray(raw)
        require(input.length() in 1..4) { "每次最多发送4张图片" }
        val uris = (0 until input.length()).map {
            LocalImageAccess.validate(input.getString(it), listOf(activity.filesDir, activity.cacheDir)).toString()
        }
        return imageJobs.start { SharedNativeWork.prepareImages(activity.applicationContext, uris) }
    }
    fun photoPickerStart(limit: String): String = onUi { (activity as SharedActivity).startPhotoPicker(limit.toIntOrNull() ?: 1) }
    fun photoPickerPoll(id: String): String = onUi { (activity as SharedActivity).pollPhotoPicker(id) }
    fun photoPickerCancel(id: String): Boolean = onUi { (activity as SharedActivity).cancelPhotoPicker(id) }
    fun imagePreparePoll(id: String): String {
        val result = imageJobs.poll(id)
        return JSONObject().put("v", 1).put("status", result.state)
            .put("images", JSONArray(result.value?.map { it.dataUrl } ?: emptyList<String>()))
            .put("fingerprints", JSONArray(result.value?.map { image ->
                LocalImageAccess.sha256(java.util.Base64.getDecoder().decode(image.dataUrl.substringAfter(',')))
            } ?: emptyList<String>()))
            .put("error", if (result.state == "failed") "图片无法读取或超出限制，请重新选择较小图片" else "").toString()
    }
    fun imagePrepareCancel(id: String): Boolean { imageJobs.cancel(id); return true }
    fun imageDigest(uri: String): String = LocalImageAccess.digest(uri, listOf(activity.filesDir, activity.cacheDir))
    fun avatarForegroundStart(uri: String, segment: Boolean): String {
        val checked = LocalImageAccess.validate(uri, listOf(activity.filesDir, activity.cacheDir))
        return avatarJobs.start { AndroidAvatarForeground(activity.applicationContext).prepare(checked, segment) }
    }
    fun avatarForegroundPoll(id: String): String {
        val result = avatarJobs.poll(id)
        return JSONObject().put("v", 1).put("status", result.state)
            .put("width", result.value?.width ?: 0).put("height", result.value?.height ?: 0)
            .put("rgba", result.value?.let { java.util.Base64.getEncoder().encodeToString(it.pixels) } ?: "")
            .put("error", if (result.state == "failed") "人物分割未完成，请换用人物清晰的分身图片；原图已保留" else "").toString()
    }
    fun avatarForegroundCancel(id: String): Boolean { avatarJobs.cancel(id); return true }
    fun workflowStart(raw: String): String = startWorkflow(raw, interactive = false)
    fun workflowDeciderHttpAllowed(url: String): Boolean = com.clawmate.platform.mika.MikaVisionConfig.run {
        permitsDebugHttp(url, debugOrigin(activity))
    }
    fun workflowStartCollection(raw: String, runName: String): String =
        startWorkflow(raw, interactive = true, collectionRun = runName)
    fun workflowStartInteractive(raw: String): String = startWorkflow(raw, interactive = true)
    fun workflowStartGui(raw: String, destination: String): String {
        require(destination.isNotBlank() && destination.length <= 240 && destination.none(Char::isISOControl)) { "Decider 目标无效" }
        return startWorkflow(raw, interactive = true, destination = destination)
    }

    fun workflowStartMika(raw: String, url: String, model: String, key: String, visionJson: String, runName: String): String {
        val config = JSONObject(visionJson)
        val vision = com.clawmate.platform.mika.MikaVisionConfig.parse(config.optString("url"), config.optString("model"), config.optString("key"))
        return startWorkflow(raw, interactive = true, mika = com.clawmate.platform.gui.MikaWorkflowSession(activity, url, model, key, vision, runName))
    }

    private fun startWorkflow(raw: String, interactive: Boolean, destination: String = "",
        mika: com.clawmate.platform.gui.MikaWorkflowSession? = null, collectionRun: String? = null): String {
        require(raw.length <= 256000) { "工作流配置过长" }
        val mailbox = WorkflowModelMailbox()
        val session = mika ?: collectionRun?.let {
            com.clawmate.platform.gui.MikaWorkflowSession(activity, "", "", "", null, it, mailbox)
        }
        return try {
            val id = workflowJobs.start {
                try {
                    if (interactive) SharedNativeWork.runWorkflow(raw, mailbox, session) { definition ->
                        com.clawmate.platform.gui.GuiControlEntry.runWorkflow(activity, definition, mailbox, destination)
                    } else SharedNativeWork.runWorkflow(raw, mailbox)
                } finally { session?.close(); mailbox.close() }
            }
            workflowModels.keys.filter { it != id }.forEach { workflowModels.remove(it)?.close() }
            workflowModels[id] = mailbox
            id
        } catch (error: Exception) { mailbox.close(); throw error }
    }
    fun workflowPoll(id: String): String {
        val operation = workflowJobs.poll(id)
        val result = operation.value
        val detail = if (result == null) "" else JSONObject()
            .put("status", result.status.name.lowercase())
            .put("message", result.message).put("resultSummary", result.resultSummary)
            .put("collectionModelRequests", result.collectionModelRequests)
            .put("summaryEntries", JSONArray(result.summaryEntries.map { JSONObject().put("day", it.day).put("text", it.text) }))
            .put("steps", JSONArray(result.steps.map { step ->
                JSONObject().put("id", step.stepId).put("type", step.type).put("status", step.status.name.lowercase())
                    .put("message", step.note).put("detail", step.detail)
                    .put("output", step.output?.let { JSONObject(it.toString()) })
            })).toString()
        val tool = workflowModels[id]?.peek()
        if (operation.state != "running") workflowModels.remove(id)?.close()
        return JSONObject().put("v", 1).put("status", operation.state).put("result", detail)
            .put("tool", tool?.let { JSONObject().put("id", it.id).put("mode", it.mode).put("question", it.question)
                .put("captureOnly", it.captureOnly).put("captureDay", it.captureDay).put("image", it.image).put("schema", it.schema?.let { schema -> JSONObject(schema.toString()) }) }).toString()
    }
    fun workflowToolReply(id: String, request: String, raw: String): Boolean {
        require(raw.length <= 100000) { "模型结果超出限制" } // JSON escaping can expand the bounded 16K response.
        val mailbox = workflowModels[id] ?: return false
        if (mailbox.peek()?.id != request) return false
        val reply = JSONObject(raw)
        require(reply.keys().asSequence().all { it in setOf("response", "error") } &&
            (!reply.has("response") || reply.get("response") is String) && (!reply.has("error") || reply.get("error") is String)) { "模型回复格式无效" }
        return mailbox.reply(request, reply.optString("response"), reply.optString("error"))
    }
    fun workflowToolPending(id: String, request: String): Boolean = onUi {
        val pending = workflowModels[id]?.peek()?.takeIf { it.id == request } ?: return@onUi false
        if (pending.mode == "xhs_public_choice") {
            com.clawmate.platform.gui.GuiControlService.connected?.xhsModelWindowReady() == true
        } else if (pending.mode == "screen_collection") {
            com.clawmate.platform.gui.GuiControlService.connected?.screenModelWindowReady() == true
        } else (activity as SharedActivity).reminderWindowReady()
    }
    fun workflowCancel(id: String): Boolean { workflowModels.remove(id)?.close(); workflowJobs.cancel(id); return true }
    fun mikaHasExplicitApp(goal: String): Boolean = MikaPhoneEntry.hasExplicitApp(activity, goal)
    fun mikaPhoneStart(goal: String, url: String, model: String, key: String): String =
        startMika(goal, url, model, key, MikaPhoneMode.SCREEN)
    /**
     * 带看图模型的手机任务：视觉配置来自页面「云模型」里的 Decider 视觉模型。
     * 配置不合法与未配置一样按不可用处理，任务照常只靠控件树执行，不静默降级成假视觉。
     */
    fun mikaPhoneStartWithVision(goal: String, url: String, model: String, key: String,
        visionUrl: String, visionModel: String, visionKey: String): String {
        require(visionUrl.length <= 2048 && visionModel.length <= 180 && visionKey.length <= 4096)
        val vision = com.clawmate.platform.mika.MikaVisionConfig.parse(visionUrl, visionModel, visionKey)
        return startMika(goal, url, model, key, MikaPhoneMode.SCREEN, vision)
    }
    fun mikaSystemTaskStart(goal: String, url: String, model: String, key: String): String =
        startMika(goal, url, model, key, MikaPhoneMode.SYSTEM)
    fun mikaCalendarBatchStart(goal: String, url: String, model: String, key: String): String =
        startMika(goal, url, model, key, MikaPhoneMode.CALENDAR_BATCH)
    private fun startMika(goal: String, url: String, model: String, key: String, mode: MikaPhoneMode,
        vision: com.clawmate.platform.mika.MikaVisionConfig? = null,
    ): String {
        require(goal.length <= 1000 && url.length <= 2048 && model.length <= 180 && key.length <= 4096)
        return mikaJobs.startWithUpdates { progress, evidence ->
            runBlocking { MikaPhoneEntry.runCloud(activity, goal, url, model, key, mode, progress, vision, onEvidence = evidence) }
        }
    }
    /**
     * 让用户通过系统文件选择器授权一个目录给 Mika 只读使用。
     * 返回是否成功发起选择；结果在 SharedActivity 的 onActivityResult(7203) 里落盘。
     */
    fun deviceFilesPick(): Boolean = onUi {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        activity.startActivityForResult(intent, 7203)
        true
    }

    fun deviceFilesPicked(uri: android.net.Uri?) {
        if (uri == null) return
        runCatching {
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        com.clawmate.platform.files.DeviceFilesGrant.write(activity, uri)
    }

    fun deviceFilesGranted(): String =
        com.clawmate.platform.files.DeviceFilesGrant.read(activity)?.toString().orEmpty()

    fun deviceFilesRevoke(): Boolean {
        com.clawmate.platform.files.DeviceFilesGrant.read(activity)?.let { uri ->
            runCatching { activity.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        com.clawmate.platform.files.DeviceFilesGrant.write(activity, null)
        return true
    }

    fun mikaPhonePoll(id: String): String {
        val job = mikaJobs.poll(id)
        return JSONObject().put("state", job.state)
            .put("status", job.value?.status.orEmpty()).put("message", job.value?.message.orEmpty())
            .put("setupIssue", job.value?.setupIssue)
            .put("progress", job.progress).put("evidence", job.evidence).toString()
    }
    /** 最近的 Mika 运行摘要（手机任务与本机采集）；只读本机私有记录，不含截图。 */
    fun mikaRunHistory(raw: String): String {        val limit = runCatching { JSONObject(raw.ifBlank { "{}" }).optInt("limit", 20) }.getOrDefault(20)
        val records = com.clawmate.platform.gui.MikaRunLog.recent(activity, limit)
        return JSONObject().put("v", 1).put("records", JSONArray(records.map { record ->
            JSONObject().put("at", record.at).put("kind", record.kind).put("app", record.app)
                .put("label", record.label).put("goal", record.goal).put("status", record.status)
                .put("steps", record.steps).put("pages", record.pages).put("items", record.items)
                .put("note", record.note)
        })).toString()
    }
    fun mikaStopAndRead(id: String): String = mikaJobs.cancel(id)

    fun exportPhoneEvidence(text: String): String {
        require(text.isNotBlank() && text.length <= 16_000)
        val downloads = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "ClawMate-已读-" + System.currentTimeMillis() + ".txt")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/ClawMate")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = activity.contentResolver
        val uri = checkNotNull(resolver.insert(downloads, values))
        try {
            checkNotNull(resolver.openOutputStream(uri)).bufferedWriter(Charsets.UTF_8).use { it.write(text) }
            check(resolver.update(uri, android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null) == 1)
            return values.getAsString(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
        } catch (failure: Exception) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    fun mikaPhoneCancel(id: String): Boolean { mikaJobs.cancel(id); return true }
    private val memorySessions by lazy {
        com.clawmate.platform.mika.memory.MikaMemorySessions(com.clawmate.platform.mika.memory.MikaMemoryRuntime.of(activity))
    }
    fun mikaMemoryBegin(goal: String, conversation: String): String = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        memorySessions.begin(goal, conversation)
    }
    fun mikaMemoryBeginContext(goal: String, conversation: String): String = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        memorySessions.begin(goal, conversation,
            com.clawmate.platform.mika.memory.MikaConversationHistory.read(activity.filesDir, conversation))
    }
    fun mikaPhoneStartInConversation(goal: String, url: String, model: String, key: String,
        mode: String, visionJson: String, memoryId: String): String {
        require(goal.length <= 1000 && url.length <= 2048 && model.length <= 180 && key.length <= 4096 && visionJson.length <= 7000)
        val phoneMode = when (mode) {
            "mikaPhoneStart" -> MikaPhoneMode.SCREEN
            "mikaSystemTaskStart" -> MikaPhoneMode.SYSTEM
            "mikaCalendarBatchStart" -> MikaPhoneMode.CALENDAR_BATCH
            else -> error("invalid_phone_mode")
        }
        val v = JSONObject(visionJson)
        val vision = com.clawmate.platform.mika.MikaVisionConfig.parse(v.optString("url"), v.optString("model"), v.optString("key"))
        val turn = memorySessions.turn(memoryId, goal)
        return mikaJobs.startWithUpdates { progress, evidence -> runBlocking {
            MikaPhoneEntry.runCloud(activity, goal, url, model, key, phoneMode, progress, vision, turn, onEvidence = evidence)
        } }
    }
    fun mikaMemoryCall(id: String, name: String, args: String): String = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        memorySessions.call(id, name, args)
    }
    fun mikaMemoryClose(id: String): Boolean { memorySessions.close(id); return true }
    fun mikaMemoryFinish(id: String, url: String, model: String, key: String): String = mikaJobs.startWithProgress { progress ->
        progress("整理本次用户记忆")
        val count = runBlocking(kotlinx.coroutines.Dispatchers.IO) { memorySessions.finish(id, url, model, key) }
        com.clawmate.platform.gui.MikaPhoneResult("success", if (count > 0) "Mika 记忆更新 ${count} 条，可在「Mika 记忆」中查看和撤销。" else "")
    }
    fun mikaMemorySettings(): Boolean = onUi { com.clawmate.platform.mika.memory.MikaMemoryUi.show(activity); true }
    /** 一次运行的逐步明细（模型调用、工具结果、界面效果、确认与结束），供运行日志页展开。 */
    fun mikaRunEvents(raw: String): String {
        val id = runCatching { JSONObject(raw.ifBlank { "{}" }).optString("id") }.getOrDefault("")
        if (id.isBlank()) return JSONObject().put("v", 1).put("events", JSONArray()).toString()
        return com.clawmate.platform.mika.MikaRunRecordsBridge.eventsJson(
            java.io.File(activity.filesDir, "mika-runs"),
            id,
        )
    }
    /** 用系统分享导出一次运行的记录（上游 RunRecordExport 格式，JSONL 文本）。 */
    fun mikaRunExport(raw: String): String {
        val id = runCatching { JSONObject(raw.ifBlank { "{}" }).optString("id") }.getOrDefault("")
        if (id.isBlank()) {
            return JSONObject().put("ok", false).put("error", "缺少运行编号").toString()
        }
        val jsonl = com.clawmate.platform.mika.MikaRunExport.payloadJsonl(
            java.io.File(activity.filesDir, "mika-runs"),
            id,
        ) ?: return JSONObject().put("ok", false).put("error", "没有这次运行的记录").toString()
        if (jsonl.length > com.clawmate.platform.mika.MikaRunExport.MAX_SHARE_CHARS) {
            return JSONObject().put("ok", false).put("error", "记录超过文本分享上限，完整记录仍保存在本机").toString()
        }
        val opened = onUi { com.clawmate.platform.mika.MikaRunExport.share(activity, id, jsonl) }
        return JSONObject().put("ok", opened).put("chars", jsonl.length)
            .put("error", if (opened) "" else "无法打开系统分享界面").toString()
    }

    /** 落盘的完整运行记录：概要、结局、步数与最终回答，供运行日志页回看。 */
    fun mikaRunRecords(raw: String): String {
        val limit = runCatching { JSONObject(raw.ifBlank { "{}" }).optInt("limit", 10) }.getOrDefault(10)
        return com.clawmate.platform.mika.MikaRunRecordsBridge.json(
            java.io.File(activity.filesDir, "mika-runs"),
            limit,
        )
    }
    fun captureStart(): String = onUi { (activity as SharedActivity).startCapture() }
    fun guiScreenshotStart(): String = onUi {
        check((activity as SharedActivity).reminderWindowReady() && activity.window.decorView.hasWindowFocus()) { "请返回前台并解锁后截图" }
        val epoch = screenshotEpoch
        screenshotJobs.start { epoch to com.clawmate.platform.gui.WindowScreenshot(activity) {
            screenshotEpoch == epoch && (activity as SharedActivity).reminderWindowReady()
        }.capture() }
    }
    fun guiScreenshotPoll(id: String): String {
        val result = screenshotJobs.poll(id)
        val value = result.value?.takeIf { it.first == screenshotEpoch }?.second
        return JSONObject().put("v",1).put("status",if(result.state=="done" && value==null) "cancelled" else result.state).put("dataUrl",value?.dataUrl ?: "")
            .put("width",value?.width ?: 0).put("height",value?.height ?: 0).toString()
    }
    fun guiPlanReady(): Boolean = onUi { (activity as SharedActivity).reminderWindowReady() }
    fun guiFloatingRows(raw: String): Boolean = onUi { com.clawmate.platform.gui.FloatingNotes.update(activity, raw); true }
    fun guiFloatingSettings(): Boolean = onUi { com.clawmate.platform.gui.FloatingNotes.settings(activity); true }
    fun takeFloatingHomeRequest(): Boolean = onUi { (activity as SharedActivity).takeFloatingHomeRequest() }
    fun guiFloatingEnabled(): Boolean = android.os.Build.VERSION.SDK_INT >= 34 && com.clawmate.platform.gui.FloatingNotes.enabled(activity)
    fun guiSetFloatingEnabled(enabled: Boolean): Boolean = onUi {
        if (android.os.Build.VERSION.SDK_INT < 34) {
            com.clawmate.platform.gui.FloatingNotes.settings(activity)
            false
        } else {
            com.clawmate.platform.gui.FloatingNotes.setEnabled(activity, enabled)
            if (enabled && com.clawmate.platform.gui.GuiControlService.connected == null) com.clawmate.platform.gui.GuiControlEntry.settings(activity)
            enabled
        }
    }
    fun guiFloatingToggle(): Boolean = onUi {
        val notes = com.clawmate.platform.gui.FloatingNotes
        notes.setEnabled(activity, !notes.enabled(activity))
        true
    }
    fun guiTaskAccessibility(): String = onUi { (activity as SharedActivity).openTaskAccessibility() }
    fun guiTaskAccessibilityResult(ticket: String): String = onUi { (activity as SharedActivity).takeTaskAccessibilityResult(ticket) }
    fun guiControlSettings(): Boolean = onUi { com.clawmate.platform.gui.GuiControlEntry.settings(activity);true }
    fun calendarAppSettings(): Boolean = onUi {
        activity.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)))
        true
    }
    fun guiControlGrants(): Boolean = onUi { com.clawmate.platform.gui.GuiControlEntry.manualGrants(activity); true }
    fun guiWirelessPairing(): Boolean = onUi {
        activity.startActivity(Intent(activity, com.clawmate.platform.display.WirelessPairingActivity::class.java))
        true
    }
    fun guiWirelessAutoSetup(): String = onUi { (activity as SharedActivity).openChatWirelessSetup() }
    fun guiWirelessSetupResult(ticket: String): String = onUi { (activity as SharedActivity).takeChatWirelessResult(ticket) }
    fun mikaAppAccessSettings(): Boolean = onUi { com.clawmate.platform.gui.GuiControlEntry.mikaAppAccess(activity); true }
    /** 手机任务操作节奏：全局档位 + 按应用覆盖，写入应用私有 mika-pace.json。 */
    fun mikaPaceSettings(): Boolean = onUi { com.clawmate.platform.gui.MikaPaceUi.settings(activity); true }
    fun guiControlOpen(): Boolean = onUi { com.clawmate.platform.gui.GuiControlEntry.open(activity);true }
    fun guiCollectionOpen(): Boolean = onUi { com.clawmate.platform.gui.GuiControlEntry.open(activity, collectOnly = true);true }
    fun guiCollectionApps(): String = onUi { com.clawmate.platform.gui.GuiControlEntry.collectionApps(activity) }
    fun guiCollectionHistory(): Boolean = onUi { com.clawmate.platform.gui.GuiControlEntry.showCollections(activity);true }
    fun guiCollectionQuery(raw: String): String = com.clawmate.platform.gui.GuiCollectionSearch.query(activity.applicationContext, raw)
    fun guiControlStop(): Boolean = onUi { com.clawmate.platform.gui.GuiControlService.connected?.stop();true }
    fun guiControlStatus(): String = JSONObject().put("v",1)
        .put("supported",android.os.Build.VERSION.SDK_INT>=34)
        .put("connected",com.clawmate.platform.gui.GuiControlService.connected!=null)
        .put("floating",com.clawmate.platform.gui.FloatingNotes.enabled(activity))
        .put("helperAvailable",com.clawmate.platform.display.BackgroundDisplay.available)
        .put("message", com.clawmate.platform.gui.GuiControlService.lastStatus + "\n" +
            if (com.clawmate.platform.display.BackgroundDisplay.available) "后台屏助手已连接" else "后台屏助手未连接").toString()
    fun guiScreenshotCancel(id: String): Boolean { screenshotJobs.cancel(id); return true }
    fun guiScreenshotDetach() { screenshotEpoch++ }
    fun capturePoll(id: String): String = onUi { (activity as SharedActivity).pollCapture(id) }
    fun cameraDiscard(value: String): Boolean {
        val root = java.io.File(activity.cacheDir, "chat-camera").canonicalFile
        val uri = Uri.parse(value)
        if (uri.scheme != "file" || !uri.authority.isNullOrBlank()) return false
        val file = java.io.File(uri.path ?: return false).canonicalFile
        if (file.parentFile != root || !Regex("[0-9a-f-]{36}\\.jpg").matches(file.name)) return false
        return !file.exists() || file.delete()
    }

    fun asrStart(): Boolean = onUi {
        check(!meeting.capturing()) { "会议正在录音，请先结束会议录音" }
        meeting.stopPlayback()
        if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 7202)
            asrState = "error"; asrError = "请允许麦克风权限后重新按下语音按钮"; asrVersion++
            return@onUi false
        }
        ttsStop(); asr?.shutdown()
        asr = AndroidSpeechRecognizerAsrAdapter(activity.applicationContext)
        asrText = ""; asrError = ""; asrState = "listening"; asrVersion++
        when (val result = asr!!.start(object : AsrRecognitionCallbacks {
            override fun onStart() { asrState = "listening"; asrVersion++ }
            override fun onPartialTranscript(transcript: String) { asrText = transcript; asrVersion++ }
            override fun onFinalTranscript(transcript: String, isLast: Boolean) { asrText = transcript; asrState = "done"; asrVersion++ }
            override fun onComplete() { asrState = "recognizing"; asrVersion++ }
            override fun onError(message: String) { asrState = "error"; asrError = message; asrVersion++ }
        })) {
            AsrStartResult.Started -> true
            else -> {
                asrState = "error"
                asrError = when (result) {
                    is AsrStartResult.PermissionDenied -> result.message
                    is AsrStartResult.Unavailable -> result.message
                    is AsrStartResult.Failed -> result.message
                    else -> "无法启动系统识别"
                }
                asrVersion++; false
            }
        }
    }
    fun asrFinish(): Boolean = onUi { asr?.finish(); true }
    fun asrCancel(): Boolean = onUi { asr?.shutdown(); asr = null; asrState = "idle"; asrVersion++; true }

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
    fun speechRequestStart(url: String, key: String, body: String, stream: Boolean): String = onUi {
        check((activity as SharedActivity).reminderWindowReady()){"请返回前台并解锁后使用语音服务"}
        if(speechHttpDetached){speechHttp=SpeechHttp();speechHttpDetached=false}
        speechHttp.start(url,key,body,stream)
    }
    fun speechRequestPoll(id: String): String {
        val value=speechHttp.poll(id)
        return JSONObject().put("v",1).put("status",value.status).put("events",JSONArray(value.events)).put("body",value.body).put("error",value.error).toString()
    }
    fun speechRequestCancel(id: String): Boolean {speechHttp.cancel(id);return true}
    fun speechPcmStart(id: String): Boolean = onUi {
        check((activity as SharedActivity).reminderWindowReady()){"请返回前台并解锁后播放语音"}
        tts?.stop();fileSpeech.close();pcmSpeech.start(id);speechMode="pcm";true
    }
    fun speechPcmAppend(id: String, base64: String): Boolean {
        require(base64.length<=87384) {"语音分片过大"}
        return pcmSpeech.append(id,java.util.Base64.getDecoder().decode(base64))
    }
    fun speechPcmFinish(id: String): Boolean {pcmSpeech.finish(id);return true}
    fun speechFilePlay(id: String, uri: String): Boolean = onUi {
        check((activity as SharedActivity).reminderWindowReady()){"请返回前台并解锁后播放语音"}
        tts?.stop();pcmSpeech.stop();fileSpeech.play(id,uri);speechMode="file";true
    }
    fun speechPlaybackPause(paused: Boolean): Boolean = onUi {
        if(!paused)check((activity as SharedActivity).reminderWindowReady()){"请返回前台并解锁后继续语音"}
        if(speechMode=="pcm")pcmSpeech.pause(paused)else fileSpeech.pause(paused);true
    }
    fun speechPlaybackPoll(): String = onUi {
        val value=if(speechMode=="pcm")pcmSpeech.snapshot()else fileSpeech.snapshot()
        JSONObject().put("v",1).put("id",value.id).put("status",value.status).put("error",value.error).put("frames",value.frames).toString()
    }
    fun speechStop(id: String): Boolean = onUi {
        val current=if(speechMode=="pcm")pcmSpeech.snapshot()else fileSpeech.snapshot()
        if(id.isEmpty() || current.id==id){pcmSpeech.stop();fileSpeech.close();speechMode=""};true
    }
    fun speechDetach() {speechHttp.close();speechHttpDetached=true;speechStop("")}

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
            .put("asrState", asrState).put("asrText", asrText).put("asrError", asrError).put("asrVersion", asrVersion)
            .put("reminderVersion", reminders.version()).put("reminderRequest", (activity as SharedActivity).reminderRequest())
            .put("reminderReady", (activity as SharedActivity).reminderWindowReady())
            .put("windowReady", activity.reminderWindowReady())
            .toString()
    }

    override fun close() {
        wechatSteps.close(); onUi { wechat.close() }
        localInference.close(); ggufPicker.close()
        if (codexBridge.isInitialized()) codex.close()
        if (codexPickers.isInitialized()) pickers.close()
        sourceAuth.close(); sources.close(); sourceJobs.close(); modelDownloader.close()
        guiScreenshotDetach();screenshotJobs.close()
        speechHttp.close();pcmSpeech.close();onUi{fileSpeech.close()}
        meeting.close()
        ocrJobs.close()
        avatarJobs.close()
        imageJobs.close(); workflowModels.values.forEach { it.close() }; workflowModels.clear(); workflowJobs.close(); mikaJobs.close(); asrCancel()
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

    /**
     * Opens an http(s) link in whatever browser the user has.
     *
     * The Codex help page needs this because ArkUI-X has no cross-platform equivalent of the
     * HarmonyOS `context.openLink`. Only http(s) is accepted: a page URL can come from the
     * bridge's guide content, and other schemes could reach unrelated apps.
     *
     * @return false when the URL is rejected or no app can handle it, so the page can tell
     *   the user to copy the link instead.
     */
    fun openLink(value: String): Boolean = onUi {
        val uri = android.net.Uri.parse(value)
        if (uri.scheme != "https" && uri.scheme != "http") return@onUi false
        try {
            activity.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (e: Exception) {
            false
        }
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





package com.clawmate.app.chat.voice

import android.content.Context
import com.clawmate.app.chat.voice.asr.AndroidSpeechRecognizerAsrAdapter
import com.clawmate.app.chat.voice.asr.AsrRecognitionCallbacks
import com.clawmate.app.chat.voice.asr.AsrStartResult
import com.clawmate.app.chat.voice.tts.AndroidTextToSpeechTtsAdapter
import com.clawmate.app.chat.voice.tts.TtsInitResult
import com.clawmate.app.chat.voice.tts.TtsInitState
import com.clawmate.app.chat.voice.tts.TtsSpeakListener
import com.clawmate.app.chat.voice.tts.TtsSpeakResult
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Voice UI state shared with [ChatScreen] — combines the pure
 * [VoiceSessionSnapshot] with the Android capability surfaces (ASR permission /
 * availability, TTS readiness) so the UI can render the correct controls and
 * explain every unavailable/permission/error condition.
 */
data class VoiceControllerState(
    val session: VoiceSessionSnapshot = VoiceSessionSnapshot(),
    val asrRecordPermissionGranted: Boolean = false,
    val asrAvailable: Boolean = false,
    val ttsReady: Boolean = false,
    val ttsInitializing: Boolean = false,
    val permissionRequirementVisible: Boolean = false,
    /** Message currently being voiced (locked to its id for speak/pause/replay). */
    val voicingMessageId: String = "",
)

/**
 * Operation result surfaced for the UI / tests.
 */
sealed class VoiceActionResult {
    object Ok : VoiceActionResult()
    data class InputBlocked(val message: String) : VoiceActionResult()
    data class PermissionRequired(val message: String) : VoiceActionResult()
    data class Unavailable(val message: String) : VoiceActionResult()
    data class TtsNotReady(val message: String) : VoiceActionResult()
}

/**
 * Orchestrator driving on-device ASR (input) and Android TTS (output) into the
 * chat session. Port of `ChatVoiceOrchestrator.ets` scoped to the Android
 * adapters.
 *
 * Truthfulness contract:
 *  - The voice controller never requests RECORD_AUDIO on its own. When the
 *    runtime permission is missing, [toggleVoiceInput] returns
 *    [VoiceActionResult.PermissionRequired] and sets
 *    [VoiceControllerState.permissionRequirementVisible] so the UI can surface
 *    the requirement. The UI (not this controller) requests the permission.
 *  - No fake transcript: the chat input is only updated from a real final ASR
 *    transcript (via [VoiceSessionController] state).
 *  - Cloud MiMO streaming voice is NOT used (see [com.clawmate.app.chat.voice.tts.CloudMimoVoiceClient]);
 *    output voice is Android TextToSpeech only, and only when it reports ready.
 */
class ChatVoiceController(
    private val context: Context,
) {

    private val asrAdapter = AndroidSpeechRecognizerAsrAdapter(context)
    private val ttsAdapter = AndroidTextToSpeechTtsAdapter(context)
    private val sessionController = VoiceSessionController()

    private var playbackGeneration: Int = 0
    private var voicingMessageId: String = ""
    private var voicingText: String = ""

    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<VoiceControllerState> = _state.asStateFlow()

    /** Host integration seams set by the owning ViewModel/UI. */
    private var activeInput: () -> String = { "" }
    private var setInput: (String) -> Unit = {}
    private var isChatBusy: () -> Boolean = { false }

    private var voiceDraftBaseInput: String = ""
    private var voiceDraftTurnId: String = ""
    private var handledTurnId: String = ""

    // Voice-input start/stop generation guards (pure, ported).
    private var voiceInputStartGeneration: Int = 0
    private var voiceInputStoppedGeneration: Int = 0

    fun setHost(
        activeInputProvider: () -> String,
        setInputSink: (String) -> Unit,
        isBusyProvider: () -> Boolean,
    ) {
        this.activeInput = activeInputProvider
        this.setInput = setInputSink
        this.isChatBusy = isBusyProvider
    }

    private fun buildState(): VoiceControllerState =
        VoiceControllerState(
            session = sessionController.snapshot(),
            asrRecordPermissionGranted = asrAdapter.hasRecordPermission(),
            asrAvailable = asrAdapter.isRecognizerAvailable(),
            ttsReady = ttsAdapter.ready,
            ttsInitializing = ttsAdapter.state == TtsInitState.INITIALIZING,
            permissionRequirementVisible = false,
            // Keep the UI state contract non-null even if this method is ever
            // called during a partially initialized controller.
            voicingMessageId = voicingMessageId.orEmpty(),
        )

    private fun emitRoomState() {
        _state.value = buildState()
    }

    fun snapshot(): VoiceSessionSnapshot = sessionController.snapshot()

    /**
     * Toggles voice input. Mirrors `toggleVoiceInput`: while listening it
     * stops the input; while voicing it stops playback; otherwise starts a new
     * ASR turn (blocked if the chat/agent loop is busy).
     */
    fun toggleVoiceInput(): VoiceActionResult {
        val status = snapshot().status
        return when (status) {
            VoiceInteractionStatus.LISTENING,
            VoiceInteractionStatus.TRANSCRIBING,
            -> stopVoiceInput()

            VoiceInteractionStatus.SPEAKING,
            VoiceInteractionStatus.SYNTHESIZING,
            VoiceInteractionStatus.PAUSED,
            -> {
                stopPlayback()
                VoiceActionResult.Ok
            }
            else -> startVoiceInput()
        }
    }

    fun startVoiceInput(): VoiceActionResult {
        if (isChatBusy()) {
            return VoiceActionResult.InputBlocked("当前正在处理消息，待完成后再使用语音")
        }
        if (!asrAdapter.hasRecordPermission()) {
            _state.value = _state.value.copy(permissionRequirementVisible = true)
            return VoiceActionResult.PermissionRequired("需要录音权限（RECORD_AUDIO）才能语音输入")
        }
        if (!asrAdapter.isRecognizerAvailable()) {
            return VoiceActionResult.Unavailable("当前设备没有可用的语音识别服务")
        }
        val startGeneration = VoiceOrchestratorLogic.nextVoiceInputStartGeneration(voiceInputStartGeneration)
        voiceInputStartGeneration = startGeneration
        // Re-check permission inside the guarded start (no hidden mic access).
        if (!asrAdapter.hasRecordPermission()) {
            _state.value = _state.value.copy(permissionRequirementVisible = true)
            return VoiceActionResult.PermissionRequired("需要录音权限（RECORD_AUDIO）才能语音输入")
        }
        if (!voiceInputStartActive(startGeneration)) {
            return VoiceActionResult.Ok
        }
        val draftTargetText = activeInput()
        voiceDraftBaseInput = draftTargetText
        val turnId = "voice-" + System.currentTimeMillis().toString() + "-" + UUID.randomUUID().toString().take(6)
        voiceDraftTurnId = turnId
        handledTurnId = ""
        sessionController.beginListening(turnId)
        emitRoomState()

        val result = asrAdapter.start(
            object : AsrRecognitionCallbacks {
                override fun onStart() {
                    if (!voiceTurnActive(turnId)) return
                    sessionController.beginListening(turnId)
                    emitRoomState()
                }

                override fun onPartialTranscript(transcript: String) {
                    if (!voiceTurnActive(turnId)) return
                    sessionController.updatePartialTranscript(transcript)
                    emitRoomState()
                }

                override fun onFinalTranscript(transcript: String, isLast: Boolean) {
                    if (!voiceTurnActive(turnId)) return
                    sessionController.completeTranscript(transcript)
                    emitRoomState()
                    if (isLast) handleTranscriptComplete(transcript)
                }

                override fun onComplete() {
                    if (!voiceTurnActive(turnId)) return
                    handleTranscriptComplete(snapshot().finalTranscript)
                }

                override fun onError(message: String) {
                    if (!voiceTurnActive(turnId)) return
                    failVoiceInteraction(message)
                }
            },
            com.clawmate.app.chat.voice.asr.AsrStartOptions(language = "zh-CN"),
        )
        return when (result) {
            AsrStartResult.Started -> {
                if (!voiceInputStartActive(startGeneration)) {
                    if (voiceTurnActive(turnId)) asrAdapter.cancel()
                }
                VoiceActionResult.Ok
            }
            is AsrStartResult.PermissionDenied -> {
                _state.value = _state.value.copy(permissionRequirementVisible = true)
                VoiceActionResult.PermissionRequired(result.message)
            }
            is AsrStartResult.Unavailable -> VoiceActionResult.Unavailable(result.message)
            is AsrStartResult.Failed -> VoiceActionResult.Unavailable(result.message)
        }
    }

    fun stopVoiceInput(): VoiceActionResult {
        voiceInputStoppedGeneration = VoiceOrchestratorLogic.voiceInputStopGeneration(
            voiceInputStartGeneration, voiceInputStoppedGeneration,
        )
        val status = snapshot().status
        if (status == VoiceInteractionStatus.LISTENING || status == VoiceInteractionStatus.TRANSCRIBING) {
            sessionController.markTranscribing()
            emitRoomState()
            asrAdapter.finish()
        }
        return VoiceActionResult.Ok
    }

    private fun voiceTurnActive(turnId: String): Boolean =
        snapshot().turnId == turnId

    private fun voiceInputStartActive(startGeneration: Int): Boolean =
        VoiceOrchestratorLogic.voiceInputStartCanProceed(
            startGeneration, voiceInputStartGeneration, voiceInputStoppedGeneration,
        )

    private fun shouldHandleTranscriptComplete(): Boolean {
        if (snapshot().turnId.isEmpty()) return false
        if (handledTurnId == snapshot().turnId) return false
        handledTurnId = snapshot().turnId
        return true
    }

    private fun handleTranscriptComplete(transcript: String) {
        if (!shouldHandleTranscriptComplete()) return
        val finalTranscript = transcript.trim().ifEmpty { snapshot().finalTranscript.trim() }
        if (finalTranscript.isEmpty()) {
            sessionController.completeWithoutTranscript()
            voiceDraftBaseInput = ""
            voiceDraftTurnId = ""
            emitRoomState()
            return
        }
        sessionController.completeTranscript(finalTranscript)
        val appended = appendVoiceTranscriptToInput(voiceDraftBaseInput, finalTranscript)
        setInput(appended)
        sessionController.reset()
        voiceDraftBaseInput = ""
        voiceDraftTurnId = ""
        emitRoomState()
    }

    private fun failVoiceInteraction(message: String) {
        asrAdapter.cancel()
        sessionController.fail(message)
        emitRoomState()
    }

    // ----------------------------------------------------------------------
    // Voice output (Android TextToSpeech)
    // ----------------------------------------------------------------------

    /**
     * Ensures the Android TTS engine is initialized. Returns [VoiceActionResult]. *
     * No cloud voice is claimed; only a ready local engine speaks.
     */
    fun ensureTtsInitialized(): VoiceActionResult {
        if (ttsAdapter.ready) return VoiceActionResult.Ok
        when (val result = ttsAdapter.initialize(object : TtsSpeakListener {
            override fun onStarted(utteranceId: String) {
                if (voicingMessageId.isEmpty()) return
                sessionController.beginSpeaking(voicingMessageId)
                emitRoomState()
            }

            override fun onDone(utteranceId: String) {
                if (sessionController.snapshot().status == VoiceInteractionStatus.SPEAKING ||
                    sessionController.snapshot().status == VoiceInteractionStatus.PAUSED
                ) {
                    sessionController.finishSpeaking()
                }
                voicingMessageId = ""
                emitRoomState()
            }

            override fun onError(utteranceId: String, message: String) {
                if (voicingMessageId.isEmpty()) return
                failVoiceInteraction("语音播放失败：$message")
            }
        })) {
            TtsInitResult.Ready -> { emitRoomState(); return VoiceActionResult.Ok }
            is TtsInitResult.Unavailable -> return VoiceActionResult.Unavailable("当前设备没有可用的语音引擎")
            is TtsInitResult.Failed -> return VoiceActionResult.Unavailable(result.message)
        }
    }

    /**
     * Speaks [text] as an assistant message via Android TTS. Starts the
     * lifecycle-safe engine if needed. Renders a NOT_CONFIGURED explanation when
     * cloud MiMO voice is requested instead (never faked).
     */
    fun speakMessage(messageId: String, text: String): VoiceActionResult {
        if (messageId.isEmpty() || text.trim().isEmpty()) return VoiceActionResult.Ok
        val sidStatus = snapshot().status
        // Toggle semantics: speaking the same message pauses/resumes.
        if (voicingMessageId == messageId && !textChanged(messageId, text)) {
            when (sidStatus) {
                VoiceInteractionStatus.SPEAKING -> { ttsAdapter.pause(); sessionController.pauseSpeaking(messageId); emitRoomState(); return VoiceActionResult.Ok }
                VoiceInteractionStatus.PAUSED -> { ttsAdapter.resume(); sessionController.beginSpeaking(messageId); emitRoomState(); return VoiceActionResult.Ok }
                else -> { /* fall through to re-speak on restart */ }
            }
        }
        stopPlayback()
        val init = ensureTtsInitialized()
        if (init != VoiceActionResult.Ok) {
            // Cloud MiMO voice is the only other option and it is NOT configured
            // on Android. Surface that clearly.
            sessionController.beginSynthesizing(messageId)
            emitRoomState()
            return VoiceActionResult.Unavailable(
                "云端语音合成未配置；当前设备未就绪的本地语音引擎也无法朗读",
            )
        }
        val speakResult = ttsAdapter.speak(text)
        return when (speakResult) {
            TtsSpeakResult.Accepted -> {
                setVoicing(messageId, text)
                VoiceActionResult.Ok
            }
            is TtsSpeakResult.NotReady -> VoiceActionResult.TtsNotReady("语音引擎未就绪（${speakResult.reason}）")
            is TtsSpeakResult.RichTypeError -> VoiceActionResult.TtsNotReady(speakResult.message)
        }
    }

    private fun textChanged(messageId: String, text: String): Boolean =
        voicingMessageId != messageId || voicingText.trim() != text.trim()

    private fun setVoicing(messageId: String, text: String) {
        voicingMessageId = messageId
        voicingText = text
        emitRoomState()
    }

    /** Replays a previously voiced message (or a freshly supplied one). */
    fun replayMessage(messageId: String, text: String): VoiceActionResult {
        val toSay = text.trim().ifEmpty { null }
        val replay = ttsAdapter.replay(toSay)
        return when (replay) {
            TtsSpeakResult.Accepted -> {
                if (voicingMessageId.isEmpty() && messageId.isNotEmpty()) {
                    voicingMessageId = messageId
                }
                emitRoomState()
                VoiceActionResult.Ok
            }
            is TtsSpeakResult.NotReady -> VoiceActionResult.TtsNotReady("暂无重播语音")
            is TtsSpeakResult.RichTypeError -> VoiceActionResult.TtsNotReady("重播失败：${replay.message}")
        }
    }

    /** Stops any voice playback and clears the voicing bubble id. */
    fun stopPlayback() {
        playbackGeneration++
        ttsAdapter.stop()
        val wasVoicing = voicingMessageId.isNotEmpty()
        voicingMessageId = ""
        voicingText = ""
        if (wasVoicing || snapshot().status == VoiceInteractionStatus.SPEAKING ||
            snapshot().status == VoiceInteractionStatus.PAUSED ||
            snapshot().status == VoiceInteractionStatus.SYNTHESIZING
        ) {
            sessionController.finishSpeaking()
            emitRoomState()
        }
    }

    /** Frees ASR + TTS resources (call from ViewModel.onCleared). */
    fun dispose() {
        asrAdapter.shutdown()
        ttsAdapter.release()
        sessionController.reset()
        voicingMessageId = ""
        voicingText = ""
        emitRoomState()
    }
}

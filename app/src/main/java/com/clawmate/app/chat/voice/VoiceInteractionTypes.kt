package com.clawmate.app.chat.voice

/**
 * Typed voice domain models for the Phase-7 voice interaction vertical slice.
 *
 * Port of HarmonyOS `entry/src/main/ets/pages/chat/VoiceInteractionTypes.ets`.
 * These are pure, deterministic data types + normalization functions so they
 * can be unit-tested without a device. The Android ASR / TTS adapters drive a
 * [VoiceSessionController] built from these types.
 *
 * Truthfulness contract: a transcript injected into the chat input is only ever
 * a *real* final transcript delivered by an ASR provider; `createAsrVoiceMetadata`
 * records the provider that actually produced it. Cloud/TTS voice playback is
 * never claimed configured unless a provider reports `ready`/`playing`.
 */
enum class VoiceInteractionStatus {
    IDLE, LISTENING, TRANSCRIBING, THINKING, SYNTHESIZING, SPEAKING, PAUSED, ERROR;

    companion object {
        fun fromWire(value: String?): VoiceInteractionStatus =
            entries.firstOrNull { it.name.lowercase() == value?.lowercase() } ?: IDLE
    }
}

enum class ChatVoiceInputKind {
    NONE, ASR, TTS;

    companion object {
        fun fromWire(value: String?): ChatVoiceInputKind =
            when (value) {
                "asr" -> ASR
                "tts" -> TTS
                else -> NONE
            }
    }
}

enum class ChatVoiceMetadataStatus {
    NONE, TRANSCRIBED, SYNTHESIZING, READY, PLAYING, PAUSED, ERROR;

    companion object {
        fun fromWire(value: String?): ChatVoiceMetadataStatus =
            when (value) {
                "transcribed" -> TRANSCRIBED
                "synthesizing" -> SYNTHESIZING
                "ready" -> READY
                "playing" -> PLAYING
                "paused" -> PAUSED
                "error" -> ERROR
                else -> NONE
            }
    }
}

enum class TtsVoiceRuntimeStatus {
    SYNTHESIZING, READY, PLAYING, PAUSED;

    companion object {
        fun fromWire(value: String?): TtsVoiceRuntimeStatus =
            when (value) {
                "synthesizing" -> SYNTHESIZING
                "playing" -> PLAYING
                "paused" -> PAUSED
                else -> READY
            }
    }
}

/** Immutable snapshot of the active voice session (port of `VoiceSessionSnapshot`). */
data class VoiceSessionSnapshot(
    val status: VoiceInteractionStatus = VoiceInteractionStatus.IDLE,
    val turnId: String = "",
    val partialTranscript: String = "",
    val finalTranscript: String = "",
    val activeMessageId: String = "",
    val error: String = "",
)

/** Per-message voice metadata (port of `ChatVoiceMetadata`). */
data class ChatVoiceMetadata(
    val input: ChatVoiceInputKind = ChatVoiceInputKind.NONE,
    val provider: String = "",
    val status: ChatVoiceMetadataStatus = ChatVoiceMetadataStatus.NONE,
    val transcript: String = "",
    val confidence: Double = 0.0,
    val audioUri: String = "",
    val voice: String = "",
    val error: String = "",
    val createdAt: Long = 0L,
)

fun createEmptyVoiceSessionSnapshot(): VoiceSessionSnapshot = VoiceSessionSnapshot()

fun createEmptyChatVoiceMetadata(): ChatVoiceMetadata = ChatVoiceMetadata()

/**
 * Appends a transcript to the current draft input (port of
 * `appendVoiceTranscriptToInput`). Returns the *existing* input unchanged when
 * the normalized transcript is empty, so a phantom transcript never changes the
 * editable chat field.
 */
fun appendVoiceTranscriptToInput(currentInput: String, transcript: String): String {
    val normalizedTranscript = transcript.trim()
    if (normalizedTranscript.isEmpty()) return currentInput
    val current = currentInput
    if (current.trim().isEmpty()) return normalizedTranscript
    if (current.endsWith('\n') || current.endsWith(' ')) return current + normalizedTranscript
    return current + '\n' + normalizedTranscript
}

private fun normalizeConfidence(value: Double): Double {
    if (value <= 0.0) return 0.0
    if (value >= 1.0) return 1.0
    return value
}

/** Normalizes a possibly-malformed [ChatVoiceMetadata] into a valid one (port of `normalizeChatVoiceMetadata`). */
fun normalizeChatVoiceMetadata(raw: ChatVoiceMetadata): ChatVoiceMetadata =
    ChatVoiceMetadata(
        input = ChatVoiceInputKind.fromWire(if (raw.input.name == "ASR") "asr" else if (raw.input.name == "TTS") "tts" else null),
        provider = raw.provider.trim(),
        status = ChatVoiceMetadataStatus.fromWire(
            if (raw.status.name == "TRANSCRIBED") "transcribed"
            else if (raw.status.name == "SYNTHESIZING") "synthesizing"
            else if (raw.status.name == "READY") "ready"
            else if (raw.status.name == "PLAYING") "playing"
            else if (raw.status.name == "PAUSED") "paused"
            else if (raw.status.name == "ERROR") "error"
            else "none"
        ),
        transcript = raw.transcript.trim(),
        confidence = normalizeConfidence(raw.confidence),
        audioUri = raw.audioUri.trim(),
        voice = raw.voice.trim(),
        error = raw.error.trim(),
        createdAt = raw.createdAt,
    )

/** True when any voice field is populated (port of `chatVoiceMetadataPresent`). */
fun chatVoiceMetadataPresent(metadata: ChatVoiceMetadata): Boolean =
    metadata.input != ChatVoiceInputKind.NONE ||
        metadata.provider.isNotEmpty() ||
        metadata.status != ChatVoiceMetadataStatus.NONE ||
        metadata.transcript.isNotEmpty() ||
        metadata.audioUri.isNotEmpty() ||
        metadata.voice.isNotEmpty() ||
        metadata.error.isNotEmpty()

/** Returns the normalized metadata, or null when it is entirely empty (port of `optionalChatVoiceMetadata`). */
fun optionalChatVoiceMetadata(raw: ChatVoiceMetadata): ChatVoiceMetadata? {
    val normalized = normalizeChatVoiceMetadata(raw)
    return if (chatVoiceMetadataPresent(normalized)) normalized else null
}

/** Factory for an ASR-delivered transcript metadata (port of `createAsrVoiceMetadata`). Truthful: records the real provider. */
fun createAsrVoiceMetadata(provider: String, transcript: String, confidence: Double, createdAt: Long): ChatVoiceMetadata =
    normalizeChatVoiceMetadata(
        ChatVoiceMetadata(
            input = ChatVoiceInputKind.ASR,
            provider = provider,
            status = ChatVoiceMetadataStatus.TRANSCRIBED,
            transcript = transcript,
            confidence = confidence,
            createdAt = createdAt,
        )
    )

fun createTtsVoiceMetadata(provider: String, voice: String, audioUri: String, createdAt: Long): ChatVoiceMetadata =
    createTtsVoiceRuntimeMetadata(
        provider,
        voice,
        audioUri,
        if (audioUri.trim().isNotEmpty()) TtsVoiceRuntimeStatus.READY else TtsVoiceRuntimeStatus.SYNTHESIZING,
        createdAt,
    )

fun createTtsVoiceRuntimeMetadata(
    provider: String,
    voice: String,
    audioUri: String,
    status: TtsVoiceRuntimeStatus,
    createdAt: Long,
): ChatVoiceMetadata {
    val metaStatus = when (status) {
        TtsVoiceRuntimeStatus.SYNTHESIZING -> ChatVoiceMetadataStatus.SYNTHESIZING
        TtsVoiceRuntimeStatus.READY -> ChatVoiceMetadataStatus.READY
        TtsVoiceRuntimeStatus.PLAYING -> ChatVoiceMetadataStatus.PLAYING
        TtsVoiceRuntimeStatus.PAUSED -> ChatVoiceMetadataStatus.PAUSED
    }
    return normalizeChatVoiceMetadata(
        ChatVoiceMetadata(
            input = ChatVoiceInputKind.TTS,
            provider = provider,
            status = metaStatus,
            audioUri = audioUri,
            voice = voice,
            createdAt = createdAt,
        )
    )
}

fun createVoiceErrorMetadata(input: ChatVoiceInputKind, provider: String, error: String, createdAt: Long): ChatVoiceMetadata =
    normalizeChatVoiceMetadata(
        ChatVoiceMetadata(
            input = input,
            provider = provider,
            status = ChatVoiceMetadataStatus.ERROR,
            error = error,
            createdAt = createdAt,
        )
    )

/** User-facing status label (port of `voiceStatusLabel`). */
fun voiceStatusLabel(status: VoiceInteractionStatus): String =
    when (status) {
        VoiceInteractionStatus.LISTENING -> "正在聆听..."
        VoiceInteractionStatus.TRANSCRIBING -> "正在转写..."
        VoiceInteractionStatus.THINKING -> "正在思考..."
        VoiceInteractionStatus.SYNTHESIZING -> "正在合成语音..."
        VoiceInteractionStatus.SPEAKING -> "正在朗读..."
        VoiceInteractionStatus.PAUSED -> "已暂停朗读"
        VoiceInteractionStatus.ERROR -> "语音出错"
        VoiceInteractionStatus.IDLE -> ""
    }

/**
 * Whether a voice replay of a message is available (port of `voiceReplayAvailable`).
 * A streaming-TTS synthesizing phase blocks every bubble replay (replay would
 * cancel the current request and truncate buffered PCM); per-message
 * synthesizing also blocks replay of that message.
 */
fun voiceReplayAvailable(
    audioUri: String,
    globalStatus: VoiceInteractionStatus,
    currentMessageSynthesizing: Boolean,
    globalReplayBlocked: Boolean = false,
): Boolean {
    if (audioUri.trim().isEmpty()) return false
    if (globalStatus == VoiceInteractionStatus.SYNTHESIZING || globalReplayBlocked) return false
    return !currentMessageSynthesizing
}

/**
 * Whether a global replay-block is needed while a message is playing (port of
 * `voiceReplayGlobalBlockNeeded`).
 */
fun voiceReplayGlobalBlockNeeded(
    globalStatus: VoiceInteractionStatus,
    activeMessageId: String,
    activeMessageVisible: Boolean,
    activeVoiceStatus: ChatVoiceMetadataStatus,
    activeAudioUri: String,
): Boolean {
    if (globalStatus == VoiceInteractionStatus.SYNTHESIZING) return true
    if (activeMessageId.isEmpty()) return false
    if (!activeMessageVisible) return globalStatus == VoiceInteractionStatus.SPEAKING ||
        globalStatus == VoiceInteractionStatus.PAUSED
    // During streaming TTS the first PCM triggers 'speaking' before the cache
    // audioUri is persisted; replay is still unsafe until audioUri is ready.
    return (activeVoiceStatus == ChatVoiceMetadataStatus.SYNTHESIZING ||
        activeVoiceStatus == ChatVoiceMetadataStatus.PLAYING ||
        activeVoiceStatus == ChatVoiceMetadataStatus.PAUSED) && activeAudioUri.isEmpty()
}

/**
 * Pure state machine over [VoiceSessionSnapshot] (port of `VoiceSessionController`).
 * The status transitions mirrored from the HarmonyOS controller are pure and
 * synchronously observable, so this is unit-testable without any device.
 */
class VoiceSessionController {

    private var state: VoiceSessionSnapshot = createEmptyVoiceSessionSnapshot()

    fun snapshot(): VoiceSessionSnapshot = state

    fun isBusy(): Boolean = state.status != VoiceInteractionStatus.IDLE &&
        state.status != VoiceInteractionStatus.ERROR

    fun beginListening(turnId: String): VoiceSessionSnapshot {
        state = VoiceSessionSnapshot(
            status = VoiceInteractionStatus.LISTENING,
            turnId = turnId,
        )
        return snapshot()
    }

    fun updatePartialTranscript(transcript: String): VoiceSessionSnapshot {
        if (state.status != VoiceInteractionStatus.LISTENING &&
            state.status != VoiceInteractionStatus.TRANSCRIBING
        ) {
            return snapshot()
        }
        state = state.copy(partialTranscript = transcript)
        return snapshot()
    }

    fun completeTranscript(transcript: String): VoiceSessionSnapshot {
        if (state.status != VoiceInteractionStatus.LISTENING &&
            state.status != VoiceInteractionStatus.TRANSCRIBING
        ) {
            return snapshot()
        }
        val final = transcript.trim()
        state = state.copy(
            status = VoiceInteractionStatus.TRANSCRIBING,
            finalTranscript = final,
            partialTranscript = final,
        )
        return snapshot()
    }

    fun markTranscribing(): VoiceSessionSnapshot {
        if (state.status != VoiceInteractionStatus.LISTENING &&
            state.status != VoiceInteractionStatus.TRANSCRIBING
        ) {
            return snapshot()
        }
        state = state.copy(status = VoiceInteractionStatus.TRANSCRIBING, error = "")
        return snapshot()
    }

    fun completeWithoutTranscript(): VoiceSessionSnapshot {
        // Empty recognition is not a voice-service error: return to idle and
        // avoid leaving the UI on an error or status placeholder.
        state = createEmptyVoiceSessionSnapshot()
        return snapshot()
    }

    fun markThinking(): VoiceSessionSnapshot {
        state = state.copy(status = VoiceInteractionStatus.THINKING, error = "")
        return snapshot()
    }

    fun beginSynthesizing(messageId: String): VoiceSessionSnapshot {
        state = state.copy(
            status = VoiceInteractionStatus.SYNTHESIZING,
            activeMessageId = messageId,
            error = "",
        )
        return snapshot()
    }

    fun beginSpeaking(messageId: String): VoiceSessionSnapshot {
        state = state.copy(
            status = VoiceInteractionStatus.SPEAKING,
            activeMessageId = messageId,
            error = "",
        )
        return snapshot()
    }

    fun pauseSpeaking(messageId: String): VoiceSessionSnapshot {
        // Pausing only affects the TTS playback state; keep activeMessageId so
        // the bubble can still locate the resumable message.
        state = state.copy(
            status = VoiceInteractionStatus.PAUSED,
            activeMessageId = messageId,
            error = "",
        )
        return snapshot()
    }

    fun finishSpeaking(): VoiceSessionSnapshot {
        state = createEmptyVoiceSessionSnapshot()
        return snapshot()
    }

    fun fail(message: String): VoiceSessionSnapshot {
        state = state.copy(
            status = VoiceInteractionStatus.ERROR,
            error = message.trim(),
        )
        return snapshot()
    }

    fun reset(): VoiceSessionSnapshot {
        state = createEmptyVoiceSessionSnapshot()
        return snapshot()
    }
}

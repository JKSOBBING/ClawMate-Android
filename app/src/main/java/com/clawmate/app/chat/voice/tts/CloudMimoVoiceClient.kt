package com.clawmate.app.chat.voice.tts

import com.clawmate.app.chat.voice.VoiceTtsPresets

/**
 * Cloud MiMO voice streaming interface, kept truthfully behind an explicit
 * NOT_CONFIGURED seam.
 *
 * Ports the *pure* request-building and response-parsing contract from
 * `entry/src/main/ets/utils/voice/MimoVoiceClient.ets` (so the wire shape is
 * deterministic and unit-tested), but the live network path is **NOT
 * configured** on Android in this phase: there is no voice-TTS baseUrl/apiKey
 * setting yet, so `synthesizeSpeech` / `synthesizeSpeechStream` return
 * [MimoVoiceResult.NotConfigured] and never fabricate audio.
 */
object MimoVoiceClient {

    private const val DEFAULT_TTS_MODEL = "mimo-v2.5-tts"

    enum class MimoAudioFormat { WAV, PCM16 }
    enum class MimoAsrAudioFormat { WAV, MP3 }
    enum class MimoAsrLanguage { AUTO, ZH, EN }

    data class MimoTtsRequest(
        val model: String,
        val messages: List<MimoVoiceMessage>,
        val format: MimoAudioFormat,
        val voice: String,
        val stream: Boolean,
    )

    data class MimoVoiceMessage(
        val role: String,
        val content: String,
    )

    /** Builds a TTS request body (port of `buildTtsRequest` / `buildStreamingTtsRequest`). */
    fun buildStreamingTtsRequest(text: String, selectedVoice: String?, model: String?, stylePrompt: String?): MimoTtsRequest {
        val voice = selectedVoice?.trim()?.ifEmpty { null } ?: "mimo_default"
        val resolvedModel = model?.trim()?.ifEmpty { null } ?: DEFAULT_TTS_MODEL
        val messages = mutableListOf<MimoVoiceMessage>()
        val style = stylePrompt?.trim()
        if (!style.isNullOrEmpty()) {
            messages += MimoVoiceMessage(role = "user", content = style)
        }
        messages += MimoVoiceMessage(role = "assistant", content = text)
        return MimoTtsRequest(
            model = resolvedModel,
            messages = messages,
            format = MimoAudioFormat.PCM16,
            voice = VoiceTtsPresets.normalizeVoiceTtsPreset(voice),
            stream = true,
        )
    }

    // ----------------------------------------------------------------------
    // Network path — NOT CONFIGURED.
    // ----------------------------------------------------------------------

    /** Result of a synthesis attempt. Only a configured provider can yield bytes. */
    sealed class MimoVoiceResult {
        object NotConfigured : MimoVoiceResult()
    }

    /**
     * Attempts cloud speech synthesis. Always returns [MimoVoiceResult.NotConfigured]
     * in this phase because cloud voice is genuinely unconfigured — no fake
     * audio is ever produced. [baseUrl]/[apiKey] are accepted so the signature
     * matches the source contract but the call never performs I/O.
     */
    @Suppress("UNUSED_PARAMETER")
    fun synthesizeSpeech(
        text: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        voice: String,
        stylePrompt: String?,
    ): MimoVoiceResult {
        if (text.trim().isEmpty()) return MimoVoiceResult.NotConfigured
        // Cloud MiMO voice is not configured on Android (no voice-TTS key yet).
        return MimoVoiceResult.NotConfigured
    }

    /**
     * Cloud streaming synthesis is exercised only through this explicit seam.
     * There is no chunk callback API used here: the interface documents that
     * streaming voice is NOT configured, and callers must treat it as such.
     */
    fun streamingConfigured(): Boolean = false
}

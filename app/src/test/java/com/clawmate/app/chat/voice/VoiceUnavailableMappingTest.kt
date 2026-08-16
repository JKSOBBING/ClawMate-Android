package com.clawmate.app.chat.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unavailable / permission / error mapping tests over the pure voice types.
 *
 * The Android `SpeechRecognizer`/`TextToSpeech` adapters surface capabilities
 * through typed states ([AsrStartResult] permission/unavailable/failed and
 * Android-`TextToSpeech` init errors) that map onto these pure statuses before
 * the UI renders them. This test locks the pure mapping contract that both
 * adapters must satisfy, so a device is not required.
 */
class VoiceUnavailableMappingTest {

    @Test
    fun `error metadata maps to error status with provider and message`() {
        val meta = createVoiceErrorMetadata(
            input = ChatVoiceInputKind.ASR,
            provider = "android",
            error = "缺少录音权限",
            createdAt = 7L,
        )
        assertEquals(ChatVoiceMetadataStatus.ERROR, meta.status)
        assertEquals(ChatVoiceInputKind.ASR, meta.input)
        assertEquals("android", meta.provider)
        assertEquals("缺少录音权限", meta.error)
    }

    @Test
    fun `error present flag true and not empty`() {
        val present = chatVoiceMetadataPresent(
            ChatVoiceMetadata(status = ChatVoiceMetadataStatus.ERROR, error = "busy"),
        )
        assertTrue(present)
    }

    @Test
    fun `error status yields the source error label`() {
        assertEquals("语音出错", voiceStatusLabel(VoiceInteractionStatus.ERROR))
    }

    @Test
    fun `tts runtime metadata maps synthesizing and ready statuses`() {
        val synth = createTtsVoiceRuntimeMetadata(
            "android", "冰糖", "", TtsVoiceRuntimeStatus.SYNTHESIZING, 1L,
        )
        assertEquals(ChatVoiceMetadataStatus.SYNTHESIZING, synth.status)
        val ready = createTtsVoiceRuntimeMetadata(
            "android", "冰糖", "a.wav", TtsVoiceRuntimeStatus.READY, 1L,
        )
        assertEquals(ChatVoiceMetadataStatus.READY, ready.status)
        // Empty audioUri -> synthesizing (not ready) per the source.
        val empty = createTtsVoiceMetadata("android", "冰糖", "", 1L)
        assertEquals(ChatVoiceMetadataStatus.SYNTHESIZING, empty.status)
        // Non-empty audioUri -> ready.
        val withAudio = createTtsVoiceMetadata("android", "冰糖", "a.wav", 1L)
        assertEquals(ChatVoiceMetadataStatus.READY, withAudio.status)
    }

    @Test
    fun `tts error mapping keeps input kind and error status`() {
        val meta = createVoiceErrorMetadata(ChatVoiceInputKind.TTS, "android", "语音引擎错误", 1L)
        assertEquals(ChatVoiceInputKind.TTS, meta.input)
        assertEquals(ChatVoiceMetadataStatus.ERROR, meta.status)
    }

    @Test
    fun `kind default is none for unset input`() {
        val meta = createVoiceErrorMetadata(ChatVoiceInputKind.NONE, "", "x", 1L)
        assertEquals(ChatVoiceInputKind.NONE, meta.input)
        assertEquals(ChatVoiceMetadataStatus.ERROR, meta.status)
    }
}

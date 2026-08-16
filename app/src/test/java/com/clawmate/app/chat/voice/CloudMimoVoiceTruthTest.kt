package com.clawmate.app.chat.voice

import com.clawmate.app.chat.voice.tts.MimoVoiceClient
import com.clawmate.app.chat.voice.tts.MimoVoiceClient.MimoVoiceResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truthfulness tests for the cloud voice seam: MiMO cloud streaming voice is
 * NOT configured on Android in this phase and must never claim otherwise or
 * fabricate audio.
 */
class CloudMimoVoiceTruthTest {

    @Test
    fun `cloud synthesis always reports NotConfigured and never returns audio`() {
        val result = MimoVoiceClient.synthesizeSpeech(
            text = "你好",
            baseUrl = "https://example.invalid",
            apiKey = "not-a-real-key",
            model = "mimo-v2.5-tts",
            voice = "冰糖",
            stylePrompt = null,
        )
        assertTrue(result is MimoVoiceResult.NotConfigured)
    }

    @Test
    fun `cloud streaming is explicitly not configured`() {
        assertFalse(MimoVoiceClient.streamingConfigured())
    }

    @Test
    fun `empty text also returns NotConfigured`() {
        val result = MimoVoiceClient.synthesizeSpeech(" ", "u", "k", "m", "v", null)
        assertTrue(result is MimoVoiceResult.NotConfigured)
    }

    @Test
    fun `streaming tts request builds correct wire shape`() {
        val req = MimoVoiceClient.buildStreamingTtsRequest(
            text = "帮我查天气",
            selectedVoice = "冰糖",
            model = "mimo-v2.5-tts",
            stylePrompt = "用温和的语气",
        )
        assertEquals("mimo-v2.5-tts", req.model)
        assertEquals("冰糖", req.voice)
        assertEquals(MimoVoiceClient.MimoAudioFormat.PCM16, req.format)
        assertTrue(req.stream)
        // Target text lives in the assistant message; style in the user message.
        assertEquals(2, req.messages.size)
        assertEquals("assistant", req.messages.last().role)
        assertEquals("帮我查天气", req.messages.last().content)
        assertEquals("user", req.messages.first().role)
    }
}

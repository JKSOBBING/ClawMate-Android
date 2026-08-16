package com.clawmate.app.chat.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInteractionTypesTest {

    // ------------------------------------------------------------------ append
    @Test
    fun `append transcript to empty input returns trimmed transcript`() {
        assertEquals("你好", appendVoiceTranscriptToInput("", "  你好  "))
    }

    @Test
    fun `append empty transcript leaves input unchanged`() {
        assertEquals("existing", appendVoiceTranscriptToInput("existing", "   "))
    }

    @Test
    fun `append to non-empty input joins with newline`() {
        assertEquals("早上好\n帮我查天气", appendVoiceTranscriptToInput("早上好", "帮我查天气"))
    }

    @Test
    fun `append to input already ending with space joins without extra separator`() {
        assertEquals("早上好 帮我", appendVoiceTranscriptToInput("早上好 ", "帮我"))
    }

    // ------------------------------------------------------------- normalize
    @Test
    fun `normalize keeps valid voice metadata fields`() {
        val raw = ChatVoiceMetadata(
            input = ChatVoiceInputKind.ASR,
            provider = "android",
            status = ChatVoiceMetadataStatus.TRANSCRIBED,
            transcript = " 你好 ",
            confidence = 0.5,
            audioUri = "file:///a.wav",
            voice = "冰糖",
            createdAt = 123,
        )
        val normalized = normalizeChatVoiceMetadata(raw)
        assertEquals(ChatVoiceInputKind.ASR, normalized.input)
        assertEquals("android", normalized.provider)
        assertEquals(ChatVoiceMetadataStatus.TRANSCRIBED, normalized.status)
        assertEquals("你好", normalized.transcript)
        assertEquals(0.5, normalized.confidence, 0.0001)
    }

    @Test
    fun `normalize clamps confidence into valid range`() {
        val negative = normalizeChatVoiceMetadata(ChatVoiceMetadata(confidence = -0.5)).confidence
        val over = normalizeChatVoiceMetadata(ChatVoiceMetadata(confidence = 2.0)).confidence
        assertEquals(0.0, negative, 0.0001)
        assertEquals(1.0, over, 0.0001)
    }

    @Test
    fun `normalize maps unknown kind and status to none`() {
        val normalized = normalizeChatVoiceMetadata(ChatVoiceMetadata())
        assertEquals(ChatVoiceInputKind.NONE, normalized.input)
        assertEquals(ChatVoiceMetadataStatus.NONE, normalized.status)
        assertEquals("", normalized.provider)
    }

    @Test
    fun `optional metadata returns null when empty and value when present`() {
        assertNull(optionalChatVoiceMetadata(ChatVoiceMetadata()))
        val present = optionalChatVoiceMetadata(ChatVoiceMetadata(transcript = "hi"))
        assertTrue(present != null)
        assertEquals("hi", present!!.transcript)
    }

    // -------------------------------------------------------------- factories
    @Test
    fun `createAsr covers truthfully only when a real transcript is supplied`() {
        val meta = createAsrVoiceMetadata("android", "你好世界", 0.9, createdAt = 1L)
        assertEquals(ChatVoiceInputKind.ASR, meta.input)
        assertEquals("android", meta.provider)
        assertEquals(ChatVoiceMetadataStatus.TRANSCRIBED, meta.status)
        assertEquals("你好世界", meta.transcript)
        assertEquals("", meta.audioUri)
    }

    // ------------------------------------------------------------ status label
    @Test
    fun `status labels match the source contract`() {
        assertEquals("正在聆听...", voiceStatusLabel(VoiceInteractionStatus.LISTENING))
        assertEquals("正在转写...", voiceStatusLabel(VoiceInteractionStatus.TRANSCRIBING))
        assertEquals("正在合成语音...", voiceStatusLabel(VoiceInteractionStatus.SYNTHESIZING))
        assertEquals("正在朗读...", voiceStatusLabel(VoiceInteractionStatus.SPEAKING))
        assertEquals("已暂停朗读", voiceStatusLabel(VoiceInteractionStatus.PAUSED))
        assertEquals("语音出错", voiceStatusLabel(VoiceInteractionStatus.ERROR))
        assertEquals("", voiceStatusLabel(VoiceInteractionStatus.IDLE))
    }

    // ------------------------------------------------------------ replay guards
    @Test
    fun `replay unavailable on empty audio uri`() {
        assertFalse(voiceReplayAvailable("", VoiceInteractionStatus.IDLE, currentMessageSynthesizing = false))
    }

    @Test
    fun `replay blocked while streaming TTS synthesizes globally`() {
        assertFalse(voiceReplayAvailable("a.wav", VoiceInteractionStatus.SYNTHESIZING, currentMessageSynthesizing = false))
        assertFalse(voiceReplayAvailable("a.wav", VoiceInteractionStatus.IDLE, currentMessageSynthesizing = false, globalReplayBlocked = true))
    }

    @Test
    fun `replay blocked when this message is synthesizing`() {
        assertFalse(voiceReplayAvailable("a.wav", VoiceInteractionStatus.IDLE, currentMessageSynthesizing = true))
    }

    @Test
    fun `replay available with ready audio regardless of speaking status`() {
        assertTrue(voiceReplayAvailable("a.wav", VoiceInteractionStatus.SPEAKING, currentMessageSynthesizing = false))
    }

    @Test
    fun `global block needed while synthesizing or active message not cached`() {
        assertTrue(voiceReplayGlobalBlockNeeded(
            VoiceInteractionStatus.SYNTHESIZING, "m1", activeMessageVisible = true,
            activeVoiceStatus = ChatVoiceMetadataStatus.PLAYING, activeAudioUri = "a.wav",
        ))
        // Streaming speaks before cache audioUri is persisted -> blocked.
        assertTrue(voiceReplayGlobalBlockNeeded(
            VoiceInteractionStatus.SPEAKING, "m1", activeMessageVisible = true,
            activeVoiceStatus = ChatVoiceMetadataStatus.PLAYING, activeAudioUri = "",
        ))
        // No active message -> no global block.
        assertFalse(voiceReplayGlobalBlockNeeded(
            VoiceInteractionStatus.SPEAKING, "", activeMessageVisible = true,
            activeVoiceStatus = ChatVoiceMetadataStatus.NONE, activeAudioUri = "",
        ))
    }
}

package com.clawmate.app.chat.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionControllerTest {

    @Test
    fun `idle snapshot and not busy`() {
        val c = VoiceSessionController()
        val snap = c.snapshot()
        assertEquals(VoiceInteractionStatus.IDLE, snap.status)
        assertEquals("", snap.turnId)
        assertFalse(c.isBusy())
    }

    @Test
    fun `begin listening sets status and turnId and is busy`() {
        val c = VoiceSessionController()
        val snap = c.beginListening("voice-1")
        assertEquals(VoiceInteractionStatus.LISTENING, snap.status)
        assertEquals("voice-1", snap.turnId)
        assertTrue(c.isBusy())
    }

    @Test
    fun `partial transcript only updates while listening`() {
        val c = VoiceSessionController()
        c.beginListening("t1")
        c.updatePartialTranscript("你好")
        c.completeTranscript("你好世界")
        assertEquals(VoiceInteractionStatus.TRANSCRIBING, c.snapshot().status)
        assertEquals("你好世界", c.snapshot().finalTranscript)
        // after reset updates are ignored
        c.reset()
        c.updatePartialTranscript("ignored")
        assertEquals("", c.snapshot().partialTranscript)
    }

    @Test
    fun `completeTranscript trims and mirrors into partial`() {
        val c = VoiceSessionController()
        c.beginListening("t1")
        c.completeTranscript("  你好  ")
        assertEquals("你好", c.snapshot().finalTranscript)
        assertEquals("你好", c.snapshot().partialTranscript)
    }

    @Test
    fun `completeWithoutTranscript returns to idle empty snapshot`() {
        val c = VoiceSessionController()
        c.beginListening("t1")
        c.completeWithoutTranscript()
        assertEquals(VoiceInteractionStatus.IDLE, c.snapshot().status)
        assertEquals("", c.snapshot().turnId)
    }

    @Test
    fun `save to idle flow clears final transcript`() {
        val c = VoiceSessionController()
        c.beginListening("t1")
        c.completeTranscript("帮我查一下天气")
        c.reset()
        assertEquals(VoiceInteractionStatus.IDLE, c.snapshot().status)
        assertEquals("", c.snapshot().finalTranscript)
    }

    @Test
    fun `speaking pause resume lifecycle`() {
        val c = VoiceSessionController()
        c.beginSpeaking("m1")
        assertEquals(VoiceInteractionStatus.SPEAKING, c.snapshot().status)
        c.pauseSpeaking("m1")
        assertEquals(VoiceInteractionStatus.PAUSED, c.snapshot().status)
        assertEquals("m1", c.snapshot().activeMessageId)
        c.finishSpeaking()
        assertEquals(VoiceInteractionStatus.IDLE, c.snapshot().status)
        assertEquals("", c.snapshot().activeMessageId)
    }

    @Test
    fun `fail sets error status and message`() {
        val c = VoiceSessionController()
        c.beginListening("t1")
        c.fail("语音识别失败")
        assertEquals(VoiceInteractionStatus.ERROR, c.snapshot().status)
        assertEquals("语音识别失败", c.snapshot().error)
    }

    @Test
    fun `beginSynthesizing resets error and tracks message`() {
        val c = VoiceSessionController()
        c.beginSynthesizing("m1")
        assertEquals(VoiceInteractionStatus.SYNTHESIZING, c.snapshot().status)
        assertEquals("m1", c.snapshot().activeMessageId)
    }
}

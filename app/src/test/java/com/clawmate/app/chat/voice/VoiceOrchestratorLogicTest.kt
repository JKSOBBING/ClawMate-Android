package com.clawmate.app.chat.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceOrchestratorLogicTest {

    @Test
    fun `start generation increments monotonically`() {
        assertEquals(1, VoiceOrchestratorLogic.nextVoiceInputStartGeneration(0))
        assertEquals(2, VoiceOrchestratorLogic.nextVoiceInputStartGeneration(1))
    }

    @Test
    fun `stop generation is the max of latest start and prior stop`() {
        assertEquals(3, VoiceOrchestratorLogic.voiceInputStopGeneration(3, 1))
        assertEquals(4, VoiceOrchestratorLogic.voiceInputStopGeneration(2, 4))
    }

    @Test
    fun `start can proceed when generation is current and not stopped`() {
        assertTrue(VoiceOrchestratorLogic.voiceInputStartCanProceed(3, 3, stoppedGeneration = 2))
    }

    @Test
    fun `start cannot proceed when it is stale or already stopped`() {
        // stale (start 2 != latest 3)
        assertFalse(VoiceOrchestratorLogic.voiceInputStartCanProceed(2, 3, stoppedGeneration = 0))
        // stopped after this start
        assertFalse(VoiceOrchestratorLogic.voiceInputStartCanProceed(3, 3, stoppedGeneration = 3))
        assertFalse(VoiceOrchestratorLogic.voiceInputStartCanProceed(3, 3, stoppedGeneration = 4))
    }

    @Test
    fun `stop fallback constant is bounded`() {
        assertTrue(VoiceOrchestratorLogic.VOICE_INPUT_STOP_FALLBACK_MS > 0)
    }
}

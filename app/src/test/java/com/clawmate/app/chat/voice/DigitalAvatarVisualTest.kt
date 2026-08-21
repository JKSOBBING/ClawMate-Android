package com.clawmate.app.chat.voice

import com.clawmate.app.chat.model.ChatMessage
import com.clawmate.app.chat.model.ChatMessageKind
import com.clawmate.app.chat.model.ChatMessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure digital-avatar visual contract
 * ([com.clawmate.app.chat.voice.DigitalAvatarVisual.kt]).
 *
 * These pin the exact HarmonyOS voice-state → visual mapping, the per-message
 * render model, the derived pulse/wave/ring formulas and the dp metrics, so the
 * Compose renderer stays a thin, faithful projection of the reference.
 */
class DigitalAvatarVisualTest {

    private fun message(
        role: ChatMessageRole = ChatMessageRole.ASSISTANT,
        kind: ChatMessageKind = ChatMessageKind.CHAT,
        id: String = "m1",
    ) = ChatMessage(
        id = id,
        role = role,
        kind = kind,
        content = "hi",
        trace = emptyList(),
        collapsed = false,
        createdAt = 0L,
    )

    // ------------------------------------------------------- fromVoiceStatus
    @Test
    fun `fromVoiceStatus maps the full voice status to visual states`() {
        assertEquals(DigitalAvatarVisualState.IDLE, DigitalAvatarVisualState.fromVoiceStatus(VoiceInteractionStatus.IDLE))
        assertEquals(DigitalAvatarVisualState.LISTENING, DigitalAvatarVisualState.fromVoiceStatus(VoiceInteractionStatus.LISTENING))
        assertEquals(DigitalAvatarVisualState.LISTENING, DigitalAvatarVisualState.fromVoiceStatus(VoiceInteractionStatus.TRANSCRIBING))
        assertEquals(DigitalAvatarVisualState.THINKING, DigitalAvatarVisualState.fromVoiceStatus(VoiceInteractionStatus.THINKING))
        assertEquals(DigitalAvatarVisualState.THINKING, DigitalAvatarVisualState.fromVoiceStatus(VoiceInteractionStatus.SYNTHESIZING))
        assertEquals(DigitalAvatarVisualState.SPEAKING, DigitalAvatarVisualState.fromVoiceStatus(VoiceInteractionStatus.SPEAKING))
        assertEquals(DigitalAvatarVisualState.INTERRUPTED, DigitalAvatarVisualState.fromVoiceStatus(VoiceInteractionStatus.PAUSED))
        assertEquals(DigitalAvatarVisualState.INTERRUPTED, DigitalAvatarVisualState.fromVoiceStatus(VoiceInteractionStatus.ERROR))
    }

    @Test
    fun `isAnimated is true only for thinking listening speaking`() {
        assertTrue(DigitalAvatarVisualState.THINKING.isAnimated)
        assertTrue(DigitalAvatarVisualState.LISTENING.isAnimated)
        assertTrue(DigitalAvatarVisualState.SPEAKING.isAnimated)
        assertFalse(DigitalAvatarVisualState.IDLE.isAnimated)
        assertFalse(DigitalAvatarVisualState.INTERRUPTED.isAnimated)
    }

    // ------------------------------------------------- visualStateForMessage
    @Test
    fun `visualStateForMessage is idle when no message is active`() {
        assertEquals(
            DigitalAvatarVisualState.IDLE,
            visualStateForMessage(VoiceInteractionStatus.SPEAKING, "", "m1"),
        )
    }

    @Test
    fun `visualStateForMessage is idle for a non-active message`() {
        assertEquals(
            DigitalAvatarVisualState.IDLE,
            visualStateForMessage(VoiceInteractionStatus.SPEAKING, "other", "m1"),
        )
    }

    @Test
    fun `visualStateForMessage mirrors the voice status for the active message`() {
        assertEquals(
            DigitalAvatarVisualState.SPEAKING,
            visualStateForMessage(VoiceInteractionStatus.SPEAKING, "m1", "m1"),
        )
        assertEquals(
            DigitalAvatarVisualState.IDLE,
            visualStateForMessage(VoiceInteractionStatus.IDLE, "m1", "m1"),
        )
    }

    // ----------------------------------------------------------- ring values
    @Test
    fun `ringScale is static for idle and interrupted`() {
        assertEquals(0.92f, ringScale(DigitalAvatarVisualState.IDLE, pulse = true), 1e-4f)
        assertEquals(1.04f, ringScale(DigitalAvatarVisualState.INTERRUPTED, pulse = true), 1e-4f)
    }

    @Test
    fun `ringScale pulses per state`() {
        assertEquals(1.26f, ringScale(DigitalAvatarVisualState.SPEAKING, true), 1e-4f)
        assertEquals(0.94f, ringScale(DigitalAvatarVisualState.SPEAKING, false), 1e-4f)
        assertEquals(1.18f, ringScale(DigitalAvatarVisualState.LISTENING, true), 1e-4f)
        assertEquals(0.92f, ringScale(DigitalAvatarVisualState.LISTENING, false), 1e-4f)
        assertEquals(1.18f, ringScale(DigitalAvatarVisualState.THINKING, true), 1e-4f)
        assertEquals(0.92f, ringScale(DigitalAvatarVisualState.THINKING, false), 1e-4f)
    }

    @Test
    fun `ringOpacity follows the HarmonyOS table`() {
        assertEquals(0.22f, ringOpacity(DigitalAvatarVisualState.IDLE, true), 1e-4f)
        assertEquals(0.42f, ringOpacity(DigitalAvatarVisualState.INTERRUPTED, true), 1e-4f)
        assertEquals(0.66f, ringOpacity(DigitalAvatarVisualState.SPEAKING, true), 1e-4f)
        assertEquals(0.24f, ringOpacity(DigitalAvatarVisualState.SPEAKING, false), 1e-4f)
        assertEquals(0.48f, ringOpacity(DigitalAvatarVisualState.LISTENING, true), 1e-4f)
        assertEquals(0.58f, ringOpacity(DigitalAvatarVisualState.THINKING, true), 1e-4f)
    }

    // ------------------------------------------------------- wave bar values
    @Test
    fun `waveBarScaleY grows the middle bar tall while speaking and pulsing`() {
        // HERO: large=28, small=12
        assertEquals(1.0f, waveBarScaleY(DigitalAvatarSize.HERO, DigitalAvatarVisualState.SPEAKING, true, index = 1), 1e-4f)
        assertEquals(12f / 28f, waveBarScaleY(DigitalAvatarSize.HERO, DigitalAvatarVisualState.SPEAKING, false, index = 1), 1e-4f)
        assertEquals(12f / 28f, waveBarScaleY(DigitalAvatarSize.HERO, DigitalAvatarVisualState.SPEAKING, true, index = 0), 1e-4f)
        assertEquals(1.0f, waveBarScaleY(DigitalAvatarSize.HERO, DigitalAvatarVisualState.SPEAKING, false, index = 0), 1e-4f)
    }

    @Test
    fun `waveBarScaleY uses the smaller message metrics`() {
        // MESSAGE: large=15, small=7
        assertEquals(1.0f, waveBarScaleY(DigitalAvatarSize.MESSAGE, DigitalAvatarVisualState.SPEAKING, true, index = 1), 1e-4f)
        assertEquals(7f / 15f, waveBarScaleY(DigitalAvatarSize.MESSAGE, DigitalAvatarVisualState.SPEAKING, true, index = 0), 1e-4f)
    }

    @Test
    fun `waveBarScaleY is the small height while interrupted`() {
        assertEquals(12f / 28f, waveBarScaleY(DigitalAvatarSize.HERO, DigitalAvatarVisualState.INTERRUPTED, true, index = 0), 1e-4f)
    }

    @Test
    fun `waveBarScaleY clamps to a tiny floor when no bars show`() {
        assertEquals(0.01f, waveBarScaleY(DigitalAvatarSize.HERO, DigitalAvatarVisualState.IDLE, true, index = 0), 1e-4f)
        assertEquals(0.01f, waveBarScaleY(DigitalAvatarSize.HERO, DigitalAvatarVisualState.THINKING, true, index = 1), 1e-4f)
    }

    @Test
    fun `waveOpacity only shows bars while speaking or interrupted`() {
        assertEquals(1.0f, waveOpacity(DigitalAvatarVisualState.SPEAKING, true), 1e-4f)
        assertEquals(0.72f, waveOpacity(DigitalAvatarVisualState.SPEAKING, false), 1e-4f)
        assertEquals(0.5f, waveOpacity(DigitalAvatarVisualState.INTERRUPTED, false), 1e-4f)
        assertEquals(0.0f, waveOpacity(DigitalAvatarVisualState.IDLE, true), 1e-4f)
        assertEquals(0.0f, waveOpacity(DigitalAvatarVisualState.LISTENING, true), 1e-4f)
        assertEquals(0.0f, waveOpacity(DigitalAvatarVisualState.THINKING, true), 1e-4f)
    }

    // --------------------------------------------------- foreground / shadow
    @Test
    fun `foregroundScale multiplies tap scale by the animated pulse`() {
        assertEquals(1.0f, foregroundScale(1f, DigitalAvatarVisualState.IDLE, pulse = true), 1e-4f)
        assertEquals(1.07f, foregroundScale(1f, DigitalAvatarVisualState.SPEAKING, pulse = true), 1e-4f)
        assertEquals(1.03f, foregroundScale(1f, DigitalAvatarVisualState.LISTENING, pulse = true), 1e-4f)
        assertEquals(1.16f, foregroundScale(1.16f, DigitalAvatarVisualState.SPEAKING, pulse = false), 1e-4f)
        assertEquals(1.16f * 1.07f, foregroundScale(1.16f, DigitalAvatarVisualState.SPEAKING, pulse = true), 1e-4f)
    }

    @Test
    fun `shadowScale dips while animated and pulsing`() {
        assertEquals(1.0f, shadowScale(1f, DigitalAvatarVisualState.IDLE, pulse = true), 1e-4f)
        assertEquals(0.92f, shadowScale(1f, DigitalAvatarVisualState.SPEAKING, pulse = true), 1e-4f)
        assertEquals(1.0f, shadowScale(1f, DigitalAvatarVisualState.SPEAKING, pulse = false), 1e-4f)
    }

    // --------------------------------------------------- chatAvatarRenderModel
    @Test
    fun `chatAvatarRenderModel renders user as a static 我 letter`() {
        val m = chatAvatarRenderModel(message(role = ChatMessageRole.USER), modeMark = "", visualState = DigitalAvatarVisualState.IDLE)
        assertFalse(m.useDigitalAvatar)
        assertEquals("我", m.fallbackText)
        assertEquals("#111827", m.fallbackColorHex)
    }

    @Test
    fun `chatAvatarRenderModel renders assistant chat as the animated 助 avatar`() {
        val m = chatAvatarRenderModel(message(), modeMark = "", visualState = DigitalAvatarVisualState.SPEAKING)
        assertTrue(m.useDigitalAvatar)
        assertEquals("助", m.fallbackText)
        assertEquals("#5968FF", m.fallbackColorHex)
        assertEquals(DigitalAvatarVisualState.SPEAKING, m.visualState)
    }

    @Test
    fun `chatAvatarRenderModel honours a non-empty mode mark`() {
        val m = chatAvatarRenderModel(message(), modeMark = "云", visualState = DigitalAvatarVisualState.IDLE)
        assertTrue(m.useDigitalAvatar)
        assertEquals("云", m.fallbackText)
    }

    @Test
    fun `chatAvatarRenderModel renders task and notice kinds as static letters`() {
        val result = chatAvatarRenderModel(message(kind = ChatMessageKind.TASK_RESULT), "", DigitalAvatarVisualState.IDLE)
        assertFalse(result.useDigitalAvatar)
        assertEquals("任", result.fallbackText)
        assertEquals("#059669", result.fallbackColorHex)

        val trace = chatAvatarRenderModel(message(kind = ChatMessageKind.TASK_TRACE), "", DigitalAvatarVisualState.IDLE)
        assertEquals("任", trace.fallbackText)
        assertEquals("#7C3AED", trace.fallbackColorHex)

        val notice = chatAvatarRenderModel(message(kind = ChatMessageKind.CONTEXT_NOTICE), "", DigitalAvatarVisualState.IDLE)
        assertFalse(notice.useDigitalAvatar)
        assertEquals("压", notice.fallbackText)
        assertEquals("#F59E0B", notice.fallbackColorHex)
    }

    // ------------------------------------------------------------- geometry
    @Test
    fun `geometry metrics match the HarmonyOS constants`() {
        // HERO
        assertEquals(120, frameWidth(DigitalAvatarSize.HERO))
        assertEquals(150, frameHeight(DigitalAvatarSize.HERO))
        assertEquals(56, fallbackSize(DigitalAvatarSize.HERO))
        assertEquals(150, containerWidth(DigitalAvatarSize.HERO, true))
        assertEquals(196, containerHeight(DigitalAvatarSize.HERO, true))
        assertEquals(104, ringSize(DigitalAvatarSize.HERO))
        assertEquals(28, waveBarMaxHeight(DigitalAvatarSize.HERO))
        assertEquals(12, waveBarSmallHeight(DigitalAvatarSize.HERO))
        // MESSAGE
        assertEquals(42, frameWidth(DigitalAvatarSize.MESSAGE))
        assertEquals(54, frameHeight(DigitalAvatarSize.MESSAGE))
        assertEquals(34, fallbackSize(DigitalAvatarSize.MESSAGE))
        assertEquals(56, containerWidth(DigitalAvatarSize.MESSAGE, true))
        assertEquals(72, containerHeight(DigitalAvatarSize.MESSAGE, true))
        assertEquals(40, ringSize(DigitalAvatarSize.MESSAGE))
        assertEquals(15, waveBarMaxHeight(DigitalAvatarSize.MESSAGE))
        assertEquals(7, waveBarSmallHeight(DigitalAvatarSize.MESSAGE))
    }

    @Test
    fun `container shrinks to the fallback size when there is no frame`() {
        assertEquals(fallbackSize(DigitalAvatarSize.HERO), containerWidth(DigitalAvatarSize.HERO, false))
        assertEquals(fallbackSize(DigitalAvatarSize.MESSAGE), containerHeight(DigitalAvatarSize.MESSAGE, false))
    }
}

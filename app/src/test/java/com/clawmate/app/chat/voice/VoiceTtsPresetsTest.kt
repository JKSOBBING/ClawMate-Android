package com.clawmate.app.chat.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTtsPresetsTest {

    @Test
    fun `whitelist has the five mimo voices`() {
        assertEquals(
            listOf("mimo_default", "冰糖", "茉莉", "苏打", "白桦"),
            VoiceTtsPresets.MIMO_CHINESE_VOICE_PRESETS,
        )
    }

    @Test
    fun `supported voice is recognized`() {
        assertTrue(VoiceTtsPresets.voiceTtsVoicePresetSupported(" 冰糖 "))
        assertFalse(VoiceTtsPresets.voiceTtsVoicePresetSupported("金嗓子"))
    }

    @Test
    fun `normalize trims and falls back to default`() {
        assertEquals("冰糖", VoiceTtsPresets.normalizeVoiceTtsPreset(" 冰糖 "))
        assertEquals("mimo_default", VoiceTtsPresets.normalizeVoiceTtsPreset(" 未知 "))
        assertEquals("mimo_default", VoiceTtsPresets.normalizeVoiceTtsPreset(""))
    }

    @Test
    fun `role presets resolve by id with fallback`() {
        val home = VoiceTtsPresets.voiceTtsRolePresetById("data_home")
        assertEquals("ClawMate", home.label)
        assertEquals("冰糖", home.voice)
        val unknown = VoiceTtsPresets.voiceTtsRolePresetById("nope")
        assertEquals(VoiceTtsPresets.VOICE_TTS_ROLE_PRESETS[0], unknown)
    }

    @Test
    fun `selection from role preset keeps snapshot`() {
        val selection = VoiceTtsPresets.voiceTtsSelectionFromRolePreset(
            VoiceTtsPresets.voiceTtsRolePresetById("task_butler"),
        )
        assertEquals("task_butler", selection.rolePreset)
        assertEquals("白桦", selection.voice)
        assertTrue(selection.stylePrompt.isNotBlank())
    }
}

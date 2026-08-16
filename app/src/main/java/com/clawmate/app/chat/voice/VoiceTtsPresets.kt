package com.clawmate.app.chat.voice

/**
 * MiMO V2.5 TTS voice presets. Port of HarmonyOS
 * `entry/src/main/ets/utils/voice/VoiceTtsPresets.ets`.
 *
 * These names are the on-device/cloud provider's whitelist. Android's local
 * `TextToSpeech` does not honor these arbitrary names, so this file is used to
 * keep the mapping deterministically stable and to reject/fall-back malformed
 * inputs — never to *claim* a particular voice engine is configured.
 */
object VoiceTtsPresets {

    /** Chinese voice presets exposed by the MiMO TTS provider. */
    val MIMO_CHINESE_VOICE_PRESETS: List<String> =
        listOf("mimo_default", "冰糖", "茉莉", "苏打", "白桦")

    val VOICE_TTS_ROLE_PRESETS: List<VoiceTtsRolePreset> = listOf(
        VoiceTtsRolePreset(
            id = "data_home",
            label = "ClawMate",
            voice = "冰糖",
            stylePrompt = "用温和清晰的中文语气朗读，适合整理回顾信息。",
        ),
        VoiceTtsRolePreset(
            id = "task_butler",
            label = "任务管家",
            voice = "白桦",
            stylePrompt = "用干练明确的中文语气朗读，适合任务执行和步骤确认。",
        ),
    )

    /** True when [voice] is one of the MiMO whitelisted presets. */
    fun voiceTtsVoicePresetSupported(voice: String): Boolean =
        voice.trim() in MIMO_CHINESE_VOICE_PRESETS

    /** Normalizes an arbitrary voice string to a whitelisted preset (fallback `mimo_default`). */
    fun normalizeVoiceTtsPreset(voice: String): String {
        val normalized = voice.trim()
        if (normalized in MIMO_CHINESE_VOICE_PRESETS) return normalized
        return "mimo_default"
    }

    fun voiceTtsRolePresetById(id: String): VoiceTtsRolePreset =
        VOICE_TTS_ROLE_PRESETS.firstOrNull { it.id == id } ?: VOICE_TTS_ROLE_PRESETS[0]

    fun voiceTtsSelectionFromRolePreset(preset: VoiceTtsRolePreset): VoiceTtsRoleSelection =
        VoiceTtsRoleSelection(
            rolePreset = preset.id,
            voice = preset.voice,
            stylePrompt = preset.stylePrompt,
        )
}

data class VoiceTtsRolePreset(
    val id: String,
    val label: String,
    val voice: String,
    val stylePrompt: String,
)

data class VoiceTtsRoleSelection(
    val rolePreset: String,
    val voice: String,
    val stylePrompt: String,
)

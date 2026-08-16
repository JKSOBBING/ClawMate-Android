package com.clawmate.app.chat.voice

/**
 * Pure voice-input start/stop generation guards. Port of
 * `ChatVoiceOrchestrator.ets` (`nextVoiceInputStartGeneration`,
 * `voiceInputStopGeneration`, `voiceInputStartCanProceed`).
 *
 * These deterministic guards bound the interplay between rapid toggles:
 * a start that raced ahead of a stop is ignored, and a stop that happened
 * *before* the latest start does not block it. Unit-testable without a device.
 */
object VoiceOrchestratorLogic {

    const val VOICE_INPUT_STOP_FALLBACK_MS: Long = 1500L

    fun nextVoiceInputStartGeneration(currentGeneration: Int): Int = currentGeneration + 1

    fun voiceInputStopGeneration(latestStartGeneration: Int, previousStoppedGeneration: Int): Int =
        maxOf(latestStartGeneration, previousStoppedGeneration)

    fun voiceInputStartCanProceed(startGeneration: Int, latestStartGeneration: Int, stoppedGeneration: Int): Boolean =
        startGeneration == latestStartGeneration && startGeneration > stoppedGeneration
}

package com.clawmate.app.gallery.insights

/**
 * Honest boundary for the cloud portions of Gallery monthly insights.
 *
 * The HarmonyOS source generates an optional AI monthly summary through a
 * configured cloud LLM (`summarizeGalleryMonth` on the gallery cloud client) and
 * stores embeddings via a configured embedding service.
 *
 * Phase 21 wires a **config-driven** adapter ([com.clawmate.app.gallery.cloud
 * .GalleryCloudAdapter]) behind explicit settings and the existing
 * OpenAI-compatible chat client / settings. The state machine below is honest:
 * NOT_CONFIGURED when a feature is off or missing endpoint/model/key,
 * UNSUPPORTED when a selected mode has no Android backend, READY when fully
 * configured, and ERROR when a call was made and failed. The deterministic
 * local insight ([GalleryMonthlyInsights]) is independent of this gate and
 * always reflects only stored analysis data.
 */
sealed class GalleryMonthlyInsightCloudState {
    /** Cloud LLM monthly AI summary is available (once configured). */
    data object NotConfigured : GalleryMonthlyInsightCloudState()

    /** The feature is selected but has no usable backend on this platform. */
    data object Unsupported : GalleryMonthlyInsightCloudState()

    /** A real cloud result is available (config present / call succeeded). */
    data class Ready(val description: String) : GalleryMonthlyInsightCloudState()

    /** An attempted cloud call failed; [message] is already secret-redacted. */
    data class Error(val message: String) : GalleryMonthlyInsightCloudState()
}

/**
 * The safe, empty default cloud-insights state used until a real cloud config /
 * adapter is wired. Kept as an explicit value so UI can render the truthful
 * "未配置" notice instead of pretending an AI summary exists.
 */
object UnconfiguredMonthlyInsightCloud {
    val aiSummary: GalleryMonthlyInsightCloudState = GalleryMonthlyInsightCloudState.NotConfigured
    val embedding: GalleryMonthlyInsightCloudState = GalleryMonthlyInsightCloudState.NotConfigured
}

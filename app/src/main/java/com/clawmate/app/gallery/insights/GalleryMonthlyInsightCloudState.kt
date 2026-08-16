package com.clawmate.app.gallery.insights

/**
 * Honest boundary for the cloud portions of Gallery monthly insights.
 *
 * The HarmonyOS source generates an optional AI monthly summary through a
 * configured cloud LLM (`summarizeGalleryMonth` on the gallery cloud client) and
 * stores embeddings via a configured embedding service. Neither cloud credential
 * nor endpoint is configured on the Android target yet, so this gate reports the
 * exact NOT_CONFIGURED state and never fabricates a summary or runs a fake call.
 * The deterministic local insight ([GalleryMonthlyInsights]) is independent of
 * this gate and always reflects only stored analysis data.
 */
sealed class GalleryMonthlyInsightCloudState {
    /** Cloud LLM monthly AI summary is available (would be Ready once configured). */
    data object NotConfigured : GalleryMonthlyInsightCloudState()

    /** The cloud LLM is configured but produced no claim; unused until config. */
    data object Unsupported : GalleryMonthlyInsightCloudState()

    /** A real cloud summary is available; unused until config. */
    data class Ready(val description: String) : GalleryMonthlyInsightCloudState()
}

/**
 * The default cloud insights state until a real cloud LLM / embedding
 * configuration exists. Kept as an explicit value so the UI can render the
 * truthful "未配置" notice instead of pretending an AI summary exists.
 */
object UnconfiguredMonthlyInsightCloud {
    val aiSummary: GalleryMonthlyInsightCloudState = GalleryMonthlyInsightCloudState.NotConfigured
    val embedding: GalleryMonthlyInsightCloudState = GalleryMonthlyInsightCloudState.NotConfigured
}

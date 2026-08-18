package com.clawmate.app.datamanager

import java.io.File

/**
 * Production composition helper for the Phase-37 **explicit** fact-splitting
 * action. It reuses the Phase-34 [ConsolidationModelClient] factory (which wraps
 * the same shared OpenAI-compatible [com.clawmate.app.chat.cloud.ChatClient] the
 * chat screen, Home planner, Phase-34 consolidation and Phase-35/36 enrich seams
 * use) so a user-facing "run fact split" action can be invoked with:
 *
 * ```kotlin
 * val runner = FactSplitClientFactory.runner(filesDir)
 * val result = runner.splitFacts(
 *     mode = FactSplitRunner.SplitMode.ModelRequested(
 *         explicitAction = true,
 *         config = ConsolidationModelConfig.fromEndpointConfig(),
 *     ),
 *     clientProvider = FactSplitClientFactory::client,
 * )
 * ```
 *
 * The runner itself enforces the explicit gate (zero network when not an
 * explicit action / unconfigured / no real entries) and never persists — the
 * caller decides whether to store the returned facts.
 */
object FactSplitClientFactory {

    /** Build the real fact-split runner bound to [baseDir]. */
    fun runner(baseDir: File): FactSplitRunner = FactSplitRunner(baseDir)

    /** Build the real model client for a fully-configured consolidation config. */
    fun client(config: ConsolidationModelConfig): ConsolidationModelClient =
        ConsolidationModelClientFactory.client(config)
}

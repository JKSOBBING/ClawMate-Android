package com.clawmate.app.datamanager

/**
 * Typed model boundary for the Phase-33 entity index.
 *
 * Android's entity resolver is deterministic and local-only by default. A model
 * is **only ever invited** for an explicit, user-triggered index/enrichment
 * action, and only when the shared [ProfileModelConfig] (from the existing
 * `EndpointConfig`) is fully configured. With no complete configuration the
 * boundary answers `NOT_CONFIGURED` — the invariant is "no real entity mentions
 * or incomplete config → never request the network".
 *
 * The model produces candidate merge assignments only; every admitted entity
 * still must reference real mention tokens (the deterministic local resolver is
 * authoritative). [EntityModelBoundary] never auto-calls anything on Home
 * launch/load.
 */
object EntityModelBoundary {

    /** Full-resolution of whether a model may be attempted for the current action. */
    enum class Gate(val token: String) {
        /** Zero network: no entity model configured (production default). */
        NOT_CONFIGURED("not_configured"),
        /** A model is fully configured and may be invited by the explicit action. */
        CONFIGURED("configured"),
        /** The action was rejected because the caller lacks explicit consent/trigger. */
        BLOCKED("blocked"),
    }

    /**
     * Resolve whether a model may be attempted. [explicitAction] must be true
     * (an explicit user-triggered index/enrichment action); [config] if present
     * must have a base url, api key and model name.
     */
    fun gate(explicitAction: Boolean, config: ProfileModelConfig?): Gate = when {
        !explicitAction -> Gate.BLOCKED
        config == null || !isCompletelyConfigured(config) -> Gate.NOT_CONFIGURED
        else -> Gate.CONFIGURED
    }

    fun isCompletelyConfigured(config: ProfileModelConfig): Boolean =
        config.baseUrl.isNotBlank() && config.apiKey.isNotBlank() && config.modelName.isNotBlank()
}

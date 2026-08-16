package com.clawmate.app.settings.config

import com.clawmate.app.config.EndpointConfig
import com.clawmate.app.settings.domain.AppSettings

/**
 * Bridges the persistent Settings store and the runtime [EndpointConfig].
 *
 * On first run (empty store) the Settings snapshot is seeded from whatever the
 * git-ignored `local_config.properties` loaded into [EndpointConfig], so a
 * configured endpoint shows up in the UI without duplicating secrets in the
 * committed defaults. On save, normalized non-masked values are pushed back
 * into [EndpointConfig] so ChatServerConfig / the cloud client see the user's
 * edit.
 *
 * Masked values are never pushed anywhere: the bridge drops them (they only
 * ever exist as a *display* transform in the UI).
 */
object EndpointBridge {

    /** Seed a snapshot with runtime endpoint values for the cloud planner. */
    fun seeded(settings: AppSettings): AppSettings = settings.copy(
        cloudPlannerBaseUrl = EndpointConfig.cloudPlannerBaseUrl,
        cloudPlannerApiKey = EndpointConfig.cloudPlannerApiKey,
        cloudPlannerModel = EndpointConfig.cloudPlannerModel,
    )

    /** Push non-masked normalized cloud endpoint values into runtime config. */
    fun applyToRuntime(settings: AppSettings) {
        if (EndpointConfig.isMasked(settings.cloudPlannerApiKey)) {
            // Keep the previously-known runtime key rather than clobbering it
            // with a masked placeholder; the plan URL/model still update.
            EndpointConfig.applyCloudEndpoint(
                plannerBaseUrl = settings.cloudPlannerBaseUrl,
                plannerApiKey = EndpointConfig.cloudPlannerApiKey,
                plannerModel = settings.cloudPlannerModel,
            )
        } else {
            EndpointConfig.applyCloudEndpoint(
                plannerBaseUrl = settings.cloudPlannerBaseUrl,
                plannerApiKey = settings.cloudPlannerApiKey,
                plannerModel = settings.cloudPlannerModel,
            )
        }
    }
}

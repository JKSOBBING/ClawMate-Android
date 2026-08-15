package com.clawmate.app.config

/**
 * Endpoint and credential configuration.
 *
 * Endpoints and secrets are deliberately NOT in source control. See
 * `MIGRATION_STATUS.md -> Networking & Security`. This object loads values
 * from a git-ignored raw resource (see app/src/main/res/raw/README and the
 * `config_example.properties` template) and falls back to safe, empty
 * defaults so the app always builds and runs without real credentials.
 *
 * HarmonyOS source reference (endpoints/keys live here — DO NOT copy the
 * real values):
 *   entry/src/main/ets/config/DefaultEndpointConfig.ets
 */
object EndpointConfig {
    var cloudPlannerBaseUrl: String = ""
        private set
    var cloudPlannerApiKey: String = ""
        private set
    var cloudPlannerModel: String = ""
        private set
    var cloudDeciderBaseUrl: String = ""
        private set
    var cloudDeciderApiKey: String = ""
        private set

    /**
     * Initialize from a `local_config.properties` file in res/raw.
     * Keys: cloudPlannerBaseUrl, cloudPlannerApiKey, cloudPlannerModel,
     *       cloudDeciderBaseUrl, cloudDeciderApiKey
     */
    fun load(properties: java.util.Properties) {
        cloudPlannerBaseUrl =
            properties.getProperty("cloudPlannerBaseUrl", "").trim()
        cloudPlannerApiKey =
            properties.getProperty("cloudPlannerApiKey", "").trim()
        cloudPlannerModel =
            properties.getProperty("cloudPlannerModel", "").trim()
        cloudDeciderBaseUrl =
            properties.getProperty("cloudDeciderBaseUrl", "").trim()
        cloudDeciderApiKey =
            properties.getProperty("cloudDeciderApiKey", "").trim()
    }
}

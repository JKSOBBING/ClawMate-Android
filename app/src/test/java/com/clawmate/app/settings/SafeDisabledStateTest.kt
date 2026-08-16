package com.clawmate.app.settings

import com.clawmate.app.settings.config.EndpointBridge
import com.clawmate.app.settings.domain.AppSettings
import com.clawmate.app.settings.domain.SensitiveValueUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Safe disabled / unconfigured states: the UI must never claim connectivity
 * when no real endpoint is configured, and the EndpointBridge must never push a
 * masked display value into runtime config.
 */
class SafeDisabledStateTest {

    @Test
    fun `planner is not configured until URL and key set`() {
        val base = AppSettings.defaults()
        assertFalse(base.cloudPlannerConfigured)
        assertFalse(base.copy(cloudPlannerBaseUrl = "https://planner.test").cloudPlannerConfigured)
        assertFalse(base.copy(cloudPlannerApiKey = "sk-x").cloudPlannerConfigured)
        assertTrue(
            base.copy(cloudPlannerBaseUrl = "https://planner.test", cloudPlannerApiKey = "sk-x")
                .cloudPlannerConfigured,
        )
    }

    @Test
    fun `configured flags require both url and secret`() {
        val base = AppSettings.defaults()
        assertFalse(base.copy(cloudDeciderBaseUrl = "https://d.test").cloudDeciderConfigured)
        assertFalse(base.copy(galleryApiBaseUrl = "https://g.test").galleryVisionConfigured)
        assertFalse(base.copy(galleryApiKey = "sk").galleryVisionConfigured)
        assertTrue(
            base.copy(cloudDeciderBaseUrl = "https://d.test", cloudDeciderApiKey = "sk").cloudDeciderConfigured,
        )
    }

    @Test
    fun `hdc configured when url present even without explicit connection flag`() {
        assertFalse(AppSettings.defaults().hdcBridgeConfigured)
        assertTrue(AppSettings.defaults().copy(hdcServerUrl = "http://192.168.1.1:9124").hdcBridgeConfigured)
    }

    @Test
    fun `endpointBridge never pushes a masked key into runtime config`() {
        // Seed runtime config with a real-looking value first.
        com.clawmate.app.config.EndpointConfig.applyCloudEndpoint(
            plannerBaseUrl = "https://current.test",
            plannerApiKey = "sk-current",
            plannerModel = "m1",
        )
        val masked = SensitiveValueUtils.maskSensitiveValue("sk-current")
        // A masked value in the draft must not clobber the runtime key.
        EndpointBridge.applyToRuntime(
            AppSettings.defaults().copy(
                cloudPlannerBaseUrl = "https://edited.test",
                cloudPlannerApiKey = masked,
                cloudPlannerModel = "m2",
            ),
        )
        assertEquals("https://edited.test", com.clawmate.app.config.EndpointConfig.cloudPlannerBaseUrl)
        // The runtime key stays the previous known real value (never the mask).
        assertEquals("sk-current", com.clawmate.app.config.EndpointConfig.cloudPlannerApiKey)
        assertEquals("m2", com.clawmate.app.config.EndpointConfig.cloudPlannerModel)
    }

    @Test
    fun `endpointBridge applies a plain typed secret to runtime config`() {
        EndpointBridge.applyToRuntime(
            AppSettings.defaults().copy(
                cloudPlannerBaseUrl = " https://new.test/ ",
                cloudPlannerApiKey = "sk-new",
            ),
        )
        assertEquals("https://new.test", com.clawmate.app.config.EndpointConfig.cloudPlannerBaseUrl)
        assertEquals("sk-new", com.clawmate.app.config.EndpointConfig.cloudPlannerApiKey)
    }

    @Test
    fun `masked value via EndpointConfig is not written when carrying mask marker`() {
        // Direct guard on the config seam itself: a masked value must leave the
        // existing key untouched rather than being stored as a literal.
        com.clawmate.app.config.EndpointConfig.applyCloudEndpoint(
            plannerBaseUrl = "https://x.test",
            plannerApiKey = "sk-real",
            plannerModel = "m",
        )
        com.clawmate.app.config.EndpointConfig.applyCloudEndpoint(
            plannerBaseUrl = "https://y.test",
            plannerApiKey = SensitiveValueUtils.maskSensitiveValue("sk-real"),
            plannerModel = "m2",
        )
        assertEquals("https://y.test", com.clawmate.app.config.EndpointConfig.cloudPlannerBaseUrl)
        // The runtime key never becomes the mask — it stays the previous value.
        assertEquals("sk-real", com.clawmate.app.config.EndpointConfig.cloudPlannerApiKey)
    }
}

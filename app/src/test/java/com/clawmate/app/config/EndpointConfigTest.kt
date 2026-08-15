package com.clawmate.app.config

import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repository-level unit check for EndpointConfig. Verifies that without any
 * configured value the app still has safe defaults (no real credentials and no
 * empty-string-turned-blank surprises), and that values are trimmed on load.
 */
class EndpointConfigTest {

    @Test
    fun `load with no properties yields empty safe defaults`() {
        val props = Properties()
        EndpointConfig.load(props)

        assertTrue(EndpointConfig.cloudPlannerBaseUrl.isEmpty())
        assertTrue(EndpointConfig.cloudPlannerApiKey.isEmpty())
        assertTrue(EndpointConfig.cloudPlannerModel.isEmpty())
        assertTrue(EndpointConfig.cloudDeciderBaseUrl.isEmpty())
        assertTrue(EndpointConfig.cloudDeciderApiKey.isEmpty())
    }

    @Test
    fun `load trims whitespace around values`() {
        val props = Properties()
        props.setProperty("cloudPlannerBaseUrl", "  https://planner.example.com  ")
        props.setProperty("cloudPlannerApiKey", "  key-123  ")
        props.setProperty("cloudPlannerModel", "  qwen-max  ")
        props.setProperty("cloudDeciderBaseUrl", "https://decider.example.com")
        props.setProperty("cloudDeciderApiKey", "key-456")

        EndpointConfig.load(props)

        assertEquals("https://planner.example.com", EndpointConfig.cloudPlannerBaseUrl)
        assertEquals("key-123", EndpointConfig.cloudPlannerApiKey)
        assertEquals("qwen-max", EndpointConfig.cloudPlannerModel)
        assertEquals("https://decider.example.com", EndpointConfig.cloudDeciderBaseUrl)
        assertEquals("key-456", EndpointConfig.cloudDeciderApiKey)
    }
}

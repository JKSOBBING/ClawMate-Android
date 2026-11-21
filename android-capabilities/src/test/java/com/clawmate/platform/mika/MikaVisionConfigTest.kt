package com.clawmate.platform.mika

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MikaVisionConfigTest {
    @Test fun parsesConfiguredVisionModelAndTrimsValues() {
        val config = MikaVisionConfig.parse(" https://api.example.com/v1 ", " qwen-vl-max ", " sk-test ")
        assertEquals("https://api.example.com/v1", config?.baseUrl)
        assertEquals("qwen-vl-max", config?.modelName)
        assertEquals("sk-test", config?.key)
    }

    @Test fun acceptsMissingKeyBecauseSomeGatewaysAuthenticateByPath() {
        val config = MikaVisionConfig.parse("https://api.example.com", "vision-model", "")
        assertEquals("https://api.example.com/v1", config?.baseUrl)
        assertEquals("", config?.key)
    }

    @Test fun blankNameSelectsEndpointDefault() {
        assertEquals("", MikaVisionConfig.parse("https://api.example.com", "  ", "k")?.modelName)
    }

    @Test fun privateDebugOriginAllowsOnlyItsOwnHostPortAndApiPath() {
        val origin = "http://127.0.0.1:19872"
        assertEquals("$origin/v1", MikaVisionConfig.parse("$origin/v1/chat/completions", "", "k", origin)?.baseUrl)
        for (url in listOf("http://127.0.0.1:19873", "$origin/elsewhere", "$origin/v1?q=1", "$origin/#token",
                "http://user@127.0.0.1:19872/v1", "http://example.com:19872")) {
            assertNull(MikaVisionConfig.parse(url, "", "k", origin))
        }
    }

    @Test fun rejectsPlainHttpAndOversizedFields() {
        assertNull("plain http", MikaVisionConfig.parse("http://api.example.com", "vision", "k"))
        assertNull("model over 180", MikaVisionConfig.parse("https://api.example.com", "m".repeat(181), "k"))
        assertNull("key over 4096", MikaVisionConfig.parse("https://api.example.com", "vision", "k".repeat(4097)))
    }
}

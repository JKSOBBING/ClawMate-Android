package com.clawmate.app.profile.avatar.generation

import com.clawmate.app.profile.avatar.data.generation.AvatarGenerationParams
import com.clawmate.app.profile.avatar.data.generation.AvatarProviderConfig
import com.clawmate.app.profile.avatar.data.generation.AvatarProviderRequest
import com.clawmate.app.profile.avatar.data.generation.AvatarProviderLimits
import com.clawmate.app.profile.avatar.data.generation.SeedreamRequestBodyBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-free tests for the pure Seedream request-body builder, ported exactly
 * from `createSeedreamRequestBody` in `SeedreamAvatarProvider.ets`. No network.
 */
class SeedreamRequestBodyBuilderTest {

    private val config = AvatarProviderConfig(
        imageBaseUrl = "https://img.example.com",
        apiKey = "sk-test",
        textToImageModel = "seedream-3",
        size = "2K",
    )

    private fun request(prompt: String = "生成一个卡通形象", size: String = "2K") = AvatarProviderRequest(
        prompt = prompt,
        model = config.textToImageModel,
        params = AvatarGenerationParams(size = size, responseFormat = "url", outputFormat = "jpeg", watermark = true),
    )

    @Test
    fun `body carries exact wire fields`() {
        val json = Json.parseToJsonElement(SeedreamRequestBodyBuilder.build(config, request())!!).jsonObject
        assertEquals("seedream-3", json["model"]!!.jsonPrimitive.content)
        assertEquals("生成一个卡通形象", json["prompt"]!!.jsonPrimitive.content)
        assertEquals("disabled", json["sequential_image_generation"]!!.jsonPrimitive.content)
        assertEquals("url", json["response_format"]!!.jsonPrimitive.content)
        assertEquals("jpeg", json["output_format"]!!.jsonPrimitive.content)
        assertEquals("2K", json["size"]!!.jsonPrimitive.content)
        assertEquals(false, json["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, json["watermark"]!!.jsonPrimitive.content.toBoolean())
        assertNull(json["image"])
    }

    @Test
    fun `output format is suppressed for seedream 4-5 models`() {
        // The model name is taken from the request (mirrors the .ets where an
        // explicit request.model wins over the config fallback).
        val modelName = "seedream-4-5-turbo"
        val json = Json.parseToJsonElement(
            SeedreamRequestBodyBuilder.build(config, request().copy(model = modelName))!!).jsonObject
        assertFalse("seedream-4-5 must omit output_format", json.containsKey("output_format"))
        assertEquals(modelName, json["model"]!!.jsonPrimitive.content)
        assertTrue(SeedreamRequestBodyBuilder.supportsOutputFormat("seedream-3"))
        assertFalse(SeedreamRequestBodyBuilder.supportsOutputFormat("seedream-4-5"))
    }

    @Test
    fun `image is included only when the source image is non-blank`() {
        val base = request()
        val noImage = SeedreamRequestBodyBuilder.build(config, base)
        val img = SeedreamRequestBodyBuilder.build(config, base.copy(sourceImage = "https://x/img.jpg"))
        assertEquals("seedream-3", Json.parseToJsonElement(noImage!!).jsonObject["model"]!!.jsonPrimitive.content)
        val with = Json.parseToJsonElement(img!!).jsonObject
        assertEquals("https://x/img.jpg", with["image"]!!.jsonPrimitive.content)
    }

    @Test
    fun `blank prompt or model yields null so no request is ever sent empty`() {
        assertNull(SeedreamRequestBodyBuilder.build(config, request(prompt = "  ")))
        // A blank request model with no config fallback must yield null.
        val noModel = AvatarProviderConfig(imageBaseUrl = "b", apiKey = "k", textToImageModel = "")
        assertNull(SeedreamRequestBodyBuilder.build(noModel, request().copy(model = "")))
        // A blank prompt is rejected even when a model is present.
        assertNull(SeedreamRequestBodyBuilder.build(config, request(prompt = "")))
    }

    @Test
    fun `prompt over the bound yields null`() {
        val huge = "a".repeat(AvatarProviderLimits.MAX_PROMPT_LENGTH + 1)
        assertNull(SeedreamRequestBodyBuilder.build(config, request(prompt = huge)))
    }

    @Test
    fun `model falls back to config text model when request model blank`() {
        val req = request().copy(model = "")
        val json = Json.parseToJsonElement(SeedreamRequestBodyBuilder.build(config, req)!!).jsonObject
        assertEquals(config.textToImageModel, json["model"]!!.jsonPrimitive.content)
    }

    // Guard against accidental mutable global state in the builder.
    @Test
    fun `concurrent builds are deterministic`() {
        val results = CopyOnWriteArrayList<String>()
        (1..64).toList().parallelStream().forEach {
            results.add(SeedreamRequestBodyBuilder.build(config, request(prompt = "并发-$it"))!!)
        }
        assertEquals(64, results.size)
        assertTrue(results.all { it.contains("并发-") })
    }
}

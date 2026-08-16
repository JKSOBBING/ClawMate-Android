package com.clawmate.app.profile.avatar.data.generation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Pure builder for the Seedream generate request body, ported faithfully from
 * `createSeedreamRequestBody` in `SeedreamAvatarProvider.ets`.
 *
 * The wire body is exactly:
 *   model, prompt, sequential_image_generation, response_format,
 *   [output_format iff the model does not contain "seedream-4-5"],
 *   size, stream, watermark, [image iff non-blank].
 *
 * [buildBounded] enforces [AvatarProviderLimits.MAX_PROMPT_LENGTH] and rejects
 * a blank prompt/model so an empty (never legitimate) request cannot be sent.
 * Deterministic and unit-testable; no I/O.
 */
object SeedreamRequestBodyBuilder {

    @Serializable
    private data class Body(
        @SerialName("model") val model: String,
        @SerialName("prompt") val prompt: String,
        @SerialName("sequential_image_generation") val sequentialImageGeneration: String,
        @SerialName("response_format") val responseFormat: String,
        @SerialName("output_format") val outputFormat: String? = null,
        @SerialName("size") val size: String,
        @SerialName("stream") val stream: Boolean,
        @SerialName("watermark") val watermark: Boolean,
        @SerialName("image") val image: String? = null,
    )

    /** Faithful port of `seedreamSupportsOutputFormat`. */
    fun supportsOutputFormat(model: String): Boolean =
        model.lowercase().contains("seedream-4-5").not()

    private fun resolveModel(model: String, config: AvatarProviderConfig, hasImage: Boolean): String {
        val explicit = model.trim()
        if (explicit.isNotEmpty()) return explicit
        return if (hasImage) config.imageToImageModel.trim() else config.textToImageModel.trim()
    }

    /**
     * Build the validated request body for a text-to-image / image-to-image
     * generation. Returns null when the request is out of bounds (blank prompt
     * or model, or prompt exceeding [AvatarProviderLimits.MAX_PROMPT_LENGTH]) so
     * the caller surfaces an honest READY/ERROR without sending any bytes.
     */
    fun build(
        config: AvatarProviderConfig,
        request: AvatarProviderRequest,
        image: String = request.sourceImage.trim(),
    ): String? {
        val model = resolveModel(request.model, config, image.isNotEmpty())
        val prompt = request.prompt.trim()
        if (model.isEmpty() || prompt.isEmpty()) return null
        if (prompt.length > AvatarProviderLimits.MAX_PROMPT_LENGTH) return null

        val outFormat = request.params.outputFormat.trim()
        val body = Body(
            model = model,
            prompt = prompt,
            sequentialImageGeneration = request.params.sequentialImageGeneration,
            responseFormat = request.params.responseFormat,
            outputFormat = if (outFormat.isNotEmpty() && supportsOutputFormat(model)) outFormat else null,
            size = request.params.size.ifBlank { config.size }.trim(),
            stream = request.params.stream,
            watermark = request.params.watermark,
            image = image.ifEmpty { null },
        )
        return Json.encodeToString(Body.serializer(), body)
    }
}

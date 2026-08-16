package com.clawmate.app.profile.avatar.data.generation

/**
 * Pure wire contract + defensive bounds for the configured Seedream digital
 * avatar image generation provider (migration row **A2**).
 *
 * Ported from the HarmonyOS source of truth (read-only, never modified):
 *   entry/src/main/ets/datamanager/avatar/AvatarTypes.ets
 *   entry/src/main/ets/datamanager/avatar/SeedreamAvatarProvider.ets
 *
 * Every type here is a plain, total, deterministic value object. Nothing in
 * this package performs network I/O, stores a credential, or fabricates an
 * image; the real OkHttp work lives in [AvatarGenerationClient] and is gated
 * behind the honest [AvatarProvider] boundary so a caller that is not READY
 * never sends a byte.
 */
object AvatarProviderLimits {
    /** Hard cap on the prompt text sent to the provider (kept well under the
     *  provider's own expectations and safely bounded). */
    const val MAX_PROMPT_LENGTH: Int = 12_000

    /** Minimum completeness for a generation to be attempted (%). */
    const val MIN_PROFILE_COMPLETENESS: Int = 20

    /** Maximum size in bytes of a decoded provider image the app will accept.
     *  ~4 MB — bounded so a hostile/oversized provider result is rejected. */
    const val MAX_IMAGE_BYTES: Int = 4 * 1024 * 1024

    /** Maximum size of the JSON generation response body read before parse. */
    const val MAX_RESPONSE_BODY_BYTES: Int = 512 * 1024

    /** Cap on the base64 string (the JSON `data[0].b64_json`) accepted before
     *  decode. Base64 of [MAX_IMAGE_BYTES] ≈ 5.6 MB; allow a little headroom.
     */
    const val MAX_BASE64_LENGTH: Int = 8 * 1024 * 1024

    /** Acceptable image content types for a downloaded provider image. */
    val ACCEPTED_IMAGE_CONTENT_TYPES: Set<String> = setOf(
        "image/jpeg", "image/jpg", "image/png", "image/webp",
    )
}

/** Provider params transported verbatim in the generate body (port of
 *  `AvatarGenerationParams` in AvatarTypes.ets). */
data class AvatarGenerationParams(
    val size: String = "",
    val responseFormat: String = "url",
    val outputFormat: String = "jpeg",
    val sequentialImageGeneration: String = "disabled",
    val stream: Boolean = false,
    val watermark: Boolean = true,
)

/**
 * Provider configuration built from the locally configured settings. Never
 * stored anywhere and never logged. Mirrors `AvatarProviderConfig`.
 */
data class AvatarProviderConfig(
    val imageBaseUrl: String = "",
    val apiKey: String = "",
    val textToImageModel: String = "",
    val imageToImageModel: String = "",
    val size: String = "",
    val responseFormat: String = "url",
    val outputFormat: String = "jpeg",
    val sequentialImageGeneration: String = "disabled",
    val stream: Boolean = false,
    val watermark: Boolean = true,
    val timeoutMs: Long = 60_000L,
)

/** A provider generate request (port of `AvatarProviderRequest`). */
data class AvatarProviderRequest(
    val prompt: String = "",
    val sourceImage: String = "",
    val model: String = "",
    val params: AvatarGenerationParams = AvatarGenerationParams(),
)

/** Result of a provider generation (port of `AvatarProviderResult`). */
data class AvatarProviderResult(
    val imageBytesBase64: String = "",
    val imageUrl: String = "",
    val providerRequestId: String = "",
    val rawMetadata: Map<String, String> = emptyMap(),
) {
    /** Whether the result carries a decodable/persistable image. */
    val hasImageData: Boolean get() = imageBytesBase64.isNotBlank() || imageUrl.isNotBlank()
}

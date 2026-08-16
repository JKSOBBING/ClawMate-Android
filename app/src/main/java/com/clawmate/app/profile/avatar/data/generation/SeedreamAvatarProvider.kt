package com.clawmate.app.profile.avatar.data.generation

import okhttp3.OkHttpClient

/**
 * Production [AvatarProvider] wired to the Seedream-compatible endpoint,
 * ported from `SeedreamAvatarProvider.ets`.
 *
 * [generateTextToImage] and [generateImageToImage] only reach the network when
 * the request is fully configured and valid:
 *  - base URL is a well-formed http(s) endpoint ([AvatarUrlValidator]);
 *  - api key, model and bounded prompt are non-blank;
 *  - image-to-image additionally requires a non-blank source image.
 *
 * Otherwise they throw an honest, *redacted* [AvatarProviderException] with
 * cause [AvatarErrorCause.NOT_READY] / [AvatarErrorCause.INVALID_ARGUMENT]
 * before any HTTP request is built. The api key never appears in any message.
 */
class SeedreamAvatarProvider(
    private val client: AvatarGenerationClient,
) : AvatarProvider {

    override suspend fun generateTextToImage(
        config: AvatarProviderConfig,
        request: AvatarProviderRequest,
    ): AvatarProviderResult {
        requireConfig(config, request, image = "")
        return client.generate(config, request)
    }

    override suspend fun generateImageToImage(
        config: AvatarProviderConfig,
        request: AvatarProviderRequest,
    ): AvatarProviderResult {
        val image = request.sourceImage.trim()
        if (image.isEmpty()) {
            throw AvatarProviderException(AvatarErrorCause.INVALID_ARGUMENT, "Seedream 图生图需要一张来源图片")
        }
        requireConfig(config, request, image)
        return client.generate(config, request.copy(sourceImage = image))
    }

    private fun requireConfig(config: AvatarProviderConfig, request: AvatarProviderRequest, image: String) {
        val base = AvatarUrlValidator.validateBaseUrl(config.imageBaseUrl)
        if (!base.valid) {
            throw AvatarProviderException(AvatarErrorCause.NOT_READY, "Seedream 图片服务 URL 未配置")
        }
        if (config.apiKey.isBlank()) {
            throw AvatarProviderException(AvatarErrorCause.NOT_READY, "Seedream API Key 未配置")
        }
        val model = request.model.trim().ifEmpty {
            if (image.isNotEmpty()) config.imageToImageModel else config.textToImageModel
        }
        if (model.isBlank()) {
            throw AvatarProviderException(AvatarErrorCause.NOT_READY, "Seedream 模型未配置")
        }
        if (request.prompt.isBlank()) {
            throw AvatarProviderException(AvatarErrorCause.INVALID_ARGUMENT, "Seedream 提示词为空")
        }
        if (request.prompt.length > AvatarProviderLimits.MAX_PROMPT_LENGTH) {
            throw AvatarProviderException(AvatarErrorCause.INVALID_ARGUMENT, "Seedream 提示词超出上限")
        }
        if (config.size.isBlank()) {
            throw AvatarProviderException(AvatarErrorCause.NOT_READY, "Seedream 图片尺寸未配置")
        }
    }

    companion object {
        /**
         * Build a [SeedreamAvatarProvider] with a default OkHttpClient configured
         * with sane connect/read/write timeouts and no auto-redirect surprises.
         * Per-call timeouts are additionally applied from the provider config.
         */
        fun withDefaultClient(
            client: OkHttpClient = OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
        ): SeedreamAvatarProvider = SeedreamAvatarProvider(AvatarGenerationClient(client))
    }
}

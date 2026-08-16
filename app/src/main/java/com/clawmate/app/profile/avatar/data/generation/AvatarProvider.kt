package com.clawmate.app.profile.avatar.data.generation

/**
 * The honest boundary a caller (the avatar ViewModel) talks to in order to
 * generate a real avatar image. Production uses [SeedreamAvatarProvider] backed
 * by [AvatarGenerationClient]; tests inject a fake. The contract promises:
 *  - **no network call when unconfigured** — a [AvatarProviderException] with a
 *    [AvatarErrorCause.NOT_READY] is thrown *before* any request is built/sent;
 *  - redacted messages — an exception message never contains the api key;
 *  - cancellation — the coroutine cancelling a generate propagates to the
 *    underlying HTTP request.
 */
interface AvatarProvider {
    /** Generate a text-to-image avatar (no source photo used). */
    suspend fun generateTextToImage(
        config: AvatarProviderConfig,
        request: AvatarProviderRequest,
    ): AvatarProviderResult

    /** Generate an image-to-image avatar from an explicit [AvatarProviderRequest.sourceImage]. */
    suspend fun generateImageToImage(
        config: AvatarProviderConfig,
        request: AvatarProviderRequest,
    ): AvatarProviderResult
}

/** High-level cause of a failed generation, used by the UI for an honest message. */
enum class AvatarErrorCause {
    NOT_READY,
    INVALID_ARGUMENT,
    HTTP,
    PARSE,
    UNSUPPORTED_IMAGE,
    DOWNLOAD,
    TIMEOUT,
    CANCELLED,
    INTERNAL,
}

/** A generation failure. [message] is always redacted (never contains the key). */
class AvatarProviderException(
    val reason: AvatarErrorCause,
    message: String,
    causeThrowable: Throwable? = null,
) : Exception(message, causeThrowable)

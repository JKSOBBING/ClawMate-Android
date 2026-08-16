package com.clawmate.app.profile.avatar.generation

import com.clawmate.app.profile.avatar.data.generation.AvatarErrorCause
import com.clawmate.app.profile.avatar.data.generation.AvatarProvider
import com.clawmate.app.profile.avatar.data.generation.AvatarProviderConfig
import com.clawmate.app.profile.avatar.data.generation.AvatarProviderException
import com.clawmate.app.profile.avatar.data.generation.AvatarProviderRequest
import com.clawmate.app.profile.avatar.data.generation.AvatarProviderResult
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Deterministic, in-memory [AvatarProvider] for device-free ViewModel tests.
 * Returns a small real base64-encoded "image" on success, records the config it
 * was called with (so a no-call assertion is exact), and can be configured to
 * throw a redacted provider error or to block until cancelled.
 */
class FakeAvatarProvider : AvatarProvider {

    val calls = AtomicInteger(0)
    val lastConfig = AtomicReference<AvatarProviderConfig?>(null)

    @Volatile
    var throwError: Throwable? = null

    /** When non-null, always throw this redacted message. */
    @Volatile
    var errorMessage: String? = null

    /** Blocking gate to exercise cancellation: when set, await this deferred. */
    @Volatile
    var blockGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** A real tiny JPEG-like payload the fake "generates". */
    val imageBytes = ByteArray(64) { (it + 1).toByte() }

    override suspend fun generateTextToImage(
        config: AvatarProviderConfig,
        request: AvatarProviderRequest,
    ): AvatarProviderResult {
        calls.incrementAndGet()
        lastConfig.set(config)
        throwError?.let { throw it }
        errorMessage?.let { throw AvatarProviderException(AvatarErrorCause.HTTP, it) }
        blockGate?.let { it.await() } // blocks until cancelled or completed
        return AvatarProviderResult(
            imageBytesBase64 = Base64.getEncoder().encodeToString(imageBytes),
            providerRequestId = "fake-req-1",
        )
    }

    override suspend fun generateImageToImage(
        config: AvatarProviderConfig,
        request: AvatarProviderRequest,
    ): AvatarProviderResult {
        calls.incrementAndGet()
        return generateTextToImage(config, request)
    }
}

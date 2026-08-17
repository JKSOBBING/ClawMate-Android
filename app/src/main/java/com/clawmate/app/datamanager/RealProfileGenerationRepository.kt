package com.clawmate.app.datamanager

import kotlinx.coroutines.CancellationException

/**
 * The Home-facing seam behind the explicit "生成画像" action. Implementing
 * [generate] performs an explicit, user-triggered profile build (local-only when
 * the model is unconfigured, model-backed otherwise) and returns a typed
 * [ProfileGenerationState]. It is **only ever** invoked from the explicit Home
 * button — never at launch, on recomposition, from a background job, or on a
 * normal Home load.
 */
fun interface ProfileGenerationRepository {
    suspend fun generate(): ProfileGenerationState
}

/**
 * The real [ProfileGenerationRepository]. Reads app-private indexed events and
 * runs [ProfileIndexRunner.run] over an app-private base dir. `wasConfigured` is
 * reported so the UI can distinguish a NOT_CONFIGURED local-only profile from a
 * model profile. Exceptions are never thrown to the UI; a run that fails returns
 * the honest local result (or a typed error).
 */
class RealProfileGenerationRepository(
    private val baseDir: java.io.File,
    private val runner: ProfileIndexRunner = ProfileIndexRunner(baseDir),
) : ProfileGenerationRepository {

    private val wasConfigured: Boolean get() = ProfileModelConfig.fromEndpointConfig() != null

    override suspend fun generate(): ProfileGenerationState {
        val result = try {
            runner.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never throw to the UI: keep the honest local result / error state.
            return ProfileGenerationState.Error(
                message = "画像生成失败：${e.message ?: "未知错误"}",
                userMessage = "画像生成失败，请重试。",
            )
        }
        return ProfileGenerationStateMapper.fromResult(result, wasConfigured)
    }

    companion object {
        /** Build a real repository over an app-private `filesDir`. */
        fun appPrivate(filesDir: java.io.File): RealProfileGenerationRepository =
            RealProfileGenerationRepository(filesDir)
    }
}

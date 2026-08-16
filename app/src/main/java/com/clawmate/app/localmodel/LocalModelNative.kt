package com.clawmate.app.localmodel

/**
 * JNI binding to the native local-model stub (`clawmate_local_model`, built
 * from `app/src/main/cpp`).
 *
 * Because MNN/HiAI for Android is absent the native side returns typed
 * UNSUPPORTED results; this object maps them into the [LocalModelStatus]
 * / [LocalModelErrorKind] domain. It is load-failure tolerant: if the shared
 * library cannot be loaded (minSdk/runtime mismatch, packaging issue), every
 * call reports a typed [LocalModelErrorKind.NATIVE_LIBRARY_MISSING] instead of
 * crashing the app.
 *
 * No function here ever invents a model result; [nativeSupported] is true only
 * after the .so loads AND reports a status that is not UNSUPPORTED.
 */
object LocalModelNative {

    /** Magic self-check value returned by the native side. */
    private const val NATIVE_MAGIC = 0xC14D4D4L

    private val loaded: Boolean = runCatching {
        System.loadLibrary("clawmate_local_model")
        true
    }.getOrDefault(false)

    private val magicOk: Boolean =
        loaded && nativeSelfCheck() == NATIVE_MAGIC

    /**
     * True only when the native library is present and its self-check passed.
     * With only the stub shipped this is always false, which is the honest
     * state (a real MNN-backed build would link the library and return true).
     */
    val nativeSupported: Boolean get() = loaded && magicOk

    /** Typed native-adapter status; falls back to UNSUPPORTED when missing. */
    fun status(): LocalModelStatus {
        if (!loaded || !magicOk) return LocalModelStatus.UNSUPPORTED
        return when (nativeStatus()) {
            "READY" -> LocalModelStatus.READY
            "LOADING" -> LocalModelStatus.LOADING
            else -> LocalModelStatus.UNSUPPORTED
        }
    }

    /**
     * Attempts a native load of a validated model dir. Returns a typed
     * result: UNSUPPORTED with [LocalModelErrorKind.NATIVE_LIBRARY_MISSING]
     * (the stub) or a typed READY on a future real native success. It can
     * never report success unless the native side actually returns it.
     */
    fun loadModel(configPath: String): NativeLoadResult {
        if (configPath.isBlank()) {
            return NativeLoadResult.Unsupported(LocalModelErrorKind.INVALID_PATH, "空模型路径")
        }
        if (!loaded || !magicOk) {
            return NativeLoadResult.Unsupported(
                LocalModelErrorKind.NATIVE_LIBRARY_MISSING,
                "缺少 Android MNN/LLM 原生推理库，无法加载本地模型",
            )
        }
        val result = nativeLoad(configPath)
        return NativeLoadResult.parse(result)
    }

    /** Releases any native model state (no-op in the stub). */
    fun unload() {
        if (loaded && magicOk) {
            nativeUnload()
        }
    }

    /** A typed result of a native load attempt. */
    sealed class NativeLoadResult {
        data class Success(val detail: String = "") : NativeLoadResult()
        data class Unsupported(val kind: LocalModelErrorKind, val message: String) : NativeLoadResult()
        data class Failed(val kind: LocalModelErrorKind, val message: String) : NativeLoadResult()

        companion object {
            fun parse(raw: String): NativeLoadResult {
                if (raw.isBlank()) {
                    return Unsupported(LocalModelErrorKind.NATIVE_LIBRARY_MISSING, "原生加载返回空结果")
                }
                val firstSegment = raw.substringBefore(':').trim()
                return when (firstSegment.uppercase()) {
                    "READY", "OK" -> Success(raw)
                    "UNSUPPORTED", "NATIVE_LIBRARY_MISSING" ->
                        Unsupported(LocalModelErrorKind.NATIVE_LIBRARY_MISSING, raw)
                    else -> Failed(LocalModelErrorKind.LOAD_FAILED, raw)
                }
            }
        }
    }

    // JNI declarations — implemented in app/src/main/cpp/local_model_jni.cpp.
    private external fun nativeStatus(): String
    private external fun nativeLoad(configPath: String): String
    private external fun nativeUnload(): String
    private external fun nativeSelfCheck(): Long
}

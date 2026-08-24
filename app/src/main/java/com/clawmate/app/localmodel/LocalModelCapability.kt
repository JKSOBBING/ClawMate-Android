package com.clawmate.app.localmodel

/**
 * The local-model capability boundary: reconciles the discovered model
 * inventory, path validation, and the native adapter into a single typed
 * [LocalModelState]. This is the honest gate that prevents fabricated output:
 *
 *  - A state can reach [LocalModelStatus.READY] **only** when BOTH a real,
 *    validated model directory exists on disk AND the native adapter reports a
 *    real native load success.
 *  - When MNN/LLM for Android is absent the native step necessarily reports
 *    [LocalModelErrorKind.NATIVE_LIBRARY_MISSING] and the state is
 *    [LocalModelStatus.UNSUPPORTED] — never READY.
 *
 * [scan] and [native] are injectable so tests can exercise every branch
 * deterministically without real files or a real .so.
 */
class LocalModelCapability(
    /** The app-private data dir this capability scans (exposed for the registry). */
    val filesDir: String?,
    private val scan: (String?) -> List<LocalModelInfo> = { LocalModelInventory.scan(it) },
    private val native: LocalModelNativeAdapter = RealNativeAdapter,
    private val loadModel: (String) -> LocalModelNative.NativeLoadResult = { LocalModelNative.loadModel(it) },
) {

    /**
     * Refreshes the inventory and returns a NOT_CONFIGURED/UNSUPPORTED/ERROR
     * state derived purely from disk. Never claims READY here — READY is only
     * produced after an actual native load succeeds.
     */
    fun refresh(): LocalModelState {
        if (filesDir.isNullOrBlank()) {
            return LocalModelState(
                status = LocalModelStatus.NOT_CONFIGURED,
                errorKind = LocalModelErrorKind.NOT_CONFIGURED,
                message = "应用私有 data 目录不可用",
            )
        }
        val models = scan(filesDir)
        // No discovered model directory => NOT_CONFIGURED (honest).
        if (models.isEmpty()) {
            return LocalModelState(
                status = LocalModelStatus.NOT_CONFIGURED,
                errorKind = LocalModelErrorKind.NOT_CONFIGURED,
                message = "未在应用私有目录发现本地模型",
                models = models,
            )
        }
        // A real model folder exists. Pick the selected/ready one (first ready,
        // else first), then decide the capability state.
        val candidate = models.firstOrNull { it.ready } ?: models.first()
        if (!native.supported()) {
            return LocalModelState(
                status = LocalModelStatus.UNSUPPORTED,
                errorKind = LocalModelErrorKind.NATIVE_LIBRARY_MISSING,
                message = "缺少 Android 端 MNN/LLM 原生推理库，模型目录存在但无法加载",
                models = models,
                selectedPath = candidate.path,
                selectedModelId = candidate.id,
            )
        }
        // Native library present but the candidate is incomplete.
        if (!candidate.ready) {
            return LocalModelState(
                status = LocalModelStatus.ERROR,
                errorKind = firstErrorKind(candidate),
                message = candidate.statusText,
                models = models,
                selectedPath = candidate.path,
                selectedModelId = candidate.id,
            )
        }
        // Found a complete model + native library supported but not yet loaded.
        return LocalModelState(
            status = LocalModelStatus.NOT_CONFIGURED,
            errorKind = null,
            message = "检测到完整模型（${candidate.label}），可执行加载测试",
            models = models,
            selectedPath = candidate.path,
            selectedModelId = candidate.id,
        )
    }

    /**
     * The explicit load/test action. It validates the selected model dir,
     * invokes the native adapter, and maps the result to a state. **READY is
     * returned only when the native load returned a Success** — never faked.
     * Throws nothing; every failure becomes a typed ERROR/UNSUPPORTED state.
     */
    fun loadAndTest(modelId: String): LocalModelState {
        if (filesDir.isNullOrBlank()) {
            return LocalModelState(
                status = LocalModelStatus.NOT_CONFIGURED,
                errorKind = LocalModelErrorKind.NOT_CONFIGURED,
                message = "应用私有 data 目录不可用",
            )
        }
        val models = scan(filesDir)
        val candidate = models.firstOrNull { it.id == modelId }
            ?: return LocalModelState(
                status = LocalModelStatus.ERROR,
                errorKind = LocalModelErrorKind.NOT_CONFIGURED,
                message = "模型不存在或尚未扫描到: $modelId",
                models = models,
            )
        // 1) Path must stay app-private and must not be an escaping/legacy alias
        //    (e.g. `modelscope/foo/bar` is not a valid repo root).
        if (!LocalModelPathValidator.isValidModelDir(filesDir, candidate.path)) {
            return LocalModelState(
                status = LocalModelStatus.ERROR,
                errorKind = LocalModelErrorKind.INVALID_PATH,
                message = "模型路径无效（必须在应用私有目录内）: ${candidate.path}",
                models = models,
                selectedPath = candidate.path,
                selectedModelId = modelId,
            )
        }

        // 2) Must be a real, complete model directory.
        if (!candidate.hasConfig) {
            return LocalModelState(
                status = LocalModelStatus.ERROR,
                errorKind = LocalModelErrorKind.MISSING_CONFIG,
                message = "缺少 ${candidate.label}/config.json",
                models = models,
                selectedPath = candidate.path,
                selectedModelId = modelId,
            )
        }
        if (!candidate.hasWeightFile) {
            return LocalModelState(
                status = LocalModelStatus.ERROR,
                errorKind = LocalModelErrorKind.MISSING_MODEL_FILE,
                message = "缺少模型权重文件（.mnn/.om/.safetensors/.weight）",
                models = models,
                selectedPath = candidate.path,
                selectedModelId = modelId,
            )
        }
        if (!candidate.ready) {
            return LocalModelState(
                status = LocalModelStatus.ERROR,
                errorKind = LocalModelErrorKind.NOT_READY,
                message = "模型尚未下载完成: ${candidate.label}",
                models = models,
                selectedPath = candidate.path,
                selectedModelId = modelId,
            )
        }

        // 3) Native library must be able to run inference.
        if (!native.supported()) {
            return LocalModelState(
                status = LocalModelStatus.UNSUPPORTED,
                errorKind = LocalModelErrorKind.NATIVE_LIBRARY_MISSING,
                message = "模型文件完整，但 Android MNN/LLM 原生推理库缺失，无法加载",
                models = models,
                selectedPath = candidate.path,
                selectedModelId = modelId,
            )
        }

        // 4) Perform the real native load. Only a real Success yields READY.
        val configPath = "${candidate.path}/config.json"
        return when (val result = loadModel(configPath)) {
            is LocalModelNative.NativeLoadResult.Success -> LocalModelState(
                status = LocalModelStatus.READY,
                errorKind = null,
                message = "本地模型已就绪: ${candidate.label}",
                models = models,
                selectedPath = candidate.path,
                selectedModelId = modelId,
            )
            is LocalModelNative.NativeLoadResult.Unsupported -> LocalModelState(
                status = LocalModelStatus.UNSUPPORTED,
                errorKind = result.kind,
                message = result.message,
                models = models,
                selectedPath = candidate.path,
                selectedModelId = modelId,
            )
            is LocalModelNative.NativeLoadResult.Failed -> LocalModelState(
                status = LocalModelStatus.ERROR,
                errorKind = result.kind,
                message = result.message,
                models = models,
                selectedPath = candidate.path,
                selectedModelId = modelId,
            )
        }
    }

    private fun firstErrorKind(info: LocalModelInfo): LocalModelErrorKind = when {
        !info.hasConfig -> LocalModelErrorKind.MISSING_CONFIG
        !info.hasWeightFile -> LocalModelErrorKind.MISSING_MODEL_FILE
        else -> LocalModelErrorKind.NOT_READY
    }
}

/** The native-adapter seam used by [LocalModelCapability]. */
interface LocalModelNativeAdapter {
    fun supported(): Boolean
}

/** Production adapter backed by the JNI stub ([LocalModelNative]). */
object RealNativeAdapter : LocalModelNativeAdapter {
    override fun supported(): Boolean = LocalModelNative.nativeSupported
}

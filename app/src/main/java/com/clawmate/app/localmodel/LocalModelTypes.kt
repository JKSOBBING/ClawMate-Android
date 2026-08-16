package com.clawmate.app.localmodel

/**
 * Local model domain types for the Android local-model capability boundary.
 *
 * The HarmonyOS reference (`entry/src/main/ets/pages/Index.ets` +
 * `entry/src/main/cpp/napi_init.cpp`) loads an on-device LLM via MNN/HiAI and
 * validates a model directory by the presence of `config.json` plus at least
 * one weight file (`.mnn`, `.om`, `.safetensors`, `.weight`), optionally
 * cross-checking a download manifest. This file ports that *decision logic*
 * into typed, deterministic Kotlin states — it never fabricates a model result
 * and never claims a native model is loaded when the native library (MNN /
 * Android LLM adapter) is absent.
 */

/** The local-model lifecycle state machine. */
enum class LocalModelStatus {
    /** No model directory is configured and none is loaded (safe default). */
    NOT_CONFIGURED,

    /** A real model is configured on disk, but the native Android inference
     *  library (MNN / llm adapter) is not present, so loading cannot run. */
    UNSUPPORTED,

    /** A real model directory is validated and a native load is in progress. */
    LOADING,

    /** A real model directory exists AND a native load succeeded. This is the
     *  only state that claims an on-device model is usable. */
    READY,

    /** A load was attempted but failed (missing/corrupt file, or native
     *  load returned a typed error). No model output is ever produced. */
    ERROR,
}

/** Typed reasons a local model cannot move to READY (never fabricated). */
enum class LocalModelErrorKind {
    /** No model directory is configured. */
    NOT_CONFIGURED,

    /** The selected path is not a valid app-private model directory (e.g.
     *  outside `filesDir`, a path that escapes the sandbox, or a symlink). */
    INVALID_PATH,

    /** The directory exists but is missing `config.json`. */
    MISSING_CONFIG,

    /** The directory exists but has no weight file (`.mnn`/`.om`/
     *  `.safetensors`/`.weight`). */
    MISSING_MODEL_FILE,

    /** The directory is not yet a complete/loadable model (download incomplete). */
    NOT_READY,

    /** The native Android inference library (MNN / llm adapter) is absent. */
    NATIVE_LIBRARY_MISSING,

    /** The native load itself reported a typed failure. */
    LOAD_FAILED,
}

/** Deterministic human-friendly label for a status (used in Settings UI). */
fun LocalModelStatus.displayLabel(): String = when (this) {
    LocalModelStatus.NOT_CONFIGURED -> "未配置"
    LocalModelStatus.UNSUPPORTED -> "不支持（缺少原生推理库）"
    LocalModelStatus.LOADING -> "加载中…"
    LocalModelStatus.READY -> "已就绪"
    LocalModelStatus.ERROR -> "加载失败"
}

/** Deterministic human-friendly label for an error kind. */
fun LocalModelErrorKind.displayLabel(): String = when (this) {
    LocalModelErrorKind.NOT_CONFIGURED -> "未配置模型目录"
    LocalModelErrorKind.INVALID_PATH -> "模型路径无效"
    LocalModelErrorKind.MISSING_CONFIG -> "缺少 config.json"
    LocalModelErrorKind.MISSING_MODEL_FILE -> "缺少模型权重文件"
    LocalModelErrorKind.NOT_READY -> "模型未下载完成"
    LocalModelErrorKind.NATIVE_LIBRARY_MISSING -> "缺少原生推理库"
    LocalModelErrorKind.LOAD_FAILED -> "模型加载失败"
}

/**
 * A discovered or configured local model.
 *
 * [path] is always validated to live under the app-privat `filesDir`,
 * mirroring `Index.ets` (`filesDir/model` legacy and `filesDir/modelscope/...`
 * ModelScope repos). [id] and [label] identify the model to the user.
 */
data class LocalModelInfo(
    val id: String,
    val label: String,
    val path: String,
    val sizeBytes: Long = 0L,
    /** Raw loadable-status text ported from `Index.ets modelLoadableStatus`. */
    val statusText: String = "",
    val ready: Boolean = false,
    val hasConfig: Boolean = false,
    val hasWeightFile: Boolean = false,
)

/**
 * The full typed snapshot the Settings panel renders. [errorKind] is non-null
 * only in non-READY states; [message] carries an honest, user-facing detail
 * when available.
 */
data class LocalModelState(
    val status: LocalModelStatus = LocalModelStatus.NOT_CONFIGURED,
    val errorKind: LocalModelErrorKind? = null,
    val message: String = "",
    /** The selected model directory (validated under `filesDir`, else empty). */
    val selectedPath: String = "",
    val selectedModelId: String = "",
    /** The currently discovered model inventory (never fabricated). */
    val models: List<LocalModelInfo> = emptyList(),
    val loading: Boolean = false,
    val loadTestBusy: Boolean = false,
) {
    /** True when a real on-device model is usable. */
    val isReady: Boolean get() = status == LocalModelStatus.READY
}

/**
 * Maps a pure loadable-status string (ported from `Index.ets`) into the typed
 * capability state. This is the single normalization entry point used by the
 * inventory scanner and the load/test flow, kept free of any native calls so it
 * is deterministic and unit-testable.
 *
 * @param statusText the raw status from a model folder scan ("下载完成",
 *   "可加载", "缺少 config.json", "缺少模型权重文件", "下载未完成", "").
 * @param nativeLibrarySupported whether the Android MNN/llm adapter is present.
 */
fun normalizeModelCapabilityState(
    statusText: String,
    hasConfig: Boolean,
    hasWeightFile: Boolean,
    nativeLibrarySupported: Boolean,
    hasModelFolder: Boolean,
): LocalModelState {
    if (!hasModelFolder) {
        return LocalModelState(
            status = LocalModelStatus.NOT_CONFIGURED,
            errorKind = LocalModelErrorKind.NOT_CONFIGURED,
            message = "未在应用私有目录发现本地模型",
        )
    }
    if (!nativeLibrarySupported) {
        return LocalModelState(
            status = LocalModelStatus.UNSUPPORTED,
            errorKind = LocalModelErrorKind.NATIVE_LIBRARY_MISSING,
            message = "缺少 Android 端 MNN/LLM 原生推理库，当前无法加载本地模型",
        )
    }
    // natively supported but the folder is incomplete
    if (!hasConfig) {
        return LocalModelState(
            status = LocalModelStatus.ERROR,
            errorKind = LocalModelErrorKind.MISSING_CONFIG,
            message = "模型目录缺少 config.json",
        )
    }
    if (!hasWeightFile) {
        return LocalModelState(
            status = LocalModelStatus.ERROR,
            errorKind = LocalModelErrorKind.MISSING_MODEL_FILE,
            message = "模型目录缺少权重文件（.mnn/.om/.safetensors/.weight）",
        )
    }
    // A real folder + config + weight present => ready to attempt a native load.
    if (statusText == "下载完成" || statusText == "可加载" || statusText == "可加载（已扫描）") {
        return LocalModelState(
            status = LocalModelStatus.NOT_CONFIGURED, // not loaded until native load succeeds
            errorKind = null,
            message = "模型文件完整，等待加载",
        )
    }
    return LocalModelState(
        status = LocalModelStatus.ERROR,
        errorKind = LocalModelErrorKind.NOT_READY,
        message = "模型未下载完成",
    )
}

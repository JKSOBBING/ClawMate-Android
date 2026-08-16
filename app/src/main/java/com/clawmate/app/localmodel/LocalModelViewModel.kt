package com.clawmate.app.localmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Backs the "本地模型" panel in the 模型与智能体 Settings section.
 *
 * It wraps [LocalModelCapability] (the honest boundary) and drives the typed
 * [LocalModelState]. `refresh()` re-scans the app-private directory; `loadTest`
 * runs the explicit, user-triggered load/test which reports READY only on a
 * real native load success. No output is ever fabricated.
 */
class LocalModelViewModel(
    private val filesDir: String?,
) : ViewModel() {

    private val capability = LocalModelCapability(
        filesDir = filesDir,
        scan = { LocalModelInventory.scan(it) },
        native = RealNativeAdapter,
        loadModel = { LocalModelNative.loadModel(it) },
    )

    private val _ui = MutableStateFlow(LocalModelUiState())
    val ui: StateFlow<LocalModelUiState> = _ui.asStateFlow()

    /** Perform a fast, disk-only inventory scan and surface its state. */
    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(refreshing = true) }
            val state = capability.refresh()
            _ui.update { it.copy(refreshing = false, state = state) }
        }
    }

    /**
     * Explicit load/test action for a specific model id. Runs the real
     * validated load; READY is reported only when the native adapter returns
     * success. Errors surface as typed UNSUPPORTED / ERROR.
     */
    fun loadTest(modelId: String) {
        viewModelScope.launch {
            _ui.update { it.copy(loadTestBusy = true) }
            val state = capability.loadAndTest(modelId)
            _ui.update { it.copy(loadTestBusy = false, state = state) }
        }
    }

    /** After a successful load, unload native state (no-op in the stub). */
    fun unload() {
        LocalModelNative.unload()
        _ui.update { it.copy(state = it.state.copy(status = LocalModelStatus.NOT_CONFIGURED, message = "已卸载本地模型")) }
    }

    class Factory(private val filesDir: String?) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LocalModelViewModel(filesDir) as T
        }
    }

    companion object {
        fun filesDir(context: android.content.Context): String? =
            runCatching { context.applicationContext.filesDir?.absolutePath }.getOrNull()
                ?: File(context.filesDir, "").absolutePath.ifEmpty { null }
    }
}

/** UI snapshot for the local-model panel. */
data class LocalModelUiState(
    val refreshing: Boolean = false,
    val loadTestBusy: Boolean = false,
    val state: LocalModelState = LocalModelState(),
)

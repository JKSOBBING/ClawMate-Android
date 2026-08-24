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

    // Loads/refreshes/unloads all go through the shared [LocalModelRegistry] so
    // the chat tab and this Settings panel observe the SAME loaded state. The
    // native load is global (LocalModelNative object); the registry is the
    // single source of truth for the Kotlin-side LocalModelState.
    private val _ui = MutableStateFlow(LocalModelUiState())
    val ui: StateFlow<LocalModelUiState> = _ui.asStateFlow()

    init {
        // Reflect the shared state on creation so an already-loaded model shows
        // READY here even if it was loaded in a previous panel instance.
        _ui.update { it.copy(state = LocalModelRegistry.state.value) }
    }

    /** Perform a fast, disk-only inventory scan and surface its state. */
    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(refreshing = true) }
            LocalModelRegistry.refresh(filesDir)
            _ui.update { it.copy(refreshing = false, state = LocalModelRegistry.state.value) }
        }
    }

    /**
     * Explicit load/test action for a specific model id. Runs the real
     * validated load via the shared registry; READY is reported only when the
     * native adapter returns success. Errors surface as typed UNSUPPORTED /
     * ERROR. The resulting state is shared with the chat tab.
     */
    fun loadTest(modelId: String) {
        viewModelScope.launch {
            _ui.update { it.copy(loadTestBusy = true) }
            LocalModelRegistry.loadAndTest(filesDir, modelId)
            _ui.update { it.copy(loadTestBusy = false, state = LocalModelRegistry.state.value) }
        }
    }

    /** After a successful load, unload native state (no-op in the stub). */
    fun unload() {
        LocalModelRegistry.unload()
        _ui.update { it.copy(state = LocalModelRegistry.state.value) }
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

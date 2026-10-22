package com.clawmate.app.home.comfort

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clawmate.app.datamanager.ConsolidationModelConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class HomeComfortTopicsUiState(val loading: Boolean = false, val generated: Boolean = false,
    val topics: List<HomeComfortTopic> = emptyList(), val error: String? = null)

class HomeComfortTopicsViewModel(
    private val configProvider: () -> ConsolidationModelConfig?,
    private val loadContext: suspend () -> HomeComfortTopicContext,
    private val generate: suspend (ConsolidationModelConfig, HomeComfortTopicContext) -> List<HomeComfortTopic>,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeComfortTopicsUiState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun refresh() {
        if (state.value.loading) return
        val config = configProvider()
        if (config == null) {
            mutableState.update { it.copy(error = "请先在设置中填写并保存云端模型地址、密钥和模型名称。") }
            return
        }
        mutableState.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            try {
                val context = loadContext()
                currentCoroutineContext().ensureActive()
                val topics = if (context.evidence.isEmpty()) emptyList() else generate(config, context)
                currentCoroutineContext().ensureActive()
                check(topics.size <= 3 && topics.all { it.evidence.isNotEmpty() })
                mutableState.value = HomeComfortTopicsUiState(generated = true, topics = topics)
            } catch (e: CancellationException) {
                mutableState.update { it.copy(loading = false, error = "已取消生成，原有话题保留。") }
                throw e
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, error = "话题生成失败，请检查模型设置和网络后重试；原有话题保留。") }
            } finally { job = null }
        }
    }

    fun cancel() { job?.cancel() }
}

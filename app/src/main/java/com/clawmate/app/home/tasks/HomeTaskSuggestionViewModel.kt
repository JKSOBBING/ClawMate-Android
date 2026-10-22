package com.clawmate.app.home.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clawmate.app.chat.cloud.ChatException
import com.clawmate.app.datamanager.ConsolidationModelConfig
import com.clawmate.app.home.domain.HomeDomainViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class HomeTaskSuggestionUiState(
    val loading: Boolean = false,
    val tasks: List<HomeSuggestedTask> = emptyList(),
    val error: String? = null,
    val selected: HomeSuggestedTask? = null,
)

/** Only the explicit generate action reads context/calls a model; errors preserve the previous batch. */
class HomeTaskSuggestionViewModel(
    private val configProvider: () -> ConsolidationModelConfig?,
    private val loadContext: suspend (List<HomeDomainViewModel>, List<String>) -> HomeTaskContext,
    private val generateTasks: suspend (ConsolidationModelConfig, HomeTaskContext) -> List<HomeSuggestedTask>,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeTaskSuggestionUiState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun generate(domains: List<HomeDomainViewModel>) {
        if (state.value.loading) return
        val config = configProvider()
        if (config == null) {
            mutableState.update { it.copy(error = "请先在设置中填写并保存云端模型地址、密钥和模型名称。") }
            return
        }
        val previous = state.value.tasks.map { it.task }
        mutableState.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            try {
                val context = loadContext(domains, previous)
                currentCoroutineContext().ensureActive()
                if (context.evidence.isEmpty()) {
                    mutableState.update { it.copy(loading = false, error = "暂无已整理的足迹或画像，请先在设置的数据采集中同步并刷新记忆索引。") }
                    return@launch
                }
                val tasks = generateTasks(config, context)
                currentCoroutineContext().ensureActive()
                check(tasks.size == 3 && tasks.all { it.evidence.isNotEmpty() })
                mutableState.update { it.copy(loading = false, tasks = tasks, error = null, selected = null) }
            } catch (e: CancellationException) {
                mutableState.update { it.copy(loading = false, error = "已取消生成，原有建议保留。") }
                throw e
            } catch (e: Exception) {
                val message = if (e is ChatException) "云端请求失败${e.httpCode?.let { "（HTTP $it）" }.orEmpty()}，请检查模型设置和网络后重试。"
                    else "未能生成3条不同且有真实依据的任务建议，请重试或更换模型。"
                mutableState.update { it.copy(loading = false, error = message) }
            } finally { job = null }
        }
    }

    fun cancel() { job?.cancel() }
    fun select(id: String) { mutableState.update { it.copy(selected = it.tasks.firstOrNull { task -> task.id == id }) } }
    fun closeDetail() { mutableState.update { it.copy(selected = null) } }
}

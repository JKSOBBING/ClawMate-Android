package com.clawmate.app.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clawmate.app.chat.cloud.ChatException
import com.clawmate.app.datamanager.ConsolidationModelConfig
import com.clawmate.app.datamanager.chatmemory.*
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMemoryConsent(val service: String, val model: String)
data class ChatMemoryUiState(
    val syncing: Boolean = false,
    val indexing: Boolean = false,
    val consent: ChatMemoryConsent? = null,
    val progress: ChatMemoryProgress? = null,
    val notice: String? = null,
    val error: String? = null,
) { val busy: Boolean get() = syncing || indexing }

/** No work at construction. Saved configuration and explicit per-run consent gate all chat access. */
class ChatMemoryViewModel(
    private val configProvider: () -> ConsolidationModelConfig?,
    private val synchronize: suspend (ConsolidationModelConfig, (ChatMemoryProgress) -> Unit) -> ChatMemorySyncSummary,
    private val refreshLocalIndex: suspend () -> Int,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ChatMemoryUiState())
    val state = mutableState.asStateFlow()
    private var pendingConfig: ConsolidationModelConfig? = null
    private var job: Job? = null

    fun requestSync() {
        if (state.value.busy || state.value.consent != null) return
        val config = configProvider()
        if (config == null) {
            mutableState.update { it.copy(error = "请先在“模型与智能体”中填写并保存云端地址、密钥和模型名称。", notice = null) }
            return
        }
        pendingConfig = config
        val service = runCatching { URI(config.baseUrl).let { uri ->
            (uri.host ?: "已保存的模型服务") + if (uri.port >= 0) ":${uri.port}" else ""
        } }.getOrDefault("已保存的模型服务")
        mutableState.update { it.copy(consent = ChatMemoryConsent(service, config.modelName.take(80)), error = null, notice = null) }
    }

    fun dismissConsent() {
        pendingConfig = null
        mutableState.update { it.copy(consent = null) }
    }

    fun confirmSync() {
        val config = pendingConfig ?: return
        if (state.value.busy || state.value.consent == null) return
        pendingConfig = null
        if (config != configProvider()) {
            mutableState.update { it.copy(consent = null, error = "云端设置已改变，请重新确认同步目标。") }
            return
        }
        mutableState.update { it.copy(syncing = true, consent = null, progress = null, error = null, notice = null) }
        job = viewModelScope.launch {
            try {
                val result = synchronize(config) { progress -> mutableState.update { it.copy(progress = progress) } }
                val message = if (result.processedParts == 0) "没有待处理的聊天片段，已保存记忆的日志已检查。" else
                    "已处理 ${result.processedParts} 段聊天，新增 ${result.factCount} 条事实，保存到 ${result.exportedFiles} 个日志文件。"
                mutableState.update { it.copy(syncing = false, notice = "$message 请刷新本地记忆索引，让聊天和汇总读取这些记忆。") }
            } catch (e: CancellationException) {
                mutableState.update { it.copy(syncing = false, notice = "同步已取消；已保存的部分会在下次继续，无需重新提取。") }
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(syncing = false, error = syncError(e)) }
            } finally { job = null }
        }
    }

    fun cancelSync() { if (state.value.syncing) job?.cancel() }

    fun refreshIndex() {
        if (state.value.busy || state.value.consent != null) return
        mutableState.update { it.copy(indexing = true, error = null, notice = null) }
        job = viewModelScope.launch {
            try {
                val events = refreshLocalIndex()
                mutableState.update { it.copy(indexing = false, notice = "本地索引已刷新：$events 条事件。汇总页点击同步后可查看，聊天可查询已索引的记忆。") }
            } catch (e: CancellationException) {
                mutableState.update { it.copy(indexing = false, notice = "索引刷新已取消，请稍后重试。") }
                throw e
            } catch (_: Exception) {
                mutableState.update { it.copy(indexing = false, error = "索引刷新失败，请检查本地日志和可用存储空间后重试。聊天同步进度仍保留。") }
            } finally { job = null }
        }
    }

    private fun syncError(error: Exception): String = when (error) {
        is ChatMemoryResponseException -> "模型返回的聊天提取结果不完整，请重试或更换模型。已保存的部分会保留。"
        is ChatException -> "云端请求失败${error.httpCode?.let { "（HTTP $it）" }.orEmpty()}，请检查模型设置、服务权限和网络后重试。已保存的部分会保留。"
        is IOException -> if (error.message?.startsWith("聊天同步记录无法读取") == true)
            "聊天同步记录无法读取。请保留原文件并检查存储；为避免重复导入，本次已停止。" else
            "网络或本地存储异常，请检查连接与可用空间后重试。已保存的部分会保留。"
        else -> "聊天同步失败，请检查设置后重试。已保存的部分会保留。"
    }
}

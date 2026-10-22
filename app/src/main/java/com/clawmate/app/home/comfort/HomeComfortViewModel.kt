package com.clawmate.app.home.comfort

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clawmate.app.chat.cloud.ChatException
import com.clawmate.app.datamanager.ConsolidationModelConfig
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class HomeComfortUiState(
    val open: Boolean = false,
    val input: String = "",
    val messages: List<HomeComfortMessage> = emptyList(),
    val sending: Boolean = false,
    val error: String? = null,
    val topic: HomeComfortTopic? = null,
)

/** Session lifetime matches the source HomeComfortChatStore, independent of ordinary chat history. */
class HomeComfortViewModel(
    private val configProvider: () -> ConsolidationModelConfig?,
    private val request: suspend (ConsolidationModelConfig, List<HomeComfortMessage>, HomeComfortTopic?) -> HomeComfortReply,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeComfortUiState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var pending: Job? = null
    private val sessions = linkedMapOf<String, HomeComfortUiState>()
    private var topicBatch: String? = null

    fun open(topic: HomeComfortTopic? = null) {
        val target = topic?.id ?: "casual"
        val old = state.value
        val oldKey = old.topic?.id ?: "casual"
        if (old.open && oldKey == target) return
        generation++
        pending?.cancel()
        pending = null
        sessions[oldKey] = old.copy(open = false, sending = false,
            error = if (old.sending) "回复已停止，可以重试或继续说。" else old.error)
        if (topic != null && topic.batchId != topicBatch) {
            sessions.keys.filter { it != "casual" }.toList().forEach(sessions::remove)
            topicBatch = topic.batchId
        }
        mutableState.value = (sessions[target] ?: initial(topic)).copy(open = true, topic = topic)
    }
    fun close() { pending?.cancel(); mutableState.update { it.copy(open = false) } }
    fun input(text: String) { if (text.length <= HomeComfortPolicy.INPUT_LIMIT) mutableState.update { it.copy(input = text) } }

    fun restart() {
        generation++
        pending?.cancel()
        pending = null
        mutableState.value = initial(state.value.topic).copy(open = state.value.open)
    }

    fun stop() { pending?.cancel() }

    fun send() {
        val state = state.value
        val text = state.input.trim()
        if (!state.open || state.sending || text.isEmpty()) return
        val config = configuration() ?: return
        val message = HomeComfortMessage(UUID.randomUUID().toString(), HomeComfortRole.USER, text)
        dispatch(config, state.messages + message)
    }

    fun retry() {
        if (!state.value.open || state.value.sending || state.value.messages.lastOrNull()?.role != HomeComfortRole.USER) return
        val config = configuration() ?: return
        dispatch(config, state.value.messages, clearInput = false)
    }

    private fun configuration(): ConsolidationModelConfig? = configProvider().also { config ->
        if (config == null) mutableState.update { it.copy(error = "请先在设置中填写并保存云端模型地址、密钥和模型名称。") }
    }

    private fun dispatch(config: ConsolidationModelConfig, history: List<HomeComfortMessage>, clearInput: Boolean = true) {
        val token = ++generation
        val topic = state.value.topic
        mutableState.update { it.copy(messages = history, input = if (clearInput) "" else it.input, sending = true, error = null) }
        pending = viewModelScope.launch {
            try {
                val reply = request(config, history, topic)
                currentCoroutineContext().ensureActive()
                if (generation == token) {
                    require(reply.text.isNotBlank() && reply.text.length <= HomeComfortPolicy.REPLY_LIMIT)
                    val message = HomeComfortMessage(UUID.randomUUID().toString(), HomeComfortRole.ASSISTANT, reply.text, reply.truncated)
                    mutableState.update { it.copy(messages = history + message, sending = false, error = null) }
                }
            } catch (e: CancellationException) {
                if (generation == token) mutableState.update { it.copy(sending = false, error = "回复已停止，可以重试或继续说。") }
                throw e
            } catch (e: Exception) {
                if (generation == token) {
                    val error = if (e is ChatException) "云端回复失败${e.httpCode?.let { "（HTTP $it）" }.orEmpty()}，请检查模型设置和网络后重试。"
                        else "未能生成完整回复，请重试。"
                    mutableState.update { it.copy(sending = false, error = error) }
                }
            } finally { if (generation == token) pending = null }
        }
    }

    private fun initial(topic: HomeComfortTopic?) = HomeComfortUiState(topic = topic, messages = topic?.let {
        listOf(HomeComfortMessage(UUID.randomUUID().toString(), HomeComfortRole.ASSISTANT, it.opening))
    }.orEmpty())
}

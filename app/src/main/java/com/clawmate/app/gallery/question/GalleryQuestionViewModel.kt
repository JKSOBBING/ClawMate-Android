package com.clawmate.app.gallery.question

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clawmate.app.chat.cloud.ChatException
import com.clawmate.app.gallery.model.GallerySearchHit
import com.clawmate.app.gallery.vision.GalleryVisionConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class GalleryQuestionConsent(val service: String, val model: String, val input: GalleryQuestionInput)
data class GalleryQuestionState(val question: String = "", val busy: Boolean = false,
    val consent: GalleryQuestionConsent? = null, val answer: GalleryQuestionAnswer? = null,
    val answeredQuestion: String = "", val error: String? = null, val notice: String? = null)

class GalleryQuestionViewModel(
    private val loadConfig: suspend () -> GalleryVisionConfig?,
    private val search: suspend (String) -> List<GallerySearchHit>,
    private val ask: suspend (GalleryVisionConfig, GalleryQuestionInput) -> GalleryQuestionAnswer,
) : ViewModel() {
    private val mutableState = MutableStateFlow(GalleryQuestionState())
    val state = mutableState.asStateFlow()
    private var pending: Pair<GalleryVisionConfig, GalleryQuestionInput>? = null
    private var version = 0L
    private var job: Job? = null

    fun updateQuestion(value: String) {
        if (state.value.busy) return
        dismiss()
        mutableState.update { it.copy(question = value, error = null, notice = null) }
    }

    fun request() {
        if (state.value.busy || state.value.consent != null) return
        val question = state.value.question.trim()
        if (question.isEmpty() || question.length > GalleryQuestionProtocol.MAX_QUESTION) {
            mutableState.update { it.copy(error = "请输入1至400个字符的问题。") }; return
        }
        val token = ++version
        mutableState.update { it.copy(busy = true, error = null, notice = null) }
        job = viewModelScope.launch {
            try {
                val config = loadConfig()
                currentCoroutineContext().ensureActive()
                if (token != version) return@launch
                if (config == null) {
                    mutableState.update { it.copy(busy = false, error = "请先在设置的数据采集中保存图库 API 地址、密钥和模型。") }
                    return@launch
                }
                val input = GalleryQuestionProtocol.prepare(question, search(question))
                currentCoroutineContext().ensureActive()
                if (token != version) return@launch
                if (input.evidence.isEmpty()) {
                    mutableState.update { it.copy(busy = false, notice = "没有找到明确相关的本机图库记录；未发送云端请求。") }
                } else {
                    pending = config to input
                    mutableState.update { it.copy(busy = false, consent = GalleryQuestionConsent(config.service, config.model, input)) }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (token == version) mutableState.update { it.copy(busy = false, error = "无法读取图库记录或设置，请重试。") } }
            finally { if (token == version) job = null }
        }
    }

    fun dismiss() { pending = null; mutableState.update { it.copy(consent = null) } }

    fun confirm() {
        val (config, input) = pending ?: return
        if (state.value.busy || state.value.consent == null) return
        dismiss()
        val token = ++version
        mutableState.update { it.copy(busy = true, error = null, notice = null) }
        job = viewModelScope.launch {
            try {
                check(config == loadConfig()) { "图库配置已改变" }
                val answer = ask(config, input)
                currentCoroutineContext().ensureActive()
                if (token == version) mutableState.update { it.copy(busy = false, answer = answer, answeredQuestion = input.question) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = if (e is ChatException) "图库问答请求失败${e.httpCode?.let { "（HTTP $it）" }.orEmpty()}，请检查网络、模型权限和配置。"
                    else "图库配置已变化或模型回答缺少有效依据，请重新确认后重试。"
                if (token == version) mutableState.update { it.copy(busy = false, error = message) }
            } finally { if (token == version) job = null }
        }
    }

    fun stop() {
        version++; job?.cancel(); job = null; pending = null
        mutableState.update { it.copy(busy = false, consent = null, notice = "已停止问答；上次回答仍按原问题显示。") }
    }

    fun clear() { stop(); mutableState.value = GalleryQuestionState() }
}

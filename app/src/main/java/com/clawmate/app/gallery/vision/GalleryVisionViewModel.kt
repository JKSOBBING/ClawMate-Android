package com.clawmate.app.gallery.vision

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clawmate.app.chat.cloud.ChatException
import com.clawmate.app.gallery.model.GalleryPhotoItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class GalleryVisionConsent(val itemId: String, val title: String, val service: String, val model: String)
data class GalleryVisionUiState(val configured: Boolean = false, val itemId: String? = null, val busy: Boolean = false,
    val consent: GalleryVisionConsent? = null, val preview: GalleryVisionAnalysis? = null, val saved: Boolean = false,
    val notice: String? = null, val error: String? = null)

class GalleryVisionViewModel(
    private val loadConfig: suspend () -> GalleryVisionConfig?,
    private val analyze: suspend (GalleryVisionConfig, GalleryPhotoItem) -> GalleryVisionAnalysis,
    private val save: suspend (GalleryPhotoItem, GalleryVisionAnalysis) -> GalleryVisionSaveResult,
) : ViewModel() {
    private val mutableState = MutableStateFlow(GalleryVisionUiState())
    val state = mutableState.asStateFlow()
    private var item: GalleryPhotoItem? = null
    private var pendingConfig: GalleryVisionConfig? = null
    private var version = 0L
    private var job: Job? = null

    fun refreshConfiguration() {
        viewModelScope.launch {
            val configured = runCatching { loadConfig() != null }.getOrDefault(false)
            mutableState.update { it.copy(configured = configured) }
        }
    }

    fun request(item: GalleryPhotoItem) {
        if (state.value.busy || state.value.consent != null) return
        val token = ++version
        val previous = if (this.item?.id == item.id && this.item?.uri == item.uri) state.value else GalleryVisionUiState(configured = state.value.configured)
        this.item = item
        mutableState.value = previous.copy(itemId = item.id, busy = true, error = null)
        job = viewModelScope.launch {
            try {
                val config = loadConfig()
                currentCoroutineContext().ensureActive()
                if (token != version) return@launch
                pendingConfig = config
                mutableState.update { if (config == null) it.copy(configured = false, busy = false,
                    error = "请先在设置的数据采集中填写并保存图库 API 地址、密钥和支持图片输入的模型。") else
                    it.copy(configured = true, busy = false, consent = GalleryVisionConsent(item.id, item.title, config.service, config.model)) }
            } catch (e: CancellationException) {
                if (token == version) mutableState.update { it.copy(busy = false, notice = "已停止准备分析。") }
                throw e
            }
            catch (_: Exception) { if (token == version) mutableState.update { it.copy(busy = false, error = "无法读取图库设置，请重试。") } }
            finally { if (token == version) job = null }
        }
    }

    fun dismiss() { pendingConfig = null; mutableState.update { it.copy(consent = null) } }

    fun confirm() {
        val config = pendingConfig ?: return
        val selected = item ?: return
        if (state.value.busy || state.value.consent == null) return
        pendingConfig = null
        val token = ++version
        mutableState.update { it.copy(busy = true, consent = null, error = null, notice = null) }
        job = viewModelScope.launch {
            try {
                check(config == loadConfig()) { "图库设置已改变，请重新确认" }
                val result = analyze(config, selected)
                currentCoroutineContext().ensureActive()
                if (token == version) mutableState.update { it.copy(busy = false, preview = result, saved = false,
                    notice = "分析已完成，请核对后保存。", error = null) }
            } catch (e: CancellationException) {
                if (token == version) mutableState.update { it.copy(busy = false, notice = "分析已停止，未保存新的分析结果。") }
                throw e
            } catch (e: Exception) {
                val message = if (e is ChatException) "云端图片分析失败${e.httpCode?.let { "（HTTP $it）" }.orEmpty()}。请检查服务权限、网络及模型是否支持图片输入。"
                    else "图片读取、配置或模型结果无效，请重新选图并确认图库设置后重试。"
                if (token == version) mutableState.update { it.copy(busy = false, error = message) }
            } finally { if (token == version) job = null }
        }
    }

    fun savePreview() {
        val selected = item ?: return
        val preview = state.value.preview ?: return
        if (state.value.busy || selected.id != state.value.itemId) return
        val token = ++version
        mutableState.update { it.copy(busy = true, error = null, notice = null) }
        job = viewModelScope.launch {
            try {
                val result = save(selected, preview)
                currentCoroutineContext().ensureActive()
                check(result.saved) { "图片已变化或不存在" }
                if (token == version) mutableState.update { it.copy(busy = false, saved = true,
                    notice = if (result.groupsRefreshed) "分析结果已保存，图库分组已刷新。" else "分析结果已保存，分组暂未刷新；可再次保存以重试。") }
            } catch (e: CancellationException) {
                if (token == version) mutableState.update { it.copy(busy = false, notice = "保存已停止，请检查当前图片结果。") }
                throw e
            } catch (_: Exception) {
                if (token == version) mutableState.update { it.copy(busy = false, error = "未能保存分析结果，图片可能已变化或被删除，请重新分析后重试。") }
            } finally { if (token == version) job = null }
        }
    }

    fun stop() {
        version++
        job?.cancel()
        job = null
        pendingConfig = null
        mutableState.update { it.copy(busy = false, consent = null, notice = "已停止等待图片处理结果，请核对当前记录。") }
    }
}

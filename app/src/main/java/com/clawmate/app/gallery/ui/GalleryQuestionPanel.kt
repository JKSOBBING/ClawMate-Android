package com.clawmate.app.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.clawmate.app.gallery.model.GallerySearchHit
import com.clawmate.app.ui.*
import com.clawmate.app.ui.theme.HarmonyTheme
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.clawmate.app.gallery.data.GalleryRepository
import com.clawmate.app.gallery.question.*
import com.clawmate.app.gallery.vision.GalleryVisionConfig
import com.clawmate.app.settings.SettingsComponent

internal fun galleryQuestionFactory(context: Context, repository: GalleryRepository): ViewModelProvider.Factory {
    val app = context.applicationContext
    return object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = GalleryQuestionViewModel(
            { GalleryVisionConfig.fromSettings(SettingsComponent.repository(app).snapshot()) },
            { query -> repository.search(query) },
            { config, input -> GalleryQuestionClient.fromConfig(config).answer(input) },
        ) as T
    }
}

@Composable
internal fun GalleryQuestionPanel(vm: GalleryQuestionViewModel, onOpenItem: (String) -> Unit, onOpenSettings: () -> Unit,
    onSearch: (String) -> Unit = {}, searchMessage: String = "输入问题后检索图库记录", searchHits: List<GallerySearchHit> = emptyList(),
    onClearSearch: () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    Column(Modifier.padding(top = 14.dp).fillMaxWidth(.9f).background(HarmonyTheme.surface, RoundedCornerShape(14.dp)).padding(16.dp)) {
        Text("图库问答", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
        Text(searchMessage, fontSize = 12.sp, color = HarmonyTheme.text("#7F8592"), modifier = Modifier.padding(top = 3.dp))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(state.question, vm::updateQuestion, Modifier.weight(1f).height(42.dp)
                .background(HarmonyTheme.background("#F2F5FC"), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                enabled = !state.busy, singleLine = true, textStyle = TextStyle(fontSize = 12.sp, color = HarmonyTheme.primaryText),
                cursorBrush = SolidColor(HarmonyTheme.text("#5968FF")), decorationBox = { field ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        if (state.question.isEmpty()) Text("例如：找一下外卖订单、停车缴费、会议安排", fontSize = 12.sp,
                            color = HarmonyTheme.mutedText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        field()
                    }
                })
            HarmonyButton("检索", { onSearch(state.question) }, Modifier.padding(start = 8.dp).width(64.dp),
                height = 42.dp, radius = 12.dp, color = HarmonyTheme.text("#FFFFFF"), background = HarmonyTheme.background("#5B68FF"),
                enabled = !state.busy, horizontalPadding = 0.dp)
            HarmonyButton(if (state.busy) "生成中" else "问答", vm::request, Modifier.padding(start = 8.dp).width(70.dp),
                height = 42.dp, radius = 12.dp, color = HarmonyTheme.text("#FFFFFF"), background = HarmonyTheme.background("#2F5BEA"),
                enabled = !state.busy, horizontalPadding = 0.dp)
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HarmonyButton("清空", { vm.clear(); onClearSearch() }, height = 30.dp, color = HarmonyTheme.secondaryText,
                background = androidx.compose.ui.graphics.Color.Transparent)
            if (state.busy) HarmonyButton("停止问答", vm::stop, height = 30.dp)
        }
        if (state.busy) Text("正在基于检索结果生成回答...", fontSize = 12.sp, color = HarmonyTheme.text("#667085"),
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).background(HarmonyTheme.background("#F7F9FC"), RoundedCornerShape(10.dp)).padding(10.dp))
        state.error?.let {
            Text("问答失败：$it", fontSize = 12.sp, color = HarmonyTheme.text("#B42318"), modifier = Modifier.padding(top = 8.dp))
            HarmonyButton("打开设置", onOpenSettings, Modifier.padding(top = 8.dp))
        }
        state.notice?.let { Text(it, fontSize = 12.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(top = 8.dp)) }
        state.answer?.let { answer ->
            Column(Modifier.padding(top = 10.dp).fillMaxWidth().background(HarmonyTheme.background("#F2F6FF"), RoundedCornerShape(12.dp)).padding(12.dp)) {
                HarmonyAiBadge(compact = true)
                Text("对应问题：${state.answeredQuestion}", fontSize = 11.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(top = 6.dp))
                SelectionContainer { Text(answer.answer, fontSize = 13.sp, lineHeight = 20.sp, color = HarmonyTheme.text("#303746"), modifier = Modifier.padding(top = 7.dp)) }
                answer.matches.forEach { evidence ->
                    Text("${evidence.date} · ${evidence.title}\n${evidence.excerpt}", fontSize = 11.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(top = 6.dp))
                    HarmonyButton("查看图片：${evidence.title}", { onOpenItem(evidence.itemId) }, Modifier.padding(top = 4.dp))
                }
            }
        }
        searchHits.forEach { hit ->
            Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(HarmonyTheme.background("#F8FAFE"))
                .clickable { onOpenItem(hit.item.id) }.padding(10.dp)) {
                AsyncImage(hit.item.uri, hit.item.title, Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(hit.item.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(hit.item.summary, fontSize = 11.sp, color = HarmonyTheme.text("#596273"), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                    Text("${hit.reason} · ${hit.item.date}", fontSize = 10.sp, color = HarmonyTheme.text("#4B5F9F"), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
    }
    state.consent?.let { consent ->
        AlertDialog(onDismissRequest = vm::dismiss, title = { Text("发送候选记录进行问答？") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("服务：${consent.service}\n模型：${consent.model}\n问题：${consent.input.question}")
                Text("将发送以下 ${consent.input.evidence.size} 张图片记录的日期、标题、分类、摘要、细节、标签和截短 OCR 文字。" +
                    "不发送原图、文件路径或原始 EXIF/GPS 元数据。")
                consent.input.evidence.forEach { Text("${it.date} · ${it.title}\n${it.excerpt}", style = MaterialTheme.typography.bodySmall) }
            } },
            confirmButton = { TextButton(vm::confirm) { Text("确认发送记录") } },
            dismissButton = { TextButton(vm::dismiss) { Text("取消") } })
    }
}

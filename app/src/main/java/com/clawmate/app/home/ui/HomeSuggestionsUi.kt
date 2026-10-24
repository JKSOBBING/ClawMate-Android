package com.clawmate.app.home.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.clawmate.app.home.domain.HomeDomainViewModel
import com.clawmate.app.home.tasks.*
import com.clawmate.app.ui.*
import com.clawmate.app.ui.theme.HarmonyTheme

internal fun taskCategoryIcon(category: String) = when (category) {
    "food" -> 0; "shopping" -> 1; "news" -> 2; "social" -> 3; "travel" -> 4; "work" -> 5; else -> 6
}
private fun taskAccent(category: String) = when (category) {
    "food" -> "#D88855"; "shopping" -> "#E87899"; "news" -> "#578FE2"; "social" -> "#45B990"
    "travel" -> "#2EA7B8"; "work" -> "#846BEB"; else -> "#6D78E7"
}
private fun taskTint(category: String) = when (category) {
    "food" -> "#FFF1E9"; "shopping" -> "#FFF0F4"; "news" -> "#EDF4FF"; "social" -> "#EAF8F3"
    "travel" -> "#EAF8FA"; "work" -> "#F1EEFF"; else -> "#EEF0FF"
}

@Composable
internal fun HomeTaskSuggestionsCard(vm: HomeTaskSuggestionViewModel, domains: List<HomeDomainViewModel>,
    onOpenTasks: () -> Unit, onOpenSettings: () -> Unit,
) {
    val state by vm.state.collectAsState()
    var execution by remember { mutableStateOf<HomeSuggestedTask?>(null) }
    Column(Modifier.fillMaxWidth(.92f).padding(top = 30.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("猜你喜欢", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, modifier = Modifier.weight(1f))
            if (state.tasks.isNotEmpty()) { AiBadge(); Spacer(Modifier.width(10.dp)) }
            HarmonyButton(when { state.loading -> "生成中"; state.tasks.isNotEmpty() -> "换一批"; state.error != null -> "重试"; else -> "生成推荐" },
                { vm.generate(domains) }, enabled = !state.loading)
        }
        if (state.tasks.isEmpty()) {
            Box(Modifier.padding(top = 10.dp)) {
                HomeEmptyHint(when { state.loading -> "正在找你喜欢的事～"; state.error != null -> "灵感还没准备好，稍后再试～"; else -> "让我找找你喜欢的事吧～" },
                    onOpenTasks, loading = state.loading)
            }
        } else Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            state.tasks.take(3).forEachIndexed { index, task ->
                Column(Modifier.weight(1f).height(154.dp).padding(horizontal = 9.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.weight(1f).fillMaxWidth().clickable { vm.select(task.id) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        HomeCategoryIcon(taskCategoryIcon(task.category), 50.dp)
                        Text(task.title, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText,
                            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
                        Text(task.reason, fontSize = 10.sp, color = HarmonyTheme.mutedText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(HomeTaskSuggestions.categoryLabel(task.category), fontSize = 10.sp, fontWeight = FontWeight.Medium,
                            color = HarmonyTheme.text(taskAccent(task.category)), modifier = Modifier.weight(1f), maxLines = 1)
                        HarmonyButton("执行", { execution = task }, Modifier.width(58.dp), height = 24.dp, radius = 12.dp, fontSize = 11.sp,
                            color = HarmonyTheme.text(taskAccent(task.category)), background = HarmonyTheme.background(taskTint(task.category)), horizontalPadding = 0.dp)
                    }
                }
                if (index < state.tasks.take(3).lastIndex) VerticalDivider(Modifier.height(104.dp), color = HarmonyTheme.border("#E7EAF2"))
            }
        }
        state.error?.let { error ->
            Text(error, fontSize = 12.sp, color = HarmonyTheme.text("#B42318"))
            Row {
                HarmonyButton("打开设置", onOpenSettings, background = Color.Transparent)
                if (state.loading) HarmonyButton("取消生成", vm::cancel, background = Color.Transparent)
            }
        }
    }
    state.selected?.let { task -> HomeTaskSuggestionDetail(task, vm::closeDetail, { execution = task }) }
    execution?.let { task -> HomeTaskExecutionSheet(task, onClose = { execution = null }, onOpenTasks = {
        execution = null; vm.closeDetail(); onOpenTasks()
    }) }
}

@Composable
private fun HomeTaskSuggestionDetail(task: HomeSuggestedTask, onClose: () -> Unit, onExecute: () -> Unit) {
    HarmonyFullScreen(onClose) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(66.dp).padding(start = 16.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                HarmonyButton("‹", onClose, Modifier.size(42.dp), height = 42.dp, radius = 21.dp, fontSize = 30.sp,
                    color = HarmonyTheme.text("#252B3B"), background = HarmonyTheme.surface, horizontalPadding = 0.dp)
                Text("推荐详情", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText,
                    modifier = Modifier.weight(1f).padding(start = 12.dp))
                AiBadge()
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(HomeTaskSuggestions.categoryLabel(task.category), fontSize = 12.sp, color = HarmonyTheme.text(taskAccent(task.category)),
                        modifier = Modifier.background(HarmonyTheme.background(taskTint(task.category)), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 5.dp))
                    Text("基于近期记录生成", fontSize = 12.sp, color = HarmonyTheme.mutedText, modifier = Modifier.padding(start = 10.dp))
                }
                Text(task.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, modifier = Modifier.padding(top = 14.dp))
                Column(Modifier.fillMaxWidth().padding(top = 18.dp).background(HarmonyTheme.background(taskTint(task.category)), RoundedCornerShape(18.dp)).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(28.dp).background(HarmonyTheme.background(taskAccent(task.category)), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                            Text("?", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Text("为什么猜你会这样做", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, modifier = Modifier.padding(start = 10.dp))
                    }
                    Text(task.reason, fontSize = 14.sp, lineHeight = 21.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(top = 11.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 24.dp)) {
                    Text("溯源依据", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, modifier = Modifier.weight(1f))
                    Text("${task.evidence.size} 条", fontSize = 12.sp, color = HarmonyTheme.mutedText)
                }
                task.evidence.forEach { evidence ->
                    Column(Modifier.fillMaxWidth().padding(top = 10.dp).background(HarmonyTheme.surface, RoundedCornerShape(16.dp))
                        .border(1.dp, HarmonyTheme.border("#E9EDF5"), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 13.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (evidence.kind == "record") "记录" else "画像", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                color = HarmonyTheme.text(taskAccent(task.category)), modifier = Modifier.background(HarmonyTheme.background(taskTint(task.category)), RoundedCornerShape(9.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
                            Text(evidence.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, modifier = Modifier.padding(start = 8.dp))
                        }
                        Text(evidence.summary, fontSize = 13.sp, lineHeight = 19.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(top = 8.dp))
                        Text(evidence.meta, fontSize = 11.sp, color = HarmonyTheme.mutedText, modifier = Modifier.padding(top = 5.dp))
                    }
                }
                Text("将执行的任务", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, modifier = Modifier.padding(top = 24.dp))
                Text(task.task, fontSize = 14.sp, lineHeight = 21.sp, color = HarmonyTheme.secondaryText,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).background(HarmonyTheme.surface, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 15.dp))
                Text("这是 AI 根据本机近期记录做出的推测，不代表你已经确认该意图。", fontSize = 11.sp, lineHeight = 17.sp,
                    color = HarmonyTheme.mutedText, modifier = Modifier.padding(top = 14.dp, bottom = 24.dp))
            }
            Box(Modifier.fillMaxWidth().background(HarmonyTheme.surface).padding(horizontal = 18.dp, vertical = 10.dp)) {
                HarmonyButton("执行这个任务", onExecute, Modifier.fillMaxWidth(), height = 48.dp, radius = 16.dp,
                    fontSize = 15.sp, bold = true, color = Color.White, background = HarmonyTheme.fill(taskAccent(task.category)))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTaskExecutionSheet(task: HomeSuggestedTask, onClose: () -> Unit, onOpenTasks: () -> Unit) {
    var qwen by remember(task.id) { mutableStateOf(false) }
    var phoneDraft by remember(task.id) { mutableStateOf(HomeTaskSuggestions.compact(task.task, 60)) }
    var qwenDraft by remember(task.id) { mutableStateOf(HomeTaskSuggestions.compact(task.qwenTask, 60)) }
    var error by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = HarmonyTheme.surface) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(task.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HarmonyButton("操控手机完成", { qwen = false; error = false }, Modifier.weight(1f), height = 44.dp, radius = 14.dp, fontSize = 14.sp,
                    color = if (!qwen) Color.White else HarmonyTheme.text("#4F46C8"), background = HarmonyTheme.background(if (!qwen) "#6658DC" else "#F0EEFC"))
                HarmonyButton("千问协同完成", { qwen = true; error = false }, Modifier.weight(1f), height = 44.dp, radius = 14.dp, fontSize = 14.sp, enabled = task.qwenSupported,
                    color = if (qwen) Color.White else HarmonyTheme.text(if (task.qwenSupported) "#4F46C8" else "#969DAA"), background = HarmonyTheme.background(if (qwen) "#6658DC" else "#F0EEFC"))
            }
            Text(if (qwen) "手机 Agent 只负责打开千问并发送需求，后续由千问处理。" else "由手机 Agent 在目标应用中操作。", fontSize = 12.sp, lineHeight = 18.sp, color = HarmonyTheme.secondaryText)
            if (!task.qwenSupported) Text(task.qwenReason.ifBlank { "此任务暂不支持千问协同。" }, fontSize = 12.sp, color = HarmonyTheme.mutedText)
            HarmonyTextField(if (qwen) qwenDraft else phoneDraft, { if (qwen) qwenDraft = it else phoneDraft = it }, "一句话，只做一件事（最多60字）",
                Modifier.fillMaxWidth(), height = 90.dp, radius = 14.dp, maxLength = 60)
            if (error) {
                Text("自由任务自动执行和千问协同尚未接入 Android，请使用已支持的流程。", fontSize = 12.sp, color = HarmonyTheme.text("#B42318"))
                HarmonyButton("查看可用流程", onOpenTasks)
            }
            HarmonyButton(if (qwen) "交给千问" else "开始手机执行", { error = true }, Modifier.fillMaxWidth(), height = 46.dp, radius = 15.dp,
                fontSize = 16.sp, color = Color.White, background = HarmonyTheme.fill("#6658DC"), enabled = (if (qwen) qwenDraft else phoneDraft).isNotBlank())
        }
    }
}

package com.clawmate.app.home.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clawmate.app.datamanager.ConsolidationModelConfig
import com.clawmate.app.datamanager.DailyLogLocalIndex
import com.clawmate.app.datamanager.LocalConsolidationTypes.QueryOptions
import com.clawmate.app.home.domain.HomeDomainViewModel
import com.clawmate.app.home.tasks.*
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun HomeTaskSuggestionsSection(domains: List<HomeDomainViewModel>, onOpenTasks: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val factory = remember(context) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeTaskSuggestionViewModel(
            configProvider = { ConsolidationModelConfig.fromEndpointConfig() },
            loadContext = { profile, previous -> withContext(Dispatchers.IO) {
                val index = DailyLogLocalIndex(context.filesDir)
                val events = if (index.hasBuiltIndex()) index.query(QueryOptions(limit = 24)).events else emptyList()
                HomeTaskSuggestions.context(events, profile, previous, LocalDate.now().toString())
            } },
            generateTasks = { config, input -> HomeTaskSuggestionModelClient.fromConfig(config).generate(input) },
        ) as T
    } }
    val vm: HomeTaskSuggestionViewModel = viewModel(key = "home-task-suggestions", factory = factory)
    HomeTaskSuggestionsCard(vm, domains, onOpenTasks, onOpenSettings)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun HomeTaskSuggestionsCard(
    vm: HomeTaskSuggestionViewModel,
    domains: List<HomeDomainViewModel>,
    onOpenTasks: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by vm.state.collectAsState()
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("猜你想做 · AI 任务建议", style = MaterialTheme.typography.titleMedium)
            Text("点击生成会将近期已整理的足迹与画像摘要发送给已保存的云端模型。建议仅供参考，选择后可查看依据与任务描述。",
                style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { vm.generate(domains) }, enabled = !state.loading) {
                    Text(if (state.tasks.isEmpty()) "生成任务建议" else "换一批任务建议")
                }
                TextButton(onClick = onOpenTasks, enabled = !state.loading) { Text("查看可用流程") }
            }
            if (state.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = vm::cancel) { Text("取消生成") }
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onOpenSettings) { Text("打开设置") }
            }
            state.tasks.forEach { task ->
                OutlinedCard(onClick = { vm.select(task.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${HomeTaskSuggestions.categoryLabel(task.category)} · ${task.title}", style = MaterialTheme.typography.titleSmall)
                        Text(task.reason, style = MaterialTheme.typography.bodySmall)
                        Text("${task.evidence.size} 项依据 · 查看详情", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    state.selected?.let { task -> HomeTaskSuggestionDetail(task, vm::closeDetail, onOpenTasks) }
}

@Composable
private fun HomeTaskSuggestionDetail(task: HomeSuggestedTask, onClose: () -> Unit, onOpenTasks: () -> Unit) {
    var draft by remember(task.id) { mutableStateOf(task.task) }
    var error by remember(task.id) { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onClose, title = { Text(task.title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI 根据以下记录给出的任务建议，请先核对。", style = MaterialTheme.typography.bodySmall)
            Text(task.reason)
            task.evidence.forEach { evidence ->
                Text("${evidence.title} · ${evidence.meta}\n${evidence.summary}", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(value = draft, onValueChange = { if (it.length <= 500) draft = it; error = null },
                label = { Text("任务描述（最多60字）") }, modifier = Modifier.fillMaxWidth())
            Text("自由任务自动执行和千问协同尚未接入 Android。可以复制任务描述，或在任务页查看当前支持的流程。",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                try {
                    clipboard.setText(AnnotatedString(HomeTaskSuggestions.executionDescription(draft)))
                    Toast.makeText(context, "已复制任务，尚未执行", Toast.LENGTH_SHORT).show()
                } catch (e: IllegalArgumentException) { error = e.message }
                catch (_: Exception) { error = "复制失败，请重试" }
            }) { Text("复制任务描述") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = { onClose(); onOpenTasks() }) { Text("查看可用流程") } },
        dismissButton = { TextButton(onClick = onClose) { Text("关闭") } },
    )
}

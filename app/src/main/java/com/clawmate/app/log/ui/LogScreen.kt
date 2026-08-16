package com.clawmate.app.log.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clawmate.app.log.LogComponent
import com.clawmate.app.log.domain.InAppLogEntry
import com.clawmate.app.log.domain.LogFilter
import com.clawmate.app.log.domain.LogLevel
import com.clawmate.app.log.domain.LogSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private val PageBackground = Color(0xFFF0F1F4)
private val LogBackground = Color(0xFF0D1117)

/**
 * Phase 14 — Compose in-app Log viewer.
 *
 * Port of the HarmonyOS `entry/src/main/ets/pages/LogView.ets`: switch log
 * sources (application runtime / cloud Agent / gallery / workflow), filter by
 * severity and free-text, auto-refresh, auto-scroll, copy the visible text, and
 * clear the current source. The empty copy is honest: when the buffer has no
 * record, an informative "(no logs yet)" state is shown instead of a fabricated
 * one.
 *
 * @param onBack returns to the Settings center.
 */
@Composable
fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: LogViewModel = viewModel(
        factory = LogViewModelFactory(repository = LogComponent.repository()),
    )
    val filter by vm.filter.collectAsState()
    val viewer by vm.viewer.collectAsState()
    val refreshedOnce by vm.refreshedOnce.collectAsState()

    // Load current buffer contents on first composition so the viewer is never
    // blank until the user taps Refresh.
    LaunchedEffect(Unit) { vm.refresh() }

    Surface(modifier = Modifier.fillMaxSize(), color = PageBackground) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            HeaderBar(onBack = onBack, source = filter.source)
            SourceTabs(source = filter.source, onSource = vm::setSource)
            FilterRow(filter = filter, onMinLevel = vm::setMinLevelOrNull, onQuery = vm::setQuery)
            ActionRow(
                autoRefresh = filter.autoRefresh,
                autoScroll = filter.autoScroll,
                onToggleAutoRefresh = { vm.setAutoRefresh(!filter.autoRefresh) },
                onToggleAutoScroll = { vm.setAutoScroll(!filter.autoScroll) },
                onRefresh = vm::refresh,
                onCopy = {
                    copyToClipboard(context, viewer.entries.map { renderLine(it) })
                },
                onClear = vm::clearSource,
                onClearAll = vm::clearAll,
            )
            HorizontalDivider(color = Color(0xFFD9DCE3))

            if (viewer.isEmpty) {
                EmptyState(refreshedOnce = refreshedOnce, modifier = Modifier.weight(1f))
            } else {
                LogList(
                    entries = viewer.entries,
                    autoScroll = filter.autoScroll,
                    modifier = Modifier.weight(1f),
                )
            }

            val capacity = LogComponent.repository().capacity
            Text(
                "共 ${viewer.totalCount} 条 · 显示 ${viewer.visibleCount} 条 · 上限 $capacity 条",
                fontSize = 11.sp,
                color = Color(0xFF8A93A3),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }

    // Auto-refresh ticker. Enabled by the Auto switch.
    LaunchedEffect(filter.autoRefresh) {
        while (filter.autoRefresh) {
            delay(2000)
            vm.refresh()
        }
    }
}

@Composable
private fun HeaderBar(onBack: () -> Unit, source: LogSource) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回设置")
        }
        Column {
            Text("运行日志", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1C1C1E))
            Text(source.displayName, fontSize = 12.sp, color = Color(0xFF6E7787))
        }
        Spacer(Modifier.weight(1f))
        Text(
            "ring buffer ${LogComponent.repository().capacity}",
            fontSize = 11.sp,
            color = Color(0xFF8A93A3),
        )
    }
}

@Composable
private fun SourceTabs(source: LogSource, onSource: (LogSource) -> Unit) {
    val tabs = listOf(
        LogSource.APPLICATION,
        LogSource.CLOUD_AGENT,
        LogSource.GALLERY,
        LogSource.WORKFLOW,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tabs.forEach { tab ->
            FilterChip(
                selected = source == tab,
                onClick = { onSource(tab) },
                label = { Text(tab.displayName, fontSize = 12.sp) },
            )
        }
    }
}

@Composable
private fun FilterRow(
    filter: LogFilter,
    onMinLevel: (LogLevel?) -> Unit,
    onQuery: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("级别:", fontSize = 12.sp, color = Color(0xFF6E7787))
            LogLevel.entries.forEach { level ->
                FilterChip(
                    selected = filter.minLevel == level,
                    onClick = { onMinLevel(level) },
                    label = { Text(level.name, fontSize = 11.sp) },
                )
            }
        }
        OutlinedTextField(
            value = filter.query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            placeholder = { Text("过滤关键词…", fontSize = 12.sp, color = Color(0xFF9AA2B1)) },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

@Composable
private fun ActionRow(
    autoRefresh: Boolean,
    autoScroll: Boolean,
    onToggleAutoRefresh: () -> Unit,
    onToggleAutoScroll: () -> Unit,
    onRefresh: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    onClearAll: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onRefresh) { Text("刷新", fontSize = 12.sp) }
        ToggleWithLabel(label = "自动", checked = autoRefresh, onToggle = onToggleAutoRefresh)
        ToggleWithLabel(label = "滚屏", checked = autoScroll, onToggle = onToggleAutoScroll)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onCopy) { Text("复制", fontSize = 12.sp) }
        TextButton(onClick = onClear) { Text("清空", fontSize = 12.sp) }
        TextButton(onClick = onClearAll) { Text("清空全部", fontSize = 12.sp) }
    }
}

@Composable
private fun ToggleWithLabel(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = Color(0xFF6E7787))
        Switch(checked = checked, onCheckedChange = { onToggle() }, modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun EmptyState(refreshedOnce: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(LogBackground, RoundedCornerShape(8.dp))
            .padding(24.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        Column {
            Text(
                if (refreshedOnce) {
                    "（暂无日志）该来源当前没有匹配的日志记录"
                } else {
                    "（暂无日志）点击“刷新”查看当前来源的实时日志"
                },
                fontSize = 13.sp,
                color = Color(0xFF9AA2B1),
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "日志只反映真实运行路径；未发生的云端/图库事件不会被伪造显示。",
                fontSize = 11.sp,
                color = Color(0xFF6E7787),
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun LogList(entries: List<InAppLogEntry>, autoScroll: Boolean, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .background(LogBackground)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        items(entries, key = { it.key }) { entry ->
            LogLine(entry = entry)
        }
    }
    LaunchedEffect(entries.size, autoScroll) {
        if (autoScroll && entries.isNotEmpty()) listState.scrollToItem(0)
    }
}

@Composable
private fun LogLine(entry: InAppLogEntry) {
    val color = when (entry.level) {
        LogLevel.ERROR -> Color(0xFFFF6B6B)
        LogLevel.WARN -> Color(0xFFF2C078)
        LogLevel.INFO -> Color(0xFFE0E0E0)
        LogLevel.DEBUG -> Color(0xFF9CABBB)
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            "[${fmtTime(entry.timestampMillis)}] [${entry.level.name}] ${entry.tag}",
            fontSize = 10.sp,
            color = Color(0xFF7BC4A0),
            fontFamily = FontFamily.Monospace,
        )
        Text(
            entry.message,
            fontSize = 11.sp,
            color = color,
            fontFamily = FontFamily.Monospace,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun renderLine(entry: InAppLogEntry): String =
    "[${fmtTime(entry.timestampMillis)}] [${entry.level.name}] [${entry.tag}] ${entry.message}"

private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

private fun fmtTime(millis: Long): String =
    runCatching { timeFormat.format(Date(millis)) }.getOrElse { millis.toString() }

private fun copyToClipboard(context: Context, lines: List<String>) {
    if (lines.isEmpty()) {
        Toast.makeText(context, "无内容可复制", Toast.LENGTH_SHORT).show()
        return
    }
    val text = lines.joinToString("\n")
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ClawMate log", text))
    }
    Toast.makeText(context, "已复制 ${lines.size} 行", Toast.LENGTH_SHORT).show()
}

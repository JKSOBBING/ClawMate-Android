package com.clawmate.app.gallery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clawmate.app.gallery.model.GalleryPhotoItem
import com.clawmate.app.ui.ClawMateInput

/**
 * Gallery item detail flow (Phase 9), opened from the gallery list.
 *
 * Shows only real data: content URI, archive date, EXIF metadata, analysis
 * state, tags/summary and collection membership — none of it fabricated. The
 * title/summary/tags fields are explicit user edits persisted to Room; delete
 * requests a confirmation and removes only the single item row. Reverse
 * geocoding is exposed as a retry-able action whose result/errors are surfaced
 * honestly (a failure never claims a location).
 *
 * Phase 11 adds an explicit **Analyze OCR** action: on-device ML Kit Chinese
 * text recognition over the picked content URI. It surfaces typed
 * loading/success/empty/error/model-unavailable states and persists only real
 * recognized text (+ analysis status) through Room when the action succeeds.
 *
 * HarmonyOS reference (read-only):
 *   pages/gallery/GalleryPage.ets (ResultDetailPanel)
 *   pages/collection/CollectionGroupDetailPage.ets
 */
@Composable
fun GalleryDetailScreen(
    detail: GalleryDetailState,
    onBack: () -> Unit,
    onEditTitle: (String, String) -> Unit,
    onEditSummary: (String, String) -> Unit,
    onEditTags: (String, List<String>) -> Unit,
    onDelete: (String) -> Unit,
    onResolveLocation: (String) -> Unit,
    onAnalyzeOcr: (String) -> Unit,
    visionContent: @Composable (GalleryPhotoItem) -> Unit = {},
) {
    val item = detail.currentItem
    val itemId = item?.id

    var titleDraft by remember(item?.id, item?.title) { mutableStateOf(item?.title ?: "") }
    var summaryDraft by remember(item?.id, item?.summary) { mutableStateOf(item?.summary ?: "") }
    var tagsDraft by remember(item?.id, item?.tags) { mutableStateOf(item?.tags?.joinToString("、") ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }

    if (item == null || itemId == null) {
        Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("该项目不存在", style = MaterialTheme.typography.titleMedium)
                Text(
                    "它可能已被删除。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("详情", style = MaterialTheme.typography.headlineSmall)
        }

        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            item("header") {
                Text(
                    GalleryDetailLabels.displayTitle(item),
                    style = MaterialTheme.typography.titleLarge,
                )
                analysisRow(item)
            }

            item("cloud-vision") { visionContent(item) }

            item("uri") { InfoRow("内容 URI", GalleryDetailLabels.contentUriLine(item.uri)) }
            item("date") { InfoRow("归档日期", item.date.ifBlank { "—" }) }
            item("time") { InfoRow("拍摄时间", GalleryDetailLabels.captureTimeLine(item)) }
            item("device") { InfoRow("设备", item.exif?.deviceModel?.ifBlank { null } ?: "（无 EXIF 设备信息）") }
            item("size") {
                InfoRow(
                    "图片尺寸",
                    sizeText(item).let { if (it.isBlank()) "（无尺寸信息）" else it },
                )
            }
            item("state") { InfoRow("分析状态", GalleryDetailLabels.analysisStateLabel(item.analysisState)) }
            item("category") { InfoRow("分类", item.category.ifBlank { "（未分类）" }) }

            item("summary") {
                Text(
                    "摘要",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    item.summary.ifBlank { "（无摘要）" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            item("edit-title") {
                SectionTitle("编辑标题")
                ClawMateInput(
                    value = titleDraft,
                    onValueChange = { titleDraft = it },
                    label = "标题",
                    singleLine = true,
                )
                Button(
                    onClick = { onEditTitle(item.id, titleDraft) },
                    modifier = Modifier.padding(top = 6.dp),
                ) { Text("保存标题") }
            }

            item("edit-summary") {
                SectionTitle("编辑摘要")
                ClawMateInput(
                    value = summaryDraft,
                    onValueChange = { summaryDraft = it },
                    label = "摘要",
                    singleLine = false,
                    maxLines = 4,
                )
                Button(
                    onClick = { onEditSummary(item.id, summaryDraft) },
                    modifier = Modifier.padding(top = 6.dp),
                ) { Text("保存摘要") }
            }

            item("edit-tags") {
                SectionTitle("编辑标签")
                ClawMateInput(
                    value = tagsDraft,
                    onValueChange = { tagsDraft = it },
                    label = "标签（用、分隔）",
                    singleLine = true,
                )
                Button(
                    onClick = { onEditTags(item.id, splitTags(tagsDraft)) },
                    modifier = Modifier.padding(top = 6.dp),
                ) { Text("保存标签") }
            }

            item("tags") { InfoRow("当前标签", GalleryDetailLabels.tagsText(item.tags).ifBlank { "（无标签）" }) }

            item("membership") { membershipSection(detail.groupMembership) }

            item("ocr") {
                ocrSection(
                    item = item,
                    state = detail.ocr,
                    onAnalyze = onAnalyzeOcr,
                )
            }

            item("location") { locationSection(item, detail.location, onResolveLocation) }

            item("footer") {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("删除该项目", color = MaterialTheme.colorScheme.error) }
                Text(
                    "删除仅移除本项目记录，不删除相册文件，也不会撤销 Photo Picker 的 URI 授权。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除该项目？") },
            text = { Text("将移除本图库条目（Content URI 记录）。相册中的原始文件不会被删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete(itemId)
                    },
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun analysisRow(item: GalleryPhotoItem) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        AssistChip(onClick = {}, label = { Text("状态 ${GalleryDetailLabels.analysisStateLabel(item.analysisState)}") })
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 16.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun membershipSection(groups: List<com.clawmate.app.gallery.model.CollectionGroup>) {
    SectionTitle("集合归属")
    if (groups.isEmpty()) {
        Text("（未被分到任何集合）", style = MaterialTheme.typography.bodySmall)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            groups.forEach { g ->
                AssistChip(onClick = {}, label = { Text("${g.title} · ${g.eventIds.size}") })
            }
        }
    }
}

@Composable
private fun ocrSection(
    item: GalleryPhotoItem,
    state: OcrViewState,
    onAnalyze: (String) -> Unit,
) {
    SectionTitle("本地 OCR（中文）")
    when (state) {
        is OcrViewState.Success -> {
            Text(
                "已识别文本：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                state.text.ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.padding(top = 4.dp)) {
                TextButton(onClick = { onAnalyze(item.id) }) { Text("重新分析") }
            }
        }
        is OcrViewState.Empty -> {
            Text(
                "已分析，未识别到文字。",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { onAnalyze(item.id) }) { Text("重新分析") }
        }
        OcrViewState.Loading -> {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                CircularProgressIndicator(Modifier.width(18.dp).height(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("正在执行本地 OCR…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        is OcrViewState.Error -> {
            Text(
                "OCR 失败：${state.message}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = { onAnalyze(item.id) }) { Text("重试") }
        }
        is OcrViewState.ModelDownload -> {
            Text(
                "本地模型尚不可用，可能需要下载：${state.message}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OcrViewState.Unavailable -> {
            Text(
                "此设备不提供本地中文 OCR。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OcrViewState.Idle -> {
            Text(
                "对选中图片运行一次本地中文文本识别。识别仅在点击时进行，不会扫描全部媒体。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { onAnalyze(item.id) },
                modifier = Modifier.padding(top = 6.dp),
            ) { Text("分析图片") }
        }
    }
}

@Composable
private fun locationSection(
    item: GalleryPhotoItem,
    location: LocationViewState,
    onResolve: (String) -> Unit,
) {
    SectionTitle("地理位置")
    val hasCoords = (item.exif?.latitude != null || item.latitude != null) &&
        (item.exif?.longitude != null || item.longitude != null)
    when (location) {
        is LocationViewState.Resolved -> {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "已解析：${location.location.formattedAddress.ifBlank { "—" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onResolve(item.id) }) { Text("重试") }
            }
        }
        is LocationViewState.Failed -> {
            Text(
                "解析失败：${GalleryDetailLabels.locationFailureText(location.error)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            if (hasCoords) {
                TextButton(onClick = { onResolve(item.id) }) { Text("重试解析") }
            }
        }
        LocationViewState.Resolving -> {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(18.dp).height(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("正在解析位置…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        LocationViewState.NoCoordinates -> {
            Text("此图片没有可用经纬度，无法解析具体位置。", style = MaterialTheme.typography.bodyMedium)
        }
        LocationViewState.NotConfigured -> {
            Text(
                "逆地理编码未配置。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hasCoords) {
                TextButton(onClick = { onResolve(item.id) }) { Text("重试") }
            }
        }
    }
}

private fun sizeText(item: GalleryPhotoItem): String {
    val w = item.exif?.imageWidth
    val h = item.exif?.imageHeight
    if (w != null && h != null) return "${w} × ${h}"
    return ""
}

private fun splitTags(raw: String): List<String> =
    raw.split('、', ',', '，').map { it.trim() }.filter { it.isNotEmpty() }

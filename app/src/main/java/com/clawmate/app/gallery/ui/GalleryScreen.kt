package com.clawmate.app.gallery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clawmate.app.gallery.GalleryComponent
import com.clawmate.app.gallery.analysis.CapabilityState
import com.clawmate.app.gallery.model.GalleryPhotoItem
import com.clawmate.app.gallery.photo.rememberPhotoPicker

/**
 * Gallery / Collection screen wired into the 汇总 tab of the shell.
 *
 * Features:
 *  - a real Photo Picker action (system UI, no broad storage permission);
 *  - a lazily-rendered list of imported photo items;
 *  - empty / loading / error states (no hardcoded demo data);
 *  - collection group chips (count and titles) recomputed from stored items;
 *  - a basic search box that filters loaded items via the deterministic
 *    search service.
 *
 * OCR / cloud vision / reverse geocoding are surfaced honestly as
 * NOT_CONFIGURED (no fake analysis is ever produced).
 *
 * HarmonyOS source of truth (read-only):
 *   pages/gallery/GalleryPage.ets
 *   pages/collection/CollectionPage.ets
 */
@Composable
fun GalleryScreen() {
    val context = LocalContext.current
    val repository = remember {
        GalleryComponent.getRepository(context)
    }
    val vm: GalleryViewModel = viewModel(
        factory = GalleryViewModel.Factory(
            repository = repository,
            ocrEngine = GalleryComponent.ocr,
            visionAnalyzer = GalleryComponent.vision,
            reverseGeocoder = GalleryComponent.reverseGeocoder,
        ),
    )
    val uiState by vm.state.collectAsState()

    var searchQuery by remember { mutableStateOf("") }

    val launchPicker = rememberPhotoPicker(maxSelectNumber = 9) { result ->
        vm.onPickResult(context, result)
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { launchPicker() },
                icon = { Icon(Icons.Filled.Add, contentDescription = "选择图片") },
                text = { Text("选择图片") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Text("汇总 / 图库", style = MaterialTheme.typography.headlineMedium)
            Text(
                "从系统相册选择图片，保存为图库条目并按集合分组。",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    vm.setSearchQuery(it)
                },
                label = { Text("搜索图库") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))

            when (val s = uiState) {
                is GalleryUiState.Loading -> LoadingBlock()
                is GalleryUiState.Error -> ErrorBlock(s.message)
                is GalleryUiState.Loaded -> LoadedBlock(
                    state = s,
                    query = searchQuery,
                )
            }
        }
    }
}

@Composable
private fun LoadingBlock() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 24.dp)) {
        CircularProgressIndicator(Modifier.size(24.dp))
        Spacer(Modifier.padding(horizontal = 8.dp))
        Text("加载中…", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ErrorBlock(message: String) {
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("出错了", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LoadedBlock(
    state: GalleryUiState.Loaded,
    query: String,
) {
    val visibleItems = if (query.isBlank()) state.items
    else filterByQuery(state.items, query)

    // Honest capability summary.
    if (state.capability.hasUnconfigured) {
        CapabilityNotice(
            ocr = state.capability.ocr,
            vision = state.capability.vision,
            reverseGeocoding = state.capability.reverseGeocoding,
        )
    }

    if (state.groups.isNotEmpty()) {
        Text(
            "集合分组（${state.groups.size}）",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.groups.take(6).forEach { g ->
                AssistChip(onClick = {}, label = { Text("${g.title} · ${g.eventIds.size}") })
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    when {
        state.items.isEmpty() -> EmptyBlock()
        visibleItems.isEmpty() -> Text("没有匹配的结果", style = MaterialTheme.typography.bodyMedium)
        else -> LazyColumn(Modifier.fillMaxWidth()) {
            items(visibleItems, key = { it.id }) { item -> PhotoRow(item) }
        }
    }
}

@Composable
private fun EmptyBlock() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
    ) {
        Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
        Text("还没有图库条目", style = MaterialTheme.typography.titleMedium)
        Text("点右下角「选择图片」从系统相册添加照片", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CapabilityNotice(
    ocr: CapabilityState,
    vision: CapabilityState,
    reverseGeocoding: CapabilityState,
) {
    val parts = mutableListOf<String>()
    if (ocr == CapabilityState.NotConfigured) parts += "本地 OCR 未配置"
    if (vision == CapabilityState.NotConfigured) parts += "云端视觉分析未配置"
    if (reverseGeocoding == CapabilityState.NotConfigured) parts += "逆地理编码未配置"
    if (parts.isNotEmpty()) {
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "分析能力（尚未实现）",
                    style = MaterialTheme.typography.titleSmall,
                )
                parts.forEach { p ->
                    Text("· $p", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "图片会被保存为图库条目，但不会进行 OCR / 云端识别 / 位置解析。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PhotoRow(item: GalleryPhotoItem) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(item.title.ifBlank { item.id }, style = MaterialTheme.typography.titleMedium)
            Text("日期 ${item.date}", style = MaterialTheme.typography.bodySmall)
            item.summary.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Deterministic in-memory filter mirroring the search service scoring. */
private fun filterByQuery(items: List<GalleryPhotoItem>, query: String): List<GalleryPhotoItem> {
    val q = query.trim()
    if (q.isEmpty()) return items
    return com.clawmate.app.gallery.search.GallerySearchService
        .search(q, items)
        .map { it.item }
}

package com.clawmate.app.gallery.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.clawmate.app.gallery.background.GalleryScanViewModel
import com.clawmate.app.gallery.background.GallerySyncGateStatus
import com.clawmate.app.gallery.background.GalleryScanUiState
import com.clawmate.app.gallery.background.GallerySyncOptinRegistry
import com.clawmate.app.gallery.model.GalleryPhotoItem
import com.clawmate.app.gallery.photo.rememberPhotoPicker
import com.clawmate.app.gallery.ui.insights.GalleryInsightsViewModel
import com.clawmate.app.gallery.ui.insights.MonthlyInsightsPanel
import com.clawmate.app.settings.SettingsComponent

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
            ocrEngine = GalleryComponent.ocr(context),
            visionAnalyzer = GalleryComponent.vision,
            reverseGeocoder = GalleryComponent.reverseGeocoder(context),
        ),
    )
    val uiState by vm.state.collectAsState()
    val selectedId by vm.selectedItemId.collectAsState()
    val detail by vm.detail.collectAsState()

    // Phase 15 ++ Phase 21 — real monthly-insights panel derived from the same
    // Room-backed repository. Cloud AI summary & embedding states come from the
    // config-driven [GalleryCloudAdapter] behind explicit settings; the adapter
    // only reports gate states and is never invoked for a silent upload.
    val insightsVm: GalleryInsightsViewModel = viewModel(
        factory = GalleryInsightsViewModel.Factory(
            repository = repository,
            cloudAdapterProvider = GalleryComponent.cloudAdapterProvider(context),
        ),
    )
    val insightsState by insightsVm.state.collectAsState()

    var searchQuery by remember { mutableStateOf("") }

    // Phase 42 — explicit "扫描/同步图库" entry. The coordinator is the
    // production singleton built in GalleryComponent with the real MediaStore
    // source and the existing Room repository. Constructing it triggers no scan;
    // only the explicit button click below dispatches scanNow().
    val scanCoordinator = remember { GalleryComponent.scanCoordinator(context) }
    val scanGateProvider = remember { GalleryComponent.scanGateProvider(context) }
    val scanVm: GalleryScanViewModel = viewModel(
        factory = GalleryScanViewModel.Factory(scanCoordinator, scanGateProvider),
    )
    val scanState by scanVm.state.collectAsState()

    // Restore the explicitly persisted opt-in after process restart. This is a
    // read-only gate refresh; it never starts a scan or requests permission.
    LaunchedEffect(Unit) {
        val optedIn = runCatching {
            SettingsComponent.repository(context).snapshot().galleryBackgroundSyncEnabled
        }.getOrDefault(false)
        GallerySyncOptinRegistry.setOptIn(optedIn)
        scanVm.refreshGate()
    }

    // Runtime media-read permission, requested only when the user taps the
    // explicit scan button. On grant we dispatch scanNow(); on denial we surface
    // an honest BLOCKED state and never scan.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        scanVm.refreshGate()
        if (granted) {
            scanVm.scanNow()
        }
    }

    val launchPicker = rememberPhotoPicker(maxSelectNumber = 9) { result ->
        vm.onPickResult(context, result)
    }

    // Phase-44 — index-backed 汇总 grouping over the real indexed daily-log
    // events. Constructing the ViewModel triggers no I/O; reads only happen on
    // the explicit「同步」action inside [CollectionIndexViewModel.refreshIndex].
    val indexedReader = remember { GalleryComponent.indexedEventReader(context) }
    val indexedVm: CollectionIndexViewModel = viewModel(
        factory = CollectionIndexViewModel.Factory(indexedReader),
    )
    val indexedDetail by indexedVm.detail.collectAsState()

    if (indexedDetail != null) {
        IndexedCollectionGroupDetailScreen(
            detail = indexedDetail!!,
            onBack = { indexedVm.closeDetail() },
        )
        return
    }

    if (selectedId != null && detail?.itemId == selectedId) {
        val d = detail
        if (d != null) {
            GalleryDetailScreen(
                detail = d,
                onBack = { vm.deselectItem() },
                onEditTitle = { id, title -> vm.editTitle(id, title) },
                onEditSummary = { id, summary -> vm.editSummary(id, summary) },
                onEditTags = { id, tags -> vm.editTags(id, tags) },
                onDelete = { id -> vm.deleteItem(id) },
                onResolveLocation = { id -> vm.resolveLocation(id) },
                onAnalyzeOcr = { id -> vm.analyzeOcr(id) },
            )
            return
        }
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

            // Phase 42 — explicit scan/sync entry. Renders an honest status
            // derived from the live opt-in + permission gate, and only ever scans
            // after the user taps the button (which requests the runtime
            // media-read permission first).
            GalleryScanSection(
                state = scanState,
                onScan = {
                    when (val g = scanGateProvider().status) {
                        GallerySyncGateStatus.READY -> scanVm.scanNow()
                        GallerySyncGateStatus.NOT_CONFIGURED ->
                            // Not opted in: honest notice, no scan. The user must
                            // enable it in Settings first.
                            scanVm.refreshGate()
                        GallerySyncGateStatus.BLOCKED ->
                            // Opted in but permission missing → request it now.
                            permissionLauncher.launch(mediaReadPermissionName())
                    }
                },
                onCancel = { scanVm.cancelActive() },
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

            // Phase 15 — real monthly-insights panel (pure derivation over stored
            // Room data). Independent loading / empty / error states; never
            // fabricates an insight or a summary.
            MonthlyInsightsPanel(
                state = insightsState,
                onOpenItem = { id -> vm.selectItem(id) },
                onGenerateSummary = { insight -> insightsVm.generateMonthlySummary(insight) },
                onEmbed = { insight -> insightsVm.embedSelectedMonth(insight) },
            )

            Spacer(Modifier.height(8.dp))

            // Phase-44 — index-backed collection grouping (real daily-log events).
            // Distinct from the photo heuristic grouping; groups are clickable and
            // open IndexedCollectionGroupDetailScreen full-screen.
            IndexedCollectionPanel(
                vm = indexedVm,
                onOpenGroup = { g -> indexedVm.openGroup(g.groupId) },
            )

            Spacer(Modifier.height(8.dp))

            when (val s = uiState) {
                is GalleryUiState.Loading -> LoadingBlock()
                is GalleryUiState.Error -> ErrorBlock(s.message)
                is GalleryUiState.Loaded -> LoadedBlock(
                    state = s,
                    query = searchQuery,
                    onOpenItem = { id -> vm.selectItem(id) },
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
    onOpenItem: (String) -> Unit,
) {
    val visibleItems = if (query.isBlank()) state.items
    else filterByQuery(state.items, query)

    // Honest capability summary reflecting real states.
    if (state.capability.hasUnconfigured || state.capability.hasUnsupported) {
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
            items(visibleItems, key = { it.id }) { item ->
                PhotoRow(item, onClick = { onOpenItem(item.id) })
            }
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
    appendCapability(parts, "本地 OCR", ocr)
    appendCapability(parts, "云端视觉分析", vision)
    appendCapability(parts, "逆地理编码", reverseGeocoding)
    if (parts.isNotEmpty()) {
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "分析能力（本地）",
                    style = MaterialTheme.typography.titleSmall,
                )
                parts.forEach { p ->
                    Text("· $p", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "位置解析使用系统内置地理编码服务，无需网络 Key；OCR / 云端识别仍受平台能力与配置限制。每一项失败都不会伪造结果。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun appendCapability(parts: MutableList<String>, label: String, state: CapabilityState) {
    when (state) {
        is CapabilityState.NotConfigured -> parts += "$label：未配置"
        CapabilityState.Unsupported -> parts += "$label：设备不支持"
        is CapabilityState.Ready -> parts += "$label：可用（${state.description}）"
    }
}

@Composable
private fun PhotoRow(item: GalleryPhotoItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
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

/** The runtime media-read permission name for the current SDK level. */
private fun mediaReadPermissionName(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

/**
 * Explicit scan/sync entry + honest status surface.
 *
 * The action: the user taps 「同步图库」. If the live gate is READY we scan now;
 * if BLOCKED (opted in, permission missing) the parent launches the runtime
 * permission request; if NOT_CONFIGURED (not opted in) we show the honest
 * require-settings notice. No scan is ever triggered by construction / load.
 * Results come from the coordinator's real state vocabulary.
 */
@Composable
private fun GalleryScanSection(
    state: GalleryScanUiState,
    onScan: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("扫描 / 同步图库", style = MaterialTheme.typography.titleSmall)
                when (state) {
                    is GalleryScanUiState.Running -> CircularProgressIndicator(Modifier.size(20.dp))
                    else -> Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
            when (state) {
                is GalleryScanUiState.Idle -> {
                    Text(
                        when (state.gate.status) {
                            GallerySyncGateStatus.NOT_CONFIGURED ->
                                "未启用后台图库同步 — 请在设置中开启「后台图库扫描/同步」后再触发。"
                            GallerySyncGateStatus.BLOCKED ->
                                "已开启，但未授予图库读取权限 — 点击「同步图库」请求权限。"
                            GallerySyncGateStatus.READY ->
                                "已就绪 — 可从系统相册扫描并同步到本地图库。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is GalleryScanUiState.Running -> Text(
                    "正在扫描并同步…",
                    style = MaterialTheme.typography.bodySmall,
                )
                is GalleryScanUiState.Succeeded -> Text(
                    "同步完成：候选 ${state.summary.candidateCount}，本地入库 ${state.summary.localStored}，云同步 ${cloudLabel(state.summary.cloudSync)}。",
                    style = MaterialTheme.typography.bodySmall,
                )
                is GalleryScanUiState.Partial -> Text(
                    "部分完成：入库 ${state.summary.localStored}，跳过 ${state.summary.skippedCount}${state.summary.skippedReason?.let { "（$it）" } ?: ""}。",
                    style = MaterialTheme.typography.bodySmall,
                )
                is GalleryScanUiState.Failed -> Text(
                    state.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
            when (state) {
                is GalleryScanUiState.Running -> OutlinedButton(onClick = onCancel) {
                    Text("取消同步")
                }
                else -> Button(onClick = onScan) {
                    Text("同步图库")
                }
            }
        }
    }
}

/** Neutral label for the optional cloud step; never claims a memory sync. */
private fun cloudLabel(status: com.clawmate.app.gallery.background.GalleryCloudSyncStatus): String =
    when (status) {
        com.clawmate.app.gallery.background.GalleryCloudSyncStatus.RUN -> "已运行"
        com.clawmate.app.gallery.background.GalleryCloudSyncStatus.SKIPPED_NOT_CONFIGURED -> "未配置（无请求）"
        com.clawmate.app.gallery.background.GalleryCloudSyncStatus.FAILED -> "失败"
    }

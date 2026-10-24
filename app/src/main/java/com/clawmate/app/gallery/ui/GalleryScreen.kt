package com.clawmate.app.gallery.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import com.clawmate.app.ui.*
import com.clawmate.app.ui.theme.HarmonyTheme
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.clawmate.app.gallery.GalleryComponent
import com.clawmate.app.gallery.background.GalleryScanViewModel
import com.clawmate.app.gallery.background.GallerySyncGateStatus
import com.clawmate.app.gallery.background.GalleryScanUiState
import com.clawmate.app.gallery.background.GallerySyncOptinRegistry
import com.clawmate.app.gallery.model.GalleryPhotoItem
import com.clawmate.app.gallery.photo.rememberPhotoPicker
import com.clawmate.app.gallery.ui.insights.GalleryInsightsViewModel
import com.clawmate.app.gallery.ui.insights.MonthlyInsightsPanel
import com.clawmate.app.settings.SettingsComponent
import com.clawmate.app.ui.ClawMateInput





@Composable
fun GalleryScreen(
    onBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val repository = remember { GalleryComponent.getRepository(context) }
    val vm: GalleryViewModel = viewModel(
        factory = GalleryViewModel.Factory(
            repository = repository,
            ocrEngine = GalleryComponent.ocr(context),
            visionAnalyzer = GalleryComponent.vision,
            reverseGeocoder = GalleryComponent.reverseGeocoder(context),
        ),
    )
    val baseUiState by vm.state.collectAsState()
    val visionVm: com.clawmate.app.gallery.vision.GalleryVisionViewModel = viewModel(
        key = "gallery-vision", factory = remember(context, repository) { galleryVisionFactory(context, repository) },
    )
    val vision by visionVm.state.collectAsState()
    val questionVm: com.clawmate.app.gallery.question.GalleryQuestionViewModel = viewModel(
        key = "gallery-question", factory = remember(context, repository) { galleryQuestionFactory(context, repository) },
    )
    val uiState = (baseUiState as? GalleryUiState.Loaded)?.let { loaded ->
        loaded.copy(capability = loaded.capability.copy(vision = if (vision.configured)
            com.clawmate.app.gallery.analysis.CapabilityState.Ready("图库图片模型已配置，需在详情确认分析") else
            com.clawmate.app.gallery.analysis.CapabilityState.NotConfigured))
    } ?: baseUiState
    val selectedId by vm.selectedItemId.collectAsState()
    val detail by vm.detail.collectAsState()
    val insightsVm: GalleryInsightsViewModel = viewModel(
        factory = GalleryInsightsViewModel.Factory(
            repository = repository,
            cloudAdapterProvider = GalleryComponent.cloudAdapterProvider(context),
        ),
    )
    val insightsState by insightsVm.state.collectAsState()
    var selectedFilter by remember { mutableIntStateOf(0) }
    var selectedTimeFilter by remember { mutableIntStateOf(0) }
    var showBulkExplanation by remember { mutableStateOf(false) }
    var bulkError by remember { mutableStateOf("") }
    val localSearchScope = rememberCoroutineScope()
    var searchHits by remember { mutableStateOf(emptyList<com.clawmate.app.gallery.model.GallerySearchHit>()) }
    var searchMessage by remember { mutableStateOf("输入问题后检索图库记录") }
    var searchGeneration by remember { mutableIntStateOf(0) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val scanCoordinator = remember { GalleryComponent.scanCoordinator(context) }
    val scanGateProvider = remember { GalleryComponent.scanGateProvider(context) }
    val scanVm: GalleryScanViewModel = viewModel(
        factory = GalleryScanViewModel.Factory(scanCoordinator, scanGateProvider),
    )
    val scanState by scanVm.state.collectAsState()
    LaunchedEffect(Unit) {
        visionVm.refreshConfiguration()
        val optedIn = runCatching {
            SettingsComponent.repository(context).snapshot().galleryBackgroundSyncEnabled
        }.getOrDefault(false)
        GallerySyncOptinRegistry.setOptIn(optedIn)
        scanVm.refreshGate()
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        scanVm.refreshGate()
        if (granted) scanVm.scanNow()
    }
    val launchPicker = rememberPhotoPicker(maxSelectNumber = 9) { result ->
        vm.onPickResult(context, result)
    }
    val requestScan = {
        when (scanGateProvider().status) {
            GallerySyncGateStatus.READY -> scanVm.scanNow()
            GallerySyncGateStatus.NOT_CONFIGURED -> scanVm.refreshGate()
            GallerySyncGateStatus.BLOCKED -> permissionLauncher.launch(mediaReadPermissionName())
        }
    }
    if (selectedId != null && detail?.itemId == selectedId) {
        val d = detail
        if (d != null) {
            BackHandler { vm.deselectItem() }
            InsetSecondaryPage { GalleryDetailScreen(
                detail = d, onBack = { vm.deselectItem() },
                onEditTitle = { id, title -> vm.editTitle(id, title) },
                onEditSummary = { id, summary -> vm.editSummary(id, summary) },
                onEditTags = { id, tags -> vm.editTags(id, tags) },
                onDelete = { id -> vm.deleteItem(id) },
                onResolveLocation = { id -> vm.resolveLocation(id) },
                onAnalyzeOcr = { id -> vm.analyzeOcr(id) },
                visionContent = { item -> GalleryVisionPanel(item, visionVm, onOpenSettings) },
            ) }
            return
        }
    }
    val loadedItems = (uiState as? GalleryUiState.Loaded)?.items.orEmpty()
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空图库分析") },
            text = { Text("将删除已导入的图库记录及其分析结果，但不会删除手机原始照片。此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        loadedItems.forEach { vm.deleteItem(it.id) }
                        showClearConfirm = false
                    },
                ) { Text("确认清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }
    val scroll = rememberScrollState()
    HarmonyPage(scrollOffset = { scroll.value.toFloat() }, header = { HarmonyGalleryHeader(onBack, onOpenSettings) }) { insets ->
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(insets), horizontalAlignment = Alignment.CenterHorizontally) {
            HarmonyGalleryToolbar(loadedItems, scanState is GalleryScanUiState.Running,
                { launchPicker() }, { showBulkExplanation = true; bulkError = "" },
                { showClearConfirm = true }, { scanVm.cancelActive() })
            if (scanState !is GalleryScanUiState.Idle) GalleryProgressStrip(scanState) { scanVm.cancelActive() }
            MonthlyInsightsPanel(insightsState, { vm.selectItem(it) },
                { insightsVm.generateMonthlySummary(it) }, { insightsVm.embedSelectedMonth(it) })
            GalleryQuestionPanel(questionVm, { vm.selectItem(it) }, onOpenSettings,
                onSearch = { query -> val generation = ++searchGeneration; localSearchScope.launch {
                    searchMessage = "检索中…"
                    try {
                        if (query.isBlank()) { searchHits = emptyList(); searchMessage = "请输入检索内容"; return@launch }
                        val found = repository.search(query.trim()).take(20)
                        if (generation != searchGeneration) return@launch
                        searchHits = found
                        searchMessage = if (searchHits.isEmpty()) "没有找到明确相关图片" else "找到 ${searchHits.size} 条相关图片"
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { if (generation == searchGeneration) { searchMessage = "无法读取本机图库记录，请重试"; searchHits = emptyList() } }
                } }, searchMessage = searchMessage, searchHits = searchHits,
                onClearSearch = { searchGeneration++; searchHits = emptyList(); searchMessage = "输入问题后检索图库记录" })
            when (val value = uiState) {
                is GalleryUiState.Loading -> LoadingBlock()
                is GalleryUiState.Error -> ErrorBlock(value.message)
                is GalleryUiState.Loaded -> HarmonyGalleryResults(value.items, selectedFilter, selectedTimeFilter,
                    { selectedFilter = it }, { selectedTimeFilter = it }, { vm.selectItem(it) })
            }
            Spacer(Modifier.height(20.dp))
        }
    }
    if (showBulkExplanation) HarmonyModal({ showBulkExplanation = false }) {
        Text("批量读图", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
        Text("当前 Android 批量入口读取并保存本机图片记录；图片模型分析请在单张详情中确认执行。",
            fontSize = 14.sp, lineHeight = 22.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(vertical = 16.dp))
        if (bulkError.isNotEmpty()) Text(bulkError, fontSize = 12.sp, color = HarmonyTheme.text("#B42318"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HarmonyButton("取消", { showBulkExplanation = false })
            HarmonyButton("扫描入库", {
                if (scanGateProvider().status == GallerySyncGateStatus.NOT_CONFIGURED) bulkError = "请先在数据采集设置中开启并配置图库后台同步。"
                else { showBulkExplanation = false; requestScan() }
            })
            HarmonyButton("设置", { showBulkExplanation = false; onOpenSettings() })
        }
    }
}

@Composable
private fun GalleryProgressStrip(state: GalleryScanUiState, onCancel: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(0.90f).padding(top = 10.dp),
        colors = CardDefaults.cardColors(containerColor = HarmonyTheme.surface), shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    when (state) { is GalleryScanUiState.Running -> "分析中"; is GalleryScanUiState.Succeeded -> "已完成"; is GalleryScanUiState.Partial -> "部分完成"; is GalleryScanUiState.Failed -> "分析失败"; is GalleryScanUiState.Idle -> "就绪" },
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.text("#202636"),
                )
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            if (state is GalleryScanUiState.Running) androidx.compose.material3.LinearProgressIndicator(
                Modifier.fillMaxWidth().height(6.dp), color = HarmonyTheme.text("#2F5BEA"), trackColor = HarmonyTheme.background("#E8EDF7"))
            else if (state is GalleryScanUiState.Succeeded) androidx.compose.material3.LinearProgressIndicator(
                progress = { 1f }, modifier = Modifier.fillMaxWidth().height(6.dp), color = HarmonyTheme.text("#2F5BEA"), trackColor = HarmonyTheme.background("#E8EDF7"))
            Spacer(Modifier.height(8.dp))
            when (state) {
                is GalleryScanUiState.Succeeded -> Row {
                    Text("入库 " + state.summary.localStored + " 项", fontSize = 12.sp, color = HarmonyTheme.text("#667085"))
                    if (state.summary.skippedCount > 0) Text(" | 跳过 " + state.summary.skippedCount + " 项", fontSize = 12.sp, color = HarmonyTheme.text("#667085"))
                }
                is GalleryScanUiState.Failed -> {
                    Text(state.reason, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = onCancel, modifier = Modifier.height(32.dp)) { Text("取消", fontSize = 12.sp) }
                }
                else -> {}
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

private fun mediaReadPermissionName(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
    else Manifest.permission.READ_EXTERNAL_STORAGE

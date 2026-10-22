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

private data class CategoryFilter(val label: String, val index: Int, val count: Int)

private val timeFilterOptions = listOf("全部时间", "近7天", "近30天", "今年")

@Composable
fun GalleryScreen(
    onBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
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
    val questionState by questionVm.state.collectAsState()
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
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableIntStateOf(-1) }
    var selectedTimeFilter by remember { mutableIntStateOf(0) }
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
            GalleryDetailScreen(
                detail = d, onBack = { vm.deselectItem() },
                onEditTitle = { id, title -> vm.editTitle(id, title) },
                onEditSummary = { id, summary -> vm.editSummary(id, summary) },
                onEditTags = { id, tags -> vm.editTags(id, tags) },
                onDelete = { id -> vm.deleteItem(id) },
                onResolveLocation = { id -> vm.resolveLocation(id) },
                onAnalyzeOcr = { id -> vm.analyzeOcr(id) },
                visionContent = { item -> GalleryVisionPanel(item, visionVm, onOpenSettings) },
            )
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
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 84.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GalleryPageHeader(onBack = onBack, onOpenSettings = onOpenSettings)
            val storedCount = loadedItems.size
            GalleryActionToolbar(
                storedCount = storedCount, syncing = scanState is GalleryScanUiState.Running,
                onPick = { launchPicker() }, onAnalyze = requestScan,
                onClear = { showClearConfirm = true },
            )
            if (scanState !is GalleryScanUiState.Idle) {
                GalleryProgressStrip(state = scanState, onCancel = { scanVm.cancelActive() })
            }
            Spacer(Modifier.height(8.dp))
            MonthlyInsightsPanel(
                state = insightsState,
                onOpenItem = { id -> vm.selectItem(id) },
                onGenerateSummary = { insight -> insightsVm.generateMonthlySummary(insight) },
                onEmbed = { insight -> insightsVm.embedSelectedMonth(insight) },
            )
            Spacer(Modifier.height(8.dp))
            GalleryQuestionPanel(questionVm, { id -> vm.selectItem(id) }, onOpenSettings)
            ClawMateInput(
                value = searchQuery,
                onValueChange = { searchQuery = it; vm.setSearchQuery(it) },
                label = "搜索图库",
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            when (val s = uiState) {
                is GalleryUiState.Loading -> LoadingBlock()
                is GalleryUiState.Error -> ErrorBlock(s.message)
                is GalleryUiState.Loaded -> GalleryResultsPanel(
                    state = s, query = searchQuery,
                    selectedFilter = selectedFilter, selectedTimeFilter = selectedTimeFilter,
                    onSelectFilter = { selectedFilter = it },
                    onSelectTimeFilter = { selectedTimeFilter = it },
                    onOpenItem = { id -> vm.selectItem(id) },
                )
            }
        }
        if (questionState.question.isBlank() && questionState.answer == null && !questionState.busy) ExtendedFloatingActionButton(
            onClick = { launchPicker() },
            icon = { Icon(Icons.Filled.Add, contentDescription = "选择图片") },
            text = { Text("选择图片") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
        )
    }
}

@Composable
private fun GalleryPageHeader(onBack: () -> Unit, onOpenSettings: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(0.90f).padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回汇总") }
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text("图库分析", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF11182F))
            Text("相册、截图与文档图片洞察", fontSize = 13.sp, color = Color(0xFF717684), modifier = Modifier.padding(top = 3.dp))
        }
        IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "图库设置", tint = Color(0xFF2F5BEA)) }
    }
}

@Composable
private fun GalleryActionToolbar(storedCount: Int, syncing: Boolean, onPick: () -> Unit, onAnalyze: () -> Unit, onClear: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(0.90f).padding(top = 14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("已分析", fontSize = 12.sp, color = Color(0xFF737987))
                    Text("" + storedCount, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2F5BEA))
                }
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(38.dp)
                        .background(Color(0xFFE5E8F0)),
                )
                Column(modifier = Modifier.weight(1f).padding(start = 18.dp)) {
                    Text("最近分析", fontSize = 12.sp, color = Color(0xFF737987))
                    Text(
                        if (syncing) "分析中" else if (storedCount > 0) "已入库" else "尚未同步",
                        fontSize = 16.sp, color = Color(0xFF202636), modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("选择图片", Color.White, Color(0xFF2F5BEA), onPick, Modifier.weight(1f))
                ActionButton(if (syncing) "暂停" else "分析全部", Color(0xFF2F5BEA), Color(0xFFEEF3FF), onAnalyze, Modifier.weight(1f))
                ActionButton(
                    "清空分析", Color(0xFFB55A00), Color(0xFFFFF4E8), onClear,
                    Modifier.weight(1f), enabled = storedCount > 0,
                )
            }
        }
    }
}

@Composable
private fun ActionButton(title: String, textColor: Color, bgColor: Color, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.height(40.dp), colors = ButtonDefaults.buttonColors(containerColor = bgColor), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
        Text(title, fontSize = 13.sp, color = textColor, maxLines = 1)
    }
}

@Composable
private fun GalleryProgressStrip(state: GalleryScanUiState, onCancel: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(0.90f).padding(top = 10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    when (state) { is GalleryScanUiState.Running -> "分析中"; is GalleryScanUiState.Succeeded -> "已完成"; is GalleryScanUiState.Partial -> "部分完成"; is GalleryScanUiState.Failed -> "分析失败"; is GalleryScanUiState.Idle -> "就绪" },
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF202636),
                )
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth().height(6.dp).background(Color(0xFFE8EDF7), RoundedCornerShape(3.dp))) {
                val progress = when (state) { is GalleryScanUiState.Running -> 0.5f; is GalleryScanUiState.Succeeded -> 1f; is GalleryScanUiState.Partial -> 0.8f; else -> 0f }
                Box(modifier = Modifier.fillMaxWidth(progress).height(6.dp).background(Color(0xFF2F5BEA), RoundedCornerShape(3.dp)))
            }
            Spacer(Modifier.height(8.dp))
            when (state) {
                is GalleryScanUiState.Succeeded -> Row {
                    Text("入库 " + state.summary.localStored + " 项", fontSize = 12.sp, color = Color(0xFF667085))
                    if (state.summary.skippedCount > 0) Text(" | 跳过 " + state.summary.skippedCount + " 项", fontSize = 12.sp, color = Color(0xFF667085))
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
private fun GalleryResultsPanel(state: GalleryUiState.Loaded, query: String, selectedFilter: Int, selectedTimeFilter: Int, onSelectFilter: (Int) -> Unit, onSelectTimeFilter: (Int) -> Unit, onOpenItem: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFF2F5FA)), shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("图库结果", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF11182F))
                    Text("共 " + state.items.size + " 项", fontSize = 12.sp, color = Color(0xFF7F8592), modifier = Modifier.padding(top = 3.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            val catGroups = state.items.groupBy { it.category.ifBlank { "未分类" } }
            val categories = catGroups.entries.mapIndexed { i, (cat, items) -> CategoryFilter(cat, i, items.size) }
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { filter ->
                    FilterChipView(label = filter.label + "（" + filter.count + "）", selected = selectedFilter == filter.index, onClick = { onSelectFilter(if (selectedFilter == filter.index) -1 else filter.index) })
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                timeFilterOptions.forEachIndexed { index, label -> FilterChipView(label = label, selected = selectedTimeFilter == index, onClick = { onSelectTimeFilter(index) }) }
            }
            Spacer(Modifier.height(8.dp))
            val filtered = state.items.filter { item ->
                val catMatch = selectedFilter == -1 || categories.getOrNull(selectedFilter)?.label == item.category.ifBlank { "未分类" }
                val q = query.trim()
                val queryMatch = q.isEmpty() || item.title.contains(q, ignoreCase = true) || item.summary.contains(q, ignoreCase = true)
                catMatch && queryMatch
            }
            if (filtered.isEmpty()) {
                EmptyResultBlock(hasItems = state.items.isNotEmpty())
            } else {
                val grouped = filtered.groupBy { it.date.take(10) }.toSortedMap(compareByDescending { it })
                grouped.forEach { (date, items) ->
                    Text(date, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4C5260), modifier = Modifier.padding(top = if (grouped.keys.first() == date) 6.dp else 14.dp))
                    items.forEach { item -> InsightCard(item = item, onClick = { onOpenItem(item.id) }) }
                }
            }
        }
    }
}

@Composable
private fun FilterChipView(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.background(if (selected) Color(0xFF5B68FF) else Color(0xFFF1F4FA), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = if (selected) Color.White else Color(0xFF596071))
    }
}

@Composable
private fun InsightCard(item: GalleryPhotoItem, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(12.dp)) {
        Row(modifier = Modifier.padding(12.dp)) {
            AsyncImage(model = item.uri, contentDescription = item.title, modifier = Modifier.size(92.dp).background(Color(0xFFF2F4F7), RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(item.title.ifBlank { item.id }, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF151A28), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (item.category.isNotBlank()) Text(item.category, fontSize = 11.sp, color = categoryColor(item.category), modifier = Modifier.background(categoryBg(item.category), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                }
                if (item.summary.isNotBlank()) Text(item.summary, fontSize = 12.sp, color = Color(0xFF596273), modifier = Modifier.padding(top = 7.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.details.isNotEmpty()) {
                    Column(modifier = Modifier.padding(top = 6.dp)) {
                        item.details.take(3).forEach { detail -> Text("· " + detail, fontSize = 11.sp, color = Color(0xFF667085), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                if (item.tags.isNotEmpty()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        item.tags.take(3).forEach { tag -> Text(tag, fontSize = 10.sp, color = Color(0xFF667085), modifier = Modifier.background(Color(0xFFF2F4F7), RoundedCornerShape(7.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(item.date, fontSize = 10.sp, color = Color(0xFF858C99), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun EmptyResultBlock(hasItems: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 24.dp, start = 18.dp, end = 18.dp).background(Color(0xFFF7F9FC), RoundedCornerShape(12.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (!hasItems) "暂无图库分析结果" else "当前筛选无结果", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF303746))
        Spacer(Modifier.height(8.dp))
        Text(if (!hasItems) "选择图片或启动全库分析后，这里会展示图片洞察。" else "换一个分类或时间范围看看。", fontSize = 12.sp, color = Color(0xFF7B8393))
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

private fun categoryColor(category: String): Color = when (category) {
    "购物" -> Color(0xFFD97706); "外卖" -> Color(0xFF39BFA3); "娱乐" -> Color(0xFF8066FF)
    "聊天" -> Color(0xFF4B8BFF); "生活" -> Color(0xFFD97706); "社交" -> Color(0xFFD9467D)
    "差旅" -> Color(0xFF0EA5E9); "餐饮" -> Color(0xFF267A54); "出行" -> Color(0xFF2F5BEA)
    "健康" -> Color(0xFF7C4DFF); else -> Color(0xFF2F5BEA)
}

private fun categoryBg(category: String): Color = when (category) {
    "餐饮" -> Color(0xFFEAF8F3); "出行" -> Color(0xFFEAF1FF); "购物" -> Color(0xFFFFF1E7)
    "健康" -> Color(0xFFF0ECFF); else -> Color(0xFFEEF3FF)
}

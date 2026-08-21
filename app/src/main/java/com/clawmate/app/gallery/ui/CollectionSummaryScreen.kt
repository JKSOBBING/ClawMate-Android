package com.clawmate.app.gallery.ui

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clawmate.app.R
import com.clawmate.app.gallery.GalleryComponent
import com.clawmate.app.gallery.collection.IndexedCollectionTab
import com.clawmate.app.gallery.collection.IndexedEventCollectionService
import com.clawmate.app.ui.ClawMateHeader
import com.clawmate.app.ui.theme.ClawMateDesign

/** HarmonyOS CollectionPage equivalent used by the Android 汇总 tab. */
@Composable
fun CollectionSummaryScreen(context: Context, onOpenSettings: () -> Unit = {}) {
    var showGallery by remember { mutableStateOf(false) }
    if (showGallery) {
        GalleryScreen(
            onBack = { showGallery = false },
            onOpenSettings = onOpenSettings,
        )
        return
    }

    val indexVm: CollectionIndexViewModel = viewModel(
        factory = CollectionIndexViewModel.Factory(GalleryComponent.indexedEventReader(context)),
    )
    val selectedTab by indexVm.selectedTab.collectAsState()
    val indexState by indexVm.uiState.collectAsState()
    val detail by indexVm.detail.collectAsState()
    val galleryItems by remember { GalleryComponent.getRepository(context).observeItems() }
        .collectAsState(initial = emptyList())

    val openDetail = detail
    if (openDetail != null) {
        IndexedCollectionGroupDetailScreen(detail = openDetail, onBack = indexVm::closeDetail)
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ClawMateDesign.Colors.PageBackground)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 84.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ClawMateHeader(title = "你的足迹", iconRes = R.drawable.header_footprint)
        SummarySceneTabs(
            selected = selectedTab,
            onSelect = { indexVm.selectTab(it.sceneId) },
        )
        SummaryStatsCard(
            count = when (val state = indexState) {
                is IndexedCollectionUiState.Loaded -> state.indexedEventCount
                else -> galleryItems.size
            },
            status = when (indexState) {
                IndexedCollectionUiState.Refreshing -> "整理中"
                is IndexedCollectionUiState.Loaded -> "已同步"
                is IndexedCollectionUiState.Error -> "同步失败"
                IndexedCollectionUiState.Idle -> "尚未同步"
            },
            syncing = indexState is IndexedCollectionUiState.Refreshing,
            onSync = indexVm::refreshIndex,
        )
        IndexedCollectionPanel(
            vm = indexVm,
            onOpenGroup = { group -> indexVm.openGroup(group.groupId) },
            showTabs = false,
        )
        Spacer(Modifier.height(8.dp))
        SummaryActionCards(
            onOpenGallery = { showGallery = true },
            onOpenSettings = onOpenSettings,
        )
    }
}

@Composable
private fun SummarySceneTabs(
    selected: IndexedCollectionTab,
    onSelect: (IndexedCollectionTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IndexedEventCollectionService.tabs().forEach { tab ->
            val isSelected = tab.sceneId == selected.sceneId
            Surface(
                onClick = { onSelect(tab) },
                color = if (isSelected) summarySceneColor(tab.sceneId) else Color.White,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(38.dp),
            )
            {
                Text(
                    tab.label,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) Color.White else Color(0xFF6D7481),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun SummaryStatsCard(
    count: Int,
    status: String,
    syncing: Boolean,
    onSync: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth(0.86f)
            .height(82.dp)
            .background(Color(0xFFF8FAFE), RoundedCornerShape(16.dp))
            .padding(start = 22.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("已整理", fontSize = 12.sp, color = Color(0xFF737987))
            Text(count.toString(), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8066FF))
        }
        androidx.compose.material3.VerticalDivider(
            modifier = Modifier.height(44.dp),
            color = Color(0xFFE7E9F0),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 18.dp)) {
            Text("最近同步", fontSize = 12.sp, color = Color(0xFF737987))
            Text(status, fontSize = 16.sp, color = Color(0xFF151A28), modifier = Modifier.padding(top = 5.dp))
        }
        Surface(
            onClick = onSync,
            enabled = !syncing,
            color = Color(0xFFF0ECFF),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.size(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(if (syncing) "整理中" else "同步", fontSize = 12.sp, color = Color(0xFF8066FF))
            }
        }
    }
}

internal fun summarySceneColor(sceneId: Int): Color = when (sceneId) {
    0 -> Color(0xFF765BFF)
    1 -> Color(0xFF39BFA3)
    2 -> Color(0xFF8066FF)
    3 -> Color(0xFF4B8BFF)
    else -> Color(0xFF8066FF)
}

@Composable
private fun SummaryActionCards(onOpenGallery: () -> Unit, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth(0.86f),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SummaryActionCard(
            icon = "＋",
            title = "新增采集源",
            description = "连接更多平台与数据源",
            color = Color(0xFF725CFF),
            background = Color(0xFFF1EEFF),
            border = Color(0xFFE5DEFF),
            onClick = onOpenSettings,
        )
        SummaryActionCard(
            icon = "图",
            title = "图库分析",
            description = "相册、截图 OCR 与分类整理",
            color = Color(0xFF3F7FEF),
            background = Color(0xFFEDF4FF),
            border = Color(0xFFDCE9FF),
            onClick = onOpenGallery,
        )
    }
}

@Composable
private fun RowScope.SummaryActionCard(
    icon: String,
    title: String,
    description: String,
    color: Color,
    background: Color,
    border: Color,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.weight(1f).height(100.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, border),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    icon,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .size(32.dp)
                        .background(background, RoundedCornerShape(10.dp)),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Text(
                    title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF151A28),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                Text("›", color = color, fontSize = 20.sp)
            }
            Text(
                description,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = Color(0xFF747C8C),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

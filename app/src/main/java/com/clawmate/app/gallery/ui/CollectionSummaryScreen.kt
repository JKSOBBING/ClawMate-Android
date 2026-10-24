package com.clawmate.app.gallery.ui

import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clawmate.app.R
import com.clawmate.app.gallery.GalleryComponent
import com.clawmate.app.gallery.collection.*
import com.clawmate.app.task.model.WorkflowSceneConfig
import com.clawmate.app.settings.SettingsComponent
import com.clawmate.app.settings.domain.AppSettings
import com.clawmate.app.ui.*
import com.clawmate.app.ui.theme.HarmonyTheme
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** CollectionPage.ets, with the existing Android index as its live data source. */
@Composable
fun CollectionSummaryScreen(context: Context, onOpenSettings: () -> Unit = {}) {
    var showGallery by remember { mutableStateOf(false) }
    if (showGallery) {
        InsetSecondaryPage { GalleryScreen(onBack = { showGallery = false }, onOpenSettings = onOpenSettings) }
        return
    }
    val vm: CollectionIndexViewModel = viewModel(factory = CollectionIndexViewModel.Factory(GalleryComponent.indexedEventReader(context)))
    val tab by vm.selectedTab.collectAsState()
    val state by vm.uiState.collectAsState()
    val detail by vm.detail.collectAsState()
    val settings by remember(context) { SettingsComponent.repository(context).settings }.collectAsState(initial = AppSettings())
    detail?.let {
        InsetSecondaryPage { IndexedCollectionGroupDetailScreen(it, vm::closeDetail) }
        return
    }
    val scroll = rememberScrollState()
    val groups = (state as? IndexedCollectionUiState.Loaded)?.groups.orEmpty()
    var syncingRequested by remember { mutableStateOf(false) }
    var lastSync by rememberSaveable { mutableStateOf("暂无同步") }
    LaunchedEffect(state) {
        if (syncingRequested && state is IndexedCollectionUiState.Loaded) {
            lastSync = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
            syncingRequested = false
        } else if (state is IndexedCollectionUiState.Error) syncingRequested = false
    }
    val sync = { syncingRequested = true; vm.refreshIndex() }
    HarmonyPage(background = HarmonyTheme.background("#F7FAFE"), scrollOffset = { scroll.value.toFloat() }, header = { ClawMateHeader("你的足迹") }) { insets ->
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(insets), horizontalAlignment = Alignment.CenterHorizontally) {
            SummarySceneTabs(tab) { vm.selectTab(it.sceneId) }
            SummaryStatsCard(groups.sumOf { it.eventIds.size }, groups.size,
                if (state is IndexedCollectionUiState.Error) "同步失败" else lastSync,
                state is IndexedCollectionUiState.Refreshing, sync)
            state.let { current ->
                if (current is IndexedCollectionUiState.Error) Text(current.message, fontSize = 12.sp, color = HarmonyTheme.text("#B42318"), modifier = Modifier.fillMaxWidth(.92f).padding(top = 8.dp))
            }
            SummaryCollectionGroups(tab.label + "记录", groups, tab.sceneId) { vm.openGroup(it.groupId) }
            if (settings.wechatAutoSummaryEnabled) WechatCollectionCard(settings, onOpenSettings)
            SummaryActionCards({ showGallery = true }, onOpenSettings)
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
internal fun SummarySceneTabs(selected: IndexedCollectionTab, onSelect: (IndexedCollectionTab) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(44.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        IndexedEventCollectionService.tabs().forEach { tab ->
            val active = tab.sceneId == selected.sceneId
            Column(Modifier.height(42.dp).padding(start = if (tab.sceneId == 0) 8.dp else 0.dp)
                .clickable { onSelect(tab) }.padding(horizontal = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom) {
                Box(Modifier.height(34.dp), contentAlignment = Alignment.Center) {
                    Text(tab.label, fontSize = 14.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = HarmonyTheme.text(if (active) "#6657E8" else "#4E5668"), maxLines = 1)
                }
                Box(Modifier.width(if (active) 26.dp else 0.dp).height(3.dp).background(HarmonyTheme.background("#7669FF"), RoundedCornerShape(2.dp)))
            }
        }
    }
}

@Composable
private fun SummaryStatsCard(count: Int, groupCount: Int, lastSync: String, syncing: Boolean, onSync: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(Modifier.fillMaxWidth(.92f).padding(top = 16.dp).height(128.dp).shadow(6.dp, shape).clip(shape)
        .background(HarmonyTheme.background("#F2F6FF")).border(1.dp, HarmonyTheme.border("#B8FFFFFF"), shape)) {
        if (!isSystemInDarkTheme()) Image(painterResource(R.drawable.home_overview_background_compact), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        Row(Modifier.fillMaxSize().padding(start = 22.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("足迹概览", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = HarmonyTheme.text("#535C70"))
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$count 条", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.text("#6C5CE7"))
                    VerticalDivider(Modifier.padding(horizontal = 15.dp).height(32.dp), color = HarmonyTheme.border("#DCE0EA"))
                    Text("$groupCount 个主题", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = HarmonyTheme.text("#232939"), maxLines = 1)
                }
                Row(Modifier.padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(HarmonyTheme.background("#39C2B3"), CircleShape))
                    Text("最近同步 $lastSync", fontSize = 12.sp, color = HarmonyTheme.text("#727A8B"), modifier = Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(Modifier.size(64.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(HarmonyTheme.background("#DFFFFFFF"))
                .border(1.dp, HarmonyTheme.border("#C9D3DDF2"), CircleShape).clickable(enabled = !syncing, onClick = onSync),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (syncing) CircularProgressIndicator(Modifier.size(22.dp), color = HarmonyTheme.text("#7669FF"))
                else Text("⟳", fontSize = 26.sp, lineHeight = 28.sp, color = HarmonyTheme.text("#7669FF"))
                Text(if (syncing) "整理中" else "同步", fontSize = 12.sp, lineHeight = 18.sp, color = HarmonyTheme.text("#5F6575"))
            }
        }
    }
}

@Composable
internal fun SummaryCollectionGroups(title: String, groups: List<IndexedCollectionGroup>, sceneId: Int,
    onOpen: (IndexedCollectionGroup) -> Unit,
) {
    var page by remember(sceneId) { mutableIntStateOf(0) }
    val pageCount = maxOf(1, (groups.size + 4) / 5)
    val current = page.coerceIn(0, pageCount - 1)
    Column(Modifier.fillMaxWidth(.92f).padding(top = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("共${groups.size}组", fontSize = 13.sp, color = HarmonyTheme.mutedText)
        }
        if (groups.isEmpty()) Column(Modifier.fillMaxWidth().height(92.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("暂无已整理记录", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
            Text("点击同步，整理采集数据及近 7 天未处理的聊天信息", fontSize = 12.sp, color = HarmonyTheme.mutedText, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        } else groups.drop(current * 5).take(5).forEachIndexed { index, group ->
            Row(Modifier.fillMaxWidth().height(86.dp).clickable { onOpen(group) }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp, 86.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(HarmonyTheme.border("#D9DDEA")))
                    Box(Modifier.size(10.dp).background(HarmonyTheme.background("#8173FF"), CircleShape).border(2.dp, HarmonyTheme.border("#FFFFFF"), CircleShape))
                }
                Box(Modifier.size(60.dp).background(HarmonyTheme.background("#F0F3F8"), RoundedCornerShape(14.dp))
                    .border(1.dp, HarmonyTheme.border("#E7EBF2"), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Text("暂无图片", fontSize = 10.sp, color = HarmonyTheme.mutedText, maxLines = 1)
                }
                Column(Modifier.weight(1f).height(72.dp).padding(start = 13.dp, end = 8.dp), verticalArrangement = Arrangement.Center) {
                    Text(group.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOf(group.source, group.time).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp,
                        color = HarmonyTheme.mutedText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
                Text(group.meta, fontSize = 14.sp, color = HarmonyTheme.text("#242A38"), maxLines = 1)
                Text("›", fontSize = 25.sp, color = HarmonyTheme.mutedText, modifier = Modifier.padding(start = 9.dp))
            }
            if (index < minOf(5, groups.size - current * 5) - 1) HorizontalDivider(Modifier.padding(start = 94.dp), color = HarmonyTheme.border("#E8EBF1"))
        }
        if (pageCount > 1) Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            HarmonyButton("上一页", { page-- }, enabled = current > 0, background = Color.Transparent)
            Text("${current + 1}/$pageCount", fontSize = 13.sp, color = HarmonyTheme.mutedText, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            HarmonyButton("下一页", { page++ }, enabled = current < pageCount - 1, background = Color.Transparent)
        }
    }
}

internal fun summarySceneColor(sceneId: Int): Color = WorkflowSceneConfig.memoryScenes
    .firstOrNull { it.sceneId == if (sceneId == 7) 1 else sceneId }
    ?.colorHex?.removePrefix("#")?.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: Color(0xFF8066FF)

@Composable
private fun SummaryActionCards(onOpenGallery: () -> Unit, onOpenSettings: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth(.92f).padding(top = 18.dp).clip(shape).background(HarmonyTheme.surface).border(1.dp, HarmonyTheme.border("#E8ECF3"), shape)) {
        SummaryActionRow("＋", "新增采集源", "连接更多平台与数据源", "#725CFF", "#F1EEFF", onOpenSettings)
        HorizontalDivider(Modifier.padding(start = 64.dp), color = HarmonyTheme.border("#E8EBF2"))
        SummaryActionRow("图", "图库分析", "相册、截图 OCR 与分类整理", "#3F7FEF", "#EDF4FF", onOpenGallery)
    }
}

@Composable
private fun SummaryActionRow(icon: String, title: String, description: String, accent: String, soft: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(68.dp).clickable(onClick = onClick).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).background(HarmonyTheme.background(soft), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Text(icon, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.text(accent))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, maxLines = 1)
            Text(description, fontSize = 11.sp, color = HarmonyTheme.mutedText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        Text("›", fontSize = 22.sp, color = HarmonyTheme.mutedText)
    }
}

@Composable
private fun WechatCollectionCard(settings: AppSettings, onOpenSettings: () -> Unit) {
    var targetMode by rememberSaveable { mutableStateOf(false) }
    var days by rememberSaveable { mutableStateOf(settings.wechatAutoSummaryDays.toString()) }
    var contacts by rememberSaveable { mutableStateOf(settings.wechatAutoSummaryMaxContacts.toString()) }
    var target by rememberSaveable { mutableStateOf(settings.wechatCollectionTargetContact) }
    var unavailable by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth(.92f).padding(top = 18.dp).background(HarmonyTheme.surface, shape).border(1.dp, HarmonyTheme.border("#E8ECF3"), shape).padding(18.dp)) {
        Text("微信消息自动汇总", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
        Text("调用 PC 侧采集服务读取微信会话，保存原始产物并写入 daily-log。", fontSize = 12.sp, color = HarmonyTheme.mutedText, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
        Text("默认 ${settings.wechatAutoSummaryDays} 天 · ${settings.wechatAutoSummaryMaxContacts} 位联系人", fontSize = 12.sp, color = HarmonyTheme.text("#4F68FF"), modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("最近联系人" to false, "指定联系人" to true).forEach { (label, mode) ->
                HarmonyButton(label, { targetMode = mode }, Modifier.weight(1f), height = 38.dp, radius = 10.dp, fontSize = 13.sp,
                    color = if (targetMode == mode) Color.White else HarmonyTheme.text("#4F68FF"), background = HarmonyTheme.background(if (targetMode == mode) "#4F68FF" else "#EEF3FF"))
            }
        }
        @Composable fun field(label: String, value: String, onChange: (String) -> Unit) {
            Text(label, fontSize = 12.sp, color = HarmonyTheme.mutedText, modifier = Modifier.padding(top = 12.dp))
            HarmonyTextField(value, onChange, "", Modifier.fillMaxWidth().padding(top = 6.dp), height = 42.dp, fontSize = 13.sp, singleLine = true)
        }
        field("时间范围（天）", days) { days = it }
        if (targetMode) field("指定联系人", target) { target = it } else field("最近联系人数量", contacts) { contacts = it }
        HarmonyButton("开始采集", { unavailable = true }, Modifier.fillMaxWidth().padding(top = 16.dp), height = 44.dp, radius = 12.dp, fontSize = 15.sp, color = Color.White, background = HarmonyTheme.fill("#4F68FF"))
        if (unavailable) {
            Text("PC 微信采集尚未接入 Android，请在设置中检查采集服务与连接配置。", fontSize = 12.sp, color = HarmonyTheme.text("#B42318"), modifier = Modifier.padding(top = 10.dp))
            HarmonyButton("打开设置", onOpenSettings, background = Color.Transparent)
        }
    }
}

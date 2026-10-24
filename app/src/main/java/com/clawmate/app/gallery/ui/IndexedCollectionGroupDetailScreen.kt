package com.clawmate.app.gallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clawmate.app.gallery.collection.IndexedCollectionEvent
import com.clawmate.app.ui.*
import com.clawmate.app.ui.theme.HarmonyTheme

/** CollectionGroupDetailPage geometry; rows are backed by the real local index. */
@Composable
fun IndexedCollectionGroupDetailScreen(detail: IndexedGroupDetailState, onBack: () -> Unit) {
    val list = rememberLazyListState()
    var selected by remember(detail.group.groupId) { mutableStateOf<IndexedCollectionEvent?>(null) }
    BackHandler(onBack = onBack)
    HarmonyPage(background = HarmonyTheme.background("#F4F8FF"),
        scrollOffset = { if (list.firstVisibleItemIndex > 0) 8f else list.firstVisibleItemScrollOffset.toFloat() },
        header = {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                HarmonyButton("返回", onBack, height = 36.dp, radius = 18.dp, fontSize = 14.sp,
                    color = HarmonyTheme.text("#8066FF"), background = HarmonyTheme.background("#F0ECFF"), horizontalPadding = 12.dp)
                Text(detail.group.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.text("#151A28"),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 12.dp))
                if (!detail.group.local) HarmonyAiBadge(Modifier.padding(start = 8.dp), compact = true)
            }
        }) { insets ->
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = insets.calculateTopPadding(),
            bottom = insets.calculateBottomPadding() + 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (detail.events.isEmpty()) item("empty") {
                Column(Modifier.fillMaxWidth(.86f).height(140.dp).background(HarmonyTheme.surface, RoundedCornerShape(16.dp)),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无已整理记录", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.text("#151A28"))
                    Text("返回采集页后重新同步", fontSize = 12.sp, color = HarmonyTheme.text("#7C8494"), modifier = Modifier.padding(top = 8.dp))
                }
            }
            items(detail.events, key = { it.eventId }) { event ->
                Row(Modifier.padding(bottom = 12.dp).fillMaxWidth(.86f).clip(RoundedCornerShape(12.dp))
                    .background(HarmonyTheme.surface).clickable { selected = event }.padding(12.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(76.dp).background(HarmonyTheme.background("#EEF2F7"), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        Text("暂无图片", fontSize = 12.sp, color = HarmonyTheme.text("#8A92A3"), maxLines = 1)
                    }
                    Column(Modifier.weight(1f).padding(start = 13.dp)) {
                        Text(event.summary.ifBlank { event.normalizedFact.ifBlank { "未命名事件" } }, fontSize = 16.sp,
                            fontWeight = FontWeight.Bold, lineHeight = 22.sp, color = HarmonyTheme.text("#151A28"), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (detail.group.local) Text("本机整理", fontSize = 10.sp, color = HarmonyTheme.text("#7C8494"), modifier = Modifier.padding(top = 6.dp))
                        else HarmonyAiBadge(Modifier.padding(top = 6.dp), compact = true)
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                            Text(event.date.ifBlank { "日期未知" }, fontSize = 12.sp, color = HarmonyTheme.text("#7C8494"),
                                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("未关联图片", fontSize = 12.sp, color = HarmonyTheme.text("#8066FF"), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
    selected?.let { event ->
        HarmonyDetailModal(event.summary.ifBlank { "事件详情" }, event.source, { selected = null }, aiGenerated = !detail.group.local) {
            HarmonyDetailSection("来源图片")
            Text("当前索引未关联来源图片", fontSize = 12.sp, color = HarmonyTheme.text("#7C8494"), modifier = Modifier.padding(top = 8.dp))
            HarmonyDetailSection("详情")
            SelectionContainer {
                Text(event.normalizedFact.ifBlank { event.summary }, fontSize = 13.sp, lineHeight = 20.sp,
                    color = HarmonyTheme.text("#303746"), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        .background(HarmonyTheme.background("#F7F9FC"), RoundedCornerShape(10.dp)).padding(10.dp))
            }
            HarmonyDetailSection("基础信息")
            HarmonyDetailInfo("日期", event.date.ifBlank { "未知" })
            HarmonyDetailInfo("来源", event.source.ifBlank { "未知" })
            HarmonyDetailInfo("图片", "未关联图片")
            HarmonyDetailInfo("ID", event.eventId)
            Spacer(Modifier.height(8.dp))
        }
    }
}

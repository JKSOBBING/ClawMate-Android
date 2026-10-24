package com.clawmate.app.home.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import com.clawmate.app.home.domain.HomeRecommendationViewModel
import com.clawmate.app.ui.*
import com.clawmate.app.ui.theme.HarmonyTheme

@Composable
internal fun HomeRemindersSection(list: RecommendationListUi, onToggle: (Boolean) -> Unit,
    onPrevious: () -> Unit, onNext: () -> Unit, onCompleted: (String, Boolean) -> Unit, onOpenTasks: () -> Unit,
) {
    var detail by remember { mutableStateOf<HomeRecommendationViewModel?>(null) }
    Column(Modifier.fillMaxWidth(.92f).padding(top = 22.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("为你提醒", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, modifier = Modifier.weight(1f))
            if (list.recommendations.isNotEmpty()) {
                AiBadge(); Spacer(Modifier.width(8.dp))
                listOf("未完成" to false, "全部" to true).forEachIndexed { index, option ->
                    if (index > 0) Spacer(Modifier.width(8.dp))
                    val selected = list.showAll == option.second
                    Column(Modifier.width(IntrinsicSize.Min).height(28.dp).clickable { onToggle(option.second) }.padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(option.first, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = HarmonyTheme.text(if (selected) "#665AE1" else "#7B8190"))
                        }
                        Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) HarmonyTheme.border("#7669FF") else Color.Transparent))
                    }
                }
            }
        }
        val page = list.page
        if (page.items.isEmpty()) {
            Box(Modifier.padding(top = 12.dp)) {
                val completed = list.recommendations.isNotEmpty()
                HomeEmptyHint(if (completed) "提醒都处理好啦，歇一会儿～" else "派我去手机里自主探索吧～",
                    onOpen = { if (completed) onToggle(true) else onOpenTasks() }, link = if (completed) "查看已完成" else "去派任务")
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 8.dp)) {
                page.items.forEachIndexed { index, item ->
                    ReminderRow(item, list.showAll) { detail = item }
                    if (index < page.items.lastIndex) HorizontalDivider(Modifier.padding(start = 73.dp), color = HarmonyTheme.border("#E9ECF3"))
                }
            }
            if (page.hasPrevious || page.hasNext) Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                HarmonyButton("‹", onPrevious, Modifier.width(32.dp), enabled = page.hasPrevious, fontSize = 20.sp, horizontalPadding = 0.dp)
                Text(page.pageLabel, fontSize = 13.sp, color = HarmonyTheme.mutedText, modifier = Modifier.weight(1f))
                HarmonyButton("›", onNext, Modifier.width(32.dp), enabled = page.hasNext, fontSize = 20.sp, horizontalPadding = 0.dp)
            }
        }
    }
    detail?.let { selected -> ReminderDetail(selected, onClose = { detail = null }, onCompleted = { value ->
        detail = selected.copy(completed = value); onCompleted(selected.id, value)
    }, onOpenTasks = onOpenTasks) }
}

@Composable
private fun ReminderRow(item: HomeRecommendationViewModel, showAll: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).clickable(onClick = onClick)
        .alpha(if (showAll && item.completed) .65f else 1f).padding(start = 4.dp, end = 2.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(17.dp)).background(HarmonyTheme.surface), contentAlignment = Alignment.Center) {
            if (item.thumbnailUri.isNotBlank()) AsyncImage(item.thumbnailUri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else HomeCategoryIcon(homeDomainIcon(item.domain), 48.dp)
        }
        Column(Modifier.weight(1f).padding(start = 13.dp, end = 8.dp)) {
            Row {
                Text(if (item.completed) "已完成" else item.tag, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = HarmonyTheme.text(item.color))
                if (item.meta.isNotBlank()) Text(" · ${item.meta}", fontSize = 10.sp, color = HarmonyTheme.mutedText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(item.title, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold,
                color = HarmonyTheme.text(if (showAll && item.completed) "#A0A7B5" else "#151A28"), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            if (item.desc.isNotBlank()) Text(item.desc, fontSize = 11.sp, color = HarmonyTheme.text("#747C8E"),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Text("›", fontSize = 25.sp, color = HarmonyTheme.mutedText, modifier = Modifier.width(22.dp))
    }
}

@Composable
private fun ReminderDetail(item: HomeRecommendationViewModel, onClose: () -> Unit, onCompleted: (Boolean) -> Unit, onOpenTasks: () -> Unit) {
    var draft by remember(item.id) { mutableStateOf(item.agentTask.take(60)) }
    var unavailable by remember { mutableStateOf(false) }
    HarmonyModal(onClose) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (item.completed) "已完成" else item.tag, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(HarmonyTheme.fill(item.color), RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 5.dp))
                Spacer(Modifier.width(8.dp)); AiBadge(); Spacer(Modifier.weight(1f))
                HarmonyButton("关闭", onClose, background = Color.Transparent, color = HarmonyTheme.mutedText, horizontalPadding = 0.dp)
            }
            Text(item.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, modifier = Modifier.padding(top = 14.dp))
            if (item.imageRefs.isEmpty()) Box(Modifier.fillMaxWidth().height(86.dp), contentAlignment = Alignment.Center) {
                Text("暂无图片", fontSize = 13.sp, color = HarmonyTheme.mutedText)
            } else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 14.dp)) {
                item.imageRefs.forEach { image ->
                    Box(Modifier.padding(end = 10.dp).size(128.dp, 260.dp).background(HarmonyTheme.soft, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        if (image.available && image.uri.isNotBlank()) AsyncImage(image.uri, image.label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        else Text("图片暂不可显示", color = HarmonyTheme.mutedText, fontSize = 12.sp)
                    }
                }
            }
            Text(item.desc.ifBlank { "暂无描述" }, fontSize = 14.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(top = 14.dp))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(item.completed, onCompleted, modifier = Modifier.size(32.dp), colors = CheckboxDefaults.colors(checkedColor = HarmonyTheme.background(item.color)))
                Text("已完成", fontSize = 13.sp, color = HarmonyTheme.text(if (item.completed) item.color else "#6D7481"),
                    modifier = Modifier.clickable { onCompleted(!item.completed) }.padding(vertical = 6.dp))
            }
            if (item.agentTask.isNotBlank()) {
                Text("执行任务", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, modifier = Modifier.padding(top = 18.dp))
                HarmonyTextField(draft, { draft = it }, "一句话，只做一件事（最多60字）", Modifier.fillMaxWidth().padding(top = 10.dp), maxLength = 60)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HarmonyButton("执行", { unavailable = true }, Modifier.weight(1f), height = 44.dp, radius = 14.dp, fontSize = 15.sp, color = Color.White, background = HarmonyTheme.fill(item.color))
                    HarmonyButton("与千问协同", { unavailable = true }, Modifier.weight(1f), height = 44.dp, radius = 14.dp, fontSize = 15.sp, color = Color.White, background = HarmonyTheme.fill(item.color))
                }
                if (unavailable) {
                    Text("自由任务执行及千问协同尚未接入 Android，可在任务页使用已有流程。", fontSize = 12.sp, color = HarmonyTheme.text("#B42318"), modifier = Modifier.padding(top = 8.dp))
                    HarmonyButton("查看可用流程", { onClose(); onOpenTasks() }, background = Color.Transparent)
                }
            }
        }
    }
}

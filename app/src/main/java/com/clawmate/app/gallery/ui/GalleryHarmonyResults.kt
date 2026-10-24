package com.clawmate.app.gallery.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.clawmate.app.gallery.model.GalleryPhotoItem
import com.clawmate.app.ui.*
import com.clawmate.app.ui.theme.HarmonyTheme

@Composable
internal fun HarmonyGalleryHeader(onBack: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        HarmonyButton("<", onBack, Modifier.width(38.dp), height = 38.dp, radius = 10.dp, fontSize = 20.sp,
            background = HarmonyTheme.background("#EEF3FF"), color = HarmonyTheme.text("#2F5BEA"), horizontalPadding = 0.dp)
        Text("图库分析", fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, color = HarmonyTheme.text("#172033"),
            textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        HarmonyButton("⚙", onSettings, Modifier.width(38.dp), height = 38.dp, radius = 10.dp, fontSize = 20.sp,
            background = HarmonyTheme.background("#EEF3FF"), color = HarmonyTheme.text("#2F5BEA"), horizontalPadding = 0.dp)
    }
}

@Composable
internal fun HarmonyGalleryToolbar(items: List<GalleryPhotoItem>, running: Boolean, onPick: () -> Unit,
    onRead: () -> Unit, onClear: () -> Unit, onCancel: () -> Unit,
) {
    Column(Modifier.padding(top = 14.dp).fillMaxWidth(.9f).background(HarmonyTheme.surface, RoundedCornerShape(12.dp)).padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("已分析", fontSize = 12.sp, color = HarmonyTheme.text("#737987"))
                Text("${items.count { it.hasCompletedAnalysis() }}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.text("#2F5BEA"))
            }
            Box(Modifier.width(1.dp).height(38.dp).background(HarmonyTheme.border("#E5E8F0")))
            Column(Modifier.weight(1f).padding(start = 18.dp)) {
                Text("最近分析", fontSize = 12.sp, color = HarmonyTheme.text("#737987"))
                Text(if (running) "处理中" else "未记录", fontSize = 16.sp, color = HarmonyTheme.text("#202636"), modifier = Modifier.padding(top = 4.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HarmonyButton("选择图片", onPick, Modifier.weight(1f), height = 38.dp, radius = 9.dp,
                color = HarmonyTheme.text("#FFFFFF"), background = HarmonyTheme.background("#2F5BEA"), horizontalPadding = 0.dp)
            HarmonyButton(if (running) "读取中" else "批量读图", onRead, Modifier.weight(1f), height = 38.dp, radius = 9.dp,
                color = HarmonyTheme.text("#2F5BEA"), background = HarmonyTheme.background("#EEF3FF"), enabled = !running, horizontalPadding = 0.dp)
            HarmonyButton(if (running) "暂停" else "清空分析", if (running) onCancel else onClear, Modifier.weight(1f), height = 38.dp, radius = 9.dp,
                color = HarmonyTheme.text("#B55A00"), background = HarmonyTheme.background("#FFF4E8"), enabled = running || items.isNotEmpty(), horizontalPadding = 0.dp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HarmonyGalleryResults(items: List<GalleryPhotoItem>, selectedCategory: Int, selectedTime: Int,
    onCategory: (Int) -> Unit, onTime: (Int) -> Unit, onOpen: (String) -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    val timeItems = galleryFrontendTimeResults(items, selectedTime, now)
    val categories = galleryFrontendCategories(timeItems)
    val effectiveCategory = selectedCategory.takeIf { requested -> categories.any { it.index == requested } } ?: 0
    LaunchedEffect(effectiveCategory, selectedCategory) { if (selectedCategory != effectiveCategory) onCategory(effectiveCategory) }
    val filtered = if (effectiveCategory == 0) timeItems else timeItems.filter { harmonyGalleryCategory(it.category) == harmonyGalleryCategories[effectiveCategory] }
    var limit by remember(selectedCategory, selectedTime, items.size) { mutableIntStateOf(10) }
    Column(Modifier.padding(top = 14.dp).fillMaxWidth(.9f).background(HarmonyTheme.background("#F2F5FA"), RoundedCornerShape(12.dp)).padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("图库结果", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
                Text("共 ${filtered.size} 项", fontSize = 12.sp, color = HarmonyTheme.text("#7F8592"), modifier = Modifier.padding(top = 3.dp))
            }
            if (items.any { it.hasCompletedAnalysis() }) HarmonyAiBadge(compact = true)
        }
        FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            categories.forEach { category -> HarmonyButton("${category.label}（${category.count}）", { onCategory(category.index) },
                Modifier.padding(end = 8.dp, bottom = 8.dp), height = 36.dp, radius = 18.dp, fontSize = 13.sp,
                bold = effectiveCategory == category.index, color = HarmonyTheme.text(if (effectiveCategory == category.index) "#FFFFFF" else "#596071"),
                background = HarmonyTheme.background(if (effectiveCategory == category.index) "#5B68FF" else "#F1F4FA"), horizontalPadding = 14.dp) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp).height(38.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部时间", "近7天", "近30天", "今年").forEachIndexed { index, label -> HarmonyButton(label, { onTime(index) },
                height = 34.dp, radius = 17.dp, fontSize = 13.sp, bold = selectedTime == index,
                color = HarmonyTheme.text(if (selectedTime == index) "#151A28" else "#596071"),
                background = HarmonyTheme.background(if (selectedTime == index) "#E8EEFF" else "#F1F4FA"), horizontalPadding = 12.dp) }
        }
        if (filtered.isEmpty()) Column(Modifier.padding(top = 16.dp).fillMaxWidth()
            .background(HarmonyTheme.background("#F7F9FC"), RoundedCornerShape(12.dp))
            .padding(start = 18.dp, end = 18.dp, top = 28.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (items.isEmpty()) "暂无图库分析结果" else "当前筛选无结果", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.text("#303746"))
            Text(if (items.isEmpty()) "选择图片，或批量读取最近照片，开始整理图片洞察。" else "换一个分类或时间范围看看。",
                fontSize = 12.sp, color = HarmonyTheme.text("#7B8393"), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        }
        var previousDate = ""
        filtered.take(limit).forEachIndexed { index, item ->
            val date = item.date.ifBlank { "日期未知" }
            if (date != previousDate) Row(Modifier.fillMaxWidth().padding(top = if (index == 0) 6.dp else 14.dp).height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(date, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.text("#4C5260"))
            }
            HarmonyGalleryInsightCard(item) { onOpen(item.id) }
            previousDate = date
        }
        if (filtered.size > limit) HarmonyButton("加载更多", { limit += 10 }, Modifier.fillMaxWidth().padding(top = 12.dp),
            height = 40.dp, radius = 12.dp, fontSize = 13.sp, color = HarmonyTheme.text("#4F68FF"), background = HarmonyTheme.background("#EEF4FF"))
    }
}

@Composable
private fun HarmonyGalleryInsightCard(item: GalleryPhotoItem, onClick: () -> Unit) {
    Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(HarmonyTheme.surface)
        .clickable(onClick = onClick).padding(12.dp)) {
        AsyncImage(item.uri, item.title, Modifier.size(92.dp).clip(RoundedCornerShape(8.dp))
            .background(HarmonyTheme.background("#F2F4F7")), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(item.title.ifBlank { "图片记录" }, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.text("#151A28"), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (item.hasCompletedAnalysis()) HarmonyAiBadge(Modifier.padding(horizontal = 8.dp), compact = true)
                val tone = galleryCategoryTone(item.category)
                Text(harmonyGalleryCategory(item.category), fontSize = 11.sp, color = HarmonyTheme.text(tone.foreground),
                    modifier = Modifier.background(HarmonyTheme.background(tone.background), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
            }
            if (item.summary.isNotBlank()) Text(item.summary, fontSize = 12.sp, color = HarmonyTheme.text("#596273"), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
            item.details.take(3).forEach { Text("· $it", fontSize = 11.sp, color = HarmonyTheme.text("#667085"), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
            Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                item.tags.take(3).forEach { Text(it, fontSize = 10.sp, color = HarmonyTheme.text("#667085"),
                    modifier = Modifier.background(HarmonyTheme.background("#F2F4F7"), RoundedCornerShape(7.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.date, fontSize = 10.sp, color = HarmonyTheme.text("#858C99"), modifier = Modifier.weight(1f))
                Text(GalleryDetailLabels.analysisStateLabel(item.analysisState), fontSize = 10.sp,
                    color = HarmonyTheme.text(if (item.hasCompletedAnalysis()) "#148F73" else "#7B8290"),
                    modifier = Modifier.background(HarmonyTheme.background("#F2F4F7"), RoundedCornerShape(7.dp)).padding(horizontal = 7.dp, vertical = 3.dp))
            }
        }
    }
}

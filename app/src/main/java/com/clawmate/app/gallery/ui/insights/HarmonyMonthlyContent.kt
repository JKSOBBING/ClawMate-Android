package com.clawmate.app.gallery.ui.insights

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.clawmate.app.gallery.insights.GalleryMonthInsight
import com.clawmate.app.gallery.ui.galleryTagTone
import com.clawmate.app.gallery.ui.galleryMonthBarFraction
import com.clawmate.app.ui.HarmonyButton
import com.clawmate.app.ui.theme.HarmonyTheme

@Composable
internal fun HarmonyMonthlyContent(state: GalleryInsightsUiState.Loaded, onOpen: (String) -> Unit,
    onGenerate: (GalleryMonthInsight) -> Unit, serviceContent: @Composable (GalleryMonthInsight) -> Unit,
) {
    var selectedKey by remember { mutableStateOf("") }
    var services by remember { mutableStateOf(false) }
    val selected = state.insights.firstOrNull { it.key == selectedKey } ?: state.insights.first()
    Column(Modifier.padding(top = 18.dp).fillMaxWidth(.86f).background(HarmonyTheme.surface, RoundedCornerShape(18.dp)).padding(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("月度洞察", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, modifier = Modifier.weight(1f))
            Text("${state.insights.size} 个月", fontSize = 13.sp, color = HarmonyTheme.text("#7F8592"))
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp).height(56.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            state.insights.forEach { insight ->
                val active = selected.key == insight.key
                Column(Modifier.height(48.dp).clip(RoundedCornerShape(12.dp))
                    .background(HarmonyTheme.background(if (active) "#4B8BFF" else "#F1F4FA"))
                    .clickable { selectedKey = insight.key }.padding(horizontal = 13.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(insight.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.text(if (active) "#FFFFFF" else "#4C5260"))
                    Text("${insight.totalCount} 张", fontSize = 11.sp, color = HarmonyTheme.text(if (active) "#EAF1FF" else "#7C8494"), modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text(selected.label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.text("#151A28"))
                Text("${selected.totalCount} 张已存记录", fontSize = 12.sp, color = HarmonyTheme.text("#7C8494"), modifier = Modifier.padding(top = 3.dp))
            }
            HarmonyButton("AI总结", { services = true; onGenerate(selected) }, Modifier.width(74.dp), height = 34.dp, radius = 10.dp,
                color = HarmonyTheme.text("#4B8BFF"), background = HarmonyTheme.background("#EEF4FF"), horizontalPadding = 0.dp)
        }
        Text(selected.summary, fontSize = 13.sp, lineHeight = 20.sp, color = HarmonyTheme.text("#596273"),
            modifier = Modifier.padding(top = 10.dp), maxLines = 8)
        selected.categoryCounts.take(4).forEach { category ->
            Column(Modifier.fillMaxWidth().padding(top = 9.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(category.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.text("#2F3645"), modifier = Modifier.weight(1f))
                    Text("${category.count} 张", fontSize = 12.sp, color = HarmonyTheme.text("#7C8494"))
                }
                Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(7.dp).background(HarmonyTheme.background("#EEF0F5"), RoundedCornerShape(4.dp))) {
                    Box(Modifier.fillMaxWidth(galleryMonthBarFraction(category.count, selected.totalCount))
                        .height(7.dp).background(HarmonyTheme.background(galleryTagTone(category.label).foreground), RoundedCornerShape(4.dp)))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp).height(72.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            selected.representativeItems.forEach { image -> AsyncImage(image.uri, image.title,
                Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).clickable { onOpen(image.id) }, contentScale = ContentScale.Crop) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp).height(34.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            selected.categoryCounts.take(6).forEach { category -> Text("${category.label} ${category.count}", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = HarmonyTheme.text(galleryTagTone(category.label).foreground), maxLines = 1, modifier = Modifier.padding(top = 7.dp)
                    .background(HarmonyTheme.background(galleryTagTone(category.label).background), RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 5.dp)) }
        }
        if (services) {
            serviceContent(selected)
            HarmonyButton("收起服务详情", { services = false }, Modifier.padding(top = 8.dp))
        }
    }
}

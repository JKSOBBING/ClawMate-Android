package com.clawmate.app.gallery.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clawmate.app.gallery.insights.GalleryMonthCategoryCount
import com.clawmate.app.gallery.insights.GalleryMonthInsight
import com.clawmate.app.gallery.insights.GalleryMonthlyInsightCloudState
import com.clawmate.app.gallery.model.GalleryPhotoItem

/**
 * Real Gallery monthly-insights panel (Phase 15), ported from the HarmonyOS
 * `pages/gallery/GalleryPage.ets` `MonthlyInsightsPanel`.
 *
 * Shows only what is really derivable from stored [com.clawmate.app.gallery.model
 * .GalleryPhotoItem]s: month chips, per-month total, deterministic category
 * counts (with a bar meter), the local deterministic summary, up to 6
 * representative items, and a truthful NOT_CONFIGURED notice for the cloud AI
 * summary / embedding. Loading / empty / error states are honest and never
 * fabricate an insight or a summary.
 */
@Composable
fun MonthlyInsightsPanel(
    state: GalleryInsightsUiState,
    onOpenItem: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "月度洞察",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text("仅基于已存图库数据", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            when (state) {
                is GalleryInsightsUiState.Loading -> InsightsLoading()
                is GalleryInsightsUiState.Empty -> InsightsEmpty()
                is GalleryInsightsUiState.Error -> InsightsError(state.message)
                is GalleryInsightsUiState.Loaded -> InsightsLoaded(
                    state = state,
                    onOpenItem = onOpenItem,
                )
            }
        }
    }
}

@Composable
private fun InsightsLoading() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 16.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("正在从已存数据计算月度洞察…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InsightsEmpty() {
    Column(Modifier.padding(vertical = 16.dp)) {
        Text("还没有图库条目，暂无月度洞察", style = MaterialTheme.typography.bodyMedium)
        Text(
            "先选择并保存一些图片，本面板只会基于真实入库数据汇总，不会伪造洞察。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InsightsError(message: String) {
    Column(Modifier.padding(vertical = 16.dp)) {
        Text(
            "月度洞察加载失败",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun InsightsLoaded(
    state: GalleryInsightsUiState.Loaded,
    onOpenItem: (String) -> Unit,
) {
    val insights = state.insights
    if (insights.isEmpty()) {
        InsightsEmpty()
        return
    }
    var selectedKey by remember(insights) { mutableStateOf(insights.first().key) }
    val selected = insights.firstOrNull { it.key == selectedKey } ?: insights.first()

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${insights.size} 个月", style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(8.dp))

    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(insights, key = { it.key }) { insight ->
            MonthChip(
                insight = insight,
                selected = insight.key == selected.key,
                onClick = { selectedKey = insight.key },
            )
        }
    }
    Spacer(Modifier.height(12.dp))

    MonthInsightDetail(
        insight = selected,
        aiSummary = state.aiSummary,
        embedding = state.embedding,
        onOpenItem = onOpenItem,
    )
}

@Composable
private fun MonthChip(
    insight: GalleryMonthInsight,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val container = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val content = if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(container, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
    ) {
        Text(insight.label, style = MaterialTheme.typography.labelLarge, color = content)
        Text(
            "${insight.totalCount} 张",
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
    }
}

@Composable
private fun MonthInsightDetail(
    insight: GalleryMonthInsight,
    aiSummary: GalleryMonthlyInsightCloudState,
    embedding: GalleryMonthlyInsightCloudState,
    onOpenItem: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(insight.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "${insight.totalCount} 张分析结果",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        Text(insight.summary, style = MaterialTheme.typography.bodyMedium)

        if (insight.categoryCounts.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("标签分布", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            insight.categoryCounts.take(4).forEach { item ->
                CategoryBar(item = item, total = insight.totalCount)
            }
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(insight.categoryCounts.take(6), key = { "cat_${it.label}" }) { item ->
                    CategoryChip(item)
                }
            }
        }

        if (insight.representativeItems.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("代表图片", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(insight.representativeItems, key = { it.id }) { item ->
                    RepresentativeCard(item = item, onClick = { onOpenItem(item.id) })
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        CloudInsetsNotice(aiSummary = aiSummary, embedding = embedding)

        Spacer(Modifier.height(4.dp))
        Text(
            "以上洞察由已存图库数据计算得出；云端 AI 总结与向量检索未配置，不会生成或伪装任何总结。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CategoryBar(item: GalleryMonthCategoryCount, total: Int) {
    val fraction = if (total <= 0) 0f else item.count.toFloat() / total.toFloat()
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row {
            Text(
                item.label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text("${item.count} 张", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colorFor(item.label)),
            )
        }
    }
}

@Composable
private fun CategoryChip(item: GalleryMonthCategoryCount) {
    Text(
        "${item.label} ${item.count}",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = colorFor(item.label),
        modifier = Modifier
            .background(colorBgFor(item.label), RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
private fun RepresentativeCard(item: GalleryPhotoItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f),
        ),
        modifier = Modifier.width(132.dp).padding(vertical = 3.dp),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                item.title.ifBlank { item.id },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            val tags = tagsText(item)
            if (tags.isNotEmpty()) {
                Text(
                    tags,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CloudInsetsNotice(
    aiSummary: GalleryMonthlyInsightCloudState,
    embedding: GalleryMonthlyInsightCloudState,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("云端 AI 总结：", style = MaterialTheme.typography.bodySmall)
        Text(
            stateText(aiSummary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("向量检索：", style = MaterialTheme.typography.bodySmall)
        Text(
            stateText(embedding),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun stateText(state: GalleryMonthlyInsightCloudState): String = when (state) {
    is GalleryMonthlyInsightCloudState.NotConfigured -> "未配置"
    is GalleryMonthlyInsightCloudState.Unsupported -> "不支持"
    is GalleryMonthlyInsightCloudState.Ready -> "可用"
}

private fun tagsText(item: GalleryPhotoItem): String =
    item.tags.joinToString("、").ifBlank { item.category.ifBlank { "" } }

private val PALETTE = listOf(
    Color(0xFF4B8BFF), Color(0xFF39BFA3), Color(0xFFFF8A3D),
    Color(0xFF765BFF), Color(0xFFD14B7A), Color(0xFF667085),
)

private val PALETTE_BG = listOf(
    Color(0xFFEAF1FF), Color(0xFFEAF8F3), Color(0xFFFFF1E7),
    Color(0xFFF0ECFF), Color(0xFFFCECF3), Color(0xFFF2F4F7),
)

private fun tagIndex(label: String): Int {
    val text = label.ifEmpty { "tag" }
    var hash = 0
    var i = 0
    while (i < text.length) {
        hash = (hash + text.codePointAt(i)) % 6
        i++
    }
    return hash
}

private fun colorFor(label: String): Color = PALETTE[tagIndex(label)]
private fun colorBgFor(label: String): Color = PALETTE_BG[tagIndex(label)]

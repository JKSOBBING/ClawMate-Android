package com.clawmate.app.home.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.clawmate.app.home.data.HomeProfileSource
import com.clawmate.app.home.data.HomeRecRepository
import com.clawmate.app.home.domain.AvatarStatus
import com.clawmate.app.home.domain.HomeDomainViewModel
import com.clawmate.app.home.domain.HomeProfileViewModel
import com.clawmate.app.home.domain.HomeRecommendationViewModel
import com.clawmate.app.home.domain.HomeUiState
import com.clawmate.app.home.domain.recommendationColor
import com.clawmate.app.profile.avatar.ui.ProfileAvatarHost

/**
 * Home tab — real Compose port of `pages/home/HomePage.ets`.
 *
 * Shows the honest profile lifecycle (loading / error / empty / ready), the
 * avatar section (NOT_CONFIGURED on Android), the four persona pillars
 * (理解你/守护你/陪伴你/成就你), and the recommendation list with completion
 * toggles, 未完成/全部 filter, and pagination. Avatar generation is never
 * fabricated; it stays DISABLED until a real Android implementation exists.
 */
@Composable
fun HomeScreen(
    homeRecRepository: HomeRecRepository,
    profileSource: HomeProfileSource = com.clawmate.app.home.data.EmptyHomeProfileSource,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModelFactory(homeRecRepository, profileSource)),
) {
    val state by viewModel.state.collectAsState()
    val list by viewModel.list.collectAsState()

    // Phase 12: the avatar section opens the real Profile / Avatar screen
    // (local profile editing + honest generation boundary) instead of staying a
    // dead disabled button.
    var showAvatar by remember { mutableStateOf(false) }
    if (showAvatar) {
        ProfileAvatarHost(onClose = { showAvatar = false })
        return
    }

    when (val s = state) {
        is HomeUiState.Loading -> LoadingBox()
        is HomeUiState.Error -> ErrorBox(message = s.message)
        is HomeUiState.Ready -> HomeContent(
            profile = s.profile,
            list = list,
            onToggleShowAll = viewModel::setShowAll,
            onPrevious = viewModel::previousPage,
            onNext = viewModel::nextPage,
            onSetCompleted = viewModel::setRecommendationCompleted,
            onOpenAvatar = { showAvatar = true },
        )
    }
}

@Composable
private fun LoadingBox() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                "加载画像中…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun ErrorBox(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("首页加载失败", style = MaterialTheme.typography.titleMedium)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun HomeContent(
    profile: HomeProfileViewModel,
    list: RecommendationListUi,
    onToggleShowAll: (Boolean) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSetCompleted: (String, Boolean) -> Unit,
    onOpenAvatar: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(Color(0xFFF4F8FF))
            .padding(bottom = 24.dp),
    ) {
        Text(
            text = "你的智伴",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1B2030),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        )

        // --------------------------------------------------- avatar section
        AvatarSectionCard(profile, onOpenAvatar)

        // ------------------------------------------- completeness + pillars
        CompletenessCard(completeness = profile.completeness)
        PillarsRow(domains = profile.domains)

        // --------------------------------------------------- recommendations
        RecommendationsSection(
            list = list,
            onToggleShowAll = onToggleShowAll,
            onPrevious = onPrevious,
            onNext = onNext,
            onSetCompleted = onSetCompleted,
        )
    }
}

@Composable
private fun AvatarSectionCard(profile: HomeProfileViewModel, onOpenAvatar: () -> Unit) {
    val avatar = profile.avatar
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEDF5FF)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (avatar.hasAvatarImage && avatar.imageUri.isNotBlank()) {
                AsyncImage(
                    model = avatar.imageUri,
                    contentDescription = "数字分身",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(270.dp)
                        .background(Color(0xFFE8F1FF), RoundedCornerShape(20.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(270.dp)
                        .background(Color(0xFFE8F1FF), RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(avatar.statusLabel, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "数字分身生成功能未在此设备启用",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF6F7684),
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(if (avatar.status == AvatarStatus.CURRENT) Color(0xFF7669FF) else Color(0xFFB1B7C6), RoundedCornerShape(4.dp)),
                )
                Text(
                    avatar.statusLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF1B2030),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                )
                Text(
                    avatar.updatedLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF7B8190),
                    modifier = Modifier.padding(start = 10.dp),
                )
                Text(">", fontSize = 17.sp, color = Color(0xFF8B909A), modifier = Modifier.padding(start = 6.dp))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Button(
                    onClick = onOpenAvatar,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7669FF)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("数字分身 · 管理本地画像", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun CompletenessCard(completeness: Int) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("画像完整度", style = MaterialTheme.typography.bodyLarge, color = Color(0xFF272C38))
            LinearProgressIndicator(
                progress = { completeness / 100f },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .height(6.dp),
                color = Color(0xFF8066FF),
                trackColor = Color(0xFFE6E8F3),
            )
            Text(
                "$completeness%",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF8066FF),
            )
        }
    }
}

@Composable
private fun PillarsRow(domains: List<HomeDomainViewModel>) {
    val pillars = domains.take(4)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(82.dp)
                .padding(horizontal = 12.dp),
        ) {
            pillars.forEachIndexed { index, item ->
                PillarCell(item = item, modifier = Modifier.weight(1f))
                if (index < pillars.size - 1) {
                    HorizontalDivider(
                        modifier = Modifier
                            .width(1.dp)
                            .height(34.dp)
                            .align(Alignment.CenterVertically),
                        color = Color(0xFFE5E7EF),
                    )
                }
            }
        }
    }
}

@Composable
private fun PillarCell(item: HomeDomainViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().background(Color.Transparent, RoundedCornerShape(18.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = item.title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = parseHomeColor(item.color),
        )
        Text(
            text = item.desc,
            fontSize = 11.sp,
            color = Color(0xFF818795),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun RecommendationsSection(
    list: RecommendationListUi,
    onToggleShowAll: (Boolean) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSetCompleted: (String, Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "为你推荐",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF11182F),
            )
            FilterChip(
                label = "未完成",
                selected = !list.showAll,
                onClick = { onToggleShowAll(false) },
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
            )
            Text("", modifier = Modifier.width(8.dp))
            FilterChip(
                label = "全部",
                selected = list.showAll,
                onClick = { onToggleShowAll(true) },
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        if (list.recommendations.isEmpty()) {
            Text(
                "暂无待办事项",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF7C8494),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            return
        }

        val page = list.page
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            page.items.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rowItems.forEach { item ->
                        RecommendationCard(
                            item = item,
                            modifier = Modifier.weight(1f),
                            onSetCompleted = onSetCompleted,
                        )
                    }
                    if (rowItems.size == 1) {
                        Box(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onPrevious,
                enabled = page.hasPrevious,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF0ECFF)),
                modifier = Modifier.padding(end = 8.dp),
            ) {
                Text("上一页", fontSize = 12.sp, color = if (page.hasPrevious) Color(0xFF8066FF) else Color(0xFFA7ADBA))
            }
            Text(
                page.pageLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF6D7481),
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onNext,
                enabled = page.hasNext,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF0ECFF)),
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text("下一页", fontSize = 12.sp, color = if (page.hasNext) Color(0xFF8066FF) else Color(0xFFA7ADBA))
            }
        }
        Text("", modifier = Modifier.padding(bottom = 24.dp))
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(15.dp),
        color = if (selected) Color(0xFF8066FF) else Color(0xFFF1F3F8),
        modifier = modifier,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Color.White else Color(0xFF6D7481),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun RecommendationCard(
    item: HomeRecommendationViewModel,
    modifier: Modifier = Modifier,
    onSetCompleted: (String, Boolean) -> Unit,
) {
    val accent = parseHomeColor(recommendationColor(item.domain))
    Card(
        modifier = modifier.clickable { },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(82.dp)
                    .background(if (item.thumbnailUri.isNotBlank()) Color.Transparent else Color(0xFFEEF2F7)),
            ) {
                if (item.thumbnailUri.isNotBlank()) {
                    AsyncImage(
                        model = item.thumbnailUri,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无图片", fontSize = 12.sp, color = Color(0xFF8B93A4))
                    }
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (item.completed) accent.copy(alpha = 0.6f) else accent,
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp),
                ) {
                    Text(
                        if (item.completed) "已完成" else item.tag.ifBlank { "未完成" },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = if (item.completed) Color(0xFFA0A7B5) else Color(0xFF151A28),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Text(
                item.desc.ifBlank { "暂无描述" },
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF787F8E),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF7C8494),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Checkbox(
                    checked = item.completed,
                    onCheckedChange = { checked -> onSetCompleted(item.id, checked) },
                    colors = CheckboxDefaults.colors(checkedColor = accent),
                    modifier = Modifier.size(36.dp),
                )
            }
        }
    }
}

/** Parse `#RRGGBB` strings used throughout the source theme. */
fun parseHomeColor(hex: String): Color {
    val h = hex.removePrefix("#")
    return if (h.length == 6) {
        Color(
            red = h.substring(0, 2).toInt(16) / 255f,
            green = h.substring(2, 4).toInt(16) / 255f,
            blue = h.substring(4, 6).toInt(16) / 255f,
        )
    } else {
        Color(0xFF8066FF)
    }
}

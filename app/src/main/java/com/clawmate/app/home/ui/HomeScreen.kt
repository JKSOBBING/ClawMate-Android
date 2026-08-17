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
import com.clawmate.app.gallery.model.GalleryPhotoItem
import com.clawmate.app.home.data.HomeProfileSource
import com.clawmate.app.home.data.HomeRecRepository
import com.clawmate.app.home.domain.AvatarStatus
import com.clawmate.app.home.domain.HomeDomainViewModel
import com.clawmate.app.home.domain.HomeProfileViewModel
import com.clawmate.app.home.domain.HomeRecommendationViewModel
import com.clawmate.app.home.domain.HomeUiState
import com.clawmate.app.home.domain.recommendationColor
import com.clawmate.app.home.recommendation.HomeRecommendationGenerationState
import com.clawmate.app.profile.avatar.ui.ProfileAvatarHost

/**
 * Home tab — real Compose port of `pages/home/HomePage.ets`.
 *
 * Shows the honest profile lifecycle (loading / error / empty / ready), the
 * avatar section (NOT_CONFIGURED on Android), the four persona pillars
 * (理解你/守护你/陪伴你/成就你), the recommendation list with completion
 * toggles, 未完成/全部 filter, an explicit Phase-28 "生成推荐" action (filled from
 * real gallery data) and pagination. Avatar generation is never fabricated; it
 * stays DISABLED until a real Android implementation exists.
 *
 * Phase 32 adds an explicit "生成画像" action (only from the button tap, never at
 * Home load/recomposition/background) that builds the four-domain profile from
 * real app-private indexed events; its typed lifecycle state
 * ([ProfileGenerationState]) is surfaced honestly (running / local-only success /
 * model success / partial / not-configured / error).
 */
@Composable
fun HomeScreen(
    homeRecRepository: HomeRecRepository,
    profileSource: HomeProfileSource = com.clawmate.app.home.data.EmptyHomeProfileSource,
    galleryItemsSource: (suspend () -> List<GalleryPhotoItem>)? = null,
    dailyLogSource: (suspend () -> List<com.clawmate.app.datamanager.DailyLogEntry>)? = null,
    profileGenerationRepository: com.clawmate.app.datamanager.ProfileGenerationRepository? = null,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModelFactory(homeRecRepository, profileSource, galleryItemsSource, dailyLogSource, profileGenerationRepository)),
) {
    val state by viewModel.state.collectAsState()
    val list by viewModel.list.collectAsState()
    val generation by viewModel.generation.collectAsState()
    val profileGeneration by viewModel.profileGeneration.collectAsState()

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
            generation = generation,
            profileGeneration = profileGeneration,
            onGenerateRecommendations = { viewModel.generateRecommendations() },
            onGenerateProfile = { viewModel.generateProfile() },
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
    generation: HomeRecommendationGenerationState,
    profileGeneration: com.clawmate.app.datamanager.ProfileGenerationState,
    onGenerateRecommendations: () -> Unit,
    onGenerateProfile: () -> Unit,
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

        // --------------------------------------------------- explicit 生成画像
        ProfileGenerationSection(
            profileGeneration = profileGeneration,
            onGenerateProfile = onGenerateProfile,
        )

        // --------------------------------------------------- recommendations
        RecommendationsSection(
            list = list,
            generation = generation,
            onGenerateRecommendations = onGenerateRecommendations,
            onToggleShowAll = onToggleShowAll,
            onPrevious = onPrevious,
            onNext = onNext,
            onSetCompleted = onSetCompleted,
        )
    }
}

/**
 * Phase-32 explicit "生成画像" action card. Only an explicit button tap ever
 * triggers the build — never Home load / recomposition / background. The typed
 * lifecycle state is surfaced honestly (running / local-only success / model
 * success / partial / not-configured / error), so a model failure is never shown
 * as a fabricated profile.
 */
@Composable
private fun ProfileGenerationSection(
    profileGeneration: com.clawmate.app.datamanager.ProfileGenerationState,
    onGenerateProfile: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7EC)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "画像生成",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF3A2E12),
            )
            when (profileGeneration) {
                is com.clawmate.app.datamanager.ProfileGenerationState.Running -> {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            "正在生成画像…",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF6D4C27),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                is com.clawmate.app.datamanager.ProfileGenerationState.Success -> {
                    val label = if (profileGeneration.isModel) "画像已生成（含模型结论）" else "已生成本地画像"
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF6D4C27),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        "覆盖 ${profileGeneration.eventCount} 条事件 · 画像条目 ${profileGeneration.claimCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9A7B46),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    ProfileGenerateButton(onGenerateProfile, running = false)
                }
                is com.clawmate.app.datamanager.ProfileGenerationState.Partial -> {
                    Text(
                        "画像部分生成（部分领域暂未形成）",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFC2564E),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        "覆盖 ${profileGeneration.eventCount} 条事件 · 画像条目 ${profileGeneration.claimCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9A7B46),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    ProfileGenerateButton(onGenerateProfile, running = false)
                }
                is com.clawmate.app.datamanager.ProfileGenerationState.Error -> {
                    Text(
                        profileGeneration.userMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFC2564E),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    ProfileGenerateButton(onGenerateProfile, running = false)
                }
                is com.clawmate.app.datamanager.ProfileGenerationState.NotConfigured -> {
                    Text(
                        "尚未配置云端模型，可生成本地画像",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9A7B46),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    ProfileGenerateButton(onGenerateProfile, running = false)
                }
                is com.clawmate.app.datamanager.ProfileGenerationState.NoData -> {
                    Text(
                        "日记数据还不足以生成画像",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9A7B46),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                com.clawmate.app.datamanager.ProfileGenerationState.Idle -> {
                    ProfileGenerateButton(onGenerateProfile, running = false)
                }
            }
        }
    }
}

@Composable
private fun ProfileGenerateButton(
    onGenerateProfile: () -> Unit,
    running: Boolean,
) {
    Button(
        onClick = onGenerateProfile,
        enabled = !running,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE7A63C)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Text("生成画像", fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
    generation: HomeRecommendationGenerationState,
    onGenerateRecommendations: () -> Unit,
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
            EmptyRecommendationsState(generation, onGenerateRecommendations)
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

/**
 * Honest empty state with the explicit Phase-28 "生成推荐" action. Shows the real
 * lifecycle state of the earlier generation attempt (未配置 / 无数据 / 运行中 / 成功 /
 * 失败). Only an explicit button tap ever starts generation.
 */
@Composable
private fun EmptyRecommendationsState(
    generation: HomeRecommendationGenerationState,
    onGenerateRecommendations: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "暂无待办事项",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF7C8494),
        )
        when (generation) {
            is HomeRecommendationGenerationState.Running -> {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        "正在生成推荐…",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF6D7481),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            is HomeRecommendationGenerationState.Error -> {
                Text(
                    generation.userMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFC2564E),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                GenerateRecommendationsButton(onGenerateRecommendations, running = false)
            }
            is HomeRecommendationGenerationState.NotConfigured -> {
                Text(
                    "尚未配置云端模型",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8B93A4),
                    modifier = Modifier.padding(top = 8.dp),
                )
                GenerateRecommendationsButton(onGenerateRecommendations, running = false)
            }
            is HomeRecommendationGenerationState.NoData -> {
                Text(
                    "图库中暂无可用内容",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8B93A4),
                    modifier = Modifier.padding(top = 8.dp),
                )
                GenerateRecommendationsButton(onGenerateRecommendations, running = false)
            }
            is HomeRecommendationGenerationState.Success -> {
                // A prior success admitted some recommendations; the list should
                // not be empty here, but keep the button available to regenerate.
                GenerateRecommendationsButton(onGenerateRecommendations, running = false)
            }
            HomeRecommendationGenerationState.Idle -> {
                GenerateRecommendationsButton(onGenerateRecommendations, running = false)
            }
        }
    }
}

@Composable
private fun GenerateRecommendationsButton(
    onGenerateRecommendations: () -> Unit,
    running: Boolean,
) {
    Button(
        onClick = onGenerateRecommendations,
        enabled = !running,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8066FF)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Text("生成推荐", fontSize = 14.sp, fontWeight = FontWeight.Bold)
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

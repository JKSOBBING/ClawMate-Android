package com.clawmate.app.home.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.res.painterResource
import com.clawmate.app.R
import com.clawmate.app.ui.HarmonyPage
import com.clawmate.app.ui.theme.HarmonyTheme
import com.clawmate.app.ui.ClawMateHeader
import com.clawmate.app.ui.theme.ClawMateDesign
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
    onOpenTasks: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val list by viewModel.list.collectAsState()
    val generation by viewModel.generation.collectAsState()
    val profileGeneration by viewModel.profileGeneration.collectAsState()

    // Phase 12: the avatar section opens the real Profile / Avatar screen
    // (local profile editing + honest generation boundary) instead of staying a
    // dead disabled button.
    var showAvatar by remember { mutableStateOf(false) }
    when (val s = state) {
        is HomeUiState.Loading -> HomeStateWithHeader {
            LoadingBox(modifier = Modifier.weight(1f))
        }
        is HomeUiState.Error -> HomeStateWithHeader {
            ErrorBox(message = s.message, modifier = Modifier.weight(1f))
        }
        is HomeUiState.Ready -> HomeContent(
            profile = s.profile,
            list = list,
            onToggleShowAll = viewModel::setShowAll,
            onPrevious = viewModel::previousPage,
            onNext = viewModel::nextPage,
            onSetCompleted = viewModel::setRecommendationCompleted,
            onOpenAvatar = { showAvatar = true },
            onOpenTasks = onOpenTasks,
            onOpenSettings = onOpenSettings,
        )
    }
    if (showAvatar) ProfileAvatarHost(onClose = { showAvatar = false }, supplementaryContent = {
        ProfileGenerationSection(profileGeneration, viewModel::generateProfile)
        EmptyRecommendationsState(generation, viewModel::generateRecommendations)
    })
}

@Composable
private fun HomeStateWithHeader(
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    HarmonyPage(background = HarmonyTheme.pageWhite, header = {
        ClawMateHeader("你的智伴", R.drawable.digital_avatar, showIcon = true, pcServerVisible = true)
    }) { insets -> Column(Modifier.fillMaxSize().padding(insets), content = content) }
}

@Composable
private fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
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
private fun ErrorBox(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = Modifier
            .then(modifier)
            .fillMaxWidth()
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
    onOpenTasks: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scroll = rememberScrollState()
    HarmonyPage(background = HarmonyTheme.pageWhite, scrollOffset = { scroll.value.toFloat() }, header = {
        ClawMateHeader(title = "你的智伴", iconRes = R.drawable.digital_avatar, showIcon = true,
            pcServerVisible = true, onIconClick = onOpenAvatar)
    }) { insets ->
      Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(insets), horizontalAlignment = Alignment.CenterHorizontally) {
        ProfileOverview(profile, list.recommendations.count { !it.completed }, onOpenAvatar)

        // --------------------------------------------------- recommendations
        HomeRemindersSection(list, onToggleShowAll, onPrevious, onNext, onSetCompleted, onOpenTasks)

        // Keep the real indexed-profile generation flow available after the
        // Harmony-aligned first viewport, rather than inserting an extra card
        // between the source page's completeness and recommendation blocks.
        HomeTaskSuggestionsSection(profile.domains, onOpenTasks, onOpenSettings)
        HomeComfortSection(onOpenSettings)

      }
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

/** Color parser retained for model-provided recommendation colors. */
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

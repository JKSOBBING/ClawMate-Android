package com.clawmate.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import com.clawmate.app.R
import com.clawmate.app.chat.ui.ChatScreen
import com.clawmate.app.gallery.GalleryComponent
import com.clawmate.app.gallery.model.GalleryPhotoItem
import com.clawmate.app.gallery.ui.GalleryScreen
import com.clawmate.app.gallery.ui.CollectionSummaryScreen
import com.clawmate.app.datamanager.DailyLogEntry
import com.clawmate.app.datamanager.DailyLogEventSourceFactory
import com.clawmate.app.home.data.DataStoreHomeRecRepository
import com.clawmate.app.home.data.DataStoreOnboardingRepository
import com.clawmate.app.home.data.HomeProfileSource
import com.clawmate.app.home.data.HomeRecRepository
import com.clawmate.app.home.data.OnboardingRepository
import com.clawmate.app.home.recommendation.HomeRecommendationFactory
import com.clawmate.app.home.ui.HomeScreen
import com.clawmate.app.profile.avatar.data.DataStoreProfileAvatarRepository
import com.clawmate.app.profile.avatar.data.ProfileAvatarRepository
import com.clawmate.app.home.ui.onboarding.FirstLaunchOnboardingScreen
import com.clawmate.app.task.ui.TaskScreen as WorkflowTaskScreen
import com.clawmate.app.settings.ui.SettingsScreen
import com.clawmate.app.settings.domain.SettingsSectionId

/**
 * Bottom-navigation host that mirrors the HarmonyOS main tab set:
 *     Home(首页) | Summary(汇总) | Chat(聊天) | Task(任务) | Settings(设置)
 *
 * HarmonyOS source reference: entry/src/main/ets/pages/main/MainTabConfig.ets
 */
sealed class Destination(
    val route: String,
    val label: String,
    val icon: Int,
) {
    data object Home : Destination("home", "首页", R.drawable.tab_home)
    data object Summary : Destination("summary", "汇总", R.drawable.tab_summary)
    data object Chat : Destination("chat", "聊天", R.drawable.tab_chat)
    data object Task : Destination("task", "任务", R.drawable.tab_task)
    data object Settings : Destination("settings", "设置", R.drawable.tab_settings)

    companion object {
        // Do not cache this list during Destination class initialization.
        // Kotlin data-object initialization can otherwise expose a null nested
        // object to the first Compose frame on some Android runtimes.
        val items: List<Destination>
            get() = listOf(Home, Summary, Chat, Task, Settings)
    }
}

/**
 * App shell root. Mirrors the HarmonyOS `Index` build: it first applies the
 * first-launch onboarding gate and, once the versioned onboarding is completed,
 * shows the 5-tab root. Onboarding reads no network and works on a
 * configuration-free fresh install.
 */
@Composable
fun ClawMateRoot(
    onboardingRepository: OnboardingRepository? = null,
    homeRecRepository: HomeRecRepository? = null,
    profileSource: HomeProfileSource? = null,
    profileRepository: ProfileAvatarRepository? = null,
) {
    val context = LocalContext.current
    // App-private DataStore-backed stores are recreated on configuration change,
    // but their data survives across the gate → tab transition.
    val onboarding = onboardingRepository ?: DataStoreOnboardingRepository(context)
    val homeRecs = homeRecRepository ?: DataStoreHomeRecRepository(context)

    // Phase 16 (A1 gap): the persisted local profile now flows into the Home
    // recommendation pipeline. When no explicit profile source is injected, the
    // real app-private DataStore profile repository backs a
    // ProfileBackedHomeProfileSource so the home pillars/completeness reflect
    // what the user actually curated in the 数字分身 screen. Callers that inject
    // their own source (e.g. tests) keep full control.
    val profileRepo = profileRepository ?: DataStoreProfileAvatarRepository(context)

    // Phase 28: the Home "生成推荐" button is powered by a real repository that
    // converts the app-private Room gallery items into bounded planner events. It
    // is gated so zero network I/O happens when the Planner backend is not
    // configured or there is no real gallery data. Never invoked on launch.
    val recommendationRepository = HomeRecommendationFactory.realRepository(context.filesDir)
    val galleryItemsSource: suspend () -> List<GalleryPhotoItem> = {
        runCatching {
            GalleryComponent.getRepository(context).observeItems()
                .first()
        }.getOrElse { emptyList() }
    }
    // Phase 29: the same explicit "生成推荐" action also consumes real app-private
    // daily-log text already present under filesDir/workflows/daily-log/*. The
    // source is read-only, never creates files, doesn't touch external storage/
    // URI/image/network, and is invoked only from the explicit button via the
    // ViewModel (never at launch/recomposition/background).
    val appPrivateDailyLogSource: suspend () -> List<DailyLogEntry> = {
        DailyLogEventSourceFactory
            .appPrivate(context.filesDir)
            .load()
            .entries
    }
    // Phase 32: the Home "生成画像" button reads the app-private indexed events
    // and materialises the four-domain profile (local-only when the model is
    // unconfigured, model-backed otherwise). It is only ever invoked from the
    // explicit button — never on launch/recomposition/background.
    val profileGenerationRepository =
        com.clawmate.app.datamanager.RealProfileGenerationRepository.appPrivate(context.filesDir)
    // Phase 32: the Home pillar cards blend the user-curated local profile with
    // the index-derived profile (local-first, never overwriting user edits).
    // Phase 48: the same source reads the **real persisted avatar** through the
    // app-private avatar asset repository so the Home card shows the actual
    // stored avatar (only when it exists, is readable and passes the safety
    // gate); the avatar section falls back to the honest not-configured state
    // otherwise.
    val homeAvatarAssetRepository =
        com.clawmate.app.profile.avatar.data.DiskAvatarAssetRepository(context)
    val homeAvatarImageCheck = com.clawmate.app.home.data.FileHomeAvatarImageCheck()
    val homeProfileSource = profileSource ?: com.clawmate.app.home.data.ProfileIndexHomeProfileSource(
        baseDir = context.filesDir,
        profileRepository = profileRepo,
        recommendationRepository = recommendationRepository,
        avatarAssetRepository = homeAvatarAssetRepository,
        avatarImageCheck = homeAvatarImageCheck,
    )

    val onboardingVersion by onboarding.onboardingVersion.collectAsState(initial = "")
    val showOnboarding = com.clawmate.app.home.domain.shouldShowFirstLaunchOnboarding(onboardingVersion)

    // Phase-68 fix: a local mutable flag forces the transition immediately when
    // onboarding finishes, without waiting for the DataStore flow re-emission.
    // This prevents a brief flash where the composition re-evaluates with
    // showOnboarding == true (stale flow value) after completion.
    var onboardingCompleted by rememberSaveable { mutableStateOf(false) }

    if (showOnboarding && !onboardingCompleted) {
        FirstLaunchOnboardingScreen(
            repository = onboarding,
            onFinished = {
                // Interlock: persist is confirmed by DataStore flow emission
                // (which eventually flips showOnboarding), but the local flag
                // cuts the latency to zero so the shell swaps immediately.
                onboardingCompleted = true
            },
        )
    } else {
        MainTabScaffold(
            homeRecRepository = homeRecs,
            profileSource = homeProfileSource,
            galleryItemsSource = galleryItemsSource,
            dailyLogSource = appPrivateDailyLogSource,
            profileGenerationRepository = profileGenerationRepository,
        )
    }
}

@Composable
private fun MainTabScaffold(
    homeRecRepository: HomeRecRepository,
    profileSource: HomeProfileSource,
    galleryItemsSource: (suspend () -> List<GalleryPhotoItem>)?,
    dailyLogSource: (suspend () -> List<DailyLogEntry>)?,
    profileGenerationRepository: com.clawmate.app.datamanager.ProfileGenerationRepository?,
) {
    // These are peer-level tabs, not a nested back stack. Keeping the selected
    // tab as local saved state avoids constructing a navigation graph for every
    // click and keeps a broken optional feature from taking down the shell.
    var selectedRoute by rememberSaveable { mutableStateOf(Destination.Home.route) }
    var settingsSectionToOpen by rememberSaveable { mutableStateOf<Int?>(null) }

    Scaffold(
        containerColor = com.clawmate.app.ui.theme.NearWhite,
        bottomBar = {
            MainBottomBar(
                selectedRoute = selectedRoute,
                onSelect = { selectedRoute = it },
            )
        },
    ) { innerPadding ->
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (selectedRoute) {
                Destination.Home.route -> {
                HomeScreen(
                    homeRecRepository = homeRecRepository,
                    profileSource = profileSource,
                    galleryItemsSource = galleryItemsSource,
                    dailyLogSource = dailyLogSource,
                    profileGenerationRepository = profileGenerationRepository,
                )
                }
                Destination.Summary.route -> CollectionSummaryScreen(
                    context = LocalContext.current,
                    onOpenSettings = { selectedRoute = Destination.Settings.route },
                )
                Destination.Chat.route -> ChatScreen(
                    context = LocalContext.current,
                    onOpenTasks = { selectedRoute = Destination.Task.route },
                    onOpenSettings = { selectedRoute = Destination.Settings.route },
                )
                Destination.Task.route -> WorkflowTaskScreen(
                    LocalContext.current,
                    onOpenSettings = {
                        settingsSectionToOpen = SettingsSectionId.TaskAutomation.ordinalIndex
                        selectedRoute = Destination.Settings.route
                    },
                )
                Destination.Settings.route -> SettingsScreen(
                    LocalContext.current,
                    initialSectionId = settingsSectionToOpen,
                )
                else -> HomeScreen(
                    homeRecRepository = homeRecRepository,
                    profileSource = profileSource,
                    galleryItemsSource = galleryItemsSource,
                    dailyLogSource = dailyLogSource,
                    profileGenerationRepository = profileGenerationRepository,
                )
            }
        }
    }
}

/** Fixed-size bottom tabs matching HarmonyOS Index.TabItem. */
@Composable
private fun MainBottomBar(
    selectedRoute: String,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Destination.items.forEach { destination ->
                val selected = selectedRoute == destination.route
                Column(
                    modifier = Modifier
                        .width(72.dp)
                        .height(58.dp)
                        .clickable { onSelect(destination.route) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(
                                if (selected) com.clawmate.app.ui.theme.TabIndicator else Color.Transparent,
                                RoundedCornerShape(16.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(destination.icon),
                            contentDescription = destination.label,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                    Text(
                        destination.label,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) com.clawmate.app.ui.theme.TabSelected else com.clawmate.app.ui.theme.MutedText,
                        modifier = Modifier.padding(top = 2.dp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

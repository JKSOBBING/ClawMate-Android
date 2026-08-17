package com.clawmate.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first
import com.clawmate.app.chat.ui.ChatScreen
import com.clawmate.app.gallery.GalleryComponent
import com.clawmate.app.gallery.model.GalleryPhotoItem
import com.clawmate.app.gallery.ui.GalleryScreen
import com.clawmate.app.home.data.DataStoreHomeRecRepository
import com.clawmate.app.home.data.DataStoreOnboardingRepository
import com.clawmate.app.home.data.HomeProfileSource
import com.clawmate.app.home.data.HomeRecRepository
import com.clawmate.app.home.data.OnboardingRepository
import com.clawmate.app.home.data.ProfileBackedHomeProfileSource
import com.clawmate.app.home.recommendation.HomeRecommendationFactory
import com.clawmate.app.home.ui.HomeScreen
import com.clawmate.app.profile.avatar.data.DataStoreProfileAvatarRepository
import com.clawmate.app.profile.avatar.data.ProfileAvatarRepository
import com.clawmate.app.home.ui.onboarding.FirstLaunchOnboardingScreen
import com.clawmate.app.task.ui.TaskScreen as WorkflowTaskScreen
import com.clawmate.app.settings.ui.SettingsScreen

/**
 * Bottom-navigation host that mirrors the HarmonyOS main tab set:
 *     Home(首页) | Summary(汇总) | Chat(聊天) | Task(任务) | Settings(设置)
 *
 * HarmonyOS source reference: entry/src/main/ets/pages/main/MainTabConfig.ets
 */
sealed class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    data object Home : Destination("home", "首页", Icons.Filled.Home)
    data object Summary : Destination("summary", "汇总", Icons.Filled.Summarize)
    data object Chat : Destination("chat", "聊天", Icons.AutoMirrored.Filled.Chat)
    data object Task : Destination("task", "任务", Icons.AutoMirrored.Filled.List)
    data object Settings : Destination("settings", "设置", Icons.Filled.Settings)

    companion object {
        val items = listOf(Home, Summary, Chat, Task, Settings)
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
    val recommendationRepository = HomeRecommendationFactory.realRepository()
    val galleryItemsSource: suspend () -> List<GalleryPhotoItem> = {
        runCatching {
            GalleryComponent.getRepository(context).observeItems()
                .first()
        }.getOrElse { emptyList() }
    }
    val homeProfileSource = profileSource ?: ProfileBackedHomeProfileSource(
        profileRepository = profileRepo,
        recommendationRepository = recommendationRepository,
    )

    val onboardingVersion by onboarding.onboardingVersion.collectAsState(initial = "")
    val showOnboarding = com.clawmate.app.home.domain.shouldShowFirstLaunchOnboarding(onboardingVersion)

    if (showOnboarding) {
        FirstLaunchOnboardingScreen(
            repository = onboarding,
            onFinished = { /* version persisted; flow re-resolves and swaps to tabs */ },
        )
    } else {
        MainTabScaffold(
            homeRecRepository = homeRecs,
            profileSource = homeProfileSource,
            galleryItemsSource = galleryItemsSource,
        )
    }
}

@Composable
private fun MainTabScaffold(
    homeRecRepository: HomeRecRepository,
    profileSource: HomeProfileSource,
    galleryItemsSource: (suspend () -> List<GalleryPhotoItem>)?,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.items.forEach { destination ->
                    val selected = currentDestination?.hierarchy
                        ?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            val context = LocalContext.current
            when (backStackEntry?.destination?.route) {
                Destination.Home.route -> HomeScreen(homeRecRepository = homeRecRepository, profileSource = profileSource, galleryItemsSource = galleryItemsSource)
                Destination.Summary.route -> GalleryScreen()
                Destination.Chat.route -> ChatScreen(context)
                Destination.Task.route -> WorkflowTaskScreen(context)
                Destination.Settings.route -> SettingsScreen(context)
                else -> HomeScreen(homeRecRepository = homeRecRepository, profileSource = profileSource, galleryItemsSource = galleryItemsSource)
            }
        }
    }
}

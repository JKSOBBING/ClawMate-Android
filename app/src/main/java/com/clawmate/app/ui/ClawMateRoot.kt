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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.clawmate.app.chat.ui.ChatScreen
import com.clawmate.app.gallery.ui.GalleryScreen
import com.clawmate.app.task.ui.TaskScreen as WorkflowTaskScreen
import com.clawmate.app.ui.home.HomeScreen
import com.clawmate.app.ui.settings.SettingsScreen

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

@Composable
fun ClawMateRoot() {
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
                Destination.Home.route -> HomeScreen()
                Destination.Summary.route -> GalleryScreen()
                Destination.Chat.route -> ChatScreen(context)
                Destination.Task.route -> WorkflowTaskScreen(context)
                Destination.Settings.route -> SettingsScreen()
                else -> HomeScreen()
            }
        }
    }
}

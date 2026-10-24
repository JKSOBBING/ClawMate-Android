package com.clawmate.app.home.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clawmate.app.datamanager.ConsolidationModelConfig
import com.clawmate.app.home.comfort.*
import java.time.LocalDate
import androidx.compose.ui.platform.LocalContext
import com.clawmate.app.chat.data.ChatDatabase
import com.clawmate.app.datamanager.chatmemory.RoomChatMemorySessionSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun HomeComfortSection(onOpenSettings: () -> Unit) {
    val factory = remember { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeComfortViewModel(
            configProvider = { ConsolidationModelConfig.fromEndpointConfig() },
            request = { config, history, topic -> HomeComfortClient.fromConfig(config).reply(history, LocalDate.now().toString(), topic) },
        ) as T
    } }
    val vm: HomeComfortViewModel = viewModel(key = "home-comfort", factory = factory)
    HomeComfortCard(vm, showEntry = false, onOpenSettings = onOpenSettings)
    val context = LocalContext.current.applicationContext
    val topicFactory = remember(context) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeComfortTopicsViewModel(
            { ConsolidationModelConfig.fromEndpointConfig() },
            { withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                HomeComfortTopics.context(RoomChatMemorySessionSource(ChatDatabase.get(context)).loadRecent(now), now)
            } },
            { config, input -> HomeComfortClient.fromConfig(config).topics(input) },
        ) as T
    } }
    val topics: HomeComfortTopicsViewModel = viewModel(key = "home-comfort-topics", factory = topicFactory)
    HomeComfortTopicsCard(topics, { vm.open(it) }, onOpenSettings, onCasualChat = { vm.open() })
}

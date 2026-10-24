package com.clawmate.app.home.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clawmate.app.datamanager.ConsolidationModelConfig
import com.clawmate.app.datamanager.DailyLogLocalIndex
import com.clawmate.app.datamanager.LocalConsolidationTypes.QueryOptions
import com.clawmate.app.home.domain.HomeDomainViewModel
import com.clawmate.app.home.tasks.*
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun HomeTaskSuggestionsSection(domains: List<HomeDomainViewModel>, onOpenTasks: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val factory = remember(context) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeTaskSuggestionViewModel(
            configProvider = { ConsolidationModelConfig.fromEndpointConfig() },
            loadContext = { profile, previous -> withContext(Dispatchers.IO) {
                val index = DailyLogLocalIndex(context.filesDir)
                val events = if (index.hasBuiltIndex()) index.query(QueryOptions(limit = 24)).events else emptyList()
                HomeTaskSuggestions.context(events, profile, previous, LocalDate.now().toString())
            } },
            generateTasks = { config, input -> HomeTaskSuggestionModelClient.fromConfig(config).generate(input) },
        ) as T
    } }
    val vm: HomeTaskSuggestionViewModel = viewModel(key = "home-task-suggestions", factory = factory)
    HomeTaskSuggestionsCard(vm, domains, onOpenTasks, onOpenSettings)
}

package com.clawmate.app.task.ui

import com.clawmate.app.ui.InsetSecondaryPage
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clawmate.app.R
import com.clawmate.app.task.TaskComponent
import com.clawmate.app.task.model.WorkflowScene
import com.clawmate.app.task.model.WorkflowTask
import com.clawmate.app.ui.ClawMateHeader
import com.clawmate.app.ui.theme.ClawMateDesign

/**
 * Compose Task screen — Android vertical slice of HarmonyOS TaskPage.ets.
 * Shows an execution header panel, scene-grouped compact task rows, and a
 * batch-run button, mirroring the HarmonyOS execution-list layout.
 */
@Composable
fun TaskScreen(
    context: Context,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
) {
    val vm: TaskViewModel = viewModel(
        factory = TaskViewModelFactory(TaskComponent.repository(context), TaskComponent.runner(context)),
    )
    val state by vm.state.collectAsState()
    val config by vm.config.collectAsState()

    config?.let { document ->
        BackHandler(onBack = vm::closeConfig)
        InsetSecondaryPage { WorkflowConfigScreen(
            document, onBack = vm::closeConfig, onRetry = { vm.openConfig(document.task) },
            canEdit = state.runningTaskId == null && !state.batchRunning,
            onEdit = vm::editConfig, onDraftChange = vm::updateConfigDraft,
            onSave = vm::saveConfig, onCancelEdit = vm::cancelConfigEdit,
            onDiscard = vm::discardConfigEdits, onKeepEditing = vm::keepConfigEditing,
        ) }
        return
    }

    Box(modifier) { HarmonyTaskPage(state, vm::toggleEnabled, vm::openConfig, vm::run,
        vm::runEnabledTasks, vm::cancelRun, vm::reload, onOpenSettings) }
}

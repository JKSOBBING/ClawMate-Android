package com.clawmate.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Summary / collection tab placeholder (Phase 1).
 * Maps to the HarmonyOS 汇总 tab backed by the collection grouping service.
 *
 * NOTE: Phase 3 wires the 汇总 tab to the real [com.clawmate.app.gallery.ui
 * .GalleryScreen]; this composable is retained only as a reference and is no
 * longer routed.
 *
 * HarmonyOS source reference:
 *   entry/src/main/ets/pages/collection/CollectionPage.ets
 *   entry/src/main/ets/datamanager/CollectionGroupingService.ets
 */
@Composable
fun SummaryScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
    ) {
        Text("汇总", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Summary / collection groups — see gallery.ui.GalleryScreen (Phase 3)",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Task / workflow tab placeholder (Phase 1).
 * Maps to the HarmonyOS 任务 tab backed by the WorkflowRunner.
 *
 * HarmonyOS source reference:
 *   entry/src/main/ets/pages/task/TaskPage.ets
 *   entry/src/main/ets/utils/WorkflowRunner.ets
 */
@Composable
fun TaskScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
    ) {
        Text("任务", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Tasks / workflows — phase 2",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

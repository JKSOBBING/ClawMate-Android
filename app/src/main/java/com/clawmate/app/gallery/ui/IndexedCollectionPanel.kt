package com.clawmate.app.gallery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clawmate.app.gallery.collection.IndexedCollectionGroup
import com.clawmate.app.gallery.collection.IndexedEventCollectionService

/**
 * Phase-44 "已整理记录（本地索引）" panel for the 汇总 tab.
 *
 * Renders local scene tabs, clickable local group items, an explicit "同步" action,
 * and honest Idle / Refreshing / Loaded / Error states over the real indexed
 * daily-log events. Owns no full-screen nav itself: when a group is tapped it
 * forwards to [onOpenGroup] (the parent hosts the group detail screen).
 *
 * HarmonyOS reference (read-only): pages/collection/CollectionPage.ets
 */
@Composable
fun IndexedCollectionPanel(
    vm: CollectionIndexViewModel,
    onOpenGroup: (IndexedCollectionGroup) -> Unit,
) {
    val uiState by vm.uiState.collectAsState()
    val selectedTab by vm.selectedTab.collectAsState()

    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("已整理记录（本地索引）", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "读取本地 daily-log 索引并按场景分组；标注 local，不调用模型/网络。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (uiState) {
                    is IndexedCollectionUiState.Refreshing -> CircularProgressIndicator(Modifier.size(20.dp))
                    is IndexedCollectionUiState.Error -> OutlinedButton(onClick = { vm.refreshIndex() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("重试")
                    }
                    else -> OutlinedButton(onClick = { vm.refreshIndex() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("同步")
                    }
                }
            }

            if (uiState is IndexedCollectionUiState.Error) {
                Text(
                    (uiState as IndexedCollectionUiState.Error).message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // Scene tabs (clickable, no I/O on switch).
            LazyRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                items(IndexedEventCollectionService.tabs()) { tab ->
                    val active = tab.sceneId == selectedTab.sceneId
                    OutlinedButton(
                        onClick = { vm.selectTab(tab.sceneId) },
                        modifier = Modifier.height(32.dp).padding(end = 8.dp),
                    ) {
                        Text(tab.label, style = if (active) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium)
                    }
                }
            }

            when (val current = uiState) {
                is IndexedCollectionUiState.Idle -> {
                    Text(
                        "点击「同步」读取本地索引后生成分组。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                is IndexedCollectionUiState.Refreshing -> {
                    Text(
                        "正在读取并整理本地索引…",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                is IndexedCollectionUiState.Loaded -> {
                    Text(
                        "local · ${current.groups.size} 组 / ${current.indexedEventCount} 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (current.groups.isEmpty()) {
                        Text(
                            "暂无已整理记录 — 生成日志并同步后，将在这里显示可点击的分组。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        LazyRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            items(current.groups, key = { it.groupId }) { g ->
                                AssistChip(
                                    onClick = { onOpenGroup(g) },
                                    label = { Text("${g.title} · ${g.meta}") },
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            }
                        }
                    }
                }
                is IndexedCollectionUiState.Error -> Unit
            }
        }
    }
}

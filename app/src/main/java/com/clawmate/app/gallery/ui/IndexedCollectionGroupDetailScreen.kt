package com.clawmate.app.gallery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clawmate.app.gallery.collection.IndexedCollectionEvent
import com.clawmate.app.gallery.collection.IndexedCollectionGroup

/**
 * Phase-44 indexed-collection group detail screen.
 *
 * Renders exactly the **real** indexed events that belong to an opened group
 * (resolved in [CollectionIndexViewModel] from the loaded local index), with
 * their real date, summary / normalized fact and source, plus the stable event
 * id. Supports back navigation (via [onBack]) and honest empty state. Nothing is
 * fabricated and no raw path / URI / token is shown.
 *
 * HarmonyOS reference (read-only): pages/collection/CollectionGroupDetailPage.ets
 */
@Composable
fun IndexedCollectionGroupDetailScreen(
    detail: IndexedGroupDetailState,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(detail.group.title, style = MaterialTheme.typography.headlineSmall)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${detail.group.meta} · ${detail.group.source}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (detail.events.isEmpty()) {
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("暂无已整理记录", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "返回汇总页并重新同步后重试。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxWidth()) {
            items(detail.events, key = { it.eventId }) { event ->
                IndexedEventRow(event)
            }
            item("footer") {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun IndexedEventRow(event: IndexedCollectionEvent) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                (event.summary.ifNotEmpty() ?: event.normalizedFact.ifNotEmpty() ?: event.eventId),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "日期 ${event.date.ifBlank { "—" }} · 来源 ${event.source.ifBlank { "—" }}",
                style = MaterialTheme.typography.bodySmall,
            )
            event.normalizedFact.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "ID ${event.eventId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Small helper to surface the first non-empty string (or null). */
private fun String?.ifNotEmpty(): String? = this?.takeIf { it.isNotEmpty() }

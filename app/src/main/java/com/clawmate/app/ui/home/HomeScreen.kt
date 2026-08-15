package com.clawmate.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Home: personal portrait, recommendations, and the four persona pillars
 * (理解你 / 守护你 / 陪伴你 / 成就你).
 *
 * Phase 1 ships the navigation placeholder. Feature buildout happens in a
 * later phase against the source data model in
 * entry/src/main/ets/datamanager/HomeRecommendationService.ets.
 */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
    ) {
        Text("首页", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Home / recommendations — phase 2",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

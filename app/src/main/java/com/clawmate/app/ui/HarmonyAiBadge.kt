package com.clawmate.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clawmate.app.ui.theme.HarmonyTheme

@Composable
fun HarmonyAiBadge(modifier: Modifier = Modifier, compact: Boolean = false, overlay: Boolean = false) {
    Text("AI生成", fontSize = (if (compact) 10 else 11).sp, fontWeight = FontWeight.Bold,
        color = HarmonyTheme.text(if (overlay) "#FFFFFF" else "#5B50C7"), maxLines = 1,
        modifier = modifier.background(HarmonyTheme.background(if (overlay) "#B3000000" else "#EEEAFE"), RoundedCornerShape(7.dp))
            .padding(horizontal = (if (compact) 5 else 7).dp, vertical = (if (compact) 2 else 3).dp))
}

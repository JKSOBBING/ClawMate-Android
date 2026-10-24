package com.clawmate.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clawmate.app.ui.theme.HarmonyTheme

/** AppHeader.ets: title centered independently of either side control. */
@Composable
fun ClawMateHeader(
    title: String,
    iconRes: Int = 0,
    subtitle: String? = null,
    pcServerVisible: Boolean = false,
    pcServerConnected: Boolean = false,
    modifier: Modifier = Modifier,
    showIcon: Boolean = false,
    onIconClick: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val titlePadding = if (showIcon || pcServerVisible) 88.dp else if (onBack != null || actionLabel != null) 56.dp else 0.dp
    Box(modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(title, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium,
            color = HarmonyTheme.text("#172033"), textAlign = TextAlign.Center, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = titlePadding))
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                Box(Modifier.size(42.dp, 40.dp).clip(RoundedCornerShape(14.dp))
                    .background(HarmonyTheme.surface).clickable(onClick = onBack)
                    .semantics { contentDescription = "返回" }, contentAlignment = Alignment.Center) {
                    Text("‹", fontSize = 28.sp, color = HarmonyTheme.text("#11182F"))
                }
            } else if (showIcon && iconRes != 0) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp))
                    .background(HarmonyTheme.background("#EEF2FF"))
                    .then(if (onIconClick != null) Modifier.clickable(onClick = onIconClick) else Modifier),
                    contentAlignment = Alignment.Center) {
                    Icon(painterResource(iconRes), "数字分身", tint = Color.Unspecified, modifier = Modifier.size(34.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            if (pcServerVisible) {
                Row(Modifier.width(80.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(7.dp).background(HarmonyTheme.background(if (pcServerConnected) "#22B07D" else "#A7AFBD"), RoundedCornerShape(4.dp)))
                    Text(if (pcServerConnected) "PC 已连接" else "PC 未连接", fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        color = HarmonyTheme.text(if (pcServerConnected) "#167456" else "#747B87"), maxLines = 1)
                }
            } else if (actionLabel != null) {
                Box(Modifier.size(56.dp, 40.dp).clip(RoundedCornerShape(14.dp))
                    .background(HarmonyTheme.background("#EEF4FF")).clickable(onClick = onAction), contentAlignment = Alignment.Center) {
                    Text(actionLabel, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = HarmonyTheme.text("#276EF1"))
                }
            }
        }
    }
}

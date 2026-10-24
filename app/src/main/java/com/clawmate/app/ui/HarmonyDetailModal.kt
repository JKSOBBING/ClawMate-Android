package com.clawmate.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.clawmate.app.ui.theme.HarmonyTheme

/** Shared gallery/collection source overlay: 88%×82%, 18dp corners and inset. */
@Composable
fun HarmonyDetailModal(title: String, subtitle: String, onDismiss: () -> Unit,
    aiGenerated: Boolean = true, subtitleColor: Color = HarmonyTheme.text("#8066FF"),
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val dim = HarmonyTheme.background("#66000000").alpha
        ConfigureHarmonyDialog(dim)
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.Center) {
            Box(Modifier.matchParentSize().clickable(interactionSource = remember { MutableInteractionSource() },
                indication = null, onClick = onDismiss))
            Column(Modifier.fillMaxWidth(.88f).fillMaxHeight(.82f)
                .background(HarmonyTheme.surface, RoundedCornerShape(18.dp))
                .pointerInput(Unit) { detectTapGestures {} }.padding(18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(subtitle, fontSize = 12.sp, color = subtitleColor, modifier = Modifier.padding(top = 5.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (aiGenerated) HarmonyAiBadge(Modifier.padding(end = 12.dp), compact = true)
                    HarmonyButton("关闭", onDismiss, fontSize = 13.sp, background = Color.Transparent,
                        color = HarmonyTheme.text("#5B68FF"), horizontalPadding = 0.dp)
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), content = content)
            }
        }
    }
}

/** Source overlays are conditional Stack children without a platform window fade. */
@Composable
internal fun ConfigureHarmonyDialog(dim: Float) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
    val dark = isSystemInDarkTheme()
    SideEffect {
        window?.let {
            it.setWindowAnimations(0)
            it.setDimAmount(dim)
            WindowCompat.getInsetsController(it, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
}

@Composable
fun HarmonyDetailSection(label: String) {
    Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.text("#202636"),
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
}

@Composable
fun HarmonyDetailInfo(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(label, fontSize = 12.sp, color = HarmonyTheme.text("#7C8494"), modifier = Modifier.width(64.dp), maxLines = 1)
        Text(value, fontSize = 12.sp, color = HarmonyTheme.text("#303746"), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

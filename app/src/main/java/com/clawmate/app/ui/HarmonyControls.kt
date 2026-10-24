package com.clawmate.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.clawmate.app.ui.theme.HarmonyTheme

/** ArkUI buttons do not inherit Material's minimum height or internal paddings. */
@Composable
fun HarmonyButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    height: Dp = 32.dp, radius: Dp = 16.dp, fontSize: TextUnit = 12.sp,
    color: Color = HarmonyTheme.text("#6658D9"), background: Color = HarmonyTheme.background("#EEECFF"),
    enabled: Boolean = true, horizontalPadding: Dp = 14.dp, bold: Boolean = false,
    fontWeight: FontWeight? = null,
) {
    Box(modifier.height(height).clip(RoundedCornerShape(radius)).background(background)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center) {
        Text(label, fontSize = fontSize, fontWeight = fontWeight ?: if (bold) FontWeight.Bold else FontWeight.Medium,
            color = color, maxLines = 1)
    }
}

@Composable
fun HarmonyModal(onDismiss: () -> Unit, widthFraction: Float = .88f, topPadding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val dim = HarmonyTheme.background("#66000000").alpha
        ConfigureHarmonyDialog(dim)
        Column(Modifier.fillMaxWidth(widthFraction).heightIn(max = 680.dp).clip(RoundedCornerShape(18.dp))
            .background(HarmonyTheme.surface).padding(start = 18.dp, end = 18.dp, top = topPadding, bottom = 20.dp),
            content = content)
    }
}

@Composable
fun HarmonyFullScreen(onDismiss: () -> Unit, background: Color = HarmonyTheme.page,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ConfigureHarmonyDialog(HarmonyTheme.background("#66000000").alpha)
        Box(Modifier.fillMaxSize().background(background).statusBarsPadding().navigationBarsPadding().imePadding()) { content() }
    }
}

@Composable
fun HarmonyTextField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    height: Dp = 56.dp, radius: Dp = 12.dp, fontSize: TextUnit = 14.sp, enabled: Boolean = true,
    maxLength: Int = Int.MAX_VALUE, singleLine: Boolean = false,
) {
    BasicTextField(value, { onChange(it.take(maxLength)) }, modifier.height(height)
        .clip(RoundedCornerShape(radius)).background(HarmonyTheme.background("#EEF2F7")).padding(horizontal = 14.dp, vertical = 8.dp),
        enabled = enabled, singleLine = singleLine, textStyle = TextStyle(fontSize = fontSize, color = HarmonyTheme.primaryText),
        decorationBox = { field -> Box(Modifier.fillMaxSize(), contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) {
            if (value.isEmpty()) Text(placeholder, fontSize = fontSize, color = HarmonyTheme.mutedText)
            field()
        } })
}

package com.clawmate.app.chat.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clawmate.app.R
import com.clawmate.app.chat.model.ChatMode
import com.clawmate.app.ui.theme.HarmonyTheme

@Composable
internal fun HarmonyChatComposer(value: String, onChange: (String) -> Unit, mode: String,
    sending: Boolean, voiceCapturing: Boolean, onPrimary: () -> Unit,
    onVoiceStart: () -> Unit, onVoiceRelease: () -> Unit, onAttach: () -> Unit,
) {
    val primary by rememberUpdatedState(onPrimary)
    val startVoice by rememberUpdatedState(onVoiceStart)
    val stopVoice by rememberUpdatedState(onVoiceRelease)
    val busy by rememberUpdatedState(sending)
    Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp).fillMaxWidth()
        .height(harmonyComposerHeight(value).dp).testTag("chat-composer")
        .shadow(5.dp, RoundedCornerShape(26.dp), spotColor = Color(0x20000000))
        .background(HarmonyTheme.surface, RoundedCornerShape(26.dp)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).shadow(2.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp)).background(HarmonyTheme.surface)
            .clickable(enabled = !sending, onClick = onAttach).semantics { contentDescription = "添加附件" },
            contentAlignment = Alignment.Center) {
            Text("+", fontSize = 30.sp, color = HarmonyTheme.text("#111827"))
        }
        BasicTextField(value, onChange, Modifier.weight(1f).padding(start = 8.dp)
            .height((harmonyComposerHeight(value) - 12).dp).padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            maxLines = 4, textStyle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, color = HarmonyTheme.text("#4B5563")),
            cursorBrush = SolidColor(HarmonyTheme.text("#5968FF")),
            decorationBox = { field -> Box(Modifier.fillMaxSize()) {
                if (value.isEmpty()) Text(if (mode == ChatMode.LOCAL_INFERENCE) "输入本地模型消息..." else "发消息或输入任务...",
                    fontSize = 15.sp, lineHeight = 20.sp, color = HarmonyTheme.mutedText)
                field()
            } })
        val label = if (sending) { if (mode == ChatMode.LOCAL_INFERENCE) "终止" else "停止" } else "发送"
        Row(Modifier.size(82.dp, 40.dp).clip(RoundedCornerShape(20.dp))
            .background(HarmonyTheme.background(if (voiceCapturing) "#5968FF" else if (sending) "#D14343" else "#111827"))
            .semantics { role = Role.Button; contentDescription = if (voiceCapturing) "语音采集中" else "$label，长按语音";
                onClick { primary(); true }; onLongClick("语音输入") { if (!busy) startVoice(); true } }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    val released = withTimeoutOrNull(450L) { waitForUpOrCancellation()?.also { it.consume() } != null }
                    if (released == true) primary()
                    else if (released == null && !busy) {
                        try { startVoice(); waitForUpOrCancellation()?.consume() }
                        finally { stopVoice() }
                    }
                }
            }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (!sending || voiceCapturing) Image(painterResource(R.drawable.voice_microphone_light), null,
                Modifier.size(if (voiceCapturing) 20.dp else 15.dp))
            if (!voiceCapturing) Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.padding(start = if (sending) 0.dp else 5.dp))
        }
    }
}

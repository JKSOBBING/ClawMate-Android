package com.clawmate.app.chat.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clawmate.app.R
import com.clawmate.app.chat.voice.*
import com.clawmate.app.ui.theme.HarmonyTheme
import java.util.Calendar

@Composable
internal fun HarmonyChatWelcome(onPrompt: (String) -> Unit, voice: VoiceSessionSnapshot,
    insets: PaddingValues, bottom: androidx.compose.ui.unit.Dp,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(start = 28.dp, end = 28.dp, top = insets.calculateTopPadding(), bottom = bottom)) {
        Row(Modifier.fillMaxWidth().height(158.dp), verticalAlignment = Alignment.Bottom) {
            DigitalAvatar(size = DigitalAvatarSize.HERO,
                visualState = DigitalAvatarVisualState.fromVoiceStatus(voice.status),
                useAssistantMascot = true, modifier = Modifier.padding(start = 8.dp))
        }
        Text(harmonyChatGreeting(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)), fontSize = 28.sp,
            fontWeight = FontWeight.Bold, color = HarmonyTheme.text("#111827"),
            modifier = Modifier.padding(top = 8.dp), maxLines = 1)
        Text("我可以帮你整理信息、执行任务", fontSize = 15.sp, lineHeight = 22.sp,
            fontWeight = FontWeight.Medium, color = HarmonyTheme.text("#6F7787"),
            modifier = Modifier.padding(top = 8.dp), maxLines = 2)
        Column(Modifier.padding(top = 14.dp)) {
            harmonyChatPrompts.forEachIndexed { index, prompt ->
                Row(Modifier.padding(bottom = 8.dp).fillMaxWidth().height(58.dp)
                    .shadow(2.dp, RoundedCornerShape(16.dp), spotColor = androidx.compose.ui.graphics.Color(0x10000000))
                    .clip(RoundedCornerShape(16.dp)).background(HarmonyTheme.surface)
                    .clickable { onPrompt(prompt.prompt) }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).background(HarmonyTheme.background("#F2F5FF"), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center) {
                        Image(painterResource(listOf(R.drawable.chat_card_flow, R.drawable.chat_card_search,
                            R.drawable.chat_card_image)[index]), null, Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(prompt.title, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            color = HarmonyTheme.text("#111827"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(prompt.description, fontSize = 11.sp, color = HarmonyTheme.text("#7B8392"),
                            modifier = Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

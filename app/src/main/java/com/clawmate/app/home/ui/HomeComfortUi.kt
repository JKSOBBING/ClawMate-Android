package com.clawmate.app.home.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.clawmate.app.home.comfort.*
import com.clawmate.app.ui.*
import com.clawmate.app.ui.theme.HarmonyTheme

@Composable
private fun CasualChatEntry(onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).background(HarmonyTheme.background("#FBF9FF"), RoundedCornerShape(20.dp))
        .clickable(onClick = onOpen).padding(horizontal = 8.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        HomeCategoryIcon(9, 48.dp)
        Text("和我随便聊聊，我会读心术", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.text("#2E2840"),
            modifier = Modifier.weight(1f).padding(start = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("›", fontSize = 24.sp, color = HarmonyTheme.text("#7658C2"))
    }
}

@Composable
internal fun HomeComfortTopicsCard(vm: HomeComfortTopicsViewModel, onOpenTopic: (HomeComfortTopic) -> Unit,
    onOpenSettings: () -> Unit, onCasualChat: () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    Column(Modifier.fillMaxWidth(.92f).padding(top = 30.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("帮你解闷", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, modifier = Modifier.weight(1f))
            AiBadge(); Spacer(Modifier.width(10.dp))
            HarmonyButton(if (state.loading) "梳理中" else "重新看看", vm::refresh, enabled = !state.loading,
                color = HarmonyTheme.text(if (state.loading) "#A0A4B0" else "#7557BC"), background = HarmonyTheme.background(if (state.loading) "#F0F1F5" else "#F1ECFF"), horizontalPadding = 13.dp)
        }
        if (state.loading && state.topics.isEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).height(82.dp).background(HarmonyTheme.surface, RoundedCornerShape(18.dp)),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), color = HarmonyTheme.text("#8B6BD6"))
                Text("正在听听最近的心事", fontSize = 13.sp, color = HarmonyTheme.mutedText, modifier = Modifier.padding(start = 12.dp))
            }
        } else if (state.topics.isEmpty()) CasualChatEntry(onCasualChat)
        else Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(HarmonyTheme.background("#FCFBFF"), RoundedCornerShape(22.dp)).padding(horizontal = 4.dp)) {
            state.topics.forEachIndexed { index, topic ->
                Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).clickable { onOpenTopic(topic) }.padding(horizontal = 5.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(50.dp).background(HarmonyTheme.surface, RoundedCornerShape(17.dp)), contentAlignment = Alignment.Center) {
                        HomeCategoryIcon(when (topic.category) { "work" -> 5; "relationship" -> 9; "money" -> 10; "health" -> 11; "study" -> 2; else -> 6 }, 44.dp)
                    }
                    Text(topic.title, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText,
                        modifier = Modifier.weight(1f).padding(start = 12.dp, end = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("›", fontSize = 24.sp, color = HarmonyTheme.mutedText)
                }
                if (index < state.topics.lastIndex) HorizontalDivider(Modifier.padding(start = 68.dp, end = 6.dp), color = HarmonyTheme.border("#EAE6F2"))
            }
        }
        state.error?.let {
            Text(it, fontSize = 12.sp, color = HarmonyTheme.text("#B42318"), modifier = Modifier.padding(top = 6.dp))
            HarmonyButton("打开设置", onOpenSettings, background = Color.Transparent)
        }
    }
}

/** Conversation is owned by the existing real ViewModel; this only replaces its presentation. */
@Composable
internal fun HomeComfortCard(vm: HomeComfortViewModel, showEntry: Boolean = true, onOpenSettings: () -> Unit) {
    val state by vm.state.collectAsState()
    if (showEntry) CasualChatEntry { vm.open() }
    if (!state.open) return
    var evidenceOpen by remember(state.topic?.id) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(state.messages.size, state.sending, state.error) {
        withFrameNanos { }; scroll.animateScrollTo(scroll.maxValue)
    }
    HarmonyFullScreen(vm::close, background = HarmonyTheme.background("#F8F6FF")) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(70.dp).padding(start = 16.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                HarmonyButton("‹", vm::close, Modifier.size(42.dp), height = 42.dp, radius = 21.dp, fontSize = 30.sp,
                    color = HarmonyTheme.primaryText, background = HarmonyTheme.surface, horizontalPadding = 0.dp)
                Text("陪你聊聊", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, modifier = Modifier.weight(1f).padding(start = 12.dp))
                HarmonyButton("重头开聊", vm::restart, color = HarmonyTheme.text("#7557BC"), background = HarmonyTheme.background("#F1ECFF"), horizontalPadding = 13.dp)
            }
            state.topic?.let { topic ->
                Row(Modifier.fillMaxWidth(.92f).align(Alignment.CenterHorizontally).padding(top = 6.dp, bottom = 8.dp)
                    .background(HarmonyTheme.surface, RoundedCornerShape(16.dp)).clickable { evidenceOpen = true }.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(4.dp).height(38.dp).background(HarmonyTheme.background("#8B6BD6"), RoundedCornerShape(2.dp)))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(topic.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(topic.summary, fontSize = 11.sp, lineHeight = 16.sp, color = HarmonyTheme.mutedText, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (state.messages.isEmpty()) Text("今天想聊点什么？", fontSize = 14.sp, color = HarmonyTheme.mutedText, modifier = Modifier.padding(top = 14.dp))
                state.messages.forEach { message ->
                    val user = message.role == HomeComfortRole.USER
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
                        if (!user) Box(Modifier.size(34.dp).background(HarmonyTheme.background("#EEE8FF"), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Text("心", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.text("#7B5CC4"))
                        }
                        Box(Modifier.widthIn(max = (LocalConfiguration.current.screenWidthDp.dp - 36.dp) * (if (user) .78f else .76f)).padding(start = if (user) 0.dp else 9.dp)
                            .background(if (user) HarmonyTheme.background("#7862D3") else HarmonyTheme.surface, RoundedCornerShape(8.dp)).padding(horizontal = 15.dp, vertical = 11.dp)) {
                            SelectionContainer { Text(message.content, fontSize = 14.sp, lineHeight = 21.sp, color = if (user) Color.White else HarmonyTheme.text("#34394A")) }
                        }
                    }
                }
                if (state.sending) Row(Modifier.padding(start = 44.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = HarmonyTheme.text("#8568D2"))
                    Text("正在认真听你说", fontSize = 12.sp, color = HarmonyTheme.mutedText, modifier = Modifier.padding(start = 9.dp))
                    HarmonyButton("停止回复", vm::stop, background = Color.Transparent)
                }
                Spacer(Modifier.height(4.dp))
            }
            Column(Modifier.fillMaxWidth().background(HarmonyTheme.surface).padding(horizontal = 18.dp, vertical = 10.dp)) {
                state.error?.let {
                    Text(it, fontSize = 11.sp, color = HarmonyTheme.text("#D65A68"), maxLines = 2, modifier = Modifier.padding(bottom = 7.dp))
                    Row {
                        HarmonyButton("打开设置", { vm.close(); onOpenSettings() }, background = Color.Transparent)
                        if (!state.sending && state.messages.lastOrNull()?.role == HomeComfortRole.USER) HarmonyButton("重试", vm::retry, background = Color.Transparent)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    HarmonyTextField(state.input, vm::input, "想说什么都可以…", Modifier.weight(1f), height = 46.dp, radius = 16.dp, enabled = !state.sending, maxLength = 500, singleLine = true)
                    Spacer(Modifier.width(10.dp))
                    HarmonyButton(if (state.sending) "…" else "发送", vm::send, Modifier.width(68.dp), height = 46.dp, radius = 16.dp, fontSize = 13.sp, bold = true,
                        color = Color.White, background = HarmonyTheme.fill(if (state.input.isNotBlank() && !state.sending) "#7862D3" else "#B9B6C8"), enabled = state.input.isNotBlank() && !state.sending, horizontalPadding = 0.dp)
                }
            }
        }
    }
    if (evidenceOpen) HarmonyModal({ evidenceOpen = false }) {
        Text("溯源依据", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
        Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState())) {
            state.topic?.evidence?.forEach { Text("${it.date} · ${it.content}", fontSize = 13.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(top = 10.dp)) }
        }
        HarmonyButton("关闭", { evidenceOpen = false })
    }
}

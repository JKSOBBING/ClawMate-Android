package com.clawmate.app.chat.ui

import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.clawmate.app.chat.model.*
import com.clawmate.app.ui.*
import com.clawmate.app.ui.theme.HarmonyTheme
import java.text.DateFormat
import java.util.Date

@Composable
internal fun HarmonyChatAction(label: String, onClick: () -> Unit) {
    HarmonyButton(label, onClick, Modifier.border(1.dp, HarmonyTheme.border("#E2E5EA"), RoundedCornerShape(17.dp)),
        height = 34.dp, radius = 17.dp, color = HarmonyTheme.text("#222222"),
        background = HarmonyTheme.surface, horizontalPadding = 13.dp)
}

@Composable
internal fun HarmonyPendingAttachments(items: List<PendingChatAttachment>, enabled: Boolean, onClear: () -> Unit) {
    if (items.isEmpty()) return
    Row(Modifier.padding(start = 22.dp, end = 22.dp, bottom = 8.dp).fillMaxWidth().height(56.dp)
        .background(HarmonyTheme.surface, RoundedCornerShape(18.dp)).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach { attachment ->
                val ref = attachment.reference as? AttachmentReference.ContentUri
                if (ref != null) AsyncImage(ref.uri, attachment.displayName,
                    Modifier.size(44.dp, 48.dp).clip(RoundedCornerShape(9.dp)), contentScale = ContentScale.Crop)
                else Text(attachment.displayName, fontSize = 11.sp, color = HarmonyTheme.secondaryText)
            }
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text("图片附件 x${items.size}", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = HarmonyTheme.primaryText)
            Text("将随下一条消息发送", fontSize = 10.sp, color = HarmonyTheme.text("#7B8392"), modifier = Modifier.padding(top = 2.dp))
        }
        HarmonyButton("×", onClear, Modifier.padding(start = 6.dp).width(40.dp), height = 40.dp, radius = 20.dp,
            fontSize = 18.sp, color = HarmonyTheme.text("#6B7280"), background = HarmonyTheme.background("#F3F4F6"),
            enabled = enabled, horizontalPadding = 0.dp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HarmonyAttachmentSources(onDismiss: () -> Unit, onAlbum: () -> Unit, onCamera: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null, containerColor = HarmonyTheme.surface, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("添加附件", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText,
                    modifier = Modifier.weight(1f))
                HarmonyButton("取消", onDismiss, height = 32.dp, fontSize = 14.sp,
                    background = androidx.compose.ui.graphics.Color.Transparent, color = HarmonyTheme.text("#6B7280"), horizontalPadding = 12.dp)
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(Triple("拍照", "调用相机", onCamera), Triple("相册", "选择图片", onAlbum)).forEach { (title, desc, click) ->
                    Column(Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(18.dp))
                        .background(HarmonyTheme.background("#F8FAFD"))
                        .border(1.dp, HarmonyTheme.border("#E8ECF3"), RoundedCornerShape(18.dp)).clickable(onClick = click),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
                        Text(desc, fontSize = 11.sp, color = HarmonyTheme.text("#7B8392"), modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

/** Only local presentation metadata; message bodies remain in the existing Room store. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun HarmonySessionDrawer(sessions: List<ChatSession>, activeId: String, onClose: () -> Unit,
    onNewSession: (String) -> Unit, onSelect: (String) -> Unit, onDelete: (String) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("chat-presentation", Context.MODE_PRIVATE) }
    var revision by remember { mutableIntStateOf(0) }
    var action by remember { mutableStateOf<ChatSession?>(null) }
    var renaming by remember { mutableStateOf<ChatSession?>(null) }
    var draft by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf(false) }
    revision
    fun title(session: ChatSession) = prefs.getString("title:${session.id}", null) ?: session.displayTitle
    ModalDrawerSheet(Modifier.width(320.dp).fillMaxHeight(), drawerContainerColor = HarmonyTheme.surface,
        drawerShape = RoundedCornerShape(0.dp), windowInsets = WindowInsets(0, 0, 0, 0)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .padding(start = 18.dp, end = 18.dp, top = 26.dp, bottom = 108.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("历史会话", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
                    Text("选择会话后由上层恢复上下文", fontSize = 12.sp, color = HarmonyTheme.text("#6B7280"),
                        modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                HarmonyButton("×", onClose, Modifier.width(40.dp), height = 40.dp, radius = 20.dp, fontSize = 24.sp,
                    color = HarmonyTheme.text("#4B5563"), background = HarmonyTheme.background("#F4F6FA"), horizontalPadding = 0.dp)
            }
            FlowRow(Modifier.fillMaxWidth().padding(top = 18.dp)) {
                listOf(ChatMode.CLOUD_AGENT, ChatMode.LOCAL_INFERENCE).forEach { mode ->
                    HarmonyButton("+ ${ChatMode.label(mode)}", { onNewSession(mode) },
                        Modifier.padding(end = 8.dp, bottom = 8.dp).border(1.dp, HarmonyTheme.border("#E7EAF0"), RoundedCornerShape(18.dp)),
                        height = 36.dp, radius = 18.dp, fontSize = 13.sp, background = HarmonyTheme.background("#F4F6FA"),
                        color = HarmonyTheme.primaryText, horizontalPadding = 12.dp)
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
                listOf(ChatMode.CLOUD_AGENT, ChatMode.LOCAL_INFERENCE).forEach { mode ->
                    val group = sessions.filter { it.mode == mode }.sortedWith(compareByDescending<ChatSession> {
                        prefs.getBoolean("pin:${it.id}", false) }.thenByDescending { it.updatedAt })
                    Row(Modifier.padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(ChatMode.label(mode), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.text("#374151"))
                        HarmonyButton("${group.size}", {}, Modifier.padding(start = 8.dp), height = 22.dp, radius = 11.dp,
                            fontSize = 11.sp, color = HarmonyTheme.text("#6B7280"), background = HarmonyTheme.background("#F3F4F6"), horizontalPadding = 8.dp)
                    }
                    if (group.isEmpty()) Text("暂无会话", fontSize = 12.sp, color = HarmonyTheme.text("#9CA3AF"), modifier = Modifier.padding(bottom = 12.dp))
                    group.forEach { session ->
                        val active = session.id == activeId
                        Column(Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(HarmonyTheme.background(if (active) "#EEF2FF" else "#FFFFFF"))
                            .border(1.dp, HarmonyTheme.border(if (active) "#C7D2FE" else "#EDF0F5"), RoundedCornerShape(16.dp))
                            .combinedClickable(onClick = { onSelect(session.id) }, onLongClick = { action = session }).padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                HarmonyButton(if (mode == ChatMode.LOCAL_INFERENCE) "本" else "云", { onSelect(session.id) },
                                    Modifier.size(26.dp), height = 26.dp, radius = 9.dp, fontSize = 12.sp, bold = true,
                                    color = HarmonyTheme.text("#FFFFFF"), background = HarmonyTheme.fill(if (mode == ChatMode.LOCAL_INFERENCE) "#111827" else "#5968FF"), horizontalPadding = 0.dp)
                                Text(title(session), fontSize = 15.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.weight(1f).padding(start = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, color = HarmonyTheme.primaryText)
                                if (prefs.getBoolean("pin:${session.id}", false)) Text("置顶", fontSize = 10.sp, color = HarmonyTheme.text("#5968FF"))
                            }
                            Text(session.preview, fontSize = 12.sp, color = HarmonyTheme.text("#6B7280"), maxLines = 2,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                            Text("${session.messageCount} 条消息 · ${DateFormat.getDateTimeInstance().format(Date(session.updatedAt))}",
                                fontSize = 11.sp, color = HarmonyTheme.text("#9CA3AF"), maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
    }
    action?.let { selected -> HarmonyModal({ action = null }) {
        Text(title(selected), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
        val pinned = prefs.getBoolean("pin:${selected.id}", false)
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HarmonyButton(if (pinned) "取消置顶" else "置顶", {
                prefs.edit().putBoolean("pin:${selected.id}", !pinned).apply(); revision++; action = null
            }, Modifier.fillMaxWidth(), height = 42.dp)
            HarmonyButton("重命名", { draft = title(selected); renameError = false; renaming = selected; action = null }, Modifier.fillMaxWidth(), height = 42.dp)
            HarmonyButton("删除会话", {
                prefs.edit().remove("pin:${selected.id}").remove("title:${selected.id}").apply()
                onDelete(selected.id); revision++; action = null
            }, Modifier.fillMaxWidth(), height = 42.dp, color = HarmonyTheme.text("#D14343"))
            HarmonyButton("取消", { action = null }, Modifier.fillMaxWidth())
        }
    } }
    renaming?.let { selected -> HarmonyModal({ renaming = null }) {
        Text("重命名会话", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
        HarmonyTextField(draft, { draft = it; renameError = false }, "输入会话名称", Modifier.fillMaxWidth().padding(top = 16.dp),
            height = 48.dp, maxLength = 40, singleLine = true)
        if (renameError) Text("名称不能为空", fontSize = 12.sp, color = HarmonyTheme.text("#D14343"))
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HarmonyButton("取消", { renaming = null })
            HarmonyButton("保存", {
                if (draft.trim().isEmpty()) renameError = true
                else { prefs.edit().putString("title:${selected.id}", draft.trim().take(40)).apply(); revision++; renaming = null }
            })
        }
    } }
}

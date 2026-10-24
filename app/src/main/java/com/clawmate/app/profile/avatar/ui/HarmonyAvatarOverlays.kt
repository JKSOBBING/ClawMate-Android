package com.clawmate.app.profile.avatar.ui

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.clawmate.app.profile.avatar.data.asset.AvatarVersionRecord
import com.clawmate.app.profile.avatar.domain.AvatarGenerationMode
import com.clawmate.app.profile.avatar.domain.AvatarRunState
import com.clawmate.app.ui.*
import com.clawmate.app.ui.theme.HarmonyTheme

/** HomePage.ets AvatarGenerationOverlay: 88%, 18dp corners, 20/18dp insets. */
@Composable
fun HarmonyAvatarGeneration(
    state: ProfileAvatarUiState, referencePhoto: String?, onClose: () -> Unit,
    onPickReference: () -> Unit, onPhotoGenerate: () -> Unit, onGenerate: () -> Unit,
    onHistory: () -> Unit, onManage: () -> Unit,
) {
    val running = state.runState == AvatarRunState.RUNNING
    HarmonyModal(onClose) {
        AvatarOverlayHeader("数字分身", "关闭", onClose)
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Text(when {
                running -> "正在生成数字分身"
                state.generationError != null -> state.generationError
                state.currentAvatar?.provider == "local-import" -> "当前使用本机照片"
                state.currentAvatar != null -> "你的数字分身已更新"
                state.completeness == 0 -> "等待导入数据生成数字分身"
                else -> "可生成数字分身"
            }, fontSize = 14.sp, color = HarmonyTheme.text("#4E5668"), modifier = Modifier.padding(top = 12.dp))
            AvatarOption("画像生成", "使用当前吃穿住行画像生成首页数字分身", "已选择", Modifier.padding(top = 18.dp))
            AvatarOption("照片参考", if (referencePhoto != null) "已选择参考照片，可结合画像生成" else "选择一张个人照片作为形象参考",
                if (referencePhoto != null) "重选" else "选择", Modifier.padding(top = 10.dp), onPickReference, soft = true)
            if (referencePhoto != null) {
                AsyncImage(referencePhoto, "参考照片", contentScale = ContentScale.Fit,
                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(160.dp)
                        .clip(RoundedCornerShape(12.dp)).background(HarmonyTheme.background("#F5F7FB")))
                HarmonyButton(if (running) "生成中..." else "使用照片生成", onPhotoGenerate,
                    Modifier.padding(top = 10.dp).fillMaxWidth(), height = 42.dp, radius = 14.dp, fontSize = 14.sp,
                    color = HarmonyTheme.text("#FFFFFF"), background = HarmonyTheme.fill("#8066FF"), bold = true, enabled = !running)
            }
            AvatarOption("历史记录 / 生成依据", if (state.versionHistory.isEmpty()) "生成后可查看每次的画像证据" else "查看缩略图、模型参数和画像证据",
                "${state.versionHistory.size} 条", Modifier.padding(top = 10.dp), onHistory)
            HarmonyButton(if (running) "生成中..." else if (state.currentAvatar != null) "更新数字分身" else "生成数字分身",
                onGenerate, Modifier.padding(top = 18.dp).fillMaxWidth(), height = 46.dp, radius = 15.dp, fontSize = 15.sp,
                color = HarmonyTheme.text("#FFFFFF"), background = HarmonyTheme.fill(if (running) "#A9B7E9" else "#8066FF"), bold = true, enabled = !running)
            // Android-only utilities remain reachable without replacing the source generation surface.
            HarmonyButton("本地画像与照片管理", onManage, Modifier.padding(top = 10.dp).fillMaxWidth(),
                background = androidx.compose.ui.graphics.Color.Transparent, color = HarmonyTheme.mutedText)
        }
    }
}

@Composable
private fun AvatarOption(title: String, description: String, badge: String, modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null, soft: Boolean = false,
) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(HarmonyTheme.background(if (soft) "#F5F7FB" else "#F8FBFF"))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
            Text(description, fontSize = 12.sp, color = HarmonyTheme.text("#6F7684"), modifier = Modifier.padding(top = 4.dp))
        }
        Text(badge, fontSize = 12.sp, color = HarmonyTheme.text("#8066FF"),
            modifier = Modifier.background(HarmonyTheme.background("#F1EEFF"), RoundedCornerShape(10.dp)).padding(horizontal = 9.dp, vertical = 5.dp))
    }
}

@Composable
internal fun AvatarOverlayHeader(title: String, action: String, onClose: () -> Unit, generated: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, modifier = Modifier.weight(1f))
        if (generated) HarmonyAiBadge(Modifier.padding(end = 12.dp))
        HarmonyButton(action, onClose, fontSize = 13.sp, color = HarmonyTheme.text("#7C8494"),
            background = androidx.compose.ui.graphics.Color.Transparent, horizontalPadding = 0.dp)
    }
}

internal fun avatarVersionMode(record: AvatarVersionRecord): String = when {
    record.provider == "local-import" -> "本机照片"
    record.mode == AvatarGenerationMode.IMAGE_TO_IMAGE -> "照片参考生成"
    else -> "画像生成"
}

@Composable
fun HarmonyAvatarHistory(versions: List<AvatarVersionRecord>, imagePath: (String) -> String?,
    onClose: () -> Unit, onVersion: (AvatarVersionRecord) -> Unit,
) {
    HarmonyModal(onClose, widthFraction = .90f, topPadding = 20.dp) {
        AvatarOverlayHeader("历史记录", "关闭", onClose)
        if (versions.isEmpty()) {
            Text("暂无历史数字分身", fontSize = 14.sp, color = HarmonyTheme.text("#7C8494"), modifier = Modifier.padding(top = 20.dp))
        } else Column(Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
            versions.forEach { item ->
                Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(HarmonyTheme.background("#F8FBFF")).clickable { onVersion(item) }
                    .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val uri = imagePath(item.thumbnailRelativePath.ifBlank { item.imageRelativePath })
                    Box(Modifier.size(64.dp, 82.dp).clip(RoundedCornerShape(12.dp)).background(HarmonyTheme.background("#EEF2F7")), contentAlignment = Alignment.Center) {
                        if (uri != null) {
                            AsyncImage(uri, "历史分身照片", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            if (item.provider != "local-import") HarmonyAiBadge(Modifier.align(Alignment.BottomEnd).padding(3.dp), compact = true)
                        } else Text("无图", fontSize = 12.sp, color = HarmonyTheme.text("#8B93A4"))
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                        Text(item.createdAt.ifBlank { "未知时间" }, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${avatarVersionMode(item)} · ${item.provider}", fontSize = 12.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.model.ifBlank { "未记录模型" }, fontSize = 12.sp, color = HarmonyTheme.mutedText, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
                        Text(if (item.provider == "local-import") "本机导入" else "未记录生成参数", fontSize = 12.sp, color = HarmonyTheme.mutedText, modifier = Modifier.padding(top = 4.dp))
                    }
                    Text(">", fontSize = 18.sp, color = HarmonyTheme.text("#A4ABBA"))
                }
            }
        }
    }
}

@Composable
fun HarmonyAvatarVersion(item: AvatarVersionRecord, imagePath: (String) -> String?, onBack: () -> Unit) {
    HarmonyModal(onBack, widthFraction = .90f, topPadding = 20.dp) {
        AvatarOverlayHeader("生成依据", "返回", onBack, generated = item.provider != "local-import")
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            val uri = imagePath(item.imageRelativePath)
            if (uri != null) Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(220.dp)
                .clip(RoundedCornerShape(14.dp)).background(HarmonyTheme.background("#F5F7FB"))) {
                AsyncImage(uri, "版本分身照片", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                if (item.provider != "local-import") HarmonyAiBadge(Modifier.align(Alignment.BottomEnd).padding(10.dp))
            }
            Text("${item.createdAt.ifBlank { "未知时间" }} · ${avatarVersionMode(item)}", fontSize = 13.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(top = 14.dp))
            Text("${item.provider} / ${item.model.ifBlank { "未记录模型" }}", fontSize = 13.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(top = 6.dp))
            if (item.provider != "local-import") Text("此版本未保存生成参数和授权快照", fontSize = 13.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(top = 6.dp))
            Text("画像摘要", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, modifier = Modifier.padding(top = 18.dp))
            Text(if (item.profileSnapshotHash.isBlank()) "此版本没有画像快照" else "已记录画像摘要标识，未保存各维度证据", fontSize = 13.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(top = 8.dp))
            Text("Prompt", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, modifier = Modifier.padding(top = 18.dp))
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(item.promptSnapshot.ifBlank { "未记录" }, fontSize = 12.sp, color = HarmonyTheme.secondaryText,
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth().background(HarmonyTheme.background("#F8FBFF"), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp))
            }
        }
    }
}

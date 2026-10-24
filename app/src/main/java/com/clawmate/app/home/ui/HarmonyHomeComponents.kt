package com.clawmate.app.home.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.clawmate.app.R
import com.clawmate.app.home.domain.HomeProfileViewModel
import com.clawmate.app.home.domain.HomeDomainViewModel
import com.clawmate.app.ui.theme.HarmonyTheme

@Composable
internal fun HomeCategoryIcon(index: Int, size: Dp, modifier: Modifier = Modifier) {
    val atlas = ImageBitmap.imageResource(R.drawable.home_category_icons)
    val cell = index.coerceIn(0, 11)
    val cx = floatArrayOf(223f, 210f, 191.5f, 178.5f, 180.5f, 150.5f, 210f, 196.5f, 182f, 158.5f, 166.5f, 140.5f)
    val cy = floatArrayOf(202.5f, 216.5f, 221.5f, 210f, 214.5f, 222f, 153f, 148.5f, 150f, 149f, 156f, 163f)
    Canvas(modifier.size(size).clip(CircleShape).background(Color.White)) {
        val edge = this.size.width.toInt()
        drawImage(atlas, srcOffset = IntOffset(cell % 6 * 362, cell / 6 * 362), srcSize = IntSize(362, 362),
            dstOffset = IntOffset(((.5f - cx[cell] / 362) * edge).toInt(), ((.5f - cy[cell] / 362) * edge).toInt()),
            dstSize = IntSize(edge, edge))
    }
}

internal fun homeDomainIcon(domain: String) = when (domain) {
    "eat" -> 0; "wear" -> 7; "live" -> 8; "travel" -> 4; "work" -> 5; else -> 6
}

@Composable
internal fun AiBadge(compact: Boolean = true) {
    Text("AI 生成", fontSize = (if (compact) 10 else 11).sp, fontWeight = FontWeight.Bold,
        color = HarmonyTheme.text("#5B50C7"), maxLines = 1,
        modifier = Modifier.background(HarmonyTheme.background("#EEEAFE"), RoundedCornerShape(7.dp))
            .padding(horizontal = (if (compact) 5 else 7).dp, vertical = (if (compact) 2 else 3).dp))
}

@Composable
internal fun HomeEmptyHint(message: String, onOpen: () -> Unit, link: String = "去派任务", loading: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator(Modifier.size(28.dp), color = HarmonyTheme.text("#5968FF"))
            else Image(painterResource(R.drawable.home_explorer_mascot), "背着小背包、朝手机出发的卡通猫伙伴", Modifier.size(76.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(message, fontSize = 15.sp, lineHeight = 22.sp, color = HarmonyTheme.text("#343A4B"))
            if (!loading) Box(Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpen).padding(end = 8.dp),
                contentAlignment = Alignment.CenterStart) {
                Text("$link ›", fontSize = 13.sp, color = HarmonyTheme.text("#5968FF"))
            }
        }
    }
}

@Composable
internal fun ProfileOverview(profile: HomeProfileViewModel, pending: Int, onOpenAvatar: () -> Unit) {
    var detail by remember { mutableStateOf<HomeDomainViewModel?>(null) }
    val dark = isSystemInDarkTheme()
    BoxWithConstraints(Modifier.fillMaxWidth(.92f).widthIn(max = 720.dp).padding(top = 8.dp)) {
        val wide = maxWidth >= 520.dp
        val shape = RoundedCornerShape(if (wide) 30.dp else 24.dp)
        Box(Modifier.fillMaxWidth().shadow(if (wide) 8.dp else 5.dp, shape, spotColor = Color(0x18000000))
            .clip(shape).background(HarmonyTheme.background("#F4F8FC"))
            .border(1.dp, HarmonyTheme.border("#A8FFFFFF"), shape)) {
            if (!dark) Image(painterResource(if (wide) R.drawable.home_overview_background else R.drawable.home_overview_background_compact),
                null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            Column(Modifier.fillMaxWidth().padding(horizontal = (if (wide) 26 else 18).dp)
                .padding(top = (if (wide) 18 else 20).dp, bottom = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = (if (wide) 24 else 12).dp)) {
                        Text("今日概览", fontSize = (if (wide) 15 else 12).sp, fontWeight = FontWeight.Medium, color = HarmonyTheme.text("#5F63A7"))
                        Text(if (pending == 0) "今天没什么要操心的" else "有 $pending 件事，我帮你记着",
                            fontSize = (if (wide) 28 else 22).sp, lineHeight = (if (wide) 36 else 30).sp, fontWeight = FontWeight.Bold,
                            color = HarmonyTheme.primaryText, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                        Row(Modifier.padding(top = (if (wide) 8 else 12).dp).clickable(onClick = onOpenAvatar), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (profile.empty) "等待导入数据生成数字分身" else profile.avatar.statusLabel,
                                fontSize = (if (wide) 15 else 13).sp, color = HarmonyTheme.text("#7A7395"), modifier = Modifier.weight(1f, false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("›", fontSize = (if (wide) 21 else 19).sp, color = HarmonyTheme.text("#7A7395"), modifier = Modifier.padding(start = 5.dp))
                        }
                    }
                    Column(Modifier.size(if (wide) 82.dp else 70.dp).background(HarmonyTheme.background("#E8FFFFFF"), CircleShape)
                        .border(1.dp, HarmonyTheme.border("#CFFFFFFF"), CircleShape), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(pending.toString(), fontSize = (if (wide) 42 else 36).sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.text("#6F63E9"))
                        Text("待处理", fontSize = (if (wide) 13 else 11).sp, color = HarmonyTheme.secondaryText)
                    }
                }
                HorizontalDivider(Modifier.padding(top = (if (wide) 12 else 18).dp, bottom = (if (wide) 10 else 14).dp), color = HarmonyTheme.border("#A8DDE3EC"))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("画像完整度", fontSize = (if (wide) 15 else 13).sp, color = HarmonyTheme.secondaryText, modifier = Modifier.width(if (wide) 92.dp else 74.dp))
                    LinearProgressIndicator(progress = profile.completeness.coerceIn(0, 100) / 100f,
                        modifier = Modifier.weight(1f).padding(start = 8.dp, end = 10.dp).height(if (wide) 6.dp else 5.dp).clip(CircleShape),
                        color = HarmonyTheme.text("#7669FF"), trackColor = HarmonyTheme.background("#C8E2E5F1"))
                    Text("${profile.completeness}%", fontSize = (if (wide) 20 else 17).sp, fontWeight = FontWeight.Bold,
                        color = HarmonyTheme.text("#7669FF"), modifier = Modifier.width(if (wide) 60.dp else 48.dp))
                }
                Row(Modifier.padding(top = (if (wide) 8 else 13).dp).height(if (wide) 104.dp else 74.dp), verticalAlignment = Alignment.CenterVertically) {
                    profile.domains.take(4).forEachIndexed { index, domain ->
                        Column(Modifier.weight(1f).fillMaxHeight().clickable { detail = domain }.padding(horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            HomeCategoryIcon(homeDomainIcon(domain.domain), if (wide) 40.dp else 30.dp)
                            Text(domain.title, fontSize = (if (wide) 16 else 14).sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, modifier = Modifier.padding(top = 4.dp))
                            Text(domain.desc, fontSize = (if (wide) 12 else 10).sp, color = HarmonyTheme.mutedText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (index < 3) VerticalDivider(Modifier.height(if (wide) 60.dp else 38.dp), color = HarmonyTheme.border)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    AiBadge(compact = !wide)
                    Text("画像摘要由 AI 分析生成", fontSize = (if (wide) 12 else 10).sp, color = HarmonyTheme.mutedText, modifier = Modifier.padding(start = 6.dp).weight(1f), maxLines = 1)
                    Text(profile.updatedLabel, fontSize = (if (wide) 12 else 10).sp, color = HarmonyTheme.mutedText, maxLines = 1)
                }
            }
        }
    }
    detail?.let { item -> AlertDialog(onDismissRequest = { detail = null }, title = { Text(item.title) },
        text = { Text(item.fullDesc) }, confirmButton = { TextButton(onClick = { detail = null }) { Text("知道了") } }) }
}

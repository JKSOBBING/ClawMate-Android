package com.clawmate.app.settings.ui

import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.clawmate.app.ui.*
import com.clawmate.app.ui.theme.HarmonyTheme

@Composable
internal fun SettingsHomeExtras(context: Context, onAuxiliary: (String) -> Unit) {
    var loginUnavailable by remember { mutableStateOf(false) }
    val prefs = remember(context) { context.getSharedPreferences("clawmate-ui", Context.MODE_PRIVATE) }
    val debug = rememberUiDebugEnabled()
    Column(Modifier.fillMaxWidth(.92f).padding(top = 8.dp).background(HarmonyTheme.surface, RoundedCornerShape(20.dp))
        .border(1.dp, HarmonyTheme.border("#E9EDF5"), RoundedCornerShape(20.dp)).padding(14.dp)) {
        HarmonyButton("华为帐号登录", { loginUnavailable = true }, Modifier.fillMaxWidth(), height = 48.dp, radius = 24.dp,
            fontSize = 16.sp, color = Color.White, background = if (isSystemInDarkTheme()) Color(0xFFCF3346) else Color(0xFFCF0A2C))
    }
    Row(Modifier.fillMaxWidth(.92f).padding(top = 12.dp).background(HarmonyTheme.surface, RoundedCornerShape(18.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("开启 Debug", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = HarmonyTheme.primaryText)
            Text("开启后显示并记录聊天和手机 HDC 日志", fontSize = 12.sp, color = HarmonyTheme.text("#7B8290"), modifier = Modifier.padding(top = 4.dp))
        }
        Switch(debug, { value -> setUiDebugEnabled(context, value) },
            colors = SwitchDefaults.colors(checkedTrackColor = HarmonyTheme.background("#4F68FF")))
    }
    SettingsHomeEntry("PC", "桌面端 App", "查看 ClawMate 桌面端下载链接", "#4F68FF", 14.dp) { onAuxiliary("desktop") }
    SettingsHomeEntry("HDC", "手机 HDC", if (debug) "连接设置 · 自控测试 · 手机任务日志" else "连接设置 · 自控测试", "#287D75", 12.dp) { onAuxiliary("hdc") }
    Text("设置与服务", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = HarmonyTheme.text("#7B8290"),
        modifier = Modifier.fillMaxWidth(.92f).padding(top = 22.dp, bottom = 2.dp))
    if (loginUnavailable) HarmonyModal({ loginUnavailable = false }) {
        Text("华为账号", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
        Text("华为账号登录尚未完成 Android 应用配置，当前无法登录。", fontSize = 14.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(vertical = 16.dp))
        HarmonyButton("知道了", { loginUnavailable = false })
    }
}

@Composable
private fun SettingsHomeEntry(badge: String, title: String, description: String, accent: String, margin: Dp, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(.92f).padding(top = margin).background(HarmonyTheme.surface, RoundedCornerShape(18.dp))
        .clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(HarmonyTheme.fill(accent), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Text(badge, fontSize = (if (badge == "HDC") 12 else 14).sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 10.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
            Text(description, fontSize = 12.sp, color = HarmonyTheme.text("#7B8290"), modifier = Modifier.padding(top = 4.dp), maxLines = 2)
        }
        Text("›", fontSize = 28.sp, color = HarmonyTheme.mutedText)
    }
}

@Composable
internal fun SettingsAuxiliaryPage(kind: String, onBack: () -> Unit, onOpenConnections: () -> Unit) {
    val scroll = rememberScrollState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val prefs = remember(context) { context.getSharedPreferences("clawmate-ui", Context.MODE_PRIVATE) }
    var downloadUrl by rememberSaveable { mutableStateOf(prefs.getString("desktop-url", "") ?: "") }
    var extract by rememberSaveable { mutableStateOf(prefs.getString("desktop-extract", "") ?: "") }
    var editingDownload by rememberSaveable { mutableStateOf(downloadUrl.isBlank()) }
    val title = if (kind == "desktop") "桌面端 App" else "手机 HDC"
    var error by remember { mutableStateOf<String?>(null) }
    var port by rememberSaveable { mutableStateOf("") }
    var pairPort by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    fun downloadAction(open: Boolean) {
        val uri = runCatching { java.net.URI(downloadUrl.trim()) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) {
            error = "请输入有效的 HTTPS 下载地址。"; return
        }
        prefs.edit().putString("desktop-url", downloadUrl.trim()).putString("desktop-extract", extract).apply()
        editingDownload = false
        error = null
        if (open) runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(downloadUrl.trim()))) }
            .onFailure { error = "无法打开浏览器，请复制链接后手动访问。" }
        else {
            clipboard.setText(AnnotatedString(downloadUrl.trim()))
            android.widget.Toast.makeText(context, "下载链接已复制", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    androidx.activity.compose.BackHandler(onBack = onBack)
    HarmonyPage(scrollOffset = { scroll.value.toFloat() }, header = { ClawMateHeader(title, onBack = onBack) }) { insets ->
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(insets), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.fillMaxWidth(.92f).background(HarmonyTheme.surface, RoundedCornerShape(20.dp)).padding(18.dp)) {
                if (kind == "desktop") {
                    Text("ClawMate 桌面端 App", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
                    Text("配合移动端完成任务执行与设备自动化", fontSize = 12.sp, color = HarmonyTheme.mutedText, modifier = Modifier.padding(top = 4.dp))
                    Text("下载链接", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText, modifier = Modifier.padding(top = 22.dp))
                    Text(downloadUrl.ifBlank { "尚未配置桌面端下载链接" }, fontSize = 13.sp, color = HarmonyTheme.text("#4F68FF"),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).background(HarmonyTheme.background("#F4F7FF"), RoundedCornerShape(12.dp))
                            .clickable { if (downloadUrl.isNotBlank()) downloadAction(true) else editingDownload = true }.padding(12.dp))
                    Text("提取码：${extract.ifBlank { "未配置" }}", fontSize = 13.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(top = 10.dp))
                    if (editingDownload) {
                        HarmonyTextField(downloadUrl, { downloadUrl = it }, "填写桌面端 HTTPS 下载地址", Modifier.fillMaxWidth().padding(top = 12.dp), height = 42.dp, singleLine = true)
                        HarmonyTextField(extract, { extract = it }, "提取码（可选）", Modifier.fillMaxWidth().padding(top = 8.dp), height = 42.dp, singleLine = true)
                    }
                    Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HarmonyButton("复制链接", { downloadAction(false) }, Modifier.weight(1f), height = 44.dp, radius = 14.dp, fontSize = 14.sp)
                        HarmonyButton("打开下载页面", { downloadAction(true) }, Modifier.weight(1f), height = 44.dp, radius = 14.dp, fontSize = 14.sp,
                            color = Color.White, background = HarmonyTheme.fill("#4F68FF"))
                    }
                } else {
                    Text("手机 HDC 连接", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
                    Text("未连接", fontSize = 13.sp, color = HarmonyTheme.text("#D26B31"), modifier = Modifier.padding(top = 8.dp))
                    Text("无线调试端口", fontSize = 13.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(top = 16.dp))
                    HarmonyTextField(port, { port = it }, "输入连接端口", Modifier.fillMaxWidth().padding(top = 6.dp), height = 42.dp, singleLine = true)
                    Text("配对端口", fontSize = 13.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(top = 12.dp))
                    HarmonyTextField(pairPort, { pairPort = it }, "输入配对端口", Modifier.fillMaxWidth().padding(top = 6.dp), height = 42.dp, singleLine = true)
                    Text("配对码", fontSize = 13.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(top = 12.dp))
                    HarmonyTextField(code, { code = it }, "输入配对码", Modifier.fillMaxWidth().padding(top = 6.dp), height = 42.dp, singleLine = true)
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HarmonyButton("配对", { error = "鸿蒙 HDC 手机直连尚未接入 Android，可在连接设置中使用已有桥接适配器。" }, Modifier.weight(1f), height = 40.dp)
                        HarmonyButton("连接", { error = "鸿蒙 HDC 手机直连尚未接入 Android，可在连接设置中使用已有桥接适配器。" }, Modifier.weight(1f), height = 40.dp)
                    }
                    HarmonyButton("连接设置", onOpenConnections, Modifier.fillMaxWidth().padding(top = 10.dp), height = 40.dp)
                }
                error?.let { Text(it, fontSize = 12.sp, color = HarmonyTheme.text("#B42318"), modifier = Modifier.padding(top = 12.dp)) }
            }
            if (kind == "desktop") Column(Modifier.fillMaxWidth(.92f).padding(top = 14.dp).background(HarmonyTheme.surface, RoundedCornerShape(20.dp)).padding(18.dp)) {
                Text("使用前请确认", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
                Text("1. 在电脑上下载安装并启动 ClawMate 桌面端 App。\n2. 确保手机与电脑的连接配置正常。\n3. 返回移动端，在任务页执行任务或在聊天页点击“下发任务”。", fontSize = 13.sp, lineHeight = 21.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

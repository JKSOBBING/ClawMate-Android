package com.clawmate.app.settings.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.clawmate.app.ui.*
import com.clawmate.app.ui.theme.HarmonyTheme

/** Keeps the Harmony-only entries visible, with honest Android capability boundaries. */
@Composable
internal fun PrivacyHarmonyEntries() {
    var declaration by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(Modifier.fillMaxWidth(.92f).padding(top = 16.dp).background(HarmonyTheme.surface, RoundedCornerShape(20.dp))
            .clickable { declaration = true }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("隐私声明", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = HarmonyTheme.primaryText)
                Text("未选择 · 阅读并管理云端分析授权", fontSize = 12.sp, color = HarmonyTheme.secondaryText, maxLines = 2)
            }
            Text("›", fontSize = 24.sp, color = HarmonyTheme.secondaryText)
        }
    }
    SettingsGroupCard("小艺读取个人记忆", "需要 API 24 或更新系统；可查询已同步的各类记忆摘要") {
        SettingToggle("允许小艺查询个人记忆", "使用已配置的云端 Agent 查询外卖、微信等记忆；需登录及联网；不提供证件号码、详细住址", false) { unavailable = true }
    }
    if (declaration) HarmonyModal({ declaration = false }) {
        Text("隐私声明", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
        Text("Android 端的独立隐私授权联动仍在迁移，当前不能在这里授予或撤回云端授权。\n\n本机记录和模型服务的实际行为以当前功能提示及已配置服务为准；不能据此确认第三方云服务的数据保留策略。",
            fontSize = 14.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(vertical = 16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HarmonyButton("不同意", {}, Modifier.weight(1f), enabled = false)
            HarmonyButton("同意", {}, Modifier.weight(1f), enabled = false)
        }
        HarmonyButton("关闭", { declaration = false })
    }
    if (unavailable) HarmonyModal({ unavailable = false }) {
        Text("小艺个人记忆", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = HarmonyTheme.primaryText)
        Text("此入口依赖鸿蒙系统的小艺服务，Android 适配尚未接入。开关保持关闭。", fontSize = 14.sp, color = HarmonyTheme.secondaryText, modifier = Modifier.padding(vertical = 16.dp))
        HarmonyButton("知道了", { unavailable = false })
    }
}

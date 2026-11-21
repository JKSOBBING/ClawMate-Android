package com.clawmate.platform.gui

import android.content.Context
import com.wangzan101.mika.core.access.AppAccessState

internal object MikaAppAccess {
    private const val PREFS = "mika-app-access"
    private const val BLOCKED = "blocked-packages"

    fun current(context: Context): AppAccessState = runCatching {
        AppAccessState(blocked = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(BLOCKED, emptySet()).orEmpty().toSet())
    }.getOrElse { AppAccessState(available = false) }

    fun saveBlocked(context: Context, blocked: Set<String>) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(BLOCKED, blocked).commit()) { "无法保存 Mika 应用访问规则" }
    }

    fun requireAllowed(context: Context, target: String) {
        when (current(context).denial(target)?.code) {
            null -> Unit
            "app_blocked" -> error("此应用已在「Mika 应用访问」中禁用：$target")
            else -> error("无法读取 Mika 应用访问规则，请在设置中修复后重试")
        }
    }
}

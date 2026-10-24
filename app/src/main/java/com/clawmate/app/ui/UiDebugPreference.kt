package com.clawmate.app.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.clawmate.app.log.AppLogger

fun setUiDebugEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences("clawmate-ui", Context.MODE_PRIVATE).edit().putBoolean("debug", enabled).apply()
    AppLogger.debugLoggingEnabled = enabled
}

@Composable
fun rememberUiDebugEnabled(): Boolean {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("clawmate-ui", Context.MODE_PRIVATE) }
    var enabled by remember(prefs) { mutableStateOf(prefs.getBoolean("debug", false)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "debug") enabled = prefs.getBoolean(key, false) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled
}

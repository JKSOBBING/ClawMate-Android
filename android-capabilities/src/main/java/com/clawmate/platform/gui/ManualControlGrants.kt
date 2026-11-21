package com.clawmate.platform.gui

import android.content.Context

/** Local authorization for gestures initiated by the user on the manual control panel. */
class ManualControlGrants(context: Context) {
    private val prefs = context.getSharedPreferences("manual-control-grants", Context.MODE_PRIVATE)
    val allSelected: Boolean get() = prefs.getBoolean("all-selected", false)
    val packages: List<String> get() = prefs.all.keys.filter { it.startsWith("app:") }.map { it.removePrefix("app:") }

    fun allows(target: String): Boolean = allSelected || prefs.contains("app:$target")

    fun setAllSelected(allow: Boolean) {
        prefs.edit().putBoolean("all-selected", allow).apply()
    }

    fun setAllowed(target: String, allow: Boolean) {
        val editor = prefs.edit()
        if (allow) editor.putBoolean("app:$target", true) else editor.remove("app:$target")
        editor.apply()
    }
}

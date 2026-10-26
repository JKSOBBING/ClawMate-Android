package com.clawmate.shared

import android.os.Bundle
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.WindowManager
import android.content.res.Configuration
import ohos.stage.ability.adapter.StageActivity

class SharedActivity : StageActivity() {
    private lateinit var platform: PlatformBridge

    override fun onCreate(state: Bundle?) {
        platform = PlatformBridge(this)
        setInstanceName("com.clawmate.shared:entry:EntryAbility:")
        super.onCreate(state)
        updateSystemBars()
    }

    internal fun updateSystemBars() {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val color = if (dark) Color.rgb(17, 21, 29) else Color.WHITE
        if ((window.decorView.background as? ColorDrawable)?.color != color) {
            window.decorView.setBackgroundColor(color)
        }
        val translucent = WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
        if (window.attributes.flags and translucent != 0) window.clearFlags(translucent)
        if (window.attributes.flags and WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS == 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        }
        if (window.statusBarColor != color) window.statusBarColor = color
        if (window.navigationBarColor != color) window.navigationBarColor = color
        val mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val flags = (window.decorView.systemUiVisibility and mask.inv()) or if (dark) 0 else mask
        if (window.decorView.systemUiVisibility != flags) window.decorView.systemUiVisibility = flags
    }

    override fun onConfigurationChanged(configuration: Configuration) {
        super.onConfigurationChanged(configuration)
        updateSystemBars()
    }

    override fun onStop() { platform.ttsStop(); super.onStop() }
    override fun onDestroy() { platform.close(); super.onDestroy() }
}
